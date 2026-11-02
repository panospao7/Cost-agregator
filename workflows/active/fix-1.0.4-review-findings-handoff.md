# fix-1.0.4-review-findings - handoff

## Origin
Owner disposition 2026-11-01 ("do your recommendation") on the whole-app review findings, under owner-approvals items 86/87/92.
Plan: `workflows/active/fix-1.0.4-review-findings-plan.md` (DRAFT - Gate 1 pending).

## Lane
- Worktree: `build/fix-wt` (inside the main checkout's gitignored `build/`; the harness denies access outside the workspace, so
  a `../Cost-aggregator-<id>` sibling is unreachable to subagent tools. Recorded as a deviation from PARALLEL_LANES:11.)
- Branch: `feature/fix-1.0.4-review-findings`
- Base: `d9c55d4d` == `origin/feature/ai-evolution` == the exact SHA the review analysed.
  **Deliberately NOT branched from the main checkout (`044be2c8`, 104 dirty paths).**
- `local.properties` copied (gitignored). Worktree clean apart from this plan/handoff.

## Evidence base
`build/review-wt/build/full-review-2026-11/` - 70 artifacts.
Revalidation package: `12-findings-index.csv`, `13-REVALIDATION-GUIDE.md`, `13a`-`13d`.
Start there, not from chat history.

## Owner dispositions (2026-11-01)
- D1 P-08-1/2: add retention targets + export scrub, **keep `STORE_RAW` default**.
- D2 DR-05: **defer the `REFUND` schema change**; add a stopgap warning instead (revised from my first suggestion).
- D3 BL-11: gate `carbon`, `price`, `negotiation` via `DEFERRED_FEATURE_IDS`.
- D4 Scope v1: the 6 severe issues (+2 near-free HIGHs), NOT the money-display family.

## Slice statuses
| Slice | Status |
|---|---|
| S0 Re-anchor | NOT STARTED |
| S1 Privacy (P-08-1, P-08-2) | NOT STARTED |
| S2 Parser (FL-02, FL-01) | NOT STARTED |
| S3 Forecasting (BL-01, BL-03) | NOT STARTED |
| S4 Release gating (BL-11) | NOT STARTED |
| S5 Refund stopgap (DR-05 partial) | NOT STARTED |
| S6 Guard additions | NOT STARTED - **needs explicit owner approval (guard change)** |
| Z Candidate gate | NOT STARTED |

## Pre-flight facts established at this SHA
1. BL-01 still valid: `HistoricalSpendingDistribution.kt:179` substitutes `0.01`; `isUsable` (`:437`) has **no sigma ceiling**;
   `MonteCarloSpendingSimulator.kt:111` degrades only on `!isUsable`; `MonteCarloForecastCard.kt:151,258` always shows P10-P90.
2. `DEFERRED_FEATURE_IDS = setOf("tax","investment","challenges")` (`DeferredFeatureGate.kt:21`); `carbon`/`price`/`negotiation`
   are `FeatureConfig.kt:52,68,76`. So S4 is a 1-line set addition + tests.
3. S5 is schema-free by design; no Room version bump, no migration, no snapshot regeneration.

## Hot files (one writer program-wide)
`strings.xml` (S5 owns; S1 may add retention strings first) | `LEGAL_PATHS.md` (S5) | guards/policy/baselines (**S6 only**) |
`TransactionLifecycleCoordinator` (**not touched**).

## Concurrency
Owner raised the cap to 2 agents. Plan keeps **one writer per lane** (PARALLEL_LANES:12); the second slot is used for the
independent `test-author` on the T3 money/privacy slices (owner item 87) or a read-only guardian/reviewer.
Validation remains strictly serial through `validation-runner`.

## Human decisions still open
1. **Gate 1: approve this plan** (or amend scope/order).
2. **Approval to start S6** (guard change = AGENTS.md Stop-and-ask).
3. Merge/push decision at Z. No push without an explicit request.

## Next action
Awaiting Gate 1. On approval: run S0 (record baseline `targeted-unit-test`, re-confirm the 6 cited lines), then S1.

## S0 - COMPLETE (2026-11-01)

### Baseline validation
- Run `vr-20261101-150207-6269e9f4`, profile `targeted-unit-test`, **PASS** (exit 0).
- 148 tests across 13 classes; 0 failures / 0 errors / 0 skipped.
- worktree fingerprint start == end (`3e5bc81f...3712`) - worktree-matched, not stale.
- git revision recorded: `d9c55d4dcb67a1f531a5015ec6b7efd7ab325bee`.
- Per-filter matches confirm the target test classes EXIST at this SHA: RetentionTargetCoverageGap 12, RetentionTargetPurge 8,
  ExportAnonymizer 12, SmsParser 17, ClipboardAmountParser 16, HistoricalSpendingDistributionBoundary 14,
  MonteCarlo 7+1+12, SmartSavingsEngine 22, SpendingThresholdCalculator 13, DeferredFeatureGate 6,
  FeatureConfigNavigationContract 8.
- `*NavigationDeferred*Test` matched 0 - that class does not exist; use `FeatureConfigNavigationContractTest`.

### Re-anchor: ALL SIX FINDINGS STILL PRESENT at d9c55d4d
P-08-1 YES (3 sites + createdAt never set) | P-08-2 YES (in-place UPDATE defeats CASCADE) | BL-01 YES (0.01 substitution, no
sigma ceiling in isUsable) | BL-03 YES (remaining formula :536-537, cap :565, minOf :592) | FL-02 YES (first match :117-118,
0.85f :138) | BL-11 YES (set literal :21, three ids :52/68/76).

### SCOPE CORRECTIONS found by S0 (these change S1/S4)
1. FOUR UserCorrection write sites, not three: `ExpenseRepository.kt:664-679` ("Bulk category update") also omits `createdAt`.
2. A new retention target is NOT one file: `RetentionPolicyContract.kt:29-62` enforces 1:1 registration and
   `DataRetentionWorker.kt:137-175` enforces cutoff routing. S1 must update RetentionModule + RetentionPolicyContract together.
3. `ReceiptItemCategorization.itemDescription` is a NON-NULL String (:42) - the scrub must write `''`, never null.
4. BL-11 also needs save-token checks at `NavigationController.kt:136-139, :225-228`
   (`carbon_footprint`, `price_protection`, `bill_negotiation`).

### Slice status
S0 COMPLETE. S1 NOT STARTED (next); S6 still needs separate owner approval (guard change).

## S1 - COMPLETE (2026-11-01) - privacy retention + export scrub

### Validation
- Run `vr-20261101-155021-9cc0a77c`, `targeted-unit-test`, **PASS** (exit 0). 291 tests / 24 suites, 0 failures, 0 errors.
- Fingerprint `efc22c8b...d23b` start == end. Revision d9c55d4d. Elapsed 467s.
- `*FlowPipeline*Test` matched 0 classes - `FlowPipelineTestHarness` is a helper, not a test class; it still compiled.

### Production changes (8 files)
- `ReviewQueueRepository.kt` - `createdAt = timeProvider.now()` at approve (:387), reject (:539), markAsRelevant (:718).
  (The class already took a TimeProvider at :59.)
- `ExpenseRepository.kt` - bulk-category site now sets `createdAt` (:679), and a **required** `timeProvider` ctor param was added.
- `UserCorrectionDao.kt` - set-based `redactNotificationTextOlderThan` (:107-118).
- `ReceiptItemCategorizationDao.kt` - set-based `redactForReceiptsOlderThan` keyed on scanned_receipts (:109-124).
- `RetentionModule.kt` - new `user_corrections.notificationText` target (:390-421); `scanned_receipts.rawOcrText` extended to
  scrub the child table (:126, :131).
- `RetentionPolicyContract.kt` - registered the new target (:39) for the 1:1 contract.
- `DataRetentionWorker.kt` - cutoff + retentionDays routing for the new target (:159, :168).
- `ExportAnonymizer.kt` - `sanitizeUserCorrections` (:398-400) + `sanitizeReceiptItemCategorizations` (:408-422).

### Selected windows (reused, no new user-facing setting)
- `user_corrections.notificationText`: same as `pending_reviews.notificationText` = `minOf(rawNotificationRetentionDays, 30)`.
- `receipt_item_categorizations`: swept inside the existing `scanned_receipts.rawOcrText` target = `rawOcrRetentionDays`.

### Tests (independent test-author, 5 files)
- `RetentionTargetCoverageGapTest` +2 (boundary: cutoff-1ms scrubbed, at-cutoff and in-window kept).
- `RetentionTargetPurgeTest` +2 (old scrubbed, in-window untouched, idempotent re-run).
- `ExportAnonymizerTest` +2 (redaction + unrelated-table negative control).
- `ReviewQueueRepositoryTest` +3 and `ExpenseRepositoryTest` +1 (createdAt from TimeProvider at all four write sites).
- 14 test files updated for the new `ExpenseRepository` ctor param.

### Things worth remembering
1. **`ExpenseRepository` ctor is now 21 params.** Any future test constructing it must pass `timeProvider`. Hilt is fine
   (`TimeModule` @Binds).
2. **Legacy rows with `createdAt = 0L`** will be scrubbed on the first retention run (they look infinitely old). This is
   intended: it is exactly the leak being closed. Learning columns are preserved.
3. DAO methods are wrapped in `@Transaction` defaults so `verify_db_access_boundaries.py` stays green without touching guard
   policy or allowlists.

### Slice status
S0, S1 COMPLETE. S2 (parser FL-02/FL-01) next.

## S2 - COMPLETE (2026-11-01) - SMS balance stripping + clipboard prefill

### Validation (3 attempts - recorded honestly)
| # | Run | Result | Failures |
|---|---|---|---|
| 1 | vr-20261101-161025-88b08afb | FAIL | 4 new S2 tests (Greek keywords mojibake; ambiguity never detected; clipboard split the grouped balance) |
| 2 | vr-20261101-163459-3b47c800 | FAIL | 2 remaining (English SMS + clipboard) |
| 3 | vr-20261101-164915-be2f5cb2 | PASS | 0 - 206 tests / 17 suites, fingerprint stable, 213s |

### Root causes found and fixed (all real, none papered over)
1. Mojibake Greek literals - an earlier edit wrote the Greek keywords as corrupted bytes, so Greek balance detection never
   worked. Fixed to real UTF-8; verified externally (no BOM, 0 x U+FFFD, upoloipo present in both files).
2. Ambiguity never flagged - balance rejection removed the balance candidate, then candidates.size > 1 was computed on the
   REMAINING set, so a disambiguated message still looked clean. The flag is now captured BEFORE rejection.
3. Trailing regex guard (the real one) - (?![\d.,]) rejected a valid amount followed by sentence punctuation, so 1,245.67.
   never matched at all; only one candidate existed and the message looked clean. Changed to (?![\d,]|\.\d) in three places.
   This is why attempts 1 and 2 failed despite correct disambiguation logic.

### Behaviour now
- "...Balance EUR 1,245.67. Purchase of EUR 12.50 at COFFEE..." -> 12.50 at 0.70f -> NEEDS_REVIEW.
- "Your balance is 1,234.56, you spent 12.50" -> clipboard null; SMS -> 12.50 at 0.70f.
- "Purchase of EUR 12.50 at COFFEE" -> 12.50 at 0.85f -> AUTO_ACCEPT (happy path asserted by test).
- 10/04 still never parsed as an amount.

### Files
Prod: SmsParser.kt, ClipboardAmountParser.kt (ConfidenceRouter untouched - thresholds NOT moved).
Tests: SmsParserTest.kt (+7), ClipboardAmountParserTest.kt (+4).

### Slice status
S0, S1, S2 COMPLETE. S3 (BL-01, BL-03) next.
## S3 - COMPLETE (2026-11-01) - forecasting fit + savings units/cap

### Validation (2 attempts)
| # | Run | Result | Detail |
|---|---|---|---|
| 1 | vr-20261101-171920-043f6f2d | FAIL 1 | a PRE-EXISTING test whose expected value encoded the old buggy formula |
| 2 | vr-20261101-173554-cf5221ae | PASS | 276 tests / 20 suites, 0 failures, 187s |

### BL-01 - the fit is no longer poisoned
- The 0.01 substitution is GONE. mu/sigma fit on POSITIVE qualifying weeks only.
- Quiet weeks are recorded separately (zeroWeekCount, zeroWeekProbability) and sampled by the simulator, so a no-spend week is
  modelled as a probability rather than as a fake tiny amount.
- Pre-first-transaction weeks excluded via effectiveRangeStart = maxOf(lookbackStart, firstTransactionWeekStart) (:154). This is
  the NEW USER fix: ~70 artificial empty weeks were previously the default state.
- MAX_USABLE_SIGMA = 2.0 (:448) added to isUsable (:456), aligned with DataQualityAssessor's existing noisy threshold. A
  pathological fit now degrades to the deterministic result instead of emitting an absurd P90.
- Measured: 6 x 100.00 + 2 quiet weeks over 14 days now P50 ~199 / P90 ~206, vs the old poisoned P50 ~20 / P90 ~4,720.

### BL-03 - like-for-like comparison + a cap that scales
- NEW historicalDiscretionaryShare (:491) = historical discretionary / historical total.
- NEW projectedDiscretionarySpending = projectedHorizonSpending * share (:556); remaining subtracts the DISCRETIONARY projection.
- NEW cap scales with volume: avgWeeklyTransactions (:587), weeklyAggregateP90 (:588), horizon multipliers (:590-592). The
  single-transaction P90 is no longer used as a monthly cap.
- "housing" added to the essential categories (:702) so rent is not treated as discretionary.
- Measured: rent 1000 + dining 300 now ~0.14 on the zero-surplus fixture and ~144.67 volume-scaled, vs the old hard cap of 25.00.

### The stale test (worth remembering)
SmartSavingsEngineTest#monte carlo discretionary baseline excludes essential categories asserted 0.0 and its comment read
"remaining = 30 - (400 * 0.3) < 0 => 0" - the test DOCUMENTED THE BUG as intended behaviour. Corrected to the hand-verified
0.1384615384615386 with the arithmetic written into the comment. Second time this campaign that a test encoded a defect.

### Files
Prod: HistoricalSpendingDistribution.kt, MonteCarloSpendingSimulator.kt, SmartSavingsEngine.kt.
Tests: HistoricalSpendingDistributionBoundaryTest.kt (+3), MonteCarloSpendingSimulatorTest.kt (+2),
MonteCarloSpendingSimulatorGoldenTest.kt (+1), SmartSavingsEngineTest.kt (+4 new, 1 stale expectation corrected).

### Slice status
S0-S3 COMPLETE. S4 (BL-11 release gating) next.
## S4 - COMPLETE (2026-11-01) - release gating of fabricated finance (BL-11)

### Validation
- Run vr-20261101-175626-38c2cf12, targeted-unit-test, PASS (exit 0). 69 tests / 6 suites, 0 failures, 349s. Fingerprint stable.

### Change
Deferred (hidden-in-release) set extended from 3 to 6:
- DEFERRED_FEATURE_IDS: + "carbon", "price", "negotiation"
- DEFERRED_SAVE_TOKENS: + "carbon_footprint", "price_protection", "bill_negotiation"
- isDeferredDestination: explicit mappings for CarbonFootprint, PriceProtection, BillNegotiation
- NavigationController.destinationFromSaveToken: the three tokens gate via .takeIf { deferredAvailable }
- MainActivity: the three screen branches redirect home when deferred features are unavailable

DeferredFeatureGate.available is still BuildConfig.DEBUG, so DEBUG builds keep all six features - nothing was removed.

### Two stale tests updated (third/fourth time this campaign)
DeferredFeatureGateTest asserted "deferred feature ids are EXACTLY tax investment challenges" and built the token set from three
destinations. Both were exact-set contract tests, so they were updated (not weakened) to the six-item set with a comment
recording WHY the set grew. Correct call here: the test's purpose IS to pin the exact set.

### Note for the reviewer
The writer did more than the plan''s "1-line set change": it also touched MainActivity.kt and NavigationController.kt so the three
screens redirect instead of merely vanishing from a menu. Defensible completeness (hidden menu entry != unreachable screen), but
it is the widest-reaching file in this slice and deserves scrutiny at the Z gate.

### Slice status
S0-S4 COMPLETE. S5 (refund stopgap, schema-free) next.
## S5 - COMPLETE (2026-11-01) - refund stopgap (DR-05 partial, schema-free)

### Validation
- Run vr-20261101-183352-80bcc099, targeted-unit-test, PASS (exit 0). 350 tests (incl. 9 new + 202 architecture guards),
  0 failures, 395s. Fingerprint stable.

### What was built (NO schema change)
- NEW domain/transaction/RefundLikelihoodDetector.kt - pure function. A DEPOSIT is refund-like when a PURCHASE exists within
  90 days on/before it, with the same amount (0.01 tolerance via DuplicateDetectionPolicy.areAmountsEqual) and the same canonical
  merchant key (MerchantKeyGenerator). No new DAO, no new dependency.
- TransactionsScreen.kt - informational notice on a matching DEPOSIT row and in ChangeTypeDialog. Diagnostic only: nothing is
  blocked, mutated, or auto-linked.
- values/strings.xml - one new string (transactions_refund_likelihood_notice). NOT added to values-el/values-es (translation out
  of scope; project already has a large known gap). No guard complained.
- docs/architecture/LEGAL_PATHS.md - records the 1.0.x limitation and the edit/delete workaround.

### Boundaries pinned by tests
- amount tolerance 0.01: 200.004 vs 200.0 TRUE, 200.02 vs 200.0 FALSE, 199.5 vs 200.0 FALSE.
- 90-day window: +90d TRUE, +91d FALSE, negative delta FALSE.
- currency mismatch FALSE; non-DEPOSIT row FALSE; empty candidates FALSE; input list not mutated.

### Deliberate scope limit (recorded)
DR-05 remains a REAL defect: refunds are still modelled as DEPOSIT, so a refund still does NOT reduce category/budget spend.
This slice only makes the gap visible and documents the workaround. The real fix (a REFUND type) needs v153 + migration +
SpendingInclusionSql + aggregates + exporters + ~10 guards, and belongs in its own plan.

### Slice status
S0-S5 COMPLETE. S6 (guard additions) BLOCKED pending explicit owner approval - it is a guard change (AGENTS.md Stop-and-ask).
Z (candidate gate) cannot run until S6 is decided.
## S6 + S7 - COMPLETE (2026-11-01) - guard strengthening + the defects it found

### Final state: static-guards 27/27 PASS (vr-20261101-225142-b8b7c77a, 1183s, fingerprint stable)

### S6 - kept vs reverted (owner chose option A)
KEPT (stricter, violations were fixable):
- verify_pii_logging_boundaries.py - new rule flags .message/.localizedMessage used when CONSTRUCTING an event/diagnostic payload
  (TransactionEvent, PipelineDiagnosticEvent, SideEffectOutcome.FailedRetryable, reason=/failureReason=), not just on a log line.
  Also fixed EXCLUDED_DIR_PATTERNS to filter on the repository-relative path so worktrees under build/ are not skipped.
- verify_spending_predicate_usage.py - new rule requires a non-aggregate SELECT over expenses to reference the spending predicate,
  carry a "// spending-exempt: <REASON>" marker, or be narrowly allowlisted.
REVERTED (option A): the barrier-dominance change in db_guard/scanner.py + policy_v2_evidence.py. It surfaced 115 unproven callable
groups and made db_access exit 2 (infra), cascading into known_good_state. Cross-cutting refactor, not part of this release.
I-01-1 remains a recorded HIGH. The revert restored the ORIGINAL repo behaviour - it did not weaken a guard that previously existed.

### S7 - the 17 real defects the new guards found, all fixed
- 11 PII sites (P-02-5): raw e.message replaced with the branch''s existing bounded reason string, keeping e.javaClass.name as the
  error class. Files: TransactionSideEffectPlanner.kt (10), PrivacySettingsRepositoryImpl.kt (1). Every event still written; no
  bounded code changed value.
- 6 spending queries (N-02-1 / N-02-4): 4 got the real predicate (getExpensesByCategory, getBusinessExpensesBetween,
  getBusinessExpensesBetweenFlow, getBusinessExpensesMissingReceipts - the last using aliased SPENDING_PREDICATE_E); 2 were
  correctly classified as non-spending suggestion readers with a "// spending-exempt: SUGGESTION" marker
  (getRecentExpensesForMerchant, getRecentExpensesWithCategoryForMerchant).

### Registration + count updates (additive, no baseline growth)
- config/guards/db_ownership_policy.yml: +2 entries for the S1 retention DAO writes (barrierMode helper, matching all 16 sibling
  RetentionModule entries). 454 -> 456.
- Guard self-test expectations updated to the new true counts (456 / 36 exemptions / SUGGESTION 3) with comments explaining why.
  No assertion logic relaxed - exact == preserved.

### Honest notes
1. The guard suite was RED for three runs while S6/S7 were in progress. That was correct: the guards were finding real defects. It
   is now green because the defects were fixed, not because the rules were loosened.
2. db_access and known_good_state exit-2 infra failures were a CASCADE from the barrier change, not independent problems.
3. One run was blocked by E_EXTERNAL_GRADLE_ACTIVE (another lane''s :app:check); waited it out rather than interfering.
4. False-positive risk accepted and recorded: the PII rule can flag a reason = e.message that is not persisted to a DB table.

### Slice status
S0-S7 COMPLETE. Z (candidate gate) next.
## Z - CANDIDATE GATE COMPLETE (2026-11-02)

### Ladder results (all serial, validation-runner only, worktree-stable)
| Step | Profile | Run | Result |
|---|---|---|---|
| 1 | targeted-unit-test (broad) | vr-20261101-234552-df5d05c4 | PASS - 1372 tests |
| 2 | unit-tests (FULL, fresh) | vr-20261102-001418-2739037e | PASS - 13344 tests, 0 failures, executed fresh (not cached) |
| 3 | app-check | vr-20261102-005418-c49088e7 | PASS - lint 0 errors; verifyDbAccessBoundaries / verifyNoIgnoredGrowth / verifyRoomSchemaSnapshots all PASS |
| 4 | static-guards | vr-20261101-225142-b8b7c77a | PASS - 27/27 |
| 5 | targeted re-run after reviewer fixes | vr-20261102-015846-fa6fe2d4 | PASS - 902 tests |

### Failures found and fixed DURING the gate (all real, none papered over)
1. DbGuardPolicyFixtureTest#manifest - ownership policy has exactly 454 entries - a FIFTH hardcoded count (Kotlin fixture) that the
   earlier Python-only update missed. Updated to 456 with a comment.
2. :app:lintDebug FAILED on MissingTranslation for the new S5 string. Fixed by TRANSLATING into values-el and values-es (real UTF-8,
   verified no BOM / no U+FFFD) - NOT by baselining or suppressing. Also closes a small part of the 80% Greek translation gap.

### Final strict review: PASS-WITH-NITS, zero blocking issues
The reviewer verified all 7 slices against the code (not the tests) and found two REAL gaps in our own fixes, both now closed:
1. S1 privacy gap: receipt_item_categorizations has FIVE text columns; we scrubbed three. alternativeCategoriesJson and
   userCorrectedCategoryName were left unscrubbed in BOTH retention and export. Both nullable, so both now set NULL.
2. S2 correctness gap: ConfidenceRouter boosts (PREVIOUS_APPROVAL_BOOST 1.2x, source trust 1.1x) could lift an ambiguous 0.70f
   back to 0.924 >= 0.85 and auto-accept - defeating the FL-02 fix. Fixed properly: ParsedTransaction now carries
   isAmountAmbiguous and the router caps an ambiguous amount at NEEDS_REVIEW regardless of boosts. Happy path unchanged.

### Remaining non-blocking nits (recorded, NOT fixed - deliberate)
- MonteCarloSpendingSimulator draws one Bernoulli for the whole remaining horizon rather than per week. P50/P90 unaffected on the
  verified fixture; per-week sampling is a refinement, not a defect.
- RefundLikelihoodDetector in the UI only sees the loaded page (50 rows), so a refund whose purchase is >50 rows back will not show
  the badge until the user scrolls. Cosmetic; the detector itself is correct.
- NON_AGGREGATE_SELECT_ALLOWLIST lives inline in the guard script rather than config/guards/. Acceptable; the rule is new and
  strictly stronger than base.
- Legacy user_corrections rows (createdAt = 0L) are wiped on the first retention run. INTENDED - that is the P-08-1 leak.

### Merge-target note (for the owner)
Our base is d9c55d4d (feature/ai-evolution), which already contains 1.0.3 (eefdaa6f) via merge 747d4828. origin/bug-fixes is at
eefdaa6f; the LOCAL bug-fixes branch is at 8e449c6f and has diverged from origin. Deciding how these fixes reach a release (merge
ai-evolution forward, or cherry-pick onto bug-fixes) is an owner decision - not done here.

### STATUS: branch is merge-ready pending owner decision. Nothing committed, nothing pushed.
## MERGE FORWARD (2026-11-02) - ai-evolution 179cb3e3 merged into our branch

### What happened
`179cb3e3` (feature/ai-evolution tip, 25 commits ahead of our base d9c55d4d) was merged into our branch as `cdf32c89`.
Those 25 commits include: app-lock S26b/S27b, G2-F1 live fingerprint invalidation, uf-quick-fixes Batch A, stress-forecast-actions.

### Conflict surface was small
Only 4 files were modified on BOTH sides (predicted 2, actual 4):
- `data/repository/ExpenseRepository.kt`
- `res/values/strings.xml`
- `res/values-el/strings.xml`
- `res/values-es/strings.xml`
`MainActivity.kt` was NOT a conflict (only we touched it). Git auto-merged all 4 with NO conflicts.

### Post-merge verification
- `git merge-base --is-ancestor 8a895772 HEAD` -> our fixes preserved.
- Spot-checked all 5 key fixes still present: RefundLikelihoodDetector, isAmountAmbiguous, MAX_USABLE_SIGMA,
  deferred carbon/price/negotiation, historicalDiscretionaryShare.
- strings.xml: our key (1) AND their stress_* keys (12) both present. el/es translations of our key intact,
  encoding still clean (no BOM, no U+FFFD, real Greek/Spanish).
- Validation `vr-20261102-062328-e67341a5`: PASS, 2759 tests, 0 failures, no semantic merge conflicts.

### Correction to an earlier claim in this handoff
An earlier note said local `bug-fixes` had "diverged" from origin. That was WRONG. Verified:
`8e449c6f` (local bug-fixes) IS an ancestor of `eefdaa6f` (origin/bug-fixes) - local is simply STALE, not diverged.
1.0.3 is on bug-fixes at eefdaa6f, merged into ai-evolution as 747d4828, and our branch contains all of it.

### Still outstanding after the merge
- The full Z ladder has NOT been re-run post-merge. Only the broad targeted filter (2759 tests) has. A full `unit-tests` +
  `app-check` + `static-guards` re-run is advisable before merge, because the merge touched strings.xml and ExpenseRepository.kt.
- `I-01-1` (barrier dominance, 115 groups) still deferred.
- `DR-05` (refund type) still a separate plan.
- Merge target for release still an owner decision.