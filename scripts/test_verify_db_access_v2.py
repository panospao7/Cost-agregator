"""D4c acceptance tests for the protocol-v2 DB discovery CLI.

These tests deliberately use throw-away ``app/src/main/java`` trees and invoke
the real verifier entry point.  The legacy tuple/stdout tests remain in
``test_verify_db_access_boundaries.py`` and are intentionally not changed.
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

from scripts.ci.guard_findings import GuardRunReport
from scripts.db_guard.scanner import _diag_from_text
from scripts.db_guard import reporting
from scripts.db_guard import room_inventory
from scripts.db_guard.policy_legacy import (
    legacy_ownership_entry_metadata_errors as ownership_entry_metadata_errors,
)
# Kept on the CLI module: these tests invoke the real verifier entry point
# in-process (CLI-level integration) alongside their subprocess runs.
import scripts.verify_db_access_boundaries as _verifier_module
from scripts.verify_db_access_boundaries import (
    main as verify_main,
    structural_manifest_metadata_errors,
)


SCRIPT = Path(__file__).with_name("verify_db_access_boundaries.py")
CANONICAL = "app/src/main/java/example/Fixture.kt"
SOURCE = """\
package example

data class Item(val id: Int)

@androidx.room.Dao
interface ExpenseDao {
    @androidx.room.Insert
    fun insert(item: Item)
}

class Repository(private val expenseDao: ExpenseDao) {
    fun save(item: Item) {
        expenseDao.insert(item)
    }
}
"""

STRUCTURAL_SOURCE = SOURCE + """

class StructuralRepository(private val context: SQLiteDatabase) {
    fun structural(item: Item) {
        context.getDatabasePath("expense.db")
    }
}
"""


def _write(root: Path, relative: str, text: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def _fixture(tmp_path: Path, *, source: str = SOURCE) -> Path:
    _write(tmp_path, CANONICAL, source)
    # Canonical raw-query policy document: exactly {"version": 1, "methods":
    # [...]}. An empty methods list is valid and matches fixtures that
    # declare no @RawQuery callables, so no policy diagnostic fires.
    _write(tmp_path, "config/guards/raw.yml", "version: 1\nmethods: []\n")
    _write_fixture_manifest(tmp_path, structural_entries=[])
    return tmp_path


def _write_fixture_manifest(
    root: Path, *, structural_entries: list[dict],
) -> Path:
    """Write manifest evidence matching the temporary policies in ``root``.

    Fixture scans must not accidentally validate against the production
    pinned structural contract.  Keep this helper deliberately exact:
    structural entries are copied into ``expected``, there are no implicit
    fixture exceptions, and ``counts`` carries ``structural_entries`` ONLY —
    ownership cardinality is never manifest metadata (GR-04 decoupling).
    """
    manifest = {
        "expected": structural_entries,
        "fixtures": [],
        "counts": {
            "structural_entries": len(structural_entries),
        },
    }
    path = root / "config/guards/db_structural_exceptions_expected_methods.yml"
    return _write(root, str(path.relative_to(root)), yaml.safe_dump(manifest, sort_keys=False))


def _sync_fixture_manifest(root: Path) -> Path:
    structural = yaml.safe_load((root / "config/guards/structural.yml").read_text(encoding="utf-8"))
    return _write_fixture_manifest(
        root,
        structural_entries=list((structural or {}).get("entries", [])),
    )


def _policy(root: Path, *, method: str = "save", barrier: bool = False,
            class_name: str = "Repository", dao: str = "expenseDao",
            operation: str = "insert") -> Path:
    # ``parameters`` must use the matcher's RESOLVED canonical spelling:
    # kotlin_callable_parser._resolve_type resolves project-local parameter
    # types to their package-qualified FQCN (closed-world resolution; only
    # builtins keep simple names), and _policy_matches compares the policy
    # signature against that resolved identity exactly.  A simple-name
    # ``[Item]`` entry is valid metadata but matches nothing, so the mutation
    # would be reported DB_UNAUTHORIZED_MUTATION instead of authorized (or,
    # with barrier_required=true, instead of DB_MISSING_WRITE_BARRIER).
    text = f"""entries:
  - path: {CANONICAL}
    class: {class_name}
    method: {method}
    daos: [{dao}]
    operation: {operation}
    signature:
      receiver: null
      kind: function
      parameters: [example.Item]
    barrier_required: {'true' if barrier else 'false'}
    reason: fixture
    owner: '@d4c'
    linked_issue: D4C-001
"""
    return _write(root, "config/guards/ownership.yml", text)


def _structural_policy(root: Path, entries: str = "") -> Path:
    return _write(root, "config/guards/structural.yml", "entries: []\n" if not entries else "entries:\n" + entries)


def _run(root: Path, report: Path | None = None, *extra: str,
         env: dict[str, str] | None = None,
         sync_manifest: bool = True) -> subprocess.CompletedProcess[str]:
    config_root = root
    if tuple(root.parts[-4:]) == ("app", "src", "main", "java"):
        config_root = root.parents[3]
    manifest = config_root / "config/guards/db_structural_exceptions_expected_methods.yml"
    if sync_manifest and all(
        (config_root / relative).is_file()
        for relative in (
            "config/guards/ownership.yml",
            "config/guards/structural.yml",
        )
    ):
        # Leave malformed fixture inputs untouched so the verifier, rather
        # than the test helper, owns the fail-closed diagnostic.
        try:
            _sync_fixture_manifest(config_root)
        except (OSError, yaml.YAMLError):
            pass
    argv = [sys.executable, str(SCRIPT), "--root", str(root),
            "--ownership-policy", str(config_root / "config/guards/ownership.yml"),
            "--structural-exceptions", str(config_root / "config/guards/structural.yml"),
            "--structural-manifest", str(manifest),
            "--raw-query-policy", str(config_root / "config/guards/raw.yml")]
    if report is not None:
        argv += ["--findings-output", str(report)]
    argv += list(extra)
    if _EVIDENCE_BYPASS_ACTIVE:
        # The scanner-isolation fixture patches the verifier module in THIS
        # interpreter; a child process would reload the real evidence stage
        # and defeat the isolation.  Dispatch the identical argv in-process.
        captured_out, captured_err = io.StringIO(), io.StringIO()
        previous_env = os.environ.copy()
        try:
            # Tests own the protocol selector (same rule as the subprocess
            # path below): a developer/CI shell setting must not leak in.
            os.environ.pop("COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA", None)
            if env:
                os.environ.update(env)
            with contextlib.redirect_stdout(captured_out), \
                    contextlib.redirect_stderr(captured_err):
                returncode = verify_main(argv[2:])
        finally:
            os.environ.clear()
            os.environ.update(previous_env)
        result = subprocess.CompletedProcess(
            argv, returncode,
            stdout=captured_out.getvalue(), stderr=captured_err.getvalue(),
        )
    else:
        child_env = os.environ.copy()
        # Tests own the protocol selector.  A developer/CI shell setting must
        # not make the absent-schema case accidentally exercise another
        # contract.
        child_env.pop("COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA", None)
        if env:
            child_env.update(env)
        result = subprocess.run(argv, cwd=str(Path(__file__).parents[1]), env=child_env,
                                text=True, capture_output=True, check=False)
    # Protocol-v2 reports are the complete machine-readable output contract;
    # the child must not put human PASS/FAIL/status lines on stdout or leak
    # filesystem/source/exception payloads on stderr.
    _assert_cli_streams(result, root, diagnostic=result.returncode == 2)
    return result


# Module-level toggle: when True, ``_run`` dispatches the identical argv
# through ``verify_main`` in-process so the evidence-bypass patch below is
# visible to the CLI pipeline (a child interpreter would reload the real
# stage and silently defeat the isolation).
_EVIDENCE_BYPASS_ACTIVE = False


@pytest.fixture
def _bypass_evidence(monkeypatch: pytest.MonkeyPatch):
    """Scanner-stage isolation for protocol-v2 CLI tests.

    Since GR-01, ``main()`` runs ``verify_ownership_policy_source_evidence``
    BEFORE scanner matching and collapses any evidence failure into the
    context-free umbrella ``DB_POLICY_SOURCE_EVIDENCE_INVALID``, so synthetic
    fixtures never reach the scanner stage these tests target.  Tests pinned
    by this fixture stub ONLY that one stage to pass; structural-manifest
    verification, scanning, inventory, reporting, and every later stage stay
    real.  Source-evidence behavior itself keeps dedicated coverage in
    ``test_verify_db_access_boundaries.py`` and the ``db_guard`` policy
    suites, plus the ordering pin in
    ``test_evidence_gate_runs_before_scanner_matching``.
    """
    global _EVIDENCE_BYPASS_ACTIVE
    monkeypatch.setattr(
        _verifier_module, "verify_ownership_policy_source_evidence",
        lambda *args, **kwargs: [],
    )
    _EVIDENCE_BYPASS_ACTIVE = True
    yield
    _EVIDENCE_BYPASS_ACTIVE = False


REPORT_KEYS = {"schema", "schema_version", "guard", "findings", "diagnostics", "statistics"}
FINDING_KEYS = {"rule", "severity", "path", "location", "symbol", "identity", "message"}
DIAGNOSTIC_KEYS = {"code", "path", "symbol", "controlled_context"}


def _finding_sort_key(item: dict) -> tuple:
    symbol = item["symbol"]
    return (
        item["rule"], item["path"], symbol["owner"], symbol["name"],
        symbol["receiver"] if symbol["receiver"] is not None else "<none>",
        tuple(symbol["parameters"]), symbol["kind"],
        tuple(sorted(item["identity"].items())),
        tuple(item["location"].get(name, 0) or 0
              for name in ("line", "column", "end_line", "end_column")),
    )


def _diagnostic_sort_key(item: dict) -> tuple:
    return (
        item["code"], item["path"] or "", item["symbol"] or "",
        json.dumps(item["controlled_context"], sort_keys=True, separators=(",", ":")),
    )


def _read_report_bytes(path: Path) -> tuple[bytes, dict]:
    """Read the persisted protocol payload and validate it, byte-for-byte ready."""
    payload = path.read_bytes()
    data = json.loads(payload.decode("utf-8"))
    GuardRunReport.from_dict(data)
    return payload, data


def _report(path: Path, expected_report: dict) -> dict:
    """Read, validate, and assert exact report equality.

    ``expected_report`` is required and must specify every top-level field
    (schema, schema_version, guard, findings, diagnostics, statistics).
    The helper asserts exact structural equality — no partial kwargs,
    no self-derived values, no ``None``-means-skip semantics.
    """
    _payload, data = _read_report_bytes(path)
    assert set(data) == REPORT_KEYS, f"unexpected report keys: {set(data) ^ REPORT_KEYS}"
    assert data == expected_report, (
        f"report mismatch:\n  actual  : {data!r}\n  expected: {expected_report!r}"
    )
    return data


_CLEAN_STATISTICS = {
    "files_scanned": 1,
    "declarations_scanned": 3,
    "inventory_daos": 1,
    "inventory_mutators": 1,
    "trusted": True,
}


def _expected(*, findings=None, diagnostics=None, statistics=None):
    """Build a complete expected report dict from caller-supplied fields.

    When ``statistics`` is ``None`` the helper derives ``{"trusted": False}``
    for diagnostic-bearing reports and the canonical clean-run statistics
    otherwise.  Every value is caller-supplied or fixture-deterministic —
    never derived from the actual report under test.
    """
    if statistics is None:
        statistics = {"trusted": False} if diagnostics else _CLEAN_STATISTICS
    return {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": findings or [],
        "diagnostics": diagnostics or [],
        "statistics": statistics,
    }


def _assert_cli_streams(result: subprocess.CompletedProcess[str], root: Path,
                        *, diagnostic: bool = False) -> None:
    assert result.stdout == ""
    assert str(root) not in result.stderr
    assert "Traceback" not in result.stderr
    assert "exception" not in result.stderr.lower()
    if diagnostic:
        assert result.stderr in {
            "ERROR: DB access discovery infrastructure diagnostics present\n",
            "ERROR: DB_FINDINGS_WRITE_FAILED\n",
        }
    else:
        assert result.stderr == ""


def _assert_deterministic_bytes(first: Path, second: Path) -> None:
    assert first.read_bytes() == second.read_bytes()


def _codes(data: dict, key: str) -> list[str]:
    return [item["rule"] if key == "findings" else item["code"]
            for item in data[key]]


def test_clean_run_writes_valid_guard_report_v2(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "out" / "findings.json"
    # The findings writer fails closed on a missing output parent
    # (``write_report_atomic`` -> MISSING_PARENT -> DB_FINDINGS_WRITE_FAILED);
    # creating the output directory is the caller's responsibility, so the
    # fixture owns it before invoking the CLI.
    report.parent.mkdir(parents=True, exist_ok=True)

    result = _run(root, report)

    assert result.returncode == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_successful_report_bytes_are_deterministic(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    first, second = root / "first.json", root / "second.json"

    assert _run(root, first).returncode == 0
    assert _run(root, second).returncode == 0
    expected = {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    }
    _report(first, expected)
    _report(second, expected)
    _assert_deterministic_bytes(first, second)


def test_diagnostics_only_report_bytes_are_deterministic(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    _write(root, "config/guards/raw.yml", "entries: [malformed]\n")
    first, second = root / "first.json", root / "second.json"

    assert _run(root, first, "--fail-on-violation").returncode == 2
    assert _run(root, second, "--fail-on-violation").returncode == 2
    expected = {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_RAW_QUERY_POLICY_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    }
    _report(first, expected)
    _report(second, expected)
    _assert_deterministic_bytes(first, second)


def test_unauthorized_mutation_is_a_finding_and_fail_on_violation_exits_one(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _write(root, "config/guards/ownership.yml", "entries: []\n")
    _structural_policy(root)
    report = root / "unauthorized.json"

    result = _run(root, report, "--fail-on-violation")

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "save",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN).
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {
                    "dao": "example.ExpenseDao",
                    "accessor": "expenseDao",
                    "operation": "insert",
                    "mutation_kind": "ROOM_INSERT",
                    "call_form": "receiver",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            }
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_valid_findings_are_strict_exit_one_without_fail_flag(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _write(root, "config/guards/ownership.yml", "entries: []\n")
    _structural_policy(root)
    report = root / "findings.json"
    result = _run(root, report)
    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "save",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN).
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {
                    "dao": "example.ExpenseDao",
                    "accessor": "expenseDao",
                    "operation": "insert",
                    "mutation_kind": "ROOM_INSERT",
                    "call_form": "receiver",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            }
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_name_only_policy_cannot_authorize_same_name_overloads(
    tmp_path: Path, _bypass_evidence,
) -> None:
    # The fixture DAO genuinely declares TWO mutating methods (two @Insert
    # overloads), so ``inventory_mutators == 2`` is correct inventory truth:
    # the inventory counts declared mutators, never authorized ones.  The
    # anti-authorization intent is carried by the policy-side assertions
    # below: the exact-signature policy authorizes only the overload the call
    # site actually resolves to, and neither the uncalled overload nor any
    # other declaration produces findings or diagnostics.
    source = SOURCE.replace(
        "fun insert(item: Item)",
        "fun insert(item: Item)\n    @androidx.room.Insert\n    fun insert(label: String)",
    )
    root = _fixture(tmp_path, source=source)
    _policy(root)
    _structural_policy(root)
    report = root / "overload.json"
    result = _run(root, report)
    assert result.returncode == 0
    data = _report(report, _expected(statistics={
        "files_scanned": 1,
        "declarations_scanned": 3,
        "inventory_daos": 1,
        "inventory_mutators": 2,
        "trusted": True,
    }))
    assert data["findings"] == []
    assert data["diagnostics"] == []


def test_overloaded_dao_with_unresolved_argument_type_is_not_authorized(
    tmp_path: Path, _bypass_evidence,
) -> None:
    # The probe argument must be an expression with NO resolvable type
    # (unknown constructor), so ``_argument_types`` returns None and the
    # scanner fails closed with DB_SIGNATURE_UNRESOLVED before any overload
    # filtering.  A known-typed argument that matches no overload would
    # instead report DB_CALL_TARGET_AMBIGUOUS.
    source = SOURCE.replace(
        "fun insert(item: Item)",
        "fun insert(item: Item)\n    @androidx.room.Insert\n    fun insert(label: String)",
    ).replace(
        "    }\n}\n",
        "    }\n\n    fun saveUnknown(value: Any) {\n        expenseDao.insert(UnknownItem())\n    }\n}\n",
        1,
    )
    root = _fixture(tmp_path, source=source)
    _policy(root)
    _structural_policy(root)
    report = root / "overload-unresolved.json"

    result = _run(root, report)

    assert result.returncode == 2
    data = _report(report, _expected(diagnostics=[{
        "code": "DB_SIGNATURE_UNRESOLVED", "path": CANONICAL,
        "symbol": None, "controlled_context": {},
    }]))
    assert data["findings"] == []
    assert _codes(data, "diagnostics") == ["DB_SIGNATURE_UNRESOLVED"]


@pytest.mark.parametrize("expression", [
    "holder(expenseDao).insert(item)",
    "array[expenseDao].insert(item)",
    "map[expenseDao].insert(item)",
    "context.expenseDao.insert(item)",
    "expenseDao?.insert(item)",
    "expenseDao  ?.  insert(item)",
    "context?.expenseDao?.insert(item)",
    "other?.expenseDao.insert(item)",
    "context.nested.expenseDao.insert(item)",
    "(context.expenseDao).insert(item)",
    "(context?.expenseDao)?.insert(item)",
    "((context.expenseDao)).insert(item)",
])
def test_qualified_dao_receiver_is_unresolved_in_structured_report(
    tmp_path: Path, expression: str, _bypass_evidence,
) -> None:
    source = SOURCE.replace("expenseDao.insert(item)", expression)
    root = _fixture(tmp_path, source=source)
    _policy(root)
    _structural_policy(root)
    report = root / "qualified-receiver.json"

    result = _run(root, report)

    assert result.returncode == 2
    data = _report(report, _expected(diagnostics=[{
        "code": "DB_DAO_SCOPE_UNRESOLVED", "path": CANONICAL,
        "symbol": None, "controlled_context": {},
    }]))
    assert data["findings"] == []
    assert _codes(data, "diagnostics") == ["DB_DAO_SCOPE_UNRESOLVED"]
    diagnostic = data["diagnostics"][0]
    assert diagnostic["path"] == CANONICAL


def test_direct_dao_receiver_remains_exactly_authorized_in_structured_report(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "direct-receiver.json"

    result = _run(root, report)

    assert result.returncode == 0
    data = _report(report, _expected())
    assert data["findings"] == []
    assert data["diagnostics"] == []


def test_unrelated_direct_dao_receiver_keeps_structured_identity(
    tmp_path: Path, _bypass_evidence,
) -> None:
    source = SOURCE.replace(
        "private val expenseDao: ExpenseDao",
        "private val expenseDao: ExpenseDao, private val otherDao: ExpenseDao",
    ).replace("expenseDao.insert(item)", "otherDao.insert(item)")
    root = _fixture(tmp_path, source=source)
    _policy(root)
    _structural_policy(root)
    report = root / "unrelated-receiver.json"

    result = _run(root, report)

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "save",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN).
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {
                    "dao": "example.ExpenseDao",
                    "accessor": "otherDao",
                    "operation": "insert",
                    "mutation_kind": "ROOM_INSERT",
                    "call_form": "receiver",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            }
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_positive_finding_contains_complete_callable_identity(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _write(root, "config/guards/ownership.yml", "entries: []\n")
    _structural_policy(root)
    report = root / "complete-identity.json"

    result = _run(root, report)

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "save",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN).
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {
                    "dao": "example.ExpenseDao",
                    "accessor": "expenseDao",
                    "operation": "insert",
                    "mutation_kind": "ROOM_INSERT",
                    "call_form": "receiver",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            }
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_property_body_is_not_silently_skipped(
    tmp_path: Path, _bypass_evidence,
) -> None:
    """A property accessor body is scanned, never skipped.

    The getter's ``expenseDao.insert(Item(1))`` mutation IS discovered inside
    the property declaration range; its constructor argument has no resolvable
    type, so the scan fails closed with the controlled DB_SIGNATURE_UNRESOLVED
    diagnostic (exit 2, findings withheld) instead of silently ignoring the
    body or guessing an authorization.
    """
    source = SOURCE + """
class PropertyRepository(private val expenseDao: ExpenseDao) {
    val saved: Item
        get() { expenseDao.insert(Item(1)); return Item(1) }
}
"""
    root = _fixture(tmp_path, source=source)
    _policy(root)
    _structural_policy(root)
    report = root / "property.json"
    result = _run(root, report)
    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_SIGNATURE_UNRESOLVED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


@pytest.mark.parametrize(("body", "expected_report"), [
    # Mutation arguments must use forms the scanner can resolve (locals with
    # explicit type annotations or constructor parameters).  A bare
    # ``Item(1)`` constructor expression has no resolvable argument type and
    # fails closed with DB_SIGNATURE_UNRESOLVED before authorization.
    (
        "class PropertyInitializer(private val expenseDao: ExpenseDao,\n"
        "        private val known: Item) {\n"
        "    val cached = expenseDao.insert(known)\n"
        "}",
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [
                {
                    "rule": "DB_UNAUTHORIZED_MUTATION",
                    "severity": "error",
                    "path": "app/src/main/java/example/Fixture.kt",
                    "location": {"line": 19, "end_line": 19},
                    "symbol": {
                        "owner": "example.PropertyInitializer",
                        "name": "cached",
                        "receiver": None,
                        "parameters": [],
                        "kind": "initializer",
                    },
                    "identity": {
                        "dao": "example.ExpenseDao",
                        "accessor": "expenseDao",
                        "operation": "insert",
                        "mutation_kind": "ROOM_INSERT",
                        "call_form": "receiver",
                    },
                    "message": "Database mutation is not owned by an exact policy entry",
                }
            ],
            "diagnostics": [],
            "statistics": {
                "files_scanned": 1,
                "declarations_scanned": 5,
                "inventory_daos": 1,
                "inventory_mutators": 1,
                "trusted": True,
            },
        },
    ),
    (
        "class PropertyGetter(private val expenseDao: ExpenseDao) {\n"
        "    val cached: Item\n"
        "        get() { val item: Item = Item(1); expenseDao.insert(item); return item }\n"
        "}",
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [
                {
                    "rule": "DB_UNAUTHORIZED_MUTATION",
                    "severity": "error",
                    "path": "app/src/main/java/example/Fixture.kt",
                    "location": {"line": 19, "end_line": 19},
                    "symbol": {
                        "owner": "example.PropertyGetter",
                        "name": "cached",
                        "receiver": None,
                        "parameters": [],
                        "kind": "property_getter",
                    },
                    "identity": {
                        "dao": "example.ExpenseDao",
                        "accessor": "expenseDao",
                        "operation": "insert",
                        "mutation_kind": "ROOM_INSERT",
                        "call_form": "receiver",
                    },
                    "message": "Database mutation is not owned by an exact policy entry",
                }
            ],
            "diagnostics": [],
            "statistics": {
                "files_scanned": 1,
                "declarations_scanned": 5,
                "inventory_daos": 1,
                "inventory_mutators": 1,
                "trusted": True,
            },
        },
    ),
    (
        # Class-level ``init`` blocks are, by the documented declaration
        # contract, part of the skipped class-owner range: they produce no
        # declaration range and therefore no finding (the same contract the
        # D4 scanner suite pins for its Scopes fixture).  With the baseline
        # save() mutation authorized, this variant pins the resulting trusted
        # clean scan EXACTLY -- including declarations_scanned=4 (no init
        # range is discovered) -- so any silent change to init-block
        # discovery shows up here.
        "class Initializer(private val expenseDao: ExpenseDao) {\n"
        "    init { val item: Item = Item(1); expenseDao.insert(item) }\n"
        "}",
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [],
            "diagnostics": [],
            "statistics": {
                "files_scanned": 1,
                "declarations_scanned": 4,
                "inventory_daos": 1,
                "inventory_mutators": 1,
                "trusted": True,
            },
        },
    ),
    (
        "object Helper { fun write(dao: ExpenseDao) { val item: Item = Item(1); dao.insert(item) } }",
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [
                {
                    "rule": "DB_UNAUTHORIZED_MUTATION",
                    "severity": "error",
                    "path": "app/src/main/java/example/Fixture.kt",
                    "location": {"line": 17, "end_line": 17},
                    "symbol": {
                        "owner": "example.Helper",
                        "name": "write",
                        "receiver": None,
                        # Resolved canonical spelling (package-qualified FQCN).
                        "parameters": ["example.ExpenseDao"],
                        "kind": "function",
                    },
                    "identity": {
                        "dao": "example.ExpenseDao",
                        "accessor": "dao",
                        "operation": "insert",
                        "mutation_kind": "ROOM_INSERT",
                        "call_form": "receiver",
                    },
                    "message": "Database mutation is not owned by an exact policy entry",
                }
            ],
            "diagnostics": [],
            "statistics": {
                "files_scanned": 1,
                "declarations_scanned": 5,
                "inventory_daos": 1,
                "inventory_mutators": 1,
                "trusted": True,
            },
        },
    ),
    (
        "fun topLevel(dao: ExpenseDao) { val item: Item = Item(1); dao.insert(item) }",
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [
                {
                    "rule": "DB_UNAUTHORIZED_MUTATION",
                    "severity": "error",
                    "path": "app/src/main/java/example/Fixture.kt",
                    "location": {"line": 17, "end_line": 17},
                    "symbol": {
                        "owner": "example",
                        "name": "topLevel",
                        "receiver": None,
                        # Resolved canonical spelling (package-qualified FQCN).
                        "parameters": ["example.ExpenseDao"],
                        "kind": "top_level_function",
                    },
                    "identity": {
                        "dao": "example.ExpenseDao",
                        "accessor": "dao",
                        "operation": "insert",
                        "mutation_kind": "ROOM_INSERT",
                        "call_form": "receiver",
                    },
                    "message": "Database mutation is not owned by an exact policy entry",
                }
            ],
            "diagnostics": [],
            "statistics": {
                "files_scanned": 1,
                "declarations_scanned": 4,
                "inventory_daos": 1,
                "inventory_mutators": 1,
                "trusted": True,
            },
        },
    ),
])
def test_d4_executable_declarations_are_discovered_with_structured_identity(
    tmp_path: Path, body: str, expected_report: dict, _bypass_evidence,
) -> None:
    source = SOURCE + "\n" + body + "\n"
    root = _fixture(tmp_path, source=source)
    # Authorize the shared SOURCE baseline mutation (Repository.save) exactly,
    # so every variant isolates ITS OWN executable declaration; an empty
    # policy would flag save@13 in each variant and drown the variant's
    # structured identity in unrelated baseline findings.
    _policy(root)
    _structural_policy(root)
    report = root / "d4.json"

    result = _run(root, report)

    # Findings exit 1; a variant whose expected report carries no findings
    # (see the init-block note in the parametrize table) documents a trusted
    # clean scan and exits 0.
    assert result.returncode == (1 if expected_report["findings"] else 0)
    _report(report, expected_report)


def test_unknown_argument_expression_is_not_authorized_by_arity(
    tmp_path: Path, _bypass_evidence,
) -> None:
    source = SOURCE + """
class ExpressionRepository(private val expenseDao: ExpenseDao) {
    fun saveUnknown(value: Any) { expenseDao.insert(value) }
}
"""
    root = _fixture(tmp_path, source=source)
    _policy(root, method="saveUnknown")
    _structural_policy(root)
    report = root / "expression.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_CALL_TARGET_AMBIGUOUS",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            },
        ],
        "statistics": {"trusted": False},
    })


def test_diag_text_keeps_path_canonical_and_parses_line() -> None:
    diagnostic = _diag_from_text("DB_SOURCE_UNREADABLE:app/src/main/java/Foo.kt:17")
    assert (diagnostic.path, diagnostic.code, diagnostic.symbol,
            diagnostic.controlled_context) == (
                "app/src/main/java/Foo.kt", "DB_SOURCE_UNREADABLE", None, {},
            )


def test_source_root_uses_project_defaults_for_policies(tmp_path: Path) -> None:
    root = _fixture(tmp_path)
    project_config = Path(__file__).parents[1] / "config/guards"
    for name in ("db_ownership_policy.yml", "db_structural_exceptions.yml",
                 "db_raw_query_classification.yml"):
        _write(root, f"config/guards/{name}",
               (project_config / name).read_text(encoding="utf-8"))
    report = root / "source-root-defaults.json"
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root / "app/src/main/java"),
         "--findings-output", str(report)],
        cwd=str(Path(__file__).parents[1]), text=True, capture_output=True, check=False,
    )
    _assert_cli_streams(result, root / "app/src/main/java", diagnostic=True)
    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_default_project_root_uses_canonical_manifest(tmp_path: Path) -> None:
    report = tmp_path / "default-root.json"
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--findings-output", str(report)],
        cwd=str(Path(__file__).parents[1]), text=True, capture_output=True, check=False,
        env={key: value for key, value in os.environ.items()
             if key != "COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA"},
    )

    assert result.returncode == 2
    _assert_cli_streams(result, Path(__file__).parents[1], diagnostic=True)
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_explicit_structural_manifest_is_selected_for_source_root_fixture(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path, source=STRUCTURAL_SOURCE)
    _policy(root)
    structural = _structural_policy(root, """  - path: app/src/main/java/example/Fixture.kt
    class: StructuralRepository
    method_pattern: structural
    operation: getDatabasePath
    reason: fixture
    owner: '@d4c'
    linked_issue: D4C-001
""")
    explicit = _write(root, "config/fixtures/explicit-structural-manifest.yml", """expected:
  - path: app/src/main/java/example/Fixture.kt
    class: StructuralRepository
    method_pattern: structural
    operation: getDatabasePath
    reason: fixture
    owner: '@d4c'
    linked_issue: D4C-001
fixtures: []
counts:
  structural_entries: 1
""")
    _write(root, "config/guards/db_structural_exceptions_expected_methods.yml",
           "entries: []\n")

    default_report = root / "default-manifest.json"
    # sync_manifest=False: the corrupted canonical manifest above IS the
    # fixture input under test; the helper must not repair it before the run.
    default_result = _run(root / "app/src/main/java", default_report,
                          sync_manifest=False)
    assert default_result.returncode == 2
    _report(default_report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })

    report = root / "explicit-manifest.json"
    result = _run(root / "app/src/main/java", report,
                  "--structural-exceptions", str(structural),
                  "--structural-manifest", str(explicit))

    assert result.returncode == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 5,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_required_barrier_is_reported(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root, barrier=True)
    _structural_policy(root)
    report = root / "barrier.json"

    result = _run(root, report, "--fail-on-violation")

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_MISSING_WRITE_BARRIER",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "save",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN);
                    # the barrier branch is only reachable once the policy
                    # entry's exact signature matches the resolved symbol.
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {
                    "dao": "example.ExpenseDao",
                    "operation": "insert",
                },
                "message": "Database write lacks required barrier evidence",
            },
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_satisfied_barrier_requirement_authorizes_the_mutation(
    tmp_path: Path, _bypass_evidence,
) -> None:
    """Pin the complementary reachable barrier outcome.

    ``barrier_required: true`` with REAL ``writeBarrier.checkWritesAllowed``
    evidence before the mutation authorizes cleanly: no finding, trusted
    scan, exit 0.  Together with ``test_required_barrier_is_reported`` this
    pins both outcomes of the scanner's barrier branch (the branch is only
    reachable once the policy entry's exact resolved signature matches).
    """
    source = SOURCE.replace(
        "    fun save(item: Item) {\n        expenseDao.insert(item)\n    }",
        "    fun save(item: Item) {\n"
        "        writeBarrier.checkWritesAllowed()\n"
        "        expenseDao.insert(item)\n"
        "    }",
    )
    root = _fixture(tmp_path, source=source)
    _policy(root, barrier=True)
    _structural_policy(root)
    report = root / "barrier-ok.json"

    result = _run(root, report)

    assert result.returncode == 0
    _report(report, _expected())


def test_structural_operation_is_reported(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path, source=STRUCTURAL_SOURCE)
    _policy(root)
    _structural_policy(root)
    report = root / "structural.json"

    result = _run(root, report, "--fail-on-violation")

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_FORBIDDEN_STRUCTURAL_OPERATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 20, "end_line": 20},
                "symbol": {
                    "owner": "example.StructuralRepository",
                    "name": "structural",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN).
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {"operation": "getDatabasePath"},
                "message": "Forbidden structural database operation",
            },
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 5,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_typed_structural_receiver_is_authorized_by_exact_fixture_entry(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path, source=STRUCTURAL_SOURCE)
    _policy(root)
    structural = _structural_policy(root, """  - path: app/src/main/java/example/Fixture.kt
    class: StructuralRepository
    method_pattern: structural
    operation: getDatabasePath
    reason: fixture
    owner: '@d4c'
    linked_issue: D4C-001
""")
    report = root / "typed-structural.json"

    result = _run(root, report, "--structural-exceptions", str(structural))

    assert result.returncode == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 5,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


WRITABLE_DATABASE_SOURCE = SOURCE + """

class WritableRepository(private val db: SQLiteDatabase) {
    fun read() {
        db.writableDatabase
    }
    fun allowed() {
        db.writableDatabase
    }
}
"""


def test_writable_database_property_has_exact_structural_identity(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path, source=WRITABLE_DATABASE_SOURCE)
    _policy(root)
    structural = _structural_policy(root, """  - path: app/src/main/java/example/Fixture.kt
    class: WritableRepository
    method_pattern: allowed
    operation: writableDatabase
    reason: fixture
    owner: '@d4c'
    linked_issue: D4C-001
""")
    report = root / "writable-property.json"

    result = _run(root, report, "--fail-on-violation",
                  "--structural-exceptions", str(structural))

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_FORBIDDEN_STRUCTURAL_OPERATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 20, "end_line": 20},
                "symbol": {
                    "owner": "example.WritableRepository",
                    "name": "read",
                    "receiver": None,
                    "parameters": [],
                    "kind": "function",
                },
                "identity": {"operation": "writableDatabase"},
                "message": "Forbidden structural database operation",
            },
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 6,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


@pytest.mark.parametrize("expression", [
    "db.writableDatabaseFoo",
    "other.writableDatabase",
    "getDb().writableDatabase",
    "db.other.writableDatabase",
])
def test_writable_database_property_rejects_prefix_other_and_unresolved_receivers(
    tmp_path: Path, expression: str, _bypass_evidence,
) -> None:
    source = SOURCE + f"""

class WritableRepository(private val db: SQLiteDatabase, private val other: String) {{
    fun read() {{
        {expression}
    }}
}}
"""
    root = _fixture(tmp_path, source=source)
    _policy(root, method="read")
    _structural_policy(root)
    report = root / "writable-negative.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                # The unsupported writableDatabase token sits on line 20 of
                # the fixture (SOURCE is 15 lines + blank/class/fun preamble);
                # _line_diagnostic carries it as bounded context.
                "controlled_context": {"line": 20},
            },
        ],
        "statistics": {"trusted": False},
    })


def test_infrastructure_diagnostic_discards_partial_findings(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    _write(root, "config/guards/raw.yml", "entries: [malformed]\n")
    report = root / "diagnostic.json"

    result = _run(root, report, "--fail-on-violation")

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_RAW_QUERY_POLICY_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_inventory_only_writes_inventory_schema_and_rejects_diagnostics(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    _sync_fixture_manifest(root)
    inventory_path = root / "out" / "inventory.json"
    inventory_path_repeat = root / "out" / "inventory-repeat.json"

    # The verifier API is real; this small seam supplies a directory fsync on
    # Windows, where O_DIRECTORY is not exposed by the standard library.
    # ``tempfile.mkstemp`` calls ``os.open(path, flags, mode)`` with three
    # positional arguments, so the seam forwards the trailing mode instead of
    # dropping it (a 2-arg lambda would turn every temp-file creation into a
    # TypeError sanitized into DB_ROOM_INVENTORY_WRITE_FAILED).
    directory_fd = 987654
    real_open, real_fsync, real_close = room_inventory.os.open, room_inventory.os.fsync, room_inventory.os.close
    monkeypatch.setattr(room_inventory.os, "O_DIRECTORY", 0x10000, raising=False)
    monkeypatch.setattr(
        room_inventory.os, "open",
        lambda path, flags, *rest: (
            directory_fd if flags & 0x10000 else real_open(path, flags, *rest)
        ),
    )
    monkeypatch.setattr(room_inventory.os, "fsync", lambda fd: None if fd == directory_fd else real_fsync(fd))
    monkeypatch.setattr(room_inventory.os, "close", lambda fd: None if fd == directory_fd else real_close(fd))

    assert verify_main(["--root", str(root), "--ownership-policy", str(root / "config/guards/ownership.yml"),
                        "--structural-exceptions", str(root / "config/guards/structural.yml"),
                        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
                        "--raw-query-policy", str(root / "config/guards/raw.yml"),
                        "--inventory-only", "--dump-room-mutators", str(inventory_path)]) == 0
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    assert (inventory["schema"], inventory["schema_version"]) == ("cost-aggregator.room-mutator-inventory", 1)
    assert inventory["diagnostics"] == []

    assert verify_main(["--root", str(root), "--ownership-policy", str(root / "config/guards/ownership.yml"),
                        "--structural-exceptions", str(root / "config/guards/structural.yml"),
                        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
                        "--raw-query-policy", str(root / "config/guards/raw.yml"),
                        "--inventory-only", "--dump-room-mutators", str(inventory_path_repeat)]) == 0
    assert inventory_path.read_bytes() == inventory_path_repeat.read_bytes()

    _write(root, "config/guards/raw.yml", "entries: [malformed]\n")
    bad = root / "bad-inventory.json"
    sentinel = b"keep this exact inventory"
    bad.write_bytes(sentinel)
    assert verify_main(["--root", str(root),
                        "--ownership-policy", str(root / "config/guards/ownership.yml"),
                        "--structural-exceptions", str(root / "config/guards/structural.yml"),
                        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
                        "--raw-query-policy", str(root / "config/guards/raw.yml"),
                        "--inventory-only", "--dump-room-mutators", str(bad),
                        "--findings-output", str(root / "bad-report.json")]) == 2
    assert bad.read_bytes() == sentinel
    _report(root / "bad-report.json", {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_RAW_QUERY_POLICY_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_dump_failure_overwrites_preexisting_report_with_diagnostics_only(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    _sync_fixture_manifest(root)
    report = root / "findings.json"
    inventory_path = root / "inventory.json"

    assert verify_main([
        "--root", str(root),
        "--ownership-policy", str(root / "config/guards/ownership.yml"),
        "--structural-exceptions", str(root / "config/guards/structural.yml"),
        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
        "--raw-query-policy", str(root / "config/guards/raw.yml"),
        "--inventory-only", "--findings-output", str(report),
    ]) == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })

    def fail_dump(*_args, **_kwargs):
        raise OSError("fixture failure")

    monkeypatch.setattr(room_inventory, "write_inventory_atomic", fail_dump)
    assert verify_main([
        "--root", str(root),
        "--ownership-policy", str(root / "config/guards/ownership.yml"),
        "--structural-exceptions", str(root / "config/guards/structural.yml"),
        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
        "--raw-query-policy", str(root / "config/guards/raw.yml"),
        "--inventory-only", "--dump-room-mutators", str(inventory_path),
        "--findings-output", str(report),
    ]) == 2

    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_INVENTORY_WRITE_FAILED",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_findings_output_write_failure_preserves_stale_destination_and_exits_two(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "findings.json"
    original = b'{"stale":true}\n'
    report.write_bytes(original)

    def fail_report(*_args, **_kwargs):
        raise OSError("injected atomic write failure")

    monkeypatch.setattr(reporting, "write_db_report_atomic", fail_report)
    result = verify_main([
        "--root", str(root),
        "--ownership-policy", str(root / "config/guards/ownership.yml"),
        "--structural-exceptions", str(root / "config/guards/structural.yml"),
        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
        "--raw-query-policy", str(root / "config/guards/raw.yml"),
        "--findings-output", str(report),
    ])

    assert result == 2
    assert report.read_bytes() == original


@pytest.mark.parametrize(("error", "expected_report"), [
    (
        room_inventory.InventoryWriteError,
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [],
            "diagnostics": [
                {
                    "code": "DB_ROOM_INVENTORY_WRITE_FAILED",
                    "path": None,
                    "symbol": None,
                    "controlled_context": {},
                }
            ],
            "statistics": {"trusted": False},
        },
    ),
    (
        room_inventory.InventoryDurabilityUnconfirmedError,
        {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [],
            "diagnostics": [
                {
                    "code": "INVENTORY_DURABILITY_UNCONFIRMED",
                    "path": None,
                    "symbol": None,
                    "controlled_context": {},
                }
            ],
            "statistics": {"trusted": False},
        },
    ),
])
def test_inventory_controlled_write_errors_preserve_catalog_code(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, error, expected_report,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    _sync_fixture_manifest(root)
    report = root / "write-error-report.json"
    destination = root / "inventory.json"
    original = b"preexisting"
    destination.write_bytes(original)

    def fail_dump(*_args, **_kwargs):
        raise error()

    monkeypatch.setattr(room_inventory, "write_inventory_atomic", fail_dump)
    result = verify_main([
        "--root", str(root), "--ownership-policy", str(root / "config/guards/ownership.yml"),
        "--structural-exceptions", str(root / "config/guards/structural.yml"),
        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
        "--raw-query-policy", str(root / "config/guards/raw.yml"), "--inventory-only",
        "--dump-room-mutators", str(destination), "--findings-output", str(report),
    ])
    assert result == 2
    assert destination.read_bytes() == original
    _report(report, expected_report)


@pytest.mark.parametrize("field,value", [
    ("kind", "unknown"),
    ("kind", "not-a-kind"),
    ("receiver", "*"),
    ("receiver", "List < String >"),
    ("parameters", ["*"]),
    ("parameters", ["List < String >"]),
])
def test_ownership_signature_metadata_rejects_noncanonical_identity(field, value):
    entry = {
        "path": CANONICAL, "class": "Repository", "method": "save",
        "daos": ["expenseDao"], "operation": "insert", "barrier_required": False,
        "reason": "fixture", "owner": "@d4c", "linked_issue": "D4C-001",
        "signature": {"receiver": None, "kind": "function", "parameters": ["Item"]},
    }
    entry["signature"][field] = value
    errors = ownership_entry_metadata_errors(entry)
    assert errors
    assert all(isinstance(error, str) and error for error in errors)


def test_ownership_signature_metadata_accepts_exact_canonical_identity():
    entry = {
        "path": CANONICAL, "class": "Repository", "method": "save",
        "daos": ["expenseDao"], "operation": "insert", "barrier_required": False,
        "reason": "fixture", "owner": "@d4c", "linked_issue": "D4C-001",
        "signature": {"receiver": None, "kind": "function", "parameters": ["Item"]},
    }
    assert ownership_entry_metadata_errors(entry) == []


def test_findings_file_environment_transport(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "env.json"

    result = _run(root, None, env={"COST_AGGREGATOR_GUARD_FINDINGS_FILE": str(report)})

    assert result.returncode == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


@pytest.mark.parametrize("schema, expected_exit, expected_report", [
    (None, 0, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    }),
    ("2", 0, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    }),
    ("1", 2, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_INVALID_INPUT",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    }),
    ("malformed", 2, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_INVALID_INPUT",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    }),
    ("2\nCOST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=1", 2, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_ROOM_INVALID_INPUT",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    }),
])
def test_findings_schema_environment_is_strict_and_report_remains_v2(
    tmp_path: Path, schema: str | None, expected_exit: int, expected_report: dict,
    _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "schema.json"
    env = {} if schema is None else {"COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA": schema}

    result = _run(root, report, env=env)

    assert result.returncode == expected_exit
    _report(report, expected_report)


@pytest.mark.parametrize("case", ["malformed-policy", "missing-policy", "missing-source", "bad-report"])
def test_invalid_inputs_exit_two_without_path_or_exception_leaks(tmp_path: Path, case: str) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "invalid.json"
    if case == "malformed-policy":
        _write(root, "config/guards/ownership.yml", "entries: [\n")
    elif case == "missing-policy":
        (root / "config/guards/ownership.yml").unlink()
    elif case == "missing-source":
        root = tmp_path / "does-not-exist"
    else:
        report = root / "missing-parent" / "report.json"

    result = _run(root, report)

    assert result.returncode == 2
    assert str(tmp_path) not in result.stderr
    assert "Traceback" not in result.stderr


@pytest.mark.parametrize(
    "manifest_case", ["missing", "stale", "legacy-ownership-count"],
)
def test_structural_manifest_failures_are_diagnostic_only_and_overwrite_old_findings(
    tmp_path: Path, manifest_case: str,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    manifest = root / "config/guards/db_structural_exceptions_expected_methods.yml"
    if manifest_case == "missing":
        manifest.unlink()
    elif manifest_case == "stale":
        _write(root, "config/guards/db_structural_exceptions_expected_methods.yml",
               manifest.read_text(encoding="utf-8").replace("counts:", "stale: true\ncounts:", 1))
    else:
        # Old-shape metadata: a counts block that still pins ownership
        # cardinality.  Under GR-04 this is unknown-count-key configuration,
        # not a count comparison input.
        _write(root, "config/guards/db_structural_exceptions_expected_methods.yml",
               "expected: []\nfixtures: []\ncounts:\n  ownership_entries: 99\n  structural_entries: 62\n")
    report = root / "manifest.json"
    report.write_text(json.dumps({"findings": [{"rule": "OLD"}], "statistics": {"trusted": True}}), encoding="utf-8")

    result = _run(root, report, sync_manifest=False)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })

    if manifest_case == "legacy-ownership-count":
        # Explicit GR-04 decoupling assertion: the legacy ownership count key
        # must be rejected as the ONLY metadata error — the manifest fails
        # closed as unknown-count-key (never silently accepted, never merely
        # mismatched against the fixture policies).
        legacy = yaml.safe_load(manifest.read_text(encoding="utf-8"))
        assert structural_manifest_metadata_errors(legacy) == [
            "unknown 'counts' key(s) ['ownership_entries']",
        ]


def test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict(
    tmp_path: Path,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    _write(root, "config/guards/db_structural_exceptions_expected_methods.yml", """expected: []
fixtures: []
counts:
  ownership_entries: 99
  structural_entries: 62
""")
    report = root / "fixture-mismatch.json"

    result = _run(root, report, sync_manifest=False)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })

    # Explicit GR-04 decoupling assertion: this manifest fails closed because
    # its legacy ownership count key is unknown-count-key metadata — not
    # merely because 99/62 disagrees with the fixture policies.  If ownership
    # cardinality is ever re-coupled into the manifest contract, this
    # assertion fails and forces the decoupling to be re-decided.
    stale_manifest = yaml.safe_load(
        (root / "config/guards/db_structural_exceptions_expected_methods.yml")
        .read_text(encoding="utf-8")
    )
    assert structural_manifest_metadata_errors(stale_manifest) == [
        "unknown 'counts' key(s) ['ownership_entries']",
    ]

    # No fixture override is supplied here: production defaults retain the
    # canonical manifest contract instead of inheriting temporary policy paths.
    canonical_report = tmp_path / "canonical-defaults.json"
    canonical = subprocess.run(
        [sys.executable, str(SCRIPT), "--findings-output", str(canonical_report)],
        cwd=str(Path(__file__).parents[1]), text=True, capture_output=True,
        check=False,
        env={key: value for key, value in os.environ.items()
             if key != "COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA"},
    )
    assert canonical.returncode == 2
    _assert_cli_streams(canonical, Path(__file__).parents[1], diagnostic=True)
    _report(canonical_report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_report_does_not_depend_on_stdout_summary_parsing(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _policy(root)
    _structural_policy(root)
    report = root / "no-stdout-parser.json"

    result = _run(root, report)

    assert result.returncode == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_argv_and_canonical_identity_are_platform_neutral(
    tmp_path: Path, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path)
    _write(root, "config/guards/ownership.yml", "entries: []\n")
    _structural_policy(root)
    report = root / "platform.json"

    # Passing an argv list (rather than shell text) exercises spaces and the
    # native Windows path spelling while report identity stays canonical POSIX.
    result = _run(root, report, "--fail-on-violation")

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "save",
                    "receiver": None,
                    # Resolved canonical spelling (package-qualified FQCN).
                    "parameters": ["example.Item"],
                    "kind": "function",
                },
                "identity": {
                    "dao": "example.ExpenseDao",
                    "accessor": "expenseDao",
                    "operation": "insert",
                    "mutation_kind": "ROOM_INSERT",
                    "call_form": "receiver",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            }
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


ACCESSOR_SOURCE = SOURCE.replace(
    "class Repository(private val expenseDao: ExpenseDao) {\n    fun save(item: Item) {\n        expenseDao.insert(item)\n    }\n}",
    """class Repository(private val expenseDao: ExpenseDao) {
    var cached: Item = Item(0)
        get() { val item: Item = Item(0); expenseDao.insert(item); return field }
        set(value: Item) { expenseDao.insert(value); field = value }
}""",
)


def _accessor_policy(root: Path, kind: str, parameters: str = "[]") -> Path:
    return _write(root, "config/guards/ownership.yml", f"""entries:
  - path: {CANONICAL}
    class: Repository
    method: cached
    daos: [expenseDao]
    operation: insert
    signature:
      receiver: null
      kind: {kind}
      parameters: {parameters}
    barrier_required: false
    reason: fixture
    owner: '@d4c'
    linked_issue: D4C-001
""")


@pytest.mark.parametrize(("kind", "parameters"), [
    ("property_getter", "[]"),
    ("property_setter", "[Item]"),
])
def test_accessor_policy_uses_exact_structured_callable_identity(
    tmp_path: Path, kind: str, parameters: str, _bypass_evidence,
) -> None:
    source = ACCESSOR_SOURCE
    if kind == "property_getter":
        source = source.replace("\n        set(value: Item) { expenseDao.insert(value); field = value }", "")
    else:
        source = source.replace(
            "\n        get() { val item: Item = Item(0); expenseDao.insert(item); return field }",
            "",
        )
    root = _fixture(tmp_path, source=source)
    _accessor_policy(root, kind, parameters)
    _structural_policy(root)
    report = root / f"{kind}.json"

    result = _run(root, report)

    assert result.returncode == 0
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            # A property and its accessors are ONE declaration range
            # (declaration_scanner._property_bounds consumes header,
            # initializer, and every accessor as a single declaration), so
            # the scan sees Item + Repository + cached = 3 ranges.
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


@pytest.mark.parametrize(("kind", "parameters"), [
    ("property_setter", "[]"),
    ("property_getter", "[Item]"),
])
def test_accessor_policy_rejects_wrong_kind_or_parameter_signature(
    tmp_path: Path, kind: str, parameters: str, _bypass_evidence,
) -> None:
    root = _fixture(tmp_path, source=ACCESSOR_SOURCE)
    _accessor_policy(root, kind, parameters)
    _structural_policy(root)
    report = root / "wrong-accessor-signature.json"

    result = _run(root, report)

    assert result.returncode == 1
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 13, "end_line": 13},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "cached",
                    "receiver": None,
                    "parameters": [],
                    "kind": "property_getter",
                },
                "identity": {
                    "accessor": "expenseDao",
                    "call_form": "receiver",
                    "dao": "example.ExpenseDao",
                    "mutation_kind": "ROOM_INSERT",
                    "operation": "insert",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            },
            {
                "rule": "DB_UNAUTHORIZED_MUTATION",
                "severity": "error",
                "path": "app/src/main/java/example/Fixture.kt",
                "location": {"line": 14, "end_line": 14},
                "symbol": {
                    "owner": "example.Repository",
                    "name": "cached",
                    "receiver": None,
                    "parameters": ["Item"],
                    "kind": "property_setter",
                },
                "identity": {
                    "accessor": "expenseDao",
                    "call_form": "receiver",
                    "dao": "example.ExpenseDao",
                    "mutation_kind": "ROOM_INSERT",
                    "operation": "insert",
                },
                "message": "Database mutation is not owned by an exact policy entry",
            },
        ],
        "diagnostics": [],
        "statistics": {
            "files_scanned": 1,
            # A property and its accessors are ONE declaration range
            # (declaration_scanner._property_bounds consumes header,
            # initializer, and every accessor as a single declaration), so
            # the scan sees Item + Repository + cached = 3 ranges.
            "declarations_scanned": 3,
            "inventory_daos": 1,
            "inventory_mutators": 1,
            "trusted": True,
        },
    })


def test_accessor_unknown_argument_type_is_not_authorized(
    tmp_path: Path, _bypass_evidence,
) -> None:
    source = ACCESSOR_SOURCE.replace("expenseDao.insert(item); return field",
                                    "expenseDao.insert(field); return field")
    root = _fixture(tmp_path, source=source)
    _accessor_policy(root, "property_getter")
    _structural_policy(root)
    report = root / "unknown-accessor-argument.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_SIGNATURE_UNRESOLVED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            },
        ],
        "statistics": {"trusted": False},
    })


def test_accessor_unknown_constructor_expression_is_not_authorized(
    tmp_path: Path, _bypass_evidence,
) -> None:
    source = ACCESSOR_SOURCE.replace(
        "val item: Item = Item(0); expenseDao.insert(item)",
        "expenseDao.insert(UnknownItem())",
    )
    root = _fixture(tmp_path, source=source)
    _accessor_policy(root, "property_getter")
    _structural_policy(root)
    report = root / "unknown-constructor-accessor.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_SIGNATURE_UNRESOLVED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            },
        ],
        "statistics": {"trusted": False},
    })


@pytest.mark.parametrize("bad_entry", [
    "    parameters: Item",
    "    # signature omitted",
    "      kind: invalid_kind",
    "    daos: []",
    "    operation: ''",
])
def test_malformed_ownership_policy_is_source_evidence_diagnostic(
    tmp_path: Path, bad_entry: str,
) -> None:
    """Malformed ownership entries fail closed with the controlled umbrella.

    The CLI deliberately collapses every per-entry evidence failure — the
    loader/metadata validator's ENTRY_INVALID reasons and the missing-signature
    pre-gate alike — into the single context-free
    DB_POLICY_SOURCE_EVIDENCE_INVALID diagnostic (exit 2, findings withheld).
    These parametrizations pin that actual controlled code; internal stage
    codes (e.g. SIGNATURE_MISSING) are intentionally not part of the CLI
    contract.  The base policy is written by ``_policy`` so the test owns its
    complete input instead of reading a shared fixture default.
    """
    root = _fixture(tmp_path)
    policy_path = _policy(root)
    policy = policy_path.read_text(encoding="utf-8")
    if bad_entry.startswith("    #"):
        policy = policy.replace(
            "    signature:\n      receiver: null\n      kind: function\n"
            "      parameters: [example.Item]\n",
            bad_entry.strip() + "\n",
        )
    elif bad_entry.startswith("      kind:"):
        policy = policy.replace("      kind: function", bad_entry.strip())
    elif "parameters" in bad_entry:
        policy = policy.replace("      parameters: [example.Item]", bad_entry.strip())
    elif "daos" in bad_entry:
        policy = policy.replace("    daos: [expenseDao]", bad_entry.strip())
    elif "operation" in bad_entry:
        policy = policy.replace("    operation: insert", bad_entry.strip())
    _write(root, "config/guards/ownership.yml", policy)
    _structural_policy(root)
    report = root / "malformed-policy.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_stale_ownership_policy_is_source_evidence_diagnostic(tmp_path: Path) -> None:
    root = _fixture(tmp_path)
    _policy(root, method="removedWriter")
    _structural_policy(root)
    report = root / "stale-policy.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_evidence_gate_runs_before_scanner_matching(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str],
) -> None:
    """Pin the GR-01 pipeline ORDER: source evidence gates scanner matching.

    The policy names a method the source never declared (stale evidence)
    while the scanned tree still contains a real unauthorized mutation.  The
    evidence stage must run FIRST and win: the report carries ONLY the
    context-free umbrella diagnostic with zero findings — scanner output for
    the very same tree would have been DB_UNAUTHORIZED_MUTATION.  A
    pass-through spy proves the CLI consulted the evidence stage with the
    loaded policy entries before any matching happened.
    """
    root = _fixture(tmp_path)
    _policy(root, method="removedWriter")
    _structural_policy(root)
    report = root / "ordering.json"

    real_evidence_check = _verifier_module.verify_ownership_policy_source_evidence
    evidence_calls = []

    def _evidence_spy(entries, source_root):
        evidence_calls.append(len(entries))
        return real_evidence_check(entries, source_root)

    monkeypatch.setattr(
        _verifier_module, "verify_ownership_policy_source_evidence", _evidence_spy,
    )

    assert verify_main([
        "--root", str(root),
        "--ownership-policy", str(root / "config/guards/ownership.yml"),
        "--structural-exceptions", str(root / "config/guards/structural.yml"),
        "--structural-manifest", str(root / "config/guards/db_structural_exceptions_expected_methods.yml"),
        "--raw-query-policy", str(root / "config/guards/raw.yml"),
        "--findings-output", str(report),
    ]) == 2

    # The pre-gate really ran (with the loaded entries) and blocked the
    # pipeline before scanner matching could produce anything.
    assert evidence_calls == [1]
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == "ERROR: DB access discovery infrastructure diagnostics present\n"
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_POLICY_SOURCE_EVIDENCE_INVALID",
                "path": None,
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


@pytest.mark.parametrize("accessors", [
    "get() { expenseDao.insert(Item(1)); return field }\n        get() { return field }",
    "set(value: Item) { expenseDao.insert(value); field = value }\n        set(value: Item) { field = value }",
])
def test_duplicate_property_accessor_identity_is_unresolved(
    tmp_path: Path, accessors: str, _bypass_evidence,
) -> None:
    source = SOURCE.replace(
        "class Repository(private val expenseDao: ExpenseDao) {\n    fun save(item: Item) {\n        expenseDao.insert(item)\n    }\n}",
        "class Repository(private val expenseDao: ExpenseDao) {\n"
        "    var cached: Item = Item(0)\n        " + accessors + "\n}",
    )
    root = _fixture(tmp_path, source=source)
    _accessor_policy(root, "property_getter")
    _structural_policy(root)
    report = root / "duplicate-accessor.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_SIGNATURE_UNRESOLVED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_receiver_scope_does_not_cross_sibling_methods(
    tmp_path: Path, _bypass_evidence,
) -> None:
    source = SOURCE.replace(
        "class Repository(private val expenseDao: ExpenseDao) {\n    fun save(item: Item) {\n        expenseDao.insert(item)\n    }\n}",
        """class Repository {
    fun first(item: Item) {
        val expenseDao: UnsafeDao = UnsafeDao()
        expenseDao.insert(item)
    }
    fun save(item: Item) {
        expenseDao.insert(item)
    }
}
class Other(private val expenseDao: ExpenseDao) {
    fun save(item: Item) { expenseDao.insert(item) }
}
class UnsafeDao
""",
    )
    root = _fixture(tmp_path, source=source)
    _policy(root, class_name="Repository")
    _structural_policy(root)
    report = root / "sibling-scope.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_DAO_SCOPE_UNRESOLVED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_receiver_scope_prefers_local_shadow_and_rejects_ambiguous_shadow(
    tmp_path: Path, _bypass_evidence,
) -> None:
    source = SOURCE.replace(
        "class Repository(private val expenseDao: ExpenseDao) {\n    fun save(item: Item) {\n        expenseDao.insert(item)\n    }\n}",
        """class Repository(private val expenseDao: ExpenseDao) {
    fun save(item: Item) {
        val expenseDao: ExpenseDao = expenseDao
        expenseDao.insert(item)
    }
    fun ambiguous(item: Item) {
        val expenseDao: ExpenseDao = expenseDao
        val expenseDao: UnsafeDao = expenseDao
        expenseDao.insert(item)
    }
}
class UnsafeDao
""",
    )
    root = _fixture(tmp_path, source=source)
    _policy(root, method="save")
    _structural_policy(root)
    report = root / "local-shadow.json"

    result = _run(root, report)

    assert result.returncode == 2
    _report(report, {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [
            {
                "code": "DB_DAO_SCOPE_UNRESOLVED",
                "path": "app/src/main/java/example/Fixture.kt",
                "symbol": None,
                "controlled_context": {},
            }
        ],
        "statistics": {"trusted": False},
    })


def test_inventory_resolves_dao_after_bodyless_sibling_without_inheritance_diagnostic(
    tmp_path: Path,
) -> None:
    """Regression: a bodyless sibling immediately before a ``@Dao`` interface
    must not hide the DAO from the inventory.

    ``data class Item(val id: Int)`` has no body of its own.  An untempered
    declaration-header pattern swallowed the following ``interface
    ExpenseDao {`` opener as the sibling's body, so ExpenseDao vanished from
    the lexical declaration index and every scan of this exact file shape —
    the canonical fixture shape of this suite — failed closed with a
    spurious ``DB_DAO_INHERITANCE_UNRESOLVED`` for a DAO that declares no
    inheritance at all.  The inventory must resolve the DAO with its
    canonical ``ROOM_INSERT`` mutator kind (the same value the scanner
    reports verbatim in ``identity.mutation_kind``) and zero diagnostics."""
    _write(tmp_path, CANONICAL, SOURCE)
    java_root = tmp_path / "app" / "src" / "main" / "java"
    inventory = room_inventory.build_room_inventory(
        java_root, {"version": 1, "methods": []}
    )
    assert "example.ExpenseDao" in {dao.fqcn for dao in inventory.daos}
    assert [mutator.mutation_kind for mutator in inventory.mutators] == [
        "ROOM_INSERT"
    ]
    assert not any(
        diagnostic.split(":", 1)[0] == "DB_DAO_INHERITANCE_UNRESOLVED"
        for diagnostic in inventory.diagnostics
    ), inventory.diagnostics
    assert inventory.diagnostics == ()
