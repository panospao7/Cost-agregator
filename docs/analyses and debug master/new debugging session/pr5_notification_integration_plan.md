# PR 5 — Notification Integration

## Baseline

Current state at `6fee004aa141878820db9240d751ea22f20c4a52`:

- `NotificationProcessingPipeline` already:
  - routes notifications into `AUTO_ACCEPT`, `NEEDS_REVIEW`, `AUTO_REJECT`, `DUPLICATE`
  - creates `PendingReview` rows for parser-failed / signal paths
  - passes `rawNotificationId` into auto-accept expense creation
  - sanitizes review text through `sanitizePendingReviewText(...)`
  - emits pipeline diagnostics
- `NotificationRepository.processAndSave(...)` still returns `Unit`, so the listener/service cannot react to the real outcome.
- `RawNotification` already has a durable `dedupeFingerprint`.
- `PendingReview` already has `rawNotificationId` and `scannedReceiptId`.
- PR1–PR4 are assumed merged:
  - source-link schema/writer exists
  - coordinator persists expense source links
  - pending-review promotion exists
  - receipt/email provenance exists

## Goal

Finish the notification ingress path so it is:

- provenance-aware
- privacy-safe
- outcome-aware
- duplicate-safe
- compatible with the universal source-link model

In practice:

- auto-accepted notification expenses must keep their raw-notification provenance chain
- notification-created pending reviews must get durable source links
- review approval must preserve the raw-notification → review → expense chain
- duplicate notifications must be recorded safely without fabricating fake provenance

---

## Non-goals

Do **not** do these in PR5:

- new source-link schema work
- bank/email/receipt refactors
- backup/restore queue redesign
- durable intake queue / replay worker
- replacing `PipelineDiagnosticEvent`
- removing legacy notification fields
- changing receipt link semantics

---

## Files to modify

### Likely
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt`
- the notification listener/capture service that calls `NotificationRepository.processAndSave(...)`
- `app/src/test/.../NotificationProcessingPipelineTest.kt`
- `app/src/test/.../NotificationRepositoryTest.kt`
- listener/service tests

### New helpers
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationCaptureDiagnostics.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationCaptureGate.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationSourceSummaryFactory.kt`

---

## 1. Make notification ingress outcome-aware

### Change `NotificationRepository`
Update:

```kotlin
suspend fun processAndSave(...): NotificationPipelineOutcome
suspend fun processAndSaveAll(...): List<NotificationPipelineOutcome>
```

Instead of returning `Unit`, return the pipeline outcome.

Keep the existing Timber logs, but do not rely on them as the only result channel.

### Why
The capture/service layer needs the real outcome to:
- preserve dedupe-window behavior correctly
- record service-level drops/duplicates
- keep orchestration and diagnostics truthful

---

## 2. Add a notification capture gate

Create `NotificationCaptureGate` for pre-extraction decisions.

It should combine:
- restore/write barrier state
- privacy settings
- blocked-package cache
- service shutdown / cancellation state

Return a small decision object:

```kotlin
data class NotificationCaptureDecision(
    val allowed: Boolean,
    val reasonCode: String?,
    val stage: String
)
```

### Rule
Do not read extras/title/text before the gate allows it.

### Use reason codes like
- `DROPPED_RESTORE_MODE`
- `DROPPED_PRIVACY`
- `DROPPED_FILTER`
- `DROPPED_BLOCKED_PACKAGE`
- `DROPPED_CANCELLED`
- `DROPPED_SERVICE_SHUTDOWN`

### Note
This is capture-layer logic, not pipeline logic. The pipeline should assume normalized input.

---

## 3. Add a capture diagnostics recorder

Create `NotificationCaptureDiagnostics` as the structured recorder for ingress-stage events.

Methods should cover:
- `recordReceived(...)`
- `recordDroppedPrivacy(...)`
- `recordDroppedRestore(...)`
- `recordDroppedFilter(...)`
- `recordDroppedBlockedPackage(...)`
- `recordDroppedCancelled(...)`
- `recordPipelineOutcome(...)`
- `recordDuplicateAttempt(...)`

Use the existing `DiagnosticEventWriter` and `SafeEventMetadata` style.

### Metadata rules
Allowed:
- hashed package name
- notification fingerprint hash (`dedupeFingerprint`)
- correlation ID
- reason codes
- stage
- outcome
- maybe rawNotificationId if a row exists

Blocked:
- raw title/text/body
- raw extras
- raw message IDs
- any un-hashed external identifiers

---

## 4. Preserve the current notification privacy behavior

The current pipeline already sanitizes review text through `sanitizePendingReviewText(...)`.

PR5 should:
- keep that path
- make it a regression-tested contract
- ensure no notification path reintroduces raw title/text into `pending_reviews`

### Required tests
- `pending_review_text_is_sanitized_for_store_redacted`
- `pending_review_text_is_null_or_masked_for_metadata_only`
- `pending_review_text_is_not_raw_when_storage_is_do_not_store`
- `duplicate_notification_metadata_contains_no_raw_text`

---

## 5. Auto-accept path

### What should happen
For auto-accepted notifications:

1. notification is allowed through the capture gate
2. raw notification is stored
3. pipeline routes `AUTO_ACCEPT`
4. `handleAutoAcceptInTransaction(...)` calls the coordinator with `rawNotificationId`
5. PR2 persists the durable `RAW_NOTIFICATION -> EXPENSE` source link
6. pipeline continues writing its existing audit/diagnostic events

### PR5 responsibility
PR5 should **not** reimplement source-link persistence here.
It should:
- preserve the `rawNotificationId` handoff
- add end-to-end tests that the raw-notification chain still reaches the expense
- verify the returned outcome is propagated back to the caller

### Acceptance test
- `notification_auto_accept_source_link`

---

## 6. Needs-review path

### What should happen
For parser-failed or routed `NEEDS_REVIEW` notifications:

1. capture gate allows ingress
2. raw notification is stored
3. pipeline creates `PendingReview`
4. review text is sanitized according to storage mode
5. PR3’s pending-review source-link service writes:
   - `target = PENDING_REVIEW`
   - `source = RAW_NOTIFICATION`
   - role `REVIEWED_FROM`
6. later approval promotes that provenance to the expense

### PR5 responsibility
PR5 should ensure the notification path always provides:
- `rawNotificationId`
- correlation ID
- sanitized review payload
- consistent duplicate/result handling

### Important
Do not fabricate raw-text provenance in the review row. Keep using the sanitizer.

### Acceptance test
- `notification_needs_review_source_link`

---

## 7. Review-approval chain

PR5 should add an end-to-end test covering:

```text
raw notification -> pending review -> approved expense
```

The expense must end up with:
- the direct `PENDING_REVIEW / APPROVED_FROM` link
- the promoted `RAW_NOTIFICATION` link from the review chain

This is mostly PR3 behavior, but PR5 must verify the notification-created review path feeds it correctly.

### Acceptance test
- `notification_review_approval_chain_raw_notification_to_expense`

---

## 8. Duplicate notification attempts

### Rule
Do **not** create fake source links for raw duplicates that never produced a durable target entity.

If the notification is dropped before raw insert:
- record a duplicate capture diagnostic
- include hashed fingerprint / reason / correlation ID
- do not invent a review or expense link

If a duplicate is detected after a raw row exists:
- record the duplicate attempt against that raw notification
- keep the source chain safe and idempotent

### Metadata source
Use `RawNotification.dedupeFingerprint` as the stable hashed identity.

### Suggested duplicate metadata
- `packageNameHash`
- `notificationFingerprintHash`
- `duplicateReasonCode`
- `stage`
- `correlationId`

### Acceptance test
- `notification_duplicate_records_duplicate_source_attempt`

---

## 9. Keep outcome propagation truthful

The listener/service that calls `NotificationRepository.processAndSave(...)` should:
- inspect the returned `NotificationPipelineOutcome`
- record service-level diagnostics
- keep or release any in-memory dedupe state based on the real outcome
- not treat every call as “success”

This is the main reason `processAndSave(...)` must stop returning `Unit`.

---

## 10. What not to do in PR5

Do not:
- add another provenance table
- change `ReceiptExpenseLink`
- modify bank/email/receipt provenance logic
- build the durable intake queue yet
- replace `PipelineDiagnosticEvent`
- store raw notification bodies/titles in new metadata

---

## 11. Test plan

### Repository / contract tests
- `notification_repository_returns_pipeline_outcome`
- `notification_repository_batch_returns_outcomes`
- `notification_outcome_is_preserved_for_auto_accept`
- `notification_outcome_is_preserved_for_needs_review`
- `notification_outcome_is_preserved_for_duplicate`

### Capture-gate tests
- `dropped_restore_mode_is_recorded_before_extraction`
- `dropped_privacy_is_recorded_before_extraction`
- `dropped_blocked_package_is_recorded_before_extraction`
- `gate_denies_without_reading_notification_extras`

### Provenance tests
- `notification_auto_accept_source_link`
- `notification_needs_review_source_link`
- `notification_review_approval_chain_raw_notification_to_expense`

### Duplicate/privacy tests
- `notification_duplicate_records_duplicate_source_attempt`
- `duplicate_notification_metadata_has_no_raw_title_or_text`
- `notification_pending_review_text_is_sanitized_for_current_storage_mode`

---

## 12. Suggested implementation order

1. Change `NotificationRepository` to return outcomes.
2. Add `NotificationSourceSummaryFactory`.
3. Add `NotificationCaptureDiagnostics`.
4. Add `NotificationCaptureGate`.
5. Wire the notification listener/service to use the gate and diagnostics.
6. Keep the existing `NotificationProcessingPipeline` sanitizer path intact.
7. Add end-to-end tests for:
   - auto-accept
   - needs-review
   - approval chain
   - duplicates
8. Verify no raw notification text leaks into diagnostics or review rows.

---

## 13. Definition of done

PR5 is done when:

- notification ingress is outcome-aware
- auto-accepted notifications still produce a correct raw-notification provenance chain
- needs-review notifications still produce durable review provenance
- approved notification reviews keep the raw-notification chain to the expense
- duplicate notifications are recorded safely with hashed metadata only
- the listener does not read notification content before capture is allowed
- existing pipeline diagnostics remain intact

---

## Sources checked

- Latest commit:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52
- `NotificationProcessingPipeline.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `NotificationRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
- `RawNotification.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt
- `PendingReview.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt
- `CreateExpenseRequest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- Pipeline 1 static report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline1_static_debug_report_b6abe0a.md
- Global source-links plan:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/global_source_links_provenance_plan.md