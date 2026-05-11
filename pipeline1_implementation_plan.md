# Pipeline 1 implementation plan — Notification Capture
Scope: remaining P1 gaps + hardening of already-implemented fixes + universal contracts that still affect Pipeline 1.

## Executive goal
Move Pipeline 1 from **“improved but partial”** to **“closure-ready”** by fixing:
- privacy ordering
- message extraction robustness
- shutdown/data-loss risk
- coarse diagnostics
- hardening of typed outcomes + restore coverage

## Delivery order
1. **PR1 — Finish privacy gate ordering (P1-P1-05, U3)**
2. **PR2 — Harden extraction for `textLines` / `messages` (P1-P1-03)**
3. **PR3 — Eliminate shutdown loss with durable intake handoff (P1-P1-07, U1/U2)**
4. **PR4 — Upgrade diagnostic ledger from coarse to forensic (P1-P1-02, U8)**
5. **PR5 — Harden implemented fixes: typed outcomes + restore coverage (P1-P1-01, P1-P1-06)**
6. **PR6 — Cleanup, retention, docs, and closure tests**

---

## PR1 — Finish privacy gate ordering
**Priority:** Critical  
**Files likely touched:**
- `service/NotificationCaptureService.kt`
- `service/NotificationFilter.kt`
- raw notification/privacy repo/settings files
- logging helpers

### Problem
Main posted-notification path checks privacy early, but the manual refresh/bypass path still extracts extras before the gate. Also settings observer failure currently fails open.

### Changes
1. Add a single **pre-extraction capture gate** method used by:
   - `onNotificationPosted()`
   - `processNotificationBypassDedupe()`
   - any refresh/rescan entrypoint
2. Gate inputs must be metadata-only:
   - package name
   - notification key/hash
   - posting time
   - category/channel metadata
   - no body/title/text extraction yet
3. Change privacy decision source:
   - use cached in-memory snapshot for fast path
   - if snapshot unavailable/stale, **fail closed** for body extraction
4. Split decisions:
   - `DENY_CAPTURE`
   - `ALLOW_CAPTURE_REDACTED`
   - `ALLOW_CAPTURE_RAW`
5. Ensure logs/diagnostics emitted before capture do not include raw text.

### Tests
- refresh path never calls text extractor when privacy denied
- observer/settings failure denies extraction
- redacted mode never persists body/title
- posted + refresh paths use same gate

### Done when
No Pipeline 1 path extracts user text before privacy approval.

---

## PR2 — Harden text extraction
**Priority:** High  
**Files:**
- `service/NotificationCaptureService.kt`
- add `NotificationTextExtractor.kt` if useful
- `NotificationFilter.kt`
- parser tests

### Problem
`textLines` support exists, but `messages` extraction appears brittle.

### Changes
1. Centralize extraction into one helper returning:
   - `title`
   - `body`
   - `textLines`
   - `messages`
   - `combinedBody`
2. Parse messaging payloads properly:
   - use Android messaging-style bundle decoding, not only `toString()`
3. Normalize text:
   - trim
   - remove blanks
   - stable ordering
   - dedupe repeated lines
4. Define deterministic `combinedBody` composition:
   - title/body first
   - then lines
   - then messages
5. Add truncation limits to avoid oversized payloads while preserving parser signal.

### Tests
- notification with only `EXTRA_TEXT_LINES`
- messaging-style notification with bundle messages
- duplicate line suppression
- combinedBody stable hash across field ordering noise

### Done when
Pipeline sees the same normalized text regardless of notification style quirks.

---

## PR3 — Replace shutdown-drain dependence with durable intake
**Priority:** Critical  
**Files:**
- `service/NotificationCaptureService.kt`
- new intake entity/DAO/repo files
- `NotificationProcessingPipeline.kt`
- if worker-based: worker + `WorkerExecutionGuard`

### Problem
Current `onDestroy()` cancels work; accepted notifications can still be lost.

### Recommended fix
Do **not** reintroduce long blocking drain in `onDestroy()`.  
Instead add a **durable intake stage**.

### Changes
1. Introduce `NotificationIntake` table/entity:
   - id
   - package name
   - notification key hash
   - post time
   - privacy mode used
   - normalized extracted payload or redacted payload
   - status: `PENDING/PROCESSING/DONE/FAILED/DROPPED`
   - attempt count / last error code
2. Service flow becomes:
   - gate privacy
   - extract only if allowed
   - persist intake row quickly
   - schedule/trigger processor
3. Processor flow:
   - claim pending intake
   - run existing filter/parser/repository path
   - persist typed outcome + linkage IDs
4. Dedupe key lifetime must move from in-memory only to durable semantics:
   - unique key on `(notificationKeyHash, postTimeBucket)` or equivalent
5. On startup/service reconnect, replay pending rows.
6. If implemented as Worker, wrap with `WorkerExecutionGuard`.

### Tests
- service destroyed after intake insert but before parse -> replay succeeds
- duplicate post -> single intake row
- restore mode prevents processing, row remains pending/retryable
- processor crash -> retry path works

### Done when
A captured notification is not lost because the service process dies mid-flight.

---

## PR4 — Upgrade diagnostics to forensic quality
**Priority:** High  
**Files:**
- `NotificationProcessingPipeline.kt`
- diagnostic event entity/DAO/repository
- `NotificationRepository.kt`

### Problem
Durable diagnostics exist but are too coarse.

### Changes
Extend diagnostic events for Pipeline 1 with:
- `notificationKeyHash`
- `rawFingerprintHash`
- `pipelineRunId/correlationId`
- `expenseId`
- `reviewId`
- `parserId`
- `confidence`
- `decisionSource` (`filter/classifier/parser/privacy/restore/dedupe`)
- `errorClass`
- `elapsedMs`

Important: use **hashes/redacted values**, not raw text.

Emit events at:
- capture denied
- dedupe drop
- filter reject
- parser failed
- review created
- expense created
- duplicate detected
- restore blocked
- exception/retry

### Tests
- every typed outcome emits one terminal event
- review/expense IDs present when created
- privacy-denied event contains no raw body

### Done when
You can reconstruct why a notification did or did not create an expense.

---

## PR5 — Harden already-implemented fixes
**Priority:** Medium

### A. Typed outcomes (P1-P1-01)
**Goal:** move from “fixed” to “fully surfaced and testable”.

Changes:
- make repository return the typed pipeline outcome to caller/service or to the durable intake processor
- map outcomes to metrics and diagnostics centrally
- remove any remaining “generic success” assumptions in callers

Tests:
- one test per terminal outcome
- terminal outcome -> expected diagnostic + side effects

### B. Restore coverage (P1-P1-06)
**Goal:** prove the fix, not just assume it.

Changes:
- verify guard points exist:
  - before processing
  - before repository writes
  - before replaying pending intake rows
- add tests where restore mode flips between intake and processing

Done when:
- restore mode never allows a write after block,
- pending rows remain safe to replay later.

---

## PR6 — Cleanup, retention, docs, closure pass
**Priority:** Medium

### Changes
1. **Retention/privacy cleanup**
   - ensure raw notification payloads respect `rawNotificationStorageMode`
   - if redacted/disabled, purge old raw fields from intake/diagnostic storage too
2. **Tracker/docs sync**
   - update Pipeline 1 tracker statuses only after tests pass
3. **CI guardrails**
   - forbid direct raw-text logging in notification path
   - grep/lint for raw notification extras dumped to logs
4. **Stress tests**
   - burst of notifications across mixed packages
   - rapid service destroy/recreate
   - replay after crash
   - privacy toggles during capture

---

## Suggested test files
- `NotificationCapturePrivacyGateTest`
- `NotificationTextExtractorTest`
- `NotificationIntakeReplayIntegrationTest`
- `NotificationProcessingPipelineOutcomeTest`
- `NotificationDiagnosticsIntegrationTest`
- `NotificationRestoreModeIntegrationTest`

---

## Closure criteria
Pipeline 1 can be called clean/stable only when:
- no capture path extracts text before privacy approval
- messaging/textLines extraction is deterministic and tested
- service death cannot silently lose accepted notifications
- diagnostics explain every terminal decision
- typed outcomes are surfaced end-to-end
- restore-mode blocking is proven by tests
- docs/tracker match HEAD

## Recommended status target after this plan
- P1-P1-01: **closed**
- P1-P1-02: **closed**
- P1-P1-03: **closed**
- P1-P1-05: **closed**
- P1-P1-06: **closed**
- P1-P1-07: **closed**