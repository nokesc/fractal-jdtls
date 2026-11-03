# SPDX-License-Identifier: Apache-2.0
"""bin/jdtls, the launcher (design §5).

Each test builds a small fake install, <tmp>/root/<release>/, with the real bin/jdtls and
bin/install.py and a pin.json and release-files.json that match its files, and a fake
`java` that records how it was started. Nothing touches the real ~/.cache.
"""

import ast
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load(name: str, path: Path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    loader.exec_module(module)
    return module


jdtls = load("fractal_jdtls_launcher", ROOT / "bin" / "jdtls")
install = load("fractal_jdtls_install", ROOT / "bin" / "install.py")
release_zip = load("fractal_jdtls_release_zip", ROOT / "tools" / "release_zip.py")

RELEASE = "9.9.9-1"
BOOT = "org.fractal.jdtls.boot"
LAUNCHER_JAR = "plugins/org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar"
ECLIPSE_FILES = {
    # The stock launcher leaves a mark if anything runs it.
    "bin/jdtls": (b'#!/bin/sh\ntouch "$STOCK_MARK"\n', 0o755),
    "bin/jdtls.py": (b"# stock\n", 0o644),
    "config_linux/config.ini": (b"osgi.bundles=...\n", 0o644),
    "config_linux_arm/config.ini": (b"osgi.bundles=...\n", 0o644),
    LAUNCHER_JAR: (b"equinox launcher\n", 0o644),
    "plugins/org.eclipse.equinox.launcher.gtk.linux.x86_64_1.2.1700.v20260913-1737.jar": (b"fragment\n", 0o644),
    "plugins/org.eclipse.jdt.ls.core_1.63.0.jar": (b"jdt ls\n" * 1000, 0o644),
}

# A fake java. `-version` prints a version line to stderr, as java does. Otherwise it
# records its argv and pid in $FAKE_JAVA_RECORD and echoes stdin to stdout, so a test
# can see that the LSP client's stdin and stdout connect straight to it.
FAKE_JAVA = """#!{python}
import json, os, sys
if sys.argv[1:] == ["-version"]:
    print('openjdk version "{version}" 2025-10-21', file=sys.stderr)
    print("OpenJDK Runtime Environment (build {version})", file=sys.stderr)
    sys.exit(0)
with open(os.environ["FAKE_JAVA_RECORD"], "w") as out:
    json.dump({{"argv": sys.argv, "pid": os.getpid(), "java": {java!r}}}, out)
sys.stdout.buffer.write(sys.stdin.buffer.read())
"""


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_install(root: Path, release: str = RELEASE) -> Path:
    target = root / release
    for name, (data, mode) in ECLIPSE_FILES.items():
        path = target / "eclipse" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(mode)
    (target / "bin").mkdir()
    (target / "lib").mkdir()
    for name in ("jdtls", "install.py"):
        shutil.copy(ROOT / "bin" / name, target / "bin" / name)
        (target / "bin" / name).chmod(0o755)
    (target / "lib" / "fractal-jdtls-agent.jar").write_bytes(b"agent jar\n")
    (target / "lib" / "fractal-jdtls-boot.jar").write_bytes(b"boot jar\n")
    byte_buddy = b"byte buddy jar\n"
    (target / "lib" / "byte-buddy-1.2.3.jar").write_bytes(byte_buddy)
    (target / "LICENSE").write_text("Apache License\n")
    (target / "NOTICE").write_text("Fractal JDT LS\n")
    pin = {
        "release": release,
        "jdtls": {
            "version": "9.9.9",
            "url": "https://download.eclipse.org/jdtls/milestones/9.9.9/jdt-language-server-9.9.9.tar.gz",
            "sha256": "0" * 64,
            "files": {name: sha(data) for name, (data, _) in sorted(ECLIPSE_FILES.items())},
        },
        "java": {"minimum": 21},
        "byte_buddy": {
            "version": "1.2.3",
            "url": "https://repo.maven.apache.org/maven2/net/bytebuddy/byte-buddy/1.2.3/byte-buddy-1.2.3.jar",
            "sha256": sha(byte_buddy),
        },
    }
    (target / "pin.json").write_text(json.dumps(pin, indent=2) + "\n")
    contents = {name: (target / name).read_bytes() for name in install.HASHED_FILES}
    (target / "release-files.json").write_bytes(release_zip.release_files_json(release, contents))
    return target


def fake_java(folder: Path, version: str) -> Path:
    path = folder / "bin" / "java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(FAKE_JAVA.format(python=sys.executable, version=version, java=str(path)))
    path.chmod(0o755)
    return path


def snapshot(folder: Path) -> dict[str, str]:
    """Every file and folder under folder, with each file's hash."""
    return {
        path.relative_to(folder).as_posix(): (sha(path.read_bytes()) if path.is_file() else "dir")
        for path in sorted(folder.rglob("*"))
    }


class World:
    pass


@pytest.fixture
def world(tmp_path, scratch_home):
    w = World()
    w.tmp = tmp_path
    w.target = make_install(tmp_path / "root")
    w.java = fake_java(tmp_path / "jdk25", "25.0.1")
    w.record = tmp_path / "java-record.json"
    w.cwd = tmp_path / "work" / "project"
    w.cwd.mkdir(parents=True)
    w.environ = {
        "PATH": os.defpath,
        "HOME": str(scratch_home),
        "XDG_CACHE_HOME": str(scratch_home / ".cache"),
        "FAKE_JAVA_RECORD": str(w.record),
        "STOCK_MARK": str(tmp_path / "stock-launcher-ran"),
    }
    return w


class Exec:
    """Stands in for os.execv."""

    def __init__(self):
        self.calls = []

    def __call__(self, path, argv):
        self.calls.append((path, list(argv)))

    @property
    def argv(self):
        assert len(self.calls) == 1
        return self.calls[0][1]


def launch(world, *argv, environ=None, cwd=None):
    execv = Exec()
    code = jdtls.main(
        list(argv), release_dir=world.target, environ=environ or world.environ, cwd=str(cwd or world.cwd), execv=execv
    )
    return code, execv


def stock_command(world, java, data, jvm_args=(), extra=(), xml_limits=True, bootdelegation=BOOT, data_arg=None):
    """design §5: the stock bin/jdtls.py command, plus the agent, plus -configuration.

    data is the workspace folder; data_arg, the -data value passed on (default: data).
    """
    eclipse = world.target / "eclipse"
    return (
        [str(java)]
        + (["-Djdk.xml.maxGeneralEntitySizeLimit=0", "-Djdk.xml.totalEntitySizeLimit=0"] if xml_limits else [])
        + [
            "-Declipse.application=org.eclipse.jdt.ls.core.id1",
            "-Dosgi.bundles.defaultStartLevel=4",
            "-Declipse.product=org.eclipse.jdt.ls.core.product",
            "-Dosgi.checkConfiguration=true",
            f"-Dosgi.sharedConfiguration.area={eclipse / 'config_linux'}",
            "-Dosgi.sharedConfiguration.area.readOnly=true",
            "-Dosgi.configuration.cascaded=true",
            "-Xms1G",
            "--add-modules=ALL-SYSTEM",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            f"-javaagent:{world.target / 'lib' / 'fractal-jdtls-agent.jar'}",
            f"-Dorg.osgi.framework.bootdelegation={bootdelegation}",
        ]
        + list(jvm_args)
        + ["-jar", str(eclipse / LAUNCHER_JAR), "-data", str(data if data_arg is None else data_arg)]
        + ["-configuration", str(Path(data) / ".fractal-jdtls" / RELEASE / "configuration")]
        + list(extra)
    )  # fmt: skip


# --- the java command --------------------------------------------------------------


def test_the_java_command_for_representative_arguments(world, capsys):
    data = world.tmp / "ws"
    code, execv = launch(
        world,
        f"--java-executable={world.java}",
        "--jvm-arg=-Xmx2G",
        "--jvm-arg",
        "-Dlog.level=ALL",
        "-data",
        str(data),
        "-Dextra=1",
        "--some-jdtls-flag",
    )
    assert code == 0
    assert execv.calls[0][0] == str(world.java)
    assert execv.argv == stock_command(
        world, world.java, data, jvm_args=["-Xmx2G", "-Dlog.level=ALL"], extra=["-Dextra=1", "--some-jdtls-flag"]
    )
    assert (data / ".fractal-jdtls" / RELEASE / "configuration").is_dir()
    assert capsys.readouterr().out == ""


def test_java_below_24_gets_no_xml_limits(world):
    java21 = fake_java(world.tmp / "jdk21", "21.0.9")
    code, execv = launch(world, "--java-executable", str(java21), "-data", str(world.tmp / "ws"))
    assert code == 0
    assert execv.argv == stock_command(world, java21, world.tmp / "ws", xml_limits=False)


def test_a_relative_data_folder_is_passed_on_unchanged(world):
    # As the stock launcher does; Equinox reads it relative to the working directory.
    code, execv = launch(world, f"--java-executable={world.java}", "-data", "ws")
    assert code == 0
    assert execv.argv == stock_command(world, world.java, world.cwd / "ws", data_arg="ws")
    assert (world.cwd / "ws" / ".fractal-jdtls" / RELEASE / "configuration").is_dir()


# -data values and the folder Equinox makes of each (checked against the pinned Equinox's
# LocationHelper.buildURL; see the T001.05 evidence, review R2). {tmp} and {cwd} are filled in.
FILE_URL_FORMS = [
    ("file:{tmp}/ws", "{tmp}/ws"),
    ("file://{tmp}/ws", "{tmp}/ws"),
    ("file://{tmp}/ws/", "{tmp}/ws"),
    ("file:{tmp}/a%20b/ws", "{tmp}/a b/ws"),
    ("file:{tmp}/a b/ws", "{tmp}/a b/ws"),
    ("file:{tmp}/caf%C3%A9", "{tmp}/café"),
    ("file:{tmp}/x%zz", "{tmp}/x%zz"),
    ("file:ws", "{cwd}/ws"),
    ("file:sub/ws", "{cwd}/sub/ws"),
]


@pytest.mark.parametrize("spec, folder", FILE_URL_FORMS)
def test_a_file_url_data_is_passed_on_unchanged_with_the_configuration_in_its_folder(world, spec, folder):
    spec = spec.format(tmp=world.tmp, cwd=world.cwd)
    folder = Path(folder.format(tmp=world.tmp, cwd=world.cwd))
    code, execv = launch(world, f"--java-executable={world.java}", "-data", spec)
    assert code == 0
    assert execv.argv == stock_command(world, world.java, folder, data_arg=spec)
    assert (folder / ".fractal-jdtls" / RELEASE / "configuration").is_dir()


@pytest.mark.parametrize(
    "spec, message",
    [
        ("@user.home/ws", "Equinox's @ locations aren't supported"),
        ("http://example.com/ws", "only a folder or a file: URL is supported"),
        ("FILE:/tmp/ws", "write the scheme in lower case, file:"),
        ("file:/tmp/ws?x=1", "a file: URL with ? or # isn't supported"),
        ("file:/tmp/ws#x", "a file: URL with ? or # isn't supported"),
    ],
)
def test_data_values_whose_folder_cant_be_checked_are_refused(world, capsys, spec, message):
    code, execv = launch(world, f"--java-executable={world.java}", "-data", spec)
    assert code == 2 and not execv.calls
    assert f"jdtls: error: -data {spec}: {message}" in capsys.readouterr().err


def test_the_client_configuration_area_is_used_as_is(world):
    code, execv = launch(world, f"--java-executable={world.java}", "-data", str(world.tmp / "ws"), "-configuration", "/c")
    assert code == 0
    assert execv.argv.count("-configuration") == 1
    assert execv.argv[-2:] == ["-configuration", "/c"]
    assert not (world.tmp / "ws").exists()


def test_stock_validate_flags_are_accepted_and_the_minimum_still_checked(world, capsys):
    code, execv = launch(world, f"--java-executable={world.java}", "--no-validate-java-version", "-data", str(world.tmp / "ws"))
    assert code == 0
    assert "--no-validate-java-version" not in execv.argv
    java17 = fake_java(world.tmp / "jdk17", "17.0.2")
    code, execv = launch(world, f"--java-executable={java17}", "--no-validate-java-version")
    assert code == 1 and not execv.calls
    assert "needs Java 21 or newer" in capsys.readouterr().err


# --- boot delegation (design §5: merged, never replaced) ---------------------------


@pytest.mark.parametrize(
    "client, merged",
    [
        (["-Dorg.osgi.framework.bootdelegation=sun.*,com.sun.*"], f"sun.*,com.sun.*,{BOOT}"),
        (["-Dorg.osgi.framework.bootdelegation= sun.* , ,javax.*"], f"sun.*,javax.*,{BOOT}"),
        (["-Dorg.osgi.framework.bootdelegation=a.*", "-Dorg.osgi.framework.bootdelegation=b.*"], f"b.*,{BOOT}"),
        ([f"-Dorg.osgi.framework.bootdelegation={BOOT},x.*"], f"{BOOT},x.*"),
        (["-Dorg.osgi.framework.bootdelegation="], BOOT),
        (["-Dorg.osgi.framework.bootdelegation"], BOOT),
    ],
)
def test_the_client_boot_delegation_is_merged_into_one_property(world, client, merged):
    data = world.tmp / "ws"
    code, execv = launch(
        world, f"--java-executable={world.java}", *(f"--jvm-arg={arg}" for arg in client), "--jvm-arg=-Xmx1G", "-data", str(data)
    )
    assert code == 0
    delegations = [arg for arg in execv.argv if arg.startswith("-Dorg.osgi.framework.bootdelegation")]
    assert delegations == [f"-Dorg.osgi.framework.bootdelegation={merged}"]
    assert execv.argv == stock_command(world, world.java, data, jvm_args=["-Xmx1G"], bootdelegation=merged)


# --- Java ----------------------------------------------------------------------------


def test_java_comes_from_the_flag_then_java_home_then_path(world):
    flag = fake_java(world.tmp / "flag", "25.0.1")
    home = fake_java(world.tmp / "home", "25.0.1")
    on_path = fake_java(world.tmp / "path", "25.0.1")
    environ = {**world.environ, "JAVA_HOME": str(world.tmp / "home"), "PATH": f"{on_path.parent}:{os.defpath}"}
    data = ["-data", str(world.tmp / "ws")]

    assert launch(world, f"--java-executable={flag}", *data, environ=environ)[1].argv[0] == str(flag)
    assert launch(world, *data, environ=environ)[1].argv[0] == str(home)
    without_home = {k: v for k, v in environ.items() if k != "JAVA_HOME"}
    assert launch(world, *data, environ=without_home)[1].argv[0] == str(on_path)
    # The stock order: a JAVA_HOME without bin/java falls back to PATH.
    no_java_home = {**environ, "JAVA_HOME": str(world.tmp / "nothing-here")}
    assert launch(world, *data, environ=no_java_home)[1].argv[0] == str(on_path)
    # A bare name for --java-executable is looked up on PATH.
    assert launch(world, "--java-executable=java", *data, environ=without_home)[1].argv[0] == str(on_path)


def test_no_java_anywhere_is_a_clear_error(world, capsys):
    code, execv = launch(world, environ={**world.environ, "PATH": str(world.tmp / "empty")})
    assert code == 1 and not execv.calls
    assert "can't run java on PATH" in capsys.readouterr().err

    code, execv = launch(world, f"--java-executable={world.tmp / 'no' / 'java'}")
    assert code == 1 and not execv.calls
    assert "from --java-executable: no such executable file" in capsys.readouterr().err


@pytest.mark.parametrize("version, major", [("17.0.2", 17), ("20", 20), ("1.8.0_402", 8)])
def test_a_java_below_the_minimum_is_refused_naming_the_minimum(world, capsys, version, major):
    old = fake_java(world.tmp / f"jdk{major}", version)
    code, execv = launch(world, f"--java-executable={old}")
    assert code == 1 and not execv.calls
    err = capsys.readouterr().err
    assert f"jdtls: error: fractal-jdtls {RELEASE} (JDT LS 9.9.9) needs Java 21 or newer, but {old} (from --java-executable) is Java {major}." in err
    assert not (Path(world.environ["XDG_CACHE_HOME"]) / "fractal-jdtls").exists()  # nothing created


def test_a_java_whose_version_cant_be_read_is_refused(world, capsys):
    odd = world.tmp / "odd-java"
    odd.write_text("#!/bin/sh\necho 'no version here' >&2\n")
    odd.chmod(0o755)
    code, execv = launch(world, f"--java-executable={odd}")
    assert code == 1 and not execv.calls
    assert f"can't tell which Java {odd} is" in capsys.readouterr().err


# --- the workspace -------------------------------------------------------------------


def test_default_data_is_keyed_on_the_full_path_of_the_working_directory(world, scratch_home):
    a = world.tmp / "a" / "project"
    b = world.tmp / "b" / "project"
    a.mkdir(parents=True)
    b.mkdir(parents=True)
    link = world.tmp / "link-to-a"
    link.symlink_to(a)

    def data_for(cwd):
        code, execv = launch(world, f"--java-executable={world.java}", cwd=cwd)
        assert code == 0
        return execv.argv[execv.argv.index("-data") + 1]

    ws = scratch_home / ".cache" / "fractal-jdtls" / "ws"
    assert data_for(a) == str(ws / hashlib.sha1(str(a.resolve()).encode()).hexdigest())
    assert data_for(b) == str(ws / hashlib.sha1(str(b.resolve()).encode()).hexdigest())
    assert data_for(a) != data_for(b)  # the stock launcher would give both the same
    assert data_for(link) == data_for(a)  # canonical: the same folder through a link
    assert (Path(data_for(a)) / ".fractal-jdtls" / RELEASE / "configuration").is_dir()


def test_default_data_ignores_a_relative_xdg_cache_home(world, scratch_home):
    code, execv = launch(world, f"--java-executable={world.java}", environ={**world.environ, "XDG_CACHE_HOME": "rel"})
    assert code == 0
    assert execv.argv[execv.argv.index("-data") + 1].startswith(str(scratch_home / ".cache" / "fractal-jdtls" / "ws") + "/")


@pytest.mark.parametrize("form", ["{t}/ws", "file:{t}/ws", "file://{t}/ws", "file:{t}/eclipse/../ws", "{t}"])
def test_data_inside_the_install_is_refused(world, capsys, form):
    spec = form.format(t=world.target)
    code, execv = launch(world, f"--java-executable={world.java}", "-data", spec)
    assert code == 2 and not execv.calls
    assert f"-data {spec} is inside the install" in capsys.readouterr().err
    assert not (world.target / "ws").exists()


def test_relative_data_resolving_inside_the_install_is_refused(world, capsys):
    code, execv = launch(world, f"--java-executable={world.java}", "-data", "file:ws", cwd=world.target / "eclipse")
    assert code == 2 and not execv.calls
    assert "-data file:ws is inside the install" in capsys.readouterr().err


@pytest.mark.parametrize(
    "form",
    ["{t}/configuration", "{t}/eclipse/configuration", "file:{t}/configuration", "file://{t}/eclipse/configuration", "{t}"],
)
def test_a_client_configuration_inside_the_install_is_refused(world, capsys, form):
    spec = form.format(t=world.target)
    data = world.tmp / "ws"
    code, execv = launch(world, f"--java-executable={world.java}", "-data", str(data), "-configuration", spec)
    assert code == 2 and not execv.calls
    assert f"-configuration {spec} is inside the install {world.target}; nothing may be written there" in capsys.readouterr().err
    assert not data.exists()


def test_any_client_configuration_inside_the_install_is_refused_even_if_a_later_one_isnt(world, capsys):
    argv = ["-configuration", str(world.target / "configuration"), "-configuration", str(world.tmp / "c")]
    code, execv = launch(world, f"--java-executable={world.java}", *argv)
    assert code == 2 and not execv.calls
    assert "is inside the install" in capsys.readouterr().err


def test_a_relative_client_configuration_is_read_from_the_working_directory(world, capsys):
    code, execv = launch(world, f"--java-executable={world.java}", "-configuration", "configuration", cwd=world.target / "eclipse")
    assert code == 2 and not execv.calls
    assert "-configuration configuration is inside the install" in capsys.readouterr().err

    code, execv = launch(world, f"--java-executable={world.java}", "-configuration", "file:conf")
    assert code == 0
    assert execv.argv[-2:] == ["-configuration", "file:conf"]


def test_an_at_location_configuration_is_refused(world, capsys):
    code, execv = launch(world, f"--java-executable={world.java}", "-configuration", "@user.home/conf")
    assert code == 2 and not execv.calls
    assert "-configuration @user.home/conf: Equinox's @ locations aren't supported" in capsys.readouterr().err


def test_an_unwritable_data_folder_is_a_clear_error(world, capsys):
    locked = world.tmp / "locked"
    locked.mkdir(mode=0o500)
    try:
        code, execv = launch(world, f"--java-executable={world.java}", "-data", str(locked / "ws"))
    finally:
        locked.chmod(0o700)
    assert code == 1 and not execv.calls
    assert "can't create the configuration area" in capsys.readouterr().err


# --- verification at launch ----------------------------------------------------------


def edit_append(path: Path):
    path.write_bytes(path.read_bytes() + b"\n")


@pytest.mark.parametrize(
    "damage, reported",
    [
        (lambda t: (t / "eclipse/bin/__pycache__").mkdir(), "extra:   eclipse/bin/__pycache__"),
        (lambda t: (t / "eclipse/configuration").mkdir(), "extra:   eclipse/configuration"),
        (lambda t: edit_append(t / "eclipse" / LAUNCHER_JAR), f"changed: eclipse/{LAUNCHER_JAR}"),
        (lambda t: (t / "lib/byte-buddy-1.2.3.jar").unlink(), "missing: lib/byte-buddy-1.2.3.jar"),
        (lambda t: edit_append(t / "lib/fractal-jdtls-agent.jar"), "changed: lib/fractal-jdtls-agent.jar"),
        (lambda t: edit_append(t / "bin/jdtls"), "changed: bin/jdtls"),
    ],
)
def test_a_launch_is_refused_when_the_install_fails_verification(world, capsys, damage, reported):
    damage(world.target)
    code, execv = launch(world, f"--java-executable={world.java}", "-data", str(world.tmp / "ws"))
    assert code == 1 and not execv.calls
    err = capsys.readouterr().err
    assert f"jdtls: error: {world.target} fails verification" in err
    assert "so JDT LS wasn't started" in err
    assert reported in err
    assert f"delete {world.target} and run install.py" in err
    assert not (world.tmp / "ws").exists()


def test_a_changed_installer_is_refused_before_it_is_imported(world, capsys):
    installer = world.target / "bin" / "install.py"
    installer.write_text(installer.read_text() + "\nraise SystemExit('imported a changed install.py')\n")
    code, execv = launch(world, f"--java-executable={world.java}")
    assert code == 1 and not execv.calls
    assert f"jdtls: error: {installer} doesn't match {world.target / 'release-files.json'}" in capsys.readouterr().err


def test_a_release_folder_that_isnt_installed_is_refused(world, capsys, tmp_path):
    unzipped = tmp_path / "unzipped"
    unzipped.mkdir()
    folder = shutil.copytree(world.target, unzipped / f"fractal-jdtls-{RELEASE}")
    code = jdtls.main([f"--java-executable={world.java}"], release_dir=folder, environ=world.environ, cwd=str(world.cwd), execv=Exec())
    assert code == 1
    assert f"isn't an install of fractal-jdtls {RELEASE}" in capsys.readouterr().err


# --- as a process: stdout, exec and the install --------------------------------------


def run_launcher(world, *argv, stdin=b"", python=None):
    """bin/jdtls as a client runs it: through its #! line, or with a given Python."""
    launcher = world.target / "bin" / "jdtls"
    command = ([python] if python else []) + [str(launcher), *argv]
    environ = {**world.environ, "PATH": f"{Path(sys.executable).parent}:{os.defpath}"}
    return subprocess.run(command, input=stdin, capture_output=True, cwd=world.cwd, env=environ, timeout=60)


MESSAGE = b'Content-Length: 46\r\n\r\n{"jsonrpc":"2.0","id":1,"method":"initialize"}'


@pytest.mark.parametrize("python", [None, sys.executable, "/usr/bin/python3"])
def test_it_replaces_itself_with_java_and_leaves_stdout_to_lsp(world, python):
    if python and not Path(python).exists():
        pytest.skip(f"{python} isn't installed")
    before = snapshot(world.target)
    result = run_launcher(world, f"--java-executable={world.java}", "-data", str(world.tmp / "ws"), stdin=MESSAGE, python=python)
    assert result.returncode == 0, result.stderr.decode()
    # The bytes on stdout are exactly the ones java echoed: the launcher wrote nothing.
    assert result.stdout == MESSAGE
    assert result.stderr == b""
    assert json.loads(world.record.read_text())["argv"] == stock_command(world, world.java, world.tmp / "ws")

    # Nothing was written into the install, not even __pycache__, and it still verifies.
    assert snapshot(world.target) == before
    assert not list(world.target.rglob("__pycache__"))
    assert not Path(world.environ["STOCK_MARK"]).exists()
    pin = install.Pin(world.target / "pin.json")
    release_files = install.ReleaseFiles(world.target / "release-files.json", pin, "")
    assert not install.verify(world.target, pin, release_files)


def test_it_execs_java_in_its_own_process(world):
    launcher = world.target / "bin" / "jdtls"
    environ = {**world.environ, "PATH": f"{Path(sys.executable).parent}:{os.defpath}"}
    process = subprocess.Popen(
        [str(launcher), f"--java-executable={world.java}", "-data", str(world.tmp / "ws")],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=world.cwd, env=environ,
    )  # fmt: skip
    stdout, stderr = process.communicate(MESSAGE, timeout=60)
    assert process.returncode == 0, stderr.decode()
    assert json.loads(world.record.read_text())["pid"] == process.pid
    assert stdout == MESSAGE


@pytest.mark.parametrize(
    "argv, code, message",
    [
        (["--help"], 0, "usage: jdtls"),
        (["--java-executable"], 2, "--java-executable needs a value"),
        (["--java-executable={old}"], 1, "needs Java 21 or newer"),
    ],
)
def test_messages_go_to_stderr_never_stdout(world, argv, code, message):
    old = fake_java(world.tmp / "jdk17", "17.0.2")
    result = run_launcher(world, *(arg.format(old=old) for arg in argv))
    assert result.returncode == code
    assert result.stdout == b""
    assert message in result.stderr.decode()


def test_a_damaged_install_is_refused_as_a_process_with_nothing_on_stdout(world):
    (world.target / "eclipse" / "configuration").mkdir()
    result = run_launcher(world, f"--java-executable={world.java}")
    assert result.returncode == 1
    assert result.stdout == b""
    assert "extra:   eclipse/configuration" in result.stderr.decode()
    assert not world.record.exists()


# --- the script itself ---------------------------------------------------------------


def test_the_launcher_parses_as_python_3_10():
    ast.parse((ROOT / "bin" / "jdtls").read_text(encoding="utf-8"), feature_version=(3, 10))


def test_the_launcher_imports_only_the_standard_library():
    tree = ast.parse((ROOT / "bin" / "jdtls").read_text(encoding="utf-8"))
    imported = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            imported.add(node.module.split(".")[0])
    assert imported - {"__future__"} <= sys.stdlib_module_names

