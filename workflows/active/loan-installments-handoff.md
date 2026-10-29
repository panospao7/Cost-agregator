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

## S4d step 1 (probe) (2026-10-26)
- Owner O1/O2/O3 = YES (2026-10-25; build/release-lead-notes item 23 in the main checkout).
- Converted exactly ONE CASE-only query per plan A12.6: `ExpenseDao.getTotalBusinessExpensesBetween` SELECT amount `${EFFECTIVE_AMOUNT_SQL}` -> `${SpendingInclusionSql.EFFECTIVE_AMOUNT}` (qualified form; no WHERE added, rest of the query untouched). `SpendingInclusionSql.EFFECTIVE_AMOUNT` exists (flagged row -> 0.0, else the existing `EFFECTIVE_AMOUNT_SQL` value).
- Not run yet (nothing was executed in this step).
- Next: run `db_access` (static-guards). If it reports DB_ROOM_QUERY_UNCLASSIFIABLE -> STOP (A12.6) and escalate. If clean -> convert the remaining A12.2 CASE-only queries.

## S4d step 2 (source + tests; 2026-10-26; nothing compiled, run or validated)
- Preconditions met: db_access PASS vr-20261025-214915-56ba69e7, parity tests PASS vr-20261025-215202-2f5bd58a (step 1 probe + SpendingInclusionSql `+` concatenation).
- Queries converted (SELECT amount expression only; no WHERE added; COUNT columns untouched; trailing spaces in untouched SQL lines preserved - trailing-space line count in ExpenseDao.kt went 70 -> 69, the one removed being the `CASE ` line of the replaced inline CASE copy):
  - getBusinessExpensesBetweenByCurrency: inline CASE copy -> `SUM(COALESCE(${SpendingInclusionSql.EFFECTIVE_AMOUNT}, 0))`
  - getBusinessExpensesByCategory, getBusinessExpensesByProject, getBusinessCategoryCurrencyTotals: `${EFFECTIVE_AMOUNT_SQL}` -> `${SpendingInclusionSql.EFFECTIVE_AMOUNT}`
  - (getTotalBusinessExpensesBetween was step 1.) All 5 A12.2 S4d queries are now converted.
- Markers placed (Kotlin line comment `// spending-exempt: <REASON>` directly above `@Query`, SQL untouched): 16 total.
  - ROW_COUNT 7: observeExpenseMutationClock, countAllExpenses, countExpensesBetween, getTotalCount, getCountForPeriod, countLocated, countUnlocated
  - NON_SPENDING_INCOME 4: getDepositTotalsBetweenByCurrency, getTotalDepositsForPeriod, getMonthlyDeposits, getTotalDeposits
  - NON_MONEY 2: getOldestExpenseDate, getMerchantLocationClusters
  - SUGGESTION 1: searchMerchants (the WITH filtered/stats/latest query)
  - ASSET_VALUE 2 (WarrantyDao.kt): getTotalProtectedValue, getTotalProtectedValueByCurrency
  - DEDUPE_BLOCKING / DEDUPE_RESOLVER: 0 placed (see below)
- Unmapped / not placed: DEDUPE_* markers. A12.6 says "every RP-11 family query", but no @Query in the dedupe region of ExpenseDao.kt (~440-1290) contains SUM/AVG/TOTAL/COUNT/MIN/MAX, so Rule 01 (aggregate on `expenses`) should not reach them and I did not guess which to mark. If the guard's report-mode run lists any dedupe query as unclassified, add DEDUPE_BLOCKING or DEDUPE_RESOLVER to exactly those (never to a query that references SpendingInclusionSql - Rule 03). Also not independently verified: that no OTHER aggregate-on-expenses @Query lacks both a SpendingInclusionSql reference and a marker (the S4a-S4c W+CASE queries reference SPENDING_PREDICATE and were not re-audited); the first report-mode run is the check. MerchantNormalizationDao has no expenses query (hit was a comment); GroupExpenseDao/BudgetAdjustmentDao/SplitItemAssignmentDao untouched (OUT of write set).
- Tests: app/src/test/.../data/database/dao/ExpenseDaoSpendingPredicateBusinessTest.kt (new): neutrality for all 5 (verbatim 8575fbe5 SQL via db.query, `${SP}` for trailing spaces); flagged case for all 5 (reference = same data with flagged rows inserted as amount 0.0 unflagged; sum drops by exactly 850.00, COUNT unchanged); AE-1 business (purchase counts 0, 12 installments count 600, COUNT 13; March alone 0.0 with count 1; March..May 100.0 count 3). Flags only via the fixture's flag() -> TLC.
- Next: S4d step 3 = the guard (scripts/verify_spending_predicate_usage.py + test, id spending_predicate_usage, registration per the S1 precedent). Validation for this step first: targeted `*ExpenseDaoSpendingPredicate*,*ExpenseDaoTest*` then db_access; first guard report-mode run must list exactly the A12.6 set.

## S4d step 3 (guard) (2026-10-26; nothing compiled, run or validated)
- New files: scripts/verify_spending_predicate_usage.py (guard; rules G-SPENDING-PREDICATE-01 uncovered aggregate / -02 marker hygiene / -03 DEDUPE_* + SpendingInclusionSql, INV-8; `--report`, `--root`, `--fail-on-violation`; exit 0/1/2), scripts/test_verify_spending_predicate_usage.py (fixtures per rule, parsing, infra exit 2, hygiene, registration, real-tree clean + marker-set parity). No baseline, no allowlist.
- Behaviour: scans production `.../data/database/dao/*.kt` (via production_source_scope manifest); lexes Kotlin (comments, strings, raw strings, templates, char literals) and parses every `@Query(...)` (concatenation, named `value =`, `${...}` kept as text). In scope = SQL names `expenses` after FROM/JOIN AND uses SUM/AVG/TOTAL/COUNT/MIN/MAX (any @Query, incl. mutations - literal A12.6 reading). Marker = standalone `// spending-exempt: <REASON>` line; only blank-free `//` or `@...` annotation lines may sit between marker and `@Query`. Exit 2: scope unresolved, no DAO file, no @Query parsed, unreadable/unparseable file.
- Registration touch points (mirrors S1 installment_cloud_reachability): scripts/ci/guard_registry.py (new entry after installment_cloud_reachability, ruleIds x3, python-direct, `--fail-on-violation`); scripts/ci/run_static_guard_suite.py SUITE_GUARD_ORDER (+ last); scripts/ci/test_run_static_guard_suite.py (LEGACY_GUARD_MANIFEST_FIXTURE leg after installment leg; POST_SLICE2_SUITE_BUDGET_ADDITIONS +300.0); scripts/ci/test_gr10b_source_scope_matrix.py (migrated 17 -> 18, registry 26 -> 27); docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md (row, S2-MIGRATED); docs/ci/GUARD_STATUS.generated.md and GUARD_COMMANDS.generated.md (blocks hand-added in registry order exactly as the generator emits for the S1 sibling - IMPLEMENTED_UNVERIFIED; run `python scripts/ci/generate_guard_docs.py --root .` NOT executed, guard_docs_truth is the check); docs/architecture/LEGAL_PATHS.md (Installment Plan ENFORCEMENT bullet), docs/architecture/CODEBASE_SEGMENTS.md (script line + Segment 39 row). ENGINE_INTERACTION_MAP not touched (S1 entry there concerned cloud payload only).
- Expected report-mode set (16 exempt; grep-audited statically against ExpenseDao.kt and WarrantyDao.kt, every aggregate-on-expenses @Query in the tree is either a SpendingInclusionSql user or in this set): ROW_COUNT 7 (observeExpenseMutationClock, countAllExpenses, countExpensesBetween, getTotalCount, getCountForPeriod, countLocated, countUnlocated); NON_SPENDING_INCOME 4 (getDepositTotalsBetweenByCurrency, getTotalDepositsForPeriod, getMonthlyDeposits, getTotalDeposits); NON_MONEY 2 (getOldestExpenseDate, getMerchantLocationClusters); SUGGESTION 1 (searchMerchants); ASSET_VALUE 2 (WarrantyDao getTotalProtectedValue, getTotalProtectedValueByCurrency). DEDUPE_*: none. UNCLASSIFIED expected 0. No unmarked/unconverted aggregate found, so no stop-and-ask item.
- Validation to request (serial, after the step 2 targeted run is green): (1) `python scripts/verify_spending_predicate_usage.py --report` via registered-guard `spending_predicate_usage` and compare the REPORT lines with the set above; (2) registered-guard raw_money_aggregates; (3) static-guards; (4) shards domain-a-m, domain-n-z, architecture-contracts (A12.7). Python tests live in the guard_tests leg of static-guards (scripts/test_verify_spending_predicate_usage.py, scripts/ci/test_run_static_guard_suite.py, scripts/ci/test_gr10b_source_scope_matrix.py, guard_docs_truth tests). Gates: architecture-guardian + reviewer-strict, FG-02/06/07/17/23.
- Residual risk: scripts never executed (regexes, lexer, fixture expectations unverified); validate_registry / verify_guard_docs_truth / any test enumerating registry guards may demand a further touch point not seen by grep; comma joins (`FROM a, expenses`) and @RawQuery not covered (documented in the script docstring).

## S4d review fixes (2026-10-26; reviewer-strict ACCEPT-WITH-CONDITIONS; nothing compiled, run or validated)
1. MAJOR A12.6 DEDUPE markers placed (supersedes the "DEDUPE_*: none" decision in S4d step 2/3). `// spending-exempt: <REASON>` line directly above `@Query` (after the KDoc `*/`, or after the blank line when there is no KDoc), SQL byte-identical, in ExpenseDao.kt only. 18 markers, membership from the KDoc family headers (":607 / :862 BLOCKING", ":1103/:1139/:1178 RP-11 FIX 2 COLLISION", ":939 fuzzy RESOLVER", "Duplicate resolution queries") and A12.2's "dedupe BLOCKING/RESOLVER families ~440-1290 incl. getDuplicateCandidatesInRange ~1254":
   - DEDUPE_BLOCKING 11: existsByDedupeKey, existsByMerchantKeyInRange, existsByMerchantInRange (legacy isDuplicate precheck tier), existsByMerchantKeyInRangeCurrencyAware, existsByMerchantKeyPrefixInRangeCurrencyAware, existsByMerchantInRangeCurrencyAware (BLOCKING family), getBlockingCandidateByMerchantKeyInRangeCurrencyAware, getBlockingCandidateByMerchantKeyPrefixInRangeCurrencyAware, getBlockingCandidateByMerchantInRangeCurrencyAware (COLLISION family, not-mine inclusive), findIdByDedupeKey, findIdByRawNotificationId.
   - DEDUPE_RESOLVER 7: getDuplicateCandidateByMerchantKeyInRange, getDuplicateCandidateByMerchantInRange (legacy import-candidate pair), getDuplicateCandidateByMerchantKeyInRangeCurrencyAware, getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware, getDuplicateCandidateByMerchantInRangeCurrencyAware (fuzzy resolver family, `isNotMine = 0`), getDuplicateCandidatesInRange (candidate list, in the A12.2 range), findDuplicateId (the "Duplicate resolution queries" section).
   - Rationale findIdByDedupeKey / findIdByRawNotificationId: idempotency lookups on the unique indexes consumed by blocking/resolution (STRICT_EXTERNAL_ID mode; `resolveExistingIdAfterInsertConflict`; KDoc ":1311 blocking/resolution must see it", includes `isNotMine` rows) -> BLOCKING, so a future predicate edit cannot hide a flagged or not-mine row from them (INV-8).
   - Not marked (not queries): the Kotlin wrappers isDuplicate, isDuplicateCurrencyAware, findDuplicateIdCurrencyAware, findBlockingDuplicateIdCurrencyAware, getDuplicateCandidateForImport(CurrencyAware).
   - Judgement call to confirm in review: the 5 legacy non-currency-aware queries (existsByDedupeKey, existsByMerchantKeyInRange, existsByMerchantInRange, getDuplicateCandidateByMerchantKeyInRange, getDuplicateCandidateByMerchantInRange) and findDuplicateId are marked because they sit in the A12.2 "~440-1290" family range and feed isDuplicate / import-candidate resolution; they carry no KDoc family header. Drop them from EXPECTED_EXEMPT + the file if the reviewer reads "RP-11 family" more narrowly.
   - Final exemption set = 34: DEDUPE_BLOCKING 11, DEDUPE_RESOLVER 7, ROW_COUNT 7, NON_SPENDING_INCOME 4, NON_MONEY 2, SUGGESTION 1, ASSET_VALUE 2 (the 16 step-2 markers unchanged). scripts/test_verify_spending_predicate_usage.py updated: EXPECTED_EXEMPT (+18), EXPECTED_REASON_COUNTS (+DEDUPE_BLOCKING 11, DEDUPE_RESOLVER 7), `len(exempt) == 34`, `SUMMARY: exempt=34`, docstring item 8; the "no DEDUPE markers" comment/test name reworded (the assertion that counts equal the full dict already covers them). Predicate count and unclassified=0 unchanged.
2. MINOR getTotalBusinessExpensesBetween: test side DONE (ExpenseDaoSpendingPredicateBusinessTest.kt pre-edit copy now `FROM expenses${SP}` and `WHERE isBusinessExpense = 1${SP}`, matching the base trailing spaces). DAO side NOT DONE: the implementer toolset has no shell and the Edit/Write tools strip trailing spaces on every line (verified with a scratch file). ExpenseDao.kt (~:2545-2546 now) still lacks the trailing space after `FROM expenses` and `WHERE isBusinessExpense = 1`. Needs a scripted edit by a shell-capable agent, e.g. python: read ExpenseDao.kt bytes; in the getTotalBusinessExpensesBetween @Query replace `0.0) FROM expenses\n        WHERE isBusinessExpense = 1\n        AND ${SPENDING_TYPE_SQL}` with `0.0) FROM expenses \n        WHERE isBusinessExpense = 1 \n        AND ${SPENDING_TYPE_SQL}` (exactly one match; confirm against `git show 8575fbe5:app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`). Until then the SQL is whitespace-different from the base (semantically identical; the neutrality test is whitespace-insensitive in execution). Also verify with `git diff` that no other line in ExpenseDao.kt lost a trailing space (this pass added only comment lines; every anchor line was matched exactly).
3. MINOR Rule 02 STALE_MARKER: scripts/verify_spending_predicate_usage.py new code `STALE_MARKER` (rule G-SPENDING-PREDICATE-02) when a valid non-DEDUPE marker sits above a query that is not in scope (does not name `expenses` after FROM/JOIN with an aggregate). DEDUPE_BLOCKING / DEDUPE_RESOLVER markers are exempt (placed on every dedupe-family query, aggregating or not). Real tree checked statically: all 16 non-dedupe markers sit on in-scope queries, including the two WarrantyDao ASSET_VALUE ones (`FROM warranties w LEFT JOIN expenses e` + `SUM(...)` / `COUNT(*)`), so no ASSET_VALUE carve-out was needed. Fixtures: 20 stale-fail cases (5 reasons x 4 out-of-scope SQL) + 6 DEDUPE-allowed cases (2 reasons x 3 non-aggregating SQL) + 1 JOIN ASSET_VALUE pass.
4. MINOR docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md row 122: "unparseable source file" added to the exit-2 list.
5. MINOR script docstring scope limits: `${CONST}` templates kept as text, so an aggregate or `FROM expenses` that exists only inside an interpolated constant, and `@Query(CONST)`, are invisible (zero cases today).
- Deviations: item 2 DAO half undone (tooling limit, see above).
- Validation to request (serial, A12.7 sequence, via validation-runner): (1) targeted `*ExpenseDaoSpendingPredicate*,*ExpenseDaoTest*,*SpendingInclusionParity*,*ExpenseRepositoryAssistantSqlGolden*,*ExpenseRepositoryAmountSemanticsSql*,*ComparableHomeValueSqlParity*`; (2) registered-guard db_access (unchanged SQL; Room raw-query classification must not move; marker comments are not SQL); (3) spending_predicate_usage (`--report`; expect exempt=34, DEDUPE_BLOCKING 11, DEDUPE_RESOLVER 7, unclassified=0, exit 0); (4) raw_money_aggregates; (5) static-guards (includes the python tests); (6) shards data, integration-golden, domain-a-m, domain-n-z, architecture-contracts. Run (1) only after the item 2 DAO whitespace restore. Room schema JSON must be unchanged (query text unchanged).

## S4d DONE (2026-10-26)
- Owner O1/O2/O3 = YES (2026-10-25). reviewer-strict ACCEPT-WITH-CONDITIONS → C1 (DEDUPE markers, 18 placed: BLOCKING 11, RESOLVER 7; final exempt set 34) and C3 (trailing spaces restored by scripted edit; STALE_MARKER rule; GR-10B matrix row; docstring scope limits) fixed.
- Validation (A12.7, serial, same tree): targeted vr-20261025-224905-5a79e4d6; db_access vr-20261025-231000-2016b2f7; spending_predicate_usage vr-20261025-231233-9eae11c1; raw_money_aggregates vr-20261025-231251-5aa9841a; static-guards vr-20261025-231309-6a61331b; shards data vr-20261025-233816-f6475b7e, integration-golden vr-20261025-234147-93a9470c, domain-a-m vr-20261025-235107-91f302e2, domain-n-z vr-20261025-235223-310a4ad6, architecture-contracts vr-20261025-235354-5fedeee4 — all PASS. (Earlier: probe db_access FAIL vr-20261025-213322-eb7ac76b → SpendingInclusionSql `+` concatenation fix → PASS vr-20261025-214915-56ba69e7.)
- Next: S6b.

## S6b (2026-10-26)
Source and tests written; nothing compiled, run or validated (implementer is not allowed to). Lane Cost-agregator-mec, uncommitted.

Files:
- domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt: new public ops `stop(planId)`, `refund(planId, refundAlreadyRecorded)` (true runs `stop`), `payOff(planId, amount: Double?, payoffEpochDay, todayEpochDay, zone)`, `editDetails(planId, displayName, lenderLabel, categoryId, fulfilmentMode, currency)` (E3 + CURRENCY_LOCKED), `extend(planId, additionalCount, respread)`, `shorten(planId, remainingCount, respread)`, `changeDueDay(planId, newDayOfMonth)` (E4). Shared private `endPlan` (stop and refund), helpers `cancelScheduledEntry`, `reverseSettledEntry`, `moveEndedPlan`, `insertEntryRow`, `rescheduleEntry`, `updatePlanShape`, `scheduledRegular`, `minorOf`, `residualPlacementOf`. Reuses writeTxn, loadPlan, purchaseGoneRefusal, recordEntryExpense (payoff uses a transient PAYOFF entry so the key is `installment:<uuid>:PAYOFF:0:0`), setPurchaseFlag (refund, INSTALLMENT_REVERSED, abortIfMissing), and the pure InstallmentPlanTransitions (planTransition, entryEffect) and InstallmentScheduleCalculator (splitEqually, dueDateEpochDay). No expense delete anywhere.
- data/database/dao/InstallmentPlanDao.kt: 3 new CAS @Query UPDATEs, no schema change: `updatePaidEntryToReversedIfLinkedTo` (PAID -> REVERSED keeping the link), `updatePlanDetailsIfActive` (ACTIVE only; the currency change is guarded in SQL by NOT EXISTS PAID/REVERSED), `updatePlanShapeIfActive` (installmentCount + anchorDayOfMonth, ACTIVE only). HookedPlanDao delegates by `by d`, so the fixture needs no change.
- config/guards/db_ownership_policy.yml: 7 new rows appended after the unlinkExpense row (not in alphabetical position; if a sort check exists, move them): cancelScheduledEntry/updateEntryStatusIfCurrent, editDetails/updatePlanDetailsIfActive, insertEntryRow/insertEntries, moveEndedPlan/updatePlanStatusIfCurrent, rescheduleEntry/updateEntryScheduleIfScheduled, reverseSettledEntry/updatePaidEntryToReversedIfLinkedTo, updatePlanShape/updatePlanShapeIfActive. All DAO writes sit in these helpers (public ops call helpers, so no other enclosing method has a plan DAO write; DAO calls are hoisted into `val` statements, none in an argument or for header).
- Pins 445 -> 452: scripts/test_verify_db_access_boundaries.py (both `== 452`, docstring and the ~line 6111 comment now says "yielding 445; loan S6b adds 7 more, yielding 452"), DbGuardPolicyFixtureTest (KDoc line 28, test name, assertion, comment). No stale 445 prose remains outside the historical S6a "445" statements. The review-fix pass below adds no policy row, so both pins stay 452.
- Test: app/src/test/.../domain/installment/lifecycle/InstallmentPlanLifecycleCoordinatorEndEditTest.kt (new; 22 tests in the first pass, 34 after the review-fix pass below): AE-7, AE-8 both answers, AE-9 450 and 440 (+ retry, input refusals), AE-21 plus AE-11 completion in the same transaction (the 5th confirm returns planStatus COMPLETED, lifetime 600), shorten without re-spread, extend (fixed and equal split with re-spread 41.74/41.66), changeDueDay, editDetails (applies afterwards, CURRENCY_LOCKED, UNSUPPORTED_CURRENCY, category refusals), terminal refusals, purchase-gone refusals, expense row count unchanged for stop/refund/shorten/extend (payoff +1, never -), CAS lost (stop, refund incl. flag rollback, respread) -> Failed(CONFLICT), CancellationException propagation for all 7 ops.

Decisions (need review):
1. editDetails takes full values (pass current ones to keep), ACTIVE plans only (COMPLETED and terminal refused ILLEGAL_TRANSITION). Currency is part of it: changeable only while no entry is PAID or REVERSED (spec 7.4 Illegal list); CAS in SQL as well as a read check. Blank displayName is not validated (same as declare; open question 7 of S6a).
2. extend/shorten/changeDueDay: ACTIVE only (a COMPLETED plan is not reopened by extend; spec only lists reopen for unskip/unlink/revert). `installmentCount` = number of REGULAR rows: extend raises it, shorten leaves it (CANCELLED rows stay). New entries are due on the plan anchor from `firstDueDate` (offset seq-1). EQUAL_SPLIT extend requires respread = true (otherwise the total would change), FIXED_PAYMENT requires false; shorten respread only for EQUAL_SPLIT; wrong combination -> ILLEGAL_TRANSITION (no better bounded code exists; a dedicated code would be a model enum change).
3. shorten keeps the first `remainingCount` SCHEDULED by seq; respread splits the sum of ALL SCHEDULED entries (before the cut) over `remainingCount` with the plan's residual placement (AE-21: 500.00 -> 5 x 100.00). remainingCount 0 is refused INVALID_COUNT (use stop).
4. changeDueDay: base = month of the first (lowest seq) SCHEDULED entry, entry seq s gets the new day in month offset s - firstSeq (skipped/other-status entries keep their gaps). Uses adjustReason DUE_DAY_CHANGED; originalScheduledAmount is set to the current amount by the existing DAO statement (COALESCE), same as adjustEntry's date-only path.
5. payOff: `amount` null = default (sum of SCHEDULED REGULAR entries in minor units, AE-9 450.00); date must be supported, <= today and >= the latest due among PAID/PAID_BEFORE_TRACKING entries, otherwise INVALID_DATE; amount <= 0 or unconvertible INVALID_AMOUNT. The payoff entry is kind PAYOFF seq 0, PAID, linkOrigin USER_CONFIRMED, dated payoff day. A retry on PAID_OFF is AlreadyInState (no second expense). "Link an already captured charge" instead of creating the payoff expense (spec 7.3 alternative) is NOT implemented.
6. refund: PAID -> REVERSED keeps the link; each linked expense is flagged INSTALLMENT_REVERSED via TLC with abortIfMissing = true, so a PAID entry whose expense row is gone rolls the refund back (EXPENSE_NOT_FOUND) until reconcile R2 repairs it. COMPLETED -> REFUNDED is allowed (transition table). PAID_BEFORE_TRACKING entries untouched by every op.
7. Plan terminalAt = now on CANCELLED/REFUNDED/PAID_OFF.

Open questions: Q3 (adjust/changeDueDay date bounds) still open and now also applies to changeDueDay; items 1, 2 and 5 above for domain-modeler; whether payOff should also take an existing expense (see 5). Return type is InstallmentPlanRef only (no cancelled/reversed counts); the UI can derive "N unrecorded past payments will not count" before calling stop.
Risks: any other test fake that implements InstallmentPlanDao directly (not by delegation) must add the 3 new methods; the policy rows' parameterTypes were written from the Kotlin signatures, nested-generic-free on purpose; the new rows' sort position is untested.

Validation to request (serial, via validation-runner): compile; targeted `*InstallmentPlanLifecycleCoordinator*` (includes EndEditTest), then `*DbGuardPolicyFixtureTest*`; shard domain-a-m; static-guards (policy rows and pins changed; db_access must show no DB_DAO_SCOPE_UNRESOLVED and no unowned write); architecture-contracts shard (InstallmentPlanArchitectureGuardTest scans the coordinator file). Gates: architecture-guardian, reviewer-strict.
Next: S6c (parallel lane) / S7a.

## S6b review fixes (2026-10-26)
reviewer-strict ACCEPT-WITH-CONDITIONS; orchestrator decisions D-a..D-d under owner standing approval. Source and tests written, nothing compiled or run. These decisions supersede decisions 1, 2 and 4 of the S6b section above where they differ.

- D-a: `refund(planId, refundAlreadyRecorded = true)` on a COMPLETED plan is AlreadyInState (nothing to stop, the plan already ended), no longer ILLEGAL_TRANSITION. Implemented as the `endPlan` flag `completedIsAlreadyEnded`; `refund` now owns its own `writeTxn` for both answers (the true answer no longer delegates to the public `stop`). ACTIVE -> CANCELLED is unchanged.
- D-b: `editDetails` also edits displayName, lenderLabel and categoryId of a COMPLETED plan. A currency or fulfilmentMode change on a COMPLETED plan is ILLEGAL_TRANSITION; every other status is still ILLEGAL_TRANSITION. One CAS kept, RENAMED `updatePlanDetailsIfActive` -> `updatePlanDetailsIfEditable` (the old name would lie): ACTIVE, or COMPLETED with currency and fulfilmentMode equal to the stored values, plus the existing PAID/REVERSED currency lock. db_ownership_policy row: `operation` renamed, no new row.
- D-c (BINDING S7A NOTE): switching fulfilmentMode CONFIRM -> AUTO_RECORD is refused (ILLEGAL_TRANSITION) while any SCHEDULED entry has due <= today. The user must confirm or skip those first. Consequence for S7a: catch-up may assume that, at the moment a plan becomes AUTO_RECORD, no past-due SCHEDULED entry exists, so it can never backdate-record an entry the user never confirmed. Do not add a bypass. `editDetails` therefore gained a last parameter `todayEpochDay: Long` (the caller supplies the civil today, like `confirmEntry`); the db_ownership_policy editDetails row got one more `Long` in parameterTypes (same row, count unchanged). S10 must pass the civil today.
- D-d: schedule positions are by position among the non-cancelled REGULAR entries, from `plan.firstDueDate` and the plan anchor, never from seq. `extend`: new entries keep seq after the highest REGULAR seq but their dates continue monthly from the last non-cancelled REGULAR entry (no gap after a shorten). `changeDueDay`: every SCHEDULED REGULAR entry is recomputed from `plan.firstDueDate` by its position (consistent with extend) and the op is refused INVALID_DATE (nothing written) if any recomputed date is before `plan.declaredOnDate` or outside the supported range. This replaces the old "first SCHEDULED entry as base" rule and closes open question Q3 for changeDueDay (adjustEntry's date bounds are untouched). A new private `requireSchedulableAnchors` maps a corrupt stored firstDueDate / anchorDayOfMonth to `conflict()` (Failed(CONFLICT)) so `dueDateEpochDay`'s require() cannot escape the transaction.

Tests added (12 in InstallmentPlanLifecycleCoordinatorEndEditTest, now 34; 7 in InstallmentPlanDaoTest; 1 new hook in the fixture): payoff lost plan CAS (CONFLICT, counts, entries, no PAYOFF row, events unchanged); refund with a PAID entry whose expense row is gone (EXPENSE_NOT_FOUND, full rollback incl. the earlier entry's reversal and flag); COMPLETED -> REFUNDED; D-a AlreadyInState with nothing written; COMPLETED refusals for stop/payOff/extend/shorten/changeDueDay and editDetails currency and mode; COMPLETED name/lender edit allowed; D-c (name-only edit allowed with a due entry, switch refused, allowed the day before, allowed after the due entry is skipped); D-d extend after shorten (seq 7/8 due Aug/Sep), changeDueDay refused before declaredOnDate then allowed, changeDueDay by position after shorten + extend, corrupt anchor/firstDueDate -> Failed(CONFLICT); cancellation thrown from `updatePlanStatusIfCurrent` AFTER the writes (stop, refund true/false, payOff) -> propagates, everything rolled back; AE-9 financing cost via the real read repository (payoff 450 -> Available(0), payoff 440 -> Available(-1000) minor). DAO tests (real SQL): `updatePaidEntryToReversedIfLinkedTo` (wrong expense, not PAID, second call), `updatePlanDetailsIfEditable` (ACTIVE all fields, currency lock for PAID and REVERSED, COMPLETED name-only vs mode/currency change, terminal and missing -> 0), `updatePlanShapeIfActive` (ACTIVE only). Fixture: `HookedPlanDao.planStatusHook` (runs before `updatePlanStatusIfCurrent`).

Pins: unchanged at 452 (no new enclosing method with an InstallmentPlanDao write; `refund`, `endPlan`, `requireSchedulableAnchors` contain no direct plan-DAO write; the one new read in `editDetails` is a hoisted `val`).
Validation to request: compile; targeted `*InstallmentPlanLifecycleCoordinator*` and `*InstallmentPlanDaoTest*`; `*DbGuardPolicyFixtureTest*`; static-guards (db_access: the policy row for editDetails changed parameterTypes and operation, so verify no DB_DAO_SCOPE_UNRESOLVED / unowned write); Room schema JSON must be unchanged (query text only).
Residual risk: Robolectric-only tests; the SQL `status = 'COMPLETED' AND fulfilmentMode = :fulfilmentMode` compares the column to the bound parameter in a SET/WHERE of the same UPDATE (WHERE sees the old row, intended) - the DAO tests pin this.

### S6b validation (2026-10-26, serial, final tree)
- targeted `*InstallmentPlanLifecycleCoordinator*,*InstallmentPlanDaoTest*,*InstallmentPlanReadRepositoryImpl*,*DbGuardPolicyFixtureTest*,*InstallmentPlanArchitectureGuardTest*` vr-20261026-004038-fb853016 PASS
- static-guards vr-20261026-005428-6204de32 PASS (db_access clean, pins 452)
- shard architecture-contracts vr-20261026-010947-d533a78a PASS
- shard domain-a-m vr-20261026-011033-166668fa PASS
- shard data vr-20261026-011149-db0ba200 PASS
- Status: S6b validated + accepted (reviewer-strict ACCEPT-WITH-CONDITIONS, conditions closed by D-a..D-d). Committed.
- Next: S6c (cross-coordinator hooks; reconcile before backup maintenance mode per F8), then S7a (binding D-c note).

## S6c planning (2026-10-26)
- Discovery: workflows/active/loan-installments-s6c-discovery.md (scout). The plan write set was stale in 4 places.
- Plan amendment A13 (feature-planner) appended to the plan. S6c is split into S6c-1 (TLC reaction), S6c-2 (recurring policy), S6c-3 (INV-29 detector), S6c-4 (backup pre-reconcile) and S6c-5 (D-18 test + docs).
- Orchestrator decisions under the standing owner instruction (owner FYI):
  - Item 17 = reconcile immediately before enterAndDrain. Verify stays fail-closed.
  - D-18 = no startup hook. BINDING S7a NOTE: every recordDueEntries run calls reconcile() first and dispatches its batches.
- Status: S6c-1 next (implementer); S6c-2 after it (same worktree, single writer).

## S6c-1 — implemented (2026-10-26; uncommitted; nothing compiled, run or validated)
Files:
- app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt: two new Lazy ctor params (`Lazy<InstallmentPlanLifecycleCoordinator>`, `Lazy<PostCommitActionRunner>`, appended after timeProvider); `planDeleted` appends `installment_expense_deleted`; `planUpdated` appends `installment_expense_changed` last, behind the exhaustive private `installmentReactionRequired(kind)`; action factory, result mapping, batch dispatch.
- app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectCategory.kt: `INSTALLMENT_PLAN` appended at the end of the enum.
- Tests: new app/src/test/.../domain/installment/lifecycle/InstallmentExpenseReactionTest.kt; ctor and expected-list updates in domain/transaction/lifecycle/TransactionSideEffectPlannerTest.kt and scenarios/TransactionTargetedUpdateSideEffectsTest.kt (stubs the plan coordinator to return an empty Done; the type update now also plans the reaction). No androidTest builds the planner. InstallmentCoordinatorFixture is unchanged.
Decisions:
- Executor: `PostCommitActionRunnerImpl`, bound to `PostCommitActionRunner` in DiagnosticsModule, is what TLC (`runner.run` / `runBestEffortAfterCommit`) and `TransactionSideEffectDispatcher` use. The reaction runs each `Done.postCommit` batch through the same `PostCommitActionRunner` (injected as Lazy into the planner). The runner is stateless apart from the event writer, so running it from inside an action is re-entrant. Batches are dispatched BEFORE the report is judged, so a plansFailed/orphanFlagsFailed result still dispatches the batches of the plans that committed. One failing batch does not stop the rest. Cancellation is rethrown (the remaining in-memory batches are then lost; reconcile is the backstop).
- SideEffectCategory: option (a), INSTALLMENT_PLAN added. Evidence: the enum is read only through `.name` (SideEffectMetadataFactory, TransactionSideEffectFailureEventWriter, a diagnostics metadata string); no `when` over it, no ordinal use, no script/config/schema/androidTest reference (scripts/ and config/ matches were unrelated names). Appended last so existing ordinals do not move.
- Kind table (planUpdated): react FULL (any field), TYPE (R4 spending type), CURRENCY (R4 plan currency), TRANSFER_DETAILS (carries a type change), PAYMENT_CORE (amount, currency, type together). No reaction: SPENDING_INCLUSION (loop breaker for the coordinator's own flag writes), CATEGORY_ONLY (AE-20), LOCATION_ONLY, BUSINESS_FLAGS_ONLY, MERCHANT, AMOUNT, DATE, OWNERSHIP (not read by R1-R6; a flagged purchase amount is locked by INV-22; reconcile is the backstop). `TransactionUpdateKind` itself is unchanged.
- Outcomes: non-Done -> FailedRetryable(installment_reaction_not_done); plansFailed/orphanFlagsFailed > 0 -> FailedRetryable(installment_reaction_reconcile_incomplete); a batch dispatch exception -> FailedRetryable(installment_reaction_dispatch_failed); any other exception, a closed barrier included (DatabaseAccessBlockedException) -> FailedRetryable(installment_reaction_exception, class simple name). Changed rows -> Completed; nothing changed -> Skipped(NO_WORK). Reasons are fixed constants; errorClass is a class simple name; the only log line is `Installment reaction failed: <simple name>`.
- planCreated and planBulkUpdated are unchanged. No DAO write outside the coordinator, no schema change, no db_ownership_policy row (pins stay 452), no db_raw_query_classification entry.
AEs covered (all in InstallmentExpenseReactionTest): AE-11 delete part (COMPLETED plan reopens, entry SCHEDULED, generation+1, autoRecordConsumed unchanged), AE-16 entry fields (+ month spending drops to 0), AE-25 (R5 through the real TLC delete), AE-20 (category edit leaves plan tables unchanged, no installment action), spec 7.6 (type change through real TLC `updateType`; currency change through the CURRENCY batch), no-plan expense (no writes), loop test (real planner + coordinator + TLC + runner: no recursion, the nested SPENDING_INCLUSION batch is dispatched, a second run changes 0 rows), kind table over every enum value, CancellationException (coordinator and dispatch), FailedRetryable cases.
Risks:
- Test-author/runner: the test file was written without compiling. Things to look at first if it fails: the Lazy SAM-constructor typing, `Cursor.getString` on numeric columns in `snapshot()`, and the `budget_check` start counts in the loop test (2 after the first run, 3 after the second).
- The nested dispatch happens inside the outer action. If the outer runner is later made non-re-entrant (a lock, a single-flight queue) this must be revisited.
- A planner->coordinator->TLC->planner cycle is broken by Lazy only; Hilt graph validation happens at app compile, not in unit tests.
- Existing `TransactionSideEffectPlannerTest` expectations changed (extra last action for FULL rows and planDeleted).
Validation to request (serial): targeted `*InstallmentExpenseReaction*,*TransactionSideEffectPlanner*` (add `*TransactionTargetedUpdateSideEffects*`); then shards domain-n-z and domain-a-m; the app compile in those runs covers the Hilt graph. Gates: architecture-guardian (FG-14), reviewer-strict.

## S6c-2 — implemented (2026-10-26; uncommitted; nothing compiled, run or validated)
Files (main):
- NEW app/src/main/java/com/yourname/expensetracker/domain/installment/lifecycle/InstallmentEntryLinkReader.kt: port, only `suspend fun linkedEntryIdFor(expenseId: Long): Long?`. `linkedExpenseIds()` is NOT added (S6c-3 owns it).
- NEW app/src/main/java/com/yourname/expensetracker/data/repository/InstallmentEntryLinkReaderImpl.kt: `@Singleton`, ctor `InstallmentPlanDao`, one read `getEntryByLinkedExpenseId` (assigned to a local val first), no status filter.
- app/src/main/java/com/yourname/expensetracker/di/InstallmentModule.kt (the module that binds InstallmentExpenseFactsReader; no new module): `provideInstallmentEntryLinkReader`.
- app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt: new last ctor param `installmentEntryLinkReader: InstallmentEntryLinkReader`; `isExpenseEligibleForRecurring` is now `suspend` and is the single gate for the auto-link (`claimLinkSuppressInWrite`, in its transaction), the manual link (same helper) and `reconcileExpenseLinkAfterUpdate` (three call sites). Private const `ELIGIBILITY_TARGET_ID = 0L` in the companion.
Files (tests):
- NEW data/repository/InstallmentEntryLinkReaderImplTest.kt (Robolectric, real in-memory Room via AppDatabaseTestFactory): linked (also PAID/REVERSED/CANCELLED), unlinked, other expense, empty store.
- NEW domain/recurring/lifecycle/RecurringLifecycleCoordinatorInstallmentLinkPolicyTest.kt (Robolectric, real Room, real reader): see AEs below. The name matches the `*RecurringLifecycleCoordinator*` filter.
- Ctor updates: domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt (null fake reader; mock DAOs), RecurringHorizonAndLinkR21Test.kt, RecurringRuleLifecycleCoordinatorTest.kt, e2e/RecurringPaymentMatchE2ETest.kt (real impl over database.installmentPlanDao()), golden/Pipeline4LifecycleGoldenTest.kt and golden/RecurringPlannedActualNoDoubleCountGoldenTest.kt (null fake reader). No androidTest builds the coordinator (grep empty).
Decisions:
- Gate = the old checks (not-mine, TRANSFER, DEPOSIT), then `InstallmentLinkRelations.forExistingExpense(linkedInstallmentEntryId = reader, planPurchaseRecordExists = false, facts)` and `ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence`. `facts` is an `InstallmentExpenseFacts` built from the Expense with the same mapping as `InstallmentExpenseFactsReaderImpl`; linkedRecurringOccurrenceId comes from `occurrenceDao.getByLinkedExpenseId`. The helper maps both INSTALLMENT_PURCHASE and INSTALLMENT_REVERSED flags to a plan purchase record, so no relations are hand-derived.
- `planPurchaseRecordExists` is false: the port has only `linkedEntryIdFor`, so a plan purchase is recognised by its exclusion flag (written with the plan row, INV-2). An unflagged plan row pointing at an expense would not be refused here; reconcile owns that repair.
- Policy target id: the gate runs before a target occurrence is chosen, and the RECURRING_OCCURRENCE rule does not use the id, so a constant 0 is passed (documented in the code). The existing link to the same occurrence passes because the recurring rule ignores `linkedRecurringOccurrenceId`; occurrence uniqueness stays with `claimLinkSuppressInWrite`.
- Refusal = existing outcomes only: auto-link returns false, manual returns `SpecificLinkResult.ExpenseNotEligible`, reconcile returns `Skipped(expense_not_eligible)` (not linked) or `Unlinked` (was linked, then became installment-linked: INV-5 repair, never re-linked). `linkExpenseToOccurrenceDetailed` now uses the gate instead of its inline copy of the old checks so a refusal is reported as `Skipped(expense_not_eligible)` and not as `NoMatch(no_matching_planned_occurrence)`. No new result or reason value.
- Direct injection, not Lazy: the impl is a leaf over `InstallmentPlanDao`; it has no path back to the recurring coordinator, TLC or the plan coordinator, so no cycle is possible and a Lazy would only add noise. (A13 common rule says new cross-module deps are Lazy; this one is a pure leaf read port, per the task's cycle rule.)
- Guard check (read, not run): InstallmentPlanArchitectureGuardTest has no rule on a domain/recurring -> domain/installment import. INV-25 forbids plan code touching recurring stores (the new port has no Dao type and no recurring reference); F6 pins three exact impl paths and the existing DOMAIN_PORT_PATHS, the new files are in neither list and are read-only anyway; the plan-DAO-write rule is satisfied (read only). RecurringArchitectureGuardTest bounds linkExpenseToOccurrence to `linkExpenseToOccurrenceDetailed(` (still holds, the OCCURRENCE_PAID / PLANNED_FULFILLED writers are unchanged). TLC and the S6c-1 planner already import domain.installment, so there is precedent. No config/ or scripts/ file lists the data/repository read impls.
- No DAO write added, no schema change, no db_ownership_policy row (pins stay 452), no db_raw_query_classification entry, no new string, no log line added.
AEs covered: AE-13 first bullet (auto-link skips the installment-linked expense, occurrence stays PLANNED, no OCCURRENCE_PAID event); manual link of an installment-linked expense refused; manual link of a plan purchase record refused (flag + plan row) and of an orphaned INSTALLMENT_REVERSED flag; an existing link to the same occurrence still passes (AlreadyLinkedToThis and reconcile UpdatedLinkedSnapshot); normal expense still links (auto, manual, reconcile); reconcile skips an installment-linked expense and unlinks a linked one that became installment-linked.
Risks:
- Tests were written without compiling. First places to look if red: Robolectric `@Config(manifest = Config.NONE, sdk = [33])` copied from RecurringHorizonAndLinkR21Test; the golden and RecurringLifecycleCoordinatorTest null fakes; whether `database` in the two golden tests is a real AppDatabase (I used the null fake there on purpose).
- Behaviour change: every recurring link attempt now does two extra reads (plan entry by linked expense, occurrence by linked expense) before the existing logic. In the post-update reconcile the gate runs up to twice per call.
- The Hilt graph (new `@Provides`) is only checked at app compile.
Validation to request (serial): targeted `*RecurringLifecycleCoordinator*,*InstallmentEntryLinkReader*,*ExpenseScheduleLinkPolicy*` (also worth adding `*RecurringHorizonAndLinkR21*,*RecurringRuleLifecycleCoordinator*,*RecurringPaymentMatchE2E*,*Pipeline4LifecycleGolden*,*RecurringPlannedActualNoDoubleCount*` for the ctor edits); then `registered-guard db_access`; then shards domain-n-z and data. Gates: architecture-guardian, reviewer-strict.

## S6c-1/S6c-2 review fixes (2026-10-26; A13.1; uncommitted; nothing compiled, run or validated)
Paths are under app/src/main/java/com/yourname/expensetracker (main) and app/src/test/java/com/yourname/expensetracker (test). No schema change, no db_ownership_policy row (pins stay 452), no guard test change. S6c-2b, the docs items and F3/finding-5 guard proposals are NOT done.
S6c-1:
- F1: main/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt:132-133. TRANSFER_DETAILS comment now says it writes direction and account name only (no type or currency change) and stays in the reacting set as a deliberate superset (A13). The earlier note at :762 ("carries a type change") is superseded.
- F2: test/scenarios/TransactionTargetedUpdateSideEffectsTest.kt:93-104 (stubs now `coAnswers` and record `db.inTransaction()` per call into `installmentCallsInTransaction`, :66), helper `assertInstallmentReaction` :159-165 (coVerify of `onExpenseChanged(expenseId)` and every recorded call ran outside the transaction). Used at :210 (updateCategory: 0), :257 (updateMerchant: 0), :309 (updateType: exactly 1), :370 (category DB-state test: 0).
- F4: test/domain/installment/lifecycle/InstallmentExpenseReactionTest.kt:330 now `assertEquals(listOf("UPDATED"), fx.eventTypes().drop(eventsBefore.size))` (exact AE-20 events).
S6c-2:
- C2: port main/domain/installment/lifecycle/InstallmentEntryLinkReader.kt:25 `suspend fun isPlanPurchase(expenseId: Long): Boolean`; impl main/data/repository/InstallmentEntryLinkReaderImpl.kt:25 (`getPlanByPurchaseExpenseId` hoisted into a local val, `!= null`, no status filter). Gate main/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:839-841 (reader and both reads hoisted into local vals) and :858 `planPurchaseRecordExists = isPlanPurchaseRow` (so "plan row OR flag", like the plan coordinator). KDoc at :826-831 updated; this supersedes the :785 decision.
  Tests: test/data/repository/InstallmentEntryLinkReaderImplTest.kt:126-156 (plan row is a purchase, any status, other/standalone/empty are not, entry link alone is not). test/domain/recurring/lifecycle/RecurringLifecycleCoordinatorInstallmentLinkPolicyTest.kt:293 `autoLinkSkipsAPlanPurchaseRowWithoutTheFlag`, :367 `manualLinkOfAPlanPurchaseRowWithoutTheFlagIsRefused` (plan row, no flag: refused).
- C3: main/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:84 ctor param is `dagger.Lazy<InstallmentEntryLinkReader>`; `.get()` is called only inside the gate (:839). This supersedes the :788 "direct, not Lazy" decision. Test ctors updated with `dagger.Lazy { ... }`: RecurringLifecycleCoordinatorInstallmentLinkPolicyTest.kt:156, RecurringLifecycleCoordinatorTest.kt:110 (fake also implements `isPlanPurchase = false`), RecurringHorizonAndLinkR21Test.kt:141, RecurringRuleLifecycleCoordinatorTest.kt:126, e2e/RecurringPaymentMatchE2ETest.kt:121.
- C4:
  - 8a: LinkPolicyTest.kt:70 `RecordingLinkReader` (delegates to the real reader and records `database.inTransaction()`), helper `assertGateReadsRanInsideTheTransaction` :255, asserted at :289 (auto path) and :348 (manual path).
  - 8b: LinkPolicyTest reconcile-unlink test (~:470-505): seeds a planned row on the occurrence key (fulfilled by the link), then asserts exactly one OCCURRENCE_UNLINKED, no new OCCURRENCE_PAID (count equals the count before the reconcile) and planned status != FULFILLED.
  - 8c: LinkPolicyTest.kt:385 `manualLinkOfAnOrphanedPurchaseFlagWithNoPlanRowIsRefused`.
  - 8d: test/golden/Pipeline4LifecycleGoldenTest.kt:164 and test/golden/RecurringPlannedActualNoDoubleCountGoldenTest.kt:321 now build `InstallmentEntryLinkReaderImpl(database.installmentPlanDao())` inside `dagger.Lazy` instead of null readers.
  - Comment at main/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:812-814: the gate covers the coordinator's link paths; the generation path (OccurrenceConflictResolver) is gated in S6c-2b. LinkPolicyTest class KDoc says "link-path eligibility gate".
Risks:
- Not compiled. Look first at: `database.inTransaction()` inside Room `withTransaction` (8a; same thread as the transaction, should be true), the PlannedExpense entity insert in the 8b test (openSourceOccurrenceKey must equal sourceOccurrenceKey while PLANNED), the `coAnswers` stubs in TransactionTargetedUpdateSideEffectsTest, and the Hilt `dagger.Lazy<InstallmentEntryLinkReader>` binding (only checked at app compile).
- The gate now does one more read (`planDao.getPlanByPurchaseExpenseId`) per call; the post-update reconcile may call the gate twice.
- Validation to request (serial): targeted `*RecurringLifecycleCoordinator*,*InstallmentEntryLinkReader*,*InstallmentExpenseReaction*,*TransactionTargetedUpdateSideEffects*,*RecurringHorizonAndLinkR21*,*RecurringRuleLifecycleCoordinator*,*RecurringPaymentMatchE2E*,*Pipeline4LifecycleGolden*,*RecurringPlannedActualNoDoubleCount*`; then `registered-guard db_access`; shards domain-n-z and data.

## S6c-2b — implemented (2026-10-26; A13.1; uncommitted; nothing compiled, run or validated)
Paths are under app/src/main/java/com/yourname/expensetracker (main) and app/src/test/java/com/yourname/expensetracker (test). The generation path now consults the installment link policy (INV-5, domain-spec row (d)).
Files (main):
- NEW main/domain/recurring/RecurringInstallmentEligibility.kt: `@Singleton @Inject`, holds `dagger.Lazy<InstallmentEntryLinkReader>`. Per-expense form `mayLinkToRecurringOccurrence(expense)` (coordinator gate). Batch form `excludedExpenseIds(expenses: Collection<Expense>): Set<Long>`: reads `linkedExpenseIds()` and `planPurchaseExpenseIds()` once per call (nothing when the input is empty) and takes the flag case from the rows in hand. Both forms call ONE private `decide(...)` (relations from `InstallmentLinkRelations.forExistingExpense`, decision by `ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence`), so they cannot disagree. Deviation from the suggested no-arg signature: the flag cost nothing extra from the rows already loaded, and a no-arg form would need an expenses-table read from the plan DAO.
- main/domain/recurring/OccurrenceConflictResolver.kt: ctor `(installmentEligibility: RecurringInstallmentEligibility)`; `resolve` returns early on no candidates, reads the excluded set ONCE, and skips excluded expenses as non-candidates (occurrence stays PLANNED, unlinked). Layer 1 (not-mine, TRANSFER, DEPOSIT, UNKNOWN) unchanged. All 6 resolve call sites are covered with no edit: `generateOccurrences` and `projectOccurrences` (RecurringLifecycleCoordinator), and createRule, activateRule, topUpHorizon, updateRule (RecurringRuleLifecycleCoordinator). The reads join the caller's Room transaction.
- main/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt: ctor param `installmentEntryLinkReader: dagger.Lazy<...>` is replaced by `installmentEligibility: RecurringInstallmentEligibility` (the Lazy lives inside the helper, so constructing the coordinator still never builds the reader). `isExpenseEligibleForRecurring` keeps layer 1 and delegates layer 2. The old `occurrenceDao.getByLinkedExpenseId` read in the gate is gone: the RECURRING_OCCURRENCE policy rule ignores `linkedRecurringOccurrenceId`, so the decision is unchanged; the facts carry null for it. Unused imports and `ELIGIBILITY_TARGET_ID` moved to the helper.
- main/domain/installment/lifecycle/InstallmentEntryLinkReader.kt + main/data/repository/InstallmentEntryLinkReaderImpl.kt: `linkedExpenseIds(): Set<Long>`, `planPurchaseExpenseIds(): Set<Long>`.
- main/data/database/dao/InstallmentPlanDao.kt: `getLinkedExpenseIds()` (`SELECT DISTINCT linkedExpenseId FROM installment_entries WHERE linkedExpenseId IS NOT NULL`) and `getPlanPurchaseExpenseIds()` (`SELECT DISTINCT purchaseExpenseId FROM installment_plans WHERE purchaseExpenseId IS NOT NULL`; the column is `purchaseExpenseId`, entity InstallmentPlan). @Query reads only. S6c-3 reuses `getLinkedExpenseIds()` and the port/impl batch read.
Policy/classification check (read, not run): scripts/verify_db_access_boundaries.py extracts only calls whose method name starts with a MUTATION_VERBS token (`insert|update|delete|...|link|...`) on a DAO receiver; `getLinkedExpenseIds` and `getPlanPurchaseExpenseIds` start with `get`, so no policy row is needed. The port calls `reader.linkedExpenseIds()` start with the verb `link` but the receiver is not a DAO (same as the existing `linkReader.linkedEntryIdFor`), so they are not extracted either. db_raw_query_classification.yml covers raw queries, not @Query; no entry needed. Pins stay 452 and Room schema JSON is untouched (no entity or version change). Prove with `registered-guard db_access`.
Tests (all new files are named for the filters below):
- NEW test/domain/recurring/RecurringInstallmentEligibilityTest.kt (JVM): per-expense matrix (normal, installment-linked, plan row no flag, purchase flag only, reversed flag only, plan row + flag, linked + flag, unrelated flag, non-purchase type), PARITY of the two forms for every expense, set reads exactly once per batch and zero per-expense reads, no reads on empty input, reader built lazily.
- NEW test/domain/recurring/OccurrenceConflictResolverInstallmentExclusionTest.kt (JVM): normal still PAID; installment-linked, plan row only, purchase flag only and reversed flag only stay PLANNED/unlinked; a normal expense beside an excluded one still pays; exactly 2 batch reads (and 0 per-expense) for 3 candidates x 4 expenses; no candidates or no expenses read nothing.
- NEW test/domain/recurring/lifecycle/RecurringGenerationInstallmentExclusionTest.kt (Robolectric, real Room, real reader/helper/resolver/materializer/projection/coordinators): for four shapes (installment-linked, plan row + flag, plan row only, orphan flag only), each with the same merchant, amount and day: createRule, activateRule, topUpHorizon, updateRule and generateOccurrences keep the occurrence PLANNED and unlinked with no paid snapshot, reminders still SCHEDULED, and no PLANNED_FULFILLED / PLANNED_FULFILLMENT_SKIPPED / OCCURRENCE_PAID event; projectOccurrences returns PLANNED and persists nothing. Normal-expense controls: createRule, activateRule, topUpHorizon, updateRule and projectOccurrences still give PAID with the link.
- test/data/database/dao/InstallmentPlanDaoTest.kt: batch id reads (any entry/plan status, distinct, empty store, standalone skipped, agreement with the single reads).
- test/data/repository/InstallmentEntryLinkReaderImplTest.kt: batch forms (any status, agreement with the single reads, sets kept separate).
- Ctor updates (resolver now takes the helper; coordinator takes the helper instead of the Lazy reader): RecurringLifecycleCoordinatorTest (fake reader implements the two batch methods), RecurringLifecycleCoordinatorInstallmentLinkPolicyTest (RecordingLinkReader records the two batch methods too, so 8a covers them), RecurringHorizonAndLinkR21Test, RecurringRuleLifecycleCoordinatorTest, e2e/RecurringPaymentMatchE2ETest, golden/Pipeline4LifecycleGoldenTest, golden/RecurringPlannedActualNoDoubleCountGoldenTest (the last three still use the REAL reader over database.installmentPlanDao()). No androidTest builds these.
AEs / INV: INV-5 (generation half; link half was S6c-2), INV-4 (an installment-linked expense never pays a generated occurrence), domain-spec row (d) ~:521, AE-13 first bullet for the generation path. INV-5 can be marked closed in docs (S6c-5) once these tests pass.
Contract: no recurring result or reason value added; `RecurringPattern` untouched; no log added; `CancellationException` is not caught anywhere in the new code (a read failure propagates and fails the generation closed).
Risks:
- Not compiled. Look first at: the Hilt graph (`OccurrenceConflictResolver` and the coordinator now need `RecurringInstallmentEligibility`, which needs the already bound `Lazy<InstallmentEntryLinkReader>`); the RecurringGenerationInstallmentExclusionTest harness (copied from RecurringHorizonAndLinkR21Test; expense date = due + 6h, occurrence due 2026-10-01 vs clock 2026-09-19 so reminders are future); `OccurrenceGenerationSource.TEST` (taken from an existing test).
- Behaviour change: every resolve with candidates AND expenses now does two extra batch reads (bounded by the number of installment entries and plans). The coordinator gate does one read fewer (occurrence lookup dropped).
- Not covered by a test: the materializer's own PAID dedupe is untouched and still sits behind the resolver.
- Not done (stop-and-ask, owner): guard strengthening proposals F3 and finding 5 (pin the new port/impl/helper in READ_ONLY_IMPL_PATHS / DOMAIN_PORT_PATHS); docs are S6c-5.
Validation to request (serial): targeted `*RecurringInstallmentEligibility*,*OccurrenceConflictResolver*,*RecurringGenerationInstallmentExclusion*,*InstallmentPlanDaoTest*,*InstallmentEntryLinkReader*,*RecurringLifecycleCoordinator*,*RecurringHorizonAndLinkR21*,*RecurringRuleLifecycleCoordinator*,*RecurringPaymentMatchE2E*,*Pipeline4LifecycleGolden*,*RecurringPlannedActualNoDoubleCount*`; then `registered-guard db_access` (proves pins at 452); then shards domain-n-z, domain-a-m and data. Gates: architecture-guardian re-check of the single-gate claim; reviewer-strict at the S6c candidate.

### S6c-2b architecture re-check (2026-10-26): ACCEPT
- Single-gate claim holds. Every production path that links an expense to an occurrence, marks it PAID or fulfils a planned row passes through RecurringInstallmentEligibility:
  - claimLinkSuppressInWrite;
  - the resolver → materializer path, in the same transaction;
  - MarkRecurringBillPaidUseCase → linkExpenseToSpecificOccurrence (LINK_REJECTED reused).
- Removing getByLinkedExpenseId changes no decision: the policy's RECURRING branch ignores the recurring link and the target id.
- Hilt has no cycle or missing binding. The new @Query reads are outside the db_ownership extraction, so the pins stay at 452.
- Follow-ups (not in S6c-2b):
  - (1) Advisory: the port methods start with the verb `link`. They are safe only because the receiver is not a *Dao. Note in S6c-5; no guard change.
  - (2) Pre-existing: recurring layer-1 eligibility differs. The resolver excludes TransactionType.UNKNOWN; the coordinator gate does not. Aligning them changes behaviour, so it is an owner/follow-up item.
  - (3) S6c-5 docs: the stale TODO P4-CURRENT-011 in OccurrenceConflictResolver.

### S6c-1/2/2b final gate (reviewer-strict ACCEPT-WITH-CONDITIONS)
- ISSUE-3 fixed here: added `generateOccurrencesStillPaysTheOccurrenceWithANormalExpense` to RecurringGenerationInstallmentExclusionTest (same harness and window as the exclusion test, normal expense with the same merchant and amount, asserts PAID and linked), so the generateOccurrences exclusion test is not vacuous.
- ISSUE-4 fixed here: matching rule 6 in the linkExpenseToOccurrence KDoc (RecurringLifecycleCoordinator) now says installment-linked and plan-purchase expenses are refused via RecurringInstallmentEligibility (INV-5). Comment only.
- ISSUE-1 to S6c-5 docs + owner FYI: recurring reconcile unlinks a recurring link when the expense is installment-linked or a plan purchase. It acts only as an INV-5 repair on corrupt or restored data and reuses the Unlinked outcome.
- ISSUE-2 to S6c-5 docs: in a FULL batch, recurring_reconcile runs before installment_expense_changed, so an R4-unlinked expense is re-offered to recurring matching only on its next edit.
- Note: the OWNERSHIP comment in the planner is a harmless superset, because ownership edits plan FULL.
- Not run: the new test and the KDoc edit are uncompiled; include `*RecurringGenerationInstallmentExclusion*` in the next targeted run.

### S6c-1/2/2b validation + acceptance (2026-10-26)
- Batch on the pre-final-fix tree (serial, all PASS):
  - targeted vr-20261026-091616-4f4a8984
  - db_access vr-20261026-092235-09dd23c5 (pins 452)
  - known_good_state vr-20261026-092539-ab53922d
  - shards domain-n-z vr-20261026-092840-4c4ffbb0, domain-a-m vr-20261026-093027-cc12a5f4, data vr-20261026-093143-7a2455b1
- After the final-gate test/KDoc fixes: targeted `*RecurringGenerationInstallmentExclusion*,*RecurringLifecycleCoordinator*` vr-20261026-095406-f9ad68f7 PASS.
- Gates:
  - architecture-guardian S6c-1 ACCEPT-WITH-CONDITIONS (fixed)
  - architecture-guardian S6c-2 ACCEPT-WITH-CONDITIONS (fixed; HIGH → S6c-2b)
  - S6c-2b re-check ACCEPT
  - reviewer-strict ACCEPT-WITH-CONDITIONS (ISSUE-3/4 fixed; ISSUE-1/2 → S6c-5 docs)
- Status: S6c-1, S6c-2, S6c-2b accepted. Committed.
- Next: S6c-3 (INV-29 detector exclusion; reuses linkedExpenseIds/planPurchaseExpenseIds from S6c-2b), then S6c-4 (backup pre-reconcile), then S6c-5 (D-18 test + docs).
- Owner items open: guard-strengthening proposals (S6c-1 F3, S6c-2 finding 5); recurring layer-1 UNKNOWN-type alignment (follow-up).

## S6c-3 — implemented (2026-10-26; A13/A13.1; uncommitted; nothing compiled, run or validated)
Paths are under app/src/main/java/com/yourname/expensetracker (main) and app/src/test (test). INV-29 detection half: the recurring pattern detector never uses an installment-linked or plan-purchase expense as evidence.
Files (main):
- main/domain/logic/RecurringExpenseEngine.kt: ctor gains a 4th param `installmentEligibility: RecurringInstallmentEligibility` (plain injection; the helper holds the `dagger.Lazy<InstallmentEntryLinkReader>`, so the reader is still lazy). NEW `suspend fun detectPatterns(allExpenses: List<ExpenseSnapshot>, excludedMerchantKeys: Set<String> = emptySet())`: computes the excluded id set ONCE per call via the helper, filters the snapshots by id, then delegates to the unchanged pure `detectPatternsFromSnapshots`. `getPatternsFromSnapshots` now calls `detectPatterns`. `getPatterns()` and `getPatterns(List<Expense>)` already reach it through `getPatternsFromSnapshots`. All were already suspend; no caller signature changed (InsightsEngine :858/:918 and AnalyticsViewModel :613 call `getPatternsFromSnapshots` and need no edit). RecurringPattern is untouched.
- main/domain/forecasting/MergedRecurringPatternsProvider.kt: `getPatternsFromSnapshots` (and so `getPatterns()`) calls `recurringExpenseEngine.detectPatterns(...)`; the ctor is unchanged.
- main/domain/recurring/RecurringInstallmentEligibility.kt: NEW `suspend fun excludedSnapshotIds(snapshots: Collection<ExpenseSnapshot>): Set<Long>`. Same two batch reads once per call, none when empty. The private `decide(...)` now ends in a new private `decideRelations(relations)`; the snapshot form builds `ExpenseScheduleRelations` and ends in the same `decideRelations`, so there is still one decision. No new DAO read, port method or query (S6c-2b's `getLinkedExpenseIds` / `getPlanPurchaseExpenseIds` and the port batch reads are reused).
Decision (snapshot cannot carry the flag): `ExpenseSnapshot` has `id` but no `spendingExclusionReason`, so the snapshot form applies only the two stored relations (entry link, plan row). It cannot see the INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED flag. The snapshot form therefore does NOT build `InstallmentExpenseFacts` or run the full facts layer (not-mine, type, shared, split), because the RECURRING_OCCURRENCE policy rule is relations-only and applying the facts layer would change today's output. I did not add a field to `ExpenseSnapshot` (it is built in about 8 places, a contract change). Gap: an orphan flag without a plan row (corrupt or restored data) is not excluded from detection; reconcile repairs those, and flagged rows are already outside the spending predicate that feeds analytics inputs.
Tests:
- NEW test/domain/logic/RecurringExpenseEngineInstallmentExclusionTest.kt (JVM, fake reader with call counters): AE-11 last bullet (12 monthly x 50.00 installment-linked -> no pattern); control (same series unlinked -> one MONTHLY 50.00 pattern); plan-purchase series excluded; only the linked expense leaves a 5-purchase series (previousDates has 4, without the linked date); no plans -> `detectPatterns` equals the pure detector on a mixed fixture (non-vacuous: at least 2 patterns); one batch read per call (1 linked set + 1 plan set, 0 per-expense, also across two calls); empty input reads nothing; `getPatternsFromSnapshots`, `getPatterns()` and `getPatterns(List<Expense>)` all route through the exclusion; CancellationException from the reader is rethrown.
- NEW test/domain/logic/RecurringDetectorSingleEntryPointScanTest.kt: no production file outside RecurringExpenseEngine.kt contains `detectPatternsFromSnapshots(` (FailClosedScan + comment stripping; asserts the engine file was seen); the provider calls `recurringExpenseEngine.detectPatterns(` and the engine's pattern path calls `detectPatterns(`.
- test/domain/recurring/RecurringInstallmentEligibilityTest.kt: two tests for `excludedSnapshotIds` (agrees with the expense form on every non-flag shape; documented flag gap; one read per set; nothing read when empty).
- test/domain/forecasting/MergedRecurringPatternsProviderTest.kt: mocks changed from `every { detectPatternsFromSnapshots }` to `coEvery { detectPatterns }`; new routing test (`detectPatterns` called once, the pure detector never).
- Ctor updates (4th arg: helper over a no-plans mock reader returning empty sets): RecurringExpenseEngineTest, RecurringExpenseEngineEmptyListTest, InsightsEngineValidationTest, e2e/AnalyticsPipelineTest, e2e/NotificationParserDashboardTotalSeamTest. CrossEngineParityMatrixTest and the AnalyticsViewModel/InsightsEngine tests use mocks and need no change. No androidTest builds the engine.
AEs / INV: INV-29 (detection half), AE-11 last bullet, INV-4/5 consistency (one decision shared with generation and the link gate).
Rules check: no new catch (CancellationException is never caught); no DAO call after a comma or in a for header; no kotlin.CharSequence use; no schema, DAO, query, classification or guard change; pins stay 452 and Room schema JSON is untouched. ForecastInputAssembler baselines are left for S8b.
Risks:
- Not compiled. Look first at: the Hilt graph (the engine now needs `RecurringInstallmentEligibility`, already a @Singleton @Inject); mockk `coEvery { noPlansReader.linkedExpenseIds() }` stubs; Calendar overflow in the weekly fixture (March 4 + 35 days rolls into April); the `FailClosedScan` / `SourceTextSanitizer` internal-object imports.
- Behaviour change: every detection call with a non-empty snapshot list does two extra batch reads. The engine is called from InsightsEngine (two call sites) and AnalyticsViewModel; the reads are bounded by the number of entries and plans, not by expenses.
- Residual: the orphan-flag gap above; FinancialHealthScoreV2, RecurringExpensesScreen and ForecastInputAssembler get patterns via the engine/provider, so they inherit the exclusion (the assembler's own baselines are S8b).
Validation to request (serial): targeted `*RecurringExpenseEngine*,*RecurringDetectorSingleEntryPoint*,*MergedRecurringPatternsProvider*,*InsightsEngineValidation*,*AnalyticsPipeline*,*NotificationParserDashboardTotalSeam*,*RecurringInstallmentEligibility*,*InstallmentPlanDaoTest*`; then `registered-guard db_access` (pins 452); then shards domain-a-m and data. Gates: architecture-guardian; standard review.

### S6c-3 architecture review (ACCEPT-WITH-CONDITIONS) and fixes
- Correction (F2): the earlier rationale "flagged rows are already outside the spending predicate that feeds analytics inputs" is wrong. Flagged rows are NOT outside the detector's inputs: ExpenseDao.getExpensesSince filters only isNotMine, and ForecastInputAssembler maps every row. The real reason the orphan-flag gap is harmless is effectiveAmount = 0: the detector uses effectiveAmount, which is 0 for rows carrying the INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED flag. A flagged row can only suppress or slightly skew a mixed group, never create one. This is pre-existing, not a regression of S6c-3. Reconcile R1 clears orphan flags.
- An exact fix (excluding flagged rows from detection inputs) needs a new ExpenseDao query. Pins move, so this is stop-and-ask and is NOT done.
- C2 (F1) fixed: RecurringDetectorSingleEntryPointScanTest now flags any `\bdetectPatternsFromSnapshots\b` match in stripped production code outside the engine (calls, `::` references, spaced calls); inside RecurringExpenseEngine.kt it requires exactly two matches (the declaration and the single call) and asserts the call is inside the body of `detectPatterns`; a planted-violation self-check covers `::detectPatternsFromSnapshots` and `detectPatternsFromSnapshots (`. Fail-closed root checks kept. Uncompiled.
- C3 (F3) fixed: the RecurringInstallmentEligibility class KDoc now lists the pattern detector as the third consumer, states the snapshot form is blind to the flag (with the effectiveAmount = 0 reasoning), and notes the direct ExpenseScheduleRelations construction is guarded by the parity test. KDoc only. (F2 is the handoff correction above.)
- Carry to S6c-5 docs:
  - F4: FinancialHealthScoreV2 Bill Reliability, InsightsEngine anomaly suppression (installment merchants are no longer suppressed; OWNER follow-up decision), and AnalyticsViewModel occurrence counts from unfiltered snapshots. These consumers inherit the exclusion.
  - F5: the subscription-candidate detector (NotificationSubscriptionDetector) is outside INV-29 (follow-up).
  - F6: the two batch reads are not in one transaction (benign for analytics).
- Validation already passed on the pre-fix tree (serial): targeted vr-20261026-101318-b0a9a44d, db_access vr-20261026-101852-fa4c4315, domain-a-m vr-20261026-102153-0bf67237, data vr-20261026-102309-b0c3f16f (all PASS). The C2 test change and C3 KDoc are after that run: include `*RecurringDetectorSingleEntryPoint*` in the next targeted run.
- S6c-3 post-fix targeted (`*RecurringDetectorSingleEntryPoint*,*RecurringInstallmentEligibility*`) vr-20261026-102841-0d129773 PASS. S6c-3 accepted (T2: architecture-guardian ACCEPT-WITH-CONDITIONS, conditions C2/C3 met, C4 carried to S6c-5). Committed. Next: S6c-4 (backup pre-reconcile).

## S6c-4 — implemented (2026-10-26; A13; uncommitted; nothing compiled, run or validated)
Paths are under app/src/main/java/com/yourname/expensetracker (main) and app/src/test/java/com/yourname/expensetracker (test). INV-30, owner item 17 (option (a)), F8.
Files (main):
- NEW main/domain/installment/lifecycle/InstallmentPreBackupReconciler.kt: `@Singleton @Inject`, ctor `(Lazy<InstallmentPlanLifecycleCoordinator>, Lazy<PostCommitActionRunner>)`. `suspend fun reconcileBeforeBackup(): PreBackupReconcileResult`. Same file: `sealed interface PreBackupReconcileResult { data object Reconciled; data class NotReconciled(reason) }` and `enum PreBackupNotReconciledReason { NOT_DONE, RECONCILE_INCOMPLETE, EXCEPTION }`. It runs `reconcile()`, dispatches every `Done.postCommit` batch through the runner (each batch in its own try/catch, CancellationException rethrown, a failing batch does not stop the rest and does not make the reconcile incomplete), and dispatches BEFORE the report is judged (batches of committed plans are never dropped). NotReconciled: result not Done (NOT_DONE), plansFailed or orphanFlagsFailed > 0 (RECONCILE_INCOMPLETE), any non-cancellation exception, a closed write barrier included (EXCEPTION). Logs: class simple name or fixed text only. Empty batches are dispatched too (harmless; the runner loops zero actions); the runner is not resolved when there is no batch.
- main/data/repository/DatabaseBackupRepositoryImpl.kt: new primary ctor param `installmentPreBackupReconciler: dagger.Lazy<InstallmentPreBackupReconciler>` (after timeProvider, before the defaulted barriers; required, no default, so a missing reconciler cannot silently skip the repair). The `internal` test ctor gains the same required param (before `operationRunRecorder`) and forwards it. The call is in `runCostBackupExport` only: after the privacy-gate result handling and before the `try` that holds `maintenanceOperationRunner.enterAndDrain(...)`, mode still NORMAL, not inside a runWrite. Cancellation propagates (no maintenance mode entered, nothing to exit; same as the privacy-gate cancellation precedent: the run handle is not finalised).
Failure outcome used (no new result or reason value): the function's existing `Result.failure(Exception(...))` plus `run.failedFinal(DiagnosticReasonCode.VALIDATION_FAILED.name)` (the code the snapshot-verify failure uses; the privacy early-returns call `run.failedFinal(reasonCode.name)` the same way). Message: `Backup aborted: installment plans could not be reconciled (<enum name>)`. It is bounded (enum name only). The abort happens before enterAndDrain, so no snapshot, no temp file, no bundle, and the mode never leaves NORMAL.
createSafetyBackup decision: NOT added. The public `createSafetyBackup` does enter BACKUP_EXPORTING from NORMAL, but it only fsync-copies the live database file; it creates no snapshot and never runs `BackupVerifier` (no orphan verify), so a pre-reconcile there would add write latency for no verify benefit. The two restore callers use `createSafetyBackupInternalAssumingMaintenance` inside restore maintenance, where reconcile must never run. `exportDatabase` (debug raw export) is excluded. BackupVerifier is untouched.
Behaviour notes:
- If the write barrier is closed (restore, reset, another backup, recovery) the reconcile throws DatabaseAccessBlockedException, which maps to NotReconciled(EXCEPTION): the backup aborts with the bounded message instead of calling `enter(BACKUP_EXPORTING)` over a non-NORMAL mode.
- Every `.costbackup` export now runs a full reconcile (one read of all plans plus the orphan scan) before the drain. A clean store changes 0 rows and dispatches no batch.
- A write racing between the reconcile and the drain can reintroduce a transient orphan; the verify then fails that one backup and a retry succeeds (item 17 option (a); the literal "under the barrier" reading would need a barrier contract change and was not taken).
Tests:
- NEW test/domain/installment/lifecycle/InstallmentPreBackupReconcilerTest.kt (JVM, mock coordinator, recording runner): clean Done -> Reconciled and batches dispatched in order; no batch -> runner never resolved; both dependencies lazy; plansFailed and orphanFlagsFailed -> RECONCILE_INCOMPLETE with batches still dispatched; every non-Done variant (AlreadyInState, Refused, RateRefused, Failed) -> NOT_DONE; exception -> EXCEPTION with the message absent from logs and result; throwing dependency resolution contained; CancellationException from reconcile and from a batch propagates (dispatch stops); failing batches do not stop later ones and the result stays Reconciled; reconcile called once.
- NEW test/data/repository/DatabaseBackupRepositoryPreReconcileTest.kt (Robolectric sdk 28, real file-backed Room at the context database path, real backup pipeline: MaintenanceOperationRunner, SqliteSnapshotCreator, BackupVerifier, bundle writer; real plan coordinator and real reconciler over ONE DatabaseWriteBarrier; mode is a state-machine mock with a trace; TLC relaxed mock, which the R2 scenario never reaches). Item 17: a paid entry whose expense was removed by a raw SQL DELETE (unreacted delete, test only) -> backup succeeds, orphan count 0, entry reopened to SCHEDULED. Control: same state with a no-op reconciler -> failure with the "Orphan installment_entries linked to non-existent expenses" verify error. Ordering: trace is exactly RECONCILE, ENTER, EXIT (reconcile once, before enter). Privacy denied never reaches the reconciler. NotReconciled (plansFailed, non-Done, exception) -> bounded message, no enter/exit, no bundle, no snapshot copy, mode NORMAL. Cancellation -> propagates, no enter, mode NORMAL. Orphan injected inside `enter` (after the reconcile, before the drain) -> verify still fails closed, mode NORMAL after, no bundle.
- Ctor updates (required new argument): data/repository/DatabaseBackupRepositoryImplTest.kt (field `mockPreBackupReconciler` returning Reconciled; the helper `createRepository` and the primary-ctor call), data/repository/RestoreAbortWorkerResumeDeferredTest.kt, data/backup/ReceiptAssetRepeatRestoreTest.kt and data/backup/BackupVerifierSavingsLedgerTest.kt (strict mock reconciler: any call fails the test, since restore never reconciles). No androidTest builds the repository.
AEs / INV: INV-30 (backup half), AE-12 backup half, owner item 17 (a backup right after an unreacted delete passes verify), F8 (reconcile before maintenance mode; plansFailed/orphanFlagsFailed > 0 = not reconciled, fail closed before the snapshot).
Rules check: no DAO write outside the coordinator, no schema change, no db_ownership_policy row (pins stay 452), no db_raw_query_classification entry, no new string, CancellationException never swallowed, no kotlin.CharSequence, no DAO call after a comma or in a for header (the test's raw SQL goes through the open helper, as in InstallmentCoordinatorFixture).
Risks:
- Not compiled. Look first at: the Hilt graph (DatabaseBackupRepositoryImpl -> Lazy reconciler -> Lazy coordinator and runner; Lazy on both edges); `dagger.Lazy { ... }` SAM constructors; mockk of the final reconciler and coordinator classes; in the Room test, the Robolectric file DB at the default context path (the snapshot method VACUUM_INTO vs drained copy on sdk 28, WAL checkpoint with the Room connection open), `StandardTestDispatcher(testScheduler)` with real Room executors, and whether any trigger or constraint rejects the raw `UPDATE installment_entries SET linkedExpenseId = 987654` or the raw expense DELETE (the reconcile fixture already deletes expense rows raw).
- Test file ctor change: the new ctor argument is required in both constructors, so any other caller built later must pass it (grep found only the five above).
- The reconcile runs before the privacy-independent maintenance entry, so a backup now needs the plan stack (coordinator + TLC graph) resolvable at first export.
Validation to request (serial): targeted `*InstallmentPreBackupReconciler*,*DatabaseBackupRepository*,*BackupVerifier*` (also worth adding `*RestoreAbortWorkerResumeDeferred*,*ReceiptAssetRepeatRestore*` for the ctor edits); then shards data and domain-a-m; the app compile in those runs covers the Hilt graph. Gates: room-migration-guardian, reviewer-strict.

### S6c-4 — review + harness fixes
- room-migration-guardian: ACCEPT-WITH-CONDITIONS. C2 and C3 done (uncompiled, unrun):
  - C2: DatabaseBackupRepositoryPreReconcileTest gains a PURCHASE_ATTACHED plan whose purchase expense is deleted by raw SQL (unreacted delete): the backup passes because the pre-reconcile (R5) detached the plan (kind STANDALONE, purchaseExpenseId null, orphan count 0); control with a no-op reconciler fails the verify with "Orphan installment_plans referencing non-existent purchase expenses". DAO-only, no TLC.
  - C3 finding 1: runCostBackupExport records one `INSTALLMENT_PRE_RECONCILE` run event (FAILED_FINAL, VALIDATION_FAILED, metadata `reason` = the enum name only) before `run.failedFinal`, so the stored run row distinguishes NOT_DONE / RECONCILE_INCOMPLETE / EXCEPTION.
  - C3 finding 2: PreBackupReconcileResult.Reconciled KDoc now says no reconcile failure was reported and a non-Move R2 is skipped and not counted, so BackupVerifier remains the authority.
- Debugger root cause: Robolectric legacy SQLite cannot open the WAL-mode Room file DB through the real snapshot pipeline ("Cannot open SQLite connection", all 4 pipeline tests); the passing DatabaseBackupRepositoryImplTest uses a rollback-journal DB. Test-only fix: setUp builds the DB with `.setJournalMode(TRUNCATE)` after `AppDatabase.fileBuilder` (last call wins over configureBuilder's WAL), creates `context.cacheDir`, and every pipeline assertion message now carries `stackTraceToString().take(1500)` (synthetic data) so a wrong cause shows the real frame. Item-17 assertions unchanged.
- Failed runs, both on this test only: vr-20261026-105126-3ddb6dcb (targeted) and vr-20261026-105728-1c1099b7 (data shard). PASS: shard domain-a-m vr-20261026-110129-fbed13ce and known_good_state vr-20261026-110248-398ea9cd.
- Owner FYI (finding 4): one plan that persistently fails reconcile blocks every .costbackup export (fail-closed by design, F8).
- S6c-5 notes (finding 8): reconcile changes 0 rows only for a quiescent snapshot; redacted restored plans have a blank displayName, which recordEntryExpense uses as the merchant, so S7a/S6c-5 must check that path.
- Next: rerun targeted `*DatabaseBackupRepositoryPreReconcile*,*InstallmentPreBackupReconciler*,*DatabaseBackupRepository*`, then the data shard.
- (2026-10-26) S6c-4 test harness, debug iterations:
  - Iteration 1: the TRUNCATE journal mode did not fix it.
  - Diagnostic: app-frame stack capture, targeted vr-20261026-115201-e435b8f6. It shows `[14] unable to open database file` when opening the snapshot copy at DatabaseBackupRepositoryImpl :802, in Robolectric's default cache dir.
  - Iteration 2 (test-only): the repository gets a ContextWrapper whose getCacheDir() is a plain temp dir, as in the passing DatabaseBackupRepositoryImplTest.
  - Earlier runs: the data shard vr-20261026-114528-b144259f failed on this test only, and its tree was edited mid-run. It does not count.
  - If iteration 2 fails: Plan B (verify a self-made snapshot directly), or hand back PARTIAL.
- Iteration 2 FIXED it. Targeted `*DatabaseBackupRepositoryPreReconcile*,*InstallmentPreBackupReconciler*,*DatabaseBackupRepository*,*BackupVerifier*,*RestoreAbortWorkerResumeDeferred*,*ReceiptAssetRepeatRestore*` vr-20261026-115603-74517089 PASS. Root cause: the Robolectric default cacheDir path could not be opened by legacy SQLite for the snapshot copy (test environment only). The data shard is re-queued on the final tree.

### S6c-4 final gate (reviewer-strict ACCEPT-WITH-CONDITIONS)
- ISSUE-1 met: data shard vr-20261026-120221-804dff49 PASS on the final tree.
- ISSUE-2/3/4 fixed (test-only, DatabaseBackupRepositoryPreReconcileTest; uncompiled, unrun):
  - ISSUE-2: a capturing OperationRunHandle replaces the no-op handle in two tests. The RECONCILE_INCOMPLETE abort asserts exactly one `INSTALLMENT_PRE_RECONCILE` event (FAILED_FINAL, VALIDATION_FAILED, metadata only `reason` = RECONCILE_INCOMPLETE) and then `failedFinal("VALIDATION_FAILED")`; the success test asserts no such event.
  - ISSUE-3: the stale harness comment is corrected. TRUNCATE is kept to mirror DatabaseBackupRepositoryImplTest; the WAL checkpoint path is not exercised on the JVM (covered by BackupWalLockInstrumentedTest); the real JVM blocker was Robolectric's default cacheDir.
  - ISSUE-4: new test with the real reconciler and a non-NORMAL mode (RESTORE_COMPLETE_RESTART_REQUIRED) seeded over an orphan: the failure message ends with "(EXCEPTION)", mode.enter/exit never called, the mode is unchanged, no artifacts, orphan still present.
- The "stackTraceToString().take(1500)" bullet above is superseded by the app-frame `detail()` helper.
- Owner FYI (a): a persistently failing plan reconcile, or any non-NORMAL mode, blocks .costbackup export with the generic message, by design.
- Owner FYI (b), pre-existing: after an early abort, the SAF document the user created stays empty.
- Next: rerun targeted `*DatabaseBackupRepositoryPreReconcile*`, then the data shard.
- S6c-4 final targeted (`*DatabaseBackupRepositoryPreReconcile*,*InstallmentPreBackupReconciler*,*DatabaseBackupRepository*`) vr-20261026-124508-2cf6dfd3 PASS. The edits after the data shard PASS (vr-20261026-120221-804dff49) are test-only.
- S6c-4 accepted: room-migration-guardian ACCEPT-WITH-CONDITIONS and reviewer-strict ACCEPT-WITH-CONDITIONS, all conditions met. Committed. Next: S6c-5 (D-18 restore round-trip test + docs incl. the carried items; guard-strengthening proposals await the owner).

## S6c-5 — implemented (2026-10-26; docs and one test; uncommitted; docs not validated)
Test (the earlier attempt wrote it and died on an API error; this pass did not touch it):
- InstallmentRestoreReconcileTest: AE-12 restore half, in two variants (non-redacted export and redacted export). Targeted run vr-20261026-133610-c8a32805 PASS.
- Finding: the redacted export blanks the plan `displayName` and `lenderLabel`. `recordEntryExpense` uses the plan displayName as the expense merchant, so S7a MUST handle a blank displayName on a restored redacted plan (fallback merchant text or a refusal). This is a binding S7a note, together with the earlier D-18 note (every recordDueEntries run calls reconcile() first and dispatches its batches; no startup hook).
Source comment fixes (comment only, no behaviour):
- ExpenseScheduleLinkPolicy KDoc (S6c-2 finding 4).
- OccurrenceConflictResolver: the stale TODO P4-CURRENT-011 removed/updated (S6c-2b re-check follow-up 3).
Docs updated:
- docs/architecture/LEGAL_PATHS.md: the S6c reaction block marked implemented with the corrected runner wording (TLC PostCommitActionRunner runs `installment_expense_changed` / `installment_expense_deleted`; planned for FULL, TYPE, CURRENCY, TRANSFER_DETAILS, PAYMENT_CORE and for deletes; the coordinator decides applicability; SPENDING_INCLUSION never reacts). New blocks: backup pre-reconcile (InstallmentPreBackupReconciler before enterAndDrain, NotReconciled aborts, BackupVerifier is the authority); recurring link-policy consultation (RecurringInstallmentEligibility on the coordinator link paths and OccurrenceConflictResolver generation, INV-5); recurring reconcile INV-5 repair (Unlinked reused); INV-29 detector exclusion (RecurringExpenseEngine.detectPatterns as the single entry point); "after restore" (reconcile at the start of each S7a recordDueEntries run, D-18 option (b), no startup hook). The operations list entries for onExpenseChanged / onExpenseDeleted and reconcile() updated. INV-5 and INV-29 detection half are closed in docs (S6c-2b and S6c-3 tests passed). The advisory about port methods starting with the verb `link` is recorded in CODEBASE_SEGMENTS.
- docs/architecture/ENGINE_INTERACTION_MAP.md: TLC to installment plan reaction row; recurring/detector exclusion rows; inherited consumer effects (FinancialHealthScoreV2 bill reliability, InsightsEngine anomaly suppression, AnalyticsViewModel counts from unfiltered snapshots); the subscription detector is outside INV-29.
- docs/architecture/CODEBASE_SEGMENTS.md: Segments 7 and 40 gain the domain/recurring to domain/installment port dependency; new files RecurringInstallmentEligibility, InstallmentEntryLinkReader (+Impl), InstallmentPreBackupReconciler.
- Recorded as docs caveat (reviewer-strict ISSUE-2): in a FULL batch recurring_reconcile runs before installment_expense_changed, so an R4-unlinked expense is re-offered to recurring matching only on its next edit. Recorded (ISSUE-1): recurring reconcile unlinks on an installment-linked or plan-purchase expense as an INV-5 repair on corrupt or restored data.
Correction: the S6c-1 note at :760 ("reconcile is the backstop" for a lost in-memory post-commit batch, e.g. budget re-check batches after a cancellation) is WRONG. Reconcile does not re-create those batches. The next budget trigger is the backstop.
Owner decisions pending (nothing done without approval):
- (a) Guard strengthening: an InstallmentPlanArchitectureGuardTest allow-list so plan code calls only the TLC DbOnly ops (S6c-1 F3, planted self-check), plus pinning the new port/impl/helper paths (InstallmentEntryLinkReader, InstallmentEntryLinkReaderImpl, RecurringInstallmentEligibility, InstallmentPreBackupReconciler) in READ_ONLY_IMPL_PATHS / DOMAIN_PORT_PATHS (S6c-2 finding 5).
- (b) InsightsEngine anomaly suppression for installment merchants: they are no longer suppressed as recurring after S6c-3. Keep or restore suppression?
- (c) Recurring layer-1 UNKNOWN-type alignment: the resolver excludes TransactionType.UNKNOWN, the coordinator gate does not (pre-existing; aligning changes behaviour).
- (d) The subscription-candidate detector (NotificationSubscriptionDetector) vs INV-29: extend the exclusion or leave it out.
Owner FYI carried: a persistently failing plan reconcile, or any non-NORMAL mode, blocks .costbackup export (fail-closed by design); the SAF document the user created stays empty after an early abort (pre-existing); the two batch reads of the detector exclusion are not in one transaction (benign for analytics); an exact exclusion of orphan-flag rows needs a new ExpenseDao query (stop-and-ask, not done).
Validation to request (serial, once for the S6c candidate): `static-guards` (FG-14, FG-26), plus shard data (and domain-a-m if the candidate also changed domain sources). The comment-only source fixes need no separate run beyond the compile in those runs.
Status: S6c-1 to S6c-5 complete pending that validation and the S6c candidate reviewer-strict gate. Next after acceptance: S7a.

### S6c-5 docs-accuracy review (architecture-guardian ACCEPT-WITH-CONDITIONS) and fixes (2026-10-26)
- F1: LEGAL_PATHS/ENGINE_INTERACTION_MAP now mark reconcile-at-recordDueEntries as PLANNED and binding on S7a; recordDueEntries does not exist yet. Until S7a the only reconcile() caller is InstallmentPreBackupReconciler.
- F2: the restore-test claim is narrowed (nothing to repair; reconcile reports all zero; the S7a call site is untested).
- F3: dependency wording in CODEBASE_SEGMENTS and the ExpenseScheduleLinkPolicy KDoc.
- F4: annotation on the FORBIDDEN line: restore half enforced by the write barrier, startup half policy only.
- F5: the OccurrenceConflictResolver comment names topUpHorizon.
- F6: wording changed to "every reacting update batch".
- F7: single-expense deletes only.
- F8: wording changed to `decideRelations`.
- F9: wording changed to "constant message plus the enum name".
- Test KDoc: the privacy gate and the post-commit runner are test doubles.
- Applied partly by a doc agent, which then died on an API error, and finished by the orchestrator: text-only edits, no code change.
- Open follow-ups:
  - (a) Owner FYI: bulk updates (planBulkUpdated, incl. TYPE/CURRENCY bulk changes) and the deleteAll paths (ExpenseRepository :886/:916, NotificationRepository :274) plan no installment reaction. Today the only backstop is the pre-backup reconcile; S7a's reconcile closes it.
  - (b) Verify the wipe paths clear the plan tables. Per the S2 D-2 finding, data removal is Room-wide, so this is likely covered.
- S6c candidate gate (final tree, serial):
  - static-guards vr-20261026-142448-542b9580 PASS
  - app-check vr-20261026-144036-f4732e0c PASS
  - S6c-5 restore test targeted vr-20261026-133610-c8a32805 PASS
  - S6c-5 accepted (T2: architecture-guardian docs review, conditions applied). Committed. S6c COMPLETE: S6c-1, 2, 2b, 3, 4, 5.
- Next: S7a (AUTO_RECORD host + foreground catch-up). Binding notes:
  - D-c: no backdating of unconfirmed entries.
  - D-18: reconcile() first in every recordDueEntries run, with its batches dispatched.
  - S6c-4 finding 8: redacted restored plans have a blank displayName used as the merchant.
  - The bulk/deleteAll gap is closed by that reconcile.
- Owner decisions pending: guard strengthening (a), InsightsEngine anomaly suppression (b), the UNKNOWN-type alignment (c), the subscription detector (d).

## S7a planning (2026-10-26)
- Discovery: workflows/active/loan-installments-s7a-discovery.md (scout).
- The domain spec was amended before S7a (domain-modeler): FX deferral removed (A2), INV-34 and AE-31 added for blank plan names, D-19 foreground trigger is overview-open only. Owner-approvals item 33 records this.
- Plan amendment A14 (feature-planner) is appended to the plan: S7a-1 is the coordinator recordDueEntries plus the INV-34 name rules; S7a-2 is the worker plus its registrations.
- Next: S7a-1 implementer.

## S7a-1 — implemented (PARTIAL: recordDueEntries held at a stop-and-ask, 2026-10-26)

Status: the INV-34 / owner-item-33 name rules, the claimEntry derivation, the refusal codes and the report type are in place. `recordDueEntries` itself is NOT implemented, because sourcing `hasCandidateCharge` hits the A14 stop trigger. Nothing was compiled or run.

### hasCandidateCharge sourcing decision (STOP, owner/orchestrator decision needed)
- The pure window `InstallmentPlanReadModel.mayHaveCandidate` and `InstallmentCandidateRule` (windows, currency, 1-minor-unit amount match) exist, but the rule needs the candidate expense's gross amount and date, and then the eligibility check (not a plan purchase record, not linked to an entry or occurrence, spending type).
- The only facts-port method that returns amounts is `findExpenseMoneyBetween`. `InstallmentPlanArchitectureGuardTest` (S6a, `coordinatorMoneyReads`, COORDINATOR_EXPENSE_MONEY_READ) forbids ANY `findExpenseMoney*` reference in the coordinator file. Using it needs a guard change; renaming a new port method to dodge the regex would evade the guard's intent. Both are stop-and-ask.
- The matcher + eligibility orchestration that already exists lives in the data layer, `InstallmentPlanReadRepositoryImpl.suggestionsOf` / `isEligible`. A coordinator-side or port-side `hasCandidateCharge` would duplicate it.
- Options for the orchestrator/owner:
  1. Extract the candidate scan (suggestionsOf's per-entry query + `InstallmentCandidateRule.matches` + `isEligible`) into one shared owner and expose a single bounded boolean on a new domain port (for example `InstallmentCandidateChargeProbe.hasCandidate(plan, entry, zone)`), used by both the read repository and the coordinator. No new DAO method needed (it reuses the facts reader and plan DAO reads). The coordinator then calls only the boolean; the guard stays intact. Needs a new port + Hilt binding + constructor param (fixture `coordinator()` factory update) and a pin in the architecture guard path lists (owner approval for guard-list edits).
  2. Owner approves narrowing the S6a guard so the coordinator may call one named probe.
  3. Owner accepts an interim fail-closed rule (never record while any unlinked same-currency expense lies in the window), which is stricter than INV-13 and contradicts AE-17 ("candidate deferral only").
- Also decide: the coordinator has no post-commit runner (reconcile() returns batches and its caller dispatches them). The proposed `recordDueEntries` returns `InstallmentOpResult.Done(InstallmentDueRunReport, batches)` for the caller (S7a-2 worker) to dispatch after each commit, matching reconcile(). The A14 text "dispatch the post-commit batch" per entry would otherwise need a runner constructor dependency.

### Files changed (all in C:\Users\panos\Desktop\Cost-agregator-mec)
- main: domain/installment/model/InstallmentRefusalCode.kt (PLAN_NAME_REQUIRED, INVALID_VALUE); domain/installment/model/InstallmentPlanTransitions.kt (`isPlanNameUsable`, the single INV-34 predicate); domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt (blank-name refusals; `claimEntry` derives `markAutoRecordConsumed = origin == AUTO_RECORDED`); domain/installment/lifecycle/InstallmentDueRunReport.kt (new; in lifecycle/, not model/, because it uses InstallmentReconcileReport and InstallmentOpFailure which live in lifecycle).
- tests: InstallmentPlanLifecycleCoordinatorDeclareTest (declareOnExpense, declareWithNewExpense, createStandalone blank-name), ...LinkConfirmTest (confirm blank-name PLAN_NAME_REQUIRED, link still allowed on a blank-name plan), ...EndEditTest (payoff blank-name, editDetails blank-name then rename).
- Not created: InstallmentRecordDueEntriesTest (blocked with the function).

### Behaviour
- confirm: PLAN_NAME_REQUIRED after the transition/AlreadyInState and INVALID_DATE checks, immediately before recordEntryExpense (TLC not called).
- payOff: PLAN_NAME_REQUIRED after the date check, before any entry cancel or TLC call.
- declareOnExpense, declareWithNewExpense, createStandalone, editDetails: INVALID_VALUE as the first statement of the transaction, before any read or write. Restore paths untouched.
- No new DAO methods, no db_ownership_policy rows (pins stay 452), no schema change.

### AEs covered so far
- AE-31 partial: confirm and payoff refused PLAN_NAME_REQUIRED writing nothing; link allowed; rename (E3) works. Not yet: the recordDueEntries parts (count PLAN_NAME_REQUIRED = 2, post-rename record).
- Owner item 33: INVALID_VALUE for declare/create/edit.
- Not yet: AE-1, 10a/c, 15a/b/c, 16, 17, 19, 29 recorder parts, reconcile-first, independent entry failure, report privacy.

### Changed existing assertions
None. No existing test used a blank name, so no assertion needed updating.

### Risks
- editDetails now refuses a blank name even when the plan is already blank and the name is not being changed (a mode-only edit of a redacted plan needs a real name). This follows owner item 33; the UI should require a name.
- The ordering of PLAN_NAME_REQUIRED relative to INVALID_DATE in confirm/payoff is mine (name check last); the spec only says before TLC.
- `InstallmentDueRunReport` is unused until recordDueEntries lands.
- Unverified by compile or test.

### Validation to request (after recordDueEntries is completed, or now for the partial)
- targeted `*InstallmentRecordDueEntries*,*InstallmentPlanLifecycleCoordinator*,*InstallmentPlanDaoTest*,*DbGuardPolicyFixtureTest*` (the first pattern matches nothing until the test exists; the coordinator suites exercise the changes made so far)
- registered-guard db_access
- shard domain-a-m

### S7a-1 part A — candidate probe (2026-10-26; reconstructed from the code after the first agent died on an API error; uncommitted)
Resolves the hasCandidateCharge stop above with option 1 (owner-approvals items 34/35): one shared owner for the candidate scan, exposed to the coordinator as a single boolean.
- Files (main):
  - domain/installment/lifecycle/InstallmentCandidateChargeProbe.kt (new read-only port: `suspend fun hasCandidate(plan, entry, zone): Boolean`; fails closed to true on unreadable stored values; returns no amount, id or text);
  - data/repository/InstallmentCandidateChargeProbeImpl.kt (new; the single scan: window query, `InstallmentCandidateRule`, then expense eligibility; `collectCandidates` + `ScanState` are shared with the read repository);
  - data/repository/InstallmentPlanReadRepositoryImpl.kt (refactor: `suggestionsOf` now delegates the scan to the probe impl; constructor gets a defaulted `candidateScan` parameter so existing call sites still compile);
  - di/InstallmentModule.kt (binds the port to the impl);
  - InstallmentPlanLifecycleCoordinator.kt (new last constructor parameter `candidateChargeProbe`).
- Files (tests): InstallmentCoordinatorFixture.coordinator() passes `InstallmentCandidateChargeProbeImpl(reader, dao)`; DatabaseBackupRepositoryPreReconcileTest and InstallmentRestoreReconcileTest construct the coordinator with the probe; new data/repository/InstallmentCandidateChargeProbeImplTest.
- Guard path additions (InstallmentPlanArchitectureGuardTest): `CANDIDATE_PROBE_IMPL_PATH` (data/repository/InstallmentCandidateChargeProbeImpl.kt) added to `READ_ONLY_IMPL_PATHS`; domain/installment/lifecycle/InstallmentCandidateChargeProbe.kt added to `DOMAIN_PORT_PATHS`. The S6a coordinator money-read ban (`COORDINATOR_EXPENSE_MONEY_READ`) is unchanged.
- No new DAO method, no db_ownership row (pins stay 452), no schema change.
- Validation: targeted PASS, run vr-20261026-171318-61e59593.

### S7a-1 part B — recordDueEntries (2026-10-26; uncommitted; nothing compiled, run or validated)
- Main file changed: domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt only. New public `suspend fun recordDueEntries(todayEpochDay, zone): InstallmentOpResult<InstallmentDueRunReport>` plus private helpers (`DueRunAccumulator`, `DueEntryOutcome`, `recordDueEntriesOfPlan`, `isDueAutoRecordCandidate`, `recordOneDueEntry`, `recordDueEntry`, `logDueRun`). Reuses `recordEntryExpense`, `claimEntry`, `settlePlanStatus`, `context`, `purchaseGoneRefusal`, `writeTxn`. No new DAO method, no db_ownership row, no schema change. Imports added: AutoRecordDecision, InstallmentDeferralReason.
- Behaviour:
  1. `reconcile()` first; its report goes into `InstallmentDueRunReport.reconcile`, its batches into `Done.postCommit` (D-18). The caller dispatches all batches after commit.
  2. `getPlansByStatus(ACTIVE)` filtered to AUTO_RECORD; per plan `getEntriesForPlan`, pre-filtered on stored values (SCHEDULED, REGULAR, unlinked, not consumed, `declaredOn <= due <= today`).
  3. A blank name (`isPlanNameUsable`) counts PLAN_NAME_REQUIRED once per pre-filtered entry and writes nothing; TLC is not called.
  4. One `writeTxn` per entry: re-read plan and entry, `purchaseGoneRefusal`, name re-check, `autoRecordDecision(ctx, false)` (NotApplicable returns without a probe read), then `candidateChargeProbe.hasCandidate` inside the transaction and `autoRecordDecision(ctx, hasCandidate)`. Defer counts AUTO_RECORD_DEFERRED_CANDIDATE. Record: supported-day check, `recordEntryExpense` (userPresentRate null, STRICT key), `claimEntry(AUTO_RECORDED)` (CAS, latches autoRecordConsumed), `settlePlanStatus`, batch collected.
  5. Failed or Refused result: counted by enum, that entry rolled back, loop continues. An unmapped non-cancellation exception is counted as `InstallmentOpFailure.RECORD_FAILED` and only its class simple name is logged. CancellationException is rethrown.
  6. One log line `InstallmentDueRun recorded= deferred= refused= failed= cause=` (integers and a class simple name only), none when all counts are zero.
- Tests: new domain/installment/lifecycle/InstallmentRecordDueEntriesTest.kt (real Room + TLC fixture) covers AE-1, AE-10a/c, AE-15a/b/c, AE-16 (latch, reconcile reopen, no re-create), AE-17 (deferral with a real candidate charge; other amount and out-of-window charge do not defer; link then accepted), AE-19, AE-29 (request userPresentRate null via a spied TLC; USD with a null-rate converter gives a sentinel row, one MISSING_RATE, no refusal, evaluator never called), AE-31 in full (empty and whitespace names, recorder count via spy, confirm/payoff refused, link, rename, next run records entry 4 only; CONFIRM-mode blank plan), reconcile-first with batches, independent per-entry failure, contained unmapped exception, non-AUTO/non-ACTIVE plans untouched, report and log privacy. Parity tests (read-repo suggestion vs probe) added to data/repository/InstallmentCandidateChargeProbeImplTest.
- Risks:
  - AE-15b is covered sequentially (second run after the first), not with two truly concurrent runs; the per-entry CAS and the in-transaction re-read are the concurrency guarantee.
  - Tests use `spyk(fx.tlc)` and a hand-built TLC with a mocked converter; if mockk cannot spy the final TLC class, those tests (AE-29 x2, AE-31 x3) need a different capture.
  - A reconcile that reopens a COMPLETED plan lets that plan be recorded in the same run (reconcile runs first by design).
  - The pre-filter and `autoRecordDecision` use the same rule; a plan with an unparsable stored code fails that entry as CONFLICT, not the run.
  - The plan and entry reads outside the per-entry transactions (`getPlansByStatus`, `getEntriesForPlan`) are not wrapped: a failure there propagates out of `recordDueEntries`; the S7a-2 worker's bounded Result must cover it.
  - The USD-plan INVALID_DATE refusal path (unsupported due day) is not tested.
- Deviations from A14: none. The caller (S7a-2 worker) dispatches the returned batches after commit instead of the coordinator dispatching per entry (owner-approvals item 35).
- Validation to request (serial):
  1. targeted `*InstallmentRecordDueEntries*,*InstallmentCandidateChargeProbe*,*InstallmentPlanLifecycleCoordinator*,*DbGuardPolicyFixtureTest*,*InstallmentPlanArchitectureGuardTest*`
  2. registered-guard db_access
  3. shard domain-a-m, then shard data
- (2026-10-26) The S7a-1 targeted run vr-20261026-174104-5ae0b044 FAILED in the two AE-29 tests only. Cause: mockk `any()` cannot generate the inline value class CurrencyCode in `evaluator.evaluate(any(), …)` (a known lesson). Test-only fix (debug iteration 1): `coVerify { fx.evaluator wasNot Called }` on the strict mock, which is equally strong.

### S7a-1 — architecture review (ACCEPT-WITH-CONDITIONS) and fixes (2026-10-26; uncommitted; nothing compiled, run or validated after these fixes)
Paths are under app/src/main/java/com/yourname/expensetracker (main) and app/src/test/java/com/yourname/expensetracker (test). No schema change, no new DAO method, no db_ownership row (pins stay 452).
- Validation that PASSED before these fixes (the pre-fix tree): targeted vr-20261026-180650-a9c9cec1; shard domain-a-m vr-20261026-180937-d0ba83eb; shard data vr-20261026-181053-bd187fa0; registered-guard db_access vr-20261026-175354-1b46f8b4; known_good_state vr-20261026-175658-3086dee4. The fixes below are NOT covered by those runs; a fresh serial targeted run, db_access, domain-a-m and data are needed.
- F1: recorded as reported by the review (no code change in this pass). The `ENTRY_LINK_READER_IMPL_PATH` pin in `READ_ONLY_IMPL_PATHS` (test/architecture/InstallmentPlanArchitectureGuardTest.kt :1077, :1088) is approved under owner item 36(b).
- F2 (fixed): the auto-record applicability rule existed twice (coordinator pre-filter `isDueAutoRecordCandidate`, and `autoRecordDecision`). New pure helper `InstallmentPlanTransitions.isAutoRecordApplicable(...)` (domain/installment/model/InstallmentPlanTransitions.kt :478) is now used by `autoRecordDecision` (:451) and by `isDueAutoRecordCandidate` (domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt :1358). The pre-filter builds its inputs from `fromCodeOrNull` (no `context()`, which throws AbortSignal outside writeTxn); a code that does not parse is not a candidate. Behaviour unchanged (the caller already filters ACTIVE + AUTO_RECORD plans).
- F3 (fixed, additive; owner item 34): `CANDIDATE_PROBE_IMPL_PATH` added to the check-8 condition `readImplExpenseStoreReferences` (test/architecture/InstallmentPlanArchitectureGuardTest.kt :822) and to its self-check loop, so the probe impl may not reference ExpenseDao, RecurringOccurrenceDao or CategoryDao. The probe impl has no such reference today.
- F4 (fixed, decision fail-closed/stop): `recordOneDueEntry` (InstallmentPlanLifecycleCoordinator.kt :1394) rethrows `DatabaseAccessBlockedException` (restore, reset, maintenance) before the generic catch, so it stops the whole run instead of counting RECORD_FAILED for each remaining entry. This matches `reconcile()`, whose barrier check at the top propagates (no bounded Failed mapping exists there, so no new `InstallmentOpFailure` value was added). Entries already committed stay committed; the in-memory batches of this run are discarded with the propagated exception (the next run reconciles and picks up the untouched entries). Note: a barrier that closes in the middle of `reconcilePlans` is still counted per plan as plansFailed (existing behaviour, not changed here).
- F5 (fixed): the default value of `candidateScan` in the `@Inject` constructor of data/repository/InstallmentPlanReadRepositoryImpl.kt (:58) is removed; Hilt builds `InstallmentCandidateChargeProbeImpl` through its own `@Inject` constructor. Test call sites updated: InstallmentPlanReadRepositoryImplTest, InstallmentCandidateChargeProbeImplTest, InstallmentPlanLifecycleCoordinatorEndEditTest.
- F6 (docs, fixed): CODEBASE_SEGMENTS.md segment 40 (probe port and impl, recordDueEntries entry point, helper); LEGAL_PATHS.md (INSTALLMENT DUE RECORDING block, the lifecycle-reaction and AFTER RESTORE blocks and the coordinator table: the coordinator part of reconcile-at-recordDueEntries is implemented in S7a-1, reconcile runs first and its batches are returned for the caller to dispatch, the S7a-2 worker and the S10 overview callers are pending); ENGINE_INTERACTION_MAP.md (new probe + recordDueEntries section, backstop note updated).
- F7 (tests, added to domain/installment/lifecycle/InstallmentRecordDueEntriesTest.kt :639-:790): (a) `RecordingProbe` asserts `fx.db.inTransaction()` on every call; (b) a blank-name plan with a candidate charge counts PLAN_NAME_REQUIRED x2, the probe is never called, tables and transaction_events unchanged; (c) an entry due on 2201-01-01 (past MAX_EPOCH_DAY) is refused INVALID_DATE, nothing written; plus the F4 test: the barrier is closed after the first claim (RESTORE_STAGING), the run throws DatabaseAccessBlockedException, entry 1 stays PAID, entries 2 and 3 are untouched, no RECORD_FAILED is logged, and after reopening the next run records the other two.
- F8: accepted as reported by the review (no change).
- A14 :940 (facts-reader port) is superseded by owner item 34 (probe port).
- Residual risk: post-commit batches of entries committed before a barrier stop are lost (see F4); S7a-2's worker must treat DatabaseAccessBlockedException as a bounded retry/skip outcome, never as a crash. (SUPERSEDED by the final-gate fix below: no throw, batches kept.)

### S7a-1 — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes (2026-10-26; uncommitted; nothing compiled, run or validated after these fixes)
Paths are under app/src/main/java/com/yourname/expensetracker (main) and app/src/test/java/com/yourname/expensetracker (test). No schema change, no new DAO method, no db_ownership row (pins stay 452). Decisions are owner-approvals item 39 (a), (b), (c).
- Decision 39(a): a write barrier closing mid-run (for example a backup export starting) stops `recordDueEntries` with a bounded marker and still returns `Done(report, batches)`; it does not throw, so committed follow-up work (budget, cache, recurring) is never dropped. The worker retries later. Decision 39(b): the auto-record latch is set on EVERY claim (recorded, confirmed or linked); once an entry has been paid or linked once, auto-record never re-creates it after the user deletes that expense (extends AE-16 to USER_CONFIRMED and USER_LINKED; the user can still confirm manually). Decision 39(c): corrupt stored data in the candidate probe keeps the fail-closed AUTO_RECORD_DEFERRED_CANDIDATE answer (no new reason code; no code change).
- Item 1 (39(a), supersedes F4 and its residual risk): `InstallmentDueRunReport` gains `stoppedByBarrier: Boolean = false` (domain/installment/lifecycle/InstallmentDueRunReport.kt). `recordOneDueEntry` catches `DatabaseAccessBlockedException` (before the generic catch, after CancellationException), sets the marker and returns; `recordDueEntriesOfPlan` and `recordDueEntries` leave their loops on it. The result is `Done(report, acc.batches)` with all batches so far: reconcile's plus those of entries committed earlier in the run. CancellationException is still rethrown. The log line is now `InstallmentDueRun recorded= deferred= refused= failed= stoppedByBarrier= cause=` and is also written when only the marker is set. KDoc of `recordDueEntries`, `recordOneDueEntry` and the report updated. A barrier already closed at the top of `reconcile()` still throws out of `recordDueEntries` (nothing was committed, so no batch can be dropped; unchanged).
- Item 2 (39(b)): `claimEntry` passes `markAutoRecordConsumed = true` for every origin (signature and the db_ownership row unchanged). Tests updated to the approved behaviour: InstallmentPlanLifecycleCoordinatorLinkConfirmTest `confirm_creates_the_installment_expense_at_noon_on_the_due_date_and_claims_the_entry` (:103, consumed now true) and `a_link_on_a_blank_name_plan_is_still_allowed_because_it_creates_no_expense` (consumed now true). The AE-27 revert test (consumed stays false, the entry was never claimed) is unchanged. New tests in InstallmentRecordDueEntriesTest: `owner39b_a_user_confirmed_entry_...` and `owner39b_a_user_linked_entry_...` (AUTO_RECORD plan, user confirms or links, the expense is deleted, reconcile reopens, recordDueEntries records 0 and creates no expense; the confirm test also confirms manually afterwards).
- Item 3 (finding 1, MAJOR test gap): a DAO wrapper `StaleSnapshotDao` returns the entries captured earlier from `getEntriesForPlan` (armed by the `getPlansByStatus` call, so reconcile still reads live rows). Tests `a_stale_snapshot_entry_that_another_run_already_recorded_is_not_recorded_again` (entry now PAID) and `a_stale_snapshot_entry_in_the_AE16_state_consumed_then_reopened_is_not_re_created`: recorded 0, failed/refused/deferred empty, no new expense row, `RecordingProbe.calls == 0`.
- Item 4 (finding 3): `reconcile_first_detaches_a_plan_whose_purchase_was_deleted_so_the_due_entry_still_records` pins D-18: an AUTO_RECORD PURCHASE_ATTACHED plan whose purchase row was deleted; r5Detached = 1, then the entry records (recorded = 1), plan STANDALONE.
- Item 5 (finding 4): the vacuous `tree.lines.none { it.contains("RECORD_FAILED") }` in the barrier test is replaced by assertions on the run's single log line (`recorded=1`, `stoppedByBarrier=true`, `failed=0`). The barrier test now expects `Done` with `stoppedByBarrier = true`, entry 1 PAID and its batch in `postCommit` (size 1), entries 2 and 3 untouched, and the next run after reopening records them (`stoppedByBarrier` false). A second test pins that a stop returns the reconcile batch plus the earlier entry's batch (size 2). The privacy-log regex now includes `stoppedByBarrier=(true|false)`.
- Correction of stale risk text: the S7a-1 part B risk "a plan with an unparsable stored code fails that entry as CONFLICT, not the run" is out of date. After F2 the pre-filter (`isDueAutoRecordCandidate`, built on `fromCodeOrNull`) skips such an entry silently; it is neither recorded nor counted.
- Note for S7a-2: run-level read failures (`getPlansByStatus` and `getEntriesForPlan`, outside the per-entry transactions) still abort the run with the exception, so the worker's bounded Result must cover them. The worker maps `stoppedByBarrier` to a retry (and still dispatches the returned batches after commit).
- Validation to request (serial): targeted `*InstallmentRecordDueEntries*,*InstallmentPlanLifecycleCoordinator*,*InstallmentRestoreReconcile*,*InstallmentExpenseReaction*`, then registered-guard db_access, shard domain-a-m, shard data.

### S7a-1 — accepted (2026-10-26)
- Final tree (serial, all PASS):
  - targeted vr-20261026-191645-db9029cc
  - db_access vr-20261026-192950-aa104292 (pins 452)
  - domain-a-m vr-20261026-193252-bd8b8dc6
  - data vr-20261026-193408-07621fa9
  - earlier: known_good_state vr-20261026-175658-3086dee4
- Gates:
  - architecture-guardian ACCEPT-WITH-CONDITIONS (fixed)
  - reviewer-strict ACCEPT-WITH-CONDITIONS (C1 met; C2 test added; C3 decided by owner-approvals item 39)
  - Review iteration limit reached; accepted on validation.
- Committed. Next: S7a-2 (InstallmentDueRecordingWorker + registrations). The worker must:
  - map stoppedByBarrier to a retry;
  - cover run-level read failures and a barrier closed at reconcile start with a bounded Result;
  - dispatch the Done.postCommit batches after the call returns.

## S7a-2 — implemented (2026-10-26; uncommitted; nothing compiled, run or validated)
No stop-and-ask trigger hit: no db_ownership change, no G-WORKER-01 allowlist entry, no startup hook, no AppStartupCoordinator edit, no persisted marker, no new privacy category.

### Files (all in C:\Users\panos\Desktop\Cost-agregator-mec)
- New: app/src/main/java/com/yourname/expensetracker/service/installment/InstallmentDueRecordingWorker.kt
  - The RecurringHorizonWorker shape. Injects InstallmentPlanLifecycleCoordinator, PostCommitActionRunner, WorkerExecutionGuard and TimeProvider.
  - Work name `installment_due_recording`. Guard request: requiresDatabaseWrite, no notification permission, no backup-export, BlockedPolicy.RETRY, specVersion from DEFAULTS.
  - Inside the guard block: one `checkpoint("installment_due:run")`, then ONE call `recordDueEntries(today, zone)`.
  - Zone is `ZoneId.systemDefault()`. Today is `TimeProvider.now()` converted to an epoch day in that zone. TimeProvider only exposes now(), the same as the template.
- Registrations in domain/workers:
  - WorkerSpec.DEFAULTS: `installment_due_recording`, 24h, KEEP, NetworkType.NOT_REQUIRED (the recurring_horizon constraints).
  - WorkerRegistry: import plus Entry after recurring_horizon.
  - WorkerGuardVerifier: workerRegistry map entry plus findWorkerClasses.
  - WorkerSpecScheduler.listAllWorkerNames.
  - PrivacyRuntimeWorkerPolicy: unchanged. It lists only gated workers, and the new worker is local-only like recurring_horizon, which is not listed there either. No new category.
- docs/architecture/LEGAL_PATHS.md: the INSTALLMENT DUE RECORDING row now describes the worker (12 work names, the Result mapping, no startup hook or marker).
- Tests:
  - New service/installment/InstallmentDueRecordingWorkerTest.kt (14 tests).
  - WorkerContractTest 8→9 plus the key set.
  - WorkerGuardStaticVerificationTest: KNOWN_WORKER_FQNS +1, listAllWorkerNames 11→12.
  - WorkerGuardVerifierTest (an extra pin the brief did not list): 11→12 plus the name.
  - No PrivacyRuntimeWorkerPolicy test change, because the worker is not in the policy.

### G-WORKER-01 check (read scripts/verify_worker_boundaries.py; not run)
The worker has the accepted shape:
- Top-level `val guardResult = executionGuard.runGuardedWithContext(...) { ... }` and a single top-level `return guardResult.toWorkerResult()`. No `return` inside the lambda. The receiver is a typed `val executionGuard: WorkerExecutionGuard` constructor property.
- No identifier ending in `Dao`, so the DAO-mutator check cannot fire.
- The only broad catch, `catch (e: Exception)` in dispatchBatches, has a sibling `catch (e: CancellationException) { throw e }`, the `if (e is CancellationException) throw e` idiom and a `Log.w`. The run-level catch is the specific DatabaseAccessBlockedException.
- Expected: no violations and no allowlist entry.

### Result mapping
| Outcome | Worker behaviour | Result |
|---|---|---|
| guard denied, restore or maintenance mode, open gate | the guard's own mapping; no coordinator call | retry (BlockedRetry) or success (Skipped) |
| Done, no failures, not stopped (including deferred and refused) | batches dispatched | success |
| Done, stoppedByBarrier | batches dispatched first, then `RetryableWorkerException("INSTALLMENT_DUE_STOPPED_BY_BARRIER")` | retry |
| Done, any per-entry failed count | batches dispatched, then `RetryableWorkerException("INSTALLMENT_DUE_PARTIAL_FAILURE")` | retry |
| DatabaseAccessBlockedException from the call (closed barrier at reconcile start) | `RetryableWorkerException("INSTALLMENT_DUE_BARRIER_CLOSED")`; no dispatch | retry |
| result not Done (impossible today) | log class name, then `IllegalStateException("INSTALLMENT_DUE_NOT_DONE")` | failure (guard Failed) |
| other run-level read exception (getPlansByStatus, getEntriesForPlan) | not caught; the guard classifies it | transient retry or failure (WORKER_UNHANDLED_EXCEPTION) |
| a batch dispatch exception | logged by class name, the other batches continue | result unchanged |
| CancellationException (call or dispatch) | never caught here | propagates |

### Notes and risks
- A dispatch failure does not change the Result, because a retry would not re-dispatch the batch.
- Retrying on per-entry failure follows the template's partial-failure policy. A persistently failing entry (a CONFLICT or RECORD_FAILED count) retries on WorkManager backoff until the next period.
- CORRECTION to the line above (architecture review F1, 2026-10-26): for periodic work `Result.retry()` keeps backing off (exponential from 30 s, capped at 5 h; WorkerSpec / WorkerSpecScheduler); it does not stop at the next period. A persistent per-entry failure therefore gives about 10 attempts in the first ~8.5 h, then one attempt every 5 h indefinitely. Each attempt is bounded in rate, idempotent and matches the RecurringHorizonWorker precedent. The orchestrator accepts this policy. Optional follow-up for both workers: a `runAttemptCount` cap that ends in success while keeping `ctx.addErrors`.
- If the run-level read fails after reconcile already committed some plan transactions, reconcile's batches are lost, because the call throws before returning. This is a known S7a-1 limit, noted in the S7a-1 handoff.
- The ZoneId is read at run time, so a zone change between runs moves "today". This is the same trust as the confirm path.
- Hilt: the worker injects the coordinator directly, as the recurring worker does with its coordinator. The coordinator's Lazy ports exist for the backup graph, not for workers.
- LEGAL_PATHS:667 still says "All 10 production CoroutineWorkers" (stale before this slice; the verifier lists 11, now 12). Left alone.

### Validation to request (serial)
1. targeted `*InstallmentDueRecordingWorker*,*WorkerContract*,*WorkerGuardStaticVerification*,*PrivacyRuntimeWorkerPolicy*,*WorkerRegistry*` (add `*WorkerGuardVerifier*`, `*WorkerRestoreRegression*` and `*RestoreMaintenanceMode*`: they enumerate DEFAULTS)
2. registered-guard cancellation (the worker cancellation guard, including G-WORKER-01)
3. shard runtime-ui, then architecture-contracts
4. S7a candidate: static-guards, then app-check

### Gates
architecture-guardian, privacy-security (diagnostics are bounded reason codes and class names only), reviewer-strict.

### S7a-2 — architecture review (ACCEPT-WITH-CONDITIONS) and fixes (2026-10-26; uncommitted; docs and comments only except the CANCEL-01 fix)
- F1 (retry policy): the S7a-2 risk note "retries on WorkManager backoff until the next period" was wrong. Correction appended under "Notes and risks" above. For periodic work `Result.retry()` backs off exponentially from 30 s, capped at 5 h, so a persistent per-entry failure gives about 10 attempts in the first ~8.5 h and then one every 5 h. Bounded in rate, idempotent, same as RecurringHorizonWorker. The orchestrator accepts the policy. Optional follow-up for both workers: a `runAttemptCount` cap that ends in success while keeping `ctx.addErrors`.
- F2: LEGAL_PATHS "All 10 production CoroutineWorkers" changed to 12 (9 WorkerSpec.DEFAULTS + notification_intake + 2 action workers), consistent with the S7a-2 row. This resolves the stale-count remark under "Notes and risks".
- F3: LEGAL_PATHS "DatabaseAccessBlockedException before the first entry" changed to "at reconcile start". A barrier that closes mid-reconcile counts as plansFailed; the run then becomes stoppedByBarrier at the first entry, or succeeds if nothing is due.
- F4: accepted, no change.
- F5 (doc drift, comment and docs only): WorkerSpec.kt KDoc "all 8 background workers" now 9; CODEBASE_SEGMENTS Segment 40 now says the worker exists (service/installment/InstallmentDueRecordingWorker.kt); WORKER_BASELINE_INVENTORY (row, registered list 7b, counts), WORKER_REQUIREMENTS_MATRIX (section and privacy summary row) and WORKER_EXECUTION_POLICY (backoff row, parity 9/9, constraints row) list `installment_due_recording` in the existing style: 24h periodic, KEEP, no network, DB write, BlockedPolicy.RETRY, no notification, no privacy gate.
- F6: KDoc on the worker's companion `schedule()`: kept for template parity, same scheduleFromSpec / unique name / KEEP as WorkerRegistry; WorkerRegistry is the production scheduling path. Comment only.
- F7: info, no change.
- CANCEL-01 (fix applied by the orchestrator): the architecture-contracts shard vr-20261026-200658-57f29be7 FAILED on CancellationSafetyArchitectureGuardTest at the coordinator's `recordOneDueEntry` broad catch, because the barrier catch sits between the CancellationException catch and the broad catch. Fix: an explicit `if (e is CancellationException) throw e` in that catch. This touches committed S7a-1 code; the fix ships with the S7a-2 commit.
- Earlier PASS runs (before the CANCEL-01 fix): targeted vr-20261026-195937-ebd96645, cancellation vr-20261026-200511-06687a6c, runtime-ui vr-20261026-200527-33bb0fe4.
- To request (serial): re-run the architecture-contracts shard after the CANCEL-01 fix, plus a targeted run of `*InstallmentRecordDueEntries*` (touched coordinator catch); then static-guards and app-check for the S7a candidate.

### S7a-2 — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27; uncommitted; nothing compiled, run or validated after these fixes)
- ISSUE-1 (fixed): `finishRun` ignored reconcile failures when nothing was due, so a run whose reconcile failed looked clean in the worker run context. Now `ctx.addErrors(failed + reconcile.plansFailed + reconcile.orphanFlagsFailed)` when the sum is above zero, and `ctx.addRowsUpdated(recorded + reconcile repair counts r1..r6 + orphanFlagsCleared)`. The Result mapping is unchanged (reconcile failures alone still give success; only `stoppedByBarrier` or per-entry `failed` retry). New test: `reconcile failures with nothing due are recorded as errors and the Result is unchanged`.
- ISSUE-2 (fixed, test only): the batch-dispatch test now asserts through `ShadowLog.getLogsForTag(InstallmentDueRecordingWorker.TAG)` that no log message contains "/private" or "1234.56" (and that a log was written). The run-level read-failure test was renamed to drop "without leaking its message" because it never asserted that.
- Accepted residual F1: persistent per-entry failure retries on WorkManager backoff (bounded in rate, idempotent, same as RecurringHorizonWorker). Follow-up for both workers: a `runAttemptCount` cap that ends in success while keeping `ctx.addErrors`.
- To request (serial): targeted `*InstallmentDueRecordingWorkerTest*`, then the S7a candidate static-guards and app-check.

### S7a-2 — accepted; S7a COMPLETE (2026-10-27)
- Final tree (serial, PASS):
  - targeted vr-20261026-215055-553c06c1
  - static-guards vr-20261026-215614-e9b548b4
- Earlier S7a candidate runs on the pre-minor-fix tree:
  - architecture-contracts vr-20261026-202935-8d5f60d6
  - app-check vr-20261026-205551-e8e90b57
  - cancellation vr-20261026-200511-06687a6c
  - runtime-ui vr-20261026-200527-33bb0fe4
- Gates:
  - architecture-guardian ACCEPT-WITH-CONDITIONS (fixed)
  - reviewer-strict ACCEPT-WITH-CONDITIONS (ISSUE-1/2 fixed)
- Includes the CANCEL-01 shape fix in the S7a-1 coordinator (`recordOneDueEntry`). LESSON: every coordinator batch must include the architecture-contracts shard.
- Committed. S7a is done (S7a-1 + S7a-2): auto-recording runs daily via InstallmentDueRecordingWorker. The overview-open trigger is S10.
- Follow-ups:
  - runAttemptCount cap for both daily workers (optional);
  - owner-approved S6c follow-ups (owner-approvals item 37);
  - guard strengthening (owner-approvals item 36(a)).
- Next per plan: S7b / S8 (check the plan dependency graph).

## S8a — implemented (2026-10-27; uncommitted; nothing compiled, run or validated)
Resumed after an API-error abort: the first implementer had already edited DashboardBriefingInputBuilder.kt and created the test. The resumed run inspected both, kept them (checked every constructor and import against source), and finished the rest. No new source file, no guard / baseline / allowlist / config edit, no schema change.

### Files (all in C:\Users\panos\Desktop\Cost-agregator-mec)
- M app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt
  - Upcoming items now go through a private explicit allow-list `UpcomingItem.toCloudInputOrNull()`: Recurring, Occurrence and Planned are mapped, `else -> null`.
  - `mapNotNull` runs BEFORE `.take(5)`, so an excluded item is dropped whole (no field copied) and the cap fills from allowed items only.
  - Any future `UpcomingItem` variant (the S8b projection item, which carries displayName) is excluded by default and has to be opted in here. Hard-wired, never a setting. The per-variant currency mapping is unchanged.
- M app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/TransactionInsightInputBuilder.kt: comment only, on the already hard-wired `upcomingItems = emptyList()`.
- New test app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInstallmentExclusionTest.kt (5 tests).
- Both main files are already on the installment_cloud_reachability surface (domain/ai/ prefix and the `*InputBuilder` name pattern). The seam adds no `installment` identifier to executable code, so the guard needs no change.

### What can reach the cloud today (D-4, re-checked at S8a)
- DASHBOARD_BRIEFING: DashboardDataProvider.getProcessedDataFlow produces ProcessedDashboardData. HomeViewModel (UI) and DailyBriefingWorker both consume it, and the worker calls GenerateDashboardBriefingUseCase. That use case calls DashboardBriefingInputBuilder.build, which is the ONLY cloud-side reader of it. It reads:
  - weather.upcomingItems (Recurring / Occurrence / Planned today, from FinancialWeatherRepository.buildUpcomingItems);
  - weather headline, summary, totalCommitted, totalLikely and discretionaryBudget;
  - budget statuses, category breakdown, pendingCount and the summary total.
  - recurringPatterns, plannedExpenses and goals on DashboardData are NOT read.
- TransactionInsight: GenerateTransactionInsightUseCase calls TransactionInsightInputBuilder.build(Expense, shouldRedact). The input is built from one Expense and `upcomingItems` is hard-wired empty. No plan path.
- FinancialWeatherRepository has no installment reference today, so no plan data reaches any cloud payload today. The seam blocks what S8b would add.
- The shared object was NOT separated. The exclusion sits on the cloud consumer side (the builder), so DashboardDataProvider and FinancialWeather (shared with the UI) are untouched and the S8a stop-and-ask was not triggered.

### OPEN ITEM for S8b (stop-and-ask for S8b, not hit in S8a)
- The seam drops projected ITEMS. It cannot drop projected AMOUNTS that S8b folds into the SHARED FinancialWeather totals (totalCommitted, totalLikely, discretionaryBudget, headline, summary). The builder copies those scalars.
- When S8b wires installment projection into the forecast, either:
  - the projection is kept out of those fields and carried separately, for example a UI-only field on FinancialWeather; or
  - the owner is told the briefing payload stays out of v1.
- That is a change to the object shared with the UI and needs an explicit decision. The AE-23 differential test (identical DashboardBriefingInput and prompt with and without plans) is the regression harness S8b must keep green. Today it is satisfied only because no wiring exists.

### AEs / tests
- AE-23 payload part:
  - DashboardBriefingInput and both prompts (redact on and off) contain no lender label, plan uuid, projected minor amount or text, or the word "installment".
  - A with/without-plans differential gives an identical input and identical prompts, with the sums, headline and summary unchanged.
  - The TransactionInsight input is identical with and without plans, has empty upcomingItems, and its prompt is clean.
- The cap is applied after the allow-list mapping (8 allowed items give 5).
- Sealed-variant tripwire: `UpcomingItem::class.sealedSubclasses` must equal {Recurring, Occurrence, Planned}. Adding a variant fails the test until its author adds an explicit exclusion test and reviews the allow-list. A hostile variant cannot be built in the test because sealed subclasses must live in the same package.
- Limit: "with plans" is vacuous until S8b, because no wiring injects projected items. This is stated in the test KDoc.

### Validation to request (serial)
1. targeted `*DashboardBriefing*,*GenerateTransactionInsight*,*ProcessedDashboard*`
2. registered-guard installment_cloud_reachability; registered-guard cloud_payload
3. static-guards
### Gates
privacy-security-guardian (mandatory), reviewer-strict YES.

### S8a — privacy review (privacy-security-guardian ACCEPT-WITH-CONDITIONS) and validation (2026-10-27)
- Validation (serial, all PASS):
  - targeted vr-20261026-225411-efeff4a6
  - installment_cloud_reachability vr-20261026-225945-deecae48
  - cloud_payload vr-20261026-230003-c6b778f5
  - static-guards vr-20261026-230021-0370c5e9
- The seam is correct and fail-closed for any new UpcomingItem variant (allow-list, dropped whole before the cap).
- C1 doc conditions:
  - F2 BINDING S8b: plan projections must NEVER be expressed as an existing UpcomingItem variant (Planned/Occurrence/Recurring). The allow-list checks type, not provenance.
  - F3 BINDING S8b (HIGH): the scalar path is concrete.
    - FinancialWeatherRepository runs ForecastInputAssembler.assemble → SynthesisEngine.synthesize → NarrativeGenerator.generate, which fills headline/summary and totalCommitted/totalLikely/discretionaryBudget.
    - DashboardBriefingInputBuilder copies all of these into the cloud payload.
    - Changing assemble() (in the S8b write set), including the INV-29 baseline exclusion, would therefore feed the cloud briefing.
    - Decision: owner-approvals item 40(a). The FinancialWeather path stays hard-wired installment-free; projections go through a separate flow merged at the UI only; otherwise option B with owner sign-off.
  - F5:
    - tests 1, 2 and 5 of DashboardBriefingInstallmentExclusionTest are PLACEHOLDERS until S8b injects projections;
    - the plan-A8 "cover the DailyBriefingWorker path" requirement is DEFERRED to S8b;
    - S8b must rewrite the differential end to end: real FinancialWeatherRepository (real ForecastInputAssembler + SynthesisEngine) with a fake projection port returning a projected entry → GenerateDashboardBriefingUseCase / DailyBriefingWorker → CloudDashboardBriefingService prepared text.
- C2: INV-31 vs §11.5 clarified (owner-approvals item 40(b)). See the spec amendment.
- FYI (owner-approvals 40(c)): unsalted SHA-256 merchant pseudonyms on some cloud paths. Pre-existing; separate ticket.

### S8a — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes (2026-10-27; uncommitted; test and docs only; nothing compiled, run or validated after these fixes)
- Validation run IDs (all PASS, before these fixes):
  - targeted vr-20261026-225411-efeff4a6
  - installment_cloud_reachability vr-20261026-225945-deecae48
  - cloud_payload vr-20261026-230003-c6b778f5
  - static-guards vr-20261026-230021-0370c5e9
- ISSUE-1 (S8b entry condition): plan S8b "Goal: INV-29 (forecast side)" and the ForecastInputAssembler write-set line are marked SUPERSEDED by A15. Domain-spec INV-29 amended: the forecast-baseline exclusion and projected entries live ONLY on a separate installment-aware entry point (separate file) feeding the UI flow, never on `assemble()` as called by FinancialWeatherRepository. Home keeps INV-29 without double counting: the UI-side installment-aware weather replaces the baseline view for Home only; the cloud path keeps the installment-free one.
- ISSUE-2 (S8b entry condition, appended to A15): the merge point is downstream of `getProcessedDataFlow` (HomeViewModel/UI layer), never in DashboardDataProvider, DashboardData, ProcessedDashboardData or FinancialWeather. S8b's proposal to add FinancialWeatherRepository.kt, ForecastInputAssembler.kt, SynthesisEngine.kt, NarrativeGenerator.kt and DashboardDataProvider.kt as exact-file entries on the installment_cloud_reachability surface (with a negative fixture) is a GUARD STRENGTHENING and needs owner approval at S8b start (stop-and-ask).
- ISSUE-3 (appended to A15 and the spec amendment): the differential holds expense rows, link state and spending-predicate state constant; only the projection-port output varies. Spending aggregates follow ordinary aggregate semantics (PURCHASE_ATTACHED and S6c-3 recurring exclusion legitimately change them), so they are not part of the differential.
- ISSUE-4 (test labels): in DashboardBriefingInstallmentExclusionTest, tests 1, 2 and 5 are marked PLACEHOLDER until S8b (A15/F5); class KDoc changed from "keep green" to "replace"; the fake `check(projectedPlanEntries().isNotEmpty())` / `assertTrue` precondition asserts were removed, and the unused projectedPlanEntries() helper and its two imports were deleted.
- ISSUE-5 (cap-order test): added `unmapped variants ahead of five allowed items are dropped whole before the cap`. Three `mockk<UpcomingItem>()` stand-ins with plan-like description, amount and date sit at positions 1-3 ahead of five Planned items; it asserts five allowed items remain and that no stubbed fact appears in the input, the prompt (redact on and off), or the payload text. The existing "cap is applied after the allow-list mapping" test is kept. UNVERIFIED: mocking the sealed UpcomingItem was not run (jvmTarget 11, MockK 1.13.8). If the targeted run shows the mock cannot be created, fall back: rename it to "upcoming items cap at five" and note in KDoc that drop-before-cap ordering is enforced by code review and the tripwire only.
- Re-validation to request (serial): targeted `*DashboardBriefingInstallmentExclusion*`. No production, guard or config change, so the guard runs above are not invalidated.
- (2026-10-27) ISSUE-5 fallback applied. The mock test failed (vr-20261026-233938-2511073f): MockK instantiates a real sealed subclass (it called getOccurrence), so an unmapped UpcomingItem cannot be planted. The test was removed and the cap test renamed "upcoming items cap at five". Its KDoc states that drop-before-cap ordering is enforced by code review plus the variant tripwire only. Test-only change.
- S8a accepted (2026-10-27).
  - Final targeted run vr-20261026-234410-c132f55c PASS. The edits since the guard runs are test and docs only.
  - Gates: privacy-security-guardian ACCEPT-WITH-CONDITIONS; reviewer-strict ACCEPT-WITH-CONDITIONS. Conditions met or recorded as S8b entry conditions in A15 and the spec amendment.
  - Committed.
  - Next loan slices: S7b (reminders) or S9 (export).
  - S8b entry conditions: A15, plus owner approval for the cloud-reachability guard strengthening.

## S9 — implemented (2026-10-27; uncommitted; nothing compiled, run or validated)
OD-11 / INV-1 export marking. No schema change, no db_ownership rows (pins stay 452), no guard / baseline / allowlist / config edit, no DAO call, no coroutine code added.

### Finding that shaped the slice
Every export total already used `Expense.effectiveAmount`, which returns 0 for a flagged row (S3). The totals therefore already follow INV-1 and were NOT reimplemented. `ExportOptionsViewModel` computes no totals (it streams rows), so it is untouched. S9 only adds the marker.

### Files (all in C:\Users\panos\Desktop\Cost-agregator-mec)
- New app/src/main/java/com/yourname/expensetracker/domain/export/ExportInstallmentMarker.kt. It is a label helper only: `markerFor(reason|expense)`, `notesWithMarker`, `stripFromNotes`, `countByMarker`. It decides no spending rule.
- M domain/export/ExpenseExportMapper.kt: `ExportTransaction.notes` is now `notesWithMarker(expense.notes, markerFor(expense))`. Unflagged rows are unchanged (the same reference). `amount` and `effectiveAmount` are untouched.
- M domain/export/AccountantReportPdfExporter.kt: new `internal installmentMarkerSummaryLines()`, written under "Transaction Count" in the Summary. Totals, breakdown and large-transactions are unchanged (already `effectiveAmount`).
- M domain/business/BusinessExpenseReportGenerator.kt: the CSV Notes cell carries the marker; the TOP 10 and MISSING RECEIPTS lines get a ` [marker]` suffix (private `installmentMarkerSuffix`). Totals are unchanged.
- M util/CsvExpenseImporter.kt, util/JsonExpenseImporter.kt: strip a leading marker from notes (two lines in the JSON importer, one in CSV, plus imports).
- New test app/src/test/java/com/yourname/expensetracker/domain/export/ExpenseExportInstallmentMarkingTest.kt (8 tests).

### Formats and fields used
| Format | Marker field | Totals |
|---|---|---|
| Generic CSV (ExportOptionsViewModel.writePage) | `Notes` column, via `ExportTransaction.notes` | none computed; `EffectiveAmount` column is 0 for flagged rows |
| Generic JSON | `notes` | none computed; `effectiveAmount` 0 for flagged rows |
| QuickBooks IIF | `MEMO` (= notes) | none; the row writes `amount` (full) |
| Accountant PDF | summary lines (the PDF has no per-row table) | `effectiveAmount` (INV-1) |
| Business report text | suffix on TOP 10 / MISSING RECEIPTS lines | `effectiveAmount` (INV-1) |
| Business CSV | `Notes` column | `Amount` column is `effectiveAmount` (0 for flagged rows) |
| Xero CSV, FreshBooks CSV | NONE (see stop-and-ask) | none; the row writes `amount` (full) |

**Correction (owner-approvals 41/42; the table above is the pre-decision S9 state):**
| Format | Flagged rows now |
|---|---|
| Xero CSV, FreshBooks CSV, QuickBooks IIF | EXCLUDED (not written; empty check, validation sample and recordCount use unflagged rows only). The QuickBooks IIF `MEMO` row above is superseded. |
| Generic CSV, Generic JSON, accountant PDF, business report | INCLUDED and marked (Notes/notes, PDF summary lines, report suffix) as in the table |

### STOP-AND-ASK (plan S9): Xero and FreshBooks have no type/notes field
- Their columns are Date, Description (= merchant), Amount, Currency, Account/Category, Reference/Vendor, plus the FX columns. There is no notes or type column.
- The mapper-level marker never reaches them; I did not change their column set or the Description.
- Consequence today: a flagged 600.00 purchase row is exported with Amount 600.00 and no marker, and the plan's installments are exported as well, so a downstream tool that sums the file double counts. This also affects QuickBooks, which writes the full `amount`, though it is marked in MEMO.
- Options for the owner:
  - (a) Exclude flagged rows from the Xero and FreshBooks files.
  - (b) Add a column.
  - (c) Suffix the marker onto Description (not recommended: it alters the merchant text).
- Not implemented; needs an owner decision. (a) is a small filter in `writePage` and `AccountingExportRepository`.

### Marker text and localization
- `[not counted (installment plan)]` for INSTALLMENT_PURCHASE (an unknown non-null reason fails closed to the same text), `[refunded]` for INSTALLMENT_REVERSED.
- NOT localized: exports are English-only today (hardcoded English headers, "Accountant Report", "Transaction Count", "Total Business Expenses"). No string resources were added, so no es/el review is needed. If exports are localized later, the two constants are the single place to change.
- Prefixed to existing notes: `[refunded] original note`.
- Plan privacy: only a fixed string is written. No plan displayName, lender, schedule or plan id is exported. The merchant is the expense's own merchant.

### Importer behaviour
- `CreateExpenseRequest` has no spending-exclusion field, so re-import can never create a flag (the test asserts this by reflection).
- The CSV importer and both JSON paths (v2 and v1) strip a leading marker from notes, so the marker is not stored as user text. A user note that merely contains the text elsewhere is untouched. Unmarked notes are unchanged.
- Amount precedence is unchanged: `amount` first. So re-importing a flagged 600.00 row creates an ordinary 600.00 expense that counts as spending. The marker is ignored by design (spec: "importers ignore the marker"), but this is a double-count path if the installments are also imported. See risks.

### Risks
- Re-import double count (above). Option for the owner: the importers skip rows carrying the marker (changes the "ignore" contract), or import them with the effective amount. Not done.
- Xero and FreshBooks unmarked (stop-and-ask above).
- Business CSV `Amount` is `effectiveAmount`, so a flagged row shows 0 (pre-existing behaviour for not-mine rows; consistent with INV-1), with the marker in Notes.
- The two deprecated private ViewModel writers `streamGenericCsvExport` and `streamJsonExport` have no callers and were left alone.
- A user note that already starts with the marker text would be stripped on import (harmless).
- Tests are unrun. The IIF test assumes `CsvCellSanitizer.sanitizeIif` leaves `[`, `(` and `)` intact. The business-report test uses mockk relaxed mocks following BusinessExpenseReportGeneratorTest. The sealed-mock caveat of S8a does not apply (no sealed type is mocked).

### Validation to request (serial)
1. targeted `*Export*,*AccountantReport*,*BusinessExpenseReport*`. This also covers the importer edits through the `*Import*` tests: add `*CsvExpenseImporter*,*JsonExpenseImporter*` to the filter.
2. unit-test-shard domain-a-m (and `util` tests if they sit in a different shard).
3. static-guards: this diff touches no guard-relevant file, but `util/*Importer.kt` is on the import surface. Run it once at the batch gate.
### Gates
privacy-security-guardian, reviewer-strict YES (money, export). Tests to add beyond this file, if wanted: an importer-level test that feeds a marked CSV/JSON row and asserts the `CreateExpenseRequest.notes` (needs the importer's mocks).

### S9 — owner-gate decisions applied (owner-approvals 41)
(2026-10-27; uncommitted; nothing compiled, run or validated.) Supersedes the stop-and-ask and the re-import double-count risk above. No schema change, no db_ownership rows, no new strings, no guard/baseline change, no coroutine code added.

41(a) Xero and FreshBooks EXCLUDE flagged rows (INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED). No new column, no Description change. QuickBooks IIF, generic CSV/JSON, PDF and business report keep the marker behaviour.
- domain/export/ExportInstallmentMarker.kt: new single rule `isInstallmentFlagged(reason | Expense | ExportTransaction)` (any non-null reason; unknown codes fail closed to flagged), and `isMarkedNotes(notes)`. `stripFromNotes` REMOVED (unused); `notesWithMarker` no longer strips. KDoc updated.
- domain/export/ExportTransaction.kt: new defaulted field `spendingExclusionReason: String?` (carries the stored code only; never written to any output column). domain/export/ExpenseExportMapper.kt: populates it.
- domain/export/AccountingExporters.kt: `XeroCSVExporter.writeExpense` and `FreshBooksExporter.writeExpense` return early on `isInstallmentFlagged`. Every path (the repository `export(...)` and the ViewModel streaming `writeExpense` at ExportOptionsViewModel 666/692) goes through `writeExpense`, so data/repository/AccountingExportRepository.kt and ExportOptionsViewModel.kt needed no edit.

41(b) Importers SKIP rows whose notes begin with either export marker (via `ExportInstallmentMarker.isMarkedNotes`).
- util/CsvExpenseImporter.kt: returns `RowResult.Duplicate("INSTALLMENT_EXPORT_MARKER")` (bounded code), so it is counted in the existing `duplicates` counter, which ImportCoordinator already maps to `skippedCount`. No new result type.
- util/JsonExpenseImporter.kt: the row loop does `skipped++; continue` before v1/v2 parsing (covers both paths); `parseV2Row`/`parseV1Row` use plain notes again.
- A user note that itself begins with a marker string is now skipped on import (accepted, per decision).

Tests (domain/export/ExpenseExportInstallmentMarkingTest.kt; the old strip test was replaced):
- `isInstallmentFlagged is the single rule for expense and export transaction`, `isMarkedNotes detects both markers and nothing else`.
- `xero export omits flagged rows and leaves unflagged rows unchanged`, `freshbooks export omits flagged rows ...` (output equals the unflagged-only export; no marker text).
- `csv re-import skips marked rows, reports them skipped and imports unflagged rows normally` (imported 1, duplicates/skipped 2, errors 0, no flag field on CreateExpenseRequest).
- `json re-import skips marked rows in v2 and v1 ...` (imported 1, skipped 2, errors 0).
Other existing tests unchanged: QuickBooks marker test and report tests still apply.

Known residuals: `accountingExportPolicy.validateAccountingDataset` still validates flagged rows before the exporter drops them; the Xero/FreshBooks file row count can be lower than the selected-range count. CSV importer skips marked rows only after date/amount presence checks (an exported row always has both).
Validation to request: targeted `*ExpenseExportInstallmentMarkingTest*,*CsvExpenseImporter*,*JsonExpenseImporter*,*AccountingExport*,*XeroCSV*,*FreshBooks*`, then the batch static-guards gate.

### S9 — owner-approvals 42 applied (IIF exclusion, filtered checks) (2026-10-27)
- The implementer died on an API error after the code edits; the orchestrator wrote this note.
- QuickBooksIIFExporter.writeExpense returns early on isInstallmentFlagged (AccountingExporters.kt:61), the same rule as Xero (:105) and FreshBooks (:162). The test `quickbooks iif omits flagged rows and leaves unflagged rows unchanged` is updated.
- Filtered pre-checks: AccountingExportRepository :111 and ExportOptionsViewModel :380 / :915 filter flagged rows before the empty check, the policy validation and the counts, for the accounting formats.
- Follow-up (minor): the "all-flagged range is refused as empty / recordCount equals written rows" test was not added.
- Test compile fix (orchestrator): @Suppress("DEPRECATION_ERROR") on the two mocked createExpense calls (the importers use the same legacy call with the same suppression). The earlier targeted vr-20261027-002059-97acd6b6 and domain-a-m vr-20261027-002415-e47a84d7 FAILED on that compile error only. static-guards vr-20261027-002504-a6845b71 PASS.
- privacy-security-guardian S9: PASS, ACCEPT-WITH-CONDITIONS. C1 (IIF) is met by item 42. Info items:
  - F3: the deprecated ViewModel writers have no callers; any revival must go through the mapper.
  - F4: pre-existing raw exception messages in JsonExpenseImporter; separate ticket.
  - INV-1: transaction counts still include flagged rows (labelled).
- (2026-10-27) Missing import of ExportInstallmentMarker in ExportOptionsViewModel added (the agent died before adding it). Targeted vr-20261027-005933-a5d48ca3 and domain-a-m vr-20261027-010050-7e283be1 FAILED on that compile error only.

### S9 — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes
(2026-10-27; uncommitted; tests and comments/docs only; nothing compiled, run or validated after these fixes.)

Conditions closed:
- ISSUE-1 (tests for owner-approvals 42 filtered pre-checks):
  - ExportOptionsViewModelTest (ui/screens/export), `installment accounting pre-check ...` group (7 tests): all-flagged Xero refused with "non-empty dataset" and no file left; all-flagged FreshBooks and QuickBooks refused; mixed range validates only the unflagged sample (a flagged USD row in an EUR range no longer trips SINGLE_CURRENCY, and the flagged row is not written); an unflagged USD row still trips SINGLE_CURRENCY (control); >10,000 range pages `getExpensesPage` to count unflagged rows (cursor of each previous page's last row verified) and refuses an all-flagged range; a later-page unflagged row is not refused; a range of exactly 10,000 uses the sample and never pages; generic CSV keeps flagged rows with the marker (filter is accounting-only).
  - AccountingExportRepositoryTest (data/repository), `installment exclusion ...` group (4 tests): recordCount equals the unflagged count and flagged merchants are absent for Xero, FreshBooks and QuickBooks; all-flagged range fails with "No expenses found" for all three; flagged USD row does not trip single-currency; unflagged USD still does.
- ISSUE-2 (comments): ExportInstallmentMarker.kt and ExportTransaction.kt KDoc now say all three accounting formats (Xero, FreshBooks, QuickBooks IIF) exclude flagged rows, while generic CSV/JSON, the PDF and the business report mark them. The "Formats and fields used" table above carries a correction block.
- ISSUE-3 (docs): "amended by owner-approvals 41/42" notes added to spec section 11.4 (accounting formats exclude flagged rows; importers skip marked rows) and plan S9 (importers SKIP marked rows; IIF excluded).

Non-blocking follow-ups (not fixed):
- The format-selection count (shown before generating) is unfiltered for accounting formats, so it can exceed the number of rows actually written.
- Mixed currency caused by a flagged row in the business report: the report still includes flagged rows, so a flagged row in another currency can make the report mixed-currency.
- The PDF can show a zero-total currency section when a currency has only flagged rows (effective amount 0).

Validation to request: targeted `*ExportOptionsViewModelTest*,*AccountingExportRepositoryTest*,*ExpenseExportInstallmentMarkingTest*` (then the batch domain-a-m shard and static-guards as already planned). Possible compile risks to check first: `Expense.copy(spendingExclusionReason = ...)` in the ViewModel test, `File.readText` of the final export path.
- S9 accepted (2026-10-27).
  - Final targeted vr-20261027-020618-e0b152e1 PASS. The edits after the production-code runs below are tests, comments and docs only.
  - Production-code runs, all PASS: targeted vr-20261027-010708-c693e1ad, domain-a-m vr-20261027-011225-55b56876, runtime-ui vr-20261027-011356-a1873cf1, static-guards vr-20261027-011529-6f7f0270.
  - Gates: privacy-security-guardian PASS; reviewer-strict ACCEPT-WITH-CONDITIONS (C1 tests added, C2 docs).
  - Committed. Next loan slice: S7b (reminders).
  - Approved follow-ups pending: item 36(a) guard strengthening, item 37 follow-ups, and S8b (needs owner approval for the cloud-reachability guard strengthening).

## S7b — implemented (2026-10-27; uncommitted; nothing compiled, run or validated)

Legal path: read-only via `InstallmentProjectionPort` (plain injection, no cycle found, so no `dagger.Lazy`). No DAO write, no schema change (db pin stays 452), no second reminder system. INV-15, INV-31; AE-7, AE-11, AE-31.

Files:
- `M:domain/reminder/BillReminderManager.kt`: new ctor params (port, ledger, `BillReminderSettingsRepository`). New `getDueInstallmentReminders()`, `claimInstallmentReminder()` (claim, then re-read the port and match `planUuid:seq:recordGeneration`; claim released when the entry is no longer projected, e.g. paid, linked, skipped, stopped, completed, or a bumped generation), `releaseInstallmentReminder()`, `buildInstallmentReminderText()`. Reminder window = `DEFAULT_REMINDER_DAYS` (3) from today; overdue entries are never projected (INV-15), so there is no overdue installment reminder.
- `N:domain/reminder/InstallmentReminderLedger.kt`: dedupe ledger (interface + SharedPreferences impl, same pattern as `BillReminderSettingsRepositoryImpl`). Holds only `planUuid:seq:recordGeneration` and an integer due day; pruned after 60 days. Not Room. Bound in `M:di/ReminderSettingsModule.kt`.
- `M:service/reminder/BillReminderWorker.kt`: ctor gets `BillReminderManager` (last param); `dispatchInstallmentReminders(ctx)` runs inside the existing `WorkerExecutionGuard` block after the settings and quiet-hours check and before the recurring dispatch, in its own try (CancellationException rethrown, other failures logged by class name only so the recurring path still runs). Installment notifications use the same channel and no Snooze/Dismiss actions (those receivers act on `recurring_reminder_deliveries` ids only). Notification id = `NotificationId.forBill(dedupeKey.hashCode())`.
- Tests: `N:domain/reminder/BillReminderManagerInstallmentTest.kt`; ctor updates in `BillReminderManagerTest.kt`, `BillReminderWorkerTest.kt`, `BillReminderWorkerTimeProviderTest.kt`.

Deviation from the plan write set: the new ledger file and the DI binding were needed because installments have no `recurring_reminder_deliveries` row and no schema change is allowed.

Privacy handling of displayName: the notification text is identical in shape to the recurring bill reminder (hard-coded title "Bill due", body "<name> due: <amount> <currency>"; no lock-screen public version is set, so Android default VISIBILITY_PRIVATE applies, as for bills). displayName is used exactly where the bill merchant is. Lender, n/N, schedule, status and planUuid are never in the text. A blank displayName (INV-34 / AE-31 redacted restore) falls back to the existing generic "Bill" (the same fallback as a recurring bill without a merchant): the user still learns that a payment is due, nothing is revealed, and the title/body are never empty. `InstallmentReminder.toString()` is redacted. Logs and diagnostics carry no plan data (no DiagnosticEvent is emitted for installment reminders, because the event entity types are recurring-delivery based).

Strings: none added. The existing bill reminder text is hard-coded English in the worker; installment reminders match it. Owner es/el review: nothing new, but the hard-coded English notification text (existing debt, also for bills) stays untranslated.

Risks:
- At-most-once delivery: the ledger claim is written before the post. A process death between claim and post loses that one reminder (no retry). Failed posts release the claim.
- The ledger lives in SharedPreferences, so a backup restore or data clear can repeat a reminder once.
- The notification id hash can collide with a recurring bill notification id in the 30000-39999 range (same inherent risk as `forBill`).
- Reminder tap routing to plan detail is still S10a.

Validation to request: targeted `*BillReminder*` (covers manager, installment, both worker tests); shard `domain-a-m`; shard `runtime-ui` (worker changed); registered-guard cancellation guard (worker cancellation rules); `architecture-contracts` (new file under domain/reminder using android Context, same as the settings repo) and Hilt graph compile (new binding). privacy-security-guardian and reviewer-strict gates still due (worker path).

### S7b — privacy review (ACCEPT-WITH-CONDITIONS) and fixes

Conditions:
- C1 (tests, done, not yet run): new `N:service/reminder/BillReminderWorkerInstallmentTest.kt` (Robolectric, same harness as `BillReminderWorkerTest`). Covers: CancellationException from `getDueInstallmentReminders` propagates and the recurring path is not run; a RuntimeException from it does not block the recurring dispatch (`markReminderSent` still called), result stays `Result.success()`, log is class name only with no plan data; a failed post (SecurityException permission_denied, and generic RuntimeException) calls `releaseInstallmentReminder(claimed)` exactly once with that candidate; a post CancellationException propagates with no release; a successful post does not release and posts one notification (title "Bill due", id in 30000-39999, no actions) via the real `NotificationManagerCompat` + ShadowNotificationManager; claim returning null neither posts nor releases; reminders disabled, whole-day quiet hours: `getDueInstallmentReminders`/`claimInstallmentReminder` never called; quiet hours enabled but outside the window, and the enabled control: claim and post happen once. Permission-denied post is testable here via the existing static `NotificationManagerCompat.from` mock pattern (mockkStatic), so no seam gap. Not covered: the real Android runtime permission-denied path (device behaviour), only the SecurityException contract.
- C2 (redaction, done by the orchestrator): `InstallmentReminderText.toString()` returns `InstallmentReminderText(redacted)` in `domain/reminder/BillReminderManager.kt` (next to the existing `InstallmentReminder.toString()` redaction).

Follow-ups (from privacy-security-guardian, not blocking S7b):
- (3) Add a separate `NotificationId.forInstallment` sub-range so installment ids cannot collide with `forBill` hash ids (S10a).
- (4) Cancel an already posted installment notification when the entry is paid or the plan is stopped or deleted (S10a).
- (5) Ledger pruning happens only on claim, so ledger keys of deleted plans linger until the next claim (opaque ids only, no plan data).
- (7) Pre-existing app-wide check: `NotificationFilter.IGNORED_PACKAGES` lacks the app's own package, so the notification capture service might capture the app's own reminder notifications. Verify before release.
- INFO: device-to-device transfer is not excluded app-wide; installment amounts are formatted with `toPlainString` while bills use the locale `%.2f` (cosmetic).

Validation to request: targeted `*BillReminderWorkerInstallmentTest*,*BillReminderWorkerTest*,*BillReminderWorkerTimeProviderTest*,*BillReminderManagerInstallmentTest*` (or `*BillReminder*`).

### S7b — reviewer-strict REJECT (iteration 1) fixes

Implemented (production only; nothing compiled, run or validated; tests are for the test-author). Owner-approvals item 44 records the delivery semantics.

- ISSUE-1 (checkpoint swallowed): `BillReminderWorker` installment dispatch try now rethrows `WorkerCheckpointBlockedException` before the generic catch (CancellationException rethrow kept), as `NotificationIntakeWorker`. The guard then sees the blocked checkpoint (RETRY) and the recurring coordinator is not reached. The other catches on the installment path (`sendInstallmentNotification`) wrap only `ensureChannelExists`/`build`/`notify`; no checkpoint runs there.
- ISSUE-2 (claim leak): the manager wraps the post-claim revalidation in `try/catch Throwable` -> `withContext(NonCancellable) { release }` -> rethrow. The worker wraps text build, id, channel, `Builder.build()` and notify in the same release-and-rethrow; `ensureChannelExists()` and `build()` moved inside `sendInstallmentNotification`'s try (a failure there is `Failed("notification_error")` and releases). CancellationException is never swallowed. `markSent` runs after the post, outside the release try.
- ISSUE-3 (at-most-once after process death): two-phase `InstallmentReminderLedger`. Value per opaque key is `dueDay|claimedAt|sent` (SharedPreferences string, no plan data). `tryClaim(key, dueEpochDay, todayEpochDay, nowMillis)` succeeds when the key is absent, or recorded for another due day, or unsent and older than `STALE_CLAIM_THRESHOLD_MS` (= `RecurringLifecycleCoordinator.STALE_CLAIM_THRESHOLD_MS`, 5 minutes, same as recurring `recoverStaleClaimedDeliveries`). `markSent(key, dueEpochDay, nowMillis)` finalizes after a successful notify; a sent key is never claimed or released again. `claimedAt` comes from `TimeProvider.now()`. `commit()` result is checked: a failed write means the claim is not obtained (no post). An unreadable record fails closed (suppressed). 60-day prune kept and now also runs on read (`getDueInstallmentReminders` calls `prune(today)`), which closes privacy finding 5.
- Due-date change (item 4): `blocks()` ignores a record whose due day differs from the entry's current due day, so an E3 edit re-reminds. Finding: `InstallmentPlanDao.updateEntryScheduleIfScheduled` (used by `rescheduleEntry`, called for RESPREAD at InstallmentPlanLifecycleCoordinator ~:784/:858 and DUE_DAY_CHANGED at ~:909) rewrites `dueDate` (and amount) of a SCHEDULED entry WITHOUT bumping `recordGeneration`. So E3 (DUE_DAY_CHANGED) can change a SCHEDULED entry's due date with the same dedupe key; the due-day check covers it. Extend/RESPREAD keeps the due date but changes the amount; the amount alone does not re-remind (accepted). Only `REVERT_PAID_BEFORE_TRACKING` (no bump, status only) and unlink/reverse paths touch generation; a PAID_BEFORE_TRACKING entry is not SCHEDULED so it is never listed. Additionally the manager releases the claim when the revalidated entry's due day differs from the claimed one (moved between list and claim).
- ISSUE-5 (amount format): installment text now uses `"%.2f".format(BigDecimal)` in the default locale, like the bill reminder (`"%.2f".format(Double)`), with no Double conversion. Scale 2 is the currency scale because only currencies for which `InstallmentMinorUnits.isSupportedScale` holds (exactly 2 digits) are projected; it equals the bill path's `%.2f`.
- ISSUE-4 (notification id sub-range): NOT done here; follow-up for S10a (touches `NotificationIdGenerator`).

New/changed signatures: `InstallmentReminderLedger.tryClaim(key, dueEpochDay, todayEpochDay, nowMillis)`, `markSent(key, dueEpochDay, nowMillis): Boolean`, `isClaimed(key, dueEpochDay, nowMillis)`, `release(key)` (unsent only), `prune(todayEpochDay)`; `BillReminderManager.markInstallmentReminderSent(reminder): Boolean`. `markSent` takes the due day (deviation from a bare `markSent(key)`) so it can upsert when the claim record is gone.

Validation to request: targeted `*BillReminder*` plus a new ledger test class; shard `domain-a-m`; registered-guard cancellation guard; Hilt graph compile (ledger signature changed, binding unchanged).

### S7b iteration-1 tests (test-author; nothing compiled or run)

Files: `domain/reminder/BillReminderManagerInstallmentTest.kt` (rewritten, recording `FakeLedger` on the new signatures, `FakeProjectionPort`/`FakeSettingsRepository` can fail from the Nth call), new `domain/reminder/InstallmentReminderLedgerImplTest.kt` (Robolectric, real SharedPreferences; also drives the manager over the real ledger), `service/reminder/BillReminderWorkerInstallmentTest.kt` (updated). `BillReminderManagerTest`, `BillReminderWorkerTest` and `BillReminderWorkerTimeProviderTest` needed no change (relaxed mocks, no ledger/manager signature use).
- Priority 1: blocked `installment_reminder` checkpoint -> `BlockedRetry` -> `Result.retry()`; no claim, no `recoverAndGetDueReminders`, no "dispatch failed" warning. The guard is the same stand-in model used by `WarrantyExpirationWorkerTest` (the real `WorkerExecutionGuard` is not driven here).
- Priority 2/3: manager revalidation throw (port, CancellationException, settings) releases and rethrows; worker text-build throw/cancel and post cancel release; channel creation failure is `Failed("notification_error")` and releases; Sent -> `markInstallmentReminderSent` once, never release; failed post -> release, never mark; mark-sent failure logs and does not release. The old test "post cancellation ... releaseInstallmentReminder exactly 0" was changed to exactly 1 (the worker now releases on any throw after the claim).
- Priority 4: ledger tests as listed in the task. The commit-failure case uses a mocked `Context`/`SharedPreferences` whose editor `commit()` returns false (real prefs cannot fail a commit). The record `toString` redaction is checked by reflection on the private nested `InstallmentReminderLedgerImpl$Record`. The raw `k:<key>` = `dueDay|claimedAt|sent` layout is read/planted only for the corrupt-value and claimedAt-kept cases.

## S6c-F.2 — implemented (2026-10-27; owner items 37(a) + 37(c); uncommitted; nothing compiled, run or validated)

Legal path: read-only through `RecurringInstallmentEligibility` (the single owner, INV-5 / INV-29, decision 46(b)). No new DAO query, no new `@Query`, no schema change (pins stay at 452), no new catch, no new logging beyond one `Timber.d` count line in the detector (same style as the existing size-check line), no CancellationSafety allowlist change.

Source files:
- `M:domain/analytics/InsightsEngine.kt`: new last ctor param `installmentEligibility: RecurringInstallmentEligibility`. In `findAnomalies`, `excludedSnapshotIds(safeExpenses)` is computed once right after `safeExpenses`, outside any try. The ids are dropped from the merged result (`merged.values.filter { it.expense.id !in installmentExcludedIds }` before sort and `take(10)`), which covers BOTH paths by id. Baselines (`historicalPurchasesByMerchant`, the recurring-keys path, `AnomalyDetector` inputs) are untouched. A reader failure propagates to the existing anomalies stage catch (`catch (e: CancellationException) { throw e } catch (e: Exception) { ...; null }` at the `anomaliesDeferred` async) = no anomalies (fail-closed); CancellationException is rethrown there (confirmed). AIML-13 KDoc extended.
- `M:domain/subscription/NotificationSubscriptionDetector.kt`: new last ctor param `installmentEligibility`. After the existing size check, `excludedExpenseIds(transactions.map { it.expense })` once, filter by id, re-check `MIN_TRANSACTIONS`, then group over the filtered list. No catch added. The pipeline is not edited.
- `M:domain/recurring/RecurringInstallmentEligibility.kt`: KDoc consumer list only (fourth and fifth consumers).

Hilt notes: `InsightsEngine` already depends on `RecurringExpenseEngine`, which takes the same `@Singleton RecurringInstallmentEligibility` (holds a `dagger.Lazy<InstallmentEntryLinkReader>`), so no new edge into the reader graph. `NotificationSubscriptionDetector` is injected only into `NotificationProcessingPipeline`; the helper builds nothing on construction (lazy reader), so no cycle is possible through the detector. No `@Provides`/module builds either class; no main-source manual construction exists. No androidTest construction site exists (grep of `InsightsEngine(` and `NotificationSubscriptionDetector(` in `app/src/androidTest` = 0). The detector has no test construction site (pipeline tests use `mockk<NotificationSubscriptionDetector>(relaxed = true)`).

Tests (ctor-only, no assertion changes): new shared helper `N:test/.../domain/analytics/NoPlansInstallmentEligibility.kt` (`noPlansInstallmentEligibility()`, a mock reader returning empty sets, same shape as the `RecurringExpenseEngineTest` setup). Updated: `InsightsEngineTest`, `InsightsEngineValidationTest` (2 sites), `InsightsEngineDeepTest`, `InsightsEngineEdgeCaseTest`, `InsightsEngineNormalizedDailyMoneyTest` (positional), e2e `AnalyticsPipelineTest`, `NotificationParserDashboardTotalSeamTest`, `FlowPipelineTestHarness`, `verification/CrossEngineParityMatrixTest`, `ui/screens/analytics/AnalyticsViewModelInsightsTest` (2 sites). New: `N:domain/analytics/InsightsEngineInstallmentAnomalySuppressionTest.kt` (F-AE-2..4) and `N:domain/subscription/NotificationSubscriptionDetectorInstallmentExclusionTest.kt` (F-AE-5). Docs: `ENGINE_INTERACTION_MAP.md` (INV-29 consumer block and the SAFE-to-change row).

Risks:
- Drop-after-merge masks a non-linked same-merchant spike on the merchant path: that path keeps only the largest current-month purchase per merchant, so if that largest one is installment-linked, a smaller non-linked spike at the same merchant is not surfaced (and a linked candidate can use one of the top-5 slots before the drop). This follows the plan text (drop from the merged result); a pre-filter of the current-month candidates would remove it but moves the baseline boundary, so it was not done.
- The snapshot form cannot see the INSTALLMENT_PURCHASE / _REVERSED flag (known gap, harmless for effectiveAmount = 0 rows).
- The detector test makes the `MerchantNormalizer` throw so the detector's existing lower-case fallback groups merchants (no canonical fixture).
- A reader failure now silences all anomalies (fail-closed by design, spec).

Targeted filter: `*InsightsEngine*,*NotificationSubscriptionDetector*,*AnalyticsPipeline*,*NotificationParserDashboardTotalSeam*,*CrossEngineParityMatrix*,*AnalyticsViewModelInsights*,*CancellationSafetyArchitectureGuard*,*RecurringInstallmentEligibility*`, plus the `FlowPipelineTestHarness` users (grep for the class name).
- Priority 5-8: D+1 re-listing after markSent and the moved-due-day release are in both the fake-ledger manager test and the real-ledger integration; PAID_BEFORE_TRACKING never listed (and revalidated away after listing); redaction of `InstallmentReminderText`/`InstallmentReminder`; German locale gives a comma (default locale restored in `finally`); AE-11 now uses a COMPLETED plan that still carries SCHEDULED in-window entries (only `prune` touches the ledger) plus a plan completed between listing and claim.
- Not testable: build-failure inside `NotificationCompat.Builder.build()` (no seam; the channel-creation failure covers the same catch). Exception identity is asserted through the cause chain because coroutine stack-trace recovery may copy the exception.

Filter to request: `*InstallmentReminderLedgerImplTest*,*BillReminderManagerInstallmentTest*,*BillReminderManagerTest*,*BillReminderWorkerInstallmentTest*,*BillReminderWorkerTest*,*BillReminderWorkerTimeProviderTest*` (or `*BillReminder*,*InstallmentReminderLedgerImplTest*`).

### S7b — reviewer-strict iteration 2 (ACCEPT-WITH-CONDITIONS) and fixes (orchestrator, 2026-10-27)
- **Finding 1 (claim discarded by prompt cancellation):** fixed. The worker wraps `claimInstallmentReminder` and, on CancellationException, releases under NonCancellable and rethrows. `release` removes only an unsent record, so it can never un-claim a sent key.
- **Finding 2 (a failing release hides the original):** fixed at both sites. A release failure is added via `t.addSuppressed(r)`, and then the original `t` is rethrown.
- **CANCEL-01:** architecture-contracts vr-20261027-035030-0ef71304 FAILED, because the WorkerCheckpointBlockedException catch pushed the generic `catch (e: Exception)` out of the guard's 200-character sibling window. Fixed with an explicit `if (e is CancellationException) throw e`.
- **Batch results before these fixes:**
  - PASS: targeted vr-20261027-034123-51a46419, cancellation vr-20261027-034712-d4ac2abe, domain-a-m vr-20261027-034728-041ab688, runtime-ui vr-20261027-034859-314e207e, static-guards vr-20261027-035118-b91ed1e1.
  - app-check was held and is re-queued after the fixes.
- **Notes A–E:** recorded as accepted residuals:
  - (A) release is not a compare-and-delete, which is safe under unique periodic work;
  - (B) undecodable records fail closed forever, so a future format change needs a version prefix;
  - (C) clock regression, which matches recurring behaviour;
  - (D) a date edit re-reminds once, by design (item 44);
  - (E) there is no notify-before-mark ordering assertion.
- **Review iterations:** 2 of 2 used. The two conditions were mechanical and are closed. Acceptance depends on the re-run batch below.
- S7b ACCEPTED (2026-10-27).
  - Final-tree runs, all PASS:
    - targeted vr-20261027-042110-e4478439
    - cancellation vr-20261027-042644-ccf6a973
    - architecture-contracts vr-20261027-042700-f2eaa60b (the CANCEL-01 fix)
    - runtime-ui vr-20261027-042746-760ebddb
    - app-check vr-20261027-042917-9c2f0bc1, which also proves the Hilt graph compiles
  - Earlier on the iteration-1 production tree (worker and manager unchanged since, except the iteration-2 fixes covered above): domain-a-m vr-20261027-034728-041ab688 and static-guards vr-20261027-035118-b91ed1e1.
  - Gates:
    - privacy-security-guardian: ACCEPT-WITH-CONDITIONS (C1 worker tests, C2 redaction).
    - reviewer-strict: iteration 1 REJECT, then fixed under owner-approvals 44; iteration 2 ACCEPT-WITH-CONDITIONS (findings 1–2 fixed).
  - Follow-ups for S10a:
    - the forInstallment notification-id sub-range;
    - cancel the posted reminder when the entry is paid or the plan is stopped or deleted.
  - Follow-up before release: check whether NotificationFilter self-captures the app's own reminders.
  - Next loan slices: S8b (needs owner approval of the guard-surface strengthening at start), then S10a/b.
- Orchestrator notes on S6c-F.2 (2026-10-27):
  - **Accepted residual:** drop-after-merge on the anomaly merchant path. A linked largest current-month purchase can hide a smaller non-linked spike at the same merchant, and a linked candidate can take a top-5 slot before it is dropped. This is no worse than before S6c-3, when the whole merchant was suppressed as recurring. Pre-filtering the candidates is a possible follow-up.
  - **One added Timber.d:** it logs a count only, matching the existing detector line. Accepted.

## S6c-F.3 — implemented (2026-10-27; owner item 37(b), decision 46(a) R2; uncommitted; nothing compiled, run or validated)

Legal path: recurring rule coordinator stays the only link writer (claimLinkSuppressInWrite); the new object is pure (no I/O, no DAO, no schema, no new query, no new catch; pins stay at 452; no guard/db_ownership/allowlist change).

Source files:
- `N:domain/recurring/RecurringLinkTypeEligibility.kt`: `mayCreateLink(type, isNotMine)` = `!isNotMine && type not in {TRANSFER, DEPOSIT, UNKNOWN}`; `mayKeepLink(type, isNotMine)` = `!isNotMine && type not in {TRANSFER, DEPOSIT}`. The current gate already excluded isNotMine on the reconcile keep path, so the keep form preserves that exactly; the only difference between the two forms is UNKNOWN.
- `M:domain/recurring/OccurrenceConflictResolver.kt`: inline rule replaced by `mayCreateLink` (behaviour unchanged); unused `TransactionType` import removed.
- `M:domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`: `linkExpenseToOccurrence` pre-check uses `mayCreateLink`; the private gate is now `isExpenseEligibleForRecurring(expense, existingLink: Boolean)` (layer 1 via keep or create form, then the unchanged INV-5 installment check); KDoc at the link function (rule 6) and the gate updated; unused `TransactionType` import removed.
- `M:domain/recurring/RecurringInstallmentEligibility.kt`: one KDoc line only (the "Recurring's own layer" sentence, which was at :46 in this tree, not :41).

Call-site classification (gate `isExpenseEligibleForRecurring`):
- `claimLinkSuppressInWrite` (auto-link and manual link, in-transaction): CREATE (`existingLink = false`). A claim writes a new link; an expense already linked to this occurrence is a no-op there, so the form only matters for a first link.
- `linkExpenseToOccurrenceDetailed` failure-reason check: CREATE. It only classifies why a new link was not made, so UNKNOWN reports `Skipped(expense_not_eligible)`.
- `reconcileExpenseLinkAfterUpdate` "linked and still matches": KEEP (`existingLink = true`). This is the R2 branch and the only keep call. `reconcileAllLinkedExpensesAfterBulkUpdate` has no gate call of its own (it loops PAID occurrences and delegates to this reconcile), so the bulk path is covered by the same keep call.
- `reconcileExpenseLinkAfterUpdate` relink after the "no longer matches" unlink: CREATE. The old link was just removed, so a relink is a new link. (A linked UNKNOWN expense that no longer matches by date or merchant is unlinked and not relinked, as for any other type.)
- `reconcileExpenseLinkAfterUpdate` not-linked branch: CREATE.
No ambiguous classification; no PAID to PLANNED change for an UNKNOWN expense that still matches.

Behaviour change: UNKNOWN expenses are no longer auto-linked, manually linked, or reported as `NoMatch` by the coordinator (now `Skipped(expense_not_eligible)` / `SpecificLinkResult.ExpenseNotEligible`), which matches what the generation resolver always did. Existing links on UNKNOWN expenses are kept. Before this slice a parsed UNKNOWN expense could be linked through the coordinator even though the resolver refused it.

Tests (none deleted, no existing expectation changed; grep found no existing test that asserted an UNKNOWN link):
- New `N:test/.../domain/recurring/RecurringLinkTypeEligibilityParityTest.kt` (F-AE-7): both tables over `TransactionType.entries` x isNotMine, keep-superset-of-create (only difference is UNKNOWN), a real `OccurrenceConflictResolver` driven over the table against `mayCreateLink`, and a source scan that neither the resolver nor the coordinator contains an inline `TransactionType.TRANSFER/DEPOSIT/UNKNOWN` and that the coordinator calls both forms.
- `M:RecurringLifecycleCoordinatorInstallmentLinkPolicyTest` (real Room; chosen over the mock-based `RecurringLifecycleCoordinatorTest`, which never exercises the link gate): helpers got optional `type`; added F-AE-6 (auto-link, detailed outcome, manual link and unlinked reconcile refuse UNKNOWN; WITHDRAWAL controls link by auto and manual) and F-AE-8 (a link retyped to UNKNOWN survives reconcile as `UpdatedLinkedSnapshot` and survives the bulk reconcile with `updatedSnapshots = 1`, stays PAID; a retype to TRANSFER still unlinks).
- `M:OccurrenceConflictResolverInstallmentExclusionTest`: helper got optional `type`; added an UNKNOWN row (PLANNED) with a WITHDRAWAL control (PAID) and an UNKNOWN-beside-PURCHASE case.
- `M:MarkRecurringBillPaidUseCaseTest` (mockk coordinator): the use case always creates a PURCHASE, so an UNKNOWN expense cannot reach it; added the ineligible outcome case (`SpecificLinkResult.ExpenseNotEligible` gives `Failed(LINK_REJECTED)`, rule not advanced). The real UNKNOWN refusal is the Room test above.

Risks:
- The unlink-on-TRANSFER control runs the unlink without a planned row; it relies on the unlink being null-safe for a missing planned row (the existing Unlinked test seeds one). If it fails, seed a planned row as that test does.
- `Pipeline4LifecycleGolden` / `RecurringPaymentMatchE2E` were not run. A grep found no `TransactionType.UNKNOWN` in them, nor in the RecurringHorizonAndLinkR21, RecurringRuleLifecycleCoordinator, RecurringGenerationInstallmentExclusion or RecurringPlannedActualNoDoubleCount tests (the only UNKNOWN uses in recurring-named tests are RecurringExpenseEngineTest and RecurringIncomeTrackerTest, which do not link). A parsed-UNKNOWN pipeline fixture could still flip them; if so, change the expectation with a `// owner item 37(b)` comment (never delete).
- Anything that created an UNKNOWN to PAID link through the coordinator before this slice now stays PLANNED until the user classifies the expense.

Targeted filter: `*RecurringLinkTypeEligibility*,*RecurringLifecycleCoordinator*,*OccurrenceConflictResolver*,*RecurringInstallmentEligibility*,*MarkRecurringBillPaid*,*RecurringPaymentMatchE2E*,*RecurringHorizonAndLinkR21*,*Pipeline4LifecycleGolden*,*RecurringGenerationInstallmentExclusion*,*RecurringExpenseEngine*,*RecurringRuleLifecycleCoordinator*,*RecurringPlannedActualNoDoubleCountGolden*,*NotificationProcessingPipeline*`.

## S6c-F.1 — implemented

Owner item 36(a), approved guard strengthening. Test-only; the single file is `N:test/.../architecture/InstallmentPlanArchitectureGuardTest.kt`. No config/guards, allowlist or baseline file touched. Not run (the validation-runner owns execution).

What changed:
- `PLAN_ALLOWED_TLC_OPS = {createExpenseDbOnlyV2, setSpendingExclusionDbOnly}` in the companion.
- New matcher `planTlcCalls(rel, raw, allowed = PLAN_ALLOWED_TLC_OPS)`, rule id `PLAN_TLC_OP_NOT_ALLOWED`. It runs only on files under `PLAN_CODE_PREFIXES`, on sanitized code (comments and string bodies ignored).
- Receivers: anything declared `name: (pkg.)TransactionLifecycleCoordinator` (nullable too), the canonical names `transactionLifecycleCoordinator` and `tlc`, and local aliases `val|var x = <receiver>` followed to a fixed point (alias chains).
- Flagged when the member is not in the allow-list: `r.m`, `r?.m`, `r!!.m`, a line break before the dot, `this.r.m`, scope functions (`.let/.run/.apply/.also` are members outside the list; `with(r)` is flagged outright), `r::m` and `TransactionLifecycleCoordinator::m`. Any member access is judged, not only `m(`, so a property or bare reference cannot dodge the list.
- Tests: live scan (zero violations expected; the only TLC user is the coordinator, three calls at ~:219, ~:1623, ~:1665, verified by grep); planted self-check (flagged at five plan paths incl. a sibling path and the ExpenseScheduleLinkPolicy file; clean: allowed ops via canonical/typed/alias/safe-call/line-break forms, comments, strings, import, unrelated receivers, non-plan paths incl. TransactionsViewModel; widened-allow-list control); non-vacuity (set pinned to exactly two ops, each matches `DbOnly(V\d+)?$`, is declared `fun <op>(` in TLC_PATH, and is called in COORDINATOR_PATH); live visibility (with an empty allow-list the coordinator yields at least 3 hits, so receiver tracking is not blind).

Scope and limits (documented in the matcher KDoc):
- A receiver passed through a generic or function-typed parameter, a lambda parameter (`x.let { t -> t.op() }` is caught only because `let` itself is flagged), a `Lazy`/`Provider` wrapper, destructuring, or a differently named untyped property is not tracked.
- A non-TLC local that happens to be named `tlc` is treated as a TLC (conservative).
- Only plan code is scanned; "only the coordinator holds a TLC" stays deferred. A third TLC op needs owner approval (the allow-list is pinned in the non-vacuity test).

Targeted filter: `*InstallmentPlanArchitectureGuard*`.
- S6c-F batch 1 (2026-10-27): targeted vr-20261027-054719-dae1e5fc FAILED on one test, the F.1 planted self-check case "alias of a typed receiver".
  - Cause: a matcher bug. The alias regex's optional type part `(?::[^=\n]*)?` could span a ctor `val coord: TransactionLifecycleCoordinator) { … val c =`, so it swallowed the real alias declaration (findAll is non-overlapping).
  - Fix (test-only, STRICTER): the type part stops at `(){},;`.
  - db_access vr-20261027-060142-2d6d25d4 PASS (pins at 452).
  - The rest of the batch was held and is re-queued.
- S6c-F full batch on the final production tree, all PASS:
  - targeted vr-20261027-060514-65d41ca5
  - cancellation vr-20261027-060817-15e60c80
  - architecture-contracts vr-20261027-060833-70b92c2a
  - domain-a-m vr-20261027-060919-1f9fedbb
  - domain-n-z vr-20261027-061036-e0147bb0
  - runtime-ui vr-20261027-061222-d3d8b072
  - data vr-20261027-061353-b1355f09
  - static-guards vr-20261027-061757-d06d20e5
  - app-check vr-20261027-063400-335f3d7f
  - db_access vr-20261027-060142-2d6d25d4 (pins at 452)
- architecture-guardian: PASS / ACCEPT-WITH-CONDITIONS. Applied, docs and comments only:
  - finding 1: the F.1 matcher's Limits KDoc now lists parentheses/cast receivers, parenthesized aliases, import aliases and string-template calls;
  - finding 4: an INV-29 amendment for the F.2 consumers, and the ENGINE map :24 row.
- **Finding 3 (accepted outcome-code change):** for a legacy UNKNOWN expense already linked to the same occurrence, a retry of the specific link now returns EXPENSE_NOT_ELIGIBLE instead of ALREADY_LINKED_TO_THIS (the detailed link gives `expense_not_eligible` instead of `expense_already_linked`). Nothing is written and PAID is kept, so R2 holds. TRANSFER and DEPOSIT already behaved this way. This corrects the handoff line "the form only matters for a first link".
- **Finding 5:** the separate raw-merchant logging ticket must also cover the throwable (SQL message and stack trace) in the pipeline's `Timber.w(e, …)`.
- S6c-F ACCEPTED (2026-10-27).
  - Final gate: reviewer-strict ACCEPT-WITH-CONDITIONS, docs only.
    - F1 corrects the masking-residual rationale above. Pre-filtering the anomaly MERCHANT path's current-month candidates would leave the baselines alone, because that path's baseline is history before the current month; only the statistical path shares its input with its baseline. The residual stays accepted, and the pre-filter is a possible follow-up.
    - F2 widens the raw-merchant logging ticket to NotificationProcessingPipeline :1772 and :1854 as well as :1798 and :1807. Those lines log the raw merchant and the exception object, and the new installment-reader failure can reach :1854. The exposure is pre-existing, not new.
    - F3 (optional, follow-up): a detector test for a non-cancellation reader failure; rename `autoLinkAndManualLinkStillLinkAWithdrawalExpense` to `autoLinkStillLinksAWithdrawalExpense`.
  - Behaviour change (approved 37(b)): new UNKNOWN-type expenses are no longer auto-linked or manually linked to recurring bills. Existing links are kept (46(a)).
  - Committed.
  - Next loan work:
    - S8b (owner approval of the guard-surface strengthening);
    - S10a/S10b UI (es/el strings; soft gate D-13 on the Release merge).

## S10a-1 — implemented (2026-10-27; uncommitted; nothing compiled, run or validated)

Scope: navigation, hidden entry, deep-link tokens, overview screen. Detail and history are S10a-2.

### Files
- NEW `ui/navigation/InstallmentsFeature.kt`: `const val ENABLED = false` (the compile-time gate), `FEATURE_ID`, deep-link host constants, `isInstallmentDestination`, `shouldRedirectHome`.
- `ui/navigation/NavigationDestination.kt`: `Installments`, `InstallmentPlanDetail(planId)`, `InstallmentHistory`. Not added to `featureDestinations` (the menu entry comes from FeatureConfig only).
- `ui/navigation/NavigationController.kt`: `toSaveToken` (exhaustive `when`, so required) and `destinationFromSaveToken`: tokens `installments`, `installment_plan?id=<n>`, `installment_history`. Restore is NOT flag-gated (a pure mapping; `FeatureConfigNavigationContractTest` requires every `allFeatures` token to restore). Rendering is gated instead.
- `ui/navigation/FeatureConfig.kt`: item `installments` (title `installments_title`, desc `feature_installments_desc`, `Icons.Rounded.Payments`) in `allFeatures`; `availableFeatures(...)` gets a defaulted `installmentsEnabled = InstallmentsFeature.ENABLED` and withholds it while false. The HomeScreen caller is unchanged.
- `ui/navigation/DeepLinkParser.kt`: `parseDeepLink(uri, installmentsEnabled = ENABLED)`: `installments` and `installment_plan?id=` return RequireConfirmation (plan names and amounts, like `review` and `activity?expenseId=`), and Reject while the flag is false or the id is missing, non-numeric or <= 0.
- `ui/MainActivity.kt`: `handleIntent` has the two hosts, both flag-gated, landing on Home when disabled or malformed. The render branch renders `InstallmentOverviewScreen` only when the flag is true; otherwise `navigateHome()`.
- NEW `ui/screens/installments/overview/InstallmentOverviewViewModel.kt` (state models, pure `InstallmentOverviewMapper`, Hilt VM) and `InstallmentOverviewScreen.kt`.
- Strings: `values/strings.xml`, `values-es`, `values-el` (both locales exist, with a MissingTranslation lint baseline, so es and el are added now to avoid new lint findings).
- Tests: NEW `ui/screens/installments/overview/InstallmentOverviewViewModelTest.kt` (18 tests); `ui/navigation/NavigationRouteContractTest.kt` minimal edit (completeness checks require `Installments`, `InstallmentHistory` in the object list and `InstallmentPlanDetail` rows in the parameterised list).

### Navigation decisions
- Allow-list for deep links: the only config-level allow-list is the AndroidManifest intent-filter hosts (home, dashboard, activity, review, plan, add, analytics, map). No guard, baseline or config file lists hosts. I did NOT add the two hosts to the manifest: the manifest cannot be gated by a Kotlin constant, and adding them would widen the exported surface of a dormant feature. Without a manifest host the links cannot arrive from outside [CORRECTED 2026-10-27: wrong — MainActivity is exported, explicit intents reach handleIntent; the code gate is the boundary, see "S10a-1 — privacy spot check"], and the parser and `handleIntent` are ready. The enablement slice (S13) must add `<data android:scheme="expensetracker" android:host="installments" />` and `...host="installment_plan"` together with flipping the flag, and S8's reminder tap must use the `installment_plan?id=` URI. No guard or allowlist change was needed.
- Detail and history: the destinations exist and have tokens, but they are NOT rendered; the render branch redirects Home (`navigateHome()`), so nothing is a dead placeholder screen and no production caller navigates to them. S10a-2 replaces that branch with the real screens (and wires the History action and card taps).
- `handleIntent` does not use `parseDeepLink` (pre-existing: it has its own switch and no confirmation prompt, PRV-16 TODO). I kept that structure and gated the new hosts the same way the parser does.
- Overview "Next due" uses the read model's per-plan `nextDue` and excludes OVERDUE ones (a plan whose earliest scheduled entry is overdue contributes no next-due candidate; its later upcoming entry is not visible without the detail read). The read model has no per-entry attention rows, so S10b adds Record/Link/Skip rows; here "Needs your attention" shows only the per-currency awaiting-confirmation lines (AE-10b).

### Behaviour
- AE-5: one line per currency present in `remainingScheduledByCurrency` (EUR before USD), with plan count; no combined total; none for an absent currency.
- AE-10b: per-currency "N past payments awaiting confirmation: amount (not counted)" lines from `awaitingConfirmationByCurrency`; a plan card shows "Awaiting confirmation · not counted yet" only for CONFIRM-mode plans with a DUE or OVERDUE next entry.
- AE-18/INV-17: remaining scheduled payments in the plan currency (minor units / 100, EUR and USD only); paid-so-far unavailable is the word "Unavailable", never zero; a real zero stays a money value. Unreadable plans are a count notice, and an overview with only unreadable plans is Data, not Empty.
- Progress "n of N paid": n = paid + paid-before-tracking, N = n + scheduled + skipped. (My choice; confirm at review.)
- Empty has two variants (no plans ever / only finished plans) chosen from `observeHistory().size`; a history failure falls back to the first variant and never blocks the overview.
- INV-31: failures map to `LOAD_FAILED` / `REFRESH_FAILED` and fixed string resources; the log carries only the code. `CancellationException` is rethrown in both `catch` operators. No DAO access; the VM depends only on `InstallmentPlanReadRepository`.
- Not in this sub-slice: History action, Refresh action (the observe flows re-emit on change), FAB "Add plan", plan-detail navigation, Record/Link/Skip actions, Possible-duplicate and deferred-candidate attention rows.

### es/el strings: all DRAFT, owner sign-off required
Keys (all new, English in `values`): `installments_title`, `feature_installments_desc`, `installments_back`, `installments_remaining_label`, `installments_remaining_note`, `installments_plan_count` (plural), `installments_next_due`, `installments_next_line`, `installments_needs_attention`, `installments_awaiting_header` (plural), `installments_active_plans`, `installments_status_active`, `installments_mode_auto_label`, `installments_mode_confirm_label`, `installments_progress`, `installments_awaiting_plan`, `installments_ends`, `installments_payments_left` (plural), `installments_paid_so_far`, `installments_unavailable`, `installments_due_today`, `installments_overdue_on`, `installments_empty_title`, `installments_empty_body`, `installments_empty_finished_title`, `installments_empty_finished_body`, `installments_error_load`, `installments_error_refresh`, `installments_unreadable_plans` (plural).
- Both `values-es` and `values-el` carry a DRAFT comment. Items for native review: es "Cuotas y préstamos", "Pagos programados restantes", "Pendiente de confirmar · aún no contado"; el "Δόσεις και δάνεια", "Υπολειπόμενες προγραμματισμένες πληρωμές" (ux-flows already flags it), "Αναμένει επιβεβαίωση · δεν προσμετράται ακόμα". Plurals use one/other only (the existing el plural style).

### Risks
- Nothing compiled. Check at first compile: `Icons.Rounded.Payments` via the `rounded.*` import in FeatureConfig; `UiText.from(Int)` overload; `InlineErrorBanner`/`ErrorState`/`EmptyState` from `ui.components.common`; mockk of the final `InstallmentHistoryItem` in the VM test.
- `if (InstallmentsFeature.ENABLED)` is a constant condition (compiler warning only; no warnings-as-errors found in app/build.gradle.kts).
- es plural `many` category: lint may suggest it for es (MissingQuantity is a warning); el matches existing usage.
- Money display uses `MoneyDisplayUi.from(Double, code)` over exact minor units / 100 (EUR and USD are 2-digit; the read model refuses other scales).
- DeepLinkParserTest (existing, Uri-based) is not extended: test-author should add cases (flag false rejects; enabled via the new parameter returns RequireConfirmation; bad id rejects), plus a FeatureConfig test that `installments` is absent from `availableFeatures` by default and present with `installmentsEnabled = true`, and an `InstallmentsFeature.shouldRedirectHome` test.

### Validation to request (serial, via validation-runner)
1. `targeted-unit-test -Filter "*Installment*ViewModel*"` plus `*NavigationRouteContractTest*`, `*FeatureConfigNavigationContractTest*`, `*DeepLinkParserTest*`.
2. `unit-test-shard -ShardName runtime-ui`.
3. Registered guard `ui_dao` (the overview imports no DAO or entity).
4. `lint` if a runner profile exists (new es/el strings, plurals).

### S10a-1 — navigation tests added (test-author; not compiled or run)
- `DeepLinkParserTest` (extended; Robolectric, Uri-based): `installments links are rejected while the flag is false`; `installments links are rejected by default while the compile-time gate is false`; `installments overview link requires confirmation when enabled`; `installment plan link with a positive id requires confirmation when enabled`; `installment plan link with a missing or malformed id is rejected even when enabled` (missing, empty, abc, 1.5, 0, -7, wrong key, overflow); `installments flag does not change unrelated hosts`.
- NEW `InstallmentsFeatureGateTest` (plain JVM): `installments is absent from availableFeatures by default while the gate is false`; `... absent ... when explicitly disabled`; `installments is present in availableFeatures when enabled`; `installments stays in allFeatures regardless of the flag`; `enabling installments does not add or drop any other feature`; `shouldRedirectHome is true for every installment destination when disabled`; `... is false for every installment destination when enabled`; `... is false for non-installment destinations in both flag states`; `shouldRedirectHome defaults to the compile-time gate`; `isInstallmentDestination recognises exactly the three installment destinations`.
- Canary tests assert `InstallmentsFeature.ENABLED == false`; S13 must update them when it flips the flag.
- Filter: `*DeepLinkParserTest*` and `*InstallmentsFeatureGateTest*` (add to the S10a-1 targeted run, together with `*NavigationRouteContractTest*`, `*FeatureConfigNavigationContractTest*`, `*Installment*ViewModel*`).

### S10a-1 — privacy spot check (ACCEPT-WITH-CONDITIONS) and validation (2026-10-27)
- **C2 correction:** the earlier claim "Without a manifest host the links cannot arrive from outside" is WRONG. MainActivity is `exported="true"` and explicit intents ignore intent-filter hosts, so `expensetracker://installment_plan?id=N` already reaches `handleIntent` today. Dormancy holds because of the CODE gate (`InstallmentsFeature.ENABLED = false` → Home), not because of the manifest. Owner-approvals 47(b) stands; only its reason is corrected.
- **C1, HARD REQUIREMENT for S13 (or any slice that flips InstallmentsFeature.ENABLED):** `handleIntent` must route the `installments` and `installment_plan` hosts through `parseDeepLink` and show a confirmation on RequireConfirmation. Today it navigates straight to InstallmentPlanDetail (existing PRV-16 gap). Alternatively, reminder taps use an explicit internal PendingIntent extra, and only external links go through confirmation.
- **Recommended for S10a-2, which edits MainActivity and NavigationController anyway:**
  - gate token restore on InstallmentsFeature.ENABLED in ProvideNavigationController;
  - add `.takeIf { it > 0L }` on the restored plan id (NavigationController :219);
  - make the render branch call `shouldRedirectHome(destination)` instead of an inline duplicate (finding 4).
- **INFO:**
  - toLongOrNull accepts a leading '+' and non-ASCII digits. The id is still positive, so this is harmless; an ASCII-only `^[0-9]{1,18}$` pre-check is optional.
  - the `parseDeepLink` reason text is English and never shown.
- **Validation, all PASS:**
  - targeted vr-20261027-081209-50b6228b
  - ui_dao vr-20261027-081958-5ab296ce
  - runtime-ui vr-20261027-082014-2d8ba014
  - architecture-contracts vr-20261027-082145-4c44bea4
  - static-guards vr-20261027-082233-4ef08131
  - lint vr-20261027-083837-6f10ebf5

### S10a-1 — review fixes (2026-10-27; uncommitted; nothing compiled, run or validated)
1. Dead helpers: `MainActivity` render branches now call `InstallmentsFeature.shouldRedirectHome(currentDestination)` for `Installments`, `InstallmentPlanDetail` and `InstallmentHistory` (the tested helper is the real gate). Redirect behaviour is identical: disabled redirects Home for all three; detail and history still redirect Home when enabled (no screen until S10a-2, which fills the not-redirect branch).
2. Token restore (privacy finding 3): `ProvideNavigationController` drops an installment destination (current and back stack) while the gate is false (`shouldRedirectHome`), falling back to `initialDestination`; the token layer stays ungated so `FeatureConfigNavigationContractTest` and the round-trip tests are unaffected. `installment_plan` id now needs `> 0` (`takeIf { it > 0L }`). Test: `NavigationRouteContractTest` gains `installment_plan token with a non-positive or malformed id does not restore`. The Compose-level restore gate has no unit test (needs a composition); it is covered by the same helper `InstallmentsFeatureGateTest` tests.
3. First paint: `finishedPlanCount()` has `.onStart { emit(0) }`, so the overview no longer waits on `observeHistory()`. Side effect: for an overview with no active plans and finished plans, the first paint can show the NO_PLANS variant, then switch to NO_ACTIVE_PLANS when the history count arrives (brief flicker). S10a-2 follow-up: `observeHistory()` loads full rows only to take `size`, and the history screen loads them again (double load); add a count-only read (for example `observeFinishedPlanCount()`), which also removes the flicker.
4. Vacuous test: the mapper filters `remainingLines` to currencies that have a plan (`remaining` keys intersect plan currencies). Test `AE-5 a currency with no plan has no line` now passes `remaining = {EUR, USD}` with only an EUR plan and asserts there is no USD line (expectation intentionally tightened; no test deleted).
5. RTL-safe text: the screen no longer concatenates strings. New format strings `installments_label_value` (`%1$s %2$s`) and `installments_two_parts` (`%1$s · %2$s`) in values, values-es, values-el (a single space: aapt collapses unquoted double spaces). `CurrencyFormatter.formatMoney` uses `NumberFormat.getCurrencyInstance`, so it already includes the currency symbol; the code prefix (`EUR  €524.00`) was dropped in the Remaining and Awaiting lines.
6. Wording: `installments_awaiting_header` now reads "N payment(s) due, awaiting confirmation: X (not counted)" since it counts DUE today as well as OVERDUE. es and el DRAFTS updated.
7. `LocalContext.current.resources.getQuantityString` replaced by `pluralStringResource` (no `LocalContext` import left).
8. Comment added in `InstallmentOverviewMapper.nextDueCard` that a plan whose earliest scheduled entry is OVERDUE contributes no Next-due candidate (limitation of the read model; S10a-2/S10b). No test added.

Known, not fixed:
- INFO 8: a screen left open past midnight shows stale DUE/OVERDUE labels until the next DB change (note for S13: add a day-change tick or recompute on resume).
- INFO 9: the cheap-review INFO 9 text was not included in the fix request; recorded here as acknowledged and not fixed. Orchestrator: add the original wording if it matters.

es/el DRAFT list for owner sign-off (changed or added in this fix, in addition to the earlier list):
- es: `installments_awaiting_header` "pago(s) con vencimiento, pendiente(s) de confirmar ... (no contado/s)"; `installments_label_value`, `installments_two_parts` (language-neutral).
- el: `installments_awaiting_header` "πληρωμή(ές) προς εξόφληση αναμένει/αναμένουν επιβεβαίωση ... (δεν προσμετράται/ρώνται)" (wording of "προς εξόφληση" for "due" needs a native check); `installments_label_value`, `installments_two_parts`.

Targeted filter for the re-run: `*Installment*ViewModel*` (InstallmentOverviewViewModelTest) plus `*NavigationRouteContractTest*`, `*FeatureConfigNavigationContractTest*`, `*InstallmentsFeatureGateTest*`, `*DeepLinkParserTest*`; then lint (strings, plurals) and `ui_dao`.
- INFO 9 (cheap review), wording: a refresh failure while the overview is Empty becomes the full Error state, and the Empty state is lost. This is acceptable; `emptyKind` is reset correctly in `onReadFailed`.
- S10a-1 ACCEPTED (2026-10-27).
  - Final-tree runs, all PASS: targeted vr-20261027-085403-01295661, ui_dao vr-20261027-090322-e5c12ac5, runtime-ui vr-20261027-090338-c596afc3, lint vr-20261027-090509-d7810528.
  - Earlier on the pre-fix tree: architecture-contracts vr-20261027-082145-4c44bea4, static-guards vr-20261027-082233-4ef08131.
  - Gates: privacy spot check ACCEPT-WITH-CONDITIONS (C1 = an S13 hard requirement; C2 = a handoff correction); cheap review ACCEPT-WITH-CONDITIONS (items 1–8 fixed).
  - The es/el strings are DRAFTS pending the owner's native sign-off.
  - Committed. Next: S10a-2 (plan detail and history screens).

## S10a-2 — implemented (2026-10-27; uncommitted; nothing compiled, run or validated)

Scope: plan detail screen, history screen, MainActivity render branches, overview entry points (plan card tap, History action). All dormant behind `InstallmentsFeature.ENABLED = false`. Read-only: no writes, no DAO in UI, no schema, guard, allowlist or manifest change.

### Files
- NEW `ui/screens/installments/InstallmentUiSupport.kt`: internal pure helpers `installmentMoney` (minor / 100 with explicit currency), `installmentAmountUi` (Available -> Money, Unavailable -> Unavailable), `formatInstallmentDate`. Shared by the two new mappers; the S10a-1 overview keeps its own private copies (no refactor).
- NEW `ui/screens/installments/detail/InstallmentPlanDetailViewModel.kt` (state models, pure `InstallmentPlanDetailMapper`, Hilt VM) and `InstallmentPlanDetailScreen.kt`.
- NEW `ui/screens/installments/history/InstallmentHistoryViewModel.kt` (state models, pure `InstallmentHistoryMapper`, Hilt VM) and `InstallmentHistoryScreen.kt`.
- `ui/screens/installments/overview/InstallmentOverviewScreen.kt`: new params `onOpenPlan: (Long) -> Unit`, `onOpenHistory: () -> Unit`; plan card is clickable; top-bar `TextButton` "History" (also shown in the empty variants).
- `ui/MainActivity.kt`: the S10a-1 combined detail/history redirect branch is split. Both branches still call `InstallmentsFeature.shouldRedirectHome(currentDestination)` (redirect Home while disabled) and otherwise render `InstallmentPlanDetailScreen(planId)` / `InstallmentHistoryScreen`. Overview passes the two navigation callbacks; history cards open the detail.
- Strings: `values`, `values-es`, `values-el` (about 50 new keys, list below).
- Tests (new): `ui/screens/installments/detail/InstallmentPlanDetailViewModelTest.kt` (25 tests), `ui/screens/installments/history/InstallmentHistoryViewModelTest.kt` (13 tests).

### Behaviour and decisions
- Detail VM takes the plan id through `start(planId)` (called from the screen in `LaunchedEffect(planId)`; the screen uses `hiltViewModel(key = "installment_plan_$planId")` so each plan has its own VM). Same-id `start` is a no-op; `retry()` restarts. No SavedStateHandle/assisted factory: the repo has no precedent for them in screens, and `start` keeps the VM testable.
- Not found (bounded code `NOT_FOUND`, fixed string `installments_error_not_found`, no Retry button): id <= 0 (no repository call), `observeDetail` emitting null (missing plan or unreadable stored codes, per the repository contract), or a plan deleted while open. Read failure: `LOAD_FAILED` (no content) or `REFRESH_FAILED` (content kept, inline banner). Log carries only the code. `CancellationException` is rethrown in the `catch` operators.
- Progress "n of N paid" per owner-approvals 47(a): n = paid + paidBeforeTracking, N = n + scheduled + skipped (cancelled and reversed are not part of N). Remaining scheduled payments = count of scheduled entries ("N payments left") plus the remaining amount in the plan currency.
- Row labels (AE-18, ux-flows 3.5): Upcoming, Due today, Overdue · not counted yet, Paid, Payoff (PAYOFF kind that is PAID), Skipped, Cancelled, Refunded · not counted (REVERSED), Paid before tracking. Sub-reasons are bounded to the entry status: skip reasons for SKIPPED, stop/refund/payoff/shortened/expense-removed for CANCELLED, expense-removed for SCHEDULED; any stored code outside that is dropped (never shown as text).
- Awaiting confirmation (AE-10b): per entry and as a card ("N payments due, awaiting confirmation: X (not counted)", reusing the S10a-1 plural) only for ACTIVE CONFIRM-mode plans, SCHEDULED entries that are DUE or OVERDUE.
- Unavailable never zero (INV-17): paid-so-far, financing cost, per-entry variance (shown as "Paid amount: Unavailable"), and null start/end dates are shown as the word "Unavailable". Financing cost keeps its sign when negative. A real zero stays a money value. Financing cost reason line follows ux-flows 3.5 (needs same-currency purchase / not available for cancelled or refunded / payment amount cannot be read).
- Entries "Was X" line only when `originalScheduledMinor != scheduledMinor` (the read model carries no original date, so a date-only adjustment is not flagged). "Differs from plan: X" only when a PAID variance is non-zero.
- Plan banners: COMPLETED "All payments are accounted for."; CANCELLED/REFUNDED/PAID_OFF "This plan is finished and can't be changed." (ux-flows 3.5 dated banners need the terminal date, which the detail read model does not expose; not added).
- History: cards from `observeHistory()` in the read model's order (newest ended first). Text per AE-7 / AE-11: status chip, "start – end · currency", "N paid · N skipped · N cancelled · N refunded", optional "N payments paid before tracking", "Total counted: X", "Financing cost: X"; REFUNDED shows "Refunded payments are not counted." so its zero total is explained. Filter chips All/Completed/Cancelled/Refunded/Paid off (ux-flows 3.8); a filter with no match is Data with zero cards and a per-filter message; no finished plans at all is Empty. Cards open the read-only detail. No delete action (S10b).
- No money is concatenated: all composed text uses format strings (`installments_two_parts`, `installments_label_value`, `installments_history_range`, `installments_history_counts`, ...) and `pluralStringResource`.

### S10a-1 follow-up: count-only finished-plan read — NOT done (note)
`InstallmentPlanReadRepository` has no count-only history read, and `InstallmentPlanReadRepositoryImpl` computes history by `loadAll()` (full plan and entry load). A count-only method added to the interface would still do the same full load unless a new DAO count query were added, which the brief forbids. The overview's `finishedPlanCount()` (`observeHistory().size` with `onStart { emit(0) }`) is therefore unchanged, including its first-paint flicker. Recommended follow-up (needs owner/scout approval of a DAO query): `InstallmentPlanDao` count of plans with status <> 'ACTIVE' plus `observeFinishedPlanCount()` on the read repository.

### es/el strings: all DRAFT, owner sign-off required
New keys (English in `values`; es and el carry a DRAFT comment): `installments_history_action`, `installments_detail_title`, `installments_error_not_found`, `installments_error_load_detail`, `installments_error_refresh_detail`, `installments_status_completed`, `_cancelled`, `_refunded`, `_paid_off`, `installments_banner_completed`, `installments_banner_ended`, `installments_kind_purchase`, `installments_kind_standalone`, `installments_lender`, `installments_end_date_unavailable`, `installments_financing_cost`, `installments_financing_needs_purchase`, `installments_financing_not_for_status`, `installments_financing_payment_unreadable`, `installments_schedule`, `installments_entry_number`, `installments_entry_upcoming`, `_overdue`, `_paid`, `_skipped`, `_cancelled`, `_refunded`, `_paid_before_tracking`, `_payoff`, `_was`, `_paid_unavailable`, `_differs`, `installments_reason_skip_lender_holiday`, `_skip_waived`, `_skip_other`, `_stopped`, `_refunded`, `_paid_off_early`, `_shortened`, `_expense_removed`, `installments_history_title`, `installments_history_empty_title`, `_empty_body`, `_empty_completed`, `_empty_cancelled`, `_empty_refunded`, `_empty_paid_off`, `installments_filter_all`, `installments_history_range` (language-neutral), `installments_history_counts`, `installments_history_before_tracking` (plural), `installments_history_total`, `installments_history_refunded_note`, `installments_error_load_history`, `installments_error_refresh_history`.
Items needing a native check:
- es: "Liquidado" (paid off), "Liquidación anticipada" (payoff entry), "Condonado" (waived), "Pausa del prestamista" (lender break), "Coste de financiación", "Compra a plazos", "Pagado antes del seguimiento".
- el: "Εξοφλήθηκε" (paid off), "Πρόωρη εξόφληση" (payoff entry), "Διαγράφηκε" (waived; may read as "deleted"), "Παύση από τον δανειστή" (lender break), "Κόστος χρηματοδότησης", "Το έξοδό της αφαιρέθηκε" (grammatical gender of the installment), "Πληρώθηκε πριν την παρακολούθηση".
- Plurals use one/other only (the existing el style); es may get a lint `many` suggestion.

### Risks (nothing compiled)
- Check at first compile: `hiltViewModel(key = ...)` overload for the default parameter; `FilterChip` (Material3 version, `ExperimentalMaterial3Api` opt-in is present on the screen); `InstallmentHistoryFilter.entries`; `Card(...).clickable` and `TextButton` imports in the overview; `ErrorState(onRetry = null)` hides Retry (assumed from the nullable parameter; if it renders a button regardless, NOT_FOUND shows a retry that re-reads and stays not-found, harmless).
- `installments_end_date_unavailable` and `installments_entry_paid_unavailable` embed the translated word "Unavailable" in one string each (so each locale can inflect it).
- Detail shows no purchase tile, activity section or "Each" tile (spec-limited or S10b); the overdue sub-reasons other than "Its expense was removed" need data the read model does not carry.
- A screen left open past midnight shows stale DUE/OVERDUE labels until the next DB change (same as S10a-1 INFO 8, S13 note).
- Settled-up plans that are COMPLETED but reopen: not handled here (a reopen is a write, S10b).
- The detail tests use mockk on the read repository interface only (no final classes mocked).

### Validation to request (serial, via validation-runner)
1. `targeted-unit-test -Filter "*Installment*ViewModel*"` plus `*NavigationRouteContractTest*`, `*FeatureConfigNavigationContractTest*`, `*InstallmentsFeatureGateTest*`, `*DeepLinkParserTest*`.
2. `unit-test-shard -ShardName runtime-ui`.
3. Registered guard `ui_dao` (the new screens import no DAO or entity).
4. `lint` (new es/el strings and plurals; MissingTranslation baseline).
Gates: cheap review; privacy spot check optional (no new data surface beyond display names and amounts on a gated screen; INV-31 mapping unchanged).

### S10a-2 — privacy spot check (ACCEPT-WITH-CONDITIONS) and fixes
Uncommitted; nothing compiled, run or validated after these fixes. Dormant, read-only, no schema, guard, allowlist or manifest change; no test deleted.
1. MEDIUM, detail and history collected forever (no NavHost, so `hiltViewModel(key = "installment_plan_$planId")` and the history ViewModel live at activity level). Fixed: `InstallmentPlanDetailViewModel.stop()` cancels `collectJob`, nulls it and clears `planId` (so `start(sameId)` after `stop()` restarts cleanly); `InstallmentPlanDetailScreen` calls it from `DisposableEffect(planId) { onDispose { viewModel.stop() } }`; the key is unchanged. `InstallmentHistoryViewModel` got public `start()` (no-op while a collection is running; init still starts the first one) and `stop()`; `InstallmentHistoryScreen` calls `start()` and `stop()` from `DisposableEffect(Unit)`. `retry()` still force-restarts.
2. Tests (detail): `start()` with a changed id (7 then 8, two `MutableSharedFlow`s) resets to Loading, ignores a later emission on 7, and shows plan 8's data; `stop()` cancels the collection (a later emission is ignored) and a later `start(7)` restarts (observe called twice). History: `stop()` cancels, `start()` restarts; `start()` while running does not collect twice.
3. Tests, both ViewModels: an upstream `CancellationException` is rethrown (state stays Loading, no error code, no refresh-failed), never turned into LOAD_FAILED.
4. Tests, both ViewModels: a planted `Timber.Tree` sees exactly one message that contains `code=LOAD_FAILED` and not the exception text or the plan name, with no throwable attached.
5. History filter after a failed refresh: the ViewModel caches the last items (`lastItems`); `selectFilter` re-runs `InstallmentHistoryMapper.toState(cached, selected)` when no collection is running and keeps `refreshFailed` and `errorCode`. Test added (REFRESH_FAILED state, CANCELLED then ALL).
6. Blank plan names (INV-34 redacted restore): the fallback is in the screens, not the mapper. Detail top bar uses `installments_detail_title`; the detail header card and history card use the new `installments_plan_unnamed` ("Installment plan"). No VM test (display-only).
7. `installments_history_counts` is removed and replaced by four plurals: `installments_history_count_paid`, `_skipped`, `_cancelled`, `_refunded`. Zero counts are hidden, so AE-7 reads "3 paid · 9 cancelled"; parts are joined with the existing `installments_two_parts` format string (no concatenation, RTL-safe); the line is omitted when every count is zero. The paid-before-tracking line is unchanged.
8. A zero variance is mapped to null in `InstallmentPlanDetailMapper` (Available with `minor == 0`); the screen's Double compare is removed; Unavailable stays visible (INV-17). Test added (0 -> null, -300 kept, Unavailable kept).
9. INFO 9: S13 C1 is still open (unchanged by this fix).
10. INFO 10: the AE-7 end-date derivation belongs to the read model (`InstallmentHistorySummary.endEpochDay`); the UI shows null as "Unavailable" and does not derive it.

es/el DRAFT list for owner sign-off (changed or added in this fix; `installments_history_counts` is gone):
- New plurals (one/other only): `installments_history_count_paid`, `_skipped`, `_cancelled`, `_refunded`. es: "pagado/pagados", "omitido/omitidos", "cancelado/cancelados", "reembolsado/reembolsados". el: "πληρωμένη/πληρωμένες", "παραλειφθείσα/παραλειφθείσες", "ακυρωμένη/ακυρωμένες", "επιστραμμένη/επιστραμμένες" (feminine, agreeing with "δόση/πληρωμή"; check the gender for one-count).
- New string `installments_plan_unnamed`: es "Plan de cuotas", el "Πλάνο δόσεων".
- Validation to request: `targeted-unit-test -Filter "*InstallmentPlanDetailViewModelTest*" "*InstallmentHistoryViewModelTest*"` (plus the S10a-1 filter above), then `lint` for the new plurals and the removed string.
- S10a-2 ACCEPTED (2026-10-27).
  - Final-tree runs, all PASS: targeted vr-20261027-095844-31694611, ui_dao vr-20261027-100348-df98adbd, runtime-ui vr-20261027-100404-97681285, lint vr-20261027-100535-8d0e6b50.
  - Earlier on the pre-fix tree: architecture-contracts vr-20261027-094008-c12383df.
  - Gate: privacy spot check plus a light correctness read, ACCEPT-WITH-CONDITIONS; all conditions fixed.
  - The es/el strings are DRAFTS pending the owner's native sign-off.
  - Committed.
  - Next loan work:
    - S10b (row actions, create/edit/end flows: writes through the plan coordinator; T3 gates);
    - S8b (needs owner approval);
    - S11 / S12a / S12b / S13.

## S10b-1 — implemented

Status: implemented, NOT validated (nothing was run). Dormant behind `InstallmentsFeature.ENABLED`. No schema, DAO, guard or allowlist change.

Files (all under `app/src/main/java/com/yourname/expensetracker/ui/screens/installments/` unless noted):
- New `actions/InstallmentEntryActionsModel.kt`: action kinds, skip reasons, `InstallmentMutationState` (Idle / InProgress / Success / Refused(code) / Failed(code)), notices, undo types, sheet state with `hasInput`, strict amount parser (at most 2 decimals, positive, never rounded silently), and `InstallmentActionMessages` (total refusal / failure / notice -> fixed string maps).
- New `actions/InstallmentEntryActionsViewModel.kt`: one coordinator op per action, post-commit batch dispatch after the op returns, bounded logging, `CancellationException` rethrown.
- New `actions/InstallmentEntryActionSheets.kt`: row action menu sheet, Record / Link / Skip / Adjust / Unskip sheets, discard dialog, notice snackbar with Undo.
- M `detail/InstallmentPlanDetailViewModel.kt`: row UI gained `scheduledMinor`, `availableActions`, `suggestions` (all defaulted; the detail ViewModel itself is still read-only).
- M `detail/InstallmentPlanDetailScreen.kt`: tappable rows, actions ViewModel (`hiltViewModel(key = "installment_actions_$planId")`), snackbar host.
- M `app/src/main/res/values{,-es,-el}/strings.xml`: `installments_action_*` strings.
- New test `app/src/test/.../ui/screens/installments/actions/InstallmentEntryActionsViewModelTest.kt`.

Coordinator ops used (`InstallmentPlanLifecycleCoordinator`), the only write path:
- Record -> `confirmEntry(entryId, todayEpochDay, zone)`.
- Link -> `linkExpense(entryId, expenseId)`.
- Skip -> `skip(entryId, USER_SKIP_*)`.
- Adjust -> `adjustEntry(entryId, newAmount?, newDueEpochDay?)` (only changed fields are sent).
- Unskip -> `unskip(entryId)`.
- Undo (AE-14): Skip -> `unskip`; Link -> `unlinkExpense`; Adjust -> `adjustEntry` with the previous values. Record has no Undo (ux-flows 3.6).
- Result handling: `Done` / `AlreadyInState` = success; `Refused(code)` -> fixed string per `InstallmentRefusalCode`; `Failed(InstallmentOpFailure)` -> `InstallmentActionFailureCode` (CONFLICT / NOT_FOUND / RECORD_FAILED / UNEXPECTED) -> fixed string; `RateRefused` -> UNEXPECTED. Exception messages are never shown or logged (INV-31); only the operation name and the enum names are logged.

Row action rules (mapper, ux-flows 3.5):
- Only REGULAR entries in an ACTIVE plan get actions.
- SCHEDULED: Record when Due or Overdue, Link when a suggestion exists for the entry, Adjust, Skip.
- SKIPPED: Unskip (ACTIVE or COMPLETED plan).
- The coordinator still decides legality; the UI only hides actions.

Deferred (not in S10b-1):
- Link from a free expense picker: no candidate-read API exists (only `InstallmentPlanDetail.suggestions`, the INV-13 matches). Link lists only those suggestions. A full picker needs a new read; owner or planner item.
- Unlink as a user-facing row action and "Delete expense and reopen" (OQ-21): S10b-2.
- "View expense" snackbar action after Record: needs navigation to the expense; not wired.
- Bulk "Record all past payments": not part of this sub-slice.
- Create, edit, end flows and plan delete: S10b-2.
- `pluralStringResource`: no new count-bearing copy was needed, so no new plurals.
- Category name in the Record description (ux-flows says "in [category]"): the read model has no category name; omitted.

es/el DRAFTS (pending owner native sign-off), all added as `installments_action_*`:
- Menu and titles: es "Registrar pago", "Vincular a un gasto coincidente", "Omitir esta cuota", "Ajustar importe o fecha", "Dejar de omitir". el "Καταγραφή πληρωμής", "Σύνδεση με αντίστοιχο έξοδο", "Παράλειψη αυτής της δόσης", "Προσαρμογή ποσού ή ημερομηνίας", "Αναίρεση παράλειψης".
- Skip reasons: es "Pausa de pagos del prestamista" / "Condonada por el prestamista" / "Otro". el "Παύση πληρωμών από τον δανειστή" / "Διαγραφή από τον δανειστή" / "Άλλο".
- Refusal / failure / notice copy (9 refusal, 4 failure, 9 notice strings) and the discard dialog.
- Check especially: "Condonada" / "Διαγραφή" for "waived", and the el/es "Nothing was changed" phrasing.

Risks and notes for review:
- Zone and today: `ZoneId.systemDefault()` plus `TimeProvider.now()`, the same as the read repository (no injected zone). Tests assert only that the epoch day is within a day.
- `PostCommitActionRunner` is injected into the actions ViewModel to dispatch the batches `confirmEntry` returns; failures are logged by class name and never change the committed result. The unit test covers only the empty-batch case (a non-empty `PostCommitActionBatch` fixture was not built).
- The actions ViewModel is activity-scoped via `hiltViewModel(key)`, like the detail ViewModel; sheet state is not cleared on screen exit (a discarded sheet stays closed, an open one reappears on re-entry). Reviewer: consider clearing on dispose.
- `InstallmentMutationState` is a feature-local sealed type, not `ui.model.MutationState` (that type has no refused-code field).
- `InstallmentEntryRowUi` gained defaulted fields, so existing detail tests still construct it; the existing detail mapper test does not yet assert `availableActions` / `suggestions` (test-author follow-up).
- Compose sheets are not UI-tested.

Validation to request, serially, via validation-runner:
1. `targeted-unit-test -Filter "*Installment*ViewModel*"` (new actions test plus the existing detail / overview / history tests).
2. `unit-test-shard -ShardName runtime-ui`.
3. architecture-contracts (confirm no UI -> DAO / TLC bypass; the UI uses only the plan coordinator and the read repository).
4. ui_dao.
5. `lint` (new strings, es/el parity, Compose).

### S10b-1 — tests added (test-author, NOT run)

Closes the two test follow-ups above (non-empty batch fixture; mapper `availableActions` / `suggestions`). Test-only; no source change.

`ui/screens/installments/detail/InstallmentPlanDetailViewModelTest.kt` (the `detail()` helper gained a `suggestions` parameter; new `suggestion()` helper):
- `S10b-1 regular entries of an active plan get actions by status and due state`
- `S10b-1 Link is offered only for an entry that has a suggestion`
- `S10b-1 a completed plan offers only Unskip on skipped entries`
- `S10b-1 terminal plans offer no actions on any entry` (CANCELLED, REFUNDED, PAID_OFF)
- `S10b-1 payoff entries get no actions even in an active plan`
- `S10b-1 suggestions are filtered per entry and mapped in the plan currency` (also: a suggestion for an entry outside the schedule is dropped; negative delta keeps its sign)
- `S10b-1 no suggestions leaves every row with an empty list`
- `S10b-1 the row carries the scheduled minor amount for the adjust prefill`

`ui/screens/installments/actions/InstallmentEntryActionsViewModelTest.kt` (new `batch()` fixture builds a non-empty `PostCommitActionBatch`):
- `a non-empty post-commit batch from Done reaches the runner exactly once after the coordinator returns` (order: coordinator, then runner)
- `only the non-empty batches of a Done result are dispatched, in order`
- `a non-empty batch is not dispatched when the result is not Done` (AlreadyInState and Refused)
- `a post-commit batch that throws does not change the success result or state`
- `a failing post-commit batch does not stop the next batch`
- `a CancellationException from the post-commit batch run propagates and is not swallowed` (later batch never runs; no Success state)

Filter to request (serially, via validation-runner): `targeted-unit-test -Filter "*InstallmentPlanDetailViewModelTest*,*InstallmentEntryActionsViewModelTest*"`, or the broader `*Installment*ViewModel*` already listed above.

### S10b-1 — architecture spot check (ACCEPT-WITH-CONDITIONS) and fixes

Fixes applied after the spot check. Implemented, NOT validated (nothing was run). Still dormant; writes only via `InstallmentPlanLifecycleCoordinator`; INV-31 kept; `CancellationException` still rethrown. No schema, DAO, guard or allowlist change.

CORRECTION to "S10b-1 — implemented" and "tests added" above (F2): the claims "AE-7/8/9" and "Undo (AE-14)" were wrong. In the domain spec AE-7 = Stop, AE-8 = Refund, AE-9 = Early payoff and AE-14 = Delete = undo; all four belong to S10b-2. The S10b-1 flows (Record, Link, Skip, Adjust, Unskip and the row Undo of Skip / Link / Adjust) come from ux-flows 3.6. KDoc (Model, ViewModel, Sheets), test section headers and the test class doc were relabelled accordingly; read every "AE-7/8/9/14" in the S10b-1 text above as "ux-flows 3.6".

Fixes:
- F1 (Medium) stale Undo replay: new `InstallmentEntryActionsViewModel.onScreenExit(changingConfigurations)`, called from the detail screen's `onDispose` (Activity found by unwrapping `LocalContext`; `isChangingConfigurations`). It always clears `notice` and `showDiscardDialog`; when not a configuration change and nothing is InProgress it also clears `sheet` and resets the mutation to Idle. A running action keeps its state. Optional part done: after a real exit the ViewModel is `detached` until the next `open()`, and an action that finishes while detached attaches no notice (so no Undo can replay on re-entry).
- F4: the action `ModalBottomSheet` uses `rememberModalBottomSheetState(confirmValueChange = { it != Hidden || (!running && !hasInput) })` (latest values via `rememberUpdatedState`), so a swipe or back cannot hide a sheet the ViewModel refuses to close. Side effect to review: with input, swipe and back are vetoed outright (they no longer open the discard dialog); the Cancel button still does.
- F4b (supersedes the F4 trade-off): the sheet's `confirmValueChange` now routes a Hidden request with input and not running to `viewModel.requestDismiss()` (discard dialog) and vetoes it; while running it vetoes silently; with no input it allows the hide (`onDismissRequest` then closes). A `BackHandler(onBack = viewModel::requestDismiss)` inside the sheet content routes system back the same way. Only `InstallmentEntryActionSheets.kt` changed; ViewModel unchanged (`requestDismiss` already covered by existing tests); Compose wiring not UI-tested; NOT validated.
- F5 (ux-flows 3.6 ~:734-737): `AlreadyInState` is a SILENT success (no snackbar, no undo). `InstallmentActionNotice.ALREADY_DONE` and `installments_action_notice_already` (en / es / el) were removed. `ILLEGAL_TRANSITION` now maps to the new `installments_action_refused_already_updated` ("This installment was already updated. Refreshing."); `PLAN_HAS_PAYMENTS` keeps `installments_action_refused_illegal`. es / el for the new string are DRAFTS pending owner native sign-off: es "Esta cuota ya se actualizó. Actualizando." / el "Αυτή η δόση είχε ήδη ενημερωθεί. Γίνεται ανανέωση." The "auto-refresh" is the detail's existing live read; no refresh call was added.
- F6 (tests, `InstallmentEntryActionsViewModelTest`): `confirmVerified(coordinator)` in every flow test; new tests: undo ignored while InProgress; Link AlreadyInState gives no notice and no undo; ILLEGAL_TRANSITION refusal maps to the already-updated copy; screen exit clears notice / dialog / idle sheet; rotation keeps the sheet and input but drops the notice; an in-progress action keeps its state and attaches no stale notice; `open()` after an exit re-attaches notices. The cancellation test now counts the coordinator call, asserts the state stays InProgress (not Failed), no notice, no dispatch and no second call. The three `assertNotEquals(0, R.string...)` tests were renamed `smoke only - ...`. No test was deleted. The AlreadyInState test was changed from ALREADY_DONE to "no notice".
- F3 (docs only now): LEGAL_PATHS "Installment Plan Mutations" gained a "UI CALLER (S10b-1)" block.

BINDING before S10b-2, S11 or S12a (F3 follow-up): extract ONE domain-level post-commit dispatcher (or a thin use case that calls the coordinator and then dispatches) and use it from the S7a-2 worker, the S6c-4 pre-backup reconciler and `InstallmentEntryActionsViewModel`, replacing the three duplicated `dispatchBatches` loops. Do not add a fourth copy in S10b-2 / S11 / S12a.

Residual risk:
- The post-commit batch is dropped if the activity finishes between commit and dispatch (same as the worker). The committed state stays correct; only side effects are skipped until the next trigger.
- Swipe / back on an edited sheet no longer shows the discard dialog (F4 trade-off, see above).
- Compose sheet and the `onDispose` wiring are not UI-tested.

Low caveats (accepted, no change):
- `unlinkExpense(entryId)` does not pin an `expenseId`: the Link Undo unlinks whatever expense the entry holds now. A concurrent relink to another expense would be undone instead.
- The Adjust Undo restores the values captured when the sheet opened, not the values at commit time; a concurrent change in between is overwritten.
- The "Adjusted" chip stays after an Adjust Undo (ux-flows 3.6 allows it).

Filter to request (serially, via validation-runner): `targeted-unit-test -Filter "*InstallmentEntryActionsViewModelTest*,*InstallmentPlanDetailViewModelTest*"`; then `lint` (new / removed strings, es / el parity, Compose) once for the batch.
- S10b-1 ACCEPTED (2026-10-27).
  - Final-tree runs, all PASS: targeted vr-20261027-112156-78411214, ui_dao vr-20261027-112715-aca6efe4, runtime-ui vr-20261027-112731-4af1d11e, lint vr-20261027-112902-3f9721ff.
  - Earlier on the pre-fix tree: architecture-contracts vr-20261027-110427-0c7ae32d.
  - Gate: architecture spot check plus cheap review, ACCEPT-WITH-CONDITIONS (F1, F2, F4/F4b, F5, F6 fixed; F3 BINDING before S10b-2/S11/S12a).
  - Committed.
  - Next loan work:
    - extract the shared post-commit dispatcher (F3, BINDING);
    - then S10b-2 (create/edit/end flows, OQ-21 delete wiring with reviewer-strict).

## S10b-1 F3 — post-commit dispatcher extracted

Implemented, NOT validated (nothing was run). No schema, DAO, guard or allowlist change. Behaviour is unchanged except one benign point (below).

Done:
- New `domain/installment/lifecycle/InstallmentPostCommitDispatcher.kt` (`@Singleton`, `@Inject` with `PostCommitActionRunner`): `suspend fun dispatch(batches)`.
  - Skips batches without actions and runs the rest in order.
  - Isolates each non-cancellation failure and logs only `e::class.java.simpleName` through Timber.
  - Rethrows `CancellationException` (a rethrowing catch sits directly above the broad catch; comments are in the KDoc, outside the CANCEL-01 200-char window).
  - Placed in domain: it depends only on `domain/sideeffect`, and the layering and cancellation architecture tests contain no rule against it.
- The three duplicated `dispatchBatches` loops are gone:
  - `InstallmentDueRecordingWorker`: constructor takes `InstallmentPostCommitDispatcher` instead of `PostCommitActionRunner`.
  - `InstallmentPreBackupReconciler`: second dependency is `Lazy<InstallmentPostCommitDispatcher>`. It is still resolved only when `postCommit` is non-empty, so the lazy graph rule is kept.
  - `InstallmentEntryActionsViewModel`: constructor takes `InstallmentPostCommitDispatcher`.
- Deviations (all benign):
  - The pre-backup reconciler used to pass empty batches to the runner and now skips them (empty batches are a no-op in the runner).
  - The worker's failure log moved from `Log.w` (worker TAG) to the dispatcher's Timber line, which still carries the class name only.
  - The worker's per-file "if (e is CancellationException) throw e" idiom was removed together with the broad catch; the worker has no broad catch left.
- Tests (no test deleted, behavioural assertions kept):
  - New `InstallmentPostCommitDispatcherTest`: empty list, skip empty batch, order, failure isolation with class-name-only log, cancellation propagates and stops the rest.
  - `InstallmentDueRecordingWorkerTest`: dispatcher wiring. The log assertion now uses Timber `CapturingTree` instead of `ShadowLog`.
  - `InstallmentPreBackupReconcilerTest`: dispatcher wiring. Its `batch()` helper now builds a non-empty batch.
  - `InstallmentEntryActionsViewModelTest`: dispatcher wired over the existing runner mock.
  - `DatabaseBackupRepositoryPreReconcileTest` and `InstallmentRestoreReconcileTest`: `Lazy` wiring only.
- Docs: LEGAL_PATHS "Installment Plan Mutations" (UI CALLER block trimmed; new "POST-COMMIT DISPATCH OWNER" block names the single owner and the three callers); ENGINE_INTERACTION_MAP (recordDueEntries block).
- The BINDING F3 condition is now met. S10b-2 / S11 / S12a callers must use `InstallmentPostCommitDispatcher` and never add a fourth loop.

Validation to request (serially, via validation-runner):
- `targeted-unit-test -Filter "*InstallmentPostCommitDispatcher*,*InstallmentDueRecordingWorker*,*InstallmentPreBackupReconciler*,*InstallmentEntryActionsViewModel*,*DatabaseBackupRepositoryPreReconcile*,*InstallmentRestoreReconcile*"`
- shards: `domain-a-m`, `runtime-ui`, `architecture-contracts`
- the registered cancellation guard (CANCEL-01, `CancellationSafetyArchitectureGuardTest`)
- S10b-1 F3 (post-commit dispatcher) ACCEPTED (2026-10-27).
  - Runs, all PASS:
    - targeted vr-20261027-124942-c578d034
    - cancellation vr-20261027-130147-0c1a80ae
    - architecture-contracts vr-20261027-130203-0c6e08df
    - domain-a-m vr-20261027-130249-59b0e21b
    - runtime-ui vr-20261027-130551-f79185c7
    - app-check vr-20261027-131008-2e573c31 (Hilt graph plus ALL unit tests)
    - static-guards vr-20261027-134131-cee3b012 (Python G-CANCEL-01)
  - Data shard vr-20261027-130420-4d23196d FAILED on `TransactionsViewModelTest > reactive load failure keeps prior rows…` with a Turbine "No value produced in 3s". That code is unrelated and unchanged, so it was a timing flake. The app-check passed on the same fingerprint afterwards, including that test, and the runner then refused a data-shard re-run as E_DUPLICATE_OR_OVERLAPPING_VALIDATION, because app-check covers it.
  - Architecture-guardian ACCEPT-WITH-CONDITIONS. Finding 1 note: the worker's batch-failure log is now a Timber line, and Timber is planted only in debug builds, so release logcat no longer carries it. It is not a persisted diagnostic, and the runner already catches per-action failures.
  - Finding 2 (an executable single-owner guard) is pending owner approval as owner-approvals 48.
  - Committed.

## S10b-2 — implemented (PARTIAL: OQ-21 "Delete expense and reopen" BLOCKED; nothing compiled, run or validated)

Dormant (InstallmentsFeature). No schema, DAO, guard or allowlist change. Every plan flow calls exactly one `InstallmentPlanLifecycleCoordinator` op; post-commit batches go through `InstallmentPostCommitDispatcher`. `CancellationException` is rethrown.

Ops used:
- Create: `createStandalone(terms, categoryId, PaymentMethod.UNKNOWN)` (FIXED_PAYMENT, residual FIRST, per-form `planUuid` so a retry is AlreadyInState).
- Edit (E3): `editDetails(planId, name, lender, categoryId, mode, currentCurrency, todayEpochDay)`.
- End: `stop` (AE-7), `refund(planId, false)` (AE-8), `refund(planId, true)` (AE-8 "already recorded", runs the Stop body), `payOff(planId, amountOrNull, date, today, zone)` (AE-9), `delete` (AE-14).
- Unlink: `unlinkExpense(entryId)`.

Files:
- NEW `ui/screens/installments/actions/InstallmentPlanActions.kt`: `InstallmentPlanMutationState`, shared `executeInstallmentPlanOp`, `InstallmentPlanActionsViewModel`, availability table (ux-flows 3.7), overflow menu and dialogs host.
- NEW `ui/screens/installments/create/InstallmentCreateScreen.kt` (form + ViewModel; overlay hosted by the overview, no new destination).
- NEW `ui/screens/installments/edit/InstallmentEditScreen.kt` (ViewModel + overlay hosted by the detail).
- M `InstallmentPlanDetailScreen.kt` (overflow menu, dialogs host, `onScreenExit`), `InstallmentOverviewScreen.kt` ("Add plan" top-bar action + create overlay).
- M read model (additive, defaulted): `InstallmentPlanDetail.categoryId`, `PlanSnapshot.categoryId`, set in `InstallmentPlanReadRepositoryImpl`. Needed because E3 sends the FULL category and the current one was not readable.
- Strings: values, es DRAFT, el DRAFT (all new `installments_*` S10b-2 keys, plus plurals `installments_stop_body`, `installments_payoff_body`). Needs owner es/el sign-off.
- Tests: `InstallmentPlanActionsViewModelTest`, `InstallmentCreateViewModelTest`.

BLOCKED (OQ-21 "Delete expense and reopen"): the standard delete is `ExpenseRepository.deleteExpense(...)` -> TLC (no use-case class exists). The INV-23 guard in `InstallmentPlanArchitectureGuardTest` (`deleteExpenseCalls`) flags ANY `deleteExpense(` call in installment code, UI included (its self-check plants `repo.deleteExpense(expense)` and an installments ViewModel path). Calling it from installment UI therefore needs a guard change or a new wrapper outside the installment package. Not done (no guard change, no new write path). Decision needed from the owner or planner: (a) allowlist one UI call site, (b) a neutral `DeleteExpenseUseCase` outside the installment packages, or (c) drop the action (Unlink plus "delete the expense in Transactions", whose reaction already reopens the entry through `planner.planDeleted`).

Deferrals and risks:
- Unlink has no row entry point yet: `openUnlink(context, entryId, seq)` is implemented and tested, but the detail row mapper has no `canUnlink` flag and the row menu has no Unlink item. The read model carries no `linkOrigin`, so the restriction "only captured/linked entries" is not enforced; the coordinator only allows PAID entries of a non-ended plan.
- Home-currency default for the create form is fixed to EUR (ux-flows 3.3 asks for the home currency when EUR/USD). Pay-off "Use an existing charge" and the financing cost preview are not built. The create form has no live preview and no first-due date picker (defaults to today; there is no field to change it yet: `setFirstDueEpochDay` exists in the ViewModel only).
- `PaymentMethod.UNKNOWN` is assumed to exist (spec: "or UNKNOWN"); verify at compile. `Category` is read via `CategoryRepository.getAll()` (id, name).
- Edit does not change the currency (shown only); no Edit ViewModel test yet.
- Compose is not UI-tested; dialog/overlay hosting is new.

Test-author follow-ups: `InstallmentEditViewModelTest` (blank name refused, canSave needs category for STANDALONE, editDetails gets the loaded category/currency, AlreadyInState closes, cancellation); a non-empty post-commit batch from `refund`/`payOff` reaches the runner once; mapper test for `toActionContext`.

Validation to request (serially): `targeted-unit-test -Filter "*Installment*ViewModel*"` (and `*InstallmentPlanReadModel*`, `*InstallmentPlanReadRepositoryImpl*`, `*InstallmentPlanDetailViewModel*` for the read-model field); shards `runtime-ui`, `architecture-contracts`; ui_dao; `lint` (new strings, es/el parity, plurals `many` in es); `static-guards` only if the owner picks a delete-wiring option for OQ-21.

### S10b-2 — test-author follow-ups added (nothing compiled or run)

Test-only. New or extended (all under `app/src/test/java/com/yourname/expensetracker/`):
- NEW `ui/screens/installments/edit/InstallmentEditViewModelTest`: `the loaded plan prefills the form with category, currency and categories and is not dirty`; `a missing plan is a bounded load failure and cannot be saved`; `INV-34 a blank name is refused locally and the coordinator is not called`; `INV-33 canSave requires a category for a STANDALONE plan`; `a purchase attached plan can be saved without a category`; `editDetails receives the loaded category and currency, trimmed name and the civil today`; `AlreadyInState is a silent success that closes the overlay`; `a refusal keeps the form and an unexpected exception becomes a bounded failure`; `a CancellationException from the coordinator is not turned into a failure`; `a dirty form asks before it closes and an untouched one closes`.
- EXTENDED `ui/screens/installments/actions/InstallmentPlanActionsViewModelTest`: `a non-empty post-commit batch from refund reaches the runner exactly once after the coordinator returns`; `a non-empty post-commit batch from payOff reaches the runner once and an empty batch is skipped`; `AlreadyInState from refund dispatches nothing`; `a failing post-commit batch does not change the refund success`; `a failing post-commit batch does not change the payOff success`.
- NEW `ui/screens/installments/actions/InstallmentPlanActionContextTest` (`toActionContext` and the ux-flows 3.7 status table): `toActionContext counts and sums only open entries, ...`; `toActionContext reports payments only for paid, payoff or refunded entries`; `toActionContext of a plan without entries ...`; `table Active without payments offers everything`; `table Active with a paid entry offers no delete`; `table Active with nothing left unpaid offers no pay off`; `table Completed offers edit details and refund only`; `table Cancelled offers delete only when nothing was paid`; `table Refunded offers nothing`; `table Paid off offers nothing`; `canEnd is true when at least one end option exists`.
- EXTENDED `domain/installment/readmodel/InstallmentPlanReadModelTest`: `detail carries the snapshot categoryId and keeps null as null` (helper `plan(...)` gained `categoryId`).
- EXTENDED `data/repository/InstallmentPlanReadRepositoryImplTest`: `loadDetail carries the stored plan categoryId for the edit prefill`; `loadDetail keeps a missing plan categoryId as null and not a default id` (helper `planRow(...)` gained `categoryId`).

Filter to request (one combined targeted run): `*InstallmentEditViewModelTest*`, `*InstallmentPlanActionsViewModelTest*`, `*InstallmentPlanActionContextTest*`, `*InstallmentPlanReadModelTest*`, `*InstallmentPlanReadRepositoryImplTest*` (or the wider `*Installment*ViewModel*` plus the last two).

Open points: (1) `PAID_BEFORE_TRACKING` is deliberately not asserted for `hasPayments` / Delete availability, because ux-flows 3.7 says only "Paid/Reversed" and the mapper ignores it; the planner should confirm the intended rule. (2) The edit ViewModel `todayEpochDay` is asserted as 20832..20833 because it uses the device zone. (3) Compose overlays (Edit/Create/Actions host) remain untested. (4) Create ViewModel post-commit dispatch is not covered here.

### S10b-2 — architecture spot check (ACCEPT-WITH-CONDITIONS) and fixes

Dormant. Source and tests only; no schema, DAO, guard or allowlist change. Nothing compiled, run or validated. Coordinator-only writes unchanged; `CancellationException` is rethrown everywhere; no new diagnostics (INV-31).

Applied:
- F1 (HIGH) Overlay ViewModels are activity-scoped (no NavHost) and never reset. Added `resetSession()` to `InstallmentCreateViewModel` and `InstallmentEditViewModel`. It is a no-op while the mutation is InProgress; otherwise it clears the form, the mutation and `showDiscardDialog`. Create also regenerates `planUuid` and recomputes `firstDueEpochDay` from `TimeProvider` (today); Edit sets `planId = null` (and cancels a pending load) so the next `start()` reloads. Called on every close path in both screens: onClose (top bar, back, dialog dismiss), discard-confirm, Create `onCreated`, Edit saved-close, and a `DisposableEffect` `onDispose`.
- F2 (MEDIUM) OQ-18 paid-before field always fails while there is no first-due picker (firstDue = today = declaredOn; `InstallmentScheduleCalculator:162`). The field is HIDDEN (`InstallmentCreateForm.SHOW_PAID_BEFORE_FIELD = false`) and `paidBeforeTrackingCount = 0` is always sent. The create test that sent paidBefore = 2 now asserts the flag is false and that 0 reaches the coordinator (not deleted). Recorded with deferral 49(b): re-show the field (flip the flag, send `form.paidBefore`) together with the first-due picker. The strings `installments_create_paid_before*` stay for that.
- F3 (MEDIUM-LOW) An in-flight op stuck InProgress after a `CancellationException`: `launchOp` (Actions), Create `save` and Edit `save` now catch `CancellationException`, set `mutation = Idle` only if it is still InProgress, and rethrow (CANCEL-01: CE-only catch that rethrows).
- F4 (LOW) Edit on a COMPLETED plan: the plan status is loaded (`InstallmentEditUiState.status`, `modeEditable`); the mode chips are disabled and `setMode` is ignored when COMPLETED.
- F5 (LOW) On a COMPLETED plan the refund "already recorded" answer no longer offers Stop (a COMPLETED plan cannot be stopped): `answerRefundAlreadyRecorded(true)` closes the dialog with the fixed notice `NOTHING_TO_CHANGE` and runs no coordinator operation. New string `installments_plan_notice_nothing_to_change` (values, es DRAFT, el DRAFT; needs owner es/el sign-off).
- F7 (tests) The CancellationException tests in Create, Edit and Actions now assert the mutation returns to Idle, not only "not Failed/Success".

Tests added (all under `app/src/test/java/com/yourname/expensetracker/ui/screens/installments/`):
- Create: success -> `resetSession` -> new `planUuid` (via two `createStandalone` calls) and Idle clean form; discard -> reopen is clean (no draft, no dialog, closes without asking); `resetSession` no-op while InProgress; `firstDueEpochDay` recomputed after the day advances (fake clock +3 days).
- Edit: success -> `resetSession` -> Idle and the next `start()` reloads (`loadDetail` twice); discard -> reopen clean; no-op while InProgress; F4 COMPLETED disables mode chips and ignores `setMode`.
- Actions: F5 refund already recorded on a COMPLETED plan closes with the notice and calls neither `refund` nor `stop`.

Follow-ups (not applied):
- F6 (LOW) TalkBack: the footer/top-bar action buttons of the Create and Edit overlays need accessibility semantics; and a disabled Confirm in the Pay off early dialog gives no feedback about why it is disabled (amount invalid or date in the future).
- The `PAID_BEFORE_TRACKING` delete rule (not counted as a payment for Delete availability, `toActionContext.hasPayments`) is CONFIRMED consistent by the spot check; it answers open point (1) above.

Filter to request (one combined targeted run): `*InstallmentCreateViewModelTest*`, `*InstallmentEditViewModelTest*`, `*InstallmentPlanActionsViewModelTest*`, `*InstallmentPlanActionContextTest*`. Also `lint` once for the new string (es/el parity).
- S10b-2 ACCEPTED, without OQ-21 (2026-10-27).
  - Final-tree runs, all PASS: targeted vr-20261027-145450-ba814fa1, runtime-ui vr-20261027-150152-4bb66816, domain-a-m vr-20261027-150323-93063fc1, lint vr-20261027-150454-4e8c1a8a.
  - Earlier on the pre-fix tree: ui_dao vr-20261027-144129-5ba33b29, architecture-contracts vr-20261027-144316-8ba1239f (InstallmentPlanArchitectureGuardTest INV-23/24/25).
  - Gate: architecture spot check plus cheap review, ACCEPT-WITH-CONDITIONS (F1–F5 and F7 fixed; F6 follow-up).
  - The OQ-21 "Delete expense and reopen" owner decision is pending (owner-approvals 49(c); recommended: drop).
  - The es/el strings are DRAFTS.
  - Committed.
  - Next loan work:
    - S11 (add expense: installments switch and currency picker; HARD gate Release 1.0 merged, which is satisfied);
    - S12a/b;
    - S8b (owner);
    - S13 (enablement, with the C1 deep-link confirmation).

## S12b — implemented

Dormant; uncommitted; nothing compiled, run or validated. Source and tests only; no schema, DAO, guard or allowlist change (no new DAO query). `BudgetRepository` unchanged.

Files (source):
- `domain/installment/readmodel/InstallmentPlanMath.kt`: `LinkedExpenseMoney` gained `dateMillis: Long? = null` (additive; existing positional constructions still compile).
- `domain/installment/readmodel/InstallmentPlanReadModel.kt`: new `InstallmentBudgetInputs`, `InstallmentBudgetInclusion`, and pure `InstallmentPlanReadModel.budgetInclusion(inputs, periodStartMillis, periodEndMillisExclusive, categoryId)`.
- `domain/installment/InstallmentPlanReadRepository.kt` + `data/repository/InstallmentPlanReadRepositoryImpl.kt`: additive `loadBudgetInputs()` (reuses `loadAll`, i.e. `getAllPlans` / `getEntriesForPlan` / `findExpenseMoney`; the linked-money load now also carries the expense date).
- `ui/screens/budget/BudgetViewModel.kt`: constructor gained `installmentReadRepository`; `BudgetInstallmentLine` (Included(MoneyDisplayUi) / Unavailable); `BudgetUiState.installmentLines: Map<budgetId, line>`; flag gate `internal var installmentsEnabled = InstallmentsFeature.ENABLED` (test seam; the const is false, so the repository is never read).
- `ui/screens/budget/BudgetScreen.kt`: `BudgetCard(installmentLine = ...)` renders the line under the spend breakdown.
- Strings (values + es/el DRAFTS, pending owner sign-off): `budget_includes_installments_format` ("Includes %1$s in installments"), `budget_includes_installments_unavailable`.

Read approach ("includes" meaning): B-2 / OD-10 / ux-flows 890. The line is the installment money already COUNTED in the budget: PAID entries only (a real linked expense; SCHEDULED, awaiting, skipped, reversed and the purchase record never count), at the linked expense's stored effective amount (so a not-mine or excluded expense adds 0), placed in the period by the CHARGE'S OWN date in [periodStart, periodEnd) (OD-10, not the nominal due date), and matched by category: plan category == budget category, an overall budget (categoryId null) takes every plan, a plan without a category counts only in overall budgets. Per currency in the read model (`Map<InstallmentCurrency, Long>`, no zero rows). The ViewModel shows, for each budget, only the amount in that budget's own currency (a budget is single-currency, so other-currency installments are not part of its spend); two budgets of different currencies show their own lines; nothing is ever summed across currencies. Only non-zero amounts are shown. Money via `MoneyDisplayUi.from(InstallmentMinorUnits.fromMinor(minor), code)` (minor units are summed exactly; no rounding).

Unavailable handling: an unreadable plan, or a PAID entry (of a plan in the budget's category scope) whose linked expense is missing, undated or in an unsupported currency, makes the result incomplete; the card then shows "Installment amount unavailable", never a partial figure and never zero. A failed read (non-cancellation exception) gives Unavailable for every budget. `CancellationException` is rethrown. No logging (INV-31).

Tests (written): NEW `ui/screens/budget/BudgetViewModelInstallmentLineTest` (AE-24: line per currency, no cross-currency sum, none when zero or outside the period, none and no read while the flag is off, flag default equals the const, unavailable for unreadable entry and for a failed read); NEW `domain/installment/readmodel/InstallmentPlanReadModelBudgetInclusionTest` (counting rules, period edges start-inclusive/end-exclusive, per currency, zero effective leaves no row, category scoping, incompleteness cases). Existing `BudgetViewModel(...)` constructions updated with `installmentReadRepository = mockk(relaxed = true)` in `BudgetViewModelCurrencyProvenanceTest` (2), `BudgetViewModelAutopilotQualityTest` (2), `BudgetAutopilotUiContractTest`, `DashboardBudgetProvenanceChainTest`.

Not covered (test-author): `InstallmentPlanReadRepositoryImplTest` for `loadBudgetInputs` and the `dateMillis` plumbing into `LinkedExpenseMoney`; the Compose line is untested.

Risks / open points:
1. Category match uses the PLAN's category (spec), not the linked expense's current category; if the user edits the installment expense's category the budget's spent follows the expense while the line follows the plan. Cheap to align later via `findExpenseFacts`.
2. The unavailable text can appear on every budget of the matching scope when one plan is unreadable (rare); chosen over hiding a possibly understated figure.
3. Tap target "tap -> plan detail/overview" from ux-flows 890 is NOT implemented (navigation would be a new route wiring, and the feature is dormant); the line is text only.
4. The budget screen re-reads on every status emission; a plan-only change (no expense change) refreshes on the next status emission or `refreshBudgets()`.
5. Possible lint note: `if (installmentsEnabled)` is a runtime var, so no constant-condition warning expected.

Category rule (follow-up applied; supersedes risk 1 and the "matched by category" wording above): the budget line matches by the linked EXPENSE's own category, not the plan's, so it follows the budget's spent when an installment expense is recategorised. The existing read (`ExpenseDao.getById` / `getExpensesBetweenUncapped`, both returning the full `Expense` row) already carries `categoryId`; it is plumbed through `InstallmentExpenseMoney.categoryId` (additive, default null) and `LinkedExpenseMoney.categoryId` (additive, default null) in `InstallmentExpenseFactsReaderImpl` / `InstallmentPlanReadRepositoryImpl`. No DAO query, schema, guard or allowlist change. `InstallmentPlanReadModel.budgetInclusion`: an overall budget (null category) takes every counted expense; a category budget takes only expenses whose categoryId equals it (an uncategorised expense counts only overall); a plan's own category is ignored. Completeness: a readable PAID expense of another category is skipped and never taints the budget; a PAID entry whose expense is missing has no known category, so it makes a category budget incomplete (Unavailable), never an understated figure. Tests updated: `InstallmentPlanReadModelBudgetInclusionTest` (expense-category rule, recategorised/adopted cases, incompleteness), `BudgetViewModelInstallmentLineTest` (linked expense carries categoryId 1L), and `InstallmentPlanReadRepositoryImplTest` (new `loadBudgetInputs` test for the dateMillis and categoryId plumbing, unreadable count, PAID-only linked money).

Owner approval 50(a) (supersedes the "own currency only" wording above; owner-approvals-20261022 item 50): the budget card's spent is converted to the home currency across all currencies, so `installmentLinesFor` shows "Includes X in installments" ONLY when the line's currency (the budget's) equals the card's spent currency (`status.currency`) AND `inclusion.minorByCurrency` has no non-zero entry in any other currency. Otherwise the line is Unavailable ("Installment amount unavailable"), never an understated figure. Nothing counted at all (zero) still gives no line; an incomplete inclusion or a failed read is still Unavailable; `CancellationException` still propagates. `installmentLinesFor` is now `internal` (test seam for the cancellation test). Tests in `BudgetViewModelInstallmentLineTest`: same currency and no others shows the line; own-currency-only non-EUR budget shows its line; other-currency counted installments give Unavailable; budget currency vs spent currency mismatch gives Unavailable; a `CancellationException` from `loadBudgetInputs` propagates; the status's own period and category reach `budgetInclusion` (different-category budget and out-of-period status get no line). The old "line per currency on each budget" and "no cross-currency sum" tests were replaced (both budgets are now Unavailable when two currencies have counted installments).

Review findings (S12b): 3 and 4 accepted (50(b): no PURCHASE-type parity check in `budgetInclusion` since the link policy already requires a spending type; a single unreadable plan makes the line unavailable everywhere, conservative). 5 is a pre-S13 follow-up (50(c)): memoise `loadBudgetInputs` per emission, or reload it only on installment changes (performance).

Validation to request (one combined targeted run first): `targeted-unit-test -Filter "*Budget*Installment*,*InstallmentPlanReadModel*"`, plus the touched constructor tests `*BudgetViewModelCurrencyProvenanceTest*`, `*BudgetViewModelAutopilotQualityTest*`, `*BudgetAutopilotUiContractTest*`, `*DashboardBudgetProvenanceChainTest*`, `*InstallmentPlanReadRepositoryImplTest*`; then `unit-test-shard -ShardName runtime-ui`; then `lint` (es/el string parity).
- S12b ACCEPTED (2026-10-27).
  - Final-tree runs, both PASS: targeted vr-20261027-155648-c09ab89e, runtime-ui vr-20261027-160150-9f733d2f.
  - Earlier on the pre-50(a) tree: domain-a-m vr-20261027-153625-b8dbc30d, data vr-20261027-153756-cea2bc95, lint vr-20261027-154329-5da44965. The 50(a) change touched BudgetViewModel and its test only.
  - Gate: cheap review ACCEPT-WITH-CONDITIONS (finding 1 resolved by owner-approvals 50(a); finding 2 tests added; 3 and 4 accepted; 5 is the pre-S13 memoise).
  - The es/el strings are DRAFTS.
  - Committed. Next: S12a.

## S12a — implemented

Status: implemented, NOT validated (nothing was run). Dormant: every hook is gated on `InstallmentsFeature.ENABLED` (false).

Ops and reads used (no new DAO query, no schema, guard or allowlist change):
- Write: only `InstallmentPlanLifecycleCoordinator.declareOnExpense(expenseId, terms, zone)` (declare) and `.delete(planId)` (Undo of the declaration, AE-14). Post-commit batches go through the shared `InstallmentPostCommitDispatcher` via `executeInstallmentPlanOp`. No DAO, TLC or repository write from the UI; no `deleteExpense(` (INV-23).
- Reads: `InstallmentPlanReadRepository.loadOverview/loadHistory/loadDetail/loadSuggestions` (existing). Chips are a UI join of expense id to entry/plan facts (OQ-22), never stored text. Menu eligibility uses `InstallmentExpenseFacts.isSpendingType/hasSplit` and the `Expense` row the screen already holds.
- AE-20: `InstallmentLockedExpenseException` is mapped to the fixed string `transactions_installment_purchase_locked` in `TransactionsViewModel.updateExpenseType` and `.updateOwnership` (two additive catch branches; the TLC already throws it).

Files:
- New: `ui/screens/installments/declare/DeclareInstallmentsSheet.kt` (ViewModel + sheet + host with Undo snackbar), `ui/screens/transactions/TransactionsInstallmentViewModel.kt` (marks loader, duplicate rule, row UI model, filter), `ui/screens/transactions/TransactionsInstallmentUi.kt` (chips, menu items, filter chip).
- Modified: `TransactionsScreen.kt` (additive hooks, new default param `onOpenInstallmentPlan`), `TransactionsViewModel.kt` (two catch branches only), `values/strings.xml`, `values-es/strings.xml`, `values-el/strings.xml`.
- Tests: `DeclareInstallmentsViewModelTest`, `TransactionsViewModelInstallmentTest`.

Deviations and decisions:
1. Marks/filter live in a NEW `TransactionsInstallmentViewModel`, not in `TransactionsViewModel` (an overlap file), so its constructor and existing tests are untouched. The plan test name `TransactionsViewModelInstallmentTest` is kept.
2. The filter is applied to the already grouped list in the screen (`filterGrouped`); with paging on the ALL tab it only filters loaded pages.
3. "Installment plan" badge and chip appear on every entry-linked expense (the read model does not expose the link origin), a superset of auto-recorded. The chip shows for PAID and REVERSED entries only.
4. AE-17 part 2 "possible duplicate" is implemented as a UI-only rule (same currency, amount within 1 minor unit, date in (due, due+10 days]). The window is a PROPOSAL for the domain-modeler to confirm. It is a chip on Transactions only, not on the plan detail.
5. Snackbar offers Undo; "View plan" is offered only after a failed Undo (a Material snackbar has one action). The row menu "View installment plan" covers the other case.
6. Navigation to the plan detail is a callback `onOpenInstallmentPlan` (default no-op); wiring it in the NavHost belongs to S13 enablement.
7. `InstallmentPlanOperation` was not extended: CREATE = declare, DELETE = undo (labels only).
8. OD-14: the plan currency picker (EUR/USD) is always shown; a plan currency different from the purchase forces FIXED_PAYMENT (no cross-currency conversion is invented) and states that financing cost is unavailable. A purchase in an unsupported currency (for example CHF) has no preselected plan currency.
9. Not implemented: recurring-link ("LINKED_ELSEWHERE") pre-check in the menu (needs a read the Transactions row does not have); the coordinator refuses with a fixed message instead. WRITES_BLOCKED has no dedicated string (shown as the generic failure).

es/el DRAFT strings (pending owner sign-off): `transactions_installment_*` (menu, plural chip, n of m, badge, matches, duplicate, filter, purchase_locked) and `declare_installments_*` (sheet labels, impact, refusals, snackbars). Format strings: `%1$d of %2$d`, `%1$s on %2$s`; plural `transactions_installment_chip_paid_in`.

Risks:
- `TransactionsScreen` chip row sits above the merchant line and the filter chip above the top bar; visual placement needs a design look. `TransactionItem`/`TransactionsScreen` are overlap files (merge conflict risk).
- The marks loader does N+1 `loadDetail` reads per refresh (small plan counts; memoise before S13 if needed).
- `InstallmentRowUi.of` runs the duplicate rule per visible row on recomposition (cheap, in memory).
- AE-20 locked-message branches in `TransactionsViewModel` have no VM-level test (full VM fixture); `TransactionsViewModelTest` should get one case.

Validation to request: targeted `*Transactions*Installment*,*DeclareInstallments*,*TransactionsViewModel*`; shards runtime-ui and architecture-contracts; ui_dao guard; lint (es/el parity, plural forms); static-guards (lifecycle entry, overlap files).
Gates: architecture-guardian, privacy-security, reviewer-strict.

S12a — AE-20 VM test added (TransactionsViewModelTest, uncommitted, not run): `INV-22 AE-20 updateExpenseType installment lock shows fixed resource message`, `INV-22 AE-20 updateOwnership installment lock shows fixed resource message`, `INV-22 AE-20 installment lock with hostile code never shows exception text`, `INV-22 AE-20 cancellation in updateExpenseType and updateOwnership is not mapped to installment lock message`. Request filter: `*TransactionsViewModelTest*`.

### S12a — privacy + architecture review (ACCEPT-WITH-CONDITIONS) and fixes

Status: fixes applied, uncommitted, NOT validated (nothing was run). Still dormant, coordinator-only writes, INV-31, no schema/DAO/guard/allowlist change. Prior targeted run vr-20261027-162517-1b8bae5a failed to compile (item 0); it is superseded by these edits.

Applied:
- Compile fix: `TransactionsViewModelInstallmentTest` mocked `spendingExclusionReason` with the enum; the entity field is `String?` (enum NAME). Now `exclusion?.name`. No other enum-vs-String mismatch on Expense fields in the new tests (`splitTemplateId` is Long?, `splitVisualization` String?).
- F1 (MEDIUM, privacy): `TransactionsViewModel.updateExpenseType` and `.updateOwnership` now have a dedicated `catch (e: InstallmentLockedExpenseException)` before `catch (e: Exception)`, mirroring the `GroupLinkedExpenseException` branch. It logs the constant `Transactions: TRANSACTION_UPDATE_BLOCKED code=EXPENSE_LOCKED_BY_INSTALLMENT_PLAN` only (never `e.code`, which is an unvalidated constructor argument) and emits the fixed UiText `transactions_installment_purchase_locked`. The old `e is InstallmentLockedExpenseException` branch inside the broad catch is removed; the broad catch keeps `if (e is CancellationException) throw e` as its first statement (CANCEL-01 order unchanged).
- F2 (MEDIUM): `DeclareInstallmentsHost` snackbars now pass an explicit duration: Declared (Undo) and UndoFailed (View plan) use `SnackbarDuration.Long`, Undone uses `Short` (matches the Transactions screen, which uses Short and no dismiss action, so `withDismissAction` is not added). Every branch consumes the notice after `showSnackbar` returns (action, dismissal or timeout), so it cannot return.
- F3 (LOW-MEDIUM): `TransactionsScreen` load-more trigger: while the installments filter is on, `shouldLoadMore` no longer needs the last-visible/`totalItems > 0` condition, so it keeps calling `loadMore()` page by page until `hasReachedEnd` (bounded by the existing paging; the derived state is keyed on the filter flag). Chosen over a "Showing loaded items only" hint because it needs no new string (no es/el draft), fixes the false-empty case (totalItems == 0 blocked the old trigger), and the existing loading indicator and "You've reached the end" row already communicate progress.
- F7 (LOW): `LaunchedEffect(installmentVm, groupedTransactionsBase)` now refreshes the marks whenever the base list changes (refresh cancels a running read), so a later auto-recorded payment no longer shows "Pay in installments...".
- F6 (tests):
  - `DeclareInstallmentsViewModelTest`: the vacuous `R.string.x != 0` test is replaced by an exhaustive check that `declareMutationMessage` is non-null for every `InstallmentRefusalCode` and every `InstallmentActionFailureCode` (Idle stays null).
  - `TransactionsViewModelInstallmentTest`: added fail-closed (a flagged row with no mark is Hidden, for every SpendingExclusionReason), split detected via `splitTemplateId`, duplicate rule ignores a non-spending type, and the failed-read test now plants a Timber tree and asserts the only log is `class=IllegalStateException` with no "Demo"/"600".
  - `TransactionsViewModelTest`: the lock tests now assert the exact log list (the constant only, no `TRANSACTION_UPDATE_FAILED` line, no throwable); the hostile-code test asserts the constant twice and no hostile text.

OWNER items (sign-off needed):
- F4: the filter is a single "installment rows" toggle, versus ux-flows "All / Installment payments / Purchase records". This is a deviation from the spec.
- F5: the purchase-lock message wording drops the spec's second sentence.

Follow-ups:
- Move the AE-17 duplicate rule into `InstallmentCandidateRule` after domain-modeler confirmation of the window.
- Show the possible duplicate on the plan detail too.
- Link origin in the read model, for exact auto-recorded badges (today the badge is a superset).
- Extend the guard scope to cover `ui/screens/transactions/TransactionsInstallment*.kt` (needs owner approval; no guard change made).
- Memoise the per-plan `loadDetail` reads before S13 (F7 refresh and F3 paging make refreshes more frequent).

Validation to request: targeted `*TransactionsViewModelInstallmentTest*,*TransactionsViewModelTest*,*DeclareInstallmentsViewModelTest*`; then runtime-ui and architecture-contracts shards; lint (no new strings); static-guards only if the gate requires it (CANCEL-01 window in `TransactionsViewModel` is the relevant check).

### S12a — final gate (reviewer-strict ACCEPT-WITH-CONDITIONS) and fixes

Status: fixes applied, uncommitted, NOT validated (nothing was run). Still dormant, coordinator-only writes, INV-31, CANCEL-01 kept, no schema/DAO/guard/allowlist change, no test deleted. Supersedes the F3 and snackbar items of the previous section where they differ.

1. MAJOR (F3 paging stall) - fixed. The predicate is now the pure `TransactionsLoadMorePolicy.shouldLoadMore(...)` (in `TransactionsInstallmentViewModel.kt`). Filter off: `isAllTab && nearEnd && !isLoadingMore && !hasReachedEnd`, EXACTLY the old rule (isLoading not consulted). Filter on: `isAllTab && !isLoading && !isLoadingMore && !hasReachedEnd` (no scroll condition). `TransactionsScreen` calls it from the same `derivedStateOf` and keys the effect `LaunchedEffect(shouldLoadMore, if (installmentFilterOn) transactions.size else -1)`. Reasoning: (a) refresh while loading: `loadMore()` returns early while `_isLoading`; the predicate is now false during the load and goes false to true when it ends, which restarts the effect; the refresh also empties the list (size changes), a second restart. (b) same-frame race (StateFlow conflation hides a fast isLoadingMore true/false pair, so `shouldLoadMore` never changes): each completed page grows the base list, which changes the key and restarts the effect. A failed page adds no rows, so no retry loop; an end-of-data page sets `hasReachedEnd`. Filter off: the key part is the constant -1, behaviour unchanged. Test: new `TransactionsLoadMorePolicyTest` (filter off equals the original rule for all 32 combinations; filter on x isLoading x isLoadingMore x hasReachedEnd x nearEnd; other tabs never page).
2. MINOR (PAYOFF label) - fixed. `InstallmentRowMark.Payment` has `showSequence` (true only for REGULAR entries); `InstallmentRowChips` shows the source badge only when false (no "Installment 0 of N"). Test: `AE-17 a PAYOFF entry shows the source badge only...` (also asserts N counts REGULAR entries and a PAYOFF entry is not a duplicate reference).
3. MINOR (reads) - fixed. The refresh job does `delay(REFRESH_DEBOUNCE_MS = 250)` before `loader.load`, under the existing `loadJob?.cancel()`, so bursts coalesce and a newer refresh cancels both the wait and an in-flight read (the CancellationException still propagates). "Skip while isLoadingMore" was NOT done (the VM does not know that state; the debounce covers paging bursts). Tests: two quick refreshes cause one `loadOverview()` (virtual time); a slow first read never lands after a second refresh.
4. MINOR (snackbar replay) - fixed. `DeclareInstallmentsViewModel.changed` is a one-shot `Flow<Unit>` (conflated Channel) sent from the declare and undo success paths; the host collects it (`rememberUpdatedState(onChanged)`), so the refresh no longer depends on the snackbar coroutine. Each notice snackbar is wrapped in try/finally that calls `consumeNotice(notice)`, which clears only that notice, so a snackbar cancelled by leaving composition consumes its notice (no replay on return) without wiping a newer one. `open()` now KEEPS a pending notice (it used to null it, silently dropping an Undo). Decision: keep, not offer a new UI: the declaration is real and Undo stays valid until its own timeout; the notice is replaced only by a newer one. Consequence for the owner: if the user declares a second purchase before the first snackbar times out, the first Undo is lost when the second notice replaces it (the plan stays reachable from the plans screen).
5. MINOR (AlreadyInState) - fixed. A silent AlreadyInState declare still sends the `changed` signal (it is `Success`). Test covers the signal with no notice.
6. Tests added/changed: `DeclareInstallmentsViewModelTest` (declare and undo `Done(ref, listOf(batch))` reach `PostCommitActionRunner.run(batch)` via coVerify; one signal per declare/undo/AlreadyInState, none on refusal; `consumeNotice(expected)` clears only that notice; `open()` keeps a pending notice). `TransactionsViewModelInstallmentTest` (`InstallmentRowMarksLoader.load(enabled = true)` maps details to marks; the failed-read test now starts from NON-empty marks so the EMPTY assertion is meaningful; PAYOFF; debounce; cancel).

OWNER question (OD-14 narrowing): the declare sheet does not offer EQUAL_SPLIT when the plan currency differs from the purchase currency (a fixed payment is the only way; `effectiveSplit`). Spec line 128 allows an equal split in that case, and the coordinator does not enforce OD-14. Decide: keep the narrowing (no conversion is invented; documented in the `DeclareForm` KDoc) or allow it (needs a stated conversion rule).

S13 re-check: declaring on an old purchase with `paidBeforeTrackingCount = 0` (the declare sheet always sends 0) creates SCHEDULED entries whose due date can already be in the past. Confirm AUTO_RECORD cannot record them as new expenses (INV-12 / D-c), for example through the due-recording worker or the pre-backup reconciler.

F5 wording note for the owner: the purchase-lock message states the type and ownership lock but not the amount (the spec's second sentence is dropped); confirm that is acceptable.

Validation to request: targeted `*TransactionsLoadMorePolicyTest*,*TransactionsViewModelInstallmentTest*,*DeclareInstallmentsViewModelTest*,*TransactionsViewModelTest*`; then runtime-ui and architecture-contracts shards; lint (no new strings). Gate: reviewer-strict re-check of items 1 and 4.

### S12a — reviewer-strict iteration 2 (ACCEPT-WITH-CONDITIONS) and text fixes (2026-10-27)
- Iteration-1 findings 1–6 are CLOSED. Filter-off paging is provably unchanged (the 32-combination policy test).
- Finding 1, text corrected: the "a failed page … no retry loop" claim was inaccurate. A page that keeps failing is still retried through the isLoadingMore toggle, as before; with the filter on, scrolling no longer stops it.
  - PRE-ENABLE item: with the filter on, key the effect on (isAllTab, isLoading, hasReachedEnd, base row count or generation) without the isLoadingMore toggle, or back off retries.
  - Correction to the iteration-1 section: its "no retry loop" claim is superseded.
- Finding 2 (recorded, owner-visible): the pending Declared/Undo snackbar is consumed when the activity is recreated (rotation, dark mode, locale), so the Undo is lost. This is fail-safe (no replay, no duplicate); the plan can still be deleted from the plan screen while it has no payments. Optional: skip the consume while isChangingConfigurations.
- Finding 3: the test name and comment were fixed.
- Finding 4, PRE-ENABLE device QA checklist (no Compose test harness in the repo):
  - (a) filter on with more than one page and few marks: paging continues to the end;
  - (b) pull-to-refresh with the filter on, during paging;
  - (c) leave and return while the Declared snackbar is showing: no replay and no second refresh;
  - (d) rotate while the Declared snackbar is showing.
- Residual stall windows (narrow, recoverable): an identical size after a same-frame refresh; a tryLock miss; an unescaped LIKE in AmountOpsDisabled search. A sturdier VM-exposed generation key is optional, pre-enable.
- Final-tree runs before these text edits, all PASS: targeted vr-20261027-171704-0fd845b6, runtime-ui vr-20261027-171750-e7040ef3, architecture-contracts vr-20261027-165255-6729065d, lint vr-20261027-165542-a00e6b80, static-guards vr-20261027-170046-6c8c8c31, cancellation vr-20261027-165138-ecfd4156.
- S12a ACCEPTED (2026-10-27).
  - Final targeted vr-20261027-172037-f635eea6 PASS, after the text-only fixes.
  - Gates:
    - privacy-security plus architecture: ACCEPT-WITH-CONDITIONS;
    - reviewer-strict iteration 1: ACCEPT-WITH-CONDITIONS (1 MAJOR, fixed);
    - reviewer-strict iteration 2: ACCEPT-WITH-CONDITIONS (text fixes applied; pre-enable items recorded).
  - Committed.
  - The loan lane is now WAITING on:
    - S8b: owner approval of the guard strengthening;
    - S11: blocked by the build order (nl-quick-add inc 1, then MEC B and C);
    - S13: enablement, needs S8b and S11 plus the pre-enable items.
  - Owner items: OQ-21 (49c); the OD-14 EQUAL_SPLIT narrowing; F4 filter granularity; F5 lock wording; es/el sign-off.

## 2026-10-27 OWNER DECISIONS (owner-approvals item 51c)
- The owner replied "I approve" to the orchestrator's recommendations:
  - S8b guard strengthening approved (A15): five exact-file entries on installment_cloud_reachability, with a negative fixture.
  - Item 48 guard approved: only InstallmentPostCommitDispatcher.kt references PostCommitActionRunner under the installment packages.
  - OQ-21 DROPPED: no "Delete expense and reopen" action.
  - OD-14 narrowing accepted: cross-currency plans allow FIXED only.
  - F4 accepted: a single filter toggle.
  - F5 accepted: the shortened locked-expense wording.
- The es/el native sign-off is still open; the strings stay DRAFTS.
- Build order confirmed: nl-quick-add increment 1 (new lane Cost-agregator-nlq), then MEC B1/B2/C, then loan S11.
- Next:
  1. item 48 guard (test-only);
  2. append-only spec/ux clarifications for OQ-21, OD-14, F4 and F5;
  3. S8b, starting with a feature-planner refresh of S8b against current source and A15.

### Item 48 guard (2026-10-27, test-author; NOT validated)
- File: app/src/test/java/com/yourname/expensetracker/architecture/InstallmentPlanArchitectureGuardTest.kt (test-only; no allow-list beyond the dispatcher's exact path; existing tests untouched).
- Rule id: POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER. Scope: domain/installment/, service/installment/, ui/screens/installments/. Matcher runs on sanitized code (comments and string bodies blanked) with `\bPostCommitActionRunner\w*`, so imports, types, ctor params, FQNs, `::` refs and Impl lookalikes are flagged; comment/KDoc mentions are not.
- Tests added:
  - `S10b-1 F3 PostCommitActionRunner is referenced only by InstallmentPostCommitDispatcher in installment packages` (live scan)
  - `self-check S10b-1 F3 post-commit runner detector flags planted references and ignores comments strings the dispatcher and other paths` (planted: ui/screens/installments, service/installment and domain/installment paths flagged; dispatcher path, ui/screens/addexpense and other non-installment paths clean)
  - `S10b-1 F3 non-vacuity - the dispatcher exists in the scan and itself references PostCommitActionRunner`
- Pre-change grep of `PostCommitActionRunner` under the three packages: only domain/installment/lifecycle/InstallmentPostCommitDispatcher.kt (line 4 import, line 19 KDoc mention, line 27 ctor param). No other file in service/installment or ui/screens/installments; no STOP condition.
- Validation to request: targeted-unit-test filter `*InstallmentPlanArchitectureGuard*`.

### Spec clarifications for item 51c (2026-10-27, domain-modeler; docs only)
- Appended "## Clarification 2026-10-27 — owner decisions (owner-approvals item 51c)" to docs/features/loan-installments/domain-spec.md (C-1, OD-14) and to ux-flows.md (U-1 OQ-21, U-2 F4, U-3 F5, U-4 OD-14 cross-reference).
- The old wording in gate2-decisions.md:24 and plan :446/:471/:543 is superseded by those sections and left as is.
- New PRE-ENABLE items (before S13), orchestrator decisions under standing approval:
  - PE-OD14: OD-14 is enforced only in the UI (DeclareInstallmentsUiState.effectiveSplit, DeclareInstallmentsSheet.kt:141-147). Neither the coordinator's declareOnExpense (:133-179) nor editDetails (:690-708) checks it.
    - Add domain enforcement in InstallmentPlanLifecycleCoordinator, the single owner, with a bounded refusal. This is a small hardening slice before S13.
    - The F-2 nit goes with it: the "Equal split" chip is enabled with no effect before a currency is picked.
  - PE-F5: the accepted short message says "its amount can't be changed", but it is emitted only on type and ownership changes (TransactionsViewModel :757-760, :866-869).
    - Recommend field-neutral wording before S13. This is a new wording decision for the owner, with es/el sign-off.
    - The edit-form locked treatment (disabled controls, "Locked while..." line, "View plan" link) is not implemented.
  - PE-UNLINK: the Unlink row entry point is not wired (openUnlink at InstallmentPlanActions.kt:261 has no caller and there is no canUnlink flag). The role lines in the Transactions delete dialog (ux-flows :820-826) are missing. The OQ-21 "unlink, then delete" route depends on both.
  - Recorded rule: Unlink is unrestricted (the read model has no link origin). "Unlink only for USER_LINKED" from plan :543 is not adopted.

### S8b-1 implementation (2026-10-27, implementer; NOT validated)
- Scope: guard strengthening only (owner-approvals item 51c; plan Amendment 2026-10-27 "S8b-1"). No Kotlin, no schema, no registry/suite/allowlist/baseline change. Rules 01-03 and all scan logic unchanged.
- Pre-checks: all five files exist under app/src/main/java/com/yourname/expensetracker/ and have 0 case-insensitive "installment" hits.
- Files changed:
  - scripts/verify_installment_cloud_reachability.py: SURFACE_EXACT_FILE_SUFFIXES gains data/repository/FinancialWeatherRepository.kt, domain/forecasting/ForecastInputAssembler.kt, domain/logic/SynthesisEngine.kt, domain/logic/NarrativeGenerator.kt, domain/usecase/dashboard/DashboardDataProvider.kt (existing di/AiModule.kt and di/PrivacyModule.kt kept); docstring "exact files" block and scope-limit note updated.
  - scripts/test_verify_installment_cloud_reachability.py: seeds, pins and new tests (below).
  - docs: docs/architecture/LEGAL_PATHS.md (~:492-500), docs/architecture/CODEBASE_SEGMENTS.md (:755), docs/architecture/ENGINE_INTERACTION_MAP.md (NOT line, ~:154-159), docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md (:121).
- Pin changes (the planner's "6" counts surface ENTRIES = 4 directory prefixes + 2 exact files, which equals the seed count and the clean-tree scanned count; the CLI "N file(s) scanned" is seeds + extra fixture files):
  - entries 6 -> 11 (4 prefixes + 7 exact); exact suffixes 2 -> 7; _SURFACE_SEEDS 6 -> 11 seeds (so _SEED_COUNT, _ALL_SURFACE_ENTRIES and the parametrized unmatched tests expand automatically);
  - test_surface_definition_constants: exact tuple now the 7 suffixes in declared order;
  - test_surface_entries_under_test_are_exactly_the_six_documented_entries -> ..._eleven_documented_entries (asserts 4/7/11 and _SEED_COUNT == 11); test_surface_all_six_entries_seeded_is_the_control_and_passes -> test_surface_all_eleven_entries_seeded_is_the_control_and_passes (scanned == 11);
  - test_negative_one_reference_in_each_surface_category list +5; matched_surface_entries truth table +5 positives +2 negatives; is_cloud_surface_path true +5, false +8;
  - comments/docstrings "six" -> "eleven".
- New tests: test_negative_s8b1_entry_flags_projection_port_import_and_field (x5; line 3 R1+R3, line 6 R3, exit 1, 3 violations); test_positive_s8b1_off_surface_home_installment_weather_source_is_not_scanned; test_positive_s8b1_suffix_precision_look_alike_file_names_are_not_on_the_surface (x5); test_positive_s8b1_suffix_precision_look_alike_directory_is_not_on_the_surface (x5); test_infra_s8b1_entry_file_missing_exits_two_naming_that_entry (x5).
- Validation to request: pytest-subset with -TestFilter scripts/test_verify_installment_cloud_reachability.py; then registered guard installment_cloud_reachability (expect PASS: all 11 entries matched, 0 hits; the scanned-file count rises by at least the 5 new files); also scripts/ci/test_gr10b_source_scope_matrix.py (docs row edited).
- Residual: scripts/ci/guard_registry.py description for installment_cloud_reachability still says "AI/privacy DI modules" (not edited by instruction; not wrong, less specific). ENGINE_INTERACTION_MAP :150 (ForecastInputAssembler "projected entries") is stale versus A15 and is left to S8b-3 docs.

### Item 48 + S8b-1 review fixes (2026-10-27, implementer; NOT validated)
- Finding 1: InstallmentPlanArchitectureGuardTest item 48 non-vacuity test now asserts every POST_COMMIT_RUNNER_SCOPE_PREFIXES entry matches at least one scanned file; the rule KDoc gains a "Known limits" note (typealias outside the three packages; wrappers that run batches without naming the type; installment code outside the three packages).
- Finding 2: ENGINE_INTERACTION_MAP.md ForecastInputAssembler line now reads SUPERSEDED by A15 (not a projection consumer; Home-only source HomeInstallmentWeatherSource, S8b-3). This closes the "stale" residual noted under S8b-1.
- Finding 3: domain-spec.md and ux-flows.md 2026-10-27 clarification Source paragraphs now cite item 49c (OQ-21) and the S12a reviews (handoff :2171-2198) for OD-14, F4 and F5, and say "Four of the six 51c(c) decisions are spec/UX decisions".
- Finding 4: ux-flows.md U-1 rule 4 and the OQ-21 row cite owner-approvals item 56 (orchestrator, standing approval; owner FYI) as the record for unrestricted Unlink.
- Finding 5: LEGAL_PATHS.md "POST-COMMIT DISPATCH OWNER" gains an "Enforced by InstallmentPlanArchitectureGuardTest POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER" line.
- Finding 6: this entry.
- BINDING: guard_registry.py:770-779 description still omits the five S8b-1 files — follow-up (registry change = stop-and-ask).
- Validation to request: targeted-unit-test `*InstallmentPlanArchitectureGuard*`; docs-only edits otherwise.

### Item 48 guard + S8b-1 + 51c clarifications ACCEPTED (2026-10-27)
- Gate: reviewer-strict ACCEPT-WITH-CONDITIONS (architecture and privacy checklist). Findings 1-5 applied.
- Validation, final tree, all PASS:
  - pytest test_verify_installment_cloud_reachability: vr-20261027-204733-f65d82a4
  - pytest test_gr10b_source_scope_matrix: vr-20261027-204751-0fa6bbcc
  - registered-guard installment_cloud_reachability: vr-20261027-204809-722bfcac
  - targeted *InstallmentPlanArchitectureGuard*: vr-20261027-204825-ecdead29
  - registered-guard guard_docs_truth: vr-20261027-205044-4b5d59ee
  - pre-fix targeted: vr-20261027-204012-3b910026
- Committed.
- Next: S8b-2 (engine projections), then S8b-3 and S8b-4. One batch validation, then static-guards and app-check at the S8b candidate.
- OWNER items (non-blocking):
  - item 53c: a HomeInstallmentWeatherSource consumer pin;
  - item 56: extend item 48 to the post-commit wrappers;
  - PE-F5: field-neutral locked wording.

### S8b-2 implementation (2026-10-27, implementer; NOT validated)
- Scope: engine projections (INV-15, INV-16, INV-29 stress baseline; AE-7, AE-11). Nothing compiled, run or validated. No schema change, no allowlist/marker/baseline/registry change, no new catch.
- Production files (under app/src/main/java/com/yourname/expensetracker/):
  - domain/cashflow/CashFlowCalculator.kt: new LAST ctor param `installmentProjectionPort: InstallmentProjectionPort`; new additive `DailyCashFlow.projectedInstallments: List<InstallmentProjectedEntry> = emptyList()`. Only `calculateDailyCashFlow` reads the port (once, after the empty-range early return, bounded to the emitted civil days). Each entry: minor to major via `InstallmentMinorUnits.fromMinor`; same currency as home = used as is; otherwise the existing `currencyConverter.convertOutcome` (FORECAST_DATE, atMillis = day start, StaleRatePolicy.None), exactly like the recurring items. Converted amounts are added to `recurringExpenseAmounts`, so they reduce the day balance and appear in `recurringAggregate`. A Failed outcome does `conversionFailures++` and `continue` (entry omitted, `isPartial`, `failedConversionCount`); never 0, never summed raw. `getUpcomingBills` untouched.
  - di/CashFlowModule.kt: provider takes and passes the port.
  - domain/forecasting/FinancialStressForecastEngine.kt (@Inject constructor, no module): two new LAST ctor params `installmentProjectionPort`, `recurringInstallmentEligibility`. Port read once per forecast up to the 90-day horizon end. Per horizon, entries whose civil due day starts in [startOfDay(now), now + N days) are converted by the existing HZ01-B `convertToDisplayOrNull` (LATEST_AVAILABLE) inside `calculateRecurringOutflows` ("Part 3"); failure raises `excludedConversionCount`, `recurringObligationsPartial`, `result.isPartial`, `result.excludedCount`. Projected amounts are part of `recurringObligations`. Baseline: if the port returns at least one entry, `recurringInstallmentEligibility.excludedSnapshotIds(normalizedExpenses)` is removed from the Monte Carlo purchases for ALL horizons ("global while projected", owner-approvals 53a). With an empty port the owner is not consulted and the baseline is identical to before.
- Docs: docs/architecture/ENGINE_INTERACTION_MAP.md ~:146-153 marks both engines as implemented by S8b-2.
- Tests (new): domain/cashflow/CashFlowCalculatorInstallmentTest.kt; domain/forecasting/FinancialStressForecastInstallmentTest.kt; domain/installment/projection/TestInstallmentProjectionPorts.kt (helpers `emptyInstallmentProjectionPort()` and `fakeInstallmentProjectionPort(plans, todayEpochDay)`, the latter runs the REAL `InstallmentProjectionRule.project`).
- Tests (constructor-only, no assertion change): CashFlowCalculatorTest, domain/budget/P6BudgetCleanupTest (:227), domain/forecasting/FinancialStressForecastEngineTest, e2e/NotificationParserDashboardTotalSeamTest (:452). A grep of app/src (test, androidTest, main) found no other `CashFlowCalculator(` / `FinancialStressForecastEngine(` call site besides CashFlowModule.
- Dormancy: no new gate. Precedent is S7b BillReminderManager, which calls the port ungated. In production the port returns entries only if plan rows exist; plans can only be created through the UI that is dormant behind InstallmentsFeature.ENABLED = false (and via restore of a backup that contains plans). So behaviour today equals pre-S8b; once a plan exists the engines react regardless of the flag.
- Behaviour notes: a port failure in `calculateDailyCashFlow` propagates (no catch; same as other DAO reads there); in the stress engine it falls into the existing outer catch and yields CALCULATION_UNAVAILABLE (fail closed). CancellationException is not caught anywhere new.
- NOT touched: SynthesisEngine, ForecastInputAssembler, FinancialWeatherRepository, NarrativeGenerator, DashboardDataProvider, health score, SmartSavingsEngine.
- Residual: CashFlowCalendarScreen shows no installment row in the day list (only totals/ending balance include it); stress engine's `recommendations` text path unchanged. The stress `now`/port `today` come from the same TimeProvider in production.
- Validation to request: targeted-unit-test `*CashFlowCalculator*,*P6BudgetCleanup*,*FinancialStressForecast*,*NotificationParserDashboardTotalSeam*,*InstallmentProjection*` (batch with S8b-3/4 per the plan).

### S8b-3 implementation (2026-10-28, implementer; NOT validated)
- Scope: Home installment-aware weather and the single merge point (A15 option A; INV-15, INV-16, INV-29; AE-7, AE-11). Nothing compiled, run or validated. No schema change, no allowlist/marker/baseline/registry/guard change, no new catch in suspend code (only `Flow.catch`, which never intercepts CancellationException). The first implementer run ran out of turns; a second run verified the code against the plan and completed docs, this handoff entry and the spec clarification.
- Production files (under app/src/main/java/com/yourname/expensetracker/):
  - N data/repository/HomeInstallmentWeatherSource.kt (@Singleton, off the cloud surface): `observe(): Flow<HomeInstallmentWeather?>`; `HomeInstallmentWeather(weather: FinancialWeather, installmentRows)` is a Home-only wrapper, NOT a field of FinancialWeather, DashboardData or ProcessedDashboardData.
  - N domain/installment/projection/InstallmentUpcomingProjection.kt: 31-day window (`today .. today + 30`), reads `InstallmentProjectionPort.upcomingEntries`, drops non-positive amounts, order (due day, plan id, seq), bounded to 100 rows, `groupByCurrency` with same-currency Long sums only. A sibling of RecurringPlanProjectionService, which is unchanged. Never an UpcomingItem.
  - N ui/screens/home/HomeWeatherMerge.kt: pure `replaceWeather`. Replaces the single FinancialWeatherWidget in place (position kept) and keeps the BASELINE widget's `upcomingItems`; null aware weather or no weather widget returns the input unchanged.
  - M ui/screens/home/HomeViewModel.kt: new LAST ctor param `homeInstallmentWeatherSource: HomeInstallmentWeatherSource`; `internal var installmentsEnabled = InstallmentsFeature.ENABLED` (BudgetViewModel precedent); a private shared `installmentWeather` flow driven by `dashboardReloadTrigger`; public `installmentRows: StateFlow<List<InstallmentUpcomingRow>>` (separate Home-only state, empty when off); in `processedDataFlow`, `combine(compiledFlow, installmentWeather)` -> `HomeWeatherMerge.replaceWeather` AFTER `computeDashboardWidgetsUseCase.compute()`. With the seam off the old `compiledFlow` is used unchanged and the source is never read.
- Design: the source runs the same pipeline as FinancialWeatherRepository.getFinancialWeather (same five input flows with `.catch { emit(emptyList()) }`, mapExpenseSnapshots, getPatternsFromSnapshots, assemble, synthesize, resolveHomeCurrency fail closed, generate, same FinancialWeather mapping) using the UNMODIFIED singletons. FinancialWeatherRepository is NOT edited (the 31-day buildUpcomingItems is duplicated in the source file instead of extracted). `compute()` returns null when the port yields no row in the window (no exclusion, no consultation of the eligibility owner), so Home keeps the baseline. A failure in one emission yields null for that emission (bounded Timber reason code = exception class name only). Invalidation: `InstallmentPlanReadRepository.observeOverview()` is a 6th combine input used only as a trigger (it combines observeAllPlans and observeAllEntries, so any entry status change re-emits); its value is unused, and its own failure falls back to a single Unit.
- How planned inputs enter assemble(): each row becomes a domain `PlannedExpense(id = -entryId, amount = InstallmentMinorUnits.fromMinor(amountMinor), currency = plan currency code, date = due day start in the system zone, priority = MUST, sourceOccurrenceKey = null, status = default "PLANNED")`, appended to `forecastInputAssembler.mapPlannedExpenses(plannedEntities)` as the `plannedExpenses` argument. The dedupe at ForecastInputAssembler :526-529 only drops items with a non-null `sourceOccurrenceKey`, so projections survive; the per-currency block at :543-589 converts foreign currencies at LATEST_AVAILABLE or omits the group and marks the forecast partial (INV-16, never 0, never raw). SynthesisEngine keeps `status == "PLANNED"` items (:242) and counts MUST items due in the rest of the month as committed (:301, :430-431, :612-618). So option B is NOT needed.
- INV-29 arithmetic: while the port has at least one row, `RecurringInstallmentEligibility.excludedSnapshotIds(allSnapshots)` (the single owner) is removed from the snapshots BEFORE detection and assembly. Aware committed = baseline committed computed without linked history + the projected MUST amounts due in the month. It REPLACES the baseline weather and is never added to it ("swap, never sum"). Global while any entry is projected (owner-approvals 53a); when the last projection ends the source emits null, Home shows the baseline, and linked history re-enters it (expected step change; spec clarification 2026-10-28).
- upcomingItems: built from the forecast components with the projected planned items (negative ids) removed, and in the Home merge the BASELINE widget's `upcomingItems` are kept, so the list is identical to pre-S8b by construction and a projection is never an UpcomingItem.
- Constructor sites updated: HomeViewModelWidgetsTest (:184, `homeInstallmentWeatherSource = mockk(relaxed = true)`), HomeViewModelCurrencyReloadTest (:177), and the two sites inside the new HomeViewModelInstallmentWeatherTest. A grep of app/src found no other `HomeViewModel(` constructor call (none in androidTest; the others are a comment and a test name).
- Catches added: none in suspend code. Only `Flow.catch` in HomeInstallmentWeatherSource (observe and per-emission) and HomeViewModel (installmentWeather); CancellationException still propagates.
- Docs: ENGINE_INTERACTION_MAP :146-171 (ForecastInputAssembler SUPERSEDED by A15; HomeInstallmentWeatherSource merged in HomeViewModel after compute(); Home weather merge point row), LEGAL_PATHS "HOME INSTALLMENT-AWARE WEATHER read path" (:994-1016) plus the FORBIDDEN entry (:1034-1036), CODEBASE_SEGMENTS (:772, :779-780, :792), and docs/features/loan-installments/domain-spec.md "Clarification 2026-10-28 — INV-29 owner (S8b-3)".
- Tests (new): data/repository/HomeInstallmentWeatherSourceTest (null when empty, AE-7, AE-11, INV-15, 31-day window, MUST planned inputs normalised by the assembler, INV-29 swap-never-sum, plan purchase exclusion, INV-16 with/without a rate, upcomingItems identical, overview re-run, home currency failure -> null); domain/installment/projection/InstallmentUpcomingProjectionTest; ui/screens/home/HomeViewModelInstallmentWeatherTest (replaced not added, separate state, no weather widget -> nothing added, seam off, production default of the seam = ENABLED = false, port empty equals seam-off state, source failure keeps the baseline). Constructor-only: HomeViewModelWidgetsTest, HomeViewModelCurrencyReloadTest.
- Cloud files UNCHANGED and installment-free (0 case-insensitive hits): FinancialWeatherRepository, ForecastInputAssembler, SynthesisEngine, NarrativeGenerator, DashboardDataProvider; UpcomingItem and DashboardBriefingInputBuilder also 0 hits. InstallmentPlanArchitectureGuardTest scans plan code (domain/installment/, service/installment/, ui/screens/installments/, ExpenseScheduleLinkPolicy) with DAO, deleteExpense, recurring-store, TLC-op and PostCommitActionRunner rules and has no importer allowlist; the new InstallmentUpcomingProjection.kt matches none of its patterns, and HomeInstallmentWeatherSource.kt is outside plan code.
- Residual risk: (1) with the seam ON, Home's `combine(compiledFlow, installmentWeather)` waits for the first installmentWeather emission, so a never-completing input of the source would delay the first Ready state (the source degrades every input to an empty list on failure and the overview trigger falls back to Unit, so a hang needs a source that never emits at all); dormant today because the seam defaults to false. (2) Other Home widgets (Monte Carlo is S8b-2, runway, health score) stay on the baseline; the weather card and S8b-2 Monte Carlo apply the global exclusion independently. (3) No UI renders `installmentRows` yet. (4) OWNER item 53c (a HomeInstallmentWeatherSource consumer pin) is not implemented here.
- Validation to request: targeted-unit-test `*HomeInstallmentWeatherSource*,*InstallmentUpcomingProjection*,*HomeViewModelInstallmentWeather*,*HomeViewModelWidgets*,*HomeViewModelCurrencyReload*` (batch with S8b-2 and S8b-4), then architecture-guardian, privacy-security-guardian and reviewer-strict gates; registered guard installment_cloud_reachability expecting 0 hits; InstallmentPlanArchitectureGuardTest and CancellationSafetyArchitectureGuardTest in the guard run.

### S8b-2 review fixes + S8b-3 liveness (2026-10-28, implementer; NOT validated)
- Nothing compiled, run or validated. No schema change, no guard/allowlist/baseline change, no new catch. Cloud-surface files untouched (FinancialWeatherRepository, ForecastInputAssembler, SynthesisEngine, NarrativeGenerator, DashboardDataProvider).
- F1 (MEDIUM, money) FIXED in domain/forecasting/FinancialStressForecastEngine.kt: the expense list is no longer filtered before `runMonteCarloSimulation`. `installmentLinkedIds` is computed once (empty set when no entry is projected, so the exclusion owner is still not consulted), passed through `calculateHorizon` into `runMonteCarloSimulation`, and applied with `filterNot { it.id in installmentLinkedIds }` where `discretionaryPurchases` is built (before the recurring-merchant filter). The `purchases.isEmpty()` synthetic 20/day fallback is tested on the PRE-exclusion purchases, so a fully linked 60-day history no longer triggers the synthetic baseline. Edge: fully linked history now yields empty `discretionaryPurchases`, all-zero daily totals and p50 = 0. Unchanged for every case where some non-linked purchase remains (same min date, same samples).
- F3 tests: CashFlowCalculatorInstallmentTest AE-7 moved to the spec dates (entries 1-3 PAID 2027-04/05/06-10, today 2027-07-05, entries 4-12 CANCELLED 2027-07-10..2028-03-10, range 2027-07-05..2028-04-01, plan CANCELLED) via helper `ae7Entries`; new control `AE-7 control - the same plan still ACTIVE with the remaining entries SCHEDULED yields nine outflows of 50` (entries 4..12, each 5000 minor, last balance 550). FinancialStressForecastInstallmentTest: new `fully linked history with a projected entry gives a zero discretionary baseline and not the synthetic fallback` (F1 regression, with a no-history non-vacuity control) and `half open window - an entry due today counts and an entry due exactly at the horizon end does not` (now stubbed to midnight so the horizon end is a day boundary: +0 and +30 gives 50 / 100 / 100 for 30 / 60 / 90).
- OBSERVATION (not changed): the stress engine's window is timestamp based, `[startOfDay(now), now + N days)`. With `now` at noon (the other fixtures) an entry due on day +N falls INSIDE the N-day horizon because its day start is before now + N days. This is the pre-existing recurring-section convention that Part 3 mirrors; the CashFlow calculator is civil-day based (end day exclusive). Orchestrator to decide whether to align Part 3 to civil days (would be a behaviour change).
- F5 DONE: ENGINE_INTERACTION_MAP :36 and :146 now read "implemented: BillReminderManager (S7b), CashFlowCalculator (S8b-2), FinancialStressForecastEngine (S8b-2), HomeInstallmentWeatherSource (S8b-3)"; ForecastInputAssembler removed from row :36 (A15).
- F2 RECORDED (53a "global while projected" consequences; the INV-29 owner clarification must say 53a is GLOBAL, not per plan):
  - (a) an entry only at +60..+90 still strips the 30 and 60 day baselines; this under-counts only for overdue SCHEDULED entries (the INV-15 trade-off).
  - (b) cross-plan coupling: when the last projection ends, up to 60 days of payments re-enter the baseline (step change).
  - (c) the AE-11 stress test scope is `recurringObligations` only.
  - (d) the sampling-window anchor can shift (pre-existing).
- F4 PRE-ENABLE ITEM: CashFlowCalendarScreen (:370, :482-492) has no installment dot or row while the day totals and balances already include installments. The dot and the day-list row MUST exist before `InstallmentsFeature.ENABLED` is flipped. Totals already include installments.
- S8b-3 liveness: ui/screens/home/HomeViewModel.kt `combine(compiledFlow, installmentWeather.onStart { emit(null) })` (seam on only; seam off unchanged). A source that has not emitted yet can no longer delay Home's first Ready state. Consequence: with the seam on, the first Ready state is the BASELINE weather; the aware weather replaces it when the source emits (a brief baseline is accepted; with the SharedFlow replay the null may precede an already available value by one emission). Residual risk (1) of the S8b-3 entry above is closed.
- Test changes (HomeViewModelInstallmentWeatherTest): new `seam on and a source that never emits - the dashboard still reaches Ready with the baseline widget` (source `flow { awaitCancellation() }`; equals the seam-off state, baseline weather same instance). Because the first Ready can now be the baseline, `awaitReady`/`stateOf` take an optional `until` predicate and the replacement test `seam on and a non-null aware weather - the single weather widget is replaced and not added` waits for totalCommitted == 90.0 to avoid a race.
- Validation to request: targeted-unit-test `*FinancialStressForecast*,*CashFlowCalculator*,*HomeViewModelInstallmentWeather*,*HomeViewModelWidgets*,*HomeViewModelCurrencyReload*`; no guard-relevant production change beyond a Flow operator in HomeViewModel and a parameter in the stress engine.

### PE-A implementation (2026-10-28, implementer; NOT validated)
- STATUS: RESOLVED, see the completion section "PE-A implementation, completion" below (option (a) approved, owner-approvals item 66). Earlier status, kept for the record: STOPPED BEFORE ANY EDIT. No production, test or doc file was changed (this entry is the only change). Reason: the plan's STOP rule "an existing coordinator test changes outcome" triggers.
- Existing InstallmentPlanLifecycleCoordinatorDeclareTest cases that declare EQUAL_SPLIT (the `fx.terms()` default) with plan currency EUR on a USD purchase, which the OD-14 enforcement would refuse with CURRENCY_MISMATCH:
  - `AE5_plan_currency_may_differ_from_the_purchase_currency` (:458; USD 240 purchase, `terms(total = 224.0, count = 4, currency = "EUR")`; expects Done). Note domain-spec C-1 says AE-5 Plan C is FIXED_PAYMENT by construction; this test's EQUAL_SPLIT with total 224 is stale against that.
  - `INV18_declareWithNewExpense_passes_the_entry_zone_to_the_rate_evaluation_for_a_foreign_purchase` (:662; USD purchase, `terms(currency = "EUR")`; expects Done and one evaluator call).
  - `G2_12_rate_refusal_is_a_normal_return_with_two_audit_events_...` (:687; USD purchase, default EUR terms; expects RateRefused plus CREATE_ATTEMPTED and CREATE_VALIDATION_FAILED events). With the check in the pure-check block before TLC create, the result becomes Refused(CURRENCY_MISMATCH) and no events are written.
  - `G2_12_each_refusal_reason_maps_one_to_one` (:707; same shape, expects RateRefused x3).
- Not yet scanned: EndEdit tests (currency-change cases use seeded plans; no purchase facts check performed), PrivacyLogTest, InstallmentEditViewModelTest, DeclareInstallmentsViewModelTest.
- Decision needed from the orchestrator: (a) approve changing those four fixtures to FIXED_PAYMENT cross-currency terms (AE-5: `split = FIXED_PAYMENT, total = null, fixed = 56.0`) so rate-refusal/zone tests keep their intent, or (b) another disposition. The G2-12 tests need the rate refusal to stay reachable; with a FIXED_PAYMENT EUR plan on a USD purchase the pure check passes and TLC runs as before.

### PE-A implementation (2026-10-28, implementer; NOT validated)
Completion of the STOPPED entry above (owner-approvals item 66: option (a)). Nothing compiled or run. No schema, guard, allowlist or baseline change; no new refusal code or string; TransactionsViewModel.kt, AddExpense*, MainActivity, manifest, ReceiptScan* untouched.
- Production:
  - N `domain/installment/model/InstallmentSplitCurrencyRule.kt`: `equalSplitAllowed(planCurrency: String?, purchaseCurrency: String?)`, true only when both are non-null and equal (fail closed).
  - M `InstallmentPlanLifecycleCoordinator`: private `isEqualSplitCurrencyMismatch(terms, purchaseCurrency)` (EQUAL_SPLIT and not allowed). `declareOnExpense`: after the schedule generation, before `insertPlanWithEntries`, against `facts.currency`. `declareWithNewExpense`: in the pure-check block after generation, before `createExpenseDbOnlyV2`, against `purchaseRequest.currency`. `editDetails`: in the currency-change branch AFTER CURRENCY_LOCKED, only for PURCHASE_ATTACHED + EQUAL_SPLIT, against the purchase facts (missing purchase facts: ILLEGAL_TRANSITION, unreachable after purchaseGoneRefusal). All refuse `CURRENCY_MISMATCH`. Name/lender/category/mode edits are unaffected.
  - M `DeclareInstallmentsSheet`: `effectiveSplit` uses the rule; new `DeclareInstallmentsUiState.equalSplitAllowed`; the equal-split chip is `enabled = !running && state.equalSplitAllowed` (a null plan currency now disables it, F-2).
- DEVIATION from the plan text ("before generate"): the declare-on-expense and declare-with-new-expense checks run AFTER `generate(...)` and still before any write or TLC call. Reason: before generate, the existing test `AE22_unsupported_currency_is_refused_UNSUPPORTED_CURRENCY` (CHF equal split on a EUR purchase) would change outcome to CURRENCY_MISMATCH, which the plan's STOP rule forbids; after generate every earlier refusal keeps its precedence. No test outcome other than the four approved fixtures changes.
- Docs: domain-spec C-1 "Implemented behaviour" gets an "Enforced in the coordinator since PE-A" bullet and a past-due bullet; LEGAL_PATHS Installment Plan Mutations gets the OD-14 rule row and the past-due sentence on recordDueEntries.
- Changed existing fixtures (inputs only, no assertion weakened), all in `InstallmentPlanLifecycleCoordinatorDeclareTest`:
  - `AE5_plan_currency_may_differ_from_the_purchase_currency`: now `split = FIXED_PAYMENT, total = null, fixed = 56.0, count = 4, currency = EUR` on the USD 240 purchase; same Done, EUR plan, four 56.0 entries, flag.
  - `INV18_declareWithNewExpense_passes_the_entry_zone_to_the_rate_evaluation_for_a_foreign_purchase`: terms `fx.crossCurrencyFixedTerms()` (FIXED_PAYMENT 50.0 x 12 EUR); same Done, base conversion 300.0 at 0.5, one evaluator call with the entry zone.
  - `G2_12_rate_refusal_is_a_normal_return_with_two_audit_events_and_no_expense_plan_or_entry_rows`: terms `fx.crossCurrencyFixedTerms()`; same RateRefused(NO_RATE), CREATE_ATTEMPTED + CREATE_VALIDATION_FAILED, no rows.
  - `G2_12_each_refusal_reason_maps_one_to_one`: terms `fx.crossCurrencyFixedTerms()`; same three RateRefused reasons.
  - New shared helper `InstallmentCoordinatorFixture.crossCurrencyFixedTerms(planUuid)`. Scan result: EndEdit, PrivacyLog, LinkConfirm, Reconcile, InstallmentRecordDueEntriesTest, InstallmentRestoreReconcileTest, DeclareInstallmentsViewModelTest, InstallmentEditViewModelTest (mocked coordinator) have no EQUAL_SPLIT cross-currency declare; no change needed.
- New tests:
  - Declare: `OD14_declareOnExpense_equal_split_in_another_currency_is_refused_CURRENCY_MISMATCH_and_writes_nothing`, `OD14_declareOnExpense_euro_purchase_with_a_dollar_equal_split_is_refused_CURRENCY_MISMATCH`, `OD14_declareOnExpense_equal_split_in_the_purchase_currency_is_allowed_for_a_dollar_purchase`, `OD14_declareOnExpense_fixed_payment_may_cross_currencies_in_both_directions`, `OD14_the_mismatch_refusal_comes_after_the_schedule_checks_so_existing_refusals_keep_precedence`, `OD14_declareWithNewExpense_equal_split_in_another_currency_is_refused_before_the_TLC_create`, `OD14_declareWithNewExpense_equal_split_in_the_purchase_currency_is_not_refused`.
  - EndEdit: `OD-14 an equal split purchase plan cannot change to another currency and nothing is written`, `OD-14 CURRENCY_LOCKED keeps precedence over CURRENCY_MISMATCH once an entry is paid`, `OD-14 a fixed payment purchase plan may change currency`, `OD-14 a legacy mismatched equal split plan still accepts name and category edits and a change back to the purchase currency`.
  - N `InstallmentSplitCurrencyRuleTest` (5 tests: same, different, null fail-closed, equal unsupported code left to the schedule, exact compare).
  - DeclareInstallmentsViewModelTest: `F-2 the equal split chip is allowed only for the purchase currency`, `F-2 the equal split chip is not allowed for a null plan currency or before a target is open`.
  - N `InstallmentPlanLifecycleCoordinatorPastDueProofTest` (real Room, real TLC, real coordinator, real InstallmentPreBackupReconciler with a relaxed runner): `PE-A past-due entries stay SCHEDULED - catch-up and pre-backup reconcile record nothing before declaredOn`, `PE-A only entries with declaredOn at or before the due date and due at or before today are recorded`, `PE-A boundary - an entry due exactly on declaredOn is recorded and the one before it is not`.
- Past-due proof by reading (no money bug): the only automatic recording path is `recordDueEntries` -> `isDueAutoRecordCandidate` -> `recordDueEntry`, which re-decides inside the transaction via `InstallmentPlanTransitions.autoRecordDecision`; both use `isAutoRecordApplicable` whose clause is `dueEpochDay >= declaredOnEpochDay && dueEpochDay <= today`. `InstallmentDueRecordingWorker` makes one call to `recordDueEntries` (after `reconcile()`, which records nothing). `InstallmentPreBackupReconciler` calls `reconcile()` only; `recordEntryExpense` is reached only from `confirmEntry`, `payOff` and `recordDueEntry`. `plan.declaredOnDate` is persisted from `terms.declaredOnEpochDay`. Pre-declaredOn entries therefore never auto-record.
- Residual risk: (1) the restore path does not enforce the rule (a restored legacy mismatched plan stays as stored, per the plan). (2) Rule comparison is exact-case; all stored and request codes are upper-case ISO (the expense currency is not normalised here). (3) The Edit screen still sends the unchanged currency, so the editDetails check is defence in depth.
- Validation to request: targeted-unit-test `*InstallmentPlanLifecycleCoordinator*,*InstallmentSplitCurrencyRule*,*DeclareInstallments*,*InstallmentDueRecordingWorker*,*InstallmentPreBackupReconciler*,*InstallmentEditViewModel*,*InstallmentPlanArchitectureGuard*`; shards domain-a-m, runtime-ui, architecture-contracts at Batch 1; gates reviewer-strict, architecture-guardian. Compile risks to check first: the `Lazy { ... }` SAM in the new proof test (dagger.Lazy), `fx.evaluator` coVerify in the declareWithNewExpense test, import order in DeclareInstallmentsSheet.

### S8b-4 tests (2026-10-28, test-author; NOT validated)
- Tests only. Nothing compiled or run. No production, guard, allowlist or baseline change. Files: app/src/test/.../domain/ai/usecase/DashboardBriefingInstallmentExclusionTest.kt (rewritten, placeholders 1, 2 and 5 replaced; the cap-at-five and variant-tripwire tests kept verbatim) and app/src/test/.../data/ai/worker/DailyBriefingWorkerTest.kt (one test added).
- Test names (DashboardBriefingInstallmentExclusionTest):
  - `AE-23 briefing payload with plans present contains no plan fields display names or projected amounts` (replaces placeholder 1)
  - `AE-23 differential - same fixture with and without plans yields identical input and prompt` (replaces placeholder 2)
  - `AE-23 transaction insight input and prompt are identical with and without plans` (replaces placeholder 5)
  - kept unchanged: `upcoming items cap at five`, `UpcomingItem variants are exactly the reviewed set - a new variant needs an explicit cloud exclusion test`
- Test name (DailyBriefingWorkerTest): `INV-31 worker passes the getProcessedDataFlow output to the briefing use case unchanged` (captures the argument of the use case and assertSame against the provider's emitted instance; verifies one provider call and one use case call). Existing harness reused (Robolectric + TestListenableWorkerBuilder), so no VERIFY-AT-IMPL skip was needed.
- Chain (real unless marked): fake InstallmentProjectionPort (empty vs one ACTIVE EUR plan, entry due 2026-01-20, 9876.54, behind the REAL InstallmentProjectionRule via TestInstallmentProjectionPorts) -> HomeInstallmentWeatherSource (only port consumer; non-vacuity) | cloud side: REAL FinancialWeatherRepository + REAL ForecastInputAssembler + REAL SynthesisEngine + REAL NarrativeGenerator -> REAL DashboardDataProvider.getProcessedDataFlow -> REAL GenerateDashboardBriefingUseCase + REAL DashboardBriefingInputBuilder -> REAL CloudDashboardBriefingService request body (DefaultCloudPayloadPolicy + DefaultCloudPayloadRedactor; redactBeforeCloud false and true). Test 5: REAL GenerateTransactionInsightUseCase + REAL TransactionInsightInputBuilder -> same real cloud service.
- Held constant (A15 ISSUE-3): the two expense rows, empty link reader (link state), the recurring pattern, planned expense, budgets, goals, home currency and clock. ONLY the port output varies.
- Asserts: processed dashboard data, DashboardBriefingInput, both prompts (shouldRedact false and true), raw cloud request bodies (both redaction settings) and the stored artifact sourceHash are equal; no plan label, lender name, uuid, minor amount, decimal amount or "installment" in input, prompts or body. Non-vacuity in the same fixture: observe() is null with the empty port and non-null with the plan port (installmentRows = [entry 11], displayName and amountMinor as projected, aware totalCommitted = cloud baseline + 9876.54 and differs from it), and the cloud baseline weather equals the pre-plan baseline.
- Substitutions (narrowest possible, documented in the test KDoc): (1) repositories/DAOs under FinancialWeatherRepository, currency settings/converter/normalizer and the pattern detector are mocks with fixed synthetic data (as in HomeInstallmentWeatherSourceTest); (2) DashboardContractsAdapter replaced by a test fake implementing the Dashboard* contracts, whose observeFinancialWeather delegates to the REAL FinancialWeatherRepository; (3) HybridDashboardBriefingService is a mock forwarding the use case's exact input to the real CloudDashboardBriefingService (route selection only), whose OkHttp client is an interceptor that records the request body; (4) AiCapabilityRouter, AiSettingsRepository, AiArtifactRepository, AiPolicy, PrivacySettingsRepository mocked. FinancialWeatherRepository itself is NOT faked. InsightsEngine is a relaxed mock (DashboardDataProvider stores it but getProcessedDataFlow never calls it).
- Cloud delta: none expected and none assumed; the chain makes the port unreachable from the cloud side by construction (FinancialWeatherRepository has no port parameter). If validation shows any difference between the two runs, STOP and escalate (do not patch a builder).
- Residual weakness: test 5 and the worker test pin invariance of paths that have no plan input by construction; they cannot fail unless someone adds one. That is the intended tripwire, not new coverage of a live path.
- Compile risks to check first on the runner (nothing was compiled): the World fixture's constructor argument names (ForecastInputAssembler, HomeInstallmentWeatherSource, DashboardDataProvider, GenerateTransactionInsightUseCase), `okio.Buffer` on the unit-test classpath, mockk of the final AiPolicy/PrivacySettingsRepository, and the real FinancialWeather/ProcessedDashboardData data-class equality across two worlds.
- A15 closure: ISSUE-3 (differential definition) and ISSUE-4 (placeholders 1, 2 and 5 replaced by the end-to-end differential) are CLOSED by these tests once they pass; the DailyBriefingWorker unchanged-pass-through leg of the A15 chain is covered. ISSUE-2 (guard strengthening) is DONE by S8b-1 (6484e54e; owner-approvals 59). Still open for the S8b gate: privacy-security-guardian review (mandatory; done, see the S8b-3/4 privacy fixes section below) and the owner-approved DashboardContractsAdapter surface entry (59a).
- Validation to request: targeted-unit-test `*DashboardBriefingInstallmentExclusion*,*DailyBriefingWorker*` (batch with the S8b-2/3 filters; add `*HomeInstallmentWeatherSource*,*DashboardBriefing*,*GenerateTransactionInsight*,*CloudDashboardBriefingService*` to catch fixture drift).

### S8b-3/4 privacy fixes + DashboardContractsAdapter surface entry (2026-10-28, implementer; NOT validated)
Source: privacy-security-guardian findings F1..F7 on S8b-3/4 (ACCEPT-WITH-CONDITIONS). Nothing compiled or run. The earlier combined targeted run (vr-20261027-215544-7398fd33) PASSED BEFORE these edits, so it is stale for the touched files.
- F1(a)/F3: DashboardBriefingInstallmentExclusionTest World now builds the REAL `DashboardContractsAdapter` (all 10 ctor deps) around the same mocks; `FixtureDashboardContracts` is deleted. New relaxed mocks: CategoryRepository (`allCategories` = empty), ReviewQueueRepository (`getPendingReviewCount` = 2), AnalyticsRepository (`getSpendingSummary`, `getCategoryBreakdown` stubbed with the old fixture values 140.0 / Food), TimeBoundaryTicker (`dayBoundaryTicks` = fixture now). Also stubbed: `ExpenseRepository.getExpensesWithCategoryInPeriod` = empty, `MergedRecurringPatternsProvider.getConfirmedPatterns` = empty. The adapter now maps the Home-side planned expense ("Synthetic laptop") into processed `data.plannedExpenses`; it is identical in both worlds. KDoc substitution list updated (the KDoc claim of F3 is gone with the fake). The AnalyticsRepository mock is shared with FinancialWeatherRepository (which only stores it). Compile/behaviour risks for the runner: AnalyticsSpendingSummary alias import, `AnalyticsCategoryBreakdown` ctor, relaxed mock of the final TimeBoundaryTicker.
- F2: one shared `NarrativeGenerator` in World for the baseline FinancialWeatherRepository and HomeInstallmentWeatherSource.
- F5: HomeViewModel `installmentWeather` flatMapLatest now uses `homeInstallmentWeatherSource.observe().onStart { emit(null) }` (stale aware value, e.g. old currency, is not replayed after a reload). HomeViewModel tests may need a look if one asserts the very first aware emission without a null before it.
- F6 docs: LEGAL_PATHS (Monte Carlo is installment-aware on Home since S8b-2; runway and health score stay baseline), ENGINE_INTERACTION_MAP line 36 (HomeInstallmentWeatherSource to HomeViewModel weather-widget replace, never UpcomingItems), plan S8b amendment note (Q2 "Monte Carlo stays installment-free" superseded for the Home stress engine). The substitution (2) description in the S8b-4 section above ("DashboardContractsAdapter replaced by a test fake") is superseded: the real adapter is used now.
- F7: the A15 closure line above is corrected (ISSUE-2 guard strengthening done by S8b-1 6484e54e, owner-approvals 59).
- F1(b) / owner-approvals 59a (guard strengthening): `data/repository/DashboardContractsAdapter.kt` is the 8th exact-file entry of installment_cloud_reachability (grep for "installment" in the file: 0 hits before the change). guard_registry.py and run_static_guard_suite.py untouched; no allowlist, no baseline. Item 53c (pin HomeInstallmentWeatherSource to HomeViewModel and DI) remains an open OWNER item.
- Pin changes (old to new), all in scripts/test_verify_installment_cloud_reachability.py unless noted:
  - `SURFACE_EXACT_FILE_SUFFIXES` (verify_installment_cloud_reachability.py): 7 entries to 8 (appended `data/repository/DashboardContractsAdapter.kt`); surface entries 11 to 12; docstring "exact files (7 entries)" to 8 and scope note "7 exact-file entries" to 8.
  - `_SURFACE_SEEDS` 11 to 12 entries; `_SEED_COUNT` 11 to 12 (and the derived `_assert_clean` default `_SEED_COUNT + 1`).
  - New `_ADAPTER_ENTRIES` (1) and `_CHAIN_ENTRIES = _S8B1_ENTRIES + _ADAPTER_ENTRIES` (5 to 6); `_S8B1_ENTRIES` unchanged (5).
  - `test_surface_definition_constants`: exact tuple 7 to 8 entries; `_S8B1_ENTRIES == [2:]` to `_CHAIN_ENTRIES == [2:]`.
  - `test_surface_entries_under_test_are_exactly_the_eleven_documented_entries` renamed `..._twelve_...`: `len(SURFACE_EXACT_FILE_SUFFIXES)` 7 to 8, `len(_ALL_SURFACE_ENTRIES)` 11 to 12, `_SEED_COUNT` 11 to 12. `test_surface_all_eleven_entries_seeded_is_the_control_and_passes` renamed `..._twelve_...`.
  - Parametrizations over `_S8B1_ENTRIES` to `_CHAIN_ENTRIES` (5 to 6 cases each): `test_negative_s8b1_entry_flags_projection_port_import_and_field` (import plus field gives exit 1 with G-01/G-03), `test_positive_s8b1_suffix_precision_look_alike_file_names_are_not_on_the_surface`, `test_positive_s8b1_suffix_precision_look_alike_directory_is_not_on_the_surface`, `test_infra_s8b1_entry_file_missing_exits_two_naming_that_entry` (unmatched control, exit 2 naming the entry). The all-entries parametrizations over `_ALL_SURFACE_ENTRIES` grow 11 to 12 cases automatically.
  - `test_matched_surface_entries_reports_only_directory_and_exact_entries`: +3 rows (adapter matches `data/repository/DashboardContractsAdapter.kt`; `XDashboardContractsAdapter.kt` and `DashboardContractsAdapterFoo.kt` match nothing).
  - Docs: LEGAL_PATHS surface sentence (7 to 8 exact files), CODEBASE_SEGMENTS segment 39 line (7 to 8), ENGINE_INTERACTION_MAP fence lines (7 to 8, 12 entries), docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md row (7 to 8).
- Validation to request (serial): the guard self-tests (`python -m pytest scripts/test_verify_installment_cloud_reachability.py`, via the runner's installment_cloud_reachability guard profile), then targeted-unit-test `*DashboardBriefingInstallmentExclusion*,*HomeViewModel*,*HomeInstallmentWeatherSource*,*DashboardContractsAdapter*,*DashboardDataProvider*`. Run the REAL guard on the repo tree (the adapter has 0 "installment" hits, so it must pass). Then the S8b candidate static-guards and app-check as planned.

### Owner 59b/c/d: Home weather consumer pin, post-commit wrapper rule, F5 wording (2026-10-28, implementer; NOT validated)
Source: owner-approvals items 59b (closes the open item 53c), 59c (extends item 48), 59d (PE-F5). Nothing compiled or run.
- 59b: new rule `HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME` in `InstallmentPlanArchitectureGuardTest` (`homeWeatherSourceReferences`): over ALL of app/src/main, `HomeInstallmentWeatherSource*` may appear in code only in `ui/screens/home/HomeViewModel.kt` and `data/repository/HomeInstallmentWeatherSource.kt` (exact paths). Grep before: main references are only those two files; no DI module binds it (`@Inject` ctor). Tests: live scan, planted self-check (flagged in data/ai, domain/ai, DashboardContractsAdapter, ui/screens/assistant, di, sibling and same-basename paths; clean in the two exempt files and for comments/KDoc/strings), non-vacuity (HomeViewModel really references it; real texts at a renamed path are flagged).
- 59c: `postCommitRunnerReferences` (rule id `POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER` unchanged) now also flags `PostCommitSideEffectEvidenceService*`, `TransactionSideEffectDispatcher*`, `ReceiptSideEffectDispatcher*`, `runWithEvidence`, `runBestEffortWithEvidence` under domain/installment, service/installment, ui/screens/installments; only the dispatcher's exact path is exempt. Grep before: none of these names appear in the three prefixes (only the dispatcher names PostCommitActionRunner). No allowlist. Self-check extended (planted wrapper refs flagged on 7 installment paths, dispatcher clean, benign comment/string/planner/lookalike clean), new non-vacuity test pins the three wrapper declarations and the two service functions and re-flags their real text at a renamed installment path. KDoc "Known limits" updated; the wrapper bullet is gone. New constants `POST_COMMIT_WRAPPER_TYPES`, `HOME_VIEW_MODEL_PATH`, `HOME_WEATHER_SOURCE_PATH`.
- 59d: `transactions_installment_purchase_locked` is now field-neutral. en "This purchase is paid in installments, so this can't be changed here."; es "Esta compra se paga a plazos, así que esto no se puede cambiar aquí."; el "Αυτή η αγορά πληρώνεται σε δόσεις, οπότε αυτό δεν μπορεί να αλλάξει εδώ." es/el carry `<!-- DRAFT: owner native sign-off pending -->`. Key, call sites and tests unchanged (TransactionsViewModelTest asserts the resource id only; no test asserts the EN literal). `ux-flows.md` has "Clarification 2026-10-28 — F5 wording (owner-approvals 59d)" superseding U-3's short text.
- Docs: LEGAL_PATHS Home weather read row (enforced by HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME) and POST-COMMIT DISPATCH OWNER enforced-by line (wrappers listed).
- Validation to request: targeted-unit-test `*InstallmentPlanArchitectureGuard*,*TransactionsViewModelTest*`. No guard/script/allowlist/baseline change, so no static-guards run is needed for this batch.

### S8b candidate strict-review fixes (2026-10-28, implementer; NOT validated)
Source: reviewer-strict findings on the S8b candidate, decisions in owner-approvals item 60. Nothing compiled or run.
- Item 60(b) BLOCKER (money): `HomeInstallmentWeatherSource.compute` applies `RecurringInstallmentEligibility.excludedSnapshotIds` ONLY to snapshots dated before `TimePeriodUtils.getStartOfMonth(now)` (system zone, the same zone the source already uses). Those are the baselines (average month, previous month, typical daily discretionary). Current-month actual installment payments stay in month-to-date, so a paid installment is never counted zero times. Accepted trade-off: the pace ratio compares a current month that includes installments against a baseline without them. Docs wording fixed in `domain-spec.md` (Home weather section) and `LEGAL_PATHS.md` (Home weather read row).
- Tests (`HomeInstallmentWeatherSourceTest`): the fake detector now ignores linked/plan-purchase ids (production parity, RecurringExpenseEngine excludes linked) and records the ids it is handed; INV-29 test rewritten (month-to-date 140, committed 40 + 50); new `INV-29 linked and plan purchase history before the current month leaves the aware baselines but stays in the current month`; new `monthly plan due on the 10th with today the 15th counts the paid entry once in month-to-date and the next entry next month`; plan-purchase test renamed `INV-29 plan purchase records are excluded from patterns and committed in the aware view`.
- Finding 2 (stale replay): `HomeViewModel.installmentWeather` now emits `GenerationalInstallmentWeather(generation, value)` tagged with the reload-trigger value, and `processedDataFlow` filters on `generation` in the combine, so a replay=1 value of an older generation (for example the old home currency) is never combined with a new baseline. `replayExpirationMillis = 0` was NOT used: it only expires the replay after the upstream stops, not across a reload while subscribed. `installmentRows` reads `.value`.
- Finding 3 (catch placement): the `.catch` moved inside the `flatMapLatest` lambda (around the source flow), as `processedDataFlow` does, so one failure ends only that generation's inner flow and a later reload resumes the aware weather. No try/catch added.
- Finding 4 (vacuous tests): the empty-port, silent-source and failing-source tests now `verify(atLeast = 1) { weatherSource.observe() }` and compare the SETTLED state (`settledStateOf`, which waits until the source has run, then a short real-time settle). New `a reload never combines the old generation aware weather with the new baseline` (currency change) and `a failure of the aware source keeps the baseline and a later reload resumes the aware weather` in `HomeViewModelInstallmentWeatherTest`.
- Finding 5: `FACTS_READER_IMPL_PATH = "..."` spacing restored in `InstallmentPlanArchitectureGuardTest`.
- Finding 6: values-es `transactions_installment_purchase_locked` DRAFT now reads "Esta compra se paga en cuotas, así que esto no se puede cambiar aquí." (matches the neighbouring strings; DRAFT comment kept; owner es sign-off still pending). Supersedes the "a plazos" text in the 59d note above.
- Item 60(a): the Home stress Monte Carlo is installment-aware (S8b-2, Home-only), superseding 53(b) and plan Q2 for the stress engine; runway and health score stay on the baseline. 60(c): the stress horizon keeps the recurring half-open convention [startOfDay(now), now + N days).
- PRE-ENABLE items (must be resolved or accepted before `InstallmentsFeature.ENABLED` is turned on):
  1. An early bank charge, held unlinked by the INV-13 deferral, can be counted as both an actual and a projected entry until it is linked.
  2. The narrative fallback label "Installment" in `HomeInstallmentWeatherSource` is English-only (redacted restore with an empty name); needs a localized label or a different fallback.
  3. `observeOverview()` is used as the invalidation trigger and costs performance on every plan/entry row change; replace it with a cheaper dedicated trigger before enabling.
- Follow-up (not done here, guard/registry edits are stop-and-ask): the `guard_registry` description of the affected installment guards (`installment_cloud_reachability` / the Home weather consumer pin) should be updated by the orchestrator to match the S8b surface.
- Validation to request: targeted-unit-test `*HomeInstallmentWeatherSource*,*HomeViewModelInstallmentWeather*,*HomeViewModel*,*InstallmentPlanArchitectureGuard*,*TransactionsViewModelTest*`; then the S8b candidate shard/app-check as planned.

### S8b ACCEPTED (2026-10-28), candidate S8b-1..4
- Gates:
  - architecture-guardian (S8b-2) ACCEPT-WITH-CONDITIONS; F1 money fix applied.
  - privacy-security-guardian (S8b-3/4) ACCEPT-WITH-CONDITIONS; F1(a), F2 and F5-F7 applied, and F1(b) closed by owner-approvals 59a.
  - reviewer-strict (candidate) ACCEPT-WITH-CONDITIONS; blocker 1 (current-month actuals) and 2-6 and 9-10 applied; 7, 8 and 12 recorded as PRE-ENABLE items (item 60).
  - Owner guard additions 59a-c and the 59d wording are included.
- Validation, final tree, all PASS:
  - targeted vr-20261027-225041-ec357ec5
  - pytest cloud-reachability vr-20261027-225645-25a96db1
  - installment_cloud_reachability vr-20261027-225703-837378f3
  - domain-a-m vr-20261027-225719-ffeee4b2
  - data vr-20261027-225850-0f755bca
  - runtime-ui vr-20261027-230307-edbc0bbd
  - integration-golden vr-20261027-230439-69f40847
  - static-guards vr-20261027-230542-03e332ea
  - app-check vr-20261027-233611-345840a2
- Committed. The loan lane now waits on S11 (after nl-quick-add increment 1 and MEC B1/B2/C) and S13 (enablement plus the pre-enable list).

### PE-B implementation (2026-10-28, implementer; NOT validated)
Plan-detail Unlink entry point, unrestricted (owner-approvals 56). Nothing compiled or run. No schema, DAO, TLC, guard, allowlist or baseline change; no new string key (the existing `installments_unlink_confirm` = "Unlink" is the menu label, so values-es/el are untouched); PE-A files untouched.
- Production:
  - M `domain/installment/model/InstallmentPlanTransitions.kt`: new pure `isUnlinkable(entryStatus, entryKind, planStatus)` (PAID + REGULAR + plan ACTIVE or COMPLETED). The UNLINK branch calls it (`SCHEDULED -> AlreadyInState` stays first). Behaviour identical; no table outcome changed.
  - M `ui/screens/installments/detail/InstallmentPlanDetailViewModel.kt`: `InstallmentEntryRowUi.canUnlink: Boolean = false`, set in `row()` via `InstallmentPlanTransitions.isUnlinkable(entry.status, entry.kind, planStatus)`.
  - M `ui/screens/installments/actions/InstallmentEntryActionSheets.kt` (declares `InstallmentEntryActionMenuSheet`): optional `canUnlink` + `onUnlink` params, an Unlink item after the action items. Unlink is deliberately NOT an `InstallmentEntryActionKind` (it runs through the plan actions ViewModel).
  - M `ui/screens/installments/detail/InstallmentPlanDetailScreen.kt`: the menu opens when `availableActions.isNotEmpty() || canUnlink`; Unlink calls `planActionsViewModel.openUnlink(loaded.toActionContext(), entryId, seq)` (the existing public `toActionContext()` extension). DEVIATION (needed, not in the plan text): `EntryRow` made clickable also when `canUnlink`, otherwise a PAID row (no entry actions) could not open the menu.
  - M `InstallmentPlanActions.kt`: class KDoc and `openUnlink` KDoc say "unrestricted (owner-approvals 56)". `InstallmentPlanActionContext` builder was already public; no change.
- No DAO, TLC, `deleteExpense(` or expense repository reference was added to installment UI; the UI reads only the pure domain model object. No link-origin read model was needed (rule 4: unrestricted).
- Tests:
  - `InstallmentPlanDetailViewModelTest`: `PE-B canUnlink is true only for a PAID REGULAR entry of an ACTIVE or COMPLETED plan over the full product` (6 plan statuses x 2 kinds x 6 entry statuses, expects exactly 2 offered combinations), `PE-B Unlink is independent of the row actions and of suggestions`.
  - `InstallmentPlanTransitionsTest`: `isUnlinkable_equals_the_UNLINK_transition_is_a_Move_over_the_full_status_kind_plan_product` (parity with `entryTransition(UNLINK)`, exactly 2 Moves).
  - `InstallmentPlanActionsViewModelTest`: `PE-B openUnlink opens the UNLINK dialog and confirm reaches unlinkExpense once with the batch dispatched`.
  - `InstallmentPlanLifecycleCoordinatorLinkConfirmTest`: `PEB_owner56_unlinking_an_auto_recorded_due_entry_keeps_the_expense_and_the_catch_up_records_no_second_one`, `PEB_owner56_a_manual_record_after_unlink_creates_a_second_expense_under_the_next_generation_key`.
- Owner-56 characterization (by reading the code; the tests pin it): every claim (AUTO_RECORDED, USER_CONFIRMED, USER_LINKED) latches `autoRecordConsumed` (`claimEntry`, markAutoRecordConsumed = true), and `releaseEntryLink` does not reset it (it clears the link and origin and bumps `recordGeneration` only). So after Unlink of a PAID entry of an AUTO_RECORD plan with due <= today, the catch-up (`recordDueEntries`) records NO second expense (the entry is `isAutoRecordApplicable == false`: consumed), now or on a later day. The expense stays and keeps counting (spending in its month unchanged) while the entry is SCHEDULED, generation 1. A later MANUAL Record (`confirmEntry`) creates a second expense under the key `installment:<uuid>:REGULAR:<seq>:1` (new generation), so the month then counts both (double count until the user deletes one). This MATCHES ux-flows U-1 rule 5 ("a later Record creates a second expense (new generation key)"; the consequence-first dialog is the only mitigation) and spec 7.6. No contradiction, no STOP. Notes for the review: (1) the existing Unlink dialog body states "The expense stays in your list and still counts as spending. The installment becomes unpaid. If the payment did not happen, delete the expense in Transactions instead." but does NOT carry the 3.6 line "This creates a new expense." for a later re-record (ux-flows 3.6 :729 says a warning applies when the installment is later recorded again; that is the Record dialog's job, not checked here). (2) S13 re-checks (plan item 5).
- Residual risk: (1) canUnlink mirrors the table only; the coordinator still refuses a purchase-gone PURCHASE_ATTACHED plan (`purchaseGoneRefusal`), so the dialog would show a fixed refusal there. (2) The menu label reuses the confirm-button key. (3) Nothing compiled: check first the `toActionContext` import in the detail screen and the new params of `InstallmentEntryActionMenuSheet`.
- Validation to request: targeted-unit-test `*InstallmentPlanDetailViewModel*,*InstallmentPlanActionsViewModel*,*InstallmentPlanTransitions*,*InstallmentPlanLifecycleCoordinatorLinkConfirm*,*InstallmentPlanArchitectureGuard*`; gates architecture-guardian; reviewer-strict at Batch 1.
- 2026-10-28 Batch 1 targeted FAILED (vr-20261028-020916-423b3e9b): MockK matcher on an inline value class in OD14_declareWithNewExpense...; test assertion rewritten (no production change).

### PE-A + PE-B ACCEPTED (2026-10-28), loan pre-enable Batch 1
- Gate: reviewer-strict (combined architecture + strict) ACCEPT-WITH-CONDITIONS. Finding 1 decided as item 68 and carried into PE-D's strings pass; findings 2-4 (docs) carried into PE-D.
- Validation, final tree, all PASS:
  - targeted vr-20261028-022207-fd49913f
  - domain-a-m vr-20261028-022438-83774f6d
  - runtime-ui vr-20261028-022554-712017cc
  - architecture-contracts vr-20261028-022711-789d74fe
  - earlier fail: vr-20261028-020916-423b3e9b (MockK matcher on a value class; test-only fix)
- Committed.
- Next: PE-C (read-path triggers, Home fallback, CashFlow dot and row), then PE-D.

### PE-C implementation (2026-10-28, implementer; NOT validated)
Read-path triggers and performance, Home fallback label, CashFlow dot and row. Nothing compiled or run. Two implementer runs: the first died on an API timeout mid-way (it had finished the production files, the strings and two test files); the second verified them against the spec and completed the rest. No schema, DAO, TLC, guard, allowlist or baseline change. No cloud-surface file (`FinancialWeatherRepository`, `ForecastInputAssembler`, `SynthesisEngine`, `NarrativeGenerator`, `DashboardDataProvider`, `DashboardContractsAdapter`) and no `TransactionsViewModel.kt` edit; the `installment_cloud_reachability` scanned set is unchanged.
- Production:
  - M `domain/installment/InstallmentPlanReadRepository.kt`: `observeChanges(): Flow<Unit>` with a default `observeOverview().map { }`.
  - M `data/repository/InstallmentPlanReadRepositoryImpl.kt`: `override fun observeChanges() = changes()` (the existing cheap private `combine(observeAllPlans, observeAllEntries).conflate()`; no load, no new query).
  - M `data/repository/HomeInstallmentWeatherSource.kt`: the 6th input is `observeChanges()` (with `.catch { emit(Unit) }`: a trigger failure must not stop the weather); new ctor param `@ApplicationContext context: Context` (last); the fallback label `context.getString(R.string.installment_plan_fallback_label)` is resolved inside `compute()` on every computation and replaces the hard-coded English "Installment" for a blank plan name.
  - M `ui/screens/budget/BudgetViewModel.kt`: `installmentInputs` flow: flag off emits `null` and never touches the repository (not even `observeChanges`); flag on = `observeChanges().mapLatest { loadInstallmentInputs() }`, with `.catch` rethrowing `CancellationException` and emitting `Failed` otherwise. `loadInstallmentInputs()` (internal) rethrows CE and maps any other exception to `Failed`. `installmentLinesFor(statuses, loaded)` is now a NON-suspend pure function (`null` -> no lines, `Failed` -> `Unavailable` for every budget, `Loaded` -> the unchanged S12b/50a rules). Statuses and inputs are joined with `combine`, so a statuses-only emission recomputes lines from the cached inputs and reloads nothing. Public ctor unchanged.
  - M `ui/screens/cashflow/CashFlowCalendarScreen.kt`: a tertiary-colour dot when `projectedInstallments.isNotEmpty()`; a day-list section (header `cashflow_installments_header`) from the pure `internal fun formatCashFlowInstallmentLines(entries, fallbackName)` (`take(3)`, "• name: -amount", plan currency via `CurrencyFormatter.formatMoney(InstallmentMinorUnits.fromMinor(amountMinor), currency.code)`, never converted, not clickable, no navigation).
- Strings (values, values-es, values-el): `installment_plan_fallback_label` (EN "Installment", es "Cuota", el "Δόση") and `cashflow_installments_header` (EN "Installments due", es "Cuotas a pagar", el "Δόσεις προς πληρωμή"). es/el carry the `DRAFT: owner native sign-off pending` comment (owner sign-off still pending).
- Fallback-label language decision: the label follows the CURRENT app locale because it is resolved from the application context on every computation (no cached string). It is only the description of the internal MUST planned input; the Home narrative (`NarrativeGenerator`) is English-only and does not display it, so nothing user-visible was added to the English-only narrative. The CashFlow section reuses the same key for a blank name (resolved with `stringResource` in the composable).
- Docs: `LEGAL_PATHS.md` Home weather read row (invalidation = `observeChanges`, fallback key); `domain-spec.md` append-only "Clarification 2026-10-28 — early bank charge over-count accepted for v1 (owner-approvals 65a)".
- Tests:
  - `InstallmentPlanReadRepositoryImplTest`: `PE-C observeChanges emits on a plan change and on an entry change and loads nothing` (first run).
  - `HomeInstallmentWeatherSourceTest` (first run): `PE-C a change emission re-runs the pipeline so an entry status change is picked up`, `PE-C a failing change trigger still lets the weather compute once`, `PE-C a blank plan name gets the localized fallback label as the planned description`, `PE-C a named plan keeps its own name and the label is resolved per computation`; ctor now takes a mocked `Context`.
  - `BudgetViewModelInstallmentLineTest` (updated): the CE test now calls `loadInstallmentInputs()`; new `a non-cancellation failure of loadBudgetInputs is mapped to Failed`, `PE-C a statuses-only emission does not reload the installment inputs`, `PE-C an installment change reloads the inputs and updates the lines`, `PE-C a failing change flow is unavailable for every budget never zero`, `PE-C the pure line function gives the same lines as the ui state on the same inputs`; the flag-off test also verifies `observeChanges()` is never called. All pre-existing line-value assertions are unchanged.
  - N `ui/screens/cashflow/CashFlowCalendarInstallmentRowsTest`: dot condition, plan-currency formatting (EUR and USD, no conversion), fallback name, named plan, `take(3)`, mixed currencies stay separate.
  - `DashboardBriefingInstallmentExclusionTest`: World ctor only (mocked `Context`, `observeChanges()` stub); cloud equality assertions untouched.
- Residual risk: (1) the dot itself is a Compose-only condition (checked by the data-level test and a device look); (2) `installmentInputs` ends after a failed change flow (a later change in the same collection does not retry until re-subscription); (3) nothing compiled: check first the new `Context` ctor param of `HomeInstallmentWeatherSource` at any Hilt/other construction site and the `CurrencyFormatter` output in the CashFlow test.
- Validation to request: targeted-unit-test `*InstallmentPlanReadRepositoryImpl*,*HomeInstallmentWeatherSource*,*HomeViewModelInstallmentWeather*,*HomeViewModel*,*DashboardBriefingInstallmentExclusion*,*BudgetViewModel*,*CashFlowCalendar*,*CashFlowCalculatorInstallment*`; shards data and runtime-ui; installment_cloud_reachability; lint; gates privacy-security-guardian, architecture-guardian, reviewer-strict.

### PE-D implementation (2026-10-28, implementer; NOT validated)
Transactions delete-dialog role lines, retry-loop key, marks-loader memoise, plus the PE-A/PE-B carry-overs (owner-approvals 68). Nothing compiled or run. No schema, DAO, TLC, guard, allowlist or baseline change (the registry edit is description-only, item 17 / 65b). `TransactionsViewModel.kt` and the PE-C files were not touched; the delete path (`viewModel.deleteExpense`) is unchanged.
- Production:
  - M `ui/screens/transactions/TransactionsInstallmentViewModel.kt`: `InstallmentRowMarksLoader.load` now makes ONE `readRepository.loadBudgetInputs()` read (every readable plan of any status with all its entries, carrying plan id, currency, purchase expense id and per entry seq, kind, status, linked expense id, due day, scheduled minor, which is everything the marks need) plus `loadSuggestions()`. The former `loadOverview` + `loadHistory` + N x `loadDetail` are gone (ACTIVE plus the history statuses = every status, so the plan set is the same). Entries are sorted (due day, seq, entry id) as `detail()` did, so first-match rules are stable. Fail-closed null, class-only log and the CE rethrow are unchanged. New pure `TransactionsLoadMorePolicy.effectKey(installmentFilterOn, baseRowCount, hasReachedEnd, isLoading)`: filter off = the constant `[-1]`; filter on = `[baseRowCount, hasReachedEnd, isLoading]` (never isLoadingMore).
  - M `ui/screens/transactions/TransactionsInstallmentUi.kt`: `enum InstallmentDeleteRole { PURCHASE, INSTALLMENT, REVERSED }` with `of(expense, marks)` (PurchaseRecord mark -> PURCHASE; Payment mark -> REVERSED if reversed else INSTALLMENT, a PAYOFF payment counts as INSTALLMENT; no mark -> the stored flag INSTALLMENT_PURCHASE -> PURCHASE, INSTALLMENT_REVERSED -> REVERSED; else null) and `messageRes()`.
  - M `ui/screens/transactions/TransactionsScreen.kt`: the load-more effect is `LaunchedEffect(effectKey, if (filterOn) false else shouldLoadMore)`. Filter off: unchanged behaviour (`if (shouldLoadMore) loadMore()`, key part -1). Filter on: `snapshotFlow { shouldLoadMore }.first { it }` once, then `loadMore()` once; a failed page leaves the key unchanged, so the effect is not relaunched (no retry loop). `DeleteConfirmationDialog` gains `installmentRole` and `onViewPlan`: the role line (bodySmall) under the existing text and a neutral "View plan" TextButton (reuses `declare_installments_snackbar_view_plan`). The role is computed only when `installmentVm != null` (gate `InstallmentsFeature.ENABLED` at :141), so it is dormant otherwise. View plan needs a mark (plan id); with only the flag fallback the line shows without the button. Navigating dismisses the dialog (`expenseToDelete = null`) and calls the existing `onOpenInstallmentPlan`.
  - M `ui/screens/installments/actions/InstallmentPlanActions.kt` (KDoc only): "restricted entry Unlink" becomes "unrestricted entry Unlink, owner-approvals 56".
- Strings (values, values-es, values-el): `transactions_installment_delete_role_installment|purchase|reversed` (EN verbatim from ux-flows :822-826; es/el with the DRAFT comment); `installments_unlink_body` gets the sentence "Recording this installment again later creates a new expense." (es/el translated, DRAFT comment above the entry). Owner es/el sign-off still pending.
- Docs: ux-flows U-1 table row (:1423) corrected to cite item 68 (the Unlink dialog text is NOT unchanged), copy table :1269 updated, "Not built yet" (:1459-1461) is now "Pre-enable items": Unlink entry point BUILT by PE-B, delete-dialog role lines BUILT by PE-D; domain-spec C-1 evidence bullets "ONE owner today" and "coordinator does NOT enforce" marked "(superseded by PE-A below)"; `scripts/ci/guard_registry.py` `installment_cloud_reachability` human-readable `description` only (names the S8b chain files, DashboardContractsAdapter and the Home weather consumer pin; no other field).
- Tests:
  - `TransactionsLoadMorePolicyTest`: the 32-combination filter-off table is untouched; new `effectKey filter off is the constant whatever the paging state`, `effectKey filter on is stable across a failed page`, `effectKey filter on changes after a successful page, the end, or a refresh`.
  - `TransactionsViewModelInstallmentTest`: fixtures moved from overview/history/detail mocks to `PlanSnapshot` + `loadBudgetInputs()` (marks on the existing fixtures unchanged); new `PE-D the loader makes one snapshot read, one suggestions read and no per-plan detail read` (strict mock, `loadDetail` 0 calls) and `PE-D plans of every status are marked ...`; the failure test still asserts null/EMPTY with the class-only log, the CE test now throws from `loadBudgetInputs`; the dormant test also verifies `loadBudgetInputs`/`loadDetail` are never called.
  - N `TransactionsInstallmentDeleteRoleTest` (9 tests: purchase, installment, PAYOFF, reversed, mark over flag, flag fallback, ordinary expense null, SCHEDULED/CANCELLED link gives no role, message mapping).
  - `InstallmentPlanLifecycleCoordinatorLinkConfirmTest.PED_item68_after_unlink_the_old_expense_is_a_link_suggestion_for_the_reopened_entry`: through the candidate scan (`InstallmentCandidateChargeProbeImpl.collectCandidates`, the scan the read model uses for suggestions): none while linked, the old expense is the candidate after Unlink.
- Not done: the optional AE5-fixture `splitMethod == FIXED_PAYMENT` assertion (PE-A test file untouched).
- Residual risk: (1) nothing compiled: check `snapshotFlow`/`first` in the Compose effect, `internal collectCandidates` access from the coordinator test, and the strict `mockk<InstallmentPlanReadRepository>` in the loader tests; (2) the Compose-only behaviour (single loadMore per key, dialog layout, View plan button) needs the item-14 device check; (3) `loadBudgetInputs` loads each paid entry's linked expense money (a heavier per-plan read than the old overview), but it is one pass instead of overview + history + N details; (4) a hypothetical future change that lets the flag fallback appear without a mark leaves View plan hidden by design.
- Validation to request: targeted-unit-test `*TransactionsLoadMorePolicy*,*TransactionsViewModelInstallment*,*TransactionsInstallment*,*TransactionsViewModelTest*,*InstallmentPlanLifecycleCoordinatorLinkConfirm*,*InstallmentCandidateChargeProbe*`; shard runtime-ui; lint (new strings, es/el parity); at the Batch 2 candidate guard_docs_truth (registry description) and installment_cloud_reachability; gates privacy-security-guardian, cheap review, reviewer-strict at Batch 2.
- 2026-10-28 Batch 2 targeted vr-20261028-042416-8896a042 FAILED compile: missing imports in HomeInstallmentWeatherSourceTest (Context, R, verify, flow); test-only fix.
- 2026-10-28 Batch 2 targeted vr-20261028-044047-22d25c93 FAILED: fallback-label test stub consumed by the named-plan computation (label resolved per computation by design); test-only fix.

### PE-C + PE-D ACCEPTED (2026-10-28), loan pre-enable Batch 2 (pre-enable batch complete)
- Gate: reviewer-strict (combined strict + privacy + architecture) ACCEPT-WITH-CONDITIONS; no code changes required.
- Minor follow-ups recorded:
  - marks-loader plan order on duplicate-hint ties;
  - Budget stays Unavailable after a change-flow failure until the VM is recreated;
  - the Compose paging effect is checked at the item-14 device check.
- Validation, final tree, all PASS:
  - targeted vr-20261028-051635-f957bab6
  - data vr-20261028-051906-365464c7
  - lint vr-20261028-052238-f78988b6
  - static-guards vr-20261028-052511-074d79b3
  - app-check vr-20261028-054100-2d5b4bdc
  - earlier: runtime-ui vr-20261028-044720-58464a81; installment_cloud_reachability vr-20261028-042907-5c39164d; guard_docs_truth vr-20261028-042925-42d5d650
- Committed.
- MEC note (nl-quick-add final review N4): when MEC B1 lands, AddExpenseViewModel.mapSaveError needs a branch for RECURRING_FOREIGN_CURRENCY_ERROR_CODE, or slice A's generic mapping hides the bounded error.
- The loan lane now waits on MERGES:
  - nl-quick-add and app-lock S20/S21 to the integration line;
  - then MEC B1/B2/C, loan S11 and S13 (S13-a deep-link confirmation coordinated with app-lock S21);
  - plus the item-14 device G-check.

## Merge of feature/ai-evolution 4add2466 (2026-10-28; NOT validated)
- Lane Cost-agregator-mec (feature/manual-entry-currency) merged origin/feature/ai-evolution 4add2466 (merge-base bcd8e921) with `--no-ff --no-commit`; the plan is `workflows/active/mec-merge-ai-evolution-plan.md`. Resolved by the implementer without builds, tests or guards; the orchestrator stages and validates. Nothing is committed or validated yet.
- Room: only this lane bumped the version (v152, `152.json` unchanged). int stayed at v151. No schema change from the merge.
- Ownership policy: 454 entries (loan 452 plus the ai-fixes net +2). The Kotlin pin (`DbGuardPolicyFixtureTest`) and both Python pins (`scripts/test_verify_db_access_boundaries.py`) are 454. Unions, not weakenings.
- D-M1 = A (owner-approvals 79a): `BillReminderWorker` installment reminders (S7b) now use the BILL presentation. `presentationResolver.resolve(NotificationKind.BILL, Build.VERSION.SDK_INT)` runs after `buildInstallmentReminderText` inside the claim-to-post try (cancellation still releases the claim under `NonCancellable`); `sendInstallmentNotification(id, title, body, decision)` goes through `presentationApplier.apply(..., detailedTitle = title, detailedText = body, actions = emptyList())`. A DISCREET installment reminder shows no plan name or amount (pinned by a new case in `BillReminderWorkerInstallmentTest`). The constructor now has 10 params: `..., timeProvider, presentationResolver, presentationApplier, billReminderManager`.
- S13-a C1 now targets `DeepLinkPolicy` / `DeepLinkHost` / `DeepLinkApplier` (host branches inside `DeepLinkApplier.kt` only). `MainActivity` stays router-only: `handleIntent` is `DeepLinkPolicy.evaluate` then `NotADeepLink` early return then `deepLinkRouter.route`. The mec pre-1a `when (data.host)` block (including the S10a installment branches) was dropped, so installment hosts are `Rejected(UNKNOWN_HOST)` and land on Home, which matches the dormant behaviour. `InstallmentsFeature.kt` KDoc updated accordingly.
- Q1 OWNER-PENDING (owner-approvals 79): the 3 enabled-path `parseDeepLink` tests in `DeepLinkParserTest.kt` (marker comment at line 152, tests through line 196) need the removed `parseDeepLink` and no longer compile. Owner decides: retire them or restate them as S13-a acceptance examples. The other S10a-1 tests were ported to `DeepLinkPolicy.evaluate`.
- S4 follow-up: `AddExpenseViewModel.mapSaveError` hides `RECURRING_FOREIGN_CURRENCY_ERROR_CODE` (slice A's generic mapping; fails closed). Fix in a separate post-merge MEC commit (bounded `mec_` string, es/el DRAFT).
- Test follow-ups from the merge: S2 `BillReminderManager` mock appended to `BillReminderWorkerNotificationPresentationTest` and `BillNotificationSweepParityTest`; S3 `currencySettingsRepository` stub added to `ManualExpenseRepositoryRecommendationTest`.

## Merge review fixes (2026-10-28; NOT validated)
- Guardian verdicts on the staged merge: privacy-security-guardian ACCEPT-WITH-CONDITIONS; architecture-guardian ACCEPT-WITH-CONDITIONS. Fixes decided by the orchestrator under standing approval item 62.
- Fixes applied (test, docs and config only; no production Kotlin):
  - Privacy P3 #2: `BillReminderWorkerInstallmentTest` gains `presentation resolve cancellation after the claim releases the claim and propagates` (the resolver throws `CancellationException` inside the claim-to-post try: claim released, nothing posted, nothing marked sent, cancellation rethrown).
  - Privacy P3 #5: `LEGAL_PATHS.md` builder owner 2 now lists `sendInstallmentNotification` (S7b, D-M1 A; no actions, no content intent) and notes that int's INV-12 "six composition sites" now has a seventh BILL site (installment reminders). Loan S13-a must re-count the composition sites.
  - Architecture P3 #2: `CODEBASE_SEGMENTS.md` header v152 / 2026-10-28; added `QuickAddSection.kt`, `QuickAddUiMapper.kt` (Segment 9), `domain/receipt/statement/*` (Segment 4) and `ui/navigation/InstallmentsFeature.kt` (Segment 40).
  - Architecture P2 #1 (strengthening): `config/validation/test-shards.json` assigns the 7 previously unassigned classes (`domain.diagnostics.*` and `domain.LifecycleWritersWriteBarrierTest` to domain-a-m; `di.RetentionModuleBatchedPurgeTest` to data). Nothing removed or narrowed.
- Q1 is still OWNER-PENDING (the enabled-path `parseDeepLink` block in `DeepLinkParserTest.kt` is untouched).
- MEC B1/B2 follow-up (owned by the MEC lane): once B2 adds the currency selector, the quick-add Fill must parse with the SELECTED entry currency, not `snapshot.homeCurrency` (`AddExpenseViewModel.kt:551-570`); `FOREIGN_CURRENCY_BLOCKED` (`QuickAddFillContract.kt:182`) and the `quick_add_hint_foreign` / `quick_add_flag_amount_foreign` copy (`QuickAddUiMapper.kt:194-196`, `strings.xml` ~:1977) must be revisited, with a test.
- S4 (`mapSaveError`) is tracked in the entry above.

## S13-a acceptance examples carried from retired DeepLinkParserTest (owner-approvals 81a, 2026-10-28)
- Q1 is resolved (owner-approvals 81a): the three enabled-path `parseDeepLink(..., installmentsEnabled = true)` / `DeepLinkDecision` tests were deleted from `DeepLinkParserTest.kt` (they no longer compile; `parseDeepLink` is gone). In their place is one comment line: "Enabled-path installment link cases retired (owner-approvals 81a); restated as loan S13-a acceptance examples in loan-installments-handoff.md."
- Still in `DeepLinkParserTest.kt` and narrowed to the flag-off case (the unrelated-hosts test lost its flag-on half; see example (d)): the flag-false rejection tests (`installments links are rejected as unknown host while the flag is false`, `installments links are rejected by default while the compile-time gate is false`) and `installments flag does not change unrelated hosts`. The compile-time-gate canary must be updated by S13 when it flips `InstallmentsFeature.ENABLED`.
- S13-a MUST re-add the following as tests against `DeepLinkPolicy.evaluate` / `DeepLinkHost` / `DeepLinkApplier`, with `InstallmentsFeature.ENABLED` true via whatever seam S13-a introduces (a test-only override or a parameter on the policy; do not flip the production constant for the test):
  - (a) `expensetracker://installments` is accepted, requires confirmation, and its destination is the Installments overview (`NavigationDestination.Installments`).
  - (b) `expensetracker://installment_plan?id=42` is accepted, requires confirmation, and its destination is `NavigationDestination.InstallmentPlanDetail(42)`.
  - (c) Each of these is rejected even when the flag is enabled (the plan id is required and must be a positive Long):
    - `expensetracker://installment_plan` (no query)
    - `expensetracker://installment_plan?id=` (empty id)
    - `expensetracker://installment_plan?id=abc`
    - `expensetracker://installment_plan?id=1.5`
    - `expensetracker://installment_plan?id=0`
    - `expensetracker://installment_plan?id=-7`
    - `expensetracker://installment_plan?plan=42` (wrong key)
    - `expensetracker://installment_plan?id=99999999999999999999` (Long overflow)
- Note for S13-a: "requires confirmation" here means whatever confirmation contract S13-a defines with app-lock S21 (see the S13-a C1 note in the merge entry above); the tests must assert the destination and the confirmation requirement through that contract, not through the removed `DeepLinkDecision.RequireConfirmation`.
  - (d) With the flag on (via the S13-a seam), unrelated hosts are unchanged: `expensetracker://home` → `Accepted(HOME)`, `expensetracker://unknown` → `Rejected(UNKNOWN_HOST)`, and `https://expensetracker.app/installments` → `NotADeepLink`. This restores the flag-on half of the pre-merge `installments flag does not change unrelated hosts` test (reviewer-strict, mec merge gate B, finding 2).

## BLOCKING before S13 enablement: assistant smallest/largest-purchase vs INV-1 (reviewer-strict, mec merge gate B, finding 3; 2026-10-28)
- The int C5 `ExecuteFinancialQueryUseCase.executeSmallest` (~:307-326, :349, :368) takes the minimum by `expense.effectiveAmount`. Under INV-1, `effectiveAmount` is 0.0 for rows with `spendingExclusionReason != null` (installment purchase records, refunded-plan expenses). Once installments are enabled, "smallest purchase" would answer 0.00.
- Required, before `InstallmentsFeature.ENABLED` flips: largest and smallest consider only `expense.countsAsSpending` rows, with a test that uses a flagged purchase record. Add this consumer to the effectiveAmount audit (S4d/Q13 note ~:420).
- Pre-existing, same audit: the COUNT/AVERAGE queries (~:426, :439) count by list size instead of `countsAsSpending`.
- Optional: a test that drives an installment reminder through `repostActiveAsDiscreet`.
- Merge-gate notes from part A for a loan slice:
  - N1: `NOTIF_ACTIONS_SUPPRESSED_LOCK` is emitted for action-less installment posts on API ≤ 30 (diagnostic noise; S13-a INV-12 re-count).
  - N2: in BillReminderWorker, the release on the `Failed` path (~:291) is not under NonCancellable, and a throwing release at ~:253 can replace the CancellationException.

## 2026-10-29: loan S11a (add-expense installments, VM and wiring) COMMITTED
- The plan amendment "S11 re-anchor after MEC B2/C" is persisted. D-8 is closed; one currency control (INSTALLMENT mode EUR/USD); the coordinator path is `declareWithNewExpense`.
- Writer and separate test-author (about 60 VM tests plus 7 repository parity tests).
- Combined guardian ACCEPT-WITH-CONDITIONS: the docs chain was added (LEGAL_PATHS, ENGINE_INTERACTION_MAP); the gate seam follows precedent. Condition: S11b calls setInstallmentsEnabled with the default `enabled` only, and reviewer-strict greps for `enabled =`.
- A home installments draft retained across sessions is intended (D-B2-R1(c)).
- Validation:
  - targeted vr-20261029-143542-73e18392;
  - runtime-ui vr-20261029-144600-e0243ad0;
  - data vr-20261029-144902-eb56e2fa;
  - known_good_state vr-20261029-145451-68a18468;
  - db_access vr-20261029-145810-9b4d8dce.
  - All PASS.
- Next: S11b (AddExpenseInstallmentsSection plus the Sheet hook, strings ×3 es/el DRAFT; currencyControlEnabled must allow Unset; add "Repeat and installments can't be combined"). Then the S11 candidate: lint, static-guards, app-check, reviewer-strict.

## 2026-10-29: loan S11b COMMITTED; S11 candidate ACCEPTED (reviewer-strict ACCEPT-WITH-CONDITIONS)
- S11b: AddExpenseInstallmentsSection (dormant behind InstallmentsFeature.ENABLED; production calls use the default gate only, verified), the Sheet hook, the Unset control stays pickable, Repeat hidden with a helper while ON. One new key: add_expense_installments_repeat_exclusive (es/el DRAFT, on the sign-off list). 18 UI tests.
- Validation:
  - targeted vr-20261029-151731-ec211e54;
  - runtime-ui vr-20261029-152248-5b122eb8;
  - lint vr-20261029-153507-42eb5786 (after the SuspiciousIndentation fix);
  - static-guards vr-20261029-154212-8464e1e4;
  - app-check vr-20261029-155915-d9a5f90c.
  - All PASS.
- Conditions: S11c is a HARD GATE before S13 (preview card and past-due notice; VM-owned first-due via sessionZone; a non-spending reason key; a switch-click UI test at S13). See the plan's 'S11 candidate review dispositions'.
- Next (loan lane): S11c, then S13 (enablement and final acceptance).

## 2026-10-29: loan S11c COMMITTED (S13 hard-gate items 1-3 done; item 4 stays with S13)
- The preview card and past-due notice (domain InstallmentScheduleCalculator; no duplicated money math); a VM-owned first-due default via the session zone (combine with sessionZoneTick; save == UI); the non-spending reason key.
- 8 new keys (es/el DRAFT, on the sign-off list) plus the owner copy check F3: a negative financing cost prints with a minus sign plus a 'less than the purchase price' line.
- Combined guardian ACCEPT-WITH-CONDITIONS (LOW: past-due may go stale across midnight while the sheet is open; cosmetic FQ names).
- Validation:
  - targeted vr-20261029-165150-99861624;
  - runtime-ui vr-20261029-165807-357a26ea;
  - lint vr-20261029-170124-077eb2e5;
  - known_good_state vr-20261029-170743-e3f42e73.
  - All PASS.
- Next (loan lane): S13 (enablement and final acceptance; needs F-B2-A11Y-1, the es/el sign-off, the S11c item 4 switch-click test, the S13-a deep-link items, all guardians, and reviewer-strict).

## 2026-10-29: S13 re-anchored; owner approvals 91; S13-0 started
- Plan: the loan plan's "S13 re-anchor amendment" (BINDING) supersedes the old S13 row. Sub-slices: S13-0 (INV-1 assistant aggregates), S13-a (deep links + confirmation + NavHost wiring + N2), S13-b (flag flip + e2e + switch-click test), S13-c (docs), S13-final (candidate ladder, device checks, three guardians, fresh reviewer-strict).
- Owner-approvals 91: (a) the two installment hosts on the existing non-BROWSABLE filter are APPROVED; (b) the ENABLED flip on this unreleased branch is APPROVED (stays unreleased under MEC H-1). Still open: es/el sign-off, item-14 device check.
- Orchestrator decisions D-S13-1..4 recorded in the plan amendment and in owner-approvals 91(d).
- MEC Z committed and pushed as bf7bd580 (lane is clean except S13 work).
- S13-0 status: COMMITTED (see below).
- Next: S13-0 targeted `*ExecuteFinancialQuery*`, combined guardian, commit; then S13-a.
- S13-0 implemented + tested (2026-10-29): ExecuteFinancialQueryUseCase filters LARGEST/SMALLEST/COUNT/AVERAGE rows with `spendingExclusionReason == null` (D-S13-1 included). Deviation from the plan wording "countsAsSpending" (orchestrator, item 62): `countsAsSpending` also drops `isNotMine` rows and would empty NOT_MINE/TRANSFER-scoped queries, because ownership is decided by the query's OwnershipFilter (ExpenseRepository.kt:482-486); exclusion-only is the correct INV-1 projection here. NOT_MINE regression tests added.
- S13-0 combined guardian: ACCEPT-WITH-CONDITIONS. Finding A (MAX tests were behaviour pins only, since effectiveAmount is 0.0 for flagged rows) fixed with a tie test that fails without the filter. Accepted LOW residual F-S13-0-COUNT-LIST: COUNT's previewCount now excludes flagged rows while the drilldown list, LIST and the category/merchant breakdown entry counts still include them (no amount affected; follow-up to the assistant owner).
- S13-0 targeted (pre-Finding-A): vr-20261029-203311-9b259427 PASS; final fingerprint ExecuteFinancialQueryUseCaseTest: vr-20261029-205046-f09a7875 PASS. S13-0 COMMITTED. Next: S13-a.

## 2026-10-29: S13-0 COMMITTED 4eb4f9af (pushed); S13-a IMPLEMENTING
- S13-a binding contract (orchestrator): hosts INSTALLMENTS/INSTALLMENT_PLAN, PLAN_ID (`id`, plan host only), `planId: Long? = null`, `DeepLinkPolicy.evaluate(..., installmentsEnabled = InstallmentsFeature.ENABLED)`, (c) rejects -> Rejected(MALFORMED) (no new rejection value), `DeepLinkHost.requiresConfirmation`, MainViewModel `pendingDeepLinkConfirmation` / `requestDeepLinkConfirmation` / `confirmPendingDeepLink` / `dismissPendingDeepLink`, dialog in MainScreen inside AppLockShell (after unlock only), Transactions `onOpenInstallmentPlan` wiring (internal tap, no confirmation), two manifest `<data>` lines (owner 91(a)), N2 suppression pattern. The DeepLinkPolicyTest INV-15 exact host-set pin is extended to 10 hosts (approved by 91(a); stays exact).
- Running: Sonnet writer (main/res/docs) and a separate Sonnet test-author (app/src/test only), in parallel against the contract.
- Next: targeted `*DeepLink*,*AppLockDeepLinkRouter*,*BillReminderWorker*,*NotificationPresentation*,*MainViewModel*`; combined guardian in parallel; then shards runtime-ui + domain-a-m + domain-n-z, guard_docs_truth; commit.
- S13-a writer DONE (main/res/docs per contract). Test-author attempt 1 died on an API timeout with no output; attempt 2 running.
- S13-a combined guardian (privacy-security, Sonnet high): ACCEPT-WITH-CONDITIONS. Dispositions (orchestrator, item 62):
  - Lock overlay: NO EXPOSURE (AppLockGate composes MainScreen only in OPEN; the dialog window is disposed on lock).
  - F1 (LOW, stale pending confirmation survives a relock and re-shows after the next unlock): ACCEPTED residual. It is still post-unlock, content-free and requires confirmation; the fix would edit the app-lock-owned AppLockDeepLinkRouter (cross-lane), so it is deferred to the app-lock owner as F-S13-a-STALE-PENDING.
  - F2 (LOW, DL_APPLIED recorded at request time, before the user confirms): ACCEPTED and documented in a code comment.
  - F3 (LOW-MED, `requiresConfirmation` unused): FIX — the applier branches on `request.host.requiresConfirmation`.
  - Condition A: COMPREHENSIVE_UI_MAP "8 total" -> 10: FIX.
  - Condition B: values-el lacked `action_confirm`/`action_cancel` (English buttons on Greek): FIX — el DRAFT entries added (join the es/el sign-off list).
- S13-a validation (2026-10-29): targeted vr-20261029-214048-b7fc98ce and runtime-ui shard vr-20261029-214434-7f141992 FAILED on 3 test-side defects (debugger: kotlinx stack-trace recovery copies the CE across withContext, so the N2 tests now assert through the cause chain; the sentinel's single Home navigation is the designed MALFORMED fallback). Production unchanged. Re-run vr-20261029-220013-deda501d PASS; guard_docs_truth vr-20261029-214753-b6511971 PASS.
- **BLOCKED (owner decision): static-guards vr-20261029-220246-672b902f FAILED, 26/27; `installment_cloud_reachability` G-INSTALLMENT-CLOUD-03 at domain/privacy/deeplink/DeepLinkRequest.kt:22, :23, :30.** The guard fences all of `domain/privacy/**` as cloud surface with a no-allowlist substring rule ("installment"). DeepLinkHost lives there for historical reasons (app-lock), so the two new host constants trip it. Renaming the constants would evade the guard's stated intent and is rejected. Options put to the owner: (A, recommended) move the single file `domain/privacy/deeplink/DeepLinkRequest.kt` to a non-cloud package (`domain/navigation/deeplink`; 14 importing files, mechanical; guard unchanged; deep-link routing is not cloud code); (B) an owner-approved exact-file exemption in the guard (a weakening); (C) keep installment hosts out of DeepLinkHost (needs app-lock held-link type changes; large). The S13-a diff stays uncommitted until decided.
- Owner-approvals 92 (standing delegation incl. pushes/merges into shared feature branches). 92(c): option A applied — `DeepLinkRequest.kt` moved (rename only; package line changed) from domain/privacy/deeplink to domain/navigation/deeplink; 13 importers, CODEBASE_SEGMENTS, CODEBASE_INVENTORY, app-lock tech-decisions TECH-4 updated; the guard is unchanged and still fences all of domain/privacy/**. No tests live in either package, so shard coverage is unchanged.
- S13-a final ladder on the committed fingerprint, all PASS: targeted vr-20261029-223725-8438e39f; architecture-contracts shard vr-20261029-224427-1d45c0af; guard_docs_truth vr-20261029-224515-b32559ed; static-guards vr-20261029-224533-c3ea238c (27/27). Earlier runtime-ui shard vr-20261029-214434-7f141992 had only the 3 test-side failures since fixed.
- S13-a COMMITTED (see git log). Next: S13-b (flag flip, canaries rewritten in place, InstallmentEndToEndScenarioTest, switch-click test, item 13).
