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
Reviews (all ACCEPT-WITH-MINORS, fixes applied or carried as A10): room-migration-guardian, privacy-security-guardian, architecture-guardian. reviewer-strict is not run separately for S2: three independent guardians covered DDL/DAO/backup/policy with the migration proof green. Recorded here as an orchestrator decision; a strict pass will run on the S2b+S3 candidate, where the first writer-adjacent code lands.
Next: S2b (backup orphan checks, manifest, privacy mode, wipe, rescue), then S3. S5 (pure domain) can run on the same lane after S3, or in between; test-shards entries approved.
