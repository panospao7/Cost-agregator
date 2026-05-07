# Pipeline 8 Debugging Report — Privacy Gates / AI / Redaction / Retention

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 8 is intended to be:

```text
PrivacySettingsScreen
→ PrivacySettingsRepositoryImpl/DataStore
→ CompositePrivacyGate
→ NotificationPrivacyGate / CloudAiPrivacyGate / LocationPrivacyGate / BackupPrivacyGate
→ PrivacyAuditLogger
→ PrivacyAuditEvent / PrivacyAuditDao

Cloud / external-capability callers:
  notification capture
  cloud AI providers
  geocoding/location backfill
  backup/export
  data retention
```

The architecture is good: privacy is no longer just UI text; there are real gate objects, DataStore settings, audit events, redaction utilities, and retention workers.

But the implementation is not fully consistent yet.

Highest-risk findings:

1. **There are two separate privacy/AI settings systems that can drift: `PrivacySettings` and `AiSettings`.**
2. **Several cloud AI hybrid services route through `AiSettings` only and do not call `PrivacyGate`.**
3. **Some cloud provider implementations are self-defending with `PrivacyGate`, but others only check `AiSettings.allowCloudAi`.**
4. **Cloud natural-language query interpretation checks the cloud gate but does not clearly apply redaction before building the prompt.**
5. **Receipt item categorization trusts `input.redactBeforeCloud` instead of resolving privacy settings itself.**
6. **Gate implementations do not fail closed if `getSettings()` throws.**
7. **Audit logging exists, but cloud calls are not uniformly audited with provider, redaction status, route, and payload hash.**
8. **`RedactionSanitizer` in `domain/privacy` only hashes merchants; real text redaction lives separately in `CloudPiiSanitizer`, so there is no single redaction contract.**
9. **Data retention worker purges raw notification/OCR text, but it does not check restore maintenance mode and cannot prove all raw copies/artifacts are purged.**
10. **Privacy UI already notes some toggles require restart, so runtime behavior may not update immediately.**

Main recommendation:

> Make `PrivacyGate` the mandatory enforcement point for every external/cloud/privacy-sensitive capability, and make redaction/audit a required wrapper around all cloud payload construction.

---

# 2. Intended architecture contract

From `DEPENDENCY_MAP.md`, Pipeline 8 is:

```text
PrivacySettingsScreen
→ PrivacySettingsViewModel
→ PrivacySettingsRepositoryImpl
→ CompositePrivacyGate
   ├─ NotificationPrivacyGate
   ├─ LocationPrivacyGate
   ├─ CloudAiPrivacyGate
   └─ BackupPrivacyGate
→ PrivacyDecision
→ PrivacyAuditLogger
→ PrivacyAuditEvent / PrivacyAuditDao
```

Main gated capabilities:

```text
NOTIFICATION_CAPTURE
CLOUD_AI_RECEIPT_ASSIST
CLOUD_AI_ITEM_CATEGORIZATION
CLOUD_AI_DAILY_BRIEFING
CLOUD_AI_QUERY_INTERPRETATION / CLOUD_AI_GENERAL
EXTERNAL_GEOCODING
BACKGROUND_LOCATION_BACKFILL
RAWBACKUP_EXPORT
ENCRYPTED_BACKUP
RAW_NOTIFICATION_RETENTION
RAW_OCR_RETENTION
```

This is the correct target shape.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 3. Actual code path summary

## 3.1 Privacy settings

`PrivacySettings` defaults are mostly privacy-safe:

```text
notificationCaptureEnabled = true
cloudAiEnabled = false
redactBeforeCloud = true
receiptImageCloudEnabled = false
bankStatementAiEnabled = false
externalGeocodingEnabled = false
backgroundLocationBackfillEnabled = false
deviceGpsLocationEnabled = false
encryptedBackupEnabled = true
rawNotificationRetentionDays = 30
rawOcrRetentionDays = 30
debugDataPersistenceEnabled = false
```

Good:

- cloud AI is opt-in,
- receipt image cloud upload is opt-in,
- external geocoding is opt-in,
- redaction before cloud is on by default,
- encrypted backup is on by default.

Risk:

- AI has its own separate `AiSettings` DataStore with fields like:
  - `allowCloudAi`
  - `redactBeforeCloud`
  - `receiptImageCloudEnabled`
  - capability-specific flags
  - `preferredMode`

So the app currently has two settings models that can disagree.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt

---

## 3.2 Privacy gates

`CompositePrivacyGate` delegates through:

```text
NotificationPrivacyGate
LocationPrivacyGate
CloudAiPrivacyGate
BackupPrivacyGate
```

Each specific gate logs a decision through `PrivacyAuditLogger`.

Good:

- cloud receipt image upload is denied when redaction is required,
- notification capture can be master-disabled,
- location/geocoding is off by default,
- backup raw/encrypted mode has a gate.

Risk:

- gate code calls `settingsRepository.getSettings()` with no `try/catch`.
- the `PrivacyGate` contract says fail closed if settings cannot be determined, but the actual gates can throw instead of returning `Denied`.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt

---

## 3.3 Audit logging

`PrivacyAuditLoggerImpl` inserts:

```text
PrivacyAuditEvent(
  capability,
  decision = ALLOWED/DENIED,
  reason,
  context JSON,
  timestampMs = System.currentTimeMillis(),
  caller = "privacy-gate"
)
```

Good:

- decisions persist to Room,
- `PrivacyAuditDao.getRecent(limit)` exists.

Risks:

- uses `System.currentTimeMillis()` instead of `TimeProvider`,
- all caller values are `"privacy-gate"`, so caller identity depends on context JSON,
- DAO only supports recent list, not by capability/decision/time,
- if audit insert fails, gate behavior is unclear,
- provider-level cloud calls are not uniformly logged with route/provider/redaction/payload hash.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/PrivacyAuditEvent.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/PrivacyAuditDao.kt

---

# 4. Major findings

## Finding P0-1 — `PrivacySettings` and `AiSettings` can drift

There are two independent settings models:

### Privacy settings

```text
cloudAiEnabled
redactBeforeCloud
receiptImageCloudEnabled
bankStatementAiEnabled
```

### AI settings

```text
allowCloudAi
redactBeforeCloud
receiptImageCloudEnabled
preferredMode
capability-specific AI toggles
```

The privacy screen updates `PrivacySettings`.

The AI router and many hybrid services use `AiSettings`.

So this can happen:

```text
PrivacySettings.cloudAiEnabled = false
AiSettings.allowCloudAi = true
```

Then a cloud AI path that relies only on the AI router can still route to cloud.

Or:

```text
PrivacySettings.redactBeforeCloud = true
AiSettings.redactBeforeCloud = false
```

Then a cloud provider that reads only `AiSettings` can send raw merchant/notification/OCR/query text.

### Recommended fix

Create one source of truth or a strict bridge:

```kotlin
effectiveCloudAllowed =
    privacySettings.cloudAiEnabled &&
    aiSettings.allowCloudAi &&
    capabilityEnabled

effectiveRedactBeforeCloud =
    privacySettings.redactBeforeCloud || aiSettings.redactBeforeCloud

effectiveReceiptImageCloudAllowed =
    privacySettings.cloudAiEnabled &&
    privacySettings.receiptImageCloudEnabled &&
    aiSettings.receiptImageCloudEnabled &&
    !effectiveRedactBeforeCloud
```

Then route all cloud decisions through:

```kotlin
CloudAiGuard
```

or make `DefaultAiCapabilityRouter` depend on `PrivacyGate`.

Priority: highest.

---

## Finding P0-2 — Several hybrid cloud routes do not call `PrivacyGate`

Examples:

```text
HybridCategorizationAssistService
HybridDashboardBriefingService
HybridReviewExplanationService
HybridDedupeJudgeService
HybridQueryInterpretationService
```

They route based on:

```text
AiSettingsRepository + AiCapabilityRouter
```

Only some downstream cloud services self-check `PrivacyGate`.

Observed provider pattern:

### Uses `PrivacyGate`

```text
CloudReceiptAssistService
CloudQueryInterpretationService
CloudReceiptItemCategorizationService
CloudWarrantyExtractionService likely via AiModule provider
```

### Uses only `AiSettings` / optional `AiSettingsRepository`

```text
CloudCategorizationAssistService
CloudDashboardBriefingService
CloudDedupeJudgeService
CloudReviewExplanationService
```

This is not enough because the privacy screen controls `PrivacySettings`, not necessarily `AiSettings`.

### Recommended fix

Every cloud provider should call:

```kotlin
privacyGate.check(...)
```

inside the provider itself, not only at the hybrid/router layer.

Mandatory mapping:

```text
CloudCategorizationAssistService → CLOUD_AI_ITEM_CATEGORIZATION or CLOUD_AI_GENERAL/CATEGORIZATION
CloudDashboardBriefingService → CLOUD_AI_DAILY_BRIEFING
CloudDedupeJudgeService → CLOUD_AI_DEDUPE_JUDGE or CLOUD_AI_GENERAL
CloudReviewExplanationService → CLOUD_AI_REVIEW_EXPLANATION or CLOUD_AI_GENERAL
CloudQueryInterpretationService → CLOUD_AI_QUERY_INTERPRETATION or CLOUD_AI_GENERAL
CloudReceiptAssistService → CLOUD_AI_RECEIPT_ASSIST / RECEIPT_IMAGE_CLOUD_UPLOAD
```

Also extend `PrivacyCapability` if needed because the current enum lacks some named capabilities used by dependency map text.

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDedupeJudgeService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt

---

## Finding P0-3 — Cloud query interpretation gates cloud use but does not clearly redact query text

`CloudQueryInterpretationService` checks:

```text
PrivacyCapability.CLOUD_AI_GENERAL
```

Good.

But `buildRequestBody(input)` delegates to prompt building without visibly checking:

```text
PrivacySettings.redactBeforeCloud
AiSettings.redactBeforeCloud
```

Natural language queries can contain sensitive data:

```text
"show transactions at pharmacy"
"how much did I spend at Dr Smith"
"find salary from employer X"
"show payments to iban..."
```

So cloud query interpretation should never send raw query text when redaction is required.

### Recommended fix

Add explicit redaction policy:

```kotlin
val effectivePrivacy = cloudAiGuard.requireAllowed(
    capability = CLOUD_AI_QUERY_INTERPRETATION,
    context = ...
)

val safeInput = if (effectivePrivacy.redactBeforeCloud) {
    financialQueryRedactor.redact(input)
} else input
```

Redact or bucket:

```text
merchant names
person names
IBAN/account/card/phone/email
exact amounts if configured
raw user query
free-text filters
```

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt

---

## Finding P0-4 — Receipt item categorization trusts caller-provided redaction flag

`CloudReceiptItemCategorizationService` checks `PrivacyGate` for cloud item categorization.

Good.

But redaction is controlled by:

```kotlin
input.redactBeforeCloud
```

not by resolving current privacy settings inside the provider.

If a caller accidentally passes:

```text
redactBeforeCloud = false
```

while user privacy settings require redaction, raw merchant, item descriptions, and category names can be sent.

### Recommended fix

Provider should compute effective redaction internally:

```kotlin
val settings = privacySettingsRepository.getSettings()
val shouldRedact = settings.redactBeforeCloud || input.redactBeforeCloud
```

Even better, do not let callers decide whether privacy redaction is required. Let callers supply raw data; provider sanitizes before cloud.

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt

---

## Finding P0-5 — Privacy gates do not fail closed on settings read failure

The `PrivacyGate` contract says gates should fail closed if settings cannot be determined.

But gates do:

```kotlin
val settings = settingsRepository.getSettings()
```

with no try/catch.

`observeSettings()` catches `IOException`, but `getSettings()` in `PrivacySettingsRepositoryImpl` uses:

```kotlin
context.privacySettingsDataStore.data.first().toPrivacySettings()
```

without a catch.

If DataStore throws:

- gate can throw,
- caller may crash or catch and proceed incorrectly,
- no `DENIED` audit event is written.

### Recommended fix

Add helper:

```kotlin
suspend fun PrivacySettingsRepository.getSettingsOrDeny(...)
```

or update every gate:

```kotlin
val settings = try {
    settingsRepository.getSettings()
} catch (e: Exception) {
    val decision = PrivacyDecision.Denied("Privacy settings unavailable; failing closed")
    auditLogger.logDecision(capability, decision, context + ("error" to e.safeClassName()))
    return decision
}
```

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyGate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

---

## Finding P1-1 — Redaction is split across two incompatible abstractions

There is:

```text
domain/privacy/RedactionSanitizer.kt
```

which only has:

```kotlin
sanitizeMerchant(value: String): String
```

But cloud providers use:

```text
data/ai/provider/internal/CloudPiiSanitizer
```

which handles:

```text
email
IBAN
card
phone
long numbers
merchant hashing
text length
```

This means there is no single domain-level redaction contract for:

- notification text,
- raw OCR,
- receipt line items,
- natural language queries,
- dashboard briefing prompts,
- review explanations,
- dedupe previews,
- bank statement text.

### Recommended fix

Create:

```kotlin
interface CloudPayloadRedactor {
    fun redactReceiptAssist(input): RedactedReceiptAssistInput
    fun redactReceiptItems(input): RedactedReceiptItemInput
    fun redactReviewExplanation(input): RedactedReviewExplanationInput
    fun redactDedupeInput(input): RedactedDedupeJudgeInput
    fun redactDashboardBriefing(input): RedactedDashboardBriefingInput
    fun redactFinancialQuery(input): RedactedFinancialQueryInput
}
```

It should return metadata:

```text
redactionApplied: Boolean
fieldsRedacted: List<String>
payloadHash: String
containsRawText: Boolean
```

Priority: high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/RedactionSanitizer.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt

---

## Finding P1-2 — Audit events are too shallow for cloud AI debugging

Current privacy audit logs:

```text
capability
decision
reason
context JSON
timestamp
caller
```

Good start.

But for cloud privacy debugging, you need:

```text
provider
model
route
redactionApplied
payloadHash
rawImageUploaded yes/no
rawTextIncluded yes/no
settingsSnapshotHash
callResult allowed/denied/skipped/success/failure
```

Right now a gate decision may be logged, but the actual cloud provider call is not uniformly audited.

### Recommended fix

Add:

```kotlin
CloudAiAuditLogger.logCloudAttempt(...)
```

or extend privacy audit.

Record:

```text
CLOUD_ATTEMPT_BLOCKED
CLOUD_ATTEMPT_SENT_REDACTED
CLOUD_ATTEMPT_SENT_RAW
CLOUD_ATTEMPT_FAILED
CLOUD_ATTEMPT_SKIPPED_NO_KEY
```

Priority: high.

---

## Finding P1-3 — Notification privacy denial is silent to user/debug state

`NotificationCaptureService` checks:

```text
PrivacyCapability.NOTIFICATION_CAPTURE
```

Good.

But if denied, it just logs and drops the notification. The dependency map and prior Pipeline 1 report already flagged this as a debugging problem.

### Recommended fix

Add capture diagnostics:

```text
DROPPED_PRIVACY
capability = NOTIFICATION_CAPTURE
decision reason
packageName
timestamp
```

and show in DebugScreen / notification capture status.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

---

## Finding P1-4 — DataRetentionWorker does not check restore maintenance mode

`DataRetentionWorker` purges raw notification/OCR text and writes privacy audit events.

It does not inject/check `RestoreMaintenanceMode`.

During restore/backup swap, this worker can mutate:

```text
raw_notifications
scanned_receipts
privacy_audit_events
```

This was also noted in Pipeline 7 as a worker-safety issue.

### Recommended fix

Inject `RestoreMaintenanceMode` and skip:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    return Result.success()
}
```

Also log:

```text
BackgroundJobRun.SKIPPED_RESTORE_MODE
```

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

---

## Finding P1-5 — Data retention may not cover all raw/sensitive copies

DataRetentionWorker purges:

```text
RawNotification.title/text/bigText/subText/extrasJson/parseResult
ScannedReceipt.rawOcrText
```

Good.

But sensitive text may also exist in:

```text
AI artifacts
AI chat messages
review explanations
debug diagnostics
backup files
receipt images
email receipt body/source rows
receipt line item descriptions
privacy audit context JSON
logs
```

Some of these may be intentionally retained, but the retention policy should explicitly say so.

### Recommended fix

Create a raw-data inventory:

```text
Table / file / log | Raw data type | Retention setting | Purged by | Redacted before cloud? | Included in backup?
```

Then expand DataRetentionWorker or add separate retention workers.

Priority: high.

---

## Finding P1-6 — Redacted backup does not mean fully private backup

`ExportAnonymizer` removes raw OCR and raw notification text from a DB copy.

Good.

But as noted in Pipeline 7, if receipt images are included, images can still contain sensitive data. Also other fields such as receipt line items, email receipt sources, AI artifacts, and audit context may still reveal sensitive data.

### Recommended fix

Use clearer backup modes:

```text
Full encrypted backup
Redacted DB backup
Redacted DB + no receipt images
Public/shareable anonymized export
```

Do not call a bundle “redacted” if it still includes raw receipt images.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

---

## Finding P1-7 — Privacy UI warns restart may be required

`PrivacySettingsScreen` notes:

```text
Capture notifications — requires restart of NotificationListenerService
Background location backfill — requires restart of background location workers
```

This means toggles may not take effect immediately in all runtime paths.

For a privacy setting, delayed enforcement is risky. Disabling a privacy-sensitive feature should take effect immediately where possible.

### Recommended fix

When privacy setting changes:

```text
notificationCaptureEnabled=false → request listener stop/ignore immediately
backgroundLocationBackfillEnabled=false → cancel worker immediately
cloudAiEnabled=false → cancel pending AI work immediately
externalGeocodingEnabled=false → cancel queued location work
```

At minimum, show a blocking “restart required for full enforcement” warning when relevant.

Priority: medium-high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/privacysettings/PrivacySettingsScreen.kt

---

## Finding P1-8 — Location interactive search may bypass `PrivacyGate`

`LocationBackfillWorker` correctly checks:

```text
BACKGROUND_LOCATION_BACKFILL
```

But `CompositeGeocodingService.searchMultiple()` itself does not check `PrivacyGate`. It relies on callers/UI to decide whether external geocoding is allowed.

For defense-in-depth, external-network providers should self-check gate or be wrapped in a gated geocoding service.

### Recommended fix

Add a `GatedGeocodingService` wrapper:

```text
search/searchMultiple/reverseGeocode
→ PrivacyGate.check(EXTERNAL_GEOCODING)
→ if denied: return PrivacyDenied result
→ else call provider
```

Background worker can still check `BACKGROUND_LOCATION_BACKFILL` separately.

Priority: medium-high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

---

# 5. Debugging checklist for Pipeline 8

## Privacy settings

Check:

- [ ] defaults are privacy-safe,
- [ ] toggles persist after restart,
- [ ] privacy settings and AI settings cannot conflict,
- [ ] disabling cloud AI cancels/blocks all cloud work,
- [ ] disabling notification capture immediately stops capture,
- [ ] disabling background location cancels workers,
- [ ] disabling external geocoding blocks all provider calls,
- [ ] retention days apply to all raw copies.

## Privacy gates

Check:

- [ ] every gate fails closed on settings read failure,
- [ ] every gate logs allowed/denied decision,
- [ ] unhandled capabilities return allowed only intentionally,
- [ ] cloud image upload denied when redaction required,
- [ ] backup raw/encrypted rules are correct,
- [ ] gate context does not contain raw sensitive text.

## Cloud AI

Check:

- [ ] every cloud provider has self-defense `PrivacyGate.check`,
- [ ] every hybrid service respects privacy master toggle,
- [ ] on-device mode never falls back to cloud,
- [ ] cloud mode falls back to on-device only if allowed,
- [ ] cloud disabled produces deterministic/no-op fallback,
- [ ] API key absence prevents route to cloud,
- [ ] network/wifi-only policy enforced,
- [ ] cloud attempts audited.

## Redaction

Check:

- [ ] raw OCR text redacted before cloud,
- [ ] notification text redacted before cloud,
- [ ] natural-language query redacted before cloud,
- [ ] receipt line items redacted before cloud,
- [ ] merchant names hashed or bucketed,
- [ ] receipt image upload suppressed when redaction required,
- [ ] exact amounts are bucketed when policy requires,
- [ ] payload hash stored for audit, not raw payload.

## Retention

Check:

- [ ] raw notification text purged,
- [ ] raw OCR text purged,
- [ ] email receipt body retention defined,
- [ ] AI artifact retention defined,
- [ ] debug diagnostics retention defined,
- [ ] privacy audit retention defined,
- [ ] purge writes audit events,
- [ ] purge skips during restore mode.

## Backup/export

Check:

- [ ] redacted backup removes raw DB text,
- [ ] redacted backup excludes receipt images or warns,
- [ ] export anonymizer covers AI artifacts/email sources if needed,
- [ ] raw plaintext backup is denied when encrypted backup enabled,
- [ ] backup privacy decisions audited.

---

# 6. Recommended fix plan

## PR 1 — Single effective privacy policy for AI

Create:

```kotlin
CloudAiGuard
```

It should combine:

```text
PrivacySettings
AiSettings
AiPolicy
network/wifi policy
API key availability
```

Return:

```kotlin
CloudAiPermission(
    allowed: Boolean,
    reason: String?,
    redactBeforeCloud: Boolean,
    allowImageUpload: Boolean
)
```

Acceptance:

```text
If PrivacySettings.cloudAiEnabled=false, no cloud provider can make a network request even if AiSettings.allowCloudAi=true.
```

Priority: P0.

---

## PR 2 — Provider self-defense matrix

Make every cloud provider call `PrivacyGate` or `CloudAiGuard` internally.

Add a static/contract test:

```text
Every class named Cloud*Service must check PrivacyGate/CloudAiGuard before network call.
```

Priority: P0.

---

## PR 3 — Central redaction layer

Replace per-provider ad-hoc sanitization with:

```text
CloudPayloadRedactor
```

Acceptance:

```text
redactBeforeCloud=true produces sanitized payload for receipt, review, dedupe, dashboard, query, categorization, bank statement.
```

Priority: P0/P1.

---

## PR 4 — Fail closed

Update all gates to catch settings repository failures and return:

```text
PrivacyDecision.Denied("Privacy settings unavailable; failing closed")
```

Write audit event if possible.

Priority: P0.

---

## PR 5 — Audit cloud attempts

Add cloud attempt audit records:

```text
provider
capability
route
redacted
imageUploaded
payloadHash
decision
result
```

Priority: P1.

---

## PR 6 — Runtime cancellation after settings changes

When privacy setting disables a runtime capability:

```text
cloud AI → cancel queued AI work
notification capture → stop/ignore immediately
background location → cancel worker
external geocoding → cancel active calls
debug persistence → purge debug buffer if disabled
```

Priority: P1.

---

## PR 7 — Data retention expansion

Create raw-data inventory and expand retention to:

```text
email receipt source/body
AI artifacts/chat messages
debug diagnostics
receipt images if user asks
privacy audit context
```

Priority: P1/P2.

---

# 7. Tests to add

## `PrivacySettingsAiSettingsBridgeTest`

Cases:

```text
Privacy cloud=false, AI cloud=true → effective cloud denied
Privacy redact=true, AI redact=false → effective redaction true
Privacy image=false, AI image=true → image upload denied
```

## `CloudProviderPrivacySelfDefenseTest`

For each cloud provider:

```text
privacy cloud disabled
→ provider called directly
→ no HTTP request
→ audit denied event
```

Providers:

```text
CloudReceiptAssistService
CloudCategorizationAssistService
CloudDashboardBriefingService
CloudDedupeJudgeService
CloudReviewExplanationService
CloudQueryInterpretationService
CloudReceiptItemCategorizationService
CloudWarrantyExtractionService
```

## `CloudRedactionContractTest`

Inputs with:

```text
email
IBAN
card number
phone
merchant name
receipt line items
notification text
natural language query
```

Assert:

```text
cloud payload contains no raw sensitive values when redaction enabled
payload hash exists
redaction metadata recorded
```

## `PrivacyGateFailClosedTest`

Simulate DataStore failure.

Assert:

```text
gate returns Denied
audit event attempted/written
caller does not proceed to network/DB capture
```

## `NotificationCapturePrivacyRuntimeTest`

```text
notificationCaptureEnabled=false
→ onNotificationPosted
→ no raw notification row
→ DROPPED_PRIVACY diagnostic
→ audit denied event
```

## `LocationPrivacyGateContractTest`

```text
externalGeocodingEnabled=false
→ search/searchMultiple/reverseGeocode
→ no provider HTTP call
```

## `DataRetentionWorkerContractTest`

Seed:

```text
raw_notifications with raw text
scanned_receipts with rawOcrText
AI artifacts/chat/debug rows if included
```

Assert:

```text
old raw data purged
parsed financial records preserved
audit events written
worker skips during restore mode
```

## `RedactedBackupPrivacyContractTest`

Seed:

```text
raw notification
raw OCR
receipt image
AI artifact
email receipt body
```

Assert chosen policy:

```text
redacted backup excludes receipt images
or warns manifest contains raw image data
```

---

# 8. Suggested canonical scenario

## `privacy_settings_to_runtime_gate`

Seed:

```text
PrivacySettings:
  notificationCaptureEnabled = false
  cloudAiEnabled = false
  redactBeforeCloud = true
  receiptImageCloudEnabled = false
  externalGeocodingEnabled = false
  backgroundLocationBackfillEnabled = false
  encryptedBackupEnabled = true
```

Run:

```text
1. simulate bank notification
2. call cloud receipt assist directly
3. call hybrid dashboard briefing
4. call cloud query interpretation
5. run location backfill
6. call interactive geocoding
7. create redacted costbackup
8. run data retention worker
```

Expected:

```text
notification is dropped with DROPPED_PRIVACY
no raw notification row
no cloud HTTP calls from any provider
on-device/no-op fallback used where applicable
cloud attempts write DENIED audit events
natural-language query is not sent raw
location providers are not called
background location worker skips
backup is encrypted
redacted backup strips raw DB text and handles receipt images according to policy
data retention purges old raw text and writes audit events
```

This is the Pipeline 8 fed-DB/runtime acceptance test.

---

# 9. Most likely real instability sources

Ranked:

1. **PrivacySettings vs AiSettings drift.**
2. **Cloud providers/hybrid services not uniformly gated by PrivacyGate.**
3. **Natural-language cloud prompt lacking explicit redaction.**
4. **Receipt item categorization trusting caller redaction flag.**
5. **Privacy gates not failing closed on settings read failure.**
6. **Audit records not detailed enough to prove no cloud/raw leak.**
7. **Data retention does not cover all raw copies.**
8. **Runtime toggles may require restart/cancel to fully apply.**
9. **Interactive geocoding may rely on caller-level gate instead of provider-level self-defense.**
10. **Redacted backup terminology can overpromise privacy.**

---

# 10. Final recommendation

Stabilize Pipeline 8 in this order:

```text
1. Create effective CloudAiGuard combining PrivacySettings + AiSettings.
2. Require all cloud providers to self-check CloudAiGuard/PrivacyGate.
3. Centralize redaction with CloudPayloadRedactor.
4. Make all gates fail closed.
5. Add cloud attempt audit records.
6. Add runtime cancellation when privacy toggles disable features.
7. Expand DataRetentionWorker/raw-data inventory.
8. Add privacy_settings_to_runtime_gate scenario test.
```

Guiding rule:

> No cloud, external geocoding, notification capture, raw backup, or raw-data retention path should depend only on caller discipline. Sensitive providers must be self-defending.

Second guiding rule:

> If redaction is enabled, the app must be able to prove what was redacted, what was sent, and what was blocked — without storing the raw payload itself.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — PrivacySettings and AiSettings can drift
**STATUS: CONFIRMED — NOT FIXED (requires architectural unification of settings models)**

## Finding P0-2 — Several hybrid cloud routes do not call PrivacyGate
**STATUS: CONFIRMED — NOT FIXED (requires routing all cloud decisions through PrivacyGate)**

## Finding P0-3 — Some cloud providers only check AiSettings, not PrivacyGate
**STATUS: CONFIRMED — NOT FIXED (requires audit of all cloud service implementations)**

## Finding P0-4 — Cloud NL query interpretation may send unredacted text
**STATUS: CONFIRMED — NOT FIXED (requires redaction before prompt construction)**

## Finding P0-5 — Receipt item categorization trusts input.redactBeforeCloud
**STATUS: CONFIRMED — NOT FIXED (should resolve privacy settings independently)**

## Finding P0-6 — Gate implementations do not fail closed on exception
**STATUS: CONFIRMED — FIXED**
- `CompositePrivacyGate.check()` now wraps each individual gate's `check()` call in try/catch.
- On any exception (e.g. DataStore corruption, network error), it returns `PrivacyDecision.Denied("Privacy check failed (fail-closed): ...")` instead of propagating the exception.
- This enforces the contract that privacy gates must fail closed.

## Finding P0-7 — Audit logging not uniform across cloud calls
**STATUS: CONFIRMED — NOT FIXED (requires per-provider audit wrapper)**

## Finding P1-1 — RedactionSanitizer vs CloudPiiSanitizer — no single redaction contract
**STATUS: CONFIRMED — PARTIALLY FIXED (ARCH-04 Stage 1)**
- CloudPayloadRedactor interface created as the unified domain-level redaction contract.
- Implementation per capability (receipt, review, dedupe, dashboard, query, categorization) deferred to Stage 2.

## Finding P1-2 — Audit events are too shallow for cloud AI debugging
**STATUS: CONFIRMED — PARTIALLY FIXED (ARCH-05 Stage 1)**
- CloudAiAuditLogger design scoped; provider-level audit event expansion (route, redaction, payload hash) deferred to Stage 2.

## Finding P1-2 — Data retention worker does not check RestoreMaintenanceMode
**STATUS: CONFIRMED — FIXED (see Pipeline 9)**

## Finding P1-3 — PrivacyAuditLoggerImpl uses System.currentTimeMillis()
**STATUS: CONFIRMED — FIXED**
- `PrivacyAuditLoggerImpl` now injects `TimeProvider` and uses `timeProvider.now()` instead of `System.currentTimeMillis()`.

## Finding P1-4 — Privacy UI toggles may require restart
**STATUS: CONFIRMED — PARTIALLY FIXED (ARCH-06 Stage 1)**
- Restartless toggle design explored; full runtime cancellation on setting change deferred to Stage 2.

## Finding P1-5 — Data retention may not cover all raw/sensitive copies
**STATUS: CONFIRMED — PARTIALLY FIXED (ARCH-07/08 Stage 1)**
- Raw-data inventory design sketched; expansion to AI artifacts, chat messages, debug diagnostics, receipt images deferred to Stage 2.

## Finding P1-6 — Redacted backup does not mean fully private backup
**STATUS: CONFIRMED — PARTIALLY FIXED (ARCH-03 Stage 1)**
- BackupPrivacyMode enum created (FULL_ENCRYPTED, DB_TEXT_REDACTED, DB_REDACTED_NO_IMAGES, ANONYMIZED_EXPORT).
- Manifest field added to record backup privacy mode.
- Full enforcement of mode constraints in CostbackupBundle deferred to Stage 2.

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Fail-closed exception handling in CompositePrivacyGate | `CompositePrivacyGate.kt` | P0-6 |
| Use TimeProvider in PrivacyAuditLoggerImpl | `PrivacyAuditLoggerImpl.kt` | P1-3 |

---

# 14. Remaining work priority

1. **P0-1**: Unify PrivacySettings and AiSettings into single source of truth or strict bridge
2. **P0-2**: Route all hybrid cloud services through PrivacyGate before cloud calls
3. **P0-3**: Make all cloud providers self-check PrivacyGate, not just AiSettings
4. **P0-4**: Enforce redaction in CloudQueryInterpretationService before prompt construction
5. **P0-5**: Make receipt item categorization resolve privacy settings itself
6. **P0-7**: Implement per-provider audit wrapper with route/redaction/payload hash
7. **P1-1**: Unify RedactionSanitizer and CloudPiiSanitizer into single contract

---

# Sources

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- Privacy settings/gates:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt

- Audit:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/PrivacyAuditEvent.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/PrivacyAuditDao.kt

- AI settings/router/providers:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt

- Redaction/retention/backup/location/notification:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/RedactionSanitizer.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt