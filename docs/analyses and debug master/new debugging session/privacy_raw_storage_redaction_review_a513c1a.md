# Privacy / Raw-Storage / Redaction Deep Review

Commit: `a513c1af21e043fc343903c5fc3f9337e18b3914`

## What looks fixed
- Fail-closed defaults were added for corruption.
- Raw storage is now modeled via `RawPersistencePolicy` / `RawPersistencePolicyResolver`.
- Notification capture has a privacy gate before extras extraction.
- Email message IDs are HMAC-hashed in the new path.
- Bank transaction persistence now hashes provider/account/counterparty IDs.
- Cloud calls now route through `EffectiveCloudAiPolicyResolver` / `CloudPayloadPolicy`.
- Export privacy is separated from backup policy.
- Data retention is registry-based.
- Static privacy guard script was added.

## Remaining confirmed issues

### 1) Corruption can still behave like first run
`PrivacySettingsRepositoryImpl` uses:

- `ReplaceFileCorruptionHandler { emptyPreferences() }`
- `observeLoadState()` maps empty prefs to `FirstRunDefault(...)`

That means a corrupted DataStore can still be observed as “first run” unless some other path explicitly detects corruption.  
Problem: `FirstRunDefault` restores the normal defaults, and those defaults still include:
- `notificationCaptureEnabled = true`
- `rawNotificationStorageMode = STORE_RAW`
- `rawOcrStorageMode = STORE_RAW`

So the fail-closed corruption story is not fully enforced on the observable path.  
This is the biggest remaining privacy bug.

### 2) `SafePrivacyMetadata` is still key-only safety
`SafePrivacyMetadata.Builder.put(key, value)` only checks whether the **key** contains blocked substrings.  
It does **not** scan/redact the **value**.

So a benign key like `note`, `context`, or `merchant` can still carry raw sensitive text if a caller passes it.  
Also:
- `putHash(key, hash)` does not validate that the key is an approved hash key
- it does not validate hash format either

This means the type is “safe-ish by convention,” not safe by construction.

### 3) Raw-storage coverage needs another audit pass
The commit adds a `RawPersistencePolicyResolver`, but the overall policy still needs verification across all newly introduced persistence targets:
- notification payloads
- pending review rows
- receipt/email source rows
- bank payloads
- diagnostics metadata
- debug/export paths
- retention targets

The architecture is much better, but the set of registered purge targets and the exact raw-vs-redacted behavior across all tables should be re-checked end-to-end.

## Risk assessment
- **High**: corruption path can still re-enable raw capture/storage behavior.
- **Medium/High**: unsafe metadata values can still leak through benign keys.
- **Medium**: retention/export/debug coverage may still miss some new sensitive surfaces.

## Recommended next fix order
1. Make corruption handling truly fail-closed on the observable settings path.
2. Add value-level sanitization to `SafePrivacyMetadata`.
3. Re-audit all raw-bearing persistence targets against the new policy matrix.
4. Add regression tests for corruption, benign-key leaks, and redacted/metadata-only modes.

## Sources checked
- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/a513c1af21e043fc343903c5fc3f9337e18b3914
- `PrivacySettings.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt
- `PrivacySettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
- `RawPersistencePolicyResolver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/RawPersistencePolicyResolver.kt
- `NotificationCaptureGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationCaptureGate.kt
- `SafePrivacyMetadata.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadata.kt
- `CloudPayloadPolicy.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadPolicy.kt
- `ExportPrivacyGate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a513c1af21e043fc343903c5fc3f9337e18b3914/app/src/main/java/com/yourname/expensetracker/domain/privacy/ExportPrivacyGate.kt