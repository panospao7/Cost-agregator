# RP-12 — Receipt save dispatch & privacy (Pipeline 3)

> **Scope class:** pipeline + crossover (warranty auto-extraction, price protection, transaction matching — the side-effect families the planner owns). **Mode:** strict (receipt lifecycle).
> **Files:** `ReceiptLifecycleCoordinator` (domain/receipt/lifecycle/), `ReceiptSideEffectPlanner` + `ReceiptSideEffectDispatcher` (domain/receipt/lifecycle/), `ReceiptRepository` (data/repository/), `ReceiptLinkService` (domain/receipt/lifecycle/ ⟂), `ScannedReceiptDao` (data/database/dao/), `ReceiptOcrService` (domain/receipt/), `ReceiptScanViewModel` + review/batch ViewModels (ui/ ⟂), `AutoCreateWarrantyFromReceiptUseCase` ⟂, `PriceProtectionTracker` ⟂.
> **PR shape:** 2 PRs — (12a) save-dispatch restoration = P3-001 + P3-009; (12b) privacy & data hygiene = P3-004 + P3-007 + P3-008.
> **Depends on:** nothing; RP-13 sequences after (shared processor file is *not* in this package — P3 statement items live there).

---

## 12a — Restore post-save dispatch on the camera/batch path

### P3-001 — Camera/batch receipts never dispatch post-save side effects (HIGH, regression)

**Problem.** `processReceiptInput` (`ReceiptLifecycleCoordinator.kt:244-565`) ends at insert + PendingReview — no planner, no dispatcher. `planAfterReceiptSaved` is called **only** on the email path (`:1035`); `ReceiptSideEffectDispatcher.dispatchAfterSave` (`:42`) has **zero callers**. Consequences per family: no warranty auto-extraction (`AutoCreateWarrantyFromReceiptUseCase` consumed only by the planner, `:208`), no price-protection check at save time, no item-categorization dispatch for **batch** import (interactive scan covers it separately via `ReceiptScanViewModel:1305`; `processBatch` gets neither), matching deferred to the periodic worker. Strong regression evidence: `options.autoMatchExistingExpense` (`:221`) is declared and never read; `ReceiptRepository:578` KDoc still claims batch dispatches side effects; `ReceiptLifecycleCoordinatorTest:234` stubs the post-commit runner and asserts rethrow — the test suite pins the **removed** contract (REVAL-2).

**Fix design.**
1. At the end of `processReceiptInput`'s happy path (post-insert, same transaction scope as the email path's planning step), call `receiptSideEffectPlanner.planAfterReceiptSaved(receipt = saved, rawOcrText = ephemeral raw text — same pattern as `:1035-1044` with `linkedExpenseIds = emptyList()` since no expense exists yet) and return the batch alongside the result so the repository/caller can run it post-commit exactly as the email path does (`:1091-1095`, `runBestEffortAfterCommit`).
   - Threading: `processReceiptInput` currently returns `Result<ScannedReceipt>`-ish — extend the return type to carry the `PostCommitBatch` (or expose a wrapper result `ReceiptProcessOutcome(receipt, sideEffectBatch)`), update the 3 call sites (repository `processReceiptInput` wrapper, batch processor caller, ViewModel paths) to run the batch after the transaction commits. **Post-commit only** — never inside the transaction (the email path's discipline).
2. Wire `options.autoMatchExistingExpense`: read it in the planning call (pass through to the planner, which already owns the match action) — delete the dead-parameter smell.
3. `ReceiptSideEffectDispatcher.dispatchAfterSave`: decide its fate — if the planner+runner path covers everything, **delete the dispatcher** (zero callers, duplicate concept; RP-20 rules). Do not keep both mechanisms.
4. Fix the stale `ReceiptRepository:578` KDoc (claiming dispatch that didn't happen — now it will).
5. Privacy guardrail: the planner receives **ephemeral** raw OCR text (post-sanitization in-memory copy), never re-persisted — verify the email path's `ephemeralRawOcrText` convention is replicated.

**What it solves.** Warranties auto-extract, price protection runs, items categorize, and (option-driven) matching fires for every camera/gallery/batch receipt — restoring the documented 7-step lifecycle.

**Guardrails.**
- Lifecycle rule: side effects dispatch **post-commit** only; failures are best-effort (runner semantics) and must never fail the receipt save.
- Worker overlap: the periodic `ReceiptMatchingWorker` also matches UNMATCHED receipts — planner-driven match must pass `requireUnmatchedClaim = true` (fixes P3-009 below in the same PR) so the two can't stomp.
- Idempotency: re-processing the same receipt (duplicate resolution path) must not double-dispatch — planner idempotency keys already include receipt identity; verify a dedup-resolved receipt skips planning.
- Interactive-scan ViewModel also calls `categorizeReceiptItemsUseCase` directly (`:1305`) — after this fix that becomes double-categorization **unless** the planner skips when status says already-categorized. Check `itemCategorizationStatus` gating in the planner and make the ViewModel call conditional on it (or remove the ViewModel call if planner covers it — verify which before deleting).

**Tests.**
- Update `ReceiptLifecycleCoordinatorTest:234` semantics: camera path now dispatches once post-commit (the existing cancellation test becomes *more* meaningful — keep its assert, it now exercises a real dispatch).
- New: batch import → planner called per receipt; `autoMatchExistingExpense=false` → no match action; duplicate-resolved receipt → no planning; categorization not double-run for interactive scans (status gate).

### P3-009 — Planner auto-match bypasses the CAS claim (LOW-MED, latent today)

**Problem.** `ReceiptSideEffectPlanner.kt:339-347` calls `linkReceiptToExpense(..., requireUnmatchedClaim default false)`; the non-claim branch overwrites match state wholesale (`ReceiptLinkService.kt:252-261`) — could stomp REJECTED→AUTO_MATCHED. Currently unreachable (email-only path never carries a match), becomes **live the moment 12a lands** — hence same PR.

**Fix design.** Pass `requireUnmatchedClaim = true` (worker parity, `ReceiptMatchingWorker.kt:96`). The link service then CAS-claims; on `ReceiptAlreadyClaimedException` the action logs a skip with the controlled reason code and succeeds (best-effort semantics — another actor won the match).

**Tests.** Planner match vs pre-REJECTED receipt → claim fails → no stomp, skip logged.

---

## 12b — Privacy & data hygiene

### P3-004 — Stale full-row `@Update` resurrects purged OCR text (MED, privacy)

**Problem.** `ReceiptLinkService.linkReceiptToExpense` loads the full entity **before** the transaction (`:168`) and writes the whole row inside (`:252`, `@Update`); unlink same (`:382/:411`); the planner's Suggested branch repeats it (`:283/:374`). A retention purge (`updateRawOcrTextPurged`, `ScannedReceiptDao.kt:155-164`, column-scoped) committing in the gap is overwritten: `rawOcrText` restored, `rawOcrTextPurgedAt` nulled (→ re-picked next purge cycle, but until then the purged text is at rest again), concurrent column updates (`itemCategorizationStatus`, etc.) reverted.

**Fix design.**
1. Replace all four full-row writes with **column-scoped** `@Query` updates in the DAO (same shape as `claimForAutoMatch`):
   - `linkReceiptFields(receiptId, expenseId, matchStatus, matchedAt, linkType, writeSourceLink...)` — exactly the columns the link path changes;
   - `unlinkReceiptFields(receiptId, ...)` for the unlink set;
   - planner branch likewise (or reuse the link query with its fields).
2. Move the pre-read **inside** the transaction (the existence/eligibility check already runs there — merge them) so even eligibility decisions use fresh state.
3. Keep event writes unchanged (they already snapshot only what they need).

**What it solves.** Purged OCR text stays purged; concurrent column updates can't be reverted by a racing link/unlink.

**Guardrails.**
- The events/audit path records before/after from the fresh in-txn read — keep using that read for the *event payload* (no behavior change), only the **write** becomes column-scoped.
- Check for other full-row `scannedReceiptDao.update(` callers (grep) — convert any on the link/unlink/match paths; leave unrelated ones with a TODO note (scope discipline).

**Tests.** Interleaving test: purge commits between load and write (fake DAO sequencing) → rawOcrText stays `''`, purgedAt preserved; concurrent categorization-status update survives a link.

### P3-007 — `parsedItems` persisted unredacted on the camera path in all modes (MED, privacy)

**Problem.** `ReceiptRepository` builds the camera draft with `parsedItems = lineItemsToJson(parsed.lineItems)` (`:284-285`) — product names/quantities — with **no storage-mode gate**, while the email path redacts per mode (`ReceiptLifecycleCoordinator.kt:832-836`, `[REDACTED_ITEMS]`) and policy treats parsedItems as raw content (`ReceiptPersistencePayload.kt:27-29` nulls it under DO_NOT_STORE). Default mode STORE_RAW makes this live; the JSON survives debug export (`ReceiptDebugExporter:151`) and DB backups (`ExportAnonymizer` nulls only `rawOcrText`).

**Fix design.**
1. Gate at draft construction: resolve the effective `rawOcrStorageMode` (the repository already reads settings for `sanitizeOcrBeforeInsert` at `:125-126` — same snapshot) and transform: `DO_NOT_STORE` → null; `STORE_METADATA_ONLY` → null; `STORE_REDACTED` → items JSON with descriptions replaced by `[REDACTED_ITEM]` (keep quantities/prices? **No** — prices are amounts, keep them; descriptions are the free-text PII. Mirror the email path's decision — check what `:832-836` keeps and replicate exactly for consistency); `STORE_RAW` → as-is.
2. The in-memory `ParsedReceipt` still carries items for the review UI — only the *persisted* JSON is transformed (same principle as rawOcrText).
3. `ExportAnonymizer.sanitizeScannedReceipts`: extend to also null `parsedItems`/`parsedMerchant` (aligns the export sink with the retention perimeter — coordinates with RP-14 which covers the at-rest purge; the anonymizer change is small and belongs here since it's the same field).

**What it solves.** The storage-mode contract covers structured item data on every ingestion path; redacted exports stop carrying product lists.

**Guardrails.** Privacy fail-closed: settings read failure → most restrictive (null). Review-UI flows that read `parsedItems` for display will show nothing under restrictive modes — acceptable and consistent with rawOcrText's existing behavior (verify the review screen already tolerates null items — it must, since DO_NOT_STORE emails produce that today). **Tests:** mode matrix (4 modes) × persisted parsedItems; export anonymizer fixture.

### P3-008 — Orphan JPEG per failed OCR (LOW-MED)

**Problem.** `ReceiptOcrService.processImage` saves the compressed copy (`:232`) **before** `recognizeText` (`:236`); on recognition failure `savedPath` is a lost local (finally recycles only the bitmap, `:277-280`); the repository fallback (`ReceiptRepository:216`) then persists a **second** copy via `persistImageCopy` (`:289-298`). No janitor exists.

**Fix design.**
1. Reorder: run OCR on the in-memory bitmap first; persist only on success (the success path needs the file anyway). If the API shape requires a file for OCR (MLKit InputFilePath?), instead: keep save-first but track `savedPath` in a local var and `runCatching { File(savedPath).delete() }` in the failure branches before rethrowing.
2. Repository fallback: pass the already-saved path through the error (or re-check for the file's existence by naming convention) instead of blind `persistImageCopy` — the "second copy" only makes sense when the first truly doesn't exist.
3. Optional (cheap, do it): a one-time startup sweep deleting unreferenced files in `filesDir/receipts` (join against `imagePath` values) — bounded, defensive; coordinate with RP-14's retention mindset but keep it here since it's asset hygiene, not row data.

**What it solves.** No orphan files per failed OCR; no duplicate copies on the fallback path.

**Guardrails.** Never delete a file referenced by any row (sweep checks the DB first). File ops wrapped in runCatching — an IO failure during cleanup must not mask the original OCR failure. **Tests:** OCR-failure fixture → file deleted / never persisted; fallback path reuses existing file; sweep leaves referenced files.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*ReceiptLifecycleCoordinator*" --tests "*ReceiptSideEffect*" --tests "*ReceiptLinkService*" --tests "*ReceiptRepository*" --tests "*ReceiptOcrService*" --tests "*RawStorage*"
```

## Sequencing & risk
- 12a restores a removed behavior — highest-value PR in this package; must land with P3-009's claim fix and the categorization double-run check. 12b is privacy-consistency. RP-13 (statement processor) lands after — different file, no conflicts.
