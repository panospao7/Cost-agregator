# AI Phase 4A QA Checklist

Use this checklist to close out the Phase 4A guarded-assistance rollout.

## Preconditions

- App builds successfully on the target branch.
- Earlier AI phases are already green.
- AI settings can toggle:
  - `ai_enabled`
  - `ai_dashboard_briefing_enabled`
  - `ai_proactive_briefings`
  - `ai_receipt_quick_save`
  - `ai_review_quick_approve`
- Test data includes:
  - a fresh dashboard briefing artifact for today
  - a scanned receipt with missing merchant and/or amount that AI can suggest
  - a pending review with AI category assist ready
  - a pending review that AI marks as a likely duplicate

## Proactive Briefing Checks

- With proactive briefings disabled, verify no dashboard briefing notification is delivered even when a fresh briefing artifact exists.
- With proactive briefings enabled, verify a fresh dashboard briefing notification deep-links to the dashboard only.
- Open the proactive notification and verify it records the briefing as opened and does not trigger any financial write.
- Re-run the daily briefing path for the same briefing key and verify the notification is not re-sent after delivery or open.
- Turn proactive briefings off and verify the worker scheduling sync removes future proactive delivery behavior.

## Receipt Quick Save Checks

- With receipt quick save disabled, verify the receipt review flow stays fully manual and no quick-save CTA appears.
- With receipt quick save enabled, verify quick save appears only when AI can safely fill at least one missing field.
- Open the quick-save confirmation and verify it clearly lists field sources before saving.
- Confirm quick save and verify the final write still goes through the existing receipt save path.
- Open the quick-save confirmation, then turn the feature off, and verify the preview closes and confirm no longer saves.
- Dismiss quick save and verify no expense is created and no core receipt data is mutated.

## Review Quick Approve Checks

- With review quick approve disabled, verify the review queue still uses only normal approve, reject, and edit flows.
- With review quick approve enabled and AI category assist ready, verify quick approve opens a confirmation that says only category changes and the normal approval path are used.
- Confirm quick approve and verify approval still runs through the existing review approval path.
- Open the quick-approve confirmation, then turn the feature off, and verify the preview closes and confirm no longer approves.
- For a likely duplicate review, verify quick approve is blocked and the UI explains that manual review is required.
- Verify dedupe does not auto-reject, auto-merge, or otherwise mutate reviews by itself.

## Rollback And Trust Boundary Checks

- Turn all Phase 4A toggles off and verify proactive briefings, receipt quick save, and review quick approve all disappear immediately.
- Verify `NotificationProcessingPipeline.kt` remains AI-free in actual behavior during notification capture.
- Verify `CategorizationEngine.kt` remains authoritative for deterministic categorization and AI only assists around the existing paths.
- Verify AI does not write directly to `Expense`, `PendingReview`, budgets, planned expenses, or category dictionaries outside confirmed save or approve flows.

## Debug Visibility Checks

- Open the debug screen and verify recent AI runtime events include Phase 4A delivery, open, preview, dismiss, and accept interactions after exercising the flows.
- Verify the debug screen shows the current Phase 4A rollout state, including the last delivered and last opened dashboard briefing keys.

## Regression Checks

- Manual receipt scan and save still work with all Phase 4A toggles off.
- Review approve, reject, and edit flows still work with all Phase 4A toggles off.
- Dashboard briefing still falls back safely when AI is disabled or unavailable.
- Existing review explanation, receipt assist, category assist, and dedupe assist flows still behave as before.

## Verification Commands

- `./gradlew :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCaseTest" --tests "com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelStressTest" --tests "com.yourname.expensetracker.ui.screens.review.ReviewViewModelStressTest" --tests "com.yourname.expensetracker.ui.screens.debug.DebugViewModelStressTest" --tests "com.yourname.expensetracker.ui.screens.debug.DebugScreenTextTest"`
- `./gradlew lintDebug testDebugUnitTest assembleDebug`

## Environment Notes

- If device or emulator validation is unavailable, record that Phase 4A closeout is unit-tested and compile-verified, with on-device confirmation pending environment readiness.
