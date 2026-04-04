# Phase 4A Plan

## Goal

Open Phase 4 through narrow, opt-in, human-confirmed flows that save time without letting AI bypass deterministic validation or existing financial write paths.

## Repo-grounded scope

Phase 4A should build on the current advisory stack that already exists:

- AI settings and per-capability kill switches
- provider routing with cloud and on-device fallbacks
- apply and dismiss flows in receipt and review surfaces
- artifact provenance and `APPLIED` / `DISMISSED` tracking
- existing manual save and approve paths in `ReceiptScanViewModel.kt` and `ReviewViewModel.kt`

## Guardrails

- Keep deterministic repositories and validation authoritative.
- Require explicit user confirmation before any assisted write.
- Keep `NotificationProcessingPipeline.kt` free of AI calls.
- Keep `CategorizationEngine.kt` authoritative for deterministic categorization.
- Do not add auto-approval, auto-rejection, auto-merge, or auto-delete behavior.
- Keep every Phase 4A feature behind its own rollback toggle.

## Phase 4A feature set

1. Receipt quick save
2. Proactive AI briefings
3. Review quick approve after guarded re-evaluation

These are the safest first Phase 4A features because they either reuse existing confirmed save or approve paths or remain read-only and notification-driven.

## PR slicing

### PR1: Guardrails and opt-ins foundation

- Add explicit Phase 4A settings toggles for proactive briefings and receipt quick save.
- Surface those toggles in `AiSettingsScreen.kt`.
- Wire daily briefing scheduling to the proactive opt-in instead of scheduling it unconditionally at startup.
- Do not add any new write automation yet.

### PR2: Receipt quick save confirmation

- Add a guarded confirmation flow in `ReceiptScanScreen.kt` and `ReceiptScanViewModel.kt`.
- Let AI fill only unresolved draft fields before save.
- Reuse the existing `createExpenseFromReceipt(...)` validation and save path.
- Mark only the artifacts actually used by the confirmed action as applied.

### PR3: Proactive briefing notification delivery

- Reuse the daily briefing worker and cached artifacts.
- Deliver a dashboard deep-link notification only when the proactive toggle is enabled.
- Keep the notification read-only; no write actions from the notification.

### PR4: Feedback and hardening

- Record accepted, dismissed, and automation-used paths clearly.
- Add manual QA checks for confirmation clarity, rollback switches, and no-regression behavior.
- If review quick approve graduates from deferral, keep it category-only and confirmation-based.

## Explicit non-goals

- No review auto-approval.
- No duplicate auto-reject or auto-merge.
- No assistant-triggered writes.
- No AI writes to `Expense`, `PendingReview`, budgets, planned expenses, or category dictionaries outside the existing confirmed save path.

## Entry criteria for broader Phase 4 work

- Receipt quick save is understandable and easy to cancel.
- Proactive briefings are useful without feeling spammy.
- Rollback toggles work immediately.
- The normal receipt save and review approval flows remain trustworthy when all Phase 4A toggles are off.

## Closeout status

- Guardrail toggles, scheduling sync, receipt quick save, proactive briefing delivery, and review quick approve are now implemented.
- Proactive briefing delivery records delivery/open events and avoids repeat notifications for already delivered or opened dashboard briefing keys.
- Receipt quick save and review quick approve both require confirmation and re-check rollback toggles before final save or approve.
- Debug diagnostics expose recent Phase 4A interactions plus the last delivered and opened briefing keys.
- Manual QA checklist lives at `docs/AI_PHASE4A_QA_CHECKLIST.md`.
