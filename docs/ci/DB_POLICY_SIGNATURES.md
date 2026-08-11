# DB Policy Signature Discovery (D1)

**Status: PARTIAL / PENDING REVIEW**

D1 provides a fail-closed, candidate-only CLI for discovering exact Kotlin
callable signatures. It does not modify the active DB ownership policy or any
baseline. A candidate is written only to the path explicitly supplied by
`--output`; `--check` is read-only. Reports are written only when explicitly
requested with `--report`, and contain sanitized, structured data.

## Current limitation

Generic type parameters and bounds are currently unsupported. If resolving a
callable requires an unresolved generic parameter or bound, discovery fails
closed with `SIGNATURE_UNSUPPORTED`; no signature is added to the candidate or
active policy.

## CLI statuses

- `RESOLVED_EXACTLY` — one exact callable and required DAO-operation pair found.
- `SIGNATURE_UNSUPPORTED` — callable signature or type resolution is unsupported.
- `PAIR_NOT_FOUND` — exact callable found, but the required DAO-operation pair is absent.
- `METHOD_MISSING` — no callable with the requested identity exists.
- `AMBIGUOUS_OVERLOAD` — more than one same-name callable exists without an exact signature.

All non-`RESOLVED_EXACTLY` statuses are fail-closed and produce a non-zero
check result. Candidate-only semantics mean the active policy is never written;
a candidate is committed only when every entry resolves exactly, and otherwise
the output path is left unchanged. This document records the partial D1 state
and makes no completion claim.
