# Test-suite audit — production findings catalogue (release follow-ups)

Compiled 2026-10-19 by a read-only scout from `test-suite-audit-handoff.md` and the slice maps; persisted by the orchestrator.
"L<n>" = line in `workflows/active/test-suite-audit-handoff.md` as of 2026-10-19 (the handoff grows; search the quoted text if a line has moved).

**Status: documented only. No production fix has been made or approved.** Every fix needs explicit user approval, and any fix is strict mode (privacy, Room, money and workers are involved). Severity is the scout's judgement (High = privacy leak into persistent storage/export, or a money miscalculation users see; Medium = fail-open risk or dead protective code; Low = cosmetic or inconsistent).

## Summary
- 52 findings: High 11, Medium 26, Low 15. Three are already fixed in this worktree (LIFE-05, DIAG-11, LOG-01), MISC-04 is fixed in tooling, and a related path of DIAG-10 is fixed.
- Top 3 most serious:
  1. DIAG-01/02: raw exception messages go into persistent diagnostics stores that `.costbackup` exports include, even in redacted mode.
  2. PRIV-01: the plaintext merchant key in `transactionFingerprint` is kept indefinitely and exported.
  3. MONEY-01/02: `extractTax` returns the VAT rate as the tax amount, and `parseAmount("1e10")` returns 110.0.
- Candidates for the release gate (High): DIAG-01, DIAG-02, DIAG-05, PRIV-01, PRIV-09, MONEY-01, MONEY-02, MONEY-06, MONEY-08 (plus MONEY-04 if the tax feature flag is ever enabled).

## Privacy & diagnostics

| ID | Area | Sev | Finding | Evidence | Suggested fix |
|---|---|---|---|---|---|
| DIAG-01 [P1] | Diagnostics stores | High | Three diagnostics stores keep exception messages that are only pattern-sanitized: the Room `pipeline_diagnostic_events` writer, `OperationRunRecorder` (`operation_run_events`) and the DataStore safe sink. Free text survives, for example MerchantNormalizer's "Failed to create or retrieve merchant: $key", SQLite messages and currency tokens. These tables are in `.costbackup` exports, and `ExportAnonymizer` does not scrub them, even in redacted mode. The policy doc (§73-82) accepts this, which conflicts with the bounded-reason-code rule in CLAUDE.md. | L730; L196 (`DiagnosticEventWriter.kt:67-68`); L714, L737. `RoomDiagnosticEventWriterPrivacyTest` no longer pins message storage (KNOWN GAP P1 comment). | Store class name + reason code only; scrub in redacted exports; update the policy doc |
| DIAG-02 [diag P1] | Operation runs | High | `CompositeOperationRunRecorder.kt:69` and `OperationRunRecorder.kt:161` pass the raw `e.message` into Room `operation_runs.errorSummary` (caller: BANK_SYNC). `OperationRunRecorder.kt:285` (`summary ?: sanitizer...`) skips the sanitizer whenever a summary is supplied. | L206, L749. No test pins it. | Bounded `DiagnosticReasonCode`; always sanitize |
| DIAG-03 [diag P2] | Diagnostics sinks | Medium | The DataStore sink (`excMsg`/`wfMsg`, :127-130) and the `RestoreDiagnosticsSink` journal (:62, :75) keep exception messages sanitized by regex only, so SQL and notification text survive. | L206 | Bounded codes only |
| DIAG-04 [diag P3] | Restore diagnostics | Medium | `runCatching` in `RestoreDiagnosticsSink` (:104, :124) swallows CancellationException (MIT-034 gap). | L206 | Rethrow CE |
| DIAG-05 | Diagnostics metadata | High | `EventMetadataSanitizer` does not block the `amount`, `merchant` and `description` keys, although the policy forbids merchant names and amounts. Amounts are persisted via SafeEventMetadata in BudgetRepository :667/:706 and PlannedExpenseRepository :114. | L721-722, L731. Pinned (labelled policy gap) by `EventMetadataSanitizerTest` K65-K67. | Block or hash these keys |
| DIAG-06 [P3] | Diagnostics metadata | Medium | Numbers of 12 or more digits bypass digit redaction (rows V06 and C12 pin a 15-digit number). Safe hash slots accept all-digit values (H33). `putHashed("sender")` is stored as [REDACTED], so its hash is lost. | L722, L732, L714, L737. Pinned by `EventMetadataSanitizerTest` V06/C12/H33. | Reject all-digit values in hash slots; align the hash-key sets |
| DIAG-07 [P2] | MIME classification | Low | Production maps "Unsupported MIME type: X" to MIME_UNKNOWN first, so MIME_UNSUPPORTED is unreachable. The intake `mimeType` is forwarded with only a 128-character cap. | L728, L731, L714 | Fix the classification order or use typed codes; map mimeType to a fixed set |
| DIAG-08 | Policy doc | Low | Policy doc :79 on the hash-key sets does not match the code. | L732 | Doc fix |
| DIAG-09 | Sinks | Medium | `FileWorkerTerminalDiagnosticSink` persists caller strings verbatim. `WorkerCheckpointBlockedException.reasonCode` is passed raw at `WorkerExecutionGuard.kt:208/215/412/419` (also `WorkerReasonCodes.kt:17/24/33`, and `WorkerRunLogger.kt:90` into DB `terminalDiagnosticCode`). | L215, L749 | Fail-closed normalisation at the sink; sanitize at the guard |
| DIAG-10 | Scheduling | Medium | `WorkerSpecScheduler.kt:170-181` stores `e.message` under the `error` key. | L733 | Bounded code (see also LOG-02) |
| DIAG-11 [FIXED] | Cancellation | Low | `SafeSinkOperationRunHandle.emitSafeEvent` swallowed CancellationException; fixed in B2-4. Leftovers: terminal emits are not under NonCancellable (N5), and `runOperation`'s `NonCancellable { runCatching { run.success() } }` swallows CE. The `cancellation.json` baseline still needs tightening (B1R-4). | L114, L204, L206, L748. Test: `SafeSinkOperationRunHandleTest`. | Tighten the baseline at merge |
| PRIV-01 [PF-1] | Bank statement import | High | `bank_statement_import_items.transactionFingerprint` holds a PLAINTEXT merchant key (`BankStatementLifecycleProcessor` :626/:698/:734/:783). Retention nulls only `merchant`, and `ExportAnonymizer` :384-397 keeps the fingerprint, so the merchant identity is kept indefinitely. | L695, L697. Pinned by `RetentionTargetCoverageGapTest` (KNOWN DEFECT PF-1). | Hash at write, or clear in the redaction UPDATE and in exports |
| PRIV-02 [PF-2] | Retention | Medium | `pending_reviews.notificationTitle/Text` are purged on the fixed 30-day `textRedactionCutoff` (`DataRetentionWorker` :124/:150), not on the user's notification window. If the user picks less than 30 days, they outlive `raw_notifications`. | L698 | Use the user window |
| PRIV-03 | Pending review | Medium | `PendingReview.suggestedMerchant` is persisted whatever the storage mode (`NotificationProcessingPipeline` :383/:491/:1590); `allowParsedMerchant` is not applied. | L687 | Apply the mode [superseded 2026-10-21: flags removed in 1.0.2, PRIV-03] |
| PRIV-04 [R1/F1] | Raw-storage builders | Medium | The Receipt, Email and Bank `*PersistencePayload` builders have no production callers, and the Notification builder is referenced only from dead code. The live paths repeat the `when(mode)` logic in at least 4 places (NotificationCaptureService, NotificationRepository, NotificationIntakeWorker :500-533). No test exercises those switches. | L644, L679-680, L683; live-path tests deferred to after integration (B6-8c, L718). | Wire in the builders or add a live-path matrix test |
| PRIV-05 [R2] | REDACTED contract | Medium | The REDACTED contract is inconsistent. NotificationPersistencePayload stores extras as `{"redacted":true}`, but `sanitizeNotificationExtras` returns null (and has no caller). Null input maps to "[REDACTED]" for notification, email and OCR text, but stays null for bank text. `sanitizeRawOcr` returns "" where `sanitizeRawOcrNullable` returns null. | L625, L645, L660. Pinned by `RawContentSanitizerLiveTest` (KNOWN INCONSISTENCY R2). | One null-input rule |
| PRIV-06 [F5] | Dead sanitizers | Low | The dead `sanitizeEmailMessageId`(+WithHash) return a constant "[REDACTED]"; wired to the unique `emailMessageId` index they would collide. | L686 | Delete or return null |
| PRIV-07 [R4/F3/F8] | Notification extras | Low | `NotificationCaptureService.kt:662/:846` put an unescaped `e.message` JSON into `extrasJson` under STORE_RAW (unreachable today). | L208, L647, L684 | Use a literal |
| PRIV-08 [R5] | Bank API | Medium | `BankApiIntegration.kt:407` calls `mapTransactionToExpense` without `rawModeOverride`. | L648 | Pass the override |
| PRIV-09 [R6/F4] | Hashing | High | `DefaultSensitiveHashingService` derives the HMAC key from the public purpose string, so the hashes can be brute-forced offline. | L649, L685 | Secret key (AndroidKeyStore) |
| PRIV-10 [c/N6] | Privacy settings | Medium | A 0-byte privacy settings file loads as FirstRunDefault, which is permissive (capture on, STORE_RAW). Deliberately left unchanged for now because of lock-out risk. | L66, L188, L191, L749 | Release/privacy decision |
| PRIV-11 | Privacy settings | Medium | `observeLoadState` rethrows non-IO errors (:93), and `getLoadState` stores the raw `e.message` as the reason (:108). | L188 | Bounded reason code |
| PRIV-12 | Receipt images | Medium | No retention policy covers receipt images (`imagePath` and the file), or `suggestedAccountName` and lat/long in `pending_reviews`. | L700 (RF-5) | Add retention targets |
| PRIV-13 | Retention routing | Low | Only a source scan covers the 10 non-D14 retention targets with non-default settings; no behavioural routing test exists. | L699 (RF-3) | Add a behavioural test |
| PRIV-14 [F2] | Dedupe cache | Low | The CE branch (`NotificationCaptureService` :735-753) does not release the dedupe key if the exception comes before intake (practically unreachable). | L207-208 | Release the key in the CE branch |
| PRIV-15 | Diagnostics file | Low | `filesDir/diagnostics` is not cleaned when the user deletes data (bounded codes, about 1 MB). | L215 | Clean on deletion |

## Retention & storage

| ID | Area | Sev | Finding | Evidence | Suggested fix |
|---|---|---|---|---|---|
| RET-01 | Retention worker | Low | `DataRetentionWorker` :252/:324/:447 log the exception message and stack via `Log.e/w`; these calls are not stripped in release builds. | L667 | Log the class name only |
| RET-02 | Retention docs | Low | The GR-14u51b "ONLY test" wording is stale. | L667 | Doc fix |

(The six retention targets that had no row-level purge test are now covered by `RetentionTargetCoverageGapTest`; that was a test gap, not a production finding.)

## Money & currency

| ID | Area | Sev | Finding | Evidence | Suggested fix |
|---|---|---|---|---|---|
| MONEY-01 | Receipt parser | High | For `Φ.Π.Α. 24,00%: 9,68 €`, `ReceiptParser.extractTax` returns the VAT RATE (24.00) instead of the tax amount (9.68). | L373, L376-378. Pinned by `ReceiptParserOcrPatternsTest` (KNOWN DEFECT). | Fix the tax pattern |
| MONEY-02 | Amount parsing | High | `AmountUtils.parseAmount("1e10")` returns 110.0, a plausible but wrong amount. | L376, L378, L388, L399. Pinned by a KNOWN QUIRK row in `AmountUtilsTest`. | Reject exponent notation |
| MONEY-03 | Amount parsing | Low | A 1001-digit string parses to Infinity (`isValidAmount` rejects it). | L376, L378 | Reject in the parser |
| MONEY-04 | Tax | Medium | DI binds `DemoTaxRateProvider`, which returns PERCENT values (GR 24.0), but `TaxEstimator.kt:136` treats the rate as a FRACTION. So the GR VAT portion of 1240 comes out as 1190.40 instead of 240. The provider is also queried with the settings country instead of the config country. The feature is behind a DEFERRED_DESIGN flag. | L120, L186, L225 | Fix the units; use the config country |
| MONEY-05 | Currency staleness | Medium | The legacy `convert` treats future-dated rates as always fresh (one-sided check, :185/:216), while `convertOutcome` uses abs. | L615 | Apply abs consistently |
| MONEY-06 | Currency rates | High | Nothing checks on read that a stored rate is finite and positive (:191/:223/:287/:311/:478), so a corrupt 0/NaN rate would flow into totals. | L616 | Validate on read |
| MONEY-07 | Currency | Low | `convertMultiple` reports an INVALID_* currency as failure type MISSING_RATE. | L617 | Map to the correct type |
| MONEY-08 | Groups | High | `GroupBalanceCalculator` :42-51 sums expenses without a currency filter, and silently falls back to EUR when the group is missing. | L623 | Filter by currency; fail closed |
| MONEY-09 | Totals | Low | Category percentages for 3×33.33 sum to 99.99; the invariant test passes only within its ±0.05 tolerance. | L504 | Largest-remainder rounding |

## Forecasting / analytics

| ID | Area | Sev | Finding | Evidence | Suggested fix |
|---|---|---|---|---|---|
| FORE-01 [P5] | Synthesis | Medium | The `SynthesisEngine.synthesize` fallback (:198) builds `ForecastComponents` from the raw history. If the history contains NaN or infinity, `require(all finite)` throws an IllegalArgumentException that escapes `synthesize`. Real bug, low likelihood. | L575 | Sanitize before building the fallback |
| FORE-02 [P1] | Discretionary | Medium | `monthlyRecurringTotal` subtracts every non-IRREGULAR pattern, including confidence <0.70 ones that are never added back. WEEKLY and BIWEEKLY subtract 4.33× and 2.17× but project only one occurrence. | L576 | Subtract only what is projected |
| FORE-03 [P2] | Goals | Medium | Undated STRICT goals reserve 100% each month. | L577 | Define a monthly share |
| FORE-04 [P3] | Committed | Medium | Overdue PLANNED or unpaid MUST items drop out of the committed amount. | L578 | Keep them |
| FORE-05 [P4] | Risk | Medium | If obligations are far above the limit while spending is on pace, the risk shows HIGH instead of CRITICAL. | L579 | Escalate |
| FORE-06 [P6] | Insights | Low | Insight counts include funded or excluded goals, and MUST items outside the window. | L580 | Filter |
| FORE-07 [P7] | Rounding | Low | The 4.33 and 2.17 multipliers are rounded. | L581 | Exact factors |
| FORE-08 [P8] | Goals | Low | The goal reserve uses a full month's share in mid-month. | L582 | Prorate |
| FORE-09 | Runway | Low | With deposits below spending and no budget, the runway card is hidden instead of showing CRITICAL/0 days (`ComputeDashboardWidgetsUseCase.kt:1327`). Product follow-up. | L120 | Product/UX decision |

## Lifecycle & groups

| ID | Area | Sev | Finding | Evidence | Suggested fix |
|---|---|---|---|---|---|
| LIFE-01 | Group delete | Medium | `deleteGroupAtomic` collects `linkedExpenseIds` outside the transaction (:969), so a race can leave shared flags uncleared. | L202, L210 | Collect inside the transaction |
| LIFE-02 | Group delete | Low | Deleting a non-existent group (count 0) still writes a `BULK_UPDATED` event. | L202, L210 | Skip on count 0 |
| LIFE-03 | Group expense | Medium | `addExpenseWithLink` lacks a `GroupExpenseAtomicRollback` catch (:591-595) and returns "Failed to add expense: null". | L210, L749 | Add the catch and a bounded message |
| LIFE-04 | Group errors | Medium | Raw exception text reaches the UI through several error results: GroupCreationResult.Error (SharedExpenseGroupsViewModel :175), GroupExpenseCreationResult.Error (GroupTransactionCoordinator :594, :922) and RemoveSharedExpenseMemberResult.Error (SharedExpenseManager :159). | L202, L209, L623, L749 | Bounded reason codes |
| LIFE-05 [FIXED] | Notification dedupe | Medium | A step-5 privacy denial poisoned the dedupe cache for 5 s; fixed in B2-2c. | L64, L110, L223. Test: `NotificationCaptureServiceBlockedDecisionTest`. | Done |
| LIFE-06 | Docs / legal path | Low | For CREATE group, `LEGAL_PATHS.md:643-652` lists only `createGroupWithMembersAtomic`, but the live path is `createGroupWithMembers` via `GroupsRepositoryImpl.kt:105`. There is also drift at LEGAL_PATHS :726. | L202, L210 | Update LEGAL_PATHS |
| LIFE-07 | Recurring | Medium | `RecurringRuleLifecycleCoordinator` calls `lifecycleEventDao.insert` directly (MIT-043), although LEGAL_PATHS :361 marks this FORBIDDEN with no debt note. | L116, L221 | Use the lifecycle writer or document the debt |
| LIFE-08 | Recurring guard | Medium | The rule-DAO detector misses accessor chains, and the hand-maintained mutator list misses `updateSubscriptionCategory`. There is no ratchet on MIT-043. | L221 (deferred to B7-K) | Derive the list from DAO annotations; add a ratchet |
| LIFE-09 | Workers | Low | `WriteBarrier` :346 uses `runCatching { }.getOrElse { false }`, which fails open on error. | L221 (N9) | Fail closed |
| LIFE-10 | Backup | Low | In the DEBUG-only `importDatabase`, cancelling after `exit()` re-runs the non-idempotent `failJournal` (`RestoreJournal.kt:728-733`). L204 and L206 disagree about this. | L204, L206 | Make it idempotent |
| LIFE-11 | Docs | Low | `evidence-map.md:20` cites `BackupRestoreMoneyIntegrityScenarioTest` as restore evidence (P-07), but that test never ran a restore. | L237 | Fix the release doc |

## Logging & other

| ID | Area | Sev | Finding | Evidence | Suggested fix |
|---|---|---|---|---|---|
| LOG-01 [FIXED] | AI briefing | Low | `DashboardBriefingResponseParser` logged raw model output; it now logs a bounded code. | L423, L429 | Done |
| LOG-02 | AI logging | Low | About 19 `Timber.w/e(e, …)` sites in data/ai log throwables (debug-only today). `Log.e(…, e)` in `WorkerSpecScheduler` is not gated to debug builds. | L423, L429, L733 | Bounded-code sweep + pii_logging guard rule |
| LOG-03 | Logging | Low | `Timber.e` logs the full throwable (planted in DEBUG only; `AppStartupCoordinator.kt:680-685`). | L215 | Class name only; architecture test |
| MISC-01 | Guards | Medium | `CancellationSafetyArchitectureGuardTest.kt:118` allowlists the whole `CompositeOperationRunRecorder.kt`, which hid the CE bug. Its frozen 2026-09-15 clock means expiry dates never fire, and 13 entries are already past 2026-10-01. | L56, L205 | Narrow the allowlist; real clock (B7/B1R) |
| MISC-02 | Guard time bomb | Medium | 16 `WriteBarrierArchitectureGuardTest` exemptions (and HANG-003) expire 2026-10-31, so the test goes red on 2026-11-01. The release branch has already renewed or fixed them. | L58, L81-84 | Re-check after the merge |
| MISC-03 | Sanitizer docs | Low | The `SourceTextSanitizer.kt:94-98` KDoc says it keeps quote delimiters, but the code blanks them. | L220 | Doc fix |
| MISC-04 [FIXED] | Tooling | Low | An all-skipped pytest selection reported PASS; fixed in B4-P1b. | L203, L219 | Done |
| MISC-05 | Release gates | Low | `runner-protocol-tests` is not on the release final-acceptance gate list. | L333 | Add it |
| MISC-06 | Crypto | Low | `SecureKeyStorage.migrateFromBuildConfigIfNeeded` is untested on the JVM (no seam). | L118 | Add a seam or an instrumented test |
