# Validation Findings — atomicity-pr21-enforcement-final (2026-08-09)

> **Historical record** — this report is a frozen validation snapshot, not current-state authority. as-of SHA `065a54387bdcb9e8040e21462b0d2d0321918cf7` (2026-08-09); scope: that validation pass only; it is not evidence for current HEAD. Current state authority: docs/ci/GUARD_EVIDENCE_INDEX.yml and docs/ci/GUARD_STATUS.generated.md.

Status: **PARTIAL / RED** — Python gate (step 1) not green; Gradle steps not yet run pending resolution.

## 0. Branch / commit

- Branch: `atomicity-pr21-enforcement-final` (expected ✓)
- `git rev-parse HEAD`: `065a54387bdcb9e8040e21462b0d2d0321918cf7`
  - Message: `chore: preserve recovery artifacts and pending guardrail work`
  - Expected tip `96c6b27d fix(time): T4C migrate year and quarter boundaries` IS present, but as the **parent** of HEAD.
- **Anomaly:** an unprompted commit landed at/shortly before session start, sweeping a large pending diff:
  - `app/build.gradle.kts` (+173), `WorkerExecutionGuard.kt` (+35), `ExchangeRateStoreAdapter.kt`, several e2e/golden test files,
    `.github/workflows/ci.yml`, `.opencode/agents/*`, `scripts/ci/guard_registry.py` (+19), `scripts/ci/run_static_guard_suite.py` (+10),
    plus doc/plan file additions/deletions at repo root.
- Working tree is **clean** when read with `core.autocrlf=false` (only 2 untracked scratch files:
  `expected_tuples.txt`, `scratch_explore.py`). The noisy `git status --short` ` M` list was a CRLF line-ending artifact under `autocrlf=true`.
- **Consequence:** every validation result below reflects `065a5438`, not `96c6b27d`.

## 1. Python guard tests

### 1a. pytest (guard test suite)

Command: `python -m pytest <script tests> -v --tb=short` (log: `%TEMP%\opencode\pytest_guard.log`, also `build/ci/static-guards/guard_tests.log`)

Result: **449 passed, 5 failed, 5 skipped** (≈133 s / 59 s)

Skipped (5, all expected Windows portability):
- `test_gradle_db_guard_contract.py::test_baseline_unreadable_is_infrastructure_error`
- `test_gradle_db_guard_contract.py::test_helper_resolve_returns_canonical_path`
- `test_gradle_db_guard_contract.py::test_helper_symlink_escaping_root_is_rejected`
- `test_gradle_db_guard_contract.py::test_helper_unreadable_path_is_rejected`
- `test_gradle_db_guard_contract.py::test_helper_python_preflight_nonzero_exit_is_rejected`

Failed (5):

1. `scripts/test_verify_db_access_boundaries.py::test_multiline_class_header_body_brace_after_constructor_parens`
   - assertion: `decls[0]["end"] == len(lines) - 1` → `7 == (9 - 1)` = `8`
   - Fixture content ends with `}\n`; `content.split("\n")` yields a trailing empty element (9 elements), so `len(lines)-1` points past the real last `}`. Parser returns `end=7` which is the correct last brace index.
   - **Test-side off-by-one on a trailing blank line.** Related DB scanner post-hoc: see 1c db_access.

2–5. `scripts/test_verify_time_boundaries.py::TestExceptionMatching::test_wrong_path_fails` / `test_wrong_class_fails` / `test_wrong_method_fails` / `test_wrong_api_fails`
   - each: `assert result.returncode == 1` **but** the guard exits with **2**.
   - Cause: with a mismatched (stale) allowlist entry + `--fail-on-violation`, `verify_time_boundaries.py` raises `GuardFatalError` and its `__main__` block does `sys.exit(2)` (both `verify_time_boundaries.py:986-989` and the ratchet contract's "child exit 2 → infrastructure"). Only real per-source violations return 1.
   - **Contract mismatch:** the guard treats a stale allowlist entry as an *infrastructure/config* failure (exit 2), while these 4 tests expect the *policy-violation* code (exit 1).

### 1b. Registry

`python scripts/ci/verify_guard_registry.py` → **PASS** (exit 0)
- Registered guards: 18; all files exist; registry self-consistent; registry consistent with CI manifest (18/18).

### 1c. Static guard suite

`python scripts/ci/run_static_guard_suite.py` → **FAIL** (exit 1)

| # | Guard | Mode | Result |
|---|-------|------|--------|
| 1 | guard_registry | blocking | PASS |
| 2 | source_provenance | blocking | PASS |
| 3 | ui_dao | blocking | PASS |
| 4 | worker | blocking | PASS |
| 5 | receipt_link | blocking | PASS |
| 6 | import_lifecycle | blocking | PASS |
| 7 | cloud_payload | blocking | PASS |
| 8 | pii_logging | blocking | PASS |
| 9 | di_release | blocking | PASS |
| 10 | allowlist_compliance | blocking | PASS |
| 11 | ignored_test_budget | blocking | PASS |
| 12 | lint_baseline_policy | blocking | PASS |
| 13 | **time_boundaries** | blocking | **VIOLATION** |
| 14 | cancellation | blocking | PASS |
| 15 | privacy | blocking | PASS |
| 16 | **db_access** | blocking | **VIOLATION** |
| 17 | event_writers | blocking | PASS |
| 18 | money | blocking | PASS |
| 19 | migration_matrix | blocking | PASS |
| 20 | **guard_tests** | blocking | **VIOLATION** |

Totals: 20 guards, 17 pass, 3 blocking violations, 0 warning viols, 0 infra errors, exit 1.

#### time_boundaries (13)

82 direct wall-clock time findings, `Calendar.getInstance()` / `System.currentTimeMillis()` / `LocalDate.now()` / `LocalDateTime.now()`.
Notable locations:
- `TimePeriodUtils.kt` legacy helpers, `addMonths/addDays/addYears/getWeekOfYear/getMonthRange/getYearRange/getLastNDaysRange` (~308 lines)
- `SynthesisEngine.kt` `synthesizeInternal` / `calculateBlockPartyData` / `buildRecurringByDayFromOccurrences` / `buildRecurringByDayLegacy` (11 findings)
- `AutomatedSavingsRuleStateRepository.buildYearMonthKey`, `AutomatedSavingsRuleEngine.buildMonthKey`
- `FinancialRescueCoordinator.backupDatabaseFiles` / `moveDatabaseFilesAside` / `markRescueDone` (3 × `System.currentTimeMillis()`)
- `TaxEstimator`, `MonthlySavingsSweepUseCase`, `BillReminderSettings`, `WarrantyTextExtractor`
- `<file>.<top>` / `<expression-body>` findings in `ui/` screens (AddExpenseSheet, HomeScreen, NavigationController, DebugViewerScreen, CashFlowCalendarScreen, etc.)
- `AiRuntimeDiagnostics`, `NotificationSeeder`, `ServiceDiagnostics`, `AiOutputValidators`, `FinancialStressForecastEngine`

**Status:** matches the documented known caveat ("remaining direct-time findings outside completed T4 batches"). Do NOT regenerate baselines / add broad exceptions.

#### 16 (db_access)

- Baseline: 15 findings → Current: **385 findings** → NEW: 385, RESOLVED: 15, UNCHANGED: 0 → **FAIL**
- The 15 baseline findings all resolved (RESOLVED) while 385 new surfaced — **not** explained by the time caveat. Strong signal the DB scanner / policy / structural-exceptions matching stopped matching broadly.
- Finding type counts (from NEW lines):
  - UNALLOWLISTED_CLASS — 386
  - UNSUPPORTED_EXPRESSION_BODY — 6
  - FORBIDDEN_FILE_OP — 4
  - UNSUPPORTED_DAO_SCOPE — 2
  - UNALLOWLISTED_CLASS_DIRECT_CHAIN — 1
  - UNSUPPORTED_METHOD_BODY — 1
- Representative findings:
  - `UNALLOWLISTED_CLASS no exact policy entry for class=WarrantyTrackerRepository method=markWarrantyAsClaimed dao=warrantyLifecycleEventDao op=insert rule=db_ownership_policy ...WarrantyTrackerRepository.kt`
  - `UNALLOWLISTED_CLASS ... class=WorkerRunLoggerImpl method=terminal dao=backgroundJobRunDao op=completeTerminal ...WorkerRunLogger.kt`
  - `FORBIDDEN_FILE_OP: DB file operation outside approved structural exception (class=MIGRATION_16_17 op=execSQL rule=db_structural_exceptions) AppDatabase.kt`
  - `FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class DatabaseMigrations.kt`
  - `FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class FinancialRescueCoordinator.kt`
  - `UNSUPPORTED_DAO_SCOPE: DAO mutation outside a resolved method (scope-format .. top-level dao=receiptEventDao op=insert) ReceiptSideEffectPlanner.kt`
  - `UNSUPPORTED_EXPRESSION_BODY: class=ExportOptionsViewModel method=formatCsvNumber multi-line expression body could not be bounded; refusing to scan mutations`
- Relevant policy files: `config/db_access_allowlist.yml`, `config/guards/db_ownership_policy.yml`, `config/guards/db_structural_exceptions.yml`, `config/guards/db_structural_exceptions_expected_methods.yml`, `config/baselines/db_access.json` (assembly).

#### 20 (guard_tests)
Runs the same pytest suite → final failure list identical to §1a (5 failed). So this violation is the pytest gate surfaced again by the suite.

## Next actions
1. Diagnose db_access 385-finding: compare current scanner output vs `config/baselines/db_access.json`, check whether changed `db_ownership_policy.yml` / echo structural scanning changed; identify if `H1/H2/G2`-era neighbor commit marks the mismatch (pending).
2. Fix the 5 pytest failures (off-by-one trailing-newline assertion; 4× exit-code contract 1 vs 2).
3. Re-run pytest + registry + static suite to GREEN before running any Gradle step.
4. Then proceed steps 2–7 (unit tests, compile, lint, :app:check, release, emulator migration matrix) — each report appended below.

---

# Update 1 — diagnosis + targeted fixes applied (2026-08-09)

## db_access 385-finding: root cause = baseline/fingerprint drift (NOT a regression)

- `scripts/verify_db_access_boundaries.py` was fully rewritten in the "exact DB ownership policy" era:
  `530ac96e` (2026-08-05) → `6596903e`/`220d1fda` (08-06) → `6f0e46c8` (08-07, last behavioral change).
- `config/baselines/db_access.json` was last generated/committed at `8589c2d5` (2026-07-11), in the G2/H1 format: **15 short file-level fingerprints** (`UNALLOWLISTED_CLASS app/src/.../Foo.kt`).
- The new scanner emits **385 detailed per-(class, method, dao, op) fingerprint lines**
  (`UNALLOWLISTED_CLASS: no exact policy entry for class=X method=Y dao=Z op=W rule=db_ownership_policy app/...`).
  The two formats share no overlap, so the ratchet reports ALL 385 as NEW and all 15 as RESOLVED every run.
- The ownership/structural policy files are current and loadable; the scanner itself is healthy — the fixed
  `test_multiline_class_header_body_brace_after_constructor_parens` now proves multiline class bodies and
  method/DAO attribution work. This is a committed-state inconsistency: scanner upgraded WITHOUT a baseline re-sync.
- **Remediation decision:** per the batch directive ("do not regenerate baselines / add broad exceptions to make
  gates pass"), the db_access baseline is NOT regenerated here. Guard stays RED and is tracked as pending:
  either re-sync the ratchet baseline to the new scanner format, or enumerate all mutations in policy, in a
  dedicated cleanup batch (not T4C scope).

## 5 pytest failures — now FIXED (pytest: 454 passed, 5 skipped; skipped are Windows-only)

1. `scripts/test_verify_db_access_boundaries.py::test_multiline_class_header_body_brace_after_constructor_parens`
   - Root cause: the test built `lines = content.split("\n")` on content ending in `\n`, which creates a phantom
     empty trailing "line"; production `readlines()` (and the parser) never produce it. The parser was correct
     (balanced body end == 7).
   - Fix: `lines = content.rstrip("\n").split("\n")` mirrors the scanner's on-disk read; `end == len(lines) - 1`
     now holds and the end-to-end scan assertions pass.
2–5. `scripts/test_verify_time_boundaries.py::TestExceptionMatching::test_wrong_{path,class,method,api}_fails`
   - Root cause: a mismatched (stale) allowlist entry is an **infrastructure/config failure** — the guard exits
     **2** (`GuardFatalError`) for an exception with no matching source evidence (that is the guard's documented
     fail-closed contract; the ratchet maps child exit 2 → infra). The tests asserted exit **1** (policy violation).
   - Fix: changed the four assertions to expect exit **2** (fail-closed stale-exception contract), keeping the
     `-fail-on-violation` flag; wrong-path test also asserts the FATAL diagnostic on stderr.

## Re-run results (step 1, post-fix)

- `pytest` (scripts + scripts/ci): **454 passed, 5 skipped** — exit 0.
- `verify_guard_registry.py`: **PASS** (18 guards, consistent with CI manifest).
- `run_static_guard_suite.py`: **18 passed / 2 blocking violations** (exit 1):
  - `time_boundaries` (~82 findings) — **known caveat**, T4C-remediation tracked, do not regenerate.
  - `db_access` (385) — **baseline drift**, documented above; do not regenerate per directive.
  - `guard_tests` now PASSES.

## Open items before "complete"
- db_access ratchet re-sync (dedicated batch, NOT regenerated here per directive).
- Remaining T4C time-boundary remediation (known caveat, do not baseline).
- Steps 2–7 still to run: unit tests, compile, lint, :app:check, release artifact, emulator migration proof,
  GitHub Actions run.

---

# Update 2 — step 2 (time-focused unit tests): blocked by a pre-existing Kotlin compile error

## Finding: `SystemMonotonicTimeProvider.kt` does not compile (HEAD `065a5438`)

- Command: `:app:testDebugUnitTest --tests "*TimePeriodUtils*" ...` → **BUILD FAILED** in `:app:compileDebugKotlin`.
- Errors:
  - `app/src/main/java/com/yourname/expensetracker/domain/util/SystemMonotonicTimeProvider.kt:18:43 Unresolved reference 'TimeMark'`
  - `...:20:51 Unresolved reference 'elapsedNow'`
- Root cause: the file declares `private val referenceMark: TimeSource.TimeMark = TimeSource.Monotonic.markNow()`, but
  `TimeMark` is a **top-level** type in `kotlin.time`, NOT a nested type of `TimeSource`. `TimeSource.TimeMark` does
  not resolve; the KDoc also references `[TimeSource.TimeMark]`.
- Provenance: the file is byte-identical between the confirmed T4C tip `96c6b27d` and HEAD `065a5438`; it was last
  touched at `da2b8565` (T1 batch "UUID, monotonic timing, and exact migration boundaries"). **This is a pre-existing
  compile break from the T1 batch, unrelated to the recovery commit.**
- Only usage in main source: this file (grep: `TimeSource.TimeMark` / `TimeMark` / `markNow` / `elapsedNow`).
- Planned fix (minimal, domain-correct): reference the top-level `kotlin.time.TimeMark` type (import it) and keep
  `markNow()`/`elapsedNow()` — the T1 monotonic timing contract is unchanged.

## Status
- Steps 0–1: reported above (registry PASS; pytest green after 5 test fixes; static suite 18/20 with the two
  documented non-regression holds).
- Step 2: **RED** — blocked on the compile error above; fix then re-run the time-focused unit tests.
- Steps 3–7: pending (compile, lint, :app:check, release, emulator proof, GitHub Actions).

---

# Update 3 — step 2 (continued): main source now compiles; TEST suite has 52 pre-existing compile errors

## Fix applied: `SystemMonotonicTimeProvider.kt`
- Changed `TimeSource.TimeMark` → top-level `kotlin.time.TimeMark` (added import). `:app:compileDebugKotlin` now passes.
- This was a pre-existing T1-era break, identical at `96c6b27d` and HEAD.

## New finding: `:app:compileDebugUnitTestKotlin` fails with 52 errors (pre-existing, present at `96c6b27d`)

No test file differs between `96c6b27d` and `065a5438` — the whole inventory below is pre-existing. Full `e:` list
captured at `%TEMP%\opencode\compile_ut_err.log` (52 errors, 10 files):

| File | Errors | Class |
|------|--------|-------|
| `data/ai/provider/DefaultAiEnvironmentMonitorTest.kt` | 8 | Suspension functions can only be called within coroutine body |
| `data/database/dao/BudgetAdjustmentDaoTest.kt` | 2 | `lateinit` not allowed on primitive; suspend fun outside coroutine |
| `data/database/dao/SavingsSweepPlanDaoTest.kt` | 2 | `lateinit` primitive; suspend fun outside coroutine |
| `domain/analytics/AdvancedAnalyticsDashboardTest.kt` | 2 | Suspension outside coroutine |
| `domain/cashflow/CashFlowCalculatorTest.kt` | 2 | Suspension outside coroutine |
| `domain/challenge/SpendingChallengeManagerTest.kt` | 1 | Unresolved reference `capture` (MockK) |
| `domain/naturallanguage/NaturalLanguageSearchEngineDefaultWindowBoundaryTest.kt` | 1 | Unresolved reference `capture` (MockK) |
| `domain/util/TimePeriodUtilsT4CBatch2CTest.kt` | 1 | Unresolved reference `value` |
| `domain/util/TimePeriodUtilsT4CBatch2DTest.kt` | 1 | Unresolved reference `value` |
| `guard/DbGuardPolicyFixtureTest.kt` | 4 | Unresolved `isCanonicalSourcePath`/`isValidMethodPattern`; missing `}` @532; unclosed comment @2298 |
| `ui/screens/assistant/AssistantViewModelTest.kt` | 18 | Only safe `?.`/`!!.` calls on nullable `String?` receiver |
| `util/JsonExpenseImporterTest.kt` | 10 | Unresolved `slot` / `captured` (MockK) |

Likely causes (to confirm per file): MockK `capture`/`slot`/`captured` usage without imports or API drift; DAO/suspend
signature drift between main and tests; `DbGuardPolicyFixtureTest` written against pre-rewrite scanner helpers
(`isCanonicalSourcePath`, `isValidMethodPattern` removed in `6f0e46c8`); nullable-receiver formatting in assistant
test; T4C batch tests referencing a missing `value` symbol.

## Plan
1. Fix the 52 test-compile errors file by file (smallest correct changes; no behavior changes beyond compilation).
2. Re-run `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin` until clean.
3. Re-run step 2 time-focused unit tests.
4. Then steps 3–7 (compile variants, lint, :app:check, release, emulator proof).

---

# Update 4 — test-compile fixes in progress (2026-08-09)

Fixed so far (smallest correct changes; behavior unchanged):

1. `SystemMonotonicTimeProvider.kt` (main): `TimeSource.TimeMark` → top-level `kotlin.time.TimeMark` (import added).
   `:app:compileDebugKotlin` now passes.
2. `util/JsonExpenseImporterTest.kt`: `captureRequest(): slot<CreateExpenseRequest>` → `CapturingSlot<CreateExpenseRequest>`
   (+ `import io.mockk.CapturingSlot`). `slot<T>()` is a function; only `CapturingSlot<T>` is a type.
3. `domain/challenge/SpendingChallengeManagerTest.kt`, `domain/naturallanguage/NaturalLanguageSearchEngineDefaultWindowBoundaryTest.kt`:
   removed invalid `import io.mockk.capture` (capture is a MockKMatcherScope member, not a top-level symbol).
4. `domain/util/TimePeriodUtilsT4CBatch2CTest.kt` @1040, `TimePeriodUtilsT4CBatch2DTest.kt` @1269:
   `startZoned.dayOfMonth.value` → `startZoned.dayOfMonth` (dayOfMonth is Int in java.time).
5. `guard/DbGuardPolicyFixtureTest.kt` — **the big one**: a KDoc at line 536 contained
   `` `app/src/main/java/.../*.kt` `` — a literal `/*` inside a block comment. Kotlin block comments NEST, so this
   opened a nested comment, swallowing most of the file's code and producing the cascade
   (unclosed comment @2298, missing `}` @532, unresolved `isCanonicalSourcePath`/`isValidMethodPattern` @365/369).
   Fixed by rewording the KDoc to `` `app/src/main/java/.../Foo.kt` `` (no `/*` sequence). The file now parses cleanly
   (verified by direct kotlinc invocation; only remaining output was a classpath-only codegen error).

Remaining error classes still to fix:
- `DefaultAiEnvironmentMonitorTest.kt` (8) — suspend calls outside coroutine
- `BudgetAdjustmentDaoTest.kt` / `SavingsSweepPlanDaoTest.kt` — `lateinit` on primitive + suspend outside coroutine
- `AdvancedAnalyticsDashboardTest.kt` (2) / `CashFlowCalculatorTest.kt` (2) — suspend outside coroutine
- `AssistantViewModelTest.kt` (18) — nullable `String?` receiver needing `?.` / `!!`

---

# Update 5 — test-compile fixes COMPLETE + DatabaseMigrationProofTest fixed (2026-08-09)

## All 52 test-compile errors fixed
- `:app:compileDebugUnitTestKotlin` is clean (exit 0, 0 `e:` lines).
- Additional fixes beyond Update 4:
  - `DefaultAiEnvironmentMonitorTest.kt`: `every`/`verify` on suspend `model.checkStatus()` → `coEvery`/`coVerify` (imports updated).
  - `BudgetAdjustmentDaoTest.kt` / `SavingsSweepPlanDaoTest.kt`: removed `lateinit var X: Long` (primitive → `= 0L`) and
    wrapped the DAO insert in `setup()` with `runBlocking` (established repo pattern).
  - `AdvancedAnalyticsDashboardTest.kt` / `CashFlowCalculatorTest.kt`: suspend `generateDashboardData` /
    `calculateDailyCashFlow` called inside `GlobalTimeZoneTestLock.withLock {}` (non-suspend lambda). Added a suspend
    overload `withLockSuspend(block: suspend () -> T)` to `GlobalTimeZoneTestLock` and switched those 4 call sites.
  - `AssistantViewModelTest.kt`: JUnit `assertNotNull` doesn't smart-cast → `diagnostics!!.contains(...)` after the
    assert; nullable `errorMessage` uses `?.contains(...) == true`.

## DatabaseMigrationProofTest — 2 infra root causes fixed
1. **Schemas not visible to unit tests**: `build.gradle.kts` wired `app/schemas` assets only for `androidTest`.
   Added `getByName("debug").assets.srcDirs("$projectDir/schemas")` (Robolectric serves debug assets) — this is what
   makes `145.json`/`148.json` reachable. (`test` source set was also added; `debug` is the operative one.)
2. **Fresh-vs-migrated DDL drift** in `DatabaseMigrations.kt`: Room-generated fresh-install DDL backticks every
   identifier and uses `ON UPDATE NO ACTION` + `CASCADE )`; the migration `ALTER TABLE ... ADD COLUMN` and the
   `MIGRATION_145_146` CREATE TABLE used unquoted identifiers and different FK clauses. Fixed to match Room exactly:
   - `MIGRATION_146_147`: `` ADD COLUMN `leftAt` `` / `` `idempotencyKey` `` backticked.
   - `MIGRATION_147_148`: all 9 `ADD COLUMN` column names backticked.
   - `MIGRATION_145_146`: `negotiation_outcomes` CREATE TABLE rewritten to single-line DDL matching Room
     (`negotiation_outcomes` (`id` ..., `ON UPDATE NO ACTION ON DELETE CASCADE `) with backticks).

Result: `:app:testDebugUnitTest --tests "*DatabaseMigrationProofTest*"` → **3/3 PASS, BUILD SUCCESSFUL**.

## Step-2 test run (focused filters) — 49 failures before fixes; current state
- `CashFlowCalculatorTest` (29) — ALL fail at `setUp`: MockK `any()` for the `CurrencyCode` value-class param of
  `MoneyNormalizationEngine.aggregateExpenses` generates a random signature value (`5cb6a82adf3a7d61`) that fails
  `CurrencyCode`'s `require(code.length == 3)`. Pre-existing test-infra defect (MockK + value-class `any()`),
  unrelated to T4C.
- `DefaultAiEnvironmentMonitorTest` (4) — `mockkObject(Generation)` cannot stub the ML Kit `Generation` object
  (`can't find stub class`). Pre-existing infra defect.
- `DatabaseMigrationProofTest` (2) — FIXED above (3/3 now PASS).
- `TimePeriodUtilsT4CBatch2B/C/D` (6) — week/quarter/cutover oracle mismatches — **T4C-relevant, known caveat**.
- `BudgetCalculatorTimeBoundaryTest` (1), `SpendingPersonalityClassifierTest` (1) — DST/time-boundary assertions —
  **T4C-relevant, known caveat**.

# Update 6 — infra blockers cleared; step-2 state clean (2026-08-09)

## CashFlowCalculatorTest — FIXED (was 29 setup failures → now passes)
Root cause was TWO stacked MockK + `@JvmInline value class` issues:
1. `coEvery { ...aggregateExpenses(any(), any(), any(), any()) }` — `any()` for the `homeCurrency: CurrencyCode`
   value-class param makes MockK generate a random signature value that fails `CurrencyCode`'s 3-letter `require`.
   Fix: use concrete `CurrencyCode.EUR` for that arg (all tests use EUR home currency).
2. `secondArg<CurrencyCode>()` in the `answers` lambda — value-class erasure: the JVM arg is the underlying
   `String` ("EUR"), so `secondArg<CurrencyCode>()` threw `ClassCastException: String cannot be cast to CurrencyCode`.
   Fix: `val homeCurrency = CurrencyCode(secondArg<String>())`.
Result: `:app:testDebugUnitTest --tests "*CashFlowCalculatorTest*"` → **BUILD SUCCESSFUL** (29 tests pass).

## DatabaseMigrationProofTest — FIXED (3/3 PASS) — see Update 5.

## Remaining step-2 failures (focused re-run, 356 tests):
- **8 T4C-relevant failures** = the documented "remaining direct-time findings" caveat (do NOT force-fix):
  - `TimePeriodUtilsT4CBatch2BTest` (1): year-9999 proleptic week boundaries
  - `TimePeriodUtilsT4CBatch2CTest` (1): cutover oracle
  - `TimePeriodUtilsT4CBatch2DTest` (4): quarters, Feb 29, cutover, year overload
  - `SpendingPersonalityClassifierTest` (1): DST weekend/night
  - `BudgetCalculatorTimeBoundaryTest` (1): T4B3 window containment
- **1 pre-existing infra blocker, not T4C**: `DefaultAiEnvironmentMonitorTest` (4) — MockK cannot stub the ML Kit
  `Generation` object in the Robolectric sandbox. Test unchanged since T1; unrelated to this batch.

## Full step-2 command (all 8 filters) — 392 tests, 12 failed (final)
- 8 T4C-relevant (caveat) + 4 `DefaultAiEnvironmentMonitorTest` (pre-existing infra, MockK cannot stub
  `com.google.mlkit.genai.prompt.Generation` — a Kotlin `object` with `INSTANCE`/`getClient()` from the genai-prompt AAR).
- `CashFlowCalculatorTest` (29) and `DatabaseMigrationProofTest` (2) now PASS (fixed in Updates 5-6).
- `TimePeriodUtilsT4CBatch1`/`2A`, `AdvancedAnalyticsDashboardTest`, `DayOfWeekAnalyzerTest` all PASS.

## Open items
- `DefaultAiEnvironmentMonitorTest` (4) — MockK/ML-Kit `mockkObject` limitation under Robolectric; separate pre-existing defect.
- 8 T4C-relevant failures — continue through remaining T4 batches (per directive, no baseline/exceptions).
- Steps 3–7 still to run.

---

# Update 7 — step 3 compile + step 4 lint + step 5 :app:check (2026-08-09)

## Step 3 — Kotlin compilation: GREEN
`:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin` → **BUILD SUCCESSFUL** (exit 0).

## Step 4 — Lint: GREEN
`:app:lintDebug` → **BUILD SUCCESSFUL** (exit 0).
- `Lint found 795 warnings, 13 hints (and 2223 errors filtered by baseline lint-baseline.xml)`.
- No new non-baselined errors; missing-translation baseline unchanged.

## Step 5 — :app:check: RED with 3 pre-existing guard holds (none introduced by this work)
Dry-run confirmed the task graph: `checkDirectTimeCalls`, `checkLifecycleBypass(es)`, `checkRawMoneyAggregates`,
`checkKotlinGradlePluginConfigurationErrors`, `verifyDbAccessBoundaries`, `verifyNoIgnoredGrowth`,
`verifyRoomSchemaSnapshots`, lint, unit tests.

**Infrastructure fix applied (cross-platform Python):**
- `build.gradle.kts`: `checkDirectTimeCalls` and `verifyDbAccessBoundaries` hard-coded `python3` (fails on Windows,
  exit 9009). Added a top-level `pythonInterpreter()` helper that respects `-PpythonExecutable` and falls back
  `python3` → `python`; both tasks now use it. Verified: guard now launches `python` (prints `Python 3.13.2`) and runs.

**`:app:check` holds (all pre-existing at the confirmed commit `96c6b27d`):**
1. `checkDirectTimeCalls` — **expected T4C caveat**: `DIRECT TIME: direct wall-clock time boundary violations found`
   (the ~82 remaining time findings). Fail-closed behavior is correct; do not add exceptions/baselines.
2. `checkLifecycleBypass` — **pre-existing real + false-positive findings**: regex `expenseDao\.(insert|update|delete)`
   matches non-allowlisted files. Real matches: `ExpenseWriteStore.kt`, `RecurringPlanProjectionService.kt`,
   `RecurringRuleLifecycleCoordinator.kt`, `DefaultExpenseCategoryAssignmentService.kt`, `ExpenseCategoryAssignmentPort.kt`.
   False positive: `ExpenseDao.kt` matches only in KDoc `[ExpenseDao.insertAtomic]`. Guard logic/allowlist untouched
   (do-not-weaken); tracked as pre-existing.
3. `verifyDbAccessBoundaries` — **db_access baseline drift** (documented Update 1); reached only if the two above are
   excluded.

Other guards not yet observed (short-circuited by the above): `verifyNoIgnoredGrowth`, `verifyRoomSchemaSnapshots`,
`checkRawMoneyAggregates`, `checkKotlinGradlePluginConfigurationErrors`, lint, unit tests.

## Open items
- `:app:check` cannot go fully green until: T4C time findings resolve (caveat), db_access baseline re-sync
  (dedicated batch), and `checkLifecycleBypass` allowlist/guard review (pre-existing).
- Steps 6–7 still to run.

---

# Update 8 — step 6 release + step 7 emulator (2026-08-09) — session conclusion

## Step 6 — Release verification: GREEN
`:app:assembleRelease` → **BUILD SUCCESSFUL** (exit 0).
- `app/build/outputs/apk/release/app-release.apk` (53.7 MB) ✓
- `app/build/outputs/mapping/release/mapping.txt` ✓
- `app/build/reports/lint-results-debug.html` ✓
- `scripts/verify_release_artifact.py --fail-on-violation` → **PASS** (exit 0; signing verified, package
  `com.yourname.expensetracker` versionCode 1).

## Step 7 — Emulator migration proof: NOT RUN (no emulator available)
- No device attached (`adb devices` empty); user confirmed no emulator in this session.
- The **Robolectric migration proof** (`*DatabaseMigrationProofTest*`, `testDebugUnitTest`) already ran and
  **passes 3/3** (migration chain, continuous-chain audit, fresh-vs-migrated schema parity) — see Update 5/6.
- The **instrumented** `DatabaseMigrationMatrixTest` (`am instrument`, `connectedDebugAndroidTest`) requires a device
  and was not executed. Recommended as a follow-up when an emulator is available.

---

# Session summary — findings and dispositions

## Files changed by this session
- `app/src/main/java/com/yourname/expensetracker/domain/util/SystemMonotonicTimeProvider.kt` — `TimeSource.TimeMark` → top-level `kotlin.time.TimeMark`.
- `app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt` — backtick-quoted `ADD COLUMN`
  names and rewrote `MIGRATION_145_146` `negotiation_outcomes` DDL to exactly match Room's fresh-install DDL.
- `app/build.gradle.kts` — schemas wired into `debug` + `test` assets; `pythonInterpreter()` helper (python3→python fallback).
- Test files (compile fixes only): `JsonExpenseImporterTest`, `SpendingChallengeManagerTest`,
  `NaturalLanguageSearchEngineDefaultWindowBoundaryTest`, `TimePeriodUtilsT4CBatch2C/DTest`,
  `DbGuardPolicyFixtureTest`, `DefaultAiEnvironmentMonitorTest`, `BudgetAdjustmentDaoTest`, `SavingsSweepPlanDaoTest`,
  `AdvancedAnalyticsDashboardTest`, `CashFlowCalculatorTest`, `AssistantViewModelTest`,
  `domain/util/GlobalTimeZoneTestLock.kt` (`withLockSuspend`).
- Python guard tests: `scripts/test_verify_db_access_boundaries.py` (trailing-newline split),
  `scripts/test_verify_time_boundaries.py` (4× exit-code 1→2 contract).
- Findings report: `VALIDATION_FINDINGS_2026-08-09.md`.

## Green gates
- Python pytest: **454 passed, 5 skipped** (skips Windows-only).
- `verify_guard_registry.py`: PASS.
- Static suite: 18/20 blocking (guard_tests now PASS).
- `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` / `:app:compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.
- `:app:lintDebug`: BUILD SUCCESSFUL (baseline-filtered, no new non-baselined errors).
- `:app:assembleRelease` + artifact verify: PASS.
- `CashFlowCalculatorTest` (29) and `DatabaseMigrationProofTest` (3/3) — fixed and green.

## Red gates / known holds (all pre-existing at the confirmed commit `96c6b27d`; none introduced by this work)
1. **time_boundaries / `checkDirectTimeCalls`** (~82 direct-time findings) — the documented "remaining direct-time
   findings outside the completed T4 batches" caveat. Do NOT regenerate baselines / add exceptions.
2. **db_access (385)** — ratchet baseline/fingerprint drift from the 2026-08-05/07 scanner rewrite vs the 2026-07-11
   baseline (Update 1). Policy files are current; scanner healthy. Needs a dedicated baseline re-sync batch.
3. **`checkLifecycleBypass`** — pre-existing regex guard finding (`expenseDao.insert/update/delete` in 5
   non-allowlisted files) + 1 false positive (`ExpenseDao.kt` KDoc). Guard/allowlist untouched (do-not-weaken).
4. **8 T4C-relevant unit-test failures** (TimePeriodUtilsT4CBatch2B/C/D, SpendingPersonalityClassifier,
   BudgetCalculatorTimeBoundary) — same T4C caveat.
5. **`DefaultAiEnvironmentMonitorTest` (4)** — pre-existing MockK+ML-Kit `mockkObject` limitation under Robolectric.
6. **Instrumented migration matrix** — not run (no emulator).

## Completion status
- **NOT complete.** Python/registry/compile/lint/release are green; `:app:check` and the full unit suite remain red on
  the 5 pre-existing holds above. A complete green GitHub Actions run and the emulator instrumented migration test
  remain outstanding. Do not mark milestones DONE.




