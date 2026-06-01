# Pipeline Issues Master Tracker

> Consolidated P0/P1 issues from all 12 pipeline debug reports.
> Each issue's **full fix strategy + implementation plan** lives in its source debug report.
> Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`
> **Last updated: 2026-06-01 (Pipeline 1 🟢 COMPLETE — all 23 issues FIXED; Pipeline 9-12 statuses reconciled)**
> **Total: 8 P0 + 112 P1 = 120 pipeline issues + 10 universal contracts = 130 items**
> **PIPELINE 1 — FINAL: 🟢 GREEN — 6 original + 17 NEW = 23/23 FIXED**
>   - P1-PR1 through P1-PR6 fully landed + NEW-P1-009 (U-PR5 adapter) closed
>
> **NOTE (2026-05-31):** All 12 pipeline statuses validated against actual HEAD code. Key corrections:
> - P1-P1-02: Was ⚠ PARTIAL → now ✅ FIXED (all drop paths emit diagnostics)
> - P1-P1-05: Was 📝 TODO → now ✅ FIXED (captureGate with cached privacy decision)
> - P1-P1-07: Was 📝 TODO → now ⚠ PARTIAL (intake coordinator exists; small loss window remains)
> - P2-P1-05: Was 📝 TODO → now ✅ FIXED (@RestrictedExpenseDaoMutation + CI test)
> - P4-P1-02: Was 📝 TODO → now ✅ FIXED (RecurringRuleLifecycleCoordinator owns CRUD)
> - P4-P1-04/06/07/08/10: Was 📝 TODO → now ✅ FIXED (materializer + coordinator improvements)
> - P5: 11 of 12 old issues now ✅ FIXED (MoneyNormalizationEngine rollout)
> - P6: 9 of 15 old issues now ✅ FIXED (write barrier + synthesis improvements)
> - P9: All 12 old issues now ✅ FIXED (WorkerExecutionGuard + registry + lease infrastructure)
> - P10-P1-07/08: Was 📝 TODO → now ⚠ PARTIAL (barrier exists but gaps remain)
> - P11-P1-01/02: Was ✅ FIXED → now ⚠ PARTIAL (fingerprint too coarse; other failures ignored)
> - P11-P1-05/06: Was ✅ FIXED/📝 TODO → now ⚠ PARTIAL (barrier/conflict partially addressed)
> - P12-P0-01: Was ⚠ PARTIAL → now 📝 TODO ONLY (no real pipeline verified)
> - P12-P1-02/03/05/08: Was ✅ FIXED → now ⚠ PARTIAL/📝 TODO (gaps found in validation)
> - 126 NEW issues added from deep code audit across all 12 Pipelines
>
> **NOTE (2026-05-30):** Pipeline 3 and Pipeline 4 commits are complete through commit `214c4266`. ~~Statuses in this tracker should be reconciled against the actual evaluations.~~ **RESOLVED 2026-05-31:** All P3/P4 statuses validated against HEAD code — see PIPELINE_3_CONSOLIDATED_ISSUES.md and PIPELINE_4_CONSOLIDATED_ISSUES.md.

## Architectural Strategy (from `response (3).md`)

Fix by **shared architectural contract** first, not strictly pipeline-by-pipeline. Many issues repeat across pipelines:

| Architectural contract | Appears in pipelines |
|---|---|
| Restore/write barrier | 1, 2, 3, 4, 6, 7, 9, 10, 11, 12 |
| Worker guard + run logging | 4, 7, 8, 9 |
| Privacy/redaction/raw storage | 1, 3, 7, 8, 11, 12 |
| Money/currency quality | 5, 6, 12, groups/investment/tax |
| Transaction lifecycle | 1, 2, 3, 10, 11, 12 |
| Receipt lifecycle/link ownership | 3, 9, 11, 12 |
| Recurring planned/actual reconciliation | 4, 6 |
| Diagnostics/drop reasons/events | almost all |
| Import/export schema/roundtrip | 7, 10, 11, 12 |
| DAO insert conflict/timestamps | 2, 3, 4, 6, 10, 11 |

Recommended fix order: global contracts → pipeline-specific fixes.

## Status Legend
- ⬜ NOT STARTED
- 🔧 IN PROGRESS  
- ✅ FIXED
- ⚠ PARTIAL (real code exists but has documented caveats)
- ⏭ DEFERRED (needs design/migration)
- 📝 TODO ONLY (documented, not coded)

---

# Pipeline 1 — Notification Capture

Full source: `PIPELINE_1_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P1-P1-01 | P1 | Processing outcomes flattened to `Success` | Bug | `processInternal()` now returns typed `NotificationPipelineOutcome` sealed interface | ✅ FIXED |
| P1-P1-02 | P1 | No durable notification diagnostic/drop-reason ledger | Enhancement | All drop paths emit via `NotificationDiagnosticEmitter`; service-level + pipeline-level covered | ✅ FIXED |
| P1-P1-03 | P1 | Extraction misses `textLines` and `messages` | Bug | MessagingStyle extraction confirmed working | ✅ FIXED |
| P1-P1-05 | P1 | Privacy gate runs after text extraction/filter | Bug | `captureGate` with cached privacy decision; fail-closed until settings emit | ✅ FIXED |
| P1-P1-06 | P1 | Restore guard exists in service but not in pipeline | Bug | Both `NotificationProcessingPipeline` and `NotificationRepository` now have `writeBarrier.checkWritesAllowed()` guards | ✅ FIXED |
| P1-P1-07 | P1 | Service shutdown silently loses accepted notifications | Bug | `withContext(NonCancellable)` wraps full filter-pass→intake-insert path; worker handles TimeoutCancellationException as retryable; DO_NOT_STORE → synchronous; REDACTED/METADATA → encrypted transient | ✅ FIXED (P1-PR1) |
| NEW-P1-001 | P1 | CancellationException swallowed in captureNotification outer catch | Bug | `catch (e: Exception)` in workTracker.launch does not rethrow CE | ✅ FIXED (U-PR1) |
| NEW-P1-002 | P1 | Source-link I/O inside DB transaction (potential deadlock) | Bug | Source-link writes deferred to post-commit; diagnostics via DeferredSourceLinkDiagnostic | ✅ FIXED (P1-PR2) |
| NEW-P1-003 | P3 | `workTracker.acceptingNewWork` never set to false — dead code | Cleanup | Field, `stopAcceptingAndDrain()`, and `if (!acceptingNewWork)` guard removed; `launch()` now always returns non-null `Job` | ✅ FIXED (P1-PR6) |
| NEW-P1-004 | P3 | `emitOrderedNotificationEvents` silently drops events on null launch return | Bug | `launch()` return type changed `Job?` → `Job` (non-nullable); call site safety comment added | ✅ FIXED (P1-PR6) |
| NEW-P1-005 | P2 | Filter blocks ALL deposit notifications unconditionally | Bug | Deposit deny requires no expense signal; deposits with fees now pass | ✅ FIXED (P1-PR3) |
| NEW-P1-006 | P2 | "failed" keyword deny overly broad | Bug | "failed" only denied in payment/auth context; merchant names pass | ✅ FIXED (P1-PR3) |
| NEW-P1-007 | P2 | Race between captureGate.warmUp() and first notification | Bug | TemporarilyUnavailable enqueues deferred intake via `captureForRetry()` with encrypted transient payload; worker decrypts and processes when gate warms up | ✅ FIXED (P1-PR4) |
| NEW-P1-008 | P2 | processMutex serializes ALL processing | Perf | Replaced Mutex with Semaphore(4) for bounded concurrency | ✅ FIXED (P1-PR5) |
| NEW-P1-009 | P2 | Double privacy settings fetch — TOCTOU race | Bug | `privacySettingsRepository.getSettings()` called once per capture; result passed to `processNotification()` via `settings` parameter | ✅ FIXED (U-PR5 adapter) |
| NEW-P1-010 | P2 | processAndSave marks processed OUTSIDE pipeline transaction | Bug | `dao.markProcessed(rawId)` moved inside 15 `database.withTransaction` blocks; post-repo + worker best-effort calls removed | ✅ FIXED (P1-PR5) |
| NEW-P1-011 | P3 | Redundant SHA-256 implementations | Cleanup | 3 inline `MessageDigest` sites consolidated to shared `Hashing.sha256()`/`sha256Fingerprint()` | ✅ FIXED (P1-PR6) |
| NEW-P1-012 | P3 | Unused `postTime` parameter in `computeDedupeKey` | Cleanup | Parameter removed | ✅ FIXED (P1-PR6) |
| NEW-P1-013 | P2 | Filter receives combinedBody as bigText — over-inclusive | Bug | Filter now receives actual raw EXTRA_BIG_TEXT, not concatenation | ✅ FIXED (P1-PR3) |
| NEW-P1-014 | P3 | Deduper `cleanupExpired` never called | Bug | `deduper.cleanupExpired(DEDUP_WINDOW_MS)` wired to `onListenerConnected` | ✅ FIXED (P1-PR6) |
| NEW-P1-015 | P1 | IllegalStateException in transaction creates orphaned diagnostic | Bug | Replaced with DeferredSourceLinkDiagnostic; diagnostics emitted post-commit | ✅ FIXED (P1-PR2) |
| NEW-P1-016 | P3 | Sensitive key filtering uses exact match (misses camelCase) | Bug | 19 camelCase variants added to `SENSITIVE_EXTRAS_KEYS` companion; `buildExtrasJson` now `@VisibleForTesting internal` | ✅ FIXED (P1-PR6) |
| NEW-P1-017 | P2 | Settings observer dies permanently on exception | Bug | while(true) retry loop with 5s backoff on transient failure | ✅ FIXED (P1-PR4) |

## Pipeline 2 — Transaction Lifecycle

Full source: `PIPELINE_2_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P2-P1-01 | P1 | `updateBusinessTaxFields()` misses restore guard | Bug | Uses `DatabaseWriteBarrier` | ✅ FIXED |
| P2-P1-02 | P1 | Failed creates invisible in `transaction_events` | Enhancement | Writes CREATE_ATTEMPTED/VALIDATION_FAILED/INSERT_CONFLICT | ✅ FIXED |
| P2-P1-03 | P1 | `STRICT_EXTERNAL_ID` returns weak `InsertConflict` | Bug | Resolves to DuplicateSkipped with existing ID | ✅ FIXED |
| P2-P1-04 | P1 | Debug/restore methods bypass lifecycle | Bug | Guarded by `BuildConfig.DEBUG` | ✅ FIXED |
| P2-P1-05 | P1 | Public DAO mutation surface enables lifecycle bypass | Enhancement | `@RestrictedExpenseDaoMutation` + CI architecture test | ✅ FIXED |
| NEW-P2-001 | P1 | TOCTOU race in `updateExpense` — beforeSnapshot outside transaction | Bug | Snapshot read outside DB transaction | ✅ FIXED (U-PR2) |
| NEW-P2-002 | P1 | Same TOCTOU in 6 other update methods | Bug | Same pattern in updateLocation/Merchant/Type/etc. | ✅ FIXED (U-PR2) |
| NEW-P2-003 | P1 | `deleteExpense(Expense)` uses stale caller entity for snapshot | Bug | Caller-provided entity may be stale | ✅ FIXED (U-PR2) |
| NEW-P2-004 | P2 | Non-atomic duplicate check in `updateExpense` | Bug | Duplicate check moved inside transaction via U-PR2 | ✅ FIXED (U-PR2) |
| NEW-P2-005 | P2 | `DefaultExpenseCategoryAssignmentService` bypasses lifecycle | Bug | Write barrier already present — verified; guards DAO writes | ✅ FIXED (verified) |
| NEW-P2-006 | P2 | `NotificationRepository.deleteAll()` bypasses audit trail | Bug | BULK_DELETED TransactionEvent written before mutations (P2-PR2) | ✅ FIXED (P2-PR2) |
| NEW-P2-007 | P2 | Currency conversion failure leaves stale `baseAmount` | Bug | Stale baseAmount/baseCurrency cleared on conversion failure (P2-PR1) | ✅ FIXED (P2-PR1) |
| NEW-P2-008 | P2 | DAO exposes `updateMerchantForMerchant` that nulls dedupeKey | Bug | `dedupeKey = NULL` removed from SQL; KDoc directs to coordinator (P2-PR2) | ✅ FIXED (P2-PR2) |
| NEW-P2-009 | P2 | Planner hardcodes `EXPENSE_CREATED` trigger for update paths | Bug | Side-effect planner fires wrong trigger type | ✅ FIXED (U-PR8) |
| NEW-P2-010 | P2 | Inconsistent event-write guard between `bulkUpdateCategory` overloads | Bug | Guard unified — only writes if affectedCount > 0 (P2-PR1) | ✅ FIXED (P2-PR1) |
| NEW-P2-011 | P3 | `updateLocation` missing correlationId | Bug | correlationId added to TransactionEvent (P2-PR3) | ✅ FIXED (P2-PR3) |
| NEW-P2-012 | P3 | `updateMerchant` missing correlationId in event | Bug | correlationId added to TransactionEvent (P2-PR3) | ✅ FIXED (P2-PR3) |
| NEW-P2-013 | P3 | `updateType` missing correlationId in event | Bug | correlationId added to TransactionEvent (P2-PR3) | ✅ FIXED (P2-PR3) |
| NEW-P2-014 | P3 | `ExpenseWriteStore.updateMerchant` doesn't update merchantKey/dedupeKey | Bug | Already FIXED (P2-PR1); test confirms regeneration | ✅ FIXED (P2-PR3 verified) |
| NEW-P2-015 | P3 | Bulk idempotency keys non-unique across time | Bug | Timestamp suffix added to 7 bulk keys (P2-PR3) | ✅ FIXED (P2-PR3) |
| NEW-P2-016 | P3 | `Flow.first()` could hang indefinitely for currency settings | Bug | `withTimeoutOrNull(5_000L)` wrapper + fallback (P2-PR3) | ✅ FIXED (P2-PR3) |

## Pipeline 3 — Receipt Capture / OCR / Email

Full source: `PIPELINE_3_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P3-P0-01 | P0 | Scanned receipts saved with `createdAt = 0` | Bug | All paths set `createdAt` at lifecycle boundary | ✅ FIXED |
| P3-P1-01 | P1 | Receipt save/update/event not atomic | Bug | `processReceiptInput()` uses atomic DB transaction | ✅ FIXED |
| P3-P1-02 | P1 | `ReceiptLinkService` lacks restore guard | Bug | Write barrier guards on link/unlink | ✅ FIXED |
| P3-P1-03 | P1 | Matching result computed but not persisted | Bug | NoMatch writes MATCH_NOT_FOUND event | ✅ FIXED |
| P3-P1-04 | P1 | Receipt-created expense + link not atomic in convenience paths | Bug | Coordinator is single owner; legacy paths exist with ERROR deprecation | ⚠ PARTIAL |
| P3-P1-05 | P1 | Direct repository methods bypass lifecycle | Bug | Write barrier guards exist but some direct DAO paths remain for backfill/debug | ⚠ PARTIAL |
| P3-P1-06 | P1 | `ScannedReceiptDao.insert()` IGNORE conflict not checked | Bug | ReceiptInsertResolver handles conflicts | ✅ FIXED |
| P3-P1-07 | P1 | Currency fallback hardcoded EUR in OCR parse | Bug | ProcessReceiptUseCase injects UserCurrencyProvider (P3-PR1) | ✅ FIXED |
| P3-P1-08 | P1 | Parse failures classified as `OCR_COMPLETED` not `PARSE_FAILED` | Bug | PARSE_FAILED correctly set in ReceiptRepository | ✅ FIXED |
| P3-P1-09 | P1 | Batch receipt import no longer creates pending reviews | Bug | `autoCreateReview = false` in batch path | 📝 TODO ONLY |
| P3-P1-10 | P1 | Bank statement lifecycle dedupe weaker than legacy | Bug | Checks only pending reviews; misses stronger legacy dedupe | 📝 TODO ONLY |
| NEW-P3-001 | P1 | CancellationException swallowed in `ReceiptSideEffectDispatcher` | Bug | `catch(e: Exception)` does not rethrow CE | ✅ FIXED (U-PR1) |
| NEW-P3-002 | P1 | CancellationException swallowed in `BankStatementLifecycleProcessor` per-item | Bug | Per-item catch swallows CE | ✅ FIXED (U-PR1) |
| NEW-P3-003 | P1 | CancellationException swallowed in `ReceiptLinkService.unlinkReceiptFromExpense` | Bug | Catch-all swallows CE | ✅ FIXED (U-PR1) |
| NEW-P3-004 | P2 | Double `attachReceipt` call in `BankStatementLifecycleProcessor` | Bug | Duplicate attachReceipt removed (P3-PR3) | ✅ FIXED (P3-PR3) |
| NEW-P3-005 | P2 | Race in post-OCR duplicate path | Bug | Non-atomic duplicate check after OCR | 🔴 OPEN |
| NEW-P3-006 | P2 | Privacy leak — merchant/category logged in production | Bug | PII in production log statements | 🔴 OPEN |

## Pipeline 4 — Recurring / Bill Reminders

Full source: `PIPELINE_4_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P4-P0-01 | P0 | Actual payment does not fulfill planned expense | Bug | `linkExpenseToOccurrence` works | ✅ FIXED |
| P4-P0-02 | P0 | Paid occurrence does not suppress reminders | Bug | `suppressOpenDeliveries` works | ✅ FIXED |
| P4-P1-01 | P1 | Reminder dispatch not exactly-once safe | Bug | Atomic `claimDelivery()` sets CLAIMED state before notification | ✅ FIXED |
| P4-P1-02 | P1 | Recurring rule CRUD bypasses lifecycle/events | Bug | `RecurringRuleLifecycleCoordinator` owns all CRUD | ✅ FIXED |
| P4-P1-03 | P1 | Bill reminder worker disabled by default (static config) | Bug | Worker enabled in `WorkerSpec.DEFAULTS` | ✅ FIXED |
| P4-P1-04 | P1 | Reminder deliveries only created when caller passes `reminderWindows` | Bug | `DEFAULT_REMINDER_WINDOWS` applied | ✅ FIXED |
| P4-P1-05 | P1 | `occurrenceKey` can collide across source types | Bug | Needs design/migration | ⏭ DEFERRED |
| P4-P1-06 | P1 | Expense→occurrence linking not globally guaranteed | Bug | Side-effect planner dispatches recurring link | ✅ FIXED |
| P4-P1-07 | P1 | Existing PAID occurrences downgraded by regeneration | Bug | Materializer checks `terminalDbValues` | ✅ FIXED |
| P4-P1-08 | P1 | Materializer updates status without lifecycle event | Bug | Materializer writes `OCCURRENCE_STATUS_CHANGED` | ✅ FIXED |
| P4-P1-09 | P1 | Shared recurring write methods miss restore guard | Bug | Write barrier present | ✅ FIXED |
| P4-P1-10 | P1 | Legacy `BillReminderManager.markBillPaid()` creates mixed behavior | Bug | Legacy deprecated, coordinator owns | ✅ FIXED |
| NEW-P4-001 | P1 | CancellationException swallowed in bulk reconcile | Bug | `catch(e: Exception)` does not rethrow CE | ✅ FIXED (U-PR1) |
| NEW-P4-003 | P2 | Race in `linkExpenseToOccurrence` — lookup outside transaction | Bug | Occurrence lookup outside DB transaction | 🔴 OPEN |
| NEW-P4-004 | P2 | `BillReminderWorker` uses `System.currentTimeMillis` | Bug | Not testable; should use injected clock | ✅ FIXED (U-PR7) |
| NEW-P4-005 | P2 | Notification ID collision risk | Bug | `hashCode()` used for notification ID | 🔴 OPEN |
| NEW-P4-007 | P2 | CancellationException swallowed in `regenerateReminderDeliveries` | Bug | Catch-all swallows CE | ✅ FIXED (U-PR1) |

## Pipeline 5 — Currency / Dashboard / Analytics

Full source: `PIPELINE_5_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P5-P1-01 | P1 | Historical totals use latest-rate aggregate conversion | Bug | Per-tx TRANSACTION_DATE via `MoneyNormalizationEngine` | ✅ FIXED |
| P5-P1-02 | P1 | `ExchangeRateDao.getRate()` ambiguous with historical rows | Bug | `getLatestRateForPair()` uses `validDate DESC` | ✅ FIXED |
| P5-P1-03 | P1 | Dashboard adapter drops `MoneyAggregate` and partial warnings | Bug | Dashboard carries `isPartial`/`warning`/`CurrencyQualityUi` | ✅ FIXED |
| P5-P1-04 | P1 | Weekly/daily totals drilldown functionally broken | Bug | Historical aggregation APIs working | ✅ FIXED |
| P5-P1-05 | P1 | Dashboard widgets raw-sum `effectiveAmount` | Bug | `DashboardNormalizedInput` used | ✅ FIXED |
| P5-P1-06 | P1 | Stale-rate state not propagated to analytics quality | Bug | `staleRateCount` populated | ✅ FIXED |
| P5-P1-07 | P1 | `MultiCurrencyRepository` inconsistent `MoneyAggregateBuilder` use | Bug | MCR uses `MoneyNormalizationEngine` | ✅ FIXED |
| P5-P1-08 | P1 | Budget-vs-actual comparisons not fully normalized | Bug | Budget limit PERIOD_END vs spend latest — tracked as P6-CURRENT-001 | ⚠ PARTIAL |
| P5-NEW-01 | P1 | Weekly/daily drilldown included non-spending types | Bug | Routed to PURCHASE-only historical APIs | ✅ FIXED |
| P5-NEW-06 | P1 | Budget dashboard dropped partial/conversion warning | Bug | `BudgetStatusSnapshot` gained `isPartial`/`conversionWarning` | ✅ FIXED |
| P5-NEW-07 | P2 | Analytics stale detection used `lastUpdated` not `validDate` | Bug | Uses `convertOutcome(TRANSACTION_DATE)` | ✅ FIXED |
| P5-NEW-09 | P2 | Monthly/yearly `PeriodTotal` dropped partial warnings | Bug | Propagates `isPartial`/`warningMessage` | ✅ FIXED |
| NEW-P5-001 | P0 | `previousMonthAggregate` always null — dead feature | Bug | Dashboard loads previous month for month-over-month comparison (P5-PR1) | ✅ FIXED (P5-PR1) |
| NEW-P5-002 | P1 | Division by zero risk in `projectedTotal` | Bug | Guard added for daysElapsed=0 (P5-PR1) | ✅ FIXED (P5-PR1) |
| NEW-P5-003 | P1 | Deposit filter includes not-mine items | Bug | Filter too broad for deposit aggregation | 🔴 OPEN |
| NEW-P5-004 | P1 | `getAverageForPeriodType(DAY)` wrong denominator | Bug | Uses wrong period count | 🔴 OPEN |
| NEW-P5-005 | P1 | `SynthesisEngine` sums planned expenses across currencies | Bug | Raw-sums without normalization | ✅ FIXED (U-PR3) |
| NEW-P5-011 | P1 | `FinancialRunway` always shows 0 days | Bug | totalRemaining computed from budget/income (P5-PR1) | ✅ FIXED (P5-PR1) |

## Pipeline 6 — Budget / Forecasting / Cashflow

Full source: `PIPELINE_6_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P6-P1-01 | P1 | Budget forecast refresh fails on unique index conflict | Bug | ABORT + `insertWithDeactivation` | ✅ FIXED |
| P6-P1-02 | P1 | Forecast rows persisted with `createdAt=0` and wrong currency | Bug | `createdAt=now`, `currency=homeCurrency` | ✅ FIXED |
| P6-P1-03 | P1 | Budget/forecast/planned writes lack restore guard | Bug | Write barrier across all budget/forecast/planned writes | ✅ FIXED |
| P6-P1-04 | P1 | Budget alerts use gross `percentUsed` when adjusted spend exists | Bug | `BudgetMonitor` reads `adjustedSpendBreakdown` | ✅ FIXED |
| P6-P1-05 | P1 | Rollover ignores partial conversion state from prior periods | Bug | Rollover ORs `isPartial`, merges warnings | ✅ FIXED |
| P6-P1-06 | P1 | Budget limit conversion uses current rate, not period-specific | Bug | Budget limit uses latest rate | 📝 TODO ONLY |
| P6-P1-07 | P1 | Forecast data quality exists but `SynthesisEngine` ignores it | Bug | `confidencePenalty` applied | ✅ FIXED |
| P6-P1-08 | P1 | Planned expenses not normalized before forecast arithmetic | Bug | Groups by currency and sums raw amounts | 📝 TODO ONLY |
| P6-P1-09 | P1 | Cancelled/skipped planned expenses still enter forecast | Bug | PLANNED-only filter | ✅ FIXED |
| P6-P1-10 | P1 | Recurring occurrence status lost before forecast | Bug | Occurrences filtered to PLANNED, normalized | ✅ FIXED |
| P6-P1-11 | P1 | Cash-flow calendar raw-sums multi-currency amounts | Bug | Sums `effectiveAmount` across currencies | 📝 TODO ONLY |
| P6-P1-12 | P1 | Cash-flow output displays pre-dedup recurring predictions | Bug | Deduplicated predicted | ✅ FIXED |
| P6-P1-13 | P1 | Stress forecast is not a real account-balance forecast | Bug | Computes net-cashflow, not account balance | 📝 TODO ONLY |
| P6-P1-14 | P1 | Stress forecast counts PAID occurrences as active outflows | Bug | ACTIVE_OCCURRENCE_STATUSES includes PAID | 📝 TODO ONLY |
| P6-P1-15 | P1 | Deleting budget can fail after forecasts exist | Bug | CASCADE + explicit delete | ✅ FIXED |
| NEW-P6-001 | P1 | `computeStressForecast` swallows CancellationException | Bug | Catch-all does not rethrow CE | ✅ FIXED (U-PR1) |
| NEW-P6-002 | P1 | `BudgetMonitor` writeAlertDiagnostic swallows CE | Bug | Diagnostic write catch swallows CE | ✅ FIXED (pre-existing) |
| NEW-P6-003 | P1 | `BudgetMonitor` CHECK_FAILED diagnostic swallows CE | Bug | Same pattern in check-failed path | ✅ FIXED (pre-existing) |
| NEW-P6-004 | P1 | Unbounded rollover loop — O(N) queries for daily budgets | Bug | Batch/limit added to period iteration (P6-PR1) | ✅ FIXED (P6-PR1) |
| NEW-P6-005 | P2 | `BudgetRepository` CRUD swallows CancellationException | Bug | Repository-level catch swallows CE | ✅ FIXED (U-PR1) |
| NEW-P6-007 | P2 | Stress `expandDetectedPatterns` closed interval double-counts | Bug | Half-open interval fix applied (P6-PR2) | ✅ FIXED (P6-PR2) |
| NEW-P6-009 | P2 | DST-unsafe day arithmetic in stress horizon | Bug | Uses naive day addition | ✅ FIXED (U-PR7) |

## Pipeline 7 — Backup / Restore

Full source: `PIPELINE_7_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P7-P0-01 | P0 | Legacy `.db` import lacks journal and maintenance mode | Bug | `importDatabase()` has no `RestoreJournal`/`RestoreMaintenanceMode`; crash corrupts live DB | 📝 TODO ONLY |
| P7-P0-02 | P0 | Startup crash recovery resumes writes after failed recovery | Bug | Persistent `CRITICAL_RECOVERY_REQUIRED`; startup auto-reset exempts it; writes stay blocked. Test: `AppStartupCoordinatorRecoveryTest` | ✅ FIXED |
| P7-P1-01 | P1 | Restore uses stale injected Room instance after DB file swap | Bug | Uses same injected Room for verification after file swap | 📝 TODO ONLY |
| P7-P1-02 | P1 | Maintenance mode not a global DB write barrier | Bug | `isWritesAllowed()` enforcement is caller-by-caller | 📝 TODO ONLY |
| P7-P1-03 | P1 | Backup creation does not freeze writes or use SQLite backup API | Bug | `createCostBackup()` does not enter backup mode; concurrent writes cause inconsistent snapshot | 📝 TODO ONLY |
| P7-P1-04 | P1 | Receipt asset restore not atomic with DB restore | Bug | Crash mid-asset-restore can rollback valid DB or leave orphan files | 📝 TODO ONLY |
| P7-P1-05 | P1 | Restore success does not prove dashboard/analytics equivalence | Bug | Verification checks table counts only, not semantic output equivalence | 📝 TODO ONLY |
| P7-P1-06 | P1 | Privacy audit events optional in backup verification | Bug | `privacy_audit_events` classified Tier 3 optional; can be dropped silently | 📝 TODO ONLY |
| P7-P1-07 | P1 | Worker pause/resume not fully spec-driven | Bug | Both `pauseAllWorkers()` and `scheduleAllWorkers()` use DEFAULTS | ✅ FIXED |
| P7-P1-08 | P1 | Successful restore leaves app blocked; UI can dismiss warning | Bug | `dismissRestartRequired()` only clears UI; writes still blocked | 📝 TODO ONLY |
| NEW-P7-001 | P0 | Encrypted export never exits maintenance mode on success | Bug | Maintenance mode entered but never exited on success path | ✅ FIXED (U-PR4) |
| NEW-P7-002 | P1 | Privacy gate denial / WAL failure leak maintenance mode | Bug | Error paths don't exit maintenance mode | ✅ FIXED (U-PR4) |
| NEW-P7-003 | P2 | `enterCriticalRecoveryRequired` non-atomic two-commit | Bug | Two separate commits; crash between them leaves inconsistent state | 🔴 OPEN |
| NEW-P7-004 | P2 | RestoreJournal `appendEvent` read-modify-write race | Bug | Concurrent appends can lose events | 🔴 OPEN |
| NEW-P7-005 | P2 | `CostbackupBundle.extract()` leaks FileInputStream | Bug | Stream not closed on exception path | 🔴 OPEN |
| NEW-P7-006 | P3 | `countRowsFromSourceTable` uses unquoted table name | Bug | SQL injection risk with special table names | 🔴 OPEN |

## Pipeline 8 — Privacy / AI / Redaction

Full source: `PIPELINE_8_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P8-P1-01 | P1 | Privacy setting changes don't stop active workers | Bug | `updateSettings()` now cancels `ai_daily_briefing`, `location_backfill`, `data_retention` workers | ✅ FIXED |
| P8-P1-02 | P1 | `PrivacySettings` and `AiSettings` can disagree | Bug | Split cloud privacy; providers check non-uniformly | 📝 TODO ONLY |
| P8-P1-03 | P1 | Audit logging noisy and not semantically precise | Bug | Gates log `Allowed` for unrelated capabilities; final decision unclear | 📝 TODO ONLY |
| P8-P1-04 | P1 | Audit context stores caller-provided sensitive data | Bug | Arbitrary `context: Map<String, String>` serialized to JSON | 📝 TODO ONLY |
| P8-P1-05 | P1 | Raw notification/OCR/email data stored first, purged later | Bug | `RawStorageMode` enum controls write-time sanitization; gaps remain in some paths | ⚠ PARTIAL |
| P8-P1-06 | P1 | Retention worker scope incomplete | Bug | Only purges raw notification + OCR; misses AI artifacts, chats, email bodies | 📝 TODO ONLY |
| P8-P1-07 | P1 | Bank-statement cloud text path sends raw prompt | Bug | `suggestFromText(prompt)` no `CloudPayloadRedactor` applied | 📝 TODO ONLY |
| P8-P1-08 | P1 | Redaction not a formal purpose-aware payload contract | Bug | No standardized `PreparedCloudPayload` | 📝 TODO ONLY |
| P8-P1-09 | P1 | Notification privacy gate too late; runtime state not cached | Bug | Text extracted before gate; setting changes do not stop service | 📝 TODO ONLY |
| P8-P1-10 | P1 | Geocoding/location gate coverage not statically guaranteed | Bug | Multiple external geocoding providers; not all gate-checked | 📝 TODO ONLY |
| P8-P1-11 | P1 | Raw backup/export remains reachable | Bug | `exportDatabase()` deprecated but exists in production | 📝 TODO ONLY |
| P8-P1-12 | P1 | Denied privacy states not consistently visible | Bug | Providers return null/failure; no unified privacy-denied UX model | 📝 TODO ONLY |
| NEW-P8-001 | P1 | `updateSettings()` TOCTOU race | Bug | Concurrent settings writes can corrupt state | 🔴 OPEN |
| NEW-P8-002 | P1 | DataRetentionWorker loop no checkpoint for 5 targets | Bug | Crash restarts all purges from beginning | 🔴 OPEN |
| NEW-P8-003 | P2 | `MERCHANT_LINE_REGEX` over-matches | Bug | Regex matches non-merchant lines | 🔴 OPEN |
| NEW-P8-004 | P2 | CloudPiiSanitizer missing patterns | Bug | Some PII patterns not covered | 🔴 OPEN |
| NEW-P8-005 | P2 | `requireAllowed()` ignores capability | Bug | Gate check doesn't consider specific capability | 🔴 OPEN |
| NEW-P8-006 | P2 | DataRetentionWorker swallows purge failures | Bug | Failed purges not reported; worker returns SUCCESS | 🔴 OPEN |
| NEW-P8-007 | P2 | `sanitizeRawOcr` conflates null with empty | Bug | Null (no data) treated same as empty string (data cleared) | 🔴 OPEN |
| NEW-P8-008 | P3 | `detectRedactedFields` misses truncation | Bug | Truncated fields not detected as redacted | 🔴 OPEN |

## Pipeline 9 — Workers / Background Jobs

Full source: `PIPELINE_9_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P9-P1-01 | P1 | `BackgroundJobRun` table unused by workers | Enhancement | Created `WorkerRunLogger` interface + impl with `BackgroundJobRunDao` | ✅ FIXED |
| P9-P1-02 | P1 | No shared `WorkerExecutionGuard` | Enhancement | `WorkerExecutionGuard` class exists; used by all 7 workers with shared restore/spec/privacy checks | ✅ FIXED |
| P9-P1-03 | P1 | Restore/backup cancellation not a running-worker barrier | Bug | `WorkerLeaseRegistry` + `WorkerDrainController` + checkpoint enforcement | ✅ FIXED |
| P9-P1-04 | P1 | Daily briefing one-shot chain breaks on early exits | Bug | Reschedule on all skips except spec-disable | ✅ FIXED |
| P9-P1-05 | P1 | Bill reminder worker disabled by static `WorkerSpec` | Bug | `WorkerSpec.DEFAULTS["bill_reminder_periodic"]` now `enabled = true` | ✅ FIXED |
| P9-P1-06 | P1 | Bill reminders not exactly-once safe | Bug | Atomic `claimDelivery()` sets CLAIMED state before dispatch | ✅ FIXED |
| P9-P1-07 | P1 | `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling | Bug | Per-receipt `claimForAutoMatch` is the overlap guard | ✅ FIXED |
| P9-P1-08 | P1 | Receipt matching outcomes not durable | Bug | Durable `ReceiptEvent`s via `ReceiptMatchLifecycleService` | ✅ FIXED |
| P9-P1-09 | P1 | Warranty notification sent-state outside DB | Bug | `WarrantyReminderDelivery` Room entity+DAO; migration 142→143 | ✅ FIXED |
| P9-P1-10 | P1 | Worker pause/resume registry hardcoded and asymmetric | Bug | Explicit `WorkerSpec.oneShotPolicy`; symmetry tested | ✅ FIXED |
| P9-P1-11 | P1 | Privacy changes don't actively cancel workers | Bug | `PrivacyRuntimeWorkerPolicy` drives cancellation | ✅ FIXED |
| P9-NEW-03 | P2 | `BackgroundJobRun` rows recorded zero counts | Bug | All workers migrated to `runGuardedWithContext` with counts | ✅ FIXED |
| NEW-P9-001 | P1 | TimeoutCancellationException misclassified as system cancellation | Bug | Timeout now classified as retryable via P9-PR1 | ✅ FIXED (P9-PR1) |
| NEW-P9-002 | P1 | BillReminderWorker bypasses guard for settings/quiet-hours | Bug | Settings/quiet-hours check moved inside guard (P9-PR1) | ✅ FIXED (P9-PR1) |
| NEW-P9-003 | P1 | WorkerRunContext counters not thread-safe | Bug | Counters made thread-safe via atomic operations (P9-PR1) | ✅ FIXED (P9-PR1) |
| NEW-P9-004 | P1 | WarrantyExpirationWorker uses `runGuarded` (no context) | Bug | Migrated to `runGuardedWithContext` (P9-PR1) | ✅ FIXED (P9-PR1) |
| NEW-P9-005 | P1 | WarrantyExpirationWorker uses `System.currentTimeMillis` | Bug | Not testable; should use injected clock | ✅ FIXED (U-PR7) |
| NEW-P9-006 | P2 | WorkerSpecScheduler uses deprecated REPLACE | Bug | Should use KEEP or UPDATE for periodic workers | 🔴 OPEN |
| NEW-P9-007 | P2 | SharedPreferences version write not atomic with enqueue | Bug | Crash between write and enqueue leaves stale version | 🔴 OPEN |
| NEW-P9-008 | P2 | NotificationIntakeWorker not in guard/registry | Bug | Bypasses shared execution infrastructure | 🔴 OPEN |
| NEW-P9-009 | P2 | LocationBackfillWorker `isStopped` exits as SUCCESS | Bug | Misleading result; should be RETRY or specific status | 🔴 OPEN |
| NEW-P9-010 | P2 | MerchantKeyBackfillWorker same `isStopped` issue | Bug | Same pattern as LocationBackfillWorker | 🔴 OPEN |
| NEW-P9-011 | P2 | `scheduleAtMidnight` near-zero delay edge case | Bug | Scheduling at 23:59:59 produces near-zero initial delay | 🔴 OPEN |
| NEW-P9-012 | P2 | DailyBriefing reschedule failure silently swallowed | Bug | `.onFailure` logging added; REPLACE policy prevents chain death | ✅ FIXED (U-PR6) |
| NEW-P9-013 | P2 | WorkerExecutionGuard read-only path no exception handling | Bug | Exceptions in read-only guard path unhandled | 🔴 OPEN |
| NEW-P9-014 | P3 | WorkerSpec no battery constraint for `merchant_key_backfill` | Bug | Heavy backfill runs without battery consideration | 🔴 OPEN |
| NEW-P9-015 | P3 | `WorkerRunLogger.Handle` not idempotent | Bug | Double-complete can corrupt run record | 🔴 OPEN |

## Pipeline 10 — Bank Integration

Full source: `PIPELINE_10_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P10-P0-01 | P0 | Bank API integration is demo-only stub | Enhancement | `BuildConfig.DEBUG` + `BankApiConfig.isStubMode` double guard in `requireStubMode()` | ✅ FIXED |
| P10-P0-02 | P0 | Bank connection UI ViewModel is no-op | Bug | `BankConnectionsViewModel` injects no repository; all methods commented out | 📝 TODO ONLY |
| P10-P1-01 | P1 | `completeConnection()` doesn't persist entity | Bug | Returns `BankConnection` without `dao.insert()`; `createdAt = 0` | 📝 TODO ONLY |
| P10-P1-02 | P1 | No OAuth state/PKCE/callback validation | Bug | No durable OAuth session, state, PKCE verifier | 📝 TODO ONLY |
| P10-P1-03 | P1 | Sync has no durable run ledger or checkpoint | Bug | No `BankSyncRun`/`BankTransactionImport`; no cursor/checkpoint persistence | 📝 TODO ONLY |
| P10-P1-04 | P1 | No low-confidence review route for bank transactions | Bug | All transactions auto-imported as approved expenses | 📝 TODO ONLY |
| P10-P1-05 | P1 | Bank metadata not preserved on imported expenses | Bug | `CreateExpenseRequest` has no `bankConnectionId`/`accountId`/`syncRunId` | 📝 TODO ONLY |
| P10-P1-06 | P1 | Token refresh doesn't persist new tokens | Bug | `refreshToken()` returns true; doesn't call provider or persist | 📝 TODO ONLY |
| P10-P1-07 | P1 | No restore/write barrier around bank writes | Bug | `BankApiIntegration` has barrier; raw DAO unguarded | ⚠ PARTIAL |
| P10-P1-08 | P1 | Bank statement import dedupe weaker than expense dedupe | Bug | Statement dedupe improved but not shared with expense dedupe | ⚠ PARTIAL |
| P10-P1-09 | P1 | Bank import creates expenses one-by-one without sync tx semantics | Bug | No outer sync transaction, no import row state, no post-run reconciliation | 📝 TODO ONLY |
| NEW-P10-001 | P2 | `BankApiConfig.isStubMode` mutable global | Bug | Made immutable via P10-PR1 | ✅ FIXED (P10-PR1) |
| NEW-P10-002 | P1 | BankTokenCipher swallows `KeyPermanentlyInvalidatedException` | Bug | User now prompted to re-authenticate (P10-PR1) | ✅ FIXED (P10-PR1) |
| NEW-P10-003 | P2 | BankStatementLifecycleProcessor per-item swallows CancellationException | Bug | Per-item catch swallows CE; worker can't be cancelled mid-batch | ✅ FIXED (U-PR1) |
| NEW-P10-004 | P3 | `generateMockTransactions` non-reproducible | Bug | Made deterministic via seed (P10-PR1) | ✅ FIXED (P10-PR1) |

## Pipeline 11 — Email Receipt Ingestion

Full source: `PIPELINE_11_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P11-P1-01 | P1 | Duplicate fingerprint content-only but too coarse | Bug | Fingerprint uses merchant+amount+date bucket; too coarse for similar receipts | ⚠ PARTIAL |
| P11-P1-02 | P1 | Existing expense duplicate treated as failure | Bug | `DuplicateSkipped` handled; other failure types still ignored | ⚠ PARTIAL |
| P11-P1-03 | P1 | Service path only partially uses receipt lifecycle | Bug | Coordinator owns mutations | ✅ FIXED |
| P11-P1-04 | P1 | Raw email body/subject/sender persisted without privacy policy | Bug | Sanitizer used but wrong mode for email fields | ⚠ PARTIAL |
| P11-P1-05 | P1 | Restore barrier incomplete at email service boundary | Bug | Service checks barrier; coordinator uses `RestoreMaintenanceMode` directly | ⚠ PARTIAL |
| P11-P1-06 | P1 | Email source insert conflicts ignored | Bug | Checks `insertOrIgnore` but messageId-only conflict unresolved | ⚠ PARTIAL |
| P11-P1-07 | P1 | Receipt post-save side effects skipped in service path | Bug | Side effects dispatched correctly; double-dispatch verified NOT present (U-PR8) | ✅ FIXED |
| P11-P1-08 | P1 | No pending-review route for uncertain email receipts | Bug | Valid parse immediately creates approved expense regardless of confidence | 📝 TODO ONLY |
| NEW-P11-001 | P1 | `ingestionMutex` blocks all concurrent processing during batch | Bug | Single mutex serializes entire batch; throughput bottleneck | 🔴 OPEN |
| NEW-P11-002 | P2 | `AmazonReceiptParser.canParse()` overly broad | Bug | Parser narrowed to Amazon-specific patterns (P11-PR3) | ✅ FIXED (P11-PR3) |
| NEW-P11-003 | P2 | `UberReceiptParser.canParse()` overly broad | Bug | Parser narrowed to Uber-specific patterns (P11-PR3) | ✅ FIXED (P11-PR3) |
| NEW-P11-004 | P3 | `parseLocalizedDate()` 176 formatter instances per date | Perf | Formatters cached/optimized (P11-PR3) | ✅ FIXED (P11-PR3) |
| NEW-P11-005 | P2 | Amazon parser regex double-escaped in raw strings | Bug | Regex escaping fixed (P11-PR3) | ✅ FIXED (P11-PR3) |

## Pipeline 12 — Import / Export / Accounting

Full source: `PIPELINE_12_CONSOLIDATED_ISSUES.md` (validated 2026-05-31)

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P12-P0-01 | P0 | No app-level CSV/JSON import roundtrip pipeline | Enhancement | No real import pipeline verified end-to-end | 📝 TODO ONLY |
| P12-P1-01 | P1 | Xero/FreshBooks CSV exporters don't do real CSV escaping | Bug | RFC-4180 compliant `CsvCellSanitizer` | ✅ FIXED |
| P12-P1-02 | P1 | Accounting validation is per-page, not global | Bug | Validation loads all data but not snapshot-tied | ⚠ PARTIAL |
| P12-P1-03 | P1 | Multi-currency export fields incomplete | Bug | Fields added but no `conversionStatus` | ⚠ PARTIAL |
| P12-P1-04 | P1 | Export snapshot consistency is not real | Bug | No true snapshot; concurrent writes cause issues | 📝 TODO ONLY |
| P12-P1-05 | P1 | Normal exports plaintext and not privacy-gated | Bug | Plaintext default; encryption not wired | 📝 TODO ONLY |
| P12-P1-06 | P1 | Export silently drops many app fields | Bug | Fields still dropped | 📝 TODO ONLY |
| P12-P1-07 | P1 | Receipt links not represented in exports | Bug | Receipt links not exported | 📝 TODO ONLY |
| P12-P1-08 | P1 | Business/tax fields not exported | Bug | DTO has fields; writers omit some | ⚠ PARTIAL |
| P12-P1-09 | P1 | Accountant PDF has raw mixed-currency combined total | Bug | PDF groups by currency | ✅ FIXED |
| P12-P1-10 | P1 | Export can run during restore/restart-required state | Bug | ViewModel checks; repository doesn't | ⚠ PARTIAL |
| NEW-P12-001 | P0 | JSON export produces invalid JSON (missing comma on null) | Bug | JSON formatting fixed (P12-PR1) | ✅ FIXED (P12-PR1) |
| NEW-P12-002 | P1 | `sourceLinksJson` double-escaped | Bug | Double-escaping removed (P12-PR1) | ✅ FIXED (P12-PR1) |
| NEW-P12-003 | P1 | CsvCellSanitizer corrupts negative amounts in accounting | Bug | Negative amounts preserved in accounting mode (P12-PR1) | ✅ FIXED (P12-PR1) |
| NEW-P12-004 | P2 | `createExportFile` path traversal risk | Bug | Filename sanitization added (P12-PR2) | ✅ FIXED (P12-PR2) |
| NEW-P12-005 | P2 | Accounting validation loads ALL expenses (OOM) | Bug | Unbounded query; large datasets crash | 🔴 OPEN |
| NEW-P12-006 | P3 | `loadExpenseCount` generic error during restore | Bug | Unhelpful error message when in restore mode | 🔴 OPEN |
| NEW-P12-007 | P2 | `sanitizeIif` corrupts merchant names starting with `-` | Bug | IIF sanitizer preserves leading dash in merchant names (P12-PR1) | ✅ FIXED (P12-PR1) |

---

# Universal Contracts Status

Universal contracts extracted from the architectural strategy — each represents a cross-cutting concern applied across all relevant pipelines.

| # | Contract | Applies to Pipelines | Implementation Status |
|---|----------|---------------------|----------------------|
| U1 | Restore/write barrier (`DatabaseWriteBarrier` + `DatabaseReadBarrier`) | 1–12 | ✅ FIXED — Both barriers exist; `writeBarrier` used across all coordinators/services; `readBarrier` introduced (limited adoption) |
| U2 | Worker guard + run logging (`WorkerExecutionGuard` + `WorkerRunLogger`) | 4, 7, 8, 9 | ✅ FIXED — Guard used by all 7 workers; `WorkerRunLogger` writes to `BackgroundJobRun` |
| U3 | Privacy/redaction/raw storage (`RawStorageMode` + `RawContentSanitizer`) | 1, 3, 7, 8, 11, 12 | ✅ FIXED — `RawStorageMode` enum with 4 levels; sanitizer used for OCR, email, notifications |
| U4 | Money/currency quality (canonical period, unified money, conversion audit) | 5, 6, 12, groups/investment/tax | ✅ FIXED — Zone-aware time, canonical period, unified money primitives, export conversion audit fields |
| U5 | Transaction lifecycle (`TransactionLifecycleCoordinator` + events) | 1, 2, 3, 10, 11, 12 | ✅ FIXED — Full lifecycle with CREATE_ATTEMPTED/VALIDATION_FAILED/INSERT_CONFLICT events, STRICT_EXTERNAL_ID, DuplicateSkipped |
| U6 | Receipt lifecycle/link ownership (`ReceiptLifecycleCoordinator` + `ReceiptLinkService`) | 3, 9, 11, 12 | ✅ FIXED — Coordinator owns all receipt operations; link service with restore guards; email lifecycle collapsed into coordinator |
| U7 | Recurring planned/actual reconciliation (`RecurringLifecycleCoordinator`) | 4, 6 | ✅ FIXED — Atomic link→PAID+fulfill+suppress, atomic claim, planned-occurrence query, reconciliation reports |
| U8 | Diagnostics/drop reasons/events (`PipelineDiagnosticEvent`) | 1, 3, 4, 7, 9, 11 | ✅ FIXED — Shared diagnostic event table; notification, email, reminder, receipt pipelines write events |
| U9 | Import/export schema/roundtrip (`ExportTransaction` + `ImportCoordinator`) | 7, 10, 11, 12 | ✅ FIXED — Full `ExportTransaction` schema; `JsonExpenseImporter` + `CsvExpenseImporter` + `ImportCoordinator`; roundtrip test exists |
| U10 | DAO insert conflict/timestamps (`OnConflictStrategy`, `createdAt` propagation) | 2, 3, 4, 6, 10, 11 | ⚠ PARTIAL — Forecast uses REPLACE; transaction uses STRICT_EXTERNAL_ID/REPLACE; `createdAt` propagated in forecast engine; P3-P0-01 (receipt createdAt=0) and P10-P1-01 (bank createdAt=0) remain |

---

# Summary

| Pipeline | P0 | P1 | Total | ✅ Fixed | ⚠ Partial | Remaining |
|----------|-----|-----|-------|----------|-----------|-----------|
| 1 — Notification | 0 | 6 | 6+17 | 23 | 0 | 0 — 🟢 COMPLETE |
| 2 — Transaction Lifecycle | 0 | 5 | 5+16 | 21 | 0 | 0 — 🟢 COMPLETE |
| 3 — Receipt Capture | 1 | 10 | 11+8 | 8 | 2 | 9 (2 TODO + 7 NEW) |
| 4 — Recurring/Bill Reminders | 2 | 10 | 12+10 | 10 | 0 | 12 (1 DEF + 11 NEW) |
| 5 — Currency/Dashboard | 0 | 12 | 12+14 | 14 | 1 | 11 NEW |
| 6 — Budget/Forecasting | 0 | 15 | 15+16 | 11 | 0 | 20 (5 TODO + 15 NEW) |
| 7 — Backup/Restore | 2 | 8 | 10+6 | 2 | 0 | 14 (8 TODO + 6 NEW) |
| 8 — Privacy/AI | 0 | 12 | 12+8 | 1 | 1 | 19 (10 TODO + 8 NEW) |
| 9 — Workers | 0 | 12 | 12+15 | 16 | 0 | 11 NEW |
| 10 — Bank Integration | 2 | 9 | 11+4 | 4 | 2 | 9 (7 TODO + 2 NEW) |
| 11 — Email Receipt | 0 | 8 | 8+5 | 6 | 5 | 2 (1 TODO + 1 NEW) |
| 12 — Import/Export | 1 | 10 | 11+7 | 7 | 4 | 7 (4 TODO + 3 NEW) |
| **UNIVERSAL CONTRACTS** | **0** | **0** | **10** | **9** | **1** | **0** |
| **TOTAL (original)** | **8** | **112** | **130** | **63** | **16** | — |
| **+ NEW issues (P1–6)** | — | — | **+81** | **+16 (P1) + partial P2-P6** | — | **≈+50 remaining** |
| **+ NEW issues (P7–12)** | — | — | **+45** | **+16 (P9-P12 post-tracker)** | — | **≈+29 remaining** |

| Status | Count |
|--------|-------|
| ✅ FIXED (original tracker issues) | 63 |
| ✅ FIXED (NEW issues — all pipelines) | ≈47 |
| ⚠ PARTIAL | 15 |
| 📝 TODO ONLY | 39 |
| ⏭ DEFERRED | 1 |
| ⏳ BLOCKED | 1 |
| 🔴 NEW OPEN (remaining new issues) | ≈79 |
| **Total open work** | **≈135 ⬇** |

> **NOTE:** Detailed NEW-issue rows for P2-P6 deep audits (32 missing rows) not yet backfilled into the per-pipeline tables. See individual `PIPELINE_N_CONSOLIDATED_ISSUES.md` for complete listings.

## Key Changes Since Last Update (2026-05-31 P7–12 validation)

**Pipelines 7–12 status corrections (validated against HEAD):**
- P9: All 12 old issues confirmed ✅ FIXED (was previously showing only 5 fixed)
- P10-P1-07: Was 📝 TODO → now ⚠ PARTIAL (BankApiIntegration has barrier; raw DAO unguarded)
- P10-P1-08: Was 📝 TODO → now ⚠ PARTIAL (statement dedupe improved, not shared)
- P11-P1-01: Was ✅ FIXED → now ⚠ PARTIAL (fingerprint content-only but too coarse)
- P11-P1-02: Was ✅ FIXED → now ⚠ PARTIAL (DuplicateSkipped handled; other failures ignored)
- P11-P1-04: Remains ⚠ PARTIAL (sanitizer used but wrong mode for email fields)
- P11-P1-05: Was ✅ FIXED → now ⚠ PARTIAL (service checks barrier; coordinator uses RestoreMaintenanceMode directly)
- P11-P1-06: Was 📝 TODO → now ⚠ PARTIAL (checks insertOrIgnore but messageId-only conflict unresolved)
- P11-P1-07: Was 📝 TODO → now ✅ FIXED (side effects dispatched) — double-dispatch verified NOT present (U-PR8 confirmed)
- P12-P0-01: Was ⚠ PARTIAL → now 📝 TODO ONLY (no real import pipeline verified)
- P12-P1-02: Was ✅ FIXED → now ⚠ PARTIAL (validation loads all, not snapshot-tied)
- P12-P1-03: Was ✅ FIXED → now ⚠ PARTIAL (fields added but no conversionStatus)
- P12-P1-05: Was ✅ FIXED → now 📝 TODO ONLY (plaintext default, encryption not wired)
- P12-P1-08: Was ✅ FIXED → now ⚠ PARTIAL (DTO has fields, writers omit some)
- P12-P1-09: Remains ✅ FIXED (PDF groups by currency)

**45 NEW issues added from deep audit across Pipelines 7–12:**
- Pipeline 7: 6 new (1 P0, 1 P1, 3 P2, 1 P3)
- Pipeline 8: 8 new (2 P1, 5 P2, 1 P3)
- Pipeline 9: 15 new (5 P1, 8 P2, 2 P3)
- Pipeline 10: 4 new (1 P1, 2 P2, 1 P3)
- Pipeline 11: 5 new (1 P1, 3 P2, 1 P3)
- Pipeline 12: 7 new (1 P0, 2 P1, 3 P2, 1 P3)

---

## Key Changes — Prior Update (2026-05-31 P1–6 validation)

**Issues newly verified as FIXED:**
- P1-P1-06: Both `NotificationProcessingPipeline` and `NotificationRepository` have `writeBarrier` checks
- P3-P0-01: All receipt creation paths now set `createdAt` at lifecycle boundary (ReceiptLifecycleCoordinator, BankStatementLifecycleProcessor)
- P4-P1-01: Atomic `claimDelivery()` sets CLAIMED state before notification dispatch
- P4-P1-03: BillReminderWorker enabled in `WorkerSpec.DEFAULTS`
- P6-P1-07: `SynthesisEngine` applies `dataQuality.confidencePenalty`
- P6-P1-09: PLANNED-only filter enforced in both `SynthesisEngine` and `ForecastInputAssembler`
- P9-P1-02: `WorkerExecutionGuard` shared across all 7 workers
- P9-P1-05: Bill reminder worker enabled (same mechanism as P4-P1-03)
- P9-P1-06: Atomic reminder claim (same mechanism as P4-P1-01)
- P9-P1-11: Privacy setting changes actively cancel affected workers
- P11-P1-01: Email fingerprint now content-only (no message ID dependency)
- P11-P1-02: `DuplicateSkipped` handled gracefully in email receipt creation
- P12-P1-08: Business/tax fields included in `ExportTransaction` DTO

**Issues newly verified as PARTIAL:**
- P1-P1-02: `PipelineDiagnosticEvent` shared table written by notification pipeline; `processBatch()` now writes diagnostic events per result
- P3-P1-04: `EmailReceiptIngestionService` inline fallback removed — coordinator is single owner for email receipt ingestion. Legacy convenience paths still exist with ERROR-level deprecation.
- P3-P1-05: Write barrier guards exist but some direct DAO paths remain for backfill/debug
- P11-P1-04: `RawContentSanitizer` applied to email sender/subject/body at write time
- P12-P0-01: `ImportCoordinator` + `JsonExpenseImporter` + `CsvExpenseImporter` exist with roundtrip test

**Newly completed PRs:**
- P1-PR2: Text extraction hardened — messages now use `getParcelableArrayList<CharSequence>` (API 33+) with deprecated `getParcelableArray` fallback
- P1-PR5: `processBatch()` now writes `PipelineDiagnosticEvent` for every outcome
- P1-PR6: Pipeline flow KDoc added to `NotificationProcessingPipeline` class
- P2-PR0: `docs/expense-mutation-inventory.md` created with all 29 classified callsites
- P2-PR1: `checkLifecycleBypass` task KDoc updated with CI-enforced boundary documentation
- P2-PR2: `createExpenseStandalone()` and `createExpenseDbOnly()` added; old `createExpense()` marked @Deprecated
- P2-PR3: `ExpenseRepository` lifecycle KDoc enhanced with MAINTENANCE/BACKFILL/DEBUG/DESIGN bypass classifications
- P2-PR4: `updateBusinessTaxFields` renamed to `updateBusinessFlags` with narrowed KDoc
- P2-PR5: Event insert failure policy documented on `writeDuplicateEvent` (best-effort vs REQUIRED)
- P3-PR4: `EmailReceiptIngestionService` inline fallback removed — coordinator is now single mutation owner
- P3-PR5: `ReceiptMatchingWorker` checkpoint verified — `executionGuard.checkpoint("receipt_matching")` present
