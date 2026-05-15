# Slice 6 Debug Report — Review Queue + AI Assist Cards

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/review/ReviewScreen.kt`
- `ui/screens/review/ReviewViewModel.kt`
- `ui/components/ai/CategoryAssistCard.kt`
- `ui/components/ai/DedupeAssistCard.kt`
- `ui/components/ai/ReceiptAssistCard.kt`
- connected repository/use-case surfaces:
  - `ReviewQueueRepository`
  - `NotificationRepository`
  - `ReceiptRepository`
  - `ExpenseRepository`
  - `AiArtifactRepository`
  - `AiSettingsRepository`
  - `ExplainPendingReviewUseCase`
  - `SuggestCategoryFallbackUseCase`
  - `SuggestReceiptExtractionUseCase`
  - `JudgePendingReviewDuplicateUseCase`
  - `ReceiptLifecycleCoordinator`

Sources inspected:
- Commit: https://github.com/panospao7/Cost-agregator/commit/18d442c5abb42a8997fd8b6bd04978776c5f6596
- Review source folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/review
- `ReviewViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt
- `ReviewScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt
- AI components folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai
- `CategoryAssistCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/CategoryAssistCard.kt
- `DedupeAssistCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/DedupeAssistCard.kt
- `ReceiptAssistCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/ReceiptAssistCard.kt
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 6 is very high risk because it is the bridge between captured notifications/receipts/statements and actual ledger transactions.

The current implementation is feature-rich:
- pending review queue
- swipe approve/reject
- edit-before-approve sheet
- approve all / reject all
- receipt batch processing
- bank statement import
- raw evidence/debug panels
- AI explanation
- category assist
- receipt assist
- duplicate assist
- AI quick approve preview

But it has several correctness and testability issues:

1. `ReviewViewModel` is too broad: review approval, AI artifacts, receipt batch import, bank statement import, debug storage, location geocoding, and quick approve all live in one class.
2. `ReviewScreen` is monolithic and contains large mutable UI state plus business-ish orchestration.
3. Approve/reject/edit/quick-approve mutation paths are not idempotency-safe.
4. Swipe processing IDs are added but not removed on failure.
5. Edit dialog closes immediately before persistence result is known.
6. Bulk apply/approve logic fetches the original review after approving it, which can break propagation.
7. Quick approve can be offered when duplicate checking has not completed.
8. AI assist requests do not consistently guard in-flight calls or catch thrown exceptions.
9. Receipt assist UI exposes field-specific apply buttons, but ReviewScreen wires all of them to “apply all”.
10. Raw notification evidence is displayed directly, which may conflict with privacy/redaction settings.
11. Location geocoding service is exposed directly from ViewModel to UI.
12. Batch processing, statement import, and approve-all share one coarse loading/progress state.
13. Financial display and edit copy still appears currency-ambiguous or EUR-specific.
14. There is no visible focused test infrastructure for these critical state machines.

Recommended strategy: do not rewrite the review queue at once. First add contracts around the approval lifecycle, AI assist state, and duplicate protection. Then extract review mutations and AI assist orchestration out of `ReviewViewModel`.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*ReviewViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*PendingReview*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReviewQueue*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CategoryAssist*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*DedupeAssist*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptAssist*" --stacktrace
```

Inventory tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Review*" -o \
  -iname "*PendingReview*" -o \
  -iname "*Dedupe*" -o \
  -iname "*CategoryAssist*" -o \
  -iname "*ReceiptAssist*"
```

If Compose tests exist:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Stop on first compile failure.

---

## 3. Current architecture map

### Review queue display

```text
ReviewQueueRepository.getAllPendingReviews()
        ↓
ReviewViewModel.pendingReviews
        ↓
ReviewScreen LazyColumn
        ↓
SwipeToDismissBox + ReviewCard
```

### Simple approve/reject path

```text
ReviewCard approve/reject button or swipe
        ↓
ReviewViewModel.approveReview/rejectReview
        ↓
ReviewQueueRepository.approveReview/rejectReview
        ↓
DB flow updates pendingReviews
```

### Edit-before-approve path

```text
ReviewCard edit
        ↓
EditReviewDialog local state
        ↓
ReviewViewModel.approveReviewWithEdits(...)
        ↓
ReviewQueueRepository.approveReview(...)
        ↓
optional bulk category/merchant propagation
        ↓
optional approve all identical merchant
```

### AI assist path

```text
ReviewCaptureAssistSection
        ↓
ReviewViewModel.requestCategoryAssist/requestReceiptAssist/requestDedupeAssist
        ↓
AI use case
        ↓
AiArtifactRepository diagnostics
        ↓
ReviewCaptureAssistState map keyed by reviewId
        ↓
CategoryAssistCard / ReceiptAssistCard / DedupeAssistCard
```

### Quick approve path

```text
Category assist ready
+ dedupe state check
+ reviewQuickApproveEnabled
        ↓
requestQuickApprovePreview
        ↓
confirmQuickApprove
        ↓
ReviewQueueRepository.approveReview(finalCategoryId = suggestion.categoryId)
        ↓
mark category/dedupe artifacts applied
```

---

# 4. Issues

## S6-001 — `ReviewViewModel` is a God ViewModel

Severity: High  
Files:
- `ReviewViewModel.kt`

Evidence:
`ReviewViewModel` currently owns:
- pending review streams
- approve/reject/edit approval
- bulk apply and approve-all
- batch receipt processing
- bank statement processing
- parser debug export
- debug data storage
- geocoding service exposure
- AI explanation
- category assist
- receipt assist
- dedupe assist
- AI artifact lifecycle
- quick approve preview/confirm

Problem:
Any constructor or behavior change in receipts, AI, debug, location, or review approval can break the review screen tests. This makes Slice 6 difficult for an agent to debug safely.

Fix strategy:
Extract small coordinators while keeping external UI behavior stable.

Recommended extraction:

```text
ReviewApprovalCoordinator
ReviewBulkActionCoordinator
ReviewAiAssistCoordinator
ReviewQuickApproveCoordinator
ReviewReceiptImportCoordinator
ReviewDebugCoordinator
ReviewLocationEditCoordinator
```

Minimal first pass:
1. Extract `ReviewApprovalCoordinator`.
2. Extract `ReviewAiAssistCoordinator`.
3. Move batch/statement/debug actions out of primary ViewModel or wrap behind `ReviewDebugCoordinator`.

Acceptance:
- `ReviewViewModel` becomes orchestration only.
- approval tests target `ReviewApprovalCoordinator`.
- AI assist tests target `ReviewAiAssistCoordinator`.
- no UI behavior changes during extraction except explicit bug fixes.

---

## S6-002 — `ReviewScreen.kt` is monolithic and high-blast-radius

Severity: High  
Files:
- `ReviewScreen.kt`

Evidence:
The screen owns:
- top bar
- debug menu
- launchers
- snackbar plumbing
- lazy list
- swipe actions
- review card
- AI explanation section
- AI capture assist section
- edit sheet
- quick approve dialog
- debug viewer/dialogs
- batch overlay
- clear confirmations

Problem:
Small UI bugs require compiling/testing a huge composable. It also mixes route logic, local state, debug-only UI, and review card UI.

Fix strategy:
Split into route, content, cards, dialogs, and debug surfaces.

Implementation plan:

```text
ui/screens/review/ReviewRoute.kt
ui/screens/review/ReviewScreenContent.kt
ui/screens/review/ReviewTopBar.kt
ui/screens/review/ReviewList.kt
ui/screens/review/ReviewCard.kt
ui/screens/review/ReviewAssistSection.kt
ui/screens/review/EditReviewSheet.kt
ui/screens/review/ReviewDialogs.kt
ui/screens/review/ReviewDebugMenu.kt
ui/screens/review/ReviewBatchOverlay.kt
```

Acceptance:
- route file only collects state and handles one-off events.
- `ReviewCard` is pure and independently testable.
- `EditReviewSheet` is pure and independently testable.
- debug menu is isolated and `BuildConfig.DEBUG` gated.

---

## S6-003 — Approve/reject actions are not idempotency-safe

Severity: Critical data integrity  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`
- `ReviewQueueRepository`

Evidence:
- Button approve/reject paths call the ViewModel directly.
- Swipe path has `processingIds`, but this is only UI-local and only for swipes.
- `approveReview()` and `rejectReview()` do not track per-review in-flight operations.
- `confirmQuickApprove()` also does not guard per-review in-flight approval.

Problem:
Fast taps, swipe + button, recomposition, or stale UI can call approve/reject more than once for the same review. The repository may defend this, but financial transaction creation must be guarded at the UI/service boundary too.

Fix strategy:
Introduce per-review mutation state in ViewModel/coordinator.

Implementation plan:

```kotlin
enum class ReviewMutationKind {
    APPROVE,
    REJECT,
    APPROVE_WITH_EDITS,
    QUICK_APPROVE
}

data class ReviewMutationState(
    val inFlight: Map<Long, ReviewMutationKind> = emptyMap(),
    val errors: Map<Long, UiText> = emptyMap()
)
```

Guard:

```kotlin
private fun beginMutation(reviewId: Long, kind: ReviewMutationKind): Boolean {
    if (_mutationState.value.inFlight.containsKey(reviewId)) return false
    _mutationState.update { it.copy(inFlight = it.inFlight + (reviewId to kind)) }
    return true
}
```

Always clear in `finally`.

Acceptance:
- double approve calls repository once.
- approve + reject race calls only one mutation.
- quick approve cannot run while normal approve is in flight.
- UI disables buttons/swipes for in-flight review ID.
- tests cover double-tap and swipe/button races.

---

## S6-004 — Swipe `processingIds` are never removed on failure

Severity: High UX/debuggability  
Files:
- `ReviewScreen.kt`

Evidence:
`processingIds.add(item.review.id)` is performed in swipe confirm logic. There is no observed removal path if repository approval/rejection fails and the item remains in the list.

Problem:
A failed swipe can permanently block future swipes for that review in the current composition. The row may remain visible but unusable by swipe.

Fix strategy:
Replace local `processingIds` with ViewModel mutation state from S6-003.

Short-term patch:
- remove ID when an error event for that review occurs.
- but better: no local list at all.

Acceptance:
- failed approve/reject re-enables the row.
- user can retry.
- test with fake repository failure.

---

## S6-005 — Edit dialog closes before persistence result

Severity: High  
Files:
- `ReviewScreen.kt`
- `ReviewViewModel.kt`

Evidence:
`EditReviewDialog` calls `viewModel.approveReviewWithEdits(...)` and immediately sets `editingReview = null`.

Problem:
If approval fails, user loses all edited values and receives only a snackbar. This is bad for financial data and hard to debug.

Fix strategy:
Make edit approval a stateful operation.

Implementation plan:
- `approveReviewWithEdits` emits success/failure event with reviewId.
- UI closes edit sheet only on success.
- on failure, keep sheet open and show inline error.

```kotlin
sealed interface ReviewUiEvent {
    data class EditApproveSucceeded(val reviewId: Long) : ReviewUiEvent
    data class EditApproveFailed(val reviewId: Long, val message: UiText) : ReviewUiEvent
}
```

Acceptance:
- failed edit approval keeps sheet open.
- success closes sheet.
- tests verify both paths.

---

## S6-006 — Bulk apply logic fetches original review after approval

Severity: Critical correctness  
Files:
- `ReviewViewModel.kt`

Evidence:
In `approveReviewWithEdits`, the code calls `reviewQueueRepository.approveReview(...)`, handles success, then later calls `getReviewById(reviewId)` to determine `originalMerchant`.

Problem:
If approval removes or changes the pending review, `getReviewById(reviewId)` may return null or changed data. Then:
- apply-to-all category propagation may not run,
- merchant bulk rename may not run,
- approve-all-identical may not find the original merchant.

Fix strategy:
Fetch the original review snapshot before approving.

Implementation plan:

```kotlin
val original = reviewQueueRepository.getReviewById(reviewId)
if (original == null) {
    emitError("Review not found")
    return@launch
}

val result = reviewQueueRepository.approveReview(...)
if (result !is Result.Success) return@launch

val originalMerchant = original.suggestedMerchant
```

Better:
Create repository transaction:

```kotlin
suspend fun approveReviewWithBulkPolicy(
    command: ApproveReviewCommand
): ReviewApprovalResult
```

Where command includes:
- reviewId
- edits
- bulk category policy
- bulk merchant policy
- approve identical policy

Acceptance:
- `applyToAll` still works after first review is approved.
- original merchant is from pre-approval snapshot.
- unit test fails on old behavior and passes after fix.

---

## S6-007 — Bulk approval silently ignores per-item failures

Severity: High  
Files:
- `ReviewViewModel.kt`

Evidence:
`approveAllPending` loops identical pending reviews and calls `reviewQueueRepository.approveReview(...)`, but does not inspect each result. Exceptions are only logged in the outer catch.

Problem:
Some pending reviews can fail, duplicate, or produce errors while the UI still appears successful or partially updated.

Fix strategy:
Aggregate per-item results.

Implementation plan:

```kotlin
data class BulkReviewResult(
    val successCount: Int,
    val duplicateCount: Int,
    val errorCount: Int,
    val errors: List<String>
)
```

For each pending:
- collect `Result.Success`
- collect `Result.Duplicate`
- collect `Result.Error`

Emit a precise snackbar/event.

Acceptance:
- UI reports partial success accurately.
- failed items remain pending.
- test covers success + duplicate + error mix.

---

## S6-008 — `approveAll()` is opaque and uses fake 0/1 progress

Severity: Medium/High  
Files:
- `ReviewViewModel.kt`

Evidence:
`approveAll()` sets progress to `(0, 1)`, calls `approveAllReview()`, then sets `(1, 1)`.

Problem:
The overlay implies progress but does not reflect item count. If the queue is large or failures occur, user cannot tell what happened.

Fix strategy:
Either:
1. make approve-all repository report progress, or
2. show an indeterminate state, not fake determinate progress.

Recommended:
- Replace `Pair<Int, Int>` with typed operation state.

```kotlin
sealed interface ReviewOperationState {
    data object Idle : ReviewOperationState
    data class Processing(
        val operation: ReviewOperation,
        val current: Int? = null,
        val total: Int? = null
    ) : ReviewOperationState
}
```

Acceptance:
- approve-all overlay is indeterminate unless real progress exists.
- batch receipt processing can still show determinate progress.
- tests cover state transitions.

---

## S6-009 — Batch, statement import, approve-all, and reject-all share one coarse operation state

Severity: High  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Evidence:
`_isBatchProcessing` and `_batchProgress` are reused for:
- approve all
- receipt batch processing
- bank statement import
- maybe future actions

`cancelBatchProcessing()` cancels only `batchJob`; it cannot cancel approve-all or statement import.

Problem:
Operation states can race. A statement import and approve-all can overwrite each other’s loading/progress flags. Cancel action semantics are unclear.

Fix strategy:
Use typed operation state and separate jobs.

Implementation plan:

```kotlin
enum class ReviewOperation {
    APPROVE_ALL,
    REJECT_ALL,
    RECEIPT_BATCH_IMPORT,
    BANK_STATEMENT_IMPORT
}

data class ReviewOperationUiState(
    val operation: ReviewOperation,
    val current: Int? = null,
    val total: Int? = null,
    val canCancel: Boolean = false
)
```

Track:
- `batchImportJob`
- `statementImportJob`
- `bulkReviewJob`

Acceptance:
- only one global operation can run at a time unless explicitly allowed.
- cancel only appears for cancellable jobs.
- statement import cannot be cancelled by “cancel batch” unless supported.
- tests cover operation race prevention.

---

## S6-010 — Quick approve can be offered without a completed duplicate check

Severity: Critical financial correctness  
Files:
- `ReviewViewModel.kt`

Evidence:
`canOfferQuickApprove()` requires category suggestion ready, but the dedupe logic allows quick approve when `dedupeSuggestion` is not ready because nullable comparison to `LIKELY_DUPLICATE` returns true.

Problem:
Quick approve can create a transaction even when duplicate judging was never run. This defeats the purpose of an AI-assisted safe path.

Fix strategy:
Require an explicit safe dedupe verdict.

Policy options:
- Strict: only `LIKELY_DISTINCT` allows quick approve.
- Conservative: `LIKELY_DISTINCT` allows, `UNCERTAIN` requires manual review, `LIKELY_DUPLICATE` blocks.
- If dedupe disabled, quick approve disabled.

Recommended:
```kotlin
val dedupeReady = state.dedupeSuggestion as? AiLoadState.Ready ?: return false
return dedupeReady.value.verdict == DuplicateVerdict.LIKELY_DISTINCT
```

Acceptance:
- no dedupe state => no quick approve.
- loading dedupe => no quick approve.
- uncertain => no quick approve unless product explicitly allows with warning.
- likely duplicate => no quick approve.
- tests cover all verdicts.

---

## S6-011 — Quick approve preview is cleared before result

Severity: Medium/High  
Files:
- `ReviewViewModel.kt`

Evidence:
`confirmQuickApprove()` sets `_quickApprovePreview.value = null` before calling repository approval.

Problem:
If approval fails, user loses preview context and may not understand what failed.

Fix strategy:
Keep preview open during save, or close only on success.

Implementation plan:
Add:

```kotlin
data class ReviewQuickApprovePreview(
    ...
    val isSubmitting: Boolean = false,
    val error: UiText? = null
)
```

On confirm:
- set `isSubmitting = true`
- on success: clear
- on failure: keep preview with error

Acceptance:
- quick approve failure keeps preview with retry/cancel.
- buttons disabled while submitting.
- test covers repository error.

---

## S6-012 — AI assist request methods lack robust in-flight guards and exception handling

Severity: High  
Files:
- `ReviewViewModel.kt`

Affected:
- `requestCategoryAssist`
- `requestReceiptAssist`
- `requestDedupeAssist`
- partly `loadAiExplanation`

Evidence:
- `loadAiExplanation` has an in-flight set, but the set is mutated inside the launched coroutine, so two immediate calls can launch duplicate jobs before the first marks in-flight.
- category/receipt/dedupe request methods have no in-flight guard.
- category/receipt/dedupe request methods do not wrap use cases in `try/catch`; thrown exceptions can leave state stuck in Loading.

Problem:
Repeated taps can issue duplicate AI calls/artifact writes. A thrown use case can permanently show Loading.

Fix strategy:
Centralize AI assist job management.

Implementation plan:

```kotlin
private val inFlightAssist = mutableSetOf<Pair<Long, AiCapability>>()

private fun launchAssist(
    reviewId: Long,
    capability: AiCapability,
    setLoading: () -> Unit,
    setError: (String) -> Unit,
    block: suspend () -> Unit
) {
    val key = reviewId to capability
    if (!inFlightAssist.add(key)) return
    viewModelScope.launch {
        setLoading()
        try {
            block()
        } catch (e: Exception) {
            Timber.e(e)
            setError(e.message ?: "AI assist failed")
        } finally {
            inFlightAssist.remove(key)
        }
    }
}
```

Important: add to set before launching.

Acceptance:
- rapid repeated taps call each AI use case once.
- thrown AI use case maps to `AiLoadState.Error`.
- state never remains Loading after exception.
- tests use fake use cases and virtual time.

---

## S6-013 — Receipt assist field-specific apply buttons are wired as apply-all

Severity: High UX correctness  
Files:
- `ReviewScreen.kt`
- `ReceiptAssistCard.kt`

Evidence:
`ReceiptAssistCard` exposes:
- `onApplyMerchant`
- `onApplyTotal`
- `onApplyDate`
- `onApplyAll`

But `ReviewScreen` passes the same callback for all of them, which calls `applyReceiptSuggestion()` and pre-fills all available fields.

Problem:
The UI says the user can apply just merchant/total/date, but every field button applies all fields. That is misleading.

Fix strategy:
Make receipt apply intent field-specific.

Implementation plan:

```kotlin
enum class ReceiptPrefillField {
    MERCHANT,
    TOTAL,
    DATE,
    ALL
}

fun applyReceiptSuggestion(reviewId: Long, field: ReceiptPrefillField)
```

`ReviewReceiptPrefill` should include only requested fields.

Wire:
```kotlin
onApplyMerchant = { viewModel.applyReceiptSuggestion(id, MERCHANT) }
onApplyTotal = { viewModel.applyReceiptSuggestion(id, TOTAL) }
onApplyDate = { viewModel.applyReceiptSuggestion(id, DATE) }
onApplyAll = { viewModel.applyReceiptSuggestion(id, ALL) }
```

Acceptance:
- applying merchant changes only merchant.
- applying total changes only amount.
- applying date changes only date.
- apply-all changes all fields.
- component test verifies field buttons invoke distinct callbacks.

---

## S6-014 — Category assist apply is also indirect and dialog-dependent

Severity: Medium  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Current behavior:
- Apply category suggestion stores category ID in `_prefilledCategorySuggestions`.
- UI opens edit dialog.
- `LaunchedEffect` consumes the prefill map.

Problem:
This two-step event-map pattern is fragile. It depends on local `editingReview` state, `LaunchedEffect` timing, and map consumption. It is easy to break under recomposition.

Fix strategy:
Use explicit one-off UI events.

Implementation plan:

```kotlin
sealed interface ReviewUiEvent {
    data class OpenEditWithPrefill(
        val reviewId: Long,
        val categoryId: Long? = null,
        val receiptPrefill: ReviewReceiptPrefill? = null
    ) : ReviewUiEvent
}
```

Acceptance:
- applying category emits one event.
- event opens edit sheet with prefill exactly once.
- no map consumption race.
- test uses Turbine or fake event collector.

---

## S6-015 — EditReviewDialog does not validate amount/merchant before save

Severity: High financial correctness  
Files:
- `ReviewScreen.kt`

Evidence:
`EditReviewDialog` parses amount with `AmountUtils.parseAmount(amount)`. If parsing fails, edited amount becomes null, so the original amount is silently kept. Blank merchant can also become an edited merchant.

Problem:
A user can type an invalid amount and tap confirm; instead of an error, the app may approve the original amount. That is dangerous.

Fix strategy:
Add local or ViewModel validation before calling save.

Implementation plan:
Create:

```kotlin
data class ReviewEditInput(
    val amountText: String,
    val merchant: String,
    val categoryId: Long?,
    val dateMs: Long,
    val type: TransactionType
)

sealed interface ReviewEditValidationResult {
    data class Valid(val command: ApproveReviewCommand) : ReviewEditValidationResult
    data class Invalid(val errors: List<ReviewEditFieldError>) : ReviewEditValidationResult
}
```

Rules:
- merchant not blank.
- amount parse succeeds.
- amount > 0 for purchases/withdrawals.
- date not in future unless deposits/transfers policy allows.
- category must exist.
- transfer type requires direction/account if transfers are supported.

Acceptance:
- invalid amount blocks save and shows error.
- blank merchant blocks save.
- tests cover invalid amount preserving no transaction write.

---

## S6-016 — Edit review amount label is EUR-specific

Severity: High for multi-currency correctness  
Files:
- `ReviewScreen.kt`

Evidence:
The amount field label uses a resource named like “amount euro label”. `AmountText` in `ReviewCard` receives amount but no explicit currency at the call site.

Problem:
Review queue may approve transactions in the wrong perceived currency, or at least display a misleading currency label.

Fix strategy:
Make review currency explicit.

Implementation options:
1. Add currency to `PendingReview` / `PendingReviewWithReceipt`.
2. If review queue is always home currency, show home currency explicitly from `CurrencySettingsRepository`.
3. If source notification contains currency, display original currency and conversion status.

Implementation plan:
- Add `homeCurrency` to ReviewViewModel state.
- Replace EUR label with `Amount (USD)` or generic localized “Amount”.
- Ensure `approveReview` command carries currency policy.

Acceptance:
- no hardcoded EUR in review UI.
- tests with non-EUR home currency render correct label.
- approved transaction currency is deterministic.

---

## S6-017 — Transfer type can be selected but transfer metadata cannot be edited

Severity: High data integrity  
Files:
- `ReviewScreen.kt`
- `ReviewViewModel.kt`
- `ReviewQueueRepository`

Evidence:
Review card displays `TransferDirectionBadge` for transfer/deposit when suggested direction/account exist. Edit sheet allows changing transaction type to `TRANSFER`, but does not expose direction/account editing.

Problem:
A user can approve a transaction as transfer without required transfer metadata, or change away from transfer while stale metadata remains.

Fix strategy:
Align edit sheet with Slice 5 transfer model.

Implementation plan:
- Add transfer metadata fields to `EditReviewSheet` when selected type is `TRANSFER`.
- Validate direction/account.
- Add approval command fields:
  - finalTransferDirection
  - finalAccountName
- Repository approval should set or clear transfer metadata atomically.

Acceptance:
- transfer approval requires direction/account.
- changing transfer to purchase clears transfer metadata.
- tests verify repository command.

---

## S6-018 — Location geocoding service is exposed directly to UI

Severity: Medium/High  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Evidence:
`ReviewViewModel` exposes `val geocodingService`, and `EditReviewDialog` passes it to `LocationSearchPicker`.

Problem:
The UI can perform domain work directly. This also bypasses any privacy/location gating and makes tests/previews harder.

Fix strategy:
Move location search into ViewModel/coordinator state/actions.

Implementation plan:
- Add `ReviewLocationEditState`.
- ViewModel owns:
  - query
  - search loading
  - search results
  - selected result
  - errors
- UI only calls:
  - `onLocationQueryChanged`
  - `onLocationSelected`
  - `onLocationCleared`

Acceptance:
- no public `geocodingService` exposed from ViewModel.
- location search can be unit-tested with fake geocoder.
- privacy-denied location state is testable.

---

## S6-019 — Location updates cannot be cleared cleanly

Severity: Medium  
Files:
- `EditReviewDialog`
- `approveReviewWithEdits`

Evidence:
Dialog only shows Add/Edit/Hide location behavior. It tracks nullable lat/lon but does not provide a clear/remove action in the visible flow.

Problem:
If a captured location is wrong or privacy-sensitive, the user needs a clear-location option before approval.

Fix strategy:
Add clear action and command semantics.

Implementation plan:
- Add `LocationEditCommand`:
  - unchanged
  - set(lat, lon, address, placeId)
  - clear
- Update repository approval to support clear.
- UI shows “Remove location” when location exists.

Acceptance:
- user can remove captured location before approval.
- clear is distinct from unchanged.
- tests cover set vs clear vs unchanged.

---

## S6-020 — Raw notification evidence is displayed directly

Severity: High privacy/security  
Files:
- `ReviewScreen.kt`
- privacy settings/domain from Slice 3

Evidence:
`ReviewCard` renders `review.notificationText` in the raw evidence section.

Problem:
Raw notification text can contain PII, bank info, card fragments, addresses, or one-time codes. If privacy settings disable raw notification retention/display, the UI should not show this text.

Fix strategy:
Gate raw evidence display and redact by default.

Implementation plan:
- Introduce `ReviewEvidenceUi`:
  - `Redacted`
  - `Available(redactedText, canReveal)`
  - `Unavailable(reason)`
- ViewModel maps review evidence through privacy/redaction policy.
- UI shows redacted evidence by default.
- reveal requires explicit tap and maybe debug-only or privacy permission.

Acceptance:
- raw evidence hidden when privacy policy disables raw retention/display.
- OTP/card/account-like values are redacted.
- tests cover raw text not rendered by default.

---

## S6-021 — Precise location coordinates are displayed directly

Severity: Medium/High privacy  
Files:
- `ReviewScreen.kt`

Evidence:
Review card displays latitude/longitude to four decimals when present.

Problem:
Even four decimals can be location-sensitive. A financial app should not casually expose precise coordinates in the main review card.

Fix strategy:
Show human-friendly coarse location or privacy-safe chip.

Implementation plan:
- Display address/place name if available.
- Else display “Location captured” with icon.
- Put exact coordinates behind debug or explicit expand.
- Respect location privacy gate.

Acceptance:
- exact lat/lon not shown in normal mode.
- debug mode can show exact coordinates if gated.
- privacy tests cover disabled location display.

---

## S6-022 — AI disabled state UX is inconsistent

Severity: Medium  
Files:
- `ReviewScreen.kt`
- `ReviewViewModel.kt`

Evidence:
`AiExplanationSection` hides disabled state, while receipt/category/dedupe assist may show nothing or info depending state.

Problem:
Users may not know why AI buttons disappear. This is especially confusing if AI was disabled by privacy settings or provider status.

Fix strategy:
Use a consistent disabled/blocked UI.

Implementation plan:
- Add `AiDisabledReason` to assist states.
- Render a small info row:
  - “AI assist is off”
  - “Cloud AI blocked by privacy settings”
  - “No provider configured”
- Do not show retry button for disabled state.

Acceptance:
- disabled state is visible when appropriate.
- no retry loop for privacy-disabled state.
- tests cover disabled rendering.

---

## S6-023 — AI artifact lifecycle updates are best-effort and silent

Severity: Medium  
Files:
- `ReviewViewModel.kt`
- `AiArtifactRepository`

Evidence:
`applyCategorySuggestion`, `applyReceiptSuggestion`, and quick approve marking call `markApplied` asynchronously. Failures are not surfaced.

Problem:
The UI can apply a suggestion but the artifact remains unapplied in diagnostics/history. This weakens auditability.

Fix strategy:
Make artifact marking part of the assist action result, or at least log/report failures.

Implementation plan:
- Wrap artifact mark calls in `runCatching`.
- Emit non-blocking diagnostics event if marking fails.
- For quick approve, mark applied only after repository approval succeeds.

Acceptance:
- artifact mark failure is logged and visible in debug diagnostics.
- quick approve does not mark artifacts applied if approval fails.
- tests cover mark failure path.

---

## S6-024 — AI diagnostics are shown directly and can leak provider details

Severity: Medium  
Files:
- `ReviewScreen.kt`
- `ReceiptAssistCard.kt`
- `CategoryAssistCard.kt`
- `DedupeAssistCard.kt`

Evidence:
Diagnostics strings are rendered directly in user-facing cards. Receipt card checks diagnostic text for provider/model substrings and shows a cloud hint.

Problem:
Diagnostics are useful for debug but can leak provider/model/internal routing details in production. String matching provider/model names is fragile.

Fix strategy:
Separate user-safe explanation from debug diagnostics.

Implementation plan:
- `AssistDiagnosticsUi`:
  - `userMessage`
  - `debugDetails`
  - `providerKind`
  - `isCloud`
- Show `userMessage` in release.
- Show `debugDetails` only in debug or expanded diagnostics.

Acceptance:
- provider/model details hidden in release unless intentionally shown.
- no UI logic matches raw diagnostic substrings.
- tests cover debug vs release policy.

---

## S6-025 — ReviewCard has no per-action disabled/loading UI

Severity: Medium/High  
Files:
- `ReviewScreen.kt`

Problem:
Approve/reject/edit buttons remain visually enabled while operations run unless global batch processing is active. Per-review operations are not visible.

Fix strategy:
Use mutation state from S6-003.

Implementation plan:
- Pass `ReviewCardUiState`:
  - `isApproving`
  - `isRejecting`
  - `isEditing`
  - `isAnyMutationInFlight`
  - `error`
- Disable buttons while in-flight.
- Show inline spinner or overlay for that card.

Acceptance:
- user sees which review is being processed.
- retry after failure is clear.
- tests verify button disabled state.

---

## S6-026 — Review queue empty/loading/error states are weak

Severity: Medium  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Evidence:
`pendingReviews` starts as empty list. The UI treats empty list as “All caught up.”

Problem:
Initial loading and load failure can look like an empty queue. If repository flow fails, there is no separate error state.

Fix strategy:
Expose typed queue state.

Implementation:

```kotlin
sealed interface ReviewQueueUiState {
    data object Loading : ReviewQueueUiState
    data class Empty(val message: UiText) : ReviewQueueUiState
    data class Data(val items: List<PendingReviewWithReceipt>) : ReviewQueueUiState
    data class Error(val message: UiText) : ReviewQueueUiState
}
```

Acceptance:
- first load shows loading skeleton.
- true empty shows empty state.
- repository failure shows retry.
- tests cover all states.

---

## S6-027 — Batch receipt processing and bank statement import belong outside core Review UI

Severity: Medium/High architecture  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Problem:
Review queue is already complex. Batch receipt and bank statement parsing are ingestion/debug flows. Keeping them here makes ReviewViewModel too broad.

Fix strategy:
Move ingestion/debug actions to dedicated surface or coordinator.

Implementation options:
1. Keep UI entry in debug menu, but delegate to `ReviewReceiptImportCoordinator`.
2. Move import actions to Receipt Scan / Debug screen.
3. Keep only “reviews created” result visible in Review queue.

Acceptance:
- core approval tests do not require receipt import dependencies.
- receipt import tests target separate coordinator.
- debug actions still gated by `BuildConfig.DEBUG`.

---

## S6-028 — `rejectAll()` can run during other operations

Severity: Medium  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Evidence:
`rejectAll()` does not check `_isBatchProcessing` or a typed operation state. The clear queue confirmation button can be shown from debug menu while other operations may be running.

Problem:
Queue clear can race with approve-all or import operations.

Fix strategy:
Use global operation guard from S6-009.

Acceptance:
- reject-all disabled during approve-all/import.
- concurrent operation attempt emits “operation already running”.
- test covers reject-all while approve-all in progress.

---

## S6-029 — Use of `TransactionType.values()` instead of `entries`

Severity: Low  
Files:
- `ReviewScreen.kt`

Problem:
Kotlin now prefers enum `entries`. Minor but useful cleanup.

Fix:
Replace:
```kotlin
TransactionType.values()
```
with:
```kotlin
TransactionType.entries
```

Acceptance:
- no behavior change.

---

## S6-030 — AI cards lack semantic/test tags and action roles

Severity: Medium  
Files:
- `CategoryAssistCard.kt`
- `DedupeAssistCard.kt`
- `ReceiptAssistCard.kt`
- `ReviewScreen.kt`

Problem:
Targeted Compose tests will be brittle if they must locate buttons by visible text only.

Fix strategy:
Add stable semantics/test tags.

Implementation:
- `category_assist_card`
- `category_assist_apply`
- `category_assist_dismiss`
- `dedupe_assist_card`
- `dedupe_assist_dismiss`
- `receipt_assist_card`
- `receipt_assist_apply_merchant`
- `receipt_assist_apply_total`
- `receipt_assist_apply_date`
- `receipt_assist_apply_all`
- `review_card_{id}`
- `review_approve_{id}`
- `review_reject_{id}`

Acceptance:
- Compose tests use tags.
- accessibility descriptions are meaningful.

---

## S6-031 — Receipt assist amount formatting is display-only and not tied to edit parser

Severity: Medium  
Files:
- `ReceiptAssistCard.kt`
- `EditReviewDialog`
- `AmountUtils`

Evidence:
Receipt assist displays total using `String.format(Locale.US, "%.2f", total.value)`. Edit dialog amount uses `AmountUtils.parseAmount`.

Problem:
Display format and edit parser may disagree in non-US locales or edge cases.

Fix strategy:
Use shared money input/display formatter.

Acceptance:
- receipt total prefill parses in edit dialog.
- locale tests pass.
- no silent parse failure.

---

## S6-032 — Review mutation success is inferred only from DB flow

Severity: Medium  
Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Problem:
For simple approve/reject this can be fine, but for edit/quick/bulk flows the UI needs explicit success/failure to close dialogs, clear previews, and update artifact state.

Fix strategy:
Keep DB-flow observation, but add one-off mutation events.

Acceptance:
- UI does not rely only on list disappearance for mutation success.
- tests assert events.

---

# 5. Recommended new tests

## JVM tests

### `ReviewViewModelApprovalTest`

Required cases:
- approve success calls repository once.
- approve duplicate surfaces duplicate message.
- approve error surfaces error and clears in-flight.
- double approve same review calls repository once.
- approve and reject same review race calls one mutation.
- reject failure clears in-flight and surfaces error.
- approve with edits keeps original merchant snapshot before approval.
- edit approval failure emits failure event.
- apply-to-all category uses original merchant.
- approve-all-identical aggregates partial failures.
- quick approve success marks artifacts applied.
- quick approve failure does not mark artifacts applied.

### `ReviewQuickApprovePolicyTest`

Required cases:
- disabled setting blocks quick approve.
- category missing blocks.
- category ID <= 0 blocks.
- dedupe idle blocks.
- dedupe loading blocks.
- dedupe disabled blocks.
- dedupe likely duplicate blocks.
- dedupe uncertain blocks unless explicitly allowed.
- dedupe likely distinct allows.
- preview contains merchant/amount/category diagnostics.

### `ReviewAiAssistStateTest`

Required cases:
- category assist success -> Ready + diagnostics.
- category assist disabled -> Disabled.
- category assist not needed -> Error or Idle according to chosen UX.
- category assist thrown exception -> Error, not Loading.
- repeated category request while loading invokes use case once.
- receipt assist with no receipt -> Error.
- receipt assist success from cache shows cache message.
- receipt assist disabled shows disabled reason.
- dedupe success maps verdict.
- dismiss marks artifact dismissed and clears UI state.
- apply category emits open-edit event or prefill.
- apply receipt merchant only emits merchant-only prefill.
- apply receipt all emits all fields.

### `ReviewOperationStateTest`

Required cases:
- approve-all shows operation state and clears on success.
- approve-all failure clears state and reports error.
- batch import reports progress.
- batch import failure reports first error safely.
- statement import reports parsed counts.
- reject-all cannot run during batch import.
- cancel cancels only cancellable batch operation.

### `ReviewEditValidationTest`

Required cases:
- blank merchant invalid.
- invalid amount invalid.
- zero/negative amount invalid according policy.
- future date invalid if policy forbids.
- category missing invalid.
- transfer type requires transfer metadata after fix.
- location set/clear/unchanged command mapping.

---

## Compose/component tests

### `ReviewCardComponentTest`
- renders merchant, amount, confidence, category.
- approve button callback fires once.
- reject button callback fires once.
- edit callback fires.
- transfer badge visible for transfer/deposit.
- raw evidence redacted by default after privacy fix.
- per-review loading disables buttons.

### `ReviewAssistSectionTest`
- idle category shows suggest button.
- category ready shows `CategoryAssistCard`.
- dedupe likely duplicate blocks quick approve info.
- receipt no receipt hides receipt assist.
- receipt ready shows field-specific apply buttons.
- disabled AI shows info row, not retry.

### `ReceiptAssistCardTest`
- apply merchant invokes only merchant callback.
- apply total invokes only total callback.
- apply date invokes only date callback.
- apply all invokes only all callback.
- dismiss invokes dismiss callback.
- diagnostics hidden/shown according debug policy after fix.

### `EditReviewSheetTest`
- initial prefill category selected.
- receipt merchant prefill affects merchant only if requested.
- invalid amount shows inline error.
- failed save keeps sheet open.
- successful save closes sheet.
- clear location action emits clear command.

### `ReviewScreenContentTest`
- loading state shows skeleton.
- empty state shows caught-up UI.
- error state shows retry.
- approve-all confirmation disabled with zero pending.
- debug menu hidden when debug disabled.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run current Review tests.
3. Inventory review-related tests.
4. Inventory repository methods:
   - `approveReview`
   - `rejectReview`
   - `approveAllReview`
   - `rejectAllReviews`
   - `updatePendingReviewCategoryBulk`
   - `updatePendingReviewMerchantBulk`
   - `getPendingReviewsByMerchant`
   - `getPendingReviewWithReceiptById`
5. Confirm whether repository approval is transactionally idempotent.

## Phase B — Add tests before changing behavior

Add:
```text
ReviewViewModelApprovalTest.kt
ReviewQuickApprovePolicyTest.kt
ReviewAiAssistStateTest.kt
ReviewOperationStateTest.kt
ReviewEditValidationTest.kt
```

Tests may initially fail; they define target behavior.

## Phase C — Fix critical data bugs

1. Add per-review mutation guard.
2. Fix quick approve dedupe policy.
3. Fetch original review before approve-with-edits.
4. Keep edit sheet/quick preview open on failure.
5. Add AI in-flight and exception handling.
6. Make receipt assist field apply specific.

## Phase D — Fix operation-state races

1. Replace `_isBatchProcessing`/`_batchProgress` with typed operation state.
2. Separate batch/statement/bulk jobs.
3. Disable reject-all/approve-all/import races.
4. Make cancel semantics explicit.

## Phase E — Privacy and money correctness

1. Redact/gate raw notification evidence.
2. Hide precise coordinates in normal UI.
3. Make amount currency explicit.
4. Add edit validation and transfer metadata handling.

## Phase F — UI extraction

Extract:
- `ReviewRoute`
- `ReviewScreenContent`
- `ReviewCard`
- `ReviewAssistSection`
- `EditReviewSheet`
- `ReviewDialogs`
- `ReviewDebugMenu`
- `ReviewBatchOverlay`

Keep behavior stable except fixed bugs.

## Phase G — Coordinator extraction

Extract:
- `ReviewApprovalCoordinator`
- `ReviewAiAssistCoordinator`
- `ReviewQuickApproveCoordinator`
- `ReviewOperationCoordinator`
- `ReviewLocationEditCoordinator`

Update tests to target coordinators.

---

# 7. Cross-slice golden scenarios after local tests pass

Add only after Slice 6 local tests are green:

1. Notification capture creates pending review.
2. Approving review creates exactly one transaction.
3. Rejecting review creates no transaction.
4. Approve with category edit updates transaction category.
5. Apply category suggestion opens edit sheet with suggested category.
6. Receipt assist apply merchant only changes merchant prefill.
7. Dedupe likely duplicate blocks quick approve.
8. Quick approve creates exactly one transaction and marks AI artifacts applied.
9. Approved review updates Home pending-review widget count.
10. Approved review appears in Transactions list.
11. Raw notification evidence is redacted when privacy setting requires it.
12. Receipt batch import creates review items without blocking existing queue.

---

# 8. Acceptance checklist for Slice 6 green

Slice 6 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Review approval tests pass.
- [ ] AI assist state tests pass.
- [ ] Quick approve policy tests pass.
- [ ] Operation-state tests pass.
- [ ] Edit validation tests pass.
- [ ] Per-review approve/reject/quick approve is idempotency-safe.
- [ ] Swipe failure does not permanently block row actions.
- [ ] Edit sheet closes only after success.
- [ ] Bulk apply uses pre-approval snapshot.
- [ ] Bulk approval aggregates failures.
- [ ] Quick approve requires explicit safe dedupe verdict.
- [ ] AI requests cannot duplicate or remain stuck Loading after exceptions.
- [ ] Receipt field apply buttons are truthful.
- [ ] Raw evidence is redacted/gated.
- [ ] Precise location display is privacy-safe.
- [ ] Currency display/editing is explicit.
- [ ] Location geocoding is not exposed directly to UI.
- [ ] Review UI is split into testable components.
- [ ] Docs are updated only after source/tests are green.

---

# 9. Agent guardrails

Do:
- Protect “approve creates exactly one transaction” first.
- Use fake repositories and deterministic coroutine tests.
- Add tests before refactors.
- Treat quick approve as safety-critical.
- Keep AI assist non-destructive until user confirms.
- Make raw evidence and location privacy-safe.
- Use typed operation and mutation states.

Do not:
- Rewrite the entire review queue in one PR.
- Let Compose compute business mutation policy.
- Keep dialogs closing before mutation results.
- Let quick approve run without duplicate check.
- Let AI exceptions leave Loading forever.
- Pass domain services directly into composables.
- Use generic snackbars as the only failure UI for financial edits.

Main invariant:

> A pending review must approve into at most one transaction, reject into zero transactions, preserve user edits on failure, never quick-approve without duplicate safety, and never expose raw sensitive evidence unless privacy policy allows it.