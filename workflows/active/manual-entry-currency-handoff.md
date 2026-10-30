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

## 2026-10-29: B2a COMMITTED (checkpoint)
- VM currency selection, preview and refresh logic, the CurrencyRateRegion model, and 35 mec_ strings (es/el DRAFT).
- CurrencyConverter gains a defaulted @IoDispatcher seam (behaviour-neutral; fixes test determinism).
- targeted vr-20261028-233743-0bb33234 PASS.
- Combined guardian ACCEPT-WITH-CONDITIONS: F1/F2 fixed; F3/F4 are B2b items.
- Shards, lint, static-guards and app-check run at the B2 candidate after B2b, when the strings get consumers.
- Next: B2b UI (sheet, CurrencySelectionControl, region composables, a callback-only MainActivity hunk, the ISSUE-1 sheet test).

## 2026-10-29: B2b status (uncommitted)
- B2b UI is written: AddExpenseSheet, CurrencySelectionControl (new), CurrencyRateRegion composables, the MainActivity callback, and 2 new strings (es/el DRAFT).
- Tests are written (AddExpenseSheetZoneSessionTest for ISSUE-1, AddExpenseSheetCurrencyUiTest, CurrencySelectionControlTest, CurrencyRateRegionContentTest).
- Combined guardian ACCEPT-WITH-CONDITIONS; F-2 (picker reopen on layout flip) is fixed.
- B2 ladder: targeted PASS vr-20261029-013405-f2492fd6, plus architecture-contracts, domain-a-m, cancellation and lint PASS.
- FAILS:
  - runtime-ui vr-20261029-013937-a32e827a: QuickAddSectionTest AppNotIdleException (an infinite animation or recomposition loop).
  - known_good_state vr-20261029-015504-3e508679: row active_db_gate INFRA, gate_exit=2 (likely a Kotlin parser trap in the B2b code).
  - static-guards and app-check fail downstream of these.
- A debugger is diagnosing. Next: fix, re-run the ladder, reviewer-strict at the MEC B2 candidate, commit. Then C (receipt review), loan S11, Z.

## 2026-10-29: MEC B2 candidate ACCEPTED and COMMITTED (B2b + review fixes)
- Failures fixed before review:
  - known_good_state active_db_gate exit 2: the scanner trap `get() = when (val region = …)` in AddExpenseViewModel was rewritten without the binding (behaviour identical).
  - The runtime-ui QuickAddSectionTest AppNotIdle was order-dependent, bisected to AddExpenseSheetZoneSessionTest (a process-static snapshot state). It moved to an activity field and the harness `close()` cancels VM scopes.
- reviewer-strict (Opus xhigh), two halves:
  - B2a ACCEPT-WITH-CONDITIONS; B2b REJECT. Both found the same MAJOR: a draft re-denominated across sessions, and confirm not discarding.
  - Fixes R-1 to R-5 per the plan dispositions; R-6 is a deviation.
  - Re-review 2/2: ACCEPT-WITH-CONDITIONS. C-1 is applied (confirmOpenExchangeRateSetting = reset then NAVIGATE; tests rewritten in place, none deleted). C-2: F-B2-A11Y-1 is a HARD GATE for the MEC 1b release.
- Validation (final tree):
  - targeted PASS vr-20261029-082747-3f0f4a6d;
  - known_good_state PASS vr-20261029-083322-cf5d66cf;
  - app-check PASS vr-20261029-083638-7c7a7dcb.
  - Earlier on this candidate: static-guards PASS vr-20261029-075030-bfb289f9; lint PASS vr-20261029-015550-c539bec0; architecture-contracts, domain-a-m and cancellation PASS.
- Follow-ups: F-B2-A11Y-1 (HARD GATE for 1b); F-B2-TYPED-REFRESH; F-B2-HOME-CHANGE; es/el sign-off for the mec_ strings, including mec_open_setting_confirm_title/body.
- Next: MEC C (receipt review; a money slice, so a writer plus a separate test-author), then loan S11, then Z.

## 2026-10-29: MEC C1 (receipt review logic) COMMITTED
- The plan's "C re-anchor and split" amendment is persisted (D-C-1..4 under item 62).
- Writer and separate test-author; combined guardian ACCEPT. Its LOW items are applied: no unselectable OCR token in debug data; a guard-scope comment.
- Test-found defects fixed: INV-10 (an unparseable pick is ignored) and INV-3 (an AI-applied date or total re-evaluates the region, so quick-save is blocked on AmountInvalid). The debug ParsedTransaction is skipped for an unselectable token (its init requires [A-Z]{3}).
- Validation:
  - targeted PASS vr-20261029-100141-ec91ab50;
  - runtime-ui PASS vr-20261029-100543-2c95119c;
  - domain-n-z PASS vr-20261029-100845-62a4f638;
  - known_good_state PASS vr-20261029-095350-b0a4e5fe;
  - db_access PASS vr-20261029-095709-86267dd8.
- Next: C2 (ReceiptScanScreen UI; the MainActivity callback-only hunk). Then the MEC C candidate: lint, architecture-contracts, static-guards, app-check, and reviewer-strict at the candidate.

## 2026-10-29: MEC C ACCEPTED and C2 COMMITTED (receipt review UI + review fixes)
- C2: CurrencySelectionControl replaces the private picker; rate region under the amount; the kept line under the card (not live); Save, quick-save and the dialog confirm blocked with reasons; open-setting confirm = reset then navigate; MainActivity callback-only.
- Writer-time fixes:
  - the real home code (not first-option);
  - an amountError text for AmountInvalid;
  - inline home-currency precision.
- Guardian (combined) ACCEPT-WITH-CONDITIONS: dialog reason, exhaustive AmountCheck mapping.
- reviewer-strict at the C candidate: ACCEPT-WITH-CONDITIONS.
  - F1 kept-line placement and F2 no carry-over / pending proposal while home is unresolved: fixed.
  - Also fixed: no raw OCR token in the item breakdown currency.
  - F3 (tax-inclusive override, pre-existing) is ROUTED to the receipt owner as F-C-TAX-OVERRIDE, with RF-6 (D-C-4) and RF-7.
- Validation (final tree):
  - targeted vr-20261029-113240-fd6780a6;
  - known_good_state vr-20261029-113829-ed83f0c1;
  - lint vr-20261029-114145-c90e1e96;
  - architecture-contracts vr-20261029-114717-ddf7d128;
  - static-guards vr-20261029-114805-ea98c171;
  - app-check vr-20261029-120509-b6aaf271.
  - All PASS.
- Follow-ups: F-C-REGION-DEDUP, F-C-HOME-PRECISION-PARITY, F-C-TAX-OVERRIDE (receipt owner), RF-6/RF-7 (receipt owner), F-B2-A11Y-1 (a HARD GATE for 1b). Note: `parsedReceipt.currency` still holds the raw token in state (pre-existing; not displayed).
- Next (MEC lane): loan S11, then Z closeout.

## 2026-10-29: F-B2-A11Y-1 CLOSED (MEC 1b HARD GATE) and COMMITTED
- previewAnnounceKey is bumped only on a currency, date or home change (and the receipt AI date apply), never on keystrokes, and is monotonic across reset (add-expense and receipt). The preview line is a polite live region only after a key change. Save stateDescription 'Fix the amount' (mec_save_disabled_amount, es/el DRAFT) for AmountInvalid; the receipt screen keeps its specific amountError.
- Combined guardian ACCEPT-WITH-CONDITIONS: F1 (receipt reset dropped the key) FIXED plus a test. F2 (latch writes plain fields in composition; worst case a silently skipped announcement) DECLINED for now and recorded as follow-up F-A11Y-LATCH-PURE for the MEC 1b candidate review. F3 noted (installment toggle does not bump; not a spec trigger).
- Validation:
  - targeted vr-20261029-174359-13998ace;
  - runtime-ui vr-20261029-174716-8f273b41;
  - lint vr-20261029-175047-e2ce6564;
  - known_good_state vr-20261029-175436-33dcec1f;
  - F1 targeted vr-20261029-180239-1b51e504.
- Next (MEC lane): Z closeout. MEC 1b release still needs the es/el sign-off of the mec_ strings.

## 2026-10-29: Z closeout (docs, one androidTest, and one fail-closed fix in CurrencyFractionDigits found by H-4)
Written by the Z writer; NOT validated. The inventory is refreshed (`docs/architecture/CODEBASE_INVENTORY.md`, Drift Sync 2026-10-29). The orchestrator owns the validation run and the final fresh-session reviewer-strict acceptance (no discovery role).

### Slice status (all slices)
| Slice | Status |
|---|---|
| S0 | DONE 2026-10-23 |
| D | ACCEPTED 2026-10-16 |
| A1 | DONE 2026-10-23 |
| A2 | DONE 2026-10-23 |
| B0 / B0' | B0' DONE 2026-10-28 (RF-2 refuted; no date-picker fix needed) |
| B1 | DONE 2026-10-28 |
| B2 (B2a + B2b) | ACCEPTED and COMMITTED 2026-10-29 |
| C (C1 + C2) | ACCEPTED and COMMITTED 2026-10-29 |
| F-B2-A11Y-1 | CLOSED and COMMITTED 2026-10-29 (MEC 1b hard gate cleared; F-A11Y-LATCH-PURE remains for the 1b candidate review) |
| Loan S11a/b/c (AddExpense installment section) | DONE on this lane (loan handoff owns the detail) |
| Z | COMMITTED 2026-10-29 (validated; H-4 PASS on device; reviewer-strict deferred to the MEC 1b candidate or merge review per item 86) |

### H-1 status
B2, C and loan S11 are UNRELEASED until MEC 1b (ECB history behind the rate setting, N1/H-1). The release track must not ship them before 1b. MEC 1b still needs the es/el sign-off below.

### H-4 (instrumented ICU digits check)
Taken as YES: one `connected-test-class` run on AVD `wave5-api35`: `com.yourname.expensetracker.domain.currency.CurrencyFractionDigitsDeviceTest`. It pins the 28 catalog digit values (0 for JPY, KRW, ISK; 2 for the rest, HUF included) through `CurrencyAmountPolicy.fractionDigits`, plus the fail-closed `ZZZ` case (null, `DigitsUnavailable`). No new dependency, no network, no permission. If the run is not made, this becomes a follow-up and the plan's R4 row stays open.

### Consolidated follow-ups (17)
1. F-B2-TYPED-REFRESH: refresh behaviour after a typed amount/date change in the add-expense region.
2. F-B2-HOME-CHANGE: reaction of the AddExpense draft/region to a home-currency change while the sheet is open.
3. F-C-REGION-DEDUP: the add-expense and receipt rate-region logic duplicate each other; merge to one owner.
4. F-C-HOME-PRECISION-PARITY: receipt inline home-currency precision vs the AddExpense rule.
5. F-C-TAX-OVERRIDE: tax-inclusive total override (pre-existing, C guardian F3) to the receipt owner.
6. RF-6 (D-C-4): receipt owner (H-5: fixed in C only if confined to ReceiptScanViewModel.kt:416; otherwise this follow-up).
7. RF-7: `ReceiptParser.kt:690` to the receipt owner.
8. F-A11Y-LATCH-PURE: the announce latch writes plain fields in composition; make it pure (review at the 1b candidate).
9. 3-decimal homes (BHD-style): typing and display for homes with 3 digits.
10. AE-36 edit precision (D-B1-2): precision check on the edit path.
11. `myShareAmount` precision (INV-13; A2 deferred; no slice owner yet).
12. The other "(EUR)" labels that are hard-coded in amount fields outside the MEC screens.
13. Prefill and suggestion "%.2f" formatting (not currency-digits aware).
14. The quick-add 1M flag (`FOREIGN`/cap flag still uses the flat 1,000,000; align with `CurrencyAmountPolicy.cap`).
15. Spanish native review (es DRAFT for all keys below).
16. Parser and notification amount caps (G2-3) to their owners (`AmountUtils`, `AppParserRegistry`, notification parser, `AppConfig`; RF-4 duplication).
17. RF-5 + OD-3 alignment to money-correctness (RF-5 itself was resolved in MC 1b; the OD-3 near-midnight analytics vs stored-conversion alignment remains).
Also still open from earlier notes (not in the requested list): RF-1b (CREATE_ATTEMPTED dedupeKey plaintext), F4 `capFor`, P1 home code for `mec_repeat_home_only`, P2 bounded code instead of `exception.message`, HomeScreen.kt:1531 stale picker (Home owner), the R23 / release-track items above, G2-20 (unsent).

### OWNER es/el sign-off list (51 keys; all DRAFT in both `values-es` and `values-el`)
`add_expense_installments_*` (9, loan S11b/S11c): `add_expense_installments_repeat_exclusive`, `add_expense_installments_not_spending`, `add_expense_installments_preview_summary`, `add_expense_installments_preview_total`, `add_expense_installments_preview_residual`, `add_expense_installments_preview_ends`, `add_expense_installments_preview_financing_less`, `add_expense_installments_preview_needs_amount`, `add_expense_installments_past_due`.

`mec_*` B1 (5): `mec_block_home_unresolved`, `mec_amount_no_decimals`, `mec_amount_max_decimals`, `mec_amount_too_large`, `mec_repeat_home_only`.

`mec_*` B2a/B2b/C/A11Y (37): `mec_control_label`, `mec_control_cd`, `mec_control_unset`, `mec_control_readonly_cd`, `mec_sheet_search`, `mec_sheet_home`, `mec_sheet_other`, `mec_sheet_no_results`, `mec_sheet_installment_note`, `mec_installment_unset`, `mec_preview_checking`, `mec_preview_ecb`, `mec_preview_other`, `mec_preview_rate_only`, `mec_block_none`, `mec_block_stale`, `mec_block_unsupported`, `mec_block_off_hint`, `mec_block_alt_hint`, `mec_action_refresh`, `mec_action_refreshing`, `mec_action_open_setting`, `mec_action_change_currency`, `mec_refresh_still_blocked`, `mec_refresh_offline`, `mec_refresh_failed`, `mec_refresh_home_unavailable`, `mec_save_disabled_rate`, `mec_save_disabled_currency`, `mec_save_disabled_amount`, `mec_repeat_cleared`, `mec_receipt_unmatched`, `mec_receipt_not_detected`, `mec_receipt_kept`, `mec_declaration_nothing_saved`, `mec_open_setting_confirm_title`, `mec_open_setting_confirm_body`.

Total: 9 + 5 + 37 = 51 (42 `mec_*` + 9 `add_expense_installments_*`); the count equals the keys in `values/strings.xml`. Other DRAFT keys in those files (quick_add_*, loan blocks, app-lock) belong to their own features' sign-off lists.

### Z validation and H-4 result (2026-10-29; orchestrator)
- H-4 first device run FAILED as designed: Android ICU returned 2 fraction digits for the unknown code `ZZZ` (the JVM throws), so `CurrencyFractionDigits` was not fail-closed on device.
- Fix: `CurrencyFractionDigits.of(code)` returns null for any code absent from `Currency.getAvailableCurrencies()` (lazy set). `CurrencyFractionDigitsTest` adds KWD -> 3 and QQQ/AAA -> null. This is a fail-closed strengthening, not a behaviour change for any catalog currency.
- Ladder on the final fingerprint, all PASS:
  - targeted `*CurrencyFractionDigits*,*CurrencyAmountPolicy*,*Currency*,*Money*`: vr-20261029-192053-b21a24d4;
  - connected `CurrencyFractionDigitsDeviceTest` on wave5-api35: vr-20261029-192810-7c25ea23 (H-4 closed; plan row R4 closed);
  - static-guards: vr-20261029-192914-bad99aa2;
  - app-check: vr-20261029-194632-821acce2.
- reviewer-strict: per owner items 86/87, it runs at the MEC 1b candidate or merge review, which must include this Z diff.
- Next (MEC lane): loan S13 (see the loan plan's S13 re-anchor amendment; owner-approvals 91).

## 2026-10-30: MEC 1b design started (loan S13 done on this lane, aa397b95)
- Explorer finding: 1b is framed only. The frame is in brief :38-39, critique :37/:54/:60 and domain-spec §10 :301-310. The fetch is on the same host (www.ecb.europa.eu) behind the R23 setting, with no new permission and no schema change expected. Open items: the endpoint, retention keyed on validDate, the trigger UX, outcomes and codes, parser caps, F-B2-HOME-CHANGE.
- Running in parallel, with disjoint write sets: domain-modeler (Opus) appends "1b - ECB 90-day history (domain rules)" to domain-spec.md, and ux-flow-designer (Opus) appends "1b - Rate history download" to ux-flows.md.
- Next: impact-mapper (tier), then the orchestrator gate under owner-approvals 92 (same host; payload and fetch-timing privacy review by privacy-security-guardian), then a feature-planner slice plan.
- 2026-10-30: the 1b impact map is persisted (docs/features/manual-entry-currency/impact-map-1b.md): T3; schema NONE; no permission; same host; no URL allowlist. Gate decisions D-1b-1..6 are in owner-approvals 94. The S0 docs reconciliation is running.
- **VG-1b-1 CONFIRMED (2026-10-30, ECB pages fetched):**
  - The official page links "XML (last 90 days only)" at https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml. Same host, HTTPS, 200, text/xml. Plain http returns a 301 to the same https path, so the app calls https directly.
  - Size 69,509 B (~68 KB), as a single line with no whitespace. The daily file is tab-indented with single quotes, so the parser must not depend on formatting.
  - Root `gesmes:Envelope` with the gesmes and eurofxref namespaces; structure Cube > Cube@time > Cube@currency/@rate.
  - 64 publication days (not 90), newest first, 2026-08-03..2026-10-29, 29 currencies per day.
  - The latest day equals the daily file's date (2026-10-29).
  - Caps check against INV-1b-9 (512 KiB, 70 days, 100-day span): size OK (~7.5x headroom); 64 days <= 70 (only ~6 days of headroom, so S1 must confirm the day cap against the maximum weekdays in 90 calendar days, ~66); span 87 days <= 100.
  - Row count: 29 + EUR = 30 currencies, so cross pairs = 30 x 29 = 870 per day, x 64 = ~55,700 rows. The spec estimated ~39k on 25 currencies. S1 must size the batch write and the test for ~56k rows (one transaction; performance budget).
- 2026-10-30: 1b S0 docs reconciliation DONE: domain-spec 1b (21 INV, 25 AE; Cancelled outcome; D-1b-5 mapping) and ux-flows 1b (RateRemedy names, refresh-first, one "amount cleared" note, the Currency-management row DEFERRED); no `[ALIGN]` markers remain. Derived calls accepted: AlreadyInProgress is presented as FAILED (the button is disabled while running); the home-change note shows in both cases; a cancel during the commit leaves all rows or none. COMMITTED with impact-map-1b.md.
- Next: the 1b slice plan (feature-planner) and the privacy-security-guardian design review (payload size, fetch timing, the ~56k-row bulk write) in parallel; then S1.
- 2026-10-30: 1b S1 (data layer) DONE.
  - Writer, plus a follow-up that populates Blocked.latestStoredPairDate via the ExchangeRateStore port (INV-1b-5); two test-authors.
  - Combined guardian ACCEPT-WITH-CONDITIONS. Fixed:
    - F1: parity test against the daily path;
    - F3: home-currency precheck before any connection;
    - F4: KDoc;
    - F5: `historyConnectionOpener` test seam, because mocking URL hung a run (vr-20261030-043455-197fcd9c TIMEOUT).
  - Ladder fixes:
    - the atomicity test seeded validDate 0, rejected by CURR-70F-04 (test fix);
    - G-CANCEL-01 on the gate catch: `ensureActive()` added;
    - db_access / known_good_state INFRA came from DB_DAO_SCOPE_UNRESOLVED at `connection.disconnect()` (it collides with BankConnectionDao.disconnect): fixed by typing `val connection: HttpURLConnection`. No guard, allowlist or policy edit.
  - Final ladder, all PASS:
    - targeted vr-20261030-055300-9a81d944;
    - db_access vr-20261030-061755-881c3206;
    - known_good_state vr-20261030-062058-5e213491;
    - data vr-20261030-062500-514c8874;
    - domain-a-m vr-20261030-063202-e15db9d3;
    - cancellation vr-20261030-063336-662b4a52;
    - static-guards vr-20261030-063354-84e54589.
  - COMMITTED. Dormant (no caller). Next: S2 (add-expense VM + UI).
