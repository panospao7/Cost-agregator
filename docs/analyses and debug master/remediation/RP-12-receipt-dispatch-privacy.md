# RP-12 — Receipt dispatch, privacy, and asset hygiene

> **Status:** CONDITIONAL — implement only after the post-commit and data-visibility contracts
> below are approved.
> **Mode:** strict (receipt lifecycle, privacy, and post-commit side effects).
> **Depends on:** none; RP-13 must follow if this plan changes shared receipt outcomes.

## Scope and invariants

The owning path is `ReceiptLifecycleCoordinator`. `ReceiptRepository` performs OCR/parsing and
returns `ProcessReceiptResult`; it must not become a second lifecycle owner. Receipt DB mutations
remain in the coordinator/link services. Side effects run only after the receipt transaction has
committed, are idempotent, and cannot turn a successful save into a failure. Raw OCR and structured
item data must not be re-persisted by downstream work or full-row updates.

## 12a — Restore one post-commit dispatch contract

### P3-001 — Define a concrete receipt outcome

`processReceiptInput` currently returns `Result<ScannedReceipt>`, while the repository's OCR result
is `ReceiptRepository.ProcessReceiptResult`. Do not overload either with an implicit batch. Add a
coordinator-owned result such as:

```kotlin
data class ReceiptProcessOutcome(
    val savedReceipt: ScannedReceipt,
    val inserted: Boolean,
    val postCommitBatch: PostCommitActionBatch?
)
```

The contract is mandatory:

* a newly inserted receipt returns `inserted = true`; its batch is planned exactly once from the
  committed receipt id;
* a pre-OCR, exact-hash, semantic, or insert-race duplicate returns the existing receipt with
  `inserted = false` and `postCommitBatch = null`;
* failure returns no outcome/batch;
* `ReceiptProcessingOptions.autoMatchExistingExpense` is carried explicitly into planning; false
  omits the matching action without suppressing unrelated actions;
* the batch may carry ephemeral raw OCR/item input in memory, but the runner/action may not persist
  it or include it in diagnostics.

Plan inside the transaction only if planning is pure and needs the transactional snapshot; never
execute there. The runner is invoked only after the transaction function has returned successfully.
The current production call graph has two `processReceiptInput` callers, which must be updated
explicitly: `ReceiptScanViewModel.kt:286` (interactive camera/gallery scan) and
`ReceiptRepository.kt:602` inside `processBatch` (the review/batch flow reaches this through
`ReviewViewModel.kt:967`). `processEmailReceipt` is a separate coordinator path at
`ReceiptLifecycleCoordinator.kt:738` and already owns its post-commit planner invocation; preserve
that path and do not run the camera batch on it. Tests are not production callers. Use the
coordinator's public entry point as the single runner owner and ensure these paths do not invoke a
second dispatcher. A best-effort batch failure is recorded as a side-effect
failure and does not change `inserted` or the receipt-save result.

If `ReceiptSideEffectDispatcher` remains zero-call alternate machinery after this unification,
remove it in a separate mechanically reviewable step or deprecate it with an architecture guard.
Do not activate planner and dispatcher simultaneously.

### P3-009 — Matching and categorization must be idempotent

Planner-driven matching must use the link service's unmatched-state CAS (`requireUnmatchedClaim =
true` or its source-compatible equivalent). An already linked, suggested, rejected, or concurrently
claimed receipt returns a controlled skipped outcome and is never overwritten.

The interactive ViewModel currently invokes receipt-item categorization separately. Select a single
production owner. Prefer the post-commit planner for both interactive and batch saves, gated by the
persisted item-categorization status and the planner idempotency key. Remove/disable the ViewModel
call only after tests prove the planner covers the same user-visible lifecycle. Do not run both.

Required tests:

* new receipt: one save, one planner batch, one runner invocation after commit;
* every duplicate path: existing receipt, `inserted = false`, zero batch/runner calls;
* batch: one batch per inserted receipt and none per duplicate;
* `autoMatchExistingExpense = false` omits only matching;
* rejected/already-claimed receipt cannot become auto-matched;
* interactive categorization runs exactly once and obeys its status gate;
* side-effect failure leaves the successful receipt result intact.

#### Batch 12a review follow-up (ISSUE-2) — validated

Strict review follow-up: duplicates are surfaced separately. `ReceiptRepository.BatchResult`
gains `duplicateCount` (and `BatchItemResult` gains `isDuplicate`), so duplicates are no longer
counted as saves; `ReviewViewModel`'s batch message reports them distinctly. Validated by the
12a targeted battery: all three new test classes (11 tests) and the cancellation-safety guard
(20 tests, passing with the two stale-protective RP-12 CE allowlist entries removed) are green.
The 6 remaining `ReceiptLifecycleCoordinatorTest` failures are the baseline-proven pre-existing
email-path failures (a subset of the 10 recorded at the lane base in
`build/guard-debug/rp12-baseline.log`); batch 12b owns them.

## 12b — Prevent privacy resurrection

> **Status (2026-09-15):** P3-004 landed in `b25dc3d8`, P3-007 core in
> `f305103e` — conditional-complete. Both insert paths (camera + email) route
> through the single `ReceiptStructuredDataPolicy` transformer; item-dependent
> post-commit actions skip with the controlled STRUCTURED_RECEIPT_DATA_UNAVAILABLE
> under every mode except STORE_RAW. Remaining P3-007 step (conditional): ephemeral
> item pass-through INTO categorization for fresh inserts under restricted modes —
> requires a CategorizeReceiptItemsUseCase API change; the controlled skip covers
> the privacy contract today. 12c not started.

### P3-004 — Use fresh reads and column-scoped receipt writes

`ReceiptLinkService` and match-suggestion code can write a stale full `ScannedReceipt` after a
retention purge, restoring purged OCR/structured columns. Move the eligibility read into the same
database transaction and replace full-row `@Update` calls in link, unlink, auto-match, and suggested-
match paths with column-scoped DAO operations for only match/link/status timestamps and ids. Build
events from the fresh in-transaction row. Preserve `rawOcrText`, `rawOcrTextPurgedAt`,
`parsedItems`, `parsedMerchant`, item-categorization status, and unrelated concurrent fields.

Add an interleaving database test in which purge/categorization commits before link/update; assert
the purged fields stay purged and the unrelated status is retained.

### P3-007 — Separate ephemeral and persisted structured data

Before changing `parsedItems`, define these distinct contracts:

| Consumer/input | Contract |
| --- | --- |
| OCR/parser and immediate planning | Ephemeral `ParsedReceipt`; available only for the current call and never logged/persisted by an action |
| Persisted review data | Produced by one `RawStorageMode` transformer before insert |
| Item categorization | Uses permitted ephemeral items for a new insert, otherwise the allowed persisted representation; records a controlled skip when unavailable |
| Price protection/warranty | Receives an explicit permitted ephemeral projection; must not silently depend on unredacted persisted JSON |
| Export/debug surfaces | Apply the same or stricter policy and never export fields disallowed by the mode |

The selected mode matrix is:

* `STORE_RAW`: persist current structured items/merchant;
* `STORE_REDACTED`: persist a typed `RedactedReceiptItem` JSON projection containing only
  `quantity`, `unitPrice`, `totalPrice`, `currency`, and an approved category id/name; omit item
  name, description, brand, SKU, free-text notes, and merchant. This exact schema is the contract,
  not an example.
* `STORE_METADATA_ONLY` and `DO_NOT_STORE`: persist no item JSON and no parsed merchant;
* settings/policy failure: use the most restrictive persisted representation.

Do not assume null fields are harmless. Update `ReceiptScanViewModel`,
`PriceProtectionTracker`, categorization input, review, and export behavior to either use the
permitted ephemeral projection during the post-commit call or return the controlled
`STRUCTURED_RECEIPT_DATA_UNAVAILABLE` state. After process restart, redacted/metadata-only receipts
must skip item-dependent price protection, warranty extraction, and categorization with that code;
they must not read disallowed persisted data. The post-commit batch must carry only the minimum
in-memory projection required;
it must not outlive the process or be retried from raw data after it is gone.

Tests cover all four modes across persistence, review display, categorization, price protection,
warranty, and export. Include process restart, where ephemeral data no longer exists.

## 12c — OCR failure asset cleanup

### P3-008 — Save first, but clean every uncommitted asset

The current OCR API saves the compressed image before ML recognition and returns a path in
`OcrResult`; the in-memory OCR path is therefore not proven to support “OCR first, save later” for
all image/PDF cases. Keep save-first as the primary design unless source changes establish full
in-memory parity.

Introduce explicit asset ownership: once a path is created it is an uncommitted temporary receipt
asset until a receipt row referencing it commits. The path is UUID-owned by that attempt and cannot
be reused by another attempt. Add `ScannedReceiptDao.countReferencesToImagePath(path)` and an
`AssetCleanupCoordinator` operation that, under the database write transaction, checks that count
is zero and records an attempt-local cleanup claim. A successful receipt insert transfers ownership
in that same transaction before a cleanup claim can be made; because paths are never reused, a
later insert cannot race the claim for the same path. The claimed unreferenced file is then deleted
and the claim released. On OCR failure, timeout, parser failure, cancellation, duplicate resolution,
or insert rollback, invoke this operation. After a successful receipt commit, cleanup is disabled.
Cleanup failures are best-effort and use controlled diagnostics; they never mask the original error.
Never delete a path already referenced by any receipt.

Avoid the current double-copy fallback: pass the owned saved path through the typed OCR failure (or
delete it before `persistImageCopy`); do not discover assets by filename convention. A historical
orphan sweep is optional and requires a bounded, centrally scheduled implementation plus a DB
reference check; it is not part of the minimal live-path fix.

Tests cover recognition failure, parser failure, timeout, caller cancellation, duplicate, insert
rollback, successful ownership transfer, cleanup failure, and a referenced asset that must survive.

## Required validation

Targeted tests (when approved; not run while editing this plan):

```text
*ReceiptLifecycleCoordinator*
*ReceiptSideEffect*
*ReceiptLinkService*
*ReceiptRepository*
*ReceiptOcrService*
*RawStorage*
*ReceiptMatching*
```

Strict review must inspect the actual call graph, transaction boundary, CAS gate, privacy mode
matrix, and every asset exit path. Sequence `12a → 12b → 12c`, then reconcile RP-13 with the final
outcome and ephemeral-data contracts.
