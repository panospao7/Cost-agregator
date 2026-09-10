"""Accounting-model tests for the v1 -> v2 DB-policy migration evidence.

These tests exercise the PR-GR-05 Slice 1 accounting model in
``scripts.db_guard.policy_v2_candidate``: :class:`AccountingRecord`,
:class:`SourceMutationCoverage`, :class:`AccountingArtifact`, and
:func:`build_accounting_artifact`.  Every invariant must fail closed with
``ValueError``; the happy path must serialize deterministically with exact
key ordering; repeated builds from identical inputs must be identical.

The appended PR-GR-05 Slice 4 section pins the dedupe-crosswalk wiring:
``MigrationResult.emission_indices`` folds identical legacy
re-authorizations into one candidate entry while every source legacy
index stays tied to the shared canonical mutation key.

The appended PR-GR-05 Slice 5 section pins the refined fold contract
(three same-key reason variants share one RESOLVED key through the
crosswalk) and the new closed ``AUTHORIZATION_METADATA_CONFLICT``
vocabulary entry that conflicted indices carry as debt.

Authored coverage; execution pending in this environment.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# ``policy_v2_candidate`` uses in-package relative imports, so it must be
# imported as ``scripts.db_guard.policy_v2_candidate`` with the worktree
# root on ``sys.path``.
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import pytest  # noqa: E402

from scripts.db_guard.policy_model import (  # noqa: E402
    BarrierMode,
    CallableKind,
    PolicyEntry,
)
from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    ACTION_EMIT_CANDIDATE,
    ACTION_REVIEW_DEBT,
    ACCOUNTING_SCHEMA_NAME,
    AccountingArtifact,
    AccountingRecord,
    COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
    COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
    COVERAGE_OBSERVED_BUT_UNRESOLVED,
    COVERAGE_UNRESOLVED_ANALYZER_INPUT,
    MIGRATION_STATUSES,
    MIGRATION_STATUSES_EXTENDED,
    MigrationResult,
    OUTCOME_RESOLVED,
    OUTCOME_UNRESOLVED,
    ResolvedRow,
    SOURCE_MUTATION_COVERAGE_KINDS,
    STATUS_AUTHORIZATION_METADATA_CONFLICT,
    STATUS_BARRIER_MODE_UNRESOLVED,
    STATUS_OWNER_MISSING,
    STATUS_PARSER_UNCERTAIN,
    STATUS_PARSER_UNSUPPORTED,
    STATUS_SOURCE_ROOT_UNRESOLVED,
    SourceMutationCoverage,
    UnresolvedRow,
    build_accounting_artifact,
    build_observed_mutation_set,
    build_source_mutation_coverage,
    classify_source_mutations,
    migrate_policy,
)

POLICY_PATH = "config/guards/db_ownership_policy.yml"
SHA_A = "a" * 64
SHA_B = "b" * 64

_ARTIFACT_KEY_ORDER = [
    "candidateSha256",
    "inputCount",
    "records",
    "schema",
    "sourceMutations",
    "sourcePolicyPath",
    "sourcePolicySha256",
    "sourceTreeSha",
    "version",
]

_RECORD_KEY_ORDER = [
    "action",
    "detail",
    "index",
    "mutationKeys",
    "outcome",
    "status",
]


def _entry(
    method="save",
    operation="insert",
    accessor="expenseDao",
    fqcn="com.example.ExpenseDao",
):
    """One schema-valid v2 candidate entry with tunable identity fields."""
    return PolicyEntry(
        path="app/src/main/java/com/example/Repo.kt",
        owner_fqcn="com.example.Repo",
        kind=CallableKind.FUNCTION,
        method=method,
        receiver=None,
        parameter_types=("Int",),
        dao_accessor=accessor,
        dao_fqcn=fqcn,
        operation=operation,
        barrier_mode=BarrierMode.DIRECT,
        reason="controlled migration reason",
        owner="expense-owners",
        linked_issue="ISSUE-100",
    )


def _canonical(entry):
    return entry.mutation_key().canonical_key()


def _resolved_row(index, entry):
    return ResolvedRow(index, entry)


def _unresolved_row(index, status=STATUS_OWNER_MISSING, detail="debt"):
    return UnresolvedRow(index, "com.example.Cls", "method", status, detail)


def _result(resolved=(), unresolved=(), input_count=None):
    if input_count is None:
        input_count = len(resolved) + len(unresolved)
    return MigrationResult(tuple(resolved), tuple(unresolved), input_count)


def _build(result, entries, **overrides):
    kwargs = {
        "source_policy_path": POLICY_PATH,
        "source_policy_sha256": SHA_A,
        "source_tree_sha": SHA_B,
    }
    kwargs.update(overrides)
    return build_accounting_artifact(result, entries, **kwargs)


def _happy_result_and_entries():
    """3 resolved rows (indices 0-2) plus 1 unresolved row (index 3)."""
    e0 = _entry(method="save", operation="insert")
    e1 = _entry(method="delete", operation="delete")
    e2 = _entry(
        method="archiveAll",
        operation="insert",
        accessor="auditDao",
        fqcn="com.example.AuditDao",
    )
    result = _result(
        resolved=(
            _resolved_row(0, e0),
            _resolved_row(1, e1),
            _resolved_row(2, e2),
        ),
        unresolved=(
            _unresolved_row(3, STATUS_BARRIER_MODE_UNRESOLVED, ""),
        ),
        input_count=4,
    )
    # Deliberately shuffled: candidate key identity is a set, never order.
    return result, [e2, e0, e1], (e0, e1, e2)


# ── Invariant violations must raise ValueError ───────────────────────────────


def test_missing_legacy_index_rejected():
    e0 = _entry(method="save")
    result = _result(
        resolved=(_resolved_row(0, e0),),
        unresolved=(_unresolved_row(2),),
        input_count=3,
    )
    with pytest.raises(ValueError):
        _build(result, [e0])


def test_out_of_range_legacy_index_rejected():
    e0 = _entry(method="save")
    result = _result(resolved=(_resolved_row(5, e0),), input_count=1)
    with pytest.raises(ValueError):
        _build(result, [e0])


def test_duplicate_record_index_rejected():
    e_a = _entry(method="save", operation="insert")
    e_b = _entry(method="save", operation="delete")
    with pytest.raises(ValueError):
        AccountingArtifact(
            source_policy_path=POLICY_PATH,
            source_policy_sha256=SHA_A,
            source_tree_sha=SHA_B,
            input_count=1,
            records=(
                AccountingRecord(
                    index=0,
                    outcome=OUTCOME_RESOLVED,
                    action=ACTION_EMIT_CANDIDATE,
                    mutation_keys=(_canonical(e_a),),
                ),
                AccountingRecord(
                    index=0,
                    outcome=OUTCOME_RESOLVED,
                    action=ACTION_EMIT_CANDIDATE,
                    mutation_keys=(_canonical(e_b),),
                ),
            ),
        )


def test_resolved_record_without_mutation_keys_rejected():
    with pytest.raises(ValueError):
        AccountingRecord(
            index=0, outcome=OUTCOME_RESOLVED, action=ACTION_EMIT_CANDIDATE
        )


def test_unresolved_record_with_mutation_keys_rejected():
    with pytest.raises(ValueError):
        AccountingRecord(
            index=0,
            outcome=OUTCOME_UNRESOLVED,
            status=STATUS_OWNER_MISSING,
            action=ACTION_REVIEW_DEBT,
            mutation_keys=("some|canonical|key",),
        )


def test_unknown_status_rejected():
    with pytest.raises(ValueError):
        AccountingRecord(
            index=0,
            outcome=OUTCOME_UNRESOLVED,
            status="TOTALLY_UNKNOWN_STATUS",
            action=ACTION_REVIEW_DEBT,
        )


def test_unresolved_record_without_status_rejected():
    with pytest.raises(ValueError):
        AccountingRecord(index=0, outcome=OUTCOME_UNRESOLVED, action=ACTION_REVIEW_DEBT)


def test_record_key_absent_from_candidates_rejected():
    e0 = _entry(method="save")
    result = _result(resolved=(_resolved_row(0, e0),), input_count=1)
    with pytest.raises(ValueError):
        _build(result, [])


def test_candidate_key_not_covered_by_records_rejected():
    e0 = _entry(method="save")
    e_other = _entry(method="purge", operation="delete")
    result = _result(resolved=(_resolved_row(0, e0),), input_count=1)
    with pytest.raises(ValueError):
        _build(result, [e0, e_other])


def test_absolute_source_policy_path_rejected():
    e0 = _entry(method="save")
    result = _result(resolved=(_resolved_row(0, e0),), input_count=1)
    for absolute in ("/etc/db_ownership_policy.yml", "C:\\tmp\\policy.yml"):
        with pytest.raises(ValueError):
            _build(result, [e0], source_policy_path=absolute)


def test_absolute_coverage_path_rejected():
    with pytest.raises(ValueError):
        SourceMutationCoverage(
            kind=COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
            path="/absolutely/not/relative.kt",
            symbol="Cls.method",
        )


def test_oversized_detail_rejected_and_boundary_accepted():
    with pytest.raises(ValueError):
        AccountingRecord(
            index=0,
            outcome=OUTCOME_UNRESOLVED,
            status=STATUS_OWNER_MISSING,
            detail="x" * 201,
            action=ACTION_REVIEW_DEBT,
        )
    boundary = AccountingRecord(
        index=0,
        outcome=OUTCOME_UNRESOLVED,
        status=STATUS_OWNER_MISSING,
        detail="x" * 200,
        action=ACTION_REVIEW_DEBT,
    )
    assert boundary.detail == "x" * 200


def test_oversized_symbol_rejected():
    with pytest.raises(ValueError):
        SourceMutationCoverage(
            kind=COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
            path="app/src/main/java/com/example/X.kt",
            symbol="y" * 201,
        )


def test_unsupported_schema_version_rejected():
    with pytest.raises(ValueError):
        AccountingArtifact(
            schema_version=2,
            source_policy_path=POLICY_PATH,
            source_policy_sha256=SHA_A,
            source_tree_sha=SHA_B,
            input_count=0,
        )


def test_malformed_sha256_rejected():
    e0 = _entry(method="save")
    result = _result(resolved=(_resolved_row(0, e0),), input_count=1)
    for bad in ("not-a-hash", "A" * 64, SHA_A[:-1] + "g"):
        with pytest.raises(ValueError):
            _build(result, [e0], source_policy_sha256=bad)


def test_unknown_outcome_rejected():
    with pytest.raises(ValueError):
        AccountingRecord(
            index=0,
            outcome="SKIPPED",
            action=ACTION_REVIEW_DEBT,
        )


def test_unknown_coverage_kind_rejected():
    assert len(SOURCE_MUTATION_COVERAGE_KINDS) == 4
    with pytest.raises(ValueError):
        SourceMutationCoverage(
            kind="NOT_A_COVERAGE_KIND",
            path="app/src/main/java/com/example/X.kt",
            symbol="X.x",
        )


def test_unsorted_mutation_keys_rejected():
    e_late = _entry(method="save", operation="insert")
    e_early = _entry(method="delete", operation="delete")
    assert _canonical(e_early) < _canonical(e_late)
    with pytest.raises(ValueError):
        AccountingRecord(
            index=0,
            outcome=OUTCOME_RESOLVED,
            action=ACTION_EMIT_CANDIDATE,
            mutation_keys=(_canonical(e_late), _canonical(e_early)),
        )


def test_unordered_source_mutations_rejected():
    e0 = _entry(method="save")
    late = SourceMutationCoverage(
        kind=COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
        path="app/src/main/java/com/example/B.kt",
        symbol="B.b",
    )
    early = SourceMutationCoverage(
        kind=COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
        path="app/src/main/java/com/example/A.kt",
        symbol="A.a",
        operation="insert",
        legacy_indices=(0,),
    )
    with pytest.raises(ValueError):
        AccountingArtifact(
            source_policy_path=POLICY_PATH,
            source_policy_sha256=SHA_A,
            source_tree_sha=SHA_B,
            input_count=1,
            records=(
                AccountingRecord(
                    index=0,
                    outcome=OUTCOME_RESOLVED,
                    action=ACTION_EMIT_CANDIDATE,
                    mutation_keys=(_canonical(e0),),
                ),
            ),
            source_mutations=(late, early),
        )


# ── Happy path, ordering, determinism ────────────────────────────────────────


def test_happy_path_round_trips_to_exactly_ordered_dict():
    result, candidates, _entries_in_order = _happy_result_and_entries()
    artifact = _build(result, candidates)
    assert artifact.input_count == 4
    assert [record.index for record in artifact.records] == [0, 1, 2, 3]
    outcomes = [record.outcome for record in artifact.records]
    assert outcomes == [OUTCOME_RESOLVED] * 3 + [OUTCOME_UNRESOLVED]
    assert all(record.status is None for record in artifact.records[:3])
    assert artifact.records[3].status == STATUS_BARRIER_MODE_UNRESOLVED
    assert all(
        record.action == ACTION_EMIT_CANDIDATE
        for record in artifact.records[:3]
    )
    assert artifact.records[3].action == ACTION_REVIEW_DEBT
    payload = artifact.to_dict()
    assert list(payload.keys()) == _ARTIFACT_KEY_ORDER
    assert payload["schema"] == ACCOUNTING_SCHEMA_NAME
    assert payload["version"] == 1
    assert payload["sourcePolicyPath"] == POLICY_PATH
    assert payload["candidateSha256"] is None
    assert len(payload["records"]) == 4
    for record_payload in payload["records"]:
        assert list(record_payload.keys()) == _RECORD_KEY_ORDER
    assert payload["records"][0]["mutationKeys"] == [
        _canonical(_entry(method="save", operation="insert"))
    ]
    assert payload["records"][3]["mutationKeys"] == []
    # Must survive a real JSON round trip unchanged.
    assert json.loads(json.dumps(payload)) == payload


def test_two_builds_from_same_inputs_are_identical():
    result, candidates, _ = _happy_result_and_entries()
    first = _build(result, candidates).to_dict()
    second = _build(result, candidates).to_dict()
    assert first == second
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)


def test_multi_operation_split_merges_under_one_index():
    e_insert = _entry(method="save", operation="insert")
    e_delete = _entry(method="save", operation="delete")
    result = _result(
        resolved=(
            _resolved_row(0, e_insert),
            _resolved_row(0, e_delete),
        ),
        unresolved=(_unresolved_row(1),),
        input_count=2,
    )
    artifact = _build(result, [e_delete, e_insert])
    merged = artifact.records[0]
    assert merged.mutation_keys == tuple(
        sorted((_canonical(e_insert), _canonical(e_delete)))
    )
    assert len(merged.mutation_keys) == 2


def test_shared_candidate_key_across_indices_allowed():
    shared = _entry(method="save")
    result = _result(
        resolved=(
            _resolved_row(0, shared),
            _resolved_row(1, shared),
        ),
        input_count=2,
    )
    artifact = _build(result, [shared])
    key = _canonical(shared)
    assert artifact.records[0].mutation_keys == (key,)
    assert artifact.records[1].mutation_keys == (key,)


def test_extended_closed_statuses_accepted():
    e0 = _entry(method="save")
    result = _result(
        resolved=(_resolved_row(0, e0),),
        unresolved=(
            _unresolved_row(1, STATUS_PARSER_UNCERTAIN, "parser failed"),
            _unresolved_row(2, STATUS_BARRIER_MODE_UNRESOLVED, ""),
        ),
        input_count=3,
    )
    artifact = _build(result, [e0])
    assert artifact.records[1].status == STATUS_PARSER_UNCERTAIN
    assert artifact.records[2].status == STATUS_BARRIER_MODE_UNRESOLVED


def test_slice2_appended_statuses_accepted_in_accounting_records():
    """PR-GR-05 Slice 2 widened the closed vocabulary at the documented
    append-only extension point; accounting records must accept the
    plan-required SOURCE_ROOT_UNRESOLVED / PARSER_UNSUPPORTED constants."""
    for status in (STATUS_SOURCE_ROOT_UNRESOLVED, STATUS_PARSER_UNSUPPORTED):
        record = AccountingRecord(
            index=0,
            outcome=OUTCOME_UNRESOLVED,
            status=status,
            action=ACTION_REVIEW_DEBT,
        )
        assert record.status == status
        # The same constants construct bounded UnresolvedRow debt too.
        row = UnresolvedRow(0, "Cls", "method", status, "")
        assert row.status == status


def test_builder_orders_source_mutations_deterministically():
    e0 = _entry(method="save")
    late = SourceMutationCoverage(
        kind=COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
        path="app/src/main/java/com/example/B.kt",
        symbol="B.b",
    )
    early = SourceMutationCoverage(
        kind=COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
        path="app/src/main/java/com/example/A.kt",
        symbol="A.a",
        operation="insert",
        legacy_indices=(0,),
    )
    artifact = _build(result=_result(resolved=(_resolved_row(0, e0),), input_count=1),
                     entries=[e0],
                     source_mutations=(late, early))
    assert artifact.source_mutations == (early, late)
    serialized = artifact.to_dict()["sourceMutations"]
    assert [item["path"] for item in serialized] == [
        "app/src/main/java/com/example/A.kt",
        "app/src/main/java/com/example/B.kt",
    ]


# ── PR-GR-05 Slice 4: dedupe-crosswalk wiring ────────────────────────────────
#
# ``migrate_policy`` now emits ONE candidate entry per unique canonical
# mutation key; identical later emissions fold away and survive only in
# ``MigrationResult.emission_indices``.  The accounting builder must wire
# that crosswalk through: every index whose emission produced a key maps
# to it — folded indices included — while every existing fail-closed
# invariant keeps rejecting contradictory batches.


def test_dedupe_crosswalk_maps_folded_index_to_shared_key():
    """One kept row + one folded index: BOTH records carry the key.

    Index 0's emission kept its ResolvedRow; index 1 emitted the identical
    canonical key and was deduplicated away, so it has no resolved row of
    its own.  The single candidate entry must still satisfy the bijective
    crosswalk with both indices' RESOLVED records.
    """
    shared = _entry(method="save")
    key = _canonical(shared)
    result = MigrationResult(
        (_resolved_row(0, shared),),
        (),
        2,
        ((key, (0, 1)),),
    )
    artifact = _build(result, [shared])
    assert [record.index for record in artifact.records] == [0, 1]
    for record in artifact.records:
        assert record.outcome == OUTCOME_RESOLVED
        assert record.action == ACTION_EMIT_CANDIDATE
        assert record.status is None
        assert record.mutation_keys == (key,)
    payload = artifact.to_dict()
    assert payload["inputCount"] == 2
    assert payload["records"][1]["mutationKeys"] == [key]


def test_dedupe_crosswalk_merges_with_resolved_derived_keys():
    """Crosswalk keys union with resolved-row keys per index.

    Two distinct keys each emitted by one kept row and one folded row:
    records must show exactly their own key, derived from both sources
    without duplication.
    """
    e_a = _entry(method="save", operation="insert")
    e_b = _entry(method="wipe", operation="delete")
    key_a, key_b = _canonical(e_a), _canonical(e_b)
    assert key_a != key_b
    result = MigrationResult(
        (_resolved_row(0, e_a), _resolved_row(1, e_b)),
        (),
        4,
        ((key_a, (0, 2)), (key_b, (1, 3))),
    )
    artifact = _build(result, [e_a, e_b])
    assert artifact.records[0].mutation_keys == (key_a,)
    assert artifact.records[1].mutation_keys == (key_b,)
    assert artifact.records[2].mutation_keys == (key_a,)
    assert artifact.records[3].mutation_keys == (key_b,)


def test_emission_map_index_with_unresolved_row_rejected():
    """A crosswalk index that also carries debt is a contradictory batch."""
    shared = _entry(method="save")
    result = MigrationResult(
        (_resolved_row(0, shared),),
        (_unresolved_row(1),),
        2,
        ((_canonical(shared), (0, 1)),),
    )
    with pytest.raises(ValueError):
        _build(result, [shared])


def test_emission_map_key_absent_from_candidates_rejected():
    """A crosswalk key with no candidate entry fails the bijective gate."""
    ghost = _entry(method="purge", operation="delete")
    real = _entry(method="save")
    result = MigrationResult(
        (_resolved_row(0, real),),
        (),
        1,
        ((_canonical(ghost), (0,)),),
    )
    with pytest.raises(ValueError):
        _build(result, [real])


def test_migration_result_rejects_malformed_emission_maps():
    """The crosswalk field itself fails closed on every malformed shape."""
    with pytest.raises(ValueError):  # duplicate keys
        MigrationResult((), (), 0, (("k", (0,)), ("k", (1,))))
    with pytest.raises(ValueError):  # keys not sorted
        MigrationResult((), (), 0, (("b", (0,)), ("a", (1,))))
    with pytest.raises(ValueError):  # indices not ascending/unique
        MigrationResult((), (), 0, (("a", (1, 0)),))
    with pytest.raises(ValueError):  # boolean index smuggled as int
        MigrationResult((), (), 0, (("a", (True,)),))
    with pytest.raises(ValueError):  # indices must be a tuple
        MigrationResult((), (), 0, (("a", [0]),))
    with pytest.raises(ValueError):  # pair must be a 2-tuple
        MigrationResult((), (), 0, (("a", 0),))
    with pytest.raises(ValueError):  # empty key
        MigrationResult((), (), 0, (("", (0,)),))
    # The valid minimal crosswalk constructs cleanly.
    ok = MigrationResult((), (), 0, (("a|b", (0, 1)),))
    assert ok.emission_indices == (("a|b", (0, 1)),)


# ── PR-GR-05 Slice 5: refined fold + AUTHORIZATION_METADATA_CONFLICT ─────────
#
# Slice 5 refines the Slice 4 fold rule and adds one closed debt status:
#   * FOLD — same canonical key AND identical authorization metadata
#     ``(barrierMode, owner, linkedIssue)``: free-text ``reason``
#     differences fold away, the lowest-index entry is kept verbatim, and
#     EVERY source index's RESOLVED accounting record carries the shared
#     key through the crosswalk;
#   * CONFLICT — same key with differing barrierMode/owner/linkedIssue:
#     every participating index becomes an UNRESOLVED record carrying the
#     new ``AUTHORIZATION_METADATA_CONFLICT`` status with zero keys, and
#     the conflicted key appears nowhere (no candidate, no crosswalk).


def test_slice5_conflict_status_joins_closed_vocabulary():
    """The new status exists only at the append-only extension point."""
    assert STATUS_AUTHORIZATION_METADATA_CONFLICT == (
        "AUTHORIZATION_METADATA_CONFLICT"
    )
    assert STATUS_AUTHORIZATION_METADATA_CONFLICT not in MIGRATION_STATUSES
    assert STATUS_AUTHORIZATION_METADATA_CONFLICT in MIGRATION_STATUSES_EXTENDED
    # The same constant constructs bounded UnresolvedRow debt...
    row = UnresolvedRow(
        0, "Cls", "method", STATUS_AUTHORIZATION_METADATA_CONFLICT, ""
    )
    assert row.status == STATUS_AUTHORIZATION_METADATA_CONFLICT
    # ...and is accepted as an UNRESOLVED accounting-record status.
    record = AccountingRecord(
        index=0,
        outcome=OUTCOME_UNRESOLVED,
        status=STATUS_AUTHORIZATION_METADATA_CONFLICT,
        action=ACTION_REVIEW_DEBT,
    )
    assert record.status == STATUS_AUTHORIZATION_METADATA_CONFLICT


def test_slice5_three_folded_indices_share_one_resolved_key():
    """Fold case: three same-key rows -> ONE entry, THREE resolved records.

    Index 0's emission is kept; indices 1 and 2 folded away (reason-only
    differences), yet all three records must carry the shared canonical
    key so the bijective crosswalk still covers every legacy index.
    """
    shared = _entry(method="save")
    key = _canonical(shared)
    result = MigrationResult(
        (_resolved_row(0, shared),),
        (),
        3,
        ((key, (0, 1, 2)),),
    )
    artifact = _build(result, [shared])
    assert [record.index for record in artifact.records] == [0, 1, 2]
    for record in artifact.records:
        assert record.outcome == OUTCOME_RESOLVED
        assert record.action == ACTION_EMIT_CANDIDATE
        assert record.status is None
        assert record.mutation_keys == (key,)
    payload = artifact.to_dict()
    assert payload["inputCount"] == 3
    assert [record["mutationKeys"] for record in payload["records"]] == [
        [key]
    ] * 3


def test_slice5_conflicted_indices_become_unresolved_records():
    """Conflict case: both conflicting indices carry the closed status.

    One clean key still emits from index 0; the two indices that disagreed
    on a conflicted key become UNRESOLVED records with
    AUTHORIZATION_METADATA_CONFLICT, zero mutation keys, and REVIEW_DEBT
    action.  The conflicted key appears nowhere in the artifact.
    """
    clean = _entry(method="save")
    detail = "conflictingIndices=2 keyTail=insert"
    result = _result(
        resolved=(_resolved_row(0, clean),),
        unresolved=(
            _unresolved_row(
                1, STATUS_AUTHORIZATION_METADATA_CONFLICT, detail
            ),
            _unresolved_row(
                2, STATUS_AUTHORIZATION_METADATA_CONFLICT, detail
            ),
        ),
        input_count=3,
    )
    artifact = _build(result, [clean])
    for record in artifact.records[1:]:
        assert record.outcome == OUTCOME_UNRESOLVED
        assert record.status == STATUS_AUTHORIZATION_METADATA_CONFLICT
        assert record.mutation_keys == ()
        assert record.action == ACTION_REVIEW_DEBT
        assert record.detail == detail
    payload = artifact.to_dict()
    assert payload["inputCount"] == 3
    outcomes = [record["outcome"] for record in payload["records"]]
    assert outcomes == ["RESOLVED", "UNRESOLVED", "UNRESOLVED"]
    # The union of record keys names ONLY the clean emission: the
    # conflicted key never leaks into the accounting evidence.
    record_keys = {
        key for record in payload["records"] for key in record["mutationKeys"]
    }
    assert record_keys == {_canonical(clean)}


# ── PR-GR-05: source-mutation coverage discovery + classification ────────────
#
# The coverage section answers one review question about the migration: of
# every caller-side DAO mutation the production tree performs, how many are
# covered by a resolved legacy row, how many match an unresolved row's
# intent, how many sit outside the legacy policy entirely, and how many are
# analyzer-input-limited.  The fixtures below run the REAL discovery and
# classification machinery over a synthetic declared-root tree (annotated
# Room DAO + caller classes) — no mocking of the evidence primitives.

_COVERAGE_DAO_KT = "app/src/main/java/com/example/ExpenseDao.kt"
_COVERAGE_CALLERS_KT = "app/src/main/java/com/example/Callers.kt"

#: Empty but VALID raw-query policy so synthetic inventories never consult
#: (and never go stale against) the real repository's classification file.
_EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}

_COVERAGE_DAO_SOURCE = (
    "package com.example\n"
    "\n"
    "@Dao\n"
    "interface ExpenseDao {\n"
    "    @Insert\n"
    "    fun insert(value: Long): Long\n"
    "}\n"
)

_COVERAGE_CALLERS_SOURCE = (
    "package com.example\n"
    "\n"
    "class Covered {\n"
    "    fun save(value: Long) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
    "\n"
    "class Stranger {\n"
    "    fun other(value: Long) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
    "\n"
    "class Debt {\n"
    "    fun target(value: Long) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
    "\n"
    "class Odd {\n"
    "    fun weird(input: ProjectType) {\n"
    "        expenseDao.insert(1)\n"
    "    }\n"
    "}\n"
)


def _coverage_legacy_entries():
    """Legacy rows producing one covered, one unresolved-target site."""
    covered = {
        "path": _COVERAGE_CALLERS_KT,
        "class": "Covered",
        "method": "save",
        "daos": ["ExpenseDao"],
        "operation": "insert",
        "barrier_required": False,
        "reason": "covered scenario",
        "owner": "expense-owners",
        "linked_issue": "ISSUE-100",
        "signature": {"receiver": None, "parameters": ["Long"]},
    }
    # Contradictory barrier metadata (mediation claim + direct-barrier
    # requirement) fails closed at the phase-1 barrier gate, so this row
    # ends UNRESOLVED while its path/class/method/operation still name the
    # Debt.target callable exactly.
    debt = dict(covered)
    debt.update(
        {
            "class": "Debt",
            "method": "target",
            "barrier_required": True,
            "barrier_via": "WorkerExecutionGuard",
            "reason": "debt scenario",
        }
    )
    return [covered, debt]


def _coverage_repo(tmp_path: Path):
    (tmp_path / ".keep").touch()
    for relative, source in (
        (_COVERAGE_DAO_KT, _COVERAGE_DAO_SOURCE),
        (_COVERAGE_CALLERS_KT, _COVERAGE_CALLERS_SOURCE),
    ):
        target = tmp_path / Path(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")


def _coverage_result(tmp_path: Path):
    entries = _coverage_legacy_entries()
    result = migrate_policy(entries, str(tmp_path))
    return entries, result


def test_coverage_fixture_kinds_are_exact(tmp_path):
    """One covered, one unresolved-target, one not-in-policy, one limited.

    Four observed sites over the same DAO operation, classified into all
    four closed kinds with exactly the legacy indices each kind owes.
    """
    _coverage_repo(tmp_path)
    entries, result = _coverage_result(tmp_path)
    observed_set = build_observed_mutation_set(
        tmp_path,
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
        attested_pairs=frozenset(
            (row.entry.dao_fqcn, row.entry.operation)
            for row in result.resolved
        ),
    )
    assert observed_set is not None
    by_owner = {}
    for mutation in observed_set.mutations:
        by_owner[mutation.owner_fqcn] = mutation
    assert set(by_owner) == {
        "com.example.Covered",
        "com.example.Stranger",
        "com.example.Debt",
        "com.example.Odd",
    }
    assert not by_owner["com.example.Covered"].analyzer_limited
    assert by_owner["com.example.Odd"].analyzer_limited

    coverage = classify_source_mutations(observed_set, result, entries)
    kinds = {}
    for item in coverage:
        kinds.setdefault(item.kind, []).append(item)
    assert set(kinds) == {
        COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
        COVERAGE_OBSERVED_BUT_UNRESOLVED,
        COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
        COVERAGE_UNRESOLVED_ANALYZER_INPUT,
    }
    covered = kinds[COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW]
    assert len(covered) == 1
    assert covered[0].path == _COVERAGE_CALLERS_KT
    assert covered[0].symbol == "com.example.Covered#save"
    assert covered[0].operation == "insert"
    # The covering legacy row's index survives on the coverage entry.
    assert covered[0].legacy_indices == (0,)
    unresolved = kinds[COVERAGE_OBSERVED_BUT_UNRESOLVED]
    assert len(unresolved) == 1
    assert unresolved[0].symbol == "com.example.Debt#target"
    assert unresolved[0].legacy_indices == (1,)
    stranger = kinds[COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY]
    assert len(stranger) == 1
    assert stranger[0].symbol == "com.example.Stranger#other"
    assert stranger[0].legacy_indices == ()
    limited = kinds[COVERAGE_UNRESOLVED_ANALYZER_INPUT]
    assert len(limited) == 1
    assert limited[0].symbol == "com.example.Odd#weird"
    assert limited[0].legacy_indices == ()


def test_coverage_partitions_observed_universe_exactly_once(tmp_path):
    """No omission, no double count: kinds partition the observed universe.

    Every observed mutation appears in exactly one coverage entry (matched
    by its full identity), the union of kinds equals the universe, and the
    ordering matches the artifact contract ``(path, symbol, operation)``.
    """
    _coverage_repo(tmp_path)
    entries, result = _coverage_result(tmp_path)
    observed_set = build_observed_mutation_set(
        tmp_path,
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
        attested_pairs=frozenset(
            (row.entry.dao_fqcn, row.entry.operation)
            for row in result.resolved
        ),
    )
    assert observed_set is not None
    coverage = classify_source_mutations(observed_set, result, entries)
    assert len(coverage) == len(observed_set.mutations)
    mutation_identities = sorted(
        (m.path, m.owner_fqcn, m.method, m.operation)
        for m in observed_set.mutations
    )
    coverage_identities = sorted(
        (
            item.path,
            item.symbol.split("#", 1)[0],
            item.symbol.split("#", 1)[1],
            item.operation or "",
        )
        for item in coverage
    )
    assert mutation_identities == coverage_identities
    identity_tuples = [
        (item.path, item.symbol, item.operation or "") for item in coverage
    ]
    assert identity_tuples == sorted(identity_tuples)


def test_coverage_classification_is_deterministic(tmp_path):
    """Two identical builds classify identically (pure evidence)."""
    _coverage_repo(tmp_path)
    entries, result = _coverage_result(tmp_path)
    first = build_source_mutation_coverage(
        tmp_path,
        result,
        entries,
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    second = build_source_mutation_coverage(
        tmp_path,
        result,
        entries,
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    assert first is not None and second is not None
    assert first == second
    serialized_first = [item.to_dict() for item in first]
    serialized_second = [item.to_dict() for item in second]
    assert serialized_first == serialized_second


def test_coverage_is_evidence_only_never_adds_candidates(tmp_path):
    """Embedding coverage leaves records and candidate crosswalk untouched.

    The artifact built with ``source_mutations`` carries exactly the same
    accounting records as the artifact built without them; only the
    additive ``sourceMutations`` section differs.
    """
    _coverage_repo(tmp_path)
    entries, result = _coverage_result(tmp_path)
    coverage = build_source_mutation_coverage(
        tmp_path,
        result,
        entries,
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    assert coverage  # non-empty on this fixture
    candidate_entries = [row.entry for row in result.resolved]
    kwargs = {
        "source_policy_path": POLICY_PATH,
        "source_policy_sha256": SHA_A,
        "source_tree_sha": SHA_B,
    }
    without = build_accounting_artifact(
        result, candidate_entries, **kwargs
    )
    with_section = build_accounting_artifact(
        result, candidate_entries, source_mutations=tuple(coverage), **kwargs
    )
    assert without.records == with_section.records
    assert without.input_count == with_section.input_count
    assert without.source_mutations == ()
    assert with_section.source_mutations == tuple(coverage)
    plain_payload = without.to_dict()
    covered_payload = with_section.to_dict()
    plain_payload.pop("sourceMutations")
    covered_payload.pop("sourceMutations")
    assert plain_payload == covered_payload


def test_coverage_wrapper_returns_none_when_tree_unresolvable(tmp_path):
    """A tree without any resolvable production root fails closed to None."""
    empty = tmp_path / "not-a-tree"
    empty.mkdir()
    entries, result = _coverage_result(tmp_path / "unused")
    assert build_source_mutation_coverage(
        empty, result, entries, raw_query_policy=_EMPTY_RAW_QUERY_POLICY
    ) is None
