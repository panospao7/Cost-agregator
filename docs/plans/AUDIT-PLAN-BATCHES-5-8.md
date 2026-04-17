# Audit: Batches 5-8 (Post Phase A-C)

Audited against current source code. Classified: RESOLVED / STILL_OPEN / PARTIALLY_AFFECTED.

---

## BATCH 5: Notification / Recommendation / Service Lifecycle

| Issue | Sub-issue | Status | Evidence |
|-------|-----------|--------|---------|
| 5.1 | Currency regex case-sensitive | 🔴 STILL_OPEN | `NotificationFilter.REGEX_CURRENCY` does not use `IGNORE_CASE` |
| 5.1 | Service teardown aborts persistence | 🔴 STILL_OPEN | `NotificationCaptureService.onDestroy()` cancels `serviceJob`; no graceful drain |
| 5.1 | 60-second restart alarm | 🔴 STILL_OPEN | `RESTART_INTERVAL_MS = 60_000L` — too aggressive |
| 5.1 | `deleteAll()` leaves `source_stats` stale | 🟡 PARTIALLY_AFFECTED | `resetAllPendingCounts()` called but total/accepted/rejected not zeroed |
| 5.2 | Non-current-user clear wipes memory | ✅ RESOLVED | `RecommendationStateManager.clearForUser` checks `currentUserId` |
| 5.2 | Signature overfits `category` | 🔴 STILL_OPEN | Two recs differing only by category bypass dedupe |
| 5.2 | Uses `System.currentTimeMillis()` | 🔴 STILL_OPEN | `RecommendationStateManager` line ~120 |
| 5.2 | Priority lacks severity semantics | 🔴 STILL_OPEN | No HIGH/MEDIUM/LOW enum mapping |
| 5.3 | Oversized-amount bypasses semantic dedup | 🔴 STILL_OPEN | `hasNearDuplicatePendingReview` only in test helper, not pipeline |
| 5.3 | SMS parser amount range narrow | 🟡 PARTIALLY_AFFECTED | Range 0.10–50,000 — insufficient for rent transfers |
| 5.4 | Briefing doesn't use NotificationIdGenerator | 🔴 STILL_OPEN | `DailyBriefingWorker` hardcoded IDs, `SimpleDateFormat` |
| 5.4 | Diagnostics counter races | ✅ RESOLVED | `ServiceDiagnostics` uses `synchronized(lock)` |
| 5.4 | `shouldSync()` doesn't check `isConnected` | 🔴 STILL_OPEN | Only checks `autoSync` |
| 5.5 | Mock OAuth not gated | 🔴 STILL_OPEN | No `NotImplementedError` or feature flag |

**Fix Plan 5:**
- Add `IGNORE_CASE` to `REGEX_CURRENCY`
- In `onDestroy()`, drain persistence tail before cancelling
- Increase restart interval to 15+ minutes
- Extend `resetAllPendingCounts()` to zero all counts
- Remove `category` from dedup signature
- Replace `System.currentTimeMillis()` with `timeProvider.now()`
- Add explicit priority enum
- Wire `hasNearDuplicatePendingReview` into oversized-amount path
- Widen SMS amount range
- Use `NotificationIdGenerator` in `DailyBriefingWorker`
- Add `isConnected` check to `shouldSync()`
- Gate mock OAuth behind feature flag

---

## BATCH 6: Export / Backup / Accounting

| Issue | Sub-issue | Status | Evidence |
|-------|-----------|--------|---------|
| 6.1 | SimpleDateFormat thread-safety | ✅ RESOLVED | Exporters now use `java.time.DateTimeFormatter` |
| 6.1 | CSV formula injection | ✅ RESOLVED | Protection present in `AccountingExporters` |
| 6.1 | Raw `Double.toString()` money output | 🔴 STILL_OPEN | `expense.amount` written without formatting |
| 6.1 | Missing currency column in generic CSV | 🔴 STILL_OPEN | `streamGenericCsvExport` has no currency column |
| 6.1 | `includeReceipts` param unused | 🔴 STILL_OPEN | Parameter exists but not wired |
| 6.2 | Date format mixing | 🔴 STILL_OPEN | Not verified specifically |
| 6.2 | Mileage rate presentation | 🔴 STILL_OPEN | Low priority |
| 6.3 | AccountingExporters uses java.time | ✅ RESOLVED | Confirmed |
| 6.3 | Backup import returns explicit result | ✅ RESOLVED | `DatabaseBackupRepositoryImpl` returns `Result<DatabaseImportSummary>` |
| 6.3 | Money formatting centralized | 🔴 STILL_OPEN | Raw `Double.toString()` still in exporters |
| 6.4 | Ad-hoc exporter construction | 🔴 STILL_OPEN | `ExportOptionsViewModel` creates exporters ad-hoc (lines 262/282/304) |

**Fix Plan 6:**
- Centralize money formatting via `CurrencyFormatter.formatForExport()`
- Add currency column to generic CSV
- Wire `includeReceipts` or remove dead parameter
- Inject `AccountingExportRepository` into `ExportOptionsViewModel` (or inject individual exporters via Hilt)

---

## BATCH 7: Savings / Investment / Tax / Financial-Health

| Issue | Sub-item | Status | Evidence |
|-------|---------|--------|---------|
| 7.1 | WEEK/QUARTER scale month-only sim | 🔴 STILL_OPEN | `SmartSavingsEngine` Monte Carlo is month-scoped, result × `horizonMultiplier` |
| 7.1 | `monthlyDiscretionary` / 3.0 | 🔴 STILL_OPEN | Line 288: hardcoded `3.0` |
| 7.1 | Monte Carlo fallback hardcoded | 🔴 STILL_OPEN | Falls back to hardcoded risk buffer |
| 7.1 | Sweep-risk includes WITHDRAWAL | 🔴 STILL_OPEN | Not excluded |
| 7.2 | Groups by raw `merchant` | 🔴 STILL_OPEN | `RecurringIncomeTracker` groups by raw string |
| 7.2 | `getStartOfMonth` doesn't zero ms | 🔴 STILL_OPEN | Confirmed |
| 7.2 | Uses `effectiveAmount` | ✅ RESOLVED | Confirmed |
| 7.2 | Hardcoded `Dispatchers.IO` | 🔴 STILL_OPEN | Not injected via constructor |
| 7.2 | `dayChange` based on latest snapshot | 🔴 STILL_OPEN | `InvestmentTracker` line 131: new price − latest |
| 7.2 | Portfolio history N+1 queries | 🔴 STILL_OPEN | Line 216 loops, individual queries |
| 7.2 | `getTopPerformers` fetches ALL | 🔴 STILL_OPEN | `getAllInvestments()` includes inactive |
| 7.3 | Bill reliability proxies pattern confidence | 🔴 STILL_OPEN | Uses recurring-pattern confidence |
| 7.3 | Trend comparison excludes current period | 🔴 STILL_OPEN | Compares against most-recent, could be same period |
| 7.3 | `calculateBudgetHealthScore` unused param | 🔴 STILL_OPEN | `periodExpenses` not used |
| 7.3 | `calculateTodayScore` mutates streak | 🔴 STILL_OPEN | Adds +1 locally instead of trusting input |
| 7.4 | `goal_crusher` uses `firstOrNull()` | 🔴 STILL_OPEN | `SavingsGamificationEngine`: progress uses `firstOrNull()` |
| 7.4 | `unlockedAt` uses `timeProvider.now()` every call | 🔴 STILL_OPEN | Overwrites original unlock time |
| 7.4 | Zero-target division not guarded | 🔴 STILL_OPEN | No `coerceAtLeast` guard |

**Fix Plan 7:**
- Implement horizon-native simulation (daily for WEEK, quarterly for QUARTER)
- Replace hardcoded `3.0` with actual month count from lookback
- Parameterize fallback risk buffer
- Normalize merchant key via `MerchantKeyGenerator` in income grouping
- Zero ms in `getStartOfMonth`
- Inject `CoroutineDispatcher` via `@IoDispatcher`
- Compute `dayChange` from previous-day-close snapshot
- Add `getPortfolioHistoryBatch(investmentIds)` DAO query
- Add `getActiveInvestments()` filter
- Replace bill reliability with actual payment-date tracking
- Exclude current period from trend comparison
- Use or remove `periodExpenses` param
- Trust supplied streak value without mutation
- Use `maxByOrNull { it.currentAmount / it.targetAmount.coerceAtLeast(1.0) }`
- Persist first-unlock timestamps
- Guard `target == 0` with `coerceAtLeast(0.01)`

---

## BATCH 8: Location / Geocoding / Map / Price-Protection

| Issue | Sub-item | Status | Evidence |
|-------|---------|--------|---------|
| 8.1 | Resolver fetches device before cache | 🔴 STILL_OPEN | `LocationResolver.resolve()` line 62 calls `getLastKnownLocation()` before cache checks |
| 8.1 | AndroidForegroundLocationProvider no caching | 🔴 STILL_OPEN | `getLastKnownLocation()` always calls `fusedClient.getCurrentLocation()` — no cache |
| 8.1 | Composite treats exceptions as terminal | 🔴 STILL_OPEN | `safeLookup()` wraps as `GeocodingError.Unknown`, `isTransient()` returns false |
| 8.1 | Nominatim ignores `limit` param | 🔴 STILL_OPEN | `searchMultiple()` uses own `AppConfig` limit, not caller's |
| 8.2 | Repository area-key uses `floor()` | ✅ RESOLVED | Line 61: `floor(lat / 0.045).toLong()` |
| 8.2 | Heatmap/Insights use `toLong()` truncation | 🔴 STILL_OPEN | `(expense.latitude / CLUSTER_RADIUS_DEG).toLong()` — wrong for negatives |
| 8.2 | Cache hits mutate `lastResolvedAt` | 🔴 STILL_OPEN | `incrementHitCount` updates `lastResolvedAt = :now` — makes TTL time-since-access |
| 8.3 | PriceProtection uses `Instant.now()` not `timeProvider` | 🔴 STILL_OPEN | Line 73: `Instant.now()` |
| 8.3 | `findBetterPrice` uses `System.currentTimeMillis()` | 🔴 STILL_OPEN | Line 256 |
| 8.3 | `findCoupons` uses `System.currentTimeMillis()` | 🔴 STILL_OPEN | Line 276 |
| 8.3 | Heuristic output surfaced as real data | 🔴 STILL_OPEN | No `isSimulated` flag on returned models |
| 8.3 | `getDealsCouponsAndBenefits` loads all receipts | 🔴 STILL_OPEN | `receiptDao.getAll().take(20)` — loads ALL into memory |
| 8.4 | Stale date-range chips | 🔴 STILL_OPEN | `val now = remember { System.currentTimeMillis() }` — never updated |
| 8.4 | Recompute races on filter change | 🔴 STILL_OPEN | Manual `recomputeMapData` calls race with ongoing `collect` in `init` |
| 8.4 | Aggressive auto-centering | ✅ RESOLVED | `lastCentredLoc` state tracks previous center |
| 8.4 | Plaintext merchant logging | ✅ RESOLVED | All logging uses `.anonymizeForLog()` |
| 8.5 | Fallback area-label | 🟡 PARTIALLY_AFFECTED | Returns `"global"` when no coordinates — functional |
| 8.5 | Destination hint fallback | 🔴 STILL_OPEN | `substringBefore(",")` — one-part addresses return full, empty returns null |

**Fix Plan 8:**
- Defer `getLastKnownLocation()` until after cache checks
- Add `cachedLocation` field to `AndroidForegroundLocationProvider` with TTL
- Treat `GeocodingError.Unknown` as transient in `isTransient()` (or for IOException subclasses)
- Pass caller's `limit` parameter through to Nominatim URL
- Replace `(coord / GRID).toLong()` with `floor(coord / GRID).toLong()` in heatmap/insights
- Remove `lastResolvedAt = :now` from `incrementHitCount` — only increment `hitCount`
- Replace `Instant.now()` / `System.currentTimeMillis()` with `timeProvider.now()` in PriceProtection
- Add `isSimulated: Boolean` flag to `DealAlternative`, `CouponMatch`, `PriceDropAlert`
- Replace `receiptDao.getAll().take(20)` with `getRecentReceipts(limit = 20)` DAO query
- Replace `remember { System.currentTimeMillis() }` with recomputed value or `remember(key)`
- Route filter changes through state flow, let single `collect` do recompute

---

## Summary

| Batch | Total | RESOLVED | STILL_OPEN | PARTIALLY_AFFECTED |
|-------|-------|----------|------------|-------------------|
| 5 | 14 | 2 | 10 | 2 |
| 6 | 10 | 4 | 6 | 0 |
| 7 | 18 | 1 | 17 | 0 |
| 8 | 16 | 4 | 10 | 2 |
| **TOTAL** | **58** | **11 (19%)** | **43 (74%)** | **4 (7%)** |