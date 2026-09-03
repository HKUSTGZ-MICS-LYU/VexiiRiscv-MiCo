#!/usr/bin/env python3
"""SiliconCompiler PDK and standard-cell definitions for ICsprout55."""

from __future__ import annotations

import re
from pathlib import Path

from ics55_assets import (
    PROVISIONAL_CAP_PF_PER_UM,
    Ics55Assets,
    provisional_routing_resistance,
    validate_assets,
)
from lambdapdk import LambdaLibrary, LambdaPDK


FLOW_DIR = Path(__file__).resolve().parent


def _openroad_tech_lef(assets: Ics55Assets) -> Path:
    """Create an OpenROAD-compatible tech LEF without unused tap NDRs."""
    cache = Path("/tmp") / "mico-ics55-tech"
    cache.mkdir(parents=True, exist_ok=True)
    output = cache / "N551P6M.lef"
    text = assets.tech_lef.read_text(encoding="utf-8", errors="replace")
    text = text.replace("USEVIARULE MET1_POLY ;", "")
    # The preview LEF's DefaultTaper NDR triggers an OpenROAD detailed-router
    # assertion while parsing its POLY/viarule combination. It is unused by
    # this MET2-MET5 standard-cell flow, so omit it from the private copy.
    text = re.sub(
        r"\nNONDEFAULTRULE\s+DefaultTaper\b.*?\nEND\s+DefaultTaper\s*\n",
        "\n",
        text,
        flags=re.DOTALL,
    )
    output.write_text(text)
    return output


class ICS55PDK(LambdaPDK):
    """Local ICS55 PDK adapter for SiliconCompiler/OpenROAD bring-up."""

    def __init__(self, root: Path | str):
        assets: Ics55Assets = validate_assets(root)
        super().__init__()
        self.set_name("ics55")
        self.set_dataroot("ics55", str(assets.root))
        tech_lef = _openroad_tech_lef(assets)
        self.set_dataroot("ics55_tech", str(tech_lef.parent))
        self.set_dataroot("mico_flow", str(FLOW_DIR))
        self.set_foundry("virtual")
        self.package.set_version("1.10.100-preview")
        self.set_node(55)
        self.set_stackup("5M1TM")
        self.set_aprroutinglayers(min="MET2", max="MET5")
        self.set_openroad_rclayers(signal="MET3", clock="MET4")
        self.add_openroad_pinlayers(horizontal="MET3", vertical="MET4", clobber=True)
        for layer in ("MET1", "MET2", "MET3", "MET4", "MET5"):
            self.set_openroad_globalroutingderating(layer, 0.25)
        for layer in ("T4M2", "RDL"):
            self.set_openroad_globalroutingderating(layer, 0.0)

        with self.active_dataroot("ics55_tech"):
            with self.active_fileset("views.lef"):
                self.add_file(Path("N551P6M.lef"), filetype="lef", dataroot="ics55_tech")
                self.add_aprtechfileset("openroad")

            # The release has no extraction deck. These estimates make OpenROAD
            # timing repair and global-route reports operational, but are not
            # suitable for signoff.
            resistance = provisional_routing_resistance(assets)
            for layer, value in resistance.items():
                self.add_openroad_rclayer(
                    "typical",
                    "routing",
                    layer,
                    value,
                    PROVISIONAL_CAP_PF_PER_UM[layer] * 1.0e-12,
                )
            for via in ("VIA1", "VIA2", "VIA3", "VIA4"):
                self.add_openroad_rclayer("typical", "via", via, 2.5)


class ICS55StdCellLibrary(LambdaLibrary):
    """One H7C threshold-voltage library variant."""

    def __init__(
        self,
        root: Path | str,
        variant_name: str,
        pdk: ICS55PDK | None = None,
        *,
        default_pdk: bool = False,
    ):
        assets = validate_assets(root)
        variant = assets.variant(variant_name)
        super().__init__()
        self.set_name(f"ics55_h7c_{variant.role}")
        self.package.set_version("1.10.100")
        self.set_dataroot("ics55", str(assets.root))
        self.set_dataroot("mico_flow", str(FLOW_DIR))
        if pdk is None:
            pdk = ICS55PDK(assets.root)
        self.add_asic_pdk(pdk, default=default_pdk)
        self.add_asic_site("core7")

        suffix = variant.suffix
        tie_high = f"TIEHIH7{suffix}"
        tie_low = f"TIELOH7{suffix}"
        filler = [f"FILLCAP{width}H7{suffix}" for width in (4, 8, 16, 32)]
        buffer = f"BUFX2H7{suffix}"
        self.add_asic_celllist("tie", [tie_high, tie_low])
        self.add_asic_celllist("filler", filler)

        with self.active_dataroot("ics55"):
            base = Path("IP", "STD_cell", "ics55_LLSC_H7C_V1p10C100", f"ics55_LLSC_{variant.name}")
            with self.active_fileset("models.physical"):
                self.add_file(base / "lef" / variant.lef.name, filetype="lef")
                self.add_asic_aprfileset()

            with self.active_fileset("models.gds"):
                self.add_file(base / "gds" / variant.gds.name, filetype="gds")
                self.add_asic_aprfileset()

            with self.active_fileset("models.verilog"):
                self.add_file(base / "verilog" / variant.verilog.name, filetype="verilog")

            for corner in ("typical", "slow", "fast"):
                with self.active_fileset(f"models.timing.{corner}.nldm"):
                    self.add_file(base / "liberty" / variant.liberty(corner).name, filetype="liberty")
                    self.add_asic_libcornerfileset(corner, "nldm")

        with self.active_dataroot("mico_flow"):
            with self.active_fileset("openroad.globalconnect"):
                self.add_file(Path("ics55_global_connect.tcl"), filetype="tcl")
                self.add_openroad_globalconnectfileset()

        self.set_yosys_driver_cell(buffer)
        self.set_yosys_buffer_cell(buffer, "A", "Y")
        self.set_yosys_tiehigh_cell(tie_high, "Z")
        self.set_yosys_tielow_cell(tie_low, "Z")
        # Liberty time_unit is 1ns. The multiplier and load follow the upstream
        # lambdapdk ICS55 target; the load remains a synthesis estimate.
        abc_load_pf = {"H": 0.00105062, "R": 0.000884993, "L": 0.000922872}[suffix]
        self.set_yosys_abc(1000, abc_load_pf * 1000 * 4)
        self.set_openroad_tiehigh_cell(tie_high, "Z")
        self.set_openroad_tielow_cell(tie_low, "Z")
        self.set_openroad_placement_density(0.60)


def make_ics55_libraries(root: Path | str):
    """Return PDK, main RVT library, and LVT/HVT optimization libraries."""
    assets = validate_assets(root)
    pdk = ICS55PDK(assets.root)
    main = ICS55StdCellLibrary(assets.root, "H7CR", pdk, default_pdk=True)
    lvt = ICS55StdCellLibrary(assets.root, "H7CL", pdk)
    hvt = ICS55StdCellLibrary(assets.root, "H7CH", pdk)
    return pdk, main, lvt, hvt
