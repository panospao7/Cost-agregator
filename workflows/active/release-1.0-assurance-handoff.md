# Release 1.0 assurance handoff

```text
Status: PENDING (Phase 0 PARTIAL; Phase 2 regression RG-01..RG-17 ALL DONE = PARTIAL (static only); stopped)
Last pin check (all RG agents, start+end): HEAD 279b7c92 (docs-only), app/ == ea446316, 0 untracked.

PHASE 2 SUMMARY (2026-10-15; independent STATIC verification only; no tests/validation run in this lane):
- All 7 original P0s VERIFIED-FIXED. Original P1s: P02-002, P03-002, P03-003, P04-005, P07-001, P07-002,
  P07-009, P08-001, P08-002, P10-001, I05-001 VERIFIED-FIXED; P03-001, P03-004, P08-003 only PARTIAL (residuals below).
- CONFIRMED P1 (static): RG11-H2 (swallowed nested Room transaction -> silent rollback with success; trigger NEEDS_RUNTIME),
  RG12-01 (receipt duplicate fingerprint drops different purchases when a field is unparsed).
- CONFIRMED P2 (12): RG01A-1, RG01-N1, RG02B-1, RG04A-1, RG04B-1, RG05A-1, RG06A-1 (release-blocker candidate:
  dashboard runway/Monte Carlo lost for all overall-budget users), RG06A-4, RG07A-1, RG12-03, RG16A-2, RG17B-1.
- Many P3s and unverified candidates (incl. V11-NEW-1 P2-candidate category merge stale refs, V16-NEW-1 P2/P3) are in
  the verification ledger. Some P2 closures (RG-08b, RG-09 consumers, RG-13, RG-14, RG-15a/c) are FIXED per discovery
  only, not independently re-verified.
- Process deviations recorded: three parallel workers once (2026-10-15); RG-14/RG-15a self-reported "claude-sonnet-5";
  one verifier extracted the Room AAR into /tmp to disassemble it (read-only for the repo).
Current source/artifact: local HEAD 84d819be6e288b82649a1c125fbcaa10f026d0d9 (pushed;
  origin/bug-fixes = 84d819be). The owner committed 14a2e6ce (perf runner), 73a22f44
  (agent guidance) and 84d819be (kit) DURING Phase 0. app/ == ea446316 (verified).
  Runner/build inputs are those of 14a2e6ce. No release artifact.
Current approved phase/scope: Phase 0 + coverage planning + ALL regression slices RG-01..RG-17 (done)
  + REMEDIATION PLANNING ONLY (approved 2026-10-15: release-planner Opus/xhigh read-only drafts; lead persists
  docs/releases/release-1.0-assurance/release-1.0-remediation-plan.md)
  + (2026-10-15 "i approve them") B-1 settlement, R0-1/R0-2 authoring + targeted runs, D-01..D-11/D-02/D-22 as
  planner-recommended, and the FIRST fix batch only (see approval log). NOT approved: other batches, guard/allowlist
  edits, connected-tests, Phase 1/3/4, commit/push.
Mode: delegated. User authorized named release subagent profiles for this lane
  on 2026-10-14. Limits: at most 2 read-only workers, 1 writer, 1 validation owner, no
  nested delegation. Historical Wave 5 and performance-lane no-subagent
  constraints are unchanged for those lanes.
Decisions / historical constraints: Wave 5 CLOSED under owner acceptance with
  independent remediation re-review WAIVED (original independent FAIL preserved).
  Release lane treats that delta as not independently reviewed (R-1).
Changed paths (this session, artifacts only):
  docs/releases/release-1.0-assurance/release-1.0-baseline.md (new)
  docs/releases/release-1.0-assurance/release-1.0-coverage-matrix.md (new)
  docs/releases/release-1.0-assurance/release-1.0-regression-ledger.md (new, seed)
  workflows/active/release-1.0-assurance-handoff.md (this file)
  RG-01: release-1.0-verification-ledger.md (new); regression ledger (CL-19, S2, Findings);
  coverage matrix (P-07, P-10, I-01 rows, status)
  RG-02: verification ledger (RG-02 section); regression ledger (CL-17, CL-18, A2, A6, Findings);
  coverage matrix (P-08 row)
  RG-04: verification ledger (RG-04 section); regression ledger (CL-29, A7, Findings);
  coverage matrix (E-04 row)
  RG-03: verification ledger (RG-03 section); regression ledger (CL-01/02/03, A1, Findings);
  coverage matrix (P-01 row)
  RG-05/RG-17: verification ledger (two sections); regression ledger (Wave 5 rows, CL-22,
  raw-money, ratchet, Findings); coverage matrix (I-04 row)
Confirmed/refuted/open findings (RG-01, independent STATIC verification only):
  CONFIRMED P2 RG01A-1 (new regression, 61a509c0): permanent CRITICAL lock on
    worker-schedule confirmation failure; only exit is clearing app data. NEEDS_RUNTIME for frequency.
  CONFIRMED P2 RG01-N1 (pre-existing): repeat restore of a foreign-device bundle silently
    loses receipt images; later image backups then fail.
  CONFIRMED P3 RG01A-2 (test debt); RECALIBRATED P3 RG01B-1 (orphaned restored file).
  CL-19 P07-003/004/005 (P0), P10-001 (P1), I01-001 and S2 R1-R4b/R6: VERIFIED-FIXED (static).
  RG-02 (static): CL-18 P07-001/P08-001 (P1), CL-17 P07-006/E04-001 (P0), A2, A6 VERIFIED-FIXED.
  CA-P08-003 still OPEN -> CONFIRMED P2 RG02B-1 (dedupe/review cloud providers persist raw
    parse-exception text, shown in Review UI; opt-in; NEEDS_RUNTIME on device).
  P3s: RG02A-1 (sanitizer policy gap, probe may raise to P2), RG02A-2, RG02B-2 (capture-toggle
    worker mapping; product decision), RG02B-3 (briefing vs proactive flag; product decision),
    RG02B-4, RG02B-5. Five unverified P3-level items are listed in the verification ledger.
  RG-04 (static): CL-29 E04-002 (P0) VERIFIED-FIXED on the live path; E04-003 and A7 VERIFIED-FIXED.
    CONFIRMED P2 RG04A-1 (pre-existing): UNEQUAL group split silently becomes equal when a
    member's joinedAt > expense date (backwards wall clock). CONFIRMED P2 RG04B-1 (pre-existing):
    UI-only "Settle" shows "settled up" while debts remain; app cannot record settlements.
    P3: RG04A-2 (LEGAL_PATHS drift), RG04A-3. REFUTED: RG04A-4 (double-tap duplicate).
  RG-03 (static): CL-01 P01-001 (P0) and P01-004, CL-02 P01-002, CL-03 P01-003/P01-005 and
    A1 x3 VERIFIED-FIXED. No new P0-P2 confirmed. P3: RG03A-1 (cold-start deferred drops,
    mislabeled codes), RG03-H1 (raw text persisted before filter under STORE_RAW; owner policy,
    P2 if ruled a breach), RG03-H2 (uncaught crash route), RG03B-1, RG03B-2 (borderline P2).
    Five unverified items, incl. V03B-NEW-A (stuck 5th attempt -> capture lost).
  ALL SEVEN original P0s are now independently verified fixed (static only; no test execution).
  RG-05 (static; first independent look at the Wave 5 remediation): F1, F2, F2-GOAL-DELETE, F8, F10
    VERIFIED-FIXED; v151 migration/chain/backup tier/grant VERIFIED; G1 removals are real fixes.
    CONFIRMED P2 RG05A-1 (new symptom): an accepted (or migration-seeded) sweep month keeps
    offering a dead Accept card. P3: RG05A-2, RG05B-1, RG05B-2. F3/F6/F7 not re-verified.
  RG-17 (static): CL-22 all 8 VERIFIED-FIXED; raw-money 83 -> PASS is a genuine source fix;
    cancellation ratchet is a clean shrink. CONFIRMED P2 RG17B-1 (T1 coverage gap: enqueue-failure
    transition only in an @Ignore'd test). FG-06 open: RG17A2-1 (cloud_payload matcher, P3 blocker),
    RG17A2-3 (53 orphan entries), 24 allowlist additions approved only in agent records, all 77
    entries expire 2027-01-01. RG17B-5 REFUTED (production VAT correct).
  Still no P0/P1 confirmed. Confirmed P2 total: 7.
  No P0/P1 currently confirmed. Unverified seeded claims for other clusters remain UNVERIFIED.
Review/guardian verdicts: none (verifiers are not guardians; no remediation exists to review).
Current vs historical validation: Wave 5 candidate-only runs (6x PASS, isolated
  clone, content-equivalent to ea446316) are baseline evidence only. No validation
  was run in this lane. Remote CI: no runs on bug-fixes since 2026-06-18.
```

## Approval log

- 2026-10-14: the user replied "proceed" to the Phase 0/coverage report. Recorded as approval
  of the coverage/dispatch plan and of slice RG-01 ONLY (discovery + independent
  refutation + persistence, then stop). It is NOT approval of remediation, validation,
  commit/push, later slices, or answers to B-1..B-3/H-1..H-4 (still open).
- RG-01 discovery dispatched: release-regression-auditor, requested Opus 5.5/xhigh.
- RG-01 execution log: attempt 1 hit the 45-turn limit with no report (nothing used). Split
  within the approved scope into RG-01a (mode/journal/startup) and RG-01b (bank barrier
  + S2 asset recovery), run in parallel with hard tool budgets. Then fresh verifiers V-01a
  and V-01b ran. V-01b raised N1, which went to a third fresh verifier V-01c. All self-reported
  Opus 5.5; effort not observable. No agent wrote files; git status stayed clean
  apart from lead artifacts.
- 2026-10-14: the user wrote "i approve the next slice". Interpreted conservatively as slice
  RG-02 ONLY (the first of the proposed pair). RG-04 is NOT approved. Dispatched RG-02a
  (CL-18, P07-006, A2, diagnostics-sink handoffs) and RG-02b (E04-001, A6, P08-003,
  worker self-gating) with release-regression-auditor at Opus 5.5/xhigh, read-only.
- 2026-10-14: the user approved "RG-04: CL-29 group split and settlement (P0 E04-002) plus A7".
  The message also quoted the lead's RG-03 sentence without naming RG-03 as approved, so RG-03
  remains NOT approved. CA-E-04-004 is a tax-currency finding and is reassigned to RG-09,
  outside RG-04. Dispatched RG-04a (E04-002 entry -> persistence, retained O-1) and RG-04b
  (E04-003 signs, A7 settlements in balances, settlement write path), Opus 5.5/xhigh, read-only.
- 2026-10-14: the user approved RG-03 by name (CL-01 P01-001 P0, P01-004; CL-02; CL-03; A1).
  Dispatched RG-03a (CL-01 deferred-capture consent + live filter body) and RG-03b (CL-02
  unique work, CL-03 worker retry/terminal + Wave 4 worker delta, A1 x3), Opus 5.5/xhigh, read-only.
- 2026-10-14: the user wrote "i approve the next slices" (plural) in reply to the proposed pair.
  Recorded as approval of RG-05 and RG-17 (the named pair), nothing else. F4/F5 stay in RG-06
  (NOT approved). Two-worker cap: RG-05a/05b first, then verifiers and RG-17a/17b in rounds.
- 2026-10-14: the user wrote "lets do all the regression slicces then in order" after being told 11
  regression slices remained (RG-06..RG-16). Recorded as approval of RG-06..RG-16, executed in numeric
  order, read-only discovery + fresh verification + ledger persistence only. NOT approved: Phase 1
  indexing, Phase 3/4, remediation, validation, commit/push. Routing: Opus/xhigh for T1 slices;
  Sonnet/high for the T2-heavy slices (RG-12 export part, RG-14, RG-15) per the coverage plan.
- PROCESS DEVIATION (2026-10-15): while RG-14 was still running, the lead dispatched RG-15a and RG-15b together,
  briefly running THREE read-only workers in parallel (cap is two). All three are read-only with disjoint scopes (no
  write conflict), but this exceeded the approved limit. No further dispatch until back under the cap. Disclosed to
  the user.
- 2026-10-15: the user wrote "i approve them" in reply to the five listed next steps. Recorded as:
  (1) B-1: owner confirms 14a2e6ce belongs on the release line; settling = one independent read-only review of the
  runner/build-input diff (reviewer-strict) + runner self-check (runner-protocol-tests, runner-smoke) by
  validation-runner. (2) D-02 = a (link/approval fails visibly); D-22 = narrow class-filter runner addition with
  independent review, recorded as a decision only — the runner edit is a separate tooling change not executed in this
  round (R0-1b is needed only before final acceptance). (3) R0-1 + R0-2 authoring by release-test-author and their
  targeted-unit-test runs. (4) D-01..D-11 = the planner's recommended options in the remediation plan section 4
  (D-06 limited to the named tests). (5) the first fix batch per plan section 5: R1 (+R1b only if R0-2 confirms the
  merge trigger) if R0 confirms; R2 if R0-1 refutes. Stop after that batch is reviewed and validated.
  NOT approved: other batches, G1/allowlist/guard edits, connected-tests, Phase 1/3/4, commit/push.
- 2026-10-15: the user replied "a" to the R1 static-guards blocker. Recorded as approval to delete the stale
  app/build/test-results/.freshness-stamp.json (Wave 5, 3e8377e4) and re-run static-guards only. Nothing else.
- 2026-10-15: after the lead offered three speed-up options, the user wrote "1 is good". Recorded as approval of
  batches R2, R3, R4, R5, R6 IN SEQUENCE, each with its own plan, fail-first test where planned, writer, independent
  review and targeted tests; stop on the first failed/blocked gate. Option 2 (shared full-suite runs) and option 3
  (lighter review) were NOT chosen: unit-tests + static-guards still run per batch. Decisions in force (planner
  recommendations already approved): D-04 (R2 rule A + duplicate notice + hide no-op buttons), D-05 a (R3), D-01 a
  and D-06 named tests (R4), D-07 accept retention (R6), D-21 P3 couplings included. NOT approved: commit/push, R7+,
  G1, guard/allowlist/policy edits, connected-tests, schema changes.
- 2026-10-15 (amendment): the user wrote "guards and full suite is better to be run after batches are done so we
  are quicker more efficient". Recorded: per batch R2..R6 = plan, fail-first test, writer, independent review(s),
  targeted-unit-test runs only (these compile the whole app + all unit tests). ONE unit-tests + ONE static-guards run
  after R6 (or earlier if the sequence stops). Each batch is PARTIAL (pending shared gate) until those pass; a shared
  failure is attributed per batch via targeted runs / debugger before any fix. Review depth unchanged.
- 2026-10-15: the user replied "approve" to the R4 blocker. Recorded as approval of the 4 additional pinned-test
  changes named in the R4 plan (ASCRT initialize_contains_asset_rollback_worker_resume_failure..., P7BugFixesTest
  worker_scheduling_failure_latches_critical_before_NORMAL_is_published, successful_registry_summary_without_active_
  work_latches_critical, confirmation_future_failure_latches_critical_without_resetting_stop_flag; renames allowed).
  NOT recorded as approval of D-23/R4b (DBRI lockCriticalForFailure path) - still open.
- 2026-10-15: the user asked for recommendations, then wrote "approve then". Recorded as approval of the lead's
  recommendations: HD-1 (delete the resolved HybridDedupeJudgeService entry from config/baselines/cancellation.json
  when R6 resolves it), HD-2 (bound e.message in JudgePendingReviewDuplicateUseCase :130/:135 and
  ExplainPendingReviewUseCase :130), HD-3 (include OnDeviceDedupeJudgeService in R6); D-23 = PLANNING PASS ONLY for
  R4b (no implementation approved); D-05b deferred.
- 2026-10-15: the user replied "i approve" to the R4b recommendations. Recorded: D-23 implementation APPROVED for
  restore + reset pre-destructive abort sites only (helper exitAfterPreDestructiveAbort in DBRI; isRestoreDurabilityFailure
  unchanged as fallback); neutral restart-screen wording + es/el translations APPROVED; checkRestoreJournal unchanged;
  debug-only import and cancel-cleanup sites stay CRITICAL (documented). Order: after R5 iteration and R6.
- 2026-10-15: the user wrote "do all your recommendations . first with the commit". Recorded: (1) commit done on NEW
  branch release-1.0-remediation (bug-fixes untouched; no push): d908405d fixes, b9e98347 records; author identity taken
  from repo history via per-command -c (git config untouched). New app pin: app/ == d908405d. (2) Approved to execute,
  with the same per-batch gates (plan, fail-first where planned, writer, independent review(s), targeted tests; one shared
  unit-tests + static-guards at the end; stop on first failed/blocked gate): R6b privacy sweep (same-class e.message leaks
  found by the R6 privacy review), R7..R13, and G1 per the planner recommendations D-08, D-09, D-10 (WI-6 reject as a
  separate batch after R11), D-11 (relabel), D-19 (fix cloud_payload matcher, remove 53 orphan entries, record owner
  confirmation of the 24 agent-approved additions, plan the 2027-01-01 expiry), D-21 couplings, D-06 for the named R8
  pinned test, and the R7 receipt_link_allowlist rationale correction. Guard/allowlist edits are limited to those named;
  any other guard/baseline/policy/schema change still stops for approval. Stale cancellation exemptions cleanup: NOT
  explicitly recommended earlier -> will be proposed, not done.
- 2026-10-15: the user wrote "follow your recommendations" to the four pending items. Recorded as approved: (1) R7
  guard-policy edit: config/guards/db_ownership_policy.yml planner entry operation update -> updateMatchSuggestion
  (+reason text) and the 2 matching rows in db_ownership_policy.signatures.accounting.json, 1:1, no growth (stop if
  regeneration touches other entries); (2) HD-R6b-1: CategorizeReceiptItemsUseCase CE -> reset ANALYZING to PENDING in
  NonCancellable, rethrow, no FAILED artifact; (3) HD-R6b-2: Home briefing shows bounded "UNKNOWN_ERROR Route: ..."
  (localized follow-up later); (4) D-06 for SavingsSweepAcceptanceCoordinatorTest.recomputed_sweep_is_rejected_in_same_
  month_even_after_plan_cleanup_but_next_month_is_allowed (:479 expectation -> producer NoRecommendation). The R8 row's
  originally named VM test needs no change.
- PROCESS DEVIATION (2026-10-15, second): the lead dispatched the R6b privacy review and the R12/R13/G1 planner while
  the R6b strict review was still running -> three read-only workers in flight (cap two). All read-only, no write
  conflict. Disclosed to the user; no further read-only dispatch until back under the cap.
- 2026-10-15: the user wrote "do your recommendations" to the six-item list. Recorded as approved: (1) H-R9-1 = B
  (goal-only partial flag; Monte Carlo unaffected; re-plan required; shared model + ComputeDashboardWidgetsUseCase gate
  edit allowed; any money-guard allowlist/baseline need -> stop); (2) R13b: DAO SQL fix so the enqueue-failure
  FAILED_FINAL transition sets terminalAt (no schema change; stop if a migration is needed); (3) G1 cloud_payload entry:
  rewrite to the exact path if it still suppresses a real finding, else remove; (4) G1: lead writes a dated owner
  DECISION line for the 24 additions citing the 2026-10-15 approvals (no invented handle); (5) stale exemption cleanup
  for files fixed by R6/R6b (cancellation_allowlist.yml entries + CancellationSafetyArchitectureGuardTest
  KNOWN_VIOLATIONS), each proven unused by a targeted run first; (6) DEFER guard hardening (Kotlin guard expiry
  enforcement, basename matching, stale-entry check, pii_logging e.message-in-results extension, cancellation scanner
  `suspend operator fun invoke` gap) to a post-1.0 guard batch; owner review on 2026-12-01 before the 2027-01-01 expiry.
- 2026-10-15: the user wrote "do your reccomendation" to R7-REGEN. Recorded: approved to regenerate the DB-ownership
  policy artifacts with the repo's official tools for the single ReceiptSideEffectPlanner update -> updateMatchSuggestion
  rename; diff must be limited to that entry + derived hashes/counts, else stop. Lead first obtains the sanctioned
  post-promotion amendment procedure (architecture-guardian, read-only) before running anything.
- 2026-10-15: the user wrote "i aprrove them" to (a) R7-GT: add the planner row to the closed move set pinned in
  scripts/test_migrate_db_policy_seed_rows.py (_after_reviewed_seed_signature_move, 10 -> 11; adds a pin) and (b) doing
  R7 policy regeneration LAST on a tree where only R7 is uncommitted (sanctioned route per GR-14 Pattern E: edit
  docs/ci/db-findings/GR-08-seeds.yml + config/guards/db_ownership_policy.yml one line each, scratch preview to build/,
  migrate_db_policy_signatures.py --generate --seed-rows ..., then --verify; promotion.json untouched; every
  sourceMutations change must be accounted for, else stop).
- Observed: artifacts were committed by the owner during the run (279b7c92, CRLF
  normalization). The owner marked H-4 resolved (commits pushed).

## Next action (requires human approval)

STATUS 2026-10-15 (end of R2..R6 sequence): R1, R2, R3, R4, R4b, R5, R6 all PASS at batch gate on the UNCOMMITTED
worktree (HEAD 279b7c92; 59 changed/untracked paths). Shared gate: unit-tests vr-20261015-135135-1be3c8c9 (8009/7733/0/276)
+ static-guards vr-20261015-140235-da3782f3 (25/25; freshness row SKIP). Fixed: P1 RG11-H2, P1 RG12-01, P2 RG06A-1/RG06A-4,
RG01A-1 (+D-23), RG01-N1, RG02B-1 (+RG02B-4, HD-2/HD-3). Evidence: verification ledger tail. Remaining remediation batches
(not approved): R7 RG07A-1, R8 RG05A-1, R9 RG16A-2, R10 RG12-03, R11 RG04A-1, R12 RG04B-1, R13 RG17B-1, G1; candidate R6b
privacy sweep. Decisions needed: commit of the validated batches; next batches; R6b; stale exemptions cleanup.

R1 (RG11-H2) BATCH STATUS 2026-10-15: PASS after owner option (a): stale stamp deleted, static-guards
vr-20261015-091626-65ab1540 PASS 25/25 (freshness row SKIP). Uncommitted; commit NOT approved. Superseded text: PARTIAL. Uncommitted worktree diff (4 main files, LEGAL_PATHS.md, 1 modified + 3 new
tests). Reviews ACCEPT-WITH-NOTES; targeted tests + unit-tests PASS; static-guards FAIL only on known_good_state row
test_result_freshness (stale Wave 5 stamp at 3e8377e4 in app/build/test-results/.freshness-stamp.json). BLOCKED-ON-HUMAN:
choose (a) delete stale stamp + re-run static-guards [lead recommendation], (b) write stamp after a committed-tree
unit-test run, or (c) waive the optional row. Also open: commit decision for R1 (not approved); R0-1b device twin (D-22
runner addition not implemented); follow-ups HZ-02 email path :1238-1245, cloud category id validation, FG-16 note N5,
worker code folding, R1b deferred (P3). Next batch per plan: R2 (RG12-01) - NOT approved.


Lesson for dispatch: T1 slices must be about 8-12 primary files per auditor with an explicit
tool budget, or the 45-turn profile limit is hit before reporting.

Recommended next (choose one):
(Phase 2 regression is complete. Superseded options removed.)
REMEDIATION PLAN PERSISTED (2026-10-15): docs/releases/release-1.0-assurance/release-1.0-remediation-plan.md
(release-planner draft, self-reported Opus 5.5; not independently reviewed; nothing approved for execution).
Next human steps, in order: (a) settle B-1 (perf runner commit) so validation is possible; (b) decide D-02 and D-22 and
approve R0-1 + R0-2 authoring (release-test-author) and their targeted-unit-test runs (validation-runner) to decide
whether RG11-H2 is a real P1; (c) answer D-01..D-11 for the batches; (d) approve the first batch (R1 if R0 confirms).
1. RECOMMENDED: remediation planning. Have release-planner (Opus/xhigh, read-only) produce
   release-1.0-remediation-plan.md batching the 2 P1s (RG11-H2, RG12-01) and 12 P2s, plus the owner
   policy decisions, plus runtime deciding tests (RG11-H2 Room nested-transaction test first: it
   decides whether RG11-H2 is a real P1). Planning only; no code. Remediation batches, strict review
   and validation each need separate approval, and B-1 must be settled before any validation.
2. Or continue coverage (Phase 1 indexing IX-01/02, then Phase 3 horizontal HZ-01..08), which will
   also re-verify the discovery-only closures noted above.
3. Or first decide remediation for the confirmed findings and the product/policy decisions (RG02B-2,
   RG02B-3, RG04B-1 "Settle" scope, CL-29 WI-6 reject-vs-fallback, RG03-H1, OBS-REVOKE,
   FG-06 items: RG17A2-1 matcher, 53 orphan entries, agent-only approval of 24 allowlist
   additions, 2027-01-01 expiry of all 77 entries). Fixes require a
   separate approved 05-remediation batch, strict review and runner validation, and B-1
   must be settled before validation:
   - RG01A-1: keep the write-blocked retryable state (NORMAL+resumePending) on
     WorkerRescheduleException instead of CRITICAL, or define the reviewed recovery
     action the CL-19 spec requires.
   - RG01-N1: adopt a proof-matching existing restored_N and surface receiptAssetWarnings.
   - RG02B-1: map ParseError/Unknown to fixed codes in CloudDedupeJudge/CloudReviewExplanation
     (and hybrid/on-device), mirroring CloudDashboardBriefingService; device test for org.json.
   - RG04A-1: date-aware Repo preflight (active members with joinedAt <= date), fail closed;
     changing the coordinator fallback needs WI-6 scope approval.
   - RG04B-1: relabel/remove the UI-only "Settle" for 1.0, or plan a legal settlement writer.
   - RG05A-1: VM treats SAVINGS_SWEEP_MONTH_ALREADY_ACCEPTED as terminal (clear card), or the
     producer declines a consumed month via a read-only marker check.
   - RG17B-1: add a real-Room test of markEnqueueFailed / enqueue-failure transition (no MockK
     suspend-extension mocking); do not un-ignore the hanging test without the RP-21 harness.
   A release-planner (Opus/xhigh, read-only) pass can turn these into one remediation plan
   (release-1.0-remediation-plan.md) once the owner policy decisions are given. RG-03 policy
   questions to add: RG03-H1 (raw text before filtering under STORE_RAW) and OBS-REVOKE
   (purge on consent revoke).
   Optional deciding runtime tests (RG01A-1 frequency, N1 repeat restore) need test
   authoring + targeted-unit-test runs, each separately approved.
Stop rule unchanged: no remediation, validation, commit/push or later slices without approval.

## Human prerequisites (from baseline dossier)

- B-1: performance lane (gradle.properties, scripts/validation-runner.ps1,
  test-shards.json) is now committed locally in 14a2e6ce. Owner must confirm it
  belongs on the release line and state the review status of its later changes.
  A runner self-check at the pin comes before any release queue. Not needed for static slices.
- H-4: RESOLVED — the three Phase 0 commits were owner-requested and are pushed on origin/bug-fixes.
- B-2: no runner release APK/AAB profile; CI release-check not running. Approve
  a narrow profile or restore CI before the artifact gate.
- B-3: production signing/distribution process (release is debug-signed).
- H-1: shipped 1.0 feature scope (bank sync appears debug-only in code; demo tax
  and static negotiation providers bound in release; debug screen reachability).
- H-2: supported upgrade/backup policy and device/API matrix, stabilization length.
- H-3: PUBLIC repo tracks an expense .db backup, 28 receipt photos and session
  transcripts. Owner decides whether they contain real personal data. This lane
  does not read them.

## Resume protocol

1. Read this file, then `docs/prompts/release-1.0-assurance/README.md`.
2. Verify `git rev-parse HEAD` (last recorded 84d819be) and that app/ is unchanged
   vs ea446316. If app/ changed, re-run Phase 0 for the delta before continuing.
   If only HEAD moved, record the new commits and their paths.
3. Read `docs/releases/release-1.0-assurance/release-1.0-baseline.md` and
   `release-1.0-coverage-matrix.md` (section 6 queue). Do not exceed the recorded
   approved scope.
4. Kit drift: README/earlier handoff name `money-w5-savings-b8-20261012-handoff.md`
   as the latest Wave 5 packet. The actual latest is
   `workflows/active/money-w5-independent-remediation-20261013-handoff.md`.

## Setup history (2026-10-13)

The kit and profiles were authored at HEAD 4137afdf on mixed Wave 5 WIP. No phase
or validation was run at setup, and actual profile loading was NOT RUN.
In this session (2026-10-14) two `scout` read-only indexing tasks ran. The profile
pin requests Haiku 5.5/medium, but the actual model/effort was not independently
confirmed from /tasks. Their reports are summarized
in the coverage matrix and regression ledger seed.
