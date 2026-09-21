# RP-13 — Statement import and OCR robustness

> **Status:** PARTIAL — Gate A (identity, register D10) and Gate B (currency, register D11)
> approved 2026-09-20 and implemented (P3-002, P3-010, 13a/P3-003). Gate B' (resume
> identity/state machine, P3-005) and final-state semantics (P3-006) remain PENDING —
> no resume/schema work has been done.
> **Mode:** strict (AI identity, statement lifecycle, OCR cancellation, and Room state).
> **Depends on:** RP-12 for shared receipt-processing outcomes (satisfied on base 2c2742d5).
> **Validation: NOT RUN** — static edits only; targeted tests authored for the serialized
> validation queue (`*BankStatement*`, `*ValidateBankStatement*`, `*ReceiptOcrService*`,
> `*ReceiptRepository*`, `*Migration*`).

## Implementation status (2026-09-20, lane RP-13)

Applied on `rp-13-wip` (base 2c2742d5). No Room schema change was required; DB version
unchanged by this lane.

- **P3-002 / Gate A (D10) — DONE.** Explicit stable `candidateId` contract end to end:
  `DebugTransaction`/`CleanTransaction` carry an immutable parser-assigned `candidateId`;
  the prompt lists candidates with their ids and requires a JSON array with EXACTLY ONE
  object per candidate in candidate order, each echoing `candidateId` as a JSON integer
  plus an explicit `status` of `accepted`/`rejected`. `ValidateBankStatementTransactionsUseCase`
  returns a typed `StatementValidationOutcome` (`Validated` / `ParserOnly` / `IdentityMismatch`
  with controlled detail codes `DUPLICATE_CANDIDATE_ID`, `MISSING_CANDIDATE_ID`,
  `MALFORMED_CANDIDATE_ID`, `OUT_OF_RANGE_CANDIDATE_ID`, `OMITTED_CANDIDATE_ID`,
  `EXTRA_ENTRY`, `REORDERED_CANDIDATE_IDS`, `MALFORMED_ENTRY`). Any identity/cardinality
  failure yields `AI_IDENTITY_MISMATCH` and a parser-echo fallback — merging is by
  `candidateId` only, never position. AI-`rejected` rows (identity proven) become
  `SKIPPED` ledger items with reason `AI_REJECTED` in
  `BankStatementLifecycleProcessor`.
- **P3-010 / Gate B (D11) — DONE.** No hard-coded EUR default, no home-currency fallback.
  `ParsedTransaction` gained an additive `currencyAssumption` field
  (`PARSED_FROM_SOURCE` vs `ASSUMED_HOME_CURRENCY`/`UNKNOWN`); the statement parser marks
  explicit symbols/tokens (`€`, `£`, `$`, ISO codes) as parsed-from-source. The validator
  resolves currency as: validated AI currency → explicitly parsed source currency →
  typed `CURRENCY_UNKNOWN` (blank sentinel). The processor turns blank currency into a
  `SKIPPED` ledger item with reason `CURRENCY_UNKNOWN` (renamed from `MISSING_CURRENCY`);
  no guessed currency reaches a review or dedupe key.
- **13a / P3-003 — DONE.** `ReceiptOcrService.runWithRetry` catches
  `TimeoutCancellationException` FIRST, retries only while the parent coroutine is active,
  rethrows genuine cancellation, and converts exhausted timeouts to the typed
  non-cancellation `OcrTimeoutException`. Per-item isolation via `runOcrItemIsolated`
  (`IsolatedOcrResult.Ok/Failed` with controlled codes `OCR_PAGE_TIMEOUT`/`OCR_PAGE_FAILED`):
  one failed PDF page no longer cancels sibling pages; all-pages-failed surfaces the typed
  `OcrRecognitionFailedException` (save-first path preserved). `OcrResult` gained an
  additive `failedPages` field. `ReceiptRepository.processBatch` now collects results
  per item under `supervisorScope` (sibling items continue on item failure; caller
  cancellation still propagates). The AI calls in the validator also rethrow caller
  cancellation instead of `runCatching`-swallowing it.
- **P3-005 / P3-006 — PENDING.** Resume identity/state machine and final-status
  separation (COMPLETED_WITH_SKIPS vs FAILED) await their gate; current finalization
  semantics (skips count toward run failure) are unchanged.

Tests authored (targeted, queued for execution — NOT RUN):

- `ValidateBankStatementTransactionsUseCaseTest` — identity faults (omitted middle
  candidate, reordered, duplicate id, out-of-range id, extra entry, string/fractional
  malformed id, omitted id field), rejected-status handling, mismatch→parser-echo fallback,
  cloud recovery after on-device mismatch, and currency cases (blank AI currency with USD
  source, blank source with home available — must NOT fall back, both unknown, invalid AI
  currency, parser-only echo policy).
- `ReceiptOcrRetryIsolationTest` — timeout twice then success; exhausted timeout → typed
  non-cancellation failure; non-timeout failure; caller cancellation propagation; timeout
  on cancelled parent; one failed item among three; controlled isolation reason codes.
- `BankStatementParserTest` — currency-assumption provenance (explicit €/token vs assumed
  home).

Known interactions / notes:

- With AI unavailable and no explicitly parsed currency, rows now land as
  `CURRENCY_UNKNOWN` skips; under the unchanged finalization rule such runs report
  FAILED. P3-006 (COMPLETED_WITH_SKIPS) is expected to revise this after its gate.
- `config/guards/db_ownership_policy.yml` `reason:` text for the item-ledger insert still
  mentions `MISSING_CURRENCY`; the policy match (method/dao/operation) is unchanged. The
  descriptive string is left untouched (guarded config — no-weakening rule).


## Scope and source facts

The shared owner is `BankStatementLifecycleProcessor`, used by statement OCR/import and bank-sync
statement flows. Supporting code is `ValidateBankStatementTransactionsUseCase`,
`ReceiptOcrService`, `ReceiptRepository`, and the `BankStatementImportRun`/`BankStatementImportItem`
entities and DAOs.

The current `CleanTransaction` has no source-row identity. AI output is shortened when invalid
entries are skipped and the processor merges it positionally. The current item ledger is unique
only on `(runId, itemIndex)`; `existsByRunAndFingerprint` is scoped to one run. `BankStatementImportRun`
has no resume cursor or cross-run source identity. These are design constraints, not details to be
papered over in implementation.

## Gate A — AI response identity (must be approved first)

### P3-002 — Never treat response position as source identity

The old proposal to add `candidateIndex` from the surviving JSON position is invalid: an omitted
middle element and a reordered response make that index point at the wrong parser row. Select one
contract before changing `CleanTransaction` or the prompt:

1. **Explicit stable candidate id (preferred):** prompt for and require an immutable `candidateId`
   generated from the parser's source-line identity. Validate integer/range, uniqueness, and
   one-to-one ownership before applying any correction.
2. **One result per candidate:** require an array with exactly one object per candidate, in the
   documented order, including an explicit `accepted`/`rejected` status. Cardinality and order are
   validated; rejected entries become ledger skips.
3. **Untrusted AI corrections:** if the provider cannot satisfy either contract, discard the AI
   corrections and use parser-only rows. Do not merge by position or fuzzy merchant/amount data.

For explicit ids, reject duplicate, missing, malformed, out-of-range, omitted, reordered, and
extra ids. On any identity/cardinality failure, return a structured `AI_IDENTITY_MISMATCH` outcome
and fall back to parser-only for the affected statement (or the affected rows only if the contract
proves the remaining ids). Never throw an index exception or silently pair a correction with a
different row. A rejected AI row is recorded as a skipped ledger item only when its source identity
is proven; otherwise the parser row remains the authoritative candidate.

The chosen contract must be represented in the prompt, response schema/model, validator, processor,
and tests before coding. Tests cover omitted middle candidate, reordered output, duplicate id,
out-of-range id, extra entry, malformed/missing id, and one-output-per-candidate rejection.

### P3-010 — Unknown currency is typed, never fabricated

The current parser uses a hard-coded `EUR` when AI currency is blank. Replace that with a validated
source policy: use the statement/source currency only when it is explicitly known and validated.
If neither the source nor the approved home-currency policy supplies a currency, produce a typed
`CURRENCY_UNKNOWN` skip/identity failure and do not put a guessed currency in a review or dedupe
key. A home-currency fallback is permitted only if product explicitly defines it as the statement
currency policy; it must not be an accidental default.

Tests cover blank AI currency with USD source, blank source with available home currency, and both
unknown/unavailable. No test should assert an implicit EUR default.

## 13a — OCR timeout and per-item isolation

### P3-003 — Retry timeouts without swallowing caller cancellation

`TimeoutCancellationException` is a `CancellationException`, so the current retry catch exits on
the first timeout. Catch timeout first and retry only while the parent coroutine remains active;
rethrow genuine caller cancellation. After the bounded attempts, convert the timeout to a typed,
non-cancellation OCR failure code so an individual item can be marked failed without cancelling
the whole import.

Change batch collection from `awaitAll()` that fails the entire scope to per-item result collection.
Each item records the existing `OCR_FAILED`/failed-ledger status and a controlled reason; sibling
items continue. A cancelled parent still cancels all children and propagates `CancellationException`.
Do not log or persist OCR text, paths, exception messages, or stack traces.

Tests: timeout twice then success; exhausted timeout; non-timeout OCR failure; one failed page among
three; caller cancellation; no zombie writes after cancellation.

## Gate B — Statement resume identity/state machine (must be approved before 13b)

### P3-005 — Choose same-run resume or add a schema-backed cross-run key

The previous “new run, no schema change, natural-key lookup” proposal is not implementable: the
current DAO cannot find an item across runs, and `(runId,itemIndex)` is not a cross-run identity.
Choose exactly one design:

* **Same-run continuation (minimal migration if source identity is stable):** retain the cancelled/
  failed run, persist a resume cursor/state, and resume only when the exact statement source
  fingerprint and parser candidate identity/version match. Existing `(runId,itemIndex)` rows are
  authoritative; completed item statuses are not reprocessed. Add a CAS transition from
  `CANCELLED`/`FAILED` to `RUNNING`, persist cursor updates transactionally with each item, and
  finalize the same run. A changed parser output or source fingerprint starts a fresh run and is
  treated as a new statement. Because the current run entity lacks cursor/parser-version fields,
  this option also requires a Room version increment, non-destructive migration, schema snapshot,
  and migration tests unless an existing column is proven to carry the same typed state (free-text
  `errorSummary` must not be repurposed).
* **New-run resume:** add a stable statement-line identity (source fingerprint + canonical line id)
  to `BankStatementImportItem`, a unique cross-run index, and DAO queries that atomically claim or
  return an existing item. Define canonicalization of merchant/date/amount/currency, collision
  handling, and whether parser line index is stable across OCR/parser versions. Increment the Room
  version, add a non-destructive migration and schema snapshot, and migration tests.

The selected state machine must define old run statuses (`RUNNING`, stale/failed, cancelled,
completed), process death, duplicate completed import, changed source, and concurrent resume. A
completed run remains a duplicate; a cancelled/failed run is resumable only under the selected
identity proof. No fuzzy natural-key matching is allowed.

Required tests: cancel after item N then resume; process death and stale recovery; concurrent resume
claim; changed source/parser version; completed re-import; partial item creation; all item outcomes.

### P3-006 — Separate failed items from skipped items

After the identity/state-machine decision, finalize status using ledger counts: any true `FAILED`
item yields `FAILED`; no failed items plus one or more validation/duplicate/identity-proven skips
yields `COMPLETED_WITH_SKIPS`; only zero failures and zero skips yields `COMPLETED`. Persist
`failedItemCount` as failed rows only. On `COMPLETED_WITH_SKIPS`, update the receipt to the existing
review-created/partial status when reviews exist and return a structured success summary. An all-
skipped statement is still `COMPLETED_WITH_SKIPS` with zero created rows, never a misleading clean
success. UI/result mapping must be verified rather than assumed.

Tests cover mixed valid/skip/duplicate, all-invalid, one true failure, receipt status, event type,
and result summary.

## Validation and sequencing

Implementation is gated: approve Gate A, then Gate B, then land OCR retry/isolation and final-state
semantics. No Room schema claim may be made until the selected resume design is known.

Targeted tests (not run while editing this plan):

```text
*BankStatement*
*ValidateBankStatement*
*ReceiptOcrService*
*ReceiptRepository*
*ReceiptMatching*
*Migration*
```

Strict review must inspect the AI contract, parser-only fallback, cancellation propagation, ledger
CAS/resume behavior, privacy diagnostics, and any Room migration/schema snapshot. This plan remains
blocked if either identity gate is unresolved.
