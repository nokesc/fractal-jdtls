# SPDX-License-Identifier: Apache-2.0
"""tools/release_tag.py: the release workflow's tag check (design §2, §9)."""

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import release_tag  # noqa: E402


def world(tmp_path, release="9.9.9-2", notes=True):
    (tmp_path / "pin.json").write_text(json.dumps({"release": release}))
    if notes:
        (tmp_path / ".github" / "release-notes").mkdir(parents=True)
        (tmp_path / ".github" / "release-notes" / f"v{release}.md").write_text("notes\n")
    return tmp_path


def test_matching_tag(tmp_path):
    assert release_tag.check("v9.9.9-2", world(tmp_path)) == {
        "release": "9.9.9-2",
        "zip": "build/fractal-jdtls-9.9.9-2.zip",
        "notes": ".github/release-notes/v9.9.9-2.md",
    }


@pytest.mark.parametrize("tag", ["v9.9.9-1", "9.9.9-2", "v9.9.9", "v9.9.9-2-rc1", "V9.9.9-2", ""])
def test_mismatched_tag(tmp_path, tag):
    with pytest.raises(release_tag.TagError, match=r"doesn't match pin.json's release '9.9.9-2'.*Tag v9\.9\.9-2"):
        release_tag.check(tag, world(tmp_path))


def test_missing_release_notes(tmp_path):
    with pytest.raises(release_tag.TagError, match=r"no release notes for v9.9.9-2: add .github/release-notes/v9.9.9-2.md"):
        release_tag.check("v9.9.9-2", world(tmp_path, notes=False))


def test_this_repository_has_notes_for_its_release():
    """The committed pin.json's release can be tagged: its notes exist."""
    release = json.loads((ROOT / "pin.json").read_text())["release"]
    assert release_tag.check(f"v{release}")["release"] == release


def test_main_writes_github_output(tmp_path, monkeypatch, capsys):
    release = json.loads((ROOT / "pin.json").read_text())["release"]
    output = tmp_path / "github_output"
    monkeypatch.setenv("GITHUB_OUTPUT", str(output))
    assert release_tag.main([f"v{release}"]) == 0
    assert output.read_text().splitlines() == [
        f"release={release}",
        f"zip=build/fractal-jdtls-{release}.zip",
        f"notes=.github/release-notes/v{release}.md",
    ]
    assert release_tag.main(["v0.0.0-1"]) == 1
    assert "doesn't match" in capsys.readouterr().err
