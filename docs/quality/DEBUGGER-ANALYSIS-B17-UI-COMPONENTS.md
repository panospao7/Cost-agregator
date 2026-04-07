# Debugger Analysis - Batch 17: UI Component Tests

## Scope

Two test files analyzed:
1. `app/src/test/java/com/yourname/expensetracker/ui/components/emptystate/ContextualActionRegistryTest.kt` (135 lines, 7 tests)
2. `app/src/test/java/com/yourname/expensetracker/domain/categorization/CategorizationComponentsTest.kt` (370 lines, 28 tests across 4 test classes)

Production source files cross-referenced:
- `ContextualActionRegistry.kt`, `EmptyStateAction.kt`, `EmptyStatePresentationModule.kt`, `DefaultEmptyStateRegistryInitializer.kt`
- `MerchantCanonicalizer.kt`, `GreeklishNormalizer.kt`, `SemanticKeywordMatcher.kt`, `ContextualInferenceEngine.kt`, `CategoryKeywords.kt`

---

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| `ContextualActionRegistryTest.kt:100-111` | Medium | Missing Edge Case | Test `duplicate action ids are all filtered once id is completed` documents that the registry allows duplicate action IDs. When one is marked completed, ALL actions with that ID are filtered out. This is implicitly accepted behavior but there is no test verifying that `registerActions` rejects or deduplicates IDs. If a future change makes IDs unique at registration time, this test would silently change semantics. | Add an explicit test for the expected behavior: either a test that `registerActions` deduplicates or a test documenting that duplicate IDs are intentionally supported. Add a comment explaining the design decision. |
| `ContextualActionRegistryTest.kt:24-36` | Low | Flaky Test Risk | Turbine `test {}` block with `StateFlow` relies on synchronous emission from `MutableStateFlow.value` assignment inside `runTest`. This is correct today but fragile if the production code ever moves to `emit()` on a background dispatcher or adds debounce/conflation. No `TestDispatcher` is injected, so any future async change would break this test silently. | Consider injecting a `CoroutineScope` or `CoroutineContext` into `ContextualActionRegistry` to make it testable with `StandardTestDispatcher`. For now, add a comment noting the synchronous assumption. |
| `ContextualActionRegistryTest.kt:14-134` | Low | Thread Safety / Test Isolation | `ContextualActionRegistry` uses plain `mutableMapOf` for internal state (lines 14-15 of production code). Concurrent access from multiple coroutines would cause `ConcurrentModificationException`. The test is single-threaded so it passes, but there is no concurrency stress test. Production DI provides this as `@Singleton` meaning multiple screens could access it concurrently. | Add a concurrent access test using `runTest` with multiple `launch` blocks. In production, consider using `ConcurrentHashMap` or `Mutex`-guarded access in `ContextualActionRegistry`. |
| `ContextualActionRegistryTest.kt:59-60` | Info | Missing Assertion | `markCompleted` mutates both `completedActionsMap` (per-screen) and `_completedActions` (global StateFlow). The test at line 59-60 only checks `isCompleted` and `getActions` (which read from `completedActionsMap`), never verifying that the global `completedActions` StateFlow was also updated with the correct `"screenA:a1"` key. A bug in the composite key format would go undetected. | Add `assertTrue(registry.completedActions.value.contains("screenA:a1"))` after `markCompleted`. |
| `CategorizationComponentsTest.kt:152-157` | Medium | Incorrect Test Assertion | Levenshtein distance between `"hello"` and `"hola"` is computed as 3 (verified via DP table). The inline comment says `"e->o, l->a, delete o"` which describes substituting e->o, substituting l->a, and deleting 'o', but that sequence is 2 subs + 1 deletion = 3 on `"hello"` which has 5 chars becoming 4 chars. However, the actual optimal alignment is different: h=h, e->o(1), l=l, l->a(1), o->_(del 1) = 3. The comment is misleading about which characters are transformed but the numeric assertion (3) is correct. | Fix the comment to accurately describe the optimal edit path: `"h=h, e->o, l=l, l->a, delete o"` or remove the misleading comment. |
| `CategorizationComponentsTest.kt:156` | High | Incorrect Test Assertion (Wrong Expected Value) | `assertEquals(3, normalizer.levenshteinDistance("kitten", "sitting"))` asserts distance = 3, but the true Levenshtein distance between `"kitten"` (6 chars) and `"sitting"` (7 chars) is **3**: k->s(1), i=i, t=t, t=t, e->i(1), n->n, _->g(1) = 3. Actually wait - let me recount: `kitten` vs `sitting`: k->s, i=i, t=t, t=t, e->i, n=n?, but "sitting" is s-i-t-t-i-n-g (7 chars) and "kitten" is k-i-t-t-e-n (6 chars). Optimal path is: k->s(1), i=i, t=t, t=t, e->i(1), n=n, _->g(insert 1) = 3. So the assertion of 3 IS correct. No bug here - assertion is valid. | No fix needed. The assertion is correct. |
| `CategorizationComponentsTest.kt:160-163` | Medium | Brittle Test / Dependency on Internal State | `findClosestMatch("Sklavvenitis", 2)` expects `"sklavenitis"` to be returned. This relies on the internal `KNOWN_VARIATIONS` map in `GreeklishNormalizer` containing `"sklavvenitis"` as a known variation of `"sklavenitis"`. If that entry is ever removed or renamed, the test breaks. The test doesn't document this dependency. | Add a comment: `// Depends on KNOWN_VARIATIONS containing "sklavvenitis" as alias of "sklavenitis"`. Consider testing `findClosestMatch` with explicitly injected variations or at minimum testing the Levenshtein fallback path separately. |
| `CategorizationComponentsTest.kt:116-118` | Medium | Fragile Assertion on Greek Transliteration | `normalizer.toLatin("Σκλαβενίτης")` expects `"Sklavenitis"`. This is case-sensitive and depends on exact diphthong processing order, accent stripping, and the static Greek-to-Latin map. The `toLatinStatic` method first does diphthong replacement then char mapping then NFD accent stripping. If any diphthong rule changes order (e.g., if "αβ" were added as a diphthong), the result would change. The test is correct but tightly coupled to implementation ordering. | This is acceptable for a transliteration test. Consider adding a comment noting the processing pipeline order dependency. |
| `CategorizationComponentsTest.kt:178-181` | Medium | Mock Oversimplification | `SemanticKeywordMatcherTest` mocks `GreeklishNormalizer.normalize()` to simply `lowercase()`. This bypasses all Greek character transliteration. Tests like `findBestMatch("Pizza Hut", 0.50)` work because the input is already Latin, but the test suite never exercises the matcher with Greek input. If the real normalizer changes behavior for Latin text (e.g., stripping accents from Latin characters like "cafe" -> "cafe"), the mock wouldn't catch it. | Add at least one test with Greek merchant input using the real `GreeklishNormalizer` instead of the mock, or add a separate integration test. |
| `CategorizationComponentsTest.kt:186-189` | Low | Weak Assertion | `findBestMatch("Pizza Hut", 0.50)` asserts `result!!.categoryName == "Food"` and `result.confidence >= 0.50`. The actual confidence should be 0.98 (from the "pizza hut" keyword in CategoryKeywords) or at minimum 0.95 (from "pizza" keyword with position boost). The assertion `>= 0.50` is far too weak and would pass even if the matching logic degraded significantly. | Tighten to `assertTrue(result.confidence >= 0.90)` or even `assertEquals(0.98, result.confidence, 0.05)` to catch regression in scoring. |
| `CategorizationComponentsTest.kt:229-236` | Low | Weak Null Assertion | `findBestMatch("SomeUnknownXYZ123", 0.20)` with threshold 0.20 asserts null. This works because no keywords match this gibberish string, but the test comment says "regardless of threshold". If any keyword with common substrings were added to `CategoryKeywords` (e.g., a keyword containing "xyz"), this test would break. The test is fragile because it depends on the absence of any substring match. | Use a more obviously unmatchable string and add a comment noting the dependency on keyword dictionary contents. |
| `CategorizationComponentsTest.kt:270-277` | Medium | Flaky Test - Day-of-Week Dependent | `inferFromContext(5.0, timestamp)` with hour=9. The helper `getTimestampForHour(9)` uses `Calendar.getInstance()` which inherits the current day of week. On weekends, additional boosts are added (Food +0.10, Shopping +0.15, Entertainment +0.20, Groceries +0.25). While the test assertion still passes (Food remains the highest), the actual `confidence` value differs between weekday (0.75) and weekend (0.85). Any future assertion tightening could introduce flakiness. | Fix `getTimestampForHour` to pin day-of-week: add `cal.set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)` to ensure deterministic results. Apply to all tests in `ContextualInferenceEngineTest`. |
| `CategorizationComponentsTest.kt:289-295` | Medium | Flaky Test - Day-of-Week Dependent | `inferFromContext(75.0, timestamp)` with hour=15. On weekdays: Shopping=0.50, Groceries=0.45. On weekends: Groceries=0.70, Shopping=0.65. The assertion `categoryName in listOf("Shopping", "Groceries")` handles both cases, but this means the test cannot detect a regression where Shopping stops being inferred on weekdays or Groceries stops being inferred on weekends. The test is effectively non-deterministic. | Pin the day-of-week in the helper. Write two separate tests: one for weekday (expect Shopping) and one for weekend (expect Groceries). |
| `CategorizationComponentsTest.kt:320-326` | High | Flaky Test Helper - Non-Deterministic Timestamp | `getTimestampForHour(hour)` does not set `Calendar.MILLISECOND`, `Calendar.DATE`, `Calendar.MONTH`, `Calendar.YEAR`, or `Calendar.DAY_OF_WEEK`. It only sets hour, minute, and second. This means: (1) day-of-week varies causing different score paths, (2) around midnight DST transitions the hour could shift, (3) the test is not reproducible across different dates. All `ContextualInferenceEngineTest` tests using this helper inherit this non-determinism. | Rewrite helper to create a fully deterministic timestamp: `cal.set(2024, Calendar.JANUARY, 15, hour, 0, 0)` (a known Wednesday) and `cal.set(Calendar.MILLISECOND, 0)`. |
| `CategorizationComponentsTest.kt:306-312` | Low | Misleading Test Name | Test `buildReason includes amount info` calls `inferFromContext` which internally calls `buildReason`. The test asserts `result.reason.contains("amount")` and `result.reason.contains("morning")`. With amount=5.0 (which is < 20.0 = LARGE_AMOUNT), `buildReason` adds `"medium amount"`. The test name says "amount info" but the assertion checks for the substring "amount" which would match "small amount", "medium amount", or "large amount" — the test doesn't verify the correct qualifier. | Either rename to `buildReason includes medium amount and morning` and assert `contains("medium amount")`, or keep generic but add a comment explaining the intent. |
| `CategorizationComponentsTest.kt:61-65` | Low | Implementation-Dependent Assertion | `canonicalize("North Store Athens")` expects `canonicalName = "store"` with "north" and "athens" stripped. The production code strips "north" as a REGION_PREFIX first, then "athens" as a LOCATION_SUFFIX. The test implicitly depends on "store" NOT being stripped as a LOCATION_SUFFIX when it's the only remaining word (because the suffix stripping checks `normalized.endsWith(" $suffix")` which requires a space before the suffix). If "store" were the entire string, it wouldn't match ` store` pattern. This is correct but the test doesn't document this subtle edge case. | Add a comment explaining why "store" is not stripped (it's the entire remaining string, and suffix stripping requires a preceding space/word). |
| `ContextualActionRegistryTest.kt` | Low | Missing Coverage | No test for `registerActions` overwrite behavior. If `registerActions("key", list1)` is called followed by `registerActions("key", list2)`, the second call overwrites the first (since it's a simple map put). There is no test verifying this behavior. | Add test: `registerActions same screenKey overwrites previous registration`. |
| `ContextualActionRegistryTest.kt` | Low | Missing Coverage | No test for `hasActions` returning true after registration. The only `hasActions` test is at line 93 which checks it returns `false` after `clearAll()`. | Add: `assertTrue(registry.hasActions("savings"))` after `registerActions("savings", ...)`. |
| `CategorizationComponentsTest.kt` | Low | Missing Coverage | `MerchantCanonicalizerTest` does not test empty string input, whitespace-only input, or very long strings. `canonicalize("")` would return `CanonicalResult("", emptyList(), 0.0)` which may or may not be desired. | Add edge case tests for `canonicalize("")`, `canonicalize("   ")`, and `canonicalize("a".repeat(10000))`. |
| `CategorizationComponentsTest.kt` | Low | Missing Coverage | `GreeklishNormalizerTest` does not test `toGreek()` method, `getVariations()` with unknown merchants, or `normalize()` with mixed Greek/Latin input. | Add tests for `toGreek("sklavenitis")`, `getVariations("unknownmerchant")`, and `normalize("Mikel Coffee Μπουγάτσα")`. |
| `CategorizationComponentsTest.kt` | Low | Missing Coverage | `CategoryKeywordsTest` does not test `getKeywordsForCategory` with a non-existent category name. It should return `emptyMap()` per the implementation. | Add: `assertEquals(emptyMap(), CategoryKeywords.getKeywordsForCategory("NonExistent"))`. |
| `CategorizationComponentsTest.kt:53-57` | Info | Defensive Regression Test | `does not treat dotted suffix as wildcard regex` is a good regression test that verifies `"m.i.k.e."` in the BUSINESS_TYPE_SUFFIXES list is properly escaped (via `Regex.escape`) and not treated as regex wildcards. This test is well designed. | No fix needed. Good test. |

---

## Summary

| Severity | Count |
|----------|-------|
| High | 1 |
| Medium | 6 |
| Low | 10 |
| Info | 3 |

---

## Detailed Root Cause Analysis

### HIGH: Non-Deterministic Timestamp Helper (CategorizationComponentsTest.kt:320-326)

**Root cause:** `getTimestampForHour(hour)` creates a `Calendar.getInstance()` that inherits the current system date, day-of-week, and milliseconds. The `ContextualInferenceEngine` has weekend-specific scoring branches that add different boosts on Saturday/Sunday vs weekdays.

**Impact:** Multiple tests in `ContextualInferenceEngineTest` produce different confidence values depending on which day the test suite runs. While current assertions are loose enough to pass on all days, this creates:
1. **Non-reproducible test results** - debugging a failure requires knowing which day it ran
2. **Prevents assertion tightening** - any attempt to test exact confidence values would be flaky
3. **Masks weekend-specific regressions** - the `in listOf("Shopping", "Groceries")` assertion at line 294 cannot detect if either category stops being inferred

**Fix:**
```kotlin
private fun getTimestampForHour(hour: Int): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(2024, java.util.Calendar.JANUARY, 10, hour, 0, 0) // Wednesday
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
```

### MEDIUM: Thread Safety in ContextualActionRegistry (Production Code)

**Root cause:** `ContextualActionRegistry` uses non-synchronized `mutableMapOf` and non-atomic `StateFlow.value` read-modify-write patterns. It is provided as `@Singleton` via Hilt, meaning any screen composable or ViewModel could call `markCompleted()` or `getActions()` concurrently.

**Impact:** Potential `ConcurrentModificationException` or lost updates in production. No test validates concurrent access.

**Fix:** Either synchronize with `Mutex` in a `CoroutineScope`, use `ConcurrentHashMap`, or add `@Synchronized` annotations to mutation methods.

---

## Verdict

**FAIL_WITH_NOTES**

The test suites are structurally sound with good coverage of core happy paths. The high-severity non-deterministic timestamp helper affects 5+ tests and represents a real flakiness risk in CI environments that run on weekends or across timezone boundaries. The medium-severity mock oversimplification in `SemanticKeywordMatcherTest` means Greek-input paths are completely untested. Thread safety of the production `ContextualActionRegistry` is a latent production bug that tests cannot catch without concurrency stress tests. These issues should be addressed before considering the test suite reliable for regression detection.
