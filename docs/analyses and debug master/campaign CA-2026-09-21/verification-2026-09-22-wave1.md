# CA-2026-09-21 — PHASE-2 VERIFICATION: WAVE 1 GATE FINDINGS

Date: 2026-09-22
Pin: `37601232b9778170c57a656a245b199ab6d7d965` verified before and during pass (`git rev-parse --short HEAD` = 37601232; `git diff --stat 37601232..HEAD -- app config scripts` empty)
Method: adversarial re-read of every cited line at the pin. Finder ≠ verifier: findings authored by
astra direct sessions; this pass executed by ZCode/GLM-5.3 (different model + session). 8 of 13
verified by direct in-session reads; 5 verified via two read-only evidence collectors
(agent_c6d84ccc / agent_7f15012c) whose verbatim quotes were judged in-session.

RESULT: **13/13 CONFIRMED, 0 REFUTED.** 3 findings CONFIRMED-WIDER than reported,
1 CONFIRMED-RECALIBRATED (scope narrower on one sub-claim). All severities endorsed.

**Consequence: all Wave-1 GATEs lift.** CL-01 members were already verified (P-01 pass). Wave 1 is
cleared for coder sessions.

---

## CL-19 — Restore/backup fail-closed state machine (5/5 CONFIRMED)

### CA-P-07-003 (P0) — CONFIRMED
`RestoreMaintenanceMode.writeMode` L206-213: `prefs.edit().putString(...).commit()` — Boolean result
ignored (L210), `_modeFlow.value = mode` publishes unconditionally. `readMode` L196-204:
`IllegalArgumentException` → `Mode.NORMAL` (fail-open on corrupt persisted value).
`DatabaseWriteBarrier.checkWritesAllowed` L15-24 blocks only when mode ≠ NORMAL — so a failed
commit or corrupted value admits production writes during a destructive operation. Chain complete
end-to-end. (Note: `enterCriticalRecoveryRequired` L119-123 has the same unchecked-commit pattern.)

### CA-P-07-004 (P0) — CONFIRMED, WORSE THAN REPORTED
`JournalEntry.fromJson` L111-117: unknown state → `JournalState.PREPARING`. `readJournal` L482-491:
any parse exception → null. `checkAndRecover` L695-712: null → `NoAction`; PREPARING →
`CleanedNonDestructive` → **`deleteJournal()` — the corrupt journal is destroyed, erasing evidence**.
`AppStartupCoordinator.checkRestoreJournal` L114-204: NoAction takes the normal path, then
L195-200 auto-resets any mode except `CRITICAL_RECOVERY_REQUIRED` to NORMAL. Net: a truncated or
unknown-state journal during SWAPPING/VERIFYING → writes resume against an unknown DB and the
journal is gone. Both reported and unreported paths confirmed.

### CA-P-07-005 (P0) — CONFIRMED
`RestoreJournal.writeJournal` L497-521: entire body (mkdirs, fsync, rename) inside
`catch (e: Exception) { Timber.e(...) }` — never rethrows, returns Unit, so callers structurally
cannot detect failure. Rename-failure fallback L513-516 also unchecked. Call sites confirmed:
`DatabaseBackupRepositoryImpl.restoreCostBackup` beginJournal L851-855, writeJournal L907-909,
transitionTo STAGED L910, SAFETY_BACKUP_CREATED L1056-1060, **SWAPPING L1063** — all proceed
unconditionally. Restore reaches the destructive swap with no durable journal on fsync/rename
failure.

### CA-P-07-006 (P0, privacy) — CONFIRMED
`resetDatabase` catch L2800: `restoreJournal.failJournal(journalEntry, e.message ?: "Reset failed")`
— raw exception text. `failJournal` L624-629 stores it in `entry.error` unsanitized.
`RestoreJournalImporter` L165-191: `errorSummary = entry.error` (L173) written into `OperationRun`
(L190) — queryable Room ledger, export surface. Violates repo rule "never persist SQL exception
messages". The structured `resetEvents.event` sink alongside uses reason codes — the journal field
is the leak. P0 endorsement: privacy-class breach persisting to DB per campaign rubric.

### CA-P-10-001 (P1) — CONFIRMED
Full chain: during restore, `BankApiIntegration.syncTransactions` L225 barrier check throws →
caught L226-239 → `BankSyncOutcome.Blocked(RESTORE_BLOCKED)` (correct). Coordinator
`syncConnection` L154-157 calls `persistOutcome` unconditionally;
`Blocked.toTerminalSyncStatus()` → `SyncStatus.FAILED` (`BankSyncOutcome.kt` L125-133);
`persistOutcome` L169-188 writes `bankConnectionDao.updateSyncStatusOnly(connectionId, FAILED)` —
a direct DAO write with **no barrier check, during the restore that denied the sync**. The inline
comment ("secondary UI state") shows awareness of the write but no guard.

### CA-I-01-001 (P2) — CONFIRMED
`AppStartupCoordinator.resumeAssetsIncompleteRecovery` L385-393: successful safety rollback →
`restoreMaintenanceMode.reset()`. `reset()` (`RestoreMaintenanceMode` L160-163) = `writeMode(NORMAL)`
only — `scheduleAllWorkers()` exists only in `exit()` L144-151. After rollback: barrier open, all
periodic workers (retention, matching, etc.) remain cancelled until next app restart.

## CL-18 — Privacy gate fail-closed/cancellation (2/2 CONFIRMED)

### CA-P-07-001 (P1) — CONFIRMED
`runCostBackupExport` L611: `if (encryptedDecision is PrivacyDecision.Denied)` — FailClosed falls
through to maintenance entry + snapshot + bundle write. `PrivacyDecision.blocksExecution()`:
documented contract "Both [Denied] and [FailClosed] block" — the export path implements only half
the contract. `CompositePrivacyGate` L57-65 can return FailClosed for unhandled gate-handled
capabilities, so the state is reachable.

### CA-P08-001 (P1) — CONFIRMED
`CompositePrivacyGate.check` (suspend) L26-39: `catch (e: Exception)` → `FailClosed` + `break` —
**no CancellationException rethrow before generic conversion**. Cancellation inside any gate's
`check()` is swallowed and converted to a privacy decision, breaking structured concurrency.
Exactly as reported.

## CL-29 — Group split/settlement (2/2 CONFIRMED)

### CA-E-04-002 (P0) — CONFIRMED, mechanism more precise than reported
Dialog/ViewModel validate against the departed-inclusive list: `GroupMemberDao.getAllForGroups`
L52-53 has no `leftAt` filter (the active-only `getActiveMembersForGroup` L110-111 exists unused);
screen L187 + ViewModel L349-361/391-395 pass that list as the valid member set. Coordinator loads
active-only (GroupTransactionCoordinator L281/399/738). Stored UNEQUAL JSON survives verbatim, but
share computation with the active-only set fails member validation
(`CustomSplitParser` L88-93) → `fallbackToEqualForInvalidLegacyData`
(`SplitCalculator` L186-193, L217-229) → **silent equal split, only Timber.w, Success returned**.
WIDER: (a) also triggered by later member *joins* (stored keys ≠ current set), (b) display path
(dep inclusive) shows the unequal split while persisted `myShareAmount` was equalized — a
display/persist inconsistency. Severity P0 endorsed (silent financial-coercion of user data).

### CA-E-04-003 (P3) — CONFIRMED (latent; zero production callers, as ledger noted)
`GroupBalanceCalculator` L57-59: `netBalance = paidTotal − owedShareTotal − settlementsPaid +
settlementsReceived` — settlement terms inverted vs the "positive = owed" convention. Worked
trace: A owes 100 → A −100/B +100; A repays 50 → A −150/B +150 (away from zero). `isSettled`
inherits the corruption. Zero production callers at pin (test-only construction); collector also
noted the *actually displayed* balances (`SplitCalculator.calculateBalances`) ignore settlements
entirely — adjacent defect, out of scope here, flagged for Stage-2 map addendum.

## CL-17 — Gate-listed members (4/4 CONFIRMED)

### CA-E-04-001 (P0) — CONFIRMED, WIDER
`SharedExpenseBudgetOffsetEngine` three `android.util.Log.w("BudgetOffset", ...)` sites (L127-131,
L160-164, L178-182) fire unconditionally on FX failure — no Timber, no BuildConfig gate — logging
merchant (personal-spend site), amounts, currency codes, and raw epoch dates. WIDER: the same
unredacted strings are returned to callers in `BudgetSpendBreakdown.conversionWarnings` (L220),
extending exposure beyond logcat to UI/diagnostics consumers. The CL-17 spec's DELETION
requirement stands; spec should also cover `conversionWarnings` content.

### CA-P-07-006 — see CL-19 above (member of both clusters' surfaces; verified once)

### CA-P-06-003 (P2) — CONFIRMED
`BudgetForecastResult.Unavailable` carries controlled `reasonCode` + free-form `reason`;
engine L112-117/144-149 populate reason with upstream text including raw `e.message`
(`CurrencySettingsRepository` L87). ViewModel L79 uses `result.reason` (ignores the enum);
L122 interpolates `e.message`; Screen L103-105 → L678-679 renders as `Text`. No sanitization
anywhere in between. Severity note: ledger says P2 — correct; cluster map's "UNVERIFIED P1"
label was a slip, no downstream effect.

### CA-P08-003 (P1) — CONFIRMED, RECALIBRATED on scope
Confirmed: all three services log throwables (stack traces) via Timber.w and return raw
`e.message` (`ParseError(e.message)`/`Unknown(e.message)` in DashboardBriefing L258/260,
ReceiptAssist L241/245/395, QueryInterpretation L162/168); ai_artifacts persistence of raw error
text confirmed for DashboardBriefing (`GenerateDashboardBriefingUseCase` L108-112/123-126) and
ReceiptAssist (`SuggestReceiptExtractionUseCase` L147-152/163-166) via unsanitized
`toReadableMessage` pass-through into `AiArtifactEntity.errorMessage`. Recalibrated: (a)
QueryInterpretation error text is returned but **never persisted** to ai_artifacts; (b) log level
is Timber.w not Timber.e; (c) HTTP-layer failures already use controlled strings
(`errorClass=... correlationId=...`) — the raw leak is confined to JSONException/Exception
catch blocks; (d) persisted text truncated to 200 chars; ExportAnonymizer nulls errorMessage on
anonymized exports (downstream mitigation only). Core defect (raw exception text logged +
returned + persisted for two of three services, no sanitizer on any write path) fully stands.

---

## Cross-cutting observations
1. Zero refutations across 13 findings + the earlier 7 (P-01): campaign finding precision now
   stands at 20/20 with 4 wider-than-reported. Astra's discovery quality at this depth is real,
   but 4/20 widening confirms verification adds material value (the widenings change fix scope).
2. P-07's fail-open family (003/004/005) is mutually reinforcing — the CL-19 spec's single
   state-machine design is the right shape; verification found no anchor drift in the spec
   (spec line anchors matched code at pin on every item checked).
3. New adjacent-defect notes for the Stage-2 map addendum: displayed group balances ignore
   settlements entirely (E-04-003 collector note); `conversionWarnings` leak surface (E-04-001).
