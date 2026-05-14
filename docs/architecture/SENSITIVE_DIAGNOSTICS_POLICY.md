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
- Controlled by `PrivacyGate` (RAWBACKUP_EXPORT, ENCRYPTED_BACKUP)
- Raw export requires explicit user permission
- Redacted export strips sensitive fields

### Privacy audit
- All privacy gate decisions are logged to `PrivacyAuditEvent`
- Audit context is sanitized (max 200 chars, allowlisted keys only)

## Enforcement

- `PrivacyCapabilityHandlingPolicyTest` ensures all capabilities have explicit policy
- `TIMBER_PII_LOGGING` capability is LOCAL_ONLY (no gate needed, just policy)
- Debug data persistence gated by `debugDataPersistenceEnabled` setting
