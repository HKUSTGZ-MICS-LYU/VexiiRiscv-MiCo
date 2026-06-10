#!/usr/bin/env python3
"""Merge LLaMa ablation benchmark rows with backfilled reruns.

The standard-ablation run may contain failed rows.  This helper overlays
successful backfill rows on top of the original results and annotates the
merged table with 500 MHz throughput plus the actual attention sample position
used by best/mid/worst cases.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


REPO = Path(__file__).resolve().parents[3]
DEFAULT_MAIN = REPO / "hw/VexiiMico/benchmark_results/e2e_llama_standard_ablation_large_fpu_3m_28m_20260607_213225"
DEFAULT_BACKFILL = REPO / "hw/VexiiMico/benchmark_results/e2e_llama_align_fix_backfill_20260608_124605"
DEFAULT_OUT = REPO / "hw/VexiiMico/benchmark_results/e2e_llama_standard_ablation_large_fpu_3m_28m_merged"

KEY_FIELDS = ("model", "software_corner", "hardware", "variant", "ablation_path")
DEFAULT_FREQ_HZ = 500_000_000.0
PROFILE_OPS = (
    "qmatmul",
    "quant",
    "fmatmul",
    "attention",
    "softmax",
    "rmsnorm",
    "rope",
    "kv_quant",
    "swiglu",
    "residual",
    "lm_head",
)


def read_csv(path: Path) -> List[Dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def find_result_csvs(path: Path) -> List[Path]:
    if path.is_file():
        return [path]
    if (path / "results.csv").exists():
        return [path / "results.csv"]
    return sorted(path.glob("**/results.csv"))


def read_result_tree(path: Path) -> List[Dict[str, str]]:
    rows: List[Dict[str, str]] = []
    for csv_path in find_result_csvs(path):
        for row in read_csv(csv_path):
            row["_source_csv"] = str(csv_path)
            rows.append(row)
    return rows


def key(row: Dict[str, str]) -> Tuple[str, ...]:
    return tuple(row.get(field, "") for field in KEY_FIELDS)


def is_success(row: Dict[str, str]) -> bool:
    return row.get("build_rc") == "0" and row.get("sim_rc") == "0" and bool(row.get("primary_cycles"))


def parse_int(value: object) -> Optional[int]:
    if value is None:
        return None
    text = str(value).strip()
    if text == "":
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def parse_float(value: object) -> Optional[float]:
    if value is None:
        return None
    text = str(value).strip()
    if text == "":
        return None
    try:
        return float(text)
    except ValueError:
        return None


def fmt_float(value: Optional[float], digits: int = 6) -> str:
    if value is None:
        return ""
    return f"{value:.{digits}f}"


def compute_position(length: Optional[int], group_size: Optional[int], case_name: str) -> Optional[int]:
    if length is None or length <= 0:
        return None
    if group_size is None or group_size <= 0:
        group_size = 32
    case_name = case_name or "legacy"
    if case_name == "legacy":
        return None
    max_pos = max(0, length - 1)
    if case_name == "best":
        residue = 0
    elif case_name == "mid":
        residue = max(0, group_size // 2 - 1)
    elif case_name == "worst":
        residue = group_size - 1
    else:
        return None
    if max_pos < residue:
        return max_pos
    return max_pos - ((max_pos - residue) % group_size)


def case_description(case_name: str, pos: Optional[int], group_size: Optional[int]) -> str:
    if pos is None:
        return ""
    if group_size is None or group_size <= 0:
        group_size = 32
    current_float_tokens = (pos % group_size) + 1
    packed_groups = pos // group_size
    return f"pos={pos}, packed_groups={packed_groups}, current_float_tokens={current_float_tokens}"


def tokens_per_second(cycles: Optional[float], freq_hz: float) -> Optional[float]:
    if cycles is None or cycles <= 0:
        return None
    return freq_hz / float(cycles)


def ceil_div(value: int, divisor: int) -> int:
    return (value + divisor - 1) // divisor


def kivi_cache_bytes(row: Dict[str, str], group_size: int) -> Dict[str, Optional[float]]:
    dim = parse_int(row.get("dim"))
    n_layers = parse_int(row.get("n_layers"))
    n_heads = parse_int(row.get("n_heads"))
    n_kv_heads = parse_int(row.get("n_kv_heads"))
    seq_len = parse_int(row.get("seq_len"))
    vlen = parse_int(row.get("vlen")) or 256
    if not dim or not n_layers or not n_heads or not n_kv_heads or not seq_len or group_size <= 0:
        return {}

    head_size = dim // n_heads
    kv_dim = (dim * n_kv_heads) // n_heads
    n_groups = ceil_div(seq_len, group_size)
    packed_group_bytes = ceil_div(group_size * head_size, 4)

    baseline_bytes = 2 * n_layers * seq_len * kv_dim * 4
    current_group_bytes = 2 * n_layers * group_size * kv_dim * 4
    q2t_bytes = 2 * n_layers * n_groups * n_kv_heads * packed_group_bytes
    scale_bytes = n_layers * n_groups * n_kv_heads * (head_size + group_size) * 4
    logical_bytes = q2t_bytes + scale_bytes + current_group_bytes

    bncfu_bytes = vlen // 8
    q8_elems = vlen // 8
    q2_full_elems = q8_elems * 4
    head_chunks = ceil_div(head_size, q2_full_elems)
    group_chunks = ceil_div(group_size, q8_elems)
    k_bdot_group_bytes = group_size * head_chunks * bncfu_bytes
    v_bdot_group_bytes = ceil_div(head_size, 4) * 4 * group_chunks * bncfu_bytes
    bdot_bytes = n_layers * n_groups * n_kv_heads * (k_bdot_group_bytes + v_bdot_group_bytes)
    physical_bytes = logical_bytes + bdot_bytes

    return {
        "baseline": float(baseline_bytes),
        "current_group": float(current_group_bytes),
        "q2t": float(q2t_bytes),
        "scales": float(scale_bytes),
        "logical": float(logical_bytes),
        "bdot": float(bdot_bytes),
        "physical": float(physical_bytes),
    }


def annotate(row: Dict[str, str], freq_hz: float) -> Dict[str, str]:
    out = dict(row)
    group_size = parse_int(out.get("kv_group_size")) or 32
    prefill_len = parse_int(out.get("prefill_len"))
    decode_len = parse_int(out.get("decode_context_len"))
    case_name = out.get("attention_case", "")

    prefill_pos = parse_int(out.get("prefill_attention_pos"))
    if prefill_pos is None:
        prefill_pos = compute_position(prefill_len, group_size, case_name)
    decode_pos = parse_int(out.get("decode_attention_pos"))
    if decode_pos is None:
        decode_pos = compute_position(decode_len, group_size, case_name)

    out["merge_success"] = "1" if is_success(out) else "0"
    out["merge_source_csv"] = out.pop("_source_csv", "")
    out["clock_mhz"] = fmt_float(freq_hz / 1_000_000.0, 3)
    out["actual_kv_group_size"] = str(group_size)
    out["actual_prefill_position"] = "" if prefill_pos is None else str(prefill_pos)
    out["actual_decode_position"] = "" if decode_pos is None else str(decode_pos)
    out["actual_prefill_case_note"] = case_description(case_name, prefill_pos, group_size)
    out["actual_decode_case_note"] = case_description(case_name, decode_pos, group_size)

    prefill_cycles = parse_int(out.get("prefill_per_token_cycles"))
    decode_cycles = parse_int(out.get("decode_per_token_cycles"))
    total_cycles = parse_int(out.get("primary_cycles"))
    decode_kv_cycles = parse_int(out.get("decode_per_token_kv_quant_cycles")) or 0
    decode_steps = parse_int(out.get("decode_steps")) or 0
    decode_kv_events = parse_int(out.get("decode_kv_quant_events")) or 0
    decode_kv_amort_tokens = group_size if group_size > 0 else 32
    decode_kv_group_amort_cycles: Optional[float] = None
    decode_group_amort_cycles: Optional[float] = None
    if decode_cycles is not None:
        if decode_kv_cycles > 0 and decode_steps > 0 and decode_kv_events > 0:
            kv_cycles_per_event = (float(decode_kv_cycles) * float(decode_steps)) / float(decode_kv_events)
            decode_kv_group_amort_cycles = kv_cycles_per_event / float(decode_kv_amort_tokens)
            decode_group_amort_cycles = float(decode_cycles) - float(decode_kv_cycles) + decode_kv_group_amort_cycles
        else:
            decode_kv_group_amort_cycles = float(decode_kv_cycles)
            decode_group_amort_cycles = float(decode_cycles)
    prefill_tps = tokens_per_second(prefill_cycles, freq_hz)
    decode_tps = tokens_per_second(decode_cycles, freq_hz)
    decode_group_amort_tps = tokens_per_second(decode_group_amort_cycles, freq_hz)
    total_tps = tokens_per_second(total_cycles, freq_hz)
    out["prefill_tokens_per_sec_500mhz"] = fmt_float(prefill_tps, 6)
    out["decode_tokens_per_sec_500mhz"] = fmt_float(decode_tps, 6)
    out["decode_kv_quant_amortization_tokens"] = str(decode_kv_amort_tokens)
    out["decode_per_token_kv_quant_cycles_group_amortized"] = fmt_float(decode_kv_group_amort_cycles, 6)
    out["decode_per_token_cycles_group_amortized"] = fmt_float(decode_group_amort_cycles, 6)
    out["decode_tokens_per_sec_500mhz_group_amortized"] = fmt_float(decode_group_amort_tps, 6)
    out["total_single_token_per_sec_500mhz"] = fmt_float(total_tps, 6)
    out["prefill_ms_per_token_500mhz"] = fmt_float((prefill_cycles / freq_hz * 1000.0) if prefill_cycles else None, 6)
    out["decode_ms_per_token_500mhz"] = fmt_float((decode_cycles / freq_hz * 1000.0) if decode_cycles else None, 6)
    out["decode_ms_per_token_500mhz_group_amortized"] = fmt_float((decode_group_amort_cycles / freq_hz * 1000.0) if decode_group_amort_cycles else None, 6)
    out["total_ms_500mhz"] = fmt_float((total_cycles / freq_hz * 1000.0) if total_cycles else None, 6)

    cache = kivi_cache_bytes(out, group_size)
    baseline_bytes = cache.get("baseline")
    logical_bytes = cache.get("logical")
    physical_bytes = cache.get("physical")
    out["kv_cache_baseline_fp32_bytes"] = fmt_float(baseline_bytes, 0)
    out["kv_cache_kivi_current_group_bytes"] = fmt_float(cache.get("current_group"), 0)
    out["kv_cache_kivi_q2t_bytes"] = fmt_float(cache.get("q2t"), 0)
    out["kv_cache_kivi_scale_bytes"] = fmt_float(cache.get("scales"), 0)
    out["kv_cache_kivi_logical_bytes"] = fmt_float(logical_bytes, 0)
    out["kv_cache_kivi_bdot_extra_bytes"] = fmt_float(cache.get("bdot"), 0)
    out["kv_cache_kivi_physical_bncfu_bytes"] = fmt_float(physical_bytes, 0)
    out["kv_cache_kivi_logical_compression_ratio"] = fmt_float((baseline_bytes / logical_bytes) if baseline_bytes and logical_bytes else None, 6)
    out["kv_cache_kivi_physical_bncfu_compression_ratio"] = fmt_float((baseline_bytes / physical_bytes) if baseline_bytes and physical_bytes else None, 6)
    return out


def merge_rows(main_rows: Sequence[Dict[str, str]], backfill_rows: Sequence[Dict[str, str]]) -> List[Dict[str, str]]:
    merged: Dict[Tuple[str, ...], Dict[str, str]] = {}
    order: List[Tuple[str, ...]] = []
    for row in main_rows:
        k = key(row)
        if k not in merged:
            order.append(k)
        row = dict(row)
        row["_merge_origin"] = "main"
        merged[k] = row

    for row in backfill_rows:
        if not is_success(row):
            continue
        k = key(row)
        row = dict(row)
        row["_merge_origin"] = "backfill"
        if k not in merged:
            order.append(k)
            merged[k] = row
            continue
        if not is_success(merged[k]):
            merged[k] = row

    for k, row in list(merged.items()):
        row.setdefault("_merge_origin", "main")
        if row.get("_merge_origin") == "backfill":
            row["merged_from_backfill"] = "1"
        else:
            row["merged_from_backfill"] = "0"

    return [merged[k] for k in order]


def union_fields(rows: Sequence[Dict[str, str]]) -> List[str]:
    preferred = [
        "model",
        "software_corner",
        "attention_case",
        "actual_kv_group_size",
        "actual_prefill_position",
        "actual_decode_position",
        "actual_prefill_case_note",
        "actual_decode_case_note",
        "ablation_path",
        "hardware",
        "variant",
        "build_rc",
        "sim_rc",
        "merge_success",
        "merged_from_backfill",
        "primary_cycles",
        "prefill_per_token_cycles",
        "decode_per_token_cycles",
        "prefill_tokens_per_sec_500mhz",
        "decode_tokens_per_sec_500mhz",
        "decode_tokens_per_sec_500mhz_group_amortized",
        "prefill_ms_per_token_500mhz",
        "decode_ms_per_token_500mhz",
        "decode_ms_per_token_500mhz_group_amortized",
        "decode_per_token_cycles_group_amortized",
        "decode_per_token_kv_quant_cycles_group_amortized",
        "decode_kv_quant_amortization_tokens",
        "kv_cache_baseline_fp32_bytes",
        "kv_cache_kivi_logical_bytes",
        "kv_cache_kivi_physical_bncfu_bytes",
        "kv_cache_kivi_logical_compression_ratio",
        "kv_cache_kivi_physical_bncfu_compression_ratio",
        "total_ms_500mhz",
    ]
    fields: List[str] = []
    for name in preferred:
        if any(name in row for row in rows):
            fields.append(name)
    for row in rows:
        for name in row:
            if name not in fields and not name.startswith("_"):
                fields.append(name)
    return fields


def write_csv(rows: Sequence[Dict[str, str]], path: Path) -> None:
    fields = union_fields(rows)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_json(rows: Sequence[Dict[str, str]], path: Path) -> None:
    path.write_text(json.dumps(rows, indent=2), encoding="utf-8")


def row_sort_key(row: Dict[str, str]) -> Tuple[str, int, str, str]:
    length = parse_int(row.get("prefill_len")) or 0
    return (row.get("model", ""), length, row.get("attention_case", ""), row.get("ablation_path", ""))


def observed_lengths_and_groups(rows: Sequence[Dict[str, str]]) -> List[Tuple[int, int]]:
    by_length: Dict[int, int] = {}
    for row in rows:
        length = parse_int(row.get("prefill_len"))
        if length is None:
            continue
        group_size = parse_int(row.get("actual_kv_group_size")) or parse_int(row.get("kv_group_size")) or 32
        by_length.setdefault(length, group_size)
    return sorted(by_length.items())


def write_summary(rows: Sequence[Dict[str, str]], path: Path, freq_hz: float, main_dir: Path, backfill_dirs: Sequence[Path]) -> None:
    lines: List[str] = [
        "# Merged LLaMa Ablation Results",
        "",
        f"- Main run: `{main_dir}`",
        "- Backfill runs:",
    ]
    lines.extend(f"  - `{p}`" for p in backfill_dirs)
    lines.extend(
        [
            f"- Clock for throughput: `{freq_hz / 1_000_000.0:.3f} MHz`",
            f"- Rows: `{len(rows)}`; successful rows: `{sum(1 for row in rows if row.get('merge_success') == '1')}`; backfilled rows: `{sum(1 for row in rows if row.get('merged_from_backfill') == '1')}`",
            "",
            "## Case Position Map",
            "",
            "| length | case | position | packed groups | current float tokens |",
            "|---:|---|---:|---:|---:|",
        ]
    )
    for length, group_size in observed_lengths_and_groups(rows):
        for case_name in ("best", "mid", "worst"):
            pos = compute_position(length, group_size, case_name)
            if pos is None:
                continue
            lines.append(f"| {length} | `{case_name}` | {pos} | {pos // group_size} | {(pos % group_size) + 1} |")

    lines.extend(
        [
            "",
            "## Successful Rows",
            "",
            "| model | corner | path | total cycles | prefill tok/s | decode tok/s | prefill pos | decode pos | source |",
            "|---|---|---|---:|---:|---:|---:|---:|---|",
        ]
    )
    for row in sorted((r for r in rows if r.get("merge_success") == "1"), key=row_sort_key):
        source = "backfill" if row.get("merged_from_backfill") == "1" else "main"
        lines.append(
            "| {model} | {corner} | {path} | {total} | {prefill_tps} | {decode_tps} | {prefill_pos} | {decode_pos} | {source} |".format(
                model=row.get("model", ""),
                corner=row.get("software_corner", ""),
                path=row.get("ablation_path", ""),
                total=row.get("primary_cycles", ""),
                prefill_tps=row.get("prefill_tokens_per_sec_500mhz", ""),
                decode_tps=row.get("decode_tokens_per_sec_500mhz", ""),
                prefill_pos=row.get("actual_prefill_position", ""),
                decode_pos=row.get("actual_decode_position", ""),
                source=source,
            )
        )

    lines.extend(
        [
            "",
            "## Decode Throughput With Group-Amortized KV Packing",
            "",
            "| model | corner | path | raw decode tok/s | group-amortized decode tok/s | amortized kv cycles/token | source |",
            "|---|---|---|---:|---:|---:|---|",
        ]
    )
    for row in sorted((r for r in rows if r.get("merge_success") == "1"), key=row_sort_key):
        source = "backfill" if row.get("merged_from_backfill") == "1" else "main"
        lines.append(
            "| {model} | {corner} | {path} | {raw} | {amort} | {kv_amort} | {source} |".format(
                model=row.get("model", ""),
                corner=row.get("software_corner", ""),
                path=row.get("ablation_path", ""),
                raw=row.get("decode_tokens_per_sec_500mhz", ""),
                amort=row.get("decode_tokens_per_sec_500mhz_group_amortized", ""),
                kv_amort=row.get("decode_per_token_kv_quant_cycles_group_amortized", ""),
                source=source,
            )
        )

    lines.extend(
        [
            "",
            "## KV Cache Compression",
            "",
            "| model | baseline FP32 bytes | KIVI logical bytes | logical compression | KIVI BNCFU physical bytes | physical compression |",
            "|---|---:|---:|---:|---:|---:|",
        ]
    )
    seen_models = set()
    for row in sorted(rows, key=lambda r: r.get("model", "")):
        model = row.get("model", "")
        if not model or model in seen_models:
            continue
        seen_models.add(model)
        lines.append(
            "| {model} | {baseline} | {logical} | {logical_ratio} | {physical} | {physical_ratio} |".format(
                model=model,
                baseline=row.get("kv_cache_baseline_fp32_bytes", ""),
                logical=row.get("kv_cache_kivi_logical_bytes", ""),
                logical_ratio=row.get("kv_cache_kivi_logical_compression_ratio", ""),
                physical=row.get("kv_cache_kivi_physical_bncfu_bytes", ""),
                physical_ratio=row.get("kv_cache_kivi_physical_bncfu_compression_ratio", ""),
            )
        )

    failed = [row for row in rows if row.get("merge_success") != "1"]
    if failed:
        lines.extend(["", "## Remaining Failed Rows", "", "| model | corner | path | build rc | sim rc | log |", "|---|---|---|---:|---:|---|"])
        for row in failed:
            lines.append(
                f"| {row.get('model', '')} | {row.get('software_corner', '')} | {row.get('ablation_path', '')} | {row.get('build_rc', '')} | {row.get('sim_rc', '')} | `{row.get('log', '')}` |"
            )

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main-dir", type=Path, default=DEFAULT_MAIN, help="Original run directory or results.csv.")
    parser.add_argument(
        "--backfill-dir",
        type=Path,
        action="append",
        default=[DEFAULT_BACKFILL],
        help="Backfill directory or results.csv. Can be passed multiple times.",
    )
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--clock-mhz", type=float, default=500.0)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    main_rows = read_result_tree(args.main_dir)
    backfill_rows: List[Dict[str, str]] = []
    for backfill_dir in args.backfill_dir:
        backfill_rows.extend(read_result_tree(backfill_dir))

    merged = merge_rows(main_rows, backfill_rows)
    freq_hz = args.clock_mhz * 1_000_000.0
    annotated = [annotate(row, freq_hz) for row in merged]

    args.out_dir.mkdir(parents=True, exist_ok=True)
    write_csv(annotated, args.out_dir / "merged_results.csv")
    write_json(annotated, args.out_dir / "merged_results.json")
    write_summary(annotated, args.out_dir / "summary.md", freq_hz, args.main_dir, args.backfill_dir)

    print(f"Read {len(main_rows)} main rows and {len(backfill_rows)} backfill rows.")
    print(f"Merged rows: {len(annotated)}")
    print(f"Successful rows: {sum(1 for row in annotated if row.get('merge_success') == '1')}")
    print(f"Backfilled rows: {sum(1 for row in annotated if row.get('merged_from_backfill') == '1')}")
    print(f"Wrote {args.out_dir / 'merged_results.csv'}")
    print(f"Wrote {args.out_dir / 'summary.md'}")


if __name__ == "__main__":
    main()
