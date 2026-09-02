#!/usr/bin/env python3
"""Generate RTL wrappers for the Spinal synchronous memory blackboxes."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from prepare_views import validate


def bits_for(value: int) -> int:
    return max(1, (value - 1).bit_length())


def classify_memory(word_width: int, mask_width: int, mask_enable: bool) -> str:
    """Classify the write granularity emitted by Spinal."""
    if word_width <= 0 or mask_width <= 0:
        raise ValueError("word_width and mask_width must be positive")
    if not mask_enable or mask_width == 1:
        return "whole_word"
    if mask_width == math.ceil(word_width / 8):
        return "byte_lane"
    return "segment_mask"


def storage_width_for_mask(word_width: int, mask_width: int, mask_enable: bool) -> int:
    """Return the physical carrier width needed for one stored unit."""
    kind = classify_memory(word_width, mask_width, mask_enable)
    if kind == "byte_lane":
        return 8
    if kind == "segment_mask":
        return math.ceil(word_width / mask_width)
    return word_width


def _eligible_macros(manifest: dict, minimum_width: int) -> list[dict]:
    eligible = [
        macro for macro in manifest["macros"]
        if macro["width"] >= minimum_width
    ]
    preferred_widths = manifest.get("logical_lane_policy", {}).get(
        "physical_width_preference", []
    )
    if preferred_widths:
        preferred = [
            macro for macro in eligible
            if macro["width"] in preferred_widths
        ]
        if preferred:
            eligible = preferred
    return eligible


def _sram_options(manifest: dict, minimum_width: int = 8) -> list[dict]:
    eligible = _eligible_macros(manifest, minimum_width)

    # Keep one carrier per depth. The preference list controls width; the
    # wrapper then emits one such carrier for each logical byte lane.
    by_depth = {}
    for macro in eligible:
        current = by_depth.get(macro["depth"])
        if current is None or (
            macro["width"], macro["name"]
        ) < (current["width"], current["name"]):
            by_depth[macro["depth"]] = macro
    options = sorted(by_depth.values(), key=lambda macro: macro["depth"])
    if not options:
        raise ValueError(
            f"manifest has no SRAM macro at least {minimum_width} bits wide"
        )
    return options


def _macro_width_groups(
    manifest: dict, minimum_width: int = 8, bit_write_only: bool = False
) -> list[tuple[int, list[dict]]]:
    """Group available depth variants by ascending physical width."""
    groups = {}
    for macro in _eligible_macros(manifest, minimum_width):
        if bit_write_only and macro.get("write_mask_granularity") != "bit":
            continue
        groups.setdefault(macro["width"], []).append(macro)
    return [
        (width, sorted(macros, key=lambda macro: macro["depth"]))
        for width, macros in sorted(groups.items())
    ]


def select_macro(
    manifest: dict, width: int, depth: int, logical_width: int | None = None
) -> dict:
    requested_width = logical_width or width
    options = _sram_options(manifest, requested_width)
    max_depth = max(m["depth"] for m in options)
    candidates = [
        m for m in options
        if m["depth"] >= min(depth, max_depth)
    ]
    if not candidates:
        raise ValueError(f"no SRAM macro for width={requested_width}, depth={depth}")
    return min(
        candidates,
        key=lambda m: (m["width"] < requested_width, m["width"], m["depth"]),
    )


def select_packed_macro(
    manifest: dict, word_width: int, word_count: int
) -> dict | None:
    """Select one bit-write macro that covers a complete logical word."""
    candidates = [
        macro for macro in manifest["macros"]
        if macro.get("write_mask_granularity") == "bit"
        and macro["width"] >= word_width
    ]
    if not candidates:
        return None
    max_depth = max(macro["depth"] for macro in candidates)
    candidates = [
        macro for macro in candidates
        if macro["depth"] >= min(word_count, max_depth)
    ]
    return min(candidates, key=lambda macro: (
        macro["width"], macro["depth"], macro["name"]
    ))


def _padded_data(macro_width: int, payload_width: str, data_name: str) -> str:
    if payload_width.isdigit():
        padding = macro_width - int(payload_width)
        if padding == 0:
            return data_name
        return f"{{{padding}'d0, {data_name}}}"
    return (
        "{{(" + str(macro_width) + " - (" + payload_width + ")){1'b0}}, "
        + data_name
        + "}"
    )


def _bit_write_bus(macro_width: int, payload_width: str, write_mask: str) -> str:
    """Convert active-high per-bit enables into an active-low WEB bus."""
    if payload_width.isdigit():
        padding = macro_width - int(payload_width)
        if padding == 0:
            return f"~({write_mask})"
        return f"{{{{{padding}{{1'b1}}}}, ~({write_mask})}}"
    return (
        "{{(" + str(macro_width) + " - (" + payload_width + ")){1'b1}}, ~("
        + write_mask
        + ")}"
    )


def _emit_macro_chain(
    body: list[str],
    manifest: dict,
    *,
    payload_width: str,
    payload_data: str,
    payload_rdata: str,
    write_enable: str,
    prefix: str,
    rf: bool,
    write_mask: str | None = None,
) -> None:
    """Emit width/depth generate branches for one stored unit."""
    groups = _macro_width_groups(manifest, bit_write_only=write_mask is not None)
    first = True
    for width, macros in groups:
        for depth_index, macro in enumerate(macros):
            if depth_index == len(macros) - 1:
                condition = f"{payload_width} <= {width}"
            else:
                condition = (
                    f"{payload_width} <= {width} && wordCount <= {macro['depth']}"
                )
            if first:
                branch = f"      if ({condition}) begin : gen_{prefix}_{macro['name']}"
                first = False
            else:
                branch = f"      end else if ({condition}) begin : gen_{prefix}_{macro['name']}"
            body.append(branch)
            body.extend([
                f"        localparam integer BANK_COUNT = (wordCount + {macro['depth']} - 1) / {macro['depth']};",
                "        localparam integer BANK_INDEX_BITS = (BANK_COUNT > 1) ? $clog2(BANK_COUNT) : 1;",
            ])
            if rf:
                body.extend([
                    "        wire [BANK_INDEX_BITS - 1:0] wr_bank_index;",
                    "        wire [BANK_INDEX_BITS - 1:0] rd_bank_index;",
                    "        reg [BANK_INDEX_BITS - 1:0] rd_bank_index_q;",
                    f"        assign wr_bank_index = wr_addr_ext[{macro['address_width']} + BANK_INDEX_BITS - 1:{macro['address_width']}];",
                    f"        assign rd_bank_index = rd_addr_ext[{macro['address_width']} + BANK_INDEX_BITS - 1:{macro['address_width']}];",
                    "        always @(posedge rd_clk) begin",
                    "          if (rd_en && rd_dataEn) rd_bank_index_q <= rd_bank_index;",
                    "        end",
                    f"        wire [{macro['width'] - 1}:0] bank_data [0:BANK_COUNT - 1];",
                    f"        assign {payload_rdata} = bank_data[rd_bank_index_q][{payload_width} - 1:0];",
                ])
            else:
                body.extend([
                    "        wire [BANK_INDEX_BITS - 1:0] bank_index;",
                    "        reg [BANK_INDEX_BITS - 1:0] rd_bank_index_q;",
                    f"        assign bank_index = addr_ext[{macro['address_width']} + BANK_INDEX_BITS - 1:{macro['address_width']}];",
                    "        always @(posedge clk) begin",
                    "          if (en && !wr) rd_bank_index_q <= bank_index;",
                    "        end",
                    f"        wire [{macro['width'] - 1}:0] bank_data [0:BANK_COUNT - 1];",
                    f"        assign {payload_rdata} = bank_data[rd_bank_index_q][{payload_width} - 1:0];",
                ])
            for_signal = "bank_idx"
            clock_signal = "wr_clk" if rf else "clk"
            address_signal = (
                f"wr_en ? wr_addr_ext[{macro['address_width'] - 1}:0] : rd_addr_ext[{macro['address_width'] - 1}:0]"
                if rf else
                f"addr_ext[{macro['address_width'] - 1}:0]"
            )
            banksel_signal = (
                "(wr_en && (wr_bank_index == bank_idx)) || (rd_en && rd_dataEn && (rd_bank_index == bank_idx))"
                if rf else
                "en && (bank_index == bank_idx)"
            )
            read_signal = (
                "rd_en && rd_dataEn && (rd_bank_index == bank_idx)"
                if rf else
                "en && !wr && (bank_index == bank_idx)"
            )
            write_signal = (
                write_enable + " && (wr_bank_index == bank_idx)"
                if rf else
                write_enable + " && (bank_index == bank_idx)"
            )
            data_signal = _padded_data(macro["width"], payload_width, payload_data)
            port_map = macro.get("port_map")
            body.extend([
                f"        genvar {for_signal};",
                f"        for ({for_signal} = 0; {for_signal} < BANK_COUNT; {for_signal} = {for_signal} + 1) begin : gen_bank",
                f"          {macro['name']} u_sram (",
            ])
            if port_map:
                write_bus = (
                    _bit_write_bus(macro["width"], payload_width, write_mask)
                    if write_mask is not None
                    else f"{{{macro['width']}{{~({write_signal})}}}}"
                )
                body.extend([
                    f"            .{port_map['clk']}({clock_signal}),",
                    f"            .{port_map['address']}({address_signal}),",
                    f"            .{port_map['data']}({data_signal}),",
                    f"            .{port_map['banksel']}(~({banksel_signal})),",
                    f"            .{port_map['read']}({read_signal}),",
                    f"            .{port_map['write']}({write_bus}),",
                    f"            .{port_map['dataout']}(bank_data[bank_idx]),",
                ])
                if port_map.get("margin"):
                    body.append(f"            .{port_map['margin']}(4'b0),")
                if port_map.get("margin_enable"):
                    body.append(f"            .{port_map['margin_enable']}(1'b0),")
                body[-1] = body[-1].rstrip(",")
                body.append("          );")
            else:
                body.extend([
                    f"            .clk({clock_signal}),",
                    f"            .ADDRESS({address_signal}),",
                    f"            .wd({data_signal}),",
                    f"            .banksel({banksel_signal}),",
                    f"            .read({read_signal}),",
                    f"            .write({write_signal}),",
                    "            .dataout(bank_data[bank_idx])",
                    "          );",
                ])
            body.append("        end")
    body.extend([
        "      end else begin : gen_sram_unsupported_width",
        f"        assign {payload_rdata} = 'x;",
        "      end",
    ])


def generate_main(manifest: dict, word_width: int, word_count: int) -> tuple[str, dict]:
    """Generate the shared-port wrapper used by RamFiber."""
    if word_width <= 0 or word_count <= 0:
        raise ValueError("word_width and word_count must be positive")
    options = _sram_options(manifest)
    byte_mask_width = max(1, (word_width + 7) // 8)
    packed_macro = (
        select_packed_macro(manifest, word_width, word_count)
        if byte_mask_width > 1 else None
    )
    selected_macro = packed_macro or select_macro(manifest, 8, word_count, logical_width=8)
    banks = math.ceil(word_count / selected_macro["depth"])
    body = [
        "module Ram_1wrs #(",
        f"  parameter integer wordWidth = {word_width},",
        f"  parameter integer wordCount = {word_count},",
        '  parameter technology = "auto",',
        '  parameter readUnderWrite = "dontCare",',
        '  parameter duringWrite = "dontCare",',
        f"  parameter integer maskWidth = {max(1, (word_width + 7) // 8)},",
        "  parameter maskEnable = 1'b1,",
        f"  parameter integer addressWidth = {bits_for(word_count)}",
        ")(",
        "  input clk,",
        "  input en,",
        "  input wr,",
        "  input [addressWidth - 1:0] addr,",
        "  input [maskWidth - 1:0] mask,",
        "  input [wordWidth - 1:0] wrData,",
        "  output [wordWidth - 1:0] rdData",
        ");",
        "  localparam integer LANE_COUNT = (wordWidth + 7) / 8;",
        "  localparam integer SEGMENT_WIDTH = (wordWidth + maskWidth - 1) / maskWidth;",
        "  localparam BYTE_MASK_MODE = maskEnable && (maskWidth > 1) && (maskWidth == LANE_COUNT);",
        "  localparam SEGMENT_MASK_MODE = maskEnable && (maskWidth > 1) && (maskWidth < LANE_COUNT);",
        "  wire [63:0] addr_ext;",
        "  assign addr_ext = {{(64 - addressWidth){1'b0}}, addr};",
        "  wire [wordWidth - 1:0] word_rdata;",
        "  wire [SEGMENT_WIDTH - 1:0] segment_rdata [0:maskWidth - 1];",
        "  generate",
    ]
    if packed_macro is not None:
        body.extend([
            "    if (BYTE_MASK_MODE) begin : gen_packed_byte_mask",
            "      wire [wordWidth - 1:0] byte_write_mask;",
            "      genvar packed_mask_idx;",
            "      for (packed_mask_idx = 0; packed_mask_idx < LANE_COUNT; packed_mask_idx = packed_mask_idx + 1) begin : gen_packed_mask",
            "        if ((packed_mask_idx + 1) * 8 <= wordWidth) begin : gen_full_mask_byte",
            "          assign byte_write_mask[packed_mask_idx * 8 +: 8] = {8{en && wr && mask[packed_mask_idx]}};",
            "        end else begin : gen_partial_mask_byte",
            "          assign byte_write_mask[packed_mask_idx * 8 +: (wordWidth - packed_mask_idx * 8)] = {(wordWidth - packed_mask_idx * 8){en && wr && mask[packed_mask_idx]}};",
            "        end",
            "      end",
        ])
        _emit_macro_chain(
            body,
            manifest,
            payload_width="wordWidth",
            payload_data="wrData",
            payload_rdata="word_rdata",
            write_enable="en && wr",
            prefix="packed_byte",
            rf=False,
            write_mask="byte_write_mask",
        )
        body.extend([
            "      assign rdData = word_rdata;",
            "    end else if (BYTE_MASK_MODE) begin : gen_byte_mask",
            "      genvar lane_idx;",
            "      for (lane_idx = 0; lane_idx < LANE_COUNT; lane_idx = lane_idx + 1) begin : gen_lane",
            "        wire [7:0] lane_wdata;",
            "        wire [7:0] lane_rdata;",
            "        if ((lane_idx + 1) * 8 <= wordWidth) begin : gen_full_write_lane",
            "          assign lane_wdata = wrData[lane_idx * 8 +: 8];",
            "        end else begin : gen_partial_write_lane",
            "          assign lane_wdata = {{(8 - (wordWidth - lane_idx * 8)){1'b0}}, wrData[wordWidth - 1:lane_idx * 8]};",
            "        end",
        ])
    else:
        body.extend([
            "    if (BYTE_MASK_MODE) begin : gen_byte_mask",
            "      genvar lane_idx;",
            "      for (lane_idx = 0; lane_idx < LANE_COUNT; lane_idx = lane_idx + 1) begin : gen_lane",
            "        wire [7:0] lane_wdata;",
            "        wire [7:0] lane_rdata;",
            "        if ((lane_idx + 1) * 8 <= wordWidth) begin : gen_full_write_lane",
            "          assign lane_wdata = wrData[lane_idx * 8 +: 8];",
            "        end else begin : gen_partial_write_lane",
            "          assign lane_wdata = {{(8 - (wordWidth - lane_idx * 8)){1'b0}}, wrData[wordWidth - 1:lane_idx * 8]};",
            "        end",
        ])
    _emit_macro_chain(
        body,
        manifest,
        payload_width="8",
        payload_data="lane_wdata",
        payload_rdata="lane_rdata",
        write_enable="en && wr && mask[lane_idx]",
        prefix="byte",
        rf=False,
    )
    body.extend([
        "        if ((lane_idx + 1) * 8 <= wordWidth) begin : gen_full_read_lane",
        "          assign rdData[lane_idx * 8 +: 8] = lane_rdata;",
        "        end else begin : gen_partial_read_lane",
        "          assign rdData[lane_idx * 8 +: (wordWidth - lane_idx * 8)] = lane_rdata[wordWidth - lane_idx * 8 - 1:0];",
        "        end",
        "      end",
        "    end else if (SEGMENT_MASK_MODE) begin : gen_segment_mask",
        "      genvar segment_idx;",
        "      for (segment_idx = 0; segment_idx < maskWidth; segment_idx = segment_idx + 1) begin : gen_segment",
        "        wire [SEGMENT_WIDTH - 1:0] segment_wdata;",
        "        if ((segment_idx + 1) * SEGMENT_WIDTH <= wordWidth) begin : gen_full_segment_write",
        "          assign segment_wdata = wrData[segment_idx * SEGMENT_WIDTH +: SEGMENT_WIDTH];",
        "        end else begin : gen_partial_segment_write",
        "          assign segment_wdata = {{(SEGMENT_WIDTH - (wordWidth - segment_idx * SEGMENT_WIDTH)){1'b0}}, wrData[wordWidth - 1:segment_idx * SEGMENT_WIDTH]};",
        "        end",
    ])
    _emit_macro_chain(
        body,
        manifest,
        payload_width="SEGMENT_WIDTH",
        payload_data="segment_wdata",
        payload_rdata="segment_rdata[segment_idx]",
        write_enable="en && wr && mask[segment_idx]",
        prefix="segment",
        rf=False,
    )
    body.extend([
        "        if ((segment_idx + 1) * SEGMENT_WIDTH <= wordWidth) begin : gen_full_segment_read",
        "          assign rdData[segment_idx * SEGMENT_WIDTH +: SEGMENT_WIDTH] = segment_rdata[segment_idx];",
        "        end else begin : gen_partial_segment_read",
        "          assign rdData[segment_idx * SEGMENT_WIDTH +: (wordWidth - segment_idx * SEGMENT_WIDTH)] = segment_rdata[segment_idx][wordWidth - segment_idx * SEGMENT_WIDTH - 1:0];",
        "        end",
        "      end",
        "    end else begin : gen_whole_word",
    ])
    _emit_macro_chain(
        body,
        manifest,
        payload_width="wordWidth",
        payload_data="wrData",
        payload_rdata="word_rdata",
        write_enable="en && wr && (!maskEnable || mask[0])",
        prefix="whole",
        rf=False,
    )
    body.extend([
        "      assign rdData = word_rdata;",
        "    end",
        "  endgenerate",
        "endmodule",
    ])
    return "\n".join(body), {
        "kind": "byte_lane",
        "macro": selected_macro["name"],
        "carrier_macro": selected_macro["name"],
        "banks": banks,
        "carrier_banks": banks,
        "logical_lanes": (word_width + 7) // 8,
        "logical_width": word_width,
        "physical_width": selected_macro["width"],
        "packing": "full_word" if packed_macro is not None else "byte_lane",
        "physical_macros": [macro["name"] for macro in options],
    }


def generate_rf(manifest: dict, word_width: int, word_count: int) -> tuple[str, dict]:
    """Generate the synchronous single-port wrapper used by cache memories and RFs."""
    if word_width <= 0 or word_count <= 0:
        raise ValueError("word_width and word_count must be positive")
    options = _sram_options(manifest)
    selected_macro = select_macro(manifest, 8, word_count, logical_width=8)
    banks = math.ceil(word_count / selected_macro["depth"])
    body = [
        "module Ram_1w_1rs #(",
        f"  parameter integer wordCount = {word_count},",
        f"  parameter integer wordWidth = {word_width},",
        "  parameter clockCrossing = 1'b0,",
        '  parameter technology = "auto",',
        '  parameter readUnderWrite = "dontCare",',
        f"  parameter integer wrAddressWidth = {bits_for(word_count)},",
        f"  parameter integer wrDataWidth = {word_width},",
        "  parameter integer wrMaskWidth = 1,",
        "  parameter wrMaskEnable = 1'b0,",
        f"  parameter integer rdAddressWidth = {bits_for(word_count)},",
        f"  parameter integer rdDataWidth = {word_width},",
        "  parameter integer rdLatency = 1",
        ")(",
        "  input wr_clk,",
        "  input wr_en,",
        "  input [wrMaskWidth - 1:0] wr_mask,",
        "  input [wrAddressWidth - 1:0] wr_addr,",
        "  input [wrDataWidth - 1:0] wr_data,",
        "  input rd_clk,",
        "  input rd_en,",
        "  input rd_dataEn,",
        "  input [rdAddressWidth - 1:0] rd_addr,",
        "  output [rdDataWidth - 1:0] rd_data",
        ");",
        "  localparam integer LANE_COUNT = (wrDataWidth + 7) / 8;",
        "  localparam integer SEGMENT_WIDTH = (wrDataWidth + wrMaskWidth - 1) / wrMaskWidth;",
        "  localparam BYTE_MASK_MODE = wrMaskEnable && (wrMaskWidth > 1) && (wrMaskWidth == LANE_COUNT);",
        "  localparam SEGMENT_MASK_MODE = wrMaskEnable && (wrMaskWidth > 1) && (wrMaskWidth < LANE_COUNT);",
        "  wire [63:0] wr_addr_ext;",
        "  wire [63:0] rd_addr_ext;",
        "  assign wr_addr_ext = {{(64 - wrAddressWidth){1'b0}}, wr_addr};",
        "  assign rd_addr_ext = {{(64 - rdAddressWidth){1'b0}}, rd_addr};",
        "  wire [wrDataWidth - 1:0] word_rdata;",
        "  wire [SEGMENT_WIDTH - 1:0] segment_rdata [0:wrMaskWidth - 1];",
        "  generate",
        "    if (BYTE_MASK_MODE) begin : gen_byte_mask",
        "      genvar lane_idx;",
        "      for (lane_idx = 0; lane_idx < LANE_COUNT; lane_idx = lane_idx + 1) begin : gen_lane",
        "        wire [7:0] lane_wdata;",
        "        wire [7:0] lane_rdata;",
        "        if ((lane_idx + 1) * 8 <= wrDataWidth) begin : gen_full_write_lane",
        "          assign lane_wdata = wr_data[lane_idx * 8 +: 8];",
        "        end else if (lane_idx * 8 < wrDataWidth) begin : gen_partial_write_lane",
        "          assign lane_wdata = {{(8 - (wrDataWidth - lane_idx * 8)){1'b0}}, wr_data[wrDataWidth - 1:lane_idx * 8]};",
        "        end else begin : gen_empty_write_lane",
        "          assign lane_wdata = 8'b0;",
        "        end",
    ]
    _emit_macro_chain(
        body,
        manifest,
        payload_width="8",
        payload_data="lane_wdata",
        payload_rdata="lane_rdata",
        write_enable="wr_en && wr_mask[lane_idx]",
        prefix="byte",
        rf=True,
    )
    body.extend([
        "        if ((lane_idx + 1) * 8 <= rdDataWidth) begin : gen_full_read_lane",
        "          assign rd_data[lane_idx * 8 +: 8] = lane_rdata;",
        "        end else if (lane_idx * 8 < rdDataWidth) begin : gen_partial_read_lane",
        "          assign rd_data[lane_idx * 8 +: (rdDataWidth - lane_idx * 8)] = lane_rdata[rdDataWidth - lane_idx * 8 - 1:0];",
        "        end",
        "      end",
        "    end else if (SEGMENT_MASK_MODE) begin : gen_segment_mask",
        "      genvar segment_idx;",
        "      for (segment_idx = 0; segment_idx < wrMaskWidth; segment_idx = segment_idx + 1) begin : gen_segment",
        "        wire [SEGMENT_WIDTH - 1:0] segment_wdata;",
        "        if ((segment_idx + 1) * SEGMENT_WIDTH <= wrDataWidth) begin : gen_full_segment_write",
        "          assign segment_wdata = wr_data[segment_idx * SEGMENT_WIDTH +: SEGMENT_WIDTH];",
        "        end else begin : gen_partial_segment_write",
        "          assign segment_wdata = {{(SEGMENT_WIDTH - (wrDataWidth - segment_idx * SEGMENT_WIDTH)){1'b0}}, wr_data[wrDataWidth - 1:segment_idx * SEGMENT_WIDTH]};",
        "        end",
    ])
    _emit_macro_chain(
        body,
        manifest,
        payload_width="SEGMENT_WIDTH",
        payload_data="segment_wdata",
        payload_rdata="segment_rdata[segment_idx]",
        write_enable="wr_en && wr_mask[segment_idx]",
        prefix="segment",
        rf=True,
    )
    body.extend([
        "        if ((segment_idx + 1) * SEGMENT_WIDTH <= rdDataWidth) begin : gen_full_segment_read",
        "          assign rd_data[segment_idx * SEGMENT_WIDTH +: SEGMENT_WIDTH] = segment_rdata[segment_idx];",
        "        end else begin : gen_partial_segment_read",
        "          assign rd_data[segment_idx * SEGMENT_WIDTH +: (rdDataWidth - segment_idx * SEGMENT_WIDTH)] = segment_rdata[segment_idx][rdDataWidth - segment_idx * SEGMENT_WIDTH - 1:0];",
        "        end",
        "      end",
        "    end else begin : gen_whole_word",
    ])
    _emit_macro_chain(
        body,
        manifest,
        payload_width="wrDataWidth",
        payload_data="wr_data",
        payload_rdata="word_rdata",
        write_enable="wr_en && (!wrMaskEnable || wr_mask[0])",
        prefix="whole",
        rf=True,
    )
    body.extend([
        "      assign rd_data = word_rdata[rdDataWidth - 1:0];",
        "    end",
        "  endgenerate",
        "endmodule",
    ])
    return "\n".join(body), {
        "kind": "sync_single_port",
        "macro": selected_macro["name"],
        "carrier_macro": selected_macro["name"],
        "banks": banks,
        "carrier_banks": banks,
        "logical_width": word_width,
        "physical_width": selected_macro["width"],
        "physical_macros": [macro["name"] for macro in options],
    }


def generate_blackbox_declarations(manifest: dict) -> str:
    """Generate Yosys declarations for every abstract macro in a manifest."""
    sections = [
        "// Generated by generate_sram_wrappers.py",
        "// Abstract-only SRAM declarations; no behavioral storage.",
        "",
    ]
    for macro in manifest["macros"]:
        port_map = macro.get("port_map")
        if port_map:
            ports = [
                f"  input {port_map['clk']},",
                f"  input [{macro['address_width']} - 1:0] {port_map['address']},",
                f"  input [{macro['width']} - 1:0] {port_map['data']},",
                f"  input {port_map['banksel']},",
                f"  input {port_map['read']},",
                f"  input [{macro['width']} - 1:0] {port_map['write']},",
                f"  output [{macro['width']} - 1:0] {port_map['dataout']},",
            ]
            if port_map.get("margin"):
                ports.append(f"  input [3:0] {port_map['margin']},")
            if port_map.get("margin_enable"):
                ports.append(f"  input {port_map['margin_enable']},")
            ports[-1] = ports[-1].rstrip(",")
        else:
            ports = [
                "  input clk,",
                f"  input [{macro['address_width']} - 1:0] ADDRESS,",
                f"  input [{macro['width']} - 1:0] wd,",
                "  input banksel,",
                "  input read,",
                "  input write,",
                f"  output [{macro['width']} - 1:0] dataout",
            ]
        sections.extend([
            "(* blackbox *)",
            f"module {macro['name']} (",
            *ports,
            ");",
            "endmodule",
            "",
        ])
    return "\n".join(sections)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("sram_views.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--main-word-count", type=int, required=True)
    parser.add_argument("--main-word-width", type=int, default=32)
    parser.add_argument("--rf-word-count", type=int)
    parser.add_argument("--rf-word-width", type=int)
    parser.add_argument("--blackbox-output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest = validate(args.manifest.resolve())
    if args.blackbox_output is not None:
        args.blackbox_output.parent.mkdir(parents=True, exist_ok=True)
        args.blackbox_output.write_text(generate_blackbox_declarations(manifest))
    main_text, main_metadata = generate_main(manifest, args.main_word_width, args.main_word_count)
    sections = ["// Generated by generate_sram_wrappers.py", "", main_text]
    metadata = {"main": main_metadata, "rf": None}
    if args.rf_word_count is not None or args.rf_word_width is not None:
        if args.rf_word_count is None or args.rf_word_width is None:
            raise ValueError("--rf-word-count and --rf-word-width must be supplied together")
        rf_text, rf_metadata = generate_rf(manifest, args.rf_word_width, args.rf_word_count)
        sections.extend(["", rf_text])
        metadata["rf"] = rf_metadata
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(sections) + "\n")
    args.output.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"generated SRAM wrappers -> {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
