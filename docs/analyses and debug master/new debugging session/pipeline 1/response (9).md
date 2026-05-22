Here is the dedicated implementation plan.

<pipeline1_p1_new_09_10_implementation_plan.md>
# Dedicated implementation plan — P1-NEW-09 and P1-NEW-10

Reviewed against commit: `e781c226862234ed412914884e98d22165a41a95`

Target issues:

| ID | Severity | Theme |
|---|---:|---|
| P1-NEW-09 | P1 | `MaintenanceSafeDiagnosticSink` is injected but notification diagnostics still use normal Room writer |
| P1-NEW-10 | P1/P2 | Blocked-package cache fail-closed startup behavior can drop valid notifications |

Recommended split:

1. **PR 1 — Safe notification diagnostic emitter**
2. **PR 2 — Blocked-package policy warm-up and non-false-drop decision path**
3. **PR 3 — Integration/regression tests + tracker update**

---

# Current evidence

## P1-NEW-09

`NotificationCaptureService` injects both:

```kotlin
@Inject lateinit var diagnosticEventWriter: DiagnosticEventWriter
@Inject lateinit var diagnosticSink: MaintenanceSafeDiagnosticSink
```

But notification diagnostic paths call:

```kotlin
diagnosticEventWriter.emit(...)
```

directly.

Examples:
- `emitOrderedNotificationEvents(...)`
- service-level `RECEIVED`
- restore-blocked terminal event
- filter-rejected terminal event
- privacy-gate terminal event

`MaintenanceSafeDiagnosticSink` already exposes:

```kotlin
suspend fun recordDiagnosticEvent(
    event: DiagnosticEvent,
    mode: RestoreMaintenanceMode.Mode,
    writeFailure: Throwable? = null
)
```

So the intended fallback exists, but notification capture does not route through it.

Risk:
- During restore/maintenance, diagnostic writes may attempt normal Room writes.
- If Room is unavailable, locked, blocked, or being restored, diagnostics can disappear.
- Diagnostic failures are often hidden with `runCatching { ... }` / empty catch blocks.

---

## P1-NEW-10

`NotificationCaptureService` currently has:

```kotlin
@Volatile private var blockedPackagesCache: Set<String> = emptySet()
@Volatile private var blockedPackageCacheLoaded = false
```

and:

```kotlin
private fun isPackageBlockedFast(packageName: String): Boolean =
    !blockedPackageCacheLoaded || packageName in blockedPackagesCache
```

Meaning:

```text
Until the first blocked-package flow emission arrives, every package is treated as blocked.
```

Risk:
- If a valid bank notification arrives immediately after service start, before the flow emits, it is dropped.
- Diagnostic reason is `BLOCKED_PACKAGE`, even though the package was not actually known blocked.
- This is privacy-safe but capture-unreliable and diagnostically misleading.

`BlockedPackageDao` currently has:
- `getAllPackageNamesFlow()`
- `isBlocked(packageName)`

but no one-shot `getAllPackageNamesOnce()` warm-up query.

---

# PR 1 — Safe notification diagnostic emitter

## Goal

All notification diagnostics must go through one safe emitter:

```text
Normal mode:
    try Room writer
    if writer fails, fallback to MaintenanceSafeDiagnosticSink

Maintenance / restore / write-blocked mode:
    do not write to Room
    write to MaintenanceSafeDiagnosticSink

Cancellation path:
    use NonCancellable best-effort emission
```

This fixes P1-NEW-09.

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt` if it writes notification pipeline diagnostics directly
- `DiagnosticEventWriter.kt` usage sites

New file:

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/NotificationDiagnosticEmitter.kt
```

Optional test file:

```text
app/src/test/java/.../NotificationDiagnosticEmitterTest.kt
```

---

## Step 1.1 — Add `NotificationDiagnosticEmitter`

Create:

```kotlin
@Singleton
class NotificationDiagnosticEmitter @Inject constructor(
    private val writer: DiagnosticEventWriter,
    private val sink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun emit(event: DiagnosticEvent) {
        withContext(ioDispatcher) {
            val mode = restoreMaintenanceMode.currentMode()

            if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
                emitToSink(event, mode, writeFailure = null)
                return@withContext
            }

            try {
                writer.emit(event)
            } catch (t: Throwable) {
                emitToSink(event, mode, writeFailure = t)
            }
        }
    }

    suspend fun emitOrdered(
        received: DiagnosticEvent,
        terminal: DiagnosticEvent
    ) {
        emit(received)
        emit(terminal)
    }

    suspend fun emitNonCancellable(event: DiagnosticEvent) {
        withContext(NonCancellable + ioDispatcher) {
            emit(event)
        }
    }

    suspend fun emitOrderedNonCancellable(
        received: DiagnosticEvent,
        terminal: DiagnosticEvent
    ) {
        withContext(NonCancellable + ioDispatcher) {
            emit(received)
            emit(terminal)
        }
    }

    private suspend fun emitToSink(
        event: DiagnosticEvent,
        mode: RestoreMaintenanceMode.Mode,
        writeFailure: Throwable?
    ) {
        try {
            sink.recordDiagnosticEvent(
                event = event,
                mode = mode,
                writeFailure = writeFailure
            )
        } catch (sinkFailure: Throwable) {
            Timber.w(
                sinkFailure,
                "Maintenance-safe diagnostic sink failed for pipeline=%s stage=%s outcome=%s",
                event.pipeline,
                event.stage,
                event.outcome
            )
        }
    }
}
```

Notes:
- `MaintenanceSafeDiagnosticSink` says implementations must not throw, but catch anyway.
- Do not store raw title/text/body/extras in diagnostic metadata.
- Use `currentMode()` rather than only `isWritesAllowed()` so the sink records the exact mode.

If `@IoDispatcher` does not exist yet, either:
- reuse the project’s existing dispatcher qualifier, or
- temporarily use `Dispatchers.IO` inside the emitter, but prefer DI for tests.

---

## Step 1.2 — Replace direct service diagnostic writer usage

In `NotificationCaptureService.kt`, replace injection:

```kotlin
@Inject lateinit var diagnosticEventWriter: DiagnosticEventWriter
@Inject lateinit var diagnosticSink: MaintenanceSafeDiagnosticSink
```

with:

```kotlin
@Inject lateinit var notificationDiagnosticEmitter: NotificationDiagnosticEmitter
```

Then replace all:

```kotlin
diagnosticEventWriter.emit(event)
```

with:

```kotlin
notificationDiagnosticEmitter.emit(event)
```

Replace:

```kotlin
runCatching { diagnosticEventWriter.emit(received) }
runCatching { diagnosticEventWriter.emit(terminal) }
```

with:

```kotlin
notificationDiagnosticEmitter.emitOrdered(received, terminal)
```

Current helper:

```kotlin
private fun emitOrderedNotificationEvents(
    received: DiagnosticEvent,
    terminal: DiagnosticEvent
)
```

should become:

```kotlin
private fun emitOrderedNotificationEvents(
    received: DiagnosticEvent,
    terminal: DiagnosticEvent
) {
    val launched = workTracker.launch(serviceScope) {
        notificationDiagnosticEmitter.emitOrdered(received, terminal)
    }

    if (launched == null) {
        // Optional but recommended if an ApplicationScope exists:
        applicationScope.launch {
            notificationDiagnosticEmitter.emitOrderedNonCancellable(received, terminal)
        }
    }
}
```

If `ApplicationScope` is not available yet, create one in DI instead of using `GlobalScope`.

---

## Step 1.3 — Restore-blocked path must never use Room

Current restore path:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    emitOrderedNotificationEvents(receivedEvent, restoreBlockedTerminal)
    return
}
```

After PR:

- this remains logically the same;
- but `emitOrderedNotificationEvents` must route through `NotificationDiagnosticEmitter`;
- since mode is non-normal, the emitter sends both events to `MaintenanceSafeDiagnosticSink`.

Acceptance for this path:

```text
restore active -> writer not called -> sink receives RECEIVED + terminal RESTORE_BLOCKED
```

---

## Step 1.4 — Remove silent diagnostic swallowing

Replace empty catches like:

```kotlin
try {
    diagnosticEventWriter.emit(...)
} catch (_: Exception) {}
```

with:

```kotlin
notificationDiagnosticEmitter.emit(...)
```

The emitter owns fallback and swallowing.

Do not scatter `runCatching` around diagnostics anymore except inside the emitter.

Search target:

```bash
grep -R "diagnosticEventWriter.emit" app/src/main/java/com/yourname/expensetracker/service
grep -R "runCatching.*Diagnostic" app/src/main/java/com/yourname/expensetracker/service
grep -R "catch (_: Exception)" app/src/main/java/com/yourname/expensetracker/service
```

Expected:
- no direct writer calls remain in notification service;
- no empty diagnostic catches remain.

---

## Step 1.5 — Optional: use emitter in pipeline diagnostic writes

If `NotificationProcessingPipeline` directly uses `DiagnosticEventWriter`, migrate it too:

```kotlin
private val diagnosticEmitter: NotificationDiagnosticEmitter
```

or create a generic:

```kotlin
SafeDiagnosticEmitter
```

and let notification-specific factory code stay elsewhere.

Recommended:
- use a generic `SafeDiagnosticEmitter` if many pipelines will later need the same maintenance-safe behavior;
- use `NotificationDiagnosticEmitter` if you want a small Pipeline 1 PR.

---

## Step 1.6 — Diagnostic event ownership

Keep existing ownership:

| Stage | Owner |
|---|---|
| `RECEIVED` | service/coordinator |
| pre-pipeline drops | service/coordinator |
| pipeline outcomes | pipeline |
| cancellation before pipeline | service/coordinator |
| writer fallback | emitter |

Do not create duplicate terminal events.

---

## PR 1 tests

### `NotificationDiagnosticEmitterTest`

Use fake writer, fake sink, fake `RestoreMaintenanceMode`.

1. **Normal mode / writer succeeds**
   - writer receives event;
   - sink receives nothing.

2. **Normal mode / writer throws**
   - writer attempted once;
   - sink receives same event;
   - `writeFailure` is passed.

3. **Restore mode**
   - writer is not called;
   - sink receives event with restore mode.

4. **Backup exporting mode**
   - writer is not called;
   - sink receives event with backup mode.

5. **Sink throws unexpectedly**
   - no exception escapes emitter;
   - safe warning log only.

6. **Ordered events**
   - `RECEIVED` emitted before terminal.

7. **NonCancellable path**
   - event is still attempted when parent coroutine is cancelled.

### Service tests

1. Restore-blocked notification uses sink, not writer.
2. Filter-rejected notification uses emitter.
3. Privacy-denied notification uses emitter.
4. Shutdown/cancel terminal event uses non-cancellable/best-effort helper.
5. No raw text appears in fallback sink event metadata.

---

## PR 1 acceptance criteria

- `MaintenanceSafeDiagnosticSink` is actually used for notification diagnostics.
- In non-normal maintenance mode, notification diagnostics do not attempt Room writes.
- If Room diagnostic write fails, event falls back to sink.
- No direct `diagnosticEventWriter.emit(...)` remains in `NotificationCaptureService`.
- No diagnostic failure is silently swallowed in notification service.
- Restore-blocked notification produces durable/fallback-safe `RECEIVED + RESTORE_BLOCKED`.
- Tests cover normal mode, restore mode, writer failure, sink failure, ordering, and cancellation.

---

# PR 2 — Blocked-package policy warm-up and no false startup drops

## Goal

Fix startup behavior:

```text
Cache not loaded must not mean “package is blocked”.
```

New rule:

```text
Before notification extras are read:
    if blocked-package cache is ready, use it.
    if cache is not ready, query DB for this package.
    if DB query says blocked, drop as BLOCKED_PACKAGE.
    if DB query says not blocked, proceed.
    if DB query fails/times out, fail closed with PACKAGE_POLICY_UNAVAILABLE/GATE_NOT_READY,
    not BLOCKED_PACKAGE.
```

This fixes P1-NEW-10.

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `BlockedPackageDao.kt`

New file recommended:

```text
app/src/main/java/com/yourname/expensetracker/domain/notification/capture/BlockedPackagePolicy.kt
```

Tests:

```text
BlockedPackagePolicyTest.kt
NotificationCaptureServiceBlockedPackageStartupTest.kt
```

---

## Step 2.1 — Add one-shot package list query

In `BlockedPackageDao.kt`, add:

```kotlin
@Query("SELECT packageName FROM blocked_packages")
suspend fun getAllPackageNamesOnce(): List<String>
```

Existing query:

```kotlin
suspend fun isBlocked(packageName: String): Boolean
```

will be used as the cache-not-ready fallback.

No migration needed.

---

## Step 2.2 — Add `BlockedPackagePolicy`

Create:

```kotlin
@Singleton
class BlockedPackagePolicy @Inject constructor(
    private val blockedPackageDao: BlockedPackageDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val state = MutableStateFlow<BlockedPackageCacheState>(
        BlockedPackageCacheState.NotLoaded
    )

    fun start(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            warmUp()
            observeChanges()
        }
    }

    suspend fun decide(packageName: String): BlockedPackageDecision {
        return when (val current = state.value) {
            is BlockedPackageCacheState.Ready -> {
                if (packageName in current.packages) {
                    BlockedPackageDecision.Blocked(source = "cache")
                } else {
                    BlockedPackageDecision.Allowed(source = "cache")
                }
            }

            BlockedPackageCacheState.NotLoaded,
            is BlockedPackageCacheState.LoadFailed -> {
                decideFromDao(packageName)
            }
        }
    }

    private suspend fun warmUp() {
        runCatching {
            blockedPackageDao.getAllPackageNamesOnce().toSet()
        }.onSuccess { packages ->
            state.value = BlockedPackageCacheState.Ready(packages)
        }.onFailure { error ->
            state.value = BlockedPackageCacheState.LoadFailed(error)
        }
    }

    private suspend fun observeChanges() {
        blockedPackageDao.getAllPackageNamesFlow().collect { packages ->
            state.value = BlockedPackageCacheState.Ready(packages.toSet())
        }
    }

    private suspend fun decideFromDao(packageName: String): BlockedPackageDecision {
        return withContext(ioDispatcher) {
            withTimeoutOrNull(PACKAGE_POLICY_DB_TIMEOUT_MS) {
                runCatching {
                    blockedPackageDao

:warning: The provider stream ended early, so this response may be incomplete.