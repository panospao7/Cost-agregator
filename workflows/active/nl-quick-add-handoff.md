# nl-quick-add — feature handoff

Feature id: `nl-quick-add` (roadmap item 2 of the `ai-evolution` program; see `workflows/active/ai-evolution-handoff.md`)
Branch at start: release-1.0-remediation
Status: PLAN APPROVED — PARKED until Release 1.0 accepted + release-1.0-wt merged + ai-fixes B2d landed; next slice S0. DOCS-ONLY (Release 1.0 frozen; implementation post-1.0, after ai-fixes).

## Origin
Owner GO 2026-10-16 ("go quick-add"). Scope source: `docs/features/ai-evolution/idea-portfolio.md` B1 + `docs/features/ai-evolution/critique.md` §5 (reshape: deterministic parser is the product, Nano optional enhancer, text only, prefill into existing add-expense, raw text in memory only).

## Owner decisions carried in (from ai-evolution-handoff.md)
- Input languages: English + Greek, mixed by circumstance; Greeklish (e.g. "kafes 3.50") IN scope.
- Owner's phone has Gemini Nano; Nano = free on-device model for simple tasks; deterministic fallback required for non-AICore devices. No cloud needed.
- Foreign currency in text ("20 usd"): prefill the original currency and convert at save via the existing converter.
- Typed sentence: Option A CONFIRMED — used once to prefill, never persisted anywhere (not as note, not as history).
- Post-save insight: keep, but only via ai-fixes B2 (own setting/capability, on-device only, never cloud).
- Nothing saved until the user presses Save in the existing add-expense screen (TLC legal path).

## Dependencies / constraints
- ai-fixes (plan approved, parked) touches ManualExpenseRepository / insight (B2d) — nl-quick-add implementation after ai-fixes B2.
- AICore/ML Kit GenAI: foreground-only, per-app quota, Prompt API beta, structured output alpha, Greek not listed.

## Artifacts
| Stage | Path | Status |
|---|---|---|
| 1 Brief | docs/features/nl-quick-add/brief.md | v2 DONE (reshape pass used; further reshape → owner) — increment 1 only, T2, deterministic, Fill + per-field fill-if-not-edited, foreign fail-closed, no AI/schema/save-path change; 1 owner Q; 8 domain Qs. v1 archived brief-v1.md. v1: DONE (feature-strategist) — deterministic-first, prefill-only; verified gap: add-expense has NO currency control (Q2); 5 owner Qs, 10 domain Qs; precondition ai-fixes B2 |
| 2 Critique | docs/features/nl-quick-add/critique.md | DONE (feature-critic) — RESHAPE: inc 1 deterministic-only T2 (Fill action, per-field fill-if-not-edited, foreign currency fail-closed); currency control SPLIT OUT as T3 money feature; Nano DEFERRED to inc 2 after ai-fixes B2 |
| 3 Domain rules | docs/features/nl-quick-add/domain-spec.md | DONE (domain-modeler) — 20 INV, 46 AE, fixture taxonomy 132 cases/13 cats, schema NONE, 5 ODs (OD-1..5), risks R1-R4 |
| 4 UX flows | docs/features/nl-quick-add/ux-flows.md | DONE (ux-flow-designer) — line + Fill at top of sheet; live-region summary; Filled markers; removable category chip (R2); foreign hint; ~45 string keys EN/el/es; 6 UX-Qs |
| Gate 2 sheet | docs/features/nl-quick-add/gate2-decisions.md | DECIDED 2026-10-16 (all recommended) |
| 5 Impact & tier | docs/features/nl-quick-add/impact-map.md | DONE (impact-mapper) — T2 upper end (guard slice T3 by rule); schema NONE; db_access expected unchanged; direct VM/Sheet conflict with loan-installments S11; SHARD GAP: domain.quickadd not in any unit-test shard |
| 6 Slice plan | workflows/active/nl-quick-add-plan.md | DONE (feature-planner) — 8 slices S0, A, B, D, C, E, F, Z; T2 upper (E T3); H-1..H-4; N1-N11; APPROVED 2026-10-16 |

## Human decisions
- 2026-10-16 Gate 1 = RESHAPE. Owner (verbatim): "recommended" → critique §5 Q1-Q6 all recommended:
  1. Currency control SPLIT OUT as separate T3 money feature `manual-entry-currency`; quick-add inc 1 fails closed on foreign
     currency (amount empty + hint); inc 1b maps currency markers into that control later. (Supersedes earlier "prefill original
     currency" for inc 1 — goal kept, delivered in 1b.)
  2. Manual foreign expense with no stored rate for its date → BLOCK save with "no exchange rate for this date" (rule for the
     manual-entry-currency feature).
  3. Nano DEFERRED to increment 2 (after ai-fixes B2); inc 1 deterministic-only, no AI capability/toggle.
  4. Line at top of add-expense + explicit Fill action; fills only fields not edited by the user; clipboard-prefilled amount counts
     as not edited (overwritable).
  5. Inc 1 deterministic categories shown visibly and learned as today; inc 2 Nano categories learned only if user taps them.
  6. Generic words accepted as merchants for inc 1; revisit if noisy.
  Owner explicitly confirmed (verbatim): "yes verify the bug" → read-only debugger verification of the possible pre-existing no-rate money defect (already launched;
  alongside; read-only, no app changes).
  Single allowed strategist reshape pass now runs with these answers.
- 2026-10-16 Brief v2 confirmation. Owner (verbatim): "recommended" → Brief v2 CONFIRMED (Gate 1 closed → GO, increment 1 only, T2);
  Q1 YES: add-expense save-error hygiene IN scope (rethrow CancellationException; bounded localized error replacing raw e.message
  at AddExpenseViewModel.kt:439-447); verified no-rate P3 filed under manual-entry-currency (no Release 1.0 action).
- 2026-10-16 Gate 2 DECIDED. Owner (verbatim): "recommended" → all G2-1..G2-18 recommended (docs/features/nl-quick-add/gate2-decisions.md):
  OD-1 clipboard 12,50→1250 fix as own slice; OD-2 + UX-Q-5 hygiene (Result.Error, 4 validation messages, scroll + assertive);
  OD-3/4 flag; OD-5 adjacent-only block; APPROVED stop-and-asks: guards G-1..G-4 in NEW QuickAddPrivacyArchitectureGuardTest.kt,
  test-shards.json domain.quickadd.* → domain-n-z, CancellationSafety KNOWN_VIOLATIONS removal for AddExpenseViewModel.kt (removal only);
  LEGAL_PATHS/ENGINE map corrections; Segment 9 ownership; UX-Q-1..4,6 yes; empty-category caption in all add flows;
  sequencing after ai-fixes B2d, before loan-installments S11 (loan rebases); follow-ups list; T2 upper (guard slice T3); risks accepted.
- 2026-10-16 Gate 3 PLAN APPROVED. Owner (verbatim): "recommended, approved". Recorded: 8 slices + order (S0 → A (B alongside) → D → C → E → F → Z);
  H-1 AE-40 via JVM write-counting fakes + G-4 (row-count test only if existing JVM Room harness; connected optional);
  H-2 D depends on B (single amount-rule owner); H-3 ambiguous clipboard values stop prefilling (accepted);
  H-4 add_expense_error_currency_loading included. N1-N11 stop-and-asks stand.

## Next action
When Release 1.0 is accepted, release-1.0-wt merged, and ai-fixes landed through B2d: run S0 pre-flight (branch feature/nl-quick-add; D-1..D-13; baselines via validation-runner). Must land before loan-installments S11. No app/ edits before that.

## Open risks
- No-rate foreign-currency behaviour VERIFIED (debugger, 2026-10-16): docs/features/manual-entry-currency/no-rate-verification.md —
  PARTIALLY CONFIRMED + already KNOWN P3 (ledger :231-232/:244-245, deferred to 1.0.x). Only Transactions amount sort + min/max filter
  use the raw fallback; totals/dashboard/analytics/budgets/headers do not. Likely frequent because rate refresh is gated on cloud AI
  (HZ01b2-F1 P2; release batch R23 part 1). Not a quick-add item; routed to manual-entry-currency / money-correctness 1.0.1 pending owner.
- Merchant text logged on suggestion failure (AddExpenseViewModel.kt:128) — handle in quick-add slice.

## Domain-spec findings needing owner (Gate 2)
- OD-1 PRE-EXISTING BUG: clipboard prefill "12,50" → amount "1250" (sanitizer drops commas) — fix in own slice? (rec yes)
- OD-2 same error hygiene for VM Result.Error branch (:415-425, raw repository exception.message) (rec yes, VM only)
- OD-3 bare leading number + word ("2 καφέδες", "15 taxi") flagged as quantity (rec flag)
- OD-4 weekday name == today's weekday → flagged, date stays today (rec flag)
- OD-5 ISO codes outside catalog ("thb") block amount only when adjacent/attached (rec yes)
- R1: MerchantNormalizer.normalize(autoCreate=false) NOT side-effect-free (alias row write + raw-name log, MerchantNormalizer.kt:96-104; categorize() logs raw merchant :185) → Fill lookup must use only normalizedCanonicalNameForMerchant + getCategoryByNormalizedCanonical; §7.5 proposes static guards (new guards = stop-and-ask).
- R2: English default category names match ordinary words ("taxi home 12" → Home) → wrong learned mapping risk (UX visibility).
- Modeler judgement calls for UX to revisit: category word removed from merchant only if something remains; 92-day year-inference window; time tokens ignored.
- Spec §15: five pre-existing defects outside this increment (route to follow-ups).

## Impact-map findings needing owner (Gate 2)
- New guards G-1..G-4 in NEW QuickAddPrivacyArchitectureGuardTest.kt (stop-and-ask).
- Validation-config edit: add domain.quickadd.* to domain-n-z in config/validation/test-shards.json (stop-and-ask). General gap: runner has no shard-coverage check — new packages silently skipped by sharded runs (also worth checking for ai-fixes/loan new packages).
- Remove AddExpenseViewModel.kt from CancellationSafety KNOWN_VIOLATIONS (removal only; guard edit sign-off).
- LEGAL_PATHS §Categorization "no persistent side effects" stale vs MerchantNormalizer alias writes → doc correction.
- Sequencing vs loan-installments S11 (same VM/Sheet).

## Slice statuses
| Slice | Status |
|---|---|
| S0, A, B, D, C, E, F, Z | planned |

## Approved global post-1.0 build order (manual-entry-currency Gate 2 G2-1, 2026-10-16)
Release 1.0 (incl. R16, R23 part 1) → ai-fixes → money-correctness-1.0.x (if specced; else after MEC a) → MEC core (a, setter-less)
→ loan S1..S10 (S3/S6a serialised vs TLC work) → nl-quick-add inc 1 → MEC (b) add-expense UI → MEC (c) receipt review
→ loan S11 on the general currency control (plan edits at loan S0: S11 write set, D-8, S6a AUTO_RECORD_DEFERRED_FX) → nl-quick-add inc 1b → MEC inc 1b.
Source: docs/features/manual-entry-currency/impact-map.md §9.
