# Pipeline Fix Status

## Pipeline 6 — Budget / Forecasting / Cashflow

Audit: done (static review at HEAD `4227cee2`)
Implementation: slices A, B, C, D, E, F, G applied (C re-done with a BOUNDED as-of aggregate — see below)
Validation (human must run — NOT run by agent):
- assembleDebug: pending
- testDebugUnitTest: pending
- check: pending  (includes verifyDbAccessBoundaries — note CI does not run this; see P6-NEW-01)
- connectedDebugAndroidTest: not required (no schema/migration change in **this batch — batch 1, code-only**). NOTE: this scope applies to batch 1 only; the later GATED batch 3 DOES bump the Room schema 141→142 and DOES require `connectedDebugAndroidTest` — see "This session (batch 3 — GATED / schema-bumping)" below.

### Issues addressed this batch
| ID | Title | Status after change |
|----|-------|---------------------|
| P6-CURRENT-001 | Budget actuals latest-rate vs limit period-end | FIXED — `getAggregateSpent` now uses bounded `getHomeCurrencyPurchaseTotalAsOf` / `getHomeCurrencyPurchaseCategoryTotalsAsOf` (grouped-by-currency, converted at `RateBasis.PERIOD_END` for the period end). Shares the limit's FX basis; no uncapped row scan (truncation contract preserved) |
| P6-CURRENT-002 | Adjusted spend not in repo statuses | FIXED — `BudgetRepository.createBudgetStatus` now populates `adjustedSpendBreakdown` via the injected offset engine; monitor + UI share it |
| P6-CURRENT-003 | Alert currency not explicit | FIXED — `BudgetMonitor` passes `status.currency` to all 3 `sendNotification` calls |
| P6-CURRENT-029 | Validation gaps (NaN/thresholds/currency/status) | FIXED — `BudgetRepository.validateBudget` + `PlannedExpenseRepository` field validation |
| P6-CURRENT-012 | Recurring/confirmed unnormalized in synthesis | FIXED — `ForecastInputAssembler.assemble` converts `RecurringPattern.averageAmount` and `ConfirmedOccurrence.expectedAmount` to home currency; failures excluded + counted |
| P6-CURRENT-014 | Block-party counts PAID occurrences | FIXED — occurrence path now PLANNED-only AND PAID-only rules no longer fall through to the legacy matcher |
| P6-CURRENT-013 | Raw synthesize overload public | FIXED — raw overload is now `internal` |
| P6-CURRENT-021 | Stress detected/fallback recurring unconverted | FIXED — `expandDetectedPatterns` converts each amount to display currency, excludes on failure |
| P6-CURRENT-022 | Stress baseline currency mismatch | FIXED — `resolveStartingBalanceBaseline(displayCurrency)` resolves/convert baseline into the horizon display currency |
| P6-CURRENT-030 | Stale stress KDoc (PAID+PLANNED) | FIXED — KDoc corrected to PLANNED-only |

### Reverted / blocked
None — P6-CURRENT-001 was implemented with a bounded as-of aggregate (see above), so the
truncation-safety conflict no longer applies.

### Open blockers / follow-ups (NOT in this batch — need approval or larger work)
- P6-CURRENT-009 / 011: forecast RESTRICT doc alignment (largely moot after the batch-3 `RESTRICT → CASCADE` migration) + `forecastPeriodDays` semantics (forecast-persistence PR follow-ups).

(Closed since the prior list — batch 2: P6-CURRENT-018, 019, 024, 025, 026, 027; reclassified follow-ups P6-P1-03 / 04 / 05 / 10. **Batch 3 (GATED / schema-bumping):** P6-CURRENT-005, 008, 010, 015, 020, P6-P1-15, P6-NEW-01 — see the "This session (batch 3 — GATED / schema-bumping)" section below.)

### Known compile-risk areas (agent could not compile)
- `BudgetRepository`: new `import ...AdjustedSpendBreakdown`; `computeAdjustedSpend` helper; `validateBudget` helper.
- `SynthesisEngine`: raw `synthesize` overload visibility changed to `internal` — all current callers are in `:app` (prod + same-module tests), so visibility holds; verify no other module references it.
- `FinancialStressForecastEngine`: `expandDetectedPatterns` is now `suspend` + extra param; both call sites updated.
- `ForecastInputAssembler.assemble`: added normalization block; `ForecastInput` field wiring unchanged.

### Tests added/updated
- `BudgetRepositoryHistoricalStatusTest`: +4 tests (adjusted-spend populated; adjusted null on offset failure; addBudget rejects NaN/Infinity/inverted thresholds; **budget actuals use period-end as-of rate** for a USD bucket). Existing 2 tests unchanged (EUR aggregate path — as-of short-circuits home currency).
- `SynthesisEngineBlockPartyPaidExclusionTest` (new): asserts PAID occurrence is excluded from block-party recurring impact while PLANNED is included.

### MultiCurrencyRepository additions
- `getHomeCurrencyPurchaseTotalAsOf(start, end, asOfMillis)` and `getHomeCurrencyPurchaseCategoryTotalsAsOf(start, end, asOfMillis)` — bounded grouped-by-currency aggregates converted at `RateBasis.PERIOD_END` via `MoneyAggregateBuilder.fromBuckets(..., BucketDatePolicy.FixedDate)`. No uncapped row scan.

---

## This session (batch 2) — P6 read-path / fail-closed / diagnostics

Self-review: GREEN (reviewer + static tester both passed after one fix loop).
Validation (human must run — NOT run by agent):
- assembleDebug: pending
- testDebugUnitTest: pending
- check: pending (includes verifyDbAccessBoundaries)
- connectedDebugAndroidTest: not required — no schema/migration change this batch (the two new
  domain fields on `DailyCashFlow` are in-memory model fields, not Room columns). NOTE: scoped to
  batch 2 only; the later GATED batch 3 DOES bump the Room schema 141→142 and DOES require
  `connectedDebugAndroidTest` — see "This session (batch 3 — GATED / schema-bumping)" below.

### Issues fixed this batch
| ID | Title | Status after change |
|----|-------|---------------------|
| P6-CURRENT-025 | `ForecastInputAssembler` falls back to `"EUR"` on home-currency failure | FIXED — `assemble` resolves home currency via `currencySettingsRepository.resolveHomeCurrency()` and throws `IllegalStateException` on `HomeCurrencyResolution.Failed` (fail-closed). `FinancialWeatherRepository` absorbs via its terminal `.catch` → `WeatherState.UNKNOWN`; `CalculateFinancialForecastUseCase` propagates. **Contract:** forecast fails closed rather than emitting wrong-currency data. |
| P6-CURRENT-024 | Forecast/cashflow/stress READ paths perform materialization writes | FIXED — new pure read-only `RecurringLifecycleCoordinator.projectOccurrences(ruleId, start, end)` (no writeBarrier, no materialize, no DAO insert/update, no event write) reuses the same expand→resolve logic as the write path. The three read callers (`ForecastInputAssembler`, `CashFlowCalculator`, `FinancialStressForecastEngine`) use it and read existing materialized rows via `recurringOccurrenceDao` guarded by `DatabaseReadBarrier.checkReadAllowed`, merging by `occurrenceKey` so materialized overrides (SKIPPED/CANCELLED) win. **Invariant:** materialization stays owned by write-authorized paths (rule create/update, projection service, reconciliation); read paths never write. |
| P6-CURRENT-018 | Cashflow occurrence-gen failure silently drops bills | FIXED — on occurrence-generation failure, falls back to ad-hoc calendar-aware expansion AND surfaces a partial flag. `DailyCashFlow` gained `occurrenceGenerationFailed: Boolean` + `failedOccurrenceRuleCount: Int` (domain model, not a Room entity — no migration); `isPartial` is now also true when generation failed. **Contract:** bills are never silently dropped — a degraded result is flagged, not hidden. |
| P6-CURRENT-019 | Cashflow recurring dedup over-collapses distinct bills | FIXED — dedup is now content-aware: keyed on `MerchantKeyGenerator` + currency + amount tolerance (±10%) within the same day, applied predicted-vs-predicted AND predicted-vs-actual. **Invariant:** distinct same-merchant bills with different amounts on the same day are preserved. |
| P6-CURRENT-026 / 027 | Budget/forecast/planned lifecycle events + BudgetMonitor diagnostic gaps | FIXED — budget CRUD (`BudgetRepository`), planned CRUD (`PlannedExpenseRepository`), and forecast generation (`BudgetForecastingEngine`) now emit durable diagnostic events; `BudgetMonitor` emits `STATUS_SKIPPED`/`NO_SPEND_OR_LIMIT` on the zero-spend/zero-limit early return and a `grossFallback=true` flag on `STATUS_COMPUTED` when `adjustedSpendBreakdown` is null. All emissions REUSE existing infra (`DiagnosticEventWriter`, `AppPipeline.BUDGET`, `SafeEventMetadata`, existing `EventOutcome`/`EventSeverity`) — NO new taxonomy, enum, table, or schema. **Contract:** all best-effort — write-barrier-guarded, `CancellationException` rethrown, `DatabaseAccessBlockedException` routed to the diagnostic sink, and a diagnostic failure never fails or rolls back the mutation. Metadata carries only ids/amounts/percent/flags (no PII). |

### Reclassified follow-ups (confirmed already FIXED at HEAD `afdf86b2` — dropped from open list)
| ID | Title | Evidence |
|----|-------|----------|
| P6-P1-03 | Budget/forecast/planned writes lack restore guard | Write-barrier guards present across `BudgetRepository` add/update/delete/toggle/deleteAll/restore + notification toggles, `BudgetForecastingEngine.generateForecastResult`/`updateForecastAccuracy`, and `PlannedExpenseRepository` add/delete. No unguarded budget/forecast/planned write remains. |
| P6-P1-04 | Budget alerts use gross `percentUsed` when adjusted spend exists | (already FIXED by P6-CURRENT-002) `BudgetMonitor` reads `adjustedSpendBreakdown?.effectiveSpend` and recomputes `adjustedPercent` for all thresholds; gross is only a logged fallback on offset-engine failure. |
| P6-P1-05 | Rollover ignores partial conversion state from prior periods | `BudgetRepository.createBudgetStatus` rollover loop ORs `periodAggregate.isPartial` and merges warnings into `BudgetStatus.isPartial`/`conversionWarning`; each completed period converts at its own period-end. |
| P6-P1-10 | Recurring occurrence status lost before forecast | `ForecastInputAssembler.assemble` filters materialized occurrences to `status == "PLANNED"`, carries status into `ConfirmedOccurrence`, normalizes amount to home currency. |

### Tests added/updated
- New tests cover: fail-closed home-currency resolution (P6-CURRENT-025), read-only `projectOccurrences` (no write side effects — P6-CURRENT-024), cashflow gen-failure fallback + partial flag (P6-CURRENT-018), content-aware dedup preserving distinct amounts (P6-CURRENT-019), and budget/planned/forecast/monitor diagnostic emissions (P6-CURRENT-026/027).
- Test-compile-fix loop: two test files carrying merge artifacts were repaired (not rewritten), and one test file that had been deleted was restored (not left deleted). No test was `@Ignore`d or had its assertions weakened to pass.

### Known compile-risk areas (agent could not compile)
- The three read-path classes (`ForecastInputAssembler`, `CashFlowCalculator`, `FinancialStressForecastEngine`) gained a `DatabaseReadBarrier` constructor param (Hilt-wired; test construction sites updated). Verify `assembleDebug` (KSP/Hilt), not just Kotlin compile.
- Budget/planned/forecast classes (`BudgetRepository`, `PlannedExpenseRepository`, `BudgetForecastingEngine`, `BudgetMonitor`) gained trailing nullable-defaulted `DiagnosticEventWriter?` / `MaintenanceSafeDiagnosticSink?` params — existing construction sites compile via the defaults; new emissions are no-ops when the dependencies are absent.

---

## This session (batch 3 — GATED / schema-bumping) — forecast persistence, FK cascade, cashflow MoneyAmount, CI gate

Self-review: GREEN (all six items + the P6-CURRENT-014 residual reviewed; code is correct).
Branch: `master-refactor` at HEAD.

> **⚠ THIS BATCH BUMPS THE ROOM SCHEMA (141 → 142).** Unlike batches 1 and 2 (which were
> code-only, in-memory model changes), this batch adds Room columns and changes a foreign-key
> action, so it carries a real migration and a hand-authored schema snapshot. The validation
> requirements below DIFFER from the earlier batches — see **Validation** and **SCHEMA CHANGE**.

### Issues fixed this batch
| ID | Title | Status after change — one-line evidence |
|----|-------|------------------------------------------|
| P6-NEW-01 | CI does not run boundary/build checks | FIXED — `.github/workflows/ci.yml` `lint-and-check` job now runs `:app:assembleDebug` + `:app:verifyDbAccessBoundaries` (did NOT switch to full `:app:check`); the instrumented-tests job stays `continue-on-error` / non-blocking. |
| P6-CURRENT-020 | Cashflow `startingBalance` is a raw `Double` | FIXED — `CashFlowCalculator.calculateDailyCashFlow` `startingBalance` is now a required `MoneyAmount`; a currency mismatch vs the resolved home currency is rejected with `IllegalArgumentException` (no auto-conversion, no EUR-baked default); `CashFlowCalendarViewModel` passes `moneyStartingBalance` and routes the throw into its existing error state. |
| P6-CURRENT-008 | Forecast insert conflict `REPLACE` overwrites history | FIXED — `BudgetForecastDao.insert` conflict strategy `REPLACE → ABORT`. Does NOT re-break P6-P1-01: the UNIQUE index is `(budgetId, targetPeriodStart, forecastDate)` so a refresh (new `forecastDate`) never collides, and `insertWithDeactivation` (deactivate-then-insert in one `@Transaction`) stays the sole production insert path, preserving history. A typed `ForecastInsertResult.DuplicateInSameInstant` (catches ONLY `SQLiteConstraintException`, propagates all else) maps a same-millisecond duplicate to the existing Unavailable/skip diagnostic instead of crashing or overwriting. |
| P6-CURRENT-010 / P6-CURRENT-015 | Forecast data-quality + `FinancialForecast` quality fields | FIXED — `BudgetForecast` entity gained 4 columns (`isPartial`, `excludedExpenseCount`, `qualityWarningsJson`, `rateBasis`); `BudgetForecastingEngine` populates them and reduces confidence by a deterministic factor clamped to `[0,1]` when historical expenses are FX-excluded; `FinancialForecast` gained `isPartial` / `qualityWarnings` / `excludedCount`, and `SynthesisEngine` copies conversion warnings through. |
| P6-CURRENT-005 / P6-P1-15 | Deleting a budget can fail after forecasts exist | FIXED — `budget_forecasts.budgetId → budgets(id)` FK relaxed `RESTRICT → CASCADE` (human chose Option A = CASCADE-purge). `BudgetRepository.deleteBudget` keeps an explicit forecast-delete belt-and-suspenders inside its existing write-barrier-guarded `withTransaction`; `deleteAll` and `restoreDebugSnapshot` now succeed with forecasts present; `restoreDebugSnapshot` stays `BuildConfig.DEBUG`-only + write-barrier-guarded. |
| P6-CURRENT-014 (residual) | Block-party recurring-by-day still includes PAID | FIXED (residual completion) — `SynthesisEngine.buildRecurringByDayFromOccurrences` now filters PLANNED-only (was incorrectly including PAID). Completes the P6-CURRENT-014 fix begun in batch 1. |

### SCHEMA CHANGE — read before validating
- **Room schema bumped 141 → 142.** `MIGRATION_141_142` added to `AppDatabase` and registered in `ALL_MIGRATIONS`. It is a table-recreate (CASCADE FK + the 4 new `budget_forecasts` columns, explicit-column `INSERT ... SELECT`, indices recreated with their exact names). Existing forecast rows are preserved; new columns are defaulted.
- **The hand-authored `app/schemas/.../142.json` currently has an identityHash that is a STALE COPY of 141's.** The human **MUST regenerate `142.json` via the build (KSP) — `:app:assembleDebug` regenerates it — and re-commit the corrected identityHash**, otherwise `verifyRoomSchemaSnapshots` / Room's runtime identity check will fail.
- **`connectedDebugAndroidTest` IS REQUIRED for this batch** (`MigrationContractTest` 141→142 + `FreshInstallIndexParityTest` must run on a device/emulator). This is the key difference from the prior code-only batches.

### Validation (human must run — NOT run by agent)
- `:app:assembleDebug` — **REQUIRED** (regenerates `142.json` identityHash via KSP; also resolves Hilt for the new nullable diagnostic ctor params). Pending.
- `:app:testDebugUnitTest` — pending (esp. `BudgetForecastingEngineTest`, `SynthesisEngineTest`, `SynthesisEngineBlockPartyPaidExclusionTest`, `CashFlowCalculatorTest`, `CashFlowCalendarViewModelTest`, `BudgetRepositoryHistoricalStatusTest`).
- `:app:verifyDbAccessBoundaries` / `verifyRoomSchemaSnapshots` — **REQUIRED** (schema-snapshot identity check; will FAIL until `142.json` is regenerated and re-committed). Pending.
- `:app:connectedDebugAndroidTest` — **REQUIRED THIS BATCH** (schema/migration change): runs `MigrationContractTest` 141→142 + `FreshInstallIndexParityTest` on a device/emulator. **This differs from batches 1 and 2, which were code-only and did NOT require it.**

### Tests added/updated
- `BudgetForecastingEngineTest`: refresh-keeps-history-one-active; same-ms-returns-conflict-not-replace; non-constraint-exception-propagates; confidence-reduced-on-exclusion.
- `SynthesisEngineTest`: `financial_forecast_contains_currency_conversion_warnings`. New `SynthesisEngineBlockPartyPaidExclusionTest`: PAID excluded from recurring impact (covers the P6-CURRENT-014 residual).
- `CashFlowCalculatorTest` / `CashFlowCalendarViewModelTest`: `MoneyAmount` call sites + currency-mismatch-rejected.
- `MigrationContractTest` (androidTest): `migrate_141_to_142` preserves forecasts / adds quality columns with defaults / relaxes FK to cascade. `MigrationRegistrationTest`: 142 added to validate the chain.
- `BudgetRepositoryHistoricalStatusTest`: delete-purges-forecasts (order), `deleteAll` succeeds, restore stays write-barrier-guarded.

### Verify-only items for the handoff (not blockers)
- **VM cold-start:** if the home-currency flow emits async, the first cashflow load may transiently show an error state until reload (no crash). Optional future polish.
- **`FreshInstallIndexParityTest`** asserts 3 `budget_forecasts` indices (the UNIQUE composite is separate) — unchanged by this batch; confirm on device.
- **New nullable diagnostic ctor params** on `BudgetRepository` / `BudgetForecastingEngine` rely on the existing Hilt `DiagnosticsModule` bindings; confirm via `:app:assembleDebug`.

---

## Pipeline 7 — Backup / Restore / Maintenance Mode / Startup Recovery

Audit: done (reconciled stale audit YAML pinned to `4113e38f` against working tree at HEAD `afdf86b2`)
Implementation: 1 slice (S1) applied. The approved plan (PR1–PR4) and most deeper
P7-CURRENT issues were **already implemented** in the working tree before this session;
verified closed with code evidence (see below). One genuine in-scope P0 gap remained open
(P7-CURRENT-003) and was fixed this slice.
Validation (human must run — NOT run by agent):
- assembleDebug: pending
- testDebugUnitTest: pending
- check: pending
- connectedDebugAndroidTest: not required (no schema/migration change in this batch)

### Already-closed before this session (verified, not re-implemented)
| ID | Title | Evidence |
|----|-------|----------|
| PR1 / P7-P0-01 | Legacy `.db` import disabled in release | `DatabaseBackupRepositoryImpl.kt:1275` `if (!BuildConfig.DEBUG) return failure(...)` |
| PR2 / P7-CURRENT-006/007 | Writes blocked during snapshot | `RestoreMaintenanceMode.isWritesAllowed()` returns true only for `NORMAL` (`:84`) |
| PR3 / P7-CURRENT-018 | Worker resume spec-driven + parity guard | `WorkerRegistry.scheduleAll()`; `WorkerContractTest.kt:76` asserts `entries == DEFAULTS.keys` |
| PR4 / P7-CURRENT-009 | Asset restore journal states + ledger | `RestoreJournal.JournalState.ASSETS_RESTORING` + `assetTasks` per-asset ledger |
| P7-CURRENT-001 | Restore deletes old live WAL/SHM sidecars | `DatabaseBackupRepositoryImpl.kt:866-870` deletes liveWal/liveShm before staged copy |
| P7-CURRENT-002 | Rollback failure → critical mode (in-process) | `:991` `enterCriticalRecoveryRequired(...)` instead of `exit(false)` |
| P7-CURRENT-005 | Fresh Room after swap | `restoreDatabaseOpener.openFreshDatabase()` used for live verify + asset repair (`:898`) |

### Issue fixed this slice
| ID | Title | Status after change |
|----|-------|---------------------|
| P7-CURRENT-003 (a.k.a. P7-P0-02 cross-restart) | Startup crash recovery not fail-closed across the *next* restart | FIXED — see below |

**Root cause:** `AppStartupCoordinator.checkRestoreJournal()` had two defects:
- Defect A: on `!recovered` it entered `RESTORE_COMPLETE_RESTART_REQUIRED` (the *success*
  "please restart" mode), not the persistent `CRITICAL_RECOVERY_REQUIRED`.
- Defect B: the final startup auto-reset block reset **any** non-NORMAL mode (including
  `CRITICAL_RECOVERY_REQUIRED`) to `NORMAL`. Because `failJournal()` renames the active
  journal to the failure file, the *second* restart sees `NoAction` and the reset block
  silently unblocked writes against a possibly-corrupt DB. This also undermined the
  cross-restart persistence of the critical mode set by `restoreCostBackup` rollback
  failure (P7-CURRENT-002).

**Fix:**
- `!recovered` branch now calls `enterCriticalRecoveryRequired(reason)`.
- The startup auto-reset explicitly **exempts** `CRITICAL_RECOVERY_REQUIRED`
  (transient modes like `RESTORE_COMPLETE_RESTART_REQUIRED` still reset to `NORMAL`).
- `checkRestoreJournal()` made `@VisibleForTesting internal` so the contract is testable.

### Files changed
- `app/src/main/java/.../startup/AppStartupCoordinator.kt` — fail-closed fix (Defect A+B) + testability.
- `app/src/test/java/.../startup/AppStartupCoordinatorRecoveryTest.kt` (new) — 3 tests.
- `docs/backup-restore-barrier-contract.md` — documented cross-restart fail-closed.
- `docs/architecture/LEGAL_PATHS.md` — corrected restore fail-closed lines.
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md` — corrected P7-P0-02 summary.

### Tests added
- `AppStartupCoordinatorRecoveryTest` (Robolectric, real `RestoreJournal` + `RestoreMaintenanceMode`):
  1. `failed crash recovery enters CRITICAL_RECOVERY_REQUIRED and blocks writes`
  2. `CRITICAL_RECOVERY_REQUIRED survives the next restart with writes still blocked` (cross-restart)
  3. `successful restart-required mode IS reset to NORMAL on a clean restart` (regression guard)

  Tests use the real lifecycle path (mode persisted in SharedPreferences, journal on disk;
  fresh instances simulate process restart) — no DAO bypass, no `@Ignore`, no weakened asserts.

### Self-review verdict
GREEN for the in-scope slice. P7-CURRENT-003 closed with evidence + cross-restart test;
PR1–PR4 verified already-closed; no regressions introduced; docs updated.

### Batch PR-B (this session, follow-up to S1) — low-risk hardening, no schema/build-config change
| ID | Title | Status after change |
|----|-------|---------------------|
| P7-CURRENT-014 | `verifyQuick` skips Tier 1 tables missing from manifest before swap | FIXED — `BackupVerifier.validateManifestCompleteness()` rejects incomplete manifests BEFORE the destructive swap; wired into `restoreCostBackup()` right after the "no data" check |
| P7-CURRENT-015 | Backup manifest count creation swallows count errors as 0 | FIXED — `BackupVerifier.collectTableCountsStrict()` throws `RequiredTableCountException` on a Tier 1/Tier 2 count failure; `createCostBackup()` aborts instead of writing a misleading manifest |
| P7-CURRENT-017 | Restore URI copy has no early size/header precheck | FIXED — `BackupRestoreViewModel.restoreBackup()` rejects oversized URIs via `OpenableColumns.SIZE`, validates COSTBACKUP header magic, hard-caps the copy at `MAX_BACKUP_BUNDLE_BYTES` (500 MB) via `copyBackupWithPreflight()` |
| P7-CURRENT-022 | RestoreJournal write not fully crash-durable | FIXED — `RestoreJournal.writeTextSynced()` fsyncs the temp file (`fd.sync()`) before the atomic rename; used by `writeJournal()` and the event append path |
| P7-CURRENT-023 | Bundle extraction lacks decompressed-size/entry-count limits | FIXED — `CostbackupBundle.extract()` enforces `ExtractionLimits` (total bytes, per-entry bytes, entry count) with bounded `copyBounded()` that measures actual streamed bytes (ZipEntry.size untrusted); throws `BackupTooLargeException` |

**PR-B files changed (prod):**
- `data/backup/BackupVerifier.kt` — `+IncompleteManifestException`, `+RequiredTableCountException`, `+requiredManifestTables()`, `+validateManifestCompleteness()`, `+collectTableCountsStrict()`.
- `data/repository/DatabaseBackupRepositoryImpl.kt` — wire 014 (pre-swap completeness) + 015 (strict counts).
- `data/backup/CostbackupBundle.kt` — `+ExtractionLimits`, `+BackupTooLargeException`, `+copyBounded()`, `extract()` limits param, `HEADER_SIZE` made public.
- `data/backup/RestoreJournal.kt` — `+writeTextSynced()` fsync on journal + event temp writes.
- `ui/screens/backup/BackupRestoreViewModel.kt` — `+queryUriSize()`, `+copyBackupWithPreflight()` (+test overload), `+MAX_BACKUP_BUNDLE_BYTES`.

**PR-B tests added/updated:**
- `BackupVerifierManifestTest` (new, Robolectric): required-table set; completeness pass/throw/lists-all; strict-collector throws on missing required tables.
- `CostbackupBundleLimitsTest` (new, pure JVM + TemporaryFolder): within-limits success; per-entry cap; total cap; entry-count cap; default-limit sanity.
- `RestoreJournalDurabilityTest` (new, Robolectric): state+safety-path round-trip after fsync'd write; events survive transitions.
- `BackupRestoreViewModelTest` (updated): 2 existing restore tests now use real COSTBACKUP byte streams (relaxed mock would spin on the new header read); +4 preflight tests (valid header copies body; wrong magic; too-short; over-cap).

**PR-B compile-risk notes:**
- `extract()` gained a defaulted `limits` param → existing callers unaffected (`restoreCostBackup` uses default).
- `CostbackupBundle.HEADER_SIZE` visibility `private`→public `const`; `readHeader()` already public.
- `BackupRestoreViewModel` gained a `companion object` + `OpenableColumns`/`VisibleForTesting`/`InputStream` imports.

### Batch PR-C (this session) — restart-required lock hardening
| ID | Title | Status after change |
|----|-------|---------------------|
| P7-CURRENT-019 | Restart-required UX not globally enforced; `dismissRestartRequired()` could hide the banner | FIXED — `dismissRestartRequired()` is now a **no-op**. Verified the authoritative lock is `MainActivity` observing persisted `operationalStateFlow` (full-screen lock + `return` before app content on `RestartRequiredAfterRestore`), independent of the screen-local `restartRequired` flag. The deprecated dismiss only fed a redundant banner and had **zero production callers**. |

**PR-C files changed:**
- `ui/screens/backup/BackupRestoreViewModel.kt` — `dismissRestartRequired()` body removed (no-op), KDoc + `@Deprecated` message updated.
- `app/src/test/.../BackupRestoreViewModelTest.kt` — `dismissRestartRequired resets restart flag` rewritten to `dismissRestartRequired does NOT clear the restart-required flag` (asserts no-op contract).
- `docs/backup-restore-barrier-contract.md` — documented the global non-dismissible lock contract.

**PR-C compile-risk notes:** trivial — method body removed, no signature/DI change, no other prod caller.

### Batch PR-D (this session) — diagnostics ledger import + destructive-path lock-in
| ID | Title | Status after change |
|----|-------|---------------------|
| P7-CURRENT-016 | Backup/restore operation ledger is logs + on-disk journal only; not queryable | FIXED (no migration) — `RestoreJournalImporter.importLastSuccessJournalIfPresent()` was **dead code (never called)**; added a symmetric `importLastFailureJournalIfPresent()` and wired **both** into `AppStartupCoordinator.importRestoreJournals()` (async, gated on writes-allowed). Restore/reset success **and** failure trails are now ingested into the queryable `OperationRun`/`OperationRunEvent` ledger on the next healthy startup. Idempotent per event. |
| P7-CURRENT-020 | `resetDatabase()` destructive but not journaled/maintenance-guarded | VERIFIED ALREADY-DONE + locked in — `resetDatabase()` already enters `RESETTING_DATABASE` via `enterAndDrain` and creates a `RestoreJournal` (audit YAML claim was stale). Added `BackupRestoreArchitectureGuardTest` to fail if either wrapper regresses. Typed-confirmation token remains out of scope (held). |
| P7-CURRENT-021 | Raw `exportDatabase()` reachable as interface API | LOCKED IN (scope reduced) — runtime release guard already blocks it; added `BackupRestoreArchitectureGuardTest` asserting no production UI references `exportDatabase()` (debug screen exempt). Source-set move deemed low-value over the guard and **not** done. |

**PR-D files changed (prod):**
- `data/backup/RestoreJournal.kt` — `FAILURE_JOURNAL_FILENAME` made public; `+readFailureJournal()`, `+getFailureJournalEvents()`, `+markFailureJournalImported()`, `+isFailureJournalImported()` (symmetric to the success APIs).
- `data/backup/RestoreJournalImporter.kt` — `+importLastFailureJournalIfPresent()` (idempotent, terminal-status run row + per-event ingest).
- `startup/AppStartupCoordinator.kt` — `+restoreJournalImporter` ctor param (Hilt `@Inject`, no module change); `+importRestoreJournals()` called in the writes-allowed startup branch.

**PR-D files changed (test):**
- `app/src/test/.../startup/AppStartupCoordinatorRecoveryTest.kt` — `newCoordinator()` passes the new mocked `restoreJournalImporter` param.
- `app/src/test/.../data/backup/RestoreJournalImporterFailureTest.kt` (new, Robolectric): failure-journal import into the ledger; idempotency across restarts; no-journal no-op. Real `RestoreJournal` + mocked DAOs.
- `app/src/test/.../architecture/BackupRestoreArchitectureGuardTest.kt` (new): 020 reset wrappers present; 021 no production-UI raw export + debug caller still exists. Robust source-root resolution, fail-on-empty-scan.

**PR-D compile-risk notes:**
- `AppStartupCoordinator` ctor gained `RestoreJournalImporter` (`@Singleton @Inject`) — Hilt resolves it with no module change; the only two construction sites are tests (both updated).
- New failure APIs mirror existing success APIs on the same file; no signature changes to existing methods.

### Assessment — P7-CURRENT-010/011/012 (read-only, NO edits made)
Inspected the **uncommitted** `ExportAnonymizer.kt` diff in the working tree (other session):
it already expands redaction to exactly the four PII tables P7-CURRENT-011 flagged —
`ai_artifacts`, `ai_chat_messages`, `merchant_locations`, `email_receipt_sources` — in a single
transaction, schema-guarded (`tableExists`), preserving dedup hashes. **011 is substantially
covered by that in-flight work**, so I did not touch the file (would collide). Remaining:
- **010** (BackupPrivacyMode "redacted + images" semantics / manifest `requested` vs `actual`
  fields) — not addressed by the in-flight diff; still open.
- **012** (privacy_audit_events preserve-vs-exclude policy) — already Tier 1 exact per Pipeline 8
  verification; the explicit manifest-flag policy decision is still open.
These remain HELD pending the other session committing `ExportAnonymizer` (avoid collision).

### Known compile-risk areas (agent could not compile)
- `AppStartupCoordinator.kt`: `checkRestoreJournal()` visibility `private` → `internal` +
  `@VisibleForTesting`. Sole prod caller is `initialize()` (same class); test calls it directly.
- New test references existing public APIs only (`RestoreJournal`, `RestoreMaintenanceMode`,
  `RestoreDatabaseOpener`, `WorkManagerTestInitHelper`, MockK). No new constructor params.

### Independent adversarial review (this session) — 2 parallel static reviewers
Two independent reviewers (lifecycle/startup boundary + data-integrity/IO boundary) verified all
eight production fixes (003, 019, 016, 014, 015, 017, 022, 023) as **correct and complete** with
file:line evidence. Both converged on a **single blocking defect** and two low-severity findings,
all now fixed:
- BLOCKER (fixed): `BackupRestoreViewModelTest.kt` used `assertThrows` without importing it →
  would fail `compileDebugUnitTestKotlin` for the WHOLE test source set (blocking all 5 P7 test
  classes). Added `import org.junit.Assert.assertThrows`.
- Low (fixed): restore `onFailure` now maps `CostbackupBundle.BackupTooLargeException` to a clear
  "too large / too many entries" message (was generic "Restore failed"); +1 ViewModel test.
- Low (fixed): corrected stale `BackupVerifier` tier-count comments (37 Tier-1 / 10 Tier-2 / 10
  Tier-3 = 57). Comment-only — map left intact to avoid a key-dup risk I can't compile-check.
- Verified directly: only `ui/screens/debug/**` calls `exportDatabase()` → 021 guard will pass.
Post-fix verdict: both boundaries GREEN pending the human compile/test run.

### Open / deferred (NOT done — need decision; held for discussion)
- P7-CURRENT-010: BackupPrivacyMode "redacted DB + images" semantics + manifest
  requested-vs-actual image fields (not covered by in-flight ExportAnonymizer work).
- P7-CURRENT-012: privacy_audit_events explicit preserve/exclude manifest-flag policy
  (table is already Tier 1 exact; remaining work is the documented policy decision).
- P7-CURRENT-011: covered by in-flight `ExportAnonymizer` change — HELD only to avoid
  colliding with that uncommitted work; re-verify once it is committed.
- P7-CURRENT-013: semantic restore-equivalence golden suite (large instrumentation-test effort).
- P7-CURRENT-020 (token): typed-confirmation token for `resetDatabase()` — public-interface +
  caller-contract change; held as a separate slice (crash-safety half already verified done).
- P7-CURRENT-021 (source-set): physically moving raw export to a debug-only source set —
  low value over the static guard now in place; not planned.
- P7-CURRENT-020: journaled/maintenance-guarded `resetDatabase()` with typed-confirmation token
  (behavioral change to a destructive path; touches lifecycle coordinator).
- P7-CURRENT-021: move raw `exportDatabase()` to a debug-only source set (**build-config / source-set change**).

---

## Pipeline 8 — Privacy / AI / Redaction

Audit basis: `pipeline8_implementation_plan.md`, `pipeline8_evaluation.md`, two static debug
reports. Verified against actual code at HEAD `afdf86b2` (branch `master-refactor`).
Mode: static only — agent did NOT compile/build/run Gradle or tests.

### Scope finding (important)
The four Pipeline 8 source documents were authored against OLDER commits (`c424274`,
`d915b10c`, `4113e38f`, `b6abe0a`) and **contradict each other**. A read-only scout verified
the live code at HEAD `afdf86b2` and found Pipeline 8 is **~90% already implemented**: the two
debug reports claiming multiple open P0 privacy bugs are **stale**. The later evaluation
("no P0 issues remain") is closest to reality.

Verified ALREADY-FIXED at HEAD (NOT changed this session — evidence by file:line in scout report):
- DO_NOT_STORE exhaustive handling + ephemeral/stored notification split (`NotificationCaptureService`)
- Bank-statement OCR sanitized via `RawContentSanitizer` (`BankStatementLifecycleProcessor`)
- DataStore corruption → `FAIL_CLOSED_DEFAULTS` (`PrivacySettingsRepositoryImpl` / `PrivacySettings`)
- `CompositePrivacyGate` fails closed for gate-handled capabilities
- `DataRetentionWorker` expanded via retention registry (notifications, OCR, email, AI chat/artifacts)
- `CloudPayloadPurpose.BANK_STATEMENT_VALIDATION` + `prepareBankStatementValidation`
- `BackupPrivacyGate` no longer owns/allows `RAWBACKUP_EXPORT`; `PrivacyGate` KDoc corrected to NotApplicable
- `emailReceiptStorageMode` present, persisted, loaded, and enforced; raw `emailMessageId` only in STORE_RAW

### Issues actually fixed this session
| ID | Title | Status after change |
|----|-------|---------------------|
| P8-CURRENT-014 / P8-NEW-04 | Allow-all / fail-open test constructors in main source diverged from siblings | FIXED — `CloudCategorizationAssistService` 2× `@VisibleForTesting` ctors now return `PrivacyDecision.FailClosed` (were `Allowed`); `CloudQueryInterpretationService` 2× ctors now pass `PrivacyCapabilityHandlingPolicy.gateHandledCapabilities` to `CompositePrivacyGate` (was fail-open `emptyList(), NO_OP`). All 8 cloud-service test ctors now fail-closed. |
| P8-CURRENT-001 / P8-NEW-01 | `PrivacySettings.redactBeforeCloud` not authoritative for receipt-assist / item-categorization build-time redaction | FIXED — `AiPolicyImpl.shouldRedact` returns only `AiSettings.redactBeforeCloud`; both input builders now OR in `privacySettingsRepository.getSettings().redactBeforeCloud`, so privacy-required redaction hashes merchant/item/category labels even when AiSettings disagrees. Defense-in-depth `prepareText` (effective OR policy) unchanged. |

### Static guard hardening (regression lock-in)
- `PrivacyGuardTest` G4b (`privacy_guard_no_allow_all_gate_even_in_test_constructors`):
  flags any `object : PrivacyGate` in MAIN source returning `PrivacyDecision.Allowed`, even in
  secondary constructors (the prior G4 exempted those).
- `PrivacyGuardTest` G4c (`privacy_guard_no_composite_gate_without_handled_capabilities`):
  flags `CompositePrivacyGate(emptyList(), ...)` calls in MAIN source missing
  `gateHandledCapabilities` (which fail open for sensitive capabilities).
- Both scan `app/src/main/java` only — test-local Allowed gates are intentional and permitted.

### Tests added/updated
- `PrivacyGuardTest`: +G4b, +G4c, +`balancedCall` helper. Existing guards untouched.
- `InputBuilderRedactionPolicyTest` (new): real `AiPolicyImpl` + fake privacy repo; asserts
  privacy=true/ai=false → `redactBeforeCloud=true` and merchant/category labels hashed
  (`merchant_…`/`cat_…`); both-false control → raw.
- `ReceiptItemCategorizationInputBuilderTest`, `ReceiptAssistInputBuilderTest`: pass new
  `PrivacySettingsRepository` ctor param; assist tests wrapped in `runTest` (build() now suspend).
- `SuggestReceiptExtractionUseCaseTest`: 8 `every { inputBuilder.build }` → `coEvery` (build suspend).
- `CloudCategorizationAssistServiceTest` (B1 fix): 9 HTTP tests rerouted through primary ctor
  + test-local Allowed gate (`serviceWithAllowedGate`); added fail-closed lock-in test proving
  the 2-arg ctor returns null with a present key.

### Self-review verdict: GREEN (reviewer + static tester)

### Files changed (this session, Pipeline-8-attributable)
Main source:
- `data/ai/provider/CloudCategorizationAssistService.kt` (restored to fail-closed test ctors)
- `data/ai/provider/CloudQueryInterpretationService.kt`
- `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `domain/ai/usecase/ReceiptAssistInputBuilder.kt`
Test source:
- `domain/privacy/PrivacyGuardTest.kt`
- `domain/ai/usecase/InputBuilderRedactionPolicyTest.kt` (new)
- `domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt`
- `domain/ai/usecase/ReceiptAssistInputBuilderTest.kt`
- `domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt`
- `data/ai/provider/CloudCategorizationAssistServiceTest.kt`

### Known compile-risk areas (agent could not compile)
- `ReceiptAssistInputBuilder.build` changed to `suspend`; sole main caller
  (`SuggestReceiptExtractionUseCase.invoke`) is already suspend. Verify no other caller.
- New `PrivacySettingsRepository` ctor param on both builders relies on existing Hilt binding
  (`PrivacyModule.bindPrivacySettingsRepository`) — verify `assembleDebug` (KSP/Hilt), not just Kotlin compile.
- `SuggestReceiptExtractionUseCaseTest` has a PRE-EXISTING `aiCapabilityRouter.decide` 2-arg/3-arg
  stub vs call shape (present at HEAD, not introduced here); benign per review.

### Validation (human must run — NOT run by agent)
- `:app:assembleDebug` — pending (REQUIRED: Hilt binding + suspend builder)
- `:app:testDebugUnitTest` — pending (esp. `*PrivacyGuardTest`, `*InputBuilderRedactionPolicyTest`,
  `*CloudCategorizationAssistServiceTest`, `*SuggestReceiptExtractionUseCaseTest`)
- `:app:check` — pending
- `:app:connectedDebugAndroidTest` — NOT required (no schema/migration change in this batch)

### Recommended shared-doc edits (left for human to confirm — not mass-edited by agent)
- Master tracker `PIPELINE_ISSUES_MASTER_TRACKER.md` P8 rows are stale ("TODO ONLY" for
  items verified FIXED at HEAD). At minimum, the redaction-authority slice of P8-P1-02 and the
  fail-open-test-ctor item (P8-NEW-04) are now FIXED. A broader reconciliation pass is advisable
  but exceeds this session's two slices.

### Working-tree note
This session's Pipeline 8 edits sit alongside UNRELATED uncommitted Pipeline 6/5 changes already
present in the working tree (`BudgetRepository.kt`, `BudgetMonitor.kt`, `MultiCurrencyRepository.kt`,
budget tests, etc.). Commit the Pipeline 8 files separately to keep attribution clean.

---

## Pipeline 9 — Workers / Background Jobs

Audit: `docs/analyses and debug master/new debugging session/pipeline9_static_debug_report_b6abe0a (1).md` (written against old commit `b6abe0a`; reconciled against current HEAD before planning — most of the audit's 10-PR plan was ALREADY implemented; only the genuine remaining gaps were worked).
Mode: static only — agent did NOT compile/build/run Gradle or tests.
Implementation: slices S1–S9 + follow-up P9-NEW-13 applied. Self-review verdict: **GREEN**.
Validation (human must run — NOT run by agent):
- assembleDebug: pending
- testDebugUnitTest: pending
- check: pending
- connectedDebugAndroidTest: **REQUIRED** (schema change — migration 142→143; see S9)
- **KSP schema export: REQUIRED FIRST** — build must regenerate `app/schemas/.../AppDatabase/143.json` (currently absent); migration-validation tests depend on it.

### Slices implemented this session
| Slice | Issue IDs | Summary | Gate |
|-------|-----------|---------|------|
| S1 | P9-NEW-04 | `WorkerExecutionGuard` enforces `requiresNotificationPermission` via injected `NotificationPermissionChecker`; durable skip `NOTIFICATION_PERMISSION_DENIED`; warranty worker uses it | GREEN |
| S2 | P9-P1-10, P9-NEW-11 | Explicit `WorkerSpec.oneShotPolicy`; `scheduleAtMidnight()` cancels when disabled; merchant_key=REPLACE, ai_daily_briefing=KEEP; version-bump still forces REPLACE | GREEN |
| S3 | P9-P1-04 | Daily-briefing reschedule driven by `WorkerGuardResult`; reschedules on Success + all incidental Skips; only explicit spec-disable (`"Worker disabled by spec"`) stops the chain (guard↔worker literal pinned by a guard-side test) | GREEN |
| S4 | P9-NEW-03 | Location/MerchantKey/DataRetention/ReceiptMatching/DailyBriefing migrated to `runGuardedWithContext`; feed real counts into `BackgroundJobRun` (Bill already did; Warranty stays on `runGuarded`) | GREEN |
| S5 | P9-P1-08 | Durable `ReceiptEvent`s MATCH_ATTEMPTED / MATCH_NOT_FOUND / MATCH_SKIPPED_DOCUMENT_TYPE / AUTO_MATCH_LINK_FAILED via `ReceiptMatchLifecycleService` (barrier + transaction) | GREEN |
| S6 | P9-P1-07, P9-NEW-07 | Atomic per-receipt claim `ScannedReceiptDao.claimForAutoMatch` (conditional UPDATE on UNMATCHED/SUGGESTED) prevents concurrent double-link; `runOnce` keeps KEEP; MATCHING enum deferred. Lease confirmed NOT mutually exclusive | GREEN |
| S7 | P9-P1-11 | `PrivacyRuntimeWorkerPolicy` drives cancel/reschedule (no hardcoded names); background-location no longer cancels merchant_key_backfill; re-enable reschedules; data_retention exempt; uses actual persisted settings | GREEN |
| S8 | (regression-prevention) | `WorkerGuardArchitectureGuardTest` asserts every `CoroutineWorker` uses the guard (allowlist: NotificationIntakeWorker only; SourceLinkBackfillWorker is not a CoroutineWorker) | GREEN |
| S9 | P9-P1-09 | New `WarrantyReminderDelivery` entity+DAO (claim-before-notify, SENT only on DELIVERED, unique key warrantyId+windowDays+expiryDate); migration **142→143** (additive); whole-file backup snapshot + `BackupVerifier` TIER_1; SharedPreferences removed | GREEN (schema; needs human migration test run) |
| P9-NEW-13 | P9-NEW-13 (found in review) | New `RetryableWorkerException` recognized by guard (precedence: Cancellation→Retryable→`classifyTransient`→Failed); Location/MerchantKey "transient/no-progress" throws now retry instead of being misclassified permanent; LocationBackfill no longer burns the permanent attempt budget on a transient throw | GREEN |

### Already-done before this session (verified, NOT re-touched)
P9-P1-01/02/03/05/06 (run logging, shared guard, lease/drain via `MaintenanceOperationRunner`, bill-reminder enable + exactly-once state machine), P9-NEW-01/02/05/06/09/12 (restore-blocked diagnostics, cancellation finalization, `allowDuringBackupExport`, data_retention not over-cancelled, startup stale recovery, typed status+statusReason), PR10 retention registry, Registry↔Spec symmetry test (`WorkerContractTest`).

### SCHEMA CHANGE (S9) — read before validating
- `APP_DATABASE_SCHEMA_VERSION` 142 → **143**. (The 141→142 bump in the working tree is the SIBLING Pipeline-6 `budget_forecasts` migration; S9 correctly stacks as 142→143.)
- `MIGRATION_142_143` is ADDITIVE only (CREATE TABLE `warranty_reminder_deliveries` + unique index `(warrantyId,windowDays,expiryDate)` + FK index on `warrantyId`, FK→`warranties(id)` ON DELETE CASCADE). No DROP/ALTER. `fallbackToDestructiveMigration` stays OFF.
- Reviewer verified the CREATE TABLE byte-matches the entity column-for-column (against sibling `recurring_reminder_deliveries` in 142.json). No SQL DEFAULT clauses (Kotlin defaults don't emit them) — confirmed.
- **`143.json` NOT yet generated** — KSP emits it on build; `MigrationRegistrationTest` 142→143 + `MigrationContractTest` depend on it. Run a build first, then commit the schema file.

### Known compile/build-risk areas (agent could not compile)
- `143.json` schema export missing (see above) — top priority for the human build.
- New Hilt bindings: `NotificationPermissionChecker`→`AndroidNotificationPermissionChecker` (WorkerModule), `WarrantyReminderDeliveryDao` (DaoModule) — needs `assembleDebug`/kapt, not just Kotlin compile.
- S4 test-mock migration: all 5 migrated worker tests now stub `runGuardedWithContext` (strict mock in MerchantKey unit test) — verified migrated; a stale `runGuarded` stub would have failed loudly.
- `WorkerRunContext.rowsSkipped`/`errors` are collected but NOT persisted by the guard's `run.success()` (only rowsScanned/rowsUpdated/notificationsSent). Documented as a follow-up, not a regression.

### Tests added/updated
- New: `WorkerExecutionGuardTest`, `WorkerSpecSchedulerTest`, `WorkerGuardArchitectureGuardTest`, `PrivacyRuntimeWorkerPolicyTest`, `PrivacySettingsRepositoryImplWorkerGatingTest`, `ReceiptMatchLifecycleServiceTest`, `ScannedReceiptClaimTest`, `WarrantyReminderDeliveryDaoTest`, `DataRetentionWorkerTest`.
- Updated: `WorkerIdempotencyTest`, `DailyBriefingWorkerTest`, `LocationBackfillWorkerTest`, `MerchantKeyBackfillWorkerTest` (unit + androidTest), `ReceiptMatchingWorkerTest`, `WarrantyExpirationWorkerTest`, `WorkerLeaseRegistryTest`, `MigrationRegistrationTest`, `BackupRestoreContractTest`.
- Instrumentation (HUMAN/device-run): `MigrationContractTest` 142→143 (table shape, unique-index enforcement, FK cascade + orphan rejection).

### Open follow-ups (NOT blocking; not in this session's scope)
- Persist `rowsSkipped`/`errors` in `BackgroundJobRun` (extend `run.success(...)` + columns).
- `notificationsSent` counts delivery *attempts that returned without throwing*, not confirmed deliveries — gate on a real delivery result (affects DailyBriefing/ReceiptMatching).
- MerchantKey no-progress retry has no cross-run attempt cap (bounded only by WorkManager backoff) — consider a persistent cap like LocationBackfill's.
- Unified `NotificationDeliveryResult` port across workers (P9-NEW-10) and a retention diagnostics target (P9-NEW-08 remainder) remain partial/deferred.
- Pre-existing (untouched, flagged): the `LocationBackfillWorker` resolver-catch budget asymmetry was corrected this session; no other open worker-test contradictions found.

### Working-tree note
Pipeline 9 edits sit alongside UNRELATED uncommitted Pipeline 5/6/7/8 changes already in the working tree. Commit the Pipeline 9 files separately to keep attribution clean. The 142→143 schema bump must be sequenced after the sibling Pipeline-6 141→142 migration (it already is).

---

## Pipeline 10 — Bank Integration / Bank API Sync / Bank Statement Imports

Audit: `pipeline_10_bank_integration_imports_debug_report.yaml` (verdict
`PROTOTYPE_SHELL_NOT_CLEAN`, written against OLD commit `4113e38f`). **Reconciled against
current HEAD `3e426f11` before any implementation** — the audit is heavily stale, matching the
pattern of every prior pipeline. Mode: static only — agent did NOT compile/build/run Gradle or
tests. Implementation: 1 slice (Slice A), code-only, no schema/migration change.
Self-review verdict: **GREEN** (static tester + adversarial reviewer both passed).

Validation (human must run — NOT run by agent):
- assembleDebug: pending
- testDebugUnitTest: pending (esp. `BankApiIntegrationTest`)
- check: pending
- connectedDebugAndroidTest: **NOT required** (no schema/migration change in this batch)

### Reconciliation finding (important — most of the 30 audit issues are already FIXED at HEAD)
The audit's 30 `P10-CURRENT-*` issues were classified against the live code at HEAD. The audit
was authored before large bank-pipeline work landed. Verified **already implemented** at HEAD
(NOT re-touched; evidence by file:line):
| Audit ID | Audit status | ACTUAL at HEAD | Evidence |
|----------|--------------|----------------|----------|
| P10-CURRENT-007 | OPEN (no durable sync ledger) | PARTIAL (generic ledger only) | `BankApiIntegration.syncTransactions` runs inside `OperationRunRecorder.runOperation("BANK_SYNC")` with durable SYNC_STARTED / PAGE_FETCHED / TOKEN_* / TRANSACTION_IMPORTED / TRANSACTION_DUPLICATE_SKIPPED / TRANSACTION_FAILED events + `run.increment(...)` counters; terminal status via `partialSuccess`/`failedFinal`/`success`. **Reviewer note:** the generic `OperationRun` ledger + counters exist, but the audit's fuller ask — a dedicated `BankSyncRun` + per-row `BankTransactionImport` with provider cursor/page checkpoint for crash-resume — is NOT present for API sync. Held with the other feature-scope items. |
| P10-CURRENT-008 | OPEN (connection status never updated by sync) | **STILL OPEN** (corrected by deep review) | Deep review (commit `35f8a37a`) found `syncTransactions` never calls `bankConnectionDao.updateSyncStatus` / `lastError` / `consecutiveErrors` — grep over `BankApiIntegration.kt` for those symbols returns nothing. Previously mis-marked ALREADY-FIXED here; it is genuinely open. Out of the 006/018 slice scope and demo-only (release-blocked), so non-blocking — but tracked honestly as open. Belongs with the `BankSyncCoordinator` finalizer feature work. |
| P10-CURRENT-012 | OPEN (no bank metadata on expenses) | ALREADY-FIXED | `CreateExpenseRequest` has `bankSyncRunId` / `bankProviderTransactionIdHash` / `bankAccountIdHash` (`CreateExpenseRequest.kt:109-113`); populated in `mapTransactionToExpense` (`:357-374`). |
| P10-CURRENT-013 | OPEN (raw description/reference in notes) | ALREADY-FIXED | `mapTransactionToExpense` gates `safeDescription`/`safeReference`/`safeTransferAccountName` on `rawOcrStorageMode` (STORE_RAW / STORE_REDACTED / else→null) (`:335-354`). |
| P10-CURRENT-017 | OPEN (fragile sign-only typing) | ALREADY-FIXED | `BankTransaction.movementType` + `toTransactionType()` maps structured provider movement first; `inferTransactionType` is a fallback only. |
| P10-CURRENT-019 | OPEN (raw IDs/messages in errors) | ALREADY-FIXED | sync errors use `transaction.id.sha256Prefix(8)` hashes and fixed reason codes; no raw description/message. |
| P10-CURRENT-020 / 023 | PARTIAL (statement not atomic/resumable) | ALREADY-FIXED | `BankStatementLifecycleProcessor` writes a `BankStatementImportRun` + per-item `BankStatementImportItem` ledger; each review+item pair wrapped in `database.withTransaction`; resumable item states (CREATED_REVIEW / DUPLICATE_* / FAILED); cancellation finalizes the run with real counts and rethrows. |
| P10-CURRENT-021 | OPEN (PendingReview createdAt=0) | ALREADY-FIXED | statement `PendingReview(... createdAt = timeProvider.now())` (`BankStatementLifecycleProcessor.kt:488`). |
| P10-CURRENT-022 | OPEN (insert result ignored) | ALREADY-FIXED | `require(revId > 0) { "PendingReview insert failed" }` before incrementing (`:496`). |
| P10-CURRENT-024 | OPEN (raw OCR stored) | ALREADY-FIXED | `rawOcrText = RawContentSanitizer.sanitizeRawOcr(ocrResult.fullText, settings.rawOcrStorageMode)` (`:269-272`); `debugData = null` by default (`:608`). |
| P10-CURRENT-001 (safety half) | SAFETY_GUARD_FIXED | ALREADY-FIXED | `requireStubMode()` errors in `!BuildConfig.DEBUG` and requires `BankApiConfig.isStubMode` (`:430-434`). |

### Issues actually fixed this slice (Slice A — genuine, OPEN, code-only, pipeline-local)
| ID | Title | Status after change |
|----|-------|---------------------|
| P10-CURRENT-018 | `CancellationException` swallowed by API sync | FIXED — the per-transaction `catch (e: Exception)` in `BankApiIntegration.syncTransactions` now `if (e is CancellationException) throw e` BEFORE recording a `TRANSACTION_FAILED` event. Cancellation propagates out of the loop → out of `runOperation` (which finalizes the run CANCELLED via its own dedicated `CancellationException` catch) → out of `syncTransactions`, instead of being converted into a `SyncResult` error with continued processing. The sibling `BankStatementLifecycleProcessor` already did this (`:622-641`); this closes the API-sync gap. |
| P10-CURRENT-006 | Provider idempotency key passed but `deduplicationMode` stays STANDARD | FIXED — `mapTransactionToExpense` now sets `deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID`. The coordinator then persists the canonical `idem:BANK_API_SYNC:<providerTxHash>` dedupeKey (via `strictExternalDedupeKey`), so a re-sync of the same provider transaction id resolves to the existing expense even if merchant/description/amount text drifts outside the STANDARD window/tolerance. `idempotencyKey` is always non-blank (`providerTxHash ?: transaction.id`), so the STRICT_EXTERNAL_ID "missing key" validation branch is unreachable. |
| (test debt) | `BankApiIntegrationTest` stale & non-compiling at HEAD | FIXED — the committed test reflected a 2-arg `mapTransactionToExpense` (real signature is 3-arg `+syncRunId` and `suspend`) via `getDeclaredMethod`, so it threw `NoSuchMethodException`; it also asserted `amount == -24.5` while the code returns `abs(amount)`. Rewritten: `mapTransactionToExpense` made `@VisibleForTesting internal`, called directly in `runTest`; assertions corrected to abs(); added STRICT_EXTERNAL_ID + stable-hash-identity coverage (006) and a real cancellation-propagation test (018). |

### Files changed (this slice)
Main source:
- `domain/bank/BankApiIntegration.kt` — (1) rethrow `CancellationException` in per-transaction catch (018); (2) `deduplicationMode = STRICT_EXTERNAL_ID` on the bank import request + `import DeduplicationMode` (006); (3) `mapTransactionToExpense` visibility `private` → `@VisibleForTesting internal` (testability).
Test source:
- `domain/bank/BankApiIntegrationTest.kt` — full repair/rewrite (see above). New in-test `BlockInvokingRecorder` (invokes the operation block with `NoOpOperationRunHandle` so the real sync loop runs); pins `BankApiConfig.isStubMode = true` in setUp to avoid global-state test-order flake.

### Tests added/updated
- `BankApiIntegrationTest` (rewritten): debit→PURCHASE abs(); credit→DEPOSIT abs(); transfer keeps direction + abs(); **STRICT_EXTERNAL_ID + hashed provider identity** (006); **same provider id → stable strict dedupe identity** (006 re-sync contract); **bank sync rethrows cancellation and does not continue importing** (018). No `@Ignore`, no weakened assertions. Uses the real `mapTransactionToExpense`/`syncTransactions` paths (not a DAO bypass) and real `DefaultSensitiveHashingService` (pure JCA, JVM-safe).

### Known compile-risk areas (agent could not compile)
- `mapTransactionToExpense` is now `internal` — the only callers are the prod loop (same class, `:193`) and the same-module test; no other module references it, so `internal` holds.
- New test constructs `BankApiIntegration` with the CURRENT 6-arg constructor; verified it is the only construction site in `app/src`.
- Test relies on `DefaultSensitiveHashingService` being pure JCA (`MessageDigest`/`Mac`) — confirmed; no Android Keystore, deterministic, JVM-unit-test-safe.
- `coordinator.createExpenseStandaloneV2` is `suspend` returning `CreateExpenseResult` — test uses `coEvery { ... } throws`; signature confirmed (`TransactionLifecycleCoordinator.kt:800`).

### Independent deep review (post-commit `35f8a37a`) — verdict GREEN
An independent adversarial reviewer (static only) validated both fixes against HEAD and ran a
fresh regression scan. Result: **GREEN, no blocking issues.**
- **P10-CURRENT-018 — FIXED/correct.** Rethrow is the first statement of the per-tx catch (`:251-256`), before `errors.add`/`TRANSACTION_FAILED`. Propagates through `RoomOperationRunRecorder.runOperation` which finalizes `CANCELLED` under `NonCancellable` and rethrows (`OperationRunRecorder.kt:134-136`). `CancellationException` is a `RuntimeException` so it is caught then rethrown — no leak path.
- **P10-CURRENT-006 — FIXED/correct.** `strictExternalIdentityKey` takes `idempotencyKey?.takeIf{isNotBlank()}` first (`TransactionLifecycleCoordinator.kt:107-111`); `providerTxHash` is non-null/non-blank (`DefaultSensitiveHashingService.hmacSha256Prefix` returns null only for null input; `transaction.id` is non-null), so the `strictKey==null → ValidationFailed` branch (`:512-537`) is unreachable. On STRICT, `expense.dedupeKey = strictKey`, range-check skipped, unique-index relied on; insert conflict resolves via exact `findIdByDedupeKey` → `DuplicateSkipped`. Idempotent re-sync confirmed.
- **Test — sound.** `BlockInvokingRecorder` implements all 3 `OperationRunRecorder` members and invokes the block (real loop runs); `internal` access OK same-module; cancellation test trace verified (stub passes, token-refresh skipped, throw propagates); pure-JVM HMAC assertions safe; no `@Ignore`/weakened/bypass.
- **`abs(amount)` clarification:** reviewer confirmed via `git show 35f8a37a~1` that `abs(...)` and `idempotencyKey = providerTxHash ?: transaction.id` were ALREADY in production before this commit; the commit only ADDED `deduplicationMode`. The "asserted amount==-24.5" note referred to the prior *stale test*, not prior production code.

### Reviewer carry-forward flags (non-blocking; tracked honestly)
- **P10-CURRENT-008 is genuinely STILL OPEN** (status table above corrected): `syncTransactions` never updates `BankConnection.lastSyncStatus`/`lastError`/`consecutiveErrors`. Was previously mis-marked ALREADY-FIXED. Demo-only / out of this slice's scope → belongs with the held `BankSyncCoordinator` finalizer work.
- **P10-CURRENT-007 is PARTIAL, not fully fixed:** generic `OperationRun` ledger + counters exist, but a dedicated `BankSyncRun` + per-row `BankTransactionImport` with provider cursor/page checkpoint (crash-resume) does NOT exist for API sync. Held.
- **STANDARD→STRICT transition edge (demo-only):** a transaction previously imported under STANDARD has an amount/merchant/date dedupeKey; an identical re-sync post-change keys on `idem:BANK_API_SYNC:<hash>` and won't collide with that old row, so it could create one duplicate. No false-positives against unrelated rows (distinct key namespace). Acceptable for the release-blocked demo path.
- **Variant sensitivity:** run `testDebugUnitTest` (not `testReleaseUnitTest`) — the cancellation test depends on `BuildConfig.DEBUG=true` for `requireStubMode()`.

### HELD — major feature scope, NOT done (needs human decision per orchestrator stop-rules)
These require net-new cross-module / schema / security feature development for a feature that is
**intentionally release-blocked demo-only** (`BankConnectionsViewModel.isDemoMode = true`,
`requireStubMode()`). Building real banking is beyond autonomous pipeline-local fix scope:
- P10-CURRENT-001 (real `BankProvider` port + provider registry), 002/003/004 (`BankConnectionRepository` / `BankConnectionLifecycleCoordinator` — wire the demo VM to real DB-backed connections), 005 (OAuth state/PKCE/`BankAuthSession` entity — **schema**), 009 (durable token refresh + reauth-required state), 010 (DAO mutator allowlist guard once a coordinator exists), 011 (`BankTransactionClassifier` low-confidence→PendingReview for API sync), 014 (multi-account model — **schema redesign**), 015 (`BankSyncWorker` in `WorkerRegistry`), 016 (deterministic demo fixtures), 025/026/027/028/029/030 (cloud-payload purpose for statement AI, token restore-decryptability policy, richer authStatus enum, payment-method mapping, currency-assumption-not-silent-EUR, dedicated bank event taxonomy).
- These should be scoped as a real feature epic (likely `@planner-advanced` + schema migration + instrumentation tests), not folded into a pipeline-local fix batch.

### Validation (human must run — NOT run by agent)
- `:app:assembleDebug --stacktrace` — verifies Kotlin compile + the `internal`/`@VisibleForTesting` change.
- `:app:testDebugUnitTest --stacktrace` — esp. `BankApiIntegrationTest` (6 tests).
- `:app:check --stacktrace` — includes `verifyDbAccessBoundaries`.
- `:app:connectedDebugAndroidTest` — NOT required (no schema/migration change this batch).

### Working-tree note
Pipeline 10 edits (`BankApiIntegration.kt`, `BankApiIntegrationTest.kt`) sit alongside UNRELATED
uncommitted Pipeline 5/6/7/8/9 changes already in the working tree. Commit the Pipeline 10 files
separately to keep attribution clean.

---

## Pipeline 11 — Email Receipt Ingestion

Audit: `pipeline_11_email_receipt_ingestion_debug_report.yaml` (verdict `IMPROVED_BUT_NOT_CLEAN`,
written against OLD commit `4113e38f`). **Reconciled against current HEAD `3e426f11` before any
implementation** — the audit is heavily stale, matching the pattern of every prior pipeline.
Mode: static only — agent did NOT compile/build/run Gradle or tests. Implementation: 1 slice,
code-only, no schema/migration change. Self-review verdict: **GREEN** (static tester + adversarial
reviewer).

Validation (human must run — NOT run by agent):
- assembleDebug: pending
- testDebugUnitTest: pending (esp. `EmailReceiptIngestionServiceTest`, `ReceiptLifecycleCoordinatorTest`)
- check: pending
- connectedDebugAndroidTest: **NOT required** (no schema/migration change in this batch)

### Reconciliation finding (most of the 22 audit issues are already FIXED at HEAD)
The audit's `P11-CURRENT-*` issues were classified against the live code at HEAD. The email
pipeline was substantially hardened after the audit commit. Verified **already implemented** at
HEAD (NOT re-touched; evidence by file:line):
| Audit ID | Audit status | ACTUAL at HEAD | Evidence |
|----------|--------------|----------------|----------|
| P11-CURRENT-002 | OPEN (P0: raw subject in `Expense.notes`) | ALREADY-FIXED | `ReceiptLifecycleCoordinator.processEmailReceipt` sets `notes = "Email receipt from ${provider}"` (`:1005`) — never the raw subject. |
| P11-CURRENT-003 | OPEN (P0: body/sender/subject use `rawOcrStorageMode`) | ALREADY-FIXED | `emailStorageMode = settings.emailReceiptStorageMode` (`:829`) governs body (`sanitizeRawOcr(rawEmailBody, emailStorageMode)` `:872`), sender (`sanitizeEmailSender(..., emailStorageMode)` `:914`), subject (`:915`), parsedItems (`:891-895`), and messageId (`:923-926`). |
| P11-CURRENT-004 | OPEN (message-ID dedupe broken under sanitized storage) | ALREADY-FIXED | ingestion HMAC-hashes messageId once (`EmailReceiptIngestionService:208`); coordinator dedupes by that hash in ALL modes (`:827,:832`) and stores it as `sourceFingerprint` (`:904`). |
| P11-CURRENT-005 | OPEN (source insert conflict leaves receipt without source) | ALREADY-FIXED | `insertOrIgnore == -1L` resolves by fingerprint/messageId/messageIdHash/contentFingerprintHash, else **throws to roll back** — never continues with `sourceId <= 0` (`:934-960`). |
| P11-CURRENT-006 | OPEN (double-dispatch of transaction side effects) | ALREADY-FIXED | service does NOT re-dispatch (`EmailReceiptIngestionService:248` comment + no dispatch call); coordinator is the single owner via `postCommitActionRunner.runBestEffortAfterCommit` (`:1085`) with one combined batch. |
| P11-CURRENT-008 | OPEN (text/semantic fingerprints not persisted) | ALREADY-FIXED | `ScannedReceipt(... textFingerprint = emailTextFingerprint, semanticFingerprint = emailSemanticFingerprint)` (`:905-906`). |
| P11-CURRENT-010 | OPEN (P1: DB diagnostic written while restore blocks writes) | ALREADY-FIXED | production `DiagnosticEventWriter` is `CompositeDiagnosticEventWriter`: routes to `MaintenanceSafeDiagnosticSink` when mode != NORMAL and checks `DatabaseWriteBarrier` before any Room write (`CompositeDiagnosticEventWriter:30-44`). No DB write during restore-block. |
| P11-CURRENT-012 | OPEN (CancellationException swallowed) | ALREADY-FIXED | `EmailReceiptIngestionService:295` `if (e is CancellationException) throw e`; `emitEmailReceiptDiagnostic` rethrows too (`ReceiptLifecycleCoordinator:1124`). |
| P11-CURRENT-013 | OPEN (P1-privacy: raw sender logged) | ALREADY-FIXED | sender only via `.putHashed("sender", sender)` (`:136`); logs emit `$provider`/correlationId/exception only — no raw sender. |
| P11-CURRENT-001 (collision) | NEEDS_VERIFICATION (P0 compile risk) | FIXED THIS SLICE — see below | |

### Issues actually fixed this slice (genuine, code-only, pipeline-local)
| ID | Title | Status after change |
|----|-------|---------------------|
| P11-CURRENT-001 | `EmailReceiptData` name collision in `EmailReceiptIngestionService` | FIXED — the file imported the canonical 10-field `domain.receipt.EmailReceiptData` (`:13`) AND declared a shadowed 5-field `data.email.EmailReceiptData` at the bottom. The local class had **zero construction sites anywhere** and its only "consumer", `processBatch`, has **zero callers** and accesses `email.from` (a domain-only field), so the file only ever bound to the domain type — the local class was dead, shadowing code. Removed it; `processBatch` now explicitly consumes the domain `EmailReceiptData`. Zero behavior change; eliminates the fragile/ambiguous declaration. |
| P11-CURRENT-020 | Home-currency DataStore read happens inside `database.withTransaction` | FIXED — `currencySettingsRepository.resolveHomeCurrency()` (+ `homeCurrency` derivation) moved to BEFORE `database.withTransaction` in `processEmailReceipt`. The value is read-only inside the transaction, so the move is behavior-preserving; it stops the Room write lock from being held during DataStore/Flow I/O (lower lock-hold duration + deadlock/flakiness risk). |
| (doc debt) | Domain `EmailReceiptData` KDoc referenced the now-deleted batch class | FIXED — KDoc updated to describe it as the single canonical model. |

### Files changed (this slice)
Main source:
- `data/email/EmailReceiptIngestionService.kt` — removed the dead shadowing `EmailReceiptData` (5-field) declaration; `processBatch` doc clarified to consume the domain model (001).
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` — hoisted `resolveHomeCurrency()`/`homeCurrency` out of the email `withTransaction` (020).
- `domain/receipt/EmailReceiptData.kt` — KDoc fix (no longer references the deleted class).
Test source: none changed (existing `ReceiptLifecycleCoordinatorTest` already uses the 10-field domain `EmailReceiptData`; both email-service tests have zero refs to the removed class / `processBatch`).

### Static checks performed
- Grep: the removed local `EmailReceiptData` had no construction sites; `processBatch` has no callers in `app/src`.
- Grep: `ReceiptLifecycleCoordinatorTest` constructs the 10-field domain `EmailReceiptData` (`messageId/from/subject/body/receivedAt/amount/merchant/currency/date/items`) — unaffected.
- Verified `CompositeDiagnosticEventWriter` (the wired impl) is restore-safe, closing P11-CURRENT-010 without code change.

### Known compile-risk areas (agent could not compile)
- `EmailReceiptIngestionService.kt`: after removing the local class, `EmailReceiptData` in `processBatch(emails: List<EmailReceiptData>)` binds to the imported domain type (`:13`); `email.body/from/subject/receivedAt/messageId` all exist on the domain model. No other module declares/expects the removed type.
- `ReceiptLifecycleCoordinator.kt`: `homeCurrency` is now declared before the `try`/`withTransaction` and captured by the lambda (used at `~:897` and `~:1001`). No signature change.

### HELD — feature scope / larger work, NOT done (needs human decision)
Genuinely-open enhancement items beyond a pipeline-local fix slice (most are net-new infra for a
mailbox-sync feature that does not yet exist):
- P11-CURRENT-007 (richer content fingerprint incl. provider/orderNumber/currency), 009/011 (low-confidence/validation-failed → `PendingReview` routing + structured non-success result instead of `Success([])`), 014 (Hilt multibinding parser registry), 015 (full `EmailIngestionEvent` ledger taxonomy), 016 (batch summary/checkpoint/backpressure), 017 (`EmailAccountConnection`/`EmailSyncRun`/`EmailMessageImport` mailbox-sync — **schema**), 018 (orderNumber/emailSource provenance on `TransactionEvent`), 019 (shared money/currency parser + ambiguous-currency review), 021 (email-artifact retention/redacted-export coverage), 022 (remove remaining unused service deps).
- These should be scoped as a feature epic (`@planner-advanced` + likely schema + instrumentation), not folded into a pipeline-local fix batch.

### Validation (human must run — NOT run by agent)
- `:app:assembleDebug --stacktrace` — verifies the removed-class resolution + Hilt graph unchanged.
- `:app:testDebugUnitTest --stacktrace` — esp. `EmailReceiptIngestionServiceTest`, `EmailReceiptIngestionServiceTransactionTest`, `ReceiptLifecycleCoordinatorTest`.
- `:app:check --stacktrace`.
- `:app:connectedDebugAndroidTest` — NOT required (no schema/migration change this batch).

### Batch 2 (this session) — fingerprint specificity + confidence/review routing + dead-code removal
Self-review: **GREEN** (adversarial reviewer + static tester + fresh debugger regression pass; one
test-assertion blocker found and fixed in the loop). Mode: static only — agent did NOT compile/build/
run Gradle or tests. **No schema/migration change → no `connectedDebugAndroidTest` required this batch.**

> Reconciliation correction to Batch 1: when Batch 2 started, the dead local `data.email.EmailReceiptData`
> class AND the unused `transactionRunner`/`database` ctor plumbing were STILL PRESENT at HEAD (Batch 1's
> dead-class removal was not in this working tree). Batch 2's S1 actually removed them — this closes
> **P11-CURRENT-022** (and re-confirms **P11-CURRENT-001**'s collision is gone). The home-currency hoist
> (**P11-CURRENT-020**) was already committed (`1a33a3ac`) and is unchanged.

| Slice | ID(s) | Status after change — evidence |
|-------|-------|--------------------------------|
| S1 | P11-CURRENT-022 (+001) | FIXED — removed the never-invoked `transactionRunner` + the `database: AppDatabase` ctor param (the coordinator owns the transaction) and the dead shadowing local `EmailReceiptData` (5-field) class. Service now has ONE `@Inject` primary ctor (6 deps, all used) + the 4-arg test ctor. `processBatch` binds the domain `EmailReceiptData` (`EmailReceiptIngestionService.kt:52-73,364-376`). |
| S3 | P11-CURRENT-007 | FIXED — `createFingerprint` is now `sha256(provider_merchant_amount_currency_dateBucket_orderNumber)` (was merchant+amount+dateBucket only). Distinct orders (different `orderNumber`) and cross-currency same-amount receipts no longer collapse into false-positive duplicates; strictly MORE specific, so it can only split previously-collapsed distinct receipts, never introduce new collapses. messageId-hash dedup path (the primary cross-send dedup) is unchanged (`EmailReceiptIngestionService.kt:183-190,369-384`). |
| S4a | P11-CURRENT-009 (persist) | FIXED — added `EmailReceiptData.confidence: Double = 1.0` (trailing default); service passes `parsedReceipt.confidence`; coordinator persists it clamped instead of hardcoded `0.7f`/`1.0` (`ScannedReceipt.confidence = emailData.confidence.coerceIn(0,1).toFloat()`; `EmailReceiptSource.confidence = ...coerceIn(0,1)`) (`ReceiptLifecycleCoordinator.kt:902-903,936-937`). |
| S4b | P11-CURRENT-009/011 (route) | FIXED — added `EmailReceiptProcessResult.NeedsReview(receiptId, reason, confidence?)` + `EmailReceiptResult.NeedsReview`. Low-confidence parses and `ValidationFailed`/`InsertConflict`/`Error`/incomplete-parse outcomes now save the receipt but route to `NeedsReview` instead of a misleading `Success([])`. The `CreateExpenseResult` `when` is now EXHAUSTIVE (all 5 variants, no catch-all `else`). `NeedsReview` fires only when no expense was created/linked (a `DuplicateSkipped` link still yields `Success`) (`ReceiptLifecycleCoordinator.kt:1009-1089,1156-1163`; service arm `EmailReceiptIngestionService.kt:279-292`). |
| S5 (fix-loop) | P11-CURRENT-009 (effectiveness) | FIXED — reviewer found the gate was DEAD: parsers floor at base `0.5` + amount bonus (`+0.2` Amazon / `+0.25` Uber+Apple), and `validateParsedReceipt` requires `amount>0`, so every real receipt arrives at **≥0.70/0.75** — the old `≤0.5` gate could never fire. Raised `EMAIL_AUTO_EXPENSE_MIN_CONFIDENCE` `0.5 → 0.75` (with the existing `<=`), so amount-only / weakly-corroborated parses route to `NeedsReview` while order-number (`+0.15`) or distinct-date (`+0.1`) corroborated receipts auto-create (`ReceiptLifecycleCoordinator.kt:153`). |

### Regression found and fixed in the review loop (the only blocker)
- **Pre-existing wrong test assertion (present at HEAD, surfaced by S5):** `ReceiptLifecycleCoordinatorTest.kt:303` and `:335` asserted `coVerify(exactly = 1) { scannedReceiptDao.insert(any()) }`, but the coordinator NEVER calls `scannedReceiptDao.insert` directly — every persistence path goes through `receiptInsertResolver.insertOrResolve(...)` (grep: 0 matches for `scannedReceiptDao.insert(` in the coordinator). The assertions were always wrong; S5's promotion of `receiptInsertResolver` to a stubbed field (`Inserted(1L)`) made them reachable and deterministically failing. **Fixed:** retargeted both to `coVerify(exactly = 1) { receiptInsertResolver.insertOrResolve(any()) }`. (The `Inserted(1L)` field stub also incidentally repaired the scan happy-path test, which previously threw `NoWhenBranchMatchedException` against a relaxed mock.)
- **Diagnostic-honesty polish (non-blocking, applied):** `NeedsReview.confidence` is now only carried when the reason IS `low_confidence` (`...takeIf { needsReviewReason == "low_confidence" }`); for `validation_failed`/`insert_conflict`/`create_error`/`incomplete_parse` it is `null` to avoid misleading diagnostics (`ReceiptLifecycleCoordinator.kt:1161`).

### Files changed (Batch 2)
Main source:
- `data/email/EmailReceiptIngestionService.kt` — S1 ctor/dead-class removal; S3 fingerprint; S4a confidence pass-through; S4b `EmailReceiptResult.NeedsReview` + `when` arm.
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` — S4a clamped-confidence persist; S4b low-confidence gate + exhaustive `CreateExpenseResult when` + `NeedsReview` terminal return; S5 threshold `0.75`; confidence-honesty polish.
- `domain/receipt/lifecycle/EmailReceiptProcessResult.kt` — `+NeedsReview` variant.
- `domain/receipt/EmailReceiptData.kt` — `+confidence: Double = 1.0` (trailing default).
Test source:
- `data/email/EmailReceiptIngestionServiceTest.kt` — +3 fingerprint-specificity tests (distinct order / distinct currency / identical→identical), +confidence-threading capture test, +2 NeedsReview-mapping tests.
- `data/email/EmailReceiptIngestionServiceTransactionTest.kt` — REWRITTEN: was a vacuous count-0 test against a relaxed-mock coordinator; now asserts the real delegate-once contract (`coVerify(exactly = 1)` + coordinator-`Error`→`ParseError`).
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt` — +low-confidence→`NeedsReview` (no auto-expense), +incomplete-parse→`NeedsReview`, renamed the 020 test to match what it asserts, promoted `receiptInsertResolver` to a stubbed field, fixed the two `coVerify` assertions.

### Static checks performed (Batch 2)
- Grep confirmed the only exhaustive `when (coordinatorResult)` over `EmailReceiptProcessResult` is in the service (4 arms incl. `NeedsReview`); `EmailReceiptResult` is only `is`-checked elsewhere; `ManualExpenseRepository:164` is a different (`CreateExpenseResult`) `when` — untouched.
- Confirmed `CreateExpenseResult` has exactly 5 variants → the `else`-less `when` is exhaustive; `EventOutcome.NEEDS_REVIEW` is a real enum constant.
- Confirmed `EmailReceiptIngestionService(` has only 2 construction sites (both tests, 4-arg ctor); `@Inject` primary resolves via Hilt with no module change.
- Verified parser confidence math (Amazon/Uber/Apple `calculateConfidence`) vs `validateParsedReceipt` to size the `0.75` threshold.
- Confirmed CancellationException still rethrown (service `:295`); the coordinator `CreateExpenseResult.Error` branch only logs because `createExpenseDbOnlyV2` returns `Error` as a value and rethrows real cancellation upstream.

### Known compile-risk areas (Batch 2 — agent could not compile)
- Hilt: `EmailReceiptIngestionService` lost the `database`/`transactionRunner` plumbing on its `@Inject` ctor — verify `:app:assembleDebug` (KSP/Hilt) resolves the trimmed 6-dep graph, not just Kotlin compile.
- `EmailReceiptData.confidence` is a trailing-defaulted param — all construction sites (service + 2 coordinator-test sites) compile via the default; no positional caller exists.
- Coordinator-test NeedsReview/incomplete tests rely on Room `database.withTransaction{}` executing its block against a relaxed `AppDatabase` mock (established harness pattern in that file). The two `coVerify` fixes are independent of that assumption.

### Still HELD — feature scope / larger work, NOT done (needs human decision)
(Updated from Batch 1: **007, 009, 011, 022 are now FIXED above** and removed from this list.)
- P11-CURRENT-014 (Hilt multibinding parser registry), 015 (full `EmailIngestionEvent` ledger taxonomy beyond the current stage diagnostics), 016 (batch summary/checkpoint/backpressure), 017 (`EmailAccountConnection`/`EmailSyncRun`/`EmailMessageImport` mailbox-sync — **schema**), 018 (orderNumber/emailSource provenance on `TransactionEvent` metadata — partial: `emailReceiptSourceId` + source link already written), 019 (shared money/currency parser + ambiguous-currency-not-silent-default review), 021 (email-artifact retention/redacted-export coverage — cross-pipeline P8).
- These should be scoped as a feature epic (`@planner-advanced` + likely schema + instrumentation), not folded into a pipeline-local fix batch.
- **Verify-only:** Uber/Apple amount-only parses land at exactly `0.75` and are gated only because the comparison is `<=` — keep `<=` (a `<` would let them auto-create). A boundary test pinning `0.75 → NeedsReview` is recommended.

### Validation (human must run — NOT run by agent) — Batch 2
- `:app:assembleDebug --stacktrace` — REQUIRED (verifies trimmed Hilt ctor graph for `EmailReceiptIngestionService`).
- `:app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.*" --tests "com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinatorTest" --stacktrace`
- `:app:check --stacktrace`
- `:app:connectedDebugAndroidTest` — NOT required (no schema/migration change this batch).

### Working-tree note
Pipeline 11 edits sit alongside UNRELATED uncommitted Pipeline 5/6/7/8/9/10/12 changes already in
the working tree. Commit the Pipeline 11 files separately to keep attribution clean.

---

## Pipeline 12 — Import / Export / Accounting

Audit basis: `pipeline_12_import_export_accounting_debug_report.yaml` (stale, pinned `4113e38f`) +
`docs/analyses and debug master/new debugging session/pipeline 12/pipeline12_recheck_4227cee2.md`
(recheck, pinned `4227cee2`). **Reconciled against actual HEAD `3e426f11`** (Pipeline 9 landed
after both docs). Mode: static only — agent did NOT compile/build/run Gradle or tests.
Reconciled PR order: **PR-REG → PR-RT → PR-IMP → PR-FIELDS → PR-ACCT → PR-SNAP → PR-TZ**.

### Scope finding (reconciliation)
- The headline finding **P12-REG-01** (export feature dead) is **CONFIRMED LIVE at HEAD** by code
  inspection: `ExportOptionsViewModel.generateExport()` requested `RAWBACKUP_EXPORT`;
  `ExportPrivacyGate.check(RAWBACKUP_EXPORT)` returns `Denied` on BOTH branches; `CompositePrivacyGate`
  breaks on first `Denied` → every normal export from the production screen fails with
  "Export denied by privacy settings". The dedicated `EXPENSE_EXPORT` capability already exists and
  returns `Allowed`, so the fix is a capability switch, not new infra.
- The import engine genuinely exists (`util/ImportCoordinator`, `CsvExpenseImporter`,
  `JsonExpenseImporter`) and routes through `TransactionLifecycleCoordinator` (no DAO bypass). The
  stale YAML's "no import pipeline" claim was wrong (classes live in `util/`, not `domain/import`).
  Production import UI + a real roundtrip test remain OPEN (PR-IMP / PR-RT).

### Batch PR-REG (this session) — UNBLOCKS THE EXPORT FEATURE
Self-review: GREEN (coder + static-tester + adversarial reviewer; one test-fix loop applied).
Branch: `master-refactor` at HEAD `3e426f11`. **No schema/migration change → no `connectedDebugAndroidTest` required this batch.**

| ID | Title | Status after change |
|----|-------|---------------------|
| P12-REG-01 | Normal (unencrypted) export unconditionally denied by gate chain | FIXED — `generateExport()` now requests `PrivacyCapability.EXPENSE_EXPORT` (plain) / `EXPENSE_EXPORT_ENCRYPTED` (encrypted) instead of `RAWBACKUP_EXPORT`/`ENCRYPTED_BACKUP`. `ExportPrivacyGate` ALLOWS `EXPENSE_EXPORT`, so the production Export button works again in release + debug. `RAWBACKUP_EXPORT` stays owned solely by `ExportPrivacyGate` (raw-DB-backup flows) — no gate-ownership conflict introduced. |
| P12-NEW-01 | Hardcoded `"default"` encryption password + plaintext-on-failure | FIXED — removed the `"default"` literal entirely. `generateExport(encryptExport, passphrase)` fails closed if encryption is requested with a null/blank passphrase (before touching the repo). Encryption now writes the hidden temp file **directly into the final `.enc` path** (plaintext never lands at a shareable path), and a single `finally` always deletes the plaintext temp (success, encryption failure, or any throw). `encryptExportFile` no longer deletes its input — the caller owns plaintext lifecycle. |
| (test debt) | `ExportOptionsViewModelTest` was `@Ignore`d AND mocked the gate as blanket-`Allowed` | FIXED — un-`@Ignore`d; injects the test dispatcher (VM now takes `@IoDispatcher CoroutineDispatcher`); added regression guards driving the **real** `CompositePrivacyGate(ExportPrivacyGate(...))` (not a relaxed mock) so a future capability regression is caught. |

### Files changed (PR-REG)
Main source:
- `ui/screens/export/ExportOptionsViewModel.kt` — capability switch (`EXPENSE_EXPORT`/`EXPENSE_EXPORT_ENCRYPTED`); `@IoDispatcher private val ioDispatcher: CoroutineDispatcher` ctor param (replaced all 4 `Dispatchers.IO`); `generateExport(encryptExport, passphrase)` fail-closed encryption (no `"default"`); encrypt-into-`.enc` + `finally` plaintext cleanup; KDoc updated.
- `data/repository/ExportDataRepository.kt` — `encryptExportFile(plaintextFile, encryptedFile, password)` (3-arg; explicit dest; no longer deletes input); stale "planned encryption" KDoc replaced with the wired contract.
Test source:
- `ui/screens/export/ExportOptionsViewModelTest.kt` — un-`@Ignore`d; injects dispatcher; +`realCompositeGate()` helper; +`export succeeds through real composite gate with EXPENSE_EXPORT capability`; +`...even when raw backup is denied` (encryptedBackupEnabled=false); +`encrypted export with blank passphrase fails closed and writes no file`; +`encrypted export uses non-default passphrase and never leaves plaintext at final path`; +`encrypted export deletes plaintext when encryption fails`.
Docs:
- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` — Export Options capability row corrected `RAWBACKUP_EXPORT` → `EXPENSE_EXPORT`/`EXPENSE_EXPORT_ENCRYPTED`; added a P12-REG-01 note.

### Static checks performed
- Grep confirmed the ONLY `ExportOptionsViewModel(...)` construction site is the test (updated to the 9-arg ctor); the production `ExportOptionsScreen` uses `hiltViewModel()` (Hilt resolves `@IoDispatcher` via existing `DispatchersModule`).
- Grep confirmed `encryptExportFile` has NO other caller besides the VM (signature change is safe).
- Grep confirmed no stray `Dispatchers` symbol remains in the VM after the import removal.
- `ExportPrivacyPolicyTest` (existing) already asserts `EXPENSE_EXPORT` is Allowed and `RAWBACKUP_EXPORT` is Denied — the VM change is consistent with it (not contradicted).

### Known compile-risk areas (agent could not compile)
- VM ctor gained `@IoDispatcher CoroutineDispatcher` — Hilt resolves it via the existing `DispatchersModule.providesIoDispatcher()`; verify `:app:assembleDebug` (KSP/Hilt), not just Kotlin compile. The sole non-Hilt construction site (the test) is updated.
- `encryptExportFile` arity 2 → 3 + return type `File` → `Unit`. Only caller is the VM (updated). No interface/other-module reference.
- Test uses MockK `slot`/`capture` and the real `CompositePrivacyGate` (suspend `check`/`logDecision` resolved via relaxed `PrivacyAuditLogger` mock + `runBlocking`).

### Validation (human must run — NOT run by agent)
- `:app:assembleDebug --stacktrace` — REQUIRED (Hilt graph: new `@IoDispatcher` ctor param).
- `:app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.export.*" --tests "com.yourname.expensetracker.domain.privacy.ExportPrivacyPolicyTest" --stacktrace`
- `:app:check --stacktrace`
- `:app:connectedDebugAndroidTest` — NOT required (no schema/migration change this batch).

### Open / not-yet-done (subsequent reconciled PRs — NOT in this batch)
- PR-RT: real `export-writer → file → import → fresh-DB` roundtrip golden; rename the misleading
  `CsvExportImportRoundtripGoldenTest` (only tests `CsvCellSanitizer`). (P12-P0-01 test half, P12-NEW-10)
- PR-IMP: production Import UI (CSV + JSON) wired to `ImportCoordinator` (import is debug-only/CSV-only today). (P12-P0-01 UI half)
- PR-FIELDS: schema v3 field coverage (business category/project/requiresReceipt, receipt links, ownership) + accounting effective-vs-gross amount + manifest. (P12-P1-06/07/08, P12-NEW-06)
- PR-ACCT: SQL-aggregate accounting validation (stop full in-memory load) + close direct-exporter bypass. (P12-P1-02/NEW-04, P12-NEW-05)
- PR-SNAP: true export snapshot (`export_snapshot_rows`) + manifest checksum — **migration + migration test** (GATED). (P12-P1-04)
- PR-TZ: deterministic timezone policy. (P12-NEW-08, P12-P1-09 cosmetic)

### Working-tree note
Pipeline 12 PR-REG edits sit alongside UNRELATED uncommitted Pipeline 5/6/7/8/9/10 changes already
in the working tree. Commit the Pipeline 12 files separately to keep attribution clean.

### Batch PR-ACCT (partial — this session) — IIF formula-injection hardening (P12-CURRENT-015)
Self-review: GREEN (coder + static-tester + reviewer). **No schema change → no `connectedDebugAndroidTest`.**
Scope note: only the **formula-neutralization** half of PR-ACCT was done this session. The
**SQL-aggregate accounting validation** half (P12-P1-02 / P12-NEW-04) and the **close-direct-exporter-bypass**
half (P12-NEW-05) are deliberately HELD — they need DAO query changes whose WHERE clause must
byte-match the streamed export rows (`isNotMine = 0`), which is too risky to land statically without
a compile. Flagged for a focused follow-up.

| ID | Title | Status after change |
|----|-------|---------------------|
| P12-CURRENT-015 | QuickBooks IIF formula hardening inconsistent | FIXED — `QuickBooksIIFExporter.writeExpense` now routes ALL IIF string fields (date, accounts, currency, memo, name) through the shared `CsvCellSanitizer.sanitizeIif()`, which neutralizes formula-leading characters (`=`,`+`,`-`,`@`) in addition to stripping tab/newline/CR. The private `escapeIifField()` (which stripped delimiters but left `=cmd|...` formula prefixes intact) is removed. Closes the formula-injection gap into QuickBooks/spreadsheet tools. |

**Files changed (PR-ACCT):**
- `domain/export/AccountingExporters.kt` — `QuickBooksIIFExporter.writeExpense` uses `CsvCellSanitizer.sanitizeIif` for every string field; removed private `escapeIifField`. (Xero/FreshBooks already used `CsvCellSanitizer.sanitize`.)
- `domain/export/CsvEscapingTest.kt` — (a) **corrected pre-existing STALE assertions** that predated the Currency + conversion columns now emitted by the exporters: Xero/QuickBooks/FreshBooks header rows, the `csv export handles empty string` row (now `,,99.99,EUR,...`), the delimiter-injection field/tab counts (Xero 10 fields; TRNS 7 tabs). These were failing at HEAD before this change — NOT weakened, re-aligned to the real contract. (b) +3 new tests: `quickbooks iif neutralizes formula-leading merchant` / `...memo` / `...at-sign and minus formula prefixes`.

**PR-ACCT compile-risk notes:**
- `CsvCellSanitizer.sanitizeIif` is in the same package (`domain.export`) as `AccountingExporters` — no new import. `sanitizeIif` strips tab/newline/CR identically to the removed `escapeIifField`, so the existing IIF behavior tests (tab→space, newline→space, CR-removed, trim) still hold; only formula neutralization is added.
- The `AccountingExportRepositoryTest` already asserts the NEW schema headers (e.g. the FreshBooks header at line 573), independently confirming the `CsvEscapingTest` header corrections were genuine stale-assertion fixes, not behavior changes.

### Batch PR-DOCS (this session) — stale-contract / false-claim comment fixes (zero compile risk)
Self-review: GREEN. KDoc/comment-only; no behavior change, no signature change.

| ID | Title | Status after change |
|----|-------|---------------------|
| P12-CURRENT-003 | `ExportDataRepository` KDoc falsely claims stable snapshot consistency | FIXED — the `BAK-13` KDoc claimed the pager "anchors on a fixed set of expense IDs … preventing phantom reads", contradicting `DeterministicExpenseExportPager` (which is honest: "NOT a true atomic snapshot"). Rewritten to state the real keyset (date,id) ordering guarantees AND that it is NOT point-in-time snapshot-consistent (count vs streamed rows can diverge under concurrent writes), pointing to PR-SNAP as the not-yet-implemented fix. Prevents a future agent marking P12-P1-04 fixed incorrectly. |
| P12-CURRENT-025 (timezone comment) | `AccountingExporters` header comment claims "UTC for dates" but code uses `ZoneId.systemDefault()` | FIXED (comment only) — replaced the misleading "UTC for dates" line with an explicit note that date columns are currently device-local (NOT UTC), non-deterministic across timezones, and that the deterministic timezone policy is the planned PR-TZ change (not yet applied). The actual behavior change remains PR-TZ. |

**Files changed (PR-DOCS):**
- `data/repository/ExportDataRepository.kt` — corrected the class-level `BAK-13` snapshot KDoc (now truthful about keyset-vs-snapshot).
- `domain/export/AccountingExporters.kt` — corrected the top-of-file timezone policy comment (no longer claims UTC).
- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` — (from PR-REG) Export Options capability row corrected.

**PR-DOCS note:** P12-NEW-10 (rename the misleading `CsvExportImportRoundtripGoldenTest`, which
only tests `CsvCellSanitizer`) was **NOT** done — a file rename means deleting a test file, which
the orchestrator stop-rules reserve for explicit human approval, and the rename only delivers value
paired with the real Robolectric roundtrip test in PR-RT (which needs a compile to author safely).
Deferred to PR-RT.

### Batch PR-VALIDATE (this session) — independent reviewer + fresh debugger pass + follow-up fixes
Two independent read-only agents validated the three landed slices:
- **Reviewer (deep, adversarial):** all four target issues FUNCTIONALLY fixed with real tests driving
  the production `CompositePrivacyGate`; `@IoDispatcher` confirmed resolvable via `DispatchersModule`;
  no lifecycle/DAO/migration/guard regressions. Found ONE blocker: a residual stale KDoc.
- **Debugger (fresh regression scan):** the three slices are **regression-free** (no P0 compile-break,
  no P1 functional regression). Confirmed the negative SPL amount is NOT passed through `sanitizeIif`
  (stays `-100.00`, not `'-100.00`). Flagged pre-existing latent bugs (not regressions).

Follow-up fixes applied this session in response:
| ID | Title | Status after change |
|----|-------|---------------------|
| P12-CURRENT-003 (residual) | Stale "stable ID snapshot for consistency" KDoc survived on `getExpensesBetween` | FIXED — the reviewer blocker. The orphaned KDoc above `exportOp` (describing `getExpensesBetween`) still re-asserted snapshot consistency, contradicting the corrected class KDoc. Rewritten to "keyset pagination … NOT a point-in-time snapshot; see the class KDoc". `rg "snapshot for consistency" app/src/main` now empty. |
| P12-CURRENT-020 (export-loop slice) | Source-link provenance read bypassed the `DatabaseReadBarrier` | FIXED — the debugger's P2-1. The export streaming loop read `exportDataRepository.sourceLinkDao.getForExpenses(...)` directly — the ONLY export read not fenced by the barrier that guards every other export read during restore. Added barrier-guarded `ExportDataRepository.getSourceLinksForExpenses(ids)` (same `EXPORT_OR_BACKUP_SNAPSHOT_READ` policy), made `sourceLinkDao` `private`, and routed the VM through it. Sole caller (VM streaming loop) updated. |

**PR-VALIDATE files changed:**
- `data/repository/ExportDataRepository.kt` — corrected residual snapshot KDoc; `+getSourceLinksForExpenses()` (barrier-guarded, returns grouped-by-targetEntityId); `sourceLinkDao` visibility `val` → `private val`; `+import EntitySourceLink`.
- `ui/screens/export/ExportOptionsViewModel.kt` — streaming loop now calls `getSourceLinksForExpenses(expenseIds)` instead of the raw DAO.

**PR-VALIDATE compile-risk notes:**
- `ExportDataRepository` is Hilt-injected (grep: no direct `ExportDataRepository(` construction site anywhere, prod or test), so narrowing `sourceLinkDao` to `private` breaks no caller. The only external `.sourceLinkDao` reference (the VM) was rewired. `EntitySourceLink.targetEntityId` confirmed present for the `groupBy`.

**Still-open pre-existing latent bugs (debugger findings — NOT regressions, NOT fixed this session):**
- P12-CURRENT-021 (P2): non-finite money (NaN/∞) silently coerced to `0.0`/`""` in `ExpenseExportMapper`/`CurrencyFormatter`/VM number formatters — needs a policy decision (reject row vs mark invalid vs manifest record); belongs with PR-FIELDS/PR-SNAP. HELD.
- Encrypted export path has no production UI caller (unchanged reachability); if surfaced later, reconcile the `.enc` filename with `ExportOptionsScreen`'s `selectedFormat`-based save/share extension. Verify-only.

### Batch PR-CLEANUP (this session) — generic-CSV sanitizer consolidation (debugger P3-1)
| ID | Title | Status after change |
|----|-------|---------------------|
| P12 P3-1 | Generic CSV used a local `escapeCsv` that did NOT strip `\u0000`/`\u000B` | FIXED — `ExportOptionsViewModel.escapeCsv` now delegates to the shared `CsvCellSanitizer.sanitize` (RFC-4180 quoting + `=,+,-,@` formula neutralization), which additionally strips NUL/VT control chars. Removed the now-dead private `isDangerousFormulaPrefix`. Behavior-preserving for existing CSV tests (`=SUM(A1:A2)`→`'=SUM(A1:A2)`, plain values unchanged); only adds control-char stripping. |

**PR-CLEANUP files changed:** `ui/screens/export/ExportOptionsViewModel.kt` — `escapeCsv` delegates to `CsvCellSanitizer.sanitize`; `+import CsvCellSanitizer`; removed dead `isDangerousFormulaPrefix`.

### Validation (human must run — NOT run by agent) — covers PR-REG + PR-ACCT
- `:app:assembleDebug --stacktrace` — REQUIRED (Hilt graph: new `@IoDispatcher` ctor param on `ExportOptionsViewModel`).
- `:app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.export.*" --tests "com.yourname.expensetracker.domain.export.*" --tests "com.yourname.expensetracker.domain.privacy.ExportPrivacyPolicyTest" --tests "com.yourname.expensetracker.data.repository.AccountingExportRepositoryTest" --stacktrace`
- `:app:check --stacktrace`
- `:app:connectedDebugAndroidTest` — NOT required (no schema/migration change in PR-REG or PR-ACCT).

### Remaining Pipeline 12 work (reconciled order — NOT done; flagged for next session)
- PR-RT (real export→import→fresh-DB roundtrip golden + rename misleading `CsvExportImportRoundtripGoldenTest`) — P12-P0-01 test half, P12-NEW-10.
- PR-IMP (production Import UI for CSV + JSON via `ImportCoordinator`) — P12-P0-01 UI half.
- PR-FIELDS (schema v3 field coverage: business category/project/requiresReceipt, receipt links, ownership; accounting effective-vs-gross amount; manifest) — P12-P1-06/07/08, P12-NEW-06.
- PR-ACCT remainder (SQL-aggregate validation + close direct-exporter bypass) — P12-P1-02/NEW-04, P12-NEW-05. **HELD: DAO WHERE-clause must match streamed rows; needs a compile.**
- PR-SNAP (true `export_snapshot_rows` + manifest checksum) — P12-P1-04. **GATED: Room migration + migration test.**
- PR-TZ (deterministic timezone policy) — P12-NEW-08, P12-P1-09 (cosmetic).
