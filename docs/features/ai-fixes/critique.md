# Critique: ai-fixes

Author: feature-critic (independent). Under review: `docs/features/ai-fixes/brief.md`. Date 2026-10-16. Persisted by the orchestrator.
Root `A` = `app/src/main/java/com/yourname/expensetracker`. ~22 read-only tool calls; anything not checked in source is UNVERIFIED.

## 1) Fact-check of load-bearing claims
| Claim | Verdict | Evidence |
|---|---|---|
| Worker needs cloud capability + notification permission | HOLDS | `A/data/ai/worker/DailyBriefingWorker.kt:74` (`CLOUD_AI_DAILY_BRIEFING`), `:75` (`requiresNotificationPermission = true`); violates `AGENTS.md:165-167`. |
| CLOUD_AI toggle cancels `ai_daily_briefing` | HOLDS | `A/domain/workers/PrivacyRuntimeWorkerPolicy.kt:58`. Same map gates `warranty_expiration_check` behind NOTIFICATION_CAPTURE (`:60-63`) — relevant to item 4. |
| Insight reuses briefing service/toggle/capability | HOLDS | `A/domain/ai/usecase/GenerateTransactionInsightUseCase.kt:43`, `:81`, `:87,97,115`; 3 s hard timeout `:52`. |
| Insight is a valuable user-visible feature | OVERSTATED (not assessed by brief) | Artifact only supplies `summaryText` for deterministic recommendations (`A/domain/engine/DashboardFollowThroughEngine.kt:149,181,217`); called post-commit when `aiEnabled` (`A/data/repository/ManualExpenseRepository.kt:237-245`). Value = one sentence on a card that exists anyway. |
| On-device Nano in build | HOLDS | `app/build.gradle.kts:302` (`genai-prompt:1.0.0-beta1`); `A/data/ai/provider/OnDeviceDashboardBriefingService.kt:3-7,45-51`. |
| "Warranties ≥0.75 saved ACTIVE without a tap" | **WRONG as production behaviour** | Band exists (`A/data/repository/WarrantyTrackerRepository.kt:74,497-498`) but `extractWarrantyFromReceipt` (`:432`) and `processReceiptForWarranty` (`:569`) have no caller in `app/src/main` (tests only, `WarrantyTrackerRepositoryTest.kt:162+`). Only production entry: `upsertReturnWindowForReceipt` (`:392-429`) ← `AutoCreateWarrantyFromReceiptUseCase.kt:382`. It sends `rawOcrText` to cloud (`:400,457-465`) but **discards the cloud warranty**; only `productName`, `returnDays`, `returnConditions` land in a **ReturnWindow**, saved without a tap (`:403-413,539,562`). It calls cloud even when a warranty already exists (`fallbackWarranty ?: extracted`, `:402`; `AutoCreate…:97-101`) → every re-run re-uploads OCR text. |
| PENDING_REVIEW confirm UI exists | HOLDS | `A/ui/screens/warranty/WarrantyTrackerViewModel.kt:181-199` (`confirmWarranty`); `WarrantyTrackerScreen.kt:191-200,304,582-597`. Raw `e.message` in UI at `WarrantyTrackerViewModel.kt:195` (same defect class as item 6). |
| Expiration/reminder workers skip PENDING_REVIEW | HOLDS (consequence missing from brief) | All expiry/reminder queries filter `status='ACTIVE'`: `A/data/database/dao/WarrantyDao.kt:16,19,25,64,67,95,111`; `WarrantyReminderDeliveryDao.kt:69,90,100`. Pending warranties never remind and **never move to EXPIRED**. |
| Deterministic auto-create out of scope and clean | OVERSTATED | `AutoCreateWarrantyFromReceiptUseCase.kt:128-131`: medium local (regex) confidence + blocked **cloud** gate → `Failure`, skipping the purely local review draft. With cloud off (default) users lose drafts — consent check applied to the wrong step. LEGAL_PATHS §Warranty Auto-Create (`docs/architecture/LEGAL_PATHS.md:981-1000`) names "WarrantyReviewDraft" while code uses PENDING_REVIEW — likely doc drift, unchecked. |
| Briefing/warranty "global-only" under RP-15 | NOT RE-VERIFIED | Relied on brief for `EffectiveCloudAiPolicy.kt`; LEGAL_PATHS has no RP-15 match. |
| Dismissed row overwritten on regeneration | UNVERIFIED | — |
| AICore foreground-only | Not checkable in source | Platform-doc claim; consistent with bounded GenAI error handling. |

## 2) Per-item verdict
1. **Notification permission / proactive gate posting only — KEEP.** Real invariant violation. Self-contained: drop `requiresNotificationPermission`, check permission at post time, schedule whenever briefing is on (not only proactive). Fixes cloud-route users without on-device work. T3 (worker guard, FG-11).
2. **On-device briefing — SPLIT-OUT as its own feature.** No on-device briefing path exists today; needs a foreground trigger + owner, dedupe/race with worker on `dashboard_home:<date>`, quota sharing with insight, possibly a template (brief calls that content/non-goal), and redefines CLOUD_AI → `ai_daily_briefing`. A feature disguised as a fix.
3. **Dismissals respected — DROP from bundle** (or shrink to option (c)). No automatic re-show; a fresh call on explicit re-tap is a UX preference, not a defect. Defer to A3.
4. **AI warranties always suggestions — RESHAPE.** As written it targets an ACTIVE band production never persists. Real defects: cloud call made for **return windows**, even when a warranty exists and on every re-run; cloud-derived return windows saved without a tap; `warrantyExtractionEnabled` default true. Keep the default flip. Decide: tap for cloud-derived return windows, or cloud only when no warranty exists; delete the dead ≥0.75 path or route it to confirm; expiry rule for pending items if volume grows. T3 (cloud payload, FG-16).
5. **Insight own setting/purpose/audit — RESHAPE.** Capability/purpose split (honest audit, own toggle, independent of briefing) justified. A dedicated insight **cloud** toggle is not for increment 1: value is one sentence on a deterministic recommendation, yet it would set the first per-capability-cloud RP-15 precedent (matrix row + test, payload review, new consent surface). Ship **on-device-only, no cloud route** — matches owner's "Nano default", no RP-15 change, fail-safe by construction. Cloud opt-in later as its own amendment. T3 (new capability, router, privacy capability).
6. **Docs/strings — SPLIT-OUT, ship first, T1.** Add `WarrantyTrackerViewModel.kt:195` raw `e.message`. Re-check each engine is truly rule-based before relabelling FEATURES.md.

## 3) Hidden scope and risks
- Worker guards: item 1 changes `WorkerGuardRequest` + `SyncProactiveBriefingWorkUseCase` condition (FG-11). Item 2 option (b) needs a cloud-off worker → split/rename gated worker in `PrivacyRuntimeWorkerPolicy.kt:58`, touching `WorkerGuardVerifier` + policy tests (FG-11, FG-16).
- Background insight: `NotificationProcessingPipeline.kt:1742` is background; Nano-only means every capture spends a call ending in `BACKGROUND_USE_BLOCKED`/`BUSY` → add a foreground short-circuit before the call. 3 s timeout (`GenerateTransactionInsightUseCase.kt:52`) may make Nano warm-up return null almost always (UNVERIFIED); success criteria should accept "mostly no insight".
- Router: `DefaultAiCapabilityRouter.kt:336-340` cloud-first set includes `WARRANTY_EXTRACTION`; new insight capability must not be added there.
- Legacy artifacts: `transaction:` keys under `DASHBOARD_BRIEFING`; Home reader keys on `dashboard_home:` (`DailyBriefingWorker.kt:90`) so collisions unlikely; purge rule for orphaned legacy rows undefined. Capability enum storage/backup restore UNVERIFIED — confirm before ruling out a migration (FG-21 only if schema change appears).
- Settings defaults: two sites per flag (`AiModels.kt`, `AiSettingsRepositoryImpl.kt`); absent-key flip silently changes untouched users; for warranty this now only removes return-window enrichment (low cost).
- Warranty lifecycle: pending items never expire (`WarrantyDao.kt:64`) or remind; growing the pending population without an expiry rule leaves stale items forever. `confirmWarranty` writes via `warrantyRepository.updateWarranty` from the ViewModel — legal-path status unchecked (FG-12).
- Local drafts blocked by cloud consent (`AutoCreate…:128-131`) is a separate adjacent bug — pull in explicitly or file; don't fix silently.

## 4) Recommended shape
- **Slice A (T1), first:** item 6 + `WarrantyTrackerViewModel.kt:195`.
- **Bundle B (T3) "consent & invariant fixes":** item 1 (cloud route only); item 4 reshaped (default off; no cloud re-upload when a warranty exists; return-window confirmation per owner; dead ≥0.75 path removed or routed to confirm); item 5 reshaped (own capability/purpose/toggle, on-device only, no cloud route, background short-circuit). Order: 4 → 5 → 1.
- **Feature C (separate brief) "on-device briefing":** item 2, after B, once AICore foreground behaviour is measured on the owner's device.
- **Item 3:** defer to A3, or option (c) only if owner insists.

## 5) Owner questions (max 6)
1. Insight cloud: on-device-only now (no RP-15 amendment), cloud opt-in later as a separately reviewed amendment? Default for new installs on (on-device) or off? Existing briefing-on users inherit?
2. Cloud warranty extraction in production only enriches return windows: require a tap for cloud-derived return windows? Skip cloud when a local warranty exists? Delete unused ≥0.75 auto-accept code or keep routed to confirm?
3. Pending warranties never expire/remind: auto-expire past end date, "still unconfirmed" state, or leave?
4. Local review drafts dropped when cloud denied (`AutoCreate…:128-131`): fix in this bundle or file separately?
5. On-device briefing as a separate feature after Bundle B? If so, Bundle B leaves on-device users unchanged (no briefing)?
6. Dismissed suggestions: drop (defer to A3) or adopt (c) — re-show dismissed result without a model call?

Brief's former Q3 (existing auto-accepted AI warranties) largely moot — production never persisted them. Former Q6 (fold-in) answered by the split.

## 6) Verdict: **RESHAPE**
Items 1, 5, 6 are real defects. Item 4 targets code with no production caller while the actual cloud upload and silent save (return windows) go unnamed. Item 2 is a feature; item 3 a preference. A dedicated insight cloud toggle isn't worth an RP-15 precedent for one sentence of recommendation text.
Minimal reshape to GO: split out items 2 and 6; retarget item 4 to `upsertReturnWindowForReceipt` + default flip; insight on-device-only (no cloud route) in increment 1; defer item 3.
