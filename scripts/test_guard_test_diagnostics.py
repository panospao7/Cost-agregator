"""Assertion diagnostics must stay bounded, useful, and non-authorizing."""
import json

import pytest

from scripts.ci.guard_test_diagnostics import (
    MAX_DISCOVERY_DIAGNOSTICS, MAX_EVIDENCE_GROUPS, MAX_ITEMS, MAX_REPORT_BYTES,
    db_guard_failure_summary,
)

UMBRELLA = "DB_POLICY_SOURCE_EVIDENCE_INVALID"
DETAIL = "DB_V2_POLICY_MUTATION_NOT_FOUND"
KEY = "app/src/main/java/example/Repo.kt|example.Repo|function|save|null|Long"
SECRET = "private raw payload /outside/private.db SELECT secret"


def _report(tmp_path, **changes):
    data = {
        "findings": [],
        "diagnostics": [{"code": UMBRELLA, "controlled_context": {"message": SECRET}}],
        "statistics": {"trusted": False},
    }
    data.update(changes)
    path = tmp_path / "report.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def _evidence(key=KEY, count=1):
    diagnostic = {"code": DETAIL, "context": {"message": SECRET}}
    return {
        "diagnostics": [diagnostic],
        "groups": [{
            "callable_key": key, "trusted": False,
            "diagnostics": [diagnostic], "mutation_keys": [SECRET],
        } for _ in range(count)],
    }


def test_summary_reads_codes_and_trust_without_modifying_report(tmp_path):
    path = _report(tmp_path)
    before = path.read_bytes()
    text = db_guard_failure_summary(2, path)
    data = json.loads(text)
    assert data["exitCode"] == 2
    assert data["trusted"] is False
    assert data["diagnosticCodes"] == [UMBRELLA]
    assert data["findingCount"] == 0
    assert SECRET not in text
    assert str(tmp_path) not in text
    assert path.read_bytes() == before


@pytest.mark.parametrize("payload", [b"{", b"null", b"[]", b'"private"', b"\xff"])
def test_invalid_reports_are_bounded_not_passes(tmp_path, payload):
    path = tmp_path / "invalid.json"
    path.write_bytes(payload)
    data = json.loads(db_guard_failure_summary(2, path))
    assert data["exitCode"] == 2
    assert data["reportStatus"] in {"INVALID_JSON", "INVALID_SHAPE"}
    assert "trusted" not in data


def test_missing_report_does_not_echo_path_or_mask_exit(tmp_path):
    text = db_guard_failure_summary(2, tmp_path / "private-missing.json")
    data = json.loads(text)
    assert data["exitCode"] == 2
    assert data["reportStatus"] == "UNAVAILABLE"
    assert "private-missing" not in text


def test_oversized_report_is_not_read_without_bound(tmp_path):
    path = tmp_path / "large.json"
    path.write_bytes(b"x" * (MAX_REPORT_BYTES + 1))
    data = json.loads(db_guard_failure_summary(2, path))
    assert data["reportStatus"] == "TOO_LARGE"
    assert data["exitCode"] == 2


def test_unknown_codes_and_malformed_rows_do_not_echo_payloads(tmp_path):
    path = _report(tmp_path, diagnostics=[{"code": SECRET}, None],
                   findings=[{"rule": SECRET}])
    text = db_guard_failure_summary(2, path)
    data = json.loads(text)
    assert data["diagnosticCodes"] == ["UNKNOWN_CODE", "UNKNOWN_CODE"]
    assert data["findingCodes"] == ["UNKNOWN_CODE"]
    assert SECRET not in text


def test_report_rows_are_capped_but_total_count_is_retained(tmp_path):
    path = _report(tmp_path, diagnostics=[{"code": UMBRELLA}] * 20)
    data = json.loads(db_guard_failure_summary(2, path))
    assert data["diagnosticCount"] == 20
    assert data["diagnosticCodes"] == [UMBRELLA] * MAX_ITEMS


def test_observed_evidence_exposes_group_identity_and_controlled_code(tmp_path):
    text = db_guard_failure_summary(2, _report(tmp_path), evidence_reports=[_evidence()])
    data = json.loads(text)
    assert data["evidenceReportCount"] == 1
    assert data["evidence"][0]["failedGroups"] == [{
        "path": "app/src/main/java/example/Repo.kt",
        "owner": "example.Repo", "method": "save", "codes": [DETAIL],
    }]
    assert SECRET not in text


@pytest.mark.parametrize("path", [
    "/outside/Repo.kt", "C:/outside/Repo.kt", "app/../Repo.kt",
    "app\\Repo.kt", "app/private\nRepo.kt", "app/" + "x" * 400 + ".kt",
])
def test_unsafe_or_oversized_identity_is_not_echoed(tmp_path, path):
    key = path + "|example.Repo|function|save|null|Long"
    text = db_guard_failure_summary(2, _report(tmp_path), evidence_reports=[_evidence(key)])
    item = json.loads(text)["evidence"][0]["failedGroups"][0]
    assert item == {"identity": "INVALID_CALLABLE_IDENTITY", "codes": [DETAIL]}


def test_evidence_rows_are_capped_without_hiding_omission_count(tmp_path):
    text = db_guard_failure_summary(2, _report(tmp_path), evidence_reports=[_evidence(count=20)])
    data = json.loads(text)["evidence"][0]
    assert data["failedGroupCount"] == 20
    assert data["omittedGroupCount"] == 20 - MAX_ITEMS
    assert len(data["failedGroups"]) == MAX_ITEMS
    assert len(text) < 16384


def test_batch_diagnostic_survives_even_without_callable_groups(tmp_path):
    evidence = {"groups": [], "diagnostics": [{"code": DETAIL}]}
    data = json.loads(db_guard_failure_summary(2, _report(tmp_path), evidence_reports=[evidence]))
    assert data["evidence"][0]["diagnosticCodes"] == [DETAIL]
    assert data["evidence"][0]["failedGroupCount"] == 0


@pytest.mark.parametrize("owner", ["example/Repo", "example:Repo", "example\nRepo"])
def test_owner_separators_must_be_literal_dots(tmp_path, owner):
    key = "app/src/main/java/example/Repo.kt|" + owner + "|function|save|null|Long"
    data = json.loads(db_guard_failure_summary(
        2, _report(tmp_path), evidence_reports=[_evidence(key)],
    ))
    assert data["evidence"][0]["failedGroups"][0] == {
        "identity": "INVALID_CALLABLE_IDENTITY", "codes": [DETAIL],
    }


def test_stale_mutation_context_exposes_only_bounded_identifiers(tmp_path):
    evidence = _evidence()
    evidence["groups"][0]["diagnostics"][0]["context"].update({
        "dao_accessor": "scannedReceiptDao", "operation": "update",
    })
    text = db_guard_failure_summary(2, _report(tmp_path), evidence_reports=[evidence])
    assert json.loads(text)["evidence"][0]["failedGroups"][0]["mutation"] == {
        "daoAccessor": "scannedReceiptDao", "operation": "update",
    }
    assert SECRET not in text


@pytest.mark.parametrize("value", [SECRET, "x" * 97, "update\nprivate"])
def test_mutation_context_rejects_payloads_and_oversized_values(tmp_path, value):
    evidence = _evidence()
    evidence["groups"][0]["diagnostics"][0]["context"].update({
        "dao_accessor": value, "operation": value,
    })
    text = db_guard_failure_summary(2, _report(tmp_path), evidence_reports=[evidence])
    assert "mutation" not in json.loads(text)["evidence"][0]["failedGroups"][0]
    assert SECRET not in text


def test_expanded_evidence_reports_all_twelve_groups_without_changing_inputs(tmp_path):
    evidence = _evidence(count=12)
    for index, group in enumerate(evidence["groups"]):
        group["callable_key"] = KEY.replace("|save|", f"|save{index}|")
        group["mutation_keys"] = ["transactionEventDao|nullSnapshotsOlderThan"]
    path = _report(tmp_path)
    before_report = path.read_bytes()
    before_evidence = json.dumps(evidence, sort_keys=True)
    text = db_guard_failure_summary(
        2, path, evidence_reports=[evidence], expanded_evidence=True,
    )
    summary = json.loads(text)
    detail = summary["evidence"][0]
    assert summary["exitCode"] == 2
    assert summary["trusted"] is False
    assert detail["failedGroupCount"] == 12
    assert detail["omittedGroupCount"] == 0
    assert [group["method"] for group in detail["failedGroups"]] == [
        f"save{index}" for index in range(12)
    ]
    for group in detail["failedGroups"]:
        assert group["actualMutationCount"] == 1
        assert group["omittedMutationCount"] == 0
        assert group["actualMutations"] == [{
            "daoAccessor": "transactionEventDao",
            "operation": "nullSnapshotsOlderThan",
        }]
    assert SECRET not in text
    assert path.read_bytes() == before_report
    assert json.dumps(evidence, sort_keys=True) == before_evidence


def test_expanded_evidence_remains_capped_and_discloses_remaining_groups(tmp_path):
    text = db_guard_failure_summary(
        2, _report(tmp_path),
        evidence_reports=[_evidence(count=MAX_EVIDENCE_GROUPS + 3)],
        expanded_evidence=True,
    )
    detail = json.loads(text)["evidence"][0]
    assert detail["failedGroupCount"] == MAX_EVIDENCE_GROUPS + 3
    assert detail["omittedGroupCount"] == 3
    assert len(detail["failedGroups"]) == MAX_EVIDENCE_GROUPS
    assert SECRET not in text
    assert len(text) < 262144


def test_expanded_actual_mutations_are_bounded_with_truthful_counts(tmp_path):
    evidence = _evidence()
    evidence["groups"][0]["mutation_keys"] = [
        f"dao{index}|update" for index in range(MAX_ITEMS + 3)
    ]
    text = db_guard_failure_summary(
        2, _report(tmp_path), evidence_reports=[evidence], expanded_evidence=True,
    )
    group = json.loads(text)["evidence"][0]["failedGroups"][0]
    assert group["actualMutationCount"] == MAX_ITEMS + 3
    assert group["omittedMutationCount"] == 3
    assert group["actualMutations"] == [
        {"daoAccessor": f"dao{index}", "operation": "update"}
        for index in range(MAX_ITEMS)
    ]


@pytest.mark.parametrize("key", [
    SECRET, "x" * 97 + "|update", "dao|update\nprivate", "dao|update|extra", None,
])
def test_expanded_actual_mutation_identities_reject_raw_or_malformed_values(tmp_path, key):
    evidence = _evidence()
    evidence["groups"][0]["mutation_keys"] = [key]
    text = db_guard_failure_summary(
        2, _report(tmp_path), evidence_reports=[evidence], expanded_evidence=True,
    )
    group = json.loads(text)["evidence"][0]["failedGroups"][0]
    assert group["actualMutations"] == [{"identity": "INVALID_MUTATION_IDENTITY"}]
    assert SECRET not in text
    assert "private" not in text


def test_expanded_evidence_sanitizes_groups_beyond_the_compact_page(tmp_path):
    evidence = _evidence(count=12)
    evidence["groups"][9]["callable_key"] = SECRET
    text = db_guard_failure_summary(
        2, _report(tmp_path), evidence_reports=[evidence], expanded_evidence=True,
    )
    group = json.loads(text)["evidence"][0]["failedGroups"][9]
    assert group["identity"] == "INVALID_CALLABLE_IDENTITY"
    assert group["actualMutations"] == [{"identity": "INVALID_MUTATION_IDENTITY"}]
    assert SECRET not in text


@pytest.mark.parametrize("flag", [None, 1, "yes"])
def test_expanded_evidence_requires_explicit_boolean_opt_in(tmp_path, flag):
    text = db_guard_failure_summary(
        2, _report(tmp_path), evidence_reports=[_evidence(count=12)],
        expanded_evidence=flag,
    )
    detail = json.loads(text)["evidence"][0]
    assert len(detail["failedGroups"]) == MAX_ITEMS
    assert detail["omittedGroupCount"] == 12 - MAX_ITEMS
    assert all("actualMutations" not in group for group in detail["failedGroups"])


def _discovery_diagnostic(index=0, *, advisory=False, **changes):
    item = {
        "code": "DB_SIGNATURE_UNRESOLVED",
        "path": f"app/src/main/java/example/Repo{index}.kt",
        "controlled_context": {"advisory": advisory, "line": index + 1, "message": SECRET},
        "symbol": SECRET,
    }
    item.update(changes)
    return item


def test_discovery_details_separate_all_four_blockers_from_twenty_two_advisories(tmp_path):
    diagnostics = [_discovery_diagnostic(i, advisory=True) for i in range(22)]
    diagnostics += [_discovery_diagnostic(i) for i in range(22, 26)]
    path = _report(tmp_path, diagnostics=diagnostics)
    before = path.read_bytes()
    text = db_guard_failure_summary(2, path)
    data = json.loads(text)
    assert data["diagnosticCount"] == 26
    assert data["blockingDiagnosticCount"] == 4
    assert data["advisoryDiagnosticCount"] == 22
    assert data["omittedDiagnosticCodeCount"] == 18
    assert data["omittedBlockingDiagnosticCount"] == 0
    assert data["blockingDiagnostics"] == [
        {"code": "DB_SIGNATURE_UNRESOLVED", "path": item["path"], "line": i + 1}
        for i, item in enumerate(diagnostics) if i >= 22
    ]
    assert data["exitCode"] == 2 and data["trusted"] is False
    assert SECRET not in text and str(tmp_path) not in text
    assert path.read_bytes() == before


@pytest.mark.parametrize("flag,limit", [
    (False, MAX_ITEMS), (None, MAX_ITEMS), (1, MAX_ITEMS),
    ("yes", MAX_ITEMS), (True, MAX_DISCOVERY_DIAGNOSTICS),
])
def test_discovery_details_are_bounded_with_explicit_omission_counts(tmp_path, flag, limit):
    count = MAX_DISCOVERY_DIAGNOSTICS + 3
    diagnostics = [_discovery_diagnostic(i) for i in range(count)]
    data = json.loads(db_guard_failure_summary(
        2, _report(tmp_path, diagnostics=diagnostics), expanded_evidence=flag,
    ))
    assert data["blockingDiagnosticCount"] == count
    assert data["advisoryDiagnosticCount"] == 0
    assert len(data["blockingDiagnostics"]) == limit
    assert data["omittedBlockingDiagnosticCount"] == count - limit
    assert data["omittedDiagnosticCodeCount"] == count - MAX_ITEMS
    assert data["exitCode"] == 2 and data["trusted"] is False


@pytest.mark.parametrize("marker,is_advisory", [
    (True, True), (False, False), (None, False), (1, False),
    ("true", False), ([], False), ({}, False),
])
def test_only_literal_true_is_an_advisory_marker(tmp_path, marker, is_advisory):
    item = _discovery_diagnostic(controlled_context={"advisory": marker})
    data = json.loads(db_guard_failure_summary(2, _report(tmp_path, diagnostics=[item])))
    assert data["advisoryDiagnosticCount"] == int(is_advisory)
    assert data["blockingDiagnosticCount"] == int(not is_advisory)
    assert data["exitCode"] == 2 and data["trusted"] is False


@pytest.mark.parametrize("path", [
    "/outside/Repo.kt", "C:/outside/Repo.kt", "app/../Repo.kt",
    "app\\Repo.kt", "app/private\nRepo.kt", "app/" + "x" * 400 + ".kt", None,
])
def test_discovery_details_reject_unsafe_source_paths(tmp_path, path):
    diagnostic = _discovery_diagnostic(path=path)
    text = db_guard_failure_summary(2, _report(tmp_path, diagnostics=[diagnostic]))
    assert json.loads(text)["blockingDiagnostics"] == [{
        "code": "DB_SIGNATURE_UNRESOLVED", "identity": "INVALID_SOURCE_IDENTITY",
    }]
    assert SECRET not in text and "/outside/" not in text


@pytest.mark.parametrize("line", [None, True, False, 0, -1, 2 ** 31, "7", [], {}])
def test_discovery_details_reject_non_integer_or_unbounded_coordinates(tmp_path, line):
    diagnostic = _discovery_diagnostic(controlled_context={"line": line})
    data = json.loads(db_guard_failure_summary(2, _report(tmp_path, diagnostics=[diagnostic])))
    assert "line" not in data["blockingDiagnostics"][0]


def test_malformed_discovery_rows_remain_visible_blockers_without_echoing_context(tmp_path):
    diagnostics = [None, {"code": SECRET, "controlled_context": SECRET}]
    text = db_guard_failure_summary(2, _report(tmp_path, diagnostics=diagnostics))
    data = json.loads(text)
    assert data["blockingDiagnosticCount"] == 2
    assert data["advisoryDiagnosticCount"] == 0
    assert data["blockingDiagnostics"] == [
        {"code": "UNKNOWN_CODE", "identity": "INVALID_SOURCE_IDENTITY"},
        {"code": "UNKNOWN_CODE", "identity": "INVALID_SOURCE_IDENTITY"},
    ]
    assert SECRET not in text
