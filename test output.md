Shell .\gradlew testDebugUnitTest 2>&1 | Where-Object { $_ -match "tests|PASSED|FAILED|ERROR|BUILD|Task :app:test" }
        WARNING: Failed to set backing field (skipping)
    > Task :app:preBuild UP-TO-DATE
    > Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
    > Task :app:preDebugBuild UP-TO-DATE
    > Task :app:preDebugUnitTestBuild UP-TO-DATE
    > Task :app:generateDebugBuildConfig FROM-CACHE
    > Task :app:testDebugUnitTest
    DedupeKeyProducerConsistencyTest > DEPOSIT key contains DEPOSIT type suffix PASSED
    DedupeKeyProducerConsistencyTest > PURCHASE and DEPOSIT produce different keys for same transaction PASSED
    DedupeKeyProducerConsistencyTest > UNKNOWN type falls back to type-blind key for backward compat PASSED
    DedupeKeyProducerConsistencyTest > all producers agree on TRANSFER key PASSED
    DedupeKeyProducerConsistencyTest > all producers agree on PURCHASE key PASSED
    DedupeKeyProducerConsistencyTest > different currencies produce different keys for same type PASSED
    DedupeKeyProducerConsistencyTest > receipt and email-receipt producers emit identical key for same inputs PASSED
    DedupeKeyProducerConsistencyTest > PURCHASE key contains PURCHASE type suffix PASSED
    DedupeKeyProducerConsistencyTest > UNKNOWN key does not contain a type suffix PASSED
    DedupeKeyProducerConsistencyTest > all producers agree on DEPOSIT key PASSED
    DedupeKeyProducerConsistencyTest > key is deterministic across 100 calls for each producer type PASSED
    DedupeKeyProducerConsistencyTest > PURCHASE and TRANSFER produce different keys for same transaction PASSED
    MerchantKeyCrossConsumerConsistencyTest > edge - numbers in merchant PASSED
    MerchantKeyCrossConsumerConsistencyTest > consistency - clean then key matches direct key for normalized input
    PASSED
    MerchantKeyCrossConsumerConsistencyTest > consistency - dedupeKey format includes amount and merchant key PASSED
    MerchantKeyCrossConsumerConsistencyTest > stress - 200 merchants all paths produce consistent keys PASSED
    MerchantKeyCrossConsumerConsistencyTest > stress - Greek merchants produce stable keys PASSED
    MerchantKeyCrossConsumerConsistencyTest > consistency - direct MerchantKeyGenerator matches Expense dedupeKey path
    PASSED
    MerchantKeyCrossConsumerConsistencyTest > stress - mixed script merchants PASSED
    MerchantKeyCrossConsumerConsistencyTest > edge - special characters stripped uniformly PASSED
    MerchantKeyCrossConsumerConsistencyTest > edge - empty merchant produces empty key in both paths PASSED
    MerchantKeyCrossConsumerConsistencyTest > edge - whitespace merchant PASSED
    PrivacyStorageContractTest > RawContentSanitizer returns empty for DO_NOT_STORE PASSED
    CurrencyNormalizerConsistencyTest > consistency - null or blank default to EUR STANDARD_ERROR
    ConstantsConsistencyTest > all cross engine thresholds match documented values STANDARD_ERROR
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - ranked selection returns closest in time
    STANDARD_ERROR
    CrossParserConsistencyTest > consistency - Generic parser merchant produces valid key STANDARD_ERROR
    > Task :app:testDebugUnitTest
    EmptyZeroNullResilienceTest > all engines handle empty zero null style inputs with sensible finite defaults
    STANDARD_ERROR
    PrivacyStorageContractTest > RawStorageMode usages use exhaustive when blocks FAILED
        java.lang.AssertionError: Files using if/else instead of exhaustive when for RawStorageMode:
    [RawPersistencePolicyResolver.kt, PrivacySettingsScreen.kt]
    PrivacyStorageContractTest > NotificationCaptureService has DO_NOT_STORE branch PASSED
    CurrencyNormalizerConsistencyTest > consistency - null or blank default to EUR PASSED
    CurrencyNormalizerConsistencyTest > consistency - Generic parser EUR produces EUR PASSED
    CurrencyNormalizerConsistencyTest > consistency - valid 3-letter codes pass through PASSED
    CurrencyNormalizerConsistencyTest > consistency - same EUR variants normalize to EUR PASSED
    CurrencyNormalizerConsistencyTest > consistency - same GBP variants normalize to GBP PASSED
    CurrencyNormalizerConsistencyTest > consistency - same INR variants normalize to INR PASSED
    CurrencyNormalizerConsistencyTest > consistency - Greek bank EUR notification produces EUR currency PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - ranked selection returns closest in time
    PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - both methods use same amount tolerance PASSED
    CloudDedupeJudgeServiceTest > judge returns parse error for malformed verdict enum STANDARD_ERROR
    DuplicateLogicConsistencyIntegrationTest > stress - findPendingReviewDuplicate 100 calls same result PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - null currency parameter defaults to EUR and
    matches EUR review PASSED
    CurrencyNormalizerConsistencyTest > consistency - parsers using E or EUR symbol produce same normalized currency
    PASSED
    CurrencyNormalizerConsistencyTest > consistency - Revolut EUR notification produces EUR currency PASSED
    CurrencyNormalizerConsistencyTest > consistency - same USD variants normalize to USD PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findPendingReviewDuplicate returns match for exact same
    transaction PASSED
    CrossParserConsistencyTest > consistency - Generic parser merchant produces valid key PASSED
    MerchantKeyConsistencyTest > consistency - corporate suffix stripped by Rules produces same key as base name PASSED
    MerchantKeyConsistencyTest > consistency - same merchant produces same key via MerchantCleaner and MerchantRules
    PASSED
    MerchantKeyConsistencyTest > consistency - MerchantKeyGenerator is single source for keys PASSED
    CrossParserConsistencyTest > consistency - same merchant string produces same key across components PASSED
    DuplicateLogicConsistencyIntegrationTest > stress - findExpenseDuplicate 100 calls same result PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - same currency matches PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - both methods reject amount outside tolerance PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - compatible transaction types match PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findPendingReviewDuplicate returns null when suggestedDate
    is null PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findExpenseDuplicate returns null for different amount
    PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - isCrossSourceDuplicate returns NoDuplicate when empty
    PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - currency mismatch returns null PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - isCrossSourceDuplicate returns SameSourceDuplicate when
    same source PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - incompatible transaction type returns null
    PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findPendingReviewDuplicate returns null for different
    amount PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - oversized fallback duplicate is detected
    with unknown type PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - generateSourceAwareDedupeKey is deterministic PASSED
    CrossParserConsistencyTest > consistency - Expense generateDedupeKey uses MerchantKeyGenerator PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findExpenseDuplicate returns match for exact same
    transaction PASSED
    DuplicateLogicConsistencyIntegrationTest > findPendingReviewDuplicate - UNKNOWN type matches any review type PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - merchant similarity allows minor variations PASSED
    > Task :app:testDebugUnitTest
    CrossParserConsistencyTest > consistency - Revolut parser merchant produces valid key PASSED
    CrossParserConsistencyTest > consistency - Greek bank parser merchant produces valid key PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findExpenseDuplicate returns null for date outside window
    PASSED
    DuplicateLogicConsistencyIntegrationTest > consistency - findExpenseDuplicate returns null for different merchant
    PASSED
    CrossParserConsistencyTest > consistency - Greek and Latin merchant produce same key PASSED
    CrossParserConsistencyTest > consistency - amount parsing consistent across parsers for same format PASSED
    CrossParserConsistencyTest > consistency - cleaned merchant produces same key as raw for simple names PASSED
    HaversineConsistencyTest > consistency - GeoUtils haversineKm matches inline formula PASSED
    HaversineConsistencyTest > consistency - GeoUtils km to meters conversion matches meter formula PASSED
    HaversineConsistencyTest > consistency - haversineKmOrNull returns null for null inputs PASSED
    HaversineConsistencyTest > consistency - haversineKmOrNull matches haversineKm for valid inputs PASSED
    HaversineConsistencyTest > consistency - zero distance for same point PASSED
    SharedUtilityConsistencyTest > edge - empty string handling consistent PASSED
    SharedUtilityConsistencyTest > consistency - AmountUtils and AmountExtractionUtils agree on currency-prefixed
    amounts PASSED
    SharedUtilityConsistencyTest > consistency - CommonPatterns AMOUNT_REGEX matches what AmountUtils parses PASSED
    SharedUtilityConsistencyTest > consistency - AmountExtractionUtils extractAmount returns same value as
    extractFirstAmount PASSED
    SharedUtilityConsistencyTest > consistency - MerchantKeyGenerator strips non-alphanumeric uniformly PASSED
    SharedUtilityConsistencyTest > consistency - both null for invalid amounts PASSED
    SharedUtilityConsistencyTest > edge - whitespace only PASSED
    SharedUtilityConsistencyTest > consistency - AmountUtils and AmountExtractionUtils agree on European format PASSED
    SharedUtilityConsistencyTest > edge - amount in sentence PASSED
    SharedUtilityConsistencyTest > edge - very long merchant produces valid key PASSED
    SharedUtilityConsistencyTest > consistency - MerchantKeyGenerator idempotent for same input PASSED
    SharedUtilityConsistencyTest > edge - amount with thousands separator PASSED
    SharedUtilityConsistencyTest > consistency - MerchantKeyGenerator Greek and Latin produce same key PASSED
    SharedUtilityConsistencyTest > consistency - MerchantKeyGenerator case insensitive PASSED
    SharedUtilityConsistencyTest > consistency - AmountUtils and AmountExtractionUtils agree on simple amounts PASSED
    RecurringDeactivateContractTest > deactivateRule cancels future occurrences PASSED
    RecurringDeactivateContractTest > deactivateRule cancels planned expenses PASSED
    RecurringDeactivateContractTest > deactivateRule method exists PASSED
    RecurringDeactivateContractTest > deactivateRule checks writeBarrier PASSED
    RecurringDeactivateContractTest > deactivateRule suppresses reminders PASSED
    LifecycleBarrierContractTest > repositories with withTransaction reference writeBarrier PASSED
    LifecycleBarrierContractTest > all LifecycleCoordinators with transactions reference writeBarrier PASSED
    CloudQueryInterpretationServiceTest > interpret returns unsupported safely when api key is absent PASSED
    CloudCategorizationAssistServiceTest > suggest retries timeout once and succeeds on second attempt PASSED
    CloudCategorizationAssistServiceTest > suggest extracts first json object when multiple objects are present PASSED
    CloudCategorizationAssistServiceTest > suggest returns null when confidence is malformed PASSED
    CloudQueryInterpretationServiceTest > interpret sends alias only prompt context in redacted mode PASSED
    CloudQueryInterpretationServiceTest > interpret does not return unsupported on successful cloud response PASSED
    CloudQueryInterpretationServiceTest > interpret parses structured response with merchant names PASSED
    CloudCategorizationAssistServiceTest > suggest retries transient 500 and succeeds on second attempt PASSED
    CloudCategorizationAssistServiceTest > suggest returns null safely when api key is absent PASSED
    CloudCategorizationAssistServiceTest > suggest builds redacted prompt and maps alias response back to real category
    PASSED
    CloudCategorizationAssistServiceTest > suggest does not retry non-retryable 400 PASSED
    ConstantsConsistencyTest > all cross engine thresholds match documented values FAILED
    ConstantsConsistencyTest > no duplicate constants drift across engines FAILED
        java.lang.AssertionError
    FinancialArithmeticPrecisionTest > toCents and fromCents canonical 100 euro conversion is exact PASSED
    FinancialArithmeticPrecisionTest > 500 repeated conversions keep numerical drift under one cent PASSED
    FinancialArithmeticPrecisionTest > overflow boundary amount converts without integer overflow PASSED
    FinancialArithmeticPrecisionTest > roundtrip cents conversion is stable for representative values PASSED
    TimePeriodAnalyticsAlignmentTest > consistency - AnalyticsViewModel MONTH uses same logic as getMonthRange PASSED
    TimePeriodAnalyticsAlignmentTest > consistency - getStartOfDay and getEndOfDay are consistent PASSED
    TimePeriodAnalyticsAlignmentTest > consistency - getMonthRange offset -1 produces previous month PASSED
    TimePeriodAnalyticsAlignmentTest > consistency - AnalyticsViewModel WEEK uses same logic as getLastNDaysRange PASSED
    TimePeriodAnalyticsAlignmentTest > consistency - getLastNDaysRange produces valid 7-day range for WEEK PASSED
    TimePeriodAnalyticsAlignmentTest > consistency - getMonthRange produces valid current month range PASSED
    CloudCategorizationAssistServiceTest > suggest retries 429 up to max attempts then returns null PASSED
    CloudCategorizationAssistServiceTest > suggest returns null when categoryId is zero or non-numeric PASSED
    CloudCategorizationAssistServiceTest > suggest ignores invalid alternativeCategoryIds values without coercing to
    zero FAILED
        java.lang.AssertionError: Expected a valid suggestion for malformed list: ["abc"]
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    CloudReviewExplanationServiceTest > generate returns null safely when api key is absent PASSED
    OnDeviceDashboardBriefingServiceTest > buildPrompt assembles redacted transaction insight in data layer PASSED
    OnDeviceDashboardBriefingServiceTest > parseResponse handles clean JSON PASSED
    OnDeviceDashboardBriefingServiceTest > parseResponse handles markdown fenced JSON PASSED
    OnDeviceDashboardBriefingServiceTest > parseResponse returns null for invalid text PASSED
    HybridReceiptItemCategorizationServiceTest > categorizeItems uses on-device and never calls cloud when router
    selects on-device PASSED
    OnDeviceQueryInterpretationServiceTest > buildPrompt uses alias only lookup keys when redacted PASSED
    OnDeviceQueryInterpretationServiceTest > parseResponse resolves redacted aliases and multi value filters PASSED
    OnDeviceQueryInterpretationServiceTest > parseResponse honors explicit period payload PASSED
    OnDeviceQueryInterpretationServiceTest > parseResponse handles structured result PASSED
    OnDeviceQueryInterpretationServiceTest > parseResponse handles unsupported result PASSED
    OnDeviceQueryInterpretationServiceTest > buildPrompt includes raw query and known dimensions PASSED
    OnDeviceQueryInterpretationServiceTest > parseResponse handles clarification result PASSED
    CanonicalMultiCurrencyFixtureTest > FakeExchangeRateStore returns configured rates PASSED
    CloudRetryPolicyTest > isRetryableIoException inspects nested causes PASSED
    CloudRetryPolicyTest > constants match shared retry defaults PASSED
    CloudRetryPolicyTest > isRetryableIoException returns false for non transient io failures PASSED
    CloudRetryPolicyTest > isRetryableIoException returns true for timeout and connection reset PASSED
    CloudRetryPolicyTest > backoffDelayMs returns bounded exponential delay with jitter PASSED
    CloudRetryPolicyTest > isRetryableHttpStatus returns false for non retryable statuses PASSED
    CloudRetryPolicyTest > isRetryableHttpStatus returns true for 5xx, 429 and 408 PASSED
    OnDeviceDashboardBriefingServiceTest > buildPrompt includes dashboard inputs PASSED
    OnDeviceReviewExplanationServiceTest > parseResponse handles clean JSON PASSED
    OnDeviceReviewExplanationServiceTest > buildPrompt includes JSON schema PASSED
    OnDeviceReviewExplanationServiceTest > parseResponse returns null when headline or body missing PASSED
    OnDeviceReviewExplanationServiceTest > parseResponse handles markdown fenced JSON PASSED
    OnDeviceReviewExplanationServiceTest > buildPrompt includes review facts PASSED
    OnDeviceReviewExplanationServiceTest > parseResponse returns null for invalid text PASSED
    CanonicalMultiCurrencyFixtureTest > empty repository returns zero aggregate PASSED
    CanonicalMultiCurrencyFixtureTest > FakeCurrencySettingsRepository defaults to EUR PASSED
    MaintenanceSafeDiagnosticSinkTest > sink_interface_is_implemented_by_timber_impl PASSED
    CanonicalMultiCurrencyFixtureTest > createTestExpensesAndVerifyRawSum returns expenses and checks raw sum PASSED
    MaintenanceSafeDiagnosticSinkTest > sink_does_not_throw_in_any_mode PASSED
    MaintenanceSafeDiagnosticSinkTest > sink_accepts_null_pipeline_and_entity PASSED
    CanonicalMultiCurrencyFixtureTest > getHomeCurrencyTotal also returns correct converted total PASSED
    CanonicalMultiCurrencyFixtureTest > raw sumOf effectiveAmount equals 150 for regression comparison PASSED
    CanonicalMultiCurrencyFixtureTest > multi-currency conversion 50 EUR + 100 USD at 0_92 rate equals 142 EUR PASSED
    MaintenanceSafeDiagnosticSinkTest > mock_sink_can_verify_calls PASSED
    MaintenanceSafeDiagnosticSinkTest > write_barrier_exception_carries_mode_for_sink PASSED
    DatabaseBarrierTest > write_allowed_in_NORMAL PASSED
    DatabaseBarrierTest > normal_read_blocked_in_BACKUP_EXPORTING PASSED
    CloudDedupeJudgeServiceTest > judge returns parse error for malformed verdict enum PASSED
    CloudDedupeJudgeServiceTest > judge maps malformed target type enum and zero id to null PASSED
    MoneyContractTest > effectiveAmount summation only in currency-aware contexts PASSED
    DatabaseBarrierTest > runWrite_executes_block_in_NORMAL PASSED
    DatabaseBarrierTest > exception_carries_operation_metadata PASSED
    CloudDedupeJudgeServiceTest > judge returns disabled when api key is missing PASSED
    DatabaseBarrierTest > write_blocked_in_RESETTING_DATABASE PASSED
    CloudDedupeJudgeServiceTest > judge parses successful cloud JSON response PASSED
    EmptyZeroNullResilienceTest > all engines handle empty zero null style inputs with sensible finite defaults FAILED
        java.lang.AssertionError: expected:<75> but was:<50>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CloudDedupeJudgeServiceTest > judge returns offline failure when http client throws IOException PASSED
    DatabaseBarrierTest > runWrite_throws_in_non_NORMAL FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    DatabaseBarrierTest > string_overload_still_works_for_read PASSED
    DatabaseBarrierTest > export_read_allowed_in_BACKUP_EXPORTING PASSED
    DatabaseBarrierTest > write_blocked_in_CRITICAL_RECOVERY_REQUIRED PASSED
    DatabaseBarrierTest > string_overload_read_blocked_in_restore PASSED
    DatabaseBarrierTest > write_blocked_in_RESTORE_PREPARING PASSED
    DatabaseBarrierTest > string_overload_still_works_for_write PASSED
    DashboardBriefingResponseParserTest > parseResponse parses valid bounded confidence PASSED
    DashboardBriefingResponseParserTest > parseResponse returns null for non-finite confidence PASSED
    DashboardBriefingResponseParserTest > parseResponse returns null for out-of-range confidence PASSED
    EmptyZeroNullResilienceTest > stateflow take one emissions for all engine outputs complete normally PASSED
    DatabaseBarrierTest > staged_db_read_always_blocked_through_app_singleton PASSED
    DatabaseBarrierTest > normal_read_blocked_in_RESTORE_VERIFYING PASSED
    DatabaseBarrierTest > normal_read_allowed_in_NORMAL PASSED
    DatabaseBarrierTest > write_blocked_in_RESTORE_COMPLETE_RESTART_REQUIRED PASSED
    DatabaseBarrierTest > write_blocked_in_BACKUP_EXPORTING PASSED
    DatabaseBarrierTest > export_read_blocked_in_RESTORE_VERIFYING PASSED
    DatabaseBarrierTest > export_read_allowed_in_NORMAL PASSED
    TemporalConsistencyTest > leap year February 2024 period calculations stay correct PASSED
    OnDeviceNotificationParserTest > parseResponse keeps transfer metadata for deposit JSON PASSED
    OnDeviceNotificationParserTest > parseResponse keeps transfer metadata for transfer JSON PASSED
    OnDeviceNotificationParserTest > parseResponse drops transfer metadata for purchase JSON with direction PASSED
    TemporalConsistencyTest > empty period mode fallback is consistent with empty spending baseline FAILED
    CloudJsonParserTest > extractFirstJsonObject handles escaped quotes and backslashes PASSED
    CloudJsonParserTest > extractFirstJsonObject returns null for missing or incomplete json PASSED
    CloudJsonParserTest > extractFencedJsonObject returns null for missing or blank fenced block PASSED
    CloudJsonParserTest > extractFencedJsonObject extracts json from fenced block PASSED
    CloudJsonParserTest > extractFirstJsonObject prefers fenced json when available PASSED
    CloudJsonParserTest > optFiniteDoubleStrictOrNull throws for non numeric values PASSED
    CloudJsonParserTest > optFiniteDoubleStrictOrNull parses numbers and nullability correctly PASSED
    CloudJsonParserTest > optStrictLongStrictOrNull parses integer and nullability correctly PASSED
    CloudJsonParserTest > extractFirstJsonObject handles braces inside quoted strings PASSED
    CloudJsonParserTest > extractFirstJsonObject returns first object when multiple are present PASSED
    CloudJsonParserTest > optStrictLongStrictOrNull throws for decimal and non numeric values PASSED
    CloudJsonParserTest > extractFirstJsonObject returns first complete object from mixed text PASSED
    TemporalConsistencyTest > DST transition Athens March 29 2026 keeps monthly period boundaries correct PASSED
    TemporalConsistencyTest > budget and spending pace share same March 2026 boundaries PASSED
    CloudReceiptItemCategorizationServiceTest > categorizeItems redaction on does not include raw category names in
    payload PASSED
    CloudReceiptItemCategorizationServiceTest > categorizeItems maps fallback cat aliases when cloud options are empty
    PASSED
    CloudReceiptItemCategorizationServiceTest > categorizeItems keeps unknown alias unchanged when no matching category
    exists PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse handles clean JSON PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse handles alternativeCategoryIds PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null when categoryId is zero PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null when confidence is not finite PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse handles JSON with markdown fences PASSED
    OnDeviceCategorizationAssistServiceTest > buildPrompt omits context line when supportingText is null PASSED
    OnDeviceCategorizationAssistServiceTest > buildPrompt includes supporting text when present PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null confidence when field missing PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns empty list when alternativeCategoryIds absent PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse handles JSON with leading text PASSED
    OnDeviceCategorizationAssistServiceTest > buildPrompt contains JSON schema instruction PASSED
    OnDeviceCategorizationAssistServiceTest > buildPrompt includes all candidate categories PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse trims whitespace from categoryName PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null for malformed JSON PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null for non-JSON text PASSED
    OnDeviceCategorizationAssistServiceTest > buildPrompt includes merchant and amount PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null for empty string PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse filters invalid alternative category ids PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null when categoryId missing PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null when confidence is out of bounds PASSED
    OnDeviceCategorizationAssistServiceTest > parseResponse returns null when categoryName blank PASSED
    OnDeviceReceiptItemCategorizationServiceTest > categorizeItems uses keyword fallback for zero-overlap items PASSED
    MaintenanceOperationRunnerTest > reset_database_requires_restart_on_success PASSED
    AppOperationalStateTest > normal_mode_maps_to_Normal_state PASSED
    MaintenanceOperationRunnerTest > backup_export_drains_workers PASSED
    AppOperationalStateTest > restore_in_progress_modes_map_to_RestoreInProgress PASSED
    AppOperationalStateTest > backup_exporting_maps_to_BackupExporting_state PASSED
    AppOperationalStateTest > critical_recovery_required_maps_to_correct_state PASSED
    AppOperationalStateTest > startup_after_clean_restart_resets_mode_to_NORMAL PASSED
    MaintenanceOperationRunnerTest > drain_timeout_does_not_prevent_block_from_running FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MaintenanceOperationRunnerTest > block_result_is_returned PASSED
    AppOperationalStateTest > restart_required_blocks_writes PASSED
    AppOperationalStateTest > restore_success_sets_global_restart_required_lock PASSED
    MaintenanceOperationRunnerTest > exception_in_block_still_exits_maintenance FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1: RestoreMaintenanceMode(#33).exit(eq(false))) was not
    called
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MaintenanceOperationRunnerTest > reset_database_exits_to_normal_when_restart_not_required PASSED
    MaintenanceOperationRunnerTest > exception_with_restart_required_exits_with_restart FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1: RestoreMaintenanceMode(#41).exit(eq(true))) was not
    called
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MaintenanceOperationRunnerTest > reset_database_drains_workers_before_block PASSED
    MaintenanceOperationRunnerTest > reset_database_enters_maintenance PASSED
    MaintenanceOperationRunnerTest > backup_export_enters_BACKUP_EXPORTING PASSED
    CloudDashboardBriefingServiceTest > generate returns null safely when api key is absent PASSED
    CloudDashboardBriefingServiceTest > generate retries transient 429 and succeeds on second attempt PASSED
    CloudDashboardBriefingServiceTest > generate retries transient 500 and succeeds on second attempt PASSED
    CloudDashboardBriefingServiceTest > generate retries transient 408 and succeeds on second attempt PASSED
    CloudDashboardBriefingServiceTest > generate returns terminal 429 after max retries PASSED
    CloudWarrantyExtractionServiceTest > extractWarranty preserves return policy only responses without warranty months
    PASSED
    CloudWarrantyExtractionServiceTest > extractWarranty returns null when API key is missing PASSED
    CloudWarrantyExtractionServiceTest > extractWarranty returns null when model reports no warranty PASSED
    CloudWarrantyExtractionServiceTest > extractWarranty parses domain result from successful response PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse handles clean JSON PASSED
    OnDeviceDedupeJudgeServiceTest > buildPrompt includes subject and candidates PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse drops matchedTargetId when model emits zero PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse maps invalid matched target type to null PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse handles markdown fenced JSON PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse returns null for unknown verdict PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse returns null for non-finite confidence PASSED
    OnDeviceDedupeJudgeServiceTest > parseResponse returns null for invalid text PASSED
    SmartReceiptAssistServiceTest > suggest does not call cloud provider when router selects on-device PASSED
    SideEffectContractTest > dispatch calls are never inside withTransaction blocks PASSED
    SmartReceiptAssistServiceTest > suggest does not retry cloud fallback when router-selected on-device has no viable
    cloud route PASSED
    SmartReceiptAssistServiceTest > suggest skips ai providers when router selects deterministic fallback PASSED
    SmartReceiptAssistServiceTest > usedImageInput reflects the selected execution result PASSED
    SmartReceiptAssistServiceTest > suggest falls through from cloud to on device when cloud attempt fails PASSED
    SmartReceiptAssistServiceTest > suggest falls through from on device to cloud when local attempt fails PASSED
    SmartReceiptAssistServiceTest > suggest does not call on-device provider when router selects cloud PASSED
    SmartReceiptAssistServiceTest > suggest skips on-device when router selects disabled PASSED
    CloudReceiptAssistServiceTest > usedImageInput only reports true when image metadata exists PASSED
    ExportReadBarrierTest > restore_blocks_export_generation PASSED
    ExportReadBarrierTest > guardedDatabaseRead_passes_in_NORMAL PASSED
    ExportReadBarrierTest > restart_required_blocks_export_generation PASSED
    ExportReadBarrierTest > normal_app_read_blocked_in_BACKUP_EXPORTING PASSED
    ExportReadBarrierTest > guardedDatabaseRead_throws_in_restore FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CloudReceiptAssistServiceTest > suggest retries transient http failures and succeeds on later attempt PASSED
    CloudReceiptAssistServiceTest > buildRequestBodyForTest includes inline image data when allowed PASSED
    CloudReceiptAssistServiceTest > buildRequestBodyForTest suppresses inline image when redaction is required FAILED
        java.lang.AssertionError
            at com.yourname.expensetracker.data.ai.provider.CloudReceiptAssistServiceTest.buildRequestBodyForTest
    suppresses inline image when redaction is required(CloudReceiptAssistServiceTest.kt:174)
    CloudReceiptAssistServiceTest > buildRequestBodyForTest redacts merchant and text when redactBeforeCloud enabled
    FAILED
        java.lang.AssertionError
            at com.yourname.expensetracker.data.ai.provider.CloudReceiptAssistServiceTest.buildRequestBodyForTest
    redacts merchant and text when redactBeforeCloud enabled(CloudReceiptAssistServiceTest.kt:256)
    CloudReceiptAssistServiceTest > suggest returns null safely when api key is absent or request unsupported PASSED
    HybridServiceDelegationTest > usedImageInput returns false on disabled and deterministic-fallback routes PASSED
    HybridServiceDelegationTest > disabled mode skips cloud and on-device providers for all hybrid services PASSED
    HybridServiceDelegationTest > usedImageInput always returns false on hybrid service — no over-reporting on any route
    PASSED
    HybridServiceDelegationTest > usedImageInput returns false on non-cloud routes without consulting cloud service
    PASSED
    HybridServiceDelegationTest > fallback mode delegates all hybrid services to deterministic fallback providers PASSED
    HybridServiceDelegationTest > cloud mode delegates all hybrid services to cloud providers PASSED
    > Task :app:testDebugUnitTest
    HybridServiceDelegationTest > on-device mode delegates all hybrid services to on-device providers PASSED
    OnDeviceReceiptAssistServiceTest > parseResponse handles clean JSON PASSED
    OnDeviceReceiptAssistServiceTest > buildPrompt includes OCR and parsed values PASSED
    OnDeviceReceiptAssistServiceTest > buildPrompt includes JSON schema PASSED
    OnDeviceReceiptAssistServiceTest > parseResponse handles markdown fenced JSON PASSED
    OnDeviceReceiptAssistServiceTest > parseResponse keeps missing values as null PASSED
    OnDeviceReceiptAssistServiceTest > parseResponse returns null for invalid text PASSED
    OnDeviceReceiptAssistServiceTest > buildRequestForTest attaches image when valid image input exists FAILED
        java.lang.AssertionError
            at com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistServiceTest.buildRequestForTest
    attaches image when valid image input exists(OnDeviceReceiptAssistServiceTest.kt:66)
    OnDeviceReceiptAssistServiceTest > buildRequestForTest stays text only when image is missing PASSED
    MigrationRegistrationTest > migration 120 to 121 creates group_lifecycle_events table FAILED
    MigrationRegistrationTest > migration 119 to 121 creates both settlement and lifecycle tables FAILED
    MigrationRegistrationTest > ALL_MIGRATIONS includes migration 117 to 119 PASSED
    ExpenseDaoBoundaryConsistencyTest > boundary - expense at midnight boundary PASSED
    ExpenseDaoBoundaryConsistencyTest > affected - functions using inconsistent boundaries PASSED
    ExpenseDaoBoundaryConsistencyTest > impact - monthly total from getTotalForPeriod vs getExpensesInDateRange PASSED
    ExpenseDaoBoundaryConsistencyTest > impact - day totals at midnight PASSED
    ExpenseDaoBoundaryConsistencyTest > impact - analytics vs transactions list mismatch PASSED
    ExpenseDaoBoundaryConsistencyTest > boundary - endOfMonth from TimePeriodUtils PASSED
    ExpenseDaoBoundaryConsistencyTest > recommendation - standardize on half-open intervals PASSED
    ExpenseDaoBoundaryConsistencyTest > canonical week range from week key is monday start and next monday exclusive
    PASSED
    ExpenseDaoBoundaryConsistencyTest > recommendation - if using inclusive end, add 1ms PASSED
    ExpenseDaoBoundaryConsistencyTest > boundary - exactly midnight end timestamp PASSED
    ExpenseDaoBoundaryConsistencyTest > range - February 2024 leap year with mixed boundaries PASSED
    ExpenseDaoBoundaryConsistencyTest > range - exactly month boundary with timestamp at boundary PASSED
    ExpenseDaoBoundaryConsistencyTest > document - queries using inclusive end date PASSED
    ExpenseDaoBoundaryConsistencyTest > boundary - overlapping periods with mixed boundaries PASSED
    ExpenseDaoBoundaryConsistencyTest > document - queries using exclusive end date PASSED
    BankConnectionDaoTest > disconnect wipes all credential and flag fields in one call PASSED
    BackgroundJobRunDaoTest > update job run sets finishedAt and status PASSED
    BankConnectionDaoTest > disconnect resets tokenEncryptionVersion to 0 PASSED
    BackgroundJobRunDaoTest > getStaleRunningRuns returns RUNNING runs older than threshold PASSED
    BankConnectionDaoTest > disconnect clears tokenExpiry to null PASSED
    RecommendationDaoTest > clearByUser removes all recommendations for user FAILED
        java.lang.AssertionError: expected:<1> but was:<0>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BackgroundJobRunDaoTest > getRecent filters by workerName PASSED
    BankConnectionDaoTest > disconnect clears accessToken to null PASSED
    BackgroundJobRunDaoTest > insert a job run PASSED
    RecommendationDaoTest > getActiveByUser respects max 5 limit PASSED
    BankConnectionDaoTest > disconnect sets isActive to false PASSED
    BackgroundJobRunDaoTest > getStaleRunningRuns returns empty when no stale runs PASSED
    RecommendationDaoTest > expireOld does not update already expired records PASSED
    GroupTransactionCoordinatorTest > deleteGroupAtomic should handle non-existent group gracefully PASSED
    BankConnectionDaoTest > disconnect affects only the targeted row when multiple connections exist PASSED
    BackgroundJobRunDaoTest > insert with full field set PASSED
    RecommendationDaoTest > expireOld marks old records as EXPIRED PASSED
    BankConnectionDaoTest > disconnect preserves non-credential fields untouched PASSED
    BackgroundJobRunDaoTest > getRecent respects limit PASSED
    RecommendationDaoTest > countActive returns correct count PASSED
    BankConnectionDaoTest > disconnect sets isConnected to false PASSED
    > Task :app:testDebugUnitTest
    GroupTransactionCoordinatorTest > deleteGroupAtomic should remove group members and expenses PASSED
    BackgroundJobRunDaoTest > getRecent returns job runs ordered by startedAt DESC PASSED
    BankConnectionDaoTest > disconnect on non-existent id is a no-op and does not throw PASSED
    RecommendationDaoTest > archiveActiveOverflow archives only active rows outside retained set PASSED
    PrivacyAuditDaoTest > verify ordering by timestamp descending PASSED
    GroupTransactionCoordinatorTest > addMemberToGroup is atomic - validates inside transaction PASSED
    BankConnectionDaoTest > disconnect clears refreshToken to null PASSED
    RecommendationDaoTest > getActiveByUser orders by priority then createdAt DESC PASSED
    PrivacyAuditDaoTest > insert an audit event PASSED
    GroupTransactionCoordinatorTest > createGroupWithMembersAtomic should assign correct groupId to members PASSED
    BankConnectionDaoTest > getConnectedCount decrements after disconnect PASSED
    PrivacyAuditDaoTest > insert multiple events and verify content PASSED
    RecommendationDaoTest > getById returns null for non-existent ID PASSED
    BankConnectionDaoTest > disconnect is idempotent when called twice PASSED
    PrivacyAuditDaoTest > getRecent returns all events within limit PASSED
    RecommendationDaoTest > insert adds record successfully PASSED
    GroupTransactionCoordinatorTest > addExpenseWithLink normalizes linked existing expense ownership fields FAILED
        but was instance of : com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult$Error
        with value          : Error(message=Custom split payload must be valid JSON)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RawNotificationDaoTest > query by package name returns matching notifications PASSED
    PrivacyAuditDaoTest > getRecent returns empty list when no events exist PASSED
    RecommendationDaoTest > deleteExpired removes expired recommendations PASSED
    RawNotificationDaoTest > deleteAll removes all notifications PASSED
    PrivacyAuditDaoTest > getRecent respects limit parameter PASSED
    GroupTransactionCoordinatorTest > transaction integrity - all operations succeed or all fail PASSED
    RecommendationDaoTest > archive sets dismissedAt and status to ARCHIVED PASSED
    DailyBriefingWorkerTest > worker retries when delivery times out FAILED
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TransactionEventDaoTest > verify ordering by timestamp descending PASSED
    RawNotificationDaoTest > insert a raw notification and query by id PASSED
    DailyBriefingWorkerTest > worker returns success PASSED
    GroupTransactionCoordinatorTest > addExpenseWithLink is atomic - validates inside transaction FAILED
        but was instance of : com.yourname.expensetracker.domain.groups.GroupExpenseCreationResult$Error
        with value          : Error(message=Current user member not found or share could not be calculated)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    DailyBriefingWorkerTest > worker handles engine failure gracefully FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RecommendationDaoTest > getAllActiveByUser returns full active set beyond capped query PASSED
    TransactionEventDaoTest > insert a CREATED event and query by id PASSED
    DailyBriefingWorkerTest > worker propagates CancellationException instead of returning success PASSED
    DailyBriefingWorkerTest > briefing generated and stored PASSED
    RawNotificationDaoTest > insert same notification twice via regular insert returns different ids FAILED
            java.util.concurrent.ExecutionException: com.almworks.sqlite4java.SQLiteException: [2067] DB[16] step()
    [INSERT OR ABORT INTO `raw_notifications`
    (`id`,`packageName`,`appName`,`title`,`text`,`bigText`,`subText`,`extrasJson`,`timestamp`,`capturedAt`,`isProcessed`
    ,`isRelevant`,`parseResult`,`rawContentPurgedAt`,`dedupeFingerprint`) VALUES (nullif(?,
    0),?,?,?,?,?,?,?,?,?,?,?,?,?,?)]DB[16][C] [UNIQUE constraint failed: raw_notifications.packageName,
    raw_notifications.timestamp, raw_notifications.title, raw_notifications.text]
                com.almworks.sqlite4java.SQLiteException: [2067] DB[16] step() [INSERT OR ABORT INTO `raw_notifications`
    (`id`,`packageName`,`appName`,`title`,`text`,`bigText`,`subText`,`extrasJson`,`timestamp`,`capturedAt`,`isProcessed`
    ,`isRelevant`,`parseResult`,`rawContentPurgedAt`,`dedupeFingerprint`) VALUES (nullif(?,
    0),?,?,?,?,?,?,?,?,?,?,?,?,?,?)]DB[16][C] [UNIQUE constraint failed: raw_notifications.packageName,
    raw_notifications.timestamp, raw_notifications.title, raw_notifications.text]
    TransactionEventDaoTest > query events by expenseId returns empty list for unknown expense PASSED
    DailyBriefingWorkerTest > no data empty briefing stored PASSED
    RecommendationDaoTest > getArchived returns archived recommendations ordered by dismissedAt DESC PASSED
    GroupTransactionCoordinatorTest > addExpenseToGroupAtomic should handle insert with no extra args PASSED
    ExchangeRateStoreAdapterTest > delete stale rates delegates to dao cleanup method PASSED
    ExchangeRateStoreAdapterTest > save rate delegates to dao upsert PASSED
    RawNotificationDaoTest > verify all fields are persisted correctly PASSED
    ExchangeRateStoreAdapterTest > get rate delegates to dao correctly PASSED
    ExchangeRateStoreAdapterTest > get all rates for base currency returns filtered results PASSED
    TransactionEventDaoTest > events for different expenseIds do not mix PASSED
    RecommendationDaoTest > getActiveByUser returns only active non-archived non-expired recommendations PASSED
    TransactionEventDaoTest > insert multiple events and verify count PASSED
    RawNotificationDaoTest > markRelevance sets isRelevant flag PASSED
    GroupTransactionCoordinatorTest > createSystemExpenseAndLinkToGroup stores current user share on linked system
    expense PASSED
    RecommendationDaoTest > insert with REPLACE strategy updates existing record FAILED
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MileageTrackingValidationTest > MileageTracking entity remains constructible for legacy rows PASSED
    RawNotificationDaoTest > insert multiple notifications and verify ordering by capturedAt DESC PASSED
    AppleReceiptParserTest > parse does not infer EUR from incidental substring tokens PASSED
    AppleReceiptParserTest > parse handles standalone localized date and comma decimal amount PASSED
    TransactionEventDaoTest > query events by expenseId returns matching events PASSED
    RawNotificationDaoTest > verify deduplication via insertOrIgnore returns -1 for duplicate fingerprint PASSED
    GroupTransactionCoordinatorTest > createGroupWithMembersAtomic should handle single member PASSED
    TransactionEventDaoTest > insert event with nullable expenseId PASSED
    ExpenseWithCategoryFormattedTimeTest > formattedTime returns Unknown for invalid epoch PASSED
    RawNotificationDaoTest > query by package name returns empty list for unknown package PASSED
    ExpenseWithCategoryFormattedTimeTest > formattedDate and formattedTime are different for the same instance PASSED
    ExpenseWithCategoryFormattedTimeTest > formattedTime (extension) returns HH-mm format PASSED
    ExpenseWithCategoryFormattedTimeTest > formattedTime contains only the time portion of formattedDate PASSED
    ExpenseWithCategoryFormattedTimeTest > formattedDate (member) returns MMM dd HH-mm format PASSED
    CategoryTest > invalid color throws exception PASSED
    CategoryTest > empty category name throws exception PASSED
    CategoryTest > color without hash throws exception PASSED
    CategoryTest > blank category name throws exception PASSED
    CategoryTest > icon too long throws exception PASSED
    CategoryTest > category name too long throws exception PASSED
    CategoryTest > valid category creates successfully PASSED
    UberReceiptParserTest > parse handles localized ride total and labeled date FAILED
        java.lang.AssertionError: expected:<12.34> but was:<4.0>
    UberReceiptParserTest > parse does not infer EUR from incidental ORDER token FAILED
        java.lang.AssertionError
    UberReceiptParserTest > parse year-less near-new-year date clamps future date to previous year FAILED
        java.lang.AssertionError
    UberReceiptParserTest > parse year-less uber date anchored to receivedAt year FAILED
        java.lang.AssertionError: expected:<1741305600000> but was:<1752105600000>
    UberReceiptParserTest > parse uses timestamped ride date subgroup instead of am pm token FAILED
        java.lang.AssertionError: expected:<23.45> but was:<5.0>
    UberReceiptParserTest > parse handles localized eats total and order date FAILED
        java.lang.AssertionError
    > Task :app:testDebugUnitTest
    GroupTransactionCoordinatorTest > createSystemExpenseAndLinkToGroup fails for inactive group PASSED
    GeocodingCancellationTest > photon cancellation rethrows CancellationException and cancels underlying call FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    GroupTransactionCoordinatorTest > addMemberToGroup returns invalid group error for inactive group PASSED
    GeocodingCancellationTest > executeCancellable cancels underlying call when coroutine is cancelled FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    LogSanitizerTest > anonymizeForLog returns a non raw token PASSED
    LogSanitizerTest > anonymizeForLog produces materially different output for distinct inputs PASSED
    LogSanitizerTest > anonymizeForLog is stable within the current process PASSED
    EmailReceiptDaoTest > insert email receipt source PASSED
    GroupTransactionCoordinatorTest > addExpenseWithLink fails closed when current user member is missing and rolls back
    PASSED
    GroupTransactionCoordinatorTest > addExpenseToGroupAtomic rejects already linked system expense PASSED
    LocationBackfillWorkerTest > worker returns success PASSED
    EmailReceiptDaoTest > getByReceiptId retrieves email sources for a given receipt PASSED
    LocationBackfillWorkerTest > expenses without location backfill attempted PASSED
    LocationBackfillWorkerTest > worker handles geocoding failure gracefully FAILED
        java.lang.RuntimeException: Some backfill resolutions failed, will retry
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GroupTransactionCoordinatorTest > createSystemExpenseAndLinkToGroup atomically creates both records PASSED
    LocationBackfillWorkerTest > retryable resolver result does not consume attempt budget FAILED
        java.lang.RuntimeException: Some backfill resolutions failed, will retry
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    EmailReceiptIngestionServiceTest > insertOrIgnore preserves original row when duplicate emailMessageId is seen
    PASSED
    LocationBackfillWorkerTest > all expenses have location no work PASSED
    EmailReceiptDaoTest > getByFingerprint retrieves receipt by fingerprint PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt returns ParseError for unknown provider with unparsable body
    FAILED
        java.lang.AssertionError
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest$processEmailReceipt returns
    ParseError for unknown provider with unparsable body$1.invokeSuspend(EmailReceiptIngestionServiceTest.kt:262)
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest$processEmailReceipt returns
    ParseError for unknown provider with unparsable body$1.invoke(EmailReceiptIngestionServiceTest.kt)
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest$processEmailReceipt returns
    ParseError for unknown provider with unparsable body$1.invoke(EmailReceiptIngestionServiceTest.kt)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest.processEmailReceipt returns
    ParseError for unknown provider with unparsable body(EmailReceiptIngestionServiceTest.kt:252)
    EmailReceiptIngestionServiceTest > processEmailReceipt messageId guard does not fire for unknown nonblank messageId
    PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt skips messageId guard when messageId is whitespace only
    PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt skips messageId guard when messageId is blank PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt detects Uber and Apple providers PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt links duplicate when matching scanned receipt exists PASSED
    GroupTransactionCoordinatorTest > concurrent transactions should not interfere PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt deduplicates by locale-safe fingerprint using Locale_US
    formatting PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt detects Amazon provider and creates expense PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt returns ParseError for malformed email FAILED
        java.lang.AssertionError
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest$processEmailReceipt returns
    ParseError for malformed email$1.invokeSuspend(EmailReceiptIngestionServiceTest.kt:248)
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest$processEmailReceipt returns
    ParseError for malformed email$1.invoke(EmailReceiptIngestionServiceTest.kt)
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest$processEmailReceipt returns
    ParseError for malformed email$1.invoke(EmailReceiptIngestionServiceTest.kt)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
            at com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest.processEmailReceipt returns
    ParseError for malformed email(EmailReceiptIngestionServiceTest.kt:237)
    EmailReceiptIngestionServiceTest > processEmailReceipt returns Duplicate immediately when nonblank messageId already
    exists PASSED
    EmailReceiptIngestionServiceTest > processEmailReceipt returns ParseError when expense creation yields no ids PASSED
    AndroidForegroundLocationProviderTest > documented fallback path expects cached last location after fresh fix
    failure PASSED
    EmailReceiptDaoTest > getCount returns correct count PASSED
    > Task :app:testDebugUnitTest
    GroupTransactionCoordinatorTest > addExpenseWithLink rejects already linked system expense PASSED
    EmailReceiptDaoTest > deleteAll removes all email receipt sources PASSED
    BudgetRepositoryHistoricalStatusTest > getBudgetStatusesAt shares same derivation for category budgets PASSED
    GroupTransactionCoordinatorTest > transaction should be atomic - partial failure rolls back everything PASSED
    BudgetRepositoryHistoricalStatusTest > getBudgetStatusesAt uses explicit evaluation time instead of current time
    FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invoke(TestBuilders.kt)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invoke(TestBuilders.kt)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1.invokeSuspend(TestBuilders.kt:313)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1.invoke(TestBuilders.kt)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1.invoke(TestBuilders.kt)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt$createTestResult$1.invokeSuspend(TestBuildersJvm.kt:11)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    EmailReceiptDaoTest > getCountByProvider returns correct counts PASSED
    EmailReceiptDaoTest > query by provider returns empty list for unknown provider PASSED
    ExpenseRepositoryTest > assistant filtered helpers keep multi value list and count filters in sync PASSED
    ExpenseRepositoryTest > getExpensesPagedDynamic SQL uses SELECT e-star so newer fields are not dropped PASSED
    MerchantKeyBackfillWorkerTest > doWork happy path populates null merchant keys PASSED
    MerchantKeyBackfillWorkerTest > doWork retries when same failing row repeats after partial progress FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MerchantKeyBackfillWorkerTest > doWork empty database returns success PASSED
    ExpenseRepositoryTest > updateExpenseMerchant applyToAll updates merchant and key in bulk and pending reviews PASSED
    GroupTransactionCoordinatorTest > createGroupWithMembersAtomic should rollback when member insert fails PASSED
    MerchantKeyBackfillWorkerTest > doWork retries when batch makes no progress FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExpenseRepositoryTest > updateExpenseMerchant noops when merchant unchanged PASSED
    MerchantKeyBackfillWorkerTest > doWork idempotent skips expenses already having a key PASSED
    ExpenseRepositoryTest > updateExpenseMerchant updates merchant and learns alias PASSED
    EmailReceiptDaoTest > verify ordering by parsedAt DESC PASSED
    ExpenseRepositoryTest > searchMerchants returns empty list for blank query PASSED
    AiArtifactRepositoryImplTest > deleteExpired delegates to dao with given timestamp PASSED
    GroupTransactionCoordinatorTest > createGroupWithMembersAtomic should insert group and members successfully PASSED
    ExpenseRepositoryTest > updateExpenseCategory updates category and records user correction PASSED
    AiArtifactRepositoryImplTest > getLatest delegates to dao with capability name PASSED
    EmailReceiptDaoTest > insertOrIgnore returns -1 for duplicate emailMessageId PASSED
    AiArtifactRepositoryImplTest > markApplied delegates to dao PASSED
    AiArtifactRepositoryImplTest > upsert delegates to dao and returns row id PASSED
    ExpenseRepositoryTest > getExpensesPagedDynamic preserves isBusinessExpense and splitTemplateId from DAO result
    PASSED
    AiArtifactRepositoryImplTest > getLatest returns null when dao returns null PASSED
    AiArtifactRepositoryImplTest > observeLatest passes capability name for DASHBOARD_BRIEFING PASSED
    AiArtifactRepositoryImplTest > markDismissed delegates to dao PASSED
    AiArtifactRepositoryImplTest > deleteByTargetKey delegates to dao PASSED
    AiArtifactRepositoryImplTest > observeLatest delegates to dao with capability name PASSED
    ExpenseRepositoryTest > getExpensesPagedDynamic constructs correct query with search and sort PASSED
    NotificationProcessingPipelineOversizedAmountTest > detectTransactionSignalCandidate prefers currency-attached
    amount over bare numbers PASSED
    NotificationProcessingPipelineOversizedAmountTest > ignores oversized number without transaction and currency
    context PASSED
    NotificationProcessingPipelineOversizedAmountTest > detectTransactionSignalCandidate returns candidate for normal
    transaction-like text PASSED
    NotificationProcessingPipelineOversizedAmountTest > detectTransactionSignalCandidate handles suffix currency and PAN
    tail PASSED
    NotificationProcessingPipelineOversizedAmountTest > detectTransactionSignalCandidate picks amount near transaction
    keyword PASSED
    NotificationProcessingPipelineOversizedAmountTest > does not route normal high but valid amounts PASSED
    NotificationProcessingPipelineOversizedAmountTest > detectTransactionSignalCandidate returns null for
    non-transaction text PASSED
    NotificationProcessingPipelineOversizedAmountTest > routes oversized transaction-like notification to review
    candidate PASSED
    GroupTransactionCoordinatorTest > addExpenseToGroupAtomic should insert expense record PASSED
    RecommendationRepositoryTest > countActive delegates to DAO PASSED
    RecommendationRepositoryTest > getById returns mapped domain model PASSED
    RecommendationRepositoryTest > saveAll enforces max 5 limit PASSED
    RecommendationRepositoryTest > saveAll prioritizes HIGH over MEDIUM over LOW PASSED
    AccountingExportRepositoryTest > exportExpenses multi-page QuickBooks IIF contains all rows end-to-end PASSED
    RecommendationRepositoryTest > getActiveForUser delegates to DAO and maps to domain model PASSED
    RecommendationRepositoryTest > getArchivedForUser delegates to DAO and maps to domain PASSED
    RecommendationRepositoryTest > cleanupExpired delegates to DAO deleteExpired PASSED
    GroupTransactionCoordinatorTest > createSystemExpenseAndLinkToGroup fails for non-member payer PASSED
    RecommendationRepositoryTest > expireAll calls DAO expireOld PASSED
    RecommendationRepositoryTest > dismiss calls DAO archive PASSED
    EmailReceiptDaoTest > getRecent returns receipts parsed after given time PASSED
    AccountingExportRepositoryTest > exportExpenses empty accounting dataset writes header only export PASSED
    AccountingExportRepositoryTest > export small dataset returns all records via deterministic path PASSED
    RecommendationRepositoryTest > saveAll merges with existing active set and archives overflow deterministically
    FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1: RecommendationDao(#249).insertAll(matcher<List>(),
    any())). Only one matching call to RecommendationDao(#249)/insertAll(List, Continuation) happened, but arguments are
    not matching:
        kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)
                                                   kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)
                                              kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)
                                                          kotlinx.coroutines.BuildersKt.runBlocking
    (-:1)
                                              kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)
                                                          kotlinx.coroutines.BuildersKt.runBlocking$default
    (-:1)
                                              kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)
                                 kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)
                                                 kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)
                                 kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)
                                                 kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)
                                 kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)
                                                 kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default
    (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RecommendationRepositoryTest > getById returns null when DAO returns null PASSED
    RecommendationRepositoryTest > save wraps insert and converts to entity PASSED
    RecommendationRepositoryTest > repository runs operations on IO dispatcher PASSED
    RecommendationRepositoryTest > observeActiveForUser returns Flow and maps entities to domain PASSED
    RecommendationRepositoryTest > clearForUser calls DAO clearByUser PASSED
    RecommendationRepositoryTest > saveAll prunes existing overflow even when incoming batch is fully duplicate PASSED
    RecommendationRepositoryTest > expireOld calls DAO expireOld PASSED
    GroupTransactionCoordinatorTest > createGroupWithMembersAtomic should handle empty member list PASSED
    SecureKeyStorageTest > migrateFromBuildConfigIfNeeded should not overwrite existing keys SKIPPED
    SecureKeyStorageTest > migrateFromBuildConfigIfNeeded should migrate non-null keys only SKIPPED
    DurableDiagnosticsAcceptanceTest > notification_received_event_has_correct_fields PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_blocks_reason_authorization PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_blocks_status_token PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_allows_provider_transaction_id_hash PASSED
    DurableDiagnosticsAcceptanceTest > exception_sanitizer_redacts_file_path PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_allows_source_id_hash PASSED
    DurableDiagnosticsAcceptanceTest > composite_writer_safe_sink_preserves_correlation_id PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_blocks_source_raw_text PASSED
    DurableDiagnosticsAcceptanceTest > side_effect_failed_is_terminal PASSED
    DurableDiagnosticsAcceptanceTest > side_effect_failed_preserves_caller_metadata PASSED
    DurableDiagnosticsAcceptanceTest > exception_sanitizer_redacts_long_account_digits PASSED
    DurableDiagnosticsAcceptanceTest > email_outer_exception_event_is_terminal_failed_final PASSED
    DurableDiagnosticsAcceptanceTest > known_dangerous_keys_are_blocked PASSED
    DurableDiagnosticsAcceptanceTest > bank_sync_metadata_hashes_provider_transaction_id PASSED
    DurableDiagnosticsAcceptanceTest > side_effect_completed_is_terminal PASSED
    DurableDiagnosticsAcceptanceTest > operation_run_started_event_is_not_terminal PASSED
    AccountingExportRepositoryTest > exportExpenses multi-page Xero CSV contains all rows end-to-end PASSED
    EmailReceiptDaoTest > query by email sender returns matching receipts PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_blocks_source_access_token PASSED
    DurableDiagnosticsAcceptanceTest > safe_event_metadata_merge_with_empty_returns_original PASSED
    DurableDiagnosticsAcceptanceTest > exception_sanitizer_truncates_large_blob PASSED
    DurableDiagnosticsAcceptanceTest > safe_event_metadata_merge_preserves_both PASSED
    DurableDiagnosticsAcceptanceTest > exception_sanitizer_redacts_iban PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_allows_exact_source PASSED
    DurableDiagnosticsAcceptanceTest > known_safe_keys_are_not_blocked PASSED
    DurableDiagnosticsAcceptanceTest > metadata_sanitizer_blocks_source_full_path PASSED
    NotificationParsingModelsTest > NotificationParseResult rejects out-of-range confidence PASSED
    NotificationParsingModelsTest > NotificationParseResult rejects non-positive amount PASSED
    AccountingExportRepositoryTest > exportExpenses rejects mixed currency accounting dataset PASSED
    AccountingExportRepositoryTest > exportExpenses accountant report pdf writes pdf output FAILED
        java.lang.AssertionError: PDF export must succeed
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AccountingExportRepositoryTest > export uses effectiveAmount for shared expenses PASSED
    AccountingExportRepositoryTest > exportExpenses rejects non purchase accounting dataset PASSED
    EmailReceiptDaoTest > query by provider returns matching receipts PASSED
    BudgetRepositoryTruncationTest > A10 Batch3 - rollover surplus is not affected by non-PURCHASE types PASSED
    AccountingExportRepositoryTest > exportExpenses QuickBooks IIF uses funding account on TRNS and category on SPL
    PASSED
    BudgetRepositoryTruncationTest > A10 Batch3 - budget spend uses only PURCHASE-filtered aggregate queries PASSED
    BudgetRepositoryTruncationTest > truncation regression - WARNING threshold reached with large aggregate PASSED
    BudgetRepositoryTruncationTest > truncation regression - 2500-row equivalent total is not capped at 2000 PASSED
    BudgetRepositoryTruncationTest > truncation regression - 800-row equivalent total is not capped at 500 PASSED
    CategorizeReceiptItemsUseCaseTest > invoke restores receipt status to pending when service returns null after
    analyzing PASSED
    AccountingExportRepositoryTest > exportExpenses multi-page FreshBooks CSV contains all rows end-to-end PASSED
    BudgetRepositoryTruncationTest > truncation regression - category budget with 3000-row equivalent uses aggregate
    PASSED
    EmailReceiptDaoTest > getByMessageId retrieves receipt by unique message id PASSED
    BudgetRepositoryTruncationTest > A10 Batch3 - category budget spend uses only PURCHASE-filtered category aggregate
    PASSED
    BudgetRepositoryTruncationTest > truncation regression - rollover history uses aggregate per window not capped rows
    PASSED
    AccountingExportRepositoryTest > fetchAllForExport multi page - exhaustive paging returns all rows PASSED
    AccountingExportRepositoryTest > fetchAllForExport single page - all rows returned PASSED
    BudgetRepositoryTruncationTest > truncation regression - CRITICAL threshold reached with large aggregate PASSED
    AccountingExportRepositoryTest > fetchAllForExport exact page boundary triggers termination call PASSED
    AccountingExportRepositoryTest > fetchAllForExport empty range returns empty list PASSED
    EmailReceiptDaoTest > query email receipt by id PASSED
    GenerateDashboardBriefingUseCaseTest > invoke stores READY artifact with briefing text when provider succeeds PASSED
    GenerateDashboardBriefingUseCaseTest > invoke returns immediately when dashboardBriefingEnabled is false PASSED
    GenerateDashboardBriefingUseCaseTest > invoke regenerates when ready artifact source hash is stale PASSED
    GenerateDashboardBriefingUseCaseTest > invoke propagates CancellationException without writing FAILED artifact
    PASSED
    GenerateDashboardBriefingUseCaseTest > invoke truncates briefing text to MAX_BRIEFING_LENGTH_CHARS PASSED
    GenerateDashboardBriefingUseCaseTest > invoke stores FAILED artifact when provider returns failure PASSED
    GenerateDashboardBriefingUseCaseTest > invoke skips generation when fresh READY artifact already exists PASSED
    GenerateDashboardBriefingUseCaseTest > invoke sets expiresAt to now plus dashboard TTL PASSED
    GenerateDashboardBriefingUseCaseTest > invoke stores FAILED artifact when provider throws PASSED
    GenerateDashboardBriefingUseCaseTest > invoke returns immediately when aiEnabled is false PASSED
    EmailReceiptDaoTest > deleteOlderThan removes old receipts PASSED
    PrioritizeReviewItemsUseCaseTest > ties broken by date PASSED
    PrioritizeReviewItemsUseCaseTest > empty list empty result PASSED
    PrioritizeReviewItemsUseCaseTest > high confidence items prioritized first PASSED
    PrioritizeReviewItemsUseCaseTest > score calculation correct PASSED
    SyncProactiveBriefingWorkUseCaseTest > invoke uses provided override settings PASSED
    BudgetRepositorySuggestionsBatchTest > getSuggestions batches category totals in single grouped query PASSED
    SyncProactiveBriefingWorkUseCaseTest > invoke cancels work when proactive briefings are disabled PASSED
    SyncProactiveBriefingWorkUseCaseTest > invoke schedules work when proactive dashboard briefings are fully enabled
    PASSED
    ReceiptEventDaoTest > verify ordering by timestamp descending PASSED
    ReceiptEventDaoTest > query events by receiptId returns matching events PASSED
    AdvancedAnalyticsEngineTest > test getPeriodRange for WEEK PASSED
    AdvancedAnalyticsEngineTest > test getSpendingPatterns detects Weekend Warrior PASSED
    AdvancedAnalyticsEngineTest > test getStatisticalInsights calculations PASSED
    DayOfWeekAnalyzerTest > analyze returns seven zeroed day buckets when no expenses exist PASSED
    DayOfWeekAnalyzerTest > analyze maps calendar days to monday-zero indexing correctly for bug b17 PASSED
    DayOfWeekAnalyzerTest > analyze keeps monday to sunday order even when spend ranking differs PASSED
    DayOfWeekAnalyzerTest > analyze shows weekend spending higher than weekday for golden march purchases PASSED
    MonthlyComparisonCalculatorTest > calculate with zero previous month keeps change amount and percentage null PASSED
    MonthlyComparisonCalculatorTest > calculate tracks count changes while filtering deposits and not-mine transactions
    PASSED
    MonthlyComparisonCalculatorTest > calculate returns expected month over month percentage for golden march versus
    february PASSED
    SpendingThresholdCalculatorTest > refreshThresholds prevents stale in-flight recompute from overwriting cache PASSED
    SpendingThresholdCalculatorTest > getThreshold convenience method works for single-user PASSED
    SpendingThresholdCalculatorTest > calculateHighAmountThreshold returns P90 for typical spending PASSED
    SpendingThresholdCalculatorTest > calculateHighAmountThreshold enforces minimum threshold PASSED
    SpendingThresholdCalculatorTest > refreshThreshold clears cache for default user PASSED
    SpendingThresholdCalculatorTest > calculateHighAmountThreshold uses correct user ID for caching PASSED
    SpendingThresholdCalculatorTest > expired cache entry is replaced by fresh recompute — TTL semantics preserved
    PASSED
    SpendingThresholdCalculatorTest > calculateHighAmountThreshold returns minimum threshold when insufficient data
    PASSED
    SpendingThresholdCalculatorTest > calculatePercentiles uses aggregate percentile DAO path PASSED
    SpendingThresholdCalculatorTest > calculatePercentiles handles single transaction PASSED
    SpendingThresholdCalculatorTest > calculatePercentiles computes correct P50 P75 P90 PASSED
    DashboardContractsAdapterTest > observeRecurringPatterns uses confirmed recurring feed for dashboard forecast
    consumers PASSED
    ReceiptEventDaoTest > insert multiple events and verify count PASSED
    BudgetAutopilotEngineTest > generateRecommendations edge case stable spending keeps stable trend STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ReceiptEventDaoTest > insert a RECEIPT_CREATED event PASSED
    BudgetAutopilotEngineTest > generateRecommendations edge case stable spending keeps stable trend STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case stable spending keeps stable trend FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations uses overall budget as canonical summary scope when overall and
    category budgets coexist STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations uses overall budget as canonical summary scope when overall and
    category budgets coexist FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations infills missing zero-spend months before trend math
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations infills missing zero-spend months before trend math FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case empty budgets returns empty recommendations
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case empty budgets returns empty recommendations PASSED
    BudgetAutopilotEngineTest > generateRecommendations and forecasting use aligned normalized month history
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ExchangeRateDaoTest > getRateCount returns correct count STANDARD_ERROR
    BudgetAutopilotEngineTest > generateRecommendations and forecasting use aligned normalized month history
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations and forecasting use aligned normalized month history FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations enforces plus and minus fifteen percent delta caps
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations enforces plus and minus fifteen percent delta caps FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations with zero current budget uses safe initial budget phrasing
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    FinancialWeatherRepositoryTest > getConfirmedRecurringPatterns excludes unconfirmed merged suggestions PASSED
    BudgetAutopilotEngineTest > generateRecommendations with zero current budget uses safe initial budget phrasing
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations with zero current budget uses safe initial budget phrasing
    FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations detects increasing trend using chronological month order
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations detects increasing trend using chronological month order FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations applies volatility safety factor for medium and high volatility
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations applies volatility safety factor for medium and high volatility
    FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case single month history remains stable and finite
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case single month history remains stable and finite FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations aggregates monthly totals not per-transaction averages
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptEventDaoTest > events for different receiptIds do not mix PASSED
    BudgetAutopilotEngineTest > generateRecommendations aggregates monthly totals not per-transaction averages FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case empty spend history applies bounded decrease
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations edge case empty spend history applies bounded decrease FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations for overall budget uses non-category DAO method STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAutopilotEngineTest > generateRecommendations for overall budget uses non-category DAO method FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    FinancialWeatherRepositoryTest > no recurring patterns and no budget still returns sane defaults PASSED
    FinancialWeatherRepositoryTest > getFinancialWeather correctly calculates past daily cumulative spend including day
    0 PASSED
    FinancialWeatherRepositoryTest > daily cumulative spend uses effectiveAmount for shared fixed-share expense PASSED
    BudgetMonitorStressTest > stress - destroy permanently cancels monitor scope PASSED
    FinancialWeatherRepositoryTest > maps forecast components to weather state risk totals and upcoming items PASSED
    ReceiptEventDaoTest > query events by receiptId returns empty list for unknown receipt PASSED
    DedupeKeyTest > key includes currency suffix PASSED
    DedupeKeyTest > currency is case-normalized to uppercase in key PASSED
    DedupeKeyTest > different amounts produce different keys PASSED
    DedupeKeyTest > Greek and Latin spelling of same merchant produce same key PASSED
    DedupeKeyTest > key has expected format PASSED
    DedupeKeyTest > timestamps within the same 5-minute bucket produce the same key PASSED
    BudgetMonitorStressTest > stress - percent at or above critical triggers critical notification FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#757).updateCriticalNotification(eq(2), any(), any())) was not called.

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle
    (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                          (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                  (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                      (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                      (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                              (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    DedupeKeyTest > different currencies produce different keys for same transaction PASSED
    DedupeKeyTest > timestamps more than 5 minutes apart produce different keys PASSED
    DedupeKeyTest > same merchant different casing produces same key PASSED
    FinancialWeatherRepositoryTest > getFinancialWeather uses confirmed recurring only for forecast assembly PASSED
    BudgetMonitorStressTest > stress - concurrent checks preserve throttle coherence and read repository once FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#766).updateWarningNotification(eq(6), any(), any())) was not called.

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle
    (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                                     (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                             (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default
    (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetMonitorStressTest > stress - spent zero does not notify PASSED
    FinancialWeatherRepositoryTest > isNotMine expenses are excluded from daily cumulative spend and pace input PASSED
    BudgetMonitorStressTest > stress - lastNotified before periodStart should notify FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#785).updateWarningNotification(eq(7), any(), any())) was not called.

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle                                       (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                           (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                            (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                            (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                                    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                    (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                        (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                        (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    FinancialWeatherRepositoryTest > getFinancialWeather merges recurring with manual precedence and confidence
    threshold PASSED
    BudgetMonitorStressTest > stress - percent at or above 100 triggers exceeded notification FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#794).updateExceededNotification(eq(1), any(), any())) was not called.

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle
    (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                     (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                             (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                 (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                 (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                         (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    FinancialWeatherRepositoryTest > daily cumulative spend uses effectiveAmount for percentage-based shared expense
    PASSED
    BudgetMonitorStressTest > stress - multiple budgets at critical all get notified FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#803).updateCriticalNotification(any(), any(), any())) was not called.

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle                                       (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                           (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                            (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                            (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                                    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                    (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                        (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                        (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    NotificationProcessingPipelineStressTest > stress - handle database errors gracefully SKIPPED
    BudgetMonitorStressTest > stress - onBackground cancels in flight work and next foreground check fetches fresh state
    PASSED
    BudgetMonitorStressTest > stress - budget amount zero does not notify PASSED
    BudgetMonitorStressTest > stress - empty budget statuses does not crash PASSED
    DatabaseBackupRepositoryImplTest > successful staged import swaps only after verification PASSED
    BudgetMonitorStressTest > stress - checkBudgets skipped when MIN_CHECK_INTERVAL not elapsed PASSED
    ExportReadBarrierTest > blockedDuringRestore_emits_in_NORMAL FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    ExportReadBarrierTest > export_read_allowed_in_BACKUP_EXPORTING PASSED
    CarbonFootprintCalculatorTest > merchant patterns used for known merchants PASSED
    AndroidSpeechInputGatewayTest > startup failure surfaces error without crashing PASSED
    CarbonFootprintCalculatorTest > unknown merchants fall back to category detection PASSED
    CarbonFootprintCalculatorTest > category breakdown sums to total emissions PASSED
    CarbonFootprintCalculatorTest > regression - calculator uses one shot uncapped dao path only PASSED
    AndroidSpeechInputGatewayTest > recognizer listener error is forwarded PASSED
    ExchangeRateDaoTest > getRateCount returns correct count PASSED
    CarbonFootprintCalculatorTest > restaurant purchases have lower emission factors PASSED
    CarbonFootprintCalculatorTest > flights have very high emission factors PASSED
    CarbonFootprintCalculatorTest > paris agreement gap shows percentage above target PASSED
    AndroidSpeechInputGatewayTest > denied permission surfaces error without starting recognizer PASSED
    CarbonFootprintCalculatorTest > grocery purchases have low emission factors PASSED
    GlobalDurableDiagnosticsGoldenTest > metadata_sanitizer_sanitize_json_returns_null_for_empty PASSED
    CarbonFootprintCalculatorTest > empty expense list returns zero emissions PASSED
    GlobalDurableDiagnosticsGoldenTest > diagnostic_writer_generates_unique_correlation_ids PASSED
    GlobalDurableDiagnosticsGoldenTest > metadata_sanitizer_redacts_jwt_like_value PASSED
    GlobalDurableDiagnosticsGoldenTest > exception_message_sanitizer_strips_file_paths PASSED
    GlobalDurableDiagnosticsGoldenTest > metadata_sanitizer_truncates_long_strings PASSED
    GlobalDurableDiagnosticsGoldenTest > diagnostic_event_has_auto_generated_correlation_id PASSED
    GlobalDurableDiagnosticsGoldenTest > all_required_pipelines_exist PASSED
    GlobalDurableDiagnosticsGoldenTest > safe_key_prefixes_are_not_blocked PASSED
    GlobalDurableDiagnosticsGoldenTest > correlation_id_is_uuid_format PASSED
    GlobalDurableDiagnosticsGoldenTest > exception_message_sanitizer_handles_null PASSED
    GlobalDurableDiagnosticsGoldenTest > exception_message_sanitizer_truncates_long_messages PASSED
    GlobalDurableDiagnosticsGoldenTest > all_required_outcomes_exist PASSED
    GlobalDurableDiagnosticsGoldenTest > diagnostic_event_defaults_are_correct PASSED
    GlobalDurableDiagnosticsGoldenTest > diagnostic_metadata_never_contains_raw_sensitive_keys PASSED
    GlobalDurableDiagnosticsGoldenTest > all_required_reason_codes_exist PASSED
    GlobalDurableDiagnosticsGoldenTest > metadata_sanitizer_redacts_bearer_token_value PASSED
    GlobalDurableDiagnosticsGoldenTest > terminal_event_has_is_terminal_true PASSED
    GlobalDurableDiagnosticsGoldenTest > safe_event_metadata_put_does_not_throw_for_blocked_key PASSED
    GlobalDurableDiagnosticsGoldenTest > metadata_sanitizer_blocks_nested_prompt PASSED
    CarbonFootprintCalculatorTest > merchants detected from Greek names PASSED
    WarrantyExtractionModelsTest > WarrantyExtractionResult rejects invalid confidence PASSED
    WarrantyExtractionModelsTest > WarrantyExtractionResult rejects non-positive day fields PASSED
    CarbonFootprintCalculatorTest > alternatives suggested for high impact purchases PASSED
    CarbonFootprintCalculatorTest > electronics purchases have moderate emission factors PASSED
    DatabaseBackupRepositoryImplTest > import rejects schema86 backups with valid defaults but invalid budgets index
    uniqueness PASSED
    CarbonFootprintCalculatorTest > regression - carbon report includes all rows beyond old 2000 limit PASSED
    CarbonFootprintCalculatorTest > calculateCarbonFootprint returns report for expenses PASSED
    CarbonFootprintCalculatorTest > non purchase transactions are filtered out PASSED
    CarbonFootprintCalculatorTest > recommendations are generated based on high emission categories PASSED
    CarbonFootprintCalculatorTest > daily average is calculated correctly PASSED
    CarbonFootprintCalculatorTest > comparison to national average is calculated PASSED
    CarbonFootprintCalculatorTest > monthly trend calculated from expense history PASSED
    CarbonFootprintCalculatorTest > offset cost is calculated for total emissions PASSED
    CarbonFootprintCalculatorTest > sustainability score is between 0 and 100 PASSED
    CarbonFootprintCalculatorTest > category percentages sum to approximately 100 PASSED
    CarbonFootprintCalculatorTest > fuel purchases have high emission factors PASSED
    ContextualInferenceEngineStressTest > stress - surname detection performance PASSED
    ContextualInferenceEngineStressTest > stress - google wallet source PASSED
    ContextualInferenceEngineStressTest > stress - process 1000 inferences quickly PASSED
    ContextualInferenceEngineStressTest > stress - all null optional parameters PASSED
    ContextualInferenceEngineStressTest > stress - all hours of day PASSED
    ContextualInferenceEngineStressTest > stress - dinner time 6-9pm PASSED
    ContextualInferenceEngineStressTest > stress - lunch time 12-2pm PASSED
    ContextualInferenceEngineStressTest > stress - weekday vs weekend FAILED
        java.lang.AssertionError: Should not mention weekend for weekday
    ContextualInferenceEngineStressTest > stress - handle edge case surnames PASSED
    ContextualInferenceEngineStressTest > stress - tiny amounts under 3 euros PASSED
    ContextualInferenceEngineStressTest > stress - all factor combinations PASSED
    ContextualInferenceEngineStressTest > stress - null source PASSED
    ContextualInferenceEngineStressTest > stress - huge amounts over 100 euros PASSED
    ContextualInferenceEngineStressTest > stress - predictions meet minimum confidence PASSED
    ContextualInferenceEngineStressTest > stress - invalid source package PASSED
    ContextualInferenceEngineStressTest > stress - negative amount PASSED
    ContextualInferenceEngineStressTest > stress - extra large amounts 50-100 euros PASSED
    ContextualInferenceEngineStressTest > stress - null day of week PASSED
    ContextualInferenceEngineStressTest > stress - revolut source PASSED
    ContextualInferenceEngineStressTest > stress - zero amount PASSED
    ContextualInferenceEngineStressTest > stress - night time 10-11pm PASSED
    ContextualInferenceEngineStressTest > stress - very large amount PASSED
    ContextualInferenceEngineStressTest > stress - greek bank sources PASSED
    ContextualInferenceEngineStressTest > stress - reason building completeness PASSED
    ContextualInferenceEngineStressTest > stress - breakfast time 6-9am PASSED
    ContextualInferenceEngineStressTest > stress - detect greek surnames by ending PASSED
    ContextualInferenceEngineStressTest > stress - detect greek surnames by prefix PASSED
    ContextualInferenceEngineStressTest > stress - low confidence returns null PASSED
    ContextualInferenceEngineStressTest > stress - large amounts 20-50 euros PASSED
    ContextualInferenceEngineStressTest > stress - amount boundary testing PASSED
    ContextualInferenceEngineStressTest > stress - reject business names as surnames PASSED
    ContextualInferenceEngineStressTest > stress - small amounts 3-8 euros PASSED
    ContextualInferenceEngineStressTest > stress - amount and time combinations PASSED
    ContextualInferenceEngineStressTest > stress - medium amounts 8-20 euros PASSED
    ContextualInferenceEngineStressTest > stress - grocery amount bracket 20-150 euros PASSED
    ContextualInferenceEngineStressTest > stress - weekend grocery boost PASSED
    DatabaseBackupRepositoryImplTest > temp migration open failure leaves live db untouched PASSED
    SemanticKeywordMatcherTest > finds transport keyword PASSED
    SemanticKeywordMatcherTest > pattern matching works for coffee house PASSED
    SemanticKeywordMatcherTest > pattern matching works for pizza PASSED
    SemanticKeywordMatcherTest > returns null for completely unknown merchants PASSED
    SemanticKeywordMatcherTest > finds pizza keyword PASSED
    SemanticKeywordMatcherTest > finds coffee keyword PASSED
    SemanticKeywordMatcherTest > matches keyword with punctuation suffix PASSED
    SemanticKeywordMatcherTest > finds supermarket keyword PASSED
    SemanticKeywordMatcherTest > returns null for unknown merchants with high threshold PASSED
    SemanticKeywordMatcherTest > matches hyphenated keyword FAILED
        java.lang.AssertionError
    SemanticKeywordMatcherTest > finds multiple matches returns best PASSED
    DeliverProactiveBriefingNotificationUseCaseTest > invoke skips notification when the same briefing was already
    opened PASSED
    DeliverProactiveBriefingNotificationUseCaseTest > invoke sends briefing notification when fresh ready artifact
    exists PASSED
    DeliverProactiveBriefingNotificationUseCaseTest > invoke skips notification when the same briefing was already
    delivered PASSED
    DatabaseBackupRepositoryImplTest > import repairs same version budgets defaults before reopen PASSED
    DeliverProactiveBriefingNotificationUseCaseTest > invoke skips notification when proactive briefings are disabled
    PASSED
    DeliverProactiveBriefingNotificationUseCaseTest > invoke skips notification when artifact was not refreshed in this
    run PASSED
    ExchangeRateDaoTest > insertOrUpdate replaces existing rate for same currency pair on same validDate PASSED
    DeliverProactiveBriefingNotificationUseCaseTest > invoke does not record delivery when notification service does not
    deliver PASSED
    DatabaseBackupRepositoryImplTest > verification rejects partial count loss for core tables PASSED
    CurrencyConverterStressTest > five hundred conversions accumulated preserve total within tight tolerance PASSED
    GetAiRuntimeStatusUseCaseTest > invoke returns unavailable guidance when runtime is missing PASSED
    GetAiRuntimeStatusUseCaseTest > invoke returns first non-null runtime message PASSED
    GetAiRuntimeStatusUseCaseTest > invoke returns null highestPriorityMessage when all capabilities available PASSED
    GetAiRuntimeStatusUseCaseTest > invoke exposes route metadata for cloud-capable status rows PASSED
    CurrencyConverterStressTest > same amount roundtrip after many iterations stays numerically stable PASSED
    DatabaseBackupRepositoryImplTest > schema37 fixture import preserves exact core counts through staged pipeline seam
    PASSED
    ExpenseExportMapperTest > toExportTransaction derives deterministic source account labels from payment method PASSED
    ExpenseExportMapperTest > toExportTransaction preserves accounting fields and uses effective amount FAILED
    ExchangeRateDaoTest > getRatesToCurrency returns rates filtered by target currency ordered by fromCurrency PASSED
    DatabaseBackupRepositoryImplTest > import does not reject backup with no expenses or categories when other tracked
    data exists PASSED
    ReceiptItemCategorizationInputBuilderTest > build keeps raw local categories and adds cloud-safe category options
    when redaction is enabled FAILED
        java.lang.AssertionError: expected:<[Category(id=10, name=Private Category Alpha, icon=A, color=#112233,
    isDefault=false), Category(id=20, name=Very Sensitive Category Beta, icon=B, color=#445566, isDefault=false)]> but
    was:<[CategoryRef(id=10, name=Private Category Alpha), CategoryRef(id=20, name=Very Sensitive Category Beta)]>
            at com.yourname.expensetracker.domain.ai.usecase.ReceiptItemCategorizationInputBuilderTest$build keeps raw
    local categories and adds cloud-safe category options when redaction is
    enabled$1.invokeSuspend(ReceiptItemCategorizationInputBuilderTest.kt:77)
            at com.yourname.expensetracker.domain.ai.usecase.ReceiptItemCategorizationInputBuilderTest$build keeps raw
    local categories and adds cloud-safe category options when redaction is
    enabled$1.invoke(ReceiptItemCategorizationInputBuilderTest.kt)
            at com.yourname.expensetracker.domain.ai.usecase.ReceiptItemCategorizationInputBuilderTest$build keeps raw
    local categories and adds cloud-safe category options when redaction is
    enabled$1.invoke(ReceiptItemCategorizationInputBuilderTest.kt)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
            at com.yourname.expensetracker.domain.ai.usecase.ReceiptItemCategorizationInputBuilderTest.build keeps raw
    local categories and adds cloud-safe category options when redaction is
    enabled(ReceiptItemCategorizationInputBuilderTest.kt:45)
    AiArtifactSourceHashTest > forReviewExplanation changes when business input changes PASSED
    AiArtifactSourceHashTest > forDedupeJudge is stable for equivalent inputs PASSED
    AiArtifactSourceHashTest > forReceiptItemCategorization changes when item amount changes PASSED
    AiArtifactSourceHashTest > forTransactionInsight changes when merchant changes PASSED
    AiArtifactSourceHashTest > forReviewCategorizationFallback is stable for equivalent inputs PASSED
    AiArtifactSourceHashTest > forDashboardBriefing is stable and locale invariant PASSED
    MonteCarloSpendingSimulatorTest > monte_carlo_zero_history_returns_deterministic_degraded_result PASSED
    DatabaseBackupRepositoryImplTest > import rejects schema86 backups with non repairable budgets mismatch PASSED
    AnalyticsStressTest > analytics_month_10k_transactions_completes_within_budget STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    FinancialHealthCalculatorBoundaryTest > daily spending grouping uses start-of-day for keys PASSED
    FinancialHealthCalculatorBoundaryTest > empty budget list does not award all budgets on track bonus PASSED
    FinancialHealthCalculatorBoundaryTest > expense at exactly midnight belongs to the new day not the previous day
    PASSED
    FinancialHealthCalculatorBoundaryTest > Sunday expense belongs to the same week as the preceding Monday PASSED
    FinancialHealthCalculatorBoundaryTest > excellent status is reachable when component score is near ceiling PASSED
    FinancialHealthCalculatorBoundaryTest > calculator produces valid scores across all periods PASSED
    FinancialHealthCalculatorBoundaryTest > expense at start of next day is excluded from today via half-open PASSED
    FinancialHealthCalculatorBoundaryTest > empty expense list produces valid default scores PASSED
    FinancialHealthCalculatorBoundaryTest > week range starts on Monday and ends on next Monday exclusive PASSED
    FinancialHealthCalculatorBoundaryTest > Monday-start is locale independent PASSED
    FinancialHealthCalculatorBoundaryTest > month range is half-open and expense on 1st of next month is excluded PASSED
    RecurringIncomeTrackerTest > A10 Batch5 - income expense ratio excludes transfer and unknown from spending PASSED
    RecurringIncomeTrackerTest > A10 Batch5 - recurring income detection queries deposits only PASSED
    RecurringIncomeTrackerTest > A10 Batch5 - income expense ratio excludes withdrawal from spending PASSED
    RecurringIncomeTrackerTest > A10 Batch5 - deposits only yields zero spending PASSED
    ExchangeRateDaoTest > query rate as of date returns correct historical rate PASSED
    DatabaseBackupRepositoryImplTest > rollback on post swap reopen failure PASSED
    DatabaseBackupRepositoryImplTest > backup creates file successfully PASSED
    ExchangeRateDaoTest > insertOrUpdateAll inserts multiple rates PASSED
    DatabaseBackupRepositoryImplTest > restore from backup works PASSED
    AnalyticsStressTest > analytics_month_10k_transactions_completes_within_budget FAILED
        java.lang.AssertionError: Expected 505000.0 ±1.0E-4, but was 0.0 (diff: 505000.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    > Task :app:testDebugUnitTest
    DatabaseBackupRepositoryImplTest > import allows same lineage backup missing later non core tables PASSED
    HybridExpenseClassifierTest > cold-start persisted model used on dictionary miss PASSED
    HybridExpenseClassifierTest > merchant dictionary matching takes priority PASSED
    HybridExpenseClassifierTest > ml exception falls back gracefully PASSED
    HybridExpenseClassifierTest > ml below threshold falls back gracefully PASSED
    HybridExpenseClassifierTest > ml threshold boundary is inclusive PASSED
    HybridExpenseClassifierTest > ml scores are clamped to valid range PASSED
    HybridExpenseClassifierTest > ml-based matching used when dictionary fails PASSED
    HybridExpenseClassifierTest > fallback used when ml returns empty results PASSED
    HybridExpenseClassifierTest > invalidateCategorySnapshot refreshes renamed categories without restart PASSED
    HybridExpenseClassifierTest > dictionary confidence is clamped to valid range PASSED
    HybridExpenseClassifierTest > gracefully falls back when category list is empty PASSED
    HybridExpenseClassifierTest > blank merchant and empty text immediately falls back PASSED
    DatabaseBackupRepositoryImplTest > rollback safety if restore fails original db preserved PASSED
    ExchangeRateDaoTest > query by non-existent currency pair returns null PASSED
    DatabaseBackupRepositoryImplTest > wal checkpoint helper works PASSED
    ExchangeRateDaoTest > deleteOldRates removes rates older than threshold PASSED
    InsightsEngineEdgeCaseTest > anomaly detection skips merchants with zero historical average PASSED
    InsightsEngineEdgeCaseTest > very large amounts do not overflow PASSED
    InsightsEngineEdgeCaseTest > leap year february calculations are correct PASSED
    GroupsRepositoryImplTest > get active groups with details returns populated data PASSED
    InsightsEngineEdgeCaseTest > single expense does not crash engine PASSED
    InsightsEngineEdgeCaseTest > negative amounts are handled in buildDailyTotals PASSED
    InsightsEngineEdgeCaseTest > empty expenses list returns valid snapshot with zeros PASSED
    SpendingPaceCalculatorDeepTest > pace status thresholds map correctly PASSED
    SpendingPaceCalculatorDeepTest > canonical pace formula calculates daily-rate ratio correctly PASSED
    GroupsRepositoryImplTest > create group returns group with members PASSED
    SpendingPaceCalculatorDeepTest > projected total uses blended smoothing in first week FAILED
        java.lang.AssertionError: Expected 1285.714 ±0.01, but was 1200.0 (diff: 85.71399999999994)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingPaceCalculatorDeepTest > projected total transitions smoothly on day four FAILED
        java.lang.AssertionError: Expected 2228.571 ±0.01, but was 2400.0 (diff: 171.4290000000001)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingPaceCalculatorDeepTest > current month spent excludes non purchase and not mine PASSED
    ExchangeRateDaoTest > getLatestRate returns most recently updated rate PASSED
    SpendingPaceCalculatorDeepTest > zero baseline returns no baseline status and null previous total PASSED
    GroupsRepositoryImplTest > add expense to group links correctly PASSED
    LocationResolverStressTest > gps biased geocode saves under derived non global area key PASSED
    LocationResolverStressTest > correction has highest priority PASSED
    GroupsRepositoryImplTest > member delete with split references returns error PASSED
    LocationResolverStressTest > provided merchant key is used for cache lookup PASSED
    LocationResolverStressTest > null island result is rejected PASSED
    GroupsRepositoryImplTest > member delete ignores equal split expenses before joinedAt PASSED
    GroupsRepositoryImplTest > member delete blocks equal split expenses on or after joinedAt PASSED
    LocationResolverStressTest > name only geocode saves under derived non global area key PASSED
    LocationResolverStressTest > multiple nearby pois require user selection PASSED
    LocationResolverStressTest > force refresh bypasses cache PASSED
    ExchangeRateDaoTest > insert exchange rate and retrieve by currency pair PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown calculates percentages correctly PASSED
    LocationResolverStressTest > recent transaction with device location uses gps bias PASSED
    LocationResolverStressTest > old transaction does not use gps bias PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown handles repository exception PASSED
    TotalsAggregationEngineTest > getPeriodStatus returns OVER_AVERAGE when above average PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType YEAR returns purchase-only average from repository PASSED
    TotalsAggregationEngineTest > getPeriodStatus returns NO_DATA when average is negative PASSED
    LocationResolverStressTest > device coordinates trigger second correction lookup before geocoding PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType DAY returns purchase-only average from repository PASSED
    TotalsAggregationEngineTest > getWeeklyTotals returns empty list when no expenses PASSED
    LocationResolverStressTest > transient geocoder failure surfaces as retryable PASSED
    TotalsAggregationEngineTest > getDailyTotals returns empty list when no expenses PASSED
    LocationResolverStressTest > cache hit skips geocoding PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType MONTH returns purchase-only average from repository PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown handles empty results PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType YEAR excludeCurrent false includes current year in average
    PASSED
    TotalsAggregationEngineTest > getWeeklyTotals returns purchase-only totals and counts from repository PASSED
    TotalsAggregationEngineTest > getPeriodStatus returns NO_DATA when average is zero PASSED
    RecurringExpenseEngineEmptyListTest > getPatterns handles empty expenses list gracefully PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType MONTH excludeCurrent returns purchase-only average without
    current period PASSED
    RecurringExpenseEngineEmptyListTest > getPatterns handles expenses filtered out by staleness check PASSED
    RecurringExpenseEngineEmptyListTest > getPatterns handles single expense without crash PASSED
    RecurringExpenseEngineEmptyListTest > getPatterns groups merchant aliases by canonical merchant key PASSED
    ExchangeRateDaoTest > deleteAllRates removes all exchange rates PASSED
    TotalsAggregationEngineTest > getDailyTotals groups by day correctly PASSED
    TotalsAggregationEngineTest > getWeeklyTotals calculates correct week labels FAILED
        java.lang.AssertionError: expected:<5> but was:<3>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TotalsAggregationEngineTest > getPeriodStatus returns UNDER_AVERAGE when below average PASSED
    SynthesisEngineTest > determineRiskLevel returns CRITICAL when budgets are exceeded PASSED
    SynthesisEngineTest > calculateBlockPartyData falls back to expenses when daily history is empty PASSED
    TotalsAggregationEngineTest > getDailyTotalsForRange preserves purchase-only counts from repository PASSED
    SynthesisEngineTest > discretionaryBudget calculation factors in all obligations PASSED
    TotalsAggregationEngineTest > getDailyTotals handles repository exception PASSED
    SynthesisEngineTest > calculateBlockPartyData BIWEEKLY rejects weekly plus seven and matches plus fourteen PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType WEEK excludeCurrent returns purchase-only average without
    current period FAILED
        java.lang.AssertionError: expected:<300.0> but was:<250.0>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SynthesisEngineTest > synthesize calculates totalCommitted correctly from recurring and planned PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown handles null category fields PASSED
    SynthesisEngineTest > synthesize on last day projects zero discretionary days PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown handles zero grand total PASSED
    SynthesisEngineTest > calculateBlockPartyData BIWEEKLY matches across month boundary PASSED
    TotalsAggregationEngineTest > getDailyTotalsForRange returns purchase-only totals from repository PASSED
    SynthesisEngineTest > calculateBlockPartyData fallback actual spend filters to PURCHASE mine-only PASSED
    SynthesisEngineTest > synthesize respects strict goal reserves PASSED
    TotalsAggregationEngineTest > getMonthlyTotals returns only repository purchase-filtered totals PASSED
    TotalsAggregationEngineTest > getMonthlyTotals handles repository exception PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType handles repository exceptions PASSED
    TotalsAggregationEngineTest > getMonthlyTotals calculates correct totals from repository PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType YEAR excludeCurrent true vs false produce different results
    with non-zero current year PASSED
    TotalsAggregationEngineTest > getPeriodStatus returns OVER_AVERAGE when equal to average PASSED
    ReceiptExpenseLinkDaoTest > deleteAllLinksForReceipt removes all links for a receipt PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown preserves descending sort by purchase amount PASSED
    TotalsAggregationEngineTest > getWeeklyTotals handles repository exception PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType YEAR excludeCurrent true excludes current year from average
    PASSED
    TotalsAggregationEngineTest > getDailyTotalsForRange zero fills missing days PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType DAY returns zero when repository returns null PASSED
    AppParserRegistryTest > test Greek Bank parsing (NBG) PASSED
    AppParserRegistryTest > test Google Wallet parsing PASSED
    AppParserRegistryTest > test Revolut parsing PASSED
    TotalsAggregationEngineTest > getYearlyTotals uses excludeCurrent true for status so partial current year does not
    skew average PASSED
    AppParserRegistryTest > test Revolut grouped amount parses via registry without fallback PASSED
    AppParserRegistryTest > test SMS Bank parsing PASSED
    AppParserRegistryTest > test noise rejection (OTP) PASSED
    AppParserRegistryTest > test generic fallback parsing PASSED
    AppParserRegistryTest > test SMS grouped amount parses via registry without fallback PASSED
    TotalsAggregationEngineTest > getYearlyTotals returns purchase-only totals via repository contract PASSED
    NBGReproTest > reproduce overmatched merchant for NBG notification PASSED
    CloudAuditProviderProvenanceTest > safe_privacy_metadata_rejects_token_key PASSED
    TotalsAggregationEngineTest > getDailyTotals primary path returns purchase-only totals and counts from repository
    PASSED
    TotalsAggregationEngineTest > getWeeklyTotals groups by week correctly PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown surfaces purchase-only data without deposits or transfers PASSED
    TotalsAggregationEngineTest > getMonthlyTotals returns empty list when no expenses PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType YEAR returns zero when no purchase data exists PASSED
    TotalsAggregationEngineTest > getAverageForPeriodType WEEK returns purchase-only average from repository PASSED
    TotalsAggregationEngineTest > getCategoryBreakdown sorts by totalAmount descending PASSED
    CloudAuditProviderProvenanceTest > prepared_payload_for_cloud_call_has_purpose_and_hash PASSED
    CloudAuditProviderProvenanceTest > audit_context_toMap_does_not_include_raw_prompt PASSED
    CloudAuditProviderProvenanceTest > audit_context_carries_purpose_name_in_map PASSED
    CloudAuditProviderProvenanceTest > cloud_call_audit_has_provider_model_purpose PASSED
    CloudAuditProviderProvenanceTest > missing_sensitive_handler_in_composite_fails_closed PASSED
    CloudAuditProviderProvenanceTest > cloud_call_audit_has_payload_hash PASSED
    CloudAuditProviderProvenanceTest > cloud_call_audit_records_redactionApplied PASSED
    BudgetCalculatorGoldenTest > monthly calendar mode on march 15 returns march 1 to april 1 exclusive range PASSED
    CloudAuditProviderProvenanceTest > privacy_gate_unrelated_capability_returns_not_applicable_or_allowed PASSED
    CloudAuditProviderProvenanceTest > safe_privacy_metadata_rejects_prompt_key PASSED
    BudgetCalculatorGoldenTest > rolling monthly mode resolves active anchored cycle via calendar month math PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_disables_notification_capture PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_disables_debug_persistence PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_sets_raw_ocr_do_not_store PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_disables_bank_statement_ai PASSED
    PrivacySettingsLoadStateTest > privacy_update_applies_actual_persisted_updated_settings PASSED
    BudgetCalculatorGoldenTest > yearly anniversary on march 15 advances window to current year start FAILED
        java.lang.AssertionError: Expected 1.7735256E12 ±0.0, but was 1.7672184E12 (diff: 6.3072E9)
    ReviewQueueRepositoryTest > approveReview preserves transfer and place metadata on success PASSED
    BudgetRecommendationEngineTest > generate recommendations for critical risk returns ordered high urgency actions
    PASSED
    BudgetRecommendationEngineTest > generate recommendations clamps negative potential savings to zero PASSED
    BudgetRecommendationEngineTest > budget health summary includes formatted spending and forecast metrics PASSED
    BudgetRecommendationEngineTest > get risk emoji returns expected symbol for each risk tier PASSED
    BudgetRecommendationEngineTest > generate recommendations for medium risk and low confidence includes subscription
    history and early warning advice PASSED
    PrivacySettingsLoadStateTest > corrupt_state_emits_fail_closed_from_observe_load_state PASSED
    PrivacySettingsLoadStateTest > first_run_state_carries_settings PASSED
    PrivacySettingsLoadStateTest > corrupted_state_carries_fail_closed_settings_and_reason PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_keeps_encrypted_backup_enabled PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_keeps_redact_before_cloud_true PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_disables_cloud_ai PASSED
    PrivacySettingsLoadStateTest > loaded_state_carries_settings PASSED
    PrivacySettingsLoadStateTest > first_run_state_emits_first_run_default_from_observe_load_state PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_disables_location PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_sets_raw_notification_do_not_store PASSED
    PrivacySettingsLoadStateTest > datastore_corruption_sets_email_do_not_store PASSED
    PrivacySettingsLoadStateTest > first_run_defaults_are_distinct_from_corruption_defaults PASSED
    ReviewQueueRepositoryTest > approveReview still returns Duplicate when createExpense races on same-type key PASSED
    ReviewQueueRepositoryTest > approveReview creates expense and records correction on success PASSED
    EnhancedMerchantExtractorTest > empty ocr text returns null PASSED
    EnhancedMerchantExtractorTest > existing merchant provided enhanced with ocr data PASSED
    EnhancedMerchantExtractorTest > no merchant in ocr text returns existing merchant PASSED
    EnhancedMerchantExtractorTest > extract merchant from ocr text with clear merchant name PASSED
    ReviewQueueRepositoryTest > approveReview allows DEPOSIT with same amount-merchant-date-currency as existing
    PURCHASE PASSED
    ReviewQueueRepositoryTest > approveReview dedupeKey includes transaction type suffix to prevent false unique-index
    collision PASSED
    ReceiptExpenseLinkDaoTest > unlink removes the specific link PASSED
    ReviewQueueRepositoryTest > approveReview falls back to Duplicate if createExpense races after policy check PASSED
    ReviewQueueRepositoryTest > approveReview returns Error if amount exceeds limit PASSED
    ReviewQueueRepositoryTest > markAsRelevant fallback PendingReview uses positive suggestedAmount PASSED
    ReviewQueueRepositoryTest > approveReview returns Duplicate result if canonical policy detects duplicate PASSED
    EmailReceiptIngestionServiceTransactionTest > processEmailReceipt rolls back receipt and email source when expense
    creation fails STANDARD_ERROR
    ReviewQueueRepositoryTest > approveReview allows same amount-merchant-date with different currency PASSED
    ReviewQueueRepositoryTest > rejectReview updates status and records negative correction PASSED
    CategorizationEngineDebugTest > learnMerchantCategory invalidates cache and allows immediate re-categorization
    PASSED
    CategorizationEngineDebugTest > debugCategorize returns trace with correct layer results for canonical match PASSED
    GreeklishNormalizerTest > normalize converts Greek to latin PASSED
    GreeklishNormalizerTest > isGreekText detects Greek characters PASSED
    GreeklishNormalizerTest > converts Greek word to latin PASSED
    GreeklishNormalizerTest > levenshteinDistance calculates correct distance PASSED
    GreeklishNormalizerTest > findClosestMatch finds typo PASSED
    GreeklishNormalizerTest > normalize keeps latin as lowercase PASSED
    GreeklishNormalizerTest > isGreeklish detects mixed text PASSED
    ExpenseStoreTest > write_store_delete_blocked_during_restore FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GreeklishNormalizerTest > findClosestMatch returns null for far matches PASSED
    GreeklishNormalizerTest > converts Greek alpha to latin a PASSED
    GreeklishNormalizerTest > getVariations returns multiple forms PASSED
    PeriodRangeTest > leap year february has correct boundary PASSED
    ExpenseStoreTest > write_store_update_delegates_in_NORMAL PASSED
    PeriodRangeTest > DST transition does not break day count PASSED
    PeriodRangeTest > month period range covers correct start and end PASSED
    PeriodRangeTest > week period range covers 7 days PASSED
    DtoContractTest > ReceiptItemCategorizationSnapshot roundtrip preserves fields PASSED
    DtoContractTest > unknown enum values handled gracefully PASSED
    DtoContractTest > AiArtifactRecord preserves all fields through copy PASSED
    DtoContractTest > AiArtifactRecord optional fields are null by default PASSED
    ExpenseStoreTest > read_store_getById_delegates_to_dao PASSED
    ForecastInputAssemblerTest > buildSpendingPace assembles expected pace values FAILED
        java.lang.AssertionError: expected:<372.0> but was:<496.0>
            at com.yourname.expensetracker.domain.forecasting.ForecastInputAssemblerTest.buildSpendingPace assembles
    expected pace values(ForecastInputAssemblerTest.kt:283)
    ForecastInputAssemblerTest > buildPastSumDaily computes cumulative month-to-date owned purchases PASSED
    ForecastInputAssemblerTest > merge recurring keeps manual and high confidence detected with manual precedence FAILED
        java.lang.AssertionError: expected:<2> but was:<3>
    ForecastInputAssemblerTest > merge recurring excludes detected below confidence threshold PASSED
    ForecastInputAssemblerTest > merge recurring keeps same merchant detected rules when amount or frequency differs
    PASSED
    ExpenseStoreTest > write_store_update_blocked_during_restore FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExpenseStoreTest > write_store_exception_names_the_operation PASSED
    ForecastInputAssemblerTest > merge recurring excludes detected only on manual stale duplicate signature collision
    PASSED
    ForecastInputAssemblerTest > merge recurring deduplicates stale manual duplicates by merchant frequency and amount
    signature PASSED
    ForecastInputAssemblerTest > merge recurring keeps legitimate same merchant manual rules when signature differs
    PASSED
    ForecastInputAssemblerTest > merge recurring keeps manual item due today visible PASSED
    ExpenseStoreTest > write_store_incrementBackfillAttempts_blocked_during_restore FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ForecastInputAssemblerTest > merge recurring rolls manual item forward only when before today PASSED
    ExpenseStoreTest > write_store_updateCategory_delegates_in_NORMAL PASSED
    ExpenseStoreTest > read_store_getTotalCount_delegates_to_dao PASSED
    SettlementCalculatorTest > empty or all settled balances return no settlements PASSED
    ExpenseStoreTest > write_store_insert_blocked_during_restore FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SettlementCalculatorTest > crash test 4_6 triangle debt yields two transactions totaling 50 PASSED
    ExpenseStoreTest > write_store_updateMerchantKey_blocked_during_restore FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SettlementCalculatorTest > settlement summary includes total volume and transaction count PASSED
    SettlementCalculatorTest > crash test 4_7 four member case resolves in three transactions and preserves volume
    PASSED
    SettlementCalculatorTest > settlement summary includes greedy fallback marker when flagged PASSED
    ExpenseStoreTest > write_store_insert_delegates_in_NORMAL PASSED
    SettlementCalculatorTest > calculateSettlementsMinAmount returns same result as primary DFS solver PASSED
    AiArtifactPresentationTest > toDiagnosticsOrNull maps cloud artifact to display text PASSED
    AiArtifactPresentationTest > toDiagnosticsOrNull maps on-device artifact to display text PASSED
    AiArtifactPresentationTest > toDiagnosticsOrNull returns null for auto mode PASSED
    AiPolicyTest > canUseCloudFor returns true when cloud and capability are enabled PASSED
    AiPolicyTest > canUseCloud returns true when both aiEnabled and allowCloudAi are true PASSED
    AiPolicyTest > shouldAllowOnDevice returns false when on-device is disabled PASSED
    AiPolicyTest > shouldRedact returns true when redactBeforeCloud is true regardless of capability PASSED
    AiPolicyTest > canUseCloudFor warranty extraction stays disabled when receipt assist is enabled PASSED
    AiPolicyTest > canUseCloud returns false when aiEnabled is true but allowCloudAi is false PASSED
    AiPolicyTest > canUseCloud returns false when both aiEnabled and allowCloudAi are false PASSED
    AiPolicyTest > canUseCloud returns false when aiEnabled is false but allowCloudAi is true PASSED
    AiPolicyTest > canUseCloudFor warranty extraction follows warranty extraction toggle PASSED
    AiPolicyTest > default AiSettings result in canUseCloud false and shouldRedact true PASSED
    AiPolicyTest > shouldRedact returns false when redactBeforeCloud is false regardless of capability PASSED
    AiPolicyTest > canUseCloudFor receipt extraction does not require image toggle PASSED
    AiPolicyTest > canUseCloudFor returns false when capability flag is off PASSED
    AiPolicyTest > shouldRedact is not influenced by aiEnabled or allowCloudAi flags PASSED
    AiPolicyTest > shouldAllowOnDevice returns true when on-device and capability are enabled PASSED
    FinancialHealthCalculatorTransactionTypeTest > UNKNOWN transaction type does not affect spending control PASSED
    FinancialHealthCalculatorTransactionTypeTest > mixed non-spend types across all periods do not change composite
    score PASSED
    FinancialHealthCalculatorTransactionTypeTest > score weights thresholds and streaks remain unchanged after filtering
    PASSED
    FinancialHealthCalculatorTransactionTypeTest > TRANSFER today does not affect today spending control score PASSED
    FinancialHealthCalculatorTransactionTypeTest > only non-spend rows produces same result as empty expense list PASSED
    FinancialHealthCalculatorTransactionTypeTest > non-spend rows this week do not affect weekly spending control PASSED
    FinancialHealthCalculatorTransactionTypeTest > non-spend rows this month do not affect monthly spending control
    PASSED
    FinancialHealthCalculatorTransactionTypeTest > WITHDRAWAL today does not affect today spending control score PASSED
    FinancialHealthCalculatorTransactionTypeTest > DEPOSIT today does not affect today spending control score PASSED
    ReceiptExpenseLinkDaoTest > deleteAllLinksForReceipt does not affect other receipts PASSED
    ExecuteFinancialQueryUseCaseTest > invoke returns previous period supporting text for comparison total FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke returns summary total for simple purchase total FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke preserves multi value filters for assistant queries FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke returns transaction list for list metric FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke renders mixed currency breakdown rows with valueText and no eur fallback
    amount FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke returns largest purchase summary FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke returns category breakdown FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExecuteFinancialQueryUseCaseTest > invoke returns merchant breakdown FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ConfidenceRouterTest > high confidence auto-accepts STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ExecuteFinancialQueryUseCaseTest > invoke renders mixed currency totals without fake EUR label FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ConfidenceRouterTest > high confidence auto-accepts PASSED
    ConfidenceRouterTest > previously approved merchant gets boost STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > previously approved merchant gets boost PASSED
    ConfidenceRouterTest > unknown merchant penalty does not drop below REVIEW_THRESHOLD when parser confidence is high
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
        WARNING: Failed to set backing field (skipping)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    ConfidenceRouterTest > unknown merchant penalty does not drop below REVIEW_THRESHOLD when parser confidence is high
    PASSED
    ConfidenceRouterTest > high merchant rejection rate reduces confidence STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ReceiptExpenseLinkDaoTest > unlink only removes the targeted pair PASSED
    ConfidenceRouterTest > high merchant rejection rate reduces confidence PASSED
    ConfidenceRouterTest > low confidence auto-rejects STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > low confidence auto-rejects PASSED
    ConfidenceRouterTest > spam source dramatically reduces confidence STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > spam source dramatically reduces confidence PASSED
    ConfidenceRouterTest > unknown merchant penalty floors to REVIEW when penalty alone causes drop STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > unknown merchant penalty floors to REVIEW when penalty alone causes drop PASSED
    ConfidenceRouterTest > confidence is clamped to 0-1 range STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > confidence is clamped to 0-1 range PASSED
    ConfidenceRouterTest > review floor does NOT override other penalties that already dropped confidence below REVIEW
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > review floor does NOT override other penalties that already dropped confidence below REVIEW
    PASSED
    ConfidenceRouterTest > thresholds are correct STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > thresholds are correct PASSED
    ConfidenceRouterTest > medium confidence needs review STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterTest > medium confidence needs review PASSED
    InterpretFinancialQueryUseCaseTest > invoke propagates CancellationException from provider instead of returning
    Unsupported PASSED
    InterpretFinancialQueryUseCaseTest > invoke returns provider structured result when available PASSED
    InterpretFinancialQueryUseCaseTest > invoke falls back locally when provider returns unsupported PASSED
    InterpretFinancialQueryUseCaseTest > invoke keeps provider grouping and fills missing period for top merchants this
    month PASSED
    InterpretFinancialQueryUseCaseTest > invoke keeps provider max metric and fills category for largest groceries this
    week PASSED
    InterpretFinancialQueryUseCaseTest > invoke returns unsupported when assistant query interpretation disabled PASSED
    InterpretFinancialQueryUseCaseTest > invoke local fallback matches category by name PASSED
    ReviewExplanationInputBuilderTest > build keeps raw fields and clamps notification text when redaction disabled
    PASSED
    ReviewExplanationInputBuilderTest > build pseudonymizes merchant and packageName and removes explanation when
    redaction enabled PASSED
    MerchantNormalizerTest > fuzzy match ranks best candidate instead of first tree result FAILED
        java.lang.AssertionError: expected:<2> but was:<0>
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    MerchantNormalizerTest > normalize uses alias if exists PASSED
    MerchantNormalizerTest > normalize handles empty name PASSED
    SpendingHeatmapEngineStressTest > stress - expenses distributed globally PASSED
    SpendingHeatmapEngineStressTest > stress - average position in cluster PASSED
    SpendingHeatmapEngineStressTest > stress - highest spending gets weight of 1 PASSED
    SpendingHeatmapEngineStressTest > stress - handle mixed very large and very small values PASSED
    SpendingHeatmapEngineStressTest > stress - deterministic results for same input PASSED
    SpendingHeatmapEngineStressTest > stress - handle 10000 expenses efficiently PASSED
    SpendingHeatmapEngineStressTest > stress - single expense PASSED
    SpendingHeatmapEngineStressTest > stress - log normalization compresses large values PASSED
    SpendingHeatmapEngineStressTest > stress - weights normalized to 0-1 range PASSED
    SpendingHeatmapEngineStressTest > stress - very small grid cells at high latitudes PASSED
    SpendingHeatmapEngineStressTest > stress - empty expense list returns empty heatmap PASSED
    SpendingHeatmapEngineStressTest > stress - handle zero spending PASSED
    SpendingHeatmapEngineStressTest > stress - handle expenses at grid boundaries PASSED
    SpendingHeatmapEngineStressTest > stress - handle very small spending values PASSED
    SpendingHeatmapEngineStressTest > stress - coordinates at extreme latitudes PASSED
    SpendingHeatmapEngineStressTest > stress - cluster expenses within same grid cell PASSED
    SpendingHeatmapEngineStressTest > stress - negative coordinates PASSED
    SpendingHeatmapEngineStressTest > stress - single expense position is exact PASSED
    SpendingHeatmapEngineStressTest > stress - negative only inputs return empty heatmap PASSED
    SpendingHeatmapEngineStressTest > stress - handle 1000 expenses quickly PASSED
    SpendingHeatmapEngineStressTest > stress - total spend preserved across clusters PASSED
    SpendingHeatmapEngineStressTest > stress - separate expenses into different grid cells PASSED
    SpendingHeatmapEngineStressTest > stress - single cell with max double value PASSED
    SpendingHeatmapEngineStressTest > stress - equal spending gets equal weights PASSED
    SpendingHeatmapEngineStressTest > stress - handle antimeridian crossing PASSED
    SpendingHeatmapEngineStressTest > stress - handle very large spending values PASSED
    SpendingHeatmapEngineStressTest > stress - mixed positive and negative inputs only accumulate positive spend PASSED
    SpendingHeatmapEngineStressTest > stress - large clustered dataset performance PASSED
    SpendingHeatmapEngineStressTest > stress - all expenses at same location PASSED
    SpendingHeatmapEngineStressTest > stress - handle expenses with same timestamp PASSED
    SpendingHeatmapEngineStressTest > stress - cluster many expenses in same location PASSED
    SpendingHeatmapEngineStressTest > stress - handle duplicate expense IDs PASSED
    SplitCalculatorGoldenTest > equal split of 100 among 7 members distributes 4 cent remainder to first 4 members
    PASSED
    SplitCalculatorGoldenTest > equal split of 100 among 3 members distributes remainder to first member PASSED
    EmailReceiptIngestionServiceTransactionTest > processEmailReceipt rolls back receipt and email source when expense
    creation fails PASSED
    ReceiptExpenseLinkDaoTest > insert with IGNORE strategy does not throw on duplicate unique index PASSED
    SplitCalculatorGoldenTest > percentage split 33 33 33 34 produces exact cent-preserving shares PASSED
    SplitCalculatorGoldenTest > percentage split 33 33 33 33 assigns remainder cent to first member by tie-break order
    PASSED
    DashboardExpenseMapperTest > list mapping preserves order and count PASSED
    DashboardExpenseMapperTest > shared expense effectiveAmount preserved in TransactionSummary PASSED
    DashboardExpenseMapperTest > null category handled gracefully PASSED
    DashboardExpenseMapperTest > expense to dashboardExpense mapping correct all fields mapped PASSED
    CompositeGeocodingServiceStressTest > provider errors do not abort merge SKIPPED
    GenericTransactionParserTest > reject sale promotion PASSED
    GenericTransactionParserTest > extract merchant after Greek preposition PASSED
    GenericTransactionParserTest > reject amount above 25000 PASSED
    GenericTransactionParserTest > parse Greek payment pattern PASSED
    GenericTransactionParserTest > parse transfer received as transfer not deposit PASSED
    GenericTransactionParserTest > reject offer notification PASSED
    GenericTransactionParserTest > extract merchant after at PASSED
    GenericTransactionParserTest > enriches transfer metadata when detector returns direction and account PASSED
    GenericTransactionParserTest > parse payment of pattern PASSED
    GenericTransactionParserTest > reject OTP notification PASSED
    GenericTransactionParserTest > fallback to Unknown when no merchant found PASSED
    GenericTransactionParserTest > reject notification without transaction signal PASSED
    GenericTransactionParserTest > ordinary purchase remains purchase PASSED
    GenericTransactionParserTest > parse you paid pattern PASSED
    GenericTransactionParserTest > reject tracking notification PASSED
    GenericTransactionParserTest > lower confidence than app-specific parsers PASSED
    GenericTransactionParserTest > reject Greek promotional notification PASSED
    GenericTransactionParserTest > parse Greeklish payment pattern PASSED
    GenericTransactionParserTest > reject amount below 0_10 PASSED
    GenericTransactionParserTest > reject balance notification PASSED
    GenericTransactionParserTest > parse charged pattern PASSED
    ReceiptExpenseLinkDaoTest > multiple links for same receipt PASSED
    SmsParserTest > ambiguous transfer SMS returns null direction PASSED
    SmsParserTest > amount bounds check - too small PASSED
    SmsParserTest > ambiguous direction suppresses transferAccountName PASSED
    SmsParserTest > reject null title PASSED
    SmsParserTest > parse grouped amount with currency prefix PASSED
    SmsParserTest > supports all messaging packages PASSED
    SmsParserTest > explicit incoming transfer retains INCOMING direction PASSED
    SmsParserTest > reject bank sender without transaction keywords PASSED
    SmsParserTest > parse bank SMS with Greek keywords PASSED
    SmsParserTest > parse grouped amount in transfer SMS PASSED
    SmsParserTest > parse bank SMS with Greeklish keywords PASSED
    SmsParserTest > parse outgoing transfer SMS as transfer with outgoing direction PASSED
    SmsParserTest > reject non-bank sender PASSED
    SmsParserTest > parse grouped EU amount in purchase SMS PASSED
    SmsParserTest > ambiguous deposit SMS returns null direction PASSED
    SmsParserTest > explicit incoming deposit retains INCOMING direction PASSED
    SmsParserTest > parse grouped US amount in purchase SMS PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert uses single-flight guard for concurrent calls on same expense PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert handles no history first transaction and sends no alert when detector
    returns none PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert suppresses when merchant cooldown is active within 24h PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert edge case with no category skips history fetch and detector can still
    run PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert fetches 90-day category history and passes context to detector PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert respects looks_normal feedback and suppresses future moderate anomalies
    PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert deduplicates and never alerts same expense twice PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert propagates CancellationException instead of logging and swallowing
    PASSED
    ReceiptExpenseLinkDaoTest > verify the link exists by expenseId PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert suppresses when category cooldown is active within 12h PASSED
    ExportPrivacyPolicyTest > standard_expense_export_is_allowed PASSED
    ExportPrivacyPolicyTest > plaintext_raw_export_requires_debug_data_persistence_enabled PASSED
    ExportPrivacyPolicyTest > encrypted_export_allowed_when_encrypted_backup_enabled PASSED
    ExportPrivacyPolicyTest > encrypted_disabled_does_not_allow_rawbackup_export PASSED
    ExportPrivacyPolicyTest > unrelated_capability_returns_not_applicable PASSED
    ExportPrivacyPolicyTest > new_export_capabilities_are_defined PASSED
    AnomalyAlertOrchestratorTest > checkAndAlert triggers alert for high confidence anomaly PASSED
    ExportPrivacyPolicyTest > redacted_export_is_always_allowed PASSED
    AnalyticsWindowingSupportTest > canonicalMerchantKey produces stable keys for unicode merchants PASSED
    AnalyticsWindowingSupportTest > canonicalMerchantKey uses stable fallback for null and blank merchant names PASSED
    ExportPrivacyPolicyTest > raw_database_export_rejected_in_release PASSED
    ExportPrivacyPolicyTest > export_privacy_policy_all_values_available PASSED
    AnalyticsWindowingSupportTest > canonicalMerchantKey handles punctuation-only merchant without crashing FAILED
    AnalyticsWindowingSupportTest > canonicalMerchantKey normalizes mixed-case aliases to same canonical key PASSED
    ExportPrivacyPolicyTest > encrypted_disabled_does_not_allow_raw_export PASSED
    AnalyticsWindowingSupportTest > resolveMerchantDisplayName prefers most frequent trimmed label PASSED
    ExportPrivacyPolicyTest > encrypted_export_denied_when_encrypted_backup_disabled PASSED
    ExportPrivacyPolicyTest > debug_raw_export_requires_debug_and_privacy_consent PASSED
    ReceiptOcrEmailStorageHardeningTest > raw_ocr_metadata_only_no_raw_text_in_receipt_events PASSED
    ReceiptOcrEmailStorageHardeningTest > email_metadata_only_keeps_message_id_hash_for_dedupe PASSED
    ReceiptOcrEmailStorageHardeningTest > email_store_redacted_removes_body_keeps_items PASSED
    ReceiptOcrEmailStorageHardeningTest > email_do_not_store_no_subject_sender_body_message_id_plaintext PASSED
    ReceiptOcrEmailStorageHardeningTest > raw_ocr_do_not_store_no_raw_text_in_scanned_receipts PASSED
    ReceiptOcrEmailStorageHardeningTest > parsed_items_kept_in_store_redacted_mode PASSED
    ReceiptOcrEmailStorageHardeningTest > email_store_raw_preserves_all_fields PASSED
    ReceiptOcrEmailStorageHardeningTest > raw_ocr_do_not_store_review_snippet_is_safe_placeholder PASSED
    ReceiptOcrEmailStorageHardeningTest > email_fingerprint_not_plaintext_merchant_amount_date PASSED
    ReceiptOcrEmailStorageHardeningTest > debug_export_blocked_by_policy_when_debug_disabled PASSED
    ReceiptOcrEmailStorageHardeningTest > raw_ocr_store_raw_preserves_text_and_items PASSED
    ReceiptOcrEmailStorageHardeningTest > parsed_items_redacted_when_mode_is_metadata_only PASSED
    OcrLanguageProcessorTest > normalize latin text keeps alphanumeric content and collapses whitespace PASSED
    ReceiptExpenseLinkDaoTest > link a receipt to an expense PASSED
    OcrLanguageProcessorTest > auto normalize returns detected language normalized text and confidence PASSED
    OcrLanguageProcessorTest > auto normalize keeps full width cjk unknown amount text intact PASSED
    OcrLanguageProcessorTest > extract amount supports unknown labeled arabic digit inputs PASSED
    OcrLanguageProcessorTest > normalize greek text uppercases strips accents and trims spacing PASSED
    OcrLanguageProcessorTest > extract amount supports eur and usd suffix patterns PASSED
    OcrLanguageProcessorTest > auto normalize keeps unknown amount only unicode text intact PASSED
    OcrLanguageProcessorTest > extract amount supports grouped and locale aware greek and latin values PASSED
    OcrLanguageProcessorTest > normalize for language preserves non latin scripts PASSED
    OcrLanguageProcessorTest > extract amount keeps non latin fallback parseable inputs working PASSED
    OcrLanguageProcessorTest > extract amount preserves unknown latin fallback and arabic separators PASSED
    OcrLanguageProcessorTest > extract amount handles greek and latin formats PASSED
    OcrLanguageProcessorTest > detect language recognizes greek latin cyrillic and unknown text PASSED
    NominatimGeocodingServiceLocaleTest > reverse geocode uses dot decimals under Greek locale PASSED
    InsightsEngineTest > buildDailyTotals includes all requested days PASSED
    InsightsEngineTest > buildDailyTotals ignores non-purchase types PASSED
    ReceiptExpenseLinkDaoTest > verify the link exists by receiptId PASSED
    AmazonReceiptParserTest > parse handles standalone localized date and comma decimal amount PASSED
    InsightsEngineTest > generateInsights propagates CancellationException instead of returning degraded snapshot FAILED
        java.lang.AssertionError: Expected CancellationException to propagate
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InsightsEngineTest > buildDailyTotals sums same-day purchases PASSED
    SpendingPaceCalculatorValidationTest > pace calculation with no current spending PASSED
    SpendingPaceCalculatorValidationTest > pace calculation for February (28 days) PASSED
    SpendingPaceCalculatorValidationTest > pace calculation for February leap year (29 days) PASSED
    SpendingPaceCalculatorValidationTest > pace calculation compares daily rates correctly PASSED
    SpendingPaceCalculatorValidationTest > pace calculation for last day of month PASSED
    SpendingPaceCalculatorValidationTest > pace calculation detects underspending PASSED
    SpendingPaceCalculatorValidationTest > pace calculation excludes non-purchase transactions PASSED
    SpendingPaceCalculatorValidationTest > projected total calculation for normal days PASSED
    SpendingPaceCalculatorValidationTest > pace calculation excludes not-mine transactions PASSED
    SpendingPaceCalculatorValidationTest > projected total uses blended smoothing for early days PASSED
    SpendingPaceCalculatorValidationTest > pace percentage handles zero previous spending PASSED
    SpendingPaceCalculatorValidationTest > pace percentage uses daily rate comparison PASSED
    SpendingPaceCalculatorValidationTest > projected total for day 4 remains smooth and below full linear PASSED
    SpendingPaceCalculatorValidationTest > pace calculation detects overspending PASSED
    RecurringLifecycleCoordinatorTest > generateOccurrences expands rule and materializes occurrences PASSED
    RecurringLifecycleCoordinatorTest > generateOccurrences throws when rule not found PASSED
    SplitCalculationPrecisionTest > custom split with single amount returns that amount PASSED
    SplitCalculationPrecisionTest > rounding behavior is consistent PASSED
    SplitCalculationPrecisionTest > multiple splits maintain precision across operations PASSED
    SplitCalculationPrecisionTest > split with two participants is exactly half PASSED
    SplitCalculationPrecisionTest > equal split of 100 dollars among 3 people sums to exactly 100 PASSED
    SplitCalculationPrecisionTest > equal split of 10 dollars among 3 people sums to exactly 10 PASSED
    SplitCalculationPrecisionTest > percentage split with very small amount FAILED
    SplitCalculationPrecisionTest > split preserves currency precision at 2 decimal places PASSED
    SplitCalculationPrecisionTest > equal split with divisible amount has no remainder PASSED
    SplitCalculationPrecisionTest > custom split with decimal amounts maintains precision PASSED
    SplitCalculationPrecisionTest > equal split with single participant returns full amount PASSED
    SplitCalculationPrecisionTest > split with negative amount fails appropriately PASSED
    SplitCalculationPrecisionTest > percentage split with 50-50 sums to total PASSED
    SplitCalculationPrecisionTest > split with zero total returns zeros PASSED
    SplitCalculationPrecisionTest > custom split sum matches expected total PASSED
    SplitCalculationPrecisionTest > equal split with large amount maintains precision PASSED
    SplitCalculationPrecisionTest > percentage split with uneven distribution PASSED
    SplitCalculationPrecisionTest > equal split with small amount maintains precision PASSED
    SplitCalculationPrecisionTest > custom split with empty list returns zero PASSED
    SplitCalculationPrecisionTest > percentage split with zero percent PASSED
    SplitCalculationPrecisionTest > percentage split with decimal percentages PASSED
    SplitCalculationPrecisionTest > equal split with many participants maintains precision PASSED
    TotalsAggregationEngineValidationTest > period status is NO_DATA when average is zero PASSED
    TotalsAggregationEngineValidationTest > period status is OVER_AVERAGE when above average PASSED
    TotalsAggregationEngineValidationTest > period status is UNDER_AVERAGE when below average PASSED
    TotalsAggregationEngineValidationTest > yearly totals status uses average of completed years only PASSED
    TotalsAggregationEngineValidationTest > daily totals sum correctly for a week PASSED
    AiChatRepositoryImplTest > appendMessage returns null when history disabled PASSED
    TotalsAggregationEngineValidationTest > monthly totals sum correctly PASSED
    TotalsAggregationEngineValidationTest > average calculation for period type MONTH is correct PASSED
    TotalsAggregationEngineValidationTest > category breakdown handles zero grand total PASSED
    TotalsAggregationEngineValidationTest > transaction at 23_59_59 is included in correct day PASSED
    AiChatRepositoryImplTest > observeSessions maps DAO entities to domain PASSED
    TotalsAggregationEngineValidationTest > category breakdown percentages sum to 100 PASSED
    TotalsAggregationEngineValidationTest > transaction at midnight is included in correct day PASSED
    TotalsAggregationEngineValidationTest > average calculation excludes current month when requested PASSED
    TotalsAggregationEngineValidationTest > category_percentage_rounding_sum_invariant PASSED
    TotalsAggregationEngineValidationTest > average calculation for period type DAY is correct PASSED
    TotalsAggregationEngineValidationTest > empty period returns zero totals PASSED
    AiChatRepositoryImplTest > clearAllHistory delegates to session dao PASSED
    TotalsAggregationEngineValidationTest > yearly totals filters out years with no data except current year PASSED
    TotalsAggregationEngineValidationTest > yearly totals transaction counts reflect purchase-only repository contract
    PASSED
    TotalsAggregationEngineValidationTest > category breakdown with single category has 100 percent PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY anchor Jan 31 leap year PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY anchor Mar 31 month coercion PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY anchor Jan 31 non-leap year PASSED
    BudgetCalculatorTest > CALENDAR yearly budget resolves Jan 1 to Jan 1 regardless of anchor PASSED
    BudgetCalculatorTest > calculatePeriodWindow DAILY returns 24h window PASSED
    BudgetCalculatorTest > calculatePeriodWindow YEARLY treats Feb 29 anchor as passed on Feb 28 in non leap year PASSED
    BudgetCalculatorTest > calculatePeriodWindow WEEKLY returns 7 day window aligned to anchor PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY anchor Dec 31 year boundary PASSED
    BudgetCalculatorTest > CALENDAR daily budget returns same range as TimePeriodUtils getDayRange PASSED
    BudgetCalculatorTest > calculatePeriodWindow YEARLY returns 12 month window PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY handles leap year Feb 29 PASSED
    BudgetCalculatorTest > calculatePeriodWindowForTime derives historical window when evaluationTime is in the past
    PASSED
    BudgetCalculatorTest > calculatePeriodWindowForTime derives next window when evaluationTime advances past current
    cycle PASSED
    BudgetCalculatorTest > ROLLING yearly budget uses anchor anniversary not Jan 1 PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY respects anchor day PASSED
    BudgetCalculatorTest > calculatePeriodWindow MONTHLY wraps around year correctly PASSED
    CompositeGeocodingServiceTest > unexpected primary exception still cascades to fallback provider PASSED
    ComputeMoneyRadarUseCaseTest > compute returns GREEN with healthy message when no bills alerts or budget PASSED
    ComputeMoneyRadarUseCaseTest > compute includes recurring bill due earlier today as due today PASSED
    OverpassNearbyServiceTest > findNearby ranks greek merchant names ahead of transliterated fallback PASSED
    BudgetTrendBoundaryTest > budget_trend_boundary_exactly_10_percent_is_stable FAILED
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:169)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AiChatRepositoryImplTest > appendMessage inserts message and updates session timestamp when history enabled FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    AiChatMessageDao(#380).insert(eq(AiChatMessageEntity(id=0, sessionId=7, role=ASSISTANT, kind=RESULT, text=42.00 EUR,
    payloadJson={}, createdAt=1000)), any())). Only one matching call to
    AiChatMessageDao(#380)/insert(AiChatMessageEntity, Continuation) happened, but arguments are not matching:

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                                      (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                              (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default
    (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AiChatRepositoryImplTest > createSession inserts when history enabled PASSED
    ComputeMoneyRadarUseCaseTest > compute applies weighted urgency factors and emits RED with critical budget CTA
    PASSED
    ComputeMoneyRadarUseCaseTest > compute includes recurring bill due earlier today in budget risk urgency path FAILED
        java.lang.AssertionError: expected:<51> but was:<36>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AiChatRepositoryImplTest > createSession returns null when history disabled PASSED
    ComputeMoneyRadarUseCaseTest > compute passes recurring obligations into Monte Carlo knownUpcoming PASSED
    ComputeMoneyRadarUseCaseTest > compute aggregates unresolved anomalies from last thirty days only PASSED
    AiChatRepositoryImplTest > observeMessages maps DAO entities to domain PASSED
    ComputeMoneyRadarUseCaseTest > compute includes confirmed recurring obligations in money radar PASSED
    OverpassNearbyServiceTest > findNearby returns RateLimited after final 429 retry PASSED
    ComputeMoneyRadarUseCaseTest > compute formats string placeholder reason with scalar merchant arg PASSED
    AiChatRepositoryImplTest > clearSession delegates to session dao PASSED
    ComputeMoneyRadarUseCaseTest > compute formats integer placeholder reasons with scalar numeric args PASSED
    ComputeMoneyRadarUseCaseTest > compute ignores unconfirmed detected recurring suggestions PASSED
    ComputeMoneyRadarUseCaseTest > compute includes only bills due within next seven days PASSED
    ComputeMoneyRadarUseCaseTest > compute uses merged recurring result to avoid duplicate stale bill inflation PASSED
    ComputeMoneyRadarUseCaseTest > compute budget urgency changes with magnitude and risk tier PASSED
    CategorizationEngineStressTest > stress - edit distance 1 from known merchant PASSED
    ComputeMoneyRadarUseCaseTest > compute excludes future dated purchases from spent to date PASSED
    CategorizationEngineStressTest > stress - three character merchants can use fuzzy matching PASSED
    AutomatedSavingsRuleStateRepositoryTest > writes prune obsolete weekly and monthly entries PASSED
    CategorizationEngineStressTest > stress - edit distance 2 from known merchant PASSED
    AutomatedSavingsRuleStateRepositoryTest > weekly reservation is idempotent and survives repository recreation FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
    AutomatedSavingsRuleStateRepositoryTest > weekly reservation and monthly cap update atomically PASSED
    CategorizationEngineStressTest > stress - AMAZON vs AMAZON COM vs AMZN PASSED
    AutomatedSavingsRuleStateRepositoryTest > weekly reservation is not consumed when monthly cap blocks reward PASSED
    CategorizationEngineStressTest > stress - uppercase lowercase Greek mixed PASSED
    CategorizationEngineStressTest > stress - special characters in merchant name PASSED
    CategorizationEngineStressTest > regression - layer priority unchanged PASSED
    AutomatedSavingsRuleStateRepositoryTest > state serializes to datastore json PASSED
    AutomatedSavingsRuleStateRepositoryTest > monthly cap consumption is atomic across concurrent updates PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute fails when date missing even if confidence is otherwise high
    PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute returns AlreadyExists when warranty already exists for receipt id
    PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute returns Failure for malformed warranty text edge case PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute auto-creates warranty when extraction confidence is high at or
    above 70 PASSED
    BudgetRolloverTest > monthly budget rollover works across month boundaries PASSED
    BusinessExpenseRepositoryTest > addMileage inserts valid mileage PASSED
    BudgetRolloverTest > anchored monthly budget rollover includes completed Jan31-Feb28 cycle when evaluated in March
    PASSED
    BusinessExpenseRepositoryTest > addMileage rejects impossible values before dao insert PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > createWarrantyForReview promotes existing draft instead of inserting
    duplicate PASSED
    BudgetRolloverTest > rollover never goes negative PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute creates low-confidence review draft and returns LowConfidence for
    40-70 PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute returns Failure for empty OCR text edge case PASSED
    BudgetRolloverTest > compounding rollover adds previous surpluses correctly PASSED
    AutoCreateWarrantyFromReceiptUseCaseTest > execute handles unique constraint conflict on receiptId and returns
    AlreadyExists PASSED
    MerchantKeyGeneratorTest > Same merchant in different cases produces same key PASSED
    MerchantKeyGeneratorTest > Greek merchant name transliterates to correct Latin key PASSED
    MerchantKeyGeneratorTest > Empty string returns empty key PASSED
    MerchantKeyGeneratorTest > Blank whitespace-only string returns empty key PASSED
    MerchantKeyGeneratorTest > Latin merchant name with apostrophe strips special char PASSED
    BudgetRolloverTest > weekly budget rollover works across week boundaries PASSED
    MerchantKeyGeneratorTest > Greek and variant Latin spellings of same merchant produce identical key PASSED
    MerchantKeyGeneratorTest > Special characters and spaces are stripped PASSED
    MerchantKeyGeneratorTest > Numeric characters are preserved PASSED
    MerchantKeyGeneratorTest > generate is idempotent for Latin merchant PASSED
    MerchantKeyGeneratorTest > Different merchants produce different keys PASSED
    MerchantKeyGeneratorTest > Greek input with accents strips diacritics correctly PASSED
    MerchantKeyGeneratorTest > Accented Latin chars are normalised to plain ASCII PASSED
    MerchantKeyGeneratorTest > Uppercase Latin is lowercased PASSED
    MerchantKeyGeneratorTest > Greek diphthong mp transliterates to b PASSED
    MerchantKeyGeneratorTest > generate is idempotent - applying twice gives same result as once PASSED
    MerchantKeyGeneratorTest > Mixed Greek and Latin merchant name PASSED
    TimePeriodUtilsTest > contract - getStartOfWeek always returns Monday PASSED
    TimePeriodUtilsTest > getEndOfWeek - consistent with getWeekRange PASSED
    TimePeriodUtilsTest > year rollover dates Dec30 Dec31 Jan1 Jan5 map to correct SQLite week keys PASSED
    TimePeriodUtilsTest > getDaysRemainingInMonth returns correct count PASSED
    TimePeriodUtilsTest > getDayRange - spans exactly 1 calendar day PASSED
    TimePeriodUtilsTest > getWeekOfYear and getYear are consistent for mid-year date PASSED
    TimePeriodUtilsTest > getStartOfDay returns midnight of the given timestamp PASSED
    TimePeriodUtilsTest > getStartOfMonth returns first day of month at midnight PASSED
    TimePeriodUtilsTest > isInRange - works with real day boundaries PASSED
    TimePeriodUtilsTest > contract - getWeekRange returns Monday to next Monday PASSED
    TimePeriodUtilsTest > contract - getEndOfYear is exclusive (Jan 1st of next year) PASSED
    TimePeriodUtilsTest > addMonths - Jan 31 plus 1 month coerces to Feb 28 or 29 PASSED
    TimePeriodUtilsTest > getDayRange - returns same as getStartOfDay and getEndOfDay PASSED
    TimePeriodUtilsTest > canonical week range supports empty week intervals with stable boundaries PASSED
    TimePeriodUtilsTest > isInRange - timestamp before start is excluded PASSED
    TimePeriodUtilsTest > contract - timestamp at midnight is included in its own day PASSED
    TimePeriodUtilsTest > getStartOfYear returns Jan 1st at midnight PASSED
    TimePeriodUtilsTest > getDayRange - consecutive days are contiguous PASSED
    TimePeriodUtilsTest > getCanonicalWeekRangeFromKey maps SQLite key 2025-00 to last week of 2024 PASSED
    TimePeriodUtilsTest > addYears - Feb 29 plus 1 year coerces to Feb 28 PASSED
    TimePeriodUtilsTest > month key helpers format parse and build inclusive range PASSED
    TimePeriodUtilsTest > getCanonicalWeekRangeFromKey maps SQLite key 2024-53 to Dec 30 2024 Monday PASSED
    TimePeriodUtilsTest > contract - timestamp at 23_59_59_999 is included in its own day PASSED
    TimePeriodUtilsTest > addDays - crosses year boundary PASSED
    TimePeriodUtilsTest > contract - day range covers exactly one calendar day PASSED
    TimePeriodUtilsTest > contract - getEndOfDay is exclusive (start of next day) PASSED
    TimePeriodUtilsTest > contract - Sunday belongs to the previous Monday's week PASSED
    TimePeriodUtilsTest > getEndOfMonth returns start of next month (exclusive end convention) PASSED
    TimePeriodUtilsTest > getEndOfWeek - is exactly 7 calendar days after getStartOfWeek PASSED
    TimePeriodUtilsTest > isInRange - timestamp at startInclusive is included PASSED
    TimePeriodUtilsTest > getWeekBasedYear still returns ISO week-based year for Jan 1 2021 PASSED
    TimePeriodUtilsTest > addMonths - March 31 minus 1 month coerces to Feb 29 in leap year PASSED
    TimePeriodUtilsTest > contract - getEndOfMonth is exclusive (1st of next month) PASSED
    TimePeriodUtilsTest > getWeekOfYear and getYear are consistent at year boundary - Jan 1 2021 PASSED
    TimePeriodUtilsTest > getWeekOfYear and getYear are consistent at year boundary - Jan 3 2016 PASSED
    TimePeriodUtilsTest > contract - Q4 endExclusive is Jan 1 next year PASSED
    TimePeriodUtilsTest > contract - getEndOfQuarter is exclusive (1st of next quarter) PASSED
    TimePeriodUtilsTest > getCanonicalWeekRangeFromKey returns Monday-start next-Monday-exclusive PASSED
    TimePeriodUtilsTest > isInRange - timestamp at endExclusive is excluded PASSED
    TimePeriodUtilsTest > contract - Q4 range contiguous with Q1 next year PASSED
    TimePeriodUtilsTest > contract - getStartOfWeek is locale-independent PASSED
    TimePeriodUtilsTest > getEndOfWeek - returns next Monday PASSED
    TimePeriodUtilsTest > getWeekOfYear and getYear are consistent at year boundary - Dec 31 2020 PASSED
    TimePeriodUtilsTest > isInRange - timestamp in middle is included PASSED
    TimePeriodUtilsTest > getEndOfYear returns start of next year (exclusive end convention) PASSED
    TimePeriodUtilsTest > isInRange - works with real month boundaries PASSED
    BudgetRolloverTest > budget with rollover carries over unspent amount PASSED
    BudgetRolloverTest > deactivating budget stops rollover accumulation PASSED
    MultiCurrencyRepositoryTest > getTotalExpensesInHomeCurrency uses aggregate DAO path PASSED
    BudgetRolloverTest > surplus calculation with zero spend is full budget amount PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency uses grouped aggregate path PASSED
    MultiCurrencyRepositoryTest > getExpensesByCurrency handles over 2000 expenses via aggregate PASSED
    BudgetRolloverTest > rollover accumulates over multiple periods PASSED
    MultiCurrencyRepositoryTest > multi-currency totals are not truncated when expense count exceeds old LIMIT 2000
    PASSED
    MultiCurrencyRepositoryTest > getExpensesByCurrency is type-agnostic and includes non-PURCHASE rows PASSED
    BudgetRolloverTest > rollover with category filter only includes category expenses PASSED
    MultiCurrencyRepositoryTest > getMonthlyTotalsInHomeCurrency single-currency uses aggregate path PASSED
    BudgetRolloverTest > rollover calculation respects period boundaries PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency keeps different merchant labels as separate buckets
    PASSED
    BudgetRolloverTest > budget without rollover does not carry over unspent amount PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency multi-currency uses grouped aggregate PASSED
    MultiCurrencyRepositoryTest > getMonthlyTotalsInHomeCurrency returns empty list when no expenses PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency multi-currency results are sorted descending PASSED
    DeterministicExpenseExportPagerTest > fetchAllBetween exhausts all deterministic pages FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    DeterministicExpenseExportPagerTest > fetchAllBetween rejects non positive page sizes PASSED
    MerchantRulesRepositoryTest > cleanMerchantName removes store numbers PASSED
    MerchantRulesRepositoryTest > cleanMerchantName handles greek characters PASSED
    MerchantRulesRepositoryTest > cleanMerchantName removes location suffixes PASSED
    MerchantRulesRepositoryTest > cleanMerchantName removes corporate suffixes PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency includes null merchantKey rows PASSED
    MultiCurrencyRepositoryTest > Known rate converts correctly via aggregate path PASSED
    MultiCurrencyRepositoryTest > getCategoryTotalsInHomeCurrency handles over 2000 expenses via aggregate PASSED
    MultiCurrencyRepositoryTest > getExpensesByCurrency returns empty map when no expenses PASSED
    MultiCurrencyRepositoryTest > getTotalExpensesInHomeCurrency is type-agnostic PASSED
    CategorizationEngineStressTest > stress - fuzz random merchant names PASSED
    MultiCurrencyRepositoryTest > getTotalExpensesInHomeCurrency handles over 2000 expenses via aggregate PASSED
    CategorizationEngineStressTest > stress - cache populated while another thread reads PASSED
    MultiCurrencyRepositoryTest > Stale rate uses last known rate with warning PASSED
    MultiCurrencyRepositoryTest > getCategoryTotalsInHomeCurrency multi-currency uses grouped aggregate PASSED
    CategorizationEngineStressTest > stress - large merchant dictionary performance PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency results are sorted descending by total PASSED
    MultiCurrencyRepositoryTest > Missing exchange rate returns home currency total PASSED
    CategorizationEngineStressTest > stress - Greek unicode characters PASSED
    MultiCurrencyRepositoryTest > getCategoryTotalsInHomeCurrency returns empty map when no expenses PASSED
    CategorizationEngineStressTest > stress - control characters in name PASSED
    MultiCurrencyRepositoryTest > getTotalExpensesInHomeCurrency returns error on missing rate PASSED
    MultiCurrencyRepositoryTest > getMerchantTotalsInHomeCurrency returns empty map when no expenses PASSED
    CategorizationEngineStressTest > stress - mixed Greek Latin characters PASSED
    MultiCurrencyRepositoryTest > Multiple currencies all converted and summed via multi-currency path PASSED
    CategorizationEngineStressTest > stress - common prefixes cause false positives PASSED
    CategorizationEngineStressTest > stress - categorization with very long merchant name PASSED
    CategorizationEngineStressTest > bug - fuzzy matching disabled for short names causes inconsistent results PASSED
    MultiCurrencyRepositoryTest > getCategoryTotalsInHomeCurrency uses grouped aggregate path PASSED
    CategorizationEngineStressTest > stress - cache invalidation during categorization PASSED
    MultiCurrencyRepositoryTest > getCategoryTotalsInHomeCurrency preserves null categoryId rows PASSED
    CategorizationEngineStressTest > stress - layer returns lower confidence each failure PASSED
    MultiCurrencyRepositoryTest > getExpensesByCurrency uses aggregate DAO helper PASSED
    CategorizationEngineStressTest > stress - whitespace only merchant name PASSED
    CategorizationEngineStressTest > stress - merchants shorter than 4 characters disable fuzzy PASSED
    CategorizationEngineStressTest > stress - empty merchant name PASSED
    CategorizationEngineStressTest > stress - short merchant names causing ambiguity PASSED
    CategorizationEngineStressTest > stress - force all layers to fail returns UNKNOWN PASSED
    CategorizationEngineStressTest > stress - cache expiry at exactly 300s boundary PASSED
    ReceiptRepositoryStatementDuplicateTest > processStatement keeps same merchant date and amount when currencies
    differ PASSED
    SavingsContributionHistoryRepositoryTest > recorded contributions survive repository recreation and range queries
    PASSED
    SavingsContributionHistoryRepositoryTest > invalid contributions are rejected without persisting PASSED
    CategorizationEngineStressTest > stress - 10000 concurrent categorization requests PASSED
    SavingsContributionHistoryRepositoryTest > pruning removes stale events but preserves current month and streak
    history PASSED
    CategorizationEngineStressTest > stress - concurrent cache updates don't lose data PASSED
    CategorizationEngineStressTest > regression - canonical match has correct confidence PASSED
    CategorizationEngineStressTest > stress - edit distance 3 - may exceed threshold PASSED
    DDL512RegressionTest > DDL-512-01 correct order - terminal event before failJournal is preserved in failure file
    PASSED
    CategorizationEngineStressTest > stress - emoji in merchant name PASSED
    DDL512RegressionTest > DDL-F876-15 packageHash with valid hex value is allowed PASSED
    DDL512RegressionTest > DDL-512-01 bug order - event after failJournal is lost PASSED
    DDL512RegressionTest > DDL-F876-15 packageHash with plain text value is redacted PASSED
    DDL512RegressionTest > DDL-F876-05 cancelled with RESTORE_BLOCKED reason code is preserved PASSED
    DDL512RegressionTest > DDL-512-14 ordered emission RECEIVED then BLOCKED preserves sequence PASSED
    DDL512RegressionTest > DDL-F876-04 direct terminal event marks handle terminal PASSED
    DDL512RegressionTest > DDL-F876-15 unknown hash-like key is always redacted regardless of value PASSED
    DDL512RegressionTest > DDL-F876-04 direct terminal then cancelled produces one terminal event PASSED
    DDL512RegressionTest > DDL-C67-01 failedFinal emits exactly one terminal event PASSED
    DDL512RegressionTest > DDL-512-02 MAINTENANCE_ENTERED before journal creation is lost PASSED
    DDL512RegressionTest > DDL-F876-04 cancelled then success produces one terminal event PASSED
    DDL512RegressionTest > DDL-512-11 getRecentFailures query includes BLOCKED outcome FAILED
        java.lang.AssertionError: Query must include BLOCKED
    DDL512RegressionTest > DDL-F876-07 parse diagnostic uses same correlationId as listener PASSED
    DDL512RegressionTest > DDL-512-10 getAllDiagnosticEvents reads from failure journal file PASSED
    DDL512RegressionTest > DDL-F876-05 cancelled with null reason defaults to CANCELLED_BY_SYSTEM PASSED
    DDL512RegressionTest > DDL-F876-08 pipeline exception path uses same cid as listener - verification PASSED
    > Task :app:testDebugUnitTest
    DDL512RegressionTest > DDL-512-02 MAINTENANCE_ENTERED appended after journal creation is preserved PASSED
    DDL512RegressionTest > DDL-512-03 metadataJson survives JSON serialize round-trip PASSED
    DDL512RegressionTest > DDL-F876-11 dispatchOnCreated accepts correlationId parameter PASSED
    DDL512RegressionTest > DDL-512-07 sanitizeJsonString redacts non-hex sourceIdHash PASSED
    DDL512RegressionTest > DDL-512-05 CreateExpenseRequest accepts and exposes correlationId PASSED
    DDL512RegressionTest > DDL-F876-15 all safe-exact keys ending in hash are in safe-hash-keys PASSED
    DDL512RegressionTest > DDL-512-06 TransactionEvent entity has correlationId field PASSED
    DDL512RegressionTest > DDL-512-04 OperationRunEvent entity declares eventId index FAILED
        java.lang.AssertionError: OperationRunEvent must declare Index on eventId column
    DDL512RegressionTest > DDL-512-07 sanitizeJsonString preserves valid hex sourceIdHash PASSED
    DDL512RegressionTest > DDL-C67-01 success emits exactly one terminal event PASSED
    AiRuntimeStatusModelsTest > routeDisplayText returns null when route missing PASSED
    AiRuntimeStatusModelsTest > routeDisplayText includes provider and model when present PASSED
    CategorizationEngineStressTest > bug - cache mutex may block under high contention PASSED
    CategorizationEngineStressTest > regression - exact match still has highest confidence PASSED
    CategorizationEngineStressTest > stress - all Greek diphthong combinations PASSED
    CategorizationEngineStressTest > stress - null character in name PASSED
    MerchantCanonicalizerStressTest > removes latin corporate suffixes case-insensitively PASSED
    MerchantCanonicalizerStressTest > removes greek corporate suffixes in greek script PASSED
    MerchantCanonicalizerStressTest > normalizes punctuation and whitespace PASSED
    MerchantCanonicalizerStressTest > removes greeklish corporate suffixes PASSED
    MerchantCanonicalizerStressTest > is deterministic across repeated calls PASSED
    MerchantCanonicalizerStressTest > removes greek prefixes and suffixes iteratively PASSED
    MerchantCanonicalizerStressTest > confidence penalty grows with stripped parts PASSED
    MerchantCanonicalizerStressTest > keeps merchant text when no suffix exists PASSED
    DefaultAiCapabilityRouterTest > decide returns DETERMINISTIC_FALLBACK when cloud preferred but API key is missing
    PASSED
    DefaultAiCapabilityRouterTest > decide still routes receipt extraction to cloud when image toggle is enabled PASSED
    DefaultAiCapabilityRouterTest > decide returns CLOUD in AUTO for cloud-first capability when network available
    PASSED
    DefaultAiCapabilityRouterTest > decide reports downloading model when on-device preferred mode is waiting on runtime
    PASSED
    DefaultAiCapabilityRouterTest > decide routes warranty extraction to cloud in auto when network available PASSED
    CurrencyConversionTest > convert uses direct rate when available PASSED
    CurrencyConversionTest > default base currency is EUR PASSED
    DefaultAiCapabilityRouterTest > decide reports unavailable model when auto has no cloud fallback PASSED
    CurrencyConversionTest > storeRates inserts multiple rates PASSED
    DefaultAiCapabilityRouterTest > decide returns DETERMINISTIC_FALLBACK when on device preferred and local unavailable
    - no cloud leak PASSED
    CurrencyConversionTest > getLastUpdateTime returns null when no rates PASSED
    DefaultAiCapabilityRouterTest > decide returns ON_DEVICE in AUTO for on-device-first capability when local model
    available PASSED
    CurrencyConversionTest > conversion result contains all required fields PASSED
    DefaultAiCapabilityRouterTest > decide routes query interpretation to cloud when cloud is preferred PASSED
    CurrencyConversionTest > hasRate returns true when rate exists PASSED
    CurrencyConversionTest > convert includes original and target currencies in result PASSED
    DefaultAiCapabilityRouterTest > decide returns ON_DEVICE for review explanation when local model available PASSED
    CurrencyConversionTest > formatAmount rounds to two decimal places PASSED
    CurrencyConversionTest > convert returns same amount when currencies are identical PASSED
    DefaultAiCapabilityRouterTest > decide reports cloud disabled explicitly when cloud setting is off PASSED
    CurrencyConversionTest > supported currency fromCode returns null for unknown PASSED
    DefaultAiCapabilityRouterTest > decide reports unsupported android version for on-device preferred mode PASSED
    CurrencyConversionTest > convertMultiple sums converted amounts PASSED
    CurrencyConversionTest > getLastUpdateTime returns timestamp of latest rate PASSED
    DefaultAiCapabilityRouterTest > decide respects wifiOnlyForCloud and falls back when wifi unavailable PASSED
    DefaultAiCapabilityRouterTest > decide reports unsupported device when auto has no cloud and no local PASSED
    CurrencyConversionTest > convert uses EUR as intermediate when no direct rate PASSED
    CurrencyConversionTest > formatAmount includes currency symbol PASSED
    CurrencyConversionTest > hasRate returns false when rate does not exist PASSED
    DefaultAiCapabilityRouterTest > decide returns ON_DEVICE for dedupe judge when local model available PASSED
    DefaultAiCapabilityRouterTest > decide returns DISABLED when AI is off PASSED
    CurrencyConversionTest > convert with zero amount returns zero PASSED
    CurrencyConversionTest > convert with negative amount works correctly PASSED
    DefaultAiCapabilityRouterTest > decide falls back to on device when cloud preferred capability has no cloud route
    but local is available PASSED
    CurrencyConversionTest > convert returns null when no rate available PASSED
    CurrencyConversionTest > convertMultiple with empty list returns zero PASSED
    DefaultAiCapabilityRouterTest > decide returns ON_DEVICE for receipt extraction when local model available PASSED
    CurrencyConversionTest > convertMultiple records failure when conversion fails PASSED
    CurrencyConversionTest > storeRate inserts exchange rate PASSED
    CurrencyConversionTest > supported currency fromCode finds matching currency PASSED
    CurrencyConversionTest > formatAmount handles unknown currency PASSED
    CurrencyConversionTest > supported currencies includes major currencies PASSED
    CurrencyConversionTest > convert handles case insensitive currency codes PASSED
    CurrencyConversionTest > convert includes timestamp from exchange rate PASSED
    CurrencyConversionTest > cleanupOldRates removes rates older than timestamp PASSED
    CurrencyConversionTest > rate lookup is case insensitive PASSED
    DashboardFollowThroughEngineTest > generateRecommendations creates category recommendation when categoryId present
    PASSED
    DashboardFollowThroughEngineTest > generateRecommendations creates filter criteria as deterministic JSON PASSED
    DashboardFollowThroughEngineTest > generateRecommendations creates merchant recommendation when merchant present
    PASSED
    DashboardFollowThroughEngineTest > very large transaction is detected as high amount with transaction list
    navigation PASSED
    DashboardFollowThroughEngineTest > generateRecommendations handles blank merchant name PASSED
    DashboardFollowThroughEngineTest > generateRecommendations skips high priority for transactions under 100 PASSED
    DashboardFollowThroughEngineTest > adaptive threshold no transactions defaults to min 50 and blocks lower amount
    PASSED
    DashboardFollowThroughEngineTest > generateRecommendations expiration is 7 days from creation FAILED
        java.lang.AssertionError: Expected value to be true.
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    DashboardFollowThroughEngineTest > generateRecommendations creates recent transactions recommendation PASSED
    DashboardFollowThroughEngineTest > generateRecommendations creates recommendation from transaction and AI artifact
    PASSED
    DashboardFollowThroughEngineTest > generateRecommendations respects max 5 limit PASSED
    DashboardFollowThroughEngineTest > generateRecommendations uses AI text when artifact provided PASSED
    DashboardFollowThroughEngineTest > generateRecommendations includes sourceArtifactId when artifact provided PASSED
    DashboardFollowThroughEngineTest > generateRecommendations assigns correct priorities PASSED
    DashboardFollowThroughEngineTest > generateFromInsight handles null categoryId PASSED
    DashboardFollowThroughEngineTest > generateRecommendations filter includes transaction type PASSED
    DashboardFollowThroughEngineTest > generateRecommendations generates fallback text when no AI artifact PASSED
    DashboardFollowThroughEngineTest > generateRecommendations sorts by priority and takes top 5 PASSED
    DashboardFollowThroughEngineTest > generateFromInsight creates recommendation with custom parameters PASSED
    DashboardFollowThroughEngineTest > generateRecommendations creates high priority for large transactions above 100
    PASSED
    HistoricalSpendingDistributionBoundaryTest > week bucket key is the Monday of that week not locale-dependent PASSED
    HistoricalSpendingDistributionBoundaryTest > distinct day counting via getStartOfDay groups same-day expenses PASSED
    HistoricalSpendingDistributionBoundaryTest > next Monday belongs to a different week bucket PASSED
    HistoricalSpendingDistributionBoundaryTest > computeDistribution excludes current partial week PASSED
    HistoricalSpendingDistributionBoundaryTest > week enumeration with addDays 7 produces exactly correct number of
    weeks PASSED
    HistoricalSpendingDistributionBoundaryTest > distribution weekly totals use effectiveAmount FAILED
        java.lang.AssertionError: Weekly total should be in expected range
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    HistoricalSpendingDistributionBoundaryTest > lookback window uses calendar month subtraction not fixed 540 days
    PASSED
    HistoricalSpendingDistributionBoundaryTest > distribution filters out non-spending transaction types and isNotMine
    FAILED
        java.lang.AssertionError: Weekly total should only include purchases expected:<400.0> but was:<0.01>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExplainPendingReviewUseCaseTest > invoke sets expiresAt to now plus TTL PASSED
    ExplainPendingReviewUseCaseTest > invoke returns immediately when reviewExplanationEnabled is false PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend excludes linked legacy system expense from
    personal spend PASSED
    ExplainPendingReviewUseCaseTest > invoke stores route metadata when provider succeeds PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend edge case no shared expenses in groups PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend propagates repository failures PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend uses SplitCalculator fallback for malformed
    custom splits PASSED
    ExplainPendingReviewUseCaseTest > invoke propagates CancellationException without writing FAILED artifact PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend avoids N+1 by fetching expenses once and mapping
    in memory PASSED
    ExplainPendingReviewUseCaseTest > invoke stores FAILED artifact when provider returns failure PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend edge case no groups returns personal only PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend applies category filtering for personal and
    shared expenses PASSED
    ExplainPendingReviewUseCaseTest > invoke skips generation when fresh READY artifact already exists PASSED
    SharedExpenseBudgetOffsetEngineTest > calculateEffectiveBudgetSpend edge case empty period returns zeros PASSED
    ExplainPendingReviewUseCaseTest > invoke stores ON_DEVICE route metadata when local review provider succeeds PASSED
    ExplainPendingReviewUseCaseTest > invoke stores FAILED artifact when provider throws PASSED
    ExplainPendingReviewUseCaseTest > invoke sets correct targetKey for review PASSED
    ExplainPendingReviewUseCaseTest > invoke regenerates when cached review explanation source hash is stale PASSED
    ExplainPendingReviewUseCaseTest > invoke stores READY artifact with headline and body when provider succeeds PASSED
    ExplainPendingReviewUseCaseTest > invoke returns immediately when aiEnabled is false PASSED
    BackupRestoreIntegrityE2ETest > full app state integrity preserved across restore cycle STANDARD_ERROR
    JudgePendingReviewDuplicateUseCaseTest > invoke reuses cache when source hash matches canonical input PASSED
    JudgePendingReviewDuplicateUseCaseTest > invoke bypasses malformed cache payload and requests fresh suggestion
    PASSED
    JudgePendingReviewDuplicateUseCaseTest > invoke stores ON_DEVICE metadata when local dedupe provider succeeds PASSED
    JudgePendingReviewDuplicateUseCaseTest > invoke returns NotNeeded when builder says not needed PASSED
    FinancialHealthScoreV2Test > calculateHealthScore edge case zero income gives neutral savings score PASSED
    JudgePendingReviewDuplicateUseCaseTest > invoke preserves matched target inside candidate set PASSED
    JudgePendingReviewDuplicateUseCaseTest > invoke clears invalid matched target outside candidate set PASSED
    FinancialHealthScoreV2Test > calculateHealthScore applies weighted formula thirty twentyfive twentyfive twenty
    PASSED
    JudgePendingReviewDuplicateUseCaseTest > invoke stores READY artifact on success PASSED
    FinancialHealthScoreV2Test > calculateHealthScore runway uses savings goals not monthly budget surplus PASSED
    FinancialHealthScoreV2Test > calculateHealthScore determines trend improving stable declining by five point
    threshold PASSED
    FinancialHealthScoreV2Test > calculateHealthScore edge case zero expenses gives neutral runway score PASSED
    FinancialHealthScoreV2Test > calculateHealthScore runway returns neutral with very low coverage and no baseline
    PASSED
    FinancialHealthScoreV2Test > calculateHealthScore edge case missing data uses neutral defaults PASSED
    FinancialHealthScoreV2Test > calculateHealthScore upserts history by updating existing period record PASSED
    FinancialHealthScoreV2Test > calculateHealthScore uses historical budget statuses for requested period end PASSED
    SuggestCategoryFallbackUseCaseTest > invoke for review still allows fallback when deterministic category is
    Uncategorized PASSED
    FinancialHealthScoreV2Test > calculateHealthScore runway uses baseline blend for early month stability PASSED
    DuplicateDetectionPolicyDedupeKeyTest > generateDedupeKeyWithType - key ends with normalized currency before type
    suffix PASSED
    DuplicateDetectionPolicyDedupeKeyTest > generateDedupeKeyWithType - UNKNOWN type produces same key as type-blind
    helper for same explicit currency PASSED
    DuplicateDetectionPolicyDedupeKeyTest > generateDedupeKey - different currencies produce different keys PASSED
    DuplicateDetectionPolicyDedupeKeyTest > generateDedupeKey - lowercase and uppercase currency produce same key PASSED
    DuplicateDetectionPolicyDedupeKeyTest > generateDedupeKey - key ends with normalized uppercase currency PASSED
    DuplicateDetectionPolicyDedupeKeyTest > generateDedupeKeyWithType - different currencies produce different keys for
    PURCHASE PASSED
    SuggestCategoryFallbackUseCaseTest > invoke stores ON_DEVICE metadata when router selects local categorization
    PASSED
    SuggestCategoryFallbackUseCaseTest > invoke for receipt stores scanned receipt artifact when provider returns
    supported category PASSED
    SuggestCategoryFallbackUseCaseTest > invoke bypasses malformed cached category payload and requests provider PASSED
    SuggestCategoryFallbackUseCaseTest > invoke for receipt returns NotNeeded when confidence is strong and category
    exists PASSED
    SuggestCategoryFallbackUseCaseTest > invoke propagates CancellationException without writing FAILED artifact PASSED
    SuggestCategoryFallbackUseCaseTest > invoke returns Disabled when flag off PASSED
    SuggestCategoryFallbackUseCaseTest > invoke stores route diagnostics in FAILED category artifact PASSED
    SuggestCategoryFallbackUseCaseTest > invoke stores READY artifact when provider returns supported category PASSED
    SuggestCategoryFallbackUseCaseTest > invoke for receipt still allows fallback when current category is Uncategorized
    PASSED
    AdvancedAnalyticsDashboardTest > transactions at or after endDate are excluded from the final monthly bucket PASSED
    AdvancedAnalyticsDashboardTest > no expenses and empty dataset return zeros and stable shapes PASSED
    AdvancedAnalyticsDashboardTest > no income edge case keeps totals and avoids divide errors in insights PASSED
    AdvancedAnalyticsDashboardTest > equal income and expenses yields zero net and no savings insight PASSED
    AdvancedAnalyticsDashboardTest > totals net cashflow top categories top merchants and trends calculate correctly
    PASSED
    AnomalyDetectorTest > anomaly_detector_false_negative_guard_for_extreme_contextual_outlier PASSED
    AnomalyDetectorTest > shared expenses use effective amount for anomaly detection PASSED
    AnomalyDetectorTest > anomaly_detector_false_positive_guard_on_tight_distribution PASSED
    AnomalyDetectorTest > zero dispersion baseline still flags obvious spike PASSED
    InvestmentTrackerTest > dayChange uses previous day close snapshot PASSED
    InvestmentTrackerTest > portfolio history collapses same-day snapshots to latest value per investment FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InvestmentTrackerTest > gainLoss calculated correctly PASSED
    InvestmentTrackerTest > returns null for nonexistent investment PASSED
    InvestmentTrackerTest > gainLoss includes purchase fees in investment performance PASSED
    InvestmentTrackerTest > allTimeHigh is null when no historical values exist PASSED
    InvestmentTrackerTest > dayChange is null when no in-window values exist PASSED
    InvestmentTrackerTest > portfolio summary includes purchase fees in total invested and gain loss PASSED
    InvestmentTrackerTest > allTimeHigh and allTimeLow query from epoch 0, not 30-day window PASSED
    TravelDetectionEngineStressTest > stress - returns null below minimum located expenses PASSED
    TravelDetectionEngineStressTest > stress - detects home and nonzero home spend PASSED
    TravelDetectionEngineStressTest > stress - extracts destination hint from resolved address PASSED
    TravelDetectionEngineStressTest > stress - classifies local and travel spending PASSED
    TravelDetectionEngineStressTest > stress - separates trips when gap exceeds three days PASSED
    TravelDetectionEngineStressTest > stress - handles out-of-order input deterministically PASSED
    TravelDetectionEngineStressTest > stress - groups travel expenses within gap into one trip PASSED
    InsightsEngineValidationTest > transaction size calculation excludes not-mine transactions PASSED
    SplitCalculatorTest > custom amount split returns exact provided amounts and preserves sum PASSED
    SplitCalculatorTest > percentage split 33_33 each allocates remainder to first member by tie break PASSED
    SplitCalculatorTest > equal split of 100 among 3 members distributes remainder to first member PASSED
    SplitCalculatorTest > calculateBalances with crash test 4_6 balances simplifies to total 50 settlement volume PASSED
    SplitCalculatorTest > invalid custom payload falls back to equal split preserving total amount PASSED
    SplitCalculatorTest > equal split of 100 among 7 members preserves sum with four one-cent remainders PASSED
    SplitCalculatorTest > percentage split 33_33 33_33 33_34 maps to exact cent values and preserves sum PASSED
    SplitCalculatorTest > large equal split stays positive and preserves total PASSED
    InsightsEngineValidationTest > spending pace calculates correct projected total PASSED
    SplitCalculatorTest > equal split validation allows backdated expense when payer is still an eligible participant
    PASSED
    SplitCalculatorTest > unequal split returns exact provided amounts and preserves sum PASSED
    SplitCalculatorTest > equal split validation rejects backdated expense when payer joined after expense date PASSED
    SplitCalculatorTest > equal split validation rejects backdated expense when no participant qualifies PASSED
    InsightsEngineValidationTest > transaction size calculation excludes non-purchase transactions PASSED
    InsightsEngineValidationTest > transaction size profiles calculate correct averages PASSED
    InsightsEngineValidationTest > category insights calculate correct percentages PASSED
    InsightsEngineValidationTest > monthly comparison handles zero previous month PASSED
    InsightsEngineValidationTest > monthly comparison handles negative change (spending decrease) PASSED
    InsightsEngineValidationTest > spending pace handles first three days conservatively PASSED
    NaturalLanguageSearchEngineVoiceInputTest > start voice input forwards result callback PASSED
    NaturalLanguageSearchEngineVoiceInputTest > start voice input forwards error callback PASSED
    NaturalLanguageSearchEngineVoiceInputTest > start voice input keeps default error callback optional PASSED
    InsightsEngineValidationTest > day of week pattern calculates correct totals PASSED
    InsightsEngineValidationTest > monthly comparison calculates correct percentage change PASSED
    InsightsEngineValidationTest > category insights calculate correct change from previous PASSED
    InsightsEngineValidationTest > spending pace delegates to SpendingPaceCalculator canonical output PASSED
    InsightsEngineValidationTest > empty expenses list returns valid snapshot with zeros FAILED
        java.lang.AssertionError
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingPaceGoldenTest > golden march last day projection equals actual month spent PASSED
    SpendingPaceGoldenTest > golden march day 15 returns expected spent projected pace percentage and over pace status
    PASSED
    TransferDirectionAnalyticsTest > insights stateflow emits initial and updated values PASSED
    GoogleWalletParserTest > keep google pay merchant purchase wording as purchase PASSED
    GoogleWalletParserTest > parse paid to friend wording as transfer PASSED
    GoogleWalletParserTest > reject unrealistic amount over 50000 PASSED
    GoogleWalletParserTest > parse INR amount with rupee symbol and merchant PASSED
    GoogleWalletParserTest > parse amount with E prefix - corrupted euro symbol PASSED
    GoogleWalletParserTest > parse incoming p2p receive as transfer PASSED
    GoogleWalletParserTest > supports both wallet package variants PASSED
    GoogleWalletParserTest > reject add a card notification PASSED
    GoogleWalletParserTest > parse amount with euro symbol - normal case PASSED
    GoogleWalletParserTest > reject unrealistic amount under 0_01 PASSED
    GoogleWalletParserTest > parse outgoing p2p send as transfer PASSED
    GoogleWalletParserTest > title is merchant when no at-pattern in text PASSED
    GoogleWalletParserTest > parse amount with currency suffix PASSED
    GoogleWalletParserTest > keep paid to merchant wording as purchase PASSED
    GoogleWalletParserTest > parse INR amount with code prefix PASSED
    GoogleWalletParserTest > reject loyalty offer PASSED
    GoogleWalletParserTest > clean card info from merchant PASSED
    GoogleWalletParserTest > parse payment at merchant in text PASSED
    TransferDirectionDetectorTest > detect incoming - greek eisrema pattern PASSED
    TransferDirectionDetectorTest > extract account name - greek from pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - greek code chi pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - greek pliromi pattern PASSED
    TransferDirectionDetectorTest > validate - total patterns at least 50 PASSED
    TransferDirectionDetectorTest > extract account name - no match returns null PASSED
    TransferDirectionDetectorTest > real world - revolut incoming notification PASSED
    TransferDirectionDetectorTest > detect direction - empty text returns null PASSED
    TransferDirectionDetectorTest > detect incoming - greek pistosi pattern PASSED
    TransferDirectionDetectorTest > real world - atm withdrawal notification PASSED
    TransferDirectionDetectorTest > detect outgoing - revolut paid to pattern PASSED
    TransferDirectionDetectorTest > detect direction - ambiguous text returns null PASSED
    TransferDirectionDetectorTest > detect direction - null text returns null PASSED
    TransferDirectionDetectorTest > validate - outgoing patterns not empty PASSED
    TransferDirectionDetectorTest > detect direction - mixed case greek PASSED
    TransferDirectionDetectorTest > detect outgoing - greek xreosi pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - sent to pattern PASSED
    TransferDirectionDetectorTest > extract account name - greek to pattern PASSED
    TransferDirectionDetectorTest > validate - incoming patterns not empty PASSED
    TransferDirectionDetectorTest > detect incoming - transfer in pattern PASSED
    TransferDirectionDetectorTest > detect direction - case insensitive matching PASSED
    TransferDirectionDetectorTest > detect outgoing - greek bank card purchase PASSED
    TransferDirectionDetectorTest > extract account name - to pattern PASSED
    TransferDirectionDetectorTest > accuracy - should detect 90% of clear patterns PASSED
    TransferDirectionDetectorTest > detect incoming - greek katathesi pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - paid to pattern PASSED
    TransferDirectionDetectorTest > detect incoming - greek bank deposit pattern PASSED
    TransferDirectionDetectorTest > real world - greek bank transfer notification PASSED
    TransferDirectionDetectorTest > detect incoming - greek code pi pattern PASSED
    TransferDirectionDetectorTest > detect incoming - revolut received from pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - debited pattern PASSED
    BackupRestoreIntegrityE2ETest > full app state integrity preserved across restore cycle FAILED
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TransferDirectionDetectorTest > detect incoming - received from pattern PASSED
    TransferDirectionDetectorTest > detect incoming - refund pattern PASSED
    TransferDirectionDetectorTest > real world - salary deposit notification PASSED
    TransferDirectionDetectorTest > detect outgoing - greek metafora pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - revolut transfer to pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - withdrew pattern PASSED
    TransferDirectionDetectorTest > detect incoming - credited pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - transfer to pattern PASSED
    TransferDirectionDetectorTest > extract account name - strips trailing greek amount details PASSED
    TransferDirectionDetectorTest > detect direction - transfer to wins in mixed incoming wording PASSED
    TransferDirectionDetectorTest > extract account name - from pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - transfer out pattern PASSED
    TransferDirectionDetectorTest > extract account name - strips trailing iban details PASSED
    TransferDirectionDetectorTest > detect direction - greek transfer se wins in mixed wording PASSED
    TransferDirectionDetectorTest > detect direction - conflicting patterns prioritizes first match PASSED
    TransferDirectionDetectorTest > detect incoming - salary deposit pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - greek anilema pattern PASSED
    TransferDirectionDetectorTest > detect direction - non-transfer type returns null PASSED
    TransferDirectionDetectorTest > detect incoming - deposited to pattern PASSED
    TransferDirectionDetectorTest > detect incoming - greek misthos pattern PASSED
    TransferDirectionDetectorTest > detect outgoing - greek bank withdrawal pattern PASSED
    NotificationPrivacyHardeningTest > notification_disabled_isCaptureAllowed_returns_false PASSED
    NotificationPrivacyHardeningTest > privacy_gate_denied_isCaptureAllowed_returns_false PASSED
    NotificationPrivacyHardeningTest > store_raw_notification_preserves_all_fields PASSED
    NotificationPrivacyHardeningTest > notification_enabled_isCaptureAllowed_returns_true PASSED
    NotificationPrivacyHardeningTest > metadata_only_notification_no_raw_extras_in_diagnostics PASSED
    NotificationPrivacyHardeningTest > do_not_store_notification_no_raw_text_in_pending_reviews PASSED
    NotificationPrivacyHardeningTest > dedupeFingerprint_always_preserved_regardless_of_mode PASSED
    NotificationPrivacyHardeningTest > privacy_fail_closed_notification_isCaptureAllowed_returns_false PASSED
    NotificationPrivacyHardeningTest > do_not_store_notification_no_raw_text_in_payload PASSED
    NotificationPrivacyHardeningTest > redacted_notification_pending_review_has_redacted_text PASSED
    RetentionRegistryTest > purge_result_with_zero_rows_is_still_success PASSED
    RetentionRegistryTest > data_retention_records_per_target_counts PASSED
    RetentionRegistryTest > retention_target_reports_error_without_throwing PASSED
    RetentionRegistryTest > retention_target_is_idempotent_on_empty_table PASSED
    RetentionRegistryTest > retention_target_name_identifies_data_class PASSED
    RetentionRegistryTest > retention_target_reports_rows_purged PASSED
    RetentionRegistryTest > disable_notification_capture_does_not_cancel_data_retention PASSED
    ReceiptParserOcrPatternsTest > test number with space after comma PASSED
    ReceiptParserOcrPatternsTest > test OCR error - METPHTA (ΜΕΤΡΗΤΑ) PASSED
    ReceiptParserOcrPatternsTest > test Greek merchant name - ΣΚΛΑΒΕΝΙΤΗΣ PASSED
    ReceiptParserOcrPatternsTest > test line item with quantity PASSED
    ReceiptParserOcrPatternsTest > test VAT extraction with Greek label PASSED
    ReceiptParserOcrPatternsTest > test complete line - ΣΥΝΟΛΟ € 50,00 PASSED
    ReceiptParserOcrPatternsTest > test skip receipt serial number - ZEIPA PASSED
    ReceiptParserOcrPatternsTest > test Greek amount keyword - ΠΛΗΡΩΤΕΟ variants PASSED
    ReceiptParserOcrPatternsTest > test OCR error - EYPOMEGA (ΕΥΡΩ) PASSED
    ReceiptParserOcrPatternsTest > test currency after amount - 50,00 € PASSED
    ReceiptParserOcrPatternsTest > test actual OCR - IYN noZOTHTA PASSED
    ReceiptParserOcrPatternsTest > test short year format - DD-MM-YY PASSED
    ReceiptParserOcrPatternsTest > test phone number not picked as amount PASSED
    ReceiptParserOcrPatternsTest > test complete line - ΣΥΝΟΛΟ 80_43 EUR PASSED
    ReceiptParserOcrPatternsTest > test Greek total keyword - ΣΥΝΟΛΙΚΗ ΑΞΙΑ variant PASSED
    ReceiptParserOcrPatternsTest > test actual OCR - ZYNOAO IONTAN PASSED
    ReceiptParserOcrPatternsTest > test severely mangled number PASSED
    ReceiptParserOcrPatternsTest > test unit price not picked as total PASSED
    ReceiptParserOcrPatternsTest > test VAT with dots in label PASSED
    ReceiptParserOcrPatternsTest > test whole year not picked as amount PASSED
    ReceiptParserOcrPatternsTest > test bilingual cash PASSED
    ReceiptParserOcrPatternsTest > test bilingual total PASSED
    ReceiptParserOcrPatternsTest > test European date format - DD-MM-YYYY PASSED
    ReceiptParserOcrPatternsTest > test OCR error - EYNONO (ΣΥΝΟΛΟ) PASSED
    ReceiptParserOcrPatternsTest > test dynamic year rejection PASSED
    ReceiptParserOcrPatternsTest > test tax ID not picked as amount PASSED
    ReceiptParserOcrPatternsTest > test date with dots PASSED
    ReceiptParserOcrPatternsTest > test bilingual VAT PASSED
    ReceiptParserOcrPatternsTest > test complete line - ΠΟΣΟ_AMOUNT PASSED
    ReceiptParserOcrPatternsTest > test line items extraction PASSED
    ReceiptParserOcrPatternsTest > test skip receipt number - APIOMOE PASSED
    ReceiptParserOcrPatternsTest > test OCR error - HM_NIA (ΗΜΕΡΟΜΗΝΙΑ) PASSED
    ReceiptParserOcrPatternsTest > test year-like amount not confused with year PASSED
    ReceiptParserOcrPatternsTest > test compound keyword - ΣΥΝΟΛΙΚΗ ΑΞΙΑ PASSED
    ReceiptParserOcrPatternsTest > test currency before amount - €50,00 PASSED
    ReceiptParserOcrPatternsTest > test number with space before dot PASSED
    ReceiptParserOcrPatternsTest > test OCR error - EYP9 (ΕΥΡΩ) PASSED
    ReceiptParserOcrPatternsTest > test VAT percentage not confused with total PASSED
    ReceiptParserOcrPatternsTest > test merchant with Greeklish - DIAMANTIS MAZOUTHIS PASSED
    ReceiptParserOcrPatternsTest > test Greek PAYABLE keyword - ΠΛΗΡΩΤΕΟ PASSED
    ReceiptParserOcrPatternsTest > test card receipt pattern PASSED
    ReceiptParserOcrPatternsTest > test date with spacing issues PASSED
    ReceiptParserOcrPatternsTest > test Greek FINAL keyword - ΤΕΛΙΚΟ PASSED
    ReceiptParserOcrPatternsTest > test compound keyword - ΓΕΝΙΚΟ ΣΥΝΟΛΟ PASSED
    ReceiptParserOcrPatternsTest > test date with dashes PASSED
    ReceiptParserOcrPatternsTest > test Greek merchant name - ΛΙΔΛ PASSED
    ReceiptParserOcrPatternsTest > test extraction before percentage sign - Receipt 3 failure case PASSED
    ReceiptParserOcrPatternsTest > test Greek CASH keyword - ΜΕΤΡΗΤΑ PASSED
    ReceiptParserOcrPatternsTest > test Greek TOTAL keyword - ΣΥΝΟΛΟ PASSED
    ReceiptParserOcrPatternsTest > test bilingual thank you PASSED
    ReceiptParserOcrPatternsTest > test confidence score with minimal data PASSED
    ReceiptParserOcrPatternsTest > test compound keyword - ΚΑΘΑΡΗ ΑΞΙΑ PASSED
    ReceiptParserOcrPatternsTest > test severely mangled number 2 PASSED
    ReceiptParserOcrPatternsTest > test Greek AMOUNT keyword - ΠΟΣΟ PASSED
    ReceiptParserOcrPatternsTest > test confidence score with good data PASSED
    ReceiptParserOcrPatternsTest > test number with space as thousands separator PASSED
    ReceiptParserOcrPatternsTest > test EUR text format PASSED
    ReceiptParserOcrPatternsTest > test skip tax-only lines - Receipt 1 failure case PASSED
    ReceiptParserOcrPatternsTest > test actual OCR - NAHPQTEO (ΠΛΗΡΩΤΕΟ) PASSED
    EmptyDataFlowTest > empty dataset flows safely with sensible defaults STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline(FlowPipelineTestHarness.kt:108)
                at
    com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline$default(FlowPipelineTestHarness.kt:75)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BillReminderManagerTest > markBillPaid advances semi annually by six months PASSED
    BillReminderManagerTest > markBillPaid advances irregular by one month fallback PASSED
    BillReminderManagerTest > getMonthlyBillsTotal includes annual semi annual and irregular semantics PASSED
    BillReminderManagerTest > getUpcomingReminders maps due today to critical and tomorrow to urgent PASSED
    BillReminderManagerTest > markBillPaid advances annually by one year PASSED
    TaxCalculationTest > US 12 percent bracket applies to income between 11000 and 44725 PASSED
    TaxCalculationTest > VAT inclusive price calculation is accurate PASSED
    TaxCalculationTest > tax bracket rates are between 0 and 1 PASSED
    TaxCalculationTest > greece configuration uses EUR currency PASSED
    TaxCalculationTest > VAT calculation with zero amount returns zero PASSED
    TaxCalculationTest > greece VAT rate is 24 percent PASSED
    TaxCalculationTest > tax on 15000 euros income in greece spans two brackets PASSED
    TaxCalculationTest > tax on income at bracket boundary uses correct rate PASSED
    TaxCalculationTest > VAT calculation on 100 euros returns 24 euros PASSED
    TaxCalculationTest > greece low income bracket is 9 percent up to 10000 PASSED
    TaxCalculationTest > US VAT rate is 0 percent PASSED
    TaxCalculationTest > tax on 5000 euros income in greece is 9 percent PASSED
    TaxCalculationTest > US 10 percent bracket applies to first 11000 PASSED
    TaxCalculationTest > US has correct number of tax brackets PASSED
    TaxCalculationTest > tax calculation with very high income PASSED
    TaxCalculationTest > greece high income bracket is 32 percent above 20000 PASSED
    TaxCalculationTest > greece medium income bracket is 22 percent from 10000 to 20000 PASSED
    TaxCalculationTest > current configuration returns greece by default PASSED
    TaxCalculationTest > US configuration uses USD currency PASSED
    TaxCalculationTest > VAT on fractional amount maintains precision PASSED
    TaxCalculationTest > tax bracket names are not empty PASSED
    TaxCalculationTest > tax on 30000 euros income in greece spans all brackets PASSED
    TaxCalculationTest > tax calculation with fractional income PASSED
    TaxCalculationTest > factory returns greece configuration for GR code PASSED
    TaxCalculationTest > greece has correct number of tax brackets PASSED
    TaxCalculationTest > tax brackets have valid min and max PASSED
    TaxCalculationTest > factory defaults to greece when no code provided PASSED
    TaxCalculationTest > tax on 10000 euros income in greece is 900 euros PASSED
    TaxCalculationTest > effective tax rate calculation is accurate PASSED
    TaxCalculationTest > VAT calculation on 50 euros returns 12 euros PASSED
    TaxCalculationTest > tax on income just above boundary uses higher rate PASSED
    TaxCalculationTest > factory returns US configuration for US code PASSED
    TaxCalculationTest > tax on zero income returns zero PASSED
    TaxCalculationTest > factory defaults to greece for unknown country code PASSED
    CategorizeExpenseUseCaseTest > empty expense handled gracefully PASSED
    CategorizeExpenseUseCaseTest > merchant normalized before categorization PASSED
    CategorizeExpenseUseCaseTest > expense categorized by engine PASSED
    CategorizeExpenseUseCaseTest > engine failure unknown category assigned PASSED
    AmountUtilsStressTest > stress - multiple currency symbols PASSED
    AmountUtilsStressTest > boundary - exactly 1 million PASSED
    AmountUtilsStressTest > stress - numbers with special characters PASSED
    AmountUtilsStressTest > stress - random currency-like strings PASSED
    AmountUtilsStressTest > stress - US locale parsing PASSED
    AmountUtilsStressTest > stress - custom max validation PASSED
    AmountUtilsStressTest > stress - single letter PASSED
    AmountUtilsStressTest > stress - negative validation rejects PASSED
    AmountUtilsStressTest > regression - negative formats still work PASSED
    AmountUtilsStressTest > stress - negative with dash prefix PASSED
    AmountUtilsStressTest > stress - negative with unicode minus PASSED
    AmountUtilsStressTest > stress - unicode whitespace nbsp and narrow nbsp PASSED
    AmountUtilsStressTest > stress - very long strings PASSED
    AmountUtilsStressTest > stress - custom currency code prefix PASSED
    AmountUtilsStressTest > boundary - just above zero PASSED
    AmountUtilsStressTest > stress - E prefix without space PASSED
    AmountUtilsStressTest > stress - maximum amount at exactly 1M in string parsing PASSED
    AmountUtilsStressTest > stress - maximum amount boundary at exactly 1M PASSED
    AmountUtilsStressTest > stress - leading whitespace PASSED
    AmountUtilsStressTest > bug - parseAmount allows negative but isValidAmount rejects PASSED
    AmountUtilsStressTest > stress - zeros only PASSED
    AmountUtilsStressTest > stress - mixed separators with comma as decimal PASSED
    AmountUtilsStressTest > stress - negative parsing works but validation rejects PASSED
    AmountUtilsStressTest > stress - formatAmount uses default locale PASSED
    AmountUtilsStressTest > regression - currency symbols still work PASSED
    AmountUtilsStressTest > stress - mixed separators with dot as decimal PASSED
    AmountUtilsStressTest > stress - yen prefix PASSED
    AmountUtilsStressTest > stress - multiple thousands separators inconsistent PASSED
    AmountUtilsStressTest > boundary - just over 1 million PASSED
    AmountUtilsStressTest > stress - ambiguous thousands with only dots PASSED
    AmountUtilsStressTest > stress - negative with parentheses PASSED
    AmountUtilsStressTest > stress - many leading zeros PASSED
    AmountUtilsStressTest > stress - internal whitespace removal PASSED
    AmountUtilsStressTest > boundary - exactly zero PASSED
    AmountUtilsStressTest > stress - ambiguous thousands with only commas PASSED
    AmountUtilsStressTest > stress - unicode characters in input PASSED
    AmountUtilsStressTest > stress - extremely large amounts rejected PASSED
    AmountUtilsStressTest > stress - two digits after comma treated as decimal PASSED
    AmountUtilsStressTest > stress - completely invalid strings PASSED
    AmountUtilsStressTest > stress - only special characters PASSED
    AmountUtilsStressTest > stress - single digit PASSED
    AmountUtilsStressTest > stress - decimal only without integer part PASSED
    AmountUtilsStressTest > stress - French locale parsing PASSED
    AmountUtilsStressTest > stress - amount with slashes PASSED
    AmountUtilsStressTest > regression - known working formats still work PASSED
    AmountUtilsStressTest > stress - Greek locale parsing PASSED
    AmountUtilsStressTest > stress - just over 1M in string parsing PASSED
    AmountUtilsStressTest > stress - random alphanumeric strings should not crash PASSED
    AmountUtilsStressTest > boundary - very large valid amount PASSED
    AmountUtilsStressTest > stress - emojis in input PASSED
    AmountUtilsStressTest > stress - trailing whitespace PASSED
    AmountUtilsStressTest > stress - euro prefix PASSED
    AmountUtilsStressTest > stress - maximum amount boundary just over 1M PASSED
    AmountUtilsStressTest > stress - euro suffix PASSED
    AmountUtilsStressTest > bug - ambiguous amount with dots interpretation PASSED
    AmountUtilsStressTest > stress - pound prefix PASSED
    AmountUtilsStressTest > regression - validation rules unchanged PASSED
    AmountUtilsStressTest > stress - signed zero PASSED
    AmountUtilsStressTest > stress - European locale parsing PASSED
    AmountUtilsStressTest > stress - tab and newline characters PASSED
    AmountUtilsStressTest > stress - dollar prefix PASSED
    AmountUtilsStressTest > regression - formatAmount is locale-stable across locales PASSED
    AmountUtilsStressTest > stress - scientific notation PASSED
    AmountUtilsStressTest > stress - very small amounts PASSED
    AmountUtilsStressTest > stress - three digit after comma treated as decimal PASSED
    MoneyTest > construction - fromDouble should preserve precision PASSED
    MoneyTest > operations - negate should flip sign PASSED
    MoneyTest > string representation - toString should format correctly PASSED
    MoneyTest > arithmetic - subtraction should maintain precision PASSED
    MoneyTest > extension functions - String toMoney should work PASSED
    MoneyTest > division - split 100 into 3 equal parts PASSED
    MoneyTest > edge cases - very small amounts PASSED
    MoneyTest > extension functions - Double toMoney should work PASSED
    MoneyTest > rounding - HALF_UP should round 0_005 to 0_01 PASSED
    MoneyTest > money value class - should be comparable PASSED
    MoneyTest > construction - fromString should parse correctly PASSED
    MoneyTest > edge cases - very large amounts PASSED
    MoneyTest > division - 1 cent remainder should be handled correctly PASSED
    MoneyTest > arithmetic - multiplication should handle correctly PASSED
    MoneyTest > construction - fromBigDecimal should use provided scale PASSED
    MoneyTest > extension functions - sum should total collection PASSED
    MoneyTest > edge cases - zero amount should be handled PASSED
    MoneyTest > complex calculation - VAT calculation example PASSED
    MoneyTest > edge cases - negative amounts should be handled PASSED
    MoneyTest > percentage - calculate 10 percent PASSED
    MoneyTest > percentage - calculate 33_33 percent split PASSED
    MoneyTest > extension functions - averageMoney should calculate correctly PASSED
    MoneyTest > construction - cents should convert correctly PASSED
    MoneyTest > comparison - equals should work correctly PASSED
    MoneyTest > rounding - HALF_UP should round 0_004 to 0_00 PASSED
    MoneyTest > operations - abs should return absolute value PASSED
    MoneyTest > percentage - calculate 24 percent VAT PASSED
    MoneyTest > arithmetic - addition should avoid floating point errors PASSED
    TimePeriodUtilsValidationTest > getEndOfWeek returns next Monday at midnight PASSED
    TimePeriodUtilsValidationTest > getQuarterRange returns correct start and end for current quarter PASSED
    TimePeriodUtilsValidationTest > startOfMonth and endOfMonth are consistent PASSED
    TimePeriodUtilsValidationTest > getDayIndexFromMonthStart returns 0-based index PASSED
    TimePeriodUtilsValidationTest > getStartOfQuarter returns correct for Q1 PASSED
    TimePeriodUtilsValidationTest > getStartOfQuarter returns correct for Q2 PASSED
    TimePeriodUtilsValidationTest > getStartOfQuarter returns correct for Q3 PASSED
    TimePeriodUtilsValidationTest > getStartOfQuarter returns correct for Q4 PASSED
    TimePeriodUtilsValidationTest > getYearRange returns correct start and end for current year PASSED
    TimePeriodUtilsValidationTest > getEndOfQuarter returns correct for Q1 PASSED
    TimePeriodUtilsValidationTest > getDayRange returns same boundaries as individual start and end calls PASSED
    TimePeriodUtilsValidationTest > leap year February 29 is handled correctly PASSED
    TimePeriodUtilsValidationTest > daysBetween handles DST transition correctly PASSED
    TimePeriodUtilsValidationTest > daysBetween ignores time of day PASSED
    TimePeriodUtilsValidationTest > getWeekOfYear returns correct week number PASSED
    TimePeriodUtilsValidationTest > month period calculation for April 2 shows last 30 days not just April PASSED
    TimePeriodUtilsValidationTest > getMonthRange returns correct start and end for previous month PASSED
    TimePeriodUtilsValidationTest > getStartOfWeek returns Monday 00_00_00_000 PASSED
    TimePeriodUtilsValidationTest > addMonths Jan 31 plus 1 month is Feb 29 in leap year PASSED
    TimePeriodUtilsValidationTest > isInRange excludes values before start PASSED
    TimePeriodUtilsValidationTest > startOfDay and endOfDay are consistent PASSED
    TimePeriodUtilsValidationTest > getYearRange returns correct start and end for previous year PASSED
    TimePeriodUtilsValidationTest > addDays subtracts correctly with negative value PASSED
    TimePeriodUtilsValidationTest > getDaysRemainingInMonth calculates correctly PASSED
    TimePeriodUtilsValidationTest > getEndOfYear returns January 1st next year at midnight PASSED
    TimePeriodUtilsValidationTest > getMonthRange returns correct start and end for current month PASSED
    TimePeriodUtilsValidationTest > getStartOfDay handles DST spring forward PASSED
    TimePeriodUtilsValidationTest > isInRange includes middle values PASSED
    TimePeriodUtilsValidationTest > getStartOfYear returns January 1st at midnight PASSED
    TimePeriodUtilsValidationTest > isSameMonth returns true for same month PASSED
    TimePeriodUtilsValidationTest > week period calculation for April 2 shows last 7 days PASSED
    TimePeriodUtilsValidationTest > addMonths adds correctly PASSED
    TimePeriodUtilsValidationTest > getQuarterRange returns correct start and end for previous quarter PASSED
    TimePeriodUtilsValidationTest > non-leap year February 28 is handled correctly PASSED
    TimePeriodUtilsValidationTest > daysBetween calculates correctly for exact days PASSED
    TimePeriodUtilsValidationTest > isSameMonth returns false for different months PASSED
    TimePeriodUtilsValidationTest > transaction at exactly 00_00_00_000 is included in that day PASSED
    TimePeriodUtilsValidationTest > getLastNDaysRange returns correct 30-day range PASSED
    TimePeriodUtilsValidationTest > isInRange works for real month boundary PASSED
    TimePeriodUtilsValidationTest > addMonths Jan 31 plus 1 month is Feb 28 in non-leap year PASSED
    TimePeriodUtilsValidationTest > transaction on year boundary Dec 31 is included in that year PASSED
    TimePeriodUtilsValidationTest > addDays adds correctly PASSED
    TimePeriodUtilsValidationTest > getEndOfDay returns exactly 00_00_00_000 next day PASSED
    TimePeriodUtilsValidationTest > getHourOfDay returns correct hour PASSED
    TimePeriodUtilsValidationTest > addYears adds correctly PASSED
    TimePeriodUtilsValidationTest > isInRange includes startInclusive PASSED
    TimePeriodUtilsValidationTest > transaction on year boundary Jan 1 is included in that year PASSED
    TimePeriodUtilsValidationTest > getDaysInMonth returns correct for December PASSED
    TimePeriodUtilsValidationTest > getWeekRange with offset -1 returns previous week PASSED
    TimePeriodUtilsValidationTest > getDayOfMonth returns correct day PASSED
    TimePeriodUtilsValidationTest > getEndOfWeek on Sunday returns next Monday PASSED
    TimePeriodUtilsValidationTest > getStartOfDay handles DST fall back PASSED
    TimePeriodUtilsValidationTest > transactions inside 30-day window are included PASSED
    TimePeriodUtilsValidationTest > getStartOfDay returns exactly 00_00_00_000 PASSED
    TimePeriodUtilsValidationTest > getDaysInMonth returns correct for April PASSED
    TimePeriodUtilsValidationTest > transaction on month boundary 31st is included in that month PASSED
    TimePeriodUtilsValidationTest > transaction at 23_59_59_999 is included in that day PASSED
    TimePeriodUtilsValidationTest > getDayRange excludes start of next day PASSED
    TimePeriodUtilsValidationTest > getWeekRange returns Monday to Monday PASSED
    TimePeriodUtilsValidationTest > getEndOfWeek consistent with getWeekRange PASSED
    TimePeriodUtilsValidationTest > getEndOfMonth returns 1st of next month at 00_00_00_000 PASSED
    TimePeriodUtilsValidationTest > getMonth returns correct month (0-indexed) PASSED
    TimePeriodUtilsValidationTest > getCanonicalWeekRangeFromKey handles year rollover deterministically PASSED
    TimePeriodUtilsValidationTest > transaction on month boundary 1st is included in that month PASSED
    TimePeriodUtilsValidationTest > getStartOfMonth returns 1st at 00_00_00_000 PASSED
    TimePeriodUtilsValidationTest > addDays crosses year boundary correctly PASSED
    TimePeriodUtilsValidationTest > getDayRange contains any timestamp within that day PASSED
    TimePeriodUtilsValidationTest > transactions outside 30-day window are excluded PASSED
    TimePeriodUtilsValidationTest > isInRange excludes transaction on 1st of next month PASSED
    TimePeriodUtilsValidationTest > getDayOfWeek returns correct day PASSED
    TimePeriodUtilsValidationTest > getYear returns correct year PASSED
    TimePeriodUtilsValidationTest > getLastNDaysRange returns correct 7-day range PASSED
    TimePeriodUtilsValidationTest > addYears from leap day Feb 29 coerces to Feb 28 in non-leap year PASSED
    TimePeriodUtilsValidationTest > getStartOfWeek handles Sunday correctly PASSED
    TimePeriodUtilsValidationTest > isInRange excludes endExclusive PASSED
    TimePeriodUtilsValidationTest > getStartOfMonth handles month with different days PASSED
    TimePeriodUtilsValidationTest > addMonths handles year boundary PASSED
    EmptyDataFlowTest > empty dataset flows safely with sensible defaults PASSED
    ReceiptProcessingPipelineTest > ocr failure handled gracefully STANDARD_ERROR
        Caused by: java.lang.IllegalStateException: Module with the Main dispatcher had failed to initialize. For tests
    Dispatchers.setMain from kotlinx-coroutines-test module can be used
                at
    kotlinx.coroutines.test.internal.TestMainDispatcher$Companion.getCurrentTestScheduler$kotlinx_coroutines_test(TestMa
    inDispatcher.kt:50)
                at kotlinx.coroutines.test.TestScopeKt.withDelaySkipping(TestScope.kt:196)
                at kotlinx.coroutines.test.TestScopeKt.TestScope(TestScope.kt:163)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at com.yourname.expensetracker.data.ai.provider.CloudDedupeJudgeServiceTest.judge returns parse error for
    malformed verdict enum(CloudDedupeJudgeServiceTest.kt:151)
    BudgetAlertPipelineTest > budget at 110 percent triggers exceeded notification FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#6085).updateExceededNotification(eq(3), eq(1774990800000), any())) was not called.

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                         (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                          (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                          (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                                  (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                  (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                      (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                      (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                              (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetAlertPipelineTest > budget at 100 percent triggers critical notification FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1: NotificationService(#6118).sendBudgetAlert(eq(2),
    eq(Critical Budget Warning), any())). Only one matching call to NotificationService(#6118)/sendBudgetAlert(Int,
    String, String) happened, but arguments are not matching:

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                         (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                          (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                          (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                                  (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                  (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                      (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                      (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                              (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ExportReadBarrierTest > blockedDuringRestore_emits_nothing_in_restore FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    BudgetAlertPipelineTest > budget monitor cleanup prevents subsequent alert processing PASSED
    ExportReadBarrierTest > restore_blocks_all_non_NORMAL_modes_for_normal_reads PASSED
    ExportReadBarrierTest > export_read_allowed_in_NORMAL PASSED
    ConvertersTest > converts PURCHASE to string and back PASSED
    ConvertersTest > invalid string returns UNKNOWN PASSED
    ConvertersTest > empty string returns UNKNOWN PASSED
    ConvertersTest > converts all TransactionTypes roundtrip PASSED
    BudgetAlertPipelineTest > budget at 90 percent triggers warning notification FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#6181).updateWarningNotification(eq(1), eq(1774990800000), any())) was not called.

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                       (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                        (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                        (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                                (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult                                   (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                            (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptProcessingPipelineTest > ocr failure handled gracefully FAILED
            at app//kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:238)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptProcessingPipelineTest > unknown merchant categorized as Uncategorized FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GroupSettlementPipelineTest > 4-member equal split preserves sum and yields zero-sum balances PASSED
    GroupSettlementPipelineTest > settlement calculator produces minimal transfer count for mixed balances PASSED
    GroupSettlementPipelineTest > percentage split distributes according to configured ratios PASSED
    ReceiptProcessingPipelineTest > greek text normalization parses and categorizes correctly FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GroupSettlementPipelineTest > all-zero balances produce empty settlement plan PASSED
    ReceiptProcessingPipelineTest > ocr text parsed and categorized correctly FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ConcurrentOccurrenceClaimTest > two claims on same occurrence - only one succeeds PASSED
    ConcurrentOccurrenceClaimTest > CANCELLED occurrence cannot be claimed PASSED
    ConcurrentOccurrenceClaimTest > PAID occurrence cannot be claimed PASSED
    MerchantCategorizationDedupeGoldenTest > merchant key normalization groups variants together PASSED
    ReceiptMatchingNoDoubleCountGoldenTest > receipt linked to existing expense counts once in analytics PASSED
    TransactionLifecycleFullContractGoldenTest > expense lifecycle - create update delete with events PASSED
    MultiCurrencyAnalyticsTest > aggregate path handles over 2000 expenses without truncation PASSED
    MultiCurrencyAnalyticsTest > multi_currency_analytics_contract FAILED
        java.lang.AssertionError
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MultiCurrencyAnalyticsTest > merchant totals keep different raw labels as separate buckets PASSED
    MultiCurrencyAnalyticsTest > getExpensesByCurrency uses aggregate path PASSED
    > Task :app:testDebugUnitTest
    ServiceRestartReceiverStressTest > does not crash when restart startForegroundService fails PASSED
    ServiceRestartReceiverStressTest > restarts capture service on restart action PASSED
    ServiceRestartReceiverStressTest > ignores unrelated action PASSED
    TransferDirectionAnalyticsTest > prune folds excess tracked transfers and corrections still work after cap PASSED
    TransferDirectionAnalyticsTest > record auto detection updates counters rates and top endpoints PASSED
    TransferDirectionAnalyticsTest > reset clears state and get report reflects cleared metrics PASSED
    TransferDirectionAnalyticsTest > record user correction adjusts accuracy idempotently and supports reverting PASSED
    BudgetForecastingEngineTest > budget zero still forecasts history and is critical risk PASSED
    BudgetForecastingEngineTest > mixed shared and isNotMine with regular expenses forecast correctly PASSED
    BudgetForecastingEngineTest > null category budget uses uncapped monthly aggregate without category filter PASSED
    BudgetForecastingEngineTest > generateForecast calls insertWithDeactivation not plain insert PASSED
    BudgetForecastingEngineTest > calendar yearly budgets forecast against remaining calendar year window PASSED
    BudgetForecastingEngineTest > contiguous observed months still include zero filled current month PASSED
    BudgetForecastingEngineTest > multi-month gaps outside returned data collapse to zero-filled lookback window PASSED
    BudgetForecastingEngineTest > two month history stable trend keeps base prediction PASSED
    BudgetForecastingEngineTest > historical data uses effectiveAmount for percentage shared expenses PASSED
    DatabaseIntegrityTest > expenses with null dedupeKey detected as INFO violation PASSED
    BudgetForecastingEngineTest > two month history increasing trend applies increasing multiplier PASSED
    BudgetForecastingEngineTest > regenerating forecast for same period deactivates previous via DAO PASSED
    BudgetForecastingEngineTest > historical data excludes isNotMine expenses and zero fills missing months PASSED
    BudgetForecastingEngineTest > forecast uses remaining active period duration instead of requested approximation
    PASSED
    BudgetForecastingEngineTest > all months same amount keeps stddev zero and confidence bounded PASSED
    BudgetForecastingEngineTest > historical data uses effectiveAmount for shared expenses not raw amount PASSED
    RecurringPaymentMatchE2ETest > recurring rule generates occurrences and links actual payment STANDARD_ERROR
    BudgetForecastingEngineTest > projected overspend stays deterministic even with subunit confidence PASSED
    BudgetForecastingEngineTest > sparse months are zero filled before averaging PASSED
    DatabaseIntegrityTest > no duplicate dedupe keys when inserting expenses PASSED
    BudgetForecastingEngineTest > historical average stddev trend and prediction are calculated correctly PASSED
    BudgetForecastingEngineTest > seasonal adjustment stays neutral in december PASSED
    BudgetForecastingEngineTest > two month history decreasing trend applies decreasing multiplier PASSED
    BudgetForecastingEngineTest > single month history yields stable trend and zero stddev path PASSED
    SharedBudgetManagerTest > shared budget progress uses effectiveAmount for fixed share expense PASSED
    SharedBudgetManagerTest > shared budget progress preserves purchase-only budget spend semantics PASSED
    SharedBudgetManagerTest > shared budget progress is not truncated when expense count exceeds old LIMIT 2000 PASSED
    SharedBudgetManagerTest > shared budget progress calls whole wallet aggregate helper for overall budget PASSED
    SharedBudgetManagerTest > shared budget progress uses effectiveAmount for percentage share expense PASSED
    SharedBudgetManagerTest > shared budget progress calls aggregate helper with correct category and date range PASSED
    DatabaseIntegrityTest > full scan returns no violations on clean database PASSED
    SharedBudgetManagerTest > shared budget progress uses active rolling weekly window instead of month to date PASSED
    ReceiptLifecycleCoordinatorTest > processReceiptInput validates and persists receipt FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    SharedBudgetManagerTest > shared budget progress mixed shared and isNotMine triggers overbudget correctly PASSED
    ReceiptLifecycleCoordinatorTest > processReceiptInput fails on validation error PASSED
    SharedBudgetManagerTest > get shared budget progress returns category scoped totals and per member average PASSED
    HeatmapNormalizesCurrencyTest > home currency expenses included directly PASSED
    SharedBudgetManagerTest > get shared budget progress throws when budget is missing PASSED
    SharedBudgetManagerTest > shared budget progress treats isNotMine expense as zero PASSED
    SharedBudgetManagerTest > get member contributions returns zero placeholders for all members FAILED
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:169)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SharedBudgetManagerTest > shared budget progress uses calendar monthly window for calendar budgets PASSED
    SharedBudgetManagerTest > get shared budget progress with null category budget counts whole wallet spend and can
    exceed PASSED
    SavingsGamificationEngineTest > calculateStreak returns honest zero history for legacy balances without events
    PASSED
    SavingsGamificationEngineTest > getAchievements milestones unlocked at thresholds 100 500 1000 PASSED
    HeatmapNormalizesCurrencyTest > converted expenses included in normalized heatmap PASSED
    SavingsGamificationEngineTest > calculateStreak uses recorded contribution history for streak and month totals
    PASSED
    SavingsGamificationEngineTest > getLevelTitle returns correct title for each level PASSED
    SavingsGamificationEngineTest > getAchievements unlocks seven day streak from recorded history PASSED
    SavingsGamificationEngineTest > calculateLevel level based on total saved brackets PASSED
    SavingsGamificationEngineTest > calculateStreak resets current streak when latest contribution is older than
    yesterday PASSED
    InvestmentDaoTest > verify value fields for an investment PASSED
    CalculateBudgetStatusUseCaseTest > budget under spent status correct PASSED
    CalculateBudgetStatusUseCaseTest > budget exceeded status correct PASSED
    CalculateBudgetStatusUseCaseTest > no budget null status PASSED
    CalculateBudgetStatusUseCaseTest > multiple budgets all calculated PASSED
    CategorizationEngineTest > normalize uppercases PASSED
    RecurringPaymentMatchE2ETest > recurring rule generates occurrences and links actual payment PASSED
    CategorizationEngineTest > exact match returns category PASSED
    CategorizationEngineTest > substring match finds pattern within merchant name PASSED
    CsvExportImportRoundtripGoldenTest > csv sanitizer neutralizes formula injection and preserves safe values PASSED
    CategorizationEngineTest > normalize handles Greek characters PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt returns null when user already accepted recently PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt returns null when lifestyle creep is not detected PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt returns null when confidence is below threshold PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt returns null when prompted within cooldown window PASSED
    CategorizationEngineTest > returns unknown when no match found PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt returns null when monthly data is missing PASSED
    MerchantCanonicalizerTest > confidence penalty increases with stripped parts PASSED
    MerchantCanonicalizerTest > strips location suffix - lagka PASSED
    MerchantCanonicalizerTest > handles special characters PASSED
    MerchantCanonicalizerTest > strips business type suffix - ae PASSED
    MerchantCanonicalizerTest > strips business type suffix - sa PASSED
    MerchantCanonicalizerTest > no stripping for simple names PASSED
    MerchantCanonicalizerTest > does not treat dotted suffix as wildcard regex PASSED
    MerchantCanonicalizerTest > handles region prefix PASSED
    MerchantCanonicalizerTest > strips multiple suffixes PASSED
    MerchantCanonicalizerTest > handles Greek characters PASSED
    MerchantCanonicalizerTest > strips location suffix - stores PASSED
    HeatmapNormalizesCurrencyTest > failed conversion expenses excluded from normalized heatmap PASSED
    MoneyAggregateBuilderTest > mixed buckets convert all convertible buckets PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt keeps percentage savings rate and uses growth-based uplift
    PASSED
    CurrencyConverterEdgeCaseTest > negative amount keeps sign after conversion PASSED
    MoneyAggregateBuilderTest > warning message says currency bucket not transaction PASSED
    CurrencyConverterEdgeCaseTest > stale direct rate is still used and timestamp is preserved PASSED
    MoneyAggregateBuilderTest > single non-home currency bucket converts to home PASSED
    MoneyAggregateBuilderTest > single home currency bucket returns no conversion PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt handles zero previous income with minimum uplift PASSED
    MoneyAggregateBuilderTest > missing rate maps to FailureReason MISSING_RATE PASSED
    MoneyAggregateBuilderTest > empty buckets returns home currency empty aggregate PASSED
    MoneyAggregateBuilderTest > transaction counts preserved in sourceBuckets PASSED
    MoneyAggregateBuilderTest > stale rate maps to FailureReason RATE_STALE PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt returns null when inflation is below threshold PASSED
    LifestyleSavingsPromptUseCaseTest > evaluateAndPrompt converts savings rate ratio to percentage and caps uplift
    PASSED
    MerchantCleanerStressTest > stress - removes successful from end PASSED
    MerchantCleanerStressTest > bug - date with dots not removed PASSED
    MerchantCleanerStressTest > stress - removes time with AM PM PASSED
    MerchantCleanerStressTest > stress - removes time at end PASSED
    MerchantCleanerStressTest > stress - mixed Greek English PASSED
    MerchantCleanerStressTest > stress - removes at from start PASSED
    MerchantCleanerStressTest > bug - only stop words should become Unknown PASSED
    MerchantCleanerStressTest > stress - numbers in merchant name preserved PASSED
    MerchantCleanerStressTest > stress - removes card number PASSED
    MerchantCleanerStressTest > stress - removes date with slashes PASSED
    MerchantCleanerStressTest > stress - normalizes multiple spaces PASSED
    MerchantCleanerStressTest > stress - Greek characters preserved PASSED
    MerchantCleanerStressTest > stress - special characters preserved PASSED
    MerchantCleanerStressTest > stress - removes date with dashes PASSED
    MerchantCleanerStressTest > stress - only whitespace returns Unknown PASSED
    MerchantCleanerStressTest > stress - null input returns Unknown PASSED
    MerchantCleanerStressTest > stress - multiple consecutive cleaning operations PASSED
    MerchantCleanerStressTest > stress - case insensitive stop words PASSED
    MerchantCleanerStressTest > stress - removes Greek stop words PASSED
    MerchantCleanerStressTest > stress - removes full date PASSED
    MerchantCleanerStressTest > stress - blank string returns Unknown PASSED
    MerchantCleanerStressTest > stress - removes Greek card text PASSED
    MerchantCleanerStressTest > stress - empty string returns Unknown PASSED
    MerchantCleanerStressTest > stress - multiple card patterns PASSED
    MerchantCleanerStressTest > stress - removes Mastercard PASSED
    MerchantCleanerStressTest > stress - removes time with seconds PASSED
    MerchantCleanerStressTest > stress - removes Visa card PASSED
    MerchantCleanerStressTest > stress - unicode non-breaking space PASSED
    MerchantCleanerStressTest > stress - single character returns PASSED
    MerchantCleanerStressTest > bug - trailing punctuation not removed PASSED
    MerchantCleanerStressTest > stress - removes confirmed from end PASSED
    ReceiptLifecycleDbContractTest > receipt link prevents orphaned state PASSED
    MerchantCleanerStressTest > stress - 1000 operations performance PASSED
    MerchantCleanerStressTest > stress - truncates very long merchant names PASSED
    MerchantCleanerStressTest > bug - emoji handling inconsistent PASSED
    MerchantCleanerStressTest > stress - real world notification example PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-9"
    > Task :app:testDebugUnitTest
    StringDistanceUtilsStressTest > stress - levenshtein identical strings PASSED
    StringDistanceUtilsStressTest > stress - jaroWinklerSimilarity with prefix bonus PASSED
    StringDistanceUtilsStressTest > stress - levenshtein completely different PASSED
    StringDistanceUtilsStressTest > stress - jaroSimilarity common prefix PASSED
    StringDistanceUtilsStressTest > stress - jaroSimilarity identical PASSED
    StringDistanceUtilsStressTest > stress - performance 1000 operations PASSED
    StringDistanceUtilsStressTest > stress - levenshteinSimilarity edge cases PASSED
    StringDistanceUtilsStressTest > stress - levenshtein performance with 1000 char strings FAILED
        java.lang.AssertionError: Should complete in under 50ms but took 401ms
    StringDistanceUtilsStressTest > stress - isFuzzyMatch with numbers PASSED
    StringDistanceUtilsStressTest > stress - combinedSimilarity identical strings PASSED
    StringDistanceUtilsStressTest > stress - jaroSimilarity empty PASSED
    StringDistanceUtilsStressTest > stress - levenshtein with unicode greek characters PASSED
    StringDistanceUtilsStressTest > stress - levenshtein similar with 1 edit PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch case insensitive PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch contains PASSED
    ReceiptLifecycleDbContractTest > scanned receipt inserted and queryable PASSED
    StringDistanceUtilsStressTest > stress - very long strings performance PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch with unicode greek PASSED
    StringDistanceUtilsStressTest > stress - levenshtein empty strings PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch within distance 2 PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch with special characters stripped PASSED
    StringDistanceUtilsStressTest > stress - combinedSimilarity returns value between 0 and 1 PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch identical PASSED
    StringDistanceUtilsStressTest > stress - jaroWinkler prefix weight parameter PASSED
    StringDistanceUtilsStressTest > stress - jaroWinklerSimilarity no prefix bonus below threshold PASSED
    StringDistanceUtilsStressTest > stress - emoji handling in fuzzy match PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch beyond distance 2 PASSED
    StringDistanceUtilsStressTest > stress - levenshtein with mixed scripts PASSED
    StringDistanceUtilsStressTest > stress - isFuzzyMatch null input PASSED
    WorkerLeaseRegistryTest > worker_checkpoint_throws_when_write_barrier_blocks FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException> but
    was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    WorkerLeaseRegistryTest > backup_waits_for_data_retention_worker_to_stop PASSED
    ReceiptLifecycleDbContractTest > receipt linked to expense PASSED
    WorkerLeaseRegistryTest > cancelled_worker_releases_lease PASSED
    WorkerLeaseRegistryTest > lease_close_is_idempotent PASSED
    WorkerLeaseRegistryTest > worker_checkpoint_passes_in_normal_mode PASSED
    WorkerLeaseRegistryTest > requestStopAndAwaitDrain_sets_stop_flag PASSED
    WorkerLeaseRegistryTest > restore_waits_for_running_worker_to_stop PASSED
    ReceiptLifecycleDbContractTest > receipt event log created PASSED
    InvestmentDaoTest > delete investment removes it PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-5"
    > Task :app:testDebugUnitTest
    WorkerLeaseRegistryTest > multiple_leases_all_must_release_before_drain PASSED
    WorkerLeaseRegistryTest > requestStopAndAwaitDrain_returns_true_when_no_active_workers PASSED
    WorkerLeaseRegistryTest > worker_checkpoint_throws_when_stop_requested FAILED
        java.lang.AssertionError: unexpected exception type thrown;
    expected:<java.util.concurrent.CancellationException> but was:<java.lang.IllegalStateException>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:221)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:305)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    WorkerLeaseRegistryTest > resetStopFlag_clears_stop_request PASSED
    CurrencyConverterEdgeCaseTest > accumulated conversion drift over repeated cycles stays bounded PASSED
    CurrencyConverterEdgeCaseTest > unknown currency pair without any path returns null PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-16"
    CurrencyConverterEdgeCaseTest > zero amount conversion returns zero converted amount PASSED
    CurrencyConverterEdgeCaseTest > storeRate rejects non positive and non finite rates PASSED
    CurrencyConverterEdgeCaseTest > storeRates skips invalid entries and persists valid ones only PASSED
    AccountingExportPolicyTest > requireSingleCurrency fails fast for mixed currency datasets PASSED
    AccountingExportPolicyTest > requirePurchaseTransactions fails fast for unsupported transaction types PASSED
    AccountingExportPolicyTest > validateAccountingDataset accepts single currency purchase dataset PASSED
    > Task :app:testDebugUnitTest
    MergedRecurringPatternsProviderTest > getPatternsFromSnapshots keeps same merchant manual rules with different
    signatures PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-8"
    > Task :app:testDebugUnitTest
    MergedRecurringPatternsProviderTest > getPatternsFromSnapshots keeps same merchant detected rules when signatures
    differ from manual PASSED
    MulticurrencyAnalyticsDashboardBudgetGoldenTest > multicurrency purchase total with partial conversion PASSED
    DailyAverageFlowTest > daily average uses periodDays not only daysWithSpending STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline(FlowPipelineTestHarness.kt:108)
                at
    com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline$default(FlowPipelineTestHarness.kt:75)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MergedRecurringPatternsProviderTest > getPatterns rolls forward manual next date before filtering windows PASSED
    MergedRecurringPatternsProviderTest > getPatternsFromSnapshots dedupes stale manual duplicates and keeps
    deterministic merchant label FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MergedRecurringPatternsProviderTest > getConfirmedPatterns returns active manual recurring only without detected
    suggestions PASSED
    SharedExpenseManagerTest > removeMember blocks deletion when member has paid expenses PASSED
    SharedExpenseManagerTest > removeMember blocks deletion when member is referenced in custom splits PASSED
    SharedExpenseManagerTest > addExpense rejects non finite custom split values FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InvestmentDaoTest > insert an investment holding PASSED
    SharedExpenseManagerTest > removeMember succeeds when member has no paid expenses and no split references PASSED
    SharedExpenseManagerTest > addExpense rejects non positive or non finite amount PASSED
    InvestmentDaoTest > query all investments returns inserted holdings PASSED
    TransactionLifecycleDbContractTest > create multiple expenses across categories and verify totals PASSED
    SharedExpenseManagerTest > crash test 4_9 split parity with SplitCalculator produces identical net balances bug B_02
    PASSED
    SharedExpenseManagerTest > removeMember blocks deletion when equal split expense is on or after joinedAt PASSED
    SharedExpenseManagerTest > calculateBalances dispatches all split types and computes expected net balances PASSED
    SharedExpenseManagerTest > addExpense uses group default currency from data port PASSED
    SharedExpenseManagerTest > removeMember ignores equal split expenses before joinedAt PASSED
    SharedExpenseManagerTest > addExpense rejects blank description PASSED
    SharedExpenseManagerTest > removeMember returns member not found when member is absent PASSED
    SharedExpenseManagerTest > calculateBalances uses joinedAt aware SplitCalculator for backdated equal splits PASSED
    SharedExpenseManagerTest > addExpense rejects payer outside group membership PASSED
    RecurringBillPaymentMatchTest > occurrence claim is atomic - only PLANNED can be claimed PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-4"
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "kotlinx.coroutines.DefaultExecutor"
    > Task :app:testDebugUnitTest
    HealthScoreEdgeCaseTest > zero income keeps savings rate score neutral at fifty PASSED
    HealthScoreEdgeCaseTest > new user asymmetry defaults bill reliability to seventy five and overall to fifty five
    PASSED
    TransactionLifecycleDbContractTest > create expense with missing category and verify null FK PASSED
    HealthScoreEdgeCaseTest > deposit only period gives max savings score with neutral runway and budget PASSED
    HealthScoreEdgeCaseTest > single category purchases with expenses above income floor savings score to zero PASSED
    HealthScoreEdgeCaseTest > weighted score uses toInt truncation and does not round bug b zero four FAILED
        java.lang.AssertionError: expected:<100> but was:<50>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InvestmentDaoTest > updatePrice changes current price and lastUpdated PASSED
    RecurringBillPaymentMatchTest > planned expense fulfilled after occurrence claim PASSED
    TransactionClassifierTest > getStats reflects training after background cycle PASSED
    TransactionClassifierTest > onBackground does not prevent future train calls PASSED
    TransactionLifecycleDbContractTest > create duplicate expense and verify deduplication PASSED
    TransactionClassifierTest > retrainFromCorrections works after onBackground PASSED
    TransactionClassifierTest > destroy permanently cancels scope - train still records but save is lost PASSED
    TransactionClassifierTest > onBackground does not prevent future predict calls PASSED
    TransactionClassifierTest > predict returns neutral score for untrained classifier PASSED
    TransactionClassifierTest > repeated onBackground transitions do not break classifier PASSED
    TransactionClassifierTest > onBackground does not prevent future initialization PASSED
    TransactionClassifierTest > onBackground is idempotent PASSED
    TransactionClassifierTest > cleanup delegates to destroy for backward compatibility PASSED
    AreaSpendingEngineStressTest > stress - aggregates spending by parsed area name PASSED
    AreaSpendingEngineStressTest > stress - computes representative centroid and averages PASSED
    AreaSpendingEngineStressTest > stress - ignores expenses without location or address PASSED
    AreaSpendingEngineStressTest > stress - sorts areas by descending total spend PASSED
    CustomSplitParserTest > parseAndValidate rejects split with unknown member duplicate and negative values PASSED
    CustomSplitParserTest > referencesMember uses parsed valid result first PASSED
    CustomSplitParserTest > parseAndValidate accepts custom amount split that sums exactly to total PASSED
    CustomSplitParserTest > parseAndValidate accepts unequal split when sums match total PASSED
    CustomSplitParserTest > referencesMember falls back to raw token matching when no parse result provided PASSED
    CustomSplitParserTest > parseAndValidate rejects custom amount split beyond AMOUNT_TOLERANCE PASSED
    CustomSplitParserTest > parseAndValidate rejects custom percent split beyond PERCENT_TOLERANCE PASSED
    CustomSplitParserTest > parseAndValidate rejects non finite totals and split values PASSED
    CustomSplitParserTest > parseAndValidate rejects equal mode because no custom payload is required PASSED
    CustomSplitParserTest > parseAndValidate accepts custom amount split at AMOUNT_TOLERANCE boundary 0_01 PASSED
    CustomSplitParserTest > parseAndValidate accepts custom percent split at PERCENT_TOLERANCE boundary 0_1 PASSED
    CustomSplitParserTest > referencesMember uses parsed invalid partial result when available PASSED
    SynthesisEngineGoldenTest > block party discretionary base rate follows budget minus recurring planned and strict
    goal reserves PASSED
    SynthesisEngineGoldenTest > confidence band thresholds classify recurring patterns into committed likely and
    excluded with base confidence 0 85 FAILED
    SynthesisEngineGoldenTest > biweekly recurrence matches on days 14 and 16 but not on day 17 with plus minus 2
    tolerance PASSED
    InvestmentDaoTest > getTotalUnrealizedGainLoss returns correct sum PASSED
    NegotiationEngineTest > no recommendation when no data PASSED
    NegotiationEngineTest > provider failure handled gracefully PASSED
    NegotiationEngineTest > market rate staleness is detected correctly PASSED
    GreekBankParserStressTest > supports european decimal comma PASSED
    GreekBankParserStressTest > handles greek merchant text PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-15"
    > Task :app:testDebugUnitTest
    GreekBankParserStressTest > is deterministic for same notification FAILED
        java.lang.AssertionError: expected:<ParsedTransaction(amount=45.0, currency=EUR, merchant=Lidl, type=PURCHASE,
    confidence=0.92, date=null, transferDirection=null, transferAccountName=null, validationNowEpochMs=1779178767046)>
    but was:<ParsedTransaction(amount=45.0, currency=EUR, merchant=Lidl, type=PURCHASE, confidence=0.92, date=null,
    transferDirection=null, transferAccountName=null, validationNowEpochMs=1779178767048)>
    GreekBankParserStressTest > parses greek deposit notification PASSED
    GreekBankParserStressTest > handles merchant names with special characters PASSED
    GreekBankParserStressTest > rejects non-transaction update message PASSED
    GreekBankParserStressTest > parses eurobank-like charge format PASSED
    GreekBankParserStressTest > parses transfer notification and sets direction PASSED
    GreekBankParserStressTest > supports single decimal in deposit and transfer formats PASSED
    GreekBankParserStressTest > parses purchase notification PASSED
    PriceProtectionTrackerTest > getCreditCardBenefits returns gas cashback for gas stations PASSED
    TransactionLifecycleDbContractTest > create manual expense and verify DB state PASSED
    RecommendationDeduplicatorTest > deduplicate handles null filter JSON PASSED
    RecommendationDeduplicatorTest > deduplicate removes exact duplicate recommendations PASSED
    RecommendationDeduplicatorTest > deduplicate handles single item PASSED
    RecommendationDeduplicatorTest > deduplicate ignores category when filter target is otherwise identical PASSED
    RecommendationDeduplicatorTest > deduplicate preserves different date ranges FAILED
        java.lang.AssertionError: Should keep all 3 different date ranges expected:<3> but was:<1>
    RecommendationDeduplicatorTest > deduplicate handles empty list PASSED
    RecommendationDeduplicatorTest > deduplicate preserves different categories PASSED
    RecommendationDeduplicatorTest > deduplicate preserves different navigation targets PASSED
    RecommendationDeduplicatorTest > deduplicate handles complex filter criteria PASSED
    RecommendationDeduplicatorTest > deduplicate respects priority order when duplicates exist PASSED
    RecommendationDeduplicatorTest > deduplicate preserves different merchants PASSED
    RecurringBillPaymentMatchTest > reminders suppressed after occurrence claim PASSED
    PriceProtectionTrackerTest > getCreditCardBenefits returns grocery cashback for supermarkets FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PriceProtectionTrackerTest > getPriceProtectedItems returns eligible items PASSED
    PriceProtectionTrackerTest > getReturnWindow returns correct days for different merchants PASSED
    PriceProtectionTrackerTest > getCreditCardBenefits returns empty for unknown merchants PASSED
    PriceProtectionTrackerTest > monitorPriceDrops ignores small price drops PASSED
    PriceProtectionTrackerTest > findBetterDeals returns empty for low priced items PASSED
    PriceProtectionTrackerTest > findCoupons returns coupons for merchant PASSED
    WarrantyExpirationWorkerTest > worker reconciles expired items before notifications PASSED
    PriceProtectionTrackerTest > isPriceProtectable returns false for non-protectable items PASSED
    PriceProtectionTrackerTest > isEligibleForPriceProtection returns true for recent purchases PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-3"
    > Task :app:testDebugUnitTest
    PriceProtectionTrackerTest > price drop alert contains correct savings information PASSED
    PriceProtectionTrackerTest > isPriceProtectable identifies electronics correctly PASSED
    WarrantyExpirationWorkerTest > no expiring warranties sends no notification PASSED
    InvestmentDaoTest > aggregate functions return null when no active investments PASSED
    PriceProtectionTrackerTest > getCreditCardBenefits returns dining cashback for restaurants PASSED
    PriceProtectionTrackerTest > findBetterDeals returns deals with 10 percent plus savings PASSED
    PriceProtectionTrackerTest > getCreditCardBenefits returns purchase protection for high value items PASSED
    PriceProtectionTrackerTest > credit card benefit calculates correct cashback value PASSED
    WarrantyExpirationWorkerTest > expiring warranty triggers notification PASSED
    PriceProtectionTrackerTest > isEligibleForPriceProtection returns false for old purchases FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PriceProtectionTrackerTest > getPriceProtectedItems filters recent receipts PASSED
    PriceProtectionTrackerTest > monitorPriceDrops emits alerts for price drops over 5 percent PASSED
    WarrantyExpirationWorkerTest > worker returns success result PASSED
    PrivacyCapabilityHandlingPolicyTest > every PrivacyCapability has an explicit handling policy FAILED
        java.lang.AssertionError: New PrivacyCapability values without explicit policy (fail-open risk):
    [EXPENSE_EXPORT, EXPENSE_EXPORT_RAW, EXPENSE_EXPORT_REDACTED, EXPENSE_EXPORT_ENCRYPTED, DEBUG_RAW_EXPORT,
    RAW_DATABASE_EXPORT]. Add them to policyMap in this test with GATE_HANDLED or LOCAL_ONLY.
    PrivacyCapabilityHandlingPolicyTest > gate-handled capabilities are majority PASSED
    PrivacyCapabilityHandlingPolicyTest > policy map covers all enum values FAILED
        java.lang.AssertionError: Policy map size must match PrivacyCapability.entries size expected:<26> but was:<20>
    WarrantyExpirationWorkerTest > worker propagates CancellationException instead of returning retry PASSED
    WarrantyExpirationWorkerTest > worker handles exception gracefully FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BankStatementParserTest > nbg transaction row with credit marker is parsed as DEPOSIT FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut promo credit row is classified as DEPOSIT FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut Transfer from row is classified as TRANSFER FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > generic row with transaction amount and larger running balance selects transaction amount
    FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > header with transaction date before value date keeps first date as transaction date FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut normal merchant spend remains PURCHASE FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut Top-up row is classified as DEPOSIT FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > parse multiple transactions from spatial blocks FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut ATM withdrawal row is classified as WITHDRAWAL FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut Transfer to row is classified as TRANSFER FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > nbg transaction row with debit marker is parsed correctly FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut GBP amount parsed correctly FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > group blocks into rows correctly even with slight vertical variation FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut cash withdrawal row is classified as WITHDRAWAL FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut Received from row is classified as TRANSFER FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > header keyword order determines which date column is the transaction date FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut grouped amount with European thousands separator is parsed correctly FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut grouped amount with US thousands separator is parsed correctly FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    BankStatementParserTest > revolut refund row is classified as DEPOSIT FAILED
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    ReceiptParserTest > testFuzzyMatching PASSED
    ReceiptParserTest > testExactHallucinationMap PASSED
    ReceiptParserTest > testLatinIntrusion PASSED
    ReceiptParserTest > test decimal parsing - US with thousands separator PASSED
    ReceiptParserTest > test greek normalization - 2 error PASSED
    ReceiptParserTest > test greek normalization - Cash keyword PASSED
    ReceiptParserTest > test greek normalization - Sigma error PASSED
    ReceiptParserTest > test greek normalization - Lambda error PASSED
    ReceiptParserTest > test year range expansion FAILED
        java.lang.AssertionError
    ReceiptParserTest > test greek normalization - Z error PASSED
    ReceiptParserTest > test decimal parsing - US standard PASSED
    ReceiptParserTest > test date ocr fix - 16-D4-2017 FAILED
        java.lang.AssertionError
    ReceiptParserTest > test decimal parsing - standard european PASSED
    ReceiptParserTest > test greek normalization - Payable variant PASSED
    ReceiptParserTest > quantity formatted line is not emitted twice when overlapping patterns match PASSED
    ReceiptParserTest > test total extraction fallback PASSED
    ReceiptParserTest > test decimal parsing - european with thousands separator PASSED
    ReceiptParserTest > testGeometricArtifacts PASSED
    ReceiptParserTest > test merchant extraction - skip noise PASSED
    ReceiptParserTest > test complex ocr number fix PASSED
    RecurringBillPaymentMatchTest > already claimed occurrence rejects second claim PASSED
    AutomatedSavingsRuleEngineGoldenTest > round up golden case 17 30 to nearest 5 produces savings amount 2 70 PASSED
    FeatureConfigNavigationContractTest > main tabs are not in feature config PASSED
    FeatureConfigNavigationContractTest > all feature destinations restore from token PASSED
    FeatureConfigNavigationContractTest > all feature destinations serialize to non-blank token PASSED
    FeatureConfigNavigationContractTest > route token round-trip preserves destination type PASSED
    FeatureConfigNavigationContractTest > no duplicate destinations in feature list PASSED
    FeatureConfigNavigationContractTest > all feature IDs are unique PASSED
    FeatureConfigNavigationContractTest > feature count matches expected PASSED
    WorkerRestoreBarrierIdempotencyGoldenTest > write barrier is idempotent and blocks all worker operations during
    restore FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TaxEstimatorTest > business deductions use only PURCHASE-filtered aggregate FAILED
        java.lang.AssertionError: Expected 300.0 ±0.01, but was 0.0 (diff: 300.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TaxEstimatorTest > estimateTaxes calculates period aligned tax with business deductions FAILED
        java.lang.AssertionError: Expected 500.0 ±0.01, but was 0.0 (diff: 500.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AdvancedAnalyticsViewModelTest > uiState exposes latest rate timestamp from settings FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AdvancedAnalyticsViewModelTest > uiState reloads when home currency changes FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CarbonFootprintScreenTest > resolveCarbonFootprintContentState returns full screen error when no report exists
    PASSED
    CarbonFootprintScreenTest > resolveCarbonFootprintContentState returns loading when first load is in progress PASSED
    CarbonFootprintScreenTest > resolveCarbonFootprintContentState keeps content visible when stale report exists with
    error PASSED
    TaxEstimatorTest > getTaxYearSummary merges null-category remainder into explicit Uncategorized total FAILED
        java.lang.AssertionError: Expected 200.0 ±0.01, but was 0.0 (diff: 200.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TaxEstimatorTest > non-business purchases do not affect VAT estimate FAILED
        java.lang.AssertionError: Expected 193.54838709677418 ±0.01, but was 0.0 (diff: 193.54838709677418)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TaxEstimatorTest > estimateTaxes returns correct date range PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-14"
    > Task :app:testDebugUnitTest
    TaxEstimatorTest > effectiveTaxRate is zero when income is zero PASSED
    TaxEstimatorTest > estimateTaxes with zero business spending returns zero VAT PASSED
    TaxEstimatorTest > estimateTaxes aligns income to requested monthly period PASSED
    TaxEstimatorTest > estimateTaxes with US config returns zero VAT PASSED
    TaxEstimatorTest > getTaxYearSummary uses real yearly income and categorizes business deductions FAILED
        java.lang.AssertionError: Expected 42000.0 ±0.01, but was 0.0 (diff: 42000.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TaxEstimatorTest > estimateTaxes keeps low income entirely in lowest bracket for full year PASSED
    HomeViewModelRecommendationTest > recommendations StateFlow emits up to 5 recommendations PASSED
    TaxEstimatorTest > estimateTaxes applies progressive brackets cumulatively for full year PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-1"
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    HomeViewModelRecommendationTest > dismissRecommendation calls dismissal handler PASSED
    TaxEstimatorTest > estimateTaxes uses business-only deductible total for VAT calculation FAILED
        java.lang.AssertionError: Expected 240.0 ±0.01, but was 0.0 (diff: 240.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InvestmentDaoTest > update investment modifies fields PASSED
    TaxEstimatorTest > estimateTaxes includes correct country code in notes PASSED
    HomeViewModelRecommendationTest > recommendations flow updates on state manager changes PASSED
    HomeViewModelRecommendationTest > recommendations StateFlow handles priority ordering PASSED
    HomeViewModelRecommendationTest > dismissRecommendation removes from selected if currently selected PASSED
    HomeViewModelRecommendationTest > dismissRecommendation handles LOW priority recommendations PASSED
    DetectDuplicateExpenseUseCaseTest > time window boundary check - delegates to getDuplicateCandidatesInWindow with
    correct window PASSED
    HomeViewModelRecommendationTest > dismissRecommendation handles multiple dismissals PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-17"
    > Task :app:testDebugUnitTest
    DetectDuplicateExpenseUseCaseTest > duplicate detected same merchant amount date PASSED
    HomeViewModelRecommendationTest > recommendations flow handles rapid updates PASSED
    DetectDuplicateExpenseUseCaseTest > empty expense list no duplicates PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation with invalid JSON filter falls back gracefully PASSED
    DetectDuplicateExpenseUseCaseTest > no duplicate returns empty PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation resolves BUDGET_DETAIL target PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation sets selected recommendation PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation resolves ANALYTICS target PASSED
    DetectDuplicateExpenseUseCaseTest > explicit currency and transaction type are forwarded to
    getDuplicateCandidatesInWindow PASSED
    AmountUtilsTest > parseAmount - valid european grouped formats parse correctly PASSED
    AmountUtilsTest > parseAmount - invalid formats return null FAILED
        java.lang.AssertionError: expected null, but was:<12.34>
    AmountUtilsTest > parseAmount - handles negative amounts PASSED
    AmountUtilsTest > parseAmount - handles currency symbols PASSED
    AmountUtilsTest > isValidAmount - validates correctly PASSED
    AmountUtilsTest > parseAmount - valid formats parse correctly PASSED
    HomeViewModelRecommendationTest > recommendations StateFlow emits initial empty list PASSED
    NotificationIdGeneratorTest > warranty with days above 7 uses 30-day range PASSED
    NotificationIdGeneratorTest > ID one past range boundary wraps PASSED
    NotificationIdGeneratorTest > toNotificationId extension for budget PASSED
    NotificationIdGeneratorTest > receipt notification for large ID stays in range PASSED
    NotificationIdGeneratorTest > toNotificationId extension for receipt PASSED
    NotificationIdGeneratorTest > toNotificationId extension for warranty 7 days PASSED
    NotificationIdGeneratorTest > receipt notification is in correct range PASSED
    NotificationIdGeneratorTest > warranty 30-day notification is in correct range PASSED
    NotificationIdGeneratorTest > fromLong with custom range PASSED
    NotificationIdGeneratorTest > multiple different IDs are distributed across range PASSED
    NotificationIdGeneratorTest > budget notification for large ID wraps correctly PASSED
    NotificationIdGeneratorTest > ID at range boundary maps correctly PASSED
    NotificationIdGeneratorTest > warranty ranges stay fully below receipt range PASSED
    NotificationIdGeneratorTest > different ranges prevent collision between types PASSED
    NotificationIdGeneratorTest > toNotificationId extension for bill PASSED
    NotificationIdGeneratorTest > budget notification is in correct range PASSED
    NotificationIdGeneratorTest > same database ID in different ranges produces different notification IDs PASSED
    NotificationIdGeneratorTest > receipt notification produces consistent results PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation handles empty filter criteria PASSED
    NotificationIdGeneratorTest > bill notification handles max Long value PASSED
    NotificationIdGeneratorTest > warranty 7-day notification is in correct range PASSED
    NotificationIdGeneratorTest > warranty notification handles zero ID PASSED
    NotificationIdGeneratorTest > all notification types have non-overlapping ranges PASSED
    NotificationIdGeneratorTest > bill notification is in correct range PASSED
    NotificationIdGeneratorTest > general notification is in correct range PASSED
    NotificationIdGeneratorTest > warranty notification handles very large ID PASSED
    NotificationIdGeneratorTest > fromLong produces stable results for same input PASSED
    NotificationIdGeneratorTest > warranty 30-day notification handles very large ID PASSED
    NotificationIdGeneratorTest > fromLong with very large value uses hash mixing PASSED
    NotificationIdGeneratorTest > toNotificationId extension for general PASSED
    NotificationIdGeneratorTest > budget notification for ID 1 gives low number PASSED
    NotificationIdGeneratorTest > warranty with days below 7 uses 7-day range PASSED
    NotificationIdGeneratorTest > warranty notifications for same ID have different ranges by days PASSED
    NotificationIdGeneratorTest > toNotificationId extension for warranty 30 days PASSED
    NotificationIdGeneratorTest > wrapping prevents ID overflow PASSED
    NotificationIdGeneratorTest > fromLong produces different IDs for different inputs PASSED
    HomeViewModelRecommendationTest > recommendations StateFlow filters out expired recommendations PASSED
    WidgetStyleRepositoryTest > toggleWidgetStyle returns correct style after successful toggle PASSED
    HomeViewModelRecommendationTest > init loads recommendations for default user PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation handles null filter criteria PASSED
    WidgetStyleRepositoryTest > toggleWidgetStyle falls back to MODERN when update fails PASSED
    HomeViewModelRecommendationTest > recommendations StateFlow emits updated list PASSED
    HomeViewModelRecommendationTest > dismissRecommendation handles HIGH priority recommendations PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation emits navigation action PASSED
    HomeViewModelRecommendationTest > selectedRecommendation can be updated PASSED
    HomeViewModelRecommendationTest > dismissRecommendation triggers refresh for user PASSED
    HomeViewModelRecommendationTest > selectedRecommendation starts as null PASSED
    HomeViewModelRecommendationTest > navigateToRecommendation resolves MAP target PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    DashboardWidgetConsistencyTest > consistency - FinancialRunway committed and likely from SynthesisEngine FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PriceProtectionViewModelTest > loadData fetches protected items PASSED
    PriceProtectionViewModelTest > price drops contain savings information PASSED
    DashboardWidgetConsistencyTest > consistency - totalSpent in CompiledDashboardData matches monthSpent FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PriceProtectionViewModelTest > loadData handles errors gracefully STANDARD_ERROR
        java.lang.RuntimeException: Network error
                at com.yourname.expensetracker.ui.screens.price.PriceProtectionViewModelTest$loadData handles errors
    gracefully$1.invokeSuspend(PriceProtectionViewModelTest.kt:122)
                at com.yourname.expensetracker.ui.screens.price.PriceProtectionViewModelTest$loadData handles errors
    gracefully$1.invoke(PriceProtectionViewModelTest.kt)
                at com.yourname.expensetracker.ui.screens.price.PriceProtectionViewModelTest$loadData handles errors
    gracefully$1.invoke(PriceProtectionViewModelTest.kt)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                at com.yourname.expensetracker.ui.screens.price.PriceProtectionViewModelTest.loadData handles errors
    gracefully(PriceProtectionViewModelTest.kt:121)
    DashboardWidgetConsistencyTest > consistency - PeriodSummary monthSpent matches purchases effectiveAmount sum FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PriceProtectionViewModelTest > loadData handles errors gracefully PASSED
    DashboardWidgetConsistencyTest > consistency - SafeToSpend and FinancialRunway use same discretionaryBudget FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PriceProtectionViewModelTest > isLoading is true during loadData PASSED
    PriceProtectionViewModelTest > refreshPriceDrops updates price drops state PASSED
    PriceProtectionViewModelTest > protected items are sorted by eligibility PASSED
    InvestmentDaoTest > getTotalInvestedAmount returns sum of purchasePrice times quantity PASSED
    PriceProtectionViewModelTest > initial loading state is false PASSED
    PriceProtectionViewModelTest > loadData monitors price drops PASSED
    InvestmentDaoTest > getAllActiveInvestments returns only active investments PASSED
    PriceProtectionViewModelTest > initial state has empty lists PASSED
    ReviewScreenTransferDirectionParserTest > parseTransferDirectionOrNull returns null for blank input PASSED
    ReviewScreenTransferDirectionParserTest > parseTransferDirectionOrNull returns enum for valid value PASSED
    ReviewScreenTransferDirectionParserTest > parseTransferDirectionOrNull returns null for invalid value PASSED
    CrossGroupIntegrationTest > shared expenses counted correctly in monthly totals STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-12"
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-10"
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-13"
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    > Task :app:testDebugUnitTest
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    CrossGroupIntegrationTest > shared expenses counted correctly in monthly totals PASSED
    CrossGroupIntegrationTest > half_open_interval_enforced_at_all_analytics_entry_points STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-6"
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-11"
    > Task :app:testDebugUnitTest
    CrossGroupIntegrationTest > half_open_interval_enforced_at_all_analytics_entry_points PASSED
    CrossGroupIntegrationTest > carbon footprint by category matches category spending totals STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossGroupIntegrationTest > carbon footprint by category matches category spending totals PASSED
    CrossGroupIntegrationTest > budget forecast uses correct historical data and produces realistic prediction
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossGroupIntegrationTest > budget forecast uses correct historical data and produces realistic prediction FAILED
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:169)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossGroupIntegrationTest > lifestyle inflation correlates with spending pace changes STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossGroupIntegrationTest > lifestyle inflation correlates with spending pace changes PASSED
    CrossGroupIntegrationTest > anomaly detection uses same transaction data as insights engine STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    InvestmentDaoTest > getTotalPortfolioValue returns sum of currentPrice times quantity PASSED
    CrossGroupIntegrationTest > anomaly detection uses same transaction data as insights engine PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    CrossGroupIntegrationTest > all integration paths handle empty dataset gracefully STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossGroupIntegrationTest > all integration paths handle empty dataset gracefully PASSED
    CrossGroupIntegrationTest > complete month analysis produces consistent results across all engines STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    BackupRestoreContractTest > backupBundle readHeaderFromStream consumes correct bytes PASSED
    CrossGroupIntegrationTest > complete month analysis produces consistent results across all engines FAILED
        java.lang.AssertionError: Expected 474.0 ±0.01, but was 0.0 (diff: 474.0)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossGroupIntegrationTest > synthesis engine feeds correctly into dashboard widgets STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-7"
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
    "DefaultDispatcher-worker-2"
    > Task :app:testDebugUnitTest
    BackupRestoreContractTest > restoreMaintenanceMode reset restores writes PASSED
    Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "Test worker"
    > Task :app:testDebugUnitTest
    BudgetThresholdAlertE2ETest > budget thresholds computed correctly as expenses accumulate STANDARD_ERROR
    InvestmentDaoTest > getByType returns active investments of a given type PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    DailyAverageFlowTest > daily average uses periodDays not only daysWithSpending FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    NotificationExpenseDashboardPipelineTest > empty notification ignored and dashboard total remains golden
    STANDARD_ERROR
        Caused by: java.lang.IllegalStateException: Module with the Main dispatcher had failed to initialize. For tests
    Dispatchers.setMain from kotlinx-coroutines-test module can be used
                at
    kotlinx.coroutines.test.internal.TestMainDispatcher$Companion.getCurrentTestScheduler$kotlinx_coroutines_test(TestMa
    inDispatcher.kt:50)
                at kotlinx.coroutines.test.TestScopeKt.withDelaySkipping(TestScope.kt:196)
                at kotlinx.coroutines.test.TestScopeKt.TestScope(TestScope.kt:163)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossGroupIntegrationTest > synthesis engine feeds correctly into dashboard widgets PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    RecurringOccurrenceDaoTest > update status from PLANNED to PAID PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    BackupRestoreContractTest > restoreMaintenanceMode blocks writes when in restore mode PASSED
    NotificationExpenseDashboardPipelineTest > empty notification ignored and dashboard total remains golden FAILED
            at app//kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:238)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BackupRestoreContractTest > backupBundle header too short throws IllegalArgumentException PASSED
    BackupRestoreContractTest > restoreJournal checkAndRecover returns NoAction when clean PASSED
    > Task :app:testDebugUnitTest
    RecurringOccurrenceDaoTest > insert an occurrence PASSED
    BackupRestoreContractTest > backupVerifier tier returns TIER_3_OPTIONAL for optional tables PASSED
    BudgetThresholdAlertE2ETest > budget thresholds computed correctly as expenses accumulate PASSED
    RecurringOccurrenceDaoTest > getByStatus returns occurrences with matching status PASSED
    BackupRestoreContractTest > restoreMaintenanceMode all restore modes block writes PASSED
    RecurringOccurrenceDaoTest > getById returns occurrence PASSED
    BackupRestoreContractTest > backupBundle valid header returns remaining ciphertext PASSED
    BackupRestoreContractTest > restoreJournal initial state is clean STANDARD_ERROR
    BackupRestoreContractTest > restoreJournal initial state is clean PASSED
    MonthlyTotalFlowTest > monthly total flows unchanged through dao repository engine viewModel and ui STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline(FlowPipelineTestHarness.kt:108)
                at
    com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline$default(FlowPipelineTestHarness.kt:75)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BackupRestoreContractTest > backupVerifier includes known core tables PASSED
    RecurringOccurrenceDaoTest > update occurrence entity via update PASSED
    BackupRestoreContractTest > restoreJournal beginJournal creates a journal entry PASSED
    BackupRestoreContractTest > backupVerifier tier defaults to TIER_3_OPTIONAL for unknown tables PASSED
    BackupRestoreContractTest > backupVerifier tier returns TIER_2_VALIDITY for derived tables PASSED
    RecurringOccurrenceDaoTest > ordering by dueDate asc for getBySource PASSED
    BackupRestoreContractTest > restoreJournal transitionTo updates state PASSED
    BackupRestoreContractTest > backupVerifier tier returns TIER_1_EXACT for core tables PASSED
    RecurringOccurrenceDaoTest > getById returns null for non-existent id PASSED
    BackupRestoreContractTest > restoreMaintenanceMode exit without force restores writes PASSED
    BackupRestoreContractTest > restoreJournal failJournal clears the journal file PASSED
    RecurringOccurrenceDaoTest > getByDateRange returns occurrences within range FAILED
        java.lang.AssertionError: expected:<3> but was:<2>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BackupRestoreContractTest > backupBundle invalid magic throws InvalidBackupFormatException PASSED
    BackupRestoreContractTest > restoreMaintenanceMode exit with force keeps writes blocked PASSED
    RecurringOccurrenceDaoTest > query by source returns empty list for unknown source PASSED
    BackupRestoreContractTest > backupBundle unsupported version throws UnsupportedBackupVersionException PASSED
    BackupRestoreContractTest > restoreMaintenanceMode allows writes in normal and backup modes FAILED
        java.lang.AssertionError: Writes should be allowed in BACKUP_EXPORTING mode
    BackupRestoreContractTest > restoreJournal commitJournal clears the journal file STANDARD_ERROR
    RecurringOccurrenceDaoTest > getByKey returns occurrence by unique key PASSED
    BackupRestoreContractTest > restoreJournal commitJournal clears the journal file PASSED
    BackupRestoreContractTest > backupVerifier reports 56 table names PASSED
    RecurringOccurrenceDaoTest > query by source type and id PASSED
    EmailReceiptPipelineScenarioTest > email receipt queryable by fingerprint PASSED
    RecurringOccurrenceDaoTest > getByDateRange excludes out-of-range occurrences PASSED
    EmailReceiptPipelineScenarioTest > email receipt source inserted and queryable PASSED
    RecurringOccurrenceDaoTest > update only targets specified ids PASSED
    EmailReceiptPipelineScenarioTest > email receipt deduplication by message ID via insertOrIgnore PASSED
    RecurringOccurrenceDaoTest > insert multiple occurrences via insertAll PASSED
    ExpenseWithCategoryFormattedAmountTest > shared expense with myShareAmount uses that amount PASSED
    ExpenseWithCategoryFormattedAmountTest > currency code appears before the numeric value PASSED
    ExpenseWithCategoryFormattedAmountTest > non-EUR currency is rendered correctly PASSED
    ExpenseWithCategoryFormattedAmountTest > DEPOSIT gets plus prefix PASSED
    ExpenseWithCategoryFormattedAmountTest > shared expense with mySharePercentage uses proportional amount PASSED
    ExpenseWithCategoryFormattedAmountTest > TRANSFER gets no prefix PASSED
    ExpenseWithCategoryFormattedAmountTest > UNKNOWN gets no prefix PASSED
    ExpenseWithCategoryFormattedAmountTest > WITHDRAWAL gets minus prefix PASSED
    ExpenseWithCategoryFormattedAmountTest > isNotMine expense formats as zero PASSED
    ExpenseWithCategoryFormattedAmountTest > PURCHASE gets minus prefix PASSED
    ExpenseWithCategoryFormattedAmountTest > standard expense uses full amount PASSED
    EmailReceiptParserTest > parseLocalizedAmount handles comma decimal and grouped values PASSED
    EmailReceiptParserTest > cleanHtml preserves meaningful line breaks and decodes entities FAILED
    EmailReceiptParserTest > parseLocalizedDate supports non english month names FAILED
        java.lang.AssertionError: expected:<1773532800000> but was:<1773525600000>
    GeocodingRetryHttpSemanticsTest > google places returns RateLimited after three 429 responses PASSED
    InvestmentGoldenScenarioTest > investment performance has dataQuality per row STANDARD_ERROR
    > Task :app:testDebugUnitTest
    GeocodingRetryHttpSemanticsTest > nominatim returns final 503 response after retries PASSED
    GeocodingRetryHttpSemanticsTest > photon returns RateLimited after three 429 responses PASSED
    InvestmentGoldenScenarioTest > investment performance has dataQuality per row STANDARD_ERROR
    GeocodingRetryHttpSemanticsTest > geoapify returns RateLimited after three 429 responses PASSED
    InvestmentGoldenScenarioTest > investment performance has dataQuality per row PASSED
    GeocodingRetryHttpSemanticsTest > nominatim returns RateLimited after three 429 responses PASSED
    InvestmentGoldenScenarioTest > investmentPortfolioSummaryMoneyAggregate PASSED
    InvestmentGoldenScenarioTest > investmentAddHoldingAtomic PASSED
    MoneyAggregateConversionScenarioTest > mixed currency subscription totals grouped by currency PASSED
    MoneyAggregateConversionScenarioTest > MoneyAmount cross-currency addition throws PASSED
    MoneyAggregateConversionScenarioTest > investment portfolio shows per-currency breakdown FAILED
        java.lang.AssertionError: Should have 1 conversion failure expected:<1> but was:<2>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MoneyAggregateConversionScenarioTest > MoneyAggregate with conversion failures has isPartial=true PASSED
    MoneyAggregateConversionScenarioTest > single currency warranty aggregate returns correct value PASSED
    MoneyAggregateConversionScenarioTest > mixed currency warranty aggregate shows partial when no converter PASSED
    ReceiptPreOcrDedupeScenarioTest > duplicate receipt detected by exact hash skips insert PASSED
    BackupEncryptionServiceTest > decrypt with corrupted ciphertext throws PASSED
    ReceiptPreOcrDedupeScenarioTest > new receipt with unique hash not found PASSED
    TransactionTargetedUpdateSideEffectsTest > category DB state changes after update PASSED
    TransactionTargetedUpdateSideEffectsTest > updateCategory dispatches budget side effect FAILED
        android.database.sqlite.SQLiteException: Cannot prepare statement, base error code: 1
    TransactionTargetedUpdateSideEffectsTest > updateType dispatches side effects STANDARD_ERROR
    BackupEncryptionServiceTest > encrypt then decrypt with binary data PASSED
    TransactionTargetedUpdateSideEffectsTest > updateType dispatches side effects FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    TransactionSideEffectDispatcher(#394).dispatchOnUpdated(eq(1), any(), eq(12fd2e64-d2c1-45d1-a9fc-474b7aed0246),
    any())). Only one matching call to TransactionSideEffectDispatcher(#394)/dispatchOnUpdated(Long, String, String,
    Continuation) happened, but arguments are not matching:

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                 (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                  (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                  (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                          (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                          (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult                             (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                              (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                              (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                      (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TransactionTargetedUpdateSideEffectsTest > updateMerchant dispatches side effects and recurring reconciliation
    FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    TransactionSideEffectDispatcher(#401).dispatchOnUpdated(eq(1), any(), eq(73e255ab-539f-4e57-98b2-98d95bbbc2a4),
    any())). Only one matching call to TransactionSideEffectDispatcher(#401)/dispatchOnUpdated(Long, String, String,
    Continuation) happened, but arguments are not matching:

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                         (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                 (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                     (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                     (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                             (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RecommendationDismissalHandlerTest > dismiss works with high priority recommendation PASSED
    RecommendationDismissalHandlerTest > dismiss works with low priority recommendation PASSED
    RecommendationDismissalHandlerTest > dismiss handles repository errors gracefully PASSED
    RecommendationDismissalHandlerTest > dismiss does not refresh when state removal fails for different current user
    PASSED
    RecommendationDismissalHandlerTest > dismiss continues after IOException PASSED
    RecommendationDismissalHandlerTest > dismissAndRefresh handles errors gracefully PASSED
    RecommendationDismissalHandlerTest > multiple dismissals work independently PASSED
    RecommendationDismissalHandlerTest > dismissAndRefresh can be called multiple times PASSED
    RecommendationDismissalHandlerTest > dismiss handles expired recommendation PASSED
    RecommendationDismissalHandlerTest > dismiss followed by dismissAndRefresh works correctly PASSED
    RecommendationDismissalHandlerTest > dismiss handles recommendation with special characters in id PASSED
    RecommendationDismissalHandlerTest > dismissAndRefresh calls stateManager refreshForUser PASSED
    RecommendationDismissalHandlerTest > dismiss refreshes current user when state removal fails after persistence
    PASSED
    RecommendationDismissalHandlerTest > dismiss handles network timeout errors PASSED
    RecommendationDismissalHandlerTest > dismiss handles already dismissed recommendation PASSED
    RecommendationDismissalHandlerTest > dismiss removes recommendation from state manager PASSED
    RecommendationDismissalHandlerTest > dismiss persists before state update PASSED
    RecommendationDismissalHandlerTest > dismiss handles concurrent calls correctly PASSED
    RecommendationDismissalHandlerTest > dismissAndRefresh works with different user IDs PASSED
    RecommendationDismissalHandlerTest > dismiss continues after IllegalStateException PASSED
    RecommendationDismissalHandlerTest > dismissAndRefresh works with empty user ID PASSED
    RecommendationDismissalHandlerTest > dismiss archives recommendation in repository PASSED
    RecommendationDismissalHandlerTest > dismissAndRefresh continues after repository error PASSED
    NavigationControllerBehaviorTest > feature from Home backs to Home PASSED
    NavigationControllerBehaviorTest > navigateHome clears stack and goes Home PASSED
    NavigationControllerBehaviorTest > feature from Transactions backs to Transactions PASSED
    NavigationControllerBehaviorTest > tab switch changes destination PASSED
    NavigationControllerBehaviorTest > back from Home returns false PASSED
    NavigationControllerBehaviorTest > canNavigateBack true on non-home tab PASSED
    NavigationControllerBehaviorTest > back from non-home tab returns to Home PASSED
    NavigationControllerBehaviorTest > canNavigateBack true on feature screen PASSED
    NavigationControllerBehaviorTest > tab switch clears feature back stack PASSED
    NavigationControllerBehaviorTest > canNavigateBack false on Home PASSED
    NavigationControllerBehaviorTest > invalid tab index falls back to Home PASSED
    NavigationControllerBehaviorTest > initial state is Home with no back PASSED
    NavigationControllerBehaviorTest > feature to feature uses back stack PASSED
    CarbonFootprintViewModelTest > high footprint suggestions shown PASSED
    CarbonFootprintViewModelTest > loadReport failure with blank message sets generic error PASSED
    CarbonFootprintViewModelTest > period change recalculates PASSED
    CarbonFootprintViewModelTest > load failure exposes error when no existing report PASSED
    CarbonFootprintViewModelTest > refresh failure keeps last successful report and sets error PASSED
    CarbonFootprintViewModelTest > empty data zero footprint PASSED
    CarbonFootprintViewModelTest > latest load request wins when stale result completes last PASSED
    CarbonFootprintViewModelTest > initial state shows carbon footprint PASSED
    ReceiptMatchingViewModelTest > match receipt to expense FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:164)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            java.lang.AssertionError
                at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:164)
                at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptMatchingViewModelTest > skip receipt FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:164)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            java.lang.AssertionError
                at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:164)
                at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptMatchingViewModelTest > initial state shows unmatched receipts PASSED
    BackupEncryptionServiceTest > decrypt with wrong password throws PASSED
    ReceiptMatchingViewModelTest > batch match all FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:164)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            java.lang.AssertionError
                at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:164)
                at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReviewViewModelStressTest > stress - clear error message SKIPPED
    ReviewViewModelStressTest > stress - requestDedupeAssist keeps failed artifact diagnostics on error SKIPPED
    ReviewViewModelStressTest > stress - loadAiExplanation sets Error when use case throws SKIPPED
    ReviewViewModelStressTest > stress - loadAiExplanation sets Error when review not found SKIPPED
    ReviewViewModelStressTest > stress - initial error message is null SKIPPED
    ReviewViewModelStressTest > stress - approve review error SKIPPED
    ReviewViewModelStressTest > stress - requestCategoryAssist keeps failed artifact diagnostics on error SKIPPED
    WarrantyTrackerViewModelTest > delete warranty removes from list PASSED
    WarrantyTrackerViewModelTest > auto detected filter chip toggles on and off PASSED
    WarrantyTrackerViewModelTest > add warranty updates list PASSED
    WarrantyTrackerViewModelTest > initial state shows warranties PASSED
    WarrantyTrackerViewModelTest > auto detected filter remains mutually exclusive with other filters PASSED
    WarrantyTrackerViewModelTest > expiring warranties highlighted PASSED
    CrossSourceVerificationTest > spending pace percentage is consistent between insights and calculator STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossSourceVerificationTest > spending pace percentage is consistent between insights and calculator FAILED
        java.lang.AssertionError: Canonical formula vs Insights: Expected 280.0 ±0.01, but was 46.666668 (diff:
    233.33333)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossSourceVerificationTest > monthly total is consistent across repository insights advanced and dashboard
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossSourceVerificationTest > monthly total is consistent across repository insights advanced and dashboard FAILED
        java.lang.AssertionError: Repository vs Advanced Engine: Expected 60.0 ±0.01, but was 0.0 (diff: 60.0)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossSourceVerificationTest > category totals are consistent across repository insights and dashboard STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossSourceVerificationTest > category totals are consistent across repository insights and dashboard FAILED
        java.lang.AssertionError: expected:<[3, 2, 1]> but was:<[]>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossSourceVerificationTest > daily average is consistent across advanced totals-engine and manual calculation
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossSourceVerificationTest > daily average is consistent across advanced totals-engine and manual calculation
    FAILED
        java.lang.AssertionError: Manual vs Advanced: Expected 40.0 ±0.01, but was 0.0 (diff: 40.0)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CrossSourceVerificationTest > transaction count is consistent across repository advanced and totals aggregation
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossSourceVerificationTest > transaction count is consistent across repository advanced and totals aggregation
    PASSED
    CrossSourceVerificationTest > spending pace returns no baseline when previous month has no spending STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CrossSourceVerificationTest > spending pace returns no baseline when previous month has no spending PASSED
    NotificationExpenseDashboardPipelineTest > raw notification parsed and included in dashboard total FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    BackupEncryptionServiceTest > encrypt produces different output each time due to random salt and iv PASSED
    MonthlyTotalFlowTest > monthly total flows unchanged through dao repository engine viewModel and ui FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    SharedExpenseFlowTest > shared expense uses effectiveAmount and excludes notMine across layers STANDARD_ERROR
        Caused by: java.lang.IllegalStateException: Module with the Main dispatcher had failed to initialize. For tests
    Dispatchers.setMain from kotlinx-coroutines-test module can be used
                at
    kotlinx.coroutines.test.internal.TestMainDispatcher$Companion.getCurrentTestScheduler$kotlinx_coroutines_test(TestMa
    inDispatcher.kt:50)
    SharedExpenseFlowTest > shared expense uses effectiveAmount and excludes notMine across layers FAILED
            at app//kotlinx.coroutines.test.TestScopeImpl.enter(TestScope.kt:238)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:309)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ForecastSynthesisGoldenTest > monte carlo produces deterministic forecast with seed 42 PASSED
    NavigationRouteSmokeTest > core navigation destinations can be instantiated PASSED
    RecurringPlannedActualNoDoubleCountGoldenTest > actual payment linked to occurrence counts once in dashboard PASSED
    GuardSeededViolationTest > raw money guard detects sumOf amount in temp file PASSED
    GuardSeededViolationTest > time calls guard detects System currentTimeMillis in temp file PASSED
    GuardSeededViolationTest > guard script detects multiple violations PASSED
    GuardSeededViolationTest > all guard scripts exist and are syntactically valid kotlin scripts PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - isNotMine expense excluded from DayOfWeekAnalyzer PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - isNotMine expense excluded from MonthlyComparisonCalculator
    PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - mixed expenses sum correctly PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - isNotMine expense excluded from SpendingPaceCalculator PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - isNotMine overrides shared PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - normal expense uses full amount PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - shared expense with mySharePercentage uses calculated share
    PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - MonthlyComparisonCalculator uses shared myShareAmount PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - myShareAmount overrides mySharePercentage when both set PASSED
    EffectiveAmountConsistencyTest > effectiveAmount - shared expense with myShareAmount uses share not full amount
    PASSED
    BackupRestoreMoneyIntegrityScenarioTest > schema v120 contains all new table DAOs PASSED
    BackupEncryptionServiceTest > encrypt then decrypt returns original data PASSED
    BackupRestoreMoneyIntegrityScenarioTest > expense seed and query survives roundtrip PASSED
    BackupRestoreMoneyIntegrityScenarioTest > ALL_MIGRATIONS contains 117 to 120 migration steps PASSED
    ExpenseDaoAggregateFilterTest > getBusinessExpensesBetweenByCurrency excludes non-spending types PASSED
    ExpenseDaoAggregateFilterTest > getLocatedMerchantTotalsByCurrency excludes not-mine rows PASSED
    ExpenseDaoAggregateFilterTest > getLocatedMerchantTotalsByCurrency excludes deposits and transfers PASSED
    BudgetRepositoryStressTest > stress - very large budget amount PASSED
    BudgetRepositoryStressTest > large history - category budget uses aggregate query not capped rows PASSED
    BudgetRepositoryStressTest > stress - addBudget with zero startDate fails PASSED
    BudgetRepositoryStressTest > aggregate contract - getTotalSpentFlow is used as invalidation trigger PASSED
    BudgetRepositoryStressTest > stress - very small budget amount PASSED
    ExpenseDaoAggregateFilterTest > getLocatedMerchantTotalsByCurrency excludes null merchantKey PASSED
    BudgetRepositoryStressTest > stress - toggle many budgets PASSED
    BudgetRepositoryStressTest > stress - updateBudget with negative amount fails PASSED
    BudgetRepositoryStressTest > stress - addBudget with negative amount fails PASSED
    BudgetRepositoryStressTest > stress - add many budgets PASSED
    BudgetRepositoryStressTest > large history - whole-wallet budget uses aggregate query not capped rows PASSED
    ExpenseDaoAggregateFilterTest > getLocatedMerchantTotalsByCurrency includes valid spending rows PASSED
    BudgetRepositoryStressTest > stress - addBudget with valid data succeeds PASSED
    BudgetRepositoryStressTest > stress - addBudget with zero amount fails PASSED
    BudgetRepositoryStressTest > large history - rollover across 12 periods uses aggregate queries per window PASSED
    BudgetRepositoryStressTest > stress - updateBudget with zero amount fails PASSED
    BudgetRepositoryStressTest > stress - addBudget with negative startDate fails PASSED
    InvestmentPortfolioScenarioTest > investment entity inserted and queryable PASSED
    CategoryRepositoryTest > learnMerchantCategory delegates to engine path for centralized invalidation PASSED
    InvestmentPortfolioScenarioTest > investment value history inserted and queryable PASSED
    ExpenseRepositoryTruncationTest > getExpensesBetweenFlow returns all rows when history exceeds former 2000-row cap
    PASSED
    InvestmentPortfolioScenarioTest > multi-currency holdings and portfolio totals PASSED
    ExpenseRepositoryTruncationTest > getAllExpenses returns all rows when history exceeds former 2000-row cap PASSED
    MulticurrencyPartialRateScenarioTest > isZero and isPositive work correctly PASSED
    ExpenseRepositoryTruncationTest > getAllExpenses returns all rows when history exceeds former 500-row cap PASSED
    ExpenseRepositoryTruncationTest > getAllExpenses delegates to uncapped DAO flow not bounded getAllFlow PASSED
    ExpenseRepositoryTruncationTest > getExpensesBetween delegates to uncapped DAO method not bounded variant PASSED
    ExpenseRepositoryTruncationTest > createDebugSnapshot uses uncapped getAllUncapped not capped getAll PASSED
    ExpenseRepositoryTruncationTest > getExpensesBetweenFlow delegates to uncapped DAO flow not bounded variant PASSED
    ExpenseRepositoryTruncationTest > getExpensesBetween returns all rows when history exceeds former 2000-row cap
    PASSED
    ExpenseRepositoryTruncationTest > getExpensesBetweenPaged still uses bounded DAO method with explicit limit-offset
    PASSED
    MulticurrencyPartialRateScenarioTest > seed multi-currency expenses and verify source currencies PASSED
    MulticurrencyPartialRateScenarioTest > cross currency addition throws exception PASSED
    MulticurrencyPartialRateScenarioTest > dashboard total is sum of raw amounts (no conversion in seedState) PASSED
    MulticurrencyPartialRateScenarioTest > same currency addition works PASSED
    MulticurrencyPartialRateScenarioTest > money helper creates correct MoneyAmount instances PASSED
    RecurringNoDoubleCountScenarioTest > actual expense does not duplicate planned in dashboard PASSED
    NotificationProcessingPipelineReliabilityTest > pending review duplicate matcher ignores non-pending or amount
    mismatch PASSED
    NotificationProcessingPipelineReliabilityTest > process inserts raw notification when exact duplicate does not exist
    PASSED
    RecurringNoDoubleCountScenarioTest > planned occurrence created and queryable PASSED
    RecurringNoDoubleCountScenarioTest > reminder delivery created for occurrence PASSED
    RecurringNoDoubleCountScenarioTest > occurrence status transitions from PLANNED to PAID PASSED
    RecurringNoDoubleCountScenarioTest > multiple occurrences for same recurring rule PASSED
    NavigationTargetResolverTest > canHandle returns true for lowercase map target PASSED
    NotificationProcessingPipelineReliabilityTest > process remains stable when concurrent subscription detections
    insert same pending candidate PASSED
    NavigationTargetResolverTest > resolve defaults to month period when no dateRange in ANALYTICS PASSED
    NavigationTargetResolverTest > resolve handles empty filter JSON for TRANSACTION_LIST PASSED
    NavigationTargetResolverTest > resolve maps TRANSACTION_LIST to ToTransactionList action PASSED
    NavigationTargetResolverTest > resolve deserializes filter JSON correctly for TRANSACTION_LIST PASSED
    NavigationTargetResolverTest > resolve handles 8 day range as month period (boundary) PASSED
    NavigationTargetResolverTest > canHandle returns true for mixed case target PASSED
    NavigationTargetResolverTest > resolve handles blank filter JSON for TRANSACTION_LIST PASSED
    NavigationTargetResolverTest > resolve handles null filter JSON for TRANSACTION_LIST PASSED
    NavigationTargetResolverTest > resolve maps BUDGET_DETAIL to ToBudgetDetail action PASSED
    NavigationTargetResolverTest > canHandle returns true for CATEGORY_DETAIL target PASSED
    NavigationTargetResolverTest > resolve handles negative dateRange span safely PASSED
    NavigationTargetResolverTest > resolve maps CATEGORY_DETAIL to ToTransactionList action PASSED
    NavigationTargetResolverTest > canHandle returns false for empty string PASSED
    NavigationTargetResolverTest > canHandle returns true for MAP target PASSED
    NavigationTargetResolverTest > resolve handles zero categoryId for BUDGET_DETAIL PASSED
    NavigationTargetResolverTest > canHandle returns false for unknown target PASSED
    NavigationTargetResolverTest > resolve is case insensitive for target matching PASSED
    NavigationTargetResolverTest > resolve maps ANALYTICS to ToAnalytics action with custom period PASSED
    NavigationTargetResolverTest > resolve handles MAP with null location PASSED
    NavigationTargetResolverTest > resolve falls back to ToTransactionList for unknown target PASSED
    NavigationTargetResolverTest > canHandle handles whitespace in target PASSED
    NavigationTargetResolverTest > resolve handles 32 day range as custom period (boundary) PASSED
    NavigationTargetResolverTest > canHandle returns true for BUDGET_DETAIL target PASSED
    NavigationTargetResolverTest > canHandle returns true for ANALYTICS target PASSED
    NavigationTargetResolverTest > resolve maps MAP to ToMap action PASSED
    NavigationTargetResolverTest > resolve uses GENERAL category when categoryId is null for BUDGET_DETAIL PASSED
    NavigationTargetResolverTest > resolve handles complex filter with multiple fields PASSED
    NavigationTargetResolverTest > resolve maps ANALYTICS to ToAnalytics action with month period PASSED
    NavigationTargetResolverTest > resolve falls back gracefully when deserialize returns null PASSED
    NavigationTargetResolverTest > canHandle returns true for TRANSACTION_LIST target PASSED
    NotificationProcessingPipelineReliabilityTest > AUTO_REJECT from non-financial package is NOT salvaged PASSED
    NavigationTargetResolverTest > resolve maps ANALYTICS to ToAnalytics action with week period PASSED
    NotificationProcessingPipelineReliabilityTest > processBatch initializes classifier once and continues on per-item
    failures PASSED
    NotificationProcessingPipelineReliabilityTest > parser-null notification without transaction signals is still
    auto-rejected PASSED
    NotificationProcessingPipelineReliabilityTest > process suppresses exact raw duplicate before insert when schema has
    no unique index PASSED
    NotificationProcessingPipelineReliabilityTest > detectTransactionSignalCandidate returns candidate for normal
    transaction-like text PASSED
    NotificationProcessingPipelineReliabilityTest > windowEndExclusive is date plus window plus 1 - matching ExpenseDao
    convention PASSED
    NotificationProcessingPipelineReliabilityTest > windowEndExclusive with default windowMs uses DUPLICATE_WINDOW_MS
    PASSED
    NotificationProcessingPipelineReliabilityTest > process swallows parser exceptions PASSED
    NotificationProcessingPipelineReliabilityTest > pending review duplicate matcher matches same amount and currency
    PASSED
    NotificationProcessingPipelineReliabilityTest > windowEndExclusive includes exact boundary timestamp under exclusive
    SQL PASSED
    RecommendationLifecycleManagerTest > checkAndExpire can be called multiple times PASSED
    RecommendationLifecycleManagerTest > cleanupExpired handles database constraint violation PASSED
    RecommendationLifecycleManagerTest > checkAndExpire works with different user IDs PASSED
    RecommendationLifecycleManagerTest > checkAndExpire evicts expired items from cache PASSED
    RecommendationLifecycleManagerTest > startPeriodicExpirationCheck starts background coroutine PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles state refresh errors gracefully PASSED
    NotificationProcessingPipelineReliabilityTest > AUTO_REJECT from financial package is salvaged to NEEDS_REVIEW
    PASSED
    RecommendationLifecycleManagerTest > cleanupExpired handles null and non-null user IDs in sequence PASSED
    RecommendationLifecycleManagerTest > startPeriodicExpirationCheck continues after errors PASSED
    RecommendationLifecycleManagerTest > checkAndExpire refreshes state manager PASSED
    RecommendationLifecycleManagerTest > periodic check uses 6 hour interval constant PASSED
    NotificationProcessingPipelineReliabilityTest > parser-null notification with currency and transaction signals
    routes to pending review PASSED
    RecommendationLifecycleManagerTest > startPeriodicExpirationCheck runs cleanup multiple times PASSED
    NotificationProcessingPipelineReliabilityTest > detectTransactionSignalCandidate returns null for non-transaction
    text PASSED
    RecommendationLifecycleManagerTest > cleanupExpired executes in correct order PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles OutOfMemoryError gracefully PASSED
    RecommendationLifecycleManagerTest > cleanupExpired refreshes state when user ID available PASSED
    NotificationProcessingPipelineReliabilityTest > process auto-accept does not treat same merchant-date-amount with
    different currency as duplicate PASSED
    RecommendationLifecycleManagerTest > checkAndExpire executes operations in correct order PASSED
    RecommendationLifecycleManagerTest > cleanupExpired handles getCurrentUserId errors gracefully PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles cache eviction errors gracefully PASSED
    RecommendationLifecycleManagerTest > cleanupExpired evicts expired cache entries PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles IOException from repository PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles repository errors gracefully PASSED
    RecommendationLifecycleManagerTest > checkAndExpire works with empty user ID PASSED
    RecommendationLifecycleManagerTest > cleanupExpired handles repository errors gracefully PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles concurrent calls for same user PASSED
    RecommendationLifecycleManagerTest > checkAndExpire handles concurrent calls for different users PASSED
    RecurringExpenseRepositoryTest > addRecurringExpense uses calculator semantics for annually PASSED
    RecurringExpenseRepositoryTest > addRecurringExpense uses calculator semantics for semi annually PASSED
    RecommendationLifecycleManagerTest > checkAndExpire calls repository expireOld PASSED
    RecurringExpenseRepositoryTest > addRecurringExpense uses calculator semantics for irregular PASSED
    RecommendationLifecycleManagerTest > multiple checkAndExpire calls do not interfere PASSED
    RecommendationLifecycleManagerTest > cleanupExpired can run concurrently with checkAndExpire PASSED
    RecommendationLifecycleManagerTest > startPeriodicExpirationCheck runs cleanup after 6 hours PASSED
    RecommendationLifecycleManagerTest > cleanupExpired calls repository cleanupExpired PASSED
    RecommendationLifecycleManagerTest > startPeriodicExpirationCheck can only be started once PASSED
    RecommendationLifecycleManagerTest > cleanupExpired does not refresh state when user ID is null PASSED
    RecommendationLifecycleManagerTest > cleanupExpired handles cache errors gracefully PASSED
    ContextualActionRegistryTest > unknown screen key returns empty defaults and remains safe on clear PASSED
    ContextualActionRegistryTest > completedActions emits when markCompleted is called PASSED
    > Task :app:testDebugUnitTest
    ContextualActionRegistryTest > clearAll resets registrations and completions PASSED
    ContextualActionRegistryTest > registerActions stores actions sorted by descending priority PASSED
    ContextualActionRegistryTest > duplicate action ids are deduplicated and filtered once completed PASSED
    ContextualActionRegistryTest > markCompleted and getActions track completion per screen key PASSED
    ContextualActionRegistryTest > clearCompleted removes completed state only for given screen PASSED
    NavigationRouteContractTest > all data object destinations roundtrip PASSED
    NavigationRouteContractTest > legacy visual_split_editor colon format without id defaults to creation PASSED
    NavigationRouteContractTest > spending challenges with showCreateDialog false roundtrips PASSED
    NavigationRouteContractTest > legacy visual_split_editor colon format is supported PASSED
    NavigationRouteContractTest > analytics destination with empty period roundtrips PASSED
    NavigationRouteContractTest > budget detail with null fields roundtrips PASSED
    NavigationRouteContractTest > visual split editor for template edit roundtrips PASSED
    NavigationRouteContractTest > spending map with null location roundtrips PASSED
    NavigationRouteContractTest > unknown route fails safely PASSED
    NavigationRouteContractTest > budget forecasting with null budget roundtrips PASSED
    NavigationRouteContractTest > route tokens match expected format PASSED
    NavigationRouteContractTest > parameterized route tokens contain expected base PASSED
    NavigationRouteContractTest > visual split editor for template creation roundtrips PASSED
    NavigationRouteContractTest > budget detail with only categoryId roundtrips PASSED
    NavigationRouteContractTest > malformed tokens return null PASSED
    NavigationRouteContractTest > transactions destination with expenseId roundtrips PASSED
    NavigationRouteContractTest > visual split editor with all fields roundtrips PASSED
    NavigationRouteContractTest > parameterized destinations roundtrip PASSED
    NavigationRouteContractTest > spending challenges with showCreateDialog true roundtrips PASSED
    NavigationRouteContractTest > visual split editor with templateId roundtrips PASSED
    NavigationRouteContractTest > transactions destination with null expenseId roundtrips PASSED
    NavigationRouteContractTest > home destination roundtrips PASSED
    NavigationRouteContractTest > analytics destination with null period roundtrips PASSED
    NavigationRouteContractTest > all parameterized destinations roundtrip PASSED
    NavigationRouteContractTest > budget detail with categoryId and categoryName roundtrips PASSED
    NavigationRouteContractTest > spending map with location roundtrips PASSED
    AndroidNotificationServiceTest > sendAiBriefingReady returns not delivered when notifications are disabled PASSED
    AndroidNotificationServiceTest > sendAiBriefingReady returns delivered after dispatch PASSED
    DurableDiagnosticsRegressionTest > two_diagnostic_events_have_different_event_ids PASSED
    DurableDiagnosticsRegressionTest > restore_diagnostics_sink_marks_room_disabled_after_swap PASSED
    DurableDiagnosticsRegressionTest > create_expense_request_has_optional_correlation_id PASSED
    DurableDiagnosticsRegressionTest > operation_intermediate_event_failure_does_not_fail_business_operation PASSED
    DurableDiagnosticsRegressionTest > diagnostic_event_has_stable_event_id PASSED
    DurableDiagnosticsRegressionTest > restore_journal_event_model_has_required_fields PASSED
    AssistantViewModelTest > openDrilldown emits navigation event PASSED
    AssistantViewModelTest > uiState hides diagnostics when runtime data is missing PASSED
    DurableDiagnosticsRegressionTest > side_effect_failed_event_is_terminal PASSED
    DurableDiagnosticsRegressionTest > metadata_sanitizer_redacts_token_inside_json_array_of_arrays PASSED
    DurableDiagnosticsRegressionTest > side_effect_completed_event_is_terminal PASSED
    DurableDiagnosticsRegressionTest > metadata_known_hash_key_is_allowed PASSED
    DurableDiagnosticsRegressionTest > notification_correlation_id_is_uuid_format PASSED
    DurableDiagnosticsRegressionTest > diagnostic_event_event_id_is_preserved_when_copied PASSED
    AssistantViewModelTest > clearSession with null session does not call repository clearSession PASSED
    DurableDiagnosticsRegressionTest > diagnostic_trace_model_has_restore_journal_events_field PASSED
    DurableDiagnosticsRegressionTest > metadata_sanitizer_redacts_nested_list_content PASSED
    DurableDiagnosticsRegressionTest > metadata_hash_suffix_does_not_override_token_substring PASSED
    DurableDiagnosticsRegressionTest > metadata_raw_text_hash_with_plain_value_is_blocked PASSED
    DurableDiagnosticsRegressionTest > safe_event_metadata_put_blocked_hash_key_is_redacted_not_thrown PASSED
    OnDeviceRuntimePresentationTest > not installed returns helpful message PASSED
    OnDeviceRuntimePresentationTest > available returns null PASSED
    OnDeviceRuntimePresentationTest > unavailable returns runtime guidance PASSED
    OnDeviceRuntimePresentationTest > unsupported android returns version message PASSED
    AssistantViewModelTest > submitQuery handles clarification result PASSED
    AssistantViewModelTest > uiState shows runtime status when on-device model not installed PASSED
    AssistantViewModelTest > concurrent clearSession calls keep state consistent PASSED
    AssistantViewModelTest > submitQuery persists when history enabled PASSED
    AssistantViewModelTest > submitQuery handles unsupported result PASSED
    AssistantViewModelTest > submitQuery adds loading and final summary result PASSED
    DedupeJudgeInputBuilderTest > builder window constant matches DuplicateDetectionPolicy canonical window PASSED
    AssistantViewModelTest > clearSession with active session clears repository session and resets state PASSED
    AssistantViewModelTest > clearSession cancels in-flight query job PASSED
    AssistantViewModelTest > submitQuery ignores blank input PASSED
    AssistantViewModelTest > concurrent clearAllHistory calls keep state consistent PASSED
    AssistantViewModelTest > clearAllHistory cancels in-flight query job PASSED
    DedupeJudgeInputBuilderTest > build returns Ready when exactly one candidate exists PASSED
    AssistantViewModelTest > clearAllHistory clears history and active session PASSED
    DedupeJudgeInputBuilderTest > build excludes pending review candidates with incompatible transaction types PASSED
    AssistantViewModelTest > clarification reply keeps conversation history when history enabled PASSED
    DedupeJudgeInputBuilderTest > build excludes pending review candidates with mismatched currency PASSED
    AssistantViewModelTest > clearSession resets ui state PASSED
    AssistantViewModelTest > uiState hides runtime warning and shows cloud diagnostics when cloud is allowed in auto
    mode PASSED
    DedupeJudgeInputBuilderTest > build returns Ready when multiple nearby candidates exist PASSED
    AssistantViewModelTest > uiState reflects disabled mode when assistant disabled PASSED
    DedupeJudgeInputBuilderTest > build forwards parsed transaction type to candidate query PASSED
    AssistantViewModelTest > submitQuery creates no session and persists nothing when history disabled PASSED
    DedupeJudgeInputBuilderTest > build falls back to UNKNOWN type for invalid suggestedType PASSED
    DedupeJudgeInputBuilderTest > build uses canonical DuplicateDetectionPolicy window via
    getDuplicateCandidatesInWindow PASSED
    CashFlowCalendarViewModelTest > initial state loads month cashflow and upcoming bills PASSED
    CashFlowCalendarViewModelTest > selectDate and changeViewMode update state PASSED
    CashFlowCalendarViewModelTest > setStartingBalance reloads using provided balance PASSED
    CashFlowCalendarViewModelTest > navigate month actions trigger calculator calls FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    CashFlowCalculator(#4297).calculateDailyCashFlow(any(), any(), any(), any())). 2 matching calls found, but needs at
    least 3 calls
                    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)
                                                               kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)
                                                          kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)
                                                                      kotlinx.coroutines.BuildersKt.runBlocking
    (-:1)
                                                          kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)
                                                                      kotlinx.coroutines.BuildersKt.runBlocking$default
    (-:1)
                                                          kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)
                                             kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)
                                                             kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)
                                             kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)
                                                             kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                         (-:1)
                         kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)
                                                                    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)
                                                               kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)
                                                                           kotlinx.coroutines.BuildersKt.runBlocking
    (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                             (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                             (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult                                (TestBuildersJvm.kt:10)
                                                  kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)
                                                                  kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)
                                                  kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)
                                                                  kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                         (-:1)

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle                                (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking                                     (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                     (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default                             (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                             (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult                                (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                 (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                 (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                         (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GenerateTransactionInsightUseCaseTest > invoke redacts merchant and exact amount for redacted cloud mode PASSED
    CashFlowCalendarViewModelTest > loadCashFlow emits loading then loaded state PASSED
    ReceiptAssistInputBuilderTest > build includes local image metadata when image cloud assist is enabled PASSED
    ReceiptAssistInputBuilderTest > build redacts long sensitive numeric values when redaction on PASSED
    ReceiptAssistInputBuilderTest > build keeps contextual receipt fields when redaction off PASSED
    CashFlowCalendarViewModelTest > calculator failure surfaces as coroutine exception after loading state PASSED
    ValidateBankStatementTransactionsUseCaseTest > empty candidates returns empty list PASSED
    ValidateBankStatementTransactionsUseCaseTest > both ai services unavailable returns parser only results PASSED
    ValidateBankStatementTransactionsUseCaseTest > ai corrected merchant name uses AI_CORRECTED source PASSED
    ValidateBankStatementTransactionsUseCaseTest > parseAiResponse with empty json returns empty list PASSED
    ValidateBankStatementTransactionsUseCaseTest > privacy gate denial skips cloud fallback PASSED
    ValidateBankStatementTransactionsUseCaseTest > on device success returns AI validated transactions PASSED
    ValidateBankStatementTransactionsUseCaseTest > parseAiResponse with markdown fences strips formatting PASSED
    ValidateBankStatementTransactionsUseCaseTest > parseAiResponse with wrapped envelope extracts transactions PASSED
    ValidateBankStatementTransactionsUseCaseTest > on device failure falls back to cloud AI PASSED
    AnalyticsCurrencyNormalizerTest > normalizeExpenses keeps same-currency transactions without warnings PASSED
    AnalyticsCurrencyNormalizerTest > normalizeExpenses excludes missing-rate transactions with warning PASSED
    AnalyticsCurrencyNormalizerTest > normalizeExpenses rejects invalid home currency with warning PASSED
    AnalyticsCurrencyNormalizerTest > normalizeExpenses converts foreign currency into home currency FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AnalyticsCurrencyNormalizerTest > normalizeExpenses excludes invalid transaction currency with warning PASSED
    CashFlowCalendarViewModelTest > upcoming bills count reflects repository result FAILED
        app.cash.turbine.TurbineAssertionError: No value produced in 3s
            at app//app.cash.turbine.TurbineAssertionError$Companion.invoke(TurbineAssertionError.kt:32)
    SharedExpenseGroupsScreenStateTest > roundedBalanceForDisplay rounds to requested fraction digits PASSED
    SharedExpenseGroupsScreenStateTest > isSettledBalance treats near-zero values as settled at currency precision
    PASSED
    LifestyleInflationScreenTest > resolveLifestyleInflationContentState keeps content visible when stale report exists
    with error PASSED
    LifestyleInflationScreenTest > calculateMonthlyTrendBarWeights keeps positive segments valid when reference amount
    is zero PASSED
    LifestyleInflationScreenTest > calculateMonthlyTrendBarWeights clamps tiny positive values above zero PASSED
    LifestyleInflationScreenTest > resolveLifestyleInflationContentState returns full screen error when no report exists
    PASSED
    LifestyleInflationScreenTest > resolveLifestyleInflationContentState returns loading when first load is in progress
    PASSED
    LifestyleInflationScreenTest > calculateMonthlyTrendBarWeights skips all zero-value segments PASSED
    ReceiptScanViewModelStressTest > stress - requestReceiptAssist keeps failed artifact diagnostics on error SKIPPED
    ReceiptScanViewModelStressTest > stress - requestReceiptQuickSaveConfirmation builds preview from AI suggestions
    SKIPPED
    ReceiptScanViewModelStressTest > stress - requestCategoryAssist keeps failed artifact diagnostics on error SKIPPED
    InsightsEngineDeepTest > day of week pattern aggregates using effective amount PASSED
    InsightsEngineDeepTest > empty dataset yields safe defaults FAILED
        java.lang.AssertionError
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InsightsEngineDeepTest > spending pace canonical formula and status are correct PASSED
    InsightsEngineDeepTest > top merchants sorted descending and recurrence uses narrow variance PASSED
    InsightsEngineDeepTest > category breakdown groups by category and computes percentage FAILED
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    InsightsEngineDeepTest > average and median transaction size should use effective amount PASSED
    InsightsEngineDeepTest > monthly comparison computes delta and percentage PASSED
    SpendingPaceBoundaryTest > day 1 projection applies conservative bias and projects 5600 FAILED
        java.lang.AssertionError: Expected 5600.0 ±0.1, but was 5048.571428571428 (diff: 551.4285714285716)
    SpendingPaceBoundaryTest > float boundary ratio at 90 percent remains on pace PASSED
    SpendingPaceBoundaryTest > pace_status_boundaries_exactly_90_and_110_are_on_pace PASSED
    SpendingPaceBoundaryTest > zero previous month baseline returns no baseline status and zero pace percentage PASSED
    TotalsAggregationEngineDeepTest > empty and boundary conditions do not crash and keep deterministic outputs PASSED
    TotalsAggregationEngineDeepTest > status determination matches under over and no data PASSED
    TotalsAggregationEngineDeepTest > average monthly weekly and daily formulas are correct FAILED
        java.lang.AssertionError: Expected 200.0 ±0.01, but was 345.0 (diff: 145.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TotalsAggregationEngineDeepTest > category breakdown calculates percentage as category over grand total FAILED
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SavingsGoalsViewModelTest > progress update reflects in UI FAILED
            at kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle(TestScope.kt:95)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SavingsGoalsViewModelTest > initial state shows goals list PASSED
    SavingsGoalsViewModelTest > contributeToGoal uses atomic addToGoalAmount FAILED
            at kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle(TestScope.kt:95)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    TotalsAggregationEngineDeepTest > monthly weekly daily yearly totals map sums from repository PASSED
    SavingsGoalsViewModelTest > empty state when no goals PASSED
    SavingsGoalsViewModelTest > loadGoals uses canonical portfolio recommendations without per goal duplication PASSED
    SavingsGoalsViewModelTest > add goal updates state FAILED
        java.lang.AssertionError: expected:<2> but was:<1>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SavingsGoalsViewModelTest > contributeToGoal with nonexistent goal does not crash PASSED
    BudgetCalculatorBoundaryTest > calculatePeriodWindowForTime with explicit past evaluation time gives correct
    historical window PASSED
    BudgetCalculatorBoundaryTest > rolling yearly budget before anniversary date returns previous cycle PASSED
    BudgetCalculatorBoundaryTest > empty period mode falls back to calendar mode and returns valid monthly range FAILED
    BudgetCalculatorBoundaryTest > calendar yearly budget on Dec 31 returns current year PASSED
    ClipboardAmountParserTest > parseAmountFromClipboard does not partial-tail match grouped amount FAILED
        java.lang.AssertionError: expected:<11,234.56> but was:<null>
    ClipboardAmountParserTest > parseAmountFromClipboard returns null for malformed grouped amount PASSED
    ClipboardAmountParserTest > parseAmountFromClipboard captures grouped amount as whole token FAILED
        java.lang.AssertionError: expected:<1,234.56> but was:<null>
    BudgetCalculatorBoundaryTest > monthly anchor day 30 coerces to Feb 28 in non-leap year PASSED
    BudgetCalculatorBoundaryTest > calendar yearly budget on Jan 1 itself returns full year PASSED
    BudgetCalculatorBoundaryTest > weekly period aligns to anchor weekday and returns monday to monday window PASSED
    BudgetCalculatorBoundaryTest > calendar yearly budget resolves Jan 1 to Jan 1 ignoring mid-year anchor PASSED
    BudgetCalculatorBoundaryTest > convenience calculatePeriodWindow delegates to calculatePeriodWindowForTime with
    timeProvider now PASSED
    BudgetCalculatorBoundaryTest > monthly anchor day 31 coerces february boundary for current cycle calculation PASSED
    BudgetCalculatorBoundaryTest > calculatePeriodWindowForTime with explicit future evaluation time gives correct
    future window PASSED
    BudgetCalculatorBoundaryTest > rolling monthly mode resolves active anchored cycle containing now PASSED
    BudgetCalculatorBoundaryTest > daily period across athens dst spring forward has 23 hour duration PASSED
    BudgetCalculatorBoundaryTest > rolling yearly budget anchored July 1 resolves correct anniversary cycle PASSED
    BudgetCalculatorBoundaryTest > monthly anchor day 30 coerces to Feb 29 in leap year PASSED
    BudgetCalculatorBoundaryTest > monthly leap anchor day 29 coerces to february 28 in non leap year then returns to 29
    PASSED
    GoldenMasterVerificationTest > EDGE CASE - no baseline month yields NO_BASELINE pace and zero projection
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > EDGE CASE - no baseline month yields NO_BASELINE pace and zero projection PASSED
    BudgetMonitorTest > onBackground clears transient state and next foreground check still runs PASSED
    GoldenMasterVerificationTest > VERIFICATION - savings recommendation follows deterministic weighted components
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    BudgetMonitorTest > check budgets sends exceeded notification and updates exceeded timestamp FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#1846).updateExceededNotification(eq(33), eq(1775538000000), any())) was not called.

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                              (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                      (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default
    (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetMonitorTest > check budgets sends warning notification and updates warning timestamp FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#1855).updateWarningNotification(eq(11), eq(1775379600000), any())) was not called.

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                            (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                    (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                        (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                                        (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                                (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    BudgetMonitorTest > check budgets sends critical notification and updates critical timestamp FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1:
    BudgetRepository(#1864).updateCriticalNotification(eq(22), eq(1775455200000), any())) was not called.

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                              (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                                      (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default
    (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > VERIFICATION - savings recommendation follows deterministic weighted components
    PASSED
    GoldenMasterVerificationTest > ERROR PATH - no baseline month yields NO_BASELINE and zero pace STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > ERROR PATH - no baseline month yields NO_BASELINE and zero pace PASSED
    GoldenMasterVerificationTest > VERIFICATION - anomaly detection handles empty dataset gracefully STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > VERIFICATION - anomaly detection handles empty dataset gracefully PASSED
    GoldenMasterVerificationTest > DIVERGENCE - linear pace projection differs from trend forecast projection
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > DIVERGENCE - linear pace projection differs from trend forecast projection FAILED
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:169)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > VERIFICATION - anomaly detection identifies extreme outlier STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > VERIFICATION - anomaly detection identifies extreme outlier PASSED
    GoldenMasterVerificationTest > PARITY - spending pace matches Insights and calculator canonical formula
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > PARITY - spending pace matches Insights and calculator canonical formula PASSED
    GoldenMasterVerificationTest > DIVERGENCE - trend-adjusted forecast differs from linear projection STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CashFlowCalculatorTest > upcoming bills returns patterns within next N days FAILED
        java.lang.AssertionError: expected:<1> but was:<0>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > DIVERGENCE - trend-adjusted forecast differs from linear projection FAILED
            at kotlinx.coroutines.BuildersKt__Builders_commonKt.withContext(Builders.common.kt:169)
            at kotlinx.coroutines.BuildersKt.withContext(Unknown Source)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CashFlowCalculatorTest > A10 Batch5 - transfer without direction and unknown rows do not affect cash-flow balance
    FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > PARITY - monthly total matches Insights Advanced and Totals engines STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CashFlowCalculatorTest > A10 Batch5 - incoming transfer is inflow and outgoing transfer is outflow FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CashFlowCalculatorTest > no expenses path preserves balance and emits no recurring FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > PARITY - monthly total matches Insights Advanced and Totals engines PASSED
    GoldenMasterVerificationTest > PARITY - all analytics engines use effectiveAmount consistently STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > PARITY - all analytics engines use effectiveAmount consistently PASSED
    CashFlowCalculatorTest > A9 regression - cashflow includes all rows beyond old 2000 limit FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > DIVERGENCE - dashboard uses raw amount while analytics engines use effectiveAmount
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > DIVERGENCE - dashboard uses raw amount while analytics engines use effectiveAmount
    PASSED
    CashFlowCalculatorTest > A10 Batch5 - withdrawal counted as outflow for balance FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > PARITY - linear projection matches across Insights and Pace after day 4
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > PARITY - linear projection matches across Insights and Pace after day 4 PASSED
    CashFlowCalculatorTest > A10 Batch5 - negative amount purchase stays on expense side FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > EDGE CASE - empty dataset returns zeroed deterministic analytics STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    CashFlowCalculatorTest > daily cashflow computes starting income expenses recurring and ending balances FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    CashFlowCalculatorTest > no income path handles expense only days and negative balances FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > EDGE CASE - empty dataset returns zeroed deterministic analytics PASSED
    ContextualInferenceEngineTest > detects likely surname - single word PASSED
    ContextualInferenceEngineTest > infers shopping from large amount PASSED
    ContextualInferenceEngineTest > infers food from small amount morning PASSED
    ContextualInferenceEngineTest > returns null for insufficient context PASSED
    ContextualInferenceEngineTest > buildReason includes amount info PASSED
    ContextualInferenceEngineTest > infers food from lunch time PASSED
    ContextualInferenceEngineTest > rejects business names as surnames PASSED
    ContextualInferenceEngineTest > does not classify arbitrary multiword merchant as surname PASSED
    GoldenMasterVerificationTest > PARITY - synthesis projection matches across SynthesisEngine and FinancialWeather
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > PARITY - synthesis projection matches across SynthesisEngine and FinancialWeather
    PASSED
    GoldenMasterVerificationTest > PARITY - anomaly detection uses effectiveAmount for all three methods STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > PARITY - anomaly detection uses effectiveAmount for all three methods PASSED
    GoldenMasterVerificationTest > VERIFICATION - financial runway calculation follows semantic contract STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > VERIFICATION - financial runway calculation follows semantic contract PASSED
    GoldenMasterVerificationTest > PARITY - category totals and percentages match semantic contract map STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    SpendingChallengeManagerTest > create challenge persists reduce spending baseline period FAILED
        java.lang.AssertionError: expected:<1709395200000> but was:<1709330400000>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > PARITY - category totals and percentages match semantic contract map PASSED
    GoldenMasterVerificationTest > EDGE CASE - single transaction honors period-day denominator and boundaries
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > EDGE CASE - single transaction honors period-day denominator and boundaries PASSED
    SpendingChallengeManagerTest > checkNoSpendStreak uses grouped day query instead of day by day expense reads PASSED
    GoldenMasterVerificationTest > PARITY - daily average matches Advanced and Totals historical definitions
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > PARITY - daily average matches Advanced and Totals historical definitions PASSED
    SpendingChallengeManagerTest > budget challenge does not complete immediately when under target PASSED
    SpendingChallengeManagerTest > reduce spending challenge uses stored baseline and completes only at end FAILED
        java.lang.AssertionError
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    GoldenMasterVerificationTest > EDGE CASE - statistical insights handle single transaction correctly STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > EDGE CASE - statistical insights handle single transaction correctly PASSED
    GoldenMasterVerificationTest > DIVERGENCE - Monte Carlo dashboard vs SmartSavings differ by knownUpcoming input
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > DIVERGENCE - Monte Carlo dashboard vs SmartSavings differ by knownUpcoming input
    PASSED
    GoldenMasterVerificationTest > VERIFICATION - spending threshold calculates P90 of last 90 days with min €50
    STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    GoldenMasterVerificationTest > VERIFICATION - spending threshold calculates P90 of last 90 days with min €50 PASSED
    ServiceDiagnosticsTest > recordServiceStart increments start count PASSED
    ServiceDiagnosticsTest > getStats returns consistent snapshot of all counters PASSED
    ServiceDiagnosticsTest > concurrent mixed operations do not lose updates PASSED
    ServiceDiagnosticsTest > recordListenerDisconnected increments disconnect count PASSED
    ServiceDiagnosticsTest > recordServiceKilled updates last kill time PASSED
    ServiceDiagnosticsTest > initial stats are all zero PASSED
    ServiceDiagnosticsTest > recordServiceKilled increments killed count PASSED
    ServiceDiagnosticsTest > concurrent counter increments do not lose updates PASSED
    ServiceDiagnosticsTest > recordServiceStart updates last restart time PASSED
    ServiceDiagnosticsTest > getStats never returns impossible mixed snapshot under contention PASSED
    ServiceDiagnosticsTest > resetStats clears all counters PASSED
    FinancialStressForecastEngineTest > computeStressForecast empty history edge case returns valid horizons PASSED
    FinancialStressForecastEngineTest > computeStressForecast when calculation fails returns degraded non-low fallback
    FAILED
        java.lang.AssertionError: expected null, but was:<1779267600000>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    FinancialStressForecastEngineTest > computeStressForecast uses merged recurring obligations so duplicate stale
    manual rows do not double count PASSED
    FinancialStressForecastEngineTest > computeStressForecast includes zero spend days in discretionary samples FAILED
        java.lang.AssertionError: Expected 0.0 ±1.0E-4, but was -300.0 (diff: 300.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    FinancialStressForecastEngineTest > computeStressForecast includes confirmed recurring obligations PASSED
    FinancialStressForecastEngineTest > computeStressForecast no-data fallback keeps percentile ordering consistent
    PASSED
    FinancialStressForecastEngineTest > computeStressForecast missing income history does not fall back to budget as
    income PASSED
    FinancialStressForecastEngineTest > computeStressForecast recurring-only purchase history keeps Monte Carlo
    discretionary at zero PASSED
    FinancialStressForecastEngineTest > computeStressForecast ignores unconfirmed detected recurring suggestions PASSED
    FinancialStressForecastEngineTest > computeStressForecast includes recurring obligation due earlier today PASSED
    FinancialStressForecastEngineTest > classifyRiskLevel maps probabilities to all five tiers PASSED
    FinancialStressForecastEngineTest > computeStressForecast does not treat current month net cashflow as account
    balance PASSED
    FinancialStressForecastEngineTest > computeStressForecast extreme positive balance drives low risk with no crunch
    date PASSED
    FinancialStressForecastEngineTest > computeStressForecast zero discretionary expenses still produces stable output
    PASSED
    FinancialStressForecastEngineTest > computeStressForecast empirical bootstrap is deterministic for same history
    PASSED
    SettlementCalculatorStressTest > larger balanced case still preserves settlement volume invariant PASSED
    SettlementCalculatorStressTest > all zero balances return empty plan immediately PASSED
    SettlementCalculatorStressTest > 15 member alternating plus minus one completes within solver budget without
    fallback bug B_03 PASSED
    FinancialHealthCalculatorBudgetNormalizationTest > weekly spending target does not double count overall and category
    budgets PASSED
    FinancialHealthCalculatorBudgetNormalizationTest > daily spending target normalizes monthly budget by actual overlap
    PASSED
    FinancialHealthCalculatorBudgetNormalizationTest > monthly spending target sums overlapping mixed budget windows
    PASSED
    ConfidenceRouterEdgeCaseTest > just below review threshold - auto reject STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > just below review threshold - auto reject PASSED
    ConfidenceRouterEdgeCaseTest > sourceStats with zero totalNotifications does not crash STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > sourceStats with zero totalNotifications does not crash PASSED
    ConfidenceRouterEdgeCaseTest > invalid confidence NaN is rejected at construction STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > invalid confidence NaN is rejected at construction PASSED
    ConfidenceRouterEdgeCaseTest > exact threshold boundary - review at exactly 0_50 STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > exact threshold boundary - review at exactly 0_50 PASSED
    ConfidenceRouterEdgeCaseTest > just below auto accept threshold - needs review STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > just below auto accept threshold - needs review PASSED
    ConfidenceRouterEdgeCaseTest > blank merchant name is rejected at construction STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > blank merchant name is rejected at construction PASSED
    ConfidenceRouterEdgeCaseTest > exact threshold boundary - auto accept at exactly 0_85 STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
    ConfidenceRouterEdgeCaseTest > exact threshold boundary - auto accept at exactly 0_85 PASSED
    MerchantNormalizerStressTest > stress - alias match returns USER_DEFINED when user defined PASSED
    MerchantNormalizerStressTest > stress - autoCreate false returns placeholder without insert PASSED
    MerchantNormalizerStressTest > stress - fuzzy match when similar merchant in tree PASSED
    MerchantNormalizerStressTest > stress - whitespace only returns Unknown PASSED
    MerchantNormalizerStressTest > stress - exact canonical match returns EXACT_MATCH PASSED
    MerchantNormalizerStressTest > stress - name over 200 chars truncated no crash PASSED
    MerchantNormalizerStressTest > stress - alias match returns ALIAS_MATCH when not user defined PASSED
    MerchantNormalizerStressTest > stress - new merchant with autoCreate creates canonical PASSED
    MerchantNormalizerStressTest > stress - cleanMerchantName delegates to merchantRules PASSED
    MerchantNormalizerStressTest > stress - empty string returns Unknown PASSED
    MerchantNormalizerStressTest > stress - Greek text produces valid result PASSED
    MerchantNormalizerStressTest > stress - concurrent normalize calls no crash PASSED
    LocationResolverTest > resolve derives cacheKey from rawMerchantName when merchantKey is null PASSED
    LocationResolverTest > resolve uses provided merchantKey as cacheKey for correction lookup PASSED
    RecurringExpenseEngineTest > merchant case variations grouped together PASSED
    RecurringExpenseEngineTest > exactly 3 occurrences minimum threshold PASSED
    RecurringExpenseEngineTest > should ignore recurring candidates marked not mine PASSED
    RecurringExpenseEngineTest > detected stale next date is rolled forward into the future FAILED
        java.lang.AssertionError: expected:<1777626000000> but was:<1777626006603>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RecurringExpenseEngineTest > exactly 2 occurrences should not detect PASSED
    RecurringExpenseEngineTest > manual override should take precedence PASSED
    RecurringExpenseEngineTest > monthly pattern tolerates variable month length PASSED
    RecurringExpenseEngineTest > should detect perfect monthly subscription PASSED
    RecurringExpenseEngineTest > should ignore random coffee purchases PASSED
    RecurringExpenseEngineTest > should detect bi-weekly salary PASSED
    RecurringExpenseEngineTest > manual stale next date is rolled forward into the future PASSED
    RecurringExpenseEngineTest > should ignore variable bills (high amount variance) PASSED
    RecurringPatternModelTest > calendar frequencies expose month semantics without fixed intervals PASSED
    RecurringPatternModelTest > irregular exposes explicit non-interval semantics PASSED
    GenericTransactionParserStressTest > rejects malformed fraction-like amount PASSED
    GenericTransactionParserStressTest > parses incoming deposit signal as deposit type PASSED
    GenericTransactionParserStressTest > does not reject greek deposit messages containing apo PASSED
    GenericTransactionParserStressTest > supports dollar currency normalization PASSED
    GenericTransactionParserStressTest > accepts supported european amount format PASSED
    GenericTransactionParserStressTest > is deterministic across repeated parses FAILED
        java.lang.AssertionError: expected:<ParsedTransaction(amount=44.1, currency=EUR, merchant=Lidl, type=PURCHASE,
    confidence=0.6, date=null, transferDirection=null, transferAccountName=null, validationNowEpochMs=1779178987080)>
    but was:<ParsedTransaction(amount=44.1, currency=EUR, merchant=Lidl, type=PURCHASE, confidence=0.6, date=null,
    transferDirection=null, transferAccountName=null, validationNowEpochMs=1779178987081)>
    GenericTransactionParserStressTest > rejects marketing message despite numbers PASSED
    GenericTransactionParserStressTest > parses generic purchase with amount and merchant PASSED
    GenericTransactionParserStressTest > rejects non-financial notification PASSED
    GenericTransactionParserStressTest > prefers transaction amount when multiple amounts are present PASSED
    GenericTransactionParserStressTest > accepts one decimal place amount with currency adjacent PASSED
    RevolutParserTest > merchant name truncated at 40 chars PASSED
    RevolutParserTest > parse purchase with USD currency PASSED
    RevolutParserTest > reject savings vault notification PASSED
    RevolutParserTest > handle null title and text PASSED
    RevolutParserTest > handle empty strings PASSED
    RevolutParserTest > parse purchase with GBP currency PASSED
    RevolutParserTest > reject exchange rate notification PASSED
    RevolutParserTest > parse sent to person PASSED
    RevolutParserTest > parse standard purchase with euro symbol PASSED
    RevolutParserTest > parse outgoing transfer with EU-format grouped amount PASSED
    RevolutParserTest > only supports revolut package PASSED
    RevolutParserTest > parse purchase with large EU-format grouped amount PASSED
    RevolutParserTest > parse add-money with grouped amount PASSED
    RevolutParserTest > parse outgoing transfer with grouped amount PASSED
    RevolutParserTest > reject security notification PASSED
    RevolutParserTest > parse received money PASSED
    RevolutParserTest > parse purchase with EU-format grouped amount PASSED
    RevolutParserTest > merchant cleaned of trailing punctuation PASSED
    RevolutParserTest > reject special offer PASSED
    RevolutParserTest > parse purchase with comma decimal separator PASSED
    RevolutParserTest > parse purchase with US-format grouped amount PASSED
    RevolutParserTest > parse ATM withdrawal with grouped amount PASSED
    RevolutParserTest > parse incoming transfer with grouped amount PASSED
    RevolutParserTest > parse ATM withdrawal PASSED
    RevolutParserTest > reject weekly report PASSED
    CloudPayloadPolicyTest > prepared_cloud_payload_purpose_is_preserved PASSED
    CloudPayloadPolicyTest > bank_statement_uses_BANK_STATEMENT_VALIDATION_purpose PASSED
    CloudPayloadPolicyTest > bank_statement_always_redacted_even_when_general_policy_allows_raw PASSED
    CloudPayloadPolicyTest > privacy_redact_true_ai_redact_false_redacts_receipt_assist PASSED
    CloudPayloadPolicyTest > privacy_redact_false_ai_redact_true_still_redacts PASSED
    CloudPayloadPolicyTest > audit_metadata_does_not_contain_raw_text PASSED
    CloudPayloadPolicyTest > privacy_redact_true_ai_redact_false_redacts_dashboard PASSED
    CloudPayloadPolicyTest > privacy_redact_false_ai_redact_false_includes_raw_text PASSED
    CloudPayloadPolicyTest > new_cloud_purposes_are_available PASSED
    CloudPayloadPolicyTest > prepared_cloud_payload_has_payload_hash PASSED
    CloudPayloadPolicyTest > receipt_image_upload_suppressed_when_redaction_required PASSED
    RawPersistencePolicyTest > safe_privacy_metadata_put_hash_stores_hash_value PASSED
    RawPersistencePolicyTest > sanitize_email_message_id_with_hash_uses_provided_hash_for_metadata_only PASSED
    RawPersistencePolicyTest > do_not_store_omits_body_and_parsed_items PASSED
    RawPersistencePolicyTest > metadata_only_omits_body_but_keeps_hashes PASSED
    RawPersistencePolicyTest > store_redacted_replaces_body_fields PASSED
    RawPersistencePolicyTest > safe_privacy_metadata_blocked_keys_replaced_with_redacted PASSED
    RawPersistencePolicyTest > sha256_prefix_is_deterministic PASSED
    RawPersistencePolicyTest > safe_privacy_metadata_merge_preserves_safe_keys PASSED
    RawPersistencePolicyTest > safe_privacy_metadata_blocks_raw_sensitive_keys PASSED
    RawPersistencePolicyTest > hash_service_returns_null_for_null_input PASSED
    RawPersistencePolicyTest > sanitize_email_message_id_do_not_store_returns_null PASSED
    RawPersistencePolicyTest > ocr_do_not_store_omits_external_id_hash PASSED
    RawPersistencePolicyTest > store_raw_preserves_allowed_raw_fields PASSED
    RawPersistencePolicyTest > sanitize_email_message_id_metadata_only_returns_null_not_hashCode PASSED
    RawPersistencePolicyTest > hash_service_does_not_use_String_hashCode PASSED
    RawPersistencePolicyTest > email_metadata_only_keeps_external_id_hash_for_dedup PASSED
    RawPersistencePolicyTest > email_message_id_hash_differs_by_purpose PASSED
    RawPersistencePolicyTest > debug_body_only_allowed_when_store_raw_and_debug_enabled PASSED
    RawPersistencePolicyTest > email_message_id_hash_stable_across_calls PASSED
    RawPersistencePolicyTest > notification_do_not_store_keeps_dedupe_hash PASSED
    GreekNormalizationTest > test amount keywords PASSED
    GreekNormalizationTest > test compound keywords PASSED
    GreekNormalizationTest > test total keywords variants PASSED
    GreekNormalizationTest > test currency normalization PASSED
    GreekNormalizationTest > test number fixes PASSED
    ReceiptTransactionMatcherTest > findBestMatch ignores non purchase compatible positive transactions PASSED
    ReceiptTransactionMatcherTest > findBestMatch keeps greek merchants comparable after normalization PASSED
    SmartSavingsEngineTest > portfolio recommendations cap allocation at remaining gap PASSED
    SmartSavingsEngineTest > monte carlo discretionary baseline excludes essential categories PASSED
    SmartSavingsEngineTest > portfolio recommendations allocate one safe amount across multiple goals FAILED
        java.lang.AssertionError: Expected 10.0 ±0.01, but was 6.666666666666666 (diff: 3.333333333333334)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SmartSavingsEngineTest > budget surplus uses overall budget without stacking category budgets PASSED
    SmartSavingsEngineTest > no budgets and no spending history return zero safe amount PASSED
    SmartSavingsEngineTest > safeToSaveAmount combines weighted surplus pace and monteCarlo FAILED
        java.lang.AssertionError: Expected 115.8 ±0.01, but was 60.0 (diff: 55.8)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SmartSavingsEngineTest > very high spending is clamped and never returns negative savings PASSED
    GetMonteCarloBudgetImpactUseCaseTest > boundary probability exactly twenty five percent is MEDIUM PASSED
    GetMonteCarloBudgetImpactUseCaseTest > invoke does not short-circuit on expectedOverrun zero and still uses
    probability PASSED
    GetMonteCarloBudgetImpactUseCaseTest > boundary overrun exactly fifteen percent is HIGH PASSED
    GetMonteCarloBudgetImpactUseCaseTest > boundary overrun exactly thirty percent is CRITICAL PASSED
    GetMonteCarloBudgetImpactUseCaseTest > invoke returns error when probability is null PASSED
    GetMonteCarloBudgetImpactUseCaseTest > boundary overrun exactly five percent is MEDIUM PASSED
    GetMonteCarloBudgetImpactUseCaseTest > invoke returns error when budget is zero PASSED
    GetMonteCarloBudgetImpactUseCaseTest > invoke ignores monte carlo internal budgetAmount null and uses provided
    budget PASSED
    GetMonteCarloBudgetImpactUseCaseTest > invoke classifies LOW when overrun and probability are both below thresholds
    PASSED
    GetMonteCarloBudgetImpactUseCaseTest > boundary probability exactly seventy five percent is CRITICAL PASSED
    GetMonteCarloBudgetImpactUseCaseTest > boundary probability exactly fifty percent is HIGH PASSED
    GetMonteCarloBudgetImpactUseCaseTest > invoke uses BOTH dimensions overrun and probability for tiering PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation edge case no goals returns null PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation edge case no budgets returns null PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation edge case negative underspend returns null PASSED
    MonthlySavingsSweepUseCaseTest > shouldShowSweepPrompt true only in last five days of month PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation passes real upcoming obligations into Monte Carlo PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation caps allocations by remaining goal gap before
    concentration cap PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation allocates proportionally to urgency PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation derives deterministic fallback risk buffer when Monte
    Carlo unavailable FAILED
        java.lang.AssertionError: Expected 30.0 ±0.01, but was 12.0 (diff: 18.0)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation filters invalid goals targetAmount less or equal zero
    preventing NaN PASSED
    MonthlySavingsSweepUseCaseTest > computeSweepRecommendation avoids double counting when overall budget exists PASSED
    MerchantKeyGeneratorStressTest > stress - greek omicron iota PASSED
    MerchantKeyGeneratorStressTest > stress - greek to latin basic PASSED
    MerchantKeyGeneratorStressTest > stress - basic latin lowercase PASSED
    MerchantKeyGeneratorStressTest > stress - punctuation stripped PASSED
    MerchantKeyGeneratorStressTest > stress - greek vowel variations PASSED
    MerchantKeyGeneratorStressTest > stress - mixed case latin PASSED
    MerchantKeyGeneratorStressTest > stress - greek common merchant PASSED
    MerchantKeyGeneratorStressTest > stress - basic latin uppercase PASSED
    MerchantKeyGeneratorStressTest > stress - emoji stripped PASSED
    MerchantKeyGeneratorStressTest > stress - unicode accents removed PASSED
    MerchantKeyGeneratorStressTest > stress - greek uppercase conversion PASSED
    MerchantKeyGeneratorStressTest > stress - greek letter combinations PASSED
    MerchantKeyGeneratorStressTest > stress - blank string returns empty PASSED
    MerchantKeyGeneratorStressTest > stress - spaces stripped PASSED
    MerchantKeyGeneratorStressTest > stress - tabs and newlines stripped PASSED
    MerchantKeyGeneratorStressTest > stress - greek diphthong conversion PASSED
    MerchantKeyGeneratorStressTest > stress - consistency same input produces same output PASSED
    MerchantKeyGeneratorStressTest > stress - multiple emojis stripped PASSED
    MerchantKeyGeneratorStressTest > stress - very long string performance PASSED
    MerchantKeyGeneratorStressTest > stress - numbers preserved PASSED
    MerchantKeyGeneratorStressTest > stress - special symbols stripped PASSED
    MerchantKeyGeneratorStressTest > stress - all greek alphabet lowercase PASSED
    MerchantKeyGeneratorStressTest > stress - empty string returns empty PASSED
    MerchantKeyGeneratorStressTest > stress - long greek string performance PASSED
    MerchantKeyGeneratorStressTest > stress - greek with spaces PASSED
    MerchantKeyGeneratorStressTest > stress - null character stripped PASSED
    MerchantKeyGeneratorStressTest > stress - special characters stripped PASSED
    MerchantKeyGeneratorStressTest > stress - greeklish mixed PASSED
    MerchantKeyGeneratorStressTest > stress - 1000 operations performance PASSED
    TimePeriodUtilsStressTest > stress - consecutive week ranges contiguous across EU DST spring forward PASSED
    TimePeriodUtilsStressTest > stress - different timezone calculations PASSED
    TimePeriodUtilsStressTest > stress - multiple days around DST spring PASSED
    TimePeriodUtilsStressTest > stress - week starting on Sunday PASSED
    TimePeriodUtilsStressTest > regression - getStartOfMonth still works PASSED
    TimePeriodUtilsStressTest > stress - timezone with half hour offset PASSED
    TimePeriodUtilsStressTest > stress - getStartOfQuarter for each quarter PASSED
    TimePeriodUtilsStressTest > stress - negative timestamp before epoch PASSED
    TimePeriodUtilsStressTest > stress - epoch timestamp PASSED
    TimePeriodUtilsStressTest > stress - getWeekRange week end is next Monday PASSED
    TimePeriodUtilsStressTest > stress - daysBetween is DST safe across spring forward PASSED
    TimePeriodUtilsStressTest > stress - days in month for all months in leap year PASSED
    TimePeriodUtilsStressTest > stress - month boundary February to March leap year PASSED
    TimePeriodUtilsStressTest > stress - midnight timestamp PASSED
    TimePeriodUtilsStressTest > stress - consecutive day ranges are contiguous PASSED
    TimePeriodUtilsStressTest > stress - getEndOfYear for different years PASSED
    TimePeriodUtilsStressTest > stress - Monday-start week is locale-independent across timezones PASSED
    TimePeriodUtilsStressTest > stress - getEndOfQuarter for each quarter PASSED
    TimePeriodUtilsStressTest > stress - DST spring forward March 2024 PASSED
    TimePeriodUtilsStressTest > stress - year boundary December 31 to January 1 PASSED
    TimePeriodUtilsStressTest > stress - isInRange month boundary - last ms included, first ms of next month excluded
    PASSED
    TimePeriodUtilsStressTest > stress - end of month for all months PASSED
    TimePeriodUtilsStressTest > stress - fuzz random timestamps PASSED
    TimePeriodUtilsStressTest > stress - month boundary January to February PASSED
    TimePeriodUtilsStressTest > stress - very far future timestamp PASSED
    TimePeriodUtilsStressTest > stress - getEndOfWeek consistent with getWeekRange across multiple weeks PASSED
    TimePeriodUtilsStressTest > stress - getDayIndexFromMonthStart is calendar-based PASSED
    TimePeriodUtilsStressTest > stress - consecutive month ranges are contiguous PASSED
    TimePeriodUtilsStressTest > stress - February 28th non-leap year 2023 PASSED
    TimePeriodUtilsStressTest > stress - getStartOfWeek during DST transition PASSED
    TimePeriodUtilsStressTest > stress - getStartOfWeek for each day of week PASSED
    TimePeriodUtilsStressTest > stress - isInRange consistent with manual half-open check across random timestamps
    PASSED
    TimePeriodUtilsStressTest > stress - month boundary December to January PASSED
    TimePeriodUtilsStressTest > regression - basic getStartOfDay still works PASSED
    TimePeriodUtilsStressTest > stress - addDays is DST safe PASSED
    TimePeriodUtilsStressTest > stress - February 29th leap year 2024 PASSED
    TimePeriodUtilsStressTest > stress - DST fall back October 2024 PASSED
    TimePeriodUtilsStressTest > stress - getLastNDaysRange uses calendar-aware subtraction PASSED
    TimePeriodUtilsStressTest > stress - end of day timestamp PASSED
    TimePeriodUtilsStressTest > stress - consecutive week ranges contiguous across US DST spring forward PASSED
    AnalyticsPipelineTest > extreme merchant outlier is detected as anomaly FAILED
        java.lang.AssertionError
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AnalyticsPipelineTest > golden march expenses flow through insights with expected purchase total PASSED
    AnalyticsPipelineTest > spending pace is correct for march day 15 FAILED
        java.lang.AssertionError: Expected 2049.03 ±0.01, but was 2049.699333333333 (diff: 0.6693333333328155)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AnalyticsPipelineTest > golden march baseline produces no anomalies PASSED
    AnalyticsPipelineTest > category breakdown matches golden grocery dining rent totals and grocery percentage PASSED
    DateBoundaryFlowTest > half open date interval includes start excludes end across flow STANDARD_ERROR
        WARNING: Failed to set backing field (skipping)
                at com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline(FlowPipelineTestHarness.kt:108)
                at
    com.yourname.expensetracker.e2e.FlowPipelineTestHarnessKt.buildPipeline$default(FlowPipelineTestHarness.kt:75)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
                at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
                at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    NotificationExpenseDashboardPipelineTest > parse failure keeps dashboard total at baseline FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    BackupRestoreRoundtripGoldenTest > data integrity preserved across restore mode transitions FAILED
        java.lang.OutOfMemoryError: Java heap space
    HiltGraphSmokeTest > core singletons can be constructed FAILED
        java.lang.OutOfMemoryError: Java heap space
    HiltGraphSmokeTest > database exposes all critical DAOs FAILED
        java.lang.OutOfMemoryError: Java heap space
    PrivacyDoNotStoreTest > STORE_REDACTED persists redacted text FAILED
        java.lang.OutOfMemoryError: Java heap space
    PrivacyDoNotStoreTest > DO_NOT_STORE persists metadata only FAILED
        java.lang.OutOfMemoryError: Java heap space
    PrivacyDoNotStoreTest > DO_NOT_STORE notification has null text fields FAILED
        java.lang.OutOfMemoryError: Java heap space
    RuleDeactivationCleanupTest > deactivate rule sets isActive false FAILED
        java.lang.OutOfMemoryError: Java heap space
    RuleDeactivationCleanupTest > deactivate rule cancels planned occurrences FAILED
        java.lang.OutOfMemoryError: Java heap space
    RuleDeactivationCleanupTest > deactivate rule suppresses open reminders FAILED
        java.lang.OutOfMemoryError: Java heap space
    DateBoundaryFlowTest > half open date interval includes start excludes end across flow FAILED
        kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m, the test body did not run to
    completion
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:354
    )
    > Task :app:testDebugUnitTest
    ReceiptMatchingE2ETest > receipt matches existing expense and links without double count STANDARD_ERROR
    ReceiptMatchingE2ETest > receipt matches existing expense and links without double count PASSED
    BankSyncFailureRecoveryGoldenTest > bank sync failure and recovery lifecycle PASSED
    HomeDashboardFinancialInvariantTest > dashboard total equals category sum equals budget spent PASSED
    PrivacyGateEnforcementGoldenTest > privacy gates deny and audit when settings disabled PASSED
    StaleRateCurrencyConversionGoldenTest > stale rate produces RATE_STALE failure PASSED
    ExpenseCreationPipelineIntegrationTest > integration - fuzzy matching with variations PASSED
    ExpenseCreationPipelineIntegrationTest > integration - recover from missing amount PASSED
    ExpenseCreationPipelineIntegrationTest > integration - handle very long inputs gracefully PASSED
    ExpenseCreationPipelineIntegrationTest > integration - amount validation after parsing PASSED
    ExpenseCreationPipelineIntegrationTest > integration - distance calculation pipeline PASSED
    ExpenseCreationPipelineIntegrationTest > integration - amount parsing with various formats PASSED
    ExpenseCreationPipelineIntegrationTest > integration - Greeklish mixed text PASSED
    ExpenseCreationPipelineIntegrationTest > integration - messy notification cleanup PASSED
    ExpenseCreationPipelineIntegrationTest > integration - process notification data PASSED
    ExpenseCreationPipelineIntegrationTest > integration - Greek notification pipeline PASSED
    ExpenseCreationPipelineIntegrationTest > integration - handle edge case notification PASSED
    ExpenseCreationPipelineIntegrationTest > integration - full Greek pipeline PASSED
    ExpenseCreationPipelineIntegrationTest > integration - recover from null inputs PASSED
    ExpenseCreationPipelineIntegrationTest > integration - same input produces same output PASSED
    ExpenseCreationPipelineIntegrationTest > integration - process 100 notifications quickly PASSED
    ExpenseCreationPipelineIntegrationTest > integration - clean raw notification PASSED
    BootReceiverStressTest > ignores unrelated broadcast action PASSED
    BootReceiverStressTest > starts capture service on package replaced PASSED
    BootReceiverStressTest > does not crash when service start throws PASSED
    BootReceiverStressTest > starts capture service on boot completed PASSED
    CurrencyRateStalenessScenarioTest > current rate within 24h is not stale PASSED
    CurrencyRateStalenessScenarioTest > CurrencyConverter with stale rate falls through to EUR composite PASSED
    CurrencyRateStalenessScenarioTest > stale rate returns conversion failure with STALE_RATE reason PASSED
    CurrencyRateStalenessScenarioTest > rate older than 24h is stale PASSED
    GroupSettlementLifecycleScenarioTest > add expense to group links correctly PASSED
    GroupSettlementLifecycleScenarioTest > create group with members persists correctly PASSED
    GroupSettlementLifecycleScenarioTest > settlement recorded and retrievable PASSED
    MixedCurrencyCoreFinancialScenarioTest > single currency produces clean MoneyAggregate PASSED
    MixedCurrencyCoreFinancialScenarioTest > conversion with stale rate produces RATE_STALE failure reason PASSED
    MixedCurrencyCoreFinancialScenarioTest > multi-currency expenses produce correct MoneyAggregate with partial state
    FAILED
        java.lang.AssertionError: Should have 2 failed transactions expected:<2> but was:<0>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PrivacyGateContractTest > composite gate allows when all gates allow PASSED
    PrivacyGateContractTest > privacy gate allows enabled capability PASSED
    PrivacyGateContractTest > redaction sanitizer removes sensitive data PASSED
    PrivacyGateContractTest > privacy gate denies disabled capability PASSED
    PrivacyGateContractTest > bank statement ai disabled denies bank capabilities PASSED
    PrivacyGateContractTest > unrecognised capability is allowed by cloud ai gate FAILED
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    PrivacyGateContractTest > receipt image upload denied when redactBeforeCloud is on PASSED
    PrivacyGateContractTest > cloud ai gate does not log on its own PASSED
    PrivacyGateContractTest > privacy settings default values are correct PASSED
    PrivacyGateContractTest > composite privacy gate short-circuits on first denial PASSED
    TransactionLifecycleCoordinatorDbContractTest > updateExpense updates row and writes UPDATED event PASSED
    TransactionLifecycleCoordinatorDbContractTest > deleteExpense removes row and writes DELETED event PASSED
    TransactionLifecycleCoordinatorDbContractTest > createExpense duplicate detected and skipped PASSED
    *** java.lang.instrument ASSERTION FAILED ***: "!errorOutstanding" with message can't create name string at
    s\open\src\java.instrument\share\native\libinstrument\JPLISAgent.c line: 838
    > Task :app:testDebugUnitTest
    TransactionLifecycleCoordinatorDbContractTest > createExpense inserts row and writes CREATED event PASSED
    RecommendationCacheServiceTest > put overwrites existing cache entry PASSED
    RecommendationCacheServiceTest > getById checks TTL expiration (7 days) PASSED
    RecommendationCacheServiceTest > getStats returns accurate cache statistics PASSED
    RecommendationCacheServiceTest > concurrent access is thread-safe PASSED
    RecommendationCacheServiceTest > putAll adds multiple recommendations to cache PASSED
    RecommendationCacheServiceTest > getById fetches from repository on cache miss PASSED
    RecommendationCacheServiceTest > LRU eviction when cache exceeds 50 items PASSED
    RecommendationCacheServiceTest > clearForUser removes only specific user's recommendations PASSED
    RecommendationCacheServiceTest > getById does not cache inactive recommendations PASSED
    RecommendationCacheServiceTest > clear removes all entries from cache PASSED
    RecommendationCacheServiceTest > LRU evicts least recently used item PASSED
    RecommendationCacheServiceTest > getById returns null when repository returns null PASSED
    RecommendationCacheServiceTest > getById caches result from repository PASSED
    RecommendationCacheServiceTest > remove deletes recommendation from cache PASSED
    RecommendationCacheServiceTest > getById removes expired entry from cache and fetches fresh FAILED
        java.lang.AssertionError: expected:<1779783914668> but was:<1778487914667>
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RecommendationCacheServiceTest > evictExpired removes only expired recommendations FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1: RecommendationRepository(#3707).getById(eq(rec1),
    any())) was not called
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    RecommendationCacheServiceTest > getById returns cached item when present and not expired PASSED
    RecommendationCacheServiceTest > put adds recommendation to cache PASSED
    ReceiptMatchingWorkerTest > worker stops retrying malformed receipt failures FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptMatchingWorkerTest > worker stops retrying logical conflicts FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptMatchingWorkerTest > worker handles db error gracefully FAILED
        java.lang.IllegalStateException: db error
            at com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorkerTest$worker handles db error
    gracefully$1.invokeSuspend(ReceiptMatchingWorkerTest.kt:108)
            at com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorkerTest$worker handles db error
    gracefully$1.invoke(ReceiptMatchingWorkerTest.kt)
            at com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorkerTest$worker handles db error
    gracefully$1.invoke(ReceiptMatchingWorkerTest.kt)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
            at com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorkerTest.worker handles db error
    gracefully(ReceiptMatchingWorkerTest.kt:107)
    ReceiptMatchingWorkerTest > worker returns success FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReceiptMatchingWorkerTest > all receipts matched no work needed PASSED
    ReceiptMatchingWorkerTest > unmatched receipts matching is attempted FAILED
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    DestinationPersistencePolicyTest > ephemeral destinations are documented PASSED
    DestinationPersistencePolicyTest > degraded destinations serialize but lose payload PASSED
    DestinationPersistencePolicyTest > main tabs are FULL persistence PASSED
    DestinationPersistencePolicyTest > feature destinations default to FULL persistence PASSED
    AiSettingsViewModelTest > setReceiptImageCloudEnabled updates repository PASSED
    AiSettingsViewModelTest > setProactiveBriefingsEnabled syncs work scheduling PASSED
    AiSettingsViewModelTest > saveApiKey requires successful connection test before storing typed key PASSED
    AiSettingsViewModelTest > setPreferredMode updates repository PASSED
    AiSettingsViewModelTest > uiState reflects repository settings PASSED
    AiSettingsViewModelTest > saveApiKey stores typed key after successful connection test FAILED
        java.lang.AssertionError: Verification failed: call 1 of 1: SecureKeyStorage(#3771).storeKey(eq(gemini_api_key),
    eq(AIza12345678901234567890))) was not called.
           kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)
                                                      kotlinx.coroutines.BlockingCoroutine.joinBlocking
    (Builders.kt:95)
                                                 kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)
                                                             kotlinx.coroutines.BuildersKt.runBlocking
    (-:1)
                                                 kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)
                                                             kotlinx.coroutines.BuildersKt.runBlocking$default
    (-:1)
                                                 kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)
                                    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)
                                                    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)
                                    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)
                                                    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0
    (-:1)
                                    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)
                                                    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default
    (-:1)

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle                                             (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                                 (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                  (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                          (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                              (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                              (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                      (-:1)

    kotlinx.coroutines.test.TestScopeKt.advanceUntilIdle                                             (TestScope.kt:95)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend
    (TestBuilders.kt:318)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend
    (TestBuilders.kt:327)

    kotlinx.coroutines.BlockingCoroutine.joinBlocking                                                 (Builders.kt:95)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking
    (Builders.kt:69)

    kotlinx.coroutines.BuildersKt.runBlocking                                                  (-:1)

    kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default
    (Builders.kt:47)

    kotlinx.coroutines.BuildersKt.runBlocking$default                                          (-:1)

    kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult
    (TestBuildersJvm.kt:10)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:310)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                              (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0
    (TestBuilders.kt:168)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0                                              (-:1)

    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default
    (TestBuilders.kt:160)

    kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default                                      (-:1)
            at io.mockk.impl.recording.states.VerifyingState.failIfNotPassed(VerifyingState.kt:63)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    AiSettingsViewModelTest > refreshRuntimeStatus updates runtime summary PASSED
    AiSettingsViewModelTest > testConnection does not persist typed key when connection test fails PASSED
    BudgetViewModelStressTest > stress - clear error operation SKIPPED
    BudgetViewModelStressTest > stress - clear error sets error to null SKIPPED
    CurrencyManagementViewModelTest > currency selection updates conversion display PASSED
    CurrencyManagementViewModelTest > rate refresh triggers loading then updates rates PASSED
    CurrencyManagementViewModelTest > initial state shows available currencies PASSED
    CurrencyManagementViewModelTest > error in rate fetch sets error state PASSED
    DashboardWidgetRenderCoverageTest > widget count matches expected PASSED
    DashboardWidgetRenderCoverageTest > all DashboardWidget subclasses have render coverage PASSED
    DashboardWidgetRenderCoverageTest > RENDERED_WIDGETS does not contain stale entries PASSED
    SpendingMapViewModelStressTest > stress - heatmap receives only spending transactions FAILED
        app.cash.turbine.TurbineAssertionError: No value produced in 3s
            at app//app.cash.turbine.TurbineAssertionError$Companion.invoke(TurbineAssertionError.kt:32)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
                    at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingMapViewModelStressTest > stress - only purchases with no non-spending rows produces same heatmap FAILED
        app.cash.turbine.TurbineAssertionError: No value produced in 3s
            at app//app.cash.turbine.TurbineAssertionError$Companion.invoke(TurbineAssertionError.kt:32)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
                    at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingMapViewModelStressTest > stress - deposits and transfers excluded from heatmap spend total FAILED
        app.cash.turbine.TurbineAssertionError: No value produced in 3s
            at app//app.cash.turbine.TurbineAssertionError$Companion.invoke(TurbineAssertionError.kt:32)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
                    at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingMapViewModelStressTest > stress - markers include all transaction types FAILED
        app.cash.turbine.TurbineAssertionError: No value produced in 3s
            at app//app.cash.turbine.TurbineAssertionError$Companion.invoke(TurbineAssertionError.kt:32)
            at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
            at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:3
    27)
                    at app//kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
                    at app//kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
                    at app//kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
                    at
    app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
                    at app//kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    SpendingMapViewModelStressTest > stress - manual resolve retryable failure shows temporary snackbar FAILED
        java.lang.AssertionError: expected:<Temporary location lookup failure. Please try again.> but was:<null>
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$1.invokeSuspend(TestBuilders.kt:318)
            at
    kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$workRunner$1.invokeSuspend(TestBuilders.kt:327)
            at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:95)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:69)
            at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
            at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:47)
            at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersJvmKt.createTestResult(TestBuildersJvm.kt:10)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:310)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0(TestBuilders.kt:168)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0(Unknown Source)
            at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTest-8Mi8wO0$default(TestBuilders.kt:160)
            at kotlinx.coroutines.test.TestBuildersKt.runTest-8Mi8wO0$default(Unknown Source)
    ReviewScreenTransactionTypeParserTest > parseTransactionTypeOrNull returns enum for valid value PASSED
    ReviewScreenTransactionTypeParserTest > parseTransactionTypeOrNull returns null for invalid value PASSED
    ReviewScreenTransactionTypeParserTest > parseTransactionTypeOrNull returns null for blank input PASSED
    SubscriptionManagementViewModelTest > empty state when no subscriptions PASSED
    SubscriptionManagementViewModelTest > cost calculation correct PASSED
    SubscriptionManagementViewModelTest > cancel subscription updates state PASSED
    SubscriptionManagementViewModelTest > initial state shows subscriptions PASSED
    CarbonFootprintTest > A9 regression - all rows included beyond old 2000 limit PASSED
    CarbonFootprintTest > category footprint calculates correctly PASSED
    CarbonFootprintTest > category breakdown sums to total PASSED
    CarbonFootprintTest > offset calculation returns deterministic cost PASSED
    CarbonFootprintTest > empty dataset returns zero footprint PASSED
    CarbonFootprintTest > shared expenses use effectiveAmount PASSED
    CarbonFootprintTest > merchant footprint uses specific factors PASSED
    WorkerContractTest > all 7 default workers have WorkerSpec entries PASSED
    WorkerContractTest > worker names match WorkerSpec DEFAULTS keys PASSED
    WorkerContractTest > all worker specs have non-null enabled flag PASSED
    WorkerContractTest > worker count matches pauseAllWorkers in RestoreMaintenanceMode PASSED