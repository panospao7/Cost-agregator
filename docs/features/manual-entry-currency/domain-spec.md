# Domain spec: manual-entry-currency — increment 1 (+ frame for 1b)

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
single snapshot rounding rule, the no-rate `Timber.w` at TLC.

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
| F14 | The digits source already used by the formatter and the allocator is `java.util.Currency.defaultFractionDigits`. NumberFormat rounds HALF_EVEN by default; the allocator uses HALF_EVEN. | [V] `CurrencyFormatter.kt:90-106`; `SourceCurrencyAllocation.kt:29-35` |
| F15 | Parser: a single comma with ≤ 2 digits after it is a decimal; comma groups of 3 are thousands; a dot-only string passes through, so `"1.500"` → 1.5. | [V] `AmountUtils.kt:80-145` |
| F16 | The date picker rebuilds the instant with `ZoneId.systemDefault()` and keeps the time of day. | [V] `AddExpenseSheet.kt:841-855` |
| F17 | Loan: v1 plan currencies are {EUR, USD} (B-1). `declareWithNewExpense` creates the purchase through TLC `createExpenseDbOnlyV2` inside the plan transaction. The loan spec Q9 assumes TLC *refuses* creation when no rate exists (`AUTO_RECORD_DEFERRED_FX`). | [V] loan `domain-spec.md:73, 426, 570-577`; plan `:452-467, 630` |
| F18 | Fact: TLC does **not** refuse unflagged creates on a missing rate. It saves with defaults and calls `Timber.w`. This answers loan D-8 ("untraced"). | [V] TLC `:554-578` |

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
| fractionDigits | Int ≥ 0 | `java.util.Currency.getInstance(code).defaultFractionDigits`, read through **one** accessor in `CurrencyAmountPolicy` (INV-12) |
| maxAmount | exact decimal, major units | the static per-currency table in `CurrencyAmountPolicy` (INV-14, OD-1) |

`AmountCheck = Ok | NotPositive | PrecisionExceeded(allowedDigits) | AboveCap(cap)`. Checks run on the parsed value (`BigDecimal.valueOf(parsed).stripTrailingZeros().scale() ≤ fractionDigits`), never on a rounded value.

Digits for the catalog (ISO 4217, as returned by the platform; tests must pin all 28): 0 for **JPY, KRW, ISK**; 2 for all others, **including HUF** (ISO says 2, though in practice amounts are whole forints. Allowing 2 decimals never forces rounding).

### 1.6 `ConvertedPreview`
`(amountInHome: Double = parsedAmount × Eligible.rate, unrounded; home: CurrencyCode; rateDate: LocalDate; sourceLabel)`.
It is rendered by the **same** function the list uses for the converted amount (INV-15). It doesn't exist for `Home`. When not eligible, no amount is shown (never 0).

### 1.7 Existing persisted fields written by a flagged create (no change)
`Expense.amount` = the original amount, exact to the currency's digits. `currency` = the selection. `date` = the instant.
`baseCurrency` = resolved home (≠ ""). `exchangeRateUsed` = `Eligible.rate` (1.0 for Home).
`baseAmount` = `amount × rate`: unrounded until money-correctness lands, then its snapshot rounding. The rate date isn't persisted (no column; "Later").

---

## 2. Owners (one rule, one owner)

| Rule | Definition owner | Enforcement owner | Consumers (advisory, same function) |
|---|---|---|---|
| Rate eligibility | `RateEligibilityEvaluator` (new, `A/domain/currency`, read-only, pure over stored rates) | TLC create path under the flag | AddExpense VM preview, ReceiptScan VM preview, installment section |
| Selectable set and proposal resolution | `CurrencySelectionPolicy` (new, `A/domain/currency`) | Evaluator (`Unsupported`) → TLC | the three VMs |
| Digits and cap | `CurrencyAmountPolicy` (new, `A/domain/currency` or `domain/core/money`) | `TransactionValidator` (cap for all creates per OD-1; precision only when flagged) | VM inline validation |
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
| INV-7 | A successful flagged foreign create stores `exchangeRateUsed == Eligible.rate` and `baseAmount == amount × Eligible.rate` (or money-correctness's snapshot rounding of it). Eligibility and snapshot come from **one** rate read in the same create call. Flagged creates never use the instant-based `convertAsOf`. | TLC |
| INV-8 | Every row created by a flagged request has `baseCurrency ==` resolved home (≠ ""), `baseAmount > 0` and `exchangeRateUsed > 0`. Flagged saves never produce an unconverted row. If home resolution fails, the save is refused with `HOME_CURRENCY_UNRESOLVED`. | TLC |
| INV-9 | Requests with `userPresentRate == null` behave exactly as today: instant-based any-age conversion, defaults when there's no rate, no precision check, same events, same dedup. The one exception is the per-currency cap, if OD-1 = A. | TLC (regression suite) |
| INV-10 | `selectable(home) = {c ∈ activeCatalog : c ∉ NO_RATE_SOURCE} ∪ {home}`, independent of rate availability. Legacy rows in any catalog currency keep converting (`fromCode` uses the full catalog, unchanged). | `CurrencySelectionPolicy` |
| INV-11 | A proposed currency (OCR, an installment default, later nl-quick-add) that isn't selectable becomes `Unset`. It is never silently replaced. `Unset` can't be saved. | `CurrencySelectionPolicy` |
| INV-12 | `fractionDigits(c)` has one accessor, backed by `java.util.Currency.defaultFractionDigits`. Validator, preview and formatter all resolve digits from that platform source (the formatter and allocator already do, F14). | `CurrencyAmountPolicy` |
| INV-13 | An amount (and `myShareAmount` when present, [U]: assumed to be in the expense currency) with more fractional digits than `fractionDigits(c)` is **rejected, never rounded or truncated**. That means inline in the VM, and in `TransactionValidator` when flagged (`AMOUNT_PRECISION_EXCEEDED`). Nothing between the input text and TLC rounds the original amount (VM `:367-370` HALF_UP normalization removed). Switching currency re-validates the unchanged text. | `CurrencyAmountPolicy` (definition); `TransactionValidator` (flagged enforcement) |
| INV-14 | `cap(c)` is defined once in `CurrencyAmountPolicy` and doesn't depend on rate availability. `TransactionValidator` (`AMOUNT_EXCEEDS_MAX`) and VM inline validation consume it. The raw 1,000,000 guards at VM `:297` and `ManualExpenseRepository.kt:114` are removed (delegated). Non-catalog codes keep 1,000,000. | `CurrencyAmountPolicy` |
| INV-15 | The preview shows `parsedAmount × Eligible.rate`, rendered with the same rounding and format function as the list's converted amount. Interim: `CurrencyFormatter.formatMoney` on the unrounded product. After money-correctness: its snapshot rounding, then the formatter. The rate date shown is `Eligible.rateDate`, rendered as a `LocalDate` (never via an instant in the device zone, and never `lastUpdated`). No preview for `Home`; no amount when not eligible. | Evaluator output + shared formatter |
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

**Placement.** After validation (step 2, which includes the per-currency cap and, when flagged, the precision check) and provenance (2b), at the conversion-snapshot step (`:529-585`), **before dedup (step 4) and before any insert**. Reasons:
1. It needs the resolved home currency, which the snapshot step resolves.
2. The eligibility read *is* the snapshot read, so there's no time-of-check/time-of-use gap (INV-7).
3. It matches how existing validation failures are handled.

Flagged branch at the snapshot step:
- Home `Failed` → refuse with `HOME_CURRENCY_UNRESOLVED`. This is new fail-closed behaviour for manual saves when DataStore fails. Today those save with the `""` sentinel. That's acceptable because the user is present and can retry.
- `currency == home` → identity snapshot (as today).
- Otherwise call the evaluator. `Eligible` → snapshot from it. Anything else → refuse.

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

**Unflagged callers** are unchanged (INV-9, AE-21). Their no-rate rows keep today's defaults until money-correctness lands.

---

## 6. Decimals, rounding, cap (decides D4, D5)

- **Single digits source** = the platform ISO table via one accessor (INV-12). An explicit table on `SupportedCurrency` was rejected because the formatter and allocator would become a second source unless they were also changed (global display blast radius). Risk: JVM unit tests use JDK currency data, while the device uses ICU. Tests pin all 28 values, and one instrumented check is recommended ([U] for minSdk ICU data).
- **Rejection, not rounding** (INV-13). The parser is currency-agnostic and runs after R16. The precision check runs on the parsed value. Interaction with R16 and F15: for JPY, `"1,500"` → 1500 (accepted), `"1500,50"` → 1500.5 (rejected), `"1.500"` → 1.5 (rejected; the user retypes). The parser never guesses using the currency.
- **Rounding (D5).** Preview and snapshot use the same product `amount × rate` (same Double operands and order), so the unrounded values match bit for bit. Interim (money-correctness not landed): the stored `baseAmount` stays unrounded (today's behaviour, critique §3.4). The preview formats it with `CurrencyFormatter.formatMoney(home)` (NumberFormat HALF_EVEN at the home currency's digits), the same call the list uses, so preview text == list text (AE-38; the list's exact call site is [U] for the impact-mapper). After money-correctness: the preview calls its snapshot rounding function. This feature never rounds manual rows on its own.
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
- **D-8 answered:** TLC does not refuse unflagged manual USD creates; it saves with defaults (F18). S11 therefore can't produce its FX refusal without this spec's flag. That makes it a sequencing decision (OD-4).
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
| Missing or stale FX | `None`/`Stale` → refusal for flagged saves; unflagged unchanged. |
| Unavailable vs zero | No preview amount when unavailable; flagged rows never have `baseAmount` 0 (INV-8). |
| Rounding conservation | No splits here. The snapshot stays unrounded until money-correctness. Preview == list by the shared function (INV-15). |
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
- The flagged path never reaches the no-rate `Timber.w` (TLC `:573-577`, routed to money-correctness), because the refusal comes first.
- No network at preview or save. Refresh is user-started and gated on R23 (INV-20).
- Pre-existing, routed (RF-1): the `CREATE_ATTEMPTED` `reason` stores merchant + amount + currency for every create, including refused attempts. Whether `transaction_events` is exported or backed up is [U].

---

## 15. Acceptance examples (synthetic)

Defaults: home EUR. Zone `Europe/Athens` (UTC+3 until 26 Oct 2025, then UTC+2). Year 2025, to match the brief's weekdays.
ECB fixtures (EUR→X): USD 1.0800, GBP 0.8700, JPY 162.50, KRW 1500.00. Stored pair X→EUR = 1/value. Rows use `validDate = date.atStartOfDay(UTC)`, `source="ecb"`.
"Flagged" = `userPresentRate(Europe/Athens)`. "Refused(R)" = `ValidationFailed([code], RateRefusal(R, …))`, no expense row, empty post-commit batch.

- **AE-1 Normal (ex 1; INV-2, 7, 15).** Given a rate for 15 Oct. When a manual USD 20.00 entry is dated Wed 15 Oct 12:00. Then `Eligible(age 0, DIRECT, ECB)`; the preview reads "≈ €18.52 · ECB rate of 15 Oct". On save: `currency=USD`, `amount=20.0`, `exchangeRateUsed=1/1.08`, `baseAmount=20×(1/1.08)` (unrounded interim), `baseCurrency=EUR`.
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
- **AE-21 Unflagged unchanged (INV-9).** A notification USD 20.00 with an empty table → row with `baseAmount=0.0`, `baseCurrency="EUR"`, `exchangeRateUsed=0.0` (today's defaults). A CSV import USD dated 15 Oct with the only rate 40 days older → converted with that rate (any age), as today.
- **AE-22 Races.** Preview `Eligible(15 Oct)`, then the rate table is cleared before Save → Refused(NO_RATE). Preview `Eligible(14 Oct)`, then a refresh stores 15 Oct before Save → the snapshot uses the 15 Oct rate and the preview re-evaluates. With no rate write in between → stored `exchangeRateUsed` == the previewed rate.
- **AE-23 Receipt review eligible (ex 7a).** OCR proposes USD 42.00 and the user confirms. With the 15 Oct rate the preview shows "≈ €38.89 · ECB rate of 15 Oct". Save → expense created and linked (one transaction).
- **AE-24 Receipt review refused (ex 7b; INV-17).** Same receipt, empty rate table. Save → `RateNotEligibleException(NO_RATE)`. The receipt row, file, `matchStatus`, links and `ReceiptEvent`s are unchanged. The screen stays in review with merchant, amount, currency and date intact. The user changes the currency to EUR and saves → created and linked.
- **AE-25 Inactive proposal.** OCR proposes BGN (home EUR) → control `Unset(PROPOSAL_NOT_SELECTABLE)`, Save disabled. A request forced flagged with BGN → Refused(UNSUPPORTED_CURRENCY).
- **AE-26 Recurring (ex 8; INV-16).** USD is selected, so Repeat is hidden. If Repeat was on and the user switches to USD, the recurrence is cleared (UX presents this). A direct repository call with USD + monthly → bounded error, no expense, no rule. EUR + monthly → unchanged.
- **AE-27 BGN/RUB (ex 9).** The options list excludes BGN, RUB and HRK for a EUR-home user. A legacy 2024 BGN row still converts in analytics. A BGN-home user: a BGN expense saves as `Home`; an EUR expense dated 15 Oct 2026, with only 2025 pairs stored → `Stale`, Refused(STALE_RATE).
- **AE-28 Installment declare (INV-18).** In installment mode a JPY selection becomes `Unset`. USD 1,200.00 with an empty table → Refused(NO_RATE): no purchase, plan or entries. With the 15 Oct rate → purchase (converted), plan and entries commit together.
- **AE-29 Installment auto-record.** The background run creates a USD installment expense with no rate → created with today's defaults (unflagged). Never refused by this rule.
- **AE-30 Logs.** Across AE-1..AE-28, captured Timber output, diagnostics and the refusal events contain no amount, currency code, merchant, date, rate or zone.
- **AE-31 Duplicates (D10).** A manual USD 20.00 at "Cafe X" on 15 Oct, then a notification for EUR 18.52 at "Cafe X" on 15 Oct → both rows exist (currency differs, rule unchanged). A second manual USD 20.00 at "Cafe X" on 15 Oct (eligible) → existing duplicate handling.
- **AE-32 Process death.** Killed mid-entry → no row; reopening shows a fresh form with home selected. Killed after commit → one row; re-entering the same data → existing dedup.
- **AE-33 Cancellation.** The sheet is dismissed while SAVING → transaction rolled back, no row, `CancellationException` propagated, and no refusal or error state recorded.
- **AE-34 Zone change mid-session.** The session opens in Athens and the device zone becomes `Europe/London` before Save. Preview and request both use Athens, so the result equals the preview.
- **AE-35 Setter allow-list (INV-5).** A static check shows `userPresentRate` is set only in `ManualExpenseRepository`, `ReceiptScanViewModel` and `declareWithNewExpense`.
- **AE-36 Edit mode (INV-23).** Editing a USD row's amount → currency shown read-only; the existing update path applies (no age limit), and USD precision is applied.
- **AE-37 Future date.** For a receipt dated tomorrow (if existing validators allow it) with today's rate → `Eligible(age 1)`. For a date +30 days → `Stale`. A manual future date → rejected by the existing VM rule (unchanged).
- **AE-38 Preview text == list text (interim).** Given AE-1. The preview text "€18.52" equals the list's converted-amount text for the saved row, because both use `CurrencyFormatter.formatMoney(18.518518…, "EUR")`.

---

## 16. Open decisions (owner)

| ID | Decision | Recommended default | Alternative |
|---|---|---|---|
| OD-1 | Per-currency cap owner and scope. `TransactionValidator` caps every source today (F10). | **A:** `CurrencyAmountPolicy` table (section 6) used by `TransactionValidator` for all creates. Strong currencies are unchanged; weak-currency imports/notifications above 1,000,000 units are now accepted (a relaxed typo guard). | B: per-currency cap only for flagged requests and 1,000,000 for the rest. That means two cap rules, which violates single-owner. |
| OD-2 | How BGN/RUB become "inactive for new selection" | Feature-local `NO_RATE_SOURCE = {BGN, RUB}` used only by `CurrencySelectionPolicy`; `isActive` untouched | Flip `isActive=false` like HRK. That also removes them from the home-currency picker and changes `fromActiveCode` results for `BudgetAutopilotEngine.kt:410` and `BillReminderManager.kt:199`, which overlaps release item RQ3 |
| OD-3 | Divergence between the flagged snapshot (local-date bound, ≤ 7 days) and analytics read-time conversion (instant bound, any age, F3) | Accept in inc 1 and route alignment of analytics, unflagged TLC and Transactions to a calendar-date basis to `money-correctness-1.0.x` | Change analytics now (brief non-goal: "no analytics change") |
| OD-4 | Loan S11 sequencing: S11 needs a refusal that TLC doesn't have (F18) | Land this feature's domain core first (evaluator, selection/amount policies, request flag, TLC refusal, `ValidationFailed.rateRefusal`; no UI) as a prerequisite slice, so S11 uses it from day one. The later control merge is then UI-only | S11 builds its own interim pre-check (a duplicate rule, removed at convergence), or S11 waits for this feature |
| OD-5 | Is batch receipt review (`RECEIPT_BATCH_REVIEW`) "user-confirmed"? | Unflagged in inc 1 (treated like pending-review approval) | Flag it, which needs per-item refusal UX in batch |

---

## 17. Routed findings (not acted on) and remaining UNVERIFIED items

- **RF-1 (privacy, pre-existing):** TLC `:397` writes merchant + amount + currency into `CREATE_ATTEMPTED.reason` for every create. Route to money-correctness/privacy. Export/backup of `transaction_events` is [U].
- **RF-2 (date, pre-existing, [U]):** `AddExpenseSheet.kt:847-849` converts `datePickerState.selectedDateMillis` (Material3 = UTC midnight, [U]) with `ZoneId.systemDefault()`. West of UTC this may shift the picked date back one day, which affects every manual entry and this feature's local-date rule. A debugger should confirm, then route to the release/1.0.x track.
- **RF-3 (loan):** loan `domain-spec.md:575-577` `AUTO_RECORD_DEFERRED_FX` assumes TLC refuses on a missing rate. It doesn't (F18). The loan planner decides.
- **RF-4 (impact-mapper):** other raw 1,000,000 copies: `AppConfig.MAX_TRANSACTION_AMOUNT`, `AmountUtils.isValidAmount` default and doc, `AppParserRegistry.kt:69`. Inventory their consumers. They're out of scope unless OD-1 extends.
- **RF-5:** TLC `:573-577` `Timber.w` (already routed to money-correctness).
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
