# Test Cleanup Plan — Reorganization, Deletion & Refactoring

> **Date:** April 5, 2026  
> **Scope:** All 269 test files audited for quality, placement, redundancy, and value  
> **This is Phase 0** of the testing overhaul. See `TESTING-WORKFLOW.md` for the full phased plan.  
> **For AI agents:** Before executing any action in this plan, read `TESTING-AGENT-PLAYBOOK.md` for conventions.

---

## Summary

The existing test suite has solid pockets (e2e flow tests, parser tests, analytics deep tests) mixed with a large amount of waste — tests that test arithmetic in isolation instead of production code, tests placed in wrong packages, and duplicated coverage. Cleaning this up before generating new tests will prevent confusion and conflicting patterns.

| Category | Files Affected | Action |
|----------|---------------|--------|
| **DELETE — Zero-value fake integration tests** | 6 files | Remove entirely |
| **DELETE — Assertion-less / print-only tests** | 2 tests in 2 files | Remove individual tests |
| **MOVE — Misplaced root-level tests** | 4 files | Move to correct packages |
| **REFACTOR — Outdated patterns (Mockito, runBlocking)** | 2 files | Migrate to MockK + runTest |
| **REFACTOR — Missing base class** | ~5 files | Extend AnalyticsEngineTestBase |
| **MERGE — Duplicate coverage** | 3 pairs | Consolidate into single files |
| **KEEP — High-value tests** | ~250 files | No action needed |

---

## 1. DELETE — Zero-Value "Integration" Tests (6 files, ~150 tests)

These files are labeled "integration" but test **no production code at all**. They perform inline arithmetic on local variables (e.g., `500.0 - 250.0 = 250.0`) and assert the result. They give a false sense of coverage while testing nothing from the codebase.

### Files to delete

| File | Tests | Why Delete |
|------|-------|------------|
| `integration/AnalyticsPipelineIntegrationTest.kt` | 20 of 21 | 20 tests are pure arithmetic (`val change = 500.0 - 400.0; assertEquals(100.0, change)`). No production code invoked. **Exception:** test `end_to_end_pipeline_preserves_effective_amount_and_filters` at line 364 is a real integration test. Extract it first. |
| `integration/BudgetCalculationPipelineIntegrationTest.kt` | 22 | All 22 tests do inline math (`val remaining = 500.0 - 250.0; assertEquals(250.0, remaining)`). Zero production code. |
| `integration/DataExportImportPipelineIntegrationTest.kt` | 20 | Tests string concatenation of CSV/JSON manually, not the actual export pipeline. Zero production code invoked. |
| `integration/RecurringExpenseDetectionPipelineIntegrationTest.kt` | 18 | Tests inline interval/variance calculations, not `RecurringExpenseEngine`. |
| `integration/InvestmentTrackingIntegrationTest.kt` | 5 | Tests inline arithmetic on `PortfolioSummary` fields. Also uses **Mockito** (deprecated in this project). |
| `integration/BankApiIntegrationTest.kt` | 5 | Mostly inline arithmetic. Also uses **Mockito**. |

### Agent instructions

1. **Extract** the single real test from `AnalyticsPipelineIntegrationTest.kt` (the `end_to_end_pipeline_preserves_effective_amount_and_filters` method, lines 364-478) into a new file: `integration/EffectiveAmountPipelineIntegrationTest.kt`
2. **Delete** all 6 files listed above
3. Total tests deleted: ~140 fake tests
4. Net effect: -140 tests, +0 real coverage lost

---

## 2. DELETE — Assertion-less / Print-only Tests (2 tests)

| File | Test | Why Delete |
|------|------|------------|
| `OcrDocumentTest.kt` line 834 | `print parser version info` | Prints a banner. No assertions. Zero value. |
| `CategorizationEngineTest.kt` line 102 | `cache invalidation resets cache` | Body is `engine.invalidateCache()` with comment "No assertion needed". This isn't a test. |

### Agent instructions

Delete just the individual `@Test` methods, not the entire files. Both files have other valid tests.

---

## 3. MOVE — Misplaced Root-Level Tests (4 files)

These files are in the root test package (`com.yourname.expensetracker`) instead of matching their production code package:

| File | Current Location | Should Be | Reason |
|------|-----------------|-----------|--------|
| `InsightsLogicTest.kt` | Root package | `domain/analytics/` or `domain/logic/` | Tests recurring interval logic, which lives in domain |
| `OcrParserTest.kt` | Root package | `domain/receipt/` | Tests `ReceiptParser` which is in `domain.receipt` |
| `OcrDocumentTest.kt` | Root package | `domain/receipt/` | Tests `ReceiptParser` which is in `domain.receipt` |
| `RegexVerificationTest.kt` | Root package | `domain/receipt/` or DELETE | Tests a standalone regex, not a production class. Consider deleting — the regex tested isn't even from a production file |

### Agent instructions

1. Move `OcrParserTest.kt` → `domain/receipt/ReceiptParserTest.kt` (rename to match class)
2. Move `OcrDocumentTest.kt` → `domain/receipt/ReceiptParserOcrPatternsTest.kt` (rename to be descriptive)
3. Move `InsightsLogicTest.kt` → `domain/analytics/RecurringIntervalLogicTest.kt` (rename to describe what it tests)
4. **Delete** `RegexVerificationTest.kt` — it tests a regex literal, not production code
5. Update `package` declarations in moved files

---

## 4. REFACTOR — Outdated Patterns (2 files)

These files use **Mockito** instead of **MockK**, which is the project standard:

| File | Issue | Fix |
|------|-------|-----|
| `integration/InvestmentTrackingIntegrationTest.kt` | Uses `@Mock`, `MockitoAnnotations.initMocks()` | ← Marked for deletion in Section 1, no refactor needed |
| `integration/BankApiIntegrationTest.kt` | Uses `Mockito.mock()`, `MockitoAnnotations.initMocks()` | ← Marked for deletion in Section 1, no refactor needed |

If these files survive deletion (e.g., if real tests are extracted from them), convert:
- `@Mock` → `mockk(relaxed = true)`
- `Mockito.mock(Foo::class.java)` → `mockk<Foo>(relaxed = true)`
- `MockitoAnnotations.initMocks(this)` → remove entirely

---

## 5. REFACTOR — Tests Using `runBlocking` Instead of `runTest` (1 file)

| File | Issue | Fix |
|------|-------|-----|
| `domain/categorization/CategorizationEngineTest.kt` | Uses `runBlocking` instead of `runTest` | Replace `runBlocking` with `= runTest {` for deterministic scheduling |

---

## 6. MERGE — Duplicate / Overlapping Test Files (3 pairs)

### Pair A: OCR Parser Tests

| File | Tests | Overlap |
|------|-------|---------|
| `OcrParserTest.kt` (root) | 15 | Tests decimal parsing, Greek normalization, OCR fixes on `ReceiptParser` |
| `OcrDocumentTest.kt` (root) | 42 | Tests the same `ReceiptParser` with more patterns |

**Recommendation:** After moving both to `domain/receipt/`, merge them into a single `ReceiptParserTest.kt`. The files test the same class with overlapping scenarios (e.g., both test European decimal parsing, both test ZYNOAO → ΣΥΝΟΛΟ). Deduplicate:
- `test decimal parsing - standard european` ↔ `test European decimal format - comma separator`
- `test decimal parsing - european with thousands separator` ↔ `test European format with thousands separator`
- `test decimal parsing - US standard` ↔ `test US decimal format - dot separator`
- `test decimal parsing - US with thousands separator` ↔ `test US format with thousands separator`
- `test greek normalization - Z error` ↔ `test OCR error - ZYNOAO (ΣΥΝΟΛΟ)`
- `test greek normalization - 2 error` ↔ `test OCR error - 2YNONO (ΣΥΝΟΛΟ)`
- `test date ocr fix - 16-D4-2017` ↔ `test date OCR fix - D instead of 0`

After dedup: ~40 unique tests in one file.

### Pair B: Analytics Stress Tests

| File | Tests | Overlap |
|------|-------|---------|
| `domain/analytics/AnalyticsStressTest.kt` | ? | Stress tests for analytics engines |
| `domain/analytics/AdvancedAnalyticsEngineTest.kt` | ? | Also has stress-like scenarios |

**Recommendation:** Review both files. If `AnalyticsStressTest` contains scenarios that overlap with `AdvancedAnalyticsEngineDeepTest` or `AdvancedAnalyticsEngineTest`, consolidate the unique scenarios into the relevant `*DeepTest` or `*StressTest` file.

### Pair C: AppParserRegistry Tests

| File | Tests | Overlap |
|------|-------|---------|
| `domain/parser/AppParserRegistryTest.kt` | ? | Parser registry tests |
| `domain/parser/AppParserRegistryRoutingTest.kt` | ? | Routing-specific tests |

**Recommendation:** These two files test the same class (`AppParserRegistry`). Merge `AppParserRegistryRoutingTest` into `AppParserRegistryTest` — routing IS the registry's core responsibility.

---

## 7. REFACTOR — Tests Missing Base Class (~5 files)

These test files mock `ExpenseDao`, `TimeProvider`, and `CategoryRepository` manually but could extend `AnalyticsEngineTestBase` to get all of that for free:

| File | Current Pattern | Suggested |
|------|----------------|-----------|
| `integration/AnalyticsPipelineIntegrationTest.kt` (the surviving real test) | Manual MockK setup | Extend `AnalyticsEngineTestBase` |
| `integration/MultiCurrencyAnalyticsTest.kt` | Manual MockK setup for DAO+TimeProvider | Extend `AnalyticsEngineTestBase` |
| `domain/health/FinancialHealthScoreV2Test.kt` | Manual MockK setup | Extend `AnalyticsEngineTestBase` |
| `domain/logic/SynthesisEngineTest.kt` | Manual MockK setup | Extend `AnalyticsEngineTestBase` |
| `domain/logic/SynthesisEngineStressTest.kt` | Manual MockK setup | Extend `AnalyticsEngineTestBase` |

### Agent instructions

For each file: Change `class XTest {` → `class XTest : AnalyticsEngineTestBase() {`, remove manual `expenseDao`/`timeProvider`/`categoryRepository` declarations, change `@Before fun setup()` → `@Before override fun setUp()`, call `super.setUp()` first, remove manual dispatcher rule if present.

---

## 8. REORGANIZE — Consistency Tests Package

The `consistency/` package is well-organized and contains **high-value** cross-component tests. **No changes needed.** These tests verify that different components produce the same results for the same input, which is exactly what's needed.

---

## 9. REORGANIZE — E2E Tests Package

The `e2e/` package has a **good** harness (`FlowPipelineTestHarness.kt`) and real pipeline tests. **No changes needed** except:

- The harness uses `Dispatchers.Unconfined` — consider switching to `testDispatcher` for determinism. This is a minor refactor, not urgent.

---

## 10. Execution Order for AI Agents

When asked to execute this cleanup plan:

1. **Extract** the single real test from `AnalyticsPipelineIntegrationTest.kt` into its own file
2. **Delete** the 6 fake integration test files (Section 1)
3. **Delete** the 2 assertion-less tests (Section 2)
4. **Delete** `RegexVerificationTest.kt` (Section 3)
5. **Move and rename** the 3 misplaced root-level files (Section 3)
6. **Merge** the OCR parser test pair into one file (Section 6, Pair A)
7. **Refactor** `CategorizationEngineTest.kt` to use `runTest` (Section 5)
8. **Refactor** the 5 files to extend `AnalyticsEngineTestBase` (Section 7)
9. Verify all tests still compile and pass

### Impact

| Metric | Before | After |
|--------|--------|-------|
| Total test files | 269 | ~260 |
| Total test methods | ~1400 | ~1260 |
| Fake / zero-value tests | ~142 | 0 |
| Misplaced files | 4 | 0 |
| Mockito usage | 2 files | 0 |
| Duplicate OCR tests | ~12 | 0 |
