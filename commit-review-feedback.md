# Review of Last 5 Relevant Commits

## Commits checked

1. `60de48b` — Phase 4 Receipt Lifecycle Foundation  
2. `3bcd192` — Phase 3 Transaction Lifecycle Foundation  
3. `43e51e5` — Phase 2 Time/Period Semantics Foundation  
4. `ba1c177` — multi-currency refactoring closeout  
5. `31e0bd8` — currency PR6 UI formatter cleanup  

Static review only: I could inspect GitHub, but I could not run Gradle/tests.

---

# Overall verdict

The direction is good: the commits add the right architectural pieces — coordinators, event ledgers, source enums, receipt document types, link tables, schema migrations, and guardrail docs.

But I would **not mark Phase 3 or Phase 4 fully complete yet**. They look like strong foundation commits, but several details are still incomplete or risky.

---

# Phase 2 feedback — mostly good

## Good

- `TimePeriodUtils` now clearly documents half-open `[start, end)` semantics.
- `DateFormatterUtils` now accepts explicit timestamps instead of calling `Instant.now()` internally.
- `NaturalLanguageSearchEngine` now injects `TimeProvider`.
- `TransactionsViewModel` now uses:
  - `getMonthRange(now)`
  - `getQuarterRange(now)`
  - `getYearRange(now)`
  instead of rolling `30/90/365` windows.

## Remaining concerns

- Some natural-language ranges still look semantically rolling-ish. Example: “last month” appears to mean `today.minusMonths(1)` to `today`, not previous calendar month.
- This may be acceptable for search UX, but it should be explicitly documented as rolling-language behavior.

Phase 2 looks substantially better than before.

---

# Phase 3 feedback — foundation exists, but not complete

## Good

`TransactionLifecycleCoordinator` now centralizes:

- validation
- merchant key generation
- dedupe key generation
- range duplicate check
- `insertAtomic`
- transaction event insertion
- standard side effects

This is the right shape.

## Issues to fix before calling Phase 3 complete

## 1. Create flow is not DB-transactional

Current order:

1. validate
2. duplicate check
3. `insertAtomic`
4. insert `TransactionEvent`
5. dispatch side effects

If event insertion fails after the expense insert, you get an expense without lifecycle audit.

Required fix:

- wrap insert + source links + primary lifecycle event in a Room transaction.
- side effects remain post-commit.

## 2. Duplicate result loses the existing ID

`isDuplicateCurrencyAware()` returns only Boolean, so duplicate result uses:

```kotlin
existingExpenseId = -1L
```

That weakens UX, audit, debugging, and source reconciliation.

Required fix:

- add DAO method returning matching duplicate candidate ID/details.
- return `DuplicateSkipped(existingExpenseId = actualId, reason = ...)`.

## 3. Validation is still too shallow

Currently validates:

- amount finite and > 0
- amount <= 1,000,000
- merchant nonblank
- merchant not `"Unknown"` or `"Parsing Failed"`
- currency nonblank
- date positive

Missing:

- future date policy
- transfer requires direction/account
- ownership conflict validation
- shared-expense field consistency
- currency normalization/ISO validation
- idempotency key handling
- deduplication mode handling

## 4. `deduplicationMode` and `idempotencyKey` appear unused

`CreateExpenseRequest` has these fields, but coordinator currently only checks `skipDeduplication`.

Required fix:

- implement `STANDARD`
- `BULK_IMPORT`
- `STRICT_EXTERNAL_ID`
- `DEBUG_RESTORE`
- idempotency handling for bank/email/import paths.

## 5. Update lifecycle is too generic

`updateExpense(expense)` writes an event and calls `expenseDao.update(expense)`.

Missing:

- before snapshot
- after snapshot
- reason/source
- dedupe key recomputation
- duplicate check when merchant/date/amount/currency/type changes
- typed update requests

This is not yet safe enough for Phase 3 update lifecycle.

## 6. Delete lifecycle snapshot is weak

Delete event stores:

```kotlin
beforeSnapshot = expense.toString()
```

Use structured JSON instead.

## 7. Side effects are only partially centralized

`TransactionSideEffectDispatcher` handles:

- budget monitor
- anomaly alert
- merchant-category learning

But its own comment says source-specific side effects remain in callers. That means Phase 3 has not fully solved side-effect inconsistency yet.

This is acceptable as an intermediate PR, but not as “complete”.

---

# Phase 4 feedback — right architecture, but several blockers

## Good

The Phase 4 commit adds the correct concepts:

- `ReceiptLifecycleCoordinator`
- `ReceiptLinkService`
- `ReceiptDuplicateDetector`
- `ReceiptSideEffectDispatcher`
- `BankStatementLifecycleProcessor`
- `ReceiptEvent`
- `ReceiptExpenseLink`
- `ReceiptSourceType`
- `ReceiptDocumentType`
- `ReceiptProcessingStatus`
- migration `95 -> 96`

This is directionally correct.

## Major issues

## 1. Commit claims all 5 entry paths migrated, but email processing is TODO

`ReceiptLifecycleCoordinator.processEmailReceipt()` currently returns:

```kotlin
UnsupportedOperationException("processEmailReceipt will be implemented in PR 5")
```

So Phase 4 cannot honestly be marked as “all 5 entry paths migrated”.

There is `saveEmailReceipt()`, but that is not the full email lifecycle.

Required fix:

- implement full email lifecycle:
  - message ID dedupe
  - fingerprint dedupe
  - save `ScannedReceipt`
  - save `EmailReceiptSource`
  - optional transaction lifecycle create
  - receipt link
  - side effects

## 2. Receipt asset handling may create orphan files

`processReceiptInput()` does this:

1. `assetStore.persistReceiptAsset(uri)`
2. compute hash
3. call `receiptRepository.processReceipt(imageUri = uri)`

But `ReceiptRepository.processReceipt()` historically also persisted its own image copy.

Then coordinator updates:

```kotlin
imagePath = receipt.imagePath ?: assetPath
```

If repository already created `receipt.imagePath`, the coordinator-created `assetPath` is now unused/orphaned.

Required fix:

- either asset store owns persistence and OCR receives persisted asset, or
- repository owns persistence and coordinator does not pre-copy.
- do not persist twice.

## 3. ReceiptLinkService does not actually prevent relinking

The plan required non-bank receipts to avoid accidental relink.

But current service only has unique `(receiptId, expenseId)`. That prevents the same pair twice, but still allows:

- receipt 10 → expense 1
- receipt 10 → expense 2

Then legacy `ScannedReceipt.expenseId` is overwritten, while old link row remains.

Required fix:

- before linking non-bank receipts, check existing links.
- fail unless explicit replacement policy is passed.
- or enforce unique primary link per receipt.
- for bank statements, allow multiple links.

## 4. Link/unlink operations are not transactional

`linkReceiptToExpense()` does:

1. insert link
2. update legacy receipt field
3. insert event

If any step fails, data can desync.

Required fix:

- wrap link + legacy update + event in one DB transaction.

## 5. `unlinkReceiptFromExpense()` can clear legacy link incorrectly

It clears `ScannedReceipt.expenseId` after unlinking one expense. But if multiple links exist, it can clear the legacy primary even though another valid link remains.

Required fix:

- after unlink, recompute primary link.
- only clear legacy field if no remaining non-bank primary link exists.

## 6. Migration-created link/event tables appear to lack FK constraints

The manual SQL shown for `receipt_events` and `receipt_expense_links` creates tables but does not show foreign keys to receipts/expenses.

If the entity has FKs but the migration SQL does not, migration/schema validation may fail or DB integrity will be weaker.

Required check:

- compare generated `96.json` against migration SQL.
- add FK clauses if expected by Room schema.

## 7. Manual fallback still hardcodes `"EUR"`

In `processReceiptInput()` fallback receipt creation uses:

```kotlin
currency = "EUR"
```

That violates the currency phase direction. For a receipt placeholder with no parsed currency, prefer:

- nullable/unknown currency if schema allows, or
- home currency passed explicitly from settings, with assumption metadata.

## 8. Duplicate detection is only partially integrated

`processReceiptInput()` currently checks exact file hash before OCR. That is useful, but not full receipt dedupe.

Still needed:

- text fingerprint dedupe after OCR
- semantic fingerprint dedupe after parsing
- batch duplicate reporting
- email fingerprint integration in coordinator

---

# Currency commits feedback

The currency commits are huge and probably necessary, but `ba1c177` is extremely broad: 377 files changed.

Positive:

- strong scope coverage
- formatter cleanup
- explicit currency call sites
- schema updates
- docs/guardrails

Concern:

- very large commits are hard to audit.
- some later code still introduces hardcoded currency, e.g. receipt fallback `"EUR"`.
- guardrails should run after Phase 3/4 because later lifecycle commits can regress currency discipline.

---

# Priority fixes I recommend next

## Critical

1. Make `TransactionLifecycleCoordinator.createExpense()` transactional.
2. Make `ReceiptLinkService.link/unlink` transactional.
3. Fix non-bank receipt relink prevention.
4. Implement `processEmailReceipt()` or downgrade the Phase 4 claim.
5. Fix receipt asset double-persistence/orphan risk.
6. Remove hardcoded `"EUR"` from receipt fallback path.
7. Add FK checks/fixes for receipt link/event migration.

## High

8. Return real duplicate IDs from transaction dedupe.
9. Implement transfer/ownership/date/currency validation in transaction coordinator.
10. Implement `deduplicationMode` and `idempotencyKey`.
11. Add structured JSON snapshots for transaction events.
12. Move more source-specific side effects into dispatchers or explicitly document which remain source-owned.

## Medium

13. Convert event type strings to enums/constants.
14. Add lifecycle tests for failure halfway through coordinator operations.
15. Add guardrail scans for:
    - direct `expenseDao.insert*`
    - direct `scannedReceiptDao`
    - hardcoded `"EUR"`
    - unsupported `processEmailReceipt`
    - receipt relink overwrite

---

# Final assessment

- **Phase 2:** mostly acceptable, with small semantics cleanup still useful.
- **Phase 3:** good foundation, but not complete until transactionality, richer validation, update lifecycle, duplicate identity, and source-specific side effects are finished.
- **Phase 4:** good foundation, but not complete because email lifecycle is still TODO and receipt linking/asset ownership have correctness risks.
- **Phase 5:** no pushed commit visible yet on `master-refactor`.

Sources:
- Commits page: https://github.com/panospao7/Cost-agregator/commits/master-refactor/
- Phase 4 commit: https://github.com/panospao7/Cost-agregator/commit/60de48bfeccab31b767bad8472bde990c58b333c
- Phase 3 commit: https://github.com/panospao7/Cost-agregator/commit/3bcd19235357951b8691bb4dd007f2af7eeb7d1e
- Phase 2 commit: https://github.com/panospao7/Cost-agregator/commit/43e51e53a814a71df2f6748a0b6163b92625075c
- TransactionLifecycleCoordinator: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- ReceiptLifecycleCoordinator: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- ReceiptLinkService: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt