# Feature brief: money-correctness — Money correctness 1.0.x (v2, reshaped)

Author: feature-strategist (stage 1, single reshape pass). Persisted by the orchestrator. Date 2026-10-16. Docs only, post-1.0, **T3** (money).
Base `brief-v1.md`; `critique.md` fact corrections and Gate 1 answers (`workflows/active/money-correctness-handoff.md` §Human decisions) binding.
**Increment 1 split:** 1a read side (right after ai-fixes), 1b write side (just before MEC A2). No schema change in either. **No new owner questions.**
Root `A` = `app/src/main/java/com/yourname/expensetracker`. Tags: [V] re-read this pass; [C] verified by critique; [P] earlier agent (doc cited); [U] unverified.

## Problem
Transactions sorts/filters by amount using numbers not in the current home currency (H): no-rate foreign rows compared by raw foreign number; on the default ALL tab, min/max uses the raw amount for **every** foreign row, even converted ones (time-range tabs use the converted value → tabs disagree); after a home switch rows are compared in the old currency as if the new; stored converted values never rounded; no-rate rows export a made-up home currency (`EUR`).
- **No rate:** EUR home, USD 20.00, no rate → high→low sorts as €20; "min €15" includes it. [P] `no-rate-verification.md`
- **ALL tab, converted row:** EUR home, USD 20.00 at 0.92 (snapshot €18.40) → on ALL "min €19" includes it (SQL compares raw 20) [C] `ExpenseRepository.kt:356-364`; ALL skips `applyAmountConstraints` [C] `TransactionsViewModel.kt:241-245, 826-827`; "This month" correctly excludes it.
- **Home switch:** EUR 100 recorded, switch to USD, USD 95 recorded → high→low EUR 100 first; "min 95" keeps both; ledger deciding test fails ([P] ledger `:239-243`). Switch is one tap, no warning: `HomeCurrencyCard` picker (`CurrencyManagementScreen.kt:178`) and any supported-currency card (`:315`) → `CurrencyManagementViewModel.setHomeCurrency` (`:165-189` [V]); KDoc: amounts "remain in their original currency", re-normalization future work.
- **Rounding:** USD 13.50 × 0.75 stored 10.125 unrounded [C] `CurrencyConverter.kt:287`. Three rules today: `CurrencyFormatter` HALF_EVEN on binary double; `SourceCurrencyAllocation` HALF_EVEN on `BigDecimal.valueOf`; `Money.kt:34` HALF_UP. 1.015 → 1.01 (formatter) vs 1.02 (allocator) [C].
- **Export:** unconverted rows export `baseAmount 0` with made-up `EUR` home column [C] `ExpenseExportMapper.kt:44-47`.

## Target users / situations
Foreign-currency transactions from notifications, receipts/OCR, bank statements, CSV/JSON, recurring, installment auto-record [P]. Likely, not edge: rate refresh gated on `CLOUD_AI_GENERAL` (off by default) → default users get no rates [P] ledger HZ01b2-F1 `:247-249]`; release R23 part 1 targets this ([U]). Users who switched home or tapped a currency card by accident (CURR-6). Non-EUR-home users with legacy `baseCurrency='EUR'` rows [C] `AppDatabase.kt:6105-6107`. Rare Rescue users.

## How it should behave (domain-modeler finalises)
One **comparable home value (CV)**, single owner, wherever Transactions sorts/filters by amount against H:
1. `currency == H` → ownership-adjusted raw amount (`isNotMine` → 0, comparable, not "none").
2. Else valid snapshot (`baseAmount > 0 && baseCurrency == H`) → ownership-adjusted snapshot (shared rows computed at read time, unrounded: `Expense.kt:180-183`, SQL `:57-60`).
3. Else **no CV** — never treated as H units.
- H fails at read time → amount sort/min/max disabled with bounded state, date-sort fallback; never rule 1 against a guessed H (Q6).
- Deterministic order both directions: non-CV last; then CV asc/desc; then `date DESC`; then `id DESC` (shape `(cv IS NULL), cv <dir>, e.date DESC, e.id DESC`).
- H bound as `?`, never interpolated; closed `SortOrder` enum unchanged [C] `ExpenseRepository.kt:387-395`.
- 1a doesn't depend on 1b (today's no-rate rows `0.0/'EUR'` already fail `baseAmount > 0`).

| Case | Stored | Today | Desired |
|---|---|---|---|
| (b) EUR home, no-rate USD 20.00 | `0.0/'EUR'/0.0` (after 1b `0.0/""/0.0`) | sorts as 20; passes "min 15" | 1a: no CV → last both ways, excluded from min/max, counted; still shows "$20.00" |
| (b') ALL, EUR home, USD 20.00 at 0.92 | `18.40/'EUR'/0.92` | ALL: "min €19" includes, "max €19" excludes; This month opposite | rule 2 → 18.40 on every tab |
| (a) USD home, legacy USD 50 | `0.0/'EUR'/0.0` | raw 50 (lucky) | rule 1 → 50; no regression |
| (a') USD home, legacy EUR 40 never converted | `0.0/'EUR'/0.0` | treated as 40 USD (wrong) | no CV |
| (c) home unresolved at create | `0.0/""/0.0` | raw fallback | rule 1 if `currency == H`, else no CV |
| (d) EUR→USD: EUR 100 (snap 100 EUR); USD 30 before (snap 27.78 EUR); USD 95 after | as stored | EUR 100 first; min-95 keeps both; USD 30 sorts 27.78 | warning first; after confirm: USD 95 → 95, USD 30 → 30 (rule 1); EUR 100 no CV, last, counted until inc 2 |
| (e) Rescue-restored foreign row | `amount/<foreign>/1.0` | sorts raw | already no CV under rule 2; M5 stores identity snapshot if `currency == H`, else sentinel |
| (f) paging: 45 non-CV rows, high→low, paged ALL | — | no tiebreaker → repeats/gaps across LIMIT/OFFSET | stable order; no duplicates/gaps; paging resets on H change |

**Home-switch warning (Q2, mandatory, 1a; UX designs):** shown before saving any switch to a different code from both entry points (`CurrencyManagementScreen.kt:178`, `:315`); cancel leaves setting unchanged. Says: existing amounts aren't converted; transactions in other currencies (incl. old-home history) will have no `<new home>` value for amount sorting/min-max until conversion support arrives; they stay in the list with original amount; a count shows how many. No amounts/merchants/content; must not claim values were converted.

**Rounding (1b):** one helper, HALF_EVEN to H minor units via `BigDecimal.valueOf(double)`, at TLC snapshot on create + update, all sources. 10.125 → €10.12; 1.015 → 1.02 (displays 1.02); JPY home USD 20 × 151.237 = 3024.74 → ¥3025; positive rounding to 0 keeps one minor unit (JPY 1 × 0.0061 → €0.01) so `baseAmount > 0` holds. `exchangeRateUsed` unrounded; existing rows not rewritten. "Display == stored == MEC preview" only for unshared rows.

## Must-have scope
### Inc 1a — read side (no TLC edits; right after ai-fixes, rebased on it)
- **M2 widened:** single CV owner used by ALL-tab SQL WHERE min/max (`ExpenseRepository.kt:356-364`), ALL-tab SQL ORDER BY (`:49-73`), in-memory sort/filter on other tabs (`TransactionsViewModel.kt:292-293, 894-903`), `Expense.normalizedEffectiveAmount` (`Expense.kt:176-185`); deterministic tiebreaker; H bound as `?`; paging reset on H change.
- **Excluded count:** real COUNT query with same filters (Q1), "N without a `<home>` value", number only.
- **Assistant isolation (Q4):** `buildExpenseDynamicQueryParts` shared with `getAssistantExpensesFiltered` / `getAssistantExpenseCountFiltered` (`ExpenseRepository.kt:238-301`) ← `ExecuteFinancialQueryUseCase.kt:71,122,411,475` [C] → assistant callers select today's raw semantics via own parameter; 1a changes no assistant WHERE/ORDER BY/count.
- **Read-time H failure (Q6):** disabled + bounded state; date-sort fallback.
- **Home-switch warning (Q2)** on both entry points.
- **M4 read matrix:** (a), (a'), (b), (b'), (c), (d), (e), (f) + `isNotMine` = 0, shared foreign rows, H Failed, H change; legacy `'EUR'`-default fixtures for EUR and non-EUR homes; ledger deciding test on ALL and a time-range tab.
- **M5 Rescue (export/data honesty, corrected):** `RescueExpense` has no base fields [C] `FinancialRescueSnapshot.kt:21-43` → stop making up `amount/<row currency>/1.0` (`FinancialRescueCoordinator.kt:418-420`); write identity snapshot (`amount/H/1.0`) when `currency == H`, else sentinel (`0.0/""/0.0`, as TLC `:543-553`), sentinel if H Failed. No sort/filter effect; keeps exports honest; lets inc 2 find rows.

### Inc 1b — write side (TLC block; just before MEC A2, per G2-2 + MEC INV-7 helper dependency)
- **M1:** TLC no-rate branch (`:572-578`) writes `baseCurrency=""` (base amount + rate 0.0), as other failure branches (`:543-553`, `:1116-1125`); one bounded reason code via controlled-diagnostic path; remove currency+amount `Timber.w` (`:573-577`, RF-5).
- **M3:** single rounding helper as above (Q3). **Owner approved HALF_EVEN as a deliberate deviation from `Money.kt:34` HALF_UP (Gate 1 Q3, 2026-10-16) — satisfies AGENTS.md:193.** MEC preview (INV-7/INV-15) uses the same helper. AddExpense input rounding (`AddExpenseViewModel.kt:369`, HALF_UP) separate, unchanged.
- **Write tests:** all four snapshot outcomes (success, home Failed, no rate, update failure) assert all three base fields; boundaries 2-dp, 0-dp, half cases (10.125, 1.015), positive-to-zero, identity rows.
- **Q5:** export cells for no-rate rows become empty — accepted + release note.

## Later increments
- **Inc 2 recovery + alignment:** re-snapshot unconverted rows on rate arrival and old-home rows after a switch via one legal path (CURR-6 hook `CurrencyManagementViewModel.kt:148-182`); analytics alignment (OD-3, snapshot-authoritative; drift ≤ half a minor unit/row); F-4 (latest-basis budget conversion returns raw on failure); date basis (instant vs local calendar date, MEC critique §3.3); warning can then describe recovery.
- **Inc 3 export honesty:** null/empty `baseAmount` for unconverted rows; `homeCurrency` column not sourced from `baseCurrency` (`ExpenseExportMapper.kt:44`); SRH-7 (NL search amount filters).

## Non-goals / routed elsewhere
Assistant raw min/max → SRH-7 / assistant (ai-fixes) owner; `AnomalyAlert.baseAmount/baseCurrency DEFAULT 'EUR'` (`AppDatabase.kt:6115-6116`, usage [U]) → separate; app-wide rounding inconsistency (`Money.kt` HALF_UP, splits/settlements, formatter on double) → money-policy item (1b fixes only snapshot rounding); mixed-currency date-header signing (`TransactionsScreen.kt:535` unsigned vs `:528` signed) → domain-modeler [U]; raw-SUM DAO family (`SharedBudgetManager.kt:48,54`, `SpendingChallengeManager.kt:216-226`, `ExpenseRepository.kt:770,829-830`; P5-P1-5 / CURR-18) → own item; ReviewScreen free-text currency → MEC (c); RF-1 → MEC A2 (G2-11). Not in scope: accounting/display currency split; historical ECB rates; manual per-expense rate; converting at home switch in inc 1 (rejected by Q2); rewriting existing rows; dashboard/forecast.

## Existing functionality reused / overlapped
- `A/data/repository/ExpenseRepository.kt` `:49-73` SortOrder CASE; `:238-301` assistant entry points; `:356-364` min/max WHERE; `:387-395` args/LIMIT; `@RawQuery` + `SimpleSQLiteQuery(sql, args)` `:227` (no Room codegen change).
- `A/ui/screens/transactions/TransactionsViewModel.kt:241-245, 292-293, 826-827, 894-903`; `TransactionsScreen.kt:522-541` (header guard unchanged).
- `A/data/database/entity/Expense.kt:146-148, 176-185`.
- `A/ui/screens/currency/CurrencyManagementViewModel.kt:165-189`, `CurrencyManagementScreen.kt:178, 315` — only home-switch path; only writer `CurrencySettingsRepositoryImpl.kt:56` [V]; one unrelated dialog today (`ConversionDialog` `:653`).
- `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:529-585, 1099-1126` — single legal snapshot writer (1b).
- `A/domain/analytics/AnalyticsCurrencyNormalizer.kt` — "unconverted = excluded" model, unchanged.
- `A/domain/currency/CurrencyConverter.kt:256-292`; `SourceCurrencyAllocation.kt:35` (`BigDecimal.valueOf` HALF_EVEN pattern).
- `FinancialRescueCoordinator.kt:418-420` (M5); `ExpenseExportMapper.kt:44-47`, `ExportOptionsViewModel.kt:691-692, 756-757` (export cells change in 1b).
- `docs/architecture/LEGAL_PATHS.md:534-552` — add CV rule + snapshot rounding rule.
- MEC flagged branch (INV-7/INV-8, AE-21) coexists and uses the 1b helper.

## Market patterns (observed)
Rate stored with each entry at its date (Toshl, Lunch Money, Tanda maker) vs re-convert at today's rate — [Toshl](https://toshl.com/blog/wp-json/wp/v2/posts/1361), [Lunch Money](https://support.lunchmoney.app/settings/multicurrency), [changelog](https://feedback.lunchmoney.app/changelog/174), [Tanda](https://www.producthunt.com/p/tanda-2/the-multi-currency-mistake-i-wanted-tanda-to-avoid). One app documents a primary-currency switch only relabels (50 USD shows as 50 EUR) — the defect we avoid → warning + "no CV" — [pocketclear](https://pocketclear.app/help/currency.html). Users complain both about silent full conversion and switch-back rate corruption; they expect original amount + currency kept — [Budget Flow #1](https://budgetflow.featurebase.app/p/bugbudget-book-currency-switching-behavior), [#2](https://budgetflow.featurebase.app/p/216-exchange-rate-of-transactions-is-not-reset-correctly-when). Not found: pre-switch warnings or ordering/filtering of no-rate rows anywhere → our design.

## Privacy & permissions: low, net improvement
No network, cloud AI, permissions, new exported fields. 1b removes a currency+amount log line for a bounded reason code. Count and H-failure state are numbers/bounded enums. Warning text has no transaction content. Inc 2 re-snapshot local rates only (worker → `WorkerExecutionGuard` + barriers). Inc 3 changes export value meaning, not fields.

## Risks
- **Rule 1 trusts `currency`**, which defaults to `'EUR'` on several tables (`AppDatabase.kt:356` etc. [C]); a live insert path relying on that default for a non-EUR-home user would lose CV (lucky today) [U] → domain-modeler/Sonnet scout confirms no writer omits `currency`.
- **Negative/refund amounts:** negative `amount` fails `baseAmount > 0` → unconverted [U] → domain-modeler pins sign convention for rule 2, positive-to-zero, deposit/transfer CV.
- **Perceived data loss after a switch** → mandatory warning, visible count, rows stay in unfiltered list.
- **Shared SQL builder:** missing the assistant parameter silently changes answers → tests pin unchanged assistant WHERE/ORDER BY/count; ai-fixes may touch the same use case → 1a rebases after it.
- **SQL performance:** bound-parameter CASE in ORDER BY + extra COUNT on ALL → impact map checks plan on large tables.
- **TLC contention (1b):** MEC A2 flagged branch in `:529-585`; loan S3 adds `ExpenseSource.INSTALLMENT_PLAN` to `:508-526` after 1b; under G2-2 if A2 lands first, 1b rebases and A2 takes the helper.
- **Export consumers:** CSV/JSON `baseCurrency`/`homeCurrency` cells `EUR` → empty (accepted Q5); importers never read base columns [C]; backup restore keeping `""` [U].
- Tests/fixtures depending on the `'EUR'` default will break (intentional).

## Success signal (no content telemetry)
Ledger deciding test passes for sort and filter on ALL and a time-range tab; matrix (a)–(f) pinned incl. paging no duplicates/gaps and reset on H change; assistant query tests unchanged; warning on both entry points, cancel leaves H unchanged; every TLC snapshot outcome asserts all three base fields; rounding boundaries pinned; privacy-guardian finds no currency/amount in logs/diagnostics; bounded reason-code count visible in existing diagnostics surface.

## Owner questions
None new (Gate 1 Q1–Q6 binding, folded in).

## Domain questions for domain-modeler
- Exact CV predicate: `isNotMine` = 0; `myShareAmount × exchangeRateUsed`; percentage share of `baseAmount`; transfer/deposit/refund signs; shared-row CV rounded or not.
- Single CV owner location so SQL and Kotlin can't drift (one spec + parity test).
- Count scope: only with an active amount filter, or also during amount sort.
- Tiebreaker for assistant ordering, or assistant byte-for-byte unchanged (rec unchanged).
- M5 with H Failed at restore; Rescue rows through TLC snapshot path (inc 2)?
- 1b helper location (`domain/core/money` vs MEC `CurrencyAmountPolicy`); identity rows; 0-dp/3-dp currencies; update path.
- Inc 2 re-snapshot legal path (TLC update vs bulk op), triggers, per-row audit events, worker vs inline, barriers; date basis (OD-3, UTC `validDate`).
- Recurring + installment auto-record no-rate rows (MEC AE-29) get M1 — confirm no caller depends on `'EUR'` default.
- The two [U] risks: `currency` default reliance; negative amounts.

## Schema flag
None in 1a or 1b (`""` fits column; H runtime bind parameter; rounding new writes only; legacy rows via rule 1; count is a query). Inc 2 recovery = data re-snapshot, not migration. Schema only if owner later wants persisted rate provenance or an explicit unconverted flag — not needed for correctness.

## Recommendation
**Pursue (reshaped).** 1a (read side: SQL + Transactions + warning + Rescue) closes the critique's three blockers (ALL-tab SQL filter, assistant isolation, paging tiebreaker) without touching the contended TLC block. 1b (write marker + approved HALF_EVEN helper) placed just ahead of MEC A2. T3, reviewer-strict on both.
