#!/usr/bin/env python3
import argparse
import copy
import csv
import json
import re
import shutil
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from bncfu_perf_explore import (
    HardwarePreset,
    hardware_presets,
    run_command,
    sim_command,
)


VEXII_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = VEXII_ROOT.parents[1]
SW = VEXII_ROOT / "sw"
DEFAULT_OUT_ROOT = VEXII_ROOT / "benchmark_results"


@dataclass(frozen=True)
class LlamaCase:
    name: str
    bin_path: Path
    quantized_weights: bool = True


@dataclass(frozen=True)
class SoftwareVariant:
    name: str
    opt: str
    extra_cflags: Tuple[str, ...]
    requires_bncfu_hw: bool = False
    notes: str = ""


@dataclass(frozen=True)
class BenchmarkCorner:
    name: str
    prefill_len: int
    decode_context_len: int
    case: str


VARIANTS: Dict[str, SoftwareVariant] = {
    "fp32_kv": SoftwareVariant(
        name="fp32_kv",
        opt="",
        extra_cflags=(),
        notes="Quantized LLaMa matmuls with normal FP32 KV attention.",
    ),
    "kivi_generic": SoftwareVariant(
        name="kivi_generic",
        opt="",
        extra_cflags=("-DUSE_KIVI_KV",),
        notes="Generic C groupwise KIVI KV attention.",
    ),
    "kivi_generic_profile": SoftwareVariant(
        name="kivi_generic_profile",
        opt="",
        extra_cflags=("-DUSE_KIVI_KV", "-DKIVI_PROFILE_INTERNAL"),
        notes="Generic C groupwise KIVI KV attention with internal kernel profile prints.",
    ),
    "kivi_bncfu": SoftwareVariant(
        name="kivi_bncfu",
        opt="bncfu",
        extra_cflags=("-DUSE_KIVI_KV",),
        requires_bncfu_hw=True,
        notes="BNCFU accelerated groupwise KIVI KV attention.",
    ),
    "kivi_bncfu_profile": SoftwareVariant(
        name="kivi_bncfu_profile",
        opt="bncfu",
        extra_cflags=("-DUSE_KIVI_KV", "-DKIVI_PROFILE_INTERNAL"),
        requires_bncfu_hw=True,
        notes="BNCFU accelerated groupwise KIVI KV attention with internal kernel profile prints.",
    ),
    "kivi_bncfu_verify": SoftwareVariant(
        name="kivi_bncfu_verify",
        opt="bncfu",
        extra_cflags=("-DUSE_KIVI_KV", "-DKIVI_PROFILE_INTERNAL", "-DKIVI_BNCFU_INT8_VERIFY"),
        requires_bncfu_hw=True,
        notes="BNCFU KIVI correctness gate with scalar BDOT verification.",
    ),
}


MODEL_ALIASES = {
    "llama3m_w2a8": "llama2/llama_3M_W2A8.bin",
    "llama3m_w8a8": "llama2/llama_3M_W8A8.bin",
    "llama28m_w2a8": "llama2/llama_28M_W2A8.bin",
    "llama28m_w8a8": "llama2/llama_28M_W8A8.bin",
}


ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
SBT_PREFIX_RE = re.compile(r"^\s*\[(?:info|warn|error|success)\]\s*")

PROFILE_RE = re.compile(
    r"\b(?P<name>PREFILL_PROFILE|PREFILL_PER_TOKEN_PROFILE|DECODE_PROFILE|DECODE_PER_TOKEN_PROFILE|TOTAL_ESTIMATE) "
    r"total=(?P<total>\d+) qmatmul=(?P<qmatmul>\d+) "
    r"quant=(?P<quant>\d+) fmatmul=(?P<fmatmul>\d+) attention=(?P<attention>\d+) "
    r"softmax=(?P<softmax>\d+) rmsnorm=(?P<rmsnorm>\d+) rope=(?P<rope>\d+) "
    r"kv_quant=(?P<kv_quant>\d+) swiglu=(?P<swiglu>\d+) residual=(?P<residual>\d+) "
    r"lm_head=(?P<lm_head>\d+)\b",
)

KIVI_PROFILE_RE = re.compile(
    r"\b(?:LLAMA_GROUPWISE_KV_PROFILE|LLAMA_KIVI_BNCFU_PROFILE) "
    r"total=\d+ q_quant=\d+ hist_score=\d+ float_score=\d+ softmax=\d+ "
    r"hist_output=\d+ float_output=\d+ group_size=\d+\b"
)


def split_csv(text: str) -> List[str]:
    return [part.strip() for part in text.split(",") if part.strip()]


def normalize_sim_text(text: str) -> str:
    """Drop simulator ANSI control codes and sbt log prefixes before parsing."""
    text = ANSI_RE.sub("", text)
    lines = []
    for line in text.splitlines():
        line = SBT_PREFIX_RE.sub("", line).strip()
        if line:
            lines.append(line)
    return "\n".join(lines)


def resolve_model_path(name_or_path: str) -> Path:
    aliased = MODEL_ALIASES.get(name_or_path, name_or_path)
    path = Path(aliased)
    if not path.is_absolute():
        if (SW / path).exists():
            return SW / path
        return REPO_ROOT / path
    return path


def make_cases(args: argparse.Namespace) -> List[LlamaCase]:
    cases: List[LlamaCase] = []
    for raw_name in split_csv(args.models):
        bin_path = resolve_model_path(raw_name)
        name = raw_name
        if raw_name in MODEL_ALIASES:
            name = raw_name
        else:
            name = bin_path.stem
        cases.append(LlamaCase(name=name, bin_path=bin_path, quantized_weights=not args.no_quantized_weights))
    return cases


def select_variants(text: str) -> List[SoftwareVariant]:
    selected: List[SoftwareVariant] = []
    for name in split_csv(text):
        if name not in VARIANTS:
            raise SystemExit(f"Unknown variant {name}. Choices: {', '.join(VARIANTS)}")
        selected.append(VARIANTS[name])
    return selected


def select_hardware(args: argparse.Namespace) -> List[HardwarePreset]:
    hws = hardware_presets(args.hardware_preset)
    if args.only_hardware:
        allow = set(args.only_hardware)
        hws = [hw for hw in hws if hw.name in allow]
    if not hws:
        raise SystemExit("No hardware presets selected")
    return hws


def ensure_preflight(cases: Sequence[LlamaCase], dry_run: bool) -> None:
    missing = []
    if not (SW / "Makefile").exists():
        missing.append(str(SW / "Makefile"))
    if not (SW / "llama2_perf_est.c").exists():
        missing.append(str(SW / "llama2_perf_est.c"))
    if not (SW / "MiCo-Lib").exists():
        missing.append(str(SW / "MiCo-Lib"))
    for case in cases:
        if not case.bin_path.exists():
            missing.append(str(case.bin_path))
    if missing and not dry_run:
        joined = "\n  ".join(missing)
        raise FileNotFoundError(f"Missing required files:\n  {joined}")


def llama_bin_define_path(path: Path) -> str:
    resolved = path.resolve()
    try:
        return "./" + str(resolved.relative_to(SW.resolve()))
    except ValueError:
        return str(resolved)


def attention_pos_for_case(length: int, group_size: int, case_name: str) -> Optional[int]:
    if case_name == "legacy":
        return None
    max_pos = max(0, length - 1)
    if case_name == "best":
        residue = 0
    elif case_name == "mid":
        residue = max(0, group_size // 2 - 1)
    elif case_name == "worst":
        residue = group_size - 1
    else:
        raise ValueError(f"Unsupported attention case: {case_name}")
    if max_pos < residue:
        return max_pos
    return max_pos - ((max_pos - residue) % group_size)


def attention_positions(args: argparse.Namespace) -> Tuple[Optional[int], Optional[int]]:
    return (
        attention_pos_for_case(args.prefill_len, args.kv_group_size, args.case),
        attention_pos_for_case(args.decode_context_len, args.kv_group_size, args.case),
    )


def benchmark_corners(args: argparse.Namespace) -> List[BenchmarkCorner]:
    if args.benchmark_preset == "single":
        return [
            BenchmarkCorner(
                name=f"l{args.prefill_len}_{args.case}",
                prefill_len=args.prefill_len,
                decode_context_len=args.decode_context_len,
                case=args.case,
            )
        ]
    if args.benchmark_preset == "standard_ablation":
        corners: List[BenchmarkCorner] = []
        for length in (64, 128, 192):
            for case_name in ("best", "worst"):
                corners.append(
                    BenchmarkCorner(
                        name=f"l{length}_{case_name}",
                        prefill_len=length,
                        decode_context_len=length,
                        case=case_name,
                    )
                )
        return corners
    raise ValueError(f"Unsupported benchmark preset: {args.benchmark_preset}")


def args_for_corner(args: argparse.Namespace, corner: BenchmarkCorner) -> argparse.Namespace:
    corner_args = copy.copy(args)
    corner_args.prefill_len = corner.prefill_len
    corner_args.decode_context_len = corner.decode_context_len
    corner_args.case = corner.case
    corner_args.corner_name = corner.name
    return corner_args


def case_id(case_name: str) -> int:
    return {"legacy": 0, "best": 1, "mid": 2, "worst": 3}[case_name]


def artifact_variant_name(variant: SoftwareVariant, args: argparse.Namespace) -> str:
    corner_name = getattr(args, "corner_name", "")
    if corner_name:
        return f"{variant.name}_{corner_name}"
    if args.case == "legacy":
        return variant.name
    return f"{variant.name}_{args.case}"


def should_run_variant_on_hw(hw: HardwarePreset, variant: SoftwareVariant, compare_mode: str) -> bool:
    if compare_mode == "ablation":
        return ablation_path_name(hw, variant) is not None
    if compare_mode == "cross_hw_accel":
        if hw.backend == "bncfu":
            return variant.name in ("fp32_kv", "kivi_bncfu")
        return variant.name == "fp32_kv"
    if compare_mode == "baseline_vs_bncfu":
        if hw.enable_bncfu:
            return variant.name == "kivi_bncfu"
        return variant.name == "kivi_generic"
    if variant.requires_bncfu_hw and not hw.enable_bncfu:
        return False
    return True


def ablation_path_name(hw: HardwarePreset, variant: SoftwareVariant) -> Optional[str]:
    if not hw.enable_bncfu and variant.name == "fp32_kv":
        return "baseline_fp32_kv"
    if "noquant" in hw.name and variant.name == "fp32_kv":
        return "bncfu256_noquant_fp32_kv"
    if "noquant" in hw.name and variant.name == "kivi_bncfu":
        return "bncfu256_noquant_kivi_kv"
    if "quant64" in hw.name and variant.name == "kivi_bncfu":
        return "bncfu256_quant64_kivi_kv"
    return None


def accel_path_name(hw: HardwarePreset, variant: SoftwareVariant) -> str:
    if hw.backend == "bncfu" and variant.name == "fp32_kv":
        return "bncfu256_quant64_fp32_kv"
    if hw.backend == "bncfu" and variant.name == "kivi_bncfu":
        return "bncfu256_quant64_kivi_kv"
    if hw.backend == "bnrv" and variant.name == "fp32_kv":
        return "bnrv32_quant_matmul_fp32_kv"
    if hw.backend == "mico" and variant.name == "fp32_kv":
        return "mico32_quant_matmul_fp32_kv"
    if hw.backend == "cfuvpu" and variant.name == "fp32_kv":
        return "cfuvpu256_quant_matmul_fp32_kv"
    return f"{hw.name}_{variant.name}"


def opt_for_hw_variant(hw: HardwarePreset, variant: SoftwareVariant, args: argparse.Namespace) -> str:
    opt = variant.opt
    if args.compare_mode in ("ablation", "all_selected") and hw.enable_bncfu and variant.name == "fp32_kv":
        return "bncfu"
    if args.compare_mode == "cross_hw_accel":
        if hw.backend == "bncfu":
            return "bncfu"
        if hw.backend == "bnrv":
            return "bnrv"
        if hw.backend == "mico":
            return "simd"
        if hw.backend == "cfuvpu":
            return "cfu"
    return opt


def make_vars(case: LlamaCase, hw: HardwarePreset, variant: SoftwareVariant, args: argparse.Namespace) -> Dict[str, str]:
    cflags: List[str] = []
    if case.quantized_weights:
        cflags.append("-DQUANTIZED")
    cflags.extend(variant.extra_cflags)
    prefill_attention_pos, decode_attention_pos = attention_positions(args)
    cflags.extend([
        f"-DPREFILL_LEN={args.prefill_len}",
        f"-DDECODE_STEPS={args.decode_steps}",
        f"-DDECODE_CONTEXT_LEN={args.decode_context_len}",
        f"-DPREFILL_LM_HEADS={args.prefill_lm_heads}",
        f"-DPROFILE_REPEATS={args.profile_repeats}",
        f"-DMICO_LLAMA_KV_GROUP_SIZE={args.kv_group_size}",
        f"-DATTENTION_CASE_ID={case_id(args.case)}",
    ])
    if prefill_attention_pos is not None:
        cflags.append(f"-DPREFILL_ATTENTION_POS={prefill_attention_pos}")
    if decode_attention_pos is not None:
        cflags.append(f"-DDECODE_ATTENTION_POS={decode_attention_pos}")
    cflags.extend(args.extra_cflag or ())

    opt = opt_for_hw_variant(hw, variant, args)

    vars: Dict[str, str] = {
        "MAIN": "llama2_perf_est",
        "BUILD": f"build_llama_{case.name}_{hw.name}_{artifact_variant_name(variant, args)}",
        "TARGET": "vexii_soc",
        "MARCH": hw.march,
        "SPRAM": "1",
        "TEST_NUM": "1",
        "VLEN": str(hw.vlen),
        "BITNET_QUANT": str(hw.bitnet_quant),
        "USE_SIMD": str(hw.use_simd),
        "LLAMA2_BIN": llama_bin_define_path(case.bin_path),
        "EXTRA_CFLAGS": " ".join(cflags),
    }
    if opt:
        vars["OPT"] = opt
    if opt and "bncfu" in opt.split():
        vars.update({
            "BNCFU_REG_DEPTH": str(hw.reg_depth),
            "BNCFU_Q2T": "1" if hw.with_q2t else "0",
            "BNCFU_Q8": "1" if hw.with_q8 else "0",
            "BNCFU_QUANT_WIDTH": str(hw.quant_width),
        })
    return vars


def build_elf(
    case: LlamaCase,
    hw: HardwarePreset,
    variant: SoftwareVariant,
    out_dir: Path,
    args: argparse.Namespace,
) -> Tuple[Path, int]:
    artifact_variant = artifact_variant_name(variant, args)
    elf_path = out_dir / "elfs" / f"{case.name}__{hw.name}__{artifact_variant}.elf"
    if elf_path.exists() and not args.force_build:
        print(f"[skip] build {case.name}/{hw.name}/{variant.name}: {elf_path}")
        return elf_path, 0

    vars = make_vars(case, hw, variant, args)
    log_path = out_dir / "logs" / case.name / hw.name / artifact_variant / "build.log"
    clean_cmd = ["make", "-C", str(SW), "-f", "Makefile", "clean"]
    clean_cmd += [f"{key}={value}" for key, value in vars.items()]
    rc = run_command(clean_cmd, log_path.with_name("clean.log"), REPO_ROOT, None, args.dry_run)
    if rc != 0:
        return elf_path, rc
    cmd = ["make", "-C", str(SW), "-f", "Makefile", "compile"]
    cmd += [f"{key}={value}" for key, value in vars.items()]
    rc = run_command(cmd, log_path, REPO_ROOT, None, args.dry_run)
    if rc == 0 and not args.dry_run:
        built = SW / "llama2_perf_est.elf"
        if not built.exists():
            raise FileNotFoundError(f"Build succeeded but ELF is missing: {built}")
        elf_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(built, elf_path)
    return elf_path, rc


def simulate(
    case: LlamaCase,
    hw: HardwarePreset,
    variant: SoftwareVariant,
    elf: Path,
    out_dir: Path,
    args: argparse.Namespace,
) -> Tuple[Path, int]:
    log_path = out_dir / "logs" / case.name / hw.name / artifact_variant_name(variant, args) / "sim.log"
    if log_path.exists() and not args.force_sim:
        text = log_path.read_text(errors="ignore")
        if "[success]" in text or "[Done] Simulation done" in text:
            print(f"[skip] sim {case.name}/{hw.name}/{variant.name}: {log_path}")
            return log_path, 0
    return log_path, run_command(sim_command(hw, elf), log_path, VEXII_ROOT, None, args.dry_run)


def parse_profiles(text: str) -> Dict[str, Dict[str, int]]:
    profiles: Dict[str, Dict[str, int]] = {}
    for match in PROFILE_RE.finditer(text):
        profiles[match.group("name")] = {
            key: int(value)
            for key, value in match.groupdict().items()
            if key != "name"
        }
    return profiles


def parse_first_int(pattern: str, text: str) -> Optional[int]:
    match = re.search(pattern, text)
    return int(match.group(1)) if match else None


def parse_raw_field(phase: str, field: str, text: str) -> Optional[int]:
    match = re.search(rf"{phase}_RAW\b[^\n]*\b{field}=(\d+)", text)
    return int(match.group(1)) if match else None


def divide_or_none(numerator: Optional[float], denominator: Optional[float]) -> Optional[float]:
    if numerator is None or denominator is None or denominator == 0:
        return None
    return numerator / denominator


PROFILE_COMPONENTS = (
    "qmatmul",
    "quant",
    "fmatmul",
    "attention",
    "softmax",
    "rmsnorm",
    "rope",
    "kv_quant",
    "swiglu",
    "residual",
    "lm_head",
)


def profile_metrics(prefix: str, profile: Dict[str, int]) -> Dict[str, object]:
    metrics: Dict[str, object] = {}
    total = profile.get("total")
    bitops = profile.get("qmatmul", 0) + profile.get("fmatmul", 0)
    attn = profile.get("attention")
    other = None
    if total is not None:
        other = total - bitops - (attn or 0)
        if other < 0:
            other = None

    metrics[f"{prefix}_cycles"] = total
    metrics[f"{prefix}_bitops_cycles"] = bitops or None
    metrics[f"{prefix}_attn_cycles"] = attn
    metrics[f"{prefix}_other_cycles"] = other
    if isinstance(total, int):
        total_float = float(total)
        metrics[f"{prefix}_bitops_pct"] = divide_or_none(bitops, total_float)
        metrics[f"{prefix}_attn_pct"] = divide_or_none(attn, total_float)
        metrics[f"{prefix}_other_pct"] = divide_or_none(other, total_float)
        for component in PROFILE_COMPONENTS:
            metrics[f"{prefix}_{component}_cycles"] = profile.get(component)
            metrics[f"{prefix}_{component}_pct"] = divide_or_none(profile.get(component), total_float)
    else:
        for component in PROFILE_COMPONENTS:
            metrics[f"{prefix}_{component}_cycles"] = profile.get(component)
            metrics[f"{prefix}_{component}_pct"] = None
    return metrics


def parse_log(
    case: LlamaCase,
    hw: HardwarePreset,
    variant: SoftwareVariant,
    run_args: argparse.Namespace,
    log_path: Path,
    build_rc: Optional[int],
    sim_rc: Optional[int],
) -> Dict[str, object]:
    raw_text = log_path.read_text(errors="ignore") if log_path.exists() else ""
    text = normalize_sim_text(raw_text)
    profiles = parse_profiles(text)
    total = profiles.get("TOTAL_ESTIMATE", {})
    prefill = profiles.get("PREFILL_PROFILE", {})
    decode = profiles.get("DECODE_PROFILE", {})
    prefill_per_token = profiles.get("PREFILL_PER_TOKEN_PROFILE", {})
    decode_per_token = profiles.get("DECODE_PER_TOKEN_PROFILE", {})
    bitops_cycles = total.get("qmatmul", 0) + total.get("fmatmul", 0)
    attn_cycles = total.get("attention")
    primary_cycles = total.get("total")
    other_cycles = None
    if primary_cycles is not None:
        other_cycles = primary_cycles - bitops_cycles - (attn_cycles or 0)
        if other_cycles < 0:
            other_cycles = None

    matmul_samples = re.findall(
        r"MATMUL_SAMPLE n=(\d+) d=(\d+) wq=(\d+) aq=(\d+) total=(\d+) qmatmul=(\d+) quant=(\d+) fmatmul=(\d+)",
        text,
    )
    kivi_profiles = KIVI_PROFILE_RE.findall(text)
    mismatch = bool(re.search(r"BDOT_MISMATCH|MISMATCH|assert", text, re.IGNORECASE))
    kv_group_size = args_kv_group_size_from_log(text)
    prefill_attention_pos = parse_raw_field("PREFILL", "attention_pos", text)
    decode_attention_pos = parse_raw_field("DECODE", "attention_pos", text)

    row: Dict[str, object] = {
        "model": case.name,
        "llama_bin": str(case.bin_path),
        "software_corner": getattr(run_args, "corner_name", ""),
        "variant": variant.name,
        "ablation_path": ablation_path_name(hw, variant) if run_args.compare_mode == "ablation" else "",
        "accel_path": accel_path_name(hw, variant) if run_args.compare_mode == "cross_hw_accel" else "",
        "hardware": hw.name,
        "hardware_backend": hw.backend,
        "enable_bncfu_hw": hw.enable_bncfu,
        "fpu": hw.fpu,
        "minimal_core": hw.minimal_core,
        "use_simd": hw.use_simd,
        "bitnet_version": hw.bitnet_version,
        "vlen": hw.vlen,
        "width": hw.width,
        "quant_width": hw.quant_width,
        "reg_depth": hw.reg_depth,
        "with_q2t": hw.with_q2t,
        "with_q8": hw.with_q8,
        "rf_sync": hw.rf_sync if hw.enable_bncfu else "",
        "sim_backend_args": " ".join(hw.sim_args),
        "prefill_len": parse_first_int(r"PREFILL_LEN=(\d+)", text),
        "decode_steps": parse_first_int(r"DECODE_STEPS=(\d+)", text),
        "decode_context_len": parse_first_int(r"DECODE_CONTEXT_LEN=(\d+)", text),
        "attention_case": run_args.case,
        "attention_case_id": parse_first_int(r"ATTENTION_CASE_ID=(\d+)", text),
        "prefill_attention_pos": prefill_attention_pos,
        "decode_attention_pos": decode_attention_pos,
        "prefill_kv_quant_events": parse_raw_field("PREFILL", "kv_quant_events", text),
        "decode_kv_quant_events": parse_raw_field("DECODE", "kv_quant_events", text),
        "kv_group_size": kv_group_size,
        "prefill_float_tokens": (prefill_attention_pos % kv_group_size + 1) if isinstance(prefill_attention_pos, int) and isinstance(kv_group_size, int) else None,
        "decode_float_tokens": (decode_attention_pos % kv_group_size + 1) if isinstance(decode_attention_pos, int) and isinstance(kv_group_size, int) else None,
        "build_rc": build_rc,
        "sim_rc": sim_rc,
        "primary_cycles": primary_cycles,
        "prefill_cycles": prefill.get("total"),
        "decode_cycles": decode.get("total"),
        "prefill_per_token_cycles": prefill_per_token.get("total"),
        "decode_per_token_cycles": decode_per_token.get("total"),
        "bitops_cycles": bitops_cycles or None,
        "attn_cycles": attn_cycles,
        "other_cycles": other_cycles,
        "qmatmul_cycles": total.get("qmatmul"),
        "fmatmul_cycles": total.get("fmatmul"),
        "quant_cycles": total.get("quant"),
        "kv_quant_cycles": total.get("kv_quant"),
        "softmax_cycles": total.get("softmax"),
        "rmsnorm_cycles": total.get("rmsnorm"),
        "rope_cycles": total.get("rope"),
        "swiglu_cycles": total.get("swiglu"),
        "residual_cycles": total.get("residual"),
        "lm_head_cycles": total.get("lm_head"),
        "matmul_sample_count": len(matmul_samples),
        "kivi_profile_line_count": len(kivi_profiles),
        "last_kivi_profile": kivi_profiles[-1] if kivi_profiles else "",
        "mismatch_or_assert": mismatch,
        "log": str(log_path.relative_to(out_dir_base(log_path))) if log_path.exists() else str(log_path),
    }
    row.update(profile_metrics("total", total))
    row.update(profile_metrics("prefill", prefill))
    row.update(profile_metrics("decode", decode))
    row.update(profile_metrics("prefill_per_token", prefill_per_token))
    row.update(profile_metrics("decode_per_token", decode_per_token))
    model_match = re.search(
        r"MODEL_CONFIG dim=(\d+) hidden_dim=(\d+) n_layers=(\d+) n_heads=(\d+) n_kv_heads=(\d+) vocab_size=(\d+) seq_len=(\d+)",
        text,
    )
    if model_match:
        for key, value in zip(("dim", "hidden_dim", "n_layers", "n_heads", "n_kv_heads", "vocab_size", "seq_len"), model_match.groups()):
            row[key] = int(value)
    if isinstance(primary_cycles, int):
        total_float = float(primary_cycles)
        row["bitops_pct"] = divide_or_none(row.get("bitops_cycles"), total_float)
        row["attn_pct"] = divide_or_none(row.get("attn_cycles"), total_float)
        row["other_pct"] = divide_or_none(row.get("other_cycles"), total_float)
    return row


def args_kv_group_size_from_log(text: str) -> Optional[int]:
    kv_group_size = parse_first_int(r"KV_GROUP_SIZE=(\d+)", text)
    if kv_group_size is not None:
        return kv_group_size
    values = [int(v) for v in re.findall(r"group_size=(\d+)", text)]
    return values[-1] if values else None


def out_dir_base(path: Path) -> Path:
    parts = path.parts
    if "logs" in parts:
        idx = parts.index("logs")
        return Path(*parts[:idx])
    return path.parent


def add_speedups(rows: List[Dict[str, object]]) -> None:
    same_hw_baseline: Dict[Tuple[object, object], float] = {}
    baseline_fpu: Dict[Tuple[object, object], float] = {}
    ablation_baseline: Dict[Tuple[object, object, object], float] = {}
    cross_hw_bncfu: Dict[Tuple[object, object], float] = {}
    for row in rows:
        cycles = row.get("primary_cycles")
        if row.get("variant") == "kivi_generic" and isinstance(cycles, int):
            same_hw_baseline[(row.get("model"), row.get("software_corner"), row.get("hardware"))] = float(cycles)
            if row.get("hardware") == "baseline_fpu":
                baseline_fpu[(row.get("model"), row.get("software_corner"))] = float(cycles)
        if row.get("ablation_path") == "baseline_fp32_kv" and isinstance(cycles, int):
            ablation_baseline[(row.get("model"), row.get("software_corner"), row.get("minimal_core"))] = float(cycles)
        if row.get("hardware_backend") == "bncfu" and isinstance(cycles, int):
            cross_hw_bncfu[(row.get("model"), row.get("software_corner"))] = float(cycles)
    for row in rows:
        cycles = row.get("primary_cycles")
        base = same_hw_baseline.get((row.get("model"), row.get("software_corner"), row.get("hardware")))
        row["speedup_vs_same_hw_kivi_generic"] = base / float(cycles) if isinstance(cycles, int) and base and cycles > 0 else None
        base_fpu = baseline_fpu.get((row.get("model"), row.get("software_corner")))
        row["speedup_vs_baseline_fpu_kivi"] = base_fpu / float(cycles) if isinstance(cycles, int) and base_fpu and cycles > 0 else None
        ablation_base = ablation_baseline.get((row.get("model"), row.get("software_corner"), row.get("minimal_core")))
        row["speedup_vs_ablation_baseline"] = ablation_base / float(cycles) if isinstance(cycles, int) and ablation_base and cycles > 0 else None
        cross_base = cross_hw_bncfu.get((row.get("model"), row.get("software_corner")))
        row["speedup_vs_cross_hw_bncfu"] = cross_base / float(cycles) if isinstance(cycles, int) and cross_base and cycles > 0 else None


def write_csv(rows: List[Dict[str, object]], path: Path) -> None:
    keys: List[str] = []
    for row in rows:
        for key in row:
            if key not in keys:
                keys.append(key)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def fmt(value: object, digits: int = 2) -> str:
    if value is None or value == "":
        return "-"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def fmt_pct(value: object, digits: int = 1) -> str:
    if value is None or value == "":
        return "-"
    if isinstance(value, (float, int)):
        return f"{float(value) * 100.0:.{digits}f}%"
    return str(value)


def write_summary(rows: List[Dict[str, object]], path: Path) -> None:
    lines = [
        "# BNCFU E2E LLaMa Benchmark Summary",
        "",
        "| model | corner | hardware | variant | path | cycles | prefill/token | decode/token | ablation speedup | total bitops% | total attn% | prefill attn% | decode attn% | mismatch | rc |",
        "| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |",
    ]
    for row in rows:
        lines.append(
            "| "
            + " | ".join([
                str(row.get("model")),
                str(row.get("software_corner") or "-"),
                str(row.get("hardware")),
                str(row.get("variant")),
                str(row.get("ablation_path") or row.get("accel_path") or "-"),
                fmt(row.get("primary_cycles"), 0),
                fmt(row.get("prefill_per_token_cycles"), 0),
                fmt(row.get("decode_per_token_cycles"), 0),
                fmt(row.get("speedup_vs_ablation_baseline")),
                fmt_pct(row.get("bitops_pct")),
                fmt_pct(row.get("attn_pct")),
                fmt_pct(row.get("prefill_attn_pct")),
                fmt_pct(row.get("decode_attn_pct")),
                str(row.get("mismatch_or_assert")),
                fmt(row.get("sim_rc"), 0),
            ])
            + " |"
        )
    lines += [
        "",
        "Notes:",
        "- `fp32_kv` still uses `-DQUANTIZED` for LLaMa weight matmuls by default; it only keeps KV attention in FP32.",
        "- `same-hw speedup` compares against `kivi_generic` for the same model and hardware preset.",
        "- `baseline-fpu speedup` compares against `baseline_fpu/kivi_generic` for the same model.",
        "- `ablation speedup` compares against `baseline_fp32_kv` for the same model and core class.",
        "- `accel_path` names the selected cross-hardware accelerated path when `compare-mode=cross_hw_accel`.",
        "- `prefill/token` and `decode/token` are from `PREFILL_PER_TOKEN_PROFILE` and `DECODE_PER_TOKEN_PROFILE`.",
        "- Percentage columns are percentages of the corresponding phase total; full per-operator fraction fields are in `results.csv` and `results.json`.",
        "- Operator buckets are `bitops` for qmatmul/fmatmul, `attn` for LLaMa attention/KIVI kernels, and `other` for the remainder.",
        "- Logs and ELF files are archived next to this summary.",
    ]
    path.write_text("\n".join(lines) + "\n")


def write_manifest(
    out_dir: Path,
    cases: Sequence[LlamaCase],
    hws: Sequence[HardwarePreset],
    variants: Sequence[SoftwareVariant],
    args: argparse.Namespace,
    corners: Sequence[BenchmarkCorner],
) -> None:
    manifest = {
        "models": [case.__dict__ for case in cases],
        "hardware": [hw.__dict__ for hw in hws],
        "variants": [variant.__dict__ for variant in variants],
        "software_corners": [corner.__dict__ for corner in corners],
        "args": vars(args),
        "commands": {
            "script": str(Path(__file__).resolve()),
            "cwd": str(REPO_ROOT),
        },
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str) + "\n")


def print_selection(cases: Sequence[LlamaCase], hws: Sequence[HardwarePreset], variants: Sequence[SoftwareVariant], args: argparse.Namespace) -> None:
    print("Models: " + ", ".join(f"{case.name}={case.bin_path}" for case in cases))
    print("Hardware: " + ", ".join(hw.name for hw in hws))
    print("Variants: " + ", ".join(variant.name for variant in variants))
    if args.compare_mode == "ablation":
        print(
            "Ablation paths: baseline_fp32_kv, bncfu256_noquant_fp32_kv, "
            "bncfu256_noquant_kivi_kv, bncfu256_quant64_kivi_kv"
        )
    if args.compare_mode == "cross_hw_accel":
        print("Cross-hardware accelerated paths are selected automatically from each hardware backend.")
    corners = benchmark_corners(args)
    if len(corners) > 1:
        print("Software corners: " + ", ".join(corner.name for corner in corners))
        return
    prefill_attention_pos, decode_attention_pos = attention_positions(args)
    if args.case == "legacy":
        print("Attention case: legacy (prefill uses len/2-1, decode uses context_len-1)")
    else:
        print(
            f"Attention case: {args.case} "
            f"(prefill_pos={prefill_attention_pos}, decode_pos={decode_attention_pos}, group_size={args.kv_group_size})"
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark LLaMa perf-est KIVI attention end to end on VexiiMico BNCFU."
    )
    parser.add_argument("--models", default="llama3m_w2a8", help="Comma-separated aliases or bin paths.")
    parser.add_argument("--no-quantized-weights", action="store_true", help="Do not define QUANTIZED. Intended only for float model bins.")
    parser.add_argument("--prefill-len", type=int, default=128)
    parser.add_argument("--decode-steps", type=int, default=1)
    parser.add_argument("--decode-context-len", type=int, default=128)
    parser.add_argument("--prefill-lm-heads", type=int, default=1)
    parser.add_argument("--profile-repeats", type=int, default=1)
    parser.add_argument("--kv-group-size", type=int, default=32)
    parser.add_argument(
        "--case",
        default="legacy",
        choices=["legacy", "best", "mid", "worst"],
        help=(
            "Attention sample case around --prefill-len/--decode-context-len. "
            "best uses current float tokens ~=1, mid uses group_size/2, worst uses group_size."
        ),
    )
    parser.add_argument(
        "--benchmark-preset",
        default="single",
        choices=["single", "standard_ablation"],
        help=(
            "single uses the explicit --prefill-len/--decode-context-len/--case. "
            "standard_ablation expands 64,128,192 x best,worst and is intended "
            "for four ablation paths: baseline FP32 KV, BNCFU noquant FP32 KV, "
            "BNCFU noquant KIVI, BNCFU quant64 KIVI."
        ),
    )
    parser.add_argument(
        "--hardware-preset",
        default="standard",
        choices=["smoke", "standard", "sync_rf", "width", "ablation_large", "ablation_small", "cross_hw_large", "cross_hw_small"],
    )
    parser.add_argument("--only-hardware", action="append")
    parser.add_argument("--variants", default=None)
    parser.add_argument(
        "--compare-mode",
        default="baseline_vs_bncfu",
        choices=["baseline_vs_bncfu", "all_selected", "ablation", "cross_hw_accel"],
        help="baseline_vs_bncfu runs generic KIVI only on non-BNCFU hardware; ablation runs fixed FP32/KIVI BNCFU256 paths; cross_hw_accel runs one accelerated path per hardware backend.",
    )
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--extra-cflag", action="append")
    parser.add_argument("--force-build", action="store_true")
    parser.add_argument("--force-sim", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--keep-going", action="store_true")
    args = parser.parse_args()

    if args.benchmark_preset == "standard_ablation":
        if args.hardware_preset == "standard":
            args.hardware_preset = "ablation_large"
        if args.compare_mode == "baseline_vs_bncfu":
            args.compare_mode = "ablation"
        if args.variants is None:
            args.variants = "fp32_kv,kivi_bncfu"
    if args.compare_mode == "cross_hw_accel":
        if args.hardware_preset == "standard":
            args.hardware_preset = "cross_hw_large"
        if args.variants is None:
            args.variants = "fp32_kv,kivi_bncfu"

    if args.prefill_len < 0:
        raise SystemExit("--prefill-len must be non-negative")
    if args.decode_steps <= 0:
        raise SystemExit("--decode-steps must be positive")
    if args.decode_context_len < 0:
        raise SystemExit("--decode-context-len must be non-negative")
    if args.profile_repeats <= 0:
        raise SystemExit("--profile-repeats must be positive")
    if args.kv_group_size <= 0:
        raise SystemExit("--kv-group-size must be positive")

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = (args.out_dir or (DEFAULT_OUT_ROOT / f"e2e_llama_{stamp}")).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    cases = make_cases(args)
    hws = select_hardware(args)
    if args.variants is None:
        args.variants = "fp32_kv,kivi_bncfu" if args.compare_mode in ("ablation", "cross_hw_accel") else "kivi_generic,kivi_bncfu"
    variants = select_variants(args.variants)
    corners = benchmark_corners(args)
    ensure_preflight(cases, args.dry_run)
    write_manifest(out_dir, cases, hws, variants, args, corners)
    print_selection(cases, hws, variants, args)

    rows: List[Dict[str, object]] = []
    start = time.time()
    for corner in corners:
        run_args = args_for_corner(args, corner)
        if len(corners) > 1:
            prefill_attention_pos, decode_attention_pos = attention_positions(run_args)
            print(
                f"\n### Software corner {corner.name}: "
                f"prefill_len={corner.prefill_len} decode_context_len={corner.decode_context_len} "
                f"case={corner.case} prefill_pos={prefill_attention_pos} decode_pos={decode_attention_pos}"
            )
        for case in cases:
            for hw in hws:
                for variant in variants:
                    if not should_run_variant_on_hw(hw, variant, run_args.compare_mode):
                        print(f"[skip] {corner.name}/{case.name}/{hw.name}/{variant.name}: compare-mode={run_args.compare_mode}")
                        continue
                    print(f"\n=== {corner.name} / {case.name} / {hw.name} / {variant.name} ===")
                    elf, build_rc = build_elf(case, hw, variant, out_dir, run_args)
                    if build_rc != 0:
                        rows.append({
                            "model": case.name,
                            "software_corner": corner.name,
                            "variant": variant.name,
                            "hardware": hw.name,
                            "build_rc": build_rc,
                            "sim_rc": None,
                        })
                        if not run_args.keep_going:
                            raise SystemExit(build_rc)
                        continue

                    log_path, sim_rc = simulate(case, hw, variant, elf, out_dir, run_args)
                    rows.append(parse_log(case, hw, variant, run_args, log_path, build_rc, sim_rc))
                    if sim_rc != 0 and not run_args.keep_going:
                        raise SystemExit(sim_rc)

    add_speedups(rows)
    write_csv(rows, out_dir / "results.csv")
    (out_dir / "results.json").write_text(json.dumps(rows, indent=2, default=str) + "\n")
    write_summary(rows, out_dir / "summary.md")

    elapsed = time.time() - start
    print(f"\nWrote {out_dir / 'results.csv'}")
    print(f"Wrote {out_dir / 'summary.md'}")
    print(f"Wrote {out_dir / 'manifest.json'}")
    print(f"Elapsed {elapsed / 60.0:.1f} min")


if __name__ == "__main__":
    main()
