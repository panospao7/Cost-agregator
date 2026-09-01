#!/usr/bin/env python3
"""
test_gr10b_source_scope_matrix.py -- GR-10B Slice 3 matrix-consistency gate.

Parses ``docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md`` and proves it stays in
lockstep with ``scripts/ci/guard_registry.py``:

  1. every registry guard id has exactly one matrix row and no stale rows
     exist (exact set equality in both directions);
  2. each row's scope classification equals the registry ``sourceScope``
     value for that guard;
  3. scope values stay inside the closed ``SOURCE_SCOPE_VALUES`` vocabulary;
  4. migration status stays inside the closed ``S2-MIGRATED`` /
     ``DB-SEAM-DEFERRED`` / ``N/A`` vocabulary, with production-Kotlin
     scopes required to carry ``S2-MIGRATED`` or ``DB-SEAM-DEFERRED`` and
     non-production scopes required to carry ``N/A``;
  5. no matrix cell carries an implicit scope (the word "unknown" never
     appears in any classification cell).

Run:
    python -m pytest scripts/ci/test_gr10b_source_scope_matrix.py -v
"""

from __future__ import annotations

import os
import re
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(_SCRIPT_DIR))
for _path in (_SCRIPT_DIR, _PROJECT_ROOT):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from guard_registry import GUARD_REGISTRY, SOURCE_SCOPE_VALUES  # noqa: E402

_MATRIX_REL_PATH = os.path.join("docs", "ci", "GR-10B_SOURCE_SCOPE_MATRIX.md")

#: Closed migration-status vocabulary (GR-10B Slice 3 deliverable).
MIGRATION_STATUS_VALUES = frozenset({"S2-MIGRATED", "DB-SEAM-DEFERRED", "N/A"})

#: Scope classifications that consume the production source-root manifest.
_PRODUCTION_SCOPES = frozenset(
    {
        "production-kotlin-all",
        "production-kotlin-filtered",
        "production-kotlin-targeted",
    }
)

_ROW_COLUMN_COUNT = 6
_SEPARATOR_CELL_RE = re.compile(r":?-{3,}:?")


def _matrix_path():
    # type: () -> str
    return os.path.join(_PROJECT_ROOT, _MATRIX_REL_PATH)


def _parse_matrix_rows(text):
    # type: (str) -> list
    """Parse the single matrix table into ``(guard_id, scope, surface,
    authority, status, contract)`` tuples.

    Skips the header row and the dashes separator row; raises AssertionError
    on any row whose column count is not ``_ROW_COLUMN_COUNT`` so a malformed
    edit cannot silently shrink the contract.
    """
    rows = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if cells and all(_SEPARATOR_CELL_RE.fullmatch(cell) for cell in cells):
            continue  # separator row
        if cells and cells[0] == "Guard ID":
            continue  # header row
        assert len(cells) == _ROW_COLUMN_COUNT, (
            "matrix row must have {0} columns: {1}".format(
                _ROW_COLUMN_COUNT, line
            )
        )
        rows.append(tuple(cells))
    return rows


def _load_rows():
    # type: () -> list
    with open(_matrix_path(), "r", encoding="utf-8") as handle:
        text = handle.read()
    rows = _parse_matrix_rows(text)
    assert rows, "matrix table is empty"
    return rows


def test_matrix_rows_cover_exactly_the_registry():
    rows = _load_rows()
    by_id = {}
    for guard_id, scope, surface, authority, status, contract in rows:
        assert guard_id not in by_id, "duplicate matrix row: {0}".format(guard_id)
        assert surface, guard_id
        assert authority, guard_id
        assert contract, guard_id
        by_id[guard_id] = (scope, status)

    assert set(by_id) == set(GUARD_REGISTRY), (
        "matrix/registry guard-id drift: missing={0} stale={1}".format(
            sorted(set(GUARD_REGISTRY) - set(by_id)),
            sorted(set(by_id) - set(GUARD_REGISTRY)),
        )
    )


def test_matrix_scopes_match_registry_and_vocabulary():
    rows = _load_rows()
    for guard_id, scope, _surface, _authority, _status, _contract in rows:
        assert guard_id in GUARD_REGISTRY, guard_id
        registry_scope = GUARD_REGISTRY[guard_id].get("sourceScope")
        assert registry_scope is not None, (
            "{0}: registry entry lost its sourceScope".format(guard_id)
        )
        assert scope == registry_scope, (
            "{0}: matrix scope {1!r} != registry sourceScope {2!r}".format(
                guard_id, scope, registry_scope
            )
        )
        assert scope in SOURCE_SCOPE_VALUES, (
            "{0}: scope outside closed vocabulary: {1!r}".format(guard_id, scope)
        )


def test_matrix_migration_statuses_match_scope_classes():
    rows = _load_rows()
    for guard_id, scope, _surface, _authority, status, _contract in rows:
        assert status in MIGRATION_STATUS_VALUES, (
            "{0}: migration status outside closed vocabulary: {1!r}".format(
                guard_id, status
            )
        )
        if scope in _PRODUCTION_SCOPES:
            assert status in ("S2-MIGRATED", "DB-SEAM-DEFERRED"), (
                "{0}: production scope must be S2-MIGRATED or "
                "DB-SEAM-DEFERRED, got {1!r}".format(guard_id, status)
            )
        else:
            assert status == "N/A", (
                "{0}: non-production scope must be N/A, got {1!r}".format(
                    guard_id, status
                )
            )


def test_matrix_has_no_implicit_scope_cells():
    rows = _load_rows()
    for guard_id, scope, surface, authority, status, contract in rows:
        for cell in (scope, surface, authority, status, contract):
            assert "unknown" not in cell.lower(), (
                "{0}: implicit scope wording in cell: {1!r}".format(
                    guard_id, cell
                )
            )


def test_expected_migration_split_is_pinned():
    # Pin the Slice 3 split explicitly: 16 guards migrated to the neutral
    # module in Slices 1-2, the DB guard deferred on its compatibility seam,
    # and every non-production scope N/A.  A change here must be a conscious
    # matrix update, never silent drift.
    rows = _load_rows()
    statuses = {row[0]: row[4] for row in rows}
    migrated = sorted(
        guard_id for guard_id, status in statuses.items()
        if status == "S2-MIGRATED"
    )
    deferred = sorted(
        guard_id for guard_id, status in statuses.items()
        if status == "DB-SEAM-DEFERRED"
    )
    not_applicable = sorted(
        guard_id for guard_id, status in statuses.items() if status == "N/A"
    )
    assert deferred == ["db_access"]
    assert not_applicable == [
        "allowlist_compliance",
        "currency_guardrails_ps",
        "db_artifact_sync",
        "ignored_test_budget",
        "known_good_state",
        "lint_baseline_policy",
        "release_artifact",
    ]
    assert len(migrated) == 16
    assert len(statuses) == len(GUARD_REGISTRY) == 24


if __name__ == "__main__":
    raise SystemExit(
        "Run with pytest: python -m pytest "
        "scripts/ci/test_gr10b_source_scope_matrix.py -v"
    )
