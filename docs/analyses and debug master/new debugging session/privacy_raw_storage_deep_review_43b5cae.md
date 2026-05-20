# Privacy / Raw-Storage / Redaction Deep Review

Commit: `43b5cae0d43228a3d5b2f27ae34d9173e358e220`

## Executive verdict

This commit fixes several previous items, but Universal Issue #3 is **not fully done**.

Good progress:
- CI now runs `verify_privacy_boundaries.py`.
- `CompositePrivacyGate` now receives `PrivacyCapabilityHandlingPolicy.gateHandledCapabilities`.
- `PrivacyGate` docs now match `NotApplicable` semantics.
- Notification normal path now checks fast privacy + blocked package before dedupe/extras extraction.
- Email message-ID hash handling and parsed-item policy improved in coordinator.
- `SafePrivacyMetadata.put()` hash-key bypass appears fixed.
- Retention registry exists.

Major remaining problems:
1. Several cloud providers still bypass `PreparedCloudPayload`.
2. `CloudReceiptAssistService` only prepares OCR text, then injects other raw fields into the prompt.
3. `CloudReceiptItemCategorizationService` uses `CloudPayloadPolicy` only as a boolean redaction flag, not as the actual prepared payload.
4. Notification refresh path still reads extras before blocked-package and shutdown checks.
5. Email real path still does not use `EmailReceiptPersistencePayload`.
6. Email coordinator diagnostics and side effects still lose correlation.
7. Retention can delete all email source rows because non-notification/OCR targets receive `now` as cutoff.
8. Tests remain mostly contract/model tests, not real persistence tests.

---

# 1. Resolved / mostly resolved

## 1.1 CI privacy guard added

`.github/workflows/ci.yml` now runs:

```bash
python3 scripts/verify_privacy_boundaries.py --root .
```

Good.

Risk: if CI currently passes, the script is not catching several still-existing provider bypasses, or those providers are not in the executed path.

---

## 1.2 Production fail-closed capability set wired

`PrivacyModule` passes:

```kotlin
gateHandledCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
```

to `CompositePrivacyGate`.

This resolves the earlier production fail-open default for gate-handled capabilities.

---

## 1.3 SafePrivacyMetadata hash-key bypass improved

`SafePrivacyMetadata.put()` now routes through key-aware sanitation. Approved hash keys require hash-like values; unknown `*Hash` keys are redacted.

Remaining caveat:
- maps/lists are redacted wholesale, not recursively sanitized. Privacy-safe, but lossy.

---

## 1.4 Notification normal path improved

Normal `onNotificationPosted` now checks:
- restore mode
- shutdown
- fast privacy flag
- blocked package cache
- dedupe

before extras extraction.

This fixes the main normal-path pre-extraction privacy issue.

---

# 2. High-priority remaining issues

## PRIV-43B-01 — Several cloud providers still bypass PreparedCloudPayload

Severity: **High**  
Type: **actual privacy architecture bug**

The plan required:

```text
Every cloud request must use PreparedCloudPayload produced by CloudPayloadPolicy.
```

But these providers still use direct redaction/prompt/request construction:

### Still not migrated

```text
CloudWarrantyExtractionService
CloudCategorizationAssistService
CloudDedupeJudgeService
CloudReviewExplanationService
```

Examples:
- `CloudWarrantyExtractionService` injects `CloudPayloadRedactor` + `EffectiveCloudAiPolicyResolver`, calls `policyResolver.resolve().redactBeforeCloud`, builds prompt, then posts request directly.
- `CloudCategorizationAssistService` still uses allow-all secondary constructors, `policyResolver.resolve().redactBeforeCloud`, `buildRequestBody(input, shouldRedact)`, and direct `Request.Builder().post(...)`.
- `CloudDedupeJudgeService` and `CloudReviewExplanationService` follow the same pattern.

Impact:
- PrivacySettings is not centrally authoritative for all cloud payloads.
- Prepared payload audit/provenance is bypassed.
- Some main-source secondary constructors still contain allow-all gates.
- CI privacy guard should probably fail on these files. If it does not, the guard is too weak.

Fix:
- Inject `CloudPayloadPolicy` into every cloud provider.
- Remove `CloudPayloadRedactor` + `EffectiveCloudAiPolicyResolver` from providers except policy layer.
- Replace provider-local `buildPrompt(input, shouldRedact)` with either:
  - `cloudPayloadPolicy.prepareText(purpose, fullPrompt)`, or
  - purpose-specific methods like `prepareWarrantyExtraction`, `prepareCategorizationAssist`, `prepareDedupeJudge`, `prepareReviewExplanation`.
- Build request body only from `prepared.text`.

Tests:
```text
cloud_warranty_uses_prepared_payload
cloud_categorization_assist_uses_prepared_payload
cloud_dedupe_judge_uses_prepared_payload
cloud_review_explanation_uses_prepared_payload
privacy_guard_flags_unmigrated_cloud_providers
```

---

## PRIV-43B-02 — CloudReceiptAssistService prepares only OCR text, then injects other raw fields

Severity: **High**  
Type: **raw cloud payload leak**

`CloudReceiptAssistService` does:

```kotlin
prepared = cloudPayloadPolicy.prepareText(RECEIPT_ASSIST, input.rawOcrText)
```

Then `buildPrompt(...)` includes:

```text
parsedMerchant
parsedTotal
parsedDate
parsedTaxAmount
lineItemsJson
rawOcrText = prepared.text
```

So only `rawOcrText` is prepared/redacted. Other fields can still carry raw merchant/item descriptions.

Also image upload is controlled by:

```kotlin
allowImage && !prepared.redactionApplied
```

not by `prepared.rawImageIncluded`. `PreparedCloudPayload` currently does not actually prepare receipt images.

Impact:
- Line item JSON and merchant can bypass redaction.
- Image upload is not governed by a real prepared image payload.
- Cloud audit payload hash only represents OCR text, not the final prompt sent.

Fix:
- Add `CloudPayloadPolicy.prepareReceiptAssist(input)` returning a payload for the **entire prompt** and image decision.
- Provider should not append raw `lineItemsJson` or merchant after policy preparation.
- Image bytes must come from `PreparedCloudPayload.imageBytes`, not provider direct file read.

Tests:
```text
receipt_assist_prepared_payload_includes_full_prompt_redaction
receipt_assist_line_items_redacted_when_policy_requires
receipt_assist_merchant_redacted_when_policy_requires
receipt_assist_image_upload_uses_prepared_rawImageIncluded
```

---

## PRIV-43B-03 — CloudReceiptItemCategorizationService uses policy only as a boolean flag

Severity: **High**  
Type: **PreparedCloudPayload bypass**

Current code:

```kotlin
val shouldRedact =
    cloudPayloadPolicy.prepareText(ITEM_CATEGORIZATION, "").redactionApplied

val prompt = buildPrompt(input, shouldRedact)
val requestBody = buildRequestBody(prompt)
```

This is not using prepared payload for the real prompt. It only calls policy on an empty string to get a redaction boolean.

Also redacted merchant/item IDs use:

```kotlin
input.merchant?.hashCode()
item.description?.hashCode()
```

`String.hashCode()` is not privacy-safe or stable across all privacy requirements.

Fix:
- Add `prepareItemCategorization(input)` to `CloudPayloadPolicy`.
- Generate final safe prompt inside policy or pass full raw prompt into `prepareText`.
- Replace `hashCode()` pseudonyms with `SensitiveHashingService.hmacSha256Prefix(...)` or policy-provided placeholders.
- Static guard should flag `hashCode()` in cloud provider prompt redaction, not only message/provider IDs.

Tests:
```text
item_categorization_does_not_call_prepareText_on_empty_string
item_categorization_request_body_uses_prepared_text
item_categorization_redaction_does_not_use_string_hashCode
```

---

## PRIV-43B-04 — Static privacy guard is still too weak

Severity: **High regression risk**

Although CI runs the script, current source still appears to contain violations:
- `policyResolver.resolve().redactBeforeCloud` in several cloud providers.
- direct `Request.Builder().post(...)` in unmigrated providers.
- allow-all `object : PrivacyGate { Allowed }` in several secondary constructors.
- `hashCode()` for redacted merchant/item placeholders.

If CI passes, the guard is missing these cases.

Specific script weakness:
- G4 checks only a small nearby context and can be fooled when `FailClosed` appears elsewhere in the same surrounding text.
- G3 only looks for `"PreparedCloudPayload"` or `"prepared"` near `Request.Builder`, which can be satisfied by comments/variable names without proving the request body derives from `PreparedCloudPayload`.

Fix:
- G3 should require the posted body expression/dataflow to originate from `PreparedCloudPayload` or a small approved `CloudAiTransport`.
- G4 should parse the anonymous `PrivacyGate` block or at least flag any `PrivacyDecision.Allowed` inside an anonymous gate in `main`.
- Add G11: no `.hashCode()` in `data/ai/provider` prompt redaction.
- Run script locally and make CI fail until all providers pass.

---

# 3. Notification remaining issues

## PRIV-43B-05 — Refresh path still reads extras before blocked-package and shutdown checks

Severity: **Medium/High**

`processNotificationBypassDedupe()` checks:
- restore
- fast privacy

then immediately:

```kotlin
val extras = sbn.notification.extras
val parts = NotificationTextParts.extract(extras)
```

It does **not** check `isPackageBlockedFast(packageName)` before extraction.

It also checks `isShuttingDown` **after** extraction/filter.

Impact:
- Manual refresh can read blocked-package notification text.
- Shutdown path can still extract raw text before cancellation.

Fix:
- Mirror normal path exactly:
  1. restore
  2. shutdown
  3. fast privacy
  4. blocked package
  5. then extras extraction/filter

Tests:
```text
refresh_blocked_package_does_not_read_extras
refresh_shutdown_does_not_read_extras
refresh_pre_extraction_order_matches_normal_path
```

---

## PRIV-43B-06 — Full privacy gate is still defense-in-depth, not pre-extraction authority

Severity: **Medium**

Normal path relies on `capturePrivacyDenied` derived from settings flow before extraction, but the full `privacyGate.check(NOTIFICATION_CAPTURE)` still happens after extraction.

If other gates or fail-closed conditions are not reflected in the fast flag, raw extras can be read.

Fix:
- Either make fast gate authoritative over all denial causes:
  - load state corruption
  - notification setting
  - package block cache
  - maintenance/shutdown
- Or perform a non-suspending pre-extraction gate object backed by cached gate state.

---

# 4. Email / receipt remaining issues

## PRIV-43B-07 — EmailReceiptPersistencePayload still not used in real path

Severity: **High**

`EmailReceiptPersistencePayload` model/tests exist, but `EmailReceiptIngestionService` still passes raw values into coordinator:

```kotlin
from = sender
subject = subject
body = emailBody
rawEmailBody = emailBody
sender = sender
subject = subject
messageId = messageId
```

Coordinator then manually sanitizes some fields.

Impact:
- The model-level tests do not prove real persistence.
- Future fields can bypass policy because the API still accepts raw persistence values.

Fix:
- Build `EmailReceiptPersistencePayload` in ingestion.
- Change coordinator API to accept:
  ```kotlin
  payload: EmailReceiptPersistencePayload
  ```
  plus ephemeral parse-only values if strictly needed.
- Remove raw persistence params from coordinator signature.

---

## PRIV-43B-08 — Email coordinator diagnostics lose correlation

Severity: **Medium/High**

`ReceiptLifecycleCoordinator.emitEmailReceiptDiagnostic(...)` creates `DiagnosticEvent` without `correlationId`.

So dedupe / validation / coordinator diagnostics emitted inside the coordinator do not share the email intake correlation.

Fix:
- Add `correlationId` parameter to `emitEmailReceiptDiagnostic`.
- Pass the `correlationId` from `processEmailReceipt(...)` into every call.

Tests:
```text
email_coordinator_dedupe_diagnostic_uses_email_correlation
email_coordinator_error_diagnostic_uses_email_correlation
```

---

## PRIV-43B-09 — Email side effects still lose correlation

Severity: **Medium**

In both ingestion service and coordinator, side effects are still called as:

```kotlin
dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT)
```

No correlation is passed despite comments saying it is.

Fix:
- Use overload:
  ```kotlin
  dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT, correlationId)
  ```
- If overload does not exist, add it.

Tests:
```text
email_side_effect_uses_email_correlation
email_expense_created_and_side_effect_share_correlation
```

---

## PRIV-43B-10 — Raw messageId still flows into duplicate detector

Severity: **Medium**

Coordinator calls:

```kotlin
duplicateDetector.checkDuplicate(
    externalSourceId = messageId.ifBlank { null }
)
```

where `messageId` is the raw message ID parameter from the service.

Even if not persisted today, this violates the “raw values only through payload policy” rule and could leak through future diagnostics or detector internals.

Fix:
- Use `messageIdHash` as external ID for duplicate detector.
- Rename variables to make semantics explicit:
  ```kotlin
  rawMessageIdEphemeral
  messageIdHashForPersistence
  ```

---

## PRIV-43B-11 — Email source stores hashed ID in plaintext field

Severity: **Medium**

Restricted modes store `messageIdHash` into:

```kotlin
EmailReceiptSource.emailMessageId
```

This works functionally but is semantically dangerous: future code may assume the column contains a raw message ID.

Fix:
- Add explicit column:
  ```text
  emailMessageIdHash
  ```
- Keep `emailMessageId` null in restricted modes.
- Migrate old hash-looking values if needed.

---

# 5. Retention issues

## PRIV-43B-12 — Retention worker may delete almost all email source rows

Severity: **High functional bug**

`DataRetentionWorker` does:

```kotlin
val emailCutoff = now - 30 days
```

but does not use it.

For all non-notification/OCR targets:

```kotlin
results += target.purge(now)
```

The `email_receipt_sources` target calls:

```kotlin
emailReceiptDao().deleteOlderThan(cutoffMs)
```

So with `cutoffMs = now`, it likely deletes every email source older than the current instant.

Impact:
- Loss of email receipt provenance.
- Possible dedupe/link trace breakage.
- Retention should purge/redact sensitive columns, not necessarily delete source rows.

Fix:
- Use per-target retention policy/cutoff.
- For email source target, redact/purge raw fields instead of deleting rows:
  ```text
  emailSender = null/redacted
  emailSubject = null/redacted
  emailMessageId = null
  keep emailMessageIdHash/fingerprint/receiptId/provider
  ```
- Use `emailCutoff`, not `now`.

Tests:
```text
retention_email_uses_email_cutoff_not_now
retention_email_redacts_sensitive_columns_not_delete_rows
retention_email_preserves_hashes_and_receipt_links
```

---

## PRIV-43B-13 — Retention target counts are inaccurate

Severity: **Medium**

Targets like:

```kotlin
ai_artifacts -> deleteExpired(cutoffMs); RetentionPurgeResult(name, 0, true)
ai_chat_messages -> deleteOlderThan(cutoffMs); RetentionPurgeResult(name, 0, true)
```

always report zero purged rows.

Impact:
- Audit counts are misleading.
- Support/debug cannot verify retention actually ran.

Fix:
- DAO delete methods should return affected row count.
- Store actual count in `RetentionPurgeResult`.

---

## PRIV-43B-14 — Retention registry still incomplete

Severity: **Medium/High**

Registered:
```text
raw_notifications
scanned_receipts.rawOcrText
ai_artifacts
ai_chat_messages
email_receipt_sources
```

Missing likely targets:
```text
scanned_receipts.parsedItems
debug exports
bank statement debug/import artifacts
pipeline_diagnostic_events metadata purge/redaction policy
operation_run_events metadata purge/redaction policy
privacy audit context if it contains old metadata
cloud call audit/payload artifacts
```

Tests are still mostly fake registry tests, not production registry + DAO behavior.

---

# 6. Cloud provider constructor / gate issues

## PRIV-43B-15 — Some main-source secondary constructors still allow all

Severity: **High**

Observed:
- `CloudCategorizationAssistService`
- `CloudDedupeJudgeService`
- `CloudReviewExplanationService`

still contain:

```kotlin
object : PrivacyGate {
    override suspend fun check(...) = PrivacyDecision.Allowed
}
```

Fix:
- Remove main-source allow-all constructors.
- Replace with fail-closed test constructors or test-source factories.
- Strengthen static guard to catch these.

---

## PRIV-43B-16 — Test constructors with `CompositePrivacyGate(emptyList(), NO_OP)` can fail open

Severity: **Medium**

`CloudReceiptItemCategorizationService` secondary constructor uses:

```kotlin
CompositePrivacyGate(emptyList(), PrivacyAuditLogger.NO_OP)
```

Since no `gateHandledCapabilities` are passed, this defaults to local-only allowed behavior.

Even if `@VisibleForTesting internal`, this is in main source.

Fix:
- Use fail-closed gate in all main-source testing constructors.
- Or pass `PrivacyCapabilityHandlingPolicy.gateHandledCapabilities`.

---

# 7. Test quality issues

## PRIV-43B-17 — Tests still mostly validate models/contracts, not real persistence

Severity: **High regression risk**

Examples:
- `CloudProviderPreparedPayloadTest` tests `DefaultCloudPayloadPolicy`, not actual providers.
- `EmailRawStorageEnforcementTest` tests `EmailReceiptPersistencePayload`, not ingestion/coordinator persistence.
- `NotificationPrivacyHardeningTest` simulates a cache/order, not `NotificationCaptureService`.
- `RetentionRegistryTest` uses fake targets and model-level payloads.

These tests would not catch:
- unmigrated cloud providers,
- receipt assist leaking line items outside prepared payload,
- refresh path blocked-package extraction,
- retention deleting all email source rows,
- coordinator diagnostics losing correlation.

Fix:
- Add provider-level tests with fake `CloudPayloadPolicy` that records calls and returns sentinel prepared text.
- Add in-memory/fake DAO tests for email/coordinator persistence.
- Add tests for actual `DataRetentionWorker` target cutoff behavior.
- Keep contract tests, but do not treat them as acceptance proof.

---

# 8. Acceptance matrix after 43b5cae

| Criterion | Status | Notes |
|---|---:|---|
| CI runs privacy guard | Partial | Added, but current code appears still violative or guard weak |
| All cloud providers use PreparedCloudPayload | Not done | Several providers still bypass |
| Receipt assist uses prepared full prompt/image | Not done | Only OCR text prepared |
| Item categorization uses prepared real prompt | Not done | Uses empty-string policy call as flag |
| Composite gate fail-closed wired | Mostly | Good |
| No allow-all gates in main | Not done | Several remain |
| Notification normal pre-extraction | Mostly | Improved |
| Notification refresh pre-extraction | Partial | Missing blocked package/shutdown order |
| Email restricted message ID hash | Partial | Functional but stored in raw-named column |
| Email payload model used in real path | Not done | Direct raw params remain |
| Email parsed items restricted | Mostly | Coordinator nulls/redacts |
| Email diagnostics correlation | Not done | coordinator helper lacks correlation |
| Retention registry | Partial | Exists but dangerous cutoff behavior |
| Retention email behavior | Risky | likely deletes source rows |
| Real persistence tests | Not done | mostly synthetic/model tests |

---

# Recommended next PR order

## PR 1 — Cloud provider migration completion

Fix:
```text
PRIV-43B-01
PRIV-43B-02
PRIV-43B-03
PRIV-43B-04
PRIV-43B-15
PRIV-43B-16
```

Goal:
```text
Every cloud provider uses PreparedCloudPayload for the final request body.
No main-source allow-all privacy gates remain.
CI guard truly catches raw provider requests.
```

## PR 2 — Notification refresh parity

Fix:
```text
PRIV-43B-05
PRIV-43B-06
```

Goal:
```text
Refresh path has same pre-extraction privacy/package/shutdown order as normal path.
```

## PR 3 — Email payload/coordinator cleanup

Fix:
```text
PRIV-43B-07
PRIV-43B-08
PRIV-43B-09
PRIV-43B-10
PRIV-43B-11
```

Goal:
```text
Real email path uses EmailReceiptPersistencePayload; diagnostics and side effects share email correlation.
```

## PR 4 — Retention safety

Fix:
```text
PRIV-43B-12
PRIV-43B-13
PRIV-43B-14
```

Goal:
```text
Retention purges/redacts sensitive fields with correct per-target cutoff and accurate counts.
```

## PR 5 — Real behavior tests

Fix:
```text
PRIV-43B-17
```

Goal:
```text
Tests exercise providers, service/coordinator persistence, and worker behavior, not only models.
```

---

# Highest-priority bug list

1. CloudWarranty/Categorization/Dedupe/Review providers still bypass `PreparedCloudPayload`.
2. Receipt assist prepares only OCR text but sends other raw fields.
3. Item categorization uses policy on empty string and uses `hashCode()` pseudonyms.
4. Retention worker likely deletes all email source rows by passing `now` to `deleteOlderThan`.
5. Main-source allow-all privacy gates remain.
6. Email coordinator diagnostics/side effects lose correlation.
7. Notification refresh path reads extras before blocked-package/shutdown checks.

---

# Sources checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/43b5cae0d43228a3d5b2f27ae34d9173e358e220

- `CloudReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `CloudReceiptItemCategorizationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt

- `CloudWarrantyExtractionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt

- `CloudCategorizationAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt

- `CloudDedupeJudgeService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt

- `CloudReviewExplanationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt

- `DefaultCloudPayloadPolicy.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/privacy/DefaultCloudPayloadPolicy.kt

- `CloudPayloadPolicy.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadPolicy.kt

- `NotificationCaptureService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `EmailReceiptIngestionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `ReceiptLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `DataRetentionWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `RetentionModule.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt

- `PrivacyModule.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt

- `verify_privacy_boundaries.py`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/43b5cae0d43228a3d5b2f27ae34d9173e358e220/scripts/verify_privacy_boundaries.py