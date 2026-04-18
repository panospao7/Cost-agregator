# PLAN-PHASE2-OPEN-FIXES-V3.md

## Purpose
Focused fix plan for the 13 remaining open issues from PHASE2-FINAL-COMPREHENSIVE-AUDIT.md.

**Status before this plan:** 52 issues resolved across 12 batches.

---

## Batch V3-1: Currency/Text Localization (6 files)
Hardcoded `€` / `EUR` literals in user-facing code.

### Files
- `domain/logic/NarrativeGenerator.kt:44-50,56-62,95,118,138,153`
- `domain/analytics/InsightsEngine.kt:149,186,201,215`
- `domain/savings/SavingsGamificationEngine.kt:106,129`
- `domain/model/budget/MonteCarloBudgetImpact.kt:46`
- `ui/screens/receiptscan/ReceiptScanScreen.kt:73`
- `domain/logic/SynthesisEngine.kt:68-71,253-255,517-526`

### Fix
Replace all `"€${...}"` and `"EUR"` fallbacks in display strings with `CurrencyFormatter.format(amount, currencyCode)` or `UiText.StringResource`. Keep raw `EUR` only where it is a true storage/default currency code, not UI text. Pass `currencyCode` through or use `CurrencyFormatter.defaultCurrency` as fallback.

---

## Batch V3-2: UI/State Mutation & Error Handling (2 files)

### Issue 2.1: ReviewScreen composition mutation
**File:** `ui/screens/review/ReviewScreen.kt:432-460`, `ReviewViewModel.kt:595-604`

**Problem:** `consumePrefilled...()` methods called during composition (render-time), causing state mutation during recomposition.

**Fix:** Move one-shot consumption into `LaunchedEffect` / `DisposableEffect` (side-effect) or hoist stable dialog state before composition. Use event-based state: set a "consume" event flag, process in effect block.

### Issue 2.2: AdvancedAnalyticsViewModel error collapsing
**File:** `ui/screens/analytics/AdvancedAnalyticsViewModel.kt:35-43`, `AdvancedAnalyticsScreen.kt:51-56`

**Problem:** Load failures collapse to `dashboardData = null`; screen shows silent blank.

**Fix:** Add typed `UiState.Error` sealed class variant. Expose `analyticsState: AnalyticsUiState` with `Loading | Success(data) | Error(message)`. Render error/retry UI instead of null.

---

## Batch V3-3: AI Provider & Policy (3 files)

### Issue 3.1: CloudJsonParser returns first brace-balanced not first valid JSON
**File:** `data/ai/provider/internal/CloudJsonParser.kt:12-55`

**Problem:** `extractFirstJsonObject()` returns first brace-balanced object string, not the first successfully parseable JSON object. Malformed prefixes cause parse failures.

**Fix:** After finding a brace-balanced candidate, try `JSONObject(candidate)`. If it throws, continue scanning for the next candidate. Return the first one that actually parses.

### Issue 3.2: Warranty routing coupled to receipt-assist toggle
**Files:** `domain/ai/policy/DefaultAiCapabilityRouter.kt:251-252`, `domain/ai/policy/AiPolicyImpl.kt:21-22,40-41`

**Problem:** `WARRANTY_EXTRACTION` capability gated by `receiptAssistEnabled`. Warranty should be independent.

**Fix:** Create separate `warrantyExtractionEnabled: Boolean` setting. Split capability routing in `DefaultAiCapabilityRouter` so warranty check uses its own flag, not `receiptAssistEnabled`.

### Issue 3.3: AI settings corruption recovery missing
**File:** `data/repository/AiSettingsRepositoryImpl.kt:19,56-57`

**Problem:** No DataStore corruption handler. Bad preferences file crashes/bricks reads.

**Fix:** Wrap DataStore read in try-catch. On corruption exception, clear the corrupted entry and re-initialize with defaults. Log the error.

---

## Batch V3-4: Recommendation & Bank Integration (2 files)

### Issue 4.1: RecommendationDeduplicator omits ownership
**File:** `service/RecommendationDeduplicator.kt:83-97`

**Problem:** Dedup signature doesn't include `ownership` (MINE/NOT_MINE/SHARED). Recommendations from different owners collapse together.

**Fix:** Add `ownership` to the dedup signature in `computeDedupKey()`. Use `ownership.name` or equivalent string in the key composition.

### Issue 4.2: BankApiIntegration remains demo stub
**File:** `domain/bank/BankApiIntegration.kt:83-99,104-127,158-160,267-289`

**Problem:** Demo OAuth URLs/tokens, mock transactions - not production-real.

**Fix:** Either (a) replace with real provider OAuth adapters, or (b) gate everything behind `BankApiConfig.isDemoMode = true` and make demo mode explicit with compile-time annotation `@StubForDemo` or runtime `require(!isDemo)` guards that fail clearly in production builds.

---

## Batch V3-5: Date/Export Utilities (2 files)

### Issue 5.1: AccountingExportRepository SimpleDateFormat for filenames
**File:** `data/repository/AccountingExportRepository.kt:78-83`

**Problem:** Uses locale-dependent `SimpleDateFormat` for export filenames - inconsistent across locales.

**Fix:** Replace with `LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))` for deterministic, locale-independent filenames.

### Issue 5.2: DateFormatterUtils deprecated ThreadLocal cache
**File:** `domain/util/DateFormatterUtils.kt:20-22,45-52,63-88`

**Problem:** Still exposes deprecated `SimpleDateFormat`-backed `ThreadLocal` cache.

**Fix:** Migrate remaining call sites to `java.time` formatters (the `javaTime()` methods already exist). Remove the `ThreadLocal<SimpleDateFormat>` cache entirely once all call sites are migrated.

---

## Batch V3-6: Groups & Entity Defaults (2 files)

### Issue 6.1: AddGroupMemberUseCase coordinator validation collapse
**Files:** `domain/groups/usecase/AddGroupMemberUseCase.kt:34-45`, `data/repository/GroupsRepositoryImpl.kt:90-101`, `data/database/GroupTransactionCoordinator.kt:106-125`

**Problem:** Coordinator validation failures return generic `null`/error, losing the real failure reason.

**Fix:** Return a typed `Result<Unit, GroupValidationError>` or sealed class from the coordinator. Propagate typed error through repository to use case. Surface the actual validation error (e.g., "user already in group", "max members reached").

### Issue 6.2: ReturnWindow wall-clock entity defaults
**File:** `data/database/entity/ReturnWindow.kt:47-48`

**Problem:** `createdAt`/`updatedAt` default to `System.currentTimeMillis()` in entity, not via TimeProvider.

**Fix:** Remove wall-clock defaults from entity. Set `createdAt` at the repository/use-case layer via `timeProvider.now()`. Use `@Entity` listeners or set in `WarrantyTrackerRepository.upsertReturnWindowForReceipt()`.

---

## Validation Commands
```bash
./gradlew.bat :app:compileDebugKotlin
```

---

## Done When
- Zero hardcoded `€` literals in display strings (NarrativeGenerator, InsightsEngine, SavingsGamificationEngine, MonteCarloBudgetImpact, ReceiptScanScreen, SynthesisEngine)
- ReviewScreen renders without calling consume methods during composition
- AdvancedAnalyticsScreen shows error state with retry on load failure
- CloudJsonParser returns first successfully parseable JSON object
- Warranty extraction has independent capability toggle
- AI settings recover gracefully from DataStore corruption
- Recommendation dedup includes ownership
- BankApiIntegration is explicitly stubbed or replaced with real adapters
- Export filenames use java.time Locale.US formatter
- DateFormatterUtils SimpleDateFormat surface removed
- AddGroupMember validation returns typed errors
- ReturnWindow timestamps set via timeProvider at write sites