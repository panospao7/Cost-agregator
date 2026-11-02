# Unfinished features: triage and planning strategy (2026-10-22)

Status: **PROPOSAL, awaiting the owner gate.** The orchestrator compiled this from the verification files in this
folder (`verification-ui.md`, `verification-placeholder-bank.md`, `verification-gated.md`,
`verification-backend.md`, `verification-reconcile.md`). It contains no new findings. Every disposition below is a
recommendation until the owner approves it.

## 0. Constraints this triage must respect
1. **An execution program already exists.** `build/release-lead-notes/three-track-execution-plan.md` defines three
   lanes (A money+AI, B loans, C capture+safety), at most three writers, a doc/strings lock, and an approved-plan
   order. This program **does not add a fourth lane**. Its slices are queued into the existing lanes.
2. **Dirty main worktree.** MC 1a-1 is validated but uncommitted. MC 1a-2 is partly present and unvalidated.
   `ExpenseRepository.kt`, `ExpenseDao.kt` and `TransactionsViewModel.kt` stay sequential-only until MC 1a-G.
3. **Guard freeze until 2026-12-01.** Owner approval exists only for the listed entries
   (`owner-approvals-20261022.md`). Any guard, baseline or allowlist edit here needs its own approval.
4. **Room v152 belongs to loan S2.** Any item below that needs schema work stops and waits for the owner.
5. **Shared hot files:**
   - `MainActivity.kt`: app-lock S10/S12/S20-22 come before capture-health S7/S11/S19.
   - `HomeScreen.kt`.
   - `ComputeDashboardWidgetsUseCase.kt`: briefing S-fix, then loan S4.
   - `BackupVerifier.kt` and `BillReminderManager.kt` (loan write set).
   - `strings.xml` (doc/strings lock).
   - The doc trio.
6. **Translations are partial:** values 2304 strings, es 288, el 84. Policy question Q-T below.

## 1. Disposition table

Tracks: **A** minor fix · **B** complete a partial feature · **C** new feature, plan later · **X** dispose
(hide/remove/keep-off) · **ABS** absorb into an approved plan · **R** reconcile docs · **—** no action
(refuted or stale TODO only).

| ID | Item (short) | Verdict | Track | Batch / feature-id | Tier | Blocked by |
|---|---|---|---|---|---|---|
| UF-01 | Category "Show all" no-op | confirmed | A | QF-2 | T1 | — |
| UF-02 | Warranty edit missing | confirmed | B | `warranty-edit` | T2→T3 | QF-2 (same screen), D-W |
| UF-03 | Warranty empty-state no-ops | confirmed | A | QF-2 | T1 | MainActivity slot |
| UF-04 | Subscription empty-state no-ops, dead param, scan_bank | confirmed | A | QF-2 | T1 | D-SB |
| UF-05 | Savings goal delete (cascade wipes history) | confirmed | B | `savings-goal-delete` | T3 | D-SG |
| UF-06 | Search result tap ignores id | partly | A (+C) | QF-2; detail view → `transaction-detail` (C) | T1 | MainActivity slot |
| UF-07 | Stress-forecast English text routing | confirmed | B | `stress-forecast-actions` | T2 | HomeScreen overlap check |
| UF-08 | Money Radar adjust-budget tiers | confirmed | A | QF-2 | T1 | D-MR |
| UF-09 | Stale challenges copy + raw e.message in UI | partly | A | with `challenges-unhide` | T1 | — |
| UF-10 | Dead Beta badge | confirmed | X | QF-3 | T1 | D-BETA |
| UF-11 | Orphan voice stack (no RECORD_AUDIO) | confirmed | X (or C) | QF-3 (remove VM method) / `voice-search` (C) | T1 / T3 | D-VOICE |
| UF-12 | Source-link backfill never triggered | partly | B or X | `source-link-backfill` | T3 | D-SL, guard freeze |
| UF-13 | Restart-required TODO | **refuted** | — | QF-3 (comment) | T1 | — |
| UF-14 | Deep links: raw briefingKey in diagnostics + DataStore write | partly | ABS + interim | app-lock S3/S10; interim `deeplink-diag-hardening` | T3 | D-DL |
| UF-20 | Price Protection simulated/invented data (release-visible) | confirmed | X now, C later | QF-1 (hide); `price-protection-local` (C) | T1 / T2-T3 | D-PP |
| UF-21 | Negotiation static EUR seed; false "competitive" claim | confirmed | A (+C) | QF-1; `negotiation-user-quotes` (C) | T1 | D-NEG |
| UF-22 | "Phase 4A Experimental" label | confirmed | ABS | ai-fixes B3b (+1 string pair); router strings → later AI/privacy pass | T1 / T3 | D-AI (scope growth) |
| UF-30 | Bank sync demo stub | confirmed | X (keep hidden) | R-1 (docs truth) | — | D-BANK |
| UF-31 | Investment: no write UI | partly | B (manual MVP) / C (live prices) | `investment-manual` | T3 | D-INV, brief+critique |
| UF-32 | Tax: unpersisted settings, US bracket bug, demo data | partly | X (keep hidden) or C | `tax-redesign` (C) | T2-T3 | D-TAX |
| UF-33 | Challenges: mixed-currency sums block un-hide | confirmed | B | `challenges-unhide` (ABS money-aggregates-sweep, extra increment) | T3 | D-CH, sweep order |
| UF-34 | Shared budgets dead code | confirmed | X | ABS money-aggregates-sweep Inc 3 | T2 | D-SBUD (test deletion + guardrail edits) |
| UF-35 | Financial rescue (disabled break-glass) | confirmed | X (keep off + document) | R-1 | — | D-RESCUE |
| UF-40 | Legacy NL filters unapplied (W15) | confirmed | defer | backlog | T1 | — |
| UF-41 | Assistant min/max, stale KDoc, dead query | partly | ABS | assistant-follow-ups RF-3 after MC 1a-G | T3 | MC 1a-G |
| UF-42 | Forecast ignores recurring income | confirmed | C | `recurring-income-forecast` | T3 | schema, D-INC |
| UF-43 | Bill reminders read rule nextDate (drift) | confirmed | ABS | loan-installments BillReminderManager slice | T2 | loan |
| UF-44 | Corrections never override a global mapping (C10) | partly | B (later) | `categorization-learning` | T2 | D-CAT; possible schema |
| UF-45 | Export lacks receiptLinks; stale PDF TODO | partly | A (TODO) + C | QF-3; `export-roundtrip` (C) | T1 / T2 | — |
| UF-46 | Restore semantic verification dead + wrong enum literals | **confirmed, worse** | B | `restore-semantic-verify` | T3 | serial vs loan (BackupVerifier) |
| UF-47 | ManualExpenseRepository coordinator TODO | **refuted** | — | QF-3 (comment) | T1 | MEC write-set check |
| UF-48 | Group archive second path; G09 validation | partly | defer | backlog (relevant to group-settle-up) | T2-T3 | settlement confirm |
| UF-49 | Deprecated money APIs | partly | split | (i) X after MC 1a-G; (ii) = UF-33; (iii) ABS MC G2-15 / MEC (confirm) | T3 | MC 1a-G |
| UF-50 | NoOp drain controller | **refuted** (test-only) | — | — | — | — |
| UF-51 | Empty `updateExpenseCurrency` | confirmed | X | QF-3 | T1 | guard yml name check |
| UF-52 | Rate/perf TODOs (A10, A03, N+1) | partly | ABS / defer | MC Inc 2; perf backlog | T2-T3 | — |
| UF-53 | Speech cleanup TODO | **refuted** | — | QF-3 (comment) | T1 | — |
| UF-54 | Business-flags coordinator TODO | **refuted** | — | QF-3b (after MC 1a-G) | T1 | MC 1a-G |
| UF-55 | Geoapify client key | confirmed | C (parked) | owner decision only | T3 | D-GEO |
| UF-56 | Hardcoded € + raw compare in follow-through text | partly | A-money | with `challenges-unhide` money slice, or QF-1 | T2 | — |
| UF-57 | Debug restore not atomic | confirmed (debug only) | defer | backlog | T1 | — |
| UF-60 | FEATURES.md over-claims | confirmed | R | R-1 | docs | doc lock |
| UF-61 | Stale handoffs / headers | confirmed | R | R-2 | docs | D-ARCH |

## 2. Batches and feature streams

### Track A: minor-fix batches (impact-mapper → feature-planner → implement → test → validate; T1 means one cheap review)
- **QF-1 "release truth"** (release-visible honesty; highest user priority):
  - UF-20: hide Price Protection through `DeferredFeatureGate` (add the "price" id, the save tokens, and a
    `MainActivity` branch as for Investment).
  - UF-21: negotiation note "reference prices, Greece, EUR, approximate". The engine reports the skipped non-EUR
    count, and the empty state becomes honest.
  - UF-56: replace the € literal with a formatter.
  - Write set: `DeferredFeatureGate.kt`, `FeatureConfig.kt`, `MainActivity.kt` (one branch),
    `SmartBillNegotiationEngine.kt`, `BillNegotiationScreen.kt`, `DashboardFollowThroughEngine.kt`, strings.
  - Validation: one targeted filter.
- **QF-2 "dead buttons"**: UF-01, UF-03, UF-04, UF-06 (filtered + highlighted row), UF-08.
  - Write set: `CategoryBreakdownSheet.kt`, `WarrantyTrackerScreen.kt`, `SubscriptionManagementScreen.kt`,
    `DefaultEmptyStateRegistryInitializer.kt`, `MainActivity.kt` (two branches), `HomeScreen.kt` (one block),
    possibly `NaturalLanguageSearchScreen.kt`.
  - Must not share in-flight time with QF-1 on `MainActivity.kt`; run QF-1 and then QF-2 serially by one writer.
- **QF-3 "stale comments and dead code"**: UF-13, UF-47, UF-53, UF-45 (PDF TODO) comments; UF-51 delete;
  UF-10 / UF-11 removals if approved; `opportunity-scan-handoff.md:57` correction.
  - Comment and docs edits never trigger a full run. Deletions need one targeted compile.
  - **QF-3b** (UF-54 and UF-41 KDoc) waits for MC 1a-G.
- **Where these run:** QF-1, QF-2 and QF-3 are light. Proposal: one writer in **Lane B during W1**. Lane B's W1 work is
  docs scaffolding plus pure domain math, so it has no UI or `MainActivity.kt` conflict. They must merge **before
  app-lock S10 (W3)** touches `MainActivity.kt`. Strings go through the doc/strings lock holder.

### Track B: completions (one feature-id each, full pipeline, one at a time)
| Feature-id | Items | Entry point | Lane / earliest | Notes |
|---|---|---|---|---|
| `challenges-unhide` | UF-33, UF-49(ii), UF-09 | domain-modeler (currency semantics) as an extra increment of money-aggregates-sweep | Lane C after sweep S4/S5 | Scope growth to an approved plan: needs owner approval. T3. Un-hiding a feature is a release decision. |
| `restore-semantic-verify` | UF-46 | impact-mapper → planner (scope is clear) | Lane B, before or after loan's BackupVerifier slice (serial) | T3: room-migration-guardian + reviewer-strict. Old backups must degrade gracefully. |
| `warranty-edit` | UF-02 | domain-modeler (editable fields, needsReview, end-date rule extraction) | after QF-2 | New transactional `editWarranty`; LEGAL_PATHS note. T2→T3. |
| `savings-goal-delete` | UF-05 | owner decision D-SG, then domain-modeler | Lane A or C, any wave after decision | Archive needs schema, which conflicts with v152 ownership. T3. |
| `stress-forecast-actions` | UF-07 | impact-mapper (HomeScreen/briefing overlap) → planner | after briefing S-fix | Typed `StressRecommendation`. T2. |
| `source-link-backfill` | UF-12 | owner decision D-SL, then planner | after guard freeze or with approval | Guard allowlists. T3. |
| `investment-manual` | UF-31 | feature-strategist brief → critic | post-1.1 | New legal path + guard rows. T3. |
| `categorization-learning` | UF-44 C10 | brief (threshold) | post-1.1 | Possible schema. |

### Track C: new features (brief only when the owner says go; no planning now)
`price-protection-local` (return-window tracker on `ReturnWindowDao`, repeat-purchase price change, user-entered card
benefits) · `negotiation-user-quotes` · `recurring-income-forecast` (schema) · `tax-redesign` (vetted tables, liability) ·
`transaction-detail` (a real single-expense view, which would also serve UF-06 and deep links) · `voice-search`
(RECORD_AUDIO, privacy) · `export-roundtrip` (receiptLinks) · `bank-sync` (PSD2/aggregator/backend; parked) ·
`geocoding-proxy` (parked).
These join the existing brief-only set (capture-health, money-aggregates-sweep, bills-paid-reconciliation,
group-settle-up) in `planning-status-matrix.md`.

### Absorb (no new slices; record in the target plan at its S0)
- UF-22 → ai-fixes B3b (+1 string pair).
- UF-41 → assistant-follow-ups RF-3.
- UF-49(i) → after MC 1a-G.
- UF-49(iii) → MC G2-15 / MEC (confirm importers).
- UF-52 A10/A03 → MC Inc 2.
- UF-43 → the loan BillReminderManager slice.
- UF-14 → app-lock S3/S10.
- UF-34 → money-aggregates-sweep Inc 3.

### Interim exception (recommended)
- **`deeplink-diag-hardening`** (UF-14 subset): the raw caller-supplied `briefingKey` is logged into AI runtime
  diagnostics (`MainActivity.kt:224-227`). This violates the bounded-reason-codes invariant **today**.
- Proposal: a tiny T3 patch now. Validate the briefingKey format, drop the raw value from diagnostics, and sanitize
  `period` and `location`. The full policy stays in app-lock S3/S10.
- Owner decision D-DL.

### Track R: documentation
- **R-1 FEATURES.md truth pass** (UF-60 + UF-30/35 notes):
  - Set honest statuses.
  - Fix the header and footer (DB v151, feature counts, date).
  - Point to `audit.md`.
  - Single writer under the doc lock. No validation run.
- **R-2 handoff hygiene** (UF-61):
  - Update the stale headers (approved-plan handoffs, app-lock, money-correctness).
  - Mark superseded Wave 2-5 / M0-M2 / release-1.0-assurance handoffs CLOSED or SUPERSEDED.
  - Archive them only if the owner approves moving files.

## 3. Prerequisites before ANY implementation from this program
1. **Land or isolate the dirty tree.** MC 1a-1 is validated, MC 1a-2 is partial. Options:
   - commit on a branch (needs an explicit owner request); or
   - move the work into the `Cost-agregator-mc` worktree (feature/money-correctness, currently 044be2c8 and clean).
   Then update `money-correctness-handoff.md`.
2. **Owner GO on the three-track lane split** (decision 1 of that plan is still open). This program's slot proposal
   depends on it.
3. **Statement-import merge (8e449c6f)** into feature/ai-evolution: owner decision. QF-2 does not depend on it.
   capture-health and MEC do (three-track hard fact 11).
4. Track A batches start in a fresh worktree cut from the integration tip, never in the dirty main tree.

## 4. Owner decisions (grouped)
**Release-truth (needed for QF-1):**
- **D-PP** Price Protection: (a) hide in release now [recommended]; (b) strip the invented card benefits and SAVE10
  and badge the rest; or (c) leave as is. Also: plan `price-protection-local` later, yes or no?
- **D-NEG** Negotiation: keep for all users with honest labels [recommended], or hide for users without EUR
  subscriptions?

**UI fixes (needed for QF-2/QF-3):**
- **D-SB** Subscription empty-state "scan_bank" action: remove from the release registry [recommended] or gate it on
  BankFeatureAvailability.
- **D-MR** Money Radar chip: route to Budget [recommended] or Analytics; and hide the chip for LOW risk?
- **D-BETA** Remove the dead Beta badge [recommended] or keep it as scaffold.
- **D-VOICE** Voice search: remove only the dead VM method [recommended], remove the whole stack, or plan
  `voice-search` (C).
- **Q-T** Translation policy for new strings: EN+EL+ES every time (current plan rule), or EN-only plus a
  translation backlog, given that existing coverage is 4% (el) and 12% (es)?

**Completions:**
- **D-CH** Challenges: approve the `challenges-unhide` increment inside money-aggregates-sweep (scope growth), plus
  the currency behaviour.
- **D-SG** Savings goal delete: hard delete with a history warning, archive (schema, after v152), or disallow when
  contributions exist.
- **D-W** Warranty edit: which fields are editable on auto-detected/receipt-linked warranties; whether an edit clears
  `needsReview`; date picker.
- **D-SL** Source-link backfill: settings button, automatic one-shot, or drop.
- **D-DL** Approve the interim `deeplink-diag-hardening` patch [recommended] or wait for app-lock.
- **D-AI** Approve UF-22 joining ai-fixes B3b; choose section title wording (e.g. "Automation (opt-in)").

**Dispositions:**
- **D-SBUD** Approve deleting SharedBudgetManager and its tests plus the guardrail line (sweep Inc 3).
- **D-RESCUE** Keep Rescue as a disabled break-glass tool [recommended] or delete it.
- **D-BANK** Bank sync out of scope after 1.x [recommended].
- **D-TAX** Keep Tax hidden [recommended], persist-only (S), or redesign (C).
- **D-INV** Investment: keep (manual MVP brief) or drop.
- **D-INC / D-CAT / D-GEO** Priority of the recurring-income forecast, categorization learning, and geocoding proxy.
- **D-ARCH** Approve marking or archiving the superseded handoffs (R-2).

## 5. Proposed order (after prerequisites)
1. R-2 headers + QF-3 comments: docs/comments only, no validation run; can run immediately in a clean worktree.
2. `deeplink-diag-hardening` (if D-DL): invariant fix.
3. QF-1, then QF-2, by one writer in Lane B, W1. One combined targeted run per batch. Merge before AL S10.
4. R-1 FEATURES.md (after QF-1, so that statuses reflect hide/relabel).
5. Track B in lane order: `restore-semantic-verify` (Lane B, serial with loan BackupVerifier) →
   `challenges-unhide` (Lane C, after sweep S4/S5) → `warranty-edit` → `stress-forecast-actions` (after briefing
   S-fix) → `savings-goal-delete` / `source-link-backfill` (after decisions).
6. Absorbed items land in their host plans. Track C items get briefs only when the owner says go.
