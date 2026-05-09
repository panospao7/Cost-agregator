# Pipeline Issues Master Tracker

> Consolidated P0/P1 issues from all 12 pipeline debug reports.
> Each issue's **full fix strategy + implementation plan** lives in its source debug report.
> Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`
> **Last updated: 2026-05-09 (implementation complete)**
> **Total: 8 P0 + 112 P1 = 120 issues (26 pipeline-specific + 8 universal contracts = 34 ✅ FIXED, 86 remaining)**

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
| P1-P1-02 | P1 | No durable notification diagnostic/drop-reason ledger | Enhancement | Pipeline decisions only in Timber logs; need `NotificationPipelineEvent` entity+DAO | 📝 TODO ONLY |
| P1-P1-03 | P1 | Extraction misses `textLines` and `messages` | Bug | `NotificationTextParts.extract()` omits `textLines`/`messages` used by bank/SMS notifications | 📝 TODO ONLY |
| P1-P1-05 | P1 | Privacy gate runs after text extraction/filter | Bug | Text extracted before privacy gate check; need cached `StateFlow<PrivacyDecision>` | 📝 TODO ONLY |
| P1-P1-06 | P1 | Restore guard exists in service but not in pipeline | Bug | `NotificationProcessingPipeline`/`NotificationRepository` do not check restore mode | 📝 TODO ONLY |
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
| P3-P0-01 | P0 | Scanned receipts saved with `createdAt = 0` | Bug | `createdAt` defaults to 0L; need to set at lifecycle boundary | 📝 TODO ONLY |
| P3-P1-01 | P1 | Receipt save/update/event not atomic | Bug | Insert + event not in single transaction (from prior session) | ✅ FIXED |
| P3-P1-02 | P1 | `ReceiptLinkService` lacks restore guard | Bug | `linkReceiptToExpense()`/`unlinkReceiptFromExpense()` not guarded (from prior session) | ✅ FIXED |
| P3-P1-03 | P1 | Matching result computed but not persisted | Bug | `findBestMatch()` result ignored; `matchStatus` stays `UNMATCHED` | 📝 TODO ONLY |
| P3-P1-04 | P1 | Receipt-created expense + link not atomic in convenience paths | Bug | Separate steps; link failure leaves unlinked expense | 📝 TODO ONLY |
| P3-P1-05 | P1 | Direct repository methods bypass lifecycle | Bug | `insertReceipt()`, `deleteReceipt()`, `clearAllScannedReceipts()` bypass coordinator | 📝 TODO ONLY |
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
| P4-P1-01 | P1 | Reminder dispatch not exactly-once safe | Bug | Need atomic `CLAIMED` state before notification | 📝 TODO ONLY |
| P4-P1-02 | P1 | Recurring rule CRUD bypasses lifecycle/events | Bug | Direct DAO calls with no events, no restore guard, `createdAt = 0` | 📝 TODO ONLY |
| P4-P1-03 | P1 | Bill reminder worker disabled by default (static config) | Bug | `WorkerSpec` `enabled = false`; need runtime `BillReminderSettingsRepository` | 📝 TODO ONLY |
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
| P5-P1-01 | P1 | Historical totals use latest-rate aggregate conversion | Bug | `MultiCurrencyRepository` period totals use `convert()` not `convertAsOf()` | 📝 TODO ONLY |
| P5-P1-02 | P1 | `ExchangeRateDao.getRate()` ambiguous with historical rows | Bug | Added `ORDER BY lastUpdated DESC LIMIT 1` (from prior session) | ✅ FIXED |
| P5-P1-03 | P1 | Dashboard adapter drops `MoneyAggregate` and partial warnings | Bug | `DashboardContractsAdapter` maps to DTOs losing `isPartial`/`warningMessage` | 📝 TODO ONLY |
| P5-P1-04 | P1 | Weekly/daily totals drilldown functionally broken | Bug | Deprecated raw mixed-currency path returns empty lists | 📝 TODO ONLY |
| P5-P1-05 | P1 | Dashboard widgets raw-sum `effectiveAmount` | Bug | Spending/forecast/widgets use raw amounts, not normalized | 📝 TODO ONLY |
| P5-P1-06 | P1 | Stale-rate state not propagated to analytics quality | Bug | `staleRateCount` hardcoded to 0 | 📝 TODO ONLY |
| P5-P1-07 | P1 | `MultiCurrencyRepository` inconsistent `MoneyAggregateBuilder` use | Bug | Manual mapping drops bucket transaction counts | 📝 TODO ONLY |
| P5-P1-08 | P1 | Budget-vs-actual comparisons not fully normalized | Bug | Compares normalized spending against raw budget amounts | 📝 TODO ONLY |

## Pipeline 6 — Budget / Forecasting / Cashflow

Full source: `pipeline-6-budget-forecasting-cashflow-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P6-P1-01 | P1 | Budget forecast refresh fails on unique index conflict | Bug | Added `@Insert(onConflict = OnConflictStrategy.REPLACE)` (from prior session) | ✅ FIXED |
| P6-P1-02 | P1 | Forecast rows persisted with `createdAt=0` and wrong currency | Bug | Engine passes `createdAt=now`, `currency=homeCurrency` (from prior session) | ✅ FIXED |
| P6-P1-03 | P1 | Budget/forecast/planned writes lack restore guard | Bug | No `RestoreMaintenanceMode` check in budget CRUD/paths | 📝 TODO ONLY |
| P6-P1-04 | P1 | Budget alerts use gross `percentUsed` when adjusted spend exists | Bug | `BudgetMonitor` uses gross even when `effectiveSpend` is available | 📝 TODO ONLY |
| P6-P1-05 | P1 | Rollover ignores partial conversion state from prior periods | Bug | Uses `.displayAmount` only; ignores `isPartial`/`warningMessage` | 📝 TODO ONLY |
| P6-P1-06 | P1 | Budget limit conversion uses current rate, not period-specific | Bug | `convertBudgetAmountToHomeCurrency()` uses `convert()` latest rate | 📝 TODO ONLY |
| P6-P1-07 | P1 | Forecast data quality exists but `SynthesisEngine` ignores it | Bug | `dataQuality` not passed to `synthesize()` | 📝 TODO ONLY |
| P6-P1-08 | P1 | Planned expenses not normalized before forecast arithmetic | Bug | Groups by currency and sums raw amounts | 📝 TODO ONLY |
| P6-P1-09 | P1 | Cancelled/skipped planned expenses still enter forecast | Bug | Filters only `!= "FULFILLED"`, not `== "PLANNED"` | 📝 TODO ONLY |
| P6-P1-10 | P1 | Recurring occurrence status lost before forecast | Bug | Queries all occurrences without status filter | 📝 TODO ONLY |
| P6-P1-11 | P1 | Cash-flow calendar raw-sums multi-currency amounts | Bug | Sums `effectiveAmount` across currencies | 📝 TODO ONLY |
| P6-P1-12 | P1 | Cash-flow output displays pre-dedup recurring predictions | Bug | `DailyCashFlow` stores original list, not deduped | 📝 TODO ONLY |
| P6-P1-13 | P1 | Stress forecast is not a real account-balance forecast | Bug | Computes 90-day net-cashflow estimate, not account balance | 📝 TODO ONLY |
| P6-P1-14 | P1 | Stress forecast counts PAID occurrences as active outflows | Bug | `ACTIVE_OCCURRENCE_STATUSES` includes `"PAID"` | 📝 TODO ONLY |
| P6-P1-15 | P1 | Deleting budget can fail after forecasts exist | Bug | FK `RESTRICT` blocks delete; no archive/delete-forecasts step | 📝 TODO ONLY |

## Pipeline 7 — Backup / Restore

Full source: `pipeline-7-backup-restore-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P7-P0-01 | P0 | Legacy `.db` import lacks journal and maintenance mode | Bug | `importDatabase()` has no `RestoreJournal`/`RestoreMaintenanceMode`; crash corrupts live DB | 📝 TODO ONLY |
| P7-P0-02 | P0 | Startup crash recovery can resume writes after failed recovery | Bug | Fail-closed: preserve journal, enter `RESTORE_COMPLETE_RESTART_REQUIRED` (from prior session) | ✅ FIXED |
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
| P8-P1-05 | P1 | Raw notification/OCR/email data stored first, purged later | Bug | Raw text always written; retention worker purges later | 📝 TODO ONLY |
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
| P9-P1-02 | P1 | No shared `WorkerExecutionGuard` | Enhancement | Each worker checks different subset of restore/spec/privacy/settings | 📝 TODO ONLY |
| P9-P1-03 | P1 | Restore/backup cancellation not a running-worker barrier | Bug | `cancelUniqueWork()` is async; workers may continue during restore | 📝 TODO ONLY |
| P9-P1-04 | P1 | Daily briefing one-shot chain breaks on early exits | Bug | Returns early without `scheduleDailyBriefing()` for restore/privacy/artifact-skip | 📝 TODO ONLY |
| P9-P1-05 | P1 | Bill reminder worker disabled by static `WorkerSpec` | Bug | `enabled = false` blocks scheduling regardless of user setting | 📝 TODO ONLY |
| P9-P1-06 | P1 | Bill reminders not exactly-once safe | Bug | No atomic claim; crash after notify before markSent duplicates | 📝 TODO ONLY |
| P9-P1-07 | P1 | `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling | Bug | Uses `enqueue()` not `enqueueUniqueWork()` | 📝 TODO ONLY |
| P9-P1-08 | P1 | Receipt matching outcomes not durable | Bug | No `BackgroundJobRun` or per-receipt events | 📝 TODO ONLY |
| P9-P1-09 | P1 | Warranty notification sent-state outside DB | Bug | Uses `SharedPreferences`; not in backup/restore model | 📝 TODO ONLY |
| P9-P1-10 | P1 | Worker pause/resume registry hardcoded and asymmetric | Bug | `pauseAllWorkers()` from DEFAULTS; `scheduleAllWorkers()` hardcoded | 📝 TODO ONLY |
| P9-P1-11 | P1 | Privacy changes don't actively cancel workers | Bug | No central policy applier cancels on setting change | 📝 TODO ONLY |

## Pipeline 10 — Bank Integration

Full source: `pipeline-10-bank-integration-imports-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P10-P0-01 | P0 | Bank API integration is demo-only stub | Enhancement | Added `BuildConfig.DEBUG` + `BankApiConfig.isStubMode` double guard; `@VisibleForTesting` | ✅ FIXED |
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
| P11-P1-01 | P1 | Duplicate fingerprint includes message ID | Bug | Forwarded receipts with different message ID bypass dedupe | 📝 TODO ONLY |
| P11-P1-02 | P1 | Existing expense duplicate treated as failure | Bug | `createExpenseFromReceipt()` throws for non-Created outcomes; no link to existing | 📝 TODO ONLY |
| P11-P1-03 | P1 | Service path only partially uses receipt lifecycle | Bug | Manual orchestration bypasses `processEmailReceipt()`; two competing contracts | 📝 TODO ONLY |
| P11-P1-04 | P1 | Raw email body/subject/sender persisted without privacy policy | Bug | `rawOcrText = emailBody.take(5000)`; no privacy gate | 📝 TODO ONLY |
| P11-P1-05 | P1 | Restore barrier incomplete at email service boundary | Bug | Direct DAO writes before/around lifecycle calls | ✅ FIXED |
| P11-P1-06 | P1 | Email source insert conflicts ignored | Bug | `insertOrIgnore()` returns -1 on conflict; all callers ignore return value | 📝 TODO ONLY |
| P11-P1-07 | P1 | Receipt post-save side effects skipped in service path | Bug | `saveEmailReceipt()` doesn't call `dispatchAfterSave()` | 📝 TODO ONLY |
| P11-P1-08 | P1 | No pending-review route for uncertain email receipts | Bug | Valid parse immediately creates approved expense regardless of confidence | 📝 TODO ONLY |

## Pipeline 12 — Import / Export / Accounting

Full source: `pipeline-12-import-export-accounting-debug-report.md`

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| P12-P0-01 | P0 | No app-level CSV/JSON import roundtrip pipeline | Enhancement | No importer for exported formats; roundtrip testing impossible | 📝 TODO ONLY |
| P12-P1-01 | P1 | Xero/FreshBooks CSV exporters don't do real CSV escaping | Bug | Replaced with RFC-4180 compliant `CsvCellWriter` (from prior session) | ✅ FIXED |
| P12-P1-02 | P1 | Accounting validation is per-page, not global | Bug | Full-dataset validation before streaming (from prior session) | ✅ FIXED |
| P12-P1-03 | P1 | Multi-currency export fields incomplete | Bug | Extended `ExportTransaction` with all audit fields (from prior session) | ✅ FIXED |
| P12-P1-04 | P1 | Export snapshot consistency is not real | Bug | No snapshot anchoring; concurrent writes cause missing/duplicate rows | 📝 TODO ONLY |
| P12-P1-05 | P1 | Normal exports plaintext and not privacy-gated | Bug | `encryptExportFile()` not called; no privacy gate checked | 📝 TODO ONLY |
| P12-P1-06 | P1 | Export silently drops many app fields | Bug | Generic export only includes 8 fields; drops business/location/base fields | 📝 TODO ONLY |
| P12-P1-07 | P1 | Receipt links not represented in exports | Bug | No `receiptId` or link metadata in export rows | 📝 TODO ONLY |
| P12-P1-08 | P1 | Business/tax fields not exported | Bug | `isBusinessExpense`/`businessPurpose`/etc. omitted from CSV/JSON exports | 📝 TODO ONLY |
| P12-P1-09 | P1 | Accountant PDF has raw mixed-currency combined total | Bug | Raw-sums `effectiveAmount` across currencies; no conversion | 📝 TODO ONLY |
| P12-P1-10 | P1 | Export can run during restore/restart-required state | Bug | `ExportOptionsViewModel`/`ExportDataRepository` don't check restore mode | 📝 TODO ONLY |

---

# Summary

| Pipeline | P0 | P1 | Total | ✅ Fixed | Remaining |
|----------|-----|-----|-------|----------|-----------|
| 1 — Notification | 0 | 6 | 6 | 1 | 5 |
| 2 — Transaction Lifecycle | 0 | 5 | 5 | 4 | 1 |
| 3 — Receipt Capture | 1 | 10 | 11 | 2 | 9 |
| 4 — Recurring/Bill Reminders | 2 | 10 | 12 | 3 | 9 |
| 5 — Currency/Dashboard | 0 | 8 | 8 | 1 | 7 |
| 6 — Budget/Forecasting | 0 | 15 | 15 | 2 | 13 |
| 7 — Backup/Restore | 2 | 8 | 10 | 2 | 8 |
| 8 — Privacy/AI | 0 | 12 | 12 | 1 | 11 |
| 9 — Workers | 0 | 11 | 11 | 1 | 10 |
| 10 — Bank Integration | 2 | 9 | 11 | 1 | 10 |
| 11 — Email Receipt | 0 | 8 | 8 | 1 | 7 |
| 12 — Import/Export | 1 | 10 | 11 | 3 | 8 |
| **UNIVERSAL CONTRACTS** | **0** | **8** | **8** | **8** | **0** |
| **TOTAL** | **8** | **120** | **128** | **34** | **94** |

| Status | Count |
|--------|-------|
| ✅ FIXED | 34 |
| 📝 TODO ONLY | 93 |
| ⏭ DEFERRED | 1 |
