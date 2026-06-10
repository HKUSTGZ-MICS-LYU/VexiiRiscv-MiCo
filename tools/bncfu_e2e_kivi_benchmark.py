#!/usr/bin/env python3
import argparse
import csv
import json
import re
import shutil
import statistics
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from bncfu_perf_explore import (
    HardwarePreset,
    hardware_presets,
    run_command,
    shell_join,
    sim_command,
)


VEXII_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = VEXII_ROOT.parents[1]
SW = VEXII_ROOT / "sw"
DEFAULT_OUT_ROOT = VEXII_ROOT / "benchmark_results"


@dataclass(frozen=True)
class ModelCase:
    name: str
    zoo_name: str
    ckpt: Optional[Path]
    weight_q: str = "2"
    act_q: str = "8"
    keep_last: bool = True


@dataclass(frozen=True)
class SoftwareVariant:
    name: str
    opt: str
    extra_cflags: Tuple[str, ...]
    requires_bncfu_hw: bool = False
    notes: str = ""


VARIANTS: Dict[str, SoftwareVariant] = {
    "fp32_attn": SoftwareVariant(
        name="fp32_attn",
        opt="",
        extra_cflags=(),
        notes="Generated quantized model path with normal FP32 attention.",
    ),
    "kivi_generic": SoftwareVariant(
        name="kivi_generic",
        opt="",
        extra_cflags=("-DKIVI_ATTN", "-DKIVI_PROFILE_INTERNAL"),
        notes="Generic C KIVI attention baseline.",
    ),
    "kivi_bncfu": SoftwareVariant(
        name="kivi_bncfu",
        opt="bncfu",
        extra_cflags=("-DKIVI_ATTN", "-DKIVI_PROFILE_INTERNAL"),
        requires_bncfu_hw=True,
        notes="BNCFU accelerated KIVI attention.",
    ),
    "kivi_bncfu_verify": SoftwareVariant(
        name="kivi_bncfu_verify",
        opt="bncfu",
        extra_cflags=("-DKIVI_ATTN", "-DKIVI_PROFILE_INTERNAL", "-DKIVI_BNCFU_INT8_VERIFY"),
        requires_bncfu_hw=True,
        notes="BNCFU KIVI correctness gate with scalar BDOT verification.",
    ),
    "kivi_bncfu_per_token": SoftwareVariant(
        name="kivi_bncfu_per_token",
        opt="bncfu",
        extra_cflags=("-DKIVI_ATTN", "-DKIVI_PROFILE_INTERNAL", "-DKIVI_K_PER_TOKEN"),
        requires_bncfu_hw=True,
        notes="BNCFU accelerated KIVI attention using K per-token quantization.",
    ),
}


MODEL_ALIASES = {
    "cct2": "cct2_cifar10",
    "cct7": "cct7_cifar100",
    "kwt": "kwt",
}


def split_csv(text: str) -> List[str]:
    return [part.strip() for part in text.split(",") if part.strip()]


def parse_ckpt_map(items: Optional[Sequence[str]]) -> Dict[str, Path]:
    mapping: Dict[str, Path] = {}
    for item in items or ():
        if "=" not in item:
            raise argparse.ArgumentTypeError(f"--ckpt expects model=path, got {item}")
        model, path = item.split("=", 1)
        mapping[normalize_model_name(model.strip())] = Path(path.strip())
    return mapping


def normalize_model_name(name: str) -> str:
    return MODEL_ALIASES.get(name, name)


def default_ckpt_for(model_name: str) -> Optional[Path]:
    candidates = [
        REPO_ROOT / "output" / "ckpt" / f"{model_name}_bitnet.pth",
        REPO_ROOT / "output" / "ckpt" / f"{model_name}.pth",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[0]


def make_model_cases(args: argparse.Namespace) -> List[ModelCase]:
    ckpt_map = parse_ckpt_map(args.ckpt)
    cases: List[ModelCase] = []
    for raw_name in split_csv(args.models):
        zoo_name = normalize_model_name(raw_name)
        ckpt = None if args.skip_ckpt else ckpt_map.get(zoo_name, default_ckpt_for(zoo_name))
        cases.append(ModelCase(
            name=raw_name,
            zoo_name=zoo_name,
            ckpt=ckpt,
            weight_q=args.weight_q,
            act_q=args.act_q,
            keep_last=not args.no_keep_last,
        ))
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


def ensure_preflight(models: Sequence[ModelCase], dry_run: bool, skip_ckpt: bool) -> None:
    missing = []
    if not (SW / "Makefile").exists():
        missing.append(str(SW / "Makefile"))
    if not (SW / "MiCo-Lib").exists():
        missing.append(str(SW / "MiCo-Lib"))
    if not skip_ckpt:
        for model in models:
            if model.ckpt is None or not model.ckpt.exists():
                missing.append(str(model.ckpt))
    if missing and not dry_run:
        joined = "\n  ".join(missing)
        raise FileNotFoundError(f"Missing required files:\n  {joined}")


def generate_model(model: ModelCase, out_dir: Path, args: argparse.Namespace) -> int:
    marker = out_dir / "generated_models" / f"{model.zoo_name}.model.generated"
    model_h_copy = out_dir / "generated_models" / f"{model.zoo_name}.h"
    model_bin_copy = out_dir / "generated_models" / f"{model.zoo_name}.bin"
    if marker.exists() and model_h_copy.exists() and model_bin_copy.exists() and not args.force_codegen:
        print(f"[skip] generate {model.zoo_name}: {model_h_copy}")
        shutil.copy2(model_h_copy, SW / "model.h")
        shutil.copy2(model_bin_copy, SW / "model.bin")
        return 0

    cmd = [
        sys.executable,
        str(REPO_ROOT / "examples" / "mpq_gen.py"),
        model.zoo_name,
        "--batch-size", "1",
        "--num-workers", str(args.num_workers),
        "--weight-q", model.weight_q,
        "--act-q", model.act_q,
        "--output-dir", str(SW),
        "--output-name", "model",
    ]
    if model.keep_last:
        cmd.append("--keep-last")
    if model.ckpt is None:
        cmd.append("--skip-ckpt")
    else:
        cmd += ["--ckpt", str(model.ckpt)]
    if args.benchmark_mode:
        cmd.append("--benchmark-mode")

    rc = run_command(cmd, out_dir / "logs" / model.zoo_name / "codegen.log", REPO_ROOT, None, args.dry_run)
    if rc == 0 and not args.dry_run:
        model_h_copy.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SW / "model.h", model_h_copy)
        shutil.copy2(SW / "model.bin", model_bin_copy)
        marker.write_text("generated\n")
    return rc


def should_run_variant_on_hw(hw: HardwarePreset, variant: SoftwareVariant, compare_mode: str) -> bool:
    if compare_mode == "ablation":
        return ablation_path_name(hw, variant) is not None
    if compare_mode == "baseline_vs_bncfu":
        if hw.enable_bncfu:
            return variant.name == "kivi_bncfu"
        return variant.name == "kivi_generic"
    if variant.requires_bncfu_hw and not hw.enable_bncfu:
        return False
    return True


def ablation_path_name(hw: HardwarePreset, variant: SoftwareVariant) -> Optional[str]:
    if not hw.enable_bncfu and variant.name == "fp32_attn":
        return "baseline_fp32_attn"
    if "noquant" in hw.name and variant.name == "fp32_attn":
        return "bncfu256_noquant_fp32_attn"
    if "noquant" in hw.name and variant.name == "kivi_bncfu_per_token":
        return "bncfu256_noquant_kivi_attn"
    if "quant64" in hw.name and variant.name == "kivi_bncfu_per_token":
        return "bncfu256_quant64_kivi_attn"
    return None


def make_vars(hw: HardwarePreset, variant: SoftwareVariant, args: argparse.Namespace) -> Dict[str, str]:
    cflags = list(variant.extra_cflags) + list(args.extra_cflag or ())
    opt = variant.opt
    if args.compare_mode == "ablation" and hw.enable_bncfu and variant.name == "fp32_attn":
        opt = "bncfu"
    vars: Dict[str, str] = {
        "MAIN": "main",
        "TARGET": "vexii_soc",
        "MARCH": hw.march,
        "SPRAM": "1",
        "TEST_NUM": str(args.test_num),
        "VLEN": str(hw.vlen),
        "BITNET_QUANT": str(hw.bitnet_quant),
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
    if cflags:
        vars["EXTRA_CFLAGS"] = " ".join(cflags)
    return vars


def build_elf(
    model: ModelCase,
    hw: HardwarePreset,
    variant: SoftwareVariant,
    out_dir: Path,
    args: argparse.Namespace,
) -> Tuple[Path, int]:
    elf_path = out_dir / "elfs" / f"{model.zoo_name}__{hw.name}__{variant.name}.elf"
    if elf_path.exists() and not args.force_build:
        print(f"[skip] build {model.zoo_name}/{hw.name}/{variant.name}: {elf_path}")
        return elf_path, 0

    vars = make_vars(hw, variant, args)
    cmd = ["make", "-C", str(SW), "-f", "Makefile", "clean", "compile"]
    cmd += [f"{key}={value}" for key, value in vars.items()]
    log_path = out_dir / "logs" / model.zoo_name / hw.name / variant.name / "build.log"
    rc = run_command(cmd, log_path, REPO_ROOT, None, args.dry_run)
    if rc == 0 and not args.dry_run:
        built = SW / "main.elf"
        if not built.exists():
            raise FileNotFoundError(f"Build succeeded but ELF is missing: {built}")
        elf_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(built, elf_path)
    return elf_path, rc


def simulate(
    model: ModelCase,
    hw: HardwarePreset,
    variant: SoftwareVariant,
    elf: Path,
    out_dir: Path,
    args: argparse.Namespace,
) -> Tuple[Path, int]:
    log_path = out_dir / "logs" / model.zoo_name / hw.name / variant.name / "sim.log"
    if log_path.exists() and not args.force_sim:
        text = log_path.read_text(errors="ignore")
        if "[success]" in text or "[Done] Simulation done" in text:
            print(f"[skip] sim {model.zoo_name}/{hw.name}/{variant.name}: {log_path}")
            return log_path, 0
    return log_path, run_command(sim_command(hw, elf), log_path, VEXII_ROOT, None, args.dry_run)


def parse_ints(pattern: str, text: str) -> List[int]:
    return [int(match) for match in re.findall(pattern, text)]


def mean_or_none(values: Sequence[int]) -> Optional[float]:
    if not values:
        return None
    return float(statistics.mean(values))


def last_or_none(values: Sequence[int]) -> Optional[int]:
    if not values:
        return None
    return int(values[-1])


def parse_profiles(text: str) -> Dict[str, object]:
    profiles = re.findall(
        r"((?:KIVI|KIVI_BNCFU|LLAMA_KIVI_BNCFU)_INTERNAL_PROFILE .+|LLAMA_KIVI_BNCFU_PROFILE .+)",
        text,
    )
    kernel_rows = re.findall(r"Benchmark Kernel (\d+): (.+)", text)
    return {
        "profile_line_count": len(profiles),
        "last_profile": profiles[-1] if profiles else "",
        "benchmark_kernel_count": len(kernel_rows),
    }


def classify_kernel(function_name: str) -> str:
    name = function_name.lower()
    if "attention" in name or "attn" in name or "kivi" in name:
        return "attn"
    if (
        "bitlinear" in name
        or "bitconv" in name
        or "qmatmul" in name
        or "addmm_q" in name
        or "conv" in name and "_q" in name
    ):
        return "bitops"
    return "other"


def parse_kernel_breakdown(text: str) -> Dict[str, object]:
    totals = {"bitops": 0, "attn": 0, "other": 0}
    counts = {"bitops": 0, "attn": 0, "other": 0}
    pattern = re.compile(
        r"Benchmark Kernel \d+: (?P<name>\S+) occurrences=(?P<count>\d+) "
        r"time=(?P<time>\d+) estimated=(?P<estimated>\d+)",
    )
    for match in pattern.finditer(text):
        category = classify_kernel(match.group("name"))
        totals[category] += int(match.group("estimated"))
        counts[category] += int(match.group("count"))

    return {
        "bitops_cycles": totals["bitops"] or None,
        "attn_cycles": totals["attn"] or None,
        "other_cycles": totals["other"] or None,
        "bitops_kernel_occurrences": counts["bitops"] or None,
        "attn_kernel_occurrences": counts["attn"] or None,
        "other_kernel_occurrences": counts["other"] or None,
    }


def divide_or_none(numerator: Optional[float], denominator: Optional[float]) -> Optional[float]:
    if numerator is None or denominator is None or denominator == 0:
        return None
    return numerator / denominator


def parse_log(
    model: ModelCase,
    hw: HardwarePreset,
    variant: SoftwareVariant,
    log_path: Path,
    build_rc: Optional[int],
    sim_rc: Optional[int],
) -> Dict[str, object]:
    text = log_path.read_text(errors="ignore") if log_path.exists() else ""
    execution_times = parse_ints(r"Execution Time:\s*(\d+)", text)
    estimated_times = parse_ints(r"Estimated Execution Time:\s*(\d+)", text)
    qmatmul_times = parse_ints(r"QMatMul Time:\s*(\d+)", text)
    quant_times = parse_ints(r"Quantization Time:\s*(\d+)", text)
    im2col_times = parse_ints(r"Im2Col Time:\s*(\d+)", text)
    qmatmul_timer = parse_ints(r"QMATMUL_TIMER:\s*(\d+)", text)
    attn_timer = parse_ints(r"ATTN_TIMER:\s*(\d+)", text)
    predicted = re.findall(r"Predicted Label:\s*(\d+), Correct Label:\s*(\d+)", text)
    correct_match = re.findall(r"Correct:\s*(\d+)\s*/\s*(\d+)", text)
    accuracy_match = re.findall(r"Accuracy:\s*([0-9.]+)", text)
    mismatch = bool(re.search(r"BDOT_MISMATCH|MISMATCH|assert", text, re.IGNORECASE))

    execution_cycles = last_or_none(execution_times)
    estimated_cycles = last_or_none(estimated_times)
    primary_cycles = execution_cycles if execution_cycles is not None else estimated_cycles
    breakdown = parse_kernel_breakdown(text)
    if breakdown["bitops_cycles"] is None:
        breakdown["bitops_cycles"] = last_or_none(qmatmul_timer) or last_or_none(qmatmul_times)
    if breakdown["attn_cycles"] is None:
        breakdown["attn_cycles"] = last_or_none(attn_timer)
    if breakdown["other_cycles"] is None and isinstance(primary_cycles, int):
        bitops = breakdown["bitops_cycles"] if isinstance(breakdown["bitops_cycles"], int) else 0
        attn = breakdown["attn_cycles"] if isinstance(breakdown["attn_cycles"], int) else 0
        other = primary_cycles - bitops - attn
        breakdown["other_cycles"] = other if other >= 0 else None

    row: Dict[str, object] = {
        "model": model.zoo_name,
        "variant": variant.name,
        "ablation_path": ablation_path_name(hw, variant) or "",
        "hardware": hw.name,
        "enable_bncfu_hw": hw.enable_bncfu,
        "fpu": hw.fpu,
        "minimal_core": hw.minimal_core,
        "vlen": hw.vlen,
        "width": hw.width,
        "quant_width": hw.quant_width,
        "reg_depth": hw.reg_depth,
        "with_q2t": hw.with_q2t,
        "with_q8": hw.with_q8,
        "rf_sync": hw.rf_sync if hw.enable_bncfu else "",
        "build_rc": build_rc,
        "sim_rc": sim_rc,
        "primary_cycles": primary_cycles,
        "execution_cycles": execution_cycles,
        "execution_cycles_mean": mean_or_none(execution_times),
        "estimated_cycles": estimated_cycles,
        "qmatmul_cycles": last_or_none(qmatmul_times),
        "qmatmul_timer_cycles": last_or_none(qmatmul_timer),
        "attn_timer_cycles": last_or_none(attn_timer),
        "quant_cycles": last_or_none(quant_times),
        "im2col_cycles": last_or_none(im2col_times),
        "prediction_count": len(predicted),
        "mismatch_or_assert": mismatch,
        "log": str(log_path.relative_to(out_dir_base(log_path))) if log_path.exists() else str(log_path),
    }
    if correct_match:
        row["correct"] = int(correct_match[-1][0])
        row["test_num_reported"] = int(correct_match[-1][1])
    if accuracy_match:
        row["accuracy"] = float(accuracy_match[-1])
    row.update(parse_profiles(text))
    row.update(breakdown)
    if isinstance(row.get("primary_cycles"), int):
        total = float(row["primary_cycles"])
        row["bitops_pct"] = divide_or_none(row.get("bitops_cycles"), total)
        row["attn_pct"] = divide_or_none(row.get("attn_cycles"), total)
        row["other_pct"] = divide_or_none(row.get("other_cycles"), total)
    return row


def out_dir_base(path: Path) -> Path:
    parts = path.parts
    if "logs" in parts:
        idx = parts.index("logs")
        return Path(*parts[:idx])
    return path.parent


def add_speedups(rows: List[Dict[str, object]]) -> None:
    same_hw_baseline: Dict[Tuple[object, object], float] = {}
    baseline_fpu: Dict[object, float] = {}
    ablation_baseline: Dict[Tuple[object, object], float] = {}
    for row in rows:
        cycles = row.get("primary_cycles")
        if row.get("variant") == "kivi_generic" and isinstance(cycles, int):
            same_hw_baseline[(row.get("model"), row.get("hardware"))] = float(cycles)
            if row.get("hardware") == "baseline_fpu":
                baseline_fpu[row.get("model")] = float(cycles)
        if row.get("ablation_path") == "baseline_fp32_attn" and isinstance(cycles, int):
            ablation_baseline[(row.get("model"), row.get("minimal_core"))] = float(cycles)
    for row in rows:
        cycles = row.get("primary_cycles")
        base = same_hw_baseline.get((row.get("model"), row.get("hardware")))
        if isinstance(cycles, int) and base and cycles > 0:
            row["speedup_vs_same_hw_kivi_generic"] = base / float(cycles)
        else:
            row["speedup_vs_same_hw_kivi_generic"] = None
        base_fpu = baseline_fpu.get(row.get("model"))
        if isinstance(cycles, int) and base_fpu and cycles > 0:
            row["speedup_vs_baseline_fpu_kivi"] = base_fpu / float(cycles)
        else:
            row["speedup_vs_baseline_fpu_kivi"] = None
        ablation_base = ablation_baseline.get((row.get("model"), row.get("minimal_core")))
        if isinstance(cycles, int) and ablation_base and cycles > 0:
            row["speedup_vs_ablation_baseline"] = ablation_base / float(cycles)
        else:
            row["speedup_vs_ablation_baseline"] = None


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


def write_summary(rows: List[Dict[str, object]], path: Path) -> None:
    lines = [
        "# BNCFU E2E KIVI Benchmark Summary",
        "",
        "| model | hardware | variant | path | cycles | same-hw speedup | baseline-fpu speedup | ablation speedup | bitops | attn | other | mismatch | rc |",
        "| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |",
    ]
    for row in rows:
        lines.append(
            "| "
            + " | ".join([
                str(row.get("model")),
                str(row.get("hardware")),
                str(row.get("variant")),
                str(row.get("ablation_path") or "-"),
                fmt(row.get("primary_cycles"), 0),
                fmt(row.get("speedup_vs_same_hw_kivi_generic")),
                fmt(row.get("speedup_vs_baseline_fpu_kivi")),
                fmt(row.get("speedup_vs_ablation_baseline")),
                fmt(row.get("bitops_cycles"), 0),
                fmt(row.get("attn_cycles"), 0),
                fmt(row.get("other_cycles"), 0),
                str(row.get("mismatch_or_assert")),
                fmt(row.get("sim_rc"), 0),
            ])
            + " |"
        )
    lines += [
        "",
        "Notes:",
        "- `same-hw speedup` compares against `kivi_generic` for the same model and hardware preset.",
        "- `baseline-fpu speedup` compares against `baseline_fpu/kivi_generic` for the same model.",
        "- `ablation speedup` compares against `baseline_fp32_attn` for the same model and core class.",
        "- Operator buckets are `bitops` for bitlinear/bitconv/qmatmul, `attn` for attention/KIVI kernels, and `other` for the remainder.",
        "- Logs, generated models, and ELF files are archived next to this summary.",
    ]
    path.write_text("\n".join(lines) + "\n")


def write_manifest(
    out_dir: Path,
    models: Sequence[ModelCase],
    hws: Sequence[HardwarePreset],
    variants: Sequence[SoftwareVariant],
    args: argparse.Namespace,
) -> None:
    manifest = {
        "models": [model.__dict__ for model in models],
        "hardware": [hw.__dict__ for hw in hws],
        "variants": [variant.__dict__ for variant in variants],
        "args": vars(args),
        "commands": {
            "script": str(Path(__file__).resolve()),
            "cwd": str(REPO_ROOT),
        },
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str) + "\n")


def print_selection(models: Sequence[ModelCase], hws: Sequence[HardwarePreset], variants: Sequence[SoftwareVariant]) -> None:
    print("Models: " + ", ".join(model.zoo_name for model in models))
    print("Hardware: " + ", ".join(hw.name for hw in hws))
    print("Variants: " + ", ".join(variant.name for variant in variants))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate CCT/KWT MiCo C models and benchmark KIVI attention end to end on VexiiMico BNCFU."
    )
    parser.add_argument("--models", default="cct2,cct7,kwt", help="Comma-separated model aliases or model_zoo names.")
    parser.add_argument("--ckpt", action="append", help="Per-model checkpoint override: model=path. Can be repeated.")
    parser.add_argument("--skip-ckpt", action="store_true", help="Generate from initialized weights for flow debugging only.")
    parser.add_argument("--weight-q", default="2")
    parser.add_argument("--act-q", default="8")
    parser.add_argument("--num-workers", type=int, default=0, help="DataLoader workers used during codegen input extraction.")
    parser.add_argument("--no-keep-last", action="store_true")
    parser.add_argument(
        "--benchmark-mode",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Use MiCoCodeGen benchmark mode for per-kernel operator breakdown. Enabled by default.",
    )
    parser.add_argument(
        "--hardware-preset",
        default="standard",
        choices=["smoke", "standard", "sync_rf", "width", "ablation_large", "ablation_small"],
    )
    parser.add_argument("--only-hardware", action="append")
    parser.add_argument("--variants", default=None)
    parser.add_argument(
        "--compare-mode",
        default="baseline_vs_bncfu",
        choices=["baseline_vs_bncfu", "all_selected", "ablation"],
        help="baseline_vs_bncfu runs generic KIVI only on non-BNCFU hardware; ablation runs fixed FP32/KIVI BNCFU256 paths.",
    )
    parser.add_argument("--test-num", type=int, default=1)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--extra-cflag", action="append")
    parser.add_argument("--force-codegen", action="store_true")
    parser.add_argument("--force-build", action="store_true")
    parser.add_argument("--force-sim", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--keep-going", action="store_true")
    args = parser.parse_args()

    if args.test_num <= 0:
        raise SystemExit("--test-num must be positive")

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = (args.out_dir or (DEFAULT_OUT_ROOT / f"e2e_kivi_{stamp}")).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    models = make_model_cases(args)
    hws = select_hardware(args)
    if args.variants is None:
        args.variants = "fp32_attn,kivi_bncfu_per_token" if args.compare_mode == "ablation" else "kivi_generic,kivi_bncfu"
    variants = select_variants(args.variants)
    ensure_preflight(models, args.dry_run, args.skip_ckpt)
    write_manifest(out_dir, models, hws, variants, args)
    print_selection(models, hws, variants)

    rows: List[Dict[str, object]] = []
    start = time.time()
    for model in models:
        print(f"\n=== generate {model.zoo_name} ===")
        codegen_rc = generate_model(model, out_dir, args)
        if codegen_rc != 0:
            rows.append({
                "model": model.zoo_name,
                "variant": "codegen_failed",
                "hardware": "",
                "build_rc": codegen_rc,
                "sim_rc": None,
            })
            if not args.keep_going:
                raise SystemExit(codegen_rc)
            continue

        for hw in hws:
            for variant in variants:
                if not should_run_variant_on_hw(hw, variant, args.compare_mode):
                    print(f"[skip] {model.zoo_name}/{hw.name}/{variant.name}: compare-mode={args.compare_mode}")
                    continue
                print(f"\n=== {model.zoo_name} / {hw.name} / {variant.name} ===")
                elf, build_rc = build_elf(model, hw, variant, out_dir, args)
                if build_rc != 0:
                    rows.append({
                        "model": model.zoo_name,
                        "variant": variant.name,
                        "hardware": hw.name,
                        "build_rc": build_rc,
                        "sim_rc": None,
                    })
                    if not args.keep_going:
                        raise SystemExit(build_rc)
                    continue

                log_path, sim_rc = simulate(model, hw, variant, elf, out_dir, args)
                rows.append(parse_log(model, hw, variant, log_path, build_rc, sim_rc))
                if sim_rc != 0 and not args.keep_going:
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
