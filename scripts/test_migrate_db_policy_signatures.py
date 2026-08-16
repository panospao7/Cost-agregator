"""Contract tests for the DB-policy signature migration CLI.

The suite uses only temporary, canonical ``app/src/main/...`` Kotlin fixtures;
the migration implementation and its real command-line entry point are used,
not a reimplementation of discovery.  Authored coverage; execution pending.
The fixture family has deliberately narrow, explicit policy entries.  No
baseline or wildcard policy is used.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))

import migrate_db_policy_signatures as migration  # noqa: E402


REL = "app/src/main/java/example/Fixture.kt"
OWNER = "example.Fixture"


def _entry(method, *, params=None, receiver=None, operation="insert", dao="ExpenseDao",
           reason="controlled reason", owner="owner-id", linked_issue="ISSUE-42",
           signature=True, barrier_via=None):
    item = {
        "path": REL, "class": OWNER, "method": method, "daos": [dao],
        "operation": operation, "barrier_required": True, "reason": reason,
        "owner": owner, "linked_issue": linked_issue,
    }
    if signature:
        item["signature"] = {"parameters": list(params or ()), "receiver": receiver}
    if barrier_via is not None:
        item["barrier_via"] = barrier_via
    return item


def _fixture(tmp_path: Path, source: str, entries: list[dict]) -> tuple[Path, Path]:
    source_path = tmp_path / Path(*REL.split("/"))
    source_path.parent.mkdir(parents=True, exist_ok=True)
    source_path.write_text(source, encoding="utf-8")
    policy = tmp_path / "policy.yml"
    policy.write_text(yaml.safe_dump({"entries": entries}, sort_keys=False), encoding="utf-8")
    return policy, source_path


def _run_cli(tmp_path: Path, args: list[str], *, fake_script: Path | None = None):
    """Invoke main() through a subprocess while making the CLI's repo root temporary."""
    fake_script = fake_script or (tmp_path / "scripts" / "migrate_db_policy_signatures.py")
    fake_script.parent.mkdir(parents=True, exist_ok=True)
    code = (
        "import sys; import migrate_db_policy_signatures as m; "
        "m.__file__ = sys.argv[1]; raise SystemExit(m.main(sys.argv[2:]))"
    )
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path(__file__).resolve().parent)
    return subprocess.run(
        [sys.executable, "-c", code, str(fake_script), *map(str, args)],
        cwd=tmp_path, env=env, text=True, capture_output=True, check=False,
    )


SOURCE = """package example
class Fixture {
    fun insert(value: Int) { ExpenseDao.insert(value) }
    fun overload(value: Int) { ExpenseDao.insert(value) }
    fun overload(value: String) { ExpenseDao.insert(value) }
    fun rich(value: List<String?>, vararg rest: Int, callback: (String, Int?) -> Unit) {
        ExpenseDao.insert(value)
    }
    fun String?.extension(value: Int) { ExpenseDao.insert(value) }
    fun absent(value: Int) { OtherDao.insert(value) }
    fun ext(value: Int) { ExpenseDao.insert(value) }
}
fun String?.ext(value: Int) { ExpenseDao.insert(value) }
"""


def test_single_non_overloaded_method_gets_exact_signature(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    candidate, report = migration.migrate(migration.load_policy(policy), tmp_path)
    assert report[0]["status"] == "RESOLVED_EXACTLY"
    assert candidate["entries"][0]["signature"] == {"parameters": ["Int"], "receiver": None}


def test_overloads_without_signature_are_ambiguous_and_exit_one(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("overload", signature=False)])
    result = _run_cli(tmp_path, ["--check", "--policy", policy])
    assert result.returncode == 1
    assert migration.migrate(migration.load_policy(policy), tmp_path)[1][0]["status"] == "AMBIGUOUS_OVERLOAD"


@pytest.mark.parametrize("method,source,signature,expected", [
    ("unsupported", "fun unsupported(value: Int) = ExpenseDao.insert(value)", {"parameters": ["Int"], "receiver": None}, "SIGNATURE_UNSUPPORTED"),
    ("missing", "fun present(value: Int) { ExpenseDao.insert(value) }", {"parameters": ["Int"], "receiver": None}, "METHOD_MISSING"),
    ("absent", "fun absent(value: Int) { OtherDao.insert(value) }", {"parameters": ["Int"], "receiver": None}, "PAIR_NOT_FOUND"),
    ("overload", "fun overload(value: Int) { ExpenseDao.insert(value) }\nfun overload(value: String) { ExpenseDao.insert(value) }", None, "AMBIGUOUS_OVERLOAD"),
])
def test_cli_reports_exact_controlled_statuses(tmp_path, method, source, signature, expected):
    body = "package example\nclass Fixture {\n" + source + "\n}\n"
    entry = _entry(method, signature=signature is not None)
    if signature is not None:
        entry["signature"] = signature
    policy, _ = _fixture(tmp_path, body, [entry])
    report = tmp_path / "nested" / "reports" / "status.json"
    result = _run_cli(tmp_path, ["--check", "--policy", policy, "--report", report])
    assert result.returncode == 1
    payload = json.loads(report.read_text(encoding="utf-8"))
    rows = payload["resolved"] if expected == "RESOLVED_EXACTLY" else payload["unresolved"]
    assert rows[0].get("status", "RESOLVED_EXACTLY") == expected
    assert result.stdout == result.stderr == ""


def test_cli_generic_type_parameter_is_fail_closed_and_does_not_change_policy_or_candidate(tmp_path):
    secret = "GENERIC_SOURCE_SECRET"
    source = f"package example\nclass Fixture {{\n    fun <T : MissingBound> insert(value: T) {{ ExpenseDao.insert(value) }} // {secret}\n}}\n"
    policy, _ = _fixture(tmp_path, source, [_entry("insert", signature=False)])
    candidate = tmp_path / "nested" / "candidate" / "policy.yml"
    candidate.parent.mkdir(parents=True, exist_ok=True)
    candidate.write_text("sentinel candidate\n", encoding="utf-8")
    before_policy = policy.read_bytes()
    report = tmp_path / "nested" / "reports" / "generic.json"
    result = _run_cli(tmp_path, ["--write-candidate", "--policy", policy,
                                 "--output", candidate, "--report", report])
    assert result.returncode == 1
    assert json.loads(report.read_text(encoding="utf-8"))["unresolved"][0]["status"] == "SIGNATURE_UNSUPPORTED"
    assert policy.read_bytes() == before_policy
    assert yaml.safe_load(candidate.read_text(encoding="utf-8")) == {"entries": []}
    assert secret not in result.stdout + result.stderr + report.read_text(encoding="utf-8")


@pytest.mark.parametrize("params,receiver,expected", [
    (["Int"], None, "RESOLVED_EXACTLY"),
    (["String"], None, "METHOD_MISSING"),
    (["Int", "String"], None, "METHOD_MISSING"),
    (["Int"], "String", "METHOD_MISSING"),
])
def test_exact_signature_shape_is_controlled(tmp_path, params, receiver, expected):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", params=params, receiver=receiver)])
    result = migration.resolve_entry(migration.load_policy(policy)["entries"][0], tmp_path)
    assert result["status"] == expected
    assert result["status"] in migration.STATUS


def test_exact_signature_resolves_and_rich_kotlin_types_are_canonical(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry(
        "rich", params=["List<String?>", "vararg Int", "(String,Int?)->Unit"]
    )])
    row = migration.resolve_entry(migration.load_policy(policy)["entries"][0], tmp_path)
    assert row["status"] == "RESOLVED_EXACTLY"
    assert row["identity"]["canonical"].endswith("#rich(List<String?>,vararg Int,(String,Int?)->Unit)")


def test_extension_receiver_signature_is_exact(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("extension", params=["Int"], receiver="String?")])
    row = migration.resolve_entry(migration.load_policy(policy)["entries"][0], tmp_path)
    assert row["status"] == "RESOLVED_EXACTLY"
    assert row["identity"]["canonical"].endswith("#extension(String?)(Int)")


def test_unresolved_type_alias_fails_closed(tmp_path):
    source = """package example
typealias Missing = NotInScope
class Fixture { fun insert(value: Missing) { ExpenseDao.insert(value) } }
"""
    policy, _ = _fixture(tmp_path, source, [_entry("insert", signature=False)])
    with pytest.raises(migration.PolicyError):
        migration.migrate(migration.load_policy(policy), tmp_path)


def test_ambiguous_import_alias_fails_closed(tmp_path):
    source = """package example
import a.Token as Alias
import b.Token as Alias
class Fixture { fun insert(value: Alias) { ExpenseDao.insert(value) } }
"""
    policy, _ = _fixture(tmp_path, source, [_entry("insert", signature=False)])
    with pytest.raises(migration.PolicyError):
        migration.migrate(migration.load_policy(policy), tmp_path)


@pytest.mark.parametrize("method,expected", [("notThere", "METHOD_MISSING"), ("absent", "PAIR_NOT_FOUND")])
def test_method_missing_and_pair_not_found_statuses(tmp_path, method, expected):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry(method, params=["Int"])])
    row = migration.resolve_entry(migration.load_policy(policy)["entries"][0], tmp_path)
    assert row["status"] == expected


def test_pair_not_found_is_not_hidden_by_discovery(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("absent", params=["Int"], dao="ExpenseDao")])
    assert migration.migrate(migration.load_policy(policy), tmp_path)[1][0]["status"] == "PAIR_NOT_FOUND"


def test_duplicate_exact_source_signatures_are_rejected(tmp_path):
    source = "package example\nclass Fixture {\n fun insert(value: Int) { ExpenseDao.insert(value) }\n fun insert(value: Int) { ExpenseDao.insert(value) }\n}\n"
    policy, _ = _fixture(tmp_path, source, [_entry("insert", params=["Int"])])
    with pytest.raises(migration.PolicyError):
        migration.migrate(migration.load_policy(policy), tmp_path)


def test_check_is_read_only_byte_for_byte(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    before = policy.read_bytes()
    result = _run_cli(tmp_path, ["--check", "--policy", policy])
    assert result.returncode == 0
    assert policy.read_bytes() == before


def test_write_candidate_requires_output_and_rejects_policy_output(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    assert _run_cli(tmp_path, ["--write-candidate", "--policy", policy]).returncode == 2
    assert _run_cli(tmp_path, ["--write-candidate", "--policy", policy, "--output", policy]).returncode == 2


def test_report_and_candidate_collision_writes_neither_artifact(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    collision = tmp_path / "artifacts" / "same.json"
    collision.parent.mkdir(parents=True, exist_ok=True)
    collision.write_text("sentinel\n", encoding="utf-8")
    before = collision.read_bytes()
    result = _run_cli(tmp_path, ["--write-candidate", "--policy", policy,
                                 "--output", collision, "--report", collision])
    assert result.returncode == 2
    assert collision.read_bytes() == before


@pytest.mark.parametrize("mode", ["--check", "--write-candidate"])
def test_report_cannot_overwrite_active_policy(tmp_path, mode):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    before = policy.read_bytes()
    args = [mode, "--policy", policy, "--report", policy]
    if mode == "--write-candidate":
        args += ["--output", tmp_path / "candidate.yml"]
    result = _run_cli(tmp_path, args)
    assert result.returncode == 2
    assert policy.read_bytes() == before


def test_candidate_cannot_overwrite_active_policy(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    before = policy.read_bytes()
    result = _run_cli(tmp_path, ["--write-candidate", "--policy", policy,
                                 "--output", policy, "--report", tmp_path / "report.json"])
    assert result.returncode == 2
    assert policy.read_bytes() == before
    assert not (tmp_path / "report.json").exists()


def test_write_candidate_writes_only_candidate_atomically(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    output = tmp_path / "candidate.yml"
    result = _run_cli(tmp_path, ["--write-candidate", "--policy", policy, "--output", output])
    assert result.returncode == 0 and output.exists()
    assert policy.read_bytes() != output.read_bytes()
    assert not list(tmp_path.glob(".db-policy-*.tmp"))
    assert yaml.safe_load(output.read_text(encoding="utf-8"))["entries"][0]["signature"]


def test_migrate_candidate_contains_only_resolved_entries_and_preserves_metadata(tmp_path):
    resolved = _entry("insert", signature=False, reason="keep this", owner="team", linked_issue="GH-9")
    unresolved = _entry("absent", params=["Int"], reason="unresolved metadata")
    policy, _ = _fixture(tmp_path, SOURCE, [resolved, unresolved])
    original = migration.load_policy(policy)
    candidate, report = migration.migrate(original, tmp_path)

    assert len(candidate["entries"]) == 1
    assert candidate["entries"][0]["method"] == "insert"
    assert candidate["entries"][0]["reason"] == "keep this"
    assert candidate["entries"][0]["owner"] == "team"
    assert candidate["entries"][0]["linked_issue"] == "GH-9"
    assert all(row["status"] == "RESOLVED_EXACTLY" for row in report if row["method"] == "insert")
    assert all(row["method"] != "absent" for row in candidate["entries"])


@pytest.mark.parametrize("signature", [
    {"parameters": ["Int"], "receiver": None, "extra": False},
    {"parameters": "Int", "receiver": None},
    {"parameters": ["Int"], "receiver": 7},
])
def test_policy_rejects_malformed_signature_during_load(tmp_path, signature):
    entry = _entry("insert", signature=False)
    entry["signature"] = signature
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    with pytest.raises(migration.PolicyError):
        migration.load_policy(policy)


@pytest.mark.parametrize("daos", [None, "expenseDao", {"name": "expenseDao"}, [], [7]])
def test_policy_rejects_malformed_daos_without_type_error_or_value_leak(tmp_path, daos):
    entry = _entry("insert", signature=False)
    entry["daos"] = daos
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    with pytest.raises(migration.PolicyError) as exc:
        migration.load_policy(policy)
    assert str(exc.value) == "invalid DB policy configuration"
    result = _run_cli(tmp_path, ["--check", "--policy", policy])
    assert result.returncode == 2
    assert result.stdout == ""
    assert result.stderr.strip() == "invalid DB policy configuration"
    assert str(daos) not in result.stderr


def test_write_atomic_uses_replace_commit(monkeypatch, tmp_path):
    calls = []
    real_replace = migration.os.replace

    def replace(source, target):
        calls.append((Path(source).parent, Path(target)))
        return real_replace(source, target)

    monkeypatch.setattr(migration.os, "replace", replace)
    output = tmp_path / "candidate.yml"
    migration.write_atomic(output, {"entries": []})
    assert output.exists()
    assert calls == [(tmp_path, output)]


def test_report_failure_is_atomic_and_cleans_up_temp(monkeypatch, tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    report = tmp_path / "nested" / "report.json"

    def fail_replace(source, target):
        raise OSError("secret absolute path should not escape")

    monkeypatch.setattr(migration.os, "replace", fail_replace)
    with pytest.raises(migration.PolicyError) as exc:
        migration._report(report, policy, tmp_path, [])
    assert str(exc.value) == "invalid DB policy configuration"
    assert not report.exists()
    assert not list(report.parent.glob(".db-policy-report-*.tmp"))


def test_reason_owner_linked_issue_and_other_fields_are_preserved(tmp_path):
    entry = _entry("insert", signature=False, reason="reason: exact", owner="team/owner", linked_issue="GH-7")
    entry["daos"] = ["ExpenseDao", "AuditDao"]
    entry["barrier_required"] = False
    entry["barrier_via"] = "WorkerExecutionGuard"
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    candidate, _ = migration.migrate(migration.load_policy(policy), tmp_path)
    result = candidate["entries"][0]
    for key in ("path", "class", "method", "daos", "operation", "barrier_required", "barrier_via", "reason", "owner", "linked_issue"):
        assert result[key] == entry[key]


@pytest.mark.parametrize("value", ["", "not controlled!", "x" * 129, 7])
def test_barrier_via_is_bounded_controlled_metadata(tmp_path, value):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False, barrier_via=value)])
    with pytest.raises(migration.PolicyError):
        migration.load_policy(policy)


def test_report_uses_safe_policy_identifier(tmp_path):
    absolute_policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    report = tmp_path / "report.json"
    assert _run_cli(tmp_path, ["--check", "--policy", absolute_policy, "--report", report]).returncode == 0
    payload = json.loads(report.read_text(encoding="utf-8"))
    assert payload["policy"] == "custom-policy"
    assert str(absolute_policy) not in report.read_text(encoding="utf-8")


def test_candidate_and_report_are_deterministically_ordered(tmp_path):
    entries = [_entry("overload", signature=True), _entry("insert", signature=False)]
    # Deliberately provide an exact overload signature and a discoverable entry.
    entries[0]["signature"] = {"parameters": ["Int"], "receiver": None}
    policy, _ = _fixture(tmp_path, SOURCE, entries)
    one = tmp_path / "one.json"
    two = tmp_path / "two.json"
    for report in (one, two):
        assert _run_cli(tmp_path, ["--check", "--policy", policy, "--report", report]).returncode == 0
    assert one.read_bytes() == two.read_bytes()
    payload = json.loads(one.read_text(encoding="utf-8"))
    assert [row["method"] for row in payload["resolved"]] == ["insert", "overload"]
    assert set(payload) == {"schema", "schema_version", "policy", "counts", "resolved", "unresolved"}
    assert payload["schema"] == "cost-aggregator.policy-signature-migration"


def test_unresolved_rows_are_complete_and_use_null_for_ambiguous_dao(tmp_path):
    entry = _entry("absent", params=["Int"], dao="ExpenseDao")
    entry["daos"] = ["ExpenseDao", "AuditDao"]
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    row = migration.migrate(migration.load_policy(policy), tmp_path)[1][0]
    assert row["status"] == "PAIR_NOT_FOUND"
    assert set(row) == {"status", "file", "class", "method", "dao", "operation",
                        "signature_evidence", "reason_code"}
    assert row["file"] == REL
    assert row["dao"] is None
    assert row["operation"] == "insert"
    assert row["reason_code"] in migration.REASON_CODES
    assert set(row["signature_evidence"]) <= {"status", "parameters", "receiver"}


def test_report_rejects_noncanonical_totals(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert")])
    with pytest.raises(migration.PolicyError):
        migration._report(tmp_path / "report.json", policy, tmp_path,
                          [{"status": "RESOLVED_EXACTLY"}],
                          enforce_expected_totals=True)


def test_dotted_owner_is_valid_in_policy_candidate_and_report(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    loaded = migration.load_policy(policy)
    candidate, rows = migration.migrate(loaded, tmp_path)
    assert candidate["entries"][0]["class"] == OWNER
    report = tmp_path / "dotted-report.json"
    migration._report(report, policy, tmp_path, rows)
    assert json.loads(report.read_text(encoding="utf-8"))["resolved"][0]["class"] == OWNER


@pytest.mark.parametrize("bad_class", [
    "", "example..Fixture", ".example.Fixture", "example.Fixture.",
    "example<Fixture>", "example.*.Fixture", "example.Fixture name",
    "example.\tFixture", "example.\x01Fixture",
])
@pytest.mark.parametrize("method,signature", [("insert", True), ("absent", True)])
def test_owner_fqcn_validation_is_consistent_and_sanitized(tmp_path, bad_class, method, signature):
    entry = _entry(method, params=["Int"], signature=signature)
    entry["class"] = bad_class
    policy, _ = _fixture(tmp_path, SOURCE, [entry])

    with pytest.raises(migration.PolicyError) as exc:
        migration.load_policy(policy)
    assert str(exc.value) == "invalid DB policy configuration"
    result = _run_cli(tmp_path, ["--check", "--policy", policy])
    assert result.returncode == 2
    assert result.stdout == ""
    assert result.stderr.strip() == "invalid DB policy configuration"
    assert bad_class not in result.stderr


@pytest.mark.parametrize("bad_class", [
    "", "example..Fixture", "example<Fixture>", "example.*.Fixture", "example.Fixture name",
])
@pytest.mark.parametrize("resolved", [True, False])
def test_report_rejects_malformed_owner_fqcn_for_both_row_sections(tmp_path, bad_class, resolved):
    method = "insert" if resolved else "absent"
    entry = _entry(method, params=["Int"])
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    row = migration.migrate(migration.load_policy(policy), tmp_path)[1][0]
    if resolved:
        row["identity"]["class"] = bad_class
    else:
        row["class"] = bad_class
    with pytest.raises(migration.PolicyError) as exc:
        migration._report(tmp_path / "invalid-owner-report.json", policy, tmp_path, [row])
    assert str(exc.value) == "invalid DB policy configuration"
    assert bad_class not in str(exc.value)


def test_unresolved_report_does_not_contain_source_text(tmp_path):
    secret = "RAW_SOURCE_OR_EXCEPTION_SECRET"
    entry = _entry("absent", params=["Int"], reason=secret)
    policy, _ = _fixture(tmp_path, SOURCE + "\n// " + secret, [entry])
    report = tmp_path / "report.json"
    assert _run_cli(tmp_path, ["--check", "--policy", policy, "--report", report]).returncode == 1
    text = report.read_text(encoding="utf-8")
    assert secret not in text
    row = json.loads(text)["unresolved"][0]
    assert row["reason_code"] == "MIGRATION_PAIR_NOT_FOUND"


def test_errors_and_reports_are_sanitized(tmp_path):
    secret = "SECRET_SOURCE_SNIPPET"
    source = f'package example\nclass Fixture {{ fun insert(value: Unknown) {{ val x = "{secret}" }} }}\n'
    policy, _ = _fixture(tmp_path, source, [_entry("insert", signature=False)])
    result = _run_cli(tmp_path, ["--check", "--policy", policy])
    assert result.returncode == 2
    assert secret not in result.stdout + result.stderr
    with pytest.raises(migration.PolicyError) as exc:
        migration.migrate(migration.load_policy(policy), tmp_path)
    assert str(exc.value) == "invalid DB policy configuration"
    assert secret not in str(exc.value)
    safe_source = f"package example\n// {secret}\nclass Fixture {{ fun insert(value: Int) {{ ExpenseDao.insert(value) }} }}\n"
    safe_policy, _ = _fixture(tmp_path, safe_source, [_entry("insert", signature=False)])
    report = tmp_path / "safe-report.json"
    assert _run_cli(tmp_path, ["--check", "--policy", safe_policy, "--report", report]).returncode == 0
    assert secret not in report.read_text(encoding="utf-8")


def test_explicit_report_only_writes_report_no_implicit_baseline(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    report = tmp_path / "reports" / "migration.json"
    assert _run_cli(tmp_path, ["--check", "--policy", policy, "--report", report]).returncode == 0
    assert report.exists()
    assert not (tmp_path / "baseline.yml").exists()
    assert not (tmp_path / "report.json").exists()
    assert not (tmp_path / "candidate.yml").exists()


@pytest.mark.parametrize("field,value", [
    ("method", "*"), ("class", "Fixture*"), ("path", "app/src/main/java/*/Fixture.kt"),
    ("daos", ["*"]), ("operation", "write"), ("operation", "unknown"),
    ("expires", "permanent"), ("allowed_until", "2099-01-01"),
])
def test_policy_rejects_wildcards_unknown_operations_and_expiry_metadata(tmp_path, field, value):
    entry = _entry("insert", signature=False)
    if field == "daos":
        entry[field] = value
    elif field in {"expires", "allowed_until"}:
        entry[field] = value
    else:
        entry[field] = value
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    with pytest.raises(migration.PolicyError) as exc:
        migration.load_policy(policy)
    assert str(exc.value) == "invalid DB policy configuration"


def test_policy_rejects_permanent_operation_without_leaking_value(tmp_path):
    entry = _entry("insert", signature=False, operation="permanent")
    policy, _ = _fixture(tmp_path, SOURCE, [entry])

    with pytest.raises(migration.PolicyError) as exc:
        migration.load_policy(policy)
    assert str(exc.value) == "invalid DB policy configuration"

    result = _run_cli(tmp_path, ["--check", "--policy", policy])
    assert result.returncode == 2
    assert result.stdout == ""
    assert result.stderr.strip() == "invalid DB policy configuration"
    assert "permanent" not in result.stderr


@pytest.mark.parametrize("field", ["reason", "owner", "linked_issue"])
def test_policy_rejects_missing_required_metadata(tmp_path, field):
    entry = _entry("insert", signature=False)
    del entry[field]
    policy, _ = _fixture(tmp_path, SOURCE, [entry])
    with pytest.raises(migration.PolicyError):
        migration.load_policy(policy)


def test_report_rejects_unknown_keys_and_raw_values(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("absent", params=["Int"])])
    row = migration.migrate(migration.load_policy(policy), tmp_path)[1][0]
    row["raw_secret"] = "DO_NOT_SERIALIZE"
    with pytest.raises(migration.PolicyError):
        migration._report(tmp_path / "report.json", policy, tmp_path, [row])


def test_checked_in_migration_report_and_candidate_contract():
    root = Path(__file__).resolve().parents[1]
    report = json.loads((root / "build/guardrail-p1-p2/policy-signature-migration.json").read_text(encoding="utf-8"))
    assert list(report)[:2] == ["schema", "schema_version"]
    assert set(report) == {"schema", "schema_version", "policy", "counts", "resolved", "unresolved"}
    assert report["schema"] == "cost-aggregator.policy-signature-migration"
    assert report["schema_version"] == 1
    assert report["policy"] == "config/guards/db_ownership_policy.yml"
    assert report["counts"] == {"input": 99, "resolved": 9, "unresolved": 90}
    assert len(report["resolved"]) == 9
    assert len(report["unresolved"]) == 90
    assert all(set(row) == migration._RESOLVED_KEYS for row in report["resolved"])
    assert all(set(row) == migration._UNRESOLVED_KEYS for row in report["unresolved"])
    for row in report["resolved"]:
        # Resolved rows intentionally have no status field; their section is the status.
        assert migration.canonical_source_path(row["file"]) == row["file"]
        assert migration._IDENTIFIER_RE.fullmatch(row["class"])
        assert migration._IDENTIFIER_RE.fullmatch(row["method"])
        assert migration._IDENTIFIER_RE.fullmatch(row["dao"])
        assert migration._IDENTIFIER_RE.fullmatch(row["operation"])
        signature = row["signature"]
        assert set(signature) == {"parameters", "receiver"}
        assert isinstance(signature["parameters"], list)
        assert all(isinstance(value, str) for value in signature["parameters"])
        assert signature["receiver"] is None or isinstance(signature["receiver"], str)
    for row in report["unresolved"]:
        assert set(row) == {"status", "file", "class", "method", "dao", "operation",
                            "signature_evidence", "reason_code"}
        assert row["status"] in migration.STATUS
        assert row["reason_code"] in migration.REASON_CODES
        assert migration.canonical_source_path(row["file"]) == row["file"]
        assert migration._IDENTIFIER_RE.fullmatch(row["class"])
        assert migration._IDENTIFIER_RE.fullmatch(row["method"])
        assert row["dao"] is None or migration._IDENTIFIER_RE.fullmatch(row["dao"])
        assert migration._IDENTIFIER_RE.fullmatch(row["operation"])
        assert set(row["signature_evidence"]) <= {"status", "parameters", "receiver"}
        evidence = row["signature_evidence"]
        if "status" in evidence:
            assert evidence["status"] in migration.STATUS | {"POLICY_PROVIDED"}
        if "parameters" in evidence:
            assert isinstance(evidence["parameters"], list)
            assert all(isinstance(value, str) for value in evidence["parameters"])
        if "receiver" in evidence:
            assert evidence["receiver"] is None or isinstance(evidence["receiver"], str)

    candidate = yaml.safe_load(
        (root / "config/guards/db_ownership_policy.signatures.candidate.yml").read_text(encoding="utf-8")
    )
    candidate_bytes = (root / "config/guards/db_ownership_policy.signatures.candidate.yml").read_bytes()
    assert "—".encode("utf-8") in candidate_bytes
    assert b"\\u2014" not in candidate_bytes
    assert len(candidate["entries"]) == 9
    active_policy = root / "config/guards/db_ownership_policy.yml"
    active_before = active_policy.read_bytes()
    active = migration.load_policy(active_policy)
    generated_candidate, generated_rows = migration.migrate(active, root)
    assert len(generated_candidate["entries"]) == 9
    assert active_policy.read_bytes() == active_before
    report_symbols = {(row["file"], row["class"], row["method"])
                      for row in report["resolved"]}
    candidate_symbols = {(row["path"], row["class"], row["method"])
                         for row in candidate["entries"]}
    assert candidate_symbols == report_symbols
    assert not candidate_symbols.intersection(
        {(row["file"], row["class"], row["method"]) for row in report["unresolved"]}
    )
    active_by_symbol = {(entry["path"], entry["class"], entry["method"]): entry
                        for entry in active["entries"]}
    resolved_by_symbol = {(row["identity"]["path"], row["identity"]["class"], row["identity"]["method"]): row
                          for row in generated_rows if row["status"] == "RESOLVED_EXACTLY"}
    assert len(resolved_by_symbol) == 9
    for candidate_entry in candidate["entries"]:
        symbol = (candidate_entry["path"], candidate_entry["class"], candidate_entry["method"])
        assert set(candidate_entry) == set(active_by_symbol[symbol]) | {"signature"}
        for key, value in active_by_symbol[symbol].items():
            assert candidate_entry[key] == value
        assert candidate_entry["signature"] == resolved_by_symbol[symbol]["signature"]
    assert not any(
        (entry["path"], entry["class"], entry["method"])
        not in resolved_by_symbol for entry in candidate["entries"]
    )


def test_checked_artifacts_are_read_only_for_check_and_proposal(tmp_path):
    root = Path(__file__).resolve().parents[1]
    policy = root / "config/guards/db_ownership_policy.yml"
    report = root / "build/guardrail-p1-p2/policy-signature-migration.json"
    candidate = root / "config/guards/db_ownership_policy.signatures.candidate.yml"
    before = {path: path.read_bytes() for path in (policy, report, candidate)}
    fake_script = root / "scripts/migrate_db_policy_signatures.py"

    checked = _run_cli(tmp_path, ["--check", "--policy", policy, "--report", report],
                       fake_script=fake_script)
    assert checked.returncode == 1
    assert {path: path.read_bytes() for path in (policy, report, candidate)} == before

    proposed = tmp_path / "new-candidate.yml"
    proposed_result = _run_cli(
        tmp_path, ["--write-candidate", "--policy", policy, "--output", proposed,
                   "--report", report], fake_script=fake_script,
    )
    assert proposed_result.returncode == 1
    assert proposed.exists()
    assert {path: path.read_bytes() for path in (policy, report, candidate)} == before
