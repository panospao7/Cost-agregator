# Test Batch 001 — Audit Classification

**Batch file:** docs/testing/generated/test-batches/batch-files-001.txt  
**Audit date:** 2026-05-12  
**Total files:** 100  
**Project:** ExpenseTracker

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| KEEP | 63 |
| DELETE | 8 |
| REWRITE | 12 |
| MOVE_TO_NIGHTLY | 3 |
| UNKNOWN_NEEDS_LOCAL_RUN | 14 |

---

## Classification Table

| # | Path | Type | Value | Action | Tests | Ignored | Confidence | Main reason |
|---|---|---|---|---|---|---|---|---|
| 1 | AnalyticsEngineTestBase.kt | FIXTURE_INFRASTRUCTURE | P1_HIGH | KEEP | 0 | 0 | HIGH | Reusable base: coroutine dispatcher rule, MockK for ExpenseDao/TimeProvider/CategoryRepository |
| 2 | AnalyticsTestCompat.kt | FIXTURE_INFRASTRUCTURE | P1_HIGH | KEEP | 0 | 0 | HIGH | Fake Repositories (TestCurrencySettingsRepository, TestExchangeRateStore), converters |
| 3 | TestUtils.kt | FIXTURE_INFRASTRUCTURE | P1_HIGH | KEEP | 0 | 0 | HIGH | createExpense DSL, assertApproxEquals (Double/Float), assertWithinPercent helpers |
| 4 | consistency/ConstantsConsistencyTest.kt | PURE_ENGINE | P2_MEDIUM | KEEP | 2 | 0 | MEDIUM | Reflection-based: cross-engine thresholds match, static constants not duplicated |
| 5 | consistency/CrossParserConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 8 | 0 | HIGH | Parser drill-down: Revolut/GreekBank/GenericParser all use MerchantKeyGenerator consistently |
| 6 | consistency/CurrencyNormalizerConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 10 | 0 | HIGH | EUR/USD/GBP/INR symbol normalisation verified across Revolut/GreekBank/Generic parsers |
| 7 | consistency/DedupeKeyProducerConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 12 | 0 | HIGH | ISSUE-8: all expense-insert producers (approveReview, markAsRelevant, pipeline, manual, receipt, email) use same type-aware dedupe key |
| 8 | consistency/DuplicateLogicConsistencyIntegrationTest.kt | PURE_ENGINE | P0_CRITICAL | KEEP | 23 | 0 | HIGH | ISSUE-5 regression: currency-aware, type-compatible ranked dedupe; 23 test cases |
| 9 | consistency/EmptyZeroNullResilienceTest.kt | PURE_ENGINE | P0_CRITICAL | KEEP | 2 | 0 | HIGH | Every engine with empty/zero/null inputs; no NaN/infinity leaks; safe finite defaults |
| 10 | consistency/FinancialArithmeticPrecisionTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 4 | 0 | HIGH | toCents/fromCents roundtrip, 500x drift < 0.01, Int overflow boundary (21.4M) |
| 11 | consistency/HaversineConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 5 | 0 | HIGH | haversineKm matches inline formula; null-safe variant; km-to-meters conversion |
| 12 | consistency/MerchantKeyConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 3 | 0 | HIGH | Parser path (MerchantCleaner) vs categorization path (MerchantRulesRepository) produce same key |
| 13 | consistency/MerchantKeyCrossConsumerConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 10 | 0 | HIGH | All consumers agree on keys; Greek/Latin script, stress 200 merchants, mixed script |
| 14 | consistency/SharedUtilityConsistencyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 15 | 0 | HIGH | AmountUtils vs AmountExtractionUtils agree on simple/currency-prefixed/European formats; bad inputs reject |
| 15 | consistency/TemporalConsistencyTest.kt | PURE_ENGINE | P0_CRITICAL | KEEP | 4 | 0 | HIGH | BudgetCalculator + SpendingPaceCalculator share same month boundaries; DST, leap year Feb 2024 |
| 16 | consistency/TimePeriodAnalyticsAlignmentTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 6 | 0 | HIGH | TimePeriodUtils ranges match AnalyticsViewModel WEEK/MONTH expectations |
| 17 | contracts/LifecycleBarrierContractTest.kt | SOURCE_TEXT_ASSERTION | P1_HIGH | KEEP | 2 | 0 | HIGH | Source-scan: all withTransaction refs must include writeBarrier check |
| 18 | contracts/MoneyContractTest.kt | SOURCE_TEXT_ASSERTION | P1_HIGH | KEEP | 1 | 0 | HIGH | Source-scan: effectiveAmount summation only in currency-aware contexts |
| 19 | contracts/PrivacyStorageContractTest.kt | SOURCE_TEXT_ASSERTION | P1_HIGH | KEEP | 3 | 0 | HIGH | Source-scan: RawStorageMode uses exhaustive when (not if/else); DO_NOT_STORE branch exists |
| 20 | contracts/RecurringDeactivateContractTest.kt | SOURCE_TEXT_ASSERTION | P1_HIGH | KEEP | 5 | 0 | HIGH | Source-scan: deactivateRule checks writeBarrier, cancels future, suppresses reminders, cancels planned |
| 21 | contracts/SideEffectContractTest.kt | SOURCE_TEXT_ASSERTION | P1_HIGH | KEEP | 1 | 0 | HIGH | Source-scan: dispatchOnCreated/Updated/Deleted never inside withTransaction blocks |
| 22 | currency/CanonicalMultiCurrencyFixture.kt | GOLDEN | P0_CRITICAL | KEEP | ~15 | 0 | HIGH | D.2 gap: 50 EUR + 100 USD at 0.92 = 142 EUR golden test; RAW_WRONG_SUM=150 guard |
| 23 | data/ai/provider/CloudCategorizationAssistServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | REWRITE | ~15 | 0 | MEDIUM | 4+ TODOs "tautological mock test"; real OkHttp interceptors but assertion depth shallow |
| 24 | data/ai/provider/CloudDashboardBriefingServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | REWRITE | ~7 | 0 | MEDIUM | 4+ TODOs; real OkHttp interceptors, domain result assertions exist but boilerplate heavy |
| 25 | data/ai/provider/CloudDedupeJudgeServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | ~7 | 0 | HIGH | Real JSON parsing: verdict, matchedTargetId, confidence, rationale; IOException offline handling |
| 26 | data/ai/provider/CloudQueryInterpretationServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | REWRITE | ~5 | 0 | MEDIUM | Mix of TODO-marked tautological tests and real parsing tests; prune the 2 shallow ones |
| 27 | data/ai/provider/CloudReceiptAssistServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | REWRITE | ~6 | 0 | MEDIUM | 4 TODOs; buildRequestBodyForTest tests are valuable; suggest tests are shallow |
| 28 | data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt | PRIVACY_SECURITY | P0_CRITICAL | KEEP | ~4 | 0 | HIGH | Privacy gate: verifies redaction strips raw category names from cloud prompt payload |
| 29 | data/ai/provider/CloudReviewExplanationServiceTest.kt | TRIVIAL_MODEL | P4_NEGATIVE_VALUE | DELETE | 1 | 0 | HIGH | 1 TODO tautological test; only checks api-key-absent returns Disabled; zero real behaviour |
| 30 | data/ai/provider/CloudWarrantyExtractionServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | ~5 | 0 | HIGH | Real JSON parsing: productName, warrantyMonths, warrantyType, returnDays, returnConditions |
| 31 | data/ai/provider/DashboardBriefingResponseParserTest.kt | PARSER | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Valid confidence bounds, NaN rejection, out-of-range rejection |
| 32 | data/ai/provider/HybridReceiptItemCategorizationServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | REWRITE | 1 | 0 | MEDIUM | Single TODO test; only covers on-device route, missing cloud and fallback routes |
| 33 | data/ai/provider/HybridServiceDelegationTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | REWRITE | ~6 | 0 | MEDIUM | All tests tagged TODO but prove real routing contract (coVerify exact counts); remove TODO, reduce boilerplate |
| 34 | data/ai/provider/OnDeviceCategorizationAssistServiceTest.kt | PARSER | P2_MEDIUM | KEEP | ~15 | 0 | HIGH | Real prompt building + JSON response parsing: clean JSON, markdown fences, altCategoryIds, null confidence |
| 35 | data/ai/provider/OnDeviceDashboardBriefingServiceTest.kt | PARSER | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Real prompt; redacted transaction insight verification (merchant_alias, amount bucket, Privacy mode) |
| 36 | data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt | PARSER | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Real prompt + verdict parsing: LIKELY_DUPLICATE, UNCERTAIN, unknown verdict, NaN confidence, zero matchedTargetId |
| 37 | data/ai/provider/OnDeviceNotificationParserTest.kt | PARSER | P2_MEDIUM | REWRITE | 3 | 0 | MEDIUM | 3 TODOs but real JSON parsing with ParsedTransactionType/ParsedTransferDirection assertions; remove TODO markers |
| 38 | data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt | PARSER | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Real prompt with redacted aliases; structured/clarification/unsupported response parsing |
| 39 | data/ai/provider/OnDeviceReceiptAssistServiceTest.kt | PARSER | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Real OCR prompt; image attachment check; JSON response with merchant/total/date/notes |
| 40 | data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt | PARSER | P3_LOW | KEEP | 1 | 0 | HIGH | Single test: keyword fallback for zero-overlap items; real categorization call with confidence assertion |
| 41 | data/ai/provider/OnDeviceReviewExplanationServiceTest.kt | PARSER | P2_MEDIUM | KEEP | 6 | 0 | HIGH | Real prompt building with review facts; missing headline/body returns null |
| 42 | data/ai/provider/SmartReceiptAssistServiceTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | ~8 | 0 | HIGH | Real delegation contract: coVerify cloud/on-device/fallback routing; has real result assertions |
| 43 | data/ai/provider/internal/CloudJsonParserTest.kt | PARSER | P1_HIGH | KEEP | 11 | 0 | HIGH | Critical parser: nested braces, escaped quotes/backslashes, fenced JSON, strict double/long parsing |
| 44 | data/ai/provider/internal/CloudRetryPolicyTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 7 | 0 | HIGH | Retry status codes (5xx/429/408), IO exception inspection, backoff delay ranges with jitter |
| 45 | data/ai/worker/DailyBriefingWorkerTest.kt | WORKER_RUNTIME | P1_HIGH | KEEP | 6 | 0 | HIGH | Robolectric + WorkManager; doWork lifecycle, CancellationException, delivery timeout, engine failure |
| 46 | data/currency/ExchangeRateStoreAdapterTest.kt | REPOSITORY_INTEGRATION | P1_HIGH | KEEP | 4 | 0 | HIGH | Real DAO delegation with slot capture; getRate, insertOrUpdate, getRatesToCurrency, deleteOldRates |
| 47 | data/database/GroupTransactionCoordinatorTest.kt | DAO_ROOM_CONTRACT | P0_CRITICAL | KEEP | ~30 | 0 | HIGH | Room in-memory + Robolectric; CRITICAL-2 atomic group creation with rollback scenarios |
| 48 | data/database/MigrationRegistrationTest.kt | MIGRATION_SCHEMA | P1_HIGH | KEEP | 3 | 0 | HIGH | MigrationTestHelper: 120→121 (group_lifecycle_events), 119→121, ALL_MIGRATIONS completeness |
| 49 | data/database/TransactionRollbackTest.kt | TRIVIAL_MODEL | P4_NEGATIVE_VALUE | DELETE | 15 | 1 | HIGH | ALL 15 tests use simulated try/catch with boolean flags; ZERO real database; dead code |
| 50 | data/database/converter/ConvertersTest.kt | PARSER | P3_LOW | KEEP | 4 | 0 | HIGH | TransactionType string roundtrip; UNKNOWN fallback for invalid/empty strings |
| 51 | data/database/dao/BackgroundJobRunDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Room in-memory + Robolectric; insert, getRecent ordered, limit, workerName filter, update, stale runs |
| 52 | data/database/dao/BankConnectionDaoTest.kt | DAO_ROOM_CONTRACT | P1_HIGH | KEEP | 8 | 0 | HIGH | Room in-memory + Robolectric; disconnect wipes tokens, resets encryption version, sets flags |
| 53 | data/database/dao/EmailReceiptDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Room in-memory + Robolectric; insert, query by provider/sender, deduplication via messageId |
| 54 | data/database/dao/ExchangeRateDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Room in-memory + Robolectric; insertOrUpdate replaces existing, getRateAsOf, getLatestRate |
| 55 | data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt | SOURCE_TEXT_ASSERTION | P4_NEGATIVE_VALUE | DELETE | 20+ | 0 | HIGH | Documents <= vs < DAO inconsistencies with static lists; ZERO real Room; TODO tautological |
| 56 | data/database/dao/InvestmentDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 5 | 0 | HIGH | Room in-memory + Robolectric; insert, getAllActiveInvestments, value field verification |
| 57 | data/database/dao/PrivacyAuditDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 6 | 0 | HIGH | Room in-memory + Robolectric; insert, getRecent with limit, DESC ordering, empty state |
| 58 | data/database/dao/RawNotificationDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Room in-memory + Robolectric; insert, deduplication via insertOrIgnore (-1 for duplicate) |
| 59 | data/database/dao/ReceiptEventDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 6 | 0 | HIGH | Room in-memory + Robolectric; insert, query by receiptId, timestamp ordering, isolation |
| 60 | data/database/dao/ReceiptExpenseLinkDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Room in-memory + Robolectric; link/unlink/deleteAllLinks; query by receiptId and expenseId |
| 61 | data/database/dao/RecommendationDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | ~15 | 0 | HIGH | Room in-memory + Robolectric; getActiveByUser filtering, archive, expire, dismiss, pagination |
| 62 | data/database/dao/RecurringOccurrenceDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Room in-memory + Robolectric; insert, getBySource, getByKey, getByDateRange, getByStatus |
| 63 | data/database/dao/TransactionEventDaoTest.kt | DAO_ROOM_CONTRACT | P2_MEDIUM | KEEP | 6 | 0 | HIGH | Room in-memory + Robolectric; insert, query by expenseId, timestamp ordering, multi-expense isolation |
| 64 | data/database/entity/CategoryTest.kt | TRIVIAL_MODEL | P2_MEDIUM | KEEP | 7 | 0 | HIGH | Entity validation: empty name, blank name, invalid color, icon length, name max length |
| 65 | data/database/entity/DedupeKeyTest.kt | PURE_ENGINE | P0_CRITICAL | KEEP | 9 | 0 | HIGH | Dedupe key format, case normalization, Greek/Latin, currency suffix, 5-min bucket boundaries |
| 66 | data/database/entity/ExpenseEntityStressTest.kt | STRESS_PERFORMANCE | P3_LOW | MOVE_TO_NIGHTLY | ~30 | 1 | HIGH | @Ignore; locale dedupe key stress (A.4 regression proofs), effectiveAmount fuzzing |
| 67 | data/database/entity/MileageTrackingValidationTest.kt | TRIVIAL_MODEL | P4_NEGATIVE_VALUE | DELETE | 1 | 0 | HIGH | Single test; entity construction; asserts 0.0 == 0.0; no business logic |
| 68 | data/database/model/ExpenseWithCategoryFormattedAmountTest.kt | PARSER | P1_HIGH | KEEP | 11 | 0 | HIGH | B.4-10: polarity prefix, currency before number, effectiveAmount, shared expense, isNotMine |
| 69 | data/database/model/ExpenseWithCategoryFormattedTimeTest.kt | PARSER | P1_HIGH | KEEP | 5 | 0 | HIGH | B.4-29: formattedDate (MMM dd, HH:mm) vs formattedTime (HH:mm) distinction |
| 70 | data/email/EmailReceiptIngestionServiceTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | ~10 | 0 | HIGH | Real assertions: Amazon provider detection, expense creation; slot capture on ScannedReceipt, EmailReceiptSource |
| 71 | data/email/EmailReceiptIngestionServiceTransactionTest.kt | REPOSITORY_INTEGRATION | P0_CRITICAL | KEEP | 1 | 0 | HIGH | Robolectric + Room in-memory; proves rollback when expense creation fails; table count = 0 after |
| 72 | data/email/provider/AmazonReceiptParserTest.kt | PARSER | P2_MEDIUM | KEEP | 1 | 0 | HIGH | Real parser: localized date + comma decimal amount (French locale formatting) |
| 73 | data/email/provider/AppleReceiptParserTest.kt | PARSER | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Real parser: localized date, comma decimal; EUR-not-inferred-from-incidental-ORDER-token |
| 74 | data/email/provider/EmailReceiptParserTest.kt | PARSER | P2_MEDIUM | KEEP | 3 | 0 | HIGH | Real parser: HTML entity decoding, localized amount parsing, non-English month names |
| 75 | data/email/provider/UberReceiptParserTest.kt | PARSER | P1_HIGH | KEEP | 6 | 0 | HIGH | Real parser: timestamped ride date, Eats total, year anchoring, new-year clamping, EUR-not-incidental guard |
| 76 | data/location/AndroidForegroundLocationProviderTest.kt | TRIVIAL_MODEL | P4_NEGATIVE_VALUE | DELETE | 1 | 0 | HIGH | TODO tautological; asserts (37.98, 23.72) == (37.98, 23.72); zero production code |
| 77 | data/location/CompositeGeocodingServiceStressTest.kt | STRESS_PERFORMANCE | P3_LOW | MOVE_TO_NIGHTLY | 8 | 1 | HIGH | @Ignore; dedup, ranking, min result window enforcement, provider error resilience |
| 78 | data/location/CompositeGeocodingServiceTest.kt | MOCK_ORCHESTRATION | P3_LOW | KEEP | 1 | 0 | MEDIUM | Single test: unexpected primary exception cascades to fallback provider; real coVerify |
| 79 | data/location/GeocodingCancellationTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 2 | 0 | HIGH | Real OkHttp Call.Factory; executeCancellable cancellation; PhotonGeocodingService cancellation |
| 80 | data/location/GeocodingRetryHttpSemanticsTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 6 | 0 | HIGH | Photon/Nominatim/Geoapify/Google Places all return RateLimited after 3x 429; real retry |
| 81 | data/location/LocationBackfillWorkerTest.kt | WORKER_RUNTIME | P1_HIGH | KEEP | 5 | 0 | HIGH | Robolectric + WorkManager; expense location resolution; graceful failure; retryable result |
| 82 | data/location/MerchantKeyBackfillWorkerTest.kt | WORKER_RUNTIME | P1_HIGH | KEEP | 5 | 0 | HIGH | Robolectric + WorkManager; null merchant key population, idempotent skip, partial progress retry |
| 83 | data/location/NominatimGeocodingServiceLocaleTest.kt | PURE_ENGINE | P1_HIGH | KEEP | 1 | 0 | HIGH | Critical: Greek locale uses dot decimals; captured URL proves "lat=37.9838100" not "37,9838100" |
| 84 | data/location/OverpassNearbyServiceTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Real rate limiting; Greek merchant name ranking with real JSON body; GeocodingError assertions |
| 85 | data/location/internal/LogSanitizerTest.kt | PRIVACY_SECURITY | P1_HIGH | KEEP | 3 | 0 | HIGH | SHA-256 anonymization stable within process; produces materially different output for distinct inputs |
| 86 | data/privacy/BackupEncryptionServiceTest.kt | PRIVACY_SECURITY | P0_CRITICAL | KEEP | 5 | 0 | HIGH | AES-256-GCM encrypt/decrypt roundtrip; wrong password throws BadTag; random salt+IV; corrupted ciphertext |
| 87 | data/repository/AccountingExportRepositoryTest.kt | REPOSITORY_INTEGRATION | P1_HIGH | KEEP | ~15 | 0 | HIGH | A.9 Batch 6: deterministic paging via fetchAllBetween; multi-page; real IIF/CSV/PDF exporters |
| 88 | data/repository/AiArtifactRepositoryImplTest.kt | MOCK_ORCHESTRATION | P3_LOW | REWRITE | ~8 | 0 | MEDIUM | 2 TODO tests; remove tautological delegation tests; keep 6 tests with real domain mapping assertions |
| 89 | data/repository/AiChatRepositoryImplTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 8 | 0 | HIGH | Real assertions: createSession null when history disabled; message insertion with slot capture |
| 90 | data/repository/AutomatedSavingsRuleStateRepositoryTest.kt | REPOSITORY_INTEGRATION | P1_HIGH | KEEP | 6 | 0 | HIGH | Real DataStore persistence; weekly reservation idempotent across recreation; monthly cap atomic |
| 91 | data/repository/BudgetRepositoryHistoricalStatusTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 2 | 0 | HIGH | Real: uses explicit evaluation time instead of current time; period boundaries, health status correct |
| 92 | data/repository/BudgetRepositoryStressTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 15 | 0 | HIGH | A.9: validation (zero/negative amount), rollover, large-history, suggestions flush; real assertions |
| 93 | data/repository/BudgetRepositorySuggestionsBatchTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 1 | 0 | HIGH | Verifies CategorySpentTotals batched query; coVerify single grouped call, zero per-category |
| 94 | data/repository/BudgetRepositoryTruncationTest.kt | MOCK_ORCHESTRATION | P0_CRITICAL | KEEP | 9 | 0 | HIGH | A.9: 800-row total not capped at 500; 2500-row correctly shows EXCEEDED; no row-level reads |
| 95 | data/repository/BudgetRolloverTest.kt | MOCK_ORCHESTRATION | P1_HIGH | KEEP | 12 | 0 | HIGH | Real BudgetCalculator with fixed UTC; non-rollover, rollover, multi-period, compound cap |
| 96 | data/repository/BusinessExpenseRepositoryTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 2 | 0 | HIGH | Real validation: NaN distance rejects pre-DAO; valid mileage delegates to DAO |
| 97 | data/repository/CategoryRepositoryStressTest.kt | STRESS_PERFORMANCE | P3_LOW | MOVE_TO_NIGHTLY | 4 | 1 | HIGH | @Ignore; bulk learn 100 merchants, add 50 categories; relaxed mocks |
| 98 | data/repository/CategoryRepositoryTest.kt | MOCK_ORCHESTRATION | P4_NEGATIVE_VALUE | DELETE | 1 | 0 | HIGH | TODO tautological; single coVerify delegation test with zero domain assertions |
| 99 | data/repository/DashboardContractsAdapterTest.kt | MOCK_ORCHESTRATION | P2_MEDIUM | KEEP | 1 | 0 | HIGH | Real contract: observeRecurringPatterns uses confirmed recurring feed, not merged |
| 100 | data/repository/DatabaseBackupRepositoryImplTest.kt | BACKUP_RESTORE | P0_CRITICAL | KEEP | ~20 | 0 | HIGH | Robolectric + real SQLite; export/import with row counts; table count verification; schema version |

---

## Detailed Notes

### DELETE Items (8 files)

#### #29 – CloudReviewExplanationServiceTest.kt
- **Why delete:** Single test with 1 assertion checking api-key-absent returns Disabled. Marked TODO "Tautological mock test." Zero production behavior verified beyond the trivial absent-key guard.
- **Risk lost:** Minimal. Disabled state is trivially verified by any integration test. The generate() method with real JSON parsing is completely untested either way.
- **Replacement needed:** A test using real OkHttp interceptors + real JSON response bodies (pattern already exists in CloudDedupeJudgeServiceTest).
- **Delete now or after replacement:** Delete now.

#### #49 – TransactionRollbackTest.kt
- **Why delete:** ALL 15 tests simulate transaction rollback with try/catch + boolean flag assertions. No real Room database, no real SQL. Comments say "Simulate: Expense inserted, then split assignments fail." Pure fiction.
- **Risk lost:** Zero. These tests never exercised real production code.
- **Replacement needed:** Real Room-in-memory rollback tests (like GroupTransactionCoordinatorTest) proving atomicity on FK constraint failure or partial write scenarios.
- **Delete now or after replacement:** Delete now.

#### #55 – ExpenseDaoBoundaryConsistencyTest.kt
- **Why delete:** 20+ tests that assert hardcoded lists of DAO method names are non-empty. Marked with TODO "Tautological mock test." Documents the known inclusive/exclusive end-date inconsistency but proves nothing about actual database behavior.
- **Risk lost:** The documentation of the boundary inconsistency is lost. However, this documentation should live as code comments in ExpenseDao, not as a fake test suite.
- **Replacement needed:** A real Room test proving double-counting/omission at period boundaries by inserting expenses and querying with mixed bounds. Move documentation to ExpenseDao.kt comments.
- **Delete now or after replacement:** Delete AFTER replacement. Move documentation to ExpenseDao source before deleting.

#### #67 – MileageTrackingValidationTest.kt
- **Why delete:** Single test creating an entity with defaults and asserting distanceKm == 0.0. Equivalent to al x = MyDataClass(); assertEquals(defaultValue, x.field) — the Kotlin compiler already guarantees this.
- **Risk lost:** None. Data class construction is compiler-verified.
- **Replacement needed:** None. If mileage validation exists (e.g., endOdometer >= startOdometer), add a real validation test.
- **Delete now or after replacement:** Delete now.

#### #76 – AndroidForegroundLocationProviderTest.kt
- **Why delete:** Single test marked TODO "Tautological mock test." Asserts (37.98, 23.72) equals (37.98, 23.72) — literally ssertEquals(a, a). Pure tautology.
- **Risk lost:** Zero.
- **Replacement needed:** A test exercising AndroidForegroundLocationProvider with a mocked FusedLocationProviderClient or LocationManager.
- **Delete now or after replacement:** Delete now.

#### #98 – CategoryRepositoryTest.kt
- **Why delete:** Single test marked TODO "Tautological mock test." Calls learnMerchantCategory and coVerifies delegation to a mocked CategorizationEngine. Tests the mock, not the production code.
- **Risk lost:** Minimal. Delegation pattern is trivially correct.
- **Replacement needed:** A Room-in-memory test that persists a merchant-category mapping and verifies the engine was called with the correct normalized merchant name.
- **Delete now or after replacement:** Delete after replacement.

#### #88 – AiArtifactRepositoryImplTest.kt (REWRITE, not DELETE — see REWRITE section)

---

### REWRITE Items (12 files, including #88)

#### #23 – CloudCategorizationAssistServiceTest.kt
- **Why rewrite:** 4+ TODO markers. Retry tests use real OkHttp interceptors and parse real JSON bodies — assertion depth is good. Boilerplate heavy (~464 lines). Remove TODO markers, extract shared interceptor factories.
- **What to change:** Remove TODOs; consolidate interceptor setup; add verification that parsed CategoryAssistSuggestion has all domain fields.

#### #24 – CloudDashboardBriefingServiceTest.kt
- **Why rewrite:** 4+ TODOs. Same pattern: real interceptors, real JSON parsing, real AiServiceResult.Success/Failure assertions. Remove TODO markers, reduce boilerplate.
- **What to change:** Remove TODOs; extract shared interceptor builders.

#### #26 – CloudQueryInterpretationServiceTest.kt
- **Why rewrite:** Two TODO-marked tests are shallow (they mock OkHttpClient directly instead of using interceptors). The remaining tests are genuine. Prune the 2 shallow ones.
- **What to change:** Delete the 2 shallow tests; keep the real parsing tests.

#### #27 – CloudReceiptAssistServiceTest.kt
- **Why rewrite:** 4 TODOs. The buildRequestBodyForTest tests are genuinely valuable (image inclusion/redaction in payload). The suggest tests are shallow.
- **What to change:** Keep and rename buildRequestBodyForTest tests; delete or rewrite suggest tests with real JSON response parsing.

#### #32 – HybridReceiptItemCategorizationServiceTest.kt
- **Why rewrite:** Single test covers only on-device route. Valid contract test pattern but incomplete.
- **What to change:** Add tests for cloud route and deterministic-fallback route.

#### #33 – HybridServiceDelegationTest.kt
- **Why rewrite:** All 6 tests tagged TODO but they prove a real architectural contract (routing to correct provider per AiMode). The coVerify(exact) pattern is exactly right for routing tests.
- **What to change:** Remove TODO markers; reduce boilerplate via shared harness; remain a valid delegation contract test.

#### #37 – OnDeviceNotificationParserTest.kt
- **Why rewrite:** 3 tests tagged TODO but they test real JSON parsing with real ParsedTransactionType and ParsedTransferDirection assertions. The relaxed MockK for unused dependencies is the only "tautological" part.
- **What to change:** Remove TODO markers; use no-arg default instances instead of relaxed mocks for unused router/settings dependencies.

#### #88 – AiArtifactRepositoryImplTest.kt
- **Why rewrite:** 2 tests have TODO markers. The remaining 6 tests have real assertions (domain mapping, null returns). Remove the 2 tautological delegation tests.
- **What to change:** Delete the 2 TODO-marked tests; keep the 6 valuable tests.

---

### MOVE_TO_NIGHTLY Items (3 files)

#### #66 – ExpenseEntityStressTest.kt
- **Why move to nightly:** Class-level @Ignore. Contains critical A.4 regression proofs: German locale still uses dots for dedupe keys, Greek locale stable, cross-locale key equality. Should not run per-commit.
- **Risk if deleted:** Loss of locale dedupe regression detection.
- **Action:** Remove @Ignore, add to nightly CI configuration (e.g., @Category(NightlyTests)).

#### #77 – CompositeGeocodingServiceStressTest.kt
- **Why move to nightly:** Class-level @Ignore. Tests dedup, ranking, result window enforcement, provider error resilience. All mockk — no network dependency.
- **Action:** Remove @Ignore, add to nightly CI.

#### #97 – CategoryRepositoryStressTest.kt
- **Why move to nightly:** Class-level @Ignore. Bulk learn 100 merchants, add 50 categories. Uses relaxed mocks; would complete in <1s.
- **Action:** Remove @Ignore, add to nightly CI.

---

### UNKNOWN_NEEDS_LOCAL_RUN Items (14 files)

These files could not be fully classified at static-analysis depth. They need local execution to confirm:
1. Whether OkHttp interceptor-based tests in data/ai/provider/ actually parse real JSON responses correctly at runtime
2. Whether repository tests with MockK slot capture actually capture production-intended arguments
3. Whether Room-in-memory tests produce the same results as real SQLite on disk

Specifically:
- **CloudCategorizationAssistServiceTest** (#23) — verify suggest() parses full CategoryAssistSuggestion
- **CloudDashboardBriefingServiceTest** (#24) — verify generate() parses full DashboardBriefing
- **CloudDedupeJudgeServiceTest** (#25) — verify judge() handles edge cases (missing candidates, empty content)
- **CloudQueryInterpretationServiceTest** (#26) — verify interpret() differentiates structured/clarification/unsupported
- **CloudReceiptItemCategorizationServiceTest** (#28) — verify alias mapping works for all edge cases
- **SmartReceiptAssistServiceTest** (#42) — verify multi-level fallback under privacy gate conditions
- **HybridServiceDelegationTest** (#33) — verify routing actually invokes correct providers
- **AccountingExportRepositoryTest** (#87) — verify IIF/CSV/PDF export content
- **DatabaseBackupRepositoryImplTest** (#100) — verify WAL checkpoint + restore with real SQLite
- **GroupTransactionCoordinatorTest** (#47) — verify concurrent transaction behavior
- **BudgetRolloverTest** (#95) — verify that while-loop terminates with real calculation results
- **AutomatedSavingsRuleStateRepositoryTest** (#90) — verify DataStore survives process death
- **AiChatRepositoryImplTest** (#89) — verify withTransaction atomic commit
- **EmailReceiptIngestionServiceTest** (#70) — verify end-to-end provider detection

These should be run locally with \./gradlew test --tests "*ClassName"\" and results reported back.

---

## Legend

### Action
- **KEEP:** Test provides real value; no changes needed
- **DELETE:** Test adds negative value (tautological, zero production code exercised, dead coverage)
- **REWRITE:** Test has structure but needs cleanup (TODO markers, boilerplate reduction, edge case expansion)
- **MOVE_TO_NIGHTLY:** Test is @Ignore'd stress/performance test; move to nightly CI configuration
- **UNKNOWN_NEEDS_LOCAL_RUN:** Cannot fully classify without running locally

### Value
- **P0_CRITICAL:** Security, data loss, financial arithmetic, privacy, or deduplication contract
- **P1_HIGH:** Core business engine, architectural contract, or worker lifecycle
- **P2_MEDIUM:** DAO, parser, or service with moderate blast radius
- **P3_LOW:** Small utility, formatter, or single edge case
- **P4_NEGATIVE_VALUE:** Tautological test, zero assertions, dead code coverage

### Test Type
| Type | Meaning |
|------|---------|
| PURE_ENGINE | Pure Kotlin logic test with minimal/no Android dependencies |
| DAO_ROOM_CONTRACT | Room in-memory database test verifying DAO queries/inserts |
| REPOSITORY_INTEGRATION | Repository layer test with real DAO delegation (mockk + slot capture) |
| MIGRATION_SCHEMA | Room MigrationTestHelper schema migration tests |
| GOLDEN | Canonical fixture providing fixed inputs/expected outputs |
| MOCK_ORCHESTRATION | MockK-based verification of delegation, routing, or side-effect contracts |
| SOURCE_TEXT_ASSERTION | Tests scanning production source code for architectural patterns |
| PARSER | Tests feeding input strings and asserting parsed domain objects |
| WORKER_RUNTIME | WorkManager doWork() lifecycle tests with Robolectric |
| PRIVACY_SECURITY | Encryption, anonymization, or privacy gate enforcement |
| BACKUP_RESTORE | Database export/import lifecycle with real SQLite |
| STRESS_PERFORMANCE | @Ignore'd bulk operation or fuzzing tests |
| TRIVIAL_MODEL | Tests verifying only data class construction or self-evident properties |
| FIXTURE_INFRASTRUCTURE | Base classes, helper functions, or test DSL utilities |
