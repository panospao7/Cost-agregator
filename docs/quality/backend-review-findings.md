# Backend Review Findings

Comprehensive review of the ExpenseTracker backend across 6 batches, 48 total issues identified.

---

## Batch 1 — Domain Engines & Use Cases
**Score:** 52/100 | **Issues:** 10 (3 CRITICAL, 7 MAJOR)

| Issue ID | Severity | File:Line | Type | Description | Suggested Fix |
|---|---|---|---|---|---|
| C1 | CRITICAL | `ComputeDashboardWidgetsUseCase.kt:657-669` | Logic/Performance | `calculateStreakData()` loops forever (`while(true)`) for users with zero purchase days — no lower bound or exit condition | Add hard stop (e.g., month/app start boundary) and explicit empty-history handling |
| C2 | CRITICAL | `BudgetForecastingEngine.kt:47-48,271-274` | Logic | Forecast spent-to-date is effectively zero because `periodStart` is set to `now`, then queried `[now, now]` — returns no data | Derive period start from budget period window (via `BudgetCalculator`) and query real elapsed period |
| C3 | CRITICAL | `BudgetForecastingEngine.kt:217-219,282-285` | Logic | Risk classification uses `getSpentAmountSync()` that always returns `0.0` (stub), understating risk systematically for all users | Remove stub; fetch real spent amount (suspend path) before risk tiering |
| H1 | HIGH | `BudgetForecastingEngine.kt:179-180` | Logic | Predicted spending is clamped to `budget.amount`, which masks true overspend scenarios and distorts `overspendProbability` | Do not cap prediction; cap only UI messaging if needed |
| H2 | HIGH | `AutomatedSavingsRuleEngine.kt:78-83` | Logic | Percentage savings rule uses wrong predicate (`!= DEPOSIT && amount >= 0`), allowing negative non-deposit transactions to be treated as income triggers | Replace with strict deposit check (`transactionType == DEPOSIT`) and normalized positive amount handling |
| H3 | HIGH | `DetectDuplicateExpenseUseCase.kt:22-27` | Performance | Duplicate detection loads ALL expenses then filters in memory — scales poorly, OOM risk for large datasets | Query repository by time window (and optionally amount band/merchant key) to avoid full-table scans |
| H4 | HIGH | `CategoryInsightEngine.kt:58` | Error Handling | Non-null assertion `category!!` can crash when category metadata is missing | Replace `category!!` with safe handling + skip/fallback category |
| H5 | HIGH | `BudgetRecommendationEngine.kt:143` | Logic | Recommendations are sorted LOW→CRITICAL, surfacing least important advice first | Sort descending by priority (or explicit comparator with CRITICAL first) |
| H6 | HIGH | `ProcessReceiptUseCase.kt:3`, `ComputeDashboardWidgetsUseCase.kt:25`, `BudgetRecommendationEngine.kt:3`, `SavingsGamificationEngine.kt:3` | Architecture | Domain use cases/engines are coupled to Android/UI artifacts (`android.net.Uri`, `R`, `UiText` resource usage), violating clean domain isolation | Move framework/resource concerns to presentation/data adapters; keep domain contracts pure |
| H7 | HIGH | `TransferDirectionAnalytics.kt:95-98,147-152` | State Management | Transfer accuracy tracking is mathematically inconsistent (recomputed from current detected count, not persisted correct-count state) and mutable singleton state is not concurrency-safe | Store explicit counters (`correctDetections`, `totalDetections`) and update atomically (mutex/atomic state update) |

---

## Batch 2 — Data Repositories & DAOs
**Score:** 58/100 | **Issues:** 10 (1 CRITICAL, 4 HIGH, 5 MEDIUM)

| Issue ID | Severity | File:Line | Type | Description | Suggested Fix |
|---|---|---|---|---|---|
| C5 | CRITICAL | `ReviewQueueRepository.kt:75,193`, `PendingReview.kt:9-14` | Consistency | Repository writes `status = "PROCESSING"` and `status = "DUPLICATE"`, but `PendingReviewStatus` enum does not define these values — can persist invalid enum values and crash reads/deserialization later | Use enum-typed DAO params (`PendingReviewStatus`) and only persist valid enum values, or add/migrate enum values explicitly if these states are required |
| H8 | HIGH | `ReceiptRepository.kt:349,400,518,526` | Architecture | Data-layer repository directly depends on UI-layer types (`ui.screens.debug.DebugData`, `DebugIssueDetector`), violating clean architecture boundaries | Move debug DTO/detection logic to domain/data module, or define data-layer debug models and map to UI models in ViewModel/UI |
| H9 | HIGH | `ReceiptRepository.kt:287-295` | Transaction | `createExpenseFromReceipt()` inserts expense and links receipt in separate DAO calls without a DB transaction — a failure/crash between steps can leave inconsistent state (expense created, receipt unlinked) | Wrap all DB mutations in `database.withTransaction { ... }`, and keep non-DB side effects after commit |
| H10 | HIGH | `ExpenseRepository.kt:283-285` | Transaction | Bulk merchant rename updates `expenses` and `pending_reviews` in separate operations without transaction — partial success can desynchronize merchant identity across tables | Inject `AppDatabase` and execute both updates in one `withTransaction` block |
| H11 | HIGH | `NotificationRepository.kt:120-126,139-147` | Transaction | Multi-table destructive/restore operations (`deleteAll`, `restoreDebugSnapshot`) are not transactional — mid-operation failure can leave partially wiped/restored data | Wrap each multi-table operation in a single Room transaction |
| M1 | MEDIUM | `GroupsRepositoryImpl.kt:24-31` | Performance | `getActiveGroupsWithDetails()` executes N+1 queries (1 for groups + 2 per group for members/expenses) — degrades with group count growth | Add DAO-level aggregate query (`@Transaction` + relations) or batch-fetch by group IDs and assemble in memory |
| M2 | MEDIUM | `PendingReviewDao.kt:68` | Query | Duplicate check query uses `LIKE '%' \|\| :merchantPattern \|\| '%'` on hot path — leading wildcard prevents index use and can cause full scans as pending table grows | Store/query a normalized merchant key column (indexed) and match with equality + date range |
| M3 | MEDIUM | `NotificationProcessingPipeline.kt:555-560` | Performance | Per-expense category lookup (`database.categoryDao().getById(...)`) in a map creates N+1 DB calls during subscription detection | Collect distinct category IDs and fetch once via `CategoryDao.getByIds`, then map locally |
| M4 | MEDIUM | `ExpenseDao.kt:192-199` | Query | `searchMerchants()` groups by `merchantKey` but selects non-aggregated `categoryId`/`merchant`, yielding nondeterministic category values per SQL semantics | Make selected columns deterministic (e.g., join latest row per merchant, or aggregate with explicit rule) |
| M5 | MEDIUM | `ReceiptRepository.kt:425-426` | Consistency | Statement dedupe uses capped snapshots (`getAllFlow(1000).first()`, pending limit 500) — older records outside caps are ignored, so duplicates can slip through | Use indexed DB-side duplicate checks per transaction (e.g., `isDuplicate`/time-window query) instead of bounded in-memory snapshots |

---

## Batch 3 — Database Schema & Migrations
**Score:** 54/100 | **Issues:** 6 (1 CRITICAL, 2 HIGH, 3 MEDIUM)

| Issue ID | Severity | File:Line | Type | Description | Suggested Fix |
|---|---|---|---|---|---|
| C6 | CRITICAL | `AppDatabase.kt:1722`, `AppDatabase.kt:1750` | Migration/FK | `MIGRATION_49_50` drops parent tables (`expenses`, `categories`) while FK enforcement is active — in SQLite this triggers FK `ON DELETE` actions on child tables, causing silent data loss/nulling (e.g., `group_expenses`/`split_item_assignments` cascades, `merchant_categories` cascades, many `categoryId` links set NULL) | Rewrite 49→50 using FK-safe rebuild strategy (disable FK checks around rebuild + `foreign_key_check`, or rebuild parent/children in a controlled order without dropping referenced parents under active FKs); add migration test seeded with linked rows to prove no relationship loss |
| H12 | HIGH | `Expense.kt:26-39`, `ExpenseDao.kt:33-44,73,80,226,242` | Index | No standalone index on `expenses.date` despite many core queries doing `ORDER BY date` and date-range scans — query plans show full scans + temp sort for hot paths | Add `Index(value = ["date"])` to `Expense` and a migration `CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses(date)` |
| H13 | HIGH | `BankConnection.kt:27-29`, `AppDatabase.kt:1469-1471` | Schema/Security | Sensitive bank tokens are stored as plain `TEXT` columns (`accessToken`, `refreshToken`) — at-rest credential exposure if DB is extracted | Encrypt tokens before persistence (Android Keystore/Tink), store ciphertext+metadata (or store only key alias), and migrate existing plaintext data |
| M6 | MEDIUM | `SpendingPersonalityProfileEntity.kt:14-17`, `SpendingPersonalityProfileDao.kt:33-40` | Index | DAO hot query `WHERE isActive = 1 LIMIT 1` has no supporting index in current schema/entity | Add `Index(value = ["isActive"])` and corresponding migration to create `index_spending_personality_profiles_isActive` |
| M7 | MEDIUM | `ManualRecurringExpense.kt:8-26`, `ManualRecurringExpenseDao.kt:19-23,41,68-72` | Index | Frequent recurring-expense queries filter/sort on `isActive`, `isSubscription`, `nextDate`, and lookup by `merchant`, but table has no indices | Add indices `(isActive, nextDate)`, `(isSubscription, isActive, nextDate)`, and `(merchant)` (or unique if business rules allow) |
| M8 | MEDIUM | `EmailReceiptSource.kt:25,40-41`, `AppDatabase.kt:2896-2908`, `EmailReceiptIngestionService.kt:76-82` | Schema/Consistency | `emailMessageId` is `NOT NULL DEFAULT ''` with a unique index — if upstream provides empty/blank IDs, inserts can replace/conflict unexpectedly and corrupt source linkage | Enforce non-empty `messageId` at ingestion; alternatively make column nullable and use a unique partial index (`WHERE emailMessageId IS NOT NULL`) plus fallback deterministic dedupe key |

---

## Batch 4 — DI & Infrastructure
**Score:** 66/100 | **Issues:** 7 (3 HIGH, 4 MEDIUM)

| Issue ID | Severity | File:Line | Type | Description | Suggested Fix |
|---|---|---|---|---|---|
| H14 | HIGH | `GeoapifyGeocodingService.kt:61-63,137` | Security/Network | Geoapify API key is embedded in the URL query string and the full URL is logged (`Log.d(TAG, "==> Geoapify request: $url")`), which can leak secrets via logs/telemetry | Stop logging full URLs, redact query params, and avoid query-string secrets where possible (prefer auth headers if provider supports them) |
| H15 | HIGH | `SmartReceiptAssistService.kt:42-52,69-145` (impact at `SuggestReceiptExtractionUseCase.kt:110-112`) | Scoping/Threading | `SmartReceiptAssistService` is `@Singleton` but stores request-specific mutable state (`lastUsedImageInput`, `lastAttemptDetails`) — concurrent requests can race and return wrong metadata for another receipt | Remove mutable shared state from singleton; return "usedImageInput/attempt details" as part of `suggest()` result object, or make the service stateless/per-request scoped |
| H16 | HIGH | `CloudReceiptItemCategorizationService.kt:56-64` | Network | HTTP response is not wrapped in `.use {}`; connection/body lifecycle is manually handled and can leak resources on some failure paths | Use `client.newCall(request).execute().use { response -> ... }` and parse body inside that block |
| M9 | MEDIUM | `Cloud*.kt` (e.g., `CloudDashboardBriefingService.kt:39-42`, `CloudReviewExplanationService.kt:39-42`) + `NetworkModule.kt:19-33` | DI/Network | Cloud AI services instantiate their own `OkHttpClient` instead of receiving DI-provided clients — this fragments timeout/interceptor/retry/security policy and bypasses centralized infra controls | Provide shared/base OkHttp client(s) via Hilt with qualifiers and inject them into cloud services |
| M10 | MEDIUM | `CurrencyRatesRepositoryImpl.kt:34` (and similar direct `Dispatchers.*` usage) | Threading/DI | Custom dispatcher DI exists, but infra code still hardcodes `Dispatchers.IO/Default`, reducing testability and making threading behavior inconsistent | Inject `@IoDispatcher/@DefaultDispatcher` where used, especially in repositories/services doing blocking I/O |
| M11 | MEDIUM | `EmptyStateModule.kt:3-18` | DI/Dead Code/Architecture | DI module in infrastructure layer depends directly on Compose/UI/navigation classes, creating layer coupling and violating clean architecture boundaries | Move UI-only wiring to presentation layer module, bind interfaces in DI layer, and keep infra modules UI-agnostic |
| M12 | LOW | `AppModule.kt:7-13`, `BudgetForecastModule.kt:13-17`, `InvestmentModule.kt:12-16`, `Phase4FeaturesModule.kt:11-15` | Dead Code | Multiple placeholder/empty modules add noise without providing bindings | Remove unused modules or keep a single documented placeholder with explicit rationale and cleanup ticket |

---

## Batch 5 — AI & External Services
**Score:** 61/100 | **Issues:** 8 (1 CRITICAL, 3 HIGH, 4 MEDIUM)

| Issue ID | Severity | File:Line | Type | Description | Suggested Fix |
|---|---|---|---|---|---|
| C10 | CRITICAL | `GeoapifyGeocodingService.kt:62,137` | Security | Geoapify request logging prints the full URL, and the URL contains `apiKey` as a query parameter — leaks secrets into logs/crash reports/logcat collection | Never log full URLs when credentials are in query params. Build a redacted log URL (mask/remove `apiKey`) or log only endpoint + non-sensitive params |
| C11 | HIGH | `FinancialQueryInterpretationInputBuilder.kt:21-33`, `CloudQueryInterpretationService.kt:98-124`, `DedupeJudgeInputBuilder.kt:63,77,91` | Privacy | Redaction policy (`redactBeforeCloud`) is not applied in query interpretation and dedupe input building — merchant names, conversation history, notification text previews, and expense notes can be sent to cloud even when user enabled redaction | Inject/use `AiPolicy.shouldRedact(...)` in these builders/use cases and strip/hash/clip sensitive free text before cloud routing |
| H17 | HIGH | `SmartReceiptAssistService.kt:157-188` | Error Handling | Retry cascade does not match documented behavior — route gating allows only cloud *or* on-device attempts, so cloud failure does not fall through to on-device AI (and vice versa), despite comments claiming a 5-step cross-route chain | Decouple retry chain from single route gate, or explicitly implement ordered fallback matrix (Cloud Vision → On-device Vision → Cloud Text → On-device Text → deterministic) when policy allows each step |
| H18 | HIGH | `CloudReceiptAssistService.kt:70`, `CloudReviewExplanationService.kt:54`, `CloudDashboardBriefingService.kt:63`, `CloudDedupeJudgeService.kt:56`, `CloudCategorizationAssistService.kt:42`, `CloudReceiptItemCategorizationService.kt:52`, `CloudQueryInterpretationService.kt:54`, `CloudWarrantyExtractionService.kt:74` | Security | Gemini API key is appended in URL query string (`?key=...`) across cloud AI providers — query params are more likely to leak via instrumentation/proxy logs and diagnostics | Prefer header-based auth (`x-goog-api-key`) and avoid putting keys in URLs. If API contract forces query param, enforce strict redacted logging and centralized request wrapper |
| M13 | MEDIUM | `CloudReceiptAssistService.kt:81,84`, `CloudReviewExplanationService.kt:65,68`, `CloudDedupeJudgeService.kt:67,70` | Error Handling | Error body is read twice via `response.body?.string()` — after first read, second read returns empty/consumed stream, causing lost diagnostics and inconsistent `HttpError` payload | Read error body once into a local variable and reuse for logging + returned error object |
| M14 | MEDIUM | `CloudWarrantyExtractionService.kt:154-156` | Parsing | JSON extraction uses regex `\{[^{}]*\}`, which breaks for nested objects and may parse the wrong fragment from model output — brittle and can silently drop valid responses | Reuse robust brace-balanced JSON extraction (as done in other providers) or enforce strict JSON response and parse directly |
| M15 | MEDIUM | `CloudWarrantyExtractionService.kt:190` | Privacy | On parse failure, full model response is logged (`$responseBody`), which may contain sensitive receipt data/PII | Remove raw payload logging; log only bounded metadata (length, hash, parse error code) and optionally first redacted chars |
| M16 | MEDIUM | `CloudReceiptItemCategorizationService.kt:56-58` | Error Handling | HTTP `Response` is not wrapped in `.use {}` — risks connection/resource leakage under exceptions and degrades client pool behavior | Wrap call in `client.newCall(request).execute().use { response -> ... }` |

---

## Batch 6 — Architecture & Cross-Layer Dependencies
**Score:** 24/100 | **Issues:** 7 (5 CRITICAL, 1 HIGH, 1 MAJOR)

| Issue ID | Severity | File:Line | Type | Description | Suggested Fix |
|---|---|---|---|---|---|
| C12 | CRITICAL | `DashboardDataProvider.kt:3-12`, `FinancialWeatherRepository.kt:3-9` | Circular Dependency | Domain and Data are mutually dependent (hard architectural cycle): `DashboardDataProvider` imports `data.repository.*`, while `FinancialWeatherRepository` imports `domain.*` engines/services — creates `domain ↔ data` compile-time coupling (and effectively `domain ↔ data ↔ ui` once UI is included) | Define repository/interfaces + domain models in domain only; move all concrete repos/DAOs/entities to data; data implements domain contracts |
| C13 | CRITICAL | `UiText.kt:3-8,110-145` | Domain Violation | Domain model depends on Compose/UI runtime (`androidx.compose.*`) and Android Context (`UiText` is rendering-aware) | Keep `UiText` as pure token/value object (resource key + args), move `@Composable` and context/string resolution extensions to presentation module |
| C14 | CRITICAL | `ReceiptRepository.kt:349,400,518,526` | Data Violation | Data repository references UI classes directly via FQCN (`DebugData`, `DebugIssueDetector`), violating dependency direction (`data -> ui`) | Move debug DTO/detector to domain or data-debug package and expose via interface; UI should consume mapped results, not be referenced by data |
| C15 | CRITICAL | `CurrencyManagementViewModel.kt:5,50,97`, `SubscriptionManagementViewModel.kt:5-8,63-66`, `ManualRecurringExpenseViewModel.kt:5,34,50`, `ExportOptionsViewModel.kt:5-6,52-53,73,123`, `ReceiptScanViewModel.kt:9,141,1003,1052` | UI Bypass | Multiple ViewModels bypass repositories/use-cases and call DAOs directly (tight DB coupling in UI) | Introduce domain use cases / repository interfaces for all DAO operations; ViewModels should depend only on use cases (or domain-facing repos) |
| C16 | CRITICAL | `EmptyStateModule.kt:3-18` | Layer Boundary | EmptyState DI module imports UI components/navigation/Compose icons in infrastructure layer, matching documented violation | Move action contracts to domain, keep UI navigation/icon mapping in presentation, and bind only domain interfaces in DI |
| H19 | HIGH | `AdvancedAnalyticsModels.kt:3` | Domain Violation | Domain analytics models import Compose annotation (`@Immutable`), pulling presentation framework into domain | Remove Compose annotations from domain models or add presentation-layer wrappers annotated for Compose |
| H20 | HIGH | `NaturalLanguageSearchEngine.kt:3-10,373-405` | Layer Boundary | Domain uses Android framework directly for speech/OCR and app resources, mixing platform/presentation concerns into domain (`Context`, `SpeechRecognizer`, `Uri`, `R`) | Extract platform adapters (speech/OCR/resource resolution) to data/presentation; domain should depend on abstractions (`SpeechInput`, `OcrGateway`, `StringProvider` interfaces) |

---

## Recommended Fix Priority

### Phase 1: Critical Data Safety (Fix First)
1. **C6**: Fix Migration 49→50 FK-safe rebuild
2. **C5**: Fix PendingReview enum consistency
3. **H13**: Encrypt bank tokens
4. **C1, C2, C3**: Fix infinite loop, forecast zero, and spent stub

### Phase 2: Architecture Boundaries
5. **C12**: Break domain ↔ data circular dependency
6. **C14**: Remove UI imports from ReceiptRepository
7. **C15**: Add repository layer to 5 DAO-bypassing ViewModels
8. **C13, H19**: Remove Compose imports from domain models

### Phase 3: Security & Privacy
9. **C10**: Redact API keys from logs
10. **C11**: Apply redaction policy to cloud input builders
11. **H14**: Stop logging full URLs with API keys
12. **H15**: Make SmartReceiptAssistService stateless

### Phase 4: Performance & Reliability
13. **H9-H11**: Wrap multi-step operations in @Transaction
14. **H12**: Add index on `expenses.date`
15. **H3**: Fix duplicate detection to use DB queries
16. **M1-M16**: Address medium issues incrementally
