# Regression Scan — Recent Refactor Waves (2026-09-04)

- Validated at: `d1fa9c68` (branch `atomicity-pr21-enforcement-final`). Static review only.
- Scope: the two largest recent refactor waves, diff-inspected commit by commit:
  - **Wave A — Time migration T1–T4C** (`da2b8565` … `96c6b27d`, 16 commits, ~78 clock reads across 40 files → TimeProvider/java.time)
  - **Wave B — PR18–PR24 atomicity/cancellation/event-consistency + PII/structured-runCatching burn-down + `bb2a6f18` gate repair**
- New findings from this scan are registered in `16_NEW_ISSUES_FOUND.md` under `NEW-REG-2026-*`.

---

## Wave A — Time migration T1–T4C: **0 real regressions**

**1. INFO-CLEAN — the feared `getEndOfDay` inclusive→exclusive flip did not happen.**
The pre-migration implementation (verified at `0c68f5c6~1`) was already exclusive next-day midnight (`startOfDay + Calendar.add(DAY_OF_MONTH, 1)`); the new java.time path (`domain/util/TimePeriodUtils.kt:484-491`) is semantically identical and DST-correct. The legacy cutover seam (`TimePeriodUtils.kt:219-224`) reproduces the old code verbatim for pre-cutover timestamps that real data never reaches. All consumers verified consistent: `WarrantyTrackerRepository.kt:603,667`, `WarrantyTextExtractor.kt:274`, `TimeBoundaryTicker.kt:69`, `BudgetRepository.kt:861`, `AddExpenseViewModel.kt:304` (`end-1` still yields 23:59:59.999).

**2. MINOR — CashFlowCalculator emits an extra partial day for non-day-aligned windows** (`1364aec1`, `domain/cashflow/CashFlowCalculator.kt:144,275`).
Old cursor preserved the caller's time-of-day and stopped when the raw cursor passed `endDate` (e.g. start Apr 1 22:00 → end Apr 2 05:00 emitted only Apr 1); the new LocalDate cursor emits every local date whose midnight is `< endTime`, so Apr 2 is now emitted (dayEnd exceeds the requested range). Arguably a fix (in-range expenses no longer dropped); production callers use day-aligned months. → registered as `NEW-REG-2026-004` (MINOR).

**3. MINOR (pre-existing, NOT introduced by T-series) — `Duration.toDays()` DST-unsafe denominators confirmed but misattributed.**
`TotalsAggregationEngine.kt:553,609` uses `Duration.between(...).toDays()`, which truncates: a DST month is 743h → 30 "days" for a 31-day month, inflating `avgPerDay` up to ~3%. `git log -S` shows introduction in `8c2289ef` (pre-T-series); the T-series never touched this file (0 diff lines across all 16 commits). Same file has fixed-millis `dayEnd = dayStart + 86_400_000L` (`:568`), also pre-existing. → `NEW-REG-2026-005` cluster.

**4. MINOR (pre-existing, missed by the sweep) — DST-unsafe arithmetic left in T-series-adjacent engines:**
`ComputeDashboardWidgetsUseCase.kt:672` (`monthStart + dayIndex * DAY_IN_MILLIS` daily-history buckets drift 1h across DST → midnight-adjacent expenses mis-bucketed); `SpendingChallengeManager.kt:55,68-73` (`± DAY_MS` streak stepping); `ReceiptTransactionMatcher.kt:84-87` (`lookbackDays * 86400000`). SynthesisEngine retains ~46-51 `Calendar` uses but its arithmetic is `Calendar.add`-based (DST-safe, just unmigrated). The sweep was scoped, not complete; behavior unchanged vs pre-migration. → `NEW-REG-2026-005` cluster.

**5. INFO-CLEAN — remaining ~49 `System.currentTimeMillis()` sites are non-behavioral:** elapsed-log timing (`FinancialStressForecastEngine.kt:91,160`), temp/backup file naming (`ReceiptAssetStore.kt:47,69`, `FinancialRescueCoordinator.kt:586,607,757`), tolerant plausibility bounds (`AiOutputValidators.kt:30`, `AppParserRegistry.kt:42,74`), and `@Deprecated(ERROR)` doc stubs. NotificationCaptureService has no wall-clock reads. T1's monotonic separation is real (`AssistantViewModel` uses `MonotonicTimeProvider.nowNanos()`).

**6. INFO-CLEAN — T3B DAO default removal is compile-enforced** (`94fb5d86`): all timestamp params became required (e.g. `RecommendationDao.getActiveByUser(userId, nowMillis)`); no NULL-write path; tests strengthened. Test audit across all 16 commits found **strengthened** assertions only (wall-clock tolerance → exact fake-clock equals). `BudgetCalculator` month/year anchoring (`5eec67c7`) preserves `Calendar.add(MONTH,1)` day-31 clamping exactly.

**Wave A verdict:** 0 real regressions; 1 minor behavior delta (cashflow partial-day, arguably a fix); 3 pre-existing DST/elapsed-day weaknesses the sweep deliberately or accidentally left behind. The migration is otherwise semantics-preserving. Note: the further sweep on unmerged `gr-00-local` (`8b45879e`) closes the Calendar/currentTimeMillis leftovers and is not an ancestor of HEAD.

---

## Wave B — Atomicity/cancellation + PII burn-down + `bb2a6f18`

**1. MAJOR (latent) — `refresh()` never returns; `isLoading` stuck + collector leak.**
`ui/screens/bank/BankConnectionsViewModel.kt:75-85`. Commit `d40c230b` replaced `5ed47933`'s SharedFlow `refreshTrigger.emit()` (returned immediately) with `coordinator.observeConnections().collect{...}`. `observeConnections()` is a cold Room-backed Flow (`BankConnectionLifecycleCoordinator.kt:18`), so `collect` never completes → `finally` (`:83`) never runs → `isLoading` stays true forever → permanent `CircularProgressIndicator` (`BankConnectionsScreen.kt:68`), and each call leaks another permanent collector. Caveat: no UI call site for `refresh()` today, so latent. Pattern sweep of `d40c230b` found no other instance; the collect-in-loader pattern in VisualSplit/Savings/Warranty ViewModels predates the waves. → `NEW-P10-2026-001`.

**2. MAJOR (guard fail-open) — raw runCatching allowlist expiry never enforced.**
`app/test/.../architecture/CancellationSafetyArchitectureGuardTest.kt:406-413`: the raw-runCatching expiry test only asserts `assertNotNull(entry.expires)` — a non-null `LocalDate` always passes — unlike `KNOWN_VIOLATIONS`, which checks `isBefore(today)` (`:352-357`). All 12 `RAW_RUN_CATCHING_ALLOWLIST` entries expire 2026-10-01 (`f652218f`/`65c265fb`); nothing fails when they do, and matching is per-file, so new raw `runCatching` in those 12 files (incl. `DatabaseBackupRepositoryImpl.kt`) passes silently. → `NEW-REG-2026-001`.

**3. MINOR — CE rethrow leaks into a caller that swallows it.**
`FinancialStressForecastEngine.kt:165` (wave `e12aad97`) now rethrows CE; caller `ComputeDashboardWidgetsUseCase.kt:963-970` catches bare `Exception` without CE rethrow → on cancellation the CE is logged as an error and the widget returns null instead of a degraded result. Impact: log noise + widget flicker; the file is on the guard's own allowlist so the guard cannot flag it. → `NEW-REG-2026-002`.

**4. MINOR — cancellation cause chain discarded.**
`BankConnectionsViewModel.kt:47,67` (`d40c230b`): `catch (_: CancellationException) { throw CancellationException("Sync cancelled") }` replaces the original `JobCancellationException` with a cause-less CE, losing structured-cancellation diagnostics. → `NEW-REG-2026-003`.

**5. INFO-CLEAN — `bb2a6f18` contains no test/guard weakening.** Full diff reviewed: all Kotlin test changes are compile repairs (MockK value-class pinning to EUR is behavior-equivalent — fixture returns "EUR" at `CashFlowCalculatorTest.kt:82`; `CapturingSlot`; `diagnostics!!`). `AssistantViewModelTest.kt:397-402` anti-leak asserts became `errorMessage?.contains(x) == true` (vacuous if null) but are pinned non-null by the preceding `assertEquals`. Python guard tests 1→2 match the real `GuardFatalError` exit contract; still nonzero. `MIGRATION_145_146` backticking matches Room canonical schema; `withLockSuspend` holds the lock in `finally`.

**6. INFO-CLEAN — known e.message gaps are pre-existing burn-down misses, not wave regressions.** Blamed origins: `TransactionSideEffectPlanner.kt:186,222,258,303,340,381(,418,455,489,618)` → `PostCommitActionRunnerImpl.kt:95,103` → `TransactionSideEffectFailureEventWriter.kt:59` (`reason = reason.take(200)` persisted) originates `534f7aec` (2026-05-21); `DatabaseBackupRepositoryImpl.kt:2388,2438` (`51cdb7d9`), `:2450` (`c67c2c82` — the reset path still persists `e.message` although `92a6ebf7` sanitized the restore path in the same file); `NotificationCaptureService.kt:558` (`f4aac79f`, `"{\"error\": \"${e.message}\"}"` into persisted raw-notification JSON — also malformed JSON). Root cause of the miss: `verify_pii_logging_boundaries.py` Rule 1 only flags `e.message` adjacent to log/print calls — data-flow into outcomes/events/journals is unscanned, and the allowlist is now `[]`, so "0 violations" is a detection-scope artifact. → systemic cluster `NEW-REG-2026-002`/see `16_NEW_ISSUES_FOUND.md`. The typed replacements themselves (`92a6ebf7`) are good: controlled constants, exception objects still passed for class-name capture.

**7. INFO-CLEAN — MIT-031/041 atomicity wrappers.** `RoomDomainTransactionRunner` uses `database.withTransaction` (Room 2.7.2, reentrant on the same coroutine); finalize+status+event transactions in `BankStatementLifecycleProcessor` are top-level (no dangerous nesting found). `b1ad7bc0`/`64bf5631`/`ca760783`/`b00241d7` only shrink allowlists or strengthen the provenance regex.

**Wave B verdict:** substantively sound; `bb2a6f18` weakened nothing. One genuine regression (`refresh()`, latent), one unenforced-expiry fail-open in the raw-runCatching guard, and pre-existing PII persistence gaps invisible to the narrow PII guard's detection scope.
