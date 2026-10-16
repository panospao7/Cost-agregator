# Impact map: ai-fixes (Part A, B1 warranty, B2 transaction insight, B3 briefing decoupling [+ OD-2 warranty-expiry worker])

Author: impact-mapper (stage 5, read-only). Persisted by the orchestrator. Date 2026-10-16.
Basis: `domain-spec.md` (binding, INV/AE ids), `brief.md` v2, `ux-flows.md`, `workflows/active/ai-fixes-handoff.md`.
`A` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`. Every named production class confirmed by Glob/Grep unless UNVERIFIED. New-by-design names (absent today): TRANSACTION_INSIGHT, AppForegroundState, InstallProvenance, BriefingDeliveryOutcome, WarrantyReviewState, transactionInsightEnabled.

## 1. Segments, files, paths, engines

Segments (CODEBASE_SEGMENTS): 20 AI Platform/Assistant/Follow-Through; 34 Warranty; 4 Receipt Lifecycle (ReceiptSideEffectPlanner, Policy A); 12 Startup & Background Runtime; 3 Notification Capture; 9 Core Expense; 11 Notifications; 28 Security/Privacy (EffectiveCloudAiPolicy, unchanged); 30 DI; 29 Debug; 39 Static Guardrails.

### Part A (hygiene; UI + docs; no persistence)
- `docs/features/FEATURES.md`: relabel :132, :166, :392, :395, :396, :568, :581, :901 and warranty section :36, :39, :45. **Spec gap:** `:911` ("AI-generated negotiation scripts") IS present; INV-1 lists only :901 → Gate 2.
- `A/data/privacy/DataRetentionWorker.kt` header lines 1-2 (comment only).
- `A/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` KDoc/WRN-15 comment (:41-53, :126-127, :133); code change is B1.
- `A/ui/screens/aisettings/AiSettingsViewModel.kt` (literals :110,:113,:153,:163,:173,:227,:237,:254,:263,:277,:283; `catch (e: Exception)` ~:333-335 raw `e.message` + CE rethrow + class-name-only log); `AiSettingsScreen.kt` (:129, :137 literals).
- `A/ui/screens/warranty/WarrantyTrackerViewModel.kt` (:181-199 confirm: raw message, CE swallowed) and `WarrantyTrackerScreen.kt` (SnackbarHost for `mutationError`, no reader today).
- `app/src/main/res/values/strings.xml`, `values-es/strings.xml`, `values-el/strings.xml`.
- Legal path/engines/DataStore/permissions/privacy: none. FG-10 touched (CE rethrow in 2 VMs).

### Part B1 (warranty)
- `A/data/repository/WarrantyTrackerRepository.kt` (MAJOR): delete `extractWarrantyFromReceipt`:432, `extractWarrantyResult`:437, `toWarrantyEntityOrNull`:476, `parseWarrantyType`:525, `toCalendarMonthEndDate`:602, `processReceiptForWarranty`:569, two cloud confidence consts; remove ctor deps `cloudExtractionService`, `aiSettingsRepository`, `aiPolicy`, `aiCapabilityRouter` (:60-63); drop `extractionResult` param of `extractReturnWindow`:534; new `confirmWarranty(id)` (barrier, read-by-id, one transaction, `CONFIRMED` event); extend `reconcileExpiredItems`:366 (pending expiry, `ExpiryReconciliationResult.expiredPendingWarrantyCount`:614).
- `A/data/database/dao/WarrantyDao.kt`: `markExpiredWarranties`:64 SQL widened ACTIVE → ACTIVE + PENDING_REVIEW, plus pending-count query. `@Query` SQL only; no entity/column change.
- `A/data/database/entity/WarrantyLifecycleEvent.kt`: additive `CONFIRMED` const (TEXT).
- `A/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`: remove :128-132 cloud gate and `privacyGate: PrivacyGate` ctor dep (:60) per OD-1(a); 0.40-0.70 band calls `createReviewDraftWarranty` directly.
- NEW domain `WarrantyReviewState.of(warranty, now)` (package: planner).
- `WarrantyTrackerViewModel.kt` + `WarrantyTrackerScreen.kt`: counts/filters/badges via `WarrantyReviewState` (INV-10); confirm → `repository.confirmWarranty(id)` (FG-12); UX §4 badge/actions.
- `A/domain/ai/model/AiModels.kt` (:87-108 `warrantyExtractionEnabled` default false; single default source, INV-22); `A/data/repository/AiSettingsRepositoryImpl.kt` (:110-133 `toAiSettings()` fallbacks from `AiSettings()`).
- `AiSettingsScreen.kt` :244-250: hide warranty row + local-note caption.
- `A/service/warranty/WarrantyExpirationWorker.kt` (only if OD-2=A): `requiresNotificationPermission=false` (:76), inject `NotificationPermissionChecker`, local check before reminders, always reconcile; ctor + test fakes change.
- KEEP unchanged (spec §5.2): `CloudWarrantyExtractionService`, `WarrantyExtractionModels`, `AiModule` provider, `AiCapability.WARRANTY_EXTRACTION` + router/policy entries, `PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION`, RP-15 row (`EffectiveCloudAiPolicy.kt:19,56`).
- Legal paths: LEGAL_PATHS Warranty Auto-Create (:981-1000) changes; return-window write, confirm, expire = new entries (spec §5.6); Workers (:483-530) for OD-2. Receipt data only via ReceiptRepository + Policy A. `rejectAutoDetectedWarranty`, `markWarrantyAsClaimed`, `deleteWarranty` unchanged.
- Engines: WarrantyTextExtractor (unchanged); PrivacyGate row (:183-198) loses a warranty use-case consumer.
- DAOs: WarrantyDao, ReturnWindowDao, WarrantyLifecycleEventDao, WarrantyReminderDeliveryDao (read). Worker: WarrantyExpirationWorker (OD-2). DataStore: `ai_warranty_extraction_enabled` default flip. Privacy: removes the only warranty upload.

### Part B2 (transaction insight, on-device only)
- `AiModels.kt`: `AiCapability.TRANSACTION_INSIGHT` appended last; `AiSettings.transactionInsightEnabled` (read fallback false).
- Exhaustive-`when` sites: `DefaultAiCapabilityRouter.kt` (:255, :276, :291, :306, :321, :336-352), `AiPolicyImpl.kt` (:17 `canUseCloudFor` false, :36), `GetAiRuntimeStatusUseCase.kt` (:95), `AiSettingsScreen.kt` (:738), `ui/screens/debug/DebugScreen.kt` (:840-865). `Converters.kt:85` unchanged (unknown-name fallback = DASHBOARD_BRIEFING → `WARRANTY_EXTRACTION` must not be removed).
- `GenerateTransactionInsightUseCase.kt` (MAJOR): returns `TransactionInsightOutcome`; drops `HybridDashboardBriefingService`, `PrivacySettingsRepository`, `AiArtifactRepository` deps; uses `OnDeviceDashboardBriefingService`; gate `aiEnabled ∧ transactionInsightEnabled ∧ allowOnDeviceAi`; foreground short-circuit; CE rethrow; bounded diagnostics; TTL const.
- NEW: `AppForegroundState` port + impl registered in `A/startup/AppStartupCoordinator.kt` `registerLifecycleObserver` (:701-702); `InstallProvenance` port + impl; skip-reason enum; DI bindings (`di/AiModule.kt` or new module; update `hilt-bindings-map.md`).
- `AiSettingsRepositoryImpl.kt`: key `ai_transaction_insight_enabled`, `produceMigrations` DataMigration on `preferencesDataStore(name="ai_settings")` (:24-27), `update()` writes key (:79-103).
- Callers: `ManualExpenseRepository.kt` (:52, :72 `RECOMMENDATION_JOB_TIMEOUT_MS=3_000`, :233-255; catch :251 must rethrow CE) and `NotificationProcessingPipeline.kt` (:153, :916, :1739-1755). `AppConfig.kt` gets `TRANSACTION_INSIGHT_TIMEOUT_MS`, `TRANSACTION_INSIGHT_TTL_MS`, `RECOMMENDATION_JOB_TIMEOUT_MS` (5_000) shared by both callers.
- `DefaultAiEnvironmentMonitor.kt` :84 CE rethrow (FG-10).
- `OnDeviceDashboardBriefingService.kt` (:45-47): optional typed GenAI-error carrier vs collapse to PROVIDER_ERROR (planner).
- `DashboardFollowThroughEngine.kt`: consumer, no change (`:150` "€" out of scope).
- `AiSettingsViewModel`/`Screen`: insight row + `setTransactionInsightEnabled`, proactive copy; optional `NotificationPermissionChecker` read (UX-Q-3).
- No write path (INV-20). LEGAL_PATHS Privacy/Cloud AI (:375-416) gains "on-device-only capabilities" line. Privacy: no PrivacyCapability/CloudPayloadPurpose/RP-15 change; removes per-expense merchant+amount cloud upload.

### Part B3 (briefing decoupling) and OD-2
- `DailyBriefingWorker.kt`: `requiresNotificationPermission=false` (:75), inject `NotificationPermissionChecker`, local check before delivery, `addNotificationsSent()` only on `DELIVERED` (:108-119), record `NOTIFICATION_PERMISSION_DENIED`. Keep `requiredCapabilities=[CLOUD_AI_DAILY_BRIEFING]` and fresh-artifact short-circuit (:90-98).
- `DeliverProactiveBriefingNotificationUseCase.kt`: returns `BriefingDeliveryOutcome`.
- `SyncProactiveBriefingWorkUseCase.kt` :16-18: predicate `aiEnabled ∧ briefing ∧ (allowCloudAi ∨ proactive)`.
- `AiSettingsViewModel.kt` :296 `setAllowCloudAi` must start syncing.
- UNCHANGED: `PrivacyRuntimeWorkerPolicy.kt:58`, `WorkerRegistry.kt:110`, `WorkerGuardVerifier.kt:41`, `WorkerSpec.kt:86`, `HomeViewModel.kt:285`, `AppStartupCoordinator` ordering.
- LEGAL_PATHS Workers (:494 optional-notification pattern). POST_NOTIFICATIONS moves from guard-level to post-time local check (AGENTS.md:165-167).
- Pre-existing divergence: `RestoreMaintenanceMode.scheduleAllWorkers()` (:446) re-arms `ai_daily_briefing` after restore without Sync; INV-26 bounds to one idle run.

## 2. Tests and guards

### Stop-and-ask test list (`T/data/repository/WarrantyTrackerRepositoryTest.kt`)
- DELETE: :641 `cloudWarrantyConfidence_0_8_autoCreatesWithoutReview`, :673 `..._0_4_createsNeedsReviewDraft`, :705 `..._0_1_discardsWithDiagnostic`, :735 `..._0_75_exactBoundary_autoAccepts`, :767 `..._0_30_exactBoundary_createsReviewDraft`. :673/:705/:767 are review/discard-band tests; the discard diagnostic is no longer production-reached after Q1(a).
- RETARGET (FG-19): :127, :171, :217 (→ `AutoCreateWarrantyFromReceiptUseCaseTest`), :316, :347; setup :68-92 mechanical.
- Other retargets: `GenerateTransactionInsightUseCaseTest` (:71, :114 → AE-25/26), `SyncProactiveBriefingWorkUseCaseTest:43` (→ 16-row predicate table, AE-47), `DeliverProactiveBriefingNotificationUseCaseTest` (6 tests), `DailyBriefingWorkerTest` (:757; add AE-41..43, AE-50), `AutoCreateWarrantyFromReceiptUseCaseTest` (AE-9..12), `AiSettingsViewModelTest` (AE-2, AE-4), `WarrantyTrackerViewModelTest` (AE-3, AE-16, AE-20), `WarrantyExpirationWorkerTest` (AE-51), `DefaultAiCapabilityRouterTest`, `AiPolicyTest` (AE-27), `GetAiRuntimeStatusUseCaseTest`, `DefaultAiEnvironmentMonitorTest`, `DashboardFollowThroughEngineTest`, `AiArtifactRepositoryImplTest:227`, `ReceiptSideEffectPlannerWarrantyTest` (AE-13), `NotificationProcessing*Test` ×3 (AE-28).
- New: `AiSettingsRepositoryImplTest` (AE-7, AE-33..38 + defaults parity), `WarrantyReviewStateTest`, confirm/reconcile tests (AE-14, 15, 17..21, 23, 24), insight use-case tests (AE-25..32, 39, 40), `AppForegroundState`/`InstallProvenance` tests, `ManualExpenseRepository` caller test (new class; AE-28..31).
- Instrumented: `androidTest/.../dao/WarrantyDaoTest.kt` (widened `markExpiredWarranties` + pending count; needs AVD).
- Expected unchanged: `WorkerExecutionGuardTest`, `WorkerContractTest`, `PrivacyRuntimeWorkerPolicyTest`, `PrivacySettingsRepositoryImplWorkerGatingTest`, `WorkerGuardStaticVerificationTest` (planner to grep notification-flag assertions), RP-15 tests, `CloudWarrantyExtractionServiceTest`, `WarrantyExtractionModelsTest`, `CloudProviderTransportPayloadTest`, `scripts/guardrails/test_cloud_payload_proof.py:276` (provider file must not be deleted).

### Guards / policy files touched (all stop-and-ask)
1. `T/architecture/SourceScanningArchitectureGuardTest.kt:408` `optionalNotificationWorkers` += `DailyBriefingWorker.kt` (+ `WarrantyExpirationWorker.kt` if OD-2=A). Rule (:395-423) not weakened: allowlisted workers must not set `= true` and must contain `notificationPermissionChecker.areNotificationsEnabled()`.
2. New source-scan guards (spec §8.4): (a) `CloudWarrantyExtractionService` referenced only from `di/AiModule.kt` (exempt its own file + `WarrantyExtractionModels.kt`); (b) `GenerateTransactionInsightUseCase.kt` imports neither Hybrid nor Cloud briefing services; (c) warranty VM does not build status in write paths.
3. `CancellationSafetyArchitectureGuardTest.kt` KNOWN_VIOLATIONS (:79, :92, :127, :151, :171; expire 2026-12-31): remove only if no broad catch remains; never add (FG-07).
4. `SourceScanningArchitectureGuardTest.kt:545-589` broad-catch allowlist: `WarrantyExpirationWorker.kt` expires 2026-09-30 (already past) — enforcement UNVERIFIED; check with a baseline run before OD-2.
5. **DB ownership policy (GR-08; not in spec):** `config/guards/db_ownership_policy.yml` (:3209-3510) rows for `WarrantyTrackerRepository`: delete stale `toWarrantyEntityOrNull` row (:3433); add `confirmWarranty(Long)` rows; possibly `reconcileExpiredItems` rows. Regenerate signatures per R7 Pattern E (`migrate_db_policy_signatures.py --generate/--verify`; `db_ownership_policy.signatures.accounting.json`, `.candidate.yml`, `docs/ci/db-findings/GR-08-seeds.yml`). `T/guard/DbGuardPolicyFixtureTest.kt:1449` unchanged unless the worker gains DAO writes. `config/db_access_allowlist.yml:247`: no new DAO; `confirmWarranty` must call `writeBarrier.checkWritesAllowed` (FG-13).
6. Lint: `app/build.gradle.kts:242-245` baseline holds only pre-existing MissingTranslation; new keys without `values-es`/`values-el` likely → NEW lint errors (UNVERIFIED until lint run). Must not be resolved by baseline refresh.

### FG-IDs by part
- A: FG-10, FG-12 (partly B1), FG-16, FG-26.
- B1: FG-12, FG-13, FG-14, FG-16, FG-19, FG-11 (OD-2), FG-07, FG-26. FG-21 NOT triggered.
- B2: FG-16, FG-10, FG-19, FG-26.
- B3: FG-11, FG-16, FG-10, FG-19, FG-26.
- Cross: FG-06 (allowlist edit), FG-08, FG-23 (new guard tests), FG-26.

## 3. Schema impact
**NONE** — no Room entity/column/version change, migration or snapshot. `AiCapability` stored as TEXT (`Converters` :80-85, no CHECK); warranty status by enum name; lifecycle event types are `const val` strings; "expired unconfirmed" = `EXPIRED ∧ needsReview`; `ReturnStatus` untouched; insight never persisted. Only: DataStore key + one-time `DataMigration` (not backed up) and `WarrantyDao` `@Query` SQL edits. FG-21 / `migration-tests` NOT required. Reopens if the planner persists insight artifacts or adds a `ReturnStatus`.

## 4. Architecture-doc updates (FG-26)
- `LEGAL_PATHS.md`: Warranty Auto-Create (:981-1000) rewrite per spec §5.6 (:990 draft = `PENDING_REVIEW`; :992/:998 per OD-1; add return-window, confirm, expire paths; 4 FORBIDDEN lines); Privacy/Cloud AI (:375-416) on-device-only line + `CloudWarrantyExtractionService` no production caller; Workers (:483-530) optional-notification pattern at :494.
- `ARCHITECTURE.md`: :51, :1269 (WarrantyExpirationWorker, if OD-2=A), DailyBriefingWorker row (~:1271), :1827, :133/:165 (UNVERIFIED), ~:1978 (UNVERIFIED).
- `DEPENDENCY_MAP.md:591,800`; `hilt-bindings-map.md` (:174, WarrantyTrackerRepository deps, new bindings, insight deps); `COMPLETE-BACKEND-MAP.md:1217` (optional).
- `ENGINE_INTERACTION_MAP.md`: note under HybridRouter (:162-173) that `HybridDashboardBriefingService` is briefing-only. Pre-existing "7 AI services" vs LEGAL_PATHS "8 providers" inconsistency: do not fix without a decision.
- `CODEBASE_SEGMENTS.md`: new files in Segments 20/12/34. `CODEBASE_INVENTORY.md` counts (UNVERIFIED regeneration).
- `FEATURES.md` (INV-1 + warranty "not active").

## 5. Risk tier
- Part A: **T1** (mild T2 edge: CE rethrow in 2 VMs). No mandatory guardians; run `static-guards`.
- B1: **T3** — privacy default flip + retired upload, warranty lifecycle, GR-08 policy rows, new source-scan guard, 5 stop-and-ask deletions.
- B2: **T3** — capability split, DataStore migration + provenance, FG-10 across 4 files, cross-segment (20/3/9/12).
- B3: **T3** — worker guard contract + allowlist, scheduling authority, permissions.
- OD-2 (if A): T3.
- **Overall T3.** Planner-first mandatory. `reviewer-strict` required for B1, B2, B3 (+OD-2). `architecture-guardian` and `privacy-security-guardian` required. `room-migration-guardian` advisory only (confirm no schema change on `@Query` edits).

## 6. Validation profiles (scripts/validation-runner.ps1 — ValidateSet verified; params -TestFilter, -Shard, -GuardId)
Only `validation-runner`, serially.
- A: `targeted-unit-test` `*AiSettingsViewModelTest*`, `*WarrantyTrackerViewModelTest*`, `*DataRetentionWorkerTest*`; `lint`; `static-guards`.
- B1: `compile`; `targeted-unit-test` `*WarrantyTrackerRepositoryTest*`, `*AutoCreateWarrantyFromReceiptUseCaseTest*`, `*ReceiptSideEffectPlannerWarrantyTest*`, `*WarrantyTrackerViewModelTest*`, `*WarrantyExpirationWorkerTest*`, `*AiSettingsRepositoryImplTest*`, `*WarrantyReviewStateTest*`, `*AiPolicyTest*`, `*DefaultAiCapabilityRouterTest*`, `*PrivacyCapability*`; `connected-test-class` `com.yourname.expensetracker.data.database.dao.WarrantyDaoTest` (AVD wave5-api35); `registered-guard` for DB ownership (id UNVERIFIED; list via `-Action Plan`/`Profiles`), then `static-guards`.
- B2: `targeted-unit-test` `*GenerateTransactionInsightUseCaseTest*`, `*DefaultAiCapabilityRouterTest*`, `*AiPolicyTest*`, `*GetAiRuntimeStatusUseCaseTest*`, `*DefaultAiEnvironmentMonitorTest*`, `*AiSettingsRepositoryImplTest*`, `*NotificationProcessingPipeline*`, `*ManualExpenseRepository*`, `*DashboardFollowThroughEngineTest*`, `*AiArtifactRepositoryImplTest*`, `*CancellationSafetyArchitectureGuardTest*`.
- B3: `targeted-unit-test` `*DailyBriefingWorkerTest*`, `*DeliverProactiveBriefingNotificationUseCaseTest*`, `*SyncProactiveBriefingWorkUseCaseTest*`, `*WorkerExecutionGuardTest*`, `*PrivacyRuntimeWorkerPolicyTest*`, `*WorkerGuardStaticVerificationTest*`, `*SourceScanningArchitectureGuardTest*`, `*WorkerContractTest*`.
- Bundle gate: `unit-test-shard` architecture + data shards (names UNVERIFIED; confirm via `-Action Plan`), `static-guards`, `lint`; `unit-tests` + `app-check` at final acceptance only. `migration-tests` NOT required.

## 7. Conflicts and sequencing
- Release candidate C* = 53824bcd on `release-1.0-remediation`; any `app/` change invalidates final evidence → no production edit until Release 1.0 accepted.
- Active release worktree `C:\Users\panos\Desktop\Cost-agregator-release` (`release-1.0-wt`), batches R16..R24:
  - **R24** (WarrantyTrackerViewModel swallows CE, raw message, unguarded writes) collides with Part A item 3 / INV-2 / INV-4 on `WarrantyTrackerViewModel.kt` (not landed yet).
  - **R21 HZ-05 F1** (daily briefing REPLACE postpones indefinitely) touches briefing scheduling used by B3 (files UNVERIFIED).
  - **R23 part 1** (exchange-rate own network setting) may touch settings repos / AI settings (UNVERIFIED).
  - R17/R19 Home/navigation (low overlap).
  - Recommendation: branch from `bug-fixes` after `release-1.0-wt` merges; rebase Part A over R24; if R24 lands first drop the overlapping WarrantyTrackerViewModel part and keep only spec deltas.
- loan-installments (post-1.0, same base): no warranty/AI-settings/insight overlap. Shared hot files: `SourceScanningArchitectureGuardTest.kt`, `LEGAL_PATHS.md` (different sections), `strings.xml`, `WorkerRegistry.kt` / `WorkerGuardStaticVerificationTest` ("exactly 10 unique work names" — second merger adjusts), `db_ownership_policy.yml` + signatures (serialize regeneration), `AppDatabase`/migrations (loan only). Don't interleave with loan's Room v152 slice.
- Order: A → B1 → B2 → B3 (OD-2 rides with B3 or B1 so the allowlist edit lands once). Land INV-22 single-source defaults in B1 before B2's new key. Land the guard allowlist change with the first worker needing it, never before the local check exists.

## 8. Open items for Gate 2
1. OD-1: (a) Policy A as receipt-text guard (remove cloud check + `PrivacyGate` dep) vs (b) new local-only PrivacyCapability (touches `PrivacyBlockedCard.kt:116`, `PrivacyAuditLoggerImpl.kt:74`, `PrivacyBlocked.kt:116-120`, `PrivacyCapabilityHandlingPolicy`, `CloudAiPrivacyGate`, `EffectiveCloudAiPolicy`, 4+ tests). Map assumes (a).
2. OD-2: (A) decouple `WarrantyExpirationWorker` in bundle vs (B) defer.
3. Guard sign-off: `SourceScanningArchitectureGuardTest.kt:408` allowlist += DailyBriefingWorker.kt [+ WarrantyExpirationWorker.kt]; 3 new source-scan guards.
4. GR-08 DB ownership policy edits (delete `toWarrantyEntityOrNull` row, add `confirmWarranty` rows, regenerate signatures) — new stop-and-ask.
5. Exact test list (deletions :641, :673, :705, :735, :767; retargets as above).
6. Translation/lint: provide `values-es`/`values-el` for every new/changed key, or confirm lint doesn't flag them (UX-Q-5 vs lint baseline).
7. UX-Q-1..Q-4 (Expired filter/count; reject confirmation dialog; VM reads notification state; highestPriorityMessage may still surface hidden warranty capability — UNVERIFIED).
8. FEATURES.md `:911` in or out.
9. Insight timeout: 3 s vs Nano warm-up UNVERIFIED; `RECOMMENDATION_JOB_TIMEOUT_MS` 5_000 approval; typed GenAI-error carrier vs PROVIDER_ERROR.
10. Sequencing: wait for `release-1.0-wt` merge; R24 vs Part A ownership of `WarrantyTrackerViewModel`.
11. Adjacent out-of-scope (spec §15) accepted: `ReceiptSideEffectPlanner.kt:216-219` retries deterministic Failure; misleading `AI_WARRANTY_CREATED` actor label; "€" in `DashboardFollowThroughEngine.kt:150`; `PrivacyRuntimeWorkerPolicy:60-63` NOTIFICATION_CAPTURE gating of warranty worker; 1 ms expiry boundary. New: `AutoCreateWarrantyFromReceiptUseCase.kt:110-115` Timber.d logs productName/merchantName/confidence and `:151` logs a throwable (INV-3 applies to touched components) — fold in?

## Unverified references
New-by-design names absent today (expected). Spec line refs `ReceiptSideEffectPlanner.kt:181-211`, `LEGAL_PATHS.md:410`, `ARCHITECTURE.md:1271/:1978` not individually re-verified. Shard names, registered-guard ids, JVM Room DAO test availability, scanner behaviour on signature drift, broad-catch allowlist expiry enforcement: UNVERIFIED.
