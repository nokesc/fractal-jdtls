# SPDX-License-Identifier: Apache-2.0
"""The endpoint's OpenAPI 3.1 description (design §11 "The contract", "Errors")."""

import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
DESCRIPTION = ROOT / "endpoint/src/main/resources/openapi.json"
BASE = "urn:fractal-jdtls:openapi"
METHODS = {"get", "post", "put", "delete", "patch"}


@pytest.fixture(scope="module")
def doc():
    return json.loads(DESCRIPTION.read_text(encoding="utf-8"))


def strict(node):
    """A copy where object schemas with properties allow no others, so a typo in an example fails."""
    if isinstance(node, dict):
        node = {k: strict(v) for k, v in node.items()}
        if node.get("type") == "object" and "properties" in node and "additionalProperties" not in node:
            node["additionalProperties"] = False
        return node
    if isinstance(node, list):
        return [strict(v) for v in node]
    return node


def operations(doc):
    for path, item in doc["paths"].items():
        for method, operation in item.items():
            if method in METHODS:
                yield f"{method.upper()} {path}", operation


def example_values(doc, media):
    for name, example in media.get("examples", {}).items():
        if "$ref" in example:
            example = doc["components"]["examples"][example["$ref"].rsplit("/", 1)[1]]
        yield name, example["value"]


@pytest.fixture(scope="module")
def validator_for(doc):
    registry = Registry().with_resource(BASE, Resource.from_contents(strict(copy.deepcopy(doc)), default_specification=DRAFT202012))

    def make(schema):
        schema = {"$ref": BASE + schema["$ref"]} if "$ref" in schema else strict(schema)
        return Draft202012Validator(schema, registry=registry)

    return make


def test_description_is_valid_openapi_3_1(doc):
    assert doc["openapi"] == "3.1.0"
    validate(doc)
    assert doc["info"]["license"]["identifier"] == "Apache-2.0"


def test_every_operation_has_descriptions_and_examples(doc):
    for name, operation in operations(doc):
        assert operation.get("operationId") and operation.get("summary") and operation.get("description"), name
        body = operation.get("requestBody")
        if body:
            assert body["content"]["application/json"].get("examples"), f"{name}: no request example"
        success = [r for status, r in operation["responses"].items() if status.startswith("2")]
        assert success and success[0]["content"]["application/json"].get("examples"), f"{name}: no response example"


def catalogue(doc):
    return {entry["const"]: entry for entry in doc["components"]["schemas"]["ErrorCode"]["oneOf"]}


def test_every_error_code_has_an_example_problem(doc):
    examples = doc["components"]["examples"]
    for code, entry in catalogue(doc).items():
        assert entry.get("description") and entry.get("x-status"), code
        value = examples[f"error-{code}"]["value"]
        assert value["code"] == code and value["status"] == entry["x-status"], code
        assert value["hint"] and value["retryable"] == entry["x-retryable"], code
        assert code == "unauthorized" or value.get("detail"), f"{code}: no detail"
        if entry["x-candidates"]:
            assert value.get("candidates"), f"{code}: the catalogue says it has candidates"


def test_operations_list_only_catalogued_codes_at_their_status(doc):
    codes = catalogue(doc)
    for name, operation in operations(doc):
        for status, response in operation["responses"].items():
            media = response.get("content", {}).get("application/problem+json")
            if not media:
                continue
            for _, value in example_values(doc, media):
                assert value["code"] in codes, f"{name}: {value['code']}"
                assert str(codes[value["code"]]["x-status"]) == status, f"{name}: {value['code']} under {status}"


def test_every_example_validates_against_its_schema(doc, validator_for):
    checked = 0
    for name, operation in operations(doc):
        media_list = []
        if operation.get("requestBody"):
            media_list += operation["requestBody"]["content"].values()
        for response in operation["responses"].values():
            media_list += response.get("content", {}).values()
        for media in media_list:
            validator = validator_for(media["schema"])
            for example, value in example_values(doc, media):
                errors = [f"{'/'.join(map(str, e.absolute_path))}: {e.message}" for e in validator.iter_errors(value)]
                assert not errors, f"{name} example {example}: {errors}"
                checked += 1
    error_validator = validator_for({"$ref": "#/components/schemas/Error"})
    for name, example in doc["components"]["examples"].items():
        assert not list(error_validator.iter_errors(example["value"])), name
        checked += 1
    assert checked > 60


def apply(request, changes):
    """The request with a candidate's `set` applied (JSON Pointers, missing objects created), or None if it can't be."""
    request = copy.deepcopy(request)
    for pointer, value in changes.items():
        parts = [p.replace("~1", "/").replace("~0", "~") for p in pointer.split("/")[1:]]
        node = request
        for part in parts[:-1]:
            if not isinstance(node, dict):
                return None
            node = node.setdefault(part, {})
        if not isinstance(node, dict):
            return None
        node[parts[-1]] = copy.deepcopy(value)
    return request


def request_forms(examples):
    """The operation's request examples, and each with its `target` as a name and as an anchor, if it has one."""
    for value in examples:
        yield value
        if "target" in value:
            yield {**value, "target": "com.acme.Foo#bar"}
            yield {**value, "target": {"file": "src/main/java/com/acme/Foo.java", "text": "bar()"}}


def test_every_candidate_applies_to_the_operations_own_request(doc, validator_for):
    """§11: a candidate can be passed straight back. Applied to the request, it must give a valid request."""
    applied = 0
    for name, operation in operations(doc):
        body = operation.get("requestBody")
        if not body:
            continue
        media = body["content"]["application/json"]
        validator = validator_for(media["schema"])
        requests = list(request_forms(value for _, value in example_values(doc, media)))
        for response in operation["responses"].values():
            problems = response.get("content", {}).get("application/problem+json")
            if not problems:
                continue
            for example, value in example_values(doc, problems):
                for candidate in value.get("candidates", []):
                    results = [apply(r, candidate["set"]) for r in requests]
                    assert any(r is not None and validator.is_valid(r) for r in results), (
                        f"{name}: {example}'s candidate {candidate['set']} gives no valid request")
                    applied += 1
    assert applied > 100
