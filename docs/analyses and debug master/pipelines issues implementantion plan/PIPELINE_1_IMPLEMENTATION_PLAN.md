# Pipeline 1 — Notification Capture: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 1 — Notification Capture  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 1 — Notification Capture
Verdict: RED
Summary:
- 5 old issues fully FIXED, 1 PARTIAL (P1-P1-07 loss window)
- 1 issue FIXED by universal (NEW-P1-001 via U-PR1)
- 1 issue BLOCKED by universal (NEW-P1-009 privacy TOCTOU → U-PR5)
- 15 pipeline-local issues remain OPEN (2 P1, 9 P2, 4 P3)
- Major regression: non-raw storage modes produce PAYLOAD_UNAVAILABLE_PRIVACY
- processMutex serializes all processing — throughput bottleneck
- Filter too broad — deposits blocked, "failed" keyword over-matches
- Settings observer dies permanently on exception — privacy regression risk
```

---

## 2. Sources Reviewed

**Docs:**
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/PIPELINE_1_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/new debugging session/pipeline 1.md`

**Source files:**
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt`
- `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureDeduper.kt`

**Universal fix plans:**
- `docs/analyses and debug master/universal issues implementation plan/U-PR1-CANCELLATION-SAFETY.md`
- `docs/analyses and debug master/universal issues implementation plan/U-PR5-PRIVACY-CONTRACT.md`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 1 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | Fixes NEW-P1-001 — outer catch in captureNotification | No | ✅ Fixed |
| U-PR2 (TOCTOU) | No direct impact — Pipeline 1 doesn't use TransactionLifecycleCoordinator update paths | No | N/A |
| U-PR3 (Money/Currency) | No direct impact — Pipeline 1 doesn't do currency aggregation | No | N/A |
| U-PR4 (Barrier/Maintenance) | Pipeline 1 already has `writeBarrier.checkWritesAllowed()` | No | ✅ Compatible |
| U-PR5 (Privacy/RawStorageMode) | **Critical** — Pipeline 1 non-raw modes broken; needs adapter for per-source-type policy | Yes — adapter PR | ⏳ Blocked |
| U-PR6 (Worker Guard) | NotificationIntakeWorker should adopt guard contract | Yes — adapter PR | 🔴 Open |
| U-PR7 (TimeProvider) | Pipeline 1 already uses TimeProvider | No | ✅ Compatible |
| U-PR8 (Side Effects) | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P1-P1-01 | ✅ FIXED | None | None |
| P1-P1-02 | ✅ FIXED | None | None |
| P1-P1-03 | ✅ FIXED | None | None |
| P1-P1-05 | ✅ FIXED | None | None |
| P1-P1-06 | ✅ FIXED | U-PR4 compatible | None |
| P1-P1-07 | ⚠ PARTIAL | U-PR5 (privacy modes) | Service-scope cancellation window; non-raw mode regression |
| NEW-P1-001 | ✅ FIXED | U-PR1 | None |
| NEW-P1-002 | ✅ FIXED | None | Diagnostic emit deferred to post-commit (P1-PR2 landed) |
| NEW-P1-003 | 🔴 OPEN | None | Remove dead `acceptingNewWork` code |
| NEW-P1-004 | 🔴 OPEN | None | Handle null launch return |
| NEW-P1-005 | 🔴 OPEN | None | Fix deposit filter logic |
| NEW-P1-006 | 🔴 OPEN | None | Narrow "failed" keyword matching |
| NEW-P1-007 | 🔴 OPEN | None | Add blocking warmUp or retry on TemporarilyUnavailable |
| NEW-P1-008 | 🔴 OPEN | None | Replace Mutex with Semaphore or per-notification concurrency |
| NEW-P1-009 | 🔴 BLOCKED | U-PR5 | Privacy TOCTOU — wait for RawContentPolicy |
| NEW-P1-010 | 🔴 OPEN | None | Move markProcessed inside transaction |
| NEW-P1-011 | 🔴 OPEN | None | Deduplicate SHA-256 implementations |
| NEW-P1-012 | 🔴 OPEN | None | Remove unused postTime parameter |
| NEW-P1-013 | 🔴 OPEN | None | Pass structured fields to filter, not concatenated body |
| NEW-P1-014 | 🔴 OPEN | None | Wire cleanupExpired to periodic maintenance |
| NEW-P1-015 | ✅ FIXED | None | IllegalStateException removed; review always created (P1-PR2 landed) |
| NEW-P1-016 | 🔴 OPEN | None | Use case-insensitive/camelCase-aware key filtering |
| NEW-P1-017 | 🔴 OPEN | None | Add supervisor/restart to settings observer |



---

## 5. New Issues / Regressions

### Regression: Non-raw storage modes no longer process notifications

**Severity:** P1  
**Evidence:** `NotificationIntakeCoordinator` stores title/text/body only when `rawStorageMode == STORE_RAW`. `NotificationIntakeWorker` returns `PAYLOAD_UNAVAILABLE_PRIVACY` when all text fields are null.  
**Impact:** Users with STORE_REDACTED, METADATA_ONLY, or DO_NOT_STORE stop getting notification-created expenses.  
**Root cause:** Processing moved to async worker but worker has no ephemeral raw payload unless stored durably.  
**Relation to universal:** Blocked by U-PR5 (RawStorageMode contract). The fix requires the shared `RawContentPolicy` to define per-source-type transient storage semantics.  
**Temporary mitigation:** For DO_NOT_STORE mode, process synchronously in service using ephemeral raw text (no async recovery). Document that full durability requires STORE_RAW or encrypted transient queue.

### Regression risk: Settings observer permanent death

**Severity:** P2  
**Evidence:** `NotificationCaptureGate.startObservers()` launches two coroutines that collect flows. If either throws an exception, the `catch` block logs but does NOT restart the observer. The observer dies permanently.  
**Impact:** If privacy settings change after observer death, the gate uses stale cached values. Could allow capture when user disabled it, or block capture when user enabled it.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| P1-P1-07 (remainder) | P1 | Service-scope cancellation window before intake insert | Intake durability | P1-PR1 |
| NEW-P1-005 | P2 | Filter blocks ALL deposit notifications | Filter logic | P1-PR3 |
| NEW-P1-006 | P2 | "failed" keyword deny overly broad | Filter logic | P1-PR3 |
| NEW-P1-013 | P2 | combinedBody as bigText — over-inclusive matching | Filter logic | P1-PR3 |
| NEW-P1-007 | P2 | Race between warmUp() and first notification | Gate lifecycle | P1-PR4 |
| NEW-P1-017 | P2 | Settings observer dies permanently on exception | Gate lifecycle | P1-PR4 |
| NEW-P1-008 | P2 | processMutex serializes ALL processing | Performance | P1-PR5 |
| NEW-P1-010 | P2 | markProcessed outside pipeline transaction | Atomicity | P1-PR5 |
| NEW-P1-003 | P3 | Dead `acceptingNewWork` code | Cleanup | P1-PR6 |
| NEW-P1-004 | P3 | Silently dropped events on null launch | Cleanup | P1-PR6 |
| NEW-P1-011 | P3 | Redundant SHA-256 implementations | Cleanup | P1-PR6 |
| NEW-P1-012 | P3 | Unused postTime parameter | Cleanup | P1-PR6 |
| NEW-P1-014 | P3 | Deduper cleanupExpired never called | Cleanup | P1-PR6 |
| NEW-P1-016 | P3 | Sensitive key filtering exact match only | Cleanup | P1-PR6 |
| NEW-P1-009 | P2 | Double privacy settings fetch — TOCTOU | Privacy | Blocked by U-PR5 |



---

## 7. PR Organization

### P1-PR1 — Intake Durability & Non-Raw Mode Fix

```
PR name: fix(p1): intake durability — NonCancellable insert + non-raw mode processing
Goal: Close the service-scope cancellation window and fix non-raw storage mode regression
Issues fixed: P1-P1-07 (remainder)
Universal dependencies: U-PR5 (for full privacy-mode semantics; can land partial fix now)
Files likely touched:
  - NotificationCaptureService.kt
  - NotificationIntakeCoordinator.kt
  - NotificationIntakeWorker.kt
Implementation steps:
  1. Wrap intakeCoordinator.capture() in withContext(NonCancellable) for the insert+enqueue critical section
  2. For STORE_REDACTED/METADATA_ONLY: store encrypted transient payload in intake entity (purge after processing)
  3. For DO_NOT_STORE: process synchronously in service scope (no async recovery)
  4. Worker: remove write-barrier violation (don't call markRetryableFailure during blocked mode)
  5. Worker: enforce maxAttempts before processing
  6. Worker: classify TimeoutCancellationException vs system CancellationException
Tests:
  - service_destruction_does_not_lose_accepted_notification
  - store_redacted_mode_processes_notification_successfully
  - do_not_store_mode_processes_synchronously
  - worker_does_not_write_during_blocked_mode
  - worker_enforces_max_attempts
Docs: Update pipeline flow KDoc to document per-mode processing paths
Architecture guards: None new
Risks: Medium — changes critical intake path; needs careful testing of all 4 storage modes
Acceptance criteria:
  - All 4 RawStorageMode values produce correct pipeline outcomes (not PAYLOAD_UNAVAILABLE_PRIVACY for non-DO_NOT_STORE)
  - Service destruction after filter pass does not lose notification
  - Worker returns Result.retry() without DB write when barrier is active
```

### P1-PR2 — Transaction Side-Effect Isolation

```
PR name: fix(p1): move diagnostic emit and source-link I/O outside DB transaction
Goal: Prevent potential deadlock from I/O side effects inside Room transaction
Issues fixed: NEW-P1-002, NEW-P1-015
Universal dependencies: None
Files likely touched:
  - NotificationProcessingPipeline.kt
Implementation steps:
  1. Collect source-link write requests as data objects inside transaction
  2. Execute sourceLinkWriter.linkTarget() calls AFTER withTransaction block
  3. Collect diagnostic events as data objects inside transaction
  4. Execute diagnosticEmitter.emit() calls AFTER withTransaction block
  5. If transaction rolls back (IllegalStateException), do NOT emit orphaned diagnostics
  6. Use PostCommitActionRunner pattern for deferred source-link writes
Tests:
  - source_link_write_executes_after_transaction_commit
  - rolled_back_transaction_does_not_emit_diagnostic
  - source_link_failure_does_not_roll_back_main_transaction
Docs: Add KDoc noting post-commit side-effect pattern
Architecture guards: None new
Risks: Low — moves I/O later in the flow; no behavior change for happy path
Acceptance criteria:
  - No suspend calls to external services inside withTransaction blocks
  - Rolled-back transactions produce zero diagnostics/source-links
  - All existing pipeline tests pass unchanged
```

### P1-PR3 — Filter Correctness

```
PR name: fix(p1): narrow filter — deposit pass-through, keyword precision, structured input
Goal: Fix false-positive rejections and over-inclusive matching
Issues fixed: NEW-P1-005, NEW-P1-006, NEW-P1-013
Universal dependencies: None
Files likely touched:
  - NotificationFilter.kt
  - NotificationCaptureService.kt (caller passes structured fields)
Implementation steps:
  1. NEW-P1-005: Remove unconditional INCOMING_ONLY deny for deposit keywords; instead, allow deposits that have expense signals (e.g. "deposit fee", "deposit charged")
  2. NEW-P1-006: Change "failed" deny to require context — only deny when "failed" appears with auth/security context (e.g. "login failed", "transaction failed" without amount)
  3. NEW-P1-013: Pass title, text, bigText as separate parameters to filter decision logic; do NOT concatenate bigText (which duplicates title+text) into combined body for keyword matching
  4. Add unit tests for each edge case
Tests:
  - deposit_notification_with_fee_is_captured
  - merchant_named_failed_pizza_is_not_denied
  - bigText_duplication_does_not_cause_false_positive
  - security_failed_login_is_still_denied
Docs: Update NotificationFilter KDoc with deny-keyword semantics
Architecture guards: None new
Risks: Low — filter logic only; no DB/lifecycle changes
Acceptance criteria:
  - "Deposit fee €2.50" from finance app is captured
  - "Payment at Failed Pizza €15.00" is captured
  - "Login failed" from finance app is denied
  - Filter uses structured fields, not concatenated body
```

### P1-PR4 — Gate Lifecycle Hardening

```
PR name: fix(p1): gate warmUp race + observer restart on failure
Goal: Prevent cold-start drops and permanent observer death
Issues fixed: NEW-P1-007, NEW-P1-017
Universal dependencies: None
Files likely touched:
  - NotificationCaptureGate.kt
  - NotificationCaptureService.kt
Implementation steps:
  1. NEW-P1-007: Change decide() to return TemporarilyUnavailable with retry hint when warmUp incomplete AND self-heal timeout expires; caller (service) should re-enqueue via intake coordinator instead of dropping
  2. NEW-P1-017: Wrap observer collect loops in while(true) with exponential backoff on exception; use supervisorScope or SupervisorJob so one observer failure doesn't kill the other
  3. Add max-retry limit for observer restarts (e.g. 5 attempts) before entering permanent Error state
Tests:
  - cold_start_notification_retried_after_warmup_completes
  - observer_restarts_after_transient_exception
  - observer_enters_error_after_max_retries
Docs: Update gate KDoc with retry/restart semantics
Architecture guards: None new
Risks: Low — defensive improvements; no behavior change for warm steady-state
Acceptance criteria:
  - No notifications permanently dropped during cold start
  - Settings observer survives transient DB exceptions
  - Observer stops retrying after configured max attempts
```

### P1-PR5 — Pipeline Concurrency & Atomicity

```
PR name: fix(p1): replace processMutex with bounded concurrency + atomic markProcessed
Goal: Improve throughput and fix atomicity gap
Issues fixed: NEW-P1-008, NEW-P1-010
Universal dependencies: None
Files likely touched:
  - NotificationProcessingPipeline.kt
  - NotificationRepository.kt
Implementation steps:
  1. NEW-P1-008: Replace single Mutex with Semaphore(MAX_CONCURRENT_PIPELINE_JOBS) where MAX=3-4; each notification processes independently; classifier.initialize() called once per batch or with its own mutex
  2. NEW-P1-010: Move markProcessed(rawId) inside the pipeline's withTransaction block so crash between commit and markProcessed cannot cause duplicate processing
  3. Ensure fingerprint dedup check remains atomic (it already uses DAO-level check)
Tests:
  - concurrent_notifications_process_in_parallel
  - crash_after_commit_does_not_reprocess_notification
  - classifier_initialization_is_thread_safe
Docs: Update pipeline KDoc with concurrency model
Architecture guards: None new
Risks: Medium — concurrency change; needs careful testing for race conditions in classifier/router
Acceptance criteria:
  - Burst of 10 notifications processes in <3x single-notification time (not 10x)
  - No duplicate expenses created under concurrent processing
  - markProcessed is atomic with pipeline outcome
```

### P1-PR6 — Dead Code & Cleanup

```
PR name: chore(p1): remove dead code, wire deduper cleanup, fix minor issues
Goal: Code hygiene and minor correctness fixes
Issues fixed: NEW-P1-003, NEW-P1-004, NEW-P1-011, NEW-P1-012, NEW-P1-014, NEW-P1-016
Universal dependencies: None
Files likely touched:
  - NotificationCaptureService.kt
  - NotificationCaptureDeduper.kt
  - NotificationProcessingPipeline.kt (or wherever SHA-256 is duplicated)
Implementation steps:
  1. NEW-P1-003: Remove `workTracker.acceptingNewWork` field and all references (never set to false)
  2. NEW-P1-004: Add null-check on launch return; log warning if coroutine not started
  3. NEW-P1-011: Extract shared SHA-256 utility; remove duplicate implementations
  4. NEW-P1-012: Remove unused `postTime` parameter from `computeDedupeKey`
  5. NEW-P1-014: Wire `NotificationCaptureDeduper.cleanupExpired()` to periodic maintenance (e.g. call from NotificationIntakeWorker after batch, or from a scheduled cleanup)
  6. NEW-P1-016: Change sensitive key filtering to use case-insensitive contains or regex that handles camelCase (e.g. "apiKey", "API_KEY", "api_key" all match)
Tests:
  - deduper_cleanup_removes_expired_entries
  - sensitive_key_filter_catches_camelCase_variants
Docs: None required
Architecture guards: None new
Risks: Very low — cleanup only
Acceptance criteria:
  - No dead code paths remain
  - Deduper memory bounded by periodic cleanup
  - Sensitive key filtering catches all common naming conventions
```



---

## 8. Detailed Implementation Plan

### P1-PR1 Step-by-Step

1. **Open** `NotificationCaptureService.kt`
   - Find the `workTracker.launch(serviceScope)` block that calls `intakeCoordinator.capture()`
   - Wrap the `intakeCoordinator.capture()` + `workManager.enqueueUniqueWork()` section in `withContext(NonCancellable)`
   - This ensures the insert+enqueue completes even if service is destroyed

2. **Open** `NotificationIntakeCoordinator.kt`
   - Find the payload storage logic: `title = if (rawStorageMode == STORE_RAW) title else null`
   - Change to:
     - `STORE_RAW`: store raw text as-is
     - `STORE_REDACTED`: store encrypted transient payload (use `EncryptedTransientPayload` helper)
     - `METADATA_ONLY`: store encrypted transient payload (metadata-only audit after processing)
     - `DO_NOT_STORE`: set a flag `requiresSynchronousProcessing = true`; do NOT insert intake row
   - For DO_NOT_STORE: call `repository.processAndSave()` directly in the service scope (ephemeral processing)

3. **Open** `NotificationIntakeWorker.kt`
   - Remove the `markRetryableFailure()` call when write barrier is active; just return `Result.retry()`
   - Add `if (current.attempts >= current.maxAttempts)` check before processing; mark final failure
   - For encrypted transient payloads: decrypt before processing, purge raw after terminal outcome
   - Reclassify `CancellationException` handling (already fixed by U-PR1, verify)

4. **Write tests** in `NotificationIntakeWorkerTest.kt` and `NotificationIntakeCoordinatorTest.kt`

### P1-PR2 Step-by-Step

1. **Open** `NotificationProcessingPipeline.kt`
   - Find all calls to `writeNotificationDedupeSourceLink()` inside `database.withTransaction { }`
   - Replace with: collect `SourceLinkRequest(rawId, matchType, correlationId)` into a local list
   - After `withTransaction` block completes, iterate the list and call `writeNotificationDedupeSourceLink()`
   - Same for `diagnosticEmitter.emit()` calls inside transactions — collect as `DiagnosticRequest` objects

2. **Handle rollback case:**
   - If `withTransaction` throws (including the `IllegalStateException` from source-link fatal failure), do NOT execute deferred source-link writes
   - Remove the `throw IllegalStateException(...)` inside transaction for source-link failures; instead, collect the failure and handle post-commit

3. **Verify** that `pendingReviewSourceLinkService.linkSourcesForReview()` is also moved outside transaction (it calls `sourceLinkWriter` internally)

### P1-PR3 Step-by-Step

1. **Open** `NotificationFilter.kt`
   - Find the INCOMING_ONLY / deposit deny logic
   - Change: only deny "deposit"/"κατάθεση" when NO expense signal keywords are present in the same notification
   - Find "failed" in deny keywords or deny logic
   - Change: require "failed" to co-occur with auth/security context words (login, authentication, verification)

2. **Open** `NotificationCaptureService.kt`
   - Find where `shouldCapture(packageName, title, text, bigText)` is called
   - Ensure `bigText` passed is the ACTUAL bigText from notification extras, NOT a concatenation of title+text+bigText
   - If the service currently builds `combinedBody` and passes it as bigText, fix to pass raw bigText only

3. **Write filter unit tests** covering edge cases

### P1-PR4 Step-by-Step

1. **Open** `NotificationCaptureGate.kt`
   - In `startObservers()`: wrap each `scope.launch { ... collect ... }` in a retry loop:
     ```kotlin
     scope.launch {
         var retries = 0
         while (retries < MAX_OBSERVER_RETRIES) {
             try {
                 privacySettingsRepository.observeSettings().collect { ... }
             } catch (e: CancellationException) { throw e }
             catch (e: Exception) {
                 retries++
                 Timber.w(e, "Settings observer failed, retry $retries/$MAX_OBSERVER_RETRIES")
                 delay(exponentialBackoff(retries))
             }
         }
         _state.value = GateState.Error("Settings observer exhausted retries")
     }
     ```
   - In `decide()`: when self-heal timeout expires and settings still not loaded, return `TemporarilyUnavailable` instead of dropping

2. **Open** `NotificationCaptureService.kt`
   - Handle `TemporarilyUnavailable` from gate: enqueue via intake coordinator for retry instead of emitting DROP diagnostic

### P1-PR5 Step-by-Step

1. **Open** `NotificationProcessingPipeline.kt`
   - Replace `private val processMutex = Mutex()` with `private val processSemaphore = Semaphore(MAX_CONCURRENT = 4)`
   - Change `processMutex.withLock { }` to `processSemaphore.withPermit { }`
   - Move `classifier.initialize()` to a separate `classifierMutex` that only guards initialization (not processing)

2. **Open** `NotificationRepository.kt`
   - Find `markProcessed()` call
   - Move it inside the pipeline's `withTransaction` block (or ensure it's called atomically with the outcome write)

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 1 Adapter/Follow-up |
|---|---|
| U-PR5 (Privacy/RawStorageMode) | **Required:** Implement per-source-type `notificationStorageMode` in intake coordinator; wire `RawContentPolicy.notificationStorageMode` instead of raw `rawStorageMode` enum; fix NEW-P1-009 TOCTOU by using single authoritative policy read |
| U-PR6 (Worker Guard) | **Optional:** Migrate `NotificationIntakeWorker` to use `WorkerExecutionGuard.runGuardedWithContext()` instead of manual barrier check + manual status tracking |

---

## 10. Validation Commands

```bash
# Build verification
./gradlew :app:assembleDebug --stacktrace

# Unit tests
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 1 targeted tests
./gradlew :app:testDebugUnitTest --tests "*NotificationProcessingPipeline*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NotificationFilter*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NotificationCaptureGate*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NotificationIntakeWorker*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NotificationIntakeCoordinator*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NotificationCaptureDeduper*" --stacktrace

# Architecture guard
./gradlew :app:testDebugUnitTest --tests "*CancellationSafetyArchitectureGuard*" --stacktrace

# Full check
./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P1-PR1: All 4 RawStorageMode values produce correct outcomes; no loss window before intake insert
- [ ] P1-PR2: Zero I/O side effects inside `withTransaction` blocks; no orphaned diagnostics
- [ ] P1-PR3: Filter passes deposit-with-fee, merchant-named-"failed"; rejects auth-failed; no bigText duplication
- [ ] P1-PR4: Gate observer survives transient exceptions; cold-start notifications retried
- [ ] P1-PR5: Concurrent notifications process in parallel; markProcessed atomic with outcome
- [ ] P1-PR6: No dead code; deduper cleanup wired; sensitive key filter comprehensive
- [ ] All existing tests pass (`./gradlew :app:testDebugUnitTest`)
- [ ] Build succeeds (`./gradlew :app:assembleDebug`)
- [ ] Architecture guard passes
- [ ] U-PR5 adapter landed (after U-PR5 merges): NEW-P1-009 closed, non-raw mode fully correct
- [ ] Pipeline 1 status upgraded to GREEN in master tracker
