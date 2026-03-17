# Receipt Image Assist Plan

## Goal

Add an opt-in, cloud-only receipt assist escalation path that can inspect the saved receipt image in addition to OCR text and parsed fields.

This feature is meant to improve hard scans such as Greek receipts where OCR and the deterministic parser lose important characters before AI ever sees them.

## Why This Exists

- Current receipt assist only sees OCR text and parsed receipt fields.
- If OCR misreads Greek characters, the parser and the AI both inherit damaged text.
- The saved receipt image already exists locally, so the app can optionally let cloud receipt assist cross-check the image.

## Guardrails

- Keep this suggestion-only. No direct writes from AI.
- Keep final save in `ReceiptRepository.createExpenseFromReceipt(...)`.
- Keep the existing OCR and parser pipeline as the default baseline.
- Make image-aware assist cloud-only for now.
- Put image-aware assist behind its own explicit opt-in toggle.
- Respect redaction and cloud settings. If cloud is off, or redaction policy blocks it, image input must not be sent.
- Do not make image input the default for every AI capability. This is receipt-only.

## Scope

### In scope

- Add a receipt-image cloud toggle in AI settings.
- Extend receipt assist input so cloud receipt assist can access the saved local image path and mime type when allowed.
- Send the receipt image together with OCR text and parsed facts only for cloud receipt assist.
- Surface to the user when image-aware assist was used.
- Keep routing, artifact storage, and manual confirmation behavior intact.

### Out of scope

- No direct image upload for review explanation, category assist, or dedupe assist.
- No on-device multimodal receipt assist in this slice.
- No replacement of ML Kit OCR or the deterministic parser.
- No automatic reprocessing of all past receipts.

## Repo-Grounded Hooks

- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt`

## Rollout Shape

### PR1: Guardrails and plumbing

- Add `receiptImageCloudEnabled` to AI settings.
- Pass image metadata through the receipt assist input builder only when policy allows it.
- Keep existing text-only behavior unchanged when the toggle is off.

### PR2: Cloud receipt assist vision path

- Extend cloud receipt assist request building to include inline image data when enabled and available.
- Keep on-device receipt assist text-only.
- Keep JSON response schema unchanged.

### PR3: UI transparency and tests

- Surface message/diagnostics that image-aware assist was used.
- Add tests for toggle behavior, prompt/request construction, and fallback behavior.

## Success Criteria

- Users can opt into image-aware cloud receipt assist explicitly.
- Hard receipts with weak OCR can be retried with AI that sees both image and OCR.
- Save flow remains unchanged and manual.
- When the toggle is off, the feature behaves exactly like current receipt assist.
