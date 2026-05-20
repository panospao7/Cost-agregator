# Deep Evaluation Report — commit `8b229c7710dda0e384a2a7052bc3ed99ced52010`

## Scope reviewed
User docs:
- `global_privacy_raw_storage_redaction_plan.md`
- `response_7.md`
- `remaining_privacy_plan_after_43b5cae.md`
- `privacy_raw_storage_deep_review_43b5cae.md`

Code reviewed in current commit:
- cloud AI providers
- notification capture
- email receipt lifecycle
- retention
- privacy guard script

## Executive verdict
This commit is a real improvement and **does resolve several of the previously identified privacy issues**, especially the cloud-provider migration, notification refresh parity, and email retention redaction.

But it is **not fully done** yet.

### What looks resolved
- Cloud providers now mostly use `CloudPayloadPolicy.prepareText(...)` with a prepared prompt.
- The old allow-all test gates were replaced with fail-closed test constructors in the reviewed cloud providers.
- Notification refresh now checks shutdown, privacy, and blocked-package state before extras extraction.
- Email dedupe now uses `messageIdHash` instead of raw message ID.
- Email retention now redacts sensitive fields instead of deleting rows.
- Static guard gained a new rule for `hashCode()` in cloud providers.

### What is still open
1. At least one live email side-effect path still does **not** pass `correlationId`.
2. `CloudReceiptAssistService` still handles image upload locally instead of through a fully policy-owned prepared payload.
3. The static privacy guard is still heuristic and can be bypassed.
4. The broader fail-closed settings corruption/load-state fix is **not evidenced** in this commit.
5. Real email persistence payload wiring is still **not verified** in the live path.

---

## Issue-by-issue status

### 1) Cloud provider PreparedCloudPayload migration
**Status:** Mostly resolved  
**Type:** architectural fix, with privacy impact

Reviewed files:
- `CloudWarrantyExtractionService.kt`
- `CloudCategorizationAssistService.kt`
- `CloudDedupeJudgeService.kt`
- `CloudReviewExplanationService.kt`
- `CloudReceiptItemCategorizationService.kt`
- `CloudReceiptAssistService.kt`

What changed:
- Providers now prepare a full raw prompt first, then call `cloudPayloadPolicy.prepareText(...)`.
- Request bodies are built from `prepared.text`.
- Test constructors now fail closed rather than allow all.

Remaining cleanup:
- `CloudCategorizationAssistService` still contains a dead `prepareText(..., "")` probe.
- `CloudReceiptItemCategorizationService` still keeps `shouldRedact` in the prompt builder, but it is effectively unused.
- These are not the main bug anymore, but they are signs that the abstraction is still thinner than the plan.

### 2) Receipt assist prompt/image handling
**Status:** Partially resolved  
**Type:** privacy architecture gap, potentially user-facing

Reviewed file:
- `CloudReceiptAssistService.kt`

What is fixed:
- The full raw prompt is now built before policy preparation.
- `prepared.text` is used in the request body.

What is still risky:
- Image upload is still decided locally with `allowImage && !prepared.redactionApplied`.
- The request still reads the image file directly in the provider.
- `PreparedCloudPayload.rawImageIncluded` is not actually driving the provider decision.

Why this matters:
- The current contract says the policy layer should own the final cloud payload.
- Right now, text is policy-driven, but image inclusion is still provider-driven.
- This is the biggest remaining privacy gap in the cloud path.

### 3) Item categorization redaction
**Status:** Resolved functionally, but still a little messy  
**Type:** architectural cleanup

Reviewed file:
- `CloudReceiptItemCategorizationService.kt`

What is fixed:
- The empty-string policy probe is gone.
- The provider now builds the real prompt first, then prepares it through policy.
- No `hashCode()` pseudonyms remain in this file.

Minor cleanup:
- `shouldRedact` is still present in the prompt builder and appears unnecessary.
- This is not a user bug, but it should be cleaned to avoid future drift.

### 4) Static privacy guard
**Status:** Improved, still weak  
**Type:** architectural regression-risk

Reviewed file:
- `scripts/verify_privacy_boundaries.py`

What improved:
- New G11 blocks `.hashCode()` in cloud provider code.
- The guard still checks for direct provider redaction misuse and bad gate patterns.

What is still weak:
- G3 is still context-based and can be fooled by nearby `prepared` text.
- G4 still looks for `PrivacyDecision.Allowed` nearby rather than truly parsing the anonymous gate.
- This is not a strong enough barrier if you want reliable CI enforcement.

Recommendation:
- Either strengthen the script substantially or replace direct `Request.Builder()` usage with a dedicated `CloudAiTransport` so the guard can be simpler and stricter.

### 5) Notification refresh path parity
**Status:** Resolved  
**Type:** actual bug fix

Reviewed file:
- `NotificationCaptureService.kt`

What is fixed:
- Refresh path now checks:
  1. restore/maintenance
  2. shutdown
  3. fast privacy
  4. blocked package cache
  5. then extras extraction

This directly addresses the earlier pre-extraction leak risk.

### 6) Email dedupe / diagnostics / correlation
**Status:** Partially resolved  
**Type:** actual traceability bug

Reviewed file:
- `ReceiptLifecycleCoordinator.kt`

What is fixed:
- `emitEmailReceiptDiagnostic(...)` now takes `correlationId`.
- Duplicate detection now uses `messageIdHash`.

What is still open:
- At least one post-commit side-effect call still appears to omit `correlationId`:
  - `dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT)`
- That means traceability is still incomplete.

Impact:
- This is a real support/debug bug, not just architecture.
- It weakens auditability of the email pipeline.

### 7) Email persistence payload wiring
**Status:** Not fully verified / likely still open  
**Type:** architectural + privacy hardening

What I could verify:
- The coordinator still visibly handles raw email body parameters.
- The commit summary mentions correlation and hash cleanup, but not a full API migration to `EmailReceiptPersistencePayload`.

What I could not verify:
- That the live ingestion path now truly requires `EmailReceiptPersistencePayload`.
- That the schema has a dedicated hash column rather than reusing the raw message-id field semantically.

Recommendation:
- Treat `EmailReceiptPersistencePayload` wiring as still open until the ingestion service signature is explicitly payload-first.

### 8) Retention
**Status:** Improved, but still needs revalidation  
**Type:** can become a user-facing data-loss bug

Reviewed files:
- `DataRetentionWorker.kt`
- `EmailReceiptDao.kt`

What is fixed:
- Email retention now uses `emailCutoff`.
- Email retention redacts sensitive fields instead of deleting rows.

What still needs verification:
- Non-email targets still receive `now` as the purge input.
- That is only safe if every target interprets it as a base timestamp and not a literal delete-before-now cutoff.
- `EmailReceiptDao.deleteOlderThan(...)` still exists; keep it only if there is a deliberate non-privacy cleanup path.

### 9) PrivacySettings corruption / fail-closed load state
**Status:** Not evidenced in this commit  
**Type:** actual user-facing privacy bug if still present

This is important:
- The broader plan required a fail-closed load-state path for DataStore corruption.
- I did **not** see evidence in this commit of `PrivacySettingsLoadState` or equivalent repository changes.

So:
- Do **not** assume the corruption/fail-closed issue is fixed.
- Recheck it separately.

---

## Bug vs architecture classification

### Actual bugs affecting users
- Missing `correlationId` in at least one email side-effect call.
- Potential cloud image upload privacy leak path in receipt assist.
- Possible fail-open settings corruption path if still unchanged.
- Retention semantics risk if any target interprets `now` as a hard cutoff.

### Architectural work / refactor debt
- Generic `CloudPayloadPolicy` still leaves prompt assembly in providers.
- Static guard is heuristic rather than authoritative.
- `CloudReceiptAssistService` still owns image assembly locally.
- Email payload model is not yet proven to be the actual live path contract.
- Retention registry semantics should be type-safe per target.

---

## Recommended next fixes, in order

### P0
1. Patch the remaining `dispatchPostCreationSideEffects(...)` call to include `correlationId`.
2. Decide the image policy for `CloudReceiptAssistService`:
   - either move image decision into `PreparedCloudPayload`
   - or suppress image upload entirely until a real image-redaction pipeline exists

### P1
3. Verify or implement full `EmailReceiptPersistencePayload` wiring in the live ingestion path.
4. Recheck DataStore corruption / settings load-state fail-closed behavior.
5. Verify retention semantics for all non-email targets.

### P2
6. Strengthen `verify_privacy_boundaries.py` or replace the direct transport pattern so CI can prove there is no raw cloud-post bypass.
7. Add behavioral tests, not just contract/model tests.

---

## Agent-ready implementation plan

### PR A — email correlation completion
- Add `correlationId` to every post-commit side effect dispatch.
- Add a regression test that fails if email side effects lose correlation.

### PR B — receipt assist image contract
- Move image inclusion behind `PreparedCloudPayload`.
- Add `imageBytes` / `imageMimeType` population or suppress image upload by default.
- Add a test proving no raw image is uploaded when policy says redaction or suppression is required.

### PR C — settings fail-closed recheck
- Verify repository load-state behavior on corruption.
- Confirm `notificationCaptureEnabled`, raw storage modes, cloud AI, and debug persistence fail closed.

### PR D — retention semantics audit
- Verify every registered retention target uses a real, explicit cutoff contract.
- Add per-target tests for row counts and no accidental mass deletion.

### PR E — static guard hardening
- Prefer a transport abstraction over heuristic scanning.
- Keep G11, but strengthen G3 and G4.

---

## Bottom line
This commit is a **good partial completion** of the privacy/raw-storage refactor, especially for cloud providers and notification refresh.

But I would **not** call the privacy work finished yet.

The most important remaining items are:
1. email side-effect correlation,
2. receipt image handling,
3. fail-closed settings corruption recheck,
4. static guard hardening,
5. end-to-end behavior tests.

## Sources reviewed
- Commit: https://github.com/panospao7/Cost-agregator/commit/8b229c7710dda0e384a2a7052bc3ed99ced52010
- `CloudReceiptAssistService.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt
- `CloudReceiptItemCategorizationService.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt
- `CloudWarrantyExtractionService.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt
- `CloudDedupeJudgeService.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt
- `CloudReviewExplanationService.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt
- `NotificationCaptureService.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
- `EmailReceiptDao.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt
- `ReceiptLifecycleCoordinator.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `DataRetentionWorker.kt`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt
- `verify_privacy_boundaries.py`: https://github.com/panospao7/Cost-agregator/blob/8b229c7710dda0e384a2a7052bc3ed99ced52010/scripts/verify_privacy_boundaries.py