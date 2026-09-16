# Fresh Audit — New Issues & Regressions (post cross-validation)

> **Generated:** 2026-09-06 · Verified against HEAD `d1fa9c68` (2026-09-03) + uncommitted working-tree changes.
> **Method:** 13 parallel read-only deep-review agents (universal/cross-cutting + pipelines 1–12), each hunting exclusively for **regressions from the recently landed fix waves** and **new bugs not in any tracker**. Known/tracked issues (see `STILL_OPEN_ISSUES_2026-09-06.md`) were excluded from scope.
> **Limitations:** static code verification with file:line evidence. Unit tests were NOT executed (repo Gradle-coordination rules). Confidence column reflects evidence strength; Medium-confidence items warrant a quick confirmation before fixing. Findings are not yet runtime-reproduced.

**Totals: 124 findings — ~30 High severity, ~40 Medium, ~54 Low.** Per pipeline: U:6 · P1:8 · P2:10 · P3:10 · P4:6 · P5:14 · P6:10 · P7:11 · P8:8 · P9:9 · P10:12 · P11:10 · P12:10.

---

## 0. Regressions caused by previously "fixed" issues (read these first)

These are direct consequences of tracked fixes — the fix works but introduced or left a new defect:

| Fresh ID | Tracked fix that caused it | Regression |
|---|---|---|
| **FRESH-P9-001** | NEW-P4-005/006 (deterministic notification IDs from `delivery.id`) | `(delivery.id % Int.MAX_VALUE)` bypasses the reserved `NotificationIdGenerator` ranges — a delivery with id 20450 collides with receipt-alert ID 20450; alerts silently replace each other. `NotificationIdGenerator.forBill` exists with **zero callers** (`BillReminderWorker.kt:247`, `NotificationIdGenerator.kt` ranges) |
| **FRESH-P5-004** | NEW-P5-003 (exclude not-mine/shared deposits from income) | The exclusion is dead at the UI boundary: `DashboardExpense` has no `isSharedExpense` field and `toDomainDashboard()` never carries it (`DashboardContractsAdapter.kt:179-191`) — shared deposits still counted as income, inflating runway |
| **FRESH-P6-007** | P6-CURRENT-001 (limit & spend on same period-end FX basis in BudgetRepository) | `BudgetForecastingEngine` still mixes bases: limit at `PERIOD_END` (:122-129) vs spend at `TRANSACTION_DATE` (:185-186) — a ~10% currency move flips risk HIGH→MEDIUM |
| **FRESH-P1-001/002** | NEW-P1-007 fix (deferred intake rows when gate not ready) | Deferred rows use a content-blind fingerprint (`DEFERRED_<keyHash>`) that never matches the live-path content fingerprint → silent permanent drop on key reuse AND double-ingestion after warm-up; also hardcodes `STORE_METADATA_ONLY`, overriding a STORE_RAW user's privacy choice (`NotificationIntakeCoordinator.kt:197,207,214-218`) |
| **FRESH-U-002** | U-PR1 (cancellation architecture guard) | The guard itself is structurally blind: `findSuspendFunBodyRanges` skips any suspend fun with default parameters (`=` before `{`), and "CE evidence" is token-presence, not rethrow (`CancellationSafetyArchitectureGuardTest.kt:664-669,210`) — which is exactly why FRESH-U-001 exists |
| **FRESH-P2-004** | Notification auto-accept path setting `skipDeduplication = true` | The flag only bypasses the pre-check; dedupeKey is still generated + unique index + STANDARD resolver still fuzzy-resolve to `DuplicateSkipped` (`TransactionLifecycleCoordinator.kt:507-584,648`) — documented contract is false |

---

## 1. Universal / cross-cutting (6)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-U-001 | High | High | **Cancelled create/update keeps writing to DB** — raw `runCatching` wraps suspending `currencyConverter.convertAsOf`; CE is swallowed into `null`, execution falls through into `withTransaction { upsert }` after cancellation was signalled. Same at event-write sites :652/:691 | `TransactionLifecycleCoordinator.kt:476,874` |
| FRESH-U-002 | High | High | Cancellation guard test skips suspend funs with default parameters (pervasive) and accepts CE *mention* as evidence → rule is vacuous for a large fraction of suspend functions | `CancellationSafetyArchitectureGuardTest.kt:664-669,210` |
| FRESH-U-003 | Med | High | `RAW_RUN_CATCHING_ALLOWLIST` expiry (2026-10-01) is never enforced — only `KNOWN_VIOLATIONS` has the expiry gate; exemptions silently become permanent | `CancellationSafetyArchitectureGuardTest.kt:350-358,405-413` |
| FRESH-U-004 | High | Med | **Barrier guard is evadable**: caller detection matches only receivers named like the lowercased DAO interface; verified invisible writers: `NotificationIntakeCoordinator` (`intakeDao`), `SourceLinkWriterImpl` (`sourceLinkDao`), `DataRetentionWorker` (`auditDao`); `RecurringOccurrenceMaterializer` (visible, no barrier) should make the test fail today — quietly allowlisted in `DirectEventDaoInsertGuardTest:171-175` instead | `WriteBarrierArchitectureGuardTest.kt:239-256` |
| FRESH-U-005 | Med | Med-High | Source-link dedupe is `exists`→`insert` with **no unique index** on `(targetType,targetId,identityKey)` (`EntitySourceLink.kt:15-20`) → concurrent linkers insert duplicates; `AlreadyExists` fallback unreachable | `SourceLinkWriterImpl.kt:45-48,84-92` |
| FRESH-U-006 | Low | High | Startup stale-run recovery + `importRestoreJournals` use raw `runCatching` around suspending calls | `AppStartupCoordinator.kt:353,369-371` |

---

## 2. Pipeline 1 — Notification capture (8)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P1-001 | High | High | Deferred-intake fingerprint is content-blind (see §0): silent permanent drop when Android reuses the notification key; double-ingestion when warm-up completes and the same content is re-captured | `NotificationIntakeCoordinator.kt:197,214-218` |
| FRESH-P1-002 | High | High | `captureForRetry` hardcodes `rawStorageMode = "STORE_METADATA_ONLY"` — a STORE_RAW user loses raw payloads for anything captured during the warm-up window | `NotificationIntakeCoordinator.kt:207`; honored at `NotificationIntakeWorker.kt:223,457` |
| FRESH-P1-003 | Med | High | WorkManager enqueue `Operation` never awaited/checked — process death or async enqueue failure orphans RECEIVED rows until the next listener-connect | `NotificationIntakeCoordinator.kt:142-146,232-236` |
| FRESH-P1-004 | Med | High | Cancellation window BEFORE the NonCancellable region (gate-decide/dedupe/filter): `onDestroy` kills in-flight work with no terminal diagnostic — dangling RECEIVED events; unused `SHUTDOWN_DRAIN_TIMEOUT_MS` shows a drain was planned then removed | `NotificationCaptureService.kt:412-511,855,188` |
| FRESH-P1-005 | Low | High | `SENSITIVE_EXTRAS_KEYS` misses `android.messages` (OTP text), `android.textLines`, `android.remoteInputHistory`, `android.conversationTitle` — defense-in-depth gap, STORE_RAW-gated | `NotificationCaptureService.kt:192-206,811-832` |
| FRESH-P1-006 | Low | High | Deduper TTL on wall clock: backward clock jump drops real notifications as duplicates; forward jump disables window. `SystemMonotonicTimeProvider` exists | `NotificationCaptureDeduper.kt:28` |
| FRESH-P1-007 | Low | Med | `captureForRetry` REPLACE unique-work cancels a pending deferred worker, orphaning its inserted row; different work namespace than live capture enables concurrent double-processing | `NotificationIntakeCoordinator.kt:232-236` |
| FRESH-P1-008 | Low | Med | NUL-delimited transient payload fields shift if notification text contains `\u0000` (silent column corruption) | `NotificationTransientPayloadCrypto.kt:45-56,70-77` |

Clean: markProcessed parity across all 14 sites; settings TOCTOU single-fetch holds; Semaphore(4) FIFO/no ordering dependency; intake claim CAS + recovery; filter regex compiled once; Keystore crypto path clean.

---

## 3. Pipeline 2 — Transaction lifecycle (10)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P2-001 | High | High | Entity-overload `deleteExpense` returns **success for a missing row** (falls through to `planDeleted` + side effects) while the id-overload returns failure — double-tap delete dispatches budget/recurring side effects against a nonexistent expense | `TransactionLifecycleCoordinator.kt:2113-2145` vs `2044-2067` |
| FRESH-P2-002 | Med | Med | Insert-conflict resolver ignores the `rawNotificationId` unique index (`Expense.kt:36`): a retry with drifted data fuzzy-resolves to an unrelated 5-min neighbor, or reports the wrong CREATE_INSERT_CONFLICT reason | `TransactionLifecycleCoordinator.kt:587,646-688` |
| FRESH-P2-003 | Med | High | `bulkUpdateMerchant` has no per-row collision guard (unlike `updateMerchant`/`updateType`): a dedupeKey collision mid-loop throws `SQLiteConstraintException` and **rolls back the entire bulk rename** | `TransactionLifecycleCoordinator.kt:1964-1969` |
| FRESH-P2-004 | Med | Med | `skipDeduplication=true` not honored end-to-end (see §0) — notification auto-accept still gets `DuplicateSkipped` and marked irrelevant | `TransactionLifecycleCoordinator.kt:507-584,648` |
| FRESH-P2-005 | Med | High | Missing-row/no-op semantics inconsistent across the 8 update sites; `updateCategory/Location/Merchant/Type` dispatch planner side effects even on no-op; `updateTypeAndTransferDetails` has no no-op check at all (spurious UPDATED event + full side effects) | `TransactionLifecycleCoordinator.kt:1043/1069,1325/1375,1405/1454,1485/1532,1558/1629` |
| FRESH-P2-006 | Low | Med | `updateTypeAndTransferDetails` rewrites the recomputed canonical dedupeKey even when type unchanged, without the collision check — stale-key rows (via deprecated `updateMerchantForMerchant`) can hit the unique index | `TransactionLifecycleCoordinator.kt:1561-1581,1608` |
| FRESH-P2-007 | Low | High | Transfer-detail update paths drop `correlationId` (null event, fresh unrelated corrId for side effects) — audit correlation impossible for transfer edits | `TransactionLifecycleCoordinator.kt:1473-1479,1526-1532,1611-1629` |
| FRESH-P2-008 | Low | High | Blocking dedupe does not exclude `isNotMine` rows while the candidate-list query does (`AND isNotMine = 0`) — blocking decisions and user-facing candidates disagree | `ExpenseDao.kt:546-650` vs `969` |
| FRESH-P2-009 | Low | High | 5 best-effort event-write sites use raw `runCatching` without CE rethrow (siblings rethrow) — inconsistent cancellation handling | `TransactionLifecycleCoordinator.kt:321,343,515,652,691` |
| FRESH-P2-010 | Low | High | `bulkUpdateMerchant` dispatches bulk side effects unconditionally, even when `affectedCount = 0` (siblings gate) | `TransactionLifecycleCoordinator.kt:1990` vs `1844,1924` |

Clean: all 14 mutating methods barrier-checked; bulk idempotency keys cannot lose side effects (runner executes unconditionally, keys are evidence-only); dedupe-key normalization consistent across create/update/bulk; strict-key path never fuzzy-matches; mid-txn throw rolls back atomically.

---

## 4. Pipeline 3 — Receipts/OCR (10)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P3-001 | High | High | **Camera-scan path never dispatches post-save side effects**: `processReceiptInput` plans nothing; `ReceiptSideEffectDispatcher.dispatchAfterSave` has **zero callers** — no warranty auto-extraction, no price-protection check, matching deferred to worker, for every camera/gallery/batch receipt | `ReceiptLifecycleCoordinator.kt:497-559`; planner called only on email path :1035 |
| FRESH-P3-002 | High | High | **AI-merge pairs by index**: `parseAiResponse` can return fewer items than parsed rows; `mapIndexed { i, cleanTx -> parsedTransactions[i] }` shifts types/dates after any skipped AI entry → wrong TRANSACTION_TYPE → type-aware dedupe checks wrong type → duplicates pass | `BankStatementLifecycleProcessor.kt:245-266`; `ValidateBankStatementTransactionsUseCase.kt:228-235` |
| FRESH-P3-003 | High | High | **OCR timeout treated as cancellation**: `TimeoutCancellationException` is a `CancellationException` → not retried despite maxAttempts=3 and rethrown by every guard; one slow page aborts the whole batch (`processBatch` coroutineScope cancels siblings) | `ReceiptOcrService.kt:591,781`; `ReceiptRepository.kt:612` |
| FRESH-P3-004 | Med | High | Link/unlink/suggest load the **full row before the txn and `@Update` the whole entity inside it**: a retention purge (`rawOcrText=''`) landing in the gap is **resurrected** — privacy regression + concurrent column updates reverted | `ReceiptLinkService.kt:168,252,382,411`; `ScannedReceiptDao.kt:155-164` |
| FRESH-P3-005 | Med | High | Cancelled statement import is unretryable: statement receipt committed early; re-import hard-fails on the pre-OCR `EXACT_HASH` check before the graceful duplicate path | `BankStatementLifecycleProcessor.kt:162-176` |
| FRESH-P3-006 | Med | High | Skips counted as failures: any `STATUS_SKIPPED` item marks the whole run FAILED + `PROCESSING_FAILED` event + receipt stuck PARSED even though 20 valid PendingReviews committed | `BankStatementLifecycleProcessor.kt:725-728,820` |
| FRESH-P3-007 | Med | Med-High | **`parsedItems` (product names) persisted unredacted on the camera path in ALL storage modes** — the email path redacts the same field per mode, the camera draft does not | `ReceiptRepository.kt:284-285` vs coordinator `:832-836` |
| FRESH-P3-008 | Low-Med | High | Orphan saved JPEG per failed OCR (file saved before OCR, never cleaned; fallback saves a second copy) | `ReceiptOcrService.kt:230-238` |
| FRESH-P3-009 | Low-Med | High | Post-save auto-match bypasses the CAS claim (`requireUnmatchedClaim` defaults false) — can stomp user REJECTED → AUTO_MATCHED during the matcher window | `ReceiptSideEffectPlanner.kt:339-347`; worker uses claim at `ReceiptMatchingWorker.kt:96` |
| FRESH-P3-010 | Low | High | AI blank currency hardcodes `"EUR"` into merged tx, dedupe and PendingReview (USD statement → EUR reviews, dedupe never matches) | `ValidateBankStatementTransactionsUseCase.kt:223` |

Clean: insertOrResolve fingerprint order + IGNORE insert; create-and-link rollback; timestamp policy at all sites; statement dedupe window boundaries consistent; cancellation finalize (NonCancellable + 2s) sound; no catastrophic regex; validator fails closed.

---

## 5. Pipeline 4 — Recurring rules (6)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P4-001 | High | High | **Subscription delete bypasses the rule coordinator**: raw `subscriptionDao.deleteById` (no FK on occurrences) leaves PLANNED occurrences + SCHEDULED deliveries alive → **ghost bill notifications forever** after deleting a subscription | `SubscriptionManagementRepository.kt:49-52`; `RecurringOccurrence.kt:12-18` |
| FRESH-P4-002 | High | High | Subscription update/toggle also bypass coordinator: `isActive=false` via raw update never runs the deactivate cascade (notifications continue); price/category edits skip regeneration (stale amounts) | `SubscriptionManagementRepository.kt:44-47,31-37` |
| FRESH-P4-003 | Med | Med-High | `updateRule` deletes **all** open PLANNED occurrences (no date filter) but regenerates only from today → a past-due unpaid occurrence (rent due Aug 30, edited Sep 2) is silently destroyed and never re-created | `RecurringRuleLifecycleCoordinator.kt:286-303` |
| FRESH-P4-004 | Med | Med | `projectFromRule` passes raw `now` (not start-of-day) as generation start → the due-today occurrence is skipped on its due day; read side was fixed, write side not | `RecurringPlanProjectionService.kt:63-80` |
| FRESH-P4-005 | Med | High | Month-end anchor drift: chained `Calendar.add(MONTH)` clamps Jan-31 → Feb-28 → **Mar-28 permanently**; distinct occurrenceKeys coexist with historical PAIDs at true dates | `RecurringOccurrenceExpander.kt:165`; `TimePeriodUtils.kt:1344-1348` |
| FRESH-P4-006 | Low | High | `FAILED_PERMISSION` deliveries are terminal in practice: never re-opened by `reopenDeliveryForOccurrenceWindow` (only CANCELLED/FAILED_TRANSIENT) → reminder window lost forever after permission re-grant | `RecurringReminderDeliveryDao.kt:174` |

Clean: claim CAS semantics (occurrence + delivery + stale-claim by claimedAt); terminal-status coverage via shared policy; idempotent materialization (unique keys + IGNORE); BillReminder ordering guard→settings→claim→revalidate; T4C boundary math DST-safe; snooze/dismiss barrier-checked.

---

## 6. Pipeline 5 — Dashboard/synthesis (14)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P5-001 | High | High | **Category/deposit/period aggregates span the 2-month source window but are consumed as current month** — TopCategories shows prev+current combined; `monthlyIncome` double-counts last month's deposits (inflating runway); MonteCarlo `spentToDate` includes all of last month. Only `monthAggregate` is clipped | `ComputeDashboardWidgetsUseCase.kt:368-397,605,737,771`; window at `DashboardContractsAdapter.kt:55-63` |
| FRESH-P5-002 | High | High | `paceStatus` hardcoded `NO_BASELINE` and never refined → SpendingPaceWidget never renders; OVER_PACE risk branches + insight are dead code | `ComputeDashboardWidgetsUseCase.kt:577,1081`; `SynthesisEngine.kt:788` |
| FRESH-P5-003 | High | High | Current MTD passed as `averageMonthlyTotal` baseline (semantically a historical average) → predicted discretionary collapses toward 0 early-month; the null→confidence-penalty branch becomes unreachable after first purchase | `ComputeDashboardWidgetsUseCase.kt:575`; `SynthesisEngine.kt:288-292,407` |
| FRESH-P5-004 | High | High | `isSharedExpense` lost in adapter mapping — shared-deposit exclusion dead (see §0) | `DashboardContractsAdapter.kt:179-191`; `Expense.kt:110` |
| FRESH-P5-005 | Med | Med-High | 6-month trend built from the 2-month fetch → months −6..−2 always zero-filled | `ComputeDashboardWidgetsUseCase.kt:829-835` |
| FRESH-P5-006 | Med | Med | Home-currency change invalidates the repo cache but triggers no dashboard recompute (reload trigger only fires at midnight / on data change) → labels vs amounts mismatched until then | `MultiCurrencyRepository.kt:86-95`; `HomeViewModel.kt:156,229,405` |
| FRESH-P5-007 | Med | High | `runBlocking` per-conversion in SynthesisEngine (blocks Default dispatcher threads) + LATEST basis mixed with TRANSACTION_DATE spend inside one KPI (`discretionaryBudget`) | `SynthesisEngine.kt:83-88,214,223,233,384-387` |
| FRESH-P5-008 | Med | High | `runCatching` swallows CE in FinancialHealthScoreV2 → on screen exit falls back to raw mixed-currency sums and still writes history | `FinancialHealthScoreV2.kt:93,99-103,383-385` |
| FRESH-P5-009 | Med | Med | Inconsistent home-currency failure modes: throw → empty charts vs typed Unavailable → 0.0 rows vs Result — three visible behaviors for the same failure | `MultiCurrencyRepository.kt:438-447,607-619`; `TotalsAggregationEngine.kt:633-646` |
| FRESH-P5-010 | Low-Med | High | Runway: day-1 zero burn → CRITICAL 0 days despite full budget; `.toInt()` truncation flips CAUTION/HEALTHY; committed/likely expenses displayed but not deducted | `ComputeDashboardWidgetsUseCase.kt:603,617-628` |
| FRESH-P5-011 | Low | High | Block-party daily history uses `dayIndex * DAY_IN_MILLIS` keys vs zone-aware `getStartOfDay` buckets — lookups miss after in-month DST shift | `ComputeDashboardWidgetsUseCase.kt:671-673` |
| FRESH-P5-012 | Low | High | TopCategories drops the uncategorized group but keeps it in the denominator (percentages don't sum; invisible spend) | `ComputeDashboardWidgetsUseCase.kt:775-776` |
| FRESH-P5-013 | Low | Med | MoM insight degenerates when previousMonthTotal = 0 ("higher by entire month") and mid-month MTD-vs-full-prev yields premature "spent less" | `ComputeDashboardWidgetsUseCase.kt:1109-1136` |
| FRESH-P5-014 | Low | Med | Monthly status average includes the partial current month → systematic UNDER_AVERAGE early each month (`excludeCurrent=true` exists, used for years only) | `TotalsAggregationEngine.kt:91,369-387` |

Clean: `daysBetween`/LocalDate DST-safe; normalization metadata not double-counted; `CurrencyConverter` historical bases never silently fall back; SynthesisEngine confidence penalty applied once; runway bills not double-counted; no formatting values fed back into math.

---

## 7. Pipeline 6 — Budget/forecast/cashflow (10)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P6-001 | High | High | **Stress outflows double-count failed-rule occurrences**: materialized read includes failed rules AND the fallback re-expands the same rules with no dedup (CashFlowCalculator's equivalent is saved by content dedup; the stress engine has none) → inflated crunch probability | `FinancialStressForecastEngine.kt:337-394` |
| FRESH-P6-002 | High | High | **Autopilot reflects on private `expenseDao` field — dies under R8** (`isMinifyEnabled = true`, no keep rule): release builds get empty history → every category budget recommended at −15% floor, one tap applies it | `BudgetAutopilotEngine.kt:238-245`; `app/build.gradle.kts:55` |
| FRESH-P6-003 | Med | High | The reflected DAO call is `@Deprecated(ERROR)` raw mixed-currency SUM — €300+$100 totals as 400 → wrong recommendations whenever non-home currency exists | `BudgetAutopilotEngine.kt:243`; `ExpenseDao.kt:1516-1530` |
| FRESH-P6-004 | Med | High | Partial current month counted as a full bucket in history → early-month averages collapse → −15% ratchet; same bias deflates predicted spending | `BudgetHistorySeriesBuilder.kt:48-51,76` |
| FRESH-P6-005 | Med | High | BudgetMonitor **ignores `isPartial`** in alert decisions (diagnostics only) — partial spend vs full limit → false-safe (no alert while rates missing) | `BudgetMonitor.kt:243-244,301` |
| FRESH-P6-006 | Med | Med | Rollover skipped whenever the *limit* aggregate is partial — benign latest-rate fallback disables rollover entirely; surplus vanishes, "Budget Exceeded!" fires against un-rolled limit | `BudgetRepository.kt:258` |
| FRESH-P6-007 | Med | High | Forecast engine rate-basis mismatch — regression vs repo fix (see §0) | `BudgetForecastingEngine.kt:122-129,153,179-186` |
| FRESH-P6-008 | Low | High | `[start,end)` while-gate drops already-overdue detected patterns; 90-day stale allowance is dead code for that path (CashFlowCalculator's catch-up loop is correct) | `FinancialStressForecastEngine.kt:418-433` |
| FRESH-P6-009 | Low | High | Currency-blind fallbacks: `daysAhead * 20.0` fabricated spend and 500/100 risk bands in home-currency numerals | `FinancialStressForecastEngine.kt:513-516`; `CashFlowCalculator.kt:426-431` |
| FRESH-P6-010 | Low | Med | "Earliest crunch date" is actually the horizon END; `/30.0*30.0` no-op math | `FinancialStressForecastEngine.kt:622-630,647-648` |

Clean: monitor hysteresis/cooldowns per severity with per-period re-arm; rollover ArrayDeque cap keeps exactly most-recent-365; cashflow day iteration DST/leap-safe; content dedup only on predicted items; planned-vs-occurrence overlap dropped; alert notification permission pattern correct; autopilot apply is user-confirmed + barrier-checked.

---

## 8. Pipeline 7 — Backup/export/restore (11)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P7-001 | High | High | **`resetDatabase` journals only PREPARING→COMPLETE**: crash between `liveDbFile.delete()` and COMPLETE leaves PREPARING, which recovery classifies *non-destructive* and deletes the journal → app opens EMPTY with no CRITICAL lock; the safety backup exists but no recovery path references it | `DatabaseBackupRepositoryImpl.kt:2404-2434`; `RestoreJournal.kt:705-711` |
| FRESH-P7-002 | High | High | **ASSETS_RESTORING crash → infinite restart loop with permanent write lock**: startup re-enters RESTART_REQUIRED and early-returns before the auto-reset block every launch; reachable without a crash (cancel during asset restore rethrows CE before `exit()`) | `AppStartupCoordinator.kt:71-78,209-217`; `DatabaseBackupRepositoryImpl.kt:1106-1108` |
| FRESH-P7-003 | High | High | **`.costbackup` writes via `java.io.File` into public Documents** — manifest has no MANAGE_EXTERNAL_STORAGE, targetSdk 35 → `mkdirs()`/FileOutputStream fails with EACCES on Android 10+; **backups silently impossible for modern-Android users** (after snapshot+verify succeeded) | `DatabaseBackupRepositoryImpl.kt:657-665`; `AndroidManifest.xml:23-26` |
| FRESH-P7-004 | Med | High | Startup recovery never consults the intact `.pre_restore` file (only the safety backup) — missed recovery source + leaked DB-sized file | `AppStartupCoordinator.kt:85-190`; `DatabaseBackupRepositoryImpl.kt:947-959` |
| FRESH-P7-005 | Med | High | Safety backup / DB copies not fsynced (journal is) — power loss can durably record SAFETY_BACKUP_CREATED while the safety file is partial → recovery restores a truncated DB → CRITICAL | `DatabaseBackupRepositoryImpl.kt:2342-2348` |
| FRESH-P7-006 | Med | High | Verification-failure rollback leaves staged DB + WAL/SHM orphans (journal already FAILED so startup cleanup won't fire) — disk waste + stale plaintext snapshots | `DatabaseBackupRepositoryImpl.kt:1096-1103` |
| FRESH-P7-007 | Med | High | PBKDF2 iteration count (600k) is a compile-time constant not persisted in the bundle — any future bump **bricks every existing backup** | `BackupEncryptionService.kt:46,159-164`; `CostbackupBundle.kt:38-43` |
| FRESH-P7-008 | Med | Med | Outer catch after file swap exits to NORMAL (`forceRestartRequired = false`) while journal says SWAPPING — writes can land in a DB that startup will roll back | `DatabaseBackupRepositoryImpl.kt:983,1105-1112` |
| FRESH-P7-009 | Low | High | Asset restore updates the DB row to `finalFile` before the rename exists on disk; failure deletes both copies — dangling imagePath, image lost despite COMPLETE journal | `DatabaseBackupRepositoryImpl.kt:1248-1268,1033` |
| FRESH-P7-010 | Low | High | `commitJournal` writes the outer (empty-task-ledger) entry, clobbering per-task asset ledger in the success journal (diagnostics impact) | `DatabaseBackupRepositoryImpl.kt:1024,1039-1040` |
| FRESH-P7-011 | Low | Med | `ai_chat_sessions.title` (user-derived text) not purged by ExportAnonymizer — the one residual redaction gap | `ExportAnonymizer.kt:216-224` |

Clean: zip-slip + decompression-bomb caps; integrity/migration verify before swap; partial-extract cleanup; liveCounts captured post-drain; WAL sidecar handling correct around swap; CRITICAL auto-reset exemption; drain/lease interplay; privacy_audit_events content safe.

---

## 9. Pipeline 8 — Privacy/retention/cloud-AI (8)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P8-001 | High | High | **Deleted-expense PII persists forever**: `transaction_events.beforeSnapshot/afterSnapshot` keep full expense JSON (merchant, notes) after the expense is deleted — by design events survive deletion, but `TransactionEventDao.deleteOlderThan` has **zero callers** and no retention target exists | `TransactionEvent.kt:79-80`; `TransactionEventDao.kt:36`; `RetentionModule.kt:33-228` |
| FRESH-P8-002 | Med | High | OCR purge nulls `rawOcrText` but `parsedItems`/`parsedMerchant`/`parseFailureReason` keep receipt content at rest past the retention window | `ScannedReceipt.kt:54,71,92`; `ScannedReceiptDao.kt:155-164` |
| FRESH-P8-003 | Med | High | `operation_runs`/`operation_run_events`/`receipt_events` have **no purge target and no delete methods** (content sanitized, so drift not leak — but inconsistent with background_job_runs' 30-day redaction) | `OperationRunEvent.kt:34-36`; `di/RetentionModule.kt` |
| FRESH-P8-004 | Med | Med | `ai_artifacts` with `expiresAt = NULL` (entity default) are purge-immune while carrying payloadJson — latent until a writer omits TTL | `AiArtifactDao.kt:70-71` |
| FRESH-P8-005 | Low-Med | High | Dead duplicate sanitizer copies in `CloudWarrantyExtractionService` predate the NEW-P8-004 PII patterns — a trap for the next caller | `CloudWarrantyExtractionService.kt:297-318,381+` |
| FRESH-P8-006 | Low | High | `requireAllowed()` handles only 4 of the cloud capability enum; the rest **throw SecurityException** — a dev wiring it for warranty/briefing ships a guaranteed crash (currently 1 production caller) | `EffectiveCloudAiPolicy.kt:29-40` |
| FRESH-P8-007 | Low | Med | `privacy_audit_events` never purged — unbounded growth of the audit surface and backup payload (content safe) | `PrivacyAuditDao.kt` (no delete) |
| FRESH-P8-008 | Low | Med | Redactor floor: <8-digit numbers pass (7-digit local phones leak); merchant hash unsalted + truncated (dictionary-reversible in outbound payloads) | `CloudPiiSanitizer.kt:9,56-57`; `DefaultCloudPayloadRedactor.kt:44-46` |

Clean: CompositePrivacyGate true AND-semantics + fail-closed; audit context allowlist enforced at write; DataStore settings atomic, corruption → fail-closed defaults; all 8 cloud call sites route through prepare*/redact; bank statement always strict-redacts; geocoding sends coordinates only; debug raw surfaces gated.

---

## 10. Pipeline 9 — Workers (9)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P9-001 | High | High | Bill-reminder notification IDs bypass reserved ranges → cross-type collisions (see §0); also snooze/dismiss request codes can collide across deliveries | `BillReminderWorker.kt:247,216,229`; `NotificationIdGenerator` (forBill: zero callers) |
| FRESH-P9-002 | Med | High | Guard drops `WorkerRunContext` counters on RETRY/FAILED/CANCELLED terminals (only success forwards ctx) — partial work systematically under-reported (e.g. 40 rows backfilled, row 41 fails → row shows 0) | `WorkerExecutionGuard.kt:416-427,363-370`; `WorkerRunLogger.kt:44` |
| FRESH-P9-003 | Med | Med | Permanent run-row *insert* failure maps to Retry in both branches → infinite retry with no FAILED terminal (e.g. SQLiteFull) | `WorkerExecutionGuard.kt:574-580` |
| FRESH-P9-004 | Med | High | DataRetention: permanent target failures → run recorded SUCCESS (even `NO_WORK`); crash-resume loses audit events + counts for pre-crash purges; failed-mark checkpoint keys never read | `DataRetentionWorker.kt:216-235,273-294` |
| FRESH-P9-005 | Med | Med | Intake `claimForProcessing` ignores `nextAttemptAt` — WorkManager's 30s backoff defeats the in-row exponential backoff; attempts burned, premature MAX_ATTEMPTS_EXCEEDED | `NotificationIntakeDao.kt:88-99` |
| FRESH-P9-006 | Low-Med | High | Warranty: null `getByKey` after successful claim strands the row CLAIMED until next daily stale-claim sweep (reminder delayed up to 24h) | `WarrantyExpirationWorker.kt:185-193,219` |
| FRESH-P9-007 | Low | High | NO_WORK mislabeling: quiet-hours skips and claims-lost runs (rowsSkipped>0) recorded as NO_WORK SUCCESS; ReceiptMatching counts notificationsSent without checking DeliveryResult | `WorkerExecutionGuard.kt:361-371`; `ReceiptMatchingWorker.kt:108` |
| FRESH-P9-008 | Low | High | 4 workers omit `specVersion` from guard requests (run rows permanently NULL) — inconsistent with the other three | `WarrantyExpirationWorker.kt:73-80` et al. |
| FRESH-P9-009 | Low | High | Scheduler comments say version bump forces UPDATE; code uses REPLACE for one-shots — doc/contract drift, future hazard | `WorkerSpecScheduler.kt:127-141,242-256` |

Clean: stop-request/lease lifecycle cannot wedge (in-memory flag dies with process); exception ordering in guard consistent (checkpoint/TCE/cancel); counters AtomicInteger; startup recovery cannot clobber live terminals; idempotency holds for both backfills and purges; notification-permission pattern correct in all workers; DailyBriefing skip-reschedule doesn't bump version.

---

## 11. Pipeline 10 — Bank sync (12) — weakest pipeline in this pass

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P10-001 | High | High | **`syncConnection` discards `SyncResult`** — failures, REAUTH_REQUIRED, and barrier-cancelled syncs all report `ConnectionSyncResult.Success`; the durable re-auth signal dead-ends | `BankConnectionLifecycleCoordinator.kt:43-45` |
| FRESH-P10-002 | High | High | **TRANSFER-typed transactions can never import**: default redacted mode nulls `transferAccountName`, missing `transferDirection` stays null → validator rejects both → guaranteed ValidationFailed for every real bank transfer | `BankApiIntegration.kt:496-500,518`; `TransactionValidator.kt:141-156` |
| FRESH-P10-003 | High | High | `lastSync`/`lastSyncStatus` never written (`updateSyncStatus` has zero callers) → status icon shows "syncing" forever, "Last synced" never renders, `shouldSync()` always true | `BankConnectionDao.kt:42-43`; `BankConnectionsScreen.kt:230,267-287,461-474` |
| FRESH-P10-004 | High | High | `refresh()` collects an immortal Room flow → `_isLoading` stuck true → **whole screen replaced by a spinner**; every refresh stacks another collector | `BankConnectionsViewModel.kt:75-87`; screen `if/else` at `BankConnectionsScreen.kt:68-74` |
| FRESH-P10-005 | Med-High | High | `abs(amount)` sign loss — refunds become positive DEPOSIT-typed **expenses**, inflating spend; 0.0 → UNKNOWN fails validation unconditionally | `BankApiIntegration.kt:512,543,548,270` |
| FRESH-P10-006 | Med | High | Sync button never disabled, no in-flight guard → double-tap runs concurrent syncs: duplicate PendingReviews (check-then-insert, no unique index) + token-refresh races | `BankConnectionsScreen.kt:244`; `BankConnectionsViewModel.kt:41-56` |
| FRESH-P10-007 | Med | Med | STRICT dedupe key omits `bankAccountIdHash` — provider tx ids are only per-account unique; cross-account collisions silently dropped as duplicates | `TransactionLifecycleCoordinator.kt:107-116` |
| FRESH-P10-008 | Med | High | `updateToken` has no optimistic lock (WHERE id only) — racing refreshes persist a superseded rotated token → permanent lockout misread as Failed | `BankConnectionDao.kt:45-52`; `BankApiIntegration.kt:428-434` |
| FRESH-P10-009 | Med | High | Disconnect races sync: token refresh **resurrects credentials on a disconnected row** (no `isConnected` predicate); bank-source PendingReviews orphaned post-disconnect | `BankConnectionDao.kt:39-52` |
| FRESH-P10-010 | Low-Med | High | `UserNotAuthenticatedException`/`KeyExpired` collapse into `Failed` → FAILED_FINAL "token expired" — transient keystore states get permanent classification, no retry/auth prompt | `BankTokenCipher.kt:77-81`; `BankApiIntegration.kt:207-215` |
| FRESH-P10-011 | Low | High | Bank low-confidence path dedupes against pending reviews from ALL sources (no source filter) — a notification review suppresses a distinct bank review with no linkage | `PendingReviewDao.kt:613-638`; `BankApiIntegration.kt:244-262` |
| FRESH-P10-012 | Low-Med | High | Stale-run recovery never wired: `recoverStaleRunningOperationRuns` + `getStaleRunningRuns` have zero production callers — killed syncs leave RUNNING rows forever | `OperationRunRecorder.kt:145`; `BankStatementImportRunDao.kt:44,53` |

Clean: STRICT hash input is the single provider tx id (no field-order fragility), HMAC deterministic across installs; CE handling correct end-to-end; recorder failures isolated; privacy of events (hashed ids, sanitized text); `completeConnection` idempotent; currency validated strict ISO.

---

## 12. Pipeline 11 — Email receipts (10)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P11-001 | High | High | Apple "Total" regex matches inside "**Sub**total" (appears earlier) → **expense = pre-tax subtotal**, tax/shipping silently dropped; makes the "Total Amount" pattern dead code | `AppleReceiptParser.kt:25-32` |
| FRESH-P11-002 | High | Med | Currency indicators include bare two-letter tokens (`US`, `IT`, `ES`, `GR`…) matched as bounded tokens on the uppercased body — the English word "it" flips a UK receipt to EUR | `AppleReceiptParser.kt:66-70,210`; `UberReceiptParser.kt:81-85,251` |
| FRESH-P11-003 | High | High | **The whole pipeline is unwired**: `EmailReceiptIngestionService` is injected nowhere, called nowhere in main sources (`saveEmailReceiptTyped` zero callers) — P11 is effectively dead code / staged feature. Confirm intent and mark or wire | `EmailReceiptIngestionService.kt:52`; `ReceiptLifecycleCoordinator.kt:616,659` |
| FRESH-P11-004 | Med | High | Date bucket = local-midnight parse ÷ UTC epoch hours → timezone/DST change re-splits the fingerprint bucket → forwarded/duplicate-delivered email re-ingests as new | `EmailReceiptParser.kt:175`; `EmailReceiptIngestionService.kt:402` |
| FRESH-P11-005 | Med | High | HMAC key is a hardcoded derivable constant (`"privacy-hmac-key-$purpose"`); blank messageId collapses all such emails into one hash → false Duplicate | `DefaultSensitiveHashingService.kt:25-27`; coordinator `:762-768` |
| FRESH-P11-006 | Med | Med | Total capture grabs trailing annotations ("€4.99 (incl. 0.79 VAT)") → parse fails → falls through to the weakest catch-all pattern (any line ending in a currency symbol) | `AppleReceiptParser.kt:25-32,156-171` |
| FRESH-P11-007 | Low-Med | High | Amazon item extraction lacks Apple's subtotal/tax skip list → summary rows persisted as phantom ReceiptItems | `AmazonReceiptParser.kt:174-197` |
| FRESH-P11-008 | Low | High | STORE_RAW stores the HMAC hash in `emailMessageId` although comment/sanitizer intend the raw id — DAO `getByMessageId(rawId)` can never match | `ReceiptLifecycleCoordinator.kt:864-868`; `RawContentSanitizer.kt:70-79` |
| FRESH-P11-009 | Low | Med | Refunds: positive "refund" emails book a second PURCHASE (double spend); negative amounts dropped as ParseError with no review trail | `AppleReceiptParser.kt:120`; `EmailReceiptIngestionService.kt:367-371`; coordinator `:960` |
| FRESH-P11-010 | Low | Med | NeedsReview receipts are a dead end: re-ingestion short-circuits as Duplicate — parser fixes/threshold changes can never upgrade them; stale PendingReview persists | `ReceiptLifecycleCoordinator.kt:762-777,1051-1069` |

Uncommitted-change check: the Apple regex un-escape and sender-gated `canParse` are correct and introduce no new regression (the body-fallback routing residual is the already-documented one). Clean: no catastrophic regex backtracking anywhere; Semaphore(3) not nested; email+receipt+event+review atomic in one txn; HTML entities + locale amounts handled; storage-mode nulling of sender/subject correct.

---

## 13. Pipeline 12 — Export/import/accounting (10)

| ID | Sev | Conf | Finding | Location |
|---|---|---|---|---|
| FRESH-P12-001 | High | High | **Importer splits on `\n` before CSV parsing** — the exporter emits RFC-4180 quoted multi-line fields (notes), so every such roundtrip row fails AND the continuation line can import as a garbage expense | `CsvExpenseImporter.kt:55` vs `CsvCellSanitizer.kt:55-59` |
| FRESH-P12-002 | Med | Med | UTF-8 BOM (Excel re-save) defeats the `#` comment predicate (`trim()` doesn't strip `\uFEFF`) → comment line becomes the header → 100% import failure | `CsvExpenseImporter.kt:60-68`; `ImportCoordinator.kt:40-44` |
| FRESH-P12-003 | Med | Med | Date formatter uses default locale — non-ASCII-digit locales fail every row ("Invalid date") | `CsvExpenseImporter.kt:40,138`; exporters correctly pin Locale |
| FRESH-P12-004 | Med | Med | CSV_FULL roundtrip ignores `TransactionType`/`EffectiveAmount` — shared/refund rows re-import as full-amount PURCHASE (balances mutate; JSON importer does read them) | `CsvExpenseImporter.kt:131,172`; `JsonExpenseImporter.kt:61` |
| FRESH-P12-005 | Med | Med | Xero/FreshBooks format the conversion **rate** with `%.2f` — rates < 0.005 export as "0.00" (IDR→EUR ≈ 0.00006); generic CSV correctly carries `6.0E-5` — same field, two files, one wrong | `AccountingExporters.kt:113-119,165-171` vs `ExportOptionsViewModel.kt:577` |
| FRESH-P12-006 | Low | High | `cancel()` without join: cancelled export keeps writing; same-millisecond double-start collides on one temp file (corrupt output) | `ExportOptionsViewModel.kt:188-190,250,294` |
| FRESH-P12-007 | Low | Med | `allowsEmptyDataset()` true for all 5 formats → the zero-rows guard is dead code; header-only file reported as success | `ExportOptionsViewModel.kt:258,892-895` |
| FRESH-P12-008 | Low | Med | `.enc` container has no format/KDF-param header (salt+IV only; 600k iterations compile-time) — future param change silently bricks old exports | `BackupEncryptionService.kt:80-99,46` |
| FRESH-P12-009 | Low | High | `exports/` never pruned; process death orphans plaintext `.tmp_` financial files indefinitely; no startup sweep | `ExportDataRepository.kt:94`; `ExportOptionsViewModel.kt:349` |
| FRESH-P12-010 | Low | Med | ImportCoordinator discards `perRowResults` — user sees "7 errors" with zero detail | `ImportCoordinator.kt:29` |

Clean: keyset pagination `(date,id)` tuple is collision-free (minSdk 26 SQLite ≥3.18); header/column parity across pages; escaping symmetric exporter↔importer; PDF pagination/finally-close correct (fully-materialized path has no live callers); encryption fail-closed, streaming chunked; JSON importer has no drift; read barriers on all export entry points.

---

## 14. Cross-cutting themes (for the fix plan)

1. **"Landed but not wired" is the dominant new defect class** (9 findings): camera-scan side effects never dispatched (P3-001), P11 pipeline has no entry point (P11-003), sync results discarded + status never persisted (P10-001/003), stale-run recoveries never invoked (P10-012), pace widget dead (P5-002), empty-guard dead (P12-007), architecture-guard blind spots (U-002/004), `forBill` ID generator unused (P9-001). Recommend a "wiring audit" gate: every new public API must have ≥1 production caller or an explicit FUTURE tag.
2. **Cancellation-safety regressions**: the raw-`runCatching` construct the U-PR1 wave eliminated survives in production paths (U-001, U-006, P2-009, P5-008) — and the guard that should catch them is structurally blind (U-002/003).
3. **Coordinator bypasses**: subscriptions (P4-001/002) are a full parallel write path around the recurring lifecycle — same class as the previously fixed DAO bypasses.
4. **Privacy at rest**: snapshot/derived fields (`transaction_events` snapshots, `parsedItems`, chat titles) sit outside the retention/anonymization perimeter (P8-001/002, P7-011, P3-007) and one full-row-update pattern can resurrect purged OCR text (P3-004).
5. **Concurrency without constraints**: dedupe/claim logic relying on check-then-act without unique indexes or optimistic locks (U-005, P10-006/008/009).
6. **Recovery paths that exist but never run** (P7-001/002/004, P10-012) — each is a potential permanent-lock or silent-data-loss state machine bug.

## 15. Suggested remediation order

1. **Data loss / permanent lock:** FRESH-P7-001, P7-002, P7-003 (backup creation broken on modern Android), P7-005.
2. **Silent wrong numbers:** FRESH-P5-001/003/004, P6-001/002/004/005/007, P11-001, P12-005, P3-002.
3. **Privacy at rest:** FRESH-P8-001, P8-002, P3-007, P3-004, P7-011.
4. **Ghost notifications / lifecycle bypass:** FRESH-P4-001/002/003, P9-001.
5. **Bank-sync correctness cluster:** FRESH-P10-001…009 (one focused PR), P10-012.
6. **Guard/wiring hardening:** FRESH-U-001…005, P1-001…004, P3-001, P3-003, P11-003 (decide: wire or tag).
7. **Polish:** the remaining Low items + P12 import cluster (one PR), P8-005…008, P9-002…009.
