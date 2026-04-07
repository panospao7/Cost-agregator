# Deep Analysis — Batch 31: Email, Currency, Speech & Security (@debugger)

## Scope
- data/email/EmailReceiptIngestionService.kt
- data/email/provider/AmazonReceiptParser.kt
- data/email/provider/AppleReceiptParser.kt
- data/email/provider/EmailReceiptParser.kt
- data/email/provider/UberReceiptParser.kt
- data/currency/ExchangeRateStoreAdapter.kt
- data/speech/AndroidSpeechInputGateway.kt
- data/security/BankTokenCipher.kt
- data/security/SecureKeyStorage.kt
- data/provider/MerchantCategoryProvider.kt
- data/repository/ParserEnumMappers.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | AndroidSpeechInputGateway.kt:24-53 | **CRITICAL** | Security/Crash | Speech recognition starts without checking `RECORD_AUDIO` permission and ignores runtime recognizer errors, so voice input can crash or hang silently. | 1. User grants no microphone permission. 2. `startListening()` throws `SecurityException`. 3. Exception caught but not surfaced. 4. Gateway hangs indefinitely. | Check permission before `startListening()`, catch `SecurityException`/runtime failures, and expose error callbacks or state. |
| 2 | EmailReceiptIngestionService.kt:97-107 | **HIGH** | Data Integrity | Email dedupe relies on fingerprint before inserts but does not first dedupe by `messageId`; reprocessing the same email with a different parse can create duplicate receipt/expense rows, while `REPLACE` on the email-source row rewrites linkage instead of preventing duplication. | 1. Process email with messageId "abc123". 2. Reprocess same email with different parse result. 3. New receipt/expense created despite same messageId. | Check `emailReceiptDao.getByMessageId(messageId)` up front and avoid `REPLACE` semantics for duplicate message IDs. |
| 3 | EmailReceiptIngestionService.kt:175-183,281-320 | **HIGH** | Error Handling | Expense creation failures are swallowed and the service still returns `Success`, leaving a stored email receipt with no expense and no retry path. | 1. Email parsed successfully. 2. Expense creation fails (DB locked, constraint violation). 3. Service returns `Success`. 4. Receipt stored but no expense linked. | Fail the operation when expense creation fails, ideally inside a single DB transaction for receipt/source/expense writes. |
| 4 | EmailReceiptParser.kt:75-80 | **HIGH** | Logic Error | Base HTML cleanup destroys line structure and removes HTML entities, which breaks provider regexes and currency parsing on real HTML receipts. | 1. HTML receipt contains `<br>` tags and `&euro;` entities. 2. Cleanup strips `<br>` and removes entities. 3. Provider regexes can't match amounts. | Replace the regex cleanup with proper HTML parsing/entity decoding while preserving logical line breaks. |
| 5 | AmazonReceiptParser.kt:39-44,138-144 | **HIGH** | Crash | Amazon date extraction assumes every regex has capture group 1, but one configured pattern has none, causing parsing exceptions on matching receipts. | 1. Amazon receipt matches pattern without capture group. 2. `group(1)` throws `IndexOutOfBoundsException`. 3. Receipt parsing fails. | Normalize all date regexes to the same capture shape or branch extraction per pattern. |
| 6 | AppleReceiptParser.kt:46-52,168-174 | **HIGH** | Crash | Apple date extraction has the same capture-group bug, so valid Apple receipts can throw during parsing instead of falling back cleanly. | Same as #5 but for Apple receipts. | Same fix — normalize capture groups. |
| 7 | UberReceiptParser.kt:44-49,169-175 | **HIGH** | Logic Error | Uber date extraction reads group 1 for all patterns, but one timestamped pattern stores `AM/PM` in group 1 and the date in group 2, causing wrong fallback dates and broken dedupe/ordering. | 1. Uber receipt with timestamp "10:30 AM" matches pattern. 2. Group 1 = "AM", not date. 3. Date parsing fails, falls back to current time. | Fix the regex groups and add tests for timestamped ride receipts. |
| 8 | AmazonReceiptParser.kt, AppleReceiptParser.kt, UberReceiptParser.kt | **HIGH** | Logic Error | Provider parsers claim international receipt support but only handle dot-decimal amounts and English month names, so localized EU receipts are likely to fail or be misdated. | 1. German Amazon receipt with "1.234,56 €" and "März". 2. Amount parser fails on comma decimal. 3. Month name not recognized. | Centralize locale-aware money/date parsing and add localized fixtures. |
| 9 | BankTokenCipher.kt:20-24,33-67 | **MEDIUM** | Security | Bank token encryption has no real version-aware rotation path even though the schema tracks `tokenEncryptionVersion`, and decrypt failures are silently collapsed to `null`. | 1. Encryption key rotated. 2. Old tokens can't be decrypted. 3. Decrypt returns `null` silently. 4. Bank connection appears disconnected. | Make decryption version-aware, log failures, and add explicit migration/re-encryption support. |
| 10 | MerchantCategoryProvider.kt:1241-1243 | **MEDIUM** | Logic Error | Seeded merchant mappings are uppercased and stored without normalized canonical names, which prevents `CategorizationEngine` fuzzy matching from working on the built-in dictionary. | 1. Built-in dictionary has "sklavenitis". 2. Seeded mapping stores "SKLAVENITIS". 3. Fuzzy match fails due to case mismatch. | Seed normalized lowercase patterns and/or populate `normalizedCanonicalName` consistently. |
| 11 | EmailReceiptIngestionService.kt:49-58,174-179 | **MEDIUM** | Architecture | Email ingestion injects `ProcessReceiptUseCase` and claims to use the existing receipt pipeline, but never actually calls it, creating pipeline drift risk. | 1. Email receipt processed through ingestion service. 2. `ProcessReceiptUseCase` injected but never called. 3. Email pipeline diverges from main receipt pipeline. | Route email receipts through a shared processing abstraction or remove the dead dependency/comment. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | EmailReceiptIngestionService ↔ Expense creation | **HIGH** | Silent Failure | Expense creation failures are swallowed, leaving orphaned email receipts with no linked expenses. | Wrap receipt/source/expense writes in single transaction. |
| C2 | Email Receipt Parsers ↔ HTML processing | **HIGH** | HTML Destruction | Base HTML cleanup destroys line structure and entities, breaking all provider regexes on real HTML receipts. | Use proper HTML parsing with entity decoding. |
| C3 | Amazon/Apple/Uber Parsers ↔ Regex patterns | **HIGH** | Capture Group Mismatch | Date extraction regexes have inconsistent capture group shapes, causing crashes or wrong dates. | Normalize all patterns to same capture shape. |
| C4 | BankTokenCipher ↔ Token rotation | **MEDIUM** | No Key Rotation | Encryption version tracking exists but no actual rotation path, so key changes break existing tokens silently. | Implement version-aware decryption and re-encryption. |
| C5 | EmailReceiptIngestionService ↔ ProcessReceiptUseCase | **MEDIUM** | Dead Dependency | `ProcessReceiptUseCase` is injected but never called, creating pipeline drift risk. | Route through shared processing or remove dead dependency. |

## Summary
- **Total issues: 16** (11 file-level + 5 cross-component)
- **Critical: 1**, **High: 8**, **Medium: 4**, **Low: 0**
- **Files with issues: 9/11** (ExchangeRateStoreAdapter.kt and ParserEnumMappers.kt are clean)

## Key Patterns

### 1. HTML Processing Destruction
The base HTML cleanup in `EmailReceiptParser` destroys line structure and removes HTML entities, breaking all provider regexes on real HTML receipts. This is a systemic issue affecting all email receipt parsing.

### 2. Capture Group Inconsistency
Amazon, Apple, and Uber date extraction regexes have inconsistent capture group shapes, causing crashes or wrong dates. This is a copy-paste pattern that needs standardization.

### 3. Silent Failure Swallowing
Email ingestion swallows expense creation failures, leaving orphaned receipts. Bank token decryption failures are silently collapsed to `null`. This makes debugging production issues nearly impossible.

### 4. Locale Ignorance
All email parsers assume English/dot-decimal formats, breaking on localized EU receipts. This is a systemic internationalization gap.

### 5. Dead Dependencies
`ProcessReceiptUseCase` is injected but never called in email ingestion, creating pipeline drift risk.
