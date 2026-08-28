"""GR-08a seed-mechanism tests for the v1 -> v2 DB-policy migration CLI.

Covers the reviewed-seed-rows contract added for GR-08a:

* seed files load through the ordinary v2 loader (full validation,
  within-document duplicate rejection) -- including the REAL tracked seed
  file ``docs/ci/db-findings/GR-08a-seed.yml``;
* seed/legacy duplicate mutation keys fail closed;
* seeded candidates merge deterministically and stay crosswalk-verifiable
  against the accounting artifact (``seedRecords``);
* seedless accounting artifacts stay byte-identical (no ``seedRecords``);
* the promotion gate's accounting key union includes seed keys;
* NEAR-MISS protection: the GR-08a rows authorize EXACTLY their callable
  identity + DAO + operation -- wrong overload, wrong owner, wrong DAO, and
  wrong operation stay unauthorized (exact-match, no wildcards).

GR-08b (MIT-DB-08B) extends the same contract to the three remaining
NotificationProcessingPipeline.kt callables:

* the tracked ``GR-08b-seed.yml`` loads with exactly its thirteen rows
  (the 11 findings-derived rows plus 2 closure rows for
  ``pendingReviewDao.upsertByRawNotificationId`` in processInternal and
  handleNeedsReviewInTransaction -- real writer mutations the findings
  scanner never reported and that only the GR-08a alias-bridge-fixed
  evidence verifier surfaces);
* the combined generation input ``GR-08-seeds.yml`` (the CLI accepts a
  SINGLE --seed-rows value) stays the exact concatenation of the two
  reviewed batch seed files -- a dropped GR-08a row fails closed here
  instead of silently re-unauthorizing batch-1 mutations at promotion;
* NEAR-MISS protection over the GR-08b rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08c (MIT-DB-08C) extends the same contract to the
RecurringLifecycleCoordinator.kt domain-lifecycle callables.  The 28 file
findings collapse to 26 UNIQUE fingerprints (> the 25-fingerprint batch
cap), so the batch was SPLIT by callable groups:

* ``GR-08c1-seed.yml`` -- the occurrence/expense-link lifecycle group
  (linkExpenseToOccurrence, reconcileExpenseLinkAfterUpdate,
  unlinkExpenseFromOccurrenceDetailed, updateOccurrenceStatus;
  10 findings / 10 unique fingerprints; ZERO closure rows -- the blind-spot
  sweep found every mutating DAO call in the file is an abstract
  Room-annotated method already covered by a finding);
* ``GR-08c2-seed.yml`` -- the reminder-delivery lifecycle group
  (regenerateReminderDeliveriesForOccurrence,
  recoverStaleClaimedDeliveries, claimReminderDelivery,
  cancelClaimedReminderDelivery, markReminderSent, markReminderFailed,
  dismissReminderDelivery, snoozeReminderDelivery;
  18 findings / 16 unique fingerprints);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the FOUR reviewed batch seed files (5 + 13 + 10 + 16 =
  44 rows) -- a dropped earlier-batch row fails closed here instead of
  silently re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08c1/c2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08d (MIT-DB-08D) extends the same contract to the
ReviewQueueRepository.kt repository-layer callables:

* ``GR-08d-seed.yml`` -- 22 rows: the 19 findings-derived rows (27
  findings / 19 unique fingerprints, within the 25-fingerprint batch cap so
  NO split was required) PLUS 3 closure rows for body-carrying
  @Transaction PendingReviewDao convenience methods the findings scanner
  never reported (``upsertByRawNotificationId`` in markAsRelevant,
  ``bulkUpdateCategoryByMerchant`` in updatePendingReviewCategoryBulk,
  ``bulkRenameMerchant`` in updatePendingReviewMerchantBulk -- the GR-08b
  blind-spot pattern);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the FIVE reviewed batch seed files (5 + 13 + 10 + 16 +
  22 = 66 rows) -- a dropped earlier-batch row fails closed here instead of
  silently re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08d rows, including the closure rows
  (wrong overload / owner / DAO / operation stay unauthorized).

Authored coverage; execution pending in this environment.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

# ``policy_v2_candidate`` uses in-package relative imports, so everything
# must be imported as ``scripts...`` with the worktree root on ``sys.path``.
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from scripts.ci.promote_db_policy_v2 import (  # noqa: E402
    _collect_accounting_mutation_keys,
)
from scripts.db_guard.policy_model import (  # noqa: E402
    BarrierMode,
    CallableKind,
    PolicyEntry,
    match_mutation,
)
from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    ResolvedRow,
    build_accounting_artifact,
    seed_record_from_entry,
)
from scripts.db_guard.policy_v2_loader import build_policy_entry  # noqa: E402
from scripts.migrate_db_policy_signatures import (  # noqa: E402
    _candidate_document,
    _load_seed_entries,
    _reject_seed_duplicates,
    _verify_candidate_accounting_pair,
)

SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08a-seed.yml"

PIPELINE_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "NotificationProcessingPipeline.kt"
)
PIPELINE_FQCN = (
    "com.yourname.expensetracker.data.repository.NotificationProcessingPipeline"
)
RAW_NOTIFICATION = (
    "com.yourname.expensetracker.data.database.entity.RawNotification"
)
PRE_DB_CONTEXT = PIPELINE_FQCN + ".PreDbContext"
DEFERRED_DIAG = PIPELINE_FQCN + ".DeferredSourceLinkDiagnostic"
AUTO_ACCEPT_PARAMS = (
    RAW_NOTIFICATION,
    "Long",
    PRE_DB_CONTEXT,
    "Long",
    "String?",
    "MutableList<" + DEFERRED_DIAG + ">",
)


def _seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08a-shaped v2 seed row mapping."""
    return {
        "path": PIPELINE_KT,
        "ownerFqcn": PIPELINE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08a EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08A",
    }


def _gr08a_seed_rows():
    """The five exact GR-08a rows (mirroring the tracked seed file)."""
    return [
        _seed_row(
            "detectAndSaveSubscriptionCandidate",
            "subscriptionCandidateDao",
            "com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao",
            "insert",
            ("String", "Long?"),
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "dao",
            "com.yourname.expensetracker.data.database.dao.RawNotificationDao",
            "markProcessed",
            AUTO_ACCEPT_PARAMS,
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "dao",
            "com.yourname.expensetracker.data.database.dao.RawNotificationDao",
            "markRelevance",
            AUTO_ACCEPT_PARAMS,
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "sourceStatsDao",
            "com.yourname.expensetracker.data.database.dao.SourceStatsDao",
            "incrementTotalAndAccepted",
            AUTO_ACCEPT_PARAMS,
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "sourceStatsDao",
            "com.yourname.expensetracker.data.database.dao.SourceStatsDao",
            "incrementTotalAndDuplicate",
            AUTO_ACCEPT_PARAMS,
        ),
    ]


def _write_seed_doc(tmp_path: Path, rows, name="seeds.yml") -> Path:
    seed_path = tmp_path / name
    seed_path.write_text(
        yaml.safe_dump(
            {"schemaVersion": 2, "entries": rows},
            sort_keys=False,
            allow_unicode=False,
        ),
        encoding="utf-8",
    )
    return seed_path


def _legacy_entry():
    """One schema-valid legacy-resolved-shaped v2 entry (synthetic)."""
    return PolicyEntry(
        path="app/src/main/java/com/example/Repository.kt",
        owner_fqcn="com.example.Repository",
        kind=CallableKind.FUNCTION,
        method="save",
        receiver=None,
        parameter_types=("Int",),
        dao_accessor="expenseDao",
        dao_fqcn="com.example.ExpenseDao",
        operation="insert",
        barrier_mode=BarrierMode.HELPER,
        reason="controlled migration reason",
        owner="expense-owners",
        linked_issue="ISSUE-100",
    )


def _legacy_result():
    """A minimal resolved MigrationResult-shaped stand-in."""

    row = ResolvedRow(0, _legacy_entry())

    class _Result:
        resolved = (row,)
        unresolved = ()
        input_count = 1
        emission_indices = ()

    return _Result()


def _accounting_for(result, candidate_entries, seed_entries=()):
    return build_accounting_artifact(
        result,
        candidate_entries,
        source_policy_path="config/guards/db_ownership_policy.legacy.yml",
        source_policy_sha256="a" * 64,
        source_tree_sha="b" * 64,
        candidate_sha256=None,
        source_mutations=(),
        seed_entries=seed_entries,
    )


# ── (1) Seed loading through the ordinary v2 loader ──────────────────────────


def test_real_tracked_seed_file_loads_with_exactly_five_rows():
    entries = _load_seed_entries(SEED_FILE)
    assert len(entries) == 5
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["detectAndSaveSubscriptionCandidate"]
        + ["handleAutoAcceptInTransaction"] * 4
    )
    for entry in entries:
        assert entry.path == PIPELINE_KT
        assert entry.owner_fqcn == PIPELINE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08A"


def test_seed_document_with_duplicate_keys_fails_closed(tmp_path):
    rows = _gr08a_seed_rows()
    rows.append(rows[0])
    seed_path = _write_seed_doc(tmp_path, rows)
    try:
        _load_seed_entries(seed_path)
    except Exception as exc:
        assert "seed rows file is not a valid v2 policy document" in str(exc)
    else:
        raise AssertionError("duplicate seed keys must fail closed")


def test_malformed_seed_document_fails_closed(tmp_path):
    seed_path = tmp_path / "bad.yml"
    seed_path.write_text(
        "schemaVersion: 2\nentries:\n- path: only-a-path\n", encoding="utf-8"
    )
    try:
        _load_seed_entries(seed_path)
    except Exception as exc:
        assert "seed rows file is not a valid v2 policy document" in str(exc)
    else:
        raise AssertionError("malformed seed document must fail closed")


# ── (2) Seed/legacy duplicate rejection ──────────────────────────────────────


def test_seed_colliding_with_legacy_key_fails_closed(tmp_path):
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    colliding_row = {
        "path": legacy_entry.path,
        "ownerFqcn": legacy_entry.owner_fqcn,
        "kind": legacy_entry.kind.value,
        "method": legacy_entry.method,
        "receiver": legacy_entry.receiver,
        "parameterTypes": list(legacy_entry.parameter_types),
        "daoAccessor": legacy_entry.dao_accessor,
        "daoFqcn": legacy_entry.dao_fqcn,
        "operation": legacy_entry.operation,
        "barrierMode": legacy_entry.barrier_mode.value,
        "reason": "seed shadowing a legacy row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08A",
    }
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, [colliding_row])
    )
    try:
        _reject_seed_duplicates(result, seed_entries)
    except Exception as exc:
        assert "duplicate a legacy-resolved candidate mutation key" in str(exc)
    else:
        raise AssertionError("seed/legacy key collision must fail closed")


def test_disjoint_seed_keys_pass_duplicate_rejection(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    _reject_seed_duplicates(_legacy_result(), seed_entries)  # must not raise


# ── (3) Candidate merge + accounting crosswalk ───────────────────────────────


def test_seeded_candidate_document_merges_and_sorts_deterministically(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    document = _candidate_document(result, seed_entries)
    assert document["schemaVersion"] == 2
    entries = document["entries"]
    assert len(entries) == 1 + len(seed_entries)
    keys = [
        (
            item["path"],
            item["ownerFqcn"],
            item["method"],
            item["daoAccessor"],
            item["operation"],
        )
        for item in entries
    ]
    assert keys == sorted(keys)
    # Every seed row is present verbatim.
    seeded = [
        (item["method"], item["daoAccessor"], item["operation"])
        for item in entries
    ]
    for entry in seed_entries:
        assert (entry.method, entry.dao_accessor, entry.operation) in seeded


def test_accounting_artifact_carries_seed_records_and_crosswalks(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    artifact = _accounting_for(result, [legacy_entry], seed_entries=seed_entries)
    payload = artifact.to_dict()
    assert len(payload["seedRecords"]) == len(seed_entries)
    seed_keys = {record["key"] for record in payload["seedRecords"]}
    assert seed_keys == {
        entry.mutation_key().canonical_key() for entry in seed_entries
    }
    record_keys = {
        key for record in payload["records"] for key in record["mutationKeys"]
    }
    assert seed_keys.isdisjoint(record_keys)


def test_seedless_accounting_artifact_stays_byte_identical():
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    payload = _accounting_for(result, [legacy_entry]).to_dict()
    assert "seedRecords" not in payload


def test_duplicate_seed_keys_rejected_by_accounting_builder(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    try:
        _accounting_for(
            result,
            [legacy_entry],
            seed_entries=seed_entries + [seed_entries[0]],
        )
    except ValueError as exc:
        assert "duplicate mutation keys" in str(exc)
    else:
        raise AssertionError("duplicate seed keys must be rejected")


def test_seed_key_colliding_with_legacy_record_rejected_by_accounting(tmp_path):
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    colliding_seed = PolicyEntry(
        path=legacy_entry.path,
        owner_fqcn=legacy_entry.owner_fqcn,
        kind=legacy_entry.kind,
        method=legacy_entry.method,
        receiver=legacy_entry.receiver,
        parameter_types=legacy_entry.parameter_types,
        dao_accessor=legacy_entry.dao_accessor,
        dao_fqcn=legacy_entry.dao_fqcn,
        operation=legacy_entry.operation,
        barrier_mode=BarrierMode.HELPER,
        reason="seed shadowing a legacy row",
        owner="@panospao7",
        linked_issue="MIT-DB-08A",
    )
    try:
        _accounting_for(result, [legacy_entry], seed_entries=[colliding_seed])
    except ValueError as exc:
        assert "duplicates a legacy record key" in str(exc)
    else:
        raise AssertionError("seed/legacy key collision must be rejected")


def test_rendered_seeded_candidate_verifies_against_accounting_pair(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    document = _candidate_document(result, seed_entries)
    candidate_text = yaml.safe_dump(
        document, sort_keys=False, allow_unicode=False
    ).replace("\r\n", "\n")
    artifact_payload = _accounting_for(
        result, [legacy_entry], seed_entries=seed_entries
    )
    artifact_payload["candidateSha256"] = None
    # Must not raise: the rendered candidate's FULL key set (legacy + seeds)
    # equals the accounting records' keys union the seedRecords keys.
    _verify_candidate_accounting_pair(candidate_text, artifact_payload)


def test_rendered_candidate_missing_seed_key_fails_pair_verification(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    document = _candidate_document(result, seed_entries)
    candidate_text = yaml.safe_dump(
        document, sort_keys=False, allow_unicode=False
    ).replace("\r\n", "\n")
    artifact_payload = _accounting_for(result, [legacy_entry])
    artifact_payload["candidateSha256"] = None
    try:
        _verify_candidate_accounting_pair(candidate_text, artifact_payload)
    except Exception as exc:
        assert "candidate and accounting artifacts disagree" in str(exc)
    else:
        raise AssertionError(
            "candidate keys absent from accounting must fail pair verification"
        )


# ── (4) Promotion gate: accounting key union includes seed keys ──────────────


def test_promotion_gate_unions_seed_record_keys():
    document = {
        "records": [{"index": 0, "mutationKeys": ["legacy|key"]}],
        "inputCount": 1,
        "seedRecords": [
            {"key": "seed|key|a"},
            {"key": "seed|key|b"},
        ],
    }
    union, input_count, problem = _collect_accounting_mutation_keys(document)
    assert problem is None
    assert input_count == 1
    assert union == {"legacy|key", "seed|key|a", "seed|key|b"}


def test_promotion_gate_malformed_seed_records_fail_closed():
    document = {
        "records": [{"index": 0, "mutationKeys": ["legacy|key"]}],
        "inputCount": 1,
        "seedRecords": ["not-a-mapping"],
    }
    union, input_count, problem = _collect_accounting_mutation_keys(document)
    assert union is None and input_count is None
    assert problem == "DB_PROMOTE_ACCOUNTING_MALFORMED"


def test_promotion_gate_without_seed_section_unchanged():
    document = {
        "records": [{"index": 0, "mutationKeys": ["legacy|key"]}],
        "inputCount": 1,
    }
    union, input_count, problem = _collect_accounting_mutation_keys(document)
    assert problem is None
    assert union == {"legacy|key"}


# ── (5) NEAR-MISS protection over the GR-08a rows ────────────────────────────
#
# The scanner authorizes a finding only when EVERY identity field matches
# exactly (``match_mutation``, no wildcards).  Each test mutates exactly one
# field of a real GR-08a row and asserts the mutation stays unauthorized.


def _gr08a_policy_entries(tmp_path):
    rows = _gr08a_seed_rows()
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08a fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_exact_match(tmp_path, **overrides):
    """The exact GR-08a auto-accept identity matches; mutants never do."""
    entries = _gr08a_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "handleAutoAcceptInTransaction"
        and entry.dao_accessor == "dao"
        and entry.operation == "markProcessed"
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_exact_identity_matches(tmp_path):
    assert _assert_exact_match(tmp_path) is True


def test_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    entries = _gr08a_policy_entries(tmp_path)
    target = entries[1]  # handleAutoAcceptInTransaction / dao / markProcessed
    wrong_overload = target.parameter_types[:-1] + ("String?",)
    assert (
        match_mutation(
            target,
            path=target.path,
            owner_fqcn=target.owner_fqcn,
            kind=target.kind,
            method=target.method,
            receiver=target.receiver,
            parameter_types=wrong_overload,
            dao_accessor=target.dao_accessor,
            dao_fqcn=target.dao_fqcn,
            operation=target.operation,
        )
        is False
    )


def test_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    assert (
        _assert_exact_match(tmp_path, owner_fqcn="com.example.OtherPipeline")
        is False
    )


def test_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    assert (
        _assert_exact_match(
            tmp_path,
            dao_accessor="sourceStatsDao",
            dao_fqcn=(
                "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
            ),
        )
        is False
    )


def test_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    assert _assert_exact_match(tmp_path, operation="markRelevance") is False


def test_near_miss_wrong_path_stays_unauthorized(tmp_path):
    assert (
        _assert_exact_match(
            tmp_path, path="app/src/main/java/com/example/Copy.kt"
        )
        is False
    )


def test_seed_record_from_entry_round_trips_key(tmp_path):
    entries = _gr08a_policy_entries(tmp_path)
    for entry in entries:
        record = seed_record_from_entry(entry)
        assert record.key == entry.mutation_key().canonical_key()
        assert record.path == entry.path
        assert record.barrier_mode == entry.barrier_mode.value
        assert record.linked_issue == entry.linked_issue


# ── (6) GR-08b rows: tracked seed files + NEAR-MISS protection ────────────────
#
# GR-08b authorizes the three remaining NotificationProcessingPipeline.kt
# callables (processInternal, handleNeedsReviewInTransaction,
# insertRawNotificationIfNotDuplicate; 30 findings / 11 unique fingerprints).
# The migration CLI accepts a SINGLE --seed-rows value, so the generation run
# consumes the COMBINED document GR-08-seeds.yml; these tests pin that the
# combined document stays the exact concatenation of the two reviewed batch
# seed files, and that the GR-08b rows authorize EXACTLY their callable
# identity + DAO + operation (wrong overload, wrong owner, wrong DAO, and
# wrong operation stay unauthorized).

GR08B_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08b-seed.yml"
COMBINED_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08-seeds.yml"

RAW_NOTIFICATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.RawNotificationDao"
)
SOURCE_STATS_DAO = (
    "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
)
PENDING_REVIEW_DAO = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
PERSISTENCE_CONTEXT = (
    "com.yourname.expensetracker.domain.notification."
    "NotificationPersistenceContext?"
)
PROCESS_INTERNAL_PARAMS = (
    RAW_NOTIFICATION,
    RAW_NOTIFICATION,
    "Boolean",
    "String?",
    PERSISTENCE_CONTEXT,
)
NEEDS_REVIEW_PARAMS = AUTO_ACCEPT_PARAMS
INSERT_RAW_PARAMS = (RAW_NOTIFICATION, RAW_NOTIFICATION)


def _gr08b_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08b-shaped v2 seed row mapping."""
    return {
        "path": PIPELINE_KT,
        "ownerFqcn": PIPELINE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08b EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08B",
    }


def _gr08b_seed_rows():
    """The thirteen exact GR-08b rows (mirroring the tracked seed file).

    Eleven findings-derived rows plus the two GR-08a-alias-bridge closure
    rows (``pendingReviewDao.upsertByRawNotificationId`` in processInternal
    and handleNeedsReviewInTransaction).
    """
    rows = []
    for operation in ("markProcessed", "markRelevance"):
        rows.append(
            _gr08b_seed_row(
                "processInternal",
                "dao",
                RAW_NOTIFICATION_DAO,
                operation,
                PROCESS_INTERNAL_PARAMS,
            )
        )
    for operation in (
        "incrementTotalAndAutoRejected",
        "incrementTotalAndDuplicate",
        "incrementTotalAndPending",
        "insertIfNotExists",
    ):
        rows.append(
            _gr08b_seed_row(
                "processInternal",
                "sourceStatsDao",
                SOURCE_STATS_DAO,
                operation,
                PROCESS_INTERNAL_PARAMS,
            )
        )
    rows.append(
        _gr08b_seed_row(
            "processInternal",
            "pendingReviewDao",
            PENDING_REVIEW_DAO,
            "upsertByRawNotificationId",
            PROCESS_INTERNAL_PARAMS,
        )
    )
    for operation in ("markProcessed", "markRelevance"):
        rows.append(
            _gr08b_seed_row(
                "handleNeedsReviewInTransaction",
                "dao",
                RAW_NOTIFICATION_DAO,
                operation,
                NEEDS_REVIEW_PARAMS,
            )
        )
    for operation in ("incrementTotalAndDuplicate", "incrementTotalAndPending"):
        rows.append(
            _gr08b_seed_row(
                "handleNeedsReviewInTransaction",
                "sourceStatsDao",
                SOURCE_STATS_DAO,
                operation,
                NEEDS_REVIEW_PARAMS,
            )
        )
    rows.append(
        _gr08b_seed_row(
            "handleNeedsReviewInTransaction",
            "pendingReviewDao",
            PENDING_REVIEW_DAO,
            "upsertByRawNotificationId",
            NEEDS_REVIEW_PARAMS,
        )
    )
    rows.append(
        _gr08b_seed_row(
            "insertRawNotificationIfNotDuplicate",
            "dao",
            RAW_NOTIFICATION_DAO,
            "insertOrIgnore",
            INSERT_RAW_PARAMS,
        )
    )
    return rows


def _entry_fields(entry):
    """Field-exact identity of a loaded seed entry (verbatim comparison)."""
    return (
        entry.path,
        entry.owner_fqcn,
        entry.kind,
        entry.method,
        entry.receiver,
        tuple(entry.parameter_types),
        entry.dao_accessor,
        entry.dao_fqcn,
        entry.operation,
        entry.barrier_mode,
        entry.reason,
        entry.owner,
        entry.linked_issue,
    )


def test_real_tracked_gr08b_seed_file_loads_with_exactly_thirteen_rows():
    entries = _load_seed_entries(GR08B_SEED_FILE)
    assert len(entries) == 13
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["processInternal"] * 7
        + ["handleNeedsReviewInTransaction"] * 5
        + ["insertRawNotificationIfNotDuplicate"]
    )
    for entry in entries:
        assert entry.path == PIPELINE_KT
        assert entry.owner_fqcn == PIPELINE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08B"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The closure rows: pendingReviewDao.upsertByRawNotificationId in both
    # multi-mutation callables (GR-08a alias-bridge evidence closure).
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.dao_accessor == "pendingReviewDao"
    )
    assert closure == [
        ("handleNeedsReviewInTransaction", "pendingReviewDao",
         "upsertByRawNotificationId"),
        ("processInternal", "pendingReviewDao",
         "upsertByRawNotificationId"),
    ]


# ── (7) GR-08c1/c2 rows: tracked seed files + concatenation + NEAR-MISS ───────
#
# GR-08c authorizes the RecurringLifecycleCoordinator.kt domain-lifecycle
# callables (28 findings / 26 unique fingerprints > the 25-fingerprint
# batch cap, hence the GR-08c1/c2 split by callable groups).  The migration
# CLI accepts a SINGLE --seed-rows value, so every generation run consumes
# the COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the FOUR reviewed batch seed
# files, and that the GR-08c1/c2 rows authorize EXACTLY their callable
# identity + DAO + operation (wrong overload, wrong owner, wrong DAO, and
# wrong operation stay unauthorized).

GR08C1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08c1-seed.yml"
GR08C2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08c2-seed.yml"

COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "lifecycle/RecurringLifecycleCoordinator.kt"
)
COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringLifecycleCoordinator"
)
OCCURRENCE_DAO = (
    "com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao"
)
REMINDER_DELIVERY_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "RecurringReminderDeliveryDao"
)
PLANNED_EXPENSE_DAO = (
    "com.yourname.expensetracker.data.database.dao.PlannedExpenseDao"
)
LIFECYCLE_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao"
)
OCCURRENCE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.RecurringOccurrence"
)
OCCURRENCE_STATUS = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringOccurrenceStatus"
)
TRANSITION_REASON = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringOccurrenceTransitionReason"
)
LINK_PARAMS = ("Long",)
RECONCILE_PARAMS = ("Long", "String")
REGENERATE_PARAMS = (OCCURRENCE_ENTITY, "Long", "List<String>")
RECOVER_PARAMS = ("String", "String")
CLAIM_PARAMS = ("Long",)
MARK_SENT_PARAMS = ("Long", "Int")
DISMISS_PARAMS = ("Long",)
SNOOZE_PARAMS = ("Long", "Long")


def _gr08c_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08c-shaped v2 seed row mapping."""
    return {
        "path": COORDINATOR_KT,
        "ownerFqcn": COORDINATOR_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08c EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08C",
    }


def _gr08c1_seed_rows():
    """The ten exact GR-08c1 rows (mirroring the tracked seed file).

    The occurrence/expense-link lifecycle group; ZERO closure rows (the
    blind-spot sweep found every mutating DAO call in the file is an
    abstract Room-annotated method already covered by a finding).
    """
    rows = []
    for accessor, dao, operation in (
        ("occurrenceDao", OCCURRENCE_DAO, "claimForExpense"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO, "linkToActualExpense"),
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "suppressOpenDeliveriesForOccurrence",
        ),
    ):
        rows.append(
            _gr08c_seed_row(
                "linkExpenseToOccurrence", accessor, dao, operation,
                LINK_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "occurrenceDao",
            OCCURRENCE_DAO,
            "updateLinkedPaymentSnapshot",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "reconcileExpenseLinkAfterUpdate", accessor, dao, operation,
                RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("occurrenceDao", OCCURRENCE_DAO, "update"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO, "unlinkActualExpense"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "unlinkExpenseFromOccurrenceDetailed", accessor, dao,
                operation, RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("occurrenceDao", OCCURRENCE_DAO, "updateStatus"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "updateOccurrenceStatus", accessor, dao, operation,
                ("Long", OCCURRENCE_STATUS, TRANSITION_REASON),
            )
        )
    return rows


def _gr08c2_seed_rows():
    """The sixteen exact GR-08c2 rows (mirroring the tracked seed file).

    The reminder-delivery lifecycle group; ZERO closure rows.
    """
    rows = []
    for accessor, dao, operation in (
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "reopenDeliveryForOccurrenceWindow",
        ),
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "regenerateReminderDeliveriesForOccurrence", accessor, dao,
                operation, REGENERATE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "recoverStaleClaimedDeliveries",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "recoverStaleClaimedDeliveries", accessor, dao, operation,
                RECOVER_PARAMS,
            )
        )
    rows.append(
        _gr08c_seed_row(
            "claimReminderDelivery", "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO, "claimDelivery", CLAIM_PARAMS,
        )
    )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "cancelClaimedDelivery",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "cancelClaimedReminderDelivery", accessor, dao, operation,
                RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "markSentFromClaimed",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "markReminderSent", accessor, dao, operation,
                MARK_SENT_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "markFailedFromClaimed",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "markReminderFailed", accessor, dao, operation,
                RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "dismissReminderDelivery", accessor, dao, operation,
                DISMISS_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "snoozeReminderDelivery", accessor, dao, operation,
                SNOOZE_PARAMS,
            )
        )
    return rows


def test_real_tracked_gr08c1_seed_file_loads_with_exactly_ten_rows():
    entries = _load_seed_entries(GR08C1_SEED_FILE)
    assert len(entries) == 10
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["linkExpenseToOccurrence"] * 3
        + ["reconcileExpenseLinkAfterUpdate"] * 2
        + ["unlinkExpenseFromOccurrenceDetailed"] * 3
        + ["updateOccurrenceStatus"] * 2
    )
    for entry in entries:
        assert entry.path == COORDINATOR_KT
        assert entry.owner_fqcn == COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08C"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


def test_real_tracked_gr08c2_seed_file_loads_with_exactly_sixteen_rows():
    entries = _load_seed_entries(GR08C2_SEED_FILE)
    assert len(entries) == 16
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["regenerateReminderDeliveriesForOccurrence"] * 3
        + ["recoverStaleClaimedDeliveries"] * 2
        + ["claimReminderDelivery"]
        + ["cancelClaimedReminderDelivery"] * 2
        + ["markReminderSent"] * 2
        + ["markReminderFailed"] * 2
        + ["dismissReminderDelivery"] * 2
        + ["snoozeReminderDelivery"] * 2
    )
    for entry in entries:
        assert entry.path == COORDINATOR_KT
        assert entry.owner_fqcn == COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08C"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


# ── (8) GR-08d rows: tracked seed file + concatenation + NEAR-MISS ────────────
#
# GR-08d authorizes the ReviewQueueRepository.kt repository-layer callables
# (27 findings / 19 unique fingerprints, within the 25-fingerprint batch cap
# so NO split was required).  The migration CLI accepts a SINGLE --seed-rows
# value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the FIVE reviewed batch seed files, and that the
# GR-08d rows authorize EXACTLY their callable identity + DAO + operation
# (wrong overload, wrong owner, wrong DAO, and wrong operation stay
# unauthorized), including the 3 closure rows for body-carrying
# @Transaction PendingReviewDao convenience methods.

GR08D_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08d-seed.yml"

REVIEW_QUEUE_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ReviewQueueRepository.kt"
)
REVIEW_QUEUE_FQCN = (
    "com.yourname.expensetracker.data.repository.ReviewQueueRepository"
)
PENDING_REVIEW_DAO_GR08D = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
RAW_NOTIFICATION_DAO_GR08D = (
    "com.yourname.expensetracker.data.database.dao.RawNotificationDao"
)
SOURCE_STATS_DAO_GR08D = (
    "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
)
TRANSACTION_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.TransactionEventDao"
)
USER_CORRECTION_DAO = (
    "com.yourname.expensetracker.data.database.dao.UserCorrectionDao"
)
TRANSACTION_TYPE = (
    "com.yourname.expensetracker.data.database.entity.TransactionType?"
)
TRANSFER_DIRECTION = (
    "com.yourname.expensetracker.data.database.entity.TransferDirection?"
)
APPROVE_PARAMS = (
    "Long",
    "Double?",
    "String?",
    "String?",
    "Long?",
    "Long?",
    TRANSACTION_TYPE,
    TRANSFER_DIRECTION,
    "String?",
    "Boolean",
    "Double?",
    "Double?",
    "String?",
    "String?",
)
MARK_RELEVANT_PARAMS = ("Long", "Boolean")
REJECT_PARAMS = ("Long",)
RECOVER_PARAMS_GR08D: tuple = ()
CATEGORY_BULK_PARAMS = ("String", "Long")
MERCHANT_BULK_PARAMS = ("String", "String")


def _gr08d_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08d-shaped v2 seed row mapping."""
    return {
        "path": REVIEW_QUEUE_KT,
        "ownerFqcn": REVIEW_QUEUE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08d EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08D",
    }


def _gr08d_seed_rows():
    """The twenty-two exact GR-08d rows (mirroring the tracked seed file).

    Nineteen findings-derived rows plus the 3 closure rows for
    body-carrying @Transaction PendingReviewDao convenience methods
    (upsertByRawNotificationId, bulkUpdateCategoryByMerchant,
    bulkRenameMerchant).
    """
    rows = []
    for accessor, dao, operation in (
        ("pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "transitionStatus"),
        ("pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "updateStatus"),
        ("rawNotificationDao", RAW_NOTIFICATION_DAO_GR08D, "markRelevance"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementAccepted"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "decrementPending"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementDuplicate"),
        ("transactionEventDao", TRANSACTION_EVENT_DAO, "insert"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "insert"),
    ):
        rows.append(
            _gr08d_seed_row(
                "approveReview", accessor, dao, operation, APPROVE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "transitionStatus"),
        ("rawNotificationDao", RAW_NOTIFICATION_DAO_GR08D, "markRelevance"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementRejected"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "decrementPending"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "insert"),
    ):
        rows.append(
            _gr08d_seed_row(
                "rejectReview", accessor, dao, operation, REJECT_PARAMS,
            )
        )
    rows.append(
        _gr08d_seed_row(
            "recoverStuckReviews",
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "recoverStuckProcessing",
            RECOVER_PARAMS_GR08D,
        )
    )
    for accessor, dao, operation in (
        ("rawNotificationDao", RAW_NOTIFICATION_DAO_GR08D, "markRelevance"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementAccepted"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementDuplicate"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementPending"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "insert"),
        # Closure row: body-carrying @Transaction convenience method.
        (
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "upsertByRawNotificationId",
        ),
    ):
        rows.append(
            _gr08d_seed_row(
                "markAsRelevant", accessor, dao, operation,
                MARK_RELEVANT_PARAMS,
            )
        )
    # Closure rows: body-carrying @Transaction convenience methods.
    rows.append(
        _gr08d_seed_row(
            "updatePendingReviewCategoryBulk",
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "bulkUpdateCategoryByMerchant",
            CATEGORY_BULK_PARAMS,
        )
    )
    rows.append(
        _gr08d_seed_row(
            "updatePendingReviewMerchantBulk",
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "bulkRenameMerchant",
            MERCHANT_BULK_PARAMS,
        )
    )
    return rows


def test_real_tracked_gr08d_seed_file_loads_with_exactly_twenty_two_rows():
    entries = _load_seed_entries(GR08D_SEED_FILE)
    assert len(entries) == 22
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["approveReview"] * 8
        + ["rejectReview"] * 5
        + ["recoverStuckReviews"]
        + ["markAsRelevant"] * 6
        + ["updatePendingReviewCategoryBulk"]
        + ["updatePendingReviewMerchantBulk"]
    )
    for entry in entries:
        assert entry.path == REVIEW_QUEUE_KT
        assert entry.owner_fqcn == REVIEW_QUEUE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08D"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The closure rows: the three body-carrying @Transaction
    # PendingReviewDao convenience methods (GR-08b blind-spot pattern).
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.dao_accessor == "pendingReviewDao"
        and entry.operation
        in (
            "upsertByRawNotificationId",
            "bulkUpdateCategoryByMerchant",
            "bulkRenameMerchant",
        )
    )
    assert closure == [
        ("markAsRelevant", "pendingReviewDao",
         "upsertByRawNotificationId"),
        ("updatePendingReviewCategoryBulk", "pendingReviewDao",
         "bulkUpdateCategoryByMerchant"),
        ("updatePendingReviewMerchantBulk", "pendingReviewDao",
         "bulkRenameMerchant"),
    ]


def test_combined_seed_file_concatenates_all_five_batch_seed_files():
    """Drift guard: generation input == GR-08a + GR-08b + GR-08c1 + GR-08c2
    + GR-08d.

    Supersedes the GR-08c-era four-file concatenation test (which pinned the
    combined document at 44 rows): the GR-08d batch extends the combined
    generation input to 66 rows, and the drift guard must cover ALL FIVE
    reviewed batch seed files.  The combined document is what --seed-rows
    actually consumes; if it ever drifts from the five reviewed batch seed
    files (a dropped earlier-batch row would silently re-unauthorize that
    batch's mutations at promotion time), this fails closed.
    """
    combined = _load_seed_entries(COMBINED_SEED_FILE)
    gr08a = _load_seed_entries(SEED_FILE)
    gr08b = _load_seed_entries(GR08B_SEED_FILE)
    gr08c1 = _load_seed_entries(GR08C1_SEED_FILE)
    gr08c2 = _load_seed_entries(GR08C2_SEED_FILE)
    gr08d = _load_seed_entries(GR08D_SEED_FILE)
    assert len(gr08a) == 5
    assert len(gr08b) == 13
    assert len(gr08c1) == 10
    assert len(gr08c2) == 16
    assert len(gr08d) == 22
    assert len(combined) == 66
    combined_fields = sorted(_entry_fields(entry) for entry in combined)
    batch_fields = sorted(
        _entry_fields(entry)
        for entry in list(gr08a) + list(gr08b) + list(gr08c1) + list(gr08c2)
        + list(gr08d)
    )
    assert combined_fields == batch_fields
    keys = [entry.mutation_key().canonical_key() for entry in combined]
    assert len(set(keys)) == len(keys)


def _gr08c_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08c fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08c_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08c row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08c_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08c1_exact_identity_matches(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense",
        )
        is True
    )


def test_gr08c1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense", parameter_types=("Long", "Long"),
        )
        is False
    )


def test_gr08c1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense", owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08c1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense",
            dao_accessor="reminderDeliveryDao",
            dao_fqcn=REMINDER_DELIVERY_DAO,
        )
        is False
    )


def test_gr08c1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense", operation="update",
        )
        is False
    )


def test_gr08c1_update_status_row_near_misses_stay_unauthorized(tmp_path):
    """The typed-status rows are exact too: sibling shapes never match."""
    rows = _gr08c1_seed_rows()
    base_kwargs = dict(
        select_method="updateOccurrenceStatus",
        select_accessor="occurrenceDao",
        select_operation="updateStatus",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: the two-parameter legacy status shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=("Long", "String")),
        )
        is False
    )
    # Wrong operation: the plain Room update spelling.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong DAO: the lifecycle-event accessor behind the same callable.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(
                base_kwargs,
                dao_accessor="lifecycleEventDao",
                dao_fqcn=LIFECYCLE_EVENT_DAO,
            ),
        )
        is False
    )


def test_gr08c2_exact_identity_matches(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery",
        )
        is True
    )


def test_gr08c2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery", parameter_types=("Long", "Long"),
        )
        is False
    )


def test_gr08c2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery", owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08c2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery",
            dao_accessor="lifecycleEventDao",
            dao_fqcn=LIFECYCLE_EVENT_DAO,
        )
        is False
    )


def test_gr08c2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery", operation="update",
        )
        is False
    )


def test_gr08c2_regenerate_rows_near_misses_stay_unauthorized(tmp_path):
    """The regenerate rows are exact too: sibling shapes never match.

    The three lifecycleEventDao.insert call sites share ONE fingerprint, so
    the seed carries exactly one row for them; a wrong parameter shape (the
    reconcile callable's (Long, String)) or a wrong DAO behind the same
    accessor spelling stays unauthorized.
    """
    rows = _gr08c2_seed_rows()
    base_kwargs = dict(
        select_method="regenerateReminderDeliveriesForOccurrence",
        select_accessor="lifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: the two-parameter reconcile shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=("Long", "String")),
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(
                base_kwargs,
                dao_accessor="reminderDeliveryDao",
                dao_fqcn=REMINDER_DELIVERY_DAO,
            ),
        )
        is False
    )
    # Wrong callable: the sibling recover-stale insert never matches the
    # regenerate identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="recoverStaleClaimedDeliveries")
        )
        is False
    )


def _gr08b_policy_entries(tmp_path):
    rows = _gr08b_seed_rows()
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08b fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08b_exact_match(tmp_path, **overrides):
    """The exact GR-08b processInternal identity matches; mutants never do."""
    entries = _gr08b_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "processInternal"
        and entry.dao_accessor == "dao"
        and entry.operation == "markProcessed"
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08b_exact_identity_matches(tmp_path):
    assert _assert_gr08b_exact_match(tmp_path) is True


def test_gr08b_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    entries = _gr08b_policy_entries(tmp_path)
    target = entries[0]  # processInternal / dao / markProcessed
    wrong_overload = target.parameter_types[:-1] + ("String?",)
    assert (
        match_mutation(
            target,
            path=target.path,
            owner_fqcn=target.owner_fqcn,
            kind=target.kind,
            method=target.method,
            receiver=target.receiver,
            parameter_types=wrong_overload,
            dao_accessor=target.dao_accessor,
            dao_fqcn=target.dao_fqcn,
            operation=target.operation,
        )
        is False
    )


def test_gr08b_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    assert (
        _assert_gr08b_exact_match(
            tmp_path, owner_fqcn="com.example.OtherPipeline"
        )
        is False
    )


def test_gr08b_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    assert (
        _assert_gr08b_exact_match(
            tmp_path,
            dao_accessor="sourceStatsDao",
            dao_fqcn=SOURCE_STATS_DAO,
        )
        is False
    )


def test_gr08b_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    assert (
        _assert_gr08b_exact_match(tmp_path, operation="markRelevance")
        is False
    )


def test_gr08b_insert_row_near_misses_stay_unauthorized(tmp_path):
    """The insertOrIgnore row is exact too: sibling shapes never match."""
    entries = _gr08b_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "insertRawNotificationIfNotDuplicate"
    ][0]
    base = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    assert match_mutation(target, **base) is True
    # Wrong overload: the single-parameter legacy insert shape.
    assert (
        match_mutation(
            target, **dict(base, parameter_types=(RAW_NOTIFICATION,))
        )
        is False
    )
    # Wrong operation: the plain Room insert spelling.
    assert match_mutation(target, **dict(base, operation="insert")) is False
    # Wrong DAO: the stats accessor.
    assert (
        match_mutation(
            target,
            **dict(
                base,
                dao_accessor="sourceStatsDao",
                dao_fqcn=SOURCE_STATS_DAO,
            ),
        )
        is False
    )


def test_gr08b_closure_row_near_misses_stay_unauthorized(tmp_path):
    """The pendingReviewDao closure rows are exact too: mutants never match.

    The closure rows authorize EXACTLY
    ``pendingReviewDao.upsertByRawNotificationId`` on their own callable
    identity; a wrong operation, a wrong DAO identity behind the same
    accessor spelling, or the sibling callable's shape stays unauthorized.
    """
    entries = _gr08b_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "processInternal"
        and entry.dao_accessor == "pendingReviewDao"
    ][0]
    base = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    assert match_mutation(target, **base) is True
    # Wrong operation: the plain Room insert spelling.
    assert match_mutation(target, **dict(base, operation="insert")) is False
    # Wrong DAO identity behind the accessor spelling.
    assert (
        match_mutation(
            target, **dict(base, dao_fqcn=RAW_NOTIFICATION_DAO)
        )
        is False
    )
    # Wrong callable: the sibling needs-review closure row never matches
    # the processInternal identity.
    assert (
        match_mutation(
            target, **dict(base, method="handleNeedsReviewInTransaction")
        )
        is False
    )


# ── (9) GR-08d NEAR-MISS protection ───────────────────────────────────────────
#
# The GR-08d rows authorize EXACTLY their callable identity + DAO +
# operation.  Each test mutates exactly one identity field of a real GR-08d
# row and asserts the mutation stays unauthorized.  The closure rows get the
# same exactness treatment (GR-08b closure precedent).


def test_gr08d_exact_identity_matches(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus",
        )
        is True
    )


def test_gr08d_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus", parameter_types=("Long",),
        )
        is False
    )


def test_gr08d_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus",
            owner_fqcn="com.example.OtherRepository",
        )
        is False
    )


def test_gr08d_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus",
            dao_accessor="rawNotificationDao",
            dao_fqcn=RAW_NOTIFICATION_DAO_GR08D,
        )
        is False
    )


def test_gr08d_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus", operation="updateStatus",
        )
        is False
    )


def test_gr08d_recover_stuck_row_near_misses_stay_unauthorized(tmp_path):
    """The zero-parameter recovery row is exact too: siblings never match."""
    rows = _gr08d_seed_rows()
    base_kwargs = dict(
        select_method="recoverStuckReviews",
        select_accessor="pendingReviewDao",
        select_operation="recoverStuckProcessing",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: a synthetic single-parameter shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong operation: the plain status-update spelling behind the same DAO.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="updateStatus")
        )
        is False
    )
    # Wrong callable: the sibling rejectReview transition row never matches
    # the recovery identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="rejectReview")
        )
        is False
    )


def test_gr08d_closure_rows_near_misses_stay_unauthorized(tmp_path):
    """The three closure rows are exact too: mutants never match.

    The closure rows authorize EXACTLY the body-carrying @Transaction
    PendingReviewDao convenience methods on their own callable identities;
    a wrong operation (the plain Room insert/update spellings behind the
    convenience bodies), a wrong DAO identity behind the same accessor
    spelling, a wrong overload, or a sibling callable's shape stays
    unauthorized.
    """
    rows = _gr08d_seed_rows()

    # (1) markAsRelevant / pendingReviewDao / upsertByRawNotificationId.
    upsert_kwargs = dict(
        select_method="markAsRelevant",
        select_accessor="pendingReviewDao",
        select_operation="upsertByRawNotificationId",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **upsert_kwargs) is True
    # Wrong operation: the plain Room insert behind the convenience body.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(upsert_kwargs, operation="insert")
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(
                upsert_kwargs,
                dao_accessor="rawNotificationDao",
                dao_fqcn=RAW_NOTIFICATION_DAO_GR08D,
            ),
        )
        is False
    )
    # Wrong overload: the rejectReview (Long) shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(upsert_kwargs, parameter_types=("Long",)),
        )
        is False
    )

    # (2) updatePendingReviewCategoryBulk / bulkUpdateCategoryByMerchant.
    category_kwargs = dict(
        select_method="updatePendingReviewCategoryBulk",
        select_accessor="pendingReviewDao",
        select_operation="bulkUpdateCategoryByMerchant",
    )
    assert (
        _assert_gr08c_exact_match(tmp_path, rows, **category_kwargs) is True
    )
    # Wrong operation: the abstract by-key update behind the convenience
    # body never matches the convenience-method identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(
                category_kwargs,
                operation="bulkUpdateCategoryByMerchantKey",
            ),
        )
        is False
    )
    # Wrong overload: the merchant-rename (String, String) shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(category_kwargs, parameter_types=("String", "String")),
        )
        is False
    )
    # Wrong callable: the sibling bulk-rename closure row never matches.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(
                category_kwargs, method="updatePendingReviewMerchantBulk"
            ),
        )
        is False
    )

    # (3) updatePendingReviewMerchantBulk / bulkRenameMerchant.
    merchant_kwargs = dict(
        select_method="updatePendingReviewMerchantBulk",
        select_accessor="pendingReviewDao",
        select_operation="bulkRenameMerchant",
    )
    assert (
        _assert_gr08c_exact_match(tmp_path, rows, **merchant_kwargs) is True
    )
    # Wrong operation: the abstract by-key rename behind the convenience
    # body never matches the convenience-method identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(merchant_kwargs, operation="bulkRenameMerchantByKey"),
        )
        is False
    )
    # Wrong overload: the category-bulk (String, Long) shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(merchant_kwargs, parameter_types=("String", "Long")),
        )
        is False
    )
    # Wrong owner: a copied repository class never matches.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(merchant_kwargs, owner_fqcn="com.example.CopyRepository"),
        )
        is False
    )
