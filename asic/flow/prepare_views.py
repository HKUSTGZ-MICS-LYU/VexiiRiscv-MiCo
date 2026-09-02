#!/usr/bin/env python3
"""Validate the checked-in ASAP7 SRAM views before SiliconCompiler runs."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


MODULE_RE = re.compile(r"\bmodule\s+(\w+)\s*\(")
PORT_RE = re.compile(
    r"\b(input|output|inout)\s+(?:(?:wire|reg)\s+)?"
    r"(?:\[(\d+)\s*:\s*(\d+)\]\s+)?(\w+)"
)
MEM_RE = re.compile(r"\bmem\s*\[\s*(\d+)\s*:\s*(\d+)\s*\]")
LEF_MACRO_RE = re.compile(r"^MACRO\s+(\S+)", re.MULTILINE)
LEF_SIZE_RE = re.compile(r"^\s*SIZE\s+([0-9.eE+-]+)\s+BY\s+([0-9.eE+-]+)", re.MULTILINE)
LEF_SITE_RE = re.compile(r"^\s*SITE\s+(\S+)\s*;", re.MULTILINE)
LEF_PIN_RE = re.compile(r"^\s*PIN\s+(\S+)", re.MULTILINE)
LIB_CELL_RE = re.compile(r"\bcell\s*\(\s*([^\)]+)\s*\)")
LIB_AREA_RE = re.compile(r"\barea\s*:\s*([0-9.eE+-]+)")
GDS_NAME_RE = re.compile(rb"(?:srambank|dummy|FILLER_BLANK)_[A-Za-z0-9_]+")

REQUIRED_PORTS = {"clk", "ADDRESS", "wd", "banksel", "read", "write", "dataout"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("sram_views.json"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--gds-output", type=Path)
    parser.add_argument("--check-only", action="store_true")
    return parser.parse_args()


def parse_verilog(path: Path) -> dict:
    text = path.read_text()
    module = MODULE_RE.search(text)
    if not module:
        raise ValueError(f"{path}: no Verilog module declaration")
    header = text[module.end() : text.find(");", module.end())]
    ports = {}
    for match in PORT_RE.finditer(header):
        direction, msb, lsb, name = match.groups()
        width = abs(int(msb) - int(lsb)) + 1 if msb is not None else 1
        ports[name] = {"direction": direction, "width": width}
    memory = MEM_RE.search(text)
    inferred_depth = None
    if memory:
        inferred_depth = max(int(memory.group(1)), int(memory.group(2))) + 1
    return {"module": module.group(1), "ports": ports, "inferred_depth": inferred_depth}


def parse_lef(path: Path) -> dict:
    text = path.read_text()
    macro = LEF_MACRO_RE.search(text)
    size = LEF_SIZE_RE.search(text)
    site = LEF_SITE_RE.search(text)
    pins = {match.group(1).split("[")[0] for match in LEF_PIN_RE.finditer(text)}
    if not macro or not size or not site:
        raise ValueError(f"{path}: missing LEF MACRO or SIZE")
    return {
        "macro": macro.group(1),
        "size": [float(size.group(1)), float(size.group(2))],
        "site": site.group(1),
        "pins": sorted(pins),
    }


def parse_lib(path: Path) -> dict:
    text = path.read_text()
    cells = [match.group(1).strip() for match in LIB_CELL_RE.finditer(text)]
    area = LIB_AREA_RE.search(text)
    return {"cells": cells, "area": float(area.group(1)) if area else None}


def gds_names(path: Path) -> set[str]:
    return {name.decode("ascii") for name in GDS_NAME_RE.findall(path.read_bytes())}


def inspect_gds(path: Path, cell: str) -> tuple[float, float]:
    info_script = Path(__file__).with_name("gds_info.rb")
    result = subprocess.run(
        ["klayout", "-b", "-r", str(info_script), "-rd", f"input={path}"],
        check=True,
        capture_output=True,
        text=True,
    )
    dbu_match = re.search(r"^DBU\s+([0-9.eE+-]+)$", result.stdout, re.MULTILINE)
    cell_match = re.search(rf"^{re.escape(cell)}\s+(\d+)\s+(\d+)\s+", result.stdout, re.MULTILINE)
    if not dbu_match or not cell_match:
        raise ValueError(f"{path}: KLayout did not report GDS cell {cell}")
    dbu = float(dbu_match.group(1))
    return int(cell_match.group(1)) * dbu, int(cell_match.group(2)) * dbu


def normalize_liberty(source: Path, output: Path, address_width: int) -> None:
    text = source.read_text()
    address_type = re.search(
        r"(type\s*\([^)]*address[^)]*\)\s*\{)(.*?)(\n\s*\})",
        text,
        re.DOTALL,
    )
    if not address_type:
        raise ValueError(f"{source}: no Liberty address bus type")
    body = address_type.group(2)
    body, width_count = re.subn(
        r"(bit_width\s*:\s*)\d+",
        rf"\g<1>{address_width}",
        body,
        count=1,
    )
    body, from_count = re.subn(
        r"(bit_from\s*:\s*)\d+",
        rf"\g<1>{address_width - 1}",
        body,
        count=1,
    )
    if width_count != 1 or from_count != 1:
        raise ValueError(f"{source}: incomplete address bus type")
    normalized = text[:address_type.start(2)] + body + text[address_type.end(2):]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(normalized)


def prepare(manifest_path: Path, output: Path, gds_output: Path | None = None) -> dict:
    manifest = validate(manifest_path)
    if not manifest.get("physical_views", True):
        lib_output = output.parent / "prepared_lib"
        lef_output = output.parent / "prepared_lef"
        lib_output.mkdir(parents=True, exist_ok=True)
        lef_output.mkdir(parents=True, exist_ok=True)
        target_site = manifest.get("abstract_lef_site")
        for entry in manifest["macros"]:
            source_lef = Path(entry["lef"])
            prepared_lef = lef_output / source_lef.name
            lef_text = source_lef.read_text()
            if target_site:
                lef_text = re.sub(
                    r"(\bSITE\s+)\S+(\s*;)",
                    rf"\g<1>{target_site}\g<2>",
                    lef_text,
                    count=1,
                )
            prepared_lef.write_text(lef_text)
            entry["lef"] = str(prepared_lef)
            normalized_lib = lib_output / f"{entry['name']}.lib"
            normalize_liberty(Path(entry["lib"]), normalized_lib, entry["address_width"])
            entry["lib"] = str(normalized_lib)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(manifest, indent=2) + "\n")
        return manifest
    if gds_output is None:
        raise ValueError("physical SRAM preparation requires a GDS output directory")
    gds_output.mkdir(parents=True, exist_ok=True)
    lib_output = output.parent / "prepared_lib"
    lib_output.mkdir(parents=True, exist_ok=True)
    alias_script = Path(__file__).with_name("klayout_alias.rb")
    for entry in manifest["macros"]:
        name = entry["name"]
        source_name = entry["gds_source_cell"]
        source = entry["gds"]
        target = gds_output / f"{name}.gds"
        subprocess.run(
            [
                "klayout", "-b", "-r", str(alias_script),
                "-rd", f"input={source}", "-rd", f"output={target}",
                "-rd", f"source={source_name}", "-rd", f"target={name}",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        width, height = inspect_gds(target, name)
        expected_width, expected_height = entry["lef_size"]
        width_delta = abs(width - expected_width)
        height_delta = abs(height - expected_height)
        if width_delta > 0.6 or height_delta > 0.6:
            raise ValueError(
                f"{name}: GDS size {width:.4f}x{height:.4f} is too far from "
                f"LEF {expected_width:.4f}x{expected_height:.4f}"
            )
        if width_delta > 0.1 or height_delta > 0.1:
            print(
                f"warning: {name}: GDS/LEF size delta is "
                f"{width_delta:.4f}x{height_delta:.4f}um",
                file=sys.stderr,
            )
        entry["gds"] = str(target)
        entry["gds_size"] = [width, height]
        normalized_lib = lib_output / f"{name}.lib"
        normalize_liberty(Path(entry["lib"]), normalized_lib, entry["address_width"])
        entry["lib"] = str(normalized_lib)
    output.write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def validate(manifest_path: Path) -> dict:
    manifest = json.loads(manifest_path.read_text())
    root = (manifest_path.parent / manifest["root"]).resolve()
    physical_views = manifest.get("physical_views", True)
    gds_names_by_source = {}
    if physical_views:
        gds_dir = root / manifest["gds_dir"]
        for source in manifest["gds_sources"].values():
            source_path = gds_dir / source
            if not source_path.is_file():
                raise ValueError(f"missing GDS source: {source_path}")
            gds_names_by_source[source] = gds_names(source_path)

    checked = []
    for entry in manifest["macros"]:
        name = entry["name"]
        lef = root / manifest["lef_dir"] / f"{name}.lef"
        lib = root / manifest["lib_dir"] / f"{name}.lib"
        verilog = root / manifest["verilog_dir"] / f"{name}.v"
        for path in (lef, lib, verilog):
            if not path.is_file():
                raise ValueError(f"{name}: missing view {path}")

        verilog_info = parse_verilog(verilog)
        lef_info = parse_lef(lef)
        lib_info = parse_lib(lib)
        if verilog_info["module"] != name:
            raise ValueError(f"{name}: Verilog module is {verilog_info['module']}")
        if lef_info["macro"] != name:
            raise ValueError(f"{name}: LEF macro is {lef_info['macro']}")
        expected_sites = set(manifest.get("lef_sites", [manifest.get("lef_site")]))
        if lef_info["site"] not in expected_sites:
            raise ValueError(f"{name}: LEF site is {lef_info['site']}, expected one of {sorted(expected_sites)}")
        if name not in lib_info["cells"]:
            raise ValueError(f"{name}: Liberty cell is missing")
        missing = REQUIRED_PORTS - set(verilog_info["ports"])
        if missing:
            raise ValueError(f"{name}: missing Verilog ports {sorted(missing)}")
        missing = REQUIRED_PORTS - set(lef_info["pins"])
        if missing:
            raise ValueError(f"{name}: missing LEF pins {sorted(missing)}")
        if verilog_info["ports"]["wd"]["width"] != entry["width"]:
            raise ValueError(f"{name}: wd width does not match manifest")
        if verilog_info["ports"]["dataout"]["width"] != entry["width"]:
            raise ValueError(f"{name}: dataout width does not match manifest")
        if verilog_info["ports"]["ADDRESS"]["width"] != entry["address_width"]:
            raise ValueError(f"{name}: address width does not match manifest")
        if verilog_info["inferred_depth"] is not None and verilog_info["inferred_depth"] != entry["depth"]:
            raise ValueError(f"{name}: inferred depth does not match manifest")

        checked_entry = {
            **entry,
            "lef": str(lef),
            "lib": str(lib),
            "verilog": str(verilog),
            "lef_size": lef_info["size"],
            "lib_area": lib_info["area"],
        }
        if physical_views:
            gds_source = manifest["gds_sources"][name]
            if entry["gds_cell"] not in gds_names_by_source[gds_source]:
                raise ValueError(f"{name}: GDS cell {entry['gds_cell']} is absent from {gds_source}")
            checked_entry.update({
                "gds": str(gds_dir / gds_source),
                "gds_source_cell": entry["gds_cell"],
            })
        checked.append(checked_entry)
    return {
        "schema_version": manifest["schema_version"],
        "library_name": manifest["library_name"],
        "physical_views": physical_views,
        "abstract_lef_site": manifest.get("abstract_lef_site"),
        "macros": checked,
    }


def main() -> int:
    args = parse_args()
    output = args.output or args.manifest.with_name("prepared_sram_views.json")
    if not args.check_only:
        gds_output = args.gds_output or output.parent / "prepared_gds"
        result = prepare(args.manifest.resolve(), output.resolve(), gds_output.resolve())
        print(f"validated and prepared {len(result['macros'])} SRAM views -> {output}")
    else:
        result = validate(args.manifest.resolve())
        print(f"validated {len(result['macros'])} SRAM views")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
