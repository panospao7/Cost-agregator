# CL-21 and A2 author static self-review

VERDICT: PASS (author static inspection only, before subsequent CL-15 edits).

This is not independent reviewer/privacy-guardian approval, runtime validation, or cluster closure. The single-session request does not supply a new review waiver. Re-review overlapping backup files after CL-15.

## Changed boundary inspection

- CloudQueryInterpretationService now requires CloudPayloadPolicy. Its privacy gate precedes preparation; the same prepared payload supplies outbound text and audit provenance. Policy failure returns a bounded unsupported reason without HTTP dispatch; cancellation propagates. Test-only convenience constructors remain fail-closed. Network retry and response handling are unchanged.
- Five production-bound wrappers now instantiate HybridRouter: categorization, dashboard briefing, review explanation, query interpretation, and receipt-item categorization. Capabilities, delegate methods, nullable/result contracts, disabled fallbacks, constructor ordering, and per-request settings reads are preserved.
- AiModule bindings are unchanged. Specialized dedupe failover and SmartReceiptAssistService image/retry orchestration are not replaced by a simpler dispatcher. The unused HybridReceiptAssistService is not represented as a production caller. Existing specialized dedupe exception/logging risks remain follow-up debt, not newly approved behavior.
- A2's established contract is recorded in A2-denial-contract.md. The live encrypted-backup producer preserves a policy denial's specific code and keeps operational FailClosed generic. The exception now provides a bounded typed property; the ViewModel consumes it, and the two associated failure logs record class names rather than throwable payloads. Gates, maintenance admission, and restore authorization are unchanged.
- A5's decimal/phone distinction and all three listed A1 cancellation sites were already repaired through CL-05. Current source, regression corpus, and commit provenance were checked; no duplicate edits were made.
- No schema, policy baseline, allowlist, guard exception, or unrelated routing change was made.

## Regression coverage authored

- CloudQueryPreparedPayloadTest: eight provider-boundary tests, including captured request/audit metadata, blocked gates, missing keys, preparation failure/cancellation, and audit failure/cancellation before HTTP.
- HybridRouterIntegrationTest: five wrapper tests parameterized across all four existing routes (20 cases), checking exact selected delegate and no alternate calls.
- HybridRouterTest: four tests for fresh settings, cancellation across routes, settings failure, and router-decision failure.
- Existing CloudQueryInterpretationServiceTest constructors now receive an explicit fail-closed redacting policy; existing behavior assertions remain.
- A2: three exception-code tests and one ViewModel test exercising both backup and restore with malformed reason text and safe diagnostics. Two existing producer tests additionally assert typed denial codes and bounded messages. Existing denial/default/SAF/permitted-path assertions were not weakened.

## Validation

- git diff --check on tracked affected files: PASS (whitespace only).
- Compilation, unit tests, instrumented tests, static guards: NOT RUN. The campaign's human-run validation policy remains in effect.
- Independent implementation/privacy review: PENDING.

On a quiescent checkout, issue serial commands using the blocking wrapper:

    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*CloudQueryPreparedPayloadTest'

Use the same command with each remaining filter: *CloudQueryInterpretationServiceTest, *HybridRouterIntegrationTest, *HybridRouterTest, *HybridServiceDelegationTest, *HybridReceiptItemCategorizationServiceTest, *PrivacyDeniedExceptionTest, *DatabaseBackupRepositoryImplTest, *BackupRestoreViewModelPrivacyDenialTest, *PrivacyCapabilityPolicyTest, *CompositePrivacyGateTest, *BackupPrivacyGateOwnershipTest, and *KeystoreInstallationSecretHashingTest.

    pwsh scripts/vrun.ps1 -Worktree . -Profile compile

Read durable result.json records before reporting runtime outcomes. CL-22 still owns the broader provider-boundary test/guard hardening and full static-guards violation report. None of those gates is replaced by this self-review.
