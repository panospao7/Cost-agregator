# Pipeline 8 — Privacy / AI / Redaction evaluation

## Executive verdict

My status call is:

- **1 tracker item is clearly fixed**
- **2 tracker TODOs are now stale in a positive way**
- **3–4 areas are materially improved beyond the old debug report**
- **but the core privacy contract is still split and inconsistent**
- so I would rate Pipeline 8 as:

> **“substantially improved, but still partial and not closure-ready.”**

The main remaining problem is still architectural:

> **PrivacySettings and AiSettings are still separate sources of truth, and enforcement is still not fully uniform.**

---

## What is genuinely better now

These are real improvements at HEAD:

### 1. Runtime cancellation on privacy change is real
`PrivacySettingsRepositoryImpl.applyPrivacyChange()` now cancels:
- `ai_daily_briefing`
- `location_backfill`
- notification-dependent jobs like `data_retention`, `receipt_matching`, `warranty_expiration_check`, `bill_reminder_periodic`

So **P8-P1-01 is genuinely fixed**.

### 2. Gate composition is stronger than the tracker/debug report implied
`CompositePrivacyGate` now:
- treats `NotApplicable` distinctly
- catches gate exceptions
- converts them into `FailClosed`
- logs only the **final** decision once

That is a meaningful improvement in both safety and audit semantics.

### 3. Audit context handling is safer than the tracker says
`PrivacyAuditLoggerImpl` no longer stores arbitrary context blindly.
It now allowlists a small set of keys:
- `operation`
- `caller`
- `entityType/entityId`
- `provider`
- `modelId`
- `payloadHash`
- `receiptId`

and drops long values.

So **P8-P1-04 is not “TODO only” anymore**.

### 4. Cloud providers are more self-gated than the old report said
At HEAD I verified cloud-side privacy checks in:
- `CloudDashboardBriefingService`
- `CloudReviewExplanationService`
- `CloudDedupeJudgeService`
- `CloudQueryInterpretationService`
- `CloudCategorizationAssistService`

So the old report’s “only some providers self-check” picture is stale.

---

## Why I still would not call Pipeline 8 clean/stable

## 1. The settings split is still real
This is still the biggest open issue.

Evidence:
- `PrivacySettings` still owns `cloudAiEnabled`, `redactBeforeCloud`, `receiptImageCloudEnabled`, `bankStatementAiEnabled`
- `AiSettings` still separately owns `allowCloudAi`, `redactBeforeCloud`, `receiptImageCloudEnabled`, `preferredMode`, capability flags
- `CloudAiPrivacyGate` still has an explicit TODO to use an effective policy resolver combining both settings systems

And enforcement is inconsistent:
- `HybridCategorizationAssistService` and `HybridDashboardBriefingService` still route using `AiSettingsRepository + AiCapabilityRouter`
- `CloudDashboardBriefingService` checks both `allowCloudAi` and `privacyGate`
- but `CloudReviewExplanationService`, `CloudDedupeJudgeService`, and `CloudCategorizationAssistService` appear to rely on `privacyGate` only, while still reading AI settings for redaction behavior

So if the two settings models disagree, behavior is still not uniformly defined.

**Call:** **P8-P1-02 still OPEN**

---

## 2. Audit semantics are better, but not fully complete
The tracker says audit logging is noisy / imprecise.
That is now only **partly true**.

What improved:
- final audit logging is centralized in `CompositePrivacyGate`
- `FailClosed` is a real decision type
- context is sanitized

What still looks incomplete:
- I did not see a universal “cloud attempt audit” contract carrying provider/route/result consistently across all cloud services
- denial/route/result visibility is still fragmented between gate audit, provider logs, and service return types

**Call:** **P8-P1-03 is now PARTIAL+, tracker stale**

---

## 3. Raw-storage policy is better, but still incomplete
`RawStorageMode` and `RawContentSanitizer` are real.
Write-time sanitization exists for OCR and email subject/sender.

But I would still not call this closed because:
- `PrivacySettings` / `PrivacySettingsRepositoryImpl` still have `rawNotificationStorageMode` and `rawOcrStorageMode` only
- I did **not** see a dedicated `rawEmailStorageMode`
- from the Pipeline 3 review, email message IDs still persist raw in the email receipt path

So the privacy contract improved, but **email is still not fully folded into it**.

**Call:** **P8-P1-05 still PARTIAL**

---

## 4. Retention scope is still definitely incomplete
`DataRetentionWorker` is now guarded by `WorkerExecutionGuard.runGuarded()`, which is good.

But its actual purge scope is still:
- raw notifications
- raw OCR text

And the file still carries an explicit TODO to expand to:
- AI artifacts
- AI chat messages
- debug diagnostics
- email receipt sources

So this remains clearly open.

**Call:** **P8-P1-06 OPEN**

---

## 5. Notification privacy timing is improved, but not fully closed
This tracker row is stale in a positive direction.

What is real now:
- `NotificationCaptureService` observes privacy settings into a cached `capturePrivacyDenied`
- `onNotificationPosted()` does a **fast pre-extraction deny check** before reading notification text
- that is a real fix

But it is still not fully clean because:
- `processNotificationBypassDedupe()` still extracts extras/text before the async gate check
- if privacy settings observation fails, it logs and sets `capturePrivacyDenied = false` (fail-open)

So:
- posted-notification path = much better
- refresh/bypass path = still not fully privacy-first

**Call:** **P8-P1-09 is PARTIAL+, tracker stale**

---

## 6. Backup/export privacy is still not fully closed
`BackupPrivacyGate` now blocks `RAWBACKUP_EXPORT` **when encrypted backups are enabled**.

That is better than the older report.

But the logic still means:
- if the user disables encrypted backups, plaintext backup export becomes allowed

So plaintext export is still conceptually reachable, not hard-removed.

**Call:** **P8-P1-11 still OPEN in substance**

---

## 7. Denied-state UX is still inconsistent
This is one of the clearer remaining UX/API contract problems.

I found inconsistent denied/failure surfaces:
- `CloudDashboardBriefingService` returns `AiServiceResult.Failure(Disabled(...))`
- `CloudCategorizationAssistService` returns `null` on gate denial
- `CloudQueryInterpretationService` uses its own unsupported/failure path

That means the app still lacks one consistent “privacy denied” outcome model across providers.

**Call:** **P8-P1-12 OPEN**

---

## Lower-confidence / still-open areas

### P8-P1-07 — bank-statement cloud text redaction
I did **not** independently re-verify the exact current bank-statement text path in this pass, so I would leave this **open / lower confidence** rather than overclaim.

### P8-P1-08 — no formal purpose-aware payload contract
I still think this is open.
Reason:
- `CloudQueryInterpretationService` now does purpose-tagged redaction before sending prompt text, which is good
- but the wider system still relies on provider-local `shouldRedact` decisions and per-service request-body logic
- I did not see a unified domain contract like `PreparedCloudPayload`

So this area is improved, but still not formalized.

### P8-P1-10 — geocoding/location coverage
`LocationPrivacyGate` exists and handles:
- `EXTERNAL_GEOCODING`
- `BACKGROUND_LOCATION_BACKFILL`

But I did **not** audit every outbound geocoding/network path, so I cannot certify static/global coverage.

**Call:** **partial / unproven**

---

## Final scorecard

- **P8-P1-01 privacy change cancels workers:** **✅ FIXED**
- **P8-P1-02 PrivacySettings vs AiSettings drift:** **⚠ OPEN**
- **P8-P1-03 audit semantics noisy:** **⚠ PARTIAL+, tracker stale**
- **P8-P1-04 audit context stores arbitrary sensitive data:** **✅/⚠ largely fixed, tracker stale**
- **P8-P1-05 raw data stored first, purged later:** **⚠ PARTIAL**
- **P8-P1-06 retention scope incomplete:** **⚠ OPEN**
- **P8-P1-07 bank-statement cloud text redaction:** **⚠ OPEN / lower confidence**
- **P8-P1-08 no formal purpose-aware payload contract:** **⚠ OPEN**
- **P8-P1-09 notification gate too late/runtime state not cached:** **⚠ PARTIAL+, tracker stale**
- **P8-P1-10 geocoding coverage not statically guaranteed:** **⚠ PARTIAL / unproven**
- **P8-P1-11 raw backup/export reachable:** **⚠ OPEN**
- **P8-P1-12 denied privacy states inconsistent:** **⚠ OPEN**

---

## Bottom line

### Are Pipeline 8 issues fixed?
**Some are, yes.**  
There is real progress in:
- runtime worker cancellation
- fail-closed gate composition
- audit context sanitization
- provider self-gating
- write-time raw storage handling

### Are they clean and stable?
**No.**

Best summary:

> **Pipeline 8 has a much better privacy framework than before, but the app still has split privacy/AI settings, incomplete retention closure, inconsistent denied-state behavior, and only partial notification/raw-storage closure, so I would not declare it clean or stable yet.**

## Sources
- Master tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Pipeline 8 debug report:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/debugging/pipeline-8-privacy-ai-redaction-debug-report.md
- `PrivacySettingsRepositoryImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
- `PrivacySettings.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt
- `AiSettingsRepositoryImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt
- `AiModels.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt
- `CompositePrivacyGate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt
- `CloudAiPrivacyGate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt
- `PrivacyAuditLoggerImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt
- `RawContentSanitizer.kt` / `RawStorageMode.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawContentSanitizer.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawStorageMode.kt
- `DataRetentionWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt
- `NotificationCaptureService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `BackupPrivacyGate.kt` / `LocationPrivacyGate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt
- Sample AI providers/hybrids:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt

## Scope note
Static code/doc review only. I did **not** run Gradle, tests, or the app.