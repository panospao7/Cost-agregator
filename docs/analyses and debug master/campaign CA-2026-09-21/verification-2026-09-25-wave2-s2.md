# CA-2026-09-21 — PHASE-2 VERIFICATION: WAVE-2 S2 FINDINGS (CL-27 + CL-05)

Date: 2026-09-25
Anchor pin: `37601232` (ancestor check); all verdicts re-located and verified at CURRENT HEAD
(`cdf501d4` at pass time) — several anchors drifted from the audit pin as anticipated by the
W2 spec-session §0. Finder: astra spec/audit sessions. Verifier: ZCode/GLM-5.3 direct session +
2 read-only collectors (agent_203f8903, agent_d70e9cb2) whose verbatim quotes were judged
in-session — finder ≠ verifier holds.

RESULT: **10/10 verified — 8 CONFIRMED (3 with corrections/narrowings), 2 PARTIAL-RECALIBRATED,
0 REFUTED.** Both specs' GATEs lift. Addendum adjudications: A8 CONFIRMED-live (mandate stands),
A5 CONFIRMED-as-real-defect (fenced owner per CL-05 spec), A7 OPEN (re-check below).

---

## CL-27 — money core (3/3)

### CA-E-01-001 (P2) — CONFIRMED, mechanism intact at HEAD
`CurrencyConverter.storeRates` (domain/currency/CurrencyConverter.kt:552-558):
`validDate: Long? = null` → `val effectiveValidDate = validDate ?: startOfDay(now)` — the
provider publication date is fabricated as download-day start-of-day whenever the caller omits
it, and the refresh caller (CurrencyRatesRepositoryImpl) has no validDate mention — it omits it.
The CURR-70F-04 storage guard (ExchangeRateStoreAdapter:68-72, `require(validDate != null &&
validDate > 0L)`) does NOT prevent this: a fabricated non-zero date passes. Spec's typed
fallback/real-date requirement stands.

### CA-E-01-003 (P2) — RECALIBRATED (PARTIAL)
The claimed interop mismatch is GONE at HEAD: every consumer (MultiCurrencyRepository:752,
BudgetHistorySeriesBuilder:103-105, BudgetForecastingEngine:480, AdvancedAnalyticsDashboard:260)
uses the single shared `TimePeriodUtils.formatMonthKey` (line 839: `String.format("%04d-%02d", …)`
— no Locale argument). Keys are therefore internally consistent. Residual defect (narrower):
no explicit Locale means localized-digit locales render non-ASCII month keys, and
`parseMonthKey` (line 849-851, `toIntOrNull ?: throw`) then throws — a series crash on such
devices. Severity drops accordingly; CL-27's fix (explicit Locale.ROOT) remains correct and
cheap, but the spec's "series intersection empties" framing should be reworded.

### CA-E-01-005 (P2) — CONFIRMED, narrowed to the rate-basis overload
`MoneyAggregateBuilder` has two fromBuckets overloads. The E5-006-era legacy overload (lines
63-131) handles failures correctly (sourceBuckets built from ALL input buckets before
conversion; per-currency transactionCounts on conversionFailures; PR-E22/E1-verified warning).
The rate-basis overload (lines 138-217) — the one date-aware consumers use — **omits failed
buckets from `sourceBuckets`** (line 191-203: Failed → failures list only, no add) while
`MoneyAggregate.totalTransactionCount` = `sourceBuckets.sumOf { it.transactionCount }`
(MoneyAggregate.kt:48-49) — so the aggregate under-reports the transaction population its own
warning ("Total excludes N transactions") references. Fix shape unchanged: failed buckets
belong in sourceBuckets (or totalTransactionCount must include failure counts).

## CL-05 — currency contract (7/7)

### CA-E-01-002 (P2) — CONFIRMED, sharpened
Three catalogs disagree pairwise: converter whitelist `SupportedCurrency` (17, incl. PLN/CZK/HUF/
RON/BGN/ISK; HRK inactive), shared refresh/selector list (20: both use the identical
PRIORITY_CURRENCIES). Consequences at HEAD: 11 currencies (CNY NZD MXN SGD HKD KRW TRY RUB INR
BRL ZAR) get rates stored but are rejected by `convertOutcome` (INVALID_SOURCE/TARGET_CURRENCY,
CurrencyConverter:344-357) — while the legacy `convert()` used by CurrencyManagementViewModel:189
has NO whitelist check and converts them anyway; and 6 whitelisted currencies (PLN CZK HUF RON
BGN ISK) never get rates → guaranteed MISSING_RATE. The spec's "one catalog" mandate is
confirmed as the correct fix shape.

### CA-P-01-006 (P2) — CONFIRMED, mechanism corrected
The premature SEK does NOT come from the ambiguous-kr branch (which resolves home-currency
correctly at NotificationMoneySignalDetector:109-128). The defect: `CurrencyDef("SEK","SEK","kr")`
(line 30) plus `isoCodes = symbols.toList()` (line 153) leaks `kr` into the EXPLICIT-ISO regex
(line 42-47), so any "12 kr" returns SEK at 0.95 confidence EXPLICIT_ISO_CODE / ambiguous=false
(line 54-62) before the ambiguity logic runs. Same conflation kills the ambiguous-`$` branch
(USD def line 21 includes "$"/"US$"; branch at 88 is dead). Data-modeling bug (isoCodes vs
symbols conflation), not branch ordering — spec should fix the CurrencyDef modeling.

### CA-P-04-006 (P2) — CONFIRMED, with a data-availability correction
`getMonthlyBillsTotal` (BillReminderManager:162-172) sums mixed currencies into one Double with
no conversion or currency filter; BillRemindersScreen:191 formats `reminder.amount` with
`homeCurrency` while `reminder.currency` (populated at :97) sits unused — the correct label data
exists and is ignored. Correction: the claim implied currency-less amounts; actually the data is
carried and dropped at render. Surface is marked LEGACY pending RecurringLifecycleCoordinator
migration (Screen:31-37) — the spec should weigh fix-here vs migrate-first.

### CA-P-06-001 (P2) — CONFIRMED
BudgetAutopilotEngine:180-185 caps ±15% around `budget.amount` (stored currency, Budget entity
default "EUR") against home-normalized history (`getHistoricalCategoryMonthlySpend` resolves home
currency, MultiCurrencyRepository:787-789). No conversion exists anywhere in the engine — no
converter dependency; injected currencySettingsRepository is never called. Harmless only when
budget.currency == home currency. Codebase's own comment (CurrencyManagementViewModel:161-164)
acknowledges re-normalization as a future step — the spec's typed-conversion requirement stands.

### CA-E-02-002 (P2) — CONFIRMED, exception-type corrected
Empty-result factories (AdvancedAnalyticsEngine:610-611, 744-745) call
`defaultDisplayCurrency()` = `Currency.getInstance(Locale.getDefault())` (:1002-1004) — which
deliberately rethrows as IllegalStateException for countryless locales — while the resolved
`input.homeCurrency` parameter is in scope and unused on the empty branch (:543-547, :680-684).
Fix: pass homeCurrency into the empty factories. (Claim's "throws" accurate; exception type is
the code's own rethrow.)

### CA-E-02-005 (P2) — CONFIRMED, location corrected
RecurringExpenseEngine PRESERVES source currency (RecurringExpenseEngine.kt:50-54,
`currency = manual.currency`). The relabel is introduced solely by InsightsEngine's mapping
(:760-778): `pattern.currency` is discarded and `displayCurrency` (= home, from :147) stamps the
unconverted amount; `RecurringExpense` has no source-currency field (AnalyticsModels:118-127).
Spec's fix targets InsightsEngine (+model shape), not RecurringExpenseEngine.

### CA-E-04-004 (P3) — PARTIAL (main mechanism CONFIRMED; one element REFUTED)
CONFIRMED: per-category sums come from the deprecated-ERROR raw-SUM DAO
(ExpenseDao:2479-2493, EFFECTIVE_AMOUNT_SQL has no currency dimension; suppressed at
BusinessExpenseRepository:57-59) and TaxEstimator labels the cross-currency sums as filing
currency with no conversion (TaxEstimator:369-375 `MoneyAggregate.singleCurrency(…,
CurrencyCode(filingCurrency))`; fallback :106-109 same). REFUTED: "null-category remainder
dropped" — TaxEstimator:361-368 explicitly folds the remainder into "Uncategorized". Missed by
claim: a currency-aware replacement DAO that includes NULL categories already exists
(ExpenseDao:2518-2527) — unused. Spec fix: switch to the replacement DAO; drop the
remainder-refuted element.

---

## Addendum adjudications (binding on specs, per Stage-2 addendum)

- **A8 (May money-batch landmine) — CONFIRMED LIVE.** `MoneyAggregateConversionScenarioTest` does
  not appear in the Phase B executed results (never reached before the OOM), so its current
  pass/fail state is UNKNOWN. Given E-01-005's confirmed overload-2 counting behavior, the May
  re-pointed assertions remain a live hazard. CL-27's spec mandate (re-examine before any test
  update) STANDS; the implementation session must run that test class and reconcile assertions
  against the FIXED overload behavior.
- **A5 (phone-heuristic over-redaction, candidate CA-W1-003) — CONFIRMED as a real defect.**
  Evidence: Phase A failing tests showed actual output `"Payment of [REDACTED_PHONE] at a shop"`
  for amount "12345.67" (KeystoreInstallationSecretHashingTest, vr-20260924-185304). Disposition
  per CL-05 spec's fence: owned by a redaction-heuristic fix (CL-17-adjacent), NOT CL-05;
  promoted from candidate to verified finding. Owner/wave assignment: decision register.
- **A7 (displayed group balances ignore settlements) — CONFIRMED OPEN post-merge.**
  SplitCalculator.calculateBalances still has no settlement term (only a @Deprecated pointer to
  SettlementCalculator for payment *plans*, a different concern). Displayed balances remain
  frozen at pre-settlement values after CL-29's merge. → decision register: small CL-29
  follow-up patch vs Wave-3 item.

## Campaign verification scorecard after this pass
Wave-1 gates: 13/13 (verification-2026-09-22-wave1.md). Wave-2 S2: 10/10. P-01: 7/7.
**Total 30/30 CONFIRMED, 0 REFUTED; 6 wider-than-reported, 3 recalibrated across campaign.**
