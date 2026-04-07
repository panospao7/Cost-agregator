# AI & Location Test Bugs (B25d3)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **CloudReceiptAssistServiceTest.kt:52-78** | 🟡 Medium | Missing `isImageAnalysisMode` param | `ReceiptAssistInput` has `isImageAnalysisMode` field. Two inline constructions omit it, relying on default value `false`. | Use `sampleInput()` helper consistently or add `isImageAnalysisMode = false` explicitly. |
| **CloudReceiptAssistServiceTest.kt:110-136** | 🟡 Medium | Temp file leak on test failure | `createTempFile` writes bytes then relies on `finally` block to delete. If JVM crashes, temp file leaks. | Use `@TempDir` JUnit rule or `File.deleteOnExit()`. |
| **CloudCategorizationAssistServiceTest.kt:38** | 🟡 Medium | Coroutine test dispatcher mismatch | Tests use `= runBlocking { ... }`. This introduces real delays during retry backoff. | Replace with `= runTest { ... }` and inject `StandardTestDispatcher`. |
| **CloudDashboardBriefingServiceTest.kt:54,110,148,186** | 🟡 Medium | Same `runBlocking` issue with real delays | All retry tests use `= runBlocking { ... }`, causing actual `delay()` waits during retries. | Switch to `runTest` with virtual time. |
| **CloudReceiptAssistServiceTest.kt:140-191** | 🟡 Medium | Same `runBlocking` issue with real delays | Retry test at line 140 uses `runBlocking`, executing real `delay()` during backoff. | Switch to `runTest`. |
| **NominatimGeocodingServiceLocaleTest.kt:50-51** | 🟢 Low | Assertion fragility | Asserts `url.contains("lat=37.9838100")` — exactly 7 decimal places. | This is acceptable as a locale regression test. Consider asserting `!url.contains(",")` as additional guard. |
| **NominatimGeocodingServiceLocaleTest.kt:24-53** | 🔴 High | Global `Locale.setDefault()` pollutes JVM state | `Locale.setDefault(Locale("el", "GR"))` mutates JVM-global state. Even with `finally` restore, parallel tests can leak. | Run this test in its own forked JVM or refactor to inject locale. |
| **CompositeGeocodingServiceStressTest.kt:23** | 🟢 Low | `@Ignore` on entire stress test class | All tests are permanently ignored. They never run in CI and will silently rot. | Move to a dedicated `slowTest` source set or use JUnit5 `@Tag("stress")`. |
| **CompositeGeocodingServiceStressTest.kt:144-150** | 🟡 Medium | Race condition in `CancellationException` test | `coEvery` for `photonService.searchMultiple` is set **inside** `runBlocking` but the mock setup happens after `runBlocking {` opens. | Move `coEvery` setup before the `runBlocking` block. |
| **DailyBriefingWorkerTest.kt:68-78** | 🟡 Medium | `runTest` with Robolectric may not advance virtual time | `DailyBriefingWorker.doWork()` is a `CoroutineWorker` that runs on `Dispatchers.Default`. The test uses `runTest` but the worker's `doWork()` dispatches to a real dispatcher. | Inject a `CoroutineDispatcher` into the worker and override it with `StandardTestDispatcher` in tests. |
| **DailyBriefingWorkerTest.kt:82-90** | 🟢 Low | Test name says "no data" but uses non-empty `sampleProcessedData()` | Creates the same `sampleProcessedData()` as other tests. | Rename to `empty expenses triggers briefing generation`. |
| **DailyBriefingWorkerTest.kt:102-113** | 🟡 Medium | Test asserts `Result.success()` on engine failure but doesn't verify no-notification delivery | When `generateDashboardBriefingUseCase` throws, the test verifies the notification is **not** delivered. This is correct. However, the production `doWork()` catches all exceptions and returns `Result.success()`. | Consider verifying that the exception path is truly exercised. |
| **LocationBackfillWorkerTest.kt:84** | 🟡 Medium | Over-broad argument matcher mismatch | `coVerify(exactly = 0) { locationResolver.resolve(any(), any(), any(), any()) }` uses 4 `any()` args but the actual `resolve()` call signature uses named parameters. | Use the same signature: `coVerify(exactly = 0) { locationResolver.resolve(rawMerchantName = any(), transactionDateMs = any()) }`. |
| **HybridServiceDelegationTest.kt:186-233** | 🟢 Low | Disabled mode test stubs `noOpService` but also verifies `noOpService` was called | For `AiRoute.DISABLED`, the test correctly stubs and verifies `noOpService` is called. However, line 230-232 verifies `aiSettingsRepository.settings()` was called `exactly = 1` for each harness. | Remove the `verify(exactly = 1)` lines or consolidate harnesses to share a single settings repo mock. |
| **ContextualInferenceEngineStressTest.kt:645-658** | 🟡 Medium | Flaky `createTimestamp` helper | `cal.add(Calendar.DAY_OF_YEAR, daysDiff)` adjusts the current date to the requested day. If today is Sunday and `dayOfWeek=Calendar.SATURDAY`, `daysDiff=6` pushes into next week. | Use a fixed base date (e.g., `cal.set(2026, Calendar.MARCH, 2)` — a known Monday) before adjusting day-of-week. |
| **ContextualInferenceEngineStressTest.kt:615-628** | 🟢 Low | Performance test uses wall-clock `System.nanoTime()` | `stress - process 1000 inferences quickly` uses wall-clock time with a 1-second threshold. | Increase the threshold to 5s or use `@Timeout` annotation. |
| **CategorizationEngineStressTest.kt:77-110** | 🟡 Medium | Stress test creates 10,000 threads | Creates `Executors.newFixedThreadPool(10)` with 10,000 tasks. On resource-constrained CI, the 60-second timeout could be hit. | Reduce to 1,000 tasks or use coroutine-based concurrency with `runTest`. |
| **CategorizationEngineStressTest.kt:112-128** | 🟡 Medium | Cache expiry test is incomplete | Test says "at exactly 300s, cache should expire" but then just documents expected behavior in a comment. | Either inject a `Clock` and advance it to 300s, or remove the misleading test name. |
| **CategorizationEngineStressTest.kt:607-621** | 🟢 Low | Dead assertion `assertTrue("...", true)` | Always passes regardless of elapsed time. | Either add a real time threshold or mark as `@Ignore`. |
| **CategorizationComponentsTest.kt:155-157** | 🟡 Medium | Levenshtein distance test has wrong expected value | Test asserts `levenshteinDistance("hello", "hola") == 3`. | No fix needed — values are correct upon re-verification. |
| **CategorizationComponentsTest.kt:117** | 🟡 Medium | Greek-to-Latin assertion assumes specific transliteration rules | `assertEquals("Sklavenitis", normalizer.toLatin("Σκλαβενίτης"))` | No code fix needed, but this is a documentation note. |
| **GeocodingRetryHttpSemanticsTest.kt:88** | 🟢 Low | Redundant assertion | `assertTrue(failure.error != GeocodingError.NetworkError)` — line 87 already asserts `assertEquals(GeocodingError.RateLimited, failure.error)`. | Remove the redundant assertion at line 88. |
| **CloudQueryInterpretationServiceTest.kt:134-143** | 🟢 Low | Test input omits `categoryNames`/`merchantNames` | The third test creates `FinancialQueryInterpretationInput` without `categoryNames` or `merchantNames`. | Add a test that captures the request body and verifies `categoryNames`/`merchantNames` are included. |
| **CloudDedupeJudgeServiceTest.kt:100** | 🟢 Low | Float comparison without delta | `assertEquals(0.93f, success.value.confidence)` — two-arg `assertEquals` for floats is deprecated. | Use `assertEquals(0.93f, success.value.confidence!!, 0.001f)`. |
| **CloudReceiptItemCategorizationServiceTest.kt:28** | 🟢 Low | Return value of `categorizeItems` not asserted | The first test calls `service.categorizeItems(input)` but doesn't assert on the return value. | Add `assertNotNull(result)` after line 116. |
| **All `OnDevice*ServiceTest.kt` files** | 🟢 Low | Missing edge case: very large input strings | On-device inference may have token limits. | Add a test with a very long `rawOcrText` (e.g., 50,000 chars). |
| **SmartReceiptAssistServiceTest.kt:27-87** | 🟢 Low | `usedImageInput` never verified | The `Smart*` wrapper delegates to either cloud or on-device, but no test verifies that `usedImageInput()` is correctly delegated. | Add a test that verifies `SmartReceiptAssistService.usedImageInput()` delegates to the correct provider. |
| **HybridReceiptItemCategorizationServiceTest.kt** | 🟢 Low | Only one routing path tested | Only tests `AiRoute.ON_DEVICE`. Missing tests for `CLOUD`, `DETERMINISTIC_FALLBACK`, and `DISABLED` routes. | Add tests for all four routing decisions. |

## Summary

| Severity | Count |
|----------|-------|
| 🔴 High | 1 |
| 🟡 Medium | 13 |
| 🟢 Low | 12 |

### Root Causes

1. **Global state mutation** (1 high): `Locale.setDefault()` in `NominatimGeocodingServiceLocaleTest` can corrupt parallel test runs.

2. **`runBlocking` instead of `runTest`** (4 medium): Cloud service retry tests use `runBlocking`, causing real `delay()` waits during retries.

3. **Incomplete test coverage** (3 medium): Cache expiry boundary never tested, `HybridReceiptItemCategorizationServiceTest` only tests one route, `SmartReceiptAssistService.usedImageInput` never verified.

4. **Flaky test infrastructure** (3 medium): Calendar-based `createTimestamp` depends on current date, thread-pool stress tests may exhaust CI resources, performance assertions use wall-clock time.

5. **Mock verification mismatches** (1 medium): `LocationBackfillWorkerTest` uses 4-arg `any()` matcher that may not match the 2-arg production call signature.

### Recommended Priority Fixes

1. **Fix the `Locale.setDefault` test** — isolate it in a forked JVM or inject locale
2. **Switch retry tests from `runBlocking` to `runTest`** — eliminates real delays and flakiness
3. **Fix `createTimestamp` helper** — use fixed base date to avoid calendar-dependent failures
4. **Add `assertEquals` float delta** in `CloudDedupeJudgeServiceTest:100` — prevent potential float precision failure
