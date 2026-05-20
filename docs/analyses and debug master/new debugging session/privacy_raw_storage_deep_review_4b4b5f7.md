# Privacy / Raw-Storage / Redaction Deep Review

Commit: `4b4b5f7a223fe44df0e39be72cca8490b0e45297`

## Verdict
Good progress. The main corruption-fail-closed path is now much better, and `SafePrivacyMetadata` is substantially safer.  
But **two real gaps remain**:

1. `SafePrivacyMetadata.put()` is still a bypass for approved hash-key names.
2. The raw-storage “audit” is still mostly **synthetic/model-level**, not end-to-end across real persistence surfaces.

## Fixed well
- Corruption now uses a sentinel and maps to fail-closed defaults.
- First-run is distinguished from corruption.
- `updateSettings()` now persists `NORMAL` and applies the actual persisted value.
- `SafePrivacyMetadata` now sanitizes values, not just keys.
- `putHash()` rejects unapproved keys and non-hash values.
- `merge()` re-sanitizes.
- Static guard checks for the corruption sentinel and value-safety marker.
- Docs were updated.

## Remaining issues

### 1) Hash-key bypass still exists through `put()`
Severity: **High**

`putHash()` is safe, but `put()` still accepts keys like:
- `messageIdHash`
- `providerTransactionIdHash`
- `accountIdHash`
- `sourceIdHash`

If a caller uses `put("messageIdHash", "plaintext")`, the value is **not** automatically treated as a hash-key violation unless it matches a sensitive pattern. So the object is not fully safe-by-construction yet.

Why this matters:
- the safety guarantee depends on callers remembering to use `putHash()`
- `merge()` cannot repair a plaintext value already stored under an approved hash key

Fix:
- make `put()` key-aware for approved hash-key names
- or reject any raw value written under approved hash-key names unless it looks like a real hash

### 2) The raw-storage audit is still not truly end-to-end
Severity: **Medium/High**

`RawStoragePolicyAuditTest` mainly validates:
- payload builders
- policy objects
- a small hardcoded retention set

It does **not** prove the real app surfaces are covered, such as:
- notification repository writes
- OCR/receipt persistence
- email ingestion
- bank pipeline writes
- export/debug/backup artifacts
- retention worker registry completeness

So the audit is useful, but not yet a full behavioral proof.

Fix:
- add integration tests for real repositories/DAOs/services
- enumerate the actual retention registry, don’t just assert a small literal set
- verify no raw value reaches persisted rows under `DO_NOT_STORE` / `STORE_METADATA_ONLY`

### 3) Corruption handling still needs a real integration test
Severity: **Medium**

The load-state tests shown are mostly fake-repository tests. The production sentinel logic should be verified with a real corrupted DataStore/temporary file test, not only a fake abstraction.

Fix:
- add a real DataStore corruption test
- assert that actual corruption writes the sentinel and loads `CorruptedFailClosed`

### 4) Static privacy guard is still heuristic
Severity: **Medium**

The Python guard helps, but it is pattern-based. It does not guarantee runtime enforcement across all persistence paths. It can be bypassed by future refactors or new field names.

Fix:
- keep the guard, but add behavior tests as the real protection
- treat the script as regression detection, not the source of truth

## Recommended next PRs
1. Make `put()` reject plaintext under approved hash-key names.
2. Replace the synthetic raw-storage audit with real integration coverage.
3. Add a real DataStore corruption test.
4. Expand retention/export coverage to actual app registries and persistence surfaces.

## Sources checked
- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/4b4b5f7a223fe44df0e39be72cca8490b0e45297
- `PrivacySettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4b4b5f7a223fe44df0e39be72cca8490b0e45297/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
- `SafePrivacyMetadata.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4b4b5f7a223fe44df0e39be72cca8490b0e45297/app/src/main/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadata.kt
- `PrivacySettingsLoadStateTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4b4b5f7a223fe44df0e39be72cca8490b0e45297/app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacySettingsLoadStateTest.kt
- `RawStoragePolicyAuditTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4b4b5f7a223fe44df0e39be72cca8490b0e45297/app/src/test/java/com/yourname/expensetracker/domain/privacy/RawStoragePolicyAuditTest.kt
- `SafePrivacyMetadataValueSafetyTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4b4b5f7a223fe44df0e39be72cca8490b0e45297/app/src/test/java/com/yourname/expensetracker/domain/privacy/SafePrivacyMetadataValueSafetyTest.kt
- `verify_privacy_boundaries.py`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4b4b5f7a223fe44df0e39be72cca8490b0e45297/scripts/verify_privacy_boundaries.py