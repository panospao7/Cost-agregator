# Sensitive Diagnostics & Logging Policy

> Last updated: 2026-09-21 (re-verified against code; PII guard enforced blocking in CI — re-checked 2026-09-21)

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
- Verified: `MainActivity` renders `DebugScreen` only when `BuildConfig.DEBUG`; otherwise it immediately navigates back

### Worker diagnostics (durable)

All background-work diagnostics use controlled, structured fields — never raw payloads:

- `WorkerRunLogger` (`domain/workers/`) writes run start/success/retry/failure rows to `BackgroundJobRunDao` (BackgroundJobRun table) with reason codes only.
- `WorkerReasonCodes` (PR12J-1, `domain/workers/`) is the central exception → reason-code mapper. Codes are constrained to `[A-Z0-9_]{1,80}` controlled constants from `DiagnosticReasonCode` (e.g. `WORKER_TIMEOUT`, `WORKER_CANCELLED`, `WORKER_PRIVACY_DENIED`, `WORKER_NOTIFICATION_PERMISSION_DENIED`) — never raw exception messages, file paths, or PII.
- `DiagnosticEventWriter` (`domain/diagnostics/`) writes typed `DiagnosticEvent` rows (pipeline, stage, outcome, severity, `DiagnosticReasonCode`, entity type/id, `sourceIdHash`, correlation/causation IDs, counts/booleans, `SafeEventMetadata`) to `PipelineDiagnosticEventDao`.
- Terminal DB-write failures fall back to `WorkerTerminalDiagnosticSink` (PR12H-3); the durable implementation `FileWorkerTerminalDiagnosticSink` (PR12I-1) persists bounded JSONL records (workerName, runId, correlationId, workId, runAttempt, intendedStatus, reasonCode, failureCode, `errorClass` — exception class name only, timestamp) and must never throw.

### SafeDiagnostic typed error contract

- `SafeDiagnostic` (`diagnostics/SafeDiagnosticReporter.kt`, root `diagnostics` package — not `domain/diagnostics/`) is a typed diagnostic value for safe error reporting: `reasonCode` (fixed enum-style code, e.g. `BACKUP_CREATE_FAILED`), `stage`, `Severity`, `exceptionClass` (class name only, never the message), `retryable`.
- Built via the `safeDiagnostic()` factory, it structurally enforces the same sanitization principles as the durable writers: bounded controlled fields only — no exception messages, file paths, SQL errors, OCR/notification text, or amounts can be carried.
- Example: `ExportAnonymizer.sanitizeExport()` maps a database-open failure to `safeDiagnostic("EXPORT_ANONYMIZE_FAILED", "EXPORT", Severity.ERROR, e)` and throws `IllegalStateException(diag.reasonCode, e)` — the propagated exception message is the controlled reason code only.

### Export/backup
- Controlled by `PrivacyGate` via `ExportPrivacyGate` (`domain/privacy/ExportPrivacyGate.kt`, PR8): dedicated capabilities `EXPENSE_EXPORT`, `EXPENSE_EXPORT_REDACTED`, `EXPENSE_EXPORT_ENCRYPTED`, `EXPENSE_EXPORT_RAW`, `DEBUG_RAW_EXPORT`, `RAW_DATABASE_EXPORT`. `RAWBACKUP_EXPORT` is denied in every branch (sole-owner per the `PrivacyGate` contract KDoc).
- Raw export requires explicit user permission (debugDataPersistenceEnabled) and/or debug build.
- Redacted export strips sensitive fields; `CsvCellSanitizer` (`domain/export/`) neutralizes formula injection (`=`, `+`, `-`, `@` prefixes) with RFC-4180 quoting.

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
- Strings longer than `2 × MAX_STRING_LENGTH` (512 chars) are replaced entirely with `[REDACTED]`
- Messages truncated to `MAX_STRING_LENGTH` (256 chars)
- Null messages remain null (no redaction needed)

> Note: Separate from `EventMetadataSanitizer`, the `ReceiptRepository.debugReceipt()` and PII sanitizers
> in `CloudPiiSanitizer` / input builders use `[REDACTED_URI]`, `[REDACTED_PATH]`, `[REDACTED_URL]`,
> `[REDACTED_EMAIL]` etc. for different purposes (UI debug export vs. durable storage).

## Enforcement

### CI guard (blocking, as of 2026-09-07)

- `scripts/verify_pii_logging_boundaries.py` (rule `G-PII-01`) scans `app/src/main/java` for PII leaking into log statements, exception messages, and diagnostics (raw OCR/notification/receipt text variables, stack traces, file paths, `e.message` logging, non-debug-guarded path logging).
- It runs as a **blocking** guard in the CI static-guard suite: `.github/workflows/ci.yml` job `static-guards` → `scripts/ci/run_static_guard_suite.py` (`GUARD_MANIFEST` entry `pii_logging`, mode `blocking`). A violation fails CI.
- Exemptions live in `scripts/allowlists/pii_logging_allowlist.yml`, which is currently **empty** — all `absolutePath` / `rawOcrText` / `e.message` exemptions were removed (G3.6); PII handling is enforced in code, not exemptions.

### Architecture guard tests (`app/src/test/java/com/yourname/expensetracker/architecture/`)

- `WorkerGuardArchitectureGuardTest` — every production `CoroutineWorker` routes through `WorkerExecutionGuard` (empty allowlist)
- `WorkerGuardStaticVerificationTest` — CI-enforcement: no known worker is unguarded; no stale registry entries
- `SourceScanningArchitectureGuardTest` (PR12F) — source-scanning guards: guard-call presence, no broadcast-receiver DAO injection, workId/runAttemptCount pass-through, schema-version/snapshot match, worker files free of direct DAO usage, notification-posting workers require permission flag
- `CancellationSafetyArchitectureGuardTest` — `CancellationException` must propagate (workers must not swallow cancellation)
- `DirectEventDaoInsertGuardTest` — direct `TransactionEvent` DAO inserts are guarded (event provenance)

### Unit enforcement

- `PrivacyCapabilityHandlingPolicyTest` ensures all capabilities have explicit policy
- `TIMBER_PII_LOGGING` capability is LOCAL_ONLY (no gate needed, just policy)
- Debug data persistence gated by `debugDataPersistenceEnabled` setting
- `EventMetadataSanitizer` verified in `GlobalDurableDiagnosticsGoldenTest` and `DurableDiagnosticsAcceptanceTest` (plus dedicated regression tests: `DurableDiagnosticsRegressionTest`, `DurableDiagnosticsA8RegressionTest`, `DDL512RegressionTest`)
- `SafePrivacyMetadata` verified in privacy contract tests
- Worker diagnostics coverage: `WorkerRunLoggerTest`, `FileWorkerTerminalDiagnosticSinkTest`

### Recent fixes

- Exception message redaction added to `EventMetadataSanitizer.sanitizeExceptionMessage()` — previously exception messages were written unsanitized to OperationRun and BackgroundJobRun tables
- `SafePrivacyMetadata` hardened: hash-key allowlist is exact-only (no prefix bypass), unknown `*Hash` keys are rejected, value patterns scanned for tokens, paths, IBANs, large blobs
- `PrivacyAuditContext` introduced as typed replacement for raw `Map<String, String>` — sensitive fields (prompt, rawText) are structurally absent from the model
