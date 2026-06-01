# Sensitive Diagnostics & Logging Policy

## Rule

> No release UI or logs should expose raw merchant names, financial queries, addresses, OCR text, notification content, or financial totals to external observers.

## What is sensitive

| Data type | Examples | Allowed in release logs? |
|-----------|----------|--------------------------|
| Merchant name | "ΣΚΛΑΒΕΝΙΤΗΣ", "Amazon" | ❌ No |
| Financial amount | "€1,234.56" | ❌ No |
| Address/location | "Γλυφάδα, Αττική" | ❌ No |
| OCR text | Receipt content | ❌ No |
| Notification text | Bank notification body | ❌ No |
| API keys | Gemini key | ❌ No |
| User queries | "how much did I spend at..." | ❌ No |
| Category names | "Food", "Transport" | ✅ Yes (not PII) |
| Error codes | "RATE_STALE", "FK_CONSTRAINT" | ✅ Yes |
| Counts | "3 expenses", "2 receipts" | ✅ Yes |

## Implementation rules

### Timber logging
- Use `Timber.d()` for sensitive data (stripped in release)
- Use `Timber.w()`/`Timber.e()` only with sanitized messages
- Never log: amounts, merchants, addresses, OCR, notification text

### UI error messages
- Show generic user-facing messages: "Failed to save expense"
- Do NOT show: "Failed to save €45.50 at Lidl"
- Use `UiText.StringResource` for user-facing errors

### Debug screen
- Gated by `BuildConfig.DEBUG` (NavigationDestination.Debug)
- May show sensitive data for development
- Must not be accessible in release builds

### Export/backup
- Controlled by `PrivacyGate` (RAWBACKUP_EXPORT, ENCRYPTED_BACKUP, EXPENSE_EXPORT_RAW)
- Raw export requires explicit user permission (debugDataPersistenceEnabled)
- Redacted export strips sensitive fields; CsvCellSanitizer neutralizes formula injection

### Privacy audit
- All privacy gate decisions are logged to `PrivacyAuditEvent`
- Audit context is typed (`PrivacyAuditContext`) — raw-sensitive fields (prompt, rawText) are intentionally absent from the model
- Context is sanitized (max 200 chars, allowlisted keys only via `SafePrivacyMetadata`)
- `PrivacyAuditLogger.logDecision()` and `logCloudCall()` accept typed audit context

### ML data at rest
- Sensitive ML model data (classifier features, training corpora) encrypted via `AtRestEncryptionService` (AES-256-GCM via Android Keystore)
- `SecureKeyStorage` manages encryption keys (never logged, never exposed)
- `TransactionClassifier` and `ExpenseCategoryClassifier` use encryption for all on-device ML data

### Exception message redaction
- All exception messages written to durable storage are sanitized by `EventMetadataSanitizer.sanitizeExceptionMessage()`
- Patterns redacted (via `sanitizeStringValue()`):
  - Digit sequences (12+), IBANs, JWT tokens, Bearer authorization headers → `[REDACTED]`
  - File paths (`/path/to/file`, `C:\path`) → `[PATH]`
  - URLs and email addresses are NOT explicitly matched (caught incidentally if at all)
  - Any value matching blocked key substrings (`raw`, `ocr`, `prompt`, `token`, `secret`, etc.)
- Messages truncated to `MAX_STRING_LENGTH` (256 chars)
- Null messages remain null (no redaction needed)

> Note: Separate from `EventMetadataSanitizer`, the `ReceiptRepository.debugReceipt()` and PII sanitizers
> in `CloudPiiSanitizer` / input builders use `[REDACTED_URI]`, `[REDACTED_PATH]`, `[REDACTED_URL]`,
> `[REDACTED_EMAIL]` etc. for different purposes (UI debug export vs. durable storage).

## Enforcement

- `PrivacyCapabilityHandlingPolicyTest` ensures all capabilities have explicit policy
- `TIMBER_PII_LOGGING` capability is LOCAL_ONLY (no gate needed, just policy)
- Debug data persistence gated by `debugDataPersistenceEnabled` setting
- `EventMetadataSanitizer` verified in `GlobalDurableDiagnosticsGoldenTest` and `DurableDiagnosticsAcceptanceTest`
- `SafePrivacyMetadata` verified in privacy contract tests

### Recent fixes

- Exception message redaction added to `EventMetadataSanitizer.sanitizeExceptionMessage()` — previously exception messages were written unsanitized to OperationRun and BackgroundJobRun tables
- `SafePrivacyMetadata` hardened: hash-key allowlist is exact-only (no prefix bypass), unknown `*Hash` keys are rejected, value patterns scanned for tokens, paths, IBANs, large blobs
- `PrivacyAuditContext` introduced as typed replacement for raw `Map<String, String>` — sensitive fields (prompt, rawText) are structurally absent from the model
