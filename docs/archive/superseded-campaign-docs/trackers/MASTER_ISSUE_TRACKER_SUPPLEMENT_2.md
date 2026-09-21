# Master Issue Tracker Supplement 2 — Verification Patch

Last verified: 2026-06-15  
Base tracker: `MASTER_ISSUE_TRACKER.md` with MIT-001 through MIT-073  
Source docs commit: `14659197606b6fbe9874ed09639c53bd64007bea`  
Primary audited code in reports: `83b798e849b4408b2bf683f52cb2746d37f7af16`

## Verification Verdict

The current master tracker is **structurally correct** and captures the major release blockers across P1–P18.

However, this second pass found several findings that are either:

- missing,
- too broad,
- not explicitly testable,
- or hidden under another issue without enough acceptance criteria.

This supplement should be treated as part of the release gate.

---

# 1. Required Patches to Existing Issues

## Patch MIT-034 — Add P1 to cancellation coverage

Current MIT-034 pipelines should include **P1**.

Add tasks:

- [ ] Add `NotificationIntakePayloadRepairer` to cancellation static guard coverage.
- [ ] `repairLegacyPlaintextTransientRows()` must rethrow `CancellationException`.
- [ ] Add regression test for cancellation during notification-intake repair.
- [ ] Add P1 repairer to universal no-unsafe-`runCatching`/`catch(Exception)` guard.

Reason: P1 report identifies repairer CE swallowing as `P1-AUD-006`.

---

## Patch MIT-035 — Add intake conflict and pre-pipeline diagnostic coverage

Add tasks:

- [ ] Intake `insertOrIgnore` conflict/drop path must emit sanitized diagnostic or operation ledger row.
- [ ] Worker restore-blocked retry must emit durable diagnostic.
- [ ] Worker payload-unavailable/filter-rejected/max-attempt/final-failure paths must emit diagnostic.
- [ ] Pre-launch service diagnostics must be non-cancellable where terminal.

Reason: P1 and P9 both found diagnostic holes before the main repository/pipeline path.

---

## Patch MIT-043 / MIT-067 — Add legacy recurring-key and stale recurring test debt

Add tasks:

- [ ] Verify/backfill legacy `occurrenceKey` values created before source-type key format.
- [ ] Add migration/backfill test if legacy keys can exist.
- [ ] Reactivate or replace ignored `RecurringLifecycleFixesTest`.
- [ ] Repair stale `BillReminderWorkerTimeProviderTest` expectations so settings/quiet-hours behavior is tested inside guard.
- [ ] Audit P4 planned/manual entities that still default currency to EUR or store raw `Double`; either migrate, document as legacy, or route through money-normalized APIs.

Reason: P4 report says new occurrence keys are fixed for new rows but legacy key migration/backfill still needs verification; it also flags stale/ignored tests and raw `Double`/EUR legacy risk.

---

## Patch MIT-055 / MIT-072 — Add PDF/export locale and schema validity details

Add tasks:

- [ ] PDF export must use explicit UTC/configured timezone or document device-locale/device-timezone semantics.
- [ ] JSON export must have full parse/roundtrip tests for manually built rows.
- [ ] `sourceLinksJson` insertion into JSON output must be validated as legal JSON and safe against malformed embedded source-link content.
- [ ] Accounting repository broad `Exception` catch must rethrow `CancellationException`.

Reason: P12 flags system timezone/device locale and manually built JSON/source-link handling.

---

## Patch MIT-012 / MIT-030 — Add read-barrier and restore-internal-scope inventory

Add tasks:

- [ ] Inventory Room Flow/read entry points and verify `DatabaseReadBarrier` coverage.
- [ ] Add static guard or wrapper for read paths that must be blocked during restore.
- [ ] Verify `RestoreInternalWriteScope` is only used by approved restore asset/path repair code.
- [ ] Verify operation-run/restore-journal import conflicts are idempotent.

Reason: P7 found write barrier issues, but also explicitly says read barrier enforcement is caller-by-caller and needs inventory.

---

## Patch MIT-028 — Add stricter network/release checks

Add tasks:

- [ ] Release CI fails on cleartext HTTP endpoints unless explicitly allowed.
- [ ] Release CI fails on unsafe OkHttp logging interceptors.
- [ ] Release CI verifies sensitive headers are redacted in diagnostics.
- [ ] Release CI checks all raw `RequestBody` paths are derived from `PreparedCloudPayload` or explicitly local-only.
- [ ] Verify network/TLS config and debug/demo network providers cannot ship in release.

Reason: P16 and P17 both flag incomplete network/security CI.

---

## Patch MIT-033 — Add pending-review migration and nullable-fingerprint schema risks

Add tasks:

- [ ] Review migration `144→145` / pending-review table behavior; ensure no live user data can be dropped without migration/copy/destructive policy.
- [ ] Verify all nullable dedupe/fingerprint columns that are relied on for uniqueness are populated by legal paths.
- [ ] Add DB-level constraints or lifecycle validation for `raw_notifications.dedupeFingerprint`, receipt fingerprints, and email privacy-safe fingerprints where required.
- [ ] Add test proving fresh schema and migrated schema have identical dedupe constraints.

Reason: P13 highlights pending-review migration risk, nullable fingerprint gaps, and fresh-vs-migrated constraint drift.

---

# 2. New Supplementary Issues

---

## MIT-074 — Notification parser money/classification and rollback hardening

**Severity:** S1  
**Pipelines:** P1, P2, P5  
**Status:** TODO  
**Labels:** `notifications`, `money`, `dedupe`, `tests`

### Problem

P1’s main privacy and restore blockers are already tracked, but its money/parser edge cases are not explicit enough.

The report notes:

- parser fallback currency can cause duplicate false negatives,
- amount still travels as `Double` through parser/pipeline paths,
- deposit/refund/transfer classification requires behavioral tests,
- source-link failure rollback and AI audit failure isolation still need direct tests.

### Tasks

- [ ] Add notification parser tests for fallback-currency duplicate behavior.
- [ ] Add deposit-fee vs salary-deposit classification tests.
- [ ] Add “failed merchant name” regression: e.g. merchant named “Failed Pizza” must not be dropped as failed payment unless context indicates failure.
- [ ] Add transfer/refund classification tests.
- [ ] Verify source-link failure rolls back notification-created expense/link transaction.
- [ ] Verify AI audit/diagnostic side-effect failure cannot corrupt primary transaction.
- [ ] Decide whether notification parser should move from `Double` to money-safe value object before lifecycle create.

### Acceptance Criteria

- [ ] Notification-created expenses do not get duplicate/misclassified because of fallback currency or weak amount typing.
- [ ] Source-link and audit side-effect failures are isolated or transactional according to contract.

---

## MIT-075 — Transaction side-effect failure evidence

**Severity:** S1  
**Pipelines:** P2, P9, diagnostics  
**Status:** TODO  
**Labels:** `transactions`, `side-effects`, `diagnostics`

### Problem

P2 tracks duplicate/delete/update lifecycle issues, but side-effect failure evidence is not explicit.

P2 report notes no clear `TransactionEvent.SIDE_EFFECT_FAILED` path was observed and side effects rely on separate side-effect logging.

### Tasks

- [ ] Decide canonical place for side-effect failures: `TransactionEvent`, side-effect event table, operation ledger, or diagnostics table.
- [ ] Make post-commit action failures durable and queryable.
- [ ] Ensure side-effect failure does not roll back already committed primary expense mutation.
- [ ] Add tests for failed recurring/link/budget/dashboard post-commit action.

### Acceptance Criteria

- [ ] A failed post-commit side effect is visible without corrupting transaction lifecycle.
- [ ] Retry or no-retry semantics are documented.

---

## MIT-076 — Recurring legacy schema and money defaults audit

**Severity:** S1  
**Pipelines:** P4, P5, P6, P13  
**Status:** TODO  
**Labels:** `recurring`, `migration`, `money`, `schema`

### Problem

P4’s current tracker covers duplicate fulfillment and event atomicity, but not legacy occurrence-key migration or recurring money defaults.

### Tasks

- [ ] Verify existing installs cannot contain old `occurrenceKey` format that collides across source types.
- [ ] If old keys can exist, add migration/backfill or compatibility resolver.
- [ ] Audit recurring/planned/manual entities for raw `Double` and default `EUR`.
- [ ] Either migrate legacy defaults, document them as harmless, or route through normalized money APIs.
- [ ] Add mixed-currency recurring projection tests that include legacy rows.

### Acceptance Criteria

- [ ] Legacy recurring rows cannot collide or corrupt forecast/planned fulfillment.
- [ ] Recurring/planned defaults cannot silently introduce EUR or raw mixed-currency math.

---

## MIT-077 — Global read-barrier and restore-read safety

**Severity:** S1  
**Pipelines:** P7, P13, P14, P15  
**Status:** TODO  
**Labels:** `restore`, `read-barrier`, `database`

### Problem

The main tracker heavily covers write barriers, but P7 also says read barriers are caller-enforced and all Room Flow/read entry points need inventory.

### Tasks

- [ ] Inventory DAO Flow/read APIs used by UI, repositories, workers, and startup.
- [ ] Add `DatabaseReadBarrier` wrappers for DB reads that must block during restore/reset/import.
- [ ] Add static guard for DB-backed flows exposed directly to UI without read-barrier policy.
- [ ] Test that restore mode blocks or safely degrades active UI flows.
- [ ] Test backup snapshot reads remain allowed only under approved snapshot mode.

### Acceptance Criteria

- [ ] Restore/reset/import cannot race with stale or unsafe DB reads.
- [ ] UI flows do not continue reading from stale singleton DB after restore.

---

## MIT-078 — Historical migration data-loss hotspots

**Severity:** S0  
**Pipelines:** P13, P7, P12  
**Status:** TODO  
**Labels:** `database`, `migration`, `data-loss`, `release-blocker`

### Problem

The main tracker covers missing migration chain and schema parity, but P13 also calls out specific data-loss/constraint hotspots.

### Tasks

- [ ] Review pending-review migration around `144→145`; confirm no active user data is dropped without copy/destructive policy.
- [ ] Audit all migrations that drop/recreate tables.
- [ ] Add migration tests with non-empty representative data for every dropped/recreated table.
- [ ] Verify table copy preserves FKs, indexes, timestamps, provenance fields, and privacy fields.
- [ ] Add release notes/user policy if any old version is intentionally unsupported.

### Acceptance Criteria

- [ ] Supported historical DBs with real data migrate without silent table loss.
- [ ] Unsupported versions are rejected intentionally with safe UX.

---

## MIT-079 — DI binding matrix release proof

**Severity:** S1  
**Pipelines:** P15, P16, P17  
**Status:** TODO  
**Labels:** `hilt`, `di`, `release`, `security`

### Problem

P15’s binding matrix identifies several modules as unknown/yellow: network, security, diagnostics, currency, service modules, app-scope tasks, and fake/demo binding risk.

### Tasks

- [ ] Generate full Hilt binding graph for debug and release.
- [ ] Verify no fake/demo/stub/no-op binding is reachable in release except explicitly release-disabled demo bank code.
- [ ] Verify all cloud/network providers are privacy-gated and payload-policy-bound.
- [ ] Verify diagnostics writers are sanitized and barrier-aware.
- [ ] Verify currency/rate providers do not hold stale cache/state across restore if DB is swapped.
- [ ] Verify app-scope tasks cancel or suspend during maintenance.

### Acceptance Criteria

- [ ] Release DI graph is free of unsafe debug/demo bindings.
- [ ] Long-lived singletons cannot retain stale DB/security/network state after restore.

---

## MIT-080 — Import/export supported-field contract

**Severity:** S1  
**Pipelines:** P12, P18, P2, P3, P5, P10, P11  
**Status:** TODO  
**Labels:** `import`, `export`, `roundtrip`, `schema`

### Problem

P18’s field coverage matrix is more detailed than the main tracker. Current import ignores or mishandles many exported fields.

### Unsupported or partial fields to decide explicitly

- `sourceAccountName`
- `originalCurrency`
- `originalAmount`
- `homeCurrency`
- `baseAmount`
- `baseCurrency`
- `exchangeRateUsed`
- `conversionRateUsed`
- `conversionStatus`
- `businessCategory`
- `businessProject`
- `requiresReceipt`
- `paymentMethod` in CSV
- `transactionType` in CSV
- `sourceLinks`
- receipt links
- shared/not-mine flags
- group/split fields
- bank/email/receipt provenance fields

### Tasks

- [ ] Define supported import schema v1/v2 field contract.
- [ ] For every exported field, choose: import, preserve as metadata, reject, or explicitly unsupported.
- [ ] Add importer validation that rejects unsupported source-specific rows unless safe source restore exists.
- [ ] Add roundtrip tests for supported fields.
- [ ] Add “loss report” for unsupported fields before import confirmation.
- [ ] Ensure import provenance validation passes before category or expense mutation.

### Acceptance Criteria

- [ ] Import never silently drops financially/accounting-relevant fields.
- [ ] Unsupported fields are visible to user/developer before import.
- [ ] Export→import behavior is documented and tested.

---

## MIT-081 — Bank shared dedupe policy across API and statement import

**Severity:** S1  
**Pipelines:** P10, P2, P13  
**Status:** TODO  
**Labels:** `banking`, `dedupe`, `idempotency`

### Problem

The main tracker covers provider/account-scoped idempotency but not the shared dedupe policy gap between bank API sync and bank statement import.

P10 notes the statement path has local three-layer dedupe and API sync uses strict external ID; a shared `BankTransactionDeduper` is planned but absent.

### Tasks

- [ ] Introduce shared bank transaction dedupe policy used by API sync and statement import.
- [ ] Scope dedupe by provider/account/connection/date/amount/currency/type/merchant hash as appropriate.
- [ ] Define behavior when same transaction appears through API and statement import.
- [ ] Add tests for API-then-statement and statement-then-API duplicate scenarios.

### Acceptance Criteria

- [ ] Same bank transaction from two bank ingestion paths does not create duplicate expenses/reviews.
- [ ] Different accounts with same provider transaction ID do not conflict.

---

## MIT-082 — Worker registry/spec parity and time-provider cleanup

**Severity:** S2  
**Pipelines:** P1, P9, P17  
**Status:** TODO  
**Labels:** `workers`, `static-guards`, `time`

### Problem

P9 covers worker guard issues, but some small guardrails are not explicit.

### Tasks

- [ ] Add static worker subclass inventory: every `CoroutineWorker` must use full guard or have equivalent lease/barrier/run-ledger tests.
- [ ] Add registry/spec parity guard: every enabled background worker must be in `WorkerRegistry` or explicitly bespoke.
- [ ] Fix/comment-test one-shot version bump comment vs implementation.
- [ ] Replace `System.currentTimeMillis()` in `NotificationIntakeWorker` workerId with `TimeProvider` or document as non-business diagnostic timestamp.
- [ ] Add test for stale `RUNNING` recovery after killed worker.

### Acceptance Criteria

- [ ] New workers cannot bypass guard/drain/run-ledger unnoticed.
- [ ] Worker diagnostics timestamps are deterministic where required.

---

## MIT-083 — UI import/export/source action inventory

**Severity:** S1  
**Pipelines:** P12, P14, P18  
**Status:** TODO  
**Labels:** `ui`, `import`, `export`, `action-paths`

### Problem

P14 tracks UI direct DAO and restore UX, but import/export action inventory needs to be explicit because P12/P18 import legality depends on what UI exposes.

### Tasks

- [ ] Inventory all import/export buttons, menu items, and file picker callbacks.
- [ ] Verify import UI cannot call util importers directly.
- [ ] Import UI must call lifecycle-owned coordinator with barrier, operation run, row ledger, cancellation handling.
- [ ] Export UI must document user-cancel vs coroutine cancellation.
- [ ] Duplicate taps must be debounced or operation-run rejected.
- [ ] UI error messages must not include raw CSV/JSON/file row content.

### Acceptance Criteria

- [ ] UI cannot trigger unsafe import/export paths.
- [ ] Cancellation and duplicate actions cannot corrupt operation state.

---

# 3. Updated Coverage Assessment

## What the main tracker already covers well

- P13 migration chain and schema parity.
- P7/P15 stale DB singleton after restore.
- P9 worker drain/lease problems.
- P1 privacy gate before notification extraction.
- P8 cloud fail-closed and semantic redaction.
- P10 bank direct DAO/raw bank text/OAuth gaps.
- P11 provider detection/dedupe/review queue.
- P12 import/export ambiguity.
- P18 unsafe util import.
- P5/P6 money correctness issues.
- P14 UI DAO and restore stale DB UX.
- P17 CI missing guardrails.

## What this supplement adds

- P1 cancellation repairer and notification parser/money/classification edge tests.
- P2 post-commit side-effect failure evidence.
- P4 legacy recurring-key and raw money/default currency audit.
- P7 read-barrier inventory.
- P13 migration data-loss hotspot review.
- P15 release DI binding proof.
- P18 exact import/export field contract.
- P10 shared bank dedupe policy.
- P9 worker subclass/spec parity and time-provider cleanup.
- P14 UI import/export action inventory.

---

# 4. Source Links Used

Commit/file tree:
- https://github.com/panospao7/Cost-agregator/commit/14659197606b6fbe9874ed09639c53bd64007bea

Debugging reports:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/pipeline1-notification-capture-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/pipeline-2-transaction-lifecycle-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/pipeline-3-debug-review-report.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/pipeline-4-review-recurring-bill-reminders.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/pipeline5-debug-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/p6-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/p7-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/p8-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/p9-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/p10-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/p11-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/pipeline12-debug-review.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/P13_DATABASE_SCHEMA_DAO_REVIEW.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/P14_UI_VIEWMODEL_ACTION_PATH_REVIEW.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/P15_HILT_DI_SINGLETON_LIFETIME_REVIEW.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/P16_SECURITY_NETWORK_SECRETS_REVIEW.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/P17_CI_STATIC_GUARDRAILS_REVIEW.md
- https://raw.githubusercontent.com/panospao7/Cost-agregator/14659197606b6fbe9874ed09639c53bd64007bea/docs/analyses%20and%20debug%20master/new%20debugging%20reports/P18_IMPORT_SUPPORT_REVIEW.md

---

# 5. Final Recommendation

Do **not** rewrite the full master tracker again.

Instead:

1. Commit the current `MASTER_ISSUE_TRACKER.md`.
2. Commit this file as `MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md`.
3. Add a link near the top of the master tracker:

```md
Supplemental verification patches:
- `docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md`
```

4. When creating GitHub issues, create MIT-074 through MIT-083 as additional issues.
5. Patch existing MIT-034, MIT-035, MIT-043, MIT-055, MIT-012, MIT-028, MIT-033 with the checklist additions above.

After this supplement, the tracker is appropriately complete for release-blocker management.