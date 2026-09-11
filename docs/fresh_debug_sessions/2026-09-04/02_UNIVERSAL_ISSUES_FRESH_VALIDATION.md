# UNIVERSAL ISSUES — Fresh Validation (2026-09-04)

- Validated at: d1fa9c68 (branch `atomicity-pr21-enforcement-final`). Static validation only — no compile/test run.
- Source docs: `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md` + `universal issues implementation plan/` (U-PR1..U-PR8) + MIT-031/034/041/043/075 entries in `MASTER_ISSUE_TRACKER.md` + universal notes in `PIPELINE_ISSUES_MASTER_TRACKER.md`.
- Method: every "FIXED" claim re-verified against current source. Code is the source of truth; tracker disagreement = DOC-DRIFT.

---

## 1. Program verdict summary

| Issue | Old status (tracker) | Fresh status (code @ d1fa9c68) | Residual work |
|-------|---------------------|-------------------------------|---------------|
| U-PR1 cancellation | ✅ IMPLEMENTED (146 guards/38 files; ~120 ViewModel catches deferred) | **MOSTLY LANDED, tail confirmed** — samples all rethrow; deferred tail = 98 allowlist entries (38 UI); 7 fresh CE-swallow residuals in production paths | Fix 7 residual sites; burn down allowlist |
| U-PR2 TOCTOU | ✅ IMPLEMENTED | **LANDED (different mechanism than planned)** — read-inside-transaction in all 10 update/delete methods; NO `atomicReadModifyWrite` helper exists (doc drift); 2 real residuals in ReceiptLinkService | Fix ReceiptLinkService stale-snapshot rewrites |
| U-PR3 money | ⚠ PARTIAL (01/02 ✅, 03 deferred) | **CONFIRMED PARTIAL** — 01/02 verified in place; 03 still open with 3+ fallback-site families | U-MONEY-03 + new silent-raw-fallback sites |
| U-PR4 barriers | ✅ IMPLEMENTED | **LANDED** — try/finally exit verified; U-BARRIER-02 claim "no static guard" is now stale (static guard exists); U-BARRIER-03 correct | Barrier-call-site coverage audit (allowlist exists) |
| U-PR5 privacy | NOT LANDED (tracker silent/deferred) | **SUBSTANTIALLY LANDED (DOC-DRIFT)** — per-source modes + bank mode exist; redaction targets exist; `requireAllowed()` has exactly 1 production caller | Wire requireAllowed into remaining cloud paths; expenses.notes decision |
| U-PR6 worker guard | ✅ RESOLVED (74c2e5b8) | **VERIFIED FIXED** — all 4 items confirmed in code; NEW-P9-001 timeout-retry confirmed (7076d730) | None material |
| U-PR7 time provider | ✅ IMPLEMENTED | **LANDED + extended (T1–T4C merged); sweep incomplete at HEAD** — claimed sites clean; ~40 residual `System.currentTimeMillis()` sites + SynthesisEngine Calendar use remain (gr-00-local unmerged) | Merge/close gr-00-local sweep |
| U-PR8 side effects | ✅ LANDED (01 not-a-bug, 02 fixed) | **CONFIRMED** — planner triggerType parameterized; no double dispatch. NEW leak found: `e.message` → persisted side-effect failure reason (6 sites) | Sanitize outcome reason (new category U-PR9 candidate) |
| U-EXPORT-01 | Open (P12-local, no PR) | **FIXED in code** (leading-comma JSON writer + P12-PR1 markers) | Tracker row stale |
| U-EXPORT-02 | Open (P12-local, no PR) | **FIXED in code** (negative-number regex carve-out in CsvCellSanitizer) | Tracker row stale |
| U-DEAD-01 | Open (P5-local, no PR) | **FIXED in code** (runway from budget/income; previousMonthAggregate computed) | Tracker row stale |

---

## 2. Per-issue fresh validation

### U-PR1 — CancellationException safety

**Verified still fixed (samples):**
- `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt:558,617,765` — CE rethrow guards present.
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` (catch block) — `if (e is kotlinx.coroutines.CancellationException) throw e`.
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt:142,158,279,688,838,909,930` — CE rethrow guards present.
- `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:516-517,607-608,637-638,677-678` — all 4 broad catches call `CancellationSafe.rethrowIfCancellation(e)` (note: zero literal `CancellationException` strings — helper pattern only; naive grep would falsely report regressions).
- Guard scale has GROWN well past the claim: 257 CE-guard occurrences (`catch (… CancellationException`, `is CancellationException) throw`, `rethrowIfCancellation`, `runCatchingCancellable`) across 82 files in `app/src/main/java` (fresh grep). The "146 guards / 38 files" figure is a 2026-05-31 snapshot, superseded by PR1–PR24 work.

**Deferred tail — confirmed real:**
- `app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt:74-212` — `KNOWN_VIOLATIONS` contains **98 entries** (13 AI, 3 BACKUP_DATA, 21 DOMAIN, 14 INFRASTRUCTURE, 6 REPOSITORY, 3 SERVICE, **38 UI**), all tagged MIT-034. MIT says "97 entries" and "~65 UI ViewModels" — both figures drifted (actual 98 / 38).
- Fresh production CE-swallow residuals (plain `runCatching` / `catch (_: Exception)` with no rethrow):
  1. `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:321` — `runCatching { transactionEventDao.insert(CREATE_ATTEMPTED …) }`, no `.onFailure` CE guard.
  2. Same file `:343` — `runCatching { … CREATE_VALIDATION_FAILED … }`, no guard (contrast `:372-395` which HAS the guard — inconsistent within one function).
  3. Same file `:515` — `runCatching { … STRICT_EXTERNAL_ID validation event … }`, no guard.
  4. Same file `:652-681` — `runCatching { … CREATE_DUPLICATE_SKIPPED … }.getOrDefault(false)` — CE converted to `false`.
  5. Same file `:691` — `runCatching { … CREATE_INSERT_CONFLICT … }`, no guard.
  6. `data/repository/NotificationProcessingPipeline.kt:819-823` — `sanitizePendingReviewText`: `catch (_: Exception) { RawStorageMode.STORE_REDACTED }` swallows CE (fail-closed for privacy, but cancellation-blind).
  7. `domain/analytics/AdvancedAnalyticsEngine.kt:985-987` — `resolveHomeCurrency(): runCatching { … }.getOrDefault(defaultDisplayCurrency())` swallows CE.
  8. `startup/AppStartupCoordinator.kt:350-355` — `recoverStaleWorkerRuns`: `lifecycleScope.launch { runCatching { … }.onFailure { Timber.w } }` swallows CE in a cancellable UI-scope coroutine.
- Note: the coordinator's `homeCurrency()` catches at `TransactionLifecycleCoordinator.kt:469-474` and `:867-872` DO rethrow CE — the pipeline claim about "resolveHomeCurrency" applies to the AdvancedAnalyticsEngine/BudgetRepository variants, not those.

**Honest tail size:** 98 allowlisted files (38 UI) + 8 confirmed production residual sites (7 files). Severity of residuals: MINOR each (best-effort diagnostic/event writes), but the five coordinator sites are on the hot create path and contradict the file's own guarded pattern at :372.

### U-PR2 — TOCTOU race in update methods

**Verified fixed:**
- The planned `atomicReadModifyWrite` helper **does not exist** (`grep atomicReadModifyWrite` → 0 hits). Instead, every update/delete method reads inside `database.withTransaction` (the plan's alternative "P2-08" pattern). Evidence in `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`:
  - `updateExpense` :884-888 ("TOCTOU-safe: read + write atomic")
  - `updateCategory` :1042-1044, `updateLocation` :1106-1109, `updateMerchant` :1324-1326 ("Collision check inside transaction for TOCTOU safety" :1333), `updateType` :1404-1406 (:1413), `updateTransferDetails` :1484-1486 (:1565)
  - `deleteExpense(Long)` :1557-1558; bulk reconcile :1958-1961 ("Fetch affected rows inside transaction to prevent TOCTOU race"); business update :2043-2045; delete(Expense) re-read :2112-2114 ("P2-08: Re-read inside transaction for TOCTOU-safe snapshot")
  - Duplicate-check inside txn: :545, :553, :559 (NEW-P2-004 markers)
- This is **DOC-DRIFT**: U-PR2 plan/tracker claim "atomicReadModifyWrite helper applied to 8 methods"; the guarantee exists but via a different, unnamed mechanism.

**Fresh residuals (stale-snapshot full-row rewrites):**
1. `domain/receipt/lifecycle/ReceiptLinkService.kt:168 → 252-261` — `linkReceiptToExpense`: `receipt` read at :168 OUTSIDE `transactionRunner.runInTransaction` (:191); at :252 `scannedReceiptDao.update(receipt.copy(...))` writes the FULL stale entity (all columns, e.g. `matchStatus`, `suggestedExpenseId`) — a concurrent OCR/matching change between :168 and :252 is silently lost. Severity: MAJOR.
2. Same file `:382 → 411-421` — `unlinkReceiptFromExpense`: receipt read at :382 outside transaction (:390); full-row `scannedReceiptDao.update(receipt.copy(...))` at :411 and :419-421. Same lost-update window. Severity: MAJOR.
3. `domain/bank/BankApiIntegration.kt:428-434` — `refreshToken` calls `BankConnectionDao.updateToken`; verified the DAO (`data/database/dao/BankConnectionDao.kt:46-53`) is a **targeted-column UPDATE** (`SET accessToken… WHERE id`), not a full-row rewrite — narrow last-writer-wins on token columns only, `@StubForDemo` path. Severity: MINOR.
4. `data/store/ExpenseWriteStore.kt:34-36` — `update(expense)` is a barrier-checked full-row write facade with no snapshot-staleness protection; correctness depends on each caller (all current coordinator callers read inside txn — see above). Assessed as guard-rail gap, not an active bug. Severity: MINOR.

### U-PR3 — Money/currency

**Verified fixed:**
- U-MONEY-01 in `domain/logic/SynthesisEngine.kt`: every sum converts before summing — occurrences :213-214, patterns :222-223, planned :232-233, budget-pattern tiers :252-263, likely :272-283, daily history :331-345, block-party :459-478, :536-554 (`convertAmount(…)` / `currencyConverter.convert`).
- U-MONEY-01 in `domain/forecasting/FinancialStressForecastEngine.kt`:375-382 (convert occurrence, EXCLUDE on failure with warning), :434-436 (convert detected pattern, exclude if null), deposits/expenses normalized before sums :464, :535.
- U-MONEY-02 in `FinancialStressForecastEngine.kt:170-172` — `isPartial` / `qualityWarnings` / `excludedCount` populated into `StressForecastResult`.

**Still open (U-MONEY-03 and adjacent):**
1. `domain/usecase/receipt/ProcessReceiptUseCase.kt:26` — `userCurrencyProvider.getHomeCurrency() ?: "EUR"` silent EUR fallback. Class has **zero production callers** (grep: only its own file) — dead-path fix target ("fix-in-dead-path" — see §3).
2. `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:1204,1206` (`calculatePlannedVsActualReport`, fun at :1185) — `totalPlanned += occ.expectedAmount`, `totalActual += paid` currency-blind Double sums. Scoped per rule (`sourceId == ruleId` filter :1196) so risk materializes only when a rule's occurrences/linked expenses carry mixed currencies; no currency check exists. Severity: MINOR-to-MAJOR depending on rule-currency stability.
3. NEW (not in tracker): SynthesisEngine conversion-failure fallbacks silently mix raw foreign amounts into home-currency sums: `:333` (`?: expense.amount`), `:345`, `:460` (`?: monthly`), `:477-478` (`?: raw`), `:554` (`?: raw`). Failure of a single conversion corrupts the whole sum without warning (no exclusion/quality flag, unlike FinancialStressForecastEngine which excludes). Severity: MAJOR for multi-currency users.

### U-PR4 — Barrier / maintenance mode

**Verified fixed:**
- U-BARRIER-01: `data/repository/DatabaseBackupRepositoryImpl.kt:360-484` — `exportDatabase()` wraps the entire post-`enterAndDrain` body in `try { … } finally { runCatching { restoreMaintenanceMode.exit(forceRestartRequired = false) } }` (:481-483). All early returns are covered: db-file-missing :389-391, encrypted privacy-gate denial :404-408, plaintext gate denial :415-419, WAL checkpoint failure :423-427, and both success paths :460/:475. Second export path (encrypted bundle, :543 enter) has matching finally :701-702. (Task's cited lines ~518-525/477-483 have drifted a few lines; the finally is at :481-483.)
- U-BARRIER-03: `domain/workers/WorkerExecutionGuard.kt:70` — `Skipped → ListenableWorker.Result.success()`; restore-blocked maps through `applyBlockedPolicy` (:654-659, SKIP_SUCCESS → Skipped). No FAILED misclassification. Confirms the plan's "no code change needed (already correct)" — with the additional nuance the plan itself noted (pre-start skips leave no durable run row; `startRunSafely` now records a blocked diagnostic :571-573).

**U-BARRIER-02 assessment ("280 sites, no static guard" claim):**
- Count verified: **284 `checkWritesAllowed` call sites in 60 files** (`grep -rn checkWritesAllowed app/src/main/java | wc -l` = 284).
- "No static guard" is now STALE: `scripts/verify_db_access_boundaries.py` + `config/db_access_allowlist.yml` (with `requires_write_barrier` / `barrier_required` fields per writer class) + `scripts/ci/run_static_guard_suite.py` form a DAO-writer allowlist guard. It is allowlist-based (per writer class/DAO), NOT a per-call-site proof that every writer path invokes the barrier — so enforcement is partial: new DAOs are caught, but a new unguarded method inside an allowlisted class is not. Severity: assessment only.

### U-PR5 — RawStorageMode / privacy contract

**Contrary to the tracker (which lists U-PR5 as the one unlanded universal PR), two of three sub-issues are substantially LANDED — DOC-DRIFT:**

1. U-PRIVACY-01 (per-source modes) — **LANDED**:
   - `domain/privacy/PrivacySettings.kt:15-18` — four distinct modes: `rawNotificationStorageMode`, `rawOcrStorageMode`, `emailReceiptStorageMode`, **`rawBankStatementStorageMode`** (the field the plan demanded); strict `FAIL_CLOSED_DEFAULTS` :38-41 sets all to `DO_NOT_STORE`.
   - `domain/privacy/RawPersistencePolicyResolver.kt:47-70` — full per-source matrix: NOTIFICATION→notification mode, RECEIPT_OCR→ocr, EMAIL_RECEIPT→email, BANK_STATEMENT and BANK_API→`rawBankStatementStorageMode`, AI_ARTIFACT/EXPORT_DEBUG→debug-gated. Persisted in `data/privacy/PrivacySettingsRepositoryImpl.kt:137,230`.
   - `domain/bank/BankApiIntegration.kt:491` — `settings.rawBankStatementStorageMode  // PR5-FIX: use dedicated bank statement mode`.
2. U-PRIVACY-02 (EffectiveCloudAiPolicy authoritative gate) — **PARTIAL (1 production caller)**:
   - `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:294-296` — `// U-PR5: Authoritative cloud AI gate`; `effectiveCloudAiPolicyResolver.resolve()` + `ocrPolicy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)` — the ONLY production `requireAllowed` call (grep-verified; the other `requireAllowed` hits are the definition and the unrelated `RecurringOccurrenceTransitionPolicy`).
   - `data/ai/provider/CloudDashboardBriefingService.kt:134-135` checks `effectivePolicy.cloudAllowed` manually (no `requireAllowed`, no capability check).
   - Bank-statement cloud path gates via PrivacyGate capability checks, not the composite policy (`data/ai/provider/CloudReceiptAssistService.kt:277-295`).
   - `processEmailReceipt` (`ReceiptLifecycleCoordinator.kt:738+`) has no cloud gate (no cloud OCR on that path — acceptable, but unverified exhaustively).
   - `requireAllowed` itself is fail-closed for unknown capabilities (`domain/privacy/EffectiveCloudAiPolicy.kt:29-43`).
3. U-PRIVACY-03 (retention/export redaction scope) — **LANDED for all three named targets**:
   - Retention: `di/RetentionModule.kt` registers 10 targets including `pending_reviews.notificationText` (:180), `background_job_runs.errorMessage` (:197), `bank_statement_import_items.merchant` (:214). Purge errors carry class name only (`errorMessage = "RETENTION_PURGE_FAILED:${it::class.simpleName}"`) and use `CancellationSafe.runCatchingCancellable` (CE-safe).
   - Export: `data/privacy/ExportAnonymizer.kt:28-29` documents and redacts `pending_reviews` (notificationText/notificationTitle → NULL) and `bank_statement_import_items` (merchant → '[REDACTED]').
   - Remaining gap (as the plan itself flagged): `expenses.notes` free text is NOT redacted in `ExportAnonymizer` (no reference in file) — likely by-design for a user's own export, but undocumented. Severity: MINOR / decision needed.

### U-PR6 — WorkerExecutionGuard contract (commit 74c2e5b8 + 7076d730)

All four claimed fixes verified in current code:
1. U-WORKER-01 barrier-before-log: `domain/workers/WorkerExecutionGuard.kt:559-569` — `startRunSafely()` calls `writeBarrier.checkWritesAllowed("WorkerRunLogger.start:…")` (:561) BEFORE `workerRunLogger.start(...)` (:562-569), with `DatabaseAccessBlockedException` → classified `StartRunResult.Blocked` (:570-573). U-WORKER-01 doc comment at :552-557.
2. U-WORKER-02 15-min stale recovery: `startup/AppStartupCoordinator.kt:346-347, 349-356` — `STARTUP_STALE_THRESHOLD_MS = 15 * 60 * 1000L` (:361, comment "U-WORKER-02") passed into `recoverStaleRunningJobs`. The guard's own default remains 4h (`WorkerExecutionGuard.kt:662`) — startup path overrides it. Recovery is CAS-based (`staleAbortIfStillRunning`, :584-599) and barrier-gated (:601-618).
3. U-WORKER-03 NO_WORK: `WorkerExecutionGuard.kt:362-368` — zero-count success emits `DiagnosticReasonCode.WORKER_NO_WORK.name` with message `"NO_WORK"` (introduced in 74c2e5b8 diff, confirmed present in `runGuardedWithContext` zero-count path).
4. U-WORKER-04 REPLACE: `domain/workers/WorkerSpecScheduler.kt:94` — `oneShotPolicy = ExistingWorkPolicy.REPLACE` with `// U-WORKER-04` comment; `data/ai/worker/DailyBriefingWorker.kt:128-138` logs midnight-reschedule failure (`if (!result.scheduled) Timber.w(...)`); `shouldRescheduleNextMidnight` (:168-174) keeps the chain alive on Success/incidental Skips.
5. NEW-P9-001 timeout retry: commit 7076d730 exists and is an ancestor of HEAD; `WorkerExecutionGuard.kt:31` (`WorkerTimeoutPolicy { RETRY, PROPAGATE_CANCELLATION }`), :57 (default RETRY), :381-383 in `runGuardedWithContext` (TimeoutCancellationException → `run.retry(WORKER_TIMEOUT)`); `DailyBriefingWorker.kt:121-123` rethrows timeout as `RetryableWorkerException(WORKER_TIMEOUT)`. `classifyTransient` (:640-652) no longer treats TimeoutCancellationException as transient ("removed: cancellation is caught before this").

### U-PR7 — TimeProvider consistency

**Verified clean (claimed-fixed sites):**
- `service/reminder/BillReminderWorker.kt:64` — `timeProvider.now()` (no `System.currentTimeMillis`).
- `service/warranty/WarrantyExpirationWorker.kt:85,88` — `timeProvider.now()`.
- T1–T4C commits (`da2b8565`..`96c6b27d`) confirmed ancestors of HEAD (e.g. 96c6b27d "T4C migrate year and quarter boundaries"); `domain/forecasting/FinancialStressForecastEngine.kt:105-106` uses DST-safe `TimePeriodUtils.addDays(now, -90/-60)`; zero `Calendar` occurrences in that file.

**Remaining at HEAD (fresh sweep of `app/src/main/java`):**
- `System.currentTimeMillis()`: ~46 hits in 22 files; excluding 4 that are string literals in deprecation messages (`TransactionLifecycleEventWriter.kt:33,71`, `ReceiptLifecycleEventWriter.kt:34,71`), the 2 legitimate ones in `SystemTimeProvider` itself, and debug-only files (NotificationSeeder ×6, DebugViewerScreen ×3, AiRuntimeDiagnostics ×3, CategorizationDebugScreen ×2, ServiceDiagnostics ×2, DebugViewModel ×1), the material production residue is:
  - `domain/forecasting/FinancialStressForecastEngine.kt:91,160` — perf-timing for a Timber duration log only (not domain time). MINOR.
  - `data/rescue/FinancialRescueCoordinator.kt:586,607,757` — rescue timestamps/markers.
  - `domain/ai/validation/AiOutputValidators.kt:30`, `domain/parser/AppParserRegistry.kt:42` — validation epoch bounds.
  - `data/database/AppDatabase.kt:540,1360` — seeding in DB callbacks (commented attribution).
  - UI display paths: `ui/util/UiTimeUtils.kt`, `ui/screens/warranty/WarrantyTrackerScreen.kt`, `ui/screens/home/HomeScreen.kt`, `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt`, `ui/navigation/NavigationController.kt:2 sites`.
  - `domain/receipt/lifecycle/ReceiptAssetStore.kt:47,69` — filename uniqueness (explicitly benign per `ReceiptOcrService.kt:737` comment).
- `Calendar`: 25 files use `Calendar` in main source; `domain/logic/SynthesisEngine.kt` alone has 46 `Calendar.` occurrences (month/day grouping, block-party day buckets, date fabrication at :530-532) — NOT migrated. The gr-00-local commit 8b45879e ("eliminate all direct wall-clock reads … 78 violations, 40 files") is **NOT an ancestor of HEAD** (`git merge-base --is-ancestor` fails) — the sweep remains unmerged. Severity: MINOR (testability/DST), except SynthesisEngine date fabrication which also affects correctness around month boundaries.

### U-PR8 — Side-effect semantics

- U-SIDEEFFECT-01 (double dispatch) — **still NOT a bug**: `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:968` uses `createExpenseDbOnlyV2` (no internal dispatch); :1031 documents planner pre-link awareness "prevents double-dispatch"; :1083-1091 single combined `postCommitActionRunner.runBestEffortAfterCommit(combinedBatch)`.
- U-SIDEEFFECT-02 — **FIXED**: `domain/transaction/lifecycle/TransactionSideEffectPlanner.kt` takes `triggerType` as a parameter (:163, :195, :232, :268) and builds idempotency keys as `"expense:$expenseId:${triggerType.name.lowercase()}:…"` (:175, :207, :244, :280). No hardcoded `:created:` on update paths remains for merchant learning/stats (literal `:created:` remains only for genuinely-created-only actions, e.g. recurring matching :319-325).

**NEW finding (privacy) in the same subsystem — see §3, category N1:** `e.message` flows into persisted `TransactionEvent.reason` via `SideEffectOutcome` reasons.

### U-EXPORT-01 — JSON export validity — FIXED (tracker stale)

`ui/screens/export/ExportOptionsViewModel.kt`:
- `writeJsonPageRows` (:593-660): leading-comma logic `if (!first) append(',')` with `first = pageNumber == 1` (:603, :610-611) — page boundaries no longer produce missing/extra commas; all strings pass `escapeJson`; null-safe number emission via `formatJsonNumber`/`?: "null"` (:634, :639).
- `sourceLinks` appended raw as already-valid JSON with the `P12-PR1 (NEW-P12-002)` marker (:651-654).
- Streaming wrapper opens/closes the rows array and root object by format (:745, :800-815).
No remaining string-assembly comma bug found in the JSON path. Severity: n/a (fixed).

### U-EXPORT-02 — CsvCellSanitizer corrupts negatives — FIXED (tracker stale)

`domain/export/CsvCellSanitizer.kt:39-44` — `P12-PR1 (NEW-P12-003)` fix present: a leading `-` is neutralized ONLY when the trimmed value is not a plain number (`Regex("^-?\\d+(\\.\\d+)?$")`); genuine negative amounts pass through un-prefixed, while `-cmd|'/C calc'…` style payloads are still neutralized. RFC-4180 quoting retained (:52-58). All accounting exporters route fields through it (`domain/export/AccountingExporters.kt:78-82, 103-132, 150-158`).

### U-DEAD-01 — Dead dashboard widgets — FIXED (tracker stale)

`domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`:
- Runway: `P5-PR1 (NEW-P5-011)` fix in `computeRunwayAndForecast` — `totalRemaining` from budget or monthly-income proxy (:595-604), `runwayDays = totalRemaining / averageDailyBurn` (:606-612) with explicit `RunwayStatus.NO_INCOME` when income is 0. Runway is no longer structurally 0.
- previousMonth: `previousMonthAggregate` is actually computed (:378-421 in `produceDashboardNormalizedInput`) and consumed at :574 (`previousMonthTotal = normalized.previousMonthAggregate?.displayAmount`). `InsightsEngine.kt:95,111` nulls appear only on the deliberate `input.period == null` early-return path (designed empty state, `PaceStatus.NO_BASELINE`), not the main path.

---

## 3. NEW universal categories discovered this session (candidates for U-PR9+)

### N1 — `e.message` (raw exception text) persisted into diagnostics/DB — NEW PRIVACY CATEGORY. Severity: CRITICAL (per AGENTS.md privacy rules: never persist arbitrary `e.message`)
No existing U-PR covers "exception message into persisted diagnostics"; PR19-6 fixed it for retention purge errors only. Occurrences:
1. **TransactionSideEffectPlanner → TransactionEvent.reason chain (6 sites)**: `domain/transaction/lifecycle/TransactionSideEffectPlanner.kt:186,222,258,303,340,381` — `SideEffectOutcome.FailedRetryable(e.message ?: "…", e.javaClass.name)`; flows through `PostCommitActionRunnerImpl.kt:95,103` (`eventWriter.failed(reason = outcome.reason, error = null)`) into `domain/sideeffect/TransactionSideEffectFailureEventWriter.kt:59` (`reason = reason.take(200)` → persisted `TransactionEvent.reason`). Note: the writer's :57 stores only `error?.javaClass?.name` (allowed); the leak is the `reason` string. A Room/SQL exception inside budget check or merchant learning therefore persists its raw message into the events table. (Earlier pipeline reports attributed this to writer :57 — attribution corrected.)
2. **Restore/reset journal**: `data/repository/DatabaseBackupRepositoryImpl.kt:2388` (`safetyBackupResult.exceptionOrNull()?.message` → run terminal message), `:2438` (`restoreJournal.failJournal(journalEntry, e.message ?: "Reset failed")` — persisted journal), `:2450` (`resetEvents.finalizeRunFailed(e.message ?: "Exception", e)`).
3. **Notification intake extrasJson**: `service/NotificationCaptureService.kt:554-559` — under `STORE_RAW`, build failure yields `"{\"error\": \"${e.message}\"}"` persisted as intake extras. Bonus bug: unescaped interpolation produces invalid JSON when `e.message` contains quotes.

### N2 — TOCTOU outside TransactionLifecycleCoordinator. Severity: MAJOR
The universal fix covered the transaction coordinator only; sibling lifecycle services retain read-outside-txn + full-row-stale-entity-write patterns.
- `domain/receipt/lifecycle/ReceiptLinkService.kt:168→252-261` (link) and `:382→411-421` (unlink) — stale `ScannedReceipt` full-row rewrites.
- `domain/bank/BankApiIntegration.kt:428` + `BankConnectionDao.updateToken` — targeted-column update, last-writer-wins (low).
- `data/store/ExpenseWriteStore.kt:34-36` — snapshot-blind full-row facade (guard-rail gap).
(PrivateSettings TOCTOU is already handled: `data/privacy/PrivacySettingsRepositoryImpl.kt:59,114` mutex comments.)

### N3 — Fix-in-dead-path (fixes landed on code with no production callers). Severity: process risk (false confidence)
- `domain/usecase/receipt/ProcessReceiptUseCase.kt` — U-MONEY-03 `?: "EUR"` fallback lives here; zero production call sites.
- `domain/bank/BankApiIntegration.kt` — `refreshToken`/`updateToken` improvements gated by `@StubForDemo`/`requireStubMode()`; MIT-041 already flags bank path as stub/demo.

### N4 — Permission-gating / capability-gating unrelated work. Severity: MAJOR when present
Cross-checked this session: the AGENTS.md rule (notification permission gates posting only) was verified consistent with the privacy-gate design in exportDatabase (capability checks scoped to export operations, `DatabaseBackupRepositoryImpl.kt:365-374, 400-419`) and DailyBriefing (privacy-denied still reschedules, `DailyBriefingWorker.kt:149-166`). No new violation found in the sampled paths; category retained from prior pipeline findings (P8) pending a dedicated sweep. Also note the inverse-safe pattern now exists: `DataRetentionWorker`/cleanup uses `runCatchingCancellable` and cleanup targets are NOT gated on the capability they enforce (`di/RetentionModule.kt`).

### N5 — Conversion-failure silent raw-mixing in SynthesisEngine. Severity: MAJOR (multi-currency)
Listed under U-PR3 residuals (`?: raw`/`?: monthly`/`?: expense.amount` at :333, :345, :460, :477-478, :554). Differs from U-MONEY-03 (EUR default): the sum silently mixes unconverted foreign amounts with no exclusion or quality warning, unlike the exclusion+warning pattern in FinancialStressForecastEngine:375-382.

---

## 4. Tracker/doc drift found this session

1. UNIVERSAL_ISSUE_TRACKER.md line 7 and §7 U-PR1 status: "146 guards across 38 files" — actual main-source CE-guard occurrences now 257 across 82 files (broader pattern); claim is a stale snapshot, understating current coverage.
2. U-PR2 plan + tracker: "atomicReadModifyWrite helper applied to all update/delete methods" — helper does not exist; equivalent guarantee implemented as read-inside-transaction. Doc should be corrected to describe the shipped mechanism.
3. UNIVERSAL_ISSUE_TRACKER.md §7/§8: U-PR5 shown as unlanded — actually U-PRIVACY-01 LANDED (incl. `rawBankStatementStorageMode`) and U-PRIVACY-03 LANDED for pending_reviews / bank_statement_import_items / background_job_runs; only U-PRIVACY-02 is partial (1 production `requireAllowed` caller).
4. U-BARRIER-02 "280 sites, no static guard" (plan doc) — 284 sites confirmed, but a static DAO-access-boundary guard now exists (`scripts/verify_db_access_boundaries.py`, `config/db_access_allowlist.yml`, `scripts/ci/run_static_guard_suite.py`); it is allowlist-based, not per-call-site.
5. MIT-034 header: "97 entries" allowlist — actual `KNOWN_VIOLATIONS` = 98 entries; "~65 UI ViewModels" — actual UI category = 38.
6. U-EXPORT-01, U-EXPORT-02, U-DEAD-01 tracker rows show open/no-PR — all three verified FIXED in code (P12-PR1 / P5-PR1 markers present).
7. U-PR6 plan says stale recovery threshold "reduced to 15 min" — true only for the startup path (`AppStartupCoordinator.kt:361`); the guard default remains 4h (`WorkerExecutionGuard.kt:662`). Docs should state both.
8. Pipeline-report attribution drift: "e.message at TransactionSideEffectFailureEventWriter.kt:57" — line 57 stores exception CLASS name (allowed); the leak is via `reason` (:59) fed by `TransactionSideEffectPlanner.kt:186/222/258/303/340/381`.

---

## 5. Recommended next actions (ordered)

1. **Sanitize persisted failure reasons (N1 — privacy, CRITICAL).**
   Replace `e.message` with controlled constants in `TransactionSideEffectPlanner.kt:186,222,258,303,340,381`; in `DatabaseBackupRepositoryImpl.kt:2388,2438,2450` keep class name only (mirror PR19-6 pattern); in `NotificationCaptureService.kt:556-559` replace `"{\"error\": \"${e.message}\"}"` with a constant reason and JSON-safe encoding. Add a static guard test asserting `SideEffectOutcome` reasons and `restoreJournal.failJournal` inputs are controlled constants.
2. **Fix the two ReceiptLinkService TOCTOU residuals (N2 — MAJOR).** Re-read `scannedReceiptDao.getById(receiptId)` inside `transactionRunner.runInTransaction` before the full-row updates at `ReceiptLinkService.kt:252` and `:411/:419` (mirror the P2-08 pattern), or switch to targeted-column updates (`claimForAutoMatch`-style CAS already exists at :242).
3. **Close the U-PR1 honest tail.** Add `if (it is CancellationException) throw it` (or `CancellationSafe.runCatchingCancellable`) to `TransactionLifecycleCoordinator.kt:321,343,515,652,691`, `NotificationProcessingPipeline.kt:819-823`, `AdvancedAnalyticsEngine.kt:985-987`, `AppStartupCoordinator.kt:350-355`.
4. **Finish U-PRIVACY-02.** Route `CloudDashboardBriefingService` (:134) and the bank-statement cloud path (`CloudReceiptAssistService.kt:277`) through `EffectiveCloudAiPolicy.requireAllowed` (or document why PrivacyGate-only is authoritative), and decide/document the `expenses.notes` export-redaction question.
5. **Fix SynthesisEngine conversion-failure handling (N5).** On `convertAmount(...) == null`, exclude the item and surface a quality warning (mirror `FinancialStressForecastEngine.kt:375-382`) instead of `?: raw` mixing (:333, :345, :460, :477-478, :554).
6. **Land or explicitly close the gr-00-local time sweep** (8b45879e, not an ancestor of HEAD) covering the remaining ~40 `System.currentTimeMillis()` sites and SynthesisEngine's 46 `Calendar` uses; until then, record the residue as accepted debt in the tracker.
7. **Recurring report currency scope (U-MONEY-03 remainder).** Add a currency assertion to `RecurringLifecycleCoordinator.calculatePlannedVsActualReport` (:1204-1206) or normalize via converter; delete or wire the dead `ProcessReceiptUseCase` (`?: "EUR"`).
8. **Doc sync.** Update UNIVERSAL_ISSUE_TRACKER.md rows for U-PR5/U-EXPORT-01/U-EXPORT-02/U-DEAD-01 and the U-PR1/U-PR2 implementation-status blocks to match the code (per §4), keeping "partial" wording where residuals remain (U-MONEY-03, U-PRIVACY-02, allowlist burn-down).

---

*All evidence above collected statically (Read/grep/git log -S/-L/merge-base) at commit d1fa9c68 on 2026-09-04. No builds, tests, or state-changing git commands were run; no existing files were modified.*
