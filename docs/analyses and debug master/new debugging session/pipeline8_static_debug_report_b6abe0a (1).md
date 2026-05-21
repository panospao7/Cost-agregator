# Pipeline 8 Static Debug Report — Privacy / AI / Redaction

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 8 is **substantially improved**, but it is **not closed**.

Good foundations now exist:

```text
PrivacyDecision.NotApplicable / FailClosed
CompositePrivacyGate final audit logging
EffectiveCloudAiPolicyResolver
RawStorageMode
RawContentSanitizer
CloudPayloadRedactor
DefaultCloudPayloadRedactor
PrivacyBlocked typed denied states
NotificationCaptureService fast pre-extraction settings cache
Runtime worker cancellation on PrivacySettings update
```

But important privacy guarantees are still incomplete.

Highest remaining user-impact risks:

1. **`privacy.redactBeforeCloud` can still be bypassed** because several cloud providers use `AiSettings.redactBeforeCloud` or caller-provided redaction flags instead of `EffectiveCloudAiPolicy.redactBeforeCloud`.
2. **Bank-statement `suggestFromText()` is only partially fixed**: it now redacts, but uses receipt purpose and only AI settings, not effective privacy policy.
3. **Raw notification/OCR/email storage mode is still partial**: raw content can still leak through downstream tables such as pending reviews/events, even if raw storage mode sanitizes the primary raw row.
4. **Privacy settings corruption is not truly fail-closed**: empty preferences still default notification capture to `true` and raw storage to `STORE_RAW`.
5. **Notification privacy pre-gate exists, but full `PrivacyGate` denial can still happen after extraction.**
6. **Test constructors with no-op allow-all gates remain in production source.**
7. **Location/geocoding coverage is still not statically guaranteed.**
8. **Backup raw export policy is still confused: `BackupPrivacyGate` allows raw export when encrypted backup is disabled.**
9. **Denied privacy state DTOs exist, but provider/UI propagation is inconsistent.**
10. **Audit context is sanitized now, but cloud provenance is still incomplete.**

Current status: **yellow/orange**. The architecture is moving in the right direction, but the actual cloud/raw/privacy enforcement is still partial.

---

# Sources checked

- Latest commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 8 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-8-privacy-ai-redaction-debug-report.md

- Current code:
  - `PrivacySettingsRepositoryImpl.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
  - `PrivacySettings.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt
  - `PrivacyDecision.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt
  - `PrivacyGate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyGate.kt
  - `CompositePrivacyGate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt
  - `PrivacyAuditLoggerImpl.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt
  - `EffectiveCloudAiPolicy.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/EffectiveCloudAiPolicy.kt
  - `CloudAiPrivacyGate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt
  - `LocationPrivacyGate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt
  - `BackupPrivacyGate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt
  - `RawContentSanitizer.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawContentSanitizer.kt
  - `CloudPayloadRedactor.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadRedactor.kt
  - `DefaultCloudPayloadRedactor.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadRedactor.kt
  - `CloudReceiptAssistService.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt
  - `CloudQueryInterpretationService.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt
  - `CloudDashboardBriefingService.kt`  
    https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt
  - `CloudReceiptItemCategorizationService.kt`  
    https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt
  - `NotificationCaptureService.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

---

# 1. Tracker reconciliation

Master tracker currently says Pipeline 8:

| ID | Tracker status |
|---|---|
| P8-P1-01 | fixed |
| P8-P1-02 | TODO |
| P8-P1-03 | TODO |
| P8-P1-04 | TODO |
| P8-P1-05 | partial |
| P8-P1-06 | TODO |
| P8-P1-07 | TODO |
| P8-P1-08 | TODO |
| P8-P1-09 | TODO |
| P8-P1-10 | TODO |
| P8-P1-11 | TODO |
| P8-P1-12 | TODO |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P8-P1-01 | **Partial, not fixed** | Settings update cancels some workers, but does not stop notification service, does not cancel in-flight HTTP calls, and uses hardcoded work names. |
| P8-P1-02 | **Partial** | `EffectiveCloudAiPolicyResolver` exists, but providers do not consistently consume its `redactBeforeCloud` decision. |
| P8-P1-03 | **Partial / mostly improved** | `NotApplicable` + composite final audit exist, but cloud provenance and denying-gate metadata remain incomplete. `PrivacyGate` docs are stale. |
| P8-P1-04 | **Mostly fixed / caveat** | Audit context allowlist exists, but safe structured context/provenance is still incomplete. |
| P8-P1-05 | **Partial** | Raw storage modes exist, but downstream `PendingReview`/events/debug/backup paths can still persist raw text. |
| P8-P1-06 | **Open / partial unknown** | No verified retention registry for AI artifacts, chats, email bodies, diagnostics. |
| P8-P1-07 | **Partial, not TODO** | `suggestFromText()` now redacts, but only via AI settings and receipt-purpose redaction; not effective privacy policy. |
| P8-P1-08 | **Partial** | `CloudPayloadPurpose` exists, but no `PreparedCloudPayload` contract; providers still build prompts directly. |
| P8-P1-09 | **Partial** | Fast notification pre-gate exists, but full gate still happens after extraction and service is not stopped on setting change. |
| P8-P1-10 | **Open / partial** | Gate exists, but `LocationPrivacyGate` comment itself says provider coverage is not statically guaranteed. |
| P8-P1-11 | **Partial** | Pipeline 7 indicates raw DB export is release-mitigated, but `BackupPrivacyGate` still allows raw export when encrypted backup is off. |
| P8-P1-12 | **Partial** | `PrivacyBlocked` model exists; provider/UI propagation remains inconsistent. |

---

# 2. Original issue evaluation

## P8-P1-01 — Privacy setting changes do not immediately stop active workers/services

### Current state

Partial.

Good:

`PrivacySettingsRepositoryImpl.updateSettings()` now calls `applyPrivacyChange(...)`.

It cancels:

```text
cloudAiEnabled false -> ai_daily_briefing
backgroundLocationBackfillEnabled false -> location_backfill, merchant_key_backfill
notificationCaptureEnabled false -> data_retention, receipt_matching, warranty_expiration_check, bill_reminder_periodic
```

Problems:

1. It does **not** stop or disable `NotificationCaptureService`.
2. It does **not** cancel in-flight cloud HTTP calls.
3. It does **not** cancel all possible cloud work, only `ai_daily_briefing`.
4. Work names are string literals, not `WorkerSpec`/`WorkerRegistry` driven.
5. It calls:
   ```kotlin
   applyPrivacyChange(old, transform(old))
   ```
   after the DataStore edit. This can diverge from the actual value persisted inside the edit block if preferences changed between the initial read and edit.
6. It cancels some workers because notification capture was disabled even though those workers are not purely notification-capture workers.

### Classification

Actual privacy bug / runtime policy weakness.

### Fix strategy

Create:

```kotlin
interface PrivacyRuntimePolicyApplier {
    suspend fun apply(old: PrivacySettings, new: PrivacySettings)
}
```

Requirements:

- use the exact persisted `updated` settings,
- use `WorkerRegistry` names, not strings,
- cancel all cloud workers when `cloudAiEnabled=false`,
- stop or rebind notification capture if capture disabled,
- cancel/mark in-flight cloud requests where possible,
- write a `PRIVACY_POLICY_APPLIED` audit/diagnostic event.

---

## P8-P1-02 — `PrivacySettings` and `AiSettings` can disagree

### Current state

Partial.

Good:

`EffectiveCloudAiPolicyResolver` exists and reconciles:

```text
PrivacySettings.cloudAiEnabled
AiSettings.allowCloudAi
PrivacySettings.redactBeforeCloud OR AiSettings.redactBeforeCloud
PrivacySettings.receiptImageCloudEnabled AND AiSettings.receiptImageCloudEnabled
PrivacySettings.bankStatementAiEnabled
```

`CloudAiPrivacyGate` uses this resolver.

Critical remaining bug:

Many cloud providers do not use the resolved policy object when building payloads. They check the privacy gate for allow/deny, then use other state for redaction:

- `CloudReceiptAssistService.suggest()` uses `AiSettings.redactBeforeCloud`.
- `CloudReceiptAssistService.suggestFromText()` uses `AiSettings.redactBeforeCloud`.
- `CloudDashboardBriefingService.generate()` uses nullable `AiSettings.redactBeforeCloud`, defaulting true only when repository is absent.
- `CloudReceiptItemCategorizationService` uses `input.redactBeforeCloud`, a caller-provided flag.

So this case can still leak raw text:

```text
PrivacySettings.redactBeforeCloud = true
AiSettings.redactBeforeCloud = false
Cloud AI allowed
provider checks gate -> allowed
provider reads AiSettings/input flag -> no redaction
raw text goes to cloud
```

### Classification

P1 privacy bug, potentially P0 depending on product promise.

### Fix strategy

Providers must consume one effective policy:

```kotlin
val policy = effectiveCloudAiPolicyResolver.resolve()
if (!policy.cloudAllowed) return Disabled(...)
val prepared = cloudPayloadPolicy.prepare(rawPayload, purpose, policy)
```

No provider should directly read `AiSettings.redactBeforeCloud` or caller-provided redaction flags for cloud-safety decisions.

---

## P8-P1-03 — Audit logging noisy / not semantically precise

### Current state

Improved but partial.

Good:

- `PrivacyDecision.NotApplicable` exists.
- Concrete gates return `NotApplicable` for unrelated capabilities.
- `CompositePrivacyGate` writes one final audit row.
- Gate exceptions become `FailClosed`.

Remaining issues:

1. `PrivacyGate.kt` documentation is stale: it still says unrecognized capabilities must return `Allowed` and every gate must audit every check.
2. `CompositePrivacyGate` treats any `Allowed` as “handled.” If a gate follows stale docs and returns `Allowed` for an unrelated capability, fail-closed missing-handler logic can be bypassed.
3. `gateHandledCapabilities` defaults to empty; if DI does not populate it with all sensitive capabilities, unsupported sensitive capability checks can default to allowed.
4. Audit row lacks:
   - denying gate,
   - provider,
   - model,
   - route,
   - redactionApplied,
   - payloadHash,
   - rawTextIncluded,
   - rawImageUploaded,
   - correlationId.

### Classification

Observability/compliance bug.

### Fix strategy

- Update `PrivacyGate` contract.
- Make `GateDecision` or `PrivacyDecision` include `Handled`.
- Force `CompositePrivacyGate` construction with a complete sensitive capability set.
- Add cloud provenance audit.

---

## P8-P1-04 — Audit context can store caller-provided sensitive data

### Current state

Mostly fixed.

Good:

`PrivacyAuditLoggerImpl` has an allowlist:

```text
operation
caller
entityType
entityId
provider
modelId
payloadHash
receiptId
```

and drops non-allowlisted keys.

Remaining caveats:

- Values are not hashed except by caller.
- `entityId`, `operation`, or `caller` can still contain sensitive caller-provided text if misused.
- There is still no typed `PrivacyAuditContext`.
- No provenance-specific schema for cloud calls.

### Classification

Mostly fixed with architectural hardening remaining.

### Fix strategy

Replace `Map<String, String>` with:

```kotlin
data class PrivacyAuditContext(
    val operation: AuditOperation?,
    val caller: String?,
    val entityType: String?,
    val entityIdHash: String?,
    val provider: String?,
    val modelId: String?,
    val payloadHash: String?,
    val redactionApplied: Boolean?,
    val rawTextIncluded: Boolean?,
    val rawImageUploaded: Boolean?,
    val correlationId: String?
)
```

---

## P8-P1-05 — Raw notification/OCR/email data stored first, purged later

### Current state

Partial.

Good:

- `RawStorageMode` exists:
  ```text
  STORE_RAW
  STORE_REDACTED
  STORE_METADATA_ONLY
  DO_NOT_STORE
  ```
- `RawContentSanitizer` exists.
- `NotificationCaptureService` creates a real in-memory `processingNotification` and a storage-safe `storageNotification`.
- Raw notification primary row can be redacted/metadata-only/do-not-store.

Remaining issues:

1. Raw notification data can still be passed downstream in `processingNotification`.
2. As noted in Pipeline 1, pending review creation paths can still persist raw title/text/body.
3. `extrasJson` is built before checking storage mode, although not persisted in metadata/do-not-store mode.
4. `RawContentSanitizer` is very coarse; `STORE_REDACTED` often becomes just `[REDACTED]`, not structured redaction.
5. Need verify all OCR/email/pending review/event/debug paths use sanitized payload, not only raw table rows.

### Classification

Actual privacy bug.

### Fix strategy

Use one write-time persistence sanitizer for all persisted targets:

```kotlin
data class SanitizedPersistencePayload(
    val rawRowTitle: String?,
    val rawRowText: String?,
    val pendingReviewTitle: String?,
    val pendingReviewText: String?,
    val eventMetadata: String?,
    val diagnosticMessage: String?
)
```

Apply to:

- raw notification rows,
- pending reviews,
- transaction events,
- receipt events,
- diagnostics,
- debug exports,
- backups.

---

## P8-P1-06 — Retention worker scope incomplete

### Current state

Still open / not verified fixed.

I did not verify a retention registry or purge targets for:

```text
AI artifacts
AI chat messages
email receipt bodies
debug diagnostics
cloud call artifacts
service diagnostics
```

### Classification

Privacy/data retention bug.

### Fix strategy

Create a retention registry:

```kotlin
interface RetentionTarget {
    val name: String
    suspend fun purge(cutoffMs: Long): RetentionPurgeResult
}
```

Targets:

```text
RawNotificationRetentionTarget
ScannedReceiptOcrRetentionTarget
EmailReceiptSourceRetentionTarget
AiArtifactRetentionTarget
AiChatMessageRetentionTarget
ServiceDiagnosticsRetentionTarget
PipelineDiagnosticRetentionTarget
DebugExportRetentionTarget
```

---

## P8-P1-07 — Bank-statement cloud text path can send raw prompt

### Current state

Partial.

Good:

`CloudReceiptAssistService.suggestFromText(prompt)` now:

- checks `CLOUD_AI_BANK_STATEMENT`,
- redacts the prompt when `AiSettings.redactBeforeCloud` is true.

Problems:

1. It does not use `EffectiveCloudAiPolicy.redactBeforeCloud`.
2. It uses `CloudPayloadPurpose.RECEIPT_ASSIST`, not a bank-statement-specific purpose.
3. It does not emit provider/model/payload audit provenance.
4. It still accepts a prebuilt raw prompt instead of a typed bank-statement payload.
5. If privacy redaction is true but AI redaction is false, raw prompt may be sent.

### Classification

Actual privacy bug.

### Fix strategy

Add:

```kotlin
CloudPayloadPurpose.BANK_STATEMENT_VALIDATION
data class CloudBankStatementPayload(...)
```

Then:

```kotlin
val policy = effectiveCloudAiPolicyResolver.resolve()
val prepared = cloudPayloadPolicy.prepare(payload, BANK_STATEMENT_VALIDATION, policy)
```

Provider sends only `PreparedCloudPayload`.

---

## P8-P1-08 — Redaction not a formal purpose-aware payload contract

### Current state

Partial.

Good:

- `CloudPayloadPurpose` exists.
- `DefaultCloudPayloadRedactor` has purpose-specific max lengths and some purpose-specific handling.

Still missing:

- no `PreparedCloudPayload`,
- no typed cloud payload DTOs,
- no static guard that HTTP requests only use prepared payloads,
- providers still manually build prompts,
- category/item/merchant values can be raw depending on caller flags,
- no mandatory audit of redaction fields.

### Classification

Architectural privacy enforcement gap.

### Fix strategy

Add:

```kotlin
data class PreparedCloudPayload(
    val text: String,
    val purpose: CloudPayloadPurpose,
    val redactionApplied: Boolean,
    val rawTextIncluded: Boolean,
    val rawImageIncluded: Boolean,
    val payloadHash: String,
    val fieldsRedacted: Set<String>
)
```

Cloud providers should accept prepared payloads only.

---

## P8-P1-09 — Notification privacy gate too late / runtime state not cached

### Current state

Partial.

Good:

`NotificationCaptureService` now has a cached `capturePrivacyDenied` flag and checks it before extracting notification extras.

Problems:

1. `capturePrivacyDenied` is derived from `PrivacySettings.notificationCaptureEnabled`, not the full `PrivacyGate` decision.
2. Full `privacyGate.check(NOTIFICATION_CAPTURE)` still happens inside the coroutine, after extraction/filtering.
3. If full gate fails closed or denies for a reason other than the settings flag, text was already read.
4. The service is not stopped when setting changes.
5. Dedupe key is inserted before pre-extraction privacy denial. If denied, the method returns before the coroutine/finally cleanup, leaving a short-lived dedupe entry.
6. Messaging extraction still appears to cast `EXTRA_MESSAGES` to `CharSequence`, which is probably not enough for real `MessagingStyle.Message` bundles.

### Classification

Actual privacy + capture correctness bug.

### Fix strategy

Create `NotificationCaptureGate`:

```kotlin
data class CaptureDecision(
    val allowed: Boolean,
    val reason: String?,
    val source: String
)
```

It should combine:

- restore mode,
- full privacy decision,
- blocked package,
- shutdown state.

Check it before dedupe and before extras extraction.

---

## P8-P1-10 — Geocoding/location gate coverage not statically guaranteed

### Current state

Open/partial.

`LocationPrivacyGate` handles:

```text
EXTERNAL_GEOCODING
BACKGROUND_LOCATION_BACKFILL
DEVICE_GPS_LOCATION
OVERPASS_API
```

But the code comment in `LocationPrivacyGate` says provider coverage is not statically guaranteed.

### Classification

Privacy regression risk, possibly actual bug if any provider bypasses gate.

### Fix strategy

- Wrap all location providers behind `PrivacyAwareGeocodingService`.
- Add static guard:
  ```text
  classes ending GeocodingService/NearbyService must inject/use PrivacyGate
  ```
- Add tests for every provider:
  ```text
  Geoapify
  GooglePlaces
  Nominatim
  Photon
  Overpass
  Foreground GPS
  LocationBackfillWorker
  ```

---

## P8-P1-11 — Raw backup/export remains reachable

### Current state

Partial.

`BackupPrivacyGate` still says:

```text
RAWBACKUP_EXPORT allowed when encryptedBackupEnabled = false
```

That policy is unsafe because disabling encrypted backup is not the same as explicitly consenting to plaintext raw export.

Pipeline 7 found production raw export is more mitigated now, but the privacy gate contract remains conceptually wrong.

### Classification

Potential P1 privacy issue if reachable in release UI; otherwise P2 architectural.

### Fix strategy

Replace boolean with:

```kotlin
enum class BackupExportPolicy {
    ENCRYPTED_ONLY,
    ENCRYPTED_REDACTED,
    DISABLED,
    DEBUG_RAW_ONLY
}
```

Rules:

- raw export disabled in release,
- raw export only debug + explicit confirmation,
- encrypted backup setting should not imply raw export permission.

---

## P8-P1-12 — Denied privacy states not consistently visible

### Current state

Partial.

Good:

`PrivacyBlocked` sealed model exists and maps denied decisions to typed user-facing states.

Remaining problems:

- Cloud providers still often return `null`, `Unsupported`, or generic `AiServiceError.Disabled`.
- UI/viewmodels may not map all disabled states to `PrivacyBlocked`.
- Notification denied states are mostly logs/diagnostics, not user-facing.
- No common “last privacy denial” health/debug panel verified.

### Classification

UX/debuggability bug.

### Fix strategy

Adopt one result model:

```kotlin
sealed interface PrivacySensitiveResult<out T> {
    data class Success<T>(val value: T)
    data class Blocked(val blocked: PrivacyBlocked)
    data class Failed(val error: Throwable)
}
```

---

# 3. New/current issues found

## P8-NEW-01 — Privacy redaction can be bypassed by `AiSettings.redactBeforeCloud`

### Severity

P1/P0.

### Evidence

`EffectiveCloudAiPolicyResolver` correctly computes:

```text
redactBeforeCloud = privacy.redactBeforeCloud || ai.redactBeforeCloud
```

But providers do not consistently use it.

Examples:

- `CloudReceiptAssistService.suggest()` uses `AiSettings.redactBeforeCloud`.
- `CloudReceiptAssistService.suggestFromText()` uses `AiSettings.redactBeforeCloud`.
- `CloudDashboardBriefingService` uses `AiSettings.redactBeforeCloud`.
- `CloudReceiptItemCategorizationService` uses `input.redactBeforeCloud`.

### Impact

If privacy settings require redaction but AI settings or caller input says no redaction, raw prompt text can be sent to cloud.

### Fix

Inject and use `EffectiveCloudAiPolicyResolver` or `CloudPayloadPolicy` in every cloud provider.

Acceptance:

```text
privacy_redact_true_ai_redact_false_redacts_all_cloud_payloads
```

---

## P8-NEW-02 — DataStore fail-closed comments are false for notification/raw storage

### Severity

P1.

### Evidence

`fallbackPreferences()` returns empty preferences.

`toPrivacySettings()` defaults:

```text
notificationCaptureEnabled = true
rawNotificationStorageMode = STORE_RAW
rawOcrStorageMode = STORE_RAW
```

So corruption/read failure is not strict fail-closed for notification capture or raw storage.

### Impact

If privacy settings are corrupt, notification capture can remain enabled and raw storage can default to raw.

### Fix

Introduce load state:

```kotlin
sealed interface PrivacySettingsLoadState {
    data class Loaded(val settings: PrivacySettings)
    data class FirstRunDefault(val settings: PrivacySettings)
    data class CorruptedFailClosed(val settings: PrivacySettings)
}
```

Corruption defaults:

```text
notificationCaptureEnabled=false
cloudAiEnabled=false
externalGeocodingEnabled=false
rawNotificationStorageMode=DO_NOT_STORE
rawOcrStorageMode=DO_NOT_STORE
emailReceiptStorageMode=DO_NOT_STORE
```

---

## P8-NEW-03 — `PrivacyGate` contract is stale and dangerous

### Severity

P2/P1 regression risk.

### Evidence

`PrivacyGate.kt` still says unrecognized capabilities must return `Allowed` and every gate must audit.

Current implementation expects concrete gates to return `NotApplicable` and only composite to audit.

### Impact

A future gate following the docs can make `CompositePrivacyGate` treat an unrelated `Allowed` as handled, defeating missing-handler fail-closed logic.

### Fix

Update docs and tests:

```text
unrelated capability -> NotApplicable
concrete gates do not audit
composite writes one final audit
```

---

## P8-NEW-04 — Test constructors with allow-all privacy gates remain in production source

### Severity

P2/P1 regression risk.

### Evidence

Cloud providers have secondary constructors in `main` source that instantiate no-op allow-all gates, e.g. receipt, query, dashboard, item categorization.

### Impact

Future production code can accidentally instantiate a cloud provider without real privacy checks.

### Fix

Move test constructors to test source or mark:

```kotlin
@VisibleForTesting
internal constructor(...)
```

Add CI grep:

```text
object : PrivacyGate { ... PrivacyDecision.Allowed }
```

must not appear in `main` except approved test-only visibility.

---

## P8-NEW-05 — `suggestFromText()` uses receipt redaction purpose for bank statements

### Severity

P1/P2.

### Evidence

Bank statement path calls:

```text
redactor.redactText(prompt, CloudPayloadPurpose.RECEIPT_ASSIST)
```

There is no `BANK_STATEMENT_VALIDATION` purpose.

### Impact

Bank statement descriptions/counterparties/account references are not treated with a dedicated stricter policy.

### Fix

Add `BANK_STATEMENT_VALIDATION` and make it stricter than receipt assist.

---

## P8-NEW-06 — Cloud audit provenance still missing despite redaction metadata existing

### Severity

P2/P1 compliance.

### Evidence

`RedactedPayload` includes fields like:

```text
redactionApplied
fieldsRedacted
payloadHash
```

but providers do not consistently pass these to audit. `CompositePrivacyGate` only logs the gate decision.

### Impact

Cannot prove which cloud call was redacted, which payload hash was sent, or which model/provider was used.

### Fix

Add `CloudAiCallAudit` or extend privacy audit.

---

## P8-NEW-07 — Notification pre-extraction denial leaves dedupe key behind

### Severity

P2.

### Evidence

`onNotificationPosted()` writes `processedNotifications[coarseDedupeKey] = now` before the fast privacy-denied return.

### Impact

If privacy denial is transient, or settings emission is delayed, the same notification can be suppressed for the dedupe window.

### Fix

Move dedupe insert after pre-extraction privacy gate, restore gate, blocked-package gate, and ideally after content-aware fingerprinting.

---

## P8-NEW-08 — `DO_NOT_STORE` for notifications still stores processed payload downstream unless all consumers sanitize

### Severity

P1.

### Evidence

`NotificationCaptureService` sends both:

```text
processingNotification = raw in-memory text
storageNotification = sanitized
```

to repository. This is valid only if repository/pipeline never persist `processingNotification` fields outside `raw_notifications`.

Pipeline 1 already found pending-review leakage risk.

### Impact

Raw notification text may persist in pending review/event tables while raw notification row is sanitized.

### Fix

Repository/pipeline should receive:

```kotlin
EphemeralNotificationInput(rawText...)
SanitizedNotificationPersistencePayload(...)
```

and persistence APIs should not accept raw text accidentally.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting privacy bugs

Prioritize these:

1. **Privacy redaction bypass via provider use of `AiSettings.redactBeforeCloud`.**
2. **DataStore corruption defaults notification capture to enabled and raw storage to raw.**
3. **Raw storage mode can be bypassed through downstream tables.**
4. **Bank-statement cloud text path lacks bank-statement-specific prepared redaction.**
5. **Notification full privacy gate still after extraction for non-settings denial/fail-closed cases.**
6. **Raw export gate still conceptually allows plaintext when encrypted backup is disabled.**
7. **Location provider coverage not statically guaranteed.**

## Architectural / hardening work

Important but lower immediate urgency:

1. Replace raw context map with typed `PrivacyAuditContext`.
2. Add cloud payload policy and `PreparedCloudPayload`.
3. Remove allow-all test constructors from main source.
4. Add static privacy guard tests.
5. Add durable cloud AI call audit.
6. Add global privacy-denied UI/diagnostic model.
7. Expand retention registry.

---

# 5. Recommended implementation plan

## PR 1 — Enforce effective cloud policy in every provider

### Goal

Privacy settings are authoritative for redaction and cloud permission.

### Files

- `EffectiveCloudAiPolicy.kt`
- all `Cloud*Service.kt`
- `Hybrid*Service.kt`
- `CloudAiPrivacyGate.kt`

### Tasks

1. Inject `EffectiveCloudAiPolicyResolver` into every cloud provider.
2. Remove direct redaction decisions from:
   - `AiSettings.redactBeforeCloud`
   - caller-provided `input.redactBeforeCloud`
3. Use:
   ```kotlin
   policy.redactBeforeCloud
   ```
   everywhere.
4. Keep AI settings only for route/model preference, not privacy weakening.
5. Add tests for privacy/AI disagreement.

### Acceptance tests

```text
privacy_cloud_false_ai_true_denies
privacy_redact_true_ai_redact_false_redacts_receipt_assist
privacy_redact_true_ai_redact_false_redacts_bank_statement
privacy_redact_true_input_redact_false_redacts_item_categorization
receipt_image_upload_denied_when_effective_redaction_true
```

---

## PR 2 — Prepared cloud payload contract

### Goal

No cloud HTTP call can send raw prompt accidentally.

### Files

- `CloudPayloadRedactor.kt`
- new `CloudPayloadPolicy.kt`
- all cloud providers
- static guard

### Tasks

1. Add `PreparedCloudPayload`.
2. Add typed payloads:
   - `CloudReceiptPayload`
   - `CloudBankStatementPayload`
   - `CloudDashboardBriefingPayload`
   - `CloudItemCategorizationPayload`
   - `CloudQueryPayload`
3. Add `BANK_STATEMENT_VALIDATION` purpose.
4. Providers send only prepared payload text/image.
5. Static guard: `Request.Builder().post(...)` in cloud package must reference `PreparedCloudPayload`.

### Acceptance tests

```text
all_cloud_providers_use_prepared_payload
bank_statement_purpose_redacts_counterparty_and_account_refs
raw_prompt_string_not_passed_to_provider_http_body
```

---

## PR 3 — Strict privacy DataStore fail-closed state

### Goal

Corruption/read failure cannot enable sensitive features.

### Files

- `PrivacySettingsRepositoryImpl.kt`
- `PrivacySettings.kt`
- UI privacy settings screen

### Tasks

1. Add load-state model.
2. On corruption:
   - notification capture off,
   - cloud AI off,
   - geocoding/GPS off,
   - raw storage modes do-not-store,
   - debug persistence off.
3. Show user warning.
4. Do not silently treat corruption as first run.

### Acceptance tests

```text
datastore_corruption_disables_notification_capture
datastore_corruption_sets_raw_storage_do_not_store
first_run_defaults_preserved
corruption_warning_visible
```

---

## PR 4 — Raw storage persistence hardening

### Goal

Raw storage modes apply to all tables, not only raw source rows.

### Files

- `NotificationCaptureService.kt`
- `NotificationRepository.kt`
- `NotificationProcessingPipeline.kt`
- receipt/email lifecycle paths
- diagnostics/events

### Tasks

1. Split ephemeral parse input from persistent payload.
2. Sanitize pending reviews/events/diagnostics.
3. Apply email/OCR storage modes to review/event/source tables.
4. Add DB-level tests that inspect all sensitive columns.

### Acceptance tests

```text
notification_do_not_store_no_raw_text_in_pending_reviews
notification_metadata_only_no_raw_text_in_events
ocr_do_not_store_no_raw_text_in_reviews_or_events
email_metadata_only_no_subject_sender_body_except_hashes
```

---

## PR 5 — Notification capture gate before dedupe/extraction

### Goal

Disabled notification capture means no extras/text read.

### Files

- `NotificationCaptureService.kt`
- new `NotificationCaptureGate.kt`
- diagnostics

### Tasks

1. Build hot cached full privacy decision.
2. Check restore/privacy/blocked package before dedupe and extraction.
3. Move dedupe insert after gate.
4. Stop/rebind listener on setting off where possible.
5. Record privacy-denied diagnostic.

### Acceptance tests

```text
notification_disabled_does_not_read_extras
fail_closed_privacy_decision_does_not_read_extras
privacy_denied_does_not_poison_dedupe_cache
settings_change_blocks_next_notification_without_restart
```

---

## PR 6 — Audit/provenance cleanup

### Goal

Audits are useful and safe.

### Files

- `PrivacyGate.kt`
- `CompositePrivacyGate.kt`
- `PrivacyAuditLoggerImpl.kt`
- `PrivacyAuditEvent.kt`
- new `CloudAiCallAudit.kt`

### Tasks

1. Fix `PrivacyGate` docs.
2. Add full sensitive `gateHandledCapabilities`.
3. Add typed `PrivacyAuditContext`.
4. Add cloud provider/model/payload/redaction/correlation metadata.
5. Ensure audit failure behavior is explicit.

### Acceptance tests

```text
unrelated_gate_returns_not_applicable
missing_sensitive_capability_handler_fail_closed
cloud_call_audit_has_provider_model_payload_hash
audit_context_rejects_raw_text
```

---

## PR 7 — Location/privacy static guard

### Goal

External location cannot bypass privacy.

### Files

- geocoding providers
- `OverpassNearbyService`
- `LocationBackfillWorker`
- `AndroidForegroundLocationProvider`
- static guard

### Tasks

1. Wrap providers with `PrivacyAwareGeocodingService`.
2. Gate:
   - external geocoding,
   - Overpass,
   - background backfill,
   - device GPS.
3. Add CI grep/Detekt rule.

### Acceptance tests

```text
external_geocoding_disabled_blocks_all_providers
overpass_disabled_blocks_api_call
device_gps_disabled_blocks_location_provider
background_location_disabled_skips_worker
```

---

## PR 8 — Raw export policy cleanup

### Goal

Plaintext raw export is impossible unless explicitly debug-only.

### Files

- `BackupPrivacyGate.kt`
- backup repository/viewmodel
- backup UI

### Tasks

1. Replace `encryptedBackupEnabled` implication with `BackupExportPolicy`.
2. Raw export disabled in release.
3. Raw export debug-only + explicit confirmation.
4. Redacted encrypted backup is default safe option.

### Acceptance tests

```text
raw_export_rejected_in_release
encrypted_disabled_does_not_allow_raw_export
raw_export_requires_debug_policy_and_confirmation
```

---

## PR 9 — Privacy denied propagation

### Goal

Users see privacy-blocked state, not generic failure.

### Files

- `PrivacyBlocked.kt`
- AI service result models
- geocoding result models
- notification diagnostics
- UI/viewmodels

### Tasks

1. Add `PrivacySensitiveResult`.
2. Map denied decisions to `PrivacyBlocked`.
3. Surface in UI and diagnostics.
4. Add health screen/debug row for recent privacy denials.

### Acceptance tests

```text
cloud_ai_denied_shows_privacy_state
geocoding_denied_shows_privacy_state
notification_capture_denied_visible_in_diagnostics
backup_export_denied_shows_raw_export_disabled
```

---

# 6. Suggested tracker updates

Update Pipeline 8 tracker:

| ID | Suggested status |
|---|---|
| P8-P1-01 | Partial |
| P8-P1-02 | Partial |
| P8-P1-03 | Partial / mostly improved |
| P8-P1-04 | Mostly fixed / structured-context caveat |
| P8-P1-05 | Partial |
| P8-P1-06 | TODO / unverified |
| P8-P1-07 | Partial |
| P8-P1-08 | Partial |
| P8-P1-09 | Partial |
| P8-P1-10 | TODO / partial |
| P8-P1-11 | Partial |
| P8-P1-12 | Partial |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P8-NEW-01 | P1/P0 | Privacy redaction can be bypassed by `AiSettings.redactBeforeCloud` |
| P8-NEW-02 | P1 | DataStore fail-closed comments are false for notification/raw storage |
| P8-NEW-03 | P2/P1 | `PrivacyGate` contract is stale and dangerous |
| P8-NEW-04 | P2/P1 | Test constructors with allow-all gates remain in production source |
| P8-NEW-05 | P1/P2 | `suggestFromText()` uses receipt redaction purpose for bank statements |
| P8-NEW-06 | P2/P1 | Cloud audit provenance missing despite redaction metadata existing |
| P8-NEW-07 | P2 | Notification pre-extraction denial leaves dedupe key behind |
| P8-NEW-08 | P1 | `DO_NOT_STORE` can still persist raw downstream unless all consumers sanitize |

---

# 7. Golden tests for Pipeline 8

Add or verify:

```text
privacy_cloud_false_ai_true_denies_all_cloud_providers
privacy_redact_true_ai_redact_false_redacts_all_cloud_payloads
privacy_redact_true_input_redact_false_redacts_item_categorization
bank_statement_suggestFromText_uses_bank_statement_purpose
bank_statement_suggestFromText_redacts_when_privacy_requires
receipt_image_upload_denied_when_effective_redaction_true
datastore_corruption_disables_notification_capture
datastore_corruption_sets_raw_storage_do_not_store
first_run_notification_default_distinct_from_corruption_default
notification_disabled_does_not_read_extras
privacy_fail_closed_notification_does_not_read_extras
privacy_denied_notification_does_not_poison_dedupe
notification_do_not_store_no_raw_text_in_pending_reviews
raw_ocr_do_not_store_no_raw_text_in_events
email_metadata_only_no_subject_sender_body
privacy_audit_one_final_decision_per_check
privacy_audit_context_rejects_raw_text
cloud_call_audit_has_provider_model_payload_hash_redaction
test_noop_privacy_gate_not_constructible_from_main_callers
external_geocoding_disabled_blocks_all_providers
raw_export_rejected_in_release
encrypted_disabled_does_not_allow_raw_export
privacy_denied_state_visible_in_ui_or_diagnostics
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "redactBeforeCloud" app/src/main/java
grep -R "allowCloudAi" app/src/main/java
grep -R "EffectiveCloudAiPolicy" app/src/main/java
grep -R "privacyGate.check" app/src/main/java/com/yourname/expensetracker/data/ai
grep -R "Request.Builder()" app/src/main/java/com/yourname/expensetracker/data/ai/provider
grep -R "object : PrivacyGate" app/src/main/java
grep -R "PrivacyDecision.Allowed" app/src/main/java/com/yourname/expensetracker/data/ai/provider
grep -R "suggestFromText" app/src/main/java
grep -R "CloudPayloadPurpose" app/src/main/java
grep -R "RawStorageMode" app/src/main/java
grep -R "PendingReview(" app/src/main/java
grep -R "rawOcrText" app/src/main/java
grep -R "EmailReceiptSource" app/src/main/java
grep -R "fallbackPreferences" app/src/main/java
grep -R "encryptedBackupEnabled" app/src/main/java
grep -R "GeocodingService" app/src/main/java
grep -R "NearbyService" app/src/main/java
grep -R "Timber." app/src/main/java/com/yourname/expensetracker/data/ai app/src/main/java/com/yourname/expensetracker/domain/privacy
```

Allowed direct cloud HTTP calls should be restricted to:

```text
Cloud*Service implementations only
each must use EffectiveCloudAiPolicy + PreparedCloudPayload + CloudAiCallAudit
```

Allowed raw sensitive persistence:

```text
Only when explicit storage mode says STORE_RAW.
All other modes must sanitize every persisted target, not only primary raw rows.
```

Definition of done:

```text
- PrivacySettings is authoritative over AiSettings.
- Effective redaction policy is used by every cloud provider.
- Bank statement cloud payload has dedicated strict redaction purpose.
- No cloud provider sends a non-prepared prompt.
- DataStore corruption fails closed for notification/raw/cloud/location.
- Notification capture denied state prevents extras extraction.
- Raw storage modes apply to pending reviews, events, diagnostics, exports.
- Audit context is typed/sanitized and cloud provenance is durable.
- No allow-all privacy gate constructors are reachable in main production source.
- Location/geocoding providers are self-defending and static-guarded.
- Raw export policy is explicit and release-safe.
- Denied privacy states are user-visible and diagnostic-visible.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fix effective cloud redaction policy consumption** — prevents direct privacy redaction bypass.
2. **Add prepared cloud payload contract, especially bank statements.**
3. **Fix DataStore corruption fail-closed defaults.**
4. **Harden raw storage mode across all persisted downstream tables.**
5. **Move notification full capture gate before dedupe/extraction.**
6. **Fix privacy audit/provenance and stale gate contract docs.**
7. **Remove/no-op-gate test constructors from main source.**
8. **Static privacy guard for location/geocoding providers.**
9. **Replace raw export boolean policy with explicit export policy.**
10. **Propagate `PrivacyBlocked` to UI/diagnostics consistently.**