#!/usr/bin/env python3
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FLOW = ROOT / "asic" / "flow"
sys.path.insert(0, str(FLOW))
from generate_sram_wrappers import (
    classify_memory,
    generate_blackbox_declarations,
    generate_main,
    generate_rf,
    select_macro,
    storage_width_for_mask,
)
from mico_flow import active_sram_macros, generator_args, main_ram_word_count
from prepare_views import normalize_liberty, prepare, validate


class SramFlowTest(unittest.TestCase):
    def test_minimal_preset_has_no_cpu_feature_flags(self):
        args = types.SimpleNamespace(
            preset="minimal", ram_kbytes=1, sram_backend="soft", no_btb=False
        )
        generated = generator_args(args, Path("staging"))
        self.assertEqual(
            generated,
            [
                "--ram-kbytes", "1",
                "--netlist-directory", "staging", "--netlist-name", "MiCoSoc",
            ],
        )

    def test_minimal_bncfu_uses_only_requested_cfu_features(self):
        args = types.SimpleNamespace(
            preset="minimal-bncfu", ram_kbytes=1, sram_backend="asap7", no_btb=False,
            bitnet_cfu_len=256, bitnet_cfu_width=128, bitnet_cfu_reg_depth=2,
            bitnet_cfu_bus_width=64, bitnet_cfu_qtype="1.5b",
            bitnet_cfu_quant_width=64, with_q2t=False, with_q8=False,
            q8_compare_pipe=False, bitnet_cfu_pipe=False,
        )
        generated = generator_args(args, Path("staging"))
        self.assertIn("--mico-bitnet-cfu", generated)
        self.assertIn("--bitnet-cfu-len", generated)
        self.assertIn("256", generated)
        self.assertIn("--bitnet-cfu-width", generated)
        self.assertIn("128", generated)
        self.assertIn("--bitnet-cfu-bus-width", generated)
        self.assertIn("--bitnet-cfu-quant-width", generated)
        self.assertEqual(generated[generated.index("--bitnet-cfu-quant-width") + 1], "64")
        self.assertNotIn("--with-rvc", generated)
        self.assertNotIn("--with-btb", generated)

    def test_asap7_btb_uses_single_port_default(self):
        args = types.SimpleNamespace(
            preset="base", ram_kbytes=8, sram_backend="asap7", no_btb=False
        )
        generated = generator_args(args, Path("staging"))
        self.assertIn("--with-btb", generated)
        self.assertIn("--btb-single-port-ram", generated)

    def test_ics55_byte_mask_prefers_one_bit_write_macro(self):
        port_map = {
            "clk": "CLK", "address": "A", "data": "D",
            "banksel": "CEB", "read": "GWEB", "write": "WEB",
            "dataout": "Q", "margin": "MAR", "margin_enable": "MARE",
        }
        manifest = {
            "macros": [
                {
                    "name": "ics55_256x8", "depth": 256, "width": 8,
                    "address_width": 8, "port_map": port_map,
                    "write_mask_granularity": "bit",
                },
                {
                    "name": "ics55_256x32", "depth": 256, "width": 32,
                    "address_width": 8, "port_map": port_map,
                    "write_mask_granularity": "bit",
                },
            ],
            "logical_lane_policy": {"physical_width_preference": [8, 32]},
        }
        text, metadata = generate_main(manifest, 32, 256)
        self.assertEqual(metadata["macro"], "ics55_256x32")
        self.assertEqual(metadata["packing"], "full_word")
        self.assertIn("gen_packed_byte_ics55_256x32", text)
        self.assertIn("~(byte_write_mask)", text)

    def test_ics55_sync_wrapper_packs_byte_mask(self):
        port_map = {
            "clk": "CLK", "address": "A", "data": "D",
            "banksel": "CEB", "read": "GWEB", "write": "WEB",
            "dataout": "Q", "margin": "MAR", "margin_enable": "MARE",
        }
        manifest = {
            "macros": [
                {
                    "name": "ics55_256x8", "depth": 256, "width": 8,
                    "address_width": 8, "port_map": port_map,
                    "write_mask_granularity": "bit",
                },
                {
                    "name": "ics55_256x32", "depth": 256, "width": 32,
                    "address_width": 8, "port_map": port_map,
                    "write_mask_granularity": "bit",
                },
            ],
            "logical_lane_policy": {"physical_width_preference": [8, 32]},
        }
        text, metadata = generate_rf(manifest, 8, 1)
        self.assertEqual(metadata["macro"], "ics55_256x8")
        self.assertIn("gen_packed_byte_ics55_256x32", text)
        self.assertIn("wrDataWidth <= 32", text)
        self.assertIn("byte_write_mask[packed_mask_idx * 8 +: 8]", text)
        self.assertIn("~(byte_write_mask)", text)

    def test_memory_mask_classes_select_storage_width(self):
        self.assertEqual(classify_memory(32, 4, True), "byte_lane")
        self.assertEqual(storage_width_for_mask(32, 4, True), 8)
        self.assertEqual(classify_memory(52, 1, True), "whole_word")
        self.assertEqual(storage_width_for_mask(52, 1, True), 52)
        self.assertEqual(classify_memory(102, 2, True), "segment_mask")
        self.assertEqual(storage_width_for_mask(102, 2, True), 51)
        self.assertEqual(classify_memory(22, 1, False), "whole_word")

    def test_btb_entry_mask_uses_whole_or_segment_units(self):
        manifest = validate(FLOW / "sram_views.json")
        text, _ = generate_main(manifest, 52, 512)
        self.assertIn("SEGMENT_MASK_MODE", text)
        self.assertIn("gen_whole_word", text)
        self.assertIn("mask[0]", text)
        self.assertIn("mask[segment_idx]", text)

    def test_abstract_width_selection_is_not_always_x16(self):
        manifest = validate(FLOW / "sram_views_abstract.json")
        self.assertEqual(
            select_macro(manifest, 32, 512)["name"],
            "srambank_128x4x32_6t122",
        )
        self.assertEqual(
            select_macro(manifest, 51, 512)["name"],
            "srambank_128x4x64_6t122",
        )

    def test_active_macro_discovery_classifies_generated_ram_instances(self):
        manifest = validate(FLOW / "sram_views_abstract.json")
        source = """
Ram_1wrs #(
  .wordCount(256), .wordWidth(32), .maskWidth(4), .maskEnable(1'b1)
) byte_mem (
);
Ram_1wrs #(
  .wordCount(256), .wordWidth(102), .maskWidth(2), .maskEnable(1'b1)
) btb_mem (
);
Ram_1w_1rs #(
  .wordCount(512), .wrDataWidth(22), .wrMaskWidth(1), .wrMaskEnable(1'b0)
) tag_mem (
);
"""
        with tempfile.TemporaryDirectory(prefix="mico-sram-discovery-") as tmp:
            rtl = Path(tmp) / "MiCoSoc.v"
            rtl.write_text(source)
            names, records = active_sram_macros(rtl, manifest)
        self.assertEqual(
            {record["kind"] for record in records},
            {"byte_lane", "segment_mask", "whole_word"},
        )
        self.assertIn("srambank_64x4x16_6t122", names)
        self.assertIn("srambank_64x4x64_6t122", names)
        self.assertIn("srambank_128x4x32_6t122", names)

    def test_micosoc_ram_depth_uses_32_bit_words(self):
        self.assertEqual(main_ram_word_count(8), 2048)
        self.assertEqual(main_ram_word_count(512), 131072)
        with self.assertRaises(ValueError):
            main_ram_word_count(0)

    def test_abstract_manifest_maps_all_lib_macros(self):
        manifest = validate(FLOW / "sram_views_abstract.json")
        self.assertFalse(manifest["physical_views"])
        self.assertEqual(len(manifest["macros"]), 36)
        self.assertEqual({macro["width"] for macro in manifest["macros"]}, {16, 18, 20, 32, 34, 36, 40, 48, 64, 72, 74, 80})
        main_text, metadata = generate_main(manifest, 32, 8192)
        self.assertEqual(metadata["macro"], "srambank_256x4x16_6t122")
        self.assertEqual(metadata["physical_width"], 16)
        self.assertEqual(
            metadata["physical_macros"],
            [
                "srambank_64x4x16_6t122",
                "srambank_128x4x16_6t122",
                "srambank_256x4x16_6t122",
            ],
        )
        self.assertIn("wd({8'd0, lane_wdata})", main_text)
        blackboxes = generate_blackbox_declarations(manifest)
        self.assertEqual(blackboxes.count("module "), 36)
        self.assertIn("module srambank_256x4x80_6t122", blackboxes)

    def test_abstract_preparation_rewrites_site_without_gds(self):
        with tempfile.TemporaryDirectory(prefix="mico-abstract-views-") as tmp:
            output = Path(tmp) / "prepared_sram_views.json"
            prepared = prepare(FLOW / "sram_views_abstract.json", output)
            self.assertFalse(prepared["physical_views"])
            self.assertFalse((output.parent / "prepared_gds").exists())
            self.assertEqual(len(list((output.parent / "prepared_lef").glob("*.lef"))), 36)
            for macro in prepared["macros"]:
                self.assertNotIn("gds", macro)
                self.assertIn("SITE asap7sc7p5t ;", Path(macro["lef"]).read_text())

    def test_manifest_contains_only_gds_valid_carriers(self):
        manifest = validate(FLOW / "sram_views.json")
        self.assertEqual(len(manifest["macros"]), 3)
        self.assertEqual({macro["width"] for macro in manifest["macros"]}, {64})
        self.assertEqual({macro["depth"] for macro in manifest["macros"]}, {256, 512, 1024})
        for macro in manifest["macros"]:
            lef = (ROOT / macro["lef"]).read_text()
            self.assertIn("SITE asap7sc7p5t ;", lef)
            self.assertNotIn("SITE coreSite ;", lef)

    def test_liberty_address_bus_normalization(self):
        source = ROOT / "asic" / "pdk" / "asap7sram" / "asap7_sram_0p0" / "generated" / "LIB" / "srambank_128x4x64_6t122.lib"
        with tempfile.TemporaryDirectory(prefix="mico-lib-test-") as tmp:
            output = Path(tmp) / "normalized.lib"
            normalize_liberty(source, output, 9)
            text = output.read_text()
            self.assertIn("bit_width : 9;", text)
            self.assertIn("bit_from  : 8;", text)

    def test_large_byte_lane_and_rf_banking(self):
        manifest = validate(FLOW / "sram_views.json")
        main_text, main_meta = generate_main(manifest, 32, 2048)
        rf_text, rf_meta = generate_rf(manifest, 32, 5)
        self.assertEqual(main_meta["macro"], "srambank_256x4x64_6t122")
        self.assertEqual(main_meta["banks"], 2)
        self.assertEqual(main_meta["logical_lanes"], 4)
        self.assertEqual(main_meta["physical_width"], 64)
        self.assertEqual(rf_meta["macro"], "srambank_64x4x64_6t122")
        self.assertEqual(main_meta["carrier_macro"], "srambank_256x4x64_6t122")
        self.assertEqual(rf_meta["carrier_macro"], "srambank_64x4x64_6t122")
        self.assertIn("(wr_en ? wr_addr_ext[7:0] : rd_addr_ext[7:0])", rf_text)
        self.assertIn("wd({56'd0, lane_wdata})", rf_text)
        self.assertIn("BANK_COUNT", main_text + rf_text)
        self.assertNotIn("srambank_256x4x32_6t122", main_text + rf_text)

    def test_macro_selection_boundaries_match_metadata(self):
        manifest = validate(FLOW / "sram_views.json")
        expected = {
            1: ("srambank_64x4x64_6t122", 1, 8),
            256: ("srambank_64x4x64_6t122", 1, 8),
            257: ("srambank_128x4x64_6t122", 1, 9),
            512: ("srambank_128x4x64_6t122", 1, 9),
            513: ("srambank_256x4x64_6t122", 1, 10),
            1024: ("srambank_256x4x64_6t122", 1, 10),
            1025: ("srambank_256x4x64_6t122", 2, 10),
        }
        for word_count, (macro, banks, address_width) in expected.items():
            text, metadata = generate_main(manifest, 32, word_count)
            self.assertEqual(metadata["macro"], macro)
            self.assertEqual(metadata["banks"], banks)
            self.assertEqual(metadata["carrier_macro"], macro)
            self.assertEqual(metadata["carrier_banks"], banks)
            self.assertIn(f".ADDRESS(addr_ext[{address_width - 1}:0])", text)
            if word_count <= 512:
                self.assertIn(f"wordCount <= {256 if word_count <= 256 else 512}", text)
            else:
                self.assertIn("BANK_COUNT = (wordCount + 1024 - 1) / 1024", text)

    @unittest.skipUnless(shutil.which("verilator"), "verilator is required")
    def test_byte_enable_behavior(self):
        macro_sources = [
            ROOT / "asic" / "pdk" / "asap7sram" / "asap7_sram_0p0" / "generated" / "verilog" / name
            for name in (
                "srambank_64x4x64_6t122.v",
                "srambank_128x4x64_6t122.v",
                "srambank_256x4x64_6t122.v",
            )
        ]
        generator = FLOW / "generate_sram_wrappers.py"
        testbench = ROOT / "asic" / "tests" / "sram_wrapper_tb.sv"
        with tempfile.TemporaryDirectory(prefix="mico-sram-test-") as tmp:
            tmp_path = Path(tmp)
            wrapper = tmp_path / "wrappers.v"
            mdir = tmp_path / "obj_dir"
            subprocess.run([
                sys.executable, str(generator), "--output", str(wrapper),
                "--main-word-count", "16", "--main-word-width", "32",
            ], cwd=ROOT, check=True, capture_output=True, text=True)
            subprocess.run([
                "verilator", "--binary", "--timing", "-Wno-TIMESCALEMOD",
                "--Mdir", str(mdir), "--top-module", "sram_wrapper_tb",
                str(wrapper), *map(str, macro_sources), str(testbench)
            ], cwd=ROOT, check=True, capture_output=True, text=True)
            result = subprocess.run([str(mdir / "Vsram_wrapper_tb")], check=True, capture_output=True, text=True)
            self.assertIn("PASS: byte-enable", result.stdout)

    @unittest.skipUnless(shutil.which("verilator"), "verilator is required")
    def test_sync_read_write_arbitrary_cache_width(self):
        macro_sources = [
            ROOT / "asic" / "pdk" / "asap7sram" / "asap7_sram_0p0" / "generated" / "verilog" / name
            for name in (
                "srambank_64x4x64_6t122.v",
                "srambank_128x4x64_6t122.v",
                "srambank_256x4x64_6t122.v",
            )
        ]
        generator = FLOW / "generate_sram_wrappers.py"
        testbench_source = """\
`timescale 1ns/1ps
module sram_1w_1rs_tb;
  logic clk = 1'b0;
  logic wr_en = 1'b0;
  logic [8:0] wr_addr = '0;
  logic [21:0] wr_data = '0;
  logic rd_en = 1'b0;
  logic [8:0] rd_addr = '0;
  wire [21:0] rd_data;

  always #1 clk = ~clk;

  Ram_1w_1rs #(
    .wordCount(512),
    .wordWidth(22),
    .wrAddressWidth(9),
    .wrDataWidth(22),
    .wrMaskWidth(1),
    .wrMaskEnable(1'b0),
    .rdAddressWidth(9),
    .rdDataWidth(22)
  ) dut (
    .wr_clk(clk), .wr_en(wr_en), .wr_mask(1'b1),
    .wr_addr(wr_addr), .wr_data(wr_data),
    .rd_clk(clk), .rd_en(rd_en), .rd_dataEn(1'b1),
    .rd_addr(rd_addr), .rd_data(rd_data)
  );

  initial begin
    @(negedge clk);
    wr_en = 1'b1;
    wr_addr = 9'd300;
    wr_data = 22'h2abcde;
    @(posedge clk);
    @(negedge clk);
    wr_en = 1'b0;
    rd_en = 1'b1;
    rd_addr = 9'd300;
    @(posedge clk);
    #0.1;
    if (rd_data !== 22'h2abcde) begin
      $display("FAIL: expected %h, got %h", 22'h2abcde, rd_data);
      $fatal(1);
    end
    $display("PASS: arbitrary cache width and bank select");
    $finish;
  end
endmodule
"""
        with tempfile.TemporaryDirectory(prefix="mico-sram-rw-test-") as tmp:
            tmp_path = Path(tmp)
            wrapper = tmp_path / "wrappers.v"
            testbench = tmp_path / "sram_1w_1rs_tb.sv"
            mdir = tmp_path / "obj_dir"
            testbench.write_text(testbench_source)
            subprocess.run([
                sys.executable, str(generator), "--output", str(wrapper),
                "--main-word-count", "2048", "--main-word-width", "32",
                "--rf-word-count", "5", "--rf-word-width", "64",
            ], cwd=ROOT, check=True, capture_output=True, text=True)
            subprocess.run([
                "verilator", "--binary", "--timing", "-Wno-TIMESCALEMOD",
                "--Mdir", str(mdir), "--top-module", "sram_1w_1rs_tb",
                str(wrapper), *map(str, macro_sources), str(testbench)
            ], cwd=ROOT, check=True, capture_output=True, text=True)
            result = subprocess.run([str(mdir / "Vsram_1w_1rs_tb")], check=True, capture_output=True, text=True)
            self.assertIn("PASS: arbitrary cache width", result.stdout)

    def test_no_main_omits_main_wrapper_definition(self):
        generator = FLOW / "generate_sram_wrappers.py"
        with tempfile.TemporaryDirectory(prefix="mico-sram-no-main-") as tmp:
            wrapper = Path(tmp) / "wrappers.v"
            subprocess.run([
                sys.executable, str(generator), "--output", str(wrapper),
                "--main-word-count", "16", "--no-main",
            ], cwd=ROOT, check=True, capture_output=True, text=True)
            self.assertNotIn("module Ram_1wrs", wrapper.read_text())


if __name__ == "__main__":
    unittest.main()
