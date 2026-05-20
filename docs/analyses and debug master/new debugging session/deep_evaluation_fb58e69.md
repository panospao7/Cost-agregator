# Deep Evaluation / Debugging Report — `fb58e690e2d89d691df2b548ef54f972eddcda61`

## Executive verdict
This commit is a **partial hardening pass**, not a closure pass.

### What it improves
- Removes raw sender from the provider-detection log.
- Replaces raw sender in the error log with `correlationId`.
- Adds static guard rules:
  - G12: empty-prompt `prepareText(..., "")`
  - G13: email side-effect dispatch without `correlationId`
- Adds a regression test file with 15 tests.

### What it does **not** solve
- Email persistence is still not payload-first.
- Raw `messageId` still flows through the live email path.
- The new tests are mostly contract/spec tests, not real service/DAO/worker behavior.
- Static guard remains heuristic and bypassable.
- The larger open privacy issues from prior reviews remain unchanged.

---

## 1) Confirmed good fixes

### Log sanitization
In `EmailReceiptIngestionService`:
- provider detection no longer logs raw sender
- exception log no longer includes raw sender

This is a real improvement and should stay.

### Static guard additions
The new rules are directionally correct:
- block empty-prompt policy probing
- block email side-effect calls that omit correlation

But they are still shallow, see below.

---

## 2) New/remaining issues found

### PRIV-FB58-01 — plaintext fallback for message hashing
Severity: **high**
Type: **privacy bug / policy violation**

In the email ingestion path, message hashing still falls back to the raw `messageId` if HMAC hashing returns null.

That means:
- the system can silently degrade to plaintext
- the “never store raw sensitive identifiers” invariant is not fully enforced

Also, the raw `messageId` is still passed separately to the coordinator, so the payload-first contract is still not complete.

### Fix
- remove the `?: messageId` fallback
- fail closed if hashing fails
- pass only hashed/payload-approved identifiers downstream unless a strict raw policy allows otherwise

---

### PRIV-FB58-02 — tests are mostly not behavioral
Severity: **medium/high**
Type: **test-quality / regression-risk**

The new test file looks good on paper, but many tests only verify constants or policy objects, not the actual service paths.

Examples:
- email side-effect test does not instantiate the real ingestion/coordinator path
- notification blocked-cache test uses booleans, not `NotificationCaptureService`
- retention tests use fake `RetentionTarget`s
- cloud tests hit `DefaultCloudPayloadPolicy` directly, not provider transport
- receipt-image tests validate policy behavior, not end-to-end request construction

So these tests will **not** catch the exact regressions found earlier.

### Fix
Add real integration/behavior tests around:
- `EmailReceiptIngestionService`
- `ReceiptLifecycleCoordinator`
- `NotificationCaptureService`
- `DataRetentionWorker`
- cloud provider transport/request builders

---

### PRIV-FB58-03 — static guard is still bypassable
Severity: **medium**
Type: **architecture/regression risk**

#### G12 weakness
The empty-prompt rule is line-regex based. It will miss:
- multiline calls
- empty-string variables
- helper methods that forward `""`

#### G13 weakness
The email side-effect rule only matches one exact positional form. It can miss:
- named arguments
- multiline calls
- wrapper/helper dispatches
- empty-string correlation fallbacks

#### Existing weaknesses remain
- G3 still relies on nearby `prepared` context
- G4 still relies on a loose context check for `PrivacyDecision.Allowed`

### Fix
Prefer a transport abstraction or AST-level check over regex heuristics.

---

## 3) What remains open from prior reviews
This commit does **not** touch these core issues, so they remain open unless fixed elsewhere:

- fail-closed `PrivacySettings` update safety
- notification blocked-package startup fail-closed
- retention registry completeness and accurate counts
- email hash schema cleanup
- retention-safe email entity nullability/redaction
- cloud categorization empty-prompt probe in real provider code
- receipt-image policy ownership at transport boundary

---

## 4) Bug vs architecture classification

### Actual bugs
- plaintext fallback on hash failure
- raw `messageId` still flowing in the live email path
- tests do not actually exercise live regressions

### Architectural debt
- regex-based static guard
- fake “behavioral” tests
- no transport-level proof for cloud/email/notification/retention paths

### Good cleanup
- raw sender removed from logs

---

## 5) Priority next actions
1. Remove plaintext hash fallback in email ingestion.
2. Stop passing raw `messageId` downstream unless explicitly allowed.
3. Replace the new “behavioral” tests with real service/DAO/worker tests.
4. Harden the static guard or replace it with a transport abstraction.
5. Continue the remaining privacy PRs unchanged by this commit.

## Sources reviewed
- Commit: https://github.com/panospao7/Cost-agregator/commit/fb58e690e2d89d691df2b548ef54f972eddcda61
- `EmailReceiptIngestionService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `verify_privacy_boundaries.py`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/scripts/verify_privacy_boundaries.py
- `PrivacyBehavioralRegressionTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/fb58e690e2d89d691df2b548ef54f972eddcda61/app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyBehavioralRegressionTest.kt