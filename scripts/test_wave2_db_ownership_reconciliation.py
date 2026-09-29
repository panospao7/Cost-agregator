"""Exact ownership reconciliation: real source proof plus negative contracts.

No scanner override, fixture policy substitution, exception or skip is used.
The full production pipeline tests remain the acceptance gate.
"""
from __future__ import annotations

from dataclasses import replace
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.verify_db_access_boundaries import load_db_ownership_policy  # noqa: E402
from scripts.db_guard.policy_model import match_mutation  # noqa: E402
from scripts.db_guard.policy_errors import (  # noqa: E402
    DB_V2_POLICY_PARSER_UNCERTAIN,
    DB_V2_POLICY_UNLISTED_MUTATION,
)
from scripts.db_guard.policy_v2_evidence import verify_v2_policy_source_evidence  # noqa: E402
from scripts.db_guard.room_inventory import build_room_inventory  # noqa: E402
from scripts.db_guard.source_roots import resolve_source_root_set  # noqa: E402

PKG = "com.yourname.expensetracker."
ENTITY = PKG + "data.database.entity."
DAO = PKG + "data.database.dao."
SOURCE = "app/src/main/java/com/yourname/expensetracker/"


def _group(owner, method, parameters, mutations, issue, source_file=None):
    return {
        "path": SOURCE + (source_file or owner.replace(".", "/") + ".kt"),
        "owner_fqcn": PKG + owner,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameter_types": parameters,
        "mutations": mutations,
        "issue": issue,
    }


CAPTURE_PARAMETERS = (
    "String", "String?", "String", "String", "Long",
    "String?", "String?", "String?", "String?", "String?",
    PKG + "domain.privacy.RawStorageMode", "String", "String",
)
RETRY_PARAMETERS = (
    "String", "String", "Long", "String", "String?", "String?", "String?", "String?",
    PKG + "domain.notification.capture.DeferredCaptureStorageSnapshot",
)

# Each mutation is (source accessor, exact DAO type, operation, barrier mode).
# These are independently enumerated source/contract expectations, not derived
# by accepting whatever rows the policy currently happens to contain.
GROUPS = (
    _group("data.privacy.DataRetentionWorker", "emitRetentionAudit", (
        PKG + "data.privacy.RetentionCheckpointStore",
        PKG + "data.privacy.RetentionCheckpointRecord",
        DAO + "PrivacyAuditDao", PKG + "domain.workers.WorkerRunContext",
    ), (("auditDao", "PrivacyAuditDao", "insert", "direct"),), "MIT-DB-08P1"),
    _group("data.repository.SubscriptionManagementRepository", "updateSubscriptionCategory",
           ("Long", "String"), (
               ("subscriptionDao", "ManualRecurringExpenseDao", "updateSubscriptionCategory", "direct"),
           ), "MIT-DB-08L"),
    _group("domain.bank.BankApiIntegration", "refreshToken", (ENTITY + "BankConnection",), (
        ("bankConnectionDao", "BankConnectionDao", "updateTokenIfConnected", "helper"),
    ), "MIT-DB-08O"),
    _group("domain.bank.BankConnectionLifecycleCoordinator", "disconnectConnection", ("Long",), (
        ("bankConnectionDao", "BankConnectionDao", "disconnect", "helper"),
        ("pendingReviewDao", "PendingReviewDao", "deleteByBankConnectionScope", "helper"),
    ), "MIT-DB-08P2"),
    _group("domain.bank.BankConnectionLifecycleCoordinator", "persistOutcome",
           ("Long", PKG + "domain.bank.BankSyncOutcome"), (
               ("bankConnectionDao", "BankConnectionDao", "updateSyncStatus", "direct"),
               ("bankConnectionDao", "BankConnectionDao", "updateSyncStatusOnly", "direct"),
           ), "MIT-DB-08P2"),
    _group("domain.notification.capture.NotificationIntakeCoordinator", "capture", CAPTURE_PARAMETERS, (
        ("intakeDao", "NotificationIntakeDao", "insertOrIgnore", "helper"),
        ("intakeDao", "NotificationIntakeDao", "markEnqueueFailed", "helper"),
    ), "MIT-DB-08P1"),
    _group("domain.notification.capture.NotificationIntakeCoordinator", "captureForRetry", RETRY_PARAMETERS, (
        ("intakeDao", "NotificationIntakeDao", "insertOrIgnore", "helper"),
        ("intakeDao", "NotificationIntakeDao", "markEnqueueFailed", "helper"),
        ("intakeDao", "NotificationIntakeDao", "markFinalFailure", "helper"),
    ), "MIT-DB-08P1"),
    _group("domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator", "reconcileUpdateInCurrentTransaction",
           (ENTITY + "ManualRecurringExpense", ENTITY + "ManualRecurringExpense", "Long"), (
               ("manualRecurringExpenseDao", "ManualRecurringExpenseDao", "update", "helper"),
               ("occurrenceDao", "RecurringOccurrenceDao", "deletePlannedByIds", "helper"),
               ("occurrenceDao", "RecurringOccurrenceDao", "update", "helper"),
               ("plannedExpenseDao", "PlannedExpenseDao", "deleteOpenPlannedBySourceKeys", "helper"),
               ("plannedExpenseDao", "PlannedExpenseDao", "updateDerivedSnapshotForKey", "helper"),
               ("reminderDeliveryDao", "RecurringReminderDeliveryDao", "deleteOpenDeliveriesByOccurrenceIds", "helper"),
           ), "MIT-DB-08F"),
    _group("domain.recurring.lifecycle.RecurringLifecycleCoordinator", "markReminderFailed", ("Long", "String"), (
        ("lifecycleEventDao", "RecurringLifecycleEventDao", "insert", "helper"),
        ("reminderDeliveryDao", "RecurringReminderDeliveryDao", "cancelClaimedDelivery", "helper"),
        ("reminderDeliveryDao", "RecurringReminderDeliveryDao", "markFailedFromClaimed", "helper"),
    ), "MIT-DB-08C"),
    _group("service.warranty.WarrantyExpirationWorker", "deliverReminder",
           (ENTITY + "Warranty", "Int", "Long", "String", "String"), (
               ("deliveryDao", "WarrantyReminderDeliveryDao", "claim", "workerMediated"),
               ("deliveryDao", "WarrantyReminderDeliveryDao", "insertOrIgnore", "workerMediated"),
               ("deliveryDao", "WarrantyReminderDeliveryDao", "markFailed", "workerMediated"),
               ("deliveryDao", "WarrantyReminderDeliveryDao", "markFailedByKey", "workerMediated"),
               ("deliveryDao", "WarrantyReminderDeliveryDao", "markSentFromClaimed", "workerMediated"),
           ), "MIT-DB-08M"),
)

# Removing delegation-only/deleted grants must not remove their real owners.
PRESERVED = (
    _group("domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator", "deleteRule", ("Long",), (
        ("lifecycleEventDao", "RecurringLifecycleEventDao", "insert", "helper"),
        ("manualRecurringExpenseDao", "ManualRecurringExpenseDao", "deleteById", "helper"),
        ("occurrenceDao", "RecurringOccurrenceDao", "deleteBySource", "helper"),
        ("plannedExpenseDao", "PlannedExpenseDao", "deleteByRecurringRuleId", "helper"),
        ("reminderDeliveryDao", "RecurringReminderDeliveryDao", "deleteByOccurrenceIds", "helper"),
    ), "MIT-DB-08F"),
    _group("domain.recurring.RecurringPlanProjectionService", "projectFromOccurrencesInCurrentTransaction",
           ("Long", "Long", "Long", "Long"), (
               ("plannedExpenseDao", "PlannedExpenseDao", "insertPlannedExpense", "helper"),
           ), "MIT-DB-08P1"),
    _group("domain.recurring.lifecycle.RoomRecurringLifecycleEventWriter", "writeCritical",
           ("Long?", "String", "String?", "String?", "String?", "Long"), (
               ("dao", "RecurringLifecycleEventDao", "insert", "helper"),
           ), "MIT-DB-08P1", "domain/recurring/lifecycle/RecurringLifecycleEventWriter.kt"),
)


def _id(group):
    return group["owner_fqcn"].rsplit(".", 1)[-1] + "." + group["method"]


def _candidate(group, mutation):
    identity = {key: group[key] for key in (
        "path", "owner_fqcn", "kind", "method", "receiver", "parameter_types",
    )}
    accessor, dao_type, operation, _mode = mutation
    return dict(identity, dao_accessor=accessor, dao_fqcn=DAO + dao_type, operation=operation)


def _rows(entries, group):
    # Deliberately do not filter by parameters: an extra overload grant fails
    # the exact group contract instead of disappearing from the assertion.
    return [entry for entry in entries if entry.path == group["path"]
            and entry.owner_fqcn == group["owner_fqcn"] and entry.method == group["method"]]


def _matches(entries, candidate):
    return [entry for entry in entries if match_mutation(entry, **candidate)]


@pytest.fixture(scope="module")
def policy():
    return tuple(load_db_ownership_policy())


def _assert_exact_group(policy, group):
    rows = _rows(policy, group)
    assert sorted((row.dao_accessor, row.dao_fqcn, row.operation, row.barrier_mode.value)
                  for row in rows) == sorted((accessor, DAO + dao_type, operation, mode)
                                             for accessor, dao_type, operation, mode in group["mutations"])
    for mutation in group["mutations"]:
        matches = _matches(policy, _candidate(group, mutation))
        assert len(matches) == 1, (_id(group), mutation)
        assert matches[0].owner == "@panospao7"
        assert matches[0].linked_issue == group["issue"]
        assert matches[0].reason.strip()


@pytest.mark.parametrize("group", GROUPS, ids=_id)
def test_reconciled_owner_has_only_the_exact_source_contract(policy, group):
    _assert_exact_group(policy, group)


@pytest.mark.parametrize("group", PRESERVED, ids=_id)
def test_delegation_and_deleted_entry_removal_preserve_actual_writers(policy, group):
    _assert_exact_group(policy, group)


MUTATIONS = [(group, mutation) for group in GROUPS for mutation in group["mutations"]]


@pytest.mark.parametrize("group,mutation", MUTATIONS, ids=[
    _id(group) + ":" + mutation[0] + "." + mutation[2] for group, mutation in MUTATIONS
])
def test_reconciled_permissions_reject_every_near_miss_identity(policy, group, mutation):
    candidate = _candidate(group, mutation)
    assert len(_matches(policy, candidate)) == 1
    for field, value in (
        ("path", group["path"].rsplit("/", 1)[-1]),
        ("owner_fqcn", "example.UnrelatedOwner"),
        ("kind", "constructor"),
        ("method", group["method"] + "Unsafe"),
        ("receiver", "String"),
        ("parameter_types", group["parameter_types"] + ("String",)),
        ("dao_accessor", "otherDao"),
        ("dao_fqcn", DAO + "UnrelatedDao"),
        ("operation", mutation[2] + "Unsafe"),
    ):
        assert _matches(policy, dict(candidate, **{field: value})) == [], field


@pytest.mark.parametrize("owner,method", (
    ("data.privacy.DataRetentionWorker", "doWork"),
    ("data.repository.SubscriptionManagementRepository", "deleteSubscriptionById"),
    ("data.repository.SubscriptionManagementRepository", "updateSubscription"),
    ("domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator", "updateRule"),
    ("domain.recurring.RecurringPlanProjectionService", "projectFromRule"),
))
def test_deleted_or_delegating_callable_has_no_residual_dao_grant(policy, owner, method):
    assert [entry for entry in policy
            if entry.owner_fqcn == PKG + owner and entry.method == method] == []


def test_unconditional_bank_token_operation_is_not_authorized(policy):
    candidate = _candidate(GROUPS[2], GROUPS[2]["mutations"][0])
    candidate["operation"] = "updateToken"
    assert _matches(policy, candidate) == []


def test_deferred_capture_without_storage_snapshot_is_not_authorized(policy):
    group = GROUPS[6]
    for mutation in group["mutations"]:
        candidate = _candidate(group, mutation)
        candidate["parameter_types"] = RETRY_PARAMETERS[:-1]
        assert _matches(policy, candidate) == []


@pytest.fixture(scope="module")
def real_source_contract():
    roots, diagnostics = resolve_source_root_set(str(ROOT))
    assert roots is not None and not diagnostics, diagnostics
    # Actual DAO declarations and canonical RawQuery policy, not a fabricated
    # inventory derived from the grants that this test is supposed to verify.
    inventory = build_room_inventory(str(ROOT), source_root_set=roots)
    assert not inventory.diagnostics, inventory.diagnostics
    return roots, inventory


def _source_evidence(entries, real_source_contract):
    roots, inventory = real_source_contract
    return verify_v2_policy_source_evidence(
        entries, str(ROOT), source_roots=roots, room_inventory=inventory,
    )


def test_reconciled_and_preserved_groups_have_real_source_evidence(policy, real_source_contract):
    groups = GROUPS + PRESERVED
    selected = [entry for group in groups for entry in _rows(policy, group)]
    assert len(groups) == 13
    assert len(selected) == 33
    result = _source_evidence(selected, real_source_contract)
    assert result.trusted is True, [
        (group.callable_key_canonical, [diagnostic.code for diagnostic in group.diagnostics])
        for group in result.groups if not group.trusted
    ]
    assert result.diagnostics == ()
    assert len(result.groups) == 13
    assert sum(len(group.mutation_keys) for group in result.groups) == 33


# Each removal leaves a nonempty callable group so the real evidence verifier
# must discover the now-unlisted live write, not pass an empty policy vacuously.
MISSING_GRANTS = (
    (3, "deleteByBankConnectionScope"),
    (5, "markEnqueueFailed"),
    (6, "markFinalFailure"),
    (7, "deletePlannedByIds"),
    (8, "cancelClaimedDelivery"),
    (9, "markFailedByKey"),
)


@pytest.mark.parametrize("group_index,operation", MISSING_GRANTS)
def test_omitting_reconciled_mutation_still_fails_closed(policy, real_source_contract, group_index, operation):
    rows = _rows(policy, GROUPS[group_index])
    omitted = [entry for entry in rows if entry.operation == operation]
    assert len(omitted) == 1
    remaining = [entry for entry in rows if entry.operation != operation]
    assert remaining
    result = _source_evidence(remaining, real_source_contract)
    assert result.trusted is False
    assert [diagnostic.code for diagnostic in result.diagnostics] == [DB_V2_POLICY_UNLISTED_MUTATION]


def test_old_deferred_signature_is_rejected_by_real_callable_resolution(policy, real_source_contract):
    rows = _rows(policy, GROUPS[6])
    assert len(rows) == 3
    old_signature = [replace(entry, parameter_types=RETRY_PARAMETERS[:-1]) for entry in rows]
    result = _source_evidence(old_signature, real_source_contract)
    assert result.trusted is False
    assert [diagnostic.code for diagnostic in result.diagnostics] == [DB_V2_POLICY_PARSER_UNCERTAIN]
