#!/usr/bin/env python3
import argparse
import csv
import json
import os
import re
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


ROOT = Path(__file__).resolve().parents[1]
SW = ROOT / "sw"
DEFAULT_OUT_ROOT = ROOT / "benchmark_results"


@dataclass(frozen=True)
class HardwarePreset:
    name: str
    enable_bncfu: bool
    fpu: bool = True
    qtype: str = "1.5b"
    bitnet_quant: int = 3
    vlen: int = 256
    width: int = 128
    reg_depth: int = 3
    bus_width: int = 64
    quant_width: int = 128
    with_q2: bool = False
    with_q2t: bool = False
    with_q8: bool = False
    pipe: bool = True
    q8_compare_pipe: bool = True
    quant_standard: bool = False
    rf_sync: bool = False
    sparse_mem_lat: int = 4
    extra_sim_args: Tuple[str, ...] = ()

    @property
    def march(self) -> str:
        return "rv32imafc_zifencei" if self.fpu else "rv32imac_zifencei"

    @property
    def sim_args(self) -> List[str]:
        args = [
            "--with-rvc",
            "--with-rvm",
            "--decoders", "2",
            "--lanes", "2",
            "--with-aligner-buffer",
            "--with-dispatcher-buffer",
            "--with-ras",
            "--with-btb",
            "--with-gshare",
            "--with-late-alu",
            "--regfile-async",
            "--fetch-l1",
            "--fetch-l1-ways", "2",
            "--lsu-l1",
            "--lsu-l1-ways", "2",
            "--allow-bypass-from", "0",
            "--div-radix", "4",
            "--sparse-mem",
            "--sparse-mem-lat", str(self.sparse_mem_lat),
        ]
        if self.fpu:
            args.append("--with-rvf")
        if self.enable_bncfu:
            args += [
                "--mico-bitnet-cfu",
                "--bitnet-cfu-qtype", self.qtype,
                "--bitnet-cfu-len", str(self.vlen),
                "--bitnet-cfu-width", str(self.width),
                "--bitnet-cfu-quant-width", str(self.quant_width),
                "--bitnet-cfu-reg-depth", str(self.reg_depth),
                "--bitnet-cfu-bus-width", str(self.bus_width),
            ]
            if self.pipe:
                args.append("--bitnet-cfu-pipe")
            if self.with_q2:
                args.append("--bitnet-cfu-with-q2")
            args.append("--bitnet-cfu-with-q2t" if self.with_q2t else "--bitnet-cfu-without-q2t")
            if self.with_q8:
                args.append("--bitnet-cfu-with-q8")
            if self.q8_compare_pipe:
                args.append("--bitnet-cfu-q8-compare-pipe")
            if self.quant_standard:
                args.append("--bitnet-cfu-quant-standard")
            args.append("--bitnet-cfu-rf-sync" if self.rf_sync else "--bitnet-cfu-rf-async")
        args += list(self.extra_sim_args)
        return args


@dataclass(frozen=True)
class BenchmarkCase:
    name: str
    family: str
    main: str
    baseline_opt: str
    bncfu_opt: str
    extra_cflags: Tuple[str, ...] = ()
    bitnet_quant: Optional[int] = None
    requires_ref: bool = False
    notes: str = ""

    def make_vars(self, hw: HardwarePreset, global_extra_cflags: Tuple[str, ...] = ()) -> Dict[str, str]:
        opt = self.bncfu_opt if hw.enable_bncfu else self.baseline_opt
        extra = list(self.extra_cflags) + list(global_extra_cflags)

        vars: Dict[str, str] = {
            "MAIN": self.main,
            "TARGET": "vexii_soc",
            "MARCH": hw.march,
            "SPRAM": "1",
            "VLEN": str(hw.vlen),
            "OPT": opt,
            "BITNET_QUANT": str(self.bitnet_quant if self.bitnet_quant is not None else hw.bitnet_quant),
        }

        if hw.enable_bncfu:
            vars.update({
                "BNCFU_REG_DEPTH": str(hw.reg_depth),
                "BNCFU_Q2T": "1" if hw.with_q2t else "0",
                "BNCFU_Q8": "1" if hw.with_q8 else "0",
                "BNCFU_QUANT_WIDTH": str(hw.quant_width),
            })

        if extra:
            vars["EXTRA_CFLAGS"] = " ".join(extra)
        return vars


def hardware_presets(name: str) -> List[HardwarePreset]:
    if name == "smoke":
        return [
            HardwarePreset("baseline_fpu", enable_bncfu=False),
            HardwarePreset(
                "bncfu_q2t_q8_pipe",
                enable_bncfu=True,
                with_q2t=True,
                with_q8=True,
                pipe=True,
                q8_compare_pipe=True,
            ),
        ]
    if name == "standard":
        return [
            HardwarePreset("baseline_fpu", enable_bncfu=False),
            HardwarePreset(
                "bncfu_256",
                vlen=256,
                width=128,
                quant_width=64,
                enable_bncfu=True,
                with_q2t=True,
                with_q8=True,
                pipe=True,
            ),
            HardwarePreset(
                "bncfu_512",
                vlen=512,
                width=256,
                quant_width=128,
                enable_bncfu=True,
                with_q2t=True,
                with_q8=True,
                pipe=True,
            ),
        ]
    if name == "width":
        return [
            HardwarePreset("baseline_fpu", enable_bncfu=False),
            HardwarePreset("bncfu_v128_w64", enable_bncfu=True, vlen=128, width=64, quant_width=128, with_q2t=True, with_q8=True, pipe=True, q8_compare_pipe=True),
            HardwarePreset("bncfu_v256_w64", enable_bncfu=True, vlen=256, width=64, quant_width=128, with_q2t=True, with_q8=True, pipe=True, q8_compare_pipe=True),
            HardwarePreset("bncfu_v256_w128", enable_bncfu=True, vlen=256, width=128, quant_width=128, with_q2t=True, with_q8=True, pipe=True, q8_compare_pipe=True),
            HardwarePreset("bncfu_v512_w128", enable_bncfu=True, vlen=512, width=128, quant_width=128, with_q2t=True, with_q8=True, pipe=True, q8_compare_pipe=True),
        ]
    raise SystemExit(f"Unknown hardware preset set: {name}")


def benchmark_cases(name: str) -> List[BenchmarkCase]:
    def attention_case(f: int) -> BenchmarkCase:
        return BenchmarkCase(
            name=f"attention_f{f}",
            family="attention",
            main="tests/kivi_attention_comp",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(
                "-DKIVI_B=1",
                "-DKIVI_H=4",
                "-DKIVI_I=16",
                "-DKIVI_J=32",
                f"-DKIVI_F={f}",
            ),
            notes="Attention sweep with B/H/I/J fixed; only the CFU-accelerated head dimension F changes.",
        )

    def matmul_case(k: int) -> BenchmarkCase:
        return BenchmarkCase(
            name=f"matmul_k{k}",
            family="matmul",
            main="tests/bnmatmul_variants_test",
            baseline_opt="ref",
            bncfu_opt="bncfu ref",
            bitnet_quant=3,
            requires_ref=True,
            extra_cflags=(
                "-DBNCFU_MATMUL_N=16",
                "-DBNCFU_MATMUL_M=16",
                f"-DBNCFU_MATMUL_K={k}",
            ),
            notes="Matmul sweep with N/M fixed; only the CFU-accelerated reduction dimension K changes.",
        )

    def q2t_case(n: int) -> BenchmarkCase:
        return BenchmarkCase(
            name=f"q2t_n{n}",
            family="q2t_quant",
            main="tests/q2t_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(f"-DQ2T_CUSTOM_N={n}",),
            notes="Q2T quantization vector-length sweep.",
        )

    def q8_case(n: int) -> BenchmarkCase:
        return BenchmarkCase(
            name=f"q8_n{n}",
            family="q8_quant",
            main="tests/q8_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(f"-DQ8_CUSTOM_N={n}",),
            notes="Q8 quantization vector-length sweep.",
        )

    smoke = [
        BenchmarkCase(
            name="attention_s",
            family="attention",
            main="tests/kivi_attention_comp",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=("-DKIVI_B=1", "-DKIVI_H=2", "-DKIVI_I=8", "-DKIVI_J=16", "-DKIVI_F=64"),
            notes="FP32 attention vs KIVI attention on a small shape.",
        ),
        BenchmarkCase(
            name="matmul_q2x_q8_aligned",
            family="matmul",
            main="tests/bnmatmul_variants_test",
            baseline_opt="ref",
            bncfu_opt="bncfu ref",
            bitnet_quant=3,
            requires_ref=True,
            extra_cflags=("-DBNCFU_ALIGNED_BNMATMUL_CASES",),
            notes="Q8x2 and Q2x8 aligned case, K=VLEN.",
        ),
        BenchmarkCase(
            name="q2t_quant",
            family="q2t_quant",
            main="tests/q2t_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
        ),
        BenchmarkCase(
            name="q8_quant",
            family="q8_quant",
            main="tests/q8_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
        ),
    ]
    if name == "smoke":
        return smoke
    if name == "standard":
        return (
            [attention_case(f) for f in (128, 256, 512, 1024)] +
            [matmul_case(k) for k in (256, 512, 1024, 2048)] +
            [q2t_case(n) for n in (2048, 4096, 8192, 16384)] +
            [q8_case(n) for n in (2048, 4096, 8192, 16384)]
        )
    if name == "full":
        return benchmark_cases("standard")
    raise SystemExit(f"Unknown benchmark suite: {name}")


def parse_int_tuple(text: str, count: int, label: str) -> Tuple[int, ...]:
    parts = re.split(r"[xX,:\s]+", text.strip())
    parts = [p for p in parts if p]
    if len(parts) != count:
        raise argparse.ArgumentTypeError(f"{label} must contain {count} integers, got: {text}")
    try:
        values = tuple(int(p, 0) for p in parts)
    except ValueError as e:
        raise argparse.ArgumentTypeError(f"{label} contains a non-integer value: {text}") from e
    if any(v <= 0 for v in values):
        raise argparse.ArgumentTypeError(f"{label} values must be positive: {text}")
    return values


def attention_shape_arg(text: str) -> Tuple[int, int, int, int, int]:
    return parse_int_tuple(text, 5, "attention shape")  # B,H,I,J,F


def matmul_shape_arg(text: str) -> Tuple[int, int, int]:
    return parse_int_tuple(text, 3, "matmul shape")  # N,M,K


def positive_int_arg(text: str) -> int:
    try:
        value = int(text, 0)
    except ValueError as e:
        raise argparse.ArgumentTypeError(f"expected a positive integer, got: {text}") from e
    if value <= 0:
        raise argparse.ArgumentTypeError(f"expected a positive integer, got: {text}")
    return value


def custom_benchmark_cases(args: argparse.Namespace) -> List[BenchmarkCase]:
    cases: List[BenchmarkCase] = []

    for b, h, i, j, f in args.attention_shape or []:
        cases.append(BenchmarkCase(
            name=f"attention_b{b}_h{h}_i{i}_j{j}_f{f}",
            family="attention",
            main="tests/kivi_attention_comp",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(
                f"-DKIVI_B={b}",
                f"-DKIVI_H={h}",
                f"-DKIVI_I={i}",
                f"-DKIVI_J={j}",
                f"-DKIVI_F={f}",
            ),
            notes="User-specified attention shape.",
        ))

    for n, m, k in args.matmul_shape or []:
        cases.append(BenchmarkCase(
            name=f"matmul_n{n}_m{m}_k{k}",
            family="matmul",
            main="tests/bnmatmul_variants_test",
            baseline_opt="ref",
            bncfu_opt="bncfu ref",
            bitnet_quant=args.matmul_bitnet_quant,
            requires_ref=True,
            extra_cflags=(
                f"-DBNCFU_MATMUL_N={n}",
                f"-DBNCFU_MATMUL_M={m}",
                f"-DBNCFU_MATMUL_K={k}",
            ),
            notes="User-specified Q8x2/Q2x8 matmul shape.",
        ))

    for n in args.q2t_size or []:
        cases.append(BenchmarkCase(
            name=f"q2t_n{n}",
            family="q2t_quant",
            main="tests/q2t_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(f"-DQ2T_CUSTOM_N={n}",),
            notes="User-specified Q2T quant vector length.",
        ))

    for n in args.q8_size or []:
        cases.append(BenchmarkCase(
            name=f"q8_n{n}",
            family="q8_quant",
            main="tests/q8_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(f"-DQ8_CUSTOM_N={n}",),
            notes="User-specified Q8 quant vector length.",
        ))

    for n in args.quant_size or []:
        cases.append(BenchmarkCase(
            name=f"q2t_n{n}",
            family="q2t_quant",
            main="tests/q2t_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(f"-DQUANT_CUSTOM_N={n}",),
            notes="User-specified shared quant vector length.",
        ))
        cases.append(BenchmarkCase(
            name=f"q8_n{n}",
            family="q8_quant",
            main="tests/q8_quant_test",
            baseline_opt="",
            bncfu_opt="bncfu",
            extra_cflags=(f"-DQUANT_CUSTOM_N={n}",),
            notes="User-specified shared quant vector length.",
        ))

    return cases


def shell_join(cmd: Iterable[str]) -> str:
    return " ".join(shlex.quote(str(x)) for x in cmd)


def run_command(cmd: List[str], log_path: Path, cwd: Path, env: Optional[Dict[str, str]], dry_run: bool) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    printable = shell_join(cmd)
    if dry_run:
        print(f"[dry-run] {printable}")
        log_path.write_text("$ " + printable + "\n")
        return 0

    with log_path.open("w") as log:
        log.write("$ " + printable + "\n")
        log.flush()
        proc = subprocess.Popen(
            cmd,
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            log.write(line)
        return proc.wait()


def build_elf(
    hw: HardwarePreset,
    bench: BenchmarkCase,
    out_dir: Path,
    force: bool,
    dry_run: bool,
    global_extra_cflags: Tuple[str, ...],
) -> Tuple[Path, int]:
    elf_path = out_dir / "elfs" / f"{hw.name}__{bench.name}.elf"
    if elf_path.exists() and not force:
        print(f"[skip] build {hw.name}/{bench.name}: {elf_path}")
        return elf_path, 0

    make_vars = bench.make_vars(hw, global_extra_cflags)
    cmd = ["make", "-C", str(SW), "-f", "Makefile", "clean", "compile"]
    cmd += [f"{k}={v}" for k, v in make_vars.items()]
    rc = run_command(cmd, out_dir / "logs" / hw.name / bench.name / "build.log", ROOT, None, dry_run)
    if rc == 0 and not dry_run:
        built = SW / f"{bench.main}.elf"
        if not built.exists():
            raise FileNotFoundError(f"Build succeeded but ELF is missing: {built}")
        elf_path.parent.mkdir(parents=True, exist_ok=True)
        elf_path.write_bytes(built.read_bytes())
    return elf_path, rc


def sim_command(hw: HardwarePreset, elf: Path) -> List[str]:
    sim_args = ["--load-elf", str(elf)] + hw.sim_args
    return ["sbt", "runMain vexiiriscv.soc.mico.MiCoSocSim " + shell_join(sim_args)]


def simulate(hw: HardwarePreset, bench: BenchmarkCase, elf: Path, out_dir: Path, force: bool, dry_run: bool) -> Tuple[Path, int]:
    log_path = out_dir / "logs" / hw.name / bench.name / "sim.log"
    if log_path.exists() and not force:
        text = log_path.read_text(errors="ignore")
        if "[success]" in text or "[Done] Simulation done" in text:
            print(f"[skip] sim {hw.name}/{bench.name}: {log_path}")
            return log_path, 0
    cmd = sim_command(hw, elf)
    return log_path, run_command(cmd, log_path, ROOT, None, dry_run)


def parse_shape(text: str) -> Dict[str, Optional[int]]:
    for pattern in (
        r"Shape: B=(\d+) H=(\d+) I=(\d+) J=(\d+) F=(\d+)",
        r"Start KIVI Attention Benchmark B=(\d+) H=(\d+) N=(\d+) D=(\d+)",
    ):
        m = re.search(pattern, text)
        if m:
            nums = [int(x) for x in m.groups()]
            if len(nums) == 5:
                return {"B": nums[0], "H": nums[1], "I": nums[2], "J": nums[3], "F": nums[4]}
            return {"B": nums[0], "H": nums[1], "N": nums[2], "D": nums[3]}
    return {}


def parse_log(hw: HardwarePreset, bench: BenchmarkCase, log_path: Path, rc: int) -> List[Dict[str, object]]:
    text = log_path.read_text(errors="ignore") if log_path.exists() else ""
    try:
        log_name = str(log_path.relative_to(ROOT))
    except ValueError:
        log_name = str(log_path)
    rows: List[Dict[str, object]] = []
    common: Dict[str, object] = {
        "hardware": hw.name,
        "bench": bench.name,
        "family": bench.family,
        "enable_bncfu": hw.enable_bncfu,
        "vlen": hw.vlen,
        "width": hw.width,
        "reg_depth": hw.reg_depth,
        "quant_width": hw.quant_width,
        "with_q2t": hw.with_q2t,
        "with_q8": hw.with_q8,
        "pipe": hw.pipe,
        "q8_compare_pipe": hw.q8_compare_pipe,
        "quant_standard": hw.quant_standard,
        "march": hw.march,
        "sim_rc": rc,
        "log": log_name,
    }
    common.update(parse_shape(text))

    if bench.family == "attention":
        m = re.search(r"KIVI_SPEED_COMPARE ref_attn=(\d+) kivi_attn=(\d+) speedup_milli=(\d+) faster=(\d+)", text)
        if m:
            ref_attn, kivi_attn, speedup_milli, faster = [int(x) for x in m.groups()]
            rows.append({
                **common,
                "metric": "kivi_attn",
                "cycles": kivi_attn,
                "ref_attn_cycles": ref_attn,
                "internal_speedup": speedup_milli / 1000.0,
                "faster_than_fp32": bool(faster),
            })
        m = re.search(r"KIVI_ONLY_RESULT kivi_attn=(\d+) total_time=(\d+)", text)
        if m:
            rows.append({
                **common,
                "metric": "kivi_only_attn",
                "cycles": int(m.group(1)),
                "total_cycles": int(m.group(2)),
            })
        if not rows:
            timers = [int(x) for x in re.findall(r"ATTN_TIMER:\s*(\d+)", text)]
            if timers:
                rows.append({**common, "metric": "attn_timer", "cycles": timers[-1]})
    elif bench.family == "matmul":
        for m in re.finditer(r"TIME (Q\d+x\d+) N=(\d+) M=(\d+) K=(\d+) cycles=(\d+)", text):
            op, n, mm, k, cycles = m.groups()
            rows.append({
                **common,
                "metric": op,
                "N": int(n),
                "M": int(mm),
                "K": int(k),
                "cycles": int(cycles),
                "ops": int(n) * int(mm) * int(k),
            })
    elif bench.family in ("q2t_quant", "q8_quant"):
        for m in re.finditer(r"TIME (\S+) n=(\d+) scalar=(\d+) dut=(\d+) speedup_x100=(\d+)", text):
            case, n, scalar, dut, speedup_x100 = m.groups()
            rows.append({
                **common,
                "metric": case,
                "n": int(n),
                "cycles": int(dut),
                "scalar_cycles": int(scalar),
                "internal_speedup": int(speedup_x100) / 100.0,
            })

    if not rows:
        rows.append({**common, "metric": "unparsed", "cycles": None})
    return rows


def row_key(row: Dict[str, object]) -> Tuple[object, ...]:
    return (
        row.get("bench"),
        row.get("family"),
        row.get("metric"),
        row.get("B"),
        row.get("H"),
        row.get("I"),
        row.get("J"),
        row.get("F"),
        row.get("N"),
        row.get("M"),
        row.get("K"),
        row.get("n"),
    )


def add_baseline_speedups(rows: List[Dict[str, object]], baseline_name: str) -> None:
    baseline: Dict[Tuple[object, ...], float] = {}
    for row in rows:
        if row.get("hardware") == baseline_name and isinstance(row.get("cycles"), int):
            baseline[row_key(row)] = float(row["cycles"])
    for row in rows:
        cycles = row.get("cycles")
        base = baseline.get(row_key(row))
        if isinstance(cycles, int) and base and cycles > 0:
            row["speedup_vs_baseline"] = base / float(cycles)
        else:
            row["speedup_vs_baseline"] = None


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
    lines = ["# BNCFU Performance Exploration", ""]
    lines += [
        "Generated by `tools/bncfu_perf_explore.py`.",
        "",
        "| hardware | bench | metric | shape | cycles | internal speedup | vs baseline | rc |",
        "| --- | --- | --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for row in rows:
        shape_parts = []
        for key in ("B", "H", "I", "J", "F", "N", "M", "K", "n"):
            if row.get(key) not in (None, ""):
                shape_parts.append(f"{key}={row[key]}")
        lines.append(
            "| "
            + " | ".join([
                str(row.get("hardware")),
                str(row.get("bench")),
                str(row.get("metric")),
                " ".join(shape_parts) or "-",
                fmt(row.get("cycles"), 0),
                fmt(row.get("internal_speedup")),
                fmt(row.get("speedup_vs_baseline")),
                fmt(row.get("sim_rc"), 0),
            ])
            + " |"
        )
    lines += [
        "",
        "Notes:",
        "- `internal speedup` is benchmark-defined. For attention it is FP32 attention vs KIVI attention; for quant it is scalar quant vs DUT quant.",
        "- `vs baseline` compares the same parsed metric against the first hardware preset, normally `baseline_fpu`.",
        "- All generated Makefile commands use `SPRAM=1` by default.",
    ]
    path.write_text("\n".join(lines) + "\n")


def plot(rows: List[Dict[str, object]], out_dir: Path) -> None:
    os.environ.setdefault("MPLCONFIGDIR", str(out_dir / ".matplotlib"))
    try:
        import matplotlib.pyplot as plt
    except Exception as e:
        print(f"[warn] matplotlib unavailable, skip plots: {e}")
        return

    plotted = [r for r in rows if isinstance(r.get("speedup_vs_baseline"), float)]
    if not plotted:
        print("[warn] no speedup rows to plot")
        return

    labels = [f"{r['hardware']}\n{r['bench']}:{r['metric']}" for r in plotted]
    values = [float(r["speedup_vs_baseline"]) for r in plotted]
    height = max(4.0, 0.28 * len(values))
    fig, ax = plt.subplots(figsize=(10.0, height), dpi=140)
    y = list(range(len(values)))
    ax.barh(y, values)
    ax.set_yticks(y)
    ax.set_yticklabels(labels, fontsize=7)
    ax.axvline(1.0, color="black", linewidth=0.8)
    ax.set_xlabel("Speedup vs baseline")
    ax.set_title("BNCFU Performance Simulation Summary")
    ax.grid(True, axis="x", alpha=0.25)
    fig.tight_layout()
    fig.savefig(out_dir / "speedup_vs_baseline.png")
    fig.savefig(out_dir / "speedup_vs_baseline.svg")
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description="Build, simulate, and compare BNCFU software benchmark performance across hardware presets.")
    parser.add_argument("--hardware-preset", default="standard", choices=["smoke", "standard", "width"])
    parser.add_argument("--bench-suite", default="standard", choices=["smoke", "standard", "full"])
    parser.add_argument("--out-dir", type=Path, help="Output directory. Defaults to benchmark_results/bncfu_perf_<timestamp>.")
    parser.add_argument("--only-hardware", action="append", help="Run only the named hardware preset. Can be repeated.")
    parser.add_argument("--only-bench", action="append", help="Run only the named benchmark case. Can be repeated.")
    parser.add_argument("--force-build", action="store_true")
    parser.add_argument("--force-sim", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--keep-going", action="store_true", help="Continue after build/simulation failures and record failed rows.")
    parser.add_argument("--no-plot", action="store_true")
    parser.add_argument("--extra-cflag", action="append", help="Append a raw EXTRA_CFLAGS token to every benchmark build, e.g. --extra-cflag=-DKIVI_ONLY.")
    parser.add_argument("--attention-shape", action="append", type=attention_shape_arg, metavar="B,H,I,J,F", help="Add a custom kivi_attention_comp shape. Separators can be comma, x, colon, or whitespace.")
    parser.add_argument("--matmul-shape", action="append", type=matmul_shape_arg, metavar="N,M,K", help="Add a custom bnmatmul_variants_test shape.")
    parser.add_argument("--matmul-bitnet-quant", type=int, default=3, choices=[2, 3], help="BITNET_QUANT used for custom --matmul-shape cases. 3 means ternary/1.5-bit path.")
    parser.add_argument("--q2t-size", action="append", type=positive_int_arg, metavar="N", help="Add a custom q2t_quant_test vector length.")
    parser.add_argument("--q8-size", action="append", type=positive_int_arg, metavar="N", help="Add a custom q8_quant_test vector length.")
    parser.add_argument("--quant-size", action="append", type=positive_int_arg, metavar="N", help="Add both Q2T and Q8 custom quant vector lengths.")
    parser.add_argument("--custom-only", action="store_true", help="Run only benchmark cases generated by --attention-shape/--matmul-shape/--q2t-size/--q8-size/--quant-size.")
    args = parser.parse_args()

    out_dir = args.out_dir
    if out_dir is None:
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        out_dir = DEFAULT_OUT_ROOT / f"bncfu_perf_{stamp}"
    out_dir = out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    hws = hardware_presets(args.hardware_preset)
    custom_benches = custom_benchmark_cases(args)
    benches = custom_benches if args.custom_only else benchmark_cases(args.bench_suite) + custom_benches
    if args.only_hardware:
        allow = set(args.only_hardware)
        hws = [h for h in hws if h.name in allow]
    if args.only_bench:
        allow = set(args.only_bench)
        benches = [b for b in benches if b.name in allow]
    if not hws:
        raise SystemExit("No hardware presets selected")
    if not benches:
        raise SystemExit("No benchmark cases selected")

    manifest = {
        "hardware_preset": args.hardware_preset,
        "bench_suite": args.bench_suite,
        "hardware": [h.__dict__ for h in hws],
        "benchmarks": [b.__dict__ for b in benches],
        "global_extra_cflags": list(args.extra_cflag or ()),
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, default=list) + "\n")

    all_rows: List[Dict[str, object]] = []
    start = time.time()
    for hw in hws:
        for bench in benches:
            print(f"\n=== {hw.name} / {bench.name} ===")
            elf, build_rc = build_elf(hw, bench, out_dir, args.force_build, args.dry_run, tuple(args.extra_cflag or ()))
            if build_rc != 0:
                print(f"[fail] build {hw.name}/{bench.name}: rc={build_rc}")
                row = {
                    "hardware": hw.name,
                    "bench": bench.name,
                    "family": bench.family,
                    "metric": "build_failed",
                    "cycles": None,
                    "build_rc": build_rc,
                    "sim_rc": None,
                }
                all_rows.append(row)
                if not args.keep_going:
                    raise SystemExit(build_rc)
                continue

            log_path, sim_rc = simulate(hw, bench, elf, out_dir, args.force_sim, args.dry_run)
            rows = parse_log(hw, bench, log_path, sim_rc)
            for row in rows:
                row["build_rc"] = build_rc
            all_rows.extend(rows)
            if sim_rc != 0 and not args.keep_going:
                raise SystemExit(sim_rc)

    add_baseline_speedups(all_rows, hws[0].name)
    write_csv(all_rows, out_dir / "results.csv")
    (out_dir / "results.json").write_text(json.dumps(all_rows, indent=2, default=str) + "\n")
    write_summary(all_rows, out_dir / "summary.md")
    if not args.no_plot:
        plot(all_rows, out_dir)

    elapsed = time.time() - start
    print(f"\nWrote {out_dir / 'results.csv'}")
    print(f"Wrote {out_dir / 'summary.md'}")
    print(f"Wrote {out_dir / 'manifest.json'}")
    print(f"Elapsed {elapsed / 60.0:.1f} min")


if __name__ == "__main__":
    main()
