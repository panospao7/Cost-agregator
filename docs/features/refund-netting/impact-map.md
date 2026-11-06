# refund-netting: impact map (impact-mapper, 2026-11-06, read at 80251ea3)

**Tier T3:** schema 152→153, money, backup/restore, TLC lifecycle, guards, and about 15 cross-segment contracts. Paths are under `app/src/main/java/com/yourname/expensetracker/`.

## Segments
- data/database: entity, DAO, migration
- domain/transaction: TLC, policy, use case
- data/backup: BackupVerifier, SemanticAggregates
- domain/export
- groups: GroupTransactionCoordinator
- the money, analytics, dashboard and forecast DTO mappers
- ui/screens/transactions
- DI (DaoModule)
- static guards

## Legal paths
- New: link, unlink, and the cleanup on delete. They replace the LEGAL_PATHS REFUNDS block (:180-188).
- TLC `deleteExpense` (:3031-3106) is extended inside its existing transaction.
- Group add-expense gets a REFUND_LINKED refusal.

## DTO copy sites that must carry `refundNettedAmount`
Otherwise netting is silently lost.
- `ExpenseRepository.kt:1272`
- `DashboardContractsAdapter.kt:192`, `DashboardPrimitives.kt:24` (→ ComputeDashboardWidgetsUseCase :642, SafeToSpend :1449)
- `ExpenseSnapshot.kt:16`, `ForecastInputAssembler.kt:168`
- `AnalyticsInputAssembler.kt:125`
- `AnalyticsCurrencyNormalizer.kt:57,83,431,451`
- `NormalizedAnalyticsInput.kt:40,103`
- `SingleCurrencyAnalyticsInput.kt:43`
- `MoneyNormalizationEngine.kt:320`
- `RecurringExpenseEngine.kt:316`
- `AnomalyAlertOrchestrator.kt:368`
- `AnalyticsViewModel.kt:1343`
- `ExpenseExportMapper.kt:62`, `ExportTransaction.kt:74`

## Consumers
- **Totals that sum effectiveAmount:**
  - ExecuteFinancialQueryUseCase (:112,174,242,351,504,597)
  - the income path in ComputeMoneyRadarUseCase (:447-457) and AdvancedAnalyticsDashboard (:163,315)
  - RecurringIncomeTracker (:135), which the spec missed
  - the ComputeDashboardWidgetsUseCase income aggregate (:443-453)
- **Missed by the spec; they read effectiveAmount directly:**
  - TaxEstimator, BusinessExpenseReportGenerator, CarbonFootprintCalculator
  - TravelDetectionEngine, AreaSpendingEngine, LifestyleInflationDetector
  - SynthesisEngine, AnomalyDetector, BudgetForecastingEngine
  - SpendingMapViewModel, ReceiptMatchingViewModel, NotificationSubscriptionDetector
  - AutomatedSavingsRuleEngine (:98 reads raw amount for DEPOSIT)
- **Gross by design:** SpendingPersonalityClassifier (:515).

## ExpenseDao (INV-9)
- 39 single-line `SUM(${EFFECTIVE_AMOUNT_SQL})` sites, plus `_E_SQL` at :2511.
- Also AVG/MIN/MAX at :1402, :1979-81, :2002-04, :2024-26, :2105-07, :2128.
- List and percentile sites: :1459, :2047, :2060.

## Schema and migration
- New: entity `ExpenseRefundLink` and `ExpenseRefundLinkDao`.
- AppDatabase: entities list, accessor, version const :33, @Database :112. DaoModule.
- `Expense.refundNettedAmount` defaultValue "0.0".
- `MIGRATION_152_153`: manual (precedent MIGRATION_151_152), registered in the list at :281.
- Schema `153.json`.
- Tests: androidTest `DatabaseMigrationTest` (:4252+ precedent), `DatabaseMigrationMatrixTest`, unit `MigrationRegistrationTest`.
- Backup:
  - BackupVerifier: version, TABLE_TIERS :84-154, requiredManifestTables :273-284, expectedCountsAfterMigration :316-343, verifySemanticIntegrity :702.
  - SemanticAggregates: hash :339; DEFINITION_CHANGED compare :613, so legacy-hash code is needed; check DECLARED_MIGRATION_INVALIDATIONS and MIN_SOURCE_SCHEMA_VERSION.
- Rescue: `FinancialRescueCoordinator.kt:428-478` hardcodes columns, so rescued rows come back gross and unlinked. This is safe.

## TLC
- linkRefund/unlinkRefund, the delete cleanup (:3057-3103), and failIfRefundLinked beside :3002 (called at :2085, :2303, :2602).
- Keep the column on full-row updates (:1491, :1848). Force 0.0 on create.
- `TransactionUpdateKind.REFUND_NETTING` plus planner branches (:78, :125).
- `expenseDao.deleteAll()` paths are covered by the FK CASCADE.

## Group and detector
- GroupTransactionCoordinator :261/:940 and SharedExpenseDataPortAdapter :95.
- RefundLikelihoodDetector :31, called from TransactionsScreen :159-162, :677, :891.

## Workers, permissions and guards
- Workers: none. Permissions: none.
- Guards:
  - FG-13/FG-12: db_ownership_policy rows per new call site, plus the pytest pins.
  - known_good_state review.
  - spending_predicate_usage: a rule for SUM/AVG/MIN/MAX on the inner constant.
  - SpendingInclusionParityTest.
  - FG-14, FG-16, FG-17, FG-21, FG-23, FG-08.

## Decisions taken (orchestrator, item 143/144 defaults)
1. AVG/MIN/MAX merchant stats and the :1459 percentile feed switch to net, consistent with SUM.
2. D-16: accept the cash-flow running-balance drift across windows; document it.
3. Rescue does NOT carry the column (rescued rows stay gross and unlinked, which is safe; documented as L-4).
4. Migration tests: the androidTest migration suites need the emulator (owner question; the emulator is not restarted unprompted). The unit-level `*Migration*` tests run in the `migration-tests` profile.

## Slices
- **S1:** entity, DAO, DB registration, migration, schema 153, BackupVerifier, SemanticAggregates, migration tests, DAO policy rows.
- **S2:** Expense.effectiveAmount, SpendingInclusionSql, ComparableHomeValue, the ExpenseDao sites, the parity grid, the guard rule, and the DTO mapper columns (S2b split if large).
- **S3:** RefundNettingPolicy, RefundLinkUseCase, TLC, UpdateKind and planner, the group refusal, the detector, policy/known_good rows.
- **S4:** UI, the export column, and 42 strings (el/es DRAFT).

## Gates
room-migration-guardian, privacy-security-guardian, architecture-guardian, a separate test-author, and reviewer-strict at the merge.
