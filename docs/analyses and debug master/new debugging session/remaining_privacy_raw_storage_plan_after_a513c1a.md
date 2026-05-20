# Remaining Privacy / Raw-Storage / Redaction Implementation Plan

Target commit: `a513c1af21e043fc343903c5fc3f9337e18b3914`

Scope: only the remaining issues from the latest privacy/raw-storage review.

## Remaining issue clusters

1. `PrivacySettings` corruption still looks like first run on the observable path.
2. `SafePrivacyMetadata` is key-safe, but not value-safe by construction.
3. Raw-storage policy coverage still needs an end-to-end audit across all persisted targets and cloud/debug/export surfaces.

---

# PR 1 — Make corruption truly fail-closed

## Problem

`PrivacySettingsRepositoryImpl` still maps empty prefs to `FirstRunDefault`, so a corrupted DataStore can still look like a normal first-run state. That can re-enable permissive defaults like:

- notification capture ON
- raw notification storage STORE_RAW
- raw OCR storage STORE_RAW

## Files

- `data/privacy/PrivacySettingsRepositoryImpl.kt`
- `domain/privacy/PrivacySettings.kt`
- `ui/screens/privacysettings/PrivacySettingsViewModel.kt`
- tests for privacy load state and viewmodel

## Implementation steps

### 1. Add an explicit persisted corruption marker
Do not rely on `emptyPreferences()` alone.

Use a sentinel preference key, e.g.

```kotlin
val LOAD_STATE = stringPreferencesKey("_privacy_load_state")
```

Possible values:
- `FIRST_RUN`
- `NORMAL`
- `CORRUPTED`

The corruption handler must return preferences containing `CORRUPTED`, not empty prefs.

### 2. Separate first-run from corruption
Load-state decoder must obey this precedence:

1. `CORRUPTED` marker -> `CorruptedFailClosed`
2. no initialized marker and empty prefs -> `FirstRunDefault`
3. otherwise -> `Loaded`

### 3. Make fail-closed the only corruption fallback
When corruption is detected, always return:

- `PrivacySettings.FAIL_CLOSED_DEFAULTS`

Never return normal defaults from the corruption path.

### 4. Keep UI warning wired to `CorruptedFailClosed`
`PrivacySettingsViewModel` should surface `showCorruptionWarning` only for `CorruptedFailClosed`.

### 5. Add init marker on successful first write
Persist a separate `INITIALIZED` marker after the first successful settings write so future empty prefs are not misclassified.

## Acceptance tests

- `privacy_load_state_first_run_is_first_run_default`
- `privacy_load_state_corruption_maps_to_corrupted_fail_closed`
- `corrupted_settings_use_fail_closed_defaults`
- `first_run_does_not_use_fail_closed_defaults`
- `privacy_viewmodel_shows_corruption_warning_for_corruption`

---

# PR 2 — Make SafePrivacyMetadata safe by construction

## Problem

`SafePrivacyMetadata` currently redacts based on keys, but it does not sanitize values. That means a benign key like `note`, `context`, or `merchant` can still carry raw sensitive text.

It also needs stronger rules for:
- `putHash(...)`
- nested maps/lists/JSON
- merge/toJson round-trips

## Files

- `domain/privacy/SafePrivacyMetadata.kt`
- optionally a shared sanitizer helper under `domain/privacy`
- tests for privacy metadata

## Implementation steps

### 1. Add value-level sanitization
`put(key, value)` must sanitize the value, not only the key.

Use a shared sanitizer that:
- truncates long strings
- redacts tokens, JWT-like strings, IBANs, account/card numbers, file paths, prompts, bodies, and raw OCR-like text
- recursively sanitizes `Map`, `Iterable`, `Array`, `JSONObject`, and `JSONArray`
- safely handles unknown object types by sanitizing `toString()`

### 2. Harden `putHash`
`putHash(key, hash)` must only accept approved hash keys.

If the key is not approved, store `[REDACTED]`.

If the key is approved, only accept a hash-like value.

### 3. Make `merge(...)` re-sanitize
Merged metadata must be sanitized again so unsafe values cannot sneak in through composition.

### 4. Make `toJson()` safe
`toJson()` must serialize only already-sanitized primitives/collections.

Never serialize raw nested objects directly.

### 5. Add a shared policy helper
Prefer a reusable helper similar to the diagnostics sanitizer so privacy and diagnostics do not drift into two different safety rules.

## Acceptance tests

- `safe_metadata_redacts_sensitive_value_under_benign_key`
- `safe_metadata_redacts_nested_sensitive_values`
- `safe_metadata_redacts_json_array_values`
- `safe_metadata_unknown_object_to_string_is_sanitized`
- `safe_metadata_put_hash_rejects_unapproved_key`
- `safe_metadata_put_hash_rejects_plaintext_value`
- `safe_metadata_merge_preserves_sanitization`
- `safe_metadata_to_json_contains_no_raw_sensitive_values`

---

# PR 3 — End-to-end raw-storage policy audit

## Problem

The policy layer exists, but every raw-bearing persistence surface still needs a full audit to prove the policy is actually enforced everywhere.

## Files to audit

- `domain/privacy/RawPersistencePolicyResolver.kt`
- `domain/privacy/NotificationCaptureGate.kt`
- `domain/privacy/NotificationPersistencePayload.kt`
- `domain/privacy/ReceiptPersistencePayload.kt`
- `domain/privacy/EmailReceiptPersistencePayload.kt`
- `domain/privacy/BankTransactionPersistencePayload.kt`
- `domain/privacy/CloudPayloadPolicy.kt`
- `data/privacy/DefaultCloudPayloadPolicy.kt`
- `data/privacy/DefaultSensitiveHashingService.kt`
- `data/repository/NotificationProcessingPipeline.kt`
- `data/email/EmailReceiptIngestionService.kt`
- `data/ai/provider/*`
- `data/repository/WarrantyTrackerRepository.kt`
- `data/privacy/DataRetentionWorker.kt`
- `domain/privacy/ExportPrivacyGate.kt`
- backup/debug/export paths
- diagnostics metadata and audit logging

## Implementation steps

### 1. Build a source-by-source policy matrix
For each source type, verify the allowed transform under each mode:

- `STORE_RAW`
- `STORE_REDACTED`
- `STORE_METADATA_ONLY`
- `DO_NOT_STORE`

Sources to cover:
- notification
- receipt OCR
- email receipt
- bank statement
- bank API
- AI artifacts
- debug/export artifacts

### 2. Enforce policy at every persistence boundary
No DAO or mapper should accept raw data unless it has already passed through a source-specific payload builder.

If a surface can carry raw content, it must go through:
- `NotificationPersistencePayload`
- `ReceiptPersistencePayload`
- `EmailReceiptPersistencePayload`
- `BankTransactionPersistencePayload`
- `PreparedCloudPayload`
- safe diagnostics metadata

### 3. Re-check cloud payload usage
All cloud providers must use:
- `EffectiveCloudAiPolicyResolver`
- `CloudPayloadPolicy`
- `PreparedCloudPayload`

No provider should decide privacy from caller flags alone.

### 4. Re-check export/debug/backup paths
Verify:
- raw export is explicitly gated
- debug export does not leak raw content unless allowed
- backup metadata does not expose raw sensitive values
- support/debug outputs use sanitized metadata only

### 5. Re-check retention registry
Every sensitive target introduced by the new policy must be in the retention registry.

## Acceptance tests

- notification DO_NOT_STORE writes no plaintext body/text anywhere
- receipt OCR DO_NOT_STORE writes no raw text anywhere
- email metadata-only mode stores no plaintext subject/sender/body/message ID
- bank redaction mode stores no raw bank description/reference/account ID
- cloud payloads are always prepared through policy
- debug/export/backup paths obey privacy policy
- retention registry includes every sensitive target

---

# PR 4 — Static guards and regression matrix

## Problem

The policy needs regression locks so future code cannot bypass it.

## Files

- `scripts/verify_privacy_boundaries.py`
- `docs/privacy/raw-storage-policy.md`
- `test/privacy/*`
- any helper scripts or CI config

## Implementation steps

### 1. Expand static guard rules
Add checks that reject:
- direct raw-value persistence outside approved payload builders
- direct cloud requests without `PreparedCloudPayload`
- use of `AiSettings.redactBeforeCloud` or caller flags as privacy authority
- raw email/notification/OCR/bank text written directly to DAOs
- any remaining `hashCode()`-based identifier hashing

### 2. Add matrix tests
Create tests that cover:
- every `RawSourceType`
- every `RawStorageMode`
- raw vs redacted vs metadata-only vs do-not-store
- cloud payload redaction behavior
- debug/export/backup gating
- retention coverage

### 3. Document the final policy
Update `docs/privacy/raw-storage-policy.md` with:
- source/mode matrix
- allowed raw fields
- hashed identifier rules
- forbidden persistence surfaces
- export/debug/backup rules

## Acceptance tests

- `privacy_guard_fails_on_raw_sensitive_persistence`
- `privacy_guard_fails_on_unprepared_cloud_payload`
- `privacy_guard_fails_on_hashcode_identifier_hashing`
- `raw_storage_mode_matrix_is_covered_for_all_sources`
- `retention_registry_covers_all_sensitive_targets`

---

# Recommended execution order

```text
PR 1  Fix corruption -> fail-closed for real
PR 2  Make SafePrivacyMetadata safe by construction
PR 3  Audit and enforce raw-storage policy end-to-end
PR 4  Static guards, docs, and regression tests
```

---

# Definition of done

The privacy/raw-storage work is complete when:

1. corruption can never be misclassified as normal first-run permissive state,
2. `SafePrivacyMetadata` cannot carry raw sensitive values through benign keys,
3. every raw-bearing persistence target is routed through policy-aware payload builders,
4. every cloud provider uses prepared payloads only,
5. export/debug/backup/retention paths obey the same privacy policy,
6. static guards fail the build on bypass attempts,
7. regression tests prove raw data is not persisted under `STORE_REDACTED`, `STORE_METADATA_ONLY`, or `DO_NOT_STORE`.

---

# Sources checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/a513c1af21e043fc343903c5fc3f9337e18b3914

- `PrivacySettings.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt

- `PrivacySettingsRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `SafePrivacyMetadata.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadata.kt

- `RawPersistencePolicyResolver.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawPersistencePolicyResolver.kt

- `CloudPayloadPolicy.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadPolicy.kt

- `NotificationCaptureGate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationCaptureGate.kt