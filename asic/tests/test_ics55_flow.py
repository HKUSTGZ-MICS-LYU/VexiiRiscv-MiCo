#!/usr/bin/env python3
import os
import types
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
import sys

ROOT = Path(__file__).resolve().parents[2]
FLOW = ROOT / "asic" / "flow"
sys.path.insert(0, str(FLOW))

from ics55_assets import discover_assets, resolve_pdk_root, validate_assets
from mico_flow import (
    _automatic_sram_requests,
    generator_args,
    ics55_sram_payload,
    parse_args,
)


class Ics55FlowTest(unittest.TestCase):
    def test_local_assets_use_ecos_lef_and_m2_gds(self):
        assets = validate_assets(ROOT / "asic" / "pdk" / "icsprout55-pdk")
        self.assertEqual(assets.tech_lef.name, "N551P6M.lef")
        self.assertEqual(
            {variant.lef.name for variant in assets.variants},
            {
                "ics55_LLSC_H7CH_ecos.lef",
                "ics55_LLSC_H7CL_ecos.lef",
                "ics55_LLSC_H7CR_ecos.lef",
            },
        )
        self.assertTrue(all(variant.gds.name.endswith("_M2.gds") for variant in assets.variants))

    def test_root_precedence_is_cli_then_environment_then_repo(self):
        repo_root = Path("/tmp/mico-repo")
        old = os.environ.get("ICSPROUT55_PDK_ROOT")
        try:
            os.environ["ICSPROUT55_PDK_ROOT"] = "/tmp/from-env"
            self.assertEqual(resolve_pdk_root(repo_root=repo_root), Path("/tmp/from-env"))
            self.assertEqual(
                resolve_pdk_root("/tmp/from-cli", repo_root=repo_root),
                Path("/tmp/from-cli"),
            )
        finally:
            if old is None:
                os.environ.pop("ICSPROUT55_PDK_ROOT", None)
            else:
                os.environ["ICSPROUT55_PDK_ROOT"] = old

    def test_clock_period_defaults_follow_liberty_units(self):
        old_argv = sys.argv
        try:
            sys.argv = ["mico_flow.py", "--pdk", "ics55"]
            self.assertEqual(parse_args().clock_period, 2.0)
            sys.argv = ["mico_flow.py"]
            self.assertEqual(parse_args().clock_period, 2000.0)
        finally:
            sys.argv = old_argv

    def test_macro_halo_is_configurable(self):
        old_argv = sys.argv
        try:
            sys.argv = ["mico_flow.py", "--pdk", "ics55", "--macro-halo", "5.0"]
            self.assertEqual(parse_args().macro_halo, 5.0)
        finally:
            sys.argv = old_argv

    def test_automatic_sram_requests_follow_generated_memory_shapes(self):
        rtl = """
module MiCoSoc;
  Ram_1wrs #(
    .wordWidth(32), .wordCount(256), .maskWidth(4), .maskEnable(1)
  ) main_mem ();
  Ram_1w_1rs #(
    .wrDataWidth(32), .wordCount(4), .wrMaskWidth(1), .wrMaskEnable(0)
  ) rf_mem ();
endmodule
"""
        old_argv = sys.argv
        try:
            sys.argv = ["mico_flow.py", "--pdk", "ics55"]
            args = parse_args()
            with TemporaryDirectory() as directory:
                rtl_path = Path(directory) / "MiCoSoc.v"
                rtl_path.write_text(rtl)
                requests = _automatic_sram_requests(args, rtl_path)
            self.assertEqual(
                [(request["words"], request["bits"], request["mux"]) for request in requests],
                [(256, 32, 8)],
            )
        finally:
            sys.argv = old_argv

    def test_automatic_sram_payload_matches_downloader_defaults(self):
        old_argv = sys.argv
        try:
            sys.argv = ["mico_flow.py", "--pdk", "ics55"]
            args = parse_args()
            self.assertEqual(
                ics55_sram_payload(args),
                {
                    "words": 2048,
                    "bits": 32,
                    "mux": 8,
                    "vt": 0,
                    "lowPower": 0,
                    "redundancy": 0,
                    "wordWrite": 0,
                    "busFormat": 1,
                    "ring": "ringless",
                    "corner": "TT1P2V25CCTYP",
                },
            )
        finally:
            sys.argv = old_argv

    def test_ics55_backend_requests_asic_sram_generation(self):
        args = types.SimpleNamespace(
            preset="minimal", ram_kbytes=1, sram_backend="ics55", no_btb=False,
        )
        self.assertIn("--asic-sram", generator_args(args, Path("staging")))

    def test_ram_port_is_parsed_and_forwarded(self):
        old_argv = sys.argv
        try:
            sys.argv = ["mico_flow.py", "--ram-port"]
            self.assertTrue(parse_args().ram_port)
            sys.argv = ["mico_flow.py", "--external-main-ram"]
            self.assertTrue(parse_args().ram_port)
        finally:
            sys.argv = old_argv

        args = types.SimpleNamespace(
            preset="minimal", ram_kbytes=1, sram_backend="soft", no_btb=False,
            ram_port=True,
        )
        self.assertIn("--ram-port", generator_args(args, Path("staging")))

    def test_ics55_default_collateral_is_discoverable(self):
        assets = discover_assets(ROOT / "asic" / "pdk" / "icsprout55-pdk")
        self.assertEqual(assets.variant("H7CR").role, "rvt")
        self.assertEqual(assets.variant("H7CL").role, "lvt")


if __name__ == "__main__":
    unittest.main()
