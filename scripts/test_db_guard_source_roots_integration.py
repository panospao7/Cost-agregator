"""Real-repo integration proof for GR-03 (PR-GR-03 Slice F).

Real-repository proof that the declared source-root contract works end to
end against THIS checkout:

  1. the checked-in manifest ``config/guards/production_source_roots.yml``
     loads with zero diagnostics;
  2. ``verify_topology`` cross-checks the declaration against the real
     ``settings.gradle.kts`` topology with zero diagnostics;
  3. ``build_room_inventory`` over the repository root resolves its roots
     through the checked-in manifest (manifest-backed resolution) and yields
     no ``DB_SOURCE_ROOT_*`` diagnostics;
  4. every discovered DAO/mutator canonical path lives under a declared
     root;
  5. ExpenseDao is discovered exactly once at its canonical path;
  6. two independent builds are semantically identical;
  7. the real DB CLI ``--inventory-only`` run is trusted and free of
     ``DB_SOURCE_ROOT_*`` codes.

Requires the checked-in manifest (the proof fails closed without it) and is
strictly READ-ONLY against the repository: no test writes inside the repo
tree; the only writes are pytest-managed temporary outputs produced by the
CLI subprocess proof.  No hard-coded DAO counts are asserted anywhere; only
the strict uniqueness of the ExpenseDao discovery is pinned.

Platform note (documented, never silently weakened): the CLI mutators dump
needs a confirmable directory durability barrier (``os.O_DIRECTORY``), which
CPython does not expose on Windows (see ``write_inventory_atomic`` in
``scripts/db_guard/room_inventory.py`` and the barrier seams in
``test_verify_db_access_v2.py``).  On capable platforms (e.g. Linux CI) the
full trusted exit-zero contract is asserted; on platforms without the
barrier the documented controlled fallback is asserted instead — the atomic
replace itself still succeeds, so both outputs still exist and the findings
still carry zero ``DB_SOURCE_ROOT_*`` codes.

Run:
    python -m pytest scripts/test_db_guard_source_roots_integration.py -v
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

# Repository root derived from this file's location: …/scripts/<this file>.
REPO_ROOT = Path(__file__).resolve().parents[1]

# sys.path bootstrap (sibling-test convention): every import below resolves
# through the repository-root namespace (``scripts.db_guard.*`` /
# ``scripts.ci.*``).
_REPO_ROOT_STR = str(REPO_ROOT)
if _REPO_ROOT_STR not in sys.path:
    sys.path.insert(0, _REPO_ROOT_STR)

from scripts.ci.verify_production_source_roots import verify_topology  # noqa: E402
from scripts.db_guard.room_inventory import build_room_inventory  # noqa: E402
from scripts.db_guard.source_roots import (  # noqa: E402
    is_declared_production_path,
    load_source_root_manifest,
)

MANIFEST_RELPATH = "config/guards/production_source_roots.yml"
MANIFEST_PATH = REPO_ROOT / MANIFEST_RELPATH

# Canonical ExpenseDao location pinned by this proof (verified on disk).
EXPENSE_DAO_RELPATH = (
    "app/src/main/java/com/yourname/expensetracker/data/database/dao/"
    "ExpenseDao.kt"
)

_DB_SOURCE_ROOT_PREFIX = "DB_SOURCE_ROOT_"

#: True only where ``write_inventory_atomic`` can confirm its directory
#: durability barrier (CPython exposes ``os.O_DIRECTORY`` on Unix, not on
#: Windows).
_HAS_DIRECTORY_BARRIER = hasattr(os, "O_DIRECTORY")


# ── Helpers ───────────────────────────────────────────────────────────────────


def _diag_code(diagnostic: str) -> str:
    """The controlled code prefix of an inventory diagnostic string.

    Inventory diagnostics are always a controlled code optionally followed
    by canonical source locations (``CODE:path[:line]``), so splitting on
    the first colon isolates the code without touching untrusted content.
    """
    return diagnostic.split(":", 1)[0]


def _source_root_diagnostics(diagnostics) -> list:
    """Inventory diagnostic strings whose code is a ``DB_SOURCE_ROOT_*``."""
    return [
        d
        for d in diagnostics
        if _diag_code(d).startswith(_DB_SOURCE_ROOT_PREFIX)
    ]


def _load_manifest_or_fail():
    """Load the checked-in manifest; fail closed when it is absent."""
    if not MANIFEST_PATH.is_file():
        pytest.fail(
            f"required checked-in manifest is missing: {MANIFEST_RELPATH}"
        )
    root_set, diagnostics = load_source_root_manifest(str(MANIFEST_PATH))
    assert diagnostics == ()
    assert root_set is not None
    return root_set


@pytest.fixture(scope="module")
def inventory():
    """The real-repository Room inventory (manifest-backed resolution)."""
    return build_room_inventory(REPO_ROOT)


# ── 1. Manifest load + real Gradle topology verification ─────────────────────


def test_manifest_loads_and_topology_verifies_against_real_repo():
    root_set = _load_manifest_or_fail()
    assert root_set.paths, "manifest must declare at least one root"
    diagnostics = verify_topology(str(REPO_ROOT), str(MANIFEST_PATH))
    assert diagnostics == ()


# ── 2. Real inventory carries no DB_SOURCE_ROOT_* diagnostics ────────────────


def test_room_inventory_from_repo_root_has_no_root_diagnostics(inventory):
    assert _source_root_diagnostics(inventory.diagnostics) == []


# ── 3. Every discovered DAO/mutator path lives under a declared root ─────────


def test_all_inventory_paths_under_declared_roots(inventory):
    root_set = _load_manifest_or_fail()
    assert inventory.daos, "real repository must discover DAO declarations"
    paths = [dao.canonical_path for dao in inventory.daos]
    paths.extend(
        mutator.method.split("::", 1)[0] for mutator in inventory.mutators
    )
    for path in paths:
        assert is_declared_production_path(root_set, path), path


# ── 4. ExpenseDao: exactly once, at its canonical repository path ────────────


def test_expense_dao_discovered_exactly_once_at_canonical_path(inventory):
    expense_daos = [
        dao for dao in inventory.daos if dao.fqcn.endswith("ExpenseDao")
    ]
    assert len(expense_daos) == 1
    discovered = expense_daos[0]
    # The discovered canonical path must exist on disk (derived from the
    # entry itself, never assumed).
    on_disk = REPO_ROOT.joinpath(*discovered.canonical_path.split("/"))
    assert on_disk.is_file()
    assert discovered.canonical_path == EXPENSE_DAO_RELPATH


# ── 5. Determinism across two independent builds ─────────────────────────────


def _semantic_content(inv):
    """A sorted, fully comparable semantic snapshot of an inventory."""
    return {
        "daos": tuple(sorted((d.fqcn, d.canonical_path) for d in inv.daos)),
        "methods": tuple(sorted(
            (
                m.dao.fqcn,
                m.dao.canonical_path,
                m.name,
                m.receiver or "",
                "|".join(m.parameters),
            )
            for m in inv.methods
        )),
        "mutators": tuple(sorted(
            (
                mu.method,
                mu.mutation_kind,
                mu.annotation,
                mu.query_kind or "",
                mu.inherited_from or "",
                mu.source_location,
            )
            for mu in inv.mutators
        )),
        "diagnostics": tuple(inv.diagnostics),
    }


def test_inventory_deterministic_across_two_runs():
    first = build_room_inventory(REPO_ROOT)
    second = build_room_inventory(REPO_ROOT)
    # Frozen-dataclass equality (the module's own notion of equality)…
    assert first == second
    # …and an explicit sorted canonical-content comparison.
    assert _semantic_content(first) == _semantic_content(second)


# ── 6. Real CLI: --inventory-only trusted run, no root diagnostics ───────────


def test_inventory_only_cli_trusted_exit_zero(tmp_path):
    findings_output = tmp_path / "room-inventory.findings.json"
    mutators_output = tmp_path / "room-mutators.json"
    result = subprocess.run(
        [
            sys.executable,
            "scripts/verify_db_access_boundaries.py",
            "--inventory-only",
            "--findings-output", str(findings_output),
            "--dump-room-mutators", str(mutators_output),
        ],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
    )
    combined = result.stdout + result.stderr

    # Both declared outputs must exist regardless of platform barrier
    # support (the mutators dump is atomically replaced before the
    # durability check runs).
    assert findings_output.is_file(), combined
    assert mutators_output.is_file(), combined

    report = json.loads(findings_output.read_text(encoding="utf-8"))
    codes = [
        entry.get("code")
        for entry in report.get("diagnostics", [])
        if isinstance(entry, dict)
    ]
    assert not any(
        isinstance(code, str) and code.startswith(_DB_SOURCE_ROOT_PREFIX)
        for code in codes
    ), codes

    if _HAS_DIRECTORY_BARRIER:
        # Full trusted contract (Linux CI and other capable platforms).
        assert result.returncode == 0, combined
        assert codes == []
        assert report.get("statistics", {}).get("trusted") is True
    else:
        # Documented controlled fallback on platforms without a confirmable
        # directory durability barrier (Windows): the dump itself succeeded
        # (atomic replace precedes the barrier check) and the CLI reports
        # exactly the single controlled INVENTORY_DURABILITY_UNCONFIRMED
        # diagnostic with an untrusted report.
        assert result.returncode == 2, combined
        assert codes == ["INVENTORY_DURABILITY_UNCONFIRMED"], codes
        assert report.get("statistics", {}).get("trusted") is False
