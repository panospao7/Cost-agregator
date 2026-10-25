# loan-installments — feature handoff

Feature id: `loan-installments`
Branch at start: release-1.0-remediation (dirty tree with unrelated R16 amount-parsing work — preserve, do not touch)
Status: PLAN APPROVED; domain-spec AMENDED (AMD-1..4). PAUSED until Release 1.0 accepted + release-1.0-wt merged

## Raw idea (owner, 2026-10-16)
"loan planner (a place that you can add a loan and its characteristics) and an installment
manager/coordinator (whatever you want to call it)"

## Orchestrator pre-check
- No loan/installment/amortization domain in `app/src/main`; only "loan"/"mortgage" category
  keywords in `domain/savings/SmartSavingsEngine.kt` and `AutomatedSavingsRuleEngine.kt`.
- Release 1.0 candidate is frozen (see `release-1.0-assurance-handoff.md`); any app/ change
  invalidates release evidence. Feature work is docs-only until the owner decides timing.

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/loan-installments/brief.md | RESHAPED (single allowed pass done; E core, per-installment spending rule, installment-declared expenses, payment-level fixed rate). Further reshape → owner. |
| 2 Critique | docs/features/loan-installments/critique.md | DONE (feature-critic) — verdict RESHAPE |
| 3 Domain rules | docs/features/loan-installments/domain-spec.md | DONE (attempt 2). 31 invariants, 25 acceptance examples, 15 open decisions OD-1..OD-15. Recommends separate InstallmentPlanLifecycleCoordinator (new legal path), new expenses.spendingExclusionReason column + canonical predicate/guard, AUTO_RECORD/CONFIRM modes, Long minor-unit math, additive schema. |
| 4 UX flows | docs/features/loan-installments/ux-flows.md | DONE (ux-flow-designer, written without domain-spec). 15 domain-dependent points DD-1..DD-15, 17 OQs; §4.7 provisional state table needs reconciling with domain-spec. Notable: no standalone expense detail screen (declare via Transactions row overflow sheet); Add expense has no currency picker (USD new-expense plans, OQ-7); no app-wide hide-amounts mode (OQ-14). |
| 4b UX reconcile | docs/features/loan-installments/ux-flows.md | DONE. 9 DDs resolved by spec, 4 mapped to ODs (DD-1→OD-3, DD-3→OD-5, DD-4→OD-1, DD-11→OD-9), 2 spec gaps (OQ-18, OQ-19). |
| Gate 2 sheet | docs/features/loan-installments/gate2-decisions.md | DECIDED 2026-10-16 (see Human decisions) |
| 6 Slice plan | workflows/active/loan-installments-plan.md | DONE (attempt 2; 20 slices S1..S13 incl. S2b, S4a-d, S6a-c, S7a-b, S8a-b, S10a-b, S12a-b; S0 D-1..D-20; 12 plan-gate items; risk register R1-R18). S0 addendum merged. APPROVED 2026-10-16. |
| 5 Impact & tier | docs/features/loan-installments/impact-map.md | DONE (Part A + Part B). Tier T3. DB 151→152 additive; 44 ExpenseDao SUM( + 229 effectiveAmount uses (70 files); new legal path + TLC op; FG-10/11/12/13/14/16/17/21/26 (+02/06/07/23 for new guards); conflicts with release worktree in TransactionsScreen/VM, AddExpenseViewModel, BudgetScreen. |

## Human decisions
- PENDING Gate 1 (go / reshape / drop). Critique verdict RESHAPE: v1 = payment tracking only (fixed amount × N
  payments), no amortization engine; one stated spending rule; explicit reuse-vs-separate criteria; correct brief
  code claims. Reshape needs owner answers (critique Q1–Q7) — the single allowed strategist reshape pass will be
  run WITH those answers, not before.

- 2026-10-16 owner answers (verbatim): "i think e is the closest one and the one that closely corresponds to
  the needs. i think the installment shall count splitted and not at once ( as the user will not pay at once ) .
  thought might be handy to be able an expesne to declare it installments and set number and the ammount to be
  splitted . fixed rate for now is sufficient / yes definitely post 1.0"
  Recorded as Gate 1 = RESHAPE (owner answered the reshape questions; revised brief returns to owner for
  confirmation before stage 3). Interpretation:
  - E (unified installment overview) is the core.
  - Spending recognised per installment, not all at once on purchase day.
  - New capability: declare an expense as installments (number of payments + amount split).
  - Fixed rate only for v1.
  - Post-1.0 confirmed; docs-only until the release candidate is accepted.
  Unanswered: budgets vs cash-flow-only (critique Q3, partly implied by "count splitted"), recurring reuse vs
  separate (Q5, defer to domain-modeler), currency (Q6), personal test case (Q1b), lent/borrowed (C).

- 2026-10-16 owner answers to reshaped-brief open questions (verbatim): "i think euro and dollar is enough for
  now . i have an ongoing loan on me but hopnestly we can mock any data we want so to speak through testing.
  money lent to someone wont be bad to have ( but it doesnt propably have fixed criteria like rate or data ) for
  what happens i am unaware , i guess when you cancel it is shall be cancelled all together ( i need your opinion )
  i think they shall count in the monthly bugdet"
  Interpretation:
  - Q1 currency: EUR + USD in v1 (per-currency totals; no silent cross-currency sum).
  - Q2 test case: mock/golden data in tests (owner has a real loan but mock is fine).
  - Q3 lent money (C): wanted, loosely structured (no fixed rate/schedule). v1 vs later NOT decided — adding to v1
    is scope growth beyond reshaped brief → owner must choose.
  - Q4 cancel/edit: owner leans "cancel all together", asked for opinion → domain-modeler to formalise options
    with a recommendation; owner decides at Gate 2.
  - Budgets: installments count in the monthly budget of the month they fall due (confirms spending rule).
  - Q5 (unconfirmed past installments) and Q6 (per-installment bank notifications): unanswered → domain-modeler
    proposes defaults as open decisions for Gate 2.
  - Reshaped brief itself: not yet explicitly confirmed (GO pending).

- 2026-10-16 owner scope addition (verbatim): "it would be nice to have the ability to see past loans etc so the
  user has an image of what they had even if now is payed . what do you suggest for the lent to someone thing"
  Recorded: owner-requested addition to increment 1 — history of completed / cancelled plans stays viewable
  (owner is the scope authority; added to brief "Owner addenda"). Domain-modeler must check retention/cleanup
  policy interaction. Orchestrator suggestion on lent money (C) given to owner: defer to increment 2 as a separate
  lightweight "money owed" record (not a payment plan, not spending). Awaiting owner decision + GO.

- 2026-10-16 owner: "ok to the lenting and go" → Gate 1 = GO on reshaped brief (incl. plan history);
  lent/borrowed money (C) deferred to increment 2.

- 2026-10-16 owner asked to give agents longer turn budgets. Applied to the 8 untracked feature-pipeline agents
  (.claude/agents): strategist 30→60, critic 25→50, domain-modeler 45→90, ux-flow-designer 35→70,
  impact-mapper 35→80, planner 40→80, implementer 50→80, test-author 45→80. Tracked/shared agents
  (guardians, reviewer-strict, validation-runner, release-*) NOT changed — pending owner OK (shared with the
  Release 1.0 campaign).
- 2026-10-16 owner: "change those also" → maxTurns raised on tracked/shared agents: architecture-, privacy-security-,
  room-migration-guardian, reviewer-strict, release-final-reviewer, release-finding-verifier, release-horizontal-auditor,
  release-vertical-auditor, release-regression-auditor, release-planner, release-remediation-coder, release-test-author
  → 80; validation-runner 35→60. (reviewer-strict had no maxTurns line; added.) Not changed: debugger, scout,
  release-orchestrator, feature-orchestrator. These are uncommitted modifications to tracked files.

- 2026-10-16 GATE 2 owner answer (verbatim): "do the reccomended. i think we can a currency picker . historical i
  guess. shall we have categories for loans or not. there are types of but i dont know if they matter to be
  categorized (naming them can be enough ) make installments visible yes. 5 is ok yes . lets leave hiding ammounts
  out for now"
  Recorded:
  - OD-1..OD-15: ACCEPT agent recommendations (OD-15 only if planner proves necessary).
  - IM-1..IM-10: APPROVED (schema 151→152 additive; T3 + guardians + reviewer-strict; LEGAL_PATHS update; 3 new
    guards; db_ownership_policy entries; raw-money-aggregate allowlist/ratchet change only as needed and itemised;
    IM-7 worker host = planner proposes, owner confirms at plan gate; enum values; wipe/backup-privacy verify;
    build after Release 1.0 work lands).
  - OQ-7: ADD a currency picker in Add expense (scope to be kept minimal by planner; general vs installment-only
    flagged for plan gate).
  - OQ-18: payments made before using the app → history only (no expenses created, do not count).
  - OQ-19: no loan "type" taxonomy — plan name is enough. Installment expenses still need a spending category for
    budgets/analytics: purchase-attached plans inherit the purchase category; standalone plans → user picks an
    existing category with a sensible default (planner to choose from existing categories).
  - OQ-22: auto-recorded installment expenses visibly marked: YES.
  - OQ-16: declare from Transactions row menu: OK.
  - OQ-14: hide-amounts mode: OUT of scope.
  - OQ-23 = OD-13: no event table in v1 (recommendation accepted).
  - OQ-8/10/11/17 unanswered, low-risk → planner defaults (per-currency history header, Spanish parity with
    existing values-es, Home link optional, no lent-money teaser), listed for plan gate.

- S0 discovery persisted: docs/features/loan-installments/discovery-s0.md (scout). Key: no recurring
  materialise worker → AUTO_RECORD needs a NEW worker; no erase-all path found; rescue allow-list must add plan tables;
  ExportAnonymizer is per-table explicit (and does not sanitise expenses — out-of-scope observation); no 150→151
  migration test (pre-existing gap); no Loans category (new default would need back-fill); Add expense has no currency
  choice, reusable picker is private in ReceiptScanScreen; cloud-briefing entry point = UpcomingItem sealed type.

- 2026-10-16 PLAN GATE owner answer (verbatim): "i approve the reccomendations yes" → plan APPROVED with all
  planner recommendations:
  1 dedicated InstallmentDueRecordingWorker (no reusable worker exists); 2 currency picker option A
  (installment-scoped); 3 OQ-18 = PAID_BEFORE_TRACKING entry status (spec amendment before S5); 4 OQ-24 financing
  cost excludes SKIPPED/CANCELLED/REVERSED (spec §9 amendment); 5 standalone plans: no preselected category (no
  Loans category exists; no new category created); 6 guard designs as proposed (in-source exemption marker, cloud
  guard new-or-extend per D-20, raw-money-aggregate re-key only — any growth = STOP); 7 pre-existing raw-.amount
  sums: decide when D-11 list exists (deferred decision); 8 defaults OQ-8/10/11/17/20/21/25 + hidden entry until
  S13; 9 no index on spendingExclusionReason; 12 instrumented migration proof on emulator (e.g. wave5-api35) —
  never PASS without a real run. Items 10/11 conditional (ask if triggered).
  Out-of-scope observations (no 150→151 migration test; ExportAnonymizer not sanitising expenses; Bills→Food
  mapping) reported to owner; no action requested.

- 2026-10-16 domain-spec amended by domain-modeler: AMD-1 PAID_BEFORE_TRACKING (INV-32), AMD-2 financing cost
  formula, AMD-3 plan category source (INV-33), AMD-4 OD-1..OD-15 decided. Amended INV-6/15/17/20/21/26; new
  AE-26..AE-30; 33 invariants total; no DDL change. Flagged to owner (no decision requested unless they object):
  declaring an already-captured purchase with k>0 paid-before-tracking makes those past months count 0 (user can
  instead use "Record all past payments" to count them).

## Slice statuses
- none (no plan yet)

## Validation run IDs
- none

## Open risks
- 2026-10-16 ~10:00: the uncommitted R16 amount-parsing edits disappeared from this worktree; verified they were
  MOVED to the release worktree `C:/Users/panos/Desktop/Cost-agregator-release` (branch `release-1.0-wt`) by other
  release work, which is actively editing AddExpenseViewModel, TransactionsScreen/ViewModel, BudgetScreen,
  ManualRecurringExpenseScreen, AmountUtils, AmountInputSanitizer, etc. → future merge-conflict risk with this feature.
  This worktree's tree is clean apart from the untracked feature docs and .claude/agents files.
- Domain-spec flags (outside this feature): BackupVerifier.kt:522 would fail on any non-RECURRING_RULE occurrence row;
  recurring event metadata reaching export unverified; AmountUtils/AmountInputSanitizer half-up rounding to confirm
  (do not modify under this feature without approval).
- Timing vs. Release 1.0 freeze.
- Money/currency/rounding (amortization math) → likely T3.

## Next action
Owner answers plan-gate items 1-12 (workflows/active/loan-installments-plan.md "Human decisions required" + S0
addendum). Then: if OQ-18 = PAID_BEFORE_TRACKING and/or OQ-24 confirmed → domain-modeler amends domain-spec
(§3.2, INV-6, §9, §11.1, new AEs) BEFORE S5. Implementation (S1 onward) starts only after Release 1.0 is accepted
and release-1.0-wt is merged; branch from bug-fixes; S11/S12a/S12b hard-gated on that merge. Docs-only until then.

## Cross-feature risk (recorded by orchestrator 2026-10-16, from manual-entry-currency domain-spec OD-4 / RF-3)
- TransactionLifecycleCoordinator never refuses an UNFLAGGED save when no exchange rate exists (it saves with default base values).
  So this plan's S11 "FX unavailable → keep form" refusal (D-8) and the AUTO_RECORD_DEFERRED_FX branch cannot trigger as designed.
  Resolution (DECIDED at manual-entry-currency Gate 2, G2-6): land manual-entry-currency's core slice (eligibility rule +
  request flag + typed refusal, no UI) BEFORE loan S11, and have declareWithNewExpense set the flag. Also: loan S11's EUR/USD control
  will be merged into the general currency control (manual-entry-currency N2, owner-approved). Re-check S11 at its S0.

## Approved global post-1.0 build order (manual-entry-currency Gate 2 G2-1, 2026-10-16)
Release 1.0 (incl. R16, R23 part 1) → ai-fixes → money-correctness-1.0.x (if specced; else after MEC a) → MEC core (a, setter-less)
→ loan S1..S10 (S3/S6a serialised vs TLC work) → nl-quick-add inc 1 → MEC (b) add-expense UI → MEC (c) receipt review
→ loan S11 on the general currency control (plan edits at loan S0: S11 write set, D-8, S6a AUTO_RECORD_DEFERRED_FX) → nl-quick-add inc 1b → MEC inc 1b.
Source: docs/features/manual-entry-currency/impact-map.md §9.

## manual-entry-currency convergence (MEC plan slice D, 2026-10-16; pre-approved by MEC Gate 2 G2-6 (+G2-1, G2-10, G2-15); loan-plan edits applied by the loan planner at loan S0; reviewer-strict ACCEPT-WITH-NOTES, reference fixes applied)
Source: workflows/active/manual-entry-currency-plan.md §D; docs/features/manual-entry-currency/domain-spec.md (INV-5, INV-18, AE-28, AE-29, RF-3).
1. **S11:** remove the EUR/USD segmented control (loan plan :455), the plan-currency row and the OD-14 note (loan ux-flows.md:222-224; currency option table loan plan :517);
   also obsolete under items 1–2: FX test :462, "currency-scope decision" :464, option-B stop-and-ask :467. Use MEC slice B2's general currency control in
   `mode = INSTALLMENT` (options ∩ {EUR, USD}; unsupported selection → Unset, restored when installments are switched off — MEC UX-Q-6).
   S11 depends on MEC B2 (and C, per the global order). S11's "FX unavailable" copy is replaced by the shared `mec_` copy.
2. **D-8 answered:** TLC refuses only FLAGGED creates. `declareWithNewExpense` sets
   `userPresentRate = UserPresentRateRequirement(entryZone)` on the purchase request. On `ValidationFailed.rateRefusal` the plan coordinator
   returns its typed refusal before inserting the plan → the whole declaration aborts (MEC INV-18).
3. **S7a / RF-3:** (the branch and `recordDueEntries` live in loan **S7a**, plan :351; R15 :591) `AUTO_RECORD_DEFERRED_FX` assumes a refusal that never happens for unflagged creates. Auto-record never sets the flag and is
   created with today's defaults (MEC AE-29). Recommendation: drop the FX deferral branch; any loan-local deferral must call
   `RateEligibilityEvaluator`, never re-implement the rule. Re-assess loan risk R15.
4. Recommendation: set the flag already in **S6a** (before S11) inside `declareWithNewExpense` (no production caller before S11; INV-18 testable
   at coordinator level). `recordDueEntries` (S7a) must never set it — the AE-29 null-flag test belongs in **S7a**.
5. Serialise AFTER MEC A2: loan **S3** (TLC + ExpenseDao + exhaustive `when (request.source)` :508-526 via `ExpenseSource.INSTALLMENT_PLAN`,
   plan :192) and **S6a** (consumes A2's `userPresentRate`/`rateRefusal` contract).
6. MEC A2's setter allow-list guard (`ManualEntryCurrencySetterAllowListGuardTest`) already pins
   `domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt`. A rename needs owner approval (MEC N16). The guard is file-level, so
   "declare only, never auto-record" is enforced by the AE-29 test + reviewer-strict.
7. Loan UX doc follow-up for MEC UX-Q-6 (plan-currency row removed; installment-mode currency subset).
Questions for the loan planner (reviewer, not defects): (a) any kept loan-local deferral contradicts AE-29 ("never refused by this rule") and
the evaluator needs an entry zone a background run doesn't have → drop the branch by default; any kept deferral goes to the owner.
(b) `declareWithNewExpense` runs TLC inside `runWrite { withTransaction { … } }` (plan :286) while MEC G2-12 says refusal events commit with no row
→ the S6a AE-28/INV-18 test must pin whether the outer transaction commits or rolls back TLC's two refusal events.
Note: loan S0 discovery already exists (plan :4, discovery-s0.md) — re-run S0 after 1.0 before applying these edits.
Acknowledge at loan S0; loan plan edits made by the loan planner under G2-6.

## Loan S0 refresh (2026-10-23, after Release 1.0.2 + MC + MA + MEC A2; lane Cost-agregator-mec, branch feature/manual-entry-currency @ 8195250f)
Branching note: loan work continues on the MEC lane branch, since MEC core (S0/A1/A2) is the required predecessor. Loan commits stack on 8195250f.
Scout (Sonnet, read-only):
- D-12: FG-26 = scripts/ci/verify_guard_docs_truth.py. It checks PATHS only (config/guards, config/baselines, docs/ci, scripts/ci, scripts/db_guard, scripts/guardrails), in indexed docs only (LEGAL_PATHS indexed; ENGINE_INTERACTION_MAP and CODEBASE_SEGMENTS not). Symbols need not exist, so the D-12 contingency is dropped and S1 keeps the docs. Trap: any scripts/ci/... path named in LEGAL_PATHS must exist in the same commit; a lastReviewedSha refresh in docs/ci/GUARD_DOCUMENT_INDEX.yml may be needed (unconfirmed).
- D-20: existing cloud_payload (G-CLOUD-01) is body-provenance only, with no reachability model, so the recommendation is a NEW guard. Template: raw_money_aggregates registry entry (guard_registry.py:722-780); run_static_guard_suite.py SUITE_GUARD_ORDER; scripts/ci/test_run_static_guard_suite.py expected lists/budgets; possibly the Gradle bridge app/build.gradle.kts ~570-602 + test_gradle_registered_guard_contract.py:40; generated docs via generate_guard_docs.py. No hash self-protection manifest; known_good_state unaffected.
- D-13: Room 151 (AppDatabase.kt:33, schema 151.json latest), so 151→152 stands.
- D-10: feasible. DatabaseWriteBarrier.runWrite is re-entrant (:54-69); createExpenseDbOnlyV2 returns MutationResult with a post-commit batch; precedent ManualExpenseRepository :191-221. OD-15 NOT needed. Re-check at S3 against A2 (now committed); STRICT_EXTERNAL_ID replays need explicit handling.
- Shards: architecture.* → architecture-contracts. domain.installment.* is in NO shard → a test-shards.json edit is needed at S5 (config edit: stop-and-ask then).
Owner approval basis for S1: gate2-decisions IM-3 (LEGAL_PATHS), IM-4 (new guards incl. cloud-reachability; registry change), plan APPROVED 2026-10-16.
G2-6 convergence plan edits DONE (feature-planner retry after an API timeout): persisted as plan section "S0 amendment — G2-6 convergence + S0 refresh" (wins over the slice text). Adopted: the S6a outer transaction commits TLC refusal events (G2-12). Owner item: test-shards.json entries at S5. domain-modeler follow-up before S7a: spec ~:577 / ux ~:484 AUTO_RECORD_DEFERRED_FX text.

## Slice statuses (2026-10-23)
| S1 | implementing |
- S1 source/scripts/docs DONE (feature-implementer, 2026-10-23):
  - Guard `installment_cloud_reachability`: scripts/verify_installment_cloud_reachability.py, rules G-INSTALLMENT-CLOUD-01 (import) / 02 (qualified ref) / 03 (Installment(Plan|Entry|Schedule|Projection|Upcoming|Due)* names). Comments and strings are masked. Exit 2 when the surface is empty. No allowlist or baseline.
  - Surface: data/ai, domain/ai, domain/privacy, data/privacy, di/AiModule.kt, di/PrivacyModule.kt, plus *CloudPayload*/*Prompt(Formatter|Builder)*/*Payload(Assembler|Builder)*/*(Briefing|Insight|Assistant|Query)*InputBuilder files.
  - Deviation from the plan: the script lives in scripts/ (template raw_money_aggregates), not scripts/ci/. LEGAL_PATHS names scripts/verify_installment_cloud_reachability.py (the file exists; FG-26 does not scan scripts/).
  - Registered in guard_registry.py and SUITE_GUARD_ORDER. No Gradle bridge (only 3 guards have one).
  - Mechanical pin updates required by registering the IM-4 guard: scripts/ci/test_run_static_guard_suite.py (manifest fixture leg + budget 300s); scripts/ci/test_gr10b_source_scope_matrix.py 16→17 and 25→26; docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md row + "26 rows".
  - Docs: LEGAL_PATHS (Installment Plan Mutations + 7 section updates, marked planned), ENGINE_INTERACTION_MAP (InstallmentProjectionPort row), CODEBASE_SEGMENTS (Segment 40).
  - Generated docs to refresh: `python scripts/ci/generate_guard_docs.py --root .` (GUARD_COMMANDS/GUARD_STATUS).
  - Known limit: this is a direct-reference fence; upstream-model reach is closed by S8a + AE-23.
- S1 tests: pass A (python guard test) running; pass B (InstallmentPlanArchitectureGuardTest.kt) next.
- S1 validation round 1:
  - installment_cloud_reachability vr-20261023-054441-cc76e6be PASS.
  - targeted vr-20261023-054457-a0ea99ac and architecture-contracts vr-20261023-054613-a780e3dd FAIL: compile error "Unclosed comment" in InstallmentPlanArchitectureGuardTest.kt:28. The KDoc globs `installment/**` opened nested Kotlin block comments. Orchestrator fixed the comment text (no behaviour change).
  - static-guards vr-20261023-054701-2a80c872 started before that edit, so it is invalid (stale tree).
  - Requeued targeted, shard, static-guards.
- S1 review round 1:
  - privacy-security-guardian ACCEPT-WITH-MINORS.
    - S1 fixes in progress: rule -03 widened to any `installment` identifier on the surface; backtick/newline-dot normalisation; every surface prefix/exact file must match (new infra code); doc carve-out (INV-28 merchant copy) and rule families; IM-9 fail-closed text; ENGINE_INTERACTION_MAP row break; CODEBASE_SEGMENTS Seg 40 package paths.
    - Later-slice items persisted as plan A8 (S2b, S8a, AE-23 differential).
  - architecture-guardian and reviewer-strict both died on API timeouts (twice for AG); they will be rerun one at a time after the fix batch.
  - static-guards vr-20261023-054701-2a80c872 (stale tree) also showed a real failure: scripts/ci/test_run_static_guard_suite.py::test_suite_order_gap_fails_derivation hard-codes "migration_matrix" as the last suite guard. Being made order-agnostic, with the same strength (a mechanical consequence of the IM-4 registration).
  - targeted vr-20261023-060203-0318b967 PASS, architecture-contracts vr-20261023-060420-e4272380 PASS (on the comment-fixed tree).
- S1 review round 2 + validation (final tree):
  - registered-guard installment_cloud_reachability vr-20261023-063004-cd101401 PASS (hardened guard; zero surface hits).
  - static-guards vr-20261023-063022-f65aa9b4 PASS (26 guards incl. guard_tests/pytest and the order-agnostic suite test).
  - targeted vr-20261023-060203-0318b967 PASS and architecture-contracts vr-20261023-060420-e4272380 PASS (the Kotlin test is unchanged since).
  - architecture-guardian (3rd attempt) ACCEPT-WITH-MINORS → plan A9 carry-forwards; Segment 40 fixed (ExpenseScheduleLinkPolicy listed, duplicate worker bullet removed). Docs-only after static-guards.
  - Next: reviewer-strict final gate on S1, then commit.
- S1 reviewer-strict (iteration 1) REJECT:
  1. MAJOR: the arch test does not fence RecurringExpenseDao (writes manual_recurring_expenses; check 2 skips `(?<!Recurring)ExpenseDao`, check 4 omits it).
  2. Minor: the `"$identifier"` template limit is undocumented.
  3. Minor: setSpendingExclusion caller wording vs the TLC exemption.
  4. Minor: GR-10B exit-2 list is incomplete.
  Validation freshness was verified by mtimes. Fix iteration 2 (the last allowed): docs (implementer) → arch test (test-author) → targeted + architecture-contracts + static-guards → reviewer-strict iteration 2.
- S1 revalidation after fix iteration 2: targeted vr-20261023-074642-ff3fb08b PASS; architecture-contracts vr-20261023-074844-b6383b7f PASS; static-guards vr-20261023-074932-6c3353cb PASS. Next: reviewer-strict iteration 2.
- OWNER 2026-10-23 ("i agree to them all"): S5 test-shards.json entries APPROVED (domain.installment.* → domain-a-m; domain.schedule.* → domain-n-z; additive). Record: Cost-agregator/build/release-lead-notes/owner-approvals-20261022.md item 10.
- S1 reviewer-strict iteration 2: ACCEPT (all 4 findings fixed; validation fresh). Non-blocking tidy-ups: the `(?<!Manual)` lookbehind in check 2 is redundant; check 4 has no GroupExpenseDao-clean assert.

## S1 DONE (2026-10-23)
Guard `installment_cloud_reachability` (rules G-INSTALLMENT-CLOUD-01..03, fail-closed, surface-entry staleness, no allowlist/baseline) + tests; InstallmentPlanArchitectureGuardTest (INV-23/24/25, incl. RecurringExpenseDao); registry/suite registration with mechanical +1 pins and an order-agnostic suite test; LEGAL_PATHS / ENGINE_INTERACTION_MAP / CODEBASE_SEGMENTS (Segment 40); plan amendment A1..A9.
Reviews: PSG ACCEPT-WITH-MINORS (fixed); AG ACCEPT-WITH-MINORS (→ A9); RS REJECT → ACCEPT.
Final validation: targeted vr-20261023-074642-ff3fb08b, architecture-contracts vr-20261023-074844-b6383b7f, static-guards vr-20261023-074932-6c3353cb, installment_cloud_reachability vr-20261023-063004-cd101401 — all PASS.
Next: S2 (schema 151→152) and S5 (pure domain; can run parallel to S2–S4; needs the approved test-shards entries). S2 needs a device/emulator for the instrumented migration proof (plan gate item 12): check availability first.

## S2 (2026-10-23) — implemented (two implementer runs; the first died on an API timeout mid-way, the second reviewed and finished)
- Schema 152:
  - MIGRATION_151_152 (additive, IF NOT EXISTS): installment_plans (unique planUuid, unique nullable purchaseExpenseId, index status, no FK); installment_entries (FK planId CASCADE, unique (planId,kind,seq), unique nullable linkedExpenseId, indexes dueDate/status); ALTER expenses ADD spendingExclusionReason TEXT DEFAULT NULL (no index; decision recorded in KDoc).
  - New entities InstallmentPlan/InstallmentEntry; SpendingExclusionReason enum; Expense field.
- InstallmentPlanDao: reads + CAS writes (insertPlan, updatePlanStatusIfCurrent, deletePlanById, insertEntries, updateEntryStatusIfCurrent, claimEntryForExpense, releaseEntryLink); no callers. DaoModule provider.
- BackupVerifier: TIER_1_EXACT for both tables; manifest-required only at version ≥152; expected 0 counts after migrating from <152.
- DB policy: NO rows added. Evidence: db_access skips *Dao.kt and flags callers only; S6a adds the rows with the coordinator. Write names keep MUTATION_VERBS prefixes.
- Version sites: all symbolic, no edit. D-16: no raw positional INSERT INTO expenses. FinancialRescueCoordinator has a fixed 6-table import, so nothing was added (S2b decides).
- 152.json: generated by Room KSP at the first compile. The first run may report STALE because the build writes a tracked file; rerun.
- Pre-existing tracked `data/database/_schema_tmp.json` is untouched.
Tests: pass 1 (JVM) running; pass 2 (androidTest migration 151→152 + matrix) next. Device: AVDs release-api26 / wave5-api35 exist.
- S2 validation round 1:
  - targeted vr-20261023-094743-8ef97cef PASS; static-guards vr-20261023-095820-327c4e9e PASS (db_access/db_artifact_sync/known_good_state unchanged; no policy rows needed).
  - migration-tests vr-20261023-095345-a4ca6201 and data shard vr-20261023-095431-b72a4f8f FAIL, one test only: SavingsSweepMonthMigrationTest.rg05a1 reopened a v151 DB with the production AppDatabase (now v152) without migrations → "migration required" → the coordinator mapped it to ACCEPTANCE_UNAVAILABLE.
  - Debugger root cause: stale test setup (the "[26] file is encrypted" lines come from DatabaseOpenGateTest garbage-bytes fixtures, unrelated).
  - Fix (orchestrator, one line, test-only, strengthens the test): `.addMigrations(DatabaseMigrations.MIGRATION_151_152)` on the reopen, so Room now validates the real 150→151→152 chain. Rerun queued.
- S2 reviews:
  - room-migration-guardian ACCEPT-WITH-MINORS (DAO CAS hardening applied: releaseEntryLink expectedExpenseId; updateEntryStatusIfCurrent never into/out of PAID; BackupVerifier comment counts).
  - privacy-security-guardian ACCEPT-WITH-MINORS.
  - architecture-guardian ACCEPT-WITH-MINORS.
  - Carry-forwards → plan A10. Docs version refresh pending (CODEBASE_INVENTORY v152 / 71 DAOs / 74 entities; LEGAL_PATHS header; Segment 40 "tables + DAO exist, no writer").

## S2 DONE (2026-10-23)
Final validation (fixed tree):
- targeted vr-20261023-094743-8ef97cef PASS;
- static-guards vr-20261023-095820-327c4e9e PASS;
- migration-tests vr-20261023-104941-e115f68d PASS;
- data shard vr-20261023-105157-e2d9c308 PASS;
- **instrumented FG-21 proof on release-api26 (minSdk AVD):** DatabaseMigrationTest vr-20261023-113430-b3874834 PASS (75 tests = 67 existing + 8 new 151→152 incl. the full chain from baseline 145); DatabaseMigrationMatrixTest vr-20261023-113747-dda0bba4 PASS (11 incl. migrate_151_to_152).
- (The compile run vr-20261023-093825-457cd762 was STALE by design: KSP wrote 152.json.)
- 152.json verified: spendingExclusionReason defaultValue "NULL"; both installment tables present.
Reviews (all ACCEPT-WITH-MINORS, fixes applied or carried as A10): room-migration-guardian, privacy-security-guardian, architecture-guardian. PROCESS DEVIATION (orchestrator): S2 was committed (d9798260) BEFORE the plan-required reviewer-strict ran. reviewer-strict now runs post-commit on d9798260; any finding is fixed in a follow-up commit before S2b starts.
Next: S2b (backup orphan checks, manifest, privacy mode, wipe, rescue), then S3. S5 (pure domain) can run on the same lane after S3, or in between; test-shards entries approved.
- S2 reviewer-strict (post-commit on d9798260): ACCEPT-WITH-MINORS. Follow-up commit fixes:
  (1) InstallmentPlanArchitectureGuardTest plan-DAO write rule now mirrors the Python MUTATION_VERBS (incl. claim/release), with planted self-checks for claimEntryForExpense / releaseEntryLink at a non-coordinator path; the A9 DDL self-check also runs at a non-exempt path;
  (2) BackupVerifier comment counts recounted from code: 61 = 42 exact (35 listed + 7 event/link) + 10 validity + 9 optional; stale "58-table" KDoc made count-free;
  (3) LEGAL_PATHS header no longer rewrites the 2026-10-13 entry.
  Validation: targeted vr-20261023-123247-3d4addb4 PASS. S2 is fully gated.

## S5 (2026-10-23) — implemented (source + approved test-shards.json entries; uncommitted)
- Files:
  - domain/installment/model/{InstallmentEnums, InstallmentRefusalCode, InstallmentMinorUnits, InstallmentPlanTransitions};
  - domain/installment/schedule/InstallmentScheduleCalculator;
  - domain/installment/readmodel/InstallmentPlanMath;
  - domain/installment/StandalonePlanDefaultCategoryPolicy;
  - domain/schedule/ExpenseScheduleLinkPolicy (own ScheduleLinkRefusal enum).
- Pure: no I/O, clock reads or default zone.
- config/validation/test-shards.json: +domain.installment.* (domain-a-m), +domain.schedule.* (domain-n-z). Owner item 10.
- Rounding: ONE point, toMinorOrNull HALF_UP at 2 dp on decimal text. This follows spec §9 OD-7 (explicitly HALF_UP); the orchestrator brief's "HALF_EVEN" was wrong. Spec wins.
- Equal split integer-only, residual FIRST/LAST.
- Implementer interpretations, for the reviewer / domain-modeler to confirm:
  (1) extra codes INVALID_DATE (1970..2200) and PURCHASE_NOT_FOUND;
  (2) |amount| ≥ 10^9 → INVALID_AMOUNT;
  (3) DELETE allowed from any status when no PAID/REVERSED entry exists (spec §3.1 vs INV-20 ambiguity);
  (4) adjust/skip reason sets are minimal;
  (5) the purchase-attached category edit allows null;
  (6) the COMPLETED history end date falls back to terminalEpochDay;
  (7) STANDALONE ⇒ FIXED_PAYMENT is enforced by the coordinator, not the calculator;
  (8) declaration preconditions live in ExpenseScheduleLinkPolicy.evaluateDeclaration.
- Next:
  - test-author (calculator AE-2/3/4/18/19/22/26/27 + conservation; math AE-5/9/18/29; transitions all cells; link policy AE-13/22; category AE-30; A9: add the exact file domain/schedule/ExpenseScheduleLinkPolicy.kt to PLAN_CODE_PREFIXES in InstallmentPlanArchitectureGuardTest ~:792);
  - then validation: targeted *Installment*/*ExpenseScheduleLinkPolicy*, shards domain-a-m + domain-n-z, static-guards;
  - reviewer-strict (money math) + domain check of the interpretations.

## S5 review fixes (2026-10-23)
After reviewer-strict REJECT. Source and S5 tests only; no builds, tests or guards run. Needs a new validation run (targeted *Installment*, shard domain-a-m) and a fresh review.

Fixes:
1. Mark paid before tracking retry (InstallmentPlanTransitions): a CANCELLED, REFUNDED or PAID_OFF plan is refused ILLEGAL_TRANSITION first. An entry already PAID_BEFORE_TRACKING is AlreadyInState even when the plan is COMPLETED. An actual move still needs ACTIVE. Bulk refuses only terminal plans and sends COMPLETED through the per-entry check: all already marked gives Allowed(toMark=[], alreadyMarked=[...]); any entry needing a move gives Refused(ILLEGAL_TRANSITION). Revert already had the same retry semantics (ACTIVE or COMPLETED, SCHEDULED gives AlreadyInState, terminal refused): unchanged, now pinned by a test.
2. INV-14 for CONFIRM: requires todayEpochDay non-null and dueEpochDay <= todayEpochDay, otherwise Illegal(ILLEGAL_TRANSITION). LINK unchanged (no date gate). EntryTransitionContext.todayEpochDay is now also required for CONFIRM.
3. Skip codes per owner-approved OQ-20: InstallmentStatusReason has exactly USER_SKIP_LENDER_HOLIDAY, USER_SKIP_WAIVED, USER_SKIP_OTHER (new), plus the helper isUserSkipReason.
4. Skip reason is an input: EntryTransitionContext.skipReason (new). SKIP returns it in Move.reason; null or a non-USER_SKIP reason gives Illegal. A retry on an already SKIPPED entry is AlreadyInState whatever the reason.
5. Calculator: generate returns INVALID_DATE when the last generated due date is after 2200-12-31. The due dates are now computed right after the date checks, so this wins over AMOUNT_TOO_SMALL_FOR_COUNT and INVALID_PAID_BEFORE_TRACKING (spec 7.0 order: date rules before minimum amount and k).
6. Delete (spec 7.5, fail-closed reading; owner confirmation pending, so this supersedes interpretation 3 in the S5 notes above): allowed only from ACTIVE or CANCELLED, and only without PAID or REVERSED entries. Payments are checked first (PLAN_HAS_PAYMENTS from every status, unchanged precedence). COMPLETED, REFUNDED and PAID_OFF without payments give ILLEGAL_TRANSITION. PAID_BEFORE_TRACKING never blocks.
7. Detach reasons: EXPENSE_REMOVED for the CANCELLED target for every detach cause (deleted, currency changed, type changed). LINKED_EXPENSE_CURRENCY_CHANGED and LINKED_EXPENSE_TYPE_CHANGED removed from InstallmentStatusReason (nothing persisted yet), and EntryTransitionContext.detachReason removed (no input left to vary).
8. Test nits: InstallmentPlanMathTest aggregation test now asserts against literals (10L + 20L = 30L); the calculator float-representation test now asserts 1234.57 as 123457 minor (x2 = 246914).

Changed or added tests (all under app/src/test/java/com/yourname/expensetracker/domain/installment/):
- model/InstallmentPlanTransitionsTest.kt
  - helpers: ctx(detach) renamed ctx(skip); ctxFor now gives CONFIRM today = dueToday and SKIP skip = USER_SKIP_LENDER_HOLIDAY.
  - changed: plan_table_DELETE_without_paid_or_reversed_entries_is_allowed_only_for_ACTIVE_and_CANCELLED (was ACTIVE, CANCELLED, REFUNDED, PAID_OFF).
  - added: plan_table_DELETE_without_payments_is_illegal_from_COMPLETED_REFUNDED_and_PAID_OFF.
  - rewritten: interpretation_3_delete_of_a_COMPLETED_plan_is_refused_ILLEGAL_TRANSITION_without_payments (was: allowed).
  - added: INV_14_confirm_of_an_entry_due_tomorrow_is_refused; INV_14_confirm_of_an_entry_due_today_or_earlier_is_allowed; INV_14_confirm_fails_closed_when_today_is_unknown; INV_14_link_of_a_future_due_entry_stays_allowed.
  - changed: AE16_... (SKIP step now supplies USER_SKIP_WAIVED); entry_table_SKIP_of_a_linked_scheduled_entry_is_illegal_... (supplies a reason so the linked path is what is tested); INV_21_the_last_scheduled_entry_resolving_by_confirm_or_skip_... (CONFIRM supplies today, SKIP supplies a reason).
  - replaced: R4_a_currency_or_type_change_cancels_with_its_own_bounded_reason_in_a_terminal_plan is now R4_..._cancels_with_EXPENSE_REMOVED_in_a_terminal_plan; R4_..._reopens_a_paid_entry_in_an_active_plan_... no longer loops over detach reasons.
  - added: R4_there_are_no_separate_currency_changed_or_type_changed_reason_codes.
  - removed (input no longer exists, replaced by the R4 codes test): pin_expense_removed_with_an_unrelated_detach_reason_is_refused.
  - changed: interpretation_4_skip_reason_codes_are_exactly_the_three_codes_of_OQ_20 (was the two codes; also checks isUserSkipReason).
  - replaced: interpretation_4_the_skip_transition_returns_the_supplied_user_skip_reason (was: Move with no reason).
  - added: interpretation_4_a_skip_without_a_reason_or_with_a_non_user_reason_is_refused; interpretation_4_a_retry_of_a_skip_on_an_already_skipped_entry_is_AlreadyInState_whatever_the_reason.
  - changed: INV_32_mark_over_plan_status_for_an_eligible_scheduled_entry_only_ACTIVE_may_mark (COMPLETED + SCHEDULED now pinned to the exact illegalEntry).
  - added: INV_32_mark_retry_over_plan_status_for_an_already_marked_entry_is_idempotent_in_ACTIVE_and_COMPLETED_and_illegal_in_terminal_plans; INV_32_AE27_a_mark_retry_after_the_mark_completed_the_plan_reports_AlreadyInState; INV_32_a_revert_retry_has_the_same_idempotency_...
  - rewritten: INV_32_bulk_mark_on_a_COMPLETED_plan_is_refused_when_an_entry_needs_a_move (now exact ILLEGAL_TRANSITION, incl. a mixed already-marked plus needs-move selection).
  - added: INV_32_AE27_bulk_mark_retry_on_a_COMPLETED_plan_with_every_entry_already_marked_is_allowed_with_nothing_to_write.
- schedule/InstallmentScheduleCalculatorTest.kt
  - changed: INV_11_floating_point_representation_error_does_not_leak_into_minor_units (asserts 1234.57).
  - added: interpretation_1_a_schedule_whose_last_due_date_is_after_2200_12_31_is_refused_INVALID_DATE (first due 2200-12-15 N=2; first due 2199-12-31 N=24); interpretation_1_control_a_schedule_whose_last_due_date_is_inside_the_range_is_accepted (first due 2200-11-15 N=2, last 2200-12-15).
- readmodel/InstallmentPlanMathTest.kt
  - changed: INV_11_aggregation_does_not_depend_on_floating_point_sums (literals).

S6a carry-forwards:
- The coordinator stores InstallmentMinorUnits.fromMinor(entries[0].amountMinor) and fromMinor(totalMinor) on the plan, never the raw input amount (INV-11).
- The coordinator must decide auto-record through autoRecordDecision(context, hasCandidateCharge = ...), never by testing the entry itself (INV-13). CONFIRM and EXPENSE_REMOVED callers pass todayEpochDay (CONFIRM) and no detach reason; SKIP callers pass a USER_SKIP_* skipReason.
- ExpenseScheduleRelations (and the entry-to-expense link lookups) must be filled from links of any entry status, including PAID and REVERSED.
- PurchaseMoney and LinkedExpenseMoney: a stored value >= 10^9 must map to Unavailable, never to 0.
- S6a test: a STANDALONE plan is FIXED_PAYMENT only (the coordinator enforces it; the calculator does not, interpretation 7).
- Consider adding `kind` (purchase-attached or standalone) to InstallmentScheduleRequest so a purchase-attached call with a null purchase date fails closed in the calculator instead of relying on the coordinator.
- The plan-level Delete result now implies status is ACTIVE or CANCELLED; the coordinator must not special-case COMPLETED, REFUNDED or PAID_OFF.

Round-2 minors (after the second review; source and S5 tests only, no builds, tests or guards run):
1. Calculator order (spec 7.0, first failing rule wins): the date rule, including the last generated due date (INVALID_DATE), now runs directly after the input-date check and before FIRST_DUE_BEFORE_PURCHASE. KDoc order list updated. AE22_validation_order_... gained three cases (first due 2200-12-15: purchase after first due with N=2; total 0.01 with N=2; paidBeforeTracking 99), all INVALID_DATE.
2. InstallmentPlanTransitions.expenseRemoved: the reopened SCHEDULED entry (ACTIVE or COMPLETED plan) now carries reason = null, same as UNLINK; only the CANCELLED target records EXPENSE_REMOVED. Pinned with exact-equality assertions (no normalized()): new entry_table_EXPENSE_REMOVED_reopening_a_paid_entry_carries_no_reason_exact (ACTIVE and COMPLETED), plus exact assertions added to AE11_... and R4_..._reopens_a_paid_entry_in_an_active_plan_...
3. INV_32_confirm_and_link_on_a_PAID_BEFORE_TRACKING_entry_are_illegal: the context now has today = dueToday, so the PAID_BEFORE_TRACKING rule is what is tested for CONFIRM.
4. The duplicate R4_a_currency_or_type_change_cancels_with_EXPENSE_REMOVED_in_a_terminal_plan was merged into entry_table_EXPENSE_REMOVED_in_a_terminal_plan_cancels_with_the_bounded_reason_EXPENSE_REMOVED (same assertions, spec 3.2 / R4 comment moved there).

Round-2 carry-forward:
- S6a coordinator test: a currency change and a type change of a linked expense both run the EXPENSE_REMOVED trigger.
- Follow-up for ux-flow-designer at S10b: docs/features/loan-installments/ux-flows.md ~:1360 still says the spec names only USER_SKIP_LENDER_HOLIDAY; OQ-20 has three codes (USER_SKIP_LENDER_HOLIDAY, USER_SKIP_WAIVED, USER_SKIP_OTHER).

## S5 DONE (2026-10-23)
- Reviews: reviewer-strict round 1 REJECT (2 MAJOR + 5 MINOR) → fixed; round 2 ACCEPT-WITH-MINORS → round-2 minors fixed; architecture-guardian (light) ACCEPT-WITH-MINORS.
- Spec amended by domain-modeler ("[amended S5-R, 2026-10-23]" tags, amendment log near the top of domain-spec.md).
- OWNER CONFIRMATION PENDING: delete source set = ACTIVE or CANCELLED without PAID/REVERSED entries (fail-closed reading of §7.5; REFUNDED excluded because delete clears the purchase flag; COMPLETED excluded — note a plan completed by declaring every entry paid-before-tracking must revert one entry before delete). Asked 2026-10-23.
- Validation (final tree): targeted *Installment*/*ExpenseScheduleLinkPolicy*/*StandalonePlanDefaultCategoryPolicy* vr-20261023-164006-1e1f1263 PASS; domain-a-m vr-20261023-164438-a709b4a2 PASS; static-guards vr-20261023-164541-383ecc23 PASS. domain-n-z vr-20261023-154354-46dd2b96 PASS on the pre-fix tree (domain/schedule unchanged since; ExpenseScheduleLinkPolicyTest re-ran in the final targeted run). One earlier static-guards run vr-20261023-154542-e7b062db was STALE (fix edits during the run) and is superseded.
- S6a carry-forwards (added by the light AG): add a self-check that domain/schedule/ExpenseScheduleLinkPolicy.kt counts as plan code, an unrelated domain/schedule/X.kt does not, and an injected ExpenseDao line in the policy file is flagged; name RecurringLifecycleCoordinator (S6c) as a legitimate ExpenseScheduleLinkPolicy caller in the plan line-272 wording once callers exist; callers of recordTimestampMillis must check isSupportedDay first or map the IllegalArgumentException to a bounded refusal; plan A9 line ref ~:627-631 is now ~:796-801.
Next: S2b (still waits on the owner IM-9 backup-redaction answer) or S3 (TLC spending-inclusion op; must copy spendingExclusionReason on updateExpense, A10).

## S3 (2026-10-23) — implemented (uncommitted; no builds, tests or guards run; two implementer runs, the second reviewed and finished)
- Files (source):
  - data/database/query/SpendingInclusionSql.kt (SPENDING_PREDICATE and _E alias, EFFECTIVE_AMOUNT and _E);
  - data/database/entity/Expense.kt (effectiveAmount / normalizedEffectiveAmount return 0.0 when flagged; new countsAsSpending);
  - data/database/dao/ExpenseDao.kt (only updateSpendingExclusionReason(expenseId, reason), @RestrictedExpenseDaoMutation);
  - domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt (setSpendingExclusionDbOnly; failIfInstallmentLocked in updateType, updateTypeAndTransferDetails, updateOwnershipDbOnlyV2; INV-22 probe in updateExpense; A10 copy);
  - domain/transaction/lifecycle/{SpendingInclusionResult, InstallmentLockedExpenseException, TransactionUpdateKind (+SPENDING_INCLUSION), TransactionSideEffectPlanner (budget recheck only)};
  - domain/transaction/ExpenseSource.kt (+INSTALLMENT_PLAN, last; TLC source `when` extended);
  - config/guards/db_ownership_policy.yml: two TLC rows for setSpendingExclusionDbOnly (expenseDao.updateSpendingExclusionReason and transactionEventDao.insert), barrierMode helper, parameterTypes Long / com.yourname.expensetracker.domain.transaction.SpendingExclusionReason? / String / String?, placed after the deleteExpense rows (alphabetical by method within the TLC block);
  - docs/architecture/LEGAL_PATHS.md (Expense Mutations / installment spending-flag writer).
- A10: updateExpense builds finalExpense = convertedExpense.copy(spendingExclusionReason = existing.spendingExclusionReason), existing read in the same transaction. Stale-save regression tests: both directions (stale null copy cannot reset a new flag; stale flagged copy cannot restore a cleared flag).
- Op contract: returns MutationResult of SpendingInclusionResult.Updated / NoOp / NotFound (typed, never throws for those, so a caller-held transaction is not marked for rollback). NoOp (same value) writes no row and no event. One UPDATED event, source INSTALLMENT_PLAN by default, metadata updateKind SPENDING_INCLUSION, reason = flag name or constant SPENDING_INCLUSION_CLEARED. Snapshots, metadata and reason are bounded (id + flag only; no amount, merchant, notes); the event row keeps the expense dedupeKey like every UPDATED event.
- D-10 finding (feasible; OD-15 not needed): DatabaseWriteBarrier.runWrite is re-entrant (InFlightWriteKey context check, :58) and Room withTransaction joins an open transaction, so the DbOnly op inside a caller-held `writeBarrier.runWrite { database.withTransaction { } }` commits or rolls back with the caller. The caller must run MutationResult.postCommitActions only after its outer transaction commits. Pinned by tests (joins, rolls back with caller, NotFound/NoOp do not roll back caller).
- D-14 finding (no migration needed): ExpenseSource and TransactionUpdateKind are persisted by NAME, never by ordinal. Expense.source is plain TEXT (Expense.spendingExclusionReason also TEXT); Converters has no converter for either enum; an update kind is never persisted (it only goes in event metadata JSON). Unknown-value behaviour is per consumer: JsonExpenseImporter wraps valueOf in runCatching and falls back to CSV_IMPORT; SourceLinkBackfillWorker.mapLegacySourceToEnum wraps valueOf in runCatching then maps legacy names; SourceLearningPolicy string overload catches IllegalArgumentException and returns untrusted (false); INSTALLMENT_PLAN is untrusted for learning (not in the trusted set). New values are appended last.
- Duplicate-merge lock: TLC has no duplicate-merge operation today (no merge path writes to an existing expense), so the "merge refused" clause of INV-22 has nothing to guard and no test; the AE-6 replay test pins that a flagged row stays a BLOCKING dedupe target (createExpenseDbOnlyV2 replay returns DuplicateSkipped and the flag survives, plus a direct findBlockingDuplicateIdCurrencyAware probe). If a merge op is added later, it must call failIfInstallmentLocked.
- Tests (all complete and syntactically closed on review):
  - domain/transaction/lifecycle/TransactionLifecycleCoordinatorSpendingInclusionTest.kt: set, clear, replace purchase by reversed, NoOp, NotFound, event kind and privacy content, outer-transaction join / rollback / typed results, AE-20 (amount, currency, type, not-mine, shared refused with nothing changed; merchant and category accepted with flag intact), unflagged unchanged, lock ends on clear, A10 x2, AE-6 replay, INV-8 probe;
  - data/database/SpendingInclusionParityTest.kt: 3 reasons x isNotMine x 3 share variants over a real in-memory Room; SQL SUM / COUNT / AVG (plain and aliased) vs Kotlin effectiveAmount / countsAsSpending; row-by-row; predicate text pin; flagged normalized amount; Room text round trip;
  - data/database/converter/InstallmentEnumValuesConvertersTest.kt: existing values keep order and new ones are last, name round trips, TEXT column type, unknown name behaviour, learning policy, update-kind helpers, reason names.
- Pins changed: none needed. A repo-wide search found no existing test or main code enumerating ExpenseSource.entries/values or TransactionUpdateKind.entries/values (only the new converter test); the only exhaustive `when` over ExpenseSource (TLC source mapping) and over TransactionUpdateKind (planner, helpers) were extended in source. The GR-13 mediation inventory docs and expected_tuples.txt are not read by any guard script and were not touched; db_artifact_sync does not read db_ownership_policy.yml (its inputs are legacy + seeds + candidate/accounting), so those were not touched.
- Deviations: none from the plan; the duplicate-merge clause is vacuous (see above). `return@withTransaction` with a non-Unit last expression follows the existing updateMerchant pattern.
- Suggested validation (validation-runner, serial): targeted `*SpendingInclusion*`, then `*TransactionLifecycleCoordinator*`, then `*Converters*` (covers InstallmentEnumValuesConvertersTest); shards `domain-n-z` and `data`; `static-guards`; registered-guard `db_access` (verifies the two new policy rows and that no other caller writes the column). Then reviewer-strict (money, lifecycle) plus architecture-guardian (FG-14) and privacy-security (event content).
- Residual risk: db_access policy row parameterTypes spelling for the nullable enum was inferred from existing `FQN?` rows; if db_access reports a signature mismatch, fix the row text only. Callers of Expense.copy elsewhere that build whole rows are outside TLC and unchanged (create paths start NULL).

### S3 review fixes (2026-10-23; reviewer-strict, architecture-guardian, privacy-security all ACCEPT-WITH-MINORS; no builds, tests or guards run)
1. Failing test fixed: `clear removes the flag and writes a second UPDATED event` now verifies planUpdated exactly twice (set + clear).
2. dedupeKey wording corrected (TLC KDoc, LEGAL_PATHS SPENDING INCLUSION entry, this section): snapshots, metadata and reason are bounded; the event row keeps the expense dedupeKey like every UPDATED event. Behaviour unchanged; a test pins event.dedupeKey == existing.dedupeKey.
3. Orphaned KDoc fixed: failIfInstallmentLocked doc and failIfGroupLinkedForEdit (R18) doc are now each directly above their function.
4. Tests added: updateTypeAndTransferDetails type change refused on a flagged row; updateOwnershipDbOnlyV2 called directly with shared=true on a flagged row refused; real DatabaseWriteBarrier (mode NORMAL) running runWrite { db.withTransaction { setSpendingExclusionDbOnly } } proves nested re-entrancy and the inFlight count returns to 0.
5. Ownership lock aligned with updateExpense and INV-22: updateOwnershipDbOnlyV2 now locks only on isNotMine, isSharedExpense, mySharePercentage, myShareAmount (compared on the normalized value); a name-only change (ownerName / sharedWithName) on a flagged row is allowed on both paths. Tests added for both paths. LEGAL_PATHS lock line updated.
6. InstallmentLockedExpenseException KDoc: "would merge a duplicate into it" replaced by "any future duplicate-merge operation must throw it too".

S6a carry-forwards from the S3 reviews:
- (a) Linking a flagged expense via GroupTransactionCoordinator (~:530, updateOwnershipDbOnlyV2) throws InstallmentLockedExpenseException out of the group transaction (fails closed, unmapped). S6a must refuse declaring a plan on a group-linked purchase, and UI callers must map the exception.
- (b) GroupTransactionCoordinator.deleteGroupAtomic (~:989) calls expenseDao.clearSharedExpenseFlags outside TLC, bypassing INV-22 for a group-linked flagged purchase. S6a decision needed.
- (c) setSpendingExclusionDbOnly `source: String` should be typed ExpenseSource or bounded when callers arrive.
- (d) Receipt matching uses effectiveAmount (ReceiptMatchingViewModel ~:122); check flagged purchases in the S4d/Q13 audit.
- (e) OWNER item (not done): scripts/verify_source_provenance_boundaries.py EXPECTED_EXPENSE_SOURCES lacks INSTALLMENT_PLAN; guard strengthening needs owner approval.

## Owner decisions 2026-10-23 (verbatim: "follow your reccomendations")
- IM-9 APPROVED: every BackupPrivacyMode with redactsRawText=true, and ANONYMIZED_EXPORT, blanks plan displayName and lenderLabel. S2b is unblocked.
- Delete source set CONFIRMED: ACTIVE or CANCELLED without PAID/REVERSED entries (spec "OWNER CONFIRMED 2026-10-23").
- Guard strengthening APPROVED and applied (S3 carry-forward e): INSTALLMENT_PLAN added to EXPECTED_EXPENSE_SOURCES in scripts/verify_source_provenance_boundaries.py.
Record: Cost-agregator/build/release-lead-notes/owner-approvals-20261022.md items 11-14.

## S3 DONE (2026-10-23)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS, architecture-guardian ACCEPT-WITH-MINORS, privacy-security-guardian ACCEPT-WITH-MINORS; review fixes applied (see "S3 review fixes").
- Validation (final tree): targeted vr-20261023-182722-3310a053 PASS; db_access vr-20261023-183326-c3e394a5 PASS; domain-n-z vr-20261023-183557-acfe2970 PASS; data vr-20261023-183743-e6eeea1b PASS; static-guards vr-20261023-190844-30c565ec PASS. Earlier runs: targeted vr-20261023-175224-cecfaaf8 FAIL (test verified planUpdated exactly=1 after set+clear; fixed); static-guards vr-20261023-184132-8a7c8e80 FAIL (db policy count pins 430 → 432 in scripts/test_verify_db_access_boundaries.py, orchestrator edit inherent to the two approved rows; merge note on lanes-board).
- Owner-approved guard strengthening applied: INSTALLMENT_PLAN in EXPECTED_EXPENSE_SOURCES.
Next: S2b (backup orphan checks, manifest, privacy mode per IM-9 approval, wipe, rescue; A10 S2b carry-forwards), then S4a–S4d.

## S2b (2026-10-23) — implemented (uncommitted; lane Cost-agregator-mec; no builds, tests or guards run by implementers)
- Files (source):
  - data/backup/BackupVerifier.kt: five installment semantic orphan checks (entries to missing plans; entries linked to missing expenses; plans whose purchase expense is missing; expenses flagged INSTALLMENT_PURCHASE that no plan references; expenses flagged INSTALLMENT_REVERSED that no REVERSED entry references). Messages are fixed, count-only (INV-31: no names, amounts or dates). Both installment tables are TIER_1_EXACT in TABLE_TIERS, gated by databaseVersion 152. Stale "57 table names" KDoc on allTableNames fixed (comment only).
  - data/privacy/ExportAnonymizer.kt: new sanitizeLoanPlanLabels sets installment_plans.displayName to '' (NOT NULL) and lenderLabel to NULL (IM-9), rows and structure retained so exact-count verification still matches; addressed by SQL string only; skipped when the table is absent; part of the single sanitize transaction.
  - data/rescue/FinancialRescueCoordinator.kt and FinancialRescueSnapshot.kt: COPY, not drop. The snapshot carries installmentTables (installment_plans then installment_entries, read column by column) and the expense spendingExclusionReason; import copies rows verbatim (same ids and values, INSERT OR REPLACE, only columns the fresh table has) and skips entries whose plan was not imported so the entry FK cannot fail the rescue. Only counts are logged.
- Test pin edits: BackupVerifierRequiredSemanticQueryTest now lists installment_entries and installment_plans in its table list and adds the column stubs for the columns the five checks read (entries: planId, linkedExpenseId, status; plans: purchaseExpenseId).
- Tests added: BackupVerifierInstallmentOrphanTest (AE-12 fixture, schema 152, real Room-created database), BackupPrivacyModePlanLabelsTest (IM-9).
- D-2 finding: there is no app-level wipe path and no per-table wipe list. Data removal is Room-wide or file-level database deletion, which covers the new tables automatically. No wipe test is needed.
- D-5 finding: the backup manifest tableCounts are produced from BackupVerifier TABLE_TIERS (requiredManifestTables), which includes both installment tables as TIER_1_EXACT. Stale "57 tables"/"57 table names" comments fixed: BackupVerifier.allTableNames KDoc, and three comments in DatabaseBackupRepositoryImplTest (two "expects 57 tables" remarks now say every tracked table; the helper KDoc now says 61 tables, see TABLE_TIERS). The BackupVerifier TABLE_TIERS block comment (61 = 42 exact + 10 validity + 9 optional) was already correct.
- Probe validation: vr-20261023-203023-4d51c351 FAIL, exactly two tests in BackupVerifierInstallmentOrphanTest; installment_cloud_reachability vr-20261023-203527-ea4b6ba7 PASS.
- Two test fixes (after the probe FAIL):
  1. AE12_p2_plan_row_removed...: the fixed-message regex rejected the hyphen in "non-existent"; the character class is now [A-Za-z_ -] (hyphen last). Intent kept: letters, spaces, underscores, hyphens and exactly one number (the count).
  2. expense_flagged_as_reversed...: reopening the file with a second mutate after BackupVerifier.verify threw SQLiteCantOpenDatabaseException under Robolectric. Split into two tests with no assertion dropped: expense_flagged_as_reversed_without_a_reversed_entry_is_reported (flag expense 2 while the entry is PAID gives exactly one unreferencedReversedFlag error and not passed) and a_reversed_entry_satisfies_the_reversed_expense_flag (a fresh AE-12 database, both mutations in one mutate before a single verify: no installment errors and passed).
- IM-9 coverage check (BackupPrivacyModePlanLabelsTest vs production): the test enumerates BackupPrivacyMode.values() filtered by redactsRawText (not a hardcoded subset), asserts ANONYMIZED_EXPORT is among them, blanks each, and a source-text test pins the caller decision. Production: DatabaseBackupRepositoryImpl.kt:641 resolvedRedacted = privacyMode?.redactsRawText ?: redacted, and :757-758 calls exportAnonymizer.sanitizeExport(tempDb) whenever resolvedRedacted, so every redactsRawText mode is sanitised; the other caller at :478 (encrypted export flow) sanitises unconditionally. No mode bypasses ExportAnonymizer. Note: the test models the repository decision rather than driving createCostBackup end to end.
- Suggested validation (validation-runner, serial): targeted `*BackupVerifier*`, `*BackupPrivacyModePlanLabels*`, `*ExportAnonymiz*`, `*FinancialRescue*`; shard `data`; `static-guards`. Then reviewer-strict (privacy, Room/backup, lifecycle) plus privacy-security-guardian.

### S2b fix round (after validation FAIL + reviews; no builds, tests or guards run by implementers)
- A. db_access guard: DB_FORBIDDEN_STRUCTURAL_OPERATION flags `execSQL` in any method not listed in config/guards/db_structural_exceptions_expected_methods.yml (config untouched). Both new writers now use typed APIs that the guard does not flag (same pattern as the existing nullifyColumnsWhereSet): ExportAnonymizer.sanitizeLoanPlanLabels uses `SQLiteDatabase.update(table, ContentValues, where, null)`; FinancialRescueCoordinator.importInstallmentTables uses `SupportSQLiteDatabase.insert(table, CONFLICT_REPLACE, ContentValues)` (a -1 result throws a bounded `RESCUE_INSTALLMENT_INSERT_FAILED`, rolling back the import transaction, as the old execSQL did).
- B. DatabaseBackupRepositoryImplTest.createFullSchemaDatabase: minimal per-table columns added for expenses (spendingExclusionReason), installment_plans (purchaseExpenseId, displayName, lenderLabel), installment_entries (planId, linkedExpenseId, status). createSqliteDatabase creates only 5 tables and never reaches BackupVerifier.verify with all tables, so it needs no change.
- C. Rescue: (1) clearOrphanInstallmentPurchaseFlags runs in the same import transaction right after importInstallmentTables (also when installmentTables is empty): INSTALLMENT_PURCHASE flags not referenced as a non-null purchaseExpenseId by any imported plan are set to NULL; count-only log. INSTALLMENT_REVERSED orphans are deliberately NOT cleared. (2) importedPlanIds is rebuilt from `SELECT id FROM installment_plans` after the plans loop (surviving rows). Tests added in FinancialRescueInstallmentTest (orphan purchase flag cleared, referenced flag kept, orphan REVERSED kept, cleared with no installment tables).
- D. ExportAnonymizer.sanitizeLoanPlanLabels KDoc corrected: it also runs unconditionally from the encrypted export path (DatabaseBackupRepositoryImpl ~:478), not only for redactsRawText modes.
- Carry-forward for S6a/S6c (MAJOR, room-migration-guardian): the five BackupVerifier orphan checks block backup creation, but the spec allows transient orphan states until reconcile (section 5.4 R1/R2/R3/R5; post-commit TLC reaction). BEFORE S6c ships choose one: (a) run reconcile R1-R5 under the backup barrier before the snapshot, (b) a same-transaction delete reaction, or (c) an explicit owner decision to keep the backup fail-closed.
- Carry-forward for S5/S6 rules: R2/CANCELLED must null linkedExpenseId; REVERSED keeps linkedExpenseId (check 5 relies on it). Later UI must show a fallback for displayName '' after restoring a redacted backup. The JSON safety snapshot omits installment data (deliberate, privacy).
- OWNER DECISION PENDING (only matters once RescueConfig.ENABLE_FINANCIAL_RESCUE is turned on): orphan INSTALLMENT_REVERSED flags after rescue. Keep (current: money-correct, but a backup of the rescued DB fails check 5) versus clear (verifiable, but the refunded purchase counts as spending again).
- Note: the framework SQLiteDatabase.insert logs the failing ContentValues on error (plan labels could reach logcat in a failure case); the old execSQL path did not. Residual risk, rescue only.

## S2b DONE (2026-10-24)
- Reviews: room-migration-guardian ACCEPT-WITH-MINORS (MAJOR carry-forward for S6c, now decided — see below), reviewer-strict ACCEPT-WITH-MINORS (rescue minors fixed: orphan purchase flags cleared, surviving-plan set). privacy lane: IM-9 path verified by room-migration-guardian + scout (every redactsRawText mode and ANONYMIZED_EXPORT, plus the encrypted export path, blank displayName/lenderLabel inside the sanitize transaction).
- Validation (final tree): targeted vr-20261023-215758-820a81bb PASS; db_access vr-20261023-220317-d4cc0c0b PASS; data vr-20261023-220548-3fcccaa5 PASS; static-guards vr-20261023-220921-de3e7fbd PASS; installment_cloud_reachability vr-20261023-203527-ea4b6ba7 PASS. Earlier FAIL runs (fixture schema lacked the checked columns; execSQL flagged by db_access → typed update/insert APIs) superseded.
- OWNER 2026-10-24 (owner-approvals items 17, 18):
  - S6c MUST run plan reconcile R1–R5 under the backup barrier before the snapshot verify (S6a exposes the reconcile entry point), so a backup right after an expense delete/update never fails on a not-yet-reconciled plan; genuine unrepairable orphans still fail closed.
  - Rescue clears orphan INSTALLMENT_REVERSED flags too (verifiable rescued DB; accepted cost: in that corrupted-source case the refunded installment counts as spending again). Follow-up commit next.
Next: rescue REVERSED follow-up (small), then S4a–S4d (canonical spending predicate rollout).

## Rescue reversed-flag follow-up (2026-10-24)
- Owner decision (owner-approvals item 18) implemented, uncommitted; no builds, tests or guards run by the implementer.
- data/rescue/FinancialRescueCoordinator.kt: clearOrphanInstallmentPurchaseFlags now also clears INSTALLMENT_REVERSED on expenses whose id is not a non-null linkedExpenseId of an installment_entries row with status REVERSED. Same import transaction, right after the purchase-flag clear, also when installmentTables is empty; typed `SupportSQLiteDatabase.update` (no execSQL); counts-only logs. KDoc documents the accepted cost (corrupted-source refunded installment counts as spending again).
- FinancialRescueInstallmentTest: the test that asserted orphan REVERSED expense 5 stays flagged is renamed rescue_import_clears_orphan_purchase_and_reversed_flags_but_keeps_referenced_flags and now asserts expense 5 is NULL (owner-approved behaviour change). Fixture adds expense 6 (REVERSED flag) plus entry 72 (REVERSED, linked to 6): the referenced flag is kept; entry counts 3 to 4 (snapshot) and 2 to 3 (plan 7 after import); the no-tables test also asserts zero REVERSED flags remain.
- Supersedes the S2b "OWNER DECISION PENDING" and "INSTALLMENT_REVERSED orphans are deliberately NOT cleared" notes. Plan A11 records the S6c reconcile-under-barrier requirement.
- Suggested validation: targeted `*FinancialRescue*`; db_access; static-guards.
- Validation: targeted *FinancialRescue* vr-20261023-224420-a1b8b192 PASS; db_access vr-20261023-224809-f9cabb17 PASS. Committed.

## S4a (2026-10-24) — implemented
- Uncommitted; lane Cost-agregator-mec; no builds, tests or guards run by the implementer. Binding spec: plan A12.1/A12.2/A12.5.
- Source (data/database/dao/ExpenseDao.kt only, token-level): exactly 15 queries, token `isNotMine = 0` to `${SpendingInclusionSql.SPENDING_PREDICATE}` (qualified form) plus the class import `com.yourname.expensetracker.data.database.query.SpendingInclusionSql`. SELECTs untouched. Converted: getCategorySpentInPeriod, getCategorySpentInPeriodFlow, getCategorySpentTotalsInPeriod, getEffectiveSpentBetweenForCategory, getTotalSpentBetweenByCurrency, getCategoryTotalsBetweenByCurrency, getMerchantTotalsBetweenByCurrency, getMonthlyTotalsBetweenByCurrency, getAllSpentBetweenByCurrency, getAllCategoryTotalsBetweenByCurrency, getAllMerchantTotalsBetweenByCurrency, getAllMonthlyTotalsBetweenByCurrency, getMonthlySpendingTotals, getMonthlySpendingTotalsBetween, getMonthlySpendingTotalsByCategoryBetween.
- Precedence: every site sits in a plain AND chain, so the AND-containing predicate needs no parentheses. getEffectiveSpentBetweenForCategory has `AND <predicate>` followed by `AND ((:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId)`; its OR is already fully parenthesized, so it is unaffected.
- Deliberately NOT touched (same file): getDepositTotalsBetweenByCurrency (deposit sum), getTotalSpentBetween 1546 and every S4b/S4c/S4d query, dedupe families, lists.
- Tests (new, test-only): data/database/dao/ExpenseDaoSpendingPredicateFixture.kt (real in-memory Room via AppDatabaseTestFactory + real TransactionLifecycleCoordinator wired like the S3 test; flags only via coordinator.setSpendingExclusionDbOnly asserting Updated) and ExpenseDaoSpendingPredicateBudgetTest.kt: (1) neutrality, verbatim pre-edit SQL run through the raw handle vs the converted DAO method over a 216-row NULL-flag fixture (isNotMine x share x type x currency x 6 date buckets incl. exclusive-end boundary); (2) flagged case, 2 INSTALLMENT_PURCHASE + 1 INSTALLMENT_REVERSED rows, converted(full) equals pre-edit over a reference DB without those rows, total drops by 1150.00 and COUNT by exactly 3; AE-1 aggregate (2027-03 = 0.00, 2027-04..2028-03 = 50.00 on 5 month queries; range totals 600.00 / 12 rows), AE-6 (no 600/650 month; March category views 0), AE-24 (Electronics 2027-04 = 110.00; March excludes 600.00).
- Deviations: none from A12. The pre-edit SQL in the test interpolates ExpenseDao.EFFECTIVE_AMOUNT_SQL / SPENDING_TYPE_SQL constants (unchanged) and binds named parameters as literals; the two getEffectiveSpentBetweenForCategory variants (category, uncategorized) are separate cases. Class-level @Suppress("DEPRECATION_ERROR") covers the ERROR-deprecated callers.
- Validation to request per A12.7 (serial): (1) targeted `*ExpenseDaoSpendingPredicate*,*SpendingInclusionParity*,*TransactionLifecycleCoordinatorSpendingInclusion*,*ExpenseDaoTest*,*ExpenseRepositoryAssistantSqlGolden*,*ExpenseRepositoryAmountSemanticsSql*,*ComparableHomeValueSqlParity*`; (2) registered-guard db_access (watch DB_ROOM_QUERY_UNCLASSIFIABLE: STOP if it fires); (3) shard data; (4) shard integration-golden. Gates: architecture-guardian + reviewer-strict.

## S4a DONE (2026-10-24)
- Reviews: reviewer-strict ACCEPT (no findings; verified exactly 15 token swaps vs dc22c7ee, verbatim pre-edit neutrality SQL, AE-1/6/24 spec-correct, golden shard exercises MultiCurrencyRepository over Room); architecture-guardian ACCEPT-WITH-MINORS.
- Validation: db_access vr-20261024-000018-2eeedca7, targeted vr-20261024-000249-0806df0d, data vr-20261024-001607-31f29c7a, integration-golden vr-20261024-001924-cdaa6450 — all PASS on the same worktree fingerprint.
- S4b carry-forwards (test hygiene): add function/class-level @OptIn(RestrictedExpenseDaoMutation::class) to ExpenseDaoSpendingPredicateFixture.insert (never @file:OptIn); optional: a flagged shared/USD/uncategorized row case; if a later slice changes EFFECTIVE_AMOUNT_SQL/SPENDING_TYPE_SQL, freeze the pre-edit SQL copies. Merge note: main checkout's uncommitted ExpenseDao.kt additions are identical to blocks already on this branch (3-way merge clean with normalized line endings); commit them on feature/ai-evolution before merging.
- Owner-visible (pre-existing, outside S4a): FinancialRescueCoordinator :214 writes spendingExclusionReason on the rescue path (second writer, rescue disabled) — confirm LEGAL_PATHS lists it under restore.
Next: S4b (dashboard totals, 11 queries per A12.2).

## S4b (2026-10-24) — implemented
- Uncommitted; lane Cost-agregator-mec; no builds, tests or guards run by the implementer. Binding spec: plan A12.1/A12.2/A12.5.
- Source (data/database/dao/ExpenseDao.kt only, token-level, import already present): exactly 11 queries, token `isNotMine = 0` to `${SpendingInclusionSql.SPENDING_PREDICATE}`; getCategoryBreakdown (alias e) `e.isNotMine = 0` to `${SpendingInclusionSql.SPENDING_PREDICATE_E}`. SELECTs untouched. Converted: getTotalSpentFlow (single line), getTotalSpentBetween, getCategoryTotalsBetween, getPurchaseCount (W-only COUNT), getSpendingDailyTotalsBetween, getTotalForPeriod, getWeeklyTotalsForPeriod, getMonthlyTotalsForPeriod, getDailyTotalsWithDatesForPeriod, getAverageDailySpend (swap inside the subquery), getCategoryBreakdown.
- Precedence: every site is a plain AND chain (`WHERE <SPENDING_TYPE> AND ... AND isNotMine = 0`); SPENDING_TYPE_SQL is a single equality, no OR anywhere, so the AND-containing predicate needs no parentheses. Not touched: getCountForPeriod (ROW_COUNT marker in S4d), getOldestExpenseDate, every S4c/S4d query.
- Tests: new data/database/dao/ExpenseDaoSpendingPredicateDashboardTest.kt reusing the S4a fixture (fixture change: function-level @OptIn(RestrictedExpenseDaoMutation::class) on insert, per S4a carry-forward). (1) neutrality: verbatim pre-edit SQL via raw handle vs converted method over the 216-row NULL-flag fixture, all 11 queries, fails on an empty result; (2) flagged case: INSTALLMENT_PURCHASE x2 + INSTALLMENT_REVERSED x1 via coordinator, converted(full) == pre-edit over reference without those rows, pre-edit result must differ (non-vacuous), total drops by 1150.00 and COUNT by exactly 3 where the query returns them (AVG and the Int count handled by result equality / count only); AE-1 getTotalForPeriod (March 0.00, April 50.00, range 600.00, plus the other single-value and category queries, March-only results empty); AE-6 weekly/monthly/daily (12 buckets of 50.00, no 2027-03 bucket, no 600/650); AE-17 control (extra unflagged 50.00 on 2027-06-11 gives June 100.00, 2 rows, purchase count 13); getPurchaseCount excludes the flagged row (12). getTotalSpentFlow read with first().
- Deviations: none from A12.
- Validation to request per A12.7 (serial), same four steps as S4a with `*ExpenseDaoSpendingPredicate*` covering the new class. Gates: architecture-guardian + reviewer-strict.

## S4b DONE (2026-10-24)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS; architecture-guardian PASS.
- Validation: db_access vr-20261024-010554-be199332, targeted vr-20261024-010825-bcd9ef44, data vr-20261024-011312-5622e363, integration-golden vr-20261024-011628-fe26c5ce — all PASS.
- Wording correction (reviewer-strict ISSUE-1): the pre-edit SQL copies in ExpenseDaoSpendingPredicateDashboardTest are verbatim UP TO TRAILING WHITESPACE for getCategoryTotalsBetween and getTotalForPeriod (base has trailing spaces after `FROM expenses ` and `WHERE ${SPENDING_TYPE_SQL} `); SQLite ignores the whitespace, so neutrality holds. The other 9 are byte-identical.
- Optional carry-forwards: AE-1 assertion getCountForPeriod(March) == 1 (ROW_COUNT badge until S4d marker); AE fixture dates on the 10th at noon UTC for closer spec fidelity; flagged shared/USD/uncategorized case.
Next: S4c (analytics/trends/merchant/location, 13 queries per A12.2).

## S4c (2026-10-24) — implemented
- Uncommitted; lane Cost-agregator-mec; no builds, tests or guards run by the implementer. Binding spec: plan A12.1/A12.2/A12.5.
- Source (data/database/dao/ExpenseDao.kt only, token-level, qualified form, import already present): exactly 13 queries, token `isNotMine = 0` to `${SpendingInclusionSql.SPENDING_PREDICATE}`; SELECTs untouched. Converted: getAmountsForPercentileCalc (row-valued), getMerchantTotalsBetween, getCategoryTotalsForPeriod, getMerchantStats, getAllMerchantStats, getTopMerchantsForPeriod, getLargestExpenseForPeriod (row-valued), getLargestExpenseForMerchant (row-valued), getDailyTotalsForPeriod, getRecurringCandidates, getDayOfWeekPattern, getLocatedMerchantTotals, getLocatedMerchantTotalsByCurrency (WHERE swap only; its inline CASE literal and its isNotMine = 1 branch stay). No alias-e site in this batch, so SPENDING_PREDICATE_E is unused. NL RawQuery builder untouched. Total `SpendingInclusionSql.SPENDING_PREDICATE}` references in the file: 38.
- Precedence: every site is a plain AND chain (`WHERE <SPENDING_TYPE> AND ... AND isNotMine = 0 ...`); the only HAVING clause with an AND (getRecurringCandidates) is a separate clause after GROUP BY and is untouched. No OR anywhere, so no parentheses needed.
- Tests: new data/database/dao/ExpenseDaoSpendingPredicateAnalyticsTest.kt reusing the fixture (fixture change: optional `latitude`/`longitude` parameters on insert, default null, needed by the two located queries). (1) neutrality over a 219-row NULL-flag fixture (216 base rows, two thirds located, plus a Rent Co merchant at 80/81/82 so the recurring and per-merchant largest queries are not vacuous); verbatim pre-edit SQL via the raw handle vs the converted method; row-valued queries compare the returned rows (percentile values; largest lookups by expense id); fails on an empty result. (2) flagged case: INSTALLMENT_PURCHASE x2 + INSTALLMENT_REVERSED x1 (600/300/250, located, merchant Rent Co) via the coordinator; converted(full) == pre-edit over the reference without them; pre-edit result must differ; total drops by 1150.00, COUNT by exactly 3, percentile list loses exactly 3 rows; getRecurringCandidates relies on result equality plus the differ check (the flagged rows push Rent Co out of the pre-edit candidates). AE-6 merchant totals and stats (getMerchantTotalsBetween, three MerchantStats queries, both located queries: 12 transactions, 600.00, avg/min/max 50.00, firstDate = the first installment); AE-6 daily and day-of-week (no 600/650 bucket); largest lookups never return E1 (amount 50.00, id differs, March-only is null); percentile list has 12 values of 50.00 and no 600; 12 x 50.00 is a recurring candidate (count 12, max 50.00, E1 excluded).
- Trailing whitespace: the base SQL has single trailing spaces at specific line ends (28 sites in the 13 queries). The tool channel cannot emit trailing spaces, so the test copies carry them as an explicit `${SP}` constant (a single space) at exactly those line ends: the pre-edit copies are byte-identical to the base text, with the source whitespace made visible.
- Deviations: none from A12.
- Validation to request per A12.7 (serial), same four steps as S4a/S4b with `*ExpenseDaoSpendingPredicate*` covering the new class. Gates: architecture-guardian + reviewer-strict.

## S4c DONE (2026-10-24)
- Reviews: reviewer-strict ACCEPT-WITH-MINORS; architecture-guardian ACCEPT-WITH-MINORS.
- Validation: db_access vr-20261024-030839-d4f543ea, targeted vr-20261024-073259-6224a801, data vr-20261024-075927-52b5c748, integration-golden vr-20261024-082428-7ae00ade — all PASS (two results logged by the orchestrator after the queue driver died; result.json files verified).
- Wording correction (both reviewers): besides the 13 token swaps, the edit tool dropped two trailing spaces — getMerchantTotalsBetween (`WHERE ${SPENDING_TYPE_SQL} `) and getRecurringCandidates (`HAVING transactionCount >= 2 `). SQLite-neutral; accepted and recorded (S4b precedent) rather than re-edited after validation. The test's pre-edit copies keep the base trailing spaces via `${SP}` and are byte-identical to fe0a62cc. So the S4c section's "token-level / HAVING untouched / no deviations" claims hold up to these two whitespace trims.
Next: MEC lane stale pin fix (DbGuardPolicyFixtureTest "exactly 430" → 432, found by the ai-fixes final review) + architecture-contracts shard; then S4d (needs owner O1/O2/O3).

## MEC stale policy pin fixed (2026-10-24)
- DbGuardPolicyFixtureTest "exactly 430" → 432 (S3 added setSpendingExclusionDbOnly's two exact identities; the python pin moved in S3, the Kotlin pin was missed). Found by the ai-fixes final review (merge note 11).
- Validation: targeted *DbGuardPolicyFixtureTest* vr-20261024-084335-a1f2f9ce PASS; shard architecture-contracts vr-20261024-084536-487a2c08 PASS.
- Next: S4d (blocked on owner O1/O2/O3).

## S6a part 1 (in progress)
Uncommitted on lane Cost-agregator-mec; no builds, tests or guards run. Source only; tests, read repository, read model and projection port are PART 2.

Files:
- domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt (new; path pinned by ManualEntryCurrencySetterAllowListGuardTest)
- domain/installment/lifecycle/InstallmentPlanOperationResults.kt (new: InstallmentOpResult, InstallmentOpFailure, InstallmentPlanRef, InstallmentEntryRef, InstallmentReconcileReport)
- domain/installment/lifecycle/InstallmentExpenseFactsReader.kt (new read-only port; see deviation 1)
- data/repository/InstallmentExpenseFactsReaderImpl.kt (new; only file touching ExpenseDao, RecurringOccurrenceDao, CategoryDao for plan reads)
- di/InstallmentModule.kt (new; binds the facts reader; the coordinator is a constructor-injected singleton)
- data/database/dao/InstallmentPlanDao.kt (3 CAS methods added; see deviation 2)

Ops done: declareOnExpense, declareWithNewExpense (userPresentRate = UserPresentRateRequirement(entryZone); rate refusal is a normal return before any insert; DuplicateSkipped is a normal-return bounded failure; InsertConflict/Error/other ValidationFailed roll back), createStandalone (forced FIXED_PAYMENT), confirmEntry (TLC STRICT key installment:uuid:kind:seq:generation, replay adopted only if the existing row passes type/currency/flag/link checks), linkExpense, unlinkExpense, skip, unskip, adjustEntry, delete (clears purchase flag via TLC in the same txn), onExpenseChanged, onExpenseDeleted, reconcile R1 (re-flag), R2, R3 (re-flag), R4, R5, R6. Extra (spec 5.2, cheap): markPaidBeforeTracking, revertPaidBeforeTracking.
Not done in part 1: see open questions 1 to 4. Not touched: stop/refund/payOff/editDetails/extend/shorten/changeDueDay (S6b), recordDueEntries (S7a), TLC hooks and backup-barrier reconcile (S6c).

Deviations (all need review):
1. NEW PORT. Plan code may not reference the expense or recurring stores at all (architecture guard checks 2 and 4 match any case of the DAO names), yet declare/link/reconcile must read the purchase and the recurring link. Added InstallmentExpenseFactsReader (domain, read-only) plus an impl in data/repository. Not in the plan write set. Spending type rule = TransactionType.PURCHASE, single definition in InstallmentExpenseFacts.isSpendingType (assumed equal to SPENDING_TYPE_SQL; confirm).
2. THREE NEW DAO METHODS (no schema change): updateEntryScheduleIfScheduled (adjust), updateReversedEntryToCancelledIfLinkedTo (R2 for REVERSED must null the link), updatePlanDetachedFromPurchaseIfLinkedTo (R5). Names keep the update prefix so db_access/arch test see them as writes. Existing DAO methods could not express these (updateEntryStatusIfCurrent refuses PAID and never touches link or amount).
3. PAID -> CANCELLED(EXPENSE_REMOVED) is two CAS steps (releaseEntryLink then SCHEDULED -> CANCELLED), so a cancelled entry also gets recordGeneration + 1 (harmless).
4. COMPLETED plans store terminalAt = now and statusReason AUTO_COMPLETED; reopening clears both (assumption from interpretation 6).
5. Standalone check order: schedule inputs first, then CATEGORY_REQUIRED / CATEGORY_NOT_FOUND (spec 7.0 order). categoryExists means only that the category row exists (no income/spending distinction is available in CategoryDao reads I looked at).
6. Idempotency: a repeat of the same planUuid returns AlreadyInState (declareOnExpense: same purchase only, otherwise ALREADY_DECLARED).
7. Entry zone for declareWithNewExpense also converts the purchase date to its civil date.
8. adjust reason is derived (USER_AMOUNT / USER_DATE / USER_AMOUNT_AND_DATE) rather than passed in.

Open questions:
1. NOT DONE: db_ownership_policy.yml rows for the coordinator (A10) and pin updates (scripts/test_verify_db_access_boundaries.py and DbGuardPolicyFixtureTest counts). Changing guard config is outside implementer rules; orchestrator to assign. The 3 new DAO methods and every coordinator call of the plan DAO writes need rows (deny-by-default per caller).
2. NOT DONE: R1/R3 second halves (expense flagged INSTALLMENT_PURCHASE/REVERSED that no plan references: clear flag). Needs an expense-store scan (a new read query on the expense DAO, plus db_raw_query_classification), which plan code may not do. Options: add a read query to the facts reader impl (ExpenseDao change) or do it in S6c.
3. NOT DONE: getAllPlans in reconcile reads all plans outside the per-plan txns; fine for now, but S6c must call it under the backup barrier (A11).
4. architecture-guardian: confirm the new data/repository reader is an acceptable seam (it is the only way plan code reads expenses without a forbidden DAO reference).
5. domain/installment now imports android.database.sqlite.SQLiteConstraintException (also Timber). Confirm acceptable for the domain package (TLC already uses Timber and org.json).
6. CreateExpenseResult.DuplicateSkipped on the purchase commits TLC's own duplicate event (normal return). If the owner prefers a rollback, change to AbortSignal.
7. Merchant = displayName may be '' after a redacted restore; TLC validation would then fail the confirm with RECORD_FAILED (bounded). UI fallback is a later slice.
Part 2 must also: add LEGAL_PATHS note for the facts reader seam, extend InstallmentPlanArchitectureGuardTest scans to the new files, and test the coordinator (Declare, LinkConfirm, Reconcile tests per the plan).

### S6a part 1 — architecture-guardian review (2026-10-24): ACCEPT-WITH-MINORS (design)
- Orchestrator ACCEPTS deviation 1 (read-only InstallmentExpenseFactsReader port + data/repository impl; reads ExpenseDao.getById / RecurringOccurrenceDao.getByLinkedExpenseId / CategoryDao.getById only; no TLC-owned read) and deviation 2 (3 CAS @Query UPDATEs on InstallmentPlanDao, no schema change). Deviations 3, 4, 6, 7 accepted as-is. DuplicateSkipped keeps TLC's own duplicate event (no rollback).
- Spending type = PURCHASE only (canonical ExpenseDao.SPENDING_TYPE); domain-spec line 651 wording to be aligned (stricter fail-closed reading).
- Part 2 must: fix F1 (reconcile must not drop committed batches), F2 (batches only in Done.postCommit), F3 (KDoc "nothing written" wording), F4 (single hasSplit), F5 (spending-type parity test), F6 (architecture test pins the facts reader read-only + port DAO-free, planted self-checks); add the A10 db_ownership_policy rows + move both pins by the row count; orphan-flag scan for R1/R3 (new read-only ExpenseDao @Query + classification entry, per-orphan writeTxn with in-txn recheck) — orchestrator approves this ExpenseDao read under the same deviation-1 acceptance; real-Room G2-12 and PURCHASE_DUPLICATE_SKIPPED pins + AE-6 rollback; LEGAL_PATHS READS line, CODEBASE_SEGMENTS entries.
- S6c carry-forward (F8): checkWritesAllowed throws outside NORMAL, so reconcile() can't run inside backup maintenance mode — S6c runs reconcile before entering it (verify still fails closed) or stops to ask the owner.

## S6a part 2a — PARTIAL, stopped (2026-10-24, owner asked to stop after running tasks)
- The part 2a implementer died on an API timeout mid-pass. Uncommitted, NOT compiled/validated. Observed state (orchestrator diff check):
  - Landed: F2 (batches only in Done.postCommit), F4 (single InstallmentExpenseFacts.hasSplit), new SpendingExclusionFlagRow + ExpenseDao read @Query (+9 lines) for the orphan scan, ~234 lines of db_ownership_policy.yml rows (26 InstallmentPlanLifecycleCoordinator references).
  - NOT landed / unverified: F1 (reconcile keeps committed batches), F3 KDoc, F10, the orphan-clear writeTxn in the coordinator, db_raw_query_classification decision, pins in scripts/test_verify_db_access_boundaries.py and DbGuardPolicyFixtureTest (untouched — static-guards will FAIL until they move by the exact row count), LEGAL_PATHS/CODEBASE_SEGMENTS/domain-spec docs.
- NEXT ACTION: a "finish" implementer pass that first diffs the tree against this list, verifies each policy row matches an actual enclosing coordinator method (exact parameterTypes), completes the missing items, moves both pins; then part 2b (read repository, read model, projection port) and the test pass; then validation per the review's step 13 and reviews (architecture-guardian, privacy-security-guardian, reviewer-strict).

## S6a part 2a DONE (source) (2026-10-25, finish pass; nothing compiled, run or validated)
Verified against the real tree (the PARTIAL note under-reported: F1, F10 and the orphan clear were already in the coordinator).
- F1: reconcile()/reconcileForExpense() use a ReconcileAccumulator; failed/refused plans and orphans count into plansFailed and the loop continues; committed batches are kept and returned only in Done.postCommit. A CancellationException still propagates and discards the in-memory batches (intended; never caught).
- F2/F4: batches only in Done.postCommit; hasSplit is the single InstallmentExpenseFacts.hasSplit (reader impl, declareOnExpense, declareWithNewExpense pre-check).
- F3: Failed/Refused/RateRefused KDoc says no plan, entry or expense row is written and TLC's own audit events may commit (RateRefused, PURCHASE_DUPLICATE_SKIPPED).
- F10: after Created, declareWithNewExpense re-evaluates the declaration on the stored facts and aborts (AbortSignal Refused) if refused.
- Orphan clear: InstallmentExpenseFactsReader.findFlaggedExpenses() backed by ExpenseDao.getSpendingExclusionFlagRows (read-only SELECT id, spendingExclusionReason WHERE ... IS NOT NULL; row SpendingExclusionFlagRow in data/database/model). clearOrphanFlags() filters to INSTALLMENT_PURCHASE/INSTALLMENT_REVERSED and clears each in its own writeTxn clearOrphanFlag (in-txn re-check: expense exists, flag unchanged, no plan purchaseExpenseId and no entry linkedExpenseId references it) via TLC setSpendingExclusionDbOnly(id, null); report.orphanFlagsCleared.
- db_ownership_policy.yml: 13 rows, all new vs 758d0766 (no coordinator rows existed there). Each row maps to one InstallmentPlanDao write call site (13 call sites, one row each): adjustEntry, applyExpenseRemoved x2, applyReversedExpenseRemoved, claimEntry, delete, insertPlanWithEntries x2, markPaidBeforeTracking, plainEntryMove, reconcilePlan, settlePlanStatus, unlinkExpense; parameterTypes match the Kotlin signatures; no wildcards; none missing, none removed.
- Pins: 432 -> 445 (yml entry count verified 445) in scripts/test_verify_db_access_boundaries.py (both) and DbGuardPolicyFixtureTest (test name, assertion, KDoc, comment line).
- db_raw_query_classification.yml: NOT changed. That classifier covers @RawQuery methods only (file header); the new read is a plain @Query.
- Docs: LEGAL_PATHS (orphan-flag clear + READS block), CODEBASE_SEGMENTS (Segment 40 file entries), domain-spec 7.0 spending type = PURCHASE only.
Left: part 2b (read repository, read model, projection port), F5 spending-type parity test, F6 architecture test (facts reader read-only, port DAO-free, planted self-checks), InstallmentPlanArchitectureGuardTest scan extension, coordinator tests (Declare, LinkConfirm, Reconcile, G2-12, PURCHASE_DUPLICATE_SKIPPED, AE-6 rollback, orphan clear), then validation and reviews. Run static-guards and the DB-guard fixture tests as part of validation (pins moved).
- S4d still blocked on owner O1/O2/O3.

## S6a part 2b (source) (2026-10-25; nothing compiled, run or validated)
Files (all read-only; no writes anywhere):
- domain/installment/readmodel/InstallmentPlanReadModel.kt (new): pure snapshots (PlanSnapshot, EntrySnapshot, CandidateExpense), InstallmentOverview / PlanSummary / PlanDetail / EntryLine / HistoryItem / LinkSuggestion, InstallmentCandidateRule (INV-13), InstallmentPlanReadModel (overview, summary, detail, entryLine, entryVariance, history, suggestionsFor, mayHaveCandidate). Money math delegated to InstallmentPlanMath (Long minor units).
- domain/installment/InstallmentPlanReadRepository.kt (new): observeOverview/Detail/History (Flow) + loadOverview/Detail/History/Suggestions.
- data/repository/InstallmentPlanReadRepositoryImpl.kt (new): InstallmentPlanDao reads + InstallmentExpenseFactsReader + ExpenseScheduleLinkPolicy for suggestion eligibility; today = TimeProvider.now() civil date in ZoneId.systemDefault(); unreadable plans counted, never zero.
- domain/installment/projection/InstallmentProjectionPort.kt (new): port, InstallmentProjectedEntry, pure InstallmentProjectionRule (INV-15). data/repository/InstallmentProjectionPortImpl.kt (new): InstallmentPlanDao reads only.
- di/InstallmentModule.kt: binds read repository and projection port.
- Edited part-1/2a files: InstallmentExpenseFactsReader (+findExpenseMoney, +findExpenseMoneyBetween, +InstallmentExpenseMoney) and its Impl (existing ExpenseDao.getById and getExpensesBetweenUncapped only; NO new ExpenseDao method); InstallmentPlanDao (+observeAllPlans, +observeAllEntries Flow reads; no schema change).
- Docs: LEGAL_PATHS READS block, CODEBASE_SEGMENTS Segment 40.
Decisions and open questions:
1. SUPERSEDED by the orchestrator decisions below (windows are now mode-aware). Original note: INV-13 window is [due-3d, due]; spec 8.2 CONFIRM-mode suggestion window is [due-5d, due+10d]. Implemented INV-13 only (InstallmentCandidateRule constants). Domain-modeler/owner to decide whether CONFIRM-mode plans use the wider window (the rule takes constants; a second rule/parameter would be needed). The "possible duplicate of installment n" indicator (AE-17 part 2) is NOT implemented (S12a).
2. Candidate amount compared = expense gross amount vs scheduled, tolerance 1 minor unit; eligibility = ExpenseScheduleLinkPolicy.evaluateInstallmentLink Allowed (spending type, currency, not purchase record, not linked to entry or occurrence). getExpensesBetweenUncapped excludes isNotMine rows.
3. "Awaiting confirmation" (AE-10b overview line) = SCHEDULED entries with dueState DUE or OVERDUE in ACTIVE CONFIRM plans, per currency count + minor. AE-10b text not re-read in this pass; confirm the exact definition.
4. Plan end date in summary = max due among SCHEDULED/PAID/PAID_BEFORE_TRACKING/SKIPPED entries (assumption).
5. Observe flows re-emit on plan/entry table changes only; an edit of a linked expense alone does not re-emit (variance/paid-so-far). UI re-loads on resume. Cheap fix later: also trigger on an expense-table invalidation.
6. InstallmentProjectedEntry carries displayName (local text); S8a must drop projected installments whole from cloud payloads.
7. Suggestion scan does one expense range query per candidate-eligible entry (window of 4 days) and per-expense eligibility reads; bounded by unrecorded entries.
Orchestrator decisions (2026-10-25; nothing compiled, run or validated):
(1) Candidate windows resolved from the domain spec: AUTO_RECORD plans use INV-13 [due-3d, due]; CONFIRM plans use section 8.2 [due-5d, due+10d]; both same currency, |amount - scheduled| <= 1 minor unit, unlinked, not a purchase record (ExpenseScheduleLinkPolicy reuse kept). Implemented as InstallmentCandidateRule.windowStartEpochDay(mode, due) / windowEndEpochDay(mode, due) / matches(mode, ...); InstallmentPlanReadModel.mayHaveCandidate(mode, entry, today); InstallmentPlanReadRepositoryImpl range query uses the plan mode window. The INV-13 window is exposed as InstallmentCandidateRule.autoRecordWindowStartEpochDay / autoRecordWindowEndEpochDay (constants AUTO_RECORD_DAYS_BEFORE_DUE=3, AUTO_RECORD_DAYS_AFTER_DUE=0) for the S7a coordinator auto-record deferral to reuse; S7a must not define a second copy. Old constants DAYS_BEFORE_DUE / DAYS_AFTER_DUE and the mode-less overloads were removed (test-author: update any test using them).
(2) AE-10b "awaiting confirmation" = SCHEDULED entries with due <= today in ACTIVE CONFIRM plans (accepted; matches the DUE or OVERDUE dueState implementation).
(3) Plan end date = max due among SCHEDULED/PAID/PAID_BEFORE_TRACKING/SKIPPED entries (accepted; flag for domain-modeler confirmation at S12a).
Tests for test-author: InstallmentPlanReadModelTest (AE-5 overview per currency, no line for absent currency; AE-10b awaiting line; AE-11 history line; variance Available negative/positive, not-mine (effective 0), missing link and currency drift Unavailable; suggestionsFor window edges due-3/due/due+1 and due-4, tolerance 0.01 vs 0.02, currency mismatch, non-SCHEDULED/PAID_BEFORE_TRACKING/PAYOFF/linked entries get none, non-ACTIVE plan none, ordering); InstallmentProjectionRuleTest (ACTIVE only, due >= today incl. today, overdue excluded, PAID_BEFORE_TRACKING/SKIPPED excluded, COMPLETED/terminal none, horizon, order, recordGeneration passed); InstallmentPlanReadRepositoryImplTest and ProjectionPortImplTest with fake DAO/reader (unknown code counted unreadable, unconvertible amount never zero, today from fake TimeProvider, zone boundary); InstallmentExpenseFactsReaderImpl money mapping; extend InstallmentPlanArchitectureGuardTest/F6 to pin the new Impl files read-only (no ExpenseDao, no write verbs) and domain files DAO-free. Existing fakes of InstallmentExpenseFactsReader and InstallmentPlanDao must add the new methods.

## S6a review fixes (source)
(2026-10-25; from architecture-guardian and privacy-security-guardian, both ACCEPT-WITH-MINORS; nothing compiled, run or validated)
1. One shared relations builder: domain/installment/lifecycle/InstallmentLinkRelations.forExistingExpense(linkedInstallmentEntryId, planPurchaseRecordExists, facts) is used by InstallmentPlanLifecycleCoordinator.linkExpense and InstallmentPlanReadRepositoryImpl.isEligible (same behavior as before: any entry status counts, INSTALLMENT_PURCHASE flag counts as purchase record). declareOnExpense (no flag fallback) and the declareWithNewExpense stored-row re-check (F10, no ids yet) keep their own ExpenseScheduleRelations on purpose.
2. reconcile()/reconcileForExpense(): each plan and each orphan candidate is wrapped in catch CancellationException rethrow / catch Exception count-and-continue. InstallmentReconcileReport.plansFailed counts plans only; new orphanFlagsFailed (last field, default 0, included in plus) counts orphan candidates. The first contained exception's class simple name is logged via logReconcile as cause (no message, no stack); counts only otherwise. Committed batches are kept; cancellation propagates and discards the in-memory batches (KDoc).
3. InstallmentPlanReadRepositoryImpl.loadAll: documented in the class KDoc that one emission can be momentarily inconsistent (separate reads, no read transaction) and the next emission corrects it. No withTransaction added (architecture test forbids it).
4. InstallmentPlanArchitectureGuardTest: new live test plus planted self-check that the coordinator file (exact path) references neither findExpenseMoney nor findExpenseMoneyBetween (matcher covers any findExpenseMoney* name). Only test edit of this pass.
5. Docs: LEGAL_PATHS READS (S6a part 2b) is mode-aware (AUTO_RECORD [due-3d, due], CONFIRM [due-5d, due+10d], autoRecordWindow* single source for S7a), lists ExpenseDao.getExpensesBetweenUncapped under the facts reader and the shared builder. CODEBASE_SEGMENTS segment 40: readmodel line cites spec 8.2, lists getExpensesBetweenUncapped and InstallmentLinkRelations, stale "no writer yet" lines (intro, data/database, table row 40) fixed.
Test-author follow-ups: InstallmentLinkRelations unit test (entry link, recurring link, plan purchase, flag-only purchase); reconcile test where one plan's writeTxn throws a non-constraint exception (other plans and orphans still processed, plansFailed = 1, batches of committed plans returned, cause logged as class name only) and one where an orphan throws (orphanFlagsFailed = 1); cancellation test (CancellationException propagates, nothing returned).
Carry-forwards:
- (a) S8b: InstallmentProjectionPortImpl silently drops unreadable plans/entries. Add an "incomplete" signal for forecast consumers so a forecast is never presented as complete when a plan was skipped. Domain-modeler to confirm whether a SCHEDULED PAYOFF entry is projected (current rule: SCHEDULED entries of ACTIVE plans, due >= today).
- (b) S6b/S7/UI callers: writeTxn maps only SQLiteConstraintException (and the private abort signal). Any other exception propagates out of an op; callers must handle it with bounded codes only (class simple name or enum), never message, stack or row content. reconcile contains them per plan as above.
- (c) S8a: recorded installment expenses (source INSTALLMENT_PLAN, merchant = plan displayName) are ordinary expenses and flow through the existing expense AI/cloud paths under existing consent. Confirm with the plan whether that is intended or whether a source = INSTALLMENT_PLAN filter is required; the plan-side projection payload must still drop projected installments whole (decision 6 above).
- (d) Optional least privilege: narrow column queries in InstallmentExpenseFactsReaderImpl instead of loading full Expense rows (ExpenseDao.getById / getExpensesBetweenUncapped) for the facts and money mapping.

## S6a strict-review fixes

Written after reviewer-strict ACCEPT-WITH-CONDITIONS on S6a and the static-guards failure vr-20261025-192809-536cfb27. Not yet re-validated.

- A. db_access DB_DAO_SCOPE_UNRESOLVED (5 sites). Root cause: the guard's receiver parser (scripts/db_guard/scanner.py `_receiver_expression`) walks left from the dot and stops only at an unmatched opener, `;`, `=`, newline, `&&`/`||`, elvis or `->`. A comma or the `in` of a for header is not a boundary, so `snapshotOf(plan, planDao.x())` parsed the receiver as `plan, planDao` and `for (p in planDao.x())` as `p in planDao`. Fix: each DAO call is hoisted into its own `val` statement. Sites: InstallmentPlanReadRepositoryImpl (loadDetail `entries`, `changes()` `plansFlow` and `entriesFlow`, loadAll `entries`), InstallmentProjectionPortImpl (`activePlans`), coordinator reconcilePlan (`planEntries`; enclosing method name unchanged). Behaviour unchanged. Convention for S6b onward: never put a DAO call after a comma or `in`; assign it to a local first.
- B1 (MAJOR). clearOrphanFlag is reason-specific (spec 5.4 R1/R3, same rule as BackupVerifier): INSTALLMENT_PURCHASE is referenced only by a plan purchase row, INSTALLMENT_REVERSED only by an entry link whose status is REVERSED. Any other flag/link combination is cleared. InstallmentLinkRelations now also treats an INSTALLMENT_REVERSED-flagged expense as non-linkable (maps to isPlanPurchaseRecord, refused LINKED_ELSEWHERE); KDoc updated.
- B2. New private `purchaseGoneRefusal(plan)` (one in-transaction check: kind PURCHASE_ATTACHED, purchaseExpenseId set, expense facts missing -> Refused(ILLEGAL_TRANSITION)). Applied in delete, confirmEntry, linkExpense, unlinkExpense, plainEntryMove (skip, unskip, revertPaidBeforeTracking), markPaidBeforeTracking and adjustEntry. reconcile is exempt. In delete the check runs only on the Delete branch, so PLAN_HAS_PAYMENTS still wins first (spec 3.1).
- B3. adjustEntry with the same amount in minor units and the same due date returns AlreadyInState after the transition check and before any write (audit fields untouched). A no-op on a non-SCHEDULED or linked entry is still refused ILLEGAL_TRANSITION.
- B4 / Q3 (OPEN QUESTION for the domain-modeler, no code change). Spec 7.4 E1 only says "adjust one entry's amount or date" and 3.1 only requires SCHEDULED + unlinked. It is silent on moving a due date before declaredOnDate or past neighbouring entries. Today adjust accepts any supported day. Consequences to decide: a SCHEDULED entry moved before declaredOnDate becomes "overdue-unrecorded" and is never auto-recorded (INV-12); order by seq vs dueDate can invert. If the owner wants a restriction, add a bounded refusal (INVALID_DATE) in adjustEntry.
- Q1 note for S10 callers. A retried delete after a committed delete returns Failed(PLAN_NOT_FOUND), not AlreadyInState. Callers must treat PLAN_NOT_FOUND on delete as already deleted.
- S6c condition. Treat InstallmentReconcileReport.plansFailed > 0 or orphanFlagsFailed > 0 as NOT reconciled and fail closed before the snapshot is taken.
- Tests touched: ReconcileTest (two reason-specific orphan cases), LinkConfirmTest (adjust no-op, purchase-gone delete and entry ops), InstallmentLinkRelationsTest (the old "reversed flag alone is not a purchase record" assertion was inverted to match B1).
- Second-pass minor fixes: reconcile() KDoc now states the reason-specific orphan rule; LinkConfirmTest gained link/unlink(PAID)/markPaidBeforeTracking purchase-gone ILLEGAL_TRANSITION assertions (nothing written) and a purchase-gone delete with a PAID entry returning PLAN_HAS_PAYMENTS (payments first, spec 3.1). S10 caller note: a retried confirm/link after the purchase was deleted returns Refused(ILLEGAL_TRANSITION) until reconcile/R5 runs and detaches the plan.
- Risk for the next validation: any existing S6a test that seeds a PURCHASE_ATTACHED plan whose purchase expense row does not exist and then runs an op other than reconcile now gets ILLEGAL_TRANSITION. None was found by reading, but it was not run.

## S6a DONE (2026-10-26)
- Reviews: architecture-guardian ACCEPT-WITH-MINORS (part 1 design + source), privacy-security-guardian ACCEPT-WITH-MINORS, reviewer-strict ACCEPT-WITH-CONDITIONS → fixes → verification review ACCEPT-WITH-CONDITIONS (2 minors) → fixed.
- Validation (serial): compile vr-20261025-184232-f45d57d4; targeted *Installment*,*DbGuardPolicyFixtureTest* vr-20261025-192405-9436a8e7 (660 tests) and on the fixed tree vr-20261025-210037-277f0fe7; static-guards vr-20261025-210141-a425cde3 (after the earlier FAIL vr-20261025-192809-536cfb27 on DB_DAO_SCOPE_UNRESOLVED and a STALE run caused by an orchestrator edit mid-run); shards architecture-contracts vr-20261025-205248-01564374, data vr-20261025-205519-4ac551e5, domain-a-m vr-20261025-205921-e8252dd0; final targeted (last minors: reconcile KDoc + purchase-gone link/unlink/mark + payments-first delete tests) vr-20261025-212301-d8d769f0. The last change after static-guards was a KDoc wording edit plus tests only (not guard-relevant).
- Policy: 13 InstallmentPlanLifecycleCoordinator rows; pins 432 → 445.
- Open: Q3 (adjust date bounds vs declaredOnDate/neighbours) → domain-modeler; carry-forwards (a)–(d) in "S6a review fixes (source)"; S6c conditions (reconcile before entering maintenance mode; plansFailed/orphanFlagsFailed > 0 = not reconciled); S10 caller notes (PLAN_NOT_FOUND on retried delete; ILLEGAL_TRANSITION on retry after purchase deletion).
- Next: S4d (owner O1/O2/O3 = YES, owner-approvals item 23), then S6b.
