# A2 — backup denial contract adjudication

Status: source-grounded author decision; implementation and focused regression tests authored, with author static inspection recorded in CL-21-self-review.md. Independent privacy guardian approval and live validation remain PENDING. This is not a new privacy-policy waiver.

## Established contract

- PrivacyDecision.Denied means the capability is disabled by policy. The existing central PrivacyBlocked mapper and UI adapter distinguish ENCRYPTED_BACKUP_DISABLED from an operational failure.
- PrivacyDecision.FailClosed, a gate evaluation failure, or an unspecified typed denial retains PRIVACY_GATE_FAILURE. Neither outcome authorizes execution.
- An unrelated operational failure remains a bounded generic error, not a fabricated capability-disabled state. SAF SecurityException retains its existing generic privacy-failure presentation.
- Arbitrary exception or gate text must not become a reason code or diagnostic payload.

## Current-source findings

The two historical failing tests were recorded against older fixtures. Current BackupRestoreViewModelPrivacyDenialTest explicitly supplies ENCRYPTED_BACKUP_DISABLED for policy-denial cases and separately asserts the generic default. Do not weaken those assertions or treat the historical test result as a current run.

At pre-implementation inspection, the live createCostBackup producer distinguished Denied from FailClosed for operation diagnostics, then constructed PrivacyDeniedException without a reason code, losing that distinction at the UI boundary. The ViewModel transported the exception message, while PrivacyDeniedException documented—but did not enforce—that its constructor argument was a controlled code. The authored patch addresses these boundaries without changing admission policy.

## Bounded implementation decision

1. Preserve the specific controlled code for an actual Denied outcome at createCostBackup's encrypted-backup gate. Preserve PRIVACY_GATE_FAILURE for FailClosed. Do not add or relax a gate, alter restore admission, or change maintenance sequencing.
2. Give PrivacyDeniedException an explicit bounded reasonCode property, normalize unrecognized input to PRIVACY_GATE_FAILURE, and retain a bounded message for compatibility. Keep its existing SecurityException inheritance and constructor parameter names.
3. Read the typed property in BackupRestoreViewModel instead of using Throwable.message as the transport contract. Sanitize the two associated backup/restore failure logs to exception class only.
4. Add targeted transport/normalization and producer tests; preserve the existing policy-denial, unspecified-failure, SAF-denial, and permitted-path assertions.

Allowed files: PrivacyDeniedException, BackupRestoreViewModel, the encrypted-backup denial branch in DatabaseBackupRepositoryImpl, focused existing/new tests, and this campaign's records. Do not refactor unrelated backup logic, raw-export authorization, cancellation paths, or UI presentation. CL-15 will separately own the database-stats failure boundary in these same files.

## Validation

Human-run policy remains in force. Required evidence includes the focused exception/producer tests, BackupRestoreViewModelPrivacyDenialTest, related privacy mapping tests, compilation, and the pending independent privacy review. No runtime PASS is claimed here.
