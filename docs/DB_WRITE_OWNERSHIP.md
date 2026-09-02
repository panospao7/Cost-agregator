# Database Write Ownership Map

Part of: Global Write/Read/Restore Barrier — PR 5

## Current state (authoritative — PR-GR-10D truth sync)

The **active DB ownership policy is schema v2 only** (`policyMode:
authoritative-v2`). The v1 policy is archived; the schema-v2 signatures
candidate (`config/guards/db_ownership_policy.signatures.candidate.yml`,
472 entries, byte-reproducible under the `db_artifact_sync` tripwire) is the
activated policy input; and the v2 ratchet baseline
(`config/baselines/db_access_v2.json`) is empty — recorded debt is never
authorization.

- **GR-07 activation has landed.** The DB gate was verified exit-0 / trusted
  with 0 findings, 20 advisory diagnostics, and 0 blocking diagnostics at
  SHA 565018c5eed61fae4351cb59342dc5c274eb27e7 — evidence record
  `gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7` in
  docs/ci/GUARD_EVIDENCE_INDEX.yml (two capture runs, byte-identical semantic
  summaries).
- **GR-08 triage is complete at zero trusted findings** (same evidence
  record, gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7); the reviewed
  debt state is tracked in docs/ci/DB_ACCESS_V2_RATCHET_DEBT.md (approved
  temporary debt: 0).
- **GR-10A/GR-10B control-plane ownership is landed:** canonical commands
  compile from the registry execution schema (see
  docs/ci/GUARD_COMMANDS.generated.md, guard `db_access`) and every guard
  carries one unified source-scope classification
  (docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md).
- **Advisory diagnostics are reported, never authorization,** and never
  baseline-able debt. The current count comes only from the evidence index
  (20 × `DB_SIGNATURE_UNRESOLVED` at the verified SHA). GR-10C advisory-debt
  closure is PARTIAL: no closure evidence is recorded in
  docs/ci/GUARD_EVIDENCE_INDEX.yml.
- **`barrierMode` is metadata, not proof.** `direct` / `helper` /
  `workerMediated` describe policy metadata only; dominance or call-graph
  proof of write-barrier mediation is future work (GR-12/GR-13/GR-15).

<!-- GUARD_STATUS:BEGIN db_access -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS (exit 0, trusted, 0 findings, 20 advisory diagnostics, 0 blocking diagnostics — advisory diagnostics are reported, never authorization; ratchet exit 0 (baseline config/baselines/db_access_v2.json: 0 new, 0 resolved, 0 expired))
Debt: 0 unchanged ratchet findings (baseline 0) — recorded debt is never authorization
Gradle leg: INFRASTRUCTURE
Canonical command reference: GUARD_COMMANDS.generated.md#db_access
Scope: production-kotlin-all
Owner: @panospao7 (doc anchor: docs/ci/GUARD_FINDING_PROTOCOL.md)
<!-- GUARD_STATUS:END db_access -->

The block above is renderer-owned (`scripts/ci/generate_guard_docs.py`);
manual edits inside its markers fail
`scripts/ci/verify_guard_docs_truth.py`. Canonical commands are generated,
never pasted: docs/ci/GUARD_COMMANDS.generated.md is the only command
reference. Distinctions that must never be blurred: the DB ownership policy
≠ the DB ratchet baseline ≠ the Room schema/migration baseline; implemented
≠ verified at the current SHA; a historical finding count ≠ the current
finding count.

> **HISTORICAL_RECORD** — the transitional-state notes below describe the
> pre-activation era (PR-01 through GR-07). They document states that no
> longer hold and are retained verbatim as history; the current truth is the
> section above plus docs/ci/GUARD_EVIDENCE_INDEX.yml.

<!-- HISTORICAL_RECORD:BEGIN (as-of: pre-GR-07 activation era; scope: v1/transition states only; superseded by the Current state section above) -->
>
> **Transitional state (PR-01):** The v1 policy (`config/guards/db_ownership_policy.yml` + structural exceptions) remains the **ACTIVE gate**, and its current blocked state is expected until v2 activation. A new authoritative v2 policy model now exists under `scripts/db_guard/` (`policy_model.py`, `policy_v2_loader.py`, `policy_v2_evidence.py`) but is **NOT wired into enforcement**. Candidate generation is GR-02; activation is GR-07. No policy/baseline files were changed by PR-01.
>
> **Transitional state (GR-02):** The checked-in candidate `config/guards/db_ownership_policy.signatures.candidate.yml` is **MACHINE-GENERATED** by `scripts/migrate_db_policy_signatures.py` — regenerate it only via that tool, never hand-edit. It is **NOT active policy** and remains explicitly non-authoritative. Unresolved migration rows visible in the migration report are **NOT authorization**; `barrierMode: direct` is metadata only until proven (GR-05/GR-11 own mediated-path classification/proof). GR-05 owns complete policy coverage; GR-07 owns activation.
>
> **Transitional state (GR-03):** Production Kotlin source roots are now declared in `config/guards/production_source_roots.yml` — the **single authoritative manifest** (currently the `:app` main source set at `app/src/main/java`). All DB guard subsystems resolve production roots through `scripts/db_guard/source_roots.py`. Undeclared production Kotlin roots fail closed (`DB_SOURCE_ROOT_UNDECLARED` / `DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED`) and no partial inventory is trusted after a root-contract failure: a root-contract failure is an infrastructure/untrusted condition, distinct from architecture violations. Excluded trees: test/androidTest/debug/release/build/generated. **Syntax/membership split complete (PR-GR-03R):** `kotlin_callable_parser.canonical_source_path`, `db_policy_signature.FunctionSignature`, and `declaration_scanner._validate_diagnostic_path` perform syntax-only validation (generic repo-relative POSIX `.kt` paths); `scanner._diag_from_text` itself checks only the text shape — a registered diagnostic code, a `.kt` suffix, and an optional trailing `:<digits>` line — while canonical POSIX form is enforced downstream by `GuardDiagnostic`'s path validation (non-canonical text degrades to a pathless diagnostic); topology membership is validated separately by root-aware stages via `source_roots.is_declared_production_path`. No hidden `app/src/` prefix authority remains outside `source_roots.py`.
>
> **Transitional state (GR-04):** The structural expected-method manifest (`config/guards/db_structural_exceptions_expected_methods.yml`) governs structural exceptions ONLY: exact `(path, class, method_pattern, operation)` tuple classification/equality against the immutable expected/fixtures contracts, duplicate rejection, structural source evidence, and the `structural_entries: 62` audit pin. Ownership cardinality was removed from the manifest and is an observational migration metric, NOT structural authorization evidence; a manifest carrying `counts.ownership_entries` fails closed as an unknown-count-key configuration error. Because no ownership count is pinned, ownership-policy entry splitting (v2) can no longer break structural validation.
>
> **Transitional state (GR-05):** The machine-generated signatures candidate now carries **48 resolved keys of 99 legacy inputs** (one entry per unique canonical mutation key after dedupe folding); the remaining **51 inputs are explicit debt rows by closed status** (`PARSER_UNCERTAIN=16`, `DAO_IDENTITY_UNRESOLVED=10`, `CALLABLE_MISSING=8`, `PARSER_UNSUPPORTED=11`, `CALLABLE_AMBIGUOUS=5`, `MUTATION_PAIR_MISSING=1`) and remain **NOT authorization**. A standalone accounting artifact (`config/guards/db_ownership_policy.signatures.accounting.json`) ships beside the candidate, tying every legacy index to exactly one outcome. Its `sourceMutations` coverage machinery is **live in the generator**: `--generate` runs now classify every caller-side DAO mutation observed in the declared production tree as covered by a resolved row, matching an unresolved row's intent, outside the legacy policy, or analyzer-input-limited (evidence only; it never adds candidate entries) and fail closed rather than ship degraded coverage. The checked-in accounting artifact still carries the pre-coverage empty `sourceMutations` list — **regenerating both artifacts via `scripts/migrate_db_policy_signatures.py --generate` is required and pending**; never hand-edit them. The Room-inventory writer's durability amendment reports a Windows-style replace as `INVENTORY_DURABILITY_UNCONFIRMED` where no confirmable directory durability barrier exists, instead of claiming durable success. **Status: pending human validation** — no DONE/GREEN/complete claim is made for GR-05 in this document.
>
> **Transitional state (GR-06, Slice 3 — shadow evidence CLI):** `scripts/ci/verify_db_policy_v2_evidence.py` verifies the **v2 signatures candidate** against exact production source evidence (`policy_v2_evidence.verify_v2_policy_source_evidence` over the declared source-root manifest, with the Room-inventory-backed `daoFqcn` cross-check ACTIVE) and writes one deterministic JSON report. It is **shadow-only**: read-only over every input, it never writes or activates policy, never touches the ratchet, and its exit code (0 trusted / 2 untrusted-or-infrastructure) never depends on the optional legacy shadow comparison. With `--legacy-shadow-report`, differences vs a legacy `verify_db_access_boundaries` findings report are classified report-only against the GR-05 accounting artifact into exactly five closed classes (`EXPECTED_LEGACY_OVERLOAD_UNION`, `LEGACY_STALE_ENTRY`, `PARSER_OR_RESOLVER_DEFECT`, `CANDIDATE_GAP`, `UNREVIEWED_DIFFERENCE`); the section ships `reviewed: false`, and `CANDIDATE_GAP`, `PARSER_OR_RESOLVER_DEFECT`, and `UNREVIEWED_DIFFERENCE` deltas **block GR-07 activation** until human-reviewed. **Status: pending human validation** — no DONE/GREEN/complete claim is made for GR-06 in this document.
>
> **Transitional state (GR-07 Option-B amendment — scanner trust contract):** Scanner-family per-callable diagnostics (`scripts/db_guard/scanner.py`) are now split into **BLOCKING vs ADVISORY** by the DB relevance of the enclosing callable. A diagnostic on a callable whose declaration range shows DB-surface evidence — a `_METHOD_CALL` against a known DAO accessor/operation name from the Room inventory, or any structural operation/handle token (`execSQL`, `openDatabase`, `getDatabasePath`, `deleteRecursively`, `writableDatabase`) — stays **BLOCKING**: the scan is untrusted (exit 2) and findings are withheld. A diagnostic on a callable with **no DB-relevant content** (Compose/UI/service code that never touches a DAO or DB handle) is reported verbatim with the bounded `controlled_context["advisory"] = true` marker and **never breaks trust**. Trust is computed over blocking diagnostics only (`statistics.trusted`); with zero blocking diagnostics, discovered findings survive as a trusted exit 1 (real GR-08/ratchet input), and `statistics.advisoryDiagnosticCount` reports the advisory load. **Pre-scan stage failures (source roots, inventory, loader, evidence) are NEVER advisory** and remain always blocking; an unknown operation on a VERIFIED database handle is likewise always blocking even without name evidence. Rationale: honest unresolved-signature debt on pure UI callables must not permanently block downstream consumption of real DB findings. Status: implemented with targeted tests in `scripts/test_db_guard_scanner_d4.py` and `scripts/test_verify_db_access_v2.py`; **test run pending**.
<!-- HISTORICAL_RECORD:END -->

Every table family has exactly one approved write owner — enforced by the active schema-v2 ownership policy (GR-07 activation); the gate was verified exit-0/trusted at SHA 565018c5eed61fae4351cb59342dc5c274eb27e7 (docs/ci/GUARD_EVIDENCE_INDEX.yml, record gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7).
Direct DAO mutation outside the canonical DB ownership policy is a violation caught by the static guard (PR 6/10).

The **canonical sources of truth** for all DB write authorization are:

- `config/guards/db_ownership_policy.yml` — enumerates every approved writer class, method, DAOs, and operation.
- `config/guards/db_structural_exceptions.yml` — grants approval for intrinsically low-level DB infrastructure operations (migrations, rescue, backup/restore, diagnostics, privacy export).

All ownership authorization is decided by exactly those two files — enforced by the active schema-v2 ownership policy (GR-07 activation; verified exit-0 at SHA 565018c5eed61fae4351cb59342dc5c274eb27e7, docs/ci/GUARD_EVIDENCE_INDEX.yml). In addition,
`config/guards/db_structural_exceptions_expected_methods.yml` is a **mandatory
integrity/classification manifest**: it pins the exact `expected`/`fixtures`
tuple classification of the structural exceptions file against immutable
checked-in contracts and enforces the structural entry-count audit pin
(`counts.structural_entries: 64`) — ownership cardinality is not pinned and is
an observational migration metric only (the pre-activation GR-04 note below
records the historical 62-entry pin; the live manifest now pins 64).
**The manifest
grants NO authorization** — only an exact ownership-policy entry or a structural
exception entry authorizes a write. Ratchet baselines likewise never authorize
writes (see below).

The legacy `config/db_access_allowlist.yml` is **superseded** by the files above and must not be treated as the active source of truth.

---

## Table family → approved writer

| Table family | Approved writer(s) | Status |
|---|---|---|
| expenses, transaction_events | `TransactionLifecycleCoordinator` | ✅ |
| expenses (backfill only) | `ExpenseRepository` guarded methods | ⚠️ migrate to `ExpenseWriteStore` (PR 11) |
| raw_notifications, pending_reviews | `NotificationRepository` | ✅ |
| raw_notifications, pending_reviews (purge) | `DataRetentionWorker` | ⚠️ migrate to `RetentionCoordinator` |
| privacy_audit_events | `DataRetentionWorker` (audit-only) | ✅ — retention purge audit trail |
| scanned_receipts, receipt_events, email_receipts, receipt_expense_links | `ReceiptLifecycleCoordinator` | ✅ |
| scanned_receipts (match status), receipt_events (match events) | `ReceiptMatchLifecycleService` | ✅ — match mutations + atomic `claimForAutoMatch`; writes `MATCH_ATTEMPTED`/`MATCH_NOT_FOUND`/`MATCH_SKIPPED_DOCUMENT_TYPE`/`AUTO_MATCH_LINK_FAILED`/`MATCH_SUGGESTED` under barrier + transaction |
| receipt_expense_links (link/unlink) | `ReceiptLinkService` | ⚠️ must use `DatabaseWriteBarrier` (PR 7) |
| recurring_expenses | `RecurringRuleLifecycleCoordinator` | ✅ |
| recurring_lifecycle_events | `RecurringLifecycleEventWriter` (via writeCritical/writeDiagnostic) | ✅ |
| recurring_occurrences | `RecurringLifecycleCoordinator`, `RecurringRuleLifecycleCoordinator`, `RecurringOccurrenceMaterializer` | ✅ |
| recurring_reminder_deliveries | `RecurringLifecycleCoordinator`, `RecurringRuleLifecycleCoordinator`, `BillReminderWorker` | ✅ |
| budgets, budget_adjustments | `BudgetRepository` | ✅ |
| budget_forecasts | `BudgetForecastingEngine` | ✅ |
| planned_expenses | `RecurringLifecycleCoordinator`, `RecurringRuleLifecycleCoordinator` (via PlannedExpenseDao inside coordinator transactions) | ✅ |
| bank_connections | `BankConnectionLifecycleCoordinator` | ⚠️ create coordinator (PR 7) |
| investments, investment_transactions, investment_values | `InvestmentRepository` | ✅ |
| savings_goals, savings_sweep_plans | `SavingsGoalRepository` | ✅ |
| subscription_candidates, subscription_price_history, subscription_usage | `SubscriptionRepository` | ✅ |
| warranties, warranty_lifecycle_events | `WarrantyRepository` | ✅ |
| warranty_reminder_deliveries | `WarrantyExpirationWorker` (via `WarrantyReminderDeliveryDao`: claim-before-notify; `SENT` only on `DELIVERED`) | ✅ — durable replacement for the removed SharedPreferences sent-state |
| expense_groups, group_members, group_expenses, group_settlements | `GroupLifecycleCoordinator`, `GroupTransactionCoordinator` | ✅ |
| categories | `CategoryRepository` | ✅ |
| merchant_categories, merchant_normalizations, merchant_locations | `MerchantCategoryRepository` | ✅ |
| spending_challenges | `SpendingChallengeRepository` | ✅ |
| anomaly_alerts | `AnomalyAlertRepositoryImpl` | ✅ |
| mileage_tracking | `BusinessExpenseRepository` | ✅ |
| exchange_rates | `ExchangeRateStoreAdapter` | ✅ |
| ai_artifacts | `AiArtifactRepository` | ✅ |
| ai_chat_messages, ai_chat_sessions | `AiArtifactRepository`, `AiChatRepositoryImpl` | ✅ |
| prompt_states | `PromptStateRepository` | ✅ |
| background_job_runs | `WorkerRunLoggerImpl` | ✅ |
| pipeline_diagnostic_events | `PipelineDiagnosticEventRepository` | ⚠️ route through `MaintenanceSafeDiagnosticSink` (PR 9) |
| DB file operations | `DatabaseBackupRepositoryImpl` | ✅ (file-level only, under maintenance mode) |

---

## Rules

1. **One owner per table family.** If two classes write the same table, one must delegate to the other.
2. **Every write entrypoint checks `DatabaseWriteBarrier`.** No exception except Room migrations.
3. **Workers do not write DAOs directly.** They call coordinator/repository methods.
4. **Debug-only writes** require `BuildConfig.DEBUG` guard AND `writeBarrier.checkWritesAllowed()`.
5. **Entries marked ⚠️** are temporary exceptions tracked via issues linked from `config/guards/db_ownership_policy.yml`. The legacy `config/db_access_allowlist.yml` file is superseded; new authorizations must go into the ownership policy.
6. **Ratchet baselines** track unresolved debt only (writers not yet listed in the ownership policy). They do **not** authorize writes — only an exact canonical ownership policy entry or a structural exception entry authorizes a DAO mutation. Baselines never make a write legal.

---

## Not approved

| Pattern | Reason |
|---|---|
| UI / ViewModel direct DAO calls | No lifecycle coordination, no barrier |
| Worker direct DAO calls (outside the canonical DB ownership policy) | Must go through coordinator |
| Email service direct DAO writes | Must delegate to `ReceiptLifecycleCoordinator` |
| Any DAO write after DB file swap without fresh Room instance | Stale Room — use `AppDatabase.fileBuilder()` |

---

## Enforcement

- **Runtime:** `DatabaseWriteBarrier.checkWritesAllowed()` throws `DatabaseAccessBlockedException` in all non-NORMAL modes.
- **Static (warning):** `scripts/verify_db_access_boundaries.py` reports violations (PR 6).
- **Static (CI failure):** Same script exits non-zero on new violations (PR 10).
- **Active guard state:** The static DB gate runs the schema-v2 ownership policy with the D4 scanner. At the verified SHA 565018c5eed61fae4351cb59342dc5c274eb27e7 it exited 0 / trusted with 0 findings and 20 advisory `DB_SIGNATURE_UNRESOLVED` diagnostics (docs/ci/GUARD_EVIDENCE_INDEX.yml, record gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7). Pre-scan stage failures remain always blocking; an untrusted scan exits 2 with findings withheld.
- **Option-B advisory diagnostics:** Scanner diagnostics on callables that never touch a DAO or DB handle are advisory (`controlled_context.advisory = true`): reported, but non-blocking — they never produce exit 2 and never withhold findings. Only blocking diagnostics (DB-relevant callables and every pre-scan stage failure) take the exit-2 path (see the GR-07 Option-B transitional note above).
- **Canonical policy:** `config/guards/db_ownership_policy.yml` is the source of truth for approved write owners, enforced through the active schema-v2 policy (GR-07 activation; verified exit-0 at SHA 565018c5eed61fae4351cb59342dc5c274eb27e7 — docs/ci/GUARD_EVIDENCE_INDEX.yml). Each entry enumerates an exact class + method + DAOs + operation. Wildcard `"*"` method entries are not supported — every writer method must be individually listed.
- **Structural exceptions:** `config/guards/db_structural_exceptions.yml` grants approval for DB file operations (Room migrations, maintenance rescue, backup/restore, diagnostics, privacy export, etc.) via exact method_pattern + operation matching.
- **Structural manifest:** `config/guards/db_structural_exceptions_expected_methods.yml` is a mandatory integrity/classification manifest enforced by `verify_db_access_boundaries.py`. It requires the current structural-exception tuple set to EXACTLY equal the manifest's `expected` + `fixtures` tuple set, the `expected` set to exactly equal the immutable expected contract, and the `fixtures` set to exactly equal the immutable fixture contract (a moved or invented tuple fails with `MANIFEST_CLASSIFICATION_MISMATCH`, exit 2). **The manifest grants NO authorization** — it only verifies that the structural exceptions file matches its recorded classification.
- **Ratchet baselines:** The ratchet baseline records unresolved debt (writers that exist in code but are not yet listed in the ownership policy). Baselines do **not** authorize new ownership; they document existing debt that must be resolved. Baselines never authorize writes — only an exact canonical ownership-policy entry or a structural exception entry authorizes a DAO mutation. New writers must be added to `db_ownership_policy.yml` — never to the baseline alone.
- **Legacy allowlist:** `config/db_access_allowlist.yml` is retained for backward compatibility only and is superseded by the ownership policy + structural exceptions files above. Do not treat it as the active source of truth.

### Pipeline 4 Enforcement Additions
- **`RecurringArchitectureGuardTest`** — 19 static architecture guard tests enforce:
  - No direct recurring rule DAO mutation outside `RecurringRuleLifecycleCoordinator`
  - No raw `updateOccurrenceStatus` calls outside coordinator (must use `RecurringOccurrenceStatus` enum)
  - No legacy `markBillPaid`/`markRuleBillAsPaid` in production code
  - Critical events use `RecurringLifecycleEventWriter` not direct DAO
  - Deactivation deletes (not cancels) open PLANNED rows
  - No `0L` placeholder occurrence IDs in reconcile results

---

## H2 ownership-policy sync — canonical sources and unresolved debt

### Canonical sources of truth

All DB write authorization is decided by exactly two files, enforced through the active schema-v2 policy (GR-07 activation; verified exit-0 at SHA 565018c5eed61fae4351cb59342dc5c274eb27e7, docs/ci/GUARD_EVIDENCE_INDEX.yml):

- **`config/guards/db_ownership_policy.yml`** — the canonical ownership policy.
  It enumerates every approved writer as an exact `(class, method, daos,
  operation)` tuple. Wildcard `method = "*"` and the generic `operation: write`
  are invalid policy metadata and are rejected by the loader
  (`verify_db_access_boundaries.py`).
- **`config/guards/db_structural_exceptions.yml`** — the canonical structural
  exceptions file for intrinsically low-level DB file operations (migrations,
  rescue, backup/restore, diagnostics, privacy export) via exact
  `method_pattern` + `operation` matching.

`config/guards/db_structural_exceptions_expected_methods.yml` is a mandatory
integrity/classification manifest (not an authorization file): it pins the exact
`expected`/`fixtures` tuple classification of the structural exceptions file
against immutable checked-in contracts and enforces the structural entry-count
audit pin (`counts.structural_entries: 64`; the pre-activation GR-04 note
records the historical 62-entry pin); ownership cardinality is not
pinned and remains an observational migration metric only. **The
manifest grants no authorization** — only an exact
ownership-policy entry or a structural exception entry authorizes a write.
Baselines never authorize writes either (see below).

The legacy `config/db_access_allowlist.yml` is superseded by the policy files
above and is not a source of authorization.

### Ratchet baseline: debt tracking only, not authorization

`config/baselines/db_access_v2.json` (driven by `scripts/ci/guard_ratchet.py`
with finding protocol 2) records the DB access guard's reviewed debt so CI
can enforce no-growth. At the verified SHA 565018c5eed61fae4351cb59342dc5c274eb27e7
the v2 baseline is empty — 0 entries, 0 new, 0 resolved, 0 expired
(docs/ci/GUARD_EVIDENCE_INDEX.yml, record
gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7). The legacy v1 baseline
`config/baselines/db_access.json` is archived history, not an input of the
active gate. Recording a writer in a baseline **does not authorize** any DAO
write — it only documents existing uncovered debt. Only an exact canonical
ownership policy entry or a structural exception entry authorizes a DAO
mutation; the ratchet baseline is never an authorization path. New writers
must be added to `db_ownership_policy.yml` — never to the baseline alone.

### Historical v1-era uncovered writers (HISTORICAL_RECORD — not current)

> **HISTORICAL_RECORD** — the list below is the v1-era uncovered-writer
> snapshot from before GR-07 activation. Under the active schema-v2 policy
> the GATE-00R scan at SHA 565018c5eed61fae4351cb59342dc5c274eb27e7 found
> zero unauthorized mutations (0 findings, trusted — see
> docs/ci/GUARD_EVIDENCE_INDEX.yml, record
> gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7). The v1 ratchet debt
> rows were dispositioned by the GR-08 triage batches into the empty v2
> baseline; the rows below are history only — neither current findings nor
> authorization.

<!-- HISTORICAL_RECORD:BEGIN (as-of: v1 era, pre-GR-07; scope: legacy uncovered-writer snapshot; superseded by the v2 gate evidence) -->

The following write paths remained outside the then-active v1 ownership
policy and had to NOT be treated as approved:

- **`merchantCategoryDao`** — `CategoryRepository` seed/normalization writes
  (`insertAll`, `updateNormalizedCanonicalName` in
  `data/repository/CategoryRepository.kt`). The ownership policy only authorizes
  `CategoryRepository.addCategory` / `deleteCategory` on `categoryDao`.
- **`ExpenseRepository.userCorrectionDao`** — user-correction insert in
  `data/repository/ExpenseRepository.kt`. `ExpenseRepository` has no
  ownership-policy entry.
- **`DatabaseBackupRepositoryImpl.scannedReceiptDao`** — restore-time
  `scannedReceiptDao.update` of receipt image paths inside
  `DatabaseBackupRepositoryImpl`. Structural exceptions cover only exact
  structural-operation tuples for this class (`execSQL`, `openDatabase`,
  `getDatabasePath`, `deleteRecursively`, `writableDatabase`), not DAO writes.
  Structural exceptions are exact path/class/method/operation tuples and do
  not authorize arbitrary raw file or database operations.
- **Notification repair/scheduler paths** — `NotificationIntakeCoordinator`,
  `NotificationIntakeWorker`, `NotificationIntakeRecoveryScheduler`, and
  `NotificationIntakePayloadRepairer` write `intakeDao` directly
  (`insertOrIgnore`, `claimForProcessing`, `markTerminal`, `markFinalFailure`,
  `markRetryableFailure`, `purgeVisiblePayload`, `releaseStaleProcessing`, etc.)
  without an ownership-policy entry.
- **v1-era DB ratchet debt** — the legacy `config/baselines/db_access.json`
  listed the then-uncovered writers (`UNALLOWLISTED_CLASS` /
  `UNALLOWLISTED_CLASS_DIRECT_CHAIN` / `FORBIDDEN_FILE_OP` findings). Those
  were debt, never authorization; they were resolved/dispositioned through
  the GR-08 triage batches and the empty v2 baseline
  (`config/baselines/db_access_v2.json`), never by deleting baseline records.

<!-- HISTORICAL_RECORD:END -->
