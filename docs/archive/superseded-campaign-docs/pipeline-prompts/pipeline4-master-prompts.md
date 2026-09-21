# Pipeline 4 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P4 — Recurring / Bill Reminders**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P4 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_4_CONSOLIDATED_ISSUES.md
- P4 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_4_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_SEGMENTS.md
- Legal paths: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/LEGAL_PATHS.md

Important context:
- P4 is **Recurring / Bill Reminders**.
- Architecture segments involved: Segment 7 Recurring Expenses, Segment 36 Bill Reminders, Segment 12 Startup & Background Runtime, Segment 9 Core Expense Management, Segment 18 Backup/Restore, Segment 29 Diagnostics, Segment 30 DI.
- The P4 docs/trackers may be stale or internally inconsistent. Treat docs as context, but **code is source of truth**.
- Specifically validate tracker claims against the target commit. Do not assume “open” or “fixed” without reading code and tests.

---

## Prompt A — P4 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin architecture, data-integrity, and pipeline-debug agent.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P4 — Recurring / Bill Reminders

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 4 end-to-end:
- recurring rule CRUD,
- occurrence expansion/materialization,
- recurring lifecycle events,
- planned recurring expense projection,
- planned-vs-actual reconciliation,
- linking actual expenses to recurring occurrences,
- unlinking/relinking after expense update/delete,
- bill reminder delivery generation,
- bill reminder worker dispatch,
- reminder claim/retry/exactly-once behavior,
- reminder snooze/dismiss receivers,
- worker scheduling/guard/logging,
- restore/write-barrier behavior,
- diagnostics/event emission,
- cross-pipeline side effects from transaction lifecycle.

Read first:
- `docs/analyses and debug master/PIPELINE_4_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_4_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs if referenced by P4.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that spirit:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read if relevant:
- UI/review screens: `COMPREHENSIVE_UI_MAP.md`, `VIEWMODEL_INJECTION_MAP.md`, `route-viewmodel-map.md`
- Privacy/diagnostics: `PRIVACY_UI_ARCHITECTURE.md`, `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export: `DATABASE_BASELINE_POLICY.md`, `DB_WRITE_OWNERSHIP.md`, `backup-restore-barrier-contract.md`, `expense-mutation-inventory.md`

For P4 specifically, pay special attention to:
- `LEGAL_PATHS.md` recurring rule/occurrence/reminder legal paths.
- `CODEBASE_SEGMENTS.md` Segment 7 and Segment 36.
- worker architecture docs for `WorkerExecutionGuard`, `WorkerRegistry`, `WorkerSpecScheduler`.
- DB ownership docs for direct DAO writes.

## 4. Build a pipeline file inventory

Do not rely only on this seed list. Use `rg`, import graph, Hilt map, DAO map, callers/callees, and tests to build the real inventory.

Start with these likely files:

### Recurring domain/coordinators
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/OccurrenceGenerationOptions.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringExpenseReconcileResult.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleEventWriter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceStatus.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`

### Reminder domain/worker
- `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderSettings.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderSettingsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt`
- `app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt`

### Repositories
- `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/PlannedExpenseRepository.kt`
- any repository that calls recurring, planned expense, or reminders.

### DAOs
- `ManualRecurringExpenseDao.kt`
- `RecurringOccurrenceDao.kt`
- `RecurringReminderDeliveryDao.kt`
- `RecurringLifecycleEventDao.kt`
- `PlannedExpenseDao.kt`
- `ExpenseDao.kt`
- any DAO discovered through callers/callees.

### Room entities / schema touchpoints
- `ManualRecurringExpense.kt`
- `RecurringOccurrence.kt`
- `RecurringReminderDelivery.kt`
- `RecurringLifecycleEvent.kt`
- `PlannedExpense.kt`
- `Expense.kt`
- `AppDatabase.kt`
- all migrations touching recurring/planned/reminder tables, schema version, indices, unique constraints.

### Worker infrastructure
- `WorkerExecutionGuard.kt`
- `WorkerGuardRequest.kt`
- `WorkerRunLogger.kt`
- `WorkerRunContext.kt`
- `WorkerSpec.kt`
- `WorkerSpecScheduler.kt`
- `WorkerRegistry.kt`
- `RetryableWorkerException.kt`
- DI bindings for workers.

### Cross-pipeline callers
Trace these even if they live outside P4:
- `TransactionLifecycleCoordinator.kt`
- transaction side-effect planner/dispatcher files
- expense update/delete paths that call recurring reconcile/unlink
- cash-flow/forecast/dashboard paths that read recurring projections
- backup/restore maintenance mode and write/read barriers
- diagnostic/event writer classes.

### Hilt modules
Inventory all Hilt modules/bindings that provide:
- recurring lifecycle coordinators,
- reminder settings repository,
- worker dependencies,
- DAOs/database,
- diagnostics,
- TimeProvider,
- write/read barriers.

### UI
If recurring or bill reminder UI exists, include:
- screens,
- ViewModels,
- routes,
- state models,
- action handlers,
- tests.

If no UI is involved, explicitly say “UI not reached” with evidence.

### Tests
Search:
- `app/src/test/**/recurring/**`
- `app/src/test/**/reminder/**`
- `app/src/test/**/worker/**`
- `app/src/androidTest/**`
- tests matching `*Recurring*`, `*Occurrence*`, `*BillReminder*`, `*PlannedExpense*`, `*Worker*`, `*Restore*`, `*Barrier*`.

Known visible test seeds:
- `app/src/test/java/com/yourname/expensetracker/domain/recurring/RecurringLifecycleFixesTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/service/reminder/BillReminderWorkerTimeProviderTest.kt`

Do not stop at these. Search the whole repo.

Also inventory:
- diagnostics/event writers,
- migrations/schema touchpoints,
- parsers/importers/exporters if they can create/update/delete expenses that trigger recurring side effects,
- backup/export/restore code if it serializes or blocks recurring tables.

## 5. Code-reading rules

Mandatory rules:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search both direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Check whether methods are public and callable from bypass paths.
- Check whether tests actually assert the invariant, not just instantiate the class.
- If a tracker says fixed/open, validate in code at the target SHA.

Use searches like:
- `rg -n "Recurring|recurring|Occurrence|occurrence|Reminder|reminder|Bill|bill"`
- `rg -n "linkExpenseToOccurrence|unlinkExpenseFromOccurrence|reconcilePlannedVsActual|claimReminderDelivery|getDueReminders"`
- `rg -n "ManualRecurringExpenseDao|RecurringOccurrenceDao|RecurringReminderDeliveryDao|RecurringLifecycleEventDao|PlannedExpenseDao"`
- `rg -n "System.currentTimeMillis|hashCode\\(|JSONObject|metadata = \"\"\"|CancellationException|catch \\(e: Exception\\)"`
- `rg -n "DatabaseWriteBarrier|writeBarrier|WorkerExecutionGuard|runGuarded|WorkerRunLogger"`
- `rg -n "insert\\(|update\\(|delete\\(" app/src/main/java/com/yourname/expensetracker/data/database/dao`

## 6. Universal contracts to verify

Audit these for P4:
1. Restore/write barrier:
   - every P4 write checks `DatabaseWriteBarrier`,
   - no writes during restore/backup modes,
   - worker execution is blocked/drained correctly.

2. Worker guard and run logging:
   - `BillReminderWorker` uses `WorkerExecutionGuard`,
   - run start/success/skip/retry/failure is logged,
   - quiet-hours/settings skips are inside guard if they should be logged,
   - retry classification does not swallow cancellation.

3. Privacy/redaction/raw-storage policy:
   - no raw PII in diagnostics/logs/events,
   - user strings in metadata are escaped or written via safe APIs,
   - reminder notification content is reasonable and not overexposed.

4. Money/currency normalization:
   - expected/paid amounts are finite,
   - currencies are non-blank and consistently compared/normalized,
   - recurring amounts do not bypass MoneyNormalizationEngine expectations if applicable.

5. Transaction lifecycle ownership:
   - actual expense creates/updates/deletes trigger recurring link/reconcile/unlink only through legal lifecycle side effects,
   - no direct `ExpenseDao` writes create duplicate money records.

6. Receipt lifecycle/link ownership:
   - receipts creating expenses still reach transaction lifecycle and recurring side effects if relevant,
   - no receipt-created expense bypasses P4 linking.

7. Recurring planned/actual reconciliation:
   - actual payment marks occurrence PAID,
   - planned expense becomes fulfilled,
   - reminders are suppressed,
   - expense update/delete relinks/unlinks safely,
   - reconciliation read methods do not surprise-write unless explicitly named and documented.

8. Diagnostics/drop reasons/events:
   - critical lifecycle events are durable,
   - diagnostic events are best-effort and cancellation-safe,
   - failures are not silently swallowed.

9. Import/export schema/roundtrip:
   - recurring/planned/reminder tables are backed up/restored safely if included,
   - restore does not leave stale Room instance or partially swapped DB,
   - schema keys/indices survive roundtrip.

10. DAO conflict handling and timestamps:
   - `IGNORE` insert results are checked,
   - updates set `updatedAt`,
   - unique constraints prevent duplicates,
   - conditional claims are atomic.

## 7. P4-specific invariants to audit

### Rule CRUD
Legal path:
- create/update/activate/deactivate/delete rule must go through `RecurringRuleLifecycleCoordinator`.
- Repositories should delegate to coordinator.
- Direct `ManualRecurringExpenseDao.insert/update/delete` outside coordinator is a bug unless explicitly grandfathered and documented.

Check:
- barrier before mutation,
- transaction atomicity,
- lifecycle events,
- generated occurrences/reminders/planned rows rollback together,
- inactive rules do not generate,
- deactivation deletes open planned future rows/reminders as architecture says,
- terminal statuses are preserved during regeneration.

### Occurrence generation/materialization
Check:
- `RecurringOccurrenceExpander` produces deterministic occurrence keys,
- `OccurrenceConflictResolver` matches actual expenses correctly,
- `RecurringOccurrenceMaterializer` handles insert conflict and status transition,
- PAID occurrences fulfill planned rows and suppress reminders,
- reminder windows default correctly,
- past-due reminder rules are correct,
- terminal occurrence statuses are not downgraded,
- lifecycle events are written.

### Expense → occurrence link
Check:
- lookup and conditional claim are inside a DB transaction,
- claim uses `WHERE status=PLANNED AND linkedExpenseId IS NULL`,
- no race can double-link one occurrence or one expense,
- matching respects date, merchant key, amount tolerance, currency, transaction type, ownership,
- `linkExpenseToOccurrenceDetailed()` returns real IDs and explicit `Error` for impossible states,
- no `0L` placeholder IDs.

### Expense update/delete reconciliation
Check:
- update after amount/date/currency/merchant/ownership change relinks/unlinks correctly,
- delete unlinks and reopens PLANNED occurrence,
- planned rows are unfulfilled safely,
- reminders are regenerated only when valid,
- bulk reconcile is bounded and not a global unsafe scan.

### Bill reminder delivery
Check:
- due reminders query only SCHEDULED reminders for PLANNED occurrences,
- stale CLAIMED recovery is safe,
- claim is atomic,
- worker revalidates after claim before notifying,
- marking sent/failed only applies from CLAIMED,
- notification IDs and PendingIntent request codes are stable and unique,
- no `hashCode()` collision risk,
- notification permission failure is handled,
- quiet hours/settings are respected and logged,
- cancellation propagates.

### Snooze/dismiss
Check:
- receivers use coordinator methods,
- operations are barrier-guarded and transactional,
- terminal statuses are no-ops,
- events are written.

### Planned-vs-actual reconciliation
Check:
- `reconcilePlannedVsActual()` does not perform hidden writes if it appears query-like, or this is explicitly documented and accepted.
- Prefer split design:
  - pure read report method,
  - separate ensure/apply generation method.
- Validate tracker issue `NEW-P4-008`.

### Occurrence key collision
Check deferred issue `P4-P1-05`:
- Can `occurrenceKey` collide across source types?
- Is uniqueness scoped by source type or only key?
- Does this need migration?
- If still unresolved, classify as deferred design/migration with exact schema evidence.

## 8. Known P4 issue set to validate

Read P4 consolidated issue doc and implementation plan, then validate each against code.

Pay special attention to:
- `P4-P1-05`: occurrenceKey can collide across source types — deferred design/migration.
- `NEW-P4-003`: race in `linkExpenseToOccurrence` — lookup outside transaction. Validate whether fixed at this SHA.
- `NEW-P4-005`: notification ID collision risk.
- `NEW-P4-006`: PendingIntent request code collision.
- `NEW-P4-008`: `reconcilePlannedVsActual` has write side-effects in query-like method.
- `NEW-P4-009`: JSON injection / unsafe metadata interpolation.
- `NEW-P4-010`: impossible state should return explicit error, not skipped.
- fixed-claim checks:
  - cancellation exceptions rethrown,
  - worker uses `TimeProvider`,
  - worker uses guard,
  - default reminder windows apply,
  - paid occurrence suppresses reminders,
  - CRUD owned by coordinator,
  - status updates write events,
  - restore/write guard exists.

Important: If P4 docs say one thing and implementation plan/code say another, report doc/code/tracker drift.

## 9. Review dimensions

Check:
- correctness,
- data integrity,
- atomicity/transactions,
- lifecycle bypasses,
- direct DAO writes,
- restore/export safety,
- privacy fail-closed behavior,
- raw PII storage/logging,
- cancellation handling,
- coroutine races,
- WorkManager retry/idempotency,
- dedupe/conflict behavior,
- state-machine transitions,
- timestamp/currency defaults,
- schema/migration compatibility,
- Hilt binding correctness,
- UI state consistency if relevant,
- diagnostics coverage,
- test coverage,
- performance risks,
- security risks.

## 10. Required output format

Produce this exact structure:

# Pipeline 4 Review — Recurring / Bill Reminders

## 1. Pipeline summary
- What P4 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |
Include:
- entry points,
- services/coordinators,
- repositories,
- DAOs,
- Room entities,
- workers,
- receivers,
- parsers/importers/exporters if relevant,
- Hilt modules,
- ViewModels/UI if reached,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow Segment 7 / Segment 36 ownership?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- rule create/update/delete,
- occurrence generation,
- expense create/update/delete side effect,
- reminder dispatch,
- snooze/dismiss,
- restore/worker gating,
- diagnostics/events.

## 5. Issue table
Use columns:
| ID | Severity P0/P1/P2/P3 | Status bug/partial/TODO/fixed/design | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |

Every finding must have concrete evidence:
- file path,
- method name,
- relevant condition,
- why it violates contract.

## 6. Universal contract audit
Subsections:
- restore barrier,
- privacy/redaction,
- lifecycle ownership,
- worker guard/run logging,
- money/currency normalization,
- diagnostics/events,
- import/export/backup if relevant,
- DAO conflict/timestamps.

For each, verdict:
- PASS,
- FAIL,
- PARTIAL,
- NOT APPLICABLE,
with evidence.

## 7. P4 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |
Include all old and new P4 issues from `PIPELINE_4_CONSOLIDATED_ISSUES.md`.

## 8. Test coverage review
- Existing tests found.
- What each test proves.
- Missing tests.
- Weak tests that do not assert the important invariant.

## 9. Test plan
Include:
- unit tests,
- integration tests,
- regression tests,
- instrumentation tests if needed,
- manual validation scenarios.

## 10. Optional deliverables
Include at least one:
- Mermaid/text data-flow diagram,
- call graph,
- dependency map,
- legal write path table,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P4 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

## 12. Completion criteria

The review is not complete until:
- P4 issue doc was read,
- master/universal trackers were read,
- architecture docs were checked,
- all relevant source files were inventoried,
- key callers/callees were traced,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P4 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P4 — Recurring / Bill Reminders

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P4 issues. Do not perform broad refactors.
Preserve existing architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_4_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_4_IMPLEMENTATION_PLAN.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`
- DB/restore docs if touching schema/restore/import/export.

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually wrong.
If only docs are stale, report docs drift instead of changing code.

## 3. Implementation constraints

Follow P4 legal paths:
- rule CRUD only through `RecurringRuleLifecycleCoordinator`,
- occurrence generation/materialization through recurring lifecycle coordinators/materializer,
- expense actual-payment linkage through `RecurringLifecycleCoordinator`,
- worker dispatch through `BillReminderWorker` + `WorkerExecutionGuard`,
- critical events through recurring lifecycle event writer where required,
- all writes guarded by `DatabaseWriteBarrier`,
- no direct DAO lifecycle bypasses,
- no raw string status update APIs where typed status is required.

General rules:
- Keep changes minimal and targeted.
- Add or update tests for every fixed issue.
- Do not introduce schema migration unless explicitly required and approved.
- Do not mask `CancellationException`.
- Do not use `System.currentTimeMillis()` where `TimeProvider` exists.
- Do not use `hashCode()` for stable persisted/user-visible IDs.
- Do not write unescaped user strings into JSON metadata.
- Do not add hidden writes to read/query methods.
- Do not make worker retries ambiguous; use the project’s retry contract.

## 4. Candidate P4 fix areas

Validate first, then fix if still broken:

### Core correctness
- `NEW-P4-003`: occurrence lookup + claim must be inside one transaction.
- `NEW-P4-008`: split planned-vs-actual reconciliation into:
  - pure read/report method,
  - explicit ensure/apply/generate method if writes are needed.
- `NEW-P4-010`: impossible state after successful link should return explicit `Error` or throw as designed, not `Skipped`.

### Reminder safety
- `NEW-P4-005`: notification IDs must be stable and unique per delivery.
- `NEW-P4-006`: PendingIntent request codes must be stable and unique per delivery/action.
- Worker must claim, revalidate, send, mark sent/failed exactly once.

### Metadata/security cleanup
- `NEW-P4-009`: use `JSONObject.put()` or safe metadata builders for all user-controlled strings.
- Search all recurring lifecycle event metadata interpolations:
  - merchant,
  - reason,
  - window,
  - currency,
  - source,
  - any user-visible text.

### Universal checks
- cancellation exceptions rethrown,
- write barrier on every mutation,
- worker guard around work,
- diagnostics best-effort but cancellation-safe,
- no direct DAO write bypasses.

### Deferred design
- `P4-P1-05` occurrenceKey collision:
  - Do not implement migration unless explicitly requested.
  - Produce design/migration plan if still unresolved.

## 5. Required tests

Add or update tests as appropriate:

### Atomic link / race
- concurrent link attempts cannot double-link one occurrence,
- concurrent expenses cannot corrupt planned fulfillment,
- successful link writes event, fulfills planned row, suppresses reminders.

### Reconcile purity
- pure planned-vs-actual report performs zero writes,
- explicit generation/apply method performs expected writes,
- existing callers use correct method.

### Reminder IDs
- notification IDs are deterministic and unique for distinct delivery IDs,
- snooze and dismiss PendingIntent request codes differ for same delivery,
- IDs survive process restart assumptions.

### Metadata safety
- merchant/reason/currency/window strings with quotes/braces/newlines produce valid JSON,
- no unsafe string interpolation remains for user-controlled metadata.

### Cancellation
- `CancellationException` propagates through loops and best-effort diagnostic catches.

### Worker/guard
- disabled reminders and quiet hours are logged/skipped under guard if intended,
- failed notification permission marks failure or skip as designed,
- sent notification only marks from CLAIMED.

### Restore/write barrier
- rule mutation blocked during restore/backup mode,
- worker run blocked/skipped when writes are not allowed,
- snooze/dismiss blocked safely during restore.

## 6. Validation commands

Run at minimum:
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Recurring*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Occurrence*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BillReminder*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*PlannedExpense*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Worker*" --stacktrace
./gradlew :app:check --stacktrace
```

If a command cannot run, report:
- exact command,
- failure reason,
- whether failure is related to P4,
- what still needs manual validation.

## 7. Required output

Produce:

## Summary
- Issues fixed.
- Issues confirmed already fixed.
- Issues deferred/design-only.
- Issues not touched and why.

## Changed files
| File | Change | Issue IDs | Tests |
 
## Issue reconciliation
| ID | Before | After | Evidence | Tests |

## Test results
- Commands run.
- Pass/fail.
- Relevant logs.

## Remaining risks
- Highest risk.
- Cross-pipeline impacts.
- Any migration/design follow-up.

## Commit plan
Split into safe PRs:
1. core recurring correctness,
2. reminder worker/notification safety,
3. metadata/security cleanup,
4. docs/tracker sync if needed.
```

---

## Prompt C — P4 Final Validation / Fixed-Claims Audit Prompt

Use this after fixes land.

```text
You are a senior validation/debugger agent.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Target:
Use the current working branch/commit provided by the user.
Baseline context commit:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P4 — Recurring / Bill Reminders

Mode:
Validation of already-fixed claims.
Do not implement new fixes.
Verify whether P4 can be marked GREEN/YELLOW/RED.

## 2. Required reading

Read:
- P4 consolidated issue doc,
- P4 implementation plan,
- master tracker,
- universal tracker,
- all architecture docs listed in Prompt A,
- changed source files,
- changed tests,
- migration/schema files if touched.

Do not trust PR descriptions or comments.
Validate against code and tests.

## 3. Claims to validate

Validate:
- all P4 old issues marked fixed,
- all P4 new issues marked fixed,
- all universal fixes that affect P4,
- all newly added tests,
- no new bypasses introduced,
- no new schema/restore risks introduced.

Specific P4 claims:
- rule CRUD has single coordinator owner,
- actual payment fulfills planned occurrence,
- paid occurrence suppresses reminders,
- reminder dispatch is exactly-once safe,
- worker enabled/scheduled through spec/registry,
- default windows applied,
- terminal statuses not downgraded,
- materializer writes status events,
- restore guard present on writes,
- legacy bill manager cannot bypass lifecycle,
- occurrence lookup/claim atomic,
- notification/PendingIntent IDs stable,
- `reconcilePlannedVsActual` behavior is explicit and safe,
- metadata JSON safe,
- impossible states explicit,
- cancellation propagates.

## 4. Required validation steps

1. Build exact file inventory.
2. Trace runtime flows.
3. Compare code to `LEGAL_PATHS.md`.
4. Run targeted tests.
5. Review test assertions for real coverage.
6. Check direct DAO writes.
7. Check worker guard/logging.
8. Check restore/write barrier.
9. Check diagnostics/privacy.
10. Check migrations/schema if touched.

## 5. Required output

Produce:

# P4 Fixed-Claims Validation

## 1. Verdict
GREEN / YELLOW / RED

## 2. Claims table
| Claim | Source doc/PR | Validated? | Evidence | Remaining risk |

## 3. Regression search
| Area | Search/check performed | Result |

## 4. Test validation
| Test | What it proves | Weakness/gap |

## 5. Contract audit
- restore barrier,
- worker guard,
- lifecycle ownership,
- money/currency,
- diagnostics/privacy,
- DAO conflicts/timestamps.

## 6. Remaining issues
| ID | Severity | Status | Evidence | Required next action |

## 7. Production safety
- Is P4 production-safe?
- Highest-risk issue.
- Required fix before GREEN.
```