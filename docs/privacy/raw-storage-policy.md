# Raw Storage Policy

> Canonical reference for allowed raw persistence in ExpenseTracker.
> Every persisted target must have an entry in this table.
> Any new raw-sensitive field must be added here before implementation.

## Policy authority

`PrivacySettings` is the authoritative privacy source.
`AiSettings` or caller input may further restrict behavior but must NEVER weaken `PrivacySettings`.

## Corruption handling

When DataStore is corrupted (`PrivacySettingsLoadState.CorruptedFailClosed`):
- The corruption handler writes `_privacy_load_state = CORRUPTED` sentinel via `mutablePreferencesOf()`
- `toLoadState()` checks the sentinel first: `CORRUPTED` → `CorruptedFailClosed`
- No sentinel + empty prefs → `FirstRunDefault` (genuine first run)
- `NORMAL` sentinel or any real key present → `Loaded`
- `updateSettings()` writes `_privacy_load_state = NORMAL` on every successful write

This ensures corruption can NEVER be misclassified as a permissive first-run state.

## Raw storage mode semantics

| Mode | Raw body | Redacted body | Parsed items | External ID hash |
|---|---|---|---|---|
| STORE_RAW | ✅ | ✅ | ✅ | ✅ |
| STORE_REDACTED | ❌ | ✅ (`[REDACTED]`) | ✅ | ✅ |
| STORE_METADATA_ONLY | ❌ | ❌ | ❌ | ✅ (dedup only) |
| DO_NOT_STORE | ❌ | ❌ | ❌ | ✅ (dedup only, sources that need it) |

## Source-to-setting mapping

| Source | Setting key | Default mode |
|---|---|---|
| `NOTIFICATION` | `rawNotificationStorageMode` | `STORE_RAW` |
| `RECEIPT_OCR` | `rawOcrStorageMode` | `STORE_RAW` |
| `EMAIL_RECEIPT` | `emailReceiptStorageMode` | `STORE_REDACTED` |
| `BANK_STATEMENT` | `rawOcrStorageMode` (reused) | `STORE_RAW` |
| `BANK_API` | `rawOcrStorageMode` (reused) | `STORE_RAW` |
| `AI_ARTIFACT` | `debugDataPersistenceEnabled` | `DO_NOT_STORE` |
| `EXPORT_DEBUG` | `debugDataPersistenceEnabled` | `DO_NOT_STORE` |

## Fail-closed corruption defaults

All modes fall back to:
- `rawNotificationStorageMode = DO_NOT_STORE`
- `rawOcrStorageMode = DO_NOT_STORE`
- `emailReceiptStorageMode = DO_NOT_STORE`
- `cloudAiEnabled = false`
- `notificationCaptureEnabled = false`

## Hashing requirements

External identifiers that may be used for deduplication must use HMAC-SHA-256 via `SensitiveHashingService.hmacSha256Prefix`:
- `emailMessageId`
- `providerTransactionId`
- `bankAccountId`
- `notificationKey`
- `receiptOrderId`
- `bankCounterparty`

Do NOT use `String.hashCode()` for any of these. See `SensitiveHashingService`.

## SafePrivacyMetadata value safety

`SafePrivacyMetadata.put(key, value)` sanitizes BOTH key and value:
- Blocked key substrings → `[REDACTED]`
- Sensitive value patterns (base64 tokens, Bearer, IBAN, card numbers, file paths, JWTs) → `[REDACTED]`
- Long strings (>512 chars) → `[REDACTED_BLOB]`
- Nested maps/lists → `[REDACTED_MAP]` / `[REDACTED_LIST]`
- `putHash(key, hash)` only accepts approved hash keys and hex-like values

## Cloud payload rules

- All cloud providers must use `PreparedCloudPayload` from `CloudPayloadPolicy`.
- `redactBeforeCloud` authority: `PrivacySettings > AiSettings > caller input`.
- Bank statement payloads always use `CloudPayloadPurpose.BANK_STATEMENT_VALIDATION` with strict redaction.
- Image upload is suppressed when redaction is required.

## Export policy

`encryptedBackupEnabled = false` does NOT imply raw export is allowed.
See `ExportPrivacyGate` and `ExportPrivacyPolicy` for explicit capability requirements.

`ExportPrivacyGate` is the **sole owner** of `RAWBACKUP_EXPORT` (it denies plaintext
raw export unless explicit debug consent in a debug build). `BackupPrivacyGate` owns
only `ENCRYPTED_BACKUP`. The two gates never issue conflicting decisions for the same
capability.

`ExportAnonymizer` redacts every PII-bearing table in the export copy (single
transaction): `scanned_receipts.rawOcrText`, `raw_notifications` raw content,
`ai_artifacts` (summary/explanation/payload/error), `ai_chat_messages` (text/payload),
`merchant_locations` (display name/address/osmId + lat/lon zeroed), and
`email_receipt_sources` (sender/subject/messageId; dedup hashes preserved).

## Static guard script

Run `python3 scripts/verify_privacy_boundaries.py` before submitting PRs touching any privacy-sensitive code path.

Rules G1–G14 are enforced. Notable rules:
- G9 verifies the corruption sentinel.
- G10 verifies value-level sanitization in `SafePrivacyMetadata`.
- G14 verifies every `*GeocodingService`/`*NearbyService` that depends on `PrivacyGate`
  calls `privacyGate.check(...)` (statically guarantees location gating).

The guard exempts allow-all `PrivacyGate` objects inside `@VisibleForTesting`/secondary
(test-only) constructors, matching the Kotlin `PrivacyGuardTest` carve-out, because DI
provides the real gate in production.

> Canonical reference for allowed raw persistence in ExpenseTracker.
> Every persisted target must have an entry in this table.
> Any new raw-sensitive field must be added here before implementation.

## Policy authority

`PrivacySettings` is the authoritative privacy source.
`AiSettings` or caller input may further restrict behavior but must NEVER weaken `PrivacySettings`.

## Raw storage mode semantics

| Mode | Raw body | Redacted body | Parsed items | External ID hash |
|---|---|---|---|---|
| STORE_RAW | ✅ | ✅ | ✅ | ✅ |
| STORE_REDACTED | ❌ | ✅ (`[REDACTED]`) | ✅ | ✅ |
| STORE_METADATA_ONLY | ❌ | ❌ | ❌ | ✅ (dedup only) |
| DO_NOT_STORE | ❌ | ❌ | ❌ | ✅ (dedup only, sources that need it) |

## Source-to-setting mapping

| Source | Setting key | Default mode |
|---|---|---|
| `NOTIFICATION` | `rawNotificationStorageMode` | `STORE_RAW` |
| `RECEIPT_OCR` | `rawOcrStorageMode` | `STORE_RAW` |
| `EMAIL_RECEIPT` | `emailReceiptStorageMode` | `STORE_REDACTED` |
| `BANK_STATEMENT` | `rawOcrStorageMode` (reused) | `STORE_RAW` |
| `BANK_API` | `rawOcrStorageMode` (reused) | `STORE_RAW` |
| `AI_ARTIFACT` | `debugDataPersistenceEnabled` | `DO_NOT_STORE` |
| `EXPORT_DEBUG` | `debugDataPersistenceEnabled` | `DO_NOT_STORE` |

## Fail-closed corruption defaults

When DataStore is corrupted (`PrivacySettingsLoadState.CorruptedFailClosed`), all modes fall back to:
- `rawNotificationStorageMode = DO_NOT_STORE`
- `rawOcrStorageMode = DO_NOT_STORE`
- `emailReceiptStorageMode = DO_NOT_STORE`
- `cloudAiEnabled = false`
- `notificationCaptureEnabled = false`

## Hashing requirements

External identifiers that may be used for deduplication must use HMAC-SHA-256 via `SensitiveHashingService.hmacSha256Prefix`:
- `emailMessageId`
- `providerTransactionId`
- `bankAccountId`
- `notificationKey`
- `receiptOrderId`
- `bankCounterparty`

Do NOT use `String.hashCode()` for any of these. See `SensitiveHashingService`.

## Cloud payload rules

- All cloud providers must use `PreparedCloudPayload` from `CloudPayloadPolicy`.
- `redactBeforeCloud` authority: `PrivacySettings > AiSettings > caller input`.
- Bank statement payloads always use `CloudPayloadPurpose.BANK_STATEMENT_VALIDATION` with strict redaction.
- Image upload is suppressed when redaction is required.

## Export policy

`encryptedBackupEnabled = false` does NOT imply raw export is allowed.
See `ExportPrivacyGate` and `ExportPrivacyPolicy` for explicit capability requirements.

## Static guard script

Run `python3 scripts/verify_privacy_boundaries.py` before submitting PRs touching any privacy-sensitive code path.
