# RP-10 - Notification capture hardening (Pipeline 1)

> **Status:** FAIL in the previous draft; this rewrite is implementation-ready only after the
> legacy-row write-boundary contract below is approved.
> **Mode:** strict (privacy, durable state machine, WorkManager enqueue, and cancellation).
> **Source facts:** `notification_intake.dedupeFingerprint` has a unique index; deferred capture
> currently uses `DEFERRED_<notificationKeyHash>`, hardcodes `STORE_METADATA_ONLY`, returns `Unit`,
> and its enqueue operation is not awaited. Retention purges payload columns but does not remove the
> intake row.

The revalidated `REPLACE/orphan` theory is not an independent defect: an identical legacy key hits
the insert conflict and returns before enqueue. Do not reintroduce that claim. The live defects are
legacy identity retention, privacy-mode loss, unchecked enqueue, and cancellation accounting.

---

## 10a - Deferred identity and privacy mode

### P1-001 - Canonical identity with an atomic legacy-row transition

Use the exact same `RawNotificationFingerprint.compute` inputs and body construction as the live
path: `packageName`, `title`, `text`, the live `parts.combinedBody` value passed as `bigText`, and
`postTime`. Do not substitute deferred `bigText` or add `subText` only to deferred rows. Change the
deferred capture contract to carry the same effective combined body (or the exact source fields
needed to reconstruct it) and persist enough bounded metadata to compare legacy rows. If an old row
does not contain enough fields to prove the canonical identity, treat it as **unverifiable** rather
than guessing. `notificationKey` is used only to locate a legacy row, never as the new dedupe
identity.

New deferred rows use this canonical fingerprint, not `DEFERRED_<hash>`. The live and deferred
capture paths must call the same coordinator/DAO operation so a deferred row followed by a live
capture cannot bypass the legacy check. Before inserting either kind of canonical row, execute one
database transaction that:

1. looks up the legacy row by the deterministic old `DEFERRED_<notificationKeyHash>` value;
2. compares its canonical payload when the payload is still available;
3. returns the existing row as `Duplicate(existingIntakeId)` when it represents the same content,
   including a comparable terminal row; a terminal row with comparable different content may be
   superseded, while an unverifiable terminal row follows the explicit bounded policy below;
4. otherwise atomically marks the legacy row terminal/inert using the existing state machine
   (`FAILED_FINAL` with controlled failure code `LEGACY_DEFERRED_SUPERSEDED`, `terminalAt`, and
   cleared claims) before inserting the new canonical row.

The DAO/coordinator operation must specify these edge cases and remain idempotent:

| Legacy row | Required behavior |
| --- | --- |
| `RECEIVED` or `FAILED_RETRYABLE`, payload available | decrypt/compare; duplicate and re-drive the old row when equal, otherwise supersede then insert the new row |
| `RECEIVED`/`FAILED_RETRYABLE`, payload purged | mark inert with `LEGACY_DEFERRED_PAYLOAD_UNAVAILABLE`, then allow the new canonical row |
| `PROCESSING`/claimed, payload available and same content | if the claim is active, do not overwrite it; return duplicate and let the claimed worker finish/re-drive |
| `PROCESSING`/claimed, payload available but changed content | leave the active claim untouched and insert the new canonical row; do not drop the new notification |
| stale `PROCESSING`/claimed, payload purged | first use the existing stale-claim CAS release; only after release, terminalize as unrecoverable and allow the new row |
| already terminal with comparable payload (`PROCESSED`, duplicate/policy/final failure/cancelled) | same canonical content returns `Duplicate`; comparable different content may insert a new canonical row |
| already terminal but payload purged/unverifiable | return a bounded `LEGACY_IDENTITY_UNVERIFIABLE` outcome; do not silently reprocess or store content until the caller's explicit recapture policy is applied |

Do not delete or mutate an active claim blindly, and do not invent a new persisted status. The unique index can
remain unchanged because new fingerprints no longer occupy the legacy namespace; the transaction
above is the required compatibility migration at the write boundary. Add a migration test only if
the chosen implementation changes the Room schema/index; otherwise add DAO transaction tests for
all rows in the table above.

When a canonical insert conflicts, look up the conflicting row and return `Duplicate` (with its
intake id) rather than `Dropped`. Re-drive only `RECEIVED`/`FAILED_RETRYABLE` rows using the existing
CAS claim; never enqueue a terminal row. The operation must be atomic with the legacy lookup and
insert so two listener/deferred callers cannot each decide to replace the same legacy row.

### P1-002 - Preserve the resolved RawStorageMode and fail closed when unknown

Change `captureForRetry` to accept a bounded privacy-policy snapshot (`RawStorageMode`, app name,
the exact effective combined body, and any storage-safe extras value needed by the live path). The
gate and deferred branch must share one bounded policy resolver; do not add an unbounded second
settings read. If the gate/settings policy is unavailable and no immutable snapshot is available,
return `NotStored` (or a retryable policy-unavailable result with **no extracted/encrypted payload**)
before parsing, encryption, or insert. `STORE_METADATA_ONLY` is valid only when that mode was
actually resolved; it is not a safe substitute for an unknown `DO_NOT_STORE` policy. Only include
extras JSON after the same STORE_RAW sanitization used by the live path.

Behavior is explicit:

- `DO_NOT_STORE`: return `NotStored` before encryption/insert; emit a terminal controlled diagnostic
  and do not create an intake row or durable payload;
- `STORE_RAW`: persist the same visible/raw fields and `payloadMode` contract as `capture()`;
- `STORE_REDACTED`/`STORE_METADATA_ONLY`: encrypt the transient payload, persist no visible text,
  and let the worker honor the stored mode.

Extend the existing `NotificationIntakeCaptureResult` rather than creating a parallel result type.
It must distinguish at least `Enqueued`, `Duplicate(existingIntakeId?)`, `NotStored`,
`StorageFailure(reasonCode)`, and `Cancelled`; retain `RequiresSynchronousProcessing` for the live
DO_NOT_STORE path if its callers still require it. Update every caller and diagnostic branch in
`NotificationCaptureService` and tests. No diagnostic contains notification text, paths, exception
messages, or stack traces.

### P1-001/P1-002 tests

- old deferred row followed by a new deferred capture for the same key and same content;
- old row followed by changed content;
- each legacy status in the table above, including purged and claimed rows;
- deferred-then-live capture dedupes to one pipeline row;
- STORE_RAW, REDACTED, METADATA_ONLY, and DO_NOT_STORE mode matrix;
- unresolved settings/policy returns no-storage/no-payload and never stores raw text;
- comparable terminal legacy row with same content returns duplicate; purged/unverifiable terminal
  row follows `LEGACY_IDENTITY_UNVERIFIABLE` policy;
- canonical fingerprint inputs match live capture exactly.

---

## 10b - Enqueue, retry, and cancellation state machine

> **Status (2026-09-16):** P1-003/P1-004 landed in `442411b8`. Awaited
> enqueue + atomic markEnqueueFailed (shared backoff ladder via
> NotificationIntakeRetryPolicy), EnqueueFailed result, app-start recovery
> hook via AppStartupCoordinator, cancellation diagnostics (CAPTURE_CANCELLED,
> NonCancellable emission, rethrow), SHUTDOWN_DRAIN_TIMEOUT_MS removed.
> Validated: RetryPolicy 2/2, Coordinator 18/18, RestoreBarrier 5/5, worker
> suites green. Conditional: EnqueueFailureTest @Ignore'd (MockK suspend
> hang family — RP-21 feed); DeferredPolicyTest never compiled at 10a
> (proven) and its first run hangs in the same family — unvalidated 10a
> carryover. 10c (P1-005/006/008 hygiene) not started.

### P1-003 - Await enqueue and transition failures atomically

Inside the existing `NonCancellable` write/enqueue region, await the WorkManager `Operation` using
the dependency-supported suspend API; if that dependency is unavailable, use a bounded state wait.
On enqueue failure, call one DAO transition that atomically:

- increments `attempts` exactly once;
- sets `FAILED_RETRYABLE` and `nextAttemptAt` using the existing backoff, or `FAILED_FINAL` when
  `maxAttempts` is reached;
- stores only a controlled `lastFailureCode`/safe hash;
- clears `lockedAt`/`lockedBy` and updates `updatedAt`;
- is conditional on the expected `RECEIVED`/enqueue-attempt state so repeated failure handling is
  idempotent.

Emit a terminal/retryable diagnostic that identifies only the controlled reason code and intake id.
The recovery scheduler must use the existing `WorkerRegistry`/`WorkerSpecScheduler` architecture;
do not create a second WorkManager scheduling path. Verify the concrete app-start and restore-
complete hooks before adding calls. If a promised hook is absent, add it through the central
coordinator and update the KDoc; keep the listener-connected recovery as-is.

Tests: operation failure, max-attempt transition, repeated failure, and recovery after process death.

### P1-004 - Account for cancellation without swallowing it

Keep gate self-healing and extraction cancellable. Wrap the pre-enqueue work in
`catch (CancellationException)` only to emit one bounded terminal `CANCELLED` diagnostic from a
`NonCancellable` emission, then rethrow the cancellation. Do not turn caller cancellation into a
retry or success and do not create a partial intake row. Preserve shutdown ordering (`SHUTDOWN`
diagnostic before cancelling the service job) and remove the unused `SHUTDOWN_DRAIN_TIMEOUT_MS`
constant.

Test cancellation during gate self-heal, extraction, and deferred capture: exactly one cancellation
diagnostic, cancellation propagates, and no durable row is left in `RECEIVED`.

---

## 10c - Hygiene (unchanged scope, corrected contracts)

### P1-005 - Sensitive extras key set

Add `android.messages`, `android.textLines`, `android.remoteInputHistory`, and
`android.conversationTitle` to `SENSITIVE_EXTRAS_KEYS`. Keep the existing STORE_RAW gate and extend
the pinned key-set test. Never log the values.

### P1-006 - Monotonic dedupe window

Inject the existing `MonotonicTimeProvider` into `NotificationCaptureDeduper` through the existing
time module binding. Add backward- and forward-clock-jump tests; the dedupe key and TTL semantics do
not otherwise change.

### P1-008 - Collision-safe transient framing

Replace NUL-delimited transient serialization with length-prefixed framing (including a presence
bit so null and empty remain distinct) before encryption. This is a transient-only wire-format
change, so no Room migration is required. Add round-trip tests containing embedded NULs and empty/
null fields.

---

## Validation and gates

```text
./gradlew :app:testDebugUnitTest --tests "*NotificationIntake*" --tests "*NotificationCaptureService*" \
  --tests "*NotificationCaptureDeduper*" --tests "*NotificationTransientPayload*" --tests "*NotificationFilter*"
```

Tests were not run while this plan was rewritten. Strict review must inspect the final DAO transition,
all result callers, privacy diagnostics, and the legacy-row tests before this plan is marked ready.
The plan must stop if source inspection reveals that the chosen legacy transition cannot preserve an
active claimed row or if a schema/index change is introduced without a Room migration and schema
snapshot.
