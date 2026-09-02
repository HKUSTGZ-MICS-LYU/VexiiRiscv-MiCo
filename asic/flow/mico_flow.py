#!/usr/bin/env python3
"""Generate and run the MiCoSoc SiliconCompiler ASIC flow."""

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
    select_packed_macro,
    storage_width_for_mask,
)
from prepare_views import parse_verilog, prepare
from ics55_assets import resolve_pdk_root


ROOT = Path(__file__).resolve().parents[2]
FLOW_DIR = Path(__file__).resolve().parent
PDK_DIR = ROOT / "asic" / "pdk"
if str(PDK_DIR) not in sys.path:
    sys.path.insert(0, str(PDK_DIR))
from download_ics55_sram_pdk import MUX_RULES, PVT_CORNERS, RINGS, download_package


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preset", choices=("minimal", "minimal-bncfu", "base", "bncfu", "bncfu-v2"), default="base")
    parser.add_argument("--step", choices=("syn", "asic"), default="syn")
    parser.add_argument("--pdk", choices=("asap7", "ics55"), default="asap7")
    parser.add_argument("--ics55-pdk-root", type=Path)
    parser.add_argument("--ics55-sram-manifest", type=Path)
    parser.add_argument(
        "--ics55-sram-words", type=int, default=None,
        help="minimum downloaded depth; otherwise derive it from generated RTL",
    )
    parser.add_argument(
        "--ics55-sram-bits", type=int, default=None,
        help="minimum downloaded width; otherwise derive it from generated RTL",
    )
    parser.add_argument(
        "--ics55-sram-mux", type=int, choices=tuple(MUX_RULES), default=None,
        help="preferred column mux; automatically fall back when incompatible",
    )
    parser.add_argument("--ics55-sram-vt", type=int, choices=(0, 2, 5), default=0)
    parser.add_argument("--ics55-sram-low-power", type=int, choices=(0, 2), default=0)
    parser.add_argument("--ics55-sram-redundancy", type=int, choices=(0, 3), default=0)
    parser.add_argument("--ics55-sram-word-write", type=int, choices=(0, 1), default=0)
    parser.add_argument("--ics55-sram-bus-format", type=int, choices=(0, 1), default=1)
    parser.add_argument("--ics55-sram-ring", choices=RINGS, default="ringless")
    parser.add_argument("--ics55-sram-corner", choices=PVT_CORNERS, default="TT1P2V25CCTYP")
    parser.add_argument("--ics55-sram-output-dir", type=Path)
    parser.add_argument("--ics55-sram-base-url", default=None)
    parser.add_argument("--ics55-sram-timeout", type=float, default=60.0)
    parser.add_argument("--ics55-sram-force-download", action="store_true")
    parser.add_argument("--sram-backend", choices=("soft", "asap7", "ics55"), default="soft")
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
        "--clock-period", type=float, default=None,
        help="clock period in the selected PDK Liberty time units",
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
    if args.clock_period is None:
        args.clock_period = 2000.0 if args.pdk == "asap7" else 2.0
    if args.ram_kbytes is None:
        args.ram_kbytes = 1 if args.preset in ("minimal", "minimal-bncfu") else 512
    return args


def main_ram_word_count(ram_kbytes: int) -> int:
    if ram_kbytes <= 0:
        raise ValueError("ram_kbytes must be positive")
    # RamFiber's TileLink data width is 32 bits, so each word occupies 4 bytes.
    return ram_kbytes * 1024 // 4


def ics55_sram_payload(args: argparse.Namespace) -> dict:
    """Build common downloader options, with legacy-compatible defaults."""
    return {
        "words": args.ics55_sram_words or 2048,
        "bits": args.ics55_sram_bits or 32,
        "mux": args.ics55_sram_mux or 8,
        "vt": args.ics55_sram_vt,
        "lowPower": args.ics55_sram_low_power,
        "redundancy": args.ics55_sram_redundancy,
        "wordWrite": args.ics55_sram_word_write,
        "busFormat": args.ics55_sram_bus_format,
        "ring": args.ics55_sram_ring,
        "corner": args.ics55_sram_corner,
    }


def _find_downloaded_lib(package_dir: Path, token: str) -> Path:
    matches = sorted(
        path for path in (package_dir / "lib").glob("*.lib")
        if token.lower() in path.name.lower()
    )
    if not matches:
        raise ValueError(f"downloaded SRAM package has no Liberty view matching {token!r}")
    return matches[0]


def _download_words_at_least(words: int, mux: int) -> int:
    """Round a requested depth to one legal downloader package depth."""
    minimum, maximum, step, _, _ = MUX_RULES[mux]
    if words > maximum:
        return maximum
    return minimum + max(0, (words - minimum + step - 1) // step) * step


def _choose_download_mux(bits: int, preferred: int | None) -> int:
    """Choose a mux that supports the required width, honoring a preference."""
    candidates = [
        mux for mux, (_, _, _, min_bits, max_bits) in MUX_RULES.items()
        if min_bits <= bits <= max_bits
    ]
    if not candidates:
        raise ValueError(
            f"no ICS55 SRAM mux supports the required carrier width {bits} bits"
        )
    if preferred in candidates:
        return preferred
    default = 8
    if default in candidates:
        return default
    return min(candidates)


def sram_memory_requirements(rtl: Path) -> list[dict]:
    """Extract the carrier width/depth required by each Spinal RAM instance."""
    instance_re = re.compile(
        r"\b(Ram_1wrs|Ram_1w_1rs)\s*#\s*\((.*?)\)\s+[A-Za-z_]\w*\s*\(",
        re.DOTALL,
    )
    parameter_re = re.compile(r"\.(\w+)\s*\(\s*([^)]*)\)")
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
        records.append({
            "kind": kind,
            "logical_width": width,
            "mask_width": mask_width,
            "carrier_width": storage_width_for_mask(width, mask_width, mask_enable),
            "word_count": params["wordCount"],
            "wrapper": match.group(1),
        })
    return records


def _automatic_sram_requests(args: argparse.Namespace, rtl: Path) -> list[dict]:
    """Coalesce RAM requirements into the smallest set of downloader requests."""
    requirements = sram_memory_requirements(rtl)
    if not requirements:
        raise ValueError("ICS55 SRAM backend found no supported RAM instances in generated RTL")

    grouped = {}
    minimum_width = args.ics55_sram_bits or 0
    minimum_depth = args.ics55_sram_words or 0
    for requirement in requirements:
        width = requirement["carrier_width"]
        # ICS55 WEB is bit-granular, so a byte-masked logical word can use
        # one wider macro instead of one macro per byte lane.
        if (
            requirement["kind"] == "byte_lane"
            and any(
                min_bits <= requirement["logical_width"] <= max_bits
                for _, _, _, min_bits, max_bits in MUX_RULES.values()
            )
        ):
            width = requirement["logical_width"]
        width = max(width, 8, minimum_width)
        depth = max(requirement["word_count"], minimum_depth)
        grouped[width] = max(grouped.get(width, 0), depth)

    requests = []
    common = ics55_sram_payload(args)
    for width, depth in sorted(grouped.items()):
        mux = _choose_download_mux(width, args.ics55_sram_mux)
        requests.append({
            **common,
            "words": _download_words_at_least(depth, mux),
            "bits": width,
            "mux": mux,
        })
    return requests


def _package_manifest_entry(package_dir: Path) -> tuple[dict, dict[str, str]]:
    """Read package dimensions and return one manifest entry plus corner files."""
    name = package_dir.name
    lef_files = sorted((package_dir / "lef").glob("*.lef"))
    core_files = sorted((package_dir / "verilog").glob("*_core.v"))
    if len(lef_files) != 1 or len(core_files) != 1:
        raise ValueError(f"downloaded SRAM package must contain one LEF and one core Verilog: {package_dir}")
    core = core_files[0]
    info = parse_verilog(core)
    if info["module"] != name:
        raise ValueError(f"downloaded SRAM module {info['module']} does not match package {name}")
    try:
        width = info["ports"]["D"]["width"]
        address_width = info["ports"]["A"]["width"]
        inferred_depth = info["inferred_depth"]
    except KeyError as error:
        raise ValueError(f"downloaded SRAM package is missing expected port {error.args[0]}") from error
    spec = re.search(r"_(\d+)X(\d+)M", name, re.IGNORECASE)
    if not spec:
        raise ValueError(f"cannot determine SRAM dimensions from package name: {name}")
    package_depth, package_width = (int(value) for value in spec.groups())
    if package_width != width:
        raise ValueError(f"downloaded SRAM width {width} disagrees with package name {package_width}: {core}")
    if inferred_depth is not None and inferred_depth != package_depth:
        raise ValueError(f"downloaded SRAM depth {inferred_depth} disagrees with package name {package_depth}: {core}")
    depth = inferred_depth or package_depth
    typical = _find_downloaded_lib(package_dir, "tt1p2v25cctyp")
    slow = _find_downloaded_lib(package_dir, "ss1p08v125ccmax")
    fast = _find_downloaded_lib(package_dir, "ff1p32vm40ccmin")
    entry = {
        "name": name,
        "depth": depth,
        "width": width,
        "address_width": address_width,
        "lef_file": str(lef_files[0].resolve()),
        "lib_file": str(typical.resolve()),
        "verilog_file": str(core.resolve()),
        "wrapper": "sync_single_port",
        "register": True,
        "margin_width": 4,
        "write_mask_granularity": "bit",
        "port_map": {
            "clk": "CLK",
            "address": "A",
            "data": "D",
            "banksel": "CEB",
            "read": "GWEB",
            "write": "WEB",
            "dataout": "Q",
            "margin": "MAR",
            "margin_enable": "MARE",
        },
    }
    corners = {
        "slow": str(slow.resolve()),
        "typical": str(typical.resolve()),
        "fast": str(fast.resolve()),
    }
    return entry, corners


def write_ics55_sram_manifest(package_dirs: list[Path], output: Path) -> Path:
    """Describe all downloaded packages using the flow's manifest schema."""
    entries = []
    corners = {"slow": [], "typical": [], "fast": []}
    for package_dir in package_dirs:
        entry, package_corners = _package_manifest_entry(package_dir)
        if any(existing["name"] == entry["name"] for existing in entries):
            raise ValueError(f"duplicate downloaded SRAM macro: {entry['name']}")
        entries.append(entry)
        for corner, path in package_corners.items():
            corners[corner].append(path)

    if not entries:
        raise ValueError("automatic SRAM manifest has no downloaded packages")
    widths = sorted({entry["width"] for entry in entries})
    manifest = {
        "schema_version": 2,
        "library_name": "mico_ics55_sram_auto",
        "root": str(output.parent.resolve()),
        "lef_dir": ".",
        "lib_dir": ".",
        "verilog_dir": ".",
        "physical_views": False,
        "required_ports": ["A", "CEB", "CLK", "D", "GWEB", "MAR", "MARE", "Q", "WEB"],
        "timing_corners": corners,
        "macros": entries,
        "logical_lane_policy": {
            "requested_width": 8,
            "physical_width_preference": widths,
            "allow_wider_carrier": True,
            "carrier_bits_used": "low_bits_only_within_each_selected_unit",
        },
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n")
    return output


def prepare_ics55_sram_manifest(args: argparse.Namespace, run_root: Path, rtl: Path) -> Path:
    """Download every carrier required by generated Spinal RAM instances."""
    output_dir = (
        args.ics55_sram_output_dir.expanduser().resolve()
        if args.ics55_sram_output_dir is not None
        else ROOT / "asic" / "pdk" / "downloads"
    )
    package_dirs = []
    for payload in _automatic_sram_requests(args, rtl):
        package_dir = download_package(
            payload,
            output_dir=output_dir,
            timeout=args.ics55_sram_timeout,
            force=args.ics55_sram_force_download,
            **({"base_url": args.ics55_sram_base_url} if args.ics55_sram_base_url else {}),
        )
        package_dirs.append(package_dir)
        print(f"Using automatically downloaded ICS55 SRAM package: {package_dir}")
    manifest_path = run_root / "ics55_sram_manifest.json"
    write_ics55_sram_manifest(package_dirs, manifest_path)
    print(f"Generated SRAM manifest: {manifest_path}")
    return manifest_path


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
        macro = (
            select_packed_macro(manifest, width, depth)
            if kind == "byte_lane" else None
        ) or select_macro(manifest, carrier_width, depth)
        names.add(macro["name"])
        records.append({
            "kind": kind,
            "logical_width": width,
            "mask_width": mask_width,
            "carrier_width": carrier_width,
            "word_count": depth,
            "macro": macro["name"],
            "packing": (
                "full_word" if kind == "byte_lane" and macro["width"] >= width
                else "byte_lane" if kind == "byte_lane" else "none"
            ),
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
    if args.sram_backend in ("asap7", "ics55"):
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
    if args.pdk == "asap7":
        asap7_demo(project, language="verilog")
        # ASAP7 synthesis uses RVT cells alongside the target's LVT/SLVT libraries.
        # Keep RVT in the physical library list so stream export includes its GDS.
        project.add("asic", "asiclib", "asap7sc7p5t_rvt")
    else:
        from ics55_pdk import make_ics55_libraries

        _, mainlib, lvtlib, hvtlib = make_ics55_libraries(
            resolve_pdk_root(args.ics55_pdk_root)
        )
        project.set_mainlib(mainlib)
        project.add_asiclib(lvtlib)
        project.add_asiclib(hvtlib)
        project.set_pdk("ics55")

        for scenario_name, libcorner, check in (
            ("slow", "slow", "setup"),
            ("typical", "typical", "power"),
            ("fast", "fast", "hold"),
        ):
            scenario = project.constraint.timing.make_scenario(scenario_name)
            if prepared is None:
                corners = [libcorner]
            else:
                sram_corners = set(prepared.get("timing_corners", {}))
                # Use the matching macro view when the manifest provides it.
                # Older manifests have only generic, so retain the PDK corner
                # plus the generic SRAM fallback in that case.
                corners = [libcorner] if libcorner in sram_corners else [libcorner, "generic"]
            scenario.add_libcorner(corners)
            scenario.set_pexcorner("typical")
            scenario.add_check(check)
        project.set_asic_delaymodel("nldm")
        # Match the native ASAP7/ICS55 demo targets. With no explicit die/core
        # area, SC passes density and coremargin to OpenROAD floorplan init.
        project.constraint.area.set_density(40)
        project.constraint.area.set_coremargin(1.0)
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
        if args.pdk == "ics55":
            # The checked-in preview PDK has no PDN generator or power-grid rules.
            asic_flow.remove_node("floorplan.power_grid")
            # FILLCAP cells cannot fill arbitrary standard-cell row gaps.
            asic_flow.remove_node("cts.fillcell")
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
            if args.pdk == "asap7":
                project.set("library", "asap7", "pdk", "minlayer", "M2")
                project.set("library", "asap7", "pdk", "maxlayer", "M7")
                project.add("tool", "openroad", "task", "global_route", "prescript",
                            str(ROOT / "asic" / "constraints" / "openroad_pin_access_compat.tcl"))
            project.set("tool", "openroad", "task", "global_route", "var",
                        "grt_use_pin_access", True)
    if prepared is not None:
        from asap7sram_library import MiCoASAP7SramLibrary, MiCoSramLibrary

        if args.pdk == "asap7":
            sram_library = MiCoASAP7SramLibrary(
                prepared, FLOW_DIR, active_macro_names
            )
        else:
            from ics55_pdk import ICS55PDK
            sram_library = MiCoSramLibrary(
                prepared,
                FLOW_DIR,
                ICS55PDK(resolve_pdk_root(args.ics55_pdk_root)),
                active_macro_names,
            )
        project.add_asiclib(sram_library)
    if args.step == "asic":
        # SC 0.38.5 requires this key even when no macros are present.
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
                result[key] = float(match.group(1)) / (1000.0 if args.pdk == "asap7" else 1.0)

    power = report_dir / "power" / "typical.rpt"
    if power.is_file():
        for line in power.read_text().splitlines():
            fields = line.split()
            if fields and fields[0] == "Total" and len(fields) >= 5:
                result["power_w"] = float(fields[4])
                break
    return result


def collect_metrics(
    project,
    args: argparse.Namespace,
    build_dir: Path,
    wrapper_metadata: dict | None,
    manifest_path: Path | None,
) -> None:
    metrics = {
        "design": "MiCoSoc",
        "preset": args.preset,
        "step": args.step,
        "pdk": args.pdk,
        "clock_period": args.clock_period,
        "clock_period_units": "ps" if args.pdk == "asap7" else "ns",
        "sram_backend": args.sram_backend,
        "sram_manifest": str(manifest_path) if args.sram_backend == "ics55" else None,
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
    if args.pdk == "asap7" and args.sram_backend == "ics55":
        raise ValueError("--sram-backend ics55 requires --pdk ics55")
    if args.pdk == "ics55" and args.sram_backend == "asap7":
        raise ValueError("--sram-backend asap7 requires --pdk asap7")
    if args.abstract_only and args.sram_backend not in ("asap7", "ics55"):
        raise ValueError("--abstract-only requires an ASIC SRAM backend")
    if args.abstract_detailed_route and not args.abstract_only:
        raise ValueError("--abstract-detailed-route requires --abstract-only")
    if args.sram_backend == "ics55" and args.ics55_sram_timeout <= 0:
        raise ValueError("--ics55-sram-timeout must be greater than zero")

    manifest_path = None
    if args.sram_backend == "asap7":
        manifest_path = FLOW_DIR / (
            "sram_views_abstract.json" if args.abstract_only else "sram_views.json"
        )
    elif args.sram_backend == "ics55":
        if args.ics55_sram_manifest is not None:
            manifest_path = args.ics55_sram_manifest.expanduser().resolve()
            if not manifest_path.is_file():
                raise ValueError(f"ICS55 SRAM manifest not found: {manifest_path}")
        else:
            # The generated RTL determines the required SRAM dimensions.
            manifest_path = None

    staging = run_root / "rtl"
    if args.skip_generate:
        rtl = staging / "MiCoSoc.v"
        if not rtl.is_file():
            raise RuntimeError(f"--skip-generate requested but {rtl} is absent")
    else:
        rtl = generate_rtl(args, staging)

    if args.sram_backend == "ics55" and manifest_path is None:
        manifest_path = prepare_ics55_sram_manifest(args, run_root, rtl)

    prepared = None
    if manifest_path is not None:
        prepared = prepare(
            manifest_path,
            run_root / "prepared_sram_views.json",
            None if args.abstract_only else run_root / "prepared_gds",
        )

    sdc = staging / "micosoc.sdc"
    sdc_template = (
        ROOT / "asic" / "constraints" /
        ("micosoc_ics55.sdc" if args.pdk == "ics55" else "micosoc.sdc")
    )
    sdc_text = sdc_template.read_text()
    sdc.write_text(re.sub(
        r"(-period\s+)[0-9.eE+-]+",
        rf"\g<1>{args.clock_period}",
        sdc_text,
        count=1,
    ))

    wrapper = None
    wrapper_metadata = None
    blackbox_cells = None
    if args.sram_backend in ("asap7", "ics55"):
        wrapper = staging / "spinal_ram_wrappers.v"
        if args.sram_backend == "asap7":
            blackbox_cells = ROOT / "asic" / "rtl" / "asap7_sram_cells.v"
            if args.abstract_only:
                blackbox_cells = staging / "asap7_sram_cells_abstract.v"
        else:
            blackbox_cells = staging / "ics55_sram_cells_abstract.v"
        main_word_count = main_ram_word_count(args.ram_kbytes)
        rf_count = args.bitnet_cfu_reg_depth if args.preset in ("minimal-bncfu", "bncfu", "bncfu-v2") else 1
        rf_width = args.bitnet_cfu_bus_width if args.preset in ("minimal-bncfu", "bncfu", "bncfu-v2") else 8
        command = [
            sys.executable, str(FLOW_DIR / "generate_sram_wrappers.py"),
            "--manifest", str(manifest_path), "--output", str(wrapper),
            "--main-word-count", str(main_word_count), "--main-word-width", "32",
        ]
        command += ["--rf-word-count", str(rf_count), "--rf-word-width", str(rf_width)]
        if args.abstract_only or args.sram_backend == "ics55":
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
    if args.step == "asic" and args.pdk == "ics55":
        if args.to_step in ("write.gds", "write.views"):
            raise ValueError(
                "ICS55 stream-out requires a verified KLayout technology and layer map; "
                "the local PDK does not provide those assets yet"
            )
        if args.to_step is None and not args.abstract_only:
            project.option.add_to("route.global", clobber=True)
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
    collect_metrics(project, args, run_root, wrapper_metadata, manifest_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
