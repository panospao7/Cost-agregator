"""
test_verify_privacy_boundaries.py
PR-GR-10B Slice 2 scope-contract tests for the privacy boundary guard
(G1-G14).

Contract under test:
  1. With the checked-in manifest declaring app/src/main/java, findings are
     identical to the pre-GR-10B hard-coded-root era.
  2. A missing manifest fails closed with exit 2 (no conventional-root
     fallback).
  3. Kotlin files outside the declared production roots are never scanned.

Run with: python -m pytest scripts/test_verify_privacy_boundaries.py -v
"""
import os
import sys
import pytest

# Import the module under test directly
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard_privacy",
    os.path.join(os.path.dirname(__file__), "verify_privacy_boundaries.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

# The guard's package-level semantic scope (a subtree of the declared roots).
MAIN_SRC = _mod.MAIN_SRC


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


_PRIVACY_VIOLATION_KT = (
    "package com.yourname.expensetracker.data.ai\n"
    "\n"
    "class BadAiClient(private val aiSettings: AiSettings) {\n"
    "    fun shouldRedact(): Boolean = aiSettings.redactBeforeCloud\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_privacy_boundaries.py", "--root", str(root)] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


# ── Test 1: declared root -> finding identical to hardcoded era ─────────────

def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, a cloud AI
    file reading AiSettings.redactBeforeCloud under the declared root is
    flagged (G1) exactly as in the pre-GR-10B hard-coded-root era."""
    _write_scope_manifest(tmp_path)
    _write_declared(
        tmp_path,
        MAIN_SRC + "/data/ai/BadAiClient.kt",
        _PRIVACY_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 1
    assert "[G1]" in out.out
    assert "BadAiClient" in out.out


# ── Test 2: missing manifest fails closed (exit 2) ──────────────────────────

def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_declared(
        tmp_path,
        MAIN_SRC + "/data/ai/BadAiClient.kt",
        _PRIVACY_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err


# ── Test 3: undeclared-root file is never scanned ───────────────────────────

def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible: a
    privacy violation under an undeclared tree produces no finding."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, MAIN_SRC + "/Clean.kt", _CLEAN_KT)
    undeclared = tmp_path / "other" / MAIN_SRC.replace("/", os.sep) / "data" / "ai" / "BadAiClient.kt"
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_PRIVACY_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "BadAiClient" not in out.out
    assert "[OK]" in out.out
