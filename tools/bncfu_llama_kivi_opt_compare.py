#!/usr/bin/env python3
import argparse
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import List


VEXII_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = VEXII_ROOT.parents[1]
TOOLS = VEXII_ROOT / "tools"
OUT_ROOT = VEXII_ROOT / "benchmark_results"


def run(cmd: List[str]) -> int:
    print("+ " + " ".join(cmd), flush=True)
    return subprocess.call(cmd, cwd=REPO_ROOT)


def llama_cmd(args: argparse.Namespace, out_dir: Path, case_name: str) -> List[str]:
    return [
        sys.executable,
        str(TOOLS / "bncfu_e2e_llama_benchmark.py"),
        "--models",
        args.models,
        "--hardware-preset",
        "ablation_large",
        "--compare-mode",
        "ablation",
        "--variants",
        "fp32_kv,kivi_bncfu",
        "--prefill-len",
        str(args.seq_len),
        "--decode-context-len",
        str(args.seq_len),
        "--decode-steps",
        str(args.decode_steps),
        "--profile-repeats",
        str(args.profile_repeats),
        "--case",
        case_name,
        "--out-dir",
        str(out_dir / case_name),
        "--keep-going",
        *(("--force-build",) if args.force_build else ()),
        *(("--force-sim",) if args.force_sim else ()),
    ]


def operator_cmd(args: argparse.Namespace, out_dir: Path) -> List[str]:
    return [
        sys.executable,
        str(TOOLS / "bncfu_perf_explore.py"),
        "--hardware-preset",
        "ablation_large",
        "--custom-only",
        "--llama-shape",
        f"{args.seq_len},{args.heads},{args.kv_heads},{args.head_size},{args.pos}",
        "--llama-repeats",
        str(args.repeats),
        "--out-dir",
        str(out_dir / "operator"),
        "--only-hardware",
        "baseline_fpu",
        "--only-hardware",
        "bncfu_256_quant64",
        "--force-build",
        "--force-sim",
        "--no-plot",
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Fixed ablation_large LLaMa KIVI BNCFU optimization comparison.")
    parser.add_argument("--models", default="llama3m_w2a8,llama28m_w2a8")
    parser.add_argument("--seq-len", type=int, default=64)
    parser.add_argument("--heads", type=int, default=8)
    parser.add_argument("--kv-heads", type=int, default=8)
    parser.add_argument("--head-size", type=int, default=32)
    parser.add_argument("--pos", type=int, default=47)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--decode-steps", type=int, default=1)
    parser.add_argument("--profile-repeats", type=int, default=1)
    parser.add_argument("--case", default="mid", choices=["best", "mid", "worst", "all"])
    parser.add_argument("--suite", default="operator_seq64_mid")
    parser.add_argument("--mode", default="operator", choices=["operator", "e2e"])
    parser.add_argument("--sync", action="store_true", help="Apply project -> hw/VexiiMico/sw source sync before running.")
    parser.add_argument("--force-build", action="store_true")
    parser.add_argument("--force-sim", action="store_true")
    args = parser.parse_args()

    if args.sync:
        rc = run([sys.executable, str(TOOLS / "sync_sw_from_project.py"), "--apply"])
        if rc != 0:
            raise SystemExit(rc)
    else:
        rc = run([sys.executable, str(TOOLS / "sync_sw_from_project.py"), "--check"])
        if rc != 0:
            print("Source drift detected. Re-run with --sync to update hw/VexiiMico/sw.", file=sys.stderr)
            raise SystemExit(rc)

    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = OUT_ROOT / f"llama_kivi_opt_{args.suite}_{stamp}"
    if args.mode == "operator":
        rc = run(operator_cmd(args, out_dir))
        if rc != 0:
            raise SystemExit(rc)
    else:
        cases = ["best", "mid", "worst"] if args.case == "all" else [args.case]
        for case_name in cases:
            rc = run(llama_cmd(args, out_dir, case_name))
            if rc != 0:
                raise SystemExit(rc)
    print(f"Wrote results under {out_dir}")


if __name__ == "__main__":
    main()
