# Unit-Tests Failure Inventory — vr-20260926-085855 (rp-27, end-of-PR-B sweep step 19)

**Current-status note:** Latest completed full-suite baseline remains `vr-20260926-181440-25aa269d`: 7,130 test events, 75 failed, 295 skipped, 36 failing classes. Candidate repairs target 71 of those cases in 32 classes. The subsequent 37-filter sweep recorded **compile plus seven test classes PASS** (17 original failing cases covered), then **MultiCurrencyAnalyticsTest FAIL** at filter 8, with 3 tests passing and 1 failing. The latest-lookup fixture correction below is **NOT RUN**. The remaining 29 filters and four deliberately unrepaired production/policy cases remain queued; there is no new full-suite verdict. See `MultiCurrency latest-lookup correction and sweep resume - 2026-09-26` and the updated consolidated resume commands below.

Historical run (vr-20260926-085855): unit-tests profile, TIMEOUT E_NO_OUTPUT_TIMEOUT at 56 min. 5,554 test events before the wedge: 5,366 PASS, **187 FAIL** (reported). The frozen tail never reported, so the true count is 187 + unknown. At that point, no completed full-suite baseline existed (history: OOM → TIMEOUT → TIMEOUT), so the original dispositions below were by diff-radius and mechanism analysis, not baseline diff.

Since this run: CurrencyConversionTest retired by the re-review repairs (validated PASS 30/30, vr-20260926-105426). Current-tree failure set may differ slightly elsewhere.

## Classification

### A. Historical OOM-associated signatures - provisional classification

This was the initial OOM-associated grouping, not a proven infrastructure-only disposition. Later inspection found independent fixture defects and coroutine timeouts before the first explicit OOM. Class membership here does not establish root cause or exonerate production behavior. The original class list was: RecurringBillPaymentMatchTest (4), ReceiptProcessingPipelineTest (4), PrivacyDoNotStoreTest (3), Pipeline4LifecycleGoldenTest (3), ConcurrentOccurrenceClaimTest (2), RecurringPlannedActualNoDoubleCountGoldenTest (2), HiltGraphSmokeTest (2), SharedExpenseFlowTest, ReceiptMatchingE2ETest, ReceiptMatchingNoDoubleCountGoldenTest, PrivacyGateEnforcementGoldenTest, NotificationReviewDashboardBudgetGoldenTest, NotificationExpenseDashboardPipelineTest (3), MulticurrencyAnalyticsDashboardBudgetGoldenTest, MerchantCategorizationDedupeGoldenTest, HomeDashboardFinancialInvariantTest, GroupSettlementBudgetOffsetGoldenTest, ForecastSynthesisGoldenTest, CsvExportImportRoundtripGoldenTest, BankSyncFailureRecoveryGoldenTest, BackupRestoreIntegrityE2ETest, BackupRestoreRoundtripGoldenTest, AssetRestoreAtomicityTest, AnalyticsStressTest, ContextualInferenceEngineStressTest, StringDistanceUtilsStressTest, BudgetMonitorStressTest (5), TransactionLifecycleCoordinatorConflictResolutionTest (2).

### B. CL-05 currency blast-radius — adjudicated pre-existing (fixtures/tests vs contracts that predate this lane)

| Class | # | Mechanism |
|---|---|---|
| CanonicalMultiCurrencyFixtureTest | 2 | 7-day LatestDefault staleness (wired by 58706ede 2026-05-20 CURR-3E8) fails the USD leg: FakeExchangeRateStore stamps lastUpdated=real wall-clock (validDate=null), fixture clock pinned to 1700000000000 (2023-11-14) ⇒ age ≈2.8y ≫ 7d ⇒ STALE_RATE ⇒ total 50 not 142. Fix: align fake rate timestamps to FakeTimeProvider or pin validDate. |
| InvalidCurrencyBehavioralTest | 2 | asserts legacy EUR-fallback contract (expected:<EUR> got:<XYZ>; warnings 1 vs 3); typed fail-closed contract now preserves the invalid code. Test asserts an obsolete contract. |
| MergedRecurringPatternsProviderTest | 1 | merchant label casing NETFLIX vs Netflix — dedupe/label contract predates lane. |
| ForecastInputAssemblerTest | 3 | float equality 58.0 vs 57.999…; counts 2 vs 3 — pace/recurring merge contract predates lane. |
| FinancialStressForecastEngineTest | 5 | zero-total diffs (800 vs 0 etc.) — merged-recurring obligations contract predates lane. |
| ~~CurrencyConversionTest~~ | ~~1~~ | RETIRED by re-review repairs (exact redacted failure codes), PASS 30/30. |

### C. Pre-existing domain debt clusters (files untouched by rp-27)

- **BankStatementParserTest (17)** — parser rows/date columns/Revolut+NBG fixtures: largest single cluster.
- **ExpenseCategoryClassifierTest (7)** — ML model persistence/saveModel semantics.
- **KeystoreInstallationSecretHashingTest (5)** — redaction/phone-heuristic boundary — ties to Stage-2 addendum A5 (phone-heuristic over-redaction, CONFIRMED real).
- **Budget family (14)** — BudgetMonitorTest 3, BudgetMonitorStressTest 5 (OOM), BudgetAlertPipelineTest 3, BudgetCalculatorGolden/TimeBoundary 2: notification thresholds + money semantics.
- **P5/P6 analytics fixes (8)** — P5AnalyticsFixesTest 4, P6BudgetCleanupTest 4 (rollover loop, cancellation rethrow).
- **Spending pace family (7)** — SpendingPaceCalculatorDeep 3, SpendingPaceBoundary 2, SpendingPaceCalculatorValidation 1, SpendingPersonalityClassifier 1.
- **Privacy family (2 behavioral + 3 OOM)** — PrivacyStorageContractTest 1, PrivacyGateEnforcementGoldenTest 1 (OOM), PrivacyDoNotStoreTest 3 (OOM).
- **Recurring family (7 behavioral + 6 OOM)** — RecurrenceCalculator 2, RecurringOccurrenceExpander 2 (leap/Feb clamp), RecurringExpenseEngine 1, Pipeline4LifecycleGolden 3 (OOM), claims 6 (OOM).
- **Guard unit-tests (6)** — CancellationSafetyArchitectureGuardTest, CancellationPropagationContractTest, WriteBarrierArchitectureGuardTest, BackupRestoreArchitectureGuardTest 2, RawPersistencePolicyConsolidationTest: consistent with the static-guards baseline drift (new G-CANCEL-01 in RestoreMaintenanceMode/DatabaseBackupRepositoryImpl).
- **Migration/DDL (4)** — MigrationRegistrationTest 2 (119→121, 120→121), DDL512RegressionTest 2.
- **Retention (3)** — RetentionPerimeterTargetTest 2, RetentionCheckpointStoreTest 1 — ties to CL-23 scope.
- **Warranty (3), AI/worker monitors (6)** — WarrantyTrackerRepository 1, WarrantyTextExtractor 1, AutoCreateWarranty 2, DefaultAiEnvironmentMonitor 4, UPR5Completion 2, GeocodingCancellation 2, OnDeviceReceiptAssist 1.
- **Flow goldens (4 behavioral + OOM)** — MonthlyTotalFlowTest, CategoryBreakdownFlowTest, DailyAverageFlowTest, DateBoundaryFlowTest, HistoricalSpendingDistributionBoundaryTest 3, AdvancedAnalyticsEngineDeepTest 3 (sparkline today-boundaries).
- **Parser/misc long tail (~25×1)** — UberReceiptParser 2, SemanticKeywordMatcher 1, MerchantNormalizer 1, SettlementCalculator 1, SharedExpenseManager 1, SharedBudgetManager 1, PriceProtectionTracker 2, SmartSavingsEngine 2, SpendingChallengeManager 2, AutomatedSavingsRule 2, NaturalLanguageSearchVoice 1, FinancialQueryInterpretationInputBuilder 1, ReceiptItemCategorizationInputBuilder 1, ReceiptRepositoryStatementDuplicate 1, ReceiptLinkServiceColumnScope 2, ExpenseExportMapper 1, BusinessExpenseReportGenerator 1, AccountingExportRepository 1, DeliverProactiveBriefingNotification 1, DashboardFollowThroughEngine 1, WarrantyReminderDelivery (pass; name contains FAILED), AnalyticsWindowingSupport 1, PeriodKindContract 1, TransactionEventDao 1.

## Reading order for repair campaigns
1. A-class OOM: after fixing identified harness defects, use isolated, serialized reruns to distinguish independent failures from downstream heap casualties. A heap increase is a diagnostic experiment, not proof of correctness or an infrastructure-only root cause.
2. B-class: fixture alignment (canonical staleness) + contract-test refresh — cheapest wins, 12 tests.
3. C-class: cluster-by-cluster, largest first (BankStatementParser 17, classifier 7, budget 14).

Raw list preserved: build/worktrees/rp-27/build/validation-runs/vr-20260926-085855-bbf64f9c/stdout.log (grep -E ' FAILED$').

## Repair log — 2026-09-26 (B-class first pass)

Validation was intentionally not run in this worktree; the user will run it.

- CanonicalMultiCurrencyFixtureTest (2): already repaired in current source by commit 2544298d, which aligns fixture-rate timestamps with the fake clock. No new change required.
- InvalidCurrencyBehavioralTest (2): refreshed stale fixtures. XYZ is a structurally valid three-letter unknown code and is asserted as a preserved failed bucket; the malformed target-currency fallback now uses AB.
- MergedRecurringPatternsProviderTest (1): aligned the deterministic winner expectation with roll-forward-before-dedup behavior.
- ForecastInputAssemblerTest (3): aligned the stabilized early-month projection, made the manual-precedence fixture use an identical recurring signature, and replaced exact floating-point equality with tolerance.
- FinancialStressForecastEngineTest (5): aligned degraded fallback with the MODERATE contract, modeled confirmed rules through projected occurrences, made identity conversion explicit, and replaced the obsolete exact-zero sparse-bootstrap assertion with bounded behavior.

### Production follow-up discovered (not fixed in this test-refresh batch)

- Currency source-bucket mislabeling: legacy MoneyAggregateBuilder.fromBuckets() builds source buckets with CurrencyCode.parse(code) falling back to EUR while conversion still receives the malformed raw code. A malformed code such as AB can therefore be represented as an EUR source bucket even though conversion fails for AB. This is a real money-diagnostics correctness issue in a critical blast-radius path and needs a dedicated production change with boundary tests.
- Currency contract ambiguity: CurrencyCode.parse() documentation says it validates ISO currency codes, but the implementation validates only the three-uppercase-ASCII shape. Unknown codes such as XYZ are preserved. Decide explicitly whether the type represents syntactically valid identifiers or only SupportedCurrency values, then align documentation and callers without silently defaulting unknown financial data.

## Repair log — 2026-09-26 (BankStatementParserTest batch)

Validation was intentionally not run in this worktree; the user will run it.

- Refreshed 16 stale or incomplete parser fixtures without weakening assertions or changing production parsing behavior.
- Revolut fixtures now include a statement identity block, allowing the parser's documented bank-specific routing to select the Revolut parser rather than the generic fallback.
- NBG debit and credit fixtures now include an NBG identity block, allowing routing through the Greek-bank parser that owns the Χ/Π marker contract.
- Replaced the always-EUR CurrencyNormalizer mock with the real format normalizer so explicit USD and GBP source tokens are exercised instead of being overwritten by test setup.
- Left `header keyword order determines which date column is the transaction date` unchanged because its failure exposes a production sequencing defect rather than a stale expectation.

### Production follow-up discovered (not fixed in this test-refresh batch)

- Bank-statement date-column detection is currently ineffective for normal header rows: `BankStatementParser.parse()` calls `preFilterRows()` before `detectDateColumns()`, while the prefilter removes rows containing header keywords such as DATE, DESCRIPTION, and AMOUNT. Consequently, VALUE DATE versus TRANSACTION DATE ordering is lost and the generic parser defaults to the first date. Repair the production sequencing with focused regression coverage before marking the remaining parser test resolved.

## Repair log — 2026-09-26 (ExpenseCategoryClassifierTest batch)

Validation was intentionally not run in this worktree; the user will run it.

- Refreshed the seven failing persistence fixtures for the AIML-16 at-rest-encryption contract rather than weakening persistence assertions.
- Replaced the relaxed encryption mock, which returned empty byte arrays, with a deterministic reversible encrypted envelope. The previous fixture caused empty model files, JSON parse failures, and empty state after reload.
- Persistence assertions now decrypt the stored model before inspecting JSON and explicitly verify that the on-disk bytes are not plaintext.
- The legacy plain-JSON compatibility test now exercises the decryption failure fallback and verifies that a successfully loaded legacy model is migrated back through the encryption boundary.

### Production follow-up discovered (not fixed in this test-refresh batch)

- `ExpenseCategoryClassifier.saveModelInternal()` catches every encryption and file-write exception, logs it, and returns normally. This contradicts the documented guarantee that `saveModel()` provides real durability because callers cannot distinguish a completed save from a failed one. The throwable is also passed directly to `Timber.e`, which can emit exception messages and stack traces instead of bounded structured diagnostics. Repair this in a dedicated privacy-reviewed production change with explicit failure-semantics tests.

## Repair log — 2026-09-26 (analytics and deterministic-fixture batch)

Validation was intentionally not run in this worktree; the user will run the combined validation.

- Refreshed six SpendingPace tests for the canonical five-day early-month stabilization policy and the explicit `-1f` no-baseline sentinel.
- Corrected PeriodKindContractTest so current periods must contain the anchor while previous calendar periods must end before it; the old loop incorrectly required LAST_WEEK, LAST_MONTH, LAST_QUARTER, and LAST_YEAR to contain now.
- Updated ExpenseExportMapperTest to assert both audit fields: full original `amount` and ownership-adjusted `effectiveAmount`.
- Added the missing deterministic TimeProvider behavior to the non-location natural-language fixture.
- Cleared Calendar state in RecurringExpenseEngineTest date fixtures so ambient seconds and milliseconds cannot leak into rolled recurrence expectations.
- Rebuilt the DST spring-forward fixture with explicit America/New_York ZonedDateTime instants instead of a helper whose fixed-zone timestamps represented two elapsed hours.

### Production follow-up discovered (not fixed in this test-refresh batch)

- UberReceiptParser still falls back to `receivedAt` for timestamped year-less ride dates such as `9:15 PM · March 07` and `7:40 PM · December 31`, despite having a date pattern intended to capture the date subgroup. The two UberReceiptParser tests remain unchanged because this is parser behavior, not an obsolete expectation; investigate extraction after `cleanHtml()` and year-less parsing before resolving them.

## Repair log — 2026-09-26 (savings, tracking, and deterministic-contract batch)

Validation was intentionally not run in this worktree; the user will run the combined validation.

- Refreshed two SmartSavingsEngine fixtures: added the required pass-through analytics currency normalization boundary and aligned multi-goal allocation with the production remaining-gap weighting policy.
- Updated two PriceProtectionTracker assertions: expired protectable items remain tracked with `priceProtectionEligible=false`, and the grocery benefit assertion now checks the stable grocery semantic rather than the singular word `grocery` against `groceries`.
- Made SettlementCalculator summary assertions locale-safe and passed the documented ISO `EUR` currency code instead of a currency symbol.
- Made DashboardFollowThroughEngine expiration verification deterministic by asserting the exact seven-day duration from each recommendation's own creation timestamp.
- Corrected two SpendingChallengeManager fixtures: the sequential clock now advances on the second progress evaluation, and the stored baseline start is derived from the same start-of-day boundary as production.
- Added the missing group-membership fixture to SharedExpenseManager's non-finite custom-split test so it reaches the intended split validation instead of failing earlier at payer membership.

## Validation handback — 2026-09-26 (14-class repair pass)

- User-run serialized validation completed with 12 of 14 classes passing.
- `NaturalLanguageSearchEngineVoiceInputTest` failed because its strict CurrencySettingsRepository fixture did not answer `homeCurrency()`. The fixture now supplies a deterministic EUR flow; revalidation is pending.
- `PriceProtectionTrackerTest` failed because 35-day millisecond arithmetic was evaluated as Int and overflowed, placing the receipt in the future. The failing calculation and the two latent copies in the 30-day threshold/helper fixtures now use Long arithmetic; revalidation is pending.
- No production behavior was changed for either failure, and no commit was created.


## Repair log — 2026-09-26 (budget alert delivery-contract batch)

Validation was intentionally not run in this worktree; the user will run the consolidated validation pass.

- Refreshed eleven budget-alert test failures across BudgetMonitorTest, BudgetMonitorStressTest, and BudgetAlertPipelineTest.
- The notification fixtures now return NotificationService.DeliveryResult.DELIVERED. Production only persists warning or exceeded timestamps after confirmed delivery; the previous relaxed enum mocks returned a non-delivered value, so timestamp verifications failed despite notification invocation.
- Corrected the exact-threshold pipeline expectation: a budget with spent equal to amount is 100% used and follows the production EXCEEDED branch, not the below-100% CRITICAL branch.
- No production defect was identified in this batch. The failures came from stale delivery-result fixtures and an inconsistent exact-100% expectation.


## Repair log — 2026-09-26 (P5 analytics contract-test batch)

Validation was intentionally not run in this worktree; the user will run the consolidated validation pass.

- Refreshed four P5AnalyticsFixesTest failures without changing production behavior.
- Replaced runtime reflection of Room Query metadata with a source-contract check because Room annotations use binary retention and are not available through Java runtime reflection.
- Replaced brittle reflection over the value-class-mangled aggregateExpenses JVM name with a direct check of the category aggregation call site and its PURCHASE_ONLY filter.
- Added the now-required homeCurrency flow fixture while retaining the resolveHomeCurrency cache verification.
- Aligned the size-mismatch assertion with MoneyAggregateBuilder canonical per-currency grouping: two USD inputs produce one USD source bucket while preserving the incomplete transaction-count warning.
- No production defect was identified in this batch; the failures were stale structural assumptions and one incomplete strict mock.


## Repair log — 2026-09-26 (P6 budget-cleanup contract batch)

Validation was intentionally not run in this worktree; the user will run the consolidated validation pass.

- Refreshed the four failing P6BudgetCleanupTest cases without changing production behavior.
- Replaced invalid Java reflection assumptions for a private suspend function and private instance properties with focused source-contract checks.
- The cancellation test now verifies the actual computeAdjustedSpend method body rethrows CancellationException instead of asserting an impossible JVM return type for a suspend function.
- The recurring-status test now verifies the declared active and excluded sets without calling Field.get(null) on instance fields.
- The rollover test now verifies both the numeric bound and the sliding-window removal that enforces it.
- The income-recurring fixture now matches the CurrencyCode value-class argument explicitly, avoiding MockK value generation failure while preserving normalization assertions.
- No production defect was identified in this batch; the failures were stale reflection mechanics and a value-class matcher limitation.


## Repair log — 2026-09-26 (recurrence boundary-fixture batch)

Validation was intentionally not run in this worktree; the user will run the consolidated validation pass.

- Refreshed four recurrence failures across RecurrenceCalculatorTest and RecurringOccurrenceExpanderTest without changing production behavior.
- IRREGULAR recurrence is non-predictive and now correctly asserts that calculateNextDate leaves the date unchanged for manual confirmation instead of fabricating a monthly interval.
- The calculator-versus-expander comparison now clips independently generated dates to the same half-open end boundary used by the expander.
- Extended the semi-annual recovery fixture through September 1 so the expected August 31 occurrence is actually inside the requested range.
- Added the February 28, 2029 annual occurrence because the fixture range ends March 1, 2029 and therefore includes it.
- No production defect was identified in this batch; all four failures were stale recurrence or half-open-range expectations.


## Repair log — 2026-09-26 (budget-window, privacy-input, and historical-distribution batches)

Validation was intentionally not run in this worktree; the user will run the consolidated validation pass.

- Repaired six fixture or contract failures across three additional clusters without changing production behavior.
- BudgetCalculatorGoldenTest now uses ROLLING mode for an anniversary-based yearly expectation. CALENDAR yearly budgets use natural January 1 boundaries and intentionally ignore the anchor date.
- FinancialQueryInterpretationInputBuilderTest now verifies that both redacted category lookup maps are keyed by aliases and do not retain the raw category name.
- ReceiptItemCategorizationInputBuilderTest now compares the local CategoryRef boundary model by category identity and name instead of requiring persistence-layer Category objects to leak through the AI input boundary.
- HistoricalSpendingDistributionBoundaryTest now supplies a deterministic homeCurrency flow to all three integration fixtures. The previous relaxed Flow mock was empty, so production's required first() lookup failed before distribution behavior was exercised.
- The failing BudgetCalculatorTimeBoundaryTest containment assertion remains unchanged because it exposes a production date-window defect rather than an obsolete test expectation.

### Production follow-up discovered (not fixed in this test-refresh batch)

- BudgetCalculator yearly rolling windows lose the original February 29 anchor when a cycle starts in a non-leap year. The implementation clamps the start to February 28 and derives the end with startDate.plusYears(1), so it cannot restore February 29 when the following year is a leap year. For a February 29, 2024 anchor evaluated on February 28, 2024, it returns the half-open window [2023-02-28, 2024-02-28), which excludes the evaluation instant. Derive both boundaries independently from the original anchor month and day, preserving the half-open containment contract.


## Validation handback — 2026-09-26 (batch-3 follow-up)

External validation was run serially by the user.

- Compile passed in `vr-20260926-124138`; the validation executor corrected the HistoricalSpendingDistributionBoundaryTest import from `data.repository.CurrencySettingsRepository` to the actual `domain.currency.CurrencySettingsRepository` package before the successful compile.
- BudgetCalculatorGoldenTest passed in `vr-20260926-124138`.
- FinancialQueryInterpretationInputBuilderTest passed in `vr-20260926-124710`.
- ReceiptItemCategorizationInputBuilderTest passed in `vr-20260926-124835`.
- HistoricalSpendingDistributionBoundaryTest reached production behavior but failed two fixture assertions in `vr-20260926-124953`. FCST-16 intentionally includes every empty week in the 18-month lookback as a nominal 0.01 quiet-week observation, so fixtures covering only five weeks were dominated by approximately 73 legitimate quiet weeks.
- Redesigned both affected historical-distribution fixtures to populate every completed week in the full 18-month lookback with four distinct transaction days. The effective-amount fixture retains bounded weekly variation, while the filtering fixture now includes both DEPOSIT rows and `isNotMine` PURCHASE rows to exercise its complete stated contract.
- Confirmed the previously requested NaturalLanguageSearchEngineVoiceInputTest home-currency Flow stub is already present in the worktree.
- Confirmed all three PriceProtectionTrackerTest overflow sites are already using Long arithmetic: the 30-day threshold, both 35-day timestamps, and the `daysOld` helper.
- Confirmed the canonical multi-currency fixture already timestamps fake exchange rates from the supplied fixture clock and includes a custom-clock freshness regression test. No additional canonical-fixture edit is required.

Post-redesign validation is pending.

## Repair log — 2026-09-26 (final non-OOM sweep)

Validation was intentionally not run in this worktree; the user will run the conclusive serialized validation pass.

### Production defects repaired in this sweep

- **BudgetCalculatorTimeBoundaryTest:** fixed yearly rolling-window construction for February 29 anchors. Start and end anniversaries are now derived independently from the original month/day, so a non-leap-year February 28 start can recover February 29 as the next leap-year exclusive boundary. This closes the previously documented containment gap instead of weakening the assertion.
- **BankStatementParserTest:** moved date-column header detection before header/noise filtering. VALUE DATE versus TRANSACTION DATE ordering is now retained and consumed by data-row parsing. This closes the previously documented parser sequencing defect.
- **UberReceiptParserTest:** expanded timestamp recognition for punctuation/symbol separators. The subsequent review remediation below removes the unsupported 45-day cutoff: yearless dates retain received-year anchoring and the existing new-year adjustment, rather than being replaced by the delivery date.
- **SemanticKeywordMatcherTest:** matching now considers the original normalized merchant, punctuation-as-space normalization, and punctuation removal, preserving both token-boundary and compact-name matches such as e-food versus efood.
- **WarrantyTextExtractorTest:** the subsequent review remediation below replaces the overly broad metadata-word blacklist with anchored metadata-label filtering and explicit product-field precedence. Product names containing words such as Support remain eligible.
- **Cancellation and write-barrier guards:** broad suspend-path catches rethrow cancellation, bulk recurring reconciliation contains an explicit cancellation guard, and recurring planned-expense writes own and check DatabaseWriteBarrier rather than bypassing restore maintenance mode.

### Stale, incomplete, or environment-bound tests repaired

- Corrected deterministic warranty dates and half-open calendar-month expectations in AutoCreateWarrantyFromReceiptUseCaseTest and WarrantyTrackerRepositoryTest.
- Corrected UPR5 cloud-policy fixtures so redaction and fail-closed assertions enable the capabilities required to reach the intended policy branch and assert only controlled reason codes.
- Replaced a relaxed concrete AiRuntimeDiagnostics mock with a real deterministic instance in DeliverProactiveBriefingNotificationUseCaseTest.
- Corrected AutomatedSavingsRuleEngine cap-state setup to pre-consume the same rule's cap, and fully joined DataStore scopes before recreation in both savings state test classes.
- Corrected TransactionEventDaoTest so the retained unrelated field is populated before snapshot-nullification is asserted.
- Corrected GeocodingCancellationTest privacy-policy setup and made cancellation completion deterministic with cancelAndJoin and bounded latches.
- Updated SharedBudgetManagerTest to assert the intentional fail-closed UnsupportedOperationException until a real budget/member allocation mapping exists; zero placeholders are not accepted as fabricated business data.
- Updated ReceiptLinkServiceColumnScopeTest to preserve the privacy contract that purged parsed merchant data remains purged after later column-scoped writes.
- Set the PDF fixture to PURCHASE rows to align with the report default transaction filter. That did not fix the missing Android PDF runtime. Correction: the PDF branch does not apply the accounting-import eligibility policy; see the later platform-correct PDF coverage below.
- Split OnDeviceReceiptAssistService image-byte loading from ML Kit SDK object construction. The JVM test checks the loaded bytes exactly. OnDeviceReceiptAssistServiceImageInstrumentedTest now authors the real request-attachment assertion; its Android execution is still NOT RUN.
- Replaced ReceiptRepositoryStatementDuplicateTest's obsolete call into the permanently disabled legacy processStatement path with a fail-closed legal-path contract pointing to BankStatementLifecycleProcessor.
- Corrected BusinessExpenseReportGeneratorTest so the expense dataset currency matches the requested filing currency instead of expecting filing metadata to relabel raw EUR amounts as USD.
- Replaced two DDL512RegressionTest runtime-reflection assumptions with focused production-source checks. Room Entity and Query annotations use binary retention, so JVM reflection returned empty metadata even though the eventId index and failure outcomes are present in source.
- Corrected MerchantNormalizerTest's relaxed exact-lookup mock so the fuzzy-ranking path is genuinely exercised.
- Corrected the four flow-golden tests' shared harness to subscribe before advancing virtual time and to avoid advanceUntilIdle against intentionally long-lived ViewModel flows.
- Redesigned HistoricalSpendingDistributionBoundaryTest fixtures across the full 18-month lookback so FCST-16 quiet-week observations no longer dominate sparse five-week test data.
- Corrected the NaturalLanguageSearchEngine strict home-currency mock and every PriceProtectionTracker millisecond-overflow site, including latent copies that previously passed for the wrong reason.

### Baseline failures already resolved in current source

The baseline also reported failures whose required production contracts are already present in the current tree: NotificationProcessingPipeline delegates raw-storage policy to RawPersistencePolicyResolver; BankApiIntegration uses exhaustive RawStorageMode handling; RetentionCheckpointStore gives v2 records precedence; AnalyticsWindowingSupport handles punctuation-only merchants; and the canonical multi-currency fixture timestamps rates from its fake clock. These remain part of the conclusive validation set even though no additional behavioral edit was needed in this final sweep.

### Remaining production follow-ups not hidden by test changes

- **Money diagnostics:** MoneyAggregateBuilder.fromBuckets can label a malformed raw source code as an EUR source bucket while conversion still fails under the malformed raw code. This requires a dedicated currency/money change and boundary tests.
- **Currency type contract:** CurrencyCode.parse currently enforces a three-uppercase-ASCII shape rather than membership in SupportedCurrency despite stronger documentation. The intended type contract must be decided explicitly.
- **Classifier durability/privacy:** ExpenseCategoryClassifier.saveModelInternal swallows encryption/write failures and logs the throwable directly. Callers cannot distinguish failed persistence from success, and diagnostics are not bounded structured fields.
- **Shared-budget allocation capability:** member contribution allocation remains intentionally unsupported until a real budget-to-group/member mapping exists. The API now fails closed rather than fabricating zero contributions.
- **On-device AI coverage:** the real ML Kit request-attachment test is now authored under androidTest, but requires execution on a device/emulator. JVM byte-loading results cannot substitute for that gate.

### Infrastructure-only baseline failures

The original run's approximately 28 OOM-cascade classes were not rewritten to manufacture green results. They must be re-baselined with the larger test heap in the conclusive serialized run. A failure that reproduces independently with sufficient heap must be treated as behavioral and triaged from its fresh signature; the old cascade classification is not evidence that it now passes.

All known non-OOM failures from vr-20260926-085855-bbf64f9c now have one of three explicit dispositions: a production repair, a proper test or fixture repair, or a documented production follow-up. Conclusive compile, targeted clusters, guards, and full-suite validation remain pending.

## Review remediation — 2026-09-26

Status: implementation and regression-test changes authored; validation NOT RUN. This section addresses the seven findings in the production-change review approved by the user. It is not a claim that the complete worktree passes, that there are no regressions, or that independent review/guardian gates have passed. No subagents were used and nothing was committed.

Scope: five production files, seven JVM test files, one new Android instrumented test, and this ledger. Existing unrelated changes remain intact. These are separate review work items, not an implicit expansion of CL-05's money-only scope: redaction remains CL-17-adjacent, restore cleanup follows CL-19, receipt/date repairs remain pipeline-local, and the reminder race belongs to the CL-05 display path. The campaign specs and journal were not rewritten to declare completion.

Paths below use `M/` = `app/src/main/java/com/yourname/expensetracker/`, `T/` = `app/src/test/java/com/yourname/expensetracker/`, and `I/` = `app/src/androidTest/java/com/yourname/expensetracker/`.

| Review finding | Files changed in this remediation | Implementation / authored regression coverage |
|---|---|---|
| Short-phone redaction regression | `M/data/ai/provider/internal/CloudPiiSanitizer.kt`; `T/data/privacy/KeystoreInstallationSecretHashingTest.kt` | Removed the blanket 10–15-digit acceptance window. Explicit contact labels take precedence over numeric amount/date shapes; ambiguous phone-like runs remain redacted. Numeric candidates do not join separate lines. Tests cover short/local contacts, long formatted runs, amount-shaped labelled contacts, spaced labels, adjacent amount/date fields, dedicated PII markers, and exact amount preservation through the real cloud-redactor adapter. |
| Secondary cancellation bypassed recovery lock | `M/data/repository/DatabaseBackupRepositoryImpl.kt`; `T/data/repository/DatabaseBackupRepositoryImplTest.kt` | Secondary cleanup cancellation attempts CRITICAL_RECOVERY_REQUIRED before propagation; the outer finally still preserves the original cancellation. The authored 18-case matrix crosses restore/import/reset, journal durability failure/cancellation, and successful critical persistence/persistence failure/secondary cancellation, while asserting original exception identity, a critical-state attempt, critical mode, journal retention and no destructive step. |
| Unsupported Uber 45-day date policy | `M/data/email/provider/UberReceiptParser.kt`; `T/data/email/provider/UberReceiptParserTest.kt` | Removed the new past-age cutoff without changing the existing seven-day future/new-year adjustment. Corrected the contradictory anchored-year assertion and corrupted middle-dot fixtures. Added delayed same-year, delayed previous-year, explicit-year and genuinely undated cases. |
| Metadata words rejected real product names | `M/domain/receipt/WarrantyTextExtractor.kt`; `T/domain/receipt/WarrantyTextExtractorTest.kt` | Explicit Product/Item/Description fields take precedence over fallback heuristics. Anchored warranty/contact metadata filtering replaces the broad added word blacklist. Paired tests preserve product names containing Support/Warranty/Coverage and reject metadata-only input. |
| Reminder results could publish out of order | `M/ui/screens/reminder/BillRemindersViewModel.kt`; `T/ui/screens/reminder/BillRemindersViewModelTest.kt` | Manual refresh and currency changes share one collectLatest pipeline. Suspended-calculation tests cover both trigger orders and late results. A separate failure/retry test preserves manual-refresh recovery after the currency observer fails; the manager still resolves the actual target through its typed contract. |
| Invalid Kotlin string in restore guard | `T/architecture/BackupRestoreArchitectureGuardTest.kt` | Corrected the quoted resetDatabase marker using a raw string. No assertions, baselines, allowlists or exceptions were removed or broadened by this remediation; FG-03/FG-06/FG-07/FG-23 remain applicable. |
| Image-attachment assertion lost from JVM coverage | `T/data/ai/provider/OnDeviceReceiptAssistServiceTest.kt`; `I/data/ai/provider/OnDeviceReceiptAssistServiceImageInstrumentedTest.kt` (new) | JVM loading now checks exact bytes. The instrumented test encodes a real PNG and asserts request.image is present in image-analysis mode and absent in text-only mode. It constructs the SDK request without invoking model inference. Instrumented execution remains mandatory and NOT RUN. |

### Validation handback — commands prepared, not executed

Run from this rp-27 worktree. The eleven-class list below is the earlier review plan; the next batch's focused handback is in the Post-validation repair batch section below. First inspect the runner's state; if a run is live, poll it instead of starting another. The helper uses child PowerShell processes, runs serially and stops on nonzero results. Omit `-Raw`: current profile-only deduplication incorrectly conflates different targeted filters; the wrapper's explicit HUMAN_REQUEST override retains the active-run lock. A wrapper timeout may leave a run active: inspect/poll its returned run ID instead of blindly rerunning.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/validation-runner.ps1 -Action List

# Continue only after confirming there is no active validation run.
function Invoke-ReviewGate {
    param([string]$Profile, [string]$TestFilter)
    $runnerArgs = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', './scripts/vrun.ps1', '-Worktree', '.',
        '-Profile', $Profile, '-GradleDaemon',
        '-MaxTotalMinutes', '150'
    )
    if ($TestFilter) { $runnerArgs += @('-TestFilter', $TestFilter) }
    & powershell @runnerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Validation stopped: $Profile $TestFilter (exit $LASTEXITCODE)"
    }
}

Invoke-ReviewGate -Profile compile
foreach ($filter in @(
    '*BackupRestoreArchitectureGuardTest',
    '*DatabaseBackupRepositoryImplTest',
    '*KeystoreInstallationSecretHashingTest',
    '*UPR5CompletionTest',
    '*UberReceiptParserTest',
    '*WarrantyTextExtractorTest',
    '*AutoCreateWarrantyFromReceiptUseCaseTest',
    '*WarrantyTrackerRepositoryTest',
    '*BillRemindersViewModelTest',
    '*BillReminderManagerTest',
    '*OnDeviceReceiptAssistServiceTest'
)) {
    Invoke-ReviewGate -Profile targeted-unit-test -TestFilter $filter
}
```

The compile profile compiles production Kotlin only; the first targeted JVM run also compiles changed JVM test sources. Do not infer test compilation from production compilation alone. Preserve run IDs, actual executed test cases and result/log artifacts. Missing, skipped, zero-match, stale, infrastructure-error or timeout results are not PASS.

After the focused gates pass on a stable tree, the conclusive broader gates are separate serialized invocations:

```powershell
Invoke-ReviewGate -Profile static-guards
Invoke-ReviewGate -Profile unit-tests
```

The new image-attachment coverage requires a connected Android device/emulator:

```powershell
Invoke-ReviewGate -Profile connected-tests
```

Important: the current connected-tests profile runs the whole connected debug suite and does not apply TestFilter. Confirm that `OnDeviceReceiptAssistServiceImageInstrumentedTest.validPngIsAttachedOnlyInImageAnalysisMode` actually executes. No device, skipped instrumentation or a JVM-only pass leaves this coverage gate pending.

Independent strict/privacy review and user-run validation remain pending. The separately documented money diagnostics, CurrencyCode contract, classifier persistence/privacy and unsupported shared-budget allocation follow-ups above are not silently treated as resolved by this review-remediation batch.

## Post-validation repair batch — 2026-09-26

Status: source changes authored and read back; new validation NOT RUN. This is a partial repair batch, not full-suite or merge approval. No subagents, builds, tests, guard executions, commits, or baseline changes were made by the coder in this batch. Existing unrelated edits remain intact.

### Verified user-run baseline

The persisted records identify revision `2544298d`, not `315abb30`. The reviewed runs have completion markers and matching start/end worktree fingerprints. Those historical fingerprints do not validate the subsequent edits below.

- Compile and the eleven earlier focused review classes passed in the user-run validation.
- `vr-20260926-145626-f1b5596a`: static-guards FAIL; 17 passed, 6 blocking violations, 2 infrastructure errors.
- `vr-20260926-150117-5ed02288`: unit-tests TIMEOUT, `E_NO_OUTPUT_TIMEOUT`, after 3,361 seconds. Direct parsing of failed-test records found 62 failures: 32 explicit OOM signatures, 7 UncompletedCoroutinesError timeouts, and 23 other signatures. These are signature categories, not proven root-cause categories; the unreported tail is unknown.
- `vr-20260926-155818-510d396c`: app-check FAIL at checkDirectTimeCalls / G-TIME-01.
- Connected tests remain NOT RUN. A reported 3 GB fork heap does not establish heap independence, a memory leak, or an infrastructure-only disposition.

### Files and mechanisms repaired

Paths use the M/ and T/ prefixes defined above. Existing numeric assertions and workload/performance thresholds were retained; the new checks strengthen failure/cleanup coverage.

| File | Diagnosis and authored repair |
|---|---|
| `M/data/repository/DatabaseBackupRepositoryImpl.kt` | The cancellation architecture test's 200-character sibling-catch window missed the long preceding cancellation handler in finishCancelledRestore. Consolidated duplicate cleanup-failure recovery into one handler: both ordinary failure and secondary cancellation attempt the critical lock; explicit cancellation checks remain in both catch paths; the outer finally preserves the caller's original cancellation. No guard was weakened. The existing 18-case restore/import/reset cancellation matrix must be rerun. |
| `T/data/ai/provider/DefaultAiEnvironmentMonitorTest.kt` | Inspected the locally resolved ML Kit SDK: Generation has INSTANCE and non-static getClient methods. Replaced ineffective static mocking with singleton-object mocking and matching teardown. All four TTL/boundary expectations remain unchanged. |
| `T/data/location/GeocodingCancellationTest.kt` | Blocking on a latch before a default-start async child could execute prevented request startup. Start children undispatched, preserve cancellation/join assertions, and release the owned OkHttp executor/connections and Log static mock after each test. |
| `T/data/privacy/RetentionPerimeterTargetTest.kt` | Closing Room cancels its coroutine scope, so it is not an ordinary database-failure fixture. Inject real SQLite DELETE failures with triggers in the isolated in-memory database instead. Seed rows old enough for both retention stages; assert stage-one snapshot clearing stays committed, failure codes remain controlled, and a failed parent deletion rolls back child deletion. No production schema or migration was changed. |
| `T/domain/price/PriceProtectionTrackerTest.kt` | The remaining threshold assertion confused an elapsed 30-day interval with the production window including today and the preceding 29 local calendar days. Use one fixed FakeTimeProvider for all receipts and an independently derived exact calendar-start assertion, retaining Long arithmetic. |
| `T/domain/forecasting/FinancialStressForecastEngineTest.kt` | The degraded-fallback test threw from an obsolete budget dependency that the forecast no longer reads. Inject the failure at getDepositsBetween and verify that call occurred; retain all MODERATE-risk, probability, horizon and degraded-message assertions. |
| `T/domain/budget/P6BudgetCleanupTest.kt` | MockK exposed CurrencyCode's underlying String in a generic argument read. Use the EUR value already constrained by the stub matcher, and use the real test CurrencyConverter for predicted recurring expenses. Preserve the 500/485 balance and income/expense assertions. |
| `T/domain/analytics/AnalyticsStressTest.kt` | The real repository now reads getExpensesBetweenUncapped, but the fixture populated only the old capped query. Stub the actual read and preserve half-open filtering, all 10,000 transactions, the 505,000 total and the original 10-second budget. |
| `T/e2e/FlowPipelineTestHarness.kt` | Four flow tests lacked emissions from allBudgets and the uncapped expense flow, and used a relaxed mock instead of the normalized-input assembler. Supply the real read contracts and real AnalyticsInputAssembler; use the same fake clock for anomaly analysis; reject error states and cancel the owned ViewModel scope in finally. |
| `T/e2e/NotificationExpenseDashboardPipelineTest.kt` | The real BudgetRepository waited for an unstubbed expense-mutation flow while its day ticker continued advancing virtual time. Supply an emitting, non-completing MutableStateFlow and assert zero remaining subscribers after each computation. Anchor the March fixture inside March, use real test currency conversion/normalization, and preserve currency/shared-expense identity in the dashboard adapter. This is a concrete fixture defect relevant to the early timeout/OOM sequence, not proof that every OOM victim is fixed. |

### Still open — do not subtract these from the failure record without reruns

- The remaining non-OOM signatures include CancellationPropagationContractTest, AssetRestoreAtomicityTest, AccountingExportRepositoryTest, ContextualInferenceEngineStressTest, RawPersistencePolicyConsolidationTest, BankStatementParserTest, two TransactionLifecycleCoordinatorConflictResolutionTest cases, StringDistanceUtilsStressTest, and BackupRestoreIntegrityE2ETest. They have not been repaired by this batch.
- CancellationPropagationContractTest has a concrete scanner mismatch to address separately: recurring bulk reconciliation already calls CancellationSafe.rethrowIfCancellation, while the contract searches for the literal CancellationException token and its first failed++ marker can select a different catch. No production swallowing of cancellation was established for that method.
- ReceiptMatchingE2ETest's timeout and the rest of the OOM-affected Room/golden/privacy classes still need isolated, serialized diagnosis. Do not raise heap or relax deadlines/assertions as a substitute for that diagnosis.
- Newly noted production follow-up: DefaultAiEnvironmentMonitor's broad exception fallback has no explicit cancellation rethrow and logs Throwable objects. Its production source was not changed by the TTL-fixture repair; cancellation/privacy remediation needs its own focused coverage and review.
- Static-guard violations/infrastructure errors, app-check, instrumented image coverage, the earlier money/classifier follow-ups, validator-side edits and independent strict/privacy review remain open. No guard, allowlist, ratchet, test exclusion, or baseline was changed.

### Corrected next validation handback — user execution only

Use the corrected Invoke-ReviewGate helper above, from rp-27, in one PowerShell window. Omit -Raw: the current runner's successful-run deduplication key ignores TestFilter. The wrapper's explicit HUMAN_REQUEST rerun override does not bypass its active-validation lock. That tooling defect is documented, not repaired here. Stop on any failure and poll an existing RUNNING run instead of restarting it.

```powershell
$env:ORG_GRADLE_PROJECT_testMaxHeapSize = '3g'
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/validation-runner.ps1 -Action List
# Confirm no active validation before executing the following block.
& {
    Invoke-ReviewGate -Profile compile
    foreach ($filter in @(
        '*CancellationSafetyArchitectureGuardTest',
        '*BackupRestoreArchitectureGuardTest',
        '*DatabaseBackupRepositoryImplTest',
        '*DefaultAiEnvironmentMonitorTest',
        '*FinancialStressForecastEngineTest',
        '*PriceProtectionTrackerTest',
        '*GeocodingCancellationTest',
        '*RetentionPerimeterTargetTest',
        '*P6BudgetCleanupTest',
        '*AnalyticsStressTest',
        '*CategoryBreakdownFlowTest',
        '*DailyAverageFlowTest',
        '*DateBoundaryFlowTest',
        '*MonthlyTotalFlowTest',
        '*NotificationExpenseDashboardPipelineTest'
    )) {
        Invoke-ReviewGate -Profile targeted-unit-test -TestFilter $filter
    }
}
```

These are focused gates for this batch, not conclusive validation. The first targeted run also compiles test sources. Resolve the remaining queue before another expensive full-suite attempt. Final closure still requires separate static-guards, full unit-tests, app-check and device-backed connected-tests gates, plus review; a timeout, missing marker or stale result is never PASS.

## Further test-only repairs - 2026-09-26

Status: **authored; validation NOT RUN**. HEAD remains `2544298d`. This pass changes nine test-source files and these recovery documents; it adds no production-code changes, dependencies, schemas, test exclusions, baselines, or allowlist entries. Earlier production edits remain in the worktree and still require their review gates. No subagents, builds, tests, static-guard executions, or commits were performed.

The preceding batch's open queue is historical as of that batch. The items below are now authored repairs, not validated closures. Source/diff read-back was performed; independent strict/privacy review and runtime verification remain pending.

### Files touched and contracts preserved

Paths below are relative to `app/src/test/java/com/yourname/expensetracker/` unless explicitly identified as Android instrumentation.

| File | Diagnosis and repair |
|---|---|
| `data/backup/AssetRestoreAtomicityTest.kt` | Disk reads validate the live-database recovery identity, unlike the standalone JSON codec. Give the PENDING-task journal a real temporary liveDbPath and assert it survives the disk round-trip. Keep all three task IDs, statuses, paths and JSON-round-trip assertions. |
| `e2e/BackupRestoreIntegrityE2ETest.kt` | DatabaseWriteBarrier reads currentMode(), not only isWritesAllowed(). Stub coherent RESTORE_PREPARING/NORMAL states. Accept only DatabaseAccessBlockedException as evidence of blocked writes, and assert its WRITE access type, restore mode and operation name. Own and cancel the repository application scope before closing Room. All integrity/golden assertions remain. |
| `domain/categorization/ContextualInferenceEngineStressTest.kt` | Production uses ISO DayOfWeek values; Calendar.FRIDAY was 6 and therefore looked like ISO Saturday. Convert the fixture to ISO weekday values throughout and a fixed local week. Keep existing assertions and performance budgets; add all-seven-day equivalence coverage between explicit weekdays and timestamp-derived weekdays. |
| `domain/transaction/lifecycle/TransactionLifecycleCoordinatorConflictResolutionTest.kt` | The two no-source-ID cases used NOTIFICATION_AUTO_ACCEPT without its required notification provenance, so they failed before conflict resolution. Use MANUAL_ENTRY only in those two cases, explicitly make the exact-key lookup miss, and verify the intended exact/fuzzy resolution calls. Notification provenance requirements are not bypassed or changed. |
| `e2e/ReceiptMatchingE2ETest.kt` | A relaxed DomainTransactionRunner did not execute the transactional link body. Wire RoomDomainTransactionRunner, the actual write barrier and the already-created real CurrencyConverter; cancel the owned application scope at teardown. Preserve golden totals, matching score and persisted-link assertions; actually exercise duplicate-link rejection and verify one link remains. These concrete fixture repairs do not prove that the earlier timeout or all OOM victims are resolved. |
| `contracts/CancellationPropagationContractTest.kt` | The first failed++ occurred outside the intended catch, and canonical CancellationSafe.rethrowIfCancellation was not recognized. Scope this recurring contract to the named method and an unambiguous containing catch. Require a leading check of the caught variable before recovery; missing/ambiguous targets fail. Added controls for counters in other methods, missing/duplicate targets, comments/strings, wrong exception variables, conditional guards and guards placed after recovery. The other critical entries remain present. The existing RecurringLifecycleCoordinatorTest cancellation behavior test is a required companion rerun. |
| `data/repository/AccountingExportRepositoryTest.kt` | Relocated only the real-PDF serialization test out of the plain-JVM suite; paging, CSV, accounting-policy and privacy tests stay here. This is not a deleted or ignored contract; its real output assertions are retained in the instrumented class below. |
| `data/repository/AccountingExportPdfRepositoryTest.kt` (new) | Portable JVM orchestration test verifies that the real repository passes the complete rows/date range to the exporter exactly once and writes its bytes unchanged. The byte sentinel is deliberately not a fabricated PDF. This test does not claim to validate PDF serialization. |
| `app/src/androidTest/java/com/yourname/expensetracker/data/repository/AccountingExportPdfRepositoryInstrumentedTest.kt` (new) | Runs the real AccountantReportPdfExporter through the repository on Android. Preserves the pre-move success, record-count, .pdf path, nonempty-file and %PDF-header assertions, with isolated temporary output and static-mock cleanup. Uses the already-declared Android test dependencies. **Required device/emulator gate; NOT RUN.** |

### Review correction: privacy scanner draft not retained

The comment-only privacy-guard failure is diagnosed, but its drafted sanitizer-based repair was not retained. The existing sanitizer also blanks Kotlin string templates, which can contain executable property reads. Reusing it blindly could weaken the ownership check. The original RawPersistencePolicyConsolidationTest source check and negative fixture remain unchanged; no selector/file exception was introduced and the explanatory production comment was not rewritten merely to satisfy the check. A template-aware scanner repair with adversarial controls remains pending.

### PDF platform correction and required gate

The initially drafted native-graphics Robolectric fixture was corrected before handback. Inspection of the locally resolved Robolectric 4.11.1 artifacts found no PdfDocument shadow, and nativeruntime-dist-compat 1.0.2 contains Linux/macOS libraries but no Windows library. Leaving that draft in the desktop suite would introduce another known harness failure. No SDK upgrade, platform skip, synthetic PDF header, or weakened real-output assertion was used instead.

The real PDF contract now requires `connected-tests`, including `AccountingExportPdfRepositoryInstrumentedTest.exportExpensesAccountantReportPdfWritesPdfOutput`. A green JVM routing test does **not** close the original PDF failure. Without an attached device/emulator, PDF validation remains NOT RUN and final closure is blocked. The normal compile/JVM test profiles do not establish Android-test compilation or execution.

### Production findings and unresolved checks - deliberately not hidden

1. **BankStatementParser / Refund Amazon: source-identified production defect.** `BankStatementParser.preFilterRows` checks `HEADER_KEYWORDS` with unbounded substring matching. The keyword `AM` matches `AMAZON` in the legitimate `Refund Amazon` transaction and removes the row before refund classification. This explains the remaining one-row-versus-zero assertion; it is not a stale merchant fixture. The test and merchant remain unchanged. Owning-lane repair needs context-aware header filtering and regressions for legitimate merchant substrings as well as real header/time noise; do not simply drop the test or rename Amazon.
2. **AccountingExportRepository cancellation/privacy follow-up.** The outer `exportExpenses` catch converts every Exception into ExportResult and returns `e.message`. Source inspection shows no CancellationException rethrow there and an uncontrolled exception-message exposure path. Production is unchanged in this pass. This needs focused cancellation and safe-error coverage plus export/privacy review, not a permissive test mock.
3. **StringDistanceUtilsStressTest remains unresolved.** The existing 1,000-character distance assertion and 50 ms budget are untouched. The recorded slow result needs isolated evidence to distinguish contention/JIT/GC effects from a real performance problem. No deadline relaxation, sleep, warm-up workaround or test removal was applied.
4. **OOM/timeout closure is still pending.** Neither the user-run 3 GB heap nor these source repairs prove a leak, heap independence, infrastructure-only failure, or a recovered full suite. Preserve the 62-failure/unknown-tail record until new durable results exist. Static-guard failures, app-check debt, earlier production follow-ups and independent review also remain open.

The production candidates are also recorded in `TRIAGE-2026-09-test-recovery.md`. No unexecuted repair is subtracted from the recorded failure counts.

### Combined next validation handback - user execution only

Run from this rp-27 worktree in one PowerShell window, serially, on a quiescent tree. This block combines the prior post-validation repair batch and the new repairs. It stops at the first nonzero result. Keep the complete run IDs and result/log artifacts. An existing RUNNING result must be polled, never started again.

```powershell
$env:ORG_GRADLE_PROJECT_testMaxHeapSize = '3g'
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/validation-runner.ps1 -Action List
# Confirm no active validation before continuing.

function Invoke-ReviewGate {
    param([string]$Profile, [string]$TestFilter)
    $runnerArgs = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', './scripts/vrun.ps1', '-Worktree', '.',
        '-Profile', $Profile, '-GradleDaemon', '-MaxTotalMinutes', '150'
    )
    if ($TestFilter) { $runnerArgs += @('-TestFilter', $TestFilter) }
    & powershell @runnerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Validation stopped: $Profile $TestFilter (exit $LASTEXITCODE)"
    }
}

& {
    Invoke-ReviewGate -Profile compile
    foreach ($filter in @(
        '*CancellationPropagationContractTest',
        '*RecurringLifecycleCoordinatorTest',
        '*AssetRestoreAtomicityTest',
        '*ContextualInferenceEngineStressTest',
        '*TransactionLifecycleCoordinatorConflictResolutionTest',
        '*AccountingExportRepositoryTest',
        '*AccountingExportPdfRepositoryTest',
        '*BackupRestoreIntegrityE2ETest',
        '*ReceiptMatchingE2ETest',
        '*CancellationSafetyArchitectureGuardTest',
        '*BackupRestoreArchitectureGuardTest',
        '*DatabaseBackupRepositoryImplTest',
        '*DefaultAiEnvironmentMonitorTest',
        '*FinancialStressForecastEngineTest',
        '*PriceProtectionTrackerTest',
        '*GeocodingCancellationTest',
        '*RetentionPerimeterTargetTest',
        '*P6BudgetCleanupTest',
        '*AnalyticsStressTest',
        '*CategoryBreakdownFlowTest',
        '*DailyAverageFlowTest',
        '*DateBoundaryFlowTest',
        '*MonthlyTotalFlowTest',
        '*NotificationExpenseDashboardPipelineTest'
    )) {
        Invoke-ReviewGate -Profile targeted-unit-test -TestFilter $filter
    }
}
```

Omit `-Raw`: the currently inspected successful-run deduplication key ignores TestFilter; the wrapper's HUMAN_REQUEST override still preserves the active-run lock. The 150-minute wrapper wait does not change the runner profile's execution/no-output watchdogs. A wrapper bookkeeping error or timeout is not permission to start an overlapping run; inspect the returned run's result.json and use the runner Wait action.

These three unresolved checks can be rerun separately for fresh diagnostic evidence; the parser test is expected to remain red until its production defect is repaired, and the privacy source check still flags the explanatory comment. A failure is not an approved baseline:

```powershell
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*RawPersistencePolicyConsolidationTest'
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*BankStatementParserTest'
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*StringDistanceUtilsStressTest'
```

After targeted failures have been reviewed, the broader gates remain separate serialized invocations. If collected before the known defects are repaired, these are diagnostic runs, not a conclusive green/merge gate. Do not run them concurrently or treat a failed earlier gate as waived:

```powershell
Invoke-ReviewGate -Profile static-guards
Invoke-ReviewGate -Profile unit-tests
Invoke-ReviewGate -Profile app-check
# Requires an attached Android device/emulator; includes real PDF and image coverage.
Invoke-ReviewGate -Profile connected-tests
```

All commands above are recommendations for user execution, not executed validation. Missing, skipped, zero-match, stale, infrastructure-error and timeout results are never PASS. No commit or merge approval is given by this repair record.


## Completed-suite baseline and next repair pass - 2026-09-26

### Persisted evidence, not headline counts

- Source: `build/validation-runs/vr-20260926-181440-25aa269d/{request.json,result.json,complete.marker,stdout.log,stderr.log}`. Result is terminal `FAIL / E_COMMAND_FAILED`, exit 1, no timeout; Gradle reached its build verdict. The recorded elapsed time is 1,225 seconds (20m 25s).
- Gradle's summary is **7,130 tests completed, 75 failed, 295 skipped**. Exact test-event parsing gives **6,760 PASSED + 75 FAILED + 295 SKIPPED**, or 6,835 non-skipped events, in **36 failing classes**. The report's 76/6,836 numbers are not test counts; the extra failed Gradle task is not another failed test.
- Start/end worktree fingerprints match in result.json. Its recorded revision and the inspected current HEAD are `2544298d`, not `315abb30`. This does not identify which uncommitted edits came from which author.
- The only `OutOfMemory` occurrence in stdout is the **passing test name** `RecommendationLifecycleManagerTest > checkAndExpire handles OutOfMemoryError gracefully PASSED` (line 28196). No matching OOM/heap-failure signature appears in stderr. This run supplies completed-suite/no-observed-OOM evidence, not a proof that future hangs are impossible or that a particular repair alone caused recovery.
- Newly observed tail failures are not automatically regressions or automatically pre-existing. No before/after equivalent full-run baseline establishes either claim. The prior active handoff already mentions some of these classes. Keep per-class causality separate from run completion.
- All changes below happened **after** this persisted run. Validation remains with the user: no Gradle, tests, lint, guards, subagents, commits, or production changes were executed/authored in this bounded follow-up.

### Authored repairs: five classes / fourteen recorded failures

`T` below means `app/src/test/java/com/yourname/expensetracker/`. Status for every row: **authored, source-reviewed, runtime/compile validation NOT RUN**.

| File under T | Recorded failures | Root cause and preserved/strengthened contract |
|---|---:|---|
| `guard/MoneyBoundaryGuardTest.kt` | 9 | Synthetic repositories omitted the now-required production-source manifest, so all nine reached infrastructure exit 2 before money rules. Declare only the synthetic app source root; require exact exit 1 plus the original rule ID for each bad-money fixture. Add a separate missing-manifest negative control requiring exit 2 and the controlled undeclared-scope diagnostic. JUnit owns temporary-root cleanup. The real guard, production manifest, baselines, and allowlists are unchanged (FG-03/FG-06/FG-07/FG-23). |
| `e2e/DailyAverageFlowTest.kt` | 1 | March 1 through April 1 is a full 31-day half-open month, not 30 days. Thirty purchases of 30 total 900; expected average is independently 900/31. Assert the actual calendar endpoints, 30 spending days and one zero-spend day, and require non-null ViewModel range/statistics rather than allowing two -1 sentinels to compare equal. The average assertion is not relaxed and production denominator code is unchanged. |
| `golden/BackupRestoreRoundtripGoldenTest.kt` | 1 | DatabaseWriteBarrier reads currentMode, not just isWritesAllowed. Supply coherent enum states; assert DatabaseAccessBlockedException's WRITE/mode/operation fields. Keep existing golden values unchanged. A companion test covers every non-NORMAL enum value, including assets/reset/critical modes missing from the historical seven-mode golden. Own/cancel the repository application scope before Room closes. |
| `golden/WorkerRestoreBarrierIdempotencyGoldenTest.kt` | 1 | Same stale strict-mode fixture. Assert typed denial; attempt actual writes inside runWrite while blocked and measure row-count differences instead of hard-coding dbMutationsDuringRestore=0. The NORMAL path proves the runWrite callback executes. Preserve the golden data and idempotency/operation-name assertions. These are simulated worker operations, not a substitute for every worker's runtime tests. |
| `service/RecommendationCacheServiceTest.kt` | 2 | The relaxed TimeProvider returned epoch zero while recommendation expiry used wall time. Use one fixed, controllable clock for service and fixtures. Replace the previously vacuous TTL test with before/after-seven-day cache observations while the recommendation itself stays active for 14 days, and verify the repository is hit exactly once and the refreshed value is cached. Reset Dispatchers.Main after each test. |

At the end of this five-class pass, 14 cases had candidate repairs and the other **61 cases in 31 classes** remained open. The later remaining-test clusters below update the cumulative queue to 44 targeted / 31 without a repair. All 75 remain baseline failed observations until validation; neither number is a new suite verdict.

### Validator-related edit review

Current source checks confirmed the `domain.currency.CurrencySettingsRepository` import against its declared interface, and `any<Uri>()` against ReceiptOcrService's distinct Uri/String overloads. The current raw-string patterns in DDL512RegressionTest and P6BudgetCleanupTest retain their index/cancellation/status/bound requirements; raw strings preserve regex escapes without changing them into Kotlin escapes. The quoted resetDatabase marker in BackupRestoreArchitectureGuardTest was also inspected. A source-presence guard is not proof of runtime ordering or complete architectural safety; broader independent review remains pending. Exact validator authorship of each earlier raw-string line cannot be reconstructed from the uncommitted diff alone.

The persisted full-run observations (not newly run checks) are: DDL512RegressionTest 27 passed, P6BudgetCleanupTest 9 passed, HistoricalSpendingDistributionBoundaryTest 8 passed, ReceiptRepositoryStatementDuplicateTest 1 passed, BackupRestoreArchitectureGuardTest 5 passed. These counts do not validate this follow-up's new edits or authorize a commit.

### Fresh class queue (all 75 failure events retained)

Authored repairs are provisional. All other rows are open observations, not an assertion that production is sound or that the assertion is stale.

| Class | Failures | Disposition |
|---|---:|---|
| `MoneyBoundaryGuardTest` | 9 | Fixture repair authored; required manifest now present, violation exit 1 remains distinct from missing-scope exit 2. NOT RUN. |
| `CrossSourceVerificationTest` | 4 | Real normalization and uncapped DAO fixtures; independent nonzero totals and as-of pace oracle. Authored / NOT RUN. |
| `GoldenMasterVerificationTest` | 4 | Shared real currency/history inputs, typed budget-spend port, purchase-only oracle and calendar boundaries. Authored / NOT RUN. |
| `SpendingMapViewModelPrivacyDenialTest` | 4 | Suspend on StateFlow while Main runs; real-time bounded IO wait, typed denials and no-GPS-on-denial controls retained. Authored / NOT RUN. |
| `TransactionTargetedUpdateSideEffectsTest` | 4 | Real write barrier, planner and post-commit runner; verify committed mutation, budget completion and atomic recurring reconciliation. Authored / NOT RUN. |
| `BackupRestoreViewModelTest` | 3 | Use non-null opaque Uri fixtures at the mocked repository boundary, a JUnit-owned cache directory and an explicit null metadata cursor. Keep the real valid-header preflight, restart/write-unblocking and extract-size assertions. Cancel/join owned ViewModels. Validator class PASS (17 tests), `vr-20260926-195919-691252f1`. Full-suite closure remains pending. |
| `CashFlowCalendarViewModelTest` | 3 | Let each navigation request complete before issuing the next; assert both month boundaries and exactly three calculations. Do not discard an already-loaded StateFlow snapshot and wait for a nonexistent replacement. Preserve upcoming-count/partial-generation assertions and cancel superseded fixtures. Validator class PASS (10 tests), `vr-20260926-200025-d251724b`. Full-suite closure remains pending. |
| `ReceiptMatchingViewModelTest` | 3 | Stub and verify the current ReceiptLinkService and ReceiptMatchLifecycleService legal boundaries, not obsolete repository mutations. CompletableDeferred gates make per-receipt and batch busy states observable. Require terminal success, exact match/suggestion calls and zero legacy mutation calls; cancel/join owned ViewModels. Validator class PASS (4 tests), `vr-20260926-200128-7edbc9b5`. Full-suite closure remains pending. |
| `ReceiptProcessingPipelineTest` | 3 | Opaque non-null Uri at mocked OCR boundary; real parsing/categorization retained and failure identity verified. Authored / NOT RUN. |
| `ReviewViewModelPrivacyDenialTest` | 3 | Explicit emitting AI-settings flow and owned ViewModel cleanup; strict exporter and typed/bounded denial assertions retained. Authored / NOT RUN. |
| `SavingsGoalsViewModelTest` | 3 | Wire the current createSavingsGoal domain port, a deterministic VM clock and a constructed history-repository spy whose Kotlin default-argument clock exists. Stub all recordContribution arguments, verify exact contribution metadata/increment calls, retain 250.0/225.0 totals and remove redundant startup ViewModels. Validator class PASS (7 tests), `vr-20260926-200230-19d14d9f`. Full-suite closure remains pending. |
| `AdvancedAnalyticsViewModelTest` | 2 | Install the Main test dispatcher and actively collect the WhileSubscribed stateIn flow. Observe initial EUR/null-timestamp state before changing settings; retain exact reload counts and cancel/join owned ViewModels. Validator class PASS (2 tests), `vr-20260926-195221-7ae69a53`. Full-suite closure remains pending. |
| `BackupRestoreViewModelPrivacyDenialTest` | 2 | Explicit setting-denial code, real header preflight and repository reachability/success assertions; default fail-closed code tested separately. Authored / NOT RUN. |
| `ClipboardAmountParserTest` | 2 | Replace Android ClipData.newPlainText JVM-null construction with strict ClipData/Item boundary mocks. The actual parser still processes the full grouped token and rejects the malformed partial-tail case; exact amount/null assertions remain. Validator class PASS (3 tests), `vr-20260926-200333-fb02313d`. Full-suite closure remains pending. |
| `CrossGroupIntegrationTest` | 2 | Pass the configured category repository and clock to real analytics engines, use real normalized history plus the typed current-period spend port, and make currency aggregates range-aware. Add an independent March purchase total of 474.0; retain cross-engine parity and positive/finite forecast assertions. Own/cancel the repository scope. Authored / NOT RUN. |
| `Pipeline4LifecycleGoldenTest` | 2 | Exercise the real RecurringRuleLifecycleCoordinator for creation/deactivation/reactivation/deletion, with real Room transaction runner, event writer, materializer and projection. Use a fixed future due date. Require generated rows/reminders before cleanup, then verify removal and regeneration. The previously vacuous passing delete test is strengthened too. Authored / NOT RUN. |
| `RecommendationCacheServiceTest` | 2 | Shared controllable clock, actual TTL-boundary exercise and Main reset authored. NOT RUN. |
| `RecommendationStateManagerTest` | 2 | Verify explicit expiry timestamp so MockK does not also record the default-argument clock call; exact repository counts retained. Authored / NOT RUN. |
| `AiSettingsViewModelTest` | 1 | Explicitly enable cloud opt-in for the permitted connection case and stub the strict connection-tester boundary. Require connection success and one tester call, and prove no key is stored before the explicit save. Denial coverage is not bypassed. Validator class PASS (8 tests), `vr-20260926-195807-49537836`. Full-suite closure remains pending. |
| `BackupRestoreMoneyIntegrityScenarioTest` | 1 | Replace retired 117-to-120 registration assumptions with the exact contiguous chain from DatabaseSchemaPolicy.MIGRATION_BASELINE through APP_DATABASE_SCHEMA_VERSION. Current disk truth is 145 through 149. Explicitly identify 117-to-120 as outside that chain; retain DAO/data assertions and remove the misleading current-schema-v120 label. Authored / NOT RUN. |
| `BackupRestoreRoundtripGoldenTest` | 1 | Strict currentMode fixture and typed barrier assertions authored; all enum modes covered separately. NOT RUN. |
| `BankStatementParserTest` | 1 | OPEN production finding: AM substring header matching drops Refund Amazon; original failing row/assertion retained. |
| `CategorizationPipelineIntegrationTest` | 1 | OPEN production finding: AmountUtils validates grouped integer text before removing the currency prefix, rejecting EUR-prefixed 1,000,000.00; original input/assertion retained. |
| `CsvImportRfc4180Test` | 1 | Capture both requests during import; assert count, order and exact embedded CRLF. Authored / NOT RUN. |
| `DailyAverageFlowTest` | 1 | Oracle repair authored; full March has 31 days, 30 spending days, one zero-spend day. NOT RUN. |
| `DbGuardPolicyFixtureTest` | 1 | NEEDS HUMAN: 406-to-412 ownership count drift is the six RP-14 RetentionModule entries in fea8cdfe. Do not silently raise the pinned guard count or regenerate policy; FG-06/FG-07 approval remains required. |
| `EffectiveAmountPipelineIntegrationTest` | 1 | Stub actual uncapped DAO method and use real normalized totals; 140.0 effective-spend oracle and exclusion fixtures retained. Authored / NOT RUN. |
| `ExportOptionsViewModelTest` | 1 | Explicit Unit return fixes JUnit method discovery; JSON parsing and all behavioral assertions retained. Whole-class behavior still needs validation. |
| `GoldenAnalyticsDatasetTest` | 1 | Independent UTC instant matches documented dataset clock; no global timezone override or tautological clock fixture. Authored / NOT RUN. |
| `InvestmentGoldenScenarioTest` | 1 | Provide the EUR homeCurrency flow consumed by the real investment repository. Keep the existing per-row data-quality assertions and fixed-time fixture. Authored / NOT RUN. |
| `MixedCurrencyCoreFinancialScenarioTest` | 1 | Supply transactionCount=1 on each explicitly constructed USD/GBP ConversionFailure. failedTransactionCount sums that metadata, not bucket count. Keep expected two failed transactions and the existing partial-state/amount/warning assertions. Authored / NOT RUN. |
| `MultiCurrencyAnalyticsTest` | 1 | FAIL in `vr-20260926-200442-9dbc1093` (3 passed / 1 failed): getRate stubs were unused by the latest-basis getLatestRateForPair path. Corrected to a strict latest-rate fixture; added independent EUR 140 total and single JPY MISSING_RATE controls. Exact JPY-only repository error remains unchanged. Follow-up NOT RUN. |
| `RawPersistencePolicyConsolidationTest` | 1 | Mask Kotlin comments only in the local ownership test; preserve literals conservatively and recursively scan executable template expressions, including nested quotes. Keep all three owned files and four selectors, route the existing negative fixture through the same scanner, and add four tests for nested comments, literal delimiters, templates and fail-closed malformed input. No exceptions/allowlists or production guard scripts are changed. Authored / NOT RUN. |
| `RecommendationDeduplicatorTest` | 1 | Align with the documented AIML-21 semantic-span contract: distinct one/seven/31-day spans retain all three ordered IDs. Add the complementary same-span generation-time-shift case retaining only the first ID. Authority: service/RecommendationDeduplicator.kt:60-106 and docs/development/FUTURE-WORK.md:230; this is not merely changing the old expected count to one. Authored / NOT RUN. |
| `TransactionLifecycleCoordinatorDbContractTest` | 1 | OPEN production finding: standard dedupe logs CREATE_DUPLICATE_SKIPPED inside the transaction, then the negative return enters insert-conflict handling and logs it again. Keep expected CREATED plus one duplicate event, not three. |
| `WorkerRestoreBarrierIdempotencyGoldenTest` | 1 | Strict currentMode fixture, real guarded write attempts and measured no-mutation oracle authored. NOT RUN. |


### Exact failed cases and log anchors

All line numbers refer to `build/validation-runs/vr-20260926-181440-25aa269d/stdout.log`. No case is deleted from the record when a candidate repair is authored.

| Class | Failed case | Log line | Current disposition |
|---|---|---:|---|
| `RawPersistencePolicyConsolidationTest` | rp15_owned_call_sites_contain_no_inline_storage_mode_selection | 18185 | Authored / NOT RUN (final remaining-inventory pass) |
| `BankStatementParserTest` | revolut refund row is classified as DEPOSIT | 18482 | OPEN production finding; assertion preserved |
| `DailyAverageFlowTest` | daily average uses periodDays not only daysWithSpending | 21772 | Authored / NOT RUN |
| `ReceiptProcessingPipelineTest` | unknown merchant categorized as Uncategorized | 22091 | Authored / NOT RUN (remaining-test clusters) |
| `ReceiptProcessingPipelineTest` | greek text normalization parses and categorizes correctly | 22123 | Authored / NOT RUN (remaining-test clusters) |
| `ReceiptProcessingPipelineTest` | ocr text parsed and categorized correctly | 22155 | Authored / NOT RUN (remaining-test clusters) |
| `BackupRestoreRoundtripGoldenTest` | data integrity preserved across restore mode transitions | 22280 | Authored / NOT RUN |
| `Pipeline4LifecycleGoldenTest` | create rule through repo generates occurrences reminders and planned rows | 22343 | Authored / NOT RUN (final remaining-inventory pass) |
| `Pipeline4LifecycleGoldenTest` | deactivate and reactivate restores occurrences reminders and planned rows | 22372 | Authored / NOT RUN (final remaining-inventory pass) |
| `WorkerRestoreBarrierIdempotencyGoldenTest` | write barrier is idempotent and blocks all worker operations during restore | 22445 | Authored / NOT RUN |
| `DbGuardPolicyFixtureTest` | manifest — ownership policy has exactly 406 entries | 22551 | NEEDS HUMAN (FG-06/FG-07); count unchanged |
| `MoneyBoundaryGuardTest` | guard flags blank currency CurrencyCode empty | 22634 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard flags CurrencyCode XXX sentinel | 22641 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard flags displayCurrency NA sentinel | 22648 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard flags emptyList fallback in dashboard | 22655 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard passes StaleRatePolicy forBasis | 22662 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard flags convertMultiple in new aggregate code | 22669 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard passes NormalizedForecastInput | 22678 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard passes MoneyAggregateResult Unavailable | 22685 | Authored / NOT RUN |
| `MoneyBoundaryGuardTest` | guard flags raw ExpenseSnapshot in synthesis | 22692 | Authored / NOT RUN |
| `CategorizationPipelineIntegrationTest` | integration - large amounts parsed correctly | 22737 | OPEN production finding; assertion preserved |
| `EffectiveAmountPipelineIntegrationTest` | end_to_end_pipeline_preserves_effective_amount_and_filters | 22938 | Authored / NOT RUN (remaining-test clusters) |
| `MultiCurrencyAnalyticsTest` | multi_currency_analytics_contract | 23013 | FAIL in vr-20260926-200442-9dbc1093; latest-lookup correction authored / NOT RUN |
| `GoldenAnalyticsDatasetTest` | dataset uses deterministic now via TimeProvider | 23096 | Authored / NOT RUN (remaining-test clusters) |
| `BackupRestoreMoneyIntegrityScenarioTest` | ALL_MIGRATIONS contains 117 to 120 migration steps | 25233 | Authored / NOT RUN (final remaining-inventory pass) |
| `InvestmentGoldenScenarioTest` | investment performance has dataQuality per row | 27430 | Authored / NOT RUN (final remaining-inventory pass) |
| `MixedCurrencyCoreFinancialScenarioTest` | multi-currency expenses produce correct MoneyAggregate with partial state | 27475 | Authored / NOT RUN (final remaining-inventory pass) |
| `TransactionLifecycleCoordinatorDbContractTest` | createExpense duplicate detected and skipped | 27633 | OPEN production finding; assertion preserved |
| `TransactionTargetedUpdateSideEffectsTest` | category DB state changes after update | 27675 | Authored / NOT RUN (remaining-test clusters) |
| `TransactionTargetedUpdateSideEffectsTest` | updateCategory dispatches budget side effect | 27702 | Authored / NOT RUN (remaining-test clusters) |
| `TransactionTargetedUpdateSideEffectsTest` | updateType dispatches side effects | 27729 | Authored / NOT RUN (remaining-test clusters) |
| `TransactionTargetedUpdateSideEffectsTest` | updateMerchant dispatches side effects and recurring reconciliation | 27756 | Authored / NOT RUN (remaining-test clusters) |
| `RecommendationCacheServiceTest` | getById removes expired entry from cache and fetches fresh | 28029 | Authored / NOT RUN |
| `RecommendationCacheServiceTest` | evictExpired removes only expired recommendations | 28062 | Authored / NOT RUN |
| `RecommendationDeduplicatorTest` | deduplicate preserves different date ranges | 28107 | Authored / NOT RUN (final remaining-inventory pass) |
| `RecommendationStateManagerTest` | refreshForUser same user reload after invalidate path publishes fresh data | 28254 | Authored / NOT RUN (remaining-test clusters) |
| `RecommendationStateManagerTest` | invalidate all path refreshes against empty active set after expireAll | 28639 | Authored / NOT RUN (remaining-test clusters) |
| `AiSettingsViewModelTest` | saveApiKey stores typed key after successful connection test | 31620 | PASS (targeted class, vr-20260926-195807-49537836) |
| `AdvancedAnalyticsViewModelTest` | uiState exposes latest rate timestamp from settings | 31900 | PASS (targeted class, vr-20260926-195221-7ae69a53) |
| `AdvancedAnalyticsViewModelTest` | uiState reloads when home currency changes | 31928 | PASS (targeted class, vr-20260926-195221-7ae69a53) |
| `BackupRestoreViewModelPrivacyDenialTest` | restore privacy denial converges on typed blocked state | 32048 | Authored / NOT RUN (remaining-test clusters) |
| `BackupRestoreViewModelPrivacyDenialTest` | backup privacy denial converges on typed blocked state without message matching | 32086 | Authored / NOT RUN (remaining-test clusters) |
| `BackupRestoreViewModelTest` | dismissRestartRequired clears the restart-required flag and unblocks writes | 32132 | PASS (targeted class, vr-20260926-195919-691252f1) |
| `BackupRestoreViewModelTest` | restoreBackup succeeds and sets restartRequired | 32166 | PASS (targeted class, vr-20260926-195919-691252f1) |
| `BackupRestoreViewModelTest` | restore maps extract-phase BackupTooLargeException to a size message | 32198 | PASS (targeted class, vr-20260926-195919-691252f1) |
| `CashFlowCalendarViewModelTest` | navigate month actions trigger calculator calls | 33044 | PASS (targeted class, vr-20260926-200025-d251724b) |
| `CashFlowCalendarViewModelTest` | upcoming bills count reflects repository result | 33321 | PASS (targeted class, vr-20260926-200025-d251724b) |
| `CashFlowCalendarViewModelTest` | partial flag from occurrence generation failure surfaces in state | 33408 | PASS (targeted class, vr-20260926-200025-d251724b) |
| `ExportOptionsViewModelTest` | initializationError | 33557 | Authored / NOT RUN (remaining-test clusters) |
| `SpendingMapViewModelPrivacyDenialTest` | permission race sets typed blocked state in addition to the snackbar | 33771 | Authored / NOT RUN (remaining-test clusters) |
| `SpendingMapViewModelPrivacyDenialTest` | gate denial sets typed blocked state | 33786 | Authored / NOT RUN (remaining-test clusters) |
| `SpendingMapViewModelPrivacyDenialTest` | permitted fetch leaves blocked state null | 33801 | Authored / NOT RUN (remaining-test clusters) |
| `SpendingMapViewModelPrivacyDenialTest` | fail-closed gate decision also sets typed blocked state | 33814 | Authored / NOT RUN (remaining-test clusters) |
| `ReceiptMatchingViewModelTest` | match receipt to expense | 33863 | PASS (targeted class, vr-20260926-200128-7edbc9b5) |
| `ReceiptMatchingViewModelTest` | skip receipt | 33964 | PASS (targeted class, vr-20260926-200128-7edbc9b5) |
| `ReceiptMatchingViewModelTest` | batch match all | 34067 | PASS (targeted class, vr-20260926-200128-7edbc9b5) |
| `ReviewViewModelPrivacyDenialTest` | receipt debug denial also converges on the typed state | 34283 | Authored / NOT RUN (remaining-test clusters) |
| `ReviewViewModelPrivacyDenialTest` | debug export denial sets typed blocked state and bounded text | 34310 | Authored / NOT RUN (remaining-test clusters) |
| `ReviewViewModelPrivacyDenialTest` | permitted debug export clears the blocked state and returns content | 34337 | Authored / NOT RUN (remaining-test clusters) |
| `SavingsGoalsViewModelTest` | progress update reflects in UI | 34438 | PASS (targeted class, vr-20260926-200230-19d14d9f) |
| `SavingsGoalsViewModelTest` | contributeToGoal uses atomic addToGoalAmount | 34477 | PASS (targeted class, vr-20260926-200230-19d14d9f) |
| `SavingsGoalsViewModelTest` | add goal updates state | 34518 | PASS (targeted class, vr-20260926-200230-19d14d9f) |
| `ClipboardAmountParserTest` | parseAmountFromClipboard does not partial-tail match grouped amount | 34656 | PASS (targeted class, vr-20260926-200333-fb02313d) |
| `ClipboardAmountParserTest` | parseAmountFromClipboard captures grouped amount as whole token | 34666 | PASS (targeted class, vr-20260926-200333-fb02313d) |
| `CsvImportRfc4180Test` | quoted field with embedded CRLF is preserved verbatim | 34702 | Authored / NOT RUN (remaining-test clusters) |
| `CrossGroupIntegrationTest` | budget forecast uses correct historical data and produces realistic prediction | 35103 | Authored / NOT RUN (final remaining-inventory pass) |
| `CrossGroupIntegrationTest` | complete month analysis produces consistent results across all engines | 35388 | Authored / NOT RUN (final remaining-inventory pass) |
| `CrossSourceVerificationTest` | spending pace percentage is consistent between insights and calculator | 35545 | Authored / NOT RUN (remaining-test clusters) |
| `CrossSourceVerificationTest` | monthly total is consistent across repository insights advanced and dashboard | 35638 | Authored / NOT RUN (remaining-test clusters) |
| `CrossSourceVerificationTest` | category totals are consistent across repository insights and dashboard | 35728 | Authored / NOT RUN (remaining-test clusters) |
| `CrossSourceVerificationTest` | daily average is consistent across advanced totals-engine and manual calculation | 35819 | Authored / NOT RUN (remaining-test clusters) |
| `GoldenMasterVerificationTest` | DIVERGENCE - linear pace projection differs from trend forecast projection | 36227 | Authored / NOT RUN (remaining-test clusters) |
| `GoldenMasterVerificationTest` | DIVERGENCE - trend-adjusted forecast differs from linear projection | 36493 | Authored / NOT RUN (remaining-test clusters) |
| `GoldenMasterVerificationTest` | PARITY - monthly total matches Insights Advanced and Totals engines | 36633 | Authored / NOT RUN (remaining-test clusters) |
| `GoldenMasterVerificationTest` | PARITY - daily average matches Advanced and Totals historical definitions | 37294 | Authored / NOT RUN (remaining-test clusters) |


### User-run validation for this pass

All commands below are **NOT RUN by the coding agent**. Inspect active validation first; if a result is RUNNING, use the existing run ID with the runner Wait action rather than launching another run. No concurrent validation. Omit `-Raw`. The wrapper's 150-minute wait does not override the profile's execution/no-output watchdogs.

```powershell
Set-Location -LiteralPath 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-27'
$env:ORG_GRADLE_PROJECT_testMaxHeapSize = '3g'
& powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/validation-runner.ps1 -Action List -Worktree .

function Invoke-ReviewGate {
    param([string]$Profile, [string]$TestFilter)
    $runnerArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', './scripts/vrun.ps1', '-Worktree', '.',
        '-Profile', $Profile, '-GradleDaemon', '-MaxTotalMinutes', '150')
    if ($TestFilter) { $runnerArgs += @('-TestFilter', $TestFilter) }
    & powershell @runnerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Validation stopped: $Profile $TestFilter (exit $LASTEXITCODE)"
    }
}

& {
    Invoke-ReviewGate -Profile compile
    foreach ($filter in @(
        '*MoneyBoundaryGuardTest',
        '*DailyAverageFlowTest',
        '*BackupRestoreRoundtripGoldenTest',
        '*WorkerRestoreBarrierIdempotencyGoldenTest',
        '*RecommendationCacheServiceTest',
        '*DDL512RegressionTest',
        '*P6BudgetCleanupTest',
        '*HistoricalSpendingDistributionBoundaryTest',
        '*ReceiptRepositoryStatementDuplicateTest'
    )) {
        Invoke-ReviewGate -Profile targeted-unit-test -TestFilter $filter
    }
}
```

Compile profile covers production sources; the first targeted-unit-test invocation also recompiles changed test sources. If any gate is nonterminal, missing, zero-match, stale, skipped, infra-error, failed or timed out, do not label it PASS. Keep full run IDs and result.json/log paths. Stop and diagnose a targeted failure before the next repair batch.

A later broader run is still **diagnostic, not a conclusive green/merge gate**, while known production findings and the other open cases remain. After inspecting each terminal result, these profiles may be run individually and serially:

```powershell
Invoke-ReviewGate -Profile static-guards
Invoke-ReviewGate -Profile unit-tests
Invoke-ReviewGate -Profile app-check
# Only with an attached Android device/emulator; previous real PDF/image coverage still needs this gate.
Invoke-ReviewGate -Profile connected-tests
```

The validator reports static-guards/app-check still red and connected-tests device-absent for the preceding cycle; those separate gate logs were not re-inspected in this bounded follow-up. No guard/baseline waiver is implied. Keep the parser AM/AMAZON row-loss finding and the export cancellation/uncontrolled-error finding in the preceding production-defect ledger. No commit, push, merge, or production edit was made in this pass. Independent strict/privacy review and user validation remain pending.

## Remaining-test repair clusters - 2026-09-26

Historical checkpoint of the preceding 12-class pass. Its 44-target / 31-open cumulative counts are superseded by the final remaining-inventory section below; its source diagnoses remain part of the record.

### Scope and evidence

Continued from the completed-suite inventory and the active diagnosis handoff, using source, scoped diffs, architecture/legal-path documents and persisted logs only. HEAD remains `2544298d`. This follow-up changes **12 test files and two diagnosis documents**, targeting **30 recorded failure events** in three clusters. No production code, schema, golden data, guard implementation, baseline, allowlist or test exclusion was changed in this follow-up. Existing unrelated worktree edits are preserved.

**Validation: NOT RUN.** No builds, tests, lint, guards, subagents or commits were run. Manual source/diff review and read-back were performed; that is not an independent strict/privacy gate and is not compilation or behavioral proof. The baseline remains 75 failures, not a predicted 31. The export class previously failed initialization, so making its methods discoverable may expose additional behavioral failures.

`T` = `app/src/test/java/com/yourname/expensetracker/`. Every repair below is authored / NOT RUN.

### Cluster 1 - analytics and currency fixtures (four classes / ten failures)

| File under T | Baseline failures | Repair and independent contract |
|---|---:|---|
| `verification/CrossSourceVerificationTest.kt` | 4 | Replace empty normalized-total mocks with the real MultiCurrencyRepository; wire the actual uncapped raw DAO methods, preserving ownership and half-open ranges. Use the fixture clock for anomalies. Assert 60 monthly spend, three transactions and a seven-calendar-day average of 40 independently, not just engine-to-engine equality. The March 15 noon pace excludes the retained March 25 future purchase: 30/15 versus February 40/28 gives 140%, with explicit spent/day/baseline assertions. Own and cancel the repository scope. |
| `verification/GoldenMasterVerificationTest.kt` | 4 | Share the real fixture repository, normalizer, settings and converter with forecasting. Supply BudgetRepository's typed current-period-spend port by delegating to real as-of normalization, with its actual argument order. Both daily/monthly totals use purchase-only effective spend: 738.49, while the 3000 deposit remains an asserted negative control. Use calendar boundaries across March DST and require a positive trend projection before asserting divergence. Own/cancel the repository scope; do not fake empty history to force divergence. |
| `integration/EffectiveAmountPipelineIntegrationTest.kt` | 1 | Stub getExpensesBetweenUncapped, which the repository actually calls, and use real MultiCurrencyRepository aggregation. Keep the 140.0 oracle and 0.0001 tolerance: 100 owned plus 40 personal share, excluding the retained not-mine expense and deposit. Own/cancel the repository scope. |
| `metrics/GoldenAnalyticsDatasetTest.kt` | 1 | The golden dataset intentionally uses UTC midnight, while forDate uses the host zone. Set the fake clock from the independent ISO instant 2026-04-01T00:00:00Z, not from the value under assertion. Keep exact epoch equality; no global timezone change. |

### Cluster 2 - lifecycle, import and test runtime (five classes / eleven failures)

| File under T | Baseline failures | Repair and preserved/strengthened contract |
|---|---:|---|
| `scenarios/TransactionTargetedUpdateSideEffectsTest.kt` | 4 | The relaxed write-barrier mock never executed its write lambda. Use the real NORMAL barrier, real TransactionSideEffectPlanner and PostCommitActionRunnerImpl over the existing real Room fixture. Verify budget invocation occurs outside the transaction, a budget completion event is emitted and no failed side-effect event occurs. Retain DB/key/dedupe assertions. Merchant/type changes verify the current atomic reconcileExpenseLinkAfterUpdate legal path, not obsolete unlink/link or dispatcher calls. This does not prove correctness of every external side-effect implementation. |
| `e2e/ReceiptProcessingPipelineTest.kt` | 3 | Plain JVM Uri.parse returned null before the mocked OCR boundary. Pass a non-null opaque mocked Uri to a strict OCR service; retain the real parser, merchant normalization, categorization and exact money/category/confidence assertions. The previously passing failure case now asserts the exact configured exception and one OCR call, so an unrelated Android-stub NPE cannot satisfy it. This is mocked-OCR pipeline coverage, not real image OCR coverage. |
| `util/CsvImportRfc4180Test.kt` | 1 | Replace repeated single-slot verification with a list captured during the two coordinator calls. Require exactly two calls, two requests in merchant order, and exact embedded CRLF/plain notes. No CSV behavior or assertion tolerance changes. |
| `ui/screens/export/ExportOptionsViewModelTest.kt` | 1 initialization event | Specify Unit for the JSON test whose final JSONObject expression inferred a non-void return. Keep the parse and all format/security assertions. JUnit discovery was the recorded blocker; none of the newly discoverable methods is claimed passing. |
| `service/RecommendationStateManagerTest.kt` | 2 | Pass nowMillis explicitly in the two expireOld verification expressions. Otherwise MockK records the default-argument TimeProvider.now() call as another verification target and applies the repository count to unrelated clock reads. Keep the original exact expire/load counts and refreshed-state assertions. |

### Cluster 3 - privacy ViewModel fixtures (three classes / nine failures)

| File under T | Baseline failures | Repair and preserved/strengthened contract |
|---|---:|---|
| `ui/screens/review/ReviewViewModelPrivacyDenialTest.kt` | 3 | Supply an emitting AiSettings flow for startup .first(), rather than an empty relaxed Flow. Use a strict debug exporter and retain the typed capability/code, bounded text, internal-detail non-disclosure and denial-to-allowed assertions. Cancel/join the ViewModel scope before Main reset. The exporter remains the policy boundary; no production privacy check is bypassed. |
| `ui/screens/map/SpendingMapViewModelPrivacyDenialTest.kt` | 4 | The public action launches on real IO, then fetchDeviceLocation launches on Main. runBlocking/Thread.sleep prevented StandardTestDispatcher from executing that inner launch. Use runTest and suspend on StateFlow; keep the bounded timeout on Dispatchers.Default so virtual time cannot outrun IO. Retain typed denial/fail-closed/permission-race and exact coordinate assertions, add zero location-provider calls when denied, and cancel/join the scope. No production dispatcher override or privacy-policy change. |
| `ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | 2 | Explicitly supply ENCRYPTED_BACKUP_DISABLED for the setting-denial fixtures; separately assert the exception's default PRIVACY_GATE_FAILURE contract. Replace the empty stream with a fresh valid v1 header so real preflight reaches the mocked repository. Verify restore invocation and typed denial; the formerly vacuous permitted case now requires repository execution, success text, no error and terminal non-restoring state. SecurityException must prevent repository invocation. Use a JUnit-owned temporary directory and cancel/join all ViewModel scopes. Header-only input is a boundary fixture, not an encrypted-backup integration test. |

**Accidental-pass controls:** keep future purchases/deposits/not-mine data in the analytics fixtures; disallow zero-vs-zero/zero-trend equivalence; require the intended OCR exception; require actual successful restore rather than merely a null privacy flag. No test was deleted, ignored or marked passing by this work.

### Production follow-ups found during this source review - NOT repaired

`M` = `app/src/main/java/com/yourname/expensetracker/`. These are source-identified findings, not newly executed reproductions and not evidence that these defects caused all baseline failures. They need owning-lane review and regression coverage; they must not disappear when fixture tests become green.

| Finding | Evidence under M | Required follow-up |
|---|---|---|
| Side-effect cancellation can be swallowed at event emission | `domain/sideeffect/PostCommitActionRunnerImpl.kt:28-35` catches Exception from started() and continues to action.execute(); completed/skipped/failed event handlers also catch Exception. CompositeSideEffectEventWriter does rethrow CancellationException, so the runner can undo that guarantee. | Test cancellation from each event-writer phase, including that cancellation from started prevents action execution and later actions. Preserve post-commit mutation semantics; require cancellation/diagnostics review before a production repair. |
| Uncontrolled side-effect failure details | `domain/transaction/lifecycle/TransactionSideEffectPlanner.kt:184-186,220-222,379-381` puts e.message into FailedRetryable.reason. The runner forwards outcome.reason to eventWriter.failed (`PostCommitActionRunnerImpl.kt:93-97`); DiagnosticSideEffectEventWriter passes it to failure metadata (`domain/sideeffect/DiagnosticSideEffectEventWriter.kt:69-72`). Runner catch sites also pass Throwable to Timber. | Replace arbitrary failure detail with bounded owned codes through the proper diagnostics path, and test sensitive-message non-disclosure. This establishes an uncontrolled value entering the pipeline, not a demonstrated persisted leak; inspect downstream metadata sanitization/storage before claiming leak impact. |
| Map denial may remain stale after a later successful GPS fetch | `ui/screens/map/SpendingMapViewModel.kt:811-831` sets gpsPrivacyBlocked on denial; the success copy updates coordinates/snackbar but does not clear that field, and onCenterOnMeRequested does not clear it first. | Add same-ViewModel denied-then-allowed coverage, including permission-race recovery, and review intended state-clearing semantics. This pass preserves the existing fresh-ViewModel allowed test; it does not cover or fix this transition. |
| Backup reason-code boundary accepts arbitrary exception text | `domain/privacy/PrivacyDeniedException.kt:13-16` accepts an unrestricted String as reasonCode, and `ui/screens/backup/BackupRestoreViewModel.kt:283-297` copies error.message into the typed state's reasonCode without membership validation. | Add an unrecognized/sensitive reason negative control and define a bounded fallback at the owning boundary. This is a source-identified hardening gap, not proof that current callers leak financial data. The valid-code fixtures do not exonerate this boundary. Existing Throwable logging/cancellation concerns also require the owning privacy review. |

The earlier AM/AMAZON parser row-loss finding and export cancellation/uncontrolled-error finding remain open. The privacy ownership guard's comment false positive also remains: do not use the executable-template-erasing sanitizer, broaden selectors/baselines, or treat missing guard scope as PASS (FG-03/FG-06/FG-07/FG-23).

### Cumulative queue and review status

The updated class queue and exact-case table above retain **all 75 events**. Across the preceding pass plus this one, **44 cases / 17 classes have candidate repairs, all NOT RUN**. **31 cases / 19 classes still have no authored repair in these two passes**:

- Three each: BackupRestoreViewModelTest, CashFlowCalendarViewModelTest, ReceiptMatchingViewModelTest, SavingsGoalsViewModelTest.
- Two each: AdvancedAnalyticsViewModelTest, ClipboardAmountParserTest, CrossGroupIntegrationTest, Pipeline4LifecycleGoldenTest.
- One each: AiSettingsViewModelTest, BackupRestoreMoneyIntegrityScenarioTest, BankStatementParserTest, CategorizationPipelineIntegrationTest, DbGuardPolicyFixtureTest, InvestmentGoldenScenarioTest, MixedCurrencyCoreFinancialScenarioTest, MultiCurrencyAnalyticsTest, RawPersistencePolicyConsolidationTest, RecommendationDeduplicatorTest, TransactionLifecycleCoordinatorDbContractTest.

Strict self-review checked source signatures, legal-path wiring, negative controls, time/money semantics, cancellation of owned fixture scopes, and scoped diffs/read-back. No independent reviewer/guardian verdict is claimed. Runtime and compilation may reveal further fixture gaps or production findings; do not silently loosen the tests if they fail.

## Final remaining-inventory repairs - 2026-09-26

Historical pre-sweep checkpoint. Its NOT RUN status is superseded for the seven validated classes by the latest-lookup follow-up below; the source diagnoses and unresolved production findings remain in force.

### Scope and current disposition

Reviewed the remaining 31 recorded cases in 19 classes against current source and their persisted failure signatures. Authored candidate repairs for **27 cases in 15 test files**; the other **four cases** are explicitly dispositioned below rather than made to pass. Together with the previous 44 targets, the current total is **71 of 75 baseline failures in 32 of 36 classes with candidate repairs, all NOT RUN**. All 75 original case names and stdout anchors remain in the inventory. Renamed current test methods are linked by their class and rationale, not removed from the historical failure record.

No builds, tests, Gradle, lint or guards were executed. No subagents or commits were used. Production Kotlin was fingerprinted at the start and end of source review and was unchanged during this pass; all authored code changes are test-side. Earlier production changes already present in the worktree remain outside this statement. Policy manifests, production guard scripts, golden values and test exclusions were not changed. The local privacy ownership-test scanner is a specifically reviewed test-side guard repair, not a baseline/allowlist expansion.

`T` = `app/src/test/java/com/yourname/expensetracker/`. Every row is authored / NOT RUN.

| File under T | Baseline failures targeted | Repair and retained contract |
|---|---:|---|
| `ui/screens/analytics/AdvancedAnalyticsViewModelTest.kt` | 2 | Install the Main test dispatcher and actively collect the WhileSubscribed stateIn flow. Observe initial EUR/null-timestamp state before changing settings; retain exact reload counts and cancel/join owned ViewModels. |
| `ui/screens/aisettings/AiSettingsViewModelTest.kt` | 1 | Explicitly enable cloud opt-in for the permitted connection case and stub the strict connection-tester boundary. Require connection success and one tester call, and prove no key is stored before the explicit save. Denial coverage is not bypassed. |
| `ui/screens/backup/BackupRestoreViewModelTest.kt` | 3 | Use non-null opaque Uri fixtures at the mocked repository boundary, a JUnit-owned cache directory and an explicit null metadata cursor. Keep the real valid-header preflight, restart/write-unblocking and extract-size assertions. Cancel/join owned ViewModels. |
| `ui/screens/cashflow/CashFlowCalendarViewModelTest.kt` | 3 | Let each navigation request complete before issuing the next; assert both month boundaries and exactly three calculations. Do not discard an already-loaded StateFlow snapshot and wait for a nonexistent replacement. Preserve upcoming-count/partial-generation assertions and cancel superseded fixtures. |
| `ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | 3 | Stub and verify the current ReceiptLinkService and ReceiptMatchLifecycleService legal boundaries, not obsolete repository mutations. CompletableDeferred gates make per-receipt and batch busy states observable. Require terminal success, exact match/suggestion calls and zero legacy mutation calls; cancel/join owned ViewModels. |
| `ui/screens/savings/SavingsGoalsViewModelTest.kt` | 3 | Wire the current createSavingsGoal domain port, a deterministic VM clock and a constructed history-repository spy whose Kotlin default-argument clock exists. Stub all recordContribution arguments, verify exact contribution metadata/increment calls, retain 250.0/225.0 totals and remove redundant startup ViewModels. |
| `ui/util/ClipboardAmountParserTest.kt` | 2 | Replace Android ClipData.newPlainText JVM-null construction with strict ClipData/Item boundary mocks. The actual parser still processes the full grouped token and rejects the malformed partial-tail case; exact amount/null assertions remain. |
| `integration/MultiCurrencyAnalyticsTest.kt` | 1 | Give CurrencyConverter the same fixed clock as the repository and fresh USD/EUR rates. Preserve the missing JPY rate and require the exact JPY-only missing-rate message, not any generic error. Retain aggregate-DAO versus uncapped-row verification and own/cancel all repository scopes. |
| `scenarios/InvestmentGoldenScenarioTest.kt` | 1 | Provide the EUR homeCurrency flow consumed by the real investment repository. Keep the existing per-row data-quality assertions and fixed-time fixture. |
| `scenarios/MixedCurrencyCoreFinancialScenarioTest.kt` | 1 | Supply transactionCount=1 on each explicitly constructed USD/GBP ConversionFailure. failedTransactionCount sums that metadata, not bucket count. Keep expected two failed transactions and the existing partial-state/amount/warning assertions. |
| `verification/CrossGroupIntegrationTest.kt` | 2 | Pass the configured category repository and clock to real analytics engines, use real normalized history plus the typed current-period spend port, and make currency aggregates range-aware. Add an independent March purchase total of 474.0; retain cross-engine parity and positive/finite forecast assertions. Own/cancel the repository scope. |
| `scenarios/BackupRestoreMoneyIntegrityScenarioTest.kt` | 1 | Replace retired 117-to-120 registration assumptions with the exact contiguous chain from DatabaseSchemaPolicy.MIGRATION_BASELINE through APP_DATABASE_SCHEMA_VERSION. Current disk truth is 145 through 149. Explicitly identify 117-to-120 as outside that chain; retain DAO/data assertions and remove the misleading current-schema-v120 label. |
| `golden/Pipeline4LifecycleGoldenTest.kt` | 2 | Exercise the real RecurringRuleLifecycleCoordinator for creation/deactivation/reactivation/deletion, with real Room transaction runner, event writer, materializer and projection. Use a fixed future due date. Require generated rows/reminders before cleanup, then verify removal and regeneration. The previously vacuous passing delete test is strengthened too. |
| `service/RecommendationDeduplicatorTest.kt` | 1 | Align with the documented AIML-21 semantic-span contract: distinct one/seven/31-day spans retain all three ordered IDs. Add the complementary same-span generation-time-shift case retaining only the first ID. Authority: service/RecommendationDeduplicator.kt:60-106 and docs/development/FUTURE-WORK.md:230; this is not merely changing the old expected count to one. |
| `domain/privacy/RawPersistencePolicyConsolidationTest.kt` | 1 | Mask Kotlin comments only in the local ownership test; preserve literals conservatively and recursively scan executable template expressions, including nested quotes. Keep all three owned files and four selectors, route the existing negative fixture through the same scanner, and add four tests for nested comments, literal delimiters, templates and fail-closed malformed input. No exceptions/allowlists or production guard scripts are changed. |

**Coverage limits:** mocked ViewModel ports do not prove service persistence, cloud network behavior, or real encrypted-backup restore. The savings test verifies incremental port calls and observable state, not concurrent Room atomicity. MixedCurrencyCoreFinancialScenarioTest constructs its aggregate explicitly even though it seeds Room; it is metadata-contract coverage, not a real converter integration. The migration change checks registrations, not migration execution or the safety of destructive fallback for unsupported schemas. Pipeline4 now exercises actual lifecycle writes and repairs the formerly vacuous deletion check. The new deduplication and scanner controls add tests beyond the 75 baseline failure events; the baseline count is not a count of all newly authored assertions.

### Four cases not repaired by changing tests

`M` = `app/src/main/java/com/yourname/expensetracker/`. These source diagnoses explain the recorded observations; no new runtime reproduction was executed.

| Case | Evidence and disposition | Required owning-lane action |
|---|---|---|
| `BankStatementParserTest.revolut refund row is classified as DEPOSIT` | `M/domain/receipt/BankStatementParser.kt:53,316-318`: header token AM is tested with contains against the entire uppercased row, so it matches AMAZON and discards Refund Amazon before transaction classification. Original merchant and expected deposit remain unchanged. | Repair header/transaction discrimination without losing real header handling; cover embedded AM/PM, genuine time/header markers and refund classification. Do not rename the merchant to avoid the defect. |
| `CategorizationPipelineIntegrationTest.integration - large amounts parsed correctly` | `M/domain/util/AmountUtils.kt:97-105,139,159-167` validates the comma-grouped integer portion while its EUR symbol is still attached. The first group is not all digits, so the legitimate currency-prefixed million amount returns null before final currency cleanup. Keep the original currency-bearing input and non-null assertion. | Normalize permitted currency/sign wrappers before strict grouping validation without admitting malformed grouping. Add exact-value and malformed/prefix/sign regression cases. The same early-rejection branches also interpolate raw amountStr into Timber warnings; replace uncontrolled financial input in diagnostics with bounded codes and review downstream logging. |
| `TransactionLifecycleCoordinatorDbContractTest.createExpense duplicate detected and skipped` | `M/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:647-651,719-754,2471-2509`: the STANDARD dedupe branch writes CREATE_DUPLICATE_SKIPPED, returns a negative duplicate ID, then the outer insertedId <= 0 conflict path resolves the same row and writes another duplicate event. This accounts for CREATED plus two duplicate events versus the expected two total. The assertion is deliberately NOT raised to three. | Distinguish an already-handled duplicate from an insert-conflict outcome so event ownership is singular. Preserve atomicity, dedupe resolution, correlation/source-link policy and cancellation, with standard/bulk/external-ID/conflict/race coverage. |
| `DbGuardPolicyFixtureTest.manifest - ownership policy has exactly 406 entries` | The pinned assertion still expects 406; the active policy has 412. Commit `fea8cdfe` adds six RP-14 exact entries under RetentionModule.provideRetentionTargets: operationRunDao.purgeTerminalRunsWithEvents; operationRunEventDao.deleteOrphanEventsOlderThan; privacyAuditDao.deleteOlderThan; receiptEventDao.deleteOlderThan; transactionEventDao.nullSnapshotsOlderThan; transactionEventDao.deleteOlderThan. This is an identified policy delta, not an unexplained count. | Obtain the explicit FG-06/FG-07 owning approval/reconciliation before changing the pinned guard expectation. Existing committed policy is not inferred to be human approval. Do not regenerate policy, broaden an allowlist or derive the expected count from the file under test. |

### Additional production/coverage follow-ups retained

- AIML-21 explicitly intends semantic recommendation deduplication (`M/service/RecommendationDeduplicator.kt:60-106`; `docs/development/FUTURE-WORK.md:230`). The repaired tests exercise distinct semantic spans and generation-time shifts separately. The implementation still aliases distinct absolute/custom windows with the same duration bucket; if those must coexist, that is a separate product-contract/production change, not grounds for pretending timestamp identity is the current documented contract. Its duplicate log at line 49 includes a signature whose fields can include merchant/minAmount/maxAmount; review diagnostic sanitization before claiming it is privacy-safe. No persisted leak was demonstrated here.
- Source review found broad exception handling/uncontrolled UI error text in `AdvancedAnalyticsViewModel.kt:67-68` and `ReceiptMatchingViewModel.kt:183-185,353-354`, and an optional-load runCatching in `CashFlowCalendarViewModel.kt:163`. Cancellation and bounded-error negative controls need owning-lane review; the positive fixture repairs are not proof of those failure-path properties. Do not confuse Flow.catch's cancellation transparency with an ordinary try/catch around a suspend call.
- Previously documented parser/export, post-commit event cancellation, side-effect diagnostic detail, map denial recovery and backup reason-code findings remain open. The privacy ownership comment false positive now has a test-local template-aware candidate repair plus negative controls, but it is NOT validated or waived.

### Review and handback status

Manual source-signature, legal-path, fixture-lifetime, money/time-oracle and scoped-diff review/read-back were performed. No independent strict/privacy/guardian PASS is claimed; compilation and behavior still require user validation. A targeted failure must be diagnosed, not silently relaxed. Four known unresolved baseline cases and the previously reported static/app-check debt mean a completed full run can provide a new trustworthy inventory, but this is **not yet a conclusive green/merge gate**.

The exact baseline remains `vr-20260926-181440-25aa269d` with 75 failed events. **71 authored targets is not 71 passes, and four unrepaired cases is not a prediction of the next failure total.** No commit, push or merge is authorized or performed.

## MultiCurrency latest-lookup correction and sweep resume - 2026-09-26

### Verified validation handback

Read the existing durable result.json and stdout.log files; no validation was executed by the coding agent. All nine inspected results have matching start/end fingerprints at revision 2544298d. The reported eight successful gates are **one compile plus seven test classes**, not eight test-class passes. The privacy-denial-specific classes later in the list were not reached by this sweep.

| Gate / class | Full run ID | Persisted result |
|---|---|---|
| `compile` | `vr-20260926-195154-ff840103` | PASS |
| `AdvancedAnalyticsViewModelTest` | `vr-20260926-195221-7ae69a53` | PASS - 2 passed / 0 failed / 0 skipped |
| `AiSettingsViewModelTest` | `vr-20260926-195807-49537836` | PASS - 8 passed / 0 failed / 0 skipped |
| `BackupRestoreViewModelTest` | `vr-20260926-195919-691252f1` | PASS - 17 passed / 0 failed / 0 skipped |
| `CashFlowCalendarViewModelTest` | `vr-20260926-200025-d251724b` | PASS - 10 passed / 0 failed / 0 skipped |
| `ReceiptMatchingViewModelTest` | `vr-20260926-200128-7edbc9b5` | PASS - 4 passed / 0 failed / 0 skipped |
| `SavingsGoalsViewModelTest` | `vr-20260926-200230-19d14d9f` | PASS - 7 passed / 0 failed / 0 skipped |
| `ClipboardAmountParserTest` | `vr-20260926-200333-fb02313d` | PASS - 3 passed / 0 failed / 0 skipped |
| `MultiCurrencyAnalyticsTest` | `vr-20260926-200442-9dbc1093` | FAIL - 3 passed / 1 failed / 0 skipped |

The seven class passes cover **17 of the original 75 failing cases**. The other 54 authored targets still require validation of their current repairs, including this one reworked failing case; four baseline cases remain intentionally unrepaired for the documented production/policy reasons. No new full-suite failure total is inferred from targeted runs.

### Root cause and narrow correction

This was a missed **fixture API mismatch in the previous patch**, not a newly justified two-pair expectation. `ExchangeRateContracts.kt:25-28` declares getRate and getLatestRateForPair as separate methods. `CurrencyConverter.convertMultiple` selects LATEST_AVAILABLE (`CurrencyConverter.kt:547-563`), and convertOutcome uses **getLatestRateForPair** for direct and EUR fallback lookups (`CurrencyConverter.kt:413-429`). The fixture only stubbed **getRate**, so its explicit fresh USD rate was not consumed. Correcting the clock/timestamps alone could not fix the unused stub.

Only `app/src/test/java/com/yourname/expensetracker/integration/MultiCurrencyAnalyticsTest.kt` changes in this code correction:
- Replace the relaxed ExchangeRateStore with a strict mock and explicitly stub the actual latest-pair lookups for USD/EUR, EUR/EUR and the deliberately unavailable JPY/EUR pair.
- Before the repository assertion, exercise the real converter and require an independently calculated **EUR 140** total (100 + 50 * 0.8), exactly one failed conversion, its JPY/EUR identity and 1000 amount, and **MISSING_RATE**, not an arbitrary/stale failure.
- Retain the exact repository error `Missing exchange rates: JPY→EUR`. Do not accept the extra USD pair or loosen the message to a substring assertion.
- Retain exactly one aggregate-DAO call and zero uncapped row scans; require zero legacy getRate and historical getRateAsOf calls on this latest-basis path. Other tests in the class are unchanged by this follow-up.

The repository already enumerates every failed pair; that behavior is not changed or declared defective by this signature. No production, schema, guard, baseline or allowlist edit is needed for this correction. The four documented unrepaired cases and other production follow-ups remain open.

A bounded source scan of the remaining named test files for getRate/getLatestRateForPair references found only a real exchangeRateDao.getRate read in BackupRestoreRoundtripGoldenTest, not another copy of this direct mock-stub mismatch. This is a limited source check, not runtime validation or proof that every remaining fixture is sound.

### Validation and review status

**New correction: NOT RUN.** Test source was read back and checked against the actual port/converter signatures. The passing results above belong to the validator's pre-correction snapshot; they do not validate this edit. No subagents, builds/tests/Gradle/lint/guards, commits, pushes or merges were performed by the coding agent. Validation remains with the user, as requested; the suggestion to let the coder run a gate is not treated as permission to override that arrangement.

Resume with the updated consolidated block below. Its first invocation recompiles changed test sources and reruns MultiCurrencyAnalyticsTest; stop on failure. Only after it passes should filters 9-37 proceed. Run the four unresolved-case diagnostics and broader profiles individually afterwards, retaining their actual terminal outcomes. Do not restart already RUNNING validation or interpret a known failure as a waiver.

### Consolidated user-run validation commands - NOT RUN

Updated resume plan: retry MultiCurrencyAnalyticsTest (original filter 8), then run the remaining 29 filters (original 9-37). Compile and the first seven unchanged test classes already have recorded PASS results. This is 30 serialized class invocations, followed by individually inspected known-red/broader diagnostics. Use one serialized validation owner and a quiescent worktree. First inspect the list; if any run is RUNNING, wait on its existing full run ID before starting anything else. Do not launch duplicate/parallel work. Omit -Raw; the known filter-fingerprint issue is not fixed here. MaxTotalMinutes bounds wrapper waiting, not the profile's execution/no-output watchdog.

```powershell
Set-Location -LiteralPath 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-27'
$env:ORG_GRADLE_PROJECT_testMaxHeapSize = '3g'
& powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/validation-runner.ps1 -Action List -Worktree .

function Invoke-ReviewGate {
    param([string]$Profile, [string]$TestFilter)
    $runnerArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', './scripts/vrun.ps1', '-Worktree', '.',
        '-Profile', $Profile, '-GradleDaemon', '-MaxTotalMinutes', '150')
    if ($TestFilter) { $runnerArgs += @('-TestFilter', $TestFilter) }
    & powershell @runnerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Validation stopped: $Profile $TestFilter (exit $LASTEXITCODE)"
    }
}

# Run only after confirming that no validation is active.
& {
    # Production compile already passed; this retry recompiles changed test sources.
    foreach ($filter in @(
        # Retry original filter 8, then continue the unrun filters 9-37:
        '*MultiCurrencyAnalyticsTest',
        '*InvestmentGoldenScenarioTest',
        '*MixedCurrencyCoreFinancialScenarioTest',
        '*CrossGroupIntegrationTest',
        '*BackupRestoreMoneyIntegrityScenarioTest',
        '*Pipeline4LifecycleGoldenTest',
        '*RecommendationDeduplicatorTest',
        '*RawPersistencePolicyConsolidationTest',
        # Preceding twelve-class pass, still awaiting validation:
        '*CrossSourceVerificationTest',
        '*GoldenMasterVerificationTest',
        '*EffectiveAmountPipelineIntegrationTest',
        '*GoldenAnalyticsDatasetTest',
        '*TransactionTargetedUpdateSideEffectsTest',
        '*ReceiptProcessingPipelineTest',
        '*CsvImportRfc4180Test',
        '*ExportOptionsViewModelTest',
        '*RecommendationStateManagerTest',
        '*ReviewViewModelPrivacyDenialTest',
        '*SpendingMapViewModelPrivacyDenialTest',
        '*BackupRestoreViewModelPrivacyDenialTest',
        # Previous five-class pass, still awaiting validation:
        '*MoneyBoundaryGuardTest',
        '*DailyAverageFlowTest',
        '*BackupRestoreRoundtripGoldenTest',
        '*WorkerRestoreBarrierIdempotencyGoldenTest',
        '*RecommendationCacheServiceTest',
        # Earlier validator-edit / architecture regression checks:
        '*DDL512RegressionTest',
        '*P6BudgetCleanupTest',
        '*HistoricalSpendingDistributionBoundaryTest',
        '*ReceiptRepositoryStatementDuplicateTest',
        '*BackupRestoreArchitectureGuardTest'
    )) {
        Invoke-ReviewGate -Profile targeted-unit-test -TestFilter $filter
    }
}
```

The compile profile covers production; the first targeted test run also recompiles changed test sources. Stop the chain on a failure and retain full run IDs/result.json/stdout/stderr. A zero-match, skipped, stale, missing, RUNNING, infrastructure-error or timed-out result is not PASS. These commands are handback instructions, not executed evidence.

The four deliberately unrepaired baseline cases remain in the full suite. To isolate them, run these **one at a time in separate invocations**, inspecting each terminal result before proceeding. A reported failure remains FAIL, not an approved baseline or waiver; do not bypass a RUNNING/timeout/infra result. The strict helper stops at a failure, so do not paste this as a stop-on-error chain expecting all four to execute automatically.

```powershell
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*BankStatementParserTest'
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*CategorizationPipelineIntegrationTest'
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*TransactionLifecycleCoordinatorDbContractTest'
Invoke-ReviewGate -Profile targeted-unit-test -TestFilter '*DbGuardPolicyFixtureTest'
```

After targeted results have been inspected, run any broader diagnostics **individually and serially**, inspecting each terminal result. Known open cases and guard debt mean this is not yet a conclusive green/merge gate:

```powershell
Invoke-ReviewGate -Profile static-guards
Invoke-ReviewGate -Profile unit-tests
Invoke-ReviewGate -Profile app-check
# Only with an attached Android device/emulator; real PDF/image coverage remains required.
Invoke-ReviewGate -Profile connected-tests
```

Nothing was committed, pushed or merged. No production change was authored by this follow-up. The uncommitted worktree already contains earlier production changes; this test-only statement does not describe the entire branch diff.
