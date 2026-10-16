# Feature brief v2: manual-entry-currency — choose a currency on manual entry; convert at save; block when no recent rate

Author: feature-strategist (stage 1b, single reshape pass). Persisted by the orchestrator. Date 2026-10-16. Docs only, post-1.0. Money feature, **T3** (reviewer-strict).
Base `brief-v1.md`; `critique.md` fact corrections binding; Gate 1 answers in `workflows/active/manual-entry-currency-handoff.md` §Human decisions binding. `A` = `app/src/main/java/com/yourname/expensetracker`. "Checked now" = verified in this pass; else from critique or UNVERIFIED.

**Recommendation: pursue as T3 (reshaped).** Increment 1 = currency control for user-present saves with one "rate for this date" rule, enforced at the existing coordinator create path. Increment 0 → `money-correctness-1.0.x`. ECB history → increment 1b. Hard deps: R23 part 1, R16. **New finding:** loan-installments S11 already adds a currency control to the same sheet → must converge (N2).

## Problem
Manual expenses are always saved in home currency; add-expense has no currency control (`A/ui/screens/addexpense/AddExpenseViewModel.kt:59-63, 276-282, 380-381`; `A/data/repository/ManualExpenseRepository.kt:89`). A user who paid 20 USD converts by hand or records 20 EUR. Owner goal: keep original currency, convert at save; no usable rate for the expense date → block; never count raw foreign amount as home currency.

## Target users / situations
Travel (on the day or catch-up — catch-up depends on 1b); foreign online purchases without notification/receipt; receipt scans of foreign receipts (user confirms currency on review); Greek owner (home EUR; USD/GBP, TRY, RON; BGN no longer an ECB currency); non-EUR home users (ECB cross pairs stored at refresh, `CurrencyRatesRepositoryImpl.kt:84-98`).

## Desired behaviour — worked examples (ECB publishes USD per 1 EUR; 20 / 1.0800 = 18.5185 → €18.52)
1. **Normal** (EUR home, USD 20.00 Wed 15 Oct, ECB rate of 15 Oct stored): pick USD; preview "≈ €18.52 · ECB rate of 15 Oct" (ECB date, not download time); save via existing coordinator (USD, 20.00, base per snapshot rounding rule); list shows original + converted.
2. **Weekend** (USD 20.00 Sun 12 Oct; latest rate Fri 10 Oct, 2 days older): allowed; "· ECB rate of 10 Oct".
3. **No rate at all** (setting off / never downloaded / no ECB source): blocked "No exchange rate for this date"; form keeps input; nothing written; actions: change currency/date, or "Open exchange-rate setting" (R23; never auto-enabled).
4. **Stale (8 days)** (setting on; last refresh stored 7 Oct; expense 15 Oct; today 16 Oct): 8 > 7 → blocked "No exchange rate for this date · latest rate 7 Oct"; "Refresh rates" (user-started, only when setting on). **Caveat (new):** daily ECB file holds only the latest publication — after 16:00 CET on 16 Oct that's the 16 Oct rate, later than the expense date → not eligible. Refresh unblocks today/recent, **not** this 15 Oct entry; only 1b or a different date fixes it → N1, RQ2.
5. **Near midnight, Greece** (GBP 30.00 at 16 Oct 01:30 Athens UTC+3 = 15 Oct 22:30 UTC; first download 17:00 16 Oct stores rate of 16 Oct as `validDate` 16 Oct 00:00 UTC): instant comparison → false block; **local calendar date** comparison (16 Oct vs 16 Oct) → allowed. Reverse: 15 Oct 23:30 local → 16 Oct rate correctly not eligible.
6. **JPY 1500**: whole yen only; "1500,50" rejected inline, never rounded; displays ¥1,500; preview ≈ €9.xx; cap in currency-appropriate terms (1,000,000 KRW ≈ €650 allowed); switching EUR "12.50" → JPY marks field invalid, not truncated.
7. **Receipt review (new)**: OCR proposes 42.00 USD, user confirms. Eligible → existing receipt lifecycle → coordinator, same preview. Not eligible → same block on review screen; receipt stays in review (not lost, no orphan file — UX/domain to define); same actions.
8. **Recurring**: "Repeat" hidden while a non-home currency is selected; shown again on switch back; if already on and user switches to foreign, must not silently carry over — UX defines fail-closed.
9. **BGN/RUB**: not offered for new entries (like HRK); existing BGN rows display/convert (`CurrencyConverter.kt:262`, full catalog); BGN-home user can add home entries (no rate needed); every foreign entry blocked (download yields no rates, `CurrencyRatesRepositoryImpl.kt:80-82`); "switch home to EUR" prompt **not in this feature** (Dependencies).

## Must-have scope — increment 1
1. **Currency control** next to amount: `SupportedCurrency.activeCatalog` minus no-rate-source currencies (BGN/RUB inactive for new selection like HRK); home always selectable even if inactive; defaults to home every open; no last-used memory, no new storage.
2. **One eligibility rule, single owner**, used by preview and save: latest ECB rate with date ≤ expense's local calendar date **and** ≤ 7 calendar days older; ECB `validDate` (UTC midnight, `CurrencyRatesRepositoryImpl.kt:156`) read as calendar date; expense instant → device-local date; > 7 days or none → not eligible.
3. **Converter contract exposes `validDate`** (`ConversionResult.timestamp` is download time, `CurrencyConverter.kt:290, 314`).
4. **Preview**: converted amount + ECB date; hidden for home; never logs typed amount.
5. **Save block on existing create path**: request-level flag on `CreateExpenseRequest` (default off), set only by add-expense manual save and user-confirmed receipt-review save. With flag on, TLC applies the same rule and returns a typed refusal (no row, no side effects) — closes preview→save races. Not a new write path. All other callers (notifications, imports, bank statements, CSV/JSON, pending-review approval) unchanged; their unconverted rows → money-correctness-1.0.x. Checked now: `CreateExpenseRequest` (`A/domain/transaction/CreateExpenseRequest.kt:74-94`) data class with defaulted fields incl. `isManualEntry` (`:87`); reuse vs dedicated flag = domain-modeler (receipt review isn't manual entry).
6. **Per-currency decimals, one source** for parser, validator, formatter, preview; excess decimals rejected, never rounded.
7. **Rounding**: preview rounds converted amount to home decimals with the **same** function as the stored snapshot; stored base follows money-correctness-1.0.x's single snapshot rounding rule (today unrounded, `CurrencyConverter.kt:287`); this increment must not round manual rows on its own. If money-correctness hasn't landed: preview rounds for display only; acceptance = preview text equals list text for that row.
8. **Max amount per currency, one owner**: remove duplicate (`AddExpenseViewModel.kt:297`, `ManualExpenseRepository.kt:114`, raw units); VM delegates; rec: cap independent of rate availability.
9. **Foreign-currency recurring toggle hidden.**
10. **Receipt-review currency control** — rec **in scope as its own slice**: block now applies there and today's private picker is a hard-coded 7-currency list (`ReceiptScanScreen.kt:1389`) → replace with shared active-catalog control + same preview/block UI. Files also touched by nl-quick-add (`workflows/active/nl-quick-add-plan.md:48`) and ai-fixes (`docs/features/ai-fixes/brief-v1.md:73`) → single writer, fixed order.
11. **Diagnostics**: bounded reason codes only (block reason none/stale/unsupported); no amount, currency, merchant, date in logs.

## Increment 1b — ECB historical rates (frame only)
Optional user-started download of recent ECB history (e.g. 90-day window) behind the **same** R23 setting (e.g. from the block message when setting on); never automatic at save. Only fix for post-trip, first use and example 4 consistent with analytics' read-time as-of conversion; replaces most of the case for a typed rate. Endpoint: same ECB host; third-party docs list `/stats/eurofxref/eurofxref-hist-90d.xml` (unconfirmed by ECB). Privacy review: larger payload; public data; fetch timing slightly reveals a backdated entry attempt. Storage: refresh stores every cross pair (`CurrencyRatesRepositoryImpl.kt:84-98`) → ~30² pairs × ~64 business days = tens of thousands of rows → consider cleanup / EUR legs only. Dates older than the window stay blocked.

## Later increments
2 typed manual rate (must not reuse `CurrencyConverter.storeRate(source="manual")`, `:603-625`, which writes the shared table; provenance + analytics consistency; schema likely); 3 edit currency/amount/date via coordinator CURRENCY update with same block; 4 sticky trip currency with expiry; 5 recover unconverted rows (CURR-6/CURR-1, `docs/development/FUTURE-WORK.md:139-141`); foreign-currency recurring after R21 V-C4 follow-up + matching design; nl-quick-add inc 1b maps typed markers into this control.

## Non-goals (with pointers)
- Old increment 0 → `money-correctness-1.0.x`: unconverted marker for every source; stop logging currency + amount at `TransactionLifecycleCoordinator.kt:572-578`; home-aware non-comparable sort/filter (Kotlin + SQL, explicit NULL ordering, legacy `baseCurrency='EUR'` matrix); single snapshot rounding rule; pin "EUR" default. Also there: FinancialRescue invented rate (`FinancialRescueCoordinator.kt:418-420`), CURR-6, deprecated raw SUMs, budget F-4.
- Typed rate, editing, trip currency, backfill → later.
- No block on background/import sources or pending-review approval (`ReviewScreen.kt:1749-1758` free-text 3-letter currency field noted for money-correctness/later).
- No currency guessing; no silent latest-rate fallback; no automatic fetch at save; no auto-enabling the download setting.
- No new write path; no analytics/dashboard change; no cloud AI; no BGN peg constant.

## Existing functionality reused / overlapped
- `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:529-585` — converts at save; single legal write path; refusal added behind the flag.
- `A/domain/transaction/CreateExpenseRequest.kt:74-94` — carries the flag.
- `A/domain/currency/CurrencyConverter.kt:256-320` — as-of (`ExchangeRateDao.kt:62`, no age limit); contract gains `validDate`; `:733-734` candidate single decimals source.
- `A/data/repository/CurrencyRatesRepositoryImpl.kt` — ECB daily XML only; `validDate` UTC midnight (`:156`); BGN/RUB home → `HOME_CURRENCY_QUOTE_UNAVAILABLE` (`:80-82`).
- `A/ui/screens/currency/CurrencyManagementViewModel.kt:238` `refreshRates()` + `CurrencyRatesRepository.refresh(homeCurrency)` — reuse for "Refresh rates"; no new fetch logic.
- `A/ui/screens/receiptscan/ReceiptScanScreen.kt:1385-1389` — private hard-coded picker to replace.
- **Loan-installments S11 (approved, option A)** — `workflows/active/loan-installments-plan.md:452-467, 513-520`; handoff `:118`: adds an EUR/USD segmented control on add-expense while "Pay in installments" is on, with its own "FX unavailable → keep form" refusal (D-8). This feature lands after S11 and must converge to one control + one rule (N2, D9).
- `RecurringExpenseRepository.kt:116-118` — recurring already via `RecurringRuleLifecycleCoordinator`; TODO at `ManualExpenseRepository.kt:185` stale (housekeeping).
- Duplicate detection compares currency (`LEGAL_PATHS.md:80-89`); `AnalyticsCurrencyNormalizer` unchanged.

## Market patterns (from v1 sources)
YNAB (no in-budget conversion) — [multiple currencies](https://support.ynab.com/en_us/using-multiple-currencies-in-ynab-a-guide-SyBF6PHno.md), [digital nomad](https://www.ynab.com/blog/the-digital-nomads-guide-to-budgeting-in-different-currencies); Monarch (never converts; misleading mixed totals) — [international](https://help.monarch.com/hc/en-us/articles/360048393552-International-Accounts-and-Currency); Accountable (currency inside amount field) — [link](https://help.accountable.eu/en/articles/10304940-how-to-add-expenses-in-other-currencies); Zoho Expense (dated custom rate) — [link](https://www.zoho.com/us/expense/help/setting-up-organization/currencies/); ECB removed BGN 2 Jan 2026, no RUB since 1 Mar 2022 — [ECB](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.ro.html), [S&P Platts](https://www.spglobal.com/energy/en/pricing-benchmarks/our-methodology/subscriber-notes/010226-platts-discontinues-the-ecb-bulglev-per-euro-assessment); ECB daily/90-day/full-history files (third-party, confirm before 1b) — [EOS Solutions](https://docs.eos-solutions.it/en/docs/apps-func/historical-ecb-exchange-rates.html), [Apify 90d](https://apify.com/parseforge/ecb-fx-rates-scraper/examples/90d-ecb-euro-fx-rates.md).

## Privacy & permissions
- Inc 1: **low** — no new network/permission/cloud AI/content; "Refresh rates" reuses existing public ECB GET only when R23 setting on and user taps; block UI points to setting, never enables; no amount/currency/merchant logged; no new exported field.
- Inc 1b: **low–medium** — bigger public fetch + storage growth; own privacy review.

## Risks
1. Day-one usability rests on R23 (default off / no periodic refresh → frequent blocks under 7-day rule).
2. Inc 1 alone leaves catch-up blocked (refresh = latest only) → N1.
3. Two currency controls on one sheet (loan S11) → N2, D9.
4. Timezone: defined zone for local date (device now vs at entry); travel shifts dates → D2.
5. Preview vs stored value disagree if rounding rule not landed / differs.
6. Block bypass: coordinator re-check closes races; unflagged callers not blocked by design.
7. Receipt review: blocked save must not lose the receipt or orphan files.
8. File contention: AddExpense (R16, nl-quick-add inc 1, loan S11); ReceiptScan (nl-quick-add, ai-fixes); coordinator shared with loan → strict single writer.
9. BGN/RUB home users see a never-producing setting until the release item lands.

## Success signal (no content telemetry)
Tests: preview eligibility == coordinator eligibility; 7- vs 8-day boundary; weekend/holiday gaps; Greek near-midnight both directions; first-download date; zero-decimal end to end; per-currency cap (1,000,000 KRW allowed); blocked save writes no row/side effect; unflagged callers unchanged; receipt review blocked → receipt preserved; no amount/currency in logs. On-device bounded counter `MANUAL_SAVE_BLOCKED_NO_RATE` (sub-reason none/stale/unsupported). User sees original + converted + ECB rate date.

## Owner questions (new from reshape)
- **N1. Ship increment 1 without 1b?** Rec: only if R23 part 1 includes a periodic rate refresh (then a ≤7-day rate exists for every recent date; only first use / long gaps block). Otherwise ship 1 + 1b together (refresh can't unblock past dates).
- **N2. Merge loan S11's installment-only EUR/USD control into the general control when this lands?** Rec: yes — one control on the sheet; while "Pay in installments" is on, limit the list to installment-supported currencies; `declareWithNewExpense` uses the same eligibility rule + refusal text. Changes S11's approved UI after it ships.

## Domain questions for domain-modeler
- D1 Single owner of the eligibility rule (preview + coordinator under flag); typed refusal (no row/side effects; lifecycle event?); reuse `isManualEntry` vs dedicated "user present, require eligible rate" flag.
- D2 Calendar dates: zone for expense local date (device now vs at entry); ECB `validDate` as UTC calendar date; future-dated expenses; via-EUR two-leg path uses older leg's date for age.
- D3 Currencies with no rate source: which `activeCatalog` currencies are missing from ECB list; "inactive for new selection" for a home currency (BGN home); OCR proposing an inactive currency on receipt review (user must pick, no silent swap).
- D4 Decimals + caps: single source (Java digits vs `SupportedCurrency` table; HUF/ISK practical vs ISO); parsing after R16; per-currency cap values + owner.
- D5 Rounding: preview = money-correctness snapshot rounding (mode, scale); interim if not landed.
- D6 Age limit only for flagged saves; unflagged + analytics keep any-age as-of — confirm consistency; imports adopting the limit → money-correctness.
- D7 Receipt-review blocked save: receipt state, file retention, retry via receipt lifecycle services.
- D8 Rate cleanup/retention vs 7-day rule and 1b volume (cleanup callers UNVERIFIED).
- D9 Loan S11 convergence: does `declareWithNewExpense` create via coordinator with the same flag; is its D-8 refusal the same rule?
- D10 Duplicates: window when the same purchase later arrives as a notification already in EUR (unchanged rule; confirm).

## Dependencies and sequencing
- **R23 part 1 — hard.** Release questions (routing to release track pending owner's explicit "send to release"): RQ1 default state of the new setting; RQ2 periodic refresh? (decides N1); RQ3 BGN/RUB home → clear bounded error + stop offering as home. Rec placement of "BGN-home → switch to EUR" prompt: inside R23 part 1 (same root cause, `CurrencyRatesRepositoryImpl.kt:80-82`, ledger `:639`); else money-correctness-1.0.x; not this feature.
- **R16 — hard** (comma decimals land in 1.0 first).
- **R21 V-C4 — soft** (foreign recurring from manual entry as future consumer; toggle hidden in inc 1).
- **money-correctness-1.0.x — soft, preferably first** (marker, log fix, snapshot rounding).
- AddExpense queue: nl-quick-add inc 1 → loan S11 → this (never parallel; single writer) → nl-quick-add inc 1b. Receipt-review slice after nl-quick-add + ai-fixes ReceiptScan changes; single writer. Owner ordering: ai-fixes first after 1.0.

## Schema flag
Inc 1: none expected (currency field + `validDate` exist; flag in-memory; cap/decimals code rules). Inc 1b: probably none (same table) — confirm volume/cleanup. Later inc 2: schema likely.
