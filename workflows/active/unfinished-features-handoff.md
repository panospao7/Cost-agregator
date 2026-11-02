# unfinished-features — program handoff

Status: **PHASE 4: PLANNING; all 5 plans APPROVED, none startable (owner: planning only)** (2026-10-26). The owner accepted all recommendations, and feature-planner is running
for the QF and deep-link plans. Next is the plan human gate.
No source edits have been made.
Owner intent (2026-10-22): minor issues get fixed, partial features get completed, and anything not started gets
planned and implemented later. Investigation and planning come first.

## Artifacts (all under `docs/features/_discovery/unfinished-features/`)
- `audit.md`: inventory UF-01..UF-61. The initial grep-level statuses are superseded by the verification files.
- `verification-ui.md`: UF-01..14. UF-09/10/13 were gap-filled and appended.
- `verification-placeholder-bank.md`: UF-20..22, UF-30.
- `verification-gated.md`: UF-31..35. UF-32 corrected.
- `verification-backend.md`: UF-40..57. UF-47/50/53/54 refuted (stale TODOs).
- `verification-reconcile.md`: UF-60/61, branch/worktree facts, dirty-tree ownership, FEATURES.md corrections.
- **`triage.md`**: disposition per item, batches QF-1/2/3, Track B feature-ids, absorb list, prerequisites,
  owner decisions, proposed order.

## Program phases
1. Verify: DONE.
2. Triage: DONE (`triage.md`).
3. **Human gate (current):** approve the tracks, batches, slotting into the three-track lanes, and decisions D-*.
4. Plan:
   - QF-1/2/3: impact-mapper, then feature-planner.
   - Track B: pipeline per feature-id.
   - Absorbed items: recorded at the host plan's S0.
   - R-1/R-2: docs only.
5. Execute: one writer per lane, fresh worktree, validation-runner only.

## Human decisions
- 2026-10-22: owner approved organizing a fix/complete/plan program, investigation first.
- 2026-10-22: **owner approved the three-track lane split** (`build/release-lead-notes/three-track-execution-plan.md`,
  decision 1). This program's QF batches are slotted into Lane B W1 (proposal; confirm at plan gate).
- 2026-10-22: owner says the dirty changes are their own in-progress feature work. Orchestrator verified that the MC 1a
  files in the main worktree are **byte-identical** to `Cost-agregator-mc` (feature/money-correctness, the canonical
  copy, newer mtime). The main tree holds a duplicate copy. Neither tree is touched by this program; any cleanup of
  the duplicate is the owner's call.
- Pending: all D-* and Q-T in `triage.md` §4.
- Pending: prerequisites in `triage.md` §3 (dirty tree, lane-split GO, 8e449c6f merge).

## Key findings to keep in view
- Invariant violation today: a raw deep-link `briefingKey` is logged into AI diagnostics (`MainActivity.kt:224-227`),
  UF-14. Interim fix proposed (D-DL).
- Release-visible fake data: Price Protection (unbadged invented card benefits), UF-20. False negotiation
  "competitive rates" claim for non-EUR users, UF-21.
- Restore semantic verification is dead code with wrong enum literals, UF-46 (T3).
- Challenges mixed-currency sums block un-hide, UF-33/49(ii).

## Open risks
- MC 1a work lives in `Cost-agregator-mc`. The main worktree holds an identical duplicate plus owner edits from other
  in-flight features. **This program must not write in the main worktree.** Implementation goes in a fresh Lane B
  worktree cut from the integration tip. Do not edit other features' handoffs/plans while owner sessions are active
  there (R-2 on hold). `money-correctness-handoff.md` is stale (owner's lane).
- `bug-fixes` (8e449c6f, statement import) is not merged into feature/ai-evolution. Merging is not approved.
- Three-track lane split has no explicit owner GO. This program's slot proposal (QF batches in Lane B W1,
  before app-lock S10) depends on it.
- Guard freeze until 2026-12-01. UF-12, UF-34 and UF-51 may touch guard artifacts and need their own approval.
- Translations are partial (es 288, el 84 of 2304). The rule for new strings is unresolved (Q-T).

## Phase 4 started (read-only, 2026-10-22)
- impact-mapper QF-1/2/3 combined run hit its 80-turn limit with no report (no artifact). Re-dispatched as three
  bounded mappers (QF-1 release truth, QF-2 dead buttons, QF-3 stale/dead code) → persist to
  `docs/features/uf-quick-fixes/impact-map-qf1.md`, `-qf2.md`, `-qf3.md`. QF-3: DONE (T1, no stop-gates; split into
  QF-3a comments, QF-3b deletions, QF-3c after MC 1a-G). QF-1: DONE (batch T2; hide needs no MainActivity edit;
  UF-56 text-only; EN-only strings fail lint, so Q-T is effectively EN+EL+ES). QF-2: DONE (T1, no strings, 4 file-disjoint slices; D-MR
  must be recorded in briefing S3).
  [git] none of the QF-1/QF-2 files is dirty in either worktree.
- **Decision sheet:** `docs/features/uf-quick-fixes/decision-sheet.md`. HUMAN GATE: awaiting owner answers.
  Lint fact for Q-T: `verify_lint_baseline_policy.py` caps MissingTranslation at 2219 and a rise fails. EN-only new
  strings likely breach it (to be confirmed by the QF-1 map).
- impact-mapper `deeplink-diag-hardening` (informs D-DL) → persist to
  `docs/features/deeplink-diag-hardening/impact-map.md` — DONE. T3, Lane C W1 micro-slice before AL S10.
  Key upgrade: the bigger issue is integrity — any app can write the briefing key via the exported intent and suppress
  a future briefing delivery; diagnostics leak itself is debug-only/in-memory. Pieces are reusable by S3/S10.

## Phase 4 decisions (2026-10-26)
- Owner: "do your recommendations". All D-* and the sequencing are accepted. They are recorded in
  `docs/features/uf-quick-fixes/decision-sheet.md` § "Owner answers".
- The implementer drafts the el/es copy, and the owner signs off before merge.
- Slices are committed on lane branches, and pushing is allowed. Merges and PRs need an owner request.
- feature-planner dispatched (in parallel, read-only):
  - QF plan → `workflows/active/uf-quick-fixes-plan.md`
  - deep-link plan → `workflows/active/deeplink-diag-hardening-plan.md`

- The deep-link plan is DONE and persisted to `workflows/active/deeplink-diag-hardening-plan.md`. It has 2 slices:
  - DL-1: pure validators plus `MarkDashboardBriefingOpenedUseCase`.
  - DL-2: about 20 lines in `MainActivity.handleIntent`.
  No blocking owner questions remain.
- QF planner, first run: it hit the 80-turn limit and returned no report. A bounded planner (≤20 calls, working from
  the impact maps only) was re-dispatched.

- QF plan DONE and persisted: `workflows/active/uf-quick-fixes-plan.md`, with orchestrator amendments A-1 to A-8.
- **Drift found [git]:** `integration/ai-evolution-1a` @ e6967db1 (worktree `Cost-agregator-int`) already contains
  money-correctness, money-aggregates, ai-fixes and app-lock 1a, including **S10 569ef5c0**. The new Lane B base is
  e6967db1, not 044be2c8.
- **Deep-link interim SUPERSEDED** by AL S10 (use case with the equality rule, DeepLinkPolicy/Applier, sentinel test,
  C4). The plan file is marked SUPERSEDED, and Lane C is not used by this program.
- **D-SB corrected (A-4):** the scan_bank strings are used only by the removed action, so delete them as well. This
  avoids UnusedResources.
- QF-3c is unblocked (A-6). UF-22 rename rejoins QF-1 (A-7); B3b did not do it.
- UF-47 `ManualExpenseRepository.kt:186` may be a real recurring-lifecycle bypass. It needs a Sonnet confirm and is
  out of the QF plan (A-8).

## 2026-10-26: owner chose "planning only for now"
- The QF plan is parked at the plan gate. It is not approved, and implementation must not start. The owner's planning
  only choice also keeps the 3-writer cap safe while app-lock is paused.
- UF-47 is confirmed STALE (plan A-8): `RecurringExpenseRepository.insert` → `RecurringRuleLifecycleCoordinator.createRule`.
- Track B `restore-semantic-verify` (UF-46, T3) has started.
  - Skipped brief/critique because triage fixed the scope: it is a bug fix with verified evidence.
  - Stage 3: domain-modeler is running → `docs/features/restore-semantic-verify/domain-spec.md`. Code is read from
    `Cost-agregator-int` @ e6967db1.
  - Then the impact-mapper, then a human gate on the open decisions, schema impact and tier, then the planner.

- Stage 3 DONE: `docs/features/restore-semantic-verify/domain-spec.md` (200 lines: 16 INV, 16 AE). No Room schema
  change (aggregates are an optional manifest field). Open owner decisions O-1 to O-6.
  - Extra defects found:
    - the verifier executes SQL taken from the manifest;
    - its queries reference a non-column (`effectiveAmount`);
    - raw `expected=`/`actual=` values reach exception text;
    - a CancellationException is swallowed at `BackupVerifier.kt:656`;
    - REAL `SUM` results are device-dependent;
    - its COUNT checks duplicate `tableCounts`.
- Stage 5: impact-mapper dispatched. It will check whether the manifest-SQL execution is reachable in production, and
  cover guards/allowlists, tests and conflicts with loan S1/S2b.

- Stage 5 DONE: `docs/features/restore-semantic-verify/impact-map.md`.
  - T3, no Room change, manifest-SQL path unreachable in production.
  - Slices S0 (optional) to S6.
- [git] Loan S2b to S6b are committed on `feature/manual-entry-currency` (34b20715) but not in integration.
  `BackupVerifier` already differs there by +40/−7, so O-5 is reversed: loan goes first.
- **HUMAN GATE:** `docs/features/restore-semantic-verify/decision-sheet.md` (O-1 to O-6, Q1 to Q9, with
  recommendations).
  - O-2 (test replacement) needs explicit approval.
  - Q6 changes the spec: a capture failure degrades the backup to NOT_CAPTURED rather than failing it.

- 2026-10-26: the owner said "I approve". All restore-semantic-verify recommendations are accepted, **including O-2**
  (test replacement). This is recorded in the decision sheet. The domain-modeler is revising the spec.
  - This does NOT approve the QF plan, which stays parked at its gate.

- Spec revised (INV-1..17, AE-1..19) and no OPEN items remain. The only thing outstanding is the el/es sign-off before
  merge.
  - Orchestrator call (within the approved intent): the SPENDING/INCOME aggregates use only the three `ExpenseDao`
    constants, with no `isNotMine` filter.
    - Not-mine rows add 0 to the sum but increase the count. These are integrity fingerprints, and this avoids copying
      a predicate.
- The feature-planner has been dispatched (≤25 calls) → `workflows/active/restore-semantic-verify-plan.md`.

- Stage 6 DONE: `workflows/active/restore-semantic-verify-plan.md`.
  - Contents: S-pre (re-anchor), then S1 to S6 in three batches B1/B2/B3, then a candidate run.
  - Status: READY, NOT STARTABLE.
  - Planner facts F1 to F4 are confirmed. Spec errata E-1 (journal reason normalisation) and E-2 (no isNotMine filter)
    have been appended to the spec.

- 2026-10-26: owner "Ok proceed" → **restore plan gate APPROVED**. It remains NOT STARTABLE (needs the MEC+loan merge
  and the owner lifting "planning only"). The QF plan stays parked (not approved).
- Track B `savings-goal-delete` (UF-05, T3) started. Brief/critique skipped: triage fixed the scope (verified UF-05
  evidence in `verification-ui.md` row UF-05). D-SG (hard delete with warning / archive / disallow) is framed by the
  domain-modeler with a recommendation and decided at the post-impact-map gate.
  - Stage 3: domain-modeler dispatched → `docs/features/savings-goal-delete/domain-spec.md` (code read from
    `Cost-agregator-int` @ e6967db1).

- Stage 3 DONE: `docs/features/savings-goal-delete/domain-spec.md` (INV-1..16, AE-1..16, O-1..O-8, SG-1..4).
  Recommends D-SG (c) delete only empty goals (no schema), with archive as a later feature `savings-goal-archive`
  (v153 after loan). Corrected stale evidence: the cascade wipes only sweep ledger rows and plans (manual
  contributions live in DataStore); the month-acceptance marker is not an orphan defect; gamification already
  refreshes via the Room Flow. New: `guardedWrite` lets non-barrier exceptions escape. SG-2 touches
  `config/guards/db_ownership_policy.yml` (exact-callable entry): guard-freeze item, needs owner approval.
- Stages 4+5 dispatched in parallel: ux-flow-designer → `ux-flows.md`; impact-mapper → `impact-map.md` (orchestrator persists).

- Stage 4 DONE: `docs/features/savings-goal-delete/ux-flows.md`. Per-card overflow menu → confirm dialog (empty) or
  not-allowed dialog (funded/completed); `DeleteFlowState` in VM state; bounded `DeleteRejected` events; 9 new string
  keys (EN drafted; EL/ES by implementer, owner sign-off); no Undo. UX questions 1-6 carry recommendations.
  Waiting for the impact map.

- Stage 5 DONE: `docs/features/savings-goal-delete/impact-map.md` (persisted, plus an orchestrator git verification
  section). T3 low-end; no schema change; a no-guard-edit design exists (the S2 db-ownership guard run decides). The
  third affected test is `NotificationParserDashboardTotalSeamTest.kt:408` (constructor call only). [git] Loan and
  app-lock tips don't touch the savings write set. Baseline MissingTranslation in int = 2223 (auto cap).
- **HUMAN GATE:** `docs/features/savings-goal-delete/decision-sheet.md` (O-1..O-8, UX-1..6, G-1/G-2, T-1/T-2, R-1).

- 2026-10-26: owner "Accept recommend" → all savings-goal-delete gate items are accepted (recorded in the decision
  sheet). The domain-modeler is revising the spec. Next comes the feature-planner.

- Spec revised: REVISED, all decisions closed. INV-1..20 and AE-1..24. The repository also injects
  `SavingsContributionLedgerDao` and calls the existing `getAll()` (a read) inside the transaction, so no new `@Query`
  is added. The planner must check that the extra DAO field passes the guards; the S2 guard run decides.
  ux-flows §8 is marked CLOSED.
- feature-planner dispatched (≤25 calls) → `workflows/active/savings-goal-delete-plan.md` (orchestrator persists).

- Stage 6 DONE: `workflows/active/savings-goal-delete-plan.md`.
  - Status: READY, NOT STARTABLE. The plan gate is pending.
  - Slices: S-pre, S1 policy, S2 repository transaction + guard checkpoint G (`registered-guard` `db_access` then
    `db_artifact_sync`), S3 VM, S4/S5 strings + screen, S6 docs; then the candidate gate.
  - Guard spot-check: a new read-only DAO field is not flagged, by static reading. G is the decider; if it fails,
    STOP and escalate.
  - No dependency on the loan merge. Base worktree: `Cost-agregator-sgd`.

- 2026-10-26: owner "Continue" → **savings-goal-delete plan gate APPROVED**. It remains NOT STARTABLE while
  "planning only" holds. The QF plan is still parked.
- Track B `warranty-edit` (UF-02, T2→T3) started. Brief/critique skipped: triage fixed the scope
  (`verification-ui.md` row UF-02). D-W is framed by the domain-modeler.
  - Stage 3: domain-modeler dispatched → `docs/features/warranty-edit/domain-spec.md`.
  - Implementation depends on QF-2d, which touches the same `WarrantyTrackerScreen.kt`.

- warranty-edit Stage 3 DONE: `docs/features/warranty-edit/domain-spec.md` (211 lines, all 12 sections). The agent
  ended with an API error after writing the file; the orchestrator checked that the file is complete.
  - O-1..O-11 carry recommendations. No schema change.
  - Design reuses the `updateWarranty(Warranty)` policy identity, so there is no guard edit (a new method would bump
    the pinned count 432).
  - Stale evidence: `updateWarranty` has ZERO production callers. End-date arithmetic is duplicated ×3 (VM, UC,
    extractor).
  - O-3 (shared picker form for add+edit) changes the shipped add UX and is scope growth: owner confirm needed.
  - O-5 allows EXPIRED→ACTIVE only on an end-date edit: owner OK needed.
- Stages 4+5 dispatched in parallel: ux-flow-designer → `ux-flows.md`; impact-mapper → `impact-map.md` (orchestrator
  persists).

- warranty-edit Stage 4 DONE: `docs/features/warranty-edit/ux-flows.md`.
  - An Edit button on each card; the action row becomes a `FlowRow`.
  - A shared scrollable `AlertDialog` form reuses `FormDateField`/`FormDropdown`.
  - 26 new string keys (EN/EL/ES).
  - UX Q1-7 have recommendations; Q7 is the O-3 add-flow scope growth.
- [git] Loan 34b20715 adds only two `// spending-exempt` comments to `WarrantyDao.kt`. The app-lock tip has no warranty
  changes. ai-fixes B1a/B1b are already in integration. cloud-alias-hashing S2 touches `CloudWarrantyExtractionService`
  and test-suite-audit B1-4 touches `WarrantyExpirationWorker`; whether either collides depends on the impact map's
  write set.

- warranty-edit impact-mapper, first run: API error, no report (the agent is read-only, so nothing was persisted).
  Re-dispatched with a tight scope (≤22 calls; it reuses the guard conclusions from savings-goal-delete §2).

- warranty-edit Stage 5 DONE: `docs/features/warranty-edit/impact-map.md`. T3, no schema change, no guard edit
  (reuses the `updateWarranty` policy rows). One test stop-gate: invert `WarrantyTrackerRepositoryTest.kt:820-837`
  (O-6). The orchestrator closed the bypass-guard unknown: `WarrantyPromotionBypassGuardTest` exempts
  `WarrantyTrackerRepository.kt`.
- **HUMAN GATE:** `docs/features/warranty-edit/decision-sheet.md` (O-1..O-11, UX-1..7, S-1, T-1, F-1, R-1). T-1 needs
  an explicit yes; O-3 and O-5 change shipped behaviour.

- 2026-10-26: owner "Accept recommendations" → all warranty-edit gate items are accepted, including T-1, O-3 and
  O-5 (recorded in the decision sheet). The domain-modeler revises the spec, then the feature-planner runs.

- warranty-edit spec revised: REVISED, all decisions closed. INV-18..21 and AE-19..22 appended. Key new rule
  (INV-18): the edit candidate keeps needsReview=true until the nested `confirmWarranty`; otherwise the CONFIRMED
  event is lost. The orchestrator fixed the title to "tier T3".
- feature-planner dispatched (≤25 calls) → `workflows/active/warranty-edit-plan.md` (orchestrator persists).

- warranty-edit planner, first run: API error, no output. Re-dispatched with ≤14 calls and ≤180 lines. Read-only
  agents have now failed 3× with "No response from API"; keep their budgets small.

- warranty-edit Stage 6 DONE: `workflows/active/warranty-edit-plan.md`.
  - Status: READY, NOT STARTABLE (planning only; needs QF-2d for S4). The plan gate is pending.
  - Slices: S-pre, S1 domain, S2 repository + checkpoint G, S3 VM, S4 UI (after QF-2d), S5 docs.
  - S1-S3 may start from e6967db1.

- 2026-10-26: owner "Continue" → **warranty-edit plan gate APPROVED**. NOT STARTABLE (planning only; S4 needs QF-2d).
  The QF plan is still parked.
- Track B `stress-forecast-actions` (UF-07, T2) started. Brief/critique skipped (`verification-ui.md` row UF-07).
  - [docs] briefing-grounded-signals is PARKED post-1.0 and its S-fix is not done. ai-fixes B3a is already on
    integration. The triage ordering "after briefing S-fix" (shared `ComputeDashboardWidgetsUseCase.kt` and
    `HomeScreen.kt`) is an implementation-order constraint only; the impact-mapper checks the overlap.
  - Stage 3: domain-modeler dispatched (≤18 calls) → `docs/features/stress-forecast-actions/domain-spec.md`.

- stress-forecast-actions Stage 3 DONE: `docs/features/stress-forecast-actions/domain-spec.md` (INV-1..13,
  AE-1..12, O-1..6).
  - Six kinds; three informational. Recommendations: localize now (O-1); informational rows have no tap (O-2).
  - No schema change: `recommendationsJson` is never written or read.
  - Zero tests need retargeting.
  - `ComputeDashboardWidgetsUseCase` only wraps the result, so the overlap with the briefing S-fix is smaller.
- Stages 4+5 dispatched in parallel (small budgets): ux-flow-designer → `ux-flows.md`; impact-mapper →
  `impact-map.md` (orchestrator persists).

- stress-forecast-actions Stage 4 DONE: `docs/features/stress-forecast-actions/ux-flows.md`.
  - Actionable rows reuse `RecommendationChip` with a chevron. Informational rows are plain, with no border or chevron.
  - 11 keys, including 1 plural. The header gets localized; "Min projected:" stays English (follow-up).
  - UX Q1-7 carry recommendations. Waiting for the impact map.

- stress-forecast-actions Stage 5 DONE: `docs/features/stress-forecast-actions/impact-map.md` (persisted, plus
  orchestrator verification).
  - T2. No CDW edit, so the briefing S-fix ordering is not needed.
  - The QF-2 HomeScreen hunk sits about 5 lines away: land QF-2 first.
  - `CancellationPropagationContractTest` anchors on the engine's `StressForecastResult(`; keep that construction
    in place.
  - Allowlist concern closed: entries are keyed by file name, and the engine is not on the list.
- **HUMAN GATE:** `docs/features/stress-forecast-actions/decision-sheet.md` (O-1..O-6, D-PL, UX-1..7, ORD, R-1).
  No guard or test items.

- stress-forecast-actions gate: the orchestrator applied all recommendations under the standing owner instruction
  (none of them is a guard, test, destructive or permission item); the owner may override at the plan gate.
  Spec revision and the planner were dispatched in parallel (the planner treats the decision sheet as authoritative).

- stress-forecast-actions Stage 6 DONE: `workflows/active/stress-forecast-actions-plan.md`.
  - Status: READY, NOT STARTABLE (after QF-2). Plan gate pending.
  - Slices: S-pre, S1 domain + engine, S2 card + HomeScreen + mapper + strings (one compile batch with S1), S3 docs
    if needed.
  - One targeted run, then lint once, then static-guards once. One cheap review.

- stress-forecast-actions spec revised: REVISED, all decisions closed. INV-14..18 and AE-13..16 appended. INV-14
  pins the cancellation-scan anchor. The plan's INV refs (INV-1..13) are unaffected.

- 2026-10-26: owner "I approve" → **stress-forecast-actions plan APPROVED** and **uf-quick-fixes plan APPROVED**
  (with A-1..A-8). Both were asked for explicitly in the same message. Neither is startable while "planning only"
  holds.

## Plan status board (2026-10-26)
| Plan | Status | Start needs |
|---|---|---|
| `uf-quick-fixes-plan.md` | APPROVED | lift "planning only" |
| `restore-semantic-verify-plan.md` | APPROVED | lift "planning only" + MEC+loan merge into integration |
| `savings-goal-delete-plan.md` | APPROVED | lift "planning only" |
| `warranty-edit-plan.md` | APPROVED | lift "planning only"; S4 needs QF-2d landed |
| `stress-forecast-actions-plan.md` | APPROVED | lift "planning only"; QF-2 landed first |

## Next action
1. **Owner:** decide whether to lift "planning only". If it is lifted, the recommended order on the single Lane B
   writer is:
   1. QF (worktree `Cost-agregator-qf` from e6967db1; QF-3a → QF-1 → QF-2a..d → QF-3b → QF-3c).
   2. stress-forecast-actions (after QF-2), then warranty-edit (S1–S3 can go earlier; S4 after QF-2d).
   3. savings-goal-delete. It is independent, so it can run as a second lane if the writer cap allows.
   4. restore-semantic-verify, once MEC+loan has merged.
   - el/es sign-off is needed before each merge.
2. The remaining Track B items each need an owner decision before planning:
   - challenges-unhide: UF-49 currency fix plus scope growth to money-aggregates-sweep (D-CH).
   - source-link-backfill: D-SL; it touches guard allowlists during the freeze.
   - investment-manual and categorization-learning: need an owner go.
3. Docs-only work that can run while planning only:
   - R-1 FEATURES.md truth pass (triage says to do it after QF-1);
   - record the absorb items (UF-41, UF-49(i), UF-52, UF-43) at their host plans' S0;
   - R-2 stays on hold.
