# money-correctness-1.0.x — feature handoff

Feature id: `money-correctness` (roadmap item 2a of the `ai-evolution` program; split out of manual-entry-currency at its Gate 1 as "increment 0")
Branch at start: release-1.0-remediation
Status: IMPLEMENTED in worktree C:\Users\panos\Desktop\Cost-agregator-mc (branch feature/money-correctness from 044be2c8, uncommitted). 1a-G and 1b-G PASSED (2026-10-22). Release preconditions still owed by the owner: Greek (values-el) review, Spanish native review (values-es), device query timing (G2-13). No commit/merge yet.

## Origin
Owner 2026-10-16 (verbatim, Greek letters): "οκ γο" = "ok go", in reply to the orchestrator's suggestion "say 'go money correctness' to start its brief".

## Owner decisions carried in
- manual-entry-currency Gate 1 Q2: increment 0 SPLIT OUT as this separate T3 item — unconverted marker for all sources, home-aware
  non-comparable sort/filter rule, single snapshot rounding rule, legacy/home-switch matrix — landing FIRST after 1.0 (after ai-fixes),
  outside the AddExpense queue (global order G2-1).
- MEC G2-2: if this isn't specced when MEC A2 is due, MEC A2 goes first and this rebases.
- MEC G2-5 / OD-3: stored snapshot vs analytics read-time conversion divergence near midnight → align here.
- MEC G2-11: RF-1 (audit reason text) is fixed in MEC A2, not here.

## Inputs
- docs/features/manual-entry-currency/no-rate-verification.md (debugger: P3 no-rate raw fallback in Transactions sort/filter; legacy vs no-rate indistinguishable; fix direction).
- docs/features/manual-entry-currency/critique.md §3.1 (inc 0 read rule unsafe: legacy baseCurrency='EUR' for non-EUR home users; CURR-6), §3.4 (rounding undefined: convertedAmount stored unrounded).
- docs/features/manual-entry-currency/domain-spec.md (RF-5, OD-3, the flagged branch design in TLC :529-585 that this must coexist with).
- docs/features/manual-entry-currency/impact-map.md §9 (TLC block shared with MEC A2 and loan S3/S6a).
- Ledger: docs/releases/release-1.0-assurance/release-1.0-phase3-4-findings-ledger.md :231-245 (offline create P3), CURR-6/CURR-1 (docs/development/FUTURE-WORK.md :139-141), SRH-7 (:321), F-4 budget latest-basis raw fallback, FinancialRescueCoordinator.kt:418-420 fabricated snapshot, deprecated raw SUM DAO queries (docs/ci/DEPRECATION_ESCALATIONS.md :26-37), ReviewScreen.kt:1749-1758 free-text currency, TLC no-rate Timber log (:573-577).

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/money-correctness/brief.md | v2 DONE (reshape used; further reshape → owner) — inc 1a read side (CV rule incl. ALL-tab SQL, tiebreaker, count, assistant isolation, H-fail fallback, home-switch warning, M5) + inc 1b write side (M1 sentinel + log removal, M3 HALF_EVEN helper); no schema; no new owner Qs. v1 archived brief-v1.md. v1: DONE (feature-strategist) — inc 1 M1-M5 (write marker, comparable-value rule, HALF_EVEN snapshot rounding, legacy/home-switch matrix, no Rescue fabrication); no schema; inc 2 recovery/analytics/F-4; inc 3 export; raw-SUM family separate; 6 owner Qs |
| 2 Critique | docs/features/money-correctness/critique.md | DONE (feature-critic) — RESHAPE: ALL-tab SQL min/max uses RAW amounts for all rows (ExpenseRepository.kt:356-364); SQL builder shared with AI assistant; paging tiebreaker needed; split 1a read / 1b TLC write; home-switch warning; rounding deviates from Money.kt HALF_UP → owner sign-off |
| 3 Domain rules | docs/features/money-correctness/domain-spec.md | DONE (domain-modeler) — 22 INV, 26 AE, 2 OD, 12 routed findings; schema NONE; CV rule (not-mine 0 / same-currency raw / valid snapshot / none) in domain/core/money + SQL copy + parity test; assistant gets required raw/comparable param |
| 4 UX flows | docs/features/money-correctness/ux-flows.md | DONE (ux-flow-designer) — no per-row marker; 'Without a EUR value' divider + notice line + explanation dialog; amount sort disabled while H unknown ('Needs your home currency'); home-switch AlertDialog (like ConversionDialog) from both entry points; no in-app export note (release-note text); ~20 keys EN/el/es; UX-Q-1..6 |
| 5 Impact & tier | docs/features/money-correctness/impact-map.md | DONE (impact-mapper) — 1a/1b T3; schema NONE; F1 no ExecuteFinancialQueryUseCase edit; F4 new @RawQuery needs classification yml row; F5 Engine5PrimitiveGuardTest forbids CurrencyCode( in domain/core; F6 CMVM EUR placeholder; F8 accounting exporters; ai-fixes no overlap |
| Gate 2 sheet | docs/features/money-correctness/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 6 Slice plan | workflows/active/money-correctness-plan.md | DONE (feature-planner) — S0, 1a-1, 1a-2, 1a-3a, 1a-3b, 1a-4, 1a-5, 1a-G, 1b-1, 1b-2, 1b-3, 1b-G/Z; H-1..H-3; MC-N1..N15; APPROVED 2026-10-16 |

## Human decisions
- 2026-10-16 Gate 1 = RESHAPE. Owner (verbatim): "recommended" → critique §5 Q1-Q6 all recommended:
  1. Non-comparable rows: last in both sort directions with deterministic tiebreaker; excluded from min/max; visible
     "N without a <home> value" count from a real COUNT query.
  2. Home switch: accept "no comparable value" for old-home rows + MANDATORY warning dialog on home-currency switch (inc 1a).
  3. Rounding: HALF_EVEN via BigDecimal.valueOf in one shared helper (also MEC preview); explicit approval of deviation from
     Money.kt HALF_UP (AGENTS.md:193); positive amount rounding to zero keeps one minor unit.
  4. AI assistant amount filters unchanged in inc 1a (own parameter); alignment → SRH-7 / ai-fixes owner.
  5. Export cells for no-rate rows become empty (M1) — accepted + release note; baseAmount null semantics inc 3.
  6. Read-time home Failed → disable amount sort/filter with bounded state, fall back to date sort.
  Shape: inc 1a (read side, no TLC edits: M2 widened to ALL-tab SQL WHERE + tiebreaker + count, assistant param, M4 read matrix,
  M5 export honesty, home-switch warning) → lands after ai-fixes; inc 1b (write side: M1 + M3 + write tests) just before MEC A2.
  Single allowed strategist reshape pass now runs.
- 2026-10-16 Brief v2 confirmation. Owner (verbatim): "ok" → v2 CONFIRMED (Gate 1 closed → GO; inc 1a + 1b, T3).
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "recommended" → G2-1..G2-16 recommended (docs/features/money-correctness/gate2-decisions.md):
  OD-1 option A (1b owns digits accessor + roundSnapshot; MEC A1 reuses; MEC plan/INV-12/spec notes updated); OD-2 Rescue nullable H → sentinel;
  APPROVED stop-and-ask: new @RawQuery count + one read row in config/guards/db_raw_query_classification.yml; wildcard escape in Transactions
  mode only; keep raw AMOUNT strings (assistant-only) + normalizedEffectiveAmount delegates; CMVM Loading → always warn; reuse MISSING_RATE;
  CurrencyConverter.kt:231 currency-pair log fixed in 1b; HomeCurrencyResolution.Failed raw text → bounded codes in 1a; UX-Q-1..4 yes +
  banner currency; UX-Q-5 follow-up; accounting exporters in release note; S0 query-plan/timing check; tests only in sharded packages;
  follow-ups routed; 1a/1b T3, no new registered guard.
- 2026-10-16 Gate 3 PLAN APPROVED. Owner (verbatim): "Recommended, approved." Recorded: slices + order + parallel windows; H-1 add closed
  variant AmountSemantics.AmountOpsDisabled; H-2 deviations approved (interim RawEffective in 1a-2, 1a-3a/3b split, escaping in all
  non-RawEffective modes, CMVM injects ExpenseRepository for predicted count, SourceCurrencyAllocation delegation in 1b-1, MC-N7 fallback);
  H-3 pre-approved SHRINK-ONLY removal of PII-logging guard/baseline entries for TLC :573-577 and CurrencyConverter.kt:231 (anything else stops);
  S0 perf threshold 200 ms at 10^5 rows → debounce/lazy count, never an index without owner. 1a ships as a unit; MEC A2 waits for 1b.
  MC-N1..N15 stand.

## Next action
Owner: Greek + Spanish string review, device query timing; then commit/merge feature/money-correctness on explicit request. Evidence: build/release-lead-notes/mc-1a1-record-20261022.md (main checkout). MEC S0 may start after merge and must apply RF-10 (see manual-entry-currency-handoff.md).

## Domain-spec findings needing owner (Gate 2)
- OD-1 ORDER CYCLE: MEC A1 waits for money-correctness, but 1b's rounding helper needs the fraction-digits accessor MEC A1 was to create.
  Rec: 1b creates digits accessor + rounding helper in domain/core/money; MEC A1's CurrencyAmountPolicy reuses it. Order ai-fixes → 1a → 1b → MEC S0 → A1 → A2 (matches G2-1). Requires MEC plan A1 note update.
- OD-2 Rescue has no home currency (built by hand in RescueActivity, pre-Hilt, disabled ENABLE_FINANCIAL_RESCUE=false).
  Rec: import takes optional home currency; current caller passes none → every rescued row gets the "" sentinel.
- Risks: ALL-tab count parity vs in-memory search post-filter (impact-mapper); unrounded legacy/shared values excluded at exact max (accepted, inc 2); full-row edit re-snapshots against current home after a switch (inc 2); query cost (impact-mapper).
- Routed: MEC domain-spec AE-21, AE-29, §5 expect 'EUR' for no-rate rows → after 1b it's "" → MEC owner update; ManualRecurringExpenseViewModel.kt:108-116 creates recurring rules without currency (→ 'EUR' default); CsvExpenseImporter.kt:238-239 falls back to "EUR"; assistant preview count ignores min/max + deprecated assistantFilteredExpenses unused → ai-fixes/SRH-7; mixed-group date header unsigned → Transactions owner; initial Transactions tab is MONTH not ALL (UX); home-currency failure reason carries raw exception text → privacy-guardian; brief's JPY round-to-zero example wrong (AE-23 corrected).

## UX findings needing owner (Gate 2)
- UX-Q-1 optional predicted count in switch dialog (rec yes, optional). UX-Q-2 filtered-empty variant of empty state (rec yes).
- UX-Q-3 copy "for now" instead of "until conversion support arrives" (no feature promise) (rec yes).
- UX-Q-4 spec gap: failed COUNT → add bounded CountUnavailable; generic notice only when a bound is set (rec yes).
- UX-Q-5 localising SortOrder.displayName (hard-coded English) out of scope → Transactions owner.
- UX-Q-6 min/max has NO editor in the filter sheet (only banner + restore in MainActivity.kt:355-356) — who sets bounds? impact-mapper to confirm; affects whether "not shown" notice is reachable.
- Banner should show bounds with home currency ("Min: EUR 15.00"; today strings.xml:229 no currency).

## Slice statuses
| Slice | Status |
|---|---|
| S0 | done (baselines, D-1..D-16; query timing on python sqlite3, device timing owed) |
| 1a-1, 1a-2, 1a-3a, 1a-3b, 1a-4, 1a-5 | done, validated |
| 1a-G | PASSED; fresh RS ACCEPT |
| 1b-1, 1b-2, 1b-3 | done, validated (1b-2 strict review fixes applied) |
| 1b-G | PASSED (app-check vr-20261022-170122-e42ad82c); fresh RS ACCEPT-WITH-MINORS, minors fixed |
| Z | done: CODEBASE_INVENTORY drift sync, this handoff, MEC RF-10 notice |

Follow-ups (routed, not in MC scope): MISSING_RATE correlation id (NIT-2); AE-21 recurring-materialisation dedicated test; CurrencyConverter.kt :190/:223/:622/:651 currency-pair logs; CurrencySettingsRepositoryImpl ~:118 catch swallowing cancellation; Engine5PrimitiveGuardTest relPath bug (N4); MC-N10 sweep of tests pinning 'EUR' for no-rate rows; create-path MISSING_RATE lost if an outer caller transaction rolls back.

## Routing update (2026-10-16, orchestrator bookkeeping)
- Dead private `assistantFilteredExpenses` + stale "raw" KDocs (RF-3 residue) now owned by assistant-follow-ups inc 1 (not ai-fixes/SRH-7).
- Assistant "0.00 N/A" empty result: owner = assistant-follow-ups inc 1 (DECIDED 2026-10-16); AmountSemantics alignment for the assistant stays a money-correctness follow-up.
