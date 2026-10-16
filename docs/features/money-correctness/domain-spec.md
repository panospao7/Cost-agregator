# Domain spec: money-correctness — increments 1a (read side) and 1b (write side)

Author: domain-modeler (stage 3). Date 2026-10-16. Docs only (Release 1.0 frozen; implementation post-1.0). Tier **T3** (money).
Inputs: `brief.md` v2 (approved), `critique.md`, `workflows/active/money-correctness-handoff.md` §Human decisions (Gate 1 Q1–Q6, **binding**), `docs/features/manual-entry-currency/domain-spec.md` (MEC INV-7, INV-12, INV-15, AE-21, AE-29), `LEGAL_PATHS.md` (CREATE/UPDATE expense `:24-61`, Money/Currency `:534-552`).
Root `A` = `app/src/main/java/com/yourname/expensetracker`. Tags: **[V]** re-read this pass; **[C]** verified by critique; **[P]** earlier agent; **[U]** unverified.
Not re-verified here (taken from critique §1): `CurrencyConverter.kt:287` unrounded, `ExpenseExportMapper.kt:44-47`, `ExportOptionsViewModel.kt:691-692,756-757`, `CurrencyManagementScreen.kt:178,315`, `FinancialRescueSnapshot.kt:21-43`, TLC audit JSON `:2545-2546`.

---

## 0. Facts this spec depends on (verified this pass)

| # | Fact | Source |
|---|---|---|
| V1 | `Expense.amount: Double`, `currency: String` (column default `'EUR'`), base fields default `0.0 / 'EUR' / 0.0`. `isNotMine`, `isSharedExpense`, `myShareAmount: Double?`, `mySharePercentage: Int?`. | [V] `Expense.kt:57-58, 108-113, 146-148` |
| V2 | `effectiveAmount`: isNotMine → 0; shared+myShareAmount → myShareAmount; shared+pct → `amount * pct / 100.0`; else amount. `normalizedEffectiveAmount` falls back to raw `effectiveAmount` when `baseAmount <= 0` (the defect). Its myShareAmount branch needs `exchangeRateUsed > 0`, otherwise it falls through. | [V] `Expense.kt:163-185` |
| V3 | SQL mirror `EFFECTIVE_AMOUNT_E_SQL` has the same branch order as `effectiveAmount`. | [V] `ExpenseDao.kt:84-88` |
| V4 | `SortOrder.AMOUNT_*` CASE has a raw fallback `ELSE (EFFECTIVE_AMOUNT_E_SQL)` and no tiebreaker. The min/max WHERE uses raw `EFFECTIVE_AMOUNT_E_SQL`. The args order is WHERE args, then LIMIT, then OFFSET. COUNT omits ORDER BY. | [V] `ExpenseRepository.kt:49-73, 356-364, 380-397` |
| V5 | Live assistant callers pass `minAmount = null, maxAmount = null` (`ExecuteFinancialQueryUseCase.kt:78-79, 418-419`) and filter amounts in memory with `convertAsOf`. The preview count (`:122-129`) passes no amount bounds. No assistant caller passes `sortOrder`, so the assistant always gets `DATE_DESC`. The only raw min/max SQL caller is `assistantFilteredExpenses` (`:466-488`): it is `@Deprecated` and has **no call site** (grep finds only its definition). | [V] |
| V6 | Transactions: the initial tab is `MONTH` (`:81`). The ALL tab is SQL-paged (`PAGE_SIZE = 50`, `:55`; `loadPagedExpensesPage` `:813-831` passes min/max + sortOrder). The ALL tab skips `applyAmountConstraints` (`:241-245`). Time-range tabs sort in memory by `normalizedEffectiveAmount` (`:289-294`) and filter by it (`:889-903`). `_homeCurrency` is `stateIn(..., "EUR")` with an "EUR" placeholder (`:201-204`). `loadInitialAll` has a requestId guard; `loadMore` has none (`:779-803, 444-464`). | [V] |
| V7 | `CurrencySettingsRepository.homeCurrency(): Flow<String>` emits `DEFAULT_CURRENCY` when the key is unset and has no typed failure. `resolveHomeCurrency()` returns `Resolved / FirstRunDefault(EUR) / Failed(reason: String)` (5 s timeout; `reason` is raw exception text). `HomeCurrencyForMoneyMath` (`domain/core/money`) = `Available(code, firstRunDefault) / Unavailable(reason)`. | [V] `CurrencySettingsRepositoryImpl.kt:42-45, 104-118`; `HomeCurrencyForMoneyMath.kt` |
| V8 | TLC create snapshot: H `Failed` → `0.0/""/0.0` + `HOME_CURRENCY_UNAVAILABLE` diagnostic. Foreign + rate → `convertedAmount/H/rateUsed` (unrounded). Foreign + no rate → only `Timber.w(currency, amount, H, date)`; base fields **left as the caller built them** (defaults `0.0/'EUR'/0.0`). Same currency → identity `amount/H/1.0`. | [V] TLC `:529-585` |
| V9 | TLC full-row update: conversion is precomputed outside the transaction (`:999-1024`). Inside: converted / identity (`baseCurrency = updatedExpense.currency`) / else sentinel `0.0/""/0.0`. H `Failed` emits a diagnostic; no-rate emits nothing. | [V] TLC `:981-1024, 1099-1126` |
| V10 | Controlled-diagnostic sink: `diagnosticEventWriter.emit(DiagnosticEvent(pipeline=TRANSACTION, stage=operation, outcome=FAILED_FINAL, severity=WARNING, reasonCode, entityType, entityId, SafeEventMetadata{operation, reason}))`, best-effort, rethrows `CancellationException`. `DiagnosticReasonCode.MISSING_RATE` exists and has **no emitter** today. | [V] TLC `:295-324`; `DiagnosticReasonCode.kt:17` |
| V11 | Create validation: `amount` must be finite and `> 0` (`AMOUNT_NOT_POSITIVE_FINITE`), and currency must match `^[A-Z]{3}$`. The same `validateFields` serves the final state on update. `TransactionType` = PURCHASE, WITHDRAWAL, TRANSFER, DEPOSIT, UNKNOWN; `TransferDirection` = INCOMING, OUTGOING. There is no refund type. | [V] `TransactionValidator.kt:17, 33-46, 79-123, 203`; `Expense.kt:245-256` |
| V12 | All `Expense(...)` constructions in main source pass `currency` explicitly (TLC `:474-476`, ReviewQueueRepository `:186-188, :574-576`, NotificationProcessingPipeline `:1299-1301`, ExpenseRepository `:439` stub). `CreateExpenseRequest.currency: String` has no default. | [V] |
| V13 | Migration v94 added base columns **without backfill**: `0.0 / 'EUR' / 0.0`. | [V] `AppDatabase.kt:6104-6107` |
| V14 | Rescue: `FinancialRescueCoordinator(context, timeProvider)` is built by hand in `RescueActivity.kt:47` and runs on a raw `Thread`. It imports through `SupportSQLiteDatabase` `INSERT OR REPLACE` into a fresh DB and writes `baseAmount=amount, baseCurrency=currency, exchangeRateUsed=1.0` (`:418-420`). It has **no access to H**. `RescueConfig.ENABLE_FINANCIAL_RESCUE = false`. | [V] `FinancialRescueCoordinator.kt:25-28, 319-426`; `RescueConfig.kt:12` |
| V15 | Home switch: `setHomeCurrency(code)` validates through `SupportedCurrency.fromActiveCode`, writes DataStore, and invalidates the classifier snapshot. No confirmation exists. Sole writer: `CurrencySettingsRepositoryImpl.setHomeCurrency`. | [V] `CurrencyManagementViewModel.kt:165-189`; `CurrencySettingsRepositoryImpl.kt:56-65` |
| V16 | `SourceCurrencyAllocation` rounds with `BigDecimal.valueOf(double).setScale(digits, HALF_EVEN)`. Digits come from `java.util.Currency.defaultFractionDigits`, and a value `< 0` raises a bounded failure. | [V] `SourceCurrencyAllocation.kt:29-35` |
| V17 | The mixed-currency date header sums `normalizedEffectiveAmount` (unsigned) only when every item has `baseAmount > 0` and all share one `baseCurrency`. A same-currency group sums `signedEffectiveAmount()`. | [V] `TransactionsScreen.kt:523-541` |
| V18 | Base fields are written only by TLC (`:545, 568, 581, 1106, 1112, 1122`) and by Rescue raw SQL. Nobody else assigns `baseAmount`. | [V] grep `baseAmount\s*=` |
| V19 | `CsvExpenseImporter` reads no base columns. When a CSV row has no currency it **sets currency to H, or to "EUR" when H fails** (`:238-239`). | [V] |

---

## 1. Entities and value objects

### 1.1 `Expense` (unchanged schema)
Relevant fields: `amount: Double` (major units of `currency`; > 0 for every validated write, V11), `currency: String` (ISO 4217, uppercase for validated writes), `isNotMine`, `isSharedExpense`, `myShareAmount: Double?` (major units of `currency` [U] — same assumption as MEC INV-13), `mySharePercentage: Int?` (0–100 [U]), `date: Long` (epoch ms, UTC instant), `id: Long`.
Snapshot triple `(baseAmount: Double, baseCurrency: String, exchangeRateUsed: Double)`: `baseAmount` is in major units of `baseCurrency`. `exchangeRateUsed` is the multiplier from `currency` to `baseCurrency`, stored unrounded.

### 1.2 Snapshot classification (pure function of the triple, the row currency and H)
| Class | Definition | Produced by |
|---|---|---|
| **Valid-for-H** | `baseAmount > 0 AND baseCurrency == H AND exchangeRateUsed > 0` (exact, case-sensitive string equality) | TLC converted or identity branch while H was the same code; Rescue (today, V14) when the row currency equals H |
| **Unconverted sentinel** | `baseAmount == 0.0 AND baseCurrency == "" AND exchangeRateUsed == 0.0` | TLC H-Failed (create, update), TLC update no-rate; after 1b also TLC create no-rate and Rescue (M5) |
| **Legacy default** | `0.0 / 'EUR' / 0.0` | migration v94 (V13); TLC create no-rate **before 1b** (V8) |
| **Other-home snapshot** | `baseAmount > 0 AND baseCurrency != H` | TLC under a previous H; Rescue's made-up foreign identity (V14) |

**Unconverted sentinel definition (normative).** The only sentinel value is `0.0 / "" / 0.0`. After 1b no writer may produce `baseAmount <= 0` with a non-empty `baseCurrency`. The one exception is pre-existing data (Legacy default), which is never rewritten.

`exchangeRateUsed > 0` in Valid-for-H is a deliberate tightening of brief rule 2. No legal writer produces `baseAmount > 0` with a rate `<= 0`: identity uses 1.0, a conversion uses `rateUsed > 0` (`CurrencyConverter.isValidRate`, `:655` [U] that every path applies it), and Rescue uses 1.0. Requiring it removes today's fall-through in which a shared row with `myShareAmount` and rate 0 compares at the full `baseAmount` (V2).

### 1.3 `ComparableValue` (new, read side, domain)
```
sealed ComparableValue
  Value(amountInH: Double)         // finite; may be 0.0 (not-mine, 0 % share); negative only for legacy negative amounts (§4.9)
  None(reason: NoComparableValueReason)
enum NoComparableValueReason       // bounded; tests/diagnostics only, never shown with row content
  NO_SNAPSHOT                      // foreign row, baseAmount <= 0 (sentinel, legacy default, no-rate)
  SNAPSHOT_OTHER_CURRENCY          // foreign row, baseAmount > 0, baseCurrency != H (old home, Rescue)
  SNAPSHOT_RATE_INVALID            // foreign row, baseAmount > 0, baseCurrency == H, exchangeRateUsed <= 0
```
SQL represents `None` as `NULL` and has no reason. Parity compares Value against NULL only.
Kotlin normalises `-0.0` to `0.0` in `Value` (SQLite treats them as equal; Kotlin `compareTo` doesn't).

### 1.4 Read-time home-currency state (new, read side)
```
ReadHomeCurrencyState
  Loading                                        // before first resolution; ≤ 5 s
  Available(code: CurrencyCode, firstRunDefault: Boolean)   // Resolved or FirstRunDefault(EUR), same as TLC (V8)
  Unavailable(reason: ReadHomeFailure)
enum ReadHomeFailure { READ_ERROR, TIMEOUT, INVALID_CODE }   // bounded; maps HomeCurrencyResolution.Failed.reason (raw text, V7) → never surfaced
```
`AmountOrderingState` (UI-facing, bounded) = `AVAILABLE | HOME_CURRENCY_LOADING | HOME_CURRENCY_UNAVAILABLE`.
Source: a typed observable of `HomeCurrencyResolution` (or `Loading + HomeCurrencyForMoneyMath`). The `"EUR"` `stateIn` placeholder (V6) and the untyped `homeCurrency()` flow (V7) must never feed the CV. Adding a typed observe method to `CurrencySettingsRepository` is an interface extension, not a new legal path. The impact-mapper places it.

### 1.5 Amount-semantics selector for the shared SQL builder (new, closed)
```
sealed AmountSemantics
  RawEffective                         // assistant: today's EFFECTIVE_AMOUNT_E_SQL WHERE + SortOrder.sql ORDER BY, byte-identical
  HomeComparable(home: CurrencyCode)   // Transactions ALL tab: CV WHERE/ORDER BY/COUNT
```
This is a **required** parameter of `buildExpenseDynamicQueryParts`, with no default. A missing choice is a compile error; it can't silently change assistant answers.

### 1.6 Snapshot rounding helper (new, 1b, domain/core/money)
```
roundSnapshot(value: Double, home: CurrencyCode): SnapshotRounding
  Rounded(amount: Double)     // > 0, exactly representable at `digits` decimals (BigDecimal.valueOf(amount) has scale ≤ digits)
  Unavailable(reason)         // NON_FINITE_OR_NON_POSITIVE | DIGITS_UNAVAILABLE  (bounded)
```
Contract (INV-16):
1. If `value` is not finite or `value <= 0` → `Unavailable(NON_FINITE_OR_NON_POSITIVE)`.
2. Look up `digits = fractionDigits(home)` through the **single** digits accessor (OD-1). If `digits < 0` or the code is unknown → `Unavailable(DIGITS_UNAVAILABLE)`. This can't happen for `SupportedCurrency` homes, but the helper fails closed.
3. Compute `r = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_EVEN)`. That's the decimal string of the double (`Double.toString`), not its binary expansion.
4. If `r.signum() == 0`, set `r = 10^-digits` (one minor unit). A positive amount never stores 0, so `baseAmount > 0` keeps meaning "converted".
5. Return `Rounded(r.toDouble())`.
It is pure: no clock, no I/O, no logging.
Owner-approved deviation from `Money.kt:34` HALF_UP (Gate 1 Q3, 2026-10-16; AGENTS.md:193 satisfied). The scope is **the snapshot only**. `AddExpenseViewModel.kt:369` HALF_UP input rounding, splits, settlements and `Money` stay unchanged.

---

## 2. The comparable-value (CV) rule

### 2.1 Predicate (normative, first matching branch wins)
For row `e` and Available home `H`:
1. `e.isNotMine` → `Value(0.0)`. Zero is the same in every currency, so a not-mine row is always comparable, whatever its currency or snapshot.
2. `e.currency == H` → `Value(effectiveAmount(e))`. This is the ownership-adjusted raw amount (V2 branch order), and it ignores the snapshot.
3. `e` is Valid-for-H (§1.2) → `Value(s)`, where
   - shared and `myShareAmount != null` → `s = myShareAmount * exchangeRateUsed`
   - else shared and `mySharePercentage != null` → `s = baseAmount * mySharePercentage / 100.0` (evaluated as `(baseAmount * pct) / 100.0`)
   - else → `s = baseAmount`
4. Otherwise → `None(reason)` (§1.3). A row is **never** compared by its raw amount unless branch 2 holds.

**Sign conventions.** CV is a magnitude, as today:
- `transactionType` (DEPOSIT, TRANSFER IN/OUT, WITHDRAWAL) does not affect CV.
- Validated rows have `amount > 0` (V11), so validated CVs are ≥ 0.
- No refund type exists. Negative amounts can only come from pre-validator legacy data or restored DB files [U]. Branch 2 passes them through (negative CV). Branch 3 rejects them because `baseAmount > 0` fails, so a negative foreign row is `None`.
- Signed totals (date headers) are out of scope (RF-4).

**Shared-row rounding.** Branch-3 shared values are computed at read time, **unrounded**, in IEEE double, with the same operation order in SQL and Kotlin. They are not stored and not rounded by the 1b helper. So "display == stored == preview" holds only for unshared rows (brief).

### 2.2 Single owner, two mirrors
- **Rule owner:** a domain policy, the `ComparableHomeValue` object in `A/domain/core/money` (name proposed). It holds the Kotlin function `comparableValue(e, H): ComparableValue` and the reason enum. It is the only Kotlin implementation of branches 1–4. It also owns the only Kotlin implementation of the "ownership-adjusted snapshot value" (branch 3 without the `== H` check), which the date header consumes (§2.4).
- **SQL mirror:** constant template `COMPARABLE_HOME_VALUE_E_SQL` in `ExpenseDao.Companion`, next to `EFFECTIVE_AMOUNT_E_SQL` (data layer; SQL must not live in domain). Its KDoc points to the owner. It contains exactly two `?` placeholders, both bound to H.
- **Binding:** the parity test (INV-7) is part of the rule. Neither mirror changes without the shared fixture. `Expense.normalizedEffectiveAmount`'s raw fallback must not survive in any sort/filter/count path. Remove the property, or turn it into a delegate of the owner's snapshot-value function. The impact-mapper chooses.

### 2.3 Reference SQL shape (H bound, never interpolated)
```
CV :=
(CASE
   WHEN e.isNotMine = 1 THEN 0.0
   WHEN e.currency = ? THEN (<EFFECTIVE_AMOUNT_E_SQL>)
   WHEN e.baseAmount > 0 AND e.baseCurrency = ? AND e.exchangeRateUsed > 0 THEN (CASE
        WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount * e.exchangeRateUsed
        WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.baseAmount * e.mySharePercentage / 100.0
        ELSE e.baseAmount END)
   ELSE NULL
 END)
```
Usage in `HomeComparable(H)` mode:
- min filter `CV >= ?` → args `[H, H, min]`; max filter `CV <= ?` → `[H, H, max]`. NULL comparisons are false, so None rows drop out.
- AMOUNT_DESC: `ORDER BY (CV IS NULL) ASC, CV DESC, e.date DESC, e.id DESC` → `[H, H, H, H]`.
- AMOUNT_ASC: `ORDER BY (CV IS NULL) ASC, CV ASC, e.date DESC, e.id DESC` → `[H, H, H, H]`.
- DATE_DESC: `e.date DESC, e.id DESC`. DATE_ASC: `e.date ASC, e.id ASC` (decision D-4, §4.6).
- Arg order = textual `?` order: WHERE args (in clause order), then ORDER BY args, then LIMIT, then OFFSET.
- `NULLS LAST` must not be used (SQLite ≥ 3.30 only; device SQLite versions vary [U]).
- Equivalent: the implementation may compute CV once in a derived table and order by its alias, if results match and H stays bound. The impact-mapper checks the query plan.
- The `SortOrder` enum stays closed and unchanged. In `HomeComparable` mode the enum value selects one of the fixed templates above. `SortOrder.sql` is used only in `RawEffective` mode.
- H is passed as a validated `CurrencyCode` (`^[A-Z]{3}$`), bound as a String arg.

### 2.4 Readers switched to the owner (1a)
| Reader | Today | After 1a |
|---|---|---|
| ALL-tab SQL WHERE min/max | raw `EFFECTIVE_AMOUNT_E_SQL` (V4) | `CV >= ?` / `CV <= ?` (`HomeComparable`) |
| ALL-tab SQL ORDER BY (amount) | `SortOrder.AMOUNT_*.sql` with raw fallback, no tiebreak | §2.3 templates |
| ALL-tab excluded count | none | §4.3 COUNT |
| Time-range tabs, in-memory sort | `normalizedEffectiveAmount` (V6) | comparator over `comparableValue(e, H)` with §4.1 order |
| Time-range tabs, in-memory min/max | `normalizedEffectiveAmount` (V6) | `Value` within bounds; `None` excluded |
| Mixed-currency date header | `normalizedEffectiveAmount` behind `baseAmount > 0` + common-base guards (V17) | owner's snapshot-value function. Behaviour unchanged except that a row with `exchangeRateUsed <= 0` yields no header total (unreachable from legal writers). Signing unchanged (RF-4) |
| Assistant (`getAssistantExpensesFiltered`, `getAssistantExpenseCountFiltered`) | raw | **unchanged** (`RawEffective`, INV-11) |

---

## 3. Invariants

| ID | Invariant (testable) | Single owner |
|---|---|---|
| INV-1 | For any row and Available H, `comparableValue` follows §2.1 branches 1–4 in order. Branch 2 ignores the snapshot. Branch 3 requires Valid-for-H. Every other row is `None`. | `ComparableHomeValue` (domain) |
| INV-2 | No row is ever compared, filtered or counted by its raw amount unless `isNotMine` or `currency == H`. A raw fallback such as `ELSE effectiveAmount` or `baseAmount <= 0 → effectiveAmount` is forbidden in every Transactions amount path. | `ComparableHomeValue` |
| INV-3 | `isNotMine` rows have `Value(0.0)`. They are comparable, sort as 0, pass `min <= 0`, and are never counted as "without a home value". | `ComparableHomeValue` |
| INV-4 | Shared branch-3 values are `myShareAmount × exchangeRateUsed`, or `baseAmount × pct / 100.0`, computed unrounded at read time with identical operation order in SQL and Kotlin. Nothing stores them. | `ComparableHomeValue` + SQL mirror |
| INV-5 | Amount ordering is total and deterministic in both directions: `None` rows last; then CV in the chosen direction; then `date DESC`; then `id DESC`. Date ordering in Transactions adds the `id` tiebreak in the same direction as the date. | `ComparableHomeValue` (Kotlin comparator) + SQL mirror |
| INV-6 | Min/max bounds are inclusive and apply to `Value` only. A `None` row never satisfies an amount bound. | same |
| INV-7 | **Parity.** For the same row set and H, the ALL tab (SQL) and time-range tabs (Kotlin) produce identical CV membership (Value vs None/NULL), identical filter membership including exact-boundary rows, and identical id order for AMOUNT_ASC, AMOUNT_DESC, DATE_ASC and DATE_DESC. A shared fixture test pins this and must change with either mirror. | `ComparableHomeValue` (owner of the fixture) |
| INV-8 | The "without a `<home>` value" count = number of rows that match every **non-amount** filter of the current view (tab time range, date range, search, type, category, merchant, ownership) and have `None` CV under the current H. The ALL tab computes it with a SQL `COUNT(*)` (same WHERE builder, plus `CV IS NULL`, without min/max). Time-range tabs compute it in memory over the full period list. It is computed **only** when H is Available **and** (amount sort is selected **or** a min/max bound is set). It is a non-negative integer; nothing else (no merchants, amounts or currencies) is exposed. | Transactions VM (count consumer); rule from `ComparableHomeValue` |
| INV-9 | Amount sort, min/max and the count run only with `ReadHomeCurrencyState.Available`. In `Loading` or `Unavailable`: effective order = DATE_DESC (`date DESC, id DESC`); min/max are **not applied** (they stay stored and re-apply when H becomes Available); no count; `AmountOrderingState` reports the bounded state. No query or comparator is ever evaluated against a placeholder or guessed H. | Transactions VM |
| INV-10 | A change of the Available H code, the sort, any filter, search or tab resets ALL-tab paging to offset 0. Every page or count request carries the query key `(H, sort, filters, search)` it was built with. A result whose key differs from the current key is discarded (this extends the `loadInitialAll` requestId guard to `loadMore` and to the count). Concatenated pages contain each matching id exactly once, in INV-5 order. | Transactions VM |
| INV-11 | **Assistant isolation.** `getAssistantExpensesFiltered` and `getAssistantExpenseCountFiltered` always use `AmountSemantics.RawEffective`. Their SQL text and args (WHERE, ORDER BY `e.date DESC` with no tiebreak, COUNT) are byte-identical to before 1a for every input. 1a changes no assistant behaviour. Alignment is routed to SRH-7 / ai-fixes. | `ExpenseRepository` (builder); test-pinned |
| INV-12 | **Home-switch warning.** Any request to set H to a code different from the current Available H (or any request while H is Loading/Unavailable) shows the warning **before** any DataStore write, from both entry points. Cancel, dismiss, back, or losing the dialog through process death → no write, no classifier invalidation, H unchanged. Confirm → exactly one `setHomeCurrency` write followed by today's invalidation. Same code as the current Available H → no warning, no write. An unsupported code keeps today's `UNSUPPORTED_HOME_CURRENCY` with no warning. | `CurrencyManagementViewModel` |
| INV-13 | TLC is the only writer of base fields for live data (create `:529-585`, full-row update `:1099-1126`, V18). Rescue is the single pre-existing exception (INV-20). No other layer assigns `baseAmount/baseCurrency/exchangeRateUsed`. | TLC |
| INV-14 | (1b) Every TLC snapshot outcome writes all three fields explicitly. It never relies on entity defaults. Outcomes: **Converted** `round(convertedAmount)/H/rateUsed`; **Identity** (`currency == H`) `amount/H/1.0`; **Home Failed** `0.0/""/0.0`; **No rate** (create and update) `0.0/""/0.0`; **Rounding unavailable** `0.0/""/0.0`. | TLC |
| INV-15 | (1b) The TLC no-rate outcome emits exactly one `DiagnosticReasonCode.MISSING_RATE` per snapshot attempt (create: `stage="createExpense"`; update: `stage="updateExpense"`, `entityId`). It goes through the existing controlled sink (V10) with metadata `{operation, reason}` only. `Rounding unavailable` emits `MISSING_RATE` with metadata reason `SNAPSHOT_ROUNDING_UNAVAILABLE`, or a new bounded code if the impact-mapper prefers one. The `Timber.w(currency, amount, H, date)` at `:573-577` is removed. No log or diagnostic in this feature contains an amount, currency code, merchant, date, rate or exception text. | TLC |
| INV-16 | (1b) Snapshot rounding follows the §1.6 contract: HALF_EVEN, `BigDecimal.valueOf`, H minor units from the single digits accessor, and a positive value never stores 0 (one minor unit minimum). It has one implementation, and no other code rounds snapshots. | rounding helper (`domain/core/money`) |
| INV-17 | (1b) TLC applies the helper exactly in the Converted branch of create and update. Identity stores `amount` unchanged, bit for bit (no rounding). `exchangeRateUsed` is stored unrounded. Existing rows are never rewritten by 1a or 1b. | TLC |
| INV-18 | (1b + MEC) The MEC user-present preview (MEC INV-15) renders `formatter(roundSnapshot(parsedAmount × Eligible.rate, H))`. The flagged snapshot (MEC INV-7) stores `roundSnapshot(amount × Eligible.rate, H)`. For unshared rows, preview text == list text == stored value. | rounding helper (rule); MEC preview use case (consumer) |
| INV-19 | Display is unchanged: rows keep showing their original amount and currency whether or not they have a CV. | — (no change) |
| INV-20 | (1a, M5) Rescue writes only Identity `amount/H/1.0`, for rows with `currency == H` when a resolved H is **supplied**, or Sentinel `0.0/""/0.0`, for every other row and whenever H is absent or Failed. It never writes a foreign snapshot and never converts. | `FinancialRescueCoordinator` |
| INV-21 | (1b) Export passes values through. A no-rate row exports `homeCurrency=""`, `baseAmount=0.0`, `baseCurrency=""` (CSV and JSON). Converted and identity rows are unchanged. Importers never read base columns (V19). | `ExpenseExportMapper` (unchanged code; meaning changes) |
| INV-22 | The warning text, the count and `AmountOrderingState` contain no transaction content. The warning never says amounts were converted. | UX copy + VMs |

Count: **22**.

---

## 4. Behaviour details

### 4.1 Ordering semantics
- Direction applies only to the CV block. The None block keeps the same internal order (`date DESC, id DESC`) and sits last in **both** directions (Q1).
- Ties inside the CV block (equal doubles) break by `date DESC, id DESC` in both directions. So AMOUNT_ASC is not the reverse of AMOUNT_DESC for tied rows. That's intended and pinned.
- Kotlin comparator: `compareBy<Row> { cv == null }` (false first), then CV asc or desc on normalised doubles (`-0.0 → 0.0`), then `date` desc, then `id` desc. It must be a total order with no reliance on stable-sort input order.

### 4.2 Filters
`min`/`max` are inclusive (`>=`, `<=`), as today. Both may be set. `min > max` gives an empty CV set (today's behaviour; UX may validate). In `RawEffective` mode the semantics are unchanged.

### 4.3 Count (Q1, decided scope)
- **When:** amount sort selected **or** any amount bound set, **and** H Available (INV-8, INV-9). With date sort and no bound → not computed (no amount semantics engaged).
- **What:** a single integer. For sort only, it tells how many rows sit unranked at the bottom. With a bound, it tells how many rows the bound can't evaluate (hidden).
- **ALL tab:** `SELECT COUNT(*) FROM expenses e WHERE <non-amount clauses> AND CV IS NULL`, args `[<non-amount args>, H, H]`, no ORDER BY/LIMIT.
- **Time-range tabs:** in memory over the full period list after the non-amount filters. It's exact because the list isn't paged.
- **Consistency:** the count and the list pages are separate reads. A concurrent insert may make them differ until the next refresh. Both re-run on mutation refresh (`refreshPagedExpensesAfterMutation`) and on every INV-10 key change. The count is advisory.
- **Search parity risk [U]:** the ALL tab also re-applies `matchesSearch` and `filterByOwnership` in memory after SQL (`TransactionsViewModel.kt:246-249`). If `matchesSearch` is narrower than SQL `LIKE`, the SQL count could include rows the list hides. The impact-mapper must confirm `matchesSearch` ⊇ SQL search semantics, or the count must be defined over the SQL predicate and the UX copy must allow for it.

### 4.4 Tab parity
Same row set + same H gives the same result on ALL and on a time-range tab (INV-7). The ledger deciding test runs on ALL and on MONTH. The tab range itself (`getTimeRangeForTab`) is unchanged and independent of the time zone for CV purposes.

### 4.5 H change during a session
On `Available(H1) → Available(H2)`: ALL paging reset + reload from offset 0 (INV-10); in-memory tabs recombine with H2; the count is recomputed; the user's selected sort and bounds are kept. In-flight requests built with H1 are discarded.

### 4.6 Decisions taken here (domain questions in the brief's agenda)
- **D-1** `isNotMine` → `Value(0)` for every currency (agenda: "isNotMine = 0").
- **D-2** Shared branch-3 values are unrounded at read time (brief rec).
- **D-3** Count scope = amount sort **or** amount bound (§4.3).
- **D-4** The id tiebreak also applies to Transactions **date** sorts (`date DESC, id DESC` / `date ASC, id ASC`). Same-timestamp rows hit the same LIMIT/OFFSET gap/duplicate defect as matrix (f). The change is invisible except for the order among exact ties. Assistant untouched.
- **D-5** The assistant stays byte-for-byte unchanged (no tiebreak added). It never pages (`includePagination=false`), so it can't hit the paging defect.
- **D-6** Identity snapshots are exact copies (no rounding): identity is not a conversion, and branch 2 already compares by raw amount.
- **D-7** Valid-for-H requires `exchangeRateUsed > 0` (§1.2).
- **D-8** 1b also emits `MISSING_RATE` on the update no-rate path (symmetry; today it's silent, V9).
- **D-9** The warning shows even when the current H is Loading/Unavailable (the code can't be proven to be the same).

---

## 5. State machines

### 5.1 Read-time H (Transactions)
```
Loading ──first typed emission: Resolved/FirstRunDefault──▶ Available(code)
Loading ──Failed / invalid code / flow error──────────────▶ Unavailable(READ_ERROR|INVALID_CODE)
Loading ──no emission within 5 s─────────────────────────▶ Unavailable(TIMEOUT)
Available(c1) ──emission c2 ≠ c1─────────────────────────▶ Available(c2)   [INV-10 reset]
Available(c) ──emission c (same)─────────────────────────▶ Available(c)    [no reset]
Available ──flow error────────────────────────────────────▶ Unavailable(READ_ERROR)
Unavailable ──pull-to-refresh / screen re-entry (re-subscribe)──▶ Loading
```
- Illegal: any amount query or comparator in `Loading`/`Unavailable`; leaving `Unavailable` without a fresh typed emission; using `Failed.reason` text anywhere.
- Process death: the VM restarts in `Loading`. Nothing is persisted. The user's sort and bounds persist only as today [U].
- Retry and cancellation: re-subscription is idempotent. Collector cancellation propagates (`CancellationException` never swallowed).
- Diagnostics: none required in 1a. If added, one bounded `HOME_CURRENCY_UNAVAILABLE` per entry into `Unavailable`, stage `"transactionsRead"`, no reason text.

### 5.2 Home-switch confirmation (CurrencyManagementViewModel)
```
Idle ──request(code): unsupported──────────────▶ Idle + UNSUPPORTED_HOME_CURRENCY (today)
Idle ──request(code): code == Available H──────▶ Idle (no write)
Idle ──request(code): otherwise────────────────▶ PendingConfirm(target=code)        [warning shown]
PendingConfirm ──cancel / dismiss / back───────▶ Idle (no write)
PendingConfirm ──request(other code)───────────▶ PendingConfirm(target=other)       [single pending]
PendingConfirm ──confirm───────────────────────▶ Writing ──write ok──▶ Idle (H = target; classifier invalidated)
Writing ──write throws (non-cancellation)──────▶ Idle + bounded error; H unchanged [U: DataStore edit atomicity assumed]
```
- Both entry points (`CurrencyManagementScreen.kt:178` picker and `:315` card) call `request`. Neither calls `setHomeCurrency` directly.
- Configuration change: `PendingConfirm` survives in the VM. Process death: lost, which equals cancel.
- Double confirm: a second confirm while `Writing` is ignored. Writing the same code twice is idempotent.
- Optional predicted count in the dialog is a UX choice. If shown, it must come from the INV-8 COUNT with H bound to the **candidate** code, no filters, all rows, number only.
- **After a switch the count shows** every row with `currency ≠ newH` and no Valid-for-newH snapshot. That includes all old-home-currency rows and foreign rows snapshotted in the old home. Rows in the new home currency become comparable through branch 2 (example (d), AE-6).

### 5.3 TLC snapshot step (1b) — per create and per full-row update
```
resolve H ─Failed──────────────────────────────▶ Sentinel + HOME_CURRENCY_UNAVAILABLE   (exists)
          ─ok, currency == H───────────────────▶ Identity amount/H/1.0                  (exists; D-6 exact)
          ─ok, foreign, convertAsOf → null/throws(non-cancel) ─▶ Sentinel + MISSING_RATE (create: NEW; update: sentinel exists, diag NEW)
          ─ok, foreign, conversion ok──▶ roundSnapshot ─Rounded(v)───▶ v/H/rateUsed      (NEW rounding)
                                                       ─Unavailable──▶ Sentinel + MISSING_RATE(reason SNAPSHOT_ROUNDING_UNAVAILABLE)
```
- The step precedes dedup (create step 4, TLC `:587`). A no-rate duplicate therefore emits `MISSING_RATE` and is then skipped. The diagnostic counts snapshot attempts, not rows (same as `HOME_CURRENCY_UNAVAILABLE` today).
- Retry: a retried create recomputes the snapshot (a rate may exist by then).
- Cancellation: `CancellationException` from `convertAsOf` or the sink is rethrown (as today).
- Process death before commit: nothing persisted. After commit: the row keeps its triple.
- MEC flagged branch (A2) sits in the same block. Flagged Home-Failed and no-eligible-rate cases **refuse** rather than writing a sentinel (MEC INV-6/INV-8). The flagged Eligible outcome uses `roundSnapshot` (INV-18). The two branches share the helper and nothing else.

---

## 6. Edge cases

| Case | Rule |
|---|---|
| Mixed currencies in one view | CV per row; the list never sums. Header unchanged (§2.4). |
| Missing FX | Foreign row without a Valid-for-H snapshot → None (last, unfiltered, counted). After 1b the triple is `0.0/""/0.0`. |
| Stale FX | A historical-rate snapshot is accepted as is (no age check on the read side). MEC age limits apply only to flagged saves. |
| Unavailable vs zero | `None` ≠ `Value(0)`. Not-mine rows and 0 % shares are `Value(0)`; never counted. |
| Legacy `'EUR'` default, EUR home | Branch 2 for EUR rows; foreign rows `0.0/'EUR'` fail `baseAmount > 0` → None (critique zero-collision). |
| Legacy `'EUR'` default, non-EUR home | Branch 2 if `currency == H`, else None (a'). |
| Unrounded legacy snapshots | E.g. `20 × 0.92 = 18.400000000000002` stored before 1b: `max 18.40` excludes it, `min 18.40` includes it. Accepted; never rewritten; inc 2 re-snapshot fixes it. Same binary-product effect applies to shared branch-3 values (e.g. `40 × 0.92`). Routed RF-8. |
| `-0.0` | Normalised to `0.0` in Kotlin for parity. |
| Non-canonical `currency` (e.g. lowercase from restored legacy data) [U exists] | Exact match fails → None in both mirrors (pinned by the parity fixture). Validated writes are uppercase (V11). |
| Negative legacy amounts [U exists] | Branch 2 → negative Value (sorts lowest); foreign → None. |
| Time zones / DST | CV and tie order are zone-independent (`date` is an instant). Tab ranges unchanged. |
| Month-end recurrence | Not affected; materialised expenses go through TLC like any create. |
| Edit of a past row | The full-row update re-snapshots against the **current** H (V9, existing). After a switch, editing an old-home row turns it into identity (if its currency == new H), new-H converted, or sentinel. Its old-home snapshot is lost (RF-9, inc 2). |
| Recurring and installment auto-record, no rate | 1b → sentinel + `MISSING_RATE`. MEC AE-21/AE-29 currently expect `'EUR'` → routed RF-10. |
| Partial or failed receipt processing | Receipt creates go through TLC; same four outcomes. Refused flagged receipt saves are MEC's (no row). |
| Duplicates and idempotency | Dedup keys use the original amount and currency (unchanged, MEC INV-21). The read path is pure. The switch confirm is idempotent. |
| Empty states | No rows → count 0, list empty. All rows None + amount sort → list in date order (None block), count = N. All rows None + bound → empty list, count = N. |
| H = FirstRunDefault (key unset) | `Available(EUR, firstRunDefault=true)` (same as TLC). |
| Rounding unavailable (digits < 0, non-finite product) | Sentinel + bounded diagnostic; never stores an unrounded or zero value. |
| Very large values | Amount cap 1,000,000 (or MEC per-currency cap) × rate; `BigDecimal.valueOf` round trip is exact for ≤ 15 significant digits. JPY-home maximum ≈ 1.5e8 (0 dp). |

---

## 7. Write side (1b) specifics

- **M1 (create no-rate, TLC `:572-578`):** `copy(baseAmount = 0.0, baseCurrency = "", exchangeRateUsed = 0.0)`, then `MISSING_RATE` through the V10 sink; delete the `Timber.w`. Update no-rate (`:1116-1125`) keeps its sentinel and gains `MISSING_RATE` (D-8). The new emitter must not pass the exception object to Timber (the existing `Timber.w(e, ...)` at `:321` is out of scope).
- **M3 rounding:** in create `:566-571` and update `:1102-1109`, replace `conversion.convertedAmount` with `roundSnapshot(convertedAmount, H)`. Handle `Unavailable` per §5.3. `rateUsed` stays unrounded. Identity unchanged (D-6).
- **Helper placement:** `A/domain/core/money` (beside `SourceCurrencyAllocation`, which uses the same `BigDecimal.valueOf` HALF_EVEN pattern). **Not** inside MEC `CurrencyAmountPolicy`: that policy "never rounds" (MEC plan A1 `CurrencyAmountPolicy.check`; MEC INV-13). Only one digits accessor may exist (MEC INV-12) → OD-1.
- **MEC consumption:** MEC INV-7 ("or money-correctness's snapshot rounding of it") resolves to `roundSnapshot`. MEC INV-15's interim "formatter on the unrounded product" ends when 1b lands.
- **Sequencing (G2-2):** see OD-1. If MEC A2 lands first, 1b rebases onto the A2 block and A2's flagged Eligible branch adopts `roundSnapshot` in the 1b slice. Loan S3 (`ExpenseSource` `when`, TLC `:508-526`) lands after 1b (brief).
- **Tests (write-side M4):** every outcome in §5.3 asserts all three base fields **and** the presence or absence of the diagnostic, and that no Timber or diagnostic output contains currency or amount (log-capture assertion).

---

## 8. Rescue (M5, 1a)

- `RescueExpense` has no base fields [C]. Rescue can't know a historical rate and **has no H today** (V14).
- Rule (INV-20): `importExpenses` takes a nullable resolved H. If `H != null && exp.currency == H` → `amount/H/1.0`. Otherwise → `0.0/""/0.0`. A null, Failed or absent H → sentinel for every row.
- Today's caller (`RescueActivity`) has no H source. Default per OD-2: it passes `null`, so every rescued row becomes a sentinel row. This has no sort/filter effect: branch 2 covers home-currency rows and branch 3 already rejected the made-up foreign snapshots. Export base cells for rescued rows become empty. Inc 2 re-snapshot can find them.
- Rescue is disabled (`ENABLE_FINANCIAL_RESCUE = false`), so M5 is test-only in 1.0.x. Rescue rows don't go through TLC at restore. Re-snapshot later uses the inc-2 TLC path like any sentinel row.
- Rescue stays the pre-existing raw-SQL restore exception. M5 changes three literal values and must not add a DAO path or a conversion.

---

## 9. Export (1b consequence)

- Mapper unchanged: `homeCurrency = baseCurrency`, `baseAmount` passthrough (finite else 0.0), `baseCurrency` passthrough [C].
- New no-rate rows (and every H-Failed or update-failure row, as today): CSV `homeCurrency` cell `""`, `baseAmount` cell `0.0`, `baseCurrency` cell `""`. JSON: `"homeCurrency":""`, `"baseAmount":0.0` (formatJsonNumber [U] exact text), `"baseCurrency":""`. Q5 accepted; needs a release note.
- Legacy rows keep exporting `0.0` and `EUR` (never rewritten). Inc 3 decides null semantics and sources `homeCurrency` from the resolved H at export time.
- Importers: `CsvExpenseImporter` reads no base columns (V19). JSON importer not re-read [C: "importers never read base columns"]. Backup restore is file-level as far as grep shows (no base-column handling in `DatabaseBackupRepositoryImpl`), so `""` survives restore [U].
- TLC audit JSON (`:2545-2546` [C]) records `baseCurrency ""` instead of `"EUR"` for no-rate creates.

---

## 10. Resolved risks from the brief

**R-U1: writers relying on the `currency` default `'EUR'`.**
- **Expenses: none found.** Every `Expense(...)` construction passes `currency` (V12). `CreateExpenseRequest.currency` has no default. The validator rejects blank or non-ISO codes (V11).
- **Upstream sources that set an expense's currency to a default or guess:**
  1. `ManualRecurringExpenseViewModel.kt:108-116` builds a `ManualRecurringExpense` **without** `currency`, so the rule gets `'EUR'` (`ManualRecurringExpense.kt:25`) and its occurrences carry it (`RecurringLifecycleCoordinator.kt:145, 244`). Whether an expense is later created from that occurrence currency is [U].
  2. `CsvExpenseImporter.kt:238-239`: a row with no currency becomes H, or `"EUR"` when H fails.
  Both produce rows that *say* EUR (or H). The CV treats them by what the row says, so a non-EUR-home user loses CV for those rows. The label is wrong upstream (RF-1, RF-2), not the CV rule. Not fixed here.
- `RecurringExpenseRepository.kt:38, 105` default `"EUR"` parameters: both live callers pass currency (`ManualExpenseRepository.kt:182`, `TransactionsViewModel.kt:761`) [V].

**R-U2: negative amounts.** Validated creates and updates require `amount > 0` (V11). There is no refund type; direction lives in `transactionType` / `transferDirection`, so stored amounts are positive magnitudes. Negatives can only come from legacy or restored data [U exists]; handling in §2.1 and §6. Positive-to-zero can't occur for a positive amount after 1b (INV-16 step 4).

---

## 11. Persistence impact

- **Schema: NONE** in 1a and 1b. No table or column change, no version bump, no migration, no snapshot or migration tests.
- **Data meaning (non-destructive):** 1b writes `""` instead of `'EUR'` in `baseCurrency` for new no-rate creates and rounds new converted `baseAmount`. M5 writes the sentinel (or identity) in Rescue. No existing row is rewritten or backfilled.
- Tests and fixtures that depend on the `'EUR'` base default for no-rate rows will break **intentionally** (brief risk). They must be updated, not deleted.
- Inc 2 re-snapshot is a data operation through TLC, not a migration.

## 12. Privacy rules

- Stored: no new content. Exported: same fields; values `""` for no-rate rows (1b).
- Logged: 1b **removes** the currency, amount, home and date log line (MEC RF-5). New diagnostics are the bounded `MISSING_RATE` with `{operation, reason}` (+ `entityId` on update, as the existing `HOME_CURRENCY_UNAVAILABLE` does).
- UI state: count (integer), `AmountOrderingState` and `ReadHomeFailure` (bounded enums). `HomeCurrencyResolution.Failed.reason` (raw exception text, V7) must never reach UI, logs or diagnostics from this feature.
- Warning dialog: static copy plus at most the target currency's display name and an optional integer. No merchants, amounts, dates or categories. It must not claim values were converted (INV-22).
- No network, cloud AI or permission change.

---

## 13. Acceptance examples (synthetic; merchants `M1..Mn`, dates `D1 < D2 < …` epoch ms, ids ascending with insert order)

Notation: row = `currency amount [ownership] base=(baseAmount/baseCurrency/rate)`. "ALL" = SQL paged builder in `HomeComparable(H)`; "MONTH" = in-memory path. Unless stated, every list AE must hold identically on ALL and MONTH (INV-7).

**Read side (1a)**
- **AE-1 (b) no rate, INV-1/2/5/6/8.** Given H=EUR; `E1 EUR 15.00 base=(15.00/EUR/1.0) D1`, `E2 EUR 25.00 base=(25.00/EUR/1.0) D2`, `U1 USD 20.00 base=(0.0/EUR/0.0) D3`. When AMOUNT_DESC → `[E2, E1, U1]`; AMOUNT_ASC → `[E1, E2, U1]`; `min=15` → `[E2, E1]` (DESC) and count = 1. Then: repeat with `U1 base=(0.0/""/0.0)` → identical results. U1 still renders "$20.00".
- **AE-2 (b') converted foreign on ALL, INV-1/7.** Given H=EUR; `C1 USD 20.00 base=(18.40/EUR/0.92)`. When `min=19` → C1 excluded; `max=19` → C1 included. Same on ALL and MONTH. Count = 0.
- **AE-3 (a) legacy home-currency row, INV-1.** Given H=USD; `L1 USD 50.00 base=(0.0/EUR/0.0)`. Then CV = 50.00 (branch 2); `min=50` includes it.
- **AE-4 (a') legacy foreign row, INV-2.** Given H=USD; `L2 EUR 40.00 base=(0.0/EUR/0.0)` → None, last in both directions, counted. Given H=EUR, the same row → CV 40.00. Given H=EUR and `L3 USD 50.00 base=(0.0/EUR/0.0)` → None (`baseCurrency == H` but `baseAmount = 0`).
- **AE-5 (c) home unresolved at create, INV-1.** Given H=EUR; `X1 EUR 12.00 base=(0.0/""/0.0)` → 12.00; `X2 USD 12.00 base=(0.0/""/0.0)` → None.
- **AE-6 (d) home switch, INV-1/8/12.** Given `A EUR 100.00 base=(100.00/EUR/1.0) D1`, `B USD 30.00 base=(27.78/EUR/0.926) D2` recorded under EUR; the user confirms the switch to USD; then `C USD 95.00 base=(95.00/USD/1.0) D3`. When H=USD AMOUNT_DESC → `[C(95), B(30), A]`; `min=95` → `[C]`; count = 1 (A). When the user switches back to EUR → `[A(100), B(27.78), C]` (C None, no USD→EUR rate), count = 1 (C).
- **AE-7 (e) Rescue rows, INV-1/20.** Given H=EUR; today's Rescue row `R1 USD 20.00 base=(20.00/USD/1.0)` → None (SNAPSHOT_OTHER_CURRENCY). When Rescue imports `R1` and `R2 EUR 10.00` with H argument `null` → both `base=(0.0/""/0.0)`; CV(R1)=None, CV(R2)=10.00. With H argument `EUR` → R2 `base=(10.00/EUR/1.0)`, R1 sentinel.
- **AE-8 (f) paging, INV-5/10.** Given H=EUR, 60 CV rows (including 5 with equal CV 10.00, and 3 of those with equal `date`) and 45 None rows (including 4 with equal `date`), ALL tab, AMOUNT_DESC, page size 50. When pages at offsets 0, 50 and 100 are loaded → the concatenation has 105 distinct ids and equals the unpaged order. Rows 61–105 are the None rows ordered `date DESC, id DESC`. Equal-CV rows are ordered `date DESC, id DESC`. Same for AMOUNT_ASC (None block identical and last).
- **AE-9 isNotMine, INV-3.** Given H=EUR; `N1 USD 50.00 isNotMine base=(0.0/EUR/0.0)` and `E1 EUR 5.00`. Then CV(N1) = 0.0; AMOUNT_DESC `[E1, N1]`; `min=0` includes N1; `min=0.01` excludes it; count = 0.
- **AE-10 shared foreign rows, INV-4/7.** Given H=EUR; `S1 USD 100.00 shared myShareAmount=50.00 base=(50.00/EUR/0.5)` → CV 25.00; `S2 USD 100.00 shared pct=50 base=(92.00/EUR/0.92)` → CV 46.00; `S3 USD 100.00 shared myShareAmount=50.00 base=(0.0/""/0.0)` → None; `S4 EUR 80.00 shared pct=25` → 20.00 (branch 2). `max=25` → `{S1, S4}` on both mirrors.
- **AE-11 H Failed / Loading, INV-9.** Given AMOUNT_DESC and `min=15` selected, H resolution Failed. Then the list is in `date DESC, id DESC` order, min is not applied, `AmountOrderingState = HOME_CURRENCY_UNAVAILABLE`, no count query runs, and no SQL arg equals a placeholder code. When refresh yields Available(EUR) → AE-1 results. While `Loading` → `HOME_CURRENCY_LOADING`, same fallback; the `"EUR"` stateIn placeholder never reaches a query.
- **AE-12 H change mid-paging, INV-10.** Given ALL, AMOUNT_DESC, 2 pages loaded under EUR, with a page-3 request in flight. When H becomes USD → state resets to offset 0, page 1 reloads under USD, the late EUR page-3 result is discarded, and the count is recomputed under USD.
- **AE-13 assistant unchanged, INV-11.** Given the AE-1 rows. When `getAssistantExpensesFiltered(minAmount=15)` is called → the result includes U1 (raw 20 ≥ 15), as before. The SQL text equals the pre-1a golden string (WHERE raw `EFFECTIVE_AMOUNT_E_SQL >= ?`, `ORDER BY e.date DESC`, no tiebreak, no H arg). `getAssistantExpenseCountFiltered(...)` → same count and SQL as before. Live `ExecuteFinancialQueryUseCase` results for an in-memory min/max query are unchanged.
- **AE-14 warning cancel/confirm, INV-12.** Given Available H=EUR. When the user taps the USD card (`:315`) → warning shown, DataStore unchanged. Cancel → H=EUR, classifier not invalidated. Repeat via the picker (`:178`) and confirm → exactly one write of USD, classifier invalidated, Transactions resets (AE-12). When the user taps EUR while H=EUR → no dialog, no write. When H is Unavailable and the user taps EUR → dialog shown.
- **AE-15 tab parity, INV-7.** Given the union of the AE-1..AE-10 rows (H=EUR). Then ALL and MONTH (all rows dated in the current month) produce identical id sequences for the four sort orders and identical membership for `min=15`, `max=25`, `min=0`.
- **AE-16 date sort tiebreak, INV-5 (D-4).** Given three rows with identical `date`, ids 7, 8, 9. DATE_DESC → `[9, 8, 7]`; DATE_ASC → `[7, 8, 9]`; stable across pages of size 2.
- **AE-17 legacy oddities, §6.** Given H=USD; `Q1 usd 10.00 base=(0.0/EUR/0.0)` → None on both mirrors. Given H=EUR; `Q2 EUR -5.00 base=(0.0/EUR/0.0)` → CV −5.00 (first in ASC); `Q3 USD -5.00 base=(-4.6/EUR/0.92)` → None.

**Write side (1b)**
- **AE-18 Converted, INV-14/16/17.** Given H=EUR, rate USD→EUR 0.75. When creating USD 13.50 → `base=(10.12/EUR/0.75)`; CV 10.12; list shows "€10.12"; no diagnostic.
- **AE-19 Identity exact, INV-17 (D-6).** Given H=EUR. When creating EUR 12.34 → `base=(12.34/EUR/1.0)`. When a legacy-precision EUR 10.125 is updated (category edit via full-row update) → `base=(10.125/EUR/1.0)` (not rounded).
- **AE-20 Home Failed, INV-14.** Given H resolution Failed. When creating USD 20.00 → `base=(0.0/""/0.0)` + one `HOME_CURRENCY_UNAVAILABLE` (stage `createExpense`); no `MISSING_RATE`.
- **AE-21 No rate (create), INV-14/15.** Given H=EUR and no USD rate. When creating USD 20.00 → `base=(0.0/""/0.0)` + one `MISSING_RATE` (stage `createExpense`, metadata only `operation`, `reason`). Captured logs contain no "USD", "20" or "EUR" from this step. Same for an installment auto-record and a recurring materialisation (supersedes MEC AE-21/AE-29 `'EUR'` expectation, RF-10).
- **AE-22 Update failure, INV-14/15 (D-8).** Given the AE-18 row and no USD→GBP rate; H switched to GBP. When the row is updated (full-row) → `base=(0.0/""/0.0)` + one `MISSING_RATE` (stage `updateExpense`, entityId set).
- **AE-23 rounding boundaries, INV-16.** `roundSnapshot` (helper unit + TLC integration where noted):
  - `13.50 × 0.75 = 10.125` → EUR **10.12** (TLC).
  - `2.03 × 0.5` (double prints `1.015`) → EUR **1.02**, which displays "€1.02". `CurrencyFormatter` on the raw double would show 1.01 (TLC).
  - `1.025` → **1.02**; `0.135` → **0.14**.
  - JPY home: `20.00 × 151.237` → **3025**. Ties: `3024.5` → **3024**, `3025.5` → **3026**.
  - Positive→zero, EUR home: `HUF 1.00 × 0.0025 = 0.0025` → HALF_EVEN 0.00 → **0.01**; exactly `0.005` → 0.00 → **0.01**; `0.0061` → **0.01** (ordinary rounding, not the floor rule; brief example corrected, routed RF-5 in §17).
  - 3-dp (helper only, code `BHD`): `1.2345` → **1.234**; `0.0004` → **0.001**.
  - Rate stored unrounded: USD 10.00 at `0.923456` → `base=(9.23/EUR/0.923456)`.
  - `NaN`, `Infinity`, `0.0`, `-1.0` → `Unavailable(NON_FINITE_OR_NON_POSITIVE)`. TLC → sentinel + `MISSING_RATE(reason SNAPSHOT_ROUNDING_UNAVAILABLE)`.
- **AE-24 MEC preview parity, INV-18.** Given H=EUR, flagged entry USD 2.03, Eligible rate 0.5. Then the preview shows "€1.02" and the saved row has `base=(1.02/EUR/0.5)`.
- **AE-25 export cells, INV-21.** Given the AE-21 row and the AE-18 row. When exported as CSV → AE-21 row: `homeCurrency=""`, `baseAmount=0.0`, `baseCurrency=""`; AE-18 row: `EUR`, `10.12`, `EUR`. JSON equivalents. Re-importing the CSV ignores base columns; the new rows get TLC snapshots.
- **AE-26 existing rows untouched, INV-17.** Given a pre-1b row `base=(18.400000000000002/EUR/0.92)`. After 1b ships and the app runs (no edit) → the value is unchanged. `max=18.40` excludes it (documented, RF-8).

Count: **26** AEs. Matrix coverage: (a) AE-3, (a') AE-4, (b) AE-1, (b') AE-2, (c) AE-5, (d) AE-6, (e) AE-7, (f) AE-8. isNotMine AE-9; shared AE-10; H Failed AE-11; H change AE-12; assistant AE-13; warning AE-14; four TLC outcomes AE-18/20/21/22 (+ identity AE-19); rounding AE-23; export AE-25.

---

## 14. Increment 2/3 framing (not specified here)

- **Inc 2 legal path:** a new TLC operation (proposed `resnapshotConversion`). It extends the TLC snapshot step and adds no DAO path. Per row, it does a compare-and-set on `(amount, currency, date, baseAmount, baseCurrency, exchangeRateUsed)` as read; the rate is read through the same rate read as the create path, and the value is rounded with `roundSnapshot`. Row selection: `currency != H AND NOT Valid-for-H`. This is a **new legal path → human decision at inc 2 Gate 1**: per-row `UPDATED` event vs a new bounded event kind, and whether it counts as a `TransactionUpdateKind` (it must not trigger recurring reconcile, since amount, date and currency don't change).
- **Triggers:** a completed user-started rate refresh; home-switch confirm (CURR-6 hook, `CurrencyManagementViewModel.kt:148-182`); optionally an app-start sweep. Execution: a unique WorkManager job with `WorkerExecutionGuard`, `DatabaseWriteBarrier` and barriers, bounded batches, idempotent, `CancellationException` propagated. The warning text can then mention recovery.
- **Date basis (OD-3 of MEC):** today's snapshot uses `convertAsOf(atMillis = date)` (instant). MEC flagged saves use the calendar date in the entry zone. Inc 2 aligns TLC unflagged, analytics and re-snapshot on one basis. Drift between rounded snapshots and unrounded analytics is ≤ half a minor unit per row (accepted).
- **Also inc 2:** analytics alignment (snapshot-authoritative); F-4 budget latest-basis raw fallback; RF-9 (old-home snapshot loss on edit).
- **Inc 3:** export `baseAmount` null or empty for unconverted rows; `homeCurrency` column from the resolved H at export time; SRH-7 (NL search amount filters, `NaturalLanguageSearchEngine.kt:347-356` converts at read time).

## 15. Proposed architecture-doc changes (not edited here)

`LEGAL_PATHS.md` → Money / Currency (`:534-552`), add:
```
COMPARE amounts against home (Transactions sort / min-max / "without home value" count):
  → ComparableHomeValue policy (domain/core/money) — rule owner
  → COMPARABLE_HOME_VALUE_E_SQL mirror (ExpenseDao companion), parity-tested
  → H from typed read-time resolution, bound as SQL parameter; Loading/Failed → amount ops disabled
SNAPSHOT base fields (baseAmount/baseCurrency/exchangeRateUsed):
  → TransactionLifecycleCoordinator only (create snapshot step, full-row update)
  → converted value via snapshot rounding helper (HALF_EVEN, BigDecimal.valueOf, home minor units, min one unit)
  → unconverted sentinel 0.0 / "" / 0.0 + bounded MISSING_RATE / HOME_CURRENCY_UNAVAILABLE
FORBIDDEN:
  ❌ Raw-amount fallback for rows without currency == home or a valid home snapshot
  ❌ Interpolating the home currency into SQL; ❌ amount ops against a placeholder home
  ❌ A second snapshot rounding rule; ❌ writing base fields outside TLC (Rescue restore excepted: identity/sentinel only)
```
`UPDATE expense` (`:52-61`): note that the full-row `updateExpense` recomputes the snapshot against the current home.

## 16. Open decisions (human)

| ID | Decision | Recommended default | Alternative |
|---|---|---|---|
| OD-1 | **Digits accessor owner and 1b vs MEC A1 order.** The MEC plan has A1 depend on "money-correctness landed", while the brief places 1b "just before A2" and the helper needs the single digits accessor that A1 would create (MEC INV-12). That's circular. | **A:** 1b creates the digits accessor (`java.util.Currency.defaultFractionDigits`) and `roundSnapshot` in `domain/core/money`. MEC A1's `CurrencyAmountPolicy.fractionDigits` delegates to it (one source, MEC INV-12 owner text updated). Order: ai-fixes → 1a → 1b → S0 → A1 → A2 (matches G2-1). | B: order ai-fixes → 1a → S0 → A1 → 1b → A2, restating A1's dependency as "money-correctness 1a landed"; the helper consumes `CurrencyAmountPolicy.fractionDigits`. |
| OD-2 | **Rescue H source (M5).** Rescue has no H (built by hand, pre-Hilt, raw thread) and is disabled. | `importExpenses` takes a nullable H; `RescueActivity` passes `null` → sentinel for every row. No new DataStore wiring. Identity becomes possible if a future caller supplies H. | Read the currency DataStore in `RescueActivity` (new startup dependency in a disabled path). |

No other human decisions. D-1…D-9 (§4.6) are domain decisions within the approved brief and Gate 1 answers.

## 17. Routed findings

| ID | Finding | Route |
|---|---|---|
| RF-1 | `ManualRecurringExpenseViewModel.kt:108-116` creates rules without `currency` → `'EUR'` for every home → occurrences (`RecurringLifecycleCoordinator.kt:145, 244`) carry EUR [V]. | recurring owner |
| RF-2 | `CsvExpenseImporter.kt:238-239` guesses currency (H, or `"EUR"` when H fails) for rows without one [V]. This conflicts with "no default currency is ever guessed" (email path, `LEGAL_PATHS.md:249`). | import owner |
| RF-3 | Assistant: the preview count (`ExecuteFinancialQueryUseCase.kt:122-129`) ignores min/max while the list path filters them in memory → count/list mismatch [V]. Dead `@Deprecated assistantFilteredExpenses` (`:466-488`) is the only raw-SQL min/max caller → delete candidate. | ai-fixes / SRH-7 |
| RF-4 | Date header: same-currency groups are signed (`TransactionsScreen.kt:528`), mixed groups unsigned (`:535`) → deposits and incoming transfers add as spending in mixed groups [V]. Unchanged here. | Transactions owner |
| RF-5 | Brief example "JPY 1 × 0.0061 → €0.01" isn't a positive→zero case (0.0061 rounds to 0.01 normally). Corrected examples in AE-23. | orchestrator (brief note) |
| RF-6 | The Transactions initial tab is MONTH (`TransactionsViewModel.kt:81`), not ALL; brief/critique say "default ALL tab". The defect matters on ALL whenever selected. | ux-flow-designer |
| RF-7 | `TransactionsViewModel.kt:201-204` `"EUR"` placeholder, and `homeCurrency()` with no failure typing (V7). The CV must use the typed state (§1.4). | impact-mapper (1a) |
| RF-8 | Unrounded legacy snapshots and binary shared products exclude rows at exact `max` boundaries (AE-26). | money-policy item / inc 2 |
| RF-9 | Full-row `updateExpense` re-snapshots against the current H on any edit (V9). After a switch, an old-home snapshot is lost and can't be used on switch-back. | inc 2 |
| RF-10 | MEC `domain-spec.md` AE-21, AE-29 and §5 "keep today's defaults until money-correctness lands" expect `baseCurrency="EUR"`; after 1b it is `""`. MEC INV-12 owner text changes under OD-1 A; MEC INV-15 interim ends. | MEC spec/plan owner |
| RF-11 | `HomeCurrencyResolution.Failed.reason` carries raw exception text (`CurrencySettingsRepositoryImpl.kt:107, 116`). Consumers must map it to bounded codes. | privacy-guardian (watch) |
| RF-12 | The impact-mapper must confirm (a) `matchesSearch` ⊇ SQL search (count parity, §4.3) [U], (b) that partial TLC updates (`updateCategory` etc.) preserve base fields and that no partial path changes amount, currency or ownership without the `:1099-1126` snapshot [U], (c) the query plan cost of the bound CASE in ORDER BY plus the extra COUNT on large tables. | impact-mapper |
