# Remaining CI Guard Implementation Plan

## 1. Target and objective

**Starting commit:** `b422240d00b79ef58e6ee9df80abb65a378e1841`  
**Reference run:** `29123964374`  
**Overall status:** RED

The latest commit only removed BOM bytes from schema `147.json`; it did not resolve the remaining CI architecture issues. ([github.com](https://github.com/panospao7/Cost-agregator/commit/b422240))

Required final outcome:

- Static Guards passes truthfully.
- Guard Integrity passes.
- Unit Tests complete.
- Lint & Check passes.
- PII logging is strict-zero.
- Existing DB/cancellation/event debt cannot grow.
- Migration Proof executes the production Room path.
- Release Verification inspects and runs the real optimized artifact.
- No issue is hidden by an exemption, baseline increase, warning mode, or skipped test.

---

# 2. Recommended PR sequence

| PR | Purpose | Dependency |
|---|---|---|
| H1 | Repair ratchets, test discovery, and Gradle/CI policy consistency | First |
| H2 | Restore exact DB ownership enforcement | H1 |
| H3 | Reach strict-zero PII logging | H1 |
| H4A | Introduce canonical guard registry and shared engine | H1 |
| H4B | Replace regex/inline guards with parser-aware analysis | H2, H3, H4A |
| H5 | Implement actual Release Verification | H1; may parallel H4 |
| H6 | Implement blocking Migration Proof | H1; may parallel H4/H5 |
| H7 | Final integration, branch protection, and acceptance evidence | All previous |

Do not combine H5 or H6 with the guard-engine refactor.

---

# 3. Global implementation rules

The agent must not:

- Add `|| true`.
- Add `continue-on-error` to required checks.
- Change blocking guards to warning/report mode.
- Increase a baseline or ignored-test budget to obtain green.
- Add whole-file, class, worker, or package exemptions.
- Add `symbol: "*"` or suffix-only path matching.
- Add `@Ignore`.
- Add broad R8 `-dontwarn` or keep-all rules.
- Treat missing artifacts, unreadable files, parser errors, or skipped tests as passes.
- claim completion from local checks alone.

Every PR must include:

1. Before-change failure evidence.
2. Root-cause description.
3. Implementation.
4. Regression test that fails before and passes after.
5. Exact local command output.
6. Actual Actions run ID.
7. Updated baseline only when entries were removed or migrated through a proven one-to-one detector migration.

---

# 4. Phase 0 — Capture exact current failures

Before editing code, download the artifacts from run `29123964374`:

- `static-guard-results`
- `gradle-logs`
- `lint-results`
- `release-apk`

Inspect:

```text
build/ci/static-guards/summary.json
build/ci/static-guards/summary.md
build/ci/static-guards/*.log
app/build/reports/
app/build/test-results/
```

Record:

- Exact guard producing `infra_error`.
- Exact failing `:app:check` task.
- Exact release verifier finding.
- Unit-test final status and slowest classes.
- Whether the PII guard’s remaining finding is still present.

Create:

```text
docs/ci/failure-snapshots/B422240_FAILURE_SNAPSHOT.md
```

Do not modify a baseline until the actual failure is identified.

---

# 5. PR H1 — Canonical ratchet and CI consistency

## Suggested title

`fix(ci): make guard ratchets deterministic and align all enforcement paths`

## 5.1 Fix guard-test discovery

The suite currently invokes only `scripts/test_verify_*.py`; tests under `scripts/ci/test_*.py` are not included. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b422240/scripts/ci/run_static_guard_suite.py))

Replace the glob-specific command with recursive discovery, or introduce a canonical test manifest.

Required behavior:

- Discover all `test_*.py` under `scripts/`.
- Exclude only declared fixture directories.
- Run `scripts/ci/test_guard_ratchet.py`.
- Run `scripts/ci/test_run_static_guard_suite.py`.
- Fail if a registered guard has no test file.
- Fail if a guard test exists but is not collected.

Add a collection verifier that compares:

- Files discovered on disk.
- Tests collected by pytest.
- Tests declared by the guard registry.

## 5.2 Stop parsing human-readable guard output

The ratchet currently parses stdout with several regular expressions and fingerprints findings using `path:line`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b422240/scripts/ci/guard_ratchet.py))

Introduce a minimal canonical finding format:

```text
schema_version
guard
detector_version
rule_id
kind
path
symbol
operation
line
message
fingerprint
```

Each ratcheted guard must support structured JSON output:

- Cancellation.
- Privacy.
- DB access.
- Event writers.
- Money.

The ratchet must read the JSON file, not stdout.

Stdout/stderr remain diagnostic logs only.

## 5.3 Repair exit-code semantics

Allowed codes:

| Code | Meaning |
|---:|---|
| 0 | Policy satisfied |
| 1 | Policy violation |
| 2 | Infrastructure/configuration/detector failure |

Required handling:

- Child `0` with unexpected findings: exit 2.
- Child `1` with zero valid structured findings: exit 2.
- Child `1` with findings: compare against baseline.
- Child `2`: exit 2.
- Unknown exit code: exit 2.
- New finding: exit 1.
- Resolved-but-unpruned baseline entry: exit 1.
- Exact current/baseline match: exit 0.

The current implementation checks only whether stdout is nonempty when the child exits 1. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b422240/scripts/ci/guard_ratchet.py))

## 5.4 Use one final outcome everywhere

Calculate a single final result before writing reports.

Use it consistently for:

- Process exit code.
- Summary JSON.
- Summary Markdown.
- Console output.
- GitHub step summary.

The current summary marks only new findings as failure, even though resolved entries later make the process fail. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b422240/scripts/ci/guard_ratchet.py))

## 5.5 Replace line-based fingerprints

Fingerprint using:

- Guard ID.
- Rule ID.
- Exact repository-relative path.
- Containing class/function/property.
- Finding category.
- Normalized unsafe operation.
- Detector version.

Exclude:

- Line number.
- Absolute path.
- Source formatting.
- Raw source text.
- Timestamp.

Line number remains diagnostic-only.

## 5.6 Migrate existing baselines safely

Create a one-time baseline migration tool.

For each existing entry:

1. Run the old detector against `b422240`.
2. Run the new detector against `b422240`.
3. Map old to new one-to-one.
4. Reject one-to-many or unmatched mappings.
5. Prove no head-only finding enters the baseline.
6. Generate a migration report.
7. Seal the new baseline format.

Do this for:

- Cancellation.
- Privacy.
- DB access.
- Event writers.
- Money.

## 5.7 Validate baseline schema

Require:

- Correct schema version.
- Correct guard name.
- Correct detector version.
- Unique fingerprints.
- Exact entry types.
- No unknown fields.
- No absolute paths.
- No wildcard paths/symbols.
- Deterministic ordering.

Malformed baselines must exit 2.

## 5.8 Prevent manual baseline inflation

Add protected-base comparison:

- Removals: allowed.
- Additions: forbidden.
- Fingerprint replacement: forbidden.
- Ratchet-to-report change: forbidden.
- Detector-version change without migration report: forbidden.
- Deleting a baseline while findings remain: forbidden.

Checkout must provide sufficient Git history.

## 5.9 Align Gradle and Static Guards

Static Guards accepts DB findings via a ratchet, while the Gradle check invokes the raw DB detector in strict mode. This makes `:app:check` incompatible with the documented DB policy.

Create one command:

```text
scripts/ci/run_guard.py --guard db_access --mode ci
```

Both Static Guards and Gradle must call this command.

Apply the same pattern to all guards wired into `:app:check`.

Gradle must propagate:

- Exit 1 as policy failure.
- Exit 2 as infrastructure failure.
- Child stdout/stderr into the Gradle report.

## 5.10 Rewrite the DB production integration test

The current test asserts that production has zero raw DB findings even though the baseline contains known findings. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b422240/scripts/test_verify_db_access_boundaries.py))

Replace it with:

1. Run the real detector.
2. Parse structured findings.
3. Load the DB baseline.
4. Assert no new findings.
5. Assert no stale entries.
6. Assert no infrastructure errors.

Keep separate clean-fixture tests that assert zero raw findings.

## H1 required tests

- Exit 1 plus invalid JSON.
- Exit 1 plus zero findings.
- Exit 2 with stdout.
- Unknown child exit.
- New finding.
- Resolved stale entry.
- Fix-one/add-one attack.
- Line movement preserves fingerprint.
- Symbol movement changes fingerprint.
- Duplicate baseline entry.
- Wrong guard name.
- Detector-version mismatch.
- Baseline addition in PR.
- Missing command.
- Timeout.
- Every later guard executes after earlier failure.
- All `scripts/ci` tests are collected.

## H1 acceptance

- Static Guards completes all source guards and all guard tests.
- Ratchets use structured findings.
- Summary and process results agree.
- `:app:check` and Static Guards enforce identical DB policy.
- Baselines cannot be inflated.
- No exit 2 remains unexplained.

---

# 6. PR H2 — Exact DB ownership and barrier enforcement

## Suggested title

`fix(database): separate legal DB ownership from architecture debt`

## Objective

Keep unresolved DB debt visible without permitting broad class-level ownership.

## 6.1 Separate three concepts

Create distinct policy files:

```text
config/guards/db_ownership_policy.yml
config/guards/db_structural_exceptions.yml
config/baselines/db_access.json
```

### Ownership policy

For legitimate canonical writers.

Each entry identifies:

- Exact path.
- Exact class.
- Exact method.
- Exact DAO.
- Exact mutation operation.
- Barrier requirement.
- Transaction requirement.
- Evidence test.

### Structural exceptions

Only intrinsically low-level operations, such as:

- Exact Room migration SQL.
- Exact maintenance/rescue operation under exclusive ownership.

### Ratchet backlog

For unresolved violations:

- Direct DAO ownership.
- Missing barriers.
- Direct database chains.
- Illegal event writes.
- Worker mutation ownership.

Debt belongs in the ratchet, not the ownership policy.

## 6.2 Audit every existing DB allowlist entry

For every entry, classify:

1. Canonical legal writer.
2. Structural exception.
3. Unresolved debt.
4. Detector false positive.

Required action:

- Legal writer: convert to exact method/operation policy.
- Structural: convert to exact exception with compensating controls.
- Debt: remove from allowlist and retain as exact ratchet finding.
- False positive: fix detector and add fixture.

Reject reasons such as:

- “Pre-existing.”
- “Legacy.”
- “Reviewed.”
- “Intentional pattern.”

## 6.3 Replace custom YAML parsing

Use mandatory safe YAML loading plus JSON Schema validation.

Fail with exit 2 on:

- Missing policy.
- Duplicate key.
- Unknown field.
- Invalid type.
- Missing owner/reason/evidence.
- Invalid date.
- Missing path or symbol.
- Path outside repository.

## 6.4 Require exact matching

Never authorize by filename alone.

Match:

- Repository-relative path.
- Class.
- Function.
- DAO type/property.
- Mutation method.
- Rule category.

One authorized mutation must not permit another mutation in the same method or file.

## 6.5 Strengthen barrier proof

Preferred legal form:

```text
writeBarrier.runWrite {
    repository-or-dao mutation
}
```

or another single canonical API.

The detector must prove the mutation is inside the guarded scope.

Do not accept:

- Barrier text elsewhere in the method.
- Barrier in an unrelated branch.
- Barrier after mutation.
- Barrier in a comment/string.
- A different helper with a similar name.

Parser-aware dominance proof is completed in H4B. Until then, use a conservative block-aware implementation and report uncertainty as a finding.

## 6.6 Prioritized burn-down

### Priority 1: unguarded writes

Fix all `MISSING_WRITE_BARRIER` and direct-chain missing-barrier findings first.

### Priority 2: diagnostics and restore

Review:

- `OperationRunRecorder`
- `RestoreJournalImporter`
- Rescue paths

Define whether diagnostics are permitted during maintenance and through which owner.

### Priority 3: receipt lifecycle

Review:

- `ReceiptMatchLifecycleService`
- `ReceiptSideEffectPlanner`
- `ReceiptInsertResolver`

Route state and events through transaction-scoped owners.

### Priority 4: event/provenance writers

Review:

- `DebugExpenseAuditWriter`
- `DefaultExpenseCategoryAssignmentService`
- `SourceLinkWriterImpl`
- `SourceLinkBackfillWorker`

### Priority 5: bank, negotiation, warranty

Review:

- `BankApiIntegration`
- `SmartBillNegotiationEngine`
- `WarrantyExpirationWorker`
- `WarrantyTrackerRepository`

Do not require all debt to reach zero in H2, but the set must not grow or be hidden.

## H2 tests

- New mutation in a known writer fails.
- Second mutation in an excepted method fails.
- Barrier in unrelated branch fails.
- Barrier after mutation fails.
- Exact migration SQL passes.
- Unrelated SQL in migration file fails.
- Rescue operation outside approved method fails.
- Missing/unreadable policy exits 2.
- One exception matching multiple findings fails.
- Stale ownership entry fails.

## H2 acceptance

- No broad class/file DB exemptions.
- Every legal writer is exact.
- Every unresolved violation remains ratcheted.
- No unexplained `requires_write_barrier: false`.
- New DB findings fail Static Guards and `:app:check`.
- DB baseline is unchanged or smaller.

---

# 7. PR H3 — Strict-zero PII logging

## Suggested title

`fix(privacy): eliminate final PII finding and prohibit broad PII exceptions`

## 7.1 Determine the remaining finding

Run the strict PII guard and identify the exact file, symbol, and category.

The G3 commit reported one remaining PII finding, while the suite currently invokes PII directly in strict mode. ([github.com](https://github.com/panospao7/Cost-agregator/compare/b71012066943a8cccf1afb26ebf7af6b4f960f3d...b422240))

Fix the code rather than baselining it.

## 7.2 Delete unused PII debt configuration

If `config/baselines/pii_logging.json` exists:

- Verify whether it is referenced.
- Fix the finding.
- Delete the baseline.
- Keep PII logging in strict mode.

Do not confuse the broader privacy guard ratchet with PII logging.

## 7.3 Remove unsafe matching behavior

The PII guard currently supports suffix path matching and `symbol: "*"`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b422240/scripts/verify_pii_logging_boundaries.py))

Replace with:

- Exact normalized path.
- Exact symbol.
- Exact category.
- Exact rule.

Preferred final state: empty PII allowlist.

## 7.4 Enforce safe diagnostics

Production diagnostics may accept only:

- Fixed reason code.
- Stage enum.
- Severity.
- Safe count.
- Retryability.
- Exception class category.

Block:

- Raw Throwable.
- `Throwable.message`.
- Paths and filenames.
- URLs.
- OCR/receipt/email content.
- Merchant/item/category text.
- Bank/account/token fields.
- Arbitrary maps.

## 7.5 Source-set enforcement

Verify debug storage/logging implementations exist only in `src/debug`.

Release stubs must:

- Return typed unavailable state.
- Not emit raw values.
- Not claim success.
- Not persist demo/debug data.

## H3 tests

Use synthetic sentinels for:

- Email.
- API token.
- User path.
- Receipt item.
- OCR line.
- Merchant.
- IBAN/card.
- URL query.
- Backup filename.

Assert no sentinel enters:

- Logs.
- UI state.
- Exceptions.
- Diagnostics.
- Persisted operation results.

## H3 acceptance

- PII guard reports zero findings.
- No PII baseline.
- No PII wildcard or suffix exemption.
- No raw exception/path/OCR logging.
- Release artifact excludes debug diagnostic implementations.

---

# 8. PR H4A — Canonical guard platform

## Suggested title

`refactor(guards): centralize guard registry execution and policy`

## 8.1 Create canonical registry

Create:

```text
config/guards/registry.yml
```

Each guard declares:

- Guard ID.
- Rule IDs.
- Owner.
- Engine.
- Entrypoint.
- Mode.
- Scope.
- Timeout.
- Policy/allowlist.
- Baseline.
- Test files.
- Detector version.
- Output schema.

Workflow YAML must invoke the registry runner only.

## 8.2 Shared guard library

Create shared modules for:

- Finding/result models.
- Strict config loading.
- Path normalization.
- Atomic report writing.
- Baseline comparison.
- Exception validation.
- Registry validation.
- JSON/SARIF reporting.

## 8.3 Thin compatibility wrappers

Existing `verify_*.py` commands may remain temporarily, but they must contain no independent detection policy.

They should delegate to the canonical engine.

## 8.4 Thin Gradle adapters

Remove detection logic from Gradle.

Gradle tasks should invoke:

```text
run_guard.py --guard <guard-id> --mode ci
```

The current build file contains inline lifecycle, money, and time regex scanners, recreating guard drift. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b422240/app/build.gradle.kts))

## 8.5 Guard integrity job

Add a blocking `Guard Integrity` job that verifies:

- Every guard registered once.
- Every registered test collected.
- No orphan allowlist/baseline.
- No mode weakening.
- No rule deletion without migration metadata.
- No direct workflow guard invocation.
- No `|| true`.
- Required artifacts exist.

## H4A acceptance

- One canonical guard inventory.
- No manual workflow command list.
- No inline Gradle detector logic.
- Guard tests and docs derive from registry.
- `Guard Integrity` passes.

---

# 9. PR H4B — Parser-aware Kotlin guards

## Suggested title

`refactor(guards): replace high-risk regex scans with symbol-aware Kotlin analysis`

## 9.1 Build a JVM guard-engine module

Use Kotlin PSI/compiler APIs compatible with the project Kotlin version.

Parse production source once and expose:

- Imports and aliases.
- Classes and inheritance.
- Functions and suspend modifiers.
- Constructor injection.
- Calls and receivers.
- Catch clauses.
- Lambdas.
- Assignments.
- Return paths.
- Source locations.

## 9.2 Migrate high-risk rules

Order:

1. Cancellation.
2. Worker guard scope.
3. UI DAO ownership.
4. DB mutation/barrier dominance.
5. Event writer ownership.
6. Cloud prepared-payload flow.
7. PII safe-diagnostic ownership.
8. Receipt links/import provenance.
9. Money aggregation.
10. Direct time calls.

## 9.3 Conservative control-flow rules

When the analyzer cannot prove safety, emit a finding.

Examples:

- Barrier must dominate mutation.
- Worker guard must dominate DB work.
- Cancellation must be rethrown before fallback.
- Prepared payload must precede network body construction.
- Transaction context must surround state/event writes.

## 9.4 Adversarial fixtures

Every migrated rule must test:

- Alias imports.
- Fully qualified calls.
- Multiline calls.
- Comments and strings containing safe markers.
- Nested functions.
- Dead guard calls.
- Similar unrelated method names.
- Typealiases.
- Expression-bodied methods.
- Named lambda parameters.
- Duplicate filenames in different packages.

## 9.5 Trusted-base protection

On PRs modifying guards:

1. Run protected-base guard engine against head application source.
2. Run head engine against head source.
3. Run policy delta.
4. Run adversarial corpus.

A PR must not weaken the guard that validates its own code.

## H4B acceptance

- Inline/regex duplicates removed.
- High-risk ordering checks are parser/control-flow aware.
- Parse/type-resolution failures exit 2.
- Existing baselines migrated one-to-one.
- No head-only finding enters a baseline.

---

# 10. PR H5 — Actual Release Verification

## Suggested title

`ci(release): inspect, sign, install, and smoke-test release artifacts`

The existing verifier only checks APK existence, ZIP entries, DEX count, and mapping-file presence. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b422240/scripts/verify_release_artifact.py))

## 10.1 Release build outputs

Build once:

- Release APK.
- Release AAB.
- Mapping/seeds/usage/configuration reports.
- Merged manifest.
- Dependency report.
- Artifact checksums.

## 10.2 Remove debug signing from release policy

The current release build explicitly uses debug signing. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b422240/app/build.gradle.kts))

For PR CI:

- Generate an ephemeral non-debug keystore.
- Inject signing configuration securely.
- Never commit/upload the key.
- Record only certificate fingerprint.
- Delete signing material in an always-run cleanup step.

Production signing remains protected and separate.

## 10.3 Add release policy

Create:

```text
config/release-verification-policy.yml
```

Define:

- Expected application ID and SDKs.
- Exported components.
- Component permissions.
- Allowed permissions.
- Forbidden classes/packages.
- Forbidden assets/resources.
- Endpoint policy.
- Logging policy.
- Signing policy.
- FileProvider policy.

## 10.4 Artifact inspection

Use Android tooling to verify:

- AAB validity.
- APK signatures.
- Manifest flags.
- Exported components.
- Permissions.
- Cleartext disabled.
- Network-security config.
- DEX class inventory.
- Debug/demo/fake/test implementations absent.
- HTTP body loggers absent.
- Secrets and private keys absent.
- Placeholder/development endpoints absent.
- FileProvider paths narrow.
- R8 and resource shrinking active.

Missing/unreadable artifacts must exit 2; policy findings exit 1.

## 10.5 ProGuard review

Review every project keep or warning rule.

Remove broad rules unless a reproduced runtime failure proves they are required.

For every retained project rule:

- Document protected behavior.
- Add release smoke coverage.

## 10.6 Runtime smoke

Install AAB-generated APKs on:

- API 26.
- API 34 or current maintained modern API.

Verify:

- Installation.
- Process launch.
- Hilt initialization.
- Room open.
- WorkManager initialization.
- Safe and malformed deep links.
- Disabled bank/demo features do not return fake success.
- No fatal exception or ANR.
- No unexpected startup network request.

## 10.7 Workflow jobs

Create:

- `release-build`
- `release-artifact-audit`
- `release-smoke`
- Stable aggregator: `Release Verification`

No required release job may be skipped or non-blocking.

## H5 acceptance

- APK and AAB produced.
- Non-debug verification certificate.
- AAB-generated APKs inspected and installed.
- Manifest/DEX/resource/signature checks pass.
- Release smoke passes on both API lanes.
- Stable Release Verification check passes.

---

# 11. PR H6 — Blocking Migration Proof

## Suggested title

`ci(database): execute production Room migrations on required API levels`

The current migration proof is a Robolectric/JVM test using `MigrationTestHelper`; its fresh schema is also created through the helper rather than the production builder. ([github.com](https://github.com/panospao7/Cost-agregator/blob/b422240/app/src/test/java/com/yourname/expensetracker/data/database/DatabaseMigrationProofTest.kt))

## 11.1 Keep fast JVM preflight

Use JVM tests for:

- Version constants.
- Registry continuity.
- Snapshot presence.
- Simple direct-edge SQL validation.

Do not call this complete production migration proof.

## 11.2 Add schema hygiene guard

Verify all Room schema files:

- Strict UTF-8.
- No BOM.
- Valid JSON.
- Filename equals internal version.
- Correct database identity.
- No missing supported snapshot.
- No duplicate version.
- Current generated schema matches committed schema.

Generate current schema through KSP and require a clean diff.

Historical schema changes require explicit review and a semantic diff.

## 11.3 Add instrumented migration package

Create:

- `DatabaseMigrationEdgeTest`
- `DatabaseMigrationChainTest`
- `DatabaseProductionBuilderMigrationTest`
- `DatabaseFreshMigratedParityTest`
- `DatabaseUnsupportedVersionPolicyTest`
- `DatabaseMigrationAtomicityTest`

No assumptions or ignored tests.

## 11.4 Production-builder proof

For every supported start version:

1. Create old DB from snapshot.
2. Seed representative data.
3. Close helper DB.
4. Open same file through `AppDatabase.fileBuilder`.
5. Trigger real Room opening.
6. Query through real DAO.
7. Assert current `user_version`.
8. Verify seeded values and relationships.
9. Run integrity and foreign-key checks.

Do not manually pass migrations to this test.

## 11.5 Semantic parity

Create fresh DB through production Room builder.

Compare fresh and migrated schemas for:

- Tables.
- Columns and ordering.
- Types.
- Nullability.
- Defaults.
- Primary keys.
- Indexes and uniqueness.
- Foreign keys/actions.
- Triggers.
- Views.
- Room identity.
- User version.

## 11.6 Unsupported-version policy

Choose exactly one:

- Block and rescue without mutation, preferred.
- Explicit destructive fallback.

Test that exact outcome. Do not accept either behavior.

## 11.7 Migration failure atomicity

Inject a failing migration and prove:

- Version not advanced.
- Original data remains.
- No partial committed schema.
- Retry with valid migration succeeds.

## 11.8 Blocking CI

Run migration package on:

- API 26.
- API 34.

Create stable aggregator:

```text
Migration Proof
```

Cancelled, skipped, timed-out, missing-JUnit, or skipped-test lanes must fail.

## H6 acceptance

- Every supported edge executes.
- Every supported start reaches current.
- Production builder is tested.
- Data survives.
- Fresh/migrated parity passes.
- Unsupported behavior deterministic.
- Migration Proof blocks merging.

---

# 12. PR H7 — Final integration and acceptance

## Suggested title

`ci: enforce final guard migration and release acceptance gates`

This PR should contain no unrelated product refactoring.

## 12.1 Required stable checks

- Validate Workflow
- Static Guards
- Guard Integrity
- Unit Tests
- Lint & Check
- Migration Proof
- Release Verification

## 12.2 Update workflow actions

Resolve Node-runtime deprecation warnings by moving to currently supported official action versions and pinning security-sensitive actions according to repository policy.

## 12.3 Branch protection

Verify through GitHub settings/API:

- All seven checks required.
- Branch must be up to date.
- Pull request and review required.
- Administrators cannot bypass if that is project policy.
- CODEOWNERS covers workflows, guards, baselines, DB/migrations, release policy, and privacy/security.

Documentation alone is insufficient.

## 12.4 Final evidence

For one exact SHA:

1. Run complete workflow.
2. Rerun all required jobs without code changes.
3. Require two complete green executions.
4. Record run IDs and artifact checksums.
5. Confirm identical guard fingerprints.
6. Confirm no missing/skipped critical tests.
7. Confirm no stale baselines.
8. Confirm release and migration API lanes.

## 12.5 Documentation

Update only after successful Actions runs:

- CI baseline inventory.
- Guard framework.
- Developer quickstart.
- Local CI guide.
- Guard audit.
- Latest verification.
- Master issue tracker.

The final record must use actual outcomes, not “expected” results.

---

# 13. Per-PR agent report template

For every PR, the agent must provide:

```text
PR:
Base SHA:
Head SHA:

BEFORE
- Failed checks:
- Findings:
- Baseline entries:
- Exceptions:
- Tests failing:

ROOT CAUSE
- Technical cause:
- Why CI previously appeared or could appear green:

IMPLEMENTATION
- Files changed:
- Invariant introduced:
- Broad exemptions removed:
- Baselines pruned/migrated:

TESTS
- Focused tests:
- Full guard tests:
- Gradle tests:
- CI run:

AFTER
- New findings:
- Resolved findings:
- Stale entries:
- Infrastructure errors:
- Remaining debt:

VERDICT
- Ready for next PR: YES/NO
```

---

# 14. Final definition of done

The remaining corrective program is complete only when:

- Every guard test is discovered and executed.
- Ratchets consume structured findings.
- Fingerprints do not depend on line numbers.
- Baseline additions from feature PRs are blocked.
- Gradle and Static Guards enforce the same policy.
- DB ownership is exact and unresolved debt remains visible.
- PII logging is strict-zero.
- No inline Gradle source scanners remain.
- High-risk rules are parser/control-flow aware.
- Release Verification builds, inspects, signs, installs, and launches the real artifact.
- Migration Proof opens old databases through the production Room builder.
- Required checks are configured in branch protection.
- Two consecutive complete runs pass for the same SHA.
- No baseline increase, broad exception, ignored test, warning conversion, or skipped proof was used to obtain green CI.

**Required invariant:**

> Green CI must mean that every required guard ran successfully against its real inputs, existing debt did not grow or mutate, migrations executed through the shipping path, and the actual optimized release artifact passed security and runtime verification.