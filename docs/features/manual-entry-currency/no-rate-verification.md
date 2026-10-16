# Verification: foreign-currency expense with no exchange rate (pre-existing behaviour)

Author: debugger (read-only, independent). Persisted by the orchestrator. Date 2026-10-16. Requested by owner ("yes verify the bug").
Root `A` = `app/src/main/java/com/yourname/expensetracker`. Nothing edited or run.

## Verdict: PARTIALLY CONFIRMED — already KNOWN (P3, pre-existing, deferred to 1.0.x)
The critic's "counted as home units in totals" is OVERSTATED. The raw fallback affects only the Transactions list amount sort and amount min/max filter. Dashboard, analytics, forecast, budgets and header totals do not use it.

## Evidence
- Write: no-rate branch `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:554-578` leaves defaults (`A/data/database/entity/Expense.kt:146-148`: `baseAmount=0.0`, `baseCurrency="EUR"`, `exchangeRateUsed=0.0`); only `Timber.w` (logs currency + amount), no diagnostic/marker. Home-currency-failed branch (:543-553) sets `baseCurrency=""`; update path (:1118-1125) clears to 0.0/""/0.0. Comment :536 states the raw fallback is intentional.
- Readers using raw fallback (Transactions only): `Expense.normalizedEffectiveAmount` (:176-185, fallback :179) → `TransactionsViewModel.kt:292-293` (in-memory sort, non-ALL tabs), `:900` (min/max filter), `TransactionsScreen.kt:535`; SQL `SortOrder.AMOUNT_ASC/DESC` ELSE branch (`A/data/repository/ExpenseRepository.kt:62,71`, ALL tab).
- Not affected: date-header totals guarded (`TransactionsScreen.kt:526-540`: single-currency sums in own currency; mixed hidden unless all rows converted); analytics `AnalyticsCurrencyNormalizer` (:131-168) re-converts at read time, excludes on failure with MISSING_EXCHANGE_RATE warning; no ExpenseDao query uses `baseAmount`; budgets use own conversion (`BudgetRepository.kt:536-573`; separate known F-4 P3); exports show `baseAmount` 0 next to raw amount/currency (`ExpenseExportMapper.kt:45`) — misleading column only.
- Separate older family: deprecated raw DAO SUM queries (`getTotalSpentFlow` etc.) still called from SharedBudgetManager, SpendingChallengeManager, BudgetRepository, ExpenseRepository (`docs/ci/DEPRECATION_ESCALATIONS.md:26-37`) — not caused by this fallback.
- Legacy vs no-rate rows indistinguishable (migration `AppDatabase.kt:6105` default 0.0; both `baseAmount=0, baseCurrency="EUR"`); only home-currency-failed rows distinguishable (`""`).

## Entry paths that can create foreign-currency expenses
Notification (`NotificationProcessingPipeline.kt:970,1110`), receipt/OCR (`ReceiptLifecycleCoordinator.kt:600,1049-1053`), bank statement (`BankStatementLifecycleProcessor.kt:324-346,603-836`), CSV/JSON import (`CsvExpenseImporter.kt:239`), recurring (inherits rule currency, `ManualExpenseRepository.kt:182`), backup restore (not traced). Manual: home only (`ManualExpenseRepository.kt:89`).
- Adjacent: `FinancialRescueCoordinator.kt:418-420` fabricates `baseAmount=amount, exchangeRateUsed=1.0` for all rows incl. foreign (rare recovery tool).

## Likelihood
High for users with foreign notifications/receipts: `CurrencyRatesRepositoryImpl.refresh` (:46-52) is gated on `PrivacyCapability.CLOUD_AI_GENERAL` → with cloud AI off (default) no rates are fetched (ledger HZ01b2-F1, CONFIRMED P2, "no rate worker"). Release batch R23 part 1 ("exchange-rate download gets its own network setting") targets this.

## Existing adjudication
- `docs/releases/release-1.0-assurance/release-1.0-phase3-4-findings-ledger.md:231-232, 244-245`: "Offline create: CONFIRMED P3 pre-existing … sort/filter use raw foreign amount; headers hidden; analytics excludes then recovers."
- Related F-1 / CURR-6 (home-currency switch breaks amount sort/filter) P2 documented deferral (ledger :239; `release-1.0-phase3-4-consolidated.md:44`; `docs/development/FUTURE-WORK.md:141`); SRH-7 (`FUTURE-WORK.md:321`).
- Falls under "~50 P3 items … defer to 1.0.x" (`release-1.0-phase3-4-consolidated.md:71-73`); outside the Release 1.0 minimum set (:76-84).
- NEW-P2-007 (stale baseAmount cleared on conversion failure) fixed and pinned.

## Tests
`TransactionLifecycleCoordinatorTest.kt:500-583` pins only the home-currency-Failed sentinel; `:307-321` (converter throws) does not assert resulting base fields → the "EUR" default on the no-rate path is unpinned. Update path pinned (`TransactionLifecycleCoordinatorUpdateTest.kt:172-211, 752-780`). No tests for raw fallback with foreign currency, SQL sort ELSE branch, or amount filter.

## Severity
P3, pre-existing, not a regression, outside Release 1.0 minimum. User impact: a no-rate foreign row sorts/filters as if its raw number were home units (20 USD sorts as 20 EUR; passes "≥ 15"); display, per-currency totals, dashboard unaffected; analytics excludes then recovers once a rate exists. Becomes more important if a manual currency control ships (frequency up) → Q-D4.

## Fix direction (no code) — T3 money, reviewer-strict
- Owner: TLC create/update conversion snapshot (single legal path) + `Expense.normalizedEffectiveAmount` / `SortOrder` readers; aggregates via `MoneyAggregate` / `AnalyticsCurrencyNormalizer` per LEGAL_PATHS Money.
- Write: no-rate branch (TLC:572-578) marks row unconverted with the same sentinel as other failure branches (`baseCurrency=""`) + bounded diagnostic reason code (no currency/amount log).
- Read: unconverted foreign row = "no comparable value" in sort/filter (ordered last / excluded from min/max) or pass home currency into readers — UX decision (Q-D4).
- Recovery: backfill `baseAmount` when a rate arrives or compute at read time (CURR-6/CURR-1 design, `FUTURE-WORK.md:139-141`; migration decision).
- Separate: fix fabricated snapshot at `FinancialRescueCoordinator.kt:418-420`.
- Route to `manual-entry-currency` or a "money correctness 1.0.1" slice — NOT nl-quick-add.

## Residual (not verified)
Backup-restore path; recurring-rule currency inheritance and CSV/JSON importers beyond currency lines; Transactions tests; reachability of deprecated raw SUM callers in release UI.
