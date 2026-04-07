# Debugger Analysis - Batch 24: Models & Data Classes Tests

**Analyzed files:**
- `domain/model/dashboard/DashboardExpenseMapperTest.kt`
- `domain/model/PeriodTotalTest.kt`
- `domain/model/CategoryBreakdownTest.kt`
- `domain/ai/model/OnDeviceRuntimePresentationTest.kt`
- `domain/ai/model/AiRuntimeStatusModelsTest.kt`
- `domain/ai/model/AiArtifactPresentationTest.kt`

**Source models reviewed:**
- `DashboardPrimitives.kt`, `DashboardExpenseMapper.kt`, `Expense.kt`, `MerchantKeyGenerator.kt`
- `PeriodTotal.kt`, `CategoryBreakdown.kt`, `CategoryInfo.kt`
- `AiModels.kt`, `OnDeviceRuntimePresentation.kt`, `AiRuntimeStatusModels.kt`, `AiArtifactPresentation.kt`, `AiArtifactEntity.kt`

---

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| `DashboardExpenseMapperTest.kt:25-27` | Medium | Assertion Bug / Lossy Round-Trip | The test maps `Expense -> DashboardExpense -> Expense` and asserts all fields match, but `DashboardExpense` **does not carry** many `Expense` fields (e.g., `currency`, `paymentMethod`, `rawNotificationId`, `dedupeKey`, `isSharedExpense`, `myShareAmount`, `mySharePercentage`, `transferDirection`, location fields, business fields, `notes`, `createdAt`). The round-trip `toEntityExpense()` reconstructs an `Expense` with default values for all of these. The test **does not assert** that these fields are lost, giving a false sense of round-trip fidelity. For instance, `isManualEntry=true` is set on the original but the assertion on line 36 passes only because the fixture explicitly maps it. However, `isSharedExpense`, `myShareAmount`, `paymentMethod` are silently dropped. | Add explicit assertions that document the fields NOT preserved by the round-trip (e.g., `assertFalse(mapped.isSharedExpense)`, `assertEquals(PaymentMethod.UNKNOWN, mapped.paymentMethod)`), or rename the test to clarify it only checks the subset mapped by `DashboardExpense`. |
| `DashboardExpenseMapperTest.kt:55-56` | Low | Weak Assertion / Tautology | Lines 55-56 construct a local variable `categoryLabel` from the already-asserted-null `mapped.categoryId` and then assert it equals `"Uncategorized"`. This is a tautological test of the test's own inline logic, not of any production code. No production `"Uncategorized"` fallback is being validated. | Remove the local variable test or replace it with an assertion against the actual production fallback path (if one exists in the UI layer). |
| `DashboardExpenseMapperTest.kt:73-74` | Medium | Misleading Test Setup — amountOverride hides real mapper behavior | The `sharedExpense effectiveAmount` test manually passes `amountOverride = sharedExpense.effectiveAmount` to the fixture, then asserts the mapped amount is 30.0. This proves that the fixture copies the override, not that the production mapper correctly uses `effectiveAmount` instead of `amount`. The real `DashboardExpenseMapper` is never invoked — only the test fixture is. | Either (a) test the actual production `toDashboardExpense()` mapper from the codebase (if one exists), or (b) document in the test name that this only validates the fixture's override behavior and is not an integration test of the real mapping. |
| `DashboardExpenseMapperTest.kt:37` | Low | Fragile Assertion — depends on MerchantKeyGenerator internals | The test asserts `assertEquals(MerchantKeyGenerator.generate(expense.merchant), mapped.merchantKey)` which duplicates production logic in the assertion. If `MerchantKeyGenerator` has a bug, this test would pass despite incorrect output. | Assert against a known expected string literal (e.g., `assertEquals("metromarket", mapped.merchantKey)`) to decouple from production internals. |
| `DashboardExpenseMapperTest.kt:104-116` | Low | Missing Edge-Case Coverage | The `toDashboardExpenseFixture` helper does not handle `effectiveAmount` correctly — it always takes the raw `effectiveAmount` from `Expense` (the computed property) regardless of the `amountOverride`. So the `DashboardExpense.effectiveAmount` can differ from `DashboardExpense.amount` silently. No test verifies that `DashboardExpense.effectiveAmount` is correct after mapping. | Add assertions on `dashboardExpense.effectiveAmount` in each test to ensure it matches expectations. |
| `PeriodTotalTest.kt:32-45` | Low | Non-Test / Tautology | The `PeriodTotal is immutable` test only asserts `assertTrue(period is PeriodTotal)` — this is an always-true type check, not an immutability test. Data classes in Kotlin are not inherently immutable if they contain mutable collections (though this one is fine with primitives). The test name is misleading and provides zero value. | Either remove this test or replace it with a meaningful immutability check (e.g., verify that modifying a copy does not affect the original). |
| `PeriodTotalTest.kt:106-111` | Medium | Fragile / Brittle — Ordinal Assertions | Tests assert exact ordinal positions of enum values (`assertEquals(0, PeriodStatus.UNDER_AVERAGE.ordinal)`). If a developer inserts a new enum value before existing ones, this test breaks for no functional reason. Ordinals are an implementation detail unless they are used for serialization. | Remove ordinal assertions unless ordinals are explicitly relied on for persistence/serialization. If they are, add a comment explaining why. |
| `PeriodTotalTest.kt:124-128` | Medium | Fragile / Brittle — Ordinal Assertions | Same issue as above for `PeriodType` ordinals. | Same fix as above. |
| `PeriodTotalTest.kt:96-103` | Low | Hardcoded Count — Fragile if Enum Evolves | `assertEquals(4, values.size)` will break as soon as a new `PeriodStatus` variant is added. This is intentional as a "change detector" but the test name doesn't communicate that purpose. | Rename to `PeriodStatus enum acts as change detector for serialization` or remove if not needed. |
| `PeriodTotalTest.kt:16` | Info | Hardcoded Timestamps Lack Context | `startDateMs = 1735689600000L` — no comment explaining what date this represents. Makes the test hard to maintain. | Add inline comments: `// 2026-01-01T00:00:00Z` etc. |
| `CategoryBreakdownTest.kt:34-51` | Low | Non-Test / Tautology | Same pattern as `PeriodTotalTest`: `is immutable` test only asserts `assertTrue(breakdown is CategoryBreakdown)`, which is always true. | Remove or replace with a real immutability test. |
| `CategoryBreakdownTest.kt:160` | Low | toString Assertion Fragility | `assertTrue(str.contains("Groceries"))` depends on `toString()` format from Kotlin data class. If a field is renamed or the class stops being a `data class`, these break. This is low-risk but worth noting. | Acceptable for data class smoke tests, but consider asserting structured field access instead. |
| `OnDeviceRuntimePresentationTest.kt` | Medium | Missing Coverage — 4 of 8 Statuses Not Tested | `OnDeviceModelStatus` has 8 values: `AVAILABLE`, `NOT_INSTALLED`, `DOWNLOADING`, `UNAVAILABLE`, `UNSUPPORTED_DEVICE`, `UNSUPPORTED_ANDROID_VERSION`, `DISABLED_BY_POLICY`, `UNKNOWN`. Only 4 are tested. Missing: `DOWNLOADING`, `UNSUPPORTED_DEVICE`, `DISABLED_BY_POLICY`, `UNKNOWN`. | Add tests for all 8 statuses to achieve full branch coverage of `toRuntimeStatusMessage()`. |
| `OnDeviceRuntimePresentationTest.kt:17` | Info | String Literal Coupling | Tests hardcode expected messages as exact string literals. Any copy change in production will break the test. This is intentional for presentation correctness but creates maintenance burden. | Consider extracting message templates into constants shared between production and test. |
| `AiRuntimeStatusModelsTest.kt` | Medium | Missing Coverage — Partial Route Testing | `routeDisplayText()` handles 5 cases: `ON_DEVICE`, `CLOUD`, `DETERMINISTIC_FALLBACK`, `DISABLED`, `null`. Only `CLOUD` and `null` are tested. Missing: `ON_DEVICE`, `DETERMINISTIC_FALLBACK`, `DISABLED`. | Add tests for all route values, including with/without provider/model name combinations. |
| `AiRuntimeStatusModelsTest.kt` | Low | Missing Edge-Case — Blank provider/model strings | `routeDisplayText()` uses `takeIf { it.isNotBlank() }` to filter provider/model names. No test verifies that blank strings (`""`, `" "`) are correctly excluded from display text. | Add test: `providerName = "  "`, `modelName = ""` and assert output is just `"Cloud"`. |
| `AiArtifactPresentationTest.kt` | Low | Missing Coverage — Null provider/model in diagnostics | `toDiagnosticsOrNull` passes `provider` and `modelName` through to `AiArtifactDiagnostics`. `toDisplayText()` filters blanks via `takeIf { it.isNotBlank() }`. No test verifies behavior when `provider` or `modelName` is null. | Add test with `provider = null`, `modelName = null` and assert display text is just `"Cloud"` or `"On-device"`. |
| `AiArtifactPresentationTest.kt:53-67` | Info | Only AUTO Returns Null — Incomplete Mode Coverage | The test for `toDiagnosticsOrNull returns null for auto mode` correctly validates `AiMode.AUTO -> null`, but does not test all non-null modes exhaustively (only `CLOUD` and `ON_DEVICE` are tested in separate tests, which is good). | No change needed — just noting for completeness. |
| All Test Files | Low | Missing Negative / Edge-Case Tests | No test validates behavior with extreme values: negative amounts, `Double.MAX_VALUE`, `Long.MIN_VALUE` for timestamps, empty strings for merchant/label, extremely long strings, or `NaN`/`Infinity` for doubles. For data classes used in financial calculations, edge-case coverage matters. | Add edge-case tests: negative amounts, zero-length strings, `Double.NaN`, `Double.POSITIVE_INFINITY`, max/min long timestamps. |
| All Test Files | Info | No data/model Test Files Found in `data/model/` | The batch specification mentions `data/model/` but no test files exist in that path. Either tests are missing or the path is incorrect. | Verify whether `data/model/` classes exist and need tests. If they have models, add tests. |
| `DashboardExpenseMapperTest.kt:61` | Low | Test Uses `id: Long? = null` with `System.currentTimeMillis()` Fallback | In `createExpense()` (TestUtils.kt:61), when `id` is null, `System.currentTimeMillis()` is used. This introduces non-determinism. The `DashboardExpenseMapperTest` always provides explicit IDs which avoids this, but the `createExpense` helper has a latent flaky-test risk for other callers. | Change default to a deterministic counter or require explicit IDs. |
| `CategoryBreakdownTest.kt` | Info | No Test for `percentageOfTotal > 100` | No test validates behavior when `percentageOfTotal` exceeds 100.0f (e.g., due to rounding errors in production aggregation). Since `CategoryBreakdown` is a plain data class with no validation, it silently accepts >100% values. | Add a test documenting that >100% is accepted (no validation), or add validation to the model. |

---

## Summary

| Severity | Count |
|----------|-------|
| Medium | 6 |
| Low | 10 |
| Info | 5 |

---

## Verdict

**PASS_WITH_NOTES**

All six test files are structurally sound and will compile and pass without flakiness. No blocking bugs, no race conditions, no incorrect mock setups (no mocks are used — these are pure data class tests). The tests correctly validate core data class behavior (construction, copy, equals/hashCode, toString).

**Key concerns that prevent a clean PASS:**

1. **Incomplete branch coverage** (Medium): `OnDeviceRuntimePresentationTest` covers only 4/8 enum branches; `AiRuntimeStatusModelsTest` covers only 2/5 route branches. This is the most actionable gap — untested branches could harbor message typos or logic errors that slip to production.

2. **Fragile ordinal assertions** (Medium): `PeriodTotalTest` hardcodes enum ordinal values, creating unnecessary coupling to declaration order that will break on any enum evolution.

3. **Tautological "immutability" tests** (Low): Two tests named "is immutable" only assert `assertTrue(x is Type)`, providing zero test value and misleading coverage reports.

4. **Round-trip fidelity gap** (Medium): `DashboardExpenseMapperTest` gives false confidence about Expense round-tripping — many fields are silently dropped and not asserted upon.

5. **No edge-case tests for financial data**: No negative amounts, NaN, infinity, or boundary values are tested on data classes that carry financial amounts.

None of these issues cause test failures or false positives in current code, but they represent gaps that could mask real regressions.
