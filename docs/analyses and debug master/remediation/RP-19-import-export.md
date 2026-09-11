# RP-19 — Import/export correctness

> **Mode:** strict for money/lifecycle/privacy. **Status:** CONDITIONAL; D3 controls UI promotion. Encryption remains RP-03-owned.

## 19-A CSV and locale boundaries

Replace line splitting with a character-stream RFC-4180 reader preserving quoted LF/CRLF and embedded quotes. Strip UTF-8 BOM at the first ingestion boundary, including after leading empty lines/comments. Pin import/export date formatters to `Locale.US`; dates remain zone-less `LocalDate`.

Tests: multiline LF/CRLF, embedded quotes, malformed quote without spurious rows, BOM variants, Arabic/Persian locale.

## 19-B Explicit roundtrip contract

Publish a field matrix: `amount` is original amount; `effectiveAmount` is reconstructed only where the entity supports the shared-effective field; `transactionType` maps to the exact enum; shared/refund/transfer/deposit metadata maps explicitly or has documented unsupported defaults. Unknown types fail with `UNKNOWN_TRANSACTION_TYPE`.

Choose one precedence per transaction type and make CSV/JSON consistent; do not mix “effective first” with JSON’s amount-first fallback. Test ordinary, shared, refund, transfer, deposit, negative, missing, and unknown-type roundtrips with exact effective values.

## 19-C Export precision and state

Use a dedicated non-scientific FX-rate formatter (up to six decimals), never a money formatter. Serialize starts, cancel-and-join prior jobs, check cancellation per page, and use unique temp names. Cancellation cannot leave a successful final file. Accounting formats reject empty datasets; CSV/JSON may emit header-only files with an explicit warning.

Sweep stale temporary plaintext files conservatively; deletion of user final exports is a separate product decision. Define snapshot consistency; if retaining the current non-snapshot contract, document and test concurrent-write row counts.

Tests: rate precision, cancellation cleanup, concurrent starts, header-only behavior, temp sweep, concurrent-write consistency.

## 19-D Canonical ImportCoordinator

Do not delete `ImportCoordinator` merely because callers are sparse: architecture documents identify it as the canonical facade. Either retain/repair it (including per-row errors), or replace it with a new facade and atomically update LEGAL_PATHS, CODEBASE_SEGMENTS, inventory, guards, and tests. RP-20 does not own this decision.

## Deferred partials and gates

Assign one owner/disposition to debug promotion, snapshot semantics, conversion status, receipt links/provenance, and accounting business fields. Sequence 19-A → 19-B → 19-C → 19-D. Require strict money/lifecycle/privacy review and targeted tests.
