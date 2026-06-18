# Master Debug/Review Prompt — Pipeline 1: Notification Capture

You are an expert Kotlin/Android architecture debugger and reviewer. Your task is to perform an extensive debug/review of **Pipeline 1 — Notification Capture** in this repository.

## 0. Target repository and version

Repository:

```text
https://github.com/panospao7/Cost-agregator
```

Pinned commit:

```text
83b798e849b4408b2bf683f52cb2746d37f7af16
```

Do not review a different branch/commit unless explicitly told. If the local checkout differs, stop and report the mismatch.

## 1. Mission

Review **Pipeline 1 — Notification Capture** end-to-end.

Primary goals:

1. Understand the actual runtime flow from Android notification listener entry point to saved expense / dropped notification / retry / diagnostic event.
2. Verify that all previously reported P1 issues are truly fixed in code.
3. Find any remaining correctness, privacy, lifecycle, concurrency, atomicity, restore-safety, worker, diagnostic, or test gaps.
4. Compare code against the architecture docs and legal paths.
5. Produce a structured issue report with evidence and recommended fixes.

This is a **deep code audit**, not a shallow docs summary.

## 2. Important warning about docs/status drift

Read both:

```text
docs/analyses and debug master/PIPELINE_1_CONSOLIDATED_ISSUES.md
docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md
```

The P1 consolidated file may show some items as open/partial, while the master tracker may claim later fixes are complete. Do **not** trust either blindly.

Your job is to reconcile:

- pipeline issue doc,
- master tracker,
- actual source code,
- actual tests.

If tracker and code disagree, report it as **doc/code drift** or **tracker/status drift**.

## 3. Docs to read first

Read these before source review:

```text
docs/analyses and debug master/PIPELINE_1_CONSOLIDATED_ISSUES.md
docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md
docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md
```

Read these architecture docs:

```text
docs/architecture/ARCHITECTURE.md
docs/architecture/CODEBASE_SEGMENTS.md
docs/architecture/DEPENDENCY_MAP.md
docs/architecture/LEGAL_PATHS.md
docs/architecture/ENGINE_INTERACTION_MAP.md
docs/architecture/COMPLETE-BACKEND-MAP.md
docs/architecture/BACKEND-MAP-INDEX.md
docs/architecture/CODEBASE_INVENTORY.md
docs/architecture/dao-map.md
docs/architecture/hilt-bindings-map.md
docs/architecture/import-graph.json
```

Also read if relevant/found:

```text
docs/DATABASE_BASELINE_POLICY.md
docs/DB_WRITE_OWNERSHIP.md
docs/backup-restore-barrier-contract.md
docs/expense-mutation-inventory.md
docs/SENSITIVE_DIAGNOSTICS_POLICY.md
docs/PRIVACY_UI_ARCHITECTURE.md
```

If any listed doc does not exist, note it and continue.

## 4. Pipeline definition

Pipeline 1 covers **Notification Capture**:

```text
Android notification posted
→ NotificationCaptureService
→ privacy/capture gate
→ extraction of notification text/extras/messages
→ sensitive-key filtering/redaction
→ NotificationFilter
→ dedupe/fingerprint
→ raw notification/intake persistence or synchronous processing
→ NotificationProcessingPipeline
→ parser / transaction creation / source link / diagnostics
→ retry worker if deferred
→ final outcome and durable diagnostic/drop reason
```

Expected pipeline outcomes include:

- accepted and processed,
- accepted and deferred,
- filtered/dropped with reason,
- duplicate skipped,
- privacy blocked,
- restore/write barrier blocked,
- failed/retryable,
- failed/non-retryable,
- diagnostic emitted.

## 5. Initial source file inventory

Start with these production files, then expand using `rg` and call graph tracing.

### Android/service entry

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt
app/src/main/java/com/yourname/expensetracker/service/NotificationFilterDecision.kt
```

### Notification domain

```text
app/src/main/java/com/yourname/expensetracker/domain/notification/NotificationPersistenceContext.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/NotificationPipelineOutcome.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/RawNotificationFingerprint.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/RawNotificationInsertResult.kt
```

### Notification capture domain

```text
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/CaptureSource.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureDecision.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureDeduper.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureGate.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCaptureResult.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakePayloadRepairer.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeRecoveryScheduler.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTextParts.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientKeyProvider.kt
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientPayloadCrypto.kt
```

### Money signal

```text
app/src/main/java/com/yourname/expensetracker/domain/notification/money/NotificationMoneySignalDetector.kt
```

### Repository / pipeline

```text
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
```

### Worker

```text
app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt
```

### Relevant DAOs

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/NotificationIntakeDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PipelineDiagnosticEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/SourceStatsDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/SourceStatsEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PrivacyAuditDao.kt
```

### Relevant entities

```text
app/src/main/java/com/yourname/expensetracker/data/database/entity/NotificationIntakeEntity.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/NotificationIntakeStatus.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PipelineDiagnosticEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/EntitySourceLink.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PrivacyAuditEvent.kt
```

### Database/migrations/schema

```text
app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt
app/schemas/com.yourname.expensetracker.data.database.AppDatabase/
```

### Cross-cutting dependencies to trace

Find all concrete files for:

```text
DatabaseWriteBarrier
DatabaseReadBarrier
RestoreMaintenanceMode
WorkerExecutionGuard
WorkerRunLogger
WorkerRegistry
DiagnosticEventWriter
NotificationDiagnosticEmitter
PrivacyDecision
RawStorageMode
PrivacySettingsRepository
TransactionLifecycleCoordinator
PostCommitActionRunner
side-effect planner/dispatcher classes
Hashing utilities
parser classes used by NotificationProcessingPipeline
Hilt modules binding NotificationCaptureService dependencies
```

Do not assume this list is complete. Build the final inventory by searching all callers/callees.

## 6. Tests to inspect

Start with these tests:

```text
app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceCleanupTest.kt
app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt
app/src/test/java/com/yourname/expensetracker/service/NotificationFilterTest.kt
app/src/test/java/com/yourname/expensetracker/worker/NotificationIntakeWorkerTimeoutTest.kt
app/src/test/java/com/yourname/expensetracker/consistency/DedupeKeyProducerConsistencyTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/CancellationPropagationContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/LifecycleBarrierContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/MoneyContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/PrivacyStorageContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/SideEffectContractTest.kt
```

Then search for additional tests:

```bash
rg -n "NotificationCapture|NotificationIntake|RawNotification|NotificationProcessingPipeline|NotificationRepository|NotificationFilter|captureGate|NotificationPipelineOutcome|RawStorageMode|PrivacyDecision|markProcessed|NonCancellable|CancellationException|DatabaseWriteBarrier|WorkerExecutionGuard" app/src/test app/src/androidTest
```

## 7. Required search commands

Run broad searches before finalizing conclusions:

```bash
rg -n "NotificationCaptureService|NotificationProcessingPipeline|NotificationRepository|NotificationFilter|NotificationCaptureGate|NotificationIntakeCoordinator|NotificationIntakeWorker|NotificationCaptureDeduper|RawNotification|NotificationIntake|NotificationPipelineOutcome" app/src/main app/src/test app/src/androidTest

rg -n "captureGate|warmUp|PrivacyDecision|RawStorageMode|DO_NOT_STORE|REDACTED|METADATA|PrivacyBlocked|FailClosed|blocksExecution|privacySettingsRepository|getSettings" app/src/main app/src/test

rg -n "DatabaseWriteBarrier|DatabaseReadBarrier|RestoreMaintenanceMode|checkWritesAllowed|checkReadsAllowed|restore|maintenance" app/src/main app/src/test

rg -n "WorkerExecutionGuard|WorkerRunLogger|WorkerRegistry|NotificationIntakeWorker|enqueue|WorkManager|Retry|TimeoutCancellationException" app/src/main app/src/test

rg -n "withTransaction|markProcessed|insertRaw|RawNotificationDao|NotificationIntakeDao|EntitySourceLinkDao|PipelineDiagnosticEventDao|TransactionEventDao" app/src/main app/src/test

rg -n "CancellationException|catch \\(e: Exception\\)|NonCancellable|SupervisorJob|CoroutineScope|launch|async|Semaphore|Mutex" app/src/main/java/com/yourname/expensetracker/service app/src/main/java/com/yourname/expensetracker/domain/notification app/src/main/java/com/yourname/expensetracker/data/repository app/src/main/java/com/yourname/expensetracker/worker

rg -n "extras|EXTRA_TEXT|EXTRA_BIG_TEXT|EXTRA_TEXT_LINES|MessagingStyle|messages|sensitive|SENSITIVE_EXTRAS_KEYS|sha256|fingerprint|dedupe" app/src/main app/src/test
```

## 8. Previous P1 issues to verify

Verify each issue from the P1 issue doc and master tracker against actual code.

At minimum check:

### Original issues

```text
P1-P1-01 — Processing outcomes flattened to Success
P1-P1-02 — No durable diagnostic/drop-reason ledger
P1-P1-03 — Extraction misses textLines/messages
P1-P1-05 — Privacy gate runs after text extraction/filter
P1-P1-06 — Restore guard exists in service but not pipeline
P1-P1-07 — Service shutdown silently loses accepted notifications
```

### New issues

```text
NEW-P1-001 — CancellationException swallowed
NEW-P1-002 — Source-link I/O inside DB transaction
NEW-P1-003 — workTracker.acceptingNewWork dead code
NEW-P1-004 — emitOrderedNotificationEvents silently drops events
NEW-P1-005 — Deposit notifications blocked unconditionally
NEW-P1-006 — "failed" keyword deny overly broad
NEW-P1-007 — captureGate.warmUp race
NEW-P1-008 — processMutex serializes all processing
NEW-P1-009 — Double privacy settings fetch / TOCTOU
NEW-P1-010 — processAndSave marks processed outside transaction
NEW-P1-011 — Redundant SHA-256 implementations
NEW-P1-012 — Unused postTime parameter in computeDedupeKey
NEW-P1-013 — combinedBody passed as bigText / over-inclusive filtering
NEW-P1-014 — Deduper cleanupExpired never called
NEW-P1-015 — IllegalStateException in transaction creates orphaned diagnostic
NEW-P1-016 — Sensitive key exact matching misses camelCase
NEW-P1-017 — Settings observer dies permanently on exception
```

For each, report:

```text
ID
claimed status in P1 doc
claimed status in master tracker
actual status in code
evidence: file + function + line range
test coverage
remaining gap, if any
```

## 9. Universal contracts to audit for P1

The master tracker says shared contracts should be fixed before pipeline-specific fixes. For P1, explicitly audit these:

### Restore/write barrier

Verify:

- service checks restore/maintenance mode before write or enqueue,
- pipeline checks `DatabaseWriteBarrier`,
- repository checks `DatabaseWriteBarrier`,
- worker respects restore/maintenance mode,
- no write path bypasses barrier,
- blocked writes emit diagnostics and do not silently lose data.

### Privacy/redaction/raw storage

Verify:

- privacy decision is fail-closed,
- capture gate runs before raw extraction/storage when required,
- no raw PII is logged,
- no raw notification body/extras stored when `DO_NOT_STORE`,
- redacted/metadata modes store only allowed data,
- transient retry payload is encrypted,
- privacy settings are read once per capture or otherwise protected from TOCTOU,
- privacy audit/diagnostics are emitted without leaking sensitive data.

### Transaction lifecycle

Verify:

- P1-created expenses use the legal transaction lifecycle path,
- direct `ExpenseDao` writes are allowed only if explicitly legal,
- source links and transaction events are created atomically or post-commit safely,
- no orphaned expense/source/diagnostic records.

### Worker guard and run logging

Verify:

- `NotificationIntakeWorker` uses worker guard/run logger if architecture requires,
- timeout/retry behavior is correct,
- duplicate work is idempotent,
- worker does not process during restore/export/blocked privacy state,
- worker records success/failure/drop reason.

### Diagnostics/drop reasons

Verify every terminal path emits a durable diagnostic/drop reason:

- privacy blocked,
- restore blocked,
- filtered,
- duplicate,
- parse failed,
- transaction creation failed,
- source-link failed,
- retry scheduled,
- retry exhausted,
- worker timeout,
- cancellation,
- DB conflict.

### Money/currency normalization

Verify notification amount/currency parsing:

- does not misread deposits/refunds as expenses unless intended,
- handles currency symbols/codes/locales,
- avoids floating-point precision issues,
- respects money normalization engine or equivalent.

### DAO conflict/timestamp handling

Verify:

- inserts have correct conflict strategy,
- timestamps are deterministic/consistent,
- update/processed marking occurs inside correct transaction,
- duplicate notification and source-link handling are idempotent.

## 10. Deep review checklist

### Entry and Android lifecycle

Check:

- `onNotificationPosted`,
- listener connected/disconnected behavior,
- permission assumptions,
- service startup/shutdown,
- service coroutine scope,
- cancellation handling,
- null notification/extras handling,
- foreground/background behavior if relevant.

Questions:

- Can a notification be accepted but lost on service shutdown?
- Can first notification after cold start race privacy warm-up?
- Can a cancellation be swallowed and converted to success/failure incorrectly?
- Can listener disconnect cause unprocessed accepted items to disappear?

### Extraction

Check:

- title,
- text,
- big text,
- text lines,
- subtext,
- messaging-style messages,
- extras,
- package name,
- post time,
- notification id/tag/key,
- channel if used.

Questions:

- Are `EXTRA_TEXT_LINES` and `MessagingStyle` messages captured?
- Are sensitive extras removed by normalized/camelCase matching?
- Is fingerprint/dedupe stable but not over-broad?
- Does extraction happen only after privacy permission allows it?

### Filtering

Check:

- deposits,
- refunds,
- failed payments,
- authorizations,
- transfers,
- merchant names containing deny keywords,
- package-level blocklist,
- money-signal detection,
- diagnostics for every filter decision.

Questions:

- Are deposit/refund notifications dropped only when they are not expense signals?
- Is “failed” only blocked in relevant payment/auth context?
- Does filter receive actual `bigText`, not combined text pretending to be bigText?
- Are filter decisions explainable and test-covered?

### Dedupe

Check:

- in-memory dedupe,
- DB dedupe,
- fingerprint algorithm,
- cleanup of expired dedupe entries,
- duplicate diagnostics,
- source link idempotency.

Questions:

- Can two distinct expenses collapse to one?
- Can one notification create duplicate expenses?
- Does dedupe survive service restart if needed?
- Does retry reuse the same dedupe key?

### Intake/deferred retry

Check:

- `NotificationIntakeCoordinator`,
- transient encrypted payload,
- capture result states,
- recovery scheduler,
- payload repairer,
- worker enqueue,
- status transitions.

Questions:

- Is the full accept→intake insert path protected from service cancellation?
- Is `NonCancellable` or application scope used safely?
- Can an intake row exist without recoverable payload?
- Are retryable failures distinguished from terminal failures?

### Processing pipeline

Check:

- `NotificationProcessingPipeline`,
- parser invocation,
- expense creation,
- raw notification insertion,
- processed marking,
- diagnostics,
- source link writing,
- post-commit side effects.

Questions:

- Is `markProcessed(rawId)` inside the same transaction as processing?
- Are source-link writes done post-commit if they are side effects?
- Are diagnostics emitted after rollback-sensitive operations safely?
- Are all outcomes represented by sealed/typed result, not flattened success?

### Worker

Check:

- `NotificationIntakeWorker`,
- timeout handling,
- retry policy,
- idempotency,
- worker guard,
- run logging,
- privacy/restore checks,
- decrypt/reprocess path.

Questions:

- Does worker retry timeout/cancellation correctly?
- Can worker process a notification twice?
- Does worker leak raw text in logs or diagnostics?
- Does worker stop during restore/export/maintenance?

### DB/schema/migration

Check:

- `RawNotification`,
- `NotificationIntakeEntity`,
- `NotificationIntakeStatus`,
- `PipelineDiagnosticEvent`,
- indexes,
- uniqueness constraints,
- migration coverage,
- schema v147 compatibility.

Questions:

- Are conflict strategies correct?
- Are required indexes present for retry scans/dedupe?
- Are status transitions enforceable?
- Can old rows be repaired or recovered?

### Hilt/DI

Check all modules binding P1 dependencies.

Questions:

- Are all constructor dependencies provided?
- Are scopes correct?
- Are service/worker dependencies compatible with Hilt injection?
- Are dispatchers/scopes qualified correctly?

### Tests

Check whether tests cover:

- privacy fail-closed,
- first-notification warm-up race,
- no raw storage in DO_NOT_STORE,
- encrypted transient payload,
- service shutdown during accepted notification,
- cancellation propagation,
- duplicate notifications,
- filter false positives,
- deposit/refund edge cases,
- failed merchant-name edge cases,
- `textLines` and messages extraction,
- markProcessed transaction atomicity,
- worker timeout retry,
- restore barrier,
- diagnostic emission for every terminal outcome.

## 11. Code-reading rules

Follow these rules strictly:

1. Do not trust docs over code.
2. Do not trust tracker statuses over code.
3. If docs and code disagree, report the mismatch.
4. Do not review only filenames; open implementation and tests.
5. Trace real runtime flow, not package structure.
6. Search direct and indirect callers.
7. Include cross-pipeline dependencies.
8. Mark uncertainty clearly.
9. Every finding must have file/function evidence.
10. Do not invent bugs without evidence.
11. If something is safe by design, explain why.
12. If a TODO is harmless, classify as P3/design, not a real bug.
13. If a TODO can cause data loss/privacy leak, classify by impact.
14. Do not make broad rewrites unless required.
15. Prefer minimal, architecture-consistent fixes.

## 12. Required output format

Produce the final report in this structure:

```markdown
# Pipeline 1 — Notification Capture Debug/Review Report

## 1. Executive verdict

Verdict: GREEN / YELLOW / RED

One-paragraph summary.

Highest-risk remaining issue:
Production safety assessment:

## 2. Pipeline flow summary

Describe actual runtime flow.

Include a compact text or Mermaid flow diagram.

## 3. Files reviewed

### Production files reviewed

| File | Role | Notes |
|---|---|---|

### Test files reviewed

| File | Coverage area | Notes |
|---|---|---|

### Files intentionally skipped

| File | Reason |
|---|---|

## 4. Architecture/doc comparison

| Area | Architecture expectation | Actual code | Status |
|---|---|---|---|

Include doc/code drift and tracker/status drift.

## 5. Previous issue reconciliation

| Issue ID | P1 doc status | Master status | Actual code status | Evidence | Test coverage | Notes |
|---|---|---|---|---|---|---|

## 6. New findings

| ID | Severity | Type | Title | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |
|---|---|---|---|---|---|---|---|---|---|---|

## 7. Universal contract audit

### Restore/write barrier
Status:
Evidence:
Gaps:

### Privacy/redaction/raw storage
Status:
Evidence:
Gaps:

### Transaction lifecycle
Status:
Evidence:
Gaps:

### Worker guard/run logging
Status:
Evidence:
Gaps:

### Diagnostics/drop reasons
Status:
Evidence:
Gaps:

### Money/currency normalization
Status:
Evidence:
Gaps:

### DAO conflict/timestamp handling
Status:
Evidence:
Gaps:

## 8. Test coverage assessment

| Behavior | Existing test? | Missing test? | Recommended test |
|---|---|---|---|

## 9. Recommended fix plan

Split fixes into safe PRs:

### PR 1 — Critical correctness/privacy/data-safety
### PR 2 — Worker/retry/diagnostics
### PR 3 — Tests/regression
### PR 4 — Cleanup/docs drift

## 10. Final production-readiness decision

GREEN/YELLOW/RED with justification.
```

## 13. Severity rubric

Use this rubric:

```text
P0 — data loss, data corruption, privacy leak, broken restore safety, duplicate money records, irreversible wrong write.
P1 — major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow, durable diagnostic absence for critical failure.
P2 — edge-case bug, retry/idempotency weakness, poor diagnostics, partial inconsistency, performance bottleneck with user impact.
P3 — cleanup, docs drift, minor TODO, non-critical maintainability.
```

## 14. Validation commands

Run as much as possible locally:

```bash
git rev-parse HEAD
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

If build/test cannot run, report why and still perform static review.

Run focused tests if available:

```bash
./gradlew testDebugUnitTest --tests "*Notification*"
./gradlew testDebugUnitTest --tests "*PrivacyStorageContractTest*"
./gradlew testDebugUnitTest --tests "*CancellationPropagationContractTest*"
./gradlew testDebugUnitTest --tests "*LifecycleBarrierContractTest*"
./gradlew testDebugUnitTest --tests "*DedupeKeyProducerConsistencyTest*"
```

## 15. Completion criteria

The review is not complete until:

- P1 consolidated issue doc was read.
- Master tracker was read.
- Universal tracker was read.
- Architecture docs were checked.
- P1 production source files were inventoried.
- P1 tests were inventoried.
- Entry points and terminal paths were traced.
- Restore/write barrier was audited.
- Privacy/raw-storage behavior was audited.
- Worker retry/idempotency was audited.
- Transaction/lifecycle path was audited.
- Diagnostics/drop reasons were audited.
- Previous P1 issues were reconciled against code.
- New findings have evidence and fix strategy.
- Missing tests are explicitly listed.
- Final verdict is given.

## 16. Source links for context

Commit:

```text
https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
```

P1 issue registry:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_1_CONSOLIDATED_ISSUES.md
```

Master tracker:

```text
https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
```

Architecture folder:

```text
https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture
```