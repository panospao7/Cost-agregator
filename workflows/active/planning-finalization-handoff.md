# Planning finalization — handoff (orchestrator)

- Started: 2026-10-21 (owner: "continue with all the remaining plans until we have finalized the planning stage all together; when you want my opinion, follow your recommendation except if you deem my contribution necessary").
- Scope: PLANNING ONLY. No `app/` source edits, no validation runs, no commits, no branch operations.
- Standing constraints: stop and ask the owner before: guard/baseline/allowlist additions, new permissions, network/cloud use, legal-path changes, destructive migrations, scope growth beyond an approved brief, deleting tests.
- Reviewer independence: the writer of an artifact is never its critic.

## Definition of "planning finalized"

Every item is in one of these states, with an artifact:
- **PLANNED**: plan approved (or decided by orchestrator under standing delegation) and parked/paused with a named prerequisite.
- **DISPOSITIONED**: brief + critique verdict recorded; parked, dropped or merged, with reason and prerequisites.

Implementation is out of scope and waits for the owner's "go" per feature.

## Scope decisions (orchestrator recommendation, applied)

1. **In-flight plans** (`ai-evolution-handoff.md` roadmap): complete any missing planning sub-stage that is needed before its first slice. Scout matrix: `docs/features/_discovery/planning-status-matrix.md` (when written).
2. **Opportunity-scan queue** (from `portfolio-critique.md` revised next-3):
   - `app-lock-privacy-mode`: PLANNED (plan v2 approved 2026-10-17).
   - `capture-health-and-review-safety`: full pipeline to plan (brief → critique → impact → plan).
   - `money-aggregates-sweep`: full pipeline to plan, after capture-health (critic order).
   - `bills-paid-reconciliation`: domain stage only after loan-installments link/confirm is reviewed (consumes it; does not co-model).
   - `group-settle-up`: brief + critique only. M/T3 with a forbidden legal writer today; sequenced after money-aggregates-sweep. Parked.
   - Remaining 8 candidates (goal-pace, ledger-edit-safety, categorization-rules, onboarding-and-data-control, cash-position-and-safe-to-spend, period-insights-and-alert-controls, history-import-and-share-intake, refunds-and-reversals): one disposition line each in the scan portfolio with prerequisites. No brief yet. Full brief only when the owner picks them.
3. **Release C4–C8** (`release-suggestions.md`): orchestrator recommendation = carry C4, C5, C6 and C7 (debug-only) to a 1.0.1 backlog; C8 is covered by app-lock Inc 1a. Recorded as recommendation for the release track; no release-line edit from this lane.

## Owner open questions: defaults applied (not blocking)

| Question | Default applied | Why |
|---|---|---|
| Market: Greece/EUR only for 1.x? | Yes, EUR/Greece for 1.x; wider markets parked | Parsers, tax, negotiation are Greek/EUR-specific |
| Freelancers? | No for 1.x; business/mileage claims removed from docs (truth pass) | No UI for business/personal toggle today |
| Network appetite post-1.0 | No new network features planned; existing cloud-AI consent stays | Keeps privacy scope stable |
| Account balances maintained? | No balance entry planned; runway and safe-to-spend stay labelled or opt-in | Matches A4/A5 advisories |
| Freeze latitude (1.0 docs-only vs 1.0.1) | Docs-only pass in 1.0 (already shipped), behaviour changes in 1.0.1 | Release already accepted |
| Groups used with others? | Assumed occasional; settle-up stays parked behind money-aggregates-sweep | Owner's usage fact: **assumption, to confirm later** |
| Pruning (hide Carbon, Investments, playful cards) | Hide permanently until finished | Matches release R19 and A1 |
| Fixed monthly salary day? | Assumed variable income; pay-cycle budgets parked | Owner's usage fact: **assumption, to confirm later** |

Owner contribution is **necessary** only for the two usage facts above if they change a plan. They are recorded as assumptions, not blockers.

## Status log

- 2026-10-21: scout planning-status matrix started (read-only).
- 2026-10-21: feature-strategist brief started for `capture-health-and-review-safety` (read-only; orchestrator persists output).

## Next action

1. Persist capture-health brief; send to feature-critic (fresh, not the strategist).
2. Persist scout matrix; resolve gaps in in-flight plans.

- 2026-10-21: scout planning-status matrix persisted -> docs/features/_discovery/planning-status-matrix.md
  (approved plans: ai-fixes, money-correctness, MEC inc 1, nl-quick-add inc 1, assistant-follow-ups,
  cloud-alias-hashing, briefing-grounded-signals, loan-installments, app-lock v2). Gaps closed by
  disposition (§B-D of the matrix). One placeholder scout run was launched by mistake; its report was
  discarded (no content used).
- Necessary owner question (only one): C-1 loan lent/borrowed — owner said "ok to the lenting and go"
  (loan handoff:73) but plan defers it (plan:597). Orchestrator recommendation: keep deferred as planned;
  owner confirms. Not blocking planning of other items.
- Pending: capture-health brief (feature-strategist, running). Next: persist brief -> feature-critic
  (fresh) -> decide by recommendation -> domain-modeler / ux (if UI) / impact-mapper -> plan.
- 2026-10-21: owner decision C-1 (verbatim): "I recommend keeping it deferred as planned keep your reccomendation".
  Recorded: loan-installments lent/borrowed stays deferred (plan:597). Closed.
- 2026-10-21: stale headers corrected (ai-fixes, money-correctness, MEC, nl-quick-add plan line 4 -> APPROVED).
  Pointer handoff created: workflows/active/home-insight-gap-handoff.md.
- 2026-10-21: capture-health brief persisted (docs/features/capture-health-and-review-safety/brief.md).
  Strategist open-question defaults adopted (undo = deferred commit ~6 s; clear queue does not teach;
  quiet = 7 days with >=5 captures in prior 30; sorts = newest + needs attention; increment order
  Inc1 review safety -> Inc2 capture health -> Inc3 source control; no schema change).
  Stage 2 critique running (feature-critic, fresh, brief-only input).
  Next: critique verdict -> apply reshape once if RESHAPE -> domain-modeler (+ux-flow-designer, UI present)
  -> impact-mapper -> feature-planner -> owner decision recorded by recommendation.
- 2026-10-21: capture-health critique = RESHAPE (critique.md, 17 items; 6 blocking). Reshape #1 used:
  strategist v2 running with owner-delegated decisions: C3 (clear-queue no-teach) moves with the release
  block-app action; undo becomes its own T3-lite increment; reset learning = removed from this device
  (copy says backups keep it); block action dismisses pending reviews with a count.
  v1 kept as brief-v1.md. A second RESHAPE goes to the owner (process rule).
- 2026-10-21: capture-health brief v2 persisted as brief.md (v1 = brief-v1.md; critique.md = RESHAPE).
  Stage 2 gate decided by orchestrator: GO on v2 (critique items folded; reshape budget used).
  Inc 1 honest counts+sort (T2) | Inc 2 undo deferred commit (T3-lite) | Inc 3 capture health (T2, reviewer-strict)
  | Inc 4 block/dismiss/reset/apps list (T3).
  Stages 3 (domain-modeler -> domain-spec.md) and 4 (ux-flow-designer -> ux-flows.md) running in parallel.
  Next: stage 5 impact-mapper (after both), then plan-gate decision by recommendation -> feature-planner.
- 2026-10-21: stage 4 ux-flows.md written. Orchestrator adopted the UX recommended defaults (Q1-Q12):
  no process-death notice (memory-only buffer); undo 6 s, extended for TalkBack; session-only newest sort;
  no single-reject success snackbar before Inc 2; partial block -> "Blocked X. Removed N. M others still in Review";
  failures = lowest-priority card, 7-day window; health suppressed after restore until fresh signal;
  EN+EL for new keys, ES for health strings; R24 literals moved to resources in touched screens; reset
  result = dialog; Block via "More" icon button; "Review" Greek term = existing app string (not asked).
  Domain follow-ups open: reset copy (apps_reset_failed vs apps_reset_partial), block/dismiss atomicity,
  preflight validation before hiding, auto-backup of health counters.
- 2026-10-21: stage 3 domain-spec.md written (34 INV, T1-T10, AE-1..33, schema none, 7 OD).
  Impact-mapper (stage 5) running, read-only.
  HUMAN SIGN-OFF REQUIRED (not decided by orchestrator; per standing rules): N1-N5 new legal paths;
  Reset learning = permanent user-row deletion + classifier rebuild (INV-32); OD-4 intake worker block
  re-check (guard-sensitive worker); db_ownership_policy entries (Inc 4); OD-3 model rebuild on
  "delete all" is outside this feature (scope flag).
  Recommended defaults for OD-1,2,5,6,7 adopted (OD-1 exclude PAYLOAD_UNAVAILABLE; OD-2 reset zeroes trust
  counters; OD-5 no marker; OD-6 include low-trust; OD-7 AI quick approve stays immediate).
- 2026-10-21 owner (verbatim): "ok follow your reccomendations". Applied to capture-health sign-off items:
  N1-N5 new legal paths APPROVED FOR PLANNING (LEGAL_PATHS entries land with the slice that adds each writer);
  reset learning APPROVED FOR PLANNING with confirmation and copy as in ux-flows (destructive-by-design);
  OD-4 intake worker block re-check APPROVED FOR PLANNING;
  DEFERRED to each slice's own approval (not granted by this message): any guard / baseline / allowlist /
  db_ownership_policy.yml change, any new permission, any Room schema change (none expected).
- 2026-10-21: stage 5 impact-map.md persisted. Tier T3 overall (Inc1 T2, Inc2 T3, Inc3 T2-adjacent, Inc4 T3).
  Schema none; no new permission. Guard/db_ownership entries NOT granted: per-slice owner approval.
  Stage 6 slice plan running (feature-planner, read-only) -> persist to workflows/active/capture-health-and-review-safety-plan.md.
  Sequencing: app-lock S10/S12/S20-22 first or serialised; ReviewQueueRepository serialised with MEC/money-correctness.
  Plan gate: decide by recommendation after review (the owner stays in the loop for guard/permission gates).
- 2026-10-21: first planner run hit its 80-turn limit with no output (no artifact written). Retry launched
  with a compact scope (fewer reads, no source re-verification, ~30 tool calls). Still stage 6.
- 2026-10-21: stage 6 plan persisted: workflows/active/capture-health-and-review-safety-plan.md (21 slices CH-S0..S21, waves W0..W12, traceability INV-1..34 / AE-1..33 complete).
  Plan gate decisions (orchestrator, by recommendation):
  - Plan structure, Inc order (1 -> 3 -> 2 -> 4), flags for Inc 2 and Inc 4 (off by default): APPROVED.
  - UX defaults Q1, Q3-Q12: ADOPTED.
  - Undo window: amend INV-6 to max(6,000 ms, system recommended timeout), duration as parameter: ADOPTED (accessibility; before S9).
  - Guard-config (db_ownership entries for S14/S16/S17/S21, and fingerprints if A3/A4 yes): NOT GRANTED. NECESSARY OWNER DECISION
    (guard freeze until 2026-12-01). Inc 1, Inc 3, Inc 2 (unless A3), S12, S13, S18 can ship without it.
  Asked owner: approve guard-config edits before 2026-12-01 for S14-S17/S21 (Inc 4), or defer Inc 4.
- 2026-10-21 owner (verbatim): "i approve". Scope recorded precisely:
  APPROVED: db_ownership_policy.yml entries (+ signatures.accounting.json and candidate.yml sync) for
  S14 dismissReviews, S16 blockPackageAndDismiss, S17 resetLearningForPackage (incl. OD-2 SourceStatsDao reset),
  S21 worker block-list read / DROPPED_POLICY write, and fingerprint updates for S2/S8 if A3 says yes,
  read entries for S6/S18 if A4 says yes. Each edit is validated through its slice's runner profiles and
  reviewed by architecture-guardian before merge.
  NOT APPROVED by this message: allowlist, baseline or ratchet edits; new permissions; schema change; any
  new catch fingerprint in TransactionClassifier (FG-10). Those still STOP-and-ask.
  Capture-health plan: APPROVED (v1, 2026-10-21). Planning complete. IMPLEMENTATION not started: waits for
  an explicit owner "go" (wave W0 = S0 decision record, then S1, then S2 and S4).
- 2026-10-21: money-aggregates-sweep brief persisted (docs/features/money-aggregates-sweep/brief.md; 3 increments:
  Inc1 fail-closed visible totals T3; Inc2 rounding/minor units T3 (owner sign-off on rounding table + release note);
  Inc3 hidden/dormant T2). Stage 2 critique running (feature-critic, brief-only input).
  Stop-and-ask items already known: Q8 deleting SharedBudgetManager/GroupBalanceCalculator AND their tests (owner approval);
  Inc2 rounding policy change (owner sign-off).
  Queued after this: group-settle-up brief + critique (parked), bills-paid (after loan review), dispositions for
  8 remaining scan candidates.
- 2026-10-21: money-aggregates critique = RESHAPE (critique.md, 13 items; 3 blocking). Reshape #1 used.
  Decisions by recommendation: not-mine header -> money-correctness 1a (drop Q13); old EUR recurring rules -> PARTIAL
  (no conversion); group balance rule owner = GroupSettlementBalancePolicy (+ LEGAL_PATHS:764-768 fix in Inc1);
  mixed rows exclude+count with honest notice; Inc2 rounding moves to group-settle-up gate; budget unavailable
  when home currency missing (fail closed).
  Only necessary owner question: deleting SharedBudgetManager, GroupBalanceCalculator, SharedExpenseManager.calculateBalances
  and their tests + LEGAL/guardrail allowlist edits (stop-and-ask: deleting tests). v1 = brief-v1.md.
  Reshape v2 running (feature-strategist).
- 2026-10-21: money-aggregates brief v2 is now brief.md (v1 = brief-v1.md). Inc1 T3 fail-closed, no schema, independent of ai-fixes and 1a. Inc2 moved to group-settle-up gate. Inc3 T2 hygiene; deleting dead classes and tests is OWNER-APPROVAL (D6). D1-D5 adopted by recommendation. Serialise with app-lock S6 on AnomalyAlertOrchestrator. Next: domain-modeler and impact-mapper; UX skipped (notice copy only).
- 2026-10-21: money-aggregates impact-map.md persisted (Inc1 T3; FG-07 baseline removal = OWNER-APPROVAL item 1; D6 deletions = item 3; create-group EUR default, budget percent/health, typed UNAVAILABLE adopted by recommendation). Domain-modeler still running. Pending owner: baseline removal (or defer both fixes); Inc3 deletions.
- 2026-10-21: money-aggregates domain-spec.md (Inc1; INV-1..20, AE-1..19) written. Decisions by recommendation: OPEN-1 include groups-list labels in Inc1; OPEN-2 detail rows unchanged; OPEN-3 unknown currency = not canonical 3-letter or not in SupportedCurrency.fromCode; OPEN-4 recurring rule stays in ViewModel; OPEN-5 new reason EFFECTIVE_SPEND_UNAVAILABLE; OPEN-6 test-only changes approved (fixture + :552 expectation changes documented; NO deletions); OPEN-7 keep VM fallback in Inc1, remove later; OPEN-8 touched sites only. Still owner: baseline removal (FG-07) and Inc3 deletions. Next: feature-planner (compact) for Inc1.
- 2026-10-21: money Inc1 planner attempt 1 failed (API no response, no artifact). Retry launched (compact scope, ~20 tool calls).
- 2026-10-21: money-aggregates Inc1 slice plan persisted: workflows/active/money-aggregates-sweep-plan.md (S1-S6, waves W0-W4, G1 gate, 19/19 AEs mapped). Plan status PLANNED; implementation not started. Owner gates pending: OG-1 (BudgetViewModel baseline entry removal), OG-2 (AnomalyAlertOrchestrator baseline entry removal). Inc3 deletions D6 pending owner. Planning queue left: group-settle-up brief+critique (parked), bills-paid (after loan review), 8 scan dispositions.
- 2026-10-21 owner (verbatim): "i approve". Applied to the money-aggregates OWNER GATES: OG-1 (remove stale G-CANCEL-01 BudgetViewModel baseline entry in S4) and OG-2 (remove stale G-CANCEL-02 AnomalyAlertOrchestrator entry in S5) APPROVED as exact manual edits in their slices. NOT covered: Inc3 D6 deletions (test deletion) — still needs explicit approval; OG-4 standing rules unchanged.
- 2026-10-21: continuing remaining planning. Strategist briefs running (read-only, max 2): group-settle-up and bills-paid-reconciliation. Then critiques (fresh critics), then dispositions for the 8 scan candidates (orchestrator-written one-liners).
- 2026-10-21: bills-paid brief persisted (docs/features/bills-paid-reconciliation/brief.md). Inc1 T2 read-only (C1 list state from occurrences, C2 reach R21 actions, C3a duplicate warning; no schema, no dependency). Inc2 T3 manual link writer (C4/C5) gated on loan S5+S6c. Strategist recommends reshape; orchestrator adopts recommended defaults Q1-Q8. Next: critique. Group-settle-up brief still pending.
- 2026-10-21: group-settle-up brief persisted (docs/features/group-settle-up/brief.md; M/T3, gated G1-G5; strategist recommends reshape/gated). Critiques running for group-settle-up and bills-paid-reconciliation (fresh feature-critic, brief-only). Remaining after: dispositions for 8 scan candidates; then owner items: group-settle-up G4 (LEGAL_PATHS writer + guard-file retirement), Inc3 money deletions.
- 2026-10-21: group-settle-up critique = PARK (critique.md). Decisions by recommendation: park until G1 merges;
  backup gate = exempt manifests lacking key; cancel allowed when PARTIAL; merge member-removal paths; block/warn linked
  expense deletion (verify R18 coverage). OWNER decision needed later: approve deleting SettlementCalculator.recordSettlement
  and editing guard tests (C4). Brief reshape deferred until G1 merged.
- 2026-10-21 owner: "follow your reccomendation". Applied: settle-up stays PARKED; guard retirement (C4) deferred to its own
  approval-gated pre-slice at un-park time (not approved now). Remaining: bills-paid critique (running), then dispositions for 8 scan candidates.
- 2026-10-21: bills-paid critique = RESHAPE (critique.md; decisions.md). Applied by recommendation: Inc1 = C1 + C2 (day-aligned), T2, no schema; C3a deferred until loan S5/S6c; nextDate writer in Inc2.
- 2026-10-21 owner (verbatim): "i approve them". Scope was ambiguous. RECORDED as approved: release items C4-C8 routed to a 1.0.1 backlog as the orchestrator recommended (release-track decision; no app edits made here). NOT treated as approval: deletion of money Inc3 classes and tests (needs explicit yes); implementation go for capture-health, money Inc1, bills Inc1, app-lock (needs explicit go per feature); group-settle-up guard-file change (deferred). Asked owner to confirm these explicitly.
- 2026-10-21 owner: "do them all". Scope taken as: implementation of all planned feature work in dependency order, with validation and review per slice. Excluded from this instruction (still stop-and-ask): group-settle-up (parked; needs G1), any change not in an approved plan. Started: money-aggregates S1 (implementer, source only; tests by test-author next; no Gradle; no commits). Working-tree rule: do not touch .claude/agents/* or gradle.properties (pre-existing unrelated edits).
- 2026-10-21: money S1 implemented (GroupSettlementBalancePolicy.kt: displayedBalances + Availability + DisplayedBalances; totals/applyToBalances unchanged). Tests: test-author running (GroupSettlementBalancePolicyTest.kt extend). Next: validation-runner targeted-unit-test (sole runner), then reviewer-strict on S1.
- 2026-10-21: money S1 tests written (31 new in GroupSettlementBalancePolicyTest.kt, not run by test-author). validation-runner targeted-unit-test running (sole runner). Pending: report run ID; then reviewer-strict on S1.
- 2026-10-21 owner: "i dont want the code only the plans". Implementation stopped. Money S1 draft saved as workflows/drafts/money-s1-draft-20261021.patch (not applied); app/ reverted to HEAD (uncommitted draft removed). Validation run for S1 may still be reporting; no further implementation. Planning only from here.
- 2026-10-21: S1 validation vr-20261021-204539-05b0b466 = STALE_RESULT (worktree changed during run). Not evidence; not re-queued. Code draft reverted (patch saved in workflows/drafts). No implementation pending.
