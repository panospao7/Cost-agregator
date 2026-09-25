# CA-2026-09-21 — PHASE-2 VERIFICATION: WAVE 3 GATE + WAVE 4 FINDINGS (ALL CLUSTERS)

Date: 2026-09-22
Pin: `37601232b9778170c57a656a245b199ab6d7d965` — verified before the pass (`git rev-parse HEAD` =
ad412aec docs-only checkpoint; `git diff --stat 37601232..HEAD -- app config scripts` empty; no
dirty source paths) and re-verified independently by five of the seven collectors inside their runs.
Scope: ALL 33 remaining non-wave-1/2 findings — Wave 3 (CL-07, CL-08, CL-10, CL-11, CL-25; 7
findings, every one a "must be revalidated before Wave 3" gate item) and Wave 4 (CL-02, CL-03,
CL-04, CL-06, CL-12, CL-13, CL-14, CL-16, CL-20, CL-24, CL-26, CL-28, CL-30; 26 findings). With
this pass every campaign finding outside CL-17's non-gate members is adversarially verified.
Method: adversarial re-read of every claimed mechanism at the pin with caller/reachability tracing;
doc-vs-code verbatim comparison for the DOCS-ONLY cluster (CL-06). Finder ≠ verifier: findings
authored by astra direct sessions; this pass executed by a ZCode/GLM-5.3 session via seven
read-only evidence collectors whose verbatim line-numbered quotes were judged in-session, plus 5
in-session spot-reads of the load-bearing sites (all 5 matched exactly, incl. both severity
disputes). Collector agents:
- agent_f686c7f2-aee9-452a-8972-7d9790f6dcc3 (CL-07 + CL-08)
- agent_a56a0949-9314-4942-a991-19a32afef80c (CL-10 + CL-11 + CL-25)
- agent_5346b3ce-5fd6-4943-a8cb-8732c1184eb7 (CL-02 + CL-03 + CL-04)
- agent_3ecf3fac-ef2b-44b6-8499-950c3e007208 (CL-06, docs-only)
- agent_bc5adfe1-d380-4fd7-a872-c4e636da3c9d (CL-12 + CL-13)
- agent_3a1ea7d8-aaf1-4f63-bc9f-ebe8937b6b28 (CL-14 + CL-16 + CL-28)
- agent_8b2386b1-4b52-4507-b099-423a02d72c65 (CL-20 + CL-24 + CL-26 + CL-30)

RESULT: **33/33 STAND — 30 CONFIRMED, 2 CONFIRMED-WIDER, 1 CONFIRMED-RECALIBRATED, 0 REFUTED.**
Two severities DISPUTED downward (CA-I-05-001 P1→P2; CA-P-12-001 P1→P2 — mechanisms fully
confirmed, live blast radius recalibrated). All other severities endorsed as ledgered.

**Consequence: all Wave-3 GATEs lift.** CL-07 (CA-P-02-002), CL-08 (CA-P-02-003, CA-I-05-001),
CL-10 (CA-P-03-002), CL-11 (CA-P-03-004), CL-25 (CA-P-12-001) — all mechanisms verified; the two
severity recalibrations are recorded for spec/triage and do not block (same precedent as wave-1's
CA-P08-003). Wave 3 cleared for spec-writing; Wave 4 pre-validated.

---

## CL-07 — Review approval side-effect timing and failure propagation (2/2 stand)

### CA-P-02-002 (P1) — CONFIRMED, WIDER
Outer approval transaction opens at `ReviewQueueRepository` L222; the nested
`receiptLinkService.linkReceiptToExpense` (L307) runs inside it; `ReceiptLinkService`'s own
`runInTransaction` (L189) is re-entrant (joins the outer tx), so the category side-effect dispatch
(`dispatchAssignedCategorySideEffects`, L368-373) executes while the outer transaction is still
open — budget/anomaly reads join uncommitted state, and the anomaly OS notification (posted by
`AnomalyAlertOrchestrator` L189/193) survives any later rollback. The runner
(`RoomDomainTransactionRunner` L43-45) is a plain `withTransaction` with no outermost-commit
callback; `DefaultExpenseCategoryAssignmentService`'s KDoc (L57-61) documents exactly this
contract and the caller violates it; LEGAL_PATHS.md L665 promises "side effects run only after the
outer commit". In-session spot-check confirmed the L222/L307/L370 nesting and the L363 comment
believing inner-return = safe. WIDER: the same nested dispatch defeats the rollback-safety comment
of `ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt` (L1565/1567/1575) and runs nested in
the email-receipt path (L1018, links at L1176/1190). Fix shape supported: the approval flow
already returns a post-commit batch (L342, run at L397-399) — convert the dispatch to contribute
to it; `PostCommitActionRunnerImpl` already classifies outcomes.

### CA-E-05-005 (P2) — CONFIRMED
`ReceiptSideEffectPlanner` L211-212 discards `WarrantyCreationResult` and returns
`SideEffectOutcome.Completed`; only thrown exceptions map to FailedRetryable (L215-220), but the
use case catches its own exceptions and returns `Failure` values (L88-90, L125, L138, L142-145);
runner counts Completed (L76-84). Dispatch placement is post-commit and correct
(`ReceiptLifecycleCoordinator` L686-690) — the outcome classification is the defect. Spec note:
fixing yields correct classification/diagnostics, not an automatic retry (none exists on this
path).

## CL-08 — Expense lifecycle single-writer and identity preservation (2/2 stand)

### CA-P-02-003 (P2) — CONFIRMED
Create persists `idem:BANK_API_SYNC:<hash>` (coordinator L107-110, L605-606, deliberately relying
solely on the unique dedupeKey index, no fuzzy preflight for STRICT_EXTERNAL_ID); every edit path
regenerates a content key without inspecting the stored one — `updateExpense` L1009/1021-1022,
`updateMerchant` L1506/1535, `updateType` L1613/1641, `updateTypeAndTransferDetails` L1820,
`bulkUpdateMerchant` L2285-2286 (DAO L343-345); no `startsWith("idem` preservation anywhere in
`app/src/main`. `BankApiIntegration`'s own comment (L682-686) promises the exact replay behavior
the edit path destroys. Producer is `@StubForDemo`/`requireStubMode()`-gated (L210/214) — severity
P2 correct as ledgered.

### CA-I-05-001 (P1) — CONFIRMED, RECALIBRATED; SEVERITY DISPUTED P1→P2 (endorsed by verifier)
Mechanism confirmed: `TransactionsViewModel.clearLocation` (L710-715, UI caller
`TransactionsScreen` L941) → `ExpenseRepository.clearExpenseLocation` (L981-989, barrier-checked
direct DAO write, "no lifecycle event by design" per the EXPENSE_DAO_MUTATION_ALLOWLIST comment)
→ `ExpenseDao.clearLocation` (L2182-2189), bypassing the coordinator's `updateLocation` (which
writes the UPDATED event with before/after snapshots, L1277-1283). The only production caller is
the user-facing Transactions screen — the "maintenance/backfill" allowlist rationale (GR-08l1)
does not match the actual caller. RECALIBRATION: the report's "lost post-commit side-effect
planning/dispatch" sub-claim is wrong — the coordinator intentionally emits an empty batch for
`LOCATION_ONLY` (`TransactionSideEffectPlanner` L63-64, verified in-session) — so the true delta
is the UPDATED event + snapshots + typed no-op/not-found semantics. P2 fits (audit-trail gap, no
data corruption). FIX-SHAPE CORRECTION for the spec: `updateLocation` cannot express a clear
(`require(latitude in -90.0..90.0)`, L1247, verified in-session) — the fix needs a
coordinator-owned clear-capable entry writing the same event, plus a GR-08l1 allowlist-note
update; routing through `updateLocation` unchanged is impossible.

## CL-10 / CL-11 / CL-25 — Wave-3 receipt + import gates (3/3 stand)

### CA-P-03-002 (P1) — CONFIRMED
`ReceiptLifecycleCoordinator` L331-333: `requireAllowed(PrivacyCapability.CLOUD_AI_RECEIPT_OCR)`
gates the entire scan before any OCR runs. `EffectiveCloudAiPolicy` registers OCR as IMAGE-input
(L53) → requires `cloudAllowed` (itself `privacy.cloudAiEnabled && ai.allowCloudAi`, both default
false) AND `receiptImageUploadAllowed` (L147-149). Under defaults the first check throws
`CLOUD_AI_DISABLED` (L95-96); global-cloud-on alone then fails at `RECEIPT_IMAGE_UPLOAD_DISABLED`
— while `ReceiptOcrService` is pure on-device ML Kit (grep: zero cloud/http code in the file) and
the SecurityException surfaces as scan failure (coordinator L730-733). No fallback between gate
and OCR. Default-privacy users lose the whole camera/batch scan entry. Fix lands on the single
L333 call — the registry has no non-cloud entry today.

### CA-P-03-004 (P1) — CONFIRMED
`ReceiptDuplicateDetector` strips all numeric amount tokens (L183) and date tokens (L185-186)
before SHA-256 (L189-190); `checkDuplicate` returns TEXT_FINGERPRINT at confidence 0.95 on the
first hash hit (L113-124) before the semantic check (L127-138) is ever consulted — the semantic
fingerprint (which carries amount/date/currency) is computed by the coordinator (L473-480) and
passed in (L496-497, L544-545) but discarded on a text hit. Both persisted and draft paths treat
any non-EXACT_HASH match as final — the draft path deletes the new image asset (L548-562).
Deterministic preprocessing collision: `TOTAL 3.50` and `TOTAL 4.50` receipts collapse; second
image destroyed, no recovery. Fix is a guard inside the early return; the discriminator already
exists.

### CA-P-12-001 (P1) — CONFIRMED; SEVERITY DISPUTED P1→P2 (endorsed by verifier)
Mechanism confirmed end-to-end (in-session spot-check: importer contains zero
`csvImportBatchId`/`csvRowNumber` occurrences; requirement contract at
`CreateExpenseSourceLinkRequirements` L29-31; DebugScreen is the only production caller):
`createExpenseMutation` returns `ValidationFailed` (coordinator L411-413, L439) before any insert
→ every row becomes `RowResult.Failed` → `imported=0, errors=N`. DISPUTE: live reach is
`BuildConfig.DEBUG`-gated (`MainActivity` L874-881, verified in-session) with zero release-user
impact — P2 under the campaign's own D3 scoping; P1 only if debug CSV import is a required RP-19
validation gate. Fix insertion point is the request builder (L230-243); a per-import batch ID
must be minted (none exists in any form today).

## CL-02 / CL-03 / CL-04 — Notification worker + backfill (4/4 CONFIRMED)

### CA-P-01-002 (P2) — CONFIRMED
Dedupe identity (content+postTime fingerprint, coordinator L322-330; unique index on
`dedupeFingerprint` only) diverged from work identity (`"intake_${notificationKeyHash}"` +
`ExistingWorkPolicy.REPLACE`, L487-491). Two distinct rows under one Android key both insert; the
second REPLACE cancels the first row's pending 5s-delayed work (WorkManager documented REPLACE
semantics); recovery sweeps re-enqueue under the per-row name `notification-intake-${row.id}` +
KEEP (scheduler L62-76) — rescue only at app start / listener reconnect. Fix supported: correct
per-row+KEEP patterns already exist in the same files; note the name derivation lives in
`NotificationIntakeCoordinator.captureForRetry`.

### CA-P-01-003 (P2) — CONFIRMED
Full trace: row backoff ladder 30s/120s/… (`NotificationIntakeRetryPolicy` L11-20) vs WorkManager
EXPONENTIAL 30s (coordinator L205-208). First retry at ~30s hits the claim predicate
`nowMs >= COALESCE(nextAttemptAt,0)` (DAO L97-98) with `nextAttemptAt = now+120s` → zero-row
claim → worker returns normally as "idempotent success" (L105-107) → guard Success →
`Result.success()` → WorkManager chain SUCCEEDED while the row stays FAILED_RETRYABLE. Only
`recoverPending` (startup/listener hooks) reschedules. Spec note: reconcile the 30s WorkManager
backoff with the row ladder so retried-but-not-due claims don't spin; the guard already maps
`RetryableWorkerException` to `Result.retry()`.

### CA-P-01-005 (P2) — CONFIRMED
Claim (L100), payload load (L126), and decrypt (L162-167) all run before the guarded failure
region opens at L241; the failure catch handlers (L318-384) attach only to that try. A
`GeneralSecurityException` from decrypt is not `RetryableWorkerException`, not transient
(`classifyTransient` L655-667) → guard FAILED → `Result.failure()` with zero intake-table writes;
row stranded PROCESSING until the 10-minute `releaseStaleProcessing` sweep; transient payload not
purged on this path. Fix: widen the try to enclose L111-206.

### CA-P-02-001 (P3) — CONFIRMED
Latent cross-product verified: for each expense whose `source` merely contains NOTIFICATION/SMS
(worker L228-229), the loop iterates ALL pending reviews unfiltered (L222-223, DAO L49-51) and
inserts an ACTIVE, isPrimary CREATED_FROM link per review (L230-256) — the code's own comment
(L225-227) admits no ownership check; the unique index deduplicates each false pair, not
ownership. Unwired status verified: zero production callers (test-only). P3 latent correct; keep
unwired or add the validated identity match.

## CL-06 — Documentation and ownership drift (7/7 CONFIRMED, DOCS-ONLY)

All seven doc-vs-code mismatches verified verbatim on both sides. Doc anchors:
- CA-P-01-007: `docs/.../RP-10-notification-capture.md` L184-186 claims a 0xFF first-byte marker;
  the writer emits a plain 0/1 presence byte (`NotificationTransientPayloadCrypto` L87-101,
  constants L50-51) — no marker exists; 0xFF appears only as a bit-mask.
- CA-P-03-005: LEGAL_PATHS.md L133-136 documents `createExpenseFromReceipt()` — a
  `DeprecationLevel.ERROR` stub (coordinator L1519-1540) — while the real atomic entry
  `createExpenseAndLinkReceipt` (L1552-1622, production caller `ReceiptScanViewModel` L1241)
  appears nowhere in LEGAL_PATHS.md.
- CA-E-05-001: LEGAL_PATHS.md L747 forbids `SubscriptionCandidateDao.insert/delete` outside
  validateAndCreate/acceptCandidate, while the enforced ownership policy
  (`config/guards/db_ownership_policy.yml` L1423-1438) affirmatively registers
  `NotificationProcessingPipeline.detectAndSaveSubscriptionCandidate` (code L1809-1813) doing
  exactly that insert — doc and fail-closed guard policy directly contradict.
- CA-E-05-002: LEGAL_PATHS.md L730-732 mandates `SubscriptionManagerEngine.recordUsage` (zero
  production callers) while the live UI uses the barrier-checked repository writer
  (`SubscriptionManagementViewModel` L234/241 → repository L52-56) which the policy itself labels
  "the LEGAL_PATHS RECORD-usage data path".
- CA-I-02-001: `AppDatabase` KDoc (L8716-8722) claims pre-configured FRESH_INSTALL_CALLBACK;
  `configureBuilder` (L8742-8748) adds none (`addCallback` grep: zero production hits); the dead
  callback's schema DDL block spans L5095-5398 (wider than cited) and is registered only by
  `DatabaseMigrationTest` L391.
- CA-I-03-001: CODEBASE_SEGMENTS.md L311/L613 misattribute the WorkerRunLogger binding to
  WorkerModule (which binds only lease/drain/permission/WorkManager, L20-42); the binding is
  `DiagnosticsModule` L55. Partially self-correcting: CODEBASE_INVENTORY.md L540/544 and
  hilt-bindings-map.md L108 already state the truth — only CODEBASE_SEGMENTS.md needs the fix.
- CA-I-03-002: all four doc surfaces (SEGMENTS L591/L610, INVENTORY L544, hilt-bindings-map
  L440-444) say 10 retention targets; `RetentionModule` registers 15 (legacy ten at L44-410 plus
  five new at L452-615, exposed at L655); tests already exercise the new names.

Severity P3 endorsed across the board. Two fix caveats beyond doc edits: CA-I-02-001's stale test
contracts (parity tests asserting callback-managed schema + the migration test registering the
dead callback) live in test code; and LEGAL_PATHS.md is CI-referenced by guards — corrections
there must be checked against whatever guard validates its anchors, with CA-E-05-001 arguably a
policy-vs-doc reconciliation rather than a plain doc edit.

## CL-12 / CL-13 — Reminder states + reconciliation (4/4 CONFIRMED)

### CA-P-04-001 (P2) — CONFIRMED
SENT is terminal (`RecurringLifecycleCoordinator` L78-79); both action coordinators NoOp on
terminal (dismiss L1196-1198, snooze L1231-1233); action workers discard the NoOp result
(`SnoozeReminderActionWorker` L49-50; Dismiss identical); no `NotificationManager.cancel`
anywhere (repo-wide grep) and `setAutoCancel` covers only body taps. Both notification action
buttons are dead after a successful send.

### CA-P-04-002 (P2) — CONFIRMED
Non-permission posting failure → `markReminderFailed` → FAILED_TRANSIENT (worker L158-160;
coordinator L1158/1163); due selection (DAO L55-56) and claim (L82-84) accept only
SCHEDULED/SNOOZED; `recoverStaleClaimedDeliveries` repairs CLAIMED only (L104); the sole
FAILED_TRANSIENT→SCHEDULED reset is gated behind explicit expense unlink (DAO L199 ← coordinator
L671 ← L821). The "retryable" name has no retry path; one transient exception permanently drops
the reminder.

### CA-P-04-003 (P2) — CONFIRMED
`updateRule` preserves `isActive` (L344, comment deferring to activate/deactivate) then
unconditionally reconciles (L354): materialization with `createReminderDeliveries = true`
(L489-493), projection (L500-505), 12-month window (L394), invariants with no activity check
(L557-619) — recreating exactly the derived state that `deactivateRule` deleted (L76-84).
Production trigger confirmed: `SmartBillNegotiationEngine` L588/L638-640 updates a paused
subscription without an isActive test → repository L129 → `updateRule`. Contrast: write-side
materializer entry points DO reject inactive rules (L120, L220) — the update reconciler is the
gap.

### CA-P-04-004 (P2) — CONFIRMED
Projection advances the anchor with the rule's fixed day (L230-238), then passes the clamped
result as `ExpandRequest.anchorDate` (L248); `expand` re-derives the day-of-month from that
argument (expander L121, keys embed the day at L191-192); materialization anchors on the saved
unclamped `nextDate` (L174, L403). Jan-31 rule → stored keys |Jan31|/|Feb28|/|Mar31| vs projected
|Mar28| — key merge keeps both (`CashFlowCalculator` L505-507; `FinancialStressForecastEngine`
L352-355, summing each) → doubled obligations inside the 60-day stress horizon. Intra-operation
handoff loss: the original day is present in `rule.nextDate` and even used in the projection loop,
then discarded at the request boundary. Distinct from the accepted D7 policy as the register
notes; fix is achievable for the handoff (pass `anchorDayOfMonth`/`rule.nextDate` alongside), not
for historically drifted persisted anchors (schema stores no original day).

## CL-14 / CL-16 / CL-28 — Dashboard, budget clamp, analytics (6/6 CONFIRMED)

### CA-P-05-001 (P2) — CONFIRMED
Month prefilter (L392) truncates the purchase list before the week window filter (L384/L413); the
six-month fetch (DashboardContractsAdapter L64-67) already contains the dropped rows. Monday-start
weeks crossing the month boundary undercount the Week KPI; month/today cards are intentionally
month-scoped (comments L386-391). Fix: full-week slice from the existing fetch; no new query.

### CA-P-05-002 (P2) — CONFIRMED
DAO emits `ORDER BY date DESC` (L230-231); insertion-ordered mutableMapOf preserves newest→oldest;
`calculateTrend` computes `(last-first)/first` = oldest-vs-newest inverted (L234-240). Worked
example reproduces exactly (true +80%/+20%/+60pp reads as −44%/−17%/−28pp), and a genuinely
inflating user always yields a negative rate that fails the `< 0.05` prompt gate
(`LifestyleSavingsPromptUseCase` L56-58) — prompt deterministically suppressed. Fix: sort month
keys chronologically before `calculateTrend`.

### CA-P-06-002 (P2) — CONFIRMED
±15% `coerceIn` (L180-185) → recommendation `isActionable=true` (L208-223) → BUD-5 hierarchy
scaling LAST (L230-249) with no re-clamp; `copy()` preserves isActionable; both apply paths
persist verbatim (`BudgetViewModel` L313/328-329, L371/385). Verified example: two category recs
at cap scaled by 0.5 → −50% cuts vs the documented ±15% promise (KDoc L21/25). Scaling only
shrinks (factor < 1) and never touches the overall rec. Fix: final per-budget clamp after the
scaling block.

### CA-E-02-001 (P2) — CONFIRMED
`categorySpending` groups by per-expense `categoryId` (L37-40; null group = uncategorized rows
only); overall budget lookup `categorySpending[null]` (L49-52) therefore sees only uncategorized
spend — fully-categorized purchases under an overall budget report actual 0.0 / 0% /
not-over-budget. Snapshots preserve null (`BudgetRepository` L111-122); chart renders the zero
(`AnalyticsViewModel` L476/L1137-1154/L1168-1169).

### CA-E-02-003 (P2) — CONFIRMED
The normalizer already computes the true per-row count (`affectedTransactionCount = expenseIds.size`,
L310-314) but the assembler counts aggregated warning *objects* (`missingWarnings = severeWarnings.count{...}`,
L146-148); every missing-rate warning shares one (type,message) key (L159/164, L242-245), so
N rows collapse to 1 → `missingRateCount ∈ {0,1}` → multiplier 0.98/1.0 and the 0.8 floor is
unreachable via this branch (needs 10 objects). Worked example verified against the classifier
formula (`SpendingPersonalityClassifier` L628-631): 0.584 vs intended 0.44. `excludedCount` stays
row-based and correct. Fix: derive from `affectedTransactionCount` — the field already exists.

### CA-E-02-004 (P2) — CONFIRMED
`totalCommitted` includes confirmed recurring occurrences (SynthesisEngine L262-270, L290) but the
projected points (`L414-L436`) sum only `lastKnownTotal + discretionary + mustCumulative +
likelyCumulative` with no recurring term — and the assembler deliberately strips occurrence-linked
planned rows from the planned maps (`ForecastInputAssembler` L499-518), so no planned map can
carry those amounts. Verified example: flat trajectory at 200 while `totalCommitted = 100` says
300. The chart's own line and its committed metric disagree by exactly the recurring amount.

## CL-20 / CL-24 / CL-26 / CL-30 — Backup privacy, email parser, export, navigation (5/5 stand)

### CA-P-07-007 (P2) — CONFIRMED, WIDER
`BackupPrivacyMode.REDACT_RAW_TEXT` = (redactsRawText=true, includesReceiptImages=true)
(`BackupPrivacyMode` L10-12); export resolves both flags (repo L603-604) then empties
`receiptFiles` under `includeReceiptImages && !redacted` (L725-729, in-session verified) while the
snapshot still records `includeReceiptImages = resolvedIncludeReceiptImages` (L736, verified);
`CostbackupBundle` manifest then asserts `options.includeReceiptImages=true` while
`includes.receiptImages = true && (0 files) = false` (L587-594) and the ZIP/checksum gates skip
(L607-608, L624) — the manifest disagrees with itself and with the ZIP. WIDER: the only live
caller (`BackupRestoreViewModel` L108, verified in-session) passes no privacyMode, so the DEFAULT
resolved flags (true, true) hit the identical mismatch on every production backup;
`REDACT_RAW_TEXT` itself has zero production callers. Restore never cross-checks
`includes.receiptImages`, so nothing fails. Spec note: the aligned contract must cover the
resolved-flag defaults, not just the enum label.

### CA-P-11-001 (P3) — CONFIRMED
`detectCurrencyCode` accepts ISO only when exactly one match (L317-322); a 2-match conflict falls
through to the trusted-domain loop and returns the domain's code — contradicting the function's
own KDoc ("conflicting ISO codes fail closed", L304-307) and the RP-18 skip contract. Worked
example (EUR order total + USD converted line on amazon.com → USD) reproduces. D2 staging
reconfirmed: `EmailIngestionModule` never provides/injects `EmailReceiptIngestionService`;
service validation has no currency-conflict guard. P3 staged-only correct.

### CA-P-11-002 (P3) — CONFIRMED
`extractTotalAmount` (L221-227) never calls `isSummaryRow` (L326-328); the keyword fallback
(`totalKeywordLineRegex` L185, `extractKeywordLineTotal` L247-253) accepts any strict amount on a
line containing mid-line "total" — "3 items, total 45,90" (which `isSummaryRow` matches) becomes
the receipt total, violating LEGAL_PATHS.md L230-232. `isSummaryRow`'s only call sites are the
item loops. D2 staging as above; P3 correct.

### CA-P-12-002 (P2) — CONFIRMED
`selectFormat` clears nothing (L136-138) and the completed state stores no originating format
(L415-423); the result card survives format switches (screen L204); Save derives the extension
from the live selection while copying the old file verbatim (L227-233, launcher L57-75,
`MainActivity` L858-861); Share derives MIME the same way (L244-265). JSON bytes proposed as
`.iif` reproduces exactly. Fix: clear on change or store/validate the originating format — either
lever lands on real code.

### CA-I-05-003 (P2) — CONFIRMED
`VisualSplitEditor.forExpense` populates amount/currency (destination L61-68, L85-91);
`toSaveToken` serializes both (controller L147-155, `Uri.encode` at L71-80); the token lands in
Compose `rememberSaveable` saved state via `PersistedNavigationState.Saver` (L36-42, L480-482),
including back-stack snapshots. `DestinationPersistencePolicy` (L20-28) is a test-facing
classification that redacts nothing; `NavigationRouteContractTest` L193-194 codifies the
100.50/"USD" round-trip. Violates the categorical "never persist user financial payloads" rule;
fix = drop the two fields + update the round-trip test in the same batch.

---

## Cross-cutting observations

1. Campaign finding precision now **87/87 verified findings with 0 refuted** (7 P-01 + 13 wave-1 +
   34 wave-2 + 33 waves-3/4). Widenings: 9 total (3 wave-1, 3 wave-2, 2 here + CL-06's in-pass
   dead-code span note); recalibrations: 3 (CA-P08-003, CA-P-07-009, CA-I-05-001). Verification
   changed fix scope or severity in 14/87 cases — every wave.
2. **Two severity recalibrations for triage:** CA-I-05-001 P1→P2 (side-effect loss sub-claim was
   wrong — LOCATION_ONLY intentionally emits no side effects; true delta is the UPDATED event +
   snapshots; and the named fix `updateLocation` cannot express a clear — a clear-capable
   coordinator entry is needed) and CA-P-12-001 P1→P2 (BuildConfig.DEBUG-gated reach; P1 only if
   debug CSV import is a required RP-19 validation gate). Both mechanisms fully confirmed; CL-08's
   cluster severity now rests on CA-P-02-003 (P2) + the recalibrated I-05-001 — the CL-08 header
   "P1" should be re-read as P2-class at spec time.
3. **Fix-shape corrections for spec writers:** CL-07 — a working post-commit batch mechanism
   already exists in the approval flow; convert the three nested dispatch sites (approval,
   create+link, email) to contribute to it; the create+link path's rollback-safety comment is
   currently false. CL-08 — needs a clear-capable coordinator entry, not `updateLocation` as-is,
   plus a GR-08l1 allowlist-note update. CL-03 — reconcile WorkManager 30s backoff with the row's
   120s+ ladder to avoid spin. CL-13/CA-P-04-004 — pass `anchorDayOfMonth` (or `rule.nextDate`)
   through the ExpandRequest; historically drifted persisted anchors remain out of scope (D7).
   CL-20 — the aligned contract must cover the default resolved flags, not just the
   REDACT_RAW_TEXT enum entry.
4. **CL-06 fix caveats:** LEGAL_PATHS.md corrections are CI-guard-referenced (check the guard that
   validates its anchors); CA-E-05-001 is a policy-vs-doc reconciliation (the enforcement policy,
   not the doc, reflects current intent); CA-I-02-001's complete fix touches test files
   (parity tests + migration test registering the dead callback), not just docs.
5. **Campaign coverage state:** with this pass, every ledger row is adversarially verified EXCEPT
   the 7 non-gate CL-17 members (CA-P-04-007, CA-P-06-004, CA-P-07-008, CA-E-01-006, CA-E-02-006,
   CA-I-05-002, CA-I-05-004 — all P2/P3 privacy-leak sites inside the wave-1 spec already being
   implemented). They are the only rows verified by cluster gate sampling rather than
   individually.
