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
