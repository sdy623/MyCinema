#!/usr/bin/env python3
"""Require the reviewed runtime GameTest case count and an unambiguous all-pass footer."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


START = re.compile(r"\b(\d+) tests are now running\b")
COMPLETE = re.compile(r"========= (\d+) GAME TESTS COMPLETE IN")
PASSED = re.compile(r"\bAll (\d+) required tests passed\b")


def inspect(text: str, expected: int) -> dict[str, object]:
    starts = [int(value) for value in START.findall(text)]
    completes = [int(value) for value in COMPLETE.findall(text)]
    passes = [int(value) for value in PASSED.findall(text)]
    failures = re.findall(r"LogTestReporter.* failed at ", text)
    ok = starts == [expected] and completes == [expected] and passes == [expected] and not failures
    return {
        "success": ok,
        "expectedCases": expected,
        "started": starts,
        "completed": completes,
        "passed": passes,
        "reportedFailures": len(failures),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--expected", type=int, default=241)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    if not args.log.is_file():
        raise SystemExit(f"GameTest log missing: {args.log}")
    result = inspect(args.log.read_text(encoding="utf-8", errors="replace"), args.expected)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("MyCinema runtime GameTests: " + ("PASS" if result["success"] else "FAIL"))
    print(json.dumps(result, sort_keys=True))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
