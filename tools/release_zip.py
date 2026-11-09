#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the release zip, build/fractal-jdtls-<release>.zip (design §2).

It holds this project's files only, under one top folder ``fractal-jdtls-<release>/``:
``bin/install.py``, ``bin/jdtls``, the agent, boot and endpoint jars from ``mise run build``
in ``lib/``, ``pin.json``, ``LICENSE`` and ``NOTICE``, and ``release-files.json``, which
records the release ID and the SHA-256 of every other file in the zip (design §4). The
release ID is pin.json's ``release``. The zip is reproducible: entries are sorted, and every entry has the root
POM's ``project.build.outputTimestamp`` and a fixed mode.

Development tooling, not shipped. Run it as ``mise run release:zip``, which builds the
jars first.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import importlib.util
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Where each file of the release comes from in the repository (design §2).
SOURCES = {
    "bin/install.py": "bin/install.py",
    "bin/jdtls": "bin/jdtls",
    "lib/fractal-jdtls-agent.jar": "agent/agent/target/fractal-jdtls-agent.jar",
    "lib/fractal-jdtls-boot.jar": "agent/boot/target/fractal-jdtls-boot.jar",
    "lib/fractal-jdtls-endpoint.jar": "endpoint/target/fractal-jdtls-endpoint.jar",
    "lib/http-mode-preferences.ini": "endpoint/src/main/preferences/http-mode-preferences.ini",
    "pin.json": "pin.json",
    "LICENSE": "LICENSE",
    "NOTICE": "NOTICE",
}


class ZipError(Exception):
    pass


def installer(root: Path):
    """The installer, for its lists of the release's files, so the two can't drift."""
    spec = importlib.util.spec_from_file_location("fractal_jdtls_install", root / "bin" / "install.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def release_files_json(release: str, contents: dict[str, bytes]) -> bytes:
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(contents.items())}
    return (json.dumps({"release": release, "files": hashes}, indent=2) + "\n").encode("utf-8")


def zip_timestamp(root: Path) -> tuple[int, int, int, int, int, int]:
    value = ET.parse(root / "pom.xml").getroot().findtext("m:properties/m:project.build.outputTimestamp", namespaces=POM_NS)
    if not value:
        raise ZipError("the root pom.xml has no project.build.outputTimestamp property")
    stamp = datetime.datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    return stamp.timetuple()[:6]


def class_path(jar: Path) -> list[str]:
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    joined = re.sub(r"\r?\n ", "", manifest)  # manifest continuation lines
    for line in joined.splitlines():
        if line.startswith("Class-Path:"):
            return line.split(":", 1)[1].split()
    return []


def build(root: Path, output_dir: Path) -> Path:
    pin = json.loads((root / "pin.json").read_text(encoding="utf-8"))
    release = pin["release"]
    install = installer(root)
    if set(install.HASHED_FILES) != set(SOURCES):
        raise ZipError(f"bin/install.py's HASHED_FILES {sorted(install.HASHED_FILES)} don't match this tool's {sorted(SOURCES)}")
    missing = [source for source in SOURCES.values() if not (root / source).is_file()]
    if missing:
        raise ZipError(f"missing {', '.join(missing)}; run `mise run build` first (mise run release:zip does)")

    byte_buddy = f"byte-buddy-{pin['byte_buddy']['version']}.jar"
    agent_class_path = class_path(root / SOURCES["lib/fractal-jdtls-agent.jar"])
    if byte_buddy not in agent_class_path:
        raise ZipError(
            f"the agent jar's Class-Path is {agent_class_path}, but pin.json pins {byte_buddy}; "
            "rebuild the agent (mise run build) or rerun tools/pin.py"
        )

    top = f"fractal-jdtls-{release}"
    stamp = zip_timestamp(root)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"{top}.zip"
    partial = target.with_name(target.name + ".part")
    contents = {name: (root / source).read_bytes() for name, source in SOURCES.items()}
    contents[install.RELEASE_FILES_JSON] = release_files_json(release, contents)
    folders = sorted({f"{top}/"} | {f"{top}/{name.rsplit('/', 1)[0]}/" for name in contents if "/" in name})
    with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for folder in folders:
            info = zipfile.ZipInfo(folder, stamp)
            info.external_attr = (0o40755 << 16) | 0x10
            archive.writestr(info, b"")
        for name in sorted(contents):
            info = zipfile.ZipInfo(f"{top}/{name}", stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name in install.EXECUTABLES else 0o100644) << 16
            archive.writestr(info, contents[name])
    partial.replace(target)
    return target


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="release_zip.py", description=__doc__.splitlines()[0])
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build", help="where to write the zip (default: build/)")
    args = parser.parse_args(argv)
    try:
        target = build(ROOT, args.output_dir)
    except ZipError as error:
        print(f"release_zip.py: error: {error}", file=sys.stderr)
        return 1
    with zipfile.ZipFile(target) as archive:
        for info in archive.infolist():
            print(f"  {info.external_attr >> 16:o}  {info.file_size:>9}  {info.filename}")
    print(f"Wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
