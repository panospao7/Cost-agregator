"""Contract tests for the non-exiting legacy policy loaders in
``scripts/db_guard/policy_legacy.py`` (``legacy_yaml_safe_load`` /
``legacy_load_ownership_policy`` / ``legacy_load_structural_exceptions``).

Pins parity with the exiting v1 loaders in
``scripts/verify_db_access_boundaries.py``: identical checks, identical
order, identical fail-closed semantics — with every ``sys.exit(2)`` replaced
by a controlled ``PolicyError`` from the closed code set and strictly
bounded context (labels, indices, counts, type names — never paths, never
raw exception text, never entry payloads).
"""

from __future__ import annotations

import pytest

from scripts.db_guard import policy_legacy
from scripts.db_guard.policy_errors import (
    KNOWN_POLICY_ERROR_CODES,
    POLICY_ERROR_ENTRY_NOT_MAPPING,
    POLICY_ERROR_INVALID_TYPE,
    POLICY_ERROR_POLICY_EMPTY,
    POLICY_ERROR_POLICY_FILE_NOT_FOUND,
    POLICY_ERROR_YAML_MALFORMED,
    POLICY_ERROR_YAML_MODULE_UNAVAILABLE,
)
from scripts.db_guard.policy_legacy import (
    legacy_load_ownership_policy,
    legacy_load_structural_exceptions,
    legacy_ownership_entry_metadata_errors,
    legacy_structural_entry_metadata_errors,
    legacy_yaml_safe_load,
)

CANONICAL_PATH = (
    "app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt"
)

VALID_OWNERSHIP_YAML = """\
entries:
- path: app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
  class: AppDatabase
  method: onCreate
  operation: insert
  reason: test fixture
  owner: test
  linked_issue: GR-00
  daos:
  - ExpenseGroupDao
  barrier_required: true
"""

VALID_STRUCTURAL_YAML = """\
entries:
- path: app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
  class: AppDatabase
  method_pattern: onCreate
  operation: execSQL
  reason: test fixture
  owner: test
  linked_issue: GR-00
"""

# Every context key the non-exiting loaders may ever emit.  Anything else is a
# bounded-context contract violation.
ALLOWED_CONTEXT_KEYS = {"label", "expected", "got", "index", "error_count"}


def _write(tmp_path, name, text):
    target = tmp_path / name
    target.write_text(text, encoding="utf-8")
    return str(target)


# ── legacy_yaml_safe_load ──────────────────────────────────────────────────────

def test_yaml_safe_load_returns_data_and_none_on_success(tmp_path):
    path = _write(tmp_path, "ok.yml", "a: 1\nb:\n- 2\n")
    data, error = legacy_yaml_safe_load(path)
    assert data == {"a": 1, "b": [2]}
    assert error is None


def test_yaml_safe_load_reports_missing_pyyaml_without_exiting(monkeypatch):
    monkeypatch.setattr(policy_legacy, "_HAS_YAML", False)
    data, error = legacy_yaml_safe_load("unused.yml")
    assert data is None
    assert error.code == POLICY_ERROR_YAML_MODULE_UNAVAILABLE
    assert error.context == {"label": "policy document"}


def test_yaml_safe_load_missing_file_fails_closed_with_controlled_code(tmp_path):
    missing = str(tmp_path / "absent.yml")
    data, error = legacy_yaml_safe_load(missing)
    assert data is None
    assert error.code == POLICY_ERROR_POLICY_FILE_NOT_FOUND
    # Bounded context: never the caller-supplied path.
    assert error.context == {"label": "policy document"}


def test_yaml_safe_load_malformed_yaml_is_controlled(tmp_path):
    path = _write(tmp_path, "broken.yml", "key: [1, 2\n")
    data, error = legacy_yaml_safe_load(path)
    assert data is None
    assert error.code == POLICY_ERROR_YAML_MALFORMED
    # Bounded context: never raw exception text.
    assert error.context == {"label": "policy document"}


def test_yaml_safe_load_empty_document_is_policy_empty(tmp_path):
    path = _write(tmp_path, "empty.yml", "")
    data, error = legacy_yaml_safe_load(path)
    assert data is None
    assert error.code == POLICY_ERROR_POLICY_EMPTY


def test_yaml_safe_load_custom_label_flows_into_context(tmp_path):
    missing = str(tmp_path / "absent.yml")
    _, error = legacy_yaml_safe_load(missing, "DB ownership policy")
    assert error.context == {"label": "DB ownership policy"}


# ── legacy_load_ownership_policy ───────────────────────────────────────────────

def test_ownership_loader_success_returns_entries_without_errors(tmp_path):
    path = _write(tmp_path, "ownership.yml", VALID_OWNERSHIP_YAML)
    entries, errors = legacy_load_ownership_policy(path)
    assert errors == []
    assert isinstance(entries, list) and len(entries) == 1
    assert entries[0]["method"] == "onCreate"


def test_ownership_loader_accepts_top_level_list_document(tmp_path):
    path = _write(
        tmp_path, "ownership.yml", VALID_OWNERSHIP_YAML.replace("entries:\n", "")
    )
    entries, errors = legacy_load_ownership_policy(path)
    assert errors == []
    assert len(entries) == 1


def test_ownership_loader_propagates_yaml_module_unavailable(monkeypatch):
    monkeypatch.setattr(policy_legacy, "_HAS_YAML", False)
    entries, errors = legacy_load_ownership_policy("unused.yml")
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_YAML_MODULE_UNAVAILABLE]


def test_ownership_loader_non_list_entries_is_invalid_type(tmp_path):
    path = _write(tmp_path, "ownership.yml", "entries: 5\n")
    entries, errors = legacy_load_ownership_policy(path)
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_INVALID_TYPE]
    assert errors[0].context == {
        "label": "DB ownership policy",
        "expected": "list",
        "got": "int",
    }


def test_ownership_loader_non_mapping_entry_fails_closed(tmp_path):
    path = _write(tmp_path, "ownership.yml", "entries:\n- just a string\n")
    entries, errors = legacy_load_ownership_policy(path)
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_ENTRY_NOT_MAPPING]
    assert errors[0].context == {
        "label": "ownership policy entry",
        "index": 0,
    }


def test_ownership_loader_invalid_mapping_entry_maps_to_invalid_type(tmp_path):
    path = _write(
        tmp_path,
        "ownership.yml",
        VALID_OWNERSHIP_YAML.replace("operation: insert", "operation: write"),
    )
    entries, errors = legacy_load_ownership_policy(path)
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_INVALID_TYPE]
    assert errors[0].context["index"] == 0
    assert errors[0].context["error_count"] >= 1
    # Parity: the dropped human-readable reasons are exactly the pure
    # validator's output for the same entry.
    bad_entry = {
        "path": CANONICAL_PATH,
        "class": "AppDatabase",
        "method": "onCreate",
        "operation": "write",
        "reason": "test fixture",
        "owner": "test",
        "linked_issue": "GR-00",
        "daos": ["ExpenseGroupDao"],
        "barrier_required": True,
    }
    assert legacy_ownership_entry_metadata_errors(bad_entry)


def test_ownership_loader_stops_at_first_invalid_entry(tmp_path):
    valid = (
        "- path: " + CANONICAL_PATH + "\n"
        "  class: AppDatabase\n"
        "  method: onCreate\n"
        "  operation: insert\n"
        "  reason: ok\n"
        "  owner: t\n"
        "  linked_issue: GR-00\n"
        "  daos:\n"
        "  - ExpenseGroupDao\n"
        "  barrier_required: true\n"
    )
    invalid = valid.replace("operation: insert", "operation: write")
    path = _write(
        tmp_path,
        "ownership.yml",
        "entries:\n" + valid + invalid + "- just a string\n",
    )
    entries, errors = legacy_load_ownership_policy(path)
    # Fail fast on entry #2 (0-based index 1); the later non-mapping entry
    # must NOT produce a second error — same as the exiting loader's exit 2.
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_INVALID_TYPE]
    assert errors[0].context["index"] == 1


# ── legacy_load_structural_exceptions ──────────────────────────────────────────

def test_structural_loader_success_returns_entries_without_errors(tmp_path):
    path = _write(tmp_path, "structural.yml", VALID_STRUCTURAL_YAML)
    entries, errors = legacy_load_structural_exceptions(path)
    assert errors == []
    assert isinstance(entries, list) and len(entries) == 1
    assert entries[0]["method_pattern"] == "onCreate"


def test_structural_loader_unbounded_method_pattern_maps_to_invalid_type(tmp_path):
    path = _write(
        tmp_path,
        "structural.yml",
        VALID_STRUCTURAL_YAML.replace('method_pattern: onCreate', 'method_pattern: ".*"'),
    )
    entries, errors = legacy_load_structural_exceptions(path)
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_INVALID_TYPE]
    assert errors[0].context["index"] == 0
    assert errors[0].context["error_count"] >= 1
    bad_entry = {
        "path": CANONICAL_PATH,
        "class": "AppDatabase",
        "method_pattern": ".*",
        "operation": "execSQL",
        "reason": "test fixture",
        "owner": "test",
        "linked_issue": "GR-00",
    }
    assert legacy_structural_entry_metadata_errors(bad_entry)


def test_structural_loader_non_list_entries_is_invalid_type(tmp_path):
    path = _write(tmp_path, "structural.yml", "entries: {}\n")
    entries, errors = legacy_load_structural_exceptions(path)
    assert entries == []
    assert [e.code for e in errors] == [POLICY_ERROR_INVALID_TYPE]
    assert errors[0].context == {
        "label": "DB structural exceptions",
        "expected": "list",
        "got": "dict",
    }


# ── Cross-cutting contracts ────────────────────────────────────────────────────

@pytest.mark.parametrize("loader,text", [
    (legacy_load_ownership_policy, "entries: 5\n"),
    (legacy_load_ownership_policy, "entries:\n- just a string\n"),
    (legacy_load_ownership_policy, VALID_OWNERSHIP_YAML.replace("operation: insert", "operation: write")),
    (legacy_load_structural_exceptions, "entries: {}\n"),
    (legacy_load_structural_exceptions, VALID_STRUCTURAL_YAML.replace("method_pattern: onCreate", 'method_pattern: ".*"')),
])
def test_all_loader_errors_use_closed_codes_and_bounded_context(
    loader, text, tmp_path
):
    path = _write(tmp_path, "policy.yml", text)
    _, errors = loader(path)
    assert errors
    for err in errors:
        assert err.code in KNOWN_POLICY_ERROR_CODES
        assert set(err.context) <= ALLOWED_CONTEXT_KEYS
        assert all(isinstance(v, (str, int)) for v in err.context.values())
        # Never leak the caller-supplied path through context.
        assert not any(
            isinstance(v, str) and str(tmp_path) in v for v in err.context.values()
        )


def test_loaders_never_raise_systemexit(tmp_path):
    broken = _write(tmp_path, "broken.yml", "key: [1, 2\n")
    for loader in (legacy_load_ownership_policy, legacy_load_structural_exceptions):
        try:
            loader(broken)
        except SystemExit as exc:  # pragma: no cover - regression guard
            pytest.fail(f"{loader.__name__} exited with {exc.code!r}")
