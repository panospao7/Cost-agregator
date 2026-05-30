# Pipeline Issues Master Tracker

> Consolidated P0/P1 issues from all 12 pipeline debug reports.
> Each issue's **full fix strategy + implementation plan** lives in its source debug report.
> Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`
> **Last updated: 2026-05-30 (documentation audit — Pipeline 3 & 4 complete through `214c4266`)**
> **Total: 8 P0 + 112 P1 = 120 pipeline issues + 10 universal contracts = 130 items (50 ✅ FIXED + 8 ⚠ PARTIAL + 71 📝 TODO ONLY + 1 ⏭ DEFERRED)**
>
> **NOTE (2026-05-30):** Pipeline 3 and Pipeline 4 commits are complete through commit `214c4266`. Statuses in this tracker should be reconciled against the actual evaluations in `pipeline3_evaluation.md` and `pipeline4_evaluation.md`. Key discrepancies found during documentation audit:
> - P3-P0-01 (`createdAt=0`): Tracker says ✅ FIXED, evaluation says still OPEN
> - P4-P0-01 (payment fulfills planned expense): Tracker says ✅ FIXED, evaluation says PARTIAL
> - P4-P0-02 (paid occurrence suppresses reminders): Tracker says ✅ FIXED, evaluation says PARTIAL/UNPROVEN
> - P4-P1-07 (PAID downgrade): Tracker says ⚠ PARTIAL, evaluation says still OPEN

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

Full source: `pipeline-1-notification-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P1-P1-01 | P1 | Processing outcomes flattened to `Success` | Bug | `processInternal()` now returns typed `NotificationPipelineOutcome` sealed interface | ✅ FIXED |
| P1-P1-02 | P1 | No durable notification diagnostic/drop-reason ledger | Enhancement | PipelineDiagnosticEvent table exists; notification pipeline writes events via `writePipelineDiagnosticEvent()` — shared generic diagnostic entity, not notification-specific | ⚠ PARTIAL |
| P1-P1-03 | P1 | Extraction misses `textLines` and `messages` | Bug | `NotificationTextParts.extract()` omits `textLines`/`messages` used by bank/SMS notifications | ✅ FIXED |
| P1-P1-05 | P1 | Privacy gate runs after text extraction/filter | Bug | Text extracted before privacy gate check; need cached `StateFlow<PrivacyDecision>` | 📝 TODO ONLY |
| P1-P1-06 | P1 | Restore guard exists in service but not in pipeline | Bug | Both `NotificationProcessingPipeline` and `NotificationRepository` now have `writeBarrier.checkWritesAllowed()` guards | ✅ FIXED |
| P1-P1-07 | P1 | Service shutdown silently loses accepted notifications | Bug | Coroutine cancelled before DB write; dedupe cache suppresses retry | 📝 TODO ONLY |

## Pipeline 2 — Transaction Lifecycle

Full source: `pipeline-2-transaction-lifecycle-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P2-P1-01 | P1 | `updateBusinessTaxFields()` misses restore guard | Bug | Added `restoreMaintenanceMode.isWritesAllowed()` guard | ✅ FIXED |
| P2-P1-02 | P1 | Failed creates invisible in `transaction_events` | Enhancement | Now writes `CREATE_ATTEMPTED`, `CREATE_VALIDATION_FAILED`, `CREATE_INSERT_CONFLICT` | ✅ FIXED |
| P2-P1-03 | P1 | `STRICT_EXTERNAL_ID` returns weak `InsertConflict` | Bug | Conflict resolves via `findIdByDedupeKey()` → `DuplicateSkipped` with existing ID | ✅ FIXED |
| P2-P1-04 | P1 | Debug/restore methods bypass lifecycle | Bug | `deleteAllExpenses()` + debug snapshots now guarded by `BuildConfig.DEBUG` | ✅ FIXED |
| P2-P1-05 | P1 | Public DAO mutation surface enables lifecycle bypass | Enhancement | Need static guard tests with approved allowlist | 📝 TODO ONLY |

## Pipeline 3 — Receipt Capture / OCR / Email

Full source: `pipeline-3-receipt-capture-ocr-email-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P3-P0-01 | P0 | Scanned receipts saved with `createdAt = 0` | Bug | `createdAt` defaults to 0L; need to set at lifecycle boundary | ✅ FIXED | ✓ All paths now set `createdAt` at lifecycle boundary (ReceiptLifecycleCoordinator, BankStatementLifecycleProcessor) |
| P3-P1-01 | P1 | Receipt save/update/event not atomic | Bug | Insert + event not in single transaction (from prior session) | ✅ FIXED | ✓ `processReceiptInput()` uses atomic DB transaction with ghost-state cleanup |
| P3-P1-02 | P1 | `ReceiptLinkService` lacks restore guard | Bug | `linkReceiptToExpense()`/`unlinkReceiptFromExpense()` now guarded (from prior session) | ✅ FIXED |
| P3-P1-03 | P1 | Matching result computed but not persisted | Bug | `findBestMatch()` result ignored; `matchStatus` stays `UNMATCHED` | 📝 TODO ONLY |
| P3-P1-04 | P1 | Receipt-created expense + link not atomic in convenience paths | Bug | Separate steps; link failure leaves unlinked expense | ⚠ PARTIAL | ⚠ EmailReceiptIngestionService inline fallback removed — coordinator is single owner. Legacy convenience paths still exist with ERROR-level deprecation. |
| P3-P1-05 | P1 | Direct repository methods bypass lifecycle | Bug | `insertReceipt()`, `deleteReceipt()`, `clearAllScannedReceipts()` bypass coordinator | ⚠ PARTIAL | ⚠ Write barrier guards exist but some direct DAO paths remain for backfill/debug.
| P3-P1-06 | P1 | `ScannedReceiptDao.insert()` IGNORE conflict not checked | Bug | Returns 0 on conflict; callers proceed with `receiptId = 0` | 📝 TODO ONLY |
| P3-P1-07 | P1 | Currency fallback hardcoded EUR in OCR parse | Bug | `ReceiptParser.parse()` defaults to `"EUR"` when no explicit currency | 📝 TODO ONLY |
| P3-P1-08 | P1 | Parse failures classified as `OCR_COMPLETED` not `PARSE_FAILED` | Bug | OCR succeeds but parsing throws; status set to wrong value | 📝 TODO ONLY |
| P3-P1-09 | P1 | Batch receipt import no longer creates pending reviews | Bug | `autoCreateReview = false` in batch path; reviews not actionable | 📝 TODO ONLY |
| P3-P1-10 | P1 | Bank statement lifecycle dedupe weaker than legacy | Bug | Checks only pending reviews; misses stronger legacy dedupe | 📝 TODO ONLY |

## Pipeline 4 — Recurring / Bill Reminders

Full source: `pipeline-4-recurring-bill-reminders-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P4-P0-01 | P0 | Actual payment does not fulfill planned expense | Bug | Added `plannedExpenseDao.getBySourceOccurrenceKey()` + `linkToActualExpense()` (from prior session) | ✅ FIXED |
| P4-P0-02 | P0 | Paid occurrence does not suppress reminders | Bug | Added `suppressOpenDeliveriesForOccurrence()` (from prior session) | ✅ FIXED |
| P4-P1-01 | P1 | Reminder dispatch not exactly-once safe | Bug | Atomic `claimDelivery()` sets CLAIMED state before notification; worker checks claim result | ✅ FIXED |
| P4-P1-02 | P1 | Recurring rule CRUD bypasses lifecycle/events | Bug | Direct DAO calls with no events, no restore guard, `createdAt = 0` | 📝 TODO ONLY |
| P4-P1-03 | P1 | Bill reminder worker disabled by default (static config) | Bug | `WorkerSpec.DEFAULTS["bill_reminder_periodic"]` now `enabled = true` (version bump) | ✅ FIXED |
| P4-P1-04 | P1 | Reminder deliveries only created when caller passes `reminderWindows` | Bug | `generateOccurrences()` defaults to empty reminder windows | 📝 TODO ONLY |
| P4-P1-05 | P1 | `occurrenceKey` can collide across source types | Bug | `buildOccurrenceKey()` omits `sourceType`; key collision possible | ⏭ DEFERRED |
| P4-P1-06 | P1 | Expense→occurrence linking not globally guaranteed | Bug | `dispatchOnCreated()` defers recurring matching; many create paths skip it | 📝 TODO ONLY |
| P4-P1-07 | P1 | Existing PAID occurrences downgraded by regeneration | Bug | `materialize()` can downgrade PAID → PLANNED | 📝 TODO ONLY |
| P4-P1-08 | P1 | Materializer updates status without lifecycle event | Bug | No `OCCURRENCE_STATUS_CHANGED` event written | 📝 TODO ONLY |
| P4-P1-09 | P1 | Shared recurring write methods miss restore guard | Bug | Added `isWritesAllowed()` guards to lifecycle methods (from prior session) | ✅ FIXED |
| P4-P1-10 | P1 | Legacy `BillReminderManager.markBillPaid()` creates mixed behavior | Bug | Legacy path does not mark PAID, fulfill planned, suppress reminders | 📝 TODO ONLY |

## Pipeline 5 — Currency / Dashboard / Analytics

Full source: `pipeline-5-currency-dashboard-analytics-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P5-P1-01 | P1 | Historical totals use latest-rate aggregate conversion | Bug | TotalsAggregationEngine year/month/week/day/category now use `MultiCurrencyRepository.get*AggregatesHistorical` / `getHomeCurrencyPurchaseTotalHistoricalResult` (per-expense TRANSACTION_DATE). Dashboard already used `DashboardNormalizedInput` (TRANSACTION_DATE). | ✅ FIXED |
| P5-P1-02 | P1 | `ExchangeRateDao.getRate()` ambiguous with historical rows | Bug | `getLatestRateForPair()` orders by `validDate DESC, lastUpdated DESC`; `getRate()` ERROR-deprecated; `getRateAsOf()` added; `storeRate` sets `validDate` | ✅ FIXED |
| P5-P1-03 | P1 | Dashboard adapter drops `MoneyAggregate` and partial warnings | Bug | Dashboard summary/category/widgets carry `isPartial`/`warningMessage`/`CurrencyQualityUi`; budget snapshot now carries `isPartial`/`conversionWarning` | ✅ FIXED |
| P5-P1-04 | P1 | Weekly/daily totals drilldown functionally broken | Bug | Drilldown now PURCHASE-only historical via `getWeeklyAggregatesHistorical`/`getDailyAggregatesHistorical`; type-agnostic helpers ERROR-deprecated | ✅ FIXED |
| P5-P1-05 | P1 | Dashboard widgets raw-sum `effectiveAmount` | Bug | Widgets consume `DashboardNormalizedInput`; no raw fallback; enforced by money guard G-MONEY-15/16 | ✅ FIXED |
| P5-P1-06 | P1 | Stale-rate state not propagated to analytics quality | Bug | `staleRateCount` populated; `DataQualityReport` applies stale penalty; normalizer keys staleness off `validDate` | ✅ FIXED |
| P5-P1-07 | P1 | `MultiCurrencyRepository` inconsistent `MoneyAggregateBuilder` use | Bug | MCR aggregates funnel through `MoneyAggregateBuilder`/`MoneyNormalizationEngine`; legacy `Result<Double>` APIs deprecated | ✅ FIXED |
| P5-P1-08 | P1 | Budget-vs-actual comparisons not fully normalized | Bug | Conversion failure → `BudgetHealthStatus.UNKNOWN` (not ON_TRACK); `isPartial`/`conversionWarning` carried through to dashboard snapshot. Residual: limit (PERIOD_END) vs spend (latest) basis split tracked as P6-CURRENT-001 | ⚠ PARTIAL |
| P5-NEW-01 | P1 | Weekly/daily drilldown included non-spending types (latest rate) | Bug | Routed to PURCHASE-only historical APIs; type-agnostic helpers ERROR-deprecated; tests added | ✅ FIXED |
| P5-NEW-06 | P1 | Budget dashboard dropped partial/conversion warning | Bug | `BudgetStatusSnapshot` gained `isPartial`/`conversionWarning`; mapped in `DashboardContractsAdapter`; test added | ✅ FIXED |
| P5-NEW-07 | P2 | Analytics stale detection used `lastUpdated` not `validDate` | Bug | `AnalyticsCurrencyNormalizer` uses `convertOutcome(TRANSACTION_DATE)` and keys staleness off `rateValidDate`; tests added | ✅ FIXED |
| P5-NEW-09 | P2 | Monthly/yearly `PeriodTotal` dropped partial warnings | Bug | `getMonthlyTotals`/`getYearlyTotals` propagate `isPartial`/`warningMessage`; tests added | ✅ FIXED |

## Pipeline 6 — Budget / Forecasting / Cashflow

Full source: `pipeline-6-budget-forecasting-cashflow-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P6-P1-01 | P1 | Budget forecast refresh fails on unique index conflict | Bug | Refresh fix preserved via UNIQUE index `(budgetId, targetPeriodStart, forecastDate)` + `insertWithDeactivation` (deactivate-then-insert in one `@Transaction`, sole production insert path). Conflict strategy is now `ABORT` (changed from `REPLACE` by P6-CURRENT-008 to stop overwriting history); a refresh uses a new `forecastDate` so no collision | ✅ FIXED |
| P6-P1-02 | P1 | Forecast rows persisted with `createdAt=0` and wrong currency | Bug | Engine passes `createdAt=now`, `currency=homeCurrency` (from prior session) | ✅ FIXED |
| P6-P1-03 | P1 | Budget/forecast/planned writes lack restore guard | Bug | Write-barrier guards present across `BudgetRepository` add/update/delete/toggle/deleteAll/restore + notification toggles, `BudgetForecastingEngine.generateForecastResult`/`updateForecastAccuracy`, and `PlannedExpenseRepository` add/delete; no unguarded budget/forecast/planned write remains | ✅ FIXED |
| P6-P1-04 | P1 | Budget alerts use gross `percentUsed` when adjusted spend exists | Bug | (via P6-CURRENT-002) `BudgetMonitor` reads `adjustedSpendBreakdown?.effectiveSpend` and recomputes `adjustedPercent` for all thresholds; gross is only a logged fallback on offset-engine failure | ✅ FIXED |
| P6-P1-05 | P1 | Rollover ignores partial conversion state from prior periods | Bug | `BudgetRepository.createBudgetStatus` rollover loop ORs `periodAggregate.isPartial` and merges warnings into `BudgetStatus.isPartial`/`conversionWarning`; each completed period converts at its own period-end | ✅ FIXED |
| P6-P1-06 | P1 | Budget limit conversion uses current rate, not period-specific | Bug | `convertBudgetAmountToHomeCurrency()` uses `convert()` latest rate | 📝 TODO ONLY |
| P6-P1-07 | P1 | Forecast data quality exists but `SynthesisEngine` ignores it | Bug | `dataQuality.confidencePenalty` now applied at `synthesize()` via `finalConfidence` computation | ✅ FIXED |
| P6-P1-08 | P1 | Planned expenses not normalized before forecast arithmetic | Bug | Groups by currency and sums raw amounts | 📝 TODO ONLY |
| P6-P1-09 | P1 | Cancelled/skipped planned expenses still enter forecast | Bug | `SynthesisEngine` filters `status == "PLANNED"`; `ForecastInputAssembler.mapPlannedExpenses` also filters PLANNED-only | ✅ FIXED |
| P6-P1-10 | P1 | Recurring occurrence status lost before forecast | Bug | `ForecastInputAssembler.assemble` filters materialized occurrences to `status == "PLANNED"`, carries status into `ConfirmedOccurrence`, normalizes amount to home currency | ✅ FIXED |
| P6-P1-11 | P1 | Cash-flow calendar raw-sums multi-currency amounts | Bug | Sums `effectiveAmount` across currencies | 📝 TODO ONLY |
| P6-P1-12 | P1 | Cash-flow output displays pre-dedup recurring predictions | Bug | `DailyCashFlow` stores original list, not deduped | 📝 TODO ONLY |
| P6-P1-13 | P1 | Stress forecast is not a real account-balance forecast | Bug | Computes 90-day net-cashflow estimate, not account balance | 📝 TODO ONLY |
| P6-P1-14 | P1 | Stress forecast counts PAID occurrences as active outflows | Bug | `ACTIVE_OCCURRENCE_STATUSES` still includes `"PAID"` | 📝 TODO ONLY |
| P6-P1-15 | P1 | Deleting budget can fail after forecasts exist | Bug | FK `budget_forecasts.budgetId → budgets(id)` relaxed `RESTRICT → CASCADE` via `MIGRATION_141_142`; `BudgetRepository.deleteBudget` keeps an explicit forecast-delete inside its write-barrier-guarded `withTransaction`; `deleteAll`/`restoreDebugSnapshot` now succeed with forecasts present | ✅ FIXED |

## Pipeline 7 — Backup / Restore

Full source: `pipeline-7-backup-restore-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P7-P0-01 | P0 | Legacy `.db` import lacks journal and maintenance mode | Bug | `importDatabase()` has no `RestoreJournal`/`RestoreMaintenanceMode`; crash corrupts live DB | 📝 TODO ONLY |
| P7-P0-02 / P7-CURRENT-003 | P0 | Startup crash recovery can resume writes after failed recovery (incl. across the *next* restart) | Bug | Fail-closed: preserve journal; failed crash-recovery now enters persistent `CRITICAL_RECOVERY_REQUIRED` (was `RESTORE_COMPLETE_RESTART_REQUIRED`); startup auto-reset exempts `CRITICAL_RECOVERY_REQUIRED` so writes stay blocked across repeated restarts. Test: `AppStartupCoordinatorRecoveryTest` | ✅ FIXED |
| P7-P1-01 | P1 | Restore uses stale injected Room instance after DB file swap | Bug | Uses same injected Room for verification after file swap | 📝 TODO ONLY |
| P7-P1-02 | P1 | Maintenance mode not a global DB write barrier | Bug | `isWritesAllowed()` enforcement is caller-by-caller | 📝 TODO ONLY |
| P7-P1-03 | P1 | Backup creation does not freeze writes or use SQLite backup API | Bug | `createCostBackup()` does not enter backup mode; concurrent writes cause inconsistent snapshot | 📝 TODO ONLY |
| P7-P1-04 | P1 | Receipt asset restore not atomic with DB restore | Bug | Crash mid-asset-restore can rollback valid DB or leave orphan files | 📝 TODO ONLY |
| P7-P1-05 | P1 | Restore success does not prove dashboard/analytics equivalence | Bug | Verification checks table counts only, not semantic output equivalence | 📝 TODO ONLY |
| P7-P1-06 | P1 | Privacy audit events optional in backup verification | Bug | `privacy_audit_events` classified Tier 3 optional; can be dropped silently | 📝 TODO ONLY |
| P7-P1-07 | P1 | Worker pause/resume not fully spec-driven | Bug | `pauseAllWorkers()` uses DEFAULTS but `scheduleAllWorkers()` hardcodes list | ✅ FIXED |
| P7-P1-08 | P1 | Successful restore leaves app blocked; UI can dismiss warning | Bug | `dismissRestartRequired()` only clears UI; writes still blocked | 📝 TODO ONLY |

## Pipeline 8 — Privacy / AI / Redaction

Full source: `pipeline-8-privacy-ai-redaction-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P8-P1-01 | P1 | Privacy setting changes don't stop active workers | Bug | `updateSettings()` now cancels `ai_daily_briefing`, `location_backfill`, `data_retention` workers | ✅ FIXED |
| P8-P1-02 | P1 | `PrivacySettings` and `AiSettings` can disagree | Bug | Split cloud privacy; providers check non-uniformly | 📝 TODO ONLY |
| P8-P1-03 | P1 | Audit logging noisy and not semantically precise | Bug | Gates log `Allowed` for unrelated capabilities; final decision unclear | 📝 TODO ONLY |
| P8-P1-04 | P1 | Audit context stores caller-provided sensitive data | Bug | Arbitrary `context: Map<String, String>` serialized to JSON | 📝 TODO ONLY |
| P8-P1-05 | P1 | Raw notification/OCR/email data stored first, purged later | Bug | `RawStorageMode` enum now controls write-time sanitization (STORE_RAW/REDACTED/METADATA_ONLY/DO_NOT_STORE); email + OCR + notification paths use `RawContentSanitizer` | ⚠ PARTIAL |
| P8-P1-06 | P1 | Retention worker scope incomplete | Bug | Only purges raw notification + OCR; misses AI artifacts, chats, email bodies | 📝 TODO ONLY |
| P8-P1-07 | P1 | Bank-statement cloud text path sends raw prompt | Bug | `suggestFromText(prompt)` no `CloudPayloadRedactor` applied | 📝 TODO ONLY |
| P8-P1-08 | P1 | Redaction not a formal purpose-aware payload contract | Bug | Redaction differs by provider/field; no standardized `PreparedCloudPayload` | 📝 TODO ONLY |
| P8-P1-09 | P1 | Notification privacy gate too late; runtime state not cached | Bug | Text extracted before gate; setting changes do not stop service | 📝 TODO ONLY |
| P8-P1-10 | P1 | Geocoding/location gate coverage not statically guaranteed | Bug | Multiple external geocoding providers; not all gate-checked | 📝 TODO ONLY |
| P8-P1-11 | P1 | Raw backup/export remains reachable | Bug | `exportDatabase()` deprecated but exists in production; `BackupPrivacyGate` permits plaintext | 📝 TODO ONLY |
| P8-P1-12 | P1 | Denied privacy states not consistently visible | Bug | Providers return null/failure; no unified privacy-denied UX model | 📝 TODO ONLY |

## Pipeline 9 — Workers / Background Jobs

Full source: `pipeline-9-workers-background-jobs-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P9-P1-01 | P1 | `BackgroundJobRun` table unused by workers | Enhancement | Created `WorkerRunLogger` interface + impl with `BackgroundJobRunDao` | ✅ FIXED |
| P9-P1-02 | P1 | No shared `WorkerExecutionGuard` | Enhancement | `WorkerExecutionGuard` class exists; used by all 7 workers with shared restore/spec/privacy checks | ✅ FIXED |
| P9-P1-03 | P1 | Restore/backup cancellation not a running-worker barrier | Bug | Real `WorkerLeaseRegistry` + `WorkerDrainController` drain wired via `MaintenanceOperationRunner`; all looping workers (incl. MerchantKeyBackfill + Warranty) now `executionGuard.checkpoint()`. Guard usage now enforced by `WorkerGuardArchitectureGuardTest` (S8) | ✅ FIXED |
| P9-P1-04 | P1 | Daily briefing one-shot chain breaks on early exits | Bug | **S3:** reschedule now driven by `WorkerGuardResult`; reschedules on Success AND all incidental Skips (fresh-artifact/no-work/privacy-denied/restore-blocked) — only an explicit spec-disable (`"Worker disabled by spec"`) stops the chain. Guard↔worker literal pinned by a guard-side test | ✅ FIXED |
| P9-P1-05 | P1 | Bill reminder worker disabled by static `WorkerSpec` | Bug | `WorkerSpec.DEFAULTS["bill_reminder_periodic"]` now `enabled = true` (version bump) | ✅ FIXED |
| P9-P1-06 | P1 | Bill reminders not exactly-once safe | Bug | Atomic `claimDelivery()` sets CLAIMED state; worker checks claim result before dispatch | ✅ FIXED |
| P9-P1-07 | P1 | `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling | Bug | **S6:** atomic per-receipt claim `ScannedReceiptDao.claimForAutoMatch` (conditional UPDATE on UNMATCHED/SUGGESTED) is the load-bearing overlap guard — concurrent periodic+manual runs can no longer double-link; `runOnce` keeps `ExistingWorkPolicy.KEEP`. (Lease is registry-only, not exclusive — verified.) | ✅ FIXED |
| P9-P1-08 | P1 | Receipt matching outcomes not durable | Bug | **S5:** durable `ReceiptEvent`s `MATCH_ATTEMPTED` / `MATCH_NOT_FOUND` / `MATCH_SKIPPED_DOCUMENT_TYPE` / `AUTO_MATCH_LINK_FAILED` via `ReceiptMatchLifecycleService` (write-barrier + transaction); worker on `runGuardedWithContext` records counts | ✅ FIXED |
| P9-P1-09 | P1 | Warranty notification sent-state outside DB | Bug | **S9:** new `WarrantyReminderDelivery` Room entity+DAO (claim-before-notify, SENT only on `DELIVERED`, unique key `warrantyId+windowDays+expiryDate`); migration **142→143**; included in whole-file backup snapshot + `BackupVerifier` TIER_1. SharedPreferences removed. ⚠️ Needs human KSP build to emit `143.json` + instrumentation migration tests | ✅ FIXED (schema; needs human migration test run) |
| P9-P1-10 | P1 | Worker pause/resume registry hardcoded and asymmetric | Bug | **S2:** explicit `WorkerSpec.oneShotPolicy`; `scheduleAtMidnight()` cancels existing work when disabled (parity with `scheduleFromSpec`); `merchant_key_backfill` resolves to REPLACE (contradiction removed). Registry↔Spec symmetry already tested | ✅ FIXED |
| P9-P1-11 | P1 | Privacy changes don't actively cancel workers | Bug | **S7:** `applyPrivacyChange()` now policy-driven via `PrivacyRuntimeWorkerPolicy` (no hardcoded names); background-location no longer cancels `merchant_key_backfill`; re-enable reschedules; `data_retention` kept exempt; uses actual persisted settings | ✅ FIXED |
| P9-NEW-03 | P2 | `BackgroundJobRun` rows recorded zero counts | Bug | **S4:** Location/MerchantKey/DataRetention/ReceiptMatching/DailyBriefing migrated to `runGuardedWithContext`; feed `rowsScanned/rowsUpdated/notificationsSent/rowsSkipped` | ✅ FIXED |
| P9-NEW-04 | P2 | `requiresNotificationPermission` declared but unused | Bug | **S1:** `WorkerExecutionGuard` now enforces it via injected `NotificationPermissionChecker`; skips durably with `NOTIFICATION_PERMISSION_DENIED`; warranty worker uses it | ✅ FIXED |
| P9-NEW-11 | P2 | One-shot scheduling policy implicit / merchant-key comment mismatch | Bug | **S2:** explicit `oneShotPolicy` field; merchant-key KDoc/spec aligned to REPLACE | ✅ FIXED |
| P9-NEW-13 | P1 | Worker retry intent dropped (thrown `RuntimeException` classified as permanent) | Bug | **Found in review.** New `RetryableWorkerException` recognized by guard (precedence: Cancellation → Retryable → `classifyTransient` → Failed); LocationBackfill + MerchantKey "no-progress/transient" throws use it; LocationBackfill no longer burns the permanent attempt budget on a transient throw | ✅ FIXED |

## Pipeline 10 — Bank Integration

Full source: `pipeline-10-bank-integration-imports-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P10-P0-01 | P0 | Bank API integration is demo-only stub | Enhancement | Added `BuildConfig.DEBUG` + `BankApiConfig.isStubMode` double guard in `requireStubMode()` | ✅ FIXED |
| P10-P0-02 | P0 | Bank connection UI ViewModel is no-op | Bug | `BankConnectionsViewModel` injects no repository; all methods commented out | 📝 TODO ONLY |
| P10-P1-01 | P1 | `completeConnection()` doesn't persist entity | Bug | Returns `BankConnection` without `dao.insert()`; `createdAt = 0` | 📝 TODO ONLY |
| P10-P1-02 | P1 | No OAuth state/PKCE/callback validation | Bug | No durable OAuth session, state, PKCE verifier | 📝 TODO ONLY |
| P10-P1-03 | P1 | Sync has no durable run ledger or checkpoint | Bug | No `BankSyncRun`/`BankTransactionImport`; no cursor/checkpoint persistence | 📝 TODO ONLY |
| P10-P1-04 | P1 | No low-confidence review route for bank transactions | Bug | All transactions auto-imported as approved expenses | 📝 TODO ONLY |
| P10-P1-05 | P1 | Bank metadata not preserved on imported expenses | Bug | `CreateExpenseRequest` has no `bankConnectionId`/`accountId`/`syncRunId` | 📝 TODO ONLY |
| P10-P1-06 | P1 | Token refresh doesn't persist new tokens | Bug | `refreshToken()` returns true; doesn't call provider or persist | 📝 TODO ONLY |
| P10-P1-07 | P1 | No restore/write barrier around bank writes | Bug | `BankApiIntegration` and `BankConnectionDao` don't check restore mode | 📝 TODO ONLY |
| P10-P1-08 | P1 | Bank statement import dedupe weaker than expense dedupe | Bug | `BankStatementLifecycleProcessor` skips approved expense check | 📝 TODO ONLY |
| P10-P1-09 | P1 | Bank import creates expenses one-by-one without sync tx semantics | Bug | No outer sync transaction, no import row state, no post-run reconciliation | 📝 TODO ONLY |

## Pipeline 11 — Email Receipt Ingestion

Full source: `pipeline-11-email-receipt-ingestion-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P11-P1-01 | P1 | Duplicate fingerprint includes message ID | Bug | `createFingerprint()` now content-only (merchant+amount+date bucket); message ID used only for message-id dedup path separately | ✅ FIXED |
| P11-P1-02 | P1 | Existing expense duplicate treated as failure | Bug | `createExpenseFromReceipt()` handles `DuplicateSkipped` gracefully returning existing expense ID | ✅ FIXED |
| P11-P1-03 | P1 | Service path only partially uses receipt lifecycle | Bug | Manual orchestration bypasses `processEmailReceipt()`; two competing contracts | 📝 TODO ONLY |
| P11-P1-04 | P1 | Raw email body/subject/sender persisted without privacy policy | Bug | `RawContentSanitizer` applied at write time via `RawStorageMode`; sender, subject, body sanitized | ⚠ PARTIAL |
| P11-P1-05 | P1 | Restore barrier incomplete at email service boundary | Bug | `writeBarrier.checkWritesAllowed()` now at entry of `processEmailReceipt()` (from prior session) | ✅ FIXED |
| P11-P1-06 | P1 | Email source insert conflicts ignored | Bug | `insertOrIgnore()` returns -1 on conflict; all callers ignore return value | 📝 TODO ONLY |
| P11-P1-07 | P1 | Receipt post-save side effects skipped in service path | Bug | `saveEmailReceipt()` doesn't call `dispatchAfterSave()` | 📝 TODO ONLY |
| P11-P1-08 | P1 | No pending-review route for uncertain email receipts | Bug | Valid parse immediately creates approved expense regardless of confidence | 📝 TODO ONLY |

## Pipeline 12 — Import / Export / Accounting

Full source: `pipeline-12-import-export-accounting-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P12-P0-01 | P0 | No app-level CSV/JSON import roundtrip pipeline | Enhancement | `ImportCoordinator` + `JsonExpenseImporter` + `CsvExpenseImporter` + `ImportResult`/`ImportFormat` exist; `ExportImportRoundtripTest` stub present | ⚠ PARTIAL |
| P12-P1-01 | P1 | Xero/FreshBooks CSV exporters don't do real CSV escaping | Bug | Replaced with RFC-4180 compliant `CsvCellWriter` (from prior session) | ✅ FIXED |
| P12-P1-02 | P1 | Accounting validation is per-page, not global | Bug | Full-dataset validation before streaming (from prior session) | ✅ FIXED |
| P12-P1-03 | P1 | Multi-currency export fields incomplete | Bug | Extended `ExportTransaction` with all audit fields (from prior session) | ✅ FIXED |
| P12-P1-04 | P1 | Export snapshot consistency is not real | Bug | No snapshot anchoring; concurrent writes cause missing/duplicate rows | 📝 TODO ONLY |
| P12-P1-05 | P1 | Normal exports plaintext and not privacy-gated | Bug | `encryptExportFile()` not called; no privacy gate checked | 📝 TODO ONLY |
| P12-P1-06 | P1 | Export silently drops many app fields | Bug | Generic export only includes 8 fields; drops business/location/base fields | 📝 TODO ONLY |
| P12-P1-07 | P1 | Receipt links not represented in exports | Bug | No `receiptId` or link metadata in export rows | 📝 TODO ONLY |
| P12-P1-08 | P1 | Business/tax fields not exported | Bug | `isBusinessExpense`/`businessPurpose`/etc. now included in `ExportTransaction` DTO | ✅ FIXED |
| P12-P1-09 | P1 | Accountant PDF has raw mixed-currency combined total | Bug | Raw-sums `effectiveAmount` across currencies; no conversion | 📝 TODO ONLY |
| P12-P1-10 | P1 | Export can run during restore/restart-required state | Bug | `ExportOptionsViewModel` now checks restore mode; `DatabaseReadBarrier` exists | ⚠ PARTIAL |

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
| 1 — Notification | 0 | 6 | 6 | 3 | 1 | 2 |
| 2 — Transaction Lifecycle | 0 | 5 | 5 | 4 | 0 | 1 |
| 3 — Receipt Capture | 1 | 10 | 11 | 3 | 2 | 6 |
| 4 — Recurring/Bill Reminders | 2 | 10 | 12 | 5 | 0 | 7 |
| 5 — Currency/Dashboard | 0 | 8 | 8 | 1 | 0 | 7 |
| 6 — Budget/Forecasting | 0 | 15 | 15 | 9 | 0 | 6 |
| 7 — Backup/Restore | 2 | 8 | 10 | 2 | 0 | 8 |
| 8 — Privacy/AI | 0 | 12 | 12 | 1 | 1 | 10 |
| 9 — Workers | 0 | 11 | 11 | 5 | 0 | 6 |
| 10 — Bank Integration | 2 | 9 | 11 | 1 | 0 | 10 |
| 11 — Email Receipt | 0 | 8 | 8 | 3 | 1 | 4 |
| 12 — Import/Export | 1 | 10 | 11 | 4 | 2 | 5 |
| **UNIVERSAL CONTRACTS** | **0** | **0** | **10** | **9** | **1** | **0** |
| **TOTAL** | **8** | **112** | **130** | **50** | **8** | **72** |

| Status | Count |
|--------|-------|
| ✅ FIXED | 50 |
| ⚠ PARTIAL | 8 |
| 📝 TODO ONLY | 71 |
| ⏭ DEFERRED | 1 |

## Key Changes Since Last Update (2026-05-11 verification)

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
