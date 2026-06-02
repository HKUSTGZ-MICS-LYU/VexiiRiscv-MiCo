#!/usr/bin/env python3
import argparse
import csv
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Dict, Iterable, List, Optional


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "synth_runs" / "bncfu_space"
TOP = "MiCoSoc"
TARGET_PERIOD_NS = 4.0


@dataclass
class Config:
    name: str
    enable_bncfu: bool = True
    qtype: str = "1.5b"
    vlen: int = 256
    width: int = 128
    reg_depth: int = 5
    bus_width: int = 64
    pipe: bool = False
    with_q2: bool = False
    with_q2t: bool = True
    with_q8: bool = False
    quant_width: int = 0
    q8_compare_pipe: bool = False
    quant_standard: bool = False
    rf_sync: bool = False
    stress: bool = False
    extra_args: List[str] = field(default_factory=list)

    @property
    def gen_args(self) -> List[str]:
        args = list(self.extra_args)
        if not self.enable_bncfu:
            return args
        args += [
            "--mico-bitnet-cfu",
            "--bitnet-cfu-qtype", self.qtype,
            "--bitnet-cfu-len", str(self.vlen),
            "--bitnet-cfu-width", str(self.width),
            "--bitnet-cfu-reg-depth", str(self.reg_depth),
            "--bitnet-cfu-bus-width", str(self.bus_width),
        ]
        if self.pipe:
            args.append("--bitnet-cfu-pipe")
        if self.with_q2:
            args.append("--bitnet-cfu-with-q2")
        args.append("--bitnet-cfu-with-q2t" if self.with_q2t else "--bitnet-cfu-without-q2t")
        if self.quant_width:
            args += ["--bitnet-cfu-quant-width", str(self.quant_width)]
        if self.with_q8:
            args.append("--bitnet-cfu-with-q8")
        if self.q8_compare_pipe:
            args.append("--bitnet-cfu-q8-compare-pipe")
        if self.quant_standard:
            args.append("--bitnet-cfu-quant-standard")
        args.append("--bitnet-cfu-rf-sync" if self.rf_sync else "--bitnet-cfu-rf-async")
        if self.stress:
            args.append("--bitnet-cfu-stress")
        return args


def preset_configs(name: str) -> List[Config]:
    if name == "existing":
        return [
            Config("base", enable_bncfu=False),
            Config("bncfu_v256_w64_r5", vlen=256, width=64, reg_depth=5, bus_width=64),
            Config("bncfu_v256_w128_r5", vlen=256, width=128, reg_depth=5, bus_width=64),
        ]
    if name == "quick":
        return [
            Config("base", enable_bncfu=False),
            Config("q15_v128_w64_r5", vlen=128, width=64, reg_depth=5, bus_width=64),
            Config("q15_v256_w64_r5", vlen=256, width=64, reg_depth=5, bus_width=64),
            Config("q15_v256_w128_r5", vlen=256, width=128, reg_depth=5, bus_width=64),
            Config("q15_v256_w128_r5_pipe", vlen=256, width=128, reg_depth=5, bus_width=64, pipe=True),
        ]
    if name == "qtype":
        return [
            Config("q1_v256_w128_r5", qtype="1b", vlen=256, width=128, reg_depth=5, bus_width=64),
            Config("q15_v256_w128_r5", qtype="1.5b", vlen=256, width=128, reg_depth=5, bus_width=64),
            Config("q2_v256_w128_r5", qtype="2b", vlen=256, width=128, reg_depth=5, bus_width=64, with_q2=True),
        ]
    if name == "features":
        return [
            Config("base", enable_bncfu=False),
            Config("bncfu_bdot_only", vlen=256, width=128, reg_depth=2, bus_width=64, with_q2t=False, quant_width=128, pipe=True),
            Config("bncfu_bdot_q2t", vlen=256, width=128, reg_depth=2, bus_width=64, with_q2t=True, quant_width=128, pipe=True),
        ]
    if name == "q8_q2t":
        base = dict(vlen=256, width=128, reg_depth=5, bus_width=64, pipe=True, qtype="1.5b")
        configs = [
            Config("base_soc", enable_bncfu=False),
            Config("bncfu_bdot_only", **base, with_q2t=False, with_q8=False),
            Config("bncfu_q2_unit", **base, with_q2=True, with_q2t=False, with_q8=False),
        ]
        configs += [
            Config(f"q2t_qw{w}", **base, with_q2t=True, quant_width=w, with_q8=False)
            for w in (32, 64, 128)
        ]
        configs += [
            Config(f"q8_qw{w}", **base, with_q2t=False, with_q8=True, quant_width=w)
            for w in (32, 64, 128)
        ]
        configs += [
            Config(f"q2t_q8_qw{w}", **base, with_q2t=True, with_q8=True, quant_width=w)
            for w in (32, 64, 128)
        ]
        return configs
    if name == "width":
        return [
            Config("base", enable_bncfu=False),
            Config("q15_v256_w32_r5", vlen=256, width=32, reg_depth=5, bus_width=64),
            Config("q15_v256_w64_r5", vlen=256, width=64, reg_depth=5, bus_width=64),
            Config("q15_v256_w128_r5", vlen=256, width=128, reg_depth=5, bus_width=64),
            Config("q15_v512_w128_r5", vlen=512, width=128, reg_depth=5, bus_width=64),
            Config("q15_v512_w256_r5", vlen=512, width=256, reg_depth=5, bus_width=64),
        ]
    raise SystemExit(f"Unknown preset: {name}")


def load_configs(path: Path) -> List[Config]:
    data = json.loads(path.read_text())
    if not isinstance(data, list):
        raise SystemExit("Config JSON must be a list of objects")
    valid = {f.name for f in fields(Config)}
    configs = []
    for entry in data:
        if not isinstance(entry, dict):
            raise SystemExit("Config JSON entries must be objects")
        entry = dict(entry)
        entry.pop("quant_normalized", None)
        entry.pop("quant_serial", None)
        configs.append(Config(**{k: v for k, v in entry.items() if k in valid}))
    return configs


def run(cmd: List[str], log_path: Path, cwd: Path = ROOT) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w") as log:
        log.write("$ " + " ".join(cmd) + "\n")
        log.flush()
        proc = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            log.write(line)
        rc = proc.wait()
    if rc != 0:
        raise subprocess.CalledProcessError(rc, cmd)


def generate_soc(cfg: Config, verilog_dir: Path, log_dir: Path, force: bool) -> None:
    target_v = verilog_dir / cfg.name / "MiCoSoc.v"
    if target_v.exists() and not force:
        print(f"[skip] gen {cfg.name}: {target_v} exists")
        return

    cmd = ["bash", "gen_soc_large.sh"] + cfg.gen_args
    run(cmd, log_dir / cfg.name / "gen.log")

    target_v.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "MiCoSoc.v", target_v)
    soc_h = ROOT / "soc.h"
    if soc_h.exists():
        shutil.copy2(soc_h, target_v.parent / "soc.h")


def synthesize(cfg: Config, verilog_dir: Path, rpt_dir: Path, log_dir: Path, force: bool) -> None:
    cfg_v = verilog_dir / cfg.name / "MiCoSoc.v"
    cfg_rpt = rpt_dir / cfg.name
    done = cfg_rpt / "timing_summary.rpt"
    if done.exists() and not force:
        print(f"[skip] synth {cfg.name}: {done} exists")
        return
    if not cfg_v.exists():
        raise FileNotFoundError(f"Missing Verilog for {cfg.name}: {cfg_v}")
    cmd = [
        "vivado", "-mode", "batch",
        "-source", "vivado_soc_synth.tcl",
        "-tclargs", str(cfg_v), str(cfg_rpt), TOP,
    ]
    run(cmd, log_dir / cfg.name / "vivado.log")


def number_from_util_line(text: str, label: str) -> Optional[float]:
    match = re.search(rf"\|\s*{re.escape(label)}\s*\|\s*([0-9.]+)", text)
    return float(match.group(1)) if match else None


def parse_util(path: Path) -> Dict[str, Optional[float]]:
    text = path.read_text(errors="ignore") if path.exists() else ""
    return {
        "lut": number_from_util_line(text, "CLB LUTs"),
        "ff": number_from_util_line(text, "CLB Registers"),
        "bram_tile": number_from_util_line(text, "Block RAM Tile"),
        "ramb36": number_from_util_line(text, "RAMB36/FIFO*"),
        "ramb18": number_from_util_line(text, "RAMB18"),
        "dsp": number_from_util_line(text, "DSPs"),
    }


def parse_power(path: Path) -> Dict[str, Optional[float]]:
    text = path.read_text(errors="ignore") if path.exists() else ""
    return {
        "power_total_w": number_from_util_line(text, "Total On-Chip Power (W)"),
        "power_dynamic_w": number_from_util_line(text, "Dynamic (W)"),
        "power_static_w": number_from_util_line(text, "Device Static (W)"),
    }


def first_match(text: str, pattern: str) -> Optional[str]:
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1) if match else None


def parse_timing(path: Path) -> Dict[str, Optional[float]]:
    text = path.read_text(errors="ignore") if path.exists() else ""
    period = first_match(text, r"period=([0-9.]+)ns")
    data_delay = first_match(text, r"Data Path Delay:\s*([0-9.]+)ns")
    logic_delay = first_match(text, r"Data Path Delay:\s*[0-9.]+ns\s*\(logic\s*([0-9.]+)ns")
    route_delay = first_match(text, r"Data Path Delay:\s*[0-9.]+ns\s*\(logic\s*[0-9.]+ns.*route\s*([0-9.]+)ns")
    source = first_match(text, r"^\s*Source:\s*(.+)$")
    destination = first_match(text, r"^\s*Destination:\s*(.+)$")
    period_ns = float(period) if period else None
    fmax_mhz = 1000.0 / period_ns if period_ns and period_ns > 0 else None
    wns_at_target = TARGET_PERIOD_NS - period_ns if period_ns else None
    return {
        "period_ns": period_ns,
        "fmax_mhz": fmax_mhz,
        "wns_at_4ns": wns_at_target,
        "data_delay_ns": float(data_delay) if data_delay else None,
        "logic_delay_ns": float(logic_delay) if logic_delay else None,
        "route_delay_ns": float(route_delay) if route_delay else None,
        "critical_source": source.strip() if source else None,
        "critical_destination": destination.strip() if destination else None,
    }


def qtype_bits(qtype: str) -> float:
    if qtype == "1b":
        return 1.0
    if qtype == "1.5b":
        return 2.0
    if qtype == "2b":
        return 2.0
    return 2.0


def estimate_perf(cfg: Config, fmax_mhz: Optional[float]) -> Dict[str, Optional[float]]:
    if not cfg.enable_bncfu:
        return {
            "lanes_per_dot": None,
            "dot_cycles": None,
            "load_cycles_per_reg": None,
            "reuse_outputs": None,
            "effective_cycles_per_dot": None,
            "peak_gops": None,
            "load_amortized_gops": None,
        }
    compute_steps = cfg.vlen // cfg.width
    dot_cycles = compute_steps + (1 if cfg.pipe else 0)
    load_cycles = cfg.vlen // cfg.bus_width
    reuse_outputs = max(cfg.reg_depth - 1, 1)
    lanes = cfg.vlen // 8
    effective_cycles = dot_cycles + (load_cycles / reuse_outputs)
    mhz = fmax_mhz or (1000.0 / TARGET_PERIOD_NS)
    # One low-bit lane selects +/-/0 times one int8 activation. Count it as one MAC-like op.
    peak_gops = lanes * mhz / dot_cycles / 1000.0
    load_amortized_gops = lanes * mhz / effective_cycles / 1000.0
    return {
        "lanes_per_dot": lanes,
        "dot_cycles": dot_cycles,
        "load_cycles_per_reg": load_cycles,
        "reuse_outputs": reuse_outputs,
        "effective_cycles_per_dot": effective_cycles,
        "peak_gops": peak_gops,
        "load_amortized_gops": load_amortized_gops,
    }


def collect(cfg: Config, rpt_dir: Path) -> Dict[str, object]:
    cfg_rpt = rpt_dir / cfg.name
    row: Dict[str, object] = {
        "name": cfg.name,
        "enable_bncfu": cfg.enable_bncfu,
        "qtype": cfg.qtype if cfg.enable_bncfu else "",
        "vlen": cfg.vlen if cfg.enable_bncfu else "",
        "width": cfg.width if cfg.enable_bncfu else "",
        "reg_depth": cfg.reg_depth if cfg.enable_bncfu else "",
        "bus_width": cfg.bus_width if cfg.enable_bncfu else "",
        "pipe": cfg.pipe if cfg.enable_bncfu else "",
        "with_q2": cfg.with_q2 if cfg.enable_bncfu else "",
        "with_q2t": cfg.with_q2t if cfg.enable_bncfu else "",
        "with_q8": cfg.with_q8 if cfg.enable_bncfu else "",
        "quant_width": cfg.quant_width if cfg.enable_bncfu else "",
        "q8_compare_pipe": cfg.q8_compare_pipe if cfg.enable_bncfu else "",
        "quant_standard": cfg.quant_standard if cfg.enable_bncfu else "",
        "rf_sync": cfg.rf_sync if cfg.enable_bncfu else "",
    }
    row.update(parse_util(cfg_rpt / "util.rpt"))
    row.update(parse_power(cfg_rpt / "power.rpt"))
    timing = parse_timing(cfg_rpt / "timing_summary.rpt")
    row.update(timing)
    row.update(estimate_perf(cfg, timing["fmax_mhz"]))
    return row


def write_csv(rows: List[Dict[str, object]], path: Path) -> None:
    keys: List[str] = []
    for row in rows:
        for key in row:
            if key not in keys:
                keys.append(key)
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


def write_markdown(rows: List[Dict[str, object]], path: Path) -> None:
    cols = [
        ("name", "config"),
        ("qtype", "q"),
        ("vlen", "VLEN"),
        ("width", "WIDTH"),
        ("reg_depth", "REG"),
        ("pipe", "pipe"),
        ("with_q2t", "Q2T"),
        ("with_q8", "Q8"),
        ("quant_width", "QWIDTH"),
        ("q8_compare_pipe", "Q8_CPIPE"),
        ("quant_standard", "Q_STD"),
        ("rf_sync", "RF_SYNC"),
        ("lut", "LUT"),
        ("ff", "FF"),
        ("bram_tile", "BRAM tile"),
        ("dsp", "DSP"),
        ("period_ns", "period ns"),
        ("fmax_mhz", "Fmax MHz"),
        ("wns_at_4ns", "WNS@4ns"),
        ("peak_gops", "peak GOPS"),
        ("load_amortized_gops", "amort GOPS"),
        ("power_total_w", "power W"),
    ]
    lines = ["# BNCFU Space Exploration", ""]
    lines.append("| " + " | ".join(title for _, title in cols) + " |")
    lines.append("| " + " | ".join("---" for _ in cols) + " |")
    for row in rows:
        lines.append("| " + " | ".join(fmt(row.get(key)) for key, _ in cols) + " |")
    lines += [
        "",
        "Notes:",
        f"- `WNS@4ns` is reconstructed as `{TARGET_PERIOD_NS}ns - reported_min_period`; negative means the original 250 MHz target is not met.",
        "- GOPS is a model estimate from VLEN/WIDTH/Fmax, not a software benchmark result.",
        "- `amort GOPS` adds one vector load cost per register and amortizes it over `regDepth - 1` reused output dots.",
    ]
    path.write_text("\n".join(lines) + "\n")


def plot(rows: List[Dict[str, object]], out_dir: Path) -> None:
    os.environ.setdefault("MPLCONFIGDIR", str(out_dir / ".matplotlib"))
    try:
        import matplotlib.pyplot as plt
    except Exception as e:
        print(f"[warn] matplotlib unavailable, skip plots: {e}")
        return

    bn_rows = [r for r in rows if r.get("enable_bncfu") and r.get("lut") and r.get("fmax_mhz")]
    if not bn_rows:
        print("[warn] no BNCFU rows with LUT/Fmax, skip plots")
        return

    x = [float(r["lut"]) for r in bn_rows]
    y = [float(r["fmax_mhz"]) for r in bn_rows]
    c = [float(r.get("load_amortized_gops") or 0.0) for r in bn_rows]
    labels = [str(r["name"]) for r in bn_rows]

    fig, ax = plt.subplots(figsize=(8.5, 5.0), dpi=140)
    sc = ax.scatter(x, y, c=c, s=95, cmap="viridis", edgecolors="black", linewidths=0.5)
    for xi, yi, label in zip(x, y, labels):
        ax.annotate(label, (xi, yi), textcoords="offset points", xytext=(5, 4), fontsize=8)
    ax.set_xlabel("CLB LUTs")
    ax.set_ylabel("Estimated Fmax (MHz)")
    ax.set_title("BNCFU Area/Frequency Exploration")
    ax.grid(True, alpha=0.25)
    cb = fig.colorbar(sc, ax=ax)
    cb.set_label("Load-amortized GOPS estimate")
    fig.tight_layout()
    fig.savefig(out_dir / "area_fmax.png")
    fig.savefig(out_dir / "area_fmax.svg")
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(8.5, 4.8), dpi=140)
    names = [str(r["name"]) for r in bn_rows]
    peak = [float(r.get("peak_gops") or 0.0) for r in bn_rows]
    amort = [float(r.get("load_amortized_gops") or 0.0) for r in bn_rows]
    idx = list(range(len(names)))
    width = 0.38
    ax.bar([i - width / 2 for i in idx], peak, width, label="peak")
    ax.bar([i + width / 2 for i in idx], amort, width, label="load amortized")
    ax.set_xticks(idx)
    ax.set_xticklabels(names, rotation=25, ha="right", fontsize=8)
    ax.set_ylabel("Estimated GOPS")
    ax.set_title("BNCFU Normalized Throughput Estimate")
    ax.legend()
    ax.grid(True, axis="y", alpha=0.25)
    fig.tight_layout()
    fig.savefig(out_dir / "throughput.png")
    fig.savefig(out_dir / "throughput.svg")
    plt.close(fig)


def summarize_deltas(rows: List[Dict[str, object]], path: Path) -> None:
    base = next((r for r in rows if r.get("name") == "bncfu_bdot_only"), None)
    if not base:
        return

    metrics = ("lut", "ff", "bram_tile", "dsp", "period_ns", "fmax_mhz", "wns_at_4ns", "power_total_w")
    lines = ["# BNCFU Feature Delta Summary", ""]
    lines.append("Baseline: `bncfu_bdot_only`.")
    lines.append("")
    lines.append("| config | QWIDTH | dLUT | dFF | dBRAM | dDSP | dPeriod ns | dFmax MHz | dPower W |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in rows:
        if not row.get("enable_bncfu") or row.get("name") == "bncfu_bdot_only":
            continue
        deltas = {}
        for metric in metrics:
            a = row.get(metric)
            b = base.get(metric)
            deltas[metric] = (float(a) - float(b)) if a not in (None, "") and b not in (None, "") else None
        lines.append(
            "| "
            + " | ".join([
                str(row.get("name")),
                fmt(row.get("quant_width")),
                fmt(deltas["lut"]),
                fmt(deltas["ff"]),
                fmt(deltas["bram_tile"]),
                fmt(deltas["dsp"]),
                fmt(deltas["period_ns"], 3),
                fmt(deltas["fmax_mhz"]),
                fmt(deltas["power_total_w"], 3),
            ])
            + " |"
        )
    path.write_text("\n".join(lines) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate, synthesize, parse and plot BNCFU SoC design-space points.")
    parser.add_argument("--preset", default="quick", choices=["existing", "quick", "qtype", "features", "width", "q8_q2t"])
    parser.add_argument("--config-json", type=Path, help="JSON list of config objects. Overrides --preset.")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--legacy-rpt-dir", type=Path, help="Parse an existing report tree, e.g. synth_runs/rpt.")
    parser.add_argument("--report-only", action="store_true", help="Only parse reports and generate tables/plots.")
    parser.add_argument("--generate-only", action="store_true", help="Stop after Verilog generation.")
    parser.add_argument("--synth-only", action="store_true", help="Assume Verilog exists and only run Vivado/report collection.")
    parser.add_argument("--force-gen", action="store_true")
    parser.add_argument("--force-synth", action="store_true")
    args = parser.parse_args()

    configs = load_configs(args.config_json) if args.config_json else preset_configs(args.preset)
    out_dir = args.out_dir.resolve()
    verilog_dir = out_dir / "verilog"
    rpt_dir = args.legacy_rpt_dir.resolve() if args.legacy_rpt_dir else out_dir / "rpt"
    log_dir = out_dir / "logs"
    out_dir.mkdir(parents=True, exist_ok=True)

    start = time.time()
    if not args.report_only:
        for cfg in configs:
            print(f"\n=== {cfg.name} ===")
            if not args.synth_only:
                generate_soc(cfg, verilog_dir, log_dir, args.force_gen)
            if args.generate_only:
                continue
            synthesize(cfg, verilog_dir, rpt_dir, log_dir, args.force_synth)

    rows = [collect(cfg, rpt_dir) for cfg in configs]
    (out_dir / "results.json").write_text(json.dumps(rows, indent=2) + "\n")
    write_csv(rows, out_dir / "results.csv")
    write_markdown(rows, out_dir / "summary.md")
    summarize_deltas(rows, out_dir / "deltas.md")
    plot(rows, out_dir)
    elapsed = time.time() - start
    print(f"\nWrote {out_dir / 'results.csv'}")
    print(f"Wrote {out_dir / 'summary.md'}")
    print(f"Wrote {out_dir / 'deltas.md'}")
    print(f"Elapsed {elapsed / 60.0:.1f} min")


if __name__ == "__main__":
    main()
