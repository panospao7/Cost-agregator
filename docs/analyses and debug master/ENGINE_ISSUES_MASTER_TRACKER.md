# Engine Issues Master Tracker

> Consolidated P0/P1 issues from 5 engine debug reports.
> Source: warranty-subscription-location-nlp, analytical, categorization-merchant, groups-investment-tax, money-time-primitives
> **Last updated: 2026-05-07**

## Status Legend
- ⬜ NOT STARTED
- 🔧 IN PROGRESS  
- ✅ FIXED
- ⏭ DEFERRED (needs design/migration)
- 📝 TODO ONLY (documented, not coded)

---

# 1. Warranty / Subscription / Location / NLP Engines

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| W01 | P0 | Warranty protected value not currency-safe | Bug | Use MoneyAggregate with effectiveAmount | ⬜ |
| W02 | P0 | Return-window refund currency not updated | Bug | Add refundCurrency to DAO, infer from expense | ⬜ |
| W03 | P0 | Warranty lifecycle has no event log | Enhancement | Add WarrantyLifecycleEvent table + DAO | ⏭ |
| W04 | P0 | Subscription price history recordedAt=0 | Bug | timeProvider.now() in creation paths | ⬜ |
| W05 | P0 | Subscription usage average can divide by zero | Bug | Coerce daysBetween to at least 1.0 | ⬜ |
| W06 | P0 | Subscription totals raw-sum mixed currencies | Bug | Return MoneyAggregate with per-currency buckets | ⬜ |
| W07 | P0 | Price change update not atomic | Bug | withTransaction wrap insert+update | ⬜ |
| W08 | P0 | Bill negotiation no persistence | Bug | Add NegotiationOutcome entity+DAO | ⏭ |
| W09 | P0 | Bill negotiation UI compares wrong rates | Bug | Compare monthly-to-monthly | ⬜ |
| W10 | P0 | Device GPS not privacy-gated | Bug | PrivacyGate(DEVICE_GPS_LOCATION) check | ⬜ |
| W11 | P0 | Location insights include non-spending | Bug | Apply spending-only filter | ⬜ |
| W12 | P0 | Map/insight amounts not currency-normalized | Bug | Use LocatedMoneyExpense with conversion | ⬜ |
| W13 | P0 | Manual correction insert can silently fail | Bug | Change upsertCorrection return to Long | ⬜ |
| W14 | P0 | Legacy NL merchant extraction broken | Bug | Extract on original query, not lowercased | ⬜ |
| W15 | P0 | Legacy NL filters parsed but ignored | Bug | Push filters to repository/DAO query | ⬜ |
| W16 | P0 | NL amount filter currency unsafe | Bug | Use ExtractedAmount with conversion | ⬜ |
| W17 | P0 | Cloud query sends raw text without redaction | Bug | Apply CloudPayloadRedactor before prompt | ⬜ |
| W18 | P1 | Warranty timestamps unset on insert | Bug | copy(createdAt=..., updatedAt=now) | ⬜ |
| W19 | P1 | Warranty AI extraction not privacy-gated | Enhancement | CloudAiGuard(CLOUD_AI_WARRANTY) check | ⬜ |
| W20 | P1 | Warranty end-date semantics ambiguous | Bug | Half-open: startInclusive/endExclusive | ⬜ |
| W21 | P1 | Manual receipt hardcodes EUR | Bug | Use homeCurrency, sanitized metadata | ⬜ |
| W22 | P1 | Subscription missing createdAt/currency/validation | Bug | CreateSubscription with enforced fields | ⬜ |
| W23 | P1 | Candidate accepted date uses fixed millis | Bug | Use RecurrenceCalculator.nextOccurrence() | ⬜ |
| W24 | P1 | Candidate uniqueness wider than intended | Enhancement | Partial unique index or ledger table | ⏭ |
| W25 | P1 | Bill negotiation rates hardcoded EUR | Enhancement | MarketRateProvider with currency/region | ⏭ |
| W26 | P1 | Date-range filtering uses inclusive end | Bug | Change <= to < for half-open contract | ⬜ |
| W27 | P1 | LocationResolver fetches GPS too early | Enhancement | Defer until actually needed | ⬜ |
| W28 | P1 | Coordinate validation incomplete | Enhancement | GeoCoordinate rejecting null-island/NaN | ⬜ |
| W29 | P1 | Area/travel engines raw-sum mixed currencies | Bug | Use MoneyAggregate output | ⬜ |
| W30 | P1 | Legacy NL does date-only broad paging | Enhancement | Use filtered DAO query | ⬜ |
| W31 | P1 | NL offset paging not snapshot-stable | Bug | Keyset pagination or single-txn snapshot | ⬜ |
| W32 | P1 | Assistant "largest" query raw mixed-currency | Bug | Normalize before maxByOrNull | ⬜ |
| W33 | P1 | Assistant query totals no partial state | Enhancement | Return dataQuality in FinancialQueryResult | ⬜ |
| W34 | P1 | Conversation history stores raw sensitive queries | Enhancement | Redact after storage, retention policy | ⏭ |
| W35 | P1 | Voice recognizer lifecycle incomplete | Bug | Add destroy() from onCleared, error handling | ⬜ |

---

# 2. Analytical Engines

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| A01 | P0 | No canonical analytics input contract | Enhancement | Create NormalizedAnalyticsInput type | ⏭ |
| A02 | P0 | TotalsAggregationEngine unsafe multi-currency | Bug | Guard with require(isSingleCurrency) | ⬜ |
| A03 | P0 | Historical analytics uses current rates | Bug | Use convertAsOf(amount, from, to, expense.date) | ⬜ |
| A04 | P0 | SpendingPersonalityClassifier not currency-safe | Bug | Inject normalizer; normalize before extraction | ⬜ |
| A05 | P0 | AnalyticsRepository drops partial-conversion | Bug | Return MoneyAggregate + dataQuality | ⬜ |
| A06 | P0 | Basic/advanced/repo/legacy analytics disagree | Bug | Create AnalyticsInputAssembler for consistency | ⏭ |
| A07 | P1 | InsightsEngine defaults to EUR | Enhancement | Require NormalizedAnalyticsInput or deprecate | ⬜ |
| A08 | P1 | Daily chart uses "last N days from now" | Bug | Use explicit startMs/endMs range | ⬜ |
| A09 | P1 | Advanced analytics may use different period | Bug | Pass explicit AnalyticsPeriodRange from VM | ⬜ |
| A10 | P1 | Category analytics compares normalized to raw budget | Bug | Normalize budget snapshots before comparison | ⬜ |
| A11 | P1 | Conversion warnings don't affect confidence | Enhancement | Add AnalyticsDataQuality + confidencePenalty | ⬜ |
| A12 | P1 | Merchant anomaly limited history in normal path | Bug | Fetch 12-month lookback independent of chart | ⬜ |
| A13 | P1 | Spending pace period wrong for historical | Bug | Add referenceNow param, use period.endMs | ⬜ |
| A14 | P1 | Location analytics raw DAO path | Bug | Use normalized snapshots/MoneyAggregate | ⬜ |
| A15 | P1 | Category deletion/history weak | Enhancement | Soft-delete or persist name snapshot | ⏭ |
| A16 | P1 | Analytics recomputes too much | Enhancement | AnalyticsInputAssembler: query once, split in memory | ⏭ |

---

# 3. Categorization / Merchant Normalization

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| C01 | P0 | Alias linking silently fails on conflict | Bug | Check rawName+normalizedKey before insert | ⬜ |
| C02 | P0 | Merchant timestamps not set on creation | Bug | timeProvider.now() in create/link methods | ⬜ |
| C03 | P0 | Category name lookup case-sensitive | Bug | Normalize keys with trim().lowercase() | ⬜ |
| C04 | P0 | Categorization cache stale for 5 min | Bug | Invalidate from all category/mapping writes | ⬜ |
| C05 | P0 | MerchantCategoryDao.insert() returns Unit | Bug | Change to insert(): Long, handle -1L | ⬜ |
| C06 | P1 | normalizedCanonicalName lookup ambiguous | Bug | Make unique or return all by source/confidence | ⬜ |
| C07 | P1 | Fuzzy search only sees top 1000 merchants | Enhancement | BK-tree from all or indexed prefix fallback | ⏭ |
| C08 | P1 | Merchant stats not consistently updated | Bug | After committed expense, update canonical stats | ⬜ |
| C09 | P1 | autoCreate=false returns placeholder display name | Bug | Match using searchKey, not normalizedName | ⬜ |
| C10 | P1 | Auto-learning can reinforce mistakes | Enhancement | Strong-learn only from USER_CONFIRMED/REVIEW_APPROVED | ⬜ |
| C11 | P1 | Category corrections don't update old rows | Enhancement | Offer lifecycle-aware backfill of existing | ⏭ |
| C12 | P1 | Semantic keyword collisions likely | Enhancement | Conflict policy: alternatives, lower confidence | ⬜ |
| C13 | P1 | Context inference too isolated | Enhancement | Expand CategorizationContext fields | ⏭ |
| C14 | P1 | Debug trace not persisted | Enhancement | Add CategorizationDecisionTrace ring buffer | ⬜ |

---

# 4. Groups / Investment / Tax Engines

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| G01 | P0 | Current-user member inserts violate key invariant | Bug | Set currentUserGroupKey=groupId on insert | ⬜ |
| G02 | P0 | Group expense side effects inside outer txn | Bug | Deferred post-commit side-effect list | ⬜ |
| G03 | P0 | Linked expense normalization bypasses lifecycle | Bug | Use coordinator.updateSharedOwnership() | ⬜ |
| G04 | P0 | Mixed-currency settlements labeled wrong | Bug | Reject or convert to group currency | ⬜ |
| G05 | P1 | Group currency consistency not enforced | Enhancement | Single-currency or multi-currency with conversion | ⏭ |
| G06 | P1 | Shared budget offsets drop conversion failures | Enhancement | Return MoneyAggregate with isPartial | ⬜ |
| G07 | P1 | Shared offset uses current rates not historical | Bug | Use convertAsOf(atMillis=expense.date) | ⬜ |
| G08 | P1 | Hard delete path bypasses coordinator | Bug | Route through archiveGroup/permanentlyDelete | ⬜ |
| G09 | P1 | Direct member delete bypasses validation | Bug | Keep validation in one coordinator/use case | ⬜ |
| G10 | P1 | runBlocking inside domain calculators | Enhancement | Make suspend or require explicit currency param | ⬜ |
| I01 | P0 | Portfolio raw-sums mixed currencies | Bug | Return MoneyAggregate with per-currency buckets | ⬜ |
| I02 | P0 | Price update not atomic with history insert | Bug | withTransaction wrap both operations | ⬜ |
| I03 | P0 | Portfolio history undercounts days | Bug | Carry forward latest value per holding | ⬜ |
| I04 | P0 | No lot/transaction ledger | Enhancement | Add InvestmentTransaction table | ⏭ |
| I05 | P1 | UI doesn't show investment performances | Bug | Expose active investments + performance flow | ⬜ |
| I06 | P1 | DAO aggregates disagree with tracker math | Bug | Include fees in aggregate or remove raw methods | ⬜ |
| I07 | P1 | Investment timestamps not enforced | Bug | Repository add with price>0, quantity>0, createdAt>0 | ⬜ |
| I08 | P1 | Direct Dispatchers.IO instead of injected | Enhancement | Inject @IoDispatcher | ⬜ |
| I09 | P1 | Price staleness not modeled | Enhancement | Add stalePriceThreshold + dataQuality | ⏭ |
| T01 | P0 | Tax totals not currency-normalized | Bug | Use MultiCurrencyRepository + MoneyAggregate | ⬜ |
| T02 | P0 | Mileage deduction undercounts null values | Bug | DAO SUM CASE fallback: distance * rate | ⬜ |
| T03 | P0 | Tax country not persisted | Bug | TaxSettingsRepository with selectedCountry | ⏭ |
| T04 | P1 | VAT estimation assumes standard-rate | Enhancement | Rename to estimatedVatPortion, per-expense fields | ⬜ |
| T05 | P1 | Business report hardcodes euro formatting | Bug | Use CurrencyFormatter with filing currency | ⬜ |
| T06 | P1 | Business report raw-sums mixed currencies | Bug | Return MoneyAggregate in report fields | ⬜ |
| T07 | P1 | Business CSV weak formula safety | Bug | Hardened CSV cell sanitizer (neutralize =,+,-,@) | ⬜ |
| T08 | P1 | Tax rates hardcoded | Enhancement | Demo/editable/official config separation | ⏭ |
| T09 | P1 | Fiscal year assumptions calendar-year only | Enhancement | Add fiscalYearStartMonth/Day to settings | ⏭ |
| T10 | P1 | Business/tax updates bypass lifecycle events | Bug | Add updateBusinessTaxFields coordinator method | ⬜ |

---

# 5. Money / Time Primitives

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| M01 | P0 | ConvertedMoney.identity() treated as failed | Bug | Add isExactSuccess/isUsable; identity not failure | ⬜ |
| M02 | P0 | MoneyAmount uses raw Double (NaN/infinity) | Bug | Use minorUnits:Long or BigDecimal, reject NaN | ⏭ |
| M03 | P0 | CurrencyCode validation too loose | Bug | Require 3 uppercase letters, reject digits | ⬜ |
| M04 | P0 | PeriodKind timezone math broken | Bug | Zone-aware java.time, not Calendar.getInstance() | ⬜ |
| M05 | P0 | Two competing PeriodRange types exist | Bug | Deprecate domain.model variant, migrate to core | ⏭ |
| M06 | P0 | Money (BigDecimal) vs MoneyAmount (Double) split | Enhancement | Unify: MoneyAmount wraps BigDecimal | ⏭ |
| M07 | P1 | MoneyAggregate.failedTransactionCount misleading | Bug | Include transactionCount in ConversionFailure | ⬜ |
| M08 | P1 | ConvertedMoney.failed(reason) ignores reason | Bug | Add failureReason + failureMessage fields | ⬜ |
| M09 | P1 | Formatting is locale-sensitive, underspecified | Enhancement | Split: display/exportStable/accounting formatters | ⏭ |
| M10 | P1 | Direct wall-clock calls still exist | Bug | CI guard for System.currentTimeMillis/Instant.now/Date | ⬜ |
| M11 | P1 | Week-number helpers inconsistent | Bug | Separate getIsoWeekNumber/getAppCalendarWeekNumber | ⬜ |
| M12 | P1 | LAST_7_DAYS includes future remainder | Enhancement | Rename: TRAILING_7_DAYS_TO_NOW vs LAST_7_CALENDAR | ⬜ |
| M13 | P1 | Entity time sentinel contracts not type-safe | Enhancement | CreatedAt/UpdatedAt types enforcing non-zero | ⏭ |
| M14 | P1 | Raw Double money output models still dominate | Enhancement | Migration rule: no new bare Double in public API | ⏭ |

---

# Quick Wins (Actionable Now — ~20 items)

These follow established patterns (add timeProvider.now(), wrap in withTransaction, normalize strings, add guard, etc.) and can be done in batches:

## Batch 1 — Timestamp/Atomic fixes (~8 items)
- W04: Subscription recordedAt=0 → timeProvider.now()
- W07: Price change not atomic → withTransaction
- W18: Warranty timestamps unset → copy(createdAt=..., updatedAt=now)
- C02: Merchant timestamps not set → timeProvider.now()
- I02: Investment price update not atomic → withTransaction
- I07: Investment timestamps not enforced → add with validation
- W13: Manual correction insert silently fails → Long return
- C05: MerchantCategoryDao returns Unit → Long return

## Batch 2 — Currency/normalization guards (~6 items)
- W01: Warranty protected value → TODO MoneyAggregate
- W03: CurrencyCode validation → require 3 uppercase letters, no digits
- C03: Category name case-sensitive → normalize keys
- C04: Categorization cache stale → invalidate from writes
- W02: Return-window refund currency → infer from expense
- W05: Subscription divide by zero → coerce to 1.0

## Batch 3 — Privacy/wall-clock/TODOs (~6 items)
- W10: Device GPS not privacy-gated → PrivacyGate check
- W17: Cloud query sends raw text → CloudPayloadRedactor (ARCH-04)
- M10: Direct wall-clock CI guard → TODO/guard
- M08: ConvertedMoney.failed(reason) → add fields
- M01: identity() treated as failed → add isExactSuccess
- T07: Business CSV formula safety → sanitize

---

# Summary

| Category | P0 | P1 | Total |
|----------|-----|-----|-------|
| Warranty/Sub/Location/NLP | 17 | 18 | 35 |
| Analytical Engines | 6 | 10 | 16 |
| Categorization/Merchant | 5 | 9 | 14 |
| Groups/Investment/Tax | 10 | 15 | 25 |
| Money/Time Primitives | 6 | 8 | 14 |
| **TOTAL** | **40** | **65** | **105** |

| Status | Count |
|--------|-------|
| ⬜ NOT STARTED | 70 |
| ⏭ DEFERRED (needs design/migration) | 35 |
| ✅ FIXED | 0 |
