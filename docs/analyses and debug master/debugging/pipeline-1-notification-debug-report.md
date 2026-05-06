# Pipeline 1 Debugging Report — Notification Capture → Expense → Dashboard

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`

## Executive summary

Pipeline 1 is architecturally correct on paper, but runtime reliability is fragile.

Expected flow:

```text
Android NotificationListener
→ NotificationCaptureService
→ NotificationFilter / PrivacyGate / RestoreMaintenanceMode
→ RawNotification
→ NotificationProcessingPipeline
→ AppParserRegistry
→ ConfidenceRouter
→ PendingReview OR TransactionLifecycleCoordinator
→ Expense / TransactionEvent
→ Dashboard / Analytics / Budget
```

Most likely reasons the app “does not read notifications”:

1. **NotificationListenerService lifecycle is being treated like a normal keep-alive foreground service.**
2. **Notifications are filtered before all useful notification fields are extracted.**
3. **There is no persistent drop-reason telemetry, so received-but-dropped looks identical to not-received.**
4. **Restore/privacy gates can silently block capture.**
5. **Package lists may be stale or real bank text may live in `infoText`, `summaryText`, `textLines`, or message extras.**
6. **The pipeline has nested transaction / side-effect timing risks after the move to lifecycle coordinators.**

My highest-confidence bug is still:

> `NotificationCaptureService.onNotificationPosted()` calls `NotificationFilter.shouldCapture()` using only `title`, `text`, and `bigText`, but later `processNotification()` resolves `infoText` and `summaryText`. So some real bank notifications can be rejected before the app ever sees their actual transaction text.

---

# 1. Architecture contract

Based on `DEPENDENCY_MAP.md`, Pipeline 1 is supposed to be:

```text
NotificationCaptureService
→ NotificationFilter
→ PrivacyGate.check(NOTIFICATION_CAPTURE)
→ RestoreMaintenanceMode
→ NotificationProcessingPipeline
→ AppParserRegistry
→ GreekBankParser / RevolutParser / SmsParser / GoogleWalletParser / GenericParser
→ ConfidenceRouter
→ NotificationRepository / ReviewQueueRepository
→ TransactionLifecycleCoordinator
→ ExpenseDao / TransactionEventDao
→ DashboardRepository / AnalyticsRepository / MultiCurrencyRepository
```

This is the right shape.

The problem is not the high-level design. The problem is that some runtime seams are unsafe:

- Android listener lifecycle
- notification field extraction
- silent gates
- insufficient diagnostics
- possible stale package mapping
- transaction boundaries around the coordinator

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 2. Actual code trace

## 2.1 Android manifest

The service is declared as:

```xml
<service
  android:name=".service.NotificationCaptureService"
  android:exported="true"
  android:foregroundServiceType="dataSync"
  android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter>
    <action android:name="android.service.notification.NotificationListenerService" />
  </intent-filter>
</service>
```

The app also declares:

```xml
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS
RECEIVE_BOOT_COMPLETED
WAKE_LOCK
```

Observations:

- Required listener permission/action exist.
- `android:exported="true"` is unnecessary for a notification listener and should probably be `false`.
- `foregroundServiceType="dataSync"` is part of the current keep-alive strategy, but notification listener callbacks are system-bound, not a normal periodic background service model.
- Android 12+ restricts background foreground-service starts.
- Android 15 adds more restrictions around BOOT_COMPLETED + foreground-service types.

Source:  
https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/AndroidManifest.xml  
Android NLS docs: https://developer.android.com/reference/android/service/notification/NotificationListenerService  
Android FGS background restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start  
Android 15 behavior changes: https://developer.android.com/about/versions/15/behavior-changes-15

---

# 3. Major findings

## Finding P0-1 — The listener lifecycle strategy is risky

`NotificationCaptureService` extends `NotificationListenerService`, but it also behaves like a foreground keep-alive service:

- `onStartCommand()` calls `startForegroundWithNotification()`
- returns `START_STICKY`
- `onCreate()` schedules a repeating restart alarm
- `BootReceiver` starts it after boot/package replacement
- `ServiceRestartReceiver` calls `startForegroundService()` every 15 minutes
- `onListenerDisconnected()` restarts foreground service while waiting for rebind

This can create misleading states:

```text
foreground service alive
but listener not connected
therefore no onNotificationPosted callbacks
```

A notification listener should primarily rely on:

```text
user grants notification listener access
→ system binds service
→ onListenerConnected()
→ onNotificationPosted()
→ requestRebind() on disconnect
```

Periodic foreground-service restarts do not guarantee listener binding.

## Recommendation

Do not use periodic restart alarms as the reliability mechanism.

Change strategy:

1. Keep the service as `NotificationListenerService`.
2. Use `onListenerConnected()` / `onListenerDisconnected()` as truth.
3. Use `requestRebind()` on disconnect.
4. Remove or disable repeating `ServiceRestartReceiver`.
5. Stop `BootReceiver` from trying to start the listener as a foreground service.
6. Add listener-health status in the app.

Relevant files:

- `NotificationCaptureService.kt`
- `BootReceiver.kt`
- `ServiceRestartReceiver.kt`
- `AndroidManifest.xml`

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/receiver/BootReceiver.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/receiver/ServiceRestartReceiver.kt

---

## Finding P0-2 — Filtering happens before full text extraction

Current `onNotificationPosted()` extracts:

```kotlin
title = EXTRA_TITLE
text = EXTRA_TEXT
bigText = EXTRA_BIG_TEXT
```

Then immediately:

```kotlin
if (!NotificationFilter.shouldCapture(packageName, title, text, bigText)) return
```

But later `processNotification()` extracts:

```kotlin
subText = EXTRA_SUB_TEXT
infoText = EXTRA_INFO_TEXT
summaryText = EXTRA_SUMMARY_TEXT
effectiveBigText = bigText ?: infoText ?: summaryText
```

Problem:

If the bank app puts transaction text in:

- `EXTRA_INFO_TEXT`
- `EXTRA_SUMMARY_TEXT`
- `EXTRA_SUB_TEXT`
- `EXTRA_TEXT_LINES`
- `EXTRA_MESSAGES`
- custom extras

then the notification can be rejected before `processNotification()` sees the actual content.

This is especially likely for banking apps, SMS apps, Gmail, wallet apps, and rich notifications.

## Recommendation

Create one extraction function and use it before filtering, hashing, raw persistence, parser input, and debug logging.

Required extracted fields:

```text
title
text
bigText
subText
infoText
summaryText
textLines
messages
extras keys
combinedTextPreview
```

Then call:

```kotlin
NotificationFilter.shouldCapture(
  packageName = packageName,
  title = parts.title,
  text = parts.text,
  bigText = parts.effectiveBigText
)
```

Also pass the same `effectiveBigText` into:

- content hash
- raw notification fingerprint
- parser
- raw DB insert

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt

---

## Finding P0-3 — Drop reasons are not persisted

`ServiceDiagnostics` currently tracks mostly:

- service start count
- service killed count
- listener disconnect count
- last restart time
- last kill time

It does **not** persist:

- listener connected time
- last `onNotificationPosted`
- last package seen
- last extras keys
- last filter decision
- privacy decision
- restore mode
- parser result
- DB insert result
- last exception
- last successful raw notification
- last successful expense/review

So right now:

```text
Android never delivered notification
```

and

```text
Android delivered notification, app dropped it by filter/privacy/restore/dedupe/parser
```

look almost the same.

## Recommendation

Add a diagnostic ring buffer/table:

```kotlin
enum class NotificationCaptureStage {
    POSTED_CALLBACK,
    DROPPED_MAINTENANCE,
    DROPPED_FILTER,
    DROPPED_PRIVACY,
    DROPPED_BLOCKED_PACKAGE,
    DROPPED_MEMORY_DEDUPE,
    RAW_INSERT_ATTEMPT,
    RAW_DUPLICATE_DB,
    PARSER_NULL,
    PENDING_REVIEW_CREATED,
    EXPENSE_CREATED,
    PIPELINE_ERROR
}
```

Record:

```text
timestamp
packageName
appLabel
title/text/effectiveBigText preview
extras keys
stage
reason
exception message
privacy decision
restore mode
listener connected yes/no
```

Then expose this in DebugScreen.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/debug/ServiceDiagnostics.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt

---

## Finding P1-1 — Restore maintenance mode can silently block notifications

`NotificationCaptureService.onNotificationPosted()` checks:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) return
```

`RestoreMaintenanceMode.isWritesAllowed()` allows only:

```text
NORMAL
BACKUP_EXPORTING
```

All restore states block writes.

This is correct architecturally, but it is currently silent from the user's perspective.

`AppStartupCoordinator` resets non-normal modes on startup, but if debugging notification capture, you still need to display:

```text
current restore mode
writes allowed yes/no
last restore transition
```

## Recommendation

Debug screen should show:

```text
Restore mode: NORMAL / RESTORE_STAGING / RESTORE_COMPLETE_RESTART_REQUIRED
Notification capture blocked by restore: yes/no
```

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

---

## Finding P1-2 — Privacy gate can silently block notification capture

`NotificationPrivacyGate` allows notification capture by default, because `PrivacySettingsRepositoryImpl` defaults:

```kotlin
notificationCaptureEnabled = true
```

But if the setting was toggled off or migrated incorrectly, every notification is dropped.

The gate logs audit decisions, which is good, but the notification service does not persist a capture-stage drop reason.

## Recommendation

Show in DebugScreen:

```text
Notification capture privacy setting: enabled/disabled
Last privacy decision: allowed/denied
Last privacy denial reason
```

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

---

## Finding P1-3 — In-memory dedupe uses incomplete content

The service computes:

```kotlin
contentHash = computeNotificationContentHash(title, text, bigText)
dedupeKey = "${sbn.key}:$contentHash"
```

But this ignores the fallback fields.

If a notification updates its amount/merchant in `infoText`, `summaryText`, or `textLines`, but `title/text/bigText` stay the same, the service can treat the new content as duplicate within the 5-second window.

## Recommendation

Use the same extracted/effective content for:

```text
filter
memory dedupe
raw dedupe fingerprint
parser
debug preview
```

This removes an entire class of inconsistencies.

---

## Finding P1-4 — Package list may be stale

`NotificationFilter.FINANCE_PACKAGES` includes:

```text
com.revolut.revolut
com.google.android.apps.walletnfcrel
com.google.android.apps.nbu.paisa.user
gr.nbg.mobilebanking
mbanking.NBG
com.eurobank.mobile
gr.alpha.mobile
com.winbank.mobile
```

If a Greek bank changed package name, the notification falls to heuristic mode.

Heuristic mode requires:

```text
amount/currency signal
AND financial keyword
```

That can drop valid short bank notifications such as:

```text
POS 12,40 EUR SKLAVENITIS
-12,40€ ****1234 ΣΚΛΑΒΕΝΙΤΗΣ
```

because they may lack the keyword list.

## Recommendation

Add a package discovery screen:

```text
last 50 notification packages seen
app label
filter passed yes/no
drop reason
extras keys
sample preview
```

Then update the finance package list from real device logs.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt

---

## Finding P1-5 — Debug simulation uses a misleading package

`DebugViewModel.simulateDepositNotification()` uses examples like:

```kotlin
Triple("com.revolut", "Revolut", "deposit €500.00 from EMPLOYER")
```

But the real Revolut package in `NotificationFilter` and `RevolutParser` is:

```text
com.revolut.revolut
```

So a debug simulation can pass/fail differently from real Revolut behavior.

## Recommendation

Fix all debug seeded notifications to use real package IDs:

```text
com.revolut.revolut
gr.nbg.mobilebanking
com.eurobank.mobile
gr.alpha.mobile
com.winbank.mobile
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt

---

## Finding P1-6 — Auto-accept path has nested transaction / post-commit side-effect risk

`NotificationProcessingPipeline.processInternal()` opens:

```kotlin
database.withTransaction { ... }
```

Inside that transaction, `handleAutoAcceptInTransaction()` calls:

```kotlin
coordinator.createExpense(request)
```

But `TransactionLifecycleCoordinator.createExpense()` also opens:

```kotlin
database.withTransaction { insert expense + transaction event }
```

Then the coordinator dispatches side effects after its inner transaction:

```kotlin
sideEffectDispatcher.dispatchOnCreated(...)
recurringLifecycleCoordinator.linkExpenseToOccurrence(...)
```

Because the coordinator is called from inside the outer notification transaction, its “post-commit” side effects may actually run before the outer notification transaction has fully committed.

This probably does not explain “not reading notifications,” but it can explain unstable downstream behavior:

- dashboard/budget side effects see incomplete raw notification state,
- side effects happen while outer transaction is still active,
- nested transaction semantics become hard to reason about,
- failures can be confusing.

## Recommendation

Refactor so the notification pipeline does not call side-effecting coordinator methods inside an existing DB transaction.

Options:

### Option A — coordinator supports DB-only creation + deferred side effects

```text
outer transaction:
  insert raw notification
  create expense row
  insert transaction event
  update source stats

after outer commit:
  dispatch side effects
  recurring link
  recommendations
```

### Option B — coordinator owns the transaction, pipeline does not wrap around it

Harder because raw notification + source stats should be consistent.

Best long-term design:

```text
TransactionLifecycleCoordinator.createExpenseAtomic(...)
returns Created + PostCommitActions
pipeline commits
pipeline executes post-commit actions
```

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

---

## Finding P2-1 — `NotificationProcessingPipeline.process()` always reports Success unless exception

`NotificationRepository.processAndSave()` receives:

```kotlin
ProcessingResult.Success
ProcessingResult.Rejected
ProcessingResult.Error
```

But `NotificationProcessingPipeline.process()` returns `Success` after `processInternal()` unless an exception occurs.

Inside `processInternal()`, duplicates, auto-rejects, parser failures, pending-review creation, and auto-accept outcomes are mostly internal.

So repository-level logging cannot clearly say:

```text
parser failed
duplicate skipped
pending review created
auto accepted
auto rejected
```

## Recommendation

Change `processInternal()` to return a detailed outcome:

```kotlin
sealed interface ProcessingOutcome {
  data class AutoAccepted(val rawId: Long, val expenseId: Long)
  data class NeedsReview(val rawId: Long, val reviewId: Long?)
  data class AutoRejected(val rawId: Long, val reason: String)
  data class Duplicate(val rawId: Long?)
  data class ParserFailed(val rawId: Long?)
}
```

Then emit the same outcome into capture diagnostics.

---

## Finding P2-2 — `AppParserRegistry` AI fallback is package-gated

`AppParserRegistry.parseWithAiFallback()` tries deterministic parsers first.

Then AI fallback only runs if:

```text
known finance package
OR communication package with financial keyword
```

All other packages skip AI fallback.

That is reasonable for privacy/compute, but if a real bank package is missing from `FINANCE_PACKAGES`, the app may never try its strongest fallback.

## Recommendation

During debug mode only, allow user to mark a recently seen package as “financial candidate” from the DebugScreen. That package should then bypass the heuristic until the static package list is updated.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt

---

## Finding P2-3 — Dependency map has one suspicious entry

`DEPENDENCY_MAP.md` lists:

```text
NotificationFilter [domain/privacy/NotificationPrivacyGate.kt]
```

But actual `NotificationFilter` is:

```text
service/NotificationFilter.kt
```

Small docs bug, but for AI agents this matters.

## Recommendation

Fix the generated dependency map source so `NotificationFilter` points to the real file.

---

# 4. Device debugging procedure

Use this exact sequence.

## Step 1 — confirm listener permission

```bash
adb shell settings get secure enabled_notification_listeners
```

Expected: your app component appears.

If not:

```text
Settings → Notification access
→ disable ExpenseTracker
→ enable ExpenseTracker
→ force stop
→ open app
```

## Step 2 — run logcat

```bash
adb logcat -s NotificationCapture AndroidRuntime ActivityManager ServiceDiagnostics
```

Trigger one real bank notification.

You need to see whether these appear:

```text
onCreate
onListenerConnected
onNotificationPosted
DROPPED_FILTER
DROPPED_PRIVACY
DROPPED_MAINTENANCE
RAW_INSERT
PARSER_NULL
EXPENSE_CREATED
```

Right now not all these stages exist. Add them.

## Step 3 — check app process/service state

```bash
adb shell ps -A | grep expensetracker
adb shell dumpsys activity services | grep -i expensetracker
adb shell dumpsys notification | grep -i expensetracker
```

## Step 4 — check battery/background state

```bash
adb shell am get-standby-bucket com.yourname.expensetracker
adb shell cmd appops get com.yourname.expensetracker RUN_ANY_IN_BACKGROUND
```

Also test with battery unrestricted.

## Step 5 — test matrix

| Situation | Expected |
|---|---|
| app open, listener enabled | capture works |
| app in background | capture works |
| screen off 30 min | capture works |
| after `adb shell am kill ...` | listener should reconnect |
| after force-stop | likely does not capture until app opened |
| after reboot | listener permission should survive, system should bind |
| battery restricted | may be inconsistent |
| battery unrestricted | should be stable |

---

# 5. Immediate fix plan

## PR 1 — Observability only

Add persistent capture diagnostics.

Do not change behavior yet.

Record:

- service created
- listener connected
- listener disconnected
- notification posted
- package name
- extras keys
- extracted fields preview
- filter decision
- privacy decision
- restore mode
- raw insert result
- parser result
- review/expense result
- exception

Expose in DebugScreen.

Acceptance:

```text
When a notification arrives, DebugScreen shows whether it was seen and why it was dropped/processed.
```

---

## PR 2 — Extraction/filter fix

Add:

```kotlin
NotificationTextExtractor.extract(extras)
```

Fields:

```text
title
text
bigText
subText
infoText
summaryText
textLines
messages
effectiveBigText
combinedPreview
extrasKeys
```

Use it for:

- filter
- contentHash
- RawNotificationFingerprint
- RawNotification.bigText
- parser input
- debug trace

Acceptance:

```text
A notification with amount only in infoText/summaryText/textLines is captured.
```

---

## PR 3 — Remove risky restart model

Disable or remove:

- repeating restart alarm
- `ServiceRestartReceiver` periodic keep-alive
- boot receiver foreground-service start

Keep:

- notification listener permission flow
- `requestRebind()` on disconnect
- foreground notification only if absolutely needed and only when safe

Acceptance:

```text
Debug status distinguishes:
- listener permission granted
- listener connected
- foreground service running
```

Do not show “monitoring active” just because foreground service exists.

---

## PR 4 — Package discovery

Add DebugScreen section:

```text
Recent notification packages:
- packageName
- appLabel
- filter pass/fail
- drop reason
- last seen
- preview
- button: mark as financial candidate
```

Acceptance:

```text
Unknown bank package can be discovered from real device logs.
```

---

## PR 5 — Pipeline outcome return type

Make `NotificationProcessingPipeline` return detailed outcomes.

Acceptance:

```text
Debug trace can say:
- raw saved
- parser failed
- review created
- auto accepted
- duplicate skipped
- auto rejected
```

---

## PR 6 — Transaction side-effect boundary

Refactor notification auto-accept so coordinator side effects do not run before the outer notification transaction commits.

Acceptance:

```text
notification raw insert + expense insert + transaction event + source stats are atomic;
budget/anomaly/recurring/recommendation side effects run after commit.
```

---

# 6. Tests to add

## Unit tests

### `NotificationTextExtractorTest`

Cases:

- amount in `EXTRA_TEXT`
- amount in `EXTRA_BIG_TEXT`
- amount in `EXTRA_INFO_TEXT`
- amount in `EXTRA_SUMMARY_TEXT`
- amount in `EXTRA_SUB_TEXT`
- amount in `EXTRA_TEXT_LINES`
- blank `bigText` falls back to info/summary/textLines
- combined preview redacts or truncates safely

### `NotificationFilterFallbackFieldsTest`

Cases:

```text
unknown package + amount/keyword in infoText → captured
communication package + financial text in textLines → captured
ignored package → rejected
known finance package → captured
```

### `NotificationCaptureDedupeTest`

Cases:

```text
same sbn.key but changed fallback text → not deduped incorrectly
same exact effective text within 5s → deduped
```

### `NotificationCaptureGateTest`

Cases:

```text
privacy denied → trace DROPPED_PRIVACY
restore mode active → trace DROPPED_MAINTENANCE
blocked package → trace DROPPED_BLOCKED_PACKAGE
```

---

## DB-backed pipeline tests

### `NotificationToRawDbContractTest`

```text
feed fake StatusBarNotification or service adapter
→ raw_notifications row inserted
→ extras/effectiveBigText saved
→ source stats updated
```

### `NotificationReviewDashboardScenarioTest`

```text
seed categories + budget
feed Greek bank notification
→ parser result
→ review or auto-accept
→ TransactionLifecycleCoordinator
→ Expense row
→ TransactionEvent.CREATED
→ dashboard monthly total
→ budget remaining
→ analytics category total
```

### `NotificationFilterPackageDiscoveryScenarioTest`

```text
unknown bank package
→ trace visible
→ user marks financial candidate
→ same format captured next time
```

---

# 7. Most likely root cause ranking

## 1. Early filter before full extraction

Very likely.

Why:

- code proves filter sees only title/text/bigText,
- later code proves additional fallback fields exist,
- real apps often use rich extras.

## 2. Listener permission stale / service disconnected

Very likely.

Why:

- user reports inconsistency,
- app uses risky foreground-service restart strategy,
- Android listener binding and foreground service running are different states.

## 3. Background FGS start restrictions

Likely.

Why:

- BootReceiver and ServiceRestartReceiver call `startForegroundService()` from background.
- Android 12+ restricts this.
- Android 15 adds more restrictions.

## 4. Silent restore/privacy gate

Possible.

Why:

- both can drop notifications before persistence.
- no visible drop reason currently.

## 5. Package list stale

Possible.

Why:

- if real bank package differs, heuristic may reject.
- current system lacks package discovery.

## 6. Parser failure

Possible but less likely for “not read at all.”

Why:

- if raw notifications are visible but no expense/review appears, parser/routing is likely.
- if no raw notifications appear, issue is before parser.

---

# 8. What I would check first on your device

1. Re-toggle notification listener access.
2. Open DebugScreen.
3. Trigger real bank notification.
4. Check whether raw notification count increases.
5. If not, check new diagnostics:
   - listener connected?
   - last notification callback?
   - drop reason?
6. If callback exists but no raw row:
   - filter/privacy/restore/package blocked issue.
7. If raw row exists but no review/expense:
   - parser/confidence/router issue.
8. If expense exists but dashboard wrong:
   - dashboard/analytics/currency flow issue.

---

# 9. Final recommendation

For Pipeline 1, do not start by rewriting parsers.

Start with:

```text
diagnostics → extraction fix → listener lifecycle cleanup → package discovery → scenario test
```

That gives you proof.

The key goal is to make the app answer:

```text
Did Android deliver the notification?
Did the app see it?
If dropped, exactly why?
If processed, what row/review/expense did it create?
Did dashboard observe it?
```

Until the app can answer those questions, notification bugs will feel random.

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- Architecture guide  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/ARCHITECTURE.md

- Android manifest  
  https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/AndroidManifest.xml

- `NotificationCaptureService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `NotificationFilter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt

- `BootReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/receiver/BootReceiver.kt

- `ServiceRestartReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/receiver/ServiceRestartReceiver.kt

- `NotificationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `ReviewQueueRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

- `AppParserRegistry.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt

- `GreekBankParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/GreekBankParser.kt

- `RevolutParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/RevolutParser.kt

- `GenericTransactionParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/parser/GenericTransactionParser.kt

- `ConfidenceRouter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouter.kt

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `RawNotification.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt

- `RawNotificationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt

- `RawNotificationFingerprint.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/notification/RawNotificationFingerprint.kt

- `RestoreMaintenanceMode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `AppStartupCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `NotificationPrivacyGate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/privacy/NotificationPrivacyGate.kt

- `PrivacySettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

- `ServiceDiagnostics.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/debug/ServiceDiagnostics.kt

- Android `NotificationListenerService` docs  
  https://developer.android.com/reference/android/service/notification/NotificationListenerService

- Android foreground-service background-start restrictions  
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

- Android notification runtime permission docs  
  https://developer.android.com/develop/ui/compose/notifications/notification-permission

- Android 15 behavior changes  
  https://developer.android.com/about/versions/15/behavior-changes-15

---

# 10. Verification & Fix Log (2026-05-06)

## Verification methodology

All findings were cross-referenced against the actual codebase by reading every Pipeline 1 source file and its dependencies, tracing the full notification flow from `onNotificationPosted()` through `TransactionLifecycleCoordinator.createExpense()`.

## Finding verification status

| Finding | Verified | Status after fix |
|---------|----------|-----------------|
| P0-1: Listener lifecycle risky | ✅ Confirmed | Not fixed — needs architectural discussion (PR 3 in fix plan) |
| P0-2: Filtering before full extraction | ✅ Confirmed | **FIXED** — `NotificationTextParts.extract()` does single-pass extraction; `effectiveBigText` passed to filter, hash, fingerprint, entity |
| P0-3: Drop reasons not persisted | ✅ Confirmed | Not fixed — needs new diagnostic table (PR 1 in fix plan) |
| P1-1: Restore mode silent block | ✅ Confirmed | Not fixed — UI observability improvement (PR 1) |
| P1-2: Privacy gate silent block | ✅ Confirmed | Not fixed — UI observability improvement (PR 1) |
| P1-3: In-memory dedupe incomplete | ✅ Confirmed | **FIXED** — `computeNotificationContentHash()` now uses `effectiveBigText` via unified extraction |
| P1-4: Package list stale | ✅ Confirmed as maintenance concern | Not fixed — needs runtime package discovery (PR 4) |
| P1-5: Debug simulation wrong package | Not verified in this pass | Deferred |
| P1-6: Nested transaction side-effects | ✅ Confirmed | Not fixed — needs coordinator refactor (PR 6) |
| P2-1: Pipeline Success reporting | ✅ Confirmed | Not fixed — needs outcome type refactor (PR 5) |
| P2-2: AI fallback package-gated | ✅ Confirmed | Not fixed — design decision, flagged for discovery (PR 4) |
| P2-3: Dependency map docs bug | ✅ Confirmed | Deferred |

## Fixes applied

### Fix 1 — Unified notification text extraction (P0-2 + P1-3)

**Files changed:** `NotificationCaptureService.kt`

Created `NotificationTextParts` data class with `extract(extras: Bundle)` companion factory that extracts ALL notification fields (`title`, `text`, `bigText`, `subText`, `infoText`, `summaryText`) in a single pass and computes `effectiveBigText`.

Both `onNotificationPosted()` and `processNotificationBypassDedupe()` now use `NotificationTextParts.extract()` and pass `effectiveBigText` to:
- `NotificationFilter.shouldCapture()` — notifications with transaction info in infoText/summaryText are no longer dropped
- `computeNotificationContentHash()` — content changes in fallback fields are detected
- `RawNotificationFingerprint.compute()` — fingerprint covers the resolved content
- `RawNotification` entity — stored bigText is the effective resolved value

Removed the old `resolveEffectiveBigText()` standalone function — its logic is now inside `NotificationTextParts.extract()`.

### Fix 2 — Restore maintenance mode guard on manual refresh (NEW-3)

**Files changed:** `NotificationCaptureService.kt`

`processNotificationBypassDedupe()` (called by `refreshActiveNotifications()`) was missing the `restoreMaintenanceMode.isWritesAllowed()` check. During a restore, a manual refresh could write to the DB.

Added the same guard as `onNotificationPosted()`.

### Fix 3 — Consolidate FINANCIAL_PACKAGES (NEW-1)

**Files changed:** `NotificationProcessingPipeline.kt`

`NotificationProcessingPipeline.FINANCIAL_PACKAGES` was a separate hardcoded set that had to be manually kept in sync with `NotificationFilter.FINANCE_PACKAGES`. Replaced with a delegating property:

```kotlin
private val FINANCIAL_PACKAGES: Set<String>
    get() = NotificationFilter.FINANCE_PACKAGES
```

### Fix 4 — Deny keyword cleanup (NEW-4)

**Files changed:** `NotificationFilter.kt`

Removed deny keywords that blocked legitimate financial events:
- `"refund processed"` — refunds are valid financial events
- `"you received"` — deposits/transfers should be captured
- `"on hold"` — authorization holds are financial events
- `"offer"` — too broad, catches "offer price $50"
- `"discount"` — too broad, catches "discount applied -$5.00"
- `"επιστροφ"` (Greek: return/refund) — financial event

Changed `"κωδικ"` (too broad) to `"κωδικός ασφαλείας"` and `"κωδικ επαληθ"` for specificity.

### Fix 5 — Filter ordering: finance packages bypass deny keywords (NEW-5)

**Files changed:** `NotificationFilter.kt`

The deny keyword check was applied BEFORE the finance package check. This violated the documented contract that "finance apps bypass heuristics — every notification is financial." A bank's 2FA notification would be incorrectly dropped.

Moved the `FINANCE_PACKAGES` check to execute before deny keyword evaluation. Finance package notifications are always captured; deny keywords only apply to communication and unknown packages.

### Fix 6 — RevolutParser reject pattern fix (NEW-8)

**Files changed:** `RevolutParser.kt`

The parser aborted the entire `parse()` when ANY candidate field (title, text, bigText) matched a reject pattern like "weekly report" — even if another field contained a valid transaction. Changed `return null` to `continue` so the parser skips the rejected field and tries the next one.

## New issues discovered (not in original report)

### NEW-1 — FINANCIAL_PACKAGES duplicated → FIXED (Fix 3)

### NEW-2 — `processNotificationBypassDedupe` missing restore guard → FIXED (Fix 2)

### NEW-3 — Deny keywords block legitimate financial notifications → FIXED (Fix 4)

### NEW-4 — No `EXTRA_TEXT_LINES` / `EXTRA_MESSAGES` extraction

`NotificationTextParts.extract()` currently does not extract `EXTRA_TEXT_LINES` (inbox-style notifications) or `EXTRA_MESSAGES` (messaging-style notifications). Banking apps that use these notification styles could have transaction text invisible to the pipeline.

**Severity:** P2 (enhancement — less common than infoText/summaryText but still a gap)

**Recommendation:** Add `textLines` and `messages` fields to `NotificationTextParts` and incorporate them into the `effectiveBigText` fallback chain.

### NEW-5 — Filter ordering violated finance package contract → FIXED (Fix 5)

### NEW-6 — `ServiceDiagnostics` uses `System.currentTimeMillis()` directly

Line 35 of `ServiceDiagnostics.kt`:
```kotlin
putLong(KEY_LAST_RESTART_TIME, System.currentTimeMillis())
```

Bypasses `TimeProvider`, making this untestable and inconsistent with the app's time abstraction.

**Severity:** P3 (consistency / testability)

### NEW-7 — `dedupeFingerprint` hash mismatch between migration backfill and runtime

`RawNotificationFingerprint.compute()` stores a **SHA-256 hex hash** of `packageName|timestamp|title|text|bigText`. But the SQL migration backfill (`MIGRATION_104_105` in `AppDatabase.kt`) stores the **plain concatenation** (SHA-256 is not available in SQLite).

This means the same logical notification can appear twice in the DB: one row with a plaintext fingerprint (from migration) and one with a SHA-256 hash (from runtime capture). The unique index compares full string values — they will never match.

**ACKNOWLEDGED (2026-05-06):** TODO comment added in AppDatabase.kt before MIGRATION_104_105 
        documenting the SHA-256 vs plaintext mismatch. Full fix requires a Kotlin one-time migration 
        (SHA-256 not available in SQLite). Deferred to a future migration window.

### NEW-8 — RevolutParser reject pattern aborts entire parse → **FIXED**

`RevolutParser.parse()` iterated through `title`, `text`, `bigText` candidates. If ANY candidate matched a reject pattern (e.g., title = "Weekly report"), `return null` aborted the entire method — even if a later candidate (text/bigText) contained a valid transaction like "Paid €12.50 at Store".

Changed `return null` to `continue` so the parser skips the rejected field and tries the next one.

### NEW-9 — `ProcessingResult.Rejected` is dead code

`NotificationProcessingPipeline.process()` always returns `Success` or `Error`. The `Rejected` variant is never emitted. `NotificationRepository.processAndSave()` has a branch for `Rejected` that never executes.

**Severity:** P3 (dead code, no runtime impact)

### NEW-10 — `ReviewQueueRepository.markAsRelevant` cache invalidation uses wrong key

`markAsRelevant` calls `confidenceRouter.invalidateAfterUserAction(notification.packageName, notification.title ?: "Unknown")`. The second argument should be a **merchant** name (used as merchant cache key), but `notification.title` is passed instead. This may mis-invalidate or miss the correct merchant-keyed cache entry.

**FIXED (2026-05-06):** confidenceRouter.invalidateAfterUserAction() now receives 
        expense?.merchant (falling back to suggestedMerchant → packageName) instead of 
        notification.title. This matches the merchant-keyed cache pattern used by approveReview/rejectReview.

### NEW-11 — `RestoreMaintenanceMode.scheduleAllWorkers` missing `ai_daily_briefing`

Class KDoc says "all 7 background workers" are managed. `pauseAllWorkers()` correctly cancels all `WorkerSpec.DEFAULTS.keys` entries. But `scheduleAllWorkers()` only reschedules 6 workers — `ai_daily_briefing` is not rescheduled after restore exit.

**FIXED (2026-05-06):** scheduleAllWorkers() now includes ai_daily_briefing 
        via WorkerSpecScheduler.scheduleAtMidnight(). All 7 workers are now rescheduled after restore.

### NEW-12 — Currency detection in fallback paths is simplistic

`detectOversizedAmountCandidate()` and `detectTransactionSignalCandidate()` in the pipeline default to EUR when no USD/GBP signal is found. Missing: CHF, SEK, PLN, CZK, and all `$`-denominated currencies beyond USD (AUD, CAD, NZD, etc.).

**Severity:** P3 (affects non-EUR/USD/GBP users only)

### NEW-13 — P1-6 nested transaction confirmed: coordinator side effects fire mid-transaction

Traced the exact call path:

```text
NotificationProcessingPipeline.processInternal()
  → database.withTransaction {                            ← OUTER
      → handleAutoAcceptInTransaction()
          → coordinator.createExpense(request)
              → database.withTransaction { insert + event }  ← INNER (savepoint)
              → sideEffectDispatcher.dispatchOnCreated()     ← SIDE EFFECTS HERE
              → recurringLifecycleCoordinator.linkExpenseToOccurrence()
          ← returns Created
      → dao.markRelevance(rawId, true)
      → sourceStatsDao.incrementTotalAndAccepted()
    }                                                       ← OUTER COMMIT

Side effects include:
  - budgetMonitor.checkBudgets() → reads expenses (uncommitted outer txn)
  - anomalyAlertOrchestrator.checkAndAlert() → reads expenses (uncommitted outer txn)
  - merchantCategoryRepository.learnPattern() → DB write (part of outer txn, safe)
  - recurringLifecycleCoordinator.linkExpenseToOccurrence() → DB write (part of outer txn, safe)
```

The DB writes in side effects are safe (they participate in the outer transaction). The READS are the risk: `budgetMonitor` and `anomalyAlertOrchestrator` query expense data that may not yet be committed. If the outer transaction rolls back, those reads saw phantom data.

**Practical risk:** Low — the remaining outer transaction steps (`markRelevance`, `incrementTotalAndAccepted`) are unlikely to fail. But architecturally unsound.

**Recommendation:** Add `createExpenseDbOnly()` to the coordinator that returns the expense ID without dispatching side effects. The pipeline owns the outer transaction and dispatches side effects post-commit.

---

# 11. Remaining work priority

After these fixes, the remaining Pipeline 1 work in priority order:

1. **PR 1 — Observability** (P0-3, P1-1, P1-2): Add persistent capture diagnostics ring buffer. Without this, debugging notification issues on user devices remains guesswork.

2. **PR 3 — Listener lifecycle** (P0-1): Remove periodic restart alarm; rely on system listener binding + `requestRebind()`. This is the most impactful reliability fix for the "not reading notifications" symptom.

3. **PR 6 — Transaction boundary** (P1-6 / NEW-8): Add `createExpenseDbOnly()` to coordinator; pipeline dispatches side effects post-commit. Prevents phantom reads.

4. **PR 4 — Package discovery** (P1-4): Runtime package discovery in debug screen. Enables users to identify unrecognized bank packages.

5. **PR 5 — Pipeline outcome** (P2-1): Return detailed outcomes from `processInternal()` for diagnostic logging.

6. **Fingerprint hash migration** (NEW-7): One-time Kotlin migration to re-hash plaintext fingerprints from pre-105 data.

7. **EXTRA_TEXT_LINES / EXTRA_MESSAGES** (NEW-4): Add inbox-style and messaging-style extraction to `NotificationTextParts`.

8. **ReviewQueueRepository cache key** (NEW-10): Fix `markAsRelevant` to pass merchant name instead of notification title.

9. **RestoreMaintenanceMode worker gap** (NEW-11): Add `ai_daily_briefing` to `scheduleAllWorkers()`.