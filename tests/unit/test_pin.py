# SPDX-License-Identifier: Apache-2.0
"""tools/pin.py (design §3), against a small fake tarball and a local fake server.

The jars are signed for real with test keys. A wrapper around jarsigner adds a test
trust store, so a test "Eclipse Foundation" key verifies with a valid chain, while a
self-signed impostor with the same name does not.
"""

import hashlib
import http.server
import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tarfile
import threading
import urllib.parse
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pin", ROOT / "tools" / "pin.py")
pin = importlib.util.module_from_spec(SPEC)
sys.modules["pin"] = pin
SPEC.loader.exec_module(pin)

VERSION = "9.9.9"
TARBALL = f"jdt-language-server-{VERSION}-202601020304.tar.gz"
BB_VERSION = "1.2.3"
STOREPASS = "changeit"
ECLIPSE_DN = "CN=Eclipse Foundation, O=Eclipse Foundation, L=Bruxelles, C=BE"
OTHER_DN = "CN=Someone Else, O=Someone Else, C=US"


def sha(data: bytes, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, data).hexdigest()


def make_jar(path: Path, name: str, pom_gav: tuple[str, str, str] | None = None) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        archive.writestr(f"{name}/Thing.txt", f"content of {name}\n")
        if pom_gav:
            group, artifact, version = pom_gav
            archive.writestr(
                f"META-INF/maven/{group}/{artifact}/pom.properties",
                f"#Generated\ngroupId={group}\nartifactId={artifact}\nversion={version}\n",
            )
    return path


def java_tool(name: str) -> str:
    found = shutil.which(name)
    assert found, f"{name} is not on PATH; run the tests through mise (mise run test)"
    return found


@pytest.fixture(scope="module")
def world(tmp_path_factory):
    """Signed jars, the fake tarball, Byte Buddy and the jarsigner wrapper, built once."""
    base = tmp_path_factory.mktemp("pin-world")
    keytool, jarsigner = java_tool("keytool"), java_tool("jarsigner")
    keys = base / "keys"
    keys.mkdir()

    def keypair(alias: str, dn: str, *extensions: str) -> None:
        subprocess.run(
            [keytool, "-genkeypair", "-keystore", str(keys / "signers.p12"), "-storepass", STOREPASS,
             "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", dn, "-validity", "3650", *extensions],
            check=True, capture_output=True,
        )

    keypair("eclipse", ECLIPSE_DN)
    keypair("impostor", ECLIPSE_DN)
    keypair("other", OTHER_DN)
    # Trusted and named for Eclipse, but its certificate may not sign code.
    keypair("server", ECLIPSE_DN, "-ext", "ExtendedKeyUsage=serverAuth")
    for alias in ("eclipse", "other", "server"):  # the impostor stays untrusted
        cert = keys / f"{alias}.cer"
        subprocess.run([keytool, "-exportcert", "-keystore", str(keys / "signers.p12"), "-storepass", STOREPASS,
                        "-alias", alias, "-file", str(cert)], check=True, capture_output=True)
        subprocess.run([keytool, "-importcert", "-noprompt", "-keystore", str(keys / "trust.p12"),
                        "-storepass", STOREPASS, "-alias", alias, "-file", str(cert)], check=True, capture_output=True)

    wrapper = base / "jarsigner-test-trust"
    wrapper.write_text(
        f'#!/bin/sh\nexec "{jarsigner}" -keystore "{keys / "trust.p12"}" -storepass {STOREPASS} "$@"\n'
    )
    wrapper.chmod(0o755)

    tree = base / "tree"
    plugins = tree / "plugins"
    plugins.mkdir(parents=True)

    def signed(name: str, alias: str) -> None:
        jar = make_jar(plugins / name, name)
        subprocess.run([jarsigner, "-keystore", str(keys / "signers.p12"), "-storepass", STOREPASS, str(jar), alias],
                       check=True, capture_output=True)

    signed("org.eclipse.signed_1.0.0.jar", "eclipse")
    signed("org.eclipse.impostor_1.0.0.jar", "impostor")
    signed("org.other.signed_1.0.0.jar", "other")
    signed("org.eclipse.serverauth_1.0.0.jar", "server")
    make_jar(plugins / "org.example.lib_2.0.0.jar", "lib", ("org.example", "lib", "2.0.0"))
    make_jar(plugins / "org.example.sha1only_3.0.0.jar", "sha1only")
    make_jar(plugins / "org.eclipse.m2e.core_1.0.0.jar", "m2e", ("org.eclipse.m2e", "org.eclipse.m2e.core", "1.0.0"))
    (tree / "bin").mkdir()
    (tree / "bin" / "jdtls.py").write_text("if java_major_version < 21:\n    raise Exception('needs 21')\n")
    (tree / "config_linux").mkdir()
    (tree / "config_linux" / "config.ini").write_text("osgi.bundles=...\n")

    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for path in sorted(tree.rglob("*")):
            archive.add(path, arcname=str(path.relative_to(tree)), recursive=False)
    tarball = buffer.getvalue()

    files = {str(p.relative_to(tree)): p.read_bytes() for p in tree.rglob("*") if p.is_file()}
    byte_buddy = make_jar(base / f"byte-buddy-{BB_VERSION}.jar", "bytebuddy").read_bytes()
    return {"wrapper": str(wrapper), "plugins": plugins, "tarball": tarball, "files": files, "byte_buddy": byte_buddy, "base": base}


def default_routes(world) -> dict[str, bytes]:
    files = world["files"]
    lib = files["plugins/org.example.lib_2.0.0.jar"]
    sha1only = files["plugins/org.example.sha1only_3.0.0.jar"]
    m2e = files["plugins/org.eclipse.m2e.core_1.0.0.jar"]
    bb = world["byte_buddy"]
    lib_url = "/maven2/org/example/lib/2.0.0/lib-2.0.0.jar"
    sha1only_url = "/maven2/org/example/sha1only/3.0.0/sha1only-3.0.0.jar"
    m2e_url = "/maven2/org/eclipse/m2e/org.eclipse.m2e.core/1.0.0/org.eclipse.m2e.core-1.0.0.jar"
    bb_url = f"/maven2/net/bytebuddy/byte-buddy/{BB_VERSION}/byte-buddy-{BB_VERSION}.jar"
    return {
        f"/milestones/{VERSION}/latest.txt": f"{TARBALL}\n".encode(),
        f"/milestones/{VERSION}/{TARBALL}": world["tarball"],
        f"/milestones/{VERSION}/{TARBALL}.sha256": sha(world["tarball"]).encode(),
        # On Central with a strong published hash, found through its own pom.properties.
        f"{lib_url}.sha1": sha(lib, "sha1").encode(),
        f"{lib_url}.sha512": f"{sha(lib, 'sha512')}  lib-2.0.0.jar\n".encode(),
        # On Central with only a SHA-1, found through the search index.
        f"{sha1only_url}": sha1only,
        f"{sha1only_url}.sha1": sha(sha1only, "sha1").encode(),
        # m2e's pom.properties names a Central artifact, but a different build of it.
        f"{m2e_url}.sha1": sha(b"another build", "sha1").encode(),
        f"{bb_url}": bb,
        f"{bb_url}.sha1": sha(bb, "sha1").encode(),
        f"{bb_url}.sha256": sha(bb).encode(),
        f"{bb_url}.asc": b"-----BEGIN PGP SIGNATURE-----\nfake\n-----END PGP SIGNATURE-----\n",
        "search": {sha(sha1only, "sha1"): [{"g": "org.example", "a": "sha1only", "v": "3.0.0", "ec": [".jar"]}]},
    }


class FakeServer:
    def __init__(self, routes):
        self.routes = routes
        self.requests: list[str] = []
        self.rate_limit_once: set[str] = set()
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                url = urllib.parse.urlsplit(self.path)
                server.requests.append(url.path)
                if url.path in server.rate_limit_once:
                    server.rate_limit_once.discard(url.path)
                    self.send_response(429)
                    self.send_header("Retry-After", "0")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if url.path == "/search":
                    query = urllib.parse.parse_qs(url.query)["q"][0].removeprefix("1:")
                    docs = server.routes["search"].get(query, [])
                    body = json.dumps({"response": {"numFound": len(docs), "docs": docs}}).encode()
                elif url.path in server.routes:
                    body = server.routes[url.path]
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_port}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture
def server(world):
    fake = FakeServer(default_routes(world))
    yield fake
    fake.close()


@pytest.fixture
def pom(tmp_path):
    path = tmp_path / "pom.xml"
    path.write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        f"<properties><byte-buddy.version>{BB_VERSION}</byte-buddy.version></properties></project>"
    )
    return path


def run_pin(world, server, pom, out: Path, *extra: str) -> int:
    return pin.main([
        VERSION, *extra,
        "--pom", str(pom),
        "--output", str(out / "pin.json"),
        "--report", str(out / "report.md"),
        "--jarsigner", world["wrapper"],
        "--gnupghome", str(out / "no-gnupg-home"),
        "--eclipse-url", f"{server.url}/milestones",
        "--central-url", f"{server.url}/maven2",
        "--central-search-url", f"{server.url}/search",
    ])


def report_row(report: str, jar: str) -> str:
    return next(line for line in report.splitlines() if line.startswith(f"| `plugins/{jar}`"))


# --- the whole run ----------------------------------------------------------------


def test_writes_the_pin(world, server, pom, tmp_path):
    assert run_pin(world, server, pom, tmp_path, "--release", "2") == 0
    document = json.loads((tmp_path / "pin.json").read_text())

    assert document["release"] == f"{VERSION}-2"
    assert document["jdtls"] == {
        "version": VERSION,
        "url": f"{server.url}/milestones/{VERSION}/{TARBALL}",
        "sha256": sha(world["tarball"]),
        "files": {path: sha(data) for path, data in sorted(world["files"].items())},
    }
    assert list(document["jdtls"]["files"]) == sorted(world["files"])
    assert document["java"] == {"minimum": 21}
    assert document["byte_buddy"] == {
        "version": BB_VERSION,
        "url": f"{server.url}/maven2/net/bytebuddy/byte-buddy/{BB_VERSION}/byte-buddy-{BB_VERSION}.jar",
        "sha256": sha(world["byte_buddy"]),
    }


def test_classifies_each_jar(world, server, pom, tmp_path):
    assert run_pin(world, server, pom, tmp_path) == 0
    report = (tmp_path / "report.md").read_text()

    assert "**`plugins/`: 1 of 7 jars are signed by the Eclipse Foundation**" in report
    assert report_row(report, "org.eclipse.signed_1.0.0.jar").endswith("| Eclipse-signed |")
    # Same name as Eclipse's certificate, but self-signed and untrusted.
    impostor = report_row(report, "org.eclipse.impostor_1.0.0.jar")
    assert "signed, not accepted" in impostor and impostor.endswith("| tarball checksum only |")
    assert report_row(report, "org.other.signed_1.0.0.jar").endswith("| tarball checksum only |")
    serverauth = report_row(report, "org.eclipse.serverauth_1.0.0.jar")
    assert "signed, not accepted" in serverauth and serverauth.endswith("| tarball checksum only |")
    assert "ExtendedKeyUsage extension doesn't allow code signing" in report

    assert "`org.example:lib:2.0.0` (sha512) | Maven Central |" in report_row(report, "org.example.lib_2.0.0.jar")
    assert "`org.example:sha1only:3.0.0` (bytes) | Maven Central |" in report_row(report, "org.example.sha1only_3.0.0.jar")
    assert report_row(report, "org.eclipse.m2e.core_1.0.0.jar") == (
        "| `plugins/org.eclipse.m2e.core_1.0.0.jar` | unsigned | no | tarball checksum only |"
    )
    assert "**2 jars are byte-identical to Maven Central** artifacts." in report

    tarball_only = report.split("## Trusted only through the tarball's checksum", 1)[1]
    for path in ("plugins/org.eclipse.impostor_1.0.0.jar", "plugins/org.other.signed_1.0.0.jar",
                 "plugins/org.eclipse.serverauth_1.0.0.jar",
                 "plugins/org.eclipse.m2e.core_1.0.0.jar", "bin/jdtls.py", "config_linux/config.ini"):
        assert f"- `{path}`" in tarball_only
    assert "org.eclipse.signed_1.0.0.jar" not in tarball_only
    assert "org.example.lib_2.0.0.jar" not in tarball_only

    assert f"SHA-256: `{sha(world['tarball'])}`, equal to Eclipse's published `{TARBALL}.sha256`" in report
    assert "Minimum Java: 21" in report
    assert f"Version `{BB_VERSION}`, from the root POM's `byte-buddy.version`." in report
    assert "equal to Maven Central's published `.sha256`, `.sha1`." in report
    assert "PGP signature (`.asc`): not checked: no GnuPG home at" in report


def test_a_second_run_writes_identical_bytes(world, server, pom, tmp_path):
    first, second = tmp_path / "first", tmp_path / "second"
    assert run_pin(world, server, pom, first) == 0
    assert run_pin(world, server, pom, second) == 0
    assert (first / "pin.json").read_bytes() == (second / "pin.json").read_bytes()


def test_cache_is_keyed_by_the_published_sha256(world, server, pom, tmp_path):
    cache = tmp_path / "cache"
    assert run_pin(world, server, pom, tmp_path / "a", "--cache-dir", str(cache)) == 0
    assert run_pin(world, server, pom, tmp_path / "b", "--cache-dir", str(cache)) == 0
    assert server.requests.count(f"/milestones/{VERSION}/{TARBALL}") == 1
    assert (cache / "sha256" / sha(world["tarball"]) / TARBALL).is_file()


def test_rate_limits_are_retried(world, server, pom, tmp_path):
    server.rate_limit_once.update({"/search", f"/milestones/{VERSION}/{TARBALL}"})
    assert run_pin(world, server, pom, tmp_path) == 0
    assert server.requests.count(f"/milestones/{VERSION}/{TARBALL}") == 2
    assert not server.rate_limit_once


# --- failures ---------------------------------------------------------------------


def test_tarball_checksum_mismatch_fails(world, server, pom, tmp_path, capsys):
    server.routes[f"/milestones/{VERSION}/{TARBALL}.sha256"] = b"0" * 64
    assert run_pin(world, server, pom, tmp_path) == 1
    assert "but Eclipse publishes 0000" in capsys.readouterr().err
    assert not (tmp_path / "pin.json").exists()


def test_byte_buddy_checksum_mismatch_fails(world, server, pom, tmp_path, capsys):
    server.routes[f"/maven2/net/bytebuddy/byte-buddy/{BB_VERSION}/byte-buddy-{BB_VERSION}.jar"] = b"tampered"
    assert run_pin(world, server, pom, tmp_path) == 1
    assert f"byte-buddy-{BB_VERSION}.jar: sha256 is" in capsys.readouterr().err
    assert not (tmp_path / "pin.json").exists()


def test_latest_txt_must_name_a_release_tarball(world, server, pom, tmp_path, capsys):
    server.routes[f"/milestones/{VERSION}/latest.txt"] = b"jdt-language-server-latest.tar.gz\n"
    assert run_pin(world, server, pom, tmp_path) == 1
    assert "not a JDT LS 9.9.9 release tarball" in capsys.readouterr().err


def test_missing_byte_buddy_property_fails(world, server, tmp_path, capsys):
    pom = tmp_path / "pom.xml"
    pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><properties/></project>')
    assert run_pin(world, server, pom, tmp_path) == 1
    assert "has no byte-buddy.version property" in capsys.readouterr().err


def test_tarball_with_a_symlink_is_refused(tmp_path):
    archive_path = tmp_path / "t.tar.gz"
    with tarfile.open(archive_path, "w:gz") as archive:
        link = tarfile.TarInfo("bin/jdtls")
        link.type, link.linkname = tarfile.SYMTYPE, "jdtls.py"
        archive.addfile(link)
    with pytest.raises(pin.PinError, match="neither a file nor a folder"):
        pin.unpack(archive_path, tmp_path / "out")


@pytest.mark.parametrize("argv", [
    ["1.63"],
    ["1.63.0", "--release", "0"],
    ["1.63.0", "--eclipse-url", "https://download.eclipse.org/jdtls/snapshots"],
])
def test_bad_arguments_are_refused(argv):
    with pytest.raises(SystemExit) as raised:
        pin.parse_args(argv)
    assert raised.value.code == 2


# --- parsing jarsigner's output ---------------------------------------------------

# Trimmed from `jarsigner -verify -verbose:summary -certs` on JDT LS 1.63.0's
# org.eclipse.m2e.workspace.cli_0.4.0.jar (JDK 25.0.1).
TSA_WARNING_OUTPUT = """\
s       1897 Mon May 18 09:22:08 CDT 2015 META-INF/MANIFEST.MF

      >>> Signer
      X.509, CN="Eclipse Foundation, Inc.", OU=IT, O="Eclipse Foundation, Inc.", L=Ottawa, ST=Ontario, C=CA
      Signature algorithm: SHA256withRSA, 2048-bit RSA key
      [certificate is valid from 3/3/15, 6:00 PM to 3/8/18, 6:00 AM]
      X.509, CN=DigiCert Assured ID Root CA, OU=www.digicert.com, O=DigiCert Inc, C=US
      [trusted certificate]
      >>> TSA
      X.509, CN=GeoTrust Timestamping Signer 1, O=GeoTrust Inc, C=US
      [certificate expired on 10/30/17, 6:59 PM]

jar verified.

Warning:
This jar contains entries whose TSA certificate chain is invalid. Reason: PKIX path building failed
The timestamp has expired.
"""


def test_a_tsa_chain_warning_alone_does_not_disqualify():
    signature = pin.parse_jarsigner(0, TSA_WARNING_OUTPUT, "x.jar")
    assert signature.status == "eclipse"
    assert signature.signers == (
        'CN="Eclipse Foundation, Inc.", OU=IT, O="Eclipse Foundation, Inc.", L=Ottawa, ST=Ontario, C=CA',
    )
    assert "The timestamp has expired." in signature.notes


@pytest.mark.parametrize("warning", [
    "This jar contains entries whose certificate chain is invalid. Reason: PKIX path building failed",
    "This jar contains entries whose signer certificate is self-signed.",
    "This jar contains unsigned entries which have not been integrity-checked.",
    "This jar contains entries whose signer certificate has expired.",
    # The JDK's three "may not sign code" warnings (JDK 25 jarsigner resources).
    "This jar contains entries whose signer certificate's KeyUsage extension doesn't allow code signing.",
    "This jar contains entries whose signer certificate's ExtendedKeyUsage extension doesn't allow code signing.",
    "This jar contains entries whose signer certificate's NetscapeCertType extension doesn't allow code signing.",
])
def test_a_signer_problem_disqualifies(warning):
    output = TSA_WARNING_OUTPUT.replace("The timestamp has expired.", warning)
    assert pin.parse_jarsigner(0, output, "x.jar").status == "other"


def test_jarsigner_output_is_english_whatever_the_jvm_locale(world, monkeypatch):
    # A German JVM would print "JAR verifiziert." and "Von ... signiert"; the tool forces English.
    monkeypatch.setenv("JAVA_TOOL_OPTIONS", "-Duser.language=de -Duser.country=DE")
    jar = world["plugins"] / "org.eclipse.signed_1.0.0.jar"
    signature = pin.verify_signature(world["wrapper"], jar)
    assert signature.status == "eclipse"
    assert [pin.dn_attributes(dn)["O"] for dn in signature.signers] == ["Eclipse Foundation"]

    # Control: without the override, this JVM really does answer in German.
    monkeypatch.setattr(pin, "JARSIGNER_LOCALE", ())
    with pytest.raises(pin.PinError, match="Signaturgeber"):
        pin.verify_signature(world["wrapper"], jar)


def test_a_failed_verification_is_an_error():
    with pytest.raises(pin.PinError, match="jarsigner -verify failed"):
        pin.parse_jarsigner(1, "jarsigner: java.lang.SecurityException: SHA-256 digest error for x.class", "x.jar")


def test_dn_attributes_honour_quotes():
    assert pin.dn_attributes('CN="Eclipse.org Foundation, Inc.", O="Eclipse.org Foundation, Inc.", C=CA') == {
        "CN": "Eclipse.org Foundation, Inc.",
        "O": "Eclipse.org Foundation, Inc.",
        "C": "CA",
    }
