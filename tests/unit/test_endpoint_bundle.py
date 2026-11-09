# SPDX-License-Identifier: Apache-2.0
"""The endpoint bundle and the build cache it compiles against (design §11 "Inside JDT LS").

- The endpoint's pom.xml compiles against jars of the pinned JDT LS only, by the paths
  tools/jdtls_cache.py fills: the folder is pin.json's tarball SHA-256, and each jar is a pinned file.
- The built bundle imports only packages that the pinned JDT LS exports, in the versions it
  exports, or that the JDK's boot layer exports (JDT LS starts with --add-modules=ALL-SYSTEM).
- tools/jdtls_cache.py downloads and verifies the tarball once, then works offline, and fails
  loudly, naming the cache, on a changed file or when it can't download.

The bundle tests need `mise run build` first; `mise run validate` builds before it tests.
"""

import hashlib
import http.server
import importlib.util
import io
import json
import re
import shutil
import subprocess
import tarfile
import threading
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
BUNDLE = ROOT / "endpoint/target/fractal-jdtls-endpoint.jar"
PIN = json.loads((ROOT / "pin.json").read_text(encoding="utf-8"))
CACHE = ROOT / ".cache/jdtls" / PIN["jdtls"]["sha256"]
# Packages the JDK's boot layer provides through the system bundle, and the module of each.
JDK_PACKAGES = {"com.sun.net.httpserver": "jdk.httpserver", "javax.xml": "java.xml", "javax.xml.parsers": "java.xml",
                "org.w3c.dom": "java.xml", "org.xml.sax": "java.xml"}  # fmt: skip

spec = importlib.util.spec_from_file_location("fractal_jdtls_cache", ROOT / "tools" / "jdtls_cache.py")
jdtls_cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(jdtls_cache)


def manifest(jar) -> dict[str, str]:
    with zipfile.ZipFile(jar) as archive:
        text = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    joined = re.sub(r"\r?\n ", "", text)
    return dict(line.split(": ", 1) for line in joined.splitlines() if ": " in line)


def clauses(header: str) -> list[tuple[str, dict[str, str]]]:
    """An OSGi header's clauses: (name, attributes and directives), with quoted commas kept."""
    parts, current, quoted = [], [], False
    for char in header:
        if char == '"':
            quoted = not quoted
        if char == "," and not quoted:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    result = []
    for part in filter(str.strip, parts):
        name, *params = [p.strip() for p in part.split(";")]
        attributes = {}
        for param in params:
            key, _, value = param.partition("=")
            attributes[key.strip().rstrip(":")] = value.strip().strip('"')
        result.append((name, attributes))
    return result


def version_tuple(version: str) -> tuple:
    numbers = (version.split(".") + ["0", "0", "0"])[:3]
    return tuple(int(n) for n in numbers)


def in_range(version: str, spec: str) -> bool:
    if not spec:
        return True
    if spec[0] not in "[(":
        return version_tuple(version) >= version_tuple(spec)
    low, high = spec[1:-1].split(",")
    v = version_tuple(version)
    above = v >= version_tuple(low) if spec[0] == "[" else v > version_tuple(low)
    below = v <= version_tuple(high) if spec[-1] == "]" else v < version_tuple(high)
    return above and below


def pinned_exports() -> dict[str, list[tuple[str, str]]]:
    """Each package the pinned JDT LS's bundles export, with (bundle, version) for each exporter."""
    assert CACHE.is_dir(), f"the build cache {CACHE} is empty; run `mise run build` first"
    exports: dict[str, list[tuple[str, str]]] = {}
    for jar in sorted((CACHE / "plugins").glob("*.jar")):
        attributes = manifest(jar)
        bundle = attributes.get("Bundle-SymbolicName", jar.name).split(";")[0]
        for package, params in clauses(attributes.get("Export-Package", "")):
            exports.setdefault(package, []).append((bundle, params.get("version", "0.0.0")))
    return exports


# --- the POM -------------------------------------------------------------------------


def endpoint_pom():
    return ET.parse(ROOT / "endpoint/pom.xml").getroot()


def test_the_endpoint_compiles_against_pinned_jars_from_the_build_cache():
    pom = endpoint_pom()
    plugins = pom.findtext("m:properties/m:jdtls.plugins", namespaces=POM_NS)
    assert plugins == "${project.basedir}/../.cache/jdtls/" + PIN["jdtls"]["sha256"] + "/plugins", (
        f"endpoint/pom.xml's jdtls.plugins is {plugins}, but pin.json's tarball is {PIN['jdtls']['sha256']}; "
        "point it at .cache/jdtls/<that SHA-256>/plugins"
    )
    system = [d for d in pom.findall("m:dependencies/m:dependency", POM_NS) if d.findtext("m:scope", namespaces=POM_NS) == "system"]
    assert system, "the endpoint has no pinned jars to compile against"
    for dependency in system:
        path = dependency.findtext("m:systemPath", namespaces=POM_NS)
        assert path.startswith("${jdtls.plugins}/"), path
        name = "plugins/" + path.removeprefix("${jdtls.plugins}/")
        assert name in PIN["jdtls"]["files"], f"endpoint/pom.xml compiles against {name}, which pin.json doesn't pin"
    others = [d.findtext("m:artifactId", namespaces=POM_NS) for d in pom.findall("m:dependencies/m:dependency", POM_NS)
              if d.findtext("m:scope", namespaces=POM_NS) not in ("system", "test")]  # fmt: skip
    assert not others, f"the endpoint has dependencies that would come from Maven or be bundled: {others}"


# --- the bundle ----------------------------------------------------------------------


def test_the_bundle_imports_only_what_the_pinned_jdtls_or_the_jdk_exports():
    assert BUNDLE.is_file(), f"{BUNDLE.relative_to(ROOT)} isn't built; run `mise run build` first"
    attributes = manifest(BUNDLE)
    assert "Require-Bundle" not in attributes, "the bundle should import packages, not require bundles"
    exports = pinned_exports()
    imports = clauses(attributes["Import-Package"])
    assert imports
    problems = []
    for package, params in imports:
        if package in JDK_PACKAGES:
            module = JDK_PACKAGES[package]
            described = subprocess.run(["java", "--describe-module", module], capture_output=True, text=True)
            if f"exports {package}" not in described.stdout:
                problems.append(f"{package}: the JDK's {module} doesn't export it")
            continue
        exporters = exports.get(package)
        if not exporters:
            problems.append(f"{package}: no bundle of the pinned JDT LS exports it")
        elif not any(in_range(version, params.get("version", "")) for _, version in exporters):
            problems.append(f"{package} {params.get('version')}: the pinned JDT LS exports {exporters}")
    assert not problems, "the bundle imports what the pinned JDT LS doesn't export:\n  " + "\n  ".join(problems)


def test_the_built_manifest_is_the_source_manifest():
    """The jar plugin always writes the jar (`forceCreation`): a change to the manifest alone reaches the bundle."""
    assert BUNDLE.is_file(), f"{BUNDLE.relative_to(ROOT)} isn't built; run `mise run build` first"
    text = (ROOT / "endpoint/src/main/manifest/MANIFEST.MF").read_text(encoding="utf-8")
    source = dict(line.split(": ", 1) for line in re.sub(r"\r?\n ", "", text).splitlines() if ": " in line)
    built = manifest(BUNDLE)
    assert {k: built.get(k) for k in source} == source


def test_every_package_the_bundles_classes_use_is_imported():
    """A class that's used but not imported fails only when it loads, inside JDT LS (T002.09 hit it): jdeps finds them all."""
    assert BUNDLE.is_file(), f"{BUNDLE.relative_to(ROOT)} isn't built; run `mise run build` first"
    jdeps = Path(shutil.which("java")).resolve().parent / "jdeps"
    listed = subprocess.run([str(jdeps), "-verbose:package", str(BUNDLE)], capture_output=True, text=True, check=True).stdout
    used = {m.group(1) for m in re.finditer(r"^\s+org\.fractal\.jdtls\.endpoint\s+->\s+(\S+)", listed, re.M)}
    assert "org.eclipse.jdt.ls.core.internal" in used, listed
    needed = {p for p in used if not p.startswith("java.") and not p.startswith("org.fractal.")}
    imported = {package for package, _ in clauses(manifest(BUNDLE)["Import-Package"])}
    assert needed - imported == set(), "used but not imported: " + ", ".join(sorted(needed - imported))


def test_the_bundle_is_the_endpoint_with_its_contract_and_nothing_else():
    attributes = manifest(BUNDLE)
    assert attributes["Bundle-SymbolicName"] == "org.fractal.jdtls.endpoint"
    assert attributes["Bundle-Activator"] == "org.fractal.jdtls.endpoint.Activator"
    assert attributes["SPDX-License-Identifier"] == "Apache-2.0"
    with zipfile.ZipFile(BUNDLE) as archive:
        names = archive.namelist()
        assert archive.read("openapi.json") == (ROOT / "endpoint/src/main/resources/openapi.json").read_bytes()
    classes = [n for n in names if n.endswith(".class")]
    assert "org/fractal/jdtls/endpoint/Activator.class" in classes
    assert all(n.startswith("org/fractal/jdtls/endpoint/") for n in classes), "the bundle carries third-party classes"
    assert set(names) - set(classes) == {"META-INF/", "META-INF/MANIFEST.MF", "openapi.json", "org/", "org/fractal/",
                                         "org/fractal/jdtls/", "org/fractal/jdtls/endpoint/"}  # fmt: skip


def test_osgi_header_parsing():
    assert clauses('a;version="[1,2)",b.c;uses:="x,y";version="2.0",d') == [
        ("a", {"version": "[1,2)"}), ("b.c", {"uses": "x,y", "version": "2.0"}), ("d", {})
    ]  # fmt: skip
    assert in_range("2.14.0", "[2.11,3)") and not in_range("3.0.0", "[2.11,3)") and in_range("1.10", "1.10")


# --- the build cache -----------------------------------------------------------------

FILES = {"plugins/a_1.0.jar": b"a jar\n", "plugins/b_2.0.jar": b"b jar\n" * 100, "config_linux/config.ini": b"x=1\n"}


def tarball(files) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, data in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.size, info.mode = len(data), 0o644
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


class Server:
    def __init__(self, body: bytes):
        server = self
        self.requests = 0

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                server.requests += 1
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_port}/jdt-language-server-9.9.9.tar.gz"
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def fake_repo(tmp_path, url, data: bytes, files=FILES) -> Path:
    repo = tmp_path / "repo"
    (repo / "bin").mkdir(parents=True)
    shutil.copy(ROOT / "bin/install.py", repo / "bin/install.py")
    pin = json.loads(json.dumps(PIN))
    pin["jdtls"] = {"version": "9.9.9", "url": url, "sha256": hashlib.sha256(data).hexdigest(),
                    "files": {name: hashlib.sha256(content).hexdigest() for name, content in files.items()}}  # fmt: skip
    (repo / "pin.json").write_text(json.dumps(pin))
    return repo


def test_the_cache_is_filled_once_verified_and_then_works_offline(tmp_path):
    data = tarball(FILES)
    server = Server(data)
    try:
        repo = fake_repo(tmp_path, server.url, data)
        cache = tmp_path / "cache"
        target = jdtls_cache.fill(repo, cache)
        assert target == cache / hashlib.sha256(data).hexdigest()
        assert (target / "plugins/b_2.0.jar").read_bytes() == FILES["plugins/b_2.0.jar"]
        assert sorted(p.name for p in cache.iterdir()) == [target.name]  # no tarball or partial folder left
    finally:
        server.close()
    assert server.requests == 1
    assert jdtls_cache.fill(repo, cache) == target  # offline: the server is gone


def test_an_empty_cache_without_network_is_a_clear_error_naming_the_cache(tmp_path):
    data = tarball(FILES)
    repo = fake_repo(tmp_path, "http://127.0.0.1:9/jdt-language-server-9.9.9.tar.gz", data)
    install = jdtls_cache.installer(repo)
    install.RETRY_DELAYS = ()  # fail at once, not after the installer's retries
    cache = tmp_path / "cache"
    with pytest.raises(jdtls_cache.CacheError) as error:
        jdtls_cache.fill(repo, cache, install)
    message = str(error.value)
    assert f"the build cache {cache} has no JDT LS 9.9.9 yet, and it can't be downloaded" in message
    assert "the first build needs network access; after that, builds work offline" in message
    assert not any(cache.iterdir())


def test_a_changed_file_in_the_cache_is_refused(tmp_path):
    data = tarball(FILES)
    server = Server(data)
    try:
        repo = fake_repo(tmp_path, server.url, data)
        target = jdtls_cache.fill(repo, tmp_path / "cache")
    finally:
        server.close()
    (target / "plugins/a_1.0.jar").write_bytes(b"changed\n")
    (target / "plugins/extra.jar").write_bytes(b"extra\n")
    (target / "config_linux/config.ini").unlink()
    with pytest.raises(jdtls_cache.CacheError) as error:
        jdtls_cache.fill(repo, tmp_path / "cache")
    message = str(error.value)
    assert f"the build cache {target} doesn't match pin.json (3 problems)" in message
    for line in ("missing: config_linux/config.ini", "extra: plugins/extra.jar", "changed: plugins/a_1.0.jar"):
        assert line in message
    assert f"Delete {target} and build again" in message


def test_a_tarball_that_doesnt_match_the_pin_is_refused_and_leaves_nothing(tmp_path):
    data = tarball({**FILES, "plugins/a_1.0.jar": b"other\n"})
    server = Server(data)
    try:
        repo = fake_repo(tmp_path, server.url, data)
        with pytest.raises(jdtls_cache.CacheError, match="doesn't match pin.json after unpacking"):
            jdtls_cache.fill(repo, tmp_path / "cache")
    finally:
        server.close()
    assert not any((tmp_path / "cache").iterdir())
