#!/usr/bin/env python3
"""Fail when a MyCinema source tree or release JAR contains forbidden dependencies."""

from __future__ import annotations

import argparse
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_JARS = {PurePosixPath("gradle/wrapper/gradle-wrapper.jar")}
FORBIDDEN_ARCHIVE_PREFIXES = (
    "me/srrapero720/waterframes/",
    "me/srrapero720/waterui/",
    "org/watermedia/",
    "net/irisshaders/",
    "net/caffeinemc/mods/sodium/",
    "net/createmod/ponder/",
    "dev/engine_room/flywheel/",
)
FORBIDDEN_CONFIG_TEXT = (
    "private-recovery",
    "watermedia-3.0.0.23-private",
    "implementation files(",
    "runtimeonly files(",
    "filetree(dir:'libs'",
    'filetree(dir:"libs"',
)
CONFIG_FILES = (
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
    "src/main/resources/META-INF/neoforge.mods.toml",
)


def tracked_files() -> list[PurePosixPath]:
    try:
        output = subprocess.check_output(
            ["git", "-C", str(ROOT), "ls-files", "-z"], stderr=subprocess.DEVNULL
        )
    except (OSError, subprocess.CalledProcessError):
        return [
            PurePosixPath(path.relative_to(ROOT).as_posix())
            for path in ROOT.rglob("*")
            if path.is_file() and ".git" not in path.parts
        ]
    return [PurePosixPath(part.decode("utf-8")) for part in output.split(b"\0") if part]


def scan_repository() -> list[str]:
    errors: list[str] = []
    paths = tracked_files()

    for path in paths:
        lower = path.as_posix().lower()
        if lower.endswith(".jar") and path not in ALLOWED_JARS:
            errors.append(f"tracked JAR is forbidden: {path}")
        if lower.endswith((".dll", ".so", ".dylib", ".exe", ".class")):
            errors.append(f"tracked compiled/native file is forbidden: {path}")
        if any(part.lower() in {"me", "org", "net"} for part in path.parts):
            source = path.as_posix()
            if source.startswith("src/main/java/me/srrapero720/"):
                errors.append(f"copied WaterFrames/WaterUI namespace: {path}")
            if source.startswith("src/main/java/org/watermedia/"):
                errors.append(f"copied WaterMedia namespace: {path}")

    for relative in CONFIG_FILES:
        path = ROOT / relative
        if not path.exists():
            errors.append(f"required configuration missing: {relative}")
            continue
        text = path.read_text(encoding="utf-8").lower()
        for forbidden in FORBIDDEN_CONFIG_TEXT:
            if forbidden in text:
                errors.append(f"forbidden private/bundled dependency reference in {relative}: {forbidden}")

    build = (ROOT / "build.gradle").read_text(encoding="utf-8")
    for coordinate in (
        "maven.modrinth:eBzFuVTM:3q7i6sG3",
        "maven.modrinth:G922NeHS:7aLfBD61",
        "maven.modrinth:4997XcoK:IvgVb1Pv",
        "net.createmod.ponder:ponder-neoforge:1.0.87+mc1.21.1",
    ):
        if coordinate not in build:
            errors.append(f"pinned official dependency missing: {coordinate}")

    for required in ("LICENSE", "NOTICE", "THIRD_PARTY.md", "docs/OPEN_SOURCE_AUDIT.md"):
        if not (ROOT / required).is_file():
            errors.append(f"required publication document missing: {required}")
    return errors


def scan_jar(path: Path) -> list[str]:
    errors: list[str] = []
    if not path.is_file():
        return [f"artifact not found: {path}"]
    try:
        with zipfile.ZipFile(path) as archive:
            names = [name.replace("\\", "/") for name in archive.namelist()]
    except zipfile.BadZipFile:
        return [f"artifact is not a readable JAR/ZIP: {path}"]

    for name in names:
        lower = name.lower()
        if lower.endswith(".jar"):
            errors.append(f"nested JAR is forbidden in {path.name}: {name}")
        if lower.endswith(".class") and name.startswith(FORBIDDEN_ARCHIVE_PREFIXES):
            errors.append(f"third-party class is forbidden in {path.name}: {name}")
    for required in ("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/THIRD_PARTY.md"):
        if required not in names:
            errors.append(f"publication notice missing from {path.name}: {required}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts", nargs="*", type=Path, help="optional built JARs to inspect")
    args = parser.parse_args()

    errors = scan_repository()
    for artifact in args.artifacts:
        errors.extend(scan_jar(artifact.resolve()))

    if errors:
        print("MyCinema release compliance: FAILED", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"MyCinema release compliance: PASS ({len(tracked_files())} repository files checked)")
    if args.artifacts:
        print(f"Artifacts checked: {len(args.artifacts)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
