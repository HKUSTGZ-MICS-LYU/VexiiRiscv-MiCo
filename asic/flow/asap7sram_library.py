#!/usr/bin/env python3
"""SiliconCompiler library wrapper for the checked-in ASAP7 SRAM views."""

from __future__ import annotations

import json
from pathlib import Path

from lambdapdk import LambdaLibrary
from lambdapdk.asap7 import ASAP7PDK


class MiCoASAP7SramLibrary(LambdaLibrary):
    """Register local SRAM views without replacing the ASAP7 standard cells."""

    def __init__(
        self,
        prepared_manifest: dict,
        flow_dir: Path,
        active_macro_names: set[str] | None = None,
    ):
        super().__init__()
        self.set_name(prepared_manifest["library_name"])
        self.add_asic_pdk(ASAP7PDK(), default=False)
        active_macro_names = active_macro_names or {
            entry["name"] for entry in prepared_manifest["macros"]
        }
        # Keep every Liberty view available for mapping/timing, but only load
        # instantiated LEFs into OpenROAD pin access and macro placement.
        physical_entries = [
            entry for entry in prepared_manifest["macros"]
            if entry["name"] in active_macro_names
        ]

        with self.active_fileset("models.physical"):
            for entry in physical_entries:
                self.add_file(entry["lef"], filetype="lef")
            if prepared_manifest.get("physical_views", True):
                gds_files = sorted({entry["gds"] for entry in physical_entries})
                for gds in gds_files:
                    self.add_file(gds, filetype="gds")
            self.add_asic_aprfileset()

            with self.active_fileset("models.timing.nldm"):
                for entry in prepared_manifest["macros"]:
                    self.add_file(entry["lib"], filetype="liberty")
                self.add_asic_libcornerfileset("generic", "nldm")

        with self.active_fileset("openroad.globalconnect"):
            self.add_file(flow_dir / "sram_global_connect.tcl", filetype="tcl")
            self.add_openroad_globalconnectfileset()

        with self.active_fileset("openroad.powergrid"):
            self.add_file(flow_dir / "sram_pdngen.tcl", filetype="tcl")
            self.add_openroad_powergridfileset()


def load_prepared(path: Path) -> dict:
    return json.loads(path.read_text())
