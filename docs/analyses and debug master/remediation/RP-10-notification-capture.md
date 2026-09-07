# RP-10 — Notification capture hardening (Pipeline 1)

> **Scope class:** pipeline-isolated, privacy-adjacent. **Mode:** strict (notification capture + privacy modes).
> **Files:** `NotificationIntakeCoordinator` ⟂ (domain/notification/ or data/repository/ — locate by class), `NotificationIntakeWorker` (worker/), `NotificationIntakeDao` (data/database/dao/), `NotificationCaptureService` (service/), `NotificationCaptureGate` (domain/notification/capture/), `NotificationCaptureDeduper` (domain/notification/capture/), `NotificationTransientPayloadCrypto` ⟂, `TimeModule` ⟂ (di/), `SystemMonotonicTimeProvider` ⟂.
> **PR shape:** 3 PRs — (10a) deferred-intake identity = P1-001 + P1-002 + P1-007-adjacent; (10b) lifecycle gaps = P1-003 + P1-004 + REVAL-4; (10c) hygiene = P1-005 + P1-006 + P1-008.
> **Note:** the uncommitted `NotificationFilter.kt` `pos`-regex change is *not* part of this package (correct as-is; commit independently).

---

## 10a — Deferred-intake identity & storage mode

### P1-001 — Content-blind deferred fingerprint → silent permanent drop + double-ingest (HIGH)

**Problem.** `NotificationIntakeCoordinator.captureForRetry` (`:197`) stores `dedupeFingerprint = "DEFERRED_" + notificationKeyHash` while the live path fingerprints **content+postTime** (`RawNotificationFingerprint.compute`). The intake table's unique index on `dedupeFingerprint` + the fact that **no code path ever deletes intake rows** (retention only nulls payload columns) produce two failure modes: (a) Android reuses the `pkg|user|tag|id` key during a later gate-unavailable window → `insertOrIgnore` returns −1 → silent return (only `Timber.d`, no terminal diagnostic, no enqueue) — the notification is **permanently dropped**, defeating the exact scenario the deferred path exists for; (b) the same content captured live after warm-up → different fingerprint → second raw row/expense outside the 5-min dedupe window.

**Fix design.**
1. **Use the content fingerprint on deferred rows**: at `captureForRetry`, all inputs for `RawNotificationFingerprint.compute` are available (parts + postTime are in scope where the service calls it). Compute and store the *same* fingerprint format as the live path; drop the `DEFERRED_` prefix scheme entirely (no migration needed — the old rows' fingerprints simply never match new-format ones; they age out via payload purge. Note this in the PR: historical deferred rows become inert, acceptable since their payloads were encrypted-transient anyway).
2. **Make the −1 branch honest**: on conflict, emit a terminal diagnostic (reuse the live `Duplicate` branch's emitter at `:56-70`) and return a typed `Duplicate(existingIntakeId)`; additionally enqueue a worker for the *existing* row if its status is still `RECEIVED` (cheap re-drive; `claimForProcessing`'s CAS makes it safe).
3. **Double-ingest closure**: with fingerprints unified, a live re-capture of the same content hits `existsByFingerprint` → existing duplicate path → done. Add a unit test proving deferred-then-live dedupes.

**What it solves.** No silent drops during gate-unavailable windows; no duplicate ingestion after warm-up.

**Guardrails.**
- Privacy: the fingerprint is already a hash — unchanged format, no new persisted content.
- WorkManager enqueue stays inside the NonCancellable region (current structure preserved).
- Diagnostics use controlled reason codes (`DUPLICATE_INTAKE`, `INTAKE_DEFERRED`) — no notification text in events.

**Tests.** `NotificationIntakeCoordinatorTest` (create): key-reuse scenario → Duplicate + re-enqueue + diagnostic; deferred-then-live same content → single pipeline row.

### P1-002 — Deferred rows hardcode `STORE_METADATA_ONLY`, overriding the user's mode (MED-HIGH)

**Problem.** `:207` stores `rawStorageMode = "STORE_METADATA_ONLY"`; the worker builds both the storage notification (`:457`) and the persistence context (`:227-236`) **exclusively** from the stored mode and never re-reads settings → a STORE_RAW user permanently loses title/text/bigText for anything captured during warm-up. Also inconsistent: no `DO_NOT_STORE` early-return on the deferred path (unlike `capture()` at `:75`).

**Fix design.**
1. At `captureForRetry`, resolve the *real* mode with a bounded one-shot read (the gate's self-heal already loads settings within 300ms — reuse the same loader/repository snapshot; if the read fails, **fail closed to METADATA_ONLY**, matching the sanitizer's fail-closed convention — never STORE_RAW on uncertainty).
2. Add the `DO_NOT_STORE` early-return mirroring `capture()`: if the resolved mode is DO_NOT_STORE → return a typed `NotStored` outcome without persisting an intake row (the transient payload is never durably written in that mode — verify the encrypt-then-insert ordering makes this clean).
3. Worker side unchanged (it honors the stored mode — now the user's actual mode).

**What it solves.** Storage-mode contract holds on every capture path; DO_NOT_STORE users get no durable artifacts from deferred captures.

**Guardrails.** Privacy fail-closed is the deciding rule: uncertain → most-restrictive. The bounded read must not add unbounded latency to the service path (same 300ms budget as the gate). **Tests:** STORE_RAW user + gate-unavailable → deferred row carries STORE_RAW, worker persists text; settings-read failure → METADATA_ONLY; DO_NOT_STORE → no row.

---

## 10b — Lifecycle gaps

### P1-003 — Enqueue result unchecked; orphaned RECEIVED rows (MED)

**Problem.** `enqueueUniqueWork`'s `Operation` is never awaited (`:142-146`, `:232-236`); process death or async enqueue failure leaves a RECEIVED row with no worker. Recovery exists only via `onListenerConnected` (`NotificationCaptureService.kt:291-298`); the RecoveryScheduler KDoc (`:26`) claims app-start and restore-complete sweeps that **don't exist** (REVAL-4).

**Fix design.**
1. Await inside the NonCancellable region: `operation.result.await()` (kotlinx-coroutines-play-services or the `Operation` suspend extension already available — check which WorkManager Kotlin artifacts the module uses; if none, poll `operation.state.isBlocked/isFailed` via `withTimeout`). On failure → mark the row `FAILED_RETRYABLE` with `nextAttemptAt = now + backoff` (reuses the existing retry machinery) + diagnostic event.
2. Add the missing sweeps the KDoc promises: call `NotificationIntakeRecoveryScheduler.recoverPending()` (a) from `AppStartupCoordinator.initialize` (cheap bounded query; runs behind the existing startup scope) and (b) on restore-complete (the maintenance-mode exit path already has a hook where workers reschedule — `RestoreMaintenanceMode.exit` → add the recovery call alongside `WorkerRegistry.scheduleAll`). Now the KDoc is true.
3. Keep `onListenerConnected` sweep as-is (primary).

**What it solves.** No permanently orphaned intake rows for any failure mode; documented behavior matches reality.

**Guardrails.** Startup-path addition must be non-blocking (fire-and-forget in the existing startup coroutine) and must not run Gradle-visible heavy work — it's a status query + enqueue. Worker rules: the sweep itself performs no DB writes except status transitions via the existing CAS claim.

**Tests.** Enqueue-fails fixture → row FAILED_RETRYABLE + diagnostic; startup invokes recovery (assert via fake scheduler).

### P1-004 — Cancellation window before the NonCancellable region (MED)

**Problem.** In `captureNotification` (`:412-511`), only the `TemporarilyUnavailable` branch (`:454`) and Step 5+ (`:517`) are NonCancellable. Steps 1–4 (RECEIVED emit → `captureGate.decide` with up to 2–3 × 300ms self-heal loads → dedupe → filter) are cancellable with no try/finally terminal: `onDestroy`'s `serviceJob.cancel()` (`:855`) kills in-flight work leaving a dangling RECEIVED event or nothing at all; the notification is never re-captured (rebind refresh requires an explicit `ACTION_REFRESH`). Dead constant `SHUTDOWN_DRAIN_TIMEOUT_MS` (`:188`) shows a drain was planned then dropped.

**Fix design.**
1. Do **not** wrap everything in NonCancellable (the gate's bounded loads should remain cancellable — AGENTS: workers/services must not swallow CE; and a draining service shouldn't be hostage to 900ms of I/O).
2. Instead, close the *accounting* gap: wrap the pre-NonCancellable steps in `try/catch(CancellationException)` that emits a terminal `CANCELLED` diagnostic via `notificationDiagnosticEmitter` inside a `withContext(NonCancellable)` emit (the emitter call is cheap), then rethrows. This mirrors the pattern already used at `:764-777` for the post-capture cancellation path.
3. `onDestroy` ordering: emit `SHUTDOWN` *before* `serviceJob.cancel()` (already the case? verify) so the diagnostic ledger shows the shutdown preceding the CANCELLED events — keeps the timeline interpretable.
4. Delete the dead `SHUTDOWN_DRAIN_TIMEOUT_MS` constant (or wire it — deleting is smaller; the drain design was explicitly rejected in the onDestroy comment re: `ForegroundServiceDidNotStopInTimeException`).

**What it solves.** Every accepted notification ends in exactly one terminal diagnostic; no dangling RECEIVED events on shutdown; no misleading dead constants.

**Guardrails.** CE rethrow is mandatory (never swallow); the NonCancellable emit must be bounded (single emit, no loops). **Tests:** cancel during gate-decide (fake gate suspends) → CANCELLED diagnostic present, CE propagates, no intake row.

---

## 10c — Hygiene

### P1-005 — Missing sensitive extras keys (LOW, STORE_RAW-gated)

**Fix design.** Add to `SENSITIVE_EXTRAS_KEYS` (`NotificationCaptureService.kt:192-206`): `android.messages`, `android.textLines`, `android.remoteInputHistory`, `android.conversationTitle`. Defense-in-depth only (extrasJson persists solely under STORE_RAW, where the same text already reaches `bigText` via `combinedBody`). Extend the pinned key-set test in `NotificationCaptureServiceCleanupTest` with the four keys.

### P1-006 — Deduper on wall clock (LOW)

**Fix design.** `NotificationCaptureDeduper` (`:28`) — inject the existing `MonotonicTimeProvider` binding (swap the `TimeProvider` constructor dependency to the monotonic interface; `SystemMonotonicTimeProvider` exists in the same package family). If the deduper's constructor type is `TimeProvider` (wall), introduce/verify a `MonotonicTimeProvider` supertype binding in `TimeModule` and depend on that. Blast radius confirmed narrow: dedupe key is content+key, so only identical re-post suppression timing changes. **Tests:** backward-jump fixture (monotonic fake) → duplicate still suppressed; forward-jump → window intact.

### P1-008 — NUL delimiter in transient payload (LOW)

**Fix design.** `NotificationTransientPayloadCrypto.encrypt/decrypt` (`:45-56, :70-77`): replace `\u0000`-join/split with a length-prefixed frame (`DataOutputStream.writeUTF`-style or manual `Int` length + bytes per field) before encryption — framing survives any content. Preserve the null-vs-empty distinction via a presence byte (fixes the `takeIf { isNotEmpty() }` erasure noted in re-validation). Wire format is transient-only (encrypted at rest, re-derived per capture) — no migration concerns. **Tests:** round-trip fixture with `\u0000` inside text, empty vs null fields.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*NotificationIntake*" --tests "*NotificationCaptureService*" --tests "*NotificationCaptureDeduper*" --tests "*NotificationTransientPayload*" --tests "*NotificationFilter*"
```

## Sequencing & risk
- Order: 10a → 10b → 10c. 10a changes the intake fingerprint format (inert-history note required in PR). Risk: low-medium; all paths covered by the existing intake-worker test family plus new coordinator tests.
