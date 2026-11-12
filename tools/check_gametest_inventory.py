#!/usr/bin/env python3
"""Fail when the reviewed NeoForge GameTest class inventory drifts."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Iterable


DEFAULT_MANIFEST = Path("tools/gametest-inventory.txt")


def _normalized(values: Iterable[str]) -> tuple[str, ...]:
    return tuple(sorted({value.strip().replace("\\", "/") for value in values if value.strip()}))


def discover(root: Path) -> tuple[str, ...]:
    source = root / "src" / "main" / "java"
    if not source.is_dir():
        raise ValueError(f"GameTest source directory is missing: {source}")
    return _normalized(path.relative_to(root).as_posix() for path in source.rglob("*GameTests.java"))


def read_manifest(path: Path) -> tuple[str, ...]:
    if not path.is_file():
        raise ValueError(f"GameTest inventory manifest is missing: {path}")
    return _normalized(
        line for line in path.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("#")
    )


def compare(expected: Iterable[str], actual: Iterable[str]) -> tuple[tuple[str, ...], tuple[str, ...]]:
    expected_set = set(_normalized(expected))
    actual_set = set(_normalized(actual))
    return tuple(sorted(expected_set - actual_set)), tuple(sorted(actual_set - expected_set))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    manifest = (args.manifest or (root / DEFAULT_MANIFEST)).resolve()
    expected = read_manifest(manifest)
    actual = discover(root)
    missing, unexpected = compare(expected, actual)

    if missing or unexpected:
        print("MyCinema GameTest inventory: FAIL")
        for path in missing:
            print(f"  missing: {path}")
        for path in unexpected:
            print(f"  unreviewed: {path}")
        print(f"  expected={len(expected)} actual={len(actual)}")
        return 1

    print(f"MyCinema GameTest inventory: PASS ({len(actual)} classes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
