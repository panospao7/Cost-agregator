# CA-2026-09-21 — PHASE-2 VERIFICATION: WAVE 2 FINDINGS (ALL CLUSTERS)

Date: 2026-09-22
Pin: `37601232b9778170c57a656a245b199ab6d7d965` — verified before the pass (`git rev-parse HEAD` =
ad412aec checkpoint, docs-only; `git diff --stat 37601232..HEAD -- app config scripts` empty; no
dirty source paths) and re-verified during the pass (collector CL-27 re-ran the diff check).
Scope: ALL 34 findings of Wave 2 (CL-05, CL-09, CL-15, CL-21, CL-22, CL-23, CL-27) — not only the
gate-listed P1s. This lifts the three "must be revalidated before Wave 2" gates (CL-09, CL-15,
CL-21) and pre-validates the remaining clusters ahead of spec-writing.
Method: adversarial re-read of every claimed mechanism at the pin, with caller/reachability tracing.
Finder ≠ verifier: findings authored by astra direct sessions; this pass executed by a ZCode/GLM-5.3
session via six read-only evidence collectors whose verbatim line-numbered quotes were judged
in-session, plus 7 independent in-session spot-reads of the load-bearing sites (all 7 matched the
collectors exactly). Collector agents:
- agent_27c0bee0-e663-4d7f-b1e9-a37362d8420a (CL-05)
- agent_27cf6b9c-8cc9-4521-982b-ee3f0ee56759 (CL-09 + CL-23)
- agent_578ecb7c-75ab-46ec-b273-6d05386dff85 (CL-15)
- agent_037f951d-1e2c-4f09-b4ad-48c1349b5ab4 (CL-21 + CA-P08-005)
- agent_0ae493f7-edc3-4d8b-8afa-919b485b3af7 (CL-22)
- agent_333dab9a-0df7-458c-b4ed-fcf748fa55f3 (CL-27)

RESULT: **34/34 STAND — 30 CONFIRMED, 3 CONFIRMED-WIDER, 1 CONFIRMED-RECALIBRATED, 0 REFUTED.**
All severities endorsed as claimed in the ledger, with two calibration notes (CA-E-04-004 is P3 per
its own cell report — matches the ledger; CA-P-07-009 stays P1 with narrower post-recalibration
reach, P2 arguable at triage).

**Consequence: all Wave-2 GATEs lift.** CL-09 gate (CA-P-03-001, CA-P-04-005, CA-E-05-008,
CA-P-09-001), CL-15 gate (CA-P-03-003, CA-P-07-002, CA-P-07-009 + remaining P2 members), CL-21 gate
(CA-P08-002) — all members verified. Wave 2 is cleared for spec-writing.

---

## CL-05 — Currency labeling and conversion contract (7/7 stand)

### CA-P-01-006 (P2) — CONFIRMED, WIDER
`NotificationMoneySignalDetector.kt`: SEK/NOK/DKK all declare isoCode "kr" (L30-32); the
explicit-ISO first pass (L41-63) returns SEK for any "42 kr" input at confidence 0.95,
ambiguous=false, before the dedicated ambiguous-kr branch (L109) can run; SEK precedes NOK/DKK so
the result is deterministic. Downstream reach confirmed: `NotificationProcessingPipeline.resolveCurrency`
(L877-880) → review suggestion (L474-476) → currency-aware duplicate check → approval adoption
(`ReviewQueueRepository` L146-149 → `CreateExpenseRequest` currency). WIDER: the kr branch is
wholly unreachable dead code — its regex is a strict subset of SEK's first-loop pattern, so no
input can ever reach it. Fix must remove/reorder, not merely re-label.

### CA-P-04-006 (P2) — CONFIRMED
`BillReminderManager` carries stored `currency` (L92-95) but `BillRemindersScreen` L191 formats
`reminder.amount` with `homeCurrency` (L112 for the total card); `getMonthlyBillsTotal()`
(L162-172) sums `toMonthlyAmount` across currencies with no conversion or grouping; ViewModel
forwards the scalar (L46-53). Display-only, as reported.

### CA-P-06-001 (P2) — CONFIRMED
`BudgetAutopilotEngine` caps the home-currency-normalized history-derived recommendation against
raw `budget.amount` (L183-187) with no budget-currency conversion anywhere in the engine; history
is home-normalized via `MultiCurrencyRepository.requireHomeCurrencyForMoneyMath` →
`aggregateExpenses(homeCurrency=...)` (L790-820). Both apply paths persist only `amount` under the
budget's original currency (`BudgetViewModel` L327-329, L384) — a USD budget receives
home-currency-denominated numbers. Minor citation drift only.

### CA-E-01-002 (P2) — CONFIRMED, WIDER
Three hard-coded catalogs diverge: converter whitelist `SupportedCurrency` = 17 entries
(`CurrencyConverter.kt` L20-37, counted), refresh/selector priority = 20 entries
(`CurrencyRatesRepositoryImpl` L37-40, `CurrencyManagementViewModel` L63-66, counted).
Offered/refreshed but converter-rejected (10): CNY, NZD, MXN, SGD, HKD, KRW, RUB, INR, BRL, ZAR.
Converter-accepted but never refreshed (8): DKK, PLN, CZK, HUF, RON, BGN, HRK, ISK. Typed
aggregation validates via `convertOutcome` (L343-356) while legacy `convert` (L131-218) and the
conversion UI path (ViewModel L194-197) bypass the whitelist entirely. WIDER than the report's
list: BGN omitted from the report's mismatch set; HRK additionally accepted by `fromCode` despite
`isActive = false` (lookup ignores the flag, L40-43).

### CA-E-02-002 (P2) — CONFIRMED
Both empty-result factories re-derive currency from the device locale
(`AdvancedAnalyticsEngine` L610-611, L744-745 → `defaultDisplayCurrency()` L1002-1004) instead of
the already-resolved `input.homeCurrency` passed to their callers (L537, L674); `Currency.getInstance(Locale)`
throws on countryless locales, and inside the ViewModel's unsupervised `async` children
(`AnalyticsViewModel` L584-588) that failure cancels the `coroutineScope` → top-level catch →
whole-screen "Analytics failed to load" (L326-333). Wrong-currency zero results on every device
for empty periods.

### CA-E-02-005 (P2) — CONFIRMED
`RecurringExpenseEngine` injects manual `amount`/`currency` unchanged (L52-63, confidence 1.0 so
manual rules sort first); `InsightsEngine.findRecurringExpenses` never reads `pattern.currency`
and stamps home `displayCurrency` (L752-778); legacy insights format `avgAmount` with
`homeCurrency` (L507). No conversion or warning anywhere in the path.

### CA-E-04-004 (P3 per cell report and ledger — severity label in cluster brief was P2; ENDORSED P3)
`TaxEstimator.getTaxYearSummary` wraps raw mixed-currency SUMs in
`MoneyAggregate.singleCurrency(..., filingCurrency)` (L369-374); source query is the
`@Deprecated(level=ERROR)` raw-SUM DAO with `businessCategory IS NOT NULL`
(`ExpenseDao` L2478-2491), so the null-category remainder is structurally absent from the typed
map while the legacy map merges it (L364-368). Dormancy confirmed: only caller is
`TaxEstimatorTest` — zero production callers. P3 correct.

**CL-05 spec note:** coherent work package but NOT one uniform fix. Must cover: (1) catalog
unification (E-01-002 — its own deliverable); (2) display/sum conversion contract (P-04-006,
E-02-005, P-06-001, E-04-004); (3) empty-factory home-currency threading (E-02-002 — also an
error-handling fix); (4) detector-ordering repair for P-01-006 (dead kr branch).

## CL-09 — Conditional stale-write protection (4/4 CONFIRMED)

### CA-P-03-001 (P1) — CONFIRMED
`ScannedReceiptDao.updateMatchSuggestion` L117-128: `SET ... matchStatus = 'SUGGESTED' ...
WHERE id = :receiptId` — no status predicate (re-verified in-session). Sibling `claimForAutoMatch`
L78-89 has the CAS (`AND matchStatus IN ('UNMATCHED','SUGGESTED')`), proving the oversight.
`ReceiptMatchLifecycleService` L50-59 re-reads existence only, discards the `Int` result; worker
eligibility read is pre-match (L56-57) with the matcher suspension between read and write;
competing UI reject path exists (`ReceiptMatchingViewModel` L235). Rejected receipts are re-admitted
by `getProcessableReceipts` (L166) after the stale write. Second sub-claim holds: the SET clause
does not touch `expenseId`, so a stale write after approve leaves the link with downgraded status.

### CA-P-04-005 (P1) — CONFIRMED
`RecurringRuleLifecycleCoordinator.activateRule`: read at L221, `withTransaction` at L222 — read
outside the transaction, exactly as claimed. `setActiveStatus` is Unit-returning
(`ManualRecurringExpenseDao` L73-74), so a zero-row update is undetectable; generation/projection/
event then proceed from the stale entity (L231-267). `RecurringOccurrence` declares five indices and
**no ForeignKey** (L12-20), and reminder dispatch checks only occurrence status
(`RecurringReminderDeliveryDao` L57) — orphans are permanent and UI-unreachable.
**Fix-scope note:** a predicate on `setActiveStatus` alone is insufficient — the real damage is
derived rows created from the stale snapshot; the read must move inside the transaction or the
update must become a checked CAS.

### CA-P-09-001 (P2) — CONFIRMED
`MerchantKeyBackfillWorker` derives keys from the L71 snapshot and writes by ID (L97-99);
`ExpenseDao.updateMerchantKey` L2322: `UPDATE expenses SET merchantKey = :merchantKey WHERE id =
:expenseId` — no `AND merchantKey IS NULL`. Competing writer
(`TransactionLifecycleCoordinator` L1487/1535) commits merchant+key+dedupeKey atomically. NULL-only
selection (L2307) makes the stale write unrepairable by future backfills.

### CA-E-05-008 (P2) — CONFIRMED
`WarrantyExpirationWorker` takes the ACTIVE snapshot once (L99/L119); the delivery `claim`
(`WarrantyReminderDeliveryDao` L63-66) is a correct CAS on the delivery row only — no join against
`warranties`; the only mid-flow re-read is the delivery row (L199). Parent status is never
revalidated, so a reminder is delivered after a concurrent user CLAIMED. Severity P2 endorsed at
the P2/P3 boundary (bounded to one notification per window). **Fix-scope note:** needs a parent
status re-read, not a write predicate — the write CAS is already sound.

## CL-15 — Failure-to-unavailable result semantics (8/8 stand)

### CA-P-03-003 (P1) — CONFIRMED
`ReceiptOcrService` counts failed pages (L662-671) but reports `pagesProcessed = pagesToProcess`
(L714-721), and `failedPages` has zero production consumers (grep-verified across `app/src`).
`BankStatementLifecycleProcessor`'s PDF_PARTIAL gate (`pagesProcessed < totalPages`, L436-441) is
therefore dead for OCR-failed pages; final status derives solely from parsed-item counts
(L835-838) → a statement missing pages finalizes COMPLETED with no ledger rows for them. Exact-hash
guard obstructs re-import. P1 endorsed.

### CA-P-05-003 (P2) — CONFIRMED
Three risk-source catches return `emptyList()`/`null` (ComputeMoneyRadarUseCase L213-216, L240-243,
L307-310); null is structurally indistinguishable from "no budget" (L259) and maps to score 0
(L355-356) → GREEN → "All Clear" (L164-167, L469-474; widget L50-59). No failure-scenario test
exists. Verified end-to-end.

### CA-P-05-004 (P2) — CONFIRMED
`computeSavingsSweepWidget` wraps work in `withTimeout(3000)` but its catch rethrows every
`CancellationException` (L1143-1144) — including its own TimeoutCancellationException — which
escapes widget assembly (L338) and hits HomeViewModel's Flow `.catch` (L246-258), replacing the
entire dashboard with Error. The sibling generic catch (L1145-1148) returning null proves the
owned-timeout case is the only escape. **Fix-scope note:** mirror-image of the cluster pattern —
purely local ownership-aware catch, no contract change.

### CA-P-06-005 (P2) — CONFIRMED
Catch branch (L175-204) returns fabricated `MODERATE`/0.20 horizons with `isPartial` defaulting
false (L805) and no failure-code field; test codifies the behavior. Mitigating nuance: the
recommendations text does say "temporarily unavailable"/"degraded estimate" — typed result is
still numerically misleading for programmatic consumers. P2 endorsed.

### CA-P-07-002 (P1) — CONFIRMED
`DatabaseBackupRepositoryImpl` constructor (L69-94) takes no `DatabaseReadBarrier`;
`getDatabaseStats` (L2628-2644) reads four DAOs with no admission check and converts any exception
(except cancellation) into `DatabaseStats(0,0,0,0)`. Barrier contract exists
(`DatabaseReadBarrier` L15-32, throws `DatabaseAccessBlockedException`); both consumers
(BackupRestoreViewModel L70, DebugViewModel L369) cannot distinguish zeros from an empty database.
P1 endorsed for the barrier-invariant violation in the restore/backup governance area; user-visible
impact bounded to stats display.

### CA-P-07-009 (P1) — CONFIRMED, RECALIBRATED on reach
Core defect fully confirmed: `verifySemanticIntegrity` catch (L487-489) logs a skip and contributes
no error; `overallPassed` (L409) cannot tell a skipped check from a passed one; this gates the live
restore path (`DatabaseBackupRepositoryImpl` L1133-1136). RECALIBRATION: 2 of the 3 orphan checks
(receipt_expense_links, budget_forecasts) are compensated by `PRAGMA foreign_key_check`
(L296-301) because those tables declare Room ForeignKeys; table-existence and count-failure cases
for Tier 1/2 are also explicitly caught (L310-334). Surviving uncompensated gap: the
`recurring_occurrences → manual_recurring_expenses` orphan class (no FK — independently confirmed
by the CL-09 collector) and transient query failures across all three checks. P1 remains defensible
(fail-open restore admission gate); P2 arguable at triage.

### CA-P-12-003 (P2) — CONFIRMED
Unterminated quote in the header record absorbs all subsequent lines into one malformed record
(`Rfc4180CsvReader` L63, L74-80); importer's header search discards it and returns
`Success(0,0,0,emptyList())` (L108-115); DebugScreen renders a success toast (L1451-1457). No test
covers a malformed first record (both fixtures prepend a valid header). Deterministic, debug-scope,
P2 right.

### CA-E-01-004 (P2) — CONFIRMED
`MoneyAggregateBuilder.fromBuckets` never sets `conversionQuality` (L207-220), so `MoneyAggregate`
defaults any nonempty failure list to PARTIAL (MoneyAggregate L36) even when nothing converted;
`BudgetForecastingEngine` gates only on UNAVAILABLE (L168-170), so an all-failed PERIOD_END spend
yields `spentToDate = 0` and a numeric forecast persisted (L188-262). `MoneyNormalizationEngine`
(L253-254) proves the intended UNAVAILABLE semantic exists — the producer just can't supply it.
**Fix-scope note:** producer-side wiring of the existing discriminator, not a new contract.

## CL-21 — Cloud provider policy and hybrid routing (3/3 CONFIRMED incl. CA-P08-005)

### CA-P08-002 (P1) — CONFIRMED
`CloudQueryInterpretationService` injects only the legacy `CloudPayloadRedactor` (L39-46,
re-verified in-session), redacts via `redactor.redactText` (L94), hand-builds `PreparedCloudPayload`
for audit only (L103-111), and posts `redacted.text` (L115); zero references to
`CloudPayloadPolicy`/`EffectiveCloudAiPolicyResolver` in the file. It is the sole holdout of 8
`Cloud*Service` providers — the other 7 inject the policy and call `prepare*` (enumerated).
LEGAL_PATHS.md L370-373 mandates the policy for all 8. Call chain confirmed: bound via
`HybridQueryInterpretationService` (AiModule L155-157), entered from
`InterpretFinancialQueryUseCase` L44. Calibration nuance: the legacy redactor is the same engine
the policy itself invokes and runs unconditionally, so the bypass skips policy authority
(effective-policy resolution + audit contract), not redaction outright. P1 endorsed.

### CA-P08-004 (P2) — CONFIRMED
`HybridRouter` (L49-71) has zero production callers — only `// AID-4:` comments in six services
and no DI binding; exactly six hybrid wrappers are bound in AiModule (L126-162), each duplicating
decide-and-dispatch (e.g., `HybridQueryInterpretationService` L30-40). Precision notes: a seventh
wrapper (`HybridReceiptAssistService`) duplicates the pattern but is bound nowhere (unused import
AiModule L16); `HybridDedupeJudgeService` L48-66 adds cross-route failover the generic router
cannot express — "wire the router as-is" holds for five of six.

### CA-P08-005 (P2) — CONFIRMED
`CloudProviderPreparedPayloadTest` constructs `DefaultCloudPayloadPolicy` directly (L35-38); all
seven behavior tests call the policy directly; no provider is ever loaded or invoked; the final
test asserts only `verify_privacy_boundaries.py` existence (L115-128). Guard side: R2's
`POLICY_MARKERS_RE` file-wide search (verify_cloud_payload_boundaries.py L78-80, L177-179) is
satisfied by the service's own hand-built audit payload string; privacy G3's marker list
(verify_privacy_boundaries.py L216-221) is satisfied by `redactor.redactText`/`buildRequestBody`
in the enclosing function. CA-P08-002 passes both today.

## CL-22 — Static guard fail-closed enforcement (7/7 stand; P08-005 above)

### CA-I-04-001 (P2) — CONFIRMED
ci.yml L161/L164 invoke `capture_db_guard_evidence.py` with `--root . --expected-sha ... --out ...`
— no `--base-ref` (re-verified in-session); the argument is `required=True` (script L3779-3783) →
argparse exit 2 before capture; contract test asserts only the other three flags. FG-03 note: the
gate fails visibly red every run — dead wiring, not a false PASS.

### CA-I-04-002 (P2) — CONFIRMED
R2 searches raw whole-file content for policy-name markers (no comment stripping); G3's
`prepared_markers` include `redactor.redactText`/`buildRequestBody` and match the enclosing
function text. Comment-only or injected-but-unused policy satisfies both; posted-body linkage is
never inspected. `toRequestBody` evades `REQUEST_BODY_CREATE_RE`. No negative fixtures exist in
either guard's test files.

### CA-I-04-003 (P2) — CONFIRMED
V1 fingerprints are `{rule} {path}` sets (guard_ratchet.py L294, L328-333 — line number stripped);
compare is set-difference only (L519-533); legacy branch exits 0 unless a new path/rule appears.
Active at pin: cancellation.json holds 64 v1 fingerprints, event_writers.json 14, privacy.json 1
(all read). Additional same-file violations are invisible; event-writer rule specifics are
discarded after the `[DAO]`/`[ENTITY]` prefix. Growth in a NEW file still detected — defect is
strictly same-file growth, as reported.

### CA-I-04-004 (P2) — CONFIRMED
Suite runs only `scripts/test_verify_*.py` + `scripts/ci/test_*.py` single-level globs
(run_static_guard_suite.py L174-180); capture's focused list is an 11-item literal (L2143-2160).
All three claimed-omitted modules exist on disk and match neither selection:
`scripts/db_guard/structural_analysis/test_barrier_proof.py`,
`scripts/guardrails/test_production_source_scope.py`, `scripts/test_db_guard_policy_v2.py`.
Coverage omission, not zero testing (some boundary tests are selected).

### CA-I-04-005 (P2) — CONFIRMED
Both the Python guard (regexes on raw file content, comments stripped only in the DAO loop) and
`WorkerGuardArchitectureGuardTest` (same regexes on full text) accept comment-only guard markers,
and `:\s*CoroutineWorker\b` misses qualified `: androidx.work.CoroutineWorker` declarations so such
workers are never scanned. No comment-only or qualified-supertype fixtures in the guard's tests.

### CA-I-04-006 (P2) — CONFIRMED, WIDER
`is_allowlisted` compares entries against the global `RULE_ID` (G-CANCEL-01) rather than the active
rule, and all three call sites pass an empty symbol (L299, L344, L371 → L420-436). A G-CANCEL-01
file exemption therefore suppresses G-CANCEL-02/03 and every symbol. WIDER than reported: because
`symbol` is always empty at the call sites, the entry `symbol` field is dead for every rule —
every declared symbol in `cancellation_allowlist.yml` yields file-wide, all-rules exemption,
contradicting guard-policy.md L20 and the file's own header.

### CA-I-04-007 (P2) — CONFIRMED
`_load_yaml_data` returns None for missing/unparseable YAML (L160-175) → `[]` (L186-187, L198) →
warning + empty violations (L216-220) → unconditional `PASS` + exit 0 (L425-433); the
`--fail-on-violation` flag only affects the non-empty-violations branch. stderr does print an ERROR
line (the report's "silently" is slightly overstated) but the CI contract — exit code and PASS
line — reports success. Clearest FG-03 conflict of the cluster: this is the meta-guard auditing
the allowlists all other guards consume; strongest escalation candidate.

## CL-23 — Worker diagnostics and monotonic drain timing (2/2 CONFIRMED)

### CA-P-09-002 (P2) — CONFIRMED
`WorkerRunLogger.toOutcome` L182-184 reads the `error` method parameter, never
`NotDurableFailure.error` (the stored DAO exception is read by no one — repo-wide grep); success
path passes `null` (L292), retry/failure paths pass the worker's own unrelated exception
(L316, L329). `WorkerExecutionGuard` L484-493 forwards the wrong/null class into
`FileWorkerTerminalDiagnosticSink` (persisted at L170). `WorkerRunLoggerTest` L462-474 codifies the
misattribution (SQLException thrown, "RuntimeException" asserted).

### CA-P-09-003 (P2) — CONFIRMED
`awaitNoActiveWorkers` computes deadline and budget checks from `timeProvider.now()`
(WorkerLeaseRegistryImpl L60-67) with production `SystemTimeProvider.now() = System.currentTimeMillis()`
(L12); no `withTimeout` wraps the drain (L75-77; default 5s from WorkerDrainController L13). Called
under maintenance mode (`MaintenanceOperationRunner` L32-36): backward clock jump stretches the
write-blocked window; forward jump aborts backup/restore spuriously. Availability/boundedness
defect, correctly scoped.

## CL-27 — Money rate, key, and aggregate quality (3/3 CONFIRMED)

### CA-E-01-001 (P2) — CONFIRMED
Parser extracts only `currency`/`rate` attributes (CurrencyRatesRepositoryImpl L72-80); refresh
passes no `validDate` (L104); `storeRates` defaults `validDate = startOfDay(now)` = download-day
UTC midnight (CurrencyConverter L549-574); `getRateAsOf` filters `validDate <= :validDate`
(ExchangeRateDao L62-63), so TRANSACTION_DATE lookups miss or mis-date. Live-checked the pinned ECB
endpoint: the response carries `<Cube time='2026-09-22'>` — a provider date the loop never reads.

### CA-E-01-003 (P2) — CONFIRMED, scoping corrected
`TimePeriodUtils.formatMonthKey` uses `String.format("%04d-%02d", ...)` with no Locale (L837-840)
→ default-locale digits; repository `getMonthKey` is ASCII string concat (MultiCurrencyRepository
L1089-1093); `BudgetHistorySeriesBuilder` membership filter (L103-110) empties the series under
non-Latin-digit locales → LOW_HISTORY non-actionable. SCOPE CORRECTION: only the autopilot path is
affected — `BudgetForecastingEngine.getHistoricalSpendingData` (L487) uses the localized formatter
on both sides and is internally consistent. Test masking confirmed (fixtures use the same
localized formatter).

### CA-E-01-005 (P2) — CONFIRMED, slightly wider
Typed `fromBuckets` adds buckets only for identity/Converted outcomes (L186-203); failed buckets
never reach `sourceBuckets`, so `totalTransactionCount` counts successes only (MoneyAggregate
L48-49). Forecast consumer divides failures by that count (BudgetForecastingEngine L191-192,
L220-224) — 2 included/3 failed → ratio 1.5 → retention 0.25 instead of 0.7, and the test pins the
wrong value. WIDER: ratio is unbounded above 1 (e.g., 5/2 → −0.25) until the final clamp voids the
documented [0,1] proof. Legacy builder conserves all buckets — semantics differ by construction
path, exactly as reported.

---

## Cross-cutting observations

1. Campaign finding precision now **54/54** (7 P-01 + 13 wave-1 + 34 wave-2), 0 refuted, with
   7 wider-than-reported and 2 recalibrated. Verification keeps changing fix scope (6 of 34 here),
   which is exactly its job.
2. **Cluster fix-shape corrections for the spec writer:**
   - CL-05: one work package, four distinct deliverables (catalog unification / conversion
     contract / empty-factory threading / detector ordering). CA-E-04-004 priced P3 (dormant API).
   - CL-09: three-of-four share the literal missing-predicate defect, but CA-P-04-005 needs
     read-inside-transaction or checked CAS (predicate alone insufficient) and CA-E-05-008 needs a
     parent re-read (its write CAS is already correct). Not a uniform "add WHERE" sweep.
   - CL-15: typed-unavailable umbrella is right, but remediation decomposes into eight local
     fixes; CA-P-05-004 is the mirror image (owned timeout over-propagates; local catch only);
     CA-P-03-003 and CA-E-01-004 are wiring of existing discriminators (`failedPages`,
     `ConversionQuality.UNAVAILABLE`), not new contracts.
   - CL-21: provider migration has an in-repo template (7 of 8 providers already compliant);
     respect the DedupeJudge failover caveat and the unbound `HybridReceiptAssistService`.
   - CL-22: every tightening reduces what passes — no baseline/allowlist relaxation; per FG-23 the
     fix PRs must also strengthen the tests that currently pin the defective behavior
     (two-glob suite expectation, evidence-gate argument assertions). CA-I-04-007 is the FG-03
     escalation candidate.
   - CL-27: three independent fix sites (refresh date parsing / one-line Locale.US format /
     bucket conservation); autopilot-only impact scope for the month-key defect.
3. Adjacent-defect notes for the Stage-2 map addendum: `HybridReceiptAssistService` is unbound dead
   code (binding decision, not refactor); the CL-05 conversions contract should explicitly cover
   the empty-factory whole-screen-failure aspect; `MoneyAggregateBuilder` broken-denominator ratio
   can go negative pre-clamp.
4. Zero REFUTED again — but as with wave 1, no widening was harmless: three change fix scope,
   one (P-07-009) halves the uncompensated gap the spec must close.
