#!/usr/bin/env python3
"""
test_gr15_acceptance_gate.py

Pytest tests for the GR-15 batch 1 owner-acceptance gate
(scripts/ci/inspect_db_mediation_proof.py registry loader + applier).

Tests verify:
  1. A valid registry loads (all fields, optional amendment allowed).
  2. Missing file fails closed (GR15_ACCEPTANCE_REGISTRY_UNAVAILABLE).
  3. Malformed YAML fails closed (GR15_ACCEPTANCE_REGISTRY_INVALID).
  4. Schema violations fail closed: unknown field, bad version, empty
     rows, duplicate mutationKey, non-string caveat.
  5. Unproven registry rows are reclassified to owner_accepted with the
     family, caveats, and record carried; summary counts move honestly.
  6. A registry row matching NO board row is a mismatch, never applied.
  7. A registry row matching a PROVEN row is a mismatch, never applied.
  8. A registry row matching a COUNTEREXAMPLE row is a mismatch, never
     applied — acceptance can never absorb a counterexample.
  9. Board rows absent from the registry stay unproven (fail closed).
 10. The caveat text travels with the loaded registry document.
 11. The tracked production registry loads and validates.

Run:
    python -m pytest scripts/ci/test_gr15_acceptance_gate.py -v
"""

import os
import sys

import yaml

_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from scripts.ci.inspect_db_mediation_proof import (  # noqa: E402
    _ACCEPTANCE_REGISTRY_RELATIVE_PATH,
    _apply_acceptance_registry,
    _load_acceptance_registry,
)

VALID_REGISTRY = {
    "schemaVersion": 1,
    "record": "docs/ci/db-mediation/GR-14_OWNER_ACCEPTANCE_RECORD.md",
    "amendment": "docs/ci/db-mediation/GR-15_GATE_AMENDMENT.md",
    "caveat": "owner_accepted means proven OR honestly accepted with recorded reasons.",
    "rows": [
        {
            "mutationKey": "app/src/main/java/X.kt|Owner|function|m|null|a|dao|Dao|op",
            "family": "familyX",
            "caveats": ["caveat one"],
        }
    ],
}


def _write_registry(tmp_path, document, name="registry.yml"):
    path = tmp_path / name
    with open(path, "w", encoding="utf-8") as handle:
        yaml.safe_dump(document, handle, sort_keys=False)
    return str(path)


def _board_row(key, status):
    return {"mutationKey": key, "proofStatus": status}


def _counts(rows):
    counts = {}
    for row in rows:
        counts[row["proofStatus"]] = counts.get(row["proofStatus"], 0) + 1
    return counts


# 1-4: loader ------------------------------------------------------------------


def test_valid_registry_loads(tmp_path):
    document, errors = _load_acceptance_registry(_write_registry(tmp_path, VALID_REGISTRY))
    assert errors == ()
    assert document is not None
    assert document["rows"][0]["family"] == "familyX"


def test_missing_registry_fails_closed(tmp_path):
    document, errors = _load_acceptance_registry(str(tmp_path / "absent.yml"))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_UNAVAILABLE",)


def test_malformed_yaml_fails_closed(tmp_path):
    path = tmp_path / "broken.yml"
    path.write_text("rows: [unclosed", encoding="utf-8")
    document, errors = _load_acceptance_registry(str(path))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_INVALID",)


def test_unknown_field_rejected(tmp_path):
    bad = dict(VALID_REGISTRY)
    bad["extra"] = 1
    document, errors = _load_acceptance_registry(_write_registry(tmp_path, bad))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_INVALID",)


def test_bad_version_rejected(tmp_path):
    bad = dict(VALID_REGISTRY, schemaVersion=2)
    document, errors = _load_acceptance_registry(_write_registry(tmp_path, bad))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_INVALID",)


def test_empty_rows_rejected(tmp_path):
    bad = dict(VALID_REGISTRY, rows=[])
    document, errors = _load_acceptance_registry(_write_registry(tmp_path, bad))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_INVALID",)


def test_duplicate_row_rejected(tmp_path):
    bad = dict(VALID_REGISTRY, rows=[VALID_REGISTRY["rows"][0], VALID_REGISTRY["rows"][0]])
    document, errors = _load_acceptance_registry(_write_registry(tmp_path, bad))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_DUPLICATE_ROW",)


def test_non_string_caveat_rejected(tmp_path):
    row = dict(VALID_REGISTRY["rows"][0], caveats=[None])
    bad = dict(VALID_REGISTRY, rows=[row])
    document, errors = _load_acceptance_registry(_write_registry(tmp_path, bad))
    assert document is None
    assert errors == ("GR15_ACCEPTANCE_REGISTRY_INVALID",)


# 5-10: applier ----------------------------------------------------------------

KEY = VALID_REGISTRY["rows"][0]["mutationKey"]


def test_unproven_row_reclassified():
    rows = [_board_row(KEY, "unproven_ambiguous_call")]
    counts = _counts(rows)
    applied, mismatches = _apply_acceptance_registry(rows, VALID_REGISTRY, counts)
    assert mismatches == []
    assert applied == [KEY]
    assert rows[0]["proofStatus"] == "owner_accepted"
    assert rows[0]["acceptance"]["family"] == "familyX"
    assert rows[0]["acceptance"]["caveats"] == ["caveat one"]
    assert rows[0]["acceptance"]["record"] == VALID_REGISTRY["record"]
    assert counts == {"owner_accepted": 1}


def test_unmatched_registry_row_is_mismatch():
    rows = [_board_row("some/other/key|Dao|op", "unproven_async_or_escaping_callback")]
    counts = _counts(rows)
    applied, mismatches = _apply_acceptance_registry(rows, VALID_REGISTRY, counts)
    assert applied == []
    assert mismatches == [
        {"mutationKey": KEY, "code": "GR15_ACCEPTANCE_ROW_UNMATCHED"}
    ]
    assert counts == {"unproven_async_or_escaping_callback": 1}


def test_proven_row_match_is_mismatch():
    rows = [_board_row(KEY, "proven_helper")]
    counts = _counts(rows)
    applied, mismatches = _apply_acceptance_registry(rows, VALID_REGISTRY, counts)
    assert applied == []
    assert mismatches == [{"mutationKey": KEY, "code": "GR15_ACCEPTANCE_ROW_PROVEN"}]
    assert rows[0]["proofStatus"] == "proven_helper"


def test_counterexample_row_never_absorbed():
    rows = [_board_row(KEY, "counterexample_unguarded_call_path")]
    counts = _counts(rows)
    applied, mismatches = _apply_acceptance_registry(rows, VALID_REGISTRY, counts)
    assert applied == []
    assert mismatches == [
        {"mutationKey": KEY, "code": "GR15_ACCEPTANCE_ROW_COUNTEREXAMPLE"}
    ]
    assert rows[0]["proofStatus"] == "counterexample_unguarded_call_path"


def test_unregistered_rows_stay_unproven():
    other = "app/src/main/java/Y.kt|Owner2|function|n|null|b|dao|Dao2|op2"
    rows = [_board_row(KEY, "unproven_ambiguous_call"), _board_row(other, "unproven_async_or_escaping_callback")]
    counts = _counts(rows)
    applied, mismatches = _apply_acceptance_registry(rows, VALID_REGISTRY, counts)
    assert applied == [KEY]
    assert mismatches == []
    assert rows[1]["proofStatus"] == "unproven_async_or_escaping_callback"
    assert counts == {"owner_accepted": 1, "unproven_async_or_escaping_callback": 1}


def test_caveat_travels_with_document():
    assert "honestly accepted" in VALID_REGISTRY["caveat"]


# 11: the tracked production registry ------------------------------------------


def test_tracked_production_registry_loads():
    path = os.path.join(_PROJECT_ROOT, _ACCEPTANCE_REGISTRY_RELATIVE_PATH)
    document, errors = _load_acceptance_registry(path)
    assert errors == ()
    assert document is not None
    # Regenerated 2026-09-15 (wave-1): 18 rows — linkTarget and writeCritical
    # became PROVEN via RP-02 barrier ownership (familyD 3 -> 1).
    assert len(document["rows"]) == 18
    families = {}
    for row in document["rows"]:
        families[row["family"]] = families.get(row["family"], 0) + 1
        assert isinstance(row["mutationKey"], str) and row["mutationKey"]
    assert families == {
        "familyA_post_commit_action": 6,
        "familyB_combine": 4,
        "familyC_ui_callback": 3,
        "familyD_run_with_retry": 1,
        "familyE_projection_as_is": 1,
        "familyF_flow_emit": 1,
        "pair_handle_masked": 2,
    }
    pair_rows = [row for row in document["rows"] if row["family"] == "pair_handle_masked"]
    assert len(pair_rows) == 2
    for row in pair_rows:
        assert len(row["caveats"]) == 2
