# Master Registry Audit — Consolidated Scout Findings

> Generated from 5 parallel scout audits covering all 48 verification batches (B01-B48).
> This document contains ALL missing issues, duplicates to remove, severity corrections, and categorization fixes.
> The coder should apply these changes to MASTER-ISSUE-REGISTRY.md ONE BATCH AT A TIME.

---

## BATCH 1: B01-B10 Audit Results

### MISSING Issues (38 total)

#### B01 Missing (9 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B01-M1 | Medium | `AdvancedAnalyticsEngine.kt` | Merchant analytics groups by raw `merchant`, re-filters full 6-month history per merchant — aliases fragment results, O(merchants × history) runtime | D.3 |
| B01-M2 | Low | `AdvancedAnalyticsDashboard.kt` | `generateDashboardData()` hardcodes `Dispatchers.IO` | D.3 |
| B01-M3 | High | `AnomalyDetector.kt` | IQR/MAD zero-dispersion bailout — obvious spikes like `[10,10,10,100]` missed | B.10 |
| B01-M4 | Medium | `CategoryInsightEngine.kt` | Previous-period expenses re-filtered for every category — O(categories × previous-expenses) | D.3 |
| B01-M5 | Medium | `MerchantInsightEngine.kt` | Merchant grouping by `merchant.lowercase()` not canonical `merchantKey` | D.3 |
| B01-M6 | High | `TotalsAggregationEngine.kt` | Monthly/weekly/daily totals only return periods with transactions — zero-spend periods disappear, week labels renumbered | B.2 |
| B01-M7 | Medium | `TransferDirectionAnalytics.kt` | `recordUserCorrection()` assumes initial detection was correct — double-decrement on incorrect initial detection | D.3 |
| B01-M8 | Low | `SpendingPaceModels.kt` | Referenced in batch plan but file doesn't exist — models live in `AnalyticsModels.kt` | D.3 |
| B01-M9 | Medium | `AdvancedAnalyticsEngine.kt` | `getMerchantAnalytics()` loads history with `getExpensesSince(historicalStart)` and never caps at `period.endMs` — post-period transactions leak | D.3 |

#### B02 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B02-M1 | High | `BudgetCalculator.kt` | `calculatePeriodWindow(period, anchorDate)` always uses `timeProvider.now()` — callers cannot derive historical/next windows reliably | B.2 |
| B02-M2 | Medium | `BudgetForecastingEngine.kt` | `generateForecast()` inserts forecast row but returns pre-insert object — caller always gets `id = 0` | D.3 |

#### B03 Missing (3 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B03-M1 | Medium | `AutomatedSavingsRuleEngine.kt` | `WEEKLY_NO_SPEND` uses rolling `now - 7 days` instead of stable calendar week | B.8 |
| B03-M2 | Medium | Cross-component | `FinancialHealthCalculator ↔ FinancialHealthScoreV2 ↔ ComputeDashboardWidgetsUseCase` — two incompatible health KPIs side by side with different formulas/filters/week definitions | C.3 |
| B03-M3 | Medium | Cross-component | `FinancialHealthScoreV2 → ComputeDashboardWidgetsUseCase` V2 swallows fatal calculation exceptions into `50` — dashboard renders as real health data | C.3 |

#### B04 Missing (5 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B04-M1 | Medium | `SynthesisEngine.kt` | Biweekly matching treats any date within ±2 days as bill day — one bill appears on up to 5 days | D.3 |
| B04-M2 | Medium | `MonteCarloSpendingSimulator.kt` | `countRecentQualifyingWeeks()` uses `total > 0` instead of `>= 3` distinct transaction-days | D.3 |
| B04-M3 | Medium | `SynthesisEngine.kt` | `now` captured once but `Calendar` seeded with second `timeProvider.now()` call — midnight race | D.3 |
| B04-M4 | Low | `SplitCalculator.kt` | `formatBalance()` hardcodes `$` — non-USD users see wrong currency | D.3 |
| B04-M5 | Medium | Cross-component | Two separate Monte Carlo implementations — `FinancialStressForecastEngine` injects but doesn't use `MonteCarloSpendingSimulator` | C.3 |

#### B05 Missing (7 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B05-M1 | Medium | `ComputeDashboardWidgetsUseCase.kt` | When no overall budget, `SafeToSpend.amount` populated with `ctx.monthSpent` (already-spent money) | D.3 |
| B05-M2 | Medium | `ComputeMoneyRadarUseCase.kt` | `compute()` captures `now` but helpers call `timeProvider.now()` again — midnight mixing | D.3 |
| B05-M3 | Medium | `ComputeDashboardWidgetsUseCase.kt` | Zero `averageDailyBurn` + remaining budget → runway days = 0 → CRITICAL | D.3 |
| B05-M4 | Medium | `ComputeDashboardWidgetsUseCase.kt` | `monthSpent` from `summary.totalSpent` while `todaySpent`/`weekSpent` from `purchases` — different reactive paths → inconsistent | D.3 |
| B05-M5 | Medium | `CalculateFinancialForecastUseCase.kt` | Forecast flow recomputes `now`/`monthStart`/`currentDay` only when repository flows emit — stale across day/month rollover | B.9 |
| B05-M6 | High | `AutoCreateWarrantyFromReceiptUseCase.kt` | Medium-confidence extraction persists `PENDING_REVIEW` draft; `createWarrantyForReview()` inserts new warranty with same `receiptId` → conflicts with draft → `AlreadyExists` | B.3 |
| B05-M7 | Medium | Cross-component | `MonthlySavingsSweepUseCase → ComputeDashboardWidgetsUseCase → HomeScreen` — `DashboardWidget.SavingsSweepPrompt` never emitted, `HomeScreen` renders empty placeholder | C.3 |

#### B06 Missing (0 issues)
All B06 issues confirmed present.

#### B07 Missing (3 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B07-M1 | Low | `GetAiRuntimeStatusUseCase.kt` | Capability status checks awaited sequentially — latency grows linearly | D.3 |
| B07-M2 | High | `InterpretFinancialQueryUseCase.kt` | Early special-case returns collapse richer queries into plain TOTAL intents — merchant/category/grouping/metric cues discarded | B.1 |
| B07-M3 | High | `ExecuteFinancialQueryUseCase.kt` | Query totals/breakdowns aggregate raw amounts across currencies with no conversion/separation — hardcoded `EUR` labels hide mixed-currency math bug | B.1 |

#### B08 Missing (7 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B08-M1 | Medium | `SuggestReceiptExtractionUseCase.kt` | Non-forced path no longer enforces deterministic `needsAssist()` gate | D.3 |
| B08-M2 | Medium | `MapFinancialQueryToNavigationUseCase.kt` | `singleOrNull()` silently drops multi-value filters — drill-down opens broader list | B.1 |
| B08-M3 | Low | `MapFinancialQueryToNavigationUseCase.kt` | `QueryMetric.MIN` explicitly rejected — unsupported end-to-end | D.3 |
| B08-M4 | Medium | `PrioritizeReviewItemsUseCase.kt` | Has no production call site — review-priority feature is dead code | D.3 |
| B08-M5 | Medium | `CloudReceiptAssistService.kt` | `buildImageInlineData()` reads full file into memory before checking size | D.3 |
| B08-M6 | Medium | `ExecuteFinancialQueryUseCase.kt` | `QueryMetric.MIN` advertised but never executed — "smallest/cheapest" falls through to `Unsupported` | D.3 |
| B08-M7 | High | Cross-component | `CloudDedupeJudgeService`/`OnDeviceDedupeJudgeService` model-emitted `matchedTargetType`/`matchedTargetId` trusted without bounds-checking against candidate set | B.1 |

#### B09 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B09-M1 | High | Cross-component | Warranty extraction → return-window: `CloudWarrantyExtractionService` returns `null` for return-policy-only receipts — `WarrantyTrackerRepository` only creates `ReturnWindow` when `Warranty` was produced | B.3 |
| B09-M2 | Medium | Cross-component | Warranty extraction → return-window mapping: AI extracts `returnDays`/`returnConditions` but repository ignores them, recreates merchant-default windows — extracted fields dead code | B.3 |

#### B10 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B10-M1 | Medium | `GenerateDashboardBriefingUseCase.kt` / `HybridDashboardBriefingService.kt` | Same double-routing flaw as review explanations: artifact metadata from one route, hybrid service re-routes and can execute different route | B.1 |

### DUPLICATES to Remove (B01-B10 audit)
| Duplicate | Location A | Location B | Action |
|-----------|------------|------------|--------|
| `DailyBriefingWorker` CancellationException | A.7 (epic) | D.3 (quick win) | Remove from D.3 (covered by epic) |
| `ComputeMoneyRadarUseCase` sequential fetches | B.9 HIGH | D.3 | Remove from D.3 |
| `SpendingPaceCalculator` current partial day | B.8 LOW | D.3 LOW | Remove from B.8 |
| `CloudRetryPolicy` ignores Retry-After | B.1 MEDIUM | D.3 | Remove from D.3 |
| `SpendingMapScreen` date-range chips | B.5 LOW | B.9 LOW | Remove from B.9 |
| `Map auto-centres` on GPS | B.5 LOW | B.9 LOW | Remove from B.9 |
| `HybridExpenseClassifier.initialized` not @Volatile | B.5 LOW | D.3 | Remove from B.5 |
| `NotificationIdGenerator.forWarranty()` overlap | B.6 HIGH | D.3 | Remove from D.3 |
| `Grid-cell bucketing` .toLong() | B.5 MEDIUM | B.10 MEDIUM | Remove from B.10 |
| `SmsParser`/`RevolutParser` amount regex | B.6 MEDIUM | B.11 MEDIUM | Remove from B.11 |

---

## BATCH 2: B11-B20 Audit Results

### MISSING Issues (28 total)

#### B11 Missing (4 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B11-M1 | Medium | `GroupTransactionCoordinator.kt` | `addMemberToGroup()`, `addExpenseToGroup()`, `addExpenseWithLink()` validate state outside single DB transaction — concurrent archive/delete can invalidate checks | B.4 |
| B11-M2 | High | Cross-component | `SharedExpenseGroupsViewModel.addExpense()` creates system expense first then `group_expenses` row — crash between two writes orphans the system expense | B.4 |
| B11-M3 | Medium | Cross-component | Validation pipeline for group creation vulnerable to archive/member-change races | B.12 |
| B11-M4 | Medium | Cross-component | Migration `69→70` + Android Keystore encryption causing DB open failure | B.4 |

#### B12 Missing (6 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B12-M1 | High | Cross-component | Receipt warranty extraction uses fixed 30-day months vs. manual warranty UI uses calendar-month addition | B.3 |
| B12-M2 | Medium | `WarrantyDao.kt` | `getTotalProtectedValue()` sums raw `expense.amount` instead of `effectiveAmount` | A.1 |
| B12-M3 | Medium | Cross-component | AI artifact → recommendation persistence (`RecommendationEntity.sourceArtifactId`) — joins and cleanup cannot be enforced safely | C.3 |
| B12-M4 | Low | Cross-component | `RecurringExpenseRepository` leaves `IRREGULAR` items without advancing `nextDate`; different semantics per code path | B.2 |
| B12-M5 | Medium | Cross-component | UI validation → database: invariants only inconsistently enforced above DB | B.4 |
| B12-M6 | Medium | Cross-component | Deduplication depends on locale-sensitive amount formatting | C.1 |

#### B13 Missing (5 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B13-M1 | Medium | `BankConnection.kt` | `defaultCategoryId` has no FK to `categories` — deleted categories leave stale IDs | B.4 |
| B13-M2 | Medium | `MerchantLocation.kt` | `areaKey` nullable inside composite unique index — multiple `(normalizedMerchantName, NULL)` rows bypass uniqueness in SQLite | B.4 |
| B13-M3 | Medium | Cross-component | Financially sensitive numeric fields have no DB-level CHECK constraints across 7 entities | B.4 |
| B13-M4 | Medium | Cross-component | `customSplitsJson` not actually JSON; parsing split between `CustomSplitParser` and `SharedExpenseBudgetOffsetEngine` | B.12 |
| B13-M5 | Medium | Cross-component | `EmailReceiptIngestionService` inserts `ScannedReceipt` and `EmailReceiptSource` in separate DAO calls — partial-write state | B.11 |

#### B14 Missing (4 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B14-M1 | Medium | `ExpenseDao.kt` | `getBusinessExpensesBetween()` doesn't filter `transactionType = 'PURCHASE'` — transfers/deposits listed as deductible | B.4 |
| B14-M2 | High | Cross-component | Warranty/return-window status never reconciled — no production path transitions rows to `EXPIRED` | B.3 |
| B14-M3 | Medium | Cross-component | `CategoryDao` → `CategoryRepository.ensureDefaultCategories()` race — concurrent seeding creates duplicate defaults | B.4 |
| B14-M4 | Medium | Cross-component | `ExpenseDao` → `BudgetRepository.getSuggestions()` N+1 per-category loop | D.3 |

#### B15 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B15-M1 | Medium | Cross-component | Merchant location global-cache fallback returns arbitrary area-scoped entry — wrong branch for multi-branch merchants | B.5 |
| B15-M2 | Medium | Cross-component | Merchant location global-key encoding inconsistency — `"global"` vs `"<normalized>|global"` | B.5 |

#### B16 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B16-M1 | High | `Expense.kt` | `isNotMine` + `isSharedExpense` simultaneously allowed; `effectiveAmount` zeroes both — rows disappear from analytics | B.9 |
| B16-M2 | High | `TransactionsViewModel.kt` | External `dateRange` filters intersected with default `MONTH` tab window — drill-down navigation clips results | B.9 |

#### B17 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B17-M1 | Medium | Cross-component | "Month" semantics differ across Home/Analytics/Transactions — calendar-month vs rolling 30 days | B.9 |

#### B18 Missing (4 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B18-M1 | High | `ReviewQueueRepository.kt` | Approved transfer/deposit reviews never copy `suggestedDirection`/`suggestedAccountName` into `Expense` — transfer metadata lost | B.9 |
| B18-M2 | Medium | `WarrantyTrackerScreen.kt` | "Expired" filter uses `status == EXPIRED` but nothing auto-transitions warranties — filter never shows items | B.3 |
| B18-M3 | High | Cross-component | Review approval pipeline loses optional metadata end-to-end — place id dropped, transfer metadata never copied | C.2 |
| B18-M4 | Medium | Cross-component | Currency presentation not centralized; multiple screens hardcode `€` | B.9 |

#### B19 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B19-M1 | High | `VisualSplitEditorScreen.kt` | "Apply Split" hands data to callback but navigation host just navigates back and discards result — no-op | B.9 |
| B19-M2 | High | Cross-component | Spending challenges end-to-end feature incomplete — no persistence API, no domain manager wire-up | B.10 |

#### B20 Missing (4 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B20-M1 | Medium | `RecommendationStateManager.kt` | `refreshForUser()` concurrent stale result overwrite — slower stale request can overwrite newer state | B.6 |
| B20-M2 | High | Cross-component | Anomaly notifications deep-link to `expensetracker://transaction/{id}` but manifest doesn't declare host | B.6 |
| B20-M3 | Medium | Cross-component | `NavigationAction.ToAnalytics(period)` / `ToMap(location)` payload dropped at `HomeScreen`/`MainActivity` | B.6 |
| B20-M4 | Medium | Cross-component | `DeliverProactiveBriefingNotificationUseCase` records briefing as delivered even when `AndroidNotificationService` returns early — permanently suppressed | B.6 |

### SEVERITY Corrections (B11-B20 audit)
| Issue | Registry | Should Be | Reason |
|-------|----------|-----------|--------|
| B17 `SpendingPatternsCard` NaN | D.2 HIGH | MEDIUM | Report DOWNGRADED from High |
| B18 `TransferDirection.valueOf` | D.2 HIGH | MEDIUM | Report DOWNGRADED (from High) |
| B22 `ExpenseTrackerApp` CoroutineScope | D.3 Medium | LOW | Report DOWNGRADED |
| B22 `TransactionClassifier`/`BudgetMonitor` eager injection | D.3 Medium | LOW | Report DOWNGRADED |
| B23 `NotificationIdGenerator` negative sign | D.3 Medium | LOW | Report DOWNGRADED |
| B23 `BKTree.size`/`isEmpty` outside mutex | D.3 Medium | LOW | Report DOWNGRADED |
| B23 `Math.abs(hash)` negative | D.3 Medium | LOW | Report DOWNGRADED |

### DUPLICATES to Remove (B11-B20 audit)
| Duplicate | Location A | Location B | Action |
|-----------|------------|------------|--------|
| `ReceiptScanScreen` collectAsState | B.9 LOW | D.3 | Remove from B.9 |
| `WarrantyTrackerScreen` expired badge | B.9 LOW | D.3 | Remove from B.9 |
| `WarrantyTrackerViewModel` auto-detected chip | B.9 LOW | D.3 | Remove from B.9 |
| `CurrencyManagementScreen` Convert enabled | B.9 LOW | D.3 | Remove from B.9 |
| `balance == 0.0` float equality | B.9 LOW | D.3 | Remove from B.9 |
| `AddExpenseViewModel.reset()` | B.9 LOW | D.3 | Remove from B.9 |
| `AddExpenseSheet` LaunchedEffect(Unit) | B.9 LOW | D.3 | Remove from B.9 |
| `CarbonFootprintViewModel` collapses exceptions | B.9 LOW | D.3 | Remove from B.9 |
| `Starter prompt chips` | B.9 LOW | D.3 | Remove from B.9 |
| `Active challenges` placeholder | B.9 LOW | D.3 | Remove from B.9 |
| `NoSpendStreakCard` Locale.GERMANY | B.9 LOW | D.3 | Remove from B.9 |
| `SubscriptionManagementViewModel` no-spend | B.9 LOW | D.3 | Remove from B.9 |
| `CarbonFootprintScreen` negative gap | B.9 LOW | D.3 | Remove from B.9 |
| `NotificationCaptureService` force-started | B.6 MEDIUM (line 369) | B.6 MEDIUM (line 374) | Remove line 374 (exact duplicate) |

### DATA ERROR (B11-B20 audit)
| Issue | Wrong Value | Correct Value |
|-------|-------------|---------------|
| `ReviewScreen.showTrustSignal` vs `WarrantyTrackerScreen.showTrustSignal` | D.3 says `WarrantyTrackerScreen` | Should be `ReviewScreen` (B18 report confirms) |

---

## BATCH 3: B21-B30 Audit Results

### MISSING Issues (18 total)

#### B21 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B21-M1 | Medium | `DeliverProactiveBriefingNotificationUseCase.kt` / `AndroidNotificationService.kt` | Briefing delivery tracking is optimistic — records as delivered even when service returns early because notifications disabled; later delivery permanently suppressed | B.6 |
| B21-M2 | Medium | `AndroidNotificationService.kt` / `MainActivity.kt` | Anomaly alert deep link not routable — `expensetracker://transaction/{expenseId}` has no handler in `MainActivity`; tapping falls through to Home | B.6 |

#### B22 Missing (0 issues)
All B22 issues confirmed present.

#### B23 Missing (3 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B23-M1 | High | `CsvExpenseImporter.kt` | Importer bypasses singleton Room graph — builds fresh `AppDatabase` instances via local extension | B.4 |
| B23-M2 | Medium | `CsvExpenseImporter.kt` | `line.split(",")` breaks quoted CSV fields — merchants/descriptions with commas corrupt column parsing | D.3 |
| B23-M3 | Medium | `CsvExpenseImporter.kt` | Failed date parse silently substitutes `System.currentTimeMillis()` — historical expenses rewritten with today's date | D.3 |

#### B24 Missing (6 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B24-M1 | High | `DashboardFollowThroughRecommendation.kt` | `expiresAt` derived from `createdAt` only at construction — later `copy(createdAt = ...)` leaves `expiresAt` stale, breaking TTL invariant | D.2 |
| B24-M2 | Medium | `RecurringPattern.kt` | Missing invariants — allows negative/non-finite amounts, negative variance days, out-of-range confidence/percentage | D.3 |
| B24-M3 | Medium | `WarrantyExtractionModels.kt` | Missing invariants — allows negative `warrantyMonths`, negative `returnDays`, out-of-range `confidence` | D.3 |
| B24-M4 | Medium | `NotificationParsingModels.kt` | Missing invariants — documents positive amount and bounded confidence but enforces neither | D.3 |
| B24-M5 | High | `DashboardExpenseMapper.kt` | `DashboardExpense` → `Expense` reconstruction loses shared-expense fields — `isSharedExpense`, `myShareAmount`, `mySharePercentage` not carried through | D.2 |
| B24-M6 | Medium | `DomainTransactionFilter.kt` | `correlationId` dropped by `TransactionFilterSerializer` — recommendation-generated filters lose end-to-end trace | D.3 |

#### B25 Missing (4 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B25-M1 | Medium | `CloudWarrantyExtractionService.kt` / `WarrantyTrackerRepository.kt` | Warranty `returnDays` and `returnConditions` extracted by AI but discarded before persistence — repository falls back to merchant defaults | B.3 |
| B25-M2 | Medium | `DefaultAiCapabilityRouter.kt` | Cloud-mode routing for on-device-only capabilities skips viable on-device providers and drops to deterministic fallback | B.1 |
| B25-M3 | Medium | `HybridReceiptAssistService.kt` / `CloudReceiptAssistService.kt` | `usedImageInput=true` reported when cloud actually fell back to text-only — metadata accuracy | B.1 |
| B25-M4 | Medium | `CloudReceiptItemCategorizationService.kt` / `CloudWarrantyExtractionService.kt` | Two provider-local sanitizers repeat truncate-before-redact mistake — item-categorization and warranty-extraction prompts also vulnerable | B.1 |

#### B26 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B26-M1 | Medium | `OnDeviceReviewPriorityScorer.kt` | Batch scoring re-reads `reviewQueueRepository.getPendingReviews().first()` for every review — O(n²) duplicate checks per batch | B.1 |
| B26-M2 | Medium | `OnDeviceQueryInterpretationService.kt` | Structured-query schema has no `transactionTypes` field — even after period/category fixes, transaction-type filters cannot be expressed | B.1 |

#### B27 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B27-M1 | High | `AnomalyAlertDao.kt` | `getLastAlertForCategory()` has no `(category, alertedAt)` index — category cooldown checks scan full table | B.4 |

#### B28 Missing (0 issues)
All B28 issues confirmed present (M28-3 is same as M27-1).

#### B29 Missing (0 issues)
All B29 issues confirmed present.

#### B30 Missing (0 issues)
All B30 issues confirmed present.

### CRITICAL Registry Error (B21-B30 audit)
| Issue | Current | Should Be | Reason |
|-------|---------|-----------|--------|
| B.1 CRITICAL: Cloud receipt assist uploads raw images | STOP-SHIP CRITICAL | **Remove from CRITICAL** | B25 verification definitively classified this as FALSE POSITIVE — intentional design with separate settings |

---

## BATCH 4: B31-B40 Audit Results

### MISSING Issues (14 total)

#### B31 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B31-M1 | High | `AndroidSpeechInputGateway.kt` | Voice input starts without `RECORD_AUDIO` permission guard or `SecurityException` handling; recognizer `onError()` signals dropped | B.11 |

#### B32 Missing (0 issues)
All B32 issues confirmed present.

#### B33 Missing (0 issues)
All B33 issues confirmed present.

#### B34 Missing (0 issues)
All B34 issues confirmed present.

#### B35 Missing (3 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B35-M1 | Medium | `ExecuteFinancialQueryUseCase.kt` | `executeList()` reports `previewCount = preview.size` from capped 500-row query — underreports total matches | B.1 |
| B35-M2 | Low | Cross-component | Artifact hashing — several use cases derive `sourceHash` from `hashCode().toString()`, weaker than SHA-256 for long-lived cache identity | D.3 |
| B35-M3 | Low | Cross-component | `toReadableMessage()` / route-diagnostic formatting / failure-message assembly duplicated across AI use cases | D.3 |

#### B36 Missing (3 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B36-M1 | High (UPGRADED) | `SuggestReceiptExtractionUseCase.kt` | `sourceHash` derived from `ReceiptAssistInput.hashCode()` including `currentTimeMs` — cache effectively disabled | D.2 |
| B36-M2 | Medium | `SuggestCategoryFallbackUseCase.kt` | Broad `catch(Exception)` swallows `CancellationException` | B.1 |
| B36-M3 | High | Cross-component | Merchant analytics inconsistency — some paths group by raw merchant text, some by canonical `merchantKey`, one path exposes key as display name | C.3 |

#### B37 Missing (4 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B37-M1 | High | `BudgetCalculator.kt` | `CALENDAR` yearly budgets fall through to anniversary-style anchor instead of Jan 1 → Jan 1 | B.2 |
| B37-M2 | High | `BudgetAutopilotEngine.kt` | Autopilot monthly history drops zero-spend months — biasing trend and volatility | B.2 |
| B37-M3 | High | `BudgetAutopilotEngine.kt` | Empty/one-point histories score ~0.7 confidence — `MIN_HISTORY_MONTHS` never enforced | B.2 |
| B37-M4 | Medium | Cross-component | `FinancialStressForecastEngine ↔ MonteCarloSpendingSimulator ↔ DataQualityAssessor` — forecasting duplication, different assumptions per engine | C.3 |

#### B38 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B38-M1 | High | `SpendingChallengeManager.kt` | `REDUCE_SPENDING` challenge type has no stored baseline/reference period — progress formula identical to simple budget cap | B.10 |
| B38-M2 | High | Cross-component | `SpendingChallengeManager → SpendingChallengesViewModel` — `createChallenge()` only returns in-memory object, no repository-backed persistence | B.10 |

#### B39 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B39-M1 | High | `BillReminderManager.kt` | `calculateNextDate()` has same stringly-typed enum drift — `ANNUALLY`, `SEMI_ANNUALLY`, `IRREGULAR` fall through to default monthly advance | D.2 |

#### B40 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B40-M1 | Medium | `MonteCarloSpendingSimulator.kt` | `countRecentQualifyingWeeks()` treats any `total > 0` week as qualifying even though fitter requires 3+ distinct transaction-days — confidence overstated | D.3 |

### SEVERITY Corrections (B31-B40 audit)
| Issue | Registry | Should Be | Reason |
|-------|----------|-----------|--------|
| `BudgetRepository.getBudgetStatuses()` 2000-row cap | MEDIUM (B.2) | HIGH | B32 report rates HIGH |
| `MultiCurrencyRepository` 2000-row cap | MEDIUM (D.3) | HIGH | B32 report rates HIGH |
| `NotificationProcessingPipeline` oversized fallback | HIGH (B.6) | MEDIUM | B33 DOWNGRADED |
| `SharedExpenseManager.addExpense()` paidById different group | HIGH (B.12) | MEDIUM | B40 DOWNGRADED |

---

## BATCH 5: B41-B48 Audit Results

### MISSING Issues (15 total)

#### B41 Missing (3 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B41-M1 | High | `FinancialHealthCalculator.kt` | Legacy health calculator includes deposits, transfers, and all non-purchase rows when computing `spentToday/week/month`, volatility, spending-control penalties — inflating health score inputs | B.8 |
| B41-M2 | High | `FinancialHealthCalculator.kt` | Spending-control targets sum every budget amount together without normalizing to common period, mixing daily/weekly/monthly/yearly budgets and double-counting overall + category | B.8 |
| B41-M3 | High | `FinancialHealthScoreV2.kt` | `calculateHealthScore(periodStart, periodEnd)` always uses `budgetRepository.getBudgetStatuses().first()` computed for `timeProvider.now()` — not the requested period | B.8 |

#### B42 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B42-M1 | Medium | `FeatureExtractor.kt` / `ExpenseCategoryClassifier.kt` | Dead feature pipeline — classifier trains/classifies only on `merchantTokens`; amount, day, hour, weekend, source-package features extracted but thrown away | B.10 |

#### B43 Missing (0 issues)
All B43 issues confirmed present.

#### B44 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B44-M1 | High | Cross-component | `RevolutParser` ↔ `BankStatementParser` inconsistency — same Revolut bank produces different transaction types (TRANSFER vs PURCHASE/DEPOSIT) | C.3 |

#### B45 Missing (4 issues - all cross-component)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B45-M1 | High | Cross-component | `ReceiptTransactionMatcher` → `ReceiptMatchingWorker` → `NotificationService` chain — matching error becomes data-integrity + notification mismatch | C.3 |
| B45-M2 | Medium | Cross-component | Savings recommendation ↔ automation ↔ gamification use different proxies instead of shared contribution ledger | C.3 |
| B45-M3 | High | Cross-component | `TaxConfiguration` exposes progressive brackets but `TaxEstimator` uses flat-rate — contract/consumer mismatch | C.3 |
| B45-M4 | High | Cross-component | `ReceiptRepository.processBatch()` parallelizes receipt processing while singleton warranty path reuses one `WarrantyTextExtractor` with shared `SimpleDateFormat` — thread-safety exposed in real pipeline | C.3 |

#### B46 Missing (4 issues - all cross-component)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B46-M1 | High | Cross-component | Block-party pipeline crosses domain boundary twice — `BlockPartyDay` carries `Expense`, use case maps to `DomainExpenseSummary`, UI mapper recreates `Expense` | C.3 |
| B46-M2 | Medium | Cross-component | Two `CategoryBreakdown` types / two `PeriodRange` types — duplicate model types with overlapping semantics used by different screens/components | C.3 |
| B46-M3 | Medium | Cross-component | Inconsistent localization boundary — raw `String`, `UiText`, hardcoded currency text, direct Android `R` in domain logic | C.3 |
| B46-M4 | Medium | `SavingsGoalsViewModel.kt` | Imports entity types directly instead of domain models; domain and entity `SavingsGoal`/`GoalProtectionLevel` definitions differ | B.9 |

#### B47 Missing (1 issue)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B47-M1 | Medium | Cross-component | In-memory dedup parses JSON into normalized fields, but `RecommendationRepository` compares rows using `filterCriteria.hashCode()` — semantically identical filters with different JSON ordering bypass cross-call deduplication | C.3 |

#### B48 Missing (2 issues)
| # | Severity | File | Description | Section |
|---|----------|------|-------------|---------|
| B48-M1 | High | `ComputeMoneyRadarUseCase.kt` | Budget-risk urgency scoring uses only overrun probability — `HIGH`/`CRITICAL` risk driven by overrun magnitude can contribute zero score | B.2 |
| B48-M2 | High | Cross-component | Financial weather uses merged detected+manual recurring patterns, but dashboard forecast widgets get only manual recurring rows — weather/runway/block-party/Monte Carlo can disagree | C.3 |

### SEVERITY Corrections (B41-B48 audit)
| Issue | Registry | Should Be | Reason |
|-------|----------|-----------|--------|
| `InvestmentTracker.updatePrice()` dayChange | LOW (D.3) | MEDIUM | B41 Missed Issue #3 explicitly says MEDIUM |
| `BillReminderManager SEMI_ANNUALLY` | MEDIUM (B.11) | HIGH | B.6 says HIGH (correct), B.11 says MEDIUM — inconsistency |

### DUPLICATES to Remove (B41-B48 audit)
| Duplicate | Location A | Location B | Action |
|-----------|------------|------------|--------|
| `SavingsGamificationEngine.goal_crusher` | B.8 MEDIUM | D.3 | Remove from B.8 |
| `SavingsGamificationEngine.unlockedAt` | B.8 MEDIUM | D.3 (wrong batch B03) | Remove from B.8, fix D.3 batch to B45 |
| `goal.currentAmount / goal.targetAmount` | B.8 LOW | D.3 | Remove from B.8 |

---

## GLOBAL: Remaining Duplicates in Section D

The following items appear BOTH in a Section B pipeline bullet AND in Section D.3. Keep them only in Section D (quick wins list) and remove from Section B:

| Item | Section B Location | Section D Location | Action |
|------|-------------------|-------------------|--------|
| `ReviewPriorityFactors.fromReview()` System.currentTimeMillis | B.9 MEDIUM (line ~906) | D.3 | Remove from B.9 |
| `ReviewPriorityFactors.calculateTimeSensitivity` System.currentTimeMillis | B.9 MEDIUM | D.3 | Remove from B.9 |
| `CaptureAssistInput.amount` accepts NaN/Infinity | B.9 MEDIUM | D.3 | Remove from B.9 |
| `ReviewExplanationInputBuilder` imports data.ai.provider.internal | B.9 MEDIUM | D.3 | Remove from B.9 |
| `DashboardBriefingInputBuilder.SimpleDateFormat` | B.9 MEDIUM | D.3 | Remove from B.9 |
| `RecurrenceFrequency.IRREGULAR.intervalInMs` returns 0L | B.9 MEDIUM | D.3 | Remove from B.9 |
| `MonteCarloBudgetImpact.formatCurrency` hardcodes euro | B.9 MEDIUM | D.3 | Remove from B.9 |
| `CategoryBreakdown`/`DashboardCategoryBreakdown` duplicated | B.9 MEDIUM | D.3 | Remove from B.9 |
| `PeriodRange` duplicated | B.9 MEDIUM | D.3 | Remove from B.9 |
| `SavingsGoal` domain/entity differ | B.9 MEDIUM | D.3 | Remove from B.9 |
| `BlockPartyDay` carries Expense | B.9 MEDIUM | D.3 | Remove from B.9 |
| `NarrativeGenerator` imports app R | B.9 MEDIUM | D.3 | Remove from B.9 |
| `FinancialForecast.generatedAt` uses Instant.now() | B.9 MEDIUM | D.3 | Remove from B.9 |
| `CalculateFinancialForecastUseCase` fabricated SpendingPace | B.9 MEDIUM | D.3 | Remove from B.9 |
| `CalculateFinancialForecastUseCase` passes pastSumDaily emptyList | B.9 MEDIUM | D.3 | Remove from B.9 |
| `CalculateFinancialForecastUseCase` savings goals TRACKING | B.9 MEDIUM | D.3 | Remove from B.9 |
| `DashboardContractsAdapter.observeDashboardExpenses()` snapshots month | B.9 MEDIUM | D.3 | Remove from B.9 |
| `DashboardDataProvider` flows replace failures with empty | B.9 MEDIUM | D.3 | Remove from B.9 |
| `ComputeMoneyRadarUseCase` budget-risk urgency | B.9 MEDIUM | D.3 | Remove from B.9 |
| `ComputeMoneyRadarUseCase` depends on AnomalyAlertDao | B.9 MEDIUM | D.3 | Remove from B.9 |
| `ComputeMoneyRadarUseCase` independent fetches sequential | B.9 MEDIUM | D.3 | Remove from B.9 |
| `GroupsModule` unused imports | B.9 LOW | D.3 | Remove from B.9 |
| `EmailIngestionModule` dead bindings | B.9 LOW | D.3 | Remove from B.9 |
| `ExportOptionsViewModel` manual construction | B.9 LOW | D.3 | Remove from B.9 |
| `ExpenseTrackerApp` own CoroutineScope | B.9 LOW | D.3 | Remove from B.9 |
| `TransactionClassifier`/`BudgetMonitor` eager injection | B.9 LOW | D.3 | Remove from B.9 |
| `LifecycleObserver.onStop()` cancels scope | B.9 LOW | D.3 | Remove from B.9 |
| `BudgetMonitor.cleanup()` cancels serviceJob | B.9 LOW | D.3 | Remove from B.9 |
| `SavingsModule` engines depend on data repo | B.9 LOW | D.3 | Remove from B.9 |
| `AiSettingsRepositoryImpl` no IOException recovery | B.9 LOW | D.3 | Remove from B.9 |
| `DefaultAiCapabilityRouter` raw enum names | B.9 LOW | D.3 | Remove from B.9 |
| `GetAiRuntimeStatusUseCase` first-match message | B.9 LOW | D.3 | Remove from B.9 |
| `OnDeviceDedupeJudgeService` Enum.valueOf | B.9 LOW | D.3 | Remove from B.9 |
| `HybridReceiptAssistService` mutable singleton | B.9 LOW | D.3 | Remove from B.9 |
| `CloudPiiSanitizer` broad phone regex | B.9 LOW | D.3 | Remove from B.9 |
| `CloudJsonParser` first brace block | B.9 LOW | D.3 | Remove from B.9 |
| `CloudRetryPolicy` ignores Retry-After | B.9 LOW | D.3 | Remove from B.9 |
| `CloudCorrelation` 8-char UUID | B.9 LOW | D.3 | Remove from B.9 |
| `DailyBriefingWorker` catches CancellationException | B.9 LOW | D.3 | Remove from B.9 |
| `ExpenseGroupDao.insertGroupWithMembers()` unused | B.9 LOW | D.3 | Remove from B.9 |
| `ExpenseGroupDao` groupId <= 0 guard | B.9 LOW | D.3 | Remove from B.9 |
| `ExpenseGroupDao` memberIds guard | B.9 LOW | D.3 | Remove from B.9 |
| `ManualRecurringExpenseDao.insert()` REPLACE | B.9 LOW | D.3 | Remove from B.9 |
| `MerchantNormalizationDao.linkAliasToCanonical()` | B.9 LOW | D.3 | Remove from B.9 |
| `ExpenseDao.getChanges()` | B.9 LOW | D.3 | Remove from B.9 |
| `ScannedReceiptDao.linkToExpense()` | B.9 LOW | D.3 | Remove from B.9 |
| `ReturnWindowDao` returns single row | B.9 LOW | D.3 | Remove from B.9 |

---

## STATISTICS UPDATE (after all corrections)

After adding all missing issues (~113 new items) and removing all duplicates (~70 items), the net change is approximately +43 items.

Updated estimates:
- **Total unique issues:** ~620 (was ~580, +43 net)
- **Section A:** 10 epics (unchanged)
- **Section B:** ~310 issues across 12 pipelines (was ~297, +13 new)
- **Section C:** ~35 cross-component dependencies (was ~23, +12 new)
- **Section D:** ~275 quick wins (was ~270, +18 new after dedup)

---

## INSTRUCTIONS FOR CODER

Apply changes to `MASTER-ISSUE-REGISTRY.md` in this order:

1. **First:** Remove the FALSE POSITIVE from B.1 CRITICAL (cloud receipt assist raw image upload)
2. **Second:** Add A.9 and A.10 if not already present (check current file)
3. **Third:** Process each batch's MISSING issues ONE AT A TIME (B01, then B02, etc.)
4. **Fourth:** Apply all SEVERITY corrections
5. **Fifth:** Remove all DUPLICATES listed above
6. **Sixth:** Fix the DATA ERROR (`WarrantyTrackerScreen` → `ReviewScreen`)
7. **Seventh:** Update the Statistics Summary

Work through batches sequentially (B01 → B48). After each batch, verify the file still has valid markdown structure.
