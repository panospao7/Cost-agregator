# Final Deep Evaluation — `fb58e690e2d89d691df2b548ef54f972eddcda61`

## Executive verdict
This commit is a **real hardening step**, but it is **not a closure commit**.

### What it genuinely improves
- Removes raw sender from provider-detection logs.
- Removes raw sender from one error log path.
- Adds static guard rules:
  - G12: flag empty-prompt `prepareText(..., "")`
  - G13: flag email side-effect dispatch without `correlationId`
- Adds a broad regression-test file for privacy boundaries.

### What remains open
1. Email hashing still has a plaintext fallback path.
2. Email live-path persistence is still not payload-first.
3. Most new “behavioral” tests are not actually exercising live service/DAO/worker paths.
4. Static guard is still regex/heuristic based and bypassable.
5. PR6–PR8 are only partially complete in practice.

---

## 1) Resolved or improved items

### Log sanitization
This part is good:
- raw sender is no longer logged in provider detection
- raw sender is no longer embedded in the caught exception log

This is a user-facing privacy improvement and should stay.

### Static guard additions
The new guard rules are directionally correct:
- empty-prompt probing is now flagged
- missing email `correlationId` dispatch is now flagged

This helps catch two real regressions.

---

## 2) Remaining bugs and risks

### PRIV-FB58-01 — plaintext fallback for message hashing
**Severity:** High  
**Type:** actual privacy bug

The email ingestion path still falls back to raw `messageId` when hashing fails.

That means the system can still silently degrade into storing or propagating a raw sensitive identifier, which violates the core privacy rule:
- no raw sensitive value unless policy explicitly allows it

### Why this matters
If HMAC or hash generation fails, the safe behavior is **fail closed**, not **store plaintext**.

### Fix
- remove any `?: messageId` fallback
- if hash generation fails, reject, skip, or mark as privacy-blocked
- keep raw `messageId` strictly ephemeral unless raw storage mode explicitly allows it

---

### PRIV-FB58-02 — tests are mostly not behavioral
**Severity:** Medium/High  
**Type:** regression-risk / test-quality issue

The new test file looks comprehensive, but many checks are still model/spec-level rather than live-path behavioral tests.

Examples of what is still weak:
- policy object tests instead of real provider transport tests
- fake retention targets instead of `DataRetentionWorker` against real DAO behavior
- boolean cache tests instead of actual `NotificationCaptureService` execution
- contract assertions instead of service/coordinator integration

### Why this matters
These tests will not reliably catch:
- duplicate dispatch bugs
- raw-path leaks in provider request builders
- real notification extra access ordering bugs
- retention deleting or redacting the wrong rows

### Fix
Add or convert to real tests around:
- `EmailReceiptIngestionService`
- `ReceiptLifecycleCoordinator`
- `NotificationCaptureService`
- `DataRetentionWorker`
- actual cloud provider request construction

---

### PRIV-FB58-03 — static guard is still bypassable
**Severity:** Medium  
**Type:** architectural regression risk

The new guard rules are helpful, but still heuristic:
- G12 is text-pattern based and can miss multiline/helper indirections
- G13 can be bypassed by wrappers, named args, or helper methods
- G3/G4 from earlier reviews remain heuristic and are still weak

### Fix
Long-term:
- prefer a transport abstraction for cloud requests
- move away from regex-only enforcement
- keep script checks, but make the architecture itself harder to bypass

---

## 3) What this commit does not solve
These remain open unless fixed in another commit:
- fail-closed `PrivacySettings` update safety
- blocked-package startup fail-closed notification gating
- retention registry completeness
- explicit email hash columns and schema cleanup
- retention-safe nullable/redacted email fields
- cloud receipt/image policy hardening at the transport boundary

---

## 4) Bug vs architecture classification

### Actual bugs
- plaintext fallback on hash failure
- tests that do not exercise the real code paths

### Architectural debt
- regex-based static privacy guard
- no payload-first proof for live email path
- no transport-level guarantee for provider safety

### Good cleanup
- raw sender removed from logs

---

## 5) Final assessment of PR6–PR8
### PR6 — Static guard hardening
**Status:** Partial
- good additions
- still too weak to trust alone

### PR7 — Log sanitization
**Status:** Mostly improved
- actual log exposure reduced
- still need broader diagnostics audit

### PR8 — Behavioral tests
**Status:** Not yet truly achieved
- tests exist, but many are not live behavioral coverage

---

## 6) Priority next actions
1. Remove plaintext fallback from email hashing.
2. Make the new tests truly behavioral.
3. Replace or strengthen the heuristic static guard.
4. Continue the remaining privacy PRs unchanged.

## Bottom line
This commit is a **useful hardening pass**, but not a final fix.  
It reduces risk, but the core privacy architecture is still not fully closed.

## Sources reviewed
- Commit: `fb58e690e2d89d691df2b548ef54f972eddcda61`
- `EmailReceiptIngestionService.kt`
- `verify_privacy_boundaries.py`
- `PrivacyBehavioralRegressionTest.kt`