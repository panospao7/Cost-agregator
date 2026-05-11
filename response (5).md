Created the Pipeline 4 AI-friendly debugging report for commit `4113e38`.

<pipeline_4_recurring_bill_reminders_debug_report.yaml>
meta:
  repo: "panospao7/Cost-agregator"
  commit: "4113e38f0d838a29d2d13d82a483ba573280721c"
  pipeline: "4 - Recurring / Bill Reminders"
  review_mode: "static GitHub source/debugging session; no local Gradle/device execution"
  verdict: "IMPROVED_BUT_NOT_CLEAN"
  summary: >
    Pipeline 4 has improved a lot since the original tracker. The recurring
    lifecycle now has default reminder windows, sourceType-safe occurrence keys,
    global transaction side-effect matching, planned-expense fulfillment on the
    explicit link path, suppression of open reminders, worker guard usage, and
    atomic reminder claiming. However, it is still not clean. The biggest
    remaining risks are link races, unlink corruption, materializer auto-PAID
    paths not fulfilling planned expenses, stale CLAIMED reminders, legacy
    markBillPaid behavior, non-atomic projection/rule CRUD, direct DAO bypasses,
    category loss, and incomplete reminder policy/state-machine handling.

tracker_reconciliation:
  P4-P0-01_actual_payment_fulfills_planned:
    tracker_status: "FIXED"
    actual_status: "PARTIAL"
    notes:
      - "Explicit RecurringLifecycleCoordinator.linkExpenseToOccurrence() marks occurrence PAID and calls PlannedExpenseDao.linkToActualExpense()."
      - "But materializer auto-PAID path does not fulfill matching planned expenses."
      - "Unlink path corrupts planned linkedActualExpenseId by setting it to 0L."
      - "Link path is read-before-write and race-prone."

  P4-P0-02_paid_occurrence_suppresses_reminders:
    tracker_status: "FIXED"
    actual_status: "MOSTLY_FIXED_WITH_GAPS"
    notes:
      - "Explicit link path calls suppressOpenDeliveriesForOccurrence()."
      - "getDueReminders() uses getPendingDeliveriesForPlannedOccurrences(), so PAID occurrences are filtered."
      - "But materializer auto-PAID does not suppress open deliveries."
      - "Suppression has no event/count/timestamp and leaves stale rows."

  P4-P1-01_reminder_dispatch_exactly_once:
    tracker_status: "FIXED"
    actual_status: "PARTIAL"
    notes:
      - "Worker now claims delivery before notification."
      - "But crash/exception after CLAIMED can leave reminder stuck forever."
      - "claimDelivery() does not check scheduledAt/snoozedUntil in the atomic UPDATE."
      - "No stale-claim recovery exists."

  P4-P1-02_rule_crud_lifecycle:
    tracker_status: "TODO_ONLY"
    actual_status: "PARTIAL"
    notes:
      - "Repositories now check DatabaseWriteBarrier, set createdAt, and write rule events."
      - "But CRUD is still repository/DAO-owned, not coordinator-owned."
      - "Events and mutations are not atomic."
      - "Delete/deactivate/update do not clean or reconcile generated occurrences/reminders/planned rows."

  P4-P1-03_worker_disabled:
    tracker_status: "FIXED"
    actual_status: "FIXED_STATIC_CONFIG_ONLY"
    notes:
      - "WorkerSpec.DEFAULTS bill_reminder_periodic enabled=true version=2."
      - "Still lacks real user reminder enablement/quiet-hours policy."

  P4-P1-04_reminder_windows_empty:
    tracker_status: "TODO_ONLY"
    actual_status: "FIXED_WITH_DESIGN_GAP"
    notes:
      - "generateOccurrences() now defaults empty reminderWindows to DEFAULT_REMINDER_WINDOWS."
      - "But no user/rule-specific settings decide whether reminders should be created."

  P4-P1-05_occurrence_key_collision:
    tracker_status: "DEFERRED"
    actual_status: "FIXED_FOR_NEW_ROWS_MIGRATION_NOT_VERIFIED"
    notes:
      - "RecurringOccurrenceExpander.buildOccurrenceKey() now includes sourceType."
      - "Migration/backfill for old keys was not verified in this static pass."

  P4-P1-06_global_expense_to_occurrence_link:
    tracker_status: "TODO_ONLY"
    actual_status: "MOSTLY_FIXED_FOR_COORDINATOR_PATHS"
    notes:
      - "TransactionSideEffectDispatcher.dispatchOnCreated() now calls recurringLifecycleCoordinator.linkExpenseToOccurrence()."
      - "dispatchOnDeleted() unlinks."
      - "Transaction update paths reconcile some key-field changes."
      - "Still best-effort/swallowed; direct DAO expense writes bypass it; deferred side-effect callers can still forget dispatch."

  P4-P1-07_paid_downgrade_by_regeneration:
    tracker_status: "TODO_ONLY"
    actual_status: "MOSTLY_FIXED"
    notes:
      - "RecurringOccurrenceMaterializer has terminal-status guard."
      - "But it can still create reminder deliveries for terminal existing occurrences when the new resolved status is PLANNED."

  P4-P1-08_materializer_status_event:
    tracker_status: "TODO_ONLY"
    actual_status: "MOSTLY_FIXED"
    notes:
      - "Materializer now writes OCCURRENCE_STATUS_CHANGED."
      - "Event is generic and does not trigger planned/reminder side effects."

  P4-P1-09_shared_write_guard:
    tracker_status: "FIXED"
    actual_status: "PARTIAL"
    notes:
      - "Coordinator methods use DatabaseWriteBarrier."
      - "Repositories use DatabaseWriteBarrier."
      - "Receivers still use RestoreMaintenanceMode directly."
      - "Projection service writes planned expenses without its own barrier."

  P4-P1-10_legacy_markBillPaid:
    tracker_status: "TODO_ONLY"
    actual_status: "OPEN"
    notes:
      - "BillReminderManager.markBillPaid() remains callable and only advances nextDate via legacy repository update."

positive_findings:
  - "RecurringLifecycleCoordinator is now the main occurrence lifecycle entry point."
  - "generateOccurrences() checks DatabaseWriteBarrier."
  - "Default reminder windows exist: 3_DAYS_BEFORE, DUE_DAY, OVERDUE."
  - "Occurrence key now includes sourceType for new rows."
  - "linkExpenseToOccurrence() marks occurrence PAID, fulfills planned expense, suppresses reminders, and writes OCCURRENCE_PAID."
  - "unlinkExpenseFromOccurrence() exists and is connected to delete side effects."
  - "TransactionSideEffectDispatcher globally attempts recurring matching on expense creation."
  - "BillReminderWorker uses WorkerExecutionGuard."
  - "BillReminderWorker claims before notification dispatch."
  - "getDueReminders() filters to PLANNED occurrences."
  - "WorkerSpec bill_reminder_periodic is enabled=true with version bump."
  - "Rule repositories now set createdAt and use DatabaseWriteBarrier."
  - "Materializer prevents terminal-status downgrades in most update cases."
  - "Materializer writes OCCURRENCE_GENERATED, REMINDER_SCHEDULED, and OCCURRENCE_STATUS_CHANGED events."

issues:
  - id: "P4-CURRENT-001"
    severity: "P0_CRITICAL"
    status: "OPEN"
    title: "linkExpenseToOccurrence() is race-prone and can double-link/last-writer-wins"
    evidence:
      - "Expense and candidate occurrences are loaded before database.withTransaction."
      - "The occurrence is matched in memory, then occurrenceDao.update() writes PAID without a conditional WHERE status='PLANNED' AND linkedExpenseId IS NULL."
      - "PlannedExpenseDao.linkToActualExpense() also overwrites without checking openSourceOccurrenceKey/current status."
    impact:
      - "Two simultaneous expense side effects can both link the same occurrence."
      - "Final linkedExpenseId can be whichever transaction writes last."
      - "Both callers may return true and produce misleading lifecycle events."
    fix:
      - "Add DAO method claimOccurrenceForExpense(occurrenceId, expenseId, paid fields, now) returning affected row count."
      - "Only fulfill planned expense and suppress reminders when claim count == 1."
      - "Use a conditional planned fulfillment query keyed by openSourceOccurrenceKey."
    tests:
      - "two_matching_expenses_race_only_one_links_occurrence"
      - "link_returns_false_when_occurrence_already_claimed"
      - "planned_fulfillment_happens_only_for_successful_occurrence_claim"

  - id: "P4-CURRENT-002"
    severity: "P0_CRITICAL"
    status: "OPEN"
    title: "Unlink corrupts planned expense by leaving linkedActualExpenseId=0"
    evidence:
      - "unlinkExpenseFromOccurrence() calls plannedExpenseDao.linkToActualExpense(planned.id, 0L, now)."
      - "linkToActualExpense() sets status='FULFILLED', linkedActualExpenseId=:expenseId, openSourceOccurrenceKey=NULL."
      - "Then updateStatus(..., 'PLANNED') reopens status/open key but does not clear linkedActualExpenseId."
    impact:
      - "A PLANNED row can still have linkedActualExpenseId=0."
      - "Forecast/dashboard/debug code can misread it as linked to an actual expense."
      - "Violates planned-expense status invariant."
    fix:
      - "Add plannedExpenseDao.unlinkActualExpense(id, updatedAt) that sets linkedActualExpenseId=NULL, status='PLANNED', openSourceOccurrenceKey=sourceOccurrenceKey."
      - "Use one atomic query, not linkToActualExpense(0L)+updateStatus."
    tests:
      - "unlink_reopens_planned_and_clears_linkedActualExpenseId"
      - "unlink_does_not_write_zero_actual_expense_id"

  - id: "P4-CURRENT-003"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Materializer auto-PAID path does not fulfill planned expense or suppress reminders"
    evidence:
      - "OccurrenceConflictResolver can resolve a candidate as PAID when an actual expense already exists."
      - "RecurringOccurrenceMaterializer updates existing PLANNED occurrence to PAID and writes OCCURRENCE_STATUS_CHANGED."
      - "Only RecurringLifecycleCoordinator.linkExpenseToOccurrence() fulfills PlannedExpense and suppresses reminder deliveries."
    impact:
      - "An occurrence can become PAID while its PlannedExpense remains PLANNED."
      - "Dashboard/forecast can double-count planned + actual."
      - "Open reminder deliveries remain stale."
    fix:
      - "Move PAID transition side effects into coordinator-level transaction."
      - "Or materializer should emit structured transition results that coordinator applies to PlannedExpense and reminders."
    tests:
      - "materializer_auto_paid_fulfills_existing_planned_expense"
      - "materializer_auto_paid_suppresses_open_reminders"
      - "materializer_auto_paid_writes_planned_fulfilled_event"

  - id: "P4-CURRENT-004"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Materializer can create reminders for terminal existing occurrences"
    evidence:
      - "isPlanned is computed from resolved r.status."
      - "When insert conflicts with an existing PAID/CANCELLED/SKIPPED occurrence, terminal guard skips status update."
      - "But the later reminder-creation block still runs when r.status == PLANNED."
    impact:
      - "Regeneration can add SCHEDULED reminder rows to PAID/CANCELLED/SKIPPED occurrences."
      - "Due query currently filters them out, but stale rows remain and may fire if status is later reopened."
    fix:
      - "Compute finalStatus after insert/update/skip."
      - "Only create reminders when final persisted occurrence status is PLANNED."
    tests:
      - "regeneration_does_not_schedule_reminders_for_existing_paid_occurrence"
      - "regeneration_does_not_schedule_reminders_for_cancelled_or_skipped_occurrence"

  - id: "P4-CURRENT-005"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "CLAIMED reminders can get stuck forever"
    evidence:
      - "BillReminderWorker claims delivery before send."
      - "TODO explicitly says stale CLAIMED reset is not implemented."
      - "If worker crashes after claim or an unexpected exception occurs after claim, row stays CLAIMED."
    impact:
      - "Reminder can be permanently lost."
      - "Exactly-once duplicate prevention was improved, but at-least-once delivery is now broken."
    fix:
      - "Add claimedAt/attemptCount/lastError fields or store them in a delivery_attempt table."
      - "Reset CLAIMED older than timeout to SCHEDULED or FAILED_RETRYABLE according to policy."
      - "Wrap post-claim send in try/catch that marks failure."
    tests:
      - "stale_claimed_delivery_is_requeued"
      - "exception_after_claim_marks_failed_or_requeues"
      - "worker_crash_after_claim_does_not_permanently_lose_reminder"

  - id: "P4-CURRENT-006"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "claimDelivery() does not atomically verify due condition"
    evidence:
      - "getDueReminders() filters scheduledAt/snoozedUntil."
      - "claimDelivery(id) only checks status IN ('SCHEDULED','SNOOZED')."
      - "It does not check scheduledAt <= now or snoozedUntil <= now."
    impact:
      - "A stale due list can claim a delivery that was snoozed into the future after the list was read."
      - "Race with receiver actions can cause premature notification."
    fix:
      - "Change claimDelivery(id, now) WHERE status='SCHEDULED' AND scheduledAt<=now OR status='SNOOZED' AND snoozedUntil<=now."
    tests:
      - "claim_snoozed_future_delivery_returns_zero"
      - "claim_scheduled_future_delivery_returns_zero"

  - id: "P4-CURRENT-007"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Recurring rule CRUD remains non-atomic and not coordinator-owned"
    evidence:
      - "RecurringExpenseRepository and ManualRecurringExpenseRepository call ManualRecurringExpenseDao directly."
      - "Lifecycle event insert and rule DAO mutation are separate operations."
      - "Delete/deactivate/update do not clean generated occurrences/reminders/planned rows."
    impact:
      - "Event can exist without mutation, or mutation without event."
      - "Inactive/deleted rules can leave active future reminders and planned expenses."
    fix:
      - "Create RecurringRuleLifecycleCoordinator with create/update/deactivate/delete/advanceNextDate."
      - "Wrap rule mutation + cleanup/reprojection + event in one transaction."
    tests:
      - "rule_create_and_event_are_atomic"
      - "rule_delete_cancels_future_occurrences_reminders_planned"
      - "rule_deactivate_suppresses_future_reminders"

  - id: "P4-CURRENT-008"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Legacy BillReminderManager.markBillPaid() still mutates old model only"
    evidence:
      - "markBillPaid() remains callable despite @Deprecated."
      - "It calculates nextDate and calls recurringExpenseRepository.update(updated)."
      - "It does not mark occurrence PAID, fulfill planned expense, suppress reminders, or create/link actual expense."
    impact:
      - "UI or legacy callers can mark a bill paid while lifecycle tables remain stale."
      - "Creates split-brain old/new recurring state."
    fix:
      - "Remove method or use DeprecationLevel.ERROR."
      - "Replace with command that creates/links actual expense through TransactionLifecycleCoordinator and RecurringLifecycleCoordinator."
    tests:
      - "legacy_markBillPaid_is_not_callable_in_production"
      - "markBillPaid_command_marks_occurrence_paid_and_fulfills_planned"

  - id: "P4-CURRENT-009"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "RecurringPlanProjectionService planned writes are non-atomic and not barrier-checked"
    evidence:
      - "projectFromRule() calls coordinator.generateOccurrences(), then fetches occurrences, then inserts PlannedExpense rows one by one."
      - "No database.withTransaction covers occurrence generation plus planned projection."
      - "No DatabaseWriteBarrier is injected into RecurringPlanProjectionService for plannedExpenseDao inserts."
    impact:
      - "Crash/restore can leave occurrences without planned rows."
      - "Planned rows can be inserted during restore after generation passed its guard."
      - "No PLANNED_GENERATED event exists."
    fix:
      - "Move projection into coordinator transaction or create RecurringProjectionCoordinator using DatabaseWriteBarrier."
      - "Write PLANNED_GENERATED/PLANNED_UPDATED events."
    tests:
      - "projection_occurrence_and_planned_insert_are_atomic"
      - "projection_blocked_by_write_barrier"
      - "projection_writes_planned_generated_event"

  - id: "P4-CURRENT-010"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Inactive rules can still generate occurrences when called by ID"
    evidence:
      - "generateOccurrences() loads rule by ID but does not check rule.isActive."
      - "ManualRecurringExpenseRepository.setActiveStatus(false) only flips isActive and writes an event."
    impact:
      - "Future jobs or UI actions can generate reminders/planned rows for deactivated subscriptions."
    fix:
      - "generateOccurrences() should reject inactive rules unless explicit includeInactive/debug option is passed."
      - "Deactivate should cancel/suppress future generated rows."
    tests:
      - "generateOccurrences_inactive_rule_returns_no_new_occurrences"
      - "deactivate_rule_suppresses_future_reminders_and_planned_rows"

  - id: "P4-CURRENT-011"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Same actual expense can satisfy multiple recurring rules"
    evidence:
      - "OccurrenceConflictResolver prevents reusing an actual expense only within one resolve() call."
      - "Each rule calls generateOccurrences() independently with its own matchedExpenseIds set."
      - "RecurringOccurrence.linkedExpenseId has a non-unique index, not a uniqueness constraint."
    impact:
      - "Two similar rules can both become PAID from the same actual expense."
      - "Actual spending can be over-attributed across recurring rules."
    fix:
      - "Add global match claim table or unique partial index for active linkedExpenseId where status='PAID'."
      - "Resolver should query already-linked occurrences and exclude those actual expense IDs."
    tests:
      - "same_actual_expense_cannot_pay_two_rules"
      - "second_rule_with_same_merchant_amount_date_remains_planned_or_conflict"

  - id: "P4-CURRENT-012"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Recurring match side effect is best-effort and failures are invisible"
    evidence:
      - "TransactionSideEffectDispatcher.runSafely() logs and swallows recurring link errors."
      - "No durable PipelineDiagnosticEvent or TransactionEvent records recurring match skipped/failed."
    impact:
      - "Expense can be created but not matched to a bill, with no durable reason."
      - "Forecast/reminders may stay wrong."
    fix:
      - "Write recurring_match diagnostic events: ATTEMPTED, MATCHED, NO_MATCH, FAILED."
      - "Expose recent match outcomes in debug UI."
    tests:
      - "expense_create_recurring_match_failure_writes_diagnostic"
      - "no_match_writes_debuggable_outcome"

  - id: "P4-CURRENT-013"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Direct DAO mutation surface remains public"
    evidence:
      - "ManualRecurringExpenseDao exposes insert/update/delete/setActiveStatus/updateNextDate."
      - "RecurringOccurrenceDao exposes insert/update/updateStatus."
      - "RecurringReminderDeliveryDao exposes insert/update/claim."
      - "No static allowlist was verified."
    impact:
      - "Future code can bypass lifecycle events, write barrier, planned reconciliation, and reminder suppression."
    fix:
      - "Add static guard/Detekt rule for recurring DAO mutators."
      - "Restrict writes to RecurringLifecycleCoordinator/RuleCoordinator/Materializer/approved migrations."
    tests:
      - "recurring_dao_mutator_guard_fails_unapproved_callers"

  - id: "P4-CURRENT-014"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Rule category is lost during occurrence generation"
    evidence:
      - "ManualRecurringExpense has categoryId."
      - "generateOccurrences() passes categoryId = null into RecurringOccurrenceExpander.ExpandRequest."
      - "RecurringPlanProjectionService copies occ.categoryId into PlannedExpense, so it also becomes null."
    impact:
      - "Budget/category forecasts lose recurring bill categories."
      - "Dashboard planned spending by category is wrong."
    fix:
      - "Pass categoryId = rule.categoryId."
    tests:
      - "rule_category_propagates_to_occurrence"
      - "rule_category_propagates_to_planned_expense"

  - id: "P4-CURRENT-015"
    severity: "P1_HIGH"
    status: "OPEN"
    title: "Reminder insert race is counted and audited as success even when insert is ignored"
    evidence:
      - "Materializer checks getByOccurrenceAndWindow() then calls insert(IGNORE)."
      - "It does not inspect insert return value."
      - "It increments remindersCreated and writes REMINDER_SCHEDULED unconditionally after insert call."
    impact:
      - "Concurrent generation can produce false reminder-created counts and false lifecycle events."
    fix:
      - "Check insertedDeliveryId > 0 before increment/event."
      - "Or use atomic upsert/insert-return outcome."
    tests:
      - "duplicate_reminder_insert_ignore_does_not_increment_created"
      - "duplicate_reminder_insert_ignore_does_not_write_scheduled_event"

  - id: "P4-CURRENT-016"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Reminder failure state is too thin"
    evidence:
      - "markReminderFailed() sets FAILED_PERMISSION or FAILED_TRANSIENT but does not store attemptCount, lastAttemptAt, failureReason column, or retryAt."
      - "Worker only writes PipelineDiagnosticEvent for SENT."
    impact:
      - "Permission/transient failures are hard to debug or recover."
      - "Permission restored flow cannot reschedule failed-permission rows cleanly."
    fix:
      - "Add delivery attempt ledger or columns: attemptCount, lastAttemptAt, failureReason, retryAfter."
      - "Write diagnostics for FAILED_PERMISSION and FAILED_TRANSIENT."
    tests:
      - "permission_denied_writes_failed_permission_diagnostic"
      - "permission_restored_reschedules_failed_permission"

  - id: "P4-CURRENT-017"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Snooze/dismiss receivers block BroadcastReceiver and bypass coordinator"
    evidence:
      - "SnoozeReminderReceiver and DismissReminderReceiver use runBlocking(Dispatchers.IO) in onReceive()."
      - "They write directly to RecurringReminderDeliveryDao."
      - "They use RestoreMaintenanceMode directly instead of DatabaseWriteBarrier."
      - "TODO comments admit they should delegate to coordinator."
    impact:
      - "Potential receiver blocking/ANR risk."
      - "Lifecycle policy and write-barrier semantics can drift from coordinator."
    fix:
      - "Use goAsync()+coroutine or one-shot WorkManager action."
      - "Add RecurringLifecycleCoordinator.snoozeReminder()/dismissReminder() with writeBarrier and transaction."
    tests:
      - "snooze_receiver_uses_goAsync"
      - "dismiss_receiver_uses_coordinator"
      - "snooze_blocked_by_databaseWriteBarrier"

  - id: "P4-CURRENT-018"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "updateOccurrenceStatus() accepts arbitrary strings and can event missing rows"
    evidence:
      - "Method signature is updateOccurrenceStatus(occurrenceId, newStatus: String)."
      - "DAO updateStatus returns Unit, not affected count."
      - "Event can be written with oldStatus=null for a missing occurrence."
      - "MISSED maps to OCCURRENCE_SKIPPED."
    impact:
      - "Invalid states can enter DB."
      - "Audit log can claim changes to nonexistent rows."
    fix:
      - "Use enum/sealed RecurringOccurrenceStatus."
      - "DAO update returns affected count."
      - "Return NotFound/InvalidTransition result."
      - "Map MISSED to OCCURRENCE_MISSED."
    tests:
      - "invalid_occurrence_status_rejected"
      - "missing_occurrence_does_not_write_event"
      - "missed_status_writes_occurrence_missed"

  - id: "P4-CURRENT-019"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "RecurringLifecycleEvent schema cannot query rule/delivery-specific history"
    evidence:
      - "RecurringLifecycleEvent has occurrenceId nullable but no ruleId or deliveryId columns."
      - "Rule events are stored with occurrenceId=null and rule details only in metadata."
      - "Delivery events store deliveryId only inside metadata."
    impact:
      - "Debug UI/AI agents cannot efficiently answer: which events belong to this rule or reminder delivery?"
    fix:
      - "Add nullable ruleId and deliveryId columns or separate typed event tables."
      - "Index ruleId, deliveryId, eventType, occurredAt."
    tests:
      - "query_rule_lifecycle_events_by_rule_id"
      - "query_delivery_events_by_delivery_id"

  - id: "P4-CURRENT-020"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Lifecycle metadata JSON is built by string interpolation"
    evidence:
      - "Several metadata strings interpolate merchant, currency, reason directly."
      - "No JSONObject/string escaping is used in recurring lifecycle writers."
    impact:
      - "Merchant/reason containing quotes, braces, or newlines can produce invalid JSON."
      - "Debug parsers can break."
    fix:
      - "Use JSONObject or a shared JsonMetadataBuilder for all event metadata."
    tests:
      - "merchant_with_quote_writes_valid_lifecycle_metadata_json"
      - "reason_with_newline_writes_valid_lifecycle_metadata_json"

  - id: "P4-CURRENT-021"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Recurring rule validation is weak/missing"
    evidence:
      - "Repositories insert ManualRecurringExpense after only setting createdAt."
      - "No visible validation for amount > 0, finite amount, ISO currency, nonblank merchant, sane nextDate."
    impact:
      - "Invalid rules can generate invalid occurrences, reminders, and planned expenses."
    fix:
      - "Rule coordinator should validate request before insert/update."
    tests:
      - "create_rule_rejects_negative_amount"
      - "create_rule_rejects_invalid_currency"
      - "create_rule_rejects_blank_merchant"

  - id: "P4-CURRENT-022"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Reminder defaults are infrastructure-driven, not user-policy-driven"
    evidence:
      - "generateOccurrences() always applies DEFAULT_REMINDER_WINDOWS when caller passes empty list."
      - "WorkerSpec enables bill_reminder_periodic globally."
      - "No reminder settings repository was observed in this pipeline."
    impact:
      - "Projection can create reminders even if the user expected no reminders."
      - "No per-rule reminder windows."
    fix:
      - "Add ReminderSettingsRepository and rule-specific reminder policy."
      - "Options should distinguish createReminders=false from useDefaultWindows=true."
    tests:
      - "reminders_disabled_generates_no_deliveries"
      - "rule_specific_windows_override_defaults"
      - "global_defaults_apply_only_when_reminders_enabled"

  - id: "P4-CURRENT-023"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Reminder scheduledAt policy lacks local time/quiet hours"
    evidence:
      - "DUE_DAY schedules at dueDate start-of-day."
      - "N_DAYS_BEFORE subtracts calendar days from dueDate."
      - "OVERDUE is dueDate + 1 day."
      - "No reminderHour/reminderMinute/quietHours policy is applied."
    impact:
      - "Notifications can fire at midnight."
      - "Timezone/quiet-hour behavior is not user-friendly or explicit."
    fix:
      - "Add reminder time-of-day, timezone, overdue offset, quiet-hours policy."
    tests:
      - "due_day_reminder_uses_user_configured_local_time"
      - "overdue_reminder_schedules_after_due_date_at_configured_time"
      - "quiet_hours_defers_reminder"

  - id: "P4-CURRENT-024"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Recurring matching is too strict for real bill payments"
    evidence:
      - "linkExpenseToOccurrence() requires same calendar day, exact merchant key, same currency, ±10 percent amount."
      - "OccurrenceConflictResolver uses the same strict assumptions."
    impact:
      - "Early/late payments, merchant alias changes, FX/card currency differences, and small amount changes can fail to match."
      - "Missed match leaves planned/reminders open."
    fix:
      - "Introduce RecurringMatchPolicy with date grace window, canonical merchant aliases, confidence score, and review path for ambiguous matches."
    tests:
      - "payment_one_day_early_matches_with_policy"
      - "merchant_alias_matches_recurring_rule"
      - "ambiguous_match_creates_review_not_auto_link"

  - id: "P4-CURRENT-025"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Existing planned projections are not updated after rule edits"
    evidence:
      - "RecurringPlanProjectionService skips when getBySourceOccurrenceKey() returns existing."
      - "It does not update amount/category/date/currency for open PLANNED rows."
    impact:
      - "Rule amount/category changes do not propagate to future planned expenses."
      - "Forecast remains stale."
    fix:
      - "When existing planned status is PLANNED, update expected amount/currency/category/date if occurrence changed."
      - "Do not overwrite FULFILLED/CANCELLED/SKIPPED rows."
    tests:
      - "rule_amount_change_updates_open_planned_projection"
      - "rule_category_change_updates_open_planned_projection"
      - "fulfilled_planned_projection_not_overwritten"

  - id: "P4-CURRENT-026"
    severity: "P2_MEDIUM"
    status: "OPEN"
    title: "Reminder suppression has no event, timestamp, or affected-row count"
    evidence:
      - "suppressOpenDeliveriesForOccurrence() updates status='CANCELLED' and returns Unit."
      - "No REMINDER_SUPPRESSED_PAID event is written."
      - "RecurringReminderDelivery has no updatedAt/suppressedAt field."
    impact:
      - "Cannot tell which reminders were suppressed because payment happened."
      - "Cannot distinguish user dismiss vs auto-cancel by payment."
    fix:
      - "Return affected count and write REMINDER_SUPPRESSED_PAID event."
      - "Add updatedAt/suppressedAt or transition event metadata."
    tests:
      - "payment_suppression_writes_event_with_count"
      - "suppressed_delivery_has_auditable_reason"

  - id: "P4-CURRENT-027"
    severity: "P3_LOW"
    status: "OPEN"
    title: "Stale comments/docs remain in code"
    evidence:
      - "WorkerSpec comment still says BillReminder disabled by default while enabled=true."
      - "BillReminderManager KDoc references old/future worker language."
      - "RecurringOccurrence occurrenceKey comment says ruleId|normalizedDueDate|frequency even though sourceType is included."
      - "RecurringExpenseRepository KDoc references a non-current coordinator package/name."
    impact:
      - "AI agents and future maintainers can make wrong assumptions."
    fix:
      - "Update comments/KDoc after refactor."
    tests:
      - "static grep no stale disabled-by-default bill reminder comment"

  - id: "P4-CURRENT-028"
    severity: "P2_MEDIUM"
    status: "NEEDS_VERIFICATION"
    title: "Old occurrenceKey migration/backfill was not verified"
    evidence:
      - "New buildOccurrenceKey includes sourceType."
      - "This static pass did not verify Room migration/backfill for existing recurring_occurrences and planned_expenses.sourceOccurrenceKey."
    impact:
      - "Existing users with old keys may have broken planned-expense joins or duplicate regenerated occurrences."
    fix:
      - "Verify migration rewrites recurring_occurrences.occurrenceKey and planned_expenses source/open keys consistently."
      - "Support legacy-key lookup during transition if needed."
    tests:
      - "migration_old_occurrence_keys_gain_source_type"
      - "migration_updates_planned_sourceOccurrenceKey"
      - "post_migration_generateOccurrences_does_not_duplicate_old_occurrences"

recommended_fix_order:
  - pr: "PR1"
    title: "Critical reconciliation correctness"
    include:
      - "Conditional occurrence claim for linkExpenseToOccurrence."
      - "Fix planned unlink to clear linkedActualExpenseId."
      - "Materializer PAID transition must fulfill planned and suppress reminders via coordinator-owned transaction."
    closes:
      - "P4-CURRENT-001"
      - "P4-CURRENT-002"
      - "P4-CURRENT-003"

  - pr: "PR2"
    title: "Reminder state-machine hardening"
    include:
      - "Prevent reminders for terminal occurrences."
      - "Due-checked atomic claim."
      - "Stale CLAIMED recovery."
      - "Failure attempt ledger."
      - "Suppression events/counts."
    closes:
      - "P4-CURRENT-004"
      - "P4-CURRENT-005"
      - "P4-CURRENT-006"
      - "P4-CURRENT-016"
      - "P4-CURRENT-026"

  - pr: "PR3"
    title: "Rule lifecycle coordinator"
    include:
      - "Coordinator-owned create/update/deactivate/delete/advance."
      - "Atomic rule event + mutation."
      - "Validation."
      - "Cleanup/reprojection of future rows."
      - "Static DAO mutator guard."
    closes:
      - "P4-CURRENT-007"
      - "P4-CURRENT-010"
      - "P4-CURRENT-013"
      - "P4-CURRENT-021"

  - pr: "PR4"
    title: "Projection atomicity and category propagation"
    include:
      - "Pass rule.categoryId."
      - "Atomic occurrence + planned projection."
      - "Update open planned rows after rule edits."
      - "Write PLANNED_GENERATED/UPDATED events."
    closes:
      - "P4-CURRENT-009"
      - "P4-CURRENT-014"
      - "P4-CURRENT-025"

  - pr: "PR5"
    title: "Legacy/API cleanup"
    include:
      - "Remove or ERROR-deprecate BillReminderManager.markBillPaid()."
      - "Replace with mark-paid command through transaction + recurring lifecycle."
      - "Fix stale comments."
    closes:
      - "P4-CURRENT-008"
      - "P4-CURRENT-027"

  - pr: "PR6"
    title: "Global match robustness and observability"
    include:
      - "Durable recurring match diagnostics."
      - "Prevent one actual expense from paying multiple rules."
      - "Confidence/review policy for ambiguous/early/late payments."
      - "Event schema/query improvements and JSON escaping."
    closes:
      - "P4-CURRENT-011"
      - "P4-CURRENT-012"
      - "P4-CURRENT-019"
      - "P4-CURRENT-020"
      - "P4-CURRENT-024"

  - pr: "PR7"
    title: "Reminder user policy and receiver cleanup"
    include:
      - "ReminderSettingsRepository."
      - "User/rule-specific windows."
      - "Quiet hours/time of day."
      - "Receivers delegate to coordinator using goAsync or WorkManager."
    closes:
      - "P4-CURRENT-017"
      - "P4-CURRENT-022"
      - "P4-CURRENT-023"

  - pr: "PR8"
    title: "Migration verification"
    include:
      - "Verify/backfill old occurrence keys and planned source keys."
      - "Add migration tests."
    closes:
      - "P4-CURRENT-028"

definition_of_done:
  - "Only one actual expense can claim a recurring occurrence."
  - "Unlink restores PLANNED state with linkedActualExpenseId=NULL."
  - "Any PAID transition, whether explicit link or materializer auto-match, fulfills planned expense and suppresses reminders atomically."
  - "Terminal occurrences never receive new reminder deliveries during regeneration."
  - "CLAIMED reminders have stale recovery and attempt tracking."
  - "Reminder claim checks due/snoozedUntil inside the atomic UPDATE."
  - "Rule CRUD is coordinator-owned, validated, restore-guarded, evented, and atomic."
  - "Deactivate/delete/update rule reconciles future occurrence/reminder/planned rows."
  - "No production code can call recurring DAO mutators outside an allowlist."
  - "Rule category propagates to occurrence and planned projection."
  - "Projection updates open planned rows after rule edits."
  - "Legacy markBillPaid cannot bypass lifecycle."
  - "Recurring matching writes durable diagnostics for matched/no-match/failure."
  - "One actual expense cannot satisfy multiple recurring rules unless explicitly split/approved."
  - "Reminder settings are user/rule-policy-driven, not hardcoded defaults only."
  - "Snooze/dismiss receivers do not use runBlocking and delegate to coordinator."
  - "RecurringLifecycleEvent can be queried by ruleId, occurrenceId, and deliveryId."
  - "Event metadata is valid escaped JSON."
  - "Old occurrence keys are migrated/backfilled or legacy-compatible."

sources:
  master_tracker:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md"
  pipeline_4_report:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-4-recurring-bill-reminders-debug-report.md"
  recurring_lifecycle_coordinator:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt"
  recurring_occurrence_materializer:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt"
  recurring_occurrence_expander:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt"
  occurrence_conflict_resolver:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt"
  recurring_plan_projection_service:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt"
  recurring_occurrence:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt"
  recurring_reminder_delivery:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt"
  recurring_lifecycle_event:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringLifecycleEvent.kt"
  manual_recurring_expense:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt"
  recurring_occurrence_dao:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringOccurrenceDao.kt"
  recurring_reminder_delivery_dao:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt"
  planned_expense_dao:
    url: "https://raw.githubusercontent.com/panospao7/Cost-agregator/4113e38f0d838a29d2d13d82a483ba573280721c/app