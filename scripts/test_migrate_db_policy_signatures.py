"""Contract tests for the DB-policy signature migration CLI.

The suite uses only temporary, canonical ``app/src/main/...`` Kotlin fixtures;
the migration implementation and its real command-line entry point are used,
not a reimplementation of discovery.  There are 22 tests (including
parametrized cases) covering one fixture family with deliberately narrow,
explicit policy entries.  No baseline or wildcard policy is used.
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
    assert json.loads(report.read_text(encoding="utf-8"))["entries"][0]["status"] == expected
    assert result.stdout == result.stderr == ""


def test_cli_generic_type_parameter_is_fail_closed_and_does_not_change_policy_or_candidate(tmp_path):
    secret = "GENERIC_SOURCE_SECRET"
    source = f"package example\nclass Fixture {{\n    fun <T : MissingBound> insert(value: T) {{ ExpenseDao.insert(value) }} // {secret}\n}}\n"
    policy, _ = _fixture(tmp_path, source, [_entry("insert", signature=False)])
    candidate = tmp_path / "nested" / "candidate" / "policy.yml"
    candidate.parent.mkdir(parents=True, exist_ok=True)
    candidate.write_text("sentinel candidate\n", encoding="utf-8")
    before_policy = policy.read_bytes()
    before_candidate = candidate.read_bytes()
    report = tmp_path / "nested" / "reports" / "generic.json"
    result = _run_cli(tmp_path, ["--write-candidate", "--policy", policy,
                                 "--output", candidate, "--report", report])
    assert result.returncode == 1
    assert json.loads(report.read_text(encoding="utf-8"))["entries"][0]["status"] == "SIGNATURE_UNSUPPORTED"
    assert policy.read_bytes() == before_policy
    assert candidate.read_bytes() == before_candidate
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


def test_write_candidate_writes_only_candidate_atomically(tmp_path):
    policy, _ = _fixture(tmp_path, SOURCE, [_entry("insert", signature=False)])
    output = tmp_path / "candidate.yml"
    result = _run_cli(tmp_path, ["--write-candidate", "--policy", policy, "--output", output])
    assert result.returncode == 0 and output.exists()
    assert policy.read_bytes() != output.read_bytes()
    assert not list(tmp_path.glob(".db-policy-*.tmp"))
    assert yaml.safe_load(output.read_text(encoding="utf-8"))["entries"][0]["signature"]


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
    assert [row["location"]["entry"] for row in payload["entries"]] == [1, 2]
    assert payload["statuses"] == sorted(payload["statuses"])


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
