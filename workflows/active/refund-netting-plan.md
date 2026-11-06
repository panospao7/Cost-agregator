# refund-netting: slice plan (feature-planner, 2026-11-06, base 80251ea3 + docs ffb6dfae)

Tier T3 (owner items 143/144). Each slice gets a Sonnet writer, a separate test-author and one combined guardian. reviewer-strict runs at the merge. Spec: `docs/features/refund-netting/` (domain-spec, decisions, ux-flows, impact-map).

## UX defaults recorded
- Q1: `RefundLinkUseCase.linksOf(expenseId): RefundLinks`. It is Available(list of linkId, counterpartId, appliedAmount, currency, createdAt) or Unavailable(READ_BLOCKED|READ_FAILED). Backed by DAO `getForExpense` (S1) and the use case (S3).
- Q2: the menu says "This is a refund of…"; the notice button says "Apply to purchase".
- Q3: chips show no counterpart name.
- Q4: no day-header explanation.
- Q5: one generic empty-picker text.
- Q6: one group refusal string.
- Q7: unlink wording per link. "Go back to {gross}" only when it is the last link, otherwise "show {net}". This is string #43.
- Q8: always confirm, no Undo.
- Q10: the menu item shows on every unlinked eligible deposit.
- Q11: one shared notice composable.

## S1: schema, DAOs, backup (start here)
- **Source:**
  - new `ExpenseRefundLink` entity;
  - `Expense.refundNettedAmount` (default "0.0");
  - new `ExpenseRefundLinkDao`: restricted `insert` and `deleteById`; reads `getById`, `getByRefund`, `getByPurchase`, `getForExpense`, plus counts;
  - `ExpenseDao.updateRefundNettedAmount` (restricted);
  - AppDatabase v153, `MIGRATION_152_153` (registered), DatabaseSchemaPolicy, DaoModule, schema 153.json;
  - BackupVerifier: TIER_1_EXACT, required from 153, expected 0 below 153, orphan and consistency checks;
  - policy rows.
- **Tests:**
  - RefundNettingMigrationTest (Robolectric 152→153);
  - MigrationRegistrationTest, ExpenseRefundLinkDaoTest, BackupVerifier*, DbGuardPolicyFixtureTest;
  - androidTest DatabaseMigrationTest and DatabaseMigrationMatrixTest are written now and run later on the emulator;
  - fix the pins on 152, including the copy in InstallmentPlanArchitectureGuardTest.
- **Done when:** existing rows have 0.0, the table is empty, the indices and FKs exist, aggregates are bit-identical, and the snapshot equals Room's export.
- **Validation:** targeted, then `migration-tests`, then db_access and known_good_state.

## S2a: formula and SQL switch (after S1)
- **Source:** `Expense.effectiveAmount`, SpendingInclusionSql, `ComparableHomeValue.snapshotValue`, ExpenseDao (38 SUM sites + `_E` + AVG/MIN/MAX + the :1459 percentile feed), SemanticAggregates (legacy hash), and a spending_predicate rule for aggregates over the inner constant (a strengthening).
- **Neutrality:** these must pass UNMODIFIED:
  - SpendingInclusionParityTest
  - ComparableHomeValueSqlParityTest and ComparableHomeValueBuilderParityTest
  - ExpenseDaoSpendingNeutralisationSqlTest
  - ExpenseRepositoryAssistantSqlGoldenTest
  - CrossEngineParityMatrixTest
  - MoneyContractTest
  - GoldenAnalyticsDatasetTest
  - HealthScoreGoldenTest
  - HomeVerdictParityTest
  - PipelineWiringParityTest
  - GroupSettlementBudgetOffsetGoldenTest
  - HistoricalSpendingDistributionBoundaryTest

## S2b: DTO copy sites (13 files, after S1)
- The sites are listed in the impact map. New test: RefundNettedAmountCopyParityTest.

## S3: write path (after S1; rebase on S2a before final validation)
- New: RefundNettingPolicy, RefundLinkUseCase (`link`, `unlink`, `findCandidates`, `linksOf`), RefundLinkedExpenseException.
- TLC: `linkRefund(refundId, purchaseId)`, `unlinkRefund(refundId)`, the delete cleanup, create forces 0.0, update keeps the column, `failIfRefundLinked` at the 3 installment-lock call sites.
- Also: UpdateKind and planner, internal detector helpers, the group REFUND_LINKED refusal, policy rows and pins.
- **Tests:**
  - RefundNettingPolicyTest
  - TransactionLifecycleCoordinatorRefundLinkTest (real Room; rollback; AE-18)
  - RefundLinkUseCaseTest, RefundLikelihoodDetectorTest
  - the group refusal test
  - RefundNettingArchitectureTest

## S4: UI, export, strings (after S2a, S2b and S3 are all merged)
- TransactionsScreen and ViewModel, the picker, details and confirm composables, export mapper and writers, and 43 strings (el/es DRAFT, plus the reworded notice).

## Parallelism
After S1, S2a, S2b and S3 can run in 3 lanes; their write sets are disjoint. ExpenseDao belongs to S1 (the setter) and S2a (the sums), never to S3.

## Deferred
Auto-link, split refunds, cross-currency links, link restore on undo or import, a rescue snapshot carrying the column.
