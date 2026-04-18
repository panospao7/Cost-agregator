# Phase 2 Final Comprehensive Audit

Methodology:
- Re-checked the current source tree for all 12 batches.
- Attempted the requested `rg` hardcoded-currency scan first; `rg` is not installed in this environment, so equivalent `grep` searches were used instead.
- Cross-referenced current code with the previously approved Phase 2 review artifacts and the local commit history.

```markdown
VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] User-visible hardcoded currency presentation still exists after the currency sweep (`€` / `EUR` literals remain in runtime-facing code) - `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:44-50,56-62,95,118,138,153`; `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:149,186,201,215`; `app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt:106,129`; `app/src/main/java/com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt:46`; `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt:73` - route all display text through centralized currency/text formatting and remove literal `€` fallbacks from user-facing code.
- [ISSUE-2] [MAJOR] `ReviewScreen` mutates ViewModel state during composition by calling destructive `consume...()` methods while rendering the dialog - `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt:432-460`; `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt:595-604` - move one-shot consumption into a side-effect/event pipeline or hoist stable dialog state before composition.
- [ISSUE-3] [MAJOR] `AdvancedAnalyticsViewModel` collapses all load failures to `dashboardData = null`, and the screen has no error state, so failures become a silent blank screen - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsViewModel.kt:35-43`; `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsScreen.kt:51-56` - expose a typed error state and render retry/error UI instead of nulling the payload.
- [ISSUE-4] [MAJOR] `AiSettingsRepositoryImpl` still has no DataStore corruption-recovery path - `app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt:19,56-57` - provide a corruption handler / recovery strategy so a bad preferences file does not brick AI settings reads.
- [ISSUE-5] [MAJOR] `CloudJsonParser.extractFirstJsonObject()` returns the first brace-balanced object, not the first valid JSON object - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt:12-55` - continue scanning until a candidate parses successfully as JSON.
- [ISSUE-6] [MAJOR] Warranty extraction routing is still incorrectly coupled to the receipt-assist toggle - `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt:251-252`; `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt:21-22,40-41` - split warranty capability gating from receipt-assist settings.
- [ISSUE-7] [MAJOR] `RecommendationDeduplicator` omits ownership from its signature, so distinct recommendations can collapse together - `app/src/main/java/com/yourname/expensetracker/service/RecommendationDeduplicator.kt:83-97` - include ownership (and any other navigation-relevant filters) in the dedupe signature.
- [ISSUE-8] [MAJOR] `BankApiIntegration` still uses demo OAuth URLs/tokens and mock transactions, so the connectivity path is not production-real - `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt:83-99,104-127,158-160,267-289` - replace placeholder flows with real provider adapters or clearly isolate this behind a non-production stub boundary.
- [ISSUE-9] [MINOR] `AccountingExportRepository` still uses locale-dependent `SimpleDateFormat` for export filenames - `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt:78-83` - use a deterministic `java.time` formatter with a stable locale for filesystem-safe filenames.
- [ISSUE-10] [MINOR] `DateFormatterUtils` still exposes deprecated `SimpleDateFormat` helpers backed by a `ThreadLocal` cache - `app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt:20-22,45-52,63-88` - finish migrating call sites to `java.time` and remove the deprecated cache layer.
- [ISSUE-11] [MAJOR] The `AddGroupMemberUseCase` path still collapses coordinator validation failures into a generic null/error result, so end-to-end validation semantics are lost - `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupMemberUseCase.kt:34-45`; `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt:90-101`; `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt:106-125` - return a typed result/error from coordinator/repository and preserve the real failure reason.
- [ISSUE-12] [MINOR] `ReturnWindow` still uses wall-clock constructor defaults for `createdAt` / `updatedAt` - `app/src/main/java/com/yourname/expensetracker/data/database/entity/ReturnWindow.kt:47-48` - inject/populate timestamps via `TimeProvider` at creation/update sites instead of `System.currentTimeMillis()` defaults.
- [ISSUE-13] [MAJOR] `NarrativeGenerator` / `SynthesisEngine` still mix domain logic with raw UI text, hardcoded currency formatting, and wall-clock time - `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:31-68,95,118,138,153`; `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:68-71,253-255,517-526` - move user text to `UiText`/domain text keys, route money formatting through currency services, and use injected time consistently.

Coverage:
- Requirements met: yes - all 12 batches were re-verified, resolved items were mapped to commit hashes, open items were re-checked in the current tree with file:line references, and the remaining hardcoded-currency problem was re-scanned with an equivalent grep-based search.
- Testing adequate: no - this final pass was a source/grep/commit audit only; no new targeted tests were run as part of this write-up.
```

## Summary Counts

- Batches reviewed: 12/12
- Resolved findings confirmed: 52
- Still-open findings confirmed: 13
- Fully closed batches: 5 (`1`, `3`, `4`, `8`, `10`)
- Partially open batches: 7 (`2`, `5`, `6`, `7`, `9`, `11`, `12`)

Resolved batch anchor commits:
- Batch 1: `5b2f87d`
- Batch 2: `119f15c`
- Batch 3: `9a75bd1`
- Batch 4: `45b098d`
- Batch 5: `52e5c58`
- Batch 6: `4690393`
- Batch 7: `47361f1`
- Batch 8: `174b30c`
- Batch 9: `fcd3907`
- Batch 10: `1a406ba`
- Batch 11: `bc41532`
- Batch 12: `5373e49`

## Batch-by-Batch Audit

### Batch 1
Status: RESOLVED

- RESOLVED - Challenge creation end-to-end (`showCreateDialog` flow) - commit `5b2f87d`

### Batch 2
Status: PARTIALLY OPEN

- RESOLVED - JSON dedup - commit `119f15c`
- RESOLVED - file-size guard - commit `119f15c`
- RESOLVED - parallel caps - commit `119f15c`
- RESOLVED - route metadata - commit `119f15c`
- STILL OPEN - AI settings corruption recovery is missing - `app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt:19,56-57`
- STILL OPEN - `CloudJsonParser` still returns the first brace-balanced object instead of the first valid JSON object - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt:12-55`
- STILL OPEN - warranty extraction is still gated by `receiptAssistEnabled` instead of an independent capability toggle - `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt:251-252`; `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt:21-22,40-41`

### Batch 3
Status: RESOLVED

- RESOLVED - zero-spend months - commit `9a75bd1`
- RESOLVED - sparse confidence handling - commit `9a75bd1`
- RESOLVED - seasonal neutral handling - commit `9a75bd1`
- RESOLVED - N+1 to batch read conversion - commit `9a75bd1`
- RESOLVED - MoneyRadar parallel execution - commit `9a75bd1`
- RESOLVED - `UiText` alignment in this lane - commit `9a75bd1`

### Batch 4
Status: RESOLVED

- RESOLVED - OCR lock race (`recognizerMutex`) - commit `45b098d`
- RESOLVED - `ImageCache` LRU + dimension handling - commit `45b098d`

### Batch 5
Status: PARTIALLY OPEN

- RESOLVED - currency regex `IGNORE_CASE` - commit `52e5c58`
- RESOLVED - 15-minute restart handling - commit `52e5c58`
- RESOLVED - recommendation severity enum - commit `52e5c58`
- RESOLVED - `TimeProvider` usage in this batch scope - commit `52e5c58`
- RESOLVED - Bank API connectivity semantics fix - commit `52e5c58`
- STILL OPEN - recommendation dedupe still ignores ownership - `app/src/main/java/com/yourname/expensetracker/service/RecommendationDeduplicator.kt:83-97`
- STILL OPEN - bank integration remains demo/mock for OAuth/tokens/transactions - `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt:83-99,104-127,158-160,267-289`

### Batch 6
Status: PARTIALLY OPEN

- RESOLVED - export `Locale.US` money formatting - commit `4690393`
- RESOLVED - CSV currency column - commit `4690393`
- RESOLVED - DI-exporter wiring - commit `4690393`
- STILL OPEN - filename generation still uses `SimpleDateFormat` - `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt:78-83`
- STILL OPEN - deprecated `DateFormatterUtils` `ThreadLocal<SimpleDateFormat>` cache still remains - `app/src/main/java/com/yourname/expensetracker/domain/util/DateFormatterUtils.kt:20-22,45-52,63-88`

### Batch 7
Status: PARTIALLY OPEN

- RESOLVED - calendar month counting - commit `47361f1`
- RESOLVED - horizon simulation - commit `47361f1`
- RESOLVED - `InvestmentTracker` previous-day-close semantics - commit `47361f1`
- RESOLVED - health-score semantics - commit `47361f1`
- RESOLVED - gamification best-goal selection - commit `47361f1`
- STILL OPEN - `NarrativeGenerator` / `SynthesisEngine` still embed raw user text, hardcoded euro formatting, and wall-clock time - `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:31-68,95,118,138,153`; `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:68-71,253-255,517-526`

### Batch 8
Status: RESOLVED

- RESOLVED - `LocationResolver` deferred resolution + area correction - commit `174b30c`
- RESOLVED - floor bucketing - commit `174b30c`
- RESOLVED - cache TTL - commit `174b30c`
- RESOLVED - `PriceProtection` `TimeProvider` + `isSimulated` - commit `174b30c`
- RESOLVED - map preset enum - commit `174b30c`

### Batch 9
Status: PARTIALLY OPEN

- RESOLVED - merchant normalization guards - commit `fcd3907`
- RESOLVED - currency-aware dedupe - commit `fcd3907`
- RESOLVED - `CategorizationEngine` `TimeProvider` migration - commit `fcd3907`
- RESOLVED - keyword dedupe - commit `fcd3907`
- RESOLVED - regex precompile - commit `fcd3907`
- STILL OPEN - hardcoded `EUR` / `€` literals remain in user-visible code after the requested scan; representative hits: `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:44-50,56-62,95,118,138,153`, `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:149,186,201,215`, `app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt:106,129`, `app/src/main/java/com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt:46`, `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt:73`
- STILL OPEN - `ReviewScreen` still performs composition-time state mutation via `consumePrefilled...()` - `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt:432-460`; `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt:595-604`
- STILL OPEN - advanced analytics load failure still collapses to blank/null UI - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsViewModel.kt:35-43`; `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsScreen.kt:51-56`

### Batch 10
Status: RESOLVED

- RESOLVED - email ingestion through `ProcessReceiptUseCase` - commit `1a406ba`
- RESOLVED - strict date parsing - commit `1a406ba`
- RESOLVED - `Locale.ROOT` normalization - commit `1a406ba`
- RESOLVED - LRU cache addition - commit `1a406ba`
- RESOLVED - cent-safe split validation - commit `1a406ba`

### Batch 11
Status: PARTIALLY OPEN

- RESOLVED - `AddGroupMemberUseCase` core cleanup - commit `bc41532`
- RESOLVED - unused parameter removal - commit `bc41532`
- RESOLVED - epsilon settlement handling - commit `bc41532`
- RESOLVED - domain validation - commit `bc41532`
- RESOLVED - `customSplitsJson` canonicalization - commit `bc41532`
- STILL OPEN - the coordinator/repository/use-case path still collapses validation failures into a generic error/null contract - `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupMemberUseCase.kt:34-45`; `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt:90-101`; `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt:106-125`

### Batch 12
Status: PARTIALLY OPEN

- RESOLVED - active warranty filter - commit `5373e49`
- RESOLVED - AI chat transaction boundary - commit `5373e49`
- RESOLVED - `ReturnWindow` unique index - commit `5373e49`
- RESOLVED - `NarrativeSection` `UiText` adoption - commit `5373e49`
- RESOLVED - `@IoDispatcher` injection cleanup - commit `5373e49`
- STILL OPEN - `ReturnWindow` entity defaults still depend on wall-clock time - `app/src/main/java/com/yourname/expensetracker/data/database/entity/ReturnWindow.kt:47-48`

## Still Open Issues

1. **Hardcoded currency presentation remains**
   - Equivalent grep scan found many remaining literal `€` / `EUR` matches under `app/src/main/java/com/yourname/expensetracker`.
   - Priority files to fix:
     - `domain/logic/NarrativeGenerator.kt:44-50,56-62,95,118,138,153`
     - `domain/analytics/InsightsEngine.kt:149,186,201,215`
     - `domain/savings/SavingsGamificationEngine.kt:106,129`
     - `domain/model/budget/MonteCarloBudgetImpact.kt:46`
     - `ui/screens/receiptscan/ReceiptScanScreen.kt:73`
   - Fix: move all display formatting to centralized currency/text utilities; keep raw `EUR` defaults only where they are true storage/domain defaults, not UI text.

2. **ReviewScreen composition mutation**
   - `ui/screens/review/ReviewScreen.kt:432-460`
   - `ui/screens/review/ReviewViewModel.kt:595-604`
   - Fix: replace destructive “consume during render” calls with event-backed state or a remembered dialog model prepared before composition.

3. **Advanced analytics error collapsing**
   - `ui/screens/analytics/AdvancedAnalyticsViewModel.kt:35-43`
   - `ui/screens/analytics/AdvancedAnalyticsScreen.kt:51-56`
   - Fix: expose an explicit error state and render retry/error content.

4. **AI settings corruption recovery missing**
   - `data/repository/AiSettingsRepositoryImpl.kt:19,56-57`
   - Fix: add DataStore corruption handling / safe fallback defaults.

5. **Cloud JSON extraction stops at first brace-balanced object**
   - `data/ai/provider/internal/CloudJsonParser.kt:12-55`
   - Fix: scan candidates until `JSONObject(...)` succeeds; ignore malformed earlier fragments.

6. **Warranty routing still coupled to receipt-assist toggle**
   - `domain/ai/policy/DefaultAiCapabilityRouter.kt:251-252`
   - `domain/ai/policy/AiPolicyImpl.kt:21-22,40-41`
   - Fix: give warranty extraction its own independent capability gate.

7. **Recommendation dedupe omits ownership**
   - `service/RecommendationDeduplicator.kt:83-97`
   - Fix: include ownership in dedupe signatures so MINE/NOT_MINE/SHARED recommendations do not collapse.

8. **Bank API integration remains a stub**
   - `domain/bank/BankApiIntegration.kt:83-99,104-127,158-160,267-289`
   - Fix: isolate this as explicit demo-only code or replace it with real provider-backed OAuth/token/sync adapters.

9. **Export filename formatter still uses `SimpleDateFormat`**
   - `data/repository/AccountingExportRepository.kt:78-83`
   - Fix: use deterministic `java.time` formatting with a stable locale for filenames.

10. **Deprecated `DateFormatterUtils` cache still live**
    - `domain/util/DateFormatterUtils.kt:20-22,45-52,63-88`
    - Fix: migrate remaining call sites to `java.time` and delete the deprecated `SimpleDateFormat` surface.

11. **Add-group-member validation semantics still not preserved end-to-end**
    - `domain/groups/usecase/AddGroupMemberUseCase.kt:34-45`
    - `data/repository/GroupsRepositoryImpl.kt:90-101`
    - `data/database/GroupTransactionCoordinator.kt:106-125`
    - Fix: propagate typed validation errors instead of returning `null`/generic failure.

12. **ReturnWindow still has wall-clock entity defaults**
    - `data/database/entity/ReturnWindow.kt:47-48`
    - Fix: create/update timestamps through `TimeProvider` at the repository/use-case layer.

13. **Narrative/Synthesis still mix domain and presentation concerns**
    - `domain/logic/NarrativeGenerator.kt:31-68,95,118,138,153`
    - `domain/logic/SynthesisEngine.kt:68-71,253-255,517-526`
    - Fix: move user-visible text to `UiText`/domain keys, remove hardcoded money strings, and stop using wall-clock time inside injected services.
