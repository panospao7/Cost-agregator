# Final Deep Evaluation — `850c0e8258141a22021eb4a52a299853e1cb60d9`

## Executive verdict
This is a **good narrow fix**, not a closure commit.

### What it fixes
- Removes the plaintext fallback for `messageId` hashing.
- Fails closed if HMAC hashing fails.
- Passes the hashed `messageId` to the coordinator.
- Keeps the coordinator-side correlation flow intact.

### What remains open
1. `createFingerprint(...)` still falls back to `raw.hashCode().toString(16)`.
2. The invalid-receipt validation path still exposes raw merchant/amount in the error text.
3. The exception diagnostic still uses plain SHA on `messageId`, not keyed hashing.
4. The new regression test is not actually behavioral.
5. The broader privacy backlog remains unchanged.

---

## Confirmed improvement
The main fix is correct:
- the email path no longer silently degrades to raw plaintext `messageId`
- if hashing fails, it now returns a failure instead of persisting unsafe data

That is the right fail-closed behavior.

---

## Remaining issues in this commit

### 1) Fingerprint fallback still uses `hashCode()`
**Severity:** medium  
**Type:** privacy/architecture bug

`createFingerprint(...)` still ends with a non-cryptographic fallback when SHA hashing fails.

Why this matters:
- it reintroduces a weak identifier fallback
- it violates the “no unsafe fallback” direction established by this fix
- it makes dedupe behavior depend on a weaker path than the rest of the privacy design

### Fix
Remove the fallback entirely:
- if hashing fails, fail closed
- do not use `hashCode()` for sensitive or quasi-sensitive identifiers

---

### 2) Validation error still leaks raw receipt content
**Severity:** medium  
**Type:** actual privacy leak

The validation failure path still includes raw merchant and amount details in the returned error text.

Why this matters:
- the error can be logged, surfaced, or forwarded
- raw receipt content should not appear in error strings unless explicitly allowed

### Fix
Replace it with a sanitized error message:
- “Invalid receipt data”
- or a safe diagnostic code
- if needed, attach only hashed/safe metadata

---

### 3) Diagnostic hash strategy is still not fully aligned
**Severity:** low/medium  
**Type:** architectural/privacy consistency issue

The exception diagnostic still hashes the raw `messageId` with plain SHA-style hashing.

Why this matters:
- the project’s privacy plan prefers keyed hashes for external identifiers
- plain SHA is weaker for linkability/privacy boundaries

### Fix
Use the keyed hashing service consistently for external identifiers in diagnostics as well.

---

### 4) The new regression test is not behavioral
**Severity:** medium/high  
**Type:** test-quality issue

The added test only simulates a null hash result with local values. It does **not**:
- instantiate the ingestion service
- inject a failing hash service
- verify real control flow
- prove the fallback cannot regress in production code

### Fix
Turn it into a real service-level test:
- mock/fake `SensitiveHashingService`
- return null from the actual dependency
- assert `EmailReceiptIngestionService` fails closed

---

## What this commit does not address
Still open from the broader backlog:
- email payload/schema cleanup
- cloud payload provenance hardening
- notification startup fail-closed race handling
- retention registry completeness
- static privacy guard hardening
- broader behavioral integration tests

---

## Bug vs architecture classification
### Actual bugs
- fingerprint fallback to `hashCode()`
- raw merchant/amount in validation error text
- non-behavioral regression test

### Architectural debt
- plain SHA for diagnostic external IDs
- unused `messageId` parameter in `createFingerprint(...)`

### Good cleanup
- plaintext fallback for `messageId` is properly removed

---

## Priority next actions
1. Remove `hashCode()` fallback from `createFingerprint(...)`.
2. Sanitize the validation failure message.
3. Switch diagnostic hashing to keyed hashing where required.
4. Convert the new test into a real service-level behavioral test.
5. Resume the remaining privacy PRs from the backlog.

## Sources
- Commit: https://github.com/panospao7/Cost-agregator/commit/850c0e8258141a22021eb4a52a299853e1cb60d9
- `EmailReceiptIngestionService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/850c0e8258141a22021eb4a52a299853e1cb60d9/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- Commit diff view: https://github.com/panospao7/Cost-agregator/commit/850c0e8258141a22021eb4a52a299853e1cb60d9