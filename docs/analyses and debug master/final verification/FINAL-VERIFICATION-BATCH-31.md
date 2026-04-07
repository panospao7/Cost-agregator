# Final Verification — Batch 31: Email, Currency, Speech & Security

## Scope
- `com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`
- `com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt`
- `com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt`
- `com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt`
- `com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt`
- `com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt`
- `com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt`
- `com/yourname/expensetracker/data/security/BankTokenCipher.kt`
- `com/yourname/expensetracker/data/security/SecureKeyStorage.kt`
- `com/yourname/expensetracker/data/provider/MerchantCategoryProvider.kt`
- `com/yourname/expensetracker/data/repository/ParserEnumMappers.kt`
- `com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt`
- `com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt`
- `com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`
- `com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt`
- `com/yourname/expensetracker/data/repository/CategoryRepository.kt`
- `com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt`
- `com/yourname/expensetracker/domain/usecase/receipt/ProcessReceiptUseCase.kt`
- `com/yourname/expensetracker/domain/naturallanguage/SpeechInputGateway.kt`
- `com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt`
- `com/yourname/expensetracker/domain/util/AmountUtils.kt`
- `com/yourname/expensetracker/domain/bank/BankApiIntegration.kt`
- `com/yourname/expensetracker/data/database/entity/BankConnection.kt`
- `com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt:97-107`; `com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt:15-16` | High | Data integrity | The ingestion flow dedupes by fingerprint before checking `messageId`, then persists `email_receipt_sources` with `REPLACE`. Reprocessing the same message with a different parsed date/amount/merchant can create a second `ScannedReceipt`/`Expense` and silently rewrite the email-source linkage. | B | CONFIRMED | Check `getByMessageId()` before any inserts, and change the DAO insert strategy from `REPLACE` to a conflict mode that surfaces duplicates instead of overwriting history. |
| 2 | `com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt:174-183,281-320` | High | Error handling / data loss | `createExpenseFromReceipt()` swallows all failures and returns `emptyList()`, but `processEmailReceipt()` still returns `Success`. The email is therefore marked as processed even when no expense exists, and the duplicate checks make the failure hard to retry safely. | B | CONFIRMED | Treat expense-creation failure as a hard failure and wrap receipt/source/expense writes in one DB transaction so partial inserts roll back. |
| 3 | `com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt:75-80` | High | HTML parsing | `cleanHtml()` strips all tags/entities and collapses all whitespace into single spaces. That destroys line boundaries used by provider regexes and removes HTML entities instead of decoding them, breaking parsing on real HTML receipts. | B | CONFIRMED | Replace regex-based cleanup with structured HTML parsing that preserves block boundaries and decodes entities. |
| 4 | `com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt:39-44,138-144` | High | Parsing bug | One Amazon date regex has no capture group, but `extractDate()` always reads `group(1)`. Matching that pattern throws and aborts parsing for otherwise valid receipts. | B | CONFIRMED | Normalize all date patterns to the same capture shape or branch extraction per pattern. |
| 5 | `com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt:46-52,168-174` | High | Parsing bug | Apple date extraction has the same capture-group invariant break: two configured patterns expose no group 1, but `extractDate()` always dereferences it. | B | CONFIRMED | Give every pattern the same capture layout, or inspect `groupCount()` before reading a subgroup. |
| 6 | `com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt:44-49,169-175` | High | Parsing bug | One Uber date pattern stores `AM/PM` in group 1 and the actual date in group 2, but `extractDate()` always reads group 1. Those receipts fall back to `receivedAt`, which breaks chronology and dedupe behavior. | B | CONFIRMED | Fix the regex group layout or extract each pattern explicitly, and add tests for timestamped receipts. |
| 7 | `com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt:24-53` | High | Permission / error handling | Voice input starts without a `RECORD_AUDIO` permission guard or `SecurityException` handling, and recognizer `onError()` signals are dropped. The feature can therefore fail noisily or silently depending on device state. | B | DOWNGRADED | Check microphone permission before starting, catch recognizer startup failures, and extend the gateway contract so callers can observe errors. |
| 8 | `com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt:22-56,195-210`; `com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt:25-65,179-195`; `com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt:23-56,180-205` | High | Internationalization / parsing coverage | All three provider parsers still assume English month names and dot-decimal amounts despite claiming multi-market receipt support. Localized receipts such as `12,34 €` or non-English month names are rejected or misdated. | B | CONFIRMED | Centralize locale-aware money/date parsing and add localized HTML/plain-text fixtures for supported markets. |
| 9 | `com/yourname/expensetracker/data/provider/MerchantCategoryProvider.kt:1240-1243`; `com/yourname/expensetracker/data/repository/CategoryRepository.kt:41-52`; `com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt:492-505` | Medium | Cross-component logic | Seeded merchant mappings are uppercased and inserted without `normalizedCanonicalName`. Exact lookup still works, but the fuzzy layer filters with case-sensitive `startsWith(prefix)` against lowercase input, so seeded mappings never participate in fuzzy fallback. | B | CONFIRMED | Seed normalized lowercase patterns and populate `normalizedCanonicalName` for the built-in dictionary. |
| 10 | `com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt:49-58,174-179`; `com/yourname/expensetracker/domain/usecase/receipt/ProcessReceiptUseCase.kt:15-47` | Medium | Architecture / pipeline drift | `ProcessReceiptUseCase` is injected and the class comment says email receipts feed the existing receipt pipeline, but the service never calls it. Email imports now have a separate behavior path that can drift from normal receipt processing. | B | CONFIRMED | Route email receipts through a shared post-parse processing abstraction, or remove the dead dependency/comment and document the separate pipeline explicitly. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/repository/CategoryRepository.kt:29-64` | High | Seed backfill | `ensureDefaultCategories()` seeds the built-in merchant dictionary only when the categories table is empty. Existing installations with preexisting categories never receive the seeded merchant mappings, so categorization remains much weaker after upgrade. | Seed merchant mappings independently of category count, guarded by mapping count or a seed-version/backfill marker. |
| 2 | `com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt:61-65,197-204`; `com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt:52-56,207-214` | High | Currency detection | `detectCurrency()` uses raw substring checks for short region fragments such as `US`, `DE`, `FR`, `IT`, `ES`, and `UK`. Ordinary words like `MUSIC`, `ORDER`, `DETAILS`, or `RESTAURANT` can therefore select the wrong currency before real symbols/codes are evaluated. | Match bounded tokens/domains/currency symbols instead of arbitrary substrings, or parse locale/currency from structured metadata. |
| 3 | `com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt:180-199` | Medium | Date parsing | When an Uber receipt date omits the year, `parseUberDate()` fills in the device's current year instead of the email's `receivedAt` year. Historical imports and year-boundary backfills are therefore misdated. | Pass `receivedAt` into year-less date parsing and derive the fallback year from the email timestamp. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R#8 / D#9` | `com/yourname/expensetracker/data/security/BankTokenCipher.kt:20-24,33-67` | The code has a future rotation/observability gap, but no current malfunction was proven. Versioning is already encoded in the payload prefix (`enc:v1`), and the only decrypt call site (`BankApiIntegration.refreshToken`) treats `null` as an explicit failure and logs a warning. This is a design limitation, not a verified present bug in this batch. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Email receipt ingestion persistence | High | Data integrity / atomicity | Deduplication is split between fingerprint and `messageId`, `email_receipt_sources` still uses `REPLACE`, and receipt/source/expense writes are not transactional. Reprocessing or mid-pipeline failures can leave duplicate or partial state. | `data/email/EmailReceiptIngestionService.kt`, `data/database/dao/EmailReceiptDao.kt`, `data/database/entity/EmailReceiptSource.kt`, `data/database/dao/ScannedReceiptDao.kt` | Check `messageId` first, stop using `REPLACE`, and wrap the whole write sequence in one DB transaction. |
| 2 | Base HTML cleanup -> provider parsers | High | Lossy normalization | `BaseEmailParser.cleanHtml()` removes structure and entities before Amazon/Apple/Uber regexes run, so provider-specific item/date/merchant extraction is built on already-damaged input. | `data/email/provider/EmailReceiptParser.kt`, `data/email/provider/AmazonReceiptParser.kt`, `data/email/provider/AppleReceiptParser.kt`, `data/email/provider/UberReceiptParser.kt` | Parse HTML structurally, preserve semantic breaks, and decode entities before provider matching. |
| 3 | Seeded merchant dictionary -> categorization engine | High | Bootstrap / normalization drift | The seeded dictionary is only backfilled for fresh installs, uppercases all keys, and omits canonical-normalized fields, while the fuzzy engine expects lowercase/canonical data. Existing users may miss the dictionary entirely, and fresh installs still lose fuzzy fallback quality. | `data/provider/MerchantCategoryProvider.kt`, `data/repository/CategoryRepository.kt`, `domain/categorization/CategorizationEngine.kt` | Add a versioned seed/backfill path and store normalized lowercase + canonical fields for built-in mappings. |
| 4 | Provider locale/currency parsing | High | Internationalization gap | The email parser stack mixes US-only date/amount parsing with substring-based currency heuristics. Localized receipts, HTML-encoded symbols, and short country-code substrings can all misclassify or reject otherwise valid provider emails. | `data/email/provider/EmailReceiptParser.kt`, `data/email/provider/AmazonReceiptParser.kt`, `data/email/provider/AppleReceiptParser.kt`, `data/email/provider/UberReceiptParser.kt` | Centralize locale-aware amount/date/currency parsing and validate it with multilingual fixtures. |
| 5 | Email receipt flow vs shared receipt processing | Medium | Architecture / drift | Email ingestion duplicates receipt-processing logic instead of using the shared receipt use case, so fixes and behavior can diverge over time. | `data/email/EmailReceiptIngestionService.kt`, `domain/usecase/receipt/ProcessReceiptUseCase.kt` | Extract a shared post-parse processing path and route both image and email receipts through it. |

## Summary
- Total verified issues: 10
- Confirmed: 10 (Critical: 0, High: 8, Medium: 2, Low: 0)
- False positives: 1
- Missed issues found: 3
- Files affected: 10/24

## Key Patterns
- The email ingestion path has **identity and transaction drift**: `messageId`, fingerprint dedupe, and multi-step persistence are not aligned.
- The provider parsers are **too regex- and ASCII-centric**: lossy HTML cleanup, inconsistent capture groups, locale-blind amount/date parsing, and naive currency heuristics all compound each other.
- The built-in categorization dictionary has **bootstrap inconsistency**: seeding, normalization, and fuzzy-match expectations are not using the same representation.
- The speech surface still lacks **defensive runtime handling** for permission and recognizer failures.
