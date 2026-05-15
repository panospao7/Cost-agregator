# Slice 7 Debug Report — Receipt Scan + OCR + Item Categorization UI

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/receiptscan/ReceiptScanScreen.kt`
- `ui/screens/receiptscan/ReceiptScanViewModel.kt`
- `ui/components/ai/ReceiptItemBreakdownCard.kt`
- `ui/components/ai/ReceiptAssistCard.kt`
- `domain/receipt/lifecycle/*`
- `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- receipt → expense linking
- OCR result review
- AI receipt extraction
- AI item categorization
- receipt quick-save

Sources inspected:
- Receipt scan folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan
- `ReceiptScanViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt
- `ReceiptScanScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt
- `ReceiptItemBreakdownCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/ReceiptItemBreakdownCard.kt
- `ReceiptAssistCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/ReceiptAssistCard.kt
- Receipt lifecycle folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle
- `ReceiptLifecycleCoordinator.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptLinkService.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `CategorizeReceiptItemsUseCase.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 7 is one of the most sensitive financial-data paths because it converts a user-selected receipt image/PDF into:
1. a persisted scanned receipt,
2. OCR/parsed fields,
3. optional AI extraction/category suggestions,
4. optional item-level categorization,
5. a final ledger transaction,
6. a receipt-expense link.

The current implementation has strong building blocks:
- `ReceiptLifecycleCoordinator` centralizes validation → OCR/parse → dedupe → save → events → side effects.
- `TransactionLifecycleCoordinator` is used for final expense creation.
- `ReceiptLinkService` is used after expense creation.
- `ReceiptScanViewModel` already uses `TimeProvider`.
- item analysis has stale-receipt guards via `matchesReceiptForAnalysis(receiptId)`.
- tax-inclusive receipt behavior has a dedicated code path.
- receipt item AI categorization persists user corrections.

But the UI/ViewModel layer has important correctness, privacy, and testability risks:

1. `ReceiptScanViewModel` is a god ViewModel with OCR orchestration, AI assist, quick-save, item categorization, transaction creation, receipt linking, debug data, currency fallback, merchant normalization, and classifier learning.
2. `ReceiptScanScreen` is monolithic and passes the full ViewModel into `ReviewStep`, making component tests difficult.
3. scan processing can race: there is no processing job/request ID guard for rapid gallery/camera selections.
4. save/quick-save is not idempotency-safe.
5. expense creation and receipt linking are two separate operations; a link failure after transaction creation can leave an unlinked receipt transaction.
6. quick-save marks AI artifacts applied before final save succeeds.
7. quick-save preview is dismissed before save result; failure loses the preview context.
8. hardcoded/fallback `"EUR"` can leak into OCR-failure state, quick-save preview, display, and currency fallback.
9. amount formatting/parsing uses locale-fragile `String.format("%.2f", ...)`.
10. user-edited amount can be overridden by tax-inclusive parsed total.
11. debug/raw OCR surfaces can leak sensitive receipt text in production.
12. duplicate receipt/duplicate expense states are not explicit enough in UI.
13. receipt item categorization corrections are persisted, but final expense save does not clearly use or explain them.
14. AI assist calls lack in-flight guards and exception wrappers.
15. `showItemRationale()` only logs; the UI implies an action exists but nothing visible happens.
16. many failure states rely on raw string messages rather than typed UI state.
17. component tests appear insufficient for receipt scan, quick-save, save/link, and item categorization flows.

Recommended strategy:
- Do not rewrite receipt scanning.
- First add tests around scan processing, save/link atomicity, currency, quick-save, and item analysis.
- Then introduce small coordinators:
  - `ReceiptScanProcessor`
  - `ReceiptSaveCoordinator`
  - `ReceiptScanAiAssistCoordinator`
  - `ReceiptItemCategorizationController`
- Keep existing UI behavior except for safety fixes.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*ReceiptScanViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptLifecycle*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptLink*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CategorizeReceiptItems*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptItem*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptAssist*" --stacktrace
```

Inventory current tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*ReceiptScan*" -o \
  -iname "*ReceiptLifecycle*" -o \
  -iname "*ReceiptLink*" -o \
  -iname "*ReceiptItem*" -o \
  -iname "*ReceiptAssist*" -o \
  -iname "*Ocr*"
```

If Compose tests are configured:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Stop on first compile failure.

---

## 3. Current architecture map

### Scan input pipeline

```text
ReceiptScanScreen
  camera launcher / gallery launcher
        ↓
ReceiptScanViewModel.processPhoto/processGalleryImage
        ↓
ReceiptLifecycleCoordinator.processReceiptInput(uri)
        ↓
ReceiptRepository.processReceipt(...)
        ↓
ReceiptDuplicateDetector
        ↓
ScannedReceiptDao + ReceiptEventDao
        ↓
ReceiptSideEffectDispatcher
        ↓
ReceiptScanState(step = REVIEW)
```

### Manual save pipeline

```text
ReviewStep form fields
        ↓
ReceiptScanViewModel.saveExpense()
        ↓
buildManualSaveRequest(...)
        ↓
saveExpenseInternal(...)
        ↓
currency resolution
tax-inclusive override
merchant normalization
category auto-classification
        ↓
TransactionLifecycleCoordinator.createExpense(...)
        ↓
ReceiptLinkService.linkReceiptToExpense(...)
        ↓
ScanStep.DONE
```

### AI receipt assist pipeline

```text
ReviewStep
        ↓
requestReceiptAssist()
        ↓
SuggestReceiptExtractionUseCase
        ↓
AiArtifactRepository latest diagnostics
        ↓
ReceiptAssistCard / apply field buttons
```

### AI category assist pipeline

```text
ReviewStep
        ↓
requestCategoryAssist()
        ↓
SuggestCategoryFallbackUseCase
        ↓
CategoryAssistCard
        ↓
selectedCategoryId update
```

### Item categorization pipeline

```text
Parsed receipt line items
        ↓
analyzeReceiptItems()
        ↓
CategorizeReceiptItemsUseCase(receiptId)
        ↓
ReceiptItemCategorizationRepository snapshots
        ↓
ReceiptItemBreakdownCard
        ↓
updateUserCorrection(...)
```

---

# 4. Issues

## S7-001 — `ReceiptScanViewModel` is too broad

Severity: High  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
The ViewModel owns:
- camera temp URI state
- receipt processing
- OCR failure handling
- parsed receipt mapping
- AI receipt extraction assist
- AI category assist
- quick-save preview and confirmation
- final expense creation
- receipt-expense linking
- item categorization
- item correction persistence
- debug data
- merchant normalization
- classifier learning
- currency fallback
- tax-inclusive correction

Problem:
A change in AI, OCR, currency, transaction lifecycle, link service, or item categorization can break one ViewModel. This makes tests and agent fixes fragile.

Fix strategy:
Extract coordinators without changing UI behavior first.

Implementation plan:

```text
ReceiptScanProcessor
  - processImage(uri)
  - maps lifecycle result to ReceiptScanDraft

ReceiptSaveCoordinator
  - validate draft
  - resolve currency
  - create transaction
  - link receipt
  - learn classifier

ReceiptScanAiAssistCoordinator
  - receipt extraction assist
  - category assist
  - artifact apply/dismiss

ReceiptItemCategorizationController
  - analyze items
  - update item correction
  - show rationale state
```

Acceptance:
- `ReceiptScanViewModel` becomes orchestration/state only.
- save/link tests target `ReceiptSaveCoordinator`.
- AI assist tests target `ReceiptScanAiAssistCoordinator`.
- item-analysis tests target `ReceiptItemCategorizationController`.

---

## S7-002 — `ReceiptScanScreen` is monolithic and passes ViewModel into child content

Severity: High  
Files:
- `ReceiptScanScreen.kt`

Evidence:
The screen owns:
- launchers
- permission flow
- top bar
- debug viewer
- capture step
- processing step
- review form
- AI assist sections
- quick-save dialog
- line-item preview
- item breakdown
- save result/error/done UI

`ReviewStep` receives the entire `ReceiptScanViewModel`, not a state + action interface.

Problem:
Component tests cannot easily render the review form or item breakdown without Hilt/ViewModel. UI behavior is tightly coupled to the ViewModel.

Fix strategy:
Split route/content and use action interfaces.

Implementation plan:

```text
ReceiptScanRoute.kt
ReceiptScanScreenContent.kt
ReceiptCaptureStep.kt
ReceiptProcessingStep.kt
ReceiptReviewStep.kt
ReceiptReviewForm.kt
ReceiptScanAiAssistSection.kt
ReceiptQuickSaveDialog.kt
ReceiptLineItemsSection.kt
ReceiptItemAnalysisSection.kt
ReceiptScanTopBar.kt
ReceiptScanDebugActions.kt
ReceiptSaveResultContent.kt
```

Add:

```kotlin
data class ReceiptScanActions(
    val onCameraClick: () -> Unit,
    val onGalleryClick: () -> Unit,
    val onMerchantChanged: (String) -> Unit,
    val onAmountChanged: (String) -> Unit,
    val onCurrencyChanged: (String) -> Unit,
    val onDateChanged: (Long) -> Unit,
    val onCategorySelected: (Long) -> Unit,
    val onSave: () -> Unit,
    val onReceiptAssist: () -> Unit,
    val onCategoryAssist: () -> Unit,
    val onAnalyzeItems: () -> Unit,
    ...
)
```

Acceptance:
- `ReceiptReviewStep` is pure.
- `ReceiptQuickSaveDialog` is testable with fake callbacks.
- `ReceiptItemAnalysisSection` is testable without ViewModel.
- launchers stay only in route layer.

---

## S7-003 — Scan processing can race and overwrite newer scans

Severity: Critical  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
`processImageUri(uri)` launches a coroutine but does not store a processing job or request ID. If the user selects gallery image A, then image B quickly, A can finish after B and overwrite state.

Item categorization has a stale receipt check, but scan processing does not.

Problem:
The review screen can show OCR result from the wrong image. This is a serious correctness bug.

Fix strategy:
Add a scan request ID and cancel prior processing.

Implementation plan:

```kotlin
private var scanJob: Job? = null
private var scanRequestSeq = 0L

private fun processImageUri(uri: Uri) {
    val requestId = ++scanRequestSeq
    scanJob?.cancel()
    itemAnalysisJob?.cancel()

    _state.update { it.toProcessing(uri, requestId) }

    scanJob = viewModelScope.launch {
        try {
            val result = receiptScanProcessor.process(uri)
            if (requestId != scanRequestSeq) return@launch
            _state.update { result.toReviewState() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestId != scanRequestSeq) return@launch
            _state.update { it.toErrorState(e) }
        }
    }
}
```

Add `activeScanRequestId` to state if useful.

Acceptance:
- rapid scan A then scan B can never show A after B.
- cancelled scan does not emit error.
- test with fake coordinator delaying A and returning B first.

---

## S7-004 — Save and quick-save are not idempotency-safe

Severity: Critical financial correctness  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
`saveExpense()` builds a request and calls `saveExpenseInternal(...)`. `saveExpenseInternal` sets `isSaving = true`, but there is no early guard if save is already in progress. `confirmReceiptQuickSave()` similarly calls save after updating state.

Problem:
Double-tap on Save, repeated quick-save confirm, or direct test invocation can create duplicate transaction attempts.

Fix strategy:
Guard before starting save and use a save request ID/job.

Implementation plan:

```kotlin
private var saveJob: Job? = null

fun saveExpense() {
    if (_state.value.isSaving) return
    val request = buildManualSaveRequest(_state.value) ?: return
    saveExpenseInternal(request)
}

private fun saveExpenseInternal(request: ReceiptSaveRequest) {
    if (_state.value.isSaving) return
    _state.update { it.copy(isSaving = true, errorMessage = null) }
    saveJob = viewModelScope.launch { ... }
}
```

Also disable confirm/save buttons in UI while `isSaving`.

Acceptance:
- double save invokes `TransactionLifecycleCoordinator` once.
- quick-save confirm while saving is ignored.
- UI save buttons disabled while saving.

---

## S7-005 — Expense creation and receipt linking are not atomic

Severity: Critical data integrity  
Files:
- `ReceiptScanViewModel.kt`
- `TransactionLifecycleCoordinator`
- `ReceiptLinkService`

Evidence:
The ViewModel:
1. creates expense through `TransactionLifecycleCoordinator.createExpense(createRequest)`;
2. then calls `receiptLinkService.linkReceiptToExpense(...)`.

If link fails after expense creation, the ledger transaction exists but the receipt is not linked.

Problem:
Receipt scan save should be atomic from the user’s perspective: create transaction + link receipt + lifecycle event should either succeed together or expose a recoverable partial state.

Fix strategy:
Move create+link into a domain-level transaction.

Implementation options:

### Option A — Recommended
Add a domain method:

```kotlin
class ReceiptSaveCoordinator {
    suspend fun saveScannedReceiptExpense(command: SaveScannedReceiptExpenseCommand): ReceiptSaveOutcome
}
```

Inside:
- database transaction if both operations are DB writes;
- or transaction lifecycle API that supports receipt link as part of command.

### Option B — Short-term
If transaction lifecycle cannot include link:
- catch link failure;
- emit `PartialSuccessExpenseCreatedButReceiptUnlinked(expenseId, receiptId)`;
- offer retry link action.

Acceptance:
- test: link failure does not show normal success.
- user can retry linking or open diagnostics.
- preferred: create+link atomic in one transaction.

---

## S7-006 — Deprecated transaction lifecycle API is still used

Severity: Medium/High  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
The code suppresses a deprecation error around `transactionLifecycleCoordinator.createExpense(createRequest)` with a TODO to migrate.

Problem:
A deprecated lifecycle path in a critical receipt-to-ledger flow is risky. Slice 5/6 emphasize not bypassing legal lifecycle paths.

Fix strategy:
Migrate to the current transaction creation API.

Implementation plan:
- Identify replacement, likely `createExpenseStandalone()` or equivalent.
- Add receipt source metadata and receipt ID to the new API.
- Ensure duplicate handling behavior remains identical.
- Add regression tests:
  - created
  - duplicate skipped
  - validation failed
  - insert conflict
  - exception

Acceptance:
- no `@Suppress("DEPRECATION_ERROR")` in `ReceiptScanViewModel`.
- receipt scan uses the same legal transaction lifecycle path as manual add/review approval.
- duplicate behavior remains tested.

---

## S7-007 — Quick-save marks AI artifacts applied before final save succeeds

Severity: High auditability/correctness  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
`confirmReceiptQuickSave()` calls `preview.usedCapabilities.forEach(::markLatestArtifactApplied)` before `saveExpenseInternal(request)` finishes.

Problem:
If save fails, AI artifacts are marked applied even though no transaction was created.

Fix strategy:
Mark artifacts applied only after successful save.

Implementation plan:
- Include `usedCapabilities` in `ReceiptSaveRequest`, or keep pending apply list in save context.
- After `CreateExpenseResult.Created` and successful receipt link, mark artifacts applied.
- If save fails, leave artifacts un-applied.

```kotlin
data class ReceiptSaveRequest(
    ...,
    val appliedAiCapabilities: Set<AiCapability> = emptySet()
)
```

Acceptance:
- quick-save failure does not mark artifacts applied.
- success marks only used capabilities.
- artifact-mark failure is logged as non-fatal diagnostics, not as save success.

---

## S7-008 — Quick-save preview is dismissed before save result

Severity: Medium/High UX  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`

Evidence:
`confirmReceiptQuickSave()` clears `quickSavePreview` before save completes.

Problem:
If save fails, user loses the preview context and may not know which AI-filled fields were used.

Fix strategy:
Keep preview visible during submit or close only on success.

Implementation plan:

```kotlin
data class ReceiptQuickSavePreview(
    ...,
    val isSubmitting: Boolean = false,
    val error: UiText? = null
)
```

On confirm:
- set `isSubmitting = true`;
- disable dialog buttons;
- on success clear preview;
- on failure keep preview with error and retry/cancel.

Acceptance:
- quick-save save failure keeps dialog open.
- retry works.
- cancel explicitly dismisses.

---

## S7-009 — Hardcoded/fallback `"EUR"` leaks into receipt state and UI

Severity: High multi-currency correctness  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`

Evidence:
- `ReceiptScanState.editCurrency` defaults to `"EUR"`.
- OCR failure path creates parsed receipt with currency `"EUR"` and sets edit currency `"EUR"`.
- `ReceiptLifecycleCoordinator` has fallback `"EUR"` if home currency cannot be resolved.
- screen helper defaults currency symbol to EUR.
- quick-save preview falls back to `parsed?.currency ?: "EUR"`.

Problem:
A non-EUR user can see or save EUR when OCR fails or currency settings fail to load.

Fix strategy:
Make currency loading explicit and do not persist placeholder currency.

Implementation plan:
1. Change state:
```kotlin
val homeCurrency: String? = null
val isHomeCurrencyLoaded: Boolean = false
val editCurrency: String? = null
```

2. Init:
```kotlin
viewModelScope.launch {
    currencySettingsRepository.homeCurrency().collect { currency ->
        _state.update {
            it.copy(
                homeCurrency = currency,
                isHomeCurrencyLoaded = true,
                editCurrency = it.editCurrency ?: currency
            )
        }
    }
}
```

3. OCR failure:
```kotlin
val fallback = _state.value.homeCurrency
if (fallback == null) show currency loading/degraded state
```

4. Save:
- if no resolved currency, block save with explicit error.
- do not default to EUR except in test fixtures/previews.

Acceptance:
- non-EUR home currency appears in OCR failure manual entry.
- save cannot persist fallback EUR when currency repository fails.
- quick-save preview uses the same currency that save will use.
- test: home currency delayed → save disabled/error, no EUR persistence.

---

## S7-010 — Amount symbol uses parsed currency, not editable selected currency

Severity: High UX/currency correctness  
Files:
- `ReceiptScanScreen.kt`

Evidence:
Amount field leading icon uses `getCurrencySymbol(parsed?.currency)`, while the actual selected currency is `state.editCurrency`.

Problem:
If user changes currency in `CurrencyPicker`, the amount field symbol can remain the parsed/OCR currency.

Fix strategy:
Use `state.editCurrency`.

Implementation:
```kotlin
leadingIcon = {
    Text(
        getCurrencySymbol(state.editCurrency),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
}
```

Acceptance:
- changing currency immediately updates amount symbol.
- test renders `USD`, changes to `GBP`, sees symbol update.

---

## S7-011 — Locale-fragile amount formatting/parsing

Severity: High financial correctness  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`
- shared `AmountUtils`

Evidence:
The ViewModel uses `String.format("%.2f", total)` without explicit locale. Input sanitizer allows digits, dots, and commas.

Problem:
On comma-decimal locales, formatted editable amounts may not parse consistently. The same bug was identified in Slice 5.

Fix strategy:
Use a shared money input formatter/sanitizer.

Implementation:
- Create/consume shared `AmountInputSanitizer` from Slice 2/5.
- Create `EditableMoneyFormatter.formatForInput(amount): String`.
- Use for:
  - parsed total prefill
  - AI total apply
  - quick-save amount text
  - field summaries if they need editable format

Acceptance:
- suggestion prefill parses in US and comma-decimal locales.
- multiple separators are rejected/sanitized.
- max fraction digits enforced.

---

## S7-012 — Tax-inclusive override can discard user-edited amount

Severity: Critical financial correctness  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
In save, if parsed receipt is tax-inclusive and parsed total exists, `effectiveAmount` is set to parsed total, overriding `request.amount`.

Problem:
If OCR total is wrong and user edits amount, save still uses parsed total when `taxInclusive == true`.

Fix strategy:
Track dirty/source state for amount.

Implementation:

```kotlin
data class ReceiptScanState(
    ...
    val amountSource: DraftFieldSource = DraftFieldSource.Ocr,
    val isAmountEditedByUser: Boolean = false
)

fun updateAmount(value: String) {
    _state.update {
        it.copy(
            editAmount = sanitizer.sanitize(value),
            isAmountEditedByUser = true,
            amountSource = DraftFieldSource.User
        )
    }
}
```

Save policy:
```kotlin
val effectiveAmount =
    if (taxInclusive && parsedTotal != null && !state.isAmountEditedByUser) {
        parsedTotal
    } else {
        request.amount
    }
```

Acceptance:
- user-edited amount wins.
- tax-inclusive parsed total still prevents double-tax when user did not edit.
- tests:
  - taxInclusive true + no edit → parsed total used.
  - taxInclusive true + user edit → edited amount used.
  - taxInclusive false → edited/request amount used.

---

## S7-013 — Merchant normalization may lose user-visible merchant value

Severity: Medium/High  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
Save normalizes merchant and passes `normalizedMerchant` as the transaction merchant.

Problem:
If normalization lowercases, strips brand suffixes, or maps to canonical normalized name, the user-edited merchant may not be what appears in the ledger.

Fix strategy:
Separate display merchant from normalized merchant key.

Implementation options:
- If `CreateExpenseRequest` supports normalized merchant, pass both.
- If not, preserve user display merchant and use normalized only for classifier/category lookup.

```kotlin
val displayMerchant = request.merchant.trim()
val merchantKey = merchantNormalizer.normalize(displayMerchant, autoCreate = true)
...
CreateExpenseRequest(
    merchant = displayMerchant,
    merchantNormalized = merchantKey.canonical.normalizedName,
    ...
)
```

Acceptance:
- user-entered merchant display is preserved.
- category/classifier uses normalized key.
- test with merchant normalization variant.

---

## S7-014 — Receipt duplicate and transaction duplicate are not explicit in UI

Severity: High  
Files:
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`

Evidence:
`ReceiptLifecycleCoordinator` can detect duplicate receipts and return an existing receipt. `TransactionLifecycleCoordinator` can return `DuplicateSkipped`.

Problem:
The UI only surfaces a generic duplicate save result. It does not distinguish:
- duplicate receipt image already scanned,
- duplicate transaction already exists,
- duplicate receipt already linked to an existing expense,
- duplicate receipt not linked.

This can lead users to save a duplicate transaction from an existing receipt or misunderstand why save was skipped.

Fix strategy:
Introduce typed duplicate UI state.

Implementation:

```kotlin
sealed interface ReceiptDuplicateUiState {
    data object None : ReceiptDuplicateUiState
    data class ReceiptDuplicate(
        val existingReceiptId: Long,
        val linkedExpenseId: Long?
    ) : ReceiptDuplicateUiState
    data class TransactionDuplicate(
        val dedupeKey: String?,
        val existingExpenseId: Long?
    ) : ReceiptDuplicateUiState
}
```

Acceptance:
- duplicate receipt scan shows clear UI before save.
- duplicate transaction save shows clear result and no “success”.
- if existing expense is known, offer “Open transaction” or “Link receipt”.
- tests cover exact hash duplicate and semantic duplicate.

---

## S7-015 — Receipt processing side effects may run before user confirmation

Severity: High architecture/correctness  
Files:
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptScanViewModel.kt`

Evidence:
`ReceiptScanViewModel` calls `receiptLifecycleCoordinator.processReceiptInput(uri)` with default options. The coordinator docs/options mention post-save side effects such as matching/categorization after save.

Problem:
For interactive receipt scan, the user may only be reviewing OCR fields, but lifecycle side effects can already run against the saved receipt. If matching side effects auto-link to an existing expense, then manual save can create a new expense and link the same receipt again unless guards prevent it.

Fix strategy:
Make interactive scan processing options explicit.

Implementation:
```kotlin
receiptLifecycleCoordinator.processReceiptInput(
    uri,
    ReceiptProcessingOptions(
        createReview = false,
        autoMatchExistingExpense = false
    )
)
```

Then run matching only after user saves or explicitly asks to match.

If `autoMatchExistingExpense` is not currently honored by `sideEffectDispatcher`, wire it through.

Acceptance:
- interactive scan review does not auto-link before confirmation.
- save path owns final create/link behavior.
- test verifies no matching side effect before save.

---

## S7-016 — AI assist requests lack in-flight guards and exception handling

Severity: High  
Files:
- `ReceiptScanViewModel.kt`

Affected:
- `requestReceiptAssist`
- `requestCategoryAssist`

Evidence:
Both launch use cases and set Loading but do not guard repeated requests. They also do not wrap thrown exceptions around the use case call.

Problem:
Repeated taps can trigger multiple AI calls. A thrown exception can leave the card stuck in Loading.

Fix strategy:
Centralize AI assist request execution.

Implementation:

```kotlin
private val inFlightAssist = mutableSetOf<AiCapability>()

private fun launchAssist(
    capability: AiCapability,
    setLoading: () -> Unit,
    setError: (Throwable) -> Unit,
    block: suspend () -> Unit
) {
    if (!inFlightAssist.add(capability)) return
    viewModelScope.launch {
        setLoading()
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(e)
        } finally {
            inFlightAssist.remove(capability)
        }
    }
}
```

Acceptance:
- repeated receipt assist tap calls use case once.
- thrown use case maps to `AiLoadState.Error`.
- state never remains Loading after exception.
- force retry works after failure.

---

## S7-017 — AI diagnostics are displayed directly to users

Severity: Medium privacy/security  
Files:
- `ReceiptScanScreen.kt`
- `ReceiptAssistCard.kt`
- `CategoryAssistCard.kt`

Evidence:
Diagnostics strings from artifacts are rendered in receipt/category assist failure/ready UI.

Problem:
Diagnostics can include provider/model/routing details or internal processing messages. This is useful for debug, but unsafe/noisy for normal users.

Fix strategy:
Separate user-safe message from debug diagnostics.

Implementation:

```kotlin
data class AssistDiagnosticsUi(
    val userMessage: UiText?,
    val debugDetails: String?,
    val providerKind: ProviderKind?,
    val isCloud: Boolean
)
```

UI:
- show `userMessage` in release;
- show `debugDetails` only behind debug/expanded diagnostics.

Acceptance:
- production UI does not expose raw provider diagnostics.
- debug UI still has details.
- tests cover debug vs release policy.

---

## S7-018 — Raw OCR/debug viewer can leak sensitive receipt text

Severity: High privacy/security  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`
- `DebugViewerScreen`

Evidence:
`debugData` is available in review/error states and the top bar shows a debug button whenever debug data exists. It is not visibly gated by `BuildConfig.DEBUG` in the receipt scan screen.

Problem:
Receipt OCR text can include names, addresses, card fragments, order IDs, loyalty numbers, or email snippets. Debug viewer should not be visible in production unless a deliberate diagnostics policy allows it.

Fix strategy:
Gate and redact debug data.

Implementation:
- Show debug button only when:
  - `BuildConfig.DEBUG`, or
  - explicit diagnostics mode is enabled.
- Apply privacy raw OCR storage/display policy from Slice 3.
- Store `DebugData.rawText` redacted by default.

```kotlin
if (BuildConfig.DEBUG && state.debugData != null) {
    DebugAction(...)
}
```

Acceptance:
- release-equivalent tests do not show debug button.
- raw OCR is redacted or unavailable when privacy settings require it.
- debug mode can show sanitized details.

---

## S7-019 — Gallery/PDF URI access is fragile

Severity: Medium  
Files:
- `ReceiptScanScreen.kt`
- `ReceiptRepository`
- `ReceiptLifecycleCoordinator`

Evidence:
Gallery uses `ActivityResultContracts.OpenDocument()` and immediately calls `processGalleryImage(uri)`. There is no visible `takePersistableUriPermission`.

Problem:
If processing is interrupted/backgrounded before the repository copies the asset, URI permission can be lost. OpenDocument supports persistable permission and the app should either persist it or immediately copy to app-private storage.

Fix strategy:
Persist permission or copy asset synchronously before background processing.

Implementation:
```kotlin
context.contentResolver.takePersistableUriPermission(
    uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION
)
```

If only images/PDFs are copied immediately by repository, document and test that asset copy occurs before URI can expire.

Acceptance:
- selected PDF/image can survive configuration change/background during processing.
- processing after permission revoke fails with clear validation error, not crash.

---

## S7-020 — Camera permission denied UI is not using shared privacy/security primitive

Severity: Low/Medium  
Files:
- `ReceiptScanScreen.kt`
- `PrivacyBlockedCard.kt`

Problem:
Camera permission denial is shown as a local card. Slice 3 introduced/standardized `PrivacyBlockedCard` for blocked/denied capability states.

Fix strategy:
Use a shared permission-blocked component or a typed variant of `PrivacyBlockedCard`.

Acceptance:
- camera permission denied state has consistent styling/accessibility.
- open-settings action is available.
- component test covers denied state.

---

## S7-021 — Manual save validation is incomplete

Severity: High financial correctness  
Files:
- `ReceiptScanViewModel.kt`

Current validation:
- merchant non-blank
- amount parse succeeds and > 0
- receiptId exists

Missing/weak:
- date valid and not zero
- future date policy
- category ID exists if selected
- currency loaded and valid
- payment method supported
- receipt is in REVIEW state
- save not already in progress
- receipt not already linked/saved

Fix strategy:
Create typed validation.

Implementation:

```kotlin
data class ReceiptDraftInput(...)
sealed interface ReceiptDraftValidation {
    data class Valid(val command: SaveScannedReceiptExpenseCommand) : ReceiptDraftValidation
    data class Invalid(val errors: List<ReceiptDraftFieldError>) : ReceiptDraftValidation
}
```

Acceptance:
- invalid date blocks save.
- future date follows app policy.
- invalid category blocks save.
- save from non-REVIEW state is ignored/error.
- save of already-linked receipt is blocked or handled explicitly.

---

## S7-022 — Save result does not include enough recovery information

Severity: Medium/High  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`

Evidence:
`SaveReceiptResult` has:
- `Success`
- `Duplicate`
- `Error(message)`

Problem:
This is too coarse for recovery. The UI cannot offer:
- retry link
- open existing transaction
- fix validation fields
- open duplicate
- retry artifact marking
- report partial success

Fix strategy:
Expand save result.

Implementation:

```kotlin
sealed interface SaveReceiptResult {
    data class Success(val expenseId: Long, val receiptId: Long) : SaveReceiptResult
    data class DuplicateExpense(val existingExpenseId: Long?, val reason: UiText) : SaveReceiptResult
    data class DuplicateReceipt(val existingReceiptId: Long, val linkedExpenseId: Long?) : SaveReceiptResult
    data class ValidationError(val errors: List<ReceiptDraftFieldError>) : SaveReceiptResult
    data class PartialLinkFailure(val expenseId: Long, val receiptId: Long, val message: UiText) : SaveReceiptResult
    data class Error(val message: UiText) : SaveReceiptResult
}
```

Acceptance:
- UI can retry only the failed portion where possible.
- success includes transaction ID for “View transaction”.
- duplicate states are actionable.

---

## S7-023 — Item categorization corrections are not clearly connected to final expense save

Severity: Medium/High product correctness  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptItemBreakdownCard.kt`

Evidence:
`updateItemCategory(...)` persists item corrections in `ReceiptItemCategorizationRepository`. Final save still creates a single expense with one category ID from draft/category assist/classifier.

Problem:
User may think item-level category corrections affect final expense categorization/splitting, but save creates one expense category. If item categorization is only receipt metadata, the UI should say that. If intended to split expense by items/categories, save must use those corrections.

Fix strategy:
Define policy explicitly.

Options:
1. Metadata-only: label section “Receipt item categories for insights only”; do not imply split expense.
2. Expense split: create multiple categorized child entries or itemized metadata linked to expense.
3. Category suggestion: derive final expense category from dominant corrected item category.

Recommended short-term:
- Metadata-only with clear copy.
- Add save result saying item corrections were saved with receipt.

Acceptance:
- UI copy explains what item categories affect.
- tests verify corrections persist.
- no user-facing implication that item categories split the transaction unless implemented.

---

## S7-024 — `showItemRationale()` is a no-op UI action

Severity: Medium  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptItemBreakdownCard.kt`

Evidence:
`showItemRationale(item)` only logs.

Problem:
If the card exposes a rationale action, the user expects a dialog/bottom sheet.

Fix strategy:
Add rationale UI state.

Implementation:

```kotlin
val selectedItemRationale: ReceiptItemCategorizationSnapshot? = null

fun showItemRationale(item: ReceiptItemCategorizationSnapshot) {
    _state.update { it.copy(selectedItemRationale = item) }
}

fun dismissItemRationale() { ... }
```

UI shows dialog with:
- item description
- category
- confidence
- AI rationale
- alternatives

Acceptance:
- rationale action opens visible dialog.
- dismiss works.
- test covers callback.

---

## S7-025 — Item category correction has no error/loading state

Severity: Medium  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
`updateItemCategory(...)` launches repository update and reloads snapshots with no try/catch or per-item loading state.

Problem:
A DB failure or stale receipt can silently fail or crash the coroutine. User receives no feedback.

Fix strategy:
Add per-item mutation state.

Implementation:

```kotlin
data class ItemCorrectionUiState(
    val updatingItemIds: Set<Long> = emptySet(),
    val error: UiText? = null
)
```

Wrap update in `runCatching`.

Acceptance:
- category correction failure shows error.
- item row disabled/spinner while saving.
- stale receipt does not update wrong scan.

---

## S7-026 — Item categorization auto-trigger errors can be lost

Severity: Medium  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
After OCR success, item analysis is launched if settings allow it. If `aiSettingsRepository.settings().first()` throws, the job has no local catch at that level.

Problem:
Auto-analysis failure may be invisible, and coroutine exception can cancel the launched job.

Fix strategy:
Wrap auto-trigger setup.

Implementation:
```kotlin
itemAnalysisJob = viewModelScope.launch {
    runCatching {
        val settings = aiSettingsRepository.settings().first()
        if (settings.aiEnabled && settings.receiptItemCategorizationEnabled) {
            analyzeReceiptItemsInternal(receipt.id)
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        _state.update { it.copy(itemAnalysisError = "Item analysis unavailable") }
    }
}
```

Acceptance:
- settings failure does not crash.
- user sees item analysis disabled/unavailable state.

---

## S7-027 — AI item analysis disabled/blocked state is not explicit

Severity: Medium  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`

Problem:
If AI is disabled, privacy-blocked, or provider unavailable, the line-item section may just show an Analyze button or an error. Users need to know why AI item categorization is unavailable.

Fix strategy:
Expose typed item analysis availability.

Implementation:

```kotlin
sealed interface ItemAnalysisAvailability {
    data object Available : ItemAnalysisAvailability
    data class Disabled(val reason: UiText) : ItemAnalysisAvailability
    data class BlockedByPrivacy(val reason: UiText) : ItemAnalysisAvailability
}
```

Acceptance:
- disabled state is visible.
- no retry button for disabled/privacy-blocked state.
- tests cover AI disabled and privacy blocked.

---

## S7-028 — ReceiptAssistCard field buttons need tests and stable tags

Severity: Medium  
Files:
- `ReceiptAssistCard.kt`

Evidence:
The card exposes separate callbacks:
- apply merchant
- apply total
- apply date
- apply all
- dismiss

This was a bug in Slice 6 review wiring; receipt scan appears to wire field-specific ViewModel methods. It still needs a regression test.

Fix strategy:
Add test tags and component tests.

Tags:
- `receipt_assist_card`
- `receipt_assist_apply_merchant`
- `receipt_assist_apply_total`
- `receipt_assist_apply_date`
- `receipt_assist_apply_all`
- `receipt_assist_dismiss`

Acceptance:
- each field button invokes only its matching callback.
- missing suggestion fields hide/disable unavailable buttons.
- apply-all calls only all callback.

---

## S7-029 — ReceiptItemBreakdownCard mixes UI and JSON parsing/business logic

Severity: Medium  
Files:
- `ReceiptItemBreakdownCard.kt`

Evidence:
The component parses alternative categories JSON in UI helper logic.

Problem:
Parsing business/data shape in a composable makes it hard to test, can throw during composition, and duplicates parsing rules.

Fix strategy:
Move alternatives parsing to ViewModel/domain mapping.

Implementation:
- Convert `ReceiptItemCategorizationSnapshot` to `ReceiptItemCategorizationUiModel`.
- Include:
  - selected category
  - alternatives as typed list
  - confidence label
  - rationale
- `ReceiptItemBreakdownCard` renders only typed UI model.

Acceptance:
- no JSON parsing in composable.
- invalid JSON handled in ViewModel/domain test.
- component test uses typed alternatives.

---

## S7-030 — ReceiptItemBreakdownCard still uses hardcoded semantic colors

Severity: Low/Medium  
Files:
- `ReceiptItemBreakdownCard.kt`

Evidence:
`ConfidenceBadge` uses `SemanticColors.SuccessGreen`, `WarningOrange`, and `DangerRed`.

Problem:
This inherits Slice 2 theme inconsistency. Not critical to receipt correctness but should be aligned with Material theme/status color adapter.

Fix strategy:
Use theme status tokens or `MaterialTheme.colorScheme`.

Acceptance:
- light/dark contrast passes.
- component test smoke-renders both themes.

---

## S7-031 — Payment method support is narrower than the app model

Severity: Medium  
Files:
- `ReceiptScanScreen.kt`
- `ReceiptScanViewModel.kt`

Evidence:
Receipt scan UI shows card and cash chips. App enum appears broader in other slices.

Problem:
Receipt save may not let user choose bank transfer/digital wallet/etc. This may be intentional but should be explicit.

Fix strategy:
Use the shared payment method selector from Add Expense, or document receipt-scan limitation.

Acceptance:
- product decision documented.
- if full support required, receipt scan uses same payment selector as Add Expense.
- tests cover selected payment method passed to save command.

---

## S7-032 — Done state auto-dismiss may hide useful recovery/navigation

Severity: Low/Medium  
Files:
- `ReceiptScanScreen.kt`

Evidence:
After `ScanStep.DONE`, screen delays 1500 ms then dismisses.

Problem:
User cannot tap “View transaction”, “Scan another”, or inspect receipt link. Auto-dismiss also complicates UI tests.

Fix strategy:
Make done screen action-driven, or keep auto-dismiss configurable.

Implementation:
```kotlin
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    autoDismissOnDone: Boolean = true,
    ...
)
```

Better:
- Done screen has:
  - View transaction
  - Scan another
  - Close

Acceptance:
- tests can disable auto-dismiss.
- success includes expenseId if View transaction is added.

---

## S7-033 — Raw string error/message fields make localization/testing hard

Severity: Medium  
Files:
- `ReceiptScanViewModel.kt`

Evidence:
State contains many raw strings:
- `errorMessage`
- `receiptAssistMessage`
- `categoryAssistMessage`
- `itemAnalysisError`
- `SaveReceiptResult.Error(message)`

Problem:
Hardcoded messages are not localizable and make tests brittle.

Fix strategy:
Use `UiText` or error codes.

Implementation:
```kotlin
sealed interface ReceiptScanMessage {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : ReceiptScanMessage
    data class DynamicError(val safeMessage: String) : ReceiptScanMessage
}
```

Acceptance:
- user-visible static strings come from resources.
- tests assert message type/error code, not English text.

---

## S7-034 — Privacy mode for raw OCR display is not explicit in scan UI

Severity: High privacy  
Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`
- `PrivacySettingsRepository`
- `RawContentSanitizer`

Evidence:
Lifecycle coordinator sanitizes raw OCR based on raw OCR storage mode. Scan UI has `rawOcrText` and `showRawText` state.

Problem:
The UI should clearly distinguish:
- raw text not stored,
- redacted text stored,
- full raw text stored,
- raw text hidden by privacy policy.

Fix strategy:
Expose typed raw OCR state.

Implementation:
```kotlin
sealed interface RawOcrUiState {
    data object HiddenByPrivacy : RawOcrUiState
    data object NotAvailable : RawOcrUiState
    data class Redacted(val text: String) : RawOcrUiState
    data class Full(val text: String, val requiresExplicitReveal: Boolean) : RawOcrUiState
}
```

Acceptance:
- no raw OCR visible when privacy blocks it.
- redacted text is labeled redacted.
- full reveal requires explicit tap and is debug/privacy-gated.

---

## S7-035 — Receipt scan has insufficient focused tests

Severity: High  
Files:
- tests missing or insufficient

Recommended new tests are listed below. This is the first thing the agent should add before broad refactors.

---

# 5. Recommended new tests

## JVM/ViewModel tests

### `ReceiptScanProcessingRaceTest`
Required cases:
- scan A starts, scan B starts, B completes first → state shows B.
- A completes after B → ignored.
- cancelled scan does not set error.
- new scan clears old AI/item/quick-save state.

### `ReceiptScanSaveValidationTest`
Required cases:
- blank merchant rejected.
- invalid amount rejected.
- zero amount rejected.
- invalid/zero date rejected.
- future date policy enforced.
- currency not loaded blocks save.
- invalid selected category rejected.
- save from CAPTURE/PROCESSING/DONE ignored.
- double save calls transaction coordinator once.

### `ReceiptScanSaveCoordinatorTest`
Required cases:
- successful save creates expense and links receipt.
- link failure does not show normal success.
- create duplicate maps to duplicate state.
- validation failed maps to field errors.
- deprecated create method removed after migration.
- classifier learning failure is non-fatal.
- receipt linked with correct source/linkType.
- merchant display preserved while normalized key used for classifier.

### `ReceiptScanCurrencyTest`
Required cases:
- OCR failure uses home currency, not EUR.
- non-EUR parsed receipt displays/saves parsed currency.
- user changes currency → save uses user currency.
- currency repo failure blocks save or explicit degraded state, no EUR persistence.
- amount leading symbol follows selected currency.

### `ReceiptScanTaxInclusiveTest`
Required cases:
- tax-inclusive and no user edit → parsed total used.
- tax-inclusive and user-edited amount → user amount used.
- non-tax-inclusive → user/request amount used.
- low-confidence tax data does not override edited amount.

### `ReceiptQuickSaveTest`
Required cases:
- disabled setting blocks quick-save.
- low OCR confidence blocks quick-save.
- no auto-applied field blocks quick-save.
- missing merchant filled from receipt assist.
- missing amount filled from receipt assist.
- missing category filled from category assist.
- quick-save failure keeps preview open.
- artifact marked applied only after successful save.
- repeated confirm calls save once.

### `ReceiptScanAiAssistTest`
Required cases:
- receipt assist success -> Ready.
- receipt assist disabled -> Disabled with reason.
- receipt assist thrown exception -> Error, not Loading.
- repeated receipt assist tap calls use case once.
- category assist receipt missing -> Error.
- category assist success applies category.
- dismiss marks artifact dismissed.
- artifact mark failure is non-fatal and logged/debug-visible.

### `ReceiptItemCategorizationControllerTest`
Required cases:
- AI enabled + line items auto-analyzes.
- AI disabled shows disabled/unavailable state.
- analyze success loads snapshots and shows breakdown.
- AlreadyAnalyzed uses cached items.
- analyze failure sets itemAnalysisError.
- stale receipt result ignored.
- update item category persists correction and reloads.
- update item failure shows error.
- rationale action sets visible rationale state.

### `ReceiptDuplicateStateTest`
Required cases:
- exact duplicate receipt maps to duplicate receipt UI.
- semantic duplicate maps to duplicate receipt UI.
- transaction duplicate maps to duplicate expense UI.
- duplicate with existing link offers open/link policy.

---

## Compose/component tests

### `ReceiptScanScreenContentTest`
- capture state shows camera/gallery actions.
- camera denied state shows permission card.
- processing state shows loading text.
- error state shows retry.
- done state can disable auto-dismiss in test.

### `ReceiptReviewFormTest`
- merchant input callback.
- amount input callback uses sanitizer.
- currency picker updates symbol.
- date selector callback.
- payment method callback.
- category callback.
- save button disabled while saving/currency unavailable.

### `ReceiptQuickSaveDialogTest`
- renders field summaries.
- amount uses selected/resolved currency.
- confirm callback fires once.
- submitting disables buttons.
- error remains visible.

### `ReceiptAssistCardTest`
- apply merchant invokes merchant callback only.
- apply total invokes total callback only.
- apply date invokes date callback only.
- apply all invokes all callback.
- dismiss invokes dismiss.
- diagnostics hidden in release policy after fix.

### `ReceiptItemBreakdownCardTest`
- renders item description/category/confidence.
- category picker opens.
- changing category invokes callback.
- rationale button invokes callback.
- alternatives render from typed UI model.
- low confidence badge renders accessible label.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run existing receipt tests.
3. Inventory tests with `find`.
4. Inventory current lifecycle APIs:
   - `ReceiptLifecycleCoordinator.processReceiptInput`
   - `ReceiptLinkService.linkReceiptToExpense`
   - current replacement for deprecated transaction create
   - item categorization result types
   - privacy raw OCR policy types

## Phase B — Add tests before behavior changes

Add:
```text
ReceiptScanProcessingRaceTest.kt
ReceiptScanSaveValidationTest.kt
ReceiptScanSaveCoordinatorTest.kt
ReceiptScanCurrencyTest.kt
ReceiptScanTaxInclusiveTest.kt
ReceiptQuickSaveTest.kt
ReceiptScanAiAssistTest.kt
ReceiptItemCategorizationControllerTest.kt
ReceiptDuplicateStateTest.kt
```

Use fake coordinators/repositories and fixed `TimeProvider`.

## Phase C — Critical correctness fixes

1. Add scan processing request ID/job cancellation.
2. Add save idempotency guard.
3. Move create+link into a coordinator/atomic outcome.
4. Migrate deprecated transaction creation API.
5. Stop marking AI artifacts applied before successful save.
6. Keep quick-save preview on failure.
7. Fix currency placeholder/fallback behavior.
8. Fix amount symbol to use `state.editCurrency`.
9. Add amount dirty tracking before tax-inclusive override.
10. Add typed duplicate state.

## Phase D — AI/item categorization hardening

1. Add in-flight guards and exception handling to assist requests.
2. Add item analysis availability state.
3. Add item correction loading/error state.
4. Implement rationale dialog state.
5. Move item alternatives JSON parsing out of composable.
6. Clarify item categorization save policy.

## Phase E — Privacy hardening

1. Gate debug viewer by debug/diagnostics mode.
2. Add typed raw OCR UI state.
3. Redact/hide raw OCR according to privacy policy.
4. Hide provider diagnostics in release UI.
5. Use shared permission blocked UI for camera denied if desired.

## Phase F — UI extraction

Extract:
- `ReceiptScanRoute`
- `ReceiptScanScreenContent`
- `ReceiptCaptureStep`
- `ReceiptProcessingStep`
- `ReceiptReviewStep`
- `ReceiptReviewForm`
- `ReceiptScanAiAssistSection`
- `ReceiptQuickSaveDialog`
- `ReceiptLineItemsSection`
- `ReceiptItemBreakdownSection`
- `ReceiptSaveResultContent`

## Phase G — Localization/theme/accessibility

1. Replace raw ViewModel strings with `UiText`/resources.
2. Add test tags for major controls.
3. Align receipt screen colors with Slice 2 theme decisions.
4. Add accessible labels for confidence/AI/item states.

---

# 7. Cross-slice golden scenarios after local tests pass

Add these only after Slice 7 local tests are green:

1. Scan receipt → review fields → save → transaction appears in Transactions.
2. Saved receipt is linked to created transaction.
3. Link failure is recoverable and not shown as success.
4. Duplicate receipt does not create duplicate transaction.
5. Multi-currency receipt saves selected currency and Home/Transactions display consistently.
6. Tax-inclusive receipt does not double-add tax and does not override user correction.
7. Receipt AI assist applies only requested fields.
8. Item categorization corrections persist and are visible after reload.
9. Raw OCR hidden/redacted when privacy settings require it.
10. Quick-save low-confidence scan is blocked.
11. Quick-save success marks artifacts applied; failure does not.
12. Scan race A/B cannot show stale A result.

---

# 8. Acceptance checklist for Slice 7 green

Slice 7 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Receipt scan processing race tests pass.
- [ ] Receipt save validation tests pass.
- [ ] Save/create/link tests pass.
- [ ] Currency tests pass.
- [ ] Tax-inclusive tests pass.
- [ ] Quick-save tests pass.
- [ ] AI assist tests pass.
- [ ] Item categorization tests pass.
- [ ] Save is idempotency-safe.
- [ ] Scan processing cannot overwrite newer scans.
- [ ] Transaction creation + receipt link is atomic or partial failure is explicit/recoverable.
- [ ] Deprecated transaction creation API is removed from receipt scan.
- [ ] No placeholder EUR can be persisted.
- [ ] Currency symbol follows selected currency.
- [ ] User-edited amount is not overwritten by tax-inclusive parsed total.
- [ ] AI artifacts are marked applied only after successful save.
- [ ] Quick-save failure preserves preview.
- [ ] Duplicate receipt/transaction states are typed and visible.
- [ ] AI assist requests cannot duplicate or remain stuck Loading.
- [ ] Raw OCR/debug viewer is privacy-gated.
- [ ] Item rationale action shows visible UI.
- [ ] UI is split into focused testable components.
- [ ] Docs are updated only after source/tests are green.

---

# 9. Agent guardrails

Do:
- Protect create-transaction/link-receipt correctness first.
- Use fake repositories/coordinators and fixed `TimeProvider`.
- Add tests before refactors.
- Treat quick-save as safety-critical.
- Make currency and amount source explicit.
- Gate raw OCR/debug content.
- Keep receipt lifecycle coordinator as the single processing entry point.

Do not:
- Rewrite the full OCR pipeline.
- Let composables perform save/link business logic.
- Persist fallback `"EUR"` as production data.
- Mark AI artifacts applied before save success.
- Let a user-edited amount be overwritten silently.
- Show debug OCR text in production.
- Add new receipt features before race/idempotency/link tests exist.

Main invariant:

> For one selected receipt image/PDF, the app must show the matching OCR result, preserve user corrections, create at most one ledger transaction, link the receipt reliably, respect privacy/currency policy, and never quick-save or AI-apply data without a tested success path.