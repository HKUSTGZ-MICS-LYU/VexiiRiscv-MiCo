#!/usr/bin/env python3
import argparse
import filecmp
import shutil
from pathlib import Path
from typing import Iterable, List, Sequence, Tuple


VEXII_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = VEXII_ROOT.parents[1]
PROJECT = REPO_ROOT / "project"
SW = VEXII_ROOT / "sw"

ROOT_FILES = (
    "Makefile",
    "llama2.c",
    "llama2_benchmark.c",
    "llama2_config.h",
    "llama2_perf_est.c",
)

SYNC_DIRS = (
    "tests",
    "MiCo-Lib/include",
    "MiCo-Lib/src",
    "MiCo-Lib/targets",
    "MiCo-Lib/test",
)

SOURCE_SUFFIXES = (".c", ".h", ".mk", ".S", ".s")


def iter_sync_files(src_root: Path) -> Iterable[Path]:
    for name in ROOT_FILES:
        path = src_root / name
        if path.exists():
            yield path
    for rel_dir in SYNC_DIRS:
        root = src_root / rel_dir
        if not root.exists():
            continue
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.suffix in SOURCE_SUFFIXES:
                yield path


def relative_files(root: Path) -> List[Path]:
    return sorted(path.relative_to(root) for path in iter_sync_files(root))


def compare(src_root: Path, dst_root: Path) -> Tuple[List[Path], List[Path], List[Path]]:
    changed: List[Path] = []
    missing: List[Path] = []
    stale: List[Path] = []
    src_files = set(relative_files(src_root))
    dst_files = set(relative_files(dst_root))
    for rel in sorted(src_files):
        src = src_root / rel
        dst = dst_root / rel
        if not dst.exists():
            missing.append(rel)
        elif not filecmp.cmp(src, dst, shallow=False):
            changed.append(rel)
    for rel in sorted(dst_files - src_files):
        stale.append(rel)
    return changed, missing, stale


def copy_files(src_root: Path, dst_root: Path, rels: Sequence[Path]) -> None:
    for rel in rels:
        src = src_root / rel
        dst = dst_root / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def print_diff(changed: Sequence[Path], missing: Sequence[Path], stale: Sequence[Path]) -> None:
    for label, rels in (("changed", changed), ("missing", missing), ("stale", stale)):
        if not rels:
            continue
        print(f"{label}:")
        for rel in rels:
            print(f"  {rel}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Synchronize Vexii sw source files from project.")
    parser.add_argument("--check", action="store_true", help="Only report drift and return non-zero if drift exists.")
    parser.add_argument("--apply", action="store_true", help="Copy source files from project into hw/VexiiMico/sw.")
    parser.add_argument("--reverse", action="store_true", help="Use hw/VexiiMico/sw as source and project as destination.")
    args = parser.parse_args()

    if args.check == args.apply:
        raise SystemExit("Choose exactly one of --check or --apply")

    src_root = SW if args.reverse else PROJECT
    dst_root = PROJECT if args.reverse else SW
    changed, missing, stale = compare(src_root, dst_root)
    print_diff(changed, missing, stale)

    if args.apply:
        copy_files(src_root, dst_root, [*changed, *missing])
        if changed or missing:
            print(f"synced {len(changed) + len(missing)} files from {src_root} to {dst_root}")
        else:
            print("already in sync")
    elif changed or missing or stale:
        raise SystemExit(1)
    else:
        print("in sync")


if __name__ == "__main__":
    main()
