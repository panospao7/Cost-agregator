# CI / Static Guardrails Implementation Plan

Last updated: 2026-06-15  
Scope: MIT-001, MIT-002, MIT-003, MIT-004, MIT-005  
Goal: make known regression classes impossible to merge through CI and static architecture enforcement.

---

## 1. Objective

Build a reliable CI and static-guardrail system that blocks:

- skipped Gradle checks,
- unsafe DB access,
- privacy boundary violations,
- money/currency aggregation bugs,
- missing migration execution,
- event/state divergence patterns,
- unsafe worker implementations,
- swallowed `CancellationException`,
- UI/ViewModel direct DAO writes,
- receipt link bypasses,
- cloud fail-open payload paths,
- import lifecycle bypasses,
- release debug/demo/stub leakage,
- stale ignored-test debt.

This plan turns the tracker from passive documentation into active enforcement.

---

## 2. Affected Master Issues

| MIT | Issue |
|---|---|
| MIT-001 | Make CI run full Gradle verification |
| MIT-002 | Run all existing Python/static guard scripts in CI |
| MIT-003 | Add missing architecture guards |
| MIT-004 | Add real migration execution matrix to CI |
| MIT-005 | Reduce stale/ignored test debt threshold |

Related but not owned by this plan:

| MIT | Relationship |
|---|---|
| MIT-028 | Release security scan can be scaffolded here, completed in security plan |
| MIT-034 | Cancellation guard implemented here, code fixes elsewhere |
| MIT-036 | DAO ownership guard implemented here |
| MIT-040 | Receipt-link guard implemented here |
| MIT-047 | Import lifecycle guard implemented here |
| MIT-050 | Raw money sum guard implemented here |
| MIT-060 | UI DAO guard implemented here |
| MIT-078 | Migration data-loss tests are enabled by migration matrix |
| MIT-079 | DI/release binding guard scaffolded here |
| MIT-082 | Worker subclass/registry guard implemented here |

---

## 3. Affected Pipelines

All pipelines are affected, but strongest coverage is for:

- P1 notification capture,
- P3 receipt/OCR,
- P4 recurring/reminders,
- P5 money/dashboard,
- P7 backup/restore,
- P8 privacy/AI/redaction,
- P9 workers,
- P10 banking,
- P11 email ingestion,
- P12 import/export/accounting,
- P13 DB/migrations/DAO constraints,
- P14 UI/ViewModel paths,
- P15 Hilt/DI lifetime,
- P16 security/network/secrets,
- P17 CI/static guardrails,
- P18 import support.

---

## 4. Current Problem

The app has many good architecture rules documented, and some guard scripts already exist, but CI is not yet strong enough.

Known gaps:

- `:app:check` is not guaranteed in PR CI.
- Existing Python/static guard scripts are not all blocking.
- Script tests are not all run.
- Migration snapshot presence is checked, but real historical migration execution is not enough.
- Guard allowlists are too broad.
- Instrumented tests are not release-gated.
- Missing static guards allow unsafe patterns to reappear.
- Too many ignored tests can hide broken release gates.
- Workflow syntax may not be validated before merge.

---

## 5. Architecture Decision

### Decision

Use a layered CI model:

1. **Fast validation jobs**  
   Workflow syntax, script syntax, guard-unit tests.

2. **Gradle correctness jobs**  
   Assemble, unit tests, lint, `:app:check`.

3. **Static architecture guard jobs**  
   Python/Kotlin/static scripts that enforce project-specific architecture rules.

4. **Migration matrix jobs**  
   Real Room migration execution from supported historical versions to latest.

5. **Release safety jobs**  
   Lightweight release-build checks for secrets, logging, debug/demo bindings, network safety.

6. **Release-candidate gate**  
   Slower/instrumented tests may run here if not suitable for every PR.

### Rejected Alternative: only fix code, not CI

Rejected because every architectural bug class can regress.

### Rejected Alternative: pipeline-by-pipeline CI

Rejected because many violations are cross-cutting. Guards should enforce rules globally.

---

## 6. Non-Negotiable Invariants

After this plan is implemented:

- [ ] Every PR runs `:app:check`.
- [ ] Every existing guard script runs in CI.
- [ ] Every guard script has tests.
- [ ] Every critical guard is blocking.
- [ ] Allowlists are explicit, reviewed, and shrinking.
- [ ] Migration execution is tested, not only schema file presence.
- [ ] Unsafe DAO access from UI/ViewModel fails CI.
- [ ] DB-writing workers without full guard fail CI.
- [ ] Unsafe `runCatching` / broad `catch(Exception)` in suspend/worker paths fails CI.
- [ ] Raw mixed-currency sums fail CI.
- [ ] Direct receipt link mutation fails CI.
- [ ] Import utility bypass of lifecycle coordinator fails CI.
- [ ] Cloud payload bypass/fail-open paths fail CI.
- [ ] Release build cannot include debug/demo/stub/no-op bindings accidentally.
- [ ] Ignored release-critical tests fail CI or require explicit waiver.

---

## 7. Target CI Job Layout

Recommended required PR jobs:

```text
validate-workflows
gradle-assemble-debug
gradle-unit-tests
gradle-lint-debug
gradle-check
static-guards
static-guard-tests
migration-matrix
release-safety-scan
ignored-test-budget
```

Optional but recommended for release branches:

```text
instrumented-release-gate
full-migration-matrix
release-apk-aab-scan
```

---

## 8. Required Local Commands

Developers should be able to reproduce CI with:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:lintDebug --stacktrace
./gradlew :app:check --stacktrace

python3 scripts/verify_privacy_boundaries.py --root .
python3 scripts/verify_db_access_boundaries.py --fail-on-violation
python3 scripts/verify_event_writers.py --fail-on-violation
python3 scripts/verify_money_boundaries.py --root .
python3 scripts/verify_source_provenance_boundaries.py --root .
python -m pytest scripts/test_*.py -v
```

Add new guard commands as they are implemented:

```bash
python3 scripts/verify_worker_boundaries.py --root . --fail-on-violation
python3 scripts/verify_cancellation_boundaries.py --root . --fail-on-violation
python3 scripts/verify_ui_dao_boundaries.py --root . --fail-on-violation
python3 scripts/verify_receipt_link_boundaries.py --root . --fail-on-violation
python3 scripts/verify_recurring_event_boundaries.py --root . --fail-on-violation
python3 scripts/verify_cloud_payload_boundaries.py --root . --fail-on-violation
python3 scripts/verify_import_lifecycle_boundaries.py --root . --fail-on-violation
python3 scripts/verify_di_release_boundaries.py --root . --fail-on-violation
python3 scripts/verify_release_security.py --root . --variant release
python3 scripts/verify_ignored_test_budget.py --root .
```

---

# 9. Implementation Phases

---

## Phase 0 — Baseline Inventory

### Goal

Know exactly what CI, Gradle tasks, scripts, guards, allowlists, and ignored tests currently exist.

### Tasks

- [ ] Inventory `.github/workflows/**`.
- [ ] Inventory Gradle verification tasks.
- [ ] Inventory existing Python scripts under `scripts/**`.
- [ ] Inventory script tests.
- [ ] Inventory architecture allowlists, especially DB access allowlists.
- [ ] Inventory ignored/skipped tests.
- [ ] Inventory Room schema files and migration classes.
- [ ] Inventory existing release/security checks.
- [ ] Create a short `docs/ci/CI_GUARDRAILS_BASELINE.md`.

### Deliverables

- `docs/ci/CI_GUARDRAILS_BASELINE.md`
- List of current CI jobs.
- List of existing guard scripts.
- List of missing guard scripts.
- Current ignored-test count.
- Current allowlist count.

### Acceptance Criteria

- [ ] CI baseline is documented.
- [ ] Every existing guard script has an owner and expected command.
- [ ] Every ignored test is counted.

---

## Phase 1 — Workflow Validation

### Goal

Bad GitHub Actions YAML should fail before merge.

### Tasks

- [ ] Add workflow validation job.
- [ ] Run `actionlint` for `.github/workflows/**`.
- [ ] Run `yamllint` if project is willing to maintain YAML style config.
- [ ] Add local instructions.
- [ ] Make job blocking.

### Files likely touched

- `.github/workflows/ci.yml`
- `.github/workflows/**`
- `.yamllint.yml`, if used
- `docs/ci/local-ci.md`

### Acceptance Criteria

- [ ] Broken workflow syntax fails PR CI.
- [ ] Workflow validation runs before expensive Gradle jobs.
- [ ] Developers can reproduce locally.

---

## Phase 2 — Gradle PR Verification

### Goal

Every PR must run the core Android/Gradle checks.

### Tasks

- [ ] Add `gradle-assemble-debug` job.
- [ ] Add `gradle-unit-tests` job.
- [ ] Add `gradle-lint-debug` job.
- [ ] Add `gradle-check` job.
- [ ] Enable Gradle cache safely.
- [ ] Upload test reports on failure.
- [ ] Upload lint reports on failure.
- [ ] Ensure `:app:check` is not skipped silently.
- [ ] Document local reproduction.

### Required commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:lintDebug --stacktrace
./gradlew :app:check --stacktrace
```

### Acceptance Criteria

- [ ] `:app:check` is a required PR status.
- [ ] A failing unit test blocks merge.
- [ ] A failing lint check blocks merge.
- [ ] A failing architecture task wired into `:app:check` blocks merge.

---

## Phase 3 — Existing Static Guards in CI

### Goal

All currently existing guard scripts become blocking.

### Tasks

- [ ] Add `static-guards` CI job.
- [ ] Run privacy guard.
- [ ] Run DB access guard.
- [ ] Run event writer guard.
- [ ] Run money boundary guard.
- [ ] Run source provenance guard.
- [ ] Run all script tests.
- [ ] Ensure every guard supports machine-readable failure output or clear line-based failure output.
- [ ] Fail on violations.
- [ ] Upload guard output as artifact on failure.

### Required commands

```bash
python3 scripts/verify_privacy_boundaries.py --root .
python3 scripts/verify_db_access_boundaries.py --fail-on-violation
python3 scripts/verify_event_writers.py --fail-on-violation
python3 scripts/verify_money_boundaries.py --root .
python3 scripts/verify_source_provenance_boundaries.py --root .
python -m pytest scripts/test_*.py -v
```

### Acceptance Criteria

- [ ] Every existing guard runs in CI.
- [ ] Script tests are blocking.
- [ ] A known artificial violation fails CI.
- [ ] Guard failures show file path, line, rule ID, and remediation hint.

---

## Phase 4 — Guard Framework Standardization

### Goal

Make every guard consistent, testable, and maintainable.

### Standard guard requirements

Every guard script must have:

- [ ] A rule ID.
- [ ] A clear description.
- [ ] A documented scope.
- [ ] Positive fixtures.
- [ ] Negative fixtures.
- [ ] Unit tests.
- [ ] Allowlist support if needed.
- [ ] Allowlist comments requiring reason and owner.
- [ ] `--fail-on-violation`.
- [ ] Deterministic output.
- [ ] Exit code `0` on pass.
- [ ] Exit code nonzero on violation.
- [ ] CI documentation.

### Recommended output format

```text
RULE_ID path/to/File.kt:123 violation message
Hint: use X instead of Y
```

### Allowlist policy

Allowlist entries must include:

```yaml
rule: RULE_ID
path: app/src/...
symbol: SomeClass.someMethod
reason: "Why this is safe"
owner: "@github-handle"
expires: "YYYY-MM-DD"
linked_issue: "MIT-..."
```

### Acceptance Criteria

- [ ] New guard scripts follow the standard.
- [ ] Allowlist entries without reason/owner/expiry fail CI.
- [ ] Expired allowlist entries fail CI.

---

# 10. New Missing Guards

---

## Guard 1 — Worker Full Guard / Lease / Run-Ledger Guard

### Related MIT

MIT-003, MIT-016, MIT-017, MIT-035, MIT-082

### Goal

Every DB-writing `CoroutineWorker` must use the full worker execution contract.

### Detect

- Classes extending `CoroutineWorker`.
- Workers injecting DAOs/repositories known to write.
- Worker classes missing `WorkerExecutionGuard`.
- Workers missing lease acquisition.
- Workers missing run ledger / terminal logging.
- Workers not listed in `WorkerRegistry`, unless explicitly bespoke.
- One-shot policy/version comments inconsistent with implementation if detectable.

### Allow exceptions

Only if:

- worker is read-only,
- or no DB access,
- or bespoke guard is documented and tested.

### Tests

- [ ] Fake worker without guard fails.
- [ ] Read-only worker with explicit safe annotation passes.
- [ ] Guarded worker passes.
- [ ] Same-name concurrent worker lease case is covered by unit/integration tests.

### Acceptance Criteria

- [ ] New DB-writing worker cannot bypass guard unnoticed.

---

## Guard 2 — Cancellation Boundary Guard

### Related MIT

MIT-003, MIT-034

### Goal

Prevent swallowed `CancellationException`.

### Detect

Patterns in suspend/worker/repository code:

- `runCatching { ... }` inside suspend functions.
- `catch (e: Exception)` without `if (e is CancellationException) throw e`.
- `catch (t: Throwable)` without cancellation handling.
- `onFailure { ... }` used in coroutine path without cancellation preservation.
- Worker paths returning success/failure after cancellation.

### High-risk paths

- Workers.
- Repositories.
- Import/export.
- Receipt/OCR.
- Bank sync/import.
- Notification repairers.
- Retention tasks.
- Snooze/dismiss receivers.

### Tests

- [ ] Unsafe `runCatching` fixture fails.
- [ ] Safe `catch` with CE rethrow passes.
- [ ] Non-suspend pure local parse code can be allowlisted if safe.
- [ ] P1 `NotificationIntakePayloadRepairer` style fixture is covered.

### Acceptance Criteria

- [ ] Cancellation cannot be swallowed in suspend/worker paths without explicit reviewed exception.

---

## Guard 3 — UI / ViewModel DAO Boundary Guard

### Related MIT

MIT-003, MIT-036, MIT-060

### Goal

ViewModels and UI must not directly inject or call mutating DAOs.

### Detect

- `@Inject` DAO fields in `ui/**`.
- DAO constructor params in `*ViewModel`.
- Direct DAO calls from Compose screen/action handlers.
- Direct mutating DAO calls from UI-adjacent packages.

### Allow exceptions

- Read-only DAO access only if specifically allowed and read-barrier policy exists.
- Prefer no DAO in ViewModel at all.

### Tests

- [ ] `BankConnectionsViewModel`-style direct DAO fixture fails.
- [ ] Repository/coordinator usage passes.
- [ ] Read-only exception requires explicit allowlist.

### Acceptance Criteria

- [ ] UI cannot bypass lifecycle owner/write barrier.

---

## Guard 4 — Receipt Link Ownership Guard

### Related MIT

MIT-003, MIT-040

### Goal

Receipt link state must be changed only by `ReceiptLinkService` or approved coordinator.

### Detect

- Direct updates to `ScannedReceipt.expenseId`.
- DAO methods that set/clear receipt `expenseId`.
- SQL update statements mutating receipt expense link outside approved files.
- Manual match approve/clear bypassing service.

### Tests

- [ ] Direct receipt `expenseId` update fixture fails.
- [ ] `ReceiptLinkService` path passes.
- [ ] Approved migration/backfill path requires allowlist.

### Acceptance Criteria

- [ ] Receipt link state cannot diverge from source links/events through direct mutation.

---

## Guard 5 — Recurring Event Atomicity Guard

### Related MIT

MIT-003, MIT-031, MIT-043, MIT-067

### Goal

Recurring state changes and lifecycle events must be atomic.

### Detect

- Direct `RecurringLifecycleEventDao.insert()`.
- State update and event insert outside transaction.
- Query methods that perform writes, e.g. hidden stale-claim recovery.
- Recurring projection code with planned rows inserted outside owning transaction.

### Tests

- [ ] Direct event DAO insert fixture fails.
- [ ] Transaction-aware event writer passes.
- [ ] Hidden write in read-named method fixture fails.

### Acceptance Criteria

- [ ] Recurring state/event divergence is blocked by CI patterns.

---

## Guard 6 — Cloud Payload Fail-Closed Guard

### Related MIT

MIT-003, MIT-022, MIT-023, MIT-028

### Goal

Cloud upload/payload paths must use central fail-closed policy.

### Detect

- Direct construction of cloud request payloads outside `CloudPayloadPolicy` / `PreparedCloudPayload`.
- Direct raw `RequestBody` creation in cloud provider paths.
- Cloud send methods accepting raw receipt path/body/string without policy object.
- Missing capability check before cloud payload preparation.

### Tests

- [ ] Direct raw `RequestBody` cloud fixture fails.
- [ ] `PreparedCloudPayload` path passes.
- [ ] Local-only network path is allowlisted if safe.

### Acceptance Criteria

- [ ] Caller mistakes cannot bypass cloud-disabled policy.

---

## Guard 7 — Import Lifecycle / Provenance Guard

### Related MIT

MIT-003, MIT-047, MIT-048, MIT-073, MIT-080

### Goal

Import utilities cannot mutate DB outside lifecycle-owned coordinator.

### Detect

- Importer classes directly calling DAOs.
- Category creation in importer.
- Expense creation in importer without import coordinator.
- Missing import run/batch/row provenance fields.
- Import code bypassing read/write barrier.
- UI calling util importers directly.

### Tests

- [ ] Direct `CategoryDao` from CSV importer fixture fails.
- [ ] Coordinator-owned category creation passes.
- [ ] UI direct importer call fixture fails.

### Acceptance Criteria

- [ ] Import cannot bypass barrier, operation run, row ledger, or legal category owner.

---

## Guard 8 — DI / Release Binding Guard

### Related MIT

MIT-003, MIT-028, MIT-079

### Goal

Debug/demo/stub/no-op bindings cannot ship accidentally.

### Detect

- `@StubForDemo` reachable in release source set.
- Fake/no-op implementations bound in release modules.
- Debug-only module included in release component.
- Bank demo API enabled in release.
- Cloud/network providers without privacy gate.
- No-op security/diagnostic implementation in release.

### Tests

- [ ] Fake release binding fixture fails.
- [ ] Debug source-set binding passes only in debug.
- [ ] Explicit release-disabled demo bank binding passes only if inaccessible to production action path.

### Acceptance Criteria

- [ ] Release DI graph is free of unsafe debug/demo implementations.

---

## Guard 9 — PII Logging / Exception Message Guard

### Related MIT

MIT-003, MIT-026

### Goal

Known sensitive values must not be logged or surfaced raw.

### Detect

- Direct `Log.d/e/w`.
- `Timber.d/e` with merchant/amount/email/path/token variables.
- `e.message` surfaced to UI.
- Exception message used in snackbar.
- Asset paths/filenames logged in backup/restore.
- Bank token `toString()` risk patterns.
- Raw CSV/JSON row content in error strings.

### Tests

- [ ] Direct Android `Log` in sensitive provider fixture fails.
- [ ] Snackbar using raw `e.message` fixture fails.
- [ ] Sanitized logger path passes.

### Acceptance Criteria

- [ ] Release-visible diagnostics use sanitizer.

---

## Guard 10 — Raw Cross-Currency Money Sum Guard

### Related MIT

MIT-003, MIT-050

### Goal

Prevent raw mixed-currency sums.

### Existing coverage

There is already a money boundary guard. Extend it.

### Detect

- Summing `effectiveAmount` across rows without normalized currency.
- Summing raw `amount` / `Double` in dashboard/analytics/forecast.
- Default currency fallback like hardcoded `EUR` in import/recurring paths unless explicitly legacy-safe.
- Use of raw `Double` in money lifecycle paths.

### Tests

- [ ] Block Party-style raw `effectiveAmount` fixture fails.
- [ ] Normalized `dailySpending` path passes.
- [ ] Hardcoded EUR fallback fixture fails unless allowlisted.

### Acceptance Criteria

- [ ] New dashboard/forecast/import money code cannot sum mixed currencies.

---

# 11. Migration Matrix Plan

---

## Phase 5 — Real Migration Execution Matrix

### Related MIT

MIT-004, MIT-010, MIT-011, MIT-078

### Goal

CI proves supported old DB versions migrate to latest correctly.

### Required decisions

- [ ] Define minimum supported DB version.
- [ ] Decide whether versions below minimum are unsupported, destructive, or require special migration.
- [ ] Decide representative old versions to test if every version is too costly.

### Recommended matrix levels

#### PR matrix

Run fast representative set:

```text
minimum_supported -> latest
last_major_before_145 -> latest
145 -> latest
146 -> latest
latest_fresh_schema
```

#### Nightly / release-candidate matrix

Run every supported historical version:

```text
N -> latest for all N >= minimum_supported
```

### Required migration test data

Each historical DB fixture should include representative non-empty data for:

- expenses,
- categories,
- source links,
- receipts,
- pending review,
- recurring rules/occurrences/reminders,
- bank connections/transactions/reviews,
- email receipt sources,
- import/export/operation tables,
- currency/exchange rates,
- privacy/audit tables.

### Schema parity tests

- [ ] Fresh latest DB schema equals migrated latest DB schema.
- [ ] Compare tables.
- [ ] Compare columns.
- [ ] Compare indexes.
- [ ] Compare unique constraints.
- [ ] Compare foreign keys.
- [ ] Compare defaults.
- [ ] Compare triggers if any.

### Data-loss tests

- [ ] Dropped/recreated tables copy data safely.
- [ ] Pending-review migration around `144→145` preserves or intentionally handles data.
- [ ] Privacy/provenance fields survive.
- [ ] Dedupe fingerprints survive or are backfilled.
- [ ] Unsupported old versions fail safely.

### Acceptance Criteria

- [ ] Missing migration fails CI.
- [ ] Fresh/migrated schema drift fails CI.
- [ ] Representative historical data survives migration.
- [ ] Unsupported DB versions are handled intentionally.

---

# 12. Ignored Test Debt Plan

---

## Phase 6 — Ignored/Skipped Test Budget

### Related MIT

MIT-005

### Goal

Prevent ignored tests from hiding release blockers.

### Tasks

- [ ] Write ignored-test scanner.
- [ ] Count JUnit `@Ignore`.
- [ ] Count disabled tests.
- [ ] Count commented-out test classes if detectable.
- [ ] Count Gradle-excluded test patterns.
- [ ] Produce category report.
- [ ] Set current baseline.
- [ ] Mark release-critical tests that may never be ignored.
- [ ] Fail if ignored count increases.
- [ ] Lower threshold milestone by milestone.

### Required categories

```text
obsolete
flaky
waiting-for-architecture-fix
release-blocking
unknown
```

### Release-critical test classes

Tests for these areas cannot be silently ignored:

- privacy gates,
- cloud fail-closed,
- restore/DB lifetime,
- migration matrix,
- worker drain/lease,
- import provenance,
- receipt link ownership,
- recurring reminder delivery,
- money/currency correctness,
- release security scan.

### Acceptance Criteria

- [ ] Ignored-test count cannot increase.
- [ ] Release-critical ignored tests fail CI.
- [ ] Every ignored test has issue link and owner.
- [ ] Threshold decreases over time.

---

# 13. Branch Protection

---

## Required GitHub Settings

Mark these as required before merge:

- `validate-workflows`
- `gradle-assemble-debug`
- `gradle-unit-tests`
- `gradle-lint-debug`
- `gradle-check`
- `static-guards`
- `static-guard-tests`
- `migration-matrix`
- `ignored-test-budget`

Recommended once stable:

- `release-safety-scan`

### Rules

- [ ] Require PR branch up to date.
- [ ] Require all required checks.
- [ ] Require review from code owners for guard allowlist changes.
- [ ] Require review from code owners for migration changes.
- [ ] Require review from code owners for workflow changes.
- [ ] Disallow force-push to protected branches.

---

# 14. Code Ownership

Recommended `CODEOWNERS` policy:

```text
.github/workflows/**                @maintainers
scripts/verify_*.py                 @maintainers
scripts/test_*.py                   @maintainers
docs/ci/**                          @maintainers
app/schemas/**                      @database-owner
**/migration/**                     @database-owner
**/*Migration*                      @database-owner
**/dao/**                           @database-owner
**/*Worker*                         @workers-owner
**/*ViewModel*                      @ui-owner
**/privacy/**                       @privacy-owner
**/security/**                      @security-owner
```

Adjust names to your actual GitHub users/teams.

---

# 15. PR Rollout Plan

---

## PR 1 — CI Baseline and Workflow Validation

### Includes

- Workflow validation.
- Baseline documentation.
- CI local reproduction docs.
- Existing CI inventory.

### Do not include

- Big new guard rewrites.
- Migration matrix.
- Code fixes.

### Acceptance

- [ ] Workflow syntax errors fail.
- [ ] Baseline doc committed.

---

## PR 2 — Full Gradle Verification

### Includes

- `assembleDebug`.
- `testDebugUnitTest`.
- `lintDebug`.
- `:app:check`.
- Artifact upload.
- Required status checks.

### Acceptance

- [ ] `:app:check` blocks PRs.
- [ ] Test/lint reports uploaded on failure.

---

## PR 3 — Existing Static Guards in CI

### Includes

- Privacy guard.
- DB access guard.
- Event writer guard.
- Money boundary guard.
- Source provenance guard.
- Script tests.

### Acceptance

- [ ] All existing guards run and block.
- [ ] Script tests pass.

---

## PR 4 — Guard Framework Standardization

### Includes

- Rule ID format.
- Allowlist format.
- Allowlist owner/reason/expiry validation.
- Guard fixture pattern.
- Guard docs.

### Acceptance

- [ ] Expired or ownerless allowlist entries fail CI.
- [ ] New guard template exists.

---

## PR 5 — Migration Matrix MVP

### Includes

- Minimum supported version decision.
- Representative migration fixtures.
- Fresh-vs-migrated parity.
- PR-level migration matrix.

### Acceptance

- [ ] Missing migration fails.
- [ ] Schema drift fails.
- [ ] Non-empty representative data migrates.

---

## PR 6 — New Guards Batch A: High-Risk Architecture

### Includes

- Cancellation guard.
- UI/ViewModel DAO guard.
- Worker full guard.
- Receipt link ownership guard.
- Import lifecycle guard.

### Acceptance

- [ ] Bad fixtures fail.
- [ ] Existing violations either fixed or explicitly allowlisted with issue links.
- [ ] Allowlist count documented.

---

## PR 7 — New Guards Batch B: Privacy/Money/Release

### Includes

- Cloud payload guard.
- PII logging/error guard.
- Raw money sum guard improvements.
- DI/release binding guard scaffold.
- Release security scan scaffold.

### Acceptance

- [ ] Raw cloud payload bypass fails.
- [ ] Raw mixed-currency sum fails.
- [ ] Debug/demo release binding fails.

---

## PR 8 — Ignored Test Budget

### Includes

- Ignored-test scanner.
- Current baseline.
- Release-critical ignored-test denylist.
- Fail-on-increase policy.

### Acceptance

- [ ] Ignored-test count cannot increase.
- [ ] Critical ignored tests fail CI.

---

## PR 9 — Branch Protection and Documentation

### Includes

- Required check documentation.
- CODEOWNERS updates.
- Guard ownership docs.
- Developer quickstart.

### Acceptance

- [ ] Required checks configured.
- [ ] Guard allowlist changes require owner review.
- [ ] Docs explain local reproduction.

---

# 16. Handling Existing Violations

The first time guards run, they may find real violations.

Use this policy:

## For S0 release blockers

Do not allowlist unless absolutely unavoidable.

Required:

- [ ] Fix immediately, or
- [ ] allowlist only with linked MIT issue,
- [ ] owner,
- [ ] expiry within 14 days,
- [ ] reason explaining why merge is safe.

## For S1 issues

Allowlist only if:

- [ ] there is an issue,
- [ ] there is a planned fix milestone,
- [ ] expiry is no more than 30 days.

## For S2/S3 issues

Allowlist may be longer, but must still have owner and issue.

## Never allowlist

- cloud fail-open in release,
- direct raw token logging,
- migration data-loss without policy,
- UI direct mutating DAO in release path,
- swallowed `CancellationException` in worker terminal path,
- DB-writing worker with no guard,
- unsupported DB migration failure for supported versions.

---

# 17. Test Strategy

---

## Static Guard Tests

Each guard needs:

- [ ] positive fixture,
- [ ] negative fixture,
- [ ] allowlisted fixture,
- [ ] expired allowlist fixture,
- [ ] path/symbol matching test,
- [ ] CI command test.

## Gradle Tests

Ensure:

- [ ] unit tests run consistently,
- [ ] lint runs consistently,
- [ ] `:app:check` includes architecture tasks,
- [ ] generated reports are uploaded.

## Migration Tests

Ensure:

- [ ] historical DB fixtures are non-empty,
- [ ] latest schema parity is checked,
- [ ] indexes/constraints are checked,
- [ ] data-loss hotspots are tested.

## Release Safety Tests

Ensure:

- [ ] release build has no debug/demo binding,
- [ ] no embedded secrets,
- [ ] no cleartext endpoints,
- [ ] no BODY logging,
- [ ] cloud payload paths are policy-bound.

---

# 18. Metrics to Track

Add a small CI/quality dashboard in docs or GitHub issue comments:

| Metric | Target |
|---|---|
| Required CI jobs | all listed jobs required |
| Existing guards run in CI | 100% |
| New guard scripts tested | 100% |
| Guard allowlist count | decreasing |
| Expired allowlist count | 0 |
| Ignored test count | decreasing |
| Release-critical ignored tests | 0 |
| Migration versions tested in PR | representative set |
| Migration versions tested in release | all supported |
| Fresh/migrated schema drift | 0 |
| CI runtime | acceptable and monitored |

---

# 19. Risks and Mitigations

## Risk: CI becomes too slow

Mitigation:

- Separate PR matrix vs nightly/release matrix.
- Cache Gradle.
- Run representative migrations in PR, full matrix nightly/release.
- Parallelize jobs.

## Risk: Guards create false positives

Mitigation:

- Require fixtures.
- Allowlist with owner/expiry.
- Improve parser over time.
- Start with warn-only only for low-risk guards, then flip to fail.

S0 guards should be fail-fast from the start if possible.

## Risk: Existing code has too many violations

Mitigation:

- Fix S0 immediately.
- Temporarily allowlist S1/S2 with expiry.
- Create linked MIT issues.
- Track allowlist burn-down.

## Risk: Developers bypass CI locally

Mitigation:

- Document local commands.
- Add pre-push optional script.
- Keep CI as final authority.

## Risk: Migration tests are hard to maintain

Mitigation:

- Keep small representative DB fixtures.
- Generate fixtures from known schema versions.
- Add helper to compare schema.
- Use full matrix only in release/nightly if PR cost is too high.

---

# 20. Completion Checklist

This plan is complete when:

- [ ] Workflow validation is blocking.
- [ ] `:app:assembleDebug` is blocking.
- [ ] `:app:testDebugUnitTest` is blocking.
- [ ] `:app:lintDebug` is blocking.
- [ ] `:app:check` is blocking.
- [ ] All existing guard scripts run in CI.
- [ ] All guard scripts have tests.
- [ ] Missing guards from MIT-003 are implemented or explicitly scheduled with blocking substitute.
- [ ] Migration execution matrix runs in CI.
- [ ] Fresh-vs-migrated schema parity is checked.
- [ ] Ignored-test budget is enforced.
- [ ] Release-critical ignored tests are forbidden.
- [ ] Allowlist policy requires owner, reason, expiry, issue link.
- [ ] Branch protection requires all critical checks.
- [ ] Docs explain local reproduction.
- [ ] The master tracker is updated with implementation commit SHAs.

---

# 21. Definition of Done for MIT Closure

## MIT-001 can close when

- [ ] Gradle assemble/test/lint/check run in PR CI.
- [ ] They are required checks.
- [ ] Artifacts upload on failure.
- [ ] Local reproduction docs exist.

## MIT-002 can close when

- [ ] All existing scripts run in CI.
- [ ] Script tests run in CI.
- [ ] Workflow validation runs.
- [ ] Source provenance guard is blocking.

## MIT-003 can close when

- [ ] All listed missing guards exist, or each has an accepted replacement.
- [ ] Each guard has tests.
- [ ] Each guard is blocking or has a documented staged rollout date.
- [ ] Allowlist policy is enforced.

## MIT-004 can close when

- [ ] Supported minimum DB version is defined.
- [ ] Representative migration matrix runs in PR CI.
- [ ] Full matrix runs in release/nightly or PR if feasible.
- [ ] Fresh-vs-migrated parity is checked.
- [ ] Migration data-loss hotspots are tested.

## MIT-005 can close when

- [ ] Ignored-test scanner exists.
- [ ] Current baseline is recorded.
- [ ] Ignored-test count cannot increase.
- [ ] Release-critical ignored tests are forbidden.
- [ ] Burn-down milestone exists.

---

# 22. Recommended First Action

Start with PR 1:

```text
PR 1 — CI Baseline and Workflow Validation
```

Then immediately PR 2:

```text
PR 2 — Full Gradle Verification
```

Do not start implementing large feature fixes until PR 2 is merged, because without `:app:check` and existing guard scripts in CI, later fixes can regress silently.