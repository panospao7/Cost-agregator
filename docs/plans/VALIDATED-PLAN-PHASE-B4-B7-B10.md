# Validated Remedy Plan: B.5, B.6, B.7, B.10

## B.5 Top Issues

## Issue: AndroidForegroundLocationProvider.getLastKnownLocation() lacks cached `lastLocation` fallback
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt:21-24` promises a fallback, but `app/src/main/java/com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt:41-48` only calls `getCurrentLocation(...)` and returns null on failure.
**Fix:** Keep the current fresh-fix attempt, but if it returns null or fails with a non-security error, fall back to `fusedClient.lastLocation.await()` before returning null. Preserve cancellation and add regression coverage for null current-fix + non-null cached-fix behavior.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/location/AndroidForegroundLocationProvider.kt`, `app/src/test/java/com/yourname/expensetracker/data/location/AndroidForegroundLocationProviderTest.kt`

## Issue: ExpenseDao.getMerchantLocationClusters() uses `CAST(...)` while repository code uses `floor(...)`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:1464-1486` groups by `CAST(latitude / 0.045 AS INTEGER)` / `CAST(longitude / 0.045 AS INTEGER)`, while `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt:58-63` builds area keys with `floor(...).toLong()`. Negative coordinates bucket differently.
**Fix:** Make DAO and repository share one true floor-based bucketing rule. Update the SQL grouping to a floor-equivalent expression that is correct for negative values, keep the same grid constant on both sides, and add a regression test with southern/western hemisphere coordinates.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`

## Issue: AreaSpendingEngine keeps the first parsed area name per grid cell
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt:69-75` seeds each cell with `Accumulator(areaName)` via `getOrPut(...)`, and the inline comment at `:70` states the first name is kept.
**Fix:** Track all parsed area-name candidates per cell, then choose the representative name after aggregation using a deterministic rule (count first, spend/count tie-break, then stable fallback). Keep the centroid/spend math unchanged.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt`, `app/src/test/java/com/yourname/expensetracker/domain/location/AreaSpendingEngineTest.kt`

## Issue: TravelDetectionEngine derives destination hints with `split(",").getOrNull(1)`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt:123-127`, `:136-137`, and `:146` all compute `tripDest` with `resolvedAddress?.split(",")?.getOrNull(1)?.trim()`.
**Fix:** Replace the inline split logic with one shared parser that prefers a locality/suburb component when present, but falls back to the first non-blank token for one-part addresses instead of returning null.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt`, `app/src/test/java/com/yourname/expensetracker/domain/location/TravelDetectionEngineTest.kt`

## Issue: CompositeGeocodingService.safeLookup() stops fallback cascades on unexpected exceptions *(added to complete top-15 set)*
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt:77-80` only cascades on transient failures, while `app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt:360-371` converts unexpected provider exceptions to `GeocodingError.Unknown`, which is treated as non-transient.
**Fix:** Preserve enough error semantics for unexpected provider failures to remain fallback-eligible (or explicitly map them to a transient/cascadable error). Keep explicit `NoResults` non-cascading. Add tests where the primary provider throws unexpectedly and fallback providers still run.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt`, `app/src/test/java/com/yourname/expensetracker/data/location/CompositeGeocodingServiceTest.kt`

## B.6 Top Issues

## Issue: NotificationCaptureService.onDestroy() cancels `serviceJob` immediately
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt:363-365` launches notification processing on `serviceScope`, and `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt:400-406` calls `serviceJob.cancel()` immediately in `onDestroy()`.
**Fix:** Stop accepting new work first, then let in-flight processing finish (or hand it off to an app-scoped component/worker) before canceling the scope. Use a bounded drain timeout so shutdown remains finite.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`, `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt`, `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceStressTest.kt`

## Issue: RecommendationInvalidator.invalidateAllForUser() does not truly invalidate all recommendations
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/service/RecommendationInvalidator.kt:34-45` clears cache and calls `repository.expireOld(userId)`; `app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt:153-164` forwards to DAO expiry; `app/src/main/java/com/yourname/expensetracker/data/database/dao/RecommendationDao.kt:131-139` only updates rows with `expiresAt < :beforeTimestamp`, leaving active rows untouched.
**Fix:** Add an explicit bulk invalidation path for all recommendations belonging to the user (clear rows, or mark all active rows `EXPIRED`/`ARCHIVED` per desired semantics), then refresh in-memory state from the post-invalidation DB state. Also log failures instead of silently swallowing them.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/service/RecommendationInvalidator.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/RecommendationDao.kt`, `app/src/test/java/com/yourname/expensetracker/service/RecommendationStateManagerTest.kt`

## Issue: ReviewQueueRepository.markAsRelevant(true) inserts fallback PendingReview rows without checking for an existing review
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:431-445` builds a fallback `PendingReview` when reparsing fails and `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:494-496` inserts it directly. A lookup already exists at `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt:42-43`, but it is not used here, and `app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt:34-40` does not enforce uniqueness on `rawNotificationId`.
**Fix:** Make the path transactional and idempotent by checking/upserting by `rawNotificationId` before insert. Prefer a DB uniqueness rule for non-null `rawNotificationId`; if added, include a migration step that collapses existing duplicates before creating the index.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/PendingReviewDaoTest.kt`

## Issue: NotificationProcessingPipeline oversized-amount fallback inserts PendingReview rows without semantic duplicate checking *(added to complete top-15 set)*
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:153-185` creates a `PendingReview` in the oversized-amount branch and inserts it immediately at `:184` without any pending-review or expense duplicate check in that branch.
**Fix:** Before inserting the oversized fallback review, run the same canonical duplicate checks used elsewhere against recent pending reviews/expenses. If a semantic duplicate is found, mark/counter it without creating a second review.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt`, `app/src/test/java/com/yourname/expensetracker/consistency/DuplicateLogicConsistencyIntegrationTest.kt`

## B.7 Top Issues

## Issue: AccountantReportPdfExporter still keeps shared formatter state
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt:64-67` uses `ONE_DECIMAL_FORMAT`, `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt:97-99` uses `TWO_DECIMAL_FORMAT`, and `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt:246-248` stores both `DecimalFormat` instances plus `DAY_FORMAT` in a companion object shared across exports.
**Fix:** Remove shared mutable formatters from companion state. Use immutable `DateTimeFormatter` for dates and per-export/thread-confined number formatters so concurrent exports cannot race.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt`, `app/src/test/java/com/yourname/expensetracker/data/repository/AccountingExportRepositoryTest.kt`

## Issue: BusinessExpenseReportGenerator.generateMileageReport() uses the first trip's rate as if it applied to the whole report
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGenerator.kt:203-208` sets report `rate` from `trips.first().deductionRatePerKm`, while the rendered report at `app/src/main/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGenerator.kt:141` labels that single value as the report-wide "Deduction Rate".
**Fix:** Change the mileage summary model/report text so it only exposes a single rate when all trips share one. Otherwise report a weighted effective rate plus a multi-rate indicator, while keeping per-trip rates authoritative.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGenerator.kt`, `app/src/test/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGeneratorTest.kt`

## B.10 Top Issues  

## Issue: CurrencyConverter.storeRate() accepts zero, negative, NaN, and infinite rates
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt:174-188` persists any `rate` value directly, and `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt:193-207` does the same in `storeRates(...)`.
**Fix:** Validate `rate.isFinite()` and `rate > 0.0` before persistence in both single-rate and batch paths. Reject or skip invalid entries with logging and regression tests for `0.0`, negatives, `NaN`, and infinities.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`, `app/src/test/java/com/yourname/expensetracker/domain/currency/CurrencyConverterTest.kt`

## Issue: HybridExpenseClassifier.initialize() caches categories for the full process lifetime
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt:38-49` loads `categories` once and flips `initialized = true`; later calls only do `if (!initialized) initialize()` at `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt:60`, so renamed/added categories never refresh until restart.
**Fix:** Replace the one-shot process cache with a refreshable snapshot or flow-backed cache. Invalidate it on category add/rename/delete and read from an atomic local snapshot during `classify()`/fallback selection.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt`, `app/src/test/java/com/yourname/expensetracker/integration/CategorizationPipelineIntegrationTest.kt`

## Issue: SemanticKeywordMatcher wraps every keyword in `\b...\b`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt:76-79` builds every keyword regex as `Regex("""\b${Regex.escape(entry.keyword)}\b""")`, which prevents matches for keywords ending in non-word characters such as `disney+`.
**Fix:** Build boundary rules from the keyword shape: keep word boundaries for word-only tokens, but use custom start/end lookarounds or escaped-substring matching for punctuation-suffixed/prefixed keywords. Add regressions for `disney+`, `e-food`, and similar cases.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt`, `app/src/test/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcherTest.kt`

## Issue: MerchantNormalizer.fuzzyMatch() takes the first BK-tree result instead of the best candidate
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt:166-178` gets all BK-tree matches, then immediately uses `val best = matches.first()` before computing similarity; equal-distance candidates are never ranked.
**Fix:** Score every BK-tree candidate using edit distance, Jaro-Winkler similarity, and a deterministic tie-break (for example canonical frequency/verified flag/name). Only auto-link aliases from the top-ranked candidate when confidence still clears the threshold.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt`, `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizerTest.kt`

## Priority Order (combined)
1. CurrencyConverter.storeRate() accepts invalid rates - B.10
2. RecommendationInvalidator.invalidateAllForUser() does not truly invalidate all recommendations - B.6
3. ReviewQueueRepository.markAsRelevant(true) inserts fallback PendingReview rows without checking for an existing review - B.6
4. HybridExpenseClassifier.initialize() caches categories for the full process lifetime - B.10
5. NotificationCaptureService.onDestroy() cancels `serviceJob` immediately - B.6
6. MerchantNormalizer.fuzzyMatch() takes the first BK-tree result instead of the best candidate - B.10
7. CompositeGeocodingService.safeLookup() stops fallback cascades on unexpected exceptions - B.5
8. NotificationProcessingPipeline oversized-amount fallback inserts PendingReview rows without semantic duplicate checking - B.6
9. ExpenseDao.getMerchantLocationClusters() uses `CAST(...)` while repository code uses `floor(...)` - B.5
10. AccountantReportPdfExporter still keeps shared formatter state - B.7
11. SemanticKeywordMatcher wraps every keyword in `\b...\b` - B.10
12. BusinessExpenseReportGenerator.generateMileageReport() uses the first trip's rate as if it applied to the whole report - B.7
13. AndroidForegroundLocationProvider.getLastKnownLocation() lacks cached `lastLocation` fallback - B.5
14. TravelDetectionEngine derives destination hints with `split(",").getOrNull(1)` - B.5
15. AreaSpendingEngine keeps the first parsed area name per grid cell - B.5

## Estimated Effort
32 hours
