#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Pin one official Eclipse JDT LS release (design §3).

Maintainer tooling, not shipped. Given a JDT LS version and a release number, it:

1. downloads the tarball from Eclipse's milestones site and checks it against the
   published ``<tarball>.sha256``;
2. records the SHA-256 of every file in it;
3. classifies each jar as signed by the Eclipse Foundation (``jarsigner -verify``),
   byte-identical to an artifact on Maven Central (by the artifact's published hash),
   or neither, trusted only through the tarball's checksum;
4. downloads Byte Buddy, at the root POM's ``byte-buddy.version``, from Maven Central,
   checks it against Central's published hashes, and verifies its PGP signature when
   ``gpg`` and the signer's key are available;
5. writes ``pin.json`` and a pin report.

``pin.json`` depends only on what was downloaded, so rerunning the tool for the same
release produces the same bytes. The report also records when the checks were made.

Standard library only.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

ECLIPSE_URL = "https://download.eclipse.org/jdtls/milestones"
CENTRAL_URL = "https://repo.maven.apache.org/maven2"
# Two front ends to Central's SHA-1 index, which each miss some artifacts.
CENTRAL_SEARCH_URLS = (
    "https://search.maven.org/solrsearch/select",
    "https://central.sonatype.com/solrsearch/select",
)
USER_AGENT = "fractal-jdtls-pin (+https://github.com/nokesc/fractal-jdtls)"

# The organisations the Eclipse Foundation's code-signing certificates have named.
ECLIPSE_SIGNER_ORGS = frozenset({
    "Eclipse Foundation",
    "Eclipse Foundation, Inc.",
    "Eclipse.org Foundation, Inc.",
})

# jarsigner warnings that mean a "jar verified." result does not show who signed it,
# that the signer's certificate may not sign code (KeyUsage, ExtendedKeyUsage or
# NetscapeCertType), or that the signature doesn't cover the whole jar. A TSA
# (timestamp) chain problem is recorded, not disqualifying: the signer's own chain is
# still checked.
DISQUALIFYING_WARNINGS = re.compile(
    r"unsigned entries"
    r"|(?<!TSA )certificate chain is invalid"
    r"|self-signed"
    r"|signer certificate has expired"
    r"|not yet valid"
    r"|not signed by the specified alias"
    r"|doesn't allow code signing",
)

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
TARBALL_RE = r"^jdt-language-server-{version}-\d{{12}}\.tar\.gz$"
MIN_JAVA_RE = re.compile(r"java_major_version\s*<\s*(\d+)")
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class PinError(Exception):
    """A check failed. The message says what failed and what to do."""


# --- downloading ------------------------------------------------------------------


RETRY_STATUSES = frozenset({429, 500, 502, 503, 504})
RETRIES = 6
# Central's search front ends rate-limit; keep few queries in flight.
SEARCH_SLOTS = threading.BoundedSemaphore(2)


def _open(url: str, sink) -> None:
    """GET url into sink(response), retrying rate limits and transient failures."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(RETRIES + 1):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                sink(response)
                return
        except urllib.error.HTTPError as error:
            if error.code not in RETRY_STATUSES or attempt == RETRIES:
                raise PinError(f"GET {url} failed: HTTP {error.code}") from error
            delay = retry_after(error.headers.get("Retry-After"), attempt)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if attempt == RETRIES:
                reason = getattr(error, "reason", error)
                raise PinError(f"GET {url} failed: {reason}. Check the network.") from error
            delay = retry_after(None, attempt)
        time.sleep(delay)


def retry_after(header: str | None, attempt: int) -> float:
    if header and header.strip().isdigit():
        return min(float(header.strip()), 60.0)
    return min(2.0 ** attempt, 30.0)


def fetch(url: str) -> bytes:
    chunks: list[bytes] = []
    _open(url, lambda response: chunks.append(response.read()))
    return b"".join(chunks)


def fetch_optional(url: str) -> bytes | None:
    """Like fetch, but None when the server answers 404."""
    try:
        return fetch(url)
    except PinError as error:
        if isinstance(error.__cause__, urllib.error.HTTPError) and error.__cause__.code == 404:
            return None
        raise


def fetch_to(url: str, target: Path) -> None:
    partial = target.with_name(target.name + ".part")

    def save(response) -> None:
        with open(partial, "wb") as out:
            shutil.copyfileobj(response, out, 1 << 20)

    try:
        _open(url, save)
    except PinError:
        partial.unlink(missing_ok=True)
        raise
    partial.replace(target)


def published_hash(text: bytes, url: str) -> str:
    """The hex digest in a published checksum file (``<hex>`` or ``<hex>  <name>``)."""
    fields = text.decode("ascii", errors="replace").split()
    if not fields or not re.fullmatch(r"[0-9a-fA-F]+", fields[0]):
        raise PinError(f"{url} does not hold a checksum: {text[:80]!r}")
    return fields[0].lower()


@dataclasses.dataclass(frozen=True)
class Digests:
    sha1: str
    sha256: str
    sha512: str

    @classmethod
    def of(cls, path: Path) -> "Digests":
        sha1, sha256, sha512 = hashlib.sha1(), hashlib.sha256(), hashlib.sha512()
        with open(path, "rb") as stream:
            while chunk := stream.read(1 << 20):
                sha1.update(chunk)
                sha256.update(chunk)
                sha512.update(chunk)
        return cls(sha1.hexdigest(), sha256.hexdigest(), sha512.hexdigest())

    def get(self, algorithm: str) -> str:
        return getattr(self, algorithm)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        while chunk := stream.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


# --- step 1: the tarball ----------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Tarball:
    name: str
    url: str
    sha256: str
    path: Path


def fetch_tarball(version: str, eclipse_url: str, cache_dir: Path) -> Tarball:
    base = f"{eclipse_url.rstrip('/')}/{version}"
    name = fetch(f"{base}/latest.txt").decode("ascii", errors="replace").strip()
    if not re.fullmatch(TARBALL_RE.format(version=re.escape(version)), name):
        raise PinError(f"{base}/latest.txt names {name!r}, not a JDT LS {version} release tarball")
    url = f"{base}/{name}"
    expected = published_hash(fetch(f"{url}.sha256"), f"{url}.sha256")

    cached = cache_dir / "sha256" / expected / name
    if cached.is_file() and sha256_of(cached) == expected:
        print(f"tarball: using the cached {cached}", file=sys.stderr)
    else:
        cached.parent.mkdir(parents=True, exist_ok=True)
        print(f"tarball: downloading {url}", file=sys.stderr)
        fetch_to(url, cached)
        actual = sha256_of(cached)
        if actual != expected:
            cached.unlink()
            raise PinError(
                f"{name}: SHA-256 is {actual}, but Eclipse publishes {expected} in {url}.sha256. "
                "The download is corrupt or not the published release; do not pin it."
            )
    return Tarball(name=name, url=url, sha256=expected, path=cached)


def unpack(tarball: Path, target: Path) -> list[str]:
    """Unpack into target; return every regular file's path, sorted, '/'-separated."""
    files = []
    with tarfile.open(tarball, "r:gz") as archive:
        for member in archive.getmembers():
            if member.isfile():
                files.append(member.name.removeprefix("./"))
            elif not member.isdir():
                raise PinError(
                    f"{tarball.name}: {member.name} is neither a file nor a folder. "
                    "The pin and the installer support only regular files; update both first."
                )
        archive.extractall(target, filter="data")
    return sorted(files)


def minimum_java(unpacked: Path) -> int:
    """The minimum Java that the release's own launcher (bin/jdtls.py) enforces."""
    launcher = unpacked / "bin" / "jdtls.py"
    match = MIN_JAVA_RE.search(launcher.read_text(encoding="utf-8")) if launcher.is_file() else None
    if not match:
        raise PinError(
            "can't find the minimum Java version in the tarball's bin/jdtls.py "
            "(looked for 'java_major_version < N'). Read the release notes and update tools/pin.py."
        )
    return int(match.group(1))


# --- step 3: classifying jars -----------------------------------------------------


def dn_attributes(dn: str) -> dict[str, str]:
    """Split an X.500 name as jarsigner prints it, honouring quoted values."""
    attributes: dict[str, str] = {}
    for part in re.findall(r'(?:[^,"]|"[^"]*")+', dn):
        key, sep, value = part.strip().partition("=")
        if sep:
            attributes[key.strip()] = value.strip().strip('"')
    return attributes


@dataclasses.dataclass(frozen=True)
class Signature:
    status: str  # "eclipse", "unsigned" or "other"
    signers: tuple[str, ...] = ()
    notes: tuple[str, ...] = ()


def parse_jarsigner(returncode: int, output: str, jar_name: str) -> Signature:
    """Classify `jarsigner -verify -verbose:summary -certs` output."""
    lines = [line.strip() for line in output.splitlines()]
    if "jar is unsigned." in lines:
        return Signature("unsigned")
    if returncode != 0 or not any(line.startswith("jar verified") for line in lines):
        raise PinError(
            f"{jar_name}: jarsigner -verify failed (exit {returncode}): "
            + " ".join(line for line in lines if line)[:400]
            + ". A broken signature in an official release needs a person to look at it."
        )

    signers = []
    for index, line in enumerate(lines):
        if line == ">>> Signer" and index + 1 < len(lines) and lines[index + 1].startswith("X.509, "):
            signers.append(lines[index + 1].removeprefix("X.509, "))
    signers = tuple(sorted(set(signers)))

    notes = []
    collecting = False
    for line in lines:
        if line in ("Warning:", "Error:"):
            collecting = True
        elif not line or line.startswith("Re-run with"):
            collecting = False
        elif collecting:
            notes.append(line)
    notes = tuple(notes)

    if not signers:
        return Signature("other", signers, notes + ("no signer certificate in jarsigner's output",))
    if not all(dn_attributes(dn).get("O") in ECLIPSE_SIGNER_ORGS for dn in signers):
        return Signature("other", signers, notes)
    if any(DISQUALIFYING_WARNINGS.search(note) for note in notes):
        return Signature("other", signers, notes)
    return Signature("eclipse", signers, notes)


# parse_jarsigner reads jarsigner's English messages, whatever the JVM's default locale.
JARSIGNER_LOCALE = ("-J-Duser.language=en", "-J-Duser.country=US")


def verify_signature(jarsigner: str, jar: Path) -> Signature:
    result = subprocess.run(
        [jarsigner, *JARSIGNER_LOCALE, "-verify", "-verbose:summary", "-certs", str(jar)],
        capture_output=True,
        text=True,
    )
    return parse_jarsigner(result.returncode, result.stdout + result.stderr, jar.name)


@dataclasses.dataclass(frozen=True)
class CentralMatch:
    gav: str
    url: str
    checked: str  # which published hash proved it: "sha512", "sha256", or "bytes"


def artifact_url(central_url: str, group: str, artifact: str, version: str, suffix: str = ".jar") -> str:
    return f"{central_url.rstrip('/')}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{suffix}"


def embedded_gavs(jar: Path) -> set[tuple[str, str, str]]:
    """GAVs named by the jar's own META-INF/maven/*/*/pom.properties."""
    gavs = set()
    try:
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith("META-INF/maven/") and name.endswith("/pom.properties"):
                    props = {}
                    for line in archive.read(name).decode("utf-8", errors="replace").splitlines():
                        key, sep, value = line.partition("=")
                        if sep and not key.startswith("#"):
                            props[key.strip()] = value.strip()
                    if props.get("groupId") and props.get("artifactId") and props.get("version"):
                        gavs.add((props["groupId"], props["artifactId"], props["version"]))
    except zipfile.BadZipFile as error:
        raise PinError(f"{jar.name} is not a valid jar: {error}") from error
    return gavs


def central_matches(jar: Path, digests: Digests, central_url: str, search_urls) -> tuple[CentralMatch, ...]:
    """Artifacts on Maven Central that are byte-identical to this jar.

    Candidates come from Central's SHA-1 search and from the jar's own pom.properties;
    each is accepted only when Central's published hash for it matches the jar.
    """
    candidates: dict[tuple[str, str, str], set[str]] = {gav: set() for gav in embedded_gavs(jar)}
    query = urllib.parse.urlencode({"q": f"1:{digests.sha1}", "rows": "20", "wt": "json"})
    for search_url in search_urls:
        with SEARCH_SLOTS:
            answer = fetch(f"{search_url}?{query}")
        try:
            docs = json.loads(answer)["response"]["docs"]
        except (ValueError, KeyError, TypeError) as error:
            raise PinError(f"Maven Central search for {jar.name} returned an unexpected answer: {error}") from error
        for doc in docs:
            if doc.get("g") and doc.get("a") and doc.get("v"):
                candidates.setdefault((doc["g"], doc["a"], doc["v"]), set()).update(doc.get("ec") or ())

    matches = []
    for (group, artifact, version), extensions in sorted(candidates.items()):
        suffixes = [".jar"] + sorted(
            e for e in extensions
            if e.startswith("-") and e.endswith(".jar") and e not in ("-sources.jar", "-javadoc.jar")
        )
        for suffix in suffixes:
            match = central_match(jar, digests, central_url, group, artifact, version, suffix)
            if match:
                matches.append(match)
                break
    return tuple(sorted(set(matches), key=lambda m: m.gav))


def central_match(jar, digests, central_url, group, artifact, version, suffix) -> CentralMatch | None:
    url = artifact_url(central_url, group, artifact, version, suffix)
    sha1 = fetch_optional(f"{url}.sha1")
    if sha1 is None or published_hash(sha1, f"{url}.sha1") != digests.sha1:
        return None
    classifier = suffix.removeprefix("-").removesuffix(".jar")
    gav = f"{group}:{artifact}:{version}" + (f":{classifier}" if classifier else "")
    # SHA-1 found the candidate; a stronger published hash, or the bytes, prove it.
    for algorithm in ("sha512", "sha256"):
        published = fetch_optional(f"{url}.{algorithm}")
        if published is not None:
            if published_hash(published, f"{url}.{algorithm}") != digests.get(algorithm):
                return None
            return CentralMatch(gav, url, algorithm)
    if hashlib.sha256(fetch(url)).hexdigest() != digests.sha256:
        return None
    return CentralMatch(gav, url, "bytes")


@dataclasses.dataclass(frozen=True)
class Jar:
    path: str
    signature: Signature
    central: tuple[CentralMatch, ...]

    @property
    def eclipse_signed(self) -> bool:
        return self.signature.status == "eclipse"

    @property
    def trust(self) -> str:
        sources = []
        if self.eclipse_signed:
            sources.append("Eclipse-signed")
        if self.central:
            sources.append("Maven Central")
        return "; ".join(sources) or "tarball checksum only"


def classify_jars(unpacked: Path, files: list[str], args) -> list[Jar]:
    def classify(path: str) -> Jar:
        jar = unpacked / path
        signature = verify_signature(args.jarsigner, jar)
        central = central_matches(jar, Digests.of(jar), args.central_url, args.central_search_url or CENTRAL_SEARCH_URLS)
        return Jar(path, signature, central)

    jars = [path for path in files if path.endswith(".jar")]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        return list(pool.map(classify, jars))


# --- step 4: Byte Buddy -----------------------------------------------------------


def byte_buddy_version(pom: Path) -> str:
    try:
        version = ET.parse(pom).getroot().findtext("m:properties/m:byte-buddy.version", namespaces=POM_NS)
    except (OSError, ET.ParseError) as error:
        raise PinError(f"can't read {pom}: {error}") from error
    if not version or not version.strip():
        raise PinError(f"{pom} has no byte-buddy.version property; the version is chosen there (design §3)")
    return version.strip()


@dataclasses.dataclass(frozen=True)
class ByteBuddy:
    version: str
    url: str
    sha256: str
    published: tuple[str, ...]  # the published hashes it matched
    pgp: str  # what the PGP check found


def fetch_byte_buddy(version: str, args, work: Path) -> ByteBuddy:
    url = artifact_url(args.central_url, "net.bytebuddy", "byte-buddy", version)
    jar = work / f"byte-buddy-{version}.jar"
    print(f"byte buddy: downloading {url}", file=sys.stderr)
    fetch_to(url, jar)
    digests = Digests.of(jar)

    matched = []
    for algorithm in ("sha512", "sha256", "sha1"):
        published = fetch_optional(f"{url}.{algorithm}")
        if published is None:
            continue
        expected = published_hash(published, f"{url}.{algorithm}")
        if expected != digests.get(algorithm):
            raise PinError(
                f"byte-buddy-{version}.jar: {algorithm} is {digests.get(algorithm)}, but Maven Central "
                f"publishes {expected}. The download is corrupt; do not pin it."
            )
        matched.append(algorithm)
    if not matched:
        raise PinError(f"Maven Central publishes no checksum for {url}; can't check the download")

    asc = fetch_optional(f"{url}.asc")
    if asc is None:
        pgp = "not checked: Maven Central has no .asc for it"
    else:
        (work / f"{jar.name}.asc").write_bytes(asc)
        pgp = verify_pgp(jar, work / f"{jar.name}.asc", args.gnupghome)
    return ByteBuddy(version, url, digests.sha256, tuple(matched), pgp)


def verify_pgp(path: Path, asc: Path, gnupghome: str | None) -> str:
    """Check a detached signature with the maintainer's keyring, without creating one."""
    gpg = shutil.which("gpg")
    if gpg is None:
        return "not checked: gpg is not installed"
    home = Path(gnupghome or os.environ.get("GNUPGHOME") or Path.home() / ".gnupg")
    shown = str(home).replace(str(Path.home()), "~", 1)
    if not home.is_dir():
        return f"not checked: no GnuPG home at {shown}"
    result = subprocess.run(
        [gpg, "--homedir", str(home), "--batch", "--no-tty", "--status-fd", "1", "--verify", str(asc), str(path)],
        capture_output=True,
        text=True,
    )
    status = {line.split()[1]: line.split()[2:] for line in result.stdout.splitlines() if line.startswith("[GNUPG:] ")}
    if "BADSIG" in status or "REVKEYSIG" in status:
        raise PinError(f"{path.name}: the PGP signature is BAD or the key is revoked. Do not pin it.\n{result.stderr}")
    if "VALIDSIG" in status:
        note = " (the key has expired)" if "EXPKEYSIG" in status else ""
        return f"verified: good signature by key {status['VALIDSIG'][0]}{note}"
    if "NO_PUBKEY" in status:
        return f"not checked: key {status['NO_PUBKEY'][0]} is not in the keyring at {shown}"
    raise PinError(f"{path.name}: gpg could not check the signature (exit {result.returncode}).\n{result.stderr}")


# --- step 5: pin.json and the report ----------------------------------------------


def pin_document(release, version, tarball, files_sha256, java_minimum, byte_buddy) -> dict:
    return {
        "release": release,
        "jdtls": {
            "version": version,
            "url": tarball.url,
            "sha256": tarball.sha256,
            "files": files_sha256,
        },
        "java": {"minimum": java_minimum},
        "byte_buddy": {
            "version": byte_buddy.version,
            "url": byte_buddy.url,
            "sha256": byte_buddy.sha256,
        },
    }


def render_pin(document: dict) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def render_report(release, version, tarball, files, jars, java_minimum, byte_buddy, checked_on) -> str:
    plugins = [j for j in jars if j.path.startswith("plugins/")]
    others = [j for j in jars if not j.path.startswith("plugins/")]
    signed_plugins = [j for j in plugins if j.eclipse_signed]
    central = [j for j in jars if j.central]
    tarball_only_jars = [j for j in jars if not j.eclipse_signed and not j.central]
    jar_paths = {j.path for j in jars}
    other_files = [f for f in files if f not in jar_paths]

    out = [
        f"# Pin report: JDT LS {version} (release {release})",
        "",
        "Written by `tools/pin.py`. It records how each file of the release was checked (design §3). "
        "Hashes we compute only prove a file hasn't changed since; what a file should be comes from the "
        "outside checks below.",
        "",
        f"Checked on {checked_on}.",
        "",
        "## Tarball",
        "",
        f"- Name: `{tarball.name}`",
        f"- URL: <{tarball.url}>",
        f"- SHA-256: `{tarball.sha256}`, equal to Eclipse's published `{tarball.name}.sha256`.",
        "  Eclipse publishes no PGP signature for it.",
        f"- {len(files)} files, {len(jars)} of them jars. `pin.json` holds the SHA-256 of every file.",
        f"- Minimum Java: {java_minimum}, as enforced by the tarball's own `bin/jdtls.py`.",
        "",
        "## Summary",
        "",
        f"- **`plugins/`: {len(signed_plugins)} of {len(plugins)} jars are signed by the Eclipse Foundation** "
        f"(`jarsigner -verify`); {len(plugins) - len(signed_plugins)} are not.",
    ]
    for jar in others:
        out.append(f"- `{jar.path}`: {jar.trust}.")
    out += [
        f"- **{len(central)} jars are byte-identical to Maven Central** artifacts.",
        f"- **{len(tarball_only_jars)} jars, and the {len(other_files)} other files, are trusted only through "
        "the tarball's checksum.**",
        "",
        "## Byte Buddy",
        "",
        f"- Version `{byte_buddy.version}`, from the root POM's `byte-buddy.version`.",
        f"- URL: <{byte_buddy.url}>",
        f"- SHA-256: `{byte_buddy.sha256}`, equal to Maven Central's published "
        + ", ".join(f"`.{a}`" for a in byte_buddy.published)
        + ".",
        f"- PGP signature (`.asc`): {byte_buddy.pgp}.",
        "",
        "## Every jar",
        "",
        "A jar counts as Eclipse-signed when `jarsigner -verify` verifies it, every signer certificate names "
        "the Eclipse Foundation as its organisation, and no warning says the signer's chain is invalid or "
        "self-signed, the signer certificate may not sign code or expired untimestamped, or some entries are "
        "unsigned. A warning "
        "about the timestamp authority's chain alone is recorded below but doesn't disqualify it.",
        "",
        "In the Maven Central column, `(sha512)` or `(sha256)` means Central's published hash of that "
        "strength equals the jar's; `(bytes)` means Central publishes only a SHA-1 for it, so the artifact was "
        "downloaded and compared byte for byte.",
        "",
        "| Jar | Eclipse signature | Maven Central | Trusted through |",
        "| --- | --- | --- | --- |",
    ]
    for jar in jars:
        if jar.eclipse_signed:
            signature = "signed by " + "; ".join(f"`{dn_attributes(dn).get('O')}`" for dn in jar.signature.signers)
        elif jar.signature.status == "unsigned":
            signature = "unsigned"
        else:
            signature = "signed, not accepted: " + "; ".join(f"`{dn}`" for dn in jar.signature.signers)
        maven = "<br>".join(f"`{m.gav}` ({m.checked})" for m in jar.central) or "no"
        out.append(f"| `{jar.path}` | {signature} | {maven} | {jar.trust} |")

    by_note: dict[str, list[str]] = {}
    for jar in jars:
        if jar.signature.status != "unsigned":
            for note in jar.signature.notes:
                by_note.setdefault(note, []).append(jar.path)
    if by_note:
        out += ["", "## jarsigner warnings", "", "Each warning `jarsigner -verify` gave for a signed jar, with the jars it applies to:", ""]
        for note, paths in sorted(by_note.items(), key=lambda item: (-len(item[1]), item[0])):
            out.append(f"- {note}")
            if len(paths) > 5:
                out.append(f"  ({len(paths)} jars)")
            else:
                out += [f"  - `{path}`" for path in paths]

    out += [
        "",
        "## Trusted only through the tarball's checksum",
        "",
        "Neither signed by the Eclipse Foundation nor byte-identical to Maven Central:",
        "",
    ]
    out += [f"- `{path}`" for path in sorted(j.path for j in tarball_only_jars)]
    out += ["", "Files that aren't jars, which nothing but the tarball's checksum covers:", ""]
    out += [f"- `{path}`" for path in other_files]
    out.append("")
    return "\n".join(out)


# --- the command ------------------------------------------------------------------


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version", help="the JDT LS version, for example 1.63.0")
    parser.add_argument("--release", type=int, default=1, help="the release number <n> in <version>-<n> (default 1)")
    parser.add_argument("--pom", type=Path, default=ROOT / "pom.xml", help="the POM with byte-buddy.version")
    parser.add_argument("--output", type=Path, default=ROOT / "pin.json", help="where to write pin.json")
    parser.add_argument("--report", type=Path, help="where to write the report (default .context/pins/<version>.md)")
    parser.add_argument("--cache-dir", type=Path, help="keep downloads here between runs (default: a temporary folder)")
    parser.add_argument("--jarsigner", default="jarsigner", help="the jarsigner to run (default: from PATH)")
    parser.add_argument("--gnupghome", help="the GnuPG home with the Byte Buddy signer's key (default: GNUPGHOME or ~/.gnupg)")
    parser.add_argument("--eclipse-url", default=ECLIPSE_URL, help=argparse.SUPPRESS)
    parser.add_argument("--central-url", default=CENTRAL_URL, help=argparse.SUPPRESS)
    parser.add_argument("--central-search-url", action="append", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if not VERSION_RE.fullmatch(args.version):
        parser.error(f"version must look like 1.63.0, not {args.version!r}")
    if args.release < 1:
        parser.error("--release must be 1 or more")
    if "snapshot" in args.eclipse_url.lower():
        parser.error("only official releases are pinned, never snapshots (design §1)")
    if args.report is None:
        args.report = ROOT / ".context" / "pins" / f"{args.version}.md"
    return args


def run(args) -> None:
    release = f"{args.version}-{args.release}"
    bb_version = byte_buddy_version(args.pom)
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="fractal-jdtls-pin-", dir=args.cache_dir) as temporary:
        work = Path(temporary)
        cache = args.cache_dir or work
        tarball = fetch_tarball(args.version, args.eclipse_url, cache)
        print(f"tarball: SHA-256 {tarball.sha256} matches {tarball.url}.sha256", file=sys.stderr)

        unpacked = work / "unpacked"
        files = unpack(tarball.path, unpacked)
        files_sha256 = {path: sha256_of(unpacked / path) for path in files}
        java_minimum = minimum_java(unpacked)

        print(f"jars: classifying {sum(p.endswith('.jar') for p in files)} jars", file=sys.stderr)
        jars = classify_jars(unpacked, files, args)
        byte_buddy = fetch_byte_buddy(bb_version, args, work)

    document = pin_document(release, args.version, tarball, files_sha256, java_minimum, byte_buddy)
    checked_on = datetime.date.today().isoformat()
    report = render_report(release, args.version, tarball, files, jars, java_minimum, byte_buddy, checked_on)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_pin(document), encoding="utf-8")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(report, encoding="utf-8")

    plugins = [j for j in jars if j.path.startswith("plugins/")]
    print(
        f"wrote {args.output} (release {release}) and {args.report}: "
        f"{sum(j.eclipse_signed for j in plugins)} of {len(plugins)} plugins/ jars Eclipse-signed, "
        f"{sum(bool(j.central) for j in jars)} jars on Maven Central, "
        f"{sum(not j.eclipse_signed and not j.central for j in jars)} jars trusted through the tarball only; "
        f"Byte Buddy {bb_version}, PGP {byte_buddy.pgp}",
        file=sys.stderr,
    )


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        run(args)
    except PinError as error:
        print(f"pin.py: error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
