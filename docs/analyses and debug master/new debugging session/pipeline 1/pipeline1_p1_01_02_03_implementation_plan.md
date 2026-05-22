# Pipeline 1 dedicated implementation plan

Target issues:

| ID | Status | Theme |
|---|---:|---|
| P1-P1-01 | Partial | Outcome propagation |
| P1-P1-02 | Partial | Durable/safe diagnostics |
| P1-P1-03 | Partial | Notification text/message extraction |

Reviewed against commit: `e781c226862234ed412914884e98d22165a41a95`

---

# Recommended PR split

Implement as **3 PRs**:

1. **PR 1 — Outcome contract and repository/service truthfulness**
   - Fixes P1-P1-01.
   - Must land first because diagnostics and dedupe decisions need real outcomes.

2. **PR 2 — Notification diagnostic emitter and complete event ledger**
   - Fixes P1-P1-02.
   - Depends on PR 1 for accurate terminal pipeline outcomes.

3. **PR 3 — MessagingStyle/text extraction correctness**
   - Fixes P1-P1-03.
   - Can be developed in parallel, but ideally lands after PR 2 so extraction failures/drops are observable.

---

# PR 1 — P1-P1-01: Outcome contract and repository/service truthfulness

## Current problem

`NotificationProcessingPipeline.process(...)` already returns `NotificationPipelineOutcome`.

But `NotificationRepository.processAndSave(...)` still returns `Unit`.

Current bad flow:

1. Pipeline produces real outcome:
   - `AutoAccepted`
   - `NeedsReview`
   - `Duplicate`
   - `ParserFailed`
   - `AutoRejected`
   - `Dropped`
   - `Error`

2. Repository logs that outcome but discards it.

3. `NotificationCaptureService.processNotification(...)` calls repository and then logs:

```text
Processed notification from: packageName
```

even if the pipeline returned `Error`, `Dropped`, `Duplicate`, or `ParserFailed`.

So the service cannot make correct follow-up decisions.

---

## Files to modify

Primary:

- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`

Recommended new file:

- `app/src/main/java/com/yourname/expensetracker/domain/notification/NotificationPipelineOutcome.kt`

Optional test files:

- `app/src/test/.../NotificationRepositoryOutcomeTest.kt`
- `app/src/test/.../NotificationCaptureServiceOutcomeTest.kt`
- `app/src/test/.../NotificationProcessingPipelineOutcomeTest.kt`

---

## Implementation steps

## Step 1.1 — Extract `NotificationPipelineOutcome` to a stable top-level type

Right now `NotificationPipelineOutcome` is nested inside `NotificationProcessingPipeline`.

That makes repository/service signatures verbose and couples public repository API to a specific pipeline class.

Move it to a top-level domain/data contract file.

Suggested location:

```text
domain/notification/NotificationPipelineOutcome.kt
```

Suggested shape:

```kotlin
sealed interface NotificationPipelineOutcome {
    val packageName: String
    val correlationId: String?

    data class AutoAccepted(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long,
        val expenseId: Long
    ) : NotificationPipelineOutcome

    data class NeedsReview(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long,
        val reviewId: Long
    ) : NotificationPipelineOutcome

    data class Duplicate(
        override val packageName: String,
        override val correlationId: String?,
        val reason: String
    ) : NotificationPipelineOutcome

    data class ParserFailed(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long?,
        val reason: String
    ) : NotificationPipelineOutcome

    data class AutoRejected(
        override val packageName: String,
        override val correlationId: String?,
        val rawId: Long?,
        val reason: String
    ) : NotificationPipelineOutcome

    data class Dropped(
        override val packageName: String,
        override val correlationId: String?,
        val reason: String
    ) : NotificationPipelineOutcome

    data class Error(
        override val packageName: String,
        override val correlationId: String?,
        val throwable: Throwable
    ) : NotificationPipelineOutcome
}
```

If you want a smaller PR, you can keep the nested type temporarily, but I recommend moving it now because PR 2 needs a clean diagnostic mapper.

---

## Step 1.2 — Update pipeline to return the top-level outcome

In `NotificationProcessingPipeline.kt`:

- remove nested `NotificationPipelineOutcome`;
- import the new top-level type;
- ensure every returned outcome includes:
  - `packageName`;
  - `correlationId`.

Current outcomes like `AutoAccepted(rawId, expenseId)` should become:

```kotlin
NotificationPipelineOutcome.AutoAccepted(
    packageName = notification.packageName,
    correlationId = correlationId,
    rawId = rawId,
    expenseId = expenseId
)
```

Do the same for every branch.

Important branches to check:

- fast duplicate before parse;
- parser failed with no transaction signal;
- parser failed but pending review created;
- oversized amount review;
- transaction signal review;
- auto accept;
- needs review;
- auto reject;
- insert duplicate after DB unique conflict;
- exception catch path.

---

## Step 1.3 — Remove dead `ProcessingResult`

`NotificationProcessingPipeline` still has dead `ProcessingResult`.

Remove:

```kotlin
sealed interface ProcessingResult
```

Also remove stale TODOs saying outcome return still needs to happen.

Replace old TODO with a new note only if there is remaining work, e.g.:

```text
TODO: move parser provenance into ParseOutcome.
```

Do not leave TODOs that claim this issue is still unfixed after the PR.

---

## Step 1.4 — Change repository signatures

In `NotificationRepository.kt`, change:

```kotlin
suspend fun processAndSave(notification: RawNotification)
```

to:

```kotlin
suspend fun processAndSave(notification: RawNotification): NotificationPipelineOutcome
```

Change:

```kotlin
suspend fun processAndSave(
    processingNotification: RawNotification,
    storageNotification: RawNotification,
    correlationId: String? = null
)
```

to:

```kotlin
suspend fun processAndSave(
    processingNotification: RawNotification,
    storageNotification: RawNotification,
    correlationId: String? = null
): NotificationPipelineOutcome
```

Change:

```kotlin
suspend fun processAndSaveAll(notifications: List<RawNotification>)
```

to:

```kotlin
suspend fun processAndSaveAll(
    notifications: List<RawNotification>
): List<NotificationPipelineOutcome>
```

Repository should still log, but must return the outcome after logging.

Example behavior:

```text
val outcome = pipeline.process(...)
logOutcome(outcome)
return outcome
```

Do not swallow `CancellationException`.

If pipeline returns `NotificationPipelineOutcome.Error`, return it. Do not throw unless the pipeline throws unexpectedly outside its own catch path.

---

## Step 1.5 — Add `logOutcome(outcome)` helper

Avoid repeated `when` blocks in repository.

Create private helper:

```kotlin
private fun logOutcome(outcome: NotificationPipelineOutcome)
```

Rules:

| Outcome | Log level |
|---|---|
| `AutoAccepted` | info |
| `NeedsReview` | info |
| `Duplicate` | debug or warning |
| `ParserFailed` | warning |
| `AutoRejected` | info/warning |
| `Dropped` | info/warning depending reason |
| `Error` | error |

Keep logs privacy-safe:
- no raw notification text;
- no raw title;
- no raw bigText;
- package should be okay only if your logging policy allows it; otherwise hash.

---

## Step 1.6 — Update service to use the returned outcome

In `NotificationCaptureService.processNotification(...)`, replace:

```kotlin
repository.processAndSave(processingNotification, storageNotification, correlationId)
Timber.d("Processed notification from: $packageName")
```

with outcome-aware handling.

Suggested behavior:

| Outcome | Service action |
|---|---|
| `AutoAccepted` | log success with expense ID |
| `NeedsReview` | log queued review |
| `Duplicate` | log duplicate, do not claim processed |
| `ParserFailed` | log parser failed, do not claim processed |
| `AutoRejected` | log rejected |
| `Dropped` | log dropped |
| `Error` | log error; optionally emit repository-stage failure only if pipeline did not already emit terminal event |

Important: avoid duplicate terminal diagnostic events.

Recommended event ownership:

- Service owns:
  - `RECEIVED`;
  - early terminal drops before pipeline;
  - cancellation before/during repository;
  - repository exception if no outcome is returned.

- Pipeline owns:
  - terminal result after `pipeline.process(...)` starts.

So after repository returns an outcome, service should not emit a second terminal event for the same correlation ID unless PR 2 deliberately adds a non-terminal “repository observed outcome” event.

---

## Step 1.7 — Prepare dedupe correctness without fixing it here

Do not fully fix P1 dedupe in this PR, but make the result available.

In the service coroutine, keep a local variable:

```kotlin
var outcome: NotificationPipelineOutcome? = null
```

Assign after repository returns.

In the `finally`, keep current behavior for now unless you want to include a minimal safe improvement.

Recommended minimal improvement:

- keep current removal behavior to avoid widening PR scope;
- add TODO linked to dedupe PR:

```text
TODO P1-DEDUP: retain successful dedupe keys until TTL based on outcome.
```

Full dedupe fix belongs to a later PR.

---

## Step 1.8 — Update all call sites

Search:

```bash
grep -R "processAndSave(" app/src/main/java
grep -R "processAndSaveAll(" app/src/main/java
```

Every caller must compile with the new return type.

Likely call sites:

- `NotificationCaptureService.processNotification(...)`
- manual refresh path indirectly through `processNotification(...)`
- tests/debug utilities if any.

---

## PR 1 tests

## Unit test group A — repository returns outcomes

Test cases:

1. Pipeline returns `AutoAccepted` → repository returns `AutoAccepted`.
2. Pipeline returns `NeedsReview` → repository returns `NeedsReview`.
3. Pipeline returns `Duplicate` → repository returns `Duplicate`.
4. Pipeline returns `ParserFailed` → repository returns `ParserFailed`.
5. Pipeline returns `AutoRejected` → repository returns `AutoRejected`.
6. Pipeline returns `Dropped` → repository returns `Dropped`.
7. Pipeline returns `Error` → repository returns `Error`.
8. Pipeline throws `CancellationException` → repository rethrows cancellation.

If mocking `NotificationProcessingPipeline` is painful because it is concrete, either:
- extract an interface, e.g. `NotificationProcessor`;
- or test `NotificationRepository` with a fake pipeline through constructor if possible;
- or move logging/return logic into a smaller testable collaborator.

---

## Unit test group B — service handles returned outcome truthfully

Create a fake repository returning each outcome.

Verify:

1. `AutoAccepted` does not log/drop as failure.
2. `NeedsReview` is not treated as error.
3. `Duplicate` is not logged as “processed”.
4. `ParserFailed` is not logged as “processed”.
5. `Dropped` is not logged as “processed”.
6. `Error` gets error handling path.

If direct service testing is hard, extract pure helper:

```kotlin
NotificationServiceOutcomeHandler.handle(outcome)
```

Then test the helper.

---

## PR 1 acceptance criteria

PR is complete when:

- `NotificationRepository.processAndSave(...)` returns `NotificationPipelineOutcome`.
- `NotificationRepository.processAndSaveAll(...)` returns `List<NotificationPipelineOutcome>`.
- `NotificationCaptureService` no longer logs unconditional success.
- All pipeline outcomes are observable to service callers.
- `CancellationException` is not swallowed.
- Dead `ProcessingResult` is removed.
- Stale TODO claiming outcome return is missing is removed.
- Tests cover all outcome variants.

---

# PR 2 — P1-P1-02: Notification diagnostic emitter and complete event ledger

## Current problem

Diagnostics are improved but still partial.

Current good state:

- `RECEIVED` events exist.
- Many early drops emit terminal events.
- Pipeline writes terminal diagnostic events.
- Correlation ID is propagated.

Remaining problems:

1. Service writes diagnostics directly via `diagnosticEventWriter.emit(...)`.
2. `MaintenanceSafeDiagnosticSink` is injected into service but not consistently used.
3. Diagnostic writes are often wrapped in `runCatching` and silently swallowed.
4. Restore-mode diagnostic behavior is ambiguous.
5. Event ownership is not formalized, so duplicate/missing terminal events are possible.
6. Some diagnostics are launched asynchronously and can be cancelled during service shutdown.
7. There is no single event factory/mapper, so event shape can drift.

This PR does **not** solve P1-P1-07 durable notification intake. It only makes the diagnostic ledger safer and more complete.

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `NotificationProcessingPipeline.kt`
- `DiagnosticEventWriter.kt` usage sites
- `MaintenanceSafeDiagnosticSink.kt` usage sites

Recommended new files:

- `domain/diagnostics/NotificationDiagnosticEmitter.kt`
- `domain/diagnostics/NotificationDiagnosticEventFactory.kt`
- `domain/diagnostics/NotificationOutcomeDiagnosticMapper.kt`

Possible DI module update:

- wherever `DiagnosticEventWriter` / diagnostic implementations are bound.

Tests:

- `NotificationDiagnosticEmitterTest.kt`
- `NotificationDiagnosticEventFactoryTest.kt`
- `NotificationCaptureDiagnosticsFlowTest.kt`
- `NotificationPipelineDiagnosticMapperTest.kt`

---

## Step 2.1 — Define event ownership rules

Add a short KDoc near the new diagnostic emitter or factory.

Rules:

1. Every notification callback should produce exactly one `RECEIVED` event unless service is already dead before code runs.
2. If notification exits before pipeline starts, service emits the terminal event.
3. If pipeline starts, pipeline emits the terminal event.
4. Service must not emit a second terminal event after a normal pipeline outcome.
5. Repository exceptions before a pipeline outcome get a service-level terminal failure.
6. Cancellation gets a terminal `CANCELLED` event using `NonCancellable`.
7. Diagnostic failure must not crash notification processing, but must be sent to fallback sink.

---

## Step 2.2 — Create `NotificationDiagnosticEmitter`

Purpose:

- one safe path for all notification diagnostic writes;
- route to Room writer during normal operation;
- route/fallback to `MaintenanceSafeDiagnosticSink` during restore/maintenance or writer failure;
- never leak raw notification text.

Suggested responsibilities:

```text
NotificationDiagnosticEmitter
- emit(event)
- emitReceived(...)
- emitTerminal(...)
- emitReceivedThenTerminal(...)
- emitCancellation(...)
- emitWriterFallback(...)
```

Constructor dependencies:

- `DiagnosticEventWriter`
- `MaintenanceSafeDiagnosticSink`
- `RestoreMaintenanceMode`
- maybe `TimeProvider`
- maybe application logger

Behavior:

1. If maintenance mode is normal:
   - try `diagnosticEventWriter.emit(event)`;
   - if it throws, call `diagnosticSink.recordDiagnosticEvent(event, mode, writeFailure)`.

2. If maintenance mode is not normal:
   - call `diagnosticSink.recordDiagnosticEvent(event, mode, writeFailure = null)`;
   - do not write to Room unless the architecture explicitly allows diagnostic writes during restore.

3. If fallback sink throws despite its contract:
   - catch and log privacy-safe warning.

4. For cancellation paths:
   - expose a helper that runs emission in `NonCancellable`.

---

## Step 2.3 — Create `NotificationDiagnosticEventFactory`

Move repeated event construction out of service.

Factory methods:

```text
receivedFromListener(...)
receivedFromRefresh(...)
restoreBlocked(...)
shutdownCancelled(...)
fastPrivacyDenied(...)
blockedPackage(...)
dedupeDuplicate(...)
filterRejected(...)
fullPrivacyDenied(...)
repositoryFailure(...)
repositoryCancellation(...)
pipelineOutcome(...)
```

Every method must accept:

- `correlationId`;
- `packageName`;
- optional `notificationKey`;
- optional `postTime`;
- source path: listener or refresh.

Every method must ensure:

- package name is hashed if stored in metadata;
- notification key is hashed;
- no raw `title`;
- no raw `text`;
- no raw `bigText`;
- no raw `combinedBody`;
- no raw extras.

---

## Step 2.4 — Create pipeline outcome mapper

After PR 1, create a mapper from `NotificationPipelineOutcome` to `DiagnosticEvent`.

Suggested mapping:

| Outcome | Stage | EventOutcome | Reason | Entity |
|---|---|---|---|---|
| `AutoAccepted` | `create` | `CREATED` | null | `Expense`, `expenseId` |
| `NeedsReview` | `review` | `NEEDS_REVIEW` | null | `PendingReview`, `reviewId` |
| `Duplicate` | `dedupe` | `DUPLICATE` | `DUPLICATE` | none |
| `ParserFailed` | `parse` | `FAILED_FINAL` | `PARSER_FAILED` | rawId if available |
| `AutoRejected` | `routing` | `DROPPED` | maybe `AUTO_REJECTED` if enum exists | rawId if available |
| `Dropped` | `drop` | `DROPPED` | mapped reason | none |
| `Error` | `error` | `FAILED_FINAL` | `UNKNOWN_ERROR` | none |

If `DiagnosticReasonCode.AUTO_REJECTED` does not exist, either:
- add it;
- or use `UNKNOWN_ERROR` only for true errors and add a safer generic `ROUTING_REJECTED`.

Do not map `AutoRejected` to `FILTER_REJECTED`; that is semantically wrong.

---

## Step 2.5 — Replace direct diagnostic writes in service

In `NotificationCaptureService.kt`, replace direct calls:

```kotlin
diagnosticEventWriter.emit(...)
```

with:

```kotlin
notificationDiagnosticEmitter.emit(...)
```

or:

```kotlin
notificationDiagnosticEmitter.emitReceivedThenTerminal(...)
```

Important paths to replace:

- restore blocked in `onNotificationPosted`;
- shutdown blocked in `onNotificationPosted`;
- fast privacy denied;
- blocked package;
- in-memory duplicate;
- filter rejected;
- full `PrivacyGate` denied;
- repository cancellation;
- repository exception;
- same refresh-path equivalents;
- `emitOrderedNotificationEvents(...)`.

After replacement, `emitOrderedNotificationEvents(...)` should either:
- move into `NotificationDiagnosticEmitter`;
- or delegate to it.

---

## Step 2.6 — Replace pipeline diagnostic writes

In `NotificationProcessingPipeline.kt`, replace:

```kotlin
writePipelineDiagnosticEvent(...)
```

internals so it uses the safe emitter or mapper.

Recommended:

- pipeline depends on `NotificationDiagnosticEmitter`, not raw `DiagnosticEventWriter`;
- or pipeline keeps depending on generic safe `DiagnosticEmitter`.

Avoid broad `runCatching` that drops events silently.

If diagnostic writing fails, fallback sink should record it.

---

## Step 2.7 — Ensure ordered `RECEIVED` then terminal

For early service exits:

```text
emitReceivedThenTerminal(received, terminal)
```

must write in one coroutine / one suspend function, sequentially.

For paths already inside a coroutine, call it directly.

For pre-coroutine paths, use `workTracker.launch(...)`, but inside that coroutine call the emitter sequentially.

If service is shutting down, use `NonCancellable` for cancellation terminal events.

---

## Step 2.8 — Define the event matrix

Add this matrix to tests and maybe docs.

| Path | Events expected |
|---|---|
| restore blocked | `RECEIVED`, terminal `BLOCKED/RESTORE_BLOCKED` |
| service shutting down | `RECEIVED`, terminal `CANCELLED/CANCELLED_BY_SYSTEM` |
| fast privacy denied | `RECEIVED`, terminal `DROPPED/PRIVACY_DENIED` |
| blocked package | `RECEIVED`, terminal `DROPPED/BLOCKED_PACKAGE` |
| in-memory duplicate | `RECEIVED`, terminal `DUPLICATE/DUPLICATE` |
| filter rejected | `RECEIVED`, terminal `DROPPED/FILTER_REJECTED` |
| full privacy denied | `RECEIVED`, terminal `DROPPED/PRIVACY_DENIED` |
| pipeline auto accepted | `RECEIVED`, terminal `CREATED` |
| pipeline needs review | `RECEIVED`, terminal `NEEDS_REVIEW` |
| pipeline duplicate | `RECEIVED`, terminal `DUPLICATE` |
| pipeline parser failed | `RECEIVED`, terminal `FAILED_FINAL/PARSER_FAILED` |
| pipeline auto rejected | `RECEIVED`, terminal `DROPPED/ROUTING_REJECTED` |
| pipeline error | `RECEIVED`, terminal `FAILED_FINAL/UNKNOWN_ERROR` |
| repository throws before outcome | `RECEIVED`, terminal `FAILED_RETRYABLE` or `FAILED_FINAL` |
| cancellation | `RECEIVED`, terminal `CANCELLED` |

---

## Step 2.9 — Add correlation-ID invariant

For each notification callback:

- generate one correlation ID;
- use same correlation ID for:
  - `RECEIVED`;
  - service early terminal event;
  - pipeline outcome event;
  - repository failure event.

Add test:

```text
All events emitted for one notification share same correlationId.
```

---

## Step 2.10 — Add terminal-count invariant

For each notification callback:

- exactly one terminal event should exist.

Exceptions:
- non-terminal side-effect/provenance events are allowed;
- but they must not have `isTerminal = true`.

Add tests:

```text
early drop => exactly 1 terminal
pipeline success => exactly 1 terminal
pipeline error => exactly 1 terminal
writer fallback => no duplicate terminal in writer + sink
```

---

## PR 2 tests

## Unit test group A — safe emitter

Use fake writer and fake sink.

1. Normal mode, writer succeeds:
   - writer receives event;
   - sink receives nothing.

2. Normal mode, writer throws:
   - writer attempted;
   - sink receives same event with failure.

3. Restore mode:
   - writer not called;
   - sink receives event.

4. Sink throws unexpectedly:
   - no exception escapes emitter.

5. Cancellation helper:
   - event is emitted even when parent coroutine is cancelled.

---

## Unit test group B — event factory privacy

For every factory method:

- metadata contains hashed `packageName`;
- metadata contains hashed `notificationKey`;
- metadata does not contain:
  - title;
  - text;
  - bigText;
  - combinedBody;
  - extras JSON.

---

## Unit test group C — service path event order

Use fake emitter recording list.

Test listener path:

1. restore blocked:
   - event[0] = `RECEIVED`;
   - event[1] = terminal restore blocked.

2. privacy denied:
   - event[0] = `RECEIVED`;
   - event[1] = terminal privacy denied.

3. filter rejected:
   - event[0] = `RECEIVED`;
   - event[1] = terminal filter rejected.

4. pipeline called:
   - service emits `RECEIVED`;
   - pipeline emits terminal;
   - no service duplicate terminal.

Repeat at least restore/privacy/filter for refresh path.

---

## Unit test group D — outcome mapper

For every `NotificationPipelineOutcome`, assert:

- correct stage;
- correct `EventOutcome`;
- correct reason code;
- correct entity type/id;
- terminal true;
- same correlation ID.

---

## PR 2 acceptance criteria

PR is complete when:

- notification diagnostics go through one safe emitter;
- `MaintenanceSafeDiagnosticSink` is used during restore/maintenance or writer failure;
- diagnostic failures are not silently lost;
- no direct raw `diagnosticEventWriter.emit(...)` remains in notification service/pipeline except inside the safe emitter;
- every early-exit path has `RECEIVED + terminal`;
- every pipeline path has `RECEIVED + one pipeline terminal`;
- correlation ID is consistent;
- exactly one terminal event per notification callback;
- no raw notification content is stored in diagnostic metadata;
- tests cover writer success, writer failure, restore mode, early exits, and pipeline outcomes.

---

# PR 3 — P1-P1-03: Correct textLines and MessagingStyle extraction

## Current problem

`EXTRA_TEXT_LINES` is handled.

But `EXTRA_MESSAGES` extraction is still likely wrong.

Current code attempts to read messages as `CharSequence`.

Real Android `Notification.EXTRA_MESSAGES` is documented as an array of `Notification.MessagingStyle.Message` bundles, not plain `CharSequence`.

So notifications where the transaction amount exists only inside MessagingStyle messages can still be missed.

---

## Files to modify

Primary:

- `NotificationCaptureService.kt`

Recommended new file:

- `app/src/main/java/com/yourname/expensetracker/domain/notification/NotificationExtractor.kt`

or if you want Android-specific placement:

- `app/src/main/java/com/yourname/expensetracker/service/NotificationExtractor.kt`

Tests:

- `NotificationExtractorTest.kt`
- possible Robolectric tests under `testDebugUnitTest`
- possible device/emulator tests under `connectedDebugAndroidTest`

---

## Step 3.1 — Move extraction out of service

Currently `NotificationTextParts` lives inside `NotificationCaptureService.kt`.

Move it to a dedicated extractor.

Suggested classes:

```text
NotificationTextParts
NotificationExtractor
```

Suggested responsibilities:

```text
NotificationExtractor.extract(notification: Notification): NotificationTextParts
NotificationExtractor.extract(extras: Bundle): NotificationTextParts
```

Keep service as orchestration only.

Benefits:

- easier unit testing;
- less service complexity;
- extraction can be tested across API levels;
- future PRs can reuse extractor in durable intake worker.

---

## Step 3.2 — Expand `NotificationTextParts`

Keep current fields:

- `title`
- `text`
- `bigText`
- `subText`
- `infoText`
- `summaryText`
- `effectiveBigText`
- `textLines`
- `messages`
- `combinedBody`

Consider adding:

- `titleBig` from `Notification.EXTRA_TITLE_BIG`;
- `conversationTitle` if available/relevant;
- `historicMessages` if you want to support `Notification.EXTRA_HISTORIC_MESSAGES`.

Recommended minimal addition:

```text
titleBig
```

because some expanded notifications use it.

Do not add raw sender names to persisted fields unless needed. If sender is included in combined body, that may increase privacy exposure. For transaction parsing, message text is usually enough.

---

## Step 3.3 — Implement proper `EXTRA_MESSAGES` extraction

Use this order:

1. Read raw parcelable array from `Notification.EXTRA_MESSAGES`.
2. On API level where available, convert bundles using:
   - `Notification.MessagingStyle.Message.getMessagesFromBundleArray(...)`
3. Extract each message’s `text`.
4. Fallback for older APIs:
   - if array item is `Bundle`, try known text keys;
   - if item is `CharSequence`, keep it for compatibility;
   - if item is another parcelable, do not stringify the entire object unless safe/useful.

Important Android API facts:

- `Notification.EXTRA_MESSAGES` is an array of MessagingStyle message bundles.
- `Notification.MessagingStyle.Message.getMessagesFromBundleArray(Parcelable[] bundles)` exists for converting bundle arrays into `Message` objects.
- `Message.getText()` returns the message text.

Compatibility guidance:

- For Android 13+ typed parcelable calls, use type-safe APIs where they match the actual stored type.
- Do not call `getParcelableArrayList(..., CharSequence::class.java)` for `EXTRA_MESSAGES`; that is the wrong target type.
- Prefer `getParcelableArray(Notification.EXTRA_MESSAGES)` or typed `Parcelable::class.java` where available, then convert.

---

## Step 3.4 — Add helper functions

Suggested helpers:

```text
extractTextLines(extras): List<String>
extractMessagingTexts(extras): List<String>
extractHistoricMessagingTexts(extras): List<String>
normalizeTextParts(parts): List<String>
buildCombinedBody(parts): String
```

Normalization rules:

1. Trim whitespace.
2. Drop blanks.
3. Collapse internal whitespace.
4. Preserve first-seen order.
5. Deduplicate exact duplicates.
6. Do not include object `toString()` for bundles/parcelables.
7. Do not include giant values.
8. Do not include bitmap/icon/person object dumps.

---

## Step 3.5 — Preserve downstream contract

After extraction:

- filter should use `parts.combinedBody`;
- content hash should use `parts.combinedBody`;
- `RawNotification.bigText` should use `parts.combinedBody`;
- parser should receive `bigText = parts.combinedBody`.

This preserves the previous “single canonical body” design.

Also update stale KDoc that says `effectiveBigText` is used for everything. The actual canonical downstream field should be `combinedBody`.

---

## Step 3.6 — Avoid widening privacy scope

This PR fixes extraction after existing pre-extraction gates.

Do not move extraction before privacy/package gates.

Service flow should remain:

1. restore check;
2. shutdown check;
3. fast privacy/package gate;
4. only then read extras/extract text;
5. filter;
6. full privacy gate until P1-P1-05 later fixes it;
7. process.

Do not persist newly extracted message text anywhere except through the existing storage-mode sanitizer.

---

## Step 3.7 — Handle duplicate text safely

Many notifications repeat the same content in:

- `EXTRA_TEXT`
- `EXTRA_BIG_TEXT`
- `EXTRA_TEXT_LINES`
- `EXTRA_MESSAGES`

`combinedBody` should not inflate repeated text.

Example:

```text
title = "Bank"
text = "Paid €12.30 at LIDL"
bigText = "Paid €12.30 at LIDL"
messages = ["Paid €12.30 at LIDL"]
combinedBody = "Bank Paid €12.30 at LIDL"
```

not:

```text
Bank Paid €12.30 at LIDL Paid €12.30 at LIDL Paid €12.30 at LIDL
```

---

## PR 3 tests

## Test group A — top-level fields

1. title only:
   - combined body includes title.

2. text only:
   - combined body includes text.

3. bigText only:
   - combined body includes bigText.

4. summary/info fallback:
   - `effectiveBigText` remains correct;
   - `combinedBody` includes relevant fields.

5. duplicate fields:
   - combined body dedupes repeated values.

---

## Test group B — `EXTRA_TEXT_LINES`

1. Amount only in text lines:
   - `textLines` contains transaction line;
   - `combinedBody` contains transaction line;
   - `NotificationFilter.shouldCapture(...)` returns true.

2. Multiple text lines:
   - order preserved;
   - blanks removed;
   - duplicates removed.

---

## Test group C — `EXTRA_MESSAGES`

1. Amount only in MessagingStyle message:
   - `messages` contains transaction message;
   - `combinedBody` contains transaction message;
   - filter captures it.

2. Multiple messages:
   - all message texts extracted;
   - order preserved.

3. Message with sender:
   - message text extracted;
   - sender not required in combined body unless deliberately chosen.

4. Message bundle array:
   - conversion through `getMessagesFromBundleArray(...)` works on supported API.

5. Legacy fallback bundle:
   - manual bundle extraction works when platform helper is unavailable.

6. CharSequence fallback:
   - still works if some OEM/app puts plain text there.

7. Bad parcelables:
   - extractor does not crash;
   - extractor does not include unsafe object dumps.

---

## Test group D — integration with service path

Use fake notification where:

- title/text/bigText are empty;
- amount exists only in `EXTRA_MESSAGES`.

Expected:

1. service passes filter;
2. `RawNotification.bigText`/processing body includes message amount;
3. parser receives body containing message amount;
4. outcome is not filter rejected.

Equivalent test for amount only in `EXTRA_TEXT_LINES`.

---

## Test group E — privacy/storage mode regression

For `RawStorageMode.DO_NOT_STORE`:

- extracted message text can be used in memory for parsing;
- raw message text is not persisted to:
  - raw notifications;
  - pending reviews;
  - diagnostics.

This overlaps with privacy PRs but should be guarded because PR 3 increases extracted content coverage.

---

## PR 3 acceptance criteria

PR is complete when:

- `NotificationTextParts` no longer lives inside the service, or at least extraction is testable separately.
- `EXTRA_TEXT_LINES` still works.
- `EXTRA_MESSAGES` works for real MessagingStyle bundle payloads.
- extraction does not cast message bundles to `CharSequence` as the primary path.
- `combinedBody` includes unique top-level fields, lines, and message texts.
- no unsafe parcelable/bundle object dumps are included.
- service still extracts only after pre-extraction gates.
- tests prove amount-only-in-message notifications are captured.

---

# Combined implementation order

Recommended order:

## Commit 1 — PR 1 prep

- Add top-level `NotificationPipelineOutcome`.
- Update pipeline imports and constructors.
- Remove nested outcome type or alias temporarily.

## Commit 2 — PR 1 repository return

- Change repository signatures.
- Return outcomes.
- Add `logOutcome(...)`.
- Update batch return type.

## Commit 3 — PR 1 service handling

- Capture outcome from repository.
- Replace unconditional success log.
- Add outcome-aware handling.
- Add tests.

## Commit 4 — PR 2 diagnostic emitter

- Add `NotificationDiagnosticEmitter`.
- Add fallback to `MaintenanceSafeDiagnosticSink`.
- Add tests for writer success/failure/restore.

## Commit 5 — PR 2 event factory and mapper

- Add event factory.
- Add outcome mapper.
- Replace pipeline diagnostic construction.
- Add mapper tests.

## Commit 6 — PR 2 service replacement

- Replace direct service diagnostic writes.
- Enforce `RECEIVED + one terminal`.
- Add event-order tests.

## Commit 7 — PR 3 extractor extraction

- Move `NotificationTextParts`.
- Add `NotificationExtractor`.
- Update service call sites.

## Commit 8 — PR 3 MessagingStyle support

- Implement correct `EXTRA_MESSAGES` extraction.
- Add `EXTRA_TEXT_LINES` regression tests.
- Add MessagingStyle tests.

## Commit 9 — docs/tracker update

Update:

- `PIPELINE_ISSUES_MASTER_TRACKER.md`
- relevant debugging-session docs

Set:

| ID | New status after PRs |
|---|---:|
| P1-P1-01 | Fixed |
| P1-P1-02 | Fixed, except durable intake caveat if P1-P1-07 remains |
| P1-P1-03 | Fixed |

Important wording for P1-P1-02:

```text
Diagnostic ledger is fixed for service/pipeline outcomes and maintenance-safe fallback.
It does not claim durable notification intake/process-death recovery; that remains P1-P1-07.
```

---

# Final validation commands

Run:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If emulator available:

```bash
./gradlew connectedDebugAndroidTest
```

Search checks:

```bash
grep -R "processAndSave(" app/src/main/java
grep -R "processAndSaveAll(" app/src/main/java
grep -R "ProcessingResult" app/src/main/java
grep -R "diagnosticEventWriter.emit" app/src/main/java/com/yourname/expensetracker/service
grep -R "getParcelableArrayList.*EXTRA_MESSAGES" app/src/main/java
grep -R "EXTRA_MESSAGES" app/src/main/java
```

Expected:

- no `ProcessingResult`;
- no service-level direct `diagnosticEventWriter.emit(...)` except inside the new safe emitter;
- no `EXTRA_MESSAGES` extraction as `CharSequence::class.java`;
- repository process methods return outcomes;
- service handles outcomes.

---

# Do-not-scope-for-these-PRs

Do not mix these fixes with:

- durable notification intake queue;
- full privacy gate before extraction;
- content-aware dedupe rewrite;
- currency fallback consolidation;
- location privacy/FGS fix;
- AI parser provenance contract.

Those are important, but they will bloat these PRs.

The only exception: PR 1 should prepare outcome data needed by later dedupe, but not implement the full dedupe fix.

---

# Sources checked

Repository/code sources:

- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `MaintenanceSafeDiagnosticSink.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt

Android primary docs:

- `Notification.EXTRA_MESSAGES`: https://developer.android.com/reference/android/app/Notification.html
- `Notification.MessagingStyle.Message`: https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message
- `Notification.MessagingStyle`: https://developer.android.com/reference/android/app/Notification.MessagingStyle
- `Bundle` typed parcelable APIs: https://developer.android.com/reference/android/os/Bundle