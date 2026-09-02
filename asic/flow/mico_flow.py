#!/usr/bin/env python3
"""Generate and run the MiCoSoc ASAP7 SiliconCompiler flow."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

from generate_sram_wrappers import (
    classify_memory,
    select_macro,
    storage_width_for_mask,
)
from prepare_views import prepare


ROOT = Path(__file__).resolve().parents[2]
FLOW_DIR = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preset", choices=("minimal", "minimal-bncfu", "base", "bncfu", "bncfu-v2"), default="base")
    parser.add_argument("--step", choices=("syn", "asic"), default="syn")
    parser.add_argument("--sram-backend", choices=("soft", "asap7"), default="soft")
    parser.add_argument(
        "--abstract-only", action="store_true",
        help="use all abstract SRAM views and stop after global route (no GDS)",
    )
    parser.add_argument(
        "--abstract-detailed-route", action="store_true",
        help="extend --abstract-only through detailed route (memory-heavy)",
    )
    parser.add_argument("--scope", choices=("soc", "cpu-cfu"), default="soc")
    parser.add_argument("--ram-kbytes", type=int, default=None)
    parser.add_argument("--bitnet-cfu-len", type=int, default=256)
    parser.add_argument("--bitnet-cfu-width", type=int, default=128)
    parser.add_argument("--bitnet-cfu-reg-depth", type=int, default=5)
    parser.add_argument("--bitnet-cfu-bus-width", type=int, default=32)
    parser.add_argument("--bitnet-cfu-qtype", default="1.5b")
    parser.add_argument("--bitnet-cfu-quant-width", type=int, default=128)
    parser.add_argument("--with-q2t", action="store_true")
    parser.add_argument("--with-q8", action="store_true")
    parser.add_argument("--q8-compare-pipe", action="store_true")
    parser.add_argument("--bitnet-cfu-pipe", action="store_true")
    parser.add_argument("--bitnet-cfu-v2-dot-pipe-stages", type=int, default=1)
    parser.add_argument("--bitnet-cfu-v2-quant-pipe-stages", type=int, default=1)
    parser.add_argument("--bitnet-cfu-v2-load-buffer-depth", type=int, default=0)
    parser.add_argument(
        "--clock-period", type=float, default=2000.0,
        help="clock period in ASAP7 Liberty time units (ps)",
    )
    parser.add_argument(
        "--fast-placement", action="store_true",
        help="skip optional timing-repair nodes for bounded P&R smoke runs",
    )
    parser.add_argument(
        "--grt-overflow-iter", type=int, default=100,
        help="OpenROAD global-route overflow iterations (default: 100)",
    )
    parser.add_argument(
        "--drt-end-iteration", type=int, default=None,
        help="bound OpenROAD detailed-route optimization iterations",
    )
    parser.add_argument(
        "--grt-allow-congestion", action="store_true",
        help="allow global route to continue with residual congestion",
    )
    parser.add_argument(
        "--grt-use-pin-access", action="store_true",
        help="run OpenROAD pin-access preparation before global route",
    )
    parser.add_argument("--no-btb", action="store_true",
                        help="omit the soft BTB array for a lightweight APR smoke")
    parser.add_argument("--from-step", help="resume ASIC flow from this SiliconCompiler step")
    parser.add_argument("--to-step", help="stop ASIC flow after this SiliconCompiler step")
    parser.add_argument("--build-dir", type=Path, default=ROOT / "asic" / "build")
    parser.add_argument("--run-name", default="default")
    parser.add_argument("--sbt-env", default="mico_env", help="Conda environment containing sbt")
    parser.add_argument("--skip-generate", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    if args.ram_kbytes is None:
        args.ram_kbytes = 1 if args.preset in ("minimal", "minimal-bncfu") else 512
    return args


def main_ram_word_count(ram_kbytes: int) -> int:
    if ram_kbytes <= 0:
        raise ValueError("ram_kbytes must be positive")
    # RamFiber's TileLink data width is 32 bits, so each word occupies 4 bytes.
    return ram_kbytes * 1024 // 4


def _parse_verilog_int(value: str) -> int:
    value = value.strip().replace("_", "")
    match = re.fullmatch(r"(?:(\d+)'([bBoOdDhH])([0-9a-fA-F]+)|(-?\d+))", value)
    if not match:
        raise ValueError(f"unsupported Verilog integer literal: {value}")
    if match.group(4) is not None:
        return int(match.group(4), 10)
    base = {"b": 2, "o": 8, "d": 10, "h": 16}[match.group(2).lower()]
    return int(match.group(3), base)


def active_sram_macros(rtl: Path, manifest: dict) -> tuple[set[str], list[dict]]:
    """Resolve actual SRAM macros from generated Spinal RAM parameters."""
    instance_re = re.compile(
        r"\b(Ram_1wrs|Ram_1w_1rs)\s*#\s*\((.*?)\)\s+[A-Za-z_]\w*\s*\(",
        re.DOTALL,
    )
    parameter_re = re.compile(r"\.(\w+)\s*\(\s*([^)]*)\)")
    names = set()
    records = []
    for match in instance_re.finditer(rtl.read_text()):
        params = {
            name: _parse_verilog_int(value)
            for name, value in parameter_re.findall(match.group(2))
            if "'" in value or value.strip().isdigit()
        }
        if match.group(1) == "Ram_1wrs":
            width = params["wordWidth"]
            mask_width = params["maskWidth"]
            mask_enable = bool(params.get("maskEnable", 1))
        else:
            width = params["wrDataWidth"]
            mask_width = params["wrMaskWidth"]
            mask_enable = bool(params.get("wrMaskEnable", 0))
        kind = classify_memory(width, mask_width, mask_enable)
        carrier_width = storage_width_for_mask(width, mask_width, mask_enable)
        depth = params["wordCount"]
        macro = select_macro(manifest, carrier_width, depth)
        names.add(macro["name"])
        records.append({
            "kind": kind,
            "logical_width": width,
            "mask_width": mask_width,
            "carrier_width": carrier_width,
            "word_count": depth,
            "macro": macro["name"],
        })
    return names, records


def generator_args(args: argparse.Namespace, staging: Path) -> list[str]:
    # The minimal preset intentionally passes no VexiiRiscv tuning flags.
    # MiCoSoc still needs a bounded RAM for a practical physical smoke.
    if args.preset in ("minimal", "minimal-bncfu"):
        result = [
            "--ram-kbytes", str(args.ram_kbytes),
            "--netlist-directory", str(staging), "--netlist-name", "MiCoSoc",
        ]
    else:
        result = [
            "--with-rvc", "--with-rvm", "--with-late-alu", "--allow-bypass-from", "0",
            "--div-radix", "4", "--lsu-l1", "--fetch-l1",
            "--ram-kbytes", str(args.ram_kbytes),
            "--netlist-directory", str(staging), "--netlist-name", "MiCoSoc",
        ]
    if args.sram_backend == "asap7":
        result.append("--asic-sram")
    if args.preset not in ("minimal", "minimal-bncfu") and not args.no_btb:
        result.append("--with-btb")
        if args.sram_backend == "asap7":
            result.append("--btb-single-port-ram")
    if args.preset in ("minimal-bncfu", "bncfu"):
        result += [
            "--mico-bitnet-cfu", "--bitnet-cfu-len", str(args.bitnet_cfu_len),
            "--bitnet-cfu-width", str(args.bitnet_cfu_width),
            "--bitnet-cfu-reg-depth", str(args.bitnet_cfu_reg_depth),
            "--bitnet-cfu-bus-width", str(args.bitnet_cfu_bus_width),
            "--bitnet-cfu-qtype", args.bitnet_cfu_qtype,
            "--bitnet-cfu-quant-width", str(args.bitnet_cfu_quant_width),
        ]
        if args.with_q2t:
            result.append("--bitnet-cfu-with-q2t")
        else:
            result.append("--bitnet-cfu-without-q2t")
        if args.with_q8:
            result.append("--bitnet-cfu-with-q8")
        if args.q8_compare_pipe:
            result.append("--bitnet-cfu-q8-compare-pipe")
        if args.bitnet_cfu_pipe:
            result.append("--bitnet-cfu-pipe")
    elif args.preset == "bncfu-v2":
        result += [
            "--mico-bitnet-cfu-v2", "--bitnet-cfu-len", str(args.bitnet_cfu_len),
            "--bitnet-cfu-width", str(args.bitnet_cfu_width),
            "--bitnet-cfu-reg-depth", str(args.bitnet_cfu_reg_depth),
            "--bitnet-cfu-bus-width", str(args.bitnet_cfu_bus_width),
            "--bitnet-cfu-qtype", args.bitnet_cfu_qtype,
            "--bitnet-cfu-quant-width", str(args.bitnet_cfu_quant_width),
            "--bitnet-cfu-v2-dot-pipe-stages", str(args.bitnet_cfu_v2_dot_pipe_stages),
            "--bitnet-cfu-v2-quant-pipe-stages", str(args.bitnet_cfu_v2_quant_pipe_stages),
            "--bitnet-cfu-v2-load-buffer-depth", str(args.bitnet_cfu_v2_load_buffer_depth),
        ]
        if args.with_q2t:
            result.append("--bitnet-cfu-with-q2t")
        else:
            result.append("--bitnet-cfu-without-q2t")
        if args.with_q8:
            result.append("--bitnet-cfu-with-q8")
    return result


def generate_rtl(args: argparse.Namespace, staging: Path) -> Path:
    staging.mkdir(parents=True, exist_ok=True)
    sbt_args = ["vexiiriscv.soc.mico.MiCoSocGen", *generator_args(args, staging)]
    sbt_command = "runMain " + " ".join(shlex.quote(item) for item in sbt_args)
    command = ["conda", "run", "-n", args.sbt_env, "sbt", sbt_command]
    print("Generating RTL:", " ".join(shlex.quote(item) for item in command))
    subprocess.run(command, cwd=ROOT, check=True)
    rtl = staging / "MiCoSoc.v"
    if not rtl.is_file():
        raise RuntimeError(f"Spinal generation completed without {rtl}")
    return rtl


def configure_project(
    args: argparse.Namespace,
    rtl: Path,
    sdc: Path,
    wrapper: Path | None,
    prepared: dict | None,
    blackbox_cells: Path | None,
    active_macro_names: set[str] | None,
):
    from siliconcompiler import ASIC, Design
    from siliconcompiler.flows import asicflow, synflow
    from siliconcompiler.targets import asap7_demo

    design = Design("MiCoSoc")
    design.set_topmodule("MiCoSoc", fileset="rtl")
    design.add_file(rtl, fileset="rtl", filetype="verilog")
    design.add_define("SYNTHESIS", fileset="rtl")
    if wrapper is not None:
        design.add_file(wrapper, fileset="rtl", filetype="verilog")
        if blackbox_cells is None:
            raise ValueError("SRAM wrapper generation requires blackbox declarations")
        design.add_file(blackbox_cells, fileset="rtl", filetype="verilog")
    design.add_file(sdc, fileset="sdc", filetype="sdc")

    project = ASIC(design)
    project.add_fileset(["rtl", "sdc"])
    asap7_demo(project, language="verilog")
    # ASAP7 synthesis uses RVT cells alongside the target's LVT/SLVT libraries.
    # Keep RVT in the physical library list so stream export includes its GDS.
    project.add("asic", "asiclib", "asap7sc7p5t_rvt")
    if args.step == "syn":
        project.set_flow(synflow.SynthesisFlow(language="verilog"))
    else:
        # SC 0.38.5 cleanup_synth records utilization=-100 before floorplanning,
        # which violates its metric schema; its buffer cleanup is optional here.
        asic_flow = asicflow.ASICFlow(name="mico-asicflow-verilog", language="verilog")
        asic_flow.remove_node("cleanup.clean")
        if args.fast_placement:
            asic_flow.remove_node("place.repair_design")
            asic_flow.remove_node("cts.repair_timing")
        project.set_flow(asic_flow)
    if args.fast_placement and args.step == "asic":
        project.set("tool", "openroad", "task", "global_placement", "var",
                    "gpl_timing_driven", False)
        project.set("tool", "openroad", "task", "global_placement", "var",
                    "gpl_routability_driven", False)
    if args.step == "asic":
        if args.grt_overflow_iter <= 0:
            raise ValueError("grt_overflow_iter must be positive")
        project.set("tool", "openroad", "task", "global_route", "var",
                    "grt_overflow_iter", args.grt_overflow_iter)
        drt_end_iteration = args.drt_end_iteration
        if drt_end_iteration is None and args.fast_placement:
            # SiliconCompiler 0.38.5 accepts detailed-route bounds from 1 to 64.
            drt_end_iteration = 1
        if drt_end_iteration is not None:
            if drt_end_iteration < 0:
                raise ValueError("drt_end_iteration must be non-negative")
            project.set("tool", "openroad", "task", "detailed_route", "var",
                        "drt_end_iteration", drt_end_iteration)
        if args.grt_allow_congestion:
            project.set("tool", "openroad", "task", "global_route", "var",
                        "grt_allow_congestion", True)
        if args.grt_use_pin_access:
            project.set("library", "asap7", "pdk", "minlayer", "M2")
            project.set("library", "asap7", "pdk", "maxlayer", "M7")
            project.set("tool", "openroad", "task", "global_route", "var",
                        "grt_use_pin_access", True)
            project.add("tool", "openroad", "task", "global_route", "prescript",
                        str(ROOT / "asic" / "constraints" / "openroad_pin_access_compat.tcl"))
    if prepared is not None:
        from asap7sram_library import MiCoASAP7SramLibrary
        project.add_asiclib(
            MiCoASAP7SramLibrary(prepared, FLOW_DIR, active_macro_names)
        )
        project.add("tool", "openroad", "task", "macro_placement", "var", "mpl_constraints", str(ROOT / "asic" / "constraints" / "macro_placement.tcl"))
        project.set("tool", "openroad", "task", "macro_placement", "var", "macro_place_halo", (1.0, 1.0))
    return project


def collect_report_metrics(args: argparse.Namespace, build_dir: Path) -> dict:
    run_dir = build_dir / "sc" / "MiCoSoc" / args.run_name
    report_dirs = [
        run_dir / node / "0" / "reports"
        for node in (
            "dfm.metal_fill", "route.detailed_antenna_repair", "route.detailed",
            "route.global", "cts.repair_timing", "cts.clock_tree_synthesis",
            "place.detailed", "floorplan.init", "synthesis.timing", "timing",
        )
    ]
    report_dir = next(
        (path for path in report_dirs if (path / "design" / "area.rpt").is_file()),
        None,
    )
    if report_dir is None:
        return {}
    result = {}

    area = report_dir / "design" / "area.rpt"
    if area.is_file():
        match = re.search(r"Design area:\s*([0-9.eE+-]+)", area.read_text())
        if match:
            result["totalarea_um2"] = float(match.group(1))

    fmax = report_dir / "clocks" / "fmax.rpt"
    if fmax.is_file():
        for line in fmax.read_text().splitlines():
            fields = line.split()
            if fields and fields[0] == "system_clk" and len(fields) >= 4:
                result["fmax_mhz"] = float(fields[3])
                break

    for filename, key, pattern in (
        ("worst_slack.setup.rpt", "setupwns_ns", r"worst slack max\s+([0-9.eE+-]+)"),
        ("total_negative_slack.setup.rpt", "setuptns_ns", r"tns max\s+([0-9.eE+-]+)"),
        ("worst_slack.hold.rpt", "holdwns_ns", r"worst slack min\s+([0-9.eE+-]+)"),
        ("total_negative_slack.hold.rpt", "holdtns_ns", r"tns min\s+([0-9.eE+-]+)"),
    ):
        path = report_dir / "timing" / filename
        if path.is_file():
            match = re.search(pattern, path.read_text(), re.IGNORECASE)
            if match:
                result[key] = float(match.group(1)) / 1000.0

    power = report_dir / "power" / "typical.rpt"
    if power.is_file():
        for line in power.read_text().splitlines():
            fields = line.split()
            if fields and fields[0] == "Total" and len(fields) >= 5:
                result["power_w"] = float(fields[4])
                break
    return result


def collect_metrics(project, args: argparse.Namespace, build_dir: Path, wrapper_metadata: dict | None) -> None:
    metrics = {
        "design": "MiCoSoc",
        "preset": args.preset,
        "step": args.step,
        "sram_backend": args.sram_backend,
        "abstract_only": args.abstract_only,
        "abstract_route_stage": (
            "route.detailed" if args.abstract_detailed_route else "route.global"
        ) if args.abstract_only else None,
        "scope": {
            "requested": args.scope,
            "physical_top": "MiCoSoc",
            "policy": "cpu-cfu uses the real full-SoC context; P&R is not replaced by a bus stub",
        },
        "ram_kbytes": args.ram_kbytes,
        "wrapper": wrapper_metadata,
        "fallback_policy": "non-single-port RAMs remain soft and are synthesized as FF",
        "sc_metrics": {},
    }
    for key in ("cellarea", "macroarea", "peakpower", "setupwns", "setuptns", "holdwns", "holdtns"):
        for step in (
            "synthesis", "synthesis.timing", "timing", "floorplan.init",
            "floorplan.macro_placement", "place.global", "place.detailed",
            "cts.clock_tree_synthesis", "cts.repair_timing", "route.global",
            "route.detailed", "dfm.metal_fill",
        ):
            try:
                value = project.get("metric", key, step=step, index=0)
            except Exception:
                continue
            if value is not None:
                metrics["sc_metrics"][f"{step}.{key}"] = value
    metrics["sc_reports"] = collect_report_metrics(args, build_dir)
    metrics["sc_metrics"].update(metrics["sc_reports"])
    output = build_dir / "metrics.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metrics, indent=2, default=str) + "\n")
    with output.with_suffix(".summary.txt").open("w") as fd:
        project.summary(fd=fd)
    print(f"wrote {output}")


def main() -> int:
    args = parse_args()
    run_root = args.build_dir.resolve() / args.preset / args.run_name
    if args.abstract_only and args.sram_backend != "asap7":
        raise ValueError("--abstract-only requires --sram-backend asap7")
    if args.abstract_detailed_route and not args.abstract_only:
        raise ValueError("--abstract-detailed-route requires --abstract-only")
    manifest_path = FLOW_DIR / (
        "sram_views_abstract.json" if args.abstract_only else "sram_views.json"
    )
    if args.sram_backend == "asap7":
        prepared = prepare(
            manifest_path.resolve(),
            run_root / "prepared_sram_views.json",
            None if args.abstract_only else run_root / "prepared_gds",
        )
    else:
        prepared = None

    staging = run_root / "rtl"
    if args.skip_generate:
        rtl = staging / "MiCoSoc.v"
        if not rtl.is_file():
            raise RuntimeError(f"--skip-generate requested but {rtl} is absent")
    else:
        rtl = generate_rtl(args, staging)

    sdc = staging / "micosoc.sdc"
    sdc.write_text(
        (ROOT / "asic" / "constraints" / "micosoc.sdc")
        .read_text()
        .replace("-period 2000.0", f"-period {args.clock_period}")
    )

    wrapper = None
    wrapper_metadata = None
    blackbox_cells = None
    if args.sram_backend == "asap7":
        wrapper = staging / "spinal_ram_wrappers.v"
        blackbox_cells = ROOT / "asic" / "rtl" / "asap7_sram_cells.v"
        if args.abstract_only:
            blackbox_cells = staging / "asap7_sram_cells_abstract.v"
        main_word_count = main_ram_word_count(args.ram_kbytes)
        rf_count = args.bitnet_cfu_reg_depth if args.preset in ("minimal-bncfu", "bncfu", "bncfu-v2") else 1
        rf_width = args.bitnet_cfu_bus_width if args.preset in ("minimal-bncfu", "bncfu", "bncfu-v2") else 8
        command = [
            sys.executable, str(FLOW_DIR / "generate_sram_wrappers.py"),
            "--manifest", str(manifest_path), "--output", str(wrapper),
            "--main-word-count", str(main_word_count), "--main-word-width", "32",
        ]
        command += ["--rf-word-count", str(rf_count), "--rf-word-width", str(rf_width)]
        if args.abstract_only:
            command += ["--blackbox-output", str(blackbox_cells)]
        subprocess.run(command, cwd=ROOT, check=True)
        wrapper_metadata = json.loads(wrapper.with_suffix(".json").read_text())

    active_macro_names = set()
    active_memory_records = []
    if prepared is not None:
        active_macro_names, active_memory_records = active_sram_macros(rtl, prepared)
    if not active_macro_names and wrapper_metadata:
        for metadata in wrapper_metadata.values():
            if metadata:
                active_macro_names.update(
                    metadata.get("physical_macros", [metadata["macro"]])
                )
    if wrapper_metadata is not None:
        wrapper_metadata["active_macros"] = sorted(active_macro_names)
        wrapper_metadata["memory_instances"] = active_memory_records
    project = configure_project(
        args, rtl, sdc, wrapper, prepared, blackbox_cells, active_macro_names or None
    )
    project.option.set_builddir(str(run_root / "sc"))
    project.option.set_jobname(args.run_name)
    if args.from_step:
        project.option.add_from(args.from_step, clobber=True)
    if args.to_step:
        project.option.add_to(args.to_step, clobber=True)
    if args.abstract_only and args.step == "asic":
        # Abstract SRAM views have no GDS; stop at route reports by default.
        stop_step = "route.detailed" if args.abstract_detailed_route else "route.global"
        project.option.add_to(stop_step, clobber=True)
    if args.verbose:
        project.set("option", "verbose", True)
    if args.step == "asic":
        # OpenROAD may initialize its Qt GUI while writing APR reports; keep CI/headless runs display-free.
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    print(f"Running SiliconCompiler {args.step} flow in {run_root / 'sc'}")
    project.run()
    collect_metrics(project, args, run_root, wrapper_metadata)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
