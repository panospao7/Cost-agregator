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

## S0 results (2026-10-23, orchestrator; worktree C:\Users\panos\Desktop\Cost-agregator-mec, branch feature/manual-entry-currency from bcd8e921)
- Base: Release 1.0.2 (044be2c8) + money-correctness 681d49a3 + money-aggregates bcd8e921. MC landed -> A1 dependency met (amendment 2026-10-16).
- Discovery (scout Sonnet): only line drift; TLC create snapshot now :605-674 (unflagged :630-667, dedup :676); CREATE_ATTEMPTED reason :473; ReviewQueueRepository cap :190; 11 TLC-constructing tests (plan said 8; D-9/N11 check at A2). nl-quick-add inc 1 NOT landed -> B1 waits (after ai-fixes B2d). Lane order: A1 -> A2 -> loan S1.. ; B1/B2/C after nl-quick-add.
- Baselines: tree == bcd8e921 which passed money-aggregates G1 on the same code (unit-tests 7759/0 incl. integration-golden vr-20261022-202152-88b92f0f, static-guards vr-20261022-203211-16dbf07c, lint vr-20261022-204926-763fcf27) -> reused. db_access vr-20261022-221113-a58331d3 PASS; db_artifact_sync vr-20261022-221402-b54dc57c PASS.
- RF-10 applied (domain-modeler) to domain-spec.md / ux-flows.md / impact-map.md: sentinel triple + one MISSING_RATE for unflagged no-rate rows (AE-21, AE-29, §5); INV-12 owner = CurrencyFractionDigits.of + roundSnapshot (CurrencyAmountPolicy delegates); INV-15 interim ended (preview = roundSnapshot(parsed x rate, home), equals stored baseAmount); RF-5 resolved in MC 1b. New OD-6 (digits unavailable).
- OD-6 / N14 DECIDED by orchestrator under the owner's standing instruction ("do your recommendation and continue"; owner may override): FAIL CLOSED. When CurrencyFractionDigits.of(code) returns null: CurrencyAmountPolicy.fractionDigits returns null and check() returns a distinct DigitsUnavailable outcome (never assumes 2); flagged user-present saves in that currency are refused (A2 maps it to the existing amount-validation refusal path, never the sentinel triple); preview shows no converted amount; home with unavailable digits -> flagged conversion refused (HOME_CURRENCY_UNRESOLVED-style refusal), unflagged path unchanged (MC sentinel). Normal UI paths only offer catalog codes, so this is a defensive branch.
- Plan text drift to apply when executing (spec already corrected): A1 :66 fractionDigits delegates to CurrencyFractionDigits.of (not java.util.Currency directly); A1 :69 ConvertedPreview amount = roundSnapshot result (not unrounded); B2 :105 preview uses roundSnapshot; A2 :81 / R9 :160 cite :630-667; Z :120 / Deferred :174 RF-5 resolved.
- needs-sonnet-confirm before A2: DiagnosticReasonCode MANUAL_SAVE_BLOCKED_NO_RATE registry pin (N13), SafeEventMetadata key limits, Dagger defaulted-param (N11), CREATE_ATTEMPTED reason readers (N12).
S0 DONE. Next: A1.

## A1 DONE (2026-10-23)
CurrencyAmountPolicy (digits delegate to CurrencyFractionDigits.of; cap table §6; check never rounds; DigitsUnavailable per OD-6), CurrencySelectionPolicy, RateEligibilityEvaluator (calendar date in injected zone, UTC-midnight bound, convertOutcome TRANSACTION_DATE + StaleRatePolicy.None, ≤7 days, via-EUR older leg, ECB label rule; no clock/logging/network), UserPresentRatePreview (roundSnapshot of parsed × rate; AmountUnavailable never 0.0). Docs: CODEBASE_SEGMENTS, LEGAL_PATHS Money/Currency, ENGINE_INTERACTION_MAP.
Review (reviewer-strict) ACCEPT-WITH-MINORS -> fixed: CurrencyConversionTest converter pins (via-EUR min date, "+" sources, as-of only, typed Failed); evaluator fail-closed branch tests with a rule-breaking store (IDENTITY and invalid-rate branches unreachable, documented); KDoc "store exceptions propagate; callers map them; CE never caught"; EUR literal -> CurrencyCode.EUR.code. A fixer test wrongly expected a same-calendar-day row 1 ms after the bound to be None; rewritten to the spec's calendar-day rule (Eligible, ageDays 0).
Validation: targeted vr-20261023-010114-3ea6ebbc PASS; domain-a-m vr-20261023-010315-1685e16c PASS; static-guards vr-20261023-010418-3a74499f PASS. 28 catalog digit pins match the JDK (N14 clear).
A2 notes: flagged snapshot = roundSnapshot(amount × Eligible.rate, home) with exchangeRateUsed = Eligible.rate from ONE evaluate() call (AE-38); TLC owns the store-exception catch (CE rethrow); refusal mapping for DigitsUnavailable/AmountUnavailable (OD-6). Residual: via-EUR with one leg after the bound only prevented by the DAO <= rule.
Next: A2.

## A2 notes (strict-review fixes, 2026-10-23)
- Per-currency cap (CurrencyAmountPolicy) also applies to UPDATES via TransactionValidator.validateFields/validateFinalExpenseState; this only loosens the old flat 1,000,000 and is required so large KRW rows (up to 1,000,000,000) stay editable. Flagged precision check is create-only.
- RF-1b follow-up (route to privacy / money-correctness; NOT fixed in A2): CREATE_ATTEMPTED.dedupeKey still stores `amount_merchant_bucket_CURRENCY_TYPE` in plaintext (pre-existing). Either null/hash it or confirm `transaction_events` is excluded from export/backup (plan D-8 / N12). The new flagged-refusal CREATE_VALIDATION_FAILED event deliberately omits dedupeKey.
- INV-13 `myShareAmount` precision check is NOT implemented in A2: spec INV-13 / edge-case table says "same digits as the expense currency ([U] semantics)" and plan D-10 lists `myShareAmount` currency semantics as an unresolved discovery item with no slice owning the check. Deferred; needs an owner (VM inline validation slice or a follow-up) once semantics are confirmed.
- DB ownership: the flagged-refusal event is written by `transactionEventDao.insert` literally inside `createExpenseMutation` (already authorized); `buildUserPresentRefusalEvent` is pure. No db_ownership_policy.yml change.

## A2 DONE (2026-10-23)
Validation on the final tree: db_access vr-20261023-031650-60125163 PASS; db_artifact_sync vr-20261023-031923-ce05fed5 PASS; shards data vr-20261023-032125-fa719ea9 PASS, architecture-contracts vr-20261023-032457-9b653e21 PASS; static-guards vr-20261023-032545-b2c14873 PASS. The first targeted run (vr-20261023-030500-d49428f3) and domain-n-z (vr-20261023-031939-06972765) both FAILED on one test only: `flagged amount precision error from the validator is a plain validation failure with no rate read` built TransactionValidationError positionally (code, message, field), so message = "amount". That was a test bug; TLC correctly maps `.message`. Fixed with named args (test-only); targeted rerun vr-20261023-042923-c12acee8 PASS, which covers the only domain-n-z failure.
Next: loan-installments S1 in this lane (after the S0 D-10/D-12/D-13/D-20 discovery); MEC B1/B2/C wait for nl-quick-add inc 1.

## Note from nl-quick-add final review (2026-10-28, N4)
- B1 must add an AddExpenseViewModel.mapSaveError branch mapping RECURRING_FOREIGN_CURRENCY_ERROR_CODE to a bounded mec_ resource. Slice A's default maps every Result.Error to add_expense_error_save_failed.

## Merge of feature/ai-evolution 4add2466 (2026-10-28; NOT validated)
- This lane (feature/manual-entry-currency) merged origin/feature/ai-evolution 4add2466 (merge-base bcd8e921) with `--no-ff --no-commit`; plan `workflows/active/mec-merge-ai-evolution-plan.md`. Resolved without builds, tests or guards; the orchestrator stages and validates. Not committed.
- Room: this lane is v152 (`152.json` unchanged); int is v151 and did not bump. No schema change from the merge.
- MEC-side conflicts resolved as unions: `ManualExpenseRepository` imports (the 4 currency imports plus `AppConfig`), `DiagnosticReasonCode` (`MANUAL_SAVE_BLOCKED_NO_RATE` first, then int's NOTIF_/DL_/LOCK_ block), es/el strings (loan block then int block, no duplicate names), docs, ownership pins at 454.
- S3: `ManualExpenseRepositoryRecommendationTest` gets a `currencySettingsRepository` mock stubbed with `resolveHomeCurrency()` returning `HomeCurrencyResolution.Resolved(CurrencyCode.EUR)`, like `ManualExpenseRepositoryTest`.
- D-M1 = A (owner-approvals 79a): `BillReminderWorker` installment reminders use the BILL presentation (resolver plus applier, 10-param constructor). Loan-owned code; see the loan handoff entry.
- S13-a C1 now targets `DeepLinkPolicy` / `DeepLinkHost` / `DeepLinkApplier` (host branches inside `DeepLinkApplier.kt` only; `MainActivity` stays router-only). The mec pre-1a `handleIntent` host switch is gone.
- Q1 OWNER-PENDING (owner-approvals 79): the 3 enabled-path `parseDeepLink` tests in `DeepLinkParserTest.kt` (lines 152 to 196) are left in place and do not compile until the owner retires or restates them.
- S4 follow-up (MEC B1, separate post-merge commit): `AddExpenseViewModel.mapSaveError` hides `RECURRING_FOREIGN_CURRENCY_ERROR_CODE`; it fails closed. Add a bounded `mec_` string (es/el DRAFT).

## Merge review fixes (2026-10-28; NOT validated)
- Guardian verdicts on the staged merge: privacy-security-guardian ACCEPT-WITH-CONDITIONS; architecture-guardian ACCEPT-WITH-CONDITIONS. Fixes decided by the orchestrator under standing approval item 62.
- Fixes applied (test, docs and config only; no production Kotlin):
  - Privacy P3 #2: `BillReminderWorkerInstallmentTest` gains `presentation resolve cancellation after the claim releases the claim and propagates` (claim released, nothing posted, nothing marked sent, cancellation rethrown).
  - Privacy P3 #5: `LEGAL_PATHS.md` builder owner 2 now lists `sendInstallmentNotification` (S7b, D-M1 A) and notes that int's INV-12 "six composition sites" now has a seventh BILL site, so loan S13-a must re-count.
  - Architecture P3 #2: `CODEBASE_SEGMENTS.md` header v152 / 2026-10-28; added `QuickAddSection.kt`, `QuickAddUiMapper.kt` (Segment 9), `domain/receipt/statement/*` (Segment 4) and `ui/navigation/InstallmentsFeature.kt` (Segment 40).
  - Architecture P2 #1 (strengthening): `config/validation/test-shards.json` now assigns the 7 previously unassigned classes (`domain.diagnostics.*` and `domain.LifecycleWritersWriteBarrierTest` to domain-a-m; `di.RetentionModuleBatchedPurgeTest` to data). No filter was removed or narrowed.
- Q1 is still OWNER-PENDING (the enabled-path `parseDeepLink` block in `DeepLinkParserTest.kt` is untouched).
- MEC B1/B2 follow-up: once B2 adds the currency selector, the quick-add Fill must parse with the SELECTED entry currency, not `snapshot.homeCurrency` (`AddExpenseViewModel.kt:551-570`). `FOREIGN_CURRENCY_BLOCKED` (`QuickAddFillContract.kt:182`) and the `quick_add_hint_foreign` / `quick_add_flag_amount_foreign` copy (`QuickAddUiMapper.kt:194-196`, `strings.xml` ~:1977) must be revisited in that slice, with a test.
- S4 (`mapSaveError` hiding `RECURRING_FOREIGN_CURRENCY_ERROR_CODE`) is tracked as above.

## 2026-10-28: Q1 approved (owner-approvals 81a)
- The 3 enabled-path parseDeepLink tests are retired from DeepLinkParserTest.kt and restated as S13-a acceptance examples in loan-installments-handoff.md.
- The merge is now compile-eligible. The validation ladder from mec-merge-ai-evolution-plan.md is queued serially. Then reviewer-strict, then the merge commit and a push of feature/manual-entry-currency.

## 2026-10-28: catch-up merge COMMITTED 59275eac and pushed (owner-approvals 82)
- All validation PASS; the reviewer-strict conditions are applied; run IDs are in the commit message.
- Next (MEC lane, in order):
  1. S4: the AddExpenseViewModel.mapSaveError RECURRING_FOREIGN_CURRENCY_ERROR_CODE branch (mec_ string, es/el DRAFT).
  2. MEC B1/B2: the currency selector, plus the quick-add selected-currency follow-up (owner-approvals 80c).
  3. MEC C.
- Loan: S11 and S13. S13-a must use the acceptance examples (a)-(d) and clear the BLOCKING INV-1 smallest/largest item first.

## 2026-10-28: B0′ + B1 DONE (owner-approvals 83-86)
- B0′ (RF-2 refuted; picker seeds from the current date) and B1 (home-only flagged save; per-currency precision and cap; no rounding; S4 mapping; entry zone F1 b′).
- Validation, all PASS:
  - targeted vr-20261028-180859-c32b9c14;
  - runtime-ui vr-20261028-181446-a4a72908;
  - data vr-20261028-181732-22952e85;
  - architecture-contracts vr-20261028-182219-5040aef7;
  - lint vr-20261028-182305-48ffc9f6;
  - static-guards vr-20261028-182910-b8cc3b6b;
  - app-check vr-20261028-184628-a9f72d80.
- Gates: privacy ACCEPT; architecture ACCEPT-WITH-CONDITIONS (fixed); reviewer-strict ACCEPT-WITH-CONDITIONS.
- B2 REQUIRED TEST (reviewer-strict ISSUE-1): a Robolectric Compose test of the sheet's F1 wiring. The zone is cleared when the sheet leaves composition and kept across `ActivityScenario.recreate()`.
- Follow-ups: F4 capFor; P1 home code for mec_repeat_home_only (B2); P2 bounded code instead of exception.message; D-B1-2 edit precision plus myShareAmount; 3-decimal typing; HomeScreen.kt:1531 stale picker (Home owner).
- Process (owner-approvals 86): from now on there is one writer per slice (it writes its own tests) and one combined guardian. reviewer-strict runs only at merges and candidates. Sonnet for writers.
- Next: B2 (currency control, preview, blocked card; quick-add selected currency 80c; the ISSUE-1 test), then C, then loan S11, then Z.
