# SPDX-License-Identifier: Apache-2.0
"""Licensing (design §7): LICENSE, NOTICE, and an SPDX line in every source file."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPDX = "SPDX-License-Identifier: Apache-2.0"
SOURCE_SUFFIXES = {".py", ".java", ".sh", ".toml", ".xml"}
SOURCE_DIRS_ANY_SUFFIX = {"bin"}
SKIPPED_DIRS = {".git", ".venv", ".cache", ".context", "target", "build", "dist", "__pycache__", ".pytest_cache"}


def source_files():
    for path in ROOT.rglob("*"):
        relative = path.relative_to(ROOT)
        if not path.is_file() or SKIPPED_DIRS.intersection(relative.parts):
            continue
        if path.suffix in SOURCE_SUFFIXES or relative.parts[0] in SOURCE_DIRS_ANY_SUFFIX:
            yield relative


def test_license_and_notice_present():
    assert "Apache License" in (ROOT / "LICENSE").read_text(encoding="utf-8")
    assert "Copyright" in (ROOT / "NOTICE").read_text(encoding="utf-8")


def test_every_source_file_has_an_spdx_line():
    files = list(source_files())
    assert files, "found no source files to check"
    missing = [str(f) for f in files if SPDX not in "\n".join((ROOT / f).read_text(encoding="utf-8").splitlines()[:5])]
    assert not missing, f"missing '{SPDX}' in the first five lines: {missing}"
