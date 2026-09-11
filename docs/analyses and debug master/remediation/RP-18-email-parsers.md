# RP-18 — Email parser correctness

> **Mode:** strict privacy/lifecycle. **Status:** CONDITIONAL. Service is staged and unwired; do not silently wire it.

## 18-A Parsing

Anchor total labels to line starts and try specific labels before bare `Total`; capture numeric grammar so VAT annotations cannot select unit prices. Remove bare two-letter currency tokens; use symbols, bounded ISO codes, and trusted sender domains. Unknown currency skips with controlled `CURRENCY_UNRESOLVED`; summary rows are excluded from items.

Tests cover subtotal/total, VAT suffixes, unit prices, currency false positives (`it`, `us`, `de`), unknown currency, and parser corpus.

## 18-B Identity and deduplication

Use calendar-date fingerprinting rather than epoch-hour buckets. Blank `messageId` uses a separate domain-separated content-fingerprint API and never the message-ID hash API. Distinct content remains distinct; identical content behavior with sender/date fields is documented and tested.

The derivable HMAC key is not fixed by an `h1:` prefix. RP-00 chooses a Keystore-backed installation secret with restart, invalidation, backup/restore, cross-install, rotation, legacy migration, and duplicate semantics, or records accepted debt. No public SHA fallback.

Use the hash as canonical email identity in every storage mode. Re-ingesting a NeedsReview receipt re-evaluates and updates it in one lifecycle transaction; ordinary duplicates remain duplicates.

Tests cover timezone stability, blank IDs (distinct/identical content), key lifecycle/migration, all storage modes, and NeedsReview upgrade/refresh.

## 18-C Routing, refunds, and staging

Trusted sender-domain matches may select provider parsers. Body-only keywords route to a generic parser only; provider parsers do not run in production for unknown senders. Ambiguous generic parses become typed review/reject outcomes. Unsupported refunds skip with `REFUND_UNSUPPORTED`; no heuristic second purchase.

Mark service/module and architecture records `STAGED — NOT WIRED` until D2 decides production wiring. Shared lifecycle/hashing changes require strict review.

Tests cover sender/body routing, generic `canParse`, unknown senders, refund skips, and staged entry-point guards.

## Sequence and gates

18-A → 18-B → 18-C. Targeted parser, hashing, routing, privacy, and lifecycle tests plus strict review are required.
