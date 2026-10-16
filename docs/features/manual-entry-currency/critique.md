# Critique: manual-entry-currency

Author: feature-critic (independent, read-only, 25 tool calls). Under review: `brief.md`. Date 2026-10-16. Persisted by the orchestrator. `A` = `app/src/main/java/com/yourname/expensetracker`.

**Verdict: RESHAPE.** The currency control is worth building and the binding decisions (currency control; no rate → block) are buildable, but three claims fail: recurring rules don't create expenses in the background; increment 0's read rule isn't safe as written; "a rate for this date" is undefined and decides whether the block ever fires and whether results are accurate.

## 1) Fact-check
| Claim | Status | Evidence |
|---|---|---|
| As-of lookup has no age limit | HOLDS | `ExchangeRateDao.kt:62`; `CurrencyConverter.kt:256-320` |
| `convertAsOf` direct then via EUR | HOLDS; via-EUR mostly dead | uses `fromCode` (full catalog → inactive HRK still converts, `CurrencyConverter.kt:262`); ECB refresh stores every cross pair among ECB-listed supported currencies (`CurrencyRatesRepositoryImpl.kt:84-98`) |
| Only source ECB daily XML gated on cloud-AI consent | HOLDS | `CurrencyRatesRepositoryImpl.kt:39, 48-52`; `validDate` = ECB publication date at **UTC** midnight (`:156`) |
| Preview can show "ECB rate of 15 Oct" | WRONG as specified | `ConversionResult.timestamp` = `rate.lastUpdated` (download time), not `validDate` (`CurrencyConverter.kt:290, 314`) → converter contract must change |
| BGN/RUB active | HOLDS, worse | active (`CurrencyConverter.kt:36, 47`); if either is **home**, `refreshFromStream` throws `HOME_CURRENCY_QUOTE_UNAVAILABLE` (`CurrencyRatesRepositoryImpl.kt:80-82`) → no rates download at all; already in `release-1.0-verification-ledger.md:639` |
| `CurrencyPicker` reusable | OVERSTATED | private and a hard-coded list of 7 currencies (`ReceiptScanScreen.kt:1389`), not `activeCatalog` → rewrite |
| Recurring occurrences convert at own date / unblockable background rows | **WRONG** (high confidence, not exhaustive) | rule creation makes PLANNED occurrences/reminders/planned rows, not expenses (`LEGAL_PATHS.md:296-298, 320-332`); expenses created separately then linked (`:328-332, 359`); `ExpenseSource.RECURRING_GENERATED` only in TLC source mapping (`TransactionLifecycleCoordinator.kt:522`); no recurring class calls `createExpense*`; only `insertAtomic` caller is TLC `:685` |
| Recurring rule creation bypasses its coordinator | **WRONG** (stale TODO) | `RecurringExpenseRepository.insert` → `ruleLifecycleCoordinator.get().createRule(...)` (`RecurringExpenseRepository.kt:116-118`) |
| No-rate branch keeps defaults + logs currency/amount | HOLDS | `TransactionLifecycleCoordinator.kt:572-578` |
| Inc 0 migration-free; "foreign + base 0 = not comparable" safe | **OVERSTATED** | §3.1; entity can't know "foreign"; legacy rows default `baseCurrency='EUR'` (`Expense.kt:147`) |
| Sort/filter raw fallback | HOLDS | `Expense.kt:179`; SQL ELSE `ExpenseRepository.kt:62, 71` |
| Cap 1,000,000 raw units | HOLDS, duplicated | VM `AddExpenseViewModel.kt:297` + `ManualExpenseRepository.kt:114` |
| R23 part 1 hard dependency | HOLDS, already approved for 1.0 | `release-1.0-assurance-handoff.md:241-242`; default on/off of new setting UNVERIFIED (decides day-one blocking) |
| "Always blocked before first download" | HOLDS, framing off | no age limit → one download unblocks all later dates; block fires only for never-downloaded, dates before first download, RUB/BGN. Bigger everyday risk = silently stale rate |

## 2) Per-scope verdict
| Item | Verdict | Reason |
|---|---|---|
| Inc 0a no-rate branch `""` marker + bounded code + stop logging | SPLIT-OUT | pre-existing P3 across all sources; small; land first with own tests |
| Inc 0b sort/filter non-comparable (Kotlin + SQL) | SPLIT-OUT + RESHAPE | needs home-currency-aware rule (CURR-6 coupling), explicit NULL/last ordering in SQL, own domain spec; not migration-free by inspection |
| Inc 0c pin "EUR" default | KEEP (in split-out) | |
| Inc 1 control, active list, default home | KEEP | |
| Inc 1 preview with rate date | KEEP + RESHAPE | expose `validDate` in `ConversionResult`/store |
| Inc 1 block at TLC | KEEP + RESHAPE | limit to MANUAL_ENTRY (request flag) or imports get dropped; define "rate for this date" first (Q1) |
| Inc 1 per-currency decimals/rounding/cap | KEEP | fix cap in both places or single rule owner |
| Inc 1 recurring toggle in foreign currency | DEFER / hide for non-home | stated justification wrong; real risks differ (§3.5) |
| Inc 2 user-entered rate | DEFER; don't reuse `CurrencyConverter.storeRate(source="manual")` | `:603-625` writes the shared rate table → typed rate becomes as-of rate for all later dates, analytics, other rows; per-expense rate ignored by analytics → disagreement; both options expensive |
| Inc 6 ECB historical download | PROMOTE to inc 1b, ahead of inc 2 | same host/public data/consent setting; only fix for post-trip/first-use consistent with analytics; privacy review (bigger payload); endpoint UNVERIFIED |
| Inc 3, 4, 5 | DEFER | as brief |

## 3) Hidden scope and risks
1. **Inc 0 read rule unsafe (blocking).** `normalizedEffectiveAmount` has no home currency (`Expense.kt:176-185`). "currency ≠ baseCurrency and base ≤ 0": legacy rows default `baseCurrency='EUR'` (`Expense.kt:147`; `AppDatabase.kt:6105`) → for non-EUR-home users every legacy home row (USD/EUR/0) becomes non-comparable — regression. "compare with current home" pulls in CURR-6. Needs domain decision, SQL CASE with explicit NULL ordering, legacy-fixture tests. Export (`ExpenseExportMapper.kt:45`), backup restore, CSV/JSON round-trip of `""` UNVERIFIED.
2. **Staleness is the everyday money risk.** No age limit + no scheduled rate worker (HZ01b2-F1) → months-old rate used silently. Owner's "no stored rate *for the expense date*" is closer to bounded age; brief's any-age Q1 arguably contradicts it. Age limit needs regular refresh — whether R23 part 1 adds a worker UNVERIFIED.
3. **Timezone boundary.** `validDate` UTC midnight (`CurrencyRatesRepositoryImpl.kt:156`) vs local expense instant with time of day (`AddExpenseSheet.kt:847-854`) → in Greece (UTC+2/+3) expenses before ~02:00–03:00 local compare to the previous UTC day; spurious block on first-download date; analytics same.
4. **Rounding undefined.** `convertedAmount = amount * rate` stored unrounded (`CurrencyConverter.kt:287`); preview rounded → stored/displayed/summed base differ by cents; rounding only manual rows makes results source-dependent → one rounding rule at TLC snapshot for all sources (belongs with inc 0).
5. **Foreign recurring has its own risks (UNVERIFIED depth).** USD rule's PLANNED occurrences may never auto-match when bank/card notification arrives already in EUR — overlaps R21 V-C4 "recurring default EUR" (`release-1.0-phase3-4-findings-ledger.md:204`); forecast/upcoming-bill totals over foreign planned rows UNVERIFIED.
6. **Capture-path inconsistency.** Receipt review already lets the user pick USD and saves unconverted when no rate (`ReceiptScanViewModel` → TLC); manual would block → owner accepts, or block also covers user-confirmed receipt saves.
7. **Cap.** 1,000,000 raw units blocks ordinary KRW/HUF/ISK (1,000,000 KRW ≈ €650); duplicated rule (VM `:297` + `ManualExpenseRepository.kt:114`).
8. **Privacy.** `Timber.w` at `TransactionLifecycleCoordinator.kt:573-577` logs currency + amount; whether release builds strip Timber UNVERIFIED — if not, breaks bounded diagnostics today, independent of this feature.
9. **File conflicts.** R16 (AddExpense amount parsing) and R21 V-C4 (recurring currency default) in-flight on same files; nl-quick-add inc 1 and loan S11 queued on AddExpense. Splitting inc 0 out moves TLC/Transactions work out of the AddExpense queue. Whether loan S11 touches TLC UNVERIFIED.

## 4) Recommended shape
- **A. `money-correctness-1.0.x`** (separate T3, lands first after 1.0): inc 0a/0b/0c reshaped; one snapshot rounding rule for all sources; own domain spec covering legacy + home-switch matrix; optionally FinancialRescue fabrication + CURR-6 decisions; reviewer-strict; outside the AddExpense queue.
- **B. `manual-entry-currency` inc 1** (T3): picker on `activeCatalog` minus no-rate-source currencies; preview showing `validDate`; manual-only block at TLC with agreed "rate for this date"; per-currency decimals + cap; recurring toggle hidden for non-home; after R16, R21, R23 part 1 in AddExpense order; soft dependency on A (marker + log fix).
- **C. inc 1b:** ECB historical rates (e.g. 90-day window) behind the same R23 setting, privacy review; replaces most of the case for a manual rate.
- **D. later:** manual rate (provenance + analytics consistency design), editing, trip currency, backfill.

## 5) Owner questions (refined, with recommended answers)
1. "A rate for this date"? **Rec:** latest ECB rate dated ≤ expense date and **≤ ~7 days older** (weekends/holidays); older → block with "refresh rates"; rate date always shown; compared by local calendar date, not UTC instant. If age limit rejected: accept silent stale rates with date shown.
2. Increment 0 as its own money-correctness item landing first? **Rec: yes, split out.**
3. Post-trip/first-use dates with no rate: ECB history or typed rate? **Rec:** ECB history as inc 1b (same consent setting); defer typed rate.
4. Block only manual, or also user-confirmed receipt saves? **Rec:** manual + user-confirmed receipt review (user present); never background/import sources (they save with the visible unconverted marker).
5. Recurring in foreign currency in inc 1? **Rec: no** — hide toggle for non-home until matching vs bank-converted notifications and V-C4 are designed.
6. BGN and RUB? **Rec:** inactive for new selection (like HRK); legacy rows keep converting (`fromCode` full catalog); BGN-home users get a prompt to switch to EUR; no fixed-peg constant for new entries.

## 6) Route elsewhere now
- **R23 part 1 (release 1.0):** BGN/RUB home → rate download yields zero rates (`CurrencyRatesRepositoryImpl.kt:80-82`; ledger `:639`); once R23 gives download its own setting those users get a working-looking setting that never produces rates → at minimum bounded clear error, stop offering BGN/RUB as home. Also decide new setting's default and whether R23 adds periodic refresh (shapes Q1).
- **Release privacy check (1.0 or 1.0.x):** `TransactionLifecycleCoordinator.kt:573-577` logs currency + amount; confirm Timber stripped in release builds, else swap in bounded reason code (one line).
- **R21 V-C4:** record foreign-currency recurring rule from manual entry as a follow-up consumer so the default fix doesn't re-hard-code the assumption.
- **Housekeeping:** stale TODO at `ManualExpenseRepository.kt:185` (path already goes through `RecurringRuleLifecycleCoordinator`, `RecurringExpenseRepository.kt:116-118`).

## 7) Verdict: **RESHAPE**
Blocking: wrong recurring premise behind the inc 0 fold-in and Q5; inc 0 "not comparable" rule unsafe/not migration-free (legacy `baseCurrency='EUR'` for non-EUR home users); "rate for this date" undefined (any-age contradicts the spirit of the block decision; preview rate date not available today).
Flips to GO: split inc 0 into money-correctness; block scoped to manual (user-present) saves with decided age + timezone rule; expose `validDate`; hide recurring for non-home; deactivate BGN/RUB.
UNVERIFIED: rate cleanup callers; backup/export of `""`; forecast over foreign planned rows; R23 worker/default; loan S11 touching TLC; exhaustiveness of "nothing creates expenses from occurrences".
