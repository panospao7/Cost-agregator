# Feature plan: manual-entry-currency (increment 1; 1b framed only)

Author: feature-planner (stage 6, read-only). Persisted by the orchestrator. Date 2026-10-16.
Status: PLAN APPROVED 2026-10-16 (Gate 3; header corrected 2026-10-21 by orchestrator, see handoff). No `app/` edits until S0 passes.

- Risk tier: **T3 every slice** (money; TLC + result contracts; receipt lifecycle; loan plan) (G2-19).
- Basis: `docs/features/manual-entry-currency/impact-map.md` (trusted), `gate2-decisions.md` (G2-1..G2-19 DECIDED = Recommended; G2-20 NOT sent), `domain-spec.md` (INV-1..23, AE-1..38, OD-1..5, RF-1..7), `ux-flows.md` §2–§15, handoff N1/N2; format per `workflows/active/nl-quick-add-plan.md`.
- Schema: **NONE** (no Room version/entity/DAO/migration/snapshot; no `exchange_rates` cleanup, INV-22; no migration tests/RMG; any need → N4).
- Legal paths: no new write path. Manual: VM → `ManualExpenseRepository.addManualExpense` → `createExpenseDbOnlyV2` → TLC → `ExpenseDao.insertAtomic()`. Receipt: `ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt` → TLC. New read-only rule owner `RateEligibilityEvaluator`. Refresh only via existing user-started `CurrencyRatesRepository.refresh(home)`.
- Roots: `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`, `res` = `app/src/main/res/values{,-el,-es}/strings.xml`. RS reviewer-strict, AG architecture-guardian, PSG privacy-security-guardian.
- Validation: validation-runner only, serial: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <p> [-TestFilter "<f>"] [-Shard <s>] [-GuardId <g>]`; poll; RUNNING ≠ failure; missing/stale/errored ≠ PASS; one filter per run unless S0 D-14 confirms multi-pattern.


## Amendment 2026-10-16 (money-correctness Gate 2 G2-1, owner-approved)
- A1 dependency becomes "money-correctness 1b landed". `CurrencyAmountPolicy.fractionDigits` DELEGATES to the single fraction-digits accessor
  created by money-correctness 1b in `domain/core/money`; A2's snapshot uses money-correctness's `roundSnapshot` (HALF_EVEN, BigDecimal.valueOf,
  one-minor-unit floor) — MEC must not create its own digits accessor or rounding (MEC INV-12 owner = money-correctness helper).
- After money-correctness 1b, unconverted no-rate rows store `baseCurrency=""` (not 'EUR') → MEC domain-spec AE-21, AE-29, §5 updated at MEC S0.
- Order: ai-fixes → money-correctness 1a → 1b → MEC S0 → A1 → A2 → …

## Slice table
| ID | Title | Tier | Prod files | Depends on | Reviewers | Key FG |
|---|---|---|---|---|---|---|
| S0 | Pre-flight (no `app/` edits) + RF-2 debugger check | — | 0 | Release 1.0 accepted; `release-1.0-wt` merged (R16, R23 p1); ai-fixes landed | — | — |
| D | Loan convergence handoff (docs only) | T3 (docs) | 0 | Gate 3 (parallel with A1; disjoint) | RS + AG | 26 |
| A1 | Pure domain: `CurrencyAmountPolicy`, `CurrencySelectionPolicy`, `RateEligibilityEvaluator`, preview use case + Money/Currency docs | T3 | 4 new + 3 docs | S0; money-correctness landed or G2-2 | RS + AG + PSG | 17, 18, 19, 20, 26 |
| A2 | Contracts + enforcement: request flag, `rateRefusal`, TLC flagged branch, validator cap/precision, manual/review caps, RF-1, setter allow-list guard + Expense docs | T3 | 6 mod (+≤2 new types) + 3 docs | A1; loan S3/S6a not in flight on TLC | RS + AG + PSG | 10, 13, 14, 16, 17, 18, 19, 20, 26 |
| B0 | *(conditional, H-3)* RF-2 date-picker zone fix | T3 | 1 | S0 confirms RF-2 + H-3 = fix here | RS + AG | 17 |
| B1 | Add-expense home-only flagged save: setter #1, VM rounding + cap removal, entry zone, home-unresolved mapping, "(€)"/"Euro" label | T3 | 3 + res | A2; nl-quick-add inc 1 (A, D, C, F) landed | RS + AG + PSG | 10, 12, 16, 17, 19, 20, 26 |
| B2 | Add-expense currency control, preview, blocked card, Repeat hiding, refresh/open-setting, shared control composables, `mec_` strings | T3 | 4–5 (2 new) + res | B1 (+B0); N1 resolved (H-1) | RS + AG + PSG | 10, 12, 16, 17, 19, 20, 26 |
| C | Receipt review: setter #2, shared control, preview, `RateNotEligibleException`, `SaveReceiptResult` variant, quick-save surface, RF-6 | T3 | 3–4 + res | A2, B2 | RS + AG + PSG | 10, 12, 15, 16, 19, 20, 26 |
| Z | Closeout + bundle acceptance gate | — | 0 | C | RS final (fresh session) | 19, 20, 26 + all |

Global position (G2-1): Release 1.0 (R16, R23 p1) → ai-fixes → money-correctness (if specced; else G2-2) → **S0 → A1 → A2** → loan S1..S10 (S3/S6a after A2) ∥ nl-quick-add inc 1 → **B0? → B1 → B2 → C** → loan S11 (general control) → **Z** → nl-quick-add inc 1b → MEC inc 1b. D right after Gate 3 so loan S0 can use it.
Shared files (sequential only): TLC, `CreateExpenseRequest.kt`, `CreateExpenseResult.kt` (A2 only; serialise vs money-correctness, loan S3/S6a); `ManualExpenseRepository.kt` (A2 → B1); AddExpense VM/Sheet (B1 → B2; after nl-quick-add; before loan S11); ReceiptScan VM/Screen + `ReceiptLifecycleCoordinator.kt` (C only); `res` ×3 (B1 → B2 → C; serialise vs ai-fixes, nl-quick-add, loan); LEGAL_PATHS/ENGINE map/SEGMENTS (A1 → A2; serialise vs ai-fixes, nl-quick-add C, loan); `test-shards.json` no edit.
Why split: (a) ~11 prod files → A1 (no production consumer, behaviour-neutral) + A2. (b) → B1 (setter + rounding removal together per G2-8; releasable alone since home-only saves never read rates) + B2 (only slice exposing foreign entry; gated by N1/H-1).

## Slices

### S0 — Pre-flight (no production edits)
1. Base: Release 1.0 accepted; `release-1.0-wt` merged into `bug-fixes`; R16 (incl. R16c) + R23 part 1 landed; ai-fixes landed; branch `feature/manual-entry-currency`. Record from R23 part 1: setting name/key + **default**, read API, whether `CurrencyRatesRepository.refresh` is consent-gated, bounded refresh outcomes (incl. `HOME_CURRENCY_QUOTE_UNAVAILABLE`), **whether a periodic refresh exists**. **N1 rule:** periodic refresh → inc 1 ships alone; else inc 1 ships with 1b (H-1 applies to B2/C). No setting/no gate → N8.
2. money-correctness status (G2-2): landed → rebase A2; not specced → A2 first; in flight on TLC `:529-585` or snapshot rounding → N7.
3. Loan: confirm no installment code (`declareWithNewExpense`, `InstallmentPlan` = 0 hits); record S3, S6a, S11 status (S3/S6a in flight on TLC or S11 started on VM/Sheet → N10); record planned coordinator path `A/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt` (rename → N16).
4. nl-quick-add inc 1 status (B1 prerequisite): CancellationSafety KNOWN_VIOLATIONS entry for `AddExpenseViewModel.kt` removed; whether quick-add guards/tests pin `quick_add_hint_foreign` / `quick_add_flag_amount_foreign` (→ N17).
5. Re-verify cited lines (D-1); record deltas; material drift → stop.
6. RF-2 (G2-17): read-only `debugger` check of `AddExpenseSheet.kt:841-855` (Material3 `selectedDateMillis` UTC midnight + `ZoneId.systemDefault()` west of UTC) → confirmed/refuted → H-3.
7. Answer D-1..D-18.
8. Baselines (serial). Required: `static-guards`; `registered-guard -GuardId db_access` then `db_artifact_sync`; `lint`; `unit-test-shard` `domain-a-m`, `domain-n-z`, `data`, `runtime-ui`, `architecture-contracts`. Recommended: `targeted-unit-test` `"*TransactionLifecycleCoordinator*"`, `"*AddExpenseViewModel*"`, `"*ReceiptScanViewModel*"`, `"*ReceiptLifecycleCoordinator*"`, `"*CurrencyConver*"`.
- Done when: base confirmed; N1 fact recorded; deltas recorded; RF-2 verdict; D-items answered; baselines recorded; fired N-items escalated.

### D — Loan convergence handoff (docs only; G2-6 edits applied by the loan planner at loan S0)
- INV-5, INV-18 (AE-28), AE-29; N2.
- Append "manual-entry-currency convergence" section to `workflows/active/loan-installments-handoff.md` (coordinate; file already has orchestrator notes):
  1. S11: remove EUR/USD segmented control, plan-currency row, OD-14 note; use B2 general control in `mode = INSTALLMENT` (options ∩ {EUR, USD}; UX-Q-6 Unset, restored on switch-off); S11 depends on MEC B2 (and C per G2-1); S11 "FX unavailable" copy → shared `mec_` copy.
  2. D-8 answered: TLC refuses only flagged creates (F18); `declareWithNewExpense` sets `userPresentRate = UserPresentRateRequirement(entryZone)`; on `ValidationFailed.rateRefusal` the plan coordinator returns its typed refusal before inserting the plan → whole declaration aborts (INV-18).
  3. S7a / RF-3 (corrected by review: branch + `recordDueEntries` live in loan S7a, plan :351): `AUTO_RECORD_DEFERRED_FX` assumes a refusal that never happens unflagged; auto-record never sets the flag, created with today's defaults (AE-29). Rec: drop the FX deferral branch; any loan-local deferral must call `RateEligibilityEvaluator`; re-assess loan risk R15.
  4. Rec: S6a sets the flag inside `declareWithNewExpense` already (no production caller before S11; INV-18 testable at coordinator level); `recordDueEntries` (S7a) never sets it (AE-29 null-flag test in S7a).
  5. Serialise after A2: S3 (TLC + `ExpenseDao` + exhaustive `when (request.source)` `:508-526` via `INSTALLMENT_PLAN`) and S6a (consumes `userPresentRate`/`rateRefusal`) — corrected by review.
  6. Setter allow-list (A2) already pins the loan coordinator path; rename needs approval (N16); file-level guard → "declare only, never auto-record" enforced by AE-29 test + RS.
  7. Loan UX doc follow-up for UX-Q-6.
- Gates RS + AG; no validation. Done when: section exists, loan planner acknowledges at loan S0, loan plan edited under G2-6.

### A1 — Pure domain policies + evaluator (T3)
- INV-1, 2, 3, 10, 11 (policy), 12, 14 (definition), 15 (domain product), 20 (read-only, no fetch), 22. AE-1, 2, 4–12, 13 (Home: rate table never read), 15–17 (policy), 18 (table), 25, 27, 34 (zone parameter), 37. All 28 catalog digits pinned.
- Source (new, `A/domain/currency/`, shard `domain-a-m`):
  - `CurrencyAmountPolicy.kt` — `fractionDigits(code)` over `java.util.Currency.defaultFractionDigits`; `cap(code)` static table (spec §6; non-catalog 1,000,000); `check(parsed, code)` → Ok / NotPositive / PrecisionExceeded(digits) / AboveCap(cap) on parsed value (`stripTrailingZeros().scale()`), never rounds; non-ISO fallback documented + tested (spec gap → N14).
  - `CurrencySelectionPolicy.kt` — `selectable(home, mode)` = `activeCatalog` − `NO_RATE_SOURCE{BGN, RUB}` ∪ {home}; INSTALLMENT = ∩ {EUR, USD}; `resolveProposal(...)` → `Unset(PROPOSAL_NOT_SELECTABLE | NONE_PROPOSED)`; `SupportedCurrency.isActive` untouched (G2-4).
  - `RateEligibilityEvaluator.kt` — `RateEligibility` (Home / Eligible(rate, rateDate, ageDays, path, sourceLabel) / Stale / None / Unsupported); `@Inject constructor(CurrencyConverter)`; local date in `entryZone` vs UTC-midnight bound via `convertOutcome(…, RateBasis.TRANSACTION_DATE, atMillis, StaleRatePolicy.None)` (G-MONEY-18/19); age in calendar days; via-EUR older leg (F1); ECB label only if every leg `source == "ecb"`; no `now()`/clock/`lastUpdated`/logging/network; no converter change.
  - `UserPresentRatePreview.kt` (provisional) — read-only use case for VMs (FG-12) → `ConvertedPreview(amountInHome = parsed × Eligible.rate unrounded; home; rateDate; sourceLabel)`; no formatting/logging.
- Docs (with symbols): `CODEBASE_SEGMENTS.md` Segment 16 (`:353-397`); `LEGAL_PATHS.md` Money/Currency (`:534`) "ELIGIBLE RATE for user-present save → RateEligibilityEvaluator (calendar date in entry zone, ≤ 7 days, older leg)", "AMOUNT digits/cap → CurrencyAmountPolicy"; `ENGINE_INTERACTION_MAP.md` `:14` note + new RateEligibilityEvaluator row (read-only; follows CurrencyConverter CRITICAL).
- Tests (`T/domain/currency/`): NEW `CurrencyAmountPolicyTest.kt` (28 digits; cap table; AE-15/16/17/18 policy parts); NEW `CurrencySelectionPolicyTest.kt` (AE-25, 27; HRK/BGN/RUB omitted; home always present; INSTALLMENT); NEW `RateEligibilityEvaluatorTest.kt` (AE-1..12, 13, 25, 34, 37; DST AE-10; `America/New_York` AE-11; injected zone only); NEW `UserPresentRatePreviewTest.kt` (AE-1 product; AE-22 inputs; AE-38 domain part — same operands/order as A2 snapshot); `CurrencyConversionTest.kt` pins only.
- Behaviour-neutral (no production consumer). Gates RS, AG, PSG (no logging/clock).
- Validation: `targeted-unit-test` `"*CurrencyAmountPolicy*"`, `"*CurrencySelectionPolicy*"`, `"*RateEligibilityEvaluator*"`, `"*UserPresentRatePreview*"`, `"*CurrencyConver*"`; `unit-test-shard -Shard domain-a-m`; `static-guards`.
- Done when: AEs green; no `now()`/`systemDefault` in new files; money/time guards pass; docs reference existing symbols.

### A2 — Contracts + TLC enforcement + caps + RF-1 + guard (T3, setter-less per G2-8)
- INV-4, 5 (reader + guard), 6, 7, 8, 9, 13 (validator, flagged), 14 (validator + manual + review approval, G2-3), 16 (repo rejection), 19 (refusal diagnostic + RF-1), 21. AE-3, 13, 14, 18, 19, 20, 21, 22, 26 (repo), 29 (unflagged analogue), 30 (TLC), 31, 33, 35.
- Source:
  - `CreateExpenseRequest.kt` (`:74`): `userPresentRate: UserPresentRateRequirement? = null`; `UserPresentRateRequirement(entryZone: ZoneId)`; 12 sites source-compatible.
  - `CreateExpenseResult.kt` (`:10`): `ValidationFailed(errors, rateRefusal: RateRefusal? = null)` + `RateRefusal`, `RateRefusalReason`, `RateRefusalCodes` (`RATE_NOT_ELIGIBLE_*`) (≤2 new sibling files).
  - `TransactionLifecycleCoordinator.kt`: evaluator from existing deps (C-1; rec defaulted ctor param so 8 TLC test files unchanged, else N11). Flagged branch inside `:529-585`, after validation + provenance, **before dedup `:587+` and any insert**: home `Failed` → `HOME_CURRENCY_UNRESOLVED`; home currency → identity snapshot; `Eligible` → snapshot from that single read (INV-7; never `convertAsOf`); else `ValidationFailed([code], rateRefusal)`, empty post-commit batch. Refusal reuses `CREATE_VALIDATION_FAILED` best-effort event (`:406-427`), no new `LifecycleEventType` (FG-14). One `MANUAL_SAVE_BLOCKED_NO_RATE` diagnostic via existing controlled sink (sub-reason none/stale/unsupported/home_unresolved; origin `request.source.name`; D-7, N13). Unflagged `:554-578` byte-identical (INV-9). **RF-1 (G2-11):** `CREATE_ATTEMPTED` reason at `:397` → bounded codes only (no merchant/amount/currency) for all creates (N12). CE rethrown. Events commit, no row (G2-12).
  - `TransactionValidator.kt` (`:87-93`): raw `> 1_000_000` → `CurrencyAmountPolicy.cap(currency)` for all creates (G2-3); `AMOUNT_PRECISION_EXCEEDED` only when flagged.
  - `ManualExpenseRepository.kt` (`:114`): cap → policy; INV-16 (currency ≠ resolved home and recurrence set → bounded error before TLC); **no setter**.
  - `ReviewQueueRepository.kt` (`:157`): cap → `CurrencyAmountPolicy.cap(resolvedCurrency)` (`:147-154`; G2-3).
  - Routed, not edited: `AppConfig.MAX_TRANSACTION_AMOUNT`, `NotificationProcessingPipeline.kt:962/997/1135`, `AppParserRegistry.kt:69`, `AmountUtils.isValidAmount`, batch receipt `:1174`.
- Docs: `LEGAL_PATHS.md` Expense Mutations (`:21`) §18 "USER-PRESENT RATE REQUIREMENT" + FORBIDDEN lines; `ENGINE_INTERACTION_MAP.md` `:58-76` TLC snapshot note; `CODEBASE_SEGMENTS.md` Segment 9 (`:243`).
- Tests: extend `TransactionLifecycleCoordinatorTest.kt` (AE-3, 13, 14, 19, 21, 22, 31, 33; dedup never queried on refusal; exactly one converter read when flagged; `convertAsOf` never called flagged; unflagged regression suite; RF-1 bounded reason flagged + unflagged); extend `T/scenarios/TransactionLifecycleCoordinatorDbContractTest.kt` (AE-20: exactly two events, no expense, no payload, one diagnostic increment); NEW `T/domain/transaction/validation/TransactionValidatorTest.kt` (AE-18, 19, per-caller cap relaxation); NEW `T/data/repository/ManualExpenseRepositoryTest.kt` (INV-16/AE-26; cap via policy; flag not set); ReviewQueueRepository test extend/new (D-14: KRW 1,500,000 approvable; EUR 1,000,000.01 refused); NEW `T/architecture/ManualEntryCurrencySetterAllowListGuardTest.kt` (AE-35; G2-10: main sources may construct `UserPresentRateRequirement(` or assign `userPresentRate =` only in `ManualExpenseRepository.kt`, `ReceiptScanViewModel.kt`, `domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt`; declaring file's `= null` excluded; absent file passes; self-test fixture; **allow-list fixed now, never grows**); importer/group/bank tests pinning raw 1M for weak currencies (D-17): update only expectations following directly from the G2-3 table and report the list; anything else → stop.
- TLC stays sole create owner; refusal inside it; no production setter → no user-visible refusals. Depends on A1; money-correctness not in flight (N7); loan S3/S6a not in flight (N10). Gates RS, AG (guard, DI, docs), PSG (RF-1, INV-19).
- Validation: `targeted-unit-test` `"*TransactionValidator*"`, `"*TransactionLifecycleCoordinator*"`, `"*ManualExpenseRepository*"`, `"*ReviewQueueRepository*"`, `"*ManualEntryCurrencySetterAllowListGuardTest*"`, `"*CurrencyConver*"`; `unit-test-shard` `domain-n-z`, `data`, `architecture-contracts`; `registered-guard` `db_access` then `db_artifact_sync` (unchanged vs S0); `static-guards`.
- Done when: INV-9 regression green; refusal writes exactly two bounded events and no row; `db_access` unchanged; guard green with zero production setters (RS greps); RF-1 confirmed by PSG; docs with symbols.

### B0 — conditional (only if S0 confirms RF-2 and H-3 = fix in MEC)
- Correct local calendar date from picker (prerequisite for AE-11, INV-2 on manual entry). Source: `AddExpenseSheet.kt:841-855` only. Test: picker-date conversion in `T/ui/screens/addexpense/` (east/west of UTC, DST). Gates RS + AG. Validation: `targeted-unit-test "*AddExpense*"`; `-Shard runtime-ui`; `lint`. Done when `America/New_York` picks keep the chosen day; nothing else changes.

### B1 — Add-expense home-only flagged save (T3; G2-8, G2-9)
- INV-4, 5 (setter #1), 13 (VM inline; `:367-370` rounding removed), 14 (VM `:297` → policy), 19, 23. AE-13, 14, 15 (JPY-home "1500,50"), 19, 32, 33, 34, 36. G2-9: home "12.345" → inline error.
- Source: `AddExpenseViewModel.kt` (remove `:297`, `:367-370`; inline `CurrencyAmountPolicy.check` on parsed value; capture `entryZone` once at sheet open (AE-34) → repo; map `HOME_CURRENCY_UNRESOLVED` + `AMOUNT_PRECISION_EXCEEDED` to bounded `UiText`; no message parsing/amount logging; edit branch unchanged, precision follows row currency (INV-23); CE rethrown; no new flagged catch). `AddExpenseSheet.kt` (amount `supportingText` precision/cap errors; remove hard-coded "(€)"/"Euro" label + content description, use home symbol `:202-209`; home-unresolved refusal on existing assertive error card). `ManualExpenseRepository.kt` (`addManualExpense(..., entryZone)` always sets `userPresentRate = UserPresentRateRequirement(entryZone)` — setter #1; typed refusal). `res` ×3 (`mec_` precision/cap/home-unresolved; owner reviews Greek).
- Tests: `AddExpenseViewModelTest.kt` (EUR "12.345" → PrecisionExceeded, text kept; JPY-home "12.50" rejected; cap via policy; AE-14, 33, 34, 36); `AddExpenseViewModelStressTest.kt`; `ManualExpenseRepositoryTest.kt` (flag always set with passed zone).
- Depends on A2; nl-quick-add inc 1; B0 if chosen. Gates RS, AG, PSG.
- Validation: `targeted-unit-test` `"*AddExpenseViewModel*"`, `"*ManualExpenseRepository*"`, `"*CancellationSafetyArchitectureGuardTest*"`, `"*ManualEntryCurrencySetterAllowListGuardTest*"`, `"*QuickAddPrivacyArchitectureGuardTest*"`; shards `runtime-ui`, `data`, `architecture-contracts`; `lint`; `static-guards`.
- Done when: no rounding between input text and TLC; no new MissingTranslation; CancellationSafety + quick-add guards green and unedited.

### B2 — Add-expense currency control + preview + blocked state (T3; gated by N1/H-1 for release)
- INV-1 (VM uses evaluator via A1 use case), 10, 11, 15, 16 (UI hide/clear), 20, 23 (read-only chip). AE-1, 2, 3, 4, 6, 7, 11 (label), 15, 16, 17, 22, 26, 27, 30, 31, 38. G2-13, G2-15.
- Source: `AddExpenseViewModel.kt` (`CurrencyRegionUi` state, `Selected(home)` default per open, options from policy, Evaluating after 250 ms, latest input wins; re-evaluate on currency/date/home/zone/refresh completion; switching currency re-validates text (AE-16); foreign → Repeat off + cleared; save passes selected currency; map all post-tap refusals; `ratesSetting` On/Off/Unknown (Unknown = Off) from R23; refresh only when On via `CurrencyRatesRepository.refresh(home)`; never enables setting; "Open setting" keeps form or confirms first if amount entered (D-11); "today" from injected clock; no preview logging). `AddExpenseSheet.kt` (control/amount Row stacking at fontScale ≥ 1.3 or width < 360dp; preview line; blocked card assertive when save-originated else polite; Repeat hidden + note; TalkBack order + 48dp per ux §13; picker untouched unless B0). NEW `A/ui/components/currency/CurrencySelectionControl.kt` (button + `ModalBottomSheet` with search; home header first, others by code; omitted not greyed; shared with C; shard coverage D-14). NEW `A/ui/components/currency/CurrencyRateRegion.kt` (`CurrencyRegionUi` model — enums, codes, `LocalDate`, no strings; preview via `CurrencyFormatter.formatMoney(product, home)` (AE-38); rate date as `LocalDate`; blocked card; shared with C). Sheet host file only if D-11 needs it. `res` ×3 (~40 `mec_` strings, ux §14; replacements for `quick_add_hint_foreign` / `quick_add_flag_amount_foreign`; setting name matches R23).
- Tests: `AddExpenseViewModelTest.kt` (listed AEs; no `refresh` when Off/Unknown; open-setting confirm; capturing Timber AE-30; preview text == `formatMoney` of stored snapshot AE-38); `AddExpenseViewModelStressTest.kt` (rapid currency/date switching).
- Depends on B1; R23 setting (else N8); H-1 decided if N1 requires 1b. Gates RS, AG, PSG; owner reviews Greek.
- Validation: `targeted-unit-test` `"*AddExpenseViewModel*"`, `"*CancellationSafetyArchitectureGuardTest*"`, `"*ManualEntryCurrencySetterAllowListGuardTest*"`, `"*QuickAddPrivacyArchitectureGuardTest*"`; shards `runtime-ui`, `architecture-contracts`; `lint`; `static-guards`.
- Done when: Save disabled for Unset, invalid amount, None/Stale/Unsupported; no amount shown for non-Eligible; no fetch at preview/save; no MissingTranslation.

### C — Receipt review (T3)
- INV-5 (setter #2), 6 (mapping), 11, 15, 17, 19. AE-22, 23, 24, 25, 30, 33, 37. G2-14; UX-Q-5; RF-6 (G2-18); OD-5/G2-7 batch untouched.
- Source: `ReceiptScanViewModel.kt` (flag with `entryZone` captured at review open at `:1253-1266`; `:461` OCR proposal via `CurrencySelectionPolicy` — not selectable → `Unset`, no OCR-token echo; `NONE_PROPOSED` → home + `mec_receipt_not_detected`; preview via A1 use case; inline precision/cap; new `SaveReceiptResult` variant (`:164-170`) for `RateNotEligibleException`, form kept; quick-save refusal opens full review with card (D-13); RF-6 `:416` zone check (H-5)). `ReceiptScanScreen.kt` (private `CurrencyPicker` `:883`, `:1385` → shared B2 control; preview/blocked card; "Your receipt is kept. Nothing was saved."; scroll + assertive). `ReceiptLifecycleCoordinator.kt` (`:1629-1630`: `rateRefusal != null` → `Result.failure(RateNotEligibleException(reason, latestRateDate))`, payload-free, modelled on `:1668`; batch `:1174-1222` untouched). `res` ×3. Quick-save confirm surface file only if separate (D-13).
- Tests: `ReceiptScanViewModelTest.kt` (AE-23, 24, 25, 30, 33, 37; G2-14; UX-Q-5; RF-6); `ReceiptScanViewModelStressTest.kt`; `ReceiptLifecycleCoordinatorTest.kt` (AE-24: receipt row, file, `matchStatus`, links, `ReceiptEvent`s unchanged; exception payload-free; batch path unflagged).
- Depends on A2, B2. Gates RS, AG, PSG.
- Validation: `targeted-unit-test` `"*ReceiptScanViewModel*"`, `"*ReceiptLifecycleCoordinator*"`, `"*ReceiptLinkServiceNestedFailure*"`, `"*ManualEntryCurrencySetterAllowListGuardTest*"`; shards `runtime-ui`, `domain-n-z`; `registered-guard -GuardId db_access`; `lint`; `static-guards`.
- Done when: refused save leaves every receipt artifact unchanged, screen stays in review with fields; batch path diff empty.

### Z — Closeout
Regenerate `docs/architecture/CODEBASE_INVENTORY.md`; update handoff; no `app/` edits. Follow-ups: RF-7 (`ReceiptParser.kt:690`) → receipt owner; parser/notification caps → owners (G2-3); RF-5 + OD-3 alignment → money-correctness; RF-2 if routed; Spanish native review; instrumented ICU check if H-4 deferred; G2-20 items (unsent). Bundle gate; final RS in a fresh session with no discovery role.

## Guard / config edit assignment
| Edit | Slice | Approval |
|---|---|---|
| NEW `T/architecture/ManualEntryCurrencySetterAllowListGuardTest.kt` (fixed 3-site allow-list) | A2 | G2-10 |
| CancellationSafety KNOWN_VIOLATIONS, quick-add guards | none (run only) | any need → N17 / stop |
| `test-shards.json`, `db_ownership_policy.yml`, `db_access_v2.json`, `guard_registry.py`, money/time guard scripts, baselines | none | any need → stop |
| Loan plan edits | loan S0 (from D) | G2-6 |

## Owner plan-gate checklist
- [ ] Base ready (Release 1.0 incl. R16, R23 p1; ai-fixes); S0 before any `app/` edit.
- [ ] Order S0 → A1 → A2 → (loan S1..S10 ∥ nl-quick-add inc 1) → B0? → B1 → B2 → C → loan S11 → Z; D right after Gate 3.
- [ ] H-1..H-5 decided.
- [ ] A2 behaviour accepted: per-currency cap for all creates + review approval (G2-3); home-unresolved fail-closed for manual saves (INV-8); RF-1 reason text bounded for every create (G2-11).
- [ ] B1 behaviour accepted: rounding removed, "12.345" rejected (G2-9).
- [ ] Greek copy reviewed at B1, B2, C; Spanish native review follow-up.
- [ ] No allow-list/baseline growth; guard's 3-site list fixed in A2.

## Human decisions required
- **H-1** (only if S0 finds no periodic refresh → N1 says inc 1 ships with 1b): rec merge A1, A2, B1, D normally (no foreign entry exposed); keep B2 + C (and therefore loan S11) on `feature/manual-entry-currency`, unreleased until MEC inc 1b lands. Alternatives: restate N1 and ship inc 1 alone; hidden runtime flag (new infrastructure → stop).
- **H-2** plan deviations from impact map: (1) (a) split A1/A2; (2) preview use case in A1; (3) setter guard in A2 listing all three G2-10 sites incl. planned loan coordinator path (never grows); (4) (b) split B1 (home-only flagged save; setter + rounding removal together per G2-8) / B2 (control/UI).
- **H-3** (if RF-2 confirmed): rec conditional slice B0 inside MEC before B2 (behaviour change west of UTC; AE-11 depends on it). Alternative: release/1.0.x track (needs explicit "send to release"; B2 waits).
- **H-4** instrumented ICU digits check: rec one `connected-test-class` run in Z (AVD `wave5-api35`) pinning 28 digits on device; no new dependency. Alternative: follow-up.
- **H-5** (if RF-6 finds `receipt.parsedDate` mismatch): rec fix in C only if confined to review-save instant construction at `ReceiptScanViewModel.kt:416`; else route to receipt owner, C ships with finding recorded.

## New stop-and-ask items
N3 `db_access`/`db_artifact_sync` finding, baseline growth, signature drift · N4 schema need · N5 lint only via baseline refresh · N6 new Gradle dependency (ICU4J, Robolectric, Compose upgrade) · N7 money-correctness in flight on TLC `:529-585`/snapshot rounding or conflict during A2 · N8 R23 part 1 lacks setting / readable API / refresh consent gate → B2 blocked · N9 post-R16 parser contradicts INV-13/AE-15/AE-17 (e.g. "1.500" → 1500) → spec author · N10 loan S3/S6a in flight on TLC/`CreateExpenseRequest` when A2 due, or S11 started before B1 · N11 Dagger can't take defaulted TLC param / `@Inject` evaluator without module edit, or TLC test ctors need non-mechanical changes · N12 `CREATE_ATTEMPTED` reason text has readers (UI history, export/backup, dedup, tests) → RF-1 scope grows · N13 `MANUAL_SAVE_BLOCKED_NO_RATE` needs guarded registry/allow-list registration, or sink can't carry sub-reason + origin · N14 JDK/ICU digits differ from spec §1.5 or non-ISO fallback undefined · N15 `myShareAmount` not in expense currency, or edit-mode currency handling contradicts INV-23 · N16 setter guard needs brittle scanning, edit to existing guard/registry, or different loan coordinator path · N17 nl-quick-add guards/tests pin obsolete foreign copy, or CancellationSafety entry still present/must be extended · N18 tests outside the G2-3 table pin raw 1M or changing them needs deletion · N19 quick-save confirm surface differs from F12/UX-Q-5.

## Risk register
| # | Risk | Mitigation / slice |
|---|---|---|
| R1 | Shared-file contention (TLC, VM/Sheet, strings, arch docs) with money-correctness, loan, nl-quick-add, ai-fixes | G2-1; S0 checks; N7, N10 |
| R2 | Rounding removal user-visible on all manual saves | G2-9; inline error keeps text; stress test |
| R3 | Home-unresolved fail-closed for manual saves | INV-8/AE-14; retry card; B1 |
| R4 | JVM vs device ICU digits | 28 pinned values; H-4; N14 |
| R5 | Timezone/DST test flakiness | injected zone + clock; AE-10/11 |
| R6 | Cap relaxation changes unflagged sources | G2-3; per-caller tests; N18; notification caps unchanged |
| R7 | Privacy leak via events, diagnostics, a11y, preview logs | RF-1 in A2; INV-19; AE-30; PSG on A1, A2, B1, B2, C |
| R8 | Preview ≠ stored snapshot | single evaluator read; same operands; AE-22, AE-38 |
| R9 | Flagged branch alters unflagged behaviour | INV-9 regression; `:554-578` diff empty |
| R10 | N1 holds B2/C (and loan S11) off release | H-1; B1 releasable alone |
| R11 | Loan depends on A2/B2 timing; FX deferral assumption wrong | D handoff; G2-6; RF-3 |
| R12 | FG-26 doc guard before symbols exist | docs with symbols (A1, A2) |
| R13 | MissingTranslation lint | EN/el/es same slice as key |
| R14 | Money guard hits (G-MONEY-11, 18/19) | TRANSACTION_DATE + None; no `CurrencyCode("")` |
| R15 | Refusal yields `DuplicateSkipped` or partial writes | refusal before dedup; DbContract AE-20 |
| R16 | Batch receipt path accidentally changed | empty diff `:1174-1222`; OD-5 test |
| R17 | RF-7 JPY/KRW receipt totals > 50,000 still dropped | routed (G2-18); noted in Z |

## Final bundle acceptance gate (validation-runner, serial; poll; stale/missing ≠ PASS)
1. `unit-test-shard -Shard architecture-contracts` 2. `-Shard domain-a-m` 3. `-Shard domain-n-z` 4. `-Shard data` 5. `-Shard runtime-ui` 6. `static-guards` 7. `registered-guard -GuardId db_access`, then `db_artifact_sync` (unchanged vs S0) 8. `lint` 9. `connected-test-class` only if H-4 = yes 10. `app-check` 11. `unit-tests` (last). `migration-tests` not required. Then fresh-session reviewer-strict final acceptance (separate from discovery reviewers).

## Deferred
MEC inc 1b (ECB 90-day history, R23 setting, privacy review; N1); nl-quick-add inc 1b; typed/manual rate; "recent currencies" (UX-Q-3); batch receipt flagging (OD-5); parser/notification caps (owners); `AmountUtils.isValidAmount`; OD-3 alignment + RF-5 (money-correctness); RF-7; persisting rate date; edit-mode currency change; Spanish native review; G2-20 release items until owner says "send to release".

## UNVERIFIED for S0
- D-1 base/branch + cited lines: TLC `:86, :92, :381-471, :397, :406-427, :508-526, :529-585, :554-578, :587+`; `TransactionValidator.kt:87-93`; `CreateExpenseRequest.kt:74`; `CreateExpenseResult.kt:10`; `ManualExpenseRepository.kt:114`; `ReviewQueueRepository.kt:147-157`; `AddExpenseViewModel.kt:63, 93-95, 276-281, 291-309, 297, 367-370, 377-381`; `AddExpenseSheet.kt:202-209, 841-855`; `ReceiptScanViewModel.kt:83, 164-170, 416, 461, 561, 1253-1319`; `ReceiptScanScreen.kt:883, 1385`; `ReceiptLifecycleCoordinator.kt:1174-1222, 1583-1668`; `CurrencyConverter.kt:21, 48, 53, 333-487`.
- D-2 R23 part 1 setting name/default/API; refresh consent gate; periodic refresh (N1, N8); refresh outcome codes.
- D-3 post-R16 `AmountUtils.parseAmount` on "1,500", "1500,50", "1.500" (N9).
- D-4 money-correctness status (G2-2, N7).
- D-5 loan: no installment code; S3/S6a/S11 status; coordinator path (N10, N16).
- D-6 nl-quick-add inc 1 landed; CancellationSafety entry removed; quick-add guards/strings pin foreign copy (N17).
- D-7 controlled-diagnostic sink API (behind `emitHomeCurrencyUnavailableDiagnosticBestEffort`); registration (N13).
- D-8 `CREATE_ATTEMPTED` reason readers; `transaction_events` export/backup (N12).
- D-9 Dagger with defaulted TLC ctor param; list of 8 TLC-constructing test files (N11).
- D-10 `myShareAmount` currency; edit-mode currency handling (N15).
- D-11 AddExpense sheet host keeps VM across navigation to settings (G2-13); host file.
- D-12 list converted-amount formatting call site (AE-38).
- D-13 receipt quick-save confirm surface (UX-Q-5, N19); review state after process death.
- D-14 shard coverage of `scenarios.*` and new `ui.components.currency.*`; ReviewQueueRepository test class; multi-pattern `-TestFilter`.
- D-15 JDK `defaultFractionDigits` for 28 catalog codes (N14).
- D-16 RF-2 debugger verdict (H-3).
- D-17 tests pinning raw 1M for weak currencies (importers, group, bank, receipt batch) (N18).
- D-18 exact G-MONEY-11/18/19 + time-guard patterns for the evaluator.

## Amendment (2026-10-28): B0 re-anchor (feature-planner; orchestrator-persisted; BINDING)
- **RF-2 REFUTED.** Release-1.0 R20c-1 (2026-10-17; findings ledger :1250-1274) already reads the picker in UTC.
  - Anchors: `UiTimeUtils.kt:64-126` (pickerMillisToLocalDate :74, instantToPickerMillis :89, pickerMillisKeepingTimeOf :113). `AddExpenseSheet.kt` DateSelector is now :884-957 (it was :841-855).
  - Evidence: UiTimeUtilsTest (18 tests: New York, Athens, Kiritimati, DST gaps and overlaps, round trips). B0-as-RF-2 is DROPPED, and B1's "B0 if chosen" dependency is empty.
- **RF-2b (new; orchestrator decision under item 62, owner FYI): B0′ "picker seeds from the current date".**
  - Bug: `rememberDatePickerState` (:892-894) is read only at first composition. After quick-add Fill (`AddExpenseViewModel.kt:647`) or reset (:676) changes `state.date`, the picker opens on the stale day, and OK without a change silently reverts the date. That changes the rate date for B2 (AE-11/INV-2).
  - Fix: in `DateSelector` only, move `rememberDatePickerState(initialSelectedDateMillis = UiTimeUtils.instantToPickerMillis(dateMs))` inside `if (showDatePicker)` before `DatePickerDialog`.
  - Unchanged: no helper, no ViewModel or UiTimeUtils change, same signature.
  - Tests: N `ui/screens/addexpense/AddExpenseDateSelectorTest.kt` (Robolectric Compose, as in QuickAddSectionTest :49-50; TimeZone.setDefault pinned and restored; whole-second instants):
    - initial OK-without-change is idempotent (New York, Auckland, Tokyo);
    - after an external date change, OK keeps the new date (New York, Auckland);
    - reopening seeds from the current date (Tokyo);
    - a DST spring-forward day keeps the day (New York);
    - dismiss does not call onDateSelected.
  - Validation: targeted `*AddExpense*,*UiTimeUtilsTest*`, then runtime-ui and lint. static-guards is batched with B1.
  - Gates: reviewer-strict and architecture-guardian (batched with B1).
  - STOP if:
    - Robolectric cannot drive DatePickerDialog (no connected tests without the owner; H-4);
    - UiTimeUtils semantics would change;
    - the signature would change;
    - HomeScreen.kt:1531 or the installment pickers would need edits.
  - Follow-up (not this feature): HomeScreen.kt:1531 has the same stale-picker pattern. Route to the Home owner.
- B0′, then B1, then B2 run sequentially (all write AddExpenseSheet.kt).


## Amendment (2026-10-28): B1 re-anchor (feature-planner; orchestrator-persisted; BINDING)
Base: 59275eac plus B0′. T3. Lane mec, one writer. B0′ comes first (it shares AddExpenseSheet.kt).

### Anchors (old → current; [D] drifted, [V] vanished)
- **AddExpenseViewModel.kt:**
  - flat cap old :297 → :374-377 [D]; rounding old :367-370 → :441-444 [D];
  - parse :366-372 (R16 AmountInputSanitizer.parseOrNull);
  - save() :345-518; addManualExpense(...) :451-476; Result.Error → mapSaveError :489-493; the CancellationException rethrow :500-501;
  - mapSaveError :525-527 (only `else`); quick-add fill() :549-613 (homeCurrency :551, the systemDefault zone :569); reset() :668-677.
  - Edit branch [V]: the sheet is create-only (AddExpenseSheet.kt:96).
- **AddExpenseSheet.kt:**
  - amount label :228 (`amount_label` "Amount (€)", es "Cantidad (€)", no el); cd :222 (`add_expense_amount_cd` "… %1$s Euro", default locale only);
  - leading icon :249-261 already shows the home symbol [D, done]; supportingText :240-248; save error card :625-640.
- **ManualExpenseRepository.kt:**
  - addManualExpense :105-127 (21 params); A2 cap :133-140; INV-16 :141-146 (RECURRING_FOREIGN_CURRENCY_ERROR_CODE :82);
  - CreateExpenseRequest :160-182 (no userPresentRate today); ValidationFailed join :233-240.
- **Codes (domain/transaction/RateRefusal.kt):** RateRefusalReason.HOME_CURRENCY_UNRESOLVED :20; RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED :41; AMOUNT_PRECISION_EXCEEDED :44; AMOUNT_DIGITS_UNAVAILABLE :47; codeFor :49.
- **CurrencyAmountPolicy:** check(Double, CurrencyCode) :92 is the same overload the validator uses (:110).
- **Strings (ux-flows §14 :168-182):** mec_block_home_unresolved, mec_amount_no_decimals, mec_amount_max_decimals, mec_amount_too_large, mec_repeat_home_only (S4).
- **AddExpenseViewModelStressTest.kt [V]:** the file does not exist.

### Write set
- Source:
  - AddExpenseViewModel.kt, AddExpenseSheet.kt, ManualExpenseRepository.kt;
  - res values/values-el/values-es strings.xml;
  - ENGINE_INTERACTION_MAP.md :94 (setter #1 is now active).
- Tests:
  - AddExpenseViewModelTest, ManualExpenseRepositoryTest;
  - mechanical `entryZone` additions in ManualExpenseRepositoryRecommendationTest and SharedExpenseGroupsViewModelTest :194.
- Out of scope: guards, test-shards, the lint baseline, the quick-add domain/mapper, QuickAddUiMapperTest.

### Behaviour (S4 folded in)
- **Repository:**
  - `addManualExpense(..., entryZone: ZoneId)` is REQUIRED (no default) and always sets `userPresentRate = UserPresentRateRequirement(entryZone)` (setter #1).
  - Typed refusal in ValidationFailed:
    1. `rateRefusal != null` → `Result.Error(RateRefusalCodes.codeFor(reason))`;
    2. otherwise, if the errors contain AMOUNT_PRECISION_EXCEEDED or AMOUNT_DIGITS_UNAVAILABLE → that code alone;
    3. otherwise the existing join.
- **VM save():**
  - Delete the flat cap and the rounding; pass the parsed amount unchanged.
  - After parse and the `<= 0` check, run `CurrencyAmountPolicy.check(amount, code)` (Double overload):
    - PrecisionExceeded(0) → mec_amount_no_decimals(code);
    - PrecisionExceeded(d) → mec_amount_max_decimals(code, d);
    - AboveCap(cap) → mec_amount_too_large(formatted cap);
    - DigitsUnavailable → add_expense_error_amount_invalid (fail closed).
  - In every case the text is kept and nothing is saved.
  - If the code does not parse, skip the precision check and use DEFAULT_CAP.
- **Entry zone (AE-34):**
  - A plain VM field `sessionZone: ZoneId?`, never in state or SavedStateHandle (G-1).
  - The Sheet calls `viewModel.onSheetOpened()` from `LaunchedEffect(Unit)`; reset() clears it; save() uses `sessionZone ?: now`.
  - The Fill zone (:569) is unchanged.
- **mapSaveError(result, currency):** equality on bounded codes only:
  - RECURRING_FOREIGN_CURRENCY_ERROR_CODE → mec_repeat_home_only(home) (S4);
  - NOT_ELIGIBLE_HOME_UNRESOLVED → mec_block_home_unresolved (AE-14);
  - AMOUNT_PRECISION_EXCEEDED → the precision text (null digits → save-failed);
  - else → save-failed.
  - No message parsing, no new Timber, no amount or zone in logs.
- **Sheet:**
  - `amount_label` edited in place (en "Amount", es "Cantidad"; el "Ποσό" only if lint shows it is baselined).
  - `add_expense_amount_cd` becomes "… %1$s %2$s" with the home code.
  - Other "(€)" labels on other screens are follow-ups.
- **Strings:** the 5 mec_ keys from ux §14, es/el DRAFT (owner reviews the Greek).
- **Quick-add:**
  - Fill keeps home-currency parsing and FOREIGN_CURRENCY_BLOCKED (replaced in B2, 80c).
  - Removing the rounding has no effect on Fill (Fill text is always 2 decimals).
  - A 0-decimal home rejects a fractional Fill at Save (AE-15).
  - N17 does not fire in B1.

### Decisions (orchestrator under item 62; owner FYI, owner-approvals 84)
- **D-B1-1:** G2-9 ("EUR 12.345 → inline error") is unreachable since R16; the sanitizer truncates en_US to 12.34, and el reads it as grouping. ACCEPT: G2-9 is met by the sanitizer plus no rounding on save. Precision is tested with 0-decimal homes (AE-15). The sanitizer's 2-decimal limit is unchanged in B1. Follow-up for B2/1b: 3-decimal homes (BHD, KWD, JOD, OMR, TND, IQD, LYD) cannot type a third decimal.
- **D-B1-2:** AE-36 edit precision has no owner (the sheet is create-only; A2 precision is create-only). DEFERRED to a follow-up, together with A2's myShareAmount precision gap.
- **B0:** RF-2 refuted (see the B0 re-anchor); B0′ lands first.

### Tests
- **AddExpenseViewModelTest:**
  - B1 save passes the parsed amount unrounded with the session entry zone;
  - B1 AE-15: JPY home "1500,50" shows the no-decimals error, keeps the text and saves nothing;
  - B1 AE-15: JPY home "1500" saves 1500;
  - B1: JPY home "12.50" is rejected inline;
  - B1 G2-9: en_US typed "12.345" is truncated to 12.34 and saved exactly;
  - B1: KRW home 2,000,000 passes the per-currency cap;
  - "amount too large" expectation edited IN PLACE to mec_amount_too_large (not deleted);
  - B1 AE-34: the zone captured at sheet open survives a device zone change;
  - B1: reset then reopen captures the new zone;
  - B1 AE-14: the home-unresolved refusal maps to mec_block_home_unresolved;
  - B1 S4: the recurring foreign-currency code maps to mec_repeat_home_only;
  - B1 AE-19: the coordinator's AMOUNT_PRECISION_EXCEEDED maps to the precision resource;
  - B1: Fill with a JPY home and a fractional amount is rejected at save;
  - B1: precision and cap errors log nothing;
  - extend AE-53 for AE-33;
  - the positional stub goes to 22 `any()`, and every named verify gains `entryZone = any()`.
- **ManualExpenseRepositoryTest:**
  - the :193 `assertNull(userPresentRate)` pin is INVERTED (not deleted): the request carries the flag with the passed zone;
  - a home-unresolved refusal returns RATE_NOT_ELIGIBLE_HOME_UNRESOLVED;
  - a flagged precision failure returns AMOUNT_PRECISION_EXCEEDED alone;
  - non-MEC validation failures keep the joined message.

### Pins and guards
- None loosened. Setter allow-list, QuickAddPrivacy G-1/G-3 and CancellationSafety are run only, never edited.
- The only pin edits are the :193 inversion and the :630 expectation (reviewer-strict confirms they are rewrites).

### Validation (batched with B0′)
1. targeted: `*AddExpense*,*UiTimeUtilsTest*,*ManualExpenseRepository*,*SharedExpenseGroupsViewModelTest*,*QuickAddUiMapperTest*,*CancellationSafetyArchitectureGuardTest*,*ManualEntryCurrencySetterAllowListGuardTest*,*QuickAddPrivacyArchitectureGuardTest*`
2. shards: runtime-ui, data, architecture-contracts.
3. lint.
4. static-guards.
5. app-check once per candidate.

### Gates
- reviewer-strict: flagged saves, rounding removal, pin edits, typed refusal.
- architecture-guardian: setter #1, the required param, the onSheetOpened seam, ENGINE map, string keys.
- privacy-security-guardian: bounded codes, zone never persisted or logged, no SavedStateHandle, no amount in logs.

### STOP
- A setter, QuickAddPrivacy or CancellationSafety guard edit.
- static-guards rejects `ZoneId.systemDefault()` in the VM (that would need a zone provider plus a DI change).
- Lint needs a baseline refresh (N5).
- Any non-mechanical quick-add or SharedExpenseGroups test change.
- Loan S11/S13 in flight on the same files.

### Deferred
- Prefill and suggestion "%.2f".
- Other "(€)" labels.
- Quick-add's 1M flag for high-cap homes.
- Fill with the selected currency (B2).
- AE-36 edit precision.
- myShareAmount precision.
- 3-decimal typing.

### B1 review dispositions (2026-10-28)
- Privacy guardian: ACCEPT.
- Architecture guardian: ACCEPT-WITH-CONDITIONS (F1/F2/F3 below).
- F1 (MEDIUM, AE-34 zone re-capture on LaunchedEffect re-run while the VM survives): NOT FIXED, STOPPED by precondition. The sheet's own close paths (save success, BackHandler, close button) all call reset(), but the host can remove the sheet from composition without reset(): MainActivity collects MainViewModel.navigationRequest and calls navigation.navigateToTab/navigateTo while NavigationDestination.AddExpense is showing, and the VM is the Activity-scoped hiltViewModel(), so it survives with a stale sessionZone. Making onSheetOpened() capture only when sessionZone == null would then keep a stale zone for the next session. Needs an orchestrator decision (see handback). rememberSaveable is forbidden here (QuickAddPrivacy G-1). Privacy P3 = F1.
- F2 (LOW): add_expense_error_amount_too_large was referenced only by the three strings files (values, values-es, values-el); the test already uses mec_amount_too_large. The three entries are deleted. Docs mentions (nl-quick-add ux-flows.md, nl-quick-add-handoff.md) are historical and left unchanged.
- F3 (LOW, docs): LEGAL_PATHS.md manual-entry-currency paragraph now lists the exact ViewModel code mapping (RECURRING_FOREIGN_CURRENCY_ERROR_CODE -> mec_repeat_home_only; RATE_NOT_ELIGIBLE_HOME_UNRESOLVED -> mec_block_home_unresolved; AMOUNT_PRECISION_EXCEEDED -> precision text; all other codes incl. AMOUNT_DIGITS_UNAVAILABLE and RATE_NOT_ELIGIBLE_NONE/STALE/UNSUPPORTED -> generic save-failed). This LEGAL_PATHS edit is recorded as a write-set extension: its old "no production setter until B1" line became false once setter #1 went live.
- Follow-ups:
  - F4: add CurrencyAmountPolicy.capFor(String) to de-duplicate the unparsable-code -> DEFAULT_CAP fallback in the VM, TransactionValidator and ManualExpenseRepository.
  - F5: a test pinning validator message == code (being added now).
  - Privacy P1: mec_repeat_home_only should receive the resolved home code explicitly once B2 separates selected vs home currency.
  - Privacy P2: ManualExpenseRepository Error branch puts exception.message into Result.Error.message (never displayed); replace with a bounded code in a later slice.
- F1 FIXED (option b′, owner-approvals 85): null-guarded capture plus a sheet-scoped DisposableEffect that clears the zone on dispose unless the Activity is changing configurations; MainActivity untouched.
