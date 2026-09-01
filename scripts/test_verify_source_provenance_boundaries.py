"""
test_verify_source_provenance_boundaries.py
PR-GR-10B Slice 2 scope-contract tests for the static source provenance
boundary guard (G-PROV-01..05).

Contract under test:
  1. With the checked-in manifest declaring app/src/main/java, findings are
     identical to the pre-GR-10B hard-coded-root era.
  2. A missing manifest fails closed with exit 2 (no conventional-root
     fallback).
  3. Kotlin files outside the declared production roots are never scanned.

Run with: python -m pytest scripts/test_verify_source_provenance_boundaries.py -v
"""
import os
import sys
import pytest

# Import the module under test directly
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard_srcprov",
    os.path.join(os.path.dirname(__file__), "verify_source_provenance_boundaries.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

# The guard's declared targeted paths (imported, not duplicated, so the
# identity test tracks the guard's actual targets).
_TARGET_PROVENANCE_DIR = _mod._TARGET_PROVENANCE_DIR
_TARGET_COORDINATOR = _mod._TARGET_COORDINATOR
_TARGET_SOURCE_LINK_WRITER = _mod._TARGET_SOURCE_LINK_WRITER

_EXPECTED_EXPENSE_SOURCES = sorted(_mod.EXPECTED_EXPENSE_SOURCES)


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


def _write_required_targets(root):
    """Write the guard's three required targets so the missing-files check
    passes and the scan actually runs (identity/undeclared tests)."""
    # Provenance package directory (Check-3 scans it by name; empty is fine).
    (root / _TARGET_PROVENANCE_DIR).mkdir(parents=True, exist_ok=True)
    # Coordinator: must contain a source = when(request.source) block that
    # handles every EXPECTED_EXPENSE_SOURCES value (Check-4).
    coordinator_lines = [
        "package com.yourname.expensetracker.domain.transaction.lifecycle",
        "",
        "fun mapSource(request: CreateRequest): ExpenseSource {",
        "    val source = when (request.source) {",
    ]
    for value in _EXPECTED_EXPENSE_SOURCES:
        coordinator_lines.append(f"        ExpenseSource.{value} -> handled")
    coordinator_lines.extend([
        "    }",
        "    return source",
        "}",
        "",
    ])
    _write_declared(root, _TARGET_COORDINATOR, "\n".join(coordinator_lines))
    # SourceLinkWriterImpl: the `else ->` branch satisfies Check-2 for every
    # SourceEntityType value; the file is on the Check-5 allowed list.
    writer_lines = [
        "package com.yourname.expensetracker.domain.provenance",
        "",
        "fun fallbackKey(type: SourceEntityType): String = when (type) {",
        "    SourceEntityType.UNKNOWN -> \"unknown\"",
        "    else -> \"typed\"",
        "}",
        "",
    ]
    _write_declared(root, _TARGET_SOURCE_LINK_WRITER, "\n".join(writer_lines))


_PROV_VIOLATION_KT = (
    "package com.example.probe\n"
    "\n"
    "class RawMetadataProbe {\n"
    "    fun build(payload: String) {\n"
    "        metadataMap[\"rawText\"] = payload\n"
    "    }\n"
    "}\n"
)

_ENTITY_LINK_VIOLATION_KT = (
    "package com.example.probe\n"
    "\n"
    "class InlineLinkFactory {\n"
    "    fun make(id: String): EntitySourceLink {\n"
    "        return EntitySourceLink(entityId = id)\n"
    "    }\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_source_provenance_boundaries.py", "--root", str(root)] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


# ── Test 1: declared root -> finding identical to hardcoded era ─────────────

def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, a raw
    sensitive metadataMap key under the declared root is flagged (G-PROV-01)
    exactly as in the pre-GR-10B hard-coded-root era."""
    _write_scope_manifest(tmp_path)
    _write_required_targets(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/probe/RawMetadataProbe.kt",
        _PROV_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 1
    assert "G-PROV-01" in out.out


# ── Test 2: missing manifest fails closed (exit 2) ──────────────────────────

def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_required_targets(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/probe/RawMetadataProbe.kt",
        _PROV_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert out.out == ""
    assert "production source scope unresolved" in out.err


# ── Test 3: undeclared-root file is never scanned ───────────────────────────

def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible: an
    EntitySourceLink( constructor under an undeclared tree produces no
    finding."""
    _write_scope_manifest(tmp_path)
    _write_required_targets(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "probe" / "InlineLinkFactory.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_ENTITY_LINK_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "InlineLinkFactory" not in out.out
    assert "PASS" in out.out
