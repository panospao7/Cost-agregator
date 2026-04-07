# Repository Test Bugs (B25d1)

## Findings

| File:Line | Severity | Type | Description | Suggested Fix |
|---|---|---|---|---|
| **RecommendationRepositoryTest.kt:43** | 🔴 High | Coroutine Dispatcher Leak | `Dispatchers.setMain(testDispatcher)` is called in `@Before` but `Dispatchers.resetMain()` is **never called** in `@After`. This permanently mutates the global `Dispatchers.Main` for all subsequent test classes. | Add `@After fun tearDown() { Dispatchers.resetMain() }` |
| **RecommendationRepositoryTest.kt:283,304** | 🟡 Medium | Flaky Test / Non-determinism | `System.nanoTime()` is used as default ID. Two calls in rapid succession can produce the same nanoTime on some JVMs (Windows timer resolution). | Use `UUID.randomUUID().toString()` or a deterministic counter for IDs. |
| **RecommendationRepositoryTest.kt:92,104** | 🟡 Medium | Flaky Test / Non-determinism | `expireAll` and `expireOld` tests use `System.currentTimeMillis()` for the `timestamp` parameter. If the clock ticks between the `val timestamp` line and the actual repository call, the mock may never match. | Use a fixed constant or the `FakeTimeProvider` already available. |
| **RecommendationRepositoryTest.kt:316-319** | 🟡 Medium | Flaky Test / Non-determinism | `createRecommendationEntity()` uses `System.currentTimeMillis()` for `createdAt`, `updatedAt`, and `expiresAt`. | Use the `FakeTimeProvider` value for consistent entity creation. |
| **GroupsRepositoryImplTest.kt:43** | 🔴 High | Test Isolation / Resource Leak | `mockkStatic("androidx.room.RoomDatabaseKt")` is called in `@Before` but there is **no `@After`** that calls `unmockkStatic(...)`. The static mock leaks into other test classes. | Add `@After fun tearDown() { unmockkStatic("androidx.room.RoomDatabaseKt") }` |
| **ReviewQueueRepositoryTest.kt:47** | 🔴 High | Test Isolation / Resource Leak | `mockkStatic("androidx.room.RoomDatabaseKt")` is called in `@Before` but there is **no `@After`** that calls `unmockkStatic(...)`. Same leak problem. | Add `@After fun tearDown() { unmockkStatic("androidx.room.RoomDatabaseKt") }` |
| **NotificationProcessingPipelineStressTest.kt:53** | 🔴 High | Tautological Assertion | `assertTrue("Should handle empty fields", result != null \|\| result == null)` — This assertion is **always true** regardless of the outcome. | Assert the expected behavior: e.g., `assertNull("Empty input should return null", result)`. |
| **NotificationProcessingPipelineStressTest.kt:\*** | 🟡 Medium | Does Not Test Production Code | The entire file (587 lines, 20+ tests) tests **private helper methods defined inside the test class** rather than the actual `NotificationProcessingPipeline`. | Rewrite tests to exercise the actual `NotificationProcessingPipeline` with mocked dependencies. |
| **NotificationProcessingPipelineReliabilityTest.kt:92,101** | 🟡 Medium | Incorrect Coroutine Test Usage | Tests use `runBlocking` instead of `runTest`. `runBlocking` blocks the real thread and bypasses `TestScope`/`StandardTestDispatcher` virtual time control. | Replace `= runBlocking {` with `= runTest(testDispatcher) {`. |
| **BudgetRolloverTest.kt:45** | 🟡 Medium | Flaky Test / Non-determinism | `timeProvider.now()` mocked to `System.currentTimeMillis()`. Budget period calculations depend on the exact current timestamp. | Use a fixed timestamp like `1_705_320_000_000L`. |
| **BudgetRolloverTest.kt:280,299,314** | 🟡 Medium | Flaky Test / Non-determinism | Helper methods use `System.currentTimeMillis()` as defaults. Tests asserting on `remainingAmount` or `spentAmount` may break at midnight or on the 1st of the month. | Replace all `System.currentTimeMillis()` with the fixed mock time value. |
| **BudgetRolloverTest.kt:301** | 🟢 Low | Data Factory Bug | `createExpense()` always generates expenses with `id = 1L`. When multiple expenses are created, they all share the same ID. | Use a counter or unique IDs. |
| **WarrantyTrackerRepositoryTest.kt:143-161** | 🟡 Medium | Flaky Test / Non-determinism | `getWarrantiesExpiringSoon` test computes `currentTime` and `expectedFutureTime` using `System.currentTimeMillis()` but the `FakeTimeProvider` is set to `1_700_000_000_000L`. | Use `timeProvider.now()` instead of `System.currentTimeMillis()`. |
| **BudgetRepositoryStressTest.kt:41** | 🟡 Medium | Flaky Test / Non-determinism | Same issue: `timeProvider.now()` mocked to `System.currentTimeMillis()`. | Use a fixed timestamp. |
| **ReviewQueueRepositoryStressTest.kt:72** | 🟡 Medium | Flaky Test / Non-determinism | Same wall-clock `System.currentTimeMillis()` mocking pattern. | Use a fixed timestamp. |
| **ReceiptRepositoryStressTest.kt:75** | 🟡 Medium | Flaky Test / Non-determinism | Same wall-clock `System.currentTimeMillis()` mocking pattern. | Use a fixed timestamp. |
| **ReceiptRepositoryStressTest.kt:109** | 🟢 Low | Incorrect Dispatcher | Uses `Dispatchers.Unconfined` for `ioDispatcher`. Use `StandardTestDispatcher()` to better simulate real dispatcher behavior. | Use `StandardTestDispatcher()`. |
| **NotificationRepositoryStressTest.kt:177-183** | 🟢 Low | Missing Assertions | `stress - deleteAll clears all tables` test calls `repository.deleteAll()` but has **no assertions or verifications**. | Add `coVerify { dao.deleteAll() }` and verify other expected DAO calls. |
| **ExpenseRepositoryTest.kt:191-211** | 🟢 Low | Brittle SQL String Assertion | Test asserts on raw SQL string contents. If the query builder changes whitespace, column prefix, or clause order, the test will break. | Assert on the query results or use parameterized verification. |
| **MultiCurrencyRepositoryTest.kt:32** | 🟢 Low | Unused Field | `testDispatcher` is created and passed to `runTest(testDispatcher)` but `MultiCurrencyRepository` doesn't receive a dispatcher injection. | Verify that `MultiCurrencyRepository` accepts an injected dispatcher. |
| **FinancialWeatherRepositoryTest.kt:39** | 🟢 Low | Unused Dispatcher | `testDispatcher = StandardTestDispatcher()` is created but never used. | Remove the unused `testDispatcher` field. |
| **ExpenseRepositoryStressTest.kt:226-233** | 🟢 Low | Missing Assertions | `getExpensesPaged` test calls the method with 4 different page sizes but never asserts on the results. | Add `assertEquals(emptyList<...>(), result)` or `coVerify` calls. |

### Summary by Severity

| Severity | Count | Key Themes |
|----------|-------|---|
| 🔴 **High** | 3 | Dispatcher leak (`setMain` without `resetMain`), static mock leaks (2 files missing `unmockkStatic`), tautological assertion |
| 🟡 **Medium** | 9 | Widespread `System.currentTimeMillis()` non-determinism (6 files), fake stress tests testing private helpers, `runBlocking` instead of `runTest`, FakeTimeProvider vs real clock mismatch |
| 🟢 **Low** | 6 | Missing assertions, unused dispatchers, brittle SQL checks, duplicate entity IDs, incorrect dispatcher choice |

### Top 3 Recommendations (Highest Impact)

1. **Add missing `@After` teardowns** in `RecommendationRepositoryTest`, `GroupsRepositoryImplTest`, and `ReviewQueueRepositoryTest` — these leak global state across the entire test suite.

2. **Replace all `System.currentTimeMillis()` / `System.nanoTime()`** in test data factories with fixed constants — 10+ files are affected.

3. **Delete or rewrite `NotificationProcessingPipelineStressTest`** — it provides zero coverage of production code and its tautological assertion actively hides potential bugs.
