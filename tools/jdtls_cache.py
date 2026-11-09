#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fill and verify the build cache of the pinned JDT LS, which the endpoint compiles against (design §11).

The endpoint bundle compiles against the exact jars of the pinned JDT LS release, many of which
(``org.eclipse.jdt.ls.core`` among them) aren't on Maven Central. This tool keeps them in a cache
inside the checkout, which git ignores:

    .cache/jdtls/<the tarball's SHA-256>/      the unpacked tarball

The first run downloads the tarball from Eclipse, checks its SHA-256 against ``pin.json`` before
unpacking, and checks every unpacked file against ``pin.json`` after, then renames the folder into
place, as ``bin/install.py`` does. Every run, including the first, verifies the cached folder
against ``pin.json`` again (every file, no missing or extra ones) before any jar is used, so
``mise run build`` works offline once the cache is filled, and never compiles against a changed
jar. The endpoint's ``pom.xml`` names the jars by their path in this folder. Eclipse's files never
go into ``~/.m2`` and are never committed.

The root POM's ``jdtls.cache`` property names the same folder. Development tooling, not shipped;
``mise run build`` runs it before Maven.
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class CacheError(Exception):
    pass


def installer(root: Path):
    """The installer, for its pin, download, unpacking and verification, so the two can't drift."""
    spec = importlib.util.spec_from_file_location("fractal_jdtls_install", root / "bin" / "install.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def reason(error: Exception) -> str:
    """An installer error's own reason, without its advice for installs."""
    return re.split(r"(?i) Nothing was installed| nothing was installed", str(error))[0].rstrip(";.") + "."


def default_cache(root: Path) -> Path:
    return root / ".cache" / "jdtls"


def problems(folder: Path, pin, install) -> list[str]:
    """Every missing, extra or changed file of folder against the pin's JDT LS files."""
    expected = set(pin.eclipse_files)
    files, folders, others = install.scan(folder)
    rows = [f"missing: {name}" for name in sorted(expected - files)]
    rows += [f"extra: {name}" for name in sorted((files | others) - expected)]
    rows += [
        f"changed: {name}"
        for name in sorted(expected & files)
        if install.sha256_of(folder / name) != pin.eclipse_files[name]
    ]
    return rows


def fill(root: Path, cache: Path, install=None) -> Path:
    """The verified folder of the pinned JDT LS in cache, downloading it first if it's not there."""
    install = install or installer(root)
    try:
        pin = install.Pin(root / "pin.json")
    except install.InstallError as error:
        raise CacheError(str(error)) from None
    target = cache / pin.tarball_sha256
    if not target.is_dir():
        cache.mkdir(parents=True, exist_ok=True)
        partial = cache / f".{pin.tarball_sha256}.partial"
        tarball = cache / f".{pin.tarball_sha256}.tar.gz"
        shutil.rmtree(partial, ignore_errors=True)
        print(f"Filling the build cache {cache} with JDT LS {pin.jdtls_version} from {pin.tarball_url}", file=sys.stderr)
        try:
            try:
                install.download(pin.tarball_url, tarball)
            except install.InstallError as error:
                raise CacheError(
                    f"the build cache {cache} has no JDT LS {pin.jdtls_version} yet, and it can't be downloaded: "
                    f"{reason(error)} The endpoint compiles against the pinned JDT LS's jars, so the first build needs "
                    "network access; after that, builds work offline."
                ) from None
            install.checked(tarball, pin.tarball_sha256, f"JDT LS {pin.jdtls_version}", pin.tarball_url)
            install.unpack(tarball, partial)
            found = problems(partial, pin, install)
            if found:
                raise CacheError(
                    f"the JDT LS {pin.jdtls_version} tarball doesn't match pin.json after unpacking:\n  "
                    + "\n  ".join(found)
                )
            partial.rename(target)
        except install.InstallError as error:
            raise CacheError(f"filling the build cache {cache} failed: {reason(error)} Build again.") from None
        finally:
            tarball.unlink(missing_ok=True)
            shutil.rmtree(partial, ignore_errors=True)
    found = problems(target, pin, install)
    if found:
        raise CacheError(
            f"the build cache {target} doesn't match pin.json ({len(found)} problem{'' if len(found) == 1 else 's'}):\n  "
            + "\n  ".join(found)
            + f"\nNothing in the cache may change. Delete {target} and build again, which downloads it again."
        )
    return target


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="jdtls_cache.py", description=__doc__.splitlines()[0])
    parser.add_argument("--cache", type=Path, help="the cache folder (default: .cache/jdtls)")
    args = parser.parse_args(argv)
    started = time.monotonic()
    try:
        target = fill(ROOT, (args.cache or default_cache(ROOT)).resolve())
    except CacheError as error:
        print(f"jdtls_cache.py: error: {error}", file=sys.stderr)
        return 1
    print(f"The pinned JDT LS in {target} verifies ({time.monotonic() - started:.2f} s)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    sys.exit(main())
