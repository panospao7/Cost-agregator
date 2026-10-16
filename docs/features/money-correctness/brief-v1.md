# Feature brief: money-correctness — Money correctness 1.0.x (unconverted rows, comparable-amount rule, snapshot rounding)

Author: feature-strategist (stage 1). Persisted by the orchestrator. Date 2026-10-16. Docs only, post-1.0, **T3** (money).
Root `A` = `app/src/main/java/com/yourname/expensetracker`. Tags: **[V]** re-read this session; **[P]** prior agent verified (doc cited); **[U]** unverified.

## Problem
With no exchange rate, a foreign-currency transaction is saved with no home value and nothing on the row says so. Transactions amount sort and min/max filter treat its raw number as home currency. After a home-currency switch, older rows are compared in the old currency as if it were the new one. The stored converted value is never rounded, so stored, displayed and summed amounts can differ by a cent.
- **No rate:** EUR home, USD 20.00 notification, no rate → "Amount high→low" places it between €25 and €15 as if €20; "min €15" includes it. [P] `no-rate-verification.md`
- **Home switch:** EUR home, EUR 100 recorded; switch to USD; USD 95 recorded → high→low lists EUR 100 first; "min 95" keeps both; dashboard correctly shows USD 195-equivalent → screens disagree. [P] ledger `:239-243` (deciding test)
- **Rounding:** USD 13.50 × 0.75 stored 10.125; displays €10.12 (HALF_EVEN formatter); a HALF_UP MEC preview would show €10.13. [V] `CurrencyConverter.kt:287`; [P] MEC domain-spec F14
- **Export:** unconverted rows export `baseAmount 0` with a fabricated `EUR` home column. [V] `ExpenseExportMapper.kt:44-47`

## Target users / situations
- Foreign-currency transactions via notifications, receipt/OCR, bank statements, CSV/JSON, recurring rules, installment auto-record. [P]
- **Likely, not edge:** rate refresh gated on `PrivacyCapability.CLOUD_AI_GENERAL` → cloud AI off (default) = no rates ever [P] ledger HZ01b2-F1 `:247-249`; release R23 part 1 targets this (status [U]).
- Users who changed home currency (CURR-6; reachable from currency tile, no warning). [P] ledger `:242`
- Non-EUR-home users with older rows carrying migration default `baseCurrency='EUR'`. [V] `AppDatabase.kt:6105-6106`
- Rare Financial Rescue users.

## How it should behave (domain-modeler finalises)
One **comparable home value** wherever rows are sorted/filtered by amount against current home H:
1. `currency == H` → row's ownership-adjusted raw amount, whatever the snapshot says.
2. Else valid snapshot (`baseAmount > 0 && baseCurrency == H`) → snapshot.
3. Else **no comparable value** — never treated as H units.

**Removes the MEC critique §3.1 blocker without a migration:** today's readers test only `baseAmount > 0`, never H or `baseCurrency` [V] `Expense.kt:176-185`, `ExpenseRepository.kt:55-72`. Rule 1 keeps older same-currency rows comparable for any home; legacy and no-rate rows needn't be told apart for reading (both "foreign and unconverted").

| Case | Stored today | Today's sort/filter | Desired (inc 1) |
|---|---|---|---|
| (b) EUR home, no-rate USD 20.00 | `0.0 / 'EUR' / 0.0` [V] TLC `:572-578` | sorts as 20; passes "min 15" | `0.0 / "" / 0.0` + bounded reason code; sorts last both directions; excluded from min/max with visible count; still shows "$20.00"; date header stays hidden |
| (a) USD-home legacy USD 50 | `0.0 / 'EUR' / 0.0` | raw 50 (correct by luck) | rule 1 → 50; no regression |
| (a') USD-home legacy EUR 40 never converted | `0.0 / 'EUR' / 0.0` | sorts as 40 "USD" (wrong) | no comparable value |
| (c) home unresolved at create | `0.0 / "" / 0.0` [V] TLC `:543-553` | raw fallback | rule 1 if `currency == H`, else none |
| (d) switch EUR→USD: EUR 100 (snap 100 EUR); USD 95 after; USD 30 before (snap 27.78 EUR) | as shown | EUR100 first; min-95 keeps both; USD 30 sorts 27.78 | USD95 = 95, USD30 = 30 (rule 1); EUR100 none until inc 2 re-snapshot |
| (e) Rescue-restored foreign row | `amount / <foreign> / 1.0` [V] `FinancialRescueCoordinator.kt:418-420` | sorts raw (fabricated) | no fabricated snapshot; reads unconverted |

**Rounding (one rule, all sources, at TLC snapshot on create and update):** rec HALF_EVEN to home minor units (matches formatter + allocator, [P] F14). USD 13.50 × 0.75 = 10.125 → €10.12; display, MEC preview (INV-15) and sums agree (two rows sum 20.24). JPY home: USD 20 × 151.237 = 3024.74 → ¥3025. `exchangeRateUsed` stays unrounded. Existing rows not rewritten. **Edge:** tiny positive amount rounding to 0.00 collides with "`baseAmount <= 0` = unconverted" (domain question).

## Must-have scope (increment 1, no schema change)
- **M1 Write marker:** TLC no-rate branch (`:572-578`) writes the same sentinel as other failure branches (`baseCurrency=""`, as `:543-553` and update `:1116-1125`); one bounded reason code via the controlled-diagnostic path; remove the currency+amount `Timber.w` (`:573-577`, RF-5); tests pin base fields on all four snapshot outcomes (today only home-failed pinned, [P] `TransactionLifecycleCoordinatorTest.kt:307-321, 500-583`).
- **M2 One comparable-value rule, single owner**, used by SQL amount sort (ALL tab), in-memory sort (`TransactionsViewModel.kt:292-293`), min/max filter (`:894-903`); non-comparable rows explicitly last both directions (no reliance on SQLite NULL ordering); filter UI shows excluded count.
- **M3 Snapshot rounding** at TLC snapshot (create + update, all sources) with boundary tests (2-dp, 0-dp, half cases, tiny amounts).
- **M4 Legacy/home-switch matrix (a)–(e)** pinned by tests with legacy fixtures (`'EUR'` default rows for EUR and non-EUR homes), incl. the ledger's deciding test.
- **M5 No fabricated snapshots:** Rescue import stops writing `baseAmount=amount, baseCurrency=<row currency>, rate=1.0`; preserves a valid snapshot or writes the sentinel.

## Later increments
- **Inc 2 recovery + alignment:** re-snapshot unconverted rows when a rate arrives and old-home rows after a switch (CURR-6) via one legal path; analytics alignment (OD-3); F-4 (latest-basis budget conversion returns raw on failure, `BudgetRepository`) — same "never raw-as-home" invariant; date basis (instant vs local calendar date; MEC critique §3.3).
- **Inc 3 export honesty:** unconverted rows export empty/null base fields, not `0` + fabricated `EUR`; `homeCurrency` column stops sourcing from `baseCurrency` (`ExpenseExportMapper.kt:44`); SRH-7 (NL search amount filters compare raw) [P] `FUTURE-WORK.md:321`.

## Non-goals / routed elsewhere
- Deprecated raw SUM DAO queries (`SharedBudgetManager.kt:48,54`, `SpendingChallengeManager.kt:216-226`, `ExpenseRepository.kt:770,829-830` [V]; `DEPRECATION_ESCALATIONS.md:26-41`) — separate family (P5-P1-5 / CURR-18), own item; release-UI reachability [U].
- ReviewScreen free-text currency (`ReviewScreen.kt:1749-1758` [V]) → MEC (c)/review owner; unsupported codes already fall into the unconverted path (`SupportedCurrency.fromCode` → null, `CurrencyConverter.kt:262`), so M1/M2 make them safe.
- RF-1 (`CREATE_ATTEMPTED` reason text) → MEC A2 (G2-11).
- Not in scope: accounting/display currency split (CURR-6 "Large"); historical ECB rates (MEC 1b); manual per-expense rate; date headers (already guarded, [P] ledger `:240-241`); dashboard/forecast; rounding existing rows.

## Existing functionality reused / overlapped
- `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:529-585` (create snapshot), `:1099-1126` (update snapshot) — single legal snapshot writer.
- `A/data/database/entity/Expense.kt:146-148, 176-185` — defaults + `normalizedEffectiveAmount`.
- `A/data/repository/ExpenseRepository.kt:49-73` — `SortOrder` SQL CASE.
- `A/ui/screens/transactions/TransactionsViewModel.kt:292-293, 894-903`; `TransactionsScreen.kt:522-541` (header guard, reused).
- `A/domain/analytics/AnalyticsCurrencyNormalizer.kt` — read-time conversion + MISSING_EXCHANGE_RATE exclusion (model: unconverted = excluded, not raw).
- `A/domain/currency/CurrencyConverter.kt:256-292` `convertAsOf`; `CurrencyFormatter` / `SourceCurrencyAllocation` HALF_EVEN digits ([P] F14).
- `A/data/repository/CurrencySettingsRepositoryImpl.kt:49-62`, `CurrencyManagementViewModel.kt:148-182` — home-switch hook ("callers MUST re-normalize", no caller) → inc 2 hook.
- MEC flagged branch (domain-spec INV-7/INV-8, AE-21) coexists: flagged saves never produce unconverted rows; unflagged rows get M1; INV-7 defers to this snapshot rounding.
- `docs/architecture/LEGAL_PATHS.md:534-552` (Money/Currency) — add "comparable home value" rule.

## Market patterns (observed)
- Store rate with each expense at its date's rate so old reports don't shift; some apps re-convert at today's rate (past totals change) — [Tanda maker post](https://www.producthunt.com/p/tanda-2/the-multi-currency-mistake-i-wanted-tanda-to-avoid) (developer account).
- Lunch Money: historic rate for transaction date, closest-day fallback; primary currency changeable anytime (re-conversion of past rows not stated); fixed a bug where past-dated non-primary transactions used latest rate — [docs](https://support.lunchmoney.app/settings/multicurrency), [changelog](https://feedback.lunchmoney.app/changelog/174).
- Toshl: historical rates back to 1999; each entry at its own day's rate — [Toshl blog](https://toshl.com/blog/wp-json/wp/v2/posts/1361).
- Trackers differ: rate-at-entry vs current vs custom (low confidence) — [Finny](https://getfinny.app/blog/best-multi-currency-expense-tracking-apps-2026).
- Not observed: how any app orders/filters rows with no rate → "last, with a count" is our design.

## Privacy & permissions: low, net improvement
No network, cloud AI, permissions, or new exported data. M1 removes a currency+amount log line (release Timber stripping [U]) for a bounded reason code. Filter count is a number only. Inc 2 re-snapshot uses local rates only (worker → `WorkerExecutionGuard` + barriers). Inc 3 changes export value meaning, no new fields.

## Risks
- **Shared TLC block:** MEC A2 flagged branch in `:529-585`; loan S3 edits TLC/`ExpenseDao`; loan S3 also adds `ExpenseSource.INSTALLMENT_PLAN` to the exhaustive `when (request.source)` `:508-526` ([P] impact-map §9, corrected by MEC slice D review). Serialise per global order; under G2-2 rebase onto MEC A2 if it lands first.
- **Perceived data loss:** rows drop to bottom / out of amount filters → visible count (UX).
- **Export/backup consumers of `baseCurrency`:** importers never read base columns ([V]) → CSV/JSON round-trip of `""` safe; export mapper fills `homeCurrency` from `baseCurrency` → M1 empties that column for no-rate rows (Q5); backup restore preserving `""` [U] (likely raw DB copy).
- **SQL sort needs H as a parameter** (today static enum string) — ordering + performance on paged ALL tab.
- Tests/fixtures relying on `'EUR'` default may break (intentional).
- Inc 2: bulk lifecycle write needs one legal path; rows from past schema versions [U].

## Success signal (no content telemetry)
Ledger deciding test passes for sort and filter; matrix (a)–(e) pinned; every TLC snapshot outcome asserts all three base fields; no log/diagnostic carries currency or amount (privacy-guardian); bounded reason-code count visible in existing diagnostics surface; spot check display == stored == preview at rounding boundaries.

## Owner questions (with recommended answers)
1. Placement of rows with no comparable value? **Rec:** last in both sort directions; excluded from min/max results with a visible "N transactions without a <home> value" count; never hidden from the unfiltered list.
2. Home-switch behaviour in inc 1? **Rec:** old-home snapshots become "no comparable value" now (honest); inc 2 re-snapshots. (Read-time conversion inside Transactions not recommended.)
3. Rounding mode? **Rec:** HALF_EVEN to home minor units (formatter/allocator), every new/updated snapshot; existing rows untouched. Alt: HALF_UP (AddExpense input rounding, `AddExpenseViewModel.kt:377` [P]).
4. Analytics alignment direction (OD-3, inc 2)? **Rec:** snapshot-authoritative — analytics uses stored snapshot when valid for current home; read-time conversion only for unconverted rows.
5. Export of unconverted rows in inc 1? **Rec:** accept `homeCurrency`/`baseCurrency` cells empty for no-rate rows (honest, no format change); null `baseAmount` + `homeCurrency` source fix in inc 3.
6. M5 (Rescue fabrication) in inc 1? **Rec:** yes — small, same invariant (NEW-P2-016), safe with M2.

## Domain questions for the domain-modeler
- Exact comparable-value predicate incl. ownership (`isNotMine` → 0; `myShareAmount × exchangeRateUsed`; percentage share of `baseAmount`); rounding of those; transfer/deposit signs.
- Home resolution `Failed` at read time: disable amount sort/filter, or rule 1 on nothing? Fail closed.
- Sentinel collision: positive amount whose rounded snapshot is 0.00 — floor to smallest unit, or rely on `baseCurrency == H` not `baseAmount > 0`?
- Rounding on update path, identity rows (`currency == H`), 0-dp currencies; helper home (`domain/core/money` vs MEC `CurrencyAmountPolicy`).
- One owner across SQL and in-memory readers (no drift).
- Inc 2 re-snapshot legal path (TLC update vs new bulk op); triggers (rate refresh, home switch); audit events per row; worker vs inline; barriers.
- Date basis: `convertAsOf` instant vs local calendar date (OD-3; MEC critique §3.3 UTC `validDate`).
- `AnomalyAlert` also has `baseAmount`/`baseCurrency DEFAULT 'EUR'` (`AppDatabase.kt:6115-6116` [V]); usage [U]; in scope?
- [U] Mixed-currency date header sums `normalizedEffectiveAmount` unsigned (`TransactionsScreen.kt:535`) vs single-currency `signedEffectiveAmount()` (`:528`) — can deposits/income appear in mixed groups?
- Recurring + installment auto-record no-rate rows (MEC AE-29) get M1 — confirm no caller depends on the `'EUR'` default.

## Schema flag
**No migration/backfill for any inc-1 item** (`""` fits the column; read rule uses runtime H; rounding only new writes; legacy rows handled by rule 1). Inc 2 recovery = data re-snapshot, not schema (avoid doing it as a migration). Schema only if the owner later wants persisted rate provenance/date (CURR-14, MEC "rate date not persisted") or an explicit unconverted flag — not needed for correctness.

## Recommendation
**Pursue.** Inc 1 bounded (TLC snapshot block, Transactions readers, Rescue import), no schema change, improves privacy, fixes a likely user-visible P2/P3 money defect. Recovery, analytics alignment, F-4, export semantics → later increments; raw-SUM family → separate item. T3, reviewer-strict.
