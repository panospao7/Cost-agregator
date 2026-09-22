# CL-01 Implementation Spec — Notification capture privacy and extraction boundary
Provenance: 2026-09-22; pin 37601232 verified; astra high Stage-3 session; source cluster-map.md.

## Context for the coder
- Notification capture turns eligible bank/payment notifications into intake for later review or expense processing. The deferred startup path must respect capture consent and package blocking before reading or forwarding content. Extended notification fields must reach the live filter so legitimate transactions are not discarded.
- Root cause: deferred capture reads storage settings without a final authorization check; live filtering receives raw bigText instead of the combined extracted body.
- Both findings are VERIFIED in verification-2026-09-21-p01.md. This session re-opened source and confirmed anchors; it did not run tests or constitute another independent verification.

## Pre-implementation checks (coder MUST run, in order)
1. `git diff 37601232..HEAD -- app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceDeferredPolicyTest.kt app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt` — read every hunk; anchors below are from the pin and may have drifted; function names + signatures are authoritative, line numbers advisory.
2. Re-read the target functions and supporting gate, extraction, filter, coordinator and tests at current HEAD. Check uncommitted changes before editing.

## Legal path constraints
- LEGAL_PATHS.md §Notification Capture: keep service → NotificationIntakeCoordinator.capture/captureForRetry → intake persistence/enqueue. The service must NOT write directly to NotificationIntakeDao or enqueue work itself.
- Preserve the coordinator's DatabaseWriteBarrier, DomainTransactionRunner, canonical fingerprint, encryption and storage contracts. The document's abbreviated signatures/storage summary are not substitutes for current source.
- The relevant gate is domain/notification/capture/NotificationCaptureGate.kt, not the similarly named domain/privacy class. Its `suspend fun decide(packageName: String, isShuttingDown: Boolean): NotificationCaptureDecision`, ≈L158-286, uses caches and 300-ms self-healing reads; another invocation alone does not guarantee fresh authorization.
- Use service collaborators already injected at ≈L135-147: PrivacyGate, PrivacySettingsRepository, BlockedPackageDao. Do not invent a new consent flag or change the global privacy policy. Preserve the initial restore/shutdown gate. POST_NOTIFICATIONS permission must not gate capture.

## Work items
### WI-1 — Authorize deferred capture before extraction (CA-P-01-001, P0, VERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt — `private fun captureNotification(sbn: StatusBarNotification, source: CaptureSource)`, ≈L363; TemporarilyUnavailable branch ≈L439-527 @pin 37601232.
- Defect: the branch reads storage settings, extracts content, and calls captureForRetry without proving capture is allowed or the package unblocked. Later worker cleanup cannot undo unauthorized persistence.
- Change: retain the initial nonterminal unavailable diagnostic, then replace the settings-only branch with this exact short-circuit sequence before reading notification extras, resolving app name, or forwarding:
  1. **Consent/capability:** call `privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)`; only Allowed proceeds. Denied, FailClosed, NotApplicable, ordinary exception or locally owned timeout drops the capture. This full capability decision is the consent authority.
  2. **Master toggle/storage snapshot:** freshly call `privacySettingsRepository.getSettings()`; require `notificationCaptureEnabled == true`. Use that same object for rawNotificationStorageMode; no default mode on read failure and no second service settings read. A disabled toggle drops capture even if step 1 returned Allowed.
  3. **Blocked package:** freshly call `blockedPackageDao.isBlocked(packageName)`; only false proceeds. True, exception or local timeout drops capture. Do not use the cached package set or NOTIFICATION_PACKAGE_ALLOWLIST as a substitute: domain/privacy/NotificationPrivacyGate.kt, `check(capability, context)` ≈L19-36, checks only the master toggle for that capability.
  4. **Authorized handoff:** extract once with NotificationTextParts, build the existing per-mode extras value, and forward the same fields/storage snapshot to captureForRetry under its existing NonCancellable scope. Authorization reads remain outside that scope and cancellable; do not schedule content-bearing recovery when authorization is unresolved.
- Bound each authorization read to 300 ms, matching the capture gate's existing self-heal budget. Use local timeout handling such as withTimeoutOrNull; rethrow parent CancellationException before generic catches. Extend the existing CAPTURE_CANCELLED emission/rethrow path to all three deferred reads. Never treat cancellation as permission.
- Keep the coordinator unchanged. Supporting anchor: domain/notification/capture/NotificationIntakeCoordinator.kt, `suspend fun captureForRetry(packageName: String, notificationKey: String, postTime: Long, correlationId: String, title: String? = null, text: String? = null, combinedBody: String? = null, subText: String? = null, storage: DeferredCaptureStorageSnapshot): NotificationIntakeCaptureResult`, ≈L281-319, 336-430. DO_NOT_STORE returns NotStored before encryption/insertion; STORE_RAW persists authorized visible fields; other storing modes use transient encrypted content.
- Acceptance criteria: rejected/unresolved consent, disabled master toggle, or blocked/unreadable package causes no extraction or coordinator handoff. Later checks do not execute after earlier rejection. Authorized captures forward exactly once with the actual storage mode. This sequential check does not claim atomic exclusion against a subsequent user setting change.

### WI-2 — Feed combinedBody to the live filter (CA-P-01-004, P2, VERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt — same captureNotification signature, ≈L531-556 @pin 37601232.
- Supporting anchors: domain/notification/capture/NotificationTextParts.kt, `fun extract(extras: Bundle): NotificationTextParts`, ≈L34-101; service/NotificationFilter.kt, `fun decide(packageName: String, title: String?, text: String?, bigText: String?): NotificationFilterDecision`, ≈L154-168, 297-307, 374-384.
- Defect: extraction includes infoText, summaryText, textLines and MessagingStyle messages in combinedBody, but the live call passes parts.bigText.
- Change: replace only the fourth argument with parts.combinedBody: `NotificationFilter.decide(packageName, parts.title, parts.text, parts.combinedBody)`. Keep the parameter name bigText and existing title/text arguments. Use the same extracted parts instance already used for fingerprinting and coordinator.capture(combinedBody = parts.combinedBody); do not concatenate fields again or change filter policy.
- Acceptance criteria: a payment present only in an extended field reaches the real live filter and accepted intake. No-amount/security/promotion exclusions continue to apply to all visible content. Existing top-level text/bigText behavior and fingerprint semantics remain intact.

## Tests
- WI-1: update existing app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceDeferredPolicyTest.kt. Reuse its Robolectric service-without-onCreate and onNotificationPosted fixture; inject explicit PrivacyGate and BlockedPackageDao mocks. Successful storage fixtures must explicitly grant Allowed, enabled toggle and unblocked package.
  1. Initial gate unavailable; Denied, FailClosed, NotApplicable separately: no later checks/extraction/handoff.
  2. Allowed followed by fresh disabled toggle with STORE_RAW retained: no package query/extraction/handoff.
  3. Allowed + enabled + blocked: no extraction/handoff.
  4. Ordinary exception and local timeout at each authorization read separately: no handoff and controlled terminal diagnostic.
  5. Parent cancellation at each read: cancellation accounted for/rethrown, no handoff. Wait for completion/accounting rather than assuming observation of the earlier nonterminal event proves completion.
  6. Successful check ordering; a fresh DAO lookup despite an initial stale/unavailable gate result; one branch settings snapshot feeds both toggle and storage.
  7. All four storage modes preserve RAW sanitized extras, REDACTED marker, METADATA_ONLY null extras, DO_NOT_STORE null extras and existing coordinator NotStored contract.
- WI-2: update existing app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt to exercise the actual service using a Robolectric fixture like DeferredPolicyTest, Allowed initial gate, explicit live collaborators, real extraction and real filter. Existing handwritten Elvis expressions do not prove wiring.
  1. Title Bank, no text/bigText, payment `Paid EUR 12.00 at Cafe` only in EXTRA_TEXT_LINES.
  2. Payment only in EXTRA_INFO_TEXT and separately EXTRA_SUMMARY_TEXT; null and blank bigText cases.
  3. Payment only in a real MessagingStyle message bundle.
  4. Normal top-level text/bigText still captures; repeated field values preserve existing extraction semantics.
  5. No amount anywhere rejects before coordinator; extended security/promotion text remains rejected for a finance package.
  6. Accepted coordinator input equals extracted combinedBody. Reverting only the service's fourth argument must fail these regressions.
- Named existing tests were inspected. Tests were NOT RUN in this spec session.

## Validation
- After privacy-security-guardian and reviewer-strict pass, validation-runner alone runs scripts/validation-runner.ps1 with `-Action Start -Profile targeted-unit-test -TestFilter '*NotificationCaptureServiceDeferredPolicyTest'`, then the same profile for `*NotificationCaptureServiceFallbackTest`, serially.
- Next use `-Action Start -Profile compile`; coder never invokes Gradle directly. Obtain expensive-run authorization where AGENTS.md requires it.
- Check existing runs first; poll RUNNING by RunId, never rerun. Report profile/filter, exit code, result.json and log paths. Missing/stale/timeout/infra-error evidence is not PASS.

## Blast-radius fence
- ALLOWED: app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt and the two full test paths listed above.
- READ ONLY: NotificationCaptureGate.kt, NotificationIntakeCoordinator.kt, NotificationTextParts.kt, NotificationFilter.kt, NotificationPrivacyGate.kt, DiagnosticReasonCode.kt.
- FORBIDDEN: intake scheduling/recovery (CL-02); NotificationIntakeWorker.kt and WorkerExecutionGuard.kt failure/retry handling (CL-03); currency/parsing (CL-05); architecture documentation correction (CL-06); CompositePrivacyGate.kt cancellation conversion (CL-18); restore/barrier internals (CL-19). No schema, worker or global logging cleanup.

## Review gate
- privacy-security-guardian plus reviewer-strict: verify consent → fresh master toggle → fresh blocked-package ordering, fail-closed exits before extraction/handoff, cancellation propagation, unchanged storage contracts, safe diagnostics, and real service wiring regressions.
- Verify combinedBody feeds the live filter without changing downstream parser/dedupe/worker policy. This document claims no implementation, review or validation gate passed.

## Privacy constraints
- PER-SITE DiagnosticReasonCode constants:
  - Initial TemporarilyUnavailable event: UNKNOWN_ERROR (existing nonterminal event); gateReason only the existing capture-block enum name.
  - Deferred capability non-Allowed/error/local timeout: PRIVACY_DENIED, terminal DROPPED.
  - Fresh disabled toggle: PRIVACY_DENIED, terminal DROPPED.
  - Settings read failure/local timeout: DEFERRED_STORAGE_POLICY_UNAVAILABLE, terminal DROPPED.
  - Package blocked/query error/local timeout: BLOCKED_PACKAGE, terminal DROPPED.
  - Parent cancellation: CAPTURE_CANCELLED, terminal CANCELLED, then rethrow.
  - Live filter rejection: FILTER_REJECTED; existing enum-only filterReason.
- These constants exist in domain/diagnostics/DiagnosticReasonCode.kt (≈L6-65). GATE_NOT_READY is a capture-block enum, not a DiagnosticReasonCode. No new diagnostic enum is required.
- Never write raw notification content, extras, financial values, raw package/key identifiers, exception messages or stack traces into logs/diagnostics/UI errors. Use existing SafeEventMetadata hashing. The separate authorized STORE_RAW intake contract remains; unresolved/denied authorization permits no payload persistence in any mode.
- EventMetadataSanitizer is FALLBACK for bounded text only — deletion beats redaction; these sites need no free-form text.

## Out of scope
- CA-P-01-002: CL-02; CA-P-01-003/005: CL-03; CA-P-01-006: CL-05; CA-P-01-007: CL-06.
- CA-P08-001: CL-18. Local code must propagate thrown cancellation; fixing a composite that already converted it is separate work.

