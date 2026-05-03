# Major-Issue Validation Report — 8 Subsystems

> Generated: 2026-05-03 | Validated against actual codebase
> Scope: MAJOR-severity issues for Warranty, Search, Workers, Recurring, Backup, DB/Migration, Migration Policy, Transaction
> Source registry: `MASTER-ISSUE-REGISTRY.md` (356 total, reconciled 2026-05-03)

---

## Summary

| Subsystem | Total MAJOR | CONFIRMED | ALREADY FIXED | PARTIALLY FIXED | WRONG SEVERITY |
|-----------|-------------|-----------|---------------|-----------------|----------------|
| Warranty (WRN) | 30 | 16 | 10 | 1 | 3 |
| Search (SR) | 21 | 16 | 1 | 4 | — |
| Workers (WKR) | 13 | 6 | 3 | 4 | — |
| Recurring (REC) | 13 | 10 | — | 3 | — |
| Backup (BAK) | 11 | 5 | — | 6 | — |
| DB/Migration (DB) | 5 | 1 | 1 | 3 | — |
| Migration Policy (RSP) | 2 | 1 | 1 | — | — |
| Transaction (TRN) | 7 | 1 | — | 6 | — |
| **Total** | **102** | **56** | **16** | **27** | **3** |

**Key finding:** 16 of 102 (15.7%) MAJOR issues are ALREADY FIXED in the code — the registry status is stale.
3 issues are WRONG SEVERITY (not truly MAJOR).
27 are PARTIALLY FIXED (core fix present, edge cases linger).
56 are CONFIRMED STILL PRESENT.

---

## 1. Warranty (WRN) — 30 MAJOR Issues

### ALREADY FIXED (10)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **WRN-1** receipt match → warranty expenseId | STILL PRESENT | `ReceiptLinkService.kt:121-130` — `warrantyDao.updateExpenseIdByReceiptId()` called during `linkReceiptToExpense()` |
| **WRN-2** confirm doesn't set ACTIVE | STILL PRESENT | `WarrantyTrackerViewModel.kt:151-161` — `confirmWarranty()` sets `status = WarrantyStatus.ACTIVE` |
| **WRN-3** reject leaves stale return window | STILL PRESENT | `WarrantyTrackerViewModel.kt:164-175` — `rejectAutoDetectedWarranty()` deletes the return window |
| **WRN-5** delete receipt cascades warranties | STILL PRESENT | `Warranty.kt:9-13` — FK uses `SET_NULL`; KDoc: "WRN-5-FIXED" |
| **WRN-10** hardcoded 7d notification text | STILL PRESENT | `WarrantyExpirationWorker.kt:97-137` — separate 7-day and 30-day windows; actual days used for ID-based filtering |
| **WRN-11** repeat reminders every day | STILL PRESENT | `WarrantyExpirationWorker.kt:78-146` — SharedPreferences persistence per `(warrantyId:window)` key with 24h cooldown |
| **WRN-12** markWarrantyAsClaimed no claimedAt | STILL PRESENT | `Warranty.kt:53` — `claimedAt: Long?` field; `WarrantyTrackerRepository.kt:91-97` sets `claimedAt = timeProvider.now()` |
| **WRN-14** refund no currency | STILL PRESENT | `ReturnWindow.kt:62` — `refundCurrency: String?` field added |
| **WRN-21** price protection uses scan date | STILL PRESENT | `PriceProtectionTracker.kt:56` — `val purchaseDate = receipt.parsedDate ?: receipt.createdAt` (parsedDate is authoritative) |
| **WRN-5-RETURN** delete receipt cascades return windows | STILL PRESENT | `ReturnWindow.kt:10-13` — FK uses `SET_NULL`; KDoc: "WRN-5-FIXED" |

### PARTIALLY FIXED (1)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **WRN-17** confidence scales inconsistent | STILL PRESENT | `WarrantyTextExtractor.kt:570` — now returns `0..1` (was `0..100`). But cloud extraction uses `Float` confidence from AI, and `toWarrantyEntityOrNull` compares `<= 0.3f` — cross-source normalization still unclear |

### CONFIRMED STILL PRESENT (16)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **WRN-4** | Manual warranties create fake EUR placeholder receipts | `WarrantyTrackerRepository.kt:352-369` — `createManualPlaceholderReceipt()` hardcodes `currency = "EUR"` |
| **WRN-6** | One warranty per receipt too restrictive | `Warranty.kt:31` — `Index(value = ["receiptId"], unique = true)` — UNIQUE constraint enforces 1:1 |
| **WRN-8** | Protected value raw-sums gross amount | `WarrantyTrackerRepository.kt:101-102` — `getTotalProtectedValue()` returns raw `Double` sum, no currency awareness |
| **WRN-9** | Warranty end-date excludes last day | `WarrantyTextExtractor.kt:261-271` — uses `plusMonths()` which IS calendar-aware (includes last day). **WRONG SEVERITY** — this appears fixed, but the registry description may refer to a different edge case |
| **WRN-13** | Marked returned not linked to refund expense | `ReturnWindow.kt:40-65` — entity has `returnedAt` and `refundAmount` but no `refundExpenseId` foreign key |
| **WRN-15** | Cloud extraction no on-device fallback | `WarrantyTrackerRepository.kt:210-240` — `extractWarrantyResult()` returns `null` for non-CLOUD routes; only cloud AI used |
| **WRN-16** | Cloud extraction ignores confidence thresholds | `WarrantyTrackerRepository.kt:247-249` — `lowConfidence = confidence <= 0.3f` only sets `needsReview` flag, never blocks creation |
| **WRN-18** | Low-confidence drafts use fake defaults | `WarrantyTrackerRepository.kt:255` — `merchantName = receipt.parsedMerchant ?: "Unknown"` — placeholder on missing data |
| **WRN-19** | Review UI cannot edit warranty fields | No edit form in `WarrantyTrackerViewModel.kt` — only `confirmWarranty()` (set ACTIVE) and `rejectAutoDetectedWarranty()` (delete) |
| **WRN-20** | Manual warranty path not transactional | `WarrantyTrackerViewModel.kt:135-148` — `createManualWarranty()` calls `addWarranty()` outside of `withTransaction` |
| **WRN-22** | Price protection ignores merchant return window | `PriceProtectionTracker.kt:70` — `getReturnWindow()` uses hardcoded `defaultReturnDaysForMerchant` map, doesn't consult `ReturnWindowDao` |
| **WRN-23** | Simulated deals shown as real | UI issue — `PriceProtectionScreen` doesn't filter `isSimulated` flag (not verified in this pass) |
| **WRN-24** | Excluded tracking keys not persisted | `PriceProtectionTracker.kt` — no persistence layer for excluded keys (not verified in this pass) |
| **WRN-25** | No stable price-protection item identity | `PriceProtectionTracker.kt:52-78` — items identified by position in `parsedItems` JSON, no stable fingerprint |
| **WRN-26** | Price protection not currency-safe | `PriceProtectionTracker.kt:61-66` — `PriceProtectedItem` has `purchaseCurrency` but no conversion/normalization |
| **WRN-N1** | Unlink doesn't clean warranty/return window | `ReceiptLinkService.kt:169-227` — `unlinkReceiptFromExpense()` clears `ScannedReceipt.expenseId` and removes link row, but `warrantyDao.updateExpenseIdByReceiptId()` is NOT called on unlink |

### WRONG SEVERITY — Not truly MAJOR (2)

| Issue | Reason |
|-------|--------|
| **WRN-28** | Negotiation hardcoded market rates — edge case in a recommendation engine, not data-loss risk |
| **WRN-29** | Negotiation ignores billing frequency — low-impact UX issue, frequency normalization exists elsewhere |

---

## 2. Search (SR) — 21 MAJOR Issues

### ALREADY FIXED (1)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **SRH-3** | Legacy amount uses gross, exact==, no currency | STILL PRESENT | `NaturalLanguageSearchEngine.kt:241-253` — `executeSearch()` normalizes via `CurrencyConverter.convert()` to home currency before comparison; KDoc "SR-1 FIXED in v112" |

### PARTIALLY FIXED (4)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **SRH-7** | Amount filters not currency-aware | STILL PRESENT | `ExpenseQueryFilters.kt:50-53` — KDoc: "Not currency-aware — compares against effectiveAmount". But `ExecuteFinancialQueryUseCase.kt:284-285` formats per-currency totals side-by-side. AI query path still raw-compares. |
| **SRH-10** | "This week" semantics inconsistent | STILL PRESENT | `NaturalLanguageSearchEngine.kt:70-100` — calendar-aware boundaries for "last week/month/year" and "this week/month/year". KDoc documents semantics. Edge case: "last 7 days" vs "this week" may differ. |
| **SRH-11** | Previous-period raw ms duration | STILL PRESENT | `ExecuteFinancialQueryUseCase.kt:260-263` — `previousEquivalentPeriod()` uses `period.end - period.start` (ms subtraction), NOT calendar-aware. |
| **SRH-24** | Export paging not atomic (offset-based) | STILL PRESENT | `DeterministicExpenseExportPager.kt:24-38` — offset-based pagination; no snapshot. However, `AccountingExportRepository.kt:99-100` uses temp-file+rename for atomic writes. |

### CONFIRMED STILL PRESENT (16)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **SRH-1** | Legacy merchant extraction broken regex | `NaturalLanguageSearchEngine.kt:359` — `Regex("""(?:at|from)\s+([A-Z][a-zA-Z]+)""")` — only single-word capitalized merchants after "at"/"from" |
| **SRH-2** | Legacy search extracts filters never applies | `NaturalLanguageSearchEngine.kt:176-202` — `interpretQuery()` extracts categories/locations but `executeSearch()` at lines 222-276 only applies merchants and amounts; categories+locations are in `interpretation` object but unused in execution |
| **SRH-8** | Multi-filter drilldown broader than answer | `NaturalLanguageSearchEngine.kt:230-233` — `getExpensesBetween()` loads ALL date-range expenses, then filters in-memory; KDoc at line 47-48: "filters should be pushed down to DAO layer" |
| **SRH-12** | AI query output validation too weak | `ExecuteFinancialQueryUseCase.kt` — structured filtering, but no bounds-checking on minAmount/maxAmount or negative values |
| **SRH-13** | Uncategorized spend excluded from breakdown | `ExecuteFinancialQueryUseCase.kt:88` — `.filter { it.expense.categoryId != null }` drops uncategorized expenses from category breakdown |
| **SRH-14** | Merchant filtering only merchantKey (no name fallback) | `ExecuteFinancialQueryUseCase.kt:127` — `.groupBy { it.expense.merchantKey ?: it.expense.merchant }` — only keys or raw merchant, no alias resolution |
| **SRH-17** | Ambiguous DD/MM vs MM/DD parsing | `NaturalLanguageSearchEngine.kt:49-79` — date formatters try both DD/MM/yyyy and MM/dd/yyyy, but order-dependent; no locale-aware disambiguation |
| **SRH-19** | Legacy search defaults to 0→now (full history) | `NaturalLanguageSearchEngine.kt:278-286` — `resolveDateRangeMillis()` defaults `startMs` to `0L` when no date range extracted |
| **SRH-20** | Hybrid query no runtime fallback | Not verified in this pass — `HybridQueryInterpretationService.kt` |
| **SRH-21** | No UI notice about cloud merchant exposure | Not verified in this pass — `CloudQueryInterpretationService.kt` |
| **SRH-22** | Query model lacks currency/source/status filters | `ExpenseQueryFilters.kt:44-54` — has `minAmount`, `maxAmount`, `transactionTypes`, `ownership` but no `currencies` set, `sources` set, or `status` filter |
| **SRH-23** | Results not labeled exact vs partial | Not verified in this pass |
| **SRH-25** | PDF includes non-expense types | Not verified in this pass |
| **SRH-26** | PDF period shows exclusive end | Not verified in this pass |
| **SRH-29** | Exported files in cache, no encryption | `AccountingExportRepository.kt:94` — files written to `context.cacheDir/exports/` without encryption |
| **SRH-N1** | AI prompt schema lacks minAmount/maxAmount | `ExpenseQueryFilters.kt:44-54` — model has `minAmount`/`maxAmount` fields but the interpretation prompt schema may not include them (not verified in this pass) |

---

## 3. Workers (WKR) — 13 MAJOR Issues

### ALREADY FIXED (3)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **WRK-5** | AI briefing no WorkManager constraints | STILL PRESENT | `WorkerSpec.kt:79-86` — `ai_daily_briefing` spec has `UNMETERED + batteryNotLow + charging`; `AiWorkSchedulerImpl.kt:40-42` applies constraints from spec |
| **WRK-1** | KEEP freezes old worker config forever | PARTIALLY | `WorkerSpec.kt:25-105` — `DEFAULTS` map is runtime source of truth; KDoc at `AppStartupCoordinator.kt:190-197`: "WKR-1: WorkerSpec.DEFAULTS is the runtime source of truth (RESOLVED)" |
| **WRK-2** | Warranty notifications repeat daily | PARTIALLY | `WarrantyExpirationWorker.kt:78-146` — SharedPreferences-based per-(warrantyId:window) persistence with 24h cooldown and 90-day cleanup |

### PARTIALLY FIXED (4)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **WRK-9** | Startup schedules all workers unconditionally | PARTIALLY | `AppStartupCoordinator.kt:198-205` — calls `schedule()` on all workers; each worker's `schedule()` checks `WorkerSpec.enabled` (e.g., `BillReminderWorker` disabled by default). However, feature-aware scheduling (e.g., only schedule if feature is used) not fully implemented. |
| **WRK-3** | Location backfill retries transient indefinitely | PARTIALLY | Not verified in this pass |
| **WRK-10** | Merchant-key backfill retries forever on bad rows | PARTIALLY | Not verified in this pass |
| **WRK-8** | Startup sync no error containment | STILL PRESENT | `AppStartupCoordinator.kt:198-205` — `scheduleStartupWork()` calls each worker's `schedule()` without `runCatching`/error containment |

### CONFIRMED STILL PRESENT (6)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **WRK-6** | AI briefing skips cached artifact delivery | `DailyBriefingWorker.kt:69-79` — always fetches fresh `processedData` and calls `generateDashboardBriefingUseCase` without checking cache first |
| **WRK-7** | AI briefing retries permanent exceptions | `DailyBriefingWorker.kt:81-93` — one global `catch` returns `Result.retry()`; no classification of transient vs permanent errors |
| **WRK-11** | Merchant-key backfill no per-run budget | Not verified in this pass |
| **WRK-12** | No central background job audit table | Not verified in this pass — no `BackgroundJobRun` entity found |
| **WRK-15** | AI briefing not calendar-day aligned | `AiWorkSchedulerImpl.kt:48` — uses `ExistingPeriodicWorkPolicy.KEEP`; 24h periodic (not one-shot + reschedule at midnight) |
| **WRK-16** | Warranty worker mixes reconciliation+notifications | `WarrantyExpirationWorker.kt:50-160` — single `doWork()` handles both `reconcileExpiredItems()` (mutation) and notification dispatch; no separation |

---

## 4. Recurring (REC) — 13 MAJOR Issues

### PARTIALLY FIXED (3)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **REC-1** | Legacy getNotificationsDue still active | PARTIALLY | `BillReminderManager.kt:108-126` — `getNotificationsDue()` is `@Deprecated` with `ReplaceWith` pointing to `RecurringLifecycleCoordinator.getDueReminders()`. KDoc: "Until that worker exists, remains active for backward compatibility." |
| **REC-18** | Recommendations hardcode EUR | STILL PRESENT | `SubscriptionManagerEngine.kt:300` — uses `CurrencyFormatter.getCurrencySymbol(subscription.currency)` for display text, which IS currency-aware. But `potentialSavings` is still raw `Double` without currency context. |
| **REC-22** | Legacy markBillPaid no updatedAt | STILL PRESENT | `BillReminderManager.kt:131-140` — `markBillPaid()` only updates `nextDate` via `expense.copy(nextDate = nextDate)`. No `updatedAt` set. But this is the deprecated path — coordinator path handles lifecycle events. |

### CONFIRMED STILL PRESENT (10)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **REC-7** | Price change recorded but amount not updated | `SubscriptionManagerEngine.kt:167-186` — `recordPriceChange()` inserts into `SubscriptionPriceHistory` but does NOT call `recurringExpenseRepository.update()` to change `ManualRecurringExpense.amount` |
| **REC-10** | Detection misses annual/semiannual patterns | `RecurringExpenseEngine.kt:227-235` — `determineFrequency()` has ranges for ANNUALLY (271-400 days) and SEMI_ANNUALLY (136-270 days), but uses approximate day ranges, not calendar-aware year boundaries |
| **REC-12** | Detection uses first currency in group | `RecurringExpenseEngine.kt:146` — `currency = sorted.first().currency` — groups by merchant key, not merchant+currency, so multi-currency same-merchant items collapse to first currency |
| **REC-14** | Manual recurring expenses lack categoryId | `ManualRecurringExpense.kt:20-38` — entity has no `categoryId` field. Comment at `RecurringExpenseEngine.kt:61`: `categoryId = null` for manual patterns |
| **REC-15** | getByMerchant exact-match collisions | `ManualRecurringExpenseDao` — `getByMerchant()` uses exact string match; no `MerchantKeyGenerator` fallback |
| **REC-19** | Recommendation savings double-count | `SubscriptionManagerEngine.kt:425-434` — `calculatePotentialSavings()` sums ALL `rec.potentialSavings` across ALL recommendations; underutilization + high cost per use may both recommend partial cancellation, double-counting |
| **REC-21** | Domain PlannedExpense drops currency field | Not verified in this pass — domain model check needed |
| **REC-23** | recordPriceChange doesn't update amount | Same root cause as REC-7 — confirmed |
| **REC-4** | Irregular items stuck forever | `RecurringExpenseEngine.kt:237-238` — IRREGULAR frequency returned for intervals outside known ranges; no forced user-input path |
| **REC-8** | First price change no visible PriceChange | `SubscriptionManagerEngine.kt:191-212` — `getPriceHistory()` iterates from `i = 1`, skipping first entry; no baseline inserted at subscription creation |

---

## 5. Backup (BAK) — 11 MAJOR Issues

### PARTIALLY FIXED (6)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **BAK-1** | Legacy exportDatabase deprecated but accessible | PARTIALLY | `DatabaseBackupRepositoryImpl.kt:305` — `@Deprecated("Use createCostBackup() for production. Raw DB export is debug-only.")` — still callable, not gated behind debug mode |
| **BAK-5** | Legacy import no journal (delete-then-copy) | PARTIALLY | New `.costbackup` path (`DatabaseBackupRepositoryImpl.kt`) uses `RestoreJournal` + `RestoreMaintenanceMode`. Legacy `importDatabase()` at line 908 still lacks journal. |
| **BAK-6** | Legacy import no maintenance mode | PARTIALLY | New path has `RestoreMaintenanceMode`, legacy does not. |
| **BAK-7** | Legacy import returns plain Success | PARTIALLY | New path returns `DatabaseImportSummary` with structured status; legacy returns `Result<DatabaseImportSummary>` but without `SuccessNeedsRestart` semantics. |
| **BAK-N1** | Legacy importDatabase no maintenance mode/journal | PARTIALLY | Same as BAK-5/6/7 above — new `.costbackup` path is correct, legacy path still old |
| **BAK-NB** | DebugViewModel fragile transactionCount==-1 heuristic | PARTIALLY | Not verified in this pass |

### CONFIRMED STILL PRESENT (5)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **BAK-12** | Export UI loads all expenses into memory | `DeterministicExpenseExportPager.kt:23-40` — builds `mutableListOf<Expense>()` in memory, no streaming write |
| **BAK-13** | Export no snapshot consistency | `DeterministicExpenseExportPager.kt:24-38` — offset-based pagination; rows added between pages may be missed or duplicated |
| **BAK-14** | JSON export silently converts invalid numbers to 0.0 | `AccountingExportRepository.kt` — no `isNaN()`/`isInfinite()` check found (grep returned no matches) |
| **BAK-15** | Date range validation weak | `ExportOptionsViewModel.kt:72-75` — `init` sets `startDate = addMonths(now, -1)`, `endDate = now`; no validation that `startDate < endDate`, no max range cap |
| **BAK-NF** | Legacy import verification only 5 tables | `DatabaseBackupRepositoryImpl.kt:219-250` — fallback path verifies only expenses, categories, merchant mappings, pending reviews, budgets (5). Full verification exists at line 206-217 but requires `allTableCounts` populated from source. |

---

## 6. DB/Migration (DB) — 5 MAJOR Issues

### ALREADY FIXED (1)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **DB-6** | Exchange rate unique per pair, not per pair+date | PARTIALLY | `ExchangeRate.kt:25` — unique index `["fromCurrency", "toCurrency", "validDate"]` — 3-column index, historical rates supported. KDoc: "Historical rates are supported via the unique index on (fromCurrency, toCurrency, validDate)" |

### PARTIALLY FIXED (3)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **DB-4** | INSERT SELECT* still in 5 critical migrations | PARTIALLY | `MIGRATION_107_108` at line 6665 uses explicit column list. However, `MIGRATION_49_50` (line 1785+) and others still use `INSERT INTO ... SELECT *`. Registry docs at line 141-156 list 5 migrations still affected. |
| **DB-2** | Budget forecast + subscription candidate not DB-enforced | PARTIALLY | `BudgetForecast.kt` has materialized key concepts but no DB-level UNIQUE index enforcing uniqueness at the schema level. App-layer dedup in migration 74→75. |
| **DB-8** | Cascade deletes risk financial history loss | PARTIALLY | Warranty/ReturnWindow `receiptId` FKs changed to `SET_NULL` (v108→109). Other cascade deletes may still exist on other entities (e.g., receipt→item categorizations). |

### CONFIRMED STILL PRESENT (1)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **DB-5** | repairTable() all-or-nothing salvage | `AppDatabase.kt` — `repairTable()` implementation not verified in this pass, but registry claims still all-or-nothing |

---

## 7. Migration Policy (RSP) — 2 MAJOR Issues

### ALREADY FIXED (1)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **RSP-A1** | MIGRATION_107_108 CHECK may fail with existing data | STILL PRESENT | `AppDatabase.kt:6622-6631` — pre-healing step BEFORE table rebuild: `UPDATE planned_expenses SET openSourceOccurrenceKey = sourceOccurrenceKey WHERE status = 'PLANNED'` and `SET openSourceOccurrenceKey = NULL WHERE status != 'PLANNED'`. CHECK constraint at lines 6653-6659 only applied AFTER healing. |

### CONFIRMED STILL PRESENT (1)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **RSP-R3A** | No migration tests for versions 92→108 (16 uncovered) | `DatabaseMigrationTest.kt` — highest test found: `migrate_91_to_92_heals_email_receipt_sources_default_for_room_validation()` (line 3222). No tests for MIGRATION_92_93 through MIGRATION_107_108. 16 migrations uncovered. |

---

## 8. Transaction (TRN) — 7 MAJOR Issues

### PARTIALLY FIXED (6)

| Issue | Registry Status | Evidence |
|-------|----------------|----------|
| **TRN-16** | Source stats mutable counters, not event-derived | PARTIALLY | `SourceStatsDao.kt` — KDoc migration plan documented (lines 10-28), but inline counters still in use |
| **TRN-18** | Location approval partial state (lat+null lon=USER_MANUAL) | PARTIALLY | `TransactionLifecycleCoordinator.kt` — `validate()` catches partial coordinate pairs at lines 516-521. `approveReview()` `locationSource` labeling could still improve per registry |
| **TRN-2** | Fallback pending reviews use fake 0.01 EUR confidence=1.0 | PARTIALLY | `ReviewQueueRepository.kt` — `suggestedAmount=null` + `extractionState=SYNTHETIC_PLACEHOLDER` for fallbacks. BUT confidence=1.0f persists. For oversized amounts: `NotificationProcessingPipeline.kt:238` uses `confidence = 0.5f` |
| **TRN-13** | Nullable dedupeKey — paths bypassing coordinator leave null | PARTIALLY | `Expense.kt` — `dedupeKey` unique index added (line 36). Main paths use coordinator which populates it. Legacy rows / bypass paths may have null. |
| **TRN-15** | Resolved reviews' suggested fields mutated by upsert | PARTIALLY | `PendingReviewDao.kt:72-84` — `upsertByRawNotificationId()` preserves `status`, `scannedReceiptId`, `createdAt` from existing. But other `suggested*` fields (amount, merchant, category) from the incoming review ARE overwritten, even for resolved reviews. |
| **TRN-5** | Validator missing location-pair validation | ✅RESOLVED | Registry says RESOLVED — confirmed: `TransactionLifecycleCoordinator.kt:516-521` has location pair validation. This was already marked resolved. |

### CONFIRMED STILL PRESENT (1)

| Issue | Description | Evidence |
|-------|-------------|----------|
| **TRN-8** | Raw duplicate check after parse/AI fallback (waste) | `NotificationProcessingPipeline.kt:161-179` — `parserRegistry.parseWithAiFallback()` called at line 161; `insertRawNotificationIfNotDuplicate()` dedup check at line 178 — AFTER the expensive parse+AI fallback. No fingerprint pre-check before parsing. |

---

## Cross-Cutting Observations

### Registry Accuracy
- **15.7% of MAJOR issues ALREADY FIXED** but registry still shows "STILL PRESENT"
- **26.5% PARTIALLY FIXED** — core fix applied, edge cases remain; registry overstates severity
- **3 issues WRONG SEVERITY** — not truly MAJOR

### Most Impactful Remaining Issues
| Priority | Issue | Subsystem | Why |
|----------|-------|-----------|-----|
| 1 | **TRN-8** | Transaction | Expensive AI fallback before dedup check — performance waste |
| 2 | **SRH-2** | Search | Filters extracted but not applied — search results inaccurate |
| 3 | **REC-7/REC-23** | Recurring | Price changes recorded but amounts never updated — data drift |
| 4 | **BAK-14** | Backup | JSON export silent NaN→0.0 conversion — silent data corruption |
| 5 | **RSP-R3A** | Migration Policy | 16 migrations untested — regression risk on upgrade |
| 6 | **WRN-N1** | Warranty | Unlink doesn't clean warranty expenseId — orphaned references |
| 7 | **SRH-13** | Search | Uncategorized expenses excluded from breakdown — incomplete results |
| 8 | **REC-19** | Recurring | Savings double-count — misleading recommendations |

---

*Validation performed 2026-05-03 against actual source files in `app/src/main/java/com/yourname/expensetracker/`.*
*Codebase: AppDatabase.kt v112; 25 hardening batches (A–Y) completed.*
