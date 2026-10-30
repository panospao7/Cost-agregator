# Domain spec: manual-entry-currency — increment 1 (+ frame for 1b)

> **RF-10 applied 2026-10-23** (domain-modeler, docs only). money-correctness 1b is on `feature/manual-entry-currency`, so the interim
> statements written for "before money-correctness" are replaced by its shipped behaviour (MEC plan "Amendment 2026-10-16";
> handoff "Notice from money-correctness Z"). Re-verified [V] on this branch: TLC conversion block `:605-674` (no-rate/rounding-unavailable
> sentinel `:654-666`, `MISSING_RATE` emitter `:337-367`, `convertedSnapshot` `:385-400`, identity `:668-673`); `SnapshotRounding.kt:45`;
> `CurrencyFractionDigits.kt:17-22`; `SourceCurrencyAllocation.kt:28`; `CurrencyFormatter.kt:90-106`. Changed:
> - Unflagged no-rate rows: sentinel triple `baseAmount=0.0`, `baseCurrency=""`, `exchangeRateUsed=0.0` plus exactly one bounded
>   `MISSING_RATE` diagnostic (metadata `{operation, reason}`), not `baseCurrency="EUR"` and no `Timber.w`: header scope line, F18, INV-9,
>   §5 "Unflagged callers", §9 D-8 bullet, §12 rows, §14 bullet, RF-5, AE-21, AE-29.
> - Digits/rounding owner: money-correctness `CurrencyFractionDigits.of` (digits) + `roundSnapshot` (rounding); MEC
>   `CurrencyAmountPolicy.fractionDigits` delegates, no second accessor: F14, §1.5 field row, §2 owners (digits row split + new
>   "Snapshot rounding" row), INV-12, §6 "Single digits source".
> - Snapshot/preview interim ended: stored `baseAmount` and the preview both = `roundSnapshot(amount × Eligible.rate, home)`; preview
>   formatted with `CurrencyFormatter.formatMoney` and equal to the stored snapshot: §1.6, §1.7, INV-7, INV-8 (pointer), INV-15, §5 flagged
>   branch (+ placement line ref), §6 "Rounding", §12 rounding row, AE-1, AE-21 (CSV case), AE-38.
> - Same fixes in this folder: `ux-flows.md` §3 preview line and §11 "≈" line; `impact-map.md` §1(a) `CurrencyAmountPolicy` and TLC rows,
>   §9 money-correctness bullet, §10 RF-5. `gate2-decisions.md`, `brief.md`, `critique.md`, `no-rate-verification.md` are dated records, left as written.
> - New **OD-6 (DECIDED 2026-10-23, fail closed)**: behaviour when `CurrencyFractionDigits.of` returns null — see OD-6 row.
> - Not changed: every other decision, INV/AE numbering and count, and other TLC line references (F9, F12, …), which predate
>   money-correctness and are re-verified at S0 (D-1).

Author: domain-modeler (stage 3). Date 2026-10-16. Docs only, post-1.0. Money feature, **T3** (reviewer-strict).
Inputs: `brief.md` v2 (approved), `critique.md` (binding fact corrections), `no-rate-verification.md`,
`workflows/active/manual-entry-currency-handoff.md` §Human decisions (binding), `docs/architecture/LEGAL_PATHS.md`
(Expense CREATE, Receipt Mutations, Money/Currency), loan-installments plan S11 and domain spec.

`A` = `app/src/main/java/com/yourname/expensetracker`. Verification tags: **[V]** checked in this pass (file:line),
**[C]** taken from critique/brief (not re-checked), **[U]** UNVERIFIED.

Scope: increment 1 = currency control on user-present saves (manual add, single-receipt review, installment declare),
one "rate for this date" rule enforced at the existing coordinator create path, per-currency decimals and cap,
foreign recurring hidden, BGN/RUB not offered. Increment 1b (ECB history download) is framed only (section 10).
Out of scope (owned by `money-correctness-1.0.x`): unconverted marker for background sources, sort/filter rule,
single snapshot rounding rule, the unflagged no-rate handling at TLC (the former `Timber.w` is now one bounded `MISSING_RATE`
diagnostic; landed in money-correctness 1b, RF-10).

---

## 0. Facts this spec depends on (new in this pass)

| # | Fact | Tag |
|---|---|---|
| F1 | `convertOutcome(…, RateBasis.TRANSACTION_DATE, atMillis, StaleRatePolicy.None)` already returns `ConversionOutcome.Converted` with `rateValidDate` (via-EUR = **min of the two legs**), `rateSource`, `conversionPath`; failures are typed (`MISSING_HISTORICAL_RATE`, `INVALID_SOURCE_CURRENCY`, …). So the "expose validDate" need is met without changing `ConversionResult`/`convertAsOf`. | [V] `CurrencyConverter.kt:333-487` (legs `:400-411`, min `:406`); `ConversionOutcome.kt:11-24` |
| F2 | `StaleRatePolicy` ages are millisecond spans (abs), not calendar days; `forBasis(TRANSACTION_DATE)` = `None` (any age). It can't express the owner's calendar-date rule. | [V] `StaleRatePolicy.kt:7-21`; `CurrencyConverter.kt:443-473` |
| F3 | Analytics re-converts every row with `convertOutcome(TRANSACTION_DATE, atMillis = expense.date)` (instant bound, any age). | [V] `AnalyticsCurrencyNormalizer.kt:146-152` |
| F4 | All stored `validDate`s are UTC midnight: ECB publication date (`atStartOfDay(ZoneOffset.UTC)`) and `startOfDay()` for the default (UTC truncation). The only production writer is the ECB refresh (`source="ecb"`); `storeRate(source="manual")` has no production caller. | [V] `CurrencyRatesRepositoryImpl.kt:100-104, 155-157`; `CurrencyConverter.kt:603-661`; grep of `A` |
| F5 | One refresh stores every cross pair among catalog currencies listed by ECB, all with the same `validDate`. So a direct pair always exists when both legs exist, and via-EUR is reached only for unusual data (e.g. restored rows). `exchange_rates` is unique on (from, to, validDate) with REPLACE. | [V] `CurrencyRatesRepositoryImpl.kt:84-104`; `ExchangeRateDao.kt:14-26` |
| F6 | `getRateAsOf` = latest row with `validDate <= bound`; rows with NULL `validDate` never match. | [V] `ExchangeRateDao.kt:62-63` |
| F7 | No retention: `cleanupOldRates`/`deleteOldRates` have no production caller (only the store adapter pass-through). `deleteOldRates` keys on `lastUpdated` (download time), not `validDate`. | [V] `CurrencyConverter.kt:688-690`; `ExchangeRateStoreAdapter.kt:51-53`; `ExchangeRateDao.kt:59-60` |
| F8 | `isManualEntry = true` is also set by group expenses and by pending-review approval of receipt-backed items, and it is persisted on the row and read by dashboard adapters. | [V] `GroupTransactionCoordinator.kt:837`; `ReviewQueueRepository.kt:197, 247`; TLC `:484`; `DashboardContractsAdapter.kt:198` |
| F9 | TLC create order: (1) `CREATE_ATTEMPTED` event (best effort; `reason` contains merchant + amount + currency), (2) validation → `ValidationFailed` + `CREATE_VALIDATION_FAILED`, (2b) provenance, (3) dedupe key from amount + merchant + date + currency + type, entity build, conversion snapshot, (4) dedup, then insert. | [V] `TransactionLifecycleCoordinator.kt:381-471, 529-594` |
| F10 | TLC validation (`TransactionValidator`) already caps **every** create at a raw 1,000,000 (`AMOUNT_EXCEEDS_MAX`). Further raw copies: VM `:297`, `ManualExpenseRepository.kt:114`, `AppConfig.MAX_TRANSACTION_AMOUNT`, `AmountUtils.isValidAmount`, `AppParserRegistry.kt:69`. | [V] `TransactionValidator.kt:87-93`; `AppConfig.kt:12`; `AmountUtils.kt:153`; `AppParserRegistry.kt:69` |
| F11 | `CreateExpenseResult` = Created / DuplicateSkipped / ValidationFailed(errors) / InsertConflict(reasonCode) / Error. Precedent for bounded codes: `InsertConflictCodes`. | [V] `CreateExpenseResult.kt:3-46` |
| F12 | Receipt save: `ReceiptScanViewModel` builds the request (`source=RECEIPT_SCAN`, `isManualEntry=true`) and is the **only** caller of `createExpenseAndLinkReceipt` (both review save and quick-save confirm). The coordinator maps `ValidationFailed` to a plain `Result.failure` without throwing, so the transaction commits with no link and no `ReceiptEvent`. The VM shows "UNKNOWN_ERROR" for it. Payload-free typed precedent: `DuplicateTransactionException`. | [V] `ReceiptScanViewModel.kt:941-978, 1253-1319`; `ReceiptLifecycleCoordinator.kt:1583-1668` |
| F13 | `addManualExpense` has one caller (AddExpense VM). The VM rejects future dates (> end of local today), caps raw > 1,000,000, and **rounds HALF_UP to 2 dp** before saving. No `SavedStateHandle` in the add-expense package. | [V] `AddExpenseViewModel.kt:291-309, 367-370, 377`; grep |
| F14 | The platform digits value (`java.util.Currency.defaultFractionDigits`) is the only digits source in use. Since money-correctness 1b it has a single accessor, `CurrencyFractionDigits.of(CurrencyCode): Int?` (`domain/core/money`; null for an unknown code or undefined digits, never guesses), used by `roundSnapshot` and the allocator. The display formatter reads the same JDK value directly and, for a code the JDK rejects, falls back to 2 digits for display. NumberFormat rounds HALF_EVEN by default; the allocator and `roundSnapshot` use HALF_EVEN. (RF-10, re-verified 2026-10-23) | [V] `CurrencyFractionDigits.kt:17-22`; `SnapshotRounding.kt:45-54`; `SourceCurrencyAllocation.kt:28-30`; `CurrencyFormatter.kt:90-106` |
| F15 | Parser: a single comma with ≤ 2 digits after it is a decimal; comma groups of 3 are thousands; a dot-only string passes through, so `"1.500"` → 1.5. | [V] `AmountUtils.kt:80-145` |
| F16 | The date picker rebuilds the instant with `ZoneId.systemDefault()` and keeps the time of day. | [V] `AddExpenseSheet.kt:841-855` |
| F17 | Loan: v1 plan currencies are {EUR, USD} (B-1). `declareWithNewExpense` creates the purchase through TLC `createExpenseDbOnlyV2` inside the plan transaction. The loan spec Q9 assumes TLC *refuses* creation when no rate exists (`AUTO_RECORD_DEFERRED_FX`). | [V] loan `domain-spec.md:73, 426, 570-577`; plan `:452-467, 630` |
| F18 | Fact: TLC does **not** refuse unflagged creates on a missing rate. Since money-correctness 1b it saves the sentinel triple `baseAmount=0.0`, `baseCurrency=""`, `exchangeRateUsed=0.0` and emits exactly one bounded `MISSING_RATE` diagnostic (metadata `{operation, reason}`; reason `MISSING_RATE`, or `SNAPSHOT_ROUNDING_UNAVAILABLE` when rounding is unavailable). No `Timber.w` with currency or amount remains. This answers loan D-8 ("untraced"): not refused; traced by that diagnostic. (RF-10, re-verified 2026-10-23) | [V] TLC `:630-667`, `:337-367`, `:385-400`, `:2502-2505` |

---

## 1. Entities and value objects

All new types are in-memory. None is persisted (section 13).

### 1.1 `CurrencySelection` (state of the currency control)
| Field | Type | Null | Notes |
|---|---|---|---|
| state | `Selected(code: CurrencyCode, isHome: Boolean)` \| `Unset(reason: PROPOSAL_NOT_SELECTABLE \| NONE_PROPOSED)` | no | Defaults to `Selected(home)` each time a manual sheet opens. No last-used memory. |
| options | ordered `List<CurrencyCode>` | no | `CurrencySelectionPolicy.selectable(home, mode)` (INV-10). Doesn't depend on rate availability. |
| mode | `STANDARD` \| `INSTALLMENT` | no | `INSTALLMENT` = options ∩ {EUR, USD} (loan B-1). Home is not added in this mode unless it is EUR or USD. |

`Unset` can't be saved. A proposal (OCR currency; later nl-quick-add markers) that isn't selectable becomes `Unset(PROPOSAL_NOT_SELECTABLE)`. It is never swapped for home or any other currency (INV-11).

### 1.2 `UserPresentRateRequirement`: the request flag (decides D1)
```
CreateExpenseRequest.userPresentRate: UserPresentRateRequirement? = null      // new, defaulted, in-memory
UserPresentRateRequirement(entryZone: java.time.ZoneId)
```
- `null` (the default) means today's path, unchanged (INV-9). Non-null means: apply INV-2, INV-7, INV-8 and the INV-13 precision check, and refuse when they fail.
- **Decision: a dedicated flag. Don't reuse `isManualEntry`.** Reasons:
  1. `isManualEntry=true` is already set by group expenses and by receipt-backed pending-review approvals (F8). Both are owner non-goals, so reusing it would block them.
  2. `isManualEntry` is a persisted row attribute with existing readers. A transient save policy must not overload stored data.
  3. The rule needs a parameter (the entry zone, section 3). A Boolean can't carry it.
  4. A nullable object with a null default leaves every existing caller unchanged. It is also easy to grep, so the setter allow-list can be guarded (INV-5).
- Allowed setters: exactly three (INV-5). `ManualExpenseRepository.addManualExpense` (always sets it; takes `entryZone`), `ReceiptScanViewModel` single-receipt save (`:1253-1266`, covers review save and quick-save confirm), and the installment plan coordinator's `declareWithNewExpense` purchase request.

### 1.3 `RateEligibility` (output of the single evaluator, INV-1)
| Variant | Fields | Meaning |
|---|---|---|
| `Home` | none | currency == home. No conversion and no preview. Always eligible. |
| `Eligible` | `rate: Double` (home units per 1 unit of the currency, > 0, finite); `rateDate: LocalDate` (ECB date); `ageDays: Int` 0..7; `path: DIRECT \| VIA_BASE_CURRENCY`; `sourceLabel: ECB \| OTHER` | Save allowed. The snapshot uses exactly `rate`. |
| `Stale` | `latestRateDate: LocalDate`; `ageDays: Int` ≥ 8 | A rate dated ≤ the expense date exists but is more than 7 days older. |
| `None` | none | No rate dated ≤ the expense date (including rows with no `validDate`). |
| `Unsupported` | `reason: NOT_IN_CATALOG \| NOT_SELECTABLE` | Not in `SupportedCurrency.catalog`, or not selectable for new entries (inactive or no rate source) and not home. |

`sourceLabel = ECB` iff every leg's `source == "ecb"`. The preview label says "ECB rate of …" only for `ECB`, otherwise "rate of …" (F4: no other writer exists today. Backups are [U]).

### 1.4 `RateRefusal`: typed refusal (decides D1)
```
CreateExpenseResult.ValidationFailed(errors: List<String>, rateRefusal: RateRefusal? = null)   // additive field
RateRefusal(reason: RateRefusalReason, latestRateDate: LocalDate?)
RateRefusalReason = NO_RATE | STALE_RATE | UNSUPPORTED_CURRENCY | HOME_CURRENCY_UNRESOLVED
errors = listOf(code) with code ∈ RateRefusalCodes:
  RATE_NOT_ELIGIBLE_NONE | RATE_NOT_ELIGIBLE_STALE | RATE_NOT_ELIGIBLE_UNSUPPORTED | RATE_NOT_ELIGIBLE_HOME_UNRESOLVED
```
- **Decision: a defaulted field on `ValidationFailed`, not a new sealed variant.** A new variant would force every exhaustive `when` over `CreateExpenseResult` (importers, notification pipeline, bank, group, review queue, migration: 8+ files, F11) to handle an outcome they can never receive. The receipt coordinator's `else` branch would also turn it silently into a generic failure. With the field, only the three flagged callers read `rateRefusal`, and they must map it (INV-6, INV-17, INV-18).
- `latestRateDate` is set only for `STALE_RATE`. It is a public ECB date and is never logged (INV-19).
- Receipt path: the coordinator maps `rateRefusal != null` to a payload-free `RateNotEligibleException(reason, latestRateDate)`, modelled on `DuplicateTransactionException` (F12).

### 1.5 `CurrencyAmountSpec` and `AmountCheck` (decides D4)
| Field | Type | Source |
|---|---|---|
| fractionDigits | Int ≥ 0, or unavailable (null) | money-correctness `CurrencyFractionDigits.of(code)` (`domain/core/money`, the **single** accessor over the JDK `defaultFractionDigits`). `CurrencyAmountPolicy.fractionDigits` delegates to it and is not a second accessor (INV-12). Null: `DigitsUnavailable` (OD-6, fail closed) |
| maxAmount | exact decimal, major units | the static per-currency table in `CurrencyAmountPolicy` (INV-14, OD-1) |

`AmountCheck = Ok | NotPositive | PrecisionExceeded(allowedDigits) | AboveCap(cap) | DigitsUnavailable` (`DigitsUnavailable` when digits are null, OD-6; checked after NotPositive). Checks run on the parsed value (`BigDecimal.valueOf(parsed).stripTrailingZeros().scale() ≤ fractionDigits`), never on a rounded value.

Digits for the catalog (ISO 4217, as returned by the platform; tests must pin all 28): 0 for **JPY, KRW, ISK**; 2 for all others, **including HUF** (ISO says 2, though in practice amounts are whole forints. Allowing 2 decimals never forces rounding).

### 1.6 `ConvertedPreview`
`(amountInHome: Double = Rounded.amount of roundSnapshot(parsedAmount × Eligible.rate, home); home: CurrencyCode; rateDate: LocalDate; sourceLabel)`.
`roundSnapshot` is money-correctness's single snapshot rounding (`SnapshotRounding.kt:45`), so `amountInHome` is the value the flagged save stores as `baseAmount` (INV-7). It is rendered by the **same** function the list uses for the converted amount (INV-15). It doesn't exist for `Home`. When not eligible, or when `roundSnapshot` returns `Unavailable` (then there is no `amountInHome`), no amount is shown (never 0; §12; save side OD-6).

### 1.7 Existing persisted fields written by a flagged create (no change)
`Expense.amount` = the original amount, exact to the currency's digits. `currency` = the selection. `date` = the instant.
`baseCurrency` = resolved home (≠ ""). `exchangeRateUsed` = `Eligible.rate`, stored unrounded (1.0 for Home).
`baseAmount` = `roundSnapshot(amount × Eligible.rate, home)` (HALF_EVEN at the home digits, `BigDecimal.valueOf`, never below one minor unit; money-correctness INV-16/17). Home: `baseAmount = amount`, identity, not rounded (TLC `:668-673`). The rate date isn't persisted (no column; "Later").

---

## 2. Owners (one rule, one owner)

| Rule | Definition owner | Enforcement owner | Consumers (advisory, same function) |
|---|---|---|---|
| Rate eligibility | `RateEligibilityEvaluator` (new, `A/domain/currency`, read-only, pure over stored rates) | TLC create path under the flag | AddExpense VM preview, ReceiptScan VM preview, installment section |
| Selectable set and proposal resolution | `CurrencySelectionPolicy` (new, `A/domain/currency`) | Evaluator (`Unsupported`) → TLC | the three VMs |
| Digits and cap | Digits: money-correctness `CurrencyFractionDigits.of` (`domain/core/money`), delegated by `CurrencyAmountPolicy.fractionDigits` (RF-10). Cap: `CurrencyAmountPolicy` (new, `A/domain/currency` or `domain/core/money`) | `TransactionValidator` (cap for all creates per OD-1; precision only when flagged) | VM inline validation |
| Snapshot rounding | money-correctness `roundSnapshot` (`domain/core/money/SnapshotRounding.kt`; RF-10) | TLC (flagged and unflagged snapshot) | preview use case (INV-15) |
| Foreign → no recurrence | `ManualExpenseRepository` | same | AddExpense VM hides the toggle |
| Receipt untouched on refusal | `ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt` | same | ReceiptScan VM |
| Declaration aborted on refusal | installment plan coordinator `declareWithNewExpense` | same | AddExpense VM (installment mode) |

Placement under `A/domain/currency` vs a use-case wrapper for VM access is a layering detail for the impact-mapper.

---

## 3. The eligibility rule (precise; decides D2, D3, D6)

Inputs: `currency`, `home`, `expenseInstant` (= `request.date`), `entryZone`. Rate table read through
`convertOutcome(amount = 1.0, currency, home, RateBasis.TRANSACTION_DATE, atMillis = B, StaleRatePolicy.None)`.

1. `currency == home` → `Home`. This holds even if home is inactive or has no rate source (BGN or HRK home).
2. `currency ∉ catalog` → `Unsupported(NOT_IN_CATALOG)`. `currency ∉ CurrencySelectionPolicy.selectable(home)` → `Unsupported(NOT_SELECTABLE)`.
3. `localDate = expenseInstant.atZone(entryZone).toLocalDate()`.
4. `B = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()`. Because every `validDate` is UTC midnight of its ECB date (F4), `validDate ≤ B` means `rateDate ≤ localDate`.
5. Lookup order is the existing one (direct first, then via EUR; F1). There is no "freshest of both" search.
6. If there's no result or `rateValidDate == null` → `None`.
7. `rateDate = Instant.ofEpochMilli(rateValidDate).atZone(ZoneOffset.UTC).toLocalDate()`. For via-EUR this is the **older leg** (F1 min).
8. `ageDays = ChronoUnit.DAYS.between(rateDate, localDate)`, which is ≥ 0 by construction. If `ageDays ≤ 7` → `Eligible`, else `Stale(rateDate, ageDays)`. **7 is inclusive.**

Decisions:
- **Zone = the zone at entry.** `entryZone` is `ZoneId.systemDefault()` captured once when the entry session starts (sheet or review screen opens). The same zone object is used for the displayed date, the date picker, the preview, and the request. TLC never uses its own device zone. Reasons: the user picked and saw the date in that zone, preview and coordinator evaluate identical inputs (so preview == save by construction), and tests are deterministic. A zone change in the middle of a session isn't tracked (AE-34).
- **The calendar date, not the instant.** That's the owner's decision. It fixes the Greek near-midnight false block (AE-6) and stops west-of-UTC users from picking up a rate published after their purchase (AE-11).
- **No wall clock.** Eligibility doesn't depend on "now", download time (`lastUpdated`) or amount (INV-3). Crossing midnight between preview and save changes nothing.
- **Future dates.** The evaluator stays pure. Whether a future date is accepted is decided by the existing date validators (manual: VM `:302-309` rejects anything after local today; receipt: [U]). A near-future date evaluates normally; a far-future date is `Stale` (AE-37).
- **Same-day rate.** A rate dated D applies to every expense on local date D, even before the 16:00 CET publication (the owner's "dated ≤ expense date").
- **Currencies with no rate source (D3).** Active catalog currencies that ECB doesn't list: **BGN** (removed 2 Jan 2026) and **RUB** (none since 1 Mar 2022) [C]. HRK is already inactive [V `:48`]. `NO_RATE_SOURCE = {BGN, RUB}` (mechanism: OD-2).
- **Age limit only for flagged saves (D6).** Unflagged TLC (`convertAsOf`, instant, any age) and analytics (F3, instant, any age) are unchanged. As a result, a flagged row's snapshot can use a different rate than analytics' read-time conversion for the same row (near midnight, or when only an older rate exists): OD-3. If imports ever adopt the limit, that belongs to money-correctness.

---

## 4. Invariants

| ID | Invariant (testable) | Single owner |
|---|---|---|
| INV-1 | Only `RateEligibilityEvaluator` decides whether a stored rate qualifies for a user-present save. Preview and TLC call it. No VM, repository or coordinator re-implements date or age logic. | Evaluator |
| INV-2 | The evaluation follows section 3 exactly: local calendar date in `entryZone`; ECB date = UTC calendar date of `validDate`; `0 ≤ ageDays ≤ 7` → `Eligible`; via-EUR uses the older leg; `Home` always eligible. | Evaluator |
| INV-3 | The evaluation is a pure function of (currency, home, expenseInstant, entryZone, rate rows). It never reads the wall clock, `lastUpdated`, or the amount, and never triggers network. | Evaluator |
| INV-4 | `localDate` is derived from `request.userPresentRate.entryZone`. TLC never substitutes `ZoneId.systemDefault()`. | TLC |
| INV-5 | Only a non-null `userPresentRate` triggers INV-6..8 and the flagged precision check. `isManualEntry` and `source` never do. Exactly three setters exist (section 1.2). Background, import, notification, email, bank, group, pending-review approval, batch-receipt (OD-5) and installment auto-record requests never set it. | TLC (reader); setter allow-list guard |
| INV-6 | A flagged create whose evaluation is not `Home`/`Eligible`, or whose home can't be resolved, returns `ValidationFailed(errors=[code], rateRefusal)`. It inserts no expense, calls no `insertAtomic`, runs no dedup, and returns an empty post-commit batch: no side effects, receipt link, recurring rule, plan or AI recommendation. The only rows written are the pre-existing `CREATE_ATTEMPTED` and one `CREATE_VALIDATION_FAILED` whose reason and metadata contain only the bounded code. | TLC |
| INV-7 | A successful flagged foreign create stores `exchangeRateUsed == Eligible.rate` (unrounded) and `baseAmount ==` the `Rounded.amount` of money-correctness `roundSnapshot(amount × Eligible.rate, home)`. Eligibility and snapshot come from **one** rate read in the same create call. Flagged creates never use the instant-based `convertAsOf`. (`roundSnapshot` → `Unavailable`: refuse, OD-6.) | TLC |
| INV-8 | Every row created by a flagged request has `baseCurrency ==` resolved home (≠ ""), `baseAmount > 0` and `exchangeRateUsed > 0`. Flagged saves never produce an unconverted row (never the money-correctness sentinel triple). If home resolution fails, the save is refused with `HOME_CURRENCY_UNRESOLVED`. (Eligible but home digits unavailable: refused, never the sentinel, OD-6.) | TLC |
| INV-9 | Requests with `userPresentRate == null` keep money-correctness's shipped behaviour, unchanged by this feature: instant-based any-age conversion with `baseAmount = roundSnapshot(convertedAmount, home)` and the unrounded rate; when there's no rate (or rounding is unavailable), the sentinel triple `baseAmount=0.0`, `baseCurrency=""`, `exchangeRateUsed=0.0` plus exactly one bounded `MISSING_RATE` diagnostic (metadata `{operation, reason}`); no precision check, same events, same dedup. The one exception is the per-currency cap, if OD-1 = A. | TLC (regression suite) |
| INV-10 | `selectable(home) = {c ∈ activeCatalog : c ∉ NO_RATE_SOURCE} ∪ {home}`, independent of rate availability. Legacy rows in any catalog currency keep converting (`fromCode` uses the full catalog, unchanged). | `CurrencySelectionPolicy` |
| INV-11 | A proposed currency (OCR, an installment default, later nl-quick-add) that isn't selectable becomes `Unset`. It is never silently replaced. `Unset` can't be saved. | `CurrencySelectionPolicy` |
| INV-12 | Digits have one accessor: money-correctness `CurrencyFractionDigits.of(CurrencyCode): Int?` (JDK `defaultFractionDigits`). `CurrencyAmountPolicy.fractionDigits` delegates to it. MEC creates no second digits accessor and no rounding helper; the only snapshot rounding is money-correctness `roundSnapshot` (HALF_EVEN), used by the TLC snapshot and the preview. Validator, preview and snapshot all resolve digits through `of`. The display formatter reads the same platform value (F14). `of` returns null for an unknown code or undefined digits; MEC behaviour on null is fail closed (OD-6): never assume 2 digits. | money-correctness `CurrencyFractionDigits.of` (digits) + `roundSnapshot` (rounding); `CurrencyAmountPolicy` delegates |
| INV-13 | An amount (and `myShareAmount` when present, [U]: assumed to be in the expense currency) with more fractional digits than `fractionDigits(c)` is **rejected, never rounded or truncated**. That means inline in the VM, and in `TransactionValidator` when flagged (`AMOUNT_PRECISION_EXCEEDED`). Nothing between the input text and TLC rounds the original amount (VM `:367-370` HALF_UP normalization removed). Switching currency re-validates the unchanged text. | `CurrencyAmountPolicy` (definition); `TransactionValidator` (flagged enforcement) |
| INV-14 | `cap(c)` is defined once in `CurrencyAmountPolicy` and doesn't depend on rate availability. `TransactionValidator` (`AMOUNT_EXCEEDS_MAX`) and VM inline validation consume it. The raw 1,000,000 guards at VM `:297` and `ManualExpenseRepository.kt:114` are removed (delegated). Non-catalog codes keep 1,000,000. | `CurrencyAmountPolicy` |
| INV-15 | The preview amount is `roundSnapshot(parsedAmount × Eligible.rate, home)`, rendered with `CurrencyFormatter.formatMoney(…, home)`, the same format function as the list's converted amount. Because the product uses the same operands and order as the snapshot and the same `roundSnapshot`, the previewed amount equals the stored `baseAmount` (INV-7) when no rate write happens between preview and save. The rate date shown is `Eligible.rateDate`, rendered as a `LocalDate` (never via an instant in the device zone, and never `lastUpdated`). No preview for `Home`; no amount when not eligible or when `roundSnapshot` returns `Unavailable`. (The interim "unrounded product" rule ended with money-correctness 1b, RF-10.) | Evaluator output + money-correctness `roundSnapshot` + shared formatter |
| INV-16 | No recurring rule is created from a manual save whose currency ≠ resolved home. The repository rejects that combination with a bounded error before calling TLC. | `ManualExpenseRepository` |
| INV-17 | A refused receipt save leaves the `ScannedReceipt` row, its file, match status, links and `ReceiptEvent`s unchanged. It returns a payload-free `RateNotEligibleException`. The review screen stays open with the user's edited fields. | `ReceiptLifecycleCoordinator` |
| INV-18 | A `declareWithNewExpense` refusal aborts the whole declaration (no purchase, plan, entries or `INSTALLMENT_PURCHASE` flag) and surfaces the same `RateRefusal` and text. Installment auto-record never sets the flag. | installment plan coordinator |
| INV-19 | Nothing this feature adds (logs, diagnostics, events, preview code) contains an amount, currency code, merchant, date, rate or zone. Refusals appear only as bounded codes. Counter `MANUAL_SAVE_BLOCKED_NO_RATE` has sub-reason ∈ {none, stale, unsupported, home_unresolved} and origin = `request.source` enum name. | TLC (refusal diagnostic); VMs (no preview logging) |
| INV-20 | No fetch happens at preview or save. "Refresh rates" is user-started only, via the existing `CurrencyRatesRepository.refresh`, and only when the R23 setting is on. Nothing enables the setting. | Evaluator (read-only); existing refresh owner |
| INV-21 | The dedupe key and currency-aware duplicate rules use the original amount and currency, as today. Eligibility is evaluated before dedup, so a refusal never yields `DuplicateSkipped`. | TLC / `DuplicateDetectionPolicy` |
| INV-22 | This feature adds no deletion or cleanup of `exchange_rates`. | exchange-rate store (unchanged) |
| INV-23 | Edit mode is unchanged in inc 1. The currency of an existing row is shown read-only. Amount or date edits use the existing TLC update path (unflagged, any age). Amount precision follows the row's currency. [U]: today's edit-mode currency handling. | AddExpense VM (edit branch) / TLC update (unchanged) |

Count: **23**.

---

## 5. Coordinator refusal (decides D1 placement, races)

**Placement.** After validation (step 2, which includes the per-currency cap and, when flagged, the precision check) and provenance (2b), at the conversion-snapshot step (`:529-585` at authoring; `:605-674` after money-correctness 1b, [V] 2026-10-23), **before dedup (step 4) and before any insert**. Reasons:
1. It needs the resolved home currency, which the snapshot step resolves.
2. The eligibility read *is* the snapshot read, so there's no time-of-check/time-of-use gap (INV-7).
3. It matches how existing validation failures are handled.

Flagged branch at the snapshot step:
- Home `Failed` → refuse with `HOME_CURRENCY_UNRESOLVED`. This is new fail-closed behaviour for manual saves when DataStore fails. Today those save with the `""` sentinel. That's acceptable because the user is present and can retry.
- `currency == home` → identity snapshot (as today).
- Otherwise call the evaluator. `Eligible` → snapshot from it: `exchangeRateUsed = Eligible.rate` (unrounded), `baseAmount = roundSnapshot(amount × Eligible.rate, home)` (INV-7; `Unavailable` → refuse, OD-6). Anything else → refuse. The flagged branch never falls through to the unflagged sentinel/`MISSING_RATE` handling.

**Events and diagnostics.** `CREATE_ATTEMPTED` was already written at step 1 (pre-existing for every create; its `reason` content is routed as RF-1). The refusal writes `CREATE_VALIDATION_FAILED` through the existing best-effort path with `errors=[code]`. That's a durable, bounded audit trail without a new `LifecycleEventType`. It also emits one bounded `MANUAL_SAVE_BLOCKED_NO_RATE` diagnostic through the existing controlled-diagnostic sink (the one behind `emitHomeCurrencyUnavailableDiagnosticBestEffort`, [U] exact API). The counter counts coordinator refusals only. Preview states where Save is disabled aren't counted.

**Transactions.** All flagged callers use `createExpenseDbOnlyV2` inside their own transaction. On refusal they don't throw:
- Manual: the repository returns a typed error.
- Receipt: `Result.failure(RateNotEligibleException)`, as for `ValidationFailed` today.
- Installment: the plan coordinator returns its typed refusal before inserting the plan.

So only the two audit events commit. If an implementer chooses to roll back instead, the events roll back too. Either is acceptable. Expense, receipt-link and plan rows must not exist in either case.

**Races.** The preview is advisory and the coordinator is authoritative.
- Rates deleted or changed between preview and save → TLC re-evaluates and refuses or uses the newer rate (AE-22).
- If no rate write happens between preview and save, preview == stored snapshot, because both evaluate identical inputs.
- The preview must re-evaluate when currency, date, home or zone change, and when a rate refresh completes.

**Unflagged callers** are unchanged by this feature (INV-9, AE-21, AE-29). They keep money-correctness's shipped behaviour: converted rows store `roundSnapshot(convertedAmount, home)` with the unrounded rate; no-rate (or rounding-unavailable) rows store the sentinel triple `baseAmount=0.0`, `baseCurrency=""`, `exchangeRateUsed=0.0` plus exactly one bounded `MISSING_RATE` diagnostic (metadata `{operation, reason}`) [V] TLC `:630-667`, `:337-367`.

---

## 6. Decimals, rounding, cap (decides D4, D5)

- **Single digits source** = the platform ISO table via money-correctness's single accessor `CurrencyFractionDigits.of` (`domain/core/money`; money-correctness G2-1, MEC plan "Amendment 2026-10-16"). `CurrencyAmountPolicy.fractionDigits` delegates to it; MEC adds no second accessor (INV-12). The allocator and `roundSnapshot` already use `of`; the display formatter reads the same JDK value (F14). When `of` returns null (unknown code or undefined digits), MEC fails closed (OD-6). An explicit table on `SupportedCurrency` was rejected because the formatter and allocator would become a second source unless they were also changed (global display blast radius). Risk: JVM unit tests use JDK currency data, while the device uses ICU. Tests pin all 28 values, and one instrumented check is recommended ([U] for minSdk ICU data).
- **Rejection, not rounding** (INV-13). The parser is currency-agnostic and runs after R16. The precision check runs on the parsed value. Interaction with R16 and F15: for JPY, `"1,500"` → 1500 (accepted), `"1500,50"` → 1500.5 (rejected), `"1.500"` → 1.5 (rejected; the user retypes). The parser never guesses using the currency.
- **Rounding (D5).** Preview and snapshot use the same product `amount × rate` (same Double operands and order), so the unrounded products match bit for bit. Both pass it through money-correctness `roundSnapshot(product, home)` (HALF_EVEN at the home digits from `CurrencyFractionDigits.of`, applied to `BigDecimal.valueOf(product)`; a positive value never rounds to 0, it becomes one minor unit). The stored `baseAmount` is `Rounded.amount`; `exchangeRateUsed` stays the unrounded `Eligible.rate`. The preview formats `Rounded.amount` with `CurrencyFormatter.formatMoney(…, home)`, the same call the list uses, so the preview amount == the stored snapshot (INV-7) and preview text == list text (AE-38; the list's exact call site is [U] for the impact-mapper). `roundSnapshot` → `Unavailable`: no preview amount; save side refused (OD-6). This feature adds no rounding of its own and never rounds the original amount (INV-13). (Interim "unrounded snapshot" rule ended with money-correctness 1b, RF-10.)
- **Cap (OD-1).** Recommended static table (a typo guard, not a business limit). Values are powers of ten chosen so the cap is roughly between €100k and €1.2M at approximate 2026 reference rates. The reference rates are documentation only and are never used at runtime:

| cap | currencies |
|---|---|
| 1,000,000 | EUR USD GBP CHF CAD AUD NZD SGD CNY HKD DKK PLN RON BRL BGN HRK |
| 10,000,000 | MXN ZAR SEK NOK CZK TRY |
| 100,000,000 | JPY INR RUB HUF ISK |
| 1,000,000,000 | KRW |
| 1,000,000 | any non-catalog code (unchanged) |

Strong currencies keep today's limit exactly. Amounts up to 10⁹ with 0 digits, or 10⁷ with 2 digits, are exact in a Double.

---

## 7. State machines

### 7.1 Add-expense save (manual, STANDARD or INSTALLMENT mode)
`EDITING → SAVING → {CLOSED (Created) | EDITING+refusal(reason, latestRateDate) | EDITING+fieldError | EDITING+duplicate (existing) | EDITING+error}`
- Triggers for preview re-evaluation (no save state change): currency, date, amount (amount affects only the preview figure), installments toggle (changes `mode`), rate-refresh completion.
- Illegal: `SAVING → SAVING` (the existing double-tap guard). Automatic retry or automatic fetch after a refusal. Saving with `Unset`. Saving foreign + Repeat (toggle hidden; repository rejects, INV-16).
- Retry: user-started only, re-runs the full path. A refusal is idempotent; each attempt appends 2 bounded audit events.
- Cancellation (sheet closed during SAVING): coroutine cancellation, Room rolls back, no row. `CancellationException` is rethrown and never mapped to a refusal or error state.
- Process death: the add-expense package has no `SavedStateHandle` (F13), so the form is lost entirely. Amount and currency are never restored separately. If TLC had committed, the row exists, and re-entry hits existing dedup.

### 7.2 Receipt review save (single receipt; review save + quick-save confirm)
`REVIEW → SAVING → {DONE (Created + linked) | REVIEW+rateRefusal | REVIEW+duplicate | REVIEW+error}`.
The persisted receipt lifecycle doesn't change: the processed (draft) receipt stays as it was before the save (section 8).
Process death in review: existing restoration behaviour [U]. If the review is restored, the currency is re-resolved through `CurrencySelectionPolicy` (never defaulted to home).

### 7.3 Installment declare-with-new-expense
`FORM → DECLARING → {DONE | FORM+rateRefusal | FORM+other refusal (loan AE-22)}`. On refusal the declaration is atomic and nothing is written (INV-18).

### 7.4 Preview evaluation
`NOT_APPLICABLE (Home) | EVALUATING | RESULT(RateEligibility)`. The latest inputs win (stale evaluations are discarded). There are no side effects.

---

## 8. Receipt review: blocked save (D7)

- The receipt row and file were persisted when the receipt was processed (`ReceiptRepository.processReceipt` draft insert, `LEGAL_PATHS.md:136-141`). The refusal happens inside `createExpenseDbOnlyV2` before any link, so `ReceiptLinkService` isn't called, no `ReceiptEvent` is written, and `matchStatus`/`expenseId` are untouched. No orphan file is created and none is cleaned up. The refusal path must not call `AssetCleanupCoordinator`.
- The coordinator maps `rateRefusal` to `RateNotEligibleException` (payload-free). The VM maps that to a typed `SaveReceiptResult` (not "UNKNOWN_ERROR"), keeps the step at review, and keeps the edit fields: merchant, amount, currency, date, category. The existing privacy clearing of raw OCR and debug text on save failure may stay.
- Retry from the same screen after the user changes the currency or date, or refreshes rates.
- Abandoning the screen after a refusal has the same semantics as abandoning review today: the receipt stays unlinked and can be auto-matched later by `ReceiptMatchingWorker` ([C] LEGAL_PATHS `:170-176`). A pre-existing race remains unchanged: if auto-match claims the receipt while it's in review, a later save fails at link time and rolls back.
- Batch receipt review (`RECEIPT_BATCH_REVIEW`) and email receipts are unflagged (OD-5).
- [U] RF-6: how `receipt.parsedDate` is built (zone). The receipt slice must confirm that the instant represents the printed date in `entryZone`.

---

## 9. Loan S11 convergence (D9, N2)

- **One control, one rule.** The general currency control replaces S11's EUR/USD segmented control. While "Pay in installments" is on, `mode = INSTALLMENT`, so options are restricted to {EUR, USD} ∩ selectable. If the current selection isn't in that set, it becomes `Unset` (no silent switch).
- `declareWithNewExpense` passes `userPresentRate(entryZone)` on the purchase request, so the same evaluator, the same TLC refusal, and the same refusal text apply. S11's D-8 "FX unavailable → keep form" **is** this refusal (INV-18).
- **D-8 answered:** TLC does not refuse unflagged manual USD creates; with no rate it saves the money-correctness sentinel triple (`baseCurrency=""`) plus one `MISSING_RATE` diagnostic (F18). S11 therefore can't produce its FX refusal without this spec's flag. That makes it a sequencing decision (OD-4).
- Installment auto-record (background) stays unflagged. The loan spec Q9 premise that TLC refuses creation when no rate exists (`AUTO_RECORD_DEFERRED_FX`) doesn't hold, so that branch is unreachable through TLC (RF-3, for the loan planner).
- Repeat and Installments stay mutually exclusive (S11). Foreign Repeat is hidden in any case.

---

## 10. Rate retention vs the 7-day rule (D8), and framing for increment 1b

- **Inc 1:** there is no retention (F7). The 7-day rule only needs rows dated within [localDate − 7, localDate], and analytics needs every row of any age (F3). So any future cleanup must key on `validDate` (not `lastUpdated`), keep every row analytics might need, and needs its own analytics decision (INV-22).
- **Inc 1b frame (not specified further):**
  - A user-started download of the ECB 90-day history, behind the same R23 setting. Never automatic at save time (INV-20).
  - Storage: **store all cross pairs, as the daily refresh does.** About 25 ECB-listed catalog currencies including EUR → 600 rows per publication day, ×~64 business days ≈ **38k rows** per 90-day file. Idempotent through the unique (from, to, validDate) REPLACE. `source="ecb"`, `validDate` = each day's UTC midnight, `lastUpdated` = download time (`getLatestRateForPair` orders by `validDate` first, so it isn't poisoned, `ExchangeRateDao.kt:28-34`).
  - **EUR legs only is rejected for 1b.** Lookups everywhere are direct-first (evaluator, unflagged TLC, analytics), so an older direct pair from a daily refresh would hide fresher EUR legs for non-EUR-home users and produce false `Stale` results. Revisit only together with a "freshest of direct/via-EUR" lookup change across all three.
  - Schema: none expected. Privacy review is required (bigger payload; fetch timing hints at a backdated entry).
  - Dates older than the window stay `None` or `Stale`.
- **N1** is unchanged: inc 1 ships alone only if R23 part 1 adds a periodic refresh. Without it, refresh only adds the latest date and can't unblock past dates (AE-4).

---

## 11. Duplicates (D10): confirmed unchanged

The dedupe key and currency-aware duplicate rules compare original amount and currency (F9; `LEGAL_PATHS.md:76-98`).
A manual 20.00 USD entry and a later card notification for 18.52 EUR (same purchase) aren't detected as duplicates, so both rows exist. This is pre-existing (receipts behave the same) and **not changed** by this feature. Cross-currency duplicate detection belongs to a later item (AE-31).

---

## 12. Edge cases

| Case | Rule |
|---|---|
| Mixed currencies in totals | Unchanged. Headers hide mixed sums; analytics uses MoneyAggregate (LEGAL_PATHS Money). |
| Missing or stale FX | `None`/`Stale` → refusal for flagged saves; unflagged unchanged (money-correctness: no rate → sentinel triple + one `MISSING_RATE`; any-age rate → converted). |
| Unavailable vs zero | No preview amount when unavailable (including `roundSnapshot` → `Unavailable`); flagged rows never have `baseAmount` 0 (INV-8). Unflagged no-rate rows carry `baseAmount=0.0` only as part of the sentinel triple with `baseCurrency=""` (unavailable, not zero; money-correctness). |
| Rounding conservation | No splits here. The snapshot is `roundSnapshot(amount × rate, home)` (money-correctness, HALF_EVEN, one-minor-unit floor); the preview uses the same function on the same product, so preview == stored snapshot (INV-7, INV-15) and preview text == list text (AE-38). The original amount is never rounded (INV-13). |
| Time zones | Calendar date in `entryZone`; rate date rendered as a LocalDate (AE-6, 7, 11, 34). |
| DST | Ages are calendar days, so 23/25-hour days don't matter (AE-10). |
| ECB holidays | The largest TARGET gap (Easter Thu → Tue, Christmas/New Year) is ≤ 5 days, inside the 7-day window (AE-9). |
| Month-end recurrence | N/A (foreign recurring hidden). |
| Edits/deletes of past items | Unchanged (INV-23). Deletes don't touch rates. |
| Partial/failed receipt processing | A refusal leaves the receipt as it was (section 8). OCR proposing an unknown or inactive currency → `Unset`. |
| Duplicates/idempotency | Section 11, plus the double-tap guard. A committed save followed by process death → existing dedup. |
| Empty states | No rates at all → every foreign currency is `None`. The options list is still shown (INV-10). |
| Home currency is BGN/RUB/HRK | Home entries save; foreign entries `None`/`Stale` (refresh throws `HOME_CURRENCY_QUOTE_UNAVAILABLE`, [V] `CurrencyRatesRepositoryImpl.kt:80-82`). The prompt to switch home isn't in this feature. |
| Home changed between preview and save | TLC's resolved home is authoritative and the refusal or snapshot follows it. |
| Transaction types | The rule doesn't depend on type (TLC converts every type today). UX may restrict the control. |
| Shared expense `myShareAmount` | Same digits as the expense currency (INV-13, [U] semantics). |

---

## 13. Persistence impact. Schema verdict: **NONE** (inc 1)

- No new table or column. No version bump, migration, backfill or destructive change.
- `userPresentRate` lives only on the request object. `RateRefusal` codes go into existing `transaction_events` text columns. Digits and caps are code rules. The snapshot uses existing `Expense` fields.
- New `CreateExpenseRequest` field (defaulted) and `ValidationFailed` field (defaulted) are source-compatible.
- Inc 1b: NONE expected (same table and unique index). Storage growth is about 38k rows per 90-day window (section 10).
- Later inc 2 (typed rate): schema likely, out of scope.

## 14. Privacy rules

- Stored: nothing new. Exported: nothing new. Logged: nothing containing amount, currency, merchant, date, rate or zone (INV-19).
- Bounded codes: `RATE_NOT_ELIGIBLE_{NONE,STALE,UNSUPPORTED,HOME_UNRESOLVED}`, `AMOUNT_PRECISION_EXCEEDED`, existing `AMOUNT_EXCEEDS_MAX`; counter `MANUAL_SAVE_BLOCKED_NO_RATE` with sub-reason and source enum.
- The flagged path never reaches the unflagged no-rate branch (sentinel triple + `MISSING_RATE`, TLC `:654-666`; it replaced the former `Timber.w` at `:573-577` in money-correctness 1b), because the refusal comes first. A flagged refusal emits only `MANUAL_SAVE_BLOCKED_NO_RATE`.
- No network at preview or save. Refresh is user-started and gated on R23 (INV-20).
- Pre-existing, routed (RF-1): the `CREATE_ATTEMPTED` `reason` stores merchant + amount + currency for every create, including refused attempts. Whether `transaction_events` is exported or backed up is [U].

---

## 15. Acceptance examples (synthetic)

Defaults: home EUR. Zone `Europe/Athens` (UTC+3 until 26 Oct 2025, then UTC+2). Year 2025, to match the brief's weekdays.
ECB fixtures (EUR→X): USD 1.0800, GBP 0.8700, JPY 162.50, KRW 1500.00. Stored pair X→EUR = 1/value. Rows use `validDate = date.atStartOfDay(UTC)`, `source="ecb"`.
"Flagged" = `userPresentRate(Europe/Athens)`. "Refused(R)" = `ValidationFailed([code], RateRefusal(R, …))`, no expense row, empty post-commit batch.

- **AE-1 Normal (ex 1; INV-2, 7, 15).** Given a rate for 15 Oct. When a manual USD 20.00 entry is dated Wed 15 Oct 12:00. Then `Eligible(age 0, DIRECT, ECB)`; the preview reads "≈ €18.52 · ECB rate of 15 Oct". On save: `currency=USD`, `amount=20.0`, `exchangeRateUsed=1/1.08` (unrounded), `baseAmount=roundSnapshot(20×(1/1.08), EUR)` = 18.52, `baseCurrency=EUR`.
- **AE-2 Weekend (ex 2).** Given the latest rate is Fri 10 Oct. When USD 20.00 is dated Sun 12 Oct. Then `Eligible(age 2)` and the label reads "ECB rate of 10 Oct".
- **AE-3 No rate (ex 3; INV-6).** Given an empty rate table. When USD 20.00 is saved for 15 Oct. Then Refused(NO_RATE). Expense count unchanged. No dedup query, no post-commit actions. Events: `CREATE_ATTEMPTED` + `CREATE_VALIDATION_FAILED(["RATE_NOT_ELIGIBLE_NONE"])`. Form kept.
- **AE-4 Stale 8 days (ex 4).** Given rates {7 Oct}. When USD is dated 15 Oct. Then Refused(STALE_RATE, latestRateDate 7 Oct). Given rates {7 Oct, 16 Oct} (refreshed after 16:00 CET on 16 Oct). When the 15 Oct entry is saved. Then still STALE(7 Oct). When it's re-dated 16 Oct. Then `Eligible(age 0)`.
- **AE-5 Boundary 7/8.** Given rates {Wed 8 Oct}. Dated Wed 15 Oct → `Eligible(age 7)`. Dated Thu 16 Oct → `Stale(8 Oct, 8)`.
- **AE-6 Greece, near midnight, allowed (ex 5a).** Given the first ever download at 16 Oct 17:00 Athens stored only the 16 Oct rate. When GBP 30.00 is dated 16 Oct 01:30 Athens (= 15 Oct 22:30 UTC). Then `localDate` = 16 Oct, `B` = 16 Oct 00:00Z, `Eligible(age 0)`, preview "≈ €34.48 · ECB rate of 16 Oct". The instant-based lookup would have returned nothing (the regression this prevents).
- **AE-7 Greece, near midnight, reverse (ex 5b).** Given the same rates. When the entry is dated 15 Oct 23:30 Athens. Then `localDate` = 15 Oct and the result is Refused(NO_RATE).
- **AE-8 First download date.** Given the first refresh ran on Wed 15 Oct 10:00 Athens (before publication) and stored the 14 Oct rate. Dated 15 Oct → `Eligible(age 1)`. Dated 13 Oct → Refused(NO_RATE).
- **AE-9 Holiday gap.** Given the latest rate is Thu 2 Apr 2026 (Good Friday and Easter Monday 6 Apr are closed). Dated Tue 7 Apr 09:00 → `Eligible(age 5)`.
- **AE-10 DST, calendar days not hours.** Given the rate of Fri 24 Oct. When an entry is dated Fri 31 Oct 23:59 Athens (UTC+2; elapsed time is more than 7×24 h). Then `Eligible(age 7)`. A milliseconds-based 7-day policy would wrongly say stale.
- **AE-11 West of UTC (rendering + no later rate).** Given zone `America/New_York` and rates {15 Oct, 16 Oct}. When an entry is dated 15 Oct 21:00 local (= 16 Oct 01:00Z). Then the 15 Oct rate is used (`Eligible(age 0)`) and the label reads "15 Oct". Rendering the date as an instant in NY time would show "14 Oct", which is forbidden.
- **AE-12 Via-EUR uses the older leg.** Given no direct GBP→USD pair, GBP→EUR dated 14 Oct and EUR→USD dated 6 Oct (synthetic), with home USD. When GBP is dated 15 Oct. Then `rateDate` = 6 Oct, age 9 → `Stale(6 Oct)`.
- **AE-13 Home.** When EUR 12.50 is saved flagged. Then `Home`: no preview, `exchangeRateUsed=1.0`, `baseAmount=12.5`, rate table never read.
- **AE-14 Home unresolved.** Given `resolveHomeCurrency()` returns `Failed`. Flagged save → Refused(HOME_CURRENCY_UNRESOLVED), no row. The same request unflagged → today's row with `baseCurrency=""` plus the existing diagnostic (unchanged).
- **AE-15 JPY whole yen (ex 6).** "1500" → valid; displays "¥1,500"; preview "≈ €9.23". "1500,50" → inline `PrecisionExceeded(0)`; the field keeps "1500,50"; Save is disabled.
- **AE-16 Currency switch.** Given EUR "12.50". When the user switches to JPY. Then the field shows "12.50" marked invalid (not truncated to 12). Switching back to EUR makes it valid again.
- **AE-17 R16 parse.** For JPY, "1,500" → 1500 (valid); "1.500" → 1.5 → `PrecisionExceeded` (never reinterpreted as 1500).
- **AE-18 Caps (OD-1 = A).** KRW 1,500,000 → allowed (≈ €1,000); KRW 1,000,000,001 → `AMOUNT_EXCEEDS_MAX`; EUR 1,000,000.01 → `AMOUNT_EXCEEDS_MAX` (unchanged); USD 1,000,000.00 → allowed. A rate being present or absent never changes the cap result.
- **AE-19 Precision at TLC.** Flagged JPY 1500.5 → `ValidationFailed(["AMOUNT_PRECISION_EXCEEDED"])`, no row. An unflagged notification JPY 1500.5 → created as today.
- **AE-20 Blocked writes nothing.** Given a refused save (any reason). Then: `expenses`, `scanned_receipts`, receipt links, recurring rules and installment tables are unchanged; there are exactly two new `transaction_events` rows, with no amount, currency, merchant or date in the refusal event; zero post-commit actions; one `MANUAL_SAVE_BLOCKED_NO_RATE` increment.
- **AE-21 Unflagged unchanged (INV-9; money-correctness behaviour).** A notification USD 20.00 with an empty table → row created (not refused) with the sentinel triple `baseAmount=0.0`, `baseCurrency=""`, `exchangeRateUsed=0.0`, plus exactly one bounded `MISSING_RATE` diagnostic with metadata `{operation="createExpense", reason="MISSING_RATE"}` and nothing else (no amount, currency, merchant or date); no `RATE_NOT_ELIGIBLE_*` event and no `MANUAL_SAVE_BLOCKED_NO_RATE`. A CSV import USD 20.00 dated 15 Oct with the only rate (USD 1.0800) dated 5 Sep (40 days older) → converted with that rate (any age): `exchangeRateUsed=1/1.08` (unrounded), `baseAmount=roundSnapshot(20×(1/1.08), EUR)` = 18.52, `baseCurrency=EUR`; no `MISSING_RATE`.
- **AE-22 Races.** Preview `Eligible(15 Oct)`, then the rate table is cleared before Save → Refused(NO_RATE). Preview `Eligible(14 Oct)`, then a refresh stores 15 Oct before Save → the snapshot uses the 15 Oct rate and the preview re-evaluates. With no rate write in between → stored `exchangeRateUsed` == the previewed rate.
- **AE-23 Receipt review eligible (ex 7a).** OCR proposes USD 42.00 and the user confirms. With the 15 Oct rate the preview shows "≈ €38.89 · ECB rate of 15 Oct". Save → expense created and linked (one transaction).
- **AE-24 Receipt review refused (ex 7b; INV-17).** Same receipt, empty rate table. Save → `RateNotEligibleException(NO_RATE)`. The receipt row, file, `matchStatus`, links and `ReceiptEvent`s are unchanged. The screen stays in review with merchant, amount, currency and date intact. The user changes the currency to EUR and saves → created and linked.
- **AE-25 Inactive proposal.** OCR proposes BGN (home EUR) → control `Unset(PROPOSAL_NOT_SELECTABLE)`, Save disabled. A request forced flagged with BGN → Refused(UNSUPPORTED_CURRENCY).
- **AE-26 Recurring (ex 8; INV-16).** USD is selected, so Repeat is hidden. If Repeat was on and the user switches to USD, the recurrence is cleared (UX presents this). A direct repository call with USD + monthly → bounded error, no expense, no rule. EUR + monthly → unchanged.
- **AE-27 BGN/RUB (ex 9).** The options list excludes BGN, RUB and HRK for a EUR-home user. A legacy 2024 BGN row still converts in analytics. A BGN-home user: a BGN expense saves as `Home`; an EUR expense dated 15 Oct 2026, with only 2025 pairs stored → `Stale`, Refused(STALE_RATE).
- **AE-28 Installment declare (INV-18).** In installment mode a JPY selection becomes `Unset`. USD 1,200.00 with an empty table → Refused(NO_RATE): no purchase, plan or entries. With the 15 Oct rate → purchase (converted), plan and entries commit together.
- **AE-29 Installment auto-record.** The background run creates a USD installment expense with no rate (`userPresentRate == null`) → unflagged create: row created with the sentinel triple `baseAmount=0.0`, `baseCurrency=""`, `exchangeRateUsed=0.0` plus exactly one bounded `MISSING_RATE` diagnostic (money-correctness behaviour). Never refused by this rule; no `RateRefusal`, no `MANUAL_SAVE_BLOCKED_NO_RATE`.
- **AE-30 Logs.** Across AE-1..AE-28, captured Timber output, diagnostics and the refusal events contain no amount, currency code, merchant, date, rate or zone.
- **AE-31 Duplicates (D10).** A manual USD 20.00 at "Cafe X" on 15 Oct, then a notification for EUR 18.52 at "Cafe X" on 15 Oct → both rows exist (currency differs, rule unchanged). A second manual USD 20.00 at "Cafe X" on 15 Oct (eligible) → existing duplicate handling.
- **AE-32 Process death.** Killed mid-entry → no row; reopening shows a fresh form with home selected. Killed after commit → one row; re-entering the same data → existing dedup.
- **AE-33 Cancellation.** The sheet is dismissed while SAVING → transaction rolled back, no row, `CancellationException` propagated, and no refusal or error state recorded.
- **AE-34 Zone change mid-session.** The session opens in Athens and the device zone becomes `Europe/London` before Save. Preview and request both use Athens, so the result equals the preview.
- **AE-35 Setter allow-list (INV-5).** A static check shows `userPresentRate` is set only in `ManualExpenseRepository`, `ReceiptScanViewModel` and `declareWithNewExpense`.
- **AE-36 Edit mode (INV-23).** Editing a USD row's amount → currency shown read-only; the existing update path applies (no age limit), and USD precision is applied.
- **AE-37 Future date.** For a receipt dated tomorrow (if existing validators allow it) with today's rate → `Eligible(age 1)`. For a date +30 days → `Stale`. A manual future date → rejected by the existing VM rule (unchanged).
- **AE-38 Preview == stored snapshot; preview text == list text (INV-7, INV-15).** Given AE-1. The preview amount is `roundSnapshot(20.0 × (1/1.08), EUR)` = 18.52, equal to the stored `baseAmount` 18.52. The preview text `CurrencyFormatter.formatMoney(18.52, "EUR")` = "€18.52" equals the list's converted-amount text for the saved row (list call site [U]).

---

## 16. Open decisions (owner)

| ID | Decision | Recommended default | Alternative |
|---|---|---|---|
| OD-1 | Per-currency cap owner and scope. `TransactionValidator` caps every source today (F10). | **A:** `CurrencyAmountPolicy` table (section 6) used by `TransactionValidator` for all creates. Strong currencies are unchanged; weak-currency imports/notifications above 1,000,000 units are now accepted (a relaxed typo guard). | B: per-currency cap only for flagged requests and 1,000,000 for the rest. That means two cap rules, which violates single-owner. |
| OD-2 | How BGN/RUB become "inactive for new selection" | Feature-local `NO_RATE_SOURCE = {BGN, RUB}` used only by `CurrencySelectionPolicy`; `isActive` untouched | Flip `isActive=false` like HRK. That also removes them from the home-currency picker and changes `fromActiveCode` results for `BudgetAutopilotEngine.kt:410` and `BillReminderManager.kt:199`, which overlaps release item RQ3 |
| OD-3 | Divergence between the flagged snapshot (local-date bound, ≤ 7 days) and analytics read-time conversion (instant bound, any age, F3) | Accept in inc 1 and route alignment of analytics, unflagged TLC and Transactions to a calendar-date basis to `money-correctness-1.0.x` | Change analytics now (brief non-goal: "no analytics change") |
| OD-4 | Loan S11 sequencing: S11 needs a refusal that TLC doesn't have (F18) | Land this feature's domain core first (evaluator, selection/amount policies, request flag, TLC refusal, `ValidationFailed.rateRefusal`; no UI) as a prerequisite slice, so S11 uses it from day one. The later control merge is then UI-only | S11 builds its own interim pre-check (a duplicate rule, removed at convergence), or S11 waits for this feature |
| OD-5 | Is batch receipt review (`RECEIPT_BATCH_REVIEW`) "user-confirmed"? | Unflagged in inc 1 (treated like pending-review approval) | Flag it, which needs per-item refusal UX in batch |
| OD-6 | **DECIDED 2026-10-23 (orchestrator under the owner's standing instruction; owner may override): FAIL CLOSED.** When money-correctness `CurrencyFractionDigits.of(code)` returns null (and so `roundSnapshot` returns `Unavailable(DIGITS_UNAVAILABLE)`): (a) `CurrencyAmountPolicy.check` returns `DigitsUnavailable` (never assumes 2), so the flagged precision step refuses with a bounded amount-validation code; (b) VM inline validation shows the amount as invalid; edit-mode legacy rows in an unknown code keep the unflagged path (no new rule); (c) a flagged `Eligible` create whose home digits are unavailable is refused (bounded refusal; never the unflagged sentinel, INV-8); (d) preview shows no amount and Save is disabled for the flagged path. Normal UI paths offer only catalog codes, so this is defensive. | Orchestrator (owner override possible) | Implemented in A1 (`AmountCheck.DigitsUnavailable`, `UserPresentRatePreviewResult.AmountUnavailable`); A2 maps the refusal |

---

## 17. Routed findings (not acted on) and remaining UNVERIFIED items

- **RF-1 (privacy, pre-existing):** TLC `:397` writes merchant + amount + currency into `CREATE_ATTEMPTED.reason` for every create. Route to money-correctness/privacy. Export/backup of `transaction_events` is [U].
- **RF-2 (date, pre-existing, [U]):** `AddExpenseSheet.kt:847-849` converts `datePickerState.selectedDateMillis` (Material3 = UTC midnight, [U]) with `ZoneId.systemDefault()`. West of UTC this may shift the picked date back one day, which affects every manual entry and this feature's local-date rule. A debugger should confirm, then route to the release/1.0.x track.
- **RF-3 (loan):** loan `domain-spec.md:575-577` `AUTO_RECORD_DEFERRED_FX` assumes TLC refuses on a missing rate. It doesn't (F18). The loan planner decides.
- **RF-4 (impact-mapper):** other raw 1,000,000 copies: `AppConfig.MAX_TRANSACTION_AMOUNT`, `AmountUtils.isValidAmount` default and doc, `AppParserRegistry.kt:69`. Inventory their consumers. They're out of scope unless OD-1 extends.
- **RF-5:** TLC `:573-577` `Timber.w` (routed to money-correctness). **Resolved in money-correctness 1b** (RF-10, [V] 2026-10-23): the log is gone; the unflagged no-rate branch emits one bounded `MISSING_RATE` diagnostic (TLC `:654-666`, `:337-367`).
- **RF-6 (receipt slice):** construction of the `receipt.parsedDate` instant (zone). Must match `entryZone` semantics.
- [U] remaining: on-device ICU digits at minSdk; the Transactions list's exact converted-amount formatting call; the controlled-diagnostic API name; `myShareAmount` currency semantics; edit-mode currency handling today; receipt review restoration after process death; backup-restored rate rows with a non-ECB `source`.

## 18. Proposed architecture-doc changes (proposal only; not edited)

`LEGAL_PATHS.md` → Expense CREATE, add:
```
USER-PRESENT RATE REQUIREMENT (manual-entry-currency):
  CreateExpenseRequest.userPresentRate (default null) set ONLY by ManualExpenseRepository.addManualExpense,
  ReceiptScanViewModel single-receipt save, installment declareWithNewExpense
  → TLC evaluates RateEligibilityEvaluator at the conversion snapshot (before dedup/insert)
  → not eligible: ValidationFailed(rateRefusal), no row, no post-commit actions
FORBIDDEN:
  ❌ Re-implementing rate-age/date eligibility outside RateEligibilityEvaluator
  ❌ Setting userPresentRate from background/import/notification/bank/group/review-approval sources
  ❌ Flagged creates converting via instant-based convertAsOf
  ❌ Rounding/truncating a user-entered amount before TLC (reject excess precision instead)
```
`LEGAL_PATHS.md` → Money / Currency, add: `ELIGIBLE RATE for user-present save → RateEligibilityEvaluator (calendar date in entry zone, ≤ 7 days, older leg)`; `AMOUNT digits/cap → CurrencyAmountPolicy`.

---

## 1b — ECB 90-day history (domain rules)

Author: domain-modeler (Opus), 2026-10-30. Docs only. Branch `feature/manual-entry-currency` at aa397b95. Money and network privacy, **T3**.
Inputs: brief :18, :38-39, :84, :95; critique :37, :54, :60; this spec §10 and §13 (:349); plan H-1 (:140), :174, D-B1-1 (:290), :382, F-B2-HOME-CHANGE (:474).
This section replaces the §10 "Inc 1b frame" bullets where they differ; §10 stays as the dated frame. Sections 0-18 still hold. INV-20 and INV-22 are extended here, not changed.
Reconciled 2026-10-30 per owner-approvals 94 (D-1b-1..6). Changes: explicit user Cancel with INV-1b-21 and a `Cancelled` outcome (D-1b-3, 1b.7, 1b.10); the outcome-to-UX-state mapping is referenced at 1b.7 and defined in `ux-flows.md` 1b.2 (D-1b-5); the Currency-management "Rate history" row is deferred as F-1b-CURMGMT (D-1b-4); INV-1b-5 (refresh first, D-1b-1) and INV-1b-20 (discard, D-1b-2) stand as written and the UX follows them; VG-1b-1 is a STOP condition before S1 code (D-1b-6).
IDs: facts F1b-n, invariants INV-1b-n, examples AE-1b-n. Tags as in the header: [V] checked in this pass, [C] carried, [U] unverified.

### 1b.0 Facts this section depends on

| # | Fact | Tag |
|---|---|---|
| F1b-1 | Daily refresh: the privacy gate runs before any HTTP (`:48-63`); then a GET on the constant `ECB_DAILY_RATES_URL` (`:41`) with 10 s connect/read timeouts and redirects left at the platform default (followed). Non-2xx and parse failures are thrown as exceptions; success returns `Refreshed(storedPairs)` (`:65-83`). | [V] `CurrencyRatesRepositoryImpl.kt` |
| F1b-2 | The daily parser requires exactly one dated `Cube` (`:137-142`, `MISSING`/`CONFLICTING`) and parses `time` as an ISO date (`:149-153`). It skips a rate child that is blank, non-numeric, non-finite or ≤ 0 (`:157-165`). It reads with one XXE-hardened factory (`:173-188`): secure processing on, DOCTYPE disallowed, external entities and DTD off, no XInclude, no entity expansion. | [V] same file |
| F1b-3 | Cross pairs: every catalog currency that ECB lists (EUR included as 1.0), every ordered pair as `toEur / fromEur`, finite and > 0 only. The home must be listed, else `HOME_CURRENCY_QUOTE_UNAVAILABLE` (BGN/RUB home); an unknown home throws `INVALID_HOME_CURRENCY`. `setLastRateUpdate(now)` runs only if ≥ 1 pair was stored (`:86-121`). | [V] same file |
| F1b-4 | `CurrencyConverter.storeRates` takes **one** `validDate` per call, stamps `lastUpdated = now`, skips invalid rates and makes one `insertOrUpdateAll` call (`CurrencyConverter.kt:644-666`). The adapter checks `DatabaseWriteBarrier`, then makes one bulk DAO insert (`ExchangeRateStoreAdapter.kt:36-39`). The DAO uses REPLACE on the unique (from, to, validDate) index (`ExchangeRateDao.kt:19-26`; `ExchangeRate.kt:23-27`). A suspend `@Insert` of a list runs in one Room transaction ([C] Room codegen; confirm in the generated `ExchangeRateDao_Impl`). | [V]/[C] |
| F1b-5 | R23: only `NetworkPrivacyGate` decides `EXCHANGE_RATE_DOWNLOAD`. It allows the download only when `exchangeRateDownloadEnabled` is true, returns `FailClosed(PRIVACY_GATE_FAILURE)` on corrupted settings, and otherwise `Denied(EXCHANGE_RATE_DOWNLOAD_DISABLED)` (`NetworkPrivacyGate.kt:29-48`). The setting key `exchange_rate_download_enabled` defaults to false (`PrivacySettingsRepositoryImpl.kt:73, :223`). | [V]/[C] |
| F1b-6 | There is no periodic refresh (plan :382), so H-1 holds B2, C and loan S11 until 1b. The add-expense VM calls `refresh` only when the setting is On, a foreign currency is selected and no refresh is running. It maps failures by exception token (`AddExpenseViewModel.kt:1424-1467`; follow-up F-B2-TYPED-REFRESH). | [V] |
| F1b-7 | The evaluator reads one row per leg with the bound `localDate` at UTC midnight, direct pair first. It applies `MAX_ELIGIBLE_AGE_DAYS = 7`, inclusive (`RateEligibilityEvaluator.kt:117-153`). The preview's `Blocked` carries only the eligibility (`UserPresentRatePreview.kt:45`). | [V] |
| F1b-8 | `getLatestRateForPair` orders by `validDate DESC, lastUpdated DESC` (`ExchangeRateDao.kt:33`). `getRateAsOf` returns the latest row with `validDate ≤ bound` (`:62`). `deleteOldRates` keys on `lastUpdated` (`:59-60`) and has no production caller (F7). | [V] |
| F1b-9 | A full-row TLC update re-snapshots the base fields against the current home (`LEGAL_PATHS.md:109-111`). Analytics re-converts at read time with the instant bound and any age (F3). | [V] |
| F1b-10 | Home handling in the add-expense VM: the home flow re-applies the selection (`:289-291`). With no override, the selection follows the new home (`:1045-1061`). A new session discards a retained non-home draft and keeps a home draft (`:1654-1676`, R-1(b)/(c)). That kept home draft is F-B2-HOME-CHANGE. | [V] `AddExpenseViewModel.kt` |

### 1b.1 Endpoint (decides item 1)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-1 | History is fetched from exactly one constant, `ECB_HISTORY_90D_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml"`. Same scheme and host as the daily file. HTTPS `GET`, no query, no body, no cookies, no app-specific header (the platform default user agent, as for the daily file). Redirects are **not** followed (`instanceFollowRedirects = false`; any 3xx = `HTTP_ERROR`). Connect and read timeouts stay 10 s. The whole attempt (connect, read, parse, store) has a 30 s deadline. | `CurrencyRatesRepositoryImpl` |
| INV-1b-2 | If the endpoint can't be used (offline, timeout, non-2xx, wrong format), the outcome is exactly one bounded `Failed(reason)` (1b.7) and the table is unchanged. There is no fallback: no other host, no full-history file (`eurofxref-hist.xml`, several MB), no daily file in its place, no partial store. | `CurrencyRatesRepositoryImpl` |

**Verification gate VG-1b-1 (at implementation; not an owner decision).** The URL comes from third-party docs (brief :39, :63). Before any code, 1b S0 must confirm on ECB's own page that the file exists at this path on this host. It must also confirm the body format that F1b-2 assumes: the `eurofxref` namespace, an outer `Cube` with one `Cube time=` per publication day (newest first), and rate children `Cube currency= rate=`. Record the actual size and day count (they size INV-1b-9). If the path, host or format differs: STOP and return to this section. Don't pick another host or file.

### 1b.2 Trigger and remedy (decides item 2)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-3 | The history download is **user-started only**: one tap on the history action of the blocked card in add-expense (B2) or receipt review (C). It never runs at preview, at save, after a refusal, at app start, from a worker or as an automatic retry, and it never enables the setting (extends INV-20). The action is offered only when the R23 setting state is On and `RateRemedyPolicy` returns `DOWNLOAD_HISTORY` (INV-1b-5). When the setting is Off or Unknown, the card keeps the existing open-setting action (B2a). | entry VMs (offer and tap); repository (gate) |
| INV-1b-4 | Before opening any connection, the repository checks the composite `PrivacyGate` for the **existing** `PrivacyCapability.EXCHANGE_RATE_DOWNLOAD`. `Denied` → `DisabledByPrivacy`. Any other blocking decision → `PrivacyCheckFailed(code)` with a bounded `PrivacyGateReasonCodes` value, mapped as `refresh` does (`:50-62`). No new capability, setting or permission. | `CurrencyRatesRepositoryImpl` + `NetworkPrivacyGate` |
| INV-1b-5 | `RateRemedyPolicy.remedy(eligibility, localDate, latestStoredPairDate, today, historyDownloadedThisSession)` is pure. It is the only place that picks the remedy for a blocked user-present entry when the setting is On: (1) `Home`, `Eligible` or `Unsupported` → `NONE`. (2) `Stale`/`None` and (`latestStoredPairDate == null` or `latestStoredPairDate ≤ localDate`) → `REFRESH_LATEST`, because the daily file can still add a date that helps. (3) `Stale`/`None`, `latestStoredPairDate > localDate`, `localDate ≥ today − 90 days`, and no successful history download in this entry session → `DOWNLOAD_HISTORY`. (4) Otherwise `NONE` with `NoRemedyReason` = `OUTSIDE_HISTORY_WINDOW` (localDate < today − 90) or `HISTORY_ALREADY_DOWNLOADED`. Inputs: `latestStoredPairDate` is the UTC calendar date of `getLatestRateForPair(currency, home).validDate` (direct pair, any date); the preview use case supplies it on `Blocked`. `today` is the VM's clock in `entryZone`. The evaluator stays clock-free (INV-3). | `RateRemedyPolicy` (new, `A/domain/currency`); the preview use case supplies the date |
| INV-1b-6 | Idempotent and single-flight. At most one history download runs per process (`Mutex.tryLock`). A second call while one runs returns `AlreadyInProgress` and opens no connection. Downloading the same file again writes the same keys with the same rate values (REPLACE); only `lastUpdated` changes. A concurrent daily refresh is harmless (same keys, same values). The VM disables the action while the download runs. After `Stored` it sets `historyDownloadedThisSession`, which is cleared with the session zone (`onSheetLeft`, `reset`). After a failure the user may retry with another tap. | repository (single-flight); VM (session flag) |

Notes:
- First use (empty table) offers `REFRESH_LATEST` first, so the user may need two steps. That's deliberate. The daily file is about 1.5 KB and covers entries dated on or after its publication. History is fetched only when the stored latest date proves the daily file can't help. This keeps the larger fetch, and its backdated-entry hint (1b.8), to the cases that need it.
- `HISTORY_WINDOW_DAYS = 90` (calendar days) only gates the offer; the stored rows decide eligibility. If the real file starts later than `today − 90`, the entry stays blocked after one download and the session flag stops further offers (AE-1b-6).
- A Save-origin blocked card (coordinator refusal) uses the same policy on the VM's local re-read (B2a F2), so the preview card and the Save card offer the same remedy.

### 1b.3 Parser contract (decides item 4)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-7 | `parseEcbHistoryRates(stream)` returns either `EcbHistoryRates(days: List<EcbRateDay(date: LocalDate, eurToCurrency: Map<String, Double>)>)`, newest first, or one bounded failure. Input structure: dated `Cube` elements in the ECB namespace, each with an ISO `time`. Rate children are read exactly as the daily parser reads them (F1b-2: an invalid child is skipped; EUR = 1.0 is added). Whole-payload failures, with nothing returned: no dated node → `PAYLOAD_EMPTY`. Any of the following → `PAYLOAD_MALFORMED`: XML that isn't well formed, a DOCTYPE, a blank or malformed `time`, the same date twice, a dated `Cube` inside another dated `Cube`, or a day with no valid rate child. | `CurrencyRatesRepositoryImpl` |
| INV-1b-8 | The history parser uses the **same** private `secureDocumentBuilderFactory()` as the daily parser: one hardened factory (F1b-2). No second factory, no relaxed feature, no entity resolver, no schema or network lookup during parsing. | `CurrencyRatesRepositoryImpl` |
| INV-1b-9 | Bounds. All fail closed, with nothing stored. (a) The decompressed body is read through a counting stream capped at `MAX_HISTORY_BYTES = 512 KiB`. A `Content-Length` above the cap fails before reading. Both → `PAYLOAD_TOO_LARGE`. (b) More than `MAX_HISTORY_DAYS = 70` dated nodes, a span (newest − oldest) over `MAX_HISTORY_SPAN_DAYS = 100` calendar days, or any date after the device's UTC date + 1 → `PAYLOAD_OUT_OF_BOUNDS`. (c) The row total is checked against `MAX_HISTORY_DAYS × c × (c − 1)`, where c = catalog size (28 today → 52,920); a larger total → `PAYLOAD_OUT_OF_BOUNDS`. The caps are constants pinned by tests and sized from VG-1b-1 (expected file: about 64 days and about 100 KB [U]). | `CurrencyRatesRepositoryImpl` |

Dates outside the file are not the parser's concern. It never invents, extends or deletes days (INV-1b-12).

### 1b.4 Storage (decides item 3)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-10 | Cross pairs are built per day by the same rule as the daily file. One shared private function, extracted from `refreshFromStream :96-110` and used by both paths, turns a day's `eurToCurrency` into every ordered pair among the catalog currencies listed **that day** (full `SupportedCurrency.catalog`, as today), with rate = `toEur / fromEur`, finite and > 0. **They are stored as cross pairs, not EUR legs only.** The §10 reason still applies: lookups are direct-first everywhere, so EUR-only rows next to older direct daily pairs would give false `Stale` results. The home check works as for the daily file, applied to the **newest** day of the payload: unknown home → `INVALID_HOME_CURRENCY`; home not listed that day → `HOME_QUOTE_UNAVAILABLE`; nothing is stored in either case. Older days that don't list the home are stored without home pairs (the evaluator then finds an older home row, or none). Zero rows in total → `PAYLOAD_EMPTY`. | `CurrencyRatesRepositoryImpl` |
| INV-1b-11 | All or nothing. All rows of one download go to the store in **one** call that runs in **one** transaction. A new `CurrencyConverter.storeRateHistory(days, source = "ecb")` builds every row: `validDate` = that day's `date.atStartOfDay(UTC)`, `lastUpdated` = one `now` for the whole download, `source = "ecb"`. It skips invalid rates as `storeRates` does and calls `ExchangeRateStore.insertOrUpdateAll` once. REPLACE on (from, to, validDate) overwrites an existing row with the same key (normally an identical daily ECB row) and nothing else. A write-barrier refusal (backup restore) or any store exception → `Failed(STORE_FAILED)`, with zero rows changed. This extends the existing rate-write path (same owner chain: converter → store → DAO); it isn't a new legal path. | `CurrencyConverter` (rows) + `ExchangeRateStore` (transaction) |
| INV-1b-12 | Rows whose `validDate` isn't a day in the payload are never touched: no delete, no update, no `lastUpdated` change. | `CurrencyConverter` / store |
| INV-1b-13 | After the transaction commits, `setLastRateUpdate(now)` is called once, because the 90-day file includes the latest publication ([U] confirm at VG-1b-1). The call is best effort: if it fails (other than by cancellation), the outcome stays `Stored`, because the rows are committed. It is never called when nothing was stored. | `CurrencyRatesRepositoryImpl` |

Storage size: about 25 listed catalog currencies give 600 rows per day. Over about 64 days that's **38-39k rows**, roughly 4-5 MB including the three indexes ([U] estimate). Later downloads only add new days (about 600 rows per business day), the same growth bound as daily refreshes.

### 1b.5 Retention and analytics (decides item 5)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-14 | INV-22 holds for 1b: no deletion or cleanup of `exchange_rates`. `deleteOldRates` and `cleanupOldRates` stay without a production caller, and 1b must not wire them. They key on `lastUpdated`, and a history download stamps old-dated rows with a new `lastUpdated`, so deleting by download time would keep old dates and drop newer ones. Any future retention must key on `validDate`, keep every row analytics may still need, and needs its own analytics decision. That is routed elsewhere, not part of 1b. | exchange-rate store (unchanged) |
| INV-1b-15 | 1b never writes an `expenses` row. Stored `baseAmount`, `baseCurrency` and `exchangeRateUsed` keep their values. Sentinel rows (`baseCurrency=""`) stay sentinel: no backfill (recovery is inc 5). Read-time conversions that use the rate table (analytics F3, the eligibility preview) pick up the new rows on the next read. That is intended (brief :39: consistent with analytics' as-of conversion) and needs no analytics code change. A user-started full-row edit of an old row re-snapshots through the existing TLC update (F1b-9) and may then use a history rate; that is existing behaviour. | TLC (unchanged); analytics (unchanged) |

### 1b.6 Eligibility after a download (decides item 6)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-16 | The evaluator and its rule don't change (INV-1..3; F1b-7). History only adds rows. Consequences: (a) a weekday with a publication → `Eligible(age 0)`; (b) a weekend or TARGET holiday → the latest publication **on or before** the local date (age 1-5, AE-9); (c) `Stale` only when the pair has a gap of more than 7 days (a currency missing from ECB for a while); (d) a local date before the oldest stored row → `None`, or `Stale`/`Eligible` from an older daily row by the normal rule. Direct pairs exist for every stored day, so via-EUR stays exceptional, and via-EUR still uses the older leg. Rows written by 1b have `source = "ecb"`, so the label stays "ECB rate of …". | `RateEligibilityEvaluator` (unchanged) |

### 1b.7 Outcomes and diagnostics (decides item 7)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-17 | `CurrencyRatesRepository.downloadRecentHistory(homeCurrency): CurrencyRateHistoryOutcome` returns a closed sealed type with six variants: `Stored(days: Int, rows: Int, earliestDate: LocalDate, latestDate: LocalDate)`, `DisabledByPrivacy`, `PrivacyCheckFailed(reasonCode)`, `AlreadyInProgress`, `Failed(reason: RateHistoryFailureReason)` and `Cancelled` (the outcome of an explicit user Cancel, INV-1b-21; the repository never returns it, because its caller's `CancellationException` must propagate). The reasons are `OFFLINE`, `TIMEOUT`, `HTTP_ERROR`, `PAYLOAD_TOO_LARGE`, `PAYLOAD_EMPTY`, `PAYLOAD_MALFORMED`, `PAYLOAD_OUT_OF_BOUNDS`, `INVALID_HOME_CURRENCY`, `HOME_QUOTE_UNAVAILABLE` and `STORE_FAILED`. Every non-cancellation failure maps to exactly one reason, and the method never throws one (unlike `refresh`, F1b-6). The caller's `CancellationException` propagates. The 30 s deadline uses `withTimeoutOrNull`, so only that inner deadline becomes `TIMEOUT`. | `CurrencyRatesRepositoryImpl` |
| INV-1b-18 | Each attempt emits one bounded diagnostic: event `RATE_HISTORY_DOWNLOAD`, outcome code = the variant or reason enum name. It goes through the existing controlled-diagnostic sink, or a constant-format `Timber` line as `refresh` uses at `:61`. Never logged, emitted as a diagnostic or written to events: the URL or host, HTTP status, headers, payload bytes, dates, rates, currency codes, row or day counts, the home currency, the entry's date or amount, exception messages. The dates in `Stored` are public ECB dates for the UI only and are never logged. | repository; VMs (never log the outcome's fields) |
| INV-1b-21 | **User Cancel** (D-1b-3). The entry VM offers Cancel only in `DOWNLOADING`. Cancel cancels the VM's download job; the `CancellationException` is what stops the attempt, and it propagates (INV-1b-17), never mapped to `Failed`. The store is all-or-nothing (INV-1b-11), so a cancel before the commit stores nothing, and a cancel during the commit leaves zero rows or all rows of that download, never a subset (a committed set is public and idempotent). `setLastRateUpdate` isn't called after a cancellation. When the job has ended by cancellation the VM sets `historyStatus = CANCELLED` (the `Cancelled` outcome), does not set `historyDownloadedThisSession`, and offers the history action again (retry = another tap). The repository emits the single INV-1b-18 diagnostic for the attempt with outcome code `CANCELLED`, then rethrows. Leaving the screen (sheet dismissed, VM cleared) cancels the same way but shows no status (1b.10). | entry VMs (Cancel action, `CANCELLED` status); repository (propagation and diagnostic only) |

**UX mapping (D-1b-5).** The outcome, the `Failed` reason and the `NoRemedyReason` map to the UI states `historyStatus` and `rateFixAction` by the table in `ux-flows.md` 1b.2 ("Outcome-to-state mapping"). That table is the only place the mapping is defined; this section doesn't repeat it. The domain supplies its inputs: the `Failed` reason, `Cancelled`, the `NoRemedyReason` (INV-1b-5) and `Stored.earliestDate`. For the rule "`HISTORY_ALREADY_DOWNLOADED` with the entry date before the earliest stored date", the entry VM keeps the `earliestDate` of this session's `Stored` outcome in memory beside `historyDownloadedThisSession` (extends INV-1b-6; cleared with it in `onSheetLeft` and `reset`; never persisted or logged). `AlreadyInProgress` has no D-1b-5 row; it is a backstop and is presented as `FAILED` (1b.15).

**Built diagnostics (C9/C11, S4 note).** The repository emits the INV-1b-18 line as a constant-format `Timber.i("RATE_HISTORY_DOWNLOAD %s", outcomeCode)` with the bounded enum name only. The two entry VMs (`AddExpenseViewModel`, `ReceiptScanViewModel`) each log an unexpected non-cancellation exception from the download with `LOG_RATE_HISTORY_FAILED` (`"RATE_HISTORY_FAILED"`): a constant-tag `Timber.w(tag)` line with no `Throwable`, no format arguments, and no pairs, dates, amounts or home currency. The exception is never attached or stringified; `CancellationException` is rethrown before that catch.

### 1b.8 Privacy (decides item 8)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-19 | The request carries no user data and doesn't depend on the entry. The URL is a constant. Nothing about the entry's date, currency or amount, the home currency or the device is sent. The file always covers the whole window, whatever the entry date. Two downloads for different entries make byte-identical requests. | `CurrencyRatesRepositoryImpl` |

- **Payload.** About 100 KB of public data, roughly 60× the daily file ([U] until VG-1b-1). It's stored in the existing table, and no export is added. If `exchange_rates` is backed up, the backup grows by the same rows [U].
- **Timing inference.** The ECB, or an on-path observer who sees only the host through TLS, can tell that this client asked for the 90-day file right after a tap. That hints that someone is entering a past-dated foreign expense. Decided mitigations: the constant whole-window request (INV-1b-19); user-started only (INV-1b-3); the daily file first, history only when the daily file can't help (INV-1b-5); one download per entry session (INV-1b-6); no retry, background fetch or prefetch. Accepted residual: low (public data, a host the app already contacts, no identifier). Rejected alternative: always fetching the 90-day file for every refresh. That's about 60× the traffic on every refresh to hide a low-value signal.
- Same R23 setting and default (Off). No new permission, capability, host or cloud AI.

### 1b.9 Home currency change during an entry (decides item 9, F-B2-HOME-CHANGE)

| ID | Invariant | Owner |
|---|---|---|
| INV-1b-20 | A home change never re-denominates an amount. The draft's **denomination** is the code its amount is in: the selected code, or for a home selection, the home at that time. Rules when the resolved home changes H1 → H2 (both known, H1 ≠ H2), whether the sheet is open or between sessions:<br>(a) Blank amount → the selection follows H2, as today.<br>(b) A foreign override X stays X, and the preview re-evaluates against H2. If X == H2, it becomes `Selected(H2, home)`: same code, amount kept.<br>(c) A home selection with a non-blank amount → the **whole draft is discarded** through the existing `reset()` path (as in R-1(b)), and one bounded note is shown. H1 is never pinned as a foreign override: that would be last-used memory, and H1 may not be selectable.<br>While SAVING, the rule waits. The request already carries the explicit code H1, and TLC's resolved home decides (§12). After `Created` the sheet closes; after a refusal or error, rule (c) applies. Installment mode follows (c) too. Receipt review keeps its explicit (proposed or picked) code. If its selection can follow home implicitly [U], it becomes `Unset` instead and the receipt's fields are kept (in the spirit of INV-17). | AddExpense VM; receipt-review VM for its screen |

### 1b.10 State machine: history download (entry VM)

`IDLE → DOWNLOADING → { IDLE + Stored (re-evaluate the region; set the session flag) | IDLE + failure status(reason) | IDLE + CANCELLED (user Cancel, INV-1b-21) | IDLE + setting Off (DisabledByPrivacy) | IDLE + setting Unknown (PrivacyCheckFailed) }`
- **Offered:** only in `IDLE` and only with remedy `DOWNLOAD_HISTORY` (INV-1b-5).
- **Illegal:** `DOWNLOADING → DOWNLOADING` (the action is disabled; the repository's `AlreadyInProgress` is the backstop); starting a download while the daily refresh is running or while SAVING (one rate action at a time); any automatic retry.
- **Retry:** another tap after a failure. Idempotent (INV-1b-6).
- **User Cancel** (D-1b-3, INV-1b-21): offered only in `DOWNLOADING`; `DOWNLOADING → IDLE + CANCELLED`. The VM cancels the download job; `CancellationException` propagates and is not mapped to a failure. A cancel before the commit stores nothing; a cancel during it leaves zero rows or all rows (INV-1b-11), never a subset. `historyDownloadedThisSession` stays unset and the action is offered again (retry = another tap). Cancel in any other state is illegal.
- **Cancellation by leaving** (sheet dismissed, VM cleared, no Cancel tap; no status is shown): the job is cancelled. `CancellationException` propagates and is never mapped to a status. If cancelled before the store call, nothing is stored. The store transaction commits whole or not at all (INV-1b-11), so cancellation can't leave a partial set of days.
- **Process death:** same as cancellation. A committed download stays. The outcome and the session flag are lost, and the next session re-evaluates from the table.
- **Scope:** the VM scope. No WorkManager, no app scope; by design the download never outlives the user's request.

### 1b.11 Owners (decides item 10)

| Rule | Owner |
|---|---|
| Endpoint, gate, single-flight, bounds, parser, cross pairs, outcomes, `lastRateUpdate` | `CurrencyRatesRepository.downloadRecentHistory` in `CurrencyRatesRepositoryImpl` (extends the existing rate repository) |
| Privacy decision | `NetworkPrivacyGate` (existing capability) |
| Row building and the atomic write | `CurrencyConverter.storeRateHistory` → `ExchangeRateStore.insertOrUpdateAll` (existing chain) |
| Remedy choice | `RateRemedyPolicy` (new, pure). `UserPresentRatePreview` supplies `latestStoredPairDate` on `Blocked` (additive field). |
| Eligibility | `RateEligibilityEvaluator` (unchanged) |
| Offer, tap, session flag, home-change rule | AddExpense VM; ReceiptScan VM (C) |
| Retention | none (INV-22, INV-1b-14) |

**No new legal path.** The history download is a second method on the same repository, behind the same gate, writing through the same converter → store chain. No expense write path changes.

### 1b.12 Persistence impact. Schema verdict: **NONE**

- Same table, columns and unique index. No version bump, migration, backfill or destructive change. No new DataStore key: the session flag lives in memory.
- Growth: about 38-39k rows on the first download, then about 600 per new business day (1b.4). No row is deleted.

### 1b.13 Architecture-doc change (applied in S4 to `docs/architecture/LEGAL_PATHS.md`, EXCHANGE-RATE history download block)

`LEGAL_PATHS.md` → EXCHANGE-RATE download (`:586-593`), add:
```
  → AddExpenseViewModel / ReceiptScanViewModel history action (user tap, setting On,
    RateRemedyPolicy = DOWNLOAD_HISTORY)
    → CurrencyRatesRepositoryImpl.downloadRecentHistory (same PrivacyGate check BEFORE any HTTP;
      constant ECB 90-day URL on www.ecb.europa.eu; no redirects; bounded, XXE-safe parse)
    → CurrencyConverter.storeRateHistory → ExchangeRateStore.insertOrUpdateAll (ONE transaction)
  → Returns CurrencyRateHistoryOutcome (Stored / DisabledByPrivacy / PrivacyCheckFailed /
    AlreadyInProgress / Failed(reason) / Cancelled; the VM sets Cancelled on a user Cancel,
    the repository rethrows CancellationException and never returns it)
FORBIDDEN:
  ❌ Fetching rate history at preview, save, refusal, start-up or from a worker
  ❌ Any rate host or file other than the two ECB constants
  ❌ Storing part of a history payload
  ❌ lastUpdated-keyed deletion of exchange_rates (deleteOldRates/cleanupOldRates) from production code
```

### 1b.14 Acceptance examples (synthetic)

Defaults as in §15: home EUR, zone `Europe/Athens`, year 2025, ECB fixtures. The setting is On unless stated. "Today" = Thu 16 Oct 2025 17:30 Athens (after the 16:00 CET publication), so `today − 90` = Fri 18 Jul.
**Fixture H90:** a synthetic `eurofxref-hist-90d.xml` with 65 dated days, Fri 18 Jul to Thu 16 Oct 2025, newest first. Each day lists 25 catalog currencies, so each day yields 600 pairs and the file yields 39,000 rows. USD = 1.1000 on Fri 3 Oct, 1.0800 on Wed 15 Oct and 1.0850 on Thu 16 Oct; GBP = 0.8700 every day; the other values are arbitrary.

- **AE-1b-1 Happy path (INV-1b-1, 5, 10, 11, 16).** Given the table holds only the 16 Oct daily rows. When a manual USD 20.00 entry is dated Sun 5 Oct 12:00. Then the preview is `Blocked(None)` and the latest stored USD→EUR date is 16 Oct > 5 Oct, so the remedy is `DOWNLOAD_HISTORY`. When the user taps it. Then the outcome is `Stored(days=65, rows=39000, earliestDate=18 Jul, latestDate=16 Oct)` and the region re-evaluates to `Eligible(rateDate=Fri 3 Oct, age 2, DIRECT, ECB)`, with preview "≈ €18.18 · ECB rate of 3 Oct". When the entry is saved. Then `exchangeRateUsed = 1/1.10` (unrounded) and `baseAmount = roundSnapshot(20 × (1/1.10), EUR) = 18.18`.
- **AE-1b-2 Endpoint unusable (INV-1b-2).** HTTP 404 → `Failed(HTTP_ERROR)`. A 301 to any location → `Failed(HTTP_ERROR)`, with no second connection. `IOException` at connect → `Failed(OFFLINE)`. In every case the row count and every row are unchanged, `setLastRateUpdate` isn't called, the session flag isn't set (the remedy is still `DOWNLOAD_HISTORY`), and a Save is `Refused(NO_RATE)`.
- **AE-1b-3 Setting gate (INV-1b-3, 4).** Setting Off → the card shows the open-setting action, never the history action. A direct `downloadRecentHistory` call → `DisabledByPrivacy` with zero connections. Corrupted settings → `PrivacyCheckFailed("PRIVACY_GATE_FAILURE")` with zero connections. Nothing turns the setting on.
- **AE-1b-4 Never automatic (INV-1b-3).** For the blocked AE-1b-1 entry: typing, changing the currency or date, re-opening the sheet and tapping Save (`Refused(NO_RATE)`) open zero connections. Only the history tap opens one, and exactly one.
- **AE-1b-5 Remedy choice (INV-1b-5).**
  - (a) Table {7 Oct}, USD entry Wed 15 Oct → `Stale(7 Oct, 8)`. The latest stored date 7 Oct ≤ 15 Oct, so the remedy is `REFRESH_LATEST`.
  - (b) After a daily refresh stores 16 Oct → still `Stale(7 Oct, 8)`; now 16 Oct > 15 Oct, so the remedy is `DOWNLOAD_HISTORY`.
  - (c) After H90 → `Eligible(15 Oct, age 0)` with rate 1/1.08.
  - (d) Empty table, entry 5 Oct → `None`, remedy `REFRESH_LATEST` (first use takes two steps).
  - (e) A forced BGN selection → `Unsupported`, remedy `NONE`.
- **AE-1b-6 Window and once per session (INV-1b-5, 6).**
  - (a) Table {16 Oct}, entry Tue 1 Jul → `None`. 1 Jul < 18 Jul, so `NONE(OUTSIDE_HISTORY_WINDOW)` and no offer.
  - (b) A synthetic shorter file starting Tue 22 Jul, entry Mon 21 Jul → offered (21 Jul ≥ 18 Jul). The outcome is `Stored(earliestDate=22 Jul, …)` and the entry is still `None`. The session flag is set, so `NONE(HISTORY_ALREADY_DOWNLOADED)` and there is no second offer in that session.
- **AE-1b-7 Idempotent, single-flight (INV-1b-6).**
  - (a) H90 downloaded in two sessions → the row count after the second equals the count after the first, every `rate` is equal, and only `lastUpdated` is newer.
  - (b) A second call while the first runs → `AlreadyInProgress`, with exactly one connection in total.
  - (c) The pre-existing 16 Oct daily rows are replaced by rows with equal rates (same keys, count unchanged).
- **AE-1b-8 Parser (INV-1b-7, 8).**
  - (a) A payload with `<!DOCTYPE … SYSTEM "http://…">` → `PAYLOAD_MALFORMED`, no external fetch attempted, nothing stored.
  - (b) Two `Cube time="2025-10-15"` → `PAYLOAD_MALFORMED`.
  - (c) `time="2025-13-01"` or `time=""` → `PAYLOAD_MALFORMED`.
  - (d) No dated node → `PAYLOAD_EMPTY`.
  - (e) The 3 Oct day with no valid rate child → `PAYLOAD_MALFORMED`, and the whole payload is rejected.
  - (f) One child `currency="USD" rate="abc"` on 3 Oct → only that child is skipped; the other 64 days and 3 Oct's other currencies are stored. A USD entry dated 3 Oct is then `Eligible(Thu 2 Oct, age 1)`.
- **AE-1b-9 Bounds (INV-1b-9).**
  - (a) A decompressed body of 524,289 bytes → `PAYLOAD_TOO_LARGE`, nothing stored.
  - (b) `Content-Length: 600000` → `PAYLOAD_TOO_LARGE` before any read.
  - (c) 71 dated nodes → `PAYLOAD_OUT_OF_BOUNDS`.
  - (d) Oldest Sun 1 Jun with newest 16 Oct (137 days) → `PAYLOAD_OUT_OF_BOUNDS`.
  - (e) A day dated Sat 18 Oct when the device's UTC date is 16 Oct → `PAYLOAD_OUT_OF_BOUNDS`.
- **AE-1b-10 All or nothing (INV-1b-11, 13).**
  - (a) A store fake that throws on row 30,000 → `Failed(STORE_FAILED)`. The table is identical to before (rows and `lastUpdated`) and `setLastRateUpdate` isn't called.
  - (b) An active write barrier → `Failed(STORE_FAILED)`, zero rows written.
  - (c) `setLastRateUpdate` throws after the commit → the outcome is still `Stored`, and all 39,000 rows are present.
- **AE-1b-11 Rows outside the payload untouched (INV-1b-12).** Given rows for Mon 2 Jun 2025 (an old daily refresh) and 16 Oct. After H90 the 2 Jun rows are byte-identical (same `lastUpdated`), the 16 Oct rows have the same rates and the new `lastUpdated`, and the total is 600 + 39,000.
- **AE-1b-12 No retention (INV-1b-14).** Across all AE-1b examples there are zero calls to `deleteOldRates`, `cleanupOldRates` or `deleteAllRates`. A static check finds no production caller of `deleteOldRates` or `cleanupOldRates`.

- **AE-1b-13 Stored conversions unchanged (INV-1b-15).** Before the download there are two rows. Row 1: a notification row USD 20.00 dated Fri 3 Oct 12:00 Athens, converted (unflagged, any age) with the 25 Sep rate 1.0950: `exchangeRateUsed = 1/1.095`, `baseAmount = 18.26`. Row 2: a sentinel row GBP 30.00 dated Wed 1 Oct with `baseCurrency=""`. After H90 both rows' stored fields are byte-equal. Analytics' read-time conversion of row 1 now uses the 3 Oct rate (≈ €18.18) and converts row 2 from the 1 Oct rate; that's expected read-time behaviour. Row 2 stays a sentinel row in storage.
- **AE-1b-14 Weekends and holidays (INV-1b-16).**
  - (a) With H90, an entry dated Sun 12 Oct → `Eligible(Fri 10 Oct, age 2)`.
  - (b) With today = Tue 20 May 2025 and a file covering 20 Feb to 20 May: Sat 10 May → `Eligible(Fri 9 May, age 1)`; Thu 1 May (TARGET closed) → `Eligible(Wed 30 Apr, age 1)`; Easter Monday 21 Apr → `Eligible(Thu 17 Apr, age 4)` (Good Friday 18 Apr is closed too).
- **AE-1b-15 Gap of more than 7 days inside the window (INV-1b-16).** An H90 variant where ISK is missing from 1 to 14 Oct but listed on Tue 30 Sep and Wed 15 Oct. An ISK entry dated Fri 10 Oct → `Stale(30 Sep, 10)`. The session flag is set, so the remedy is `NONE(HISTORY_ALREADY_DOWNLOADED)`. Save → `Refused(STALE_RATE, latestRateDate 30 Sep)`.
- **AE-1b-16 Non-EUR home (INV-1b-10, 16).** Home USD. H90 stores the direct GBP→USD pair for 3 Oct = 1.1000 / 0.8700 ≈ 1.264368. A GBP 30.00 entry dated 3 Oct → `Eligible(DIRECT, 3 Oct, age 0)` with `baseAmount = roundSnapshot(30 × 1.264368…, USD) = 37.93`. No via-EUR lookup happens.
- **AE-1b-17 Home quote (INV-1b-10).** Home BGN, today Thu 15 Jan 2026. A synthetic file covers 17 Oct 2025 to 15 Jan 2026; its days up to 31 Dec 2025 list BGN, its later days don't. Result: `Failed(HOME_QUOTE_UNAVAILABLE)` (judged on the newest day), and nothing is stored, including the days that list BGN. Home "XXX" (not in the catalog) → `Failed(INVALID_HOME_CURRENCY)`.
- **AE-1b-18 Logs (INV-1b-18).** Across AE-1b-1..17, captured `Timber` output and diagnostics contain only `RATE_HISTORY_DOWNLOAD` and an outcome code (for example `STORED`, `HTTP_ERROR`, `PAYLOAD_TOO_LARGE`). They contain no URL, host, status, header, payload fragment, date, rate, currency code, count, home currency, amount or exception message.
- **AE-1b-19 Request content (INV-1b-19).** The request for the 5 Oct entry (AE-1b-1) and the request for an entry dated Wed 20 Aug are identical: the same constant URL, `GET`, no query, no body, no cookie, no app-specific header, and no redirect followed.
- **AE-1b-20 Cancellation and timeout (INV-1b-17; 1b.10).**
  - (a) The sheet is dismissed while the body is being read → `CancellationException` propagates, no status is recorded, nothing is stored.
  - (b) It's dismissed after the store call started → the transaction commits whole or not at all; never a subset of days.
  - (c) A server that drips bytes for more than 30 s → `Failed(TIMEOUT)`, nothing stored, and the VM scope stays active.
- **AE-1b-21 Home change while the sheet is open (INV-1b-20).**
  - (a) Home EUR, amount "20.00", home selected; the home becomes USD (for example by a restore) → the draft is discarded (blank amount, USD selected) and one note is shown. No USD 20.00 can be saved from that draft.
  - (b) USD override, "20.00", home EUR → GBP → USD and "20.00" are kept, and the preview is against GBP.
  - (c) USD override, home → USD → `Selected(USD, home)`, "20.00" kept, no preview.
  - (d) Blank amount, home EUR → USD → USD is selected; nothing else changes.
- **AE-1b-22 Home change between sessions (INV-1b-20).** A home draft "12.50" (EUR) is retained, the user changes home to GBP in settings, and then reopens the sheet → blank amount, GBP selected. With the home unchanged, "12.50" EUR is kept (R-1(c), unchanged).
- **AE-1b-23 Home change during SAVING (INV-1b-20).** A home-selected EUR "20.00" save is in flight when the home becomes USD. The request still carries `currency=EUR`, and TLC resolves home USD, so this is a flagged foreign evaluation of EUR. If `Eligible` → the row is EUR 20.00 with `baseCurrency=USD` and the sheet closes. If refused → the refusal is mapped, then rule (c) discards the draft.
- **AE-1b-24 User Cancel (INV-1b-21; D-1b-3).** Setting On, the AE-1b-1 entry, a download in `DOWNLOADING`.
  - (a) Cancel is tapped while the body is being read → `CancellationException` propagates out of `downloadRecentHistory` (no `Failed`, no `Stored`). The table is unchanged (rows and `lastUpdated`), `setLastRateUpdate` isn't called, `historyStatus = CANCELLED`, `historyDownloadedThisSession` is false, and the remedy is still `DOWNLOAD_HISTORY`. One diagnostic with outcome code `CANCELLED` is emitted, with no URL, date or count.
  - (b) A store fake that suspends inside the transaction; Cancel is tapped → the table holds either zero rows or all 39,000 rows of H90, never a subset; `historyStatus = CANCELLED` either way.
  - (c) After (a), another tap on the history action starts a normal download → `Stored`, status back to `IDLE`, session flag set.
  - (d) In `IDLE` there is no Cancel action; no state changes.
  - (e) The sheet is dismissed instead (no Cancel tap) → the job is cancelled and no status is recorded (AE-1b-20(a)).
- **AE-1b-25 Outcome to UX state (D-1b-5; table in `ux-flows.md` 1b.2).**
  - (a) Table {16 Oct}, entry Tue 1 Jul → `NONE(OUTSIDE_HISTORY_WINDOW)` → `TOO_OLD` (`DONE_TOO_OLD` if reached after a download), no button.
  - (b) AE-1b-6(b): session `Stored.earliestDate` = 22 Jul, entry Mon 21 Jul, `HISTORY_ALREADY_DOWNLOADED` → 21 Jul is before 22 Jul → `DONE_TOO_OLD`.
  - (c) AE-1b-15: ISK entry Fri 10 Oct, `HISTORY_ALREADY_DOWNLOADED`, session earliest 18 Jul → 10 Oct is not before 18 Jul → `DONE_NO_RATE`.
  - (d) `Failed(OFFLINE)` → `FAILED_OFFLINE`. `Failed(INVALID_HOME_CURRENCY)` and `Failed(HOME_QUOTE_UNAVAILABLE)` → `FAILED_HOME_UNAVAILABLE`. Each of `TIMEOUT`, `HTTP_ERROR`, `PAYLOAD_TOO_LARGE`, `PAYLOAD_EMPTY`, `PAYLOAD_MALFORMED`, `PAYLOAD_OUT_OF_BOUNDS`, `STORE_FAILED` and `AlreadyInProgress` → `FAILED`. `Cancelled` → `CANCELLED`.

### 1b.15 Open decisions

**None for the owner.** Every item in the 1b brief was decided from the frame:
- endpoint: same host, constant, no fallback;
- trigger: user tap, setting On, remedy policy, daily first;
- storage: cross pairs, one transaction, bounded;
- parser bounds;
- retention: none, INV-22 kept;
- eligibility: unchanged rule;
- closed outcomes;
- privacy mitigations;
- F-B2-HOME-CHANGE: discard a home draft whose denomination would change;
- owners, with no new legal path and schema NONE.

Not decisions, but tracked:
- **VG-1b-1** (implementation STOP condition): confirm the URL, format, size and day count on ECB's own page.
- **[U]:** whether a Room list `@Insert` runs as one transaction (generated code); whether `exchange_rates` is in backups; whether the receipt-review selection can follow home implicitly; actual file size.
- **Routed, not 1b:** a `validDate`-keyed retention policy (needs an analytics decision); F-B2-TYPED-REFRESH (typed outcomes for the daily `refresh`); D-B1-1 three-decimal homes (unaffected: all 28 catalog currencies have 0 or 2 digits, §1.5).
- **Release:** H-1 stays the release gate for B2, C and loan S11. 1b also needs the es/el sign-off of its strings (ux-flows 1b) and the F-A11Y-LATCH-PURE review at the 1b candidate.

Reconciliation notes (owner-approvals 94):
- **Routed, not 1b (D-1b-4):** F-1b-CURMGMT, a Currency-management "Rate history" row. INV-1b-3 stays as written; the follow-up needs its own owner entry and an INV-1b-3 extension.
- **Derived, flagged for the combined guardian:** (1) `AlreadyInProgress` has no D-1b-5 row; ux-flows 1b.2 presents it as `FAILED`. (2) INV-1b-20(c) says "one bounded note" for both the open-sheet and the between-sessions discard, so the UX shows the note `mec_home_changed_cleared` in both cases (ux-flows 1b.4, 1b.9).

Count: **21** invariants (INV-1b-1..21; INV-1b-21 is the user Cancel row in 1b.7), **25** examples (AE-1b-1..25), 10 facts. Schema impact: **none**.
