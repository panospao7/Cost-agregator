# U-PR5 — RawStorageMode / Privacy Contract

## 1. Issue Summary

| ID | Priority | Title |
|----|----------|-------|
| U-PRIVACY-01 | P0 | RawStorageMode semantics inconsistent — notification uses `rawNotificationStorageMode`, OCR uses `rawOcrStorageMode`, email uses `rawOcrStorageMode` (wrong!), bank statement ignores it entirely |
| U-PRIVACY-02 | P1 | `EffectiveCloudAiPolicy` exists but not wired as authoritative gate for cloud services |
| U-PRIVACY-03 | P1 | Retention/export redaction scope covers only 2 tables |

**Affected Pipelines:** 1, 3, 7, 8, 10, 11, 12

## 2. Root Cause Analysis

### U-PRIVACY-01
`PrivacySettings` defines three distinct storage modes:
- `rawNotificationStorageMode` — for notification content
- `rawOcrStorageMode` — for OCR text from receipts
- `emailReceiptStorageMode` — for email receipt raw content

**Current usage:**
- `NotificationIntakeCoordinator.capture()` — receives `rawStorageMode` as parameter (caller passes `rawNotificationStorageMode`) ✓ CORRECT
- `BankStatementLifecycleProcessor` — uses `privacySettingsRepository.getSettings().rawOcrStorageMode` for OCR text ✓ CORRECT for OCR, but **ignores** any bank-statement-specific mode for the raw description/reference fields
- `BankApiIntegration.mapTransactionToExpense()` — uses `settings.rawOcrStorageMode` for bank transaction description/reference — **WRONG**, should use a dedicated `bankStatementStorageMode` or at minimum `emailReceiptStorageMode`
- `EmailReceiptIngestionService` — delegates to `ReceiptLifecycleCoordinator.processEmailReceipt()` which uses... needs verification

The core issue: there is no `rawBankStatementStorageMode` in `PrivacySettings`, and `BankApiIntegration` reuses `rawOcrStorageMode` which is semantically incorrect (bank API data is not OCR output).

### U-PRIVACY-02
`EffectiveCloudAiPolicyResolver` exists and correctly computes a composite policy from both `PrivacySettings` and `AiSettings`. However:
- It is not injected into any of the cloud-calling services as an authoritative gate
- Individual services check `PrivacyGate` capabilities independently, which may diverge from the composite policy
- The `EffectiveCloudAiPolicy.requireAllowed()` method exists but is never called in production code

### U-PRIVACY-03
`DataRetentionWorker` uses `RetentionRegistry.allTargets()` which covers:
- `raw_notifications` — notification raw content
- `scanned_receipts.rawOcrText` — OCR text
- `email_receipt_sources` — email fields (redact not delete)
- `ai_chat_messages` — chat text
- `ai_artifacts` — AI generated text
- `notification_intake` — intake raw content
- `pipeline_diagnostic_events` — diagnostic PII

`ExportAnonymizer.sanitizeExport()` covers:
- `scanned_receipts` ✓
- `raw_notifications` ✓
- `notification_intake` ✓
- `ai_artifacts` ✓
- `ai_chat_messages` ✓
- `merchant_locations` ✓
- `email_receipt_sources` ✓

**Gap:** The retention worker covers 7 targets. The export anonymizer covers 7 tables. The issue description says "only 2 tables" — this appears to have been partially fixed already. Remaining gaps:
- `bank_statement_import_items` — contains merchant names from bank statements (not redacted)
- `pending_reviews` — contains `notificationText`, `notificationTitle` (not redacted in export)
- `expenses` — contains `notes` field (user-entered free text, not redacted)
- `background_job_runs` — contains `errorMessage` which may leak PII

## 3. Affected Files

| File | Changes Required |
|------|-----------------|
| `PrivacySettings.kt` | Add `rawBankStatementStorageMode: RawStorageMode` field |
| `BankApiIntegration.kt` | Use new `rawBankStatementStorageMode` instead of `rawOcrStorageMode` |
| `EmailReceiptIngestionService.kt` | Verify correct mode is passed to coordinator |
| `EffectiveCloudAiPolicy.kt` | No structural changes needed |
| `ReceiptLifecycleCoordinator.kt` | Wire `EffectiveCloudAiPolicy` as pre-check for cloud OCR |
| `DataRetentionWorker.kt` | Add retention targets for remaining PII tables |
| `ExportAnonymizer.kt` | Add redaction for `pending_reviews`, `bank_statement_import_items` |
| `NotificationIntakeCoordinator.kt` | Already correct — no changes |
| `BankStatementLifecycleProcessor.kt` | Use `rawBankStatementStorageMode` for non-OCR fields |
| `RawContentSanitizer.kt` | Add `sanitizeBankDescription()` method |

## 4. Verification of Issues in Source

### U-PRIVACY-01 — CONFIRMED
- `BankApiIntegration.kt` line ~340: `val mode = settings.rawOcrStorageMode  // bank API uses OCR storage mode` — explicit comment acknowledges the wrong mode
- No `rawBankStatementStorageMode` exists in `PrivacySettings`
- `EmailReceiptIngestionService` delegates entirely to `ReceiptLifecycleCoordinator` — the coordinator handles storage mode. The service itself doesn't store raw content directly. **Partially confirmed** — the email path is correct by delegation.

### U-PRIVACY-02 — CONFIRMED
- `EffectiveCloudAiPolicyResolver` is `@Singleton` and `@Inject`-able but grep shows no injection into cloud-calling services
- `requireAllowed()` exists but is unused in production paths

### U-PRIVACY-03 — PARTIALLY CONFIRMED
- Export anonymizer covers 7 tables (more than "2" stated in issue)
- Retention registry covers 7 targets
- Remaining gaps: `pending_reviews`, `bank_statement_import_items`, `background_job_runs`

## 5. Implementation Plan

### U-PRIVACY-01 Fix

**Phase 1: Add dedicated bank statement storage mode**

```kotlin
// PrivacySettings.kt — add field
data class PrivacySettings(
    // ... existing fields ...
    val rawBankStatementStorageMode: RawStorageMode = RawStorageMode.STORE_REDACTED,
    // ...
)
```

**Phase 2: Add sanitizer method**

```kotlin
// RawContentSanitizer.kt
fun sanitizeBankDescription(text: String?, mode: RawStorageMode): String? = when (mode) {
    RawStorageMode.STORE_RAW -> text
    RawStorageMode.STORE_REDACTED -> if (text != null) "[REDACTED]" else null
    RawStorageMode.STORE_METADATA_ONLY -> null
    RawStorageMode.DO_NOT_STORE -> null
}
```

**Phase 3: Wire into BankApiIntegration**

```kotlin
// BankApiIntegration.mapTransactionToExpense()
val mode = settings.rawBankStatementStorageMode  // FIX: use correct mode
val safeDescription = RawContentSanitizer.sanitizeBankDescription(transaction.description, mode)
val safeReference = RawContentSanitizer.sanitizeBankDescription(transaction.reference, mode)
```

**Phase 4: Wire into BankStatementLifecycleProcessor for non-OCR fields**

The processor already uses `rawOcrStorageMode` for OCR text (correct). For any bank-specific metadata stored alongside, use `rawBankStatementStorageMode`.

### U-PRIVACY-02 Fix

**Strategy:** Inject `EffectiveCloudAiPolicyResolver` into cloud-calling services and call `resolve()` + `requireAllowed()` before any cloud API call.

Key integration points:
1. `GenerateDashboardBriefingUseCase` — before calling Gemini API
2. Receipt OCR cloud path — before uploading image to cloud
3. Any future cloud categorization service

```kotlin
// Example integration in a cloud-calling use case:
val policy = effectiveCloudAiPolicyResolver.resolve()
policy.requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)
if (policy.redactBeforeCloud) {
    // redact sensitive fields before sending
}
```

### U-PRIVACY-03 Fix

**Phase 1: Expand ExportAnonymizer**

```kotlin
// Add to ExportAnonymizer.sanitizeExport():
val pendingReviewsPurged = sanitizePendingReviews(db)
val bankItemsPurged = sanitizeBankStatementItems(db)

private fun sanitizePendingReviews(db: SQLiteDatabase): Int {
    if (!tableExists(db, "pending_reviews")) return 0
    val where = "notificationText IS NOT NULL OR notificationTitle IS NOT NULL"
    val count = countWhere(db, "pending_reviews", where)
    if (count == 0) return 0
    db.execSQL("UPDATE pending_reviews SET notificationText = NULL, notificationTitle = NULL WHERE $where")
    return count
}

private fun sanitizeBankStatementItems(db: SQLiteDatabase): Int {
    if (!tableExists(db, "bank_statement_import_items")) return 0
    val where = "merchant IS NOT NULL"
    val count = countWhere(db, "bank_statement_import_items", where)
    if (count == 0) return 0
    db.execSQL("UPDATE bank_statement_import_items SET merchant = '[REDACTED]' WHERE $where")
    return count
}
```

**Phase 2: Add retention targets**

Register new `RetentionTarget` implementations for:
- `pending_reviews.notificationText` — purge after notification retention period
- `background_job_runs.errorMessage` — purge after 30 days

## 6. Execution Order

1. **U-PRIVACY-01** (P0) — Add `rawBankStatementStorageMode`, wire into BankApiIntegration
2. **U-PRIVACY-03** (P1) — Expand export anonymizer and retention targets
3. **U-PRIVACY-02** (P1) — Wire `EffectiveCloudAiPolicy` as authoritative gate

## 7. Testing Strategy

### Unit Tests
- `RawContentSanitizerTest`: Add tests for `sanitizeBankDescription()` across all modes
- `BankApiIntegrationTest`: Verify `rawBankStatementStorageMode` is used (not `rawOcrStorageMode`)
- `ExportAnonymizerTest`: Add assertions for `pending_reviews` and `bank_statement_import_items` redaction
- `EffectiveCloudAiPolicyResolverTest`: Verify `requireAllowed()` throws when cloud is disabled

### Integration Tests
- End-to-end bank sync with `DO_NOT_STORE` mode → verify no raw descriptions persisted
- Export with redaction → verify all PII tables are sanitized
- Cloud OCR attempt with cloud disabled → verify `SecurityException` from policy gate

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Adding field to PrivacySettings breaks DataStore serialization | Medium | High | Use default value; DataStore handles missing fields gracefully |
| Existing bank sync data has raw descriptions | High | Low | Retention worker will purge over time; no retroactive redaction needed |
| EffectiveCloudAiPolicy gate blocks legitimate cloud calls | Low | Medium | Gate only blocks when BOTH privacy AND AI settings disable cloud |

## 9. Rollback Plan

- `rawBankStatementStorageMode` has a default value, so removing it later won't break existing data
- Export anonymizer additions are additive — removing them just means less redaction
- Policy gate can be bypassed by removing the `requireAllowed()` call

## 10. Dependencies

- Database migration needed if `rawBankStatementStorageMode` is persisted in Room (it's in DataStore, so no migration)
- `PrivacySettingsScreen` UI needs a new toggle for bank statement storage mode
- `FAIL_CLOSED_DEFAULTS` needs the new field set to `DO_NOT_STORE`

## 11. Migration / Data Impact

- No Room database migration required (PrivacySettings is in DataStore)
- DataStore will use default value (`STORE_REDACTED`) for existing installations
- No retroactive data changes — new mode applies to future bank syncs only

## 12. Performance Impact

- Negligible — one additional `when` branch per bank transaction during sync
- Export anonymizer adds 2 more UPDATE queries (bounded by table size)
- Policy resolver is cached per-call (suspend function, not reactive)

## 13. Documentation Updates

- Update `docs/privacy/raw-storage-policy.md` with bank statement mode semantics
- Add `rawBankStatementStorageMode` to privacy settings documentation
- Document `EffectiveCloudAiPolicy` as the authoritative cloud gate in architecture docs

## 14. Acceptance Criteria

- [ ] `BankApiIntegration` uses `rawBankStatementStorageMode` (not `rawOcrStorageMode`)
- [ ] `PrivacySettings` has `rawBankStatementStorageMode` field with `STORE_REDACTED` default
- [ ] `RawContentSanitizer` has `sanitizeBankDescription()` method
- [ ] `ExportAnonymizer` redacts `pending_reviews` and `bank_statement_import_items`
- [ ] `EffectiveCloudAiPolicyResolver` is injected and called before cloud API usage
- [ ] `FAIL_CLOSED_DEFAULTS` sets new field to `DO_NOT_STORE`
- [ ] Unit tests cover all new paths
- [ ] Privacy settings UI exposes the new bank statement storage mode toggle
