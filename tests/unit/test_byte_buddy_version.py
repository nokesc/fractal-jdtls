# SPDX-License-Identifier: Apache-2.0
"""The Byte Buddy version is chosen once, in the root POM (design §3).

pin.json and the built agent jar's Class-Path must name the same version; a mismatch
would otherwise surface only as the agent failing to load at launch. The jar test needs
`mise run build` first; `mise run validate` builds before it tests.
"""

import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
AGENT_JAR = ROOT / "agent/agent/target/fractal-jdtls-agent.jar"


def pom_byte_buddy_version() -> str:
    version = ET.parse(ROOT / "pom.xml").getroot().findtext("m:properties/m:byte-buddy.version", namespaces=POM_NS)
    assert version, "the root pom.xml has no byte-buddy.version property"
    return version.strip()


def manifest(jar: Path) -> dict[str, str]:
    with zipfile.ZipFile(jar) as archive:
        text = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    joined = re.sub(r"\r?\n ", "", text)  # manifest continuation lines
    return dict(line.split(": ", 1) for line in joined.splitlines() if ": " in line)


def test_pin_names_the_root_pom_byte_buddy_version():
    pinned = json.loads((ROOT / "pin.json").read_text(encoding="utf-8"))["byte_buddy"]
    version = pom_byte_buddy_version()
    assert pinned["version"] == version, (
        f"pin.json pins Byte Buddy {pinned['version']}, but the root pom.xml's byte-buddy.version is {version}; "
        "rerun tools/pin.py"
    )
    assert pinned["url"].endswith(f"/net/bytebuddy/byte-buddy/{version}/byte-buddy-{version}.jar")


def test_the_built_agent_jar_names_the_same_byte_buddy():
    assert AGENT_JAR.is_file(), f"{AGENT_JAR.relative_to(ROOT)} isn't built; run `mise run build` first"
    attributes = manifest(AGENT_JAR)
    version = pom_byte_buddy_version()
    pinned = json.loads((ROOT / "pin.json").read_text(encoding="utf-8"))["byte_buddy"]["version"]
    assert attributes.get("Class-Path", "").split() == [f"byte-buddy-{version}.jar"], (
        f"the agent jar's Class-Path is {attributes.get('Class-Path')!r}, but the root pom.xml's byte-buddy.version is "
        f"{version}; rebuild with `mise run build`"
    )
    assert version == pinned
    assert attributes.get("Boot-Class-Path") == "fractal-jdtls-boot.jar"
    assert attributes.get("Premain-Class") == "org.fractal.jdtls.agent.Agent"
    with zipfile.ZipFile(AGENT_JAR) as archive:
        bundled = [n for n in archive.namelist() if n.startswith("net/bytebuddy/")]
    assert not bundled, f"the agent jar bundles Byte Buddy ({len(bundled)} entries); it must only name it (design §6)"
