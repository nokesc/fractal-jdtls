# SPDX-License-Identifier: Apache-2.0
"""Check a release tag against pin.json, the first step of the release workflow (design §2, §9).

Usage: python tools/release_tag.py <tag>

The tag must be `v` plus pin.json's `release` (for example v1.63.0-1), and the release notes
.github/release-notes/<tag>.md must exist. On success it prints the release ID, the zip and
the notes file, and, under GitHub Actions, writes them to $GITHUB_OUTPUT as `release`, `zip`
and `notes`. It exits 1, saying what to do, otherwise.
"""

import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class TagError(Exception):
    pass


def check(tag: str, root: Path = ROOT) -> dict:
    release = json.loads((root / "pin.json").read_text(encoding="utf-8"))["release"]
    expected = f"v{release}"
    if tag != expected:
        raise TagError(
            f"the tag {tag!r} doesn't match pin.json's release {release!r}: this commit can only be released "
            f"as {expected}. Tag {expected} instead, or re-pin (mise run pin) and commit before tagging."
        )
    notes = Path(".github") / "release-notes" / f"{tag}.md"
    if not (root / notes).is_file():
        raise TagError(f"there are no release notes for {tag}: add {notes} and commit it before tagging")
    return {"release": release, "zip": f"build/fractal-jdtls-{release}.zip", "notes": str(notes)}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="release_tag.py", description=__doc__.splitlines()[0])
    parser.add_argument("tag", help="the pushed tag, for example v1.63.0-1")
    args = parser.parse_args(argv)
    try:
        outputs = check(args.tag)
    except TagError as error:
        print(f"release_tag.py: error: {error}", file=sys.stderr)
        return 1
    lines = [f"{key}={value}" for key, value in outputs.items()]
    print(f"The tag {args.tag} matches pin.json's release.")
    print("\n".join(lines))
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as out:
            out.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
