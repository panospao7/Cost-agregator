# PR 3 — Pending Review Source-Link Promotion Implementation Plan

## Assumptions

This PR assumes PR1 and PR2 are already merged:

- `entity_source_links` exists.
- `SourceLinkWriter` can link any `SourceTargetRef`.
- `TransactionLifecycleCoordinator` maps `CreateExpenseRequest` legacy source fields to expense source links.
- Duplicate source-link policy exists in the coordinator.
- `LifecycleEventType.SOURCE_LINKED` is already used for expense source-link writes.

If PR1/PR2 are not merged, stop and merge them first.

---

# 1. Current baseline facts checked

At `6fee004aa141878820db9240d751ea22f20c4a52`:

## `PendingReview`

`PendingReview` already has narrow source fields:

```text
rawNotificationId
scannedReceiptId
```

But there is no generic source-link row for the review itself.

## `NotificationProcessingPipeline`

Pending reviews are created in several paths:

```text
parser-failed oversized candidate
parser-failed transaction-signal candidate
normal NEEDS_REVIEW routing path
```

All of these eventually call:

```kotlin
pendingReviewDao.upsertByRawNotificationId(review)
```

## `ReviewQueueRepository.approveReview()`

Approval currently:

1. loads review
2. validates user overrides
3. builds an `Expense`
4. transitions review `PENDING -> PROCESSING`
5. performs a local duplicate pre-check
6. if duplicate, marks review `DUPLICATE` and does not call coordinator
7. otherwise calls `TransactionLifecycleCoordinator.createExpense(..., SideEffectMode.DEFER)`
8. passes these source fields into `CreateExpenseRequest`:

```text
rawNotificationId
pendingReviewId
scannedReceiptId
```

9. links receipt functionally via `ReceiptLinkService`
10. marks review `APPROVED`

Important current bug:

```text
Review duplicate pre-check bypasses coordinator duplicate events/source outcome.
```

PR3 fixes that.

---

# 2. Goal

Make review provenance durable through the full chain:

```text
source object -> PendingReview -> Expense
```

Required invariants:

```text
1. PendingReview creation writes target=PENDING_REVIEW source links.

2. Review approval promotes PendingReview source links to the created Expense.

3. The created Expense has a direct PENDING_REVIEW / APPROVED_FROM link.

4. Duplicate review approval records a durable duplicate/source outcome.

5. Review status changes, expense creation, source-link promotion, receipt link,
   correction write, and stats updates remain one atomic DB transaction.

6. No raw notification/email/bank text is persisted in source-link metadata.
```

---

# 3. Non-goals

Do not include these in PR3:

- Export/import of source links.
- Backfill worker.
- UI/debug provenance screen.
- Receipt/email/bank full integration.
- Removing legacy `PendingReview.rawNotificationId` / `scannedReceiptId`.
- Replacing `ReceiptExpenseLink`.
- New source-link schema migration.
- Broad side-effect dispatcher refactor.

---

# 4. Files to add

```text
app/src/main/java/com/yourname/expensetracker/domain/provenance/PendingReviewSourceLinkService.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/PendingReviewSourceLinkPromoter.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/PendingReviewSourcePayloadFactory.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/PendingReviewPromotionResult.kt
app/src/test/java/com/yourname/expensetracker/domain/provenance/PendingReviewSourcePayloadFactoryTest.kt
app/src/test/java/com/yourname/expensetracker/domain/provenance/PendingReviewSourceLinkPromoterTest.kt
```

Optional if you keep review-specific code near transaction lifecycle:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/PendingReviewSourceLinkPromoter.kt
```

But provenance package is cleaner.

---

# 5. Files to modify

```text
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryTest.kt
app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt
```

Possibly:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt
```

only if PR1 did not add `getForTarget(...)`.

---

# 6. New service: `PendingReviewSourceLinkService`

## Purpose

Centralize all source-link writes where the target is:

```text
TargetEntityType.PENDING_REVIEW
```

Do not duplicate this logic inside notification, receipt, bank, or email pipelines.

## Interface

```kotlin
interface PendingReviewSourceLinkService {
    suspend fun linkSourcesForReview(
        review: PendingReview,
        reviewId: Long,
        sourceType: ExpenseSource,
        correlationId: String?,
        context: PendingReviewSourceContext = PendingReviewSourceContext.empty()
    ): PendingReviewSourceLinkResult
}
```

## `PendingReviewSourceContext`

```kotlin
data class PendingReviewSourceContext(
    val stage: String? = null,
    val reason: String? = null,
    val parserId: String? = null,
    val parserVersion: String? = null,
    val routingDecision: String? = null,
    val confidence: Float? = null,
    val extractionState: String? = null,
    val packageName: String? = null
) {
    companion object {
        fun empty() = PendingReviewSourceContext()
    }
}
```

Privacy rule:

```text
packageName must be hashed or omitted if metadata policy does not allow it.
Do not store notification title/text/body.
```

---

# 7. Source payload mapping for pending reviews

Create:

```kotlin
object PendingReviewSourcePayloadFactory {
    fun fromReview(
        review: PendingReview,
        sourceType: ExpenseSource,
        context: PendingReviewSourceContext
    ): List<SourceLinkPayload>
}
```

## 7.1 `rawNotificationId`

If present:

```text
target = PENDING_REVIEW
sourceEntityType = RAW_NOTIFICATION
sourceEntityLocalId = review.rawNotificationId
role = REVIEWED_FROM
status = ACTIVE
isPrimary = true
sourceType = sourceType
confidence = review.confidence
```

Recommended `sourceType` for notification-created reviews in PR3:

```kotlin
ExpenseSource.REVIEW_APPROVAL
```

Reason:

```text
Do not add a new ExpenseSource enum in PR3 unless you want to update every
exhaustive source mapping and static guard from PR2. The concrete source entity
RAW_NOTIFICATION is what matters for provenance correctness.
```

If later you add `NOTIFICATION_REVIEW`, do it as a separate enum-cleanup PR.

## 7.2 `scannedReceiptId`

If present:

```text
target = PENDING_REVIEW
sourceEntityType = SCANNED_RECEIPT
sourceEntityLocalId = review.scannedReceiptId
role = REVIEWED_FROM
status = ACTIVE
isPrimary = review.rawNotificationId == null
sourceType = sourceType
confidence = review.confidence
```

Do not create `ReceiptExpenseLink` here. This is only review provenance.

## 7.3 No source fields

If both are null:

```text
No source link is required.
```

Do not create `UNKNOWN` review source links for every manual/internal review unless there is a real source identity.

---

# 8. Link pending-review sources at review creation

## 8.1 `NotificationProcessingPipeline`

Every place that does:

```kotlin
val reviewId = pendingReviewDao.upsertByRawNotificationId(review)
```

must immediately call:

```kotlin
pendingReviewSourceLinkService.linkSourcesForReview(
    review = review,
    reviewId = reviewId,
    sourceType = ExpenseSource.REVIEW_APPROVAL,
    correlationId = correlationId,
    context = PendingReviewSourceContext(
        stage = "notification_needs_review",
        reason = "...",
        confidence = review.confidence,
        extractionState = review.extractionState.name,
        packageName = notification.packageName
    )
)
```

This must happen inside the existing `database.withTransaction`.

Required insertion sites:

```text
1. parser-failed oversized candidate branch
2. parser-failed transaction-signal candidate branch
3. normal RoutingDecision.NEEDS_REVIEW path
```

## 8.2 `ReviewQueueRepository.markAsRelevant()`

This method can create a placeholder pending review for manually recovered raw notifications.

After:

```kotlin
pendingReviewDao.upsertByRawNotificationId(pendingReview)
```

call the same service.

Context:

```text
stage = "manual_mark_relevant_pending_review"
reason = "manual_recovery_placeholder"
extractionState = SYNTHETIC_PLACEHOLDER
```

## 8.3 Idempotency with upsert

`PendingReviewDao.upsertByRawNotificationId()` can return an existing review ID.

The source-link insert must be idempotent through the unique index:

```text
targetEntityType + targetEntityId + sourceIdentityKey
```

Expected result:

```text
first call -> Inserted
repeat/upsert call -> AlreadyExists
```

Do not treat `AlreadyExists` as an error.

---

# 9. New promoter: `PendingReviewSourceLinkPromoter`

## Purpose

Copy/promote source links from:

```text
target = PENDING_REVIEW, targetId = reviewId
```

to:

```text
target = EXPENSE, targetId = expenseId
```

during approval.

## Interface

```kotlin
interface PendingReviewSourceLinkPromoter {
    suspend fun promotePendingReviewLinksToExpense(
        pendingReviewId: Long,
        expenseId: Long,
        correlationId: String?,
        source: ExpenseSource = ExpenseSource.REVIEW_APPROVAL
    ): PendingReviewPromotionResult
}
```

## Result

```kotlin
data class PendingReviewPromotionResult(
    val attempted: Int,
    val inserted: Int,
    val alreadyExists: Int,
    val failed: Int,
    val failures: List<String> = emptyList()
) {
    val hasFatalFailure: Boolean get() = failed > 0
}
```

---

# 10. Promotion rules

Given each `EntitySourceLink` attached to the pending review, create an equivalent expense-target link.

## 10.1 Role transformation

Use a deterministic policy:

```text
PENDING_REVIEW source link role     Expense promoted role
--------------------------------------------------------
REVIEWED_FROM                       CREATED_FROM
LINKED_PROOF                        LINKED_PROOF
DUPLICATE_MATCHED                   DUPLICATE_MATCHED
IMPORTED_FROM                       IMPORTED_FROM
GENERATED_FROM                      GENERATED_FROM
ENRICHED_BY                         ENRICHED_BY
LEGACY_BACKFILL                     LEGACY_BACKFILL
otherwise                           preserve original role
```

Rationale:

```text
The review was reviewed from the source, but the approved expense was created
from the source through the review.
```

## 10.2 Status transformation

```text
ACTIVE          -> ACTIVE
DUPLICATE       -> DUPLICATE
REDACTED        -> REDACTED
LEGACY_PARTIAL  -> LEGACY_PARTIAL
FAILED          -> do not promote unless explicitly needed
SUPERSEDED      -> do not promote by default
```

## 10.3 Metadata added during promotion

Safe metadata only:

```json
{
  "promotedFromTargetType": "PENDING_REVIEW",
  "promotedFromPendingReviewId": 123,
  "promotedFromSourceLinkId": 456,
  "promotedFromRole": "REVIEWED_FROM"
}
```

Do not copy unsafe/raw metadata.

Use `SafeProvenanceMetadata.mergeSafe(...)` or equivalent from PR1.

## 10.4 Primary link policy

The direct pending-review approval link should be primary:

```text
sourceEntityType = PENDING_REVIEW
role = APPROVED_FROM
isPrimary = true
```

Promoted raw/receipt/bank/email links should usually be:

```text
isPrimary = false
```

Exception:

```text
If no PENDING_REVIEW link is written for some reason, the strongest original
source may remain primary.
```

But normal PR3 flow must always write the `PENDING_REVIEW / APPROVED_FROM` link.

---

# 11. Approval integration in `ReviewQueueRepository.approveReview()`

## 11.1 Remove the local duplicate pre-check as a terminal branch

Current code does:

```kotlin
val isDuplicate = hasCanonicalApprovalDuplicate(expense)
if (isDuplicate) {
    sourceStatsDao.incrementDuplicate(...)
    sourceStatsDao.decrementPending(...)
    pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
    return@withTransaction txDuplicate
}
```

This bypasses coordinator duplicate event/source policy.

Replace with:

```text
Do not return early on local duplicate pre-check.
Let TransactionLifecycleCoordinator run duplicate detection.
```

Set request:

```kotlin
skipDeduplication = false
```

Remove or stop using:

```kotlin
hasCanonicalApprovalDuplicate(...)
```

This also helps with the current stale-currency bug where the precheck can use `review.suggestedCurrency` after a user currency override.

## 11.2 Build request from resolved values

Keep current resolved values:

```text
amount
currency
merchant
categoryId
type
transactionDate
transfer metadata
location metadata
```

But do not build a separate `Expense` just for duplicate precheck if it can be avoided.

Request must include:

```kotlin
source = ExpenseSource.REVIEW_APPROVAL
rawNotificationId = review.rawNotificationId
pendingReviewId = reviewId
scannedReceiptId = review.scannedReceiptId
skipDeduplication = false
correlationId = existing correlation if available
```

If PR2 added explicit `sourceLinks`, you can optionally add:

```kotlin
sourceLinks = pendingReviewSourceLinkPromoter.previewExpensePayloadsFromReview(reviewId)
```

But prefer create-then-promote for PR3 because the expense ID is not known yet.

## 11.3 Use the non-deprecated DB-only API

Replace:

```kotlin
transactionLifecycleCoordinator.createExpense(request, SideEffectMode.DEFER)
```

with:

```kotlin
transactionLifecycleCoordinator.createExpenseDbOnly(request)
```

Reason:

```text
approveReview() already owns the outer DB transaction and dispatches post-commit
side effects after commit.
```

## 11.4 On `CreateExpenseResult.Created`

Inside the same outer transaction, after coordinator returns created ID:

```kotlin
val promotion = pendingReviewSourceLinkPromoter.promotePendingReviewLinksToExpense(
    pendingReviewId = reviewId,
    expenseId = id,
    correlationId = request.correlationId,
    source = ExpenseSource.REVIEW_APPROVAL
)

if (promotion.hasFatalFailure) {
    throw IllegalStateException("Failed to promote pending-review source links: ...")
}
```

Then continue current logic:

```text
mark raw notification relevant
update stats
link receipt functionally
mark review APPROVED
insert user correction
```

Important:

```text
Promotion failure is fatal for created approval.
Rollback expense creation and review status change.
```

## 11.5 On `CreateExpenseResult.DuplicateSkipped`

Current code already marks review duplicate.

With PR2 duplicate policy, coordinator should now write:

```text
CREATE_DUPLICATE_SKIPPED
optional duplicate source links to existing expense
metadata with pendingReviewId/rawNotificationId/scannedReceiptId
```

PR3 requirement:

```text
Do not mark DUPLICATE before calling coordinator.
```

On duplicate result:

```kotlin
sourceStatsDao.incrementDuplicate(review.packageName)
sourceStatsDao.decrementPending(review.packageName)
pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.DUPLICATE)
```

Optional extra review source outcome:

```text
Do not insert another PENDING_REVIEW source link with status DUPLICATE if the
same sourceIdentityKey already has an ACTIVE link, because the unique index will
block it. Use coordinator duplicate event metadata for the duplicate outcome.
```

---

# 12. `SOURCE_LINKED` event for promotion

PR2 writes `SOURCE_LINKED` for links created inside `TransactionLifecycleCoordinator`.

PR3 promotion may add extra links not known to the request, especially future:

```text
BANK_TRANSACTION -> PENDING_REVIEW
EMAIL_RECEIPT_SOURCE -> PENDING_REVIEW
SCANNED_RECEIPT -> PENDING_REVIEW
```

Therefore, after promotion, if `inserted > 0`, write an additional event:

```text
eventType = SOURCE_LINKED
expenseId = created expense ID
source = REVIEW_APPROVAL
metadata = safe promotion summary
correlationId = approval correlation ID
```

Safe metadata:

```json
{
  "operation": "PENDING_REVIEW_SOURCE_PROMOTION",
  "pendingReviewId": 123,
  "attempted": 2,
  "inserted": 1,
  "alreadyExists": 1,
  "sourceEntityTypes": ["RAW_NOTIFICATION", "SCANNED_RECEIPT"]
}
```

Never include raw notification text/title.

---

# 13. Atomicity rule

The following must commit or rollback together during successful approval:

```text
PendingReview PENDING -> PROCESSING
Expense insert
TransactionEvent CREATED
EntitySourceLink rows from PR2 request mapping
EntitySourceLink rows promoted from PendingReview
TransactionEvent SOURCE_LINKED for promotion
ReceiptExpenseLink functional relation, if scannedReceiptId exists
PendingReview APPROVED
SourceStats changes
UserCorrection insert
RawNotification relevance mark
```

If any step fails before transaction commit:

```text
review remains PENDING
no expense remains
no promoted source links remain
no receipt link remains
```

Post-commit side effects remain outside the transaction:

```text
dispatchPostCreationSideEffects
classifier retraining
merchant alias learning
confidence cache invalidation
```

---

# 14. Privacy requirements

Allowed source-link metadata:

```text
stage
reason code
confidence
extractionState
routingDecision
parserId/parserVersion
hashed packageName
promotedFromPendingReviewId
promotedFromSourceLinkId
```

Forbidden:

```text
notificationTitle
notificationText
raw notification body
email subject/body/sender
bank description/reference
account/card/IBAN values
external un-hashed IDs
```

PR3 must add tests proving:

```text
EntitySourceLink.metadataJson does not contain review.notificationTitle
EntitySourceLink.metadataJson does not contain review.notificationText
EntitySourceLink.metadataJson does not contain raw externalFingerprint
```

---

# 15. Test plan

## 15.1 Pending-review source-link creation tests

```text
notification_needs_review_creates_pending_review_raw_notification_source_link
parser_failed_oversized_review_creates_raw_notification_source_link
parser_failed_signal_review_creates_raw_notification_source_link
mark_as_relevant_placeholder_review_creates_raw_notification_source_link
review_with_scannedReceiptId_creates_scanned_receipt_pending_review_source_link
pending_review_source_link_upsert_is_idempotent
pending_review_source_link_metadata_is_privacy_safe
```

Expected row:

```text
targetEntityType = PENDING_REVIEW
targetEntityId = reviewId
sourceEntityType = RAW_NOTIFICATION
sourceEntityLocalId = rawId
role = REVIEWED_FROM
status = ACTIVE
```

## 15.2 Promotion tests

```text
review_approval_promotes_raw_notification_link_to_expense
review_approval_promotes_scanned_receipt_link_to_expense
review_approval_adds_pending_review_approved_from_expense_link
review_approval_promotion_is_idempotent
promotion_transforms_reviewed_from_to_created_from
promotion_preserves_linked_proof_role
promotion_writes_SOURCE_LINKED_event_when_new_links_inserted
promotion_does_not_write_duplicate_event_when_all_links_already_exist
```

Expected created expense links:

```text
PENDING_REVIEW / APPROVED_FROM
RAW_NOTIFICATION / CREATED_FROM
SCANNED_RECEIPT / CREATED_FROM or LINKED_PROOF based on mapper/policy
```

## 15.3 Duplicate approval tests

```text
review_duplicate_approval_calls_coordinator_not_local_precheck
review_duplicate_approval_writes_CREATE_DUPLICATE_SKIPPED
review_duplicate_approval_marks_review_DUPLICATE
review_duplicate_approval_links_source_to_existing_expense_by_PR2_policy
review_duplicate_approval_does_not_create_new_expense
review_duplicate_approval_metadata_contains_pendingReviewId
```

## 15.4 Atomic rollback tests

```text
promotion_failure_rolls_back_created_expense
promotion_failure_rolls_back_review_status_to_PENDING
receipt_link_failure_rolls_back_promoted_source_links
coordinator_validation_failure_rolls_back_PROCESSING_status
```

Use in-memory Room where possible.

## 15.5 Privacy tests

```text
pending_review_source_metadata_has_no_notification_title
pending_review_source_metadata_has_no_notification_text
promotion_metadata_has_no_raw_notification_text
duplicate_review_event_has_no_raw_notification_text
```

---

# 16. Implementation sequence

## Step 1 — Add `PendingReviewSourcePayloadFactory`

- Map `rawNotificationId`.
- Map `scannedReceiptId`.
- Add privacy-safe metadata builder.
- Add unit tests.

## Step 2 — Add `PendingReviewSourceLinkService`

- Inject `SourceLinkWriter`.
- Link target `PENDING_REVIEW`.
- Treat `AlreadyExists` as success.
- Fatal only on actual writer failure.
- Add tests with fake writer.

## Step 3 — Wire notification review creation

Modify all pending-review creation sites in `NotificationProcessingPipeline`:

```text
oversized parser failure
transaction-signal parser failure
normal needs-review routing
```

Call the service immediately after `upsertByRawNotificationId`.

## Step 4 — Wire `markAsRelevant()` placeholder review

After placeholder review upsert, call the same service.

## Step 5 — Add `PendingReviewSourceLinkPromoter`

- Inject `EntitySourceLinkDao`.
- Inject `SourceLinkWriter`.
- Inject event writer or return summary for caller to write event.
- Read target `PENDING_REVIEW`.
- Transform payloads to target `EXPENSE`.
- Add tests.

## Step 6 — Refactor approval duplicate handling

In `ReviewQueueRepository.approveReview()`:

- remove terminal local duplicate precheck
- set `skipDeduplication = false`
- rely on coordinator duplicate path
- keep status/stat updates based on coordinator result

## Step 7 — Promote on successful approval

After `CreateExpenseResult.Created`, call promoter inside the same transaction.

Then continue:

```text
raw relevance
stats
receipt functional link
review APPROVED
correction
```

## Step 8 — Add promotion `SOURCE_LINKED` event

Write only if promotion inserted new rows.

## Step 9 — Add rollback/integration tests

Use real Room + fake writer failure.

## Step 10 — Cleanup

- Remove unused `hasCanonicalApprovalDuplicate()` if no longer used.
- Remove stale TODOs that say review source fields are not persisted, if PR2 already covered them.
- Add TODO only for future enum cleanup if desired.

---

# 17. Acceptance criteria

PR3 is done when:

```text
1. Every newly created PendingReview from notification paths has source links.

2. Review approval creates/promotes this provenance to the final Expense.

3. Expense created from review has:
   - PENDING_REVIEW / APPROVED_FROM
   - RAW_NOTIFICATION / CREATED_FROM when available
   - SCANNED_RECEIPT / CREATED_FROM or LINKED_PROOF when available

4. Future source links attached to PendingReview are automatically promoted.

5. Review duplicate approval no longer disappears through local precheck.

6. Duplicate review approval writes/uses coordinator duplicate outcome.

7. Promotion is atomic with approval.

8. Source-link inserts are idempotent.

9. No raw notification title/text/body appears in EntitySourceLink metadata.

10. Existing post-commit side-effect behavior remains unchanged.
```

---

# 18. Sources checked

- Latest commit:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52

- `PendingReview.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt

- `PendingReviewDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt

- `ReviewQueueRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

- `NotificationProcessingPipeline.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `CreateExpenseRequest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `LifecycleEventType.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt

- `TransactionEvent.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- Global source-links plan:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/global_source_links_provenance_plan.md

- Pipeline 2 static report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline2_static_debug_report_b6abe0a.md

- Pipeline 1 static report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline1_static_debug_report_b6abe0a.md