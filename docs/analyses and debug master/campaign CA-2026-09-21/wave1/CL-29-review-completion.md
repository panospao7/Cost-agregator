# CL-29 review-completion — rp-24 / Wave 1

Date: 2026-09-25 (+03:00). First reviewer: astra high.
Reviewed implementation HEAD: `da0173f0c354ee3a63ee4fd7030d2a1b15f50347` (`rp-24-wip`).
Required base: `ad412aecb1347daebee7fef62aca745cf205f0aa`.
Spec: `CL-29-group-split-settlement-correctness.md` in this directory, read in full.

**VERDICT: FAIL — completion evidence incomplete; 0/2 DONE-CORRECT. WI-1 PARTIAL; WI-2 PARTIAL. Confirmed diff-introduced regressions: 0.** The bounded new-entry validation and dormant repayment-sign corrections match the spec in source. Required boundary coverage/execution remains incomplete. The broader historical display/persist inconsistency is **not universally resolved**; distinguish it from the corrected new-entry counterexample and from a new regression.

## 1. Environment, gate and inventory

- After committing the CL-01 artifact, changed to `C:/Users/panos/Desktop/cost agregator/ExpenseTracker/build/worktrees/rp-24`. Verified branch/HEAD above, clean status, and `ead80016` ancestor check exit 0. Rechecked clean status before creating this artifact.
- The spec's September 22 post-spec update explicitly **lifts** its earlier independent-revalidation gate. Read `../verification-2026-09-22-wave1.md:84-103`, including the requested/persisted-share, member-join and settlement counterexamples. Do not revive the superseded UNVERIFIED block. Lifting that gate does not imply implementation tests or this review passed.
- Read scoped `CODEBASE_SEGMENTS.md` / `CODEBASE_INVENTORY.md` group ownership, `LEGAL_PATHS.md:628-709,1213-1234`, and `ENGINE_INTERACTION_MAP.md` group/shared-expense impact. Read every lane-specific diff hunk and surrounding UI/repository/parser/calculator/coordinator/test code. No active handoff files were present in `workflows/active`.
- Required base-to-HEAD inventory: **21 files, 1,711 insertions, 47 deletions**. CL-29 commits are `37be9d9c`, `64cd2302`, `da0173f0`; parent `edc4275f`. This lane-specific delta is **9 files, 772 insertions, 26 deletions**, exactly four allowed production files and five named tests. The other 12 paths are the same inherited rp-25/CL-18 changes read in the first lane's full diff, with the same common ancestor/content; they are not CL-29-authored fence breaches. Exact log/stat/numstat appear in the appendix.
- **No subagents or validation were run.** No production/test edits, runner actions, builds, lint or guards. Only this artifact is authored/committed; JOURNAL and all other campaign files remain untouched by the reviewer. Existing logs are evidence read, not reviewer execution.

### Citation key — current implementation HEAD unless stated otherwise

- **Screen**: `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt`
- **VM**: `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt`
- **Repo**: `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt`
- **Balance**: `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt`
- **Split**: `app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt`
- **Parser / Codec**: `app/src/main/java/com/yourname/expensetracker/domain/logic/CustomSplitParser.kt` / `CustomSplitJsonCodec.kt`
- **Coordinator**: `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- **UseCase**: `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`
- **ScreenTest / VMTest**: `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt` / `SharedExpenseGroupsViewModelTest.kt`
- **RepoTest**: `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt`
- **RoomTest**: `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt`
- **BalanceTest**: `app/src/test/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculatorTest.kt`
- **V**: `build/validation-runs/` in rp-24, not the parent checkout.

## 2. Per-spec-work-item verdicts

DONE-CORRECT requires the implementation, required boundary coverage and completion gates; compiled or statically plausible code alone is insufficient. Neither item is NOT-STARTED; no concrete new production defect in the bounded changes was established.

| Work item | Verdict | Evidence at HEAD / remaining completion |
| --- | --- | --- |
| WI-1 — active new-entry membership and reject unequal mismatch | **PARTIAL** | `37be9d9c` plus `da0173f0`: VM's activeMembers projection uses exactly leftAt == null; Screen:187 consumes it, 799-807 reconciles payer and resets custom inputs on active-ID change, 1052-1053 disables invalid/empty payer submission. VM:239-255,363-406 resolves fresh repository membership, validates payer and exact active IDs, retains invalid-input dialog and bounded errors. Repo:134-163,183-205,319-350 checks the barrier, reads active members inside IO, validates canonical JSON with the shared parser for every non-EQUAL type, and forwards original JSON/type unchanged. Historical aggregate reads remain all-member at 51-73. The specified 10/80/0 rejection and 10/80 persisted-share tests are authored, but no class executed; both-endpoint boundary matrices and member-join/display agreement proofs remain incomplete. |
| WI-2 — repayment signs in dormant balance API | **PARTIAL** | `64cd2302`: Balance:59 changes only net balance to paid - owed + settlementsPaid - settlementsReceived. History/status/currency/component/epsilon behavior is unchanged. BalanceTest:145-262 adds five methods covering both-member partial/full repayment, multiple payments, exclusions, overpayment, epsilon and departed history. Exact source trace moves balances toward zero. No named execution or final source-bound compile/review acceptance; strengthen the zero-row and recipient-overpayment checks without changing the correct formula. |

### Test inventory and every enumerated boundary

Current source declares **4 ScreenTest, 14 VMTest, 10 RepoTest, 36 RoomTest, 10 BalanceTest** test methods. These are source counts, not executed/passing counts.

| Spec boundary | Actual evidence | Assessment |
| --- | --- | --- |
| Screen: mixed active/departed; all departed/empty; history retained | ScreenTest:12-42 | Tests production projection, not a copied filter. Actual binding/payer/seed/submit logic inspected at Screen:187,799-807,885-959,977-1053. No runtime recomposition/reset test. |
| VM.1 active Alice/Bob with historical Carol | VMTest:412-435 | Keeps history and rejects departed payer with dialog open. |
| VM.2 canonical CUSTOM_AMOUNT / CUSTOM_PERCENT / UNEQUAL | VMTest:438-475 | All three exact canonical maps and types verified at invokeAtomic. |
| VM.3 extra/missing/same-count wrong IDs | VMTest:478-498 | Three UNEQUAL shapes rejected with no invocation. Shared parser used for all non-EQUAL types. |
| VM.4 invalid payer and empty active set | VMTest:501-521 | Departed, foreign, missing/zero ID and empty list covered. |
| VM.5 stale selectedGroup / fresh-read failure | VMTest:524-542 | Newly departed payer and read failure do not call atomic use case. No explicit newly joined-member case or error/dialog assertion for each branch. |
| VM.6 null/empty/negative/NaN/infinity/fractional-cent/wrong total/percent | VMTest:545-572 | All listed input families represented; invalid percentages include wrong total and fractional basis points. |
| VM.7 EQUAL, zero share, invalid stays open, success closes/refreshes | VMTest:575-619 and 91-154 | Exact EQUAL null JSON and UNEQUAL zero-share delegation; original success test strengthened with dialog-close assertion. None executed. |
| Repository.1 both endpoints, three mismatch shapes, all non-EQUAL modes | RepoTest:319-365 | Cross-product loop covers both endpoints and UNEQUAL/CUSTOM_AMOUNT/CUSTOM_PERCENT, exact bounded error, zero delegates. |
| Repository.2 malformed/noncanonical/null, invalid money, empty set, payer | RepoTest:367-415 | Families present but split across endpoints: malformed/custom payload cases only create; invalid amount and percent-total cases only link; invalid payer only link; empty active only create. Complete the mirrored cases, literal non-finite payloads, and absent/departed payer controls. |
| Repository.3 valid unequal, amount, percent, EQUAL arguments/results | RepoTest:418-463,213-266 | Unequal/percent create, custom amount/EQUAL link; exact forwarding asserted for unequal create and amount link. Not every mode through both endpoints, nor exact percent/EQUAL payload/result-error propagation assertions. |
| Repository.4 historical members and write barrier | RepoTest:466-491 | History includes Carol. Barrier proof checks create only; link barrier/no-read/no-delegate case missing. |
| Room.1 EUR90 10/80/0 with Carol departed before date | RoomTest:928-956 | Enters real repository and Room coordinator; exact bounded error, both tables empty. |
| Room.2 EUR90 10/80 persists Alice share10 and unchanged JSON/type | RoomTest:958-991 | Explicit system/group queries and history member retained; dates after active joinedAt. No displayed-share assertion. |
| Room.3 valid amount/percent; rejected link does not alter ownership | RoomTest:993-1030 | Real create/link Success checks; rejected existing link has exact before/after expense and no link. Add exact stored amount/percent/share/link assertions, rather than only Success for positives. Existing rollback/post-commit fixture tests remain. |
| Balance.1 baseline/full50 => zero/isSettled; Balance.2 partial20/multiple50 | BalanceTest:145-195,219-229 | Both nets -30/+30 and 0/0, full settles, two RECORDED payments sum to zero. Baseline -50/+50 separately asserted. |
| Balance.3 status and currency | BalanceTest:158-175,198-215 | RECORDED and COMPLETED included; CANCELLED, UNKNOWN and USD excluded with both net balances. Component-only older assertions are not the sole proof. |
| Balance.4 joined/departed history | BalanceTest:33-87,251-262 | Existing joinedAt tests retained; departed-before-now historical expense share added. Existing joined test covers EQUAL, not the reviewer's UNEQUAL join scenario. |
| Balance.5 none/zero, overpayment, +/-0.01 vs +/-0.02 | BalanceTest:218-248 | Empty settlements leaves both baseline nets; overpayment checks Alice +1/unsettled only; epsilon covers both signs using both members. Add literal zero-valued row and Bob -1/zero-sum overpayment assertion. |

## 3. Adversarial counterexamples and disposition

These are **source traces, not executed reproductions**. Preconditions matter; do not repeat a broader historical claim without checking joinedAt/leftAt at the expense date.

### A. Requested versus persisted unequal share — bounded new-entry fix present

Alice (current user) and Bob active; Carol left before the expense date; all active joinedAt values precede that date; total EUR90.

| Request | Before lane | At reviewed HEAD |
| --- | --- | --- |
| UNEQUAL {Alice:10, Bob:80, Carol:0} | Historical UI validation could admit it; active-only coordinator parser rejects extra Carol, legacy fallback computes 45/45 while JSON remains verbatim. | VM active-ID parser rejects; direct callers are rejected by Repo:334-346 before either coordinator entry. RoomTest:928-956 asserts no system/group rows. No new 45/45 persistence on this path. |
| UNEQUAL {Alice:10, Bob:80} | Direct repository callers could already supply it, but historical UI membership blocked/misled new entry. | Exact active keys and 90.00 total validate; original JSON/type forwarded. Coordinator:738,791-802,1034-1070 computes Alice10 and CreateExpenseRequest:839 carries that value. RoomTest:958-991 queries myShareAmount10 and identical JSON/type. With Carol left before date, Split:66-68 excludes her on display too, so new requested/persisted/displayed share is10 by inspection. |

Preflight is not a transaction-wide membership lock. Membership changing after Repo's read but before the coordinator read remains the explicitly excluded concurrency window; no outer transaction was introduced to alter post-commit timing.

### B. Member-join trigger — distinguish submission from historical display

1. **Join while a new-entry form is stale:** cached Alice/Bob map10/80; Carol becomes active before submission. VM:363-368 rereads membership, Parser:122-126 rejects missing Carol. Direct Repo callers also reread current active IDs. On a UI refresh, Screen:805-807 resets fields to blank for the new ID set, requiring review instead of silently assigning a new share. This handles the in-scope stale-submit trigger in source; no dedicated join test was added.
2. **Ordinary join after an already-recorded expense:** expense at t=2000, Carol joinedAt=5000. Split:39,54-69 filters historical participants for **all** modes, including UNEQUAL; Carol is excluded and valid old Alice/Bob JSON remains valid. A later join alone does not reproduce the verifier's broad current-set claim at this HEAD. Split has no changes from `37601232` through HEAD; this date filter is pre-existing, not a CL-29 achievement. Add an UNEQUAL history regression to prove this boundary rather than citing the retained EQUAL-only test.
3. **Join included at the expense date / stale legacy payload:** if Carol's joinedAt <= expense.date but stored JSON omits Carol (for example a legacy row or a future-dated expense followed by a join before its recorded date), Parser:122-126 still rejects the key set and Split:186-228 still falls back equally. Already-persisted system myShareAmount is not recomputed by this read. CL-29 has no legacy repair and does not universally eliminate this wider variant. Concurrent changes after preflight are likewise not covered by a new lock.

### C. Display/persist inconsistency — **remains open outside the bounded new-entry case**

A concrete retained example, requiring neither a new schema nor a race: all three joinedAt=1000; expense date=2000; Carol.leftAt=3000; operation occurs after3000. Alice/Bob are currently active. Repository request UNEQUAL {Alice:10, Bob:80}, EUR90, passes the new active-ID preflight. Coordinator:738 and 1034-1070 calculates with current active Alice/Bob and persists Alice10. The all-member display path VM:94-111,137-145 receives historical Carol; Split:66-68 includes her because she was present at date2000. The two-key payload fails Parser:122-126 and falls back to **30/30/30**, so displayed Alice share30 differs from persisted10 (if Alice paid, displayed net+60 instead of+80). This can affect backdated repository create/link operations and historical data; ordinary new-entry date-after-departure is the fixed case A.

This input and both calculation paths already exist at the base; the lane preserves the forbidden shared-math/coordinator/history surfaces. It is a **retained P1/MAJOR correctness risk**, not a newly introduced regression and not permission to change those surfaces. The original reviewer's particular displayed10/persisted45 shape changes with temporal participation; do not claim every shape was fixed or that all later joins fail. Closing the broader finding needs a separately reviewed temporal-participation/legacy-data scope. No such repair or scope approval is claimed here.

### D. Repayment moves toward zero; dormant API only

Bob paid EUR100 for Alice/Bob: Alice paid0-owed50=-50; Bob paid100-owed50=+50. At Balance:59, Alice-to-Bob20 adds20 to Alice and subtracts20 from Bob: **-30/+30**. A full50 or20+30 yields **0/0**, both settled. Overpay51 yields **+1/-1**, not clamped. Sum is conserved. This agrees with `domain/groups/SettlementCalculator.kt:73-82` (negative debtors pay positive creditors). Status allowlist RECORDED/COMPLETED, matching currency and abs(net)<=0.01 are unchanged.

Ran only the requested read-only symbol search `git grep -n -E 'GroupBalanceCalculator|calculateMemberBalance' HEAD -- app/src/main`: exactly the class and method declarations in Balance, **zero external production callers/injections**. The live VM still uses Split.calculateBalances and does not consume settlement rows. The sign fix is not a live settlement-screen fix; no writer/UI activation was added.

## 4. Findings and regression checklist

- **G-1 [P2 / MAJOR completion gap]:** incomplete mirrored repository boundary tests, missing explicit fresh-join and historical UNEQUAL/display agreement tests, and partial positive persistence assertions. See section2 and WI-3. Projection tests alone are not recomposition execution evidence; actual keyed-state source was inspected.
- **G-2 [P2 / MAJOR completion gap]:** no execution of any of the five named classes. Current merged prerequisites remove the old blanket compilation excuse; see section5 and WI-5.
- **O-1 [P1 / MAJOR retained/out-of-scope]:** temporal historical display/persist discrepancy in section3C and legacy join variant in3B.3. No new regression counted. Record the distinction and require revised scope before modifying forbidden math/writer/history behavior; WI-6 is a scope-gated follow-up, not an unauthorized implementation batch.
- **Confirmed diff-introduced regressions: 0.** No source-proven new production or test defect was established in the nine-file lane delta. This is not a PASS guarantee for unexecuted tests.
- No CL-29-authored outside-fence paths. Coordinator/interface, DAOs/history queries, shared parser/codec/math, settlement writer/planner, SharedExpenseManager/adapter, schemas and guards remain unchanged. No outer transaction or changed post-commit dispatch order.
- New repository/UI validation errors are fixed bounded strings; parser reasons containing IDs/amounts and exception messages are no longer exposed from the changed VM path. Retained name-bearing expense notes are the existing authorized expense payload, not new diagnostic logging. No new financial-payload/exception logging site.
- New catches preserve cancellation in VM addExpense and both newly expanded homeCurrency catches; parser catches only ArithmeticException. Ordinary fallback currency behavior is unchanged. Existing broad catches elsewhere are not attributed to this delta or swept into its fence.
- No test deleted/disabled/excluded or guard/baseline weakened. VMTest:360 replaces the prior raw-exception-text expectation with exact bounded error, matching the intentional privacy fix rather than hiding a failure. Added active-member stubbing keeps the old link-success fixture on its intended legal path. Early uncommitted BalanceTest constructor errors in old logs are absent from current named-argument constructors, not current regressions.

## 5. Existing validation evidence — read, not run

Six local records, all with completion markers, all revision `ad412aec` plus dirty-worktree fingerprints. None certifies the current clean/rebased HEAD. No JUnit XML was found in this worktree's test-results directory.

| Run ID under V | Recorded profile/filter | Status / exit | Actual evidence |
| --- | --- | --- | --- |
| vr-20260923-192338-8923efc8 | targeted-unit-test / *SharedExpenseGroupsScreenStateTest | FAIL / 1 | SDK location missing; no tests. |
| vr-20260923-192624-71c9ed95 | same screen filter | FAIL / 1 | Test compilation failed: five unrelated stale test contracts plus then-uncommitted BalanceTest positional settlement-constructor errors. |
| vr-20260923-194357-2d567474 | same screen filter | FAIL / 1 | Test compilation failed on five unrelated files; BalanceTest errors already absent. |
| vr-20260923-194903-eaa081ae | compile | PASS / 0 | Production compile UP-TO-DATE; not test execution. |
| vr-20260923-195607-b7b581a8 | same screen filter | FAIL / 1 | Latest named attempt stops at compileDebugUnitTestKotlin on the five unrelated files. |
| vr-20260923-200609-864efada | compile | PASS / 0 | Production compile UP-TO-DATE; no named execution. |

Each row's exact command, revision/fingerprint and status is in `V/<run-id>/result.json`; diagnostic evidence is in sibling `stdout.log`, `stderr.log`, `complete.marker`. **ScreenTest never reached execution; no local run even targets VMTest, RepoTest, RoomTest or BalanceTest.** The five external compilation repairs are already inherited in `ead80016` (Keystore nullability, DAO fake, receipt constructor, backup success fixture, export serializer). Do not prescribe their repair again, exclude sources or promote either compile PASS to test evidence. The old source fingerprints cannot substitute for reviewed-HEAD execution.

## 6. DIRECT-FIX completion plan

Continue original WI numbering, bounded batches only. Correct-looking production does not need a cosmetic rewrite. All commands below are for the later validation owner, **not executed by this reviewer**.

### WI-3 — Complete WI-1 boundary matrix and join/persist/display proofs (G-1, P2)

- **Location:** ScreenTest, VMTest:412-619, RepoTest:319-491, RoomTest:928-1030; read-only production anchors above.
- **Change:** mirror malformed/null/noncanonical/non-finite/fractional/negative/wrong-total/empty-member/invalid-payer rejection through both repository endpoints with exact bounded errors and zero delegation. Include barrier rejection for both endpoints and preserve original EQUAL behavior. Verify all valid custom modes plus EQUAL preserve original JSON/type/amount/result through each endpoint; do not rewrite invalid maps. Add a fresh-member-join submission case with a still-valid payer and now-missing active split key, including the stale selectedGroup and dialog/error behavior. Preserve the production active-ID projection and inspect/reproduce keyed input reset on join/leave without silently reseeding approved amounts; no dependency or broad UI refactor is authorized.
- **Persistence tests:** retain the two exact EUR90 counterexamples; query real linked system/group rows and myShareAmount for valid amount and percentage cases, and query all-member displayed split for the ordinary date-after-Carol-left valid10/80 case. Keep no-orphan and rejected-existing-link before/after assertions. Add an UNEQUAL historical expense followed by a member joining after expense.date and prove unchanged10/80 and zero share for the new member, using current production calculation. Do not replace this with a test-only filter.
- **Acceptance criteria:** each spec boundary has explicit observation through its named entry point, positive requested/stored/displayed values agree for the bounded new-entry case, every rejected payload yields no write/delegate, and membership change before submission is rejected/reset for user review. No claim that the excluded post-preflight race is serialized.
- **Tests / validation:** the first four named classes, serial targeted profiles in WI-5. Keep actual Room/coordinator lifecycle chain and existing rollback/post-commit tests.
- **Fence:** the four named WI-1 tests; only a demonstrated in-fence local production repair if a new test exposes one. No coordinator/parser/codec/SplitCalculator/DAO/history/schema/guard edits. Stop for revised scope if temporal historical behavior requires them; see WI-6.

### WI-4 — Finish repayment boundary assertions without changing correct signs (G-1, P2)

- **Location:** BalanceTest:145-262; Balance:28,35-65 read-only.
- **Change:** retain both-member full/partial/multiple-payment and status/currency tests. Add an explicit zero-valued allowed-status settlement to the empty-settlement control. Assert Bob's -1 as well as Alice's +1 for overpayment51, their zero sum and unsettled status. Retain +/-0.01 included and +/-0.02 excluded, joinedAt and departed history.
- **Acceptance criteria:** tests observe actual net balances of both members, not just component paid/received fields; no clamping or tolerance widening; no settlement UI/writer activation or new production consumer.
- **Tests / validation:** GroupBalanceCalculatorTest, exact named run in WI-5. This improves complete evidence, not a request to rewrite the single-line formula.
- **Fence:** BalanceTest only unless an executed failure proves a bounded Balance defect. No shared math/currency/planner changes.

### WI-5 — Execute all five named classes and final compile (G-2, P2)

- **Location:** lane runner records and original Validation/Review gate.
- **Change:** obtain passing architecture/strict review on completed in-fence work; this first review is FAIL and no separate guardian pass is claimed. The independent-revalidation gate was already lifted. Have the sole validation-runner owner check active runs/global lock and SDK access, then run the commands below serially. Obtain expensive-run authorization where required. Do not run Gradle directly, hide baseline failures or reuse a compile-only PASS.
- **Acceptance criteria:** five actual named classes execute with nonzero case counts covering updated tests, zero relevant failures/skips and durable terminal PASS; then compile PASS. Record exact revision/fingerprint, command/filter, exit, result/log/marker paths and case counts. Poll RUNNING rather than rerunning/overlapping. Missing, stale, timeout, infra-error or unexecuted results never count as PASS. Re-review runtime-driven changes. No broad unit suite/static guard profile added to the spec.

```powershell
Set-Location -LiteralPath 'C:/Users/panos/Desktop/cost agregator/ExpenseTracker/build/worktrees/rp-24'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action List
# Start each next profile only after the previous run is terminal PASS.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*SharedExpenseGroupsScreenStateTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*SharedExpenseGroupsViewModelTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*GroupsRepositoryImplTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*GroupTransactionCoordinatorTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*GroupBalanceCalculatorTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
# For RUNNING, substitute the actual ID and repeat Wait until terminal:
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId <returned-run-id>
```

- **Fence:** validation evidence only; no unrelated source repairs, guard/baseline changes or JOURNAL/campaign-status edits.

### WI-6 — Scope-gated disposition of retained historical inconsistency (O-1, P1)

- **Location:** section3B.3/3C; VM:94-145, Repo:319-350, Coordinator:1034-1102, Split:54-69,177-228. Coordinator/shared math/history remain forbidden by the current spec.
- **Defect:** current-active write participants can differ from date-effective historical display participants; old stored myShareAmount is not repaired when a read falls back equally. Later joins alone with joinedAt after the expense do not demonstrate this defect.
- **Next action:** present the exact temporal counterexample to the architecture owner for an explicit revised scope/contract decision before any source repair. The decision must identify the authoritative participant set for backdated links/creates and distinguish legacy-row repair from new-entry rejection. Do not silently switch history to active-only, drop JSON keys, equalize approved shares or wrap the coordinator in an outer transaction. No unapproved shared math or data migration is authorized by this artifact.
- **Acceptance criteria for that separately approved follow-up:** requested, persisted and displayed shares agree for a dated expense spanning departure/join history; invalid legacy data is handled under an explicit preservation policy rather than assumed repaired. Add deterministic real-row and read-path tests before claiming the broader CA-E-04-002 finding closed.
- **Validation / fence:** proposed focused RoomTest/VMTest coverage and the existing named runner profiles, only after the revised scope and strict/architecture review. Original bounded WI-1 can be assessed on its own tests/gates, but must not be advertised as universal historical repair. Record this open risk rather than expanding CL-29 silently.

## 7. Delivery

Only `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-29-review-completion.md` is added. Delivery commit SHA and journal line are returned separately after verification. Reviewed implementation remains pinned above. Reviewer validation: **NOT RUN (prohibited)**. Remaining risks are incomplete runtime evidence/boundary proof and the explicitly retained temporal history discrepancy, not invented new regression counts.

## Appendix — exact required Git inventory

### git log --oneline ad412aec..HEAD
```text
da0173f0 fixup! CL-29 WI-1: validate new group splits against active members
64cd2302 CL-29 WI-2: correct settlement repayment balance signs
37be9d9c CL-29 WI-1: validate new group splits against active members
edc4275f test(backup): remove unrelated privacy fixture formatting delta
36d499db test(backup): preserve cancellation sentinel identity across coroutine recovery
8383fdcf test(backup): complete CL-18 export boundary coverage
984596ed fix(backup): preserve preflight failure result without maintenance cleanup
20e42214 docs: record CL-18 completion rereview
577c9e56 fix: complete CL-18 review remediation
86e7f244 docs: add CL-18 completion review
b10af5f6 fix: propagate privacy gate cancellation
83918e0f fix: fail closed backup exports
ead80016 merge: rp-25 test-recovery — unit-test compilation repairs + behavioral triage
00ca7e1e test: record trusted-tests + Phase B baseline triage (CANCEL-01 production violations, OOM-bounded baseline 5230P/214F)
9e8353bf test: repair unit-test compilation at HEAD (5 files, assertions unchanged) + triage behavioral failures
```

### git diff --stat ad412aec..HEAD
```text
 .../repository/DatabaseBackupRepositoryImpl.kt     |  40 ++-
 .../data/repository/GroupsRepositoryImpl.kt        |  64 +++-
 .../domain/groups/GroupBalanceCalculator.kt        |   2 +-
 .../domain/privacy/CompositePrivacyGate.kt         |   3 +
 .../ui/screens/groups/SharedExpenseGroupsScreen.kt |   9 +-
 .../screens/groups/SharedExpenseGroupsViewModel.kt |  38 ++-
 .../database/GroupTransactionCoordinatorTest.kt    | 125 +++++++-
 .../KeystoreInstallationSecretHashingTest.kt       |   7 +-
 .../repository/DatabaseBackupRepositoryImplTest.kt | 327 ++++++++++++++++++++-
 .../data/repository/GroupsRepositoryImplTest.kt    | 182 +++++++++++-
 .../LegacyDataConsistencyCheckerTest.kt            |   6 +
 .../domain/groups/GroupBalanceCalculatorTest.kt    | 119 ++++++++
 .../domain/privacy/CompositePrivacyGateTest.kt     |  98 ++++++
 .../lifecycle/ReceiptLifecycleCoordinatorTest.kt   |   1 -
 .../BackupRestoreViewModelPrivacyDenialTest.kt     |  10 +-
 .../ExportOptionsViewModelPrivacyDenialTest.kt     |   1 +
 .../groups/SharedExpenseGroupsScreenStateTest.kt   |  34 +++
 .../groups/SharedExpenseGroupsViewModelTest.kt     | 225 +++++++++++++-
 .../wave1/CL-18-rereview-completion.md             | 178 +++++++++++
 .../wave1/CL-18-review-completion.md               | 221 ++++++++++++++
 .../generated/TRIAGE-2026-09-test-recovery.md      |  68 +++++
 21 files changed, 1711 insertions(+), 47 deletions(-)
```

### git diff --numstat ad412aec..HEAD
```text
27	13	app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
63	1	app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt
1	1	app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt
3	0	app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt
7	2	app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt
23	15	app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt
124	1	app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt
4	3	app/src/test/java/com/yourname/expensetracker/data/privacy/KeystoreInstallationSecretHashingTest.kt
324	3	app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt
181	1	app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt
6	0	app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt
119	0	app/src/test/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculatorTest.kt
98	0	app/src/test/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGateTest.kt
0	1	app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt
9	1	app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt
1	0	app/src/test/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModelPrivacyDenialTest.kt
34	0	app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt
220	5	app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt
178	0	docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-rereview-completion.md
221	0	docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-review-completion.md
68	0	docs/testing/generated/TRIAGE-2026-09-test-recovery.md
```
