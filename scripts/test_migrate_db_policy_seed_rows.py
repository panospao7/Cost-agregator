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

GR-08e (MIT-DB-08E) extends the same contract to the two repository-layer
files NotificationRepository.kt and WarrantyTrackerRepository.kt.  The
combined batch carries 46 findings / 46 unique fingerprints > the
25-fingerprint batch cap, so the batch was SPLIT per the GR-08c precedent:

* ``GR-08e1-seed.yml`` -- NotificationRepository.kt: 23 rows (23 findings /
  23 unique fingerprints; ZERO closure rows -- every mutating DAO call in
  the file is an abstract Room-annotated method);
* ``GR-08e2-seed.yml`` -- WarrantyTrackerRepository.kt: 23 rows (23
  findings / 23 unique fingerprints; ZERO closure rows -- WarrantyDao,
  ReturnWindowDao and WarrantyLifecycleEventDao are fully abstract);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the SEVEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 = 112 rows) -- a dropped earlier-batch
  row fails closed here instead of silently re-unauthorizing that batch's
  mutations at promotion;
* NEAR-MISS protection over the GR-08e1/e2 rows, including the
  accessor-normalized rows (the GR-08e source change replaced the
  database-chained ``database.xxxDao()`` receivers with injected
  constructor properties because no chain-form spelling can pass both the
  scanner gate and the v2 evidence verifier; the seed rows spell the
  normalized ``transactionEventDao`` / ``warrantyLifecycleEventDao``
  accessors, and a wrong accessor spelling -- including the historical
  chain text -- stays unauthorized).

GR-08f (MIT-DB-08F) extends the same contract to the
RecurringRuleLifecycleCoordinator.kt domain-lifecycle callables -- the MOST
authoritative writer layer (recurring rule mutations MUST go through it per
docs/architecture/LEGAL_PATHS.md):

* ``GR-08f-seed.yml`` -- 21 rows: the 21 findings-derived rows (21
  findings / 21 unique fingerprints, within the 25-fingerprint batch cap so
  NO split was required); ZERO closure rows -- the blind-spot sweep found
  every mutating DAO call in the file is an abstract Room-annotated method
  already covered by a finding (all five mutated DAOs are fully abstract
  interfaces; the sixth accessor, expenseDao, is called only read-only via
  getExpensesBetween);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the EIGHT reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 = 133 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08f rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08g (MIT-DB-08G) extends the same contract to the
BankStatementLifecycleProcessor.kt receipt-lifecycle callables -- the
AUTHORITATIVE writer layer for bank statement imports (receipt mutations go
through the receipt lifecycle services per docs/architecture/LEGAL_PATHS.md,
"Bank Statement Mutations": everything happens in
processBankStatement(); BankStatementImportItemDao.insert /
BankStatementImportRunDao.insertRun outside the processor are FORBIDDEN):

* ``GR-08g-seed.yml`` -- 7 rows: the 20 findings collapse to 7 UNIQUE
  fingerprints (all sites live in the single mutating callable
  processBankStatement(android.net.Uri): bankStatementImportItemDao.insert
  x8, bankStatementImportRunDao.finalize x6,
  bankStatementImportRunDao.attachReceipt x2, and one site each for
  bankStatementImportRunDao.insert, bankStatementImportRunDao.updatePdfPartial,
  pendingReviewDao.insert, scannedReceiptDao.update), within the
  25-fingerprint batch cap so NO split was required; ZERO closure rows --
  the blind-spot sweep found every mutating DAO call in the file is an
  abstract Room-annotated method already covered by a finding
  (BankStatementImportRunDao, BankStatementImportItemDao and
  ScannedReceiptDao are fully abstract interfaces; the two body-carrying
  @Transaction convenience methods REACHED from the file --
  ExpenseDao.findDuplicateIdCurrencyAware and
  PendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware -- are
  strictly read-only composites, and PendingReviewDao's MUTATING
  convenience methods are not called from this file);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the NINE reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 = 140 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08g rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08h (MIT-DB-08H) extends the same contract to the
ReceiptMatchLifecycleService.kt receipt-lifecycle callables -- the
AUTHORITATIVE writer for match mutations (docs/architecture/LEGAL_PATHS.md,
"MATCH receipt (suggest/approve/reject/clear)":
ReceiptMatchLifecycleService.saveMatchSuggestion() /
approveMatchSuggestion() / rejectAllSuggestions() / clearMatchForReceipt();
"Each operation: DatabaseWriteBarrier check -> withTransaction ->
ReceiptEvent"; FORBIDDEN: ReceiptRepository.saveMatchSuggestion()
[DeprecationLevel.ERROR] and "Any match mutation without ReceiptEvent"):

* ``GR-08h-seed.yml`` -- 13 rows: the 13 findings collapse to 13 UNIQUE
  fingerprints (each per-call-site finding is its own tuple: the four
  match-mutation callables each carry exactly one scannedReceiptDao.update
  + one receiptEventDao.insert site, and the five P9-P1-08/PR12L-3
  diagnostics writers each carry exactly one receiptEventDao.insert site),
  within the 25-fingerprint batch cap so NO split was required; ZERO
  closure rows -- the blind-spot sweep found every mutating DAO call in the
  file is an abstract Room-annotated method already covered by a finding
  (ReceiptEventDao is a fully abstract interface with exactly two methods
  and ScannedReceiptDao is likewise fully abstract -- NEITHER carries a
  body-carrying @Transaction convenience method at all; the only other
  DAO-accessor calls are the nine read-only scannedReceiptDao.getById
  lookups, and the database.withTransaction calls are the
  androidx.room.withTransaction extension, not DAO accessors);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the TEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 = 153 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08h rows (wrong overload / owner /
  DAO / operation stay unauthorized).

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


def test_combined_seed_file_concatenates_all_nine_batch_seed_files():
    """Drift guard: generation input == GR-08a + GR-08b + GR-08c1 + GR-08c2
    + GR-08d + GR-08e1 + GR-08e2 + GR-08f + GR-08g.

    Supersedes the GR-08f-era eight-file concatenation test (which pinned
    the combined document at 133 rows): the GR-08g batch extends the
    combined generation input to 140 rows, and the drift guard must cover
    ALL NINE reviewed batch seed files.  The combined document is what
    --seed-rows actually consumes; if it ever drifts from the nine
    reviewed batch seed files (a dropped earlier-batch row would silently
    re-unauthorize that batch's mutations at promotion time), this fails
    closed.
    """
    combined = _load_seed_entries(COMBINED_SEED_FILE)
    gr08a = _load_seed_entries(SEED_FILE)
    gr08b = _load_seed_entries(GR08B_SEED_FILE)
    gr08c1 = _load_seed_entries(GR08C1_SEED_FILE)
    gr08c2 = _load_seed_entries(GR08C2_SEED_FILE)
    gr08d = _load_seed_entries(GR08D_SEED_FILE)
    gr08e1 = _load_seed_entries(GR08E1_SEED_FILE)
    gr08e2 = _load_seed_entries(GR08E2_SEED_FILE)
    gr08f = _load_seed_entries(GR08F_SEED_FILE)
    gr08g = _load_seed_entries(GR08G_SEED_FILE)
    assert len(gr08a) == 5
    assert len(gr08b) == 13
    assert len(gr08c1) == 10
    assert len(gr08c2) == 16
    assert len(gr08d) == 22
    assert len(gr08e1) == 23
    assert len(gr08e2) == 23
    assert len(gr08f) == 21
    assert len(gr08g) == 7
    assert len(combined) == 140
    combined_fields = sorted(_entry_fields(entry) for entry in combined)
    batch_fields = sorted(
        _entry_fields(entry)
        for entry in list(gr08a) + list(gr08b) + list(gr08c1) + list(gr08c2)
        + list(gr08d) + list(gr08e1) + list(gr08e2) + list(gr08f)
        + list(gr08g)
    )
    assert combined_fields == batch_fields
    keys = [entry.mutation_key().canonical_key() for entry in combined]
    assert len(set(keys)) == len(keys)


def test_combined_seed_file_concatenates_all_ten_batch_seed_files():
    """Drift guard: generation input == GR-08a + GR-08b + GR-08c1 + GR-08c2
    + GR-08d + GR-08e1 + GR-08e2 + GR-08f + GR-08g + GR-08h.

    Supersedes the GR-08g-era nine-file concatenation test (which pinned
    the combined document at 140 rows): the GR-08h batch extends the
    combined generation input to 153 rows, and the drift guard must cover
    ALL TEN reviewed batch seed files.  The combined document is what
    --seed-rows actually consumes; if it ever drifts from the ten
    reviewed batch seed files (a dropped earlier-batch row would silently
    re-unauthorize that batch's mutations at promotion time), this fails
    closed.
    """
    combined = _load_seed_entries(COMBINED_SEED_FILE)
    gr08a = _load_seed_entries(SEED_FILE)
    gr08b = _load_seed_entries(GR08B_SEED_FILE)
    gr08c1 = _load_seed_entries(GR08C1_SEED_FILE)
    gr08c2 = _load_seed_entries(GR08C2_SEED_FILE)
    gr08d = _load_seed_entries(GR08D_SEED_FILE)
    gr08e1 = _load_seed_entries(GR08E1_SEED_FILE)
    gr08e2 = _load_seed_entries(GR08E2_SEED_FILE)
    gr08f = _load_seed_entries(GR08F_SEED_FILE)
    gr08g = _load_seed_entries(GR08G_SEED_FILE)
    gr08h = _load_seed_entries(GR08H_SEED_FILE)
    assert len(gr08a) == 5
    assert len(gr08b) == 13
    assert len(gr08c1) == 10
    assert len(gr08c2) == 16
    assert len(gr08d) == 22
    assert len(gr08e1) == 23
    assert len(gr08e2) == 23
    assert len(gr08f) == 21
    assert len(gr08g) == 7
    assert len(gr08h) == 13
    assert len(combined) == 153
    combined_fields = sorted(_entry_fields(entry) for entry in combined)
    batch_fields = sorted(
        _entry_fields(entry)
        for entry in list(gr08a) + list(gr08b) + list(gr08c1) + list(gr08c2)
        + list(gr08d) + list(gr08e1) + list(gr08e2) + list(gr08f)
        + list(gr08g) + list(gr08h)
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


# ── (10) GR-08e1/e2 rows: tracked seed files + concatenation + NEAR-MISS ──────
#
# GR-08e authorizes the two repository-layer files NotificationRepository.kt
# and WarrantyTrackerRepository.kt (46 findings / 46 unique fingerprints >
# the 25-fingerprint batch cap, hence the GR-08e1/e2 split).  The migration
# CLI accepts a SINGLE --seed-rows value, so every generation run consumes
# the COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the SEVEN reviewed batch seed
# files, and that the GR-08e1/e2 rows authorize EXACTLY their callable
# identity + DAO + operation (wrong overload, wrong owner, wrong DAO, and
# wrong operation stay unauthorized).  The accessor-normalized rows (the
# GR-08e source change replaced the database-chained receivers with injected
# constructor properties) get explicit near-miss coverage: the historical
# chain-text accessor spelling stays unauthorized.

GR08E1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08e1-seed.yml"
GR08E2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08e2-seed.yml"

NOTIFICATION_REPO_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "NotificationRepository.kt"
)
NOTIFICATION_REPO_FQCN = (
    "com.yourname.expensetracker.data.repository.NotificationRepository"
)
WARRANTY_REPO_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "WarrantyTrackerRepository.kt"
)
WARRANTY_REPO_FQCN = (
    "com.yourname.expensetracker.data.repository.WarrantyTrackerRepository"
)
BLOCKED_PACKAGE_DAO = (
    "com.yourname.expensetracker.data.database.dao.BlockedPackageDao"
)
EXPENSE_DAO_GR08E = (
    "com.yourname.expensetracker.data.database.dao.ExpenseDao"
)
WARRANTY_DAO = "com.yourname.expensetracker.data.database.dao.WarrantyDao"
RETURN_WINDOW_DAO = (
    "com.yourname.expensetracker.data.database.dao.ReturnWindowDao"
)
WARRANTY_LIFECYCLE_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao"
)
RAW_NOTIFICATION_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.RawNotification"
)
DEBUG_SNAPSHOT = NOTIFICATION_REPO_FQCN + ".DebugNotificationsSnapshot"
SOURCE_STATS_LIST = (
    "List<com.yourname.expensetracker.data.database.entity.SourceStats>"
)
WARRANTY_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.Warranty"
)
RETURN_WINDOW_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ReturnWindow"
)
SCANNED_RECEIPT_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ScannedReceipt"
)
WARRANTY_EXTRACTION_RESULT = (
    "com.yourname.expensetracker.domain.ai.model.WarrantyExtractionResult"
)
DELETE_ALL_NOTIFICATIONS_PARAMS = ("Long",)
DELETE_ALL_PARAMS: tuple = ()
RESTORE_SNAPSHOT_PARAMS = (DEBUG_SNAPSHOT,)
RESTORE_STATS_PARAMS = (SOURCE_STATS_LIST,)
WARRANTY_PARAMS = (WARRANTY_ENTITY,)
RETURN_WINDOW_PARAMS = (RETURN_WINDOW_ENTITY,)
MARK_AS_RETURNED_PARAMS = ("Long", "Double?", "String?")
UPSERT_RETURN_WINDOW_PARAMS = ("Long", WARRANTY_ENTITY + "?")
TO_WARRANTY_ENTITY_PARAMS = (SCANNED_RECEIPT_ENTITY,)


def _gr08e_seed_row(path, owner_fqcn, method, dao_accessor, dao_fqcn,
                    operation, params):
    """One exact GR-08e-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08e EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08E",
    }


def _gr08e1_seed_rows():
    """The twenty-three exact GR-08e1 rows (mirroring the tracked seed file).

    NotificationRepository.kt; ZERO closure rows.  The
    deleteAllNotifications/transactionEventDao row spells the NORMALIZED
    accessor (the GR-08e source change replaced the database-chained
    receiver with an injected constructor property).
    """
    rows = []
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "save", "dao",
            RAW_NOTIFICATION_DAO, "insert", (RAW_NOTIFICATION_ENTITY,),
        )
    )
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "blockPackage",
            "blockedPackageDao", BLOCKED_PACKAGE_DAO, "block", ("String",),
        )
    )
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "unblockPackage",
            "blockedPackageDao", BLOCKED_PACKAGE_DAO, "unblock", ("String",),
        )
    )
    for accessor, dao, operation in (
        ("sourceStatsDao", SOURCE_STATS_DAO, "decrementPending"),
        ("pendingReviewDao", PENDING_REVIEW_DAO, "deleteByRawId"),
        ("dao", RAW_NOTIFICATION_DAO, "delete"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "delete",
                accessor, dao, operation, (RAW_NOTIFICATION_ENTITY,),
            )
        )
    for accessor, dao, operation in (
        ("transactionEventDao", TRANSACTION_EVENT_DAO, "insert"),
        ("dao", RAW_NOTIFICATION_DAO, "deleteAll"),
        ("pendingReviewDao", PENDING_REVIEW_DAO, "deleteAll"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "resetAllPendingCounts"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN,
                "deleteAllNotifications", accessor, dao, operation,
                DELETE_ALL_NOTIFICATIONS_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("dao", RAW_NOTIFICATION_DAO, "deleteAll"),
        ("expenseDao", EXPENSE_DAO_GR08E, "deleteAll"),
        ("pendingReviewDao", PENDING_REVIEW_DAO, "deleteAll"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "resetAllPendingCounts"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "deleteAll",
                accessor, dao, operation, DELETE_ALL_PARAMS,
            )
        )
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "resetSourceStats",
            "sourceStatsDao", SOURCE_STATS_DAO, "deleteAll",
            DELETE_ALL_PARAMS,
        )
    )
    for accessor, dao, operation in (
        ("dao", RAW_NOTIFICATION_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "deleteAll"),
        ("dao", RAW_NOTIFICATION_DAO, "insertAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "insertAll"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN,
                "restoreDebugSnapshot", accessor, dao, operation,
                RESTORE_SNAPSHOT_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("sourceStatsDao", SOURCE_STATS_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "insertAll"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN,
                "restoreSourceStatsSnapshot", accessor, dao, operation,
                RESTORE_STATS_PARAMS,
            )
        )
    return rows


def _gr08e2_seed_rows():
    """The twenty-three exact GR-08e2 rows (mirroring the tracked seed file).

    WarrantyTrackerRepository.kt; ZERO closure rows.  The eight
    warrantyLifecycleEventDao rows spell the NORMALIZED accessor (the
    GR-08e source change replaced the database-chained receivers with an
    injected constructor property).
    """
    rows = []
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "insertWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "addWarranty",
                accessor, dao, operation, WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "insertWarrantyIgnore"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN,
                "addWarrantyIgnoreConflicts", accessor, dao, operation,
                WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "updateWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "updateWarranty",
                accessor, dao, operation, WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "deleteWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "deleteWarranty",
                accessor, dao, operation, WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("returnWindowDao", RETURN_WINDOW_DAO, "deleteReturnWindow"),
        ("warrantyDao", WARRANTY_DAO, "deleteWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN,
                "rejectAutoDetectedWarranty", accessor, dao, operation,
                WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "updateWarrantyStatus"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "markWarrantyAsClaimed",
                accessor, dao, operation, ("Long",),
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "markExpiredWarranties"),
        ("returnWindowDao", RETURN_WINDOW_DAO, "markExpiredReturnWindows"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "reconcileExpiredItems",
                accessor, dao, operation, ("Long",),
            )
        )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "toWarrantyEntityOrNull",
            "warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO,
            "insert", TO_WARRANTY_ENTITY_PARAMS,
        )
    )
    # Fix the receiver on the extension-function row (the generic row helper
    # leaves it None; the tracked seed file carries the extension receiver).
    rows[-1]["receiver"] = WARRANTY_EXTRACTION_RESULT
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "addReturnWindow",
            "returnWindowDao", RETURN_WINDOW_DAO, "insertReturnWindow",
            RETURN_WINDOW_PARAMS,
        )
    )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "updateReturnWindow",
            "returnWindowDao", RETURN_WINDOW_DAO, "updateReturnWindow",
            RETURN_WINDOW_PARAMS,
        )
    )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "deleteReturnWindow",
            "returnWindowDao", RETURN_WINDOW_DAO, "deleteReturnWindow",
            RETURN_WINDOW_PARAMS,
        )
    )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "markAsReturned",
            "returnWindowDao", RETURN_WINDOW_DAO, "updateReturnWindow",
            MARK_AS_RETURNED_PARAMS,
        )
    )
    for operation in ("insertReturnWindow", "updateReturnWindow"):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN,
                "upsertReturnWindowForReceipt", "returnWindowDao",
                RETURN_WINDOW_DAO, operation, UPSERT_RETURN_WINDOW_PARAMS,
            )
        )
    return rows


def test_real_tracked_gr08e1_seed_file_loads_with_exactly_twenty_three_rows():
    entries = _load_seed_entries(GR08E1_SEED_FILE)
    assert len(entries) == 23
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["save"]
        + ["blockPackage", "unblockPackage"]
        + ["delete"] * 3
        + ["deleteAllNotifications"] * 5
        + ["deleteAll"] * 5
        + ["resetSourceStats"]
        + ["restoreDebugSnapshot"] * 4
        + ["restoreSourceStatsSnapshot"] * 2
    )
    for entry in entries:
        assert entry.path == NOTIFICATION_REPO_KT
        assert entry.owner_fqcn == NOTIFICATION_REPO_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08E"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property or
    # the normalized transactionEventDao property; no convenience methods.
    assert all(
        entry.dao_accessor
        in {
            "dao",
            "blockedPackageDao",
            "expenseDao",
            "pendingReviewDao",
            "userCorrectionDao",
            "sourceStatsDao",
            "transactionEventDao",
        }
        for entry in entries
    )
    # The normalized accessor row: exactly one, on deleteAllNotifications.
    normalized = [
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.dao_accessor == "transactionEventDao"
    ]
    assert normalized == [
        ("deleteAllNotifications", "transactionEventDao", "insert")
    ]


def test_real_tracked_gr08e2_seed_file_loads_with_exactly_twenty_three_rows():
    entries = _load_seed_entries(GR08E2_SEED_FILE)
    assert len(entries) == 23
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["addWarranty"] * 2
        + ["addWarrantyIgnoreConflicts"] * 2
        + ["updateWarranty"] * 2
        + ["deleteWarranty"] * 2
        + ["rejectAutoDetectedWarranty"] * 3
        + ["markWarrantyAsClaimed"] * 2
        + ["reconcileExpiredItems"] * 3
        + ["toWarrantyEntityOrNull"]
        + ["addReturnWindow", "updateReturnWindow", "deleteReturnWindow"]
        + ["markAsReturned"]
        + ["upsertReturnWindowForReceipt"] * 2
    )
    for entry in entries:
        assert entry.path == WARRANTY_REPO_KT
        assert entry.owner_fqcn == WARRANTY_REPO_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08E"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property or
    # the normalized warrantyLifecycleEventDao property.
    assert all(
        entry.dao_accessor
        in {"warrantyDao", "returnWindowDao", "warrantyLifecycleEventDao"}
        for entry in entries
    )
    # The normalized accessor rows: exactly eight, all lifecycle inserts.
    normalized = sorted(
        (entry.method, entry.operation)
        for entry in entries
        if entry.dao_accessor == "warrantyLifecycleEventDao"
    )
    assert normalized == sorted([
        ("addWarranty", "insert"),
        ("addWarrantyIgnoreConflicts", "insert"),
        ("updateWarranty", "insert"),
        ("deleteWarranty", "insert"),
        ("rejectAutoDetectedWarranty", "insert"),
        ("markWarrantyAsClaimed", "insert"),
        ("reconcileExpiredItems", "insert"),
        ("toWarrantyEntityOrNull", "insert"),
    ])
    # The extension-function row carries its receiver identity.
    extension = [
        entry for entry in entries if entry.method == "toWarrantyEntityOrNull"
    ]
    assert len(extension) == 1
    assert extension[0].receiver == WARRANTY_EXTRACTION_RESULT


def _gr08e_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08e fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08e_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08e row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08e_policy_entries(tmp_path, rows)
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


def test_gr08e1_exact_identity_matches(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert"
        )
        is True
    )


def test_gr08e1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            parameter_types=("String",),
        )
        is False
    )


def test_gr08e1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            owner_fqcn="com.example.OtherRepository",
        )
        is False
    )


def test_gr08e1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            dao_accessor="sourceStatsDao",
            dao_fqcn=SOURCE_STATS_DAO,
        )
        is False
    )


def test_gr08e1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert", operation="insertAll"
        )
        is False
    )


def test_gr08e1_normalized_accessor_row_near_misses_stay_unauthorized(tmp_path):
    """The accessor-normalized audit row is exact too: mutants never match.

    The GR-08e source change replaced the database-chained
    ``database.transactionEventDao()`` receiver with the injected
    ``transactionEventDao`` constructor property.  The seed row spells the
    NORMALIZED accessor; the historical chain text, a wrong DAO identity
    behind the normalized spelling, a wrong overload, and the sibling
    deleteAll identity all stay unauthorized.
    """
    rows = _gr08e1_seed_rows()
    base_kwargs = dict(
        select_method="deleteAllNotifications",
        select_accessor="transactionEventDao",
        select_operation="insert",
    )
    assert _assert_gr08e_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong accessor: the historical database-chained spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, dao_accessor="database.transactionEventDao()")
        )
        is False
    )
    # Wrong DAO identity behind the normalized spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, dao_fqcn=SOURCE_STATS_DAO)
        )
        is False
    )
    # Wrong overload: the deprecated zero-parameter deleteAll shape.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=())
        )
        is False
    )
    # Wrong callable: the sibling deleteAll identity never matches.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="deleteAll")
        )
        is False
    )


def test_gr08e2_exact_identity_matches(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty"
        )
        is True
    )


def test_gr08e2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            parameter_types=(RETURN_WINDOW_ENTITY,),
        )
        is False
    )


def test_gr08e2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            owner_fqcn="com.example.OtherRepository",
        )
        is False
    )


def test_gr08e2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            dao_accessor="returnWindowDao",
            dao_fqcn=RETURN_WINDOW_DAO,
        )
        is False
    )


def test_gr08e2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            operation="insertWarrantyIgnore",
        )
        is False
    )


def test_gr08e2_normalized_accessor_rows_near_misses_stay_unauthorized(tmp_path):
    """The accessor-normalized lifecycle rows are exact too: mutants never
    match.

    The GR-08e source change replaced the database-chained
    ``database.warrantyLifecycleEventDao()`` receivers with the injected
    ``warrantyLifecycleEventDao`` constructor property.  The seed rows spell
    the NORMALIZED accessor; the historical chain text, a wrong DAO identity
    behind the normalized spelling, a wrong operation, and the sibling
    callable's shape all stay unauthorized.
    """
    rows = _gr08e2_seed_rows()
    base_kwargs = dict(
        select_method="addWarranty",
        select_accessor="warrantyLifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08e_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong accessor: the historical database-chained spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="database.warrantyLifecycleEventDao()",
            ),
        )
        is False
    )
    # Wrong DAO identity behind the normalized spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, dao_fqcn=WARRANTY_DAO)
        )
        is False
    )
    # Wrong operation: the plain Room insert spelling behind the sibling DAO.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="insertWarranty")
        )
        is False
    )
    # Wrong callable: the sibling addWarrantyIgnoreConflicts lifecycle row
    # never matches the addWarranty identity.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, method="addWarrantyIgnoreConflicts")
        )
        is False
    )


def test_gr08e2_extension_row_near_misses_stay_unauthorized(tmp_path):
    """The extension-function row pins its receiver identity too."""
    rows = _gr08e2_seed_rows()
    base_kwargs = dict(
        select_method="toWarrantyEntityOrNull",
        select_accessor="warrantyLifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08e_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong receiver: a bare function shape never matches the extension.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, receiver=None)
        )
        is False
    )
    # Wrong overload: the addWarranty entity shape.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=(WARRANTY_ENTITY,))
        )
        is False
    )


# ── (11) GR-08f rows: tracked seed file + concatenation + NEAR-MISS ───────────
#
# GR-08f authorizes the RecurringRuleLifecycleCoordinator.kt
# domain-lifecycle callables (21 findings / 21 unique fingerprints, within
# the 25-fingerprint batch cap so NO split was required).  The migration
# CLI accepts a SINGLE --seed-rows value, so every generation run consumes
# the COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the EIGHT reviewed batch seed
# files, and that the GR-08f rows authorize EXACTLY their callable identity
# + DAO + operation (wrong overload, wrong owner, wrong DAO, and wrong
# operation stay unauthorized).

GR08F_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08f-seed.yml"

RULE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "lifecycle/RecurringRuleLifecycleCoordinator.kt"
)
RULE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringRuleLifecycleCoordinator"
)
MANUAL_RECURRING_EXPENSE_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "ManualRecurringExpenseDao"
)
MANUAL_RECURRING_EXPENSE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity."
    "ManualRecurringExpense"
)
RULE_ID_PARAMS = ("Long",)
ADVANCE_PARAMS = ("Long", "Long")
RULE_ENTITY_PARAMS = (MANUAL_RECURRING_EXPENSE_ENTITY,)


def _gr08f_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08f-shaped v2 seed row mapping."""
    return {
        "path": RULE_COORDINATOR_KT,
        "ownerFqcn": RULE_COORDINATOR_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08f EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08F",
    }


def _gr08f_seed_rows():
    """The twenty-one exact GR-08f rows (mirroring the tracked seed file).

    ZERO closure rows: the blind-spot sweep found every mutating DAO call
    in the file is an abstract Room-annotated method already covered by a
    finding (all five mutated DAOs are fully abstract interfaces; the
    sixth accessor, expenseDao, is called only read-only via
    getExpensesBetween).
    """
    rows = []
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "setActiveStatus"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "activateRule", accessor, dao, operation, RULE_ID_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "updateNextDate"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "advanceNextDate", accessor, dao, operation, ADVANCE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "insert"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "createRule", accessor, dao, operation, RULE_ENTITY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "setActiveStatus"),
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "deleteByOccurrenceIds"),
        ("occurrenceDao", OCCURRENCE_DAO, "deleteOpenPlannedBySource"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO,
         "deleteOpenPlannedByRecurringRuleId"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "deactivateRule", accessor, dao, operation, RULE_ID_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "deleteByOccurrenceIds"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO,
         "deleteByRecurringRuleId"),
        ("occurrenceDao", OCCURRENCE_DAO, "deleteBySource"),
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "deleteById"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "deleteRule", accessor, dao, operation, RULE_ID_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "deleteByOccurrenceIds"),
        ("occurrenceDao", OCCURRENCE_DAO, "deleteOpenPlannedBySource"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO,
         "deleteOpenPlannedByRecurringRuleId"),
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "updateRule", accessor, dao, operation, RULE_ENTITY_PARAMS,
            )
        )
    return rows


def test_real_tracked_gr08f_seed_file_loads_with_exactly_twenty_one_rows():
    entries = _load_seed_entries(GR08F_SEED_FILE)
    assert len(entries) == 21
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["activateRule"] * 2
        + ["advanceNextDate"] * 2
        + ["createRule"] * 2
        + ["deactivateRule"] * 5
        + ["deleteRule"] * 5
        + ["updateRule"] * 5
    )
    for entry in entries:
        assert entry.path == RULE_COORDINATOR_KT
        assert entry.owner_fqcn == RULE_COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08F"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {
            "manualRecurringExpenseDao",
            "occurrenceDao",
            "reminderDeliveryDao",
            "plannedExpenseDao",
            "lifecycleEventDao",
        }
        for entry in entries
    )
    # The lifecycle-event provenance rows: exactly six, one per callable.
    provenance = sorted(
        entry.method
        for entry in entries
        if entry.dao_accessor == "lifecycleEventDao"
    )
    assert provenance == [
        "activateRule",
        "advanceNextDate",
        "createRule",
        "deactivateRule",
        "deleteRule",
        "updateRule",
    ]


def _gr08f_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08f fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08f_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08f row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08f_policy_entries(tmp_path, rows)
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


def test_gr08f_exact_identity_matches(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus",
        )
        is True
    )


def test_gr08f_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus", parameter_types=("Long", "Boolean"),
        )
        is False
    )


def test_gr08f_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus",
            owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08f_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus",
            dao_accessor="occurrenceDao",
            dao_fqcn=OCCURRENCE_DAO,
        )
        is False
    )


def test_gr08f_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus", operation="update",
        )
        is False
    )


def test_gr08f_advance_row_near_misses_stay_unauthorized(tmp_path):
    """The two-parameter advance row is exact too: siblings never match."""
    rows = _gr08f_seed_rows()
    base_kwargs = dict(
        select_method="advanceNextDate",
        select_accessor="manualRecurringExpenseDao",
        select_operation="updateNextDate",
    )
    assert _assert_gr08f_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: the single-parameter rule-id shape.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong operation: the plain Room update spelling behind the same DAO.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong callable: the sibling activateRule status row never matches the
    # advance identity.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="activateRule")
        )
        is False
    )


def test_gr08f_entity_rows_near_misses_stay_unauthorized(tmp_path):
    """The ManualRecurringExpense-entity rows are exact too.

    createRule/insert and updateRule/update share the entity parameter
    shape but differ in callable + operation; a swapped operation, a
    swapped callable, or the Long rule-id overload stays unauthorized.
    """
    rows = _gr08f_seed_rows()
    base_kwargs = dict(
        select_method="createRule",
        select_accessor="manualRecurringExpenseDao",
        select_operation="insert",
    )
    assert _assert_gr08f_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the plain entity-update spelling.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong callable: the sibling updateRule entity row never matches the
    # createRule identity.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="updateRule")
        )
        is False
    )
    # Wrong overload: the Long rule-id shape.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong owner: a copied coordinator class never matches.
    assert (
        _assert_gr08f_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, owner_fqcn="com.example.CopyCoordinator"),
        )
        is False
    )


def test_gr08f_provenance_rows_near_misses_stay_unauthorized(tmp_path):
    """The lifecycleEventDao provenance rows are exact per callable.

    All six callables write the same lifecycleEventDao.insert operation;
    each row authorizes EXACTLY its own callable identity, so a sibling
    callable's shape (e.g. deleteRule vs deactivateRule) stays
    unauthorized.
    """
    rows = _gr08f_seed_rows()
    base_kwargs = dict(
        select_method="deleteRule",
        select_accessor="lifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08f_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the sibling deactivateRule provenance row never
    # matches the deleteRule identity.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="deactivateRule")
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08f_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_fqcn=OCCURRENCE_DAO),
        )
        is False
    )
    # Wrong overload: the (Long, Long) advance shape.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=("Long", "Long"))
        )
        is False
    )


# ── (12) GR-08g rows: tracked seed file + concatenation + NEAR-MISS ───────────
#
# GR-08g authorizes the BankStatementLifecycleProcessor.kt receipt-lifecycle
# callable (20 findings / 7 unique fingerprints, within the 25-fingerprint
# batch cap so NO split was required).  The migration CLI accepts a SINGLE
# --seed-rows value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the NINE reviewed batch seed files, and that the
# GR-08g rows authorize EXACTLY their callable identity + DAO + operation
# (wrong overload, wrong owner, wrong DAO, and wrong operation stay
# unauthorized).

GR08G_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08g-seed.yml"

BANK_STATEMENT_PROCESSOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/"
    "lifecycle/BankStatementLifecycleProcessor.kt"
)
BANK_STATEMENT_PROCESSOR_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "BankStatementLifecycleProcessor"
)
BANK_STATEMENT_IMPORT_RUN_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "BankStatementImportRunDao"
)
BANK_STATEMENT_IMPORT_ITEM_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "BankStatementImportItemDao"
)
SCANNED_RECEIPT_DAO = (
    "com.yourname.expensetracker.data.database.dao.ScannedReceiptDao"
)
STATEMENT_URI_PARAMS = ("android.net.Uri",)


def _gr08g_seed_row(dao_accessor, dao_fqcn, operation):
    """One exact GR-08g-shaped v2 seed row mapping."""
    return {
        "path": BANK_STATEMENT_PROCESSOR_KT,
        "ownerFqcn": BANK_STATEMENT_PROCESSOR_FQCN,
        "kind": "function",
        "method": "processBankStatement",
        "receiver": None,
        "parameterTypes": list(STATEMENT_URI_PARAMS),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08g EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08G",
    }


def _gr08g_seed_rows():
    """The seven exact GR-08g rows (mirroring the tracked seed file).

    ZERO closure rows: the blind-spot sweep found every mutating DAO call
    in the file is an abstract Room-annotated method already covered by a
    finding (BankStatementImportRunDao, BankStatementImportItemDao and
    ScannedReceiptDao are fully abstract interfaces; the two body-carrying
    @Transaction convenience methods REACHED from the file --
    ExpenseDao.findDuplicateIdCurrencyAware and
    PendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware -- are
    strictly read-only composites, and PendingReviewDao's MUTATING
    convenience methods are not called from this file).
    """
    return [
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "insert",
        ),
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "attachReceipt",
        ),
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "finalize",
        ),
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "updatePdfPartial",
        ),
        _gr08g_seed_row(
            "bankStatementImportItemDao", BANK_STATEMENT_IMPORT_ITEM_DAO,
            "insert",
        ),
        _gr08g_seed_row(
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "insert",
        ),
        _gr08g_seed_row(
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
    ]


def test_real_tracked_gr08g_seed_file_loads_with_exactly_seven_rows():
    entries = _load_seed_entries(GR08G_SEED_FILE)
    assert len(entries) == 7
    methods = sorted(entry.method for entry in entries)
    assert methods == ["processBankStatement"] * 7
    for entry in entries:
        assert entry.path == BANK_STATEMENT_PROCESSOR_KT
        assert entry.owner_fqcn == BANK_STATEMENT_PROCESSOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.parameter_types == STATEMENT_URI_PARAMS
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08G"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {
            "bankStatementImportRunDao",
            "bankStatementImportItemDao",
            "pendingReviewDao",
            "scannedReceiptDao",
        }
        for entry in entries
    )
    # The run-ledger rows: exactly four distinct operations behind
    # bankStatementImportRunDao.
    run_operations = sorted(
        entry.operation
        for entry in entries
        if entry.dao_accessor == "bankStatementImportRunDao"
    )
    assert run_operations == [
        "attachReceipt",
        "finalize",
        "insert",
        "updatePdfPartial",
    ]


def _gr08g_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08g fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08g_exact_match(tmp_path, rows, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08g row identity matches; mutants never do.

    Target selection is fixed by ``(select_accessor, select_operation)``
    (every row shares the processBankStatement callable identity);
    ``overrides`` perturb exactly one identity field of the match query for
    the near-miss assertions.
    """
    entries = _gr08g_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.dao_accessor == select_accessor
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


def test_gr08g_exact_identity_matches(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
        )
        is True
    )


def test_gr08g_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            parameter_types=("android.net.Uri", "Long"),
        )
        is False
    )


def test_gr08g_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            owner_fqcn="com.example.OtherProcessor",
        )
        is False
    )


def test_gr08g_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            dao_accessor="bankStatementImportItemDao",
            dao_fqcn=BANK_STATEMENT_IMPORT_ITEM_DAO,
        )
        is False
    )


def test_gr08g_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            operation="markStaleFailed",
        )
        is False
    )


def test_gr08g_run_ledger_rows_near_misses_stay_unauthorized(tmp_path):
    """The four bankStatementImportRunDao rows are exact per operation.

    All four rows share the processBankStatement callable identity and the
    run-ledger DAO; a swapped operation, a swapped accessor, or a wrong
    callable name stays unauthorized.
    """
    rows = _gr08g_seed_rows()
    base_kwargs = dict(
        select_accessor="bankStatementImportRunDao",
        select_operation="insert",
    )
    assert _assert_gr08g_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling attachReceipt spelling behind the same
    # DAO.
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="attachReceipt")
        )
        is False
    )
    # Wrong accessor: the per-item ledger DAO never matches the run-ledger
    # identity.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="bankStatementImportItemDao",
                dao_fqcn=BANK_STATEMENT_IMPORT_ITEM_DAO,
            ),
        )
        is False
    )
    # Wrong callable: a copied processor class never matches.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, method="processStatementCopy"),
        )
        is False
    )


def test_gr08g_receipt_status_row_near_misses_stay_unauthorized(tmp_path):
    """The scannedReceiptDao.update row is exact too.

    The receipt status transition shares the processBankStatement callable
    identity with the six ledger/review rows; a swapped DAO, a swapped
    operation (e.g. the abstract insert the processor never calls), or a
    wrong overload stays unauthorized.
    """
    rows = _gr08g_seed_rows()
    base_kwargs = dict(
        select_accessor="scannedReceiptDao",
        select_operation="update",
    )
    assert _assert_gr08g_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the ScannedReceiptDao.insert spelling (the processor
    # delegates receipt creation to ReceiptRecordWriter, it never calls
    # scannedReceiptDao.insert directly).
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="insert")
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_fqcn=BANK_STATEMENT_IMPORT_RUN_DAO),
        )
        is False
    )
    # Wrong overload: a two-parameter shape.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, parameter_types=("android.net.Uri", "Long")),
        )
        is False
    )


# ── (13) GR-08h rows: tracked seed file + concatenation + NEAR-MISS ───────────
#
# GR-08h authorizes the ReceiptMatchLifecycleService.kt receipt-lifecycle
# callables (13 findings / 13 unique fingerprints, within the 25-fingerprint
# batch cap so NO split was required).  The migration CLI accepts a SINGLE
# --seed-rows value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the TEN reviewed batch seed files, and that the
# GR-08h rows authorize EXACTLY their callable identity + DAO + operation
# (wrong overload, wrong owner, wrong DAO, and wrong operation stay
# unauthorized).

GR08H_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08h-seed.yml"

RECEIPT_MATCH_LIFECYCLE_SERVICE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/"
    "lifecycle/ReceiptMatchLifecycleService.kt"
)
RECEIPT_MATCH_LIFECYCLE_SERVICE_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "ReceiptMatchLifecycleService"
)
RECEIPT_EVENT_DAO_GR08H = (
    "com.yourname.expensetracker.data.database.dao.ReceiptEventDao"
)
# SCANNED_RECEIPT_DAO is already defined by the GR-08g section above.

SAVE_MATCH_SUGGESTION_PARAMS = ("Long", "Long", "Double")
SINGLE_RECEIPT_ID_PARAMS = ("Long",)
RECORD_MATCH_ATTEMPTED_PARAMS = ("Long", "Int")
RECORD_MATCH_SKIPPED_PARAMS = ("Long", "String?")
RECORD_AUTO_MATCH_LINK_FAILED_PARAMS = ("Long", "Long?", "String?", "String?")
RECORD_NOTIFICATION_SUPPRESSED_PARAMS = ("Long", "Long?", "String", "String?")


def _gr08h_seed_row(method, parameter_types, dao_accessor, dao_fqcn, operation):
    """One exact GR-08h-shaped v2 seed row mapping."""
    return {
        "path": RECEIPT_MATCH_LIFECYCLE_SERVICE_KT,
        "ownerFqcn": RECEIPT_MATCH_LIFECYCLE_SERVICE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08h EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08H",
    }


def _gr08h_seed_rows():
    """The thirteen exact GR-08h rows (mirroring the tracked seed file).

    ZERO closure rows: the blind-spot sweep found every mutating DAO call
    in the file is an abstract Room-annotated method already covered by a
    finding (ReceiptEventDao is a fully abstract interface with exactly two
    methods and ScannedReceiptDao is likewise fully abstract -- NEITHER
    carries a body-carrying @Transaction convenience method at all; the
    only other DAO-accessor calls are the nine read-only
    scannedReceiptDao.getById lookups, and the database.withTransaction
    calls are the androidx.room.withTransaction extension, not DAO
    accessors).
    """
    return [
        # Match-state transitions (scannedReceiptDao.update, 4 callables).
        _gr08h_seed_row(
            "saveMatchSuggestion", SAVE_MATCH_SUGGESTION_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        _gr08h_seed_row(
            "approveMatchSuggestion", SINGLE_RECEIPT_ID_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        _gr08h_seed_row(
            "rejectAllSuggestions", SINGLE_RECEIPT_ID_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        _gr08h_seed_row(
            "clearMatchForReceipt", SINGLE_RECEIPT_ID_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        # Lifecycle events (receiptEventDao.insert, 9 callables).
        _gr08h_seed_row(
            "saveMatchSuggestion", SAVE_MATCH_SUGGESTION_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "approveMatchSuggestion", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "rejectAllSuggestions", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "clearMatchForReceipt", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordMatchAttempted", RECORD_MATCH_ATTEMPTED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordMatchNotFound", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordMatchSkippedDocumentType", RECORD_MATCH_SKIPPED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordAutoMatchLinkFailed",
            RECORD_AUTO_MATCH_LINK_FAILED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordNotificationSuppressed",
            RECORD_NOTIFICATION_SUPPRESSED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
    ]


def test_real_tracked_gr08h_seed_file_loads_with_exactly_thirteen_rows():
    entries = _load_seed_entries(GR08H_SEED_FILE)
    assert len(entries) == 13
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted([
        "saveMatchSuggestion",
        "saveMatchSuggestion",
        "approveMatchSuggestion",
        "approveMatchSuggestion",
        "rejectAllSuggestions",
        "rejectAllSuggestions",
        "clearMatchForReceipt",
        "clearMatchForReceipt",
        "recordMatchAttempted",
        "recordMatchNotFound",
        "recordMatchSkippedDocumentType",
        "recordAutoMatchLinkFailed",
        "recordNotificationSuppressed",
    ])
    for entry in entries:
        assert entry.path == RECEIPT_MATCH_LIFECYCLE_SERVICE_KT
        assert entry.owner_fqcn == RECEIPT_MATCH_LIFECYCLE_SERVICE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08H"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor in {"scannedReceiptDao", "receiptEventDao"}
        for entry in entries
    )
    # The match-state rows: exactly four scannedReceiptDao.update rows, one
    # per match-mutation callable.
    update_methods = sorted(
        entry.method
        for entry in entries
        if entry.dao_accessor == "scannedReceiptDao"
    )
    assert update_methods == [
        "approveMatchSuggestion",
        "clearMatchForReceipt",
        "rejectAllSuggestions",
        "saveMatchSuggestion",
    ]
    # The event rows: exactly nine receiptEventDao.insert rows, one per
    # event-writing callable.
    insert_methods = sorted(
        entry.method
        for entry in entries
        if entry.dao_accessor == "receiptEventDao"
    )
    assert insert_methods == [
        "approveMatchSuggestion",
        "clearMatchForReceipt",
        "recordAutoMatchLinkFailed",
        "recordMatchAttempted",
        "recordMatchNotFound",
        "recordMatchSkippedDocumentType",
        "recordNotificationSuppressed",
        "rejectAllSuggestions",
        "saveMatchSuggestion",
    ]


def _gr08h_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08h fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08h_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08h row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` (unlike GR-08g, the batch spans NINE callables, so
    the callable name is part of the selection key); ``overrides`` perturb
    exactly one identity field of the match query for the near-miss
    assertions.
    """
    entries = _gr08h_policy_entries(tmp_path, rows)
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


def test_gr08h_exact_identity_matches(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update",
        )
        is True
    )
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "recordNotificationSuppressed",
            "receiptEventDao", "insert",
        )
        is True
    )


def test_gr08h_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update", parameter_types=("Long", "Long"),
        )
        is False
    )


def test_gr08h_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update",
            owner_fqcn="com.example.OtherMatchService",
        )
        is False
    )


def test_gr08h_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update",
            dao_accessor="receiptEventDao",
            dao_fqcn=RECEIPT_EVENT_DAO_GR08H,
        )
        is False
    )


def test_gr08h_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update", operation="insert",
        )
        is False
    )


def test_gr08h_match_state_rows_near_misses_stay_unauthorized(tmp_path):
    """The four scannedReceiptDao.update rows are exact per callable.

    All four rows share the scannedReceiptDao.update DAO identity but
    differ in callable identity (and saveMatchSuggestion differs in
    overload too); a swapped callable, a swapped overload, or the plain
    insert spelling stays unauthorized.
    """
    rows = _gr08h_seed_rows()
    base_kwargs = dict(
        select_method="approveMatchSuggestion",
        select_accessor="scannedReceiptDao",
        select_operation="update",
    )
    assert _assert_gr08h_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the sibling rejectAllSuggestions row never matches
    # the approveMatchSuggestion identity.
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="rejectAllSuggestions")
        )
        is False
    )
    # Wrong overload: the three-parameter saveMatchSuggestion shape.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, parameter_types=("Long", "Long", "Double")),
        )
        is False
    )
    # Wrong operation: the ReceiptEventDao.insert spelling behind the other
    # accessor.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="receiptEventDao",
                dao_fqcn=RECEIPT_EVENT_DAO_GR08H,
                operation="insert",
            ),
        )
        is False
    )


def test_gr08h_diagnostics_rows_near_misses_stay_unauthorized(tmp_path):
    """The five diagnostics receiptEventDao.insert rows are exact per
    callable AND per overload.

    recordMatchAttempted / recordMatchSkippedDocumentType /
    recordAutoMatchLinkFailed / recordNotificationSuppressed all write the
    same receiptEventDao.insert operation; each row authorizes EXACTLY its
    own callable identity + parameter shape, so a sibling callable's shape
    or a perturbed overload stays unauthorized.
    """
    rows = _gr08h_seed_rows()
    base_kwargs = dict(
        select_method="recordMatchAttempted",
        select_accessor="receiptEventDao",
        select_operation="insert",
    )
    assert _assert_gr08h_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the sibling recordMatchNotFound row never matches the
    # recordMatchAttempted identity.
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="recordMatchNotFound")
        )
        is False
    )
    # Wrong overload: the single-parameter recordMatchNotFound shape.
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong overload: the four-parameter recordAutoMatchLinkFailed shape.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                parameter_types=("Long", "Long?", "String?", "String?"),
            ),
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_fqcn=SCANNED_RECEIPT_DAO),
        )
        is False
    )

