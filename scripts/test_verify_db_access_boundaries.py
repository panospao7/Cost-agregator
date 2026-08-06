"""
test_verify_db_access_boundaries.py
Acceptance tests for the exact DB access boundary scanner contract.

Contract under test (scripts/verify_db_access_boundaries.py):

* Canonical policy paths are repository-relative POSIX paths under
  ``app/src/main/java`` (``canonical_policy_path`` / ``canonical_policy_path_error``).
  Bare basenames, backslashes, absolute paths, ``..`` / ``.`` segments, non-``.kt``
  paths, and paths outside the approved source root are rejected (fail closed).
* Ownership policy entries must name the EXACT DAO method they authorize via
  ``operation`` - the universal ``operation: write`` is invalid policy metadata and
  is rejected by the loader AND never authorizes at scan time.
* Ownership entries require canonical paths, exact (non-wildcard) methods, non-empty
  daos, a REAL boolean ``barrier_required``, and no unknown fields.
* Structural exception entries require canonical paths and bounded method_patterns
  (an exact Kotlin identifier or the single ``MIGRATION_\\d+_\\d+`` form).
* Class/method/body resolution is exact: names come from the ACTUAL Kotlin
  declarations, bodies are balanced-brace scoped, and there is no file-wide or
  basename fallback.
* DAO identities resolve through class/constructor property types
  (``private val groupDao: ExpenseGroupDao`` -> ``expenseGroupDao``) and method-local
  assignments (``val dao = appDatabase.privacyAuditDao()`` -> ``privacyAuditDao``).
* Authorization is all-or-nothing per mutation: every extracted
  ``(dao_identity, operation)`` pair must be covered by an exact policy entry;
  a single uncovered pair fails.
* Structural matching uses ``re.fullmatch`` on the ACTUAL enclosing declaration
  names - ``verify`` never matches ``verifyInternal``.
* File operations are detected from the COMPLETE statefully masked text with
  EXACT call/token evidence - calls may span line breaks
  (``db.execSQL\\n("...")``, ``SQLiteDatabase.openDatabase\\n(...)``) and keep
  the call-start line; ``raw_`` categories authorize only provably-exact
  operations (never prefix-like text such as ``getDatabasePathway`` /
  ``openDatabaseHelper`` / ``mywritableDatabase``); a supported operation
  token that cannot be parsed as an exact call fails closed with
  ``UNSUPPORTED_STRUCTURAL_OP`` instead of being silently skipped.
* Missing / unreadable / empty source behavior is fail-closed.

Scan fixtures are written to a temporary tree whose PROJECT_ROOT is pinned to the
fixture root, so scanned files get canonical ``app/src/main/java/...`` rel paths
that canonical policy paths can match exactly.  The real policy YAML files are
NOT required by these tests (they are migrated to the new contract separately).

Run with: python -m pytest scripts/test_verify_db_access_boundaries.py -v
"""
import os
import sys
import builtins

import pytest

# Import the module under test directly (its CLI only runs under __main__).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_db_access_boundaries as _mod  # noqa: E402

scan = _mod.scan
canonical_policy_path = _mod.canonical_policy_path
canonical_policy_path_error = _mod.canonical_policy_path_error
ownership_entry_metadata_errors = _mod.ownership_entry_metadata_errors
structural_entry_metadata_errors = _mod.structural_entry_metadata_errors
parse_type_declarations = _mod.parse_type_declarations
parse_function_declarations = _mod.parse_function_declarations
extract_method_body = _mod.extract_method_body
build_dao_var_map = _mod.build_dao_var_map
extract_mutation_pairs = _mod.extract_mutation_pairs
matches_policy_pair = _mod.matches_policy_pair
load_db_ownership_policy = _mod.load_db_ownership_policy
load_db_structural_exceptions = _mod.load_db_structural_exceptions
normalize_policy_for_scan = _mod._normalize_policy_for_scan

CANONICAL_ROOT = "app/src/main/java"


def _canonical(rel_under_root):
    """Build a canonical policy path under app/src/main/java."""
    return f"{CANONICAL_ROOT}/{rel_under_root}"


def _write_kt(base_dir, rel_path, content):
    path = base_dir / rel_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def _fixture_source(tmp_path, monkeypatch):
    """Pin PROJECT_ROOT to the fixture root so scanned files get canonical paths.

    Returns the app/src/main/java directory to write fixtures into.  Scanned
    files there relativize to ``app/src/main/java/...`` which canonical policy
    paths can match exactly.
    """
    monkeypatch.setattr(_mod, "PROJECT_ROOT", str(tmp_path))
    src = tmp_path / "app" / "src" / "main" / "java"
    src.mkdir(parents=True, exist_ok=True)
    return src


def _entry(path, class_name, method, daos, operation,
           barrier_required=False, barrier_via=None):
    entry = {
        "path": path,
        "class": class_name,
        "method": method,
        "daos": list(daos),
        "operation": operation,
        "barrier_required": barrier_required,
        "reason": "test",
        "owner": "@test",
        "linked_issue": "TEST-001",
    }
    if barrier_via is not None:
        entry["barrier_via"] = barrier_via
    return entry


def _sexc(path, class_name, method_pattern, operation):
    return {
        "path": path,
        "class": class_name,
        "method_pattern": method_pattern,
        "operation": operation,
        "reason": "test",
        "owner": "@test",
        "linked_issue": "TEST-001",
    }


def _normalized(entries):
    return normalize_policy_for_scan(entries, "path")


def _write_policy_yaml(tmp_path, entries_body):
    policy = tmp_path / "policy.yml"
    policy.write_text("entries:\n" + entries_body, encoding="utf-8")
    return str(policy)


def _write_exceptions_yaml(tmp_path, entries_body):
    exceptions = tmp_path / "exceptions.yml"
    exceptions.write_text("entries:\n" + entries_body, encoding="utf-8")
    return str(exceptions)


# ── 1. Canonical policy paths ─────────────────────────────────────────────────

def test_canonical_policy_path_accepts_app_main_java():
    path = _canonical("com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt")
    assert canonical_policy_path_error(path) is None
    assert canonical_policy_path(path) == path


def test_canonical_policy_path_rejects_bare_basename():
    path = "GroupTransactionCoordinator.kt"
    assert canonical_policy_path(path) is None
    assert "bare basename" in canonical_policy_path_error(path)


def test_canonical_policy_path_rejects_backslash():
    path = _canonical("com/example/Foo.kt").replace("/", "\\")
    assert canonical_policy_path(path) is None
    assert "backslash" in canonical_policy_path_error(path)


def test_canonical_policy_path_rejects_absolute():
    path = "/app/src/main/java/com/example/Foo.kt"
    assert canonical_policy_path(path) is None
    assert "repository-relative" in canonical_policy_path_error(path)


def test_canonical_policy_path_rejects_drive_letter_absolute():
    path = "C:\\proj\\app\\src\\main\\java\\com\\example\\Foo.kt"
    assert canonical_policy_path(path) is None
    assert canonical_policy_path_error(path) is not None


def test_canonical_policy_path_rejects_dotdot_and_dot_segments():
    dotdot = _canonical("com/example/../Foo.kt")
    assert canonical_policy_path(dotdot) is None
    assert "'..'" in canonical_policy_path_error(dotdot)

    dot = "./" + _canonical("com/example/Foo.kt")
    assert canonical_policy_path(dot) is None
    assert canonical_policy_path_error(dot) is not None


def test_canonical_policy_path_rejects_wrong_source_root():
    for path in (
        "app/src/main/kotlin/com/example/Foo.kt",
        "src/main/java/com/example/Foo.kt",
        "app/src/test/java/com/example/Foo.kt",
        "app/src/main/java_test/com/example/Foo.kt",
    ):
        assert canonical_policy_path(path) is None, path
        assert "approved production source root" in canonical_policy_path_error(path), path


def test_canonical_policy_path_rejects_non_kt_and_empty():
    java = _canonical("com/example/Foo.java")
    assert canonical_policy_path(java) is None
    assert ".kt" in canonical_policy_path_error(java)

    assert canonical_policy_path("") is None
    assert canonical_policy_path("   ") is None
    assert "non-empty" in canonical_policy_path_error("  ")


def test_canonical_policy_path_rejects_non_string():
    assert canonical_policy_path(None) is None
    assert canonical_policy_path(123) is None
    assert canonical_policy_path_error(None) is not None


# ── 2. Ownership policy loader (exact contract) ───────────────────────────────

def test_ownership_loader_rejects_operation_write(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: write
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_wildcard_method(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "*"
    daos: [expenseDao]
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_anchored_method(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "^doWork"
    daos: [expenseDao]
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_missing_method(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    daos: [expenseDao]
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_noncanonical_path_bare_basename(tmp_path, capsys):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "not canonical" in err
    assert "SomeRepo.kt" in err


def test_ownership_loader_rejects_noncanonical_path_backslash(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: 'app/src/main/java\\com\\example\\SomeRepo.kt'
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_missing_daos(tmp_path, capsys):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "daos" in err
    assert "SomeRepo.kt" in err


def test_ownership_loader_rejects_empty_daos(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: []
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_missing_barrier_required(tmp_path, capsys):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: insert
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "barrier_required" in err
    assert "SomeRepo.kt" in err


def test_ownership_loader_rejects_string_barrier_required(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: insert
    barrier_required: "yes"
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_integer_barrier_required(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: insert
    barrier_required: 1
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2


def test_ownership_loader_rejects_unknown_field(tmp_path, capsys):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daoz: [expenseDao]
    operation: insert
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_ownership_policy(policy_path)
    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "unknown key" in err
    assert "SomeRepo.kt" in err


def test_ownership_loader_accepts_exact_entry(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeRepo.kt
    class: SomeRepo
    method: "doWork"
    daos: [expenseDao]
    operation: insertOrIgnore
    barrier_required: false
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    entries = load_db_ownership_policy(policy_path)
    assert len(entries) == 1
    assert entries[0]["method"] == "doWork"
    assert entries[0]["operation"] == "insertOrIgnore"


def test_ownership_loader_accepts_worker_barrier_via_metadata(tmp_path):
    policy_path = _write_policy_yaml(tmp_path, """
  - path: app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt
    class: DataRetentionWorker
    method: "doWork"
    daos: [privacyAuditDao]
    operation: insert
    barrier_required: false
    barrier_via: WorkerExecutionGuard
    reason: WorkerExecutionGuard-mediated write protection
    owner: "@test"
    linked_issue: "TEST-001"
""")
    entries = load_db_ownership_policy(policy_path)
    assert len(entries) == 1
    assert entries[0]["barrier_required"] is False
    assert entries[0]["barrier_via"] == "WorkerExecutionGuard"


# ── 3. Structural exception loader (bounded contract) ─────────────────────────

def test_structural_loader_rejects_noncanonical_path(tmp_path, capsys):
    exceptions_path = _write_exceptions_yaml(tmp_path, """
  - path: DatabaseMigrations.kt
    class: DatabaseMigrations
    method_pattern: 'MIGRATION_\\d+_\\d+'
    operation: execSQL
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_structural_exceptions(exceptions_path)
    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "not canonical" in err
    assert "DatabaseMigrations.kt" in err


def test_structural_loader_rejects_broad_method_patterns(tmp_path):
    bad_patterns = [
        r".*",
        r".+",
        r"\w+",
        r"[A-Z]+",
        r"foo.*?",
        r"runRescueIfNeeded|onCreate",
        r"^foo",
        r"foo$",
        r"foo.bar",
    ]
    for i, pattern in enumerate(bad_patterns):
        exceptions_path = _write_exceptions_yaml(tmp_path, f"""
  - path: app/src/main/java/com/example/SomeClass.kt
    class: SomeClass
    method_pattern: '{pattern}'
    operation: raw_sqlite
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
        with pytest.raises(SystemExit) as exc_info:
            load_db_structural_exceptions(exceptions_path)
        assert exc_info.value.code == 2, f"Pattern {pattern!r} must be rejected"


def test_structural_loader_accepts_exact_method_names(tmp_path):
    for pattern in ("verify", "verifyQuick", "onCreate", "FRESH_INSTALL_CALLBACK"):
        exceptions_path = _write_exceptions_yaml(tmp_path, f"""
  - path: app/src/main/java/com/example/SomeClass.kt
    class: SomeClass
    method_pattern: '{pattern}'
    operation: raw_sqlite
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
        entries = load_db_structural_exceptions(exceptions_path)
        assert len(entries) == 1
        assert entries[0]["method_pattern"] == pattern


def test_structural_loader_accepts_bounded_migration_form(tmp_path):
    exceptions_path = _write_exceptions_yaml(tmp_path, """
  - path: app/src/main/java/com/example/DatabaseMigrations.kt
    class: DatabaseMigrations
    method_pattern: 'MIGRATION_\\d+_\\d+'
    operation: execSQL
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    entries = load_db_structural_exceptions(exceptions_path)
    assert len(entries) == 1
    assert entries[0]["method_pattern"] == r"MIGRATION_\d+_\d+"


def test_structural_loader_rejects_unknown_field(tmp_path, capsys):
    exceptions_path = _write_exceptions_yaml(tmp_path, """
  - path: app/src/main/java/com/example/SomeClass.kt
    class: SomeClass
    method_pattern: "verify"
    operation: raw_sqlite
    class_name: "SomeClass"
    reason: test
    owner: "@test"
    linked_issue: "TEST-001"
""")
    with pytest.raises(SystemExit) as exc_info:
        load_db_structural_exceptions(exceptions_path)
    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "unknown key" in err
    assert "SomeClass.kt" in err


def test_structural_entry_metadata_errors_validate_entries():
    good = {
        "path": _canonical("com/example/SomeClass.kt"),
        "class": "SomeClass",
        "method_pattern": "verifyInternal",
        "operation": "execSQL",
        "reason": "test",
        "owner": "@test",
        "linked_issue": "TEST-001",
    }
    assert structural_entry_metadata_errors(good) == []

    bad_path = {
        "path": "SomeClass.kt",
        "class": "SomeClass",
        "method_pattern": "verifyInternal",
        "operation": "execSQL",
    }
    errors = structural_entry_metadata_errors(bad_path)
    assert any("not canonical" in e for e in errors)

    bad_pattern = {
        "path": _canonical("com/example/SomeClass.kt"),
        "class": "SomeClass",
        "method_pattern": ".*",
        "operation": "execSQL",
    }
    errors = structural_entry_metadata_errors(bad_pattern)
    assert any("method_pattern" in e for e in errors)


# ── 4. Exact class / method / body resolution ─────────────────────────────────

def test_parse_type_declarations_extracts_exact_names_and_kinds():
    content = """package com.example

class Foo {
    fun a() {}
    inner class Bar {}
}

interface Baz {
    fun q(): Int
}

object Qux {
    val MIGRATION_1_2 = object : Migration(1, 2) {}
}
"""
    decls = parse_type_declarations(content.split("\n"))
    by_name = {d["name"]: d["kind"] for d in decls}
    assert by_name == {"Foo": "class", "Bar": "class", "Baz": "interface", "Qux": "object"}
    # Every declaration must expose a sane, non-negative balanced range.
    for d in decls:
        assert 0 <= d["start"] <= d["end"]


def test_parse_function_declarations_extracts_methods_and_bodies():
    content = """class Foo {
    fun a() {
        val x = 1
    }
    fun b() {
        dao.insert(x)
    }
}
"""
    lines = content.split("\n")
    methods = parse_function_declarations(lines, 0, len(lines) - 1)
    assert [m["name"] for m in methods] == ["a", "b"]
    assert methods[0]["start"] == 1
    assert methods[1]["start"] == 4
    assert "dao.insert(x)" in methods[1]["body"]


def test_extract_method_body_balances_nested_braces():
    lines = """fun outer() {
    if (x) {
        for (i in list) {
            dao.insert(i)
        }
    }
    dao.update(y)
}
""".split("\n")
    body = extract_method_body(lines, 0)
    assert "dao.insert(i)" in body
    assert "dao.update(y)" in body
    assert body.count("{") == body.count("}")


def test_extract_method_body_signature_only_is_empty():
    lines = ["class Foo {", "    fun declared(): Int", "}"]
    assert extract_method_body(lines, 1) == ""


def test_scan_exact_class_method_body_authorized(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeRepo.kt")
    _write_kt(
        src,
        "com/example/SomeRepo.kt",
        """package com.example
class SomeRepo {
    fun updateBudget() {
        writeBarrier.checkWritesAllowed("updateBudget")
        budgetDao.update(b)
    }
}
""",
    )
    policy = [_entry(path, "SomeRepo", "updateBudget", ["budgetDao"], "update", barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_wrong_class_in_policy_is_rejected(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeRepo.kt")
    _write_kt(
        src,
        "com/example/SomeRepo.kt",
        "class SomeRepo { fun updateBudget() { budgetDao.update(b) } }",
    )
    policy = [_entry(path, "OtherRepo", "updateBudget", ["budgetDao"], "update")]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations)


def test_scan_wrong_method_in_policy_is_rejected(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeRepo.kt")
    _write_kt(
        src,
        "com/example/SomeRepo.kt",
        "class SomeRepo { fun updateBudget() { budgetDao.update(b) } }",
    )
    policy = [_entry(path, "SomeRepo", "deleteBudget", ["budgetDao"], "update")]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations)


def test_scan_wrong_body_delegator_not_approved(tmp_path, monkeypatch):
    """A delegating method whose body contains no DAO op must not be approved."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/GroupTransactionCoordinator.kt")
    _write_kt(
        src,
        "com/example/GroupTransactionCoordinator.kt",
        """class GroupTransactionCoordinator {
    fun permanentlyDeleteGroup(groupId: Long) {
        deleteGroupAtomic(groupId)
    }
    fun deleteGroupAtomic(groupId: Long) {
        groupDao.delete(group)
    }
}
""",
    )
    policy = [
        _entry(path, "GroupTransactionCoordinator", "permanentlyDeleteGroup",
               ["groupDao"], "delete", barrier_required=True)
    ]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations


# ── 4b. Declaration discovery masking (fake decls in comments/strings) ─────────
# parse_type_declarations / parse_function_declarations must match declarations
# on a stateful source mask (line comments, block comments, strings,
# triple-quoted strings, char literals removed; offsets/newlines preserved) and
# recover names only from the RAW span proven to be code.  Fake class/object/fun
# text inside comments/strings must never create declarations or alter scope,
# and exact class/method lookup must still fail closed on zero/ambiguous real
# matches.

def test_parse_type_declarations_ignores_fake_decls_in_comments_and_strings():
    """Fake class/object/interface text inside line comments, block comments,
    strings, triple-quoted strings, and char literals never creates a type
    declaration — only the REAL declarations after them are discovered and keep
    their true balanced ranges."""
    content = '''// class FakeLineClass {
/* object FakeBlockObject { */
val s = "class FakeStringClass {"
val t = """object FakeTripleObject {
    interface FakeNestedInsideTriple {}
}"""
val c = '{'
class RealClass {
    fun a() {}
}
interface RealInterface {}
'''
    decls = parse_type_declarations(content.split("\n"))
    by_name = {d["name"]: d["kind"] for d in decls}
    assert by_name == {"RealClass": "class", "RealInterface": "interface"}
    real = next(d for d in decls if d["name"] == "RealClass")
    assert real["start"] == 7
    assert real["end"] == 9


def test_parse_type_declarations_comment_fake_class_does_not_absorb_real_class():
    """A fake ``class`` inside a block comment on the SAME line as a real
    declaration must not create a declaration or swallow the real one."""
    content = """/* class FakeClass { */ class RealClass {
    fun a() {}
}
"""
    decls = parse_type_declarations(content.split("\n"))
    assert [d["name"] for d in decls] == ["RealClass"]
    assert decls[0]["kind"] == "class"
    assert decls[0]["start"] == 0
    assert decls[0]["end"] == 2


def test_parse_function_declarations_ignores_fake_fun_in_comments_and_strings():
    """Fake ``fun`` text inside comments, strings, and triple-quoted strings
    never creates a method declaration — only the real function after them is
    discovered with its true body."""
    content = '''class RealClass {
    // fun fakeLineFun() {
    /* fun fakeBlockFun() { */
    val s = "fun fakeStringFun() {"
    val t = """fun fakeTripleFun() {
        expenseDao.delete(fake)
    }"""
    fun realFun() {
        expenseDao.insert(e)
    }
}
'''
    lines = content.split("\n")
    methods = parse_function_declarations(lines, 0, len(lines) - 1)
    assert [m["name"] for m in methods] == ["realFun"]
    assert methods[0]["start"] == 7
    assert "expenseDao.insert(e)" in methods[0]["body"]


def test_parse_function_declarations_comment_fake_fun_does_not_absorb_real_fun():
    """A fake ``fun`` inside a block comment on the SAME line as a real
    declaration must not create a declaration or absorb the real method."""
    content = """class RealClass {
    /* fun fakeFun() { */ fun realFun() {
        expenseDao.insert(e)
    }
}
"""
    lines = content.split("\n")
    methods = parse_function_declarations(lines, 0, len(lines) - 1)
    assert [m["name"] for m in methods] == ["realFun"]
    assert methods[0]["start"] == 1
    assert "expenseDao.insert(e)" in methods[0]["body"]


def test_scan_comment_fake_fun_same_line_does_not_hide_real_mutation(tmp_path, monkeypatch):
    """A fake ``fun`` inside a trailing block comment on the same line as a
    real declaration must not absorb the real method — its mutation is still
    discovered and attributed to method=realMethod instead of being silently
    hidden under a fake method name."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SameLineFakeFunRepo.kt")
    _write_kt(
        src,
        "com/example/SameLineFakeFunRepo.kt",
        """class SameLineFakeFunRepo {
    /* fun fakeMethod() { */ fun realMethod() {
        expenseDao.insert(e)
    }
}
""",
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    real = [v for v in violations if "op=insert" in v[3]]
    assert len(real) == 1, violations
    assert "method=realMethod" in real[0][3]
    assert "method=fakeMethod" not in real[0][3]


def test_fake_type_text_in_comments_does_not_create_false_ambiguity(tmp_path, monkeypatch):
    """Comment text that repeats a REAL class name must not make the name
    ambiguous — the real class remains exactly authorizable."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/AmbiguityRepo.kt")
    _write_kt(
        src,
        "com/example/AmbiguityRepo.kt",
        """// class AmbiguityRepo {
class AmbiguityRepo {
    fun doWork() {
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "AmbiguityRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_fake_declarations_inside_class_do_not_alter_scope(tmp_path, monkeypatch):
    """Fake class/object/fun text inside comments/strings inside a class body
    must not create nested declarations that change the class's method scoping
    — the real method's mutation is still detected and authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/FakeDeclRepo.kt")
    _write_kt(
        src,
        "com/example/FakeDeclRepo.kt",
        """class FakeDeclRepo {
    // class FakeNestedComment {
    /* fun fakeMethodBlock() { */
    val s = "object FakeStringObject {"
    fun realMethod() {
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "FakeDeclRepo", "realMethod", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_fake_migration_object_in_comment_does_not_approve_file_op(tmp_path, monkeypatch):
    """A fake ``val MIGRATION_\\d+_\\d+ = object`` inside a comment must never
    enter the enclosing-declaration scope, so a structural exception keyed on
    the bounded migration form cannot be satisfied by comment text."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DatabaseMigrations.kt")
    _write_kt(
        src,
        "com/example/DatabaseMigrations.kt",
        """object DatabaseMigrations {
    // val MIGRATION_1_2 = object : Migration(1, 2) {}
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE x")
    }
}
""",
    )
    exceptions = [_sexc(path, "DatabaseMigrations", r"MIGRATION_\d+_\d+", "execSQL")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert any("FORBIDDEN_FILE_OP" in v[3] for v in violations), violations


def test_scan_duplicate_real_class_name_fails_closed_as_ambiguous(tmp_path, monkeypatch):
    """Two REAL declarations of the same class name remain ambiguous — every
    mutation inside them fails closed with the ambiguous-class reason, even
    when a policy entry covers the exact (class, method, dao, op) pair."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DupClassRepo.kt")
    _write_kt(
        src,
        "com/example/DupClassRepo.kt",
        """class DupClassRepo {
    fun a() {
        expenseDao.insert(e)
    }
}
class DupClassRepo {
    fun b() {
        expenseDao.insert(f)
    }
}
""",
    )
    policy = [_entry(path, "DupClassRepo", "a", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), policy, [])
    ambiguous = [v for v in violations if "ambiguous class declaration" in v[3]]
    assert len(ambiguous) == 2, violations
    for v in ambiguous:
        # The controlled fail-closed message is:
        #   UNALLOWLISTED_CLASS: ambiguous class declaration 'DupClassRepo' in source file rule=...
        assert "ambiguous class declaration 'DupClassRepo' in source file" in v[3], v[3]
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 2, violations


def test_file_op_with_zero_enclosing_class_lookup_fails_closed(tmp_path, monkeypatch):
    """A file op whose enclosing-class lookup returns ZERO matches (top-level
    code) is associated with ``<top-level>`` and can never be approved by a
    structural exception — exact class lookup fails closed on zero matches."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/TopLevelOps.kt")
    _write_kt(
        src,
        "com/example/TopLevelOps.kt",
        """package com.example

fun doBackup() {
    val db = getDatabasePath("backup.db")
}
""",
    )
    exceptions = [_sexc(path, "SomeOtherClass", r"doBackup", "getDatabasePath")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert any("FORBIDDEN_FILE_OP" in v[3] for v in violations), violations


# ── 5. DAO property/type mapping ──────────────────────────────────────────────

def test_build_dao_var_map_maps_property_type_to_accessor():
    lines = """class GroupLifecycleCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    val memberDao: GroupMemberDao,
) {
    fun x() {
        val auditDao = appDatabase.privacyAuditDao()
        val direct = database.scannedReceiptDao()
    }
}
""".split("\n")
    var_map = build_dao_var_map(lines)
    assert var_map["groupDao"] == "expenseGroupDao"
    assert var_map["memberDao"] == "groupMemberDao"
    assert var_map["auditDao"] == "privacyAuditDao"
    assert var_map["direct"] == "scannedReceiptDao"


def test_extract_mutation_pairs_resolves_property_dao():
    var_map = {"groupDao": "expenseGroupDao", "memberDao": "groupMemberDao"}
    pairs = extract_mutation_pairs("groupDao.insert(g)\nmemberDao.update(m)", var_map)
    assert ("expenseGroupDao", "insert") in pairs
    assert ("groupMemberDao", "update") in pairs

    chain = extract_mutation_pairs("database.scannedReceiptDao().delete(r)", {})
    assert chain == [("scannedReceiptDao", "delete")]


def test_scan_property_typed_dao_authorized(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/GroupLifecycleCoordinator.kt")
    _write_kt(
        src,
        "com/example/GroupLifecycleCoordinator.kt",
        """package com.example
import javax.inject.Inject

class GroupLifecycleCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao
) {
    fun addGroup() {
        groupDao.insert(g)
    }
}
""",
    )
    policy = [_entry(path, "GroupLifecycleCoordinator", "addGroup", ["expenseGroupDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


# ── 6. Exact operation pairs ──────────────────────────────────────────────────

_EXACT_OPERATION_CASES = [
    ("expenseDao", "insert"),
    ("budgetDao", "update"),
    ("categoryDao", "delete"),
    ("expenseDao", "insertAll"),
    ("deliveryDao", "insertOrIgnore"),
    ("groupDao", "archiveGroup"),
    ("groupExpenseDao", "deleteAllForGroup"),
    ("pendingReviewDao", "bulkRenameMerchant"),
    ("backgroundJobRunDao", "staleAbortIfStillRunning"),
    ("categoryDao", "getOrInsertByNameNoCase"),
]


def test_extract_mutation_pairs_exact_operation_names():
    for dao, op in _EXACT_OPERATION_CASES:
        body = f"{dao}.{op}(arg)"
        pairs = extract_mutation_pairs(body, {})
        assert pairs == [(dao, op)], f"expected exactly ({dao}, {op}), got {pairs}"


def test_extract_mutation_pairs_read_only_calls_never_extracted():
    body = (
        "val a = expenseDao.getById(id)\n"
        "val b = expenseDao.observeAll()\n"
        "val c = expenseDao.countAllExpenses()\n"
        "val d = expenseDao.existsByDedupeKey(key)"
    )
    assert extract_mutation_pairs(body, {}) == []


def test_matches_policy_pair_requires_exact_operation():
    path = _canonical("com/example/SomeRepo.kt")
    entries = _normalized([_entry(path, "SomeRepo", "doWork", ["expenseDao"], "insert")])
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "expenseDao", "insert") is not None
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "expenseDao", "insertAll") is None
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "expenseDao", "update") is None


def test_matches_policy_pair_requires_exact_class():
    path = _canonical("com/example/SomeRepo.kt")
    entries = _normalized([_entry(path, "SomeRepo", "doWork", ["expenseDao"], "insert")])
    assert matches_policy_pair(entries, path, "OtherRepo", "doWork", "expenseDao", "insert") is None


def test_matches_policy_pair_requires_exact_method():
    path = _canonical("com/example/SomeRepo.kt")
    entries = _normalized([_entry(path, "SomeRepo", "doWork", ["expenseDao"], "insert")])
    assert matches_policy_pair(entries, path, "SomeRepo", "otherMethod", "expenseDao", "insert") is None


def test_matches_policy_pair_requires_exact_dao():
    path = _canonical("com/example/SomeRepo.kt")
    entries = _normalized([_entry(path, "SomeRepo", "doWork", ["expenseDao"], "insert")])
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "budgetDao", "insert") is None


def test_matches_policy_pair_requires_canonical_path():
    path = _canonical("com/example/SomeRepo.kt")
    # A hand-built entry with a legacy bare basename normalizes to _canonical_path=None
    # and can never authorize a canonical file path (fail closed).
    legacy = _entry("SomeRepo.kt", "SomeRepo", "doWork", ["expenseDao"], "insert")
    entries = _normalized([legacy])
    assert entries[0]["_canonical_path"] is None
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "expenseDao", "insert") is None


# ── 7. All-or-nothing DAO authorization ───────────────────────────────────────

def test_mixed_approved_and_unapproved_dao_pairs_fail(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiDaoRepo.kt")
    _write_kt(
        src,
        "com/example/MultiDaoRepo.kt",
        """class MultiDaoRepo {
    fun doWork() {
        expenseGroupDao.insert(g)
        groupMemberDao.update(m)
    }
}
""",
    )
    policy = [_entry(path, "MultiDaoRepo", "doWork", ["expenseGroupDao"], "insert")]
    violations, _ = scan(str(src), policy, [])
    assert len(violations) == 1, violations
    assert "UNALLOWLISTED_CLASS" in violations[0][3]


def test_mixed_pairs_same_line_fail(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiDaoRepo.kt")
    _write_kt(
        src,
        "com/example/MultiDaoRepo.kt",
        "class MultiDaoRepo { fun doWork() { expenseGroupDao.insert(g); groupMemberDao.update(m) } }",
    )
    policy = [_entry(path, "MultiDaoRepo", "doWork", ["expenseGroupDao"], "insert")]
    violations, _ = scan(str(src), policy, [])
    assert len(violations) == 1, violations
    assert "UNALLOWLISTED_CLASS" in violations[0][3]


def test_all_approved_dao_pairs_pass(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiDaoRepo.kt")
    _write_kt(
        src,
        "com/example/MultiDaoRepo.kt",
        "class MultiDaoRepo { fun doWork() { expenseGroupDao.insert(g); groupMemberDao.update(m) } }",
    )
    policy = [
        _entry(path, "MultiDaoRepo", "doWork", ["expenseGroupDao"], "insert"),
        _entry(path, "MultiDaoRepo", "doWork", ["groupMemberDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


# ── 8. operation: write never authorizes ──────────────────────────────────────

def test_ownership_entry_metadata_errors_validate_entries():
    good = _entry(
        _canonical("com/example/Foo.kt"), "Foo", "doWork", ["expenseDao"], "insert"
    )
    assert ownership_entry_metadata_errors(good) == []

    bad_path = _entry("Foo.kt", "Foo", "doWork", ["expenseDao"], "insert")
    errors = ownership_entry_metadata_errors(bad_path)
    assert any("not canonical" in e for e in errors)

    bad_op = _entry(
        _canonical("com/example/Foo.kt"), "Foo", "doWork", ["expenseDao"], "insert now"
    )
    errors = ownership_entry_metadata_errors(bad_op)
    assert any("'operation'" in e for e in errors)


def test_ownership_metadata_rejects_write_operation():
    errors = ownership_entry_metadata_errors(
        _entry(
            _canonical("com/example/Foo.kt"), "Foo", "doWork", ["expenseDao"], "write"
        )
    )
    assert any("operation: write" in e for e in errors)


def test_ownership_metadata_rejects_unknown_key():
    entry = _entry(
        _canonical("com/example/Foo.kt"), "Foo", "doWork", ["expenseDao"], "insert"
    )
    entry["daoz"] = ["expenseDao"]
    errors = ownership_entry_metadata_errors(entry)
    assert any("unknown key" in e for e in errors)


def test_ownership_metadata_rejects_missing_required_fields():
    # Missing class/method/daos/barrier_required are configuration errors —
    # never a silently-approved entry.
    entry = {"path": _canonical("com/example/Foo.kt"), "operation": "insert"}
    errors = ownership_entry_metadata_errors(entry)
    assert any("'class'" in e for e in errors)
    assert any("'method'" in e for e in errors)
    assert any("daos" in e for e in errors)
    assert any("barrier_required" in e for e in errors)


def test_ownership_metadata_rejects_bad_booleans():
    entry = _entry(
        _canonical("com/example/Foo.kt"), "Foo", "doWork", ["expenseDao"], "insert",
        barrier_required="yes",
    )
    errors = ownership_entry_metadata_errors(entry)
    assert any("'barrier_required'" in e for e in errors)


def test_ownership_metadata_rejects_empty_daos():
    entry = _entry(
        _canonical("com/example/Foo.kt"), "Foo", "doWork", [], "insert"
    )
    errors = ownership_entry_metadata_errors(entry)
    assert any("daos" in e for e in errors)


def test_ownership_metadata_rejects_wildcard_method():
    entry = _entry(
        _canonical("com/example/Foo.kt"), "Foo", "*", ["expenseDao"], "insert"
    )
    errors = ownership_entry_metadata_errors(entry)
    assert any("wildcard" in e for e in errors)


def test_matches_policy_pair_never_authorizes_write_operation():
    path = _canonical("com/example/SomeRepo.kt")
    entries = _normalized([_entry(path, "SomeRepo", "doWork", ["expenseDao"], "write")])
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "expenseDao", "insert") is None
    assert matches_policy_pair(entries, path, "SomeRepo", "doWork", "expenseDao", "update") is None


def test_scan_entry_with_write_operation_never_authorizes(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeRepo.kt")
    _write_kt(
        src,
        "com/example/SomeRepo.kt",
        "class SomeRepo { fun doWork() { expenseDao.insert(e) } }",
    )
    policy = [_entry(path, "SomeRepo", "doWork", ["expenseDao"], "write")]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations)


# ── 9. Structural matching uses fullmatch ─────────────────────────────────────

def test_structural_fullmatch_verify_does_not_match_verify_internal(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeClass.kt")
    _write_kt(
        src,
        "com/example/SomeClass.kt",
        """class SomeClass {
    fun verifyInternal() {
        val db = getDatabase()
        db.execSQL("SELECT 1")
    }
}
""",
    )
    exceptions = [_sexc(path, "SomeClass", r"verify", "execSQL")]
    violations, _ = scan(str(src), [], exceptions)
    assert any("FORBIDDEN_FILE_OP" in v[3] for v in violations), violations


def test_structural_fullmatch_exact_verify_internal_passes(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeClass.kt")
    _write_kt(
        src,
        "com/example/SomeClass.kt",
        """class SomeClass {
    fun verifyInternal() {
        val db = getDatabase()
        db.execSQL("SELECT 1")
    }
}
""",
    )
    exceptions = [_sexc(path, "SomeClass", r"verifyInternal", "execSQL")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations


def test_structural_fullmatch_migration_form_accepts_exact_object(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DatabaseMigrations.kt")
    _write_kt(
        src,
        "com/example/DatabaseMigrations.kt",
        """package com.example
import androidx.room.migration.Migration
object DatabaseMigrations {
    val MIGRATION_145_146 = object : Migration(145, 146) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE test(id INTEGER)")
        }
    }
}
""",
    )
    exceptions = [_sexc(path, "DatabaseMigrations", r"MIGRATION_\d+_\d+", "execSQL")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations


def test_structural_fullmatch_migration_form_rejects_plain_migrate(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DatabaseMigrations.kt")
    _write_kt(
        src,
        "com/example/DatabaseMigrations.kt",
        """package com.example
object DatabaseMigrations {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE x")
    }
}
""",
    )
    exceptions = [_sexc(path, "DatabaseMigrations", r"MIGRATION_\d+_\d+", "execSQL")]
    violations, _ = scan(str(src), [], exceptions)
    assert any("FORBIDDEN_FILE_OP" in v[3] for v in violations), violations


def test_structural_operation_evidence_required(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SomeClass.kt")
    _write_kt(
        src,
        "com/example/SomeClass.kt",
        """class SomeClass {
    fun doBackup() {
        val db = getDatabasePath("backup.db")
    }
}
""",
    )
    exceptions = [_sexc(path, "SomeClass", r"doBackup", "execSQL")]
    violations, _ = scan(str(src), [], exceptions)
    assert any("FORBIDDEN_FILE_OP" in v[3] for v in violations), violations


# ── 10. Missing / unreadable / empty source is fail-closed ────────────────────

def test_scan_missing_source_dir_returns_zero_files(tmp_path):
    violations, files_scanned = scan(str(tmp_path / "does-not-exist"), [], [])
    assert files_scanned == 0
    assert violations == []


def test_scan_empty_source_dir_returns_zero_files(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 0
    assert violations == []


def test_scan_unreadable_file_reports_infrastructure_error(tmp_path, monkeypatch):
    """An unreadable source file emits ONLY the controlled
    ``DB_SCAN_UNREADABLE_FILE`` diagnostic with the canonical relative path —
    the raw OSError message and any absolute filesystem path are never leaked."""
    src = _fixture_source(tmp_path, monkeypatch)
    _write_kt(src, "Bad.kt", "class Bad { fun x() { expenseDao.insert(e) } }")
    target = str(src / "Bad.kt")
    original_open = builtins.open

    def failing_open(file, *args, **kwargs):
        if str(file) == target:
            raise OSError("permission denied C:\\secret\\fixture\\leak")
        return original_open(file, *args, **kwargs)

    monkeypatch.setattr(builtins, "open", failing_open)
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert len(violations) == 1
    code = violations[0][3]
    assert code.startswith("ERROR: DB_SCAN_UNREADABLE_FILE")
    # The canonical repository-relative path is reported (no filesystem leak).
    assert _canonical("Bad.kt") in code
    # The raw exception text and absolute filesystem paths never appear.
    assert "permission denied" not in code
    assert "C:\\secret" not in code
    assert "fixture\\leak" not in code


def test_scan_undecodable_file_reports_infrastructure_error(tmp_path, monkeypatch):
    """A file that fails UTF-8 decode emits only the controlled
    ``DB_SCAN_UNREADABLE_FILE`` diagnostic; the decode exception text (which
    can embed raw payload bytes) is never leaked."""
    src = _fixture_source(tmp_path, monkeypatch)
    _write_kt(src, "Bad.kt", "class Bad { fun x() { expenseDao.insert(e) } }")
    target = str(src / "Bad.kt")
    original_open = builtins.open

    def failing_open(file, *args, **kwargs):
        if str(file) == target:
            raise UnicodeDecodeError(
                "utf-8", b"\xff\xfe\x00secret", 0, 1, "invalid start byte"
            )
        return original_open(file, *args, **kwargs)

    monkeypatch.setattr(builtins, "open", failing_open)
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert len(violations) == 1
    code = violations[0][3]
    assert code.startswith("ERROR: DB_SCAN_UNREADABLE_FILE")
    assert _canonical("Bad.kt") in code
    # Raw decode exception message and payload bytes never appear.
    assert "invalid start byte" not in code
    assert "secret" not in code


def test_scan_dao_interface_files_skipped_but_counted(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    _write_kt(
        src,
        "com/example/ExpenseDao.kt",
        "@Dao interface ExpenseDao { @Insert fun insert(e: Expense): Long }",
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_worker_direct_dao_mutation_fails(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    _write_kt(
        src,
        "com/example/DataRetentionWorker.kt",
        "class DataRetentionWorker { fun run() { scannedReceiptDao.delete(r) } }",
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations)


# ── 11. Focused production-path tests (new exact contract) ────────────────────
# These mirror the REAL production writers with canonical paths and exact
# operations: worker barrier mediation, exchange-rate policy, prompt-state
# policy, and the group implementation path (data/database, never the domain
# interface).

def test_worker_barrier_mediated_entry_authorizes(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt",
        """package com.yourname.expensetracker.data.privacy
class DataRetentionWorker {
    fun doWork() {
        val auditDao = appDatabase.privacyAuditDao()
        auditDao.insert(event)
    }
}
""",
    )
    policy = [_entry(path, "DataRetentionWorker", "doWork", ["privacyAuditDao"], "insert",
                     barrier_required=False, barrier_via="WorkerExecutionGuard")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_worker_barrier_direct_entry_requires_barrier(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt",
        """class WorkerExecutionGuard(
    private val backgroundJobRunDao: BackgroundJobRunDao
) {
    suspend fun recoverStaleRunningJobs() {
        backgroundJobRunDao.staleAbortIfStillRunning(runId, stamp)
    }
}
""",
    )
    policy = [_entry(path, "WorkerExecutionGuard", "recoverStaleRunningJobs",
                     ["backgroundJobRunDao"], "staleAbortIfStillRunning", barrier_required=True)]
    violations, _ = scan(str(src), policy, [])
    assert any("MISSING_WRITE_BARRIER" in v[3] for v in violations), violations

    _write_kt(
        src,
        "com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt",
        """class WorkerExecutionGuard(
    private val backgroundJobRunDao: BackgroundJobRunDao
) {
    suspend fun recoverStaleRunningJobs() {
        writeBarrier.checkWritesAllowed("WorkerExecutionGuard.recoverStaleRunningJobs")
        backgroundJobRunDao.staleAbortIfStillRunning(runId, stamp)
    }
}
""",
    )
    violations, _ = scan(str(src), policy, [])
    assert violations == [], violations


def test_barrier_before_line_matches_canonical_barrier_forms_only():
    """The write-barrier evidence is exact: it matches the ACTUAL barrier forms
    (``writeBarrier.checkWritesAllowed(...)`` and ``writeBarrier.runWrite(...)``)
    but never a read-only mode predicate (``writeBarrier.writesAllowed()`` — it
    does not block writes) or a different receiver
    (``myWriteBarrier.checkWritesAllowed(...)``)."""
    template = [
        "class Foo {",
        "    fun doWork() {",
        "        {BARRIER}",
        "        expenseDao.insert(e)",
        "    }",
        "}",
    ]

    def barrier_ok(barrier_line):
        lines = [line.replace("{BARRIER}", barrier_line) for line in template]
        return _mod._barrier_before_line(lines, fun_start=1, mutation_lineno=4)

    # Canonical enforcing barrier forms used by production writers.
    assert barrier_ok('writeBarrier.checkWritesAllowed("Foo.doWork")') is True
    assert barrier_ok("writeBarrier.runWrite(op) { }") is True
    # A read-only predicate never satisfies the barrier.
    assert barrier_ok("writeBarrier.writesAllowed()") is False
    # A different receiver cannot satisfy evidence (exact receiver match).
    assert barrier_ok('myWriteBarrier.checkWritesAllowed("Foo.doWork")') is False
    # A qualified receiver with spaces around the dot is NOT the unqualified
    # ``writeBarrier`` receiver — it never satisfies barrier evidence.
    assert barrier_ok('foo . writeBarrier.checkWritesAllowed("Foo.doWork")') is False
    # A qualified receiver with a comment between the dot and the token is NOT
    # the unqualified ``writeBarrier`` receiver either.
    assert barrier_ok('foo. /*c*/ writeBarrier.checkWritesAllowed("Foo.doWork")') is False
    # No barrier at all still fails closed.
    assert barrier_ok("val x = 1") is False


def test_scan_barrier_evidence_real_call_before_mutation_passes(tmp_path, monkeypatch):
    """A REAL ``writeBarrier.checkWritesAllowed(...)`` call strictly before the
    DAO mutation satisfies barrier evidence and the writer is authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/BarrierEvidenceRepo.kt",
        """class BarrierEvidenceRepo {
    fun doWork() {
        writeBarrier.checkWritesAllowed("BarrierEvidenceRepo.doWork")
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_barrier_evidence_fake_call_in_comment_fails(tmp_path, monkeypatch):
    """A fake ``writeBarrier.checkWritesAllowed(...)`` inside a line comment is
    masked and NEVER satisfies barrier evidence — the writer still fails closed
    with MISSING_WRITE_BARRIER."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/BarrierEvidenceRepo.kt",
        """class BarrierEvidenceRepo {
    fun doWork() {
        // writeBarrier.checkWritesAllowed("fake")
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    missing = [v for v in violations if "MISSING_WRITE_BARRIER" in v[3]]
    assert len(missing) == 1, violations


def test_scan_barrier_evidence_fake_call_in_string_fails(tmp_path, monkeypatch):
    """A fake ``writeBarrier.checkWritesAllowed(...)`` inside a string literal
    is masked and NEVER satisfies barrier evidence — the writer still fails
    closed with MISSING_WRITE_BARRIER."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/BarrierEvidenceRepo.kt",
        """class BarrierEvidenceRepo {
    fun doWork() {
        val fake = "writeBarrier.checkWritesAllowed(fake)"
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    missing = [v for v in violations if "MISSING_WRITE_BARRIER" in v[3]]
    assert len(missing) == 1, violations


def test_scan_barrier_evidence_qualified_receiver_fails(tmp_path, monkeypatch):
    """A QUALIFIED receiver (``foo.writeBarrier.checkWritesAllowed(...)``) is
    NOT the unqualified ``writeBarrier`` receiver — it never satisfies barrier
    evidence and the writer fails closed with MISSING_WRITE_BARRIER."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/BarrierEvidenceRepo.kt",
        """class BarrierEvidenceRepo {
    fun doWork() {
        foo.writeBarrier.checkWritesAllowed("BarrierEvidenceRepo.doWork")
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    missing = [v for v in violations if "MISSING_WRITE_BARRIER" in v[3]]
    assert len(missing) == 1, violations


def test_scan_barrier_evidence_spaced_or_comment_qualified_receiver_fails(tmp_path, monkeypatch):
    """A QUALIFIED receiver with spaces around the dot
    (``foo . writeBarrier.checkWritesAllowed(...)``) or with a comment between
    the dot and the token (``foo. /*comment*/ writeBarrier.checkWritesAllowed(...)``)
    is NOT the unqualified ``writeBarrier`` receiver — it never satisfies
    barrier evidence and the writer fails closed with MISSING_WRITE_BARRIER."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    for barrier_line in (
        'foo . writeBarrier.checkWritesAllowed("BarrierEvidenceRepo.doWork")',
        'foo. /*comment*/ writeBarrier.checkWritesAllowed("BarrierEvidenceRepo.doWork")',
    ):
        _write_kt(
            src,
            "com/example/BarrierEvidenceRepo.kt",
            f"""class BarrierEvidenceRepo {{
    fun doWork() {{
        {barrier_line}
        expenseDao.insert(e)
    }}
}}
""",
        )
        violations, files_scanned = scan(str(src), policy, [])
        assert files_scanned == 1
        missing = [v for v in violations if "MISSING_WRITE_BARRIER" in v[3]]
        assert len(missing) == 1, violations


def test_scan_barrier_evidence_comment_and_spaced_qualified_receiver_fails(tmp_path, monkeypatch):
    """A QUALIFIED receiver with a comment between the receiver and the dot
    AND spaces around the dot (``foo /* comment */ . writeBarrier.checkWritesAllowed(...)``)
    is NOT the unqualified ``writeBarrier`` receiver — it never satisfies
    barrier evidence and the writer fails closed with MISSING_WRITE_BARRIER."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/BarrierEvidenceRepo.kt",
        """class BarrierEvidenceRepo {
    fun doWork() {
        foo /* comment */ . writeBarrier.checkWritesAllowed("BarrierEvidenceRepo.doWork")
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    # Exactly one violation, and it is the controlled MISSING_WRITE_BARRIER:
    # the mutation is NOT authorized.
    assert len(violations) == 1, violations
    assert "MISSING_WRITE_BARRIER" in violations[0][3], violations


def test_scan_barrier_evidence_wrong_order_fails(tmp_path, monkeypatch):
    """A barrier call AFTER the DAO mutation is NOT before it — it never
    satisfies barrier evidence and the writer fails closed with
    MISSING_WRITE_BARRIER."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BarrierEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/BarrierEvidenceRepo.kt",
        """class BarrierEvidenceRepo {
    fun doWork() {
        expenseDao.insert(e)
        writeBarrier.checkWritesAllowed("BarrierEvidenceRepo.doWork")
    }
}
""",
    )
    policy = [_entry(path, "BarrierEvidenceRepo", "doWork", ["expenseDao"], "insert",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    missing = [v for v in violations if "MISSING_WRITE_BARRIER" in v[3]]
    assert len(missing) == 1, violations


def test_exchange_rate_policy_exact_entries_authorize(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt",
        """class ExchangeRateStoreAdapter(
    private val exchangeRateDao: ExchangeRateDao
) {
    suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdate")
        exchangeRateDao.insertOrUpdate(rate)
    }
    suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdateAll")
        exchangeRateDao.insertOrUpdateAll(rates)
    }
    suspend fun deleteOldRates(olderThan: Long) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.deleteOldRates")
        exchangeRateDao.deleteOldRates(olderThan)
    }
}
""",
    )
    policy = [
        _entry(path, "ExchangeRateStoreAdapter", "insertOrUpdate", ["exchangeRateDao"], "insertOrUpdate", barrier_required=True),
        _entry(path, "ExchangeRateStoreAdapter", "insertOrUpdateAll", ["exchangeRateDao"], "insertOrUpdateAll", barrier_required=True),
        _entry(path, "ExchangeRateStoreAdapter", "deleteOldRates", ["exchangeRateDao"], "deleteOldRates", barrier_required=True),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_exchange_rate_policy_wrong_operation_rejected(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt",
        """class ExchangeRateStoreAdapter(
    private val exchangeRateDao: ExchangeRateDao
) {
    suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdate")
        exchangeRateDao.insertOrUpdate(rate)
    }
}
""",
    )
    # Exact method but WRONG operation - the policy authorizes `update`, source calls insertOrUpdate.
    policy = [_entry(path, "ExchangeRateStoreAdapter", "insertOrUpdate", ["exchangeRateDao"], "update", barrier_required=True)]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations


def test_prompt_state_policy_exact_entries_authorize(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/yourname/expensetracker/data/repository/PromptStateRepository.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/repository/PromptStateRepository.kt",
        """class PromptStateRepository(
    private val promptStateDao: PromptStateDao
) {
    suspend fun recordPrompt(promptType: String): Long {
        writeBarrier.checkWritesAllowed("PromptStateRepository.recordPrompt")
        val promptState = PromptState(promptType = promptType, createdAt = 0L)
        return promptStateDao.insertPromptState(promptState)
    }
    suspend fun cleanupOldRecords() {
        writeBarrier.checkWritesAllowed("PromptStateRepository.cleanupOldRecords")
        val cutoffTime = 0L
        promptStateDao.deleteOldPrompts(cutoffTime)
    }
}
""",
    )
    policy = [
        _entry(path, "PromptStateRepository", "recordPrompt", ["promptStateDao"], "insertPromptState", barrier_required=True),
        _entry(path, "PromptStateRepository", "cleanupOldRecords", ["promptStateDao"], "deleteOldPrompts", barrier_required=True),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_group_implementation_path_authorized_data_impl_only(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    data_path = _canonical("com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt")
    domain_path = _canonical("com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt",
        """package com.example.data
class GroupTransactionCoordinator(
    private val groupDao: ExpenseGroupDao
) {
    suspend fun archiveGroup(groupId: Long) {
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.archiveGroup")
        groupDao.archiveGroup(groupId)
    }
}
""",
    )
    _write_kt(
        src,
        "com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt",
        """package com.example.domain
interface GroupTransactionCoordinator {
    suspend fun archiveGroup(groupId: Long): Boolean
}
""",
    )
    # The data impl declares `groupDao: ExpenseGroupDao`, so the scanner resolves
    # the DAO identity to the Room accessor name `expenseGroupDao` exactly as it
    # does for the real data/database/GroupTransactionCoordinator.kt.
    policy = [_entry(data_path, "GroupTransactionCoordinator", "archiveGroup",
                     ["expenseGroupDao"], "archiveGroup", barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 2
    assert violations == [], violations


def test_group_implementation_wrong_subdirectory_path_rejected(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    data_path = _canonical("com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt")
    domain_path = _canonical("com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt",
        """class GroupTransactionCoordinator(
    private val groupDao: ExpenseGroupDao
) {
    suspend fun archiveGroup(groupId: Long) {
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.archiveGroup")
        groupDao.archiveGroup(groupId)
    }
}
""",
    )
    # Policy points at the DOMAIN interface path - it must NOT authorize the data impl.
    policy = [_entry(domain_path, "GroupTransactionCoordinator", "archiveGroup",
                     ["expenseGroupDao"], "archiveGroup", barrier_required=True)]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations


def test_group_implementation_delegator_not_approved(tmp_path, monkeypatch):
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt")
    _write_kt(
        src,
        "com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt",
        """class GroupTransactionCoordinator(
    private val groupDao: ExpenseGroupDao,
    private val groupExpenseDao: GroupExpenseDao
) {
    suspend fun permanentlyDeleteGroup(groupId: Long) {
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.permanentlyDeleteGroup")
        deleteGroupAtomic(groupId)
    }
    suspend fun deleteGroupAtomic(groupId: Long) {
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.deleteGroupAtomic")
        groupExpenseDao.deleteAllForGroup(groupId)
        groupDao.delete(group)
    }
}
""",
    )
    policy = [_entry(path, "GroupTransactionCoordinator", "permanentlyDeleteGroup",
                     ["expenseGroupDao", "groupExpenseDao"], "deleteAllForGroup", barrier_required=True)]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations


def test_focused_production_paths_are_canonical():
    """The real production writer paths used by the focused tests are canonical."""
    paths = [
        "app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt",
        "app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt",
        "app/src/main/java/com/yourname/expensetracker/data/repository/PromptStateRepository.kt",
        "app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt",
    ]
    for path in paths:
        assert canonical_policy_path_error(path) is None, path
        assert canonical_policy_path(path) == path


# ── 12. Method-local DAO alias leakage (OUT_OF_SCOPE_DAO_ALIAS) ───────────────

def test_method_a_local_alias_cannot_authorize_method_b(tmp_path, monkeypatch):
    """A DAO local alias declared in method A must never authorize method B.

    Even when a policy entry would otherwise cover B's (dao, op) pair, the
    receiver is out of scope in B and the mutation fails closed with
    OUT_OF_SCOPE_DAO_ALIAS.
    """
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/AliasLeakRepo.kt")
    _write_kt(
        src,
        "com/example/AliasLeakRepo.kt",
        """class AliasLeakRepo {
    fun methodA() {
        val dao = database.expenseDao()
        dao.insert(e)
    }
    fun methodB() {
        dao.delete(b)
    }
}
""",
    )
    policy = [
        _entry(path, "AliasLeakRepo", "methodA", ["expenseDao"], "insert"),
        _entry(path, "AliasLeakRepo", "methodB", ["expenseDao"], "delete"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    out_of_scope = [v for v in violations if "OUT_OF_SCOPE_DAO_ALIAS" in v[3]]
    assert len(out_of_scope) == 1, violations
    # The violation is anchored to methodB's exact line (1-based line 7).
    assert out_of_scope[0][1] == 7
    assert "method=methodB" in out_of_scope[0][3]
    assert "dao=expenseDao" in out_of_scope[0][3]
    assert "op=delete" in out_of_scope[0][3]


def test_method_b_own_local_alias_passes(tmp_path, monkeypatch):
    """The same alias name declared INSIDE method B authorizes B's own call."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/AliasLeakRepo.kt")
    _write_kt(
        src,
        "com/example/AliasLeakRepo.kt",
        """class AliasLeakRepo {
    fun methodA() {
        val dao = database.expenseDao()
        dao.insert(e)
    }
    fun methodB() {
        val dao = database.expenseDao()
        dao.delete(b)
    }
}
""",
    )
    policy = [
        _entry(path, "AliasLeakRepo", "methodA", ["expenseDao"], "insert"),
        _entry(path, "AliasLeakRepo", "methodB", ["expenseDao"], "delete"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


# ── 13. cleanupOldDismissedAlerts detection and exact authorization ───────────

def test_cleanup_old_dismissed_alerts_detected_and_exact_authorization(tmp_path, monkeypatch):
    """The ``cleanup`` verb prefix detects cleanupOldDismissedAlerts and the
    policy must name the EXACT DAO method (never the bare verb)."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/AnomalyCleanupRepo.kt")
    _write_kt(
        src,
        "com/example/AnomalyCleanupRepo.kt",
        """class AnomalyCleanupRepo {
    fun purgeDismissed(olderThanMs: Long) {
        anomalyAlertDao.cleanupOldDismissedAlerts(olderThanMs)
    }
}
""",
    )
    # Detection: without any policy entry the call must be flagged.
    violations, _ = scan(str(src), [], [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations
    assert any("op=cleanupOldDismissedAlerts" in v[3] for v in violations), violations

    # Exact authorization passes.
    policy_exact = [_entry(path, "AnomalyCleanupRepo", "purgeDismissed",
                           ["anomalyAlertDao"], "cleanupOldDismissedAlerts")]
    violations, files_scanned = scan(str(src), policy_exact, [])
    assert files_scanned == 1
    assert violations == [], violations

    # A bare verb-prefix operation never authorizes the exact call.
    policy_prefix = [_entry(path, "AnomalyCleanupRepo", "purgeDismissed",
                            ["anomalyAlertDao"], "cleanup")]
    violations, _ = scan(str(src), policy_prefix, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations
    assert any("op=cleanupOldDismissedAlerts" in v[3] for v in violations), violations


# ── 14. Multi-line DAO call detection ─────────────────────────────────────────

def test_multiline_dao_call_detected(tmp_path, monkeypatch):
    """A DAO call split across lines (receiver then ``.insert(...)``) is still
    detected from the complete method body — never missed by line scanning."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineRepo.kt",
        """class MultiLineRepo {
    fun doWork() {
        expenseDao
            .insert(e)
    }
}
""",
    )
    policy = [_entry(path, "MultiLineRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_multiline_dao_call_uncovered_fails(tmp_path, monkeypatch):
    """A multi-line call is still a real mutation: without an exact policy
    entry it fails closed with UNALLOWLISTED_CLASS."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineRepo.kt",
        """class MultiLineRepo {
    fun doWork() {
        expenseDao
            .update(b)
    }
}
""",
    )
    policy = [_entry(path, "MultiLineRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations
    assert any("op=update" in v[3] for v in violations), violations


# ── 15. Multi-line expression bodies ──────────────────────────────────────────

def test_multiline_expression_body_detected_and_authorized(tmp_path, monkeypatch):
    """A bounded multi-line expression body is parsed to its complete boundary
    and its mutations are authorized normally."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/ExprBodyRepo.kt")
    _write_kt(
        src,
        "com/example/ExprBodyRepo.kt",
        """class ExprBodyRepo {
    fun doWork() =
        expenseDao.insert(e)
}
""",
    )
    policy = [_entry(path, "ExprBodyRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_multiline_expression_body_all_pairs_required(tmp_path, monkeypatch):
    """Every mutation inside a multi-line expression body must be authorized —
    a partial policy still fails closed."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/ExprBodyRepo.kt")
    _write_kt(
        src,
        "com/example/ExprBodyRepo.kt",
        """class ExprBodyRepo {
    fun doWork() = expenseDao.insert(e) +
        budgetDao.update(b)
}
""",
    )
    policy = [_entry(path, "ExprBodyRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), policy, [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations
    assert any("op=update" in v[3] for v in violations), violations


def test_unbounded_expression_body_fails_closed(tmp_path, monkeypatch):
    """An expression body that cannot be bounded fails closed with
    UNSUPPORTED_EXPRESSION_BODY instead of authorizing a truncated body."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/ExprBodyRepo.kt")
    _write_kt(
        src,
        "com/example/ExprBodyRepo.kt",
        """class ExprBodyRepo {
    fun doWork() = expenseDao.insert(e) +
        budgetDao.update(b) +
""",
    )
    policy = [
        _entry(path, "ExprBodyRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "ExprBodyRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=ExprBodyRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # Fail closed: even a fully-covering policy cannot rescue an unbounded body.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_unterminated_braced_method_body_fails_closed(tmp_path, monkeypatch):
    """An unterminated NORMAL (braced) method body — one whose ``{`` never
    closes before the enclosing type/file bound — fails closed with the
    controlled UNSUPPORTED_METHOD_BODY violation.  The DAO mutation inside the
    partial body must never be authorized, even by a fully-covering policy:
    the scanner cannot prove where the method ends, so nothing is authorized
    from the truncated body."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/UnterminatedRepo.kt")
    _write_kt(
        src,
        "com/example/UnterminatedRepo.kt",
        """package com.example
class UnterminatedRepo {
    fun updateBudget() {
        writeBarrier.checkWritesAllowed("updateBudget")
        budgetDao.update(b)
""",  # NOTE: neither the method nor the class body is ever closed
    )
    policy = [_entry(path, "UnterminatedRepo", "updateBudget", ["budgetDao"], "update",
                     barrier_required=True)]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    # Exactly one controlled violation; the partial body is never authorized.
    assert len(violations) == 1, violations
    unsupported = [v for v in violations if "UNSUPPORTED_METHOD_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=UnterminatedRepo" in unsupported[0][3]
    assert "method=updateBudget" in unsupported[0][3]
    # Fail closed: no mutation from the partial body is authorized and the
    # expression-body reason code is NOT emitted for a braced method.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations
    assert len([v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]) == 0, violations


# ── 15b. Comment/string-aware brace accounting ────────────────────────────────

def test_line_comment_brace_does_not_close_method_body(tmp_path, monkeypatch):
    """A ``// }`` line comment must never terminate a method body early — the
    mutation on the following line stays inside the body and is authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/CommentBraceRepo.kt")
    _write_kt(
        src,
        "com/example/CommentBraceRepo.kt",
        """class CommentBraceRepo {
    fun doWork() {
        // } this comment contains a closing brace
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "CommentBraceRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_block_comment_braces_do_not_close_method_body(tmp_path, monkeypatch):
    """Block-comment braces must not open/close a method body."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/CommentBraceRepo.kt")
    _write_kt(
        src,
        "com/example/CommentBraceRepo.kt",
        """class CommentBraceRepo {
    fun doWork() {
        /* } { */
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "CommentBraceRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_comment_and_string_braces_do_not_close_class_body(tmp_path, monkeypatch):
    """Class-level comments/strings containing braces must not close the class
    body early — the mutation inside the class is still scanned and the
    all-or-nothing policy applies."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/CommentBraceRepo.kt")
    _write_kt(
        src,
        "com/example/CommentBraceRepo.kt",
        """class CommentBraceRepo {
    // }
    val template = "}"
    fun doWork() {
        expenseDao.insert(e)
    }
}
""",
    )
    # Without a policy entry the mutation must still be detected (proving the
    # class/method scope was not collapsed by the comment/string braces).
    violations, _ = scan(str(src), [], [])
    assert any("UNALLOWLISTED_CLASS" in v[3] for v in violations), violations
    # With the exact policy entry it authorizes normally.
    policy = [_entry(path, "CommentBraceRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_multiline_block_comment_braces_across_lines(tmp_path, monkeypatch):
    """A block comment spanning multiple lines with braces must be fully
    masked so neither line distorts method-body accounting."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/CommentBraceRepo.kt")
    _write_kt(
        src,
        "com/example/CommentBraceRepo.kt",
        """class CommentBraceRepo {
    fun doWork() {
        /* } {
           } { */
        expenseDao.insert(e)
    }
}
""",
    )
    policy = [_entry(path, "CommentBraceRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


# ── 15c. Multi-line control-flow expression bodies ────────────────────────────

def test_multiline_if_expression_body_parsed_completely(tmp_path, monkeypatch):
    """A valid ``= if (condition) { ... } else { ... }`` expression body with
    DAO mutations on following lines is parsed to its complete boundary — the
    else branch is never silently truncated."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineIfRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineIfRepo.kt",
        """class MultiLineIfRepo {
    fun doWork(condition: Boolean) = if (condition) {
        expenseDao.insert(e)
    } else {
        budgetDao.update(b)
    }
}
""",
    )
    policy = [
        _entry(path, "MultiLineIfRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "MultiLineIfRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_multiline_if_expression_body_partial_policy_fails(tmp_path, monkeypatch):
    """A partial policy must NOT authorize a multi-line if/else expression
    body: the else-branch mutation is extracted and all-or-nothing
    authorization applies."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineIfRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineIfRepo.kt",
        """class MultiLineIfRepo {
    fun doWork(condition: Boolean) = if (condition) {
        expenseDao.insert(e)
    } else {
        budgetDao.update(b)
    }
}
""",
    )
    policy = [_entry(path, "MultiLineIfRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), policy, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=update" in v[3] for v in violations
    ), violations


def test_multiline_if_expression_body_else_if_chain(tmp_path, monkeypatch):
    """``else if`` chains inside an expression body stay bounded and every
    branch's mutations are extracted."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineIfRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineIfRepo.kt",
        """class MultiLineIfRepo {
    fun doWork(a: Int) = if (a > 0) {
        expenseDao.insert(e)
    } else if (a == 0) {
        budgetDao.update(b)
    } else {
        categoryDao.delete(c)
    }
}
""",
    )
    policy = [
        _entry(path, "MultiLineIfRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "MultiLineIfRepo", "doWork", ["budgetDao"], "update"),
        _entry(path, "MultiLineIfRepo", "doWork", ["categoryDao"], "delete"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_multiline_try_catch_expression_body_parsed(tmp_path, monkeypatch):
    """A multi-line ``= try { } catch { }`` expression body is bounded
    structurally and its mutations are extracted."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineTryRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineTryRepo.kt",
        """class MultiLineTryRepo {
    fun doWork() = try {
        expenseDao.insert(e)
    } catch (ex: Exception) {
        budgetDao.update(b)
    }
}
""",
    )
    policy = [
        _entry(path, "MultiLineTryRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "MultiLineTryRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_unbounded_multiline_if_expression_fails_closed(tmp_path, monkeypatch):
    """A ``= if (...)`` expression body that can never be bounded fails closed
    with UNSUPPORTED_EXPRESSION_BODY and is never authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineIfRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineIfRepo.kt",
        """class MultiLineIfRepo {
    fun doWork(condition: Boolean) = if (condition) {
        expenseDao.insert(e)
""",
    )
    policy = [_entry(path, "MultiLineIfRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=MultiLineIfRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # Fail closed: even a fully-covering policy cannot rescue an unbounded body.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


# ── 15d. Same-line control-flow expression bodies ─────────────────────────────

def test_same_line_if_else_expression_body_later_branch_detected(tmp_path, monkeypatch):
    """A same-line ``= if (...) { ... } else { ... }`` expression body with a
    mutation in the later (else) branch must be parsed to its complete
    boundary — the later-branch mutation is detected and all-or-nothing
    authorization applies."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SameLineIfRepo.kt")
    _write_kt(
        src,
        "com/example/SameLineIfRepo.kt",
        """class SameLineIfRepo {
    fun doWork(condition: Boolean) = if (condition) { expenseDao.insert(e) } else { budgetDao.update(b) }
}
""",
    )
    # Detection: a partial policy covering only the first branch still fails
    # closed on the later-branch mutation.
    partial = [_entry(path, "SameLineIfRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), partial, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=update" in v[3] for v in violations
    ), violations
    # Full policy authorizes both branches.
    full = [
        _entry(path, "SameLineIfRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "SameLineIfRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), full, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_same_line_branch_close_else_on_following_line(tmp_path, monkeypatch):
    """The first branch may close on the declaration line while ``else``
    continues on the following line — the body must not be truncated at the
    closing brace."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SameLineIfRepo.kt")
    _write_kt(
        src,
        "com/example/SameLineIfRepo.kt",
        """class SameLineIfRepo {
    fun doWork(condition: Boolean) = if (condition) { expenseDao.insert(e) }
        else {
            budgetDao.update(b)
        }
}
""",
    )
    partial = [_entry(path, "SameLineIfRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), partial, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=update" in v[3] for v in violations
    ), violations


def test_same_line_try_catch_expression_body_later_branch_detected(tmp_path, monkeypatch):
    """A same-line ``= try { ... } catch { ... }`` expression body with a
    mutation in the later (catch) branch is parsed completely and the
    later-branch mutation is detected."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SameLineTryRepo.kt")
    _write_kt(
        src,
        "com/example/SameLineTryRepo.kt",
        """class SameLineTryRepo {
    fun doWork() = try { expenseDao.insert(e) } catch (ex: Exception) { budgetDao.update(b) }
}
""",
    )
    partial = [_entry(path, "SameLineTryRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), partial, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=update" in v[3] for v in violations
    ), violations
    full = [
        _entry(path, "SameLineTryRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "SameLineTryRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), full, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_same_line_try_catch_finally_expression_body_detected(tmp_path, monkeypatch):
    """A ``= try {} catch {} finally {}`` expression body spanning lines stays
    bounded through every continuation keyword and every branch's mutations are
    detected."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SameLineTryRepo.kt")
    _write_kt(
        src,
        "com/example/SameLineTryRepo.kt",
        """class SameLineTryRepo {
    fun doWork() = try { expenseDao.insert(e) }
    catch (ex: Exception) { budgetDao.update(b) }
    finally { categoryDao.delete(c) }
}
""",
    )
    partial = [_entry(path, "SameLineTryRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), partial, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=update" in v[3] for v in violations
    ), violations
    full = [
        _entry(path, "SameLineTryRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "SameLineTryRepo", "doWork", ["budgetDao"], "update"),
        _entry(path, "SameLineTryRepo", "doWork", ["categoryDao"], "delete"),
    ]
    violations, files_scanned = scan(str(src), full, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_same_line_when_expression_body_unbounded_fails_closed(tmp_path, monkeypatch):
    """A same-line ``= when (x) {`` expression body that never bounds fails
    closed with UNSUPPORTED_EXPRESSION_BODY and is never authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SameLineWhenRepo.kt")
    _write_kt(
        src,
        "com/example/SameLineWhenRepo.kt",
        """class SameLineWhenRepo {
    fun doWork(x: Int) = when (x) {
        expenseDao.insert(e)
""",
    )
    policy = [_entry(path, "SameLineWhenRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=SameLineWhenRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # Fail closed: even a fully-covering policy cannot rescue an unbounded body.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


# ── 15e. Comment/string-masked mutation extraction ────────────────────────────

def test_extract_mutation_pairs_ignores_comments_and_strings():
    """DAO-looking calls inside line comments, block comments, strings,
    triple-quoted strings, and char literals never become mutation pairs —
    only the real mutation is extracted."""
    body = '''// expenseDao.insert(fakeLineComment)
/* budgetDao.update(fakeBlockComment) */
val s = "categoryDao.delete(fakeString)"
val t = """groupDao.archiveGroup(fakeTriple)"""
val c = 'x' /* deliveryDao.insertOrIgnore(fakeTailComment) */
expenseDao.insert(real)
'''
    pairs = extract_mutation_pairs(body, {})
    assert pairs == [("expenseDao", "insert")], pairs


def test_mutation_extraction_ignores_fake_dao_calls_in_comments_and_strings(tmp_path, monkeypatch):
    """Fake DAO calls in comments/strings inside a real method body must never
    produce mutation pairs; the real mutation is still detected and authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MaskMutationRepo.kt")
    _write_kt(
        src,
        "com/example/MaskMutationRepo.kt",
        '''class MaskMutationRepo {
    fun doWork() {
        // expenseDao.insert(fakeLineComment)
        /* budgetDao.update(fakeBlockComment) */
        val sql = "categoryDao.delete(fakeString)"
        val triple = """
            groupDao.archiveGroup(fakeTriple)
        """
        val c = 'x' /* deliveryDao.insertOrIgnore(fakeTailComment) */
        expenseDao.insert(real)
    }
}
''',
    )
    # Only the real mutation is reported: a policy covering it passes cleanly.
    policy = [_entry(path, "MaskMutationRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations
    # The real mutation is still detected: a policy covering only fake ops
    # (or the wrong op) fails on the real one.
    wrong = [_entry(path, "MaskMutationRepo", "doWork", ["expenseDao"], "update")]
    violations, _ = scan(str(src), wrong, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=insert" in v[3] for v in violations
    ), violations


def test_multiline_block_comment_fake_dao_call_ignored(tmp_path, monkeypatch):
    """A fake DAO call inside a multi-line block comment is masked to its true
    end and never becomes a mutation pair, while a real mutation below it is
    still detected with the correct source line."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MaskMutationRepo.kt")
    _write_kt(
        src,
        "com/example/MaskMutationRepo.kt",
        """class MaskMutationRepo {
    fun doWork() {
        /*
        expenseDao.insert(fakeAcrossLines)
        */
        budgetDao.update(real)
    }
}
""",
    )
    # Detection: the real mutation is attributed to its exact line (line 6,
    # 1-based) — not to a line inside the masked comment.
    violations, _ = scan(str(src), [], [])
    real = [v for v in violations if "op=update" in v[3]]
    assert len(real) == 1, violations
    assert real[0][1] == 6, real
    # Authorization: a policy covering only the real pair passes cleanly.
    policy = [_entry(path, "MaskMutationRepo", "doWork", ["budgetDao"], "update")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


# ── 15f. Brace-less control-flow expression bodies fail closed ────────────────

def test_brace_less_if_expression_header_fails_closed(tmp_path, monkeypatch):
    """A brace-less ``= if (x)`` expression body whose branch body lives on a
    following line cannot be bounded — the method fails closed with
    UNSUPPORTED_EXPRESSION_BODY.  The later DAO mutation is never silently
    omitted, nor is a truncated body authorized by a covering policy."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessIfRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessIfRepo.kt",
        """class BraceLessIfRepo {
    fun doWork(condition: Boolean) = if (condition)
        expenseDao.insert(e)
}
""",
    )
    # Even a fully-covering policy cannot rescue the method: the expression
    # boundary is unknown, so the mutation must never be authorized.
    policy = [_entry(path, "BraceLessIfRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessIfRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # No mutation is silently omitted and none is authorized from a truncated
    # body: the method fails closed instead of passing.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_braced_if_brace_less_else_fails_closed(tmp_path, monkeypatch):
    """A braced ``= if (...) { ... }`` body with a brace-less ``else`` branch
    cannot be bounded reliably — the method fails closed with
    UNSUPPORTED_EXPRESSION_BODY instead of truncating the else branch."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessElseRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessElseRepo.kt",
        """class BraceLessElseRepo {
    fun doWork(condition: Boolean) = if (condition) {
        expenseDao.insert(e)
    } else
        budgetDao.update(b)
}
""",
    )
    policy = [
        _entry(path, "BraceLessElseRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BraceLessElseRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessElseRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # The else-branch mutation is neither silently omitted nor authorized.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_braced_if_next_line_brace_less_else_not_silently_omitted(tmp_path, monkeypatch):
    """``}\\nelse`` brace-less continuation (keyword on its own line) previously
    truncated the body at the ``else`` line — the else-branch mutation was
    silently dropped and a fully-covering policy PASSED.  It now fails closed
    with UNSUPPORTED_EXPRESSION_BODY so no mutation is silently omitted."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessElseRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessElseRepo.kt",
        """class BraceLessElseRepo {
    fun doWork(condition: Boolean) = if (condition) {
        expenseDao.insert(e)
    }
    else
        budgetDao.update(b)
}
""",
    )
    policy = [
        _entry(path, "BraceLessElseRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BraceLessElseRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessElseRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_brace_less_multiline_when_fails_closed(tmp_path, monkeypatch):
    """A brace-less ``= when (x)`` expression body whose branch body lives on a
    following line cannot be bounded — the method fails closed with
    UNSUPPORTED_EXPRESSION_BODY.  The later DAO mutation is never silently
    omitted, nor is a truncated body authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessWhenRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessWhenRepo.kt",
        """class BraceLessWhenRepo {
    fun doWork(x: Int) = when (x)
        expenseDao.insert(e)
}
""",
    )
    policy = [_entry(path, "BraceLessWhenRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessWhenRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_brace_less_try_expression_body_fails_closed(tmp_path, monkeypatch):
    """A brace-less ``= try`` expression body whose branch body lives on a
    following line cannot be bounded — the method fails closed with
    UNSUPPORTED_EXPRESSION_BODY.  The later DAO mutation is never silently
    omitted, nor is a truncated body authorized by a covering policy."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessTryRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessTryRepo.kt",
        """class BraceLessTryRepo {
    fun doWork() = try
        expenseDao.insert(e)
}
""",
    )
    policy = [_entry(path, "BraceLessTryRepo", "doWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessTryRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # No mutation is silently omitted and none is authorized from a truncated
    # body: the method fails closed instead of passing.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_braced_try_brace_less_catch_fails_closed(tmp_path, monkeypatch):
    """A braced ``= try { ... }`` body with a brace-less ``catch`` branch
    cannot be bounded reliably — the method fails closed with
    UNSUPPORTED_EXPRESSION_BODY instead of truncating the catch branch."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessCatchRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessCatchRepo.kt",
        """class BraceLessCatchRepo {
    fun doWork() = try {
        expenseDao.insert(e)
    } catch (ex: Exception)
        budgetDao.update(b)
}
""",
    )
    policy = [
        _entry(path, "BraceLessCatchRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BraceLessCatchRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessCatchRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # The catch-branch mutation is neither silently omitted nor authorized.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_braced_try_next_line_brace_less_catch_fails_closed(tmp_path, monkeypatch):
    """A braced ``= try { ... }`` body with a brace-less ``catch`` that starts
    on its own following line (``}`` then ``catch (...)``) cannot be bounded —
    the method fails closed with UNSUPPORTED_EXPRESSION_BODY and the
    catch-branch mutation is never silently omitted nor authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessCatchNextLineRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessCatchNextLineRepo.kt",
        """class BraceLessCatchNextLineRepo {
    fun doWork() = try {
        expenseDao.insert(e)
    }
    catch (ex: Exception)
        budgetDao.update(b)
}
""",
    )
    policy = [
        _entry(path, "BraceLessCatchNextLineRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BraceLessCatchNextLineRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessCatchNextLineRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # The catch-branch mutation is neither silently omitted nor authorized.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_braced_try_brace_less_finally_fails_closed(tmp_path, monkeypatch):
    """A braced ``= try { ... } catch { ... }`` body with a brace-less
    ``finally`` branch cannot be bounded reliably — the method fails closed
    with UNSUPPORTED_EXPRESSION_BODY instead of truncating the finally
    branch's mutation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessFinallyRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessFinallyRepo.kt",
        """class BraceLessFinallyRepo {
    fun doWork() = try {
        expenseDao.insert(e)
    } catch (ex: Exception) {
        budgetDao.update(b)
    } finally
        categoryDao.delete(c)
}
""",
    )
    policy = [
        _entry(path, "BraceLessFinallyRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BraceLessFinallyRepo", "doWork", ["budgetDao"], "update"),
        _entry(path, "BraceLessFinallyRepo", "doWork", ["categoryDao"], "delete"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessFinallyRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # The finally-branch mutation is neither silently omitted nor authorized.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_braced_try_next_line_brace_less_finally_fails_closed(tmp_path, monkeypatch):
    """A braced ``= try { ... } catch { ... }`` body with a brace-less
    ``finally`` that starts on its own following line (``}`` then ``finally``)
    cannot be bounded — the method fails closed with
    UNSUPPORTED_EXPRESSION_BODY and the finally-branch mutation is never
    silently omitted nor authorized."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BraceLessFinallyNextLineRepo.kt")
    _write_kt(
        src,
        "com/example/BraceLessFinallyNextLineRepo.kt",
        """class BraceLessFinallyNextLineRepo {
    fun doWork() = try {
        expenseDao.insert(e)
    } catch (ex: Exception) {
        budgetDao.update(b)
    }
    finally
        categoryDao.delete(c)
}
""",
    )
    policy = [
        _entry(path, "BraceLessFinallyNextLineRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BraceLessFinallyNextLineRepo", "doWork", ["budgetDao"], "update"),
        _entry(path, "BraceLessFinallyNextLineRepo", "doWork", ["categoryDao"], "delete"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_EXPRESSION_BODY" in v[3]]
    assert len(unsupported) == 1, violations
    assert "class=BraceLessFinallyNextLineRepo" in unsupported[0][3]
    assert "method=doWork" in unsupported[0][3]
    # The finally-branch mutation is neither silently omitted nor authorized.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


# ── 15g. Braced when expression bodies (positive contract) ────────────────────

def test_braced_when_expression_body_parsed_completely(tmp_path, monkeypatch):
    """A valid ``= when (x) { ... }`` expression body with DAO mutations in
    later branches is parsed to its complete boundary — the last branch is
    never silently truncated and an exact policy authorizes every mutation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BracedWhenRepo.kt")
    _write_kt(
        src,
        "com/example/BracedWhenRepo.kt",
        """class BracedWhenRepo {
    fun doWork(x: Int) = when (x) {
        1 -> expenseDao.insert(e)
        else -> budgetDao.update(b)
    }
}
""",
    )
    policy = [
        _entry(path, "BracedWhenRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "BracedWhenRepo", "doWork", ["budgetDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    assert violations == [], violations


def test_braced_when_expression_body_incorrect_policy_fails(tmp_path, monkeypatch):
    """A partial or wrong-operation policy must NOT authorize a braced
    ``= when`` expression body: the later-branch mutation is extracted and
    all-or-nothing authorization applies."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/BracedWhenRepo.kt")
    _write_kt(
        src,
        "com/example/BracedWhenRepo.kt",
        """class BracedWhenRepo {
    fun doWork(x: Int) = when (x) {
        1 -> expenseDao.insert(e)
        else -> budgetDao.update(b)
    }
}
""",
    )
    # Partial policy: the later (else) branch is uncovered and fails closed.
    partial = [_entry(path, "BracedWhenRepo", "doWork", ["expenseDao"], "insert")]
    violations, _ = scan(str(src), partial, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=update" in v[3] for v in violations
    ), violations
    # Wrong-operation policy: exact pair but wrong op can never authorize.
    wrong_op = [_entry(path, "BracedWhenRepo", "doWork", ["expenseDao"], "delete")]
    violations, _ = scan(str(src), wrong_op, [])
    assert any(
        "UNALLOWLISTED_CLASS" in v[3] and "op=insert" in v[3] for v in violations
    ), violations


# ── 16. Direct scan API fail-closed on invalid policy metadata ────────────────

def test_scan_api_rejects_structural_dot_star(tmp_path, monkeypatch):
    """A hand-built structural exception with method_pattern ``.*`` is dropped by
    scan() itself: the invalid entry emits the controlled DB_SCAN_INVALID_POLICY
    configuration error and is never used to approve a file operation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DirectApiRepo.kt")
    _write_kt(
        src,
        "com/example/DirectApiRepo.kt",
        """class DirectApiRepo {
    fun doBackup() {
        val db = getDatabase()
        db.execSQL("SELECT 1")
    }
}
""",
    )
    exceptions = [_sexc(path, "DirectApiRepo", ".*", "execSQL")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    # Configuration error: the malformed structural entry is rejected by scan()
    # itself with the controlled DB_SCAN_INVALID_POLICY diagnostic.
    config = [v for v in violations if "DB_SCAN_INVALID_POLICY" in v[3]]
    assert len(config) == 1, violations
    assert config[0][0] == "config:db_structural_exceptions"
    assert config[0][1] == 0
    assert config[0][3].startswith("ERROR: DB_SCAN_INVALID_POLICY: structural entry #1")
    assert "method_pattern" in config[0][3]
    # Never authorizes: the real file op still fails closed at its exact line
    # and the invalid exception approves nothing.
    forbidden = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(forbidden) == 1, violations
    assert forbidden[0][1] == 4, forbidden
    assert len(violations) == 2, violations


def test_scan_api_rejects_ownership_write_operation(tmp_path, monkeypatch):
    """A hand-built ownership entry with ``operation: write`` is dropped by
    scan() itself: the invalid entry emits the controlled DB_SCAN_INVALID_POLICY
    configuration error and never authorizes the mutation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DirectApiRepo.kt")
    _write_kt(
        src,
        "com/example/DirectApiRepo.kt",
        "class DirectApiRepo { fun doWork() { expenseDao.insert(e) } }",
    )
    policy = [_entry(path, "DirectApiRepo", "doWork", ["expenseDao"], "write")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    # Configuration error: the malformed ownership entry is rejected by scan()
    # itself with the controlled DB_SCAN_INVALID_POLICY diagnostic.
    config = [v for v in violations if "DB_SCAN_INVALID_POLICY" in v[3]]
    assert len(config) == 1, violations
    assert config[0][0] == "config:db_ownership_policy"
    assert config[0][1] == 0
    assert config[0][3].startswith("ERROR: DB_SCAN_INVALID_POLICY: ownership entry #1")
    assert "operation: write" in config[0][3]
    # Never authorizes: the mutation still fails closed with UNALLOWLISTED_CLASS
    # and exactly one file violation is reported.
    unallisted = [v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]
    assert len(unallisted) == 1, violations
    assert len(violations) == 2, violations


# ── 17. Masked file-operation detection & exact structural evidence ───────────
# File-operation detection (execSQL, openDatabase, writableDatabase, raw
# database operations) runs on the stateful comment/string mask of the WHOLE
# file (line comments, block comments, strings, triple-quoted strings, and char
# literals replaced by spaces with offsets/newlines preserved).  Fake operation
# text in comments/strings can never be detected as a file operation and can
# never satisfy structural operation evidence — evidence requires the EXACT
# call/token in the MASKED line, never a raw substring.

def test_scan_file_ops_in_comments_and_strings_not_detected(tmp_path, monkeypatch):
    """Fake ``execSQL(`` / ``openDatabase(`` / ``writableDatabase`` text inside
    line comments, trailing comments, block comments, strings, triple-quoted
    strings, and char literals is masked and never detected — with no
    structural exceptions the scan reports zero violations."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/FakeFileOps.kt")
    _write_kt(
        src,
        "com/example/FakeFileOps.kt",
        '''class FakeFileOps {
    fun doWork() {
        // execSQL("DROP TABLE fake")
        val a = 1 // openDatabase(fake)
        /* writableDatabase */
        val b = "execSQL(fake)"
        val c = """openDatabase(fake)"""
        val d = 'x'
        /* execSQL("DROP") */ val e = 1
    }
}
''',
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_multiline_block_comment_and_triple_string_file_ops_masked(tmp_path, monkeypatch):
    """File-op text inside MULTI-LINE block comments and triple-quoted strings
    is masked to its true end (stateful mask), so no intermediate line is ever
    detected — a real ``execSQL(`` after them is still detected at its exact
    line."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultilineFakeFileOps.kt")
    _write_kt(
        src,
        "com/example/MultilineFakeFileOps.kt",
        '''class MultilineFakeFileOps {
    fun doWork() {
        /*
        openDatabase("fake")
        writableDatabase
        */
        val t = """
        execSQL("fake")
        """
        db.execSQL("SELECT 1")
    }
}
''',
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    real = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(real) == 1, violations
    assert real[0][1] == 10, real


def test_structural_evidence_requires_exact_masked_operation(tmp_path, monkeypatch):
    """A structural exception's operation evidence is checked against the
    MASKED line as an exact call/token — a trailing comment containing the
    approved operation on the SAME line can never authorize a DIFFERENT real
    file operation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/ExactEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/ExactEvidenceRepo.kt",
        """class ExactEvidenceRepo {
    fun doBackup() {
        openDatabase("real.db") // execSQL(fake)
    }
}
""",
    )
    # The real op is openDatabase; the exception only covers execSQL.  The
    # trailing-comment "execSQL(fake)" must NOT satisfy the execSQL evidence
    # for the openDatabase call.
    exceptions = [_sexc(path, "ExactEvidenceRepo", r"doBackup", "execSQL")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert len(violations) == 1, violations
    assert "FORBIDDEN_FILE_OP" in violations[0][3]
    assert violations[0][1] == 3, violations


def test_structural_evidence_exact_token_not_substring_prefix(tmp_path, monkeypatch):
    """Structural evidence is an EXACT token — an exception for ``getDatabase``
    never satisfies evidence for a detected ``getDatabasePath(`` operation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/TokenEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/TokenEvidenceRepo.kt",
        """class TokenEvidenceRepo {
    fun doBackup() {
        val db = getDatabasePath("backup.db")
    }
}
""",
    )
    exceptions = [_sexc(path, "TokenEvidenceRepo", r"doBackup", "getDatabase")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    real = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(real) == 1, violations
    assert real[0][1] == 3, real


def test_real_file_ops_still_detected_and_authorized(tmp_path, monkeypatch):
    """Real file operations are still detected at their exact line and exact
    structural exceptions still authorize them via call/token evidence."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/RealFileOps.kt")
    _write_kt(
        src,
        "com/example/RealFileOps.kt",
        """class RealFileOps {
    fun exec() {
        db.execSQL("CREATE TABLE t(id INTEGER)")
    }
    fun open() {
        SQLiteDatabase.openDatabase(path, null, flags)
    }
    fun writable() {
        val db = helper.writableDatabase
    }
}
""",
    )
    exceptions = [
        _sexc(path, "RealFileOps", r"exec", "execSQL"),
        _sexc(path, "RealFileOps", r"open", "openDatabase"),
        _sexc(path, "RealFileOps", r"writable", "writableDatabase"),
    ]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations

    # Without the exceptions every real op is flagged at its exact line.
    violations, _ = scan(str(src), [], [])
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 3, violations
    assert [v[1] for v in flagged] == [3, 6, 9], flagged


def test_raw_prefix_structural_operation_authorizes_real_ops_only(tmp_path, monkeypatch):
    """``raw_``-prefixed structural operations remain catch-all: they approve
    every REAL masked file operation inside the approved class/method, while a
    real op in an unapproved method is still flagged."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/RawOpRepo.kt")
    _write_kt(
        src,
        "com/example/RawOpRepo.kt",
        """class RawOpRepo {
    fun rescue() {
        db.execSQL("VACUUM")
        val w = db.writableDatabase
    }
    fun other() {
        db.execSQL("DROP")
    }
}
""",
    )
    exceptions = [_sexc(path, "RawOpRepo", r"rescue", "raw_sqlite")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 1, violations
    assert flagged[0][1] == 7, flagged


# ── 17b. Multiline structural file operations ─────────────────────────────────
# Supported file operations are detected from the COMPLETE statefully masked
# file text, so a call may span line breaks (``db.execSQL\n("...")``,
# ``SQLiteDatabase.openDatabase\n(path, ...)``).  Each occurrence keeps the
# exact source line of its call-start token (the operation-name line).  When a
# supported operation token cannot be proven as an exact call, the scanner
# emits the controlled UNSUPPORTED_STRUCTURAL_OP violation (fail closed)
# instead of silently skipping it.

def test_scan_multiline_execSQL_call_detected_and_authorized(tmp_path, monkeypatch):
    """A real ``db.execSQL`` call split across lines is detected from the
    complete masked text and authorized by an exact structural exception; the
    call is attributed to the line of the operation-name token."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineExecSQLRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineExecSQLRepo.kt",
        """class MultiLineExecSQLRepo {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL
            ("CREATE TABLE t(id INTEGER)")
    }
}
""",
    )
    # Uncovered: the multiline call is still detected at its call-start line.
    violations, _ = scan(str(src), [], [])
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 1, violations
    assert flagged[0][1] == 3, flagged
    # Authorized by the exact structural exception for the enclosing method.
    exceptions = [_sexc(path, "MultiLineExecSQLRepo", r"migrate", "execSQL")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_multiline_openDatabase_call_detected_and_authorized(tmp_path, monkeypatch):
    """A real ``SQLiteDatabase.openDatabase`` call split across lines is
    detected and authorized; the call is attributed to the operation-name
    line, not the argument line."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineOpenRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineOpenRepo.kt",
        """class MultiLineOpenRepo {
    fun open() {
        SQLiteDatabase.openDatabase
            (path, null, SQLiteDatabase.OPEN_READONLY)
    }
}
""",
    )
    violations, _ = scan(str(src), [], [])
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 1, violations
    assert flagged[0][1] == 3, flagged
    exceptions = [_sexc(path, "MultiLineOpenRepo", r"open", "openDatabase")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_multiline_getDatabasePath_deleteRecursively_writable_detected(tmp_path, monkeypatch):
    """Multiline ``getDatabasePath`` / ``deleteRecursively`` calls and a
    line-broken ``.writableDatabase`` property access are each detected at
    their own call-start line and authorized by exact exceptions."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/MultiLineMixedRepo.kt")
    _write_kt(
        src,
        "com/example/MultiLineMixedRepo.kt",
        """class MultiLineMixedRepo {
    fun path() {
        val f = context.getDatabasePath
            (AppDatabase.DATABASE_NAME)
    }
    fun cleanup() {
        tempDir.deleteRecursively
            ()
    }
    fun writable() {
        val db = helper
            .writableDatabase
    }
}
""",
    )
    violations, _ = scan(str(src), [], [])
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 3, violations
    assert [v[1] for v in flagged] == [3, 7, 12], flagged
    exceptions = [
        _sexc(path, "MultiLineMixedRepo", r"path", "getDatabasePath"),
        _sexc(path, "MultiLineMixedRepo", r"cleanup", "deleteRecursively"),
        _sexc(path, "MultiLineMixedRepo", r"writable", "writableDatabase"),
    ]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_multiline_fake_calls_in_comments_and_strings_not_detected(tmp_path, monkeypatch):
    """File-op text that only exists in comments/strings — including fake
    MULTILINE calls split inside line comments, block comments, and
    triple-quoted strings — is masked and never detected."""
    src = _fixture_source(tmp_path, monkeypatch)
    _write_kt(
        src,
        "com/example/FakeMultiLineFileOps.kt",
        '''class FakeMultiLineFileOps {
    fun doWork() {
        // db.execSQL
        //     ("DROP TABLE fake")
        /* db.openDatabase
           (fake, null, 0) */
        val s = "SQLiteDatabase.openDatabase"
        val t = """
            db.execSQL
            ("fake")
        """
        val c = 'x'
    }
}
''',
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_unsupported_structural_operation_token_fails_closed(tmp_path, monkeypatch):
    """A supported operation identifier in code whose exact call form cannot
    be proven (``::getDatabasePath``, ``db.execSQL`` with no parens,
    ``db.openDatabase`` with no parens) fails closed with the controlled
    UNSUPPORTED_STRUCTURAL_OP violation — it is never silently skipped and
    never becomes a FORBIDDEN_FILE_OP.  A prefix-like identifier
    (``myExecSQL``) is not the exact token and is not reported."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/UnsupportedStructuralRepo.kt")
    _write_kt(
        src,
        "com/example/UnsupportedStructuralRepo.kt",
        """class UnsupportedStructuralRepo {
    fun doBackup() {
        val ref = ::getDatabasePath
        db.execSQL
        db.openDatabase
        val sql = myExecSQL
    }
}
""",
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_STRUCTURAL_OP" in v[3]]
    assert len(unsupported) == 3, violations
    assert [v[1] for v in unsupported] == [3, 4, 5], unsupported
    for v in unsupported:
        assert "rule=db_structural_exceptions" in v[3], v[3]
        assert "class=UnsupportedStructuralRepo" in v[3], v[3]
    # Prefix-like text is not the exact token and no FORBIDDEN_FILE_OP fires.
    assert len([v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]) == 0, violations
    assert len([v for v in violations if v[1] == 6]) == 0, violations


def test_scan_unsupported_structural_operation_not_authorized_by_raw(tmp_path, monkeypatch):
    """A raw_ exception cannot authorize an unparseable supported operation
    token — the real call in the same method is approved, the bare token
    fails closed with UNSUPPORTED_STRUCTURAL_OP."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/UnsupportedRawRepo.kt")
    _write_kt(
        src,
        "com/example/UnsupportedRawRepo.kt",
        """class UnsupportedRawRepo {
    fun rescue() {
        db.execSQL
        db.execSQL("VACUUM")
    }
}
""",
    )
    exceptions = [_sexc(path, "UnsupportedRawRepo", r"rescue", "raw_sqlite")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_STRUCTURAL_OP" in v[3]]
    assert len(unsupported) == 1, violations
    assert unsupported[0][1] == 3, unsupported
    assert len([v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]) == 0, violations


# ── 17c. Raw structural operation precision ───────────────────────────────────
# ``raw_`` exception categories are catch-all ONLY for provably-exact file
# operations.  Detection uses exact masked call syntax / token boundaries, so
# prefix-like identifiers (``getDatabasePathway``, ``openDatabaseHelper``,
# ``mywritableDatabase``) are never detected as the supported operations and a
# raw_ exception can never authorize them.

def test_scan_prefix_like_identifiers_not_detected_as_file_ops(tmp_path, monkeypatch):
    """Prefix-like identifiers are NOT supported file operations: with no
    structural exceptions they produce zero FORBIDDEN_FILE_OP violations
    (detection is exact token/call matching, never a raw substring)."""
    src = _fixture_source(tmp_path, monkeypatch)
    _write_kt(
        src,
        "com/example/PrefixFalsePositiveRepo.kt",
        """class PrefixFalsePositiveRepo {
    fun doWork() {
        val a = getDatabasePathway("backup.db")
        val b = openDatabaseHelper(path, null, 0)
        val c = mywritableDatabase
    }
}
""",
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    assert violations == [], violations


def test_scan_raw_exception_never_authorizes_prefix_like_text(tmp_path, monkeypatch):
    """A raw_ exception covering the method authorizes only real provable
    operations — prefix-like identifiers are never detected, never authorized,
    and a REAL op in an uncovered method is still flagged at its exact line."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/PrefixRawRepo.kt")
    _write_kt(
        src,
        "com/example/PrefixRawRepo.kt",
        """class PrefixRawRepo {
    fun rescue() {
        val a = getDatabasePathway("backup.db")
        val b = openDatabaseHelper(path, null, 0)
        val c = mywritableDatabase
        db.execSQL("VACUUM")
    }
    fun other() {
        db.execSQL("DROP")
    }
}
""",
    )
    exceptions = [_sexc(path, "PrefixRawRepo", r"rescue", "raw_sqlite")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    # The real op inside rescue is authorized; the real op in `other` is
    # flagged at its exact line; prefix-like identifiers are never reported
    # as file operations.
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 1, violations
    assert flagged[0][1] == 9, flagged
    assert len([v for v in violations if "UNSUPPORTED_STRUCTURAL_OP" in v[3]]) == 0, violations


def test_scan_raw_exception_authorizes_exact_real_operations(tmp_path, monkeypatch):
    """A raw_ exception approves the EXACT real supported operations in the
    approved method — getDatabasePath, openDatabase, writableDatabase,
    deleteRecursively, execSQL — each detected with exact token/call syntax."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/RawPositiveRepo.kt")
    _write_kt(
        src,
        "com/example/RawPositiveRepo.kt",
        """class RawPositiveRepo {
    fun rescue() {
        val dbFile = context.getDatabasePath("backup.db")
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, 0)
        val writable = db.writableDatabase
        tempDir.deleteRecursively()
        db.execSQL("VACUUM")
    }
}
""",
    )
    exceptions = [_sexc(path, "RawPositiveRepo", r"rescue", "raw_db_file")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations
    # Without the exception each real op is flagged at its exact line.
    violations, _ = scan(str(src), [], [])
    flagged = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(flagged) == 5, violations
    assert [v[1] for v in flagged] == [3, 4, 5, 6, 7], flagged


def test_scan_single_op_exception_requires_exact_operation(tmp_path, monkeypatch):
    """A non-raw structural exception authorizes ONLY the exact operation — a
    prefix-like sibling (getDatabasePathway) never satisfies the evidence for
    the real getDatabasePath call, which is still approved."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/SingleOpEvidenceRepo.kt")
    _write_kt(
        src,
        "com/example/SingleOpEvidenceRepo.kt",
        """class SingleOpEvidenceRepo {
    fun path() {
        val a = getDatabasePathway("x")
        val real = context.getDatabasePath("real.db")
    }
}
""",
    )
    exceptions = [_sexc(path, "SingleOpEvidenceRepo", r"path", "getDatabasePath")]
    violations, files_scanned = scan(str(src), [], exceptions)
    assert files_scanned == 1
    assert violations == [], violations


# ── 18. Direct-API config errors and unsupported DAO scopes ───────────────────

def test_scan_api_malformed_structural_entry_config_error_and_no_authorization(tmp_path, monkeypatch):
    """A malformed structural entry supplied directly to scan() emits the
    controlled DB_SCAN_INVALID_POLICY configuration error and is never used to
    authorize a file operation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DirectStructRepo.kt")
    _write_kt(
        src,
        "com/example/DirectStructRepo.kt",
        """class DirectStructRepo {
    fun doBackup() {
        val db = getDatabase()
        db.execSQL("SELECT 1")
    }
}
""",
    )
    malformed = _sexc(path, "DirectStructRepo", "doBackup", "execSQL")
    del malformed["class"]
    violations, files_scanned = scan(str(src), [], [malformed])
    assert files_scanned == 1
    # Configuration error: the malformed structural entry is rejected by scan()
    # itself with the controlled DB_SCAN_INVALID_POLICY diagnostic.
    config = [v for v in violations if "DB_SCAN_INVALID_POLICY" in v[3]]
    assert len(config) == 1, violations
    assert config[0][0] == "config:db_structural_exceptions"
    assert config[0][1] == 0
    assert "'class' must be a non-empty string" in config[0][3]
    # Never authorizes: the real file op still fails closed at its exact line.
    forbidden = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
    assert len(forbidden) == 1, violations
    assert forbidden[0][1] == 4, forbidden
    assert len(violations) == 2, violations


def test_scan_api_structural_missing_unknown_noncanonical_fail_closed(tmp_path, monkeypatch):
    """Missing structural required fields, unknown keys, and non-canonical
    paths each fail closed: every malformed entry emits a controlled
    DB_SCAN_INVALID_POLICY configuration error and can never authorize the real
    file operation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/StructuralVariantsRepo.kt")
    _write_kt(
        src,
        "com/example/StructuralVariantsRepo.kt",
        """class StructuralVariantsRepo {
    fun doBackup() {
        val db = getDatabase()
        db.execSQL("SELECT 1")
    }
}
""",
    )

    missing_operation = _sexc(path, "StructuralVariantsRepo", "doBackup", "execSQL")
    del missing_operation["operation"]

    unknown_key = _sexc(path, "StructuralVariantsRepo", "doBackup", "execSQL")
    unknown_key["method_patter"] = unknown_key.pop("method_pattern")

    noncanonical_path = _sexc(
        "StructuralVariantsRepo.kt", "StructuralVariantsRepo", "doBackup", "execSQL"
    )

    for label, malformed in (
        ("missing required field", missing_operation),
        ("unknown key", unknown_key),
        ("noncanonical path", noncanonical_path),
    ):
        violations, files_scanned = scan(str(src), [], [malformed])
        assert files_scanned == 1, label
        config = [v for v in violations if "DB_SCAN_INVALID_POLICY" in v[3]]
        assert len(config) == 1, f"{label}: {violations}"
        assert config[0][0] == "config:db_structural_exceptions"
        assert config[0][1] == 0
        assert config[0][3].startswith(
            "ERROR: DB_SCAN_INVALID_POLICY: structural entry #1"
        )
        # Never authorizes: the real file op is never approved by the malformed
        # entry and no other violation is reported.
        forbidden = [v for v in violations if "FORBIDDEN_FILE_OP" in v[3]]
        assert len(forbidden) == 1, f"{label}: {violations}"
        assert forbidden[0][1] == 4, f"{label}: {violations}"
        assert len(violations) == 2, f"{label}: {violations}"


def test_scan_api_malformed_ownership_entry_config_error_and_no_authorization(tmp_path, monkeypatch):
    """A malformed ownership entry supplied directly to scan() emits the
    controlled DB_SCAN_INVALID_POLICY configuration error and is never used to
    authorize the DAO mutation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/DirectOwnershipRepo.kt")
    _write_kt(
        src,
        "com/example/DirectOwnershipRepo.kt",
        "class DirectOwnershipRepo { fun doWork() { expenseDao.insert(e) } }",
    )
    malformed = _entry(path, "DirectOwnershipRepo", "doWork", ["expenseDao"], "insert")
    malformed["daoz"] = malformed.pop("daos")
    violations, files_scanned = scan(str(src), [malformed], [])
    assert files_scanned == 1
    # Configuration error: the malformed ownership entry is rejected by scan()
    # itself with the controlled DB_SCAN_INVALID_POLICY diagnostic.
    config = [v for v in violations if "DB_SCAN_INVALID_POLICY" in v[3]]
    assert len(config) == 1, violations
    assert config[0][0] == "config:db_ownership_policy"
    assert config[0][1] == 0
    assert config[0][3].startswith("ERROR: DB_SCAN_INVALID_POLICY: ownership entry #1")
    assert "unknown key" in config[0][3]
    # Never authorizes: the mutation still fails closed with UNALLOWLISTED_CLASS
    # and exactly one file violation is reported.
    unallisted = [v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]
    assert len(unallisted) == 1, violations
    assert len(violations) == 2, violations


def test_class_initializer_dao_mutation_emits_unsupported_dao_scope(tmp_path, monkeypatch):
    """A DAO mutation in a class initializer (property assignment or init
    block) has no (class, method) pair a policy entry could authorize — it
    fails closed with UNSUPPORTED_DAO_SCOPE and is never silently skipped."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/ClassInitRepo.kt")
    _write_kt(
        src,
        "com/example/ClassInitRepo.kt",
        """class ClassInitRepo {
    private val audit = expenseDao.insert(e)
    init {
        expenseDao.update(e)
    }
    fun doWork() {}
}
""",
    )
    # Even a policy entry that would cover the class/method pair cannot
    # authorize the class-initializer scope.
    policy = [
        _entry(path, "ClassInitRepo", "doWork", ["expenseDao"], "insert"),
        _entry(path, "ClassInitRepo", "doWork", ["expenseDao"], "update"),
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_DAO_SCOPE" in v[3]]
    assert len(unsupported) == 2, violations
    assert {v[1] for v in unsupported} == {2, 4}, unsupported
    assert any("op=insert" in v[3] for v in unsupported), violations
    assert any("op=update" in v[3] for v in unsupported), violations
    for v in unsupported:
        assert "scope=class-initializer" in v[3], v[3]
        assert "class=ClassInitRepo" in v[3], v[3]
        assert "dao=expenseDao" in v[3], v[3]
        assert "rule=db_ownership_policy" in v[3], v[3]
    # Never authorized and never silently skipped: no UNALLOWLISTED_CLASS.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_top_level_dao_mutation_emits_unsupported_dao_scope(tmp_path, monkeypatch):
    """A DAO mutation outside every type declaration (top-level code) has no
    enclosing class and fails closed with UNSUPPORTED_DAO_SCOPE
    (scope=top-level)."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/TopLevelDaoMutation.kt")
    _write_kt(
        src,
        "com/example/TopLevelDaoMutation.kt",
        """package com.example

val seeded = expenseDao.insert(e)
""",
    )
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_DAO_SCOPE" in v[3]]
    assert len(unsupported) == 1, violations
    assert unsupported[0][1] == 3, unsupported
    assert "scope=top-level" in unsupported[0][3], unsupported[0][3]
    assert "dao=expenseDao" in unsupported[0][3], unsupported[0][3]
    assert "op=insert" in unsupported[0][3], unsupported[0][3]
    assert "rule=db_ownership_policy" in unsupported[0][3], unsupported[0][3]
    # Never authorized and never silently skipped: no UNALLOWLISTED_CLASS.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_top_level_function_body_fails_closed(tmp_path, monkeypatch):
    """A DAO mutation inside a top-level function body is not resolvable to any
    approved (class, method) pair — even a covering policy cannot authorize it
    and it fails closed with UNSUPPORTED_DAO_SCOPE (scope=top-level)."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/TopLevelFunctionBody.kt")
    _write_kt(
        src,
        "com/example/TopLevelFunctionBody.kt",
        """package com.example

fun doBackup() {
    expenseDao.insert(e)
}
""",
    )
    # A policy entry that would cover the pair if it were inside an approved
    # class can never authorize top-level code.
    policy = [_entry(path, "SomeOtherClass", "doBackup", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_DAO_SCOPE" in v[3]]
    assert len(unsupported) == 1, violations
    assert unsupported[0][1] == 4, unsupported
    assert "scope=top-level" in unsupported[0][3], unsupported[0][3]
    assert "dao=expenseDao" in unsupported[0][3], unsupported[0][3]
    assert "op=insert" in unsupported[0][3], unsupported[0][3]
    assert "rule=db_ownership_policy" in unsupported[0][3], unsupported[0][3]
    # Fail closed: no UNALLOWLISTED_CLASS and no silent authorization.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_nested_inner_class_initializer_fails_closed(tmp_path, monkeypatch):
    """A DAO mutation in a nested inner class's own initializer has no
    (class, method) pair to authorize — it fails closed with
    UNSUPPORTED_DAO_SCOPE attributed to the INNER class, and the outer class is
    never double-reported."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/NestedInnerRepo.kt")
    _write_kt(
        src,
        "com/example/NestedInnerRepo.kt",
        """class OuterRepo {
    inner class InnerRepo {
        val audit = expenseDao.insert(e)
    }
    fun outerWork() {
        expenseDao.insert(o)
    }
}
""",
    )
    policy = [_entry(path, "OuterRepo", "outerWork", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_DAO_SCOPE" in v[3]]
    assert len(unsupported) == 1, violations
    assert unsupported[0][1] == 3, unsupported
    assert "scope=class-initializer" in unsupported[0][3], unsupported[0][3]
    assert "class=InnerRepo" in unsupported[0][3], unsupported[0][3]
    assert "dao=expenseDao" in unsupported[0][3], unsupported[0][3]
    assert "op=insert" in unsupported[0][3], unsupported[0][3]
    # The outer class's own method mutation is authorized and the inner
    # initializer is not double-reported under the outer class.
    assert len([v for v in violations if "class=OuterRepo" in v[3]]) == 0, violations
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations


def test_method_local_alias_referenced_at_class_body_fails_closed(tmp_path, monkeypatch):
    """A DAO alias declared inside one method and referenced at the class body
    is not in class scope and fails closed with UNSUPPORTED_DAO_SCOPE — the
    method-local binding can never authorize a class-body mutation."""
    src = _fixture_source(tmp_path, monkeypatch)
    path = _canonical("com/example/AliasClassScopeRepo.kt")
    _write_kt(
        src,
        "com/example/AliasClassScopeRepo.kt",
        """class AliasClassScopeRepo {
    fun methodA() {
        val dao = database.expenseDao()
        dao.insert(e)
    }
    val bad = dao.delete(b)
}
""",
    )
    # methodA's own mutation is authorized; the class-body use of the
    # method-local alias is not.
    policy = [_entry(path, "AliasClassScopeRepo", "methodA", ["expenseDao"], "insert")]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned == 1
    unsupported = [v for v in violations if "UNSUPPORTED_DAO_SCOPE" in v[3]]
    assert len(unsupported) == 1, violations
    assert unsupported[0][1] == 6, unsupported
    assert "scope=class-initializer" in unsupported[0][3], unsupported[0][3]
    assert "class=AliasClassScopeRepo" in unsupported[0][3], unsupported[0][3]
    assert "dao=expenseDao" in unsupported[0][3], unsupported[0][3]
    assert "op=delete" in unsupported[0][3], unsupported[0][3]
    # The valid method-A mutation is authorized: only the class-body scope
    # violation is reported.
    assert len([v for v in violations if "UNALLOWLISTED_CLASS" in v[3]]) == 0, violations

