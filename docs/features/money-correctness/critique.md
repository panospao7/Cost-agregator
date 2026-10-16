# Critique: money-correctness

Author: feature-critic (independent, read-only, ~22 tool calls). Under review: `brief.md`. Date 2026-10-16. Persisted by the orchestrator.

**Verdict: RESHAPE.** Problem real and likely; comparable-value rule mostly holds. But M2 misses the reader the default ALL tab actually uses for amount filtering; M1/M3 are bundled with M2 though M2 needs neither; the home-switch side effect is understated.

## 1) Fact-check
| Claim | Status | Evidence |
|---|---|---|
| No-rate branch keeps `'EUR'` default and logs currency + amount | HOLDS | `TransactionLifecycleCoordinator.kt:572-578` only `Timber.w(... expense.currency, expense.amount ...)`, no `copy`; defaults `Expense.kt:146-148` |
| Home-failed + update-failure branches write `""` | HOLDS | TLC `:543-548`, `:1116-1125` |
| Readers check only `baseAmount > 0` | HOLDS | `Expense.kt:176-185`, `ExpenseRepository.kt:55-72` |
| **M2 reader list complete** | **WRONG** | On ALL tab min/max is applied **in SQL** on **raw** `EFFECTIVE_AMOUNT_E_SQL` (`ExpenseRepository.kt:356-364`) via `TransactionsViewModel.kt:826-827`; `:241-245` skips `applyAmountConstraints` for ALL → on ALL even **converted** foreign rows are filtered by raw amount. Ledger "min €15 includes USD 20" is wider than the no-rate case. |
| SQL builder Transactions-only | **WRONG / missed** | `buildExpenseDynamicQueryParts` shared with the AI assistant: `getAssistantExpensesFiltered` / `getAssistantExpenseCountFiltered` (`ExpenseRepository.kt:238-301`) ← `ExecuteFinancialQueryUseCase.kt:71,122,411,475` → changing shared min/max or ORDER BY changes assistant answers |
| Sentinel `'EUR'` → `""` affects only exports | HOLDS (mostly) | readers: `TransactionsScreen.kt:532-535` (header already hidden, no change), `ExpenseExportMapper.kt:44-46`, `ExportOptionsViewModel.kt:691-692, 756-757` (CSV/JSON cells → `""`), TLC audit JSON `:2545-2546`. Analytics/dashboard/forecast/carbon convert at read time, never read `baseAmount`. No dedup/budget reader. Backup restore of `""` UNVERIFIED. |
| `normalizedEffectiveAmount` used beyond Transactions | OVERSTATED risk | analytics/dashboard/carbon/forecast hits are a different field (`NormalizedExpense.normalizedEffectiveAmount`, `AnalyticsCurrencyNormalizer.kt:349`); only `Expense.normalizedEffectiveAmount` affected: `TransactionsViewModel.kt:292-293, 900`, `TransactionsScreen.kt:535` |
| Rescue fabricates a snapshot | HOLDS | `FinancialRescueCoordinator.kt:418-420` |
| M5 "preserves a valid snapshot" | **OVERSTATED** | `RescueExpense` has no base fields (`FinancialRescueSnapshot.kt:21-43`) → M5 can only write sentinel or identity snapshot when currency == H |
| What M5 changes | Reframe | under M2 a fabricated foreign snapshot (baseCurrency = row currency ≠ H) already fails rule 2 → unconverted; same-currency identity rows correct → M5 adds nothing to sort/filter; fixes export honesty + lets inc 2 find rows |
| HALF_EVEN matches formatter + allocator | HOLDS with caveats | `CurrencyFormatter.kt:98` only `maximumFractionDigits` → JDK HALF_EVEN on **binary double**; `SourceCurrencyAllocation.kt:35` explicit HALF_EVEN on `BigDecimal.valueOf` (**decimal string**) — disagree on e.g. 1.015. App money type defaults **HALF_UP** (`Money.kt:34`), as do AddExpense (`AddExpenseViewModel.kt:369`, not `:377`) and splits/settlements. AGENTS.md:193 "preserve rounding semantics" → M3 needs explicit owner sign-off |
| 10.125 → €10.12 | HOLDS | exact in binary; both paths agree |
| `convertAsOf` stores unrounded | HOLDS | `CurrencyConverter.kt:287` |
| Legacy `'EUR'` default (also AnomalyAlert, nullable) | HOLDS | `AppDatabase.kt:6105-6107, 6115-6116` |
| SQL sort needs H as bind parameter | HOLDS, feasible | `@RawQuery` via `SimpleSQLiteQuery(sql, args)` (`:227`) → no schema/Room codegen change; args after WHERE args, before LIMIT/OFFSET (`:389-395`); keep "closed SortOrder enum" (`:387-388`): bind H with `?`, never interpolate |

## 2) Per-scope verdicts
- **M1** (write marker, reason code, drop `Timber.w`): GO — low read impact (readers already treat `baseAmount=0` as unconverted); value = privacy (RF-5) + honest exports. Caution: CSV/JSON `baseCurrency` cell `EUR` → empty (user/importer visible). Same TLC block as MEC A2, loan S3.
- **M2** (comparable rule): RESHAPE — must cover ALL-tab SQL `WHERE` (`ExpenseRepository.kt:356-364`) and decide the shared assistant builder; deterministic tiebreaker (e.g. `ORDER BY (cv IS NULL), cv DESC, e.date DESC, e.id DESC`; today none → tied non-comparable block under LIMIT/OFFSET may duplicate/skip rows across pages); "excluded count" on paged ALL tab needs its own COUNT with same filters; H Failed at read time → disable amount sort/filter (fail closed); paging reset when H changes.
- **M3** (snapshot rounding): GO only with owner approval; specify double→BigDecimal path (`BigDecimal.valueOf`); one helper shared with MEC preview; define positive-rounds-to-zero. Can't drop `baseAmount > 0`: a legacy foreign row with EUR home (`0.0/'EUR'`) would pass `baseCurrency == H` as valid zero → need "floor to one minor unit when rounded = 0" or "`baseAmount > 0` OR explicit sentinel". Shared rows compute `myShareAmount × exchangeRateUsed` / `baseAmount × pct` at read time (`Expense.kt:180-183`, SQL `:57-60`), unrounded → "display == stored == preview" only for unshared rows.
- **M4** (matrix tests): GO — add ALL-tab SQL filter + paging-tiebreak cases, `isNotMine` (comparable 0, not "none"), shared foreign rows.
- **M5** (Rescue): GO, downgraded to export/data honesty; fix "preserves a valid snapshot" wording.
- **Inc 2**: GO later; home-switch re-snapshot may need to come earlier (§3).
- **Inc 3**: GO; M1 already changes export cells → Q5 settled in inc 1.
- **Non-goals**: correct; add "AI assistant min/max semantics" as explicit decision.

## 3) Hidden scope and risks
1. **Blocking:** ALL-tab amount filter is raw for every row, converted or not (`ExpenseRepository.kt:356-364`); ALL vs time-range tabs differ today; brief's M2 doesn't fix ALL.
2. **Blocking:** builder shared with assistant (`ExpenseRepository.kt:238-301`, `ExecuteFinancialQueryUseCase.kt`) → silently changes assistant answers/counts unless decided.
3. **Home switch makes history disappear from amount views:** under brief Q2, after a switch every old-home row drops to bottom and out of amount filters — for a one-time switcher, most of their history; switch has no warning (CURR-6); worse than today's wrong-but-ordered until inc 2. Minimum: inc 1 ships a confirmation on the home-switch screen (`CurrencyManagementViewModel.kt:148-182`, not re-read) stating the effect; else pull home-switch re-snapshot forward.
4. Rule 1 trusts `currency`, which itself defaults to `'EUR'` on many tables (`AppDatabase.kt:356` etc.); if any path defaulted `currency` to EUR for a non-EUR-home user, those rows (correct by luck today) would be excluded — UNVERIFIED whether any live path relies on it.
5. Negative/refund amounts: if any source stores negative `amount`, `baseAmount > 0` fails → reads unconverted — UNVERIFIED; domain-modeler pins sign convention.
6. Paging stability: tiebreaker mandatory.
7. OD-3 drift grows (rounded snapshots vs unrounded analytics read-time): ≤ half a cent/row; acceptable as inc 2, state it.

## 4) Recommended shape
- **Split by layer** (M2 doesn't depend on M1: today's no-rate rows `0.0/'EUR'` already fail `baseAmount > 0`):
  - **Inc 1a (read side, no TLC edits):** M2 widened to SQL WHERE + tiebreaker, assistant decision; M4 read matrix; M5; home-switch warning. Avoids MEC A2 / loan S3 contention → can land first after ai-fixes.
  - **Inc 1b (write side, TLC block):** M1 + M3 + write-side M4 tests; sequence with MEC A2 per G2-2 (A2's INV-7 depends on M3 helper → 1b just before A2, or A2 takes the helper and 1b rebases). Loan S3 (`ExpenseSource` `when` TLC `:508-526`) after 1b.
- Tier T3, reviewer-strict on 1a (money + SQL) and 1b (TLC lifecycle). No schema in either — HOLDS.

## 5) Owner questions (refined, recommended answers)
1. Placement of non-comparable rows? **Rec:** last both directions with deterministic tiebreaker; excluded from min/max; visible "N without a <home> value" count from a real COUNT query.
2. Home switch in inc 1? **Rec:** accept "no comparable value" **plus** a mandatory warning dialog on home switch; if warning rejected, pull inc 2 home-switch re-snapshot into inc 1.
3. Rounding mode? **Rec:** HALF_EVEN via `BigDecimal.valueOf` in one shared helper (also MEC preview); explicit approval since it deviates from `Money.kt` HALF_UP (AGENTS.md:193); positive amount rounding to zero keeps one minor unit.
4. Assistant amount filters (new)? **Rec:** inc 1a keeps assistant builder behaviour unchanged via its own parameter; assistant alignment → SRH-7 / ai-fixes owner.
5. Export cells for no-rate rows in inc 1 (M1 empties them)? **Rec:** accept + release note; `baseAmount` null semantics in inc 3.
6. Read-time home Failed? **Rec:** disable amount sort/filter with a bounded state, fall back to date sort; never apply rule 1 against a guessed H.
(Brief's Q4 analytics direction stays with inc 2.)

## 6) Route elsewhere
- Assistant min/max raw comparison (`ExecuteFinancialQueryUseCase` via `ExpenseRepository.kt:356-364`) → ai-fixes / SRH-7 owner.
- `AnomalyAlert.baseAmount/baseCurrency` (`AppDatabase.kt:6115-6116`) → separate; usage UNVERIFIED.
- App-wide rounding inconsistency (HALF_UP `Money.kt:34`, splits/settlements vs HALF_EVEN formatter/allocator) → money-policy item.
- Mixed-group date-header signing (`TransactionsScreen.kt:535` unsigned vs `:528` signed) → UNVERIFIED, domain-modeler.

## 7) Verdict: **RESHAPE**
Blocking: (a) M2 omits ALL-tab SQL min/max reader (`ExpenseRepository.kt:356-364`) → ledger deciding test still fails on the default tab; (b) shared assistant builder undecided; (c) no paging tiebreaker for tied non-comparable rows.
Flips to GO: widen M2 to SQL WHERE + tiebreaker; decide assistant behaviour; split 1a (read) / 1b (TLC write) around MEC A2; home-switch warning in 1a (or pull re-snapshot forward); fix M5 wording; explicit owner approval of rounding deviation incl. zero-collision rule and shared-row caveat.
