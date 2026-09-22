# CL-18 Implementation Spec — Privacy gate fail-closed and cancellation semantics
Provenance: 2026-09-22; pin 37601232 verified; astra xhigh Stage-3 session; source cluster-map.md.

## GATE

- **UPDATE 2026-09-22 (post-spec): GATE LIFTED.** All member findings verified 13/13 CONFIRMED by independent adversarial pass (ZCode/GLM-5.3, different model+session from finder) in `../verification-2026-09-22-wave1.md`; journal PHASE-2-VERIFICATION 2026-09-22T17:3x. Implementation may proceed.
- Implementation is blocked until CA-P-07-001 and CA-P08-001 (both P1, unverified) are independently revalidated. The cited pin evidence is not verification approval.

## Context for the coder
- Backup export is entered from `BackupRestoreViewModel.createCostBackup` through both `DatabaseBackupRepositoryImpl.createCostBackup` overloads and the shared `runCostBackupExport` pipeline. The privacy decision must stop all snapshot, maintenance, and destination writes when execution is not allowed.
- `CompositePrivacyGate.check` is a suspend boundary used by cloud providers, workers, and other privacy callers. Coroutine cancellation must propagate so callers retain retry/cancellation semantics instead of receiving a durable-looking privacy failure.
- Root cause: the export checks only `Denied`, while `PrivacyDecision.blocksExecution()` already defines both `Denied` and `FailClosed` as blocking; the composite catches `CancellationException` inside `catch (Exception)`.

## Pre-implementation checks (coder MUST run, in order)
1. `git diff 37601232..HEAD -- app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt` — read every hunk.
2. Re-read `CompositePrivacyGate.check`, `PrivacyDecision.blocksExecution`, `DatabaseBackupRepositoryImpl.runCostBackupExport`, and both public `createCostBackup` overloads at current HEAD before editing.

## Legal path constraints
- All production backup exports remain on `DatabaseBackupRepository.createCostBackup(...)` → `DatabaseBackupRepositoryImpl.runCostBackupExport`; do not add a caller-specific enum comparison in ViewModels or destination writers.
- Privacy decisions must use `PrivacyDecision.blocksExecution()`; `FailClosed` is unconditional blocking. Do not write maintenance mode, snapshot files, encrypted bundles, or SAF destinations after a blocking decision.
- Cloud/worker callers continue to receive the composite decision through `PrivacyGate`; no direct DAO or alternate gate path may be introduced.

## Work items
### WI-1 — Block every export entry point on FailClosed (CA-P-07-001, P1, unverified)
- Location: `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` — `runCostBackupExport(...)` ≈L580-615 @pin 37601232; public `createCostBackup(password, includeReceiptImages, redacted, privacyMode)` and `createCostBackup(destination, password, includeReceiptImages, redacted, privacyMode)` ≈L528-579.
- Defect: the shared pipeline returns failure only for `PrivacyDecision.Denied`; `FailClosed` proceeds into `BACKUP_EXPORTING`, WAL/snapshot work, and the destination writer.
- Change: immediately after `privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP, mapOf("operation" to "create_costbackup"))`, branch on `encryptedDecision.blocksExecution()`. For either `Denied` or `FailClosed`, record only the existing bounded privacy reason code (`PRIVACY_DENIED` for explicit denial or `PRIVACY_FAIL_CLOSED` for fail-closed) in the operation run, return the existing typed `PrivacyDeniedException(ENCRYPTED_BACKUP)`, and exit before `maintenanceOperationRunner.enterAndDrain`. Preserve both overloads through this one helper; do not duplicate the check in UI callers or destination-specific writers. Do not interpolate the decision reason into logs or durable free text.
- Acceptance criteria: Allowed reaches maintenance and can invoke either destination writer; Denied and FailClosed both return failure; neither enters maintenance, checkpoints WAL, creates a snapshot, opens a SAF destination, or writes a bundle; cancellation thrown by the gate is still propagated (WI-2).

### WI-2 — Rethrow cancellation before generic gate conversion (CA-P08-001, P1, unverified)
- Location: `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt` — `check(capability, context)` ≈L20-75 @pin 37601232.
- Defect: `catch (e: Exception)` catches `CancellationException` and converts coroutine cancellation into `PrivacyDecision.FailClosed`.
- Change: add the cancellation branch as the first catch branch (`if (e is CancellationException) throw e`) before the existing generic exception conversion. Keep generic failures fail-closed with `PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE`, logging only exception class name and capability name. Do not call `auditLogger.logDecision` for a cancellation that is rethrown; normal decisions and generic failures retain current audit behavior.
- Acceptance criteria: a gate that throws `CancellationException` causes `CompositePrivacyGate.check` to throw the same cancellation; a gate that throws another exception returns `FailClosed(PRIVACY_GATE_FAILURE)`; no cancellation is logged as a terminal privacy decision.

## Tests
- WI-1: update `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` (or the existing backup repository fixture) with: Allowed file export; Allowed SAF export; Denied blocks before maintenance; FailClosed blocks before maintenance; privacy decision blocks both overloads; destination writer is never invoked on either blocking decision; operation failure uses controlled reason only.
- WI-2: add/update a focused `CompositePrivacyGate` test class under `app/src/test/java/com/yourname/expensetracker/domain/privacy/` with: one delegate throws `CancellationException` and the exact exception propagates; one delegate throws ordinary `IllegalStateException` and result is FailClosed with `PRIVACY_GATE_FAILURE`; no audit decision is emitted for cancellation; existing Denied/FailClosed/NotApplicable composition remains unchanged.

## Validation
- Use `validation-runner` profile `targeted-unit-test` first for the named backup repository and composite gate tests.
- Then use `validation-runner` profile `compile`.
- The coder must not invoke Gradle directly.

## Blast-radius fence
- ALLOWED: `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt`, `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt` only if a type-level helper is strictly required, `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`, and the focused tests named above.
- FORBIDDEN: `RestoreMaintenanceMode.kt`, `RestoreJournal.kt`, `DatabaseWriteBarrier.kt`, restore/import state handling (CL-19); backup image/privacy-mode contract (CL-20); cloud payload routing (CL-21); UI-only privacy comparisons; unrelated provider or worker edits.

## Review gate
- `privacy-security-guardian`: verify every export entry point reaches the shared blocking check, FailClosed cannot enter maintenance or open a destination, cancellation is rethrown before generic conversion, and no raw decision reason/exception message is logged or persisted.

## Privacy constraints
- Controlled reason codes per site: `runCostBackupExport` operation-run failure metadata: `PRIVACY_DENIED` or `PRIVACY_FAIL_CLOSED`; `CompositePrivacyGate` generic exception: `PRIVACY_GATE_FAILURE`; no caller may copy arbitrary `PrivacyDecision.reason` into diagnostics.
- Never log or persist gate exception messages, stack traces, capability context payloads, passwords, URIs, database paths, or backup contents.
- `EventMetadataSanitizer` is fallback for bounded text only; deletion of unneeded reason text is preferred.

## Out of scope
- CA-P-07-006/CA-P-07-008 restore journal sanitization/logging: CL-17.
- CA-P-07-003/004/005 restore state durability and CA-P-10-001 bank barrier caller: CL-19.
- CA-P-07-007 backup image mode contract: CL-20.
- CA-P08-002/P08-003 cloud policy and provider error handling: later clusters/CL-17 as mapped.
