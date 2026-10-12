# Wave 5 savings Batch 4 (R14) - 2026-10-11

Status: PARTIAL. Batch 4 implementation, local self-review and bounded runner
validation have passed. Independent acceptance remains NOT RUN.
Continue from this packet and the approved remaining plan, not prior chat.

## Scope and ownership

- Approved plan: workflows/active/money-w5-remaining-implementation-plan-20261009.md,
  Batch 4 only. Prior packet: money-w5-savings-b3-20261011-handoff.md.
- User's no-further-subagents amendment remains in force. All roles are local;
  local self-review is not independent reviewer/guardian acceptance.
- Branch bug-fixes, HEAD a1c66173219d7d959b0d36859a68176aaf814242.
- Pre-gate tree: 150 tracked changes and 50 untracked paths before this packet.
  This mixed tree includes older Wave 5 and unrelated work. Preserve those hunks.
- Read scoped architecture segment/inventory ownership, Money/Currency legal path,
  engine impact matrix, savings model/repository/producers/consumers and tests.
- No staging, commit, schema/migration, runner, guard, allowlist, or baseline edits.
  R13 cancellation baseline ratchet remains unauthorized.

## Files touched in this slice

- app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGoalCurrencyPolicy.kt (new)
- app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt
- app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt
- app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt
- app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt
- app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt
- app/src/main/res/values/strings.xml (one goal-currency unavailable string)
- app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGoalCurrencyPolicyTest.kt (new)
- app/src/test/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngineTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngineTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt
- app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt
- app/src/test/java/com/yourname/expensetracker/data/repository/SavingsGoalRepositoryCurrencyTest.kt
- app/src/test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt (explicit savings fixture only)
- This packet and money-w5-candidate-paths-20261008.md.

## Behavior changed

- Shared savings policy accepts supported currencies only with explicit provenance:
  EXPLICIT, EXPLICIT_HOME_CURRENCY, USER_CONFIRMED or PARSED_FROM_SOURCE.
  LEGACY_DEFAULT, unknown/unrecognized/blank and assumed currencies remain unknown,
  even if their stored label equals home currency. No persistence defaults changed.
- Unknown active unfinished goals make smart/sweep computation Unavailable with
  controlled INVALID_SOURCE_CURRENCY before zero-budget/underspend shortcuts.
  Mixed explicit+unknown portfolios are not silently allocated as partial portfolios.
  Sweep outside its recommendation window remains an intentional no-computation path.
- Explicit foreign goals remain unsupported allocation inputs, not relabeled or
  converted. Empty/finished-only portfolios can still have no recommendation;
  completed unknown balances remain unavailable for portfolio valuation/achievements.
- Gamification uses the same policy for portfolio balances and completion history
  attribution. Unknown zero balances cannot create a known zero total or level.
  Count-based goal creation and event-day streaks are not denomination-dependent.
  Known goal balances can still unlock money thresholds even when historical events
  cannot establish an unlock date; the existing no-fabricated-date contract is retained.
- Smart presentation filters unknown/finished goals. Smart and sweep acceptance
  recheck current goal/provenance and home/rate settings before writes, including
  settings or repository changes without collector emissions. Manual quick-add
  rejects unknown denomination as well.
- Narrow GoalCard adjustment hides monetary progress/completion/quick-add for
  unknown currency instead of presenting the legacy label as verified money.
  No goal repair/confirmation flow was invented.
- Existing successful arithmetic fixtures are now explicitly denominated; monetary
  weights, caps, rounding and assertions are unchanged. The focused golden savings
  oracle fixture was updated for the same reason.

## Local review gate

VERDICT: PASS (local self-review only).
Issues: None blocking in this slice.
- Inspected scoped git status/diff, surrounding source/callers, tests and ownership.
- No new direct DAO path; cancellation behavior and structured safe reason codes
  retained; no raw payloads/messages/stack logging added; no schema implication.
- Recommendation compatibility API also rejects unknown provenance. Production
  consumers use typed decision APIs; compatibility remains test/presentation-only.
- Scoped git diff --check: PASS (line-ending warnings only).
- Independent strict/architecture/privacy guardians: NOT RUN, not waived.

## Validation queue (NOT RUN at packet creation)

Only scripts/validation-runner.ps1; one live process at a time:
1. compile
2. targeted-unit-test: *SavingsGoalCurrencyPolicyTest*
3. targeted-unit-test: *SmartSavingsEngineTest*
4. targeted-unit-test: *MonthlySavingsSweepUseCaseTest*
5. targeted-unit-test: *SavingsGoalsViewModelTest*
6. targeted-unit-test: *SavingsGamificationEngineTest*
7. targeted-unit-test: *SavingsGoalRepositoryCurrencyTest*
8. targeted-unit-test: *GoldenMasterVerificationTest.*savings*

Preflight found no competing Gradle/wrapper client or active runner lock.
Start/Wait/Status records must be polled, not restarted while RUNNING.
PASS requires result.json PASS/0, physical complete.marker, meaningful log, and
matching start/end worktree byte fingerprints. Historical Batch 3 evidence is
not evidence for these source/test edits. Results will be appended after the queue.

## Risks and next steps

- No cross-currency recommendations added. Unknown legacy goals intentionally
  remain nonactionable until a separate owner-approved confirmation flow exists.
- Rechecks do not create an atomic settings/goal/history transaction. Balance and
  history are still separate persistence boundaries; multi-goal sweep acceptance
  remains fail-closed. Visible failures/idempotence belong to Batch 8, not this slice.
- Batch 5: semantic contribution-history corruption validation. Currency-less
  legacy history is unknown, not automatically corrupt; preserve current bytes.
- Batches 6-7: source boundaries/PDF and device evidence; Batch 8 persistence last.
- SpendingThresholdCalculator raw-money/currency policy still requires owner review.
- Broader shards, static guards, independent acceptance and final freshness are
  outstanding; this is not whole-wave, release, or commit-ready acceptance.

## Initial validation attempt and test-only correction

- compile vr-20261011-172847-f9442a21: PASS/0, physical complete.marker and
  meaningful log, equal start/end fingerprint
  7007136fba13b38e7e68c5d233a6c8e01878243bfb5ce100cd1c371005cca731.
- First targeted run vr-20261011-173744-cfaf8e78: FAIL/1 at test Kotlin
  compilation, E_COMMAND_FAILED, same unchanged fingerprint. No tests ran.
  Remaining queue steps did not start; no active runner lock remains.
- Root cause: two new EUR/USD/GBP mocks passed displayCurrency as simulator
  parameter five (Double) instead of four (String). Changed only these two calls
  to match the actual public signature. No production arithmetic/assertion changes.
- Local correction review: PASS. Restart bounded compile/test queue on the new
  fingerprint; the first attempt is retained, not treated as a test pass.

## Bounded validation completion

The corrected frozen queue passed. Physically inspected every terminal
result.json, complete.marker and meaningful stdout log tail: PASS/0, no logged
test failures, equal start/end fingerprint
4696910394c48665e2127e281c628b51afcde3c065d5d6a35402f0be72b57a6e.
The read-only runner fingerprint function returned the same current hash before
this evidence append; no application/test bytes changed after the runs.

| Runner ID | Profile/filter | Logged test passes |
|---|---|---:|
| vr-20261011-174621-87986978 | compile | n/a |
| vr-20261011-174651-a2415649 | *SavingsGoalCurrencyPolicyTest* | 4 |
| vr-20261011-175622-777c089a | *SmartSavingsEngineTest* | 20 |
| vr-20261011-175753-9cbee8fe | *MonthlySavingsSweepUseCaseTest* | 27 |
| vr-20261011-175922-86edc1d9 | *SavingsGoalsViewModelTest* | 26 |
| vr-20261011-180047-52d812e9 | *SavingsGamificationEngineTest* | 23 |
| vr-20261011-180237-6b1dfd2e | *SavingsGoalRepositoryCurrencyTest* | 3 |
| vr-20261011-180427-a17d7642 | *GoldenMasterVerificationTest.*savings* | 1 |

104 passing tests across seven focused, nonoverlapping class/method filters.
Corrected compile was up-to-date (24s; 16 tasks up-to-date); the earlier production
compile actually executed changed production/resource tasks. The corrected
policy test run compiled the test sources and executed testing (9m16s, 4 executed
tasks/31 up-to-date). Each subsequent filter executed testing (1 task executed,
34 up-to-date), not a cached assumed test pass.

Commands:
- powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
- Same wrapper with -Profile targeted-unit-test -TestFilter '<table filter>'.
- Wait polled each existing RunId; the queue stopped on the first failed attempt
  and restarted only after its terminal failure and the reviewed test-only fix.

Result/marker/stdout.log/stderr.log files are beneath
build/validation-runs/<runner ID>/. No active lock remained, scoped git diff
--check passed, and nothing was staged or committed. Final pre-evidence-append
tree: 150 tracked changes and 51 untracked paths, still mixed.

This status/evidence update changes the overall worktree fingerprint only in
documentation. These are pre-documentation-append gates, not a final whole-wave
freeze. Independent strict/guardian review, broader guards/shards and device
evidence remain NOT RUN/pending. Next approved implementation batch: Batch 5.
