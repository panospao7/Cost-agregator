# PLAN-B3-Batch6C — SMS/Revolut Parser Thousands-Separated Amounts

## 1. Objective & Blast Radius
- **The Core Issue:** `SmsParser` and standalone `RevolutParser` notification regexes only accept a single decimal separator, so grouped amounts like `1,234.56` or `1.234,56` can fail outright or parse only part of the number. `SmsParser.detectSmsDirection()` also defaults ambiguous transfer wording to `INCOMING`, biasing unclear transfers toward incoming money instead of leaving the direction unknown.
- **Blast Radius:**
  - `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/RevolutParser.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/SmsParser.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/util/CommonPatterns.kt` (only if a shared grouped-amount fragment is introduced)
  - `app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
  - Notification-ingested expenses/reviews and every downstream dashboard/analytics surface after those notifications are accepted or approved
- **Assumptions / Unknowns:**
  - `TransferDirectionDetector` already returns `null` for clearly ambiguous wording; Batch 6C should let ambiguity survive rather than fabricate `INCOMING` in the parser layer.
  - Standalone `RevolutParser` transaction-type mapping is otherwise acceptable for this batch; Batch 6C is primarily about grouped-amount support and SMS transfer-direction ambiguity.
  - The user-supplied Batch 44 issue text lists the grouped-regex bug beside B.3 items, but the live registry currently stores that exact bullet under `### B.6: Notification/Service/Worker Pipeline`. Documentation must update the live location in place instead of creating a duplicate under B.3.

## 2. The Single Source of Truth (The Standard)
- The canonical numeric rule for both parsers is: **capture the full raw grouped amount token adjacent to a real currency token and pass it to `AmountUtils.parseAmount()` unchanged.**
- The preferred utility for this batch is: **one shared grouped-amount token fragment in `CommonPatterns.kt` reused by `RevolutParser` and `SmsParser`, while leaving the existing broad `AMOUNT_REGEX` behavior untouched.**
- The canonical SMS direction rule is:
  - `TRANSFER`: incoming evidence wins -> `INCOMING`; outgoing evidence wins -> `OUTGOING`; tie/unknown -> `null`
  - `DEPOSIT`: keep `INCOMING` only when there is explicit deposit/incoming evidence; otherwise `null`
- Preserve public contracts:
  - do **not** change parser constructor signatures
  - do **not** add a neutral transfer-direction enum
  - do **not** change `ParsedTransaction` fields or validation rules in this batch

## 3. File-by-File Execution Checklist

### Domain Layer

#### Batch 1 — Shared grouped-amount token + standalone `RevolutParser`
- **Complete when:** standalone Revolut notifications parse grouped amounts through `AmountUtils` without regressing current purchase/transfer/deposit/withdrawal behavior.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/util/CommonPatterns.kt`
  - Add one reusable grouped-amount token fragment/string suitable for embedding inside parser-specific regexes.
  - Keep it narrow enough for currency-adjacent money parsing; it should not become a new generic catch-all for IDs or dates.
  - Do **not** change the semantics of the existing `AMOUNT_REGEX` in this batch.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/RevolutParser.kt`
  - Replace the single-separator numeric subpatterns in `PAID_AT_PATTERN`, `PAID_TO_PATTERN`, `RECEIVED_PATTERN`, and `ATM_PATTERN` with the shared grouped-amount token.
  - Keep existing verb anchors and merchant/counterparty capture structure intact.
  - Parse the captured raw amount token with `AmountUtils.parseAmount()`.
  - Preserve reject patterns and current transaction-type mapping.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt`
  - Audit only.
  - Confirm no routing change is needed once `RevolutParser` stops returning `null` for grouped-amount notifications.
  - Do **not** reorder parser priority in this batch.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/parser/RevolutParserTest.kt`
  - Add grouped-amount regressions for:
    - purchase
    - outgoing transfer
    - incoming transfer / add-money
    - ATM withdrawal
  - Assert amount, currency, merchant/counterparty, and `ParsedTransactionType`.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/parser/AppParserRegistryTest.kt`
  - Add at least one package-routed Revolut grouped-amount case to prove the real parser succeeds without falling through to generic fallback.

> [!WARNING]
> - Do **not** touch `BankStatementParser.kt` here; Batch 6B owns statement parsing.
> - Do **not** broaden `RevolutParser` into unrelated trailing-currency or integer-only formats unless an existing production test proves the need.
> - Do **not** use `RevolutParserStressTest.kt` as primary evidence; it does not instantiate the production parser.

#### Batch 2 — `SmsParser` grouped amounts + ambiguous transfer direction
- **Complete when:** grouped SMS amounts parse correctly and ambiguous transfer SMS messages no longer default to incoming.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/SmsParser.kt`
  - Replace the current one-separator `amountPattern` with a currency-adjacent grouped-amount pattern that captures the full token with currency on either side.
  - Reuse the shared grouped-amount token from `CommonPatterns.kt` if Batch 1 introduced it.
  - Keep currency adjacency mandatory so dates, card suffixes, and IDs are not misread as money.
  - Parse the captured raw amount token with `AmountUtils.parseAmount()`.
  - Change `detectSmsDirection()` so ambiguous `TRANSFER` cases return `null`.
  - For `DEPOSIT`, keep `INCOMING` only when the message has explicit deposit/incoming evidence; otherwise return `null`.
  - Allow ambiguous direction to suppress `transferAccountName` prefixing rather than inventing `From:` / `To:` labels.
  - Keep sender validation, package support, and merchant extraction logic unchanged unless a test proves a direct dependency.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/parser/SmsParserTest.kt`
  - Add grouped-amount regressions for:
    - purchase SMS
    - transfer SMS
  - Add an ambiguous transfer regression asserting `transferDirection == null`.
  - Add an explicit deposit/incoming regression confirming intended `INCOMING` behavior still works when wording is clear.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/parser/AppParserRegistryTest.kt`
  - Add at least one package-routed SMS grouped-amount case so the registry path is covered through the real parser.

> [!WARNING]
> - Do **not** inject `TransferDirectionDetector` into `SmsParser` or change its constructor signature.
> - Do **not** add a neutral transfer-direction enum or modify `ParsedTransaction`.
> - If broadened regex starts swallowing dates/card numbers, tighten currency adjacency instead of reverting to one-separator parsing.

### Data Layer

#### Batch 3 — Notification-ingestion / reparse audit
- **Complete when:** grouped-amount and null-direction parser outputs flow through existing repository consumers without unnecessary contract changes.

- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
  - Read first.
  - Confirm that parser-supplied grouped amounts now pass through unchanged.
  - Audit the `parsed.transferDirection ?: directionDetector.detectDirection(...)` fallback carefully.
  - If ambiguous SMS transfer `null` values are being re-hydrated into a false direction during ingestion, add the smallest possible guard to preserve parser ambiguity for that path only.
  - Do **not** change routing, dedupe, budget hooks, or review-creation semantics unless directly required by the ambiguity fix.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineReliabilityTest.kt`
  - If `NotificationProcessingPipeline.kt` changes, add a focused regression proving an ambiguous SMS transfer can remain `transferDirection = null` end-to-end.
  - If no pipeline code change is needed, run this file unchanged as a regression guard only.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
  - Audit only.
  - Confirm grouped-amount parser fixes flow through `parserRegistry.parse()` with no API edits.
  - Do **not** add transfer-direction persistence in this batch.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryTest.kt`
  - Touch only if `ReviewQueueRepository.kt` changes.
  - Keep any new regression tightly scoped to parser-output consumption.

> [!WARNING]
> - Do **not** widen repository/database models to preserve parser ambiguity.
> - Do **not** add package-specific hacks unless a failing regression proves the generic fallback is reintroducing the bug.

### UI Layer

- **No planned UI code changes.**
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
  - Audit only if Batch 3 discovers a user-visible null-direction rendering problem.
  - Otherwise leave untouched.

> [!WARNING]
> - Batch 6C should not redesign review or dashboard UI.

## 4. Verification Plan
- **Unit Tests:**
  - Update and run:
    - `app/src/test/java/com/yourname/expensetracker/domain/parser/RevolutParserTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/parser/SmsParserTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/parser/AppParserRegistryTest.kt`
  - Run unchanged as route/regression guards:
    - `app/src/test/java/com/yourname/expensetracker/domain/parser/AppParserRegistryRoutingTest.kt`
  - If Batch 3 changes pipeline/reparse code, update/run:
    - `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineReliabilityTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryTest.kt` *(only if that production file changes)*
- **Compile / verification order:**
  - After each micro-batch, run:
    - `./gradlew.bat :app:compileDebugKotlin`
    - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.parser.RevolutParserTest" --tests "com.yourname.expensetracker.domain.parser.SmsParserTest" --tests "com.yourname.expensetracker.domain.parser.AppParserRegistryTest"`
  - Add the narrowest extra test command only if Batch 3 changes repository consumers.
- **Syntax/Lint:**
  - Ensure no broken imports after introducing any shared grouped-amount token in `CommonPatterns.kt`.
  - Ensure regex escaping remains valid in Java `Pattern.compile(...)` strings.
  - Ensure no stale comments still claim one-separator-only amount support.
- **Reviewer gate:**
  - Reviewer should explicitly inspect one grouped US-format amount and one grouped EU-format amount per parser.
  - Reviewer should confirm ambiguous SMS transfers now stay `null` unless there is clear directional evidence.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, under `### B.3: Receipt/OCR Pipeline`, append `[RESOLVED BY B.3 — Batch 6C]` to:
    - current line ~249: ``SmsParser.detectSmsDirection()` returns `INCOMING` on tie/unknown for transfers — ambiguous transfers labeled as incoming money (B44)``
  - In the same file, under `### B.6: Notification/Service/Worker Pipeline`, append `[RESOLVED BY B.3 — Batch 6C]` to:
    - current line ~397: ``SmsParser` and `RevolutParser` amount regex only accepts single decimal separator — thousands-separated amounts rejected (B44-missed)``
  - Do **not** create a duplicate grouped-regex bullet under B.3; update the live B.6 location in place.
- **Batch Reports:**
  - Update `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-44.md`
    - resolve / annotate verified issue row **1** (`SmsParser.detectSmsDirection()` ambiguity)
    - resolve / annotate missed issue rows **1 and 2** (standalone `RevolutParser` / `SmsParser` grouped-amount regexes)
  - Update `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-44.md`
    - resolve / annotate issue row **1**
    - resolve / annotate cross-component issue **#2** only if reviewer confirms the standalone-vs-statement Revolut semantics drift is fully closed by Batch 6B + Batch 6C together
  - Update `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-44-DEBUGGER.md`
    - resolve / annotate issue row **1**
    - resolve / annotate cross-component issue **C1** only if reviewer confirms the ambiguity fix is fully satisfied
  - Do **not** invent grouped-regex resolved rows in deep-analysis files where that issue never appeared.
- **Documentation sequencing rule:**
  - Do docs only after reviewer PASS.
  - Update registry first, then `FINAL-VERIFICATION-BATCH-44.md`, then deep-analysis mirror(s).
  - Do **not** mark the whole `B.3` or `B.6` pipeline resolved.
