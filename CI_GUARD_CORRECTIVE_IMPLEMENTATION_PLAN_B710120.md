# CI Guard Corrective Implementation Plan

**Target reviewed:** `b71012066943a8cccf1afb26ebf7af6b4f960f3d`  
**Base reviewed:** `ebb5aa93348282b31c1c669d1bf1271d584b9eb0`  
**Status:** RED — corrective work required before merge.

At target SHA, Actions run `29116299203` reports failures in Static Guards, Lint & Check, and Release Check. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29116299203))

---

# 1. Required PR sequence

| PR | Purpose | Priority |
|---|---|---|
| G1 | Repair CI orchestration and ratchet semantics | S0 — first |
| G2 | Restore truthful DB-access enforcement | S0 |
| G3 | Remove unsafe PII suppressions | S0 |
| G4 | Make Gradle, lint, ignored-test, and artifact checks truthful | S0 |
| G5 | Correct actual release verification | S0 |
| G6 | Replace incomplete migration proof with executable proof | S0 |

After G1–G6, run the Final CI Guard Acceptance Gate. Do not call the original PR A–F program complete before that final verification.

---

# 2. Global agent rules

The implementation agent must not:

- Add `|| true`.
- Add `continue-on-error` to required checks.
- Change a blocking guard to warning/report mode.
- Add whole-file, whole-class, or package-wide exemptions.
- Add generic `"pre-existing pattern"` exemptions.
- Add `requires_write_barrier: false` merely to silence DB findings.
- Add raw PII findings to an allowlist.
- Increase ratchet or lint baselines.
- Add `@Ignore`.
- Raise the ignored-test threshold.
- Use debug signing as proof of release signing.
- Replace a real fix with a broad ProGuard `-dontwarn`.
- claim success from local tests alone.

When a violation cannot be fixed in the current PR:

1. Keep it in the exact pre-existing ratchet.
2. Link it to the correct issue.
3. Do not convert it into an allowlist exemption.
4. Ensure the ratchet still rejects new findings.

---

# 3. PR G1 — Repair CI control-plane semantics

## Suggested title

`fix(ci): separate source guards from artifact checks and repair ratchet contracts`

## Objective

Make Static Guards executable from a clean source checkout and standardize all child/suite exit semantics.

## Current defects

The Static Guards manifest includes `verify_release_artifact.py`, although the Static Guards job does not build an APK. Consequently, that guard cannot pass from a clean checkout. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b71012066943a8cccf1afb26ebf7af6b4f960f3d/.github/workflows/ci.yml))

The ratchet uses exit code `3` for resolved findings, while the suite runner recognizes only `0`, `1`, and “everything else is infrastructure failure.” ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b71012066943a8cccf1afb26ebf7af6b4f960f3d/scripts/ci/run_static_guard_suite.py))

## G1.1 Remove artifact checks from Static Guards

Remove `release_artifact` from `GUARD_MANIFEST`.

Static Guards may contain only checks whose declared inputs exist immediately after checkout and dependency setup.

Release artifact verification must run only after release construction in the release job.

Add a registry/manifest test asserting that source-only jobs cannot invoke guards requiring:

- APKs.
- AABs.
- R8 reports.
- Instrumented-test results.
- Migration-device results.

## G1.2 Standardize ratchet exit codes

Use only:

| Exit | Meaning |
|---:|---|
| 0 | Policy satisfied |
| 1 | Policy violation |
| 2 | Infrastructure/configuration/detector error |

Delete exit code `3`.

Ratchet outcomes:

| Situation | Exit |
|---|---:|
| Current findings exactly match baseline | 0 |
| New finding exists | 1 |
| Resolved finding remains in baseline | 1 |
| Baseline was properly pruned and no new finding exists | 0 |
| Guard child exits 2 | 2 |
| Guard child exits unknown code | 2 |
| Missing/malformed baseline | 2 |
| Child exits 1 but emits no parseable findings | 2 |

Resolved-but-unpruned entries must fail because otherwise they could later be reintroduced silently.

## G1.3 Correct child-process handling

Do not infer semantics from whether stdout is empty.

Required logic:

```text
child exit 0:
    current findings must be empty unless guard supports structured report output
child exit 1:
    parse findings from canonical output
    if no parseable findings exist, return infrastructure error
child exit 2:
    infrastructure error
other:
    infrastructure error
```

Capture both stdout and stderr in diagnostic logs, but require structured findings on the configured output channel.

## G1.4 Restrict baseline updates

Remove `--update-baseline` from normal CI-facing commands.

Bootstrap/update operations must:

- Be unavailable from the required suite.
- Compare against the protected base SHA.
- Prove every proposed finding existed on the base.
- Reject head-only findings.
- Require CODEOWNER review.
- Never update a baseline automatically after a failed run.

## G1.5 Reconcile lint-baseline count

The PR A commit describes 2,223 baseline entries, while the current suite passes `--max-missing-translations 2219`. ([github.com](https://github.com/panospao7/Cost-agregator/compare/ebb5aa93348282b31c1c669d1bf1271d584b9eb0...b71012066943a8cccf1afb26ebf7af6b4f960f3d))

The agent must:

1. Parse the committed baseline.
2. Calculate the exact current count.
3. Remove stale entries for strings already fixed.
4. Store the accepted count in one policy file.
5. Make the runner read that policy.
6. Remove the hardcoded count from `GUARD_MANIFEST`.

Do not increase the policy count to match an unexplained baseline.

## G1.6 Tests

Add tests for:

- Artifact-required guard rejected from source-only suite.
- Child exit 0.
- Child exit 1 with valid findings.
- Child exit 1 without findings.
- Child exit 2 with stdout.
- Child exit 2 without stdout.
- Unknown child exit.
- Resolved baseline entry.
- Fix-one/add-one attack.
- Missing command.
- Timeout.
- Missing baseline.
- Malformed baseline.
- Baseline update prohibited in CI mode.
- All later guards execute after an earlier failure.

## G1 acceptance

- Static suite runs every source guard.
- No artifact verifier runs in Static Guards.
- Suite emits only 0/1/2.
- Resolved stale baseline entries fail as policy violations.
- Static Guards no longer exits 2 because debt decreased.
- All guard logs and summary files upload.
- Static Guards reaches guard pytest.

---

# 4. PR G2 — Restore truthful DB-access enforcement

## Suggested title

`fix(database): revert broad DAO exemptions and restore exact DB ownership ratchet`

## Objective

Undo the false zero created by class-level DAO exemptions, keep existing debt visible, and make the DB guard fail closed.

## Current defects

The latest commit added many class-level entries, commonly using:

- `requires_write_barrier: false`
- No `methods_only`
- Generic MIT-003 ownership
- Generic “pre-existing pattern” reasons

It also added `DatabaseMigrations` and `FinancialRescueCoordinator` to a whole-class file-operation set. ([github.com](https://github.com/panospao7/Cost-agregator/commit/b71012066943a8cccf1afb26ebf7af6b4f960f3d))

The DB guard currently:

- Treats a missing allowlist as a warning and empty allowlist.
- Uses a custom line-oriented YAML parser.
- Silently skips unreadable source files.
- Approves file operations by class name.
- Treats any earlier barrier text in a method as sufficient proof. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/scripts/verify_db_access_boundaries.py))

## G2.1 Revert broad additions

Revert the DB allowlist additions introduced by `b710120`, except entries independently proven to be exact structural necessities.

Do not delete the original DB ratchet merely because the allowlist made the detector report zero.

Re-run the unsuppressed guard and recapture the actual current finding set.

Expected process:

1. Empty transitional allowlist locally.
2. Run the DB detector.
3. Classify every raw finding.
4. Identify detector false positives.
5. Fix detector false positives.
6. Restore exact pre-existing findings to the ratchet.
7. Add only proven structural exceptions.

## G2.2 Separate debt from structural exceptions

### Ratchet backlog

Use for unresolved architecture violations such as:

- Direct DAO ownership.
- Missing barrier.
- Direct event insertion.
- Worker mutation.
- Repository/lifecycle mismatch.

### Structural exceptions

Potential examples:

- Exact migration SQL inside named `MIGRATION_N_N+1` objects.
- Exact rescue operations executed under exclusive maintenance ownership.

Structural exceptions must specify:

- Rule/category.
- Exact path.
- Exact class.
- Exact function or migration symbol.
- Exact operation.
- Compensating control.
- Evidence test.

Do not maintain `FILE_OP_APPROVED` as a class-name set.

## G2.3 Replace custom YAML parsing

Use mandatory PyYAML safe loading plus schema validation.

Fail with exit 2 for:

- Missing file.
- Malformed YAML.
- Duplicate class/symbol entry.
- Unknown key.
- Wrong type.
- Missing reason/owner/issue.
- Invalid method.
- Invalid path.

## G2.4 Fail on unreadable source

Replace `except OSError: continue` with error collection and final exit 2.

Also fail on:

- Invalid UTF-8.
- Missing source root.
- Unexpected zero-file scan.
- Symlink escaping the repository.

## G2.5 Narrow matching

Replace filename-only authorization with:

- Exact repository path.
- Exact containing class.
- Exact function.
- Exact DAO.
- Exact mutation method.

An entry for one mutation must not suppress another mutation in the same class.

## G2.6 Barrier proof

Short-term acceptable proof:

- Barrier occurs in the same method.
- Barrier appears before the mutation.
- No early branch can reach the mutation without the barrier.
- Mutation inside `writeBarrier.runWrite {}` is accepted.

Long-term preferred proof:

- Parser/control-flow analysis.

Do not accept a barrier located in:

- A different method.
- An unrelated conditional branch.
- A comment/string.
- A path that returns before mutation.

## G2.7 Architecture burn-down groups

Perform code cleanup in this order:

### Group 1 — Diagnostics

- `OperationRunRecorder`
- `RestoreJournalImporter`

Create or use canonical diagnostic writers. Decide explicitly how diagnostics behave during maintenance.

### Group 2 — Receipt lifecycle

- `ReceiptMatchLifecycleService`
- `ReceiptSideEffectPlanner`
- `ReceiptInsertResolver`

Route receipt state/event writes through transaction-scoped legal owners.

### Group 3 — Transaction and provenance

- `DefaultExpenseCategoryAssignmentService`
- `DebugExpenseAuditWriter`
- `SourceLinkWriterImpl`
- `SourceLinkBackfillWorker`

Use canonical event/source-link writers and guarded worker scope.

### Group 4 — Bank, warranty, negotiation

- `BankApiIntegration`
- `WarrantyExpirationWorker`
- `WarrantyTrackerRepository`
- `SmartBillNegotiationEngine`

Move mutations behind lifecycle/repository coordinators and require barriers.

### Group 5 — Rescue and migrations

Keep only exact low-level structural exceptions with exclusive maintenance or Room migration proof.

## G2 tests

- Missing allowlist exits 2.
- Malformed YAML exits 2.
- Unreadable source exits 2.
- New mutation in an existing ratcheted class fails.
- Second mutation in a structurally excepted class fails.
- Barrier in unrelated branch fails.
- Barrier after mutation fails.
- Exact migration SQL exception passes.
- Unrelated SQL in `DatabaseMigrations.kt` fails.
- Rescue file operation outside approved method fails.

## G2 acceptance

- Broad entries from `b710120` removed.
- DB findings are either fixed, exactly ratcheted, or structurally excepted.
- No unexplained `requires_write_barrier: false`.
- No generic “pre-existing pattern” reasons.
- No class-level file-operation exemptions.
- DB baseline does not grow.
- DB guard fails closed.

---

# 5. PR G3 — Remove unsafe PII suppressions

## Suggested title

`fix(privacy): replace raw paths and exception text with typed safe diagnostics`

## Objective

Make PII strict-zero represent safe code rather than an allowlisted set of raw paths, OCR text, and exception messages.

## Current defects

The PII allowlist declares raw values safe in backup/export/debug contexts, including:

- `absolutePath`
- `e.message_logging`
- `e.message_wrap`
- `rawOcrText`

It also assumes network/system exception messages cannot contain PII. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/scripts/allowlists/pii_logging_allowlist.yml))

These assumptions are unsafe:

- Paths can contain user names, document names, merchants, dates, or account data.
- Exception messages can include URLs, query data, filenames, OCR text, provider bodies, or identifiers.
- Raw OCR is inherently sensitive.
- ViewModel errors can reach UI or diagnostics.

## G3.1 Introduce a typed diagnostic contract

Create or consolidate:

`SafeDiagnosticReporter`

Allowed fields:

- Fixed reason-code enum.
- Fixed stage enum.
- Severity.
- Safe numeric counts.
- Exception class category.
- Retryability.
- Build variant where necessary.

Forbidden fields:

- Raw Throwable.
- `Throwable.message`.
- Paths.
- Filenames.
- URLs.
- OCR text.
- Merchant/item/category values.
- Bank/account/token values.
- Arbitrary metadata maps.

## G3.2 Fix backup paths

Affected surfaces include:

- `DatabaseBackupRepositoryImpl`
- `CostbackupBundle`
- `BackupVerifier`
- `BackupRestoreViewModel`

Required behavior:

- Store/display a user-facing operation result without raw path.
- Log fixed codes such as `BACKUP_CREATE_FAILED`.
- Use document display names only in UI if explicitly user-selected and safely handled; do not log them.
- Never wrap `e.message`.
- Preserve cancellation propagation.

## G3.3 Fix export paths

Affected surfaces include:

- `ExportAnonymizer`
- `ExportOptionsViewModel`

Required behavior:

- Remove raw OCR logging entirely.
- Replace exceptions with typed export outcomes.
- Do not persist or display internal paths.
- Use controlled user-facing messages.
- Keep raw causes internal and non-serialized.

## G3.4 Move debug-only code

If `DebugDataStorage` is truly debug-only:

- Move it to `app/src/debug`.
- Provide a release-safe abstraction in `src/main` or `src/release`.
- Ensure the release artifact does not contain the debug implementation.

A `BuildConfig.DEBUG` branch inside `src/main` is not sufficient privacy ownership.

## G3.5 Fix location/network paths

For geocoding and location services:

- Do not assume `e.message` is safe.
- Record exception class/category only.
- Remove request URLs and location values from errors.
- Return typed network/permission/unavailable outcomes.

## G3.6 Remove unsafe allowlist entries

Delete entries for:

- `absolutePath`
- `rawOcrText`
- `e.message_logging`
- `e.message_wrap`

Do not replace them with differently named generic symbols.

## G3.7 Sentinel tests

Use synthetic sentinel values resembling:

- Email.
- User filesystem path.
- API token.
- IBAN/card number.
- Receipt item.
- Merchant name.
- OCR line.
- URL query.
- Backup filename.

Assert no sentinel reaches:

- Logs.
- Diagnostic metadata.
- Exception messages.
- UI state.
- Operation-run records.
- Analytics.
- Persisted errors.

## G3 acceptance

- PII guard strict-zero.
- No unsafe PII allowlist entries.
- No raw exception message in touched paths.
- No raw Throwable passed to logging.
- No production `printStackTrace`.
- No path/OCR logging.
- Debug-only implementation absent from release source set.

---

# 6. PR G4 — Make Gradle and baseline checks truthful

## Suggested title

`fix(ci): remove fail-open Gradle guards and unify lint and ignored-test policies`

## Objective

Ensure `:app:check` has no external-tool surprises, fail-open missing-script behavior, or inconsistent debt thresholds.

## Current defects

Gradle-wired KTS guards require a separate `kotlin` executable. Missing scripts warn and return, while command failure throws. The inline lifecycle guard uses class-name substring allowlisting. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/app/build.gradle.kts))

The ignored-test Gradle threshold defaults to 310 despite the Python guard using a baseline near 29. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/app/build.gradle.kts))

## G4.1 Remove external Kotlin CLI dependency

Replace:

- `check_lifecycle_bypasses.kts`
- `check_raw_money_aggregates.kts`
- `check_direct_time_calls.kts`

with one of:

1. Canonical Python guards, or
2. JVM architecture tests built by Gradle.

Gradle must not require `kotlin` on PATH.

## G4.2 Fail on missing guard implementation

A missing guard script or executable must throw `GradleException`.

Do not log a warning and return.

Differentiate:

- Exit 1: policy violation.
- Exit 2: guard infrastructure failure.

Preserve the child output in the Gradle failure report.

## G4.3 Remove duplicate inline scanner

Remove or retire the inline `checkLifecycleBypass` class-name substring scanner after canonical DB/lifecycle guard parity is proven.

There must be one source of:

- Rule logic.
- Allowlist.
- Findings.
- Documentation.

## G4.4 Unify ignored-test policy

Create one canonical ignored-test policy.

Use the same exact baseline for:

- Python guard.
- Gradle task.
- CI docs.

Replace default `310` with the actual accepted baseline.

Prefer parser-aware annotation detection over line counting.

Rules:

- New ignore fails.
- Resolved ignore requires baseline pruning.
- Critical test ignore always fails.
- Removing a critical class from policy fails.

## G4.5 Harden lint baseline

Maintain only `MissingTranslation` entries.

Implement exact set comparison using fingerprints based on:

- Issue ID.
- Resource file.
- String resource name.
- Locale/resource source.

Rules:

- New untranslated string fails.
- Resolved entry must be pruned.
- Fix-one/add-one fails.
- Non-MissingTranslation entry fails.
- Whole-baseline regeneration fails.
- Count threshold comes from one policy source.

Run full lint; ensure no `checkOnly MissingTranslation` remains.

## G4.6 Improve CI diagnostics

Split commands or capture output so the failing `:app:check` task is visible.

Upload:

- Full Gradle logs.
- JUnit XML.
- Lint HTML/XML/SARIF.
- Guard outputs.
- Room verification.
- Ignored-test verification.

Required artifact absence must fail the relevant aggregator.

## G4.7 Unit-test timeout

If unit tests still approach 30 minutes:

1. Obtain per-class timing.
2. Identify hangs/slow architecture scans.
3. Fix deterministic waits.
4. Split core and architecture suites if needed.
5. Use a stable Unit Tests aggregator.
6. Do not simply raise the timeout without diagnosis.

## G4 acceptance

- `:app:check` succeeds without external Kotlin CLI.
- Missing guard implementation fails closed.
- One canonical lifecycle/DB guard remains.
- Ignored-test baseline is exact and shared.
- Lint baseline is exact and no-growth.
- Full lint remains enabled.
- Complete reports upload.

---

# 7. PR G5 — Correct actual release verification

## Suggested title

`fix(release): verify the built release artifact with real Android tooling`

## Objective

Replace the current minimal ZIP-name scanner with meaningful release-artifact inspection.

## Current defects

The release build uses debug signing for CI. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/app/build.gradle.kts))

The verifier:

- Checks only whether an APK exists.
- Searches ZIP entry names for `debug` or `mock`.
- Checks whether `mapping.txt` exists.
- Does not inspect the merged manifest, DEX classes, signing certificate, endpoints, resources, or runtime behavior. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/scripts/verify_release_artifact.py))

The ProGuard configuration contains broad component keep rules and a direct JP2Decoder `-dontwarn`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/app/proguard-rules.pro))

## G5.1 Separate build and audit

Release workflow:

1. Build release APK and AAB.
2. Upload immutable artifact/checksums.
3. Audit the exact built artifacts.
4. Generate APKs from AAB.
5. Install and smoke-test.
6. Aggregate into stable `Release Verification`.

Do not rerun the source-only static suite’s artifact checks.

## G5.2 Use proper Android tools

Use:

- `apkanalyzer`
- `aapt2`/Android manifest tooling
- `apksigner`
- `bundletool`
- DEX class/string inspection

Do not infer packaged classes from ZIP entry names.

## G5.3 Ephemeral non-debug signing

For PR CI:

- Generate an ephemeral verification keystore.
- Use a certificate that is not the Android debug certificate.
- Do not commit/upload the key.
- Record only the public certificate fingerprint.
- Clean up signing material with `if: always()`.

Production signing remains in a protected release environment.

Remove hardcoded debug signing from the release build configuration. Inject CI signing through secured Gradle properties.

## G5.4 Manifest policy

Verify from the built artifact:

- `debuggable=false`
- `testOnly=false`
- `allowBackup=false`
- Cleartext disabled
- Expected application ID/SDK versions
- Exact exported components
- Exact component permissions
- No test instrumentation
- No debug providers/activities
- Narrow FileProvider paths

## G5.5 Artifact policy

Inspect DEX/resources/assets for:

- Debug/demo/fake/mock/test implementations.
- HTTP body loggers.
- Debug logger trees.
- Placeholder endpoints.
- Secrets/private keys/keystores.
- Test fixtures.
- Demo bank connectors.
- Unsafe no-op bindings.

## G5.6 Review ProGuard rules

For each broad keep rule:

- Determine whether AGP/library consumer rules already cover it.
- Remove it where unnecessary.
- Add narrow keep rules only for reproduced runtime failures.
- Add a smoke test for every project-owned keep rule.

For `JP2Decoder`:

1. Determine which dependency references it.
2. Determine whether the code path is optional and unreachable.
3. Prefer dependency correction or feature exclusion.
4. Retain a narrow `-dontwarn` only with a documented dependency reason and release smoke proof.

## G5.7 Runtime smoke

On minimum supported API and modern API:

- Install AAB-generated APKs.
- Launch application.
- Verify Hilt startup.
- Open Room.
- Initialize WorkManager.
- Test safe/invalid deep links.
- Verify disabled bank/demo features cannot report fake success.
- Fail on fatal exception or ANR.

## G5.8 Verifier exit semantics

- Missing expected artifact: exit 2.
- Malformed/unreadable artifact: exit 2.
- Security policy violation: exit 1.
- Complete pass: exit 0.

Generate machine-readable reports even on failure.

## G5 acceptance

- Release APK and AAB built.
- Non-debug CI certificate used.
- Artifact verifier uses Android/DEX tooling.
- Manifest, signing, classes, resources, secrets, and endpoints inspected.
- Minification and shrinking proven.
- Runtime smoke passes.
- Release Verification is blocking.

---

# 8. PR G6 — Replace incomplete migration proof

## Suggested title

`fix(database): execute production Room migrations and enforce semantic schema parity`

## Objective

Supplement the Robolectric test with a blocking Android migration proof that exercises the shipping builder.

## Current defects

The current migration proof uses `MigrationTestHelper` under Robolectric/API 28. Its “fresh” schema is created by `MigrationTestHelper.createDatabase(currentVersion)`, not by the production Room builder. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b71012066943a8cccf1afb26ebf7af6b4f960f3d/app/src/test/java/com/yourname/expensetracker/data/database/DatabaseMigrationProofTest.kt))

That proves schema-snapshot compatibility but does not fully prove:

- Shipping builder registration.
- Fresh production entity schema.
- Android SQLite behavior across API levels.
- Runtime DAO behavior.

## G6.1 Keep fast JVM preflight

Retain JVM tests for:

- Version policy.
- Registry continuity.
- Schema snapshot presence.
- Simple edge validation.

Rename documentation so it does not claim complete production migration proof.

## G6.2 Add blocking instrumented migration suite

Create dedicated classes:

- `DatabaseMigrationEdgeTest`
- `DatabaseMigrationChainTest`
- `DatabaseProductionBuilderMigrationTest`
- `DatabaseFreshMigratedParityTest`
- `DatabaseUnsupportedVersionPolicyTest`
- `DatabaseMigrationAtomicityTest`

Run only this package in a dedicated required emulator job.

## G6.3 Production-builder migration

For every supported start version:

1. Create old DB from schema snapshot.
2. Seed representative data.
3. Close helper DB.
4. Open the same file through `AppDatabase.fileBuilder`.
5. Trigger actual opening.
6. Query through real DAOs.
7. Verify current `user_version`.
8. Verify seeded data.
9. Run integrity/FK checks.

Do not pass migrations manually in this test.

## G6.4 Fresh-production parity

Fresh side:

- Create through production `AppDatabase` builder.
- Force database open.
- Capture structured schema.

Migrated side:

- Create at minimum supported version.
- Open through production builder.
- Capture structured schema.

Compare:

- Tables.
- Columns.
- Types.
- Nullability.
- Defaults.
- PK order.
- Indexes and uniqueness.
- Foreign keys and actions.
- Triggers.
- Views.
- Room identity.
- User version.

## G6.5 Representative data

Seed a consistent graph covering:

- Expense/category.
- Recurring rule.
- Receipt/link.
- Group/member.
- Background job/run.
- Lifecycle event where available.

Verify exact values, relationships, defaults, and uniqueness behavior.

## G6.6 Unsupported versions

Choose one deterministic policy:

- Block and rescue without mutation, preferably, or
- Explicit destructive fallback.

Test the exact outcome. Do not accept either preservation or deletion.

## G6.7 API matrix

Run on:

- Minimum supported API, currently derived from `minSdk`.
- API 34 or current maintained modern API.

Both lanes block.

## G6 acceptance

- Fast JVM migration checks pass.
- Blocking emulator Migration Proof passes.
- Production builder tested.
- Every supported start reaches current.
- Representative data survives.
- Semantic parity passes.
- Unsupported policy deterministic.
- No skipped migration tests.

---

# 9. Final integration PR

## Suggested title

`ci: enforce final guard acceptance gate and record verified evidence`

This PR must contain no new product refactoring.

Tasks:

1. Run all source guards.
2. Run trusted-base versus head guard comparison.
3. Run unit and architecture tests.
4. Run full lint and `:app:check`.
5. Run Migration Proof.
6. Run Release Verification.
7. Verify all required artifacts.
8. Verify branch protection.
9. Run the exact target SHA twice.
10. Update documentation only after both runs pass.

Required stable checks:

- Validate Workflow
- Static Guards
- Guard Integrity
- Unit Tests
- Lint & Check
- Migration Proof
- Release Verification

---

# 10. Agent working protocol

For every corrective PR, the agent shall produce:

## Before-change report

- Target SHA.
- Commands run.
- Current findings.
- Current baseline/allowlist entries.
- Current CI failure.
- Intended removals and fixes.

## Implementation report

For each changed file:

- Why it changed.
- What invariant is enforced.
- What failure it prevents.
- Which test covers it.

## After-change report

- Exact command results.
- Findings added/removed.
- Baselines pruned.
- Exceptions added/removed.
- Tests executed.
- CI run ID.
- Remaining known debt.

## Mandatory review question

Before adding any exception, the agent must answer:

> Is this behavior intrinsically required, or is this unresolved architecture debt?

If unresolved debt, it belongs in the exact ratchet—not an allowlist.

---

# 11. Final definition of done

The correction program is complete only when:

- Static Guards runs successfully from a source-only checkout.
- Artifact guards run only after artifacts exist.
- Ratchets use only exit codes 0/1/2.
- New and stale findings both block.
- DB zero is not manufactured through class-level exemptions.
- PII zero is not manufactured through raw-data suppressions.
- `:app:check` does not depend on external Kotlin CLI.
- Lint and ignored-test debt cannot grow or be swapped.
- Release verification inspects and installs the actual artifact.
- Migration proof uses the shipping Room builder.
- Every required check is blocking.
- Two complete runs pass for the exact same SHA.
- No baseline, exception, threshold, warning mode, ignored test, or broad suppression was introduced to obtain green CI.

**Required invariant:**

> Green CI must mean that every required check ran against its real inputs, existing debt did not grow or mutate, and no architecture, privacy, database, migration, or release finding was hidden to manufacture success.