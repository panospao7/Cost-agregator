# P-01 — Notification capture: direct static audit

Date: 2026-09-21 (UTC)
Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`
Provenance: **direct astra session**; primary/direct cell auditor, as explicitly requested by the user.
Cell: **P-01 — Notification capture**
Mode: AUDIT, static only. No production edits, builds, tests, or guard execution.
Disposition: primary cell source reading complete; **7 discovery findings, pending independent Phase 2 verification**. This is not a verification verdict or a campaign completion claim.
Counts: **P0: 1; P1: 0; P2: 5; P3: 1**.

## Pin and scope evidence

Both initial and closing source checks found HEAD at the exact requested pin. `git rev-parse --short HEAD` returned `37601232`; full HEAD returned the SHA above. `git diff --stat 37601232..HEAD -- app config scripts` was empty. The additional closing `git diff --stat 37601232 -- app config scripts` was also empty, covering production working-tree edits. Existing documentation and agent-configuration drift was accepted as instructed. All source line references below therefore refer to the pin, not a different worktree revision.

Read the governing prompt's sections 2 and 4 first. Read the P-01 row of `docs/architecture/COVERAGE_MATRIX.md:25`, then `LEGAL_PATHS.md` sections Notification Capture (838–895), Expense Mutations (11–121), Categorization / Merchant Learning (755–783), and Workers / Background Jobs (471–520). Read the owning entries in `CODEBASE_SEGMENTS.md` (segments 3, 6, 11, 28) and the relevant notification/parser inventory entries. Selected engine rows were MerchantNormalizer, CategorizationEngine, PrivacyGate/CloudPayloadPolicy, and WorkerExecutionGuard (`ENGINE_INTERACTION_MAP.md:15–20,36`); the large map was not loaded wholesale.

Baseline inputs inspected: `REVALIDATED_ISSUE_REGISTRY_2026-09-07.md`, notification/worker rows in `FRESH_AUDIT_FINDINGS_2026-09-06.md`, relevant `WAVE-1-STATUS.md` entries, `RP-00-DECISIONS-RECORDED.md`, and the relevant RP-10/RP-16 remediation contracts. D1–D8 and later bank-specific currency decisions were not treated as authorization to change notification behavior. Accepted hash-format debt NEW-P1-011 is excluded.

The requested directory actually contains 17 Kotlin files, including the coordinator; the service and worker bring the explicit primary set to **19 files**. Parser registry and review queue live outside that directory, so those and the processing pipeline were also read. This is a capture-cell audit with traced downstream boundaries, not an exhaustive audit of every parser implementation, AI provider, shared engine, or Room table.

## Findings

### CA-P-01-001

ID: CA-P-01-001
Title: Deferred capture persists notification content without resolving capture consent or the package block
Defect class: 9 — Privacy
Severity: P0
Evidence:
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureGate.kt`, `decide`, 184–220: failure/timeout of settings self-heal returns TemporarilyUnavailable before the master-toggle, full privacy, and package checks at 223–281.
- `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`, `captureNotification`, 439–527: the deferred branch reads settings again at 461–463, then extracts and forwards content at 495–524. It never tests the returned `notificationCaptureEnabled`, calls the full privacy gate, or resolves `blockedPackageDao` in this branch.
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt`, `captureForRetry`, 302–319, 336–395, 402–430: only DO_NOT_STORE prevents storage; STORE_RAW writes title/text/body/extras directly into the intake row.
- `app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt`, `doWork`, 55–60, 116–127, 188–248, and `isNotificationCaptureAllowed`, 429–434: the later check is global capture consent, not the package block.
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt`, `check`, 19–36: even the NOTIFICATION_PACKAGE_ALLOWLIST capability checks only the master toggle, with no blocked-package lookup.
Impact path: listener arrives while the initial settings read times out → later settings read succeeds with capture disabled but STORE_RAW retained → content is durably stored despite disabled capture. Eventual worker cleanup cannot undo that unauthorized persistence. Alternatively, global capture is enabled but this source package is blocked: deferred intake is stored and the worker can parse it into a review/expense because no later package-block check exists.
Caller trace: Android manifest service registration → `onNotificationPosted` (service 350–352), or manual refresh (891–898) → `captureNotification` → TemporarilyUnavailable branch → `captureForRetry` → `NotificationIntakeDao.insertOrIgnore` → worker → repository/pipeline.
Existing tests/guards: `NotificationCaptureServiceDeferredPolicyTest.kt:122–224` covers read failure and storage-mode forwarding, using a mocked gate; it does not cover a recovered disabled master toggle or a blocked package. `NotificationIntakeCoordinatorTest` covers DO_NOT_STORE/encryption/barriers, not consent. Worker privacy-denial tests exercise cleanup after storage. `WorkerGuardArchitectureGuardTest` checks guard invocation, not service-side consent before insert. None was run.
Cross-cell impact: I-01 worker/runtime; P-08 privacy; P-02 expense creation and review approval. This is unauthorized local persistence, not a demonstrated network disclosure.
Old-ID cross-refs: none for this consent/block bypass. FRESH-P1-002 concerns storage-mode preservation/DO_NOT_STORE and is related context, not this finding. RP-10's STORE_RAW support makes plaintext persistence possible in the deferred branch; this report does not relabel accepted storage-mode behavior itself as a defect.

### CA-P-01-002

ID: CA-P-01-002
Title: Canonical deferred fingerprints make the formerly refuted REPLACE cancellation reachable
Defect class: 15 — Fix-regression
Severity: P2
Evidence:
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt`, `captureForRetry`, 322–330, 384, 430–440: changed content or postTime under the same Android key now yields another independently inserted row.
- Same file/function, 470–490: every such row still uses `intake_<notificationKeyHash>` and `ExistingWorkPolicy.REPLACE`, with an initial five-second delay.
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeRecoveryScheduler.kt`, `recoverPending`, 35–75: recovery is a separate sweep, not part of replacement cancellation.
- `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`, `initialize`/`recoverPendingIntakeRows`, 58, 90–99; service `onListenerConnected`, 295–300: the only production sweep hooks found are startup and listener connection.
Impact path: two deferred captures with the same source notification key and different transaction content arrive before the first delayed worker runs → both inserts succeed → second enqueue cancels first work → first intake stays RECEIVED without its worker. If the first worker was already processing, cancellation can leave its claim PROCESSING until a later stale-claim recovery. No immediate recovery is scheduled by replacement. Expense/review creation for the first notification is delayed indefinitely absent another sweep; retention can later remove its payload.
Caller trace: `onNotificationPosted`/refresh → deferred service branch → `captureForRetry` → WorkManager replacement. Different keys are not affected by this specific collision; identical canonical content returns before enqueue.
Existing tests/guards: `NotificationIntakeCoordinatorTest.kt:268–314` pins canonical identity and live/deferred agreement, but mocks WorkManager with an immediately completed Operation. It does not exercise two distinct rows sharing the same unique work name. The intake unique index/CAS prevent duplicate execution of one row, not cancellation of another row's work. No tests or guards were run.
Cross-cell impact: I-01 scheduling/recovery; P-02 missing/delayed expense/review intake.
Old-ID cross-refs: **FRESH-P1-007, previously REFUTED — now regressed/reachable**; FRESH-P1-001 remediation is the trigger. Inspected commit `09680da5`: it replaces `DEFERRED_<keyHash>` identity with the canonical content fingerprint while retaining the key-derived work name. The old refutation depended explicitly on the second insert always conflicting; that premise is no longer true. This is not a restatement of the old unreachable scenario.

### CA-P-01-003

ID: CA-P-01-003
Title: An early retry refused by the new DAO backoff predicate completes its WorkManager chain successfully
Defect class: 15 — Fix-regression
Severity: P2
Evidence:
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/NotificationIntakeDao.kt`, `claimForProcessing`, 88–100: claim now requires `nowMs >= nextAttemptAt` and increments attempts on successful claim.
- `app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt`, `doWork`, 98–113: every zero-row claim returns normally; it does not distinguish backoff-not-due from terminal/already claimed.
- Same function, 257–269 and 360–371: failures schedule row backoff using the already incremented `claimedMeta.attempts + 1` and return a guard-mediated retry.
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeRetryPolicy.kt`, `backoffFor`, 11–20: attempt 2 is 120 seconds.
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt`, `capture`, 201–209: WorkManager starts with a 30-second exponential backoff.
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt`, `runGuardedWithContext`/`toWorkerResult`, 362–375, 68–73: normal block return becomes WorkManager success.
Impact path: first successful claim raises attempts 0→1; quick retryable processing failure sets nextAttemptAt to approximately start+120s; WorkManager retries after approximately 30s; DAO returns 0; worker returns success. The row remains FAILED_RETRYABLE, but that WorkManager chain stops. A later startup/listener sweep can rescue it; no timer in this path schedules the row for its due time. The same mismatch can recur after rescue.
Caller trace: live or deferred capture → coordinator enqueue → worker → repository/pipeline retryable error → WorkManager retry → refused DAO claim → success.
Existing tests/guards: `WorkerClaimRepairDaoTest.kt` separately asserts refusal before the deadline and success afterward. `NotificationIntakeWorkerTimeoutTest.kt:134–194` stubs a successful claim and checks only the first retry; `already_claimed_row_records_no_work` at 792–850 stubs zero without a future-deadline scenario. These do not compose the real DAO with the retrying worker. Worker-guard presence cannot distinguish these claim outcomes. Not run.
Cross-cell impact: I-01; P-02 eventual completeness of captured transactions.
Old-ID cross-refs: **FRESH-P9-005 remediation regression**, not the original missing-predicate finding. `git show 1bca7e5c -- .../NotificationIntakeDao.kt` establishes that RP-16 added the predicate without updating the caller's zero-claim handling. The former failure burned attempts early; the new failure terminates the retry chain early.

### CA-P-01-004

ID: CA-P-01-004
Title: The live filter ignores the combined notification body that extraction prepares
Defect class: 12 — Error handling
Severity: P2
Evidence:
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTextParts.kt`, `extract`, 37–45, 47–89, 91–101: infoText, summaryText, textLines, and MessagingStyle messages are extracted into `combinedBody`; `bigText` remains the original big-text field.
- `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`, `captureNotification`, 531–556, 557–571: fingerprinting uses combinedBody, but filtering passes `parts.bigText` and returns on rejection.
- `app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt`, `decide`, 154–168, 297–307, 374–384: only the three supplied text fields contribute to the amount/signal decision.
- `app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt`, `doWork`, 188–194: deferred processing instead filters the stored combined body.
Impact path: a bank notification has title "Bank", no text/bigText, and textLines or infoText containing "Paid EUR 12.00 at Cafe" → extraction successfully obtains the transaction → live filter sees no amount and drops it before durable intake. The deferred path can process the same fields, so capture depends on gate readiness. The declared unified-extraction intent is not honored by the actual service call.
Caller trace: `onNotificationPosted`/refresh → Allowed gate → `NotificationTextParts.extract` → `NotificationFilter.decide` → early return; coordinator/DAO/parser are never reached for this notification.
Existing tests/guards: `NotificationCaptureServiceFallbackTest.kt:9–67` executes hand-written Elvis/blank fallback expressions inside each test; it invokes neither the service nor `NotificationTextParts.extract`. Those tests can pass with the faulty service argument unchanged. Filter unit tests receive explicit strings and do not prove this call-site wiring. Not run. This class-14 test weakness is supporting evidence, not a second finding.
Cross-cell impact: P-02 completeness of captured expenses; no claim against OCR/email extraction.
Old-ID cross-refs: source comments reference P1-P1-03 (MessagingStyle extraction) and P1-NEW-16 (safe combined-body extraction). Those extraction mechanisms exist; the new finding is the live filter call discarding their output. No matching open/accepted item was found in the consulted registry.

### CA-P-01-005

ID: CA-P-01-005
Title: Payload-load/decrypt failures occur after claim but outside the intake failure transition handler
Defect class: 12 — Error handling
Severity: P2
Evidence:
- `app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt`, `doWork`, 98–127, 157–172: claim occurs first, then metadata/payload reload and decryption.
- Same function, 238–248, 318–384: the local handler that repairs intake status starts only at repository processing. It does not enclose the previous payload load/decrypt.
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientPayloadCrypto.kt`, `decrypt`, 69–82: Keystore access, Base64/GCM processing, and framing can throw.
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt`, `runGuardedWithContext`, 420–436: unclassified errors produce a failed worker run, but no intake-table transition.
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/NotificationIntakeDao.kt`, `claimForProcessing`/`releaseStaleProcessing`, 96–100, 179–189: PROCESSING cannot be reclaimed until a separate stale sweep releases it.
Impact path: a persisted transient payload cannot decrypt (for example, its key is unavailable or ciphertext is invalid) → worker has already claimed the row → error escapes to the generic guard → WorkManager failure while intake remains PROCESSING, with no intake failure code or immediate payload purge. A transient database error in the same pre-handler interval instead yields a generic retry that sees PROCESSING and returns success. Recovery requires a later sweep after the ten-minute stale threshold; an earlier startup/reconnect sweep does not schedule a future stale release.
Caller trace: coordinator or recovery scheduler → `NotificationIntakeWorker.doWork` → `claimForProcessing` → `getPayloadForProcessing`/`crypto.decrypt` → outer guard only.
Existing tests/guards: `NotificationIntakeWorkerTimeoutTest` covers repository exceptions, privacy denial, and a checkpoint before decrypt; it does not establish a repaired intake state after decrypt throws. Crypto tests exercise decryption failures in isolation. Guard-presence checks are satisfied despite the missing domain-state transition. Not run.
Cross-cell impact: I-01 worker run vs domain queue-state consistency; I-02 intake retention/recovery.
Old-ID cross-refs: none. This does **not** re-report the fixed FRESH-P1-008 NUL-framing issue: it concerns the worker's exception boundary for any failing decrypt/load.

### CA-P-01-006

ID: CA-P-01-006
Title: Ambiguous kr amounts are classified as explicit SEK before home-currency resolution can run
Defect class: 7 — Money / currency
Severity: P2
Evidence:
- `app/src/main/java/com/yourname/expensetracker/domain/notification/money/NotificationMoneySignalDetector.kt`, currency definitions and `CurrencyDef`, 18–35, 149–154: `isoCodes` contains every symbol, including "kr"; SEK precedes NOK and DKK.
- Same file, `bestTransactionAmount`, 41–62: the supposed explicit-ISO pass matches "42 kr" and immediately returns SEK with EXPLICIT_ISO_CODE, confidence 0.95, ambiguous=false.
- Same function, 109–127: the intended kr home-currency/unresolved branch is reached only after that earlier return, so it cannot handle an ordinary positive "42 kr" input.
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`, `resolveCurrency`, 877–880, and `processInternal`, 293–302, 424–452, 474–488: the result feeds parser-fallback review currency and currency-aware duplicate checks.
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`, `approveReview`, 146–153, 235–259: approval adopts suggestedCurrency when no override is supplied.
Impact path: deterministic/AI parsing returns no transaction for a kr-denominated notification → fallback currency resolution runs with homeCurrency=NOK (or DKK) → detector returns SEK anyway → review suggests the wrong currency and duplicate checks search the wrong currency. Approval without a currency correction carries that suggestion into the lifecycle request. This is a review-path defect, not a claim that this detector directly auto-books all parsed notifications.
Caller trace: listener → intake worker (or DO_NOT_STORE synchronous path) → repository → pipeline parser-null branch → `resolveCurrency` → `bestTransactionAmount` → PendingReview → user approval.
Existing tests/guards: searched `app/src/test` for `NotificationMoneySignalDetector` and `bestTransactionAmount`; no direct tests were found. Existing oversized/signal pipeline tests do not establish this helper's kr branch ordering. No tests run.
Cross-cell impact: E-01 currency identity, P-02 review approval, analytics downstream of approved expense currency.
Old-ID cross-refs: none for the kr branch-shadowing defect. Pipeline comments reference P2-10, which moved fallback currency resolution into this detector; that is related history, not a request to change the accepted migration-pinned fingerprint or bank-specific D11 currency policy.

### CA-P-01-007

ID: CA-P-01-007
Title: RP-10 completion documentation describes a transient framing marker absent from the pinned code
Defect class: 11 — Data integrity (serialization-contract documentation drift)
Severity: P3
Evidence:
- `docs/analyses and debug master/remediation/RP-10-notification-capture.md`, section 10c status, 179–189: claims a 0xFF first-byte marker inside the ciphertext's plaintext frame.
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTransientPayloadCrypto.kt`, `frame`, 87–101: immediately emits the first field's 0/1 presence byte, with no marker.
- Same file, `decrypt`/`parseFrame`, 69–82, 105–124: tries strict presence/length parsing first and legacy parsing second; no marker dispatch.
Impact path: a future reader, migration author, or compatibility implementation follows the recorded on-disk format and expects a byte that this deployed writer never emits. Current writer/reader agree with each other; no current ciphertext corruption or privacy exploit is asserted here.
Caller trace: live/deferred non-raw capture → `crypto.encrypt` → intake storage → worker `crypto.decrypt`. The incorrect contract is in the remediation completion documentation, not an unwired production API.
Existing tests/guards: `NotificationTransientPayloadCryptoTest.kt:74–169` asserts current round trips and legacy fallback; 175–205 covers selected invalid plaintext. It does not assert the documented marker. These tests were inspected, not run. Valid new frames preserve embedded NUL and null/empty distinctions; the original NUL bug is not re-reported.
Cross-cell impact: I-02 serialization/migration consumers; campaign reconciliation/documentation.
Old-ID cross-refs: FRESH-P1-008 (fixed implementation, inaccurate completion description); no new instance of the old delimiter bug.

## End-to-end path and invariant checks

1. Manifest registers `NotificationCaptureService` as the notification listener. Both Android callbacks and refresh converge on `captureNotification`. Fast restore/shutdown checks precede the coroutine; the loaded gate checks global consent and packages before live extraction. Deferred gating diverges as CA-P-01-001 documents.
2. The live memory deduper uses monotonic nanoseconds, a synchronized check/insert, and a 1000-entry bound. Durable raw/intake fingerprinting shares `RawNotificationFingerprint.compute`; null/empty and delimiter format remain unchanged by policy. New deferred/live rows share that canonical identity. No second independent expense insertion was assumed merely because two worker names exist: the DAO claim CAS and later expense constraints matter.
3. Coordinator writes have the canonical barrier calls; deferred legacy terminalization and insertion execute in `DomainTransactionRunner`, whose Room implementation uses `database.withTransaction`. The barrier implementation itself performs a mode check; it is not a mutex spanning the operation. Global restore exclusion belongs to the runtime/restore cells, and this audit does not infer atomic exclusion from the name `runWrite`.
4. Worker enters `WorkerExecutionGuard` with NOTIFICATION_CAPTURE and no posting-permission requirement. Metadata precedes payload access. Denied/FailClosed consent takes the separate cleanup guard with no required capabilities, permitting deletion. Explicit processing cancellation is rethrown. The failure-boundary and scheduling counterexamples are CA-P-01-003/005.
5. `NotificationRepository.processAndSave` delegates to `NotificationProcessingPipeline`. Registry routes specific parser → generic parser → policy-gated AI fallback. The pipeline checks raw fingerprint before expensive parsing; its write transaction repeats the insert-conflict check. Parsed branches run canonical expense and pending-review dedupe inside the transaction. Currency and type enter these comparisons; fallback currency has the concrete CA-P-01-006 exception.
6. Auto-accept calls `TransactionLifecycleCoordinator.createExpenseDbOnlyV2` (pipeline 1311); coordinator's `createExpenseMutation` inserts via `ExpenseDao.insertAtomic`, writes CREATED, and writes provenance in the same transaction (coordinator 628–704). Pipeline relevance/source counts/markProcessed are within its outer transaction. Planner returns actions rather than dispatching them from this DB-only call.
7. Review insertion and markProcessed share the pipeline transaction. Approval uses PENDING→PROCESSING CAS, lifecycle creation, source promotion, correction insert, and final review status in one transaction (`ReviewQueueRepository` 219–363). Category/merchant bulk updates and approval pre-read were inspected; this does not prove every cross-screen edit race absent. Rejection also uses a status CAS before counter/correction updates.
8. `runParsedPostCommitActions` is reached after the outer transaction returns (pipeline 645–657; 1624–1675). It dispatches lifecycle actions, then transfer analytics/training and bounded recommendation/subscription work. Review approval dispatches at 397–421 after commit. Read `TransactionSideEffectPlanner.planCreated` and `PostCommitActionRunnerImpl.run`: actions are in-memory, and per-action failures are best effort; no exactly-once durable outbox guarantee is claimed by this audit.

## All 15 defect classes: coverage record

| Class | Concrete checks and disposition |
| --- | --- |
| 1 Legal path | Traced auto-accept, approveReview, and manual relevance recovery to createExpenseDbOnlyV2, insertAtomic, CREATED, provenance, and action planning. Deprecated deleteAll remains outside the main intake path and is already tracked under the dead-API/D4 baseline. No new lifecycle bypass asserted. |
| 2 Barrier | Read coordinator, recovery, repairer, worker checkpoints, pipeline write scopes, review scopes, and actual DatabaseWriteBarrier implementation. Presence is not treated as proof of global exclusion. Existing universal guard/barrier debt not reissued. |
| 3 Atomicity / TOCTOU | Checked canonical conflict, legacy transition transaction, claim CAS, raw insert/processed atomicity, review CAS/approval transaction, and event insertion. The claim-to-error-handler gap is CA-P-01-005. Shared-engine race claims require their owning cell's context. |
| 4 Idempotency / duplicates | Followed memory vs intake vs raw vs expense vs pending-review identities, retry outcomes, unique work naming, and recovery. CA-P-01-002/003 are concrete counterexamples; NEW-P1-011 excluded. |
| 5 Cancellation | Read gate rethrows, service NonCancellable capture region/onDestroy, crypto/deferred catches, worker timeout/cancellation ordering, source-link helper catch, repairer's broad catch, and review batch catches. Existing pre-gate cancellation gap FRESH-P1-004 is not new. Broader broad-catch reconciliation belongs with U-CANCEL-01/FRESH-U-002; no claim that all cancellation sites are clean or that cancelled Room writes still execute. |
| 6 Side-effect timing | Verified outer commit precedes action runner in pipeline/review; inspected asynchronous recommendation/subscription launches and source-link diagnostic deferral. Best-effort actions are not a durable exactly-once mechanism. No new pre-commit expense side-effect finding asserted. |
| 7 Money / currency | Read all NotificationMoneySignalDetector code, fallback candidate/routing code, ParsedTransaction validation, typed/currency-aware dedupe arguments and approval currency resolution. CA-P-01-006. No aggregate-money engine audit is implied. |
| 8 Time | Monotonic dedupe arithmetic, injected capture/retry timestamps, postTime fingerprint input, stale threshold, retry ladder, event-date fallback, exclusive dedupe window helper use. Retry timing counterexample CA-P-01-003. |
| 9 Privacy | Checked live/deferred consent, blocked packages, all storage modes, extras filtering, transient crypto, payload-load order, cleanup, review-text sanitizer, safe diagnostic metadata. CA-P-01-001; no repeat of accepted capture-time storage context. |
| 10 Worker hygiene | Guard request, lease/checkpoint/result mapping, no POST_NOTIFICATIONS dependency for processing, metadata counters, retry/terminal/purge flows, recovery reachability. CA-P-01-003/005. Guard logging does not repair domain queue state automatically. |
| 11 Data integrity | Unique intake index, raw/pending identity lookup/upsert, approved expense raw provenance, terminal transitions, frame compatibility. CA-P-01-007 is documentation drift only. No schema mutation performed. |
| 12 Error handling | Walked service branch returns, deferred failures, enqueue await, decrypt/load exceptions, filter inputs, repository errors, parser fallback, terminal purge. CA-P-01-004/005. Existing enqueue/recovery debt not republished. |
| 13 Wiring / dead code | Located manifest, injection binding, both service entry points, startup and listener recovery hooks, repository/pipeline calls, review UI callers, helper caller count. Guard default specVersion=null for this dynamic worker is intentional. |
| 14 Test correctness | Inspected focused tests described per finding. Fallback test duplicates expressions rather than calling production; coordinator WorkManager mocks conceal replacement behavior; real DAO predicate tests do not exercise worker result mapping. Known MockK/hang ledger is not a new finding. |
| 15 Fix-regression | Inspected actual commit diffs 09680da5 and 1bca7e5c; CA-P-01-002/003 have changed premises demonstrated by source. Monotonic dedupe, extra-key additions, and valid new crypto framing remain present. |

## Known-issue exclusion and fixed-area verification

- **FRESH-P1-001/002:** canonical new-row fingerprints and the resolved storage-mode snapshot exist. The remaining legacy transition differs from parts of the RP-10 design (live capture does not run its legacy transaction; deferred capture does not compare legacy payload identity). Those are recorded here against the old work, not new CA findings. CA-P-01-002 instead identifies the newly reachable scheduling consequence of changing the dedupe namespace.
- **FRESH-P1-003 / REVAL-4:** coordinator now awaits enqueue and app startup calls recovery. Recovery still has finite sweeps; no generalized new "missing startup hook" finding is made. Synchronous enqueue exceptions occur outside the await try, but that belongs to the existing enqueue-failure contract, not a duplicate new issue.
- **FRESH-P1-004:** cancellation handling remains narrower than the original problem: the gate/dedupe/filter interval is still outside the later catch. Kept under the known ID; not counted as new or claimed fixed.
- **FRESH-P1-005:** the four named sensitive Android extras keys are present and filtering is case-insensitive. STORE_RAW policy itself is not a defect.
- **FRESH-P1-006:** the deduper reads MonotonicTimeProvider.nowNanos and converts configured milliseconds to nanoseconds. No wall-clock TTL regression found.
- **FRESH-P1-008:** all five fields use explicit presence plus byte length; new NUL-containing fields do not shift positions. Legacy fallback remains intentional. CA-P-01-007 flags only the inaccurate marker description.
- **NEW-P1-011:** preserved, accepted migration-pinned fingerprint format. No hash cleanup or delimiter redesign proposed.
- **FRESH-P9-005:** old missing deadline predicate is gone; CA-P-01-003 is its newly introduced caller-level failure mode.

## Read ledger and limits

Full primary-file reads, under `app/src/main/java/com/yourname/expensetracker/`:

- `service/NotificationCaptureService.kt` (1–1011).
- `worker/NotificationIntakeWorker.kt` (1–488).
- `domain/notification/capture/`: `CaptureSource.kt`, `NotificationCaptureDecision.kt`, `NotificationCaptureDeduper.kt`, `NotificationCaptureGate.kt`, `NotificationIntakeCaptureResult.kt`, `NotificationIntakeCoordinator.kt`, `NotificationIntakePayloadRepairer.kt`, `NotificationIntakeRecoveryScheduler.kt`, `NotificationIntakeRetryPolicy.kt`, `NotificationTextParts.kt`, `NotificationTransientKeyProvider.kt`, `NotificationTransientPayloadCrypto.kt`.
- `domain/notification/`: `NotificationPersistenceContext.kt`, `NotificationPipelineOutcome.kt`, `RawNotificationFingerprint.kt`, `RawNotificationInsertResult.kt`; `money/NotificationMoneySignalDetector.kt`.

Full directly traced supporting reads: `data/repository/NotificationRepository.kt`, `NotificationProcessingPipeline.kt` (1–1825), `ReviewQueueRepository.kt` (1–792); `domain/parser/AppParserRegistry.kt` (1–370); `service/NotificationFilter.kt`; `data/database/dao/NotificationIntakeDao.kt` (1–307); `data/database/entity/NotificationIntakeEntity.kt`; `data/backup/DatabaseWriteBarrier.kt`; `domain/privacy/NotificationPrivacyGate.kt`; `data/database/RoomDomainTransactionRunner.kt`.

Targeted boundary reads/searches (not claims of whole-file audit): coordinator create/insert/event/action sections; ExpenseDao insert and identity lookup declarations; PendingReviewDao upsert/status CAS; RawNotificationDao fingerprint declarations; WorkerExecutionGuard request/result, privacy, checkpoint, execution/exception/terminal handling; startup recovery; manifest and SecurityModule binding; review/debug UI callers; TransactionSideEffectPlanner.planCreated and PostCommitActionRunnerImpl execution flow. Individual specific/generic parser algorithms and downstream analytics/categorization implementations were not exhaustively reread.

Focused test-source inspection: service deferred-policy, fallback and cleanup surfaces; coordinator canonical/storage-mode/barrier tests; enqueue-failure/retry-policy surfaces; crypto round-trip/legacy/invalid-frame cases; worker timeout/privacy/retry/claim-counter cases; WorkerClaimRepairDaoTest; worker-guard presence and restricted expense-mutation guard definitions. Tests not read in full are not claimed as fully audited. Static guard references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-11 (workers), FG-13 (database/barrier), FG-14 (events/transactions); no guard was executed or declared passing.

Validation: **NOT RUN**, as required. All examples above are source-derived execution traces, not runtime reproductions. Severity is calibrated to the stated production path: P0 is unauthorized persistence; recoverable scheduling/review defects are P2; incorrect format documentation is P3. Independent verification may refute or recalibrate any discovery finding.

## Provenance

- Author: **direct astra session**, direct cell auditor for P-01, at the user's explicit request.
- Agents invoked: **none**. No delegated review, verifier result, session ID, or rollout ID is fabricated. The direct-session instruction supersedes the campaign's usual orchestrator delegation/writer arrangement for this artifact.
- Source pin: `37601232b9778170c57a656a245b199ab6d7d965`; checked at entry and after source inspection.
- Audit timestamp: 2026-09-21T18:08:45Z (UTC clock read during report preparation).
- Writes authorized/performed: this report and one append-only JOURNAL.md entry in the campaign folder. No registry, matrix, state, production, test, guard, or configuration edits.
- Phase: static discovery only; independent finding verification remains pending.
