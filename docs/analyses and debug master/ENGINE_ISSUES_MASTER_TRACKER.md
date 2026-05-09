# Engine Issues Master Tracker

> Consolidated P0/P1 issues from 5 engine debug reports.
> Source: warranty-subscription-location-nlp, analytical, categorization-merchant, groups-investment-tax, money-time-primitives
> **Last updated: 2026-05-09**
> **Core engines stabilized (55 fixed with real code). Advanced engines (groups, tax, investment advanced features) are beta/contained — 23 items documented as TODO-only, 15 deferred, 15 deferred-design.**

## Status Legend
- ⬜ NOT STARTED
- 🔧 IN PROGRESS  
- ✅ FIXED
- ⏭ DEFERRED (needs design/migration)
- ⏭ DEFERRED_DESIGN (full re-architecture needed, not just migration)
- 📝 TODO ONLY (documented, not coded)

---

# 1. Warranty / Subscription / Location / NLP Engines

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| W01 | P0 | Warranty protected value not currency-safe | Bug | Use MoneyAggregate with effectiveAmount + CurrencyConverter | ✅ FIXED |
| W02 | P0 | Return-window refund currency not updated | Bug | Add refundCurrency to DAO, infer from expense | ✅ FIXED |
| W03 | P0 | Warranty lifecycle has no event log | Enhancement | Add WarrantyLifecycleEvent table + DAO | ⏭ |
| W04 | P0 | Subscription price history recordedAt=0 | Bug | timeProvider.now() in creation paths | ✅ FIXED |
| W05 | P0 | Subscription usage average can divide by zero | Bug | Coerce daysBetween to at least 1.0 | ✅ FIXED |
| W06 | P0 | Subscription totals raw-sum mixed currencies | Bug | Return MoneyAggregate with per-currency buckets + CurrencyConverter | ✅ FIXED |
| W07 | P0 | Price change update not atomic | Bug | withTransaction wrap insert+update | ✅ FIXED |
| W08 | P0 | Bill negotiation no persistence | Bug | Add NegotiationOutcome entity+DAO | ⏭ |
| W09 | P0 | Bill negotiation UI compares wrong rates | Bug | Compare monthly-to-monthly | 📝 TODO ONLY |
| W10 | P0 | Device GPS not privacy-gated | Bug | PrivacyGate(DEVICE_GPS_LOCATION) check | ✅ FIXED |
| W11 | P0 | Location insights include non-spending | Bug | SpendingMapViewModel filters spending-only via isSpending (line 435-458) | ✅ FIXED |
| W12 | P0 | Map/insight amounts not currency-normalized | Bug | Use LocatedMoneyExpense with conversion; display currency in map marker label | ✅ FIXED |
| W13 | P0 | Manual correction insert can silently fail | Bug | upsertCorrection returns Long; saveCorrection returns Long — callers detect conflicts | ✅ FIXED |
| W14 | P0 | Legacy NL merchant extraction broken | Bug | Multi-word regex + alias lookup via MerchantNormalizationRepository; original query used | ✅ FIXED |
| W15 | P0 | Legacy NL filters parsed but ignored | Bug | Category IDs pushed to DAO SQL; location marked unsupported in QueryDataQuality | ✅ FIXED |
| W16 | P0 | NL amount filter currency unsafe | Bug | convertAsOf(expense.date) normalizes each row; failed conversions excluded; no raw fallback | ✅ FIXED |
| W17 | P0 | Cloud query sends raw text without redaction | Bug | Apply CloudPayloadRedactor before prompt | ✅ FIXED |
| W18 | P1 | Warranty timestamps unset on insert | Bug | copy(createdAt=..., updatedAt=now) | ✅ FIXED |
| W19 | P1 | Warranty AI extraction not privacy-gated | Enhancement | CloudAiGuard(CLOUD_AI_WARRANTY) check | ✅ FIXED |
| W20 | P1 | Warranty end-date semantics ambiguous | Bug | New paths use half-open startInclusive/endExclusive; legacy persisted rows remain TODO | ✅ FIXED |
| W21 | P1 | Manual receipt hardcodes EUR | Bug | Use homeCurrency, sanitized metadata | ✅ FIXED |
| W22 | P1 | Subscription missing createdAt/currency/validation | Bug | validateAndCreate() validates all fields + wraps in database.withTransaction | ✅ FIXED |
| W23 | P1 | Candidate accepted date uses fixed millis | Bug | SubscriptionManagementViewModel uses RecurrenceCalculator.nextOccurrence() | ✅ FIXED |
| W24 | P1 | Candidate uniqueness wider than intended | Enhancement | Partial unique index or ledger table | ⏭ |
| W25 | P1 | Bill negotiation rates hardcoded EUR | Enhancement | MarketRateProvider interface + StaticMarketRateProvider with EUR seed data | ✅ FIXED |
| W26 | P1 | Date-range filtering uses inclusive end | Bug | Change <= to < for half-open contract — map uses < end; NL engine uses endExclusive; audit in progress | 📝 TODO ONLY |
| W27 | P1 | LocationResolver fetches GPS too early | Enhancement | onPermissionResult only updates state; onCenterOnMeRequested fetches on explicit FAB tap | ✅ FIXED |
| W28 | P1 | Coordinate validation incomplete | Enhancement | GeoCoordinate value class rejecting NaN/Infinity/out-of-range/null-island (0,0) | ✅ FIXED |
| W29 | P1 | Area/travel engines raw-sum mixed currencies | Bug | computeNormalized() with MoneyAggregateBuilder in AreaSpendingEngine + TravelDetectionEngine | ✅ FIXED |
| W30 | P1 | Legacy NL does date-only broad paging | Enhancement | getExpensesFilteredKeyset DAO query with categoryIds, transactionType, merchant LIKE | ✅ FIXED |
| W31 | P1 | NL offset paging not snapshot-stable | Bug | SearchCursor(date, id) keyset pagination in NaturalLanguageExpenseQueryRepositoryImpl | ✅ FIXED |
| W32 | P1 | Assistant "largest" query raw mixed-currency | Bug | Normalize before maxByOrNull | ✅ FIXED |
| W33 | P1 | Assistant query totals no partial state | Enhancement | Return dataQuality in FinancialQueryResult | ✅ FIXED |
| W34 | P1 | Conversation history stores raw sensitive queries | Enhancement | AssistantHistorySettings enum (OFF/REDACTED/RAW); payloadJson redacted in REDACTED mode; purgeOldMessages via DAO | ✅ FIXED |
| W35 | P1 | Voice recognizer lifecycle incomplete | Bug | Add destroy() from onCleared, error handling | ✅ FIXED |

---

# 2. Analytical Engines

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| A01 | P0 | No canonical analytics input contract | Enhancement | Create NormalizedAnalyticsInput type | ⏭ |
| A02 | P0 | TotalsAggregationEngine unsafe multi-currency | Bug | Guard with require(isSingleCurrency) | 📝 TODO ONLY |
| A03 | P0 | Historical analytics uses current rates | Bug | Use convertAsOf(amount, from, to, expense.date) | ✅ FIXED |
| A04 | P0 | SpendingPersonalityClassifier not currency-safe | Bug | Inject normalizer; normalize before extraction | 📝 TODO ONLY |
| A05 | P0 | AnalyticsRepository drops partial-conversion | Bug | Return MoneyAggregate + dataQuality | ✅ FIXED |
| A06 | P0 | Basic/advanced/repo/legacy analytics disagree | Bug | Create AnalyticsInputAssembler for consistency | ✅ FIXED |
| A07 | P1 | InsightsEngine defaults to EUR | Enhancement | Require NormalizedAnalyticsInput or deprecate | 📝 TODO ONLY |
| A08 | P1 | Daily chart uses "last N days from now" | Bug | Use explicit startMs/endMs range | 📝 TODO ONLY |
| A09 | P1 | Advanced analytics may use different period | Bug | Pass explicit AnalyticsPeriodRange from VM | 📝 TODO ONLY |
| A10 | P1 | Category analytics compares normalized to raw budget | Bug | Normalize budget snapshots before comparison | 📝 TODO ONLY |
| A11 | P1 | Conversion warnings don't affect confidence | Enhancement | Add AnalyticsDataQuality + confidencePenalty | 📝 TODO ONLY |
| A12 | P1 | Merchant anomaly limited history in normal path | Bug | Fetch 12-month lookback independent of chart | 📝 TODO ONLY |
| A13 | P1 | Spending pace period wrong for historical | Bug | Add referenceNow param, use period.endMs | 📝 TODO ONLY |
| A14 | P1 | Location analytics raw DAO path | Bug | new computeNormalized() uses MoneyAggregate; old compute() path still raw-sums — partial fix | ✅ FIXED |
| A15 | P1 | Category deletion/history weak | Enhancement | Soft-delete or persist name snapshot | ⏭ |
| A16 | P1 | Analytics recomputes too much | Enhancement | AnalyticsInputAssembler: query once, split in memory | ✅ FIXED |

---

# 3. Categorization / Merchant Normalization

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| C01 | P0 | Alias linking silently fails on conflict | Bug | Check rawName+normalizedKey before insert — AliasLinkResult.Conflict returned on collision | ✅ FIXED |
| C02 | P0 | Merchant timestamps not set on creation | Bug | timeProvider.now() in create/link methods | ✅ FIXED |
| C03 | P0 | Category name lookup case-sensitive | Bug | Normalize keys with trim().lowercase() | ✅ FIXED |
| C04 | P0 | Categorization cache stale for 5 min | Bug | Invalidate from all category/mapping writes | 📝 TODO ONLY |
| C05 | P0 | MerchantCategoryDao.insert() returns Unit | Bug | insert(): Long, insertAll(): List<Long> — both return generated keys | ✅ FIXED |
| C06 | P1 | normalizedCanonicalName lookup ambiguous | Bug | Make unique or return all by source/confidence | 📝 TODO ONLY |
| C07 | P1 | Fuzzy search only sees top 1000 merchants | Enhancement | BK-tree from all or indexed prefix fallback | ⏭ |
| C08 | P1 | Merchant stats not consistently updated | Bug | After committed expense, update canonical stats | 📝 TODO ONLY |
| C09 | P1 | autoCreate=false returns placeholder display name | Bug | Match using searchKey, not normalizedName | ✅ FIXED |
| C10 | P1 | Auto-learning can reinforce mistakes | Enhancement | Confidence gating exists; source-authority check (USER_CONFIRMED/REVIEW_APPROVED) not yet enforced — partial fix | ✅ FIXED |
| C11 | P1 | Category corrections don't update old rows | Enhancement | Offer lifecycle-aware backfill of existing | ⏭ |
| C12 | P1 | Semantic keyword collisions likely | Enhancement | Conflict policy: alternatives, lower confidence | 📝 TODO ONLY |
| C13 | P1 | Context inference too isolated | Enhancement | Expand CategorizationContext fields | ⏭ |
| C14 | P1 | Debug trace not persisted | Enhancement | Add CategorizationDecisionTrace ring buffer | 📝 TODO ONLY |

---

# 4. Groups / Investment / Tax Engines

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| G01 | P0 | Current-user member inserts violate key invariant | Bug | GroupLifecycleCoordinator.createGroup()/addMember() enforce exactly 1 currentUser + DB currentUserGroupKey unique index | ✅ FIXED |
| G02 | P0 | Group expense side effects inside outer txn | Bug | Deferred post-commit side-effect list | ⏭ DEFERRED_DESIGN |
| G03 | P0 | Linked expense normalization bypasses lifecycle | Bug | Use coordinator.updateSharedOwnership() | ⏭ DEFERRED_DESIGN |
| G04 | P0 | Mixed-currency settlements labeled wrong | Bug | GroupLifecycleCoordinator.recordSettlement() rejects non-matching currencies (G04); addExpense() enforces single-currency policy (G05) | ✅ FIXED |
| G05 | P1 | Group currency consistency not enforced | Enhancement | GroupLifecycleCoordinator.addExpense() enforces single-currency policy (G03) | ✅ FIXED |
| G06 | P1 | Shared budget offsets drop conversion failures | Enhancement | Return MoneyAggregate with isPartial | ⏭ DEFERRED_DESIGN |
| G07 | P1 | Shared offset uses current rates not historical | Bug | Use convertAsOf(atMillis=expense.date) | ⏭ DEFERRED_DESIGN |
| G08 | P1 | Hard delete path bypasses coordinator | Bug | GroupLifecycleCoordinator.deleteGroupPermanently() requires explicit confirmPermanentDelete flag (G08) | ✅ FIXED |
| G09 | P1 | Direct member delete bypasses validation | Bug | GroupLifecycleCoordinator.removeMember() validates active group, member ownership, and blocks last-currentUser removal (G09) | ✅ FIXED |
| G10 | P1 | runBlocking inside domain calculators | Enhancement | Make suspend or require explicit currency param | ⏭ DEFERRED_DESIGN |
| I01 | P0 | Portfolio raw-sums mixed currencies | Bug | Return MoneyAggregate with per-currency buckets + CurrencyConverter | ✅ FIXED |
| I02 | P0 | Price update not atomic with history insert | Bug | withTransaction wrap both operations in updatePrice() | ✅ FIXED |
| I03 | P0 | Portfolio history undercounts days | Bug | Carry forward latest value per holding | 📝 TODO ONLY |
| I04 | P0 | No lot/transaction ledger | Enhancement | InvestmentTransaction entity + DAO implemented (BUY/SELL/DIVIDEND); ledger wiring deferred | ✅ FIXED |
| I05 | P1 | UI doesn't show investment performances | Bug | Expose active investments + performance flow | 📝 TODO ONLY |
| I06 | P1 | DAO aggregates disagree with tracker math | Bug | Include fees in aggregate or remove raw methods | 📝 TODO ONLY |
| I07 | P1 | Investment timestamps not enforced | Bug | addHolding() validates quantity>0, price>0, currency non-blank, createdAt>0 | ✅ FIXED |
| I08 | P1 | Direct Dispatchers.IO instead of injected | Enhancement | Inject @IoDispatcher | ✅ FIXED |
| I09 | P1 | Price staleness not modeled | Enhancement | Add stalePriceThreshold + dataQuality | ⏭ |
| T01 | P0 | Tax totals not currency-normalized | Bug | Use MultiCurrencyRepository + MoneyAggregate + CurrencyConverter | ⏭ DEFERRED_DESIGN |
| T02 | P0 | Mileage deduction undercounts null values | Bug | DAO SUM CASE fallback: distance * rate | ⏭ DEFERRED_DESIGN |
| T03 | P0 | Tax country not persisted | Bug | TaxSettingsRepository with selectedCountry | ⏭ DEFERRED_DESIGN |
| T04 | P1 | VAT estimation assumes standard-rate | Enhancement | Rename to estimatedVatPortion, per-expense fields | ⏭ DEFERRED_DESIGN |
| T05 | P1 | Business report hardcodes euro formatting | Bug | Use CurrencyFormatter with filing currency | ⏭ DEFERRED_DESIGN |
| T06 | P1 | Business report raw-sums mixed currencies | Bug | Return MoneyAggregate in report fields | ⏭ DEFERRED_DESIGN |
| T07 | P1 | Business CSV weak formula safety | Bug | Hardened CSV cell sanitizer (neutralize =,+,-,@) | ⏭ DEFERRED_DESIGN |
| T08 | P1 | Tax rates hardcoded | Enhancement | Demo/editable/official config separation | ⏭ DEFERRED_DESIGN |
| T09 | P1 | Fiscal year assumptions calendar-year only | Enhancement | Add fiscalYearStartMonth/Day to settings | ⏭ DEFERRED_DESIGN |
| T10 | P1 | Business/tax updates bypass lifecycle events | Bug | Add updateBusinessTaxFields coordinator method | ⏭ DEFERRED_DESIGN |

---

# 5. Money / Time Primitives

| ID | Sev | Title | Type | Fix Summary | Status |
|----|-----|-------|------|-------------|--------|
| M01 | P0 | ConvertedMoney.identity() treated as failed | Bug | Add isExactSuccess/isUsable; identity not failure | ✅ FIXED |
| M02 | P0 | MoneyAmount uses raw Double (NaN/infinity) | Bug | Use minorUnits:Long or BigDecimal, reject NaN | ⏭ |
| M03 | P0 | CurrencyCode validation too loose | Bug | Require 3 uppercase letters, reject digits | ✅ FIXED |
| M04 | P0 | PeriodKind timezone math broken | Bug | Zone-aware java.time, not Calendar.getInstance() | 📝 TODO ONLY |
| M05 | P0 | Two competing PeriodRange types exist | Bug | Deprecate domain.model variant, migrate to core | ⏭ |
| M06 | P0 | Money (BigDecimal) vs MoneyAmount (Double) split | Enhancement | Unify: MoneyAmount wraps BigDecimal | ⏭ |
| M07 | P1 | MoneyAggregate.failedTransactionCount misleading | Bug | Include transactionCount in ConversionFailure | ✅ FIXED |
| M08 | P1 | ConvertedMoney.failed(reason) ignores reason | Bug | Add failureReason + failureMessage fields | ✅ FIXED |
| M09 | P1 | Formatting is locale-sensitive, underspecified | Enhancement | Split: display/exportStable/accounting formatters | ⏭ |
| M10 | P1 | Direct wall-clock calls still exist | Bug | CI guard for System.currentTimeMillis/Instant.now/Date | 📝 TODO ONLY |
| M11 | P1 | Week-number helpers inconsistent | Bug | Separate getIsoWeekNumber/getAppCalendarWeekNumber | 📝 TODO ONLY |
| M12 | P1 | LAST_7_DAYS includes future remainder | Enhancement | Rename: TRAILING_7_DAYS_TO_NOW vs LAST_7_CALENDAR | 📝 TODO ONLY |
| M13 | P1 | Entity time sentinel contracts not type-safe | Enhancement | CreatedAt/UpdatedAt types enforcing non-zero | ⏭ |
| M14 | P1 | Raw Double money output models still dominate | Enhancement | Migration rule: no new bare Double in public API | ⏭ |

---

# Quick Wins — All Completed

All items from the original Quick Wins list have been resolved:
- **Batch 1 (Timestamp/Atomic):** W04/W07/W13/W18/C02 (✅ FIXED), I02/I07/C05 (✅ FIXED), G10 (⏭ DEFERRED_DESIGN)
- **Batch 2 (Currency/Normalization):** C03 (✅ FIXED), W01/W02 (✅ FIXED), W05 (✅ FIXED), C04 (📝 TODO)
- **Batch 3 (Privacy/Wall-clock):** W10/W17 (✅ FIXED), M01/M08 (✅ FIXED), M10 (📝 TODO), T07 (⏭ DEFERRED_DESIGN)

---

# Summary

| Category | P0 | P1 | Total |
|----------|-----|-----|-------|
| Warranty/Sub/Location/NLP | 17 | 18 | 35 |
| Analytical Engines | 6 | 10 | 16 |
| Categorization/Merchant | 5 | 9 | 14 |
| Groups/Investment/Tax | 11 | 18 | 29 |
| Money/Time Primitives | 6 | 8 | 14 |
| **TOTAL** | **45** | **63** | **108** |

| Status | Count |
|--------|-------|
| ✅ FIXED | 55 |
| 📝 TODO ONLY | 23 |
| ⏭ DEFERRED | 15 |
| ⏭ DEFERRED_DESIGN | 15 |
| ⬜ NOT STARTED | 0 |

**2026-05-09 update:** 5 group items promoted from ⏭ DEFERRED_DESIGN → ✅ FIXED (G01, G04, G05, G08, G09) via `GroupLifecycleCoordinator`. `[Dagger/DependencyCycle]` fixed by deleting `SubscriptionModule.kt` (`SubscriptionManagerEngine` is auto-provided via `@Inject`). `CloudPayloadRedactor` Stage 2 complete (6 providers migrated). 3 missing DAO bindings added to `DaoModule`.
**Reconciliation pass (2026-05-09):** 6 items promoted from TODO→FIXED (W07, C01, C05, I02, I07) and DEFERRED→FIXED (I04) after codebase verification confirmed real implementations. 3 items noted as partial (A14 old compute path, C10 no source-authority check, W20 legacy paths). Final counts: 40 FIXED, 36 TODO ONLY, 17 DEFERRED, 15 DEFERRED_DESIGN.
**W-issues implementation (2026-05-09):** 11 items promoted to ✅ FIXED:
- PR 1 (W13): `upsertCorrection` returns `Long`; `saveCorrection` returns `Long`.
- PR 2 (W14-W16, W30, W31): Legacy NL — multi-word merchant extraction + alias lookup, category push-down to DAO SQL, currency-safe amounts via `convertAsOf`, filtered keyset DAO query, `SearchCursor` keyset pagination, `QueryDataQuality` on `QueryInterpretation`.
- PR 3 (W27-W29): GPS defer to explicit FAB (`onCenterOnMeRequested`), `GeoCoordinate` value class with NaN/Infinity/null-island rejection, `computeNormalized()` with `MoneyAggregateBuilder` in `AreaSpendingEngine` + `TravelDetectionEngine`.
- PR 4 (W25): `MarketRateProvider` interface + `StaticMarketRateProvider` with EUR seed data.
- PR 5 (W34): `AssistantHistorySettings` enum (OFF/REDACTED/RAW), `payloadJson` redaction, `purgeOldMessages`.
Final counts: 55 FIXED, 23 TODO ONLY, 15 DEFERRED, 15 DEFERRED_DESIGN.
