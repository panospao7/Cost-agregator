# Database Write Ownership Map

Part of: Global Write/Read/Restore Barrier — PR 5

> **Transitional state (PR-01):** The v1 policy (`config/guards/db_ownership_policy.yml` + structural exceptions) remains the **ACTIVE gate**, and its current blocked state is expected until v2 activation. A new authoritative v2 policy model now exists under `scripts/db_guard/` (`policy_model.py`, `policy_v2_loader.py`, `policy_v2_evidence.py`) but is **NOT wired into enforcement**. Candidate generation is GR-02; activation is GR-07. No policy/baseline files were changed by PR-01.
>
> **Transitional state (GR-02):** The checked-in candidate `config/guards/db_ownership_policy.signatures.candidate.yml` is **MACHINE-GENERATED** by `scripts/migrate_db_policy_signatures.py` — regenerate it only via that tool, never hand-edit. It is **NOT active policy** and remains explicitly non-authoritative. Unresolved migration rows visible in the migration report are **NOT authorization**; `barrierMode: direct` is metadata only until proven (GR-05/GR-11 own mediated-path classification/proof). GR-05 owns complete policy coverage; GR-07 owns activation.
>
> **Transitional state (GR-03):** Production Kotlin source roots are now declared in `config/guards/production_source_roots.yml` — the **single authoritative manifest** (currently the `:app` main source set at `app/src/main/java`). All DB guard subsystems resolve production roots through `scripts/db_guard/source_roots.py`. Undeclared production Kotlin roots fail closed (`DB_SOURCE_ROOT_UNDECLARED` / `DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED`) and no partial inventory is trusted after a root-contract failure: a root-contract failure is an infrastructure/untrusted condition, distinct from architecture violations. Excluded trees: test/androidTest/debug/release/build/generated. **Syntax/membership split complete (PR-GR-03R):** `kotlin_callable_parser.canonical_source_path`, `db_policy_signature.FunctionSignature`, and `declaration_scanner._validate_diagnostic_path` perform syntax-only validation (generic repo-relative POSIX `.kt` paths); `scanner._diag_from_text` itself checks only the text shape — a registered diagnostic code, a `.kt` suffix, and an optional trailing `:<digits>` line — while canonical POSIX form is enforced downstream by `GuardDiagnostic`'s path validation (non-canonical text degrades to a pathless diagnostic); topology membership is validated separately by root-aware stages via `source_roots.is_declared_production_path`. No hidden `app/src/` prefix authority remains outside `source_roots.py`.
>
> **Transitional state (GR-04):** The structural expected-method manifest (`config/guards/db_structural_exceptions_expected_methods.yml`) governs structural exceptions ONLY: exact `(path, class, method_pattern, operation)` tuple classification/equality against the immutable expected/fixtures contracts, duplicate rejection, structural source evidence, and the `structural_entries: 62` audit pin. Ownership cardinality was removed from the manifest and is an observational migration metric, NOT structural authorization evidence; a manifest carrying `counts.ownership_entries` fails closed as an unknown-count-key configuration error. Because no ownership count is pinned, ownership-policy entry splitting (v2) can no longer break structural validation.

Every table family has exactly one approved write owner — an architectural objective, enforced exactly only after v2 activation (GR-07); until then the legacy v1 gate applies with known overload-union limitations.
Direct DAO mutation outside the canonical DB ownership policy is a violation caught by the static guard (PR 6/10).

The **canonical sources of truth** for all DB write authorization are:

- `config/guards/db_ownership_policy.yml` — enumerates every approved writer class, method, DAOs, and operation.
- `config/guards/db_structural_exceptions.yml` — grants approval for intrinsically low-level DB infrastructure operations (migrations, rescue, backup/restore, diagnostics, privacy export).

All ownership authorization is decided by exactly those two files — an architectural objective, enforced exactly only after v2 activation (GR-07); until then the legacy v1 gate applies with known overload-union limitations. In addition,
`config/guards/db_structural_exceptions_expected_methods.yml` is a **mandatory
integrity/classification manifest**: it pins the exact `expected`/`fixtures`
tuple classification of the structural exceptions file against immutable
checked-in contracts and enforces the structural entry-count audit pin
(`counts.structural_entries: 62`) — ownership cardinality is not pinned and is
an observational migration metric only (see the GR-04 transitional note above).
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
- **Transitional guard state:** The static guard currently reports `SIGNATURE_MISSING` / `DB_POLICY_SOURCE_EVIDENCE_INVALID` for entries lacking signatures; this blocked state is intentional pre-v2 and is expected until v2 activation (GR-07).
- **Canonical policy:** `config/guards/db_ownership_policy.yml` is the source of truth for approved write owners — an architectural objective, enforced exactly only after v2 activation (GR-07); until then the legacy v1 gate applies with known overload-union limitations. Each entry enumerates an exact class + method + DAOs + operation. Wildcard `"*"` method entries are not supported — every writer method must be individually listed.
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

All DB write authorization is decided by exactly two files (an architectural objective — enforced exactly only after v2 activation (GR-07); until then the legacy v1 gate applies with known overload-union limitations):

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
audit pin (`counts.structural_entries: 62`); ownership cardinality is not
pinned and remains an observational migration metric only (see the GR-04
transitional note above). **The
manifest grants no authorization** — only an exact
ownership-policy entry or a structural exception entry authorizes a write.
Baselines never authorize writes either (see below).

The legacy `config/db_access_allowlist.yml` is superseded by the policy files
above and is not a source of authorization.

### Ratchet baseline: debt tracking only, not authorization

`config/baselines/db_access.json` (driven by `scripts/ci/guard_ratchet.py`)
records the DB access guard's current findings so CI can enforce no-growth.
Recording a writer there **does not authorize** any DAO write — it only documents
existing uncovered debt. Only an exact canonical ownership policy entry or a
structural exception entry authorizes a DAO mutation; the ratchet baseline is
never an authorization path. New writers must be added to
`db_ownership_policy.yml` — never to the baseline alone.

### Unresolved categories (not authorized)

The following write paths remain outside the ownership policy and must NOT be
treated as approved:

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
- **Current DB ratchet debt** — `config/baselines/db_access.json` lists the
  currently uncovered writers (`UNALLOWLISTED_CLASS` / `UNALLOWLISTED_CLASS_DIRECT_CHAIN` /
  `FORBIDDEN_FILE_OP` findings). These are debt, not authorization; resolving them
  means adding entries to the ownership policy / structural exceptions, never
  deleting the baseline record.
