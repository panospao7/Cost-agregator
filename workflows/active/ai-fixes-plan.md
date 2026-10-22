# Feature plan: ai-fixes

Author: feature-planner (stage 6, attempt 2; attempt 1 hit its turn limit with no output). Persisted by the orchestrator. Date 2026-10-16.
Status: PLAN APPROVED 2026-10-16 (Gate 3; header corrected 2026-10-21 by orchestrator, see handoff). Guard regeneration (B1a) approved by owner 2026-10-22 (build/release-lead-notes/owner-approvals-20261022.md). Implementation only after Release 1.0 accepted + `release-1.0-wt` merged.

- Risk tier: **T3 overall** (Part A T1 with mild T2 edge: CE rethrow in 2 VMs; B1, B2, B3, OD-2 each T3).
- Basis: `docs/features/ai-fixes/impact-map.md` (trusted), `gate2-decisions.md` (all DECIDED = Recommended), `domain-spec.md` §2/§4–§9, `ux-flows.md`.
- Schema: **NONE** (no Room entity/column/version/migration/snapshot; FG-21 + `migration-tests` not required). Only `WarrantyDao` `@Query` SQL + one DataStore `DataMigration`. Any slice needing schema → stop, return to human.
- Conventions: `A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`, `AT` = `app/src/androidTest/java/com/yourname/expensetracker`, `res` = `app/src/main/res/values{,-es,-el}/strings.xml`. AG = architecture-guardian, PSG = privacy-security-guardian, RMG = room-migration-guardian, RS = reviewer-strict.
- Validation only by validation-runner, serially: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <p> [-TestFilter "<f>"] [-Shard <s>] [-GuardId <g>]`

## Pre-flight check results (planning stage)
1. Shards (`config/validation/test-shards.json`): `architecture-contracts`, `data`, `domain-a-m`, `domain-n-z`, `runtime-ui`, `integration-golden`. Registered guards (`scripts/ci/guard_registry.py`): `db_access` (:598, ratchet; policies incl. `config/guards/db_ownership_policy.yml`; baseline `config/baselines/db_access_v2.json` must never grow); `db_artifact_sync` (:410; `migrate_db_policy_signatures.py --verify` vs signatures candidate/accounting, `GR-08-seeds.yml`, `db_ownership_policy.legacy.yml`). `config/db_access_allowlist.yml` is superseded (`guard_registry.py:604`) — do not edit.
2. `T/architecture/WorkerGuardStaticVerificationTest.kt`: no notification-permission assertions; lists class names (:49, :51) and "exactly 10 unique work names" (:158). Unchanged (no new worker).
3. UX-Q-4 resolved: `GetAiRuntimeStatusUseCase` builds `highestPriorityMessage` only from caller-passed capabilities (:30, :49-51); `AiSettingsViewModel.kt:92-101` passes 7, excluding `WARRANTY_EXTRACTION`. `DebugViewModel.kt:337` passes all (debug-only, OK). `AssistantViewModel.kt:522` → S0 D-3.

## Slice table
| ID | Title | Tier | Prod files | Depends on | Reviewers | Key FG |
|---|---|---|---|---|---|---|
| S0 | Pre-flight (no `app/` edits) | — | 0 | Release 1.0 accepted + `release-1.0-wt` merged | — | — |
| A | Hygiene: docs, bounded errors, CE rethrow | T1 | 5 + res | S0 | cheap reviewer | 10, 12, 16, 26 |
| B1a | Warranty core: retire cloud path, `confirmWarranty`, pending expiry, GR-08 | T3 | 5 (1 new) + policy | A | AG + PSG + RMG (advisory) + RS | 07, 08, 12, 13, 14, 16, 19, 23, 26 |
| B1b | Warranty UI via `WarrantyReviewState` | T3 | 2 + res | B1a | AG + RS | 12, 16, 19, 23, 26 |
| B1c | Single-source AI-settings defaults, hidden warranty row | T3 | 3 (+1 cond.) + res | B1b | PSG + AG + RS | 16, 19, 26 |
| B2a | `TRANSACTION_INSIGHT` capability (tables only, no caller) | T3 | 6 | B1c | AG + PSG + RS | 16, 19, 26 |
| B2b | Insight key + DataMigration + InstallProvenance | T3 | 2–4 (1–2 new) | B2a | PSG + AG + RS | 16, 19, 26 |
| B2c | AppForegroundState port + startup registration | T3 | 3–4 (2 new) | B2a (parallel with B2b only if DI file separate) | AG + RS | 16, 26 |
| B2d | On-device insight use case, callers, timeouts | T3 | 6 (1 new) | B2b, B2c | AG + PSG + RS | 10, 16, 19, 23, 26 |
| B2e | Insight settings row | T3 (UI) | 2 + res | B2d | cheap reviewer + PSG copy check | 16, 26 |
| B3a | Daily-briefing + warranty-expiry worker decoupling (OD-2), scheduling predicate, allowlist | T3 | 6 (1 new) | B1a, B2e | AG + PSG + RS | 06, 08, 10, 11, 16, 19, 26 |
| B3b | Proactive copy + notifications-blocked caption | T3 (UI) | 2 + res | B3a | cheap reviewer | 16, 26 |
| Z | Closeout docs + bundle acceptance gate | — | 0 | B3b | RS final (fresh session) | 26 |

Order: S0 → A → B1a → B1b → B1c → B2a → B2b/B2c → B2d → B2e → B3a → B3b → Z. Shared sequential files (never edited in parallel): `res` (A, B1b, B1c, B2e, B3b); `AiSettingsViewModel.kt`/`AiSettingsScreen.kt` (A, B1c, B2a, B2e, B3a, B3b); `SourceScanningArchitectureGuardTest.kt` (B1a, B1b, B2d, B3a); `CancellationSafetyArchitectureGuardTest.kt` (removals only).

## Slices

### S0 — Pre-flight (no production edits)
- Goal: right base; clear remaining UNVERIFIED before any `app/` edit; C* = 53824bcd not invalidated while Release 1.0 open.
- Steps: (1) confirm Release 1.0 accepted + `release-1.0-wt` merged into `bug-fixes`; branch `feature/ai-fixes`. (2) Record landing state of R24 (WarrantyTrackerViewModel CE/raw message/unguarded writes → remaining A/B1b deltas, G2-13), R21 HZ-05 F1 (briefing scheduling files), R23 part 1 (AiSettingsRepositoryImpl/AiModels/AiSettingsViewModel), loan-installments slices on shared hot files. (3) Re-verify line numbers for every write-set file; diff vs 53824bcd; record deltas in handoff. (4) Discovery:
  - D-1 map `CancellationSafetyArchitectureGuardTest.kt` KNOWN_VIOLATIONS :79/:92/:127/:151/:171 to files.
  - D-2 exact test file paths (Glob) for every test class below.
  - D-3 `AssistantViewModel.kt:522` capability list — if it contains `WARRANTY_EXTRACTION`, add file to B1c.
  - D-4 any legitimate `updateWarranty` use in `WarrantyTrackerViewModel` (decides guard (c) form).
  - D-5 is `lifecycle-process`/`ProcessLifecycleOwner` already a dependency (`AppStartupCoordinator.kt:701-702`)? If not → N3.
  - D-6 exact R7 Pattern E commands; generator ownership (see plan-gate).
  - D-7 doc anchors: `ARCHITECTURE.md` :133/:165/:1271/:1978, `LEGAL_PATHS.md:410`, `ReceiptSideEffectPlanner.kt:181-211`, `hilt-bindings-map.md` path.
  - D-8 JVM Room/Robolectric DAO harness? If not, DAO SQL covered by `connected-test-class` only (no new infra).
  - D-9 via `-Action Profiles`/`Plan`: multi-pattern `-TestFilter`? `connected-test-class` params? `compile` profile exists?
  - D-10 how `CODEBASE_INVENTORY.md` is regenerated.
- Baseline validation (serial): `targeted-unit-test -TestFilter "*SourceScanningArchitectureGuardTest*"` (shows whether expired 2026-09-30 broad-catch entry for `WarrantyExpirationWorker.kt` :545-589 is enforced); `"*CancellationSafetyArchitectureGuardTest*"`; `static-guards`; `registered-guard -GuardId db_access`; `registered-guard -GuardId db_artifact_sync`; `lint` (MissingTranslation baseline).
- Done when: base merged, D-1..D-10 answered, baselines recorded (green or pre-existing red with reason), any fired N-item escalated.

### A — Part A hygiene (T1)
- INV-1, 2, 3, 4 (two VMs). AE-1, 2, 4, 5, 6; AE-3 interim (retargeted to `confirmWarranty` in B1b).
- Source: `docs/features/FEATURES.md` relabel :132, :166, :392, :395, :396, :568, :581, :901, :911 (G2-11); warranty section :36/:39/:45 → local (regex) detection, "warranty AI is not active". `A/data/privacy/DataRetentionWorker.kt` header 1-2. `A/ui/screens/aisettings/AiSettingsViewModel.kt` literals :110, :113, :153, :163, :173, :227, :237, :254, :263, :277, :283, :335 → closed set of string resources (`UiText.StringResource`); catch ~:333 rethrow CE first, class-name-only log (INV-3, :334). `AiSettingsScreen.kt` :129, :137 (render UiText). `A/ui/screens/warranty/WarrantyTrackerViewModel.kt` confirm :181-199 (bounded error, CE rethrow; R24 residual only, G2-13). `WarrantyTrackerScreen.kt` SnackbarHost for `mutationError`. `res` ×3 EN+es+el (G2-7), no exception-carrying format args. (`AutoCreateWarrantyFromReceiptUseCase.kt` KDoc moves to B1a.)
- Tests: `AiSettingsViewModelTest` (AE-2, 4, 5); `WarrantyTrackerViewModelTest` (AE-3 interim); `CancellationSafetyArchitectureGuardTest` removals only (D-1).
- Docs: FEATURES.md. Legal path: none changed.
- Gates: cheap reviewer.
- Validation: `targeted-unit-test` `"*AiSettingsViewModelTest*"`, `"*WarrantyTrackerViewModelTest*"`, `"*DataRetentionWorkerTest*"`, `"*CancellationSafetyArchitectureGuardTest*"` (if edited); `lint`; `static-guards`.
- Done when: AE-1 grep on 9 entries = 0; no `e.message`/raw text in either VM; no new MissingTranslation; green.

### B1a — Warranty core (T3)
- INV-5, 6, 8, 9, 11, 12, 13, 14; INV-1 (KDoc); INV-3 (G2-14). AE-9..15, 17..19, 20 (repo), 21 (pin), 22, 23, 24; AE-16 domain part.
- Source:
  - `A/data/repository/WarrantyTrackerRepository.kt`: delete `extractWarrantyFromReceipt`, `extractWarrantyResult`, `toWarrantyEntityOrNull`, `parseWarrantyType`, `toCalendarMonthEndDate`, `processReceiptForWarranty`, two cloud confidence consts; remove ctor deps `cloudExtractionService`, `aiSettingsRepository`, `aiPolicy`, `aiCapabilityRouter`; drop `extractionResult` from `extractReturnWindow`; add `confirmWarranty(id)` (`writeBarrier.checkWritesAllowed` first, read by id, one transaction, `CONFIRMED` event, NotFound/AlreadyConfirmed); extend `reconcileExpiredItems` for pending; `ExpiryReconciliationResult.expiredPendingWarrantyCount`; batch event description counts only.
  - `A/data/database/dao/WarrantyDao.kt`: `markExpiredWarranties` → ACTIVE + PENDING_REVIEW; pending-count query (`@Query` only).
  - `A/data/database/entity/WarrantyLifecycleEvent.kt`: `CONFIRMED` const.
  - `A/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`: remove :128-132 cloud gate + `privacyGate` dep (OD-1(a)); 0.40-0.70 band → `createReviewDraftWarranty`; KDoc :41-53, :126-127, :133; G2-14 bounded logs at :110-115, :151.
  - NEW `WarrantyReviewState.kt` (domain, Segment 34; `of(warranty, now)`).
  - KEEP unchanged: `CloudWarrantyExtractionService`, `WarrantyExtractionModels`, `AiModule` provider, `AiCapability.WARRANTY_EXTRACTION` + tables, `PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION`, RP-15 row.
- Tests: `T/data/repository/WarrantyTrackerRepositoryTest.kt` DELETE :641, :673, :705, :735, :767 (G2-6); RETARGET :127 → AE-9 return-window, :171 → AE-24, :316 → AE-23, :347 → warranty=null merchant window; setup :68-92 mechanical; new confirm/reconcile tests AE-14, 15, 17-21. `AutoCreateWarrantyFromReceiptUseCaseTest` (AE-9..12; AE-24 production end-date; :217 retarget → AE-11). `ReceiptSideEffectPlannerWarrantyTest` (AE-13 + ctor). NEW `WarrantyReviewStateTest`. `WarrantyExpirationWorkerTest` (mechanical if result ctor breaks). `SourceScanningArchitectureGuardTest` **new guard (a)**: `CloudWarrantyExtractionService` referenced in `app/src/main` only from `di/AiModule.kt` (exempt own file + `WarrantyExtractionModels.kt`) (AE-22). `CancellationSafetyArchitectureGuardTest` removals only. `AT/data/database/dao/WarrantyDaoTest.kt`: widened expiry, pending count, ACTIVE-only reminders (INV-12).
- Guard/policy (G2-5 pre-approved): `config/guards/db_ownership_policy.yml` delete stale `toWarrantyEntityOrNull` row (~:3433), add `confirmWarranty(Long)` rows, `reconcileExpiredItems` rows only if `db_access` reports; regenerate per R7 Pattern E (`db_ownership_policy.signatures.accounting.json`, `.signatures.candidate.yml`, `docs/ci/db-findings/GR-08-seeds.yml`, `.legacy.yml` if touched). Never grow `config/baselines/db_access_v2.json`. `T/guard/DbGuardPolicyFixtureTest.kt` expected unchanged.
- Docs: `LEGAL_PATHS.md` Warranty Auto-Create :981-1000 rewrite (spec §5.6; :990 PENDING_REVIEW; :992/:998 OD-1(a)); add return-window/confirm/expire paths; FORBIDDEN lines (cloud/AI in warranty path, reminders for non-ACTIVE, reading persisted rawOcrText); Privacy/Cloud AI :383 note `CloudWarrantyExtractionService` has no production caller. `ENGINE_INTERACTION_MAP.md` PrivacyGate row (:183-198). `ARCHITECTURE.md` :51, :1827 (+ D-7). `DEPENDENCY_MAP.md` :591, :800. `hilt-bindings-map.md` :174. `CODEBASE_SEGMENTS.md` Segment 34. Optional `COMPLETE-BACKEND-MAP.md:1217`.
- Legal path preserved: ReceiptSideEffectPlanner (Policy A, ephemeral OCR) → AutoCreate → `WarrantyTrackerRepository` (sole writer) → WarrantyDao; confirm/expire only via repository behind write barrier; receipt rows via `ReceiptRepository`, never `rawOcrText`; reject/claim/delete unchanged.
- Gates: AG, PSG, RMG advisory, RS.
- Validation: `compile`; `targeted-unit-test` (serial unless D-9 allows lists) `"*WarrantyTrackerRepositoryTest*"`, `"*AutoCreateWarrantyFromReceiptUseCaseTest*"`, `"*ReceiptSideEffectPlannerWarrantyTest*"`, `"*WarrantyReviewStateTest*"`, `"*WarrantyExpirationWorkerTest*"`, `"*CloudWarrantyExtractionServiceTest*"`, `"*WarrantyExtractionModelsTest*"`, `"*PrivacyCapability*"`, `"*AiPolicyTest*"`, `"*DefaultAiCapabilityRouterTest*"`, `"*DbGuardPolicyFixtureTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`; `registered-guard -GuardId db_access`; `registered-guard -GuardId db_artifact_sync`; `connected-test-class` `com.yourname.expensetracker.data.database.dao.WarrantyDaoTest` (AVD wave5-api35); `static-guards`.
- Done when: AE-22 guard green; 5 deletions + 5 retargets done; both DB guards pass with no baseline growth; WarrantyDaoTest green on AVD.

### B1b — Warranty UI (T3)
- INV-10, INV-13 (VM), INV-2, INV-4. AE-3 (final), AE-16, AE-20 (VM bounded message). G2-8 (Expired chip includes EXPIRED_UNCONFIRMED; "N expired without confirmation" only when N > 0); G2-9 (Reject confirmation dialog, also review cards).
- Source: `WarrantyTrackerViewModel.kt` (counts/filters/badges only from `WarrantyReviewState`; confirm → `repository.confirmWarranty(id)`; bounded errors; CE rethrow); `WarrantyTrackerScreen.kt` (UX §4.1-4.4); `res` ×3.
- Tests: `WarrantyTrackerViewModelTest`; `SourceScanningArchitectureGuardTest` **new guard (c)**: `WarrantyTrackerViewModel.kt` has no `updateWarranty(` and no `.copy(` setting `status =`/`needsReview =` (copy-only form if D-4 finds a legit edit path).
- Docs: `LEGAL_PATHS.md` FORBIDDEN "ViewModel-built warranty status/needsReview changes".
- Legal path: VM → `confirmWarranty` / `rejectAutoDetectedWarranty` / `markWarrantyAsClaimed` only (FG-12).
- Gates: AG, RS. Validation: `targeted-unit-test` `"*WarrantyTrackerViewModelTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`; `lint`.
- Done when: no VM/screen review logic reads `needsReview`/`status` directly; guard (c) green.

### B1c — Single-source AI-settings defaults (T3)
- INV-22, INV-7. AE-7, 8, 38. UX §2.3 (hide warranty row + local-note caption); UX-Q-4 pin.
- Source: `A/domain/ai/model/AiModels.kt` (:87-108) `warrantyExtractionEnabled=false`, data class is the only default source; `A/data/repository/AiSettingsRepositoryImpl.kt` (:110-133) fallbacks from `AiSettings()`; `AiSettingsScreen.kt` :244-250; `res` ×3; conditional `AssistantViewModel.kt` (D-3).
- Tests: NEW `AiSettingsRepositoryImplTest` (AE-7, AE-8, AE-38 parity `emptyPreferences().toAiSettings() == AiSettings()`); `AiSettingsViewModelTest` pin runtime list excludes `WARRANTY_EXTRACTION`.
- Docs: `ARCHITECTURE.md` single-source note if defaults listed (D-7).
- Legal path: settings only via `AiSettingsRepository.update`; no migration rewrites the warranty flag (spec §4.5).
- Gates: PSG, AG, RS. Validation: `targeted-unit-test` `"*AiSettingsRepositoryImplTest*"`, `"*AiSettingsViewModelTest*"`; `lint`.
- Done when: parity test green; lands before B2b.

### B2a — TRANSACTION_INSIGHT capability (T3, no caller)
- INV-15 (tables), INV-16 (policy). AE-27.
- Source: `AiModels.kt` append enum last (never remove/reorder; `Converters.kt:85` unknown → DASHBOARD_BRIEFING); `DefaultAiCapabilityRouter.kt` (:255, :276, :291, :306, :321, :336-352: on-device-implemented, not cloud-first); `AiPolicyImpl.kt` (:17 `canUseCloudFor`=false, :36); `GetAiRuntimeStatusUseCase.kt` (:95); `AiSettingsScreen.kt` (:738); `ui/screens/debug/DebugScreen.kt` (:840-865); `res` ×3 only if :738 label uses resources.
- Tests: `DefaultAiCapabilityRouterTest` (AE-27: AUTO/CLOUD/ON_DEVICE × NOT_INSTALLED → DETERMINISTIC_FALLBACK); `AiPolicyTest`; `GetAiRuntimeStatusUseCaseTest`; Converters round-trip/fallback pin.
- Docs: `LEGAL_PATHS.md` Privacy/Cloud AI (:375-416) "on-device-only capabilities: TRANSACTION_INSIGHT".
- Legal path: router/policy tables single source; no PrivacyCapability/CloudPayloadPurpose/RP-15 change.
- Gates: AG, PSG, RS. Validation: `compile`; `targeted-unit-test` `"*DefaultAiCapabilityRouterTest*"`, `"*AiPolicyTest*"`, `"*GetAiRuntimeStatusUseCaseTest*"`, `"*Converters*"`, `"*EffectiveCloudAiPolicy*"`.
- Done when: all exhaustive `when`s compile; AE-27 green.

### B2b — Insight key + DataMigration + InstallProvenance (T3)
- INV-23, INV-22 (new field). AE-33..38 (extended).
- Source: `AiModels.kt` `transactionInsightEnabled=false`; `AiSettingsRepositoryImpl.kt` key `ai_transaction_insight_enabled`, `produceMigrations` on `preferencesDataStore(name="ai_settings")` (:24-27) with one never-throwing `DataMigration` per spec §4 (no startup coroutine), `update()` writes key (:79-103); NEW `InstallProvenance` port + impl (NEW_INSTALL/UPGRADE/UNKNOWN; own-package `PackageManager` install/update times only; no new permission, no `QUERY_ALL_PACKAGES`, no network; failure → UNKNOWN); migration extracted into testable internal class taking `InstallProvenance` (DI binding only if injected).
- Tests: `AiSettingsRepositoryImplTest` AE-33..37 + migration-before-first-edit; NEW `InstallProvenanceTest`.
- Docs: `CODEBASE_SEGMENTS.md` Segment 20; `hilt-bindings-map.md` if binding.
- Legal path: `produceMigrations` sole resolver; explicit values never overwritten.
- Gates: PSG, AG, RS. Validation: `targeted-unit-test` `"*AiSettingsRepositoryImplTest*"`, `"*InstallProvenance*"`.
- Done when: AE-33..38 green; `settings()` emits no exception when provenance throws.

### B2c — AppForegroundState (T3)
- Infrastructure for INV-17; default "not foreground" (fail-safe skip).
- Source: NEW `AppForegroundState` port (domain) + impl; `A/startup/AppStartupCoordinator.kt` register in `registerLifecycleObserver` (:701-702), ordering unchanged; DI binding in a NEW module file (allows parallel with B2b) or `di/AiModule.kt` if sequential.
- Tests: NEW `AppForegroundStateTest`; existing startup tests (D-2).
- Docs: `CODEBASE_SEGMENTS.md` Segments 12/20; `hilt-bindings-map.md`; `ARCHITECTURE.md` startup (D-7).
- Legal path: lifecycle observers only via `AppStartupCoordinator`.
- Gates: AG, RS. Validation: `targeted-unit-test` `"*AppForegroundState*"`, `"*Startup*"`.
- Done when: signal exists, unconsumed; startup tests green. N3 if `lifecycle-process` missing.

### B2d — On-device insight use case + callers (T3)
- INV-3, 4, 15, 16, 17, 18, 19, 20, 21. AE-25, 26, 28, 29 (GenAI errors → PROVIDER_ERROR, G2-12), 30, 31, 32, 39, 40.
- Source: `GenerateTransactionInsightUseCase.kt` (returns `TransactionInsightOutcome`; only `OnDeviceDashboardBriefingService`; drop Hybrid/PrivacySettings/AiArtifactRepository deps; gate `aiEnabled ∧ transactionInsightEnabled ∧ allowOnDeviceAi` then foreground short-circuit before any router/AICore call (§3.3); CLOUD route → `ROUTE_REJECTED`; rethrow CE; `recordInteraction("transaction_insight", reason.name)`; fix `Timber.w(e,…)` :67; TTL from AppConfig). NEW `TransactionInsightOutcome.kt` (Outcome + SkipReason, spec §1.9). `AppConfig.kt`: `TRANSACTION_INSIGHT_TIMEOUT_MS`=3_000, `TRANSACTION_INSIGHT_TTL_MS`=24h, `RECOMMENDATION_JOB_TIMEOUT_MS`=5_000. `ManualExpenseRepository.kt` (:52, remove private const :72, :233-255, CE rethrow in :251 catch). `NotificationProcessingPipeline.kt` (:153, :916, :1739-1755); consume `outcome.artifactOrNull`; deterministic recommendations always saved. `DefaultAiEnvironmentMonitor.kt` (:84 CE rethrow). `OnDeviceDashboardBriefingService.kt` unchanged.
- Tests: `GenerateTransactionInsightUseCaseTest` (retarget :71 → AE-26, :114 → AE-25; add AE-25..32, 39, 40); NEW `ManualExpenseRepository` recommendation test (AE-28..31); `NotificationProcessing*Test` ×3 (AE-28); `DefaultAiEnvironmentMonitorTest`; `DashboardFollowThroughEngineTest`; `AiArtifactRepositoryImplTest` (:227); `SourceScanningArchitectureGuardTest` **new guard (b)**: `GenerateTransactionInsightUseCase.kt` imports neither `HybridDashboardBriefingService` nor `CloudDashboardBriefingService`; `CancellationSafetyArchitectureGuardTest` removals only.
- Docs: `ENGINE_INTERACTION_MAP.md` HybridRouter (:162-173) briefing-only; `hilt-bindings-map.md`; `CODEBASE_SEGMENTS.md`; `LEGAL_PATHS.md` Privacy/Cloud AI: insight on-device, foreground-only, never persisted.
- Legal path: no write path (INV-20/21); recommendations via existing caller path; expense writes stay with TLC; nothing to `ai_artifacts`.
- Gates: AG, PSG, RS. Validation: `compile`; `targeted-unit-test` `"*GenerateTransactionInsightUseCaseTest*"`, `"*ManualExpenseRepository*"`, `"*NotificationProcessing*"`, `"*DefaultAiEnvironmentMonitorTest*"`, `"*DashboardFollowThroughEngineTest*"`, `"*AiArtifactRepositoryImplTest*"`, `"*AiSettingsRepositoryImplTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`.
- Done when: AE-39 = 0 cloud calls; guard (b) green; no duplicate `RECOMMENDATION_JOB_TIMEOUT_MS`.

### B2e — Insight settings row (T3, UI)
- UX §2.1/§2.2; AE-32 (insight setter doesn't touch briefing arming).
- Source: `AiSettingsViewModel.kt` `setTransactionInsightEnabled` (A's bounded errors + CE rethrow; optionally append `TRANSACTION_INSIGHT` last to runtime list only if §2.2 states need it); `AiSettingsScreen.kt` insight row, honest on-device copy; `res` ×3.
- Tests: `AiSettingsViewModelTest` (setter never invokes `SyncProactiveBriefingWorkUseCase`).
- Gates: cheap reviewer + PSG copy check. Validation: `targeted-unit-test` `"*AiSettingsViewModelTest*"`; `lint`.

### B3a — Worker notification decoupling + scheduling (T3; includes OD-2)
- INV-24..29, INV-4. AE-41, 42, 43, 44 (pin), 45, 46, 47, 48 (pin), 49 (:232 stays green), 50, 51.
- Source: `DailyBriefingWorker.kt` (`requiresNotificationPermission=false` :75; keep `requiredCapabilities=[CLOUD_AI_DAILY_BRIEFING]` + fresh-artifact short-circuit :90-98; inject `NotificationPermissionChecker`; literal `notificationPermissionChecker.areNotificationsEnabled()` immediately before delivery; `addNotificationsSent()` only on DELIVERED; record `NOTIFICATION_PERMISSION_DENIED`, run still SUCCESS). `A/service/warranty/WarrantyExpirationWorker.kt` (`requiresNotificationPermission=false` :76; inject checker; always `reconcileExpiredItems`; local reminder guard; if allowlisted broad catch remains: narrow, rethrow CE first, REMOVE expired 2026-09-30 entry, never extend — N1). `DeliverProactiveBriefingNotificationUseCase.kt` → `BriefingDeliveryOutcome` (existing checks kept). NEW `BriefingDeliveryOutcome.kt`. `SyncProactiveBriefingWorkUseCase.kt` (:16-18) arm iff `aiEnabled ∧ briefing ∧ (allowCloudAi ∨ proactive)`. `AiSettingsViewModel.kt` (:296) `setAllowCloudAi` syncs. UNCHANGED: `PrivacyRuntimeWorkerPolicy.kt:58`, `WorkerRegistry.kt:110`, `WorkerGuardVerifier.kt:41`, `WorkerSpec.kt:86`, `HomeViewModel.kt:285`, startup order.
- Tests: `DailyBriefingWorkerTest` (:757 stub DELIVERED; AE-41..43); `DeliverProactiveBriefingNotificationUseCaseTest` (6 + outcome); `SyncProactiveBriefingWorkUseCaseTest` (:43 → 16-row AE-47 table; AE-45, 46, 48); `WarrantyExpirationWorkerTest` (AE-51 + ctor fakes; setup allows NOTIFICATION_CAPTURE); `AiSettingsViewModelTest`; `SourceScanningArchitectureGuardTest`.
- Guard/policy (G2-3 pre-approved; FG-06): `SourceScanningArchitectureGuardTest.kt:408` += `"DailyBriefingWorker.kt"`, `"WarrantyExpirationWorker.kt"` — lands once, with both local checks; rule :395-423 not weakened. Remove expired broad-catch entry (:545-589) if catch fixed. KNOWN_VIOLATIONS removals only.
- Docs: `LEGAL_PATHS.md` Workers (:483-530) optional-notification pattern :494 covers both; `ARCHITECTURE.md` :1269, ~:1271.
- Legal path: workers via `WorkerExecutionGuard` + barriers; AI-side arming = Sync, privacy-side = `PrivacyRuntimeWorkerPolicy`, never call each other (INV-26); posting only via delivery use case.
- Gates: AG, PSG, RS. Validation: `targeted-unit-test` `"*DailyBriefingWorkerTest*"`, `"*DeliverProactiveBriefingNotificationUseCaseTest*"`, `"*SyncProactiveBriefingWorkUseCaseTest*"`, `"*WarrantyExpirationWorkerTest*"`, `"*WorkerExecutionGuardTest*"`, `"*WorkerContractTest*"`, `"*PrivacyRuntimeWorkerPolicyTest*"`, `"*PrivacySettingsRepositoryImplWorkerGatingTest*"`, `"*WorkerGuardStaticVerificationTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`, `"*AiSettingsViewModelTest*"`; `static-guards`.
- Done when: AE-50 via allowlist rule; AE-51 green; worker-guard tests unchanged + green.

### B3b — Proactive copy + notifications-blocked caption (T3, UI)
- UX §2.4; G2-10 / UX-Q-3 (state read only; no new permission, no deep link).
- Source: `AiSettingsViewModel.kt` reads `NotificationPermissionChecker` at existing refresh points; `AiSettingsScreen.kt` `ai_proactive_notifications_blocked` caption + proactive copy; `res` ×3.
- Tests: `AiSettingsViewModelTest`. Gates: cheap reviewer. Validation: `targeted-unit-test` `"*AiSettingsViewModelTest*"`; `lint`.

### Z — Closeout + bundle acceptance gate
- Write set: `CODEBASE_INVENTORY.md` regeneration (D-10); `workflows/active/ai-evolution-handoff.md` (follow-ups, deferred); `workflows/active/ai-fixes-handoff.md`. No `app/` edits.
- Bundle gate below; RS in a fresh session, separate from discovery.

## Guard/policy edit assignment
| Edit | Slice |
|---|---|
| New guard (a): cloud warranty service referenced only from DI | B1a |
| GR-08 `db_ownership_policy.yml` rows + R7 Pattern E regeneration | B1a only (serialize with loan-installments regeneration) |
| New guard (c): VM does not build status | B1b |
| New guard (b): insight imports | B2d |
| `SourceScanning:408` allowlist (both workers) + expired broad-catch removal | B3a |
| CancellationSafety KNOWN_VIOLATIONS | removals only, by the slice fixing the file (D-1) |
New guards are `@Test`s in the existing `SourceScanningArchitectureGuardTest.kt`.

## Owner plan-gate checklist
- [ ] Release 1.0 accepted + `release-1.0-wt` merged; S0 before any `app/` edit.
- [ ] Approve sub-slicing (B1a/b/c, B2a–e, B3a/b) and order; OD-2 rides in B3a.
- [ ] Confirm spec §8.1: cloud discard diagnostic (:705) unreachable after Q1(a) → deleting it loses no production coverage.
- [ ] Who runs the R7 Pattern E signature **generator** (writes tracked artifacts): implementer or validation-runner? (`--verify` via `registered-guard -GuardId db_artifact_sync` either way.)
- [ ] New guards in `SourceScanningArchitectureGuardTest.kt`; guard (c) narrow form.
- [ ] AE-3 split: interim in A, final in B1b.
- [ ] Expired `WarrantyExpirationWorker` broad-catch entry removed after the fix, never extended.
- [ ] Non-blocking: owner measures Nano warm-up vs 3 s timeout on device (G2-12).

## New stop-and-ask items (beyond Gate 2)
- N1: S0 baseline shows the expired broad-catch entry enforced and failing, or B3a can't remove that catch (extension = guard weakening).
- N2: any `db_access` baseline growth or signature drift outside `WarrantyTrackerRepository` rows.
- N3: new Gradle dependency needed (e.g. `lifecycle-process`).
- N4: release-batch drift (R21, R23, R24) contradicts INV-2/13 or INV-24..27.
- N5: lint clearable only by baseline refresh.
- N6: any slice needs a Room schema change.

## Risk register
| # | Risk | Mitigation / slice |
|---|---|---|
| R1 | Base drift from R24/R21/R23 | S0 diff + G2-13; N4 |
| R2 | Hot files shared with loan-installments (SourceScanning guard, strings, LEGAL_PATHS, db policy signatures, worker count :158) | Serialize; GR-08 regeneration only in B1a, never interleaved with loan's regeneration or Room v152 slice |
| R3 | Expired 2026-09-30 broad-catch entry | S0 baseline; B3a removes; N1 |
| R4 | DataMigration throw → `settings()` fails → AI off | Never-throw; AE-37 (B2b) |
| R5 | `update()` writes all keys, materializing fallback before migration | `produceMigrations` before first edit; dedicated test (B2b) |
| R6 | Provenance misclassified (update before first launch) | Fail-safe off (spec §4.8); accepted |
| R7 | Converter fallback silently remaps enum names | Append last; never remove `WARRANTY_EXTRACTION`; Converters pin (B2a) |
| R8 | Exhaustive `when` sites break compile incl. DebugScreen | All in B2a; `compile` |
| R9 | Foreground false negatives after cold start | Default not-foreground → skip (fail-safe) |
| R10 | Deterministic recommendations starve | 5 s shared job timeout; AE-30 (B2d) |
| R11 | `db_access` ratchet finds new `confirmWarranty` write | Policy rows, never baseline growth; N2 |
| R12 | Missing translations → lint errors | es/el in same slice as key |
| R13 | Decoupled workers post without permission | Literal local check enforced by :416-423 rule; `AndroidNotificationService` backstop |
| R14 | `RestoreMaintenanceMode` re-arms briefing without Sync | Bounded to one idle run (INV-26); accepted |
| R15 | AE-51 depends on NOTIFICATION_CAPTURE gating (G2-15 out of scope) | Test setup allows it; follow-up list |
| R16 | Insight input unredacted on device | Guard (b) + AE-39 + PSG (B2d) |
| R17 | FG-26 doc guard requires named symbols to exist | Docs land with symbols |

## Final bundle acceptance gate (validation-runner, serial; poll run ID; stale/missing ≠ PASS)
1. `unit-test-shard -Shard architecture-contracts`
2. `unit-test-shard -Shard data`
3. `unit-test-shard -Shard domain-a-m`
4. `unit-test-shard -Shard domain-n-z`
5. `unit-test-shard -Shard runtime-ui`
6. `unit-test-shard -Shard integration-golden`
7. `registered-guard -GuardId db_access`, then `registered-guard -GuardId db_artifact_sync`
8. `static-guards`
9. `lint`
10. `connected-test-class` `com.yourname.expensetracker.data.database.dao.WarrantyDaoTest` on AVD `wave5-api35`
11. `app-check`
12. `unit-tests` (last)
`migration-tests` not required. Then fresh-session reviewer-strict final acceptance. The planner does not declare acceptance.

## Deferred to later increments
A6 (Nano warranty route; deleting `CloudWarrantyExtractionService`/`WarrantyExtractionModels`/provider); G2-15 list; backfill of older untranslated `ai_*`/`warranty_*` keys; localizing `runtime.message`; typed GenAI error carrier + insight timeout tuning; "7 AI services" vs "8 providers" doc inconsistency.

## UNVERIFIED
- Resolved: shard names; guard ids `db_access`/`db_artifact_sync`; `db_access_allowlist.yml` superseded; WorkerGuardStaticVerificationTest has no permission-flag assertions; UX-Q-4.
- Remaining (S0): D-1..D-10; broad-catch expiry enforcement; historic `transaction:` artifacts (spec §6.7; no action).

## Orchestrator note (not planner output)
R7 precedent (`release-1.0-assurance-handoff.md:203-208`): regeneration ran LAST on a tree where only that change was uncommitted, via the sanctioned GR-14 Pattern E route (edit `GR-08-seeds.yml` + `db_ownership_policy.yml`, scratch preview to `build/`, `migrate_db_policy_signatures.py --generate --seed-rows ...`, then `--verify`; promotion.json untouched; every sourceMutations change accounted for, else stop), after architecture-guardian supplied the procedure. R7 also needed a pin update in `scripts/test_migrate_db_policy_seed_rows.py` (closed move set) — B1a may need the same; treat as part of G2-5 only if limited to the WarrantyTrackerRepository rows, else N2.


---

# ADDENDUM — Part C (assistant Track 0, C-1..C-6)

Author: feature-planner (read-only). Persisted by the orchestrator. Date 2026-10-16. Status: **APPROVED 2026-10-16** (owner: "Recommended approved"; all §9 checklist items as recommended — C3 LEGAL_PATHS one-liner KEPT).
Basis: `docs/features/ai-fixes/impact-map-part-c.md` (trusted), `gate2-decisions-part-c.md` (PC-1..PC-15 DECIDED = Recommended), `domain-spec-part-c.md` (INV-C1..C18, AE-C1..C30).
Tier T3 overall (C1, C2, C5 T3; C3, C4 T2). Schema none (no Room/DAO/entity/expense writes; no `migration-tests`). One new read-only legal path: clarification completion → `ExecuteFinancialQueryUseCase`. Line numbers are pre-Part-A; S0 and each slice re-read them. Part C guards are named **(Ca), (Cb), (Cc)** (distinct from Part B (a)/(b)/(c)).

## 1. Amended global order and sequential files
**Order:** S0 → A → **C1 → C2 → C3 → C4 → C5 → CX** → B1a → B1b → B1c → B2a → B2b/B2c → B2d → B2e → B3a → B3b → Z. C1..C5 strictly serial (C5 logically after C2 but runs after C4: same use cases + `res`). CX = Part C exit checkpoint (validation only). B1a now depends on CX (ordering only). Global feature order unchanged: Release 1.0 → ai-fixes (A, C, B) → money-correctness 1a → assistant-follow-ups inc 1 → nl-quick-add → assistant-follow-ups inc 1b.
**Shared files (never parallel):** `res`: A, C2, C4, C5, B1b, B1c, B2e, B3b · `LEGAL_PATHS.md`: C1, C3, C4, B1a, B1b, B2a, B2d, B3a (Privacy/Cloud AI block :375-416: C1, B1a, B2a, B2d) · `SourceScanningArchitectureGuardTest.kt`: C1, B1a, B1b, B2d, B3a · `CancellationSafetyArchitectureGuardTest.kt`: removals only by the fixing slice (candidates A, C1, C2, C4 (not expected), B1a, B1b, B2d, B3a; never add — FG-07, N12) · `AiSettingsViewModel/Screen`: list unchanged · `A/ui/screens/assistant/AssistantViewModel.kt`: C1, C2, C4, then B1c only if D-3 still requires (post-C4 lines) · `A/domain/config/AppConfig.kt`: C1, B2d.
**Part C internal hot files:** `FinancialQueryModels.kt` C1-C4; `OnDeviceQueryInterpretationService.kt` C1-C3; `InterpretFinancialQueryUseCase.kt` C2-C5; `ExecuteFinancialQueryUseCase.kt` C2-C5; `AssistantViewModelTest.kt` C1, C2, C4; `ExecuteFinancialQueryUseCaseTest.kt` C2-C5 (then money-correctness 1a). Docs: `CODEBASE_SEGMENTS.md` C1-C4, B1a, B2b, B2c, B2d; `DEPENDENCY_MAP.md` C1, C4, B1a; `VIEWMODEL_INJECTION_MAP.md` C1, C4.

## 2. Slice rows (insert after A; amend B1a)
| ID | Title | Tier | Prod files | Depends on | Reviewers | Key FG |
|---|---|---|---|---|---|---|
| C1 | Prior-USER-only prompt history + effective redaction source (C-1, C-6) | T3 | 5 + docs | A | PSG + AG + RS | 07, 10, 16, 19, 23, 26 |
| C2 | Typed bounded unsupported reasons + log hygiene (C-2) | T3 | 10 (2 new) + res | C1 | PSG + AG + RS | 10, 16, 19, 20, 26 |
| C3 | Period resolver, granularity, calendar previous period (C-3, OD-C5) | T2 | 5 (1 new) + docs | C2 | cheap reviewer | 17, 19, 26 |
| C4 | Clarification completion, live pending state, disabled stale options (C-4) | T2 | 7 (1 new) + res + docs | C3 | RS | 10, 16, 19, 20, 26 |
| C5 | MIN executor (C-5) | T3 | 2 + res + docs | C4 (logically C2) | RS | 17, 19, 20, 26 |
| CX | Part C exit checkpoint (validation only) | — | 0 | C5 | — | — |
| B1a | (unchanged except) | | | **CX** (was A) | | |
Guard/policy edits: (Ca) C1 source scan (AE-C30): `AssistantViewModel.kt` has no `redactBeforeCloud`; `FinancialQueryInterpretationInputBuilder.kt` has no `privacySettingsRepository`/`aiPolicy.shouldRedact` · (Cb) C1 source scan: in `app/src/main` only `FinancialQueryInterpretationInputBuilder.kt` constructs `FinancialQueryInterpretationInput(` (data class declaration exempt) · (Cc) C1 behavioural test in `T/data/ai/provider/CloudQueryInterpretationServiceTest.kt`: cloud request body has no `ASSISTANT:` line (AE-C1 cloud half) · Engine5 allowlist: **no change** (PC-8) · `scripts/allowlists/pii_logging_allowlist.yml`: **no change** · CancellationSafety KNOWN_VIOLATIONS for `OnDeviceQueryInterpretationService.kt` (:81), `CloudQueryInterpretationService.kt` (:134), `AssistantViewModel.kt` (:152): removal only if no broad catch remains (not expected).

## 3. Per-slice detail
Validation: validation-runner only, serial (`-Action Start -Profile <p> [-TestFilter] [-Shard] [-GuardId]`); one class per run unless D-9 allows lists; `compile` first only if D-9 confirms the profile.

### C1 — Prior-USER-only history + effective redaction source (T3)
- INV-C1, C2, C3, C4, C18; AE-C1..C6, C29, C30; PC-1 (both routes), PC-2 (K = 4), PC-10 (scoped to assistant).
- Source (5): `FinancialQueryModels.kt:118` `conversationHistory: List<AiChatMessage>` → `priorUserQueries: List<String>`; `AppConfig.kt:223` add `MAX_QUERY_HISTORY_USER_TURNS_FOR_MODEL = 4`, delete `MAX_QUERY_HISTORY_TURNS_FOR_MODEL`; `FinancialQueryInterpretationInputBuilder.kt` ctor `PrivacySettingsRepository` → `EffectiveCloudAiPolicyResolver` (keep `AiPolicy` for `canUseCloudFor`), `shouldRedact = aiPolicy.canUseCloudFor(settings, QUERY_INTERPRETATION) && effectiveCloudAiPolicyResolver.resolve().redactBeforeCloud`, remove OR at :29-32, history = USER rows only, last 4, oldest first, `sanitizeQueryContext` then `sanitizeFreeText`, drop blanks; `OnDeviceQueryInterpretationService.kt:76-107` renders `- USER: <text>`, `Conversation history:`/`none`, drops its own `takeLast(8)` (shared with cloud via `CloudQueryInterpretationService.kt:78`; cloud file NOT edited; name + `prepareText` flow unchanged — cloud_payload proof :274); `AssistantViewModel.kt:235-249` snapshot **before** `persistUserTurn`, unfiltered, delete `takeLast(10)` and the redaction branch (:239-246), remove `privacySettingsRepository` ctor param (:105).
- Tests (PC-7 approved): `FinancialQueryInterpretationInputBuilderTest.kt` — **retarget** `build trims truncates and enriches interpretation input` (:54-97) → AE-C3; **retarget** `build redacts when privacy requires it even though ai redaction is off` (:164-190) → resolver fixture (AE-C29; assert resolver invoked, `PrivacySettingsRepository` not consulted for redaction); mechanical `build creates reversible alias maps when redaction is enabled` (:111-162 → `.priorUserQueries.single()` at :160-161) + ctor in every test; new builder halves of AE-C1, AE-C4. `AssistantViewModelTest.kt` — **DELETE** `submitQuery strips history payloadJson when privacy requires redaction even though ai redaction is off` (:484-544) per PC-7 (VM branch removed; coverage in builder AE-C1, AE-C4; cite PC-7 in handoff); mechanical ctor sites (:96, :115, :141, :179, :204, :457, :507, :564) + `setUp`; `clarification reply keeps conversation history when history enabled` (:546-616) stays green, argument shape only (else N14); new AE-C1 VM half (snapshot before persist, unfiltered, no `takeLast`), AE-C5 (history off → empty), AE-C6 (one USER + one ASSISTANT row persisted, same format). Provider: `OnDeviceQueryInterpretationServiceTest.kt` AE-C2 (only `- USER:` lines; no ASSISTANT/SYSTEM/`payloadJson`), AE-C5 prompt half; `CloudQueryInterpretationServiceTest.kt` **new (Cc)**; `CloudQueryPreparedPayloadTest`, `CloudProviderTransportPayloadTest`, `HybridServiceDelegationTest`, `HybridRouterIntegrationTest` mechanical rename where they construct the input (D-19); `CloudAuditProviderProvenanceTest`/`CloudPayloadPolicyTest` mechanical only if D-13 shows a fixture pin (privacy assertion → N9). Guards: `SourceScanningArchitectureGuardTest.kt` **new (Ca), (Cb)**; CancellationSafety no additions.
- FG-16 (`cloud_payload`, `pii_logging`), FG-10, FG-23, FG-07.
- Docs: `LEGAL_PATHS.md` Privacy/Cloud AI FORBIDDEN (:406-412): "❌ ASSISTANT/SYSTEM turns or app-computed answers in any query-interpretation prompt (model history = prior USER queries, shaped only by `FinancialQueryInterpretationInputBuilder`)" and "❌ Assistant query interpretation reading `PrivacySettings.redactBeforeCloud` directly or OR-ing redaction flags outside `EffectiveCloudAiPolicyResolver`" (assistant-scoped, PC-10); **new section "Assistant / Financial Query"**: `AssistantViewModel.submitQuery` → `FinancialQueryInterpretationInputBuilder` (sole input constructor) → `InterpretFinancialQueryUseCase` → `HybridRouter`/providers (cloud via `CloudAiPrivacyGate` → `DefaultCloudPayloadPolicy.prepareText` → audit) → `ExecuteFinancialQueryUseCase` (read-only), plus mirroring FORBIDDEN lines; `DEPENDENCY_MAP.md:1283`; `VIEWMODEL_INJECTION_MAP.md:86`; `CODEBASE_SEGMENTS.md` Segment 20; optional `COMPLETE-BACKEND-MAP.md:122`; `hilt-bindings-map.md` only if it lists ctor deps (D-7); remove dangling refs to old names (D-20).
- Gates: PSG + AG + RS. Validation: `compile`; `targeted-unit-test` `"*FinancialQueryInterpretationInputBuilderTest*"`, `"*AssistantViewModelTest*"`, `"*OnDeviceQueryInterpretationServiceTest*"`, `"*CloudQueryInterpretationServiceTest*"`, `"*CloudQueryPreparedPayloadTest*"`, `"*CloudProviderTransportPayloadTest*"`, `"*CloudAuditProviderProvenanceTest*"`, `"*CloudPayloadPolicyTest*"`, `"*HybridServiceDelegationTest*"`, `"*HybridRouterIntegrationTest*"`, `"*SourceScanningArchitectureGuardTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`; `registered-guard -GuardId cloud_payload`; `-GuardId pii_logging`; `static-guards`.
- Done when: AE-C1..C6, C29, C30 green; (Ca)(Cb)(Cc) green; cloud_payload proof passes with no edit to `scripts/guardrails/test_cloud_payload_proof.py`; old constant unreferenced; deletion recorded with PC-7.

### C2 — Typed bounded unsupported reasons + log hygiene (T3)
- INV-C5, C6, C7 (reuses INV-3, INV-4); AE-C7..C10; PC-11 (amount logs, both places); PC-13 (EN + es + el).
- Source (10, 2 new) + `res` ×3: **NEW** `A/domain/ai/model/AssistantUnsupportedReason.kt` (closed enum, 7 codes, spec §1.3); `FinancialQueryModels.kt:131-133, :171-173` `reason: AssistantUnsupportedReason` + optional `diagnostic: DiagnosticReasonCode?` (no new values); `InterpretFinancialQueryUseCase.kt` :36-38 INTERPRETATION_DISABLED, :47-51 INTERPRETATION_FAILED (code + exception class, no throwable), :259 METRIC_NOT_SUPPORTED, :315-329 `validateAmountBound` logs no amounts; `OnDeviceQueryInterpretationService.kt` :52-64, :119-127 bounded logs (:58, :63, :125), CE rethrow in `parseResponse` catch (:124), discard model `unsupportedReason` (→ MODEL_DECLINED) and remove it from schema line :98, :338-339, `validateAmountBound` :316-325 logs no amounts; `CloudQueryInterpretationService.kt:260-262` `unsupported(code)` → typed reason + `diagnostic`, 8 call sites keep codes as `diagnostic` (no rename, no `prepareText` change); `NoOpQueryInterpretationService.kt:13` PROVIDER_UNAVAILABLE; `ExecuteFinancialQueryUseCase.kt:59, :276, :309` METRIC_NOT_SUPPORTED, NO_MATCHING_TRANSACTIONS; **NEW** `A/ui/components/ai/AssistantUnsupportedReasonText.kt` (single reason → `@StringRes` mapping); `AssistantResultCard.kt:113-115` renders `assistant_unsupported` + mapped message; `AssistantViewModel.kt:471, :513-516` `toDisplayText` localized, payload `{"type":"unsupported","reason":"<CODE_NAME>"}`; `res` ×3 ~7 messages.
- Tests (PC-7 mechanical, FG-19): `CloudQueryInterpretationServiceTest.kt:104, 124, 137, 393, 410, 474, 494, 530`; `CloudQueryPreparedPayloadTest.kt:117, 146`; `HybridServiceDelegationTest.kt:50, 103, 150, 197`; `HybridRouterIntegrationTest.kt:137-139`; `InterpretFinancialQueryUseCaseTest.kt:101, 125`; `AssistantViewModelTest.kt:301, 349`; `OnDeviceQueryInterpretationServiceTest.kt:89-92` add MODEL_DECLINED (AE-C8); English-literal assertions from D-19 → typed codes. New: AE-C7 (every producer emits an enum, no free text), AE-C8 (no "Lidl"/"4242"), AE-C9 (render + persisted text/payload), AE-C10 (class-name-only log; CE propagates from `parseResponse`); `validateAmountBound` log assertions in both places; **NEW** `T/ui/components/ai/AssistantUnsupportedReasonTextTest.kt` (exhaustive mapping). CancellationSafety no additions.
- FG-16, FG-10, FG-19, FG-20 (no baseline refresh, N5). Docs: `CODEBASE_SEGMENTS.md` Segment 20. Gates: PSG + AG + RS.
- Validation: `compile`; C1 filter list + `"*InterpretFinancialQueryUseCaseTest*"`, `"*ExecuteFinancialQueryUseCaseTest*"`, `"*NoOpQueryInterpretationService*"`, `"*AssistantUnsupportedReason*"`; `lint`; `registered-guard` `cloud_payload`, `pii_logging`, `cancellation`; `static-guards`.
- Done when: no production `Unsupported` takes a String; the 4 log sites log no throwable; no amount logged in either `validateAmountBound`; no new MissingTranslation; AE-C7..C10 green.

### C3 — Period resolver, granularity, calendar previous period (T2)
- INV-C8..C12; AE-C11..C19; PC-5, PC-6, PC-8.
- Source (5, 1 new): **NEW** `A/domain/ai/usecase/AssistantPeriodResolver.kt` (`object`: `resolveText`, `resolveKeyword`, `resolveChoice`, `previousEquivalent`, single comparison-cue function; takes `nowMs` and `zone: ZoneId = ZoneId.systemDefault()`; returns a plain `Long` start/end or its own type, **never imports `domain.model.PeriodRange`**; no `*.now()`; `resolveChoice` consumed in C4); `FinancialQueryModels.kt:74` `ExpenseQueryFilters.periodGranularity` (default null) + `PeriodGranularity`; `InterpretFinancialQueryUseCase.kt` `resolvePeriod` (:297-308), comparison cues :128, :227 → resolver, bare-period branches :176-207, `containsRichIntentCue` drops `"previous"` (:373), enrich sets granularity, allowlisted callers wrap into `PeriodRange`; `OnDeviceQueryInterpretationService.kt:256-306` keyword parser → resolver, schema line :98 + `LAST_WEEK`, `LAST_QUARTER`, `LAST_YEAR`; `ExecuteFinancialQueryUseCase.kt:496-499` delete private `previousEquivalentPeriod`, call site :345 → `AssistantPeriodResolver.previousEquivalent`.
- Tests: **NEW** `T/domain/ai/usecase/AssistantPeriodResolverTest.kt` (Europe/Athens; AE-C11..C16, C19; AE-C18 DST — Oct 2026 = 745 h, previous = [09-01 00:00 EEST, 10-01 00:00 EEST); month-end clamp AE-C16; INV-C12 join: FULL `prevEnd == s`, always `prevEnd ≤ s`); `ExecuteFinancialQueryUseCaseTest` comparison AE-C12, AE-C18, and `invoke returns previous period supporting text for comparison total` (:90-131) **UNCHANGED** (AE-C17); `InterpretFinancialQueryUseCaseTest` AE-C13, C14, C19 (:156/:186 add `periodGranularity` only if D-17 shows whole-filter compare); `OnDeviceQueryInterpretationServiceTest` `LAST_*` (schema pin only if it exists); `TimePeriodUtils*Test` regression, unedited.
- FG-17 `time_boundaries` (G-TIME-01 zero); Engine5 E5-GUARD-001 green with no allowlist (N7); `MoneyBoundaryGuardTest` untouched; FG-19. Docs: Segment 20 (resolver = only assistant period lexicon); optional LEGAL_PATHS one-liner "Assistant period ranges and comparison cue only via `AssistantPeriodResolver`" (owner may strike). Gates: cheap reviewer + DST tests.
- Validation: `"*AssistantPeriodResolverTest*"`, `"*ExecuteFinancialQueryUseCaseTest*"`, `"*InterpretFinancialQueryUseCaseTest*"`, `"*OnDeviceQueryInterpretationServiceTest*"`, `"*TimePeriodUtils*"`, `"*Engine5PrimitiveGuardTest*"`, `"*MapFinancialQueryToNavigationUseCase*"` (regression); `registered-guard -GuardId time_boundaries`; `static-guards`.
- Done when: AE-C11..C19 green incl. DST + clamp; Engine5 green with no guard/allowlist change; `time_boundaries` zero; no duplicate cue expression at :128/:227.

### C4 — Clarification completion, live pending state, disabled stale options (T2)
- INV-C13..C16 (reuses INV-4); AE-C20..C26; PC-4.
- Source (7, 1 new) + `res` ×3: `FinancialQueryModels.kt:126-129, :166-169` `ClarificationSlot`, `ClarificationChoice`, `ClarificationCompletion`; both Clarification types get `completion: ClarificationCompletion? = null`; **NEW** `A/domain/ai/usecase/CompleteFinancialQueryClarificationUseCase.kt` (pure; PERIOD → `AssistantPeriodResolver.resolveChoice(choice, nowMs)` with the existing clock (D-21); BREAKDOWN → TOTAL; TRANSACTION_LIST → LIST + `grouping = NONE`; all other fields byte-for-byte); `ExecuteFinancialQueryUseCase.kt:46-49` period clarification `completion` [THIS_MONTH, THIS_WEEK, LAST_MONTH]; `InterpretFinancialQueryUseCase.kt:264-269` presentation clarification `completion` [BREAKDOWN, TRANSACTION_LIST]; `AssistantViewModel.kt` `livePendingClarification` + `User.completedIntent`; tap by (itemId, optionIndex): consume first, then execute, no interpreter/provider/network; pending cleared by `submitQuery`, a later Result/Error, `clearSession`, `clearAllHistory`, disabled check (as :211); taps during `_isSubmitting` ignored; `retryLast` (:363-367) re-executes `completedIntent`; stale tap logs bounded `CLARIFICATION_EXPIRED` (no option text); CE propagates; ctor gains the completer; pending never persisted; `AssistantSheet.kt:152-204` + `AssistantResultCard.kt:99-111` route by (itemId, index), disabled when not live, labels from resources, `completion == null` keeps `onClarificationSelected(text)`; `res` ×3 5 option labels.
- Tests: **NEW** `CompleteFinancialQueryClarificationUseCaseTest.kt` (AE-C20, C21 incl. byte-for-byte preservation); `AssistantViewModelTest` AE-C20 (interpreter never called), AE-C22 (expiry, token, disabled), AE-C23 (double tap → executor once), AE-C24 (legacy flow; :546-616 argument shape only, else N14), AE-C25 (retry), AE-C26 (CE, no Error item), tap while submitting ignored; `ExecuteFinancialQueryUseCaseTest` period clarification carries `completion`; `InterpretFinancialQueryUseCaseTest` presentation clarification carries `completion`. CancellationSafety no additions.
- FG-10, FG-16 (bounded token), FG-17 (no new `*.now()`), FG-20. Docs: LEGAL_PATHS assistant section "Clarification completion → `CompleteFinancialQueryClarificationUseCase` → `ExecuteFinancialQueryUseCase` (read-only; no re-interpretation)" + FORBIDDEN: re-interpreting a structured clarification tap, calling providers on completion, persisting pending state; `DEPENDENCY_MAP.md:1283`; `VIEWMODEL_INJECTION_MAP.md:86`; Segment 20. Gates: RS.
- Validation: `"*CompleteFinancialQueryClarificationUseCaseTest*"`, `"*AssistantViewModelTest*"`, `"*InterpretFinancialQueryUseCaseTest*"`, `"*ExecuteFinancialQueryUseCaseTest*"`, `"*CancellationSafetyArchitectureGuardTest*"`, `"*SourceScanningArchitectureGuardTest*"` ((Cb) stays green); `registered-guard` `time_boundaries`, `pii_logging`; `lint`; `static-guards`.
- Done when: AE-C20..C26 green; both surfaces disable non-live options; interpreter calls on completion = 0.

### C5 — MIN executor (T3 by rule)
- INV-C17; AE-C27, C28; PC-3.
- Source (2) + `res` ×3: `ExecuteFinancialQueryUseCase.kt:55-59` dispatches MIN after MAX to a new `executeSmallest` mirroring `executeLargest` (:270-335): same `assistantFilteredExpensesCurrencyAware` input; single currency `minByOrNull { effectiveAmount }`; mixed currencies `convertAsOf(atMillis = expense.date)` per row, no-rate rows excluded (`isPartial`/`excludedCount`); empty → NO_MATCHING_TRANSACTIONS; title key `domain_ai_smallest_purchase`; drilldown narrowed as for MAX; `else` → METRIC_NOT_SUPPORTED; preferred: one shared private extreme-selection helper for MAX/MIN, existing MAX tests unchanged + green. `InterpretFinancialQueryUseCase.kt:247-256, :274-280, :343-375` cues `smallest|cheapest|lowest|minimum` → MIN; MIN added to `supported` + `containsRichIntentCue`. `res` ×3 `domain_ai_smallest_purchase` (runtime `UiText.fromKey`).
- Tests: `ExecuteFinancialQueryUseCaseTest` AE-C27 (single, mixed + no-rate excluded + `isPartial`, empty, drilldown); `InterpretFinancialQueryUseCaseTest` AE-C28 (provider unavailable → fallback MIN → Summary; all 4 cues); MAX tests untouched.
- FG-17 (`MoneyBoundaryGuardTest` untouched; no new conversion logic), FG-19, FG-20. Docs: money-correctness `impact-map.md` stale-line update (PC-14; already applied by orchestrator 2026-10-16) + note that MIN is the second consumer of the `convertAsOf` vs stored-snapshot gap → AmountSemantics alignment covers MAX + MIN (RF-C3). Gates: RS.
- Validation: `"*ExecuteFinancialQueryUseCaseTest*"`, `"*InterpretFinancialQueryUseCaseTest*"`, `"*MoneyBoundaryGuardTest*"`; `lint`.
- Done when: AE-C27, C28 green; no current metric reaches `else`; MAX tests unchanged + green.

### CX — Part C exit checkpoint (validation only, serial)
`unit-test-shard` `domain-a-m`, `data`, `runtime-ui`, `architecture-contracts`; `registered-guard` `cloud_payload`, `pii_logging`, `time_boundaries`, `cancellation`; `static-guards`; `lint`. Done when green, or a pre-existing red matches the S0 baseline with a recorded reason. B1a starts only after. The handoff records C1..C5 results, the PC-7 deletion and any fired N-items.

## 4. S0 additions (Part C discovery; no `app/` edits)
- D-11 `HybridRouter`/`HybridQueryInterpretationService` fall-through: same input to every route; none constructs `Unsupported` or the input (else N16 / re-plan).
- D-12 `AiChatRepository.observeMessages(sessionId).first()`: emits committed rows incl. the just-inserted one? deterministic oldest-first order? (else N11).
- D-13 `CloudAuditProviderProvenanceTest`/`CloudPayloadPolicyTest` pin QUERY_INTERPRETATION payload size/content? (fixture → mechanical in C1; privacy assertion → N9).
- D-14 `hilt-bindings-map.md` path and whether it lists assistant VM/builder ctor deps.
- D-15 `pii_logging` baseline green for the existing assistant `Timber.w(e, …)` sites? (red → N10).
- D-16 `scripts/guardrails/test_cloud_payload_proof.py` passes on base (names `CloudQueryInterpretationService` at :274; fails → N8).
- D-17 `InterpretFinancialQueryUseCaseTest` (:156, :164-198, :186) compares whole `ExpenseQueryFilters`?
- D-18 release-worktree batch file lists (R16..R24; R23 part 1 strings/AI settings) vs Part C write sets (`res` rebase-only; else N4).
- D-19 grep tests for `FinancialQueryInterpretationInput(`, `conversationHistory` (C1 list); `Unsupported("` and the English literals "No matching transactions found" / "This query type is not supported yet" (C2 list); schema-line or `Conversation history` pins (C1/C3).
- D-20 grep `docs/` for `MAX_QUERY_HISTORY_TURNS_FOR_MODEL`, `conversationHistory`, `previousEquivalentPeriod` (FG-26).
- D-21 existing `now` source in Interpret/Execute/VM (clock or time-provider) and exact G-TIME-01 scope (`guard_registry.py:348`; does it flag `System.currentTimeMillis()`?); resolver, completer, VM reuse it; none → N13.
- D-22 release-notes location + "Unreleased"/next section.
- D-23 extend S0 line re-verification to every Part C write-set file; refresh D-3 (`AssistantViewModel.kt:522`) after C4 for B1c.
Baseline additions (serial): `registered-guard` `cloud_payload`, `pii_logging`, `time_boundaries`, `cancellation`; `targeted-unit-test` `"*Engine5PrimitiveGuardTest*"`, `"*FinancialQueryInterpretationInputBuilderTest*"`, `"*AssistantViewModelTest*"`, `"*ExecuteFinancialQueryUseCaseTest*"`. S0 done-when also requires D-11..D-23 answered.

## 5. New stop-and-ask items (Part C)
- N7 Engine5 E5-GUARD-001 satisfiable only with an allowlist entry, or a new non-allowlisted file needs `domain.model.PeriodRange` (PC-8 forbids).
- N8 `cloud_payload` proof red at S0 or after C1/C2, or would need editing — never edit the proof to make it pass.
- N9 a cloud audit/policy test pins the QUERY_INTERPRETATION payload such that only a privacy-assertion change would fix it (D-13).
- N10 `pii_logging` baseline red for existing assistant logs, or clearing it needs allowlist entries/baseline growth.
- N11 `observeMessages` emission/order non-deterministic (D-12) → INV-C2/C3 unprovable without a new repository API (out of scope).
- N12 any assistant file needs a new CancellationSafety KNOWN_VIOLATIONS entry.
- N13 G-TIME-01 flags resolver/completer/VM time acquisition and no clock abstraction exists (D-21) — no guard weakening or new clock infra without approval.
- N14 any test outside the PC-7 list needs deletion or a changed assertion intent (e.g. AE-C24 :546-616 beyond argument shape; `ExecuteFinancialQueryUseCaseTest:90-131` must stay unchanged; existing MAX tests).
- N16 an unlisted producer/reader of `Unsupported.reason` or persisted `payloadJson.reason` (export, analytics, restore) → migration question.
- N4/N5 extended: N4 also covers R23 part 1 drift on assistant/AI strings or `AssistantViewModel`; N5 also covers Part C keys (C2, C4, C5).

## 6. Risk register additions
| # | Risk | Mitigation / slice |
|---|---|---|
| R18 | Shared prompt builder: on-device edits change the cloud payload | (Cc), `cloud_payload` guard, PSG on C1/C2, cloud file not edited (C1–C3) |
| R19 | History snapshot race (read vs `persistUserTurn`) leaks the current query or misorders history | read before persist; AE-C1 VM test; D-12; N11 (C1) |
| R20 | User-visible change: "last month" = previous calendar month; full-unit comparisons | AE-C11..C14; release note (Z); PC-5 |
| R21 | DST/month-end errors in `previousEquivalent` | zone param; Europe/Athens AE-C16, C18; INV-C12 join (C3) |
| R22 | Deprecated `PeriodRange` vs Engine5 guard | plain values, allowlisted callers wrap; N7 (C3) |
| R23 | Persisted `payloadJson.reason` free text → code name | no reader; `ExportAnonymizer` nulls it; same key; old rows readable; N16 (C2) |
| R24 | Double/stale tap executes twice or runs a stale intent | consume first; (itemId, index); AE-C22/C23; RS (C4) |
| R25 | MIN inherits the F-C `convertAsOf` vs snapshot gap | money-correctness RF-C3 (MAX + MIN) (C5) |
| R26 | Hot-file serialisation across C and B | strict order §1; line re-read per slice; D-23 |
| R27 | Guard-label collision Part B vs Part C | Ca/Cb/Cc + distinct `@Test` names |
| R28 | Greek string quality | EN + es + el in the same slice (PC-13); owner reviews el before Z |
| R29 | 6 other AI builders still OR redaction flags | FORBIDDEN scoped to assistant (PC-10); follow-up with `cloud-alias-hashing` (Z) |
| R30 | Downstream plans (money-correctness 1a, assistant-follow-ups, nl-quick-add) written against the pre-Part-C tree | rebase onto post-C seams (`priorUserQueries`, `AssistantUnsupportedReason`, `AssistantPeriodResolver`, `ClarificationCompletion`, `periodGranularity`); no forks |

## 7. Bundle gate impact
Part C folds into the final bundle gate (all 6 shards, `app-check`, `unit-tests` last). Insert after step 7 (db guards), serially: 7a `registered-guard -GuardId cloud_payload`, 7b `pii_logging`, 7c `time_boundaries`, 7d `cancellation`. No `migration-tests`, no `connected-test-class` for Part C. CX is intermediate only. The fresh-session reviewer-strict final acceptance covers A, B and C. Z write-set additions: the release-note line (§8); `CODEBASE_INVENTORY.md` regeneration incl. `AssistantUnsupportedReason.kt`, the UI mapping file, `AssistantPeriodResolver.kt`, `CompleteFinancialQueryClarificationUseCase.kt`; `workflows/active/ai-evolution-handoff.md` follow-ups.

## 8. Release-note line
At Z after C3, C4 and C5 land (staged in the handoff when C5 lands); file per D-22, next/Unreleased section, never the frozen 1.0 notes. Text (spec §9): "Assistant: 'last week', 'last month', 'last quarter' and 'last year' now mean the previous calendar period (previously the current one was used, e.g. 'food last month' showed this month). Comparisons now use the previous calendar week/month/quarter/year instead of a same-length window. Choosing an option in a clarification keeps your original filters. Smallest-purchase questions are now answered."

## 9. Owner plan-gate checklist (Part C)
- [ ] Approve C1..C5 + CX and order S0 → A → C1..C5 → CX → B1a.. → Z (PC-12).
- [ ] Accept CX cost (4 shards + 4 registered guards + `static-guards` + `lint`).
- [ ] (Cc) as a behavioural test in `CloudQueryInterpretationServiceTest`; (Ca), (Cb) in `SourceScanningArchitectureGuardTest`.
- [ ] Optional C3 LEGAL_PATHS one-liner: keep or strike.
- [ ] C5: shared private MAX/MIN helper preferred over a literal copy (MAX tests unchanged).
- [ ] Owner reviews Greek strings for C2, C4, C5 before Z (PC-13).
Deferred: migrating the 6 other builders' redaction OR (with `cloud-alias-hashing`; insight rides B2d); restored-transcript half of OD-C3; Greek period vocabulary (nl-quick-add normaliser + assistant-follow-ups 1b); session context + chips (assistant-follow-ups inc 1); MAX/MIN AmountSemantics alignment (money-correctness RF-C3); backfill of EN-only `assistant_*`/`domain_ai_*` keys in `app/lint-baseline.xml`.
