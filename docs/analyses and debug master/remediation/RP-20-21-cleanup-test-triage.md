# RP-20 — Dead-code & cleanup sweep (cross-pipeline)

> **Scope class:** universal (cross-pipeline deletions). **Mode:** standard, but **each deletion requires its zero-caller verification quoted in the commit message** (grep output), per D4 approval.
> **Rule:** one deletion = one commit. If any item's grep finds a live caller, **stop and re-scope** — the audit basis was HEAD `d1fa9c68` + uncommitted; new code may have consumed an API since.
> **Sequence:** land **last** (after RP-01…RP-19) so no package depends on a to-be-deleted symbol; several items are already executed inside their owning RP (noted) — this document is the checklist/ledger.

---

## Deletion ledger (D4 items + hygiene)

| # | Symbol / file | Location | Status | Owner |
|---|---|---|---|---|
| 1 | `NotificationRepository.deleteAll()` | `data/repository/NotificationRepository.kt:247-258` | pending (D4) — `@Deprecated(ERROR)`, zero compilable callers; tests may reference → update tests in same commit | RP-20 |
| 2 | `ExpenseDao.updateMerchantForMerchant` (**NEW-P2-008**) | `data/database/dao/ExpenseDao.kt:337` | pending (D4) — `@Restricted`, zero production callers; tests pin non-use → remove pins | RP-20 |
| 3 | `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween` | `:1516-1530` | executed in RP-08 (P6-003) — delete after the autopilot migration lands | RP-08 |
| 4 | `ProcessReceiptUseCase` (whole class) | ⟞ `domain/…/ProcessReceiptUseCase.kt` | pending (D4) — zero production callers; delete class + its test; closes P3-P1-07 permanently | RP-20 |
| 5 | `ReceiptRepository.insertReceipt` deprecation | `data/repository/ReceiptRepository.kt:506-519` | escalate `WARNING → ERROR` after confirming `WarrantyTrackerRepository:725`'s chain is production-dead (P3-P1-05 adjusted); if any caller survives, route it through `ReceiptInsertResolver` instead | RP-20 |
| 6 | `RecurringPlanProjectionService.projectFromRule` (**method only**) | domain/recurring/RecurringPlanProjectionService.kt:58 | pending (D4, corrected) — method has zero callers; **service is live** (`projectFromOccurrencesInCurrentTransaction` used at RecurringRuleLifecycleCoordinator :167/:226/:334) — delete only the method + fix the stale KDoc mention in SynthesisEngine.kt:36 | RP-20 |
| 7 | `ImportCoordinator` | `util/ImportCoordinator.kt` | executed in RP-19a (preferred) or here per D4 | RP-19 |
| 8 | `BankConnectionsViewModel.refresh()` | ⟞ | pending (D4) — immortal-collect bug, zero callers; delete method (P10-004 closed-as-dead) | RP-20 |
| 9 | `CloudWarrantyExtractionService` private sanitizers + companion regexes | `:297-318, :381+` | executed in RP-15b (P8-005) | RP-15 |
| 10 | `captureForRetry` REPLACE policy → `KEEP` | `NotificationIntakeCoordinator.kt:232-236` | executed in RP-10a (fingerprint unification makes the second-capture path unreachable; KEEP is the honest policy) — RP-10a decides | RP-10 |
| 11 | `RecurringLifecycleFixesTest` (empty `@Ignore` stub) | `app/src/test/.../domain/recurring/` | delete (superseded by RP-04's new tests) | RP-20 |
| 12 | `MultiCurrencyRepository.updateExpenseCurrency` stub | `:935-943` | delete if zero callers (REVAL-8; else KDoc-stamp as staged feature) — decided in RP-06a | RP-06 |
| 13 | `SHUTDOWN_DRAIN_TIMEOUT_MS` dead constant | `NotificationCaptureService.kt:188` | executed in RP-10b (P1-004) | RP-10 |
| 14 | `ReceiptSideEffectDispatcher.dispatchAfterSave` | ⟞ | decided in RP-12a: delete if planner+runner covers all dispatch (zero callers today) | RP-12 |
| 15 | FAILED_PERMISSION delivery status | enum + writer | executed in RP-04c (P4-006): route to CANCELLED/PERSMISSION_REVOKED; delete enum value if no historical rows can exist | RP-04 |

## Additional hygiene from the audits (same rules)

- **`NotificationIntakeRecoveryScheduler` KDoc** claims nonexistent sweeps — fixed functionally in RP-10b; verify the KDoc is corrected there (REVAL-4).
- **`ExportOptionsViewModel` dead `allowsEmptyDataset` inversion** — resolved in RP-19b.
- **`AiChatSessionEntity.title`** — anonymizer coverage added in RP-03c (P7-011); no deletion.
- **Doc corrections shipped in-code by owning RPs:** `ReceiptRepository:578` batch-dispatch KDoc (RP-12a), scheduler UPDATE-comment (RP-16c), `TransactionSideEffectPlanner`/`CreateExpenseRequest` skipDedup contract (RP-11b).

## Execution checklist per deletion commit

1. `grep -rn "<symbol>" app/src` — quote the output in the commit body.
2. Confirm no DI module `@Provides`/`@Binds` references the symbol (Hilt fails at compile otherwise — compile check covers this).
3. Tests referencing the symbol: update or delete **in the same commit** (deleting a test that pinned dead behavior is legitimate here — cite the audit ID).
4. One commit message format: `chore(dead-code): remove <symbol> (audit <ID>; zero production callers verified <date>)`.

## Validation (when Gradle re-enables)
```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*Architecture*"   # guards must stay green with symbols gone
```
Watch specifically: `ExpenseDaoMutationAccessTest`, `RecurringArchitectureGuardTest`, `DirectEventDaoInsertGuardTest` — deletion must **shrink** their allowlists where applicable (never leave stale allowlist entries for deleted symbols).

---

# RP-21 — Test-debt triage (gated)

> **Scope class:** the unit suite itself. **Mode:** standard. **Hard gate:** the stakeholder re-enables Gradle/test runs first (this package *is* the reason — the suite currently can't arbitrate anything).
> **Basis:** REVAL-1/2/3/6/7/9 + the ~178-failure observation (attribution unknown: environment vs outdated vs real).

## Phase 0 — Baseline & attribution (no fixes)
1. Full `:app:testDebugUnitTest` run, results archived per class.
2. Triage sheet: each failing test → **ENV** (machine/locale/timezone/DST-sensitive — the `TimePeriodUtilsT4C*`, `BudgetCalculatorTimeBoundaryTest`, `SpendingPersonalityClassifierTest` DST families are prime suspects), **OUTDATED** (pins removed/changed behavior — REVAL-2/6/7 provide known examples), or **REAL** (contradicts intended current behavior → cross-check the registry; several may already be fixed by the RPs by now).
3. No test edits in phase 0.

## Phase 1 — Known outdated (fix alongside their owning RPs)
- `ReceiptLifecycleCoordinatorTest:234` (camera-dispatch contract removed→restored by RP-12a) — updated in RP-12a, verified here.
- `BudgetAutopilotEngineTest:253-262` (−15% empty-history default) — re-pinned by RP-08.
- `DataRetentionWorkerTest:206` (permanent-failure success semantics) — re-pinned by RP-16b (partialFailureCount visibility).
- `RawStoragePolicyAuditTest` tautology (REVAL-3) — rewritten by RP-14b; verified here against the real registry.

## Phase 2 — ENV hardening
- Time/DST-sensitive tests: inject fixed `ZoneId`/`Clock` (the codebase has `TimeProvider` infrastructure) instead of depending on the runner's default timezone/locale; pin `Locale.setDefault(Locale.US)` in affected tests' `@Before`/rule.
- Windows-host-specific failures (path/line-ending) — normalize with existing test utilities; document any that must remain host-gated.

## Phase 3 — REAL failures
- Each REAL item maps to a registry ID (or becomes a new registry entry via the standing audit process); fix order follows the registry's remediation priority — the RP packages above may already cover them; re-run and close.

## Phase 4 — Regression gate restoration
- Once green (or green-with-documented-ENV-skips), the suite resumes its AGENTS-mandated role: targeted commands per PR, strict-mode packages cite their runs in completion reports.

## Exit criteria
- 0 unattributed failures; outdated pins replaced; ENV tests hermetic; guard tests green (including RP-02's strengthened guards and RP-20's shrunk allowlists).
