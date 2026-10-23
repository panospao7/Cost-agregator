# Slice plan: loan-installments

Author: feature-planner (stage 6, attempt 2, docs-only). Persisted verbatim by feature-orchestrator, 2026-10-16.
Status: APPROVED by owner 2026-10-16 (all recommendations). Guard/worker approvals (S2/S3, S7a) confirmed by owner 2026-10-22 (build/release-lead-notes/owner-approvals-20261022.md). Implementation post-1.0 only. S0 discovery answers: `docs/features/loan-installments/discovery-s0.md` (scout).

---

Feature plan: loan-installments

This is the stage 6 slice plan, read-only, ready to persist to `workflows/active/loan-installments-plan.md`. Inputs used: `docs/features/loan-installments/{gate2-decisions,domain-spec,impact-map,ux-flows}.md` and `workflows/active/loan-installments-handoff.md`. I did not verify source, as instructed. Package paths come from one Glob over existing files. New-file paths are proposals that follow sibling conventions.

Path abbreviations: `M:` = `app/src/main/java/com/yourname/expensetracker/`, `T:` = `app/src/test/java/com/yourname/expensetracker/`, `A:` = `app/src/androidTest/java/com/yourname/expensetracker/`.

---

## Header

- **Risk tier: T3** (impact-map A6). Every slice is planner-first. room-migration-, privacy-security- and architecture-guardian are mandatory where listed. reviewer-strict gates every slice that touches DB, money, lifecycle, worker, privacy or guard code. Each slice gets at most 2 review and 2 debug iterations, then goes back as PARTIAL or BLOCKED.
- **Owner timing (IM-10):** everything comes after Release 1.0. The feature branch is cut from `bug-fixes` after `release-1.0-wt` (`Cost-agregator-release`) has merged.
  - **Hard gate** (do not start before the merge): S11, S12a and S12b. They touch `AddExpenseViewModel.kt`/`AddExpenseSheet.kt`, `TransactionsScreen.kt`/`TransactionsViewModel.kt` and `BudgetScreen.kt`/`BudgetViewModel.kt`.
  - **Soft gate** (rebase after D-13): all other slices.
- **The app stays shippable after every slice:**
  - S1–S9 are inert. No user path can create a plan, so `spendingExclusionReason` stays NULL everywhere. The predicate and `effectiveAmount` changes therefore cannot change any total.
  - UI ships behind a hidden compile-time entry gate (S10a) until S13 enables it.
  - Export marking (S9) and the full predicate rollout (S4d) must land before any UI that can set a flag (S11/S12a).
- **Validation runner profiles used** (from `scripts/validation-runner.ps1`): `compile`, `targeted-unit-test -Filter`, `unit-test-shard -ShardName {architecture-contracts|data|domain-a-m|domain-n-z|runtime-ui|integration-golden}`, `migration-tests` (JVM `*Migration*`), `connected-test-class -Filter com.yourname.expensetracker.data.database.DatabaseMigrationTest` (instrumented migration proof, FG-21), `static-guards`, `registered-guard -SelectedGuard <id>`, `lint`, `unit-tests`, `app-check`.
  - I assigned shards by package name and have not confirmed them. validation-runner should confirm membership with `-Action Plan` before each run.
  - Runs are strictly serial and go through validation-runner only.

---

## Slice 0 — discovery inputs (no code; dependent slices cite D-n)

D-1..D-8 are being resolved by the parallel scout. D-9..D-20 are extra planner inputs. Some can be the first task inside the slice that needs them; the "Needed by" column says which.

| ID | Question | Needed by |
|---|---|---|
| D-1 | Name and shape of the recurring-generation worker. Does `WorkerRegistry` / worker inventory have a no-growth count, ratchet or baseline? | IM-7 decision, S7a |
| D-2 | Erase-all / factory-reset path (`clearAllTables` vs an explicit table list, `FinancialRescueCoordinator`). Are new tables covered automatically? | S2b |
| D-3 | Export code: which exporters compute totals (`ExpenseExportMapper`, `ExportTransaction`, `AccountantReportPdfExporter`, `BusinessExpenseReportGenerator`, `ExportOptionsViewModel`). Which formats have a type/notes field for marking? | S9 |
| D-4 | Upstream assembly of `ProcessedDashboardData` → `DashboardBriefingInputBuilder` / `GenerateDashboardBriefingUseCase`, and `GenerateTransactionInsightUseCase` inputs. Exactly where do cash flow, forecast, `RecurringPlanProjectionService` and the upcoming items (`DashboardUpcomingItemInput`) enter? | S8a, S8b |
| D-5 | `BackupPrivacyMode` set. Which modes strip or redact expense merchant/notes, and how is per-table text stripping declared? Manifest count mechanism in `DatabaseBackupRepositoryImpl`. | S2, S2b |
| D-6 | Pattern of the 150→151 test in `A:data/database/DatabaseMigrationTest.kt`. Do JVM Room tests (Robolectric) exist for DAO and parity tests? Is a device or emulator available for `connected-test-class`? | S2, S3 |
| D-7 | Default category for standalone plans: an existing category matching the app's existing "loan"/"mortgage" essential keywords (handoff line 12: id, name, source file), plus a generic fallback category if none matches. | S5, S10b |
| D-8 | Add-expense currency. Does TLC `createExpenseDbOnlyV2` accept a currency and compute home-currency `baseAmount` for manual USD entries? What happens when FX is unavailable? Where do `AddExpenseUiState` and the save path live? | Currency decision, S11 |
| D-9 | FG-ID mapping of `check_raw_money_aggregates` / `verify_raw_money_aggregates`, and how its allowlist keys entries (file+line, query hash, count). Does adopting the predicate re-key entries or grow them? | S4a–S4d |
| D-10 | TLC DbOnly composition. Can the plan coordinator open `writeBarrier.runWrite { database.withTransaction { … } }` and call `createExpenseDbOnlyV2` plus a new DbOnly op inside it (barrier re-entrancy, post-commit batch return)? This decides OD-15. | S3, S6a |
| D-11 | Classify all 44 `ExpenseDao` `SUM(`, plus AVG/COUNT-as-spending, into: spending aggregate / dedupe BLOCKING or RESOLVER / non-spending (balance, reconciliation, income, raw). For each, record how it handles isNotMine (WHERE filter vs `CASE … myShareAmount` expression). Do the same per-query check for `GroupExpenseDao`, `BudgetAdjustmentDao`, `SplitItemAssignmentDao` and the 3 `SUM(` in TLC. (W0 re-run 2026-10-21: TLC has 0 raw `SUM(`, so the stale "3" is corrected here.) List Kotlin readers that sum raw `.amount` instead of `effectiveAmount` (savings engines, spec Q13). | S4a–S4d |
| D-12 | Does the FG-26 doc-traceability guard require symbols named in `LEGAL_PATHS.md` / `ENGINE_INTERACTION_MAP.md` to exist in source? | S1 (docs placement) |
| D-13 | After the Release-1.0 merge: Room version (does release bump past 151?), and diffs touching this plan's write sets (`ExpenseDao`, TLC, `BackupVerifier`, `DatabaseMigrations`, `AppDatabase`, `NavigationDestination`, `FeatureConfig`, `MainActivity`, export files, `BillReminderManager`). | All slices |
| D-14 | How `ExpenseSource` and `TransactionUpdateKind` are persisted (name string vs ordinal), and how unknown values behave in `Converters.kt`, JSON/CSV importers and backup restore. | S3 |
| D-15 | Location of the recurring pattern detector (DETECTED_PATTERN). Consumers of `RecurringPlanProjectionService` (Home weather card upcoming, briefing). | S6c, S8b |
| D-16 | Any raw positional `INSERT INTO expenses` SQL (migrations, importers, restore) that breaks when a column is added. | S2 |
| D-17 | Does `values-es/strings.xml` exist, with a parity lint or test? Does `FeatureConfig` support hidden or dev items? | S10a |
| D-18 | Restore pipeline hook for a post-restore step. Does the restore barrier block coordinator writes? | S6c |
| D-19 | Foreground catch-up hook (startup runtime). Would adding one touch a startup guard? If so, use an overview-open trigger only. | S7a |
| D-20 | Is there an existing cloud-payload dependency guard to extend, or is a new one needed? | S1 |

**Stop rules:**
- If D-10 proves single-transaction composition impossible, stop and report the proof. OD-15 is pre-approved only "if planner proves necessary", and the plan gate must record it.
- If D-13 shows Release 1.0 bumped the DB version, renumber S2 to N→N+1. No other change.

---

## Slices

### S1 — Guard scaffolding + architecture docs (legal paths)
- **Goal / invariants:** INV-23, INV-24, INV-25, INV-31 (cloud reachability), INV-8 (documented). Put fences up before any plan code exists. The guards pass vacuously today.
- **Write set (source):**
  - `docs/architecture/LEGAL_PATHS.md`:
    - ADD "Installment Plan Mutations": the spec §5.2 ops list plus the forbidden list.
    - UPDATE "Expense Mutations": `setSpendingExclusionDbOnly`, UPDATED event, `SPENDING_INCLUSION`, plan-coordinator-only caller. `insertAtomic()` stays the sole insert.
    - UPDATE "Workers", "Backup / Restore", "Privacy / Cloud AI", "Diagnostics", "Lifecycle Events" (`TransactionSideEffectPlanner.planUpdated/planDeleted` → `onExpenseChanged/onExpenseDeleted`).
    - UPDATE "Recurring Plan Projection" with a note on the sibling projection.
  - `docs/architecture/ENGINE_INTERACTION_MAP.md`: row for `InstallmentProjectionPort` plus the 4 wired readers and the reminder reader.
  - `docs/architecture/CODEBASE_SEGMENTS.md`: new segment "Installment Plans" with its owner.
  - `scripts/ci/check_installment_cloud_reachability.py` (or an extension of the existing guard, per D-20).
  - `scripts/ci/guard_registry.py`, `scripts/ci/run_static_guard_suite.py` (registration, FG-16 mapping, FG-02/FG-23 self-protection entries).
- **Write set (tests):**
  - `T:architecture/InstallmentPlanArchitectureGuardTest.kt`, which checks:
    - plan DAO writes appear only in `InstallmentPlanLifecycleCoordinator`;
    - no `ExpenseDao` reference in `M:domain/installment/**`, `M:service/installment/**` or `M:ui/screens/installments/**`;
    - no `deleteExpense` call from plan code;
    - no writes to `recurring_occurrences` / `manual_recurring_expenses` / `planned_expenses` from plan code;
    - `setSpendingExclusion*` is called only from the plan coordinator.
  - `scripts/test_check_installment_cloud_reachability.py`, with positive and negative fixtures.
- **D-12 contingency:** if D-12 says FG-26 requires symbols to exist, the doc edits move into the slice that introduces each symbol: TLC op → S3, coordinator → S6a, worker → S7a, projection rows → S8b. S1 then keeps only the guards.
- **Legal path preserved:** no runtime code changes.
- **Depends on:** S0 (D-12, D-20). Branch cut after the Release-1.0 merge.
- **Gates:** architecture-guardian and privacy-security-guardian. reviewer-strict YES (guard registry change, FG-02/06/23).
- **Validation profiles:**
  - `targeted-unit-test -Filter "*InstallmentPlanArchitectureGuardTest*"`
  - `registered-guard -SelectedGuard installment_cloud_reachability` (id to be confirmed)
  - `unit-test-shard -ShardName architecture-contracts`
  - `static-guards`
- **Guard / policy changes:** 1 new arch test and 1 new static guard (IM-4 approved in principle). The guard registry gets new entries. No allowlist or baseline is introduced.
- **Stop-and-ask:**
  - If the cloud guard needs an allowlist for existing paths, stop.
  - If the guard self-protection manifest requires a hash update outside the new guard, stop.
- **Done when:** guards are registered and green, the negative fixtures fail as expected, and the docs pass FG-26.

### S2 — Schema 151→152: entities, DAO, migration, snapshot, verifier tiers
- **Goal / invariants:**
  - Persistence for INV-2 (column), INV-3 (unique `purchaseExpenseId`), INV-4 (unique `linkedExpenseId`).
  - INV-26: no retention target. `RetentionPolicyContract` is untouched.
  - INV-30: TIER_1_EXACT tiers.
  - Additive only (IM-1).
- **Write set (source):**
  - `M:data/database/AppDatabase.kt`: `APP_DATABASE_SCHEMA_VERSION = 152`, entity list, DAO accessor, append `MIGRATION_151_152`.
  - `M:data/database/DatabaseMigrations.kt`: `MIGRATION_151_152`:
    - CREATE TABLE `installment_plans` per §2.1: unique `planUuid`, unique nullable `purchaseExpenseId`, index `status`. No FK to expenses.
    - CREATE TABLE `installment_entries` per §2.2: FK `planId` → plans ON DELETE CASCADE, unique (`planId`,`kind`,`seq`), unique nullable `linkedExpenseId` (no FK), indexes `dueDate` and `status`.
    - `ALTER TABLE expenses ADD COLUMN spendingExclusionReason TEXT DEFAULT NULL`.
    - Enums are stored as TEXT, money as REAL with 2 dp, civil dates as INTEGER epochDay.
    - No index on `spendingExclusionReason`. A mostly-NULL, low-selectivity column gains nothing from an index, and adding one later would cost another schema bump. Record this decision.
  - `M:data/database/entity/InstallmentPlan.kt`, `M:data/database/entity/InstallmentEntry.kt` (new; name per entity-package convention).
  - `M:data/database/entity/Expense.kt`: field `spendingExclusionReason` only, with `@ColumnInfo(defaultValue = "NULL")` matching the migration. The `effectiveAmount` change waits for S3.
  - `M:domain/transaction/SpendingExclusionReason.kt` (new enum: `INSTALLMENT_PURCHASE`, `INSTALLMENT_REVERSED`, following the `ExpenseSource` precedent).
  - `M:data/database/converter/Converters.kt` if required.
  - `M:data/database/dao/InstallmentPlanDao.kt` (new; one DAO for both tables). Reads plus compare-and-set write methods. No callers yet.
  - `M:di/DaoModule.kt`.
  - `M:data/database/DatabaseSchemaPolicy.kt`.
  - `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/152.json`.
  - `M:data/backup/BackupVerifier.kt`: `TABLE_TIERS` += both tables at `TIER_1_EXACT`.
  - Version-constant consumers: `DatabaseBackupRepositoryImpl.kt`, `FinancialRescueCoordinator.kt`.
  - `config/guards/db_ownership_policy.yml` + `config/guards/db_ownership_policy.signatures.accounting.json`: `InstallmentPlanDao` write signatures → writer `InstallmentPlanLifecycleCoordinator` only.
- **Write set (tests):**
  - `A:data/database/DatabaseMigrationTest.kt`: 151→152 cases.
    - Seeded expenses at 151 come out with the column NULL.
    - Per-month and per-category totals are identical before and after.
    - New tables are empty.
    - Uniques and FK exist; `PRAGMA foreign_key_check` is clean.
    - The full chain from the oldest supported version reaches 152.
    - Existing backup verify passes.
  - Update `T:architecture/SourceScanningArchitectureGuardTest.kt`, `DatabaseBackupRepositoryImplTest.kt`, `BackupRestoreMoneyIntegrityScenarioTest.kt` and `ReceiptAssetRepeatRestoreTest.kt` only where they assert the current version. Historical fixtures (user_version 86/37) stay as they are.
  - `T:data/database/InstallmentPlanDaoTest.kt`, if JVM Room tests exist (D-6).
- **Legal path preserved:** no writer of the new tables exists yet. The column has no writer.
- **Depends on:** S1; D-5, D-6, D-13, D-16. Also the OQ-18 model decision: under the recommended entry-status model no DDL changes; under the plan-column model the columns must land here.
- **Gates:** room-migration-guardian (mandatory), privacy-security-guardian (backup tiers), architecture-guardian (db policy). reviewer-strict YES.
- **Validation profiles:**
  - `compile`
  - `targeted-unit-test -Filter "*BackupVerifier*"`, then `"*DatabaseSchemaPolicy*"`, then `"*InstallmentPlanDao*"`
  - `migration-tests`
  - `connected-test-class -Filter com.yourname.expensetracker.data.database.DatabaseMigrationTest` (FG-21, blocking)
  - `unit-test-shard -ShardName data`
  - `static-guards` (db ownership policy)
- **Guard / policy changes:** `db_ownership_policy.yml` and the signatures accounting file (IM-5, approved).
- **Stop-and-ask:**
  - any destructive DDL;
  - any change to existing columns or indexes;
  - Room requiring a schema export of other tables beyond the regenerated snapshot diff;
  - a version collision with Release 1.0 (D-13);
  - no device available for the instrumented migration proof. That makes the slice BLOCKED, never PASS.
- **Done when:** 152.json is committed in the working tree, the instrumented migration test passes, backup verify passes on the migrated DB, and static guards are green.

### S2b — Backup orphan checks, manifest, privacy mode, wipe coverage
- **Goal / invariants:** INV-30 (5 orphan checks, §11.3), INV-31 / IM-9 (`displayName`/`lenderLabel` stripped like merchant/notes), §11.2 wipe coverage.
- **Write set (source):**
  - `M:data/backup/BackupVerifier.kt`: 5 orphan checks with fixed count-only strings, next to the block at :520–537. The check at :522 is unchanged.
  - Backup privacy-mode stripping and manifest counts in `M:data/repository/DatabaseBackupRepositoryImpl.kt` and the backup package (exact file per D-5).
  - Wipe path (per D-2), only if new tables are not covered automatically.
- **Write set (tests):**
  - `T:data/backup/BackupVerifierInstallmentOrphanTest.kt`: AE-12, including the P2-plan-removed case (fixed message, count 240, no names/amounts/dates).
  - A privacy-mode stripping test.
  - A wipe-coverage test.
- **Legal path preserved:** verifier is read-only. Stripping goes through the existing backup path.
- **Depends on:** S2; D-2, D-5.
- **Gates:** privacy-security-guardian, room-migration-guardian. reviewer-strict YES.
- **Validation profiles:**
  - `targeted-unit-test -Filter "*BackupVerifier*"`, then `"*Backup*Privacy*"`, then the wipe test filter
  - `unit-test-shard -ShardName data`
  - `static-guards`
- **Stop-and-ask:**
  - if the wipe path needs an explicit list change that is not additive;
  - if the privacy-mode set has no merchant/notes stripping to mirror (the IM-9 premise would be wrong).
- **Done when:** AE-12 verify parts pass (restore and reconcile parts complete in S6c).

### S3 — TLC spending-inclusion op, purchase lock, `effectiveAmount`, canonical predicate, parity
- **Goal / invariants:** INV-1 (definition: one rule, two projections, parity), INV-2 (TLC side), INV-8, INV-22, INV-24.
- **Write set (source):**
  - `M:data/database/query/SpendingInclusionSql.kt` (new). Kotlin `const val` constants usable in `@Query` concatenation:
    - `SPENDING_PREDICATE` = `isNotMine = 0 AND spendingExclusionReason IS NULL`, plus a table-alias variant;
    - a canonical effective-amount SQL expression for queries that zero via CASE.
  - `M:data/database/entity/Expense.kt`:
    - `effectiveAmount` and `normalizedEffectiveAmount` return 0.0 when `spendingExclusionReason != null`;
    - new `countsAsSpending` boolean, so Kotlin COUNT and AVG match the SQL WHERE semantics.
  - `M:data/database/dao/ExpenseDao.kt`: one new write `updateSpendingExclusionReason(id, reason)` only. No aggregate edits here.
  - `M:domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`:
    - `setSpendingExclusionDbOnly(expenseId, reason?)` returning `MutationResult`; emits UPDATED with `SPENDING_INCLUSION`; usable inside a caller-held transaction (D-10);
    - INV-22 lock: refuse amount/currency/transactionType/ownership edits and duplicate-merge on flagged rows with `EXPENSE_LOCKED_BY_INSTALLMENT_PLAN`.
  - `M:domain/transaction/lifecycle/TransactionUpdateKind.kt` (+ `SPENDING_INCLUSION`).
  - `M:domain/transaction/ExpenseSource.kt` (+ `INSTALLMENT_PLAN`).
  - `M:domain/transaction/lifecycle/TransactionSideEffectPlanner.kt`: only if the new update kind needs a side-effect mapping (budget recheck).
  - Converters and importers per D-14.
  - `config/guards/db_ownership_policy.yml` + signatures: `ExpenseDao.updateSpendingExclusionReason` → TLC only.
  - `docs/architecture/LEGAL_PATHS.md` Expense Mutations, if it moved here per D-12.
- **Write set (tests):**
  - `T:domain/transaction/lifecycle/TransactionLifecycleCoordinatorSpendingInclusionTest.kt`: set and clear; event kind; AE-20 (amount refused, merchant ok, category ok, not-mine refused, merge refused); flagged row still a dedupe BLOCKING target (AE-6 replay part, INV-8).
  - `T:data/database/SpendingInclusionParityTest.kt`: SQL `SUM`/`COUNT` with the predicate equal Kotlin `effectiveAmount`/`countsAsSpending` over mixed fixtures (null / PURCHASE / REVERSED × isNotMine × shared).
  - Enum converter round-trip and unknown-value tests (IM-8).
- **Legal path preserved:** the column is written only by TLC. `insertAtomic()` is untouched.
- **Depends on:** S2; D-10, D-14.
- **Gates:** architecture-guardian (FG-14), privacy-security (no free text in the event). reviewer-strict YES (money, lifecycle).
- **Validation profiles:**
  - `targeted-unit-test -Filter "*SpendingInclusion*"`, then `"*TransactionLifecycleCoordinator*"`, then `"*Converters*"`
  - `unit-test-shard -ShardName domain-n-z`, then `data`
  - `static-guards`
- **Guard / policy changes:** db policy entries for the new DAO write.
- **Stop-and-ask:**
  - if D-10 shows the DbOnly op cannot join an outer transaction (OD-15 proof);
  - if D-14 shows persisted enums are stored by ordinal (would need a migration and a new decision).
- **Done when:** parity is green, AE-20 passes, and TLC remains the sole writer of the column.

### S4a–S4d — Canonical predicate rollout across spending aggregates (sequential; same file)
- **Goal / invariants:** INV-1 and INV-7 at the aggregate level; INV-8 (never in dedupe BLOCKING/RESOLVER). Every batch is behaviour-neutral because all flags are NULL.
- **Batching rule** (fixed by the D-11 classification; at most about 12 queries per batch, grouped by consuming segment):

  | Batch | Scope |
  |---|---|
  | S4a | Budget and category-by-month sums (Seg 2; feeds AE-24) |
  | S4b | Dashboard totals, period and summary sums (Seg 10) |
  | S4c | Analytics, trends, merchant and search sums (Seg 8; NL query sums) |
  | S4d | Remaining `ExpenseDao` spending sums; per-query fixes in `GroupExpenseDao` / `BudgetAdjustmentDao` / `SplitItemAssignmentDao` (only where D-11 says spending); Kotlin raw-`.amount` readers (Q13 list, only as approved at plan gate); then activate the canonical-predicate guard |

- **Rule per query:** a query that filters isNotMine with WHERE adopts `SPENDING_PREDICATE`. A query that zeroes via CASE adopts the canonical effective-amount expression. Never touch dedupe BLOCKING/RESOLVER families or non-spending sums.
- **Write set (source):**
  - `M:data/database/dao/ExpenseDao.kt` (each batch, its own line ranges).
  - S4d also: the other DAOs named above, the Kotlin readers on the approved list, and the new static guard `scripts/ci/check_spending_predicate_usage.py` with its registry entries in `scripts/ci/guard_registry.py` / `run_static_guard_suite.py` (FG-17).
  - Exemptions use an in-source marker on the query, e.g. `/* spending-exempt: DEDUPE_BLOCKING */`. No file allowlist.
  - `check_raw_money_aggregates` allowlist: re-key only, with a non-increasing count (per D-9).
- **Write set (tests):**
  - `T:data/database/dao/ExpenseDaoSpendingPredicateTest.kt`: cases added per batch, with flagged fixtures set through TLC `setSpendingExclusionDbOnly` in test setup and never by raw DAO writes.
    - AE-1 aggregate part: E1 flagged → March 0.00.
    - AE-6: no 600/650 months.
    - AE-24: Electronics March excludes 600; April 110.00.
    - AE-17: June 100.00 when both count.
  - Golden totals unchanged (`integration-golden`).
  - `scripts/test_check_spending_predicate_usage.py` (S4d).
- **Legal path preserved:** read-only query changes. The dedupe families are untouched (INV-8 test from S3 re-run).
- **Depends on:** S3; D-9, D-11. Strictly S4a → S4b → S4c → S4d.
- **Gates:** architecture-guardian. reviewer-strict YES on every batch (money aggregates). S4d also needs guard review (FG-02/06/07/23).
- **Validation profiles, per batch:**
  - `targeted-unit-test -Filter "*ExpenseDaoSpendingPredicate*"`, then `"*SpendingInclusionParity*"`
  - `unit-test-shard -ShardName data`, then `integration-golden`
  - `static-guards` on any batch that changes the `check_raw_money_aggregates` keys
  - S4d also: `registered-guard -SelectedGuard spending_predicate_usage` (id to be confirmed), `static-guards`, `unit-test-shard -ShardName domain-a-m`, then `domain-n-z`
- **Guard / policy changes:** new guard (IM-4); `check_raw_money_aggregates` re-key (IM-6).
- **Stop-and-ask:**
  - any net growth of the `check_raw_money_aggregates` allowlist;
  - any golden total change;
  - any Kotlin raw-sum fix not on the plan-gate approved list (it changes isNotMine behaviour);
  - an exemption marker mechanism the owner has not approved.
- **Done when:** every spending aggregate uses a constant or carries an approved exemption marker, the guard enforces with zero unmarked exceptions, and goldens are identical.

### S5 — Pure domain: schedule, money, policies, transitions (can run in parallel with S2–S4)
- **Goal / invariants:** INV-9, INV-10, INV-11, INV-18 (incl. noon-local stamping helper), INV-5 (`ExpenseScheduleLinkPolicy`), INV-16/17 and INV-6 math (read-model calculators), INV-20/21 (pure transition table), refusal codes (§7.0), standalone default-category policy, and the OQ-18 history-only status (per gate decision).
- **Write set (source; all new, disjoint):**
  - `M:domain/installment/schedule/InstallmentScheduleCalculator.kt`
  - `M:domain/installment/model/` (enums for kind, split method, status, entry status/kind, link origin, fulfilment mode, residual placement, bounded reason/refusal codes)
  - `M:domain/installment/model/InstallmentPlanTransitions.kt`
  - `M:domain/installment/readmodel/InstallmentPlanMath.kt` (remaining per currency, financing cost per INV-17 and the OQ-24 decision, history derivation INV-26)
  - `M:domain/installment/StandalonePlanDefaultCategoryPolicy.kt`
  - `M:domain/schedule/ExpenseScheduleLinkPolicy.kt`
  - Must not touch `AmountUtils.kt` / `AmountInputSanitizer.kt`. Minor-unit helpers live in the installment package.
- **Write set (tests):**
  - `T:domain/installment/schedule/InstallmentScheduleCalculatorTest.kt`: AE-2, AE-3, AE-4, AE-18 (end date 2047-04-01, 125,560.80), AE-19 (noon stamp; America/New_York April bucket; London counter-example).
  - `T:domain/installment/readmodel/InstallmentPlanMathTest.kt`: AE-5 lines, AE-9 financing 0.00 / −10.00, AE-18 financing 50.00 / unavailable.
  - `T:domain/installment/model/InstallmentPlanTransitionsTest.kt`
  - `T:domain/schedule/ExpenseScheduleLinkPolicyTest.kt`: AE-13 rules.
  - `T:domain/installment/StandalonePlanDefaultCategoryPolicyTest.kt`
  - Input-refusal tests: AE-22 `INVALID_COUNT`, `UNSUPPORTED_CURRENCY`, `AMOUNT_TOO_SMALL_FOR_COUNT`, `FIRST_DUE_BEFORE_PURCHASE`, `INVALID_AMOUNT`.
- **Legal path preserved:** no I/O. Callable only by the coordinator and read model (S1 arch test extended if needed).
- **Depends on:** S1; plan-gate decisions OQ-18, OQ-24, OQ-20; D-7 (default category data, but the policy is testable with fakes).
- **Gates:** architecture-guardian (light). reviewer-strict YES (money math).
- **Validation profiles:**
  - `targeted-unit-test -Filter "*Installment*"`, then `"*ExpenseScheduleLinkPolicy*"`
  - `unit-test-shard -ShardName domain-a-m`
- **Stop-and-ask:** any open spec decision. OQ-18 and OQ-24 must be amended in `domain-spec.md` by domain-modeler before coding.
- **Done when:** all listed AEs pass as pure tests.

### S6a — Plan coordinator core + read model + projection port + DI
- **Goal / invariants:** INV-2, INV-3, INV-4, INV-5, INV-6, INV-12 (key format only), INV-16, INV-19, INV-20, INV-21, INV-23, INV-24, INV-25, INV-27, INV-28, INV-31.
- **Write set (source):**
  - `M:domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt` (new):
    - ops: `declareOnExpense`, `declareWithNewExpense`, `createStandalone`, `confirmEntry`, `linkExpense`, `unlinkExpense`, `skip`/`unskip`, `adjustEntry`, `delete`, `onExpenseChanged`, `onExpenseDeleted`, `reconcile` (R1–R6);
    - every op: `writeBarrier.checkWritesAllowed` + `runWrite { withTransaction { … } }`, compare-and-set, `AlreadyInState` / `IllegalTransition`, `CancellationException` rethrown;
    - expense writes only via TLC `createExpenseDbOnlyV2` (STRICT_EXTERNAL_ID `installment:<planUuid>:<kind>:<seq>:<recordGeneration>`, `ExpenseSource.INSTALLMENT_PLAN`, merchant = `displayName`, no notes) and `setSpendingExclusionDbOnly`;
    - post-commit batches returned.
  - `M:domain/installment/InstallmentPlanReadRepository.kt` + `M:data/repository/InstallmentPlanReadRepositoryImpl.kt` (read-only).
  - `M:domain/installment/readmodel/InstallmentPlanReadModel.kt` (overview, detail, history, suggestions per INV-13 candidate rule, variance).
  - `M:domain/installment/projection/InstallmentProjectionPort.kt` + impl (SCHEDULED entries of ACTIVE plans with due ≥ today, INV-15).
  - `M:di/InstallmentModule.kt`.
  - Diagnostics: bounded codes and integer counts only.
  - LEGAL_PATHS "Installment Plan Mutations", if it moved here per D-12.
- **Write set (tests):**
  - `T:domain/installment/lifecycle/InstallmentPlanLifecycleCoordinatorDeclareTest.kt`:
    - AE-1 declaration state;
    - AE-6 injected failure between entries insert and flag write → nothing persisted, purchase 600; retry exactly once;
    - AE-14;
    - AE-15d;
    - AE-22, all 7 refusals, nothing written.
  - `…LinkConfirmTest.kt`: AE-5 `CURRENCY_MISMATCH`, AE-10b (CONFIRM: not counted until linked; link counts in May), AE-13 refusals, AE-16 skip.
  - `…ReconcileTest.kt`: R1–R6 including AE-25 (R5).
  - `InstallmentPlanReadModelTest.kt`: AE-5, AE-10b overview line, AE-11 history line.
  - Privacy log-capture test: AE-23 diagnostics subset.
  - Extend `InstallmentPlanArchitectureGuardTest` so it now asserts against real classes.
- **Legal path preserved:** expense writes go only through TLC and plan-table writes only through the coordinator. No caller exists outside tests yet (inert).
- **Depends on:** S3, S4d, S5, S2b; D-10 (OD-15).
- **Gates:** architecture-guardian, privacy-security-guardian. reviewer-strict YES.
- **Validation profiles:**
  - `targeted-unit-test -Filter "*InstallmentPlanLifecycleCoordinator*"`, then `"*InstallmentPlanReadModel*"`, then `"*InstallmentPlanArchitectureGuardTest*"`
  - `unit-test-shard -ShardName domain-a-m`, then `architecture-contracts`
  - `static-guards`
- **Stop-and-ask:** OD-15 fallback needed (report the D-10 proof); any need to call `ExpenseDao` or `deleteExpense`.
- **Done when:** the listed AEs pass, the guards bind to real classes, and static guards are green.

### S6b — End and edit operations
- **Goal / invariants:** INV-19, INV-20, INV-21, INV-23, INV-6, INV-9 (re-spread), INV-2 (refund flags via TLC in the same transaction).
- **Write set (source):** `M:domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt`: `stop`, `refund(refundAlreadyRecorded)` (Yes → Stop), `payOff(amount, date)` (PAYOFF STRICT key `…:PAYOFF:0:0`), `editDetails` (E3), `extend`/`shorten` with re-spread (E2), `changeDueDay` (E4), `CURRENCY_LOCKED`.
- **Write set (tests):** `T:domain/installment/lifecycle/InstallmentPlanLifecycleCoordinatorEndEditTest.kt`: AE-7, AE-8 (both answers), AE-9 (450 / 440), AE-21, AE-11 completion in the same transaction.
- **Legal path preserved:** same as S6a. No expense deletes (row-count assertion).
- **Depends on:** S6a. Can run in parallel with S6c (disjoint files).
- **Gates:** architecture-guardian. reviewer-strict YES.
- **Validation profiles:** `targeted-unit-test -Filter "*InstallmentPlanLifecycleCoordinator*"`, then `unit-test-shard -ShardName domain-a-m`.
- **Done when:** all AEs pass and every operation keeps the expense row count unchanged.

### S6c — Cross-coordinator hooks
- **Goal / invariants:** INV-5 (recurring side), INV-29 (pattern detector), INV-30 (post-restore reconcile), §7.6 reactions.
- **Write set (source):**
  - `M:domain/transaction/lifecycle/TransactionSideEffectPlanner.kt` (+ dispatcher if needed): `planUpdated/planDeleted` → coordinator `onExpenseChanged/onExpenseDeleted`, post-commit.
  - `M:domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`: consult `ExpenseScheduleLinkPolicy` before auto-link and manual link.
  - Recurring pattern detector (path per D-15): exclude installment-linked expenses.
  - Restore pipeline hook (per D-18): `reconcile()` before the first `recordDueEntries`.
- **Write set (tests):**
  - `T:domain/installment/lifecycle/InstallmentExpenseReactionTest.kt`: AE-11 (delete installment expense → entry SCHEDULED, plan ACTIVE, no re-record), AE-16, AE-25, AE-20 (category edit leaves the plan untouched).
  - Recurring auto-link test: AE-13 first bullet.
  - Pattern-detector exclusion test.
  - Restore-then-reconcile test: AE-12 restore, reconcile changes 0 rows, per-month totals equal.
- **Legal path preserved:** TLC stays the expense owner. The plan reacts post-commit, and reconcile R2/R4 is the backstop. Recurring rules live only in the policy.
- **Depends on:** S6a; D-15, D-18.
- **Gates:** architecture-guardian (cross-module, FG-14). reviewer-strict YES.
- **Validation profiles:**
  - `targeted-unit-test -Filter "*InstallmentExpenseReaction*"`, then `"*RecurringLifecycleCoordinator*"`, then `"*Restore*"`
  - `unit-test-shard -ShardName domain-n-z`, then `domain-a-m`
  - `static-guards`
- **Stop-and-ask:** if the recurring contract (RECURRING_RULE) or detector output contract has to change beyond an exclusion filter.

### S7a — AUTO_RECORD host + foreground catch-up
- **Goal / invariants:** INV-12, INV-13, INV-14, INV-18, INV-21, INV-31; FG-10/FG-11.
- **Write set (source; assumes the recommended IM-7 option, a dedicated worker):**
  - Coordinator `recordDueEntries(today)`: per-entry transaction; candidate deferral `AUTO_RECORD_DEFERRED_CANDIDATE`; FX deferral `AUTO_RECORD_DEFERRED_FX`; reconcile first.
  - `M:service/installment/InstallmentDueRecordingWorker.kt` (new; `WorkerExecutionGuard`, barriers, never swallows `CancellationException`).
  - `M:domain/workers/WorkerRegistry.kt` (daily periodic spec registration).
  - Foreground trigger: startup hook if D-19 allows, otherwise overview-open only.
  - LEGAL_PATHS Workers row.
  - If the reuse option is chosen instead, the recurring worker file replaces the new worker and the `WorkerRegistry` edit is dropped.
- **Write set (tests):**
  - `T:domain/installment/lifecycle/InstallmentRecordDueEntriesTest.kt`: AE-1 catch-up, AE-10a, AE-10c, AE-15a/b/c, AE-16 (no re-create), AE-17 deferral, AE-19 DST.
  - `T:service/installment/InstallmentDueRecordingWorkerTest.kt`: guard, cancellation propagation.
  - Worker-registry guard test update.
- **Legal path preserved:** worker → coordinator → TLC.
- **Depends on:** S6a (S6b not required); IM-7 decision; D-1, D-19.
- **Gates:** architecture-guardian (FG-10/11/13), privacy-security (diagnostics). reviewer-strict YES.
- **Validation profiles:**
  - `targeted-unit-test -Filter "*InstallmentRecordDueEntries*"`, then `"*InstallmentDueRecordingWorker*"`, then `"*WorkerRegistry*"`
  - `unit-test-shard -ShardName runtime-ui` (to be confirmed), then `architecture-contracts`
  - `static-guards`
- **Guard / policy changes:** a WorkerRegistry entry (if dedicated). If D-1 shows a worker-count ratchet, stop: it would be baseline growth.
- **Stop-and-ask:** any worker-count baseline change; a startup-guard change.

### S7b — Installment reminders through existing BillReminder
- **Goal / invariants:** INV-15, INV-31; dedupe key `planUuid:seq:recordGeneration`; post-claim revalidation of entry state.
- **Write set (source):**
  - `M:domain/reminder/BillReminderManager.kt` (+ `InstallmentProjectionPort` dependency).
  - `M:service/reminder/BillReminderWorker.kt` (only if the delivery path needs it).
  - Reminder tap routing to plan detail is completed in S10a via a deep-link token.
- **Write set (tests):** `T:domain/reminder/BillReminderManagerInstallmentTest.kt`: reminders only for SCHEDULED entries of ACTIVE plans due ≥ today; nothing after AE-7 Stop or AE-11 completion; dedupe; quiet hours honoured.
- **Legal path preserved:** read-only via the port. No second reminder system.
- **Depends on:** S6a. Can run in parallel with S7a and S8 (disjoint files).
- **Gates:** privacy-security-guardian (notification text follows existing BillReminder visibility). reviewer-strict YES (worker path).
- **Validation profiles:** `targeted-unit-test -Filter "*BillReminder*"`, then `unit-test-shard -ShardName domain-a-m`.

### S8a — Cloud-payload exclusion seam (lands before any projection wiring)
- **Goal / invariants:** INV-31; §11.5 (AE-23 payload part).
- **Write set (source; files per D-4):**
  - The upstream `ProcessedDashboardData` assembler and the `GenerateTransactionInsightUseCase` input assembly get an explicit installment-projection exclusion. The cloud-bound path is hard-wired to "excluded" and is never a user setting.
  - `M:domain/ai/usecase/DashboardBriefingInputBuilder.kt`, only if upcoming items are mapped there.
- **Write set (tests):** `T:domain/ai/usecase/DashboardBriefingInstallmentExclusionTest.kt`: a `DASHBOARD_BRIEFING` payload with plans present contains no plan fields and no projected amounts. The cloud guard from S1 is re-run.
- **Depends on:** S6a (port type); D-4.
- **Gates:** privacy-security-guardian (mandatory). reviewer-strict YES.
- **Validation profiles:**
  - `targeted-unit-test -Filter "*DashboardBriefing*"`
  - `registered-guard -SelectedGuard installment_cloud_reachability`
  - `static-guards`
- **Stop-and-ask:** if the briefing consumes the same `CashFlowCalculator` / forecast output object as the UI, so it cannot be separated without restructuring. Then those payloads stay out of v1 and the owner must be told.

### S8b — Projection wiring (cash flow, forecast, stress, recurring sibling projection)
- **Goal / invariants:** INV-15, INV-29 (forecast side), INV-16 (USD entries use the engine's existing multi-currency handling; if a rate is unavailable the item is marked partial or omitted, never 0 and never summed as EUR).
- **Write set (source):**
  - `M:domain/cashflow/CashFlowCalculator.kt`
  - `M:domain/forecasting/ForecastInputAssembler.kt` (projected entries + exclude installment-linked history from baselines)
  - `M:domain/forecasting/FinancialStressForecastEngine.kt`
  - New `M:domain/installment/projection/InstallmentUpcomingProjection.kt`: sibling of `M:domain/recurring/RecurringPlanProjectionService.kt`, which stays unchanged, per the OQ-25 recommendation.
  - The single Home upcoming merge point (per D-15).
  - `ENGINE_INTERACTION_MAP.md` rows, if moved here.
- **Write set (tests):** `T:domain/cashflow/CashFlowCalculatorInstallmentTest.kt`, `T:domain/forecasting/ForecastInputAssemblerInstallmentTest.kt`, `T:domain/forecasting/FinancialStressForecastInstallmentTest.kt`, `T:domain/installment/projection/InstallmentUpcomingProjectionTest.kt`. They cover:
  - AE-7: nothing projected after Stop;
  - AE-11: no 50.00 outflow from 2028-04, from either the plan or a pattern;
  - INV-15 bounds.
- **Depends on:** S8a, S6a; D-15.
- **Gates:** architecture-guardian, privacy-security-guardian. reviewer-strict YES.
- **Validation profiles:**
  - `targeted-unit-test -Filter "*Installment*"` (projection subset), then `"*CashFlowCalculator*"`, then `"*ForecastInputAssembler*"`
  - `unit-test-shard -ShardName domain-a-m`, then `domain-n-z`, then `integration-golden`
  - `static-guards`
- **Stop-and-ask:** any change to the RECURRING_RULE projection contract; health score or SynthesisEngine wiring (OD-12: stay unwired).

### S9 — Export marking + export totals (OD-11; must precede S11/S12a)
- **Goal / invariants:** INV-1 for export totals; flagged rows are included and marked "not counted (installment plan)" / refunded. No plan-data export.
- **Write set (source; per D-3):** `M:domain/export/ExpenseExportMapper.kt`, `M:domain/export/ExportTransaction.kt`, `M:domain/export/AccountantReportPdfExporter.kt`, `BusinessExpenseReportGenerator`, `ExportOptionsViewModel` totals only. Importers ignore the marker.
- **Write set (tests):** `T:domain/export/ExpenseExportInstallmentMarkingTest.kt`. These are new examples, my assumption since the spec has no AE for this:
  - E1 exported with the marker; the total excludes 600.00;
  - a REVERSED installment is marked;
  - re-importing the file creates no flag.
- **Depends on:** S3; D-3. Can run in parallel with S5–S8.
- **Gates:** privacy-security-guardian. reviewer-strict YES (money, export).
- **Validation profiles:** `targeted-unit-test -Filter "*Export*"`, then `unit-test-shard -ShardName domain-a-m`.
- **Stop-and-ask:** if a format has no type/notes field, propose either exclusion or a column for that format at the gate.

### S10a — UI read surfaces + navigation + hidden entry
- **Goal / invariants:** INV-16, INV-17, INV-26 displays; INV-31 (bounded codes mapped to fixed strings, never `e.message`).
- **Write set (source):**
  - `M:ui/navigation/NavigationDestination.kt` (`Installments`, `InstallmentPlanDetail(planId)`, `InstallmentHistory`)
  - `M:ui/MainActivity.kt` routing
  - `M:ui/navigation/FeatureConfig.kt` (item `installments`, gated by a compile-time constant `InstallmentsFeature.ENABLED = false`)
  - Deep-link tokens `installments`, `installment_plan?id=` for reminder taps
  - `M:ui/screens/installments/overview/*`, `…/detail/*`, `…/history/*` (screens and ViewModels using `LoadableUiState`, `MoneyDisplayUi`)
  - `res/values/strings.xml` (+ `values-es` per D-17 / OQ-10)
- **Write set (tests):** `T:ui/screens/installments/*ViewModelTest.kt`: AE-5 lines, AE-10b "awaiting confirmation", AE-7 and AE-11 history text, AE-18 labels, "unavailable" not 0.
- **Depends on:** S6a; D-17. Can run in parallel with S7 and S8. Soft gate on the Release merge (navigation files, D-13).
- **Gates:** cheap review + privacy-security spot check. reviewer-strict NO.
- **Validation profiles:** `targeted-unit-test -Filter "*Installment*ViewModel*"`, then `unit-test-shard -ShardName runtime-ui`, then `lint`.

### S10b — UI create standalone, edit, row actions, end flows
- **Goal / invariants:** OD-3 four end actions with consequence copy; `MutationState`; refund question; default category (D-7 policy); OQ-18 history-only input; OQ-21 restricted Unlink + "Delete expense and reopen" through the standard expense-delete use case (never the coordinator).
- **Write set (source):** `M:ui/screens/installments/create/*`, `…/edit/*`, `…/actions/*` (sheets for confirm / link / suggestion / skip / adjust / end); strings.
- **Write set (tests):** ViewModel tests: AE-7, AE-8 and AE-9 flows reach the correct coordinator op; AE-14 Undo; refusal-code → string mapping; discard dialog.
- **Depends on:** S10a, S6b, S7a (auto-recorded state).
- **Gates:** cheap review + architecture spot check (no `ExpenseDao` / coordinator bypass). reviewer-strict NO, unless the diff reaches the expense-delete wiring, then YES.
- **Validation profiles:** `targeted-unit-test -Filter "*Installment*ViewModel*"`, then `unit-test-shard -ShardName runtime-ui`, then `lint`.

### S11 — Add expense: installments switch + currency picker (HARD gate: Release 1.0 merged)
- **Goal / invariants:** INV-27 via `declareWithNewExpense`; INV-16; Repeat and Installments are mutually exclusive; the existing back behaviour is unchanged.
- **Write set (source; recommended option A, installment-scoped):**
  - `M:ui/screens/addexpense/AddExpenseSheet.kt`: installments section + EUR/USD segmented control visible only while the switch is on.
  - `M:ui/screens/addexpense/AddExpenseViewModel.kt`: installment sub-state + submit branch → coordinator `declareWithNewExpense`. The plain save path is unchanged.
  - Strings.
- **Write set (tests):** `T:ui/screens/addexpense/AddExpenseInstallmentsTest.kt`:
  - AE-1 via the new-expense path;
  - AE-22 refusals surface as fixed strings;
  - USD purchase+plan;
  - FX-unavailable refusal keeps the form (D-8);
  - non-installment save unchanged (regression).
- **Depends on:** S10a, S6a, S9, S4d; D-8; currency-scope decision; Release merge.
- **Gates:** architecture-guardian. reviewer-strict YES (money/currency, overlap file).
- **Validation profiles:** `targeted-unit-test -Filter "*AddExpense*"`, then `unit-test-shard -ShardName runtime-ui`, then `lint`.
- **Stop-and-ask:** if D-8 shows TLC cannot create manual USD expenses without changing the general save path. That turns this into option B and needs owner approval.

### S12a — Transactions: row menu declare sheet, chips, filter, badges, suggestions (HARD gate)
- **Goal / invariants:** OQ-16 entry; OQ-22 auto-recorded visible marking (source badge "Installment plan" + chip "Installment n of N" as a UI join, never stored text); purchase chips "Paid in N installments" / "Not counted as spending"; "Not counted (refunded)"; AE-17 suggestion and "possible duplicate"; locked-edit messaging (INV-22); declare sheet with plan-currency picker (OD-14).
- **Write set (source):** `M:ui/screens/transactions/TransactionsScreen.kt`, `M:ui/screens/transactions/TransactionsViewModel.kt` (small additive hooks); new `M:ui/screens/installments/declare/DeclareInstallmentsSheet.kt` (+ ViewModel).
- **Write set (tests):** `TransactionsViewModelInstallmentTest.kt`, `DeclareInstallmentsViewModelTest.kt`: AE-1 existing-expense path, AE-17 surfaces, AE-20 messaging, AE-22, Undo.
- **Depends on:** S6c, S7a, S9, S10a, S4d; Release merge.
- **Gates:** architecture-guardian, privacy-security (no raw text). reviewer-strict YES (lifecycle entry, overlap files).
- **Validation profiles:** `targeted-unit-test -Filter "*Transactions*Installment*"`, then `"*DeclareInstallments*"`, then `unit-test-shard -ShardName runtime-ui`, then `lint`.

### S12b — Budget info line (HARD gate)
- **Goal / invariants:** B-2 / INV-1 display "Includes €X in installments", computed through the read model, per currency.
- **Write set (source):** `M:ui/screens/budget/BudgetScreen.kt`, `M:ui/screens/budget/BudgetViewModel.kt`, plus a read-model query (`InstallmentPlanReadModel`, additive method). `BudgetRepository` stays unchanged unless D-13 says otherwise.
- **Write set (tests):** `BudgetViewModelInstallmentLineTest.kt`: AE-24.
- **Depends on:** S6a, S4a; Release merge. Can run in parallel with S12a (disjoint files) if the read-model method is added in S12b only.
- **Gates:** cheap review. reviewer-strict NO (display only).
- **Validation profiles:** `targeted-unit-test -Filter "*Budget*Installment*"`, then `unit-test-shard -ShardName runtime-ui`.

### S13 — Enablement + final acceptance
- **Goal:** flip `InstallmentsFeature.ENABLED`; update `docs/architecture/CODEBASE_INVENTORY.md`; run end-to-end AE-6 replay and AE-23 diagnostics plus payload; confirm the whole-feature privacy and money posture.
- **Write set:** `InstallmentsFeature` constant; inventory doc; `T:integration/InstallmentEndToEndScenarioTest.kt` (AE-1 → AE-11 lifecycle, AE-6, AE-23).
- **Gates:** reviewer-strict (Opus, final acceptance) by a reviewer other than any slice reviewer. All three guardians sign off.
- **Validation profiles:** `static-guards`, then `unit-tests`, then `app-check`, then `connected-test-class -Filter com.yourname.expensetracker.data.database.DatabaseMigrationTest`, then `lint`. All serial.

### Dependency and parallelism summary

```
S0 → S1 → S2 → S2b ┐
            S2 → S3 → S4a → S4b → S4c → S4d ┐
      S1 → S5 ──────────────────────────────┤ (parallel with S2–S4)
                                            S6a → S6b
                                            S6a → S6c          (parallel with S6b)
                                            S6a → S7a ; S6a → S7b (parallel)
                                            S6a → S8a → S8b
                         S3 → S9                 (parallel with S5–S8)
                                            S6a → S10a → S10b
[Release 1.0 merged] + S10a,S9,S4d → S11 ; + S6c,S7a → S12a ; + S6a → S12b
all → S13
```

Write sets are disjoint between the parallel pairs listed. Files shared across slices (coordinator, `ExpenseDao`, `BackupVerifier`) are always sequential.

---

## Requested topics

### 1. Currency picker scope (OQ-7) — owner decision needed at plan gate

| Option | What changes | Blast radius |
|---|---|---|
| **A. Installment-scoped (recommended)** | An EUR/USD selector appears in Add expense only while "Pay in installments" is on. It sets the new purchase's currency and the plan currency, and is submitted only through `declareWithNewExpense`. Plans whose currency differs from the purchase (OD-14) are available only from the Transactions declare sheet. | `AddExpenseSheet.kt` (installment section), `AddExpenseViewModel.kt` (installment branch), strings. The plain save path, edit mode, receipt prefill and dedupe inputs are untouched. Depends on D-8 (TLC manual USD creation and FX-unavailable behaviour). |
| B. General Add-expense currency | A currency field for every manual expense. | The main save path, `AddExpenseUiState` validation, edit mode, receipt-scan prefill, FX/`baseAmount` for all manual entries, dedupe inputs, analytics now fed multi-currency manual rows, more tests, bigger Release-1.0 overlap. Out-of-feature behaviour change. |

Recommendation: A. It satisfies "add a currency picker to Add expense, minimal scope".

### 2. Default category for standalone plans (OQ-19)
- **Rule:** a standalone plan requires an existing spending category, chosen with a picker. No new categories are created and no type taxonomy is added. Purchase-attached plans copy the purchase `categoryId` exactly (null stays null).
- **Preselected default:** resolved by pure `StandalonePlanDefaultCategoryPolicy`, in this order:
  1. the existing category matching the app's existing "loan"/"mortgage" essential keywords;
  2. the app's generic fallback category;
  3. no preselection, with Save disabled until the user picks.
- **Concrete categories:** D-7 (S0 input).

### 3. Sequencing vs Release 1.0
- Everything is post-1.0. The branch is cut from `bug-fixes` after the `release-1.0-wt` merge.
- S11, S12a and S12b are hard-gated.
- All other slices are soft-gated by D-13: version collision and diffs on `ExpenseDao`, TLC, `BackupVerifier`, migrations, navigation and exports.

### 4. Proposed defaults (all ASSUMPTIONS until confirmed at the gate)
- **OQ-8:** no per-currency header in History. Each card shows its currency, and there are no cross-currency totals.
- **OQ-10:** Spanish parity is kept in the same UI slice if `values-es` exists (D-17). Otherwise English only.
- **OQ-11:** no new Home weather-card link. Installments appear as upcoming items with subtitle "Installment n of N" and tap through to plan detail. Entry is via the Features menu.
- **OQ-17:** no lent/borrowed teaser.
- **OQ-20:** skip reasons are bounded to `USER_SKIP_LENDER_HOLIDAY`, `USER_SKIP_WAIVED` and `USER_SKIP_OTHER`. No free text.
- **OQ-21:** user-facing Unlink only for `USER_LINKED` entries. AUTO_RECORDED and USER_CONFIRMED entries get "Delete expense and reopen" via the standard expense-delete use case; reopen happens via S6c `onExpenseDeleted`. The coordinator `unlinkExpense` stays general for internal and R4 use.
- **OQ-24:** financing cost = Σ (PAID linked amounts + SCHEDULED + PAYOFF + history-only entries) − purchase. It excludes SKIPPED, CANCELLED and REVERSED. This is a spec §9 amendment and needs domain-modeler confirmation.
- **OQ-25:** a sibling `InstallmentUpcomingProjection`, merged at one Home upcoming point. `RecurringPlanProjectionService` and the RECURRING_RULE contract stay unchanged. This also keeps the briefing exclusion simple (S8a).
- **IM-7 (worker host):** a dedicated `InstallmentDueRecordingWorker` with a `WorkerRegistry` entry. It gives single ownership, independent cancellation and retry, and FG-11 coverage, and it avoids coupling the recurring worker to the plan coordinator. The alternative is reuse of the recurring-generation worker (D-1). If D-1 finds a worker-count ratchet, the owner must choose.
- **OQ-18 (history-only pre-app payments; spec gap, domain change):** recommend a new entry status `PAID_BEFORE_TRACKING`.
  - Allowed only for entries with `dueDate < declaredOnDate`. Set at create or declare ("payments already made: k") or later in bulk from overdue entries.
  - No linked expense. Counts 0. Never projected. Counts as non-SCHEDULED for INV-21. Shown as "k paid before tracking" in history.
  - Included in financing cost. Reversible to SCHEDULED while the plan is ACTIVE/COMPLETED. Does not block Delete.
  - Stored as TEXT, so no DDL impact.
  - Needs amendments to spec §3.2, INV-6, §9 and §11.1, plus new AEs, before S5.
  - Alternative: plan-level columns (count + amount). These add DDL, so they must be decided before S2, and they lose per-entry "n of N" labelling.
- **Feature visibility:** compile-time hidden entry until S13. No remote config or network.
- **OD-15:** not used unless D-10 proves single-transaction composition impossible. Then the proof is reported and recorded at the gate.

---

## Human decisions required (plan gate)
1. IM-7 worker host: dedicated worker (recommended) or reuse the recurring worker.
2. Currency picker scope: option A (recommended) or B.
3. OQ-18 modelling: `PAID_BEFORE_TRACKING` entry status (recommended) or plan-level columns. This is a domain-spec amendment by domain-modeler and blocks S5. The plan-column option also blocks S2.
4. OQ-24 financing cost excludes SKIPPED. Spec §9 amendment.
5. Standalone default-category rule, with the concrete category from D-7.
6. Guard designs (IM-4/IM-6 detail):
   - canonical-predicate exemption via in-source marker (no file allowlist);
   - cloud-reachability guard, new or extended (D-20);
   - `check_raw_money_aggregates` re-key-only (D-9). Any growth means stop.
7. Pre-existing raw-`.amount` Kotlin sums found by D-11 (spec Q13): approve the itemised list as in-scope fixes (they change isNotMine behaviour), or defer them.
8. Defaults: OQ-8/10/11/17/20/21/25 and hidden-entry-until-S13.
9. No index on `expenses.spendingExclusionReason` (it would cost a schema bump later).
10. If D-10 proves it: record OD-15 fallback activation, with its transient double-count window until the next reconcile.
11. If D-13 shows a version collision: renumber the migration.
12. A device or emulator for the blocking instrumented migration proof (S2, S13).

## Risk register
| # | Risk | Impact | Mitigation / slice |
|---|---|---|---|
| R1 | An aggregate misses the predicate → purchase double-counts | High (money) | D-11 classification, S4 batches, parity test, goldens, S4d guard. Declare UI only after S4d+S9 (S11/S12a deps) |
| R2 | Plan data leaks into `DASHBOARD_BRIEFING` / insights | High (privacy) | S8a lands before S8b; cloud guard (S1); AE-23 tests; fail closed (payload out of v1) |
| R3 | DB version collision with Release 1.0 | Medium | D-13; renumber; rebase after the merge |
| R4 | TLC DbOnly cannot share the transaction | Medium | D-10; OD-15 fallback + reconcile R1; report |
| R5 | Worker/foreground race or process death creates double expenses | High | STRICT_EXTERNAL_ID + compare-and-set + per-entry transaction; AE-15 (S7a) |
| R6 | Merge conflicts in Transactions / AddExpense / Budget | Medium | Hard gate; additive hooks; S11/S12 last |
| R7 | Q13 raw-sum fixes change isNotMine totals | Medium | Owner-approved list; goldens; separate commits in S4d |
| R8 | AVG/COUNT semantics: SQL WHERE vs Kotlin 0.0 | Medium | `countsAsSpending`; parity covers SUM and COUNT (S3) |
| R9 | New persisted enum values break restore or import in edge cases | Medium | D-14; converter unknown-value tests (S3) |
| R10 | No device for the instrumented migration test | Medium (blocks) | Gate item 12; never report PASS without it |
| R11 | Reminder notification shows plan name on the lock screen | Low–Med (privacy) | Reuse BillReminder visibility; privacy guardian (S7b) |
| R12 | FG-26 requires symbols to exist → S1 docs fail | Low | D-12; docs move per slice |
| R13 | `ExpenseDao` diff too large to review | Medium | ≤12 queries per batch; reviewer-strict per batch |
| R14 | Wipe or privacy-mode misses plan tables | Medium (privacy) | S2b tests (D-2, D-5) |
| R15 | USD auto-record loops on FX-unavailable | Low | `AUTO_RECORD_DEFERRED_FX`, bounded counts; retried next run |
| R16 | DST/timezone bucket errors | Medium | Noon-local stamping; AE-19 (S5, S7a) |
| R17 | USD projections summed as EUR in home-currency engines | Medium (money) | S8b: existing engine conversion or partial/omit, never 0 or EUR |
| R18 | History-only model chosen late changes DDL | Medium | Decide before S2 (gate item 3) |

## Deferred to later increments
- Lent/borrowed "money owed" (B-4).
- BIWEEKLY/QUARTERLY frequencies (OD-8).
- Shared, split or not-mine purchase declaration (OD-9).
- Plan-data export (OD-11).
- Plan event table (OD-13).
- Health score Bill Reliability and SynthesisEngine wiring (OD-12).
- Hide-amounts mode (OQ-14).
- Attaching a standalone plan to a purchase later (OQ-15).
- Partial refunds.
- "Committed" budget hint.
- Cross-currency conversion of plan totals (needs RateBasis).

Out-of-scope observations for the orchestrator, not fixed here:
- `BackupVerifier.kt:522` ignores `sourceType`. (W0 re-run 2026-10-21: this reference is stale. No `sourceType` appears in BackupVerifier; the orphan checks sit at ~:517-530 and cover receipt_expense_links, recurring_occurrences and budget_forecasts only. S2b must add any plan-table checks.)
- Whether recurring upcoming-payment event metadata reaches any export is unverified.

Files referenced: `C:\Users\panos\Desktop\Cost-agregator\docs\features\loan-installments\gate2-decisions.md`, `C:\Users\panos\Desktop\Cost-agregator\docs\features\loan-installments\domain-spec.md`, `C:\Users\panos\Desktop\Cost-agregator\docs\features\loan-installments\impact-map.md`, `C:\Users\panos\Desktop\Cost-agregator\docs\features\loan-installments\ux-flows.md`, `C:\Users\panos\Desktop\Cost-agregator\workflows\active\loan-installments-handoff.md`, `C:\Users\panos\Desktop\Cost-agregator\scripts\validation-runner.ps1`, `C:\Users\panos\Desktop\Cost-agregator\config\validation\test-shards.json`.

---

## Orchestrator addendum — S0 resolution status (from `docs/features/loan-installments/discovery-s0.md`)

Merged after the plan was written; scout (Sonnet, read-only). Unresolved items remain first tasks of the slice that needs them.

| D | Status | Answer (short) | Effect on plan |
|---|---|---|---|
| D-1 | RESOLVED | No recurring "materialise" worker exists; `WorkerRegistry.kt:93-111` lists 7 workers; generation driver untraced | Gate item 1: reuse option does not exist → dedicated `InstallmentDueRecordingWorker`. Worker-count ratchet still to check in S7a |
| D-2 | PARTIAL | No `clearAllTables` / production erase-all found; clearing is per-DAO; `FinancialRescueCoordinator` copies explicit allow-list | S2b: add plan tables to rescue allow-list; wipe path to confirm (DataRetentionWorker, settings) |
| D-3 | PARTIAL | `ExpenseExportMapper.kt:20-92`, `ExportTransaction.kt:13`, `AccountingExportRepository.kt:115`, `AccountantReportPdfExporter.kt:43`, `ExportOptionsViewModel.kt:78`; CSV/JSON writers untraced | S9 first task: locate writers |
| D-4 | RESOLVED | Briefing reads `FinancialWeatherRepository` `upcomingItems` (sealed `UpcomingItem`: Recurring/Occurrence/Planned; exhaustive `when` at `DashboardBriefingInputBuilder.kt:87-91`) + `totalCommitted` | S8a exclusion seam = `UpcomingItem` variant / `totalCommitted`; `RecurringPlanProjectionService` not used by dashboard |
| D-5 | RESOLVED | 4 `BackupPrivacyMode`s; `ExportAnonymizer.sanitizeExport` explicit per-table; `expenses` NOT sanitised | S2b: explicit decision for plan `displayName`/`lenderLabel` |
| D-6 | PARTIAL | No 150→151 test exists (latest 147_148/148_149); pattern `migration_X_Y_*` + `runMigrationsAndValidate`; JVM Room test availability + device untraced | S2: follow pattern; device = gate item 12 |
| D-7 | RESOLVED | 20 defaults in `MerchantCategoryProvider.kt:11-32`; **no Loan/Mortgage/Bills/Debt category**; new defaults not back-filled for existing users | Gate item 5: keyword match step yields nothing → fallback/no preselection |
| D-8 | PARTIAL | Add expense has no currency choice (home currency only); private `CurrencyPicker` in `ReceiptScanScreen.kt:1379` + inline in `ReviewScreen.kt:1749`; TLC manual-USD/FX-unavailable behaviour untraced | S11: extract picker; TLC FX behaviour first task |
| D-9..D-20 | OPEN | Planner inputs | First task of the slice that needs them |

---

## S0 amendment — G2-6 convergence + S0 refresh (feature-planner, 2026-10-23; persisted by orchestrator)
Authority: MEC Gate 2 G2-6 (pre-approved plan edits), loan gate2 IM-3/IM-4, S0 refresh in the handoff. **Where this section and the slice text above differ, this section wins.** "MEC INV-18" = the manual-entry-currency invariant (not loan INV-18).

**A1. S6a**
- Goal adds MEC INV-18 (AE-28).
- `declareWithNewExpense` calls `createExpenseDbOnlyV2` with `userPresentRate = UserPresentRateRequirement(entryZone)`. `entryZone` comes from the caller and is never persisted or logged. This is the only place the flag is set; `declareOnExpense`, `recordDueEntries` and every other op leave it null. The file path is pinned by `ManualEntryCurrencySetterAllowListGuardTest`, so a rename needs owner approval (MEC N16).
- On `CreateExpenseResult.ValidationFailed` with non-null `rateRefusal`, return a typed refusal (reason mapped from `RateRefusalReason`) BEFORE inserting the plan/entries, so the whole declaration aborts. It is a normal return from the `withTransaction` lambda: no throw, no rollback flag, empty post-commit batch.
- Other non-Created results write nothing further. `DuplicateSkipped`/`InsertConflict` on the purchase map to bounded refusals.
- Tests (`…DeclareTest.kt`):
  - AE-28: USD 1,200.00 with an empty rate table is refused `NO_RATE` (no purchase/plan/entries); with the 15 Oct rate, all three commit together.
  - G2-12 pin test with real in-memory Room + real TLC: after a refusal, expenses/plan/entry tables are unchanged, `transaction_events` gains exactly the AE-20 count (2 rows) with no amount/currency/merchant/date/zone, and there are zero post-commit actions.
  - Contrast test: the AE-6 injected throw rolls back everything incl. events.
- **Decision (recommended, adopted):** the outer transaction COMMITS TLC's refusal events. TLC writes them via best-effort (`bestEffortEvent`, TLC.kt ~:810), so only a normal return keeps them, which is what G2-12 ("events commit, no row") requires.
- STRICT_EXTERNAL_ID replay caveat (also in the S3 re-check):
  - A replay returns `DuplicateSkipped(existingExpenseId)` or `InsertConflict`, never `Created`, and each is mapped explicitly.
  - `DuplicateSkipped` is adopted only if the existing row belongs to this entry; `InsertConflict` rolls back with a bounded refusal. The AE-6 retry test pins this.
- Depends: S3, S4d, S5, S2b; MEC A2 (committed 8195250f); D-10 (answered). The OD-15 stop-and-ask is removed.

**A2. S7a**
- `recordDueEntries` never sets `userPresentRate` (always null, so the create is unflagged; MEC AE-29). **No `AUTO_RECORD_DEFERRED_FX` branch.** Any future loan-local deferral must call `RateEligibilityEvaluator` (never re-implement the rule) and goes to the owner.
- `InstallmentRecordDueEntriesTest` adds the AE-29 null-flag test: the request handed to TLC has `userPresentRate == null`. A USD entry with an empty rate table is Created with the sentinel triple (0.0 / "" / 0.0) and exactly one MISSING_RATE diagnostic; there is no RateRefusal and no MANUAL_SAVE_BLOCKED_NO_RATE.
- Shard `runtime-ui` confirmed (covers `service.*`, `ui.*`).
- R15 replaced: | R15 | USD auto-record without a rate yields a sentinel row | Low (money display) | No loop is possible: auto-record is unflagged and never refused (MEC AE-29). The row is excluded from comparable-home-value totals; display via R17/S8b. AE-29 test in S7a. |
- Follow-up (domain-modeler, before S7a): domain-spec.md ~:577 and ux-flows.md ~:484 still describe AUTO_RECORD_DEFERRED_FX ("Waiting for an exchange rate").

**A3. S11** — new title: "Add expense: installments switch on the general currency control (Release 1.0 merge gate satisfied; needs MEC B2 and C)".
- The currency control is MEC slice B2's general control in `mode = INSTALLMENT`: options ∩ {EUR, USD}; an unsupported selection becomes Unset and is restored when installments are switched off (MEC UX-Q-6). No loan-specific currency control or plan-currency row. The submit branch passes the entry zone to `declareWithNewExpense`.
- Tests:
  - Struck: "USD purchase+plan" and "FX-unavailable refusal keeps the form (D-8)".
  - New: AE-1 via the new-expense path; AE-22 refusals as fixed strings; MEC AE-28 UI (JPY → Unset in installment mode; a NO_RATE refusal keeps the form with the shared `mec_` copy, e.g. `mec_block_stale` / `mec_block_unsupported`); non-installment save unchanged.
- Depends: S10a, S6a (declares the flag), S9, S4d, MEC B2 and C. D-8 and the currency-scope decision are closed by G2-6.
- Option-B stop-and-ask struck. New stop-and-ask: installment mode needs the general control changed beyond B2's INSTALLMENT mode.
- D-8 (addendum table): ANSWERED (G2-6), TLC refuses only flagged creates.
- Requested topics §1 and Human decisions item 2: SUPERSEDED by G2-6.
- OD-14 (plan currency ≠ purchase currency) stays for S12a's existing-expense declare sheet; G2-6 removes only the Add-expense plan-currency row.

**A4. S1** — D-12 contingency deleted. D-12 resolved:
- FG-26 (`verify_guard_docs_truth.py`) checks paths only (config/guards, config/baselines, docs/ci, scripts/ci, scripts/db_guard, scripts/guardrails), in indexed docs only (LEGAL_PATHS is indexed; ENGINE_INTERACTION_MAP and CODEBASE_SEGMENTS are not). All doc edits stay in S1.
- Trap: any `scripts/ci/...` path named in LEGAL_PATHS must exist in the same commit. Refresh `lastReviewedSha` in `docs/ci/GUARD_DOCUMENT_INDEX.yml` only if the verifier demands it.
- The "if it moved here per D-12" doc lines in S3/S6a/S7a are dropped. R12: resolved.

**A5. OD-15 not used** (D-10: `DatabaseWriteBarrier.runWrite` re-entrant :54-69; precedent ManualExpenseRepository :191-221).
- S3 stop-and-ask becomes "if re-checking against committed A2 shows the DbOnly op does not join the outer transaction". S3 depends on S2 and D-14.
- Human decisions item 10 and the OD-15 bullet in topic 4 are removed. R4: Low; D-10 answered; reconcile R1 remains the backstop.

**A6. S5** — STOP-AND-ASK (owner, at S5 start): `domain/installment/**` and `domain/schedule/**` are in no shard of `config/validation/test-shards.json`.
- Required config edit, no new shard:
  - `com.yourname.expensetracker.domain.installment.*` → `domain-a-m` (between income and intelligence);
  - `com.yourname.expensetracker.domain.schedule.*` → `domain-n-z` (between savings and sideeffect).
- Until approved, S5/S6a-c/S7a validate with `targeted-unit-test` filters only.
- `config/validation/test-shards.json` is added to the S5 write set.

**A7. Dependency summary**: `[Release 1.0 merged: satisfied] + S10a,S9,S4d + MEC B2 + MEC C → S11`.

Owner decisions from this amendment: only the A6 test-shards.json edit (at S5). Everything else falls under G2-6 / IM-3 / IM-4.

**A8. Carry-forwards from the S1 privacy-security-guardian review (2026-10-23; binding on later slices)**
- **S2b:** IM-9 default = every `redactsRawText` mode (and ANONYMIZED_EXPORT) blanks plan `displayName` and `lenderLabel`. This is an owner confirmation at the S2b stop-and-ask, because today ExportAnonymizer strips expense merchant/notes in NO mode (D-5), so "mirror expense free text" would strip nothing. ExportAnonymizer lives in data/privacy (on the cloud-guard surface), so S2b references plan tables by SQL table/column strings, never plan entity types.
- **S8a:** any new cloud-bound assembler file must be on the `installment_cloud_reachability` surface (exact file or a matching name pattern), with a negative fixture. Known cloud inputs outside the surface today: WarrantyTrackerRepository → CloudWarrantyExtractionService; AiChatRepositoryImpl history; DashboardDataProvider / FinancialWeatherRepository, which build the briefing input shared with Home. None is an S8b reader; re-check at S8a.
- **S8a AE-23 strengthened (differential):** the same fixture with and without plans must produce an identical `DashboardBriefingInput` and prompt, AND an identical TransactionInsight input. Cover the DailyBriefingWorker path too. Derived sums (totalCommitted, totalLikely, discretionaryBudget, headline/summary) must not change. A literal-string check alone is insufficient. The shared Home/briefing provider makes the S8a stop-and-ask likely.

**A9. Carry-forwards from the S1 architecture-guardian review (2026-10-23; binding on later slices)**
- **S2b (Medium):** InstallmentPlanArchitectureGuardTest rule PLAN_TABLE_RAW_SQL_WRITE (~:166-193) exempts only the coordinator and `data/database/dao/`. Infrastructure writers need exact-path exemptions covering the raw-SQL rule only, each with a self-check; no package-wide grants:
  - `data/privacy/ExportAnonymizer.kt` (A8 IM-9 `UPDATE installment_plans SET displayName…`);
  - `data/rescue/FinancialRescueCoordinator.kt` (`INSERT OR REPLACE INTO <table>`);
  - `data/database/AppDatabase.kt` / `DatabaseMigrations.kt` (only for future rebuild/backfill migrations; S2's CREATE TABLE/ALTER is not matched);
  - `DatabaseBackupRepositoryImpl.kt` only if restore fixups touch plan tables.
- **S2:** add a self-check that the S2 DDL (`CREATE TABLE/INDEX installment_*`, `ALTER TABLE expenses ADD COLUMN`) is not flagged.
- **S2/S2b/S6c (Low):** LEGAL_PATHS "ALL plan/entry writes" and "ONLY writer of spendingExclusionReason" must name the infrastructure exceptions (migrations, rescue copy, export anonymizer, wipe) and get db_ownership_policy entries. If plans carry `categoryId`, a CategoryDao-style reassign would be a second writer and must be fenced.
- **S5 (Low):** `domain/schedule/ExpenseScheduleLinkPolicy.kt` sits outside `domain/installment/`, so add that exact file to `PLAN_CODE_PREFIXES` (~:627-631) in the arch guard test. Segment 40 now lists it.
- **Info:** `stripCommentsKeepStrings` doesn't handle nested quotes inside string templates (`"${f("a//b")}"`); it affects the raw-SQL rules only. Optional hardening.

**A10. Carry-forwards from the S2 reviews (2026-10-23; binding)**
- **S2 policy rows moved to S6a:** the S2 write-set item "db_ownership_policy.yml + signatures accounting for InstallmentPlanDao" moves to S6a. db_access skips *Dao.kt and is deny-by-default per caller, so the first caller (the coordinator) must add its rows. Evidence: architecture-guardian + room-migration-guardian.
- **S3 (Medium, binding):** TransactionLifecycleCoordinator.updateExpense writes the caller's whole row (`expenseDao.update(finalExpense)`, ~:1420-1462). A stale editor save would silently reset spendingExclusionReason. Fix in S3: `finalExpense.copy(spendingExclusionReason = existing.spendingExclusionReason)` (existing is read in-txn ~:1338), plus a stale-save regression test. The other TLC partial updates use existing.copy (safe); create paths correctly start NULL.
- **S2b:**
  - FinancialRescueCoordinator (~:385-398) copies a fixed baseCols list that omits spendingExclusionReason and does not copy the plan tables. A rescued purchase would become full spending again (double count). S2b must copy the flag + plan tables together, or deliberately drop both and document it.
  - The ExportAnonymizer stripping test must cover ANONYMIZED_EXPORT and every redactsRawText mode for displayName/lenderLabel.
  - ExportAnonymizer is on the cloud-guard surface: use no Kotlin identifier containing "installment" (SQL strings are masked and fine), or ask the owner before any exemption.
  - Wipe / anonymizer infrastructure exceptions per A9.
- **S6a:**
  - insert entries only as SCHEDULED + unlinked (test it);
  - deletePlanById must clear the purchase expense flag in the same transaction;
  - re-read the plan status in-txn before claimEntryForExpense;
  - pass only bounded enum names as statusReason/adjustReason;
  - map ABORT constraint exceptions to reason codes, never persisting/logging messages.

**A11. (owner 2026-10-24, owner-approvals items 17, 18; binding)**
- **S6c:** must run plan reconcile R1–R5 under the backup barrier before the snapshot verify (S6a exposes the reconcile entry point), so a backup right after an expense delete/update never fails on a not-yet-reconciled plan; genuine unrepairable orphans still fail closed.
- **Rescue:** FinancialRescueCoordinator clears orphan INSTALLMENT_PURCHASE flags AND orphan INSTALLMENT_REVERSED flags (not the non-null linkedExpenseId of any REVERSED entry) in the import transaction, also when installmentTables is empty. Accepted cost: in that corrupted-source case the refunded installment counts as spending again.
