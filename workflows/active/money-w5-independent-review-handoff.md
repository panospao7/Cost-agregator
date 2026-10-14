# Wave 5 independent acceptance review - 2026-10-13

Status: **FAIL - Wave 5 is NOT ready to finalize.** Two demonstrated savings-sweep
acceptance defects (R16) block acceptance. Validation evidence for the recorded
snapshot is genuine and correctly traced, but it cannot prove behavior that the
tests do not cover. This is an independent, read-only reviewer pass. No
implementation, test, guard, config or staging changes were made. This file is
the only file written.

Reviewer constraints honored: no subagents (user instruction), no edits, no
staging/commit/push/reset/cleanup, no new runner executions (see "Validation").
Resume from this packet; do not rely on the review conversation.

## 1. Reviewed candidate and workspace state

| Item | Value | Verified how |
| --- | --- | --- |
| Branch | `bug-fixes` | `git rev-parse --abbrev-ref HEAD` |
| Pre-Wave-5 base | `a1c66173219d7d959b0d36859a68176aaf814242` | `git cat-file -t` = commit; ancestor of source |
| Source checkpoint | `d5d4d97e014078d651a841b8309c748ed93d9944` | commit; ancestor of docs checkpoint |
| Documentation checkpoint | `3e8377e42083c92990f32f4e46fdcb481c29cc3a` | = HEAD = `origin/bug-fixes` |
| Range commits | `73c4e4c4` (WIP money/migration), `4137afdf` (conversation export), `d5d4d97e` (B8 + validation repairs), `3e8377e4` (docs) | `git log a1c66173..HEAD` |
| Full source delta | 248 files, +173,424/-2,669 (60 added, 188 modified; includes a 144,394-line transcript) | `git diff --stat a1c66173..d5d4d97e` |
| Docs delta d5d4d97e..3e8377e4 | one file: `money-w5-savings-b8-20261012-handoff.md` (+21) | `git diff --stat` |
| HEAD did not advance | HEAD = documentation checkpoint; no later commits | `git log` |
| Working tree | tracked: `.codex/config.toml` modified; untracked: `.claude/`, `CLAUDE.md`, `docs/prompts/release-1.0-assurance/`, `workflows/active/release-1.0-assurance-handoff.md` | `git status --porcelain` |
| Active validation lock | none (`active.lock.json` absent; stale `active.lock.json.7412.tmp` from 2026-10-12 is an orphan temp, not a lock) | `ls build/validation-runs` |

## 2. Candidate-boundary verification

- `git diff --name-only 4137afdf d5d4d97e` = exactly the 56 paths in
  `build/validation-runs/wave5-20261013-commit-preflight.json` (set equality, no
  extras/omissions).
- The runner fingerprint covers every HEAD-modified + untracked path
  (`scripts/validation-runner.ps1` `Get-WorktreeFingerprint`). Therefore every
  path outside the 56 equals `4137afdf` in both the validated snapshot and the commit.
- All 33 excluded snapshot paths are unrelated (`.codex/config.toml`, 16 `.claude/agents/*`,
  `CLAUDE.md`, 14 `docs/prompts/release-1.0-assurance/*`, the release-assurance
  handoff). No app/test/guard/config path was excluded.
- 48 audited implementation hashes (`wave5-20261013-closure-evidence.json`):
  46 equal the current worktree bytes and the committed blobs (`git hash-object`
  vs `git rev-parse d5d4d97e:<path>`, autocrlf-normalized) and are unchanged at HEAD.
  The 2 deviations are `SavingsContributionLedgerDao.kt` and
  `SavingsContributionLedgerEntry.kt`, whose before-hashes equal the audited hashes
  in `wave5-20261013-whitespace-correction.json` (documented whitespace-only edit).
  Their post-edit compile is verified in section 6.
- Unrelated exclusions were preserved and are excluded from this acceptance.

## 3. Independent verdicts

| Dimension | Verdict | Basis |
| --- | --- | --- |
| Strict source | **FAIL** | F1 and F2 are demonstrated R16 defects; F3 is a visible-outcome contract gap. |
| Architecture / legal paths | PASS | One owner (`SavingsSweepAcceptanceCoordinator.accept`) with one Room transaction; barrier check matches the existing `runWrite` semantics; exact writer grants; no DAO bypass; no Room+DataStore dual write for sweeps. |
| Privacy / security | PASS (introduced code) | Added diagnostics use controlled codes and class names capped at 80 characters; no added `e.message`, stack trace, path, SQL text or payload. Observation F9 (committed transcript) is repository hygiene, not code. |
| Room / migration / backup | PASS | Additive 149->150; schema 149->150 delta exactly matches migration SQL; DI/entity/DAO parity; TIER_1_EXACT ledger; pre-150 exact-zero post-migration verification; device upgrade test passed. |
| Test adequacy | **FAIL** (R16 scope) | No test for non-representable goal completion (F1) or recompute-after-acceptance in the same month (F2). One VM test verifies obsolete collaborators (F7), and one pins silent rejection (F3). Elsewhere coverage is strong (385 tests added, none removed or ignored). |
| Validation provenance | PASS with qualifications | 12 runs plus the post-doc guard are physically verified; cache/up-to-date results trace to actual passing executions; no full-suite run on the exact commit bytes (whitespace-only delta; compile verified). |

**FINAL WAVE 5 ACCEPTANCE: FAIL.**
**WAVE 5 READY TO FINALIZE: NO.**

## 4. Findings

Severity: High = acceptance-blocking defect; Medium = contract gap that requires
remediation or explicit human acceptance; Low = non-blocking.
"Regression" = introduced by Wave 5. "Retained" = pre-existing behavior that
remains inside a Wave 5 contract scope.

### F1 - HIGH - Exact per-goal target cap rejects legitimate goal-completing sweeps (demonstrated)
- Where: `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsSweepAcceptanceCoordinator.kt:231`
  (`goal.currentAmount + allocation.suggestedAllocation <= goal.targetAmount`, no
  tolerance) and the allocator's gap cap at
  `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt:411,446`.
- Failure scenario: goal current 592.09, target 1975.95; safe sweep 1892.71.
  The allocator caps at `gap = 1975.95 - 592.09 = 1383.8600000000001`. The
  coordinator computes `592.09 + 1383.8600000000001 = 1975.9500000000003 > 1975.95`
  and returns `Rejected(SAVINGS_SWEEP_SNAPSHOT_MISMATCH)`. Recompute yields the same
  allocation, so this sweep can never be accepted. Reproduced with JVM doubles
  (single-file Java check under a scratch temp dir; not a project build). A
  faithful IEEE-754 port of `allocateAcrossGoals` rejects **1.3%** of single-goal
  completing sweeps (1,345/100,000) and 0.74% of random 1-4 goal scenarios. The
  aggregate check had 0 false rejections.
- Impact: Fails closed (no money corruption). However, a primary R16 success path
  (completing a goal) is permanently refused, and the shown reason ("snapshot
  mismatch") is misleading. The handoff's "exact completion allowed" claim is tested
  only with exactly representable values (`SavingsSweepAcceptanceCoordinatorTest.kt:336`,
  990 + 10).
- Minimal correction: make the producer emit allocations that satisfy the persisted
  invariant in double arithmetic. For example, after capping, step
  `Math.nextDown` until `current + allocation <= target`, or cap with a BigDecimal
  minor-unit computation. Keep the coordinator's exact check (do not add an
  exceed-target tolerance). Add regression tests with 592.09/1975.95 through the use
  case and coordinator.

### F2 - HIGH - Monthly safe-sweep bound is not enforced across recommendations; the same month can be swept repeatedly (confirmed by source trace)
- Where: `SavingsSweepAcceptanceCoordinator.kt:58-61,267-291`. The key includes
  `computedAt` and `currentProgress`, and the coordinator never consults
  `monthEnd`/accepted plans. `MonthlySavingsSweepUseCase.kt:57-68,159-218` has no
  plan/ledger dependency. `SavingsSweepPlanDao.getPlansForMonth`/`getMostRecentForMonth`
  have no production consumer. `SavingsGoalsViewModel.kt:208-215,415-419` recomputes
  on init and refresh.
- Failure scenario: In the month-end window, the user accepts a 40 EUR sweep (goal
  200 -> 240). The underspend is budget-derived and unchanged by a goal increment.
  Returning to the screen (new ViewModel) or refreshing recomputes the same safe
  40 EUR and allocates it to the remaining gap. That produces a new key, because
  `currentProgress` and `computedAt` changed. The coordinator accepts again, and
  repeats until goals fill. A crash or restart after commit followed by
  recomputation reaches the same path.
- Impact: Recorded savings can exceed the month's safe amount by an unbounded
  multiple. The previous independent FAIL ("missing aggregate safe-sweep bound") is
  closed only per recommendation, not per month. The coordinator test
  `committed_acceptance_survives_database_reopen_and_retry` (line 203) retries the
  identical object only, so it does not model real recomputation.
- Minimal correction: in the coordinator transaction, reject (or cap) when ACCEPTED
  plans/ledger rows already exist for the same `monthEnd` with a different
  acceptance identity. Alternatively, have the use case return NoRecommendation (or
  subtract already-accepted amounts) for a month with accepted plans. Both should be
  backed by the existing `(monthEnd, status)` index. Add tests: accept -> recompute ->
  re-accept in the same month (rejected), and the next month (allowed).
  No schema change is needed if the existing index is used.

### F3 - MEDIUM - ViewModel pre-coordinator stale checks reject silently while the card stays actionable (regression in visible-outcome contract)
- Where: `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt:244,246,247,249-255,257-258`
  (bare `return@launch`). The behavior is pinned by
  `SavingsGoalsViewModelTest.kt:691` (`sweepAcceptanceRechecksSettingsAfterRecomputation`
  asserts the recommendation is still displayed with no reason).
- Failure scenario: after an expense is added, the held recommendation differs from
  the refreshed one (line 247). Each Accept tap does nothing, and no
  `sweepUnavailableReason` is set until the user refreshes.
- Impact: This contradicts plan Batch 8 item 6 ("UI must not offer a contract
  acceptance silently rejects"). It is not a money-safety issue.
- Minimal correction: on each early exit, set a controlled reason (for example
  `SAVINGS_SWEEP_STALE_RECOMMENDATION`) and clear or replace the recommendation.
  Update the pinning test to assert the visible reason.

### F4 - MEDIUM (retained) - Dashboard Monte Carlo/runway use the overall budget amount with no currency/provenance check
- Where: `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:586,772,963`
  (`G-MONEY-ALLOW[CURR-587-05]`). Budgets carry `currency`/`currencyAssumption`
  (`Budget.kt:67-68`).
- Failure scenario: an EUR budget with home changed to USD feeds a raw EUR number
  as a USD budget into `simulate(..., displayCurrency = "USD")`.
- Impact: The Batch 1 "complete decision inputs" contract is only partially met. The
  sweep (`MonthlySavingsSweepUseCase.kt:159-164`) fails closed on the same condition.
  Not a regression.
- Minimal correction: apply the sweep's budget-currency gate before simulation, or
  return controlled unavailable. Alternatively, record an explicit human-accepted
  limitation.

### F5 - MEDIUM (retained) - Email receipts use home currency as source-currency evidence and can auto-create expenses
- Where: `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:1047,1051,1171,1285`
  (`emailData.currency ?: homeCurrency`; `EmailReceiptData.currency` is nullable).
- Failure scenario: a high-confidence email with no currency token creates an
  expense in the user's home currency.
- Impact: The scan/OCR and bank paths now fail closed. The email path is inside
  the Batch 6 file scope ("missing source must stay unresolved") but is unchanged.
  Not a regression.
- Minimal correction: when `emailData.currency` is null, route to review with
  `XXX`, matching the OCR path. Alternatively, record an explicit human-accepted
  limitation.

### F6 - MEDIUM (retained, open prerequisite) - Smart-savings cap uses P90 of raw, currency-mixed amounts
- Where: `app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt:541-549`
  (the `homeCurrency` parameter is unused) and
  `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt:114`.
- Failure scenario: an EUR-home user with JPY/KRW/HUF transactions (all supported)
  gets a P90 inflated 100-1000x, so the "safe to save" cap becomes meaningless.
  The inverse case over-restricts the cap.
- Impact: This breaks the complete-input-only policy for an actionable
  recommendation. The B3 (line 101) and B4 (line 108) handoffs recorded it as
  needing "owner review before whole-wave acceptance", and no later packet closes it.
- Minimal correction: normalize the percentile input to home currency (or restrict
  it to home-currency rows with complete quality). Otherwise fail closed or omit the
  cap with an explicit reason.

### F7 - LOW - VM test asserts the wrong collaborators for the sweep stale-rate path
- Where: `SavingsGoalsViewModelTest.kt:399-418`. It verifies
  `incrementSavingsGoalAmount`/`recordContribution` are not called, but sweeps now
  use the coordinator, so the test passes even if `accept` is called.
- Correction: add `coVerify(exactly = 0) { savingsSweepAcceptanceCoordinator.accept(any()) }`.

### F8 - LOW - New ledger entity/migration declare an implicit EUR default
- Where: `SavingsContributionLedgerEntry.kt:38-39`, `DatabaseMigrations.kt:123`.
  The sole writer always passes a currency, so there is no current effect. This is
  inconsistent with the R15 no-implicit-EUR contract. Changing it now requires
  schema/migration work, so it should be deferred and documented rather than churned.

### F9 - LOW (hygiene/privacy, user decision) - Private session transcript committed and pushed inside the reviewed range
- Where: `codex-session-01a12ab8-cb3b-7f00-9254-7440f59190a2.md` (repo root,
  10.7 MB, commit `4137afdf`). `.gitignore` `session-*.md` does not match the
  `codex-` prefix.
- Heuristic scan found no credential patterns (only a scanner regex and a test
  fixture). It does contain about 1,025 local user-path references and the full
  agent conversation. This contradicts the cross-PC packet's claim that the private
  transcript was excluded.
- Correction: the user decides whether it should remain in history. Removing it
  from history requires a force-push and explicit approval. At minimum, extend
  `.gitignore`.

### F10 - LOW (defense in depth) - Coordinator does not recheck `safeSweepAmount <= totalUnderspend - riskBuffer`
- Where: `SavingsSweepAcceptanceCoordinator.kt:159-211`. The ViewModel recompute
  equality (line 247) covers today's only caller.

### Evidence gap G1 - R13 cancellation removals and four writer grants: authorization not verbatim-recorded
- Five new baseline removals were made (FinancialHealthScoreV2 G-CANCEL-01,
  ComputeDashboardWidgetsUseCase G-CANCEL-01, MonthlySavingsSweepUseCase
  G-CANCEL-01/02, HistoricalSpendingDistribution G-CANCEL-02), in addition to the
  separately authorized AdvancedAnalyticsEngine removal. That gives 118 -> 112, and
  the cancellation log at the snapshot shows baseline=current=112, NEW 0.
- Four `MIT-DB-W5-R16` grants are exact (single callable, exact DAO operation,
  pinned by `scripts/test_verify_db_access_boundaries.py` 424 -> 428).
- These are ratchet tightening and narrowly scoped exceptions. Neither weakens a
  guard (`FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/FG-06/FG-07/FG-23).
- The repository records only the assertion "previously recorded human authority".
  The committed transcript ends at the `73c4e4c4` push and predates Batch 8. The
  human owner must confirm both approvals in writing. This is not a code defect.

## 5. Prior FAIL findings - explicit re-review

| Prior FAIL | Source | Disposition |
| --- | --- | --- |
| Missing aggregate safe-sweep bound | B8 same-model review | **Closed per recommendation**: BigDecimal sum <= safe + n*ulp(safe), with non-finite/overflow rejected (simulation: 0 false rejections). **Retained at month scope: F2.** |
| Missing per-goal target cap | B8 same-model review | **Implemented and safe** (in-transaction check plus CAS on current/target/currency). **Retained defect: F1** (over-rejection from floating point). |
| Partial inputs reaching simulator | B1 strict/privacy FAIL | Closed: `computeMonteCarlo` requires complete owner quality and a non-partial forecast with zero exclusions, explicit currency, cancellation rethrow, and controlled reasons (`ComputeDashboardWidgetsUseCase.kt:937-982`). Budget-input currency remains: F4. |
| R01-R12 (8454ae review) | independent FAIL | Reconciled in the section 7 matrix. |
| Batch 6 local FAIL (R06/R14 runway legacy) | local | Closed: runway uses `SavingsGoalCurrencyPolicy.knownCurrencyOrNull` (B6 correction). |

## 6. Validation evidence (independently verified)

All records below were read from `build/validation-runs/<id>/{result.json,complete.marker,stdout.log,stderr.log}`.
Each has terminal PASS, exit 0, physical marker
`VALIDATION_COMPLETE/status=PASS/exit_code=0`, nonempty stdout, and start fingerprint =
end fingerprint = `9b3c78c8856b8e278d9819ca7c2e589753cef61ed1392c8300b4148156adcf4d`
(git_revision `4137afdf`). All matched the closure-evidence copies.

| Run | Profile | Execution status (from stdout) |
| --- | --- | --- |
| vr-20261013-102452-6a1bf581 | registered-guard ignored_test_budget | actual guard execution |
| vr-20261013-102605-6b2a6f38 | static-guards | 25/25 PASS, 0 blocking/warn/infra; cancellation 112/112 NEW 0; `guard_tests` executed |
| vr-20261013-104157-d01ca439 | app-check | 13 executed/74 up-to-date; lint and bridges executed; `testDebugUnitTest`/`testReleaseUnitTest` UP-TO-DATE |
| vr-20261013-104818-b28377bf | compile | 16 up-to-date |
| vr-20261013-104829-855605f1 | migration-tests | 1 from cache, 34 up-to-date |
| vr-20261013-104850-a73311e7 ... 105034-5a2eeee2 | 6 named shards | each 1 from cache, 34 up-to-date |
| vr-20261013-105055-c7852189 | connected-tests (API 35 emulator) | 14 executed; HTML index: 359 tests, 0 failures, 9 skipped, 100% |
| vr-20261013-114318-efdfe3b2 | registered-guard guard_docs_truth (post-doc fp `d0deaab2`) | PASS/0 |
| vr-20261013-121642-ff0b5a65 | compile after whitespace fix (fp `97c72997`) | KSP + Kotlin **executed** (2 executed/14 up-to-date) |

Cache provenance (honest labeling):
- The last **actual** executions of migration-tests and all six shards are at
  fingerprint `17a08ae9` (vr-20261013-094810 ... 095806, each "1 executed").
- The last actual full debug+release unit-task execution is app-check
  `vr-20261013-095857-e1030d8a`. Both test tasks ran to completion; its only
  `FAILED` line is the legacy `verifyNoIgnoredGrowth` counter (32 vs 29, KDoc mentions).
- No `ignoreFailures` is configured, so Gradle cache/up-to-date entries imply
  prior passing executions with identical task inputs.
- Device HTML: `DatabaseMigrationMatrixTest.html` and
  `AccountingExportPdfRepositoryInstrumentedTest.html` SHA-256 values equal the
  closure record (written 11:09:29Z, inside the run window). All five required
  cases are `success`: `baseline_chain_to_current_version_passes`,
  `fresh_vs_migrated_table_parity_at_current_version`,
  `migrate_149_to_150_adds_savings_acceptance_ledger` (seeds goal/accepted plan;
  asserts preservation, null key, zero ledger rows),
  `exportExpensesAccountantReportPdfWritesPdfOutput`, and
  `mixedCurrencyReportSerializesAndRendersMultiplePagesWithoutFx`.
- The 9 skips are pre-existing `@Ignore`d archived pre-v51 migration tests; no
  required case was skipped.
- No test-result XML was consumed.

Limits of the evidence:
- There is no full-suite run on the exact committed bytes. The only implementation
  delta is whitespace in two files, and a post-delta compile is verified.
- Any fix for F1-F3 invalidates all of the above for the changed files.

This reviewer started **no** runner executions. Reruns could not change the
verdict (F1/F2 are logic defects that existing tests do not exercise) and would be
UP-TO-DATE/FROM-CACHE for unchanged inputs. Reviewer-side commands were read-only:
git inspection, Python analysis of artifacts/schemas, and a scratch JVM arithmetic
check outside the repository.

## 7. Requirement-to-source-to-test-to-evidence matrix

| Req | Source (key path) | Tests | Evidence | Disposition |
| --- | --- | --- | --- | --- |
| R01 merchant ownership | `AnalyticsViewModel.kt:77-98` (group transaction IDs) | `AnalyticsViewModelInsightsTest` | shards (cached <- 17a08ae9) | Closed |
| R02 same-currency savings, stale rec rejection | SmartSavings/sweep/VM | VM, SmartSavings, sweep tests | shards | Closed (documented same-currency-only limitation) |
| R03 nullable totals/levels | `SavingsGoalsViewModel.kt:133-136` | VM/gamification | shards | Closed |
| R04 settings change during compute | VM `loadGoals` combine + recheck `:171-172` | currency/rate change tests | shards | Closed |
| R05 complete-input decisions | smart/sweep/distribution/health | provenance tests | shards | **Partial: F6** (raw P90 cap) |
| R06 runway currencies/provenance | `FinancialHealthScoreV2` | `FinancialHealthScoreV2Test` | shards | Closed |
| R07 cancellation propagation | multiple owners | identity tests | static cancellation 112/112 | Closed (ratchet: G1) |
| R08 empty zero vs unavailable | golden/analytics | `GoldenMasterVerificationTest` | shards | Closed |
| R09 bounded conversions | `SavingsGamificationEngine` | 5,000-event fixtures | shards | Closed |
| R10 chronological completion | gamification scan | earliest-crossing tests | shards | Closed (ledger events merged) |
| R11 PDF arithmetic/policy/device | `AccountantReportPdfExporter` | `AccountantReportPdfMoneyTest` + 2 device tests | connected PASS (verified HTML) | Closed |
| R12 whole-candidate acceptance | - | - | this review | **FAIL** |
| R13 cancellation ratchet | `config/baselines/cancellation.json` | guard tests | static PASS | Source OK; **G1 authorization record gap** |
| R14 legacy provenance | `SavingsGoalCurrencyPolicy.kt` | policy/VM/coordinator tests | shards | Closed |
| R15 explicit currencies | simulator/distribution signatures (no EUR defaults) | simulator/distribution tests | shards | Closed (F8 low) |
| R16 sweep acceptance persistence | coordinator + v150 ledger | 16 coordinator tests, VM tests | data shard; connected upgrade | **FAIL: F1, F2, F3; F7 test gap** |
| R17 typed unavailable quality | typed results, dashboard MC | dashboard/sweep tests | shards | Closed with limitation; **F4 retained** |
| R18 bank/receipt fail-closed | bank processor `CURRENCY_UNKNOWN`; OCR -> review `XXX` | bank/receipt boundary tests | shards | Closed for bank/OCR; **F5 retained (email)** |
| R19 history corruption | strict reader, ledger validation | history repo tests | data shard | Closed |
| R20 PDF comment | exporter | - | - | Closed |
| Tax/item/category conservation | `ReceiptTaxAllocator`, `SourceCurrencyAllocation` | allocator tests | shards | Closed (BigDecimal item and category+unallocated checks) |
| Room 149->150 | `DatabaseMigrations.kt:110-144`, schema `150.json` | `migrate_149_to_150...`, MigrationRegistration | migration + connected | Closed |
| Backup exact tier | `BackupVerifier.kt`, `DatabaseBackupRepositoryImpl.kt:1028-1246` | `BackupVerifierSavingsLedgerTest` (4 cases, real restore) | data shard | Closed |
| Archived migration repairs C1-C5, category index | `AppDatabase.kt` archived bodies | DatabaseMigrationTest/Contract/CategoryDao | connected | Closed: no exported schema 112-150 has `index_categories_name_nocase`; `getOrInsertByNameNoCase` is `@Transaction` |
| Release test repairs | `BuildConfig.DEBUG` branches | release denial asserts | app-check (cached release units) | Closed: debug asserts retained; release asserts denial and no side effects; schemas in `test` resources only |
| Ignored-test gate | `build.gradle.kts` bridge | `test_verify_ignored_test_budget.py` | registered guard PASS | Closed: same roots, fixed 29, override removed (strengthening) |
| Runner serialization | `validation-runner.ps1` | `test_validation_runner_protocol.py` | static guard_tests | Closed (fail-closed reads, orphan -> INFRA/2, exact-command duplicate detection) |
| Writer grants | `db_ownership_policy.yml` (4 exact) | pinned contract test | static PASS | Closed in source; G1 for authorization record |

Unreviewed or limited areas: the transcript commit content beyond the heuristic
scan, and a line-by-line review of all 87 main-source files. Analytics, health and
forecast engines were sampled at their contract boundaries, building on prior
packet evidence. Inherited diagnostics (for example throwable logging in
`CloudReceiptItemCategorizationService` and the P50/P90 debug logs) predate Wave 5
and are not claimed closed.

## 8. Remaining mandatory gates (all required before finalization)

1. Fix F1 (target-cap floating-point invariant at the producer) with regression tests.
2. Fix F2 (month-level sweep bound/idempotency) with accept -> recompute ->
   re-accept tests.
3. Fix F3 (visible stale rejection), and correct the F7 test.
4. Human-owner disposition of F4, F5 and F6: fix, or record each as an explicit
   accepted limitation.
5. Human-owner written confirmation of G1 (the five cancellation removals and the
   four `MIT-DB-W5-R16` grants).
6. Fresh serialized runner validation after the fixes: compile; targeted
   coordinator, VM and sweep tests; data, domain-n-z and runtime-ui shards (or all
   six); app-check (debug and release units must execute); full static-guards.
   Add migration-tests and connected upgrade only if the fix touches schema or
   indexes.
7. Independent re-review of the remediation by a reviewer other than the implementer.
8. Optional user decision: F9 transcript retention and `.gitignore`.

Unrelated `.codex/config.toml`, `.claude/`, `CLAUDE.md`, release-assurance prompts
and their handoff remain excluded and untouched.
