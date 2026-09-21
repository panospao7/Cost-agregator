# Tests Indicating Production Bugs — Tracker

Created: 2026-09-20
Owner: pending assignment
Disposition rule: these failing tests are kept as evidence. They are NOT
stale and were deliberately NOT "fixed" to pass, because doing so would mask
a production defect. Each entry names the failing test(s), the suspected
production bug with file:line evidence, and the recommended production fix.

Status vocabulary: OPEN / CONFIRMED / FIXED / WONTFIX. Update this file — do
not delete entries.

---

## PB-01 BankStatementParser — duplicate imports, date drift, dropped rows (OPEN)

Severity: HIGH (financial correctness: duplicate ledger entries, wrong dates,
silently lost transactions)

Failing tests (15, all in `app/src/test/java/com/yourname/expensetracker/
domain/receipt/BankStatementParserTest.kt`): revolut Top-up / Transfer to /
Transfer from / Received from / ATM withdrawal / cash withdrawal / refund /
promo credit / GBP amount / EU-thousands / US-thousands / normal merchant
spend, nbg credit row, nbg debit row, header keyword order.

Four distinct production defects in `app/src/main/java/com/yourname/
expensetracker/domain/receipt/BankStatementParser.kt`:

1. **No break after generic-parser success** (`parse()`, lines ~164-201).
   The parser-priority loop `break`s after a revolut/greek match but the
   generic branch adds without breaking, so a second parser can match the
   same row and the row imports TWICE (e.g. generic PURCHASE + revolut
   TRANSFER for one "Transfer to John Doe" row).
   Fix: `break` after a successful generic match (matches the other branches).

2. **Header detection runs after header rows are filtered away**
   (`parse()` lines ~148-154: `preFilterRows` strips header rows, then
   `detectDateColumns(rowStrings)` runs on the FILTERED list). Header-derived
   transaction-date/value-date order is therefore always UNKNOWN whenever the
   statement header contains any header keyword, and rows fall back to the
   legacy "first date wins" heuristic — picking the value date when the
   statement prints VALUE DATE before TRANSACTION DATE. Evidence: the
   "header keyword order" test gets 18/03 (value date) instead of 15/03
   (transaction date).
   Fix: detect date columns on the pre-filter row list (or retain header rows
   for detection), then filter.

3. **Bare "AM"/"PM" header keywords substring-match data rows**
   (`HEADER_KEYWORDS` lines ~52-53 contain "AM"/"PM"; `preFilterRows` line
   ~317 matches with `upper.contains(keyword.uppercase())`). Any row whose
   text contains the letters "AM" anywhere (e.g. "Refund AMAZON") is silently
   dropped as a header and the transaction is LOST (0 parsed, not even a
   duplicate).
   Fix: match header keywords on word boundaries (token-level match, not
   substring), and/or drop "AM"/"PM" from header keywords (they are meridiem
   markers, not header labels).

4. **Format auto-detection too narrow** (`parse()` lines ~165-175): requires
   the literal word "Revolut" or Greek bank names anywhere in the full OCR
   text; realistic single-row test statements fall through to generic-first
   priority, and the generic parser cannot produce TRANSFER/WITHDRAWAL/DEPOSIT
   classifications, keeps store/terminal codes in merchant names, and routes
   currency through the normalizer (GBP rows come out "EUR" in mocks).
   Fix: per-row format detection (date-shape + marker heuristics) to choose
   the parser priority per row, not per statement.

Note: defects 1+2 were diagnosed 2026-09-19 (handoff-test-diagnosis-20260919,
commit b35a974f / 469596cd lineage); defect 3 and 4 were newly characterized
2026-09-20. Until fixed, bank/import statement intake can create duplicate
financial entries and lose rows.

---

## PB-02 Migration 119→121 does not create pipeline_diagnostic_events (OPEN — NEEDS-HUMAN)

Severity: MEDIUM today (path is retired; data-loss relevant IF pre-v145
upgrades are ever supported again)

Failing tests (kept as evidence, `app/src/test/java/com/yourname/expensetracker/
data/database/MigrationRegistrationTest.kt`):
- `migration 119 to 121 creates both settlement and lifecycle tables`
- `migration 120 to 121 creates group_lifecycle_events table`
  (this one ALSO has a test-side defect: its seed INSERT references an
  `effectiveAmount` column that never existed in any exported schema — v119,
  v120, v121 or v148)

Evidence: exported schema `app/schemas/...AppDatabase/121.json` contains
`pipeline_diagnostic_events` (+ 2 indexes), but the only SQL that creates the
table is `MIGRATION_121_122` (AppDatabase.kt ~:7674-7697, whose comment
incorrectly claims the table "was created fresh in MIGRATION_121_122").
`MIGRATION_119_120` / `MIGRATION_120_121` (AppDatabase.kt ~:7628-7672) create
only the group_settlements / group_lifecycle_events tables, so Room
strict-validation of the 119→121 path fails with
`Migration didn't properly handle: pipeline_diagnostic_events`.

Decision needed from owner: are pre-v145 upgrade paths supported?
- If NO (current reality: baseline v145, `DatabaseMigrations.ALL` registers
  only 145→146→147→148): retire/delete the dead pre-145 Migration objects and
  the two tests above with them; no SQL change needed.
- If YES: add the missing CREATE TABLE + indexes to the 119→121 path and
  re-validate against 121.json (note 120.json also already contains
  group_lifecycle_events — the migration history is internally inconsistent
  and needs a full audit before "supported" can be claimed).

---

## PB-03 CloudAiPrivacyGate treats CLOUD_AI_RECEIPT_OCR as generic cloud AI (OPEN — latent)

Severity: LOW-MEDIUM (latent: no live caller routes OCR through the gate
today; live OCR intake uses the stricter resolver path)

Evidence (2026-09-20 re-verification supersedes the 2026-09-19
REAL-PROD-BUG classification — the live path is fail-closed):
- `EffectiveCloudAiPolicy.requireAllowed` maps `CLOUD_AI_RECEIPT_OCR` →
  `receiptImageUploadAllowed` (both `receiptImageCloudEnabled` flags must be
  true; default false = fail-closed). The LIVE OCR intake
  (`ReceiptLifecycleCoordinator.kt:329-330`) calls exactly this — safe.
- BUT `CloudAiPrivacyGate.check` (lines ~27-38) groups
  `CLOUD_AI_RECEIPT_OCR` with generic cloud AI and checks ONLY
  `policy.cloudAllowed` — ignoring receipt-upload consent AND
  `redactBeforeCloud`. `redactBeforeCloud` is only logged on the OCR path,
  not enforced (ReceiptLifecycleCoordinator.kt:331-333).
- `grep` finds no production caller of `gate.check(CLOUD_AI_RECEIPT_OCR)` —
  hence latent, not an active leak. Note OCR itself is on-device ML Kit.

Recommended fix (cheap hardening for when a future caller appears): move
`CLOUD_AI_RECEIPT_OCR` out of the generic branch and enforce
`receiptImageUploadAllowed` (+ redaction policy) the same way
`RECEIPT_IMAGE_CLOUD_UPLOAD` is handled, or route OCR callers through
`requireAllowed` only and remove the capability from the gate's generic list.
The 2026-09-19 diagnosis menu overstates this as an active leak; treat as P2
hardening.

Related test note: `PrivacyCapabilityHandlingPolicyTest` and
`UPR5CompletionTest` were updated 2026-09-20 (test-side only) to reflect the
resolver's actual, stricter contract.

---

## PB-04 raw_money_aggregates guard fails on 88 pre-existing raw aggregates (OPEN — NEEDS-HUMAN policy decision)

Severity: MEDIUM (guard integrity: a blocking guard is red; money hygiene
debt is real but long-standing)

The `raw_money_aggregates` guard (G-MONEY-RAW-01..07,
`scripts/verify_raw_money_aggregates.py`) is a 1:1 transcription of the
retired KTS scanner `checkRawMoneyAggregates` (verified against
`scripts/guards/check_raw_money_aggregates.kts` at bda01f16~1 — same rules,
same 7-file allowlist). It is registered BLOCKING with NO baseline by design
("never creates or updates a baseline; a violation always fails"). Current
tree: 88 violations across ~20 production files (GroupBalanceCalculator,
FinancialHealthCalculator/ScoreV2, SynthesisEngine, ReceiptParser,
SavingsGamificationEngine, SmartSavingsEngine, ComputeDashboardWidgetsUseCase,
ComputeMoneyRadarUseCase, MonthlySavingsSweepUseCase, AnalyticsViewModel,
TotalsDashboardCard, CashFlowCalendarScreen, BillRemindersScreen, ...).

Historical note: the KTS-era task was `check`-wired but required a `kotlin`
binary on PATH; enforcement historically was inconsistent at best, so the 88
findings are long-standing debt newly surfaced by real enforcement — NOT a
recent regression.

Owner decision required (FG-06/FG-07 — cannot be resolved by agents):
- Option A: approve a dedicated money-safety lane routing the 88 sites
  through MoneyAggregate / normalized money primitives (production refactor,
  strict mode, money blast radius).
- Option B: approve creating a baseline for the guard (registry change from
  `baseline: None` to a ratchet with the current 88 fingerprints) so the
  ratchet locks "no growth" while the debt is paid down incrementally.
Doing nothing keeps the whole static-guard suite red (fail-closed) — the
suite's exit code is then not a useful signal for OTHER regressions.

---

## PB-05 DB-access gate cannot model RestoreInternalWriteScope.run{} (OPEN — parked engine batch)

Severity: MEDIUM (keeps db_access guard at INFRA/exit-2, fail-closed, and
known_good_state row 1 red; no runtime impact)

After the RP-03 P7-009 reorder,
`DatabaseBackupRepositoryImpl.restoreReceiptAssets` performs its
`ScannedReceiptDao.update` inside
`restoreInternalWriteScope.run("restoreReceiptAssets.updateImagePath") { ... }`
(DatabaseBackupRepositoryImpl.kt:1581-1583). The GR mediation engine's
barrier vocabulary (`scripts/db_guard/structural_analysis/tokenizer.py`)
recognizes `writeBarrier.runWrite(op) { }`, `writeBarrier.checkWritesAllowed()`
and `*.runGuarded[WithContext] { }` — but NOT
`RestoreInternalWriteScope.run(label) { }` — so the mutation cannot be
correlated with its policy row and the subject collapses to
`unsupported_source` / `GR13_NO_D4_OBSERVATION`, failing stage-4 evidence
with the umbrella `DB_POLICY_SOURCE_EVIDENCE_INVALID` (observed verbatim in
`build/guard-debug/known-good-state/active-db-gate.findings.json`).

Recorded remedy (docs/ci/db-mediation/GR-14_OWNER_ACCEPTANCE_REGISTRY.yml
header, commit 8c11ecb3): the parked "engine-typing batch" — teach the D4
discovery/body-slicing to model restore-internal scope runs. This is a
design-level engine change (new barrier-marker kind, proof-layer semantics,
dedicated engine tests), NOT a regex tweak; it is explicitly recorded as NOT
owner-acceptable to mask via data/allowlist regeneration. Do not "fix" the
red gate by weakening policy — fix the engine.

---

## Resolved since the 2026-09-19 diagnosis menu (for the record)

- `CalculateFinancialForecastUseCase` manual-recurring drop (was REAL-PROD-BUG):
  FIXED by `042d68db` (S3) — `manualRecurringEntities = recurringEntities`.
- `DatabaseBackupRepositoryImplTest` message assertions: FIXED 2026-09-20
  (test-side truth-sync to the sanitized reason codes; no production bug —
  rollback/barrier behavior is correct and covered by passing tests).
- MigrationRegistration registration pins for retired pre-baseline paths
  (117→119, 141→142, 142→143): test-side FIXED 2026-09-20 (matrix rewritten
  around the v145 baseline). The 119→121 evidence tests remain failing under
  PB-02.
- OCR test map/consent staleness: see PB-03 test note.
