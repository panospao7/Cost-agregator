# RP-20 — Dead-code and cleanup sweep

> **Mode:** strict cross-pipeline verification. **Status:** CONDITIONAL. Execute after RP-15–19 and dependent owner decisions.

## Ownership rule

Every symbol has exactly one owner and disposition: behavior fix, deletion, test rewrite, documentation-only, or accepted debt. Re-run zero-caller and DI/reflection scans after preceding plans land; audit-time results are not proof. Compile after each deletion and shrink architecture-guard allowlists in the same change.

## Disposition ledger

| Symbol | Owner/disposition | Gate |
|---|---|---|
| `ImportCoordinator` | RP-19; retain/repair or replace canonical facade | Never delete from RP-20 on zero callers |
| `ReceiptRepository.insertReceipt` | RP-12/lifecycle | `WarrantyTrackerRepository.kt:725` remains a production caller; no ERROR deprecation yet |
| `ProcessReceiptUseCase` | RP-20 candidate deletion | Hilt/reflection scan, migrate valuable test to coordinator, update architecture docs |
| `ReceiptSideEffectDispatcher.dispatchAfterSave` | RP-12 decision | Defer until restoration/caller scan |
| `RecurringLifecycleFixesTest` | RP-04 replacement contract tests | Do not blindly delete lifecycle assertions |
| `RawStoragePolicyAuditTest` | RP-14 rewrite | RP-21 classifies only |
| `captureForRetry` | RP-10 behavior | RP-20 does not relitigate |
| `RecurringPlanProjectionService.projectFromRule` | RP-20 candidate deletion | Service remains live; verify method-only callers |
| `BankConnectionsViewModel.refresh` | RP-17 reachability decision | Verify screen/navigation callers |
| Warranty duplicate sanitizers | RP-15 removal | Zero local/reflection callers |
| DAO methods/constants | RP-20 candidates | Zero-caller, guard scan, compile |

## Verification and completion

The scanner excludes declarations/comments/test-only references while reporting those categories separately. Check Hilt, reflection/string references, navigation, and architecture-document references before deletion. A surviving production caller stops deletion and triggers re-scoping. Deleted legal-path symbols require simultaneous docs and guard updates.

Run compile after each deletion, architecture guard tests after allowlist changes, DI/reflection scans, and documentation consistency checks. Never delete a meaningful regression test solely because behavior changed; preserve red, repair, or migrate it with an owner and registry ID. RP-20 completes only after the final post-RP-19 scan and strict review pass.

---

# RP-21 — Test-debt triage

> **Mode:** standard with blocking architecture-guard checks. **Status:** CONDITIONAL.

Capture a baseline before test changes. Record timezone, locale, OS, API level, stable failure artifacts, and deterministic/flaky classification. Classify failures as ENV, OUTDATED, REAL, or INTENTIONALLY-RED; guard failures always block closure.

Assign each test one owning RP. For every outdated test record intended architecture behavior, current behavior, fix/delete/preserve decision, registry ID, and reviewer. Receipt lifecycle failures exposing missing side effects remain regressions until RP-12 decides otherwise. Replace empty recurring stubs with current contract tests.

Run owning-RP targeted tests after each change, then a final full-suite attribution pass. Do not declare restoration with unattributed failures or weakened assertions.
