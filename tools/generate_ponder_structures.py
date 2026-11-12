#!/usr/bin/env python3
"""Generate the small, deterministic structure blueprints used by MyCinema Ponder scenes."""

from __future__ import annotations

import argparse
import gzip
import io
import struct
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/mycinema/ponder/tutorial"
DATA_VERSION = 3955
SIZE = (7, 4, 7)


def state(name: str, **properties: str) -> tuple[str, tuple[tuple[str, str], ...]]:
    return name, tuple(sorted(properties.items()))


FLOOR = [(x, 0, z, state("minecraft:smooth_stone")) for x in range(7) for z in range(7)]
SCENES: dict[str, list[tuple[int, int, int, tuple[str, tuple[tuple[str, str], ...]]]]] = {
    "manager_terminal": FLOOR + [
        (3, 1, 3, state("mycinema:manager_terminal")),
        (1, 1, 3, state("mycinema:tms_terminal")),
        (5, 1, 3, state("mycinema:information_panel")),
    ],
    "tms_terminal": FLOOR + [
        (3, 1, 3, state("mycinema:tms_terminal")),
        (1, 1, 1, state("mycinema:cinema_projector")),
        (5, 1, 1, state("mycinema:cinema_projector")),
    ],
    "cinema_projector": FLOOR + [
        (3, 1, 2, state("mycinema:cinema_projector")),
        (3, 1, 5, state("mycinema:tms_terminal")),
    ],
    "imax_gt_projector": FLOOR + [
        (3, 1, 2, state("mycinema:imax_gt_projector")),
        (3, 1, 5, state("mycinema:tms_terminal")),
    ],
    "information_panel": FLOOR + [
        (2, 1, 3, state("mycinema:manager_terminal")),
        (4, 1, 3, state("mycinema:information_panel")),
    ],
    "seat_guide": FLOOR + [
        (3, 1, 1, state("mycinema:seat_guide", facing="south", floor="true")),
        *[(x, 1, z, state("mycinema:cinema_seat", facing="north")) for z in (4, 5) for x in range(1, 6)],
    ],
    "staff_reader": FLOOR + [
        (2, 1, 3, state("mycinema:staff_reader", facing="east")),
        (4, 1, 3, state("mycinema:cinema_door_metal", facing="north", hinge="left", open="false", part="lower", half_drop="false", powered="false", access="staff_front")),
        (4, 2, 3, state("mycinema:cinema_door_metal", facing="north", hinge="left", open="false", part="middle", half_drop="false", powered="false", access="staff_front")),
        (4, 3, 3, state("mycinema:cinema_door_metal", facing="north", hinge="left", open="false", part="upper", half_drop="false", powered="false", access="staff_front")),
    ],
}


def utf(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def payload(tag_type: int, value: Any) -> bytes:
    if tag_type == 3:
        return struct.pack(">i", value)
    if tag_type == 8:
        return utf(value)
    if tag_type == 9:
        element_type, values = value
        return bytes([element_type]) + struct.pack(">i", len(values)) + b"".join(payload(element_type, item) for item in values)
    if tag_type == 10:
        return compound(value)
    raise ValueError(f"unsupported tag type {tag_type}")


def named(name: str, tag_type: int, value: Any) -> bytes:
    return bytes([tag_type]) + utf(name) + payload(tag_type, value)


def compound(entries: list[tuple[str, int, Any]]) -> bytes:
    return b"".join(named(name, tag_type, value) for name, tag_type, value in entries) + b"\x00"


def structure_bytes(blocks: list[tuple[int, int, int, tuple[str, tuple[tuple[str, str], ...]]]]) -> bytes:
    palette: list[tuple[str, tuple[tuple[str, str], ...]]] = []
    for _x, _y, _z, block_state in blocks:
        if block_state not in palette:
            palette.append(block_state)

    palette_tags = []
    for name, properties in palette:
        fields: list[tuple[str, int, Any]] = [("Name", 8, name)]
        if properties:
            fields.append(("Properties", 10, [(key, 8, value) for key, value in properties]))
        palette_tags.append(fields)

    block_tags = [
        [("pos", 9, (3, [x, y, z])), ("state", 3, palette.index(block_state))]
        for x, y, z, block_state in blocks
    ]
    root = compound([
        ("DataVersion", 3, DATA_VERSION),
        ("size", 9, (3, list(SIZE))),
        ("palette", 9, (10, palette_tags)),
        ("blocks", 9, (10, block_tags)),
        ("entities", 9, (0, [])),
    ])
    uncompressed = b"\x0a\x00\x00" + root
    target = io.BytesIO()
    with gzip.GzipFile(fileobj=target, mode="wb", filename="", mtime=0) as stream:
        stream.write(uncompressed)
    return target.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify committed resources without rewriting them")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    changed: list[str] = []
    for scene, blocks in SCENES.items():
        target = OUTPUT / f"{scene}.nbt"
        expected = structure_bytes(blocks)
        actual = target.read_bytes() if target.is_file() else None
        if actual != expected:
            changed.append(scene)
            if not args.check:
                target.write_bytes(expected)
    if args.check and changed:
        raise SystemExit("Ponder structure resources are missing or stale: " + ", ".join(changed))
    print(("verified" if args.check else "generated") + f" {len(SCENES)} Ponder structures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
