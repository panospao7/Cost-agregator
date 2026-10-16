# Feature brief: ai-fixes — AI integration defect bundle

Author: feature-strategist (stage 1). Persisted by the orchestrator. Date 2026-10-16. Docs-only; ships post-1.0 (Release 1.0 frozen).
Inputs: `workflows/active/ai-fixes-handoff.md` (owner decisions, binding), `docs/features/ai-evolution/critique.md` §1/§4, `ai-inventory.md`, `idea-portfolio.md` A1/A3/A6/A7, `AGENTS.md:163-167`.
Root `A` = `app/src/main/java/com/yourname/expensetracker`. ~30 bounded verification calls; anything not checked in source is marked UNVERIFIED.

## Problem (one line)
The shipped AI features break their own rules in six places, five of them user-visible: the daily briefing depends on notification consent and cloud consent; a dismissed suggestion costs a fresh model call when asked again; AI-found warranties are saved without a tap and extraction is default-on; a per-transaction cloud call runs under the briefing's purpose and toggle; docs call rule-based engines "AI".

## Verified facts this brief depends on
| Claim | Verdict | Evidence |
|---|---|---|
| Briefing toggles off by default | VERIFIED | `A/domain/ai/model/AiModels.kt:93` (`dashboardBriefingEnabled=false`), `:100` (`proactiveBriefingsEnabled=false`); second default `A/data/repository/AiSettingsRepositoryImpl.kt:116,123` (`?: false`). |
| Warranty extraction default-on (two sites) | VERIFIED | `AiModels.kt:107` (`= true`); `AiSettingsRepositoryImpl.kt:132` (`?: true`). |
| Worker is the only briefing generator; scheduled only if both toggles on | VERIFIED | `A/domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt:17-18`; `DeliverProactiveBriefingNotificationUseCase.kt:28`. Home card therefore also needs the "proactive" toggle. |
| Worker needs cloud capability + notification permission | VERIFIED | `A/data/ai/worker/DailyBriefingWorker.kt:74-75`; `A/domain/workers/PrivacyRuntimeWorkerPolicy.kt:58` (CLOUD_AI toggle cancels `ai_daily_briefing`). `WorkerExecutionGuard.kt:174` per critique, not re-read. |
| Transaction insight reuses briefing capability, toggle, concrete service | VERIFIED | `A/domain/ai/usecase/GenerateTransactionInsightUseCase.kt:43` (injects `HybridDashboardBriefingService`), `:81` (gated on `dashboardBriefingEnabled` only), `:87,115` (`AiCapability.DASHBOARD_BRIEFING`). Callers `A/data/repository/ManualExpenseRepository.kt:241`, `A/data/repository/NotificationProcessingPipeline.kt:1742`. |
| What insight sends to cloud | VERIFIED | `A/data/ai/provider/DashboardBriefingPromptFormatter.kt:111-149`: date, merchant label, amount, high-value flag. Redaction off → sanitized merchant + **exact amount**; on → redacted merchant + amount bucket. Purpose `CloudPayloadPurpose.DASHBOARD_BRIEFING` (`CloudDashboardBriefingService.kt:277,286`) → audit mislabels per-transaction calls as "briefing". AUTO = cloud-first (`A/domain/ai/policy/DefaultAiCapabilityRouter.kt:336-337`). Cloud gate is global only (`:167` `CLOUD_AI_GENERAL`). |
| Dismissed suggestion reappears | **PARTLY HOLDS (reframed)** | Assists run only on button tap (`A/ui/screens/review/ReviewScreen.kt:1402,1443`; `A/ui/screens/receiptscan/ReceiptScanScreen.kt:626,689,722,782`). Dismiss → DISMISSED + card idle (`ReviewViewModel.kt:927-961,1199-1206`). No automatic re-show found. Re-tap ignores dismissal: cache honours READY only (`SuggestCategoryFallbackUseCase.kt:148-159`); receipt-assist passes `force=true` (skips cache) → new model call (possibly cloud). Whether regenerated row overwrites DISMISSED row is UNVERIFIED. |
| Warranties ≥0.75 saved ACTIVE without tap; review status exists | VERIFIED | `A/data/repository/WarrantyTrackerRepository.kt:74,76,497-498` (<0.75 `PENDING_REVIEW`, ≥0.75 `ACTIVE`). Extraction sends `receipt.rawOcrText` to cloud (`:457-465`). |
| Cloud warranty + briefing deliberately "global-only" | VERIFIED | `A/domain/privacy/EffectiveCloudAiPolicy.kt:15-33,56-57` — RP-15: "a per-capability toggle must NOT be invented implicitly later". |
| FEATURES.md mislabels | VERIFIED | `docs/features/FEATURES.md:132,166,392,395,568,581,901,911`. Line 39 (warranty) is real AI. Rule-based nature of each engine not re-checked. |
| `AiSettingsScreen` strings hard-coded | **CORRECTED** | Screen is mostly localized (88 `stringResource`). Hard-coded English is in `A/ui/screens/aisettings/AiSettingsViewModel.kt:110,113,153,163,173,227,237,254,263,277,283`; `:335` surfaces raw `e.message` in UI (violates bounded-reason-code rule). |
| Stale chat-retention comment | VERIFIED | `A/data/privacy/DataRetentionWorker.kt:2` vs `:120,133` (purges `ai_chat_messages` at 30d). |

## Platform constraint (ML Kit GenAI / AICore) — official docs
- "GenAI API inference is permitted only when the app is the top foreground application." Background calls, including from a foreground service, return `ErrorCode.BACKGROUND_USE_BLOCKED`. https://developers.google.com/ml-kit/genai
- Per-app quotas: `BUSY` (bursts), `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (long-duration). Same source.
- Prompt API beta; structured output alpha; Greek not listed. https://developers.google.com/ml-kit/genai/prompt/android
- Implication 1: a midnight WorkManager job can never run Nano; re-gating the worker for on-device users yields a worker that always fails on-device.
- Implication 2 (inferred, UNVERIFIED at runtime): insights for notification-captured expenses (`NotificationProcessingPipeline.kt:1742`, background) cannot use Nano → with on-device default they get no insight in background, or must be deferred to foreground.
- Implication 3 (inferred): the worker's cloud→on-device fallback is likely dead code today.

## Who is affected
- Briefing on + notifications denied or "proactive" off → no Home card at all.
- On-device-only users (default posture, incl. owner's Nano phone) → no briefing even with both toggles on.
- Cloud on + briefing on → every manual/notification expense may send merchant + amount (exact if redaction off) under a mislabelled purpose.
- Anyone who turned cloud on → warranty extraction uploads receipt OCR without a choice and saves warranties without a tap.
- Review / receipt-scan users who dismiss then ask again.
- Readers of FEATURES.md and AI settings → untrue "AI" claims, unlocalized strings.

## Desired behaviour (increment 1, must-have)
1. **Notification permission and "proactive" gate posting only** (`AGENTS.md:165-167`). Briefing on → Home card regardless of permission/proactive; notification posted only if both allow.
2. **On-device users get a briefing.** Nano is foreground-only → on-device generation happens while the app is open; midnight worker keeps the cloud route. Options (for domain-modeler/UX, not designed here): (a) generate on Home open/refresh when no fresh briefing today, worker cloud-only; (b) (a) + deterministic template briefing (no model) from the worker for on-device users, labelled non-AI; (c) notification only for cloud-route users, card only for on-device. The cloud toggle no longer decides whether on-device users see a briefing; it still cancels the cloud route.
3. **Dismissals respected for unchanged input.** No silent regeneration or model call (on-device or cloud) for same target + same source input unless the user explicitly asks again via a visible "try again". Changed input may produce a new suggestion. Exact UX = owner Q1.
4. **AI warranties are always suggestions** (owner decision (a)). Every cloud-extracted warranty at or above the discard threshold is tap-to-confirm; nothing becomes ACTIVE without a tap; ≥0.75 auto-persist removed. `warrantyExtractionEnabled` defaults off (both default sites change together).
5. **Transaction insight gets its own setting, purpose, audit label** (owner decision). Own toggle independent of briefing; own capability + cloud payload purpose; on-device Nano default; cloud only if user enabled cloud *for insights*; no dependency on the briefing's concrete service or cloud-first ordering. Nano unavailable (background, unavailable, quota) → "no insight", never silent cloud fall-through.
6. **Docs/strings:** correct FEATURES.md mislabels; localize hard-coded `AiSettingsViewModel` messages; replace raw `e.message` (`:335`) with bounded user-safe message; fix stale `DataRetentionWorker.kt:2` comment.

## Later increments (not this bundle)
Briefing refresh/time-of-day/grounded signals (A1 residual, A2); learning from dismissals/corrections (A3 beyond don't-regenerate); Nano warranty route + unified thresholds (A6 remainder); dormant code wiring/deletion (A7 dormant part; stop-and-ask).

## Non-goals
- No new AI capabilities, prompts or models; no briefing content improvements.
- No new cloud purposes beyond splitting insight from briefing; no widening of any cloud payload.
- No Room schema change expected (`PENDING_REVIEW` exists; settings in DataStore `ai_settings`). Domain-modeler must confirm a new capability value stored in `ai_artifacts` needs no migration — if it does, flag and stop.
- No change to the deterministic regex warranty auto-create (`AutoCreateWarrantyFromReceiptUseCase`, ≥70%; LEGAL_PATHS §Warranty Auto-Create) unless owner pulls it in (Q4).
- No change to global-only gating of other cloud capabilities. No new permissions, network calls, or telemetry.

## Shipped-behaviour changes the owner must accept
1. Users who denied notifications or left "proactive" off start seeing a Home briefing (if briefing on).
2. On-device users start getting briefings, generated while the app is open (timing differs from midnight cloud).
3. Cloud warranty results ≥0.75 wait for a tap instead of appearing ACTIVE; expiry reminders cover confirmed warranties only (UNVERIFIED whether expiration worker skips `PENDING_REVIEW`).
4. `warrantyExtractionEnabled` default flip affects every user with no stored key (not those who explicitly toggled); some current cloud users stop getting extraction until they opt in.
5. Insights stop following the briefing toggle; existing briefing-on users may lose/keep insights per migration choice (Q2); cloud insights stop unless opted in.
6. Re-requesting a dismissed suggestion behaves differently (Q1).

## Existing functionality reused / overlapped
- Briefing: `A/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt` (worker-only caller today), `SyncProactiveBriefingWorkUseCase.kt`, `DeliverProactiveBriefingNotificationUseCase.kt`, `A/data/ai/worker/DailyBriefingWorker.kt`, `HomeViewModel.aiBriefing`.
- Worker gating: `A/domain/workers/PrivacyRuntimeWorkerPolicy.kt:58`, `WorkerExecutionGuard`, `WorkerGuardVerifier.kt:41` (per critique).
- Cloud gating/routing: `A/domain/privacy/EffectiveCloudAiPolicy.kt` (RP-15), `CloudAiPrivacyGate.kt`, `PrivacyCapability.kt`, `CloudPayloadRedactor.kt` (`CloudPayloadPurpose`), `DefaultAiCapabilityRouter.kt:256-344`, `AiPolicyImpl.kt:18-41`.
- Insight: `GenerateTransactionInsightUseCase.kt`, `TransactionInsightInputBuilder.kt`, `DashboardBriefingPromptFormatter.kt:111`.
- Suggestions: `SuggestCategoryFallbackUseCase.kt`, `SuggestReceiptExtractionUseCase.kt:74`, `JudgePendingReviewDuplicateUseCase.kt:64`, `AiArtifactDao.kt:56`, `ReviewViewModel`, `ReceiptScanViewModel`.
- Warranty: `A/data/repository/WarrantyTrackerRepository.kt`, `ui/screens/warranty` (PENDING_REVIEW confirm surface assumed, UNVERIFIED).
- Settings: `AiModels.kt:93-107`, `AiSettingsRepositoryImpl.kt:116-132`, `AiSettingsViewModel.kt`, `AiSettingsScreen.kt`.
- Docs/retention: `docs/features/FEATURES.md`, `A/data/privacy/DataRetentionWorker.kt`.

## Market patterns
Limited relevance for a defect bundle. Observed: Google GenAI docs restrict on-device inference to the foreground app with per-app quotas (https://developers.google.com/ml-kit/genai). Monarch's automatic weekly recap shows scheduled summaries are a recognised pattern (https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features); no decision changes.

## Privacy & permissions impact — net positive, medium review weight
- Nothing new leaves the device. Item 5 reduces cloud traffic and makes the audit purpose honest; items 4–5 move to explicit opt-in / confirm-by-tap.
- No new Android permissions; notification permission keeps gating posting only.
- Risk points: item 5 adds a dedicated cloud setting to a matrix RP-15 froze as global-only → must be an explicit, documented RP-15 amendment with fail-closed default (cloud off for insight). Insight payload with redaction off (exact amount + merchant) should be reviewed at the split — shrinking allowed, widening not.
- Diagnostics: replacing raw `e.message` (`AiSettingsViewModel.kt:335`) aligns with bounded reason codes.

## Risks
- Worker/guard tier (likely T3): `DailyBriefingWorker` gating, `PrivacyRuntimeWorkerPolicy` map + test, `WorkerGuardVerifier`, FG worker guards (impact-map must cite FG-IDs from `FINAL_CI_GUARD_ACCEPTANCE_GATE.md`). CLOUD_AI toggle must still cancel the cloud route; `data_retention` exemption semantics intact.
- AICore foreground-only: background Nano fails with `BACKGROUND_USE_BLOCKED`; Home-open generation adds latency and spends quota; briefing + insight share quota (`BUSY`) — both degrade to nothing/template, never cloud.
- Settings migration: two default sites per flag must stay in sync; DataStore absent-key semantics silently affect untouched users; new insight toggle needs a defined carry-over from `dashboardBriefingEnabled`.
- Artifacts: legacy insight artifacts stored under `DASHBOARD_BRIEFING` with `transaction:` keys — retention, TTL and Home readers must not confuse them after the split; cache-key/upsert on DISMISSED rows UNVERIFIED.
- Warranty: existing auto-accepted ACTIVE AI warranties (Q3); reminder/expiration workers may ignore pending items (UNVERIFIED).
- Language quality: Nano Greek quality unproven; template fallback is content, a non-goal here.

## Success criteria (observable, no content-leaking telemetry)
- Briefing on, notifications denied, proactive off → Home briefing appears that day; no notification posted.
- Briefing on, cloud off, Nano available → opening Home produces on-device briefing; no DASHBOARD_BRIEFING cloud audit entry. Nano unavailable → no briefing or labelled template, never cloud.
- Cloud off, or cloud on without insight-cloud opt-in → zero cloud calls on expense save. With opt-in → audit shows insight purpose, not DASHBOARD_BRIEFING.
- Background insight with Nano → no crash, no cloud fallback; bounded reason code for background block.
- After dismissing, reopening the item makes no model call for unchanged input; repeat requests follow agreed UX.
- No AI-extracted warranty reaches ACTIVE without a tap; fresh install has `warrantyExtractionEnabled=false`.
- No "AI-powered" claim in FEATURES.md for rule-based/statistical engines; no hard-coded user-facing English in `AiSettingsViewModel`; no raw exception text in UI; `DataRetentionWorker` header matches behaviour.
- Existing guard/worker tests pass after amendment; RP-15 matrix doc + test reflect the new insight setting.

## Open questions for owner (max 6)
1. **Dismissed suggestion, asked again with unchanged input:** (a) hide button, show "You dismissed this suggestion" + explicit "Try again"; (b) regenerate on tap as today but never auto-re-show; (c) show the previously dismissed result again without a new model call. Today ≈ (b) with a fresh call each time.
2. **Insight defaults:** new insight toggle on by default for new installs (on-device only)? Existing users inherit `dashboardBriefingEnabled` or start off?
3. **Existing AI warranties:** leave already auto-accepted ACTIVE ones, or move them back to "suggested"?
4. **Deterministic warranty auto-create (≥70%, regex, non-AI):** keep out of scope (recommended) or apply tap-to-confirm too?
5. **On-device briefing delivery:** accept "generated when you open the app", no morning notification for on-device users (c)? Or a non-AI template notification from the night worker (b)?
6. **Fold-in:** A7 in-app honest labels and A8 cloud-key copy ride with this bundle, or stay separate? Recommended: separate; only FEATURES.md + strings in scope here; A8 needs a fresh Gemini terms check.

## Domain questions for domain-modeler (flagged, not resolved)
1. Briefing generation: owner of foreground generation; dedupe vs worker for same `dashboard_home:<date>` artifact; race/idempotency when Home open and worker overlap.
2. Worker gating: cloud-route-only worker semantics; new meaning of `PrivacyRuntimeWorkerPolicy` CLOUD_AI → `ai_daily_briefing` (cancel only cloud route?).
3. Dismissal identity: key defining "same suggestion" (target + capability + sourceHash + promptVersion); survives TTL/retention purge?; does `force=true` bypass it?
4. Insight split: new `AiCapability`, `PrivacyCapability`, `CloudPayloadPurpose` values; RP-15 row with dedicated setting; artifact TTL/retention; legacy `DASHBOARD_BRIEFING` insight artifacts; confirm no Room migration.
5. Background insight: skip, defer to foreground, or template?
6. Warranty: single threshold policy after removing ACTIVE band; `PENDING_REVIEW` interaction with expiration-check/reminder workers, return-window creation (`WarrantyTrackerRepository.kt:400-403` uses extracted result even when pending), backup.
7. Settings migration: DataStore absent-key vs explicit values; single source of truth for the two default sites (`AiModels.kt`, `AiSettingsRepositoryImpl.kt`).

## Recommendation
**Pursue (post-1.0), item 3 reshaped.** Five items are verified defects/consent problems; none appears to need a schema change. Item 3 is lighter than claimed (no automatic re-show, only regeneration on request). Item 2 must be built around AICore's foreground-only rule, not by re-gating the midnight worker. Expected tier: T3 for items 1, 2, 4, 5 (workers, privacy gates, RP-15 amendment); T1 for item 6.

Sources: [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai) · [ML Kit GenAI Prompt API (Android)](https://developers.google.com/ml-kit/genai/prompt/android) · [Monarch AI features](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features)
