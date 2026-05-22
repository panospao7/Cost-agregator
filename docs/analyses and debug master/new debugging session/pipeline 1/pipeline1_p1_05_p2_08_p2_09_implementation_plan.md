# Dedicated implementation plan — P1-P1-05, P2-08, P2-09

Target commit context: `e781c226862234ed412914884e98d22165a41a95`

Target issues:

| ID | Status | Theme |
|---|---:|---|
| P1-P1-05 | ⚠ Partial | Full privacy/package/restore gate before extraction |
| P2-08 | ⚠ Partial | Manual refresh still bypasses dedupe / causes duplicate work |
| P2-09 | ⚠ Partial | Finance-app filtering still allows balance/currency-only noise |

Recommended split:

1. **PR 1 — Capture gate before extraction**
2. **PR 2 — Refresh dedupe + fingerprint-first duplicate check**
3. **PR 3 — Finance transaction-signal detector v2**

---

# PR 1 — P1-P1-05: Capture gate before extraction

## Current problem

Current service flow is improved but still not correct enough:

- `capturePrivacyDenied` checks only `settings.notificationCaptureEnabled`.
- Full `PrivacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)` still runs **after**:
  - notification extras are read,
  - text fields are extracted,
  - filter runs.
- `blockedPackageCacheLoaded == false` causes every package to be treated as blocked.
- `capturePrivacyDenied == true` until first settings emission, so valid notifications can be dropped on service startup.
- `PrivacyDecision.NotApplicable` / inconclusive decisions currently proceed.
- Normal path and refresh path duplicate gate logic.

## Goal

Create one canonical gate:

```kotlin
NotificationCaptureGate
```

Rule:

```text
No notification extras/text extraction until NotificationCaptureGate returns Allowed.
```

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `NotificationFilter.kt` call sites only
- `PrivacySettingsRepository`
- `BlockedPackageDao`

New files:

- `NotificationCaptureGate.kt`
- `NotificationCaptureDecision.kt`
- `NotificationCaptureGateTest.kt`

Possible diagnostics integration:

- `DiagnosticReasonCode.kt`
- notification diagnostic emitter/factory, if already created from P1-P1-02 work

---

## Step 1.1 — Add one-shot blocked-package query

Current service observes:

```kotlin
blockedPackageDao.getAllPackageNamesFlow()
```

Add one-shot query:

```kotlin
@Query("SELECT packageName FROM blocked_packages")
suspend fun getAllPackageNamesOnce(): List<String>
```

Reason:

- avoid fail-closed false drops before first flow emission;
- gate can warm up deterministically.

---

## Step 1.2 — Define capture decision model

Create:

```kotlin
sealed interface NotificationCaptureDecision {
    data object Allowed : NotificationCaptureDecision

    data class Blocked(
        val reason: NotificationCaptureBlockReason,
        val diagnosticStage: String,
        val terminalReasonCode: DiagnosticReasonCode
    ) : NotificationCaptureDecision

    data class TemporarilyUnavailable(
        val reason: NotificationCaptureBlockReason,
        val retryable: Boolean
    ) : NotificationCaptureDecision
}
```

Reasons:

```kotlin
enum class NotificationCaptureBlockReason {
    RESTORE_MODE,
    SERVICE_SHUTTING_DOWN,
    PRIVACY_SETTING_DISABLED,
    PRIVACY_GATE_DENIED,
    PRIVACY_GATE_FAIL_CLOSED,
    PRIVACY_GATE_NOT_APPLICABLE,
    BLOCKED_PACKAGE,
    GATE_NOT_READY,
    GATE_ERROR
}
```

Important policy decision:

```text
Denied / FailClosed / NotApplicable must not proceed to extraction.
Only Allowed proceeds.
```

If you intentionally want `NotApplicable` to proceed, encode that explicitly in the gate and document why. Do not leave it as an accidental `else`.

---

## Step 1.3 — Implement `NotificationCaptureGate`

Constructor dependencies:

```kotlin
class NotificationCaptureGate @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository,
    private val privacyGate: PrivacyGate,
    private val blockedPackageDao: BlockedPackageDao,
    private val restoreMaintenanceMode: RestoreMaintenanceMode
)
```

Internal state:

```kotlin
@Volatile private var blockedPackages: Set<String> = emptySet()
@Volatile private var blockedPackagesLoaded: Boolean = false
@Volatile private var settingsLoaded: Boolean = false
@Volatile private var notificationCaptureEnabled: Boolean = false
```

Public API:

```kotlin
suspend fun warmUp()

fun startObservers(scope: CoroutineScope)

suspend fun decide(
    packageName: String,
    isShuttingDown: Boolean
): NotificationCaptureDecision
```

`warmUp()` should:

1. load `privacySettingsRepository.getSettings()`;
2. load `blockedPackageDao.getAllPackageNamesOnce()`;
3. set both loaded flags;
4. fail closed only if loading fails.

`decide()` order:

1. restore mode;
2. shutdown;
3. settings/cache readiness;
4. blocked package;
5. notification capture setting;
6. full `privacyGate.check(NOTIFICATION_CAPTURE)`;
7. only `Allowed` returns `Allowed`.

Why full gate is in `decide()`:

- it is suspend-safe;
- it runs before extraction;
- it avoids stale/incomplete fast booleans.

---

## Step 1.4 — Move capture handling into coroutine before extraction

Current `onNotificationPosted()` extracts parts before launching work.

Change to:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return
    val envelope = NotificationCaptureEnvelope.from(sbn)
    workTracker.launch(serviceScope) {
        handleNotificationCapture(envelope, CaptureSource.LISTENER)
    } ?: emitShutdownDropBestEffort(envelope)
}
```

`NotificationCaptureEnvelope` should contain only safe metadata:

```kotlin
data class NotificationCaptureEnvelope(
    val sbn: StatusBarNotification,
    val packageName: String,
    val notificationKey: String,
    val postTime: Long,
    val correlationId: String
)
```

No extras/title/text access in `from()`.

---

## Step 1.5 — Create shared handler for listener + refresh

New function:

```kotlin
private suspend fun handleNotificationCapture(
    envelope: NotificationCaptureEnvelope,
    source: CaptureSource
)
```

Flow:

```text
1. emit RECEIVED
2. gate.decide(...)
3. if not allowed -> emit terminal blocked/drop event and return
4. read extras
5. extract NotificationTextParts
6. filter
7. process notification
```

Refresh path should call the same handler.

Remove duplicated privacy/package/restore logic from:

- `onNotificationPosted`
- `processNotificationBypassDedupe`

---

## Step 1.6 — Remove fail-closed false-drop behavior

Replace:

```kotlin
capturePrivacyDenied = true
blockedPackageCacheLoaded = false
isPackageBlockedFast() = !loaded || package in cache
```

with:

```text
gate warm-up happens on service start.
If a notification arrives before warm-up completes, wait briefly.
If still unavailable, emit GATE_NOT_READY, not fake PRIVACY_DENIED or BLOCKED_PACKAGE.
```

Suggested timeout:

```kotlin
private const val GATE_READY_TIMEOUT_MS = 300L
```

Policy options:

- If durable intake is not implemented yet, prefer a short wait + explicit `GATE_NOT_READY` diagnostic.
- Do not label cache-loading drops as blocked package.

---

## Step 1.7 — Diagnostics

For denied decisions, emit:

| Reason | Stage | Outcome |
|---|---|---|
| `RESTORE_MODE` | `capture_gate` | `BLOCKED / RESTORE_BLOCKED` |
| `SERVICE_SHUTTING_DOWN` | `capture_gate` | `CANCELLED / CANCELLED_BY_SYSTEM` |
| `PRIVACY_SETTING_DISABLED` | `capture_gate` | `DROPPED / PRIVACY_DENIED` |
| `PRIVACY_GATE_DENIED` | `capture_gate` | `DROPPED / PRIVACY_DENIED` |
| `PRIVACY_GATE_FAIL_CLOSED` | `capture_gate` | `BLOCKED / PRIVACY_DENIED` or `FAILED_FINAL` |
| `BLOCKED_PACKAGE` | `capture_gate` | `DROPPED / BLOCKED_PACKAGE` |
| `GATE_NOT_READY` | `capture_gate` | `FAILED_RETRYABLE` or `DROPPED` with explicit reason |

Keep metadata safe:

- hash package name;
- hash notification key;
- never include title/text/body/extras.

---

## PR 1 tests

### Gate unit tests

1. restore mode active => blocked before privacy check.
2. shutdown true => cancelled.
3. blocked package => blocked.
4. setting disabled => privacy denied.
5. full privacy gate denied => privacy denied.
6. full privacy gate fail-closed => blocked/fail-closed.
7. full privacy gate allowed => allowed.
8. cache not ready => `GATE_NOT_READY`, not `BLOCKED_PACKAGE`.

### Service flow tests

Use fake extractor.

1. privacy denied => extractor not called.
2. blocked package => extractor not called.
3. restore active => extractor not called.
4. gate allowed => extractor called.
5. refresh path uses same gate.
6. listener path uses same gate.
7. `NotApplicable` does not accidentally proceed unless explicit policy says so.

## PR 1 acceptance criteria

- No extras access before capture gate returns `Allowed`.
- Normal path and refresh path use one handler.
- No startup false drop labeled as blocked package/privacy denied.
- Full `PrivacyGate.check(NOTIFICATION_CAPTURE)` runs before extraction.
- Tests prove extractor is not invoked on denied paths.

---

# PR 2 — P2-08: Manual refresh dedupe + fingerprint-first duplicate check

## Current problem

Current refresh path:

```kotlin
refreshActiveNotifications()
  -> processNotificationBypassDedupe(sbn)
```

Problems:

- refresh bypasses service in-memory dedupe;
- repeated refresh can re-run pipeline/parser;
- current fast duplicate pre-check uses raw title/text/body fields;
- under `STORE_METADATA_ONLY`, `STORE_REDACTED`, or `DO_NOT_STORE`, stored raw fields may not match the in-memory raw fields;
- unique `dedupeFingerprint` eventually protects DB insert, but too late to avoid parser/AI work.

## Goal

Manual refresh should:

```text
Use the same gate/filter/process path as listener notifications.
Never bypass durable duplicate checks.
Avoid parser/AI work for already-seen active notifications.
Avoid concurrent duplicate processing with listener path.
```

## Dependencies

Best implemented after:

- P1-P1-01 outcome-return PR;
- PR 1 above, because refresh should use shared capture handler.

## Files to modify

Primary:

- `NotificationCaptureService.kt`
- `RawNotificationDao.kt`
- `NotificationProcessingPipeline.kt`
- `NotificationRepository.kt`

Possible new file:

- `NotificationCaptureDeduper.kt`

Tests:

- `NotificationRefreshDedupeTest.kt`
- `NotificationProcessingPipelineDedupeTest.kt`

---

## Step 2.1 — Add fingerprint DAO query

`RawNotification` already has a unique `dedupeFingerprint` index.

Add:

```kotlin
@Query("""
    SELECT EXISTS(
        SELECT 1 FROM raw_notifications
        WHERE dedupeFingerprint = :fingerprint
    )
""")
suspend fun existsByDedupeFingerprint(fingerprint: String): Boolean
```

No migration needed if the column/index already exists.

---

## Step 2.2 — Replace raw-field pre-check in pipeline

Current pipeline pre-check uses:

```text
packageName + timestamp + title + text + bigText
```

Replace with:

```kotlin
val fingerprint = notification.dedupeFingerprint
    ?: RawNotificationFingerprint.compute(
        packageName = notification.packageName,
        title = notification.title,
        text = notification.text,
        bigText = notification.bigText,
        timestamp = notification.timestamp
    )

if (dao.existsByDedupeFingerprint(fingerprint)) {
    return NotificationPipelineOutcome.Duplicate(
        packageName = notification.packageName,
        reason = "Fingerprint duplicate"
    )
}
```

Do this before parser/AI fallback.

Keep raw-field `exists(...)` only as legacy fallback if `dedupeFingerprint` is null.

---

## Step 2.3 — Strengthen insert result

Current insert path returns `-1L` on duplicate.

Replace with typed result:

```kotlin
sealed interface RawNotificationInsertResult {
    data class Inserted(val rawId: Long) : RawNotificationInsertResult
    data object Duplicate : RawNotificationInsertResult
}
```

Then:

```kotlin
private suspend fun insertRawNotificationIfNotDuplicate(...): RawNotificationInsertResult
```

Map `insertOrIgnore == -1L` to `Duplicate`.

This avoids ambiguous `-1L` handling.

---

## Step 2.4 — Remove `processNotificationBypassDedupe`

Delete/replace:

```kotlin
processNotificationBypassDedupe(sbn)
```

Refresh should call:

```kotlin
enqueueNotificationCapture(
    sbn = sbn,
    source = CaptureSource.REFRESH,
    dedupePolicy = DedupePolicy.CHECK_IN_FLIGHT_AND_DURABLE
)
```

Normal listener path should call:

```kotlin
enqueueNotificationCapture(
    sbn = sbn,
    source = CaptureSource.LISTENER,
    dedupePolicy = DedupePolicy.CHECK_IN_FLIGHT_AND_DURABLE
)
```

The only difference between listener and refresh should be diagnostic `source/stage`, not bypassing dedupe.

---

## Step 2.5 — Add in-flight deduper

Create:

```kotlin
class NotificationCaptureDeduper {
    fun tryStart(key: NotificationInFlightKey, nowMs: Long): DedupeStartResult
    fun finish(key: NotificationInFlightKey, outcome: NotificationPipelineOutcome?)
}
```

For this PR, scope it to **in-flight** protection.

Key:

```kotlin
data class NotificationInFlightKey(
    val packageName: String,
    val notificationKey: String,
    val postTime: Long,
    val contentFingerprint: String
)
```

Build the key after gate + extraction.

Rules:

- if same content is already in-flight, drop as duplicate;
- listener and refresh share the same deduper;
- after completion, durable DB fingerprint handles future duplicates;
- do not rely on manual refresh bypassing memory cache.

Full success-TTL dedupe can remain later if you want to keep PR small.

---

## Step 2.6 — Pipeline outcome handling

After P1-P1-01, repository returns outcome.

Service should use that outcome to call:

```kotlin
deduper.finish(key, outcome)
```

If cancelled before repository starts:

```kotlin
deduper.finish(key, null)
```

If pipeline returns `Duplicate`, do not treat as failure.

---

## Step 2.7 — Diagnostics

Add duplicate reason metadata:

```text
source = LISTENER or REFRESH
duplicateReason = IN_FLIGHT or FINGERPRINT
```

Expected refresh duplicate flow:

```text
RECEIVED(refresh)
DUPLICATE(fingerprint)
```

No parser/AI call.

---

## PR 2 tests

### DAO/pipeline tests

1. existing fingerprint => pipeline returns `Duplicate`.
2. existing fingerprint => parser not called.
3. metadata-only stored row still matches fingerprint.
4. redacted stored row still matches fingerprint.
5. `insertOrIgnore == -1L` maps to typed duplicate.

### Service refresh tests

1. refresh same active notification twice => second is duplicate.
2. listener in-flight + refresh same notification => refresh duplicate/in-flight.
3. refresh existing DB notification => no parser call.
4. refresh new notification => processed normally.
5. refresh still applies capture gate before extraction.
6. refresh still applies filter.

## PR 2 acceptance criteria

- `processNotificationBypassDedupe` removed or no longer bypasses duplicate protection.
- Manual refresh cannot cause duplicate parser/AI work for existing fingerprint.
- Duplicate detection works under all raw-storage modes.
- Listener and refresh share the same capture handler.
- Tests prove parser is not invoked for fingerprint duplicates.

---

# PR 3 — P2-09: Finance transaction-signal detector v2

## Current problem

Current `NotificationFilter` is better than before but still too broad for finance packages.

For finance packages, current logic roughly allows:

```text
transaction/payment/purchase/transfer OR any currency-looking amount
```

So these can still pass:

- balance-only alerts;
- account summaries;
- FX rate/currency-only messages;
- promotional cashback offers;
- incoming transfer/credit messages;
- amount-only notifications with no expense action;
- card/security/account-management alerts with currency amounts.

## Goal

Finance packages should not be blindly captured just because they contain a currency amount.

Rule:

```text
Capture only expense-like or review-worthy transaction notifications.
Reject balance/account/security/promo/currency-only finance noise.
```

## Files to modify

Primary:

- `NotificationFilter.kt`

New files recommended:

- `NotificationTransactionSignalDetector.kt`
- `NotificationFilterDecision.kt`
- `NotificationTextNormalizer.kt`
- `NotificationMoneySignalDetector.kt` if you want to prepare for P2-10

Tests:

- `NotificationFilterTest.kt`
- `NotificationTransactionSignalDetectorTest.kt`

---

## Step 3.1 — Replace boolean-only filter internals with decision model

Keep backward API:

```kotlin
fun shouldCapture(...): Boolean = decide(...).capture
```

Add:

```kotlin
data class NotificationFilterDecision(
    val capture: Boolean,
    val reason: NotificationFilterReason,
    val confidence: Float,
    val amountSignals: List<MoneySignal> = emptyList(),
    val direction: TransactionDirection = TransactionDirection.UNKNOWN
)
```

Enums:

```kotlin
enum class NotificationFilterReason {
    IGNORED_PACKAGE,
    SECURITY_OR_AUTH,
    PROMOTION,
    BALANCE_ONLY,
    ACCOUNT_INFO_ONLY,
    CURRENCY_ONLY,
    NO_AMOUNT,
    NO_FINANCIAL_KEYWORD,
    INCOMING_ONLY,
    PAYMENT_FAILED_OR_DECLINED,
    ALLOW_STRONG_EXPENSE,
    ALLOW_REVIEWABLE_TRANSFER,
    ALLOW_REVIEWABLE_FINANCIAL_SIGNAL
}

enum class TransactionDirection {
    DEBIT,
    CREDIT,
    TRANSFER_OUT,
    TRANSFER_IN,
    UNKNOWN
}
```

---

## Step 3.2 — Add text normalizer

Create:

```kotlin
object NotificationTextNormalizer {
    fun normalize(raw: String): String
}
```

Rules:

- lowercase;
- collapse whitespace;
- remove accents/diacritics where useful;
- keep Greek text support;
- keep original enough for regex matching.

Reason:

- Greek notifications may contain accents;
- current keyword matching is fragile.

---

## Step 3.3 — Add money signal detector

Create:

```kotlin
data class MoneySignal(
    val raw: String,
    val amount: Double?,
    val currency: String?,
    val hasCurrencySymbol: Boolean
)
```

Detector should identify:

- `€12.30`
- `12.30 EUR`
- `EUR 12.30`
- existing supported symbols/codes
- decimal comma variants

For this PR, do not fully solve P2-10, but stop using “any currency-looking text” as enough to allow finance packages.

---

## Step 3.4 — Create structured transaction signal detector

Create:

```kotlin
class NotificationTransactionSignalDetector {
    fun detect(
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?
    ): NotificationFilterDecision
}
```

Order:

1. ignored package => reject.
2. security/auth deny => reject.
3. promo/offer deny => reject.
4. payment failed/declined => reject unless you intentionally want review.
5. balance/account info => reject if no strong debit action.
6. no amount => reject.
7. incoming-only/credit-only => reject for expense pipeline.
8. strong expense/debit signal + amount => allow.
9. outgoing transfer + amount => allow or reviewable.
10. ambiguous finance amount + weak transaction signal => allow as reviewable only if finance package.
11. otherwise reject.

---

## Step 3.5 — Keyword groups

Use grouped keyword sets, not one flat list.

### Hard deny — security/auth

Examples:

```text
2fa, verification code, security code, login, password reset, OTP
```

Greek equivalents already partly exist; keep and expand.

### Hard deny — promo

Examples:

```text
offer, promo, cashback offer, reward, discount, deal
```

Be careful: “cashback received” is not an expense.

### Hard deny — balance/account info

Examples:

```text
balance, available balance, account balance, statement, limit, monthly summary
```

Greek examples:

```text
υπόλοιπο, υπολοιπο, διαθέσιμο υπόλοιπο, διαθέσιμο, λογαριασμός
```

Reject balance-only even if amount exists.

### Strong expense/debit

Examples:

```text
paid, spent, purchase, card payment, charged, POS, contactless, online payment,
debit, withdrawn, withdrawal
```

Greek/Greeklish:

```text
αγορά, αγορα, χρέωση, χρεωση, πληρωμή, πληρωμη, κάρτα, καρτα, αναληψη
```

### Credit/incoming

Examples:

```text
received, credited, refund, deposit, salary, incoming transfer
```

Usually reject for expense capture.

### Transfer

Split:

```text
sent, transferred to, outgoing, debited
```

vs.

```text
received, incoming, credited
```

Outgoing transfer may be reviewable; incoming transfer should be rejected for expense pipeline unless product supports income capture.

---

## Step 3.6 — Finance package rules

For packages in `FINANCE_PACKAGES`:

Old:

```text
amount/currency OR generic transaction keyword => allow
```

New:

```text
hard deny first;
amount required;
strong expense/debit => allow;
outgoing transfer => allow/review;
ambiguous amount + weak financial keyword => reviewable allow only if not balance/account/currency-only;
currency-only/balance-only/security/promo => reject.
```

Examples:

| Notification | Expected |
|---|---:|
| “Card purchase €12.30 at LIDL” | allow |
| “You paid 8.40 EUR” | allow |
| “Available balance €1,240.00” | reject |
| “Your monthly statement is ready” | reject |
| “EUR/USD rate changed” | reject |
| “Security code 123456” | reject |
| “Payment declined €20” | reject unless product wants failed-payment review |
| “Incoming transfer €100 received” | reject |
| “Transfer sent €50 to John” | allow/reviewable |

---

## Step 3.7 — Communication package rules

For `COMMUNICATION_PACKAGES`, keep stricter behavior:

```text
amount + financial keyword + not hard-deny
```

But use the new detector so Gmail/SMS alerts benefit from:

- balance-only rejection;
- security rejection;
- incoming-only rejection.

---

## Step 3.8 — Unknown package rules

For unknown packages:

```text
amount + strong expense/debit keyword + not hard-deny
```

Do not allow weak generic finance words from unknown packages.

---

## Step 3.9 — Service diagnostics

When filter rejects, use decision reason:

```kotlin
val decision = notificationFilter.decide(...)
if (!decision.capture) {
    emitFilterDrop(reason = decision.reason)
    return
}
```

Do not store raw text in diagnostics.

Safe metadata:

```text
filterReason
confidence
hasAmountSignal
direction
packageNameHash
```

---

## PR 3 tests

### Allow tests

1. Revolut card purchase.
2. Google Wallet NFC/card payment.
3. Greek bank card charge.
4. Greeklish purchase.
5. outgoing transfer with debit/sent wording.
6. SMS bank purchase alert.
7. Gmail bank purchase alert.

### Reject tests

1. balance-only finance alert with currency amount.
2. available balance update.
3. account statement notification.
4. FX/currency-rate alert.
5. security code / OTP.
6. login attempt.
7. cashback offer / promo.
8. incoming transfer / credited.
9. salary/deposit.
10. payment failed.
11. card declined.
12. amount-only message with no action.
13. generic unknown package with currency only.

### Regression tests

1. Existing valid Revolut examples still captured.
2. Existing Greek bank purchase examples still captured.
3. Communication app requires amount + financial keyword.
4. Ignored packages remain rejected.
5. `shouldCapture()` remains backward compatible.

## PR 3 acceptance criteria

- Finance package notifications are no longer accepted solely because they contain currency/amount.
- Balance-only and currency-only alerts are rejected.
- Real card purchase/payment alerts are still captured.
- Filter returns structured reasons.
- Diagnostics include safe filter reason metadata.
- Tests cover finance, communication, and unknown package behavior.

---

# Recommended implementation order

1. **PR 1 — Capture gate before extraction**
   - fixes the privacy boundary first.
   - makes refresh and listener paths easier to unify.

2. **PR 2 — Refresh dedupe + fingerprint duplicate check**
   - depends on shared capture flow.
   - should also depend on P1-P1-01 outcome return if possible.

3. **PR 3 — Finance detector v2**
   - can be built in parallel but should land after PR 1 if diagnostics/gate flow is being refactored.

---

# Do not mix into these PRs

Keep these out of scope:

- durable intake queue / process-death recovery;
- full in-memory success-TTL dedupe rewrite;
- full multi-currency parser fallback P2-10;
- AI parser provenance;
- MessagingStyle extraction P1-P1-03;
- location/foreground-service fix.

Small exception:

- PR 2 should add `existsByDedupeFingerprint()` because it directly fixes refresh duplicate work.

---

# Final validation commands

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
grep -R "processNotificationBypassDedupe" app/src/main/java
grep -R "isPackageBlockedFast" app/src/main/java
grep -R "capturePrivacyDenied" app/src/main/java
grep -R "PrivacyDecision.NotApplicable" app/src/main/java/com/yourname/expensetracker/service
grep -R "existsByDedupeFingerprint" app/src/main/java
grep -R "FINANCE_PACKAGES" app/src/test
```

Expected:

- no refresh path bypassing dedupe;
- no extras extraction before gate allowed;
- no fake blocked-package drops due only to cache not loaded;
- fingerprint duplicate check exists before parser;
- finance filter tests include balance/currency-only rejects.

---

# Tracker update after these PRs

After PR 1:

| ID | New status |
|---|---:|
| P1-P1-05 | Fixed |

After PR 2:

| ID | New status |
|---|---:|
| P2-08 | Fixed |

After PR 3:

| ID | New status |
|---|---:|
| P2-09 | Fixed or Mostly Fixed |

Use “Mostly Fixed” for P2-09 only if you intentionally defer:
- user-configurable deny keywords;
- full shared currency detector with P2-10;
- income-vs-expense product policy.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/e781c226862234ed412914884e98d22165a41a95
- `NotificationCaptureService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `NotificationFilter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt
- `NotificationRepository.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
- `NotificationProcessingPipeline.kt`: https://github.com/panospao7/Cost-agregator/blob/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `RawNotificationDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt
- `RawNotification.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt
- `RawNotificationFingerprint.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/e781c226862234ed412914884e98d22165a41a95/app/src/main/java/com/yourname/expensetracker/domain/notification/RawNotificationFingerprint.kt