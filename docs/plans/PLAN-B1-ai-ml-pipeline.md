## Technical Plan

### Scope
- In: B.1 **CRITICAL** and **HIGH** items only from `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` lines 101-120, with compile-neighbor test/support changes required to keep the AI/ML pipeline green.
- Out: B.1 **MEDIUM/LOW** items, Room schema/entity/migration work, unrelated UI polish, and any broad refactors outside the exact assistant/AI paths listed below.

### Files
- create: `docs/plans/PLAN-B1-ai-ml-pipeline.md`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
- create: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/AiArtifactFreshness.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParser.kt`
- create: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/TransactionInsightInputBuilder.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistServiceTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilderTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouterTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistServiceTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParserTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-06.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-08.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-09.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-25.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-26.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-34.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-35.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
- create: `docs/reviews/REVIEW-B1-ai-ml-pipeline.md`

### 1. Objective & Blast Radius
- **The Core Issue:** B.1 still has open stop-ship/privacy defects and high-severity correctness drift across financial-query interpretation/execution, cloud redaction, cache freshness, dedupe validation, receipt retry routing, receipt-item categorization, notification parsing, and transaction-insight generation.
- **Goal of this plan:** close every B.1 **CRITICAL** and **HIGH** item without widening into B.1 medium/low cleanup.

- **Blast Radius:**
  - **Assistant query path:** `FinancialQueryInterpretationInputBuilder.kt`, `OnDeviceQueryInterpretationService.kt`, `CloudQueryInterpretationService.kt` (schema consumer), `InterpretFinancialQueryUseCase.kt`, `ExecuteFinancialQueryUseCase.kt`, `ExpenseRepository.kt`, `ExpenseDao.kt`, assistant UI renderers that already consume `primaryText` / `valueText`.
  - **Cloud privacy path:** `CaptureAssistModels.kt`, `CategorizationAssistInputBuilder.kt`, `CloudCategorizationAssistService.kt`, `SuggestCategoryFallbackUseCase.kt`, review/receipt category-assist entry points.
  - **Artifact caching:** `GenerateDashboardBriefingUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `AiArtifactRepository` callers and artifact tests.
  - **Dedupe:** `DedupeJudgeInputBuilder.kt`, `JudgePendingReviewDuplicateUseCase.kt`, `DedupeJudgeService` consumers and review queue assist UI.
  - **Routing / receipt assist:** `DefaultAiCapabilityRouter.kt`, `SmartReceiptAssistService.kt`, downstream receipt assist callers.
  - **Receipt-item categorization:** `CategorizeReceiptItemsUseCase.kt`, `OnDeviceReceiptItemCategorizationService.kt`, receipt scan/review flows.
  - **Notification parsing:** `OnDeviceNotificationParser.kt`, `ParsedTransaction` creation in notification ingestion path.
  - **Transaction insights / recommendation enrichment:** `GenerateTransactionInsightUseCase.kt`, `NotificationProcessingPipeline.kt`, `ManualExpenseRepository.kt` callers.

- **Assumptions / unknowns:**
  - There is no already-approved FX conversion pipeline wired into assistant query execution; if that remains true, **currency-separated output is the safe fix** and raw cross-currency math must not be reintroduced.
  - `CloudQueryInterpretationService.kt` reuses `OnDeviceQueryInterpretationService` prompt/parse helpers, so Batch 1 should target the on-device helper first and only touch cloud tests unless the request-body contract requires explicit updates.
  - `MapFinancialQueryToNavigationUseCase.singleOrNull()` is listed separately as a medium issue. Treat it as **out of scope unless Batch 2 proves the active assistant UX is still user-visible broken without a tiny compile-neighbor follow-up**.
  - Several focused test files do not yet exist (`CategorizeReceiptItemsUseCaseTest.kt`, `OnDeviceReceiptItemCategorizationServiceTest.kt`, `OnDeviceNotificationParserTest.kt`, `GenerateTransactionInsightUseCaseTest.kt`); create only narrow regression coverage, not broad suites.

### 2. The Single Source of Truth
- **Financial query standard:** one normalized `FinancialQueryIntent` must preserve all supported filter dimensions end-to-end: period, merchant(s), category(s), transaction type(s), ownership, grouping, comparison, and answer mode.
- **Financial query execution standard:** AI assistant totals/counts/breakdowns must never use paged UI queries as their source of truth. Execution must run on a complete filtered dataset (or an exact equivalent count query) and must not silently drop multi-value filters.
- **Currency standard:** when filtered results span multiple currencies, the assistant must render currency-safe text (`primaryText` / `valueText`) per currency; it must never label mixed-currency arithmetic as `EUR`.
- **Cloud redaction standard:** when `AiPolicy.shouldRedact(...)` is `true`, only redacted/aliasized category labels, merchant history, and scrubbed free text may leave the device. Alias resolution back to real IDs/names must happen locally after the response returns.
- **Artifact freshness standard:** a cache hit is valid only when all of these match: `READY` status, prompt version, `sourceHash`, and unexpired TTL.
- **Dedupe standard:** the AI judge may run with **one or more** bounded candidates; any `matchedTargetType` / `matchedTargetId` coming back from a model is untrusted until it is validated against the exact candidate set used for that request.
- **Routing standard:** preferred mode defines route order, **not** exclusivity. If the preferred family is unavailable or fails and the alternate family is viable, fallback must remain possible before deterministic/no-op fallback.
- **State-transition standard:** once a receipt is moved to `ANALYZING`, every null/error/early-return path must restore it to a non-stuck terminal state.
- **Logging standard:** no raw merchant/amount/OCR/notification text may be logged from cloud-bound AI paths after these fixes land.

> [!WARNING]
> Do **not** change Room entities, `@Entity` annotations, DAO schemas, migrations, or database versioning in B.1. This plan is logic/test/documentation only.

> [!WARNING]
> Do **not** fix assistant correctness by widening repository/public API behavior used by UI paging. If new uncapped helpers are required, add assistant-specific helpers and keep existing paged APIs backward-compatible.

> [!WARNING]
> Do **not** send raw category names, merchant history, OCR text, or review text to cloud providers when redaction is enabled. If any test prompt still contains raw labels in redacted mode, rollback that batch before proceeding.

> [!WARNING]
> Do **not** mark the whole B.1 section resolved. Only the exact CRITICAL/HIGH bullets fixed by this plan may be dispositioned; B.1 medium/low lines must remain open.

> [!WARNING]
> Do **not** broaden Batch 2 into serializer/drilldown cleanup, and do **not** broaden Batch 9 into a new AI capability/service unless the redaction-safe builder approach proves impossible.

### 3. File-by-File Execution Checklist (micro-batches)

#### Batch 1A — Restore financial-query interpretation contract (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
  - Add only the minimum extra interpretation-input metadata needed to resolve redacted category aliases and explicit provider period payloads without widening `FinancialQueryIntent`.
  - Keep `FinancialQueryIntent` / `ExpenseQueryFilters` as the canonical downstream contract.
  - Do **not** add UI-only fields or persistence concerns.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
  - Populate canonical lookup/alias data for both redacted and non-redacted category/merchant interpretation.
  - Preserve existing truncation/sanitization limits.
  - Do **not** reintroduce raw merchant/category values into redacted cloud input.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`
  - Expand the structured prompt/JSON schema so the provider can express period, category aliases/names, merchant aliases/names, and transaction-type filters.
  - Parse multi-value arrays instead of collapsing to single values.
  - Resolve aliases using the builder-provided canonical lookup data.
  - Do **not** silently default richer structured output back to bare totals.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
  - Tighten/remove early special-case returns so they only fire for bare period-only queries.
  - When provider output is partial, merge missing dimensions from local heuristics without discarding provider grouping/metric/filter richness.
  - Preserve cancellation behavior and current disabled-gate behavior.

**Validation for Batch 1A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- Provider/local interpretation can preserve period + merchant + category + transaction-type + grouping signals in one intent.

#### Batch 1B — Lock financial-query interpretation with regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt`
  - Add regressions for redacted alias inputs, multi-value filter parsing, and explicit period parsing.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt`
  - Add regressions for queries like “top merchants for this month” / “largest groceries this week” so richer cues are not collapsed to plain totals.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt`
  - Assert reversible redacted lookup data still exists after Batch 1A.
  - Do **not** rewrite unrelated builder coverage.

**Validation for Batch 1B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.OnDeviceQueryInterpretationServiceTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.InterpretFinancialQueryUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.FinancialQueryInterpretationInputBuilderTest"`

**Complete when**
- The query-interpretation regressions fail on old behavior and pass on the new contract.

#### Batch 2A — Make financial-query execution exact and currency-safe (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - Add assistant-specific uncapped raw-query entry points (full result set and/or exact count) that share one filter contract with repository code.
  - Keep existing paged UI methods unchanged.
  - Do **not** change existing Room table definitions or generic paging methods.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
  - Expose assistant-only filtered full-read / exact-count helpers that keep list/count filters in sync.
  - Keep public backward compatibility for existing paging/search callers.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
  - Stop using the capped `limit = 500` page as the source of truth.
  - Preserve multi-value merchant/category/type filters instead of using `singleOrNull()`.
  - Remove/replace any fast path that bypasses currency safety.
  - Compute exact `previewCount`.
  - Render totals, averages, max results, and breakdown rows with real currency codes or per-currency text; never hardcode `EUR` for mixed-currency math.
  - Do **not** break `FinancialQueryResult` shape if existing `primaryText` / `valueText` can carry the fix.

**Validation for Batch 2A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- Query execution no longer truncates at 500 rows, no longer drops multi-value filters, and no longer emits fake EUR labels for mixed-currency results.

#### Batch 2B — Lock financial-query execution with regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt`
  - Add regressions for exact preview counts, multi-value filters, and mixed-currency output.
  - Keep tests focused on assistant execution semantics only.

**Validation for Batch 2B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.ExecuteFinancialQueryUseCaseTest"`

**Complete when**
- The assistant query executor is covered for the B.1 high/critical regressions.

#### Batch 3A — Stop redacted categorization context from leaking to cloud (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
  - Add explicit cloud-safe category/history fields (or the narrowest equivalent) so raw on-device labels remain separate from cloud-bound aliases.
  - Keep existing caller-facing category suggestion result shape stable.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
  - Build redacted alias payloads for candidate categories and recent same-merchant history when redaction is enabled.
  - Keep raw labels only for local/on-device use.
  - Do **not** erase category IDs; local validation still needs deterministic mapping.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
  - Build prompts only from cloud-safe aliases under redaction.
  - Map alias output back to real category IDs/names locally before returning.
  - Do **not** rely on raw `candidateCategories` / `recentTransactionsWithSameMerchant` in redacted mode.

**Validation for Batch 3A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- No raw category labels or merchant-history labels can leave the device in redacted cloud mode.

#### Batch 3B — Lock categorization redaction stop-ship with regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt`
  - Assert raw labels stay local while cloud-safe aliases are populated under redaction.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistServiceTest.kt`
  - Assert redacted prompts do not contain raw category/history labels and alias responses still map back to valid categories.

**Validation for Batch 3B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.CategorizationAssistInputBuilderTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.CloudCategorizationAssistServiceTest"`

**Complete when**
- The privacy regressions are covered and reproducible.

#### Batch 4A — Make artifact cache freshness depend on `sourceHash` (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/AiArtifactFreshness.kt`
  - Create one shared helper/extension for cache-hit validation: `READY` + promptVersion + `sourceHash` + unexpired TTL.
  - Keep it domain-owned and repository-agnostic.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
  - Compute source hash before freshness check and use the shared helper.
  - Do **not** treat any same-target/same-version artifact as fresh when inputs changed.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
  - Apply the same shared freshness rule.
  - Preserve existing route diagnostics and failure handling.

**Validation for Batch 4A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- Dashboard briefing and review explanation cache hits are invalidated by input changes.

#### Batch 4B — Lock cache freshness with regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt`
  - Add a stale-`sourceHash` regression.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt`
  - Add the same stale-`sourceHash` regression.

**Validation for Batch 4B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.GenerateDashboardBriefingUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCaseTest"`

**Complete when**
- Both stale-cache paths are covered and passing.

#### Batch 5A — Fix dedupe judge candidate gating and target validation (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
  - Treat `candidates.size == 1` as a valid AI-judge case.
  - Keep `NotNeeded` only for zero bounded candidates.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
  - Add one canonical validator that confirms the returned `matchedTargetType` / `matchedTargetId` exists in the exact request candidate set.
  - If invalid, clear the match fields and conservatively downgrade to `UNCERTAIN` before caching/returning.
  - Do **not** trust model-emitted IDs directly.

**Validation for Batch 5A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- One-candidate dedupe requests still invoke AI and out-of-set model matches are rejected.

#### Batch 5B — Lock dedupe regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilderTest.kt`
  - Add/replace the one-candidate regression so it now returns `Ready`.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt`
  - Add a regression for invalid `matchedTarget*` fields and a passing in-set match case.

**Validation for Batch 5B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.DedupeJudgeInputBuilderTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.JudgePendingReviewDuplicateUseCaseTest"`

**Complete when**
- The dedupe judge no longer skips the common one-candidate path and cannot cache illegal target references.

#### Batch 6A — Make routing symmetric and receipt retry chain cross-family (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt`
  - Preferred `CLOUD` mode must still route to on-device-only capabilities when cloud is impossible.
  - Preferred `ON_DEVICE` mode must still route to cloud-only/available cloud capabilities when local is impossible.
  - Keep policy gates intact; do **not** route around `AiPolicy`.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
  - Use router preference to order attempts, not to suppress viable alternate-family fallbacks.
  - Allow cloud failures to fall through to on-device attempts and on-device failures to fall through to cloud attempts when settings/policy permit.
  - Preserve `usedImageInput` based on the actual successful attempt.

**Validation for Batch 6A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- Preferred mode still influences ordering, but a viable alternate family is no longer skipped.

#### Batch 6B — Lock routing/retry regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouterTest.kt`
  - Add regressions for cloud-preferred/on-device-only and on-device-preferred/cloud-only capability fallback.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistServiceTest.kt`
  - Add regressions where the first family fails and the second family succeeds.

**Validation for Batch 6B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.policy.DefaultAiCapabilityRouterTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistServiceTest"`

**Complete when**
- Routing symmetry and runtime fallback behavior are both proven by tests.

#### Batch 7A — Fix receipt-item categorization stuck state and overlap floor (code)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
  - Funnel every post-`ANALYZING` null/invalid/error exit through one failure helper that restores receipt status to `PENDING` (or the narrowest non-stuck equivalent) before returning `Error`.
  - Preserve artifact failure writes and success-path `READY` transition.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
  - Remove the `0.3..0.7` floor that turns zero-overlap into pseudo-confidence.
  - Keep keyword fallback reachable for unknown items.
  - Do **not** inflate confidence to hide uncertainty.

**Validation for Batch 7A**
- `./gradlew.bat :app:compileDebugKotlin`

**Complete when**
- Receipt-item categorization failures no longer strand receipts in `ANALYZING`, and zero-overlap items can hit keyword fallback.

#### Batch 7B — Lock receipt-item categorization regressions (tests)
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt`
  - Create a narrow regression for null service result / failure path restoring status.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt`
  - Create a narrow regression proving keyword fallback still runs for unknown/zero-overlap items.

**Validation for Batch 7B**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptItemCategorizationServiceTest"`

**Complete when**
- The stuck-state and overlap-floor regressions are covered.

#### Batch 8 — Keep purchase notification parses valid (code + test)
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParser.kt`
  - Only attach `transferDirection` / `transferAccountName` for `TRANSFER` or `DEPOSIT` results.
  - Tighten prompt guidance/examples so purchases are not expected to carry transfer direction.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParserTest.kt`
  - Create regressions for purchase-with-direction JSON, transfer JSON, and deposit JSON.

**Validation for Batch 8**
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.OnDeviceNotificationParserTest"`

**Complete when**
- Purchase parses survive and transfer/deposit direction still works.

#### Batch 9 — Make transaction insight generation redaction-safe (code + test)
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/TransactionInsightInputBuilder.kt`
  - Create a dedicated, tiny builder that converts an `Expense` into a **sanitized** prompt payload for the existing briefing service contract.
  - Centralize merchant/amount scrubbing and log-safe text shaping here.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
  - Stop fabricating raw prompt text inline.
  - Apply redaction policy before any cloud-bound content is created.
  - Remove raw merchant/amount/debug logging.
  - Keep timeout and graceful-degradation behavior.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt`
  - Create a narrow regression for redacted cloud mode and non-leaky behavior.

**Validation for Batch 9**
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCaseTest"`

**Complete when**
- Transaction insight generation no longer bypasses redaction policy or log raw merchant/amount prompt text.

### 4. Verification Plan
- **After every micro-batch:** run `./gradlew.bat :app:compileDebugKotlin` before moving on.
- **Targeted batch tests:**
  - Batch 1: `OnDeviceQueryInterpretationServiceTest`, `InterpretFinancialQueryUseCaseTest`, `FinancialQueryInterpretationInputBuilderTest`
  - Batch 2: `ExecuteFinancialQueryUseCaseTest`
  - Batch 3: `CategorizationAssistInputBuilderTest`, `CloudCategorizationAssistServiceTest`
  - Batch 4: `GenerateDashboardBriefingUseCaseTest`, `ExplainPendingReviewUseCaseTest`
  - Batch 5: `DedupeJudgeInputBuilderTest`, `JudgePendingReviewDuplicateUseCaseTest`
  - Batch 6: `DefaultAiCapabilityRouterTest`, `SmartReceiptAssistServiceTest`
  - Batch 7: `CategorizeReceiptItemsUseCaseTest`, `OnDeviceReceiptItemCategorizationServiceTest`
  - Batch 8: `OnDeviceNotificationParserTest`
  - Batch 9: `GenerateTransactionInsightUseCaseTest`
- **Cross-batch smoke reruns before review:**
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.ExecuteFinancialQueryUseCaseTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.policy.DefaultAiCapabilityRouterTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistServiceTest"`
- **Final epic gate:**
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest`
  - Because B.1 has no schema work, Room migration validation is not expected; only compile/test regressions should be checked.
- **Prompt/privacy verification:** explicitly inspect or assert in tests that redacted cloud prompts do not contain raw merchant/category/history strings.
- **Rollback rule:** if any batch compiles but fails its targeted regression coverage, revert that batch before starting the next one; do not stack later routing/privacy changes on a red baseline.

### 5. Documentation & Registry Updates
- **Registry update first:** after code + review PASS, update only the B.1 CRITICAL/HIGH bullet lines in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` (currently lines 101-120).
  - Mark resolved only the exact bullets closed by this plan.
  - Do **not** mark the B.1 header or medium/low bullets resolved.
- **Exact final-verification files to update next:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-06.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-08.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-09.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-25.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-26.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-34.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-35.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
- **Deep-analysis mirror docs:** only after the registry + exact final-verification rows are updated, and only if the project’s documentation closeout still requires mirror sync for the same resolved bullets.
- **Review doc:** write `docs/reviews/REVIEW-B1-ai-ml-pipeline.md` after all micro-batches are complete; include targeted evidence, privacy prompt assertions, and any waivers/blockers.
- **Playbook status:** update `docs/plans/EXECUTION-PLAYBOOK.md` only at final closeout if B.1 becomes the completed active Phase B epic; do not pre-mark it complete during execution.

### Implementation Steps
1. Execute Batch 1A, then Batch 1B.
2. Execute Batch 2A, then Batch 2B.
3. Execute Batch 3A, then Batch 3B.
4. Execute Batch 4A, then Batch 4B.
5. Execute Batch 5A, then Batch 5B.
6. Execute Batch 6A, then Batch 6B.
7. Execute Batch 7A, then Batch 7B.
8. Execute Batch 8.
9. Execute Batch 9.
10. Run the final verification lane.
11. Run review, remediate one issue at a time if needed, then update registry/final-verification docs in playbook order.

### Risks
- Query-contract fixes can ripple into assistant drilldown behavior; keep B.2 tightly scoped and do not silently widen navigation semantics unless Batch 2 proves it is required.
- The privacy stop-ship fix is easy to regress if any cloud prompt still reads raw `candidateCategories` or `recentTransactionsWithSameMerchant`.
- Currency-safe query output is user-visible; if any path still falls back to numeric `amount` + EUR formatting for mixed-currency rows, the bug remains.
- Routing symmetry and retry-chain fixes can accidentally bypass policy gates if fallback checks are not centralized.
- Transaction insight has weak direct test coverage today; land the dedicated builder and narrow tests before changing caller behavior.

### Acceptance Criteria
- [ ] Financial-query interpretation preserves supported filters end-to-end, including period/category/merchant/type richness.
- [ ] Financial-query execution no longer truncates at 500 rows, no longer drops multi-value filters, and no longer hardcodes `EUR` for mixed-currency results.
- [ ] Redacted categorization assist never sends raw category labels or merchant history to cloud providers.
- [ ] Dashboard briefing and review explanation cache reuse requires a matching `sourceHash`.
- [ ] The dedupe judge runs for one-candidate cases and rejects out-of-bounds `matchedTarget*` values.
- [ ] Preferred AI mode is symmetric, and receipt assist can fall through across cloud/on-device families before deterministic fallback.
- [ ] Receipt-item categorization failures do not leave receipts stuck in `ANALYZING`, and keyword fallback remains reachable for unknown items.
- [ ] On-device notification parsing no longer drops purchase parses by attaching illegal transfer-direction state.
- [ ] Transaction insight generation respects redaction policy and no longer logs raw merchant/amount prompt text.
- [ ] Registry, review, and final-verification docs are updated only for the B.1 CRITICAL/HIGH bullets fixed here.
