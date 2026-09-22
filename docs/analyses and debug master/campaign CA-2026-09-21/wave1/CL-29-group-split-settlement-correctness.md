# CL-29 Implementation Spec — Group split and settlement correctness
Provenance: 2026-09-22; pin 37601232 verified; astra high Stage-3 session; source cluster-map.md.

## GATE

- **UPDATE 2026-09-22 (post-spec): GATE LIFTED.** All member findings verified 13/13 CONFIRMED by independent adversarial pass (ZCode/GLM-5.3, different model+session from finder) in `../verification-2026-09-22-wave1.md`; journal PHASE-2-VERIFICATION 2026-09-22T17:3x. Implementation may proceed.
- Implementation of this spec is blocked until CA-E-04-002 (P0) and CA-E-04-003 (P3) are independently revalidated. Both remain UNVERIFIED campaign findings; the map explicitly requires the P0 gate before Wave 1.
- This session confirms source anchors and zero production callers for GroupBalanceCalculator at the pin; it does not promote either finding to VERIFIED. Revalidation must reproduce the requested-versus-persisted share counterexample and the settlement net-balance counterexample below.

## Context for the coder
- Groups keep departed members in history so old expenses and balances remain understandable. A new expense should offer only active members as payers and split recipients, and store exactly the split the user approved. Settlement-aware balance calculation should reduce a debt when its debtor repays its creditor.
- Root cause: historical-member lists are reused for new-entry validation, allowing invalid UNEQUAL payloads to reach an equal-split fallback; the dormant settlement calculator applies paid/received adjustments with reversed signs.
- Placement stays at SharedExpenseGroupsScreen/ViewModel, GroupsRepositoryImpl and GroupBalanceCalculator. The revised cluster map removed the CL-05 dependency; this spec changes neither currency conversion nor currency labeling.

## Pre-implementation checks (coder MUST run, in order)
1. `git diff 37601232..HEAD -- app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt app/src/test/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculatorTest.kt` — read every hunk; anchors below are from the pin and may have drifted; function names + signatures are authoritative, line numbers advisory.
2. Re-read each target function, supporting parser/coordinator and named tests at current HEAD before editing. Check uncommitted changes.
3. Recheck production usages: `git grep -n -E 'GroupBalanceCalculator|calculateMemberBalance' HEAD -- app/src/main`. At pin 37601232, matches outside GroupBalanceCalculator.kt are absent (a no-match search exits 1). Tests construct it in GroupBalanceCalculatorTest; generated outputs are not production callers.

## Legal path constraints
- LEGAL_PATHS.md §Group Mutations and §Shared Expense Management (Groups — Domain Facade) name the coordinator as the atomic writer. Actual live UI chain at pin: SharedExpenseGroupsViewModel.addExpense → domain/groups/usecase/AddGroupExpenseUseCase.invokeAtomic (≈L58-88) → GroupsRepositoryImpl.createSystemExpenseAndLinkToGroup → data/database/GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup.
- The data coordinator owns the transaction and calls TransactionLifecycleCoordinator.createExpenseDbOnlyV2 before GroupExpenseDao.insert, then dispatches its returned post-commit actions after commit (≈L711-915). Preserve that chain; do not migrate the UI to a different facade as part of this fix.
- Screen/ViewModel/repository validation must NOT insert/update group or expense DAOs directly. Read active members through the repository's existing injected memberDao. Reuse CustomSplitParser; do not duplicate money rounding/total rules.
- Keep historical member reads: GroupsRepositoryImpl.getActiveGroupsWithDetails(): List<GroupDetailsAggregate> (≈L46-66) uses GroupMemberDao.getAllForGroups; GroupBalanceCalculator uses getAllForGroup. Do not replace these with active-only queries.
- GroupBalanceCalculator remains read-only. Do not add settlement recording, DAO writers, Room schema changes, a new GroupLifecycleCoordinator or outer transactions that would change post-commit timing.

## Work items
### WI-1 — Separate new-entry membership and reject mismatched unequal splits (CA-E-04-002, P0, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt — SharedExpenseGroupsScreen's selected-group dialog call ≈L185-197; `private fun AddExpenseDialog(members: List<GroupMember>, onDismiss: () -> Unit, onAdd: (String, Double, Long, SplitType, Map<Long, Double>?) -> Unit)`, ≈L792-1057 @pin 37601232.
- Location: app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt — GroupWithDetails ≈L49; `fun addExpense(groupId: Long, description: String, amount: Double, paidById: Long, splitType: SplitType, customSplits: Map<Long, Double>? = null)`, ≈L218-280; `private suspend fun resolveGroupMembers(groupId: Long): List<GroupMember>`, ≈L349-361; `private fun serializeAndValidateCustomSplits(splitType: SplitType, customSplits: Map<Long, Double>?, totalAmount: Double, members: List<GroupMember>): CustomSplitPayload`, ≈L363-398.
- Location: app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt — `override suspend fun createSystemExpenseAndLinkToGroup(groupId: Long, description: String, amount: Double, paidById: Long, currency: String, splitType: SplitType, customSplitsJson: String?, date: Long, transactionType: TransactionType, notes: String?): GroupExpenseCreationResult`, ≈L152-177; companion entry `override suspend fun addExpenseWithLink(groupId: Long, systemExpenseId: Long, description: String, amount: Double, paidById: Long, splitType: SplitType, customSplitsJson: String?, date: Long): GroupExpenseCreationResult`, ≈L116-145.
- Defect: the dialog requires departed-member inputs and offers departed payers. The ViewModel validates those historical IDs, but the writer calculates shares using active members. Invalid UNEQUAL then reaches SplitCalculator.calculateUnequalSplit → fallbackToEqualForInvalidLegacyData, potentially persisting a different share.
- Supporting evidence: data/database/GroupTransactionCoordinator.kt loads getActiveMembersForGroup at ≈L738; validateCustomSplitPayloadFormat(splitType, customSplitsJson, memberCount) at ≈L1119-1149 count-checks only CUSTOM_AMOUNT/CUSTOM_PERCENT, not UNEQUAL. resolveCurrentUserShare at ≈L1034-1070 invokes SplitCalculator. domain/logic/SplitCalculator.kt ≈L177-228 applies equal fallback on an invalid UNEQUAL parse. These are read-only evidence, not permission to rewrite legacy read behavior.
- Change — dialog boundary: add a computed active-member projection to GroupWithDetails (new property, not a claimed existing API) using exactly `leftAt == null`, and pass that projection to AddExpenseDialog at ≈L187. All payer choices, defaults, split seeding, field validation and outgoing map keys must use this list. Keep GroupWithDetails.members, historical expense labels and calculateBalances inputs unchanged. Disable submit with no active members or an invalid payer. Key/reconcile remembered payer and input state by the active-ID set so refresh cannot leave stale keys; a membership change must invalidate/reset custom entry for user review, not silently reassign an entered amount.
- Change — ViewModel: resolve members freshly from the existing repository aggregate at submit, filtering only for this new-entry operation; do not return the cached selectedGroup/groups lists as authoritative submission membership. Reject payer IDs absent from that active set. Feed active IDs to the existing serializer/parser for every non-EQUAL type, including UNEQUAL. Invalid input stays on the existing error path and leaves the dialog open; do not call invokeAtomic, strip extra IDs, fill missing IDs with zero, or normalize invalid input into equal shares.
- Change — repository boundary: before delegating either createSystemExpenseAndLinkToGroup or addExpenseWithLink, retain the existing write-barrier check, then inside the existing IO context obtain memberDao.getActiveMembersForGroup(groupId). Reject inactive/foreign/missing payer. For non-EQUAL splits, require canonical JSON using CustomSplitJsonCodec.isCanonicalJsonPayload (existing coordinator requirement), then reuse CustomSplitParser.parseAndValidate with the existing toCustomSplitMode helper, amount and exact active-ID set. Invalid returns GroupExpenseCreationResult.Error with a fixed bounded message, e.g. "Invalid custom split for active members", before coordinator invocation. Do not expose parser reasons containing IDs/amounts. On Valid, forward the original JSON and split type unchanged. EQUAL keeps its null/custom-payload behavior and coordinator validation.
- Exact mismatch semantics: `parsed.keys == activeIds`; extra departed ID, same-count wrong ID, missing active ID, empty/null payload, malformed JSON, negative/non-finite/fractional-cent amount, or invalid total is rejection, never coercion. Parser source: domain/logic/CustomSplitParser.kt, `fun parseAndValidate(splitsString: String?, splitType: CustomSplitMode, totalAmount: Double, groupMemberIds: Set<Long>): CustomSplitParseResult`, ≈L30-150. Amount/UNEQUAL totals are exact cents; percentages total exactly 100.00 in basis points. Reuse those rules, not the dialog's floating tolerances.
- Acceptance criteria: Alice/Bob active and Carol departed produces two payer/split inputs. EUR 90 UNEQUAL {Alice:10, Bob:80, Carol:0} is rejected without a system expense/link; it must never become EUR 45/45. Valid {Alice:10, Bob:80} persists requested JSON and Alice's myShareAmount=10 when Alice is the current user. Historical Carol remains visible in older expense/balance data.
- Scope limit: repository validation is preflight against a fresh read, not a new transaction-wide membership lock. Preserve the coordinator's own transaction and validations; do not wrap it in a repository transaction and thereby move its post-commit actions inside an outer transaction. A demand for serializable concurrent membership changes requires separately reviewed scope.

### WI-2 — Correct repayment signs in the dormant balance API (CA-E-04-003, P3, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt — `suspend fun calculateMemberBalance(groupId: Long, memberId: Long): GroupMemberBalance`, ≈L35-61 @pin 37601232.
- Defect: creditor-positive base balance (paidTotal - owedShareTotal) is adjusted by subtracting payments made and adding receipts, doubling settled debts.
- Change: adjust only the net-balance formula to `paidTotal - owedShareTotal + settlementsPaid - settlementsReceived`. Preserve all-member historical participation, status allowlist RECORDED/COMPLETED, matching-currency filter, component totals, and isSettled's absolute epsilon <= 0.01.
- Direction evidence: domain/groups/SettlementCalculator.kt, calculateSettlements ≈L73-98, makes negative balances payers and positive balances recipients. Do not change that planner or wire new consumers.
- Worked example: A owes B EUR 50 (X). B paid EUR 100 for A/B; initial A=-50, B=+50. A repays B EUR 50 (from=A, to=B): A=-50+50=0; B=+50-50=0. Each balance moves TOWARD zero by X. For a partial EUR 20 repayment, A=-30 and B=+30. The old formula gives -100/+100 for full repayment.
- Acceptance criteria: both payer and recipient net balances reflect the example; zero-sum balance is conserved; cancelled/unknown-status/foreign-currency rows remain excluded. Do not clamp an overpayment to zero; preserve arithmetic and existing epsilon semantics.
- Caller disposition: pin-time production symbol search found no callers or injections outside this file. SharedExpenseGroupsViewModel.calculateBalances at ≈L139-143 calls SplitCalculator directly. Tests are the primary consumer; the dormant defect still requires correction, without claiming a live-screen settlement bug.

## Tests
- All paths below are under app/src/test/java/com/yourname/expensetracker/. Existing classes/fixtures were inspected; no tests were run in this spec session.
- WI-1: ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt — exercise the production active-member projection consumed by the dialog: mixed active/departed list; all-departed/empty; historical members unchanged. Reviewer must inspect actual dialog binding, keyed state and no-active-member submit gating; a test-only filter expression is insufficient.
- WI-1: ui/screens/groups/SharedExpenseGroupsViewModelTest.kt:
  1. Active Alice/Bob + departed Carol remains in history but only Alice/Bob validates for submission.
  2. Valid active CUSTOM_AMOUNT, CUSTOM_PERCENT and UNEQUAL forwards canonical requested values unchanged.
  3. Extra Carol, missing Bob, and same-count wrong ID each reject with no invokeAtomic.
  4. Departed/foreign/missing payer and empty active set reject.
  5. Stale selectedGroup with a newly departed member is rejected against refreshed repository membership; read failure does not fall back to stale members.
  6. Null/empty custom map, negative/NaN/infinite values, fractional-cent amounts, wrong totals and percent totals other than 100.00 reject.
  7. EQUAL regression and valid zero share for one active member; invalid input retains dialog/error, success closes/refreshes.
- WI-1: data/repository/GroupsRepositoryImplTest.kt:
  1. For BOTH entry points, repeat extra/missing/same-count wrong IDs for UNEQUAL and custom types; require Error and zero coordinator calls.
  2. Malformed/noncanonical/null JSON, invalid money values/totals, empty active set, invalid payer: bounded error, zero coordinator calls.
  3. Valid unequal 10/80, custom amount and percent, and EQUAL preserve arguments and result propagation.
  4. getActiveGroupsWithDetails still returns departed members for history; barrier failure still prevents coordinator delegation.
- WI-1: data/database/GroupTransactionCoordinatorTest.kt — extend its existing Room fixture to enter through a real GroupsRepositoryImpl for this regression (retain coordinator implementation):
  1. Alice current user, Bob active, Carol leftAt set before expense date: EUR 90 unequal 10/80/0 fails before either system or group row appears.
  2. Correct active 10/80 succeeds; query stored system myShareAmount=10 and group JSON/type unchanged.
  3. Valid active custom percentage/amount still links atomically; rejected addExpenseWithLink does not alter an existing expense's ownership/link. Keep test dates after active members' joinedAt.
- WI-2: domain/groups/GroupBalanceCalculatorTest.kt:
  1. Baseline A=-50/B=+50, full A-to-B 50 => both zero/isSettled.
  2. Partial 20 => -30/+30; two partial repayments totaling 50 => zero.
  3. RECORDED and COMPLETED included; CANCELLED and another unknown status excluded; foreign currency excluded; assert both net balances, not only paid/received fields.
  4. Retain joinedAt historical tests and add departed-member historical expense coverage.
  5. Zero repayment/no settlements leaves baseline; overpayment is not silently clamped; isSettled includes +/-0.01 and excludes +/-0.02.

## Validation
- After the revalidation GATE and architecture-guardian/reviewer-strict pass, validation-runner alone uses scripts/validation-runner.ps1 `-Action Start -Profile targeted-unit-test -TestFilter '<class filter>'` serially for:
  - *SharedExpenseGroupsScreenStateTest
  - *SharedExpenseGroupsViewModelTest
  - *GroupsRepositoryImplTest
  - *GroupTransactionCoordinatorTest
  - *GroupBalanceCalculatorTest
- Then run profile compile through the same owner/wrapper. Coder NEVER invokes Gradle directly; obtain expensive-run authorization if required by AGENTS.md.
- Check active validation first; poll RUNNING by RunId rather than rerun. Report profile/filter, exit code, durable result.json/log paths; missing/stale/timeout/infra-error results are not PASS.

## Blast-radius fence
- ALLOWED production (prefix app/src/main/java/com/yourname/expensetracker/): ui/screens/groups/SharedExpenseGroupsScreen.kt; ui/screens/groups/SharedExpenseGroupsViewModel.kt; data/repository/GroupsRepositoryImpl.kt; domain/groups/GroupBalanceCalculator.kt.
- ALLOWED tests: the five exact relative test paths listed above.
- FORBIDDEN: data/database/GroupTransactionCoordinator.kt and domain coordinator interface (read-only lifecycle evidence); GroupMemberDao.kt/history queries; SplitCalculator.kt/CustomSplitParser.kt/CustomSplitJsonCodec.kt shared math and legacy fallback; SettlementCalculator.kt/writer APIs; SharedExpenseManager.kt and SharedExpenseDataPortAdapter.kt. These adjacent group surfaces are not assigned another CL for this finding; expansion needs a revised reviewed scope.
- FORBIDDEN cluster-owned neighbors: SharedExpenseBudgetOffsetEngine.kt financial logging (CL-17); TaxEstimator.kt/BusinessExpenseRepository.kt currency aggregates (CL-05); lifecycle single-writer refactors (CL-08). No schema, currency, log-cleanup or member-removal refactor.

## Review gate
- architecture-guardian plus reviewer-strict: verify history stays all-member; new inputs and repository validation use active IDs; UNEQUAL is rejected, never repaired/equalized; bounded errors and unchanged JSON/amounts; no direct mutations outside coordinator; no outer transaction changes post-commit ordering.
- Verify the sign example for both members, unchanged status/currency filters/epsilon, zero-caller disclosure, enumerated regression coverage, and continued CL-29 independent-revalidation block until evidence is accepted.

## Out of scope
- CA-E-04-001 payload logging: CL-17; CA-E-04-004 tax currency aggregation: CL-05.
- Existing historical participation/member-removal debt is preserved, not relabeled as a new finding.
- No settlement UI/writer activation, legacy invalid-row repair, currency migration, or broad concurrency redesign.

