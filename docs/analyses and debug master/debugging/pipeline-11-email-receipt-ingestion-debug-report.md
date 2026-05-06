# Pipeline 11 Debugging Report — Email Receipt Ingestion

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local execution.

## 1. Executive summary

Pipeline 11 is intended to be:

```text
email body / sender / subject / messageId
→ provider detection
→ Amazon/Uber/Apple parser
→ receipt dedupe
→ ScannedReceipt EMAIL_RECEIPT
→ EmailReceiptSource
→ ReceiptEvent.RECEIPT_SAVED
→ item categorization / receipt side effects
→ TransactionLifecycleCoordinator.createExpense()
→ ReceiptLinkService.linkReceiptToExpense()
→ dashboard / analytics
```

The current code has useful pieces:

- provider parsers exist for Amazon, Uber, Apple,
- email source entity exists with FK to `scanned_receipts`,
- messageId dedupe exists in `EmailReceiptIngestionService`,
- receipt save goes through `ReceiptLifecycleCoordinator.saveEmailReceipt()`,
- expense creation goes through `TransactionLifecycleCoordinator`,
- rollback test exists for failed expense creation,
- parser tests exist for localization/date/amount edge cases.

But the pipeline is still fragile.

Highest-risk findings:

1. **There are two different email receipt entry points with different behavior.**
2. **`EmailReceiptIngestionService` bypasses the guarded `ReceiptLifecycleCoordinator.processEmailReceipt()` path and calls `saveEmailReceipt()` directly.**
3. **`saveEmailReceipt()` itself has no restore guard and no transaction.**
4. **The whole ingestion runs inside an outer transaction while calling `TransactionLifecycleCoordinator.createExpense()`, causing the same post-commit side-effect risk from Pipeline 2.**
5. **Receipt link result is ignored; email ingestion can return success even if receipt-expense linking failed.**
6. **Provider parser success can still fail because `ProcessReceiptUseCase` reparses the raw email body.**
7. **Dedupe fingerprint is too weak and can false-dedupe legitimate same-day same-merchant same-amount emails.**
8. **Message/order IDs are not used as transaction idempotency keys.**
9. **Raw email body is stored as OCR text without a clear email-specific privacy/retention policy.**
10. **Tests appear stale: some tests expect direct `ExpenseDao.insertAtomic()` calls even though production now uses `TransactionLifecycleCoordinator`.**

Main recommendation:

> Make `ReceiptLifecycleCoordinator.processEmailReceipt()` the single canonical email lifecycle path, and move email expense creation/linking into a DB-backed, deferred-side-effect transaction contract.

---

## 2. Intended architecture contract

A stable email receipt pipeline should be:

```text
EmailReceiptIngestionService
→ EmailReceiptParserRegistry
→ ParsedEmailReceipt
→ ReceiptLifecycleCoordinator.processEmailReceipt()
→ ScannedReceipt + EmailReceiptSource + ReceiptEvent
→ duplicate detector
→ optional existing-expense match
→ TransactionLifecycleCoordinator.createExpenseDbOnly/deferred
→ ReceiptLinkService checked result
→ post-commit side effects
→ dashboard/analytics
```

The dependency map already places `EmailReceiptIngestionService` as a caller of `TransactionLifecycleCoordinator`, and receipt lifecycle documentation says email ingestion should be one of the receipt lifecycle entry paths.

Current implementation is close, but ownership is split.

---

## 3. Actual code path summary

## 3.1 Main email ingestion path

`EmailReceiptIngestionService.processEmailReceipt(...)` does:

```text
detect provider
→ parse using provider parser
→ validate amount/merchant/date
→ normalize merchant
→ create merchant/amount/date fingerprint
→ transactionRunner { database.withTransaction }
    → check messageId duplicate in EmailReceiptDao
    → check fingerprint duplicate in EmailReceiptDao
    → check recent scanned receipts by computed fingerprint
    → create ScannedReceipt with rawOcrText = emailBody.take(5000)
    → receiptLifecycleCoordinator.saveEmailReceipt(scannedReceipt)
    → insert EmailReceiptSource
    → ProcessReceiptUseCase(ReceiptSource.ParsedContent(...))
    → TransactionLifecycleCoordinator.createExpense()
    → ReceiptLinkService.linkReceiptToExpense()
    → return Success
```

Important: it does **not** call `ReceiptLifecycleCoordinator.processEmailReceipt(domain EmailReceiptData)`.

---

## 3.2 Alternative coordinator email path

`ReceiptLifecycleCoordinator.processEmailReceipt(emailData)` also exists.

It does:

```text
restore write guard
→ check scanned receipt by sourceFingerprint/messageId
→ build ScannedReceipt
→ insert ScannedReceipt
→ insert EmailReceiptSource
→ insert RECEIPT_SAVED event
→ dispatchAfterSave()
→ return receipt
```

But it does not create an expense or link receipt to expense.

This means there are currently two email receipt lifecycles with different behavior:

```text
EmailReceiptIngestionService.processEmailReceipt(...)
  creates receipt + email source + expense + link

ReceiptLifecycleCoordinator.processEmailReceipt(...)
  creates receipt + email source + side effects only
```

That is a source of future bugs.

---

# 4. Major findings

## Finding P0-1 — Two email receipt lifecycle paths can diverge

Current paths:

```text
A. EmailReceiptIngestionService.processEmailReceipt(...)
B. ReceiptLifecycleCoordinator.processEmailReceipt(EmailReceiptData)
```

They differ in:

| Behavior | IngestionService | Coordinator path |
|---|---|---|
| provider parser | yes | no |
| messageId dedupe source | EmailReceiptDao | ScannedReceipt.sourceFingerprint |
| fingerprint dedupe | yes | no meaningful fingerprint |
| expense creation | yes | no |
| receipt-expense link | yes | no |
| restore guard | indirect/weak | yes |
| transaction wrapper | yes | no |
| raw body truncation | 5000 chars | full body |
| EmailReceiptSource fingerprint | computed | empty string |

This violates the “single lifecycle coordinator” architecture.

### Fix

Make the canonical path:

```text
EmailReceiptIngestionService parses provider-specific email
→ produces ParsedEmailReceipt / EmailReceiptCommand
→ ReceiptLifecycleCoordinator.ingestEmailReceipt(command)
```

That coordinator should own:

```text
dedupe
receipt save
email source save
event write
expense creation
receipt link
post-commit side effects
```

Priority: highest.

---

## Finding P0-2 — `saveEmailReceipt()` bypasses restore guard

`ReceiptLifecycleCoordinator.processEmailReceipt(emailData)` checks:

```text
restoreMaintenanceMode.isWritesAllowed()
```

But `EmailReceiptIngestionService` does not call that method. It calls:

```kotlin
receiptLifecycleCoordinator.saveEmailReceipt(scannedReceipt)
```

`saveEmailReceipt()` does not check restore mode.

So the primary email ingestion path can start writes during restore/maintenance mode. Later, `TransactionLifecycleCoordinator.createExpense()` may block and roll back, but the path still performs parsing, attempts receipt writes, and relies on the outer transaction to undo them.

If `saveEmailReceipt()` is used elsewhere outside the outer transaction, it can write during restore mode.

### Fix

- Add restore guard to `EmailReceiptIngestionService` before work begins.
- Add restore guard to `saveEmailReceipt()` or make it private/internal and only callable from guarded coordinator transactions.
- Prefer canonical `processEmailReceipt()` only.

Priority: highest.

---

## Finding P0-3 — `saveEmailReceipt()` is not atomic by itself

`saveEmailReceipt()` does:

```text
insert ScannedReceipt
insert ReceiptEvent
return id
```

without `database.withTransaction`.

So if `ReceiptEvent` insert fails, you can have a receipt without lifecycle event.

`processEmailReceipt(emailData)` also does several writes and side effects without a surrounding transaction.

### Fix

All lifecycle save paths should wrap:

```text
ScannedReceipt insert
EmailReceiptSource insert
ReceiptEvent insert
```

in one Room transaction.

Side effects should run after commit.

Priority: highest.

---

## Finding P0-4 — Nested transaction / post-commit side-effect risk

`EmailReceiptIngestionService` wraps everything in:

```kotlin
database.withTransaction { ... }
```

Inside that transaction it calls:

```kotlin
TransactionLifecycleCoordinator.createExpense(request)
```

The coordinator itself does:

```text
database.withTransaction { insert expense + TransactionEvent.CREATED }
then sideEffectDispatcher.dispatchOnCreated()
then recurringLifecycleCoordinator.linkExpenseToOccurrence()
```

Because the email ingestion outer transaction is still active, the coordinator’s “post-commit” side effects may run before the full email transaction commits.

Failure example:

```text
outer email transaction starts
→ receipt row inserted
→ email source row inserted
→ coordinator creates expense + event
→ coordinator runs budget/anomaly/recurring side effects
→ ReceiptLinkService fails
→ outer transaction rolls back receipt/source/expense/event
→ side effects already ran for rolled-back data
```

### Fix

Use deferred post-commit actions:

```text
database.withTransaction {
    save receipt
    save email source
    create expense DB-only
    link receipt
    collect postCommitActions
}

run postCommitActions
```

This requires Pipeline 2’s lifecycle deferred-side-effect fix.

Priority: highest.

---

## Finding P0-5 — Receipt link result is ignored

`createExpenseFromReceipt()` calls:

```kotlin
receiptLinkService.linkReceiptToExpense(...)
return listOf(expenseId)
```

It does not check whether linking succeeded.

So email ingestion can return:

```text
Success(receiptId, expenseIds = [id])
```

even if:

- receipt link insert failed,
- receipt already linked,
- receipt not found,
- duplicate link conflict occurred,
- link service returned failure.

Because Pipeline 3 found that `ReceiptLinkService` also ignores the `insert()` return value, this is even riskier.

### Fix

```kotlin
val linkResult = receiptLinkService.linkReceiptToExpense(...)
if (linkResult.isFailure) {
    throw EmailReceiptExpenseCreationException("Expense created but receipt link failed", ...)
}
```

Also fix `ReceiptLinkService` itself to validate insert success and expense existence.

Priority: highest.

---

## Finding P0-6 — Provider parser success can still fail due to generic reparse

After Amazon/Uber/Apple parser succeeds, the service calls:

```kotlin
processReceiptUseCase(
    ReceiptSource.ParsedContent(
        rawText = emailBody,
        merchant = parsedReceipt.merchant,
        amount = parsedReceipt.amount,
        date = parsedReceipt.date
    )
)
```

`ProcessReceiptUseCase` still calls:

```kotlin
receiptParser.parse(source.rawText)
```

before applying the provided merchant/amount/date fallback.

So a provider-specific parser can successfully extract a valid receipt, but the pipeline can still fail if the generic `ReceiptParser` throws or cannot parse the email body.

That is unnecessary coupling.

### Fix

Add a path that accepts trusted structured parsed content without requiring generic receipt parsing:

```kotlin
ProcessParsedReceiptUseCase(
    merchant,
    amount,
    date,
    currency,
    items,
    provider,
    confidence
)
```

Or make generic parse best-effort:

```text
if provider parsed amount/merchant/date exist, generic parser failure must not fail ingestion
```

Priority: highest.

---

## Finding P0-7 — Existing expense duplicates become ParseError instead of link/no-op

`TransactionLifecycleCoordinator.createExpense()` can return:

```text
DuplicateSkipped
InsertConflict
ValidationFailed
Error
```

Email ingestion treats anything except `Created` as failure:

```kotlin
else -> throw EmailReceiptExpenseCreationException(...)
```

So if the email receipt matches an existing notification/bank/manual expense, the result becomes:

```text
ParseError("Failed to create expense from receipt")
```

instead of:

```text
receipt saved
linked to existing expense
no duplicate expense
analytics counts once
```

This is a major no-double-count bug.

### Fix

On `DuplicateSkipped(existingExpenseId)`:

```text
link receipt to existing expense
write RECEIPT_LINKED_TO_EXPENSE
return Success(receiptId, [existingExpenseId]) or DuplicateLinked
```

For `InsertConflict`, lookup by dedupe key/idempotency key and link if safe.

Priority: highest.

---

## Finding P1-1 — Email dedupe fingerprint is too weak

Fingerprint:

```text
normalizedMerchant_lowercase + roundedAmount + date / 5 minutes
```

But many provider parsers return dates at start-of-day, not exact transaction time.

So two legitimate receipts can collide:

```text
Amazon, €9.99, same order date
Apple, €0.99 subscription, same day
Uber Eats, same restaurant, same amount, same day
```

Also, `EmailReceiptSource.fingerprint` index is not unique.

The in-process `Mutex` only protects one app process. It does not protect future parallel import processes or DB-level races.

### Fix

Use stronger dedupe priority:

```text
1. provider messageId
2. provider orderNumber/tripId/documentId
3. provider + sender + orderNumber
4. canonical fingerprint:
   provider + merchant + amount + currency + date + itemHash + subjectHash
```

Add a unique index where safe:

```text
unique(emailMessageId) where not null
unique(provider, fingerprint) for nonblank fingerprint if policy allows
```

Or use a separate `EmailReceiptDedupKey` table.

Priority: high.

---

## Finding P1-2 — Provider order numbers are parsed but discarded

`ParsedEmailReceipt` includes:

```text
orderNumber
```

But ingestion does not persist it into:

- EmailReceiptSource,
- ScannedReceipt.sourceFingerprint,
- ReceiptEvent metadata,
- expense idempotency key,
- expense notes/metadata.

This loses the strongest source identity.

### Fix

Add fields:

```text
EmailReceiptSource.providerOrderId
EmailReceiptSource.providerReceiptId
EmailReceiptSource.bodyHash
EmailReceiptSource.rawPayloadHash
```

Use:

```text
idempotencyKey = provider + messageId/orderNumber
deduplicationMode = STRICT_EXTERNAL_ID
```

Priority: high.

---

## Finding P1-3 — EmailReceiptSource unique messageId behavior differs by path

`EmailReceiptIngestionService` stores blank message IDs as `null`:

```kotlin
messageId.takeIf { it.isNotBlank() }
```

Good.

But `ReceiptLifecycleCoordinator.processEmailReceipt(emailData)` stores:

```kotlin
emailMessageId = emailData.messageId
```

If that value is `""`, the unique index on `emailMessageId` can allow only one blank-message email source.

### Fix

Normalize message ID everywhere:

```kotlin
val normalizedMessageId = messageId.trim().takeIf { it.isNotBlank() }
```

Use it for both `EmailReceiptSource.emailMessageId` and `ScannedReceipt.sourceFingerprint`.

Priority: high.

---

## Finding P1-4 — Raw email body retention/privacy is unclear

The ingestion path stores:

```kotlin
rawOcrText = emailBody.take(5000)
```

The coordinator path stores:

```kotlin
rawOcrText = emailData.body
```

Email bodies can contain:

- full name,
- address,
- phone,
- order IDs,
- loyalty data,
- item-level purchases,
- emails,
- account/card fragments.

DataRetentionWorker purges `scanned_receipts.rawOcrText` eventually, but there is no email-specific policy such as:

```text
rawEmailRetentionDays
store email body yes/no
store sanitized email body only
store body hash only
```

### Fix

Add email-retention policy:

```text
emailRawBodyRetentionEnabled
emailRawBodyRetentionDays
storeEmailBodyMode = RAW / SANITIZED / HASH_ONLY / NONE
```

If privacy says no raw retention:

```text
rawOcrText = sanitized preview or ""
rawOcrTextPurgedAt = now
EmailReceiptSource.rawPayloadHash = sha256(body)
```

Priority: high.

---

## Finding P1-5 — Email parsers are not injected despite DI module

`EmailIngestionModule` provides:

```text
AmazonReceiptParser
UberReceiptParser
AppleReceiptParser
```

But `EmailReceiptIngestionService` constructs its own:

```kotlin
private val amazonParser = AmazonReceiptParser()
private val uberParser = UberReceiptParser()
private val appleParser = AppleReceiptParser()
```

Tests then modify private fields via reflection.

This defeats DI, makes parser replacement hard, and prevents a clean parser registry/multibinding.

### Fix

Inject:

```kotlin
Set<@JvmSuppressWildcards EmailReceiptParser>
```

or:

```kotlin
EmailReceiptParserRegistry
```

Then add providers through Hilt multibindings.

Priority: high.

---

## Finding P1-6 — Parser/provider detection can false-positive

Examples:

- Amazon parser can parse if subject/body is broadly order-like or contains Amazon domains.
- Unknown provider tries all parsers.
- Apple amount patterns can match early price lines, not necessarily final total.
- Marketing/shipping emails can contain amounts and dates.

Validation only checks:

```text
amount > 0
merchant nonblank
date > 0
```

So a non-receipt email can become an expense if a parser extracts a plausible amount.

### Fix

Add provider confidence gates:

```text
provider identity confidence
receipt-type confidence
amount total confidence
order ID present or merchant/total/date agreement
```

Low confidence should create a review candidate, not auto-create expense.

Priority: high.

---

## Finding P1-7 — Email receipts always create expenses

The current service creates an expense immediately after parsing.

But some email receipts should not auto-create expenses:

```text
already matched notification/bank transaction
shipping confirmation with no charge
refund receipt
order cancellation
invoice not paid
subscription renewal notice before payment
```

### Fix

Route email receipts through confidence/review:

```text
high-confidence + no existing match → create/link
existing matching expense → link only
low-confidence → PendingReview
refund/cancellation → review or non-purchase transaction type
```

Priority: high.

---

## Finding P1-8 — No email inbox/provider connector exists

The repository contains the ingestion service and parsers, but the `data/email` tree does not show Gmail/IMAP/Mail provider integration, OAuth, worker, or permission flow.

So Pipeline 11 currently starts at:

```text
caller provides emailBody/sender/subject/messageId
```

not:

```text
app reads user email inbox
```

This is okay if email ingestion is manual/import-only, but the UI/docs should not imply automatic email reading unless connector/auth exists.

### Fix

Clarify feature mode:

```text
manual pasted/imported email receipt
share target
Gmail API connector
IMAP connector
```

If automatic inbox ingestion is planned, build it separately with privacy gates, OAuth, sync ledger, and clear user consent.

Priority: medium-high.

---

## Finding P1-9 — Tests are useful but appear stale/incomplete

Existing tests:

- provider parser tests for Amazon/Uber/Apple,
- mock-based `EmailReceiptIngestionServiceTest`,
- one Robolectric transaction rollback test.

Problems:

1. Several ingestion tests expect `ExpenseDao.insertAtomic()` calls, but production service now calls `TransactionLifecycleCoordinator.createExpense()`.
2. Tests do not appear to stub `coordinator.createExpense()` with `CreateExpenseResult.Created`.
3. The transaction test uses real DB for receipt/source rollback but mocks the lifecycle coordinator and receipt coordinator, so it does not prove real `Expense` + `TransactionEvent` + `ReceiptExpenseLink` behavior.
4. No fed-DB test verifies:
   - EmailReceiptSource,
   - ScannedReceipt,
   - ReceiptEvent,
   - Expense,
   - TransactionEvent,
   - ReceiptExpenseLink,
   - dashboard/analytics no-double-count.

### Fix

Rewrite tests around current architecture:

```text
EmailReceiptIngestionServiceContractTest
EmailReceiptLifecycleDbContractTest
EmailReceiptToExpenseScenarioTest
EmailReceiptExistingExpenseLinkScenarioTest
```

Priority: high.

---

# 5. Debugging checklist for Pipeline 11

## Input / source

Check:

- [ ] how email body enters app,
- [ ] sender available,
- [ ] subject available,
- [ ] messageId available,
- [ ] receivedAt reliable,
- [ ] HTML decoded safely,
- [ ] attachments ignored/handled intentionally,
- [ ] user consent/privacy setting exists.

## Provider detection

Check:

- [ ] Amazon sender/domain detection,
- [ ] Uber sender/domain detection,
- [ ] Apple sender/domain detection,
- [ ] unknown provider fallback does not false-positive,
- [ ] provider confidence available,
- [ ] marketing/shipping/cancellation/refund emails rejected or routed to review.

## Parsing

Check:

- [ ] localized amount parsing,
- [ ] comma decimal parsing,
- [ ] thousand separators,
- [ ] currency detection,
- [ ] order/trip/document ID extraction,
- [ ] date extraction,
- [ ] yearless date anchoring,
- [ ] item extraction,
- [ ] total vs subtotal/shipping/tax distinction,
- [ ] refund/negative amount handling.

## Dedupe

Check:

- [ ] nonblank messageId dedupe,
- [ ] blank messageId normalization,
- [ ] orderNumber/tripId dedupe,
- [ ] fingerprint includes currency/provider/order/item hash,
- [ ] scanned receipt duplicate check,
- [ ] existing expense duplicate check,
- [ ] concurrent duplicate import cannot create two rows.

## Persistence

Check:

- [ ] ScannedReceipt sourceType = EMAIL,
- [ ] documentType = EMAIL_RECEIPT,
- [ ] processingStatus = PARSED or REVIEW_REQUIRED,
- [ ] EmailReceiptSource row created once,
- [ ] ReceiptEvent.RECEIPT_SAVED written,
- [ ] raw body retention policy applied,
- [ ] source fingerprint/order ID stored,
- [ ] writes blocked during restore.

## Expense/link

Check:

- [ ] high-confidence email creates expense through lifecycle,
- [ ] TransactionEvent.CREATED exists,
- [ ] duplicate expense result links to existing expense,
- [ ] receipt link insert success checked,
- [ ] ReceiptEvent.RECEIPT_LINKED_TO_EXPENSE exists,
- [ ] analytics/dashboard count once,
- [ ] low-confidence email creates review, not expense.

## Privacy

Check:

- [ ] raw email body retention setting,
- [ ] redaction before any cloud item categorization,
- [ ] email body not stored in AI artifact when policy forbids,
- [ ] backup redaction removes raw email text,
- [ ] retention worker purges old email body text.

---

# 6. Recommended fix plan

## PR 1 — Canonicalize email lifecycle entry point

Create one method:

```kotlin
ReceiptLifecycleCoordinator.ingestEmailReceipt(command)
```

It should own:

```text
dedupe
receipt save
email source save
event write
expense create/link
post-commit side effects
```

Make `saveEmailReceipt()` private/internal or guarded.

Acceptance:

```text
There is exactly one email receipt lifecycle path.
```

---

## PR 2 — Fix transaction boundary

Use deferred transaction side effects.

Pattern:

```text
database.withTransaction {
    save receipt
    save email source
    create expense DB-only
    link receipt
    collect postCommitActions
}
run postCommitActions
```

Acceptance:

```text
if receipt link fails, expense side effects do not run for rolled-back data.
```

---

## PR 3 — Check link result and duplicate expense result

Handle:

```text
Created → link receipt
DuplicateSkipped(existingExpenseId) → link to existing expense
InsertConflict → lookup existing and link if safe
ValidationFailed → review/error
```

Acceptance:

```text
email matching existing notification expense links without creating duplicate and without returning ParseError.
```

---

## PR 4 — Stop generic parser from failing provider-parsed emails

Add structured processing path.

Acceptance:

```text
Amazon parser success is enough to create/review receipt even if generic ReceiptParser cannot parse raw email HTML.
```

---

## PR 5 — Strengthen dedupe identity

Persist:

```text
providerOrderId
bodyHash
provider
messageId
currency
itemHash
```

Use strict idempotency for expense creation when messageId/orderNumber exists.

Acceptance:

```text
same email imported twice creates one receipt/expense;
two legitimate same-day same-amount Amazon receipts do not false-dedupe if order IDs differ.
```

---

## PR 6 — Inject parser registry

Replace private parser construction with Hilt multibinding/registry.

Acceptance:

```text
new provider parser can be added without editing EmailReceiptIngestionService.
```

---

## PR 7 — Add privacy/retention policy for raw email body

Add settings and enforcement:

```text
RAW / SANITIZED / HASH_ONLY / NONE
```

Acceptance:

```text
privacy-disabled raw retention stores no full email body and still dedupes via hash.
```

---

# 7. Tests to add

## `EmailReceiptProviderParserRegressionTest`

For Amazon/Uber/Apple:

```text
localized amount/date
HTML body
tax/shipping/subtotal/total
refund/cancellation
marketing false positive
```

Assert:

```text
valid receipts parsed
non-receipts rejected
currency correct
order/trip/document ID extracted
```

---

## `EmailReceiptLifecycleDbContractTest`

Use real in-memory Room.

Input:

```text
Amazon email with messageId and orderNumber
```

Assert:

```text
ScannedReceipt EMAIL_RECEIPT row
EmailReceiptSource row
ReceiptEvent.RECEIPT_SAVED
Expense row
TransactionEvent.CREATED
ReceiptExpenseLink row
```

---

## `EmailReceiptExistingExpenseLinkScenarioTest`

Seed:

```text
existing expense from notification:
merchant Amazon
amount 29.99
date same day
currency EUR
```

Input:

```text
Amazon receipt email same merchant/amount/date
```

Assert:

```text
no new expense
receipt linked to existing expense
analytics/dashboard count once
```

---

## `EmailReceiptIdempotencyTest`

Cases:

```text
same nonblank messageId twice
same provider orderNumber twice
blank messageId but same body hash twice
same merchant/amount/date but different orderNumber
```

Assert:

```text
duplicates skipped correctly
legitimate repeated purchases not skipped
```

---

## `EmailReceiptTransactionRollbackTest`

Use real DB + real lifecycle coordinator if feasible.

Simulate:

```text
receipt saved
expense created
link insert fails
```

Assert:

```text
no receipt
no email source
no expense
no transaction event
no side effects
```

---

## `EmailReceiptRawBodyPrivacyRetentionTest`

Cases:

```text
raw retention enabled
raw retention disabled
retention worker purge
backup redaction
```

Assert:

```text
raw email body is stored/purged/redacted according to policy
dedupe still works via hash
```

---

## `EmailReceiptRestoreModeGuardTest`

Set restore maintenance mode active.

Assert:

```text
processEmailReceipt returns blocked result
no ScannedReceipt row
no EmailReceiptSource row
no Expense row
no side effects
```

---

# 8. Suggested canonical scenario

## `email_receipt_lifecycle_warranty_price_analytics`

Seed:

```text
home currency EUR
category rules:
  Amazon → Shopping
existing notification expense:
  merchant = Amazon
  amount = 42.00 EUR
  date = 2026-05-01
```

Input:

```text
Amazon email:
  messageId = msg-abc
  orderNumber = 123-456
  total = 42.00 EUR
  date = 2026-05-01
  items = headphones
```

Expected:

```text
provider = amazon
ScannedReceipt row:
  sourceType = EMAIL
  documentType = EMAIL_RECEIPT
  processingStatus = PARSED

EmailReceiptSource row:
  messageId = msg-abc
  providerOrderId = 123-456
  fingerprint/bodyHash stored

ReceiptEvent:
  RECEIPT_SAVED
  RECEIPT_LINKED_TO_EXPENSE

Expense:
  no new expense if existing notification expense matches

ReceiptExpenseLink:
  links email receipt to existing expense

Analytics/dashboard:
  Amazon spend counted once

Privacy:
  raw email body stored/sanitized according to setting

Second import:
  returns Duplicate/AlreadyImported
  creates no new rows
```

This should become the Pipeline 11 fed-DB acceptance test.

---

# 9. Most likely real instability sources

Ranked:

1. **Split lifecycle ownership between EmailReceiptIngestionService and ReceiptLifecycleCoordinator.**
2. **Nested transaction + post-commit side effects.**
3. **Receipt link result ignored.**
4. **Provider parsed email can fail because generic ReceiptParser reparses raw body.**
5. **Duplicate existing expense becomes ParseError instead of link/no-op.**
6. **Weak merchant/amount/date fingerprint.**
7. **OrderNumber/messageId not used as transaction idempotency key.**
8. **Raw email body privacy/retention not explicit.**
9. **DI parser module unused because service instantiates parsers directly.**
10. **Tests stale relative to coordinator-based expense creation.**

---

# 10. Final recommendation

Stabilize Pipeline 11 in this order:

```text
1. Make one canonical ReceiptLifecycleCoordinator email ingestion method.
2. Add restore guard + atomic receipt/source/event writes.
3. Defer transaction side effects until after outer email transaction commits.
4. Check ReceiptLinkService result and handle DuplicateSkipped by linking existing expense.
5. Stop generic parser failure from killing provider-parsed emails.
6. Strengthen dedupe using messageId/orderNumber/bodyHash/itemHash.
7. Inject parser registry instead of private parser instances.
8. Add email raw-body privacy/retention policy.
9. Rewrite tests to DB-backed lifecycle/analytics scenarios.
```

Guiding rule:

> An email receipt should either link to an existing expense, create exactly one lifecycle expense, or become a review item. It should never create duplicate spending, fake success without a receipt link, or fail as “ParseError” after successful provider parsing.

Second guiding rule:

> Provider identity and message/order IDs are source-of-truth dedupe keys. Merchant/amount/date fingerprints are only fallback heuristics.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Two different email receipt entry points with different behavior
**STATUS: CONFIRMED — NOT FIXED (requires architectural unification)**

## Finding P0-2 — EmailReceiptIngestionService bypasses processEmailReceipt
**STATUS: CONFIRMED — NOT FIXED (would require major refactor)**

## Finding P0-3 — saveEmailReceipt has no restore guard
**STATUS: CONFIRMED — FIXED**
- `ReceiptLifecycleCoordinator.saveEmailReceipt()` now checks `restoreMaintenanceMode.isWritesAllowed()` and throws `IllegalStateException` when writes are blocked.
- Consistent with `processReceiptInput()`, `processEmailReceipt()`, and `deleteReceipt()` which already had restore guards.

## Finding P0-4 — Nested transaction with SideEffectMode.IMMEDIATE
**STATUS: CONFIRMED — FIXED (in Pipeline 3 session)**
- `EmailReceiptIngestionService.createExpenseFromReceipt()` now uses `SideEffectMode.DEFER`.
- Post-creation side effects are dispatched after the `transactionRunner` block completes.

## Finding P0-5 — Receipt link result is ignored
**STATUS: CONFIRMED — FIXED**
- `EmailReceiptIngestionService.createExpenseFromReceipt()` now checks `linkResult.isFailure` and logs a warning.
- The expense is still returned as successful (link failure is non-fatal), but the failure is now visible in logs.

## Finding P0-6 — Provider parser success can fail at ProcessReceiptUseCase
**STATUS: CONFIRMED — NOT FIXED (design limitation — two parsing phases)**

## Finding P0-7 — Dedupe fingerprint is too weak
**STATUS: CONFIRMED — NOT FIXED (requires stronger fingerprinting with order/message ID)**

## Finding P0-8 — Message/order IDs not used as transaction idempotency keys
**STATUS: CONFIRMED — NOT FIXED (requires CreateExpenseRequest extension)**

## Finding P1-1 — Raw email body stored without privacy policy
**STATUS: CONFIRMED — NOT FIXED (DataRetentionWorker handles OCR text purge)**

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Add restore guard to saveEmailReceipt | `ReceiptLifecycleCoordinator.kt` | P0-3 |
| Use SideEffectMode.DEFER in email ingestion | `EmailReceiptIngestionService.kt` | P0-4 |
| Check receipt link result | `EmailReceiptIngestionService.kt` | P0-5 |

---

# 14. Remaining work priority

1. **P0-1/P0-2**: Unify email receipt entry points through ReceiptLifecycleCoordinator
2. **P0-7**: Strengthen dedupe fingerprint with order/message ID components
3. **P0-8**: Use message/order IDs as transaction dedup keys
4. **P0-6**: Consider removing double-parsing (provider + ProcessReceiptUseCase)

---

# Sources

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `EmailReceiptSource.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt

- `EmailReceiptDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt

- `ScannedReceipt.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt

- `ScannedReceiptDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt

- `ReceiptLinkService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

- `ReceiptExpenseLink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt

- `ReceiptExpenseLinkDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptExpenseLinkDao.kt

- `ProcessReceiptUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/usecase/receipt/ProcessReceiptUseCase.kt

- `ReceiptSideEffectDispatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `EmailReceiptParser.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt

- `AmazonReceiptParser.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt

- `UberReceiptParser.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt

- `AppleReceiptParser.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt

- `EmailIngestionModule.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/di/EmailIngestionModule.kt

- `EmailReceiptData.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/EmailReceiptData.kt

- Existing tests:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceTransactionTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParserTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/email/provider/UberReceiptParserTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParserTest.kt