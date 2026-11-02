# Impact map: savings-goal-delete (UF-05)

impact-mapper wrote this map (read-only, Sonnet, `Cost-agregator-int` @ e6967db1, schema v151). The orchestrator persisted it on 2026-10-26 and appended an "Orchestrator verification" section at the end.
- **Path keys:** M = `app/src/main/java/com/yourname/expensetracker/`, T = `app/src/test/java/com/yourname/expensetracker/`.
- **Inputs:** `domain-spec.md` (INV-1..16, AE-1..16, O-1..O-8, SG-1..4). `ux-flows.md` was written in parallel. It chose a per-card overflow menu, so the card tap stays `selectGoal`.

## Headline findings (these differ from the domain spec)
1. **No guard edit is needed.**
   - Keep `deleteSavingsGoal(goal: SavingsGoal)` as the callable, with the same name, receiver and parameter types.
   - Keep `savingsGoalDao.deleteGoal` as the only DAO mutation in it.
   - Add only reads whose names don't start with a mutation verb.
   - Change the return type freely. It is not part of the policy identity.
   - Spec SG-2's assumption that the policy row "must change" holds only if the DAO write is renamed, for example to `deleteIfEmpty`.
2. **A third test breaks.** `T e2e/NotificationParserDashboardTotalSeamTest.kt:408` builds `SavingsGoalRepository(mockk(relaxed = true), savingsGoalDao)`. The constructor change breaks it, and that change is needed to inject `AppDatabase` and the history repository. Only the constructor call changes.
3. **The VM test needs no rewrite.** `T ui/screens/savings/SavingsGoalsViewModelWriteBlockedTest.kt:129-142` stubs `deleteSavingsGoal(any())` throwing the blocked exception and expects `WriteBlocked`. It still holds if the parameter stays `(SavingsGoal)`. The only necessary rewrite is `T data/repository/SavingsGoalRepositoryCurrencyTest.kt:58-72`: it constructs the repository at `:20` and asserts an unconditional delete for a goal with balance 30.0 at `:61-70`.
4. **Every existing savings string is untranslated and baselined**, for example `savings_goal_created_message` at `strings.xml:1576` and baseline `app/lint-baseline.xml:14449`. New strings must ship in `values`, `values-el` and `values-es` in the same change.
5. **History check.** `SavingsContributionHistoryRepository.getAllContributions()` (M data/repository/SavingsContributionHistoryRepository.kt:74-92) already returns the DataStore events plus all ledger rows. It throws `SavingsContributionHistoryUnavailableException` (`:32,76,89`). So the delete can use `getAllContributions().any { it.goalId == id }`, with no new DAO query and no ledger DAO injection.
6. **Entry point.** The card tap at `SavingsGoalsScreen.kt:201` is `selectGoal`, so delete goes in an overflow menu (ux-flows agrees).

## Impact summary
- **Segments:** domain/savings belongs to Segment 35 (`CODEBASE_SEGMENTS.md:729-735`). The rest is data/repository, di, ui/screens/savings and resources.
- **Legal paths:**
  - **Savings goal delete** (existing, amended): `SavingsGoalsViewModel.deleteGoal` → `SavingsGoalRepository.deleteSavingsGoal` → `SavingsGoalDao.deleteGoal`. `LEGAL_PATHS.md` has only "Savings Sweep Acceptance" (`:11-17`); the new "Savings Goal Deletion" subsection goes after `:17` (SG-4, after approval).
  - **Sweep acceptance:** unchanged. It is the serialization counterparty.
  - **Manual contribution:** unchanged.
- **Engines and consumers:** they read goals through the repository flow. The Room `getAllGoals()` flow re-emits on delete, so none needs a code change:
  - `SavingsGamificationEngine`, `SmartSavingsEngine`, `MonthlySavingsSweepUseCase`, `LifestyleSavingsPromptUseCase`
  - `CalculateFinancialForecastUseCase`, `DashboardDataProvider`, `DashboardContractsAdapter`, `FinancialHealthScoreV2`
  - `FinancialWeatherRepository`, `LegacyDataMigrationService`, STRICT reserves (`SynthesisEngine`)
- **Coordinators:** `SavingsSweepAcceptanceCoordinator` is unchanged. Its `getById ?: reject(GOAL_NOT_FOUND)` is at `:116-117`, the CAS at `:141-148`, and the CancellationException rethrow at `:183-184`.
- **Schema:** none for option (c), so no version bump. The FK CASCADE stays but can't be reached for non-empty goals.
- **Workers:** none.
- **Barrier:** `writeBarrier.checkWritesAllowed("SavingsGoalRepository.deleteSavingsGoal")` stays the first statement (`M data/repository/SavingsGoalRepository.kt:36`).
- **Error handling:** `UiWriteGuard.guardedWrite` (`M ui/util/UiWriteGuard.kt:28-38`) handles only `DatabaseAccessBlockedException`. The VM must add its own handling and rethrow CancellationException.
- **Privacy:** no permissions. The goal name is user text, so logs carry bounded codes only: `SAVINGS_GOAL_DELETE_HAS_BALANCE`, `_HAS_HISTORY`, `_HISTORY_UNAVAILABLE`, `_UNAVAILABLE`. Never log the name, the id or exception messages.
- **FG guards in scope:**
  - FG-10 cancellation, FG-12 UI/DAO ownership, FG-13 DB access and write barrier, FG-14 transaction ownership
  - FG-16 PII, FG-19 ignored/critical tests, FG-20 lint, FG-26 docs
  - FG-06 only if allowlist edits become unavoidable; FG-21 only for option (b)

## 1. Files touched, option (c)
- **Domain**
  - NEW `M domain/savings/SavingsGoalDeletionPolicy.kt`: a pure policy. It takes the facts (balance, history present, history unavailable) and returns `Deletable` or `Rejected(code)`. It fails closed on NaN, ±Infinity and negative balances.
  - NEW `SavingsGoalDeleteResult { Deleted, AlreadyAbsent, Rejected(reasonCode) }` in `M domain/savings/`.
  - EDIT `M domain/savings/SavingsGoalRepository.kt:13`: change the return type only. An optional read-only eligibility method for the UI must call the same policy (INV-16). There are no other implementors.
- **Data:** EDIT `M data/repository/SavingsGoalRepository.kt` (96 lines).
  - The constructor (`:14-17`) adds `AppDatabase` and `SavingsContributionHistoryRepository`.
  - `deleteSavingsGoal` (`:35-38`):
    - Steps, in order: barrier, then `database.withTransaction`, then `getById(id) ?: AlreadyAbsent`, then the facts, then the policy, then `savingsGoalDao.deleteGoal(<entity re-read in the transaction>)`, which returns `Deleted`.
    - Exceptions, in this order:
      - CancellationException is rethrown.
      - `DatabaseAccessBlockedException` is rethrown.
      - HistoryUnavailable returns `Rejected(HISTORY_UNAVAILABLE)`.
      - Any other Exception returns `Rejected(UNAVAILABLE)`.
  - Never pass `goal.toEntity()` to the delete (INV-2).
  - Optional comment-only fix for the stale DB-8 comment at `M data/database/entity/SavingsSweepPlan.kt:24-25`.
- **DI:** most likely unchanged.
  - The `@Inject` constructor and `@Singleton` stay.
  - The history repository is already `@Provides @Singleton` (`SavingsModule.kt:56-66`), and there is no cycle.
  - Confirm that `DashboardContractsModule.kt:53` still compiles.
- **UI**
  - `M ui/screens/savings/SavingsGoalsViewModel.kt:464-474`:
    - handle the result;
    - catch non-blocked exceptions and rethrow CancellationException (F-1);
    - on success, clear `selectedGoal` (INV-11);
    - add new events next to `:79-86`;
    - drop any recommendations that name the deleted goal.
    - The VM constructor is unchanged.
  - `M ui/screens/savings/SavingsGoalsScreen.kt` (896 lines): overflow entry, confirm dialog, not-allowed dialog and the event mapping, following `ux-flows.md`.
- **Resources:** new strings in `values`, `values-el` and `values-es`. `ux-flows.md` lists 9 keys.
- **Docs** (orchestrator, after approval): the `LEGAL_PATHS.md` subsection.

**Option (b) archive delta:**
- An additive nullable `archivedAt` column, at v153 after loan's v152.
- File touches: the entity and domain model, `AppDatabase`, the migrations, `app/schemas/153.json`, `BackupVerifier` expectations, and the `getAllGoals` filter.
- A new archive DAO mutation, so a new `db_ownership_policy.yml` row (an exception to the guard freeze).
- O-4 consumer decisions.
- Migration tests and room-migration-guardian.

**Option (a) delta:** no schema change. It destroys exact-tier ledger rows and accepted plans through the cascade, and leaves DataStore events orphaned. It needs a re-checked confirm token and explicit approval of destructive behaviour. Not recommended.

## 2. Guards
- **`config/guards/db_ownership_policy.yml:2903-2916`**
  - The identity is: path, owner, kind, method, receiver, ordered parameter types, DAO accessor, DAO FQCN and operation (`scripts/verify_db_access_boundaries.py:34-38`). **The return type and constructor shape are not part of it.**
  - The mediation keys use the same form: `GR-13_MEDIATION_INVENTORY.yml:1931-1932`, `GR-13_UNPROVEN_WRITERS.yml:1849-1850`, `GR-11_CORPUS.yml:790`.
- **Mutation detection** works by name prefix (`verify_db_access_boundaries.py:171-217`). Names like `getById`, `getAllContributions` and `hasLedgerForGoal` are not mutations.
- **The no-edit design:**
  - same callable name and parameters;
  - the only mutation inside is `savingsGoalDao.deleteGoal`;
  - read names don't start with a mutation verb;
  - the barrier sits before `withTransaction`, the same shape as the proven coordinator rows (`:2934-2961`, `barrierMode: helper`);
  - **no** new `DELETE … WHERE NOT EXISTS` DAO method.
- **Residual risk:** the barrier-proof analyzer (`scripts/db_guard/structural_analysis/barrier_proof.py`) must still see the barrier dominate the call inside `withTransaction`. If it doesn't, the edit is unavoidable. It would change one policy row (reason, maybe `barrierMode`) plus the generated inventories and the candidate/accounting files. That edit falls under the guard freeze and needs owner approval. Never edit `db_ownership_policy.legacy.gr08*.yml`.
- **Untouched:**
  - `config/db_access_allowlist.yml:442-448` is the legacy v1 allowlist and not authoritative.
  - `db_structural_exceptions` (no structural file operations).
  - The cancellation and PII baselines (no savings files appear in them, and new code must comply).
  - `db_raw_query_classification.yml` (unless a new `@Query` is added, so prefer `getAllContributions()`).

## 3. Tests
- **Rewrite (modify, never delete; SG-3):**
  - `T data/repository/SavingsGoalRepositoryCurrencyTest.kt:58-72`: assert `Rejected(HAS_BALANCE)` and that `deleteGoal` is never called. Keep the read half. Add a Deleted case for balance 0.0. Change the constructor call at `:20`.
  - `T e2e/NotificationParserDashboardTotalSeamTest.kt:408`: constructor call only.
  - `T ui/screens/savings/SavingsGoalsViewModelWriteBlockedTest.kt:129-142`: compiles unchanged. Extend it with the new event assertions.
- **Must stay unchanged:**
  - `T data/repository/SavingsSweepAcceptanceCoordinatorTest.kt:85-107` (pins the cascade and the month marker).
  - `T data/database/dao/SavingsGoalDaoTest.kt:87-94`.
- **Compile check:** `SavingsGoalsViewModelTest`, `DashboardContractsAdapterTest`, `FinancialWeatherRepositoryTest`, `EmptyZeroNullResilienceTest` and `CrossEngineParityMatrixTest`.
- **New:**
  - `T domain/savings/SavingsGoalDeletionPolicyTest.kt` (AE-16, the edge-case table).
  - `T data/repository/SavingsGoalRepositoryDeleteTest.kt`, using Robolectric and an in-memory Room database: AE-1..8, AE-12, AE-14, AE-15, and the race tests AE-9..11 with deterministic ordering.
  - VM tests: AE-13 and AE-15, plus `selectedGoal` clearing, deleting the last goal, the zero-goal header, and the `first_goal` pin.

## 4. Concurrency
- **Locking:** the database uses WAL mode (`AppDatabase.kt:8908`), and `withTransaction` takes the write lock, so writes are serialized. That lock model is an assumption and hasn't been checked against the pinned Room/SDK versions.
- **Delete vs sweep accept:** both run in `withTransaction`.
  - If the delete runs first, the accept's `getById` returns null, so `GOAL_NOT_FOUND`, and it rolls back.
  - If the accept runs first, the balance or history is non-empty, so the delete is rejected.
  - An "accept committed, then cascaded" outcome can't happen.
- **Delete vs manual contribution** (a Room UPDATE, then a DataStore write; not atomic):
  - A: delete first. The UPDATE matches 0 rows and returns false, so no event is written.
  - B: increment first. Balance is above 0, so the delete is rejected.
  - C: increment committed but no event yet. Still rejected, because balance is above 0.
  - Balance is the authoritative witness, since it never decreases (F-4).
- **Refinement:** read the DataStore history before the transaction, and re-check the balance and ledger inside it. This keeps the write lock from being held across a DataStore read.

## 5. Conflicts with in-flight work
The mapper had no shell. The orchestrator ran the git checks; see the verification section below.
- **Plans:**
  - Nothing plans edits to `SavingsGoalRepository`, the VM or the screen. The references in `money-correctness-plan.md:45,56`, `loan-installments-plan.md:48` and `restore-semantic-verify-plan.md:383` are not edits to these files.
  - `test-suite-audit-plan.md:324,329,357` (B6-18, B6-23, B8-5) moves or merges `SavingsGoalDaoTest` and `SavingsSweepAcceptanceCoordinatorTest`, which this feature relies on as unchanged pins. Coordinate the order.
- **Likely collision points:** append-only edits to `strings.xml` (all three languages) and `AppDatabase.kt` (only for option b).

## 6. Lint and strings
- **Baseline:** `app/lint-baseline.xml` in int has **2223** MissingTranslation entries. `verify_lint_baseline_policy.py:82-86` derives the cap from the baseline when no flag is passed. No other baseline entry types are allowed (`:68-94`). Do not regenerate it.
- **Translations:** new strings go into all three locales together. These are the first translated savings strings, so el/es sign-off is needed.
- **UnusedResources:** add each string only in the slice that references it.
- **UiText:** use `UiText.StringResource`, with no raw literals. The existing literal `"Unavailable"` is at `SavingsGoalsViewModel.kt:156`.

## 7. Tier, gates and validation
- **Tier: T3, at the low end since there is no schema change.** Reasons:
  - money-history integrity;
  - an irreversible write;
  - transaction ownership across the repository, history, coordinator and barrier;
  - FG-13 and FG-10 adjacency;
  - the goal name is a privacy surface;
  - the domain interface contract changes.
- **Gates:**
  - planner-first;
  - architecture-guardian (lifecycle/ownership and FG-13 mediation);
  - privacy-security-guardian only for the diagnostics check (bounded codes). The orchestrator decides whether it's needed;
  - reviewer-strict;
  - no room-migration-guardian for (c).
- **Validation** (validation-runner only, serial):
  1. `targeted-unit-test` with `SavingsGoalDeletionPolicyTest`, `SavingsGoalRepositoryDeleteTest`, `SavingsGoalRepositoryCurrencyTest`, `SavingsSweepAcceptanceCoordinatorTest`, `SavingsGoalDaoTest`, `SavingsGoalsViewModelTest`, `SavingsGoalsViewModelWriteBlockedTest`, `NotificationParserDashboardTotalSeamTest`, `DashboardContractsAdapterTest` and `FinancialWeatherRepositoryTest`.
  2. The data shard.
  3. `static-guards`: db-ownership, lint-baseline policy, cancellation and PII.
  4. Lint once at the end.
  - Full suites only at the gate.
- **Stop-gates:**
  - SG-1: any schema change.
  - SG-2: any guard, inventory or baseline edit (freeze).
  - SG-3: no test deletions or ignores.
  - Option (a).
  - el/es sign-off.
  - No commit, push or merge unless asked.

## 8. Slice sketch
- **S1 – policy.** Policy and result type (NEW), plus the policy test. Pure JVM.
- **S2 – repository transaction.**
  - Edits: the domain interface return type, the data repository, the currency-test rewrite, the seam-test constructor, the new delete test and the race tests.
  - **Run the db-ownership guard on S2 alone.** It decides whether the no-edit design holds. If it fails, stop and escalate (SG-2).
- **S3 – VM.** Events, `selectedGoal` cleanup, exception handling, VM tests.
- **S4 – strings (en/el/es).** Only the keys S5 uses. Lands together with S5.
- **S5 – screen.** Built from `ux-flows.md`.
- **S6 – docs.** The `LEGAL_PATHS.md` subsection and the optional `SavingsSweepPlan.kt` comment fix.

## 9. Owner questions (mapper recommendations)
- **O-1:** option (c) now, and a separate `savings-goal-archive` later.
- **O-2:** extend the repository rather than add a new coordinator, which would force new policy rows.
- **O-3:** yes, show a confirm dialog.
- **O-5:** accept that `first_goal` re-locks, and pin it with a test.
- **O-6:** defer RESTRICT.
- **O-7:** no cleanup job.
- **New:**
  - Rely on the no-policy-edit design, let the S2 guard run decide, and escalate only if it fails: yes.
  - Keep the `(goal: SavingsGoal)` parameter, with a KDoc saying the amounts are ignored: yes.
  - Entry point: an overflow menu (decided in ux-flows).
  - Baseline count 2223 vs 2219: see the verification section below.
  - Git evidence: see the verification section below.

## Orchestrator verification (2026-10-26, git, read-only)
- **Loan `feature/manual-entry-currency` @ 34b20715** (merge-base with int = bcd8e921): the savings paths differ only in `SavingsSweepMonthMigrationTest.kt` (+2). There is **no overlap** with this feature's write set.
- **App-lock tip 1efdda7e** (merge-base 25096c65): **no savings changes.**
- **`money-aggregates-sweep-plan.md`:** no savings mentions, so no conflict.
- **MissingTranslation:** int `app/lint-baseline.xml` has 2223 entries. The cap is auto-derived from the baseline (`verify_lint_baseline_policy.py:82`). The "2219" in earlier QF notes came from an older tree. Either way, the rule is: never add entries, and decreases are allowed. Nothing for the owner to decide.
