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
- P7-CURRENT-013: semantic restore-equivalence golden suite (large instrumentation-test effort).
- P7-CURRENT-016: restore diagnostics ledger import — HELD (see dedicated section above). NO
  migration required, but needs startup + DI wiring (revive dead success importer, add failure
  importer, inject into `AppStartupCoordinator`). Held for a focused, reviewed follow-up.
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
