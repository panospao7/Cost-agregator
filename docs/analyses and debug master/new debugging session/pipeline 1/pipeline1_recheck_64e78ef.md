# Pipeline 1 deep re-evaluation — commit `64e78ef81433756bc457cfa97ef61a63139127e2`

Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/64e78ef81433756bc457cfa97ef61a63139127e2

Main files checked:
- `NotificationCaptureService.kt`
- `NotificationCaptureGate.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`
- `RawNotificationDao.kt`
- `BlockedPackageDao.kt`
- `RawNotificationInsertResult.kt`
- `RawNotification.kt`
- `NotificationIntakeEntity.kt`
- `NotificationIntakeDao.kt`
- `NotificationTextParts.kt`
- `NotificationDiagnosticEmitter.kt`
- `NotificationProcessingPipelineReliabilityTest.kt`

---

# Executive verdict

Commit `64e78ef` is a **real and meaningful repair pass** over `bdcd0b6`.

It successfully addresses several issues I previously flagged:

- listener callback no longer uses `runBlocking`;
- gate no longer allows capture when only one cache is loaded;
- blocked-package fallback exists;
- refresh no longer uses `processNotificationBypassDedupe`;
- in-memory dedupe check+put is atomic;
- content fingerprint now uses SHA-256;
- repository write barriers were added for `blockPackage`, `unblockPackage`, and `restoreSourceStatsSnapshot`;
- typed `RawNotificationInsertResult` now exists;
- insert helper now computes/stores resolved fingerprint even when incoming notification has null fingerprint.

However, the codebase is **still not fully clean**.

Big remaining issues:

1. **P1-P1-07 durable intake is still open.** Intake schema/DAO exist, but runtime capture still processes directly through service → repository → pipeline.
2. **P2-10 currency fallback is still partial.** Regex coverage expanded, but fallback still hardcodes `USD`/`EUR` and has no ambiguity/home-currency model.
3. **P2-12 parser provenance is still partial.** Pipeline still calls `parseWithAiFallback()` and no `ParseOutcome` / `parseWithProvenance()` exists.
4. **P3-13 service decomposition is still partial.** Some pieces moved, but service still owns orchestration, filtering, dedupe, mapping, extras, diagnostics.
5. **P1-NEW-14 remains open.** `RawNotification.isProcessed` still has a TODO saying it is never set.
6. **P1-NEW-16 remains open.** Notification pipeline still calls `locationProvider.getLastKnownLocation()`.
7. **Tests are not green.** Commit message says `compileDebugKotlin PASS`, but only `11/17` pipeline tests pass; six tests still need updates.

Recommended tracker stance:

```text
Repair A/B/C/D improved Pipeline 1 substantially, but do not mark the whole pipeline clean yet.
```

---

# High-level status table

| Issue | Status at `64e78ef` | Notes |
|---|---:|---|
| P1-P1-01 | ✅ Fixed | Outcome propagation still good. |
| P1-P1-02 | ✅ Mostly fixed | Safe diagnostics remain; some service-cancellation caveats. |
| P1-P1-03 | ✅ Mostly fixed | Messaging extraction exists; unsafe fallback `item.toString()` remains. |
| P1-P1-05 | ✅ Mostly fixed | Gate now self-heals and requires settings; minor diagnostic/policy caveats remain. |
| P1-P1-06 | ✅ Fixed | Missing repository write barriers now added. |
| P1-P1-07 | ❌ Open | Intake schema exists, but no runtime worker/coordinator wiring. |
| P2-08 | ✅ Mostly fixed | Refresh now calls `onNotificationPosted`; source labeling still wrong. |
| P2-09 | ⚠ Still needs focused verification | Not obviously addressed in this repair commit. |
| P2-10 | ⚠ Partial | More currencies, but still hardcoded fallback. |
| P2-11 | ⚠ Partial | Listener path better; public/batch raw paths still bypass sanitizer. |
| P2-12 | ⚠ Partial | No typed parse provenance. |
| P3-13 | ⚠ Partial | Service still large. |
| P1-NEW-09 | ✅ Mostly fixed | Safe emitter still present. |
| P1-NEW-10 | ✅ Mostly fixed | Blocked cache now self-heals/fallbacks. |
| P1-NEW-11 | ✅ Mostly fixed | Privacy startup false-drop greatly reduced. |
| P1-NEW-12 | ✅ Mostly fixed | Fingerprint pre-check fixed; batch/public entrypoint caveats remain. |
| P1-NEW-13 | ✅ Mostly fixed | Typed result exists; `-1L` only inside adapter/helper. |
| P1-NEW-14 | ❌ Open | `isProcessed` still dead/TODO. |
| P1-NEW-15 | ✅ Mostly fixed | Extras JSON guarded by storage mode in listener path. |
| P1-NEW-16 | ❌ Open | Pipeline still reads GPS/location. |
| P1-NEW-17 | ✅ Mostly fixed | Auto-accept audit no longer stores raw title/text in visible code. |
| P1-NEW-18 | ⚠ Partial | Failure diagnostic exists, but still no typed source-link result. |
| P1-NEW-19 | ✅ Mostly fixed | Pipeline diagnostic writes use safe emitter. |
| P1-NEW-20 | ✅ Mostly fixed | Content-aware atomic dedupe exists; key design still has tradeoffs. |
| P1-NEW-21 | ✅ Mostly fixed | Successful keys no longer removed immediately; TTL/classification still crude. |

---

# Detailed review

---

## 1. CaptureGate repair — P1-P1-05 / P1-NEW-10 / P1-NEW-11

## What improved

`NotificationCaptureGate.decide(...)` now does self-healing one-shot loads when either settings or blocked-package state is missing.

The important fix is here:

```kotlin
val needSettings = !settingsLoaded
val needBlocked = !blockedPackagesLoaded
```

Then it attempts:

```kotlin
privacySettingsRepository.getSettings()
blockedPackageDao.getAllPackageNamesOnce()
```

with a short timeout.

It also now blocks if settings still are not loaded:

```kotlin
if (!settingsLoaded) {
    return TemporarilyUnavailable(GATE_NOT_READY)
}
```

This fixes my previous concern where:

```text
settingsLoaded=false
blockedPackagesLoaded=true
```

could still allow capture.

It also now falls back to package-specific `isBlocked(packageName)` if the blocked package list still is not loaded:

```kotlin
val isBlocked = if (blockedPackagesLoaded) {
    packageName in blockedPackages
} else {
    withTimeoutOrNull(selfHealTimeoutMs) {
        blockedPackageDao.isBlocked(packageName)
    } ?: true
}
```

That fixes the previous blocked-package bypass.

## Remaining caveats

### Caveat 1 — cache failure is diagnosed as blocked package

If blocked-package list loading fails and `isBlocked(packageName)` also fails or times out, the code fails closed by returning `true`, then emits `BLOCKED_PACKAGE`.

That is privacy-safe, but diagnostically misleading.

Better distinction:

```text
BLOCKED_PACKAGE = package is actually in blocklist
PACKAGE_POLICY_UNAVAILABLE = DB/cache unavailable, fail-closed
```

Current behavior could confuse debugging:

```text
User asks: “Why was my bank blocked?”
Diagnostic says: BLOCKED_PACKAGE
Reality: blocked-package DAO timed out
```

### Caveat 2 — `GateState.Error("Failed to load both...")` wording is inaccurate

`warmUp()` now sets:

```kotlin
_state.value = if (settingsLoaded && blockedPackagesLoaded) {
    Ready
} else {
    Error("Failed to load both privacy settings and blocked packages")
}
```

If only one failed, the error message still says “both”.

Minor, but for debug accuracy use:

```text
Failed to load required gate state: settingsLoaded=false, blockedPackagesLoaded=true
```

### Caveat 3 — full privacy gate still happens twice

CaptureGate calls full `PrivacyGate.check(NOTIFICATION_CAPTURE)` before extraction.

Service then calls it again after filter:

```kotlin
when (privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)) { ... }
```

This is defense-in-depth, not a bug. But if the second check returns `NotApplicable`, the service currently proceeds:

```kotlin
else -> {
    Timber.d("Privacy check inconclusive ... proceeding")
}
```

Since the first gate already allowed, this is less severe than before. Still, long-term, the second check should use the same strict semantics as the gate or be removed to avoid divergent policy.

## Verdict

```text
P1-P1-05: mostly fixed.
P1-NEW-10: mostly fixed.
P1-NEW-11: mostly fixed.
```

Recommended small follow-up:

```text
Add PACKAGE_POLICY_UNAVAILABLE / PRIVACY_SETTINGS_NOT_READY diagnostic reasons.
Do not report DAO failure as real BLOCKED_PACKAGE.
```

---

# 2. Service runBlocking removal

## What improved

`onNotificationPosted()` no longer calls:

```kotlin
runBlocking { captureGate.decide(...) }
```

Now the callback creates safe metadata and launches coroutine work:

```kotlin
workTracker.launch(serviceScope) {
    notificationDiagnosticEmitter.emit(receivedEvent)
    val gateDecision = captureGate.decide(packageName, isShuttingDown)
    ...
}
```

This is a correct improvement.

## Remaining caveat

`workTracker.launch(serviceScope)` return value is not checked in the main listener path.

If the scope is already cancelled or work tracker stops accepting work, there is no fallback diagnostic.

Also, `onDestroy()` does not call `workTracker.stopAcceptingAndDrain(...)`; it sets `isShuttingDown=true` and cancels `serviceJob`.

So race remains:

```text
onNotificationPosted passes initial isShuttingDown check
onDestroy cancels serviceJob
work is cancelled before diagnostic/pipeline
```

This is exactly why durable intake is still needed.

## Verdict

```text
BUG-D fixed.
Shutdown-loss still covered by P1-P1-07, not fixed here.
```

---

# 3. Refresh unification — P2-08

## What improved

`processNotificationBypassDedupe` is now removed/commented as deleted.

Refresh now does:

```kotlin
activeNotifications.forEach { sbn -> onNotificationPosted(sbn) }
```

This means refresh uses the same:
- gate,
- extraction,
- dedupe,
- filter,
- processing,
- diagnostics.

That fixes the core bypass.

## Remaining caveat — diagnostics source is wrong

Calling `onNotificationPosted(sbn)` directly means manual refresh diagnostics still look like:

```text
stage = listener
sourceType = notification
```

There is no distinction between:
- real Android listener event,
- manual refresh event.

This is not a correctness bug, but it weakens debugging.

Better:

```kotlin
private fun enqueueNotificationCapture(
    sbn: StatusBarNotification,
    source: CaptureSource
)
```

Then:

```kotlin
onNotificationPosted -> source = LISTENER
refreshActiveNotifications -> source = REFRESH
```

## Verdict

```text
P2-08: mostly fixed.
```

Do not call it fully clean until source labeling is restored.

---

# 4. In-memory dedupe — P1-NEW-20 / P1-NEW-21

## What improved

The prior race is fixed:

```kotlin
val isDuplicate = synchronized(processedNotifications) {
    val last = processedNotifications[dedupeKey]
    if (last != null && (now - last) < DEDUP_WINDOW_MS) {
        true
    } else {
        processedNotifications[dedupeKey] = now
        false
    }
}
```

This is much better than non-atomic check+put.

The key is now content-aware and includes:
- package hash,
- notification key hash,
- postTime,
- SHA-256 content fingerprint prefix.

The service no longer blindly removes the dedupe key in `finally`. It removes only if:

```kotlin
pipelineOutcome == null || pipelineOutcome is Error
```

So successful/final outcomes retain dedupe briefly.

## Remaining caveats

### Caveat 1 — package/key hashing still uses `String.hashCode()`

```kotlin
val pkgHash = packageName.hashCode().toString(36)
val keyHash = notificationKey.hashCode().toString(36)
```

This is not cryptographic and has 32-bit collision risk.

Since this is only in-memory, it is not a major privacy issue. But it is weaker than the stated “hashed identifiers” design.

Better:

```kotlin
sha256(packageName).take(16)
sha256(notificationKey).take(16)
```

### Caveat 2 — content fingerprint is truncated to 16 hex chars

```kotlin
contentFingerprint.take(16)
```

That is 64 bits. Probably fine for in-memory dedupe, but if you want maximum correctness, keep full SHA-256 or at least 128 bits.

### Caveat 3 — including `postTime` is a tradeoff

Including `postTime` fixes this old bug:

```text
same sbn.key + same body but two legitimate posts at different times
```

But it weakens dedupe for this case:

```text
same real notification re-dispatched/updated with same body but different postTime
```

In that case:
- service dedupe misses it;
- `RawNotificationFingerprint` also includes timestamp, so DB fingerprint may miss it too;
- canonical expense duplicate may catch it after parse, but parser/AI work may still repeat.

Recommended future model:

```text
service in-flight key:
  packageHash + notificationKeyHash + contentFingerprint

durable raw fingerprint:
  package + normalized content + maybe postTime bucket

legitimate repeated same-content transactions:
  separated by durable transaction duplicate policy, not raw callback dedupe alone
```

### Caveat 4 — no dedicated deduper class

Dedupe logic still lives inside `NotificationCaptureService`.

This is acceptable for repair PR, but P3-13 still wants a `NotificationCaptureDeduper`.

## Verdict

```text
P1-NEW-20: mostly fixed.
P1-NEW-21: mostly fixed.
```

---

# 5. Typed raw insert result — P1-NEW-13 / P1-NEW-12

## What improved

A new sealed interface exists:

```kotlin
sealed interface RawNotificationInsertResult {
    data class Inserted(...)
    data class Duplicate(...)
}
```

`insertRawNotificationIfNotDuplicate(...)` now returns `RawNotificationInsertResult`.

Pipeline code now branches on:

```kotlin
when (val insertResult = insertRawNotificationIfNotDuplicate(...)) {
    is RawNotificationInsertResult.Duplicate -> ...
    is RawNotificationInsertResult.Inserted -> ...
}
```

The helper now always resolves a fingerprint:

```kotlin
val fingerprint = notification.dedupeFingerprint
    ?: RawNotificationFingerprint.compute(...)
```

and stores it:

```kotlin
storageNotification.copy(dedupeFingerprint = fingerprint)
```

This fixes the previous null-fingerprint insertion bug.

## Remaining caveats

### Caveat 1 — `-1L` still exists inside the adapter/helper

```kotlin
return if (insertId == -1L) {
    RawNotificationInsertResult.Duplicate(...)
}
```

This is acceptable. The issue was callers handling `-1L`; now only the adapter/helper knows Room’s sentinel.

### Caveat 2 — duplicate basis is not always propagated to final outcome

For parsed path:

```kotlin
RawDuplicate -> NotificationPipelineOutcome.Duplicate(..., "Raw duplicate in transaction")
```

The final outcome loses whether it was:
- precheck,
- insert conflict.

Parser-failed path keeps:

```kotlin
"Insert ${insertResult.basis.name}"
```

Not critical, but diagnostics would be better if `DuplicateBasis` propagated consistently.

### Caveat 3 — batch/public paths still bypass listener storage sanitizer

Repository still exposes:

```kotlin
processAndSave(notification)
processAndSaveAll(notifications)
```

Pipeline default still uses:

```kotlin
storageNotification = notification
```

So raw persistence policy is still listener-path-specific.

This is not part of typed insert, but it remains a privacy contract gap.

## Verdict

```text
P1-NEW-13: mostly fixed.
P1-NEW-12: mostly fixed.
```

---

# 6. Repository write barriers — P1-P1-06 / BUG-Q / BUG-R

## What improved

`NotificationRepository.blockPackage(...)` now checks:

```kotlin
writeBarrier.checkWritesAllowed("NotificationRepository.blockPackage")
```

`unblockPackage(...)` also checks.

`restoreSourceStatsSnapshot(...)` also checks:

```kotlin
writeBarrier.checkWritesAllowed("NotificationRepository.restoreSourceStatsSnapshot")
```

This fixes the specific write-barrier gaps I flagged.

## Verdict

```text
P1-P1-06: fixed with normal caveat that every future DAO mutation must follow same rule.
```

---

# 7. Diagnostics safety — P1-P1-02 / P1-NEW-09 / P1-NEW-19

## Current state

`NotificationDiagnosticEmitter` still routes:
- normal mode → `DiagnosticEventWriter`,
- maintenance/restore mode → `MaintenanceSafeDiagnosticSink`,
- writer failure → fallback sink.

Pipeline uses it.

Service uses it.

This is a good fix.

## Remaining caveats

### Caveat 1 — `emitOrderedNotificationEvents` still launches on `serviceScope`

```kotlin
workTracker.launch(serviceScope) {
    notificationDiagnosticEmitter.emitOrdered(received, terminal)
}
```

If the service scope is cancelled immediately, the ordered diagnostic can still disappear.

The emitter has:

```kotlin
emitOrderedNonCancellable(...)
```

but this helper does not use it.

### Caveat 2 — no fallback when `workTracker.launch` returns null

Current helper ignores launch result.

If future code calls `stopAcceptingAndDrain()`, or work tracker rejects new work, diagnostics are silently skipped.

Recommended:

```kotlin
val job = workTracker.launch(serviceScope) {
    notificationDiagnosticEmitter.emitOrdered(received, terminal)
}
if (job == null) {
    applicationScope.launch {
        notificationDiagnosticEmitter.emitOrderedNonCancellable(received, terminal)
    }
}
```

## Verdict

```text
Diagnostics: mostly fixed.
```

Remaining weakness is shutdown/cancellation durability, which again overlaps with P1-P1-07.

---

# 8. Durable notification intake — P1-P1-07

## Current state

Schema and DAO exist:

- `NotificationIntakeEntity`
- `NotificationIntakeDao`

But service still does:

```kotlin
processNotification(...)
repository.processAndSave(...)
```

There is no visible runtime path:

```text
listener -> intake row -> WorkManager -> worker -> recovery
```

Service `onDestroy()` still says:

```text
For full durability, a NotificationIntake table is planned
```

So the core failure remains:

```text
listener receives notification
service coroutine starts
service/process dies before raw DB write
notification may never be reposted
transaction lost
```

## Verdict

```text
P1-P1-07: still open.
```

Current status should be:

```text
Intake schema/DAO added, runtime durability not implemented.
```

Do not mark this fixed until you add:
- `NotificationIntakeCoordinator`,
- worker,
- recovery scheduler,
- service spool-before-process.

---

# 9. Currency fallback — P2-10

## Current state

Regexes now include many more currencies:

```text
EUR, USD, GBP, CHF, PLN, RON, TRY, CAD, AUD, JPY,
SEK, NOK, DKK, HUF, CZK, TL, kr, Ft, Kč/Kc
```

This is better.

## Still partial

Fallback still does:

```kotlin
fullText.contains("$") -> "USD"
else -> "EUR"
```

Both `detectOversizedAmountCandidate` and `detectTransactionSignalCandidate` still contain this pattern.

Problems:
- `$` can mean USD/CAD/AUD.
- `kr` can mean SEK/NOK/DKK.
- no home-currency provider;
- no `CurrencyResolution`;
- no ambiguity metadata;
- no confidence/basis;
- no shared detector used by filter and pipeline;
- defaulting to EUR remains.

## Verdict

```text
P2-10: partial.
```

This is a regex expansion, not the canonical money detector from the plan.

---

# 10. Parser provenance — P2-12

## Current state

Pipeline still calls:

```kotlin
parserRegistry.parseWithAiFallback(...)
```

There is still no:

```kotlin
parseWithProvenance(...)
ParseOutcome
ParseProvenance
AiFallbackStatus
ParserAttempt
```

The pipeline comment still says:

```text
A full ParseOutcome contract would carry provenance metadata directly...
```

So this is explicitly not finished.

## Verdict

```text
P2-12: partial.
```

Good:
- no obvious second deterministic parse in pipeline.

Still missing:
- typed provenance,
- AI attempted/skipped/failed,
- provider/model,
- confidence source,
- deterministic parser ID.

---

# 11. Service decomposition — P3-13

## Current state

Some extraction was moved to `NotificationTextParts`.

But `NotificationCaptureService` still owns:

- Android service lifecycle,
- foreground service,
- restart alarm,
- listener callback,
- refresh,
- gate call,
- diagnostics,
- extraction orchestration,
- in-memory dedupe,
- filtering,
- storage mode handling,
- extras JSON building,
- raw/storage notification mapping,
- repository call,
- shutdown behavior.

Also the service KDoc is stale: it still mentions `processNotificationBypassDedupe` even though the method was deleted.

## Verdict

```text
P3-13: partial.
```

Recommended next decomposition:
- `NotificationCaptureCoordinator`
- `NotificationCaptureDeduper`
- `NotificationPersistenceMapper`
- `NotificationExtrasPersistencePolicy`
- `NotificationForegroundController`
- `NotificationRefreshCoordinator`

---

# 12. RawNotification.isProcessed — P1-NEW-14

## Current state

`RawNotification.kt` still says:

```kotlin
// TODO P1-CURRENT-015: isProcessed is never set to true anywhere in the codebase.
// Either update it after pipeline processing or remove the field in a migration.
val isProcessed: Boolean = false
```

So this issue remains open.

## Verdict

```text
P1-NEW-14: open.
```

Decide one:
1. mark terminal raw rows processed, or
2. deprecate/remove field once intake status becomes source of truth.

---

# 13. Extras JSON privacy — P1-NEW-15

## Current state

Listener path is improved.

Service now gets settings before extras JSON persistence:

```kotlin
val extrasJson = when (settings.rawNotificationStorageMode) {
    STORE_RAW -> buildExtrasJson(extras)
    STORE_REDACTED -> """{"redacted":true}"""
    STORE_METADATA_ONLY -> null
    DO_NOT_STORE -> null
}
```

This fixes the main listener-path privacy issue.

## Remaining caveats

### Caveat 1 — `buildExtrasJson` still uses broad `value.toString()`

Only in `STORE_RAW`, so acceptable if raw mode truly allows raw extras.

But it can still serialize object dumps from arbitrary extras values.

### Caveat 2 — public/batch paths still bypass service policy

Any path that constructs `RawNotification` outside the service can still pass raw `extrasJson`.

## Verdict

```text
P1-NEW-15: mostly fixed for listener path.
```

---

# 14. Messaging extraction — P1-P1-03

## Current state

`NotificationTextParts.extract(...)` uses:

```kotlin
Notification.MessagingStyle.Message.getMessagesFromBundleArray(parcelables)
```

Good.

## Remaining issue

Fallback still does:

```kotlin
else -> item?.toString()?.takeIf { it.isNotBlank() }
```

That can pollute parser input/dedupe hash with object dumps.

Better:

```kotlin
else -> null
```

Also `computeNotificationContentHash(parts)` still exists and returns:

```kotlin
parts.combinedBody.hashCode()
```

The service now uses the new SHA-256 helper, so this old helper is likely stale. Remove or deprecate to avoid future accidental use.

## Verdict

```text
P1-P1-03: mostly fixed, with fallback cleanup.
```

---

# 15. Location/GPS — P1-NEW-16

## Current state

This remains a real issue.

Pipeline still injects:

```kotlin
ForegroundLocationProvider
```

and calls:

```kotlin
locationProvider.getLastKnownLocation()
```

inside notification processing.

The comment says provider internally gates privacy/permission, but this does not fully resolve the architectural mismatch:

- service KDoc says notification capture does not read location and uses `FOREGROUND_SERVICE_TYPE_DATA_SYNC`;
- pipeline still performs best-effort GPS enrichment;
- no explicit notification-pipeline location policy exists;
- no diagnostic differentiates disabled/denied/unavailable;
- service/foreground type remains potentially inconsistent with actual behavior.

## Verdict

```text
P1-NEW-16: open.
```

Recommended fix remains:

```text
Remove GPS from notification pipeline by default,
or introduce explicit NotificationLocationContextProvider with privacy/permission/FGS policy.
```

---

# 16. Auto-accept audit raw title — P1-NEW-17

## Current state

Visible auto-accept audit is improved.

Audit metadata now appears to contain:

```kotlin
confidence
routingDecision
rawNotificationId
```

and later:

```kotlin
packageName
amount
merchant
```

I did not see raw `notification.title`/`text` stored in the auto-accept audit event in the visible code.

However, the KDoc still says:

```text
sanitised notification payload
```

and the pipeline still creates:

```kotlin
val auditReason = JSONObject().apply {
    put("packageName", notification.packageName)
    put("amount", ...)
    put("merchant", ...)
}.toString()
```

Potential issue:
- `packageName` and merchant are not raw notification title/text, so this is much safer.
- But `auditReason` appears unused in the visible event write, so it may be dead local data.

## Verdict

```text
P1-NEW-17: mostly fixed.
```

Cleanup:
- remove unused `auditReason` if it is not used;
- update KDoc to say “safe metadata only; no raw notification title/text/body.”

---

# 17. Source-link failures — P1-NEW-18

## Current state

The old silent swallow is improved.

`writeNotificationDedupeSourceLink(...)` now catches failure and emits:

```kotlin
SOURCE_LINK_FAILED
```

via diagnostic emitter.

Good.

## Still partial

There is still no typed result:

```kotlin
SourceLinkWriteResult.Created
SourceLinkWriteResult.AlreadyExists
SourceLinkWriteResult.Failed
```

The helper still returns `Unit`, so the caller cannot know source-link failure happened.

Also source-link failures in `pendingReviewSourceLinkService.linkSourcesForReview(...)` paths are treated as fatal:

```kotlin
if (linkResult.hasFatalFailure) {
    throw IllegalStateException(...)
}
```

That may be intended, but it differs from dedupe source-link “best effort” behavior. Make this policy explicit.

## Verdict

```text
P1-NEW-18: partial.
```

---

# 18. Tests

Commit message says:

```text
compileDebugKotlin PASS
Tests: 11/17 pipeline tests pass
```

The test file still has old expectations:

```kotlin
coVerify { rawDao.exists(...) }
```

But pipeline now uses fingerprint methods:

```kotlin
findIdByDedupeFingerprint(...)
existsByDedupeFingerprint(...)
```

So those tests are stale.

## Verdict

```text
Build may compile, but test suite is not green.
```

Before marking issues fixed, update tests:

- exact raw duplicate test → fingerprint duplicate test;
- insert test → verify `findIdByDedupeFingerprint` then `insertOrIgnore(storage.copy(dedupeFingerprint=...))`;
- metadata-only duplicate → parser not called;
- insert conflict → typed duplicate;
- service refresh → same dedupe path;
- gate self-heal → settings/blocked missing scenarios.

---

# Issue-by-issue final recommendation

## Mark fixed / mostly fixed now

| Issue | Suggested status |
|---|---:|
| P1-P1-01 | ✅ Fixed |
| P1-P1-02 | ✅ Mostly fixed |
| P1-P1-03 | ✅ Mostly fixed |
| P1-P1-05 | ✅ Mostly fixed |
| P1-P1-06 | ✅ Fixed |
| P2-08 | ✅ Mostly fixed |
| P1-NEW-09 | ✅ Fixed |
| P1-NEW-10 | ✅ Mostly fixed |
| P1-NEW-11 | ✅ Mostly fixed |
| P1-NEW-12 | ✅ Mostly fixed |
| P1-NEW-13 | ✅ Mostly fixed |
| P1-NEW-15 | ✅ Mostly fixed |
| P1-NEW-17 | ✅ Mostly fixed |
| P1-NEW-19 | ✅ Fixed |
| P1-NEW-20 | ✅ Mostly fixed |
| P1-NEW-21 | ✅ Mostly fixed |

## Keep partial/open

| Issue | Suggested status |
|---|---:|
| P1-P1-07 | ❌ Open |
| P2-09 | ⚠ Needs verification / likely partial |
| P2-10 | ⚠ Partial |
| P2-11 | ⚠ Partial |
| P2-12 | ⚠ Partial |
| P3-13 | ⚠ Partial |
| P1-NEW-14 | ❌ Open |
| P1-NEW-16 | ❌ Open |
| P1-NEW-18 | ⚠ Partial |

---

# New / remaining gaps found at `64e78ef`

## GAP-64-01 — refresh diagnostics are mislabeled as listener events

Because refresh calls:

```kotlin
onNotificationPosted(sbn)
```

diagnostics cannot distinguish manual refresh from actual listener callback.

Fix:

```kotlin
private fun enqueueNotificationCapture(sbn, source: CaptureSource)
```

## GAP-64-02 — gate fail-closed package-policy failure is mislabeled as blocked package

If blocklist DB fails, code returns `BLOCKED_PACKAGE`.

Fix:
- add `PACKAGE_POLICY_UNAVAILABLE`,
- emit `FAILED_RETRYABLE` or `BLOCKED` with exact reason.

## GAP-64-03 — dedupe uses `String.hashCode()` for package/key hashes

In-memory only, but still weak.

Fix:
- SHA-256/HMAC package/key hashes.

## GAP-64-04 — dedupe includes `postTime`, weakening duplicate callback suppression

Same body + same key + changed `postTime` will bypass in-memory dedupe and raw fingerprint dedupe.

Fix:
- use content-only in-flight key,
- use postTime bucket only if needed,
- or rely on durable transaction duplicate detection after parser.

## GAP-64-05 — stale `computeNotificationContentHash()` still returns `hashCode()`

Remove or deprecate to avoid future reuse.

## GAP-64-06 — service still processes directly; intake schema is unused

P1-P1-07 remains the major unresolved user-loss bug.

## GAP-64-07 — location provider still called from notification pipeline

P1-NEW-16 remains unresolved.

## GAP-64-08 — public/batch repository paths bypass privacy storage mapper

Listener path has sanitized storage payload; `process(notification)` and `processBatch` still default storage to raw notification.

## GAP-64-09 — six reliability tests are stale/failing

Tracker should not call this fully validated until tests are updated and green.

---

# Recommended next repair sequence

## PR Next-1 — Green the tests

Update stale tests after typed insert/fingerprint changes.

Acceptance:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```

must pass.

## PR Next-2 — Durable intake runtime

Fix P1-P1-07 for real.

Add:
- `NotificationIntakeCoordinator`,
- service inserts intake row before pipeline work,
- `NotificationIntakeWorker`,
- recovery scheduler,
- stale `PROCESSING` release,
- payload purge policy.

## PR Next-3 — Location policy

Fix P1-NEW-16.

Preferred:
- remove notification GPS enrichment entirely,
- or use explicit `NotificationLocationContextProvider`.

## PR Next-4 — Parser provenance

Fix P2-12.

Add:
- `ParseOutcome`,
- `ParseProvenance`,
- `parseWithProvenance()`.

## PR Next-5 — Currency detector

Fix P2-10.

Add:
- shared money detector,
- home-currency provider,
- ambiguity handling for `$` and `kr`,
- no silent EUR fallback.

## PR Next-6 — Service decomposition

Fix P3-13.

Extract:
- capture coordinator,
- deduper,
- mapper,
- extras policy,
- foreground controller,
- refresh coordinator.

---

# Bottom line

`64e78ef` successfully fixes the main **Repair A/B/C/D** problems I called out after `bdcd0b6`:

```text
CaptureGate correctness: mostly fixed.
runBlocking removal: fixed.
Refresh bypass: mostly fixed.
Atomic content-aware dedupe: mostly fixed.
Typed insert: mostly fixed.
Write barriers: fixed.
```

But Pipeline 1 is still not “done” because:

```text
Durable intake is schema-only.
Currency fallback is still heuristic/defaulting.
Parser provenance is still missing.
Location is still read.
RawNotification.isProcessed is still dead.
Tests are not fully green.
```

I would mark the repair commit as a **successful hardening pass**, but keep Pipeline 1 in **PARTIAL / not clean** state until the next set lands.