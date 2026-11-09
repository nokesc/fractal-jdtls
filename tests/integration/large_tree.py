# SPDX-License-Identifier: Apache-2.0
"""A large Maven reactor in its own Git repository, for the import tests (ported from T002.01's spike generator).

`generate(folder)` writes 50 modules of 200 Java files (10,000 files, plus m00's Base) and a parent POM: 51 Maven
projects. Module m00 also holds Base; every other module depends on m00 and on the module before it, and each class
calls Base.touch and uses a class of the module before, so a module m2e doesn't know breaks every module after it
(HTTP-FINDINGS "An import that silently broke"). The tree is committed to its own repository, so the agent sees a
worktree. It's written to a scratch folder only, with real pom.xml files, never inside this checkout (design §8).
"""

from pathlib import Path

from scenario import git

CHECKOUT = Path(__file__).resolve().parents[2]
PLUGINS = """\
    <build>
        <pluginManagement>
            <plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.3.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
            </plugins>
        </pluginManagement>
    </build>
"""


def pom(artifact, body, packaging="jar", parent=True):
    head = (
        "    <parent><groupId>org.gen</groupId><artifactId>gen-parent</artifactId><version>1.0-SNAPSHOT</version></parent>\n"
        if parent
        else "    <groupId>org.gen</groupId>\n    <version>1.0-SNAPSHOT</version>\n"
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
        f"    <modelVersion>4.0.0</modelVersion>\n{head}    <artifactId>{artifact}</artifactId>\n"
        f"    <packaging>{packaging}</packaging>\n{body}</project>\n"
    )


def java_class(module: int, index: int) -> str:
    pkg = f"org.gen.m{module:02d}"
    previous = f"org.gen.m{module - 1:02d}.C{module - 1:02d}_{index:03d}" if module > 0 else None
    uses = f"        return new {previous}().work(x) + org.gen.m00.Base.touch(x);\n" if previous else "        return Base.touch(x) + 1;\n"
    return (
        f"// SPDX-License-Identifier: Apache-2.0\npackage {pkg};\n\n"
        f"public class C{module:02d}_{index:03d} {{\n\n"
        "    private int count;\n\n"
        f"    public int work(int x) {{\n{uses}    }}\n\n"
        f"    public int step(int a, int b) {{\n        int sum = a + b;\n        count += sum;\n        return sum * {index + 1};\n    }}\n\n"
        f'    public String label() {{\n        return "C{module:02d}_{index:03d}:" + count;\n    }}\n}}\n'
    )


def generate(folder: Path, modules: int = 50, files: int = 200) -> Path:
    """Write the tree into folder (which mustn't exist) and commit it; returns folder."""
    if folder.resolve().is_relative_to(CHECKOUT):
        raise ValueError(f"{folder} is inside this checkout; generate the tree in a scratch folder")
    folder.mkdir(parents=True)
    names = [f"m{m:02d}" for m in range(modules)]
    body = (
        "    <properties>\n        <maven.compiler.release>25</maven.compiler.release>\n"
        "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n    </properties>\n"
        "    <modules>\n" + "".join(f"        <module>{n}</module>\n" for n in names) + "    </modules>\n" + PLUGINS
    )
    (folder / "pom.xml").write_text(pom("gen-parent", body, "pom", parent=False))
    (folder / ".gitignore").write_text("target/\n")
    for m, name in enumerate(names):
        deps = (["m00"] if m > 0 else []) + ([f"m{m - 1:02d}"] if m > 1 else [])
        dep_xml = "".join(
            f"        <dependency><groupId>org.gen</groupId><artifactId>{d}</artifactId><version>1.0-SNAPSHOT</version></dependency>\n"
            for d in deps
        )
        (folder / name).mkdir()
        (folder / name / "pom.xml").write_text(pom(name, f"    <dependencies>\n{dep_xml}    </dependencies>\n" if deps else ""))
        src = folder / name / "src/main/java/org/gen" / name
        src.mkdir(parents=True)
        if m == 0:
            (src / "Base.java").write_text(
                "// SPDX-License-Identifier: Apache-2.0\npackage org.gen.m00;\n\npublic final class Base {\n\n"
                "    public static int touch(int x) {\n        return x * 31;\n    }\n}\n"
            )
        for i in range(files):
            (src / f"C{m:02d}_{i:03d}.java").write_text(java_class(m, i))
    git("init", "-q", "-b", "main", cwd=folder)
    git("add", "-A", cwd=folder)
    git("-c", "user.name=fractal-jdtls tests", "-c", "user.email=tests@localhost", "commit", "-q", "-m", "generated", cwd=folder)
    return folder


def module_names(modules: int = 50) -> list[str]:
    """The tree's module paths as the endpoint lists them: the root POM, then m00 to m49."""
    return ["."] + [f"m{m:02d}" for m in range(modules)]
