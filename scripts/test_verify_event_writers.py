"""
test_verify_event_writers.py
PR-GR-10B Slice 2 scope-contract tests for the durable diagnostics /
lifecycle event writer guard.

Contract under test:
  1. With the checked-in manifest declaring app/src/main/java, findings are
     identical to the pre-GR-10B hard-coded-root era.
  2. A missing manifest fails closed with exit 2 (no conventional-root
     fallback).
  3. Kotlin files outside the declared production roots are never scanned.
  4. An unreadable subdirectory inside a declared root fails closed with
     exit 2 — never a partial scan reported as a pass.
  5. A symlinked .kt entry inside a declared root fails closed with
     exit 2 (skipped where symlink creation is not permitted).
  6. Violation reporting is deterministic (canonical path order) and all
     violating files under a declared root are flagged regardless of
     directory-creation order (scan-surface identity).

Run with: python -m pytest scripts/test_verify_event_writers.py -v
"""
import os
import sys
import pytest

# Import the module under test directly
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard_event_writers",
    os.path.join(os.path.dirname(__file__), "verify_event_writers.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_scope_manifest(root, root_rel="app/src/main/java"):
    manifest = root / "config" / "guards" / "production_source_roots.yml"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        "schemaVersion: 1\n"
        "roots:\n"
        "  - module: ':app'\n"
        "    sourceSet: main\n"
        f"    path: {root_rel}\n",
        encoding="utf-8",
    )


def _write_declared(root, rel, content):
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


_EVENT_VIOLATION_KT = (
    "package com.example\n"
    "\n"
    "class EventViolator {\n"
    "    fun record() {\n"
    "        val event = TransactionEvent(id = \"1\")\n"
    "    }\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_event_writers.py", "--root", str(root)] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


def _make_file_symlink(link_abs, target_abs):
    """Create a file symlink; return False when unsupported."""
    try:
        os.symlink(target_abs, link_abs)
    except (OSError, NotImplementedError):
        return False
    return True


# ── Test 1: declared root -> finding identical to hardcoded era ─────────────

def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, direct
    TransactionEvent( construction outside the designated writers under the
    declared root is flagged exactly as in the pre-GR-10B hard-coded-root
    era."""
    _write_scope_manifest(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/EventViolator.kt",
        _EVENT_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "TransactionEvent" in out.out
    assert "EventViolator.kt" in out.out


# ── Test 2: missing manifest fails closed (exit 2) ──────────────────────────

def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/EventViolator.kt",
        _EVENT_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err


# ── Test 3: undeclared-root file is never scanned ───────────────────────────

def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible: an
    event construction under an undeclared tree produces no finding."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "EventViolator.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_EVENT_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "EventViolator" not in out.out
    assert "No violations found." in out.out


# ── Test 4: unreadable subdir inside a declared root -> exit 2 ───────────────

def test_unreadable_subdir_fails_closed_exit_2(tmp_path, monkeypatch, capsys):
    """A declared production root containing an unlistable subdirectory must
    exit 2 (infrastructure error).  The pre-fix per-root
    os.walk(onerror=None) silently skipped unreadable subdirs and reported
    the partial scan as a pass (exit 0)."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    sealed_dir = (
        tmp_path / "app" / "src" / "main" / "java" / "com" / "example" / "sealed"
    )
    sealed_dir.mkdir(parents=True, exist_ok=True)

    real_listdir = os.listdir

    def fake_listdir(path):
        if os.path.abspath(str(path)) == os.path.abspath(str(sealed_dir)):
            raise PermissionError(13, "denied")
        return real_listdir(path)

    monkeypatch.setattr(os, "listdir", fake_listdir)

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source enumeration failed" in out.err
    assert "DB_SOURCE_ROOT_UNREADABLE" in out.err


# ── Test 5: symlinked .kt entry inside a declared root -> exit 2 ─────────────

def test_symlinked_kt_entry_fails_closed_exit_2(tmp_path, monkeypatch, capsys):
    """A symlinked .kt file inside a declared production root must exit 2
    (fail closed): the neutral enumerator refuses symlinked entries rather
    than traversing them non-deterministically."""
    outside = tmp_path / "outside"
    outside.mkdir()
    escaped = outside / "Escaped.kt"
    escaped.write_text("// kt\n", encoding="utf-8")

    repo = tmp_path / "repo"
    _write_scope_manifest(repo)
    _write_declared(repo, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    java_dir = repo / "app" / "src" / "main" / "java"
    if not _make_file_symlink(str(java_dir / "Escape.kt"), str(escaped)):
        pytest.skip("symlink creation not permitted on this platform")

    code, out = _scope_run_main(repo, monkeypatch, capsys)

    assert code == 2
    assert "production source enumeration failed" in out.err
    assert "DB_SOURCE_ROOT_SYMLINK_OUTSIDE" in out.err


# ── Test 6: deterministic canonical-order identity on the fixture tree ──────

def test_violation_reporting_is_deterministic_canonical_order(tmp_path, monkeypatch, capsys):
    """Scan-surface identity: every violating file under the declared root
    is flagged regardless of directory-creation order, and the report order
    is the neutral module's canonical path order (stable across runs)."""
    _write_scope_manifest(tmp_path)
    # Create the z-prefixed directory first so filesystem creation order
    # differs from canonical path order.
    _write_declared(
        tmp_path, "app/src/main/java/com/example/zeta/LateViolator.kt",
        _EVENT_VIOLATION_KT,
    )
    _write_declared(
        tmp_path, "app/src/main/java/com/example/alpha/EarlyViolator.kt",
        _EVENT_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "EarlyViolator.kt" in out.out
    assert "LateViolator.kt" in out.out
    assert out.out.index("EarlyViolator.kt") < out.out.index("LateViolator.kt")

    code2, out2 = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])
    assert code2 == 1
    assert out2.out == out.out


# ── I-04-4: exemptions are per-rule and keyed on the exact repo-relative path ─

import json
import types

_PKG = "app/src/main/java/com/yourname/expensetracker/"
_TX_WRITER_REL = _PKG + "domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt"
_OP_RECORDER_REL = _PKG + "domain/diagnostics/OperationRunRecorder.kt"
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _fake_source(root, rel):
    return types.SimpleNamespace(
        absolute_path=str(root / rel), repository_relative_path=rel
    )


def _write_script_dir(tmp_path, allowlist_text):
    script_dir = tmp_path / "scripts_fixture"
    script_dir.mkdir()
    (script_dir / "event_writer_allowlist.txt").write_text(
        allowlist_text, encoding="utf-8"
    )
    return script_dir


def test_same_basename_in_another_directory_is_flagged(tmp_path, monkeypatch, capsys):
    """The designated writer's basename in a different directory is not exempt;
    the real path is."""
    _write_scope_manifest(tmp_path)
    wrong_rel = "app/src/main/java/com/example/TransactionLifecycleEventWriter.kt"
    _write_declared(tmp_path, wrong_rel, _EVENT_VIOLATION_KT)
    _write_declared(tmp_path, _TX_WRITER_REL, _EVENT_VIOLATION_KT)

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert wrong_rel in out.out.replace("\\", "/")
    assert _TX_WRITER_REL not in out.out.replace("\\", "/")


def test_allowlisted_writer_violating_a_different_rule_is_flagged(tmp_path, monkeypatch, capsys):
    """The designated TransactionEvent writer is exempt for TransactionEvent
    only; constructing a ReceiptEvent there is still a violation."""
    _write_scope_manifest(tmp_path)
    _write_declared(
        tmp_path,
        _TX_WRITER_REL,
        "package com.example\n"
        "class W {\n"
        "    fun a() = TransactionEvent(id = \"1\")\n"
        "    fun b() = ReceiptEvent(id = \"2\")\n"
        "}\n",
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "[ENTITY] ReceiptEvent" in out.out
    assert "[ENTITY] TransactionEvent" not in out.out


def test_allowlist_row_exempts_only_its_rule_and_its_exact_file(tmp_path):
    rel = "app/src/main/java/com/example/Legit.kt"
    other = "app/src/main/java/com/example/sub/Legit.kt"
    body = "class L {\n    fun a() = TransactionEvent(id = 1)\n    fun b() = ReceiptEvent(id = 2)\n}\n"
    for path in (rel, other):
        _write_declared(tmp_path, path, body)
    allowlist = {"TransactionEvent": {rel}}

    violations = _mod.scan(
        [_fake_source(tmp_path, rel), _fake_source(tmp_path, other)], allowlist
    )

    found = sorted((v["rule"], os.path.relpath(v["file"], tmp_path).replace("\\", "/")) for v in violations)
    assert found == sorted(
        [("ReceiptEvent", rel), ("ReceiptEvent", other), ("TransactionEvent", other)]
    )


def test_fake_operation_run_recorder_is_not_exempt(tmp_path, monkeypatch, capsys):
    """The old substring match exempted any basename containing
    OperationRunRecorder.kt; a look-alike must now be flagged."""
    _write_scope_manifest(tmp_path)
    fake_rel = _PKG + "domain/diagnostics/FakeOperationRunRecorder.kt"
    _write_declared(
        tmp_path,
        fake_rel,
        "package com.example\nclass FakeOperationRunRecorder {\n"
        "    fun r() = OperationRun(id = 1)\n}\n",
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "[ENTITY] OperationRun" in out.out
    assert "FakeOperationRunRecorder.kt" in out.out


def test_real_operation_recorder_path_is_still_exempt(tmp_path, monkeypatch, capsys):
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, _OP_RECORDER_REL, "class R { fun r() = OperationRun(id = 1) }\n")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 0, out.out


def test_real_database_and_entity_paths_are_exempt():
    assert _mod.is_exempt("x", _PKG + "data/database/AppDatabase.kt")
    assert _mod.is_exempt("x", _PKG + "data/database/entity/Foo.kt")


@pytest.mark.parametrize(
    "rel",
    [
        _PKG + "data/database/FakeAppDatabase.kt",
        _PKG + "other/AppDatabase.kt",
        _PKG + "other/fooentity/Foo.kt",
        _PKG + "other/entity/Foo.kt",
        _PKG + "data/database/entityextra/Foo.kt",
    ],
)
def test_lookalike_database_and_entity_paths_are_not_exempt(rel):
    assert not _mod.is_exempt("x", rel)


@pytest.mark.parametrize(
    "row, code",
    [
        ("TransactionLifecycleEventWriter.kt # legacy basename-only row\n", "LEGACY_OR_MALFORMED"),
        ("TransactionLifecycleEventWriter.kt\n", "MISSING_REASON"),
        ("NoSuchRule " + _TX_WRITER_REL + " # unknown rule name\n", "UNKNOWN_RULE"),
        ("TransactionEvent TransactionLifecycleEventWriter.kt # basename as path\n", "BAD_PATH"),
        ("TransactionEvent app\\src\\X.kt # backslash path form\n", "BAD_PATH"),
        ("TransactionEvent app/src/**/X.kt # glob path form\n", "BAD_PATH"),
        ("TransactionEvent ../app/src/X.kt # dot-dot path form\n", "BAD_PATH"),
        ("TransactionEvent " + _TX_WRITER_REL + "\n", "MISSING_REASON"),
        (
            "TransactionEvent " + _TX_WRITER_REL + " # first\n"
            "TransactionEvent " + _TX_WRITER_REL + " # again\n",
            "DUPLICATE",
        ),
    ],
)
def test_legacy_or_malformed_allowlist_row_is_rejected(tmp_path, row, code):
    script_dir = _write_script_dir(tmp_path, row)

    with pytest.raises(_mod.AllowlistError) as excinfo:
        _mod.load_allowlist(str(script_dir))

    assert excinfo.value.code == "EVENT_WRITER_ALLOWLIST_" + code


def test_legacy_allowlist_line_exits_2(tmp_path, monkeypatch, capsys):
    root = tmp_path / "repo"
    root.mkdir()
    _write_scope_manifest(root)
    _write_declared(root, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    script_dir = _write_script_dir(
        tmp_path, "DiagnosticEventWriter.kt # legacy basename-only row\n"
    )
    monkeypatch.setitem(
        _mod.__dict__, "__file__", str(script_dir / "verify_event_writers.py")
    )

    code, out = _scope_run_main(root, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 2
    assert "EVENT_WRITER_ALLOWLIST_LEGACY_OR_MALFORMED" in out.err
    assert "DiagnosticEventWriter" not in out.err


def test_real_allowlist_parses_and_every_row_is_a_known_rule_and_existing_file():
    allowlist = _mod.load_allowlist(os.path.dirname(os.path.abspath(_mod.__file__)))
    assert allowlist, "real allowlist must not be empty"
    for rule, rows in allowlist.items():
        assert rule in _mod.KNOWN_RULE_NAMES
        for rel in rows:
            assert os.path.isfile(os.path.join(_REPO_ROOT, *rel.split("/"))), rel


def test_real_tree_findings_do_not_exceed_baseline():
    """The real tree passes under the strict guard: every finding is already in
    the checked-in baseline (12 fingerprints) and no count grows."""
    root_set, diagnostics = _mod.resolve_production_source_scope(_REPO_ROOT)
    assert root_set is not None, diagnostics
    files = list(_mod.iter_production_kotlin_files(_REPO_ROOT, root_set))
    allowlist = _mod.load_allowlist(os.path.dirname(os.path.abspath(_mod.__file__)))
    violations = _mod.scan(files, allowlist)

    counts = {}
    for v in violations:
        rel = os.path.relpath(v["file"], _REPO_ROOT).replace("\\", "/")
        key = f"{v['type'].upper()}:{v['rule']} {rel}"
        counts[key] = counts.get(key, 0) + 1

    with open(
        os.path.join(_REPO_ROOT, "config", "baselines", "event_writers.json"),
        encoding="utf-8",
    ) as handle:
        baseline = json.load(handle)
    assert len(baseline["fingerprints"]) == 12
    assert set(counts) <= set(baseline["fingerprints"]), sorted(
        set(counts) - set(baseline["fingerprints"])
    )
    for key, count in counts.items():
        assert count <= baseline["occurrence_counts"][key], key
