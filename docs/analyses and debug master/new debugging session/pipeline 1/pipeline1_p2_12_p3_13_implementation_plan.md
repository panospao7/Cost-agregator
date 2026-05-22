# Dedicated implementation plan — P2-12 and P3-13

Reviewed against commit: `e781c226862234ed412914884e98d22165a41a95`

Target issues:

| ID | Status | Theme |
|---|---:|---|
| P2-12 | TODO ONLY | AI fallback provenance incomplete; deterministic parser is called twice |
| P3-13 | TODO ONLY | `NotificationCaptureService` remains too large / multi-responsibility |

Recommended split:

1. **PR 1 — Parser provenance domain contract**
2. **PR 2 — Refactor `AppParserRegistry` to return parse outcome**
3. **PR 3 — Pipeline diagnostics/review metadata for parse provenance**
4. **PR 4 — Service decomposition phase 1: move pure notification utilities**
5. **PR 5 — Service decomposition phase 2: capture coordinator**
6. **PR 6 — Service decomposition phase 3: lifecycle/foreground/restart cleanup**

---

# Current code evidence

## P2-12

Current pipeline flow:

```kotlin
val parsed = parserRegistry.parseWithAiFallback(...)

val deterministicResult = parserRegistry.parse(...)

val parserSource =
    if (deterministicResult != null) "PARSER_USED"
    else if (parsed != null) "AI_FALLBACK_USED"
    else null
```

Problem:

- `parseWithAiFallback()` already calls deterministic `parse()` internally.
- Pipeline then calls `parse()` again only to infer provenance.
- This duplicates CPU work.
- `parseWithAiFallback()` returns only `ParsedTransaction?`.
- No typed outcome says:
  - deterministic parser succeeded;
  - which parser succeeded;
  - AI fallback was attempted;
  - AI fallback was skipped;
  - AI fallback failed;
  - provider/model used;
  - reason for failure/skip;
  - confidence source.

## P3-13

`NotificationCaptureService` currently owns too much:

- Android listener callbacks;
- foreground service startup;
- restart alarms;
- privacy cache;
- blocked-package cache;
- restore checks;
- diagnostics;
- extraction;
- filtering;
- in-memory dedupe;
- extras JSON building;
- raw/storage notification mapping;
- repository invocation;
- refresh processing;
- work tracking/shutdown draining.

This makes every Pipeline 1 fix risky because one class owns most stages.

---

# Implementation order

Recommended order:

1. **P2-12 first**
   - Small, bounded, improves parser truth.
   - Helps diagnostics and debugging.

2. **P3-13 after foundation PRs**
   - Ideally after:
     - P1-P1-01 outcome return;
     - P1-P1-02 diagnostic emitter;
     - P1-P1-03 extractor;
     - P1-P1-05 capture gate;
     - P2-08 dedupe;
     - P1-P1-07 intake queue if you choose to land it first.
   - If those are not landed, decomposition should create seams but avoid changing behavior.

---

# PR 1 — P2-12: Parser provenance domain contract

## Goal

Introduce typed parse outcome models without changing behavior yet.

## New files

Suggested package:

```text
app/src/main/java/com/yourname/expensetracker/domain/parser/provenance/
```

Files:

```text
NotificationParseOutcome.kt
ParserSource.kt
ParserAttempt.kt
AiFallbackStatus.kt
ParseFailureReason.kt
```

## Data model

```kotlin
sealed interface NotificationParseOutcome {
    val parsed: ParsedTransaction?
    val provenance: ParseProvenance

    data class Parsed(
        override val parsed: ParsedTransaction,
        override val provenance: ParseProvenance
    ) : NotificationParseOutcome

    data class NoParse(
        override val provenance: ParseProvenance
    ) : NotificationParseOutcome {
        override val parsed: ParsedTransaction? = null
    }
}
```

```kotlin
data class ParseProvenance(
    val source: ParserSource,
    val winningParserId: String?,
    val deterministicAttempted: Boolean,
    val deterministicParserId: String?,
    val deterministicSucceeded: Boolean,
    val aiAttempted: Boolean,
    val aiStatus: AiFallbackStatus,
    val aiProvider: String?,
    val aiModel: String?,
    val aiConfidence: Float?,
    val failureReason: ParseFailureReason?,
    val attempts: List<ParserAttempt>
)
```

```kotlin
enum class ParserSource {
    SPECIFIC_DETERMINISTIC,
    GENERIC_DETERMINISTIC,
    AI_FALLBACK,
    NONE
}
```

```kotlin
enum class AiFallbackStatus {
    NOT_NEEDED,
    SKIPPED_POLICY,
    SKIPPED_PRIVACY,
    UNAVAILABLE,
    ATTEMPTED_NO_RESULT,
    FAILED_EXCEPTION,
    SUCCEEDED
}
```

```kotlin
enum class ParseFailureReason {
    NO_DETERMINISTIC_MATCH,
    AI_NOT_ALLOWED_FOR_PACKAGE,
    AI_UNAVAILABLE,
    AI_EXCEPTION,
    AI_NO_RESULT,
    PARSER_EXCEPTION,
    NO_FINANCIAL_SIGNAL
}
```

```kotlin
data class ParserAttempt(
    val parserId: String,
    val parserType: ParserSource,
    val attempted: Boolean,
    val succeeded: Boolean,
    val failureReason: ParseFailureReason? = null
)
```

## Add parser ID support

In `AppNotificationParser`, add a default property:

```kotlin
val parserId: String
    get() = this::class.simpleName ?: "UnknownParser"
```

This avoids editing every parser immediately.

For `GenericTransactionParser`, since it is not an `AppNotificationParser`, use constant:

```kotlin
private const val GENERIC_PARSER_ID = "GenericTransactionParser"
```

## PR 1 tests

1. `ParseProvenance` can represent deterministic success.
2. `ParseProvenance` can represent AI success.
3. `ParseProvenance` can represent AI skipped.
4. `ParseProvenance` can represent AI exception.
5. Parser IDs are safe metadata, not raw text.

## Acceptance

- Models compile.
- No behavior change yet.
- No raw notification body/title in provenance model.

---

# PR 2 — P2-12: Refactor `AppParserRegistry`

## Goal

Replace:

```kotlin
parseWithAiFallback(...): ParsedTransaction?
```

with:

```kotlin
parseWithProvenance(...): NotificationParseOutcome
```

Keep old method temporarily as a compatibility wrapper.

## Step 2.1 — Add deterministic parse outcome

Add internal method:

```kotlin
private fun parseDeterministicWithProvenance(
    title: String?,
    text: String?,
    bigText: String?,
    subText: String?,
    packageName: String
): DeterministicParseResult
```

Suggested result:

```kotlin
private sealed interface DeterministicParseResult {
    data class Success(
        val parsed: ParsedTransaction,
        val parserId: String,
        val source: ParserSource,
        val attempts: List<ParserAttempt>
    ) : DeterministicParseResult

    data class Failure(
        val attempts: List<ParserAttempt>,
        val failureReason: ParseFailureReason
    ) : DeterministicParseResult
}
```

Order must stay current:

1. package-specific parser;
2. generic parser.

Do not change parser semantics.

## Step 2.2 — Add AI metadata result

Current AI interface:

```kotlin
suspend fun parse(...): ParsedTransaction?
```

Add non-breaking default method:

```kotlin
data class AiFallbackParseResult(
    val parsed: ParsedTransaction?,
    val status: AiFallbackStatus,
    val provider: String? = "ON_DEVICE_AI",
    val model: String? = null,
    val confidence: Float? = parsed?.confidence,
    val failureReason: ParseFailureReason? = null
)
```

In `NotificationFallbackParser`:

```kotlin
suspend fun parseWithMetadata(
    title: String?,
    text: String?,
    bigText: String?,
    packageName: String
): AiFallbackParseResult {
    return try {
        val parsed = parse(title, text, bigText, packageName)
        if (parsed != null) {
            AiFallbackParseResult(
                parsed = parsed,
                status = AiFallbackStatus.SUCCEEDED,
                confidence = parsed.confidence
            )
        } else {
            AiFallbackParseResult(
                parsed = null,
                status = AiFallbackStatus.ATTEMPTED_NO_RESULT,
                failureReason = ParseFailureReason.AI_NO_RESULT
            )
        }
    } catch (e: Exception) {
        AiFallbackParseResult(
            parsed = null,
            status = AiFallbackStatus.FAILED_EXCEPTION,
            failureReason = ParseFailureReason.AI_EXCEPTION
        )
    }
}
```

If the concrete implementation can expose real provider/model, override it there.

## Step 2.3 — Implement `parseWithProvenance`

Pseudo:

```kotlin
suspend fun parseWithProvenance(
    title: String?,
    text: String?,
    bigText: String?,
    subText: String?,
    packageName: String
): NotificationParseOutcome {
    val deterministic = parseDeterministicWithProvenance(...)

    if (deterministic is Success) {
        return NotificationParseOutcome.Parsed(
            parsed = deterministic.parsed,
            provenance = ParseProvenance(
                source = deterministic.source,
                winningParserId = deterministic.parserId,
                deterministicAttempted = true,
                deterministicParserId = deterministic.parserId,
                deterministicSucceeded = true,
                aiAttempted = false,
                aiStatus = AiFallbackStatus.NOT_NEEDED,
                aiProvider = null,
                aiModel = null,
                aiConfidence = null,
                failureReason = null,
                attempts = deterministic.attempts
            )
        )
    }

    if (!shouldAttemptAiFallback(packageName, title, text, bigText)) {
        return NotificationParseOutcome.NoParse(
            provenance = ParseProvenance(
                source = ParserSource.NONE,
                winningParserId = null,
                deterministicAttempted = true,
                deterministicParserId = null,
                deterministicSucceeded = false,
                aiAttempted = false,
                aiStatus = AiFallbackStatus.SKIPPED_POLICY,
                aiProvider = null,
                aiModel = null,
                aiConfidence = null,
                failureReason = ParseFailureReason.AI_NOT_ALLOWED_FOR_PACKAGE,
                attempts = deterministic.attempts
            )
        )
    }

    val ai = aiFallbackParser.parseWithMetadata(...)
    return if (ai.parsed != null) {
        NotificationParseOutcome.Parsed(
            parsed = ai.parsed,
            provenance = ParseProvenance(
                source = ParserSource.AI_FALLBACK,
                winningParserId = "NotificationFallbackParser",
                deterministicAttempted = true,
                deterministicParserId = null,
                deterministicSucceeded = false,
                aiAttempted = true,
                aiStatus = ai.status,
                aiProvider = ai.provider,
                aiModel = ai.model,
                aiConfidence = ai.confidence,
                failureReason = null,
                attempts = deterministic.attempts + ParserAttempt(
                    parserId = "NotificationFallbackParser",
                    parserType = ParserSource.AI_FALLBACK,
                    attempted = true,
                    succeeded = true
                )
            )
        )
    } else {
        NotificationParseOutcome.NoParse(
            provenance = ParseProvenance(
                source = ParserSource.NONE,
                winningParserId = null,
                deterministicAttempted = true,
                deterministicParserId = null,
                deterministicSucceeded = false,
                aiAttempted = true,
                aiStatus = ai.status,
                aiProvider = ai.provider,
                aiModel = ai.model,
                aiConfidence = ai.confidence,
                failureReason = ai.failureReason,
                attempts = deterministic.attempts + ParserAttempt(
                    parserId = "NotificationFallbackParser",
                    parserType = ParserSource.AI_FALLBACK,
                    attempted = true,
                    succeeded = false,
                    failureReason = ai.failureReason
                )
            )
        )
    }
}
```

## Step 2.4 — Keep compatibility wrapper

```kotlin
@Deprecated("Use parseWithProvenance() to avoid losing parser source metadata.")
suspend fun parseWithAiFallback(...): ParsedTransaction? =
    parseWithProvenance(...).parsed
```

This lets other call sites migrate gradually.

## PR 2 tests

1. Specific parser success:
   - generic not called if current behavior skips it;
   - AI not called;
   - source = `SPECIFIC_DETERMINISTIC`.

2. Specific parser null, generic success:
   - source = `GENERIC_DETERMINISTIC`.

3. Deterministic fail, AI skipped:
   - source = `NONE`;
   - `aiStatus = SKIPPED_POLICY`.

4. Deterministic fail, AI success:
   - source = `AI_FALLBACK`;
   - provider/model/confidence present if available.

5. AI throws:
   - no exception escapes registry;
   - outcome = `NoParse`;
   - `aiStatus = FAILED_EXCEPTION`.

6. No double parse:
   - deterministic parser fake call count is exactly 1.

## Acceptance

- `parseWithProvenance()` is the new primary API.
- Old API still works.
- AI fallback source is explicit.
- Deterministic parse is not repeated inside registry.

---

# PR 3 — P2-12: Pipeline diagnostics/review metadata

## Goal

Make `NotificationProcessingPipeline` consume parse provenance and delete the second deterministic parse.

## Step 3.1 — Replace pipeline parse block

Replace current flow:

```kotlin
val parsed = parserRegistry.parseWithAiFallback(...)
val deterministicResult = parserRegistry.parse(...)
val parserSource = ...
```

with:

```kotlin
val parseOutcome = parserRegistry.parseWithProvenance(
    title = notification.title,
    text = notification.text,
    bigText = notification.bigText,
    subText = notification.subText,
    packageName = notification.packageName
)

val parsed = parseOutcome.parsed
val provenance = parseOutcome.provenance
```

## Step 3.2 — Emit parse provenance diagnostics

Use the safe diagnostic emitter if P1-P1-02 has landed. Otherwise keep existing writer but do not add raw text.

Events:

| Condition | Event |
|---|---|
| deterministic success | `PARSE_SUCCEEDED` |
| AI skipped | `AI_FALLBACK_SKIPPED` |
| AI attempted | `AI_FALLBACK_ATTEMPTED` |
| AI success | `AI_FALLBACK_SUCCEEDED` |
| AI failure/no result | `AI_FALLBACK_FAILED` |
| no parser result | `PARSE_FAILED` |

Safe metadata:

```text
parserSource
winningParserId
deterministicSucceeded
aiAttempted
aiStatus
aiProvider
aiModel
aiConfidence
failureReason
packageNameHash
```

Do not store:

- title;
- text;
- bigText;
- combinedBody;
- prompt;
- AI raw response.

## Step 3.3 — Store provenance in review/expense metadata where possible

For pending reviews created after parser failure or low-confidence parse, include safe provenance fields.

If existing schema has no structured metadata, add to explanation only in safe summary form:

```text
"Parsed by AI fallback; provider=ON_DEVICE_AI; confidence=0.74"
```

For no parse:

```text
"Deterministic parser failed; AI fallback skipped by package policy"
```

Do not include raw notification text.

## Step 3.4 — Add provenance to `PreDbContext`

Current `PreDbContext` should carry parse provenance:

```kotlin
private data class PreDbContext(
    val parsed: ParsedTransaction,
    ...
    val parseProvenance: ParseProvenance
)
```

Then accepted/review paths can attach provenance to:

- transaction event metadata;
- source link metadata;
- diagnostic events.

## Step 3.5 — Remove stale TODO

Delete the TODO around the second parse.

## PR 3 tests

1. Pipeline calls `parseWithProvenance()` once.
2. Pipeline does not call `parse()` after `parseWithProvenance()`.
3. Deterministic success emits deterministic provenance.
4. AI success emits AI provenance.
5. AI skipped emits skipped reason.
6. Parser failed review includes safe provenance summary.
7. Diagnostics contain no raw notification text.
8. Auto-accepted transaction has parser source metadata.

## Acceptance

- No second deterministic parse.
- `P2-12` can explain parser source for every outcome.
- AI fallback used/skipped/failed is visible.
- No raw body/title/prompt/AI response in diagnostics.

---

# PR 4 — P3-13 phase 1: Move pure notification utilities out of service

## Goal

Start decomposition without changing behavior.

## Extract from `NotificationCaptureService`

Create:

```text
domain/notification/capture/NotificationTextParts.kt
domain/notification/capture/NotificationExtractor.kt
domain/notification/capture/NotificationContentHasher.kt
domain/notification/capture/NotificationExtrasJsonBuilder.kt
domain/notification/capture/NotificationEnvelope.kt
domain/notification/capture/NotificationPersistenceMapper.kt
```

## Component responsibilities

### `NotificationEnvelope`

Safe metadata only:

```kotlin
data class NotificationEnvelope(
    val packageName: String,
    val notificationKey: String?,
    val notificationKeyHash: String?,
    val postTime: Long,
    val correlationId: String,
    val source: NotificationCaptureSource
)
```

No extras/title/text.

### `NotificationExtractor`

Owns:

```kotlin
fun extract(notification: Notification): NotificationTextParts
fun extract(extras: Bundle): NotificationTextParts
```

This should include the P1-P1-03 MessagingStyle fix if already landed.

### `NotificationExtrasJsonBuilder`

Owns:

```kotlin
fun build(extras: Bundle, mode: RawStorageMode): String?
```

Rule:

- raw mode may serialize extras;
- metadata-only / do-not-store should not materialize all extras.

### `NotificationPersistenceMapper`

Owns building:

```kotlin
data class NotificationPersistencePayload(
    val processingNotification: RawNotification,
    val storageNotification: RawNotification
)
```

Input:

```kotlin
NotificationEnvelope
NotificationTextParts
RawStorageMode
appName
extrasJson
```

This removes raw/storage privacy mapping from the Android service.

## PR 4 tests

1. Envelope creation does not read extras.
2. Extractor returns same fields as old service.
3. Extras JSON builder respects raw-storage mode.
4. Persistence mapper creates correct processing vs storage notification for each `RawStorageMode`.
5. Content hash uses `combinedBody`.

## Acceptance

- Service no longer contains `NotificationTextParts`.
- Service no longer contains extras JSON builder.
- Service no longer creates `RawNotification` directly.
- Behavior remains equivalent.

---

# PR 5 — P3-13 phase 2: Add `NotificationCaptureCoordinator`

## Goal

Make service an Android adapter only.

## New class

```kotlin
class NotificationCaptureCoordinator @Inject constructor(
    private val captureGate: NotificationCaptureGate,
    private val extractor: NotificationExtractor,
    private val filter: NotificationFilter,
    private val deduper: NotificationCaptureDeduper,
    private val mapper: NotificationPersistenceMapper,
    private val repository: NotificationRepository,
    private val diagnostics: NotificationDiagnosticEmitter,
    private val appNameResolver: AppNameResolver,
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val timeProvider: TimeProvider
) {
    suspend fun handle(
        sbn: StatusBarNotification,
        source: NotificationCaptureSource
    ): NotificationCaptureResult
}
```

If P1-P1-07 intake queue is already implemented, replace repository with:

```kotlin
private val intakeCoordinator: NotificationIntakeCoordinator
```

## Coordinator flow

```text
1. Build safe envelope.
2. Emit RECEIVED.
3. Capture gate decides.
4. If denied, emit terminal and return.
5. Extract text.
6. Filter.
7. Dedupe.
8. Build persistence payload.
9. Call repository or intake coordinator.
10. Return typed capture result.
```

## Result model

```kotlin
sealed interface NotificationCaptureResult {
    data class Processed(
        val correlationId: String,
        val outcome: NotificationPipelineOutcome
    ) : NotificationCaptureResult

    data class Dropped(
        val correlationId: String,
        val reason: NotificationCaptureDropReason
    ) : NotificationCaptureResult

    data class Duplicate(
        val correlationId: String,
        val reason: String
    ) : NotificationCaptureResult

    data class Error(
        val correlationId: String,
        val throwable: Throwable
    ) : NotificationCaptureResult
}
```

## Service after PR 5

`NotificationCaptureService` should keep only:

- injection;
- service lifecycle state;
- `onCreate`;
- `onStartCommand`;
- `onListenerConnected`;
- `onNotificationPosted`;
- `onDestroy`;
- foreground notification start;
- refresh trigger.

Example:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return
    workTracker.launch(serviceScope) {
        coordinator.handle(sbn, NotificationCaptureSource.LISTENER)
    }
}
```

Refresh:

```kotlin
private fun refreshActiveNotifications() {
    activeNotifications.orEmpty().forEach { sbn ->
        workTracker.launch(serviceScope) {
            coordinator.handle(sbn, NotificationCaptureSource.REFRESH)
        }
    }
}
```

No separate `processNotificationBypassDedupe`.

## PR 5 tests

Use fake dependencies.

1. Gate denied => extractor not called.
2. Allowed + filter reject => repository not called.
3. Allowed + duplicate => repository not called.
4. Allowed + process => repository called once.
5. Refresh and listener share same coordinator.
6. Correlation ID is preserved.
7. Repository outcome is returned.
8. Cancellation does not get swallowed.

## Acceptance

- Normal path and refresh path use the same coordinator.
- Service does not directly call:
  - `NotificationFilter`;
  - `PrivacyGate`;
  - `blockedPackageDao`;
  - `repository.processAndSave`;
  - `diagnosticEventWriter.emit`;
  - `RawNotification(...)`.
- Service is mostly Android lifecycle glue.

---

# PR 6 — P3-13 phase 3: Lifecycle/foreground/restart cleanup

## Goal

Finish service decomposition by moving non-capture Android operational code out.

## Extract components

### `NotificationForegroundController`

Owns:

- notification channel creation;
- foreground notification creation;
- `startForeground`.

API:

```kotlin
fun start(service: Service)
fun ensureChannel()
```

### `NotificationListenerRestartScheduler`

Owns:

- restart alarm scheduling;
- restart alarm cancellation;
- `ServiceRestartReceiver` integration.

API:

```kotlin
fun schedule()
fun cancel()
```

### `NotificationServiceState`

Owns volatile booleans:

- running;
- connected;
- shutting down;
- pending refresh.

Optional, but makes tests easier.

### `NotificationRefreshCoordinator`

If refresh remains complex:

```kotlin
fun requestRefresh()
fun onListenerConnected()
```

It can call `NotificationCaptureCoordinator` for each active notification.

## PR 6 tests

1. `onStartCommand(ACTION_REFRESH_NOTIFICATIONS)` requests refresh.
2. If listener disconnected, refresh is deferred.
3. On listener connected, pending refresh runs.
4. `onDestroy()` stops accepting work and cancels service job.
5. Foreground controller creates notification channel only where needed.
6. Restart scheduler schedules correct alarm intent.

## Acceptance

- `NotificationCaptureService` contains no pure business logic.
- Service length is materially reduced.
- Components can be unit tested without Android listener instance.
- KDoc no longer claims behavior owned by service if it moved to coordinator.

---

# Final architecture target

After all P3-13 PRs:

```text
NotificationCaptureService
  └── Android callbacks only
      └── NotificationCaptureCoordinator
          ├── NotificationEnvelopeFactory
          ├── NotificationCaptureGate
          ├── NotificationExtractor
          ├── NotificationFilter / TransactionSignalDetector
          ├── NotificationCaptureDeduper
          ├── NotificationPersistenceMapper
          ├── NotificationRepository or NotificationIntakeCoordinator
          └── NotificationDiagnosticEmitter
```

`NotificationCaptureService` should not know:

- how text is extracted;
- how filters work;
- how privacy decisions are made;
- how raw/storage payloads differ;
- how diagnostics are built;
- how parser outcomes are interpreted.

---

# Validation commands

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
grep -R "parseWithAiFallback(" app/src/main/java
grep -R "parserRegistry.parse(" app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
grep -R "P2-12" app/src/main/java
grep -R "NotificationTextParts" app/src/main/java/com/yourname/expensetracker/service
grep -R "RawNotification(" app/src/main/java/com/yourname/expensetracker/service
grep -R "NotificationFilter.shouldCapture" app/src/main/java/com/yourname/expensetracker/service
grep -R "diagnosticEventWriter.emit" app/src/main/java/com/yourname/expensetracker/service
grep -R "processNotificationBypassDedupe" app/src/main/java
```

Expected after all PRs:

- pipeline uses `parseWithProvenance()`;
- no second deterministic parse;
- no stale P2-12 TODO;
- service does not contain extraction/mapping/filtering/diagnostic business logic;
- no `processNotificationBypassDedupe`.

---

# Tracker update

After PRs 1–3:

| ID | New status |
|---|---:|
| P2-12 | Fixed |

After PRs 4–6:

| ID | New status |
|---|---:|
| P3-13 | Fixed |

If durable intake P1-P1-07 is not landed yet, P3-13 can still be marked fixed if the service delegates to a repository-backed coordinator. Later, the coordinator can swap repository processing for intake processing without expanding the service again.

---

# Out of scope

Do not mix these with:

- durable intake implementation;
- currency fallback detector;
- full privacy gate;
- in-memory dedupe rewrite;
- location/foreground-service fix;
- app-wide AI/cloud policy overhaul.

Small exception:

- P2-12 should record AI provider/model if the existing on-device implementation can provide it.
- Do not introduce cloud AI fallback in this PR.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `NotificationProcessingPipeline.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `NotificationCaptureService.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `AppParserRegistry.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt
- `NotificationFallbackParser.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/ai/service/NotificationFallbackParser.kt