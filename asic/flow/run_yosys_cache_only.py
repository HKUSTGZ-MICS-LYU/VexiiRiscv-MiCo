#!/usr/bin/env python3
"""Generate cache-only MiCoSoc RTL and synthesize it with standalone Yosys."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FLOW_DIR = Path(__file__).resolve().parent
MACRO_TYPES = {
    "srambank_64x4x64_6t122",
    "srambank_128x4x64_6t122",
    "srambank_256x4x64_6t122",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--workdir", type=Path,
        default=ROOT / "asic" / "build" / "yosys-cache-nocfu",
        help="standalone input/output directory",
    )
    parser.add_argument("--ram-kbytes", type=int, default=8)
    parser.add_argument("--sbt-env", default="mico_env")
    parser.add_argument("--no-btb", action="store_true")
    parser.add_argument("--skip-generate", action="store_true")
    parser.add_argument(
        "--yosys-script", type=Path,
        default=FLOW_DIR / "yosys_cache_only.ys",
    )
    return parser.parse_args()


def run(command: list[str], cwd: Path) -> None:
    print("+", " ".join(shlex.quote(item) for item in command))
    subprocess.run(command, cwd=cwd, check=True)


def generate_inputs(args: argparse.Namespace, workdir: Path) -> None:
    staging = workdir / "rtl"
    inputs = workdir / "inputs"
    staging.mkdir(parents=True, exist_ok=True)
    inputs.mkdir(parents=True, exist_ok=True)

    generator_args = [
        "--with-rvc", "--with-rvm", "--with-late-alu",
        "--allow-bypass-from", "0", "--div-radix", "4",
        "--lsu-l1", "--fetch-l1", "--ram-kbytes", str(args.ram_kbytes),
        "--asic-sram", "--netlist-directory", str(staging),
        "--netlist-name", "MiCoSoc",
    ]
    if not args.no_btb:
        generator_args += ["--with-btb", "--btb-single-port-ram"]
    sbt_command = "runMain vexiiriscv.soc.mico.MiCoSocGen " + " ".join(
        shlex.quote(item) for item in generator_args
    )
    run(["conda", "run", "-n", args.sbt_env, "sbt", sbt_command], ROOT)

    main_word_count = args.ram_kbytes * 1024 // 4
    run([
        sys.executable, str(FLOW_DIR / "generate_sram_wrappers.py"),
        "--output", str(inputs / "spinal_ram_wrappers.v"),
        "--main-word-count", str(main_word_count),
        "--main-word-width", "32",
        "--rf-word-count", "1", "--rf-word-width", "8",
    ], ROOT)
    shutil.copy2(staging / "MiCoSoc.v", inputs / "MiCoSoc.v")
    shutil.copy2(ROOT / "asic" / "rtl" / "asap7_sram_cells.v", inputs / "asap7_sram_cells.v")


def top_module(netlist: dict) -> dict:
    modules = netlist["modules"]
    if "MiCoSoc" in modules:
        return modules["MiCoSoc"]
    raise KeyError("MiCoSoc module is missing from Yosys JSON")


def macro_group_name(cell_name: str) -> str:
    if "system_cpu_logic_core." in cell_name:
        return cell_name.split("system_cpu_logic_core.", 1)[1].split(".gen_lane", 1)[0]
    if "system_ram_thread_logic.mem" in cell_name:
        return "system_ram_thread_logic.mem"
    return cell_name.split(".gen_lane", 1)[0]


def register_type(cell_type: str) -> bool:
    return (
        cell_type.startswith(("$dff", "$adff", "$sdff", "$dffe", "$adffe"))
        or cell_type.startswith("$_DFF")
        or cell_type.startswith("$_SDFF")
    )


def wrapper_instances(rtl: str) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for wrapper in ("Ram_1wrs", "Ram_1w_1rs"):
        pattern = re.compile(
            rf"\b{wrapper}\s*#\s*\(.*?\)\s*([A-Za-z_][A-Za-z0-9_$]*)\s*\(",
            re.DOTALL,
        )
        result[wrapper] = sorted(match.group(1) for match in pattern.finditer(rtl))
    return result


def make_report(workdir: Path, args: argparse.Namespace) -> dict:
    outputs = workdir / "outputs"
    reports = workdir / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    pre_map = json.loads((outputs / "pre_memory.json").read_text())
    post_synth = json.loads((outputs / "post_synth.json").read_text())
    pre_top = top_module(pre_map)
    post_top = top_module(post_synth)

    macro_cells = []
    register_cells = []
    for name, cell in sorted(post_top.get("cells", {}).items()):
        cell_type = cell.get("type", "")
        if cell_type in MACRO_TYPES:
            macro_cells.append({"name": name, "type": cell_type})
        if register_type(cell_type):
            register_cells.append({"name": name, "type": cell_type})

    remaining_memories = []
    for name, memory in sorted(pre_top.get("memories", {}).items()):
        remaining_memories.append({
            "name": name,
            "width": memory.get("width"),
            "size": memory.get("size"),
            "kind": "soft_memory_before_memory_map",
        })

    final_memories = sorted(post_top.get("memories", {}).keys())
    macro_groups: dict[str, dict[str, int]] = {}
    for macro in macro_cells:
        group = macro_group_name(macro["name"])
        macro_groups.setdefault(group, {})
        macro_groups[group][macro["type"]] = macro_groups[group].get(macro["type"], 0) + 1
    rtl = (workdir / "inputs" / "MiCoSoc.v").read_text()
    report = {
        "design": "MiCoSoc",
        "preset": "cache-no-cfu",
        "sram_backend": "asap7-blackbox",
        "ram_kbytes": args.ram_kbytes,
        "with_btb": not args.no_btb,
        "cfu_enabled": False,
        "yosys_script": str(args.yosys_script),
        "hard_wrapper_instances": wrapper_instances(rtl),
        "sram_macro_cells": macro_cells,
        "sram_macro_count": len(macro_cells),
        "sram_macro_groups": macro_groups,
        "remaining_memories_before_memory_map": remaining_memories,
        "remaining_memory_count_before_memory_map": len(remaining_memories),
        "remaining_memories_after_synthesis": final_memories,
        "remaining_memory_count_after_synthesis": len(final_memories),
        "register_count_after_synthesis": len(register_cells),
        "register_cells_by_type": {
            cell_type: sum(1 for cell in register_cells if cell["type"] == cell_type)
            for cell_type in sorted({cell["type"] for cell in register_cells})
        },
    }
    output = reports / "cache_memory_report.json"
    output.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> int:
    args = parse_args()
    if args.ram_kbytes <= 0:
        raise ValueError("ram_kbytes must be positive")
    workdir = args.workdir.resolve()
    workdir.mkdir(parents=True, exist_ok=True)
    if not args.skip_generate:
        generate_inputs(args, workdir)
    required = [
        workdir / "inputs" / "MiCoSoc.v",
        workdir / "inputs" / "spinal_ram_wrappers.v",
        workdir / "inputs" / "asap7_sram_cells.v",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError("missing Yosys inputs: " + ", ".join(missing))
    (workdir / "outputs").mkdir(exist_ok=True)
    (workdir / "reports").mkdir(exist_ok=True)
    run(["yosys", "-s", str(args.yosys_script.resolve())], workdir)
    report = make_report(workdir, args)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
