# ai-evolution — current-state AI inventory

Source: `scout` (Sonnet), read-only, branch `release-1.0-remediation`, 2026-10-16. Persisted verbatim-in-substance
by the orchestrator. Root `A` = `app/src/main/java/com/yourname/expensetracker`. Items marked
`needs-sonnet-confirm` were only skimmed.

## 0. Shape
- Cloud: Gemini only (Google AI Studio REST, `gemini-2.5-flash`). On-device: ML Kit GenAI Prompt API (Gemini Nano via AICore, Android 14+).
- AI settings-gated: `AiSettings` defaults in `A/domain/ai/model/AiModels.kt`; `aiEnabled=true`, `allowCloudAi=false` (opt-in), `allowOnDeviceAi=true`; all capability toggles false except `warrantyExtractionEnabled=true`.
- Outputs are user-applied suggestions persisted in `ai_artifacts`. Per-capability routing via `DefaultAiCapabilityRouter`; `HybridRouter` is a thin dispatcher.

## 1. Capabilities (`AiCapability` enum, 12 values)
| Capability | What it does | Entry / caller | Mode | UI | Wired? |
|---|---|---|---|---|---|
| Dashboard briefing | Daily text (title/text/tone/confidence, ≤600 chars) | `GenerateDashboardBriefingUseCase` ← only `DailyBriefingWorker` | Hybrid, cloud-first in AUTO, on-device fallback | Home card (`HomeViewModel.aiBriefing`) + proactive notification | Wired; no on-demand path; worker only if `aiEnabled && dashboardBriefingEnabled && proactiveBriefingsEnabled` |
| Transaction insight | One-line insight per transaction (reuses DASHBOARD_BRIEFING, 3s timeout, null on error, uncached) | `GenerateTransactionInsightUseCase` ← `NotificationProcessingPipeline`, `ManualExpenseRepository` | Hybrid | Recommendation cards | Wired |
| Review explanation | Why a notification became a pending review | `ExplainPendingReviewUseCase` ← `ReviewViewModel` | Hybrid, cloud-first AUTO | Review screen | Wired; cloud text cap 500 chars |
| Categorization assist | Category suggestion for review / scanned receipt | `SuggestCategoryFallbackUseCase` ← Review/ReceiptScan VMs | Hybrid, on-device-first | `CategoryAssistCard` | Wired (user-triggered) |
| Receipt assist | Merchant/amount/date from OCR (+ optional image) | `SuggestReceiptExtractionUseCase` | `SmartReceiptAssistService` (on-device / cloud) | `ReceiptAssistCard` | Wired; image to cloud only if image toggle + privacy + redaction OFF |
| Receipt item categorization | Per-line-item categories | `CategorizeReceiptItemsUseCase` ← `ReceiptSideEffectPlanner`, `ReceiptScanViewModel` | Hybrid; on-device is heuristic (no Nano) | Receipt item list | Wired; only AI path writing rows as lifecycle side effect |
| Warranty extraction | Warranty/return window from receipts | `WarrantyTrackerRepository` → `CloudWarrantyExtractionService` | Cloud only | Warranty tracker | Wired; ≥0.75 auto-accept, 0.30–0.75 review, <0.30 discard |
| Dedupe judge | Duplicate verdict for pending review | `JudgePendingReviewDuplicateUseCase` ← `ReviewViewModel` | Hybrid with cross-route failover (only one) | `DedupeAssistCard` | Wired; gates quick-approve |
| Query interpretation (assistant) | NL → `FinancialQueryIntent`, executed locally | `InterpretFinancialQueryUseCase` → `ExecuteFinancialQueryUseCase` ← `AssistantViewModel` | Hybrid + local regex fallback | `ui/screens/assistant/AssistantSheet.kt` | Wired; LLM picks intent, never produces numbers |
| Notification parsing | Last-resort fallback parser | `OnDeviceNotificationParser` ← `domain/parser/AppParserRegistry.kt` | On-device only (Nano); cloud forbidden | none (feeds review pipeline) | Wired |
| Semantic duplicate detection | Similarity score | `OnDeviceSemanticDuplicateDetector` ← `domain/intelligence/CrossSourceDeduplication` | On-device heuristic | none | Detector wired; `DetectSemanticDuplicateUseCase` DORMANT |
| Review priority scoring | Orders pending reviews | `OnDeviceReviewPriorityScorer` behind `PrioritizeReviewItemsUseCase` | On-device heuristic | none | DORMANT (no caller) |
| Bank statement validation | Validates/corrects statement OCR rows (matched by `candidateId`) | `ValidateBankStatementTransactionsUseCase` ← `BankStatementLifecycleProcessor` | Receipt assist services, gated by `bankStatementAiEnabled` | Bank statement import | Wired |
| LOCATION_SUMMARY | enum only | router/policy only | — | none | DORMANT |

`HybridReceiptAssistService` is not a production binding.

## 2. Routing & providers
- `A/domain/ai/policy/DefaultAiCapabilityRouter.kt` → route ∈ {ON_DEVICE, CLOUD, DETERMINISTIC_FALLBACK, DISABLED}; logged via `AiRuntimeDiagnostics`.
  - ON_DEVICE mode never falls back to cloud. CLOUD: cloud → on-device → fallback. AUTO: cloud-first for {briefing, review explanation, dedupe judge, warranty}; others on-device-first.
  - `canUseCloud`: policy + network + optional Wi-Fi-only + `KEY_GEMINI` present + `PrivacyGate CLOUD_AI_GENERAL`.
  - `canUseOnDevice`: implemented set + `allowOnDeviceAi` + `DefaultAiEnvironmentMonitor` AVAILABLE (SDK ≥ 34).
- `HybridRouter` does no exception failover (only `HybridDedupeJudgeService` does).
- Cloud: `domain/config/AppConfig.kt` `object Ai` — all `google-ai-studio` + `gemini-2.5-flash`, 10–12s timeouts, 220–384 max tokens, all prompt versions "v1". No other providers, no streaming, no per-capability model choice.
- Keys: `A/data/security/SecureKeyStorage.kt` (EncryptedSharedPreferences; `KEY_GEMINI`, `KEY_GEOAPIFY`, `KEY_GOOGLE_PLACES`). Connection test: `A/data/ai/OkHttpCloudProviderConnectionTester.kt`.
- Real Nano calls: briefing, receipt assist, dedupe judge, review explanation, query interpretation, notification parser, categorization assist. Pure heuristics: semantic dedupe, review priority, receipt item categorization.
- Shared plumbing: `data/ai/provider/internal/` (`CloudRetryPolicy`, `CloudPiiSanitizer`, `CloudJsonParser`, `CloudCorrelation`), `StrictAiJsonParsing.kt`.

## 3. Privacy / consent
- `EffectiveCloudAiPolicyResolver` merges `PrivacySettings` + `AiSettings` (either can block). `A/domain/privacy/CloudAiPrivacyGate.kt` per `PrivacyCapability` (RECEIPT_ASSIST, RECEIPT_OCR, ITEM_CATEGORIZATION, WARRANTY_EXTRACTION, DAILY_BRIEFING, GENERAL, BANK_STATEMENT, RECEIPT_IMAGE_CLOUD_UPLOAD). RP-15 matrix in `EffectiveCloudAiPolicy.kt`; unknown → fail closed (`CAPABILITY_NOT_CLOUD`).
- All 8 cloud providers go through `CloudPayloadPolicy`; `CloudPayloadRedactor` per `CloudPayloadPurpose`; `redactBeforeCloud=true` default; amounts bucketed for briefing; `PrivacyAuditLogger` metadata + payload hash per call.
- On-device-only by policy: notification parsing, review prioritization, semantic dedupe.
- Docs: `docs/architecture/PRIVACY_UI_ARCHITECTURE.md`, `docs/architecture/LEGAL_PATHS.md` §"Privacy / Cloud AI" (~line 375).
- UI: `PrivacyBlockedCard` in `AssistantSheet`; `AiSettingsScreen`/`AiSettingsViewModel` (toggles, key, connection test); DataStore `ai_settings`.

## 4. Persistence
- `ai_artifacts` (`AiArtifactEntity`, `AiArtifactDao`): status QUEUED/RUNNING/READY/FAILED/DISMISSED/APPLIED; unique (targetKey, capability, promptVersion, sourceHash); TTLs briefing 24h, others 30d; purged by `DataRetentionWorker` (exact window `needs-sonnet-confirm`).
- `ai_chat_sessions` / `ai_chat_messages`: only if `storeConversationHistory=true` (default false); `RetentionModule.kt:201` purges by age (stale contrary comment in `DataRetentionWorker`).
- `AiEngagementRepositoryImpl`: two briefing keys only.
- Other AI-derived data: `ReceiptItemCategorization` rows; warranties from cloud extraction.

## 5. Workers
- Only `data/ai/worker/DailyBriefingWorker.kt` (`ai_daily_briefing`), self-rescheduling midnight one-shot via `AiWorkSchedulerImpl`; `WorkerExecutionGuard` with `requiredCapabilities=[CLOUD_AI_DAILY_BRIEFING]` → likely skipped when cloud is off even for on-device-only users (`needs-sonnet-confirm`).

## 6. Output validation
- `A/domain/ai/validation/AiOutputValidators.kt` (clamp confidence, positive amount, plausible epoch); strict JSON parsing; length caps.
- AI never writes expenses: `ExpenseDao`/TLC path untouched. Apply is user-driven (prefill, `markApplied`); quick-approve / quick-save shortcuts default off with preview + revalidation.
- Exceptions persisted without a per-item tap: receipt item categorization rows, warranties ≥0.75, notification-parse fallback (→ pending reviews), briefing/insight text.

## 7. Factual gaps
- Dormant: `PrioritizeReviewItemsUseCase`/`OnDeviceReviewPriorityScorer`, `DetectSemanticDuplicateUseCase`, `LOCATION_SUMMARY`, `HybridReceiptAssistService`.
- "On-device AI" that is heuristic: semantic dedupe, review priority, receipt item categorization.
- Single provider/model; router hard-checks `KEY_GEMINI`.
- Briefing: midnight-only, no on-demand, no time-of-day choice, none without proactive toggle.
- Assistant: stateless read-only NL→query; no actions, no follow-ups/memory; legacy `NaturalLanguageSearchEngine` path still routed.
- No feedback loop: APPLIED/DISMISSED statuses unused for learning; no accuracy metrics; all prompts v1.
- `warrantyExtractionEnabled=true` default inconsistent with others.
- `docs/features/FEATURES.md` labels forecasting/savings/bill negotiation/analytics "AI-powered" but engines are rule/statistics-based (`needs-sonnet-confirm`).
- Warranty has no on-device fallback.
- `GenerateTransactionInsightUseCase` injects concrete `HybridDashboardBriefingService` and shares briefing toggles.
- `AiSettingsScreen` strings mostly hard-coded.

## 8. Feature areas with no AI involvement
Budgets (`domain/budget/BudgetRecommendationEngine.kt`), budget forecasting (`BudgetForecastingEngine.kt`), financial stress forecast (`domain/forecasting/FinancialStressForecastEngine.kt`), savings (`domain/savings/*`), recurring (`domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`), subscriptions (`domain/subscription/SubscriptionManagerEngine.kt`), bill reminders, bill negotiation (`domain/negotiation/SmartBillNegotiationEngine.kt`), loans (docs only), mileage (entity/DAO only), groups/splits (`ui/screens/groups`, `ui/screens/split`), exports, backup/restore, bank connections, analytics (`ui/screens/analytics`), cash-flow calendar, lifestyle inflation, carbon, price protection, investments, tax, challenges, spending map (`domain/location/*`), currency, categories, receipt matching, transactions/manual entry, notification capture (deterministic-first).

## Risk note
Low for ideation. Any follow-up touching `ai_artifacts`, `ReceiptItemCategorization` writes, warranty auto-accept, workers, or cloud payloads is T3 territory (Room/lifecycle/privacy).
