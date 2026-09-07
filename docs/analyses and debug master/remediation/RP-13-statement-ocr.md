# RP-13 — Statement import & OCR robustness (Pipeline 3/10 shared processor)

> **Scope class:** shared engine (`BankStatementLifecycleProcessor` serves both PDF/statement import and bank-sync statement flows) + OCR service. **Mode:** strict (transaction creation paths).
> **Files:** `BankStatementLifecycleProcessor` (domain/receipt/lifecycle/), `ValidateBankStatementTransactionsUseCase` (domain/ai/usecase/), `ReceiptOcrService` (domain/receipt/), `ReceiptRepository` (data/repository/), `ScannedReceiptDao` + `BankStatementImportRunDao`/`BankStatementImportItemDao` (data/database/dao/).
> **PR shape:** 2 PRs — (13a) AI merge & retry semantics = P3-002 + P3-010 + P3-003; (13b) run lifecycle = P3-005 + P3-006.
> **Depends on:** RP-12 (same processor file — sequence after; 13's changes are in different methods but rebase cleanly).

---

## 13a — AI merge & OCR retry semantics

### P3-002 — AI-merge pairs by index after skipped entries (HIGH)

**Problem.** `ValidateBankStatementTransactionsUseCase.parseAiResponse` (`:228-235`) `continue`s past invalid AI entries (blank merchant, `amount <= 0`, non-finite, unparseable date) returning a **shorter** list (`:264`); `BankStatementLifecycleProcessor` merges `parsedTransactions.mapIndexed { i, cleanTx -> val originalTx = parsedTransactions[i] }` (`:245-246`) with **no size recheck** → after a skip, every subsequent row pairs with the wrong parser row: `type = originalTx.type` (`:254`) and date fallback (`:252`) shift by one — a DEPOSIT stamped PURCHASE defeats the type-aware dedupe (`:537/:545`) and tail rows are silently dropped. If AI returns *more* entries, `:246` throws `IndexOutOfBounds` → whole run `WORKER_UNHANDLED_EXCEPTION`.

**Fix design.**
1. Carry identity through the pipeline: in `parseAiResponse`, attach the **candidate index** to each surviving `CleanTransaction` (add `candidateIndex: Int` — captured before the skip checks). Rejecting an entry no longer shifts anyone.
2. Merge by `candidateIndex`: `val originalTx = parsedTransactions[cleanTx.candidateIndex]` (with an explicit bounds re-check that fails the single item, not the run).
3. Size guards: `require(cleanTx.candidateIndex < parsedTransactions.size)` per item → mismatch = structured per-item failure (`AI_MERGE_INDEX_INVALID`) counted as skipped, run continues.
4. Ledger: skipped-because-invalid AI entries already surface as `STATUS_SKIPPED` items downstream of validation — verify the skip reason distinguishes "AI rejected row N" (informational for the user's import review list).

**What it solves.** Row type/date/amount integrity for statements where the AI model skips any line; no more wrong-type dedupe bypasses or dropped tails.

**Guardrails.** `CleanTransaction` is a domain type used by the AI-validation tests — additive field, update fixtures. No privacy change (indices are not PII). Crossover: bank-sync's low-confidence path consumes the same validated list (`BankApiIntegration` side untouched — it uses per-transaction confidence, not this merge).

**Tests.** `ValidateBankStatementTransactionsUseCaseTest` + processor test: 5 candidates, AI skips #2 → rows 1,3,4,5 pair correctly, #2 surfaces as skipped; AI returns 7 entries for 5 candidates → per-item failure not crash; type-aware dedupe verified against correctly-typed DEPOSIT.

### P3-010 — AI blank currency hardcodes "EUR" (LOW-MED)

**Problem.** `:223` `.ifBlank { "EUR" }` — blank AI currency becomes EUR wholesale when `confidence > 0.5` (`:247-251` adopts it), flowing into `PendingReview.suggestedCurrency` (`:625`) and currency-scoped dedupe (`:530-546, :582-591`) → USD statements get EUR reviews; duplicates never match.

**Fix design.** Replace the hardcode: `currency = obj.optString("currency").trim().ifBlank { homeCurrency }` — `homeCurrency` is already a parameter of `validateTransactions` (verify signature; it is threaded for other purposes). If blank and home-currency also unavailable (shouldn't happen — the processor resolves it earlier), skip the item as `STATUS_SKIPPED` with reason `CURRENCY_UNKNOWN` rather than inventing one.

**What it solves.** Reviews/dedupe carry the real (or home) currency; no phantom-EUR artifacts. **Tests:** blank AI currency + USD home → suggested currency USD; both blank → skip with reason.

### P3-003 — OCR timeout aborts the whole batch, never retries (HIGH)

**Problem.** `ReceiptOcrService.recognizeText` wraps MLKit in `withTimeout(15_000)` (`:587-603`); `TimeoutCancellationException` **is a** `CancellationException`, so `runWithRetry`'s `catch (CancellationException) { throw }` (`:781`) fires before the generic retry catch → timeouts never retry despite `maxAttempts = 3`; the exception then propagates through the coordinator's `rethrowIfCancellation` (by design) into `processBatch`'s `coroutineScope { async }.awaitAll()` (`ReceiptRepository.kt:595-631`) — **one slow page cancels every sibling OCR** and fails the batch.

**Fix design.**
1. In `runWithRetry`, distinguish timeout from caller cancellation: catch `TimeoutCancellationException` **first** and treat it as retryable **only when** the coroutine is still active (`currentCoroutineContext().isActive` — if the caller was cancelled, rethrow). Concretely:
   ```kotlin
   catch (e: TimeoutCancellationException) {
       if (!currentCoroutineContext().isActive) throw e
       lastError = e; continue // retry with a fresh withTimeout
   }
   catch (e: CancellationException) { throw e }
   ```
2. After exhausting retries on timeout, throw a **typed non-CE** failure (`OcrTimeoutException` — new, extends the service's existing failure type) so upstream `catch(CancellationException)` handlers don't misclassify it and the batch scope treats it as a per-item failure.
3. `processBatch`: switch `awaitAll` to per-item result collection (each `async` returns `Result`-style; item failures recorded, siblings continue) — this is the structural fix for "one bad page fails the batch" and matches the per-item ledger philosophy used everywhere else. Cancelled scope (user cancel) still propagates normally to all children.

**What it solves.** Slow pages retry then fail *individually*; batches complete with per-item outcomes.

**Guardrails.**
- Worker/service rule: genuine cancellation must still propagate — the `isActive` check is the boundary; test both directions.
- `CancellationSafe.rethrowIfCancellation` callers (coordinator `:560`) rely on CE semantics — the typed exception keeps them honest (non-CE passes through as a normal failure).
- Per-item isolation must not swallow per-item exceptions into silence — each failure records the existing `OCR_FAILED`-family status + diagnostic.

**Tests.** `ReceiptOcrService` fake: page times out twice, succeeds third → retried; times out N times → typed failure, not CE. `ReceiptRepository.processBatch`: one failing item among three → two succeed with rows, one recorded failed, scope completes; scope cancel → CE propagates, no zombie writes.

---

## 13b — Statement run lifecycle

### P3-005 — Cancelled statement import is unretryable (MED)

**Problem.** The statement receipt (with `imageHash`) commits at step 4 (`:309-347`) **before** per-item processing; cancellation mid-items finalizes the run `STATUS_CANCELLED` (`:838-867`) but nothing clears the hash; re-importing the same file hard-fails at the pre-OCR `EXACT_HASH` check (`:162-176`, `getByImageHash` unfiltered by status) before the graceful `ReceiptRecordWriter` duplicate path can run → items 6-20 permanently unimportable.

**Fix design.**
1. At the hash-check branch (`:169-175`), before failing, look up the associated `BankStatementImportRun` (by source receipt id — the linkage exists in the run table) and allow re-import when the prior run is terminal-but-incomplete (`STATUS_CANCELLED` or `STATUS_FAILED`): proceed into the duplicate path (the writer's resolution handles the existing receipt) and **create a new run** whose item processing re-checks each item's existing ledger row (`BankStatementImportItem` by natural key) — already-committed items come back as duplicates/skips, unprocessed ones proceed. This reuses the per-item idempotency that already exists.
2. A prior **COMPLETED** run still hard-fails (true duplicate) — unchanged semantics.
3. Diagnostic: the re-import path emits `IMPORT_RESUMED_AFTER_CANCEL` (controlled constant) so the behavior is auditable.

**What it solves.** Users can retry a cancelled/failed statement import; no permanently stuck files.

**Guardrails.** Item-level dedupe must rely on the ledger's natural key (statement-line identity), not fuzzy matching — verify the item DAO has a unique/stable key per line (amount+date+merchant hash or line index); if only run-scoped ids exist, add per-item natural-key lookup by query (no schema change if a composite query suffices — **stop and flag** if it requires an index/migration). Crossover: bank-sync statement flow shares this processor — its runs also get resume semantics (desirable; note in PR).

**Tests.** Cancel at item 5/20 → re-import → items 1-5 skipped-as-existing, 6-20 processed, run COMPLETED; completed-run re-import → duplicate failure (unchanged).

### P3-006 — Skips counted as run failures (MED)

**Problem.** Final status (`:725-729`): `(finalFailedItemCount + skippedItemCount) > 0 → STATUS_FAILED` → `PROCESSING_FAILED` event, receipt stuck PARSED, `Result.failure` (`:820`) — even when 20 valid PendingReviews committed. Duplicate-skips correctly yield `COMPLETED_WITH_SKIPS`; validation-skips (bad amount/currency/date rows in the file) are lumped into FAILED.

**Fix design.**
1. Split the predicate: `finalFailedItemCount > 0 → STATUS_FAILED`; else `(any skips incl. validation + duplicates) → STATUS_FAILED? No —` `COMPLETED_WITH_SKIPS`; else `COMPLETED`. I.e. validation-skips join duplicate-skips in the with-skips bucket.
2. Receipt status on the with-skips path: set `REVIEW_CREATED` when `createdCount > 0` (the current FAILED branch skips that update, `:762-768` — now only genuinely failed runs leave PARSED).
3. `Result`: `COMPLETED_WITH_SKIPS` returns success with a skip summary (counts already tracked); the error message on true failure now counts only failures (message currently conflates — fix the copy).
4. UI surface: the import result screen presumably keys off the Result — verify it can show "completed with N skipped rows" (string resource; likely exists for duplicates).

**What it solves.** Partially-invalid statements import their valid rows and report honestly; no more false FAILED events or stuck-PARSED receipts with committed reviews.

**Guardrails.** A run where **every** item skipped-validations (zero created, zero failed) → `COMPLETED_WITH_SKIPS` with a clear skip breakdown — do not report success-as-COMPLETED (user must see the file was entirely invalid). **Tests:** mixed fixture (valid + validation-skip + duplicate) → COMPLETED_WITH_SKIPS, receipt REVIEW_CREATED, reviews committed; all-invalid → with-skips + zero created; any true failure → FAILED path intact.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*BankStatement*" --tests "*ValidateBankStatement*" --tests "*ReceiptOcrService*" --tests "*ReceiptRepository*" --tests "*ReceiptMatching*"
```

## Sequencing & risk
- After RP-12 (processor file shared; different methods). 13a is the user-facing correctness core (index merge + batch isolation); 13b changes run-state semantics — the import screen's result mapping must be reviewed together. No schema changes planned; the P3-005 natural-key check is the one place to stop if a query isn't sufficient.
