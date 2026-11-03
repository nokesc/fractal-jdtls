#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Install this Fractal JDT LS release (design §2, §4).

Run it from the unzipped release:

    python3 fractal-jdtls-<release>/bin/install.py [--root DIR]
    python3 <root>/<release>/bin/install.py --verify

It installs the release it ships in into ``<root>/<release>/``:

- this release's own files (``bin/``, the agent and boot jars in ``lib/``,
  ``pin.json``, ``LICENSE``, ``NOTICE``), checked against the release's
  ``release-files.json`` and copied from the release folder with it;
- JDT LS into ``eclipse/``, downloaded from Eclipse; the tarball's SHA-256 is checked
  before unpacking, and every unpacked file's SHA-256 after;
- Byte Buddy into ``lib/``, downloaded from Maven Central and checked.

Everything is assembled in a temporary folder beside the target, verified against
``pin.json`` and ``release-files.json``, then renamed into place, so a failed or
interrupted install never leaves a partial target. If the target already exists and
verifies, it does nothing; if it fails verification, it is left alone.

``--verify`` re-checks an existing install and reports every missing, extra or changed
file, from the unzipped release or from inside the install. The root is ``--root``, else ``$FRACTAL_JDTLS_ROOT``, else the root this copy is
installed in (when run from ``<root>/<release>/bin/``), else
``${XDG_DATA_HOME:-~/.local/share}/fractal-jdtls``. To uninstall, delete the folder.

Standard library only, Python 3.10 or newer.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import http.client
import json
import os
import re
import shutil
import signal
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath

try:
    import fcntl
except ImportError:  # not Linux; only Linux is supported (design §1)
    fcntl = None

ROOT_ENV = "FRACTAL_JDTLS_ROOT"
USER_AGENT = "fractal-jdtls-install (+https://github.com/nokesc/fractal-jdtls)"
RELEASE_RE = re.compile(r"^\d+\.\d+\.\d+-\d+$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z.+_-]*$")

# This release's own files, whose SHA-256 release-files.json records (design §2, §4).
RELEASE_FILES_JSON = "release-files.json"
HASHED_FILES = (
    "bin/install.py",
    "bin/jdtls",
    "lib/fractal-jdtls-agent.jar",
    "lib/fractal-jdtls-boot.jar",
    "pin.json",
    "LICENSE",
    "NOTICE",
)
# Everything copied from the release folder into the install.
RELEASE_FILES = HASHED_FILES + (RELEASE_FILES_JSON,)
EXECUTABLES = frozenset({"bin/install.py", "bin/jdtls"})
ECLIPSE_DIR = "eclipse"

RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})
RETRY_DELAYS = (1.0, 3.0, 9.0)  # seconds before each retry of a failed download
TIMEOUT = 60


class InstallError(Exception):
    """A failure the user can act on; the message says what failed and what to do."""


# --- the pin -----------------------------------------------------------------------


class Pin:
    """The parts of pin.json the installer uses (design §3), checked for shape."""

    def __init__(self, path: Path):
        self.path = path
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
            self.release = document["release"]
            jdtls = document["jdtls"]
            self.jdtls_version = jdtls["version"]
            self.tarball_url = jdtls["url"]
            self.tarball_sha256 = jdtls["sha256"]
            self.eclipse_files = dict(jdtls["files"])
            byte_buddy = document["byte_buddy"]
            self.byte_buddy_version = byte_buddy["version"]
            self.byte_buddy_url = byte_buddy["url"]
            self.byte_buddy_sha256 = byte_buddy["sha256"]
        except FileNotFoundError:
            raise InstallError(f"{path} is missing. Run install.py from an unzipped release.") from None
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise InstallError(f"{path} is not a valid pin ({error!r}). Unzip the release again.") from None
        problems = []
        if not isinstance(self.release, str) or not RELEASE_RE.match(self.release):
            problems.append(f"release {self.release!r} is not <jdtls-version>-<n>")
        if not isinstance(self.byte_buddy_version, str) or not VERSION_RE.match(self.byte_buddy_version):
            problems.append(f"byte_buddy.version {self.byte_buddy_version!r} is not a version")
        for name, value in (("jdtls.sha256", self.tarball_sha256), ("byte_buddy.sha256", self.byte_buddy_sha256)):
            if not isinstance(value, str) or not SHA256_RE.match(value):
                problems.append(f"{name} is not a SHA-256")
        for name, value in (("jdtls.url", self.tarball_url), ("byte_buddy.url", self.byte_buddy_url)):
            if not isinstance(value, str) or not value.startswith(("https://", "http://")):
                problems.append(f"{name} is not a URL")
        if not self.eclipse_files:
            problems.append("jdtls.files is empty")
        for name, digest in self.eclipse_files.items():
            if not safe_relative(name) or not isinstance(digest, str) or not SHA256_RE.match(digest):
                problems.append(f"jdtls.files has an invalid entry {name!r}")
                break
        if problems:
            raise InstallError(f"{path} is not a valid pin: {'; '.join(problems)}. Unzip the release again.")

    @property
    def byte_buddy_file(self) -> str:
        return f"lib/byte-buddy-{self.byte_buddy_version}.jar"

    @property
    def tarball_name(self) -> str:
        return self.tarball_url.rstrip("/").rsplit("/", 1)[-1] or "jdtls.tar.gz"

    def expected_files(self) -> set[str]:
        """Every file an install holds, relative to it, '/'-separated."""
        files = set(RELEASE_FILES)
        files.add(self.byte_buddy_file)
        files.update(f"{ECLIPSE_DIR}/{name}" for name in self.eclipse_files)
        return files

    def pinned_hashes(self) -> dict[str, str]:
        """The files whose SHA-256 pin.json records, relative to the install."""
        hashes = {f"{ECLIPSE_DIR}/{name}": digest for name, digest in self.eclipse_files.items()}
        hashes[self.byte_buddy_file] = self.byte_buddy_sha256
        return hashes


class ReleaseFiles:
    """release-files.json: the release ID and the SHA-256 of this release's own files (design §4)."""

    def __init__(self, path: Path, pin: Pin, remedy: str):
        self.path = path
        try:
            raw = path.read_bytes()
            document = json.loads(raw.decode("utf-8"))
            self.release = document["release"]
            self.hashes = dict(document["files"])
        except FileNotFoundError:
            raise InstallError(f"{path} is missing. {remedy}") from None
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise InstallError(f"{path} is not a valid release-files.json ({error!r}). {remedy}") from None
        self.sha256 = hashlib.sha256(raw).hexdigest()
        if self.release != pin.release:
            raise InstallError(
                f"{path} is for release {self.release!r}, but {pin.path} is for {pin.release!r}: "
                f"the two don't belong together. {remedy}"
            )
        missing = sorted(set(HASHED_FILES) - set(self.hashes))
        extra = sorted(set(self.hashes) - set(HASHED_FILES))
        invalid = sorted(name for name, digest in self.hashes.items() if not (isinstance(digest, str) and SHA256_RE.match(digest)))
        if missing or extra or invalid:
            details = [f"no entry for {', '.join(missing)}"] if missing else []
            details += [f"unexpected entries {', '.join(extra)}"] if extra else []
            details += [f"invalid SHA-256 for {', '.join(invalid)}"] if invalid else []
            raise InstallError(f"{path} is not a valid release-files.json: {'; '.join(details)}. {remedy}")

    def expected_hashes(self) -> dict[str, str]:
        """This release's own files, and release-files.json itself, as loaded."""
        return {**self.hashes, RELEASE_FILES_JSON: self.sha256}


def safe_relative(name: str) -> bool:
    path = PurePosixPath(name)
    return bool(name) and not path.is_absolute() and ".." not in path.parts and "\\" not in name and str(path) == name


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


# --- downloads ---------------------------------------------------------------------


def download(url: str, target: Path) -> None:
    """GET url into target, retrying rate limits and transient failures."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(len(RETRY_DELAYS) + 1):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response, open(target, "wb") as out:
                shutil.copyfileobj(response, out, 1 << 20)
                length = response.headers.get("Content-Length", "")
                received = out.tell()
            # urllib doesn't notice a connection that closes early.
            if length.isdigit() and received != int(length):
                raise http.client.IncompleteRead(b"", int(length) - received)
            return
        except urllib.error.HTTPError as error:
            if error.code not in RETRY_STATUSES or attempt == len(RETRY_DELAYS):
                raise InstallError(
                    f"downloading {url} failed: HTTP {error.code} {error.reason}. Nothing was installed. "
                    "Check that the URL still works, then run install.py again."
                ) from None
            reason = f"HTTP {error.code}"
        except (urllib.error.URLError, http.client.HTTPException, OSError) as error:
            if attempt == len(RETRY_DELAYS):
                raise InstallError(
                    f"downloading {url} failed: {getattr(error, 'reason', error)}. Nothing was installed. "
                    "Check the network connection, then run install.py again."
                ) from None
            reason = str(getattr(error, "reason", error)) or type(error).__name__
        print(f"  {reason}; retrying in {RETRY_DELAYS[attempt]:g} s", file=sys.stderr)
        time.sleep(RETRY_DELAYS[attempt])


def checked(path: Path, expected: str, what: str, url: str) -> None:
    actual = sha256_of(path)
    if actual != expected:
        raise InstallError(
            f"{what} from {url} has SHA-256 {actual}, but pin.json expects {expected}. "
            "The download is corrupt or has been tampered with; nothing was installed. "
            "Run install.py again; if it fails the same way, report it."
        )


# --- unpacking ---------------------------------------------------------------------


def unpack(tarball: Path, target: Path) -> int:
    """Unpack regular files and folders only, refusing anything else; return the file count."""
    count = 0
    target.mkdir()
    try:
        archive = tarfile.open(tarball, "r:gz")
    except (tarfile.TarError, OSError) as error:
        raise InstallError(f"{tarball.name} can't be read as a tarball: {error}.") from None
    with archive:
        try:
            for member in archive:
                name = member.name.removeprefix("./").rstrip("/")
                if name in ("", "."):
                    continue
                if not safe_relative(name):
                    raise InstallError(f"{tarball.name} holds an unsafe path {member.name!r}; nothing was installed.")
                path = target / name
                if member.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                elif member.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    source = archive.extractfile(member)
                    with source, open(path, "xb") as out:
                        shutil.copyfileobj(source, out, 1 << 20)
                    path.chmod(0o755 if member.mode & 0o100 else 0o644)
                    count += 1
                else:
                    raise InstallError(
                        f"{tarball.name} holds {member.name!r}, which is neither a file nor a folder. "
                        "The installer supports only regular files; nothing was installed."
                    )
        except (tarfile.TarError, EOFError, FileExistsError) as error:
            raise InstallError(f"{tarball.name} is damaged ({error}); nothing was installed.") from None
    return count


# --- verification ------------------------------------------------------------------


class Report:
    def __init__(self) -> None:
        self.missing: list[str] = []
        self.extra: list[str] = []
        self.changed: list[str] = []

    def __bool__(self) -> bool:
        return bool(self.missing or self.extra or self.changed)

    def lines(self) -> list[str]:
        rows = [("missing", name) for name in self.missing]
        rows += [("extra", name) for name in self.extra]
        rows += [("changed", name) for name in self.changed]
        return [f"  {kind + ':':<9}{name}" for kind, name in sorted(rows, key=lambda row: (row[1], row[0]))]

    def count(self) -> int:
        return len(self.missing) + len(self.extra) + len(self.changed)


def scan(folder: Path) -> tuple[set[str], set[str], set[str]]:
    """Regular files, folders and anything else under folder, relative and '/'-separated."""
    files, folders, others = set(), set(), set()
    for parent, dirnames, filenames in os.walk(folder):
        base = Path(parent)
        for name in dirnames + filenames:
            path = base / name
            relative = path.relative_to(folder).as_posix()
            if path.is_symlink():
                others.add(relative)
            elif path.is_dir():
                folders.add(relative)
            elif path.is_file():
                files.add(relative)
            else:
                others.add(relative)
        dirnames[:] = [name for name in dirnames if not (base / name).is_symlink()]
    return files, folders, others


def verify(target: Path, pin: Pin, release_files: ReleaseFiles) -> Report:
    """Check every file in target against pin.json and release-files.json.

    Verifying an install from inside it, release_files is its own release-files.json;
    from the unzipped release, it is the release's, so the installed copy must equal it.
    """
    report = Report()
    expected = pin.expected_files()
    expected_folders = {str(parent) for name in expected for parent in PurePosixPath(name).parents} - {"."}
    files, folders, others = scan(target)

    # An expected file that is now a folder, a link or anything else has changed.
    not_regular = (folders | others) & expected
    report.missing = sorted(expected - files - not_regular)
    report.changed = sorted(not_regular)
    extras = (files - expected) | (folders - expected_folders - expected) | (others - expected)
    # Report an extra folder once, not every file below it.
    report.extra = sorted(
        name for name in extras if not any(str(parent) in extras for parent in PurePosixPath(name).parents)
    )
    for name, digest in {**pin.pinned_hashes(), **release_files.expected_hashes()}.items():
        if name in files and sha256_of(target / name) != digest:
            report.changed.append(name)
    report.changed.sort()
    return report


# --- installing --------------------------------------------------------------------


def default_root() -> Path:
    data_home = os.environ.get("XDG_DATA_HOME", "")
    if not os.path.isabs(data_home):  # the XDG spec says to ignore a relative value
        data_home = os.path.join(os.path.expanduser("~"), ".local", "share")
    return Path(data_home) / "fractal-jdtls"


def resolve_root(flag: str | None, release_dir: Path | None = None, release: str | None = None) -> Path:
    """--root, else $FRACTAL_JDTLS_ROOT, else the root release_dir is installed in, else the default."""
    value = flag or os.environ.get(ROOT_ENV) or ""
    if value:
        root = Path(value).expanduser()
    elif release_dir is not None and release_dir.name == release:
        root = release_dir.parent  # an installed copy, <root>/<release>/bin/install.py
    else:
        root = default_root()
    return Path(os.path.abspath(root))


UNZIP_AGAIN = "Unzip the release again, or download it again if this repeats, and run its bin/install.py."


def check_release_dir(release_dir: Path, release_files: ReleaseFiles) -> None:
    """The unzipped release must match its release-files.json before anything is installed."""
    missing = [name for name in HASHED_FILES if not (release_dir / name).is_file()]
    if missing:
        raise InstallError(f"the release folder {release_dir} is incomplete: missing {', '.join(missing)}. {UNZIP_AGAIN}")
    changed = [name for name in HASHED_FILES if sha256_of(release_dir / name) != release_files.hashes[name]]
    if changed:
        raise InstallError(
            f"the release folder {release_dir} doesn't match its release-files.json: "
            f"changed {', '.join(changed)}. Nothing was installed. {UNZIP_AGAIN}"
        )


@contextlib.contextmanager
def locked(root: Path, release: str):
    """Hold an exclusive lock for this release under root, so installs don't race."""
    if fcntl is None:
        yield
        return
    with open(root / f".{release}.lock", "a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def install(release_dir: Path, root: Path, pin: Pin, release_files: ReleaseFiles) -> int:
    target = root / pin.release
    if Path(os.path.realpath(target)) == release_dir:
        return verify_command(target, pin, release_files, installing=True)
    check_release_dir(release_dir, release_files)
    try:
        root.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise InstallError(f"can't create the install root {root}: {error.strerror}. Choose another with --root.") from None

    with locked(root, pin.release):
        if target.exists() or target.is_symlink():
            return verify_command(target, pin, release_files, installing=True)
        # Only a holder of the lock writes partial folders, so any left now are from an
        # install that was killed.
        for stale in root.glob(f".{pin.release}.*.partial"):
            print(f"Removing {stale.name}, left by an interrupted install.")
            shutil.rmtree(stale, ignore_errors=True)

        print(f"Installing fractal-jdtls {pin.release} into {target}")
        work = Path(tempfile.mkdtemp(prefix=f".{pin.release}.", suffix=".partial", dir=root))
        try:
            assemble(work, release_dir, pin)
            report = verify(work, pin, release_files)
            if report:
                raise InstallError(
                    "the assembled install fails verification, so nothing was installed:\n" + "\n".join(report.lines())
                )
            work.chmod(0o755)
            os.rename(work, target)
        except BaseException:
            shutil.rmtree(work, ignore_errors=True)
            raise
    print(f"Installed fractal-jdtls {pin.release} into {target}")
    print(f"The launcher is {target / 'bin' / 'jdtls'}")
    return 0


def assemble(work: Path, release_dir: Path, pin: Pin) -> None:
    downloads = work / ".download"
    downloads.mkdir()

    tarball = downloads / pin.tarball_name
    print(f"Downloading JDT LS {pin.jdtls_version}: {pin.tarball_url}")
    download(pin.tarball_url, tarball)
    checked(tarball, pin.tarball_sha256, f"the JDT LS tarball {pin.tarball_name}", pin.tarball_url)
    print(f"  SHA-256 matches pin.json ({pin.tarball_sha256})")
    count = unpack(tarball, work / ECLIPSE_DIR)
    tarball.unlink()
    print(f"  unpacked {count} files into {ECLIPSE_DIR}/")

    (work / "lib").mkdir()
    jar = work / pin.byte_buddy_file
    print(f"Downloading Byte Buddy {pin.byte_buddy_version}: {pin.byte_buddy_url}")
    download(pin.byte_buddy_url, jar)
    checked(jar, pin.byte_buddy_sha256, f"Byte Buddy {pin.byte_buddy_version}", pin.byte_buddy_url)
    jar.chmod(0o644)
    print(f"  SHA-256 matches pin.json ({pin.byte_buddy_sha256})")
    downloads.rmdir()

    for name in RELEASE_FILES:
        destination = work / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(release_dir / name, destination)
        destination.chmod(0o755 if name in EXECUTABLES else 0o644)
    print("Verifying every file against pin.json and release-files.json")


def verify_command(target: Path, pin: Pin, release_files: ReleaseFiles, installing: bool = False) -> int:
    if not target.is_dir() or target.is_symlink():
        if target.exists() or target.is_symlink():
            raise InstallError(f"{target} exists but isn't a folder. Delete it and run install.py again.")
        raise InstallError(f"fractal-jdtls {pin.release} isn't installed in {target}. Run install.py to install it.")
    report = verify(target, pin, release_files)
    if report:
        verb = "already exists but fails" if installing else "fails"
        print(
            f"install.py: error: {target} {verb} verification "
            f"({report.count()} problem{'' if report.count() == 1 else 's'}):\n"
            + "\n".join(report.lines())
            + f"\nTo reinstall, delete {target} and run install.py again.",
            file=sys.stderr,
        )
        return 1
    count = len(pin.expected_files())
    if installing:
        print(f"fractal-jdtls {pin.release} is already installed in {target} and verifies; nothing to do.")
    else:
        print(f"{target} verifies: all {count} files are present, with nothing extra.")
        print(
            f"  {len(pin.pinned_hashes())} match pin.json, and this release's own {len(HASHED_FILES)} "
            f"match {release_files.path}."
        )
    return 0


# --- command line ------------------------------------------------------------------


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="install.py",
        description="Install this Fractal JDT LS release into <root>/<release>/, or verify an install.",
    )
    parser.add_argument(
        "--root",
        help=(
            f"the install root (default: ${ROOT_ENV}, else the root this copy is installed in, "
            "else ${XDG_DATA_HOME:-~/.local/share}/fractal-jdtls)"
        ),
    )
    parser.add_argument(
        "--verify", action="store_true", help="re-check an existing install and report missing, extra or changed files"
    )
    return parser.parse_args(argv)


def main(argv=None, release_dir: Path | None = None) -> int:
    args = parse_args(argv)
    if release_dir is None:
        release_dir = Path(__file__).resolve().parent.parent
    release_dir = Path(os.path.realpath(release_dir))
    try:
        pin = Pin(release_dir / "pin.json")
        installed = release_dir.name == pin.release
        remedy = (
            f"The install is damaged: delete {release_dir} and run install.py from the unzipped release again."
            if installed else UNZIP_AGAIN
        )
        release_files = ReleaseFiles(release_dir / RELEASE_FILES_JSON, pin, remedy)
        root = resolve_root(args.root, release_dir, pin.release)
        target = root / pin.release
        if args.verify:
            if Path(os.path.realpath(target)) != release_dir:
                check_release_dir(release_dir, release_files)
            return verify_command(target, pin, release_files)
        return install(release_dir, root, pin, release_files)
    except InstallError as error:
        print(f"install.py: error: {error}", file=sys.stderr)
        return 1
    except OSError as error:
        where = f"{error.filename}: " if error.filename else ""
        print(f"install.py: error: {where}{error.strerror or error}. Nothing was installed.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("install.py: interrupted; nothing was installed.", file=sys.stderr)
        return 130


def _terminated(signum, frame):
    # Unwind like Ctrl-C, so the partial folder is removed.
    raise KeyboardInterrupt


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, _terminated)
    sys.exit(main())
