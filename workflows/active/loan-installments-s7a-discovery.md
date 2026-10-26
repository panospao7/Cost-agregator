# S7a discovery (scout, 2026-10-26, read-only)

Paths are relative to app/src/ unless noted.

## 1. Worker-count pins
There is no config baseline. The pins are hardcoded test counts, which are additive, owner-approved for S7a on 2026-10-22, and not stop-and-ask.

Tests:
- test/.../workers/WorkerContractTest.kt
  - :28-30 asserts 8 default workers.
  - :32-42 asserts the exact key set.
  - :73 asserts `WorkerSpec.DEFAULTS.size == 8`.
  - :77-88 asserts that the WorkerRegistry entries equal the DEFAULTS keys.
- test/.../architecture/WorkerGuardStaticVerificationTest.kt
  - :44-56 lists KNOWN_WORKER_FQNS (11 entries).
  - :128-134 asserts its size equals findWorkerClasses.
  - :158-161 expects 11 from listAllWorkerNames.

Main-source registrations:
- WorkerGuardVerifier.kt :34-46, the workerRegistry map, plus findWorkerClasses (~:99).
- WorkerSpecScheduler.listAllWorkerNames (~:354).
- WorkerSpec.DEFAULTS.
- WorkerRegistry.kt :92-117.
- PrivacyRuntimeWorkerPolicy.kt (~:62): check the classification.

G-WORKER-01 (scripts/verify_worker_boundaries.py with scripts/allowlists/worker_allowlist.yml):
- A thin worker that calls only the coordinator needs no allowlist entry.
- An allowlist entry would be stop-and-ask.

Stale text:
- The plan D-1 says "7 workers"; the actual count is 8.
- The AppStartupCoordinator :721 comment says 7.

## 2. D-19 foreground hook
- AppForegroundState does not exist on this branch.
- The startup work list is pinned: AppStartupCoordinator :712-726 is covered by AppStartupCoordinatorRecoveryTest.
- Any startup hook or persisted marker is stop-and-ask (D-18/D-19). Use an overview-open trigger only.
- No installment overview UI exists yet (S10). For now S7a exposes the coordinator API that the overview calls later.

## 3. Worker template
- service/recurring/RecurringHorizonWorker.kt
  - Injects WorkerExecutionGuard (:39).
  - Uses WORK_NAME and the WorkerSpec.DEFAULTS version (:53).
  - Schedules via companion `schedule` (~:94).
- The registry entry pattern is at WorkerRegistry.kt :108-110.
- A second template is service/warranty/WarrantyExpirationWorker.

## 4. Coordinator readiness
InstallmentPlanLifecycleCoordinator:
- Private `recordEntryExpense(plan, entry, zone)` (:1421-1457) is shared by confirm and the future recorder.
  - merchant = plan.displayName (:1427).
  - Source INSTALLMENT_PLAN, STRICT_EXTERNAL_ID, idempotencyKey(planUuid, entry).
  - userPresentRate = null.
  - Noon-local timestamp.
  - Calls TLC createExpenseDbOnlyV2.
  - DuplicateSkipped goes to adoptableReplay; CancellationException is rethrown.
- confirmEntry (:296).
- Public reconcile() (:1068); its only caller is InstallmentPreBackupReconciler.
- `recordDueEntries` does not exist yet.
- FX per plan amendment A2 (:654, :657):
  - Auto-record never sets userPresentRate, so the flag is null and it is never refused (MEC AE-29).
  - There is no AUTO_RECORD_DEFERRED_FX branch.
  - USD without a rate gives a sentinel row, excluded from comparable totals.
  - The AE-29 null-flag test belongs in S7a.
  - The S7a bullet at plan :351 is stale; A2 wins.

## 5. Binding notes
- D-c (handoff :728):
  - CONFIRM to AUTO_RECORD is refused while any past-due SCHEDULED entry exists.
  - Catch-up therefore never backdates an unconfirmed entry. Do not add a bypass.
- D-18 (plan :863, handoff :751, :990-:1002):
  - Every recordDueEntries run (worker and foreground) calls reconcile() first and dispatches its batches.
  - No startup trigger and no persisted marker.
  - This closes the bulk-update/deleteAll gap.
- Blank displayName (handoff :944, :970): a redacted restored plan has a blank displayName, which recordEntryExpense uses as the merchant. TLC validation then fails it with RECORD_FAILED. S7a must handle it (a fallback or a bounded refusal).
- Gates: architecture-guardian (FG-10/11/13), privacy-security (diagnostics), reviewer-strict.

## 6. Spec and UX text still describing AUTO_RECORD_DEFERRED_FX
- domain-spec.md :606-607.
- ux-flows.md :484, :609, :684, :1031, :1068, :1274.
- This is the plan's domain-modeler follow-up "before S7a".

## Open checks
- Whether recordDueEntries needs new DAO writes or queries, which would mean db_ownership pins and rows.
- The entry compare-and-set (CAS) DAO names.
