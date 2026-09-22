# Phase 2 verification — cell P-01 (adversarial pass over cell-P-01-audit.md)

Date: 2026-09-21
Verifier: **independent adversarial session** (ZCode/GLM-5.3, direct — different model
and session from the astra auditor, per the two-person rule).
Pinned source re-verified at verification time: `git rev-parse --short HEAD` =
`37601232`; `git diff --stat 37601232..HEAD -- app config scripts` empty; working-tree
diff vs pin for the same paths empty. Every cited line below was re-read at the pin.

## Verdict summary

| Finding | Auditor claim | Verdict | Severity |
|---|---|---|---|
| CA-P-01-001 | Deferred capture bypasses consent + package block | **CONFIRMED** | P0 (upheld) |
| CA-P-01-002 | Canonical fingerprints make REPLACE cancellation reachable | **CONFIRMED** | P2 (upheld) |
| CA-P-01-003 | Backoff-refused claim ends WorkManager chain with success | **CONFIRMED** | P2 (upheld) |
| CA-P-01-004 | Live filter ignores combined body prepared by extraction | **CONFIRMED** (worse than reported) | P2 (upheld) |
| CA-P-01-005 | Decrypt failure after claim leaves row PROCESSING, chain ends | **CONFIRMED** | P2 (upheld) |
| CA-P-01-006 | "42 kr" classified explicit SEK before home-currency resolution | **CONFIRMED** | P2 (upheld) |
| CA-P-01-007 | RP-10 doc describes 0xFF marker absent from code | **CONFIRMED** | P3 (upheld) |

**7/7 CONFIRMED, 0 REFUTED, 0 RECALIBRATED.** All severities upheld as calibrated.

## Per-finding verification evidence (decisive lines re-read)

### CA-P-01-001 — CONFIRMED (P0 upheld)
- `NotificationCaptureGate.kt:216-221`: `if (!settingsLoaded) return TemporarilyUnavailable`
  executes BEFORE the master-toggle check (224) and full privacy gate (232+). ✓
- `NotificationCaptureService.kt:461-524`: deferred branch re-reads settings; the only
  field consumed is `settings.rawNotificationStorageMode` (503-520). `notificationCaptureEnabled`
  is never consulted; content extracted at 498 and forwarded to `captureForRetry` at 509-524. ✓
- `NotificationIntakeCoordinator.kt:305-319`: `captureForRetry` gates ONLY on
  `DO_NOT_STORE`; all other modes persist the row (raw fields 387-391 or transient payload). ✓
- `NotificationIntakeWorker.kt:429-435` + `NotificationPrivacyGate.kt:23-35`: the only
  downstream check is `PrivacyCapability.NOTIFICATION_CAPTURE`, which the gate resolves
  by master toggle alone — no blocked-package lookup exists anywhere downstream. ✓
- Severity note: the disabled-toggle scenario is a transient persistence window (worker
  denial later purges); the **blocked-package scenario is a full bypass** — a package the
  user explicitly blocked gets parsed into review/expense. P0 stands on the bypass.

### CA-P-01-002 — CONFIRMED (P2 upheld)
- `NotificationIntakeCoordinator.kt:324-330`: row identity = content-derived
  `RawNotificationFingerprint.compute(...)`; `:430-437` insertOrIgnore conflicts only on
  identical content. `:487-490`: work name = `intake_${notificationKeyHash}` (key-derived)
  with `ExistingWorkPolicy.REPLACE` and 5s initial delay. Same notification slot updated
  with new content ⇒ second row inserted, second enqueue REPLACES first row's work. ✓
- P2 upheld: startup + listener-reconnect recovery sweeps exist and recur.

### CA-P-01-003 — CONFIRMED (P2 upheld)
- `NotificationIntakeDao.kt:94,98`: claim increments attempts AND requires
  `nowMs >= COALESCE(nextAttemptAt, 0)`. `NotificationIntakeWorker.kt:105-107`:
  zero-row claim returns unconditionally — the "already claimed" comment is also true for
  backoff-not-due. `NotificationIntakeRetryPolicy.kt:12-13` (attempt 2 = 120s) vs
  coordinator WM backoff 30s (`NotificationIntakeCoordinator.kt:476-479`) ⇒ WM retry at
  ~30s is refused, returns success, chain ends; row FAILED_RETRYABLE until a rescue sweep. ✓

### CA-P-01-004 — CONFIRMED, and WIDER than reported (P2 upheld)
- `NotificationTextParts.kt:91-102`: the returned `bigText` is the RAW extras field (94);
  `effectiveBigText` (info/summary fallback, 43-45) and `combinedBody` (textLines +
  MessagingStyle messages, 85-89) are separate fields.
- `NotificationCaptureService.kt:556`: filter receives `parts.bigText` — so textLines,
  MessagingStyle messages AND info/summary-text (when bigText itself is blank) are all
  invisible to the live filter, while the fingerprint one line earlier (537) uses
  `combinedBody`. `NotificationFilter.decide:165-167` decides on the three passed fields only. ✓
- Auditor's example is valid; the gap additionally covers the effectiveBigText fallback.

### CA-P-01-005 — CONFIRMED (P2 upheld)
- `NotificationIntakeWorker.kt`: claim at 100 → payload load 126 → decrypt 163-167 — all
  BEFORE the intake-repair try-block starting at 241. `NotificationTransientPayloadCrypto.decrypt:69-82`
  throws GeneralSecurityException (keystore/doFinal/parse). Escaping decrypt leaves the row
  PROCESSING; next WM run's claim is refused by the DAO status predicate (97) and returns
  success (105-107). `releaseStaleProcessing:179-189` exists but requires a later sweep
  after the stale threshold. ✓

### CA-P-01-006 — CONFIRMED (P2 upheld)
- `NotificationMoneySignalDetector.kt:30-32`: SEK, NOK, DKK all carry isoCode "kr", SEK
  first. `:42-62`: explicit-ISO loop matches "42 kr" against SEK's regex first and returns
  `EXPLICIT_ISO_CODE`, `ambiguous=false`, confidence 0.95. `:109-128`: the intended
  home-currency kr branch (AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME) is unreachable for any
  positive amount the SEK regex matches. ✓

### CA-P-01-007 — CONFIRMED (P3 upheld)
- `RP-10-notification-capture.md:184-186`: claims "presence bit behind a 0xFF first-byte
  marker inside the ciphertext". `NotificationTransientPayloadCrypto.frame:87-101`:
  writes ABSENT/PRESENT byte + length-prefixed fields; no marker byte exists. Writer and
  reader agree with each other; drift is in the completion documentation only. ✓

## Notes for the registry
- All seven are eligible for registry entry with CONFIRMED status; severity calibration
  upheld with no changes.
- CA-P-01-001 should headline the next remediation planning round (privacy bypass of an
  explicit user block).
- CA-P-01-002/003 resurrect previously-refuted/reme​diated behavior under new premises
  (FRESH-P1-007 regression-reachable; FRESH-P9-005 caller regression) — cross-reference
  both old IDs when registering.

Validation: NOT RUN (static verification only). No production files were modified.
