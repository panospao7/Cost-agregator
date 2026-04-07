# Deep Analysis — Batch 31: Email, Currency, Speech & Security (@reviewer)

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
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/email/EmailReceiptIngestionService.kt:97-107` | HIGH | Deduplication / Data integrity | The service deduplicates only by parsed fingerprint before creating new rows, even though `messageId` is documented as the unique email identifier. If the same email is reprocessed with a different fallback date/merchant/amount parse, the fingerprint changes and a second `ScannedReceipt`/`Expense` can be created; the later `email_receipt_sources` insert then relies on a unique `emailMessageId` with `REPLACE`, which overwrites the old linkage instead of preventing the duplicate. | Check `emailReceiptDao.getByMessageId(messageId)` first, fail/return duplicate before any inserts, and stop using `REPLACE` for `EmailReceiptDao.insert` so duplicate message IDs surface as conflicts instead of rewriting history. |
| 2 | `data/email/EmailReceiptIngestionService.kt:175-183,281-320` | HIGH | Error handling / Data loss | `createExpenseFromReceipt()` catches all exceptions and returns `emptyList()`, but `processEmailReceipt()` still returns `Success`. That marks the email as processed and persists the receipt source even when no expense was created, making the failure silent and effectively non-retryable. | Treat expense-creation failure as a hard failure (or wrap all receipt/source/expense writes in one DB transaction and roll back), and return `ParseError`/`Failure` when no expense was created. |
| 3 | `data/email/provider/EmailReceiptParser.kt:75-80` | HIGH | HTML parsing | `cleanHtml()` strips all tags/entities and then collapses all whitespace to single spaces. That destroys line/block boundaries relied on by the provider regexes and drops HTML currency entities such as `&euro;`, causing item extraction and some currency detection paths to fail on real HTML receipts. | Use a real HTML parser (e.g. Jsoup), preserve semantic line breaks for block elements, and decode HTML entities instead of replacing them with spaces. |
| 4 | `data/email/provider/AmazonReceiptParser.kt:39-44,138-144` | HIGH | Parsing bug | `DATE_PATTERNS` contains a `dd MMMM yyyy` pattern without a capture group, but `extractDate()` always reads `matcher.group(1)`. When that pattern matches, parsing throws and the whole Amazon receipt import fails. | Make all date patterns expose the same capture group shape, or branch per pattern and use `group()` when no explicit subgroup exists. |
| 5 | `data/email/provider/AppleReceiptParser.kt:46-52,168-174` | HIGH | Parsing bug | Apple date extraction has the same invariant break: two patterns have no capture group but `extractDate()` always accesses group 1. Valid Apple receipts that match those patterns throw during parsing instead of cleanly falling back. | Normalize the regexes so every pattern captures the date in the same group, or detect group count before reading. |
| 6 | `data/email/provider/UberReceiptParser.kt:44-49,169-175` | HIGH | Parsing bug | Uber date extraction uses `matcher.group(1)` for every pattern, but one pattern captures `(AM|PM)` as group 1 and the actual date as group 2. Timestamped receipts therefore parse `AM`/`PM` as the date, fail conversion, and silently fall back to `receivedAt`, which breaks dedupe and chronology. | Fix the regex group layout (or extract per pattern explicitly) so the actual date text is always read, and add tests for timestamped ride receipts. |
| 7 | `data/speech/AndroidSpeechInputGateway.kt:24-53` | CRITICAL | Permissions / Crash risk | `startListening()` calls `SpeechRecognizer.startListening()` with no `RECORD_AUDIO` permission check and no `SecurityException` handling. On devices where the permission is missing or revoked, voice input can crash immediately. `onError()` is also ignored, so failures are invisible to callers. | Gate startup on `RECORD_AUDIO`, catch `SecurityException`/runtime recognizer failures, and expose an error callback/state so callers can react instead of hanging silently. |
| 8 | `data/security/BankTokenCipher.kt:20-24,33-67` | MEDIUM | Security / Key management | The cipher hard-codes a single `enc:v1` format and silently returns `null` on every decrypt failure. The schema already tracks `tokenEncryptionVersion`, but the cipher never uses it, so there is no real rotation/migration path and no diagnostics when keys become invalid after restore/reset. | Make decryption version-aware, log/telemetry decrypt failures, and add an explicit migration/re-encryption path keyed off `tokenEncryptionVersion`. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 9 | `BaseEmailParser -> AmazonReceiptParser / AppleReceiptParser / UberReceiptParser` | HIGH | Locale / Parsing coverage | All three provider parsers only accept dot-decimal amounts (`12.34`) and only parse English month names, despite claiming coverage for domains/markets like Amazon DE/FR/ES and EU Apple/Uber receipts. Localized emails with comma decimals (`12,34`) or non-English month names will be rejected or misdated. | Centralize locale-aware money/date parsing and add fixtures for EU-localized emails (comma decimals, translated month names, HTML entities). |
| 10 | `MerchantCategoryProvider -> CategoryRepository -> CategorizationEngine` | MEDIUM | Cross-component logic | `MerchantCategoryProvider.getExpandedMap()` uppercases seeded merchant keys, but `CategoryRepository` stores them without `normalizedCanonicalName`. Later, `CategorizationEngine.findFuzzyMatch()` does a case-sensitive `startsWith(prefix)` against `merchantPattern`, so fuzzy matching never works for the built-in seeded dictionary. | Seed normalized lowercase patterns (and/or populate `normalizedCanonicalName`) so exact, canonical, and fuzzy layers all operate on the same normalized representation. |
| 11 | `EmailReceiptIngestionService -> ProcessReceiptUseCase / receipt pipeline` | MEDIUM | Architecture / Pipeline drift | `EmailReceiptIngestionService` claims it feeds the existing receipt-processing pipeline, injects `ProcessReceiptUseCase`, and then never calls it. Email imports therefore bypass shared receipt-processing behavior and can drift from scanned-receipt behavior over time. | Either actually route email receipts through a shared processing abstraction or remove the unused dependency/comment and define a dedicated, fully equivalent email pipeline. |

## Summary
- Total issues: 11
- Critical: 1, High: 7, Medium: 3, Low: 0
- Files with issues: 8/11

## Key Patterns
- The email parsing stack is fragile against real-world HTML/localized content: structural cleanup is lossy, regex group conventions are inconsistent, and locale-specific numbers/dates are not handled centrally.
- The email ingestion flow has transactional gaps: dedupe is split across message IDs/fingerprints, failures are swallowed, and partial writes can leave the pipeline in a “processed but incomplete” state.
- Security/storage code uses strong primitives, but operational concerns (rotation, invalidation recovery, diagnostics) are still under-specified.
- Some seeded classification behavior is internally inconsistent across provider/bootstrap/engine layers, reducing the effectiveness of fallback matching.
