# Domain spec: ai-fixes (Part A, B1 warranty, B2 insight, B3 notification decoupling)

Author: domain-modeler (stage 3). Date 2026-10-16. Docs-only; implementation post-1.0 (Release 1.0 frozen).
Inputs: `brief.md` v2 (approved scope), `critique.md`, `workflows/active/ai-fixes-handoff.md` §Human decisions (binding: Gate 1 = RESHAPE "recommended"; brief v2 confirmed; **Q1 = (a)**; test deletions approved in principle, exact list returns at plan approval).
`A` = `app/src/main/java/com/yourname/expensetracker`. Every rule below was checked against the cited source unless marked UNVERIFIED.

Binding decisions applied here:
- Q1(a): the cloud call is retired from the return-window path. Return windows use local merchant defaults only. No return-window UI. No schema change. `warrantyExtractionEnabled` defaults to off. FEATURES.md says warranty AI is not active.
- Insight runs on-device (Nano) only. It has no cloud route and needs no RP-15 amendment. It is ON for new installs; existing users inherit `dashboardBriefingEnabled`.
- Pending warranties auto-expire after their end date. Local review drafts are created even when the cloud gate is denied.
- On-device users' briefing behaviour is unchanged. Dismissed-suggestion handling is out of scope.

---

## 0. Verified source facts that shape the rules (new in this stage)

| # | Fact | Evidence | Consequence |
|---|---|---|---|
| F1 | `AiSettingsRepositoryImpl.update()` writes **every** key on every edit. | `A/data/repository/AiSettingsRepositoryImpl.kt:79-103` | Any user who ever changed any AI setting has explicit values for all keys, including a materialized `ai_warranty_extraction_enabled=true`. An explicit value does not mean the user chose it. An empty DataStore cannot separate a new install from an untouched upgrade (§4). |
| F2 | Both default sites agree on every key today. The only planned divergence is the warranty flip. | `AiModels.kt:87-108` vs `AiSettingsRepositoryImpl.kt:110-133` | We can safely switch to a single source of defaults (INV-22). |
| F3 | `GenerateTransactionInsightUseCase` **never persists** its `AiArtifactRecord`: `aiArtifactRepository` is injected but never called. The record is handed in memory to `DashboardFollowThroughEngine`, which stores `summaryText` as `recommendationText` with a 7-day `expiresAt`. | `GenerateTransactionInsightUseCase.kt:42,111-136`; `DashboardFollowThroughEngine.kt:149-172` | Current code writes no `transaction:` rows to `ai_artifacts`. Legacy end of life is trivial (§6.7). The insight capability value never reaches Room storage. |
| F4 | The insight timeout (3 s) equals the outer recommendation-job timeout (3 s) in **both** callers. A slow insight cancels the whole job, so deterministic recommendations are lost. | `GenerateTransactionInsightUseCase.kt:52`; `ManualExpenseRepository.kt:72,236`; `NotificationProcessingPipeline.kt:916,1739` | This breaks brief B2.3 ("deterministic recommendations still save"). Nano warm-up makes it likely. Fixed by INV-18. |
| F5 | The insight use case catches `Exception` inside `withTimeoutOrNull`, so parent cancellation is swallowed. `ManualExpenseRepository.kt:251` and `DefaultAiEnvironmentMonitor.kt:84` also catch without rethrowing. | as cited | FG-10 fix is required on the B2 path (INV-4). |
| F6 | No foreground-state query exists. `ProcessLifecycleOwner` is only used for scopes and for `AppBackgroundLifecycleObserver.onStop`. | `A/startup/AppStartupCoordinator.kt:702`, `A/startup/AppBackgroundLifecycleObserver.kt` | A new port is needed (§1.7). |
| F7 | `SourceScanningArchitectureGuardTest.notification_posting_workers_require_permission_flag` requires `requiresNotificationPermission = true` for any worker mentioning `DailyBriefing`/`WarrantyExpiration`. The exception is the `optionalNotificationWorkers` allowlist (today only `ReceiptMatchingWorker.kt`). Allowlisted workers must contain the literal `notificationPermissionChecker.areNotificationsEnabled()`. | `app/src/test/.../architecture/SourceScanningArchitectureGuardTest.kt:391-423` | B3 needs a guard amendment, which is stop-and-ask (§8.3). It also decides where the post-time permission check lives (INV-24). |
| F8 | `WarrantyExpirationWorker` (the only caller of `reconcileExpiredItems`) has `requiresNotificationPermission = true`. With notifications denied it never reconciles any expiry. | `A/service/warranty/WarrantyExpirationWorker.kt:73-83`, `WarrantyTrackerRepository.kt:366` | The persisted half of "pending warranties auto-expire" never happens for these users. This is the same AGENTS.md:165-167 violation as B3 → OD-2. |
| F9 | No `PrivacyCapability` covers local receipt access. Local-only capabilities are retention/logging toggles only. The real receipt-text guard is `ReceiptSideEffectPlanner` Policy A, which allows only ephemeral raw OCR and never falls back to persisted text, and runs **before** `AutoCreate….execute`. | `A/domain/privacy/PrivacyCapability.kt`, `PrivacyCapabilityHandlingPolicy.kt:48-53`, `A/domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt:181-211` | The `CLOUD_AI_WARRANTY_EXTRACTION` check at `AutoCreate…:128` guards nothing that leaves the device. `LEGAL_PATHS.md:992,998` has drifted → OD-1. |
| F10 | Today's cloud call sends **persisted** `receipt.rawOcrText`, not the ephemeral text. | `WarrantyTrackerRepository.kt:457-458` | This conflicts with LEGAL_PATHS "parsing from stored text forbidden" (`:410`). Retiring the call removes it. |
| F11 | `AiCapability` is stored as TEXT (`name`) through `Converters.toAiCapability`, which falls back to `DASHBOARD_BRIEFING` for unknown names. Warranty status is stored as the enum name and queried by string literal. Lifecycle event types are `const val` strings. | `A/data/database/converter/Converters.kt:80-85`; `WarrantyDao.kt:16-67`; `WarrantyLifecycleEvent.kt:27-36` | Additive enum values and constants need no Room migration (§10). |
| F12 | The app backup copies only the Room DB. The `ai_settings` DataStore is not backed up, and `android:allowBackup="false"`. | `AndroidManifest.xml:32`; `A/data/backup/*` (no DataStore reference) | A restore never brings back AI settings, so the insight migration is device-local. |
| F13 | When `notificationService.sendAiBriefingReadyWithResult` finds notifications disabled, it already returns `NOT_DELIVERED`. The worker still calls `ctx.addNotificationsSent()` without checking the result. | `A/data/service/AndroidNotificationService.kt:102-110`; `DailyBriefingWorker.kt:108-119` | After B3, many runs will deliver nothing, so the count must reflect the real outcome (INV-27). |
| F14 | `confirmWarranty` builds the status transition inside the ViewModel from a stale UI snapshot. It catches `Exception` without rethrowing cancellation and shows `e.message`. | `A/ui/screens/warranty/WarrantyTrackerViewModel.kt:181-199` | FG-12 / FG-10 / Part A (INV-13, INV-2, INV-4). |
| F15 | The UI's "needs review" count and filter use the `needsReview` flag. | `WarrantyTrackerViewModel.kt:68,232` | When pending items expire, a read model is needed (INV-10). |

---

## 1. Entities, settings and value objects touched

### 1.1 Warranty (`warranties`, schema unchanged)
Relevant fields:
- `status: WarrantyStatus` ∈ {ACTIVE, PENDING_REVIEW, EXPIRED, CLAIMED, TRANSFERRED}, stored as TEXT name.
- `needsReview: Boolean` (default false).
- `autoDetected: Boolean`.
- `extractionSource: String` ("ocr" | "manual" | legacy "cloud").
- `extractionConfidence: Double?` in [0,1].
- `warrantyEndDate: Long` epoch ms. This is an **exclusive** half-open boundary computed in the system zone at creation time.
- `purchaseDate`, `receiptId?`, `productName`, `merchantName`.

"Expired unconfirmed" is **not** a new persisted status. It means `status = EXPIRED ∧ needsReview = true`. It needs no enum change and no migration.

### 1.2 WarrantyReviewState (new domain value, derived, never persisted)
Computed by a single function `WarrantyReviewState.of(warranty, now)`. The owner sits in the domain warranty package; the exact location is the planner's choice.

| Value | Condition (evaluated in order) |
|---|---|
| NOT_IN_REVIEW | `needsReview = false`, or `status ∈ {CLAIMED, TRANSFERRED}` |
| EXPIRED_UNCONFIRMED | `needsReview = true` and (`status = EXPIRED`, or `warrantyEndDate < now`) |
| AWAITING_REVIEW | `needsReview = true` and `status ∈ {PENDING_REVIEW, ACTIVE}` and not `warrantyEndDate < now` |

Notes:
- "Past end" uses the predicate `warrantyEndDate < now`. This is the same predicate as the existing `WarrantyDao.markExpiredWarranties` (`:64`), so there is one expiry semantics for ACTIVE and pending rows.
- `ACTIVE ∧ needsReview` should not exist (INV-14). It is handled defensively as AWAITING_REVIEW.

### 1.3 ReturnWindow (`return_windows`, schema and `ReturnStatus` unchanged)
After ai-fixes, the provenance of new and updated values is local only:
- `returnDays` comes from the merchant default table (Amazon 30, Best Buy 15, Apple 14, else 30; `WarrantyTrackerRepository.kt:577-584`).
- `returnConditions` and `returnPolicyUrl` are never produced.
- `ReturnStatus` gains no pending value.

### 1.4 WarrantyLifecycleEvent types
Additive constant `CONFIRMED = "CONFIRMED"`, stored as TEXT; no schema change.

`AI_EXTRACTION_DISCARDED` is kept: historic rows exist, and `rejectAutoDetectedWarranty` (`Repo:251-275`) still writes it. After ai-fixes its only writer is the user rejecting an auto-detected warranty. Renaming it is out of scope (§15).

### 1.5 AiSettings (DataStore `ai_settings`, not Room)

| Field / key | Default (absent key) | Change |
|---|---|---|
| `warrantyExtractionEnabled` / `ai_warranty_extraction_enabled` | **false** (was true) | Default flip at the single default source. Explicit stored values are never rewritten. The flag gates **no** data flow after ai-fixes (INV-7). |
| `transactionInsightEnabled` / `ai_transaction_insight_enabled` | **false** as the read fallback | NEW key. The product default for new installs (true) is applied by the one-time migration, not by the fallback (§4). |
| `dashboardBriefingEnabled`, `proactiveBriefingsEnabled`, `allowCloudAi`, `allowOnDeviceAi`, `aiEnabled` | unchanged | `allowCloudAi` becomes an input to the briefing scheduling predicate (INV-25). |

### 1.6 AiCapability.TRANSACTION_INSIGHT (new enum value, appended last)
Routing matrix row (every exhaustive `when` must be updated: `DefaultAiCapabilityRouter`, `AiPolicyImpl`, `GetAiRuntimeStatusUseCase.runtimeLabel`, `AiSettingsScreen` label, `DebugScreen:840-865`):

| Aspect | Value |
|---|---|
| `isCapabilityEnabled` / `shouldAllowOnDevice` | `settings.transactionInsightEnabled` (`shouldAllowOnDevice` also requires `aiEnabled ∧ allowOnDeviceAi`, as today) |
| `canUseCloudFor` | **false**, unconditionally (same pattern as NOTIFICATION_PARSE / REVIEW_PRIORITIZATION / SEMANTIC_DEDUPE, `AiPolicyImpl.kt:26-28`) |
| `CLOUD_FIRST_CAPABILITIES` | **not** a member |
| `ON_DEVICE_IMPLEMENTED_CAPABILITIES` | member |
| Cloud provider / model | `"unsupported"` |
| On-device model | `AppConfig.Ai.ON_DEVICE_BRIEFING_MODEL` (reuses the briefing prompt and model; no new prompt) |
| PrivacyCapability / CloudPayloadPurpose / RP-15 row | **none** (no cloud route exists) |

### 1.7 AppForegroundState (new domain port)
- `isAppInForeground(): Boolean`. It is non-suspending, callable from any thread, and returns quickly.
- Implementation: a `@Volatile` flag set by a `DefaultLifecycleObserver` on `ProcessLifecycleOwner`: `onStart` → true, `onStop` → false. It is registered next to `AppBackgroundLifecycleObserver` in `AppStartupCoordinator.registerLifecycleObserver`. Before the first `onStart` it is false (fail-closed).
- This port is a platform signal only, not a business rule.

### 1.8 InstallProvenance (new data-layer port, used only by the settings migration)
- `classify(): NEW_INSTALL | UPGRADE | UNKNOWN`.
- The value comes from `PackageInfo.firstInstallTime == lastUpdateTime` (NEW_INSTALL) vs `<` (UPGRADE).
- Any exception → UNKNOWN.

### 1.9 TransactionInsightOutcome / TransactionInsightSkipReason (new domain types)
- `Generated(artifact: AiArtifactRecord)` | `Skipped(reason)`.
- `TransactionInsightSkipReason` is a closed enum: `AI_DISABLED`, `INSIGHT_DISABLED`, `ON_DEVICE_NOT_ALLOWED`, `APP_NOT_FOREGROUND`, `ROUTE_NOT_ON_DEVICE`, `ROUTE_REJECTED`, `BACKGROUND_USE_BLOCKED`, `BUSY`, `QUOTA_EXCEEDED`, `MODEL_UNAVAILABLE`, `TIMEOUT`, `EMPTY_OUTPUT`, `PROVIDER_ERROR`.
- Cancellation is never an outcome: it is rethrown.

### 1.10 BriefingDeliveryOutcome (new closed enum, returned by DeliverProactiveBriefingNotificationUseCase)
`DELIVERED`, `SKIPPED_SETTINGS_OFF` (ai, briefing or proactive off), `SKIPPED_DUPLICATE` (already delivered or opened), `SKIPPED_NO_FRESH_ARTIFACT`, `NOT_DELIVERED` (platform refused).

[amended B3a, 2026-10-24] Sixth value `NOTIFICATION_PERMISSION_DENIED`: notification permission blocked delivery. The use case takes the caller's local permission check as `notificationsAllowed: Boolean` and evaluates it only AFTER the settings, dedupe and fresh-artifact checks, so a denied permission never masks a skip with another cause. Merge mapping with the app-lock `NotificationService.DeliveryResult`: `DELIVERED` -> `DELIVERED`; `NOT_DELIVERED` -> `NOT_DELIVERED`; `PERMISSION_DENIED` (post refused by a SecurityException) -> `NOTIFICATION_PERMISSION_DENIED`. The worker records the bounded `NOTIFICATION_PERMISSION_DENIED` diagnostic only for this outcome.

The worker-side permission skip is recorded as `NOTIFICATION_PERMISSION_DENIED` (an existing `DiagnosticReasonCode`).

### 1.11 New AppConfig.Ai constants (single source; replaces the duplicated private constants)
- `TRANSACTION_INSIGHT_TIMEOUT_MS` = 3_000 (unchanged value; Nano warm-up is UNVERIFIED).
- `TRANSACTION_INSIGHT_TTL_MS` = 24 h (same value as before; the record is never persisted, but the explicit TTL keeps guard test `AiArtifactRepositoryImplTest:227` green).
- `RECOMMENDATION_JOB_TIMEOUT_MS` ≥ `TRANSACTION_INSIGHT_TIMEOUT_MS + 2_000`; proposed 5_000. Both callers use it.

---

## 2. Invariants

Format: statement → **Owner** (the single component that holds the rule) → enforcement → AE.

### Part A — hygiene
- **INV-1 Docs match behaviour.**
  - FEATURES.md entries for ReceiptTransactionMatcher, ReceiptMatchingWorker, SmartSavingsEngine, AutomatedSavingsRuleEngine, BudgetForecastingEngine, BudgetRecommendationEngine, AdvancedAnalyticsDashboard and SmartBillNegotiationEngine (`FEATURES.md:132,166,392-396,568,581,901`) carry no "AI-powered"/"AI-generated"/"AI" claim.
  - The warranty section (`:36,39,45`) says warranties are detected locally (regex) and return windows use merchant defaults: "warranty AI is not active".
  - The `DataRetentionWorker.kt:1-2` header lists only real gaps. `ai_chat_messages` and diagnostics are covered (`:120-135`).
  - The KDoc at `AutoCreateWarrantyFromReceiptUseCase.kt:41-53` (WRN-15 "planned cloud fallback") is corrected to local-only.
  - **Owner:** the docs/comments. Test: doc review + grep (AE-1).
- **INV-2 Bounded, localized user-facing errors.**
  - `AiSettingsViewModel` and `WarrantyTrackerViewModel` show failures only as localized string resources from a closed set, keyed by failure category. This includes the effective-cloud-blocked reasons (`:107-113`) and every literal at `:110,113,153,163,173,227,237,254,263,277,283,335`.
  - They never interpolate an exception message, class name, path, SQL text or payload.
  - `runtime.message` (`GetAiRuntimeStatusUseCase.kt:71-81`) is VERIFIED bounded: constant router or status strings plus enum labels, with no exception text. Localizing it is out of scope (§15).
  - **Owner:** the error-mapping function in each VM. AE-2, AE-3, AE-5.
- **INV-3 Bounded diagnostics.** Logs added or touched by this bundle contain only bounded codes, enum names, counts or IDs. They never contain OCR, merchant, amount, prompt or model output, `e.message`, or a throwable. The `Timber.w(e, …)` calls at `GenerateTransactionInsightUseCase.kt:67` and `AiSettingsViewModel.kt:334` become class-name-only. **Owner:** each touched component.
- **INV-4 Cancellation is always rethrown.** Every `catch (Exception)` added or modified by this bundle rethrows `CancellationException` first (FG-10). This explicitly covers:
  - `AiSettingsViewModel.update` (`:333`) and `WarrantyTrackerViewModel.confirmWarranty` (`:194`);
  - `GenerateTransactionInsightUseCase.invoke` (`:63-69`). Its own timeout maps to `Skipped(TIMEOUT)`; parent cancellation propagates;
  - `ManualExpenseRepository.kt:251`;
  - `DefaultAiEnvironmentMonitor.getOnDeviceModelStatus` (`:84`), which the insight path reaches.
  - **Owner:** each touched component. AE-4, AE-31.

### B1 — warranty
- **INV-5 No AI or cloud anywhere in warranty processing.**
  - No production path in warranty auto-create or return-window derivation calls `CloudWarrantyExtractionService`, `AiCapabilityRouter.decide(WARRANTY_EXTRACTION, …)` or any model.
  - `WarrantyTrackerRepository` loses its `CloudWarrantyExtractionService`, `AiCapabilityRouter`, `AiSettingsRepository` and `AiPolicy` constructor dependencies.
  - Only `di/AiModule.kt` may reference `CloudWarrantyExtractionService` in `app/src/main`. This is enforced by a new source-scan guard.
  - **Owner:** `WarrantyTrackerRepository.upsertReturnWindowForReceipt`. AE-9, AE-10, AE-22.
- **INV-6 Return windows derive only from local structured fields.**
  - `returnDays = defaultReturnDaysForMerchant(parsedMerchant)`.
  - On insert, `returnConditions` and `returnPolicyUrl` are null. On update, existing values are preserved (current `Repo:415-425` semantics).
  - Inputs are read through `ReceiptRepository` and limited to `id`, `parsedMerchant`, `parsedDate`/`createdAt`, `expenseId`, `sourceType`, `documentType`, `processingStatus`, plus the passed warranty's `productName`. `rawOcrText` is **never** read.
  - `writeBarrier.checkWritesAllowed` runs first (existing).
  - `extractReturnWindow` loses its `extractionResult` parameter.
  - **Owner:** `WarrantyTrackerRepository.upsertReturnWindowForReceipt` / `extractReturnWindow`. AE-9, AE-23.
- **INV-7 `warrantyExtractionEnabled` is an inert stored preference.**
  - Absent key → false.
  - Explicit stored values are never rewritten by any migration.
  - No code path reads it to enable or disable a data flow. The router and policy table entries stay, but no caller exists.
  - Wiring it to a call path needs a new reviewed increment (A6).
  - **Owner:** `AiSettings` default (INV-22). AE-7, AE-8.
- **INV-8 Local confidence bands are independent of cloud or privacy settings.**
  - c ≥ 0.70 → create ACTIVE (`needsReview=false`).
  - 0.40 ≤ c < 0.70 → create PENDING_REVIEW draft (`needsReview=true`, `extractionSource="ocr"`) via `createReviewDraftWarranty`.
  - c < 0.40 → persist nothing.
  - No cloud `PrivacyCapability` is checked on any band. The `CLOUD_AI_WARRANTY_EXTRACTION` check at `:128-132` is removed (per OD-1). Thresholds are unchanged.
  - **Owner:** `AutoCreateWarrantyFromReceiptUseCase.execute`. AE-11, AE-12.
- **INV-9 Receipt-text access guard (per OD-1, recommended default).**
  - OCR text reaches `AutoCreate….execute` only as ephemeral raw text passed by `ReceiptSideEffectPlanner` (Policy A).
  - When it is unavailable, the side effect is `Skipped` with match event `SIDE_EFFECT_SKIPPED_PRIVACY`, and the use case is not invoked.
  - Neither the use case nor the repository reads persisted `rawOcrText` for extraction.
  - **Owner:** `ReceiptSideEffectPlanner` (`:181-211`). AE-13.
- **INV-10 One review-state derivation.**
  - Every UI count, filter and badge about review (`needsReviewCount`, "needs review" filter, card border or confirm actions) uses `WarrantyReviewState.of(w, now)`.
  - No ViewModel or screen inspects `needsReview` or `status` directly for review purposes.
  - **Owner:** the `WarrantyReviewState` domain function. AE-16.
- **INV-11 Expiry transition.**
  - `reconcileExpiredItems(now)` sets `status=EXPIRED, updatedAt=now` for rows with `status ∈ {ACTIVE, PENDING_REVIEW}` and `warrantyEndDate < now`.
  - It leaves `needsReview`, `autoDetected`, `extractionConfidence` and `extractionSource` unchanged.
  - It is idempotent and writes at most one batch `EXPIRED` lifecycle event per run (`warrantyId=-1`). The description holds counts only, with the pending count separate. `ExpiryReconciliationResult` gains `expiredPendingWarrantyCount`, a subset of `expiredWarrantyCount`, which stays the total of ACTIVE and PENDING rows expired.
  - The write barrier is checked first.
  - It is the **sole** writer of any transition into EXPIRED. Confirm computes EXPIRED for already-past rows but does not reconcile others.
  - **Owner:** `WarrantyTrackerRepository.reconcileExpiredItems` (SQL encoding in `WarrantyDao`). AE-14, AE-15.
- **INV-12 Reminders only for ACTIVE.**
  - Reminder selection and eligibility consider only `status='ACTIVE'` (`WarrantyDao.kt:19`; `WarrantyReminderDeliveryDao` eligibility).
  - PENDING_REVIEW and EXPIRED rows (including expired-unconfirmed) never create reminder delivery rows or notifications.
  - Existing rule, now pinned by tests. **Owner:** `WarrantyDao` queries used by `WarrantyExpirationWorker`. AE-14, AE-15, AE-18.
- **INV-13 Confirm ownership (FG-12).**
  - The only legal user-driven exits from review are `WarrantyTrackerRepository.confirmWarranty(warrantyId)` (NEW) and the existing `rejectAutoDetectedWarranty`.
  - The VM passes the id only. It never builds `status` or `needsReview` and never calls `updateWarranty` for that purpose.
  - Confirm runs one transaction: barrier check → fresh read by id → if the row is missing → `NotFound`; if `needsReview=false` and status ≠ PENDING_REVIEW → `AlreadyConfirmed` (no write, no event); else set `needsReview=false`, `status = EXPIRED` if `warrantyEndDate < now` else `ACTIVE`, `updatedAt=now`, and write a `CONFIRMED` lifecycle event (event failure fails the whole transaction closed — no confirm without its CONFIRMED event — matching R29 and the sibling in-transaction writers; amended 2026-10-23 at B1a).
  - Confirm never moves EXPIRED → ACTIVE.
  - **Owner:** `WarrantyTrackerRepository`. AE-17 to AE-20.
- **INV-14 Status/review consistency.**
  - `needsReview=true ⇒ status ∈ {PENDING_REVIEW, EXPIRED}`.
  - `status=PENDING_REVIEW ⇒ needsReview=true`.
  - Writers: AutoCreate (create), `reconcileExpiredItems` (expire), `confirmWarranty` (clear).
  - **Owner:** the `WarrantyTrackerRepository` write methods plus AutoCreate entity construction. AE-14, AE-17.

### B2 — transaction insight
- **INV-15 Capability split.**
  - The insight uses `AiCapability.TRANSACTION_INSIGHT` for the router decision, artifact `capability`, and diagnostics. It never uses `DASHBOARD_BRIEFING`.
  - It runs iff `aiEnabled ∧ transactionInsightEnabled ∧ allowOnDeviceAi`.
  - It is independent of `dashboardBriefingEnabled` and `proactiveBriefingsEnabled`.
  - **Owner:** `GenerateTransactionInsightUseCase` (gate) + `AiPolicyImpl`/`DefaultAiCapabilityRouter` tables (§1.6). AE-25, AE-32, AE-40.
- **INV-16 On-device only, no fall-through.**
  - `AiPolicyImpl.canUseCloudFor(_, TRANSACTION_INSIGHT) = false` for all settings, so the router can never return CLOUD in any `preferredMode`.
  - The use case depends on `OnDeviceDashboardBriefingService` only. It has no `HybridDashboardBriefingService`, `CloudDashboardBriefingService` or `PrivacySettingsRepository` dependency.
  - Route ≠ ON_DEVICE → `Skipped` with zero provider calls. Route = CLOUD (a defect) → `Skipped(ROUTE_REJECTED)`.
  - Input is built unredacted (data stays on device), which is current on-device behaviour.
  - **Owner:** `AiPolicyImpl` (policy) and `GenerateTransactionInsightUseCase` (consumer). AE-25 to AE-27, AE-39.
- **INV-17 Foreground short-circuit.**
  - Before any `router.decide`, `getOnDeviceModelStatus`/`checkStatus`, or Nano call, the use case evaluates `AppForegroundState.isAppInForeground()`.
  - false → `Skipped(APP_NOT_FOREGROUND)` with zero AICore/ML Kit calls.
  - This applies to every caller (`ManualExpenseRepository:241`, `NotificationProcessingPipeline:1742`). The check lives in one place, not in the callers.
  - A platform `BACKGROUND_USE_BLOCKED` at call time is the backstop and maps to `Skipped(BACKGROUND_USE_BLOCKED)`.
  - **Owner:** `GenerateTransactionInsightUseCase`. AE-28, AE-29.
- **INV-18 Timeout and no starvation.**
  - The insight is bounded by `TRANSACTION_INSIGHT_TIMEOUT_MS`. Its own timeout → `Skipped(TIMEOUT)`.
  - For every recommendation job that is not externally cancelled, deterministic recommendations are generated and saved whatever the insight outcome (Generated, any Skipped, or provider error). They carry `aiArtifact = null` unless Generated.
  - `RECOMMENDATION_JOB_TIMEOUT_MS ≥ TRANSACTION_INSIGHT_TIMEOUT_MS + 2_000` comes from one AppConfig constant.
  - **Owner:** the timeout belongs to `GenerateTransactionInsightUseCase`; non-starvation belongs to the recommendation-job block in each caller (structurally identical blocks; consolidation is optional, planner's call). AE-28 to AE-30.
- **INV-19 Bounded outcome and diagnostics.**
  - Every invocation ends in exactly one of `Generated` or `Skipped(reason ∈ TransactionInsightSkipReason)`, or rethrown cancellation.
  - The reason is recorded via `AiRuntimeDiagnostics.recordInteraction(type="transaction_insight", message=reason.name)`. This is the in-memory ring buffer, not a durable row: the insight is a best-effort enrichment, not a pipeline exit.
  - GenAI failures map to reasons by error-code constant, not by parsing text. If a typed kind cannot be carried without widening `AiServiceError`, collapsing to `PROVIDER_ERROR` is acceptable.
  - **Owner:** `GenerateTransactionInsightUseCase` (mapping input from `OnDeviceDashboardBriefingService`). AE-29.
- **INV-20 No new persistence.**
  - The insight `AiArtifactRecord` stays an in-memory handoff (never written to `ai_artifacts`). It is built with `expiresAt = now + TRANSACTION_INSIGHT_TTL_MS`.
  - Insight text persists only as `DashboardFollowThroughRecommendation.recommendationText`, with the existing 7-day expiry.
  - **Owner:** `GenerateTransactionInsightUseCase`; retention by `RecommendationRepository.deleteExpired`. AE-25.
- **INV-21 AI never writes expenses.** Insight output never changes Expense rows, categories or amounts. It only feeds recommendation text. **Owner:** `DashboardFollowThroughEngine` (sole consumer). AE-25.
- **INV-22 Single source of AI-settings defaults.**
  - `AiSettings` data-class defaults (`AiModels.kt`) are the only default values.
  - `AiSettingsRepositoryImpl.toAiSettings()` takes every absent-key fallback from a default `AiSettings()` instance.
  - A parity test pins `emptyPreferences().toAiSettings() == AiSettings()`.
  - **Owner:** `AiModels.kt`. AE-38.
- **INV-23 Insight key is resolved once.**
  - `ai_transaction_insight_enabled` is resolved exactly once per device by a DataStore `DataMigration` (§4). It runs before any read or `update()`.
  - It never overwrites an existing value and never throws.
  - **Owner:** `AiSettingsRepositoryImpl` (DataStore `produceMigrations`). AE-33 to AE-37.

### B3 — notification decoupling (cloud route only)
- **INV-24 Permission and proactive gate posting only.**
  - `DailyBriefingWorker`'s `WorkerGuardRequest` has `requiresNotificationPermission = false`. `requiredCapabilities = [CLOUD_AI_DAILY_BRIEFING]` is unchanged.
  - Generating and persisting `dashboard_home:<date>` never depends on notification permission or `proactiveBriefingsEnabled`.
  - The permission check sits **in the worker, immediately before delivery**, as the literal `notificationPermissionChecker.areNotificationsEnabled()`. This is the PR12K-3 pattern (F7).
  - **Owner:** `DailyBriefingWorker` (AGENTS.md:165-167, FG-11). AE-41, AE-42, AE-50.
- **INV-25 Scheduling predicate.**
  - `ai_daily_briefing` is armed iff `aiEnabled ∧ dashboardBriefingEnabled ∧ (allowCloudAi ∨ proactiveBriefingsEnabled)`.
    - For cloud-route users, proactive no longer affects arming (the B3 target).
    - For `allowCloudAi = false` users, arming is identical to today (proactive still arms the legacy on-device fallback path, which is untouched).
  - It is re-evaluated at startup (after `WorkerRegistry.scheduleAll`, existing order `AppStartupCoordinator.kt:83-84`) and on every change of an input. `AiSettingsViewModel.setAllowCloudAi` must start syncing in addition to aiEnabled, briefing and proactive.
  - **Owner:** `SyncProactiveBriefingWorkUseCase` (sole AI-settings-side arm/disarm authority). AE-45 to AE-47.
- **INV-26 One-directional privacy gating, no ping-pong.**
  - `PrivacyRuntimeWorkerPolicy` keeps `CLOUD_AI → {ai_daily_briefing}` (`:58`) unchanged. A true→false toggle cancels; false→true re-arms through `WorkerRegistry`.
  - The privacy path never invokes `SyncProactiveBriefingWorkUseCase`, and Sync never reads or writes privacy settings.
  - The guard's `CLOUD_AI_DAILY_BRIEFING` check and `GenerateDashboardBriefingUseCase`'s settings gate (`:55`) are the authoritative fail-closed runtime gates.
  - Divergence between the two arming authorities is bounded to at most one idle constrained run per day until the next startup Sync. This is the existing documented contract (`LEGAL_PATHS.md:519-521`; `DailyBriefingWorker.kt:163-166`).
  - **Owner:** `PrivacyRuntimeWorkerPolicy` (privacy side) and Sync (AI side), with no shared state. AE-44, AE-48.
- **INV-27 Delivery outcome honesty.**
  - `DeliverProactiveBriefingNotificationUseCase` returns `BriefingDeliveryOutcome`. It keeps its existing checks: settings and proactive (`:28`), dedupe (`:35`), freshness (`:39-43`).
  - The worker calls `ctx.addNotificationsSent()` only on `DELIVERED`.
  - A permission-denied skip is recorded with the bounded reason `NOTIFICATION_PERMISSION_DENIED` and does not change the worker result (Success).
  - **Owner:** the delivery use case (posting rules) and `DailyBriefingWorker` (counting). AE-41 to AE-43.
- **INV-28 On-device users are not newly armed.** For `allowCloudAi = false`, ai-fixes adds no new arming and no new generation path. The only difference: a run that was already armed (proactive on) is no longer skipped for notification permission, and any on-device attempt follows the untouched fallback path. **Owner:** INV-25 predicate. AE-45, AE-46.

### Conditional on OD-2 = A (recommended)
- **INV-29 Expiry reconciliation does not depend on notification permission.**
  - `WarrantyExpirationWorker` uses `requiresNotificationPermission = false` and runs `reconcileExpiredItems` on every run.
  - Reminder posting is guarded locally by `notificationPermissionChecker.areNotificationsEnabled()`.
  - `NOTIFICATION_CAPTURE` gating in `PrivacyRuntimeWorkerPolicy:60-63` is unchanged (§15).
  - **Owner:** `WarrantyExpirationWorker`. AE-51.

---

## 3. State machines

### 3.1 Warranty status with review overlay
States: `ACTIVE`, `PENDING_REVIEW` (nr=true), `EXPIRED` (nr=false), `EXPIRED` (nr=true, i.e. expired-unconfirmed), `CLAIMED`, `TRANSFERRED`, `∅` (none or deleted).

| From → To | Trigger (legal path) | Guard |
|---|---|---|
| ∅ → ACTIVE | `AutoCreate.execute` c ≥ 0.70 → `addWarrantyIgnoreConflicts`; manual `addWarranty` | no existing warranty for the receipt |
| ∅ → PENDING_REVIEW | `AutoCreate.execute` 0.40 ≤ c < 0.70 → `createReviewDraftWarranty` | the same; **no cloud gate** (INV-8) |
| PENDING_REVIEW → ACTIVE | `confirmWarranty(id)` | `warrantyEndDate ≥ now` (not `< now`) |
| PENDING_REVIEW → EXPIRED (nr=false) | `confirmWarranty(id)` | `warrantyEndDate < now` |
| PENDING_REVIEW → EXPIRED (nr=true) | `reconcileExpiredItems(now)` | `warrantyEndDate < now` |
| EXPIRED (nr=true) → EXPIRED (nr=false) | `confirmWarranty(id)` | — |
| ACTIVE → EXPIRED | `reconcileExpiredItems` (existing) | `warrantyEndDate < now` |
| ACTIVE → CLAIMED | `markWarrantyAsClaimed` (existing, unchanged) | — |
| any auto-detected → ∅ | `rejectAutoDetectedWarranty` (deletes warranty + return window atomically; existing) | — |
| any → ∅ | `deleteWarranty` (existing) | — |

**Illegal transitions:**
- EXPIRED → ACTIVE by any path (including confirm).
- Any → PENDING_REVIEW after creation.
- PENDING_REVIEW → ACTIVE or EXPIRED via `updateWarranty` from the UI.
- Any status change built in a ViewModel.
- `markWarrantyAsClaimed` on review states has no new precondition (out of scope). UX must not offer Claim on AWAITING_REVIEW or EXPIRED_UNCONFIRMED (handoff to UX).

**Retry, cancellation and process death:**
- AutoCreate side-effect retry is idempotent. An existing warranty takes the `AlreadyExists` branch and only upserts the return window.
- Cancellation propagates (existing tests `AutoCreate…Test:194,210`).
- If the process dies after the draft insert but before the return-window upsert, the draft exists without a return window. On restart the ephemeral raw text is gone, so the retry is Skipped (Policy A). The return window is filled on the next processing of that receipt, if any. This is acceptable because return windows are invisible.
- Reconcile is idempotent across retries. Confirm is idempotent (`AlreadyConfirmed`). Confirm racing reconcile resolves under Room transaction serialization to the same final state, because both use the `< now` predicate.

### 3.2 Return window
No state-machine change. `ReturnStatus` stays RETURNABLE/EXPIRED/RETURNED/EXCHANGED/NON_RETURNABLE. Expiry stays `markExpiredReturnWindows`. Only value provenance changes (INV-6).

### 3.3 Insight route decision (one invocation)
```
S0 read AiSettings (once)
  ├─ !aiEnabled                 → Skipped(AI_DISABLED)
  ├─ !transactionInsightEnabled → Skipped(INSIGHT_DISABLED)
  └─ !allowOnDeviceAi           → Skipped(ON_DEVICE_NOT_ALLOWED)
S1 AppForegroundState.isAppInForeground()
  └─ false                      → Skipped(APP_NOT_FOREGROUND)      [no AICore call of any kind]
S2 router.decide(TRANSACTION_INSIGHT)
  ├─ CLOUD                      → Skipped(ROUTE_REJECTED)          [defect guard; no call]
  ├─ DISABLED / DETERMINISTIC   → Skipped(ROUTE_NOT_ON_DEVICE)
  └─ ON_DEVICE                  → S3
S3 build input (unredacted) → OnDeviceDashboardBriefingService.generate within TRANSACTION_INSIGHT_TIMEOUT_MS
  ├─ Success, non-blank text    → Generated(artifact: capability=TRANSACTION_INSIGHT, mode=ON_DEVICE,
  │                                 targetKey="transaction:<id>", summaryText ≤ MAX_BRIEFING_LENGTH_CHARS)
  ├─ Success, blank / parse err → Skipped(EMPTY_OUTPUT)
  ├─ GenAI failure              → Skipped(BACKGROUND_USE_BLOCKED | BUSY | QUOTA_EXCEEDED | MODEL_UNAVAILABLE | PROVIDER_ERROR)
  ├─ own timeout                → Skipped(TIMEOUT)
  └─ parent cancellation        → rethrow (no outcome recorded)
```
- Retry: none. The insight is never retried.
- Process death: nothing was persisted. The post-commit recommendation job is lost (existing). The expense is already committed.

### 3.4 Daily briefing: arm → run → post
- **Arming (INV-25):** `DISARMED ⇄ ARMED`, driven only by Sync (AI side) and by the privacy-toggle cancel/re-arm (INV-26). Startup runs `scheduleAll` (arms), then Sync, which has the final word.
- **Run:**
```
guard(CLOUD_AI_DAILY_BRIEFING, requiresNotificationPermission=false)
  ├─ privacy denied/fail-closed → Skipped (reschedule midnight; existing)
  └─ allowed → fresh-artifact check → (fresh: Success, reschedule)
               → generate (GenerateDashboardBriefingUseCase; settings gate aiEnabled ∧ briefing) → artifact READY
               → permission = notificationPermissionChecker.areNotificationsEnabled()
                    ├─ false → record NOTIFICATION_PERMISSION_DENIED; no post
                    └─ true  → DeliverProactiveBriefingNotificationUseCase → outcome
               → notificationsSent += (outcome == DELIVERED ? 1 : 0)
               → Success → reschedule midnight
```
- Timeout: `RetryableWorkerException(WORKER_TIMEOUT)` (existing).
- Cancellation: rethrown (existing test `DailyBriefingWorkerTest:232`).
- Retry after a timeout that happened post-delivery: delivery is deduplicated by the engagement keys (existing).

### 3.5 Insight setting migration (once per device)
`KEY_ABSENT → RESOLVED(value)`, which is terminal. RESOLVED is changed only by the user toggle through `update()`. Rules are in §4.

---

## 4. Settings migration rules (AiSettings DataStore)

1. **Mechanism.** Use DataStore `produceMigrations` on the `ai_settings` store with one `DataMigration`:
   - `shouldMigrate(prefs)` = `ai_transaction_insight_enabled` is absent.
   - `migrate` returns prefs with that key set.
   - `cleanUp` does nothing.
   - DataStore runs migrations before the first read **and** before the first `edit`. This prevents `update()` (which writes all keys, F1) from materializing the read fallback first.
   - A startup coroutine is forbidden for this job.
2. **Resolution, in order:**
   - (a) `ai_dashboard_briefing_enabled` is present → copy its value. This covers an upgrade where the user touched settings.
   - (b) Otherwise, `InstallProvenance.classify()`:
     - NEW_INSTALL → `true`;
     - UPGRADE → `false`. This inherits the absent-key briefing default, which is false;
     - UNKNOWN → `false` (fail-safe: no AI processing).
3. **Never throw.** Any failure in provenance → UNKNOWN. The migration must not throw: an exception other than IOException would surface through `settings()` (`AiSettingsRepositoryImpl.kt:67-76`).
4. **Explicit values win forever.** Once the key exists, no later version re-derives it.
5. **Warranty flag.** There is no migration and no rewrite. Absent → false by INV-22 default. Explicit true/false is kept (owner decision). Because of F1, an explicit `true` usually means "materialized default", but it is harmless: the flag is inert (INV-7).
6. **Read fallback.** `transactionInsightEnabled` falls back to `false`. That is only reachable on an IOException read (`emptyPreferences()`) or corruption replacement (`ReplaceFileCorruptionHandler`); after corruption, the migration re-runs and resolves via provenance (NEW_INSTALL → true; UPGRADE or UNKNOWN → false; so a never-updated install re-enables the insight after corruption). The product default "on for new installs" lives only in rule 2(b).
7. **Backup and restore.** These do not affect the migration (F12).
8. **Edge accepted.** If the user installs this version and an update lands before first launch, provenance says UPGRADE and the insight starts off. This fails safe. The user can enable it.

---

## 5. B1 rules under Q1(a)

### 5.1 What replaces the cloud call
- Nothing does. `upsertReturnWindowForReceipt(receiptId, warranty)` computes the window from merchant defaults (INV-6).
- The success criteria "zero cloud calls on re-processing a receipt with an existing warranty" and "no cloud-derived return-window value persisted without a tap" hold by construction: no cloud value is produced.

### 5.2 Fate of code in the dead or retired path

| Item | Fate |
|---|---|
| `extractWarrantyResult` (`Repo:437-466`) | DELETE |
| `toWarrantyEntityOrNull` (`:476-523`), `AUTO_ACCEPT_CLOUD_CONFIDENCE`, `REVIEW_CLOUD_CONFIDENCE` (`:74,76`), `parseWarrantyType` (`:525-531`), `toCalendarMonthEndDate` (`:602-612`, sole caller is `toWarrantyEntityOrNull`) | DELETE |
| `extractWarrantyFromReceipt` (`:432`), `processReceiptForWarranty` (`:569`) | DELETE (tests only) |
| `extractReturnWindow(…, extractionResult)` parameter | REMOVE the parameter |
| Constructor deps `cloudExtractionService`, `aiSettingsRepository`, `aiPolicy`, `aiCapabilityRouter` on `WarrantyTrackerRepository` | REMOVE (Hilt graph + `hilt-bindings-map.md`) |
| The `AI_EXTRACTION_DISCARDED` write in the cloud discard path (`:477-494`) | Removed with `toWarrantyEntityOrNull`. The constant stays (§1.4). |
| `CloudWarrantyExtractionService`, its `AiModule` provider, `WarrantyExtractionModels`, its own tests | KEEP, unreachable. A source-scan guard allows references only from `di/AiModule.kt`. Deletion is deferred to A6 ("Nano warranty route + unified thresholds"), consistent with "retire **for now**". |
| `AiCapability.WARRANTY_EXTRACTION` (enum, router/policy tables, `CLOUD_FIRST_CAPABILITIES` membership) | KEEP unchanged. It has no caller, so it is unreachable as a route. Removing the enum value is forbidden: the converter fallback would silently map stored names to DASHBOARD_BRIEFING. |
| `PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION`, the RP-15 matrix row (`EffectiveCloudAiPolicy.kt:19,56`), `CloudPayloadPurpose.WARRANTY_EXTRACTION` | KEEP unchanged (RP-15 matrix and test unchanged, a success criterion) |
| `AutoCreate…:128-132` cloud gate and the `privacyGate` constructor dependency | REMOVE (per OD-1(a)) |

### 5.3 Local-draft fix
In the 0.40–0.70 band, call `createReviewDraftWarranty` directly. The result mapping is unchanged: `LowConfidence` on success, `AlreadyExists`/`Failure` passed through. The receipt-text guard is Policy A (INV-9, OD-1).

### 5.4 Pending expiry
- Owner: `reconcileExpiredItems` (INV-11).
- Lifecycle: one batch `EXPIRED` event with counts (`"Batch expired: N warranties (P unconfirmed), M return windows"`, counts only).
- Reminders excluded (INV-12).
- The UI shows expired-unconfirmed through `WarrantyReviewState`, even before reconciliation runs (INV-10). Display copy is a UX decision.
- No conflict with `rejectAutoDetectedWarranty`, which deletes the row (allowed in any review state), or with reconciliation (only →EXPIRED).
- The persisted transition reaches notification-denied users only if OD-2 = A.

### 5.5 `confirmWarranty` write ownership
New repository method, per INV-13. The VM change is: `confirmWarranty(warranty)` → `repository.confirmWarranty(warranty.id)`, plus bounded error mapping (INV-2) and cancellation rethrow (INV-4).

### 5.6 Required LEGAL_PATHS changes (doc-change requirements; not edited here)
- `:990` "Low/medium → WarrantyReviewDraft" → "Medium (0.40 ≤ c < 0.70) → `Warranty` with `status=PENDING_REVIEW`, `needsReview=true` (no separate draft entity). Low (< 0.40) → nothing persisted."
- `:992` / `:998` → per OD-1(a): "Receipt OCR text reaches the use case only as ephemeral raw text via `ReceiptSideEffectPlanner` Policy A. There is no fallback to persisted or sanitized text. Receipt rows are read only through `ReceiptRepository`. No `PrivacyGate` capability applies to local regex extraction. Any future cloud or model step requires `PrivacyGate.check(CLOUD_AI_*)` before data leaves the device."
- Add **Return window:** "`WarrantyTrackerRepository.upsertReturnWindowForReceipt` — write barrier → structured receipt fields via `ReceiptRepository` → merchant-default `returnDays` → insert, or update preserving status/conditions/refund."
- Add **Confirm / expire:** `confirmWarranty(id)` (INV-13); `reconcileExpiredItems` (INV-11).
- FORBIDDEN additions:
  - "❌ Cloud/AI extraction in the warranty or return-window path"
  - "❌ ViewModel-built warranty status/needsReview changes (use confirm/reject/claim)"
  - "❌ Reminders for non-ACTIVE warranties"
  - "❌ Reading persisted rawOcrText for warranty or return-window derivation"
- §Privacy/Cloud AI `:383`: note that `CloudWarrantyExtractionService` remains one of the 8 providers but has no production caller.

---

## 6. B2 rules

1. **Capability split** (§1.6, INV-15). `HybridDashboardBriefingService` and `DASHBOARD_BRIEFING` become briefing-only.
2. **Router entry.** On-device-implemented. It is not cloud-first, and `canUseCloudFor = false`, so there is no fall-through in AUTO, ON_DEVICE or CLOUD preferred modes (`DefaultAiCapabilityRouter.kt:56-159`, `canUseCloud` → `AiPolicy.canUseCloudFor`) (INV-16).
3. **Privacy plumbing.** No `PrivacyCapability`, `CloudPayloadPurpose`, `EffectiveCloudAiPolicy` row or audit entry. Honest attribution comes from the `TRANSACTION_INSIGHT` tag in route diagnostics and in the artifact. With no cloud call, no `CLOUD_AI_DAILY_BRIEFING` check or audit happens per expense.
4. **Foreground signal.** `AppForegroundState` (§1.7). The short-circuit is in the use case (INV-17).
5. **Timeout** (INV-18). 3 s is kept as a named constant; whether Nano warm-up fits is UNVERIFIED. "Mostly no insight" is acceptable.
6. **Retention** (INV-20). Insight text in recommendations expires after 7 days. Nothing goes to `ai_artifacts`.
7. **Legacy end of life.**
   - The current code writes no `transaction:` artifacts (F3).
   - Historic rows from older versions are UNVERIFIED but, if any, carry `capability=DASHBOARD_BRIEFING` and `expiresAt = createdAt + 24 h` (or null). They are swept by `AiArtifactDao.deleteExpired` (`RetentionModule.kt:163`; null-expiry cutoff).
   - No reader matches them: every `DASHBOARD_BRIEFING` reader uses the exact key `dashboard_home:<date>` (`DailyBriefingWorker.kt:90`, `DeliverProactive…:39`, `HomeViewModel.kt:285`).
   - Cloud-generated recommendation texts created before the upgrade expire within 7 days.
   - No purge, re-tag or migration.
8. **Callers.** Both keep their `aiEnabled` gate and deterministic recommendations. They consume `outcome.artifactOrNull`. `ManualExpenseRepository:233-255` must rethrow cancellation (INV-4). `NotificationProcessingPipeline:1752-1755` already does.

---

## 7. B3 rules

1. **`WorkerGuardRequest` change.** Drop `requiresNotificationPermission = true` (`DailyBriefingWorker.kt:75`). Inject `NotificationPermissionChecker` and check it immediately before delivery (INV-24).
2. **Scheduling.** INV-25 predicate in `SyncProactiveBriefingWorkUseCase` (rename optional, not required). The VM also syncs on `setAllowCloudAi`.
3. **No loop.** The privacy and AI arming authorities never call each other. Startup order is `scheduleAll` then Sync. The runtime guards are authoritative (INV-26).
4. **Posting checks.**
   - Permission: in the worker.
   - Proactive, dedupe, freshness: in `DeliverProactiveBriefingNotificationUseCase`, unchanged.
   - `AndroidNotificationService.areNotificationsEnabled()` (`:108`) stays as a platform backstop. It is not a business rule.
5. **Counting.** INV-27.
6. **Unchanged.**
   - The CLOUD_AI privacy toggle cancels or re-arms (`PrivacyRuntimeWorkerPolicy:58`).
   - The guard's `CLOUD_AI_DAILY_BRIEFING` requirement.
   - The `HybridDashboardBriefingService` cloud→on-device fallback in the worker (belongs to `on-device-briefing`).
   - The midnight reschedule contract.
   - The Home reader (`HomeViewModel.kt:281-314`, which displays any READY artifact).
   - `WorkerGuardVerifier` and its registry: no new worker and no rename, so no change.

---

## 8. Test changes: stop-and-ask list (FG-19)

### 8.1 `app/src/test/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepositoryTest.kt`

**DELETE.** These test the cloud confidence bands only. The code is deleted under Q1(a) and has no production equivalent. They fall within the approved ":641 + ~:665-791 band tests".

| Line | Test name |
|---|---|
| :641 | `cloudWarrantyConfidence_0_8_autoCreatesWithoutReview` |
| :673 | `cloudWarrantyConfidence_0_4_createsNeedsReviewDraft` |
| :705 | `cloudWarrantyConfidence_0_1_discardsWithDiagnostic` |
| :735 | `cloudWarrantyConfidence_0_75_exactBoundary_autoAccepts` |
| :767 | `cloudWarrantyConfidence_0_30_exactBoundary_createsReviewDraft` |

Note: `:673`, `:705` and `:767` cover the review and discard bands, not ≥0.75. The brief called the discard diagnostic "production-reached", but it is unreachable once Q1(a) retires the cloud call. The owner should confirm this explicitly at plan approval.

**RETARGET to the production path (not deleted):**

| Line | Current test | Retarget to |
|---|---|---|
| :127 | `extractWarrantyFromReceipt delegates to cloud service` | `upsertReturnWindowForReceipt uses merchant default and no AI even when rawOcrText contains a return policy`. Behavioural: returnDays = merchant default, `returnConditions == null`. Pair it with the new source-scan guard (INV-5). |
| :171 | `extractWarrantyFromReceipt uses calendar month addition for warranty end date` | The production end-date computation (`AutoCreate….calculateWarrantyEndDate` via the review-draft/promote path, or `WarrantyTextExtractor`). Jan 31 2024 + 1 month → last covered day Feb 29, exclusive end Mar 1 00:00 local (AE-24). |
| :217 | `extractWarrantyFromReceipt skips cloud extraction when route is not cloud` | `AutoCreate….execute` 0.40–0.70 creates a PENDING_REVIEW draft without consulting any cloud gate. Lives in `AutoCreateWarrantyFromReceiptUseCaseTest` (AE-11). |
| :316 | `extractReturnWindow persists extracted return metadata` | Legacy-row update semantics: an existing row with `returnDays=45, returnConditions="Tags attached"` → upsert → returnDays = merchant default, conditions preserved, status preserved (AE-23). |
| :347 | `processReceiptForWarranty creates return window for return policy only extraction` | `upsertReturnWindowForReceipt(receiptId, warranty = null)` creates a merchant-default window with productName "Purchase from <merchant>". |

**MECHANICAL** (no assertion change): setup `:68-92` drops the removed constructor arguments and the router/policy/settings stubs.

### 8.2 Other tests whose assertions change (retarget; listed for sign-off)
- `GenerateTransactionInsightUseCaseTest` `:71` (`invoke redacts merchant and exact amount for redacted cloud mode`) and `:114` (`invoke redacts on cloud route when privacy requires it…`). The cloud route no longer exists for the insight. Retarget to AE-26 (a CLOUD decision is rejected, no provider call) and AE-25 (ON_DEVICE artifact with capability TRANSACTION_INSIGHT).
- `SyncProactiveBriefingWorkUseCaseTest:43` `invoke cancels work when proactive briefings are disabled`. It now holds only when `allowCloudAi=false`. Retarget to the predicate table (AE-47).
- `DeliverProactiveBriefingNotificationUseCaseTest` (6 tests). The return type becomes `BriefingDeliveryOutcome`. Assertions are kept and outcome checks added.
- `DailyBriefingWorkerTest`: `successful_retry_records_notifications_sent_once` (`:757`) must stub `DELIVERED`. Add permission-denied tests (AE-41).
- `AutoCreateWarrantyFromReceiptUseCaseTest`: mechanical constructor change (`privacyGate` removed per OD-1(a)).
- `WorkerExecutionGuardTest`, `WorkerContractTest`, `PrivacyRuntimeWorkerPolicyTest`, `PrivacySettingsRepositoryImplWorkerGatingTest`: expected unchanged. Whether any asserts DailyBriefingWorker's permission flag is UNVERIFIED; the planner must grep.

### 8.3 Guard amendment (stop-and-ask: modifies a guard)
`SourceScanningArchitectureGuardTest.kt:408`: `optionalNotificationWorkers` += `"DailyBriefingWorker.kt"`, plus `"WarrantyExpirationWorker.kt"` if OD-2 = A. The rule is not weakened: allowlisted workers must contain the local permission check (`:416-423`). It follows the PR12K-3 precedent.

### 8.4 New guards proposed
1. Only `di/AiModule.kt` references `CloudWarrantyExtractionService` in `app/src/main`.
2. `GenerateTransactionInsightUseCase.kt` imports neither `HybridDashboardBriefingService` nor `CloudDashboardBriefingService`.
3. `WarrantyTrackerViewModel.kt` does not reference `WarrantyStatus.ACTIVE`/`PENDING_REVIEW` in write paths (or does not call `updateWarranty`).

---

## 9. Acceptance examples (Given/When/Then; synthetic data)

`NOW = 2026-11-15T10:00Z`. "Cloud fully enabled" means `aiEnabled`, `allowCloudAi`, privacy `cloudAiEnabled`, Gemini key present, network up, and `warrantyExtractionEnabled = true`.

**Part A**
- **AE-1** (INV-1) Given FEATURES.md after Part A. When it is grepped at the 8 engine entries for `AI-powered|AI-generated|AI `. Then there are 0 matches, and the warranty section contains "not active" wording.
- **AE-2** (INV-2,3) Given `aiSettingsRepository.update` throws `IOException("/data/user/0/x/files/datastore/ai_settings.preferences_pb")`. When `setDashboardBriefingEnabled(true)` runs. Then `settingsWriteError` is the localized resource for "settings could not be saved". It contains no path or exception text, and the log holds the class name only.
- **AE-3** (INV-2,13) Given `repository.confirmWarranty(7)` throws `SQLiteException("no such table: warranties")`. When the VM confirms id 7. Then `mutationError` is the localized "could not confirm" resource with no SQL text, and `mutatingWarrantyIds` no longer contains 7.
- **AE-4** (INV-4) Given `viewModelScope` is cancelled during `update`. When the write suspends. Then `CancellationException` propagates and no `settingsWriteError` is set.
- **AE-5** (INV-2) Given `checkStatus` throws `Exception("token=abc123")`. When runtime status is refreshed. Then each `runtime.message` is a constant from the closed set and does not contain `abc123`.
- **AE-6** (INV-1) Given `DataRetentionWorker.kt` after the change. When the header is reviewed. Then it does not list `ai_chat_messages` or diagnostics as gaps.

**B1**
- **AE-7** (INV-7,22) Given empty `ai_settings` (fresh install). When settings are read. Then `warrantyExtractionEnabled == false`, and `AiSettings().warrantyExtractionEnabled == false`.
- **AE-8** (INV-7) Given a stored explicit `ai_warranty_extraction_enabled=true`. When the app is upgraded and settings are read. Then the value is still true, and processing a receipt still makes 0 cloud calls (AE-9).
- **AE-9** (INV-5,6) Given cloud fully enabled; receipt 10 (`parsedMerchant="Best Buy"`, `rawOcrText` contains "returns accepted within 60 days"); an existing ACTIVE warranty for receipt 10; no return window yet. When `AutoCreate.execute(10, text)` runs. Then the result is `AlreadyExists`; one return window is inserted with `returnDays=15`, `returnConditions=null`, `returnPolicyUrl=null`; and there are 0 network requests and 0 `router.decide(WARRANTY_EXTRACTION)` calls.
- **AE-10** (INV-5,8) Given cloud fully enabled and local confidence 0.85 on receipt 11 ("Apple"). When `execute` runs. Then an ACTIVE warranty is created (`needsReview=false`) and the return window has `returnDays=14`, with 0 cloud calls.
- **AE-11** (INV-8) Given defaults (cloud off; privacy denies `CLOUD_AI_WARRANTY_EXTRACTION`) and local confidence 0.55 on receipt 12. When `execute` runs. Then a PENDING_REVIEW warranty is created (`needsReview=true`, `extractionSource="ocr"`) with a merchant-default return window, the result is `LowConfidence`, the side effect is `Completed`, and no `PrivacyGate` call occurs.
- **AE-12** (INV-8) Given local confidence 0.35. When `execute` runs. Then no warranty and no return window exist for the receipt.
- **AE-13** (INV-9) Given a receipt saved in `DO_NOT_STORE` mode after a process restart (no ephemeral raw). When the warranty side effect runs. Then it is `Skipped`, the match event `SIDE_EFFECT_SKIPPED_PRIVACY` is written, and `execute` is not invoked.
- **AE-14** (INV-11,12,14) Given W1: PENDING_REVIEW, `needsReview=true`, `warrantyEndDate=NOW−1d`; and W2: ACTIVE with `warrantyEndDate=NOW−2d`. When `reconcileExpiredItems(NOW)` runs. Then W1 has status EXPIRED and still `needsReview=true` (EXPIRED_UNCONFIRMED); W2 is EXPIRED; there is 1 batch EXPIRED event with `expiredWarrantyCount=2` and `expiredPendingWarrantyCount=1`; and there are 0 reminder delivery rows for W1. A second run changes nothing and writes no event.
- **AE-15** (INV-11,12) Given W3: PENDING_REVIEW with `warrantyEndDate=NOW+5d`. When reconcile runs and the reminder windows (7d/30d) are evaluated. Then W3 stays PENDING_REVIEW, and no reminder is seeded or posted.
- **AE-16** (INV-10) Given W1 still PENDING_REVIEW in the DB with `warrantyEndDate=NOW−1d` (reconcile not yet run). When the warranty screen state is derived. Then `needsReviewCount` excludes W1, and W1's review state is EXPIRED_UNCONFIRMED.
- **AE-17** (INV-13,14) Given W3 (AWAITING_REVIEW, end NOW+5d). When `confirmWarranty(W3.id)` runs. Then status is ACTIVE with `needsReview=false`, 1 `CONFIRMED` event is written, and the write barrier was checked first.
- **AE-18** (INV-13,12) Given W1 (EXPIRED, `needsReview=true`). When `confirmWarranty(W1.id)` runs. Then status stays EXPIRED with `needsReview=false`, and no reminder is created.
- **AE-19** (INV-13) Given W2 (EXPIRED, `needsReview=false`). When `confirmWarranty(W2.id)` runs. Then the result is `AlreadyConfirmed` with no write and no event. Given id 999 does not exist, confirm returns `NotFound`.
- **AE-20** (INV-13) Given maintenance mode BACKUP_EXPORTING. When `confirmWarranty(W3.id)` runs. Then `DatabaseAccessBlockedException` is thrown, the row is unchanged, and the VM shows a bounded message.
- **AE-21** (§3.1) Given W1 (EXPIRED_UNCONFIRMED) with a return window. When `rejectAutoDetectedWarranty(W1)` runs. Then the warranty and the return window are both deleted in one transaction.
- **AE-22** (INV-5) Given `app/src/main` after B1. When it is source-scanned. Then `WarrantyTrackerRepository.kt` has no reference to `CloudWarrantyExtractionService`, `AiCapabilityRouter`, `WarrantyExtractionResult` or `AUTO_ACCEPT_CLOUD_CONFIDENCE`, and the only reference to `CloudWarrantyExtractionService` is in `di/AiModule.kt`.
- **AE-23** (INV-6, legacy) Given an existing return window for receipt 13 (merchant "Policy Store", `returnDays=45`, `returnConditions="Tags attached"`, status RETURNED). When `upsertReturnWindowForReceipt(13, w)` runs. Then `returnDays=30` and the deadline is recomputed, while `returnConditions="Tags attached"`, `status=RETURNED`, `returnedAt` and `refundAmount` are unchanged.
- **AE-24** (§8.1 :171) Given purchase date 2024-01-31 (local) and duration 1 month on the production path. When the end date is computed. Then the last covered day is 2024-02-29 and the exclusive end is 2024-03-01T00:00 local.

**B2**
- **AE-25** (INV-15,16,20,21) Given cloud fully enabled, `dashboardBriefingEnabled=true`, `transactionInsightEnabled=true`, app in foreground, Nano AVAILABLE returning "Big spend at Café X". When a manual expense (id 42, 187.43 EUR) is saved. Then there is exactly 1 `OnDeviceDashboardBriefingService.generate` call and 0 cloud calls; the artifact has `capability=TRANSACTION_INSIGHT`, `mode=ON_DEVICE`, `expiresAt=NOW+24h`; no `ai_artifacts` row exists; recommendations are saved with that text; and expense 42 is unchanged.
- **AE-26** (INV-16) Given a router fake returning CLOUD for TRANSACTION_INSIGHT. When the insight is invoked. Then the outcome is `Skipped(ROUTE_REJECTED)` with 0 provider calls.
- **AE-27** (INV-16) Given the real router and policy with `preferredMode ∈ {AUTO, CLOUD, ON_DEVICE}`, cloud fully enabled, and Nano NOT_INSTALLED. When `decide(TRANSACTION_INSIGHT)` runs. Then the route is DETERMINISTIC_FALLBACK, never CLOUD, and `canUseCloudFor(s, TRANSACTION_INSIGHT) == false` for all generated settings `s`.
- **AE-28** (INV-17,18) Given the app in background and a notification-captured expense. When `launchRecommendationEnrichment` runs. Then there are 0 `router.decide`, 0 `getOnDeviceModelStatus` and 0 Nano calls; the outcome is `Skipped(APP_NOT_FOREGROUND)` recorded in `AiRuntimeDiagnostics`; and deterministic recommendations are saved with fallback text and `sourceArtifactId=""`.
- **AE-29** (INV-17,19) Given foreground, but Nano throws the GenAI error with code BUSY (or BACKGROUND_USE_BLOCKED, or the per-app quota code). When the insight runs. Then the outcome is `Skipped(BUSY)` (or the matching reason, or `PROVIDER_ERROR` if collapsed), the diagnostics message equals the enum name, and recommendations are saved.
- **AE-30** (INV-18) Given Nano that never returns. When the job runs. Then the insight is `Skipped(TIMEOUT)` after `TRANSACTION_INSIGHT_TIMEOUT_MS`, and recommendations are saved before `RECOMMENDATION_JOB_TIMEOUT_MS` expires.
- **AE-31** (INV-4) Given the job's scope is cancelled while Nano is suspended. When the cancellation arrives. Then `CancellationException` propagates out of the use case and the job, no Skipped outcome is recorded, and nothing is saved.
- **AE-32** (INV-15) Given `dashboardBriefingEnabled=false, transactionInsightEnabled=true`. When an expense is saved in the foreground. Then the insight is attempted. Given `dashboardBriefingEnabled=true, transactionInsightEnabled=false`, the outcome is `Skipped(INSIGHT_DISABLED)`, and daily briefing arming is unaffected.
- **AE-33** (INV-23) Given empty `ai_settings` and provenance NEW_INSTALL. When settings are read the first time. Then the key is stored explicitly as true.
- **AE-34** (INV-23) Given stored `ai_dashboard_briefing_enabled=true` (or false) and no insight key. When settings are read after the upgrade. Then the insight key is stored as true (or false).
- **AE-35** (INV-23) Given empty `ai_settings` and provenance UPGRADE. When settings are read. Then the insight key is stored as false.
- **AE-36** (INV-23) Given a stored insight key = false and briefing = true. When the app is updated again. Then the insight stays false.
- **AE-37** (INV-23) Given provenance throws. When settings are read. Then the insight key is stored as false and no exception escapes `settings()`.
- **AE-38** (INV-22) Given `emptyPreferences()`. When it is mapped. Then the result equals `AiSettings()` field by field.
- **AE-39** (INV-16, privacy) Given cloud fully enabled. When 5 expenses are saved in the foreground. Then `CloudDashboardBriefingService.generate` is called 0 times and 0 `CLOUD_AI_DAILY_BRIEFING` privacy checks or audit entries come from the saves.
- **AE-40** (INV-15) Given `allowOnDeviceAi=false`. When the insight runs. Then the outcome is `Skipped(ON_DEVICE_NOT_ALLOWED)` with 0 AICore calls.

**B3**
- **AE-41** (INV-24,27) Given a cloud-route user (`aiEnabled`, briefing, `allowCloudAi`, privacy cloud on), proactive on, and notifications denied. When `DailyBriefingWorker` runs. Then the guard does not skip; `dashboard_home:2026-11-15` is READY; 0 notifications are posted; `notificationsSent=0`; the run is SUCCESS (not SKIPPED `NOTIFICATION_PERMISSION_DENIED`); and the bounded reason `NOTIFICATION_PERMISSION_DENIED` is recorded for delivery only.
- **AE-42** (INV-24,25) Given the same user with notifications allowed and proactive off. When Sync runs. Then the worker is armed. When the worker runs, the artifact is READY, the outcome is `SKIPPED_SETTINGS_OFF`, and no notification is posted.
- **AE-43** (INV-27) Given both allowed. When the worker runs. Then exactly 1 notification is posted, the outcome is `DELIVERED`, and `notificationsSent=1`. A retry the same day is `SKIPPED_DUPLICATE`.
- **AE-44** (INV-26) Given privacy `cloudAiEnabled` true→false. When the toggle is applied. Then `ai_daily_briefing` is cancelled. If a run happens anyway, the guard returns Skipped(privacy) and no generation happens.
- **AE-45** (INV-25,28) Given `allowCloudAi=false`, briefing on, proactive off. When Sync runs. Then the worker is cancelled (not armed), as today.
- **AE-46** (INV-28) Given `allowCloudAi=false`, briefing on, proactive on. When Sync runs. Then the worker is armed, as today.
- **AE-47** (INV-25) Predicate table: (ai, briefing, cloud, proactive) → armed only for rows with ai ∧ briefing ∧ (cloud ∨ proactive). All 16 combinations are asserted.
- **AE-48** (INV-26) Given startup. When `scheduleAll` and then Sync run. Then the final arming equals the INV-25 predicate. Toggling privacy CLOUD_AI never invokes Sync, and Sync never calls `PrivacySettingsRepository`.
- **AE-49** (INV-4) Given the worker is cancelled mid-generation. Then `CancellationException` propagates (existing test `:232` stays green).
- **AE-50** (INV-24) Given `DailyBriefingWorker.kt`. When it is source-scanned. Then it has no `requiresNotificationPermission = true` and does contain `notificationPermissionChecker.areNotificationsEnabled()`.

**Conditional (OD-2 = A)**
- **AE-51** (INV-29) Given notifications denied and W1 (PENDING_REVIEW, end NOW−1d). When `WarrantyExpirationWorker` runs. Then W1 is EXPIRED with `needsReview=true`, 0 reminders are posted, and the run is SUCCESS.

---

## 10. Schema and migration verdict: **NONE (no Room change, no version bump)**
- `AiCapability.TRANSACTION_INSIGHT` is additive. `ai_artifacts.capability` is TEXT via `Converters.fromAiCapability` (`.name`) with no CHECK constraint (F11). In addition, the insight record is never persisted (F3), so the value never reaches Room in this bundle.
- Warranty statuses reuse the existing values. "Expired unconfirmed" = `EXPIRED ∧ needsReview` uses existing columns.
- `WarrantyLifecycleEventTypes.CONFIRMED` is an additive TEXT constant.
- `ReturnStatus` is unchanged (no pending value under Q1(a)).
- DataStore: one new key plus a one-time `DataMigration` write. This is a DataStore migration, not Room, and it is not part of the backup (F12).
- Backup and restore: the backup copies the Room DB only. Restoring an older DB into the new app works: PENDING_REVIEW rows past end date are reconciled on the next eligible run (and shown as expired-unconfirmed immediately via INV-10). Legacy `ai_artifacts` rows are TTL-swept. Restoring a newer DB into an older build is unsupported, as today.
- Destructive operations: none. No purge of legacy cloud-derived `returnConditions` and no backfill of drafts for historic 40–70% receipts. A backfill is impossible anyway: Policy A forbids re-extracting from persisted text.

---

## 11. Privacy rules
- **P-1 Stored.**
  - New PENDING_REVIEW drafts store regex-derived product/merchant/support fields locally, as ACTIVE warranties do today.
  - Insight text (≤ 600 chars) is stored only as recommendation text with a 7-day expiry.
  - No new tables and nothing new sent off-device.
- **P-2 Off-device.**
  - B1 removes the only warranty upload (persisted `rawOcrText` + merchant + total + date + currency).
  - B2 removes per-expense merchant + amount uploads under the briefing purpose.
  - B3 sends nothing new: the cloud briefing payload and purpose are unchanged, and it runs only for users already armed by the predicate.
- **P-3 Exported or backed up.** The Room backup includes warranties and recommendations as today. AI settings are not backed up.
- **P-4 Logged.**
  - Only bounded reason enums, counts and IDs (INV-3, INV-19, INV-27).
  - Lifecycle event descriptions in touched paths are constants or counts.
  - The removed cloud-discard event used to embed confidence and receiptId; it is gone.
- **P-5 Fail-closed defaults.**
  - Insight read fallback = false. Provenance UNKNOWN → false.
  - Foreground unknown → background.
  - The warranty flag defaults to false and is inert.

---

## 12. Edge cases

| Case | Rule |
|---|---|
| Mixed currencies | No money arithmetic changes. Insight input carries `currencyCode` (unchanged). Protected value stays ACTIVE-only (pending excluded, unchanged). Adjacent defect: the deterministic fallback text hard-codes "€" (§15). |
| Missing or stale FX | Not touched. |
| Unavailable vs zero | An insight that is unavailable or empty becomes `Skipped` (no empty-string artifact), and the recommendation uses deterministic text. Delivery "not attempted" (permission) is distinct from `NOT_DELIVERED` (platform). |
| Rounding conservation | Not applicable. |
| Time zones / DST | Warranty end dates are fixed epoch ms computed in the creation-time zone. Expiry, review state and confirm all compare epoch ms with `< now`, so a zone change does not move boundaries. The briefing date key uses the zone at worker start (unchanged). The 1 ms boundary gap between `getActiveWarranties (end > now)` and `markExpired (end < now)` is pre-existing (§15). |
| Month-end | Calendar-month addition (Jan 31 + 1 → Feb 29/28) on the production path (AE-24). Return windows are day counts. |
| Edits / deletes | Receipt deleted → FK behaviour unchanged. Warranty rejected → warranty and window deleted atomically. Expense edits do not regenerate the insight. No un-expire path exists. |
| Partial or failed receipt processing | OCR_FAILED, BANK_STATEMENT and MANUAL_PLACEHOLDER are skipped (unchanged). Missing ephemeral raw → Skipped (INV-9). Return-window persist failure after a draft insert → retry → `AlreadyExists` → upsert. |
| Duplicates / idempotency | One warranty per receipt (lookup + IGNORE) and one window per receipt (unique index), both unchanged. Reconcile, confirm, migration and Sync (REPLACE) are idempotent. Delivery is deduplicated by the engagement keys. |
| Empty states | Nothing expired → no lifecycle event (existing `if counts > 0`). No pending items → `needsReviewCount=0`. No Nano → always `Skipped`, and recommendations still appear. |
| Retry / cancellation / process death | §3.1, §3.3, §3.4. |
| Historic receipts that lost drafts (40–70%, cloud denied) | Not recovered (no ephemeral raw). Accepted. |
| Legacy cloud-derived return-window data | Left as is. Re-processing recomputes `returnDays` from merchant defaults and preserves conditions and status (AE-23). It is invisible and stays local. |

---

## 13. Proposed architecture-doc changes (for impact-mapper; not edited here)
- `LEGAL_PATHS.md`:
  - §Warranty Auto-Create (§5.6).
  - §Privacy/Cloud AI: add an "On-device-only capabilities" line (NOTIFICATION_PARSE, REVIEW_PRIORITIZATION, SEMANTIC_DEDUPE, TRANSACTION_INSIGHT: `canUseCloudFor=false`, never cloud-first).
  - §Workers `:494`: add the optional-notification pattern (`requiresNotificationPermission=false` + local check before posting: ReceiptMatchingWorker, DailyBriefingWorker [, WarrantyExpirationWorker]).
- `ARCHITECTURE.md:1271` (DailyBriefingWorker gating); `:1978` F1 pipeline (no cloud).
- `DEPENDENCY_MAP.md:591,800` (CloudWarrantyExtractionService has no production caller).
- `hilt-bindings-map.md` (WarrantyTrackerRepository dependencies).
- `FEATURES.md` (INV-1).

---

## 14. Open decisions (owner)

- **OD-1 — Receipt-text guard for local warranty extraction (re-point of `LEGAL_PATHS.md:992,998`).**
  - The brief asked to "re-point, not drop" the PrivacyGate check. Source shows no `PrivacyCapability` that governs local receipt processing (F9). The privacy control that actually guards receipt text is `ReceiptSideEffectPlanner` Policy A, which already runs before the use case.
  - **Recommended (a):** treat Policy A plus ReceiptRepository-only reads as the guard. Remove the cloud check and the `PrivacyGate` dependency from `AutoCreate`. Rewrite LEGAL_PATHS `:992/:998` as in §5.6.
  - (b) Add a new local-only `PrivacyCapability` that defaults to Allowed and check it in `AutoCreate`. Not recommended: it is ceremonial, touches every exhaustive privacy map and label, and adds audit noise with no user control behind it.
- **OD-2 — Persisted pending expiry for users with notifications denied (F8).**
  - **Recommended (A):** include it in this bundle. Apply the B3 pattern to `WarrantyExpirationWorker`: `requiresNotificationPermission=false`, always reconcile, check permission locally before reminders (INV-29, AE-51, guard allowlist += `WarrantyExpirationWorker.kt`).
    - Fixes the same AGENTS.md:165-167 violation and makes the stored state honest for every user.
    - Cost: one more worker under FG-11.
    - Shipped change: ACTIVE and PENDING rows of notification-denied users start becoming EXPIRED in the DB. The visible effect is nil for ACTIVE rows (already date-filtered in the UI).
  - (B) Defer to a follow-up. The UI is still correct through INV-10, but the DB keeps PENDING_REVIEW until a run with permission.

Stop-and-ask sign-offs (not new decisions, already approved in principle): §8.1 deletions (5 tests, including the 3 non-≥0.75 band tests); §8.2 retargets; the §8.3 guard allowlist amendment.

---

## 15. Adjacent findings (out of scope; for impact-mapper or follow-ups)
1. `WarrantyCreationResult.Failure` for < 0.40 confidence and ineligible document types maps to `SideEffectOutcome.FailedRetryable` (`ReceiptSideEffectPlanner.kt:216-219`). These are pointless retries of deterministic outcomes.
2. `addWarrantyIgnoreConflicts` writes the receipt event `AI_WARRANTY_CREATED` (actor `system:ai_warranty_extraction`) for **regex** auto-detected warranties (`Repo:169-205`). This label is misleading for internal diagnostics. The same applies to `AI_EXTRACTION_DISCARDED` on user reject.
3. The `DashboardFollowThroughEngine.kt:150` fallback text hard-codes "€" and uses default-locale `String.format`, whatever the expense currency.
4. `PrivacyRuntimeWorkerPolicy:60-63` gates `warranty_expiration_check` on NOTIFICATION_CAPTURE. That gating is effectively undone at the next startup `scheduleAll`, and the worker is not notification-derived.
5. A 1 ms boundary inconsistency: `getActiveWarranties` uses `end > now` while `markExpiredWarranties` uses `end < now`.
6. Router and runtime strings (`DefaultAiCapabilityRouter`, `GetAiRuntimeStatusUseCase`) are bounded but unlocalized English.
7. `createWarrantyForReview` / `promoteReviewDraft` (`AutoCreate…:160-180,339-378`) have no production caller.
8. A recommendation `sourceArtifactId` becomes `"0"` for a non-persisted insight artifact (`DashboardFollowThroughEngine.kt:172`).
9. UX handoff:
   - The `warrantyExtractionEnabled` toggle (`AiSettingsScreen.kt:246`) controls nothing after B1. Hide it, or show it as "not active".
   - Copy and placement of the new insight toggle.
   - Expired-unconfirmed display and actions (confirm/delete; no claim).
