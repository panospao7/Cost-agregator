## Technical Plan

### Scope
- In: all **HIGH** rows under `### B.11: Email/Parsing Pipeline` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, limited to email receipt ingestion integrity, provider HTML/date/locale parsing, transfer classification in notification/statement parsers, voice-input permission/error handling, and the `BillReminderManager` semi-annual reminder gap.
- Out: all **MEDIUM/LOW** B.11 rows, `ProcessReceiptUseCase` architecture cleanup, `EmailIngestionModule` DI cleanup, GenericTransactionParser strict-date work, seeded merchant mapping cleanup, broad OCR runtime wiring, and any Room schema/entity/migration/index change.
- Assumptions / unknowns:
  - `B.4` local commit remains the Phase B gate; this plan is execution-ready but should only start once the orchestrator opens the B.11 lane.
  - Several B.11 HIGH rows appear partially or fully compliant in live code (`messageId` ordering / non-destructive email insert, `BankStatementParser` Revolut classification, `OcrLanguageProcessor`, `BillReminderManager`). Treat those as **audit-first** rows: prefer regression lock-in over production churn.
  - Email ingestion atomicity must be solved with the existing `AppDatabase.withTransaction` boundary, not with new constraints/columns.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/parser/GenericTransactionParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/GoogleWalletParser.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/SpeechInputGateway.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt` *(audit/fix only if live regression remains)*
- modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/OcrLanguageProcessor.kt` *(audit/fix only if live regression remains)*
- modify: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt` *(audit/fix only if live regression remains)*
- modify: `app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTransactionTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParserTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParserTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParserTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/UberReceiptParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/parser/GenericTransactionParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/parser/GoogleWalletParserTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGatewayTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrLanguageProcessorTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-31.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-43.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-44.md`
- create: `docs/reviews/REVIEW-B11.md`

### Implementation Steps
1. Close email-ingestion success/transactionality first so parser fixes cannot create new partial-write states.
2. Establish one shared HTML/entity/locale parsing foundation in the email provider base layer.
3. Fix provider-specific Amazon / Apple / Uber capture-group and localization regressions.
4. Normalize transfer semantics across Generic and Google Wallet notification parsers.
5. Surface speech-input permission/runtime failures instead of swallowing them.
6. Audit-lock already-compliant B.11 HIGH rows (`BankStatementParser`, `OcrLanguageProcessor`, `BillReminderManager`) with minimal or zero production churn.

### 1. Objective & Blast Radius
- **Core issue:** B.11 has no CRITICAL rows, but its HIGH defects still allow email receipts to be partially persisted or falsely marked successful, localized provider emails to misparse, transfer flows to be misclassified, and voice-input failures to disappear silently.
- **Blast radius:** `data/email/`, `data/email/provider/`, `domain/parser/`, `data/speech/`, `domain/naturallanguage/`, `domain/receipt/`, `domain/reminder/`, their focused tests, and the B.11 registry / verification trail.
- **Primary downstream surfaces:** receipt ingestion, scanned-receipt linkage, expense creation, transaction-type analytics, OCR normalization consumers, natural-language voice entry, and reminder totals/scheduling.

> [!WARNING]
> - Do **not** touch B.11 MEDIUM/LOW rows in this plan.
> - Do **not** change Room entities, schema versions, migrations, indices, or column names to solve email atomicity.
> - Do **not** fold in `ProcessReceiptUseCase` integration, parser DI cleanup, or broad OCR-runtime rewiring; those are separate rows.
> - If `BankStatementParser`, `OcrLanguageProcessor`, or `BillReminderManager` are already compliant, keep production code stable and close them with proof, not churn.

### 2. The Single Source of Truth
- **Email-ingestion truth:** `EmailReceiptIngestionService` must treat nonblank `messageId` as the authoritative dedupe key before fingerprint fallback, and it must not return `Success` until receipt/source/expense persistence completes inside one DB transaction.
- **Email text truth:** `BaseEmailParser` is the only approved place to normalize provider HTML, decode entities, and parse locale-aware amounts/dates. Provider parsers must not keep ad hoc `replace(",", "") + toDoubleOrNull()` or blind `group(1)` assumptions.
- **Transfer truth:** person-to-person or account-transfer wording maps to `ParsedTransactionType.TRANSFER` with direction metadata. `DEPOSIT` is reserved for true income/refund/top-up semantics that are not transfers.
- **Speech-input truth:** `SpeechInputGateway` must never start listening without availability/permission/startup guards, and recognizer failures must be surfaced to callers instead of dropped.
- **Audit truth:** rows that are already compliant in live code (`BankStatementParser`, `OcrLanguageProcessor`, `BillReminderManager`, and the messageId-first/non-REPLACE portion of email ingestion) should be proven by tests and documentation, not rewritten unnecessarily.

> [!WARNING]
> - Keep the speech-input contract change backward-compatible where possible (default callback / overload); do not force a broad UI rewrite.
> - Use existing transfer-direction semantics (`ParsedTransferDirection` and current detector helpers) instead of inventing parser-specific meaning.
> - Do **not** reopen unrelated B.44 items like balance-column selection, header-date selection, or OCR DI wiring while closing the B.11 HIGH rows.

### 3. File-by-File Execution Checklist (micro-batches)

#### Batch 1 — Email ingestion atomic success semantics
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTransactionTest.kt`
- Checklist:
  - [ ] `EmailReceiptIngestionService.kt`: inject/use the existing `AppDatabase` transaction boundary so `ScannedReceipt`, `EmailReceiptSource`, expense creation, and scanned-receipt linkage commit or roll back together.
  - [ ] `EmailReceiptIngestionService.kt`: keep the current nonblank `messageId` duplicate short-circuit ahead of fingerprint fallback; fingerprint stays the fallback path for blank IDs only.
  - [ ] `EmailReceiptIngestionService.kt`: treat `createExpenseFromReceipt()` returning no IDs (or throwing) as a hard failure; never emit `EmailReceiptResult.Success` with an empty expense list.
  - [ ] `EmailReceiptIngestionService.kt`: preserve the current non-destructive email-source behavior (`insertOrIgnore` / no `REPLACE`); do **not** reintroduce overwriting history.
  - [ ] `EmailReceiptIngestionServiceTest.kt`: keep/update regressions for duplicate short-circuit ordering and “do not call destructive insert path” behavior.
  - [ ] `EmailReceiptIngestionServiceTransactionTest.kt`: prove expense-creation failure rolls back receipt/source writes, using a real transaction boundary rather than a pure mock.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTransactionTest"`
- Rollback / stop rule:
  - If this fix appears to require new DB constraints, schema changes, or entity edits, stop and split; B.11 must solve this with the existing Room transaction surface.
- Done when:
  - Reprocessing can no longer leave partial receipt/source state.
  - `Success` is impossible unless at least one expense ID is actually persisted.

#### Batch 2 — Shared HTML/entity/locale parsing foundation
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParserTest.kt`
- Checklist:
  - [ ] `EmailReceiptParser.kt`: replace lossy `cleanHtml()` behavior with semantic cleanup that preserves meaningful line / block breaks and decodes common plus numeric HTML entities before regex matching.
  - [ ] `EmailReceiptParser.kt`: add reusable helper(s) for locale-aware amount/date parsing so provider parsers stop relying on English-only month names and dot-decimal-only amounts.
  - [ ] Keep helper scope inside the email provider package; do **not** widen into unrelated OCR or generic parser modules.
  - [ ] `EmailReceiptParserTest.kt`: add focused tests for line-break preservation, entity decoding, comma-decimal/grouped amounts, and at least one non-English month parsing path.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.EmailReceiptParserTest"`
- Rollback / stop rule:
  - Do **not** add a new heavy HTML-parsing dependency or refactor the whole provider stack unless a minimal helper truly cannot solve the HIGH rows.
- Done when:
  - Provider parsers receive decoded, line-aware text and can share locale-aware amount/date helpers.

#### Batch 3 — Amazon + Apple provider parsing correctness
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParserTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParserTest.kt`
- Checklist:
  - [ ] `AmazonReceiptParser.kt`: normalize date extraction so patterns without the old `group(1)` shape no longer throw; extract the actual matched date text intentionally.
  - [ ] `AppleReceiptParser.kt`: remove the same capture-group invariant break for date extraction; no blind subgroup reads from patterns that do not expose group 1.
  - [ ] Both provider files: route amount/date parsing through the shared Batch-2 helper so localized month names and comma-decimal totals can parse.
  - [ ] Preserve existing merchant/order/item heuristics unless they block the HIGH fix; do **not** redesign provider detection.
  - [ ] Add localized HTML/plain-text regressions covering valid Amazon and Apple receipts that previously failed because of capture-group or locale assumptions.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.AmazonReceiptParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.AppleReceiptParserTest"`
- Rollback / stop rule:
  - If a full provider redesign or metadata-service lookup seems necessary, stop after fixing capture-group and locale parsing paths only.
- Done when:
  - Valid Amazon/Apple receipts no longer crash/fall back because of subgroup mismatch.
  - Localized supported-market fixtures parse amount/date successfully.

#### Batch 4 — Uber provider timestamp + locale correctness
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/email/provider/UberReceiptParserTest.kt`
- Checklist:
  - [ ] `UberReceiptParser.kt`: fix date extraction so timestamped patterns read the real date subgroup, not the `AM/PM` token.
  - [ ] `UberReceiptParser.kt`: route amount/date parsing through the shared locale-aware helper for both ride and Eats receipts.
  - [ ] Keep current yearless-date fallback behavior unless the helper forces a trivial compatible change; the historical year bug is not part of this HIGH lane.
  - [ ] `UberReceiptParserTest.kt`: add regressions for timestamped receipts, localized totals, and both ride / Eats variants.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.UberReceiptParserTest"`
- Rollback / stop rule:
  - Do **not** widen this batch into the medium received-year/backfill issue unless a tiny helper signature alignment makes it unavoidable.
- Done when:
  - Uber receipts no longer misread `AM/PM` as the date and localized fixtures parse cleanly.

#### Batch 5 — Transfer semantics in Generic + Google Wallet parsers
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/parser/GenericTransactionParser.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/GoogleWalletParser.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/parser/GenericTransactionParserTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/parser/GoogleWalletParserTest.kt`
- Checklist:
  - [ ] `GenericTransactionParser.kt`: separate incoming-transfer wording from true deposit/income wording so `transfer received` and equivalent phrases emit `ParsedTransactionType.TRANSFER`, not `DEPOSIT`.
  - [ ] `GenericTransactionParser.kt`: keep salary/refund/top-up semantics as `DEPOSIT` where appropriate; do **not** collapse all money-in to transfers.
  - [ ] `GoogleWalletParser.kt`: add an explicit P2P transfer path for send/receive wording so outgoing sends and incoming person-to-person money use `TRANSFER` with direction metadata.
  - [ ] Reuse existing transfer-direction semantics (`ParsedTransferDirection`, current direction detector, or a narrow shared helper) rather than inventing a second meaning system.
  - [ ] `GenericTransactionParserTest.kt` and `GoogleWalletParserTest.kt`: add regressions for incoming transfer, outgoing P2P send, and unchanged ordinary purchases.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.parser.GenericTransactionParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.parser.GoogleWalletParserTest"`
- Rollback / stop rule:
  - Do **not** widen into GenericTransactionParser strict-date validation or a broad parser-registry refactor.
- Done when:
  - Transfer wording no longer lands as `DEPOSIT`/`PURCHASE` in these two parsers.

#### Batch 6 — Speech input safe start + observable errors
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/SpeechInputGateway.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGatewayTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt`
- Checklist:
  - [ ] `SpeechInputGateway.kt`: extend the contract narrowly so `startListening` can surface permission / security / recognizer errors without forcing existing callers into a breaking rewrite (default callback / overload is preferred).
  - [ ] `AndroidSpeechInputGateway.kt`: guard `RECORD_AUDIO` before starting, catch `SecurityException` / recognizer startup failures, and forward `onError()` instead of discarding it.
  - [ ] `NaturalLanguageSearchEngine.kt`: thread the optional error callback through the wrapper so the failure signal is reachable above the gateway layer.
  - [ ] `AndroidSpeechInputGatewayTest.kt`: prove denied permission and startup failure do not crash and do surface an error.
  - [ ] `NaturalLanguageSearchEngineVoiceInputTest.kt`: prove the wrapper forwards both result and error callbacks.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.speech.AndroidSpeechInputGatewayTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngineVoiceInputTest"`
- Rollback / stop rule:
  - Do **not** turn this into a UI permission-request flow or manifest overhaul unless compile/runtime proof shows a missing declaration blocker.
- Done when:
  - Voice input no longer starts unsafely or drops recognizer failures on the floor.

#### Batch 7 — Audit-lock OCR + Revolut statement HIGH rows
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt` *(only if live audit fails)*
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/OcrLanguageProcessor.kt` *(only if live audit fails)*
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrLanguageProcessorTest.kt`
- Checklist:
  - [ ] Read both production files first. If live code already emits correct Revolut transfer/top-up/refund semantics and preserves non-Latin scripts plus locale-specific amount separators, keep production edits minimal or zero.
  - [ ] `BankStatementParser.kt`: if any remaining B.11 HIGH regression exists, apply the smallest fix limited to Revolut transaction-type classification. Do **not** reopen other B.44 items like balance-column selection or header-date usage.
  - [ ] `OcrLanguageProcessor.kt`: if any remaining B.11 HIGH regression exists, apply the smallest fix limited to script-preserving normalization and locale-aware amount extraction. Do **not** widen into OCR runtime DI/integration work.
  - [ ] `BankStatementParserTest.kt` and `OcrLanguageProcessorTest.kt`: add or refresh proof for the exact B.11 HIGH rows so documentation can close them confidently even if production code stays unchanged.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.BankStatementParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.OcrLanguageProcessorTest"`
- Rollback / stop rule:
  - If the only remaining gap is the broader OCR runtime wiring issue, document it and defer; it is not part of this B.11 HIGH lane.
- Done when:
  - The B.11 HIGH rows for Revolut statement classification and OCR script/amount handling are either fixed or proven compliant with focused tests.

#### Batch 8 — Bill reminder semi-annual audit lock
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt` *(only if live audit fails)*
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt`
- Checklist:
  - [ ] Audit `SEMI_ANNUALLY`, `ANNUALLY`, and `IRREGULAR` handling in both next-date calculation and monthly-total conversion.
  - [ ] If live code is already enum-driven and compliant, keep production code stable and tighten proof in tests only.
  - [ ] If a regression remains, fix only the missing `SEMI_ANNUALLY` / monthly-equivalent path; do **not** widen into unrelated B.39 reminder urgency or `markBillPaid` semantics.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.reminder.BillReminderManagerTest"`
- Rollback / stop rule:
  - If closing this row requires centralizing recurrence across `RecurrenceCalculator` / `RecurringExpenseRepository`, stop and split that broader semantic-drift fix.
- Done when:
  - `SEMI_ANNUALLY` handling is either fixed or proven compliant in reminder scheduling and monthly totals.

### 4. Verification Plan
- **Static verification after each batch:**
  - Re-read every modified file.
  - Confirm imports/signatures remain local to the batch.
  - Grep for forbidden leftovers before moving on:
    - `replace(",", "").toDoubleOrNull()` under `app/src/main/java/com/yourname/expensetracker/data/email/provider/`
    - `matcher.group(1)` in provider date extractors that still use mixed capture layouts
    - `override fun onError(error: Int) = Unit` in `AndroidSpeechInputGateway.kt`
    - `transfer received` still sitting only in deposit-only signal lists in `GenericTransactionParser.kt`
- **Serialized Gradle lane (orchestrator-owned):** B.11 verification must run one pipeline at a time per the playbook; do not overlap this lane with other active Phase B compile/test runs.
- **Per-batch minimum gate:** `./gradlew.bat :app:compileDebugKotlin`
- **Targeted final B.11 verification lane (after all batches):**
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.EmailReceiptIngestionServiceTransactionTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.EmailReceiptParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.AmazonReceiptParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.AppleReceiptParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.email.provider.UberReceiptParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.parser.GenericTransactionParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.parser.GoogleWalletParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.speech.AndroidSpeechInputGatewayTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageSearchEngineVoiceInputTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.BankStatementParserTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.OcrLanguageProcessorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.reminder.BillReminderManagerTest"`
- **Completion gate:** B.11 is not complete until reviewer PASS, registry updates, final-verification updates, and any audit-only dispositions are documented in the same closeout.

### 5. Documentation & Registry Updates
- After reviewer PASS, update `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` in the `### B.11: Email/Parsing Pipeline` section (currently the HIGH bullets near lines `607-621`):
  - mark each fixed HIGH row with `[RESOLVED BY B.11]`, or
  - if a row was already compliant in live code (`BankStatementParser`, `OcrLanguageProcessor`, `BillReminderManager`, or the messageId/non-REPLACE portion of email ingestion), note it as verified during B.11 rather than silently rewriting history.
- Update the exact final-verification files tied to B.11 batches:
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-31.md` — email ingestion, provider parser, and speech-input rows
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-43.md` — `GenericTransactionParser`, `GoogleWalletParser`, and `BillReminderManager` rows
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-44.md` — `BankStatementParser` and `OcrLanguageProcessor` rows
- Apply the playbook ripple-effect rule: if Batch 1’s transaction fix directly resolves the separate email partial-write row elsewhere in the registry, mark that downstream row `[RESOLVED BY B.11]` in the same documentation pass.
- Create/update `docs/reviews/REVIEW-B11.md` with:
  - batch-by-batch file list,
  - focused verification evidence,
  - any audit-only “already compliant” dispositions,
  - any explicit waivers or deferred follow-ups.
- Documentation order must stay:
  1. `MASTER-ISSUE-REGISTRY.md`
  2. exact `FINAL-VERIFICATION-BATCH-31.md` / `43.md` / `44.md`
  3. matching deep-analysis mirror rows only if they exist and only after the first two are complete.

### Risks
- Transaction rollback can be falsely “tested” if mocked too loosely; use a real Room transaction proof for the rollback path.
- Locale-aware parsing can easily regress existing English fixtures if helper rules are too broad or too aggressive.
- Transfer heuristics may accidentally relabel salary/refund flows as transfers if direction detection is not separated from income detection.
- Speech-input contract changes can ripple upward if hidden callers exist; keep the API extension narrow and backward-compatible.
- Audit-only rows (`BankStatementParser`, `OcrLanguageProcessor`, `BillReminderManager`) create scope-creep pressure toward unrelated B.44/B.39 fixes; resist widening beyond the named HIGH rows.

### Acceptance Criteria
- [ ] `EmailReceiptIngestionService` cannot return `Success` without persisted expense IDs, and failed expense creation rolls back receipt/source writes.
- [ ] `messageId` remains the first dedupe gate when present; fingerprint is fallback-only for blank IDs.
- [ ] Base email parsing preserves semantic line boundaries and decodes HTML entities before provider matching.
- [ ] Amazon, Apple, and Uber provider parsers no longer depend on broken capture-group assumptions and can parse localized amount/date fixtures.
- [ ] `GenericTransactionParser` and `GoogleWalletParser` emit `TRANSFER` for transfer/P2P wording and preserve direction metadata.
- [ ] `AndroidSpeechInputGateway` guards permission/startup and surfaces recognizer errors instead of dropping them.
- [ ] `BankStatementParser`, `OcrLanguageProcessor`, and `BillReminderManager` B.11 HIGH rows are either fixed or proven compliant with targeted tests.
- [ ] `:app:compileDebugKotlin` plus the targeted B.11 test lane pass in the orchestrator’s serialized verification lane.
- [ ] Registry, final-verification docs, and `REVIEW-B11.md` are updated in the required order.
