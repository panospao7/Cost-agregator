# Feature brief: manual-entry-currency — Currency on manual expense entry (convert at save, block when no rate)

Author: feature-strategist (stage 1). Persisted by the orchestrator. Date 2026-10-16.
Status: stage-1 brief (docs, post-1.0). Money feature, expected **T3** (reviewer-strict). ~26 tool calls. `A` = `app/src/main/java/com/yourname/expensetracker`. Facts from earlier docs cited to them; "verified now" = checked in this pass; else UNVERIFIED.

**Recommendation: pursue as T3**, after: R23 part 1 lands (else rates never download unless cloud AI is on → every foreign entry blocked); R16 lands (comma decimals); owner decides Q4 ("increment 0" scope).

## Problem
Manual expenses are always saved in home currency — add-expense has no currency control (`A/ui/screens/addexpense/AddExpenseViewModel.kt:59-63, 276-282, 380-381`; `A/data/repository/ManualExpenseRepository.kt:89`; nl-quick-add critique §1). A user who paid 20 USD must convert by hand or record it wrongly as 20 EUR. Owner goal: keep the original currency, convert at save; no stored rate for the expense date → block save; never count raw amount as home currency.

## Target users / situations
- Travel: cash/card abroad, on the day or catching up after the trip.
- Foreign online purchases: USD/GBP subscriptions, app stores without notification/receipt.
- Greek owner (home EUR): occasional USD/GBP; neighbouring currencies (BGN, TRY, RON).
- Home ≠ EUR users: conversion goes through EUR (`CurrencyConverter.kt:294-316`, verified now).

## Desired behaviour — worked examples (illustrative amounts)
1. **EUR home, USD 20.00 dated 15 Oct, rate stored:** pick USD; live preview "≈ €18.52 · ECB rate of 15 Oct"; save via existing coordinator (currency USD, amount 20.00 + base snapshot); list shows original with converted.
2. **USD 20.00, no rate stored** (download off — today tied to cloud AI, HZ01b2-F1; never downloaded; date before first stored rate): save blocked, "No exchange rate for this date"; form keeps input; actions: change currency/date or open the rate-download setting (R23) — never auto-enabled; nothing written.
3. **JPY 1500:** whole yen only; "1500,50" flagged, not silently rounded; displays ¥1,500; max-amount check in currency-appropriate terms; preview ≈ €9.x.
4. **Changing currency of a saved expense:** not in increment 1 (no amount/currency edit screen exists); workaround delete + re-add; later increment via coordinator CURRENCY update with same block rule.
5. **Recurring USD subscription (20 USD monthly from add-expense recurring toggle):** rule keeps USD (`ManualExpenseRepository.kt:182`); each occurrence converts at its own date; background occurrences can't be "blocked" → with no rate saved visibly **unconverted** (out of comparisons, shown "not converted", never €20). Depends on increment 0.

## Must-have scope
**Increment 0 — fix the existing no-rate bug at its single write point** (recommended fold-in; Q4)
- Coordinator no-rate branch (`TransactionLifecycleCoordinator.kt:554-578`) marks the row unconverted with the same marker as other failure branches (`baseCurrency=""`); bounded reason code; stop logging currency + amount (`:573-577`).
- Transactions amount sort + min/max filter treat foreign unconverted rows as "no comparable value" instead of raw fallback: `Expense.kt:179`, `TransactionsViewModel.kt:292-293, 900`, SQL `SortOrder` fallback `ExpenseRepository.kt:62,71` (no-rate-verification.md).
- Pin the currently unpinned "EUR" default with tests.
- Applies to all sources (notification, receipt, bank statement, CSV/JSON, recurring). Looks migration-free (domain-modeler to confirm).

**Increment 1 — the currency control**
- Control on add-expense next to amount; defaults to home every time the sheet opens.
- Lists only active supported currencies (`SupportedCurrency.activeCatalog`, `CurrencyConverter.kt:21-65`, verified now); currencies that can't get a rate disabled or omitted (D3).
- Live conversion preview with converted amount + rate date; hidden for home currency.
- Save blocked when no stored rate as of the expense date — a rule on the existing coordinator create path (domain-modeler), not a new write path.
- Decimals, rounding, max amount follow the currency (today always 2dp `AddExpenseViewModel.kt:367-370`; cap 1,000,000 raw units `:297` — verified now).
- Recurring toggle for foreign currency: rule keeps currency (needs increment 0).
- No raw amounts/currencies in logs; bounded reason codes only.

## Later increments
2 user-entered rate when blocked, stored provenance (Q2); 3 edit amount/currency/date of existing manual expense via coordinator, same block rule; 4 sticky "trip currency" with expiry (Q3); 5 recover unconverted rows (backfill when rate arrives or read-time compute; CURR-6/CURR-1, `docs/development/FUTURE-WORK.md:139-141`); 6 optional ECB historical-rate download (larger fetch; separate privacy review); quick-add inc 1b maps typed currency markers into this control (owned by nl-quick-add).

## Non-goals
No new expense write path (coordinator → `ExpenseDao.insertAtomic()`); no currency guessing from locale/merchant/GPS; no silent latest-rate fallback on save; no automatic network fetch at save; no change to analytics/dashboard aggregation (already read-time conversion + exclusion); not fixing `FinancialRescueCoordinator.kt:418-420` (invented 1.0 rate), CURR-6, deprecated raw SUM queries, budget F-4 → separate "money-correctness 1.0.x"; no cloud AI.

## Existing functionality reused / overlapped
- `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:529-585` — converts at save as of expense date; single legal write path.
- `A/domain/currency/CurrencyConverter.kt:256-320` — as-of conversion. Verified now: "a rate" = most recent stored rate dated ≤ expense date (`ExchangeRateDao.kt:62`), direct then via EUR; **no age limit**; nothing for unknown codes.
- `A/data/repository/CurrencyRatesRepositoryImpl.kt:39,48,103` — only source is **ECB daily XML (today only)**, dated by ECB publication date; download gated on cloud-AI consent (HZ01b2-F1 → R23 part 1). Expense dated before first stored rate is always blocked.
- `A/ui/screens/receiptscan/ReceiptScanScreen.kt:883,1385`, `ReceiptScanViewModel.kt:561` — existing `CurrencyPicker` is **`private`** (verified now) → move into a shared component (UI only); active-currency filtering UNVERIFIED.
- `CurrencyConverter.kt:733-734` — formatter using standard Java currency digits; reuse as single source for decimals.
- `ManualExpenseRepository.kt:175-188` — creates recurring rule with expense currency via `recurringExpenseRepository.insert`; TODO "Should route through RecurringLifecycleCoordinator" conflicts with `LEGAL_PATHS.md:362` (domain question).
- `TransactionLifecycleCoordinator.kt:981` `updateExpense` + CURRENCY update kind (`LEGAL_PATHS.md:348`, `TransactionSideEffectPlanner.kt:71`) exist; only UI caller found is split edit (`ui/MainActivity.kt:301` → `ExpenseRepository.kt:456`); no amount/currency edit screen found (not exhaustive, UNVERIFIED).
- Duplicate detection already compares currency (`LEGAL_PATHS.md:80-89`).
- Analytics converts at read time, excludes failed rows (`AnalyticsCurrencyNormalizer`).
- `A/ui/screens/currency/CurrencyManagementViewModel.kt:165` — home-currency setting (read only).

## Market patterns
- YNAB: no conversion within a budget; one budget per currency; record rate/fees at transfer; final charged rate unknowable at entry — [YNAB multiple currencies](https://support.ynab.com/en_us/using-multiple-currencies-in-ynab-a-guide-SyBF6PHno.md), [YNAB digital nomad guide](https://www.ynab.com/blog/the-digital-nomads-guide-to-budgeting-in-different-currencies).
- Monarch: never converts; warns mixed totals mislead (10,000 CAD + 10,000 USD shown as $20,000) — the failure our block rule prevents — [Monarch international accounts](https://help.monarch.com/hc/en-us/articles/360048393552-International-Accounts-and-Currency), [Monarch manual transactions](https://help.monarch.com/hc/en-us/articles/360058441811), [Sacra](https://sacra.com/chat/h/c1c03e02-e948-4b2d-8be0-4438aa173d71/).
- Currency drop-down inside amount field (Accountable); dated custom rate when automatic rates off (Zoho Expense) — [Accountable](https://help.accountable.eu/en/articles/10304940-how-to-add-expenses-in-other-currencies), [Zoho Expense](https://www.zoho.com/us/expense/help/setting-up-organization/currencies/).
- Spendee/Wallet automatic rates: third-party blog only; per-expense override UNVERIFIED — [getfinny](https://getfinny.app/blog/multi-currency-expense-tracker-digital-nomads).
- **External fact:** ECB removed the Bulgarian lev from reference rates on 2 Jan 2026 (euro adoption) and has published no rouble rate since 1 Mar 2022, yet BGN and RUB are active in `SupportedCurrency` — [ECB reference rates](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.ro.html), [S&P Platts notice](https://www.spglobal.com/energy/en/pricing-benchmarks/our-methodology/subscriber-notes/010226-platts-discontinues-the-ecb-bulglev-per-euro-assessment).

## Privacy & permissions impact: low
No new network, permissions, cloud AI, or receipt/notification/OCR content. Depends on the rate download (R23 part 1 → own setting; HTTPS GET of public ECB data, no user content). Block message may point to that setting, never enable it. Replace currency+amount log at `TransactionLifecycleCoordinator.kt:573-577` with bounded reason code; preview/block never log typed amount. Increment 2 provenance field would add an exported field → review.

## Risks
1. Unusable without R23 (cloud AI off by default → no rates → every foreign entry blocked).
2. Strict as-of lookup, no history → expenses before first download always blocked (hurts post-trip catch-up).
3. As-of has no age limit → months-old rate silently used (e.g. leftover BGN 2025); mitigate by showing rate date.
4. RUB/BGN can never get new rates → permanently blocked or quietly stale.
5. Background recurring occurrences can't be blocked → without increment 0 they create raw-comparing rows.
6. UI-only block is racy / bypassable by other callers → coordinator-level rule (D1).
7. Same files as nl-quick-add inc 1 and loan-installments S11 → single writer, fixed order.
8. Recurring rule creation may bypass its lifecycle coordinator (TODO `ManualExpenseRepository.kt:185`).
9. Max-amount cap in raw units — wrong for JPY/KRW/HUF/ISK.

## Success signal (no content telemetry)
Tests prove manual entry never creates a foreign row with invented or raw-counted base amount; no-rate rows from any source sort/filter as "not comparable"; zero-decimal currencies exact end to end; on-device bounded counter (e.g. `MANUAL_SAVE_BLOCKED_NO_RATE`, no amount/currency) signals whether increment 2 is worth it; user sees original + converted amount + rate date.

## Owner questions (with recommended answers)
1. What counts as "a rate for this date"? **Rec:** existing as-of rule (latest stored rate ≤ expense date, any age), rate date always shown in preview; no latest-rate fallback on save; any age limit proposed by domain-modeler.
2. When blocked, may the user type their own rate? **Rec:** not in increment 1 — offer change currency/date or download setting; increment 2 (needs provenance + consistency with analytics read-time conversion, which would ignore a per-expense rate — UNVERIFIED; schema change possible).
3. Remember last-used currency? **Rec:** no — default home every time; show recently used first only if no new storage needed; sticky trip currency later.
4. Fold increment 0 (unconverted marker for all sources + Transactions sort/filter non-comparable) into this feature? **Rec:** yes, narrowly — same write point, same strict review; this feature raises no-rate frequency via recurring; looks migration-free. Backfill, rescue fabrication, CURR-6, deprecated SUMs stay in a separate money-correctness item.
5. Recurring rules in foreign currency in increment 1? **Rec:** yes, only with increment 0 (no-rate occurrences saved visibly unconverted). If Q4 declined, hide recurring toggle for non-home currencies.
6. Editing amount/currency of existing expenses in scope? **Rec:** no for increment 1; later via coordinator CURRENCY update with same block rule.

## Domain questions for domain-modeler
- D1 Where the no-rate block lives (coordinator rule for manual creates, fail-closed on the legal path, and/or UI pre-check); rate changing between check and save.
- D2 Exact "unconverted" meaning (reuse `baseCurrency=""`?); legacy vs no-rate rows (both `baseAmount=0, baseCurrency="EUR"`; `AppDatabase.kt:6105`); is "foreign + base 0 = not comparable" a safe read-only rule; interaction with CURR-6; export/backup consumers of `baseCurrency`.
- D3 Currencies without a rate source: RUB (none since 2022), BGN (removed 2026; fixed 1.95583/EUR — constant or deactivate like HRK?), ISK/others vs ECB list; hide or disable in picker.
- D4 Precision: per-currency decimals from one source (Java currency digits vs table in `SupportedCurrency`); rounding mode for amount and converted amount; parsing with R16 comma decimals; max amount per currency vs home terms.
- D5 Rate provenance: `exchangeRateUsed` + rate date enough? Where a user-entered rate lives (rate table vs per expense); consistency with analytics; schema change for increment 2 (likely).
- D6 Recurring: currency inheritance; per-occurrence conversion via coordinator (UNVERIFIED); does `ManualExpenseRepository.kt:185` bypass `RecurringRuleLifecycleCoordinator`; overlap with R21 V-C4 ("recurring default EUR").
- D7 Home currency itself has no rate (home ≠ EUR via EUR, `:543-553` sentinel) — same block?
- D8 Duplicate window / money comparison when the same purchase later arrives as a notification in a different or already-converted currency.

## Dependencies and sequencing
- R23 part 1 (separate rate-download setting) = **hard** dependency; R16 lands in 1.0 first.
- Add-expense order: nl-quick-add inc 1 → loan-installments S11 → this feature (or before S11 by orchestrator choice; never parallel; single writer) → quick-add inc 1b.
- Owner ordering recorded: ai-fixes first after 1.0.

## Schema flag
Increments 0 and 1 look migration-free (currency field and `""` marker exist). Increment 2 (manual rate + provenance) and possibly 5 (backfill) likely need schema change/migration — domain-modeler to confirm.
