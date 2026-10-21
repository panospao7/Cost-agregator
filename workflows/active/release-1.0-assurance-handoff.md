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
- 2026-10-15: the user chose option (a): build the D-22 device-test runner addition and start final acceptance.
  Recorded: FINAL CANDIDATE FREEZE = branch release-1.0-remediation @ 2ec50671 (clean tree). Any app/ change after this
  invalidates final evidence. Next: Phase 6 runtime/release evidence PLAN (read-only) -> owner approves device matrix +
  runner additions (D-22 connected-test class filter; B-2 release artifact profile) -> strict review -> execution via
  validation-runner -> Phase 7 independent final review. Environment: no device attached; AVD wave5-api35 available.
- 2026-10-15: the user wrote "do 1 2 and 7 and for the rest directly install apk seems good". Recorded: HD-P6-1 = final
  candidate C* = 2ec50671 + one prep commit (runner D-22 connected-test-class + -DeviceSerial, B-2 assemble-release +
  release-artifact-evidence profiles, protocol tests, scripts/ci/release_artifact_evidence.py, R0-1b and V02B-N4 device
  tests, handoff), with proof that shipped inputs are unchanged; B-2 approved; item 7 = MockWebServer
  androidTestImplementation allowed ONLY if an OkHttp interceptor fake cannot work; B-3 = production distribution by
  directly installed (sideloaded) APK signed with an owner-held release key (key never handled by agents; key continuity
  for updates). STILL OPEN (owner): device matrix / AVD creation (D1 API 26, D2 API 33) and physical device; H-1 scope;
  H-2 upgrade/old-backup support; H-3 public-repo personal data; performance bounds.
- 2026-10-15: the user wrote "i approve yes and i havbe aspare phone". Recorded: R14 (RT-C1 Android 15 dataSync FGS
  onTimeout) and R15 (RT-C3 Gson/R8 keep rules) APPROVED as release blockers to include in the final candidate C*;
  download system image + create AVD for API 26 (D1) APPROVED (API 33 not explicitly approved); spare physical phone
  available for release smoke + beta (P1). AVD wave5-api35 = Pixel 6 API 35 google_apis (D3), used in Wave 5 as
  emulator-5554.
- 2026-10-15: FINAL CANDIDATE C* = the prep commit on release-1.0-remediation containing runner D-22/B-2 additions,
  R0-1b + V02B-N4 device tests, R14 (Android 15 dataSync FGS timeout) and R15 (Gson R8 keep rules) - supersedes the
  2ec50671 freeze; any later fingerprinted change invalidates final evidence.
- 2026-10-15: the user wrote "I want phase 3 and phase 4 to be done also." Recorded: Phase 3 horizontal audit (HZ-*) and
  Phase 4 vertical journey audit (VJ-*) APPROVED, read-only discovery + independent refutation per batch, max 2 read-only
  workers, against C* 53824bcd; runs in parallel with final-gate validation (no edits). Confirmed findings need separate
  remediation approval; any fix changes C* and re-triggers affected final gates.
- 2026-10-16: Phase 3 + Phase 4 COMPLETE (static, all slices PARTIAL; residual cells listed). Records:
  docs/releases/release-1.0-assurance/release-1.0-phase3-4-findings-ledger.md (evidence) and
  release-1.0-phase3-4-consolidated.md (P1/P2 list, proposed batches R16..R24). Confirmed P1: comma decimal (R16),
  no listener-access status (R17). Final gates part 3 on 0a9cab1c were blocked by runner dedup and are now moot.
- 2026-10-16: the user wrote "i approve them all" to the lead's list. Recorded as APPROVED: (1) runner dedup fix
  (option 2: duplicate detection also requires the same git_revision); (2) fix batches R16..R24 (full set, including
  R20 and R24; P3s deferred to 1.0.x unless bundled); (3) H-1: HIDE Tax, Investment and Challenges for 1.0 (R19), bill
  reminders treated as a headline feature (R21 V-C1 + V-C2 required); (5) exchange-rate download gets its own
  network setting, separate from cloud-AI consent (R23 part 1); (6) reboot of the wave5-api35 emulator (synthetic data
  only) for the V-I-6 boot check. NOT answerable by approval, still OPEN: H-2 (any shipped build with DB schema < 145?),
  R23 part 2 (backups include receipt images vs disclose the omission), H-3. Execution: one batch at a time, plan ->
  coder -> targeted tests -> strict review -> batch gate; consolidated final-gate cycle on the new C* afterwards.
- 2026-10-16: runner dedup fix (git_revision must match; UNKNOWN never dedups) PASS: runner-protocol-tests
  vr-20261016-061023-9cfa9263, 340 passed / 0 failed (330 + 10 new), fingerprint start = end, rev 0a9cab1c + dirty tree.
- 2026-10-16: the user answered: "for h2 the answer is yes . for backups you can exclude images for now . dont worry about
  h3". Recorded: H-2 = YES, a build with DB schema < 145 has shipped -> HZ-02 F3 (open of a < v145 database = Room
  IllegalStateException, DatabaseModule :36 uncaught, rescue disabled -> crash loop) is now P1, and HZ-02 F2 (backups
  v6..v144 rejected late with a generic error) is raised for planning as batch R25 (upgrade path); exact shipped
  version(s) still to establish. R23 part 2 = keep excluding receipt images from backups and DISCLOSE it (backup screen
  copy + fix docs/reference/domain-quick-reference.md :48). H-3 = owner accepts, no action.
- 2026-10-16: R16/R17 plan recorded as remediation plan section 21 (release-planner). Lead decisions: R16c (remaining
  toDoubleOrNull amount fields) is inside the approved R16 scope; non-dismissable "capture inactive" Home card accepted;
  single writer, order R16-S1 -> R16-S2 -> R17 -> R16c.
- Observed: artifacts were committed by the owner during the run (279b7c92, CRLF
  normalization). The owner marked H-4 resolved (commits pushed).

## Next action (requires human approval)

STATUS 2026-10-15 (end of second sequence): ALL remediation batches R1..R13 + R4b, R6b, R13b, G1 PASS at batch gate on
branch release-1.0-remediation (commits d908405d, b9e98347, fd6eb490, + R7 commit). Final static-guards
vr-20261015-181743-1773c8b7 25/25; unit-tests vr-20261015-174137-443d2e41 8067/0 failed. Remaining before release
acceptance: final-candidate app-check + lint, connected-tests (D-22 runner addition / device), independent final
review (release-final-reviewer), B-2 release artifact profile, B-3 signing, H-1 scope, H-3 public repo data. Open
follow-ups listed in the verification ledger (guard hardening deferred to 2026-12-01 owner review; data repair for
stuck FAILED_FINAL rows; D-05b; D-10 WI-6; orphan export file; residual stale full-row write; etc.).

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

- 2026-10-16: release work moved to worktree C:\Users\panos\Desktop\Cost-agregator-release (branch release-1.0-wt from 2ca932a9)
  because the owner's loan-installments feature session writes into the main folder (two STALE_RESULT runs). Owner phone (62d5f6f9):
  live DB = final v145 (identity 6f54382e...), so R25 part 3 dropped; signing option 1 (owner release key + backup/restore). Safety
  copy of the owner DB at %USERPROFILE%\Desktop\expense-db-copy-2026-10-16 (integrity ok). Real-device crashes: backup SQLITE_BUSY
  (root cause DBRI :678 locking_mode EXCLUSIVE -> R26 P1, approved) and dataSync FGS timeout (fixed by R14).
- 2026-10-16: owner approvals: R21 worker, R23 default OFF, R25 plaintext untouched-file export, R26 core + R26-V + R26-D (Q-V1 yes,
  lost-count yes, NOTIFICATION_CAPTURE only), G2 + G3 (+ COVERED_BY_CALLER category for 3 workers, dropped mirror events during
  maintenance, DBRI privacy checks before :391, warranty reclassification, bank stub debug-only owner). Plan sections 22-25 recorded.
  Deadline: G2 before 2026-10-31 (WriteBarrierArchitectureGuardTest expiry). R27 (non-worker in-flight tx vs enterAndDrain) queued.
- 2026-10-16: R16 PASS at batch gate (worktree, fingerprint 57d1c5dc..., rev 2ca932a9 + diff): targeted runs listed in the findings
  ledger; app-check vr-20261016-074919-4c734c1d PASS (testDebugUnitTest 8164/0 failed/276 skipped, testReleaseUnitTest 8164/0/277,
  lint no new errors). Reviews: S1 + S2 reviewer-strict ACCEPT-WITH-NOTES (findings fixed). Debug iterations: over-cap statement row
  skip; deterministic TimeoutCancellationException in 3 tests. Committed on release-1.0-wt.
- 2026-10-16: R26 core PASS at batch gate (worktree, fingerprint ce730c77..., rev c3fc8947 + diff): backup lock fix (locking_mode
  pragma removed, read-only snapshot, recorder fallback, VM catch), audit-write failure fails closed with PRIVACY_AUDIT_WRITE_FAILED
  (workers retry), batched purges, stale runCostBackupExport/writableDatabase structural exception removed (owner-approved guard
  tightening, counts 65->64 / 60->59). Root cause confirmed on emulator API 35 (connected BackupWalLockInstrumentedTest 3/3,
  vr-20261016-085112-48dff99a). Final gate: DbGuardPolicyFixtureTest vr-20261016-095638-3b4af43f, static-guards 25/25
  vr-20261016-095903-03674f6b, app-check vr-20261016-102123-064fc003 (8185/0 failed/276 and 277 skipped; lint via baseline).
  Reviews: reviewer-strict ACCEPT-WITH-NOTES (F1-F4 addressed); architecture-guardian on the guard edit (see findings ledger).
  R26 reached the 2-debug-iteration cap. Committed on release-1.0-wt. Next: G2 (deadline 2026-10-31).
  Branch note: release-1.0-remediation in the main folder gained owner commits e5a2f0e7 and 7da9fcae, so the final move to
  release-1.0-wt is not a fast-forward any more; the owner decides merge vs rebase at the end.
- 2026-10-16: G2 PASS at batch gate (fingerprint ab8993a6..., rev 88382c4c + diff): 8 U-004 WriteBarrier allowlist entries removed
  (6 already fixed + RoomDiagnosticEventWriter and RoomOperationRunRecorder now check the barrier); backup export finalizes its run
  after maintenance exit (DeferredRunTerminal); blocked finalize -> bounded safe-sink terminal record; owner-approved cancellation
  baseline shrink (OperationRunRecorder G-CANCEL-03 3 -> 1). Gate: static-guards 25/25 vr-20261016-115803-6d009c7e, app-check
  vr-20261016-122027-364680e6 (0 failed), legacy-tests vr-20261016-125302-b26c6ac6 (253/0). Reviews: architecture-guardian REJECT
  then ACCEPT-WITH-NOTES. Debug cap reached (compile fix; baseline shrink by owner decision). Committed on release-1.0-wt.
  Open: restore/import/reset run rows stay RUNNING (fixed in G3 S2); legacy-tests does not exercise HANG-001..003, so the
  2026-11-01 hanging-ledger review deadline is still open. Next: G3 (plan section 26; owner decisions Q-G3-1/2 recorded).
- 2026-10-16: G3 part 1 (S1-S3) PASS at batch gate (fingerprint f63a1e57..., rev cd3b8d71 + diff). S1: write barrier in
  ReceiptInsertResolver, DebugExpenseAuditWriter, Room receipt/transaction lifecycle event writers. S2: PrivacyAuditLoggerImpl
  barrier; backup export privacy checks before enterAndDrain; restore/reset/legacy-import finalize their run after maintenance
  exit (G2b). S3: all remaining U-004 allowlist entries removed; COVERED_BY_CALLER guard category (cap 3; WORKER_CHECKPOINT,
  SOLE_CALLER_GUARDED) with fixtures; WarrantyExpirationWorker and NotificationIntakeWorker purge checkpoints. Debug iteration 1:
  6 db_ownership_policy rows synced to the new signatures (identity only, owner pre-approval Q-G3-1). Gate: registered-guard
  db_access vr-20261016-141844-d113db06, known_good_state vr-20261016-142126-4a324b97 (freshness stamp SKIP), static-guards 25/25
  vr-20261016-142437-9e43c10b, app-check vr-20261016-144626-142c1426 (8246 run / 0 failed). Reviews: reviewer-strict
  ACCEPT-WITH-NOTES (S1, S2), architecture-guardian ACCEPT-WITH-NOTES (S3). Committed on release-1.0-wt.
  Owner approved R28 (retention also purges encrypted intake payloads) and HL-1 (hanging-ledger owner/evidence/review_by
  2026-12-01). Correction: G2 legacy-tests run did exercise HANG-001..003 (no hang). Next: batch R28+HL-1, then G3 part 2
  (S4-S6, before 2026-11-20).
- 2026-10-16: R28+HL-1 PASS at batch gate (fingerprint a5646bd5..., rev e6727b2b + diff). R28: the notification_intake retention
  sweep now also clears the encrypted transient payload (selection capturedAt < cutoff AND (rawPayloadPurgedAt IS NULL OR
  transientPayloadCiphertext IS NOT NULL), status-agnostic, -> purgeAllPayload); active db_ownership_policy row operation swapped
  (same site/accessor/barrier; generated candidate/seed files untouched, follow-up F-R28-a). HL-1: HANG-001..003 owner @panospao7,
  review_by 2026-12-01, evidence += clean legacy run; status stays suspected until a clean run on the final candidate. Gate: legacy-tests
  vr-20261016-154702-3e58036a (253/0, no hang), static-guards 25/25 vr-20261016-154758-9c85e96c, app-check vr-20261016-160928-351117e4
  (8253 run / 0 failed). Review: privacy-security-guardian ACCEPT-WITH-NOTES (LOW fixture values fixed). Committed on release-1.0-wt.
  Follow-ups: F-R28-a..d and R28-X (export copy keeps intake ciphertext) in the findings ledger. Next: G3 part 2 (S4-S6, before 2026-11-20).
- 2026-10-16: G3 part 2 (S4-S6) PASS at batch gate (fingerprint 4a1e5647..., rev 8671b844 + diff). S4: NotificationRepository.
  deleteAllNotifications and ReviewQueueRepository.approveReview SOURCE_LINKED now write through DomainTransactionRunner +
  TransactionLifecycleEventWriter (no direct TransactionEventDao.insert); event_writers baseline and 2 active db_ownership_policy rows
  removed (count pins 429 -> 427). S5: DirectEventDaoInsertGuardTest has no LEGACY_REPOSITORY entries left; Warranty -> WARRANTY_OWNER and
  BankApiIntegration -> DEBUG_ONLY (expiry 2027-01-01, capped, category-locked, stale-entry checked); WarrantyTrackerRepository rethrows
  DatabaseAccessBlockedException from the AID-9 audit (no id for an unsaved warranty) and barrier-checks the discard audit. Gate:
  app-check vr-20261016-184020-8290b11e (8261/0), static-guards 25/25 vr-20261016-191101-00e18025, legacy-tests vr-20261016-193438-d072e47e.
  Reviews: reviewer-strict (S4) and architecture-guardian (S5) ACCEPT-WITH-NOTES. Debug cap reached (count pins). The 2026-11-20
  DirectEventDao expiry is resolved. New finding R29 (warranty in-transaction audit failure silently rolls back while returning an id)
  awaits owner. Speed-up protocol approved by owner (grouped gates; second worktree release-1.0-wt-b). Next: integrate B (R26-D + R26-V).
- 2026-10-17: R26-D + R26-V group PASS (worktree B, fingerprint 499d0979..., rev 6f706c29 + diff). R26-D: repeated ALLOWED
  NOTIFICATION_CAPTURE privacy-audit decisions coalesce to one row per 60-minute window (coalescedCount / coalescedFlush /
  coalescedSinceMs added after sanitizing; DENIED/FAIL_CLOSED/NOT_APPLICABLE and all other capabilities always individual rows; barrier
  checked before and inside the lock; counts are a lower bound across process death). R26-V: routine listener/refresh RECEIVED and
  INFO filter/dedupe terminal drops coalesce into hourly per-stage summaries (metadata count only); problems always written with their
  RECEIVED row; CancellationException now rethrown in NotificationDiagnosticEmitter (G-CANCEL-01 baseline + CancellationSafety
  allowlist entry removed). StatisticsUtilsStressTest timing flake made deterministic (warm-up). Gate: app-check
  vr-20261016-204120-e7c93afc (8296/0), static-guards 25/25 vr-20261016-212445-c9eeb61f, legacy-tests vr-20261016-214738-79bca026.
  Reviews: privacy-security-guardian (R26-D) and reviewer-strict (R26-V) ACCEPT-WITH-NOTES, notes fixed. Owner: standing approval for
  lead recommendations (limits in ledger); R27 decisions Q-R27-1..5 and R29 approved. Next: R29 gate (worktree A), R27 (worktree B), R21.
- 2026-10-17: R29 PASS (fingerprint 0113c26c..., rev e008470c + diff). WarrantyTrackerRepository in-transaction event/audit writes fail
  closed (addWarranty, addWarrantyIgnoreConflicts incl. AID-9, rejectAutoDetectedWarranty, markWarrantyAsClaimed): a failed nested
  transaction no longer silently rolls back while returning success; background callers map it to bounded failure/retry;
  WarrantyTrackerViewModel catches mutation failures (CancellationException rethrown) and shows a bounded string (en/el/es) via a new
  snackbar; real-Room WarrantyTrackerRepositoryTransactionTest. Gate: static-guards 25/25 vr-20261016-214931-8093c15b, app-check
  vr-20261016-221153-1c7cc08b (8316/0), legacy-tests vr-20261016-224514-bf121686. Review: reviewer-strict ACCEPT-WITH-NOTES (fixed).
  Next: R27 group (worktree B) incl. R30 (pipeline auto-accept event fail-closed).
- 2026-10-17: R27 group PASS (worktree B, fingerprint eeb10035..., rev 91f775b0 + diff). DatabaseWriteBarrier.runWrite counts in-flight
  writes; backup export, safety backup, restore, import and reset wait (10 s, fail closed, bounded TIMEOUT) and never stay in
  maintenance on cancel; main non-worker writers moved to runWrite (TransactionLifecycleCoordinator, ReceiptLifecycleCoordinator,
  NotificationRepository, pipeline auto-accept, OperationRunRecorder, RoomDiagnosticEventWriter, PrivacyAuditLoggerImpl,
  BankStatementLifecycleProcessor incl. finalizers and skip rows); new R27RunWriteCoverageGuardTest pins them; automatic app relaunch
  after an in-process successful restore (RestoreRestartSignal; banner button wired; dismissRestartRequired removed); R30
  pipeline auto-accept event failure now fails closed. Known limitations documented in docs/backup-restore-barrier-contract.md.
  Gate: static-guards 25/25 vr-20261016-230924-4eac6ffd, app-check vr-20261016-233434-13cec8e4 (8361/0), legacy-tests
  vr-20261017-000241-1066bde0 (262/0). Reviews: room-migration-guardian (S1), reviewer-strict (S2/S4), architecture-guardian
  (S3/S4b/S5/R30) ACCEPT-WITH-NOTES, notes fixed. Next: R21 gate (worktree A), then R23, R25, R17/R19, R18, R20/R22b/R24/R16c.
- 2026-10-17: R21 PASS (fingerprint 70cada5f..., rev f631d1be + diff). Recurring bills: "Mark paid" creates exactly one expense
  through TransactionLifecycleCoordinator for the occurrence on the shown due day (dated on that due day, rule currency/amount/category,
  STRICT idempotency key), links it to that occurrence, runs the full post-commit batch once, and moves nextDate strictly forward;
  "Already recorded" skips the occurrence and cancels its planned row and reminders; IRREGULAR rules do not offer it; card/dialog show
  the rule's currency; bounded translated messages. New guarded RecurringHorizonWorker tops up the 12-month horizon daily (catch-up <= 12
  months, no past-due reminders); BillReminderWorker collapses stale due reminders first; materializer never links an expense twice;
  month-end-aware anchor; WorkManager one-shot KEEP + DailyBriefing APPEND_OR_REPLACE. Policy +3 rows / 3 re-keyed (pins 427 -> 430).
  Gate: static-guards 25/25 vr-20261017-002912-c9d2fbae, app-check vr-20261017-004723-9b7a4b4e (8438/0), legacy-tests
  vr-20261017-030301-5b4bd157 (262/0). Known limitations in LEGAL_PATHS (pre-R21 stale planned rows; LINK_REJECTED race; process-death
  window for post-commit actions; anchor drift). Next: R25 (worktree B, review fixes + owner Q-R25-G1), R23, R17/R19, R18, rest.
- 2026-10-17: R23 PASS (fingerprint 04d62dca..., rev 9706f7ec + diff). New PrivacyCapability.EXCHANGE_RATE_DOWNLOAD (default OFF, fail-closed, own DataStore key) decided by a new NetworkPrivacyGate in CompositePrivacyGate; CurrencyRatesRepositoryImpl returns a typed outcome before any network call; currency screen shows truthful bounded messages (no offline claim when disabled); Privacy settings Network section with ECB/IP disclosure; backup screen notes receipt images are not included; BackupEncryptionService rejects truncated GCM input provider-independently. Gate: static-guards 25/25 vr-20261017-052101-9b1fd1ab, app-check vr-20261017-053927-ac4a09f2 (8464/0), legacy-tests vr-20261017-061041-222d8def. New user-facing notes: docs/releases/release-1.0-assurance/release-1.0-user-notes.md. Next: R25 (needs owner Q-R25-G1), R17/R19, R18, R20/R22b/R24/R16c, emulator restore, final gate, Phase 7.
- 2026-10-17: R17 PASS (fingerprint 8c5c1fe6..., rev a112c9cd + diff). Notification-capture status: a read-only checker reads the system enabled_notification_listeners setting on every check (fail closed); Home shows a card only when capture consent is on but Android access is off; Privacy settings always shows the OS access state with "Manage access" (system settings intent chain with fallbacks and an Android 13+ restricted-settings hint). Gate: static-guards 25/25 vr-20261017-071307-786335ea, app-check vr-20261017-073126-fbdb0f11 (8488/0), legacy-tests vr-20261017-080154-8cab8a15. Next: integrate R25 (worktree B; owner approved the 3 db_access tuples), then R19, R18, R20/R22b/R24/R16c.
- 2026-10-17: R25 parts 1-2 + R27 second safeguard PASS (rebased on 798a3d06; fingerprint d72c7088...). DatabaseOpenGate probes the live DB read-only once per process (v145..v151 open; older/newer/unreadable blocked; missing/empty = fresh); entry points skip when blocked (MainApplication, WorkerExecutionGuard, NotificationCaptureService, 4 receivers) and MainActivity redirects to UnsupportedDatabaseActivity (non-exported, no Hilt/Room; user-started SAF zip export of db + -wal only); GatedOpenHelperFactory/GatedOpenHelper refuse live opens while blocked or during restore swap/rollback/reset (latch + persisted peek); interrupted-restore recovery runs before blocking (BlockedGateStartupRecovery); backup floor = v145 with BACKUP_SCHEMA_UNSUPPORTED_LEGACY/NEWER (journal allowlist + specific UI strings). Owner-approved db_access structural exceptions 64 -> 67. Gate: db_access PASS vr-20261017-083309-bcaf6ed3, static-guards 25/25 vr-20261017-084116-19ebab13, app-check vr-20261017-090243-42d6ff13 (8553/0), legacy-tests vr-20261017-093423-a6e07dc9. Next: R19 gate (worktree A), R18 (after R25; BootReceiver), R20/R22b/R24/R16c, emulator, final gate, Phase 7.
- 2026-10-17: R19 PASS (fingerprint 36aa20d3..., rev 841cadfa + diff). Deferred features (tax configuration, investment portfolio, spending challenges) are release-gated by DeferredFeatureGate (available only in debug builds): hidden from Home/feature list, saved-destination tokens and deep links resolve to nothing, MainActivity branches redirect Home. Privacy-settings feature card strings added (3 locales); unused nlp_search_voice_cd removed. Gate: ui.navigation.* 77/0 vr-20261017-094000-31b9c74c, static-guards 25/25 vr-20261017-095339-9175eaed, app-check vr-20261017-101520-7dd5d0e4 (8568/0), legacy-tests vr-20261017-104505-75d3bddc. Next: R18 (slice A coded in worktree B, in review), R20/R22b/R24/R16c, emulator, final gate, Phase 7.
- 2026-10-17: R20a PASS (fingerprint 1ae53eda..., rev df769791 + diff). Subscriptions total via MultiCurrencyRepository.aggregateDisplayAmounts (AVAILABLE/PARTIAL/UNAVAILABLE; per-item own-currency cost-per-use/candidate annual); FinancialStressForecastEngine converts with convertOutcome + StaleRatePolicy.forBasis, excludes and counts failed conversions (max across nested horizons), partial flag, card warning only when excludedCount > 0, blank display currency fails closed; MonthlySavingsSweepUseCase riskBuffer = upcoming bills + uncertainty (owner decision via standing approval). Cancellation baseline FinancialStressForecastEngine G-CANCEL-02 5 -> 4. Assumptions: unsupported currency code -> subscriptions total Unavailable; persisted pre-1.0 riskBuffer = uncertainty only. Follow-up F-R20-a: 4 remaining CE-swallowing runCatching in resolveStartingBalanceBaseline/getEmergencyBuffer. Gate: static-guards 25/25 vr-20261017-123235-e4baa19e, app-check vr-20261017-125420-b1f7ad66 (8583/0), legacy-tests vr-20261017-132326-86392dcf. Plans 33/34a/34b appended to the remediation plan. Next: R18 gate (worktree B), then R22b, R20c-1, R20b, R20c-2, R16c-1/2, R24a/b/c.
- 2026-10-17: R18 PASS (fingerprint 6c66aca9..., rev 0710449c + R18). R18a: TransactionLifecycleCoordinator blocks delete (ExpenseDeleteOutcome.LinkedToGroup -> GroupLinkedExpenseException EXPENSE_DELETE_BLOCKED_GROUP_LINKED) and money/ownership/type edits (EXPENSE_EDIT_BLOCKED_GROUP_LINKED) of group-linked expenses inside the existing barrier + transaction (GroupExpenseDao.existsForExpense read); updateOwnership wrapper mirrors V2 NoOp comparison; updateOwnershipDbOnlyV2 untouched; callers TransactionsViewModel x3 + MainActivity split show bounded strings. R18b: ui/util/UiWriteGuard.guardedWrite (only DatabaseAccessBlockedException -> ui_write_blocked_maintenance) swept across SavingsGoals (events Channel; GoalCreated after success), Home, VisualSplit, Category (init crash during restore), RecurringExpensesScreen VM (+deleteManualRule CE/bounded log), SpendingMap (resolve/POI, separate snackbar dismissals, bounded log), Warranty, Assistant, Review. Out-of-plan write set recorded: 6 screens + 3 strings files, StringResource instead of DynamicString. Not wrapped (no barrier): widget style, dashboard config, recommendation dismissal, savings contribution history. No HomeViewModel write-blocked test (Home VM tests @Ignored; RP-21 harness). R18c: BootReceiver.shouldStartOnBoot skips BOOT_COMPLETED FGS start on API >= 35 (BOOT_FGS_START_SKIPPED_API35; BOOT_FGS_START_FAILED class=...); capture continues via system-bound listener. Device checklist: API 35 reboot (granted/revoked) -> logcat FGS exceptions + capture works; delete group-linked row; writes during backup. Follow-ups: F-R18-a SubscriptionManagementViewModel :401 raw e.message (after R20 integration); MainActivity :880-893 hardcoded "Unable to apply split" (P3); VisualSplit blocked save silent (MainActivity :907 navigates back first, P3); names-only ownership change blocked via wrapper (P3). Gate: static-guards 25/25 vr-20261017-134626-dfec6459, app-check vr-20261017-140457-92c8f3ad (8628/0), legacy-tests vr-20261017-143435-bbf3f31e (276/0).
- 2026-10-17: R16c-1 (c02ebb94), R22b (2706c9e1), R20c-1 (b12dd1c6), R20b PASS on one combined tree (fingerprint 491f553b...). R16c-1: AmountInputSanitizer sanitize/parseOrNull at Home planned dialog, BillNegotiation, VisualSplit (defect-pinning test flipped), CurrencyManagement, CashFlow; new formatForInput (locale decimal mark, 2 decimals). R22b: transaction-shaped rows bypass substring header keywords except whole-word SUMMARY_KEYWORDS; "+" deposits (local removePrefix before AmountUtils); CREDIT CARD not a deposit; positional date regex; statement-wide day order (dd/MM default); future dates skipped; generic break. R20c-1: UiTimeUtils picker conversions at DateSelector/ManualRecurring/FormComponents. R20b: AnalyticsComparisonWindows (previous calendar period cut at the same elapsed minute; YoY to addYears(now,-1); ALL no %; % and category deltas from rows <= comparison minute; cache keyed on the minute), export setPickedDateRange (local day, exclusive next midnight, chips end-1ms), forecast re-emits on home currency / rate updates. Follow-ups: F-R20b-a Home/Dashboard/AdvancedAnalyticsDashboard same-length windows; F-R20b-b current-minute rows until re-entry; F-R22-b/c Greek path "+" and NBG-in-generic; F-R16c-a R16 original Locale.US prefills; F-R20c-a..c (ManualRecurring default now, DateSelector remember key, Review re-pick ms). Device: TZ=America/New_York add expense/recurring/savings; el_GR amount entry incl. bill negotiation. Gate: static-guards 25/25 vr-20261017-160622-da31e3e4, app-check vr-20261017-162448-a611e9aa (8714/0), legacy-tests vr-20261017-165748-9f3823fb (276/0). Next: R24a+R24b (worktree B), R20c-2, R16c-2 (+F-R18-a), R24c, emulator, final gate, Phase 7.
- 2026-10-17: R20c-2/R16c-2/F-R18-a (1914ee0d) + R24a/b/c PASS on one combined tree (fingerprint dc3206c1...). R24a: ReviewQueueRepository bounded approval errors (KNOWN_APPROVAL_ERRORS; validation count only), ReviewViewModel shows only known messages, bounded batch message (known reason allowlist), class-only logs, CE siblings across review paths; ReceiptLifecycleCoordinator logs validator error count only. R24b: NotificationPromptPolicy/Store/SettingsLauncher; prompt once per install; deny never reopens; one "Open settings" offer when rationale false before+after. R24c: ReceiptScanState.currencyUnverified (blank OCR currency or OCR_FAILED) -> warning + quick-save off (preview/reason/confirm guards); home currency kept across reset/retry. Cancellation baseline: ReviewQueueRepository G-CANCEL-01 3 -> 1. Records: R22 user note corrected (statement import is debug-only in release builds). Device plan = remediation-plan section 35 (P1..P15); P1 (owner DB copy on emulator) awaits owner approval Q-DEV-1. Gate: static-guards 25/25 vr-20261017-182628-fc1320f7, app-check vr-20261017-184732-0c7e7e16 (8741/0), legacy-tests vr-20261017-192243-b17a231e (276/0). Next: device verification on wave5-api35 / release-api26, final gate on the frozen candidate (incl. second clean legacy run for HANG-001..003), Phase 7.
- 2026-10-18: Device sessions 1-3 on emulators (synthetic data; evidence build/device-evidence/ in the release worktree): P2, P6 (partial), P8, P12, P13, P15 PASS; P10 partial PASS; P7 card PASS in Privacy settings. Findings fixed as R31: Home banners (incl. R17 access card) were drawn under the dashboard grid (now one stable-key full-span item); Safe-to-spend card always showed a false "could not be converted" warning (hardcoded isPartial; now real data quality) and told budget owners to set a budget (hasBudget). R31 targeted: ComputeDashboardWidgets 35/0 vr-20261017-213608-a7934d35, home.* 31/0 vr-20261017-214536-8f5a9d5f; review ACCEPT-WITH-NOTES. Owner: Privacy settings discoverability is future work; owner approved using the owner DB copy on the emulator for P1 (emulator only, counts only, wipe after). Next: rebuild APKs, device session 4 (P1 + R31 visual + P9), final gate on the frozen candidate (static-guards, app-check, legacy x2 for HANG-001..003, assemble-release + evidence), Phase 7.
- 2026-10-18: FINAL GATE on frozen candidate 6b44823e: static-guards 25/25 vr-20261017-221803-cb61b66d, app-check vr-20261017-223741-99107e79 (8745/0 debug + release), legacy-tests vr-20261017-230732-86d1d142 (276/0, no hang; second identical-fingerprint run refused by runner dedup, HANG-001..003 recorded NOT REPRODUCED after 9 clean runs), release-artifact-evidence vr-20261017-230857-57a188fe PASS (debug-signed; owner signs with release key). Device: P1 owner DB upgrade PASS (v145->151, counts equal, AVD wiped after), R31 visual PASS; blocked-by-automation probes listed in the findings ledger for an owner manual smoke. Next: Phase 7 independent final acceptance (release-final-reviewer, max effort), then the owner's release decision (merge vs rebase into release-1.0-remediation; signing; no push by the lead).
- 2026-10-18: Phase 7 attempt 1 = CONDITIONAL (no P0/P1 in code; gaps: production signing, minified-artifact smoke, connected tests, device probes P3/P4/P5/P9/P14, owner upgrade path RT-03, HZ-03 F1, explicit owner confirmation of P2 deferrals, beta/perf/PDF). Closed by the lead: connected tests on emulator (NestedTransactionSemanticsInstrumentedTest 3/0 vr-20261018-001835-6fa1a3a7; full suite 376/0 vr-20261018-001952-74f0d8b5); R32 (HZ-03 F1: post-swap restore cancel -> restart required + journal recovery instead of permanent CRITICAL lock, owner-delegated Q-R32-1; restore/import/reset refuse to start while restart is pending or a journal is active; F-R20-a cancellation-safe forecast fallbacks, baseline entry retired); RG11-H2 and RG17B-1 dispositions. R32 targeted 8/8 PASS (data.backup.* 226/0 vr-20261018-013128-b6548563); reviews ACCEPT-WITH-NOTES x2. Owner actions open: production-signed build + smoke, manual emulator checklist (incl. backup/uninstall/install/restore upgrade path), explicit confirmation of the deferrals, capture-consent default, beta/perf/PDF scope.
- 2026-10-18: FINAL GATE cycle 2 on candidate 01fae80c (R32; clean tree, all runs on this revision): static-guards vr-20261018-013948-2a96efea PASS, app-check vr-20261018-015917-fae8ab55 PASS, legacy-tests vr-20261018-181715-c051f10c 276/0, connected-tests emulator-5554 (wave5-api35, API 35) vr-20261018-182059-2b39c1e1 376/0/9 skipped (pre-v145 migration chains), assemble-release vr-20261018-183624-b57e7eb7, release-artifact-evidence vr-20261018-183848-1a44c46e PASS (app-release.apk SHA-256 b364a144...f18f, debug certificate, non-debuggable). Records: user notes R32 entry; coverage matrix section 0 (final coverage status). Next: Phase 7 re-run on 01fae80c (release-final-reviewer, max effort), verdict to release-1.0-final-acceptance.md; owner actions unchanged (signing, manual checklist + RT-03, deferral confirmation, capture default, beta/perf/PDF).
- 2026-10-18: Phase 7 attempt 3 on 01fae80c = CONDITIONAL (engineering: no P0/P1; owner-only conditions). Verdict and owner decisions recorded in docs/releases/release-1.0-assurance/release-1.0-final-acceptance.md: signing deferred by owner, manual device checks waived for now, all deferrals to 1.0.1 approved, capture consent default ON kept, short beta approved. Regression ledger reconciled (RG12-01/CL-11, RG01A-1, RG01-N1, RG04B-1, RG11-H2/CL-07, RG17B-1 CLOSED). Owner requested merge into bug-fixes + push: bug-fixes fast-forwarded to this records commit. Next (owner): production-signed build, signed-artifact smoke, short beta; 1.0.1 backlog = Known limitations + F-* follow-ups.
