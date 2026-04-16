## Technical Plan

### Scope
- In: all **HIGH** rows under `### B.12: Groups/Shared Expenses Pipeline` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, limited to split-resolution parity, silent budget-offset failure masking, linked group/system-expense budget overcounting, historical equal-split member-deletion drift, and recurrence semantic drift across shared-expense-adjacent recurrence paths.
- Out: all **MEDIUM/LOW** B.12 rows, schema/entity/migration/index work, `SharedExpenseGroupsViewModel` service-boundary cleanup, repository/domain-port unification, `userId` cleanup in budget offset, dispatcher/time-provider cleanup, UI refactors, and any opportunistic B.9/B.11/B.33/B.40/B.43 spillover beyond the listed HIGH rows.
- Assumptions / unknowns:
  - `B.4` has already cleared the Phase B gate locally before this pipeline starts execution.
  - Existing `Expense` ownership fields (`isSharedExpense`, `myShareAmount`) are sufficient to normalize new/linked group expenses; no schema change is required.
  - There is no historical membership snapshot table for equal splits. The safe HIGH-scope fix is therefore **preventive** (block future destructive deletions that would rewrite history), not reconstructive.
  - `RecurrenceCalculator` is assumed to be the canonical semantic source for `IRREGULAR` unless reviewer evidence shows product intent differs. If that assumption is wrong, Batch 5 must update all recurrence consumers together.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SplitCalculatorTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SplitCalculatorStressTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculatorTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepositoryTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-40.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-43.md`
- create: `docs/reviews/REVIEW-B12.md`

### Implementation Steps

#### 1. Objective & Blast Radius
- **The core issue:** B.12 HIGH findings all stem from the shared-expense lane using multiple incompatible truths for the same concepts: split math, linked-expense ownership, member-history interpretation, and recurrence semantics. That drift lets budget totals disagree with settlements, lets valid failures masquerade as zero spend, lets linked group expenses count twice, and lets member deletion rewrite historical equal-split liabilities.
- **Blast radius:** `domain/logic/`, `domain/groups/`, `data/database/`, `data/repository/`, `domain/reminder/`, shared-expense and recurrence tests, and the B.12 registry / verification closeout files.
- **Primary downstream surfaces:** budget cards / adjusted budget spend, group balances and settlements, group expense creation/link flows, member deletion, recurring bill reminders, and any consumer relying on `Expense.effectiveAmount` for group-created expenses.

> [!WARNING]
> - Do **not** touch B.12 MEDIUM/LOW rows in this plan.
> - Do **not** change Room entities, schema versions, migrations, indices, table names, or column names.
> - Do **not** refactor `SharedExpenseGroupsViewModel` to consume domain services here; that is a separate MEDIUM row.
> - Do **not** collapse `DeleteGroupMemberResult` and `RemoveSharedExpenseMemberResult` into a new public contract in this epic.
> - If a fix appears to require a historical-member snapshot table or new persisted split metadata, stop and split; that is outside this HIGH-only lane.

#### 2. The Single Source of Truth
- **Split truth:** `SplitCalculator` + `CustomSplitParser` are the only approved shared-expense split engine. No B.12 caller may reparse `customSplitsJson`, divide by `members.size`, or invent separate rounding/remainder behavior.
- **Ownership truth:** `Expense.effectiveAmount` is authoritative for any linked system expense. Therefore `GroupTransactionCoordinator` must stamp/normalize linked system `Expense` rows using existing shared-expense fields so the stored row already reflects the current user’s liability.
- **Budget-offset truth:** `SharedExpenseBudgetOffsetEngine` may add group liabilities, but it must never double-count system expenses that are already linked to a group expense, and it must never hide infrastructure failures behind an all-zero breakdown.
- **Historical-membership truth:** because equal splits have no persisted roster snapshot, member deletion must be blocked whenever removing a member would cause current-member recomputation to mutate historical equal-split liabilities.
- **Recurrence truth:** `RecurrenceCalculator` is the only legal source for monthly-equivalent and next-date recurrence math; repositories/managers may delegate to it but may not re-implement recurrence semantics privately.

> [!WARNING]
> - Reuse existing columns only (`isSharedExpense`, `myShareAmount`); do not add new persistence fields to “remember” split history in this pipeline.
> - If a convenience helper is added around `SplitCalculator`, it must delegate to the existing canonical calculation path rather than introducing a second algorithm.
> - Preserve public repository signatures unless a strictly backward-compatible addition is unavoidable.
> - Leave MEDIUM semantics such as `getPendingReimbursement()` and `isExpenseFullySettled()` untouched unless a HIGH fix cannot compile without a tiny compatibility edit.

#### 3. File-by-File Execution Checklist (micro-batches)

##### Batch 1 — Canonical split math + overflow hardening
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SplitCalculatorTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/logic/SplitCalculatorStressTest.kt`
- Checklist:
  - [ ] `SplitCalculator.kt`: replace all `Int`-based cent storage/conversion with `Long` so equal and percentage splits cannot overflow above ~€21.47M.
  - [ ] `SplitCalculator.kt`: keep deterministic remainder distribution and existing malformed-custom-split fallback behavior unchanged except for the overflow fix.
  - [ ] `SplitCalculator.kt`: ensure downstream callers can reuse the existing canonical result map rather than re-implementing member-share lookup.
  - [ ] `SplitCalculatorTest.kt`: keep current golden expectations intact for equal/percentage/custom paths and add at least one large-amount happy-path assertion.
  - [ ] `SplitCalculatorStressTest.kt`: replace the current “documents broken overflow behavior” expectation with a regression proving large totals stay positive, finite, and sum correctly.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.SplitCalculatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.SplitCalculatorStressTest"`
- Rollback / stop rule:
  - If `Long` migration changes deterministic remainder order or breaks existing golden expectations beyond overflow repair, stop and fix parity before any downstream batch proceeds.
- Done when:
  - Large shared-expense amounts no longer overflow into negative cents.
  - Canonical split outputs for normal amounts remain unchanged.

##### Batch 2 — Budget offset parity, legacy linked-expense exclusion, and no silent zero fallback
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt`
- Checklist:
  - [ ] `SharedExpenseBudgetOffsetEngine.kt`: delete the open-coded `calculateMyShare()` / raw split parser path and resolve all group shares via `SplitCalculator.calculateSplitAmounts()`.
  - [ ] `SharedExpenseBudgetOffsetEngine.kt`: build the linked `expenseId` set from group expenses in-scope and exclude those linked system expenses from the “personal spend” bucket so legacy group-linked rows are not counted twice.
  - [ ] `SharedExpenseBudgetOffsetEngine.kt`: stop converting unexpected exceptions into an all-zero `BudgetSpendBreakdown`; let failure propagate (while preserving `CancellationException`) so callers can distinguish “error” from “real zero shared spend.”
  - [ ] `SharedExpenseBudgetOffsetEngine.kt`: keep the public `BudgetSpendBreakdown` shape stable; do not widen this batch into MEDIUM reimbursement-sign semantics.
  - [ ] `SharedExpenseBudgetOffsetEngineTest.kt`: add regressions for split parity with malformed custom payloads, linked group/system expense counted exactly once, and repository failure surfacing instead of returning zeros.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngineTest"`
- Rollback / stop rule:
  - If this batch requires changing `BudgetViewModel` or `BudgetScreen` public behavior beyond existing null/fallback handling, stop and split that UI fallout separately.
- Done when:
  - Budget offset uses the same split output as settlement math.
  - Linked group expenses can no longer appear as full personal spend plus an added group share.
  - Failures are no longer indistinguishable from genuine zero shared spend.

##### Batch 3 — Group expense create/link normalization for `effectiveAmount`
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt`
- Checklist:
  - [ ] `GroupTransactionCoordinator.kt`: in `createSystemExpenseAndLinkToGroup`, compute the current user’s authoritative share inside the transaction using the Batch-1 split truth.
  - [ ] `GroupTransactionCoordinator.kt`: persist that share onto the linked system `Expense` row via existing ownership columns so `effectiveAmount` already matches the user’s liability.
  - [ ] `GroupTransactionCoordinator.kt`: apply the same normalization in `addExpenseWithLink` for already-existing system expenses, without changing public signatures or unrelated expense fields.
  - [ ] `GroupTransactionCoordinator.kt`: fail closed if no current-user member can be identified; do not guess a share.
  - [ ] `GroupTransactionCoordinatorTest.kt`: add regressions proving both create-and-link and link-existing paths store the expected shared-expense ownership state, while inactive/non-member failures still roll back atomically.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.database.GroupTransactionCoordinatorTest"`
- Rollback / stop rule:
  - If normalization appears to require new columns or a migration, stop; only existing `Expense` ownership fields may be used here.
- Done when:
  - Newly created or newly linked group-backed system expenses store the user share once at the data source.
  - `effectiveAmount` becomes trustworthy for group-created system expenses going forward.

##### Batch 4 — Historical equal-split member-deletion guard
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseTest.kt`
- Checklist:
  - [ ] `GroupsRepositoryImpl.kt`: treat equal-split group expenses dated on/after `member.joinedAt` as historical references that block deletion, because current-member recomputation would change old liabilities.
  - [ ] `GroupsRepositoryImpl.kt`: keep existing payer/custom-split blocking logic intact; equal-split history blocking is additive, not a replacement.
  - [ ] `SharedExpenseManager.kt`: mirror the same rule on the domain-port deletion path so both access patterns reject the same historical-drift case.
  - [ ] Preserve existing result types (`CannotDeleteMemberReferencedInSplits`, etc.) for compatibility; broaden behavior internally rather than creating a new outward contract in this epic.
  - [ ] `GroupsRepositoryImplTest.kt`, `SharedExpenseManagerTest.kt`, and `SharedExpenseTest.kt`: update current “equal-split deletion succeeds” expectations and add a guard regression showing expenses before `joinedAt` do not falsely block deletion.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.GroupsRepositoryImplTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.groups.SharedExpenseManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.verification.SharedExpenseTest"`
- Rollback / stop rule:
  - If reviewer evidence shows the product requires true historical-member reconstruction rather than preventive blocking, stop and split a schema-backed follow-up; do not fake history in this batch.
- Done when:
  - Removing a member can no longer rewrite historical equal-split balances for future reads.
  - Both repository and domain deletion paths enforce the same prevention rule.

##### Batch 5A — Recurrence centralization in the canonical calculator + reminder manager
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculatorTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt`
- Checklist:
  - [ ] `RecurrenceCalculator.kt`: expose/lock the canonical monthly-conversion and next-date helpers for all supported frequencies, explicitly covering `IRREGULAR`, `SEMI_ANNUALLY`, and `ANNUALLY`.
  - [ ] `BillReminderManager.kt`: remove private recurrence math duplication and delegate monthly totals / next-date advancement to `RecurrenceCalculator`.
  - [ ] `RecurrenceCalculatorTest.kt`: add explicit regressions for `IRREGULAR`, `SEMI_ANNUALLY`, and `ANNUALLY` next-date and monthly-equivalent behavior.
  - [ ] `BillReminderManagerTest.kt`: update reminder expectations to assert delegation-aligned semantics rather than an independent private implementation.
  - [ ] Do **not** widen this batch into subscription candidate string parsing or UI label cleanup.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.RecurrenceCalculatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.reminder.BillReminderManagerTest"`
- Rollback / stop rule:
  - If the chosen `IRREGULAR` policy is found to conflict with product intent, update `RecurrenceCalculator` first and keep every consumer aligned in the same batch; do not leave mixed semantics behind.
- Done when:
  - Reminder math is a pure consumer of the canonical recurrence utility.
  - `IRREGULAR`, `SEMI_ANNUALLY`, and `ANNUALLY` behavior is explicit and test-locked.

##### Batch 5B — Recurrence centralization in the repository
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepositoryTest.kt`
- Checklist:
  - [ ] `RecurringExpenseRepository.kt`: delete the private recurrence implementation and delegate `addRecurringExpense()` next-date calculation to `RecurrenceCalculator`.
  - [ ] `RecurringExpenseRepository.kt`: preserve the active-only DAO contract and repository public API.
  - [ ] `RecurringExpenseRepositoryTest.kt`: add regressions proving repository-created `nextDate` values for `IRREGULAR`, `SEMI_ANNUALLY`, and `ANNUALLY` match `RecurrenceCalculator` exactly.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.RecurringExpenseRepositoryTest"`
- Rollback / stop rule:
  - If repository callers rely on the old divergent `IRREGULAR` behavior, document it in review and keep the lane blocked until all affected tests are reconciled to one canonical rule.
- Done when:
  - Repository recurrence semantics no longer drift from the shared calculator/reminder path.

#### 4. Verification Plan
- **Static verification after each batch:**
  - Re-read every modified file.
  - Confirm imports/signatures remain within the batch scope.
  - Grep for forbidden leftovers before moving on:
    - `toInt()` or `Int`-cent state inside `SplitCalculator.kt`
    - raw `expense.totalAmount / members.size` share math inside `SharedExpenseBudgetOffsetEngine.kt`
    - private raw split parsing inside `SharedExpenseBudgetOffsetEngine.kt`
    - `catch (e: Exception)` returning a zeroed `BudgetSpendBreakdown`
    - duplicate private `calculateNextDate` recurrence logic remaining in `RecurringExpenseRepository.kt` or `BillReminderManager.kt`
- **Serialized Gradle lane (orchestrator-owned):** B.12 verification must run one pipeline at a time per the playbook. Do not overlap B.12 compile/test work with other active Phase B pipelines.
- **Per-batch minimum gate:** `./gradlew.bat :app:compileDebugKotlin`
- **Targeted final B.12 verification lane:**
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.SplitCalculatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.SplitCalculatorStressTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngineTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.database.GroupTransactionCoordinatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.GroupsRepositoryImplTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.groups.SharedExpenseManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.verification.SharedExpenseTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.RecurrenceCalculatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.RecurringExpenseRepositoryTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.reminder.BillReminderManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.verification.CrossGroupIntegrationTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.e2e.SharedExpenseFlowTest"`
- **Review gate:** after all batches are complete, create `docs/reviews/REVIEW-B12.md` and require a full reviewer PASS before documentation closeout.

#### 5. Documentation & Registry Updates
- **Registry update:** in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, update the B.12 HIGH block (lines 646-652 in the current snapshot) to mark the resolved HIGH rows with `[RESOLVED BY B.12]` language. Leave MEDIUM/LOW rows untouched unless reviewer explicitly proves a downstream row became obsolete.
- **Batch report updates:** update only the matching HIGH findings in:
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-40.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-43.md`
- **Ordering rule:** follow the playbook order exactly: (1) registry, (2) final verification reports, (3) deep-analysis mirrors only if a mirror file is later discovered. No deep-analysis mirror files for B33/B40/B43 were found during planning, so assume none unless reviewer finds one.
- **Review artifact:** create `docs/reviews/REVIEW-B12.md` and ensure the same commit contains code + tests + review + documentation updates.

### Risks
- Historical equal-split corruption that already happened before this fix cannot be reconstructed without schema-backed membership history; this plan prevents new corruption but may not repair old deleted-member groups.
- Normalizing linked system expenses changes ownership fields on persisted `Expense` rows; targeted tests must prove analytics/budget consumers now read the intended share rather than the full amount.
- The stricter member-deletion guard may expose user-facing behavior changes (deletion now blocked where it previously succeeded), so tests and review notes must document that this is intentional correctness hardening.
- `IRREGULAR` recurrence semantics may have product ambiguity; all recurrence consumers must move together to whichever canonical rule reviewer accepts.

### Acceptance Criteria
- [ ] Shared-expense split math is canonicalized on `SplitCalculator`, and large totals no longer overflow into corrupted negative values.
- [ ] `SharedExpenseBudgetOffsetEngine` uses the canonical split pipeline, excludes linked system expenses from double counting, and no longer returns fake all-zero breakdowns on failure.
- [ ] Group-created and group-linked system expenses persist shared ownership fields so `effectiveAmount` matches the current user’s liability.
- [ ] Member deletion can no longer mutate historical equal-split liabilities for either repository or domain-port deletion paths.
- [ ] `RecurrenceCalculator`, `RecurringExpenseRepository`, and `BillReminderManager` follow one recurrence rule set for `IRREGULAR`, `SEMI_ANNUALLY`, and `ANNUALLY`.
- [ ] Reviewer PASS is obtained, and the B.12 registry / final-verification rows are updated in the same closeout commit.
