#!/usr/bin/env python3
"""SiliconCompiler registration for prepared SRAM views."""

from __future__ import annotations

import json
from pathlib import Path

from lambdapdk import LambdaLibrary
from lambdapdk.asap7 import ASAP7PDK


class MiCoSramLibrary(LambdaLibrary):
    """Register manifest-driven SRAM views without assuming a specific PDK."""

    def __init__(
        self,
        prepared_manifest: dict,
        flow_dir: Path,
        pdk,
        active_macro_names: set[str] | None = None,
        *,
        global_connect_file: Path | None = None,
        powergrid_file: Path | None = None,
    ):
        super().__init__()
        self.set_name(prepared_manifest["library_name"])
        self.set_dataroot("mico_sram", Path("/").anchor or "/")
        self.add_asic_pdk(pdk, default=False)
        active_macro_names = active_macro_names or {
            entry["name"] for entry in prepared_manifest["macros"]
        }
        physical_entries = [
            entry for entry in prepared_manifest["macros"]
            if entry["name"] in active_macro_names
        ]

        with self.active_fileset("models.physical"):
            for entry in physical_entries:
                self.add_file(entry["lef"], filetype="lef", dataroot="mico_sram")
            if prepared_manifest.get("physical_views", True):
                gds_files = sorted({entry["gds"] for entry in physical_entries})
                for gds in gds_files:
                    self.add_file(gds, filetype="gds", dataroot="mico_sram")
            self.add_asic_aprfileset()

        with self.active_fileset("models.timing.nldm"):
            for entry in prepared_manifest["macros"]:
                self.add_file(entry["lib"], filetype="liberty", dataroot="mico_sram")
            self.add_asic_libcornerfileset("generic", "nldm")

        for corner, files in prepared_manifest.get("timing_corners", {}).items():
            with self.active_fileset(f"models.timing.{corner}.nldm"):
                for path in files:
                    self.add_file(path, filetype="liberty", dataroot="mico_sram")
                self.add_asic_libcornerfileset(corner, "nldm")

        if global_connect_file is not None:
            with self.active_fileset("openroad.globalconnect"):
                self.add_file(global_connect_file, filetype="tcl", dataroot="mico_sram")
                self.add_openroad_globalconnectfileset()

        if powergrid_file is not None:
            with self.active_fileset("openroad.powergrid"):
                self.add_file(powergrid_file, filetype="tcl", dataroot="mico_sram")
                self.add_openroad_powergridfileset()


class MiCoASAP7SramLibrary(MiCoSramLibrary):
    """Backward-compatible ASAP7 SRAM registration."""

    def __init__(
        self,
        prepared_manifest: dict,
        flow_dir: Path,
        active_macro_names: set[str] | None = None,
    ):
        super().__init__(
            prepared_manifest,
            flow_dir,
            ASAP7PDK(),
            active_macro_names,
            global_connect_file=flow_dir / "sram_global_connect.tcl",
            powergrid_file=flow_dir / "sram_pdngen.tcl",
        )


def load_prepared(path: Path) -> dict:
    return json.loads(path.read_text())
