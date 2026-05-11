# Master Implementation Plan — Remaining Pipeline Issues

> Generated: 2026-05-11  
> Baseline debug reports: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
> Current HEAD: `92d77385` (DB v124)  
> Status: **45 FIXED / 8 PARTIAL / 76 TODO / 1 DEFERRED** out of 130 items  
> All 10 universal architectural contracts are FIXED or PARTIAL.

---

## Executive Summary

The 12 pipeline debug reports identified 130 issues (8 P0 + 112 P1 + 10 universal contracts). After aggressive refactoring (30+ commits since baseline), **45 are fully fixed** and **all universal contracts are in place**. The remaining **77 issues** are pipeline-specific hardening items that build on the existing infrastructure.

The remaining work clusters into **6 implementation waves**, ordered by impact and dependency:

| Wave | Theme | Issues | Effort | Priority |
|------|-------|--------|--------|----------|
| 1 | Currency/Dashboard/Analytics completion | 7 | High | P1 — user-visible wrong totals |
| 2 | Budget/Forecast/Cashflow hardening | 11 | High | P1 — wrong forecasts/alerts |
| 3 | Backup/Restore safety | 8 | Medium | P1 — data safety |
| 4 | Privacy/AI enforcement | 10 | Medium | P1 — privacy compliance |
| 5 | Recurring/Receipt/Notification pipeline-specific | 13 | Medium | P1 — feature correctness |
| 6 | Bank Integration + Workers + Export | 21 | Low-Med | P1 — mostly demo/stub + polish |

---

## Wave 1: Currency / Dashboard / Analytics (Pipeline 5)

**Why first:** These are user-visible wrong financial totals on the main screen. Every user sees the dashboard daily.

### Issues (7 remaining)

| ID | Issue | Fix Strategy |
|----|-------|-------------|
| P5-P1-01 | Historical totals use latest-rate conversion | Add `getHomeCurrencyPurchaseTotalHistorical()` using `convertAsOf(expense.date)` per row or per-day bucket |
| P5-P1-03 | Dashboard adapter drops MoneyAggregate/partial warnings | Extend `SpendingSummary` and `DashboardCategoryBreakdown` with `aggregate`, `isPartial`, `warningMessage` |
| P5-P1-04 | Weekly/daily totals drilldown returns empty | Wire `MultiCurrencyRepository.getHomeCurrencyWeeklyTotals()` into `TotalsAggregationEngine` |
| P5-P1-05 | Dashboard widgets raw-sum effectiveAmount | Build `DashboardNormalizedInput` using `AnalyticsCurrencyNormalizer`; all widgets consume normalized amounts |
| P5-P1-06 | Stale-rate not propagated to analytics quality | Make `AnalyticsCurrencyNormalizer` return typed `ConversionOutcome`; fill `staleRateCount` in `AnalyticsDataQuality` |
| P5-P1-07 | MCR inconsistent MoneyAggregateBuilder use | Replace manual aggregate helpers with `MoneyAggregateBuilder.fromBuckets()` everywhere |
| P5-P1-08 | Budget-vs-actual not fully normalized | Make `DashboardContractsAdapter.observeBudgetStatuses()` use `BudgetVsActualEngine` |

### Implementation Plan

**PR 5-1: Deterministic historical aggregate API**
- Add `MultiCurrencyRepository.getHomeCurrencyPurchaseTotalHistorical(start, end)` 
- Fetch per-currency totals, convert each with `convertAsOf(midpoint or per-expense date)`
- Rename current APIs to `*LatestRate` suffix
- Wire into `AnalyticsRepository.getSpendingSummary()`

**PR 5-2: Dashboard quality propagation**
- Extend `SpendingSummary`, `DashboardCategoryBreakdown` with `MoneyAggregate?`, `isPartial`, `warningMessage`
- Update `DashboardContractsAdapter` to preserve these fields
- UI cards show warning icon when `isPartial = true`

**PR 5-3: Fix totals drilldown**
- Replace empty-list returns in `TotalsAggregationEngine.getWeeklyTotals()`/`getDailyTotals()` with `MultiCurrencyRepository` safe APIs
- Convert `PeriodMoneyAggregate` → `PeriodTotal` with quality metadata

**PR 5-4: Normalize dashboard widget input**
- Create `DashboardNormalizedInput` in `DashboardDataProvider` using `AnalyticsInputAssembler`
- `ComputeDashboardWidgetsUseCase` consumes normalized amounts for trend/forecast/block-party/health

**PR 5-5: Stale/missing rate quality + MCR builder consistency**
- `AnalyticsCurrencyNormalizer` returns typed failures (MISSING vs STALE)
- Fill `staleRateCount` in `AnalyticsDataQuality`
- Replace manual MCR aggregate helpers with `MoneyAggregateBuilder.fromBuckets()`
- `BudgetVsActualEngine` becomes canonical budget comparison

---

## Wave 2: Budget / Forecast / Cashflow (Pipeline 6)

**Why second:** Forecasts and budgets directly affect user financial decisions. Wrong alerts or double-counted planned expenses are high-impact.

### Issues (11 remaining)

| ID | Issue | Fix Strategy |
|----|-------|-------------|
| P6-P1-03 | Budget/forecast/planned writes lack restore guard | Inject `DatabaseWriteBarrier` into `BudgetRepository`, `BudgetForecastingEngine`, `PlannedExpenseRepository` |
| P6-P1-04 | Budget alerts use gross percent | Recompute percent from `adjustedSpendBreakdown.effectiveSpend` in `BudgetMonitor` |
| P6-P1-05 | Rollover ignores partial conversion | Carry `MoneyAggregate` quality through rollover loop |
| P6-P1-06 | Budget limit uses current rate not period-specific | Add `convertBudgetLimitForStatus(budget, periodEnd)` using `convertAsOf` |
| P6-P1-08 | Planned expenses not normalized before forecast | Copy planned expenses with normalized amount/currency before `SynthesisEngine` |
| P6-P1-10 | Recurring occurrence status lost before forecast | Extend `ConfirmedOccurrence` with status; filter PLANNED-only in assembler |
| P6-P1-11 | Cash-flow raw-sums multi-currency | Inject `AnalyticsCurrencyNormalizer` into `CashFlowCalculator`; normalize before summing |
| P6-P1-12 | Cash-flow displays pre-dedup predictions | Change `predictedRecurring = deduplicatedPredicted` |
| P6-P1-13 | Stress forecast not real balance | Add `StressForecastMode` enum; label output as estimate |
| P6-P1-14 | Stress forecast counts PAID as active | Change `ACTIVE_OCCURRENCE_STATUSES` to `setOf("PLANNED")` only |
| P6-P1-15 | Delete budget fails with forecasts | Add `budgetForecastDao.deleteForecastsForBudget(id)` before budget delete, or archive |

### Implementation Plan

**PR 6-1: Restore barrier + budget alert fix**
- Add `writeBarrier.checkWritesAllowed()` to all budget/planned/forecast write methods
- Recompute `effectivePercent` from adjusted spend in `BudgetMonitor.processBudgetStatus()`

**PR 6-2: Forecast input hardening**
- Normalize planned expenses to home currency before `SynthesisEngine`
- Extend `ConfirmedOccurrence` with `status`; filter PLANNED-only
- Change stress forecast `ACTIVE_OCCURRENCE_STATUSES` to `setOf("PLANNED")`

**PR 6-3: Cash-flow currency normalization**
- Inject normalizer into `CashFlowCalculator`
- Normalize income/expenses/recurring before summing
- Return `deduplicatedPredicted` in `DailyCashFlow.predictedRecurring`

**PR 6-4: Budget conversion basis + rollover quality**
- Add `convertAsOf(periodEnd)` for budget limit in period reports
- Carry `isPartial`/warnings through rollover loop
- Add `StressForecastMode` enum and relabel output

**PR 6-5: Budget deletion semantics**
- `deleteBudget()` → `database.withTransaction { deleteForecastsForBudget(id); delete(budget) }`

---

## Wave 3: Backup / Restore Safety (Pipeline 7)

**Why third:** Data safety is critical but less frequently triggered than daily dashboard use.

### Issues (8 remaining)

| ID | Issue | Fix Strategy |
|----|-------|-------------|
| P7-P0-01 | Legacy `.db` import lacks journal/maintenance | Add DEBUG guard or wire through restore state machine |
| P7-P1-01 | Stale Room instance after swap | Use fresh `AppDatabase.fileBuilder()` for post-swap verification |
| P7-P1-02 | Maintenance mode not global barrier | Already have `DatabaseWriteBarrier`; ensure all remaining writers use it |
| P7-P1-03 | Backup doesn't freeze writes | Enter `BACKUP_EXPORTING` mode; checkpoint WAL; copy under barrier |
| P7-P1-04 | Receipt asset restore not atomic | Add `ASSETS_RESTORING` journal state; make idempotent |
| P7-P1-05 | Restore doesn't prove semantic equivalence | Add golden restore-equivalence integration tests |
| P7-P1-06 | Privacy audit optional in verification | Decide policy: Tier 1 or explicit manifest exclusion |
| P7-P1-08 | UI can dismiss restart-required | Make restart-required a global blocking state |

### Implementation Plan

**PR 7-1: Legacy import guard**
- Add `if (!BuildConfig.DEBUG) error("Legacy import disabled in release")` 
- Or wire through restore journal + maintenance mode

**PR 7-2: Fresh Room after swap**
- Create `RestoreDatabaseOpener` that builds fresh `AppDatabase` for verification
- Close fresh DB after verification; keep singleton invalid until restart

**PR 7-3: Backup snapshot consistency**
- Enter `BACKUP_EXPORTING` in `RestoreMaintenanceMode`
- Checkpoint WAL before copy
- Exit after bundle creation

**PR 7-4: Asset restore state machine**
- Add `ASSETS_RESTORING` → `ASSETS_RESTORED` journal transitions
- Make asset copy idempotent; resume on startup if interrupted
- Don't rollback valid DB for asset-phase crash

**PR 7-5: Semantic equivalence tests + restart enforcement**
- Add integration test fixture with expenses/receipts/recurring/budgets
- Backup → restore → compare dashboard totals/analytics/links
- Make restart-required block all navigation except restart prompt

---

## Wave 4: Privacy / AI Enforcement (Pipeline 8)

**Why fourth:** Privacy is important but most cloud AI is disabled by default. These are hardening items.

### Issues (10 remaining)

| ID | Issue | Fix Strategy |
|----|-------|-------------|
| P8-P1-02 | PrivacySettings vs AiSettings disagree | Create `EffectiveCloudAiPolicy` resolver; privacy is authoritative |
| P8-P1-03 | Audit logging noisy | Add `GateDecision.NotApplicable`; only composite gate writes final audit |
| P8-P1-04 | Audit context stores sensitive data | Replace raw map with structured `PrivacyAuditContext` |
| P8-P1-06 | Retention scope incomplete | Add retention targets for AI artifacts, chat, email, diagnostics |
| P8-P1-07 | Bank statement cloud sends raw prompt | Apply `CloudPayloadRedactor` inside `suggestFromText()` |
| P8-P1-08 | Redaction not purpose-aware | Define typed `CloudPayload` per purpose; providers accept `PreparedCloudPayload` |
| P8-P1-09 | Notification gate too late | Add cached `NotificationCaptureGate` with `StateFlow<Boolean>`; check before extraction |
| P8-P1-10 | Geocoding gate not statically guaranteed | Wrap all geocoding providers with `PrivacyAwareGeocodingService` |
| P8-P1-11 | Raw export reachable in release | Add `BuildConfig.DEBUG` guard to `exportDatabase()` |
| P8-P1-12 | Denied states not visible to users | Add `PrivacyBlocked` sealed interface; map to UI states |

### Implementation Plan

**PR 8-1: Unified cloud AI policy**
- Create `EffectiveCloudAiPolicy` from privacy settings (authoritative) + AI settings (routing only)
- Replace direct `settings.allowCloudAi` checks in all cloud services

**PR 8-2: Audit cleanup**
- Add `GateDecision.NotApplicable` to concrete gates
- Composite gate writes one final audit event
- Replace raw context map with `PrivacyAuditContext`

**PR 8-3: Retention expansion + bank redaction**
- Add `RetentionTarget` interface; register AI/email/chat/diagnostics targets
- Apply `CloudPayloadRedactor` inside `suggestFromText()`

**PR 8-4: Notification gate + geocoding guard**
- Add `NotificationCaptureGate` with cached `StateFlow`
- Wrap geocoding providers with privacy-aware decorator
- Add `BuildConfig.DEBUG` guard to raw export

---

## Wave 5: Recurring / Receipt / Notification Pipeline-Specific (Pipelines 1, 3, 4)

### Issues (13 remaining)

**Pipeline 1 (2 issues):**
| ID | Issue | Fix |
|----|-------|-----|
| P1-P1-05 | Privacy gate after extraction | Check cached privacy before reading extras |
| P1-P1-07 | Shutdown loses notifications | Split into durable raw insert + async processing; or remove dedupe key on cancel |

**Pipeline 3 (6 issues):**
| ID | Issue | Fix |
|----|-------|-----|
| P3-P1-03 | Matching not persisted | Add `matchAndPersist()` that calls `ReceiptLinkService` for AutoMatch |
| P3-P1-06 | Insert IGNORE conflict not checked | Add `require(id > 0)` or `insertOrResolve()` helper |
| P3-P1-07 | Currency fallback EUR | Pass `homeCurrency()` to `ReceiptParser.parse()` |
| P3-P1-08 | Parse failures classified wrong | Return `ReceiptProcessStage.PARSE_FAILED` when parser throws |
| P3-P1-09 | Batch import no reviews | Add `ReceiptProcessingOptions(createReview = true)` for batch |
| P3-P1-10 | Bank statement dedupe weak | Create `StatementTransactionDeduper` checking expenses + pending reviews |

**Pipeline 4 (5 issues):**
| ID | Issue | Fix |
|----|-------|-----|
| P4-P1-02 | Rule CRUD bypasses lifecycle | Create coordinator methods for create/update/delete/deactivate |
| P4-P1-04 | Reminder windows not applied | Resolve from rule/settings; don't require caller to pass |
| P4-P1-06 | Expense→occurrence not globally linked | Add `RecurringPaymentMatcher` to `TransactionSideEffectDispatcher` |
| P4-P1-07 | PAID downgraded by regeneration | Add `canAutoTransition()` guard; terminal statuses immutable |
| P4-P1-10 | Legacy markBillPaid mixed behavior | Delegate to coordinator or deprecate |

### Implementation Plan

**PR R-1: Receipt matching persistence + insert checks**
- `ReceiptSideEffectDispatcher.dispatchAfterSave()` calls `matchAndPersist()`
- AutoMatch → `ReceiptLinkService.linkReceiptToExpense()`
- Add `require(id > 0)` after all `ScannedReceiptDao.insert()` calls

**PR R-2: Receipt currency + parse status + batch reviews**
- Pass `homeCurrency()` to parser
- Return `PARSE_FAILED` status when parser throws
- Add `createReview` option to batch import

**PR R-3: Recurring rule lifecycle + payment matching**
- Create `RecurringRuleLifecycleCoordinator` with restore guard + timestamps + events
- Add `RecurringPaymentMatcher` port to `TransactionSideEffectDispatcher.dispatchOnCreated()`
- Add `canAutoTransition()` to materializer

**PR R-4: Notification shutdown safety + privacy gate**
- Add cached `NotificationCaptureGate` checked before extraction
- On cancellation, remove dedupe key or persist raw row first

---

## Wave 6: Bank Integration + Workers + Export (Pipelines 9, 10, 11, 12)

**Why last:** Bank integration is mostly demo/stub (not user-facing in production). Worker and export issues are lower-impact polish.

### Bank Integration (10 issues — mostly demo infrastructure)
These are largely **feature development**, not bug fixes. The bank pipeline is a prototype shell. Real implementation requires:
1. Bank connection repository/lifecycle coordinator
2. OAuth session model (state/PKCE/expiry)
3. Sync run ledger with per-transaction import status
4. Low-confidence review routing
5. Token refresh persistence
6. Multi-account model

**Recommendation:** Defer bank integration to a dedicated feature sprint. Current demo guard prevents production harm.

### Workers (6 remaining issues)
| ID | Issue | Fix |
|----|-------|-----|
| P9-P1-03 | Restore not a running-worker barrier | Add `checkpoint()` calls in worker loops |
| P9-P1-04 | Daily briefing chain breaks | Always schedule next in `finally` block |
| P9-P1-07 | Receipt matching runOnce not unique | Use `enqueueUniqueWork()` |
| P9-P1-08 | Receipt matching outcomes not durable | Write `ReceiptEvent` per receipt in worker |
| P9-P1-09 | Warranty sent-state in SharedPreferences | Move to Room `WarrantyReminderDelivery` table |
| P9-P1-10 | Worker registry asymmetric | Already have `WorkerRegistry`; ensure resume uses it |

### Export (5 remaining issues)
| ID | Issue | Fix |
|----|-------|-----|
| P12-P1-04 | Export snapshot not real | Add `export_snapshot_rows` temp table; anchor IDs at export start |
| P12-P1-05 | Exports plaintext, not privacy-gated | Check `PrivacyGate` before export; offer encryption |
| P12-P1-06 | Export drops many fields | Already have `ExportTransaction` v2; ensure generic CSV/JSON use all fields |
| P12-P1-07 | Receipt links not in exports | Add `receiptIds`/`primaryReceiptId`/`matchStatus` to export row |
| P12-P1-09 | PDF raw-sums mixed currency | Use `MoneyAggregate` for combined total; show per-currency only if multi |

### Email (4 remaining issues)
| ID | Issue | Fix |
|----|-------|-----|
| P11-P1-03 | Service path partially uses lifecycle | Collapse into single coordinator (already PARTIAL) |
| P11-P1-06 | Email source insert conflicts ignored | Check return value; return `DuplicateReceipt` |
| P11-P1-07 | Side effects skipped in service path | Call `dispatchAfterSave()` after email receipt save |
| P11-P1-08 | No review route for uncertain emails | Add confidence threshold; low → PendingReview |

---

## Cross-Cutting Patterns for All Waves

These patterns should be applied consistently across all PRs:

### 1. Restore/Write Barrier
```kotlin
writeBarrier.checkWritesAllowed("operation_name")
```
Already exists. Ensure every new write path uses it.

### 2. Timestamps
```kotlin
val now = timeProvider.now()
entity.copy(createdAt = now, updatedAt = now)
```
Never persist `createdAt = 0`.

### 3. Insert Conflict Handling
```kotlin
val id = dao.insert(entity)
require(id > 0) { "Insert conflict for $entity" }
```
Or use structured `InsertResult` sealed class.

### 4. Lifecycle Events
Every state transition writes a durable event to the appropriate event table.

### 5. Privacy at Write Time
Use `RawContentSanitizer` with `RawStorageMode` before persisting sensitive text.

### 6. MoneyAggregate for Financial Totals
Never raw-sum `effectiveAmount` across currencies. Always use `MoneyAggregate` or `AnalyticsCurrencyNormalizer`.

---

## Recommended Execution Order

```
Week 1: Wave 1 (Currency/Dashboard) — 5 PRs
Week 2: Wave 2 (Budget/Forecast) — 5 PRs  
Week 3: Wave 3 (Backup/Restore) — 5 PRs
Week 4: Wave 4 (Privacy/AI) — 4 PRs
Week 5: Wave 5 (Recurring/Receipt/Notification) — 4 PRs
Week 6: Wave 6 (Workers/Export/Email) — selective PRs
```

Bank integration deferred to dedicated feature sprint.

---

## Definition of Done (per wave)

- All targeted issues move from 📝 TODO to ✅ FIXED in master tracker
- Unit tests cover each fix
- `./gradlew testDebugUnitTest` passes
- No new raw-currency-sum or missing-restore-guard regressions
- Master tracker updated with commit references

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Wave 1 changes dashboard behavior | Add feature flag for historical vs latest rate mode |
| Wave 2 changes forecast numbers | Compare before/after for existing test fixtures |
| Wave 3 backup format changes | Maintain backward compatibility; version manifest |
| Wave 6 bank integration scope creep | Keep demo guard; defer to feature sprint |
| DB migrations accumulate | Batch migrations per wave; currently at v124 |

---

## Files Most Frequently Modified

These files appear across multiple waves and should be handled carefully:

1. `MultiCurrencyRepository.kt` — Waves 1, 2
2. `CurrencyConverter.kt` — Waves 1, 2
3. `TransactionSideEffectDispatcher.kt` — Waves 2, 5
4. `RestoreMaintenanceMode.kt` — Waves 2, 3
5. `PrivacySettingsRepositoryImpl.kt` — Wave 4
6. `BudgetRepository.kt` — Wave 2
7. `CashFlowCalculator.kt` — Wave 2
8. `ReceiptLifecycleCoordinator.kt` — Wave 5
9. `RecurringLifecycleCoordinator.kt` — Wave 5
10. `ExportOptionsViewModel.kt` — Wave 6
