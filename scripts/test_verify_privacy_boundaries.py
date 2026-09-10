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
  4. A privacy finding under the expensetracker package of a SECOND
     declared production root IS detected (no enumeration blind spot).
  5. A non-privacy package under a declared root is still not flagged
     (the package-subtree semantic filter survives the enumeration fix).
  6. A fail-closed enumeration error exits 2 — never a partial scan
     reported as a pass.

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


def _write_multi_root_manifest(root, roots=((":app", "app/src/main/java"),
                                           (":feature", "feature/src/main/java"))):
    """Write a manifest declaring several production roots (canonical
    (module, path) order, as the manifest validator requires)."""
    manifest = root / "config" / "guards" / "production_source_roots.yml"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    lines = ["schemaVersion: 1", "roots:"]
    for module, rel in sorted(roots):
        lines.append(f"  - module: '{module}'")
        lines.append("    sourceSet: main")
        lines.append(f"    path: {rel}")
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")


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


# ── Test 4: second declared root — privacy finding IS detected ───────────────

def test_second_declared_root_privacy_finding_is_detected(tmp_path, monkeypatch, capsys):
    """Blind-spot killer: a privacy violation (G1/G2) under the
    expensetracker package of a SECOND declared production root IS
    detected.  The guard enumerates every declared root through the
    neutral module and applies the package-subtree filter per root; the
    pre-fix private os.walk over the ':app' package subtree silently
    missed this finding (false pass, exit 0)."""
    _write_multi_root_manifest(tmp_path)
    _write_declared(tmp_path, MAIN_SRC + "/data/ai/CleanAppClient.kt", _CLEAN_KT)
    _write_declared(
        tmp_path,
        "feature/src/main/java/com/yourname/expensetracker/data/ai"
        "/BadFeatureAiClient.kt",
        _PRIVACY_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 1
    assert "[G1]" in out.out
    assert "BadFeatureAiClient" in out.out


# ── Test 5: non-privacy package under a declared root is not flagged ─────────

_G12_PROBE_KT = (
    "package com.other\n"
    "\n"
    "class PromptProbe(private val payloadPolicy: CloudPayloadPolicy) {\n"
    "    fun probe(prompt: String): String = payloadPolicy.prepareText(prompt, \"\")\n"
    "}\n"
)


def test_non_privacy_package_under_declared_root_is_not_flagged(tmp_path, monkeypatch, capsys):
    """Negative half of the blind-spot fix: a G12 empty-prompt probe under a
    NON-expensetracker package of a declared production root is still not
    flagged — the enumeration fix widens the scan to every declared root
    while the guard's package-subtree semantic filter keeps non-privacy
    packages out of scope."""
    _write_multi_root_manifest(tmp_path)
    _write_declared(tmp_path, MAIN_SRC + "/Clean.kt", _CLEAN_KT)
    _write_declared(
        tmp_path,
        "feature/src/main/java/com/other/PromptProbe.kt",
        _G12_PROBE_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "PromptProbe" not in out.out
    assert "[OK]" in out.out


# ── Test 6: fail-closed enumeration error exits 2 ────────────────────────────

def test_enumeration_failure_fails_closed(tmp_path, monkeypatch, capsys):
    """A fail-closed enumeration error (unreadable tree / symlink escape)
    must exit 2 — never a partial scan reported as a pass."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, MAIN_SRC + "/Clean.kt", _CLEAN_KT)

    def _raise_scope_error(repo_root, root_set):
        raise _mod.ProductionSourceScopeError(
            _mod.PRODUCTION_SOURCE_SCOPE_UNREADABLE
        )

    monkeypatch.setattr(_mod, "iter_production_kotlin_files", _raise_scope_error)

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source enumeration failed" in out.err
    assert "DB_SOURCE_ROOT_UNREADABLE" in out.err
