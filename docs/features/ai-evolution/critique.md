# ai-evolution — Portfolio critique

Author: feature-critic (independent; attempt 2 — attempt 1 hit its turn limit with no report). Date: 2026-10-16.
Under review: `docs/features/ai-evolution/idea-portfolio.md`. Baseline: `docs/features/ai-evolution/ai-inventory.md`.
Bounded verification (~20 tool calls); claims not checked in source are marked UNVERIFIED.
Persisted by the orchestrator.

## 1. Fact-check

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| (a) | Briefing worker requires `CLOUD_AI_DAILY_BRIEFING` + notification permission → cloud-off / no-notification users get no briefing | **HOLDS mechanically; OVERSTATED in framing; fix is larger than claimed** | `data/ai/worker/DailyBriefingWorker.kt:74-75`; `domain/privacy/CloudAiPrivacyGate.kt:31-34`; `domain/workers/WorkerExecutionGuard.kt:174` (permission check before `block()`); `GenerateDashboardBriefingUseCase` only called by the worker. |
| (b) | Assistant follow-ups only with `storeConversationHistory=true` | **HOLDS** | `ui/screens/assistant/AssistantViewModel.kt:237-249` returns `emptyList()` unless history stored and session exists; passed at `:253`. |
| (c) | `user_corrections` + `ai_artifacts` APPLIED/DISMISSED unused by AI | **HOLDS for LLM paths; OVERSTATED as "app doesn't learn"** | See below. |
| (d) | Add-expense goes through TLC | **HOLDS** | `ui/screens/addexpense/AddExpenseViewModel.kt:377` → `data/repository/ManualExpenseRepository.kt:163` (`createExpenseDbOnlyV2`); post-insert `:241` calls `generateTransactionInsightUseCase`. |

**(a) detail**
- Default users get no briefing because `dashboardBriefingEnabled`/`proactiveBriefingsEnabled` are off by default (UNVERIFIED in `AiModels.kt`), not because of the cloud gate. Affected: users who enabled briefing with cloud off, or denied notifications.
- Cloud gate applied twice: `domain/workers/PrivacyRuntimeWorkerPolicy.kt:58` maps CLOUD_AI toggle to `ai_daily_briefing` (cloud off cancels worker). Fix also touches `PrivacyRuntimeWorkerPolicy`, its test, `WorkerGuardVerifier.kt:41`, likely FG worker guards.
- Notification half contradicts `AGENTS.md:165-167` (notification permission gates posting only).

**(c) detail**
- APPLIED/DISMISSED only written (`AiArtifactDao.kt:56,63`); cache reuse checks `READY` only (`SuggestCategoryFallbackUseCase.kt:148-159`, `SuggestReceiptExtractionUseCase.kt:74`, `JudgePendingReviewDuplicateUseCase.kt:64`) → dismissed suggestions regenerate.
- Corrections already used deterministically: `domain/intelligence/TransactionClassifier.kt:140,194`; `learnMerchantCategory` via `CategoryRepository.kt:193`, `HybridExpenseClassifier.kt:252`, `CategorizeExpenseUseCase.kt:27`. Bulk backfill through TLC is C11-DEFERRED (`CategoryRepository.kt:177-199`).

## 2. Per-idea verdicts

| Id | Verdict | Reason |
|---|---|---|
| A1 briefing-for-everyone | RESHAPE (split) | Core is a defect (notification permission blocks generation; hybrid gated as cloud) → bugfix track. Residual (refresh, time picker, separate insight toggle) small T2. Caution: ML Kit GenAI/AICore is documented foreground-only (critic's knowledge, not repo-verified) → on-device fix may have to be "generate on Home open", not re-gating the midnight worker. |
| A2 briefing-grounded-signals | KEEP (on-device first) | Reuses ~10 engines; template mode must be the baseline (Nano Greek quality unproven). Cloud signal payload = T3, wait for per-purpose allowlist. |
| A3 suggestion-feedback-loop | RESHAPE | "Don't regenerate DISMISSED for same target+sourceHash" is a bugfix. Correction learning exists deterministically. Few-shot from corrections needs notificationText exclusion. Acceptance-rate display = gimmick. |
| A4 assistant-follow-ups | KEEP | Real gap, S–M, no persistence. Prior intent to cloud = new content under existing purpose → privacy review (borderline T3). On-device + deterministic chips first. |
| A5 nano-receipt-items | KEEP, later | Structured output alpha, latency, receipt lifecycle side-effect writes (T3). Needs fixtures. |
| A6 warranty-on-device-and-confirm | RESHAPE (split) | Default-on toggle + tap-free ≥0.75 persistence = consent/behaviour defects → bugfix track, owner decision. Nano route later. |
| A7 honest-labels-and-dormant-cleanup | KEEP labels/docs; RESHAPE dormant code | Labels/FEATURES.md = T1 now. Wiring/deleting dormant use cases touches tests/guards → stop-and-ask. |
| A8 cloud-key-transparency | KEEP (copy only) | Cheap; re-check current Gemini terms (cited 2024). Model selection → C5. |
| B1 nl-quick-add | KEEP, RESHAPE scope | Best new value; writes only via TLC. Deterministic parser is the product, Nano optional enhancer; no voice in increment 1. See post-insert insight caution. |
| B2 explain-this-number | MERGE-INTO A2 | Same digest adapter, different surface. |
| B3 bulk-cleanup-suggestions | RESHAPE, defer | No bulk TLC recategorization (C11-DEFERRED); no legal owner for alias writes; T3. |
| B4 subscription-auditor | RESHAPE | Value is deterministic (price-hike/overlap/renewal alerts); ship outside AI portfolio. |
| B5 negotiation-and-cancel-drafts | DROP for now | Rare use, Greek Nano quality doubtful, cloud sends provider names/prices. Fix label via A7. |
| B6 plan-coach | DROP for now | L/T3; no Budget Mutations legal path in LEGAL_PATHS (only §Budget Forecasting line 1224); depends on C3/C4. |
| B7 period-recap | MERGE-INTO A2 (later) | Narration = same digest; DROP export cover page (new exported content under `ExportPrivacyGate`). |
| B8 receipt-split-assist | DROP | L/T3 cross-coordinator rounding; niche. |
| B9 installment-recognition | MERGE-INTO loan-installments roadmap | Mostly deterministic; write path belongs to loan feature. |
| B10 greek-e-payment-tax-helper | DROP from AI portfolio | Rules + yearly upkeep, near tax advice; separate non-AI brief if wanted. |
| B11 trip-recap | RESHAPE | Deterministic per-trip card is the value; location-sensitive; low priority. |
| B12 what-if-affordability | DROP for now | Near advice; needs C3. |
| C1 ai-eval-harness | RESHAPE (shrink) | Nano can't run in CI; cloud evals need key/network/cost/egress. Fake-provider/parser golden tests + manual on-device debug eval, built inside first LLM-heavy feature. |
| C2 personal-context-provider | MERGE-INTO A3 | Premature abstraction / new privacy boundary; build with first consumer. |
| C3 read-only-tool-assistant | DEFER | L/T3, biggest privacy-model change; Nano has no tool calling. |
| C4 suggestion-apply-contract | RESHAPE | Extract after a second apply consumer exists (post-B1); avoid a write facade. |
| C5 provider-abstraction-and-reach | DROP for now | Multi-GB Gemma download; each vendor = new data processor. Revisit only if no AICore. |
| C6 ai-transparency-center | RESHAPE (partial KEEP) | Cheap part: per-feature "available / blocked because…" status. Per-call ledger needs `PrivacyAuditLogger` retention review — not S. |

## 3. Shortlist challenge
- Overrated: A1 at #1 (mostly a defect; not cheap — workers, privacy-runtime policy, guards, AICore foreground limit); C1 as Wave 0; C2/C3/C4 as up-front foundations; B4 as "low-risk AI".
- Underrated: Nano Greek-language quality (affects all narration ideas → template/deterministic baseline everywhere); post-insert transaction insight (`ManualExpenseRepository.kt:241`) reuses DASHBOARD_BRIEFING purpose — B1 would multiply cloud calls under the wrong purpose (UNVERIFIED payload) → fix before B1.
- Cheap? A7 labels / A8 copy yes; A4 mostly; A1 no; C1 no as written.
- Proposed sequencing: Wave 0 = bugfix track + A7 labels + A8 copy. Wave 1 = B1 → A4. Wave 2 = A2 (+B2/B7) on-device. Rest gated on owner answers.

## 4. Bugs vs features (bugfix track)
1. Briefing generation blocked by notification permission (`DailyBriefingWorker.kt:75` + `WorkerExecutionGuard.kt:174`) vs `AGENTS.md:165-167`.
2. Hybrid briefing gated as cloud-only (`DailyBriefingWorker.kt:74`, `PrivacyRuntimeWorkerPolicy.kt:58`); needs domain-modeler call (AICore background limits).
3. Dismissed AI suggestions regenerated/re-shown (cache honours READY only); UX reappearance UNVERIFIED.
4. Warranty extraction default-on + tap-free persistence ≥0.75 — owner decision.
5. Transaction insight shares DASHBOARD_BRIEFING purpose/toggles/concrete service — purpose-scoping breach (UNVERIFIED wiring).
6. Docs/strings: FEATURES.md "AI-powered" mislabels; hard-coded `AiSettingsScreen` strings; stale chat-retention comment in `DataRetentionWorker`.

## 5. Recommendation
First pick: **`nl-quick-add` (B1), reshaped** — deterministic parser first, optional Nano enhancer, text only, prefill into existing add-expense, raw text in memory only. Size M; no new data flow or write path; risk concentrated in money parsing (Greek decimal comma, currency default, relative dates/time zone). Precondition: bugfix item 5 resolved or explicitly accepted.
Next 3: `assistant-follow-ups` (A4); `briefing-grounded-signals` (A2 + B2 + B7 narration, after bugfixes 1-2); `honest-ai-labels` (A7 labels) + `cloud-key-transparency` (A8 copy).
Not next: C1 standalone, C2, C3, C4, C5, B3, B6, B8, B10, B12.

## 6. Owner questions (before B1 → brief)
1. Share of spending that is cash/uncaptured; quick-add language (Greek / Greeklish / English)?
2. Devices — AICore/Gemini Nano available?
3. Foreign currency in text ("20 usd"): prefill original currency and convert at save via existing converter, or refuse/ask?
4. Confirm raw typed text is never persisted (not even as note) unless user edits the note field.
5. Should a quick-add save trigger the post-insert transaction insight (possibly cloud), or fix bugfix item 5 first?
6. Approve moving briefing notification-permission decoupling (bugfix 1-2) into a bugfix track as a shipped-behaviour change?

## 7. Overall verdict: **RESHAPE**
Proceed once: briefing gating/permission items move to a bugfix track; C1/C2 folded into first consuming features; B1 promoted to first pick with deterministic-first scope.
