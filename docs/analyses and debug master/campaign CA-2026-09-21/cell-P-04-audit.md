# P-04 — Recurring rules and reminders

## Provenance
- Date: 2026-09-21; finalized 2026-09-22 (session date rollover).
- Pinned source: `37601232b9778170c57a656a245b199ab6d7d965`.
- Auditor: **direct astra session**, cell P-04; AUDIT, static only.
- Pin check: `git rev-parse --short HEAD` returned `37601232`; `git diff --stat 37601232..HEAD -- app config scripts` empty; production working-tree status empty.
- Scope: recurring rule single writer, occurrence materialization/reconciliation, lifecycle events, bill reminder dispatch, snooze/dismiss workers and their reachable callers/DAOs.
- No production edits, builds, tests, or guards executed. Independent Phase 2 verification remains pending.

## Status
Static primary-file sweep finished. **7 findings: 0 P0, 1 P1, 6 P2, 0 P3.** Six newly identified defects plus one distinctly worsened FRESH-P4-005 remediation regression. All findings require independent Phase 2 verification. No implementation or runtime validation is claimed.

## Governing intent and exclusions
- Read governing prompt §2 and §4: all 15 classes, strict finding schema/severity, known-debt exclusion, intended-behavior rule.
- Read COVERAGE_MATRIX P-04 row; LEGAL_PATHS sections Recurring Rule Mutations, Recurring Plan Projection, Workers / Background Jobs, Lifecycle Events; CODEBASE_SEGMENTS Segment 7; inventory recurring/worker entries; selected engine rows for recurring coordinator/event writer/reminder worker.
- FRESH-P4-001..006 are already tracked and will not be restated. Registry narrows P4-002 to toggle bypass, P4-004 to dead code, P4-006 to latent status inconsistency.
- D7: fixed-anchor expansion prevents new drift from the current persisted anchor; correction jumps are intended. No demand to infer the original anchor of historically drifted rules. D4 approves deletion of projectFromRule.
- Remediation README and WAVE-1-STATUS recurring references consulted; further candidate-specific deduplication required before reporting.

## Coverage ledger
Full production-file reads: **20 / maximum 20**, enumerated in the checkpoints below. Source evidence was read from the clean pinned checkout with numbered lines. Package mates were searched for mutation/IO patterns and read selectively.

### Primary scope covered
- RecurringRuleLifecycleCoordinator
- RecurringLifecycleEventWriter
- RecurringOccurrenceMaterializer
- RecurringLifecycleCoordinator
- RecurringOccurrenceDao / RecurringReminderDeliveryDao / rule and event DAO
- BillReminderWorker
- SnoozeReminderActionWorker / DismissReminderActionWorker
- Projection/expansion and reachable callers, scheduling, effects, relevant tests/guards

## New findings
Finding IDs: CA-P-04-001 through CA-P-04-007. Numbered findings below contain pin-specific evidence, caller traces, tests/guards, cross-cell ownership, and baseline distinctions.

### Coverage checkpoint 1
Full primary production files read (9/20): all paths below relative to `app/src/main/java/com/yourname/expensetracker/`.
- `domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` 1–621: all CRUD/activation/advance paths, update slot mapping, derived-row retirement/adoption, events, pre-commit assertions. Checked transaction boundaries versus pre-transaction rule reads and active-state preservation.
- `domain/recurring/lifecycle/RecurringLifecycleEventWriter.kt` 1–59: interface exposes only writeCritical; Room implementation checks barrier and propagates insert failure. Compared with documented dual-channel contract and coordinator direct event writes.
- `domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt` 1–372: IGNORE dedupe, terminal protection, automatic payment fulfillment/suppression, event insertion, window scheduling, write scope.
- `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` 1–1393: generation/projection, expense matching/link/unlink/snapshot/bulk reconciliation, status transitions, claim recovery, dispatch revalidation, sent/failed/cancelled actions, snooze/dismiss, legacy report paths. Re-read 310 onward separately where a combined output truncated.
- `service/reminder/BillReminderWorker.kt` 1–303: guard/settings/quiet hours, claims and revalidation, actual notify then durable SENT, diagnostics, exception/cancellation branches, notification actions, canonical scheduler.
- `service/reminder/SnoozeReminderActionWorker.kt` 1–59 and `DismissReminderActionWorker.kt` 1–59: guarded DB action and checkpoints; returned action result is discarded.
- `data/database/dao/RecurringOccurrenceDao.kt` 1–96: unique-key insert, date/source queries, conditional claim, linked-expense lookup, update/retirement.
- `data/database/dao/RecurringReminderDeliveryDao.kt` 1–222: pending/claim predicates, stale recovery, sent/failed/cancel CAS, reopen and suppression status sets, retirement query.

Initial candidates were resolved as follows: SENT action rejection → 001; transient failure → 002; inactive-rule update → 003. The standalone updateRule stale-read race was not promoted after inspecting outer caller transactions. Terminal delivery-history cascade on retiring slots remains an unpromoted lead (see limits).

## Finding records and continuing coverage

### CA-P-04-001
ID: CA-P-04-001
Title: Posted bill notifications cannot be snoozed or dismissed through their action buttons
Defect class: 12 (Error handling; also 13 wiring and 14 test coverage)
Severity: P2
Evidence: All paths relative to `app/src/main/java/com/yourname/expensetracker/`, at the pin. `service/reminder/BillReminderWorker.kt`, doWork/sendNotification, 109–113 and 221–255, posts Snooze/Dismiss actions and immediately records successful delivery as SENT. `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`, TERMINAL_STATUSES, 71–79, includes SENT; dismissReminderDelivery 1187–1211 and snoozeReminderDelivery 1219–1246 return NoOp for that state. `service/reminder/SnoozeReminderActionWorker.kt` and `DismissReminderActionWorker.kt`, doWork, 47–53, discard the returned action result. The two receivers, onReceive, 26–38, only enqueue work; neither receiver nor either worker cancels the posted Android notification.
Impact path: successful bill notification → delivery SENT → user taps Snooze → guarded action returns NoOp → no SNOOZED timestamp/event and no repeat notification 24h later. Dismiss likewise records no dismissal and does not explicitly remove the posted notification. This concerns the actual action button, not automatic replay of terminal deliveries.
Caller trace: BillReminderWorker.sendNotification → explicit PendingIntent to SnoozeReminderReceiver/DismissReminderReceiver → corresponding Hilt action worker → recurring coordinator → terminal-state short circuit before DAO/event writes.
Existing tests/guards: `app/src/test/java/com/yourname/expensetracker/service/reminder/SnoozeReminderActionWorkerTest.kt` 26–50 mocks the coordinator and asserts only success/delegation; dismiss sibling has the same shape. RecurringLifecycleCoordinatorTest covers historical FAILED_PERMISSION no-op (335–345), not action handling after a successful send. Worker guard presence does not validate this state transition. Tests not run.
Cross-cell impact: P-09/E-05 guarded worker result appears successful although the user action is ineffective; P-04 owns the behavior.
Old-ID cross-refs: none. FRESH-P4-006 concerns permission failure, not SENT notification actions. No matching known-debt entry found in the registry, still-open ledger, RP-04, or RP-20/21 triage plan.

### CA-P-04-002
ID: CA-P-04-002
Title: A transient notification failure permanently removes the delivery from automatic retry
Defect class: 10 (Worker hygiene; also 12 error handling)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt`, sendNotification 258–267 and doWork 135–161, turns a non-permission posting exception into notification_error, calls markReminderFailed, and continues without requesting retry. `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`, markReminderFailed 1158–1179, commits FAILED_TRANSIENT; getDueReminders/recoverAndGetDueReminders 947–961 only use the pending query and stale-claim recovery. `data/database/dao/RecurringReminderDeliveryDao.kt`, getPendingDeliveriesForPlannedOccurrences 53–60 and claimDelivery 72–87, allow only SCHEDULED/SNOOZED; recoverStaleClaimedDeliveries 99–108 only repairs CLAIMED. reopenDeliveryForOccurrenceWindow 188–206 is the only automatic-looking FAILED_TRANSIENT reset, but its sole production caller is regenerateReminderDeliveriesForOccurrence at coordinator 671, invoked only when unlinking an actual expense (819–831).
Impact path: due reminder → posting throws a non-permission transient exception → delivery FAILED_TRANSIENT → current worker completes and every subsequent periodic run ignores that delivery. A bill reminder is lost despite the retryable status name. Expense unlink or an explicit action is not automatic failure recovery.
Caller trace: WorkerRegistry bill_reminder_periodic entry → BillReminderWorker.schedule/doWork → markReminderFailed → DAO.markFailedFromClaimed; next periodic doWork → recoverAndGetDueReminders excludes the failed row. WorkerSpec enables this schedule at 68–75.
Existing tests/guards: BillReminderWorkerTest exercises permission cancellation and successful metrics using a mocked coordinator; RecurringLifecycleCoordinatorTest contains permission routing/history cases. DAO status predicates and caller search reveal no exercised non-permission failure → later successful redelivery scenario in these tests. No build/test/guard executed.
Cross-cell impact: P-09 retry/terminal observability; delivery state machine remains P-04-owned.
Old-ID cross-refs: none. FRESH-P4-006 is the distinct, explicitly accepted historical FAILED_PERMISSION disposition; this finding is limited to the live non-permission FAILED_TRANSIENT path.

### Coverage checkpoint 2
- Full reads now 16/20: `domain/recurring/RecurringOccurrenceExpander.kt` 1–231, `OccurrenceConflictResolver.kt` 1–144, `RecurringPlanProjectionService.kt` 1–95; both reminder receivers 1–40; `data/database/dao/ManualRecurringExpenseDao.kt` 1–99 and `RecurringLifecycleEventDao.kt` 1–22.
- Expansion: fixed anchor within expand, calendar-aware windows, key identity, irregular exclusion. Resolver checks ownership, UNKNOWN/TRANSFER/DEPOSIT, merchant, finite amount and same currency; its explicit P4-CURRENT-011 global expense-reuse TODO is known debt, not a new finding.
- Projection: creates keyed planned rows in caller transaction; no active-rule check of its own. Traced only coordinator calls. Report/generation/status-convenience methods have no external production callers; do not assign live runtime severity to those isolated paths.
- Receiver → action-worker path fully traced. Both use explicit immutable PendingIntents; no notification cancellation in receiver/worker/coordinator path.
- Read mutation excerpts of recurring/manual/subscription repositories, subscription toggle/delete ViewModel, negotiation outcome engine 580–649, subscription price-change engine 336–375. Current subscription toggle/delete delegate correctly (known FRESH-P4-001/002 remediation); subscriptionCategory column-only carve-out is explicitly approved.
- Read delivery entity FK/index section 8–30: occurrence delete cascades all deliveries. Read coordinator test method inventory and focused action/terminal/invariant/reminder tests. Tests remain static evidence only.
- Important candidate correction: the two production callers of RecurringExpenseRepository.update wrap their calls in an outer Room transaction; the updateRule pre-transaction read alone is insufficient to prove a live race on those callers. Not promoted as a finding.
- RP-04 execution notes tolerate transitional direct-event-DAO writes (around 264); that known architectural debt is excluded, despite stricter LEGAL_PATHS wording. Documentation still claims a writeDiagnostic API absent from the current writer.

### CA-P-04-003
ID: CA-P-04-003
Title: Updating an inactive rule recreates the open obligations and reminders removed by deactivation
Defect class: 11 (Data integrity; also 1 lifecycle contract)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`, updateRule 334–355 preserves old.isActive, but reconcileUpdateInCurrentTransaction 393–407, 454–505 unconditionally expands, resolves, materializes with reminders enabled, and projects planned rows. Its invariant assertions 557–619 never check rule activity. `domain/recurring/RecurringPlanProjectionService.kt`, projectFromOccurrencesInCurrentTransaction 63–87, checks occurrence status/source only. Contrast recurring coordinator generateOccurrences 119–120 and projectOccurrences 219–220, which explicitly reject inactive rules. Delivery DAO 53–60 and getDispatchableClaimedReminder 1033–1038 do not check rule activity.
Impact path: deactivation deletes open derived state → a later price update for the retained inactive rule creates up to 12 months of PLANNED occurrences/planned rows/SCHEDULED deliveries while isActive remains false → enabled BillReminderWorker posts reminders for the paused subscription. A production trigger is an in-flight/stale negotiation outcome: deactivation wins before the outcome transaction, then the outcome updates the same rule by ID without testing activity. Forecast consumers' active-rule filters do not protect notification dispatch.
Caller trace: `ui/screens/negotiation/BillNegotiationScreen.kt` 165 → BillNegotiationViewModel.recordOutcome 68–90 → `domain/negotiation/SmartBillNegotiationEngine.kt`, recordNegotiationOutcome 588–640 (getById, no activity rejection, repository update inside transaction) → `data/repository/RecurringExpenseRepository.kt` 128–129 → updateRule. Subscription toggle/deactivation independently enters via SubscriptionManagementViewModel 256–264 and SubscriptionManagementRepository 81–85.
Existing tests/guards: RecurringRuleLifecycleCoordinatorTest covers deactivation (655 onward) and reactivation (680 onward), but no update of an inactive rule in its method inventory. assertInvariants 322–371 checks occurrence/planned/delivery consistency, not absence of derived state for inactive rules. The single-writer guard sees a legal coordinator call and cannot prevent this semantic violation. Tests not run.
Cross-cell impact: P-09 reminder dispatch; P-06/planned data consumers; negotiation/subscription entry surfaces.
Old-ID cross-refs: related symptom to FRESH-P4-002, but distinct NEW issue: the old toggle bypass is repaired at this pin; this regeneration happens inside the legal coordinator on a subsequent update. It is not a restatement of the old raw DAO toggle.

### CA-P-04-004
ID: CA-P-04-004
Title: Fixed-anchor remediation makes projection and materialization disagree, duplicating future bills
Defect class: 15 (Fix-regression; also 8 time correctness and 4 duplicates)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`, projectOccurrences 227–253 derives the original day, advances to the first in-range date, then passes that possibly clamped date as ExpandRequest.anchorDate. `domain/recurring/RecurringOccurrenceExpander.kt`, expand 119–146 derives a NEW fixed day from that argument; key generation 185–192 embeds the due timestamp. In contrast, `RecurringRuleLifecycleCoordinator.kt`, createRule 164–176 and update reconciliation 393–407 pass saved/normalized.nextDate directly, preserving the original fixed day across the whole materialization. `domain/cashflow/CashFlowCalculator.kt`, getUpcomingBills 463–510, merges projected/materialized rows by key, so differing dates survive together. `domain/forecasting/FinancialStressForecastEngine.kt` 316–320, 337–380 does the same and sums both.
Impact path: create a MONTHLY rule anchored 2027-01-31, with no matching actuals → persisted expansion contains Feb 28 and Mar 31. On Feb 28, getUpcomingBills(30) projects through Mar 30: catch-up hands Feb 28 to expand, which emits Mar 28. The stored Mar 31 is outside this window, so the UI shows a bill too early. On Feb 1, the production 60-day financial-stress horizon (FinancialStressForecastEngine 129–136, 219–223) includes both March dates: projection emits Mar 28, materialized state supplies Mar 31, and the merge/sum counts both. This is NEW cross-path divergence after the fixed-anchor fix, not the intended D7 correction jump and not a historically drifted persisted nextDate.
Caller trace: CashFlowCalendarViewModel.loadUpcomingBills 161–165 → CashFlowCalculator.getUpcomingBills(30) → projectOccurrences → expander → merge; financial stress recurring-outflow calculation uses the same projection/merge over its horizons. calculateDailyCashFlow 168–172, 221–230 is also affected for ranges spanning the clamped month and following due dates.
Existing tests/guards: RecurringOccurrenceExpanderTest, `anchor before range is advanced without emitting pre-range occurrences`, 226–242 passes the original Dec-31 anchor directly and expects Feb-28/Mar-31. It does not exercise the coordinator's preliminary catch-up. RecurringLifecycleCoordinatorTest uses a mocked expander (137, plus projection fixtures), so it does not establish real expander/coordinator parity. No tests run.
Cross-cell impact: P-06 forecasting/cash flow, E time/calendar primitives. No duplicate actual expense rows are asserted; harm is false/doubled derived obligations.
Old-ID cross-refs: **FRESH-P4-005 / RP-04 A3 — clearly worsened by incomplete remediation**, not a new ID for the original uniform drift. Diff `9101aab3^..9101aab3` shows fixed-anchor catch-up added without preserving that anchor into ExpandRequest; the same change fixes direct materialization, creating divergent keys. D7 remains accepted: original persisted Jan-31 is available here, so losing it at the intra-operation handoff is not the approved historical-drift behavior.

### CA-P-04-005
ID: CA-P-04-005
Title: Activation can recreate orphan recurring state after a concurrent rule deletion
Defect class: 3 (Atomicity / TOCTOU; also 11 data integrity)
Severity: P1
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`, activateRule 218–239 reads the rule at 221 BEFORE opening the transaction at 222; setActiveStatus at 223 returns no affected-row signal, and generation 241–265 uses the saved entity unconditionally. deleteRule 115–125 atomically removes all derived rows and the rule, but cannot invalidate the activation's saved entity. `data/database/dao/ManualRecurringExpenseDao.kt` 69–74 defines delete and an unchecked Unit-returning update. `data/database/entity/RecurringOccurrence.kt` 12–25 defines no rule FK; the delivery FK only points to the newly created occurrence. Projection then inserts planned rows by ID/key at RecurringPlanProjectionService 63–87 without reading the rule.
Impact path: activateRule reads existing inactive rule → concurrent deleteRule transaction commits → activation transaction UPDATE affects zero rules → materializer inserts occurrences/deliveries and projection inserts planned rows for the deleted rule → RULE_ACTIVATED_REGENERATED event commits despite no rule existing. Dispatch checks only PLANNED occurrence state, allowing ghost reminders; no later rule deletion can clean these up through the missing UI row.
Caller trace: ManualRecurringExpenseViewModel.toggleStatus 130–134 → ManualRecurringExpenseRepository.setActiveStatus 31–36 → activateRule; independently launched deleteExpense 146–150 → repository.deleteById 43–44 → deleteRule. Both viewModelScope.launch entry points have no mutual exclusion; neither repository wraps activation's initial read in a transaction. SubscriptionManagementRepository's activate/delete delegates provide an additional path.
Existing tests/guards: RecurringRuleLifecycleCoordinatorTest.activateRuleMissingIdIsIdempotentNoOp 748–767 checks an ID missing before the initial read. It does not interleave deletion between the successful read and activation transaction. The transaction encloses generation but not the prerequisite read; the write barrier is not a mutex (`DatabaseWriteBarrier.runWrite` 33–38). Tests not run.
Cross-cell impact: P-09 dispatch; P-06 derived planned obligations; P-07 verifier can encounter orphan recurring sources. No restore race is claimed.
Old-ID cross-refs: none for this race. Related ghost-reminder outcome to FRESH-P4-001, whose raw repository delete bypass is repaired; this finding is a distinct race between two legal coordinator operations.

### CA-P-04-006
ID: CA-P-04-006
Title: Bill reminder screen relabels foreign amounts and sums mixed currencies as home-currency totals
Defect class: 7 (Money / currency)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`, getUpcomingReminders 92–100 preserves expense.amount and expense.currency; getMonthlyBillsTotal 162–172 directly sums monthly-equivalent raw amounts across all active rules without conversion or currency grouping. `ui/screens/reminder/BillRemindersViewModel.kt`, calculateMonthlyTotal 46–52 forwards the scalar. `ui/screens/reminder/BillRemindersScreen.kt`, MonthlyBillsCard 107–113 formats that scalar in homeCurrency, and BillReminderCard 190–193 formats reminder.amount in homeCurrency instead of its stored currency. These are source amounts, not pre-converted amounts.
Impact path: EUR home currency + USD monthly subscription of 100 → reminder card shows EUR 100 without FX conversion; adding a EUR 100 subscription yields EUR 200 expected monthly total regardless of exchange rate. Amount display and aggregate both misrepresent financial obligations. No persisted actual-money corruption claimed.
Caller trace: `ui/MainActivity.kt` 748–751, NavigationDestination.BillReminders → BillRemindersScreen → Hilt BillRemindersViewModel init 30–32 → BillReminderManager → RecurringExpenseRepository.getAll → ManualRecurringExpenseDao.getAllActive; screen then attaches home currency to the raw numbers.
Existing tests/guards: BillReminderManagerTest.getMonthlyBillsTotal includes annual/semiannual/irregular frequency semantics (81 onward), but fixtures use EUR (130). BillRemindersViewModelTest mocks a precomputed scalar and USD-only reminders; neither covers heterogeneous currency aggregation or foreign reminder rendering. No runtime validation performed.
Cross-cell impact: E money/currency; bill reminder UI, distinct from worker notification text (which correctly uses occurrence.expectedCurrency).
Old-ID cross-refs: none found in recurring registry/remediation, still-open ledger, or deferred handoff. The UI's documented legacy reminder-source migration does not authorize mixed-currency arithmetic or relabeling.

### Coverage checkpoint 3
- Full-file budget reached: **20/20**. Added `data/database/dao/PlannedExpenseDao.kt` 1–176, `data/backup/DatabaseWriteBarrier.kt` 1–40, `domain/reminder/BillReminderManager.kt` 1–174, `data/database/RoomDomainTransactionRunner.kt` 1–47. No more full-file reads after this checkpoint.
- Planned DAO: checked all insert/delete/status/fulfill/unlink/derived-key SQL; open key moves with status, FULFILLED snapshots excluded from rule-update snapshot writes. Occurrence/event tables do not FK to rule; delivery FK cascades from occurrence.
- Barrier/runner: runWrite is check + block, not a lease or lock. DomainTransactionRunner executes its block inside Room transaction; does not swallow cancellation.
- Followed transaction-side post-commit factories through `TransactionSideEffectPlanner.kt` 315–420 (create matching, update reconcile, delete unlink); these call the audited coordinator and rethrow cancellation. Broader dispatcher replay semantics are owned by P-02 and were not re-audited here.
- Read cash-flow consumer projection, merge, and UI entry excerpts; financial-stress projection/merge/sum; ForecastInputAssembler 403–525. The single-month assembler can avoid the particular multi-period clamp divergence; not all forecasting calls are asserted broken.
- Read TimePeriodUtils calendar arithmetic 1344–1409; fixed-day month helper preserves time-of-day and clamps correctly when the caller preserves the anchor argument. The defect is the coordinator's handoff, not the helper.
- Read BillReminderManager's complete legacy read path and targeted bill-reminder UI/VM formatting branches; deprecated markBillPaid throws and has no live mutation role. IRREGULAR return-empty is explicitly planned behavior, not a bug.
- Read focused action tests, rule-update/activation/invariant tests, expander anchor tests, DirectEventDaoInsertGuardTest recurring exemption records and WorkerSpec bill entry. Builds/tests/guards remain NOT RUN.

### CA-P-04-007
ID: CA-P-04-007
Title: Bill reminder failure paths send raw Throwables directly to Android logs
Defect class: 9 (Privacy; also 10 worker diagnostics)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt`, doWork 126–128, 154–156 and 167–170 passes the caught exception to Log.w/Log.e; sendNotification 261–267 does the same for SecurityException and other notification exceptions. The outer catch covers coordinator/database operations as well as notification posting. There is no bounded reason-code/class-only transformation before these Android Log calls. The safe DiagnosticEvent metadata used on the successful/permission branches does not intercept them.
Impact path: a reminder DAO/Room/platform or diagnostic writer throws → raw exception message and stack are emitted to Android logs before the guard classifies the run → the repository's no-stacktrace/no-arbitrary-message logging boundary is violated. This is a proven unsanitized logging path; no particular user's PII payload or external exfiltration was demonstrated, so severity is P2 rather than asserting a P0 data leak.
Caller trace: enabled WorkerRegistry bill schedule → BillReminderWorker.doWork → coordinator recover/claim/mark or diagnostic emission/platform notify → broad catch → android.util.Log throwable overload.
Existing tests/guards: reviewed worker tests focus on dispatch, permission handling and counters; no log-sink redaction assertion found. The calls bypass Timber, and the targeted `app/proguard-rules.pro` search found no android.util.Log stripping rule. Release artifact contents were not built or inspected; source-level exposure and debug behavior are the established evidence.
Cross-cell impact: P-08 privacy/diagnostics and P-09 worker logging. P-04 owns these call sites.
Old-ID cross-refs: none found in the still-open/revalidated registries, RP-14 retention perimeter, or RP-16 worker-notification remediation. This is not the separate known transaction-event snapshot retention issue FRESH-P8-001.

## All 15 defect classes — disposition

| Class | Evidence checked and result |
|---|---|
| 1 Legal path | Followed manual/subscription repositories into the rule coordinator; current toggle/delete are repaired; display category carve-out explicitly allowed. Materializer and projection entry callers checked. Inactive regeneration (003) violates the lifecycle contract inside its legal writer. Transitional direct event DAO writes remain approved/ledgered debt, not new findings. |
| 2 Barrier | Read DatabaseWriteBarrier completely; checked every audited mutation's check/runWrite, action-worker checkpoints, and WorkerExecutionGuard 323–360 lease/start/privacy/permission gates. runWrite is not synchronization; do not equate a named scope with atomicity. Read-only projection's absence of a write check is intentional. No independently established new restore/write bypass promoted; restore internals remain shared P-07/P-09 scope. |
| 3 Atomicity / TOCTOU | Read rule CRUD transaction boundaries, materialization/event coupling, conditional occurrence/delivery claims, linked snapshots and unlink reads. Concrete activation/delete race is 005. updateRule pre-read candidate not promoted because live update callers establish an outer Room transaction. |
| 4 Idempotency / duplicates | Verified unique occurrence keys and delivery occurrence/window keys, IGNORE result handling, conditional claim, terminal preservation, planned open-key maintenance. New projection/materialization key divergence is 004. Global actual reuse across rules is already explicit P4-CURRENT-011 debt and excluded. |
| 5 Cancellation | Worker broad catches rethrow CE; materializer/projection/writer have no swallowing catch; coordinator regeneration/bulk catches invoke CancellationSafe. RoomDomainTransactionRunner has no catch. Supporting legacy UI catches contain broad failure fallbacks; a complete UI cancellation audit is outside this bounded cell. No tests claimed. |
| 6 Side-effect timing | Worker posts only after claim/revalidation; markSent plus event is a subsequent transaction, so process death can replay a stable notification ID. Factories for matching/reconcile/unlink are PostCommitActions; TransactionLifecycleCoordinator update 1127–1136 runs them after transaction closure. No assertion of exactly-once Android posting. Notification action effects are missing (001). |
| 7 Money / currency | Materializer forwards expected/paid currencies; resolver checks finite amounts and same currency. Bill screen's mixed sums/foreign-currency relabeling are 006. Legacy calculatePlannedVsActualReport also sums raw currencies but has no external live callers, so not promoted as a production money error. |
| 8 Time correctness | Read expansion and window scheduling fully; checked half-open range queries, TimeProvider, calendar-day arithmetic, month-anchor helper and coordinator handoff. D7 accepted; new split-path anchor regression is 004. Quiet-hours implementation and timezone-change migration were not fully audited. |
| 9 Privacy | Event writer has a barrier and propagates critical failure; reviewed metadata and controlled notification reason constants. Worker raw throwable logging is 007. Financial lifecycle event metadata exists; full retention/export/anonymization tracing was not performed and no separate leak claim is made. |
| 10 Worker hygiene | Bill and both actions use guard; actions do not require posting permission; bill guard checks permission and notify catches revocation. Actual send precedes success metric. Lost transient retry is 002. WorkerSpec enables a six-hour schedule; source inventory saying disabled is stale. |
| 11 Data integrity | Read rule/occurrence/delivery/planned/event DAOs end-to-end and entity FK/index excerpts. Verified fulfill/unlink updates, rule-update invariant checks and cascade relationships. Inactive derived rows (003) and activation orphans (005) are concrete. No schema mutation performed. |
| 12 Error handling | Traced insert conflicts, no-op/missing action results, failed send classification and worker completion. Findings 001/002 capture unreachable recovery/false action success. Critical writer does not suppress failures; regeneration diagnostic catches are cancellation-safe. |
| 13 Wiring / dead code | Traced actual UI and worker entry points, manifest receivers, WorkManager registry/spec, DI bindings for event writer and transaction runner. projectFromRule deletion and legacy reconciliation retention are D4/RP-04 accepted. No live severity assigned to convenience status/report methods with no external caller found. Notification buttons are wired but ineffective (001). |
| 14 Test correctness | Read focused test bodies and inventories; action tests mock the state machine, coordinator projection tests mock the expander, missing-ID activation test lacks interleaving. RecurringArchitectureGuardTest 393–401 named “generation is atomic” checks substring presence/no catch, not location of the rule read. Gaps attached to findings, not inflated into duplicate findings. RP-21 fixture/hang debt excluded. |
| 15 Fix-regression | Inspected `9101aab3^..9101aab3` coordinator diff and pinned fixed-anchor materialization/expander. 004 is the worsened FRESH-P4-005 cross-path divergence introduced by fixing direct expansion without preserving the handoff anchor. Other FRESH-P4 items are not restated. |

## Known-debt reconciliation and unpromoted leads

- FRESH-P4-001/002: source now delegates subscription delete/toggle to coordinator; no repeated findings for the old bypasses. Findings 003/005 explicitly identify different causes after that repair.
- FRESH-P4-003: new reconciliation preserves unmatched overdue PLANNED rows; no restatement of old delete-all behavior.
- FRESH-P4-004: projectFromRule deleted as approved; no dead-code finding.
- FRESH-P4-005: uniform historical drift not re-reported; 004 records the new materialized/projected disagreement after RP-04 A3, with a rule whose original Jan-31 anchor still exists.
- FRESH-P4-006: accepted permission cancellation/historical terminal semantics retained; no request to re-enable FAILED_PERMISSION retries.
- P4-CURRENT-011 expense reuse, approved direct-event-DAO transition debt (also DirectEventDaoInsertGuardTest recurring exemptions), legacy reminder screen migration, and RP-21 test-harness debt are excluded from new findings.
- Rule-update retirement deletes a PLANNED occurrence after deleting only open deliveries; entity CASCADE also deletes SENT/DISMISSED deliveries. This contradicts the DAO comment that history survives, but a sufficiently direct live date/frequency-edit caller and baseline distinction were not established. Retained as an unpromoted lead, not counted.
- Documentation drift observed: writer no longer offers writeDiagnostic despite LEGAL_PATHS/segment dual-channel text; inventory says bill worker disabled while WorkerSpec version 2 enables it; receiver inventory still describes removed direct DAO dependencies. These are coverage notes, not inflated standalone production defects.

## Limits, validation, and final provenance

- Completed the **bounded static primary-file sweep**, with supporting code read by targeted excerpts; this is not exhaustive whole-repository certification. Primary coordinators, materializer, event writer, bill/action workers, relevant DAOs, expander/resolver/projection, receivers and legacy reminder manager were read end-to-end.
- Full reads: 20/20; no further full-file reads after reaching the cap. Large maps were not loaded wholesale. Coverage/finding records were persisted throughout; no context compaction occurred.
- Not covered end-to-end: shared forecast engines/transaction dispatcher, worker infrastructure internals, restore/DB-interceptor machinery, diagnostics export/retention pipeline, whole UI packages, all test bodies, runtime quiet-hours/timezone transitions. Shared surfaces were followed only far enough for this cell's evidence/caller traces.
- **Validation: NOT RUN** — user required static-only AUDIT; no builds, tests, lint, or guards executed. Source searches, Git reads and document writes only. No production/config/script edits.
- Independent Phase 2 adversarial verification: pending. These are auditor findings, not self-verified campaign conclusions.
- Agents invoked: none. Auditor identity: **astra-cell-auditor (direct session)** / **direct astra session**. Cell: **P-04**. Pin: `37601232b9778170c57a656a245b199ab6d7d965`. Finalization date: **2026-09-22**. Exact final timestamp is appended below and recorded in JOURNAL.md.
- Final pin recheck: full HEAD equals the pinned SHA, short HEAD `37601232`, production commit diff and production working-tree status both empty.
- Final timestamp: **2026-09-21T21:02:36Z** (2026-09-22 in the workstation's +03:00 timezone; session date rollover above).
