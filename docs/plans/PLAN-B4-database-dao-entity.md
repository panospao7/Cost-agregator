## Technical Plan

### 1. Objective & blast radius
- **Objective:** stabilize the full B.4 database/DAO/entity pipeline defined in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` lines 260-317, starting with the in-place `69→70` migration blocker and then landing forward-only schema, DAO, and repository fixes in dependency order.
- **In:** Room migrations, `AppDatabase.kt`, entity/DAO contracts under `app/src/main/java/com/yourname/expensetracker/data/database/`, related repositories, and only the directly affected domain/UI callers needed to keep B.4 green.
- **Out:** other Phase 2 epics, unrelated suite stabilization, and any planning inputs from deep-analysis/final-verification docs.
- **Blast radius:** `AppDatabase.kt`, entity files (`Budget`, `GroupMember`, `GroupExpense`, `Merchant*`, `BankConnection`, `RawNotification`, `AnomalyAlert`, `SubscriptionCandidate`, `BudgetForecast`, `SavingsGoal`, `MileageTracking`, `PendingReview`, `Expense`), DAOs, repositories for groups/category/merchant/email/receipt/savings/subscription/import, selected UI/domain files (`SharedExpenseGroupsViewModel`, `BudgetForecastingEngine`, `InvestmentTracker`, debug CSV import, amount formatting), exported schema JSON, and targeted androidTest/unit tests.
- **Execution rule:** per the playbook, **B.4 runs first and alone**; no other B.* lane should be active until B.4 is fully closed.
- **Assumptions / unknowns:**
  - Phase 2 gate (`A.1–A.10`) is already satisfied; if not, B.4 cannot start.
  - Batch 1 must patch `MIGRATION_69_70` **without** changing DB version `70`.
  - Existing user data may contain duplicates that must be deterministically cleaned before unique constraints are added.
  - Several B.4 items appear partially addressed in current source; each batch should confirm the live gap before editing to avoid churn.

### 2. Risk hotspots
- **Historical migration risk:** `MIGRATION_69_70` currently performs Keystore-backed token work during migration; DB-open failures here block every later B.4 batch.
- **Schema-version churn:** after Batch 1, every schema batch must be forward-only and must not stack on top of an unverified prior migration.
- **Duplicate-retention risk:** unique-index batches need explicit retention rules before migration SQL is written, especially for:
  - multiple active budgets,
  - multiple `isCurrentUser=1` members per group,
  - duplicate `group_expenses.expenseId` links,
  - merchant canonical/alias collisions,
  - duplicate pending subscription candidates,
  - overlapping active forecasts.
- **Cross-table invariant risk:** `GroupExpense.paidById` “same group” enforcement may require a trigger; if trigger behavior is not clearly safe, stop and split rather than guessing.
- **Key-format compatibility risk:** merchant location global keys currently appear split between `"global"` and `"<normalized>|global"` representations; fixes must preserve existing lookup callers.
- **Validation-environment risk:** full `androidTest` / full suite has known unrelated issues; targeted class-level evidence plus written waivers must be the default closeout path.
- **Rollback rule:** if a batch cannot be made green with its own targeted evidence, revert that batch’s files before proceeding; never build the next migration/version on a red baseline.

### 3. Dependency-ordered micro-batches
> **Versioning note:** Batch 1 patches version `70` in place. Later schema batches assume one new forward Room version per green schema batch (expected `71+`). Do not mint the next version until the current schema batch is verified.

#### Batch 1 — Historical migration blocker (`69→70`) with **no version bump**
- **Objective:** patch `MIGRATION_69_70` so legacy `69→70` upgrades no longer depend on a fragile Keystore path during DB open, while preserving the existing version-70 schema and the current `69→70` hardening intent.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/MigrationContractTest.kt`.
- **Constraints / do-not-touch boundaries:**
  - **Do not** change `@Database(version = 70)`.
  - **Do not** allocate `71.json` or add a new migration number here.
  - Preserve existing version-70 schema shape unless absolutely impossible.
  - Avoid broad `BankTokenCipher` refactors unless the migration cannot be stabilized otherwise.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.MigrationContractTest`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
- **Explicit stop boundary:** stop as soon as `69→70` can open safely in place and the targeted migration evidence/waiver is captured; do not start any forward schema version work yet.
- **Done when:** Batch 1 proves the historical upgrade path is no longer the epic blocker.

#### Batch 2 — Group transaction atomicity and orphan-prevention
- **Objective:** close the B11 transaction/orphan issues before schema tightening: move group validation/linking into one transactional path, remove the “system expense created first, group row later” orphan window, and fix false-success/no-op group transaction helpers.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`, `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt` (if a unified transaction path is introduced), `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt`, `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt`.
- **Constraints / do-not-touch boundaries:**
  - No Room version bump in this batch.
  - Do not change group table schema/indices/FKs here; schema hardening is next.
  - Keep signature churn narrow; prefer repository/coordinator boundaries over wide UI fallout.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.database.GroupTransactionCoordinatorTest"`
  - `./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.groups.SharedExpenseGroupsViewModelTest"`
- **Explicit stop boundary:** stop once create/add/delete group flows are transactionally safe and the orphaned system-expense path is removed or transactionally compensated.
- **Done when:** group lifecycle logic is atomic even before stronger DB constraints land.

#### Batch 3 — Group schema integrity migration (`70→71`)
- **Objective:** make group persistence deterministic: one current user per group, one non-null system-expense link per `group_expenses` row-set, and a safe DB-level strategy for payer/group consistency if feasible.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupMember.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/GroupMemberDaoTest.kt`, `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/71.json`.
- **Constraints / do-not-touch boundaries:**
  - Only group-related schema/invariant work in this version bump.
  - Define duplicate-retention rules before adding unique indices.
  - If same-group `paidById` cannot be enforced cleanly in bounded scope, stop and split a trigger-only follow-up instead of broadening this batch.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.dao.GroupMemberDaoTest`
- **Explicit stop boundary:** stop after the new group migration is green and group callers still compile.
- **Done when:** current-user lookup and system-expense linkage are no longer nondeterministic.

#### Batch 4 — Budget / recurring / category contract cleanup (`71→72`)
- **Objective:** fix budget nondeterminism and recurring misclassification: one active overall/category budget path, threshold constraints, correct `ManualRecurringExpense.isSubscription` behavior, active-only recurring reads, and the default-category seeding race / category read-safety if still present.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringExpenseDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/Category.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/BudgetDaoTest.kt`, `app/src/test/java/com/yourname/expensetracker/data/repository/CategoryRepositoryStressTest.kt` (or a new focused non-ignored sibling test), `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/72.json`.
- **Constraints / do-not-touch boundaries:**
  - Do not mix merchant/bank/email/subscription work into this migration.
  - Prefer transactional seeding/idempotent DAO behavior before introducing broad category-name uniqueness.
  - Keep recurring API semantics explicit: if `getAll()` changes to active-only, rename or document the contract accordingly.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "*CategoryRepository*"`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.dao.BudgetDaoTest`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
- **Explicit stop boundary:** stop once budget selection, recurring classification, and default seeding behavior are deterministic.
- **Done when:** budget/recurring/category callers no longer depend on `LIMIT 1` ambiguity or racy seeding.

#### Batch 5 — Merchant identity / location / correction migration (`72→73`)
- **Objective:** make merchant lookup deterministic and index-backed: canonical search-key uniqueness, alias normalized-key determinism, non-null merchant-location global-key consistency, and hot-path/tie-break fixes for `UserCorrection`.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCanonical.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantAlias.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantNormalizationDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantLocation.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/UserCorrection.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/MerchantNormalizationDaoTest.kt`, `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/73.json`.
- **Constraints / do-not-touch boundaries:**
  - Do not touch bank/email/alert/subscription code in this batch.
  - Preserve the cross-consumer merchant-key format already used by repository/domain callers.
  - Define deterministic retention rules before adding canonical/alias uniqueness.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "*MerchantKey*"`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.dao.MerchantNormalizationDaoTest`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
- **Explicit stop boundary:** stop after merchant lookup/global-area-key behavior is consistent end-to-end.
- **Done when:** merchant canonicalization no longer returns arbitrary rows or admits null-areaKey uniqueness loopholes.

#### Batch 6 — Bank / email / notification / alert hardening (`73→74`)
- **Objective:** close B15/B28 persistence leaks: clear credentials on bank disconnect, add `defaultCategoryId` FK, make email receipt dedupe non-destructive and receipt cardinality correct, replace nullable notification uniqueness loopholes, and add alert FK/index hardening.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/AnomalyAlertDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTest.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt`, `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/74.json`.
- **Constraints / do-not-touch boundaries:**
  - Do not mix importer/business-expense/presentation work here.
  - Keep email fingerprint behavior aligned with DAO uniqueness changes.
  - If nullable-notification dedupe needs an expression index or migration-only SQL, keep DAO null-safe existence checks aligned.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest"`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
- **Explicit stop boundary:** stop once overwrite/leak/orphan-dedupe issues are closed and covered.
- **Done when:** disconnect no longer preserves live tokens and duplicate email/notification ingest no longer destroys or bypasses persisted records.

#### Batch 7 — Subscription / forecast / supporting index wave (`74→75`)
- **Objective:** eliminate duplicate pending subscriptions and overlapping forecast ambiguity, and add the remaining supporting indexes for subscription usage and exchange-rate base scans.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt`, `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/SubscriptionUsageDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngineTest.kt`, `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/75.json`.
- **Constraints / do-not-touch boundaries:**
  - Keep this batch focused on subscription/forecast/index integrity only.
  - Align DAO dedupe semantics with `NotificationProcessingPipeline`; no read-then-insert races should remain.
  - Define deterministic retention for overlapping forecasts before adding uniqueness/exclusion logic.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "*BudgetForecastingEngineTest"`
  - `./gradlew :app:testDebugUnitTest --tests "*Subscription*"`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
- **Explicit stop boundary:** stop after pending-candidate and forecast reads are deterministic and the new index coverage is in place.
- **Done when:** duplicate pending subscription rows and arbitrary forecast selection are no longer possible.

#### Batch 8 — Financial / auxiliary contract wave (`75→76`)
- **Objective:** land the remaining DB-level guards from B12/B28/B29 scope: numeric CHECK coverage, savings-goal constraints, mileage impossible-state rejection, `PendingReview.suggestedType` persistence guard, and `Expense.splitTemplateId` FK hardening.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsGoal.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt` (if threshold checks were not fully closed in Batch 4), `app/src/main/java/com/yourname/expensetracker/data/database/entity/MileageTracking.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt`, `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/76.json`, plus new narrow invariant tests if no existing focused coverage is sufficient.
- **Constraints / do-not-touch boundaries:**
  - Confirm the exact 7-entity numeric-CHECK list before editing; do not invent new business rules beyond the registry.
  - Keep this batch schema-first; caller semantics land next.
  - If one invariant explodes scope, split it rather than widening the migration batch.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest`
- **Explicit stop boundary:** stop once impossible persisted states are rejected at the DB boundary.
- **Done when:** remaining low-level entity invariants are enforced by schema instead of caller discipline alone.

#### Batch 9 — DAO / repository semantics sweep (post-schema)
- **Objective:** finish the runtime B14 gaps that depend on stabilized schema: atomic savings updates, correct receipt-link state transitions, and the remaining business-expense query/receipt-detection issues.
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/SavingsGoalDao.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`, `app/src/test/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt`, `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDaoTest.kt`.
- **Constraints / do-not-touch boundaries:**
  - No new Room version in this batch unless a missed schema blocker is discovered.
  - Preserve A.1 effective-amount semantics; re-verify current SQL before editing so only still-live gaps move.
  - Keep caller changes minimal and local to savings/receipt paths.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.database.dao.ExpenseDaoBoundaryConsistencyTest"`
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.dao.ScannedReceiptDaoTest`
- **Explicit stop boundary:** stop after savings/receipt/business-expense runtime semantics are aligned with the hardened DB contract.
- **Done when:** contributions are no longer lost, linked receipts no longer stay `UNMATCHED`, and receipt-missing business queries no longer use the wrong proxy.

#### Batch 10 — Integration touchpoints and presentation closeout
- **Objective:** close the remaining non-schema B.4 items: remove the fresh-`Room.databaseBuilder` bypass in CSV import, fix investment “all-time” statistics, and unify `formattedAmount` behavior (effective amount, polarity, locale consistency).
- **Likely exact file scope:** `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt` (if importer needs DI-backed access), `app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/model/ExpenseWithCategory.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/model/ExpenseWithCategory_Extensions.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt` (only if formatter contract changes ripple), plus new narrow unit tests for investment/formatting/import if current coverage is absent.
- **Constraints / do-not-touch boundaries:**
  - No new schema work here.
  - Keep importer changes local to the debug/import path; do not refactor the whole DI graph unless required.
  - Unify formatting behavior without broad UI redesign.
- **Focused validation command(s):**
  - `./gradlew :app:compileDebugKotlin`
  - `./gradlew :app:testDebugUnitTest --tests "*InvestmentTracker*"`
  - `./gradlew :app:testDebugUnitTest --tests "*ExpenseWithCategory*"`
- **Explicit stop boundary:** stop once only docs/review/commit work remains.
- **Done when:** no direct importer-side Room bypass remains, all-time stats are truly all-time, and amount-formatting surfaces agree.

### 4. Validation strategy
- **Per-batch minimum gate:** `./gradlew :app:compileDebugKotlin`.
- **Schema batches:** add `./gradlew :app:compileDebugAndroidTestKotlin` plus filtered `connectedDebugAndroidTest` class runs one class at a time.
- **Unit batches:** run only the smallest relevant `--tests` selectors for the touched repository/domain/UI files.
- **Migration evidence rule:** for every new forward version, extend `DatabaseMigrationTest.kt` and rerun the filtered migration class; keep `MigrationContractTest.kt` as the snapshot-independent guardrail for critical legacy behavior.
- **Serialized verification lane:** because B.4 is the only active Phase 2 lane, keep Gradle/test execution serialized; do not overlap long-running verification tasks.
- **Final epic gate (targeted, not full-suite-dependent):**
  - `./gradlew :app:compileDebugKotlin`
  - focused unit suites from Batches 2, 4, 7, 9, and 10
  - `./gradlew :app:compileDebugAndroidTestKotlin`
  - filtered `connectedDebugAndroidTest` runs for:
    - `com.yourname.expensetracker.data.database.MigrationContractTest`
    - `com.yourname.expensetracker.data.database.DatabaseMigrationTest`
    - changed DAO androidTest classes (`GroupMemberDaoTest`, `BudgetDaoTest`, `MerchantNormalizationDaoTest`, `ScannedReceiptDaoTest`, and any new focused DAO tests)
- **Waiver rule:** if device availability or unrelated `androidTest` packaging issues block a targeted class run, capture:
  - `compileDebugAndroidTestKotlin`,
  - the exact blocker,
  - why it is unrelated to the changed batch,
  - and a written waiver in the review doc.
- **Full-suite rule:** full `:app:testDebugUnitTest` / full `androidTest` may be run as informational evidence only; they are not the primary gate for B.4 because unrelated failures are already assumed possible.

### 5. Documentation / closeout sequence
- [x] Keep this plan updated batch-by-batch as a living execution checklist while B.4 is active.
- [x] After each green schema batch, update the corresponding migration notes/evidence in the eventual review doc rather than waiting until the end.
- [x] After final code closeout, update `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` line-by-line for the B.4 section (resolved / partially resolved / explicitly deferred with rationale).
- [x] Update `docs/plans/EXECUTION-PLAYBOOK.md` to mark B.4 complete and unlock the post-B.4 Phase 2 fan-out only after validation is green or formally waived.
- [x] Create/update a final review document (likely `docs/reviews/REVIEW-B4.md`) summarizing:
  - batch sequence,
  - changed files,
  - targeted test evidence,
  - any waivers,
  - any intentionally deferred follow-up.
- [x] Run the final serialized verification lane from Section 4 and record exact outputs or waivers.
- [x] Late closeout documentation update applied: REVIEW-B4.md, MASTER-ISSUE-REGISTRY.md (B.4 section), and final-verification files for Batches 11–15, 27–29 updated to incorporate six post-Batch-10 fixes — (1) `ExpenseRepository.getExpensesPagedDynamic` `SELECT e.*` projection re-verified, (2) `InvestmentTracker.getInvestmentPerformance` recent-value ordering fix (`recentValues.lastOrNull()`), (3) `UserCorrection` `Index("originalMerchant")` entity annotation + `MIGRATION_76_77` schema version bump to 77, (4) `AnomalyAlert` `Index(["category", "alertedAt"])` entity annotation + `MIGRATION_77_78` schema version bump to 78, (5) `ExpenseWithCategory_Extensions.kt` extension renamed from `formattedDate` to `formattedTime` (removes member-shadows-extension ambiguity; dead `formattedAmount` extension deleted; `TransactionsScreen.kt` import updated to `formattedTime`), (6) `ExchangeRate` `Index(["toCurrency"])` entity annotation + `MIGRATION_78_79` schema version bump to 79 (`CREATE INDEX IF NOT EXISTS index_exchange_rates_toCurrency ON exchange_rates (toCurrency)`); `MigrationContractTest` extended with `migration_78_to_79_adds_toCurrency_index_on_exchange_rates`; `DatabaseMigrationTest` extended with `migrate_77_to_79_chain_passes_and_has_toCurrency_index`. Waiver wording updated with exact blocked commands and failure reasons.
- [ ] Only when user requests commit creation: stage only B.4 code + docs, inspect the diff, and commit using repo-consistent message style (prefer one commit per green batch or one final B.4 closeout commit if the working tree is already fully stabilized).
- [x] B.4 is complete only when every registry item in `MASTER-ISSUE-REGISTRY.md` lines 260-317 has a disposition and the playbook no longer lists B.4 as the active blocker. ✓ Final schema version: 79. All registry items dispositioned.

### 6. Status at closeout

**B.4 — PASS (pending local commit).** Final schema version: 79 (migrations 69→70 through 78→79 all forward-only and registered in `ALL_MIGRATIONS`). All registry items in `MASTER-ISSUE-REGISTRY.md` lines 260–317 have been resolved or formally dispositioned. The playbook will mark B.4 complete and the Phase B fan-out will unlock once the local B.4 commit lands.
