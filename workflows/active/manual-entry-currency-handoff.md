# manual-entry-currency — feature handoff

Feature id: `manual-entry-currency` (roadmap item 2b of the `ai-evolution` program; split out of nl-quick-add at Gate 1)
Branch at start: release-1.0-remediation
Status: PLAN APPROVED — slice D ACCEPTED; rest PARKED per global post-1.0 order (next: S0 after Release 1.0, ai-fixes, money-correctness if specced). DOCS-ONLY (Release 1.0 frozen; implementation post-1.0).

## Origin
Owner 2026-10-16 (verbatim): "go then" — given after the orchestrator's "what's left" summary with three open decisions
(commit docs? / ai-fixes or loan-installments first after 1.0? / start another planning item?). Interpreted as: start the
next planning item at the top of the not-started list (manual-entry-currency) and accept the suggested ordering
(ai-fixes first after 1.0). Commit NOT interpreted as approved (needs explicit ask). Interpretation stated to owner.

## Owner decisions carried in
- nl-quick-add Gate 1 Q1: currency control split out as this separate T3 money feature; quick-add inc 1b later maps currency
  markers into this control (owner's original goal: "prefill the original currency and convert at save").
- nl-quick-add Gate 1 Q2: manual foreign-currency expense with no stored rate for its date → BLOCK save with
  "no exchange rate for this date"; never silently count the raw amount as home currency.

## Inputs
- docs/features/manual-entry-currency/no-rate-verification.md (debugger: known P3 — no-rate foreign rows use raw amount in
  Transactions amount sort/filter; legacy vs no-rate rows indistinguishable; fix direction).
- docs/features/nl-quick-add/critique.md §1, §3 B1, §4.2 (currency control scope: CurrencyPicker reuse, rounding, recurring, edit path).
- docs/features/nl-quick-add/brief-v1.md §6 (currency rule), Q-D4.
- Release batches touching this area: R16 (comma decimals, amount fields), R23 part 1 (exchange-rate download gets its own
  network setting, separate from cloud-AI consent) — see workflows/active/release-1.0-assurance-handoff.md.
- loan-installments plan S11 touches AddExpenseViewModel/Sheet; nl-quick-add inc 1 also (lands before loan S11).

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/manual-entry-currency/brief.md | v2 DONE (reshape used; further reshape → owner) — inc 1 user-present currency control, ≤7-day local-date rule at TLC via request flag, validDate preview, receipt-review slice, recurring hidden; inc 1b ECB history; NEW: loan S11 already adds EUR/USD control → converge (N2). v1 archived brief-v1.md. v1: DONE (feature-strategist) — T3; inc 0 (unconverted marker all sources + sort/filter) + inc 1 (currency control, block on no rate); hard dep R23 part 1; BGN/RUB have no ECB rates; 6 owner Qs, 8 domain Qs |
| 2 Critique | docs/features/manual-entry-currency/critique.md | DONE (feature-critic) — RESHAPE: split inc 0 into money-correctness-1.0.x; define rate-for-date (≤7 days, local date); manual(+receipt-review)-only block; expose validDate; hide foreign recurring; deactivate BGN/RUB; ECB history as inc 1b |
| 3 Domain rules | docs/features/manual-entry-currency/domain-spec.md | DONE (domain-modeler) — 23 INV, 38 AE, 5 OD, 6 routed findings RF-1..; schema NONE (inc 1 + 1b); new optional request flag carrying entry zone; refusal via ValidationFailed field; no converter change (convertOutcome has validDate) |
| 4 UX flows | docs/features/manual-entry-currency/ux-flows.md | DONE (ux-flow-designer) — '$ USD ▾' control + searchable sheet; preview line; one blocked card (no rate/stale/unsupported/home unreadable); Save disabled when blocked; receipt kept on refusal; Repeat hidden for foreign; S11 merge (EUR/USD subset); ~40 mec_ keys EN/el/es; UX-Q-1..7 |
| 5 Impact & tier | docs/features/manual-entry-currency/impact-map.md | DONE (impact-mapper) — T3 all parts (a core, b add-expense, c receipt, d loan convergence); schema NONE; C-1..C-4 spec corrections; global post-1.0 order; S-1..S-6; RF-7 |
| Gate 2 sheet | docs/features/manual-entry-currency/gate2-decisions.md | DECIDED 2026-10-16 (G2-1..19 recommended; G2-20 not sent) |
| 6 Slice plan | workflows/active/manual-entry-currency-plan.md | DONE (feature-planner) — S0, D, A1, A2, B0?, B1, B2, C, Z; all T3; H-1..H-5; N3-N19; APPROVED 2026-10-16 |

## Human decisions
- 2026-10-16 Gate 1 = RESHAPE. Owner (verbatim): "recommended" → critique §5 Q1-Q6 all recommended:
  1. "Rate for this date" = latest ECB rate dated ≤ expense date and ≤ ~7 days older; older → block with "refresh rates";
     rate date always shown; compared by LOCAL calendar date (not UTC instant).
  2. Increment 0 SPLIT OUT as separate T3 item `money-correctness-1.0.x` (unconverted marker all sources, home-aware non-comparable
     sort/filter rule, single snapshot rounding rule, legacy/home-switch matrix), landing first after 1.0, outside AddExpense queue.
  3. Post-trip/first-use: ECB historical rates (e.g. 90-day window) as inc 1b behind the same R23 setting (privacy review); typed rate deferred.
  4. Block applies to manual entry + user-confirmed receipt review saves; never background/import sources (saved with unconverted marker).
  5. Foreign-currency recurring hidden in inc 1.
  6. BGN/RUB inactive for new selection (like HRK); legacy rows still convert; BGN-home users prompted to switch to EUR; no peg constant.
  Release-track routing (critique §6) NOT yet confirmed — owner asked to say "send to release" explicitly; not inferred from "recommended".
  Single allowed strategist reshape pass now runs with these answers.
- 2026-10-16 Brief v2 confirmation. Owner (verbatim): "recommended" → v2 CONFIRMED (Gate 1 closed → GO, inc 1 T3);
  N1: ship inc 1 alone ONLY IF R23 part 1 includes a periodic rate refresh; otherwise inc 1 + 1b ship together (conditional, resolved at plan/S0);
  N2: YES — merge loan S11's installment-only EUR/USD control into the general control when this lands (one control, one eligibility rule;
  installment mode limits list to installment-supported currencies; declareWithNewExpense uses same rule + refusal text).
  "send to release" NOT given (asked twice) → RQ1-RQ3, Timber currency/amount log, V-C4 note remain recorded here only.
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "recommended" → G2-1..G2-19 recommended (docs/features/manual-entry-currency/gate2-decisions.md):
  global post-1.0 order approved; MEC (a) before money-correctness if not specced; cap = one per-currency table for validator +
  manual + VM + review approval (parser/notification caps → owners); BGN/RUB feature-local list; OD-3 accept; core before loan S11
  + loan plan edits at loan S0; batch receipts unflagged; core ships setter-less; S-2 rounding removal accepted; S-1 setter
  allow-list guard approved; RF-1 audit reason text fixed in (a); refusal = events commit, no row; UX-Q-1..7 recommended;
  RF-2 debugger check before (b); RF-7 follow-up; all parts T3. G2-20 release routing still NOT sent (third time unanswered).
- 2026-10-16 Gate 3 PLAN APPROVED. Owner (verbatim): "reccomended, approved". Recorded: slices S0, D, A1, A2, B0 (conditional), B1, B2, C, Z
  + order; H-1 (no periodic refresh → release A1/A2/B1, hold B2 + C + loan S11 unreleased until MEC 1b); H-2 deviations approved
  (A1/A2 split, preview in A1, 3-site guard fixed in A2, B1/B2 split); H-3 RF-2 confirmed → fix in B0; H-4 one connected-test-class ICU
  digits run in Z; H-5 RF-6 fixed in C only if confined to ReceiptScanViewModel.kt:416. N3-N19 stand.

## Next action
PARKED: S0 at its global-order position (after Release 1.0, ai-fixes, money-correctness if specced).

## Items to route to the release track (critique §6) — NOT acted on; owner decides
- R23 part 1: BGN/RUB as home → rate download yields zero rates (CurrencyRatesRepositoryImpl.kt:80-82; ledger :639) → clear error + stop offering BGN/RUB as home; decide new setting default + periodic refresh.
- TLC no-rate Timber.w logs currency + amount (TransactionLifecycleCoordinator.kt:573-577) → confirm Timber stripped in release, else bounded reason code.
- R21 V-C4: note foreign-currency recurring from manual entry as a future consumer.

## Domain-spec findings needing owner (Gate 2)
- OD-1 cap: one per-currency cap table for ALL creates (TransactionValidator.kt:87 caps every source at 1,000,000) (rec) vs flagged-only cap.
- OD-2 BGN/RUB removal via feature-local list (rec), not inactive-like-HRK (would affect home picker, budget autopilot, bill reminders; overlaps RQ3).
- OD-3 analytics vs stored conversion may differ near midnight → accept; align in money-correctness (rec).
- OD-4 IMPORTANT: land this feature's core (rule + flag + refusal, no UI) as a prerequisite slice BEFORE loan-installments S11 (rec) — TLC never refuses unflagged no-rate saves, so loan S11's planned "FX unavailable" refusal (D-8) and AUTO_RECORD_DEFERRED_FX branch cannot work without it (RF-3). Affects the approved loan plan.
- OD-5 batch receipt review not flagged in inc 1 (rec).
- RF-1 every create (incl. refused retries) stores merchant/amount/currency in the audit event reason text (TransactionLifecycleCoordinator.kt:397) — pre-existing privacy issue.
- RF-2 possible date-picker bug west of UTC (AddExpenseSheet.kt:847-849) — picked date may shift back a day; needs debugger check.
- RF-4 1,000,000 cap duplicated (AppConfig, AmountUtils, AppParserRegistry) — impact-mapper inventory.

## UX findings needing owner (Gate 2)
- UX-Q-1 "Open exchange-rate setting" may discard the form (sheet has no saved state) → keep form if host allows, else confirm first when an amount is entered (impact-mapper to confirm hosting).
- UX-Q-2 receipt with no OCR currency → preselect home with "check it" caption (as today) rather than strict unset (domain-modeler to align).
- UX-Q-3 no "recently used" currency group in inc 1.
- UX-Q-4 keep "Refresh rates" on stale card with honest post-refresh copy.
- UX-Q-5 quick-save confirm surface confirmed in receipt slice.
- UX-Q-6 installments + unsupported selection → unset + restore on switch-off; loan plan/UX docs need follow-up (plan-currency row removed).
- UX-Q-7 R23 setting name from R23 part 1; Greek/Spanish native review.
- Quick-add's quick_add_hint_foreign copy becomes obsolete once this lands (replacement copy provided).

## Slice statuses
| Slice | Status |
|---|---|
| D | ACCEPTED 2026-10-16 — reviewer-strict ACCEPT-WITH-NOTES (2 minor slice-reference errors S6a→S7a / S6a→S3, inherited from plan §D + G2-6; fixed in loan handoff + plan; reviewer: substance needs no re-review). Review iterations used: 1/2 |
| S0, A1, A2, B0?, B1, B2, C, Z | planned |

## Notice from money-correctness Z (2026-10-22): apply RF-10 at MEC S0
money-correctness 1b is implemented and gate-passed on branch feature/money-correctness (not yet merged). Once merged, MEC S0 must:
- update MEC domain-spec AE-21, AE-29 and §5 ("keep today's defaults until money-correctness lands") to expect `baseCurrency=""`, `baseAmount=0.0`, `exchangeRateUsed=0.0` plus one `MISSING_RATE` for no-rate rows (not 'EUR');
- update MEC INV-12 owner text: owner = money-correctness `CurrencyFractionDigits` + `roundSnapshot` in `domain/core/money` (OD-1 A);
- end the MEC INV-15 interim.
MEC A1 dependency ("money-correctness 1b landed") is met only after the merge.

## Cross-feature notes from money-correctness domain-spec (2026-10-16)
- After money-correctness 1b, unconverted no-rate rows store baseCurrency="" (not 'EUR') → this feature's domain-spec AE-21, AE-29, §5 must be updated at MEC S0.
- DECIDED (money-correctness Gate 2 G2-1, 2026-10-16): money-correctness 1b creates the fraction-digits accessor + HALF_EVEN rounding helper in domain/core/money; MEC A1's CurrencyAmountPolicy REUSES it (MEC A1 dependency becomes "money-correctness 1b landed").
