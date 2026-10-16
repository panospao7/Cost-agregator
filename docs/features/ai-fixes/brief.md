# Feature brief v2: ai-fixes — AI integration defect bundle (reshaped)

Author: feature-strategist (stage 1b, the single allowed reshape pass). Persisted by the orchestrator. Date 2026-10-16. Docs-only; ships post-1.0 (Release 1.0 frozen).
Inputs: `docs/features/ai-fixes/brief-v1.md` (base), `docs/features/ai-fixes/critique.md` (fact-check corrections binding), `workflows/active/ai-fixes-handoff.md` §Human decisions (Gate 1, binding).
`A` = `app/src/main/java/com/yourname/expensetracker`. ~27 bounded tool calls; not-checked items marked UNVERIFIED.

## Problem
Shipped AI features break their own rules: receipt OCR is uploaded to cloud to produce data the user never sees or confirms; a per-transaction model call runs under the daily briefing's toggle and cloud purpose; the cloud-route briefing depends on notification consent; docs and some settings messages misdescribe the app or leak raw exception text.

## Shape (owner Gate 1 = RESHAPE, "recommended")
- **Part A (T1, first):** docs and strings hygiene.
- **Part B (T3), in order:** B1 warranty → B2 insight → B3 notification decoupling (cloud route only).
- **Moved out:** on-device briefing → roadmap item `on-device-briefing`; dismissed-suggestion handling → deferred to A3.

## Verified facts this brief depends on
| Claim | Verdict | Evidence |
|---|---|---|
| FEATURES.md calls rule/statistics engines "AI-powered" | VERIFIED (direct imports only) | `docs/features/FEATURES.md:132` (ReceiptTransactionMatcher, ReceiptMatchingWorker), `:166` (SmartSavingsEngine, AutomatedSavingsRuleEngine), `:392,395,396` (BudgetForecastingEngine, BudgetRecommendationEngine, "AI-generated forecasts"), `:568,581` (AdvancedAnalyticsDashboard), `:901` (SmartBillNegotiationEngine). None of the 8 files imports AI types/providers. Transitive AI use UNVERIFIED; v1's `:911` not re-found (UNVERIFIED). |
| FEATURES.md warranty section overstates AI | NEW, VERIFIED | `:36,39,45` claim warranty periods come from "AI extraction"; in production cloud never creates a warranty (see B1). Wording depends on B1 outcome. |
| Hard-coded strings in `AiSettingsViewModel` | VERIFIED (v1) | `A/ui/screens/aisettings/AiSettingsViewModel.kt:110,113,153,163,173,227,237,254,263,277,283` |
| Raw `e.message` in AI/warranty UI | VERIFIED | `AiSettingsViewModel.kt:335`, `A/ui/screens/warranty/WarrantyTrackerViewModel.kt:195`. `AiSettingsScreen.kt:355,596`, `AssistantViewModel.kt:575` display `runtime.message` (boundedness UNVERIFIED — check in Part A). `AssistantViewModel.kt:538` reads `error.message` for classification (never displayed UNVERIFIED). |
| Raw `e.message` in non-AI screens | VERIFIED, out of scope | ~25 sites in Tax, Subscription, Investment, CashFlow, ReceiptMatching, Groups, ManualRecurring, Challenges, AdvancedAnalytics, Budget, AddExpense, Debug VMs → separate hygiene follow-up. |
| Stale retention comment | VERIFIED (v1) | `A/data/privacy/DataRetentionWorker.kt:2` vs `:120,133` |
| Production cloud warranty path | VERIFIED | Only caller of `WarrantyTrackerRepository.upsertReturnWindowForReceipt` (`A/data/repository/WarrantyTrackerRepository.kt:392-429`) is `AutoCreateWarrantyFromReceiptUseCase.persistReturnWindow` (`A/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt:380-382`); calls `extractWarrantyResult` (`:400`) sending `rawOcrText` + merchant + total + date + currency (`:457-465`). |
| **Every production call already passes a warranty** | NEW, VERIFIED | `persistReturnWindow` only called with non-null warranty: `AutoCreate…:100` (exists), `:171` (review flow), `:255` (just created), `:327` (draft inserted), `:369` (draft promoted). `fallbackWarranty ?: extractedWarranty` (`Repo:402`) **always** discards the cloud warranty; only `returnDays`/`returnConditions` used (`:539,562`). Cloud never consulted when local regex < 40% (`AutoCreate…:141-145`). |
| **Return windows have no UI and no reader** | NEW, VERIFIED | No `ReturnWindow`/`returnDays`/`returnConditions`/`ReturnStatus` match in `A/ui` (`PriceProtectionScreen.kt:549` uses merchant defaults from `PriceProtectionTracker.kt:103,236`). `getAllReturnWindows`/`getActiveReturnWindows`/`getReturnWindowsExpiringSoon` (`Repo:342-357`) have no external caller. Other uses: delete on reject (`Repo:251-258`), expiry reconciliation, `ReceiptLinkService`. Backup/export inclusion UNVERIFIED. |
| Return windows have no pending state | NEW, VERIFIED | `A/data/database/entity/ReturnWindow.kt:94-100`: RETURNABLE, EXPIRED, RETURNED, EXCHANGED, NON_RETURNABLE; default RETURNABLE (`:72`). |
| ≥0.75 auto-accept has no production caller | VERIFIED (critique) | `extractWarrantyFromReceipt` (`Repo:432`), `processReceiptForWarranty` (`:569`) tests only. `toWarrantyEntityOrNull` (`:476-523`) still runs via `:401`; its `<0.30` discard writes `AI_EXTRACTION_DISCARDED` lifecycle event (`:477-494`) — partly live. |
| Tests touching the dead path | VERIFIED (names partly) | `app/src/test/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepositoryTest.kt`: `:127` delegates to cloud service, `:171` calendar month addition, `:217` skips cloud when route not cloud, `:347` processReceiptForWarranty return-policy-only, `:641` cloudWarrantyConfidence_0_8_autoCreatesWithoutReview, plus calls at `:665,697,729,759,791` (threshold-band tests). |
| `warrantyExtractionEnabled` default on, two sites | VERIFIED | `A/domain/ai/model/AiModels.kt:107`, `A/data/repository/AiSettingsRepositoryImpl.kt:132`; read at `DefaultAiCapabilityRouter.kt:260`, `AiPolicyImpl.kt:22,41`; UI `AiSettingsScreen.kt:246`. |
| Local review drafts dropped when cloud denied | VERIFIED | `AutoCreate…:123-139`: 40–70% local confidence + `privacyGate.check(CLOUD_AI_WARRANTY_EXTRACTION)` blocks → `Failure` before `createReviewDraftWarranty` (`:134`). |
| LEGAL_PATHS drift on warranty auto-create | NEW, VERIFIED | `docs/architecture/LEGAL_PATHS.md:981-1000` names "WarrantyReviewDraft" (code: `PENDING_REVIEW`), says "Low/medium → draft" (code: <40% creates nothing), requires "PrivacyGate.check() before accessing receipt data" (`:992`) which the `:128` cloud check may be standing in for. |
| PENDING_REVIEW confirm UI exists; pending never expires/reminds | VERIFIED (critique) | `WarrantyTrackerViewModel.kt:181-199`, `WarrantyTrackerScreen.kt:191-200,304,582-597`; `A/data/database/dao/WarrantyDao.kt:16,19,25,64,67,95,111` filter `status='ACTIVE'`. In production, pending warranties are only local regex review drafts. |
| Insight reuses briefing service/toggle/capability | VERIFIED | `GenerateTransactionInsightUseCase.kt:43,52 (3 s),81,87,115`. Callers `ManualExpenseRepository.kt:241`; `NotificationProcessingPipeline.kt:1742` (fire-and-forget `asyncScope.launch`, gated on `aiEnabled` `:1741`; recommendations with nullable artifact `:1744-1749`). |
| Router cloud-first set | VERIFIED | `DefaultAiCapabilityRouter.kt:336-341` = DASHBOARD_BRIEFING, REVIEW_EXPLANATION, DEDUPE_JUDGE, WARRANTY_EXTRACTION; on-device set `:343+`; on-device-only precedents `:330-331` (REVIEW_PRIORITIZATION, SEMANTIC_DEDUPE). |
| Briefing worker gating | VERIFIED | `DailyBriefingWorker.kt:74-75`; `SyncProactiveBriefingWorkUseCase.kt:17-18`; `PrivacyRuntimeWorkerPolicy.kt:58`. Violates `AGENTS.md:165-167`. |
| Foreground signal exists | PARTLY | Only `A/startup/AppStartupCoordinator.kt` references `ProcessLifecycleOwner`; reuse as query UNVERIFIED. |

## Platform constraint (unchanged from v1)
ML Kit GenAI/AICore inference only while the app is top foreground; background → `BACKGROUND_USE_BLOCKED`; per-app `BUSY`/battery quotas (https://developers.google.com/ml-kit/genai). Drives B2; B3 is cloud-route only.

## Who is affected
- Cloud-on users: every receipt with a local warranty uploads OCR to Gemini, on every re-run, only to change an invisible return-window row written without a tap.
- Default cloud-off users with 40–70% local warranty confidence: silently no review draft.
- Users with pending warranties: "needs review" forever, never expire.
- Briefing-on users: insight follows the briefing toggle; with cloud on, each expense may send merchant + amount under the briefing purpose.
- Cloud-route briefing users who denied notifications or left proactive off: no Home briefing.
- Readers of FEATURES.md and AI settings/warranty screens: untrue "AI" claims, unlocalized messages, raw exception text.
- Not affected here: on-device-only users' briefing (absent until `on-device-briefing`); dismissed-suggestion behaviour.

## Desired behaviour (must-have)

### Part A — T1 hygiene
1. Relabel verified FEATURES.md entries as rule-based/statistical; drop "AI-powered"/"AI-generated". Warranty section (`:36,39,45`) updated to match B1 (may land with B1).
2. Localize hard-coded user-facing strings in `AiSettingsViewModel`.
3. Replace raw `e.message` at `AiSettingsViewModel.kt:335` and `WarrantyTrackerViewModel.kt:195` with bounded, localized, user-safe messages; confirm `runtime.message` surfaces are bounded.
4. Fix stale header at `DataRetentionWorker.kt:2`.

### Part B1 — warranty (T3)
1. No cloud warranty call when a warranty already exists for the receipt (exact trigger: owner Q1).
2. Cloud-derived return-window data (`returnDays`, `returnConditions`) is a tap-confirmed suggestion before saving; merchant-default (local, non-AI) return windows unchanged. Constraint: no confirm surface, no return-window UI, no pending `ReturnStatus`. See Q1.
3. Delete the unused ≥0.75 auto-accept path (`extractWarrantyFromReceipt`, `processReceiptForWarranty`, ACTIVE band `Repo:497-498`); production-reached logic (discard diagnostic, calendar-month end date, route-not-cloud skip) stays covered. **Stop-and-ask:** tests listed above deleted or retargeted — `:641` and likely `:665-791` band tests obsolete; `:127,171,217,347` retargeted to the production path, not deleted (FG-19).
4. `warrantyExtractionEnabled` default off at both sites; explicitly stored values kept.
5. Pending (`PENDING_REVIEW`) warranties auto-expire after end date; still no reminders.
6. Local review drafts created for 40–70% local confidence regardless of the cloud gate; receipt data access stays behind the correct PrivacyGate check (`LEGAL_PATHS.md:992`).

### Part B2 — transaction insight (T3)
1. Own AI capability, audit purpose and settings toggle; independent of `dashboardBriefingEnabled` and `HybridDashboardBriefingService` cloud-first ordering.
2. On-device (Nano) only; no cloud route; no RP-15 amendment; not in `CLOUD_FIRST_CAPABILITIES`. Unavailable/busy/quota/timeout/background → "no insight" + bounded reason code; never cloud.
3. Foreground short-circuit before any Nano call from background paths (at least `NotificationProcessingPipeline.kt:1742`); deterministic recommendations still save with null artifact.
4. Defaults: on for new installs; existing users inherit current `dashboardBriefingEnabled`.

### Part B3 — notification decoupling, cloud route only (T3)
1. Notification permission and "proactive" gate posting only (`AGENTS.md:165-167`).
2. Cloud-route briefing generated/scheduled whenever briefing is on; Home card appears regardless; notification posts only if both allow.
3. CLOUD_AI toggle still cancels the cloud route; on-device users unchanged (still none).

## Later increments (not this bundle)
`on-device-briefing` (roadmap); A3 learn-from-corrections (incl. dismissed re-request); insight cloud opt-in only as separately reviewed RP-15 amendment; Nano warranty route + unified thresholds (A6 remainder); return-window UI if wanted later; non-AI raw `e.message` hygiene (~12 VMs).

## Non-goals
- On-device briefing (no Home-open generation, templates, or on-device user changes).
- Dismissed suggestions / re-request UX (no change to `SuggestCategoryFallbackUseCase` caching or `force=true`).
- No new AI capabilities beyond the insight split; no new prompts, models, briefing content.
- No new cloud purposes, no insight cloud route, no RP-15 amendment, no payload widening.
- No change to the deterministic ≥70% regex auto-create threshold (only the `:128-131` cloud-gate misuse).
- No new permissions, network calls, telemetry.
- No Room schema change expected; if Q1(b) needs a new `ReturnStatus` or insight capability storage needs one → domain-modeler flags and work stops (FG-21).

## Shipped-behaviour changes accepted with this bundle
1. Cloud-route briefing users with notifications denied / proactive off start seeing a Home briefing.
2. Absent-key users stop cloud warranty extraction (visible cost ≈ zero: only invisible return-window enrichment).
3. Cloud-derived return-window data no longer saved silently (Q1 decides if cloud runs at all).
4. Default cloud-off users start getting "needs review" drafts for 40–70% receipts.
5. Unconfirmed warranties past end date become expired.
6. Insight stops using cloud entirely; cloud-route users lose cloud insight sentences; background captures get no insight; new installs get on-device insight by default; insight follows its own toggle.
7. Privacy audit stops showing per-transaction `DASHBOARD_BRIEFING` cloud entries.

## Existing functionality reused / overlapped
- Warranty: `A/data/repository/WarrantyTrackerRepository.kt` (`upsertReturnWindowForReceipt`, `extractWarrantyResult`, `toWarrantyEntityOrNull`, expiry reconciliation ~`:350-390`, `rejectAutoDetectedWarranty :251`); `A/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`; `WarrantyDao`, `ReturnWindowDao`, `WarrantyReminderDeliveryDao`; `ui/screens/warranty/WarrantyTrackerViewModel.kt`/`WarrantyTrackerScreen.kt`; `LEGAL_PATHS.md §Warranty Auto-Create`.
- Insight: `GenerateTransactionInsightUseCase.kt`, `TransactionInsightInputBuilder.kt`, `OnDeviceDashboardBriefingService.kt`, `DashboardFollowThroughEngine.kt:149,181,217`, `ManualExpenseRepository.kt:237-245`, `NotificationProcessingPipeline.kt:1734-1750`, `DefaultAiCapabilityRouter.kt` (`:330-331`), `AppStartupCoordinator.kt` (foreground signal, reuse UNVERIFIED).
- Briefing: `DailyBriefingWorker.kt`, `SyncProactiveBriefingWorkUseCase.kt`, `DeliverProactiveBriefingNotificationUseCase.kt`, `PrivacyRuntimeWorkerPolicy.kt:58`, `WorkerExecutionGuard`, `WorkerGuardVerifier`.
- Settings: `AiModels.kt:93-107`, `AiSettingsRepositoryImpl.kt:91-132`, `AiSettingsViewModel.kt`, `AiSettingsScreen.kt`.
- Docs: `docs/features/FEATURES.md`, `docs/architecture/LEGAL_PATHS.md`, `A/data/privacy/DataRetentionWorker.kt`.

## Market patterns
Limited relevance. Google restricts on-device GenAI inference to the foreground app with per-app quotas (https://developers.google.com/ml-kit/genai).

## Privacy & permissions impact — net positive, medium review weight
- Less leaves the device: B1 removes/limits OCR uploads producing unseen data; B2 removes the insight cloud path; B3 sends nothing new.
- No RP-15 change (`EffectiveCloudAiPolicy.kt:15-33` stays global-only).
- No new permissions; notification permission gates posting only (`AGENTS.md:165-167`).
- Diagnostics: raw `e.message` replaced with bounded messages; new insight/background-block outcomes use bounded reason codes.
- Watch point: the B1 local-draft fix must re-point, not drop, the PrivacyGate check on receipt data required by `LEGAL_PATHS.md:992`.

## Risks (FG-IDs from `FINAL_CI_GUARD_ACCEPTANCE_GATE.md`)
- FG-16 (privacy/cloud boundaries): warranty cloud trigger + default flip; insight out of cloud-first with no fall-through; cloud gate re-scoped at `AutoCreate…:128`; audit purpose honesty.
- FG-11 (worker guard): drop `requiresNotificationPermission` from `DailyBriefingWorker`; `SyncProactiveBriefingWorkUseCase` condition; `PrivacyRuntimeWorkerPolicy` + tests; `WorkerGuardVerifier`.
- FG-10 (cancellation): foreground short-circuit and timeout paths must rethrow `CancellationException`.
- FG-12 (UI/DAO ownership): `confirmWarranty` writes from VM via `updateWarranty` (legal-path status unchecked); any return-window confirm surface must follow legal paths.
- FG-13 (write barrier): `upsertReturnWindowForReceipt` checks it; new confirm/expire paths must too.
- FG-19 (critical tests): dead-path test changes = stop-and-ask.
- FG-21: only if schema/enum-storage change appears.
- FG-26 (doc traceability): FEATURES.md + LEGAL_PATHS updates.
- Settings migration: two default sites; absent-key semantics; new-install vs upgrade marker for insight default.
- Nano viability: 3 s timeout may beat warm-up (UNVERIFIED); background gets nothing; shared quota later.
- Legacy `transaction:` insight artifacts under `DASHBOARD_BRIEFING` need a defined end of life.
- Pending expiry must not trigger reminders or conflict with `rejectAutoDetectedWarranty` / reconciliation lifecycle events.

## Success criteria (observable, no content-leaking telemetry)
- Part A: no "AI-powered/AI-generated" for listed engines; warranty section matches production; no hard-coded user-facing English in `AiSettingsViewModel`; no raw exception text in AI settings/warranty UI; `DataRetentionWorker` header matches behaviour.
- B1: fresh install `warrantyExtractionEnabled=false`; re-processing a receipt with an existing warranty → zero cloud calls; no cloud-derived return-window value persisted without a tap (or none produced under Q1(a)); cloud off + 40–70% receipt → PENDING_REVIEW draft; PENDING_REVIEW past end date no longer pending, no reminder; ≥0.75 band code gone, retargeted tests pass.
- B2: expense save never produces an insight cloud call; no per-transaction `DASHBOARD_BRIEFING` audit entries; background capture → no Nano call + bounded reason; foreground manual save with Nano may produce insight ("often no insight" acceptable); insight toggle independent; new install = on, upgrade = previous briefing value.
- B3: cloud-route user, briefing on, notifications denied or proactive off → Home briefing that day, no notification; both allowed → notification as today; cloud off → no briefing work, as today; on-device-only unchanged.
- Existing guard/worker tests pass after amendment; RP-15 matrix + test unchanged.

## Owner questions (new, from the reshape)
1. **Cloud warranty extraction.** Every production call already has a warranty and return windows are invisible, so "no cloud call when a warranty exists" literally means cloud is never called in production, and tap-to-confirm would confirm data the user can't see.
   - **(a) Recommended:** retire the cloud call from the return-window path for now; return windows use local merchant defaults only (tap-to-confirm satisfied by construction); setting stays default-off; FEATURES.md says warranty AI is not active. Smallest, most privacy-positive. A visible return-window feature with confirm can come later.
   - **(b)** Cloud only on first processing (warranty just created, `AutoCreate…:255/327/369`), skip re-runs (`:100/:171`); hold cloud `returnDays`/`returnConditions` as a pending suggestion → needs new confirm surface + pending representation `ReturnStatus` lacks (possible schema/enum impact, FG-21).

## Domain questions for domain-modeler (flagged, not resolved)
1. B1 trigger: where "warranty exists → no cloud" lives (use case vs repository); fate of `toWarrantyEntityOrNull` + `AI_EXTRACTION_DISCARDED` if the cloud warranty object is never used.
2. B1 under Q1(b): pending return-window representation, enum storage/migration, confirm write path, reject, expiry of unconfirmed suggestions, backup/restore (UNVERIFIED).
3. Pending warranty expiry: target status (EXPIRED vs distinct), transition owner (expiry reconciliation in `WarrantyTrackerRepository`), lifecycle events, reminder exclusion, UI listing.
4. Local-draft fix: which PrivacyGate capability guards local receipt access per `LEGAL_PATHS.md:992`; fix LEGAL_PATHS drift ("WarrantyReviewDraft", "low → draft").
5. Dead-path deletion: retarget vs delete list (stop-and-ask); production coverage for calendar-month end date, discard threshold, route-not-cloud skip.
6. `confirmWarranty` write ownership (VM → `updateWarranty`) vs legal paths (FG-12).
7. Insight split: new `AiCapability`; whether a `PrivacyCapability`/`CloudPayloadPurpose` value is needed with no cloud route; on-device-only router entry; artifact TTL/retention; confirm no Room migration.
8. Insight settings migration: new key default true for new installs; upgrades copy `dashboardBriefingEnabled` once; new-install vs upgrade detection; two default sites in sync.
9. Foreground short-circuit for every caller incl. `ManualExpenseRepository:241` (background manual save possible? UNVERIFIED); foreground signal source; bounded reason code; 3 s timeout vs Nano.
10. Legacy `DASHBOARD_BRIEFING` `transaction:` artifacts: TTL, purge, or re-tag; no Home/briefing reader picks them up.
11. B3: scheduling condition so `SyncProactiveBriefingWorkUseCase` and `PrivacyRuntimeWorkerPolicy:58` don't loop schedule/cancel; location of post-time permission/proactive check; `WorkerGuardRequest` change + `WorkerGuardVerifier`/FG-11 tests; worker's cloud→on-device fallback untouched (belongs to `on-device-briefing`).
12. Warranty default flip: absent-key vs explicit-true; single source of truth for `AiModels.kt:107` / `AiSettingsRepositoryImpl.kt:132`.

## Recommendation
**Pursue (post-1.0) as reshaped.** All remaining items are verified defects or consent problems with no RP-15 change. B1 is smaller than it looked: production cloud extraction only feeds an invisible table, so Q1(a) retires an upload rather than building a confirm surface. Tiers: Part A T1; B1–B3 T3.

Sources: [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai) · [ML Kit GenAI Prompt API (Android)](https://developers.google.com/ml-kit/genai/prompt/android)
