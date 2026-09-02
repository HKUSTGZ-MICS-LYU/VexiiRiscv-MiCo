#!/usr/bin/env python3
"""Asset discovery and validation for the local ICsprout55 PDK."""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import re


DEFAULT_PDK_RELATIVE = Path("asic", "pdk", "icsprout55-pdk")
PDK_ROOT_ENV = "ICSPROUT55_PDK_ROOT"
TECH_LEF_NAME = "N551P6M.lef"
PROVISIONAL_CAP_PF_PER_UM = {
    "MET1": 7.41819e-5,
    "MET2": 6.74606e-5,
    "MET3": 8.88758e-5,
    "MET4": 1.07121e-4,
    "MET5": 1.08964e-4,
}

VARIANTS = {
    "H7CH": {"suffix": "H", "role": "hvt"},
    "H7CL": {"suffix": "L", "role": "lvt"},
    "H7CR": {"suffix": "R", "role": "rvt"},
}

CORNER_FILES = {
    "fast": "{variant}_ff_rcbest_1p32_m40_nldm.lib",
    "slow": "{variant}_ss_rcworst_1p08_125_nldm.lib",
    "typical": "{variant}_typ_tt_1p2_25_nldm.lib",
}
ALL_CORNER_FILES = (
    "{variant}_ff_cbest_1p32_125_nldm.lib",
    "{variant}_ff_rcbest_1p08_125_nldm.lib",
    "{variant}_ff_rcbest_1p32_m40_nldm.lib",
    "{variant}_ss_cworst_1p08_m40_nldm.lib",
    "{variant}_ss_rcworst_1p08_125_nldm.lib",
    "{variant}_ss_rcworst_1p2_m40_nldm.lib",
    "{variant}_typ_tt_1p2_25_nldm.lib",
)

# The release has no extraction deck. These are pre-route estimates from the
# installed lambdapdk integration and are never signoff models.


@dataclass(frozen=True)
class Ics55Variant:
    name: str
    directory: Path
    suffix: str
    role: str

    @property
    def lef(self) -> Path:
        return self.directory / "lef" / f"ics55_LLSC_{self.name}_ecos.lef"

    @property
    def verilog(self) -> Path:
        return self.directory / "verilog" / f"ics55_LLSC_{self.name}.v"

    @property
    def gds(self) -> Path:
        return self.directory / "gds" / f"ics55_LLSC_{self.name}_M2.gds"

    @property
    def cell_list(self) -> Path:
        return self.directory / "cell_list" / f"ics55_LLSC_{self.name}.txt"

    @property
    def liberty_dir(self) -> Path:
        return self.directory / "liberty"

    def liberty(self, corner: str) -> Path:
        try:
            filename = CORNER_FILES[corner].format(variant=f"ics55_LLSC_{self.name}")
        except KeyError as error:
            raise ValueError(f"unsupported ICS55 corner: {corner}") from error
        return self.liberty_dir / filename

    def all_liberty(self) -> tuple[Path, ...]:
        prefix = f"ics55_LLSC_{self.name}"
        return tuple(self.liberty_dir / item.format(variant=prefix) for item in ALL_CORNER_FILES)


@dataclass(frozen=True)
class Ics55Assets:
    root: Path
    tech_lef: Path
    variants: tuple[Ics55Variant, ...]

    def variant(self, name: str = "H7CR") -> Ics55Variant:
        normalized = name.upper()
        for variant in self.variants:
            if variant.name == normalized:
                return variant
        raise ValueError(f"unsupported ICS55 standard-cell variant: {name}")


def resolve_pdk_root(cli_root: Path | str | None = None, *, repo_root: Path | None = None) -> Path:
    if cli_root is not None:
        return Path(cli_root).expanduser().resolve()
    env_root = os.environ.get(PDK_ROOT_ENV)
    if env_root:
        return Path(env_root).expanduser().resolve()
    if repo_root is None:
        repo_root = Path(__file__).resolve().parents[2]
    return (repo_root / DEFAULT_PDK_RELATIVE).resolve()


def discover_assets(root: Path | str) -> Ics55Assets:
    root = Path(root).expanduser().resolve()
    base = root / "IP" / "STD_cell" / "ics55_LLSC_H7C_V1p10C100"
    variants = tuple(
        Ics55Variant(name, base / f"ics55_LLSC_{name}", info["suffix"], info["role"])
        for name, info in VARIANTS.items()
    )
    return Ics55Assets(root, root / "prtech" / "techLEF" / TECH_LEF_NAME, variants)


def _require_files(paths: list[Path]) -> None:
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        raise ValueError("missing ICS55 PDK files:\n  " + "\n  ".join(missing))


def _require_match(path: Path, pattern: str, description: str) -> None:
    text = path.read_text(encoding="utf-8", errors="replace")
    if not re.search(pattern, text, re.MULTILINE):
        raise ValueError(f"{path}: missing {description}")


def validate_assets(root: Path | str, *, require_gds: bool = True) -> Ics55Assets:
    assets = discover_assets(root)
    required = [assets.tech_lef]
    for variant in assets.variants:
        required.extend((variant.lef, variant.verilog, variant.cell_list))
        required.extend(variant.all_liberty())
        if require_gds:
            required.append(variant.gds)
    _require_files(required)

    _require_match(assets.tech_lef, r"DATABASE\s+MICRONS\s+1000\s*;", "DATABASE MICRONS 1000")
    for layer in range(1, 6):
        _require_match(assets.tech_lef, rf"^LAYER\s+MET{layer}\s*$", f"MET{layer} routing layer")
    _require_match(assets.tech_lef, r"^SITE\s+core7\s*$", "core7 site")
    _require_match(assets.tech_lef, r"SIZE\s+0\.200\s+BY\s+1\.400", "core7 0.2 x 1.4um site")

    for variant in assets.variants:
        _require_match(variant.lef, rf"^MACRO\s+BUFX2H7{variant.suffix}\s*$", "representative LEF cell")
        _require_match(variant.lef, r"^\s+SITE\s+core7\s*;", "core7 cell site")
        _require_match(variant.verilog, rf"\bmodule\s+BUFX2H7{variant.suffix}\b", "representative Verilog cell")
        _require_match(variant.liberty("typical"), rf"^library\s*\(ics55_LLSC_{variant.name}_typ_tt_1p2_25\)", "matching Liberty library")
        _require_match(variant.liberty("typical"), r'time_unit\s*:\s*"1ns"', "Liberty 1ns time unit")
        cells = variant.cell_list.read_text(encoding="utf-8", errors="replace").split()
        required_cells = {
            f"BUFX2H7{variant.suffix}",
            f"TIEHIH7{variant.suffix}",
            f"TIELOH7{variant.suffix}",
            f"FILLCAP4H7{variant.suffix}",
            f"FILLCAP8H7{variant.suffix}",
            f"FILLCAP16H7{variant.suffix}",
            f"FILLCAP32H7{variant.suffix}",
        }
        missing = sorted(required_cells - set(cells))
        if missing:
            raise ValueError(f"{variant.name}: missing required support cells {missing}")

    return assets


def provisional_routing_resistance(assets: Ics55Assets) -> dict[str, float]:
    """Return ohm/um estimates from the tech LEF sheet resistance and width."""
    text = assets.tech_lef.read_text(encoding="utf-8", errors="replace")
    values: dict[str, float] = {}
    for layer in range(1, 6):
        match = re.search(
            rf"LAYER\s+MET{layer}\b(.*?)(?:END\s+MET{layer})",
            text,
            re.DOTALL,
        )
        if not match:
            raise ValueError(f"{assets.tech_lef}: cannot parse MET{layer} geometry")
        block = match.group(1)
        resistance = re.search(r"RESISTANCE\s+RPERSQ\s+([0-9.eE+-]+)", block)
        width = re.search(r"WIDTH\s+([0-9.eE+-]+)", block)
        if not resistance or not width:
            raise ValueError(f"{assets.tech_lef}: incomplete MET{layer} RC data")
        values[f"MET{layer}"] = float(resistance.group(1)) / float(width.group(1))
    return values
