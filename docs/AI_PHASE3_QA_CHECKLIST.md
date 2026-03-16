# AI Phase 3 QA Checklist

Use this checklist to close out the Phase 3 capture-assist release behind feature flags.

## Preconditions

- App builds successfully on the target branch.
- Phase 2 assistant/query verification is already green.
- AI flags can be toggled for `ai_enabled`, `ai_receipt_assist_enabled`, `ai_categorization_fallback_enabled`, and `ai_dedupe_judge_enabled`.
- Test data includes:
  - low-confidence scanned receipts
  - pending reviews with weak or missing category suggestions
  - pending reviews with ambiguous duplicate candidates

## Receipt Assist Checks

- With receipt assist disabled, scan a weak receipt and verify the manual correction flow still works without any AI dependency.
- With receipt assist enabled, scan a low-confidence receipt and verify `Try AI assist` appears only in review mode.
- Apply merchant, total, and date suggestions one by one and verify only local draft state changes.
- Apply all suggestions and verify `Save Expense` still uses the existing deterministic save path.
- Dismiss receipt assist and verify the suggestion card disappears without changing the saved receipt or expense tables.
- Verify tax hints remain advisory only and do not silently alter stored expense data.

## Review Assist Checks

- For a weak or missing category suggestion, request AI category assist and verify the suggestion maps to an existing app category only.
- Apply the category suggestion and verify it only prefills the existing edit sheet instead of mutating the review directly.
- Approve the review after applying the suggestion and verify approval still runs through the normal review approval path.
- Request duplicate assist on an ambiguous review and verify the result is advisory text only.
- Dismiss category or dedupe assist and verify the card is hidden without rejecting, approving, merging, or deleting anything.

## Trust Boundary Checks

- Verify `NotificationProcessingPipeline.kt` remains AI-free in actual behavior and no receipt/category/dedupe assist runs during notification ingestion.
- Verify `CategorizationEngine.kt` still supplies deterministic suggestions first and AI acts only as fallback or explicit request help.
- Verify AI suggestions never create new categories and never write directly to `Expense`, `PendingReview`, budgets, or planned expenses.
- Verify duplicate assist only evaluates a bounded deterministic candidate set rather than the full ledger.

## Regression Checks

- Manual receipt scan/save still works with all Phase 3 flags off.
- Review approve, reject, and edit flows still work when all Phase 3 flags are off.
- Statement import still behaves deterministically even if capture assist providers are no-op.
- Phase 2 assistant/query flows still compile and open correctly after Phase 3 changes.

## Verification Commands

- `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
- `./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.*" --tests "com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelStressTest" --tests "com.yourname.expensetracker.ui.screens.review.ReviewViewModelStressTest"`

## Environment Notes

- If running instrumented tests, the environment still needs `adb` plus a connected device or emulator.
- If instrumented execution is unavailable, record that the Phase 3 closeout is unit-tested and compile-verified, with device validation pending environment readiness.
