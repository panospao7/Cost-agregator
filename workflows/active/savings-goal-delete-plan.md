# Feature plan: savings-goal-delete (UF-05)

**Status: APPROVED (owner "Continue", plan gate 2026-10-26), NOT STARTABLE (owner: planning only).**
feature-planner wrote this plan (read-only, Sonnet, `Cost-agregator-int` @ e6967db1), and the orchestrator persisted it.

**Inputs** (all decisions closed): `docs/features/savings-goal-delete/{domain-spec,ux-flows,impact-map,decision-sheet}.md`.

**Path keys:** `M` = `app/src/main/java/com/yourname/expensetracker/`, `T` = `app/src/test/java/com/yourname/expensetracker/`. The line refs come from the artifacts plus spot-checks. Re-anchor them at S-pre.

- **Risk tier:** T3, at the low end (impact map §7). No schema change, no migration, no new permission or network use. All decisions are closed: O-1(c), G-1, G-2, T-1, T-2, R-1 and UX-1..6.
- **Base and lane:**
  - Worktree `C:\Users\panos\Desktop\Cost-agregator-sgd`, branch `feature/savings-goal-delete`, cut from `integration/ai-evolution-1a` @ e6967db1.
  - One writer lane. Validation is serial and goes through validation-runner only.
  - The implementer and the test-author are separate invocations with disjoint write sets.
  - Each slice runs implementer → test-author → validation batch.
  - Commits on the feature branch are allowed. There is no merge or PR unless the owner asks.
- **Legal path preserved:** `SavingsGoalsViewModel.deleteGoal` → `SavingsGoalRepository.deleteSavingsGoal` → `SavingsGoalDao.deleteGoal`.
  - The callable, its parameter type and its single DAO mutation stay the same.
  - Sweep accept and manual contribution are untouched.

## Guard spot-check
A new read-only `savingsContributionLedgerDao` field in `SavingsGoalRepository` is **not flagged, by static reading**. Checkpoint G is still the decider (SG-2).
1. **The v1 class-level list is not checked against fields.**
   - `config/db_access_allowlist.yml:444-448` already lists `savingsSweepPlanDao`, which is not a field of the class.
   - The `db_access` guard's allowlist is `None` ("Legacy ... superseded") at `scripts/ci/guard_registry.py:604`.
   - `scripts/db_guard/policy_legacy.py:266-274` validates only the shape of the list.
   - `scripts/verify_allowlist_compliance.py` checks entry hygiene only.
2. **v2 matches mutations by verb prefix.** `scripts/verify_db_access_boundaries.py:171-217` matches the pattern `\.\s*(verb)\w*\s*\(`. Reads named `get*` produce no (DAO, operation) pair.
3. **The barrier proof accepts the transaction wrapper.** `scripts/db_guard/structural_analysis/barrier_proof.py:61-66,236,244` models `withTransaction` as a guarded wrapper, proved by dominance. The coordinator rows prove the same shape (`db_ownership_policy.yml:2934-2961`, `barrierMode: helper`).
4. **Conclusion: keep the primary design** (ledger DAO injected, INV-17(d)). If G fails, STOP and escalate. Do not switch silently to the history-repository-only fallback, because it changes INV-17(d) and AE-18.
5. **Identity traps (binding on the S2 implementer):**
   - `savingsGoalDao.deleteGoal(...)` stays lexically inside `deleteSavingsGoal(goal: SavingsGoal)`, including inside the `withTransaction` lambda. Do not move it into a helper.
   - The method stays a plain member function, with the same parameter type and `receiver: null`.
   - The barrier literal `"SavingsGoalRepository.deleteSavingsGoal"` stays byte-identical and remains the first statement.
   - No new member or call may be named with a mutation verb (the list is at `:171-189`). Use names like `getSavingsGoalDeletionEligibility` (public) and `gatherDeletionFacts` (private, reads only).
   - Use `return@withTransaction`.
   - Compare the balance as a primitive: `balance == 0.0`, so -0.0 counts as zero (INV-18).

## Slices

### S-pre. Setup and re-anchor (orchestrator + Sonnet scout, read-only apart from creating the worktree)
1. Confirm `HEAD == e6967db1`. If integration has moved, diff the in-scope files and re-anchor. If anything changed semantically, re-plan.
2. Confirm the schema is still v151 (`AppDatabase.kt:33`).
3. Grep `app/src`, including androidTest, for:
   - `deleteSavingsGoal(`;
   - `SavingsGoalRepository(`;
   - fakes implementing the domain interface;
   - `just Runs` stubs on `deleteSavingsGoal`.
4. Confirm the repository is the only product caller of `savingsGoalDao.deleteGoal` / `.deleteGoal(` in `app/src/main` (INV-7).
5. Confirm there is no Hilt cycle: `SavingsContributionHistoryRepository` must not depend on `SavingsGoalRepository` (`di/SavingsModule.kt:56-66`). `DashboardContractsModule.kt:53` must still compile.
6. Record baseline hashes or diff-stat anchors for `config/guards/*`, `config/db_access_allowlist.yml`, `app/lint-baseline.xml` (2223 MissingTranslation entries) and `app/schemas`. These anchor AE-23 and AE-24.
7. Run validation-runner `-Action Plan` / `-Action Profiles` to confirm:
   - the targeted filter grammar;
   - the parameter name for `registered-guard`;
   - the guard ids `db_access` and `db_artifact_sync` (`guard_registry.py:598,410`);
   - the data shard name in `config/validation/test-shards.json` (not verified yet).
8. Check whether a Compose/Robolectric test pattern exists for `SavingsGoalsScreen`, and whether a string-parity test exists. This decides S5's tests.
9. T-2: find the status of B6-18, B6-23 and B8-5 in `test-suite-audit-plan.md`, and run `git log` on the savings test paths.
- **Stop:** any premise fails (extra implementors or fakes, a Hilt cycle, a second caller of `deleteGoal`). Report before S1.

### S1. Policy and result types (pure domain)
- **Goal:** create the single owner of the emptiness rule, `SavingsGoalDeletionPolicy`.
  - **Input facts:** the balance, whether ledger rows or events exist for this id, and whether the history is unavailable.
  - **Output:** `Deletable` or `Rejected(code)`.
  - **INV-18 precedence:**
    1. If the balance is not exactly `0.0`: `HAS_BALANCE`.
    2. Else, if any ledger row or event exists: `HAS_HISTORY`.
    3. Else, if the history is unavailable: `HISTORY_UNAVAILABLE`.
    4. Else: `Deletable`.
  - A missing goal is handled in the repository, not the policy.
- **Invariants:** INV-1, INV-14 (bounded constants), INV-16, INV-18.
- **Source** (implementer, all NEW):
  - `M domain/savings/SavingsGoalDeletionPolicy.kt`, with the constants `SAVINGS_GOAL_DELETE_HAS_BALANCE`, `_HAS_HISTORY`, `_HISTORY_UNAVAILABLE` and `_UNAVAILABLE`.
  - `M domain/savings/SavingsGoalDeleteResult.kt`:
    - `SavingsGoalDeleteResult { Deleted, AlreadyAbsent, Rejected(reasonCode) }`;
    - `SavingsGoalDeleteEligibility { Deletable, AlreadyAbsent, Rejected(reasonCode) }`.
  - The shapes are proposals and may be adjusted if INV-16/18 still hold. No Android imports, no arithmetic, no logging.
- **Tests** (test-author, NEW): `T domain/savings/SavingsGoalDeletionPolicyTest.kt`.
  - Balance table: `0.0`, `-0.0`, `0.01`, `NaN`, `±Infinity`, negative.
  - AE-19 rows:
    - balance 10 with history unavailable → `HAS_BALANCE`;
    - ledger row with history unavailable → `HAS_HISTORY`;
    - no row with history unavailable → `HISTORY_UNAVAILABLE`;
    - events present with history available → `HAS_HISTORY`.
  - AE-16, the policy half.
- **AE:** AE-16 (policy), AE-19, and the AE-3 variant (predicate).
- **Depends on:** S-pre.
- **Gates:** architecture-guardian, folded into the S2 pass.
- **Validation:** Batch 1. An S1-only `targeted-unit-test` with `*SavingsGoalDeletionPolicyTest` is optional.
- **Stop:** the policy needs anything that isn't a pure function of its inputs.
- **Done:** the policy table passes, and no emptiness logic exists anywhere else.

### S2. Repository transaction, with guard checkpoint G
- **Goal:** extend `deleteSavingsGoal` in place, in this order:
  1. The barrier is the first statement.
  2. Read `SavingsContributionHistoryRepository.getAllContributions()` **outside** the transaction. Capture the result (events present, absent, or unavailable) as a fact; do not throw.
  3. Run the check and delete inside `database.withTransaction { ... }`:
     - `savingsGoalDao.getById(id) ?: return@withTransaction AlreadyAbsent`;
     - take the balance from the row re-read inside the transaction;
     - `ledgerDao.getAll().any { it.goalId == id }`;
     - apply the policy;
     - if `Deletable`: `savingsGoalDao.deleteGoal(<re-read entity>)` and return `Deleted`.
  4. Handle exceptions in this order:
     - `CancellationException`: rethrow.
     - `DatabaseAccessBlockedException`: rethrow.
     - any other `Exception`: `Rejected(UNAVAILABLE)`.
     - History-unavailable is a fact that maps to `Rejected(HISTORY_UNAVAILABLE)`, not an exception path.
  - Never pass `goal.toEntity()` to the delete (INV-2). The KDoc says only `goal.id` is used (G-2).
  - Add a read-only `getSavingsGoalDeletionEligibility(goalId)` on the interface and the implementation. It uses the same fact-gatherer and policy, and writes nothing.
  - No repository logging. If a log is ever unavoidable, it carries only the bounded code and at most the exception's simple class name.
- **Invariants:** INV-1..10, INV-12..15, INV-17, INV-18, INV-19.
- **Source** (implementer):
  - EDIT `M domain/savings/SavingsGoalRepository.kt`: the return type at `:13` and the new eligibility member.
  - EDIT `M data/repository/SavingsGoalRepository.kt`: the constructor gains `AppDatabase`, `SavingsContributionHistoryRepository` and `SavingsContributionLedgerDao`. `@Singleton` and `@Inject` stay.
  - The DI modules are not expected to change. If the compile or Hilt check forces a change, report first.
  - **No VM edit.** A one-line compile fix in the VM needs explicit approval.
  - No new `@Query`, no DAO method, no `db_raw_query_classification.yml` edit.
- **Tests** (test-author):
  - EDIT `T data/repository/SavingsGoalRepositoryCurrencyTest.kt`:
    - the constructor call at `:20`;
    - `:58-72` now asserts `Rejected(HAS_BALANCE)` and that `deleteGoal` is never called;
    - keep the read half;
    - add a `Deleted` case for balance 0.0.
    - Nothing is deleted or ignored (T-1).
  - EDIT `T e2e/NotificationParserDashboardTotalSeamTest.kt:408`: the constructor call only.
  - NEW `T data/repository/SavingsGoalRepositoryDeleteTest.kt` (Robolectric, in-memory Room):
    - AE-1..8;
    - AE-12, with corrupt DataStore on an empty, an absent and a funded goal;
    - AE-15, the repository half: no secret text and no goal name in the logs;
    - AE-18 and AE-3, with the production-wired history repository seeing the ledger row;
    - AE-20, the repository half: eligibility mutates nothing and returns the same code as the delete;
    - INV-15: other goals stay bit-identical;
    - AE-6, the repository half.
  - NEW `T data/repository/SavingsGoalRepositoryDeleteConcurrencyTest.kt`:
    - AE-9 and AE-10, in both orders, deterministic, using the real sweep coordinator and a gate;
    - AE-11;
    - AE-14: cancellation mid-transaction rolls back with no emission, and cancellation during the pre-read touches nothing;
    - AE-17: the history read happens while `!database.inTransaction()`. A spy hook interleaves `incrementSavingsGoalAmount` between the pre-read and the transaction.
  - Rules for these tests:
    - every latch has a timeout;
    - nothing is added to `config/validation/known-hanging-tests.json`;
    - copy fixtures from `SavingsSweepAcceptanceCoordinatorTest` without editing that file.
    - These tests prove the logic's serialization, not SQLite locking (the WAL assumption isn't checked).
  - Pins that stay unchanged: `SavingsSweepAcceptanceCoordinatorTest.kt:85-107` and `SavingsGoalDaoTest.kt:87-94`.
- **AE:** AE-1..12, AE-14, AE-15 (repository), AE-17, AE-18, AE-19 (absent), AE-20 (repository), AE-23 (G).
- **Depends on:** S1.
- **Gates** (max 2 review + 2 debug iterations, then PARTIAL/BLOCKED):
  - checkpoint G;
  - architecture-guardian (legal path, transaction ownership, FG-13/FG-14, plus S1);
  - room-migration-guardian (writer ownership and atomic check-and-delete);
  - privacy-security-guardian (bounded codes).
- **Validation:** Batch 1, then G.
- **Stop:**
  - **G fails: STOP and escalate to the owner.** No edit to any guard, policy, inventory, allowlist or baseline. Never touch `db_ownership_policy.legacy.gr08*.yml`.
  - A new DAO query or `@Query` is needed.
  - A Hilt cycle.
  - A compile break that needs files outside the write set.
  - A Robolectric hang that persists after 2 debug iterations.
  - A cancellation or PII guard hit.
- **Done:**
  - Batch 1 is green and G passes.
  - The diff stat shows no change under `config/`, `app/schemas/` or `app/lint-baseline.xml`.
  - The guardians pass.

#### Checkpoint G (S1+S2 only in the tree)
- **Runs:** `registered-guard` with guard id `db_access` (`guard_registry.py:598`), then `registered-guard` with `db_artifact_sync` (`:410`). If the runner rejects the guard-id argument, fall back to `static-guards`.
- **PASS:** continue to S3.
- **FAIL:** STOP and escalate to the owner with the persisted diagnostic (guard freeze until 2026-12-01).
  - If the diagnostic names only the new DAO field, offer the history-repository-only fallback. It changes INV-17(d) and AE-18, so it needs owner approval.
  - If the diagnostic names the barrier proof or the policy identity, the owner decides on a guard edit.

### S3. ViewModel
- **Goal:** replace the unguarded `deleteGoal` (`SavingsGoalsViewModel.kt:464-470`) and fix F-1.
  - **State:** `DeleteFlowState` on `SavingsGoalsState`: `Idle | Checking(goalId) | Confirm(goalId, name) | NotAllowed(goalId) | Deleting(goalId)`.
  - **Events** (next to `:79-86`): `GoalDeleted` and `DeleteRejected(reason: NOT_EMPTY | HISTORY_UNAVAILABLE | GENERIC)`. These are bounded enums with no text.
  - **`requestDeleteGoal(goalId)`** calls the eligibility check:
    - `Deletable` → `Confirm`;
    - `HAS_BALANCE` or `HAS_HISTORY` → `NotAllowed`, with no event;
    - `HISTORY_UNAVAILABLE` → `DeleteRejected(HISTORY_UNAVAILABLE)`;
    - `UNAVAILABLE` → `DeleteRejected(GENERIC)`;
    - `AlreadyAbsent` → the Deleted path (UX-4).
  - **Keep the public `deleteGoal(goal: SavingsGoal)`.** It becomes the confirm path:
    - set the state to `Deleting`;
    - inside `guardedWrite`, catch `CancellationException` → rethrow, `DatabaseAccessBlockedException` → rethrow, and any other `Exception` → `DeleteRejected(GENERIC)`;
    - on `Deleted` or `AlreadyAbsent`, emit `GoalDeleted`, and apply INV-11 in one place: clear `selectedGoal` if the id matches, and drop `smartRecommendations` and `sweepRecommendation` that name the id.
    - A `Rejected` result maps to its matching event.
    - Every terminal state returns to `Idle`. Cancellation goes to `Idle` with no message.
  - **`dismissDeleteFlow()`** is ignored while `Deleting`.
  - The VM holds no emptiness logic (INV-16) and never logs the goal name, id or amount (INV-14).
  - **UX-6:** check the header for zero goals (`:156`; `SavingsGoalsScreen.kt:288,312`) and pin the real values. If "Unavailable" shows for an empty list, fix it: a VM-side fix goes here, a render-side fix goes in S5. **If it needs a 10th string key, STOP and ask the owner.**
- **Invariants:** INV-11..14, INV-16.
- **Source** (implementer): EDIT `M ui/screens/savings/SavingsGoalsViewModel.kt`. The constructor is unchanged.
- **Tests** (test-author):
  - EDIT `T ui/screens/savings/SavingsGoalsViewModelWriteBlockedTest.kt` (extend `:129-142`): a blocked delete → `WriteBlocked`, the flow returns to `Idle`, and there is no `GoalDeleted` or `DeleteRejected`.
  - NEW `T ui/screens/savings/SavingsGoalsViewModelDeleteTest.kt`:
    - AE-13;
    - AE-14 (VM half);
    - AE-15 (VM half): "secret text" never escapes, and the logs are clean;
    - AE-16 (VM half);
    - AE-20 (VM half);
    - AE-21 (the zero-goal header);
    - a double tap, and disabled dialog buttons while `Deleting`;
    - deleting the last goal.
  - AE-22 (`first_goal` re-lock): if the VM test mocks the engine, add NEW `T domain/savings/SavingsGamificationFirstGoalReLockTest.kt` against the real engine. Do not edit existing engine tests.
  - Compile-only (no edits): `SavingsGoalsViewModelTest`, `DashboardContractsAdapterTest`, `FinancialWeatherRepositoryTest`, `EmptyZeroNullResilienceTest`, `CrossEngineParityMatrixTest`.
- **AE:** AE-6, 13, 14, 15, 16 and 20 (the VM halves), plus AE-21 and AE-22.
- **Depends on:** S2, and G PASS.
- **Gates:** architecture-guardian (no rule duplication) and privacy-security-guardian (cancellation, no name or id in logs).
- **Validation:** Batch 2.
- **Stop:**
  - a 10th string key is needed for UX-6;
  - the VM logs the goal name, id or amount;
  - a cancellation guard hit.
- **Done:** Batch 2 is green, and the S2 tests are still green.

### S4/S5. Strings (EN+EL+ES) and screen, one slice
S4 lands inside S5, so that each key exists only where it is referenced (UnusedResources).
- **Goal:** build the UI per `ux-flows.md`.
  - A MoreVert overflow menu with a single "Delete" item on every `GoalCard`, in both variants. The touch target is 48dp, placed after quick-add. Tapping the card still runs `selectGoal`.
  - **D1, confirm:** an `AlertDialog` that names the goal. The Delete button uses the error colour.
  - **D2, deleting:** buttons disabled and a progress indicator. Outside taps and Back are ignored.
  - **D3, can't delete:** neutral, with `action_got_it`.
  - **Snackbars:** `GoalDeleted` and `DeleteRejected(...)` go through the existing `SnackbarHost` with `withDismissAction = true`. `WriteBlocked` keeps its existing message.
  - Clear the screen-local `recommendationToConfirm` (`:45`) when its goal leaves `state.goals`. D1 and D3 dismiss silently if their goal disappears.
  - Focus return and accessibility follow ux-flows §6. Compose holds no emptiness logic (INV-16).
- **The 9 canonical keys** (in values, values-el and values-es together):
  - `savings_goal_more_actions_cd_format`
  - `savings_goal_delete_confirm_title`
  - `savings_goal_delete_confirm_message_format`
  - `savings_goal_delete_blocked_title`
  - `savings_goal_delete_blocked_message`
  - `savings_goal_deleted_message`
  - `savings_goal_delete_not_empty_message`
  - `savings_goal_delete_history_unavailable_message`
  - `savings_goal_deleting_cd`
- **Reused keys:** `action_delete`, `action_cancel`, `action_got_it`, `error_delete_failed`. Recheck their el coverage at S-pre.
- **String rules:**
  - Escape `\'`, and write the quotes around `%1$s` as `\"`.
  - Use the same positional arguments in all three locales.
  - Use `UiText.StringResource`, never raw literals.
  - The goal name appears only as a format argument, in the confirm dialog and the overflow content description.
  - The implementer drafts EL and ES. **The owner signs off before merge, and merge is blocked until then.**
- **Invariants:** INV-20, INV-11 (screen-local), INV-16, UX-1..6.
- **Source** (implementer):
  - EDIT `M ui/screens/savings/SavingsGoalsScreen.kt`.
  - EDIT `app/src/main/res/values/strings.xml`, `values-el/strings.xml`, `values-es/strings.xml` (append-only).
  - `SavingsGoalsViewModel.kt` only for a UX-6 fix (a declared exception).
  - `app/lint-baseline.xml` stays byte-identical.
- **Tests** (test-author):
  - Compose/Robolectric tests only if S-pre found an existing pattern or a parity test.
  - Update the AE-21 pin if the UX-6 fix lands.
  - Otherwise the evidence is lint plus the manual accessibility checklist (ux-flows §6), signed off at the candidate.
- **AE:** AE-24, AE-13 (screen-local), UX-3/4/5.
- **Depends on:** S3.
- **Gates:** privacy-security-guardian (the name only as a format argument, never logged) and architecture-guardian (UiText, no logic in Compose).
- **Validation:** Batch 3.
- **Stop:**
  - quote or apostrophe handling can't be made to work in all locales;
  - a 10th key is needed;
  - MissingTranslation goes above 2223, or the baseline changes (never regenerate it);
  - any UnusedResources hit.
- **Done:**
  - compile and lint are green;
  - all 9 keys exist in all three locales;
  - AE-24 holds;
  - el/es sign-off has been requested. Merge stays blocked until the owner signs off.

### S6. Docs and one comment (applied by the orchestrator; the comment edit may be delegated)
- **Goal:**
  - Add a "Savings Goal Deletion" subsection to `docs/architecture/LEGAL_PATHS.md` after `:17`, using the spec §3 text.
  - Fix the stale DB-8 comment at `M data/database/entity/SavingsSweepPlan.kt:24-25` (ACCEPTED plans are provenance). Comment only.
- **Invariants:** SG-4, F-2. No tests, and `app/schemas` must not change.
- **Gates:** FG-26, via `static-guards` at the candidate.
- **Depends on:** S2.
- **Stop:** FG-26 flags inventory or segment drift. Use the documented generator, or ask the orchestrator. Never grow a baseline or allowlist.
- **Merge note:** the main tree has pending edits in `LEGAL_PATHS.md`, `CODEBASE_SEGMENTS.md` and `ENGINE_INTERACTION_MAP.md`, so expect a rebase.

### Candidate gate
1. Run a combined targeted run (the union of the Batch 1 and Batch 2 filters).
2. Run the data shard.
3. Run full `static-guards`: db-ownership, FG-10, FG-16, FG-20, FG-19 and FG-26.
4. Run reviewer-strict (Opus) after the guardians.
5. Confirm:
   - nothing changed under `config/guards/*`, `config/db_access_allowlist.yml`, `app/lint-baseline.xml` or `app/schemas`;
   - `git grep savingsGoalDao.deleteGoal` in `app/src/main` finds only the repository.

## Validation batches (validation-runner only, serial; poll the run ID)
The filter grammar is confirmed at S-pre. Targeted runs compile the whole test source set, and a filter holds at most 50 entries.

| Batch | When | Profile | Filter or detail |
|---|---|---|---|
| 1 | After S1+S2 | `targeted-unit-test` | `*SavingsGoalDeletionPolicyTest,*SavingsGoalRepositoryDeleteTest,*SavingsGoalRepositoryDeleteConcurrencyTest,*SavingsGoalRepositoryCurrencyTest,*SavingsSweepAcceptanceCoordinatorTest,*SavingsGoalDaoTest,*NotificationParserDashboardTotalSeamTest,*DashboardContractsAdapterTest,*FinancialWeatherRepositoryTest,*SavingsGoalsViewModelTest,*SavingsGoalsViewModelWriteBlockedTest` |
| G | Right after Batch 1 is green, with only S1+S2 in the tree | `registered-guard` ×2 | `db_access`, then `db_artifact_sync`; fallback `static-guards` |
| 2 | After S3 | `targeted-unit-test` | `*SavingsGoalsViewModelDeleteTest,*SavingsGoalsViewModelWriteBlockedTest,*SavingsGoalsViewModelTest,*SavingsGamificationFirstGoalReLockTest` (the last only if that file exists) |
| 3 | After S5 | `compile`, then `lint` once | Re-run the Batch 2 filter if the VM was touched for UX-6. MissingTranslation must stay ≤ 2223, and the baseline must not change. |
| Candidate | After S6 | `targeted-unit-test`, then `unit-test-shard`, then `static-guards` | Union of the Batch 1 and 2 filters. Data shard name from S-pre. |

**Not planned:**
- `migration-tests`: there is no schema change.
- `unit-tests` and `app-check`: only if the data shard shows cross-suite fallout, or reviewer-strict or a release gate demands it.
- `connected-tests`.
- A second lint run.

**Optional:** run `registered-guard` for the cancellation and PII guards after S3.

## Gates
| Slice | architecture-guardian | room-migration-guardian | privacy-security-guardian | reviewer-strict |
|---|---|---|---|---|
| S1 | with S2 | no | no | candidate |
| S2 | yes | yes (writer ownership, atomicity) | yes | candidate |
| S3 | yes | no | yes | candidate |
| S5 | yes | no | yes | candidate |
| S6 | no | no | no | candidate |

Each slice gets at most 2 review and 2 debug iterations, then it is handed back as PARTIAL or BLOCKED.

## T-2: ordering against `test-suite-audit-plan.md`
The audit plan touches tests this feature relies on, but this feature edits none of those files:
- B6-18 merges or rewrites `SavingsSweepAcceptanceCoordinatorTest`.
- B8-5 moves `SavingsGoalDaoTest`.
- B6-23 merges `BackupVerifierSavingsLedgerTest`.

This feature relies on the cascade/month-marker pin (`:85-107`) and the DAO delete pin (`SavingsGoalDaoTest.kt:87-94`).
- **Whichever lands second rebases.**
- If an audit item lands first, re-cut the base and update the Batch 1 filter and the AE-8 fixtures.
- If this feature lands first, tell the audit lane, when B6-18 is dispatched, to keep the cascade pin, the marker pin and the sequential "accept then delete via DAO" case.
- Match those tests by name and behaviour, not by line number.

## Human decisions remaining
- **el/es sign-off before merge** (blocks the merge).
- **Escalation if G fails:** a guard edit during the freeze, or the history-repository-only fallback.
- **Escalation if UX-6 needs a 10th key.**
- **No merge or PR** unless the owner asks.

## Deferred
- `savings-goal-archive` (v153 after loan v152): `archivedAt`, O-4 and O-6 (RESTRICT).
- No orphan-event cleanup job (O-7).
- Any future path that lowers a goal's balance reopens INV-17 (F-4).
- Option (a) hard delete and typed confirmation: rejected.

## Open risks
1. **Barrier proof inside `withTransaction`** is reasoned from source only. G decides; if G fails, the feature freezes at S2.
2. **Policy identity traps.** A helper extraction or a verb-named member would force a guard edit. See the binding notes above.
3. **Return-type ripple.** `just Runs` stubs or fakes may break. S-pre greps for them, and anything outside a write set goes back to the orchestrator.
4. **Race-test determinism.** The tests don't prove SQLite locking (WAL unchecked), and Robolectric can hang. Every latch needs a timeout.
5. **There may be no screen tests.** S5 would then rely on lint, VM tests and a manual a11y checklist.
6. **The UX-6 outcome is unknown.** It may cross the S3/S5 boundary or need a 10th key.
7. **`getAll()` reads every ledger row inside the transaction.** The ledger is small (one row per monthly sweep allocation), and the spec accepts this.
8. **FG-26 docs drift** for the new classes.
9. **Merge friction** in `strings.xml` across all three locales, and in `LEGAL_PATHS.md`.
10. **The pre-check maps `AlreadyAbsent` to the GoalDeleted path** (the planner's reading of UX-4). Confirm in S3 review.
11. **Stale line refs.** Re-anchor at S-pre if integration has moved.

## Exact first action when the owner lifts "planning only" and approves this plan
```
git -C C:\Users\panos\Desktop\Cost-agregator-int worktree add -b feature/savings-goal-delete C:\Users\panos\Desktop\Cost-agregator-sgd integration/ai-evolution-1a
git -C C:\Users\panos\Desktop\Cost-agregator-sgd rev-parse HEAD   # expect e6967db1
```
- If HEAD = e6967db1:
  1. Run the S-pre scout checklist (Sonnet).
  2. Run validation-runner `-Action Plan` / `-Action Profiles`.
  3. Start S1: implementer, then test-author.
- If HEAD differs, diff the in-scope files first, then re-anchor or re-plan.
