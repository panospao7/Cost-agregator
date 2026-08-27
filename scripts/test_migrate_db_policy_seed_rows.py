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
