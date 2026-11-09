# SPDX-License-Identifier: Apache-2.0
"""Navigation and search by name (design §11 "Addressing code", "Navigation and search"), through HTTP only.

One story on the fixture's main checkout and linked worktrees, in one process:

- each worktree gets a file of its own that uses the contract's `Names` (main's `MainUse`, wt-a's `AUse`, wt-b's
  `BUse`), and wt-b's `Names.java` gains a comment line, so every answer shows which worktree it came from;
- every navigation operation on the fixture's sources: definition, references, implementations, type and call
  hierarchy, outline, symbols and describe, by Java name and by text anchor;
- library and JDK names on the requesting module's classpath: `java.lang.String#join`, and `StringUtils` of
  commons-lang3, which wt-a's harness-app gains as a dependency on disk (the fixed point takes the POM in);
- every addressing error, its problem kept whole, and the request corrected from the problem alone succeeding;
- `Names`, in all three worktrees, asked from each: each answer is the asking worktree's.

Every call is kept in runs/navigation/calls.json, with its time.
"""

import copy
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from endpoint_client import RELEASE, write_settings  # noqa: E402
from scenario import APP, CONTRACT, make_worktrees  # noqa: E402
from sync_scenario import start  # noqa: E402

NAMES = "org.fractal.harness.contract.Names"
STEP = "org.fractal.harness.contract.Step"
GREETER_FILE = "src/main/java/org/fractal/harness/app/Greeter.java"
NAMES_FILE = "src/main/java/org/fractal/harness/contract/Names.java"
JOIN = "java.lang.String#join(java.lang.CharSequence,java.lang.CharSequence...)"
STRING_UTILS = "org.apache.commons.lang3.StringUtils"
USE = ("package org.fractal.harness.app;\n\nimport org.fractal.harness.contract.Names;\n\n"
       "public class {name} {{\n\n    public String use() {{\n        return Names.describe(\"{name}\");\n    }}\n}}\n")  # fmt: skip
USES_LANG3 = ("package org.fractal.harness.app;\n\nimport org.apache.commons.lang3.StringUtils;\n\n"
              "public class Text {\n\n    public String shout(String s) {\n        return StringUtils.upperCase(s);\n    }\n}\n")  # fmt: skip
LANG3 = ("        <dependency>\n            <groupId>org.apache.commons</groupId>\n            <artifactId>commons-lang3</artifactId>\n"
         "            <version>3.17.0</version>\n        </dependency>\n    </dependencies>")  # fmt: skip
DUPLICATE = "package org.fractal.harness.contract;\n\npublic final class Names {\n}\n"
DUPLICATE_PATH = "harness-app/src/main/java/org/fractal/harness/contract/Names.java"
USE_OF = {"main": "MainUse", "wt-a": "AUse", "wt-b": "BUse"}
# T002.06's review: a library interface's method implemented with type arguments (R1); a field and a method that share
# a name, and a generic field (R3, R5); a type's name that its constructor repeats (R4). Written to wt-a only.
BY_LENGTH = ("package org.fractal.harness.app;\n\nimport java.util.Comparator;\n\npublic class ByLength implements Comparator<String> {\n\n"
             "    @Override\n    public int compare(String a, String b) {\n        return Integer.compare(a.length(), b.length());\n    }\n}\n")  # fmt: skip
SIZES = ("package org.fractal.harness.contract;\n\npublic class Sizes {\n\n    private int size;\n\n"
         "    public java.util.Map<String, Integer> entries;\n\n    public int size() {\n        return size;\n    }\n}\n")  # fmt: skip
TWICE = "package org.fractal.harness.contract;\n\npublic class Twice {\n\n    public Twice() {\n    }\n}\n"
SIZES_NAME = "org.fractal.harness.contract.Sizes"


def apply(body: dict, candidate: dict) -> dict:
    """A request with a problem's candidate applied: each JSON Pointer set, creating objects on the way."""
    fixed = copy.deepcopy(body)
    for pointer, value in candidate["set"].items():
        parts = pointer.lstrip("/").split("/")
        node = fixed
        for part in parts[:-1]:
            if not isinstance(node.get(part), dict):
                node[part] = {}
            node = node[part]
        node[parts[-1]] = value
    return fixed


@pytest.fixture(scope="module")
def story(scratch, release_dir, maven_repository, launch_env, jvm_home):
    folder = scratch / "runs" / "navigation"
    folder.mkdir(parents=True)
    wt = make_worktrees(folder / "worktrees")
    settings = write_settings(folder / "settings.json", maven_repository)
    seen = {"worktrees": wt}
    calls = []

    endpoint, c = start(folder, "jdtls", release_dir, jvm_home, launch_env, settings)

    def call(op, name, module, **body):
        request = {"worktree": str(wt / name), "module": module, **body}
        status, value, ms = c.post(f"/v1/{op}", request)
        calls.append({"op": op, "worktree": name, "request": request, "status": status, "ms": ms, "response": value})
        return status, value

    def corrected(op, request, value, pick=0):
        """The same request with the problem's candidate `pick` applied, sent again."""
        fixed = apply(request, value["candidates"][pick])
        name = Path(fixed.pop("worktree")).name
        module = fixed.pop("module")
        return call(op, name, module, **fixed)

    try:
        for name in ("main", "wt-a", "wt-b"):
            (wt / name / APP / f"{USE_OF[name]}.java").write_text(USE.format(name=USE_OF[name]))
        (wt / "wt-a" / APP / "ByLength.java").write_text(BY_LENGTH)
        (wt / "wt-a" / CONTRACT / "Sizes.java").write_text(SIZES)
        (wt / "wt-a" / CONTRACT / "Twice.java").write_text(TWICE)
        names_b = wt / "wt-b" / CONTRACT / "Names.java"
        names_b.write_text(names_b.read_text().replace("package org.fractal.harness.contract;\n", "package org.fractal.harness.contract;\n// wt-b\n", 1))
        for name in ("main", "wt-a", "wt-b"):
            assert c.post("/v1/worktrees/add", {"worktree": str(wt / name)})[0] == 200

        # --- every operation, in wt-a ---------------------------------------------------------
        anchor = {"file": GREETER_FILE, "text": "String clean = Names.normalize(who);", "select": "normalize"}
        seen["definition_anchor"] = call("definition", "wt-a", "harness-app", target=anchor)
        seen["definition_name"] = call("definition", "wt-a", "harness-app", target=f"{NAMES}#describe(int)")
        seen["definition_jdk"] = call("definition", "wt-a", "harness-app", target=JOIN)
        seen["references_normalize"] = call("references", "wt-a", "harness-contract", target=f"{NAMES}#normalize(String)")
        seen["references_from_app"] = call("references", "wt-a", "harness-app", target=f"{NAMES}#normalize(String)")
        seen["references_local"] = call("references", "wt-a", "harness-app", includeDeclaration=True,
                                        target={"file": GREETER_FILE, "text": "int width = 10 - clean.length();", "select": "width"})  # fmt: skip
        seen["references_jdk"] = call("references", "wt-a", "harness-contract", target=JOIN)
        seen["references_limit"] = call("references", "wt-a", "harness-contract", target=NAMES, limit=1)
        seen["implementations_type"] = call("implementations", "wt-a", "harness-contract", target=STEP)
        seen["implementations_method"] = call("implementations", "wt-a", "harness-contract", target=f"{STEP}#run")
        seen["implementations_jdk"] = call("implementations", "wt-a", "harness-contract", target="java.lang.CharSequence")
        seen["type_hierarchy_up"] = call("type-hierarchy", "wt-a", "harness-contract", target="org.fractal.harness.contract.EchoStep",
                                         direction="supertypes")  # fmt: skip
        seen["type_hierarchy_down"] = call("type-hierarchy", "wt-a", "harness-contract", target=STEP, direction="subtypes")
        seen["type_hierarchy_record"] = call("type-hierarchy", "wt-a", "harness-contract", target="org.fractal.harness.contract.ExecutionId",
                                             direction="supertypes")  # fmt: skip
        seen["call_hierarchy_in"] = call("call-hierarchy", "wt-a", "harness-contract", target=f"{NAMES}#normalize", depth=2)
        seen["call_hierarchy_out"] = call("call-hierarchy", "wt-a", "harness-app", target="org.fractal.harness.app.PrintStep#run",
                                          direction="outgoing", depth=2)  # fmt: skip
        seen["outline"] = call("outline", "wt-a", "harness-contract", file=NAMES_FILE)
        seen["symbols_methods"] = call("symbols", "wt-a", "harness-app", query="describe", kinds=["method"])
        seen["symbols_camel"] = call("symbols", "wt-a", "harness-app", query="ES")
        seen["symbols_step"] = call("symbols", "wt-a", "harness-app", query="step", kinds=["class", "interface"])
        seen["describe_type"] = call("describe", "wt-a", "harness-contract", target=NAMES)
        seen["describe_method"] = call("describe", "wt-a", "harness-contract", target=f"{NAMES}#describe(int)")
        seen["describe_nested"] = call("describe", "wt-a", "harness-contract", target=f"{NAMES}$Inner#label()")
        seen["describe_record"] = call("describe", "wt-a", "harness-contract", target="org.fractal.harness.contract.ExecutionId")
        seen["describe_package"] = call("describe", "wt-a", "harness-contract", target="org.fractal.harness.contract")
        seen["describe_jdk"] = call("describe", "wt-a", "harness-contract", target=JOIN)
        seen["describe_jdk_anchor"] = call("describe", "wt-a", "harness-contract",
                                           target={"file": NAMES_FILE, "text": 'String.join(",", values)', "select": "join"})  # fmt: skip
        seen["describe_varargs"] = call("describe", "wt-a", "harness-contract", target=f"{NAMES}#describe(String...)")
        seen["describe_constructor"] = call("describe", "wt-a", "harness-contract", target=f"{NAMES}#Names(int)")
        seen["describe_field"] = call("describe", "wt-a", "harness-contract", target=f"{NAMES}#count")

        # --- T002.06's review -------------------------------------------------------------------
        seen["review_comparator"] = call("implementations", "wt-a", "harness-app", target="java.util.Comparator#compare(java.lang.Object,java.lang.Object)")
        seen["review_field"] = call("describe", "wt-a", "harness-contract", target=f"{SIZES_NAME}#size")
        seen["review_method"] = call("describe", "wt-a", "harness-contract", target=f"{SIZES_NAME}#size()")
        seen["review_generic"] = call("describe", "wt-a", "harness-contract", target=f"{SIZES_NAME}#entries")
        seen["review_outline"] = call("outline", "wt-a", "harness-contract", file="src/main/java/org/fractal/harness/contract/Sizes.java")
        twice = {"file": "src/main/java/org/fractal/harness/contract/Twice.java", "text": "Twice"}
        seen["review_twice"] = call("definition", "wt-a", "harness-contract", target=twice)
        seen["review_twice_applied"] = [call("definition", "wt-a", "harness-contract", target={**twice, **{k.removeprefix("/target/"): v
                                        for k, v in cand["set"].items()}}) for cand in seen["review_twice"][1].get("candidates", [])]  # fmt: skip

        # --- a Maven dependency's type: commons-lang3, added to wt-a's harness-app on disk ------
        (wt / "wt-a" / APP / "Text.java").write_text(USES_LANG3)
        pom = wt / "wt-a" / "harness-app" / "pom.xml"
        pom.write_text(pom.read_text().replace("    </dependencies>", LANG3, 1))
        seen["describe_maven"] = call("describe", "wt-a", "harness-app", target=STRING_UTILS, members=False)
        seen["describe_maven_method"] = call("describe", "wt-a", "harness-app", target=f"{STRING_UTILS}#upperCase(String)")
        seen["references_maven"] = call("references", "wt-a", "harness-app", target=f"{STRING_UTILS}#upperCase(java.lang.String)")
        seen["call_hierarchy_maven"] = call("call-hierarchy", "wt-a", "harness-app", target="org.fractal.harness.app.Text#shout",
                                            direction="outgoing")  # fmt: skip
        seen["maven_elsewhere"] = call("describe", "wt-b", "harness-app", target=STRING_UTILS)
        seen["fast_path"] = call("describe", "wt-a", "harness-app", target=STRING_UTILS, members=False)

        # --- no crossing: the same requests in each worktree ------------------------------------
        for name in ("main", "wt-a", "wt-b"):
            seen[f"cross_references_{name}"] = call("references", name, "harness-contract", target=NAMES)
            seen[f"cross_definition_{name}"] = call("definition", name, "harness-app", target=NAMES)
            seen[f"cross_symbols_{name}"] = call("symbols", name, "harness-app", query="Use", kinds=["class"])
            seen[f"cross_subtypes_{name}"] = call("type-hierarchy", name, "harness-contract", target=STEP, direction="subtypes")
            seen[f"cross_callers_{name}"] = call("call-hierarchy", name, "harness-contract", target=f"{NAMES}#describe(String)")

        # --- every addressing error, and the request corrected from its problem alone ------------
        errors = seen["errors"] = {}

        def error_case(key, op, name, module, pick=0, fix=None, **body):
            request = {"worktree": str(wt / name), "module": module, **body}
            problem = call(op, name, module, **body)
            if fix is None:
                again = corrected(op, request, problem[1], pick) if problem[1].get("candidates") else None
            else:
                again = call(op, name, module, **fix)
            errors[key] = {"request": request, "problem": problem, "corrected": again}

        error_case("invalid-name", "describe", "wt-a", "harness-contract", target=f"{NAMES}#describe(int",
                   fix={"target": f"{NAMES}#describe(int)"})  # fmt: skip
        error_case("invalid-request", "definition", "wt-a", "harness-app", target={"file": GREETER_FILE},
                   fix={"target": {"file": GREETER_FILE, "text": "Names.normalize(who).toUpperCase()", "select": "normalize"}})  # fmt: skip
        error_case("type-not-found", "describe", "wt-a", "harness-contract", target="org.fractal.harness.app.Names")
        error_case("member-not-found", "describe", "wt-a", "harness-contract", target=f"{NAMES}#describe(long)")
        error_case("package-not-found", "describe", "wt-a", "harness-contract", target="org.fractal.harness.api",
                   fix={"target": "org.fractal.harness.contract"})  # fmt: skip
        error_case("ambiguous-member", "references", "wt-a", "harness-contract", target=f"{NAMES}#describe", pick=1)
        error_case("ambiguous-member-classpath", "describe", "wt-a", "harness-app", target="java.lang.Math#max")
        error_case("text-not-found", "definition", "wt-a", "harness-app",
                   target={"file": GREETER_FILE, "text": "String clean=Names.normalize(who);", "select": "normalize"})  # fmt: skip
        error_case("text-not-found-closest", "definition", "wt-a", "harness-app",
                   target={"file": GREETER_FILE, "text": "int width = 20 - clean.length();", "select": "width"},
                   fix={"target": {"file": GREETER_FILE, "text": "int width = 10 - clean.length();", "select": "width"}})  # fmt: skip
        error_case("text-not-unique", "definition", "wt-a", "harness-app", pick=1,
                   target={"file": GREETER_FILE, "text": "Names.normalize(who)", "select": "normalize"})  # fmt: skip
        error_case("file-not-found", "outline", "wt-a", "harness-app", file="src/main/java/org/fractal/harness/app/Greter.java",
                   fix={"file": GREETER_FILE})  # fmt: skip
        error_case("file-in-other-module", "definition", "wt-a", ".",
                   target={"file": f"harness-app/{GREETER_FILE}", "text": "String clean = Names.normalize(who);", "select": "normalize"})  # fmt: skip
        error_case("not-java-source", "outline", "wt-a", "harness-app", file="pom.xml", fix={"file": GREETER_FILE})
        error_case("not-applicable", "implementations", "wt-a", "harness-contract", target=f"{NAMES}#count", fix={"target": STEP})
        error_case("not-applicable-anchor", "describe", "wt-a", "harness-app",
                   target={"file": GREETER_FILE, "text": "return \"Hello, \" + clean"},
                   fix={"target": {"file": GREETER_FILE, "text": "return \"Hello, \" + clean", "select": "clean"}})  # fmt: skip
        # Two modules of wt-b declare org.fractal.harness.contract.Names; the root module, a pom, has no classpath to choose by.
        (wt / "wt-b" / DUPLICATE_PATH).parent.mkdir(parents=True)
        (wt / "wt-b" / DUPLICATE_PATH).write_text(DUPLICATE)
        error_case("ambiguous-type", "describe", "wt-b", ".", target=NAMES, members=False)
        (wt / "wt-b" / DUPLICATE_PATH).unlink()
        seen["after_duplicate"] = call("describe", "wt-b", ".", target=NAMES, members=False)

        seen["log"] = (folder / f"data/.fractal-jdtls/{RELEASE}/endpoint.log").read_text().splitlines()
        assert endpoint.request("POST", "/v1/shutdown", body={})[0] == 202
        seen["exit"] = endpoint.wait_exit(120)
    finally:
        endpoint.stop()
    seen["calls"] = calls
    (folder / "calls.json").write_text(json.dumps(calls, indent=1) + "\n")
    record = {k: v for k, v in seen.items() if k not in ("worktrees", "log", "calls")}
    (folder / "seen.json").write_text(json.dumps(record, indent=1, default=str) + "\n")
    return seen


def ok(pair):
    status, value = pair
    assert status == 200, value
    return value


def places(locations):
    return [(loc.get("module"), loc.get("file"), loc.get("line"), loc.get("element")) for loc in locations]


def test_the_story_ran_and_stopped_cleanly(story):
    assert story["exit"] == 0
    assert [c for c in story["calls"] if c["status"] >= 500] == []


def test_definition_by_anchor_by_name_and_of_a_jdk_method(story):
    (d,) = ok(story["definition_anchor"])["definitions"]
    assert d == {"element": f"{NAMES}#normalize(java.lang.String)", "module": "harness-contract", "file": NAMES_FILE, "line": 38, "column": 26,
                 "endLine": 38, "endColumn": 35, "source": "    public static String normalize(String raw) {"}  # fmt: skip
    (d,) = ok(story["definition_name"])["definitions"]
    assert (d["element"], d["module"], d["file"], d["line"], d["source"]) == (
        f"{NAMES}#describe(int)", "harness-contract", NAMES_FILE, 18, "    public static String describe(int value) {")
    (d,) = ok(story["definition_jdk"])["definitions"]
    assert d == {"element": JOIN, "library": "jrt:/java.base"}


def test_references_cover_the_module_and_its_dependents(story):
    value = ok(story["references_normalize"])
    assert value["element"] == f"{NAMES}#normalize(java.lang.String)"
    assert places(value["references"]) == [
        ("harness-app", GREETER_FILE, 9, "org.fractal.harness.app.Greeter#greet(java.lang.String)"),
        ("harness-app", GREETER_FILE, 15, "org.fractal.harness.app.Greeter#shout(java.lang.String)"),
        ("harness-contract", NAMES_FILE, 7, f"{NAMES}#count"),  # the {@link #normalize(String)} in count's Javadoc
        ("harness-contract", NAMES_FILE, 24, f"{NAMES}#describe(java.lang.String)"),
    ]
    assert value["total"] == 4 and value["truncated"] is False
    # From harness-app, which harness-contract doesn't depend on: only harness-app's uses.
    assert [p[0] for p in places(ok(story["references_from_app"])["references"])] == ["harness-app", "harness-app"]
    local = ok(story["references_local"])
    assert [(r["line"], r["source"].strip()) for r in local["references"]] == [
        (10, "int width = 10 - clean.length();"), (11, 'return "Hello, " + clean + " ".repeat(Math.max(0, width));')]
    jdk = ok(story["references_jdk"])
    assert jdk["element"] == JOIN and places(jdk["references"]) == [("harness-contract", NAMES_FILE, 34, f"{NAMES}#describe(java.lang.String...)")]
    limited = ok(story["references_limit"])
    assert len(limited["references"]) == 1 and limited["truncated"] is True and limited["total"] > 1


def test_implementations_of_an_interface_and_its_method(story):
    value = ok(story["implementations_type"])
    assert [(i["module"], i["element"]) for i in value["implementations"]] == [
        ("harness-app", "org.fractal.harness.app.PrintStep"), ("harness-contract", "org.fractal.harness.contract.EchoStep")]
    value = ok(story["implementations_method"])
    assert value["element"] == f"{STEP}#run(org.fractal.harness.contract.ExecutionId)"
    assert [i["element"] for i in value["implementations"]] == [
        "org.fractal.harness.app.PrintStep#run(org.fractal.harness.contract.ExecutionId)",
        "org.fractal.harness.contract.EchoStep#run(org.fractal.harness.contract.ExecutionId)"]
    assert ok(story["implementations_jdk"])["implementations"] == []


def test_type_hierarchies(story):
    up = ok(story["type_hierarchy_up"])["type"]
    assert up["name"] == "org.fractal.harness.contract.EchoStep" and up["kind"] == "class"
    assert [(s["name"], s["kind"], s["location"].get("library", s["location"].get("module"))) for s in up["supertypes"]] == [
        ("java.lang.Object", "class", "jrt:/java.base"), (STEP, "interface", "harness-contract")]
    down = ok(story["type_hierarchy_down"])["type"]
    assert [s["name"] for s in down["subtypes"]] == ["org.fractal.harness.app.PrintStep", "org.fractal.harness.contract.EchoStep"]
    record = ok(story["type_hierarchy_record"])["type"]
    assert record["kind"] == "record"
    assert [s["name"] for s in record["supertypes"]] == ["java.lang.Record"]
    assert [s["name"] for s in record["supertypes"][0]["supertypes"]] == ["java.lang.Object"]


def test_call_hierarchies_across_modules(story):
    value = ok(story["call_hierarchy_in"])
    assert value["element"] == f"{NAMES}#normalize(java.lang.String)"
    callers = {n["name"]: n for n in value["calls"]}
    assert sorted(callers) == ["org.fractal.harness.app.Greeter#greet(java.lang.String)", "org.fractal.harness.app.Greeter#shout(java.lang.String)",
                               f"{NAMES}#describe(java.lang.String)"]  # fmt: skip
    assert [c["line"] for c in callers[f"{NAMES}#describe(java.lang.String)"]["calls"]] == [24]
    assert [n["name"] for n in callers[f"{NAMES}#describe(java.lang.String)"]["children"]] == [
        "org.fractal.harness.app.AUse#use()", f"{NAMES}#describe(org.fractal.harness.contract.ExecutionId)"]
    out = ok(story["call_hierarchy_out"])
    callees = {n["name"]: n for n in out["calls"]}
    assert set(callees) == {"org.fractal.harness.app.Greeter#greet(java.lang.String)", f"{NAMES}#describe(org.fractal.harness.contract.ExecutionId)"}
    assert callees[f"{NAMES}#describe(org.fractal.harness.contract.ExecutionId)"]["location"]["module"] == "harness-contract"
    assert [c["source"].strip() for c in callees["org.fractal.harness.app.Greeter#greet(java.lang.String)"]["calls"]] == [
        "return greeter.greet(Names.describe(id));"]


def test_outline_and_symbols(story):
    value = ok(story["outline"])
    (names,) = value["symbols"]
    assert (names["name"], names["kind"], names["signature"], names["line"]) == (NAMES, "class", "public final class Names", 5)
    assert [(c["name"], c["kind"], c["line"]) for c in names["children"]] == [
        (f"{NAMES}#count", "field", 8),
        (f"{NAMES}#width", "field", 10),
        (f"{NAMES}#Names(int)", "constructor", 13),
        (f"{NAMES}#describe(int)", "method", 18),
        (f"{NAMES}#describe(java.lang.String)", "method", 23),
        (f"{NAMES}#describe(org.fractal.harness.contract.ExecutionId)", "method", 28),
        (f"{NAMES}#describe(java.lang.String...)", "method", 33),
        (f"{NAMES}#normalize(java.lang.String)", "method", 38),
        (f"{NAMES}#pad(java.lang.String)", "method", 44),
        (f"{NAMES}.Inner", "class", 49),
    ]
    assert names["children"][-1]["children"][0]["name"] == f"{NAMES}.Inner#label()"
    assert [s["name"] for s in ok(story["symbols_methods"])["symbols"]] == [
        f"{NAMES}#describe(int)", f"{NAMES}#describe(java.lang.String)", f"{NAMES}#describe(java.lang.String...)",
        f"{NAMES}#describe(org.fractal.harness.contract.ExecutionId)"]
    assert "org.fractal.harness.contract.EchoStep" in [s["name"] for s in ok(story["symbols_camel"])["symbols"]]
    assert [s["name"] for s in ok(story["symbols_step"])["symbols"]] == [
        STEP, "org.fractal.harness.app.PrintStep", "org.fractal.harness.contract.EchoStep"]


def test_describe_with_signatures_and_javadoc(story):
    value = ok(story["describe_type"])
    assert (value["kind"], value["signature"], value["javadoc"]) == ("class", "public final class Names", "Names for executions and their parts.")
    assert [m["name"] for m in value["members"]][:3] == [f"{NAMES}#count", f"{NAMES}#width", f"{NAMES}#Names(int)"]
    assert value["members"][-1] == {"name": f"{NAMES}.Inner", "kind": "class", "signature": "public static final class Inner"}
    method = ok(story["describe_method"])
    assert method["signature"] == "public static String describe(int value)"
    assert method["javadoc"] == "The name of a number.\n\n* **Parameters:**\n  * **value**", "JDT LS's Markdown, with its parameter list"
    assert ok(story["describe_nested"])["element"] == f"{NAMES}.Inner#label()"
    record = ok(story["describe_record"])
    assert (record["kind"], record["signature"]) == ("record", "public record ExecutionId(String value)")
    package = ok(story["describe_package"])
    assert package["kind"] == "package" and f"{STEP}" in [m["name"] for m in package["members"]]
    assert ok(story["describe_varargs"])["signature"] == "public static String describe(String... values)"
    assert ok(story["describe_constructor"])["kind"] == "constructor"
    assert ok(story["describe_field"])["signature"] == "public static int count"


def test_jdk_and_maven_names_resolve_on_the_modules_classpath(story):
    jdk = ok(story["describe_jdk"])
    assert jdk["element"] == JOIN and jdk["location"] == {"element": JOIN, "library": "jrt:/java.base"}
    assert jdk["signature"].startswith("public static String join(CharSequence")
    assert ok(story["describe_jdk_anchor"])["element"] == JOIN
    maven = ok(story["describe_maven"])
    assert maven["element"] == STRING_UTILS and maven["kind"] == "class"
    assert maven["location"]["library"].endswith("commons-lang3-3.17.0.jar")
    assert maven["sync"]["pomsUpdated"] == ["harness-app"] and maven["sync"]["skipped"] is False
    upper = ok(story["describe_maven_method"])
    assert upper["element"] == f"{STRING_UTILS}#upperCase(java.lang.String)"
    refs = ok(story["references_maven"])
    assert places(refs["references"]) == [("harness-app", "src/main/java/org/fractal/harness/app/Text.java", 8, "org.fractal.harness.app.Text#shout(java.lang.String)")]
    (callee,) = ok(story["call_hierarchy_maven"])["calls"]
    assert callee["name"] == f"{STRING_UTILS}#upperCase(java.lang.String)" and "library" in callee["location"]
    status, value = story["maven_elsewhere"]
    assert status == 404 and value["code"] == "type-not-found", "wt-b's harness-app has no commons-lang3"
    assert ok(story["fast_path"])["sync"]["skipped"] is True


@pytest.mark.parametrize("name", ["main", "wt-a", "wt-b"])
def test_no_crossing_each_worktree_answers_from_its_own_files(story, name):
    refs = ok(story[f"cross_references_{name}"])["references"]
    uses = {r["file"].rsplit("/", 1)[-1] for r in refs if r["module"] == "harness-app" and r["file"].endswith("Use.java")}
    assert uses == {f"{USE_OF[name]}.java"}, refs
    (d,) = ok(story[f"cross_definition_{name}"])["definitions"]
    assert d["line"] == (6 if name == "wt-b" else 5), "wt-b's Names.java has one more line"
    assert [s["name"] for s in ok(story[f"cross_symbols_{name}"])["symbols"]] == [f"org.fractal.harness.app.{USE_OF[name]}"]
    subtypes = ok(story[f"cross_subtypes_{name}"])["type"]["subtypes"]
    assert [s["name"] for s in subtypes] == ["org.fractal.harness.app.PrintStep", "org.fractal.harness.contract.EchoStep"]
    callers = [n["name"] for n in ok(story[f"cross_callers_{name}"])["calls"]]
    assert f"org.fractal.harness.app.{USE_OF[name]}#use()" in callers
    assert not [u for u in USE_OF.values() if u != USE_OF[name] and f"org.fractal.harness.app.{u}#use()" in callers]


EXPECTED_ERRORS = {
    "invalid-name": (400, "/target"),
    "invalid-request": (400, None),
    "type-not-found": (404, "/target"),
    "member-not-found": (404, "/target"),
    "package-not-found": (404, "/target"),
    "ambiguous-member": (422, "/target"),
    "ambiguous-member-classpath": (422, "/target"),
    "text-not-found": (404, "/target/text"),
    "text-not-found-closest": (404, "/target/text"),
    "text-not-unique": (422, "/target/text"),
    "file-not-found": (404, "/file"),
    "file-in-other-module": (422, "/target/file"),
    "not-java-source": (422, "/file"),
    "not-applicable": (422, "/target"),
    "not-applicable-anchor": (422, "/target/text"),
    "ambiguous-type": (422, "/module"),
}


@pytest.mark.parametrize("key", sorted(EXPECTED_ERRORS))
def test_each_addressing_error_says_how_to_correct_the_request(story, key):
    case = story["errors"][key]
    status, problem = case["problem"]
    expected_status, field = EXPECTED_ERRORS[key]
    assert status == expected_status, problem
    assert problem["code"] == key.removesuffix("-classpath").removesuffix("-closest").removesuffix("-anchor")
    assert problem.get("field") == field
    assert problem["detail"] and problem["hint"]
    corrected_status, corrected = case["corrected"]
    assert corrected_status == 200, corrected


def test_review_r1_a_library_method_implemented_with_type_arguments(story):
    value = ok(story["review_comparator"])
    assert value["element"] == "java.util.Comparator#compare(java.lang.Object,java.lang.Object)"
    assert [i["element"] for i in value["implementations"]] == ["org.fractal.harness.app.ByLength#compare(java.lang.String,java.lang.String)"]


def test_review_r3_a_field_wins_a_name_without_parentheses(story):
    field = ok(story["review_field"])
    assert (field["element"], field["kind"], field["signature"]) == (f"{SIZES_NAME}#size", "field", "private int size")
    method = ok(story["review_method"])
    assert (method["element"], method["kind"], method["signature"]) == (f"{SIZES_NAME}#size()", "method", "public int size()")


def test_review_r5_a_generic_fields_signature_keeps_its_type_arguments(story):
    assert ok(story["review_generic"])["signature"] == "public java.util.Map<String, Integer> entries"
    (sizes,) = ok(story["review_outline"])["symbols"]
    assert [c["signature"] for c in sizes["children"]] == ["private int size", "public java.util.Map<String, Integer> entries", "public int size()"]


def test_review_r4_every_within_candidate_resolves(story):
    status, problem = story["review_twice"]
    assert status == 422 and problem["code"] == "text-not-unique"
    assert [c["set"] for c in problem["candidates"]] == [{"/target/within": "org.fractal.harness.contract.Twice#Twice()"}], \
        "the type's range holds both occurrences, so only the constructor tells them apart"
    assert [(s, v["definitions"][0]["element"]) for s, v in story["review_twice_applied"]] == [(200, "org.fractal.harness.contract.Twice#Twice()")]


def test_the_addressing_errors_details(story):
    errors = story["errors"]
    assert errors["invalid-name"]["problem"][1]["detail"] == f"'{NAMES}#describe(int' has no closing parenthesis."
    assert [c["set"]["/target"] for c in errors["type-not-found"]["problem"][1]["candidates"]] == [NAMES]
    assert len(errors["member-not-found"]["problem"][1]["candidates"]) == 4
    ambiguous = errors["ambiguous-member"]["problem"][1]
    assert [c["set"]["/target"] for c in ambiguous["candidates"]] == [
        f"{NAMES}#describe(int)", f"{NAMES}#describe(java.lang.String)", f"{NAMES}#describe(org.fractal.harness.contract.ExecutionId)",
        f"{NAMES}#describe(java.lang.String...)"]
    assert all(c["about"].endswith("from the classpath: jrt:/java.base") for c in errors["ambiguous-member-classpath"]["problem"][1]["candidates"])
    whitespace = errors["text-not-found"]["problem"][1]
    assert [c["set"]["/target/text"] for c in whitespace["candidates"]] == ["String clean = Names.normalize(who);"]
    assert "The closest line is 10: `int width = 10 - clean.length();`" in errors["text-not-found-closest"]["problem"][1]["detail"]
    unique = errors["text-not-unique"]["problem"][1]
    assert [o["line"] for o in unique["occurrences"]] == [9, 15]
    assert [c["set"]["/target/within"] for c in unique["candidates"]] == [
        "org.fractal.harness.app.Greeter#greet(java.lang.String)", "org.fractal.harness.app.Greeter#shout(java.lang.String)"]
    other = errors["file-in-other-module"]["problem"][1]
    assert other["candidates"][0]["set"] == {"/module": "harness-app", "/target/file": GREETER_FILE}
    typed = errors["ambiguous-type"]["problem"][1]
    assert sorted(c["set"]["/module"] for c in typed["candidates"]) == ["harness-app", "harness-contract"]
    assert ok(story["after_duplicate"])["location"]["module"] == "harness-contract"
