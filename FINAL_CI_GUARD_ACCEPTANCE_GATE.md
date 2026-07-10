# Final CI Guard Acceptance Gate

## Agent Execution and Sign-Off Specification

**Purpose:** Define the exact conditions under which the CI guardrail program may be declared complete and trustworthy.

**Applies after:** PR A through PR F.

**Core rule:**

> A missing check, missing artifact, skipped test, infrastructure error, unknown result, or unverified claim is a failure—not a pass.

This gate validates the CI guard system. It does not automatically close unrelated product defects unless their fixes and tests are included.

---

# 1. Final required invariant

The CI guard implementation is accepted only when:

> Every intended source, configuration, migration, artifact, and runtime surface was successfully read, parsed, analyzed, tested, and compared against exact policy; any violation, execution error, skipped proof, or policy weakening blocks merging.

A green result must never mean:

- The guard did not run.
- The guard crashed.
- A source file could not be read.
- Configuration failed to load.
- A test was skipped.
- A broad exemption hid the code.
- A baseline count was increased.
- A warning was ignored.
- A debug artifact was tested instead of release.
- Documentation predicted success without an actual successful run.

---

# 2. Verdict model

Only these verdicts are allowed.

## GREEN — Accepted

All mandatory gates passed for the exact target commit.

Existing debt is permitted only when:

- It is represented by an exact ratchet fingerprint.
- It existed at the protected base revision.
- No new finding was introduced.
- No old finding was replaced by a different finding.
- Every stale resolved entry was removed.

## RED — Failed

One or more code, architecture, privacy, security, migration, release, test, workflow, or policy violations exist.

## BLOCKED — Unverified

The agent cannot obtain required evidence because of:

- Missing credentials or repository permissions.
- Missing Git history.
- Unavailable emulator.
- Missing artifact.
- Missing branch-protection visibility.
- Tool failure.
- Timeout.
- Cancelled workflow.
- Malformed report.
- Any other infrastructure problem.

`BLOCKED` must be treated as **not mergeable**. It must never be converted into GREEN.

There is no “expected green,” “probably passes,” or “green except for unknowns” verdict.

---

# 3. Enforcement modes

## Strict

No findings are allowed.

A strict guard passes only when:

- The guard executes successfully.
- The entire declared scope is scanned.
- Current findings equal zero.

## Ratchet

Only exact pre-existing findings are tolerated.

A ratchet passes only when:

- `new findings = 0`
- `stale baseline entries = 0`
- `prohibited baseline additions = 0`
- `detector errors = 0`

A lower total count is insufficient if a different violation was introduced.

## Structural exception

A narrowly approved behavior that is intrinsically required, such as exact raw SQL inside a specific Room migration.

It must contain:

- Exact rule.
- Exact path.
- Exact class and symbol.
- Exact finding category.
- Technical reason.
- Compensating control.
- Owner.
- Linked issue or architectural decision.
- Evidence test.

It must suppress exactly one finding.

## Temporary debt exception

Allowed only when an exact finding cannot immediately be removed.

It must:

- Expire within the policy limit.
- Link to an open corrective issue.
- State its removal condition.
- Never use a wildcard.
- Never suppress an entire file or class.
- Never suppress guard execution, parsing, or infrastructure errors.

---

# 4. Mandatory required checks

The protected branch must require stable checks equivalent to:

1. **Validate Workflow**
2. **Static Guards**
3. **Guard Integrity**
4. **Unit Tests**
5. **Lint & Check**
6. **Migration Proof**
7. **Release Verification**

If jobs are internally sharded, each category must expose one stable aggregator check.

An aggregator must fail when any dependency is:

- Failed.
- Cancelled.
- Skipped unexpectedly.
- Timed out.
- Missing.
- Unable to produce required evidence.

General non-critical instrumented tests may remain separate, but migration and release runtime proof must be independently blocking.

---

# 5. Agent execution order

The agent must perform gates in this order:

1. Freeze target and base revisions.
2. Validate repository and workflow state.
3. Validate guard registry and policy.
4. Validate guard implementation contracts.
5. Run trusted-base guard protection.
6. Run the complete static guard suite.
7. Validate suppressions and ratchets.
8. Run guard unit, adversarial, and integration tests.
9. Run Gradle unit, architecture, lint, build, and check tasks.
10. Run blocking migration proof.
11. Build and inspect the actual release artifact.
12. Install and smoke-test bundle-generated release APKs.
13. Verify artifacts and reports.
14. Verify branch protection.
15. Run the complete candidate twice.
16. Produce the final acceptance report.

The agent may run focused checks while fixing failures, but final acceptance requires the complete sequence.

---

# 6. Gate FG-00 — Target revision and repository state

## The agent shall verify

- Exact target commit SHA.
- Exact protected base SHA.
- Pull request merge base.
- Branch name.
- Workflow run ID.
- Repository has sufficient Git history for base/head comparisons.
- Checkout is clean.
- No uncommitted generated changes exist.
- Submodules, if any, are at committed revisions.
- The target branch includes all intended PR A–F work.
- The target is up to date with the protected branch.

## Pass criteria

- All evidence refers to the same target SHA.
- Base/head guard comparisons use immutable SHAs.
- No evidence from an older commit is presented as evidence for the target.
- A cancelled run caused by a newer commit is not counted.

## Hard failures

- Short or ambiguous revision only.
- Dirty working tree.
- Shallow history prevents policy comparison.
- Reports produced from different commits.
- Target branch is behind when branch policy requires current base.
- Documentation says “latest” without an exact SHA.

## Required evidence

- Target SHA.
- Base SHA.
- Merge-base SHA.
- Clean-tree output.
- Workflow run URL or run ID.
- Trigger type.

---

# 7. Gate FG-01 — Workflow validity and integrity

## The agent shall verify

- All workflow YAML passes `actionlint`.
- Referenced actions exist.
- Security-sensitive actions are pinned according to repository policy.
- Workflow permissions are minimal.
- Pull-request workflows do not receive production signing or provider secrets.
- Concurrency cancellation cannot cause an old run to be accepted for a new SHA.
- Required jobs have explicit timeouts.
- Required jobs do not use `continue-on-error`.
- Required commands do not use `|| true`.
- Required guards do not run in warning or report mode.
- Artifact uploads use `if: always()`.
- Missing required artifacts cause failure.
- Required checks run for all relevant pull requests.

## Prohibited patterns

- `continue-on-error: true` on a required job or step.
- `command || true`.
- `set +e` without explicit collection and final failure propagation.
- Suppressing a command’s exit status.
- Running only on `main` after merge.
- Path filters that allow relevant changes to bypass proof.
- Step conditions that silently skip required checks.
- Unpinned executable downloads without checksum or signature validation.
- Production secrets exposed to forked pull requests.

## Pass criteria

- Workflow validation passes.
- Required checks execute on the target PR.
- Every required status has a definitive conclusion.
- The stable aggregators correctly reflect all child jobs.

## Evidence

- Actionlint report.
- Workflow permissions report.
- Required job conclusions.
- Aggregator dependency report.
- Artifact list.

---

# 8. Gate FG-02 — Canonical guard registry

## The agent shall verify

A canonical registry defines every guard with:

- Unique guard ID.
- Unique rule IDs.
- Owner.
- Description.
- Detector version.
- Engine and entry point.
- Source scope.
- Include/exclude rules.
- Configuration path.
- Allowlist path, if applicable.
- Backlog path and mode.
- Timeout.
- Test manifest.
- Output schema.
- Documentation anchor.

## Pass criteria

- Every active guard is registered exactly once.
- Every registry entry has an executable implementation.
- Every implementation has a registry entry.
- Every required guard appears in the canonical suite.
- Workflow YAML does not maintain an independent manual guard list.
- Documentation tables are generated from or validated against the registry.
- No duplicate Python, KTS, Gradle, or JVM scanner independently enforces a different version of the same rule.

## Hard failures

- Orphan guard.
- Orphan allowlist.
- Duplicate rule ID.
- Duplicate guard invocation.
- Missing detector version.
- Unregistered workflow guard.
- Guard implemented only in documentation.
- Legacy scanner disagrees with canonical scanner.
- Required guard missing from the suite.

## Evidence

- Guard inventory JSON.
- Registry validation report.
- Duplicate-rule report.
- Canonical guard list.

---

# 9. Gate FG-03 — Fail-closed detector contract

Every guard must use the same execution semantics.

## Required exit codes

- `0`: policy satisfied.
- `1`: policy violation.
- `2`: detector, parser, configuration, I/O, dependency, timeout, or infrastructure failure.

## The agent shall test

- Missing source directory.
- Missing required allowlist.
- Malformed YAML or JSON.
- Duplicate configuration key.
- Unsupported schema version.
- Unknown configuration field.
- Invalid UTF-8.
- Unreadable source file.
- Symlink escaping repository.
- Parser failure.
- Symbol-resolution failure.
- Missing external tool.
- Timeout.
- Output directory failure.
- Empty unexpected scan scope.
- Missing required sentinel package or file.

## Pass criteria

Each case returns exit code 2 and an actionable safe error.

A guard may continue scanning other files for diagnostic completeness, but the final status must remain an infrastructure error.

## Hard failures

- `except Exception: pass`.
- Returning no findings after a read error.
- Treating malformed configuration as an empty allowlist.
- Treating missing PyYAML or parser dependency as optional.
- Warning and continuing after required input disappears.
- Partial JSON output interpreted as success.
- Unknown exit codes.
- Parser failure converted to a normal pass.

---

# 10. Gate FG-04 — Deterministic structured output

## Every guard result must contain

- Schema version.
- Guard ID.
- Detector version.
- Mode.
- Status.
- Files and bytes scanned.
- Findings.
- Suppressed findings.
- Infrastructure errors.
- Duration.
- Output artifact paths.

## Every finding must contain

- Rule ID.
- Stable fingerprint.
- Severity.
- Repository-relative path.
- Start/end position.
- Containing symbol.
- Finding category.
- Safe operation signature.
- Remediation.
- Linked policy or issue.
- Suppression metadata, if applicable.

## Pass criteria

- Findings are deterministically sorted.
- Absolute runner paths are absent.
- Line numbers do not define identity.
- Formatting and comment changes do not alter fingerprints.
- Sensitive literals are not copied into reports.
- Reports are atomically written.
- JSON validates against the canonical schema.
- SARIF, if generated, is valid.

## Determinism proof

Run the same guard suite twice from clean checkouts.

After excluding explicit runtime metadata such as duration:

- Fingerprints must match.
- Ordering must match.
- Counts must match.
- Suppression matching must match.

---

# 11. Gate FG-05 — Complete static guard execution

## The agent shall verify

- Every guard runs even if an earlier guard fails.
- Every guard produces a log.
- Every guard produces structured output.
- Guard tests execute after detector execution.
- Final suite status is calculated after all guards finish.
- A blocking failure cannot be overwritten by a later success.
- An infrastructure failure always fails the suite.
- Expected files-scanned counts are nonzero and plausible.
- Required source roots and sentinel symbols were discovered.

## Pass criteria

The suite summary contains every registered guard exactly once.

For each guard:

- Mode is strict or ratchet.
- Exit status is valid.
- Result artifact exists.
- No infrastructure error exists.
- Findings satisfy policy.

## Hard failures

- Guard not reached due to an earlier failure.
- Missing log.
- Missing result JSON.
- Empty scan scope.
- Hardcoded documentation count used instead of detector output.
- Required guard running in warning/report mode.
- Suite exits zero while any required guard failed.

---

# 12. Gate FG-06 — Allowlist and suppression safety

## Every suppression must match

- One exact rule.
- One exact repository-relative path.
- One exact class/symbol.
- One exact finding category.
- One exact operation.
- One live finding.

## Required metadata

- Reason.
- Owner.
- Expiry for temporary debt.
- Linked issue.
- Evidence test.
- Classification: structural or temporary debt.
- Compensating control for structural exceptions.

## The agent shall verify

- No wildcard path or symbol.
- No filename-only match.
- No basename match.
- No bidirectional suffix match.
- No whole-file early return.
- No whole-class early return.
- No package-wide exemption.
- No permanent unresolved debt.
- No exemption for missing guard execution.
- No exemption for parser/configuration errors.
- No exception matches multiple findings.
- No finding matches multiple exceptions.
- No stale exception remains.
- No expired exception remains.
- Referenced path, symbol, issue, and test exist.

## Pass criteria

Every exception is finding-scoped and policy-compliant.

## Hard failures

- `symbol: "*"`
- `path: "*"`
- `permanent` used for unresolved debt.
- “Legacy,” “pre-existing,” or “reviewed” as the only reason.
- Exemption suppresses missing WorkerExecutionGuard.
- Exemption causes an entire Gradle file to be skipped.
- Exception remains after code is fixed.
- Exception is added only to make the current PR pass without a corrective issue.

---

# 13. Gate FG-07 — No-growth backlog ratchets

## Required comparison

For each ratcheted guard:

- `unchanged = current ∩ baseline`
- `new = current − baseline`
- `resolved = baseline − current`

## Pass criteria

- New findings: zero.
- Stale resolved entries: zero.
- Baseline additions: zero, except a separately proven detector-upgrade bootstrap.
- Detector errors: zero.
- Mode weakening: zero.
- Fingerprint replacement: zero.
- Baseline/current sets are exact.

## Required anti-bypass tests

1. Add one new finding: fail.
2. Fix one and add one: fail.
3. Lower total count but add a different finding: fail.
4. Fix a finding but leave its baseline entry: fail.
5. Remove resolved baseline entry: pass.
6. Add an entry in the same feature PR: fail.
7. Change ratchet to report: fail.
8. Delete the baseline while findings remain: fail.
9. Replace an old fingerprint with a new fingerprint: fail.
10. Add a finding to an already-baselined file: fail.

## Detector upgrades

When detector behavior changes:

- Detector version must increase.
- Old detector runs against base source.
- New detector runs against base source.
- New detector runs against head source.
- A migration report classifies preserved, resolved, base-historical, and head-introduced findings.
- Head-only findings must be fixed.
- One-to-one fingerprint remapping requires proof.

---

# 14. Gate FG-08 — Guard test quality

Every critical rule must include:

- Positive fixture.
- Negative fixture.
- At least three adversarial/bypass fixtures.
- Infrastructure-failure fixture.
- Real-repository integration test.
- Actual subprocess exit-code test.

## Required adversarial variations

- Multiline calls.
- Fully qualified calls.
- Import aliases.
- Typealiases.
- Nested classes/functions.
- Extension functions.
- Comments containing safe markers.
- Strings containing safe markers.
- Braces inside strings.
- Expression-bodied functions.
- Reordered annotations.
- CRLF and LF.
- UTF-8 BOM.
- Generic receivers.
- Local helper indirection.
- Dead or unreachable guard calls.
- Similar but unrelated API names.
- Duplicate filenames in separate packages.

## Seeded violation requirement

A seeded test must:

1. Create the violating source.
2. Execute the actual guard.
3. Assert exit code 1.
4. Parse actual JSON.
5. Assert expected rule, symbol, and category.
6. Fix the source.
7. Execute again.
8. Assert exit code 0.

Tests must not reproduce the detector’s regex or logic independently.

## Required quality gates

- Metamorphic tests pass.
- Mutation tests kill intentional detector weakenings.
- Linux and Windows path/configuration tests pass.
- Python guard core meets its configured branch-coverage threshold.
- Kotlin analyzer meets its configured branch-coverage threshold.
- Every exit code is covered.

---

# 15. Gate FG-09 — Parser-aware high-risk analysis

High-risk Kotlin guards must inspect syntax, symbols, and limited control flow rather than raw lines.

## Required parser behavior

- Comments and strings are not treated as executable code.
- Import aliases resolve correctly.
- Fully qualified symbols resolve correctly.
- Nested scopes are handled.
- Multiline calls are handled.
- Parse/type-resolution failures fail closed.
- Source is parsed once and shared among compatible rules.

## Conservative proof rule

When safety depends on ordering, the analyzer must prove it.

If it cannot prove safety, it must report a finding rather than assume safety.

Ordering-sensitive examples:

- Cancellation rethrow before fallback.
- Worker guard before DB work.
- Write barrier before mutation.
- Privacy preparation before request construction.
- Lifecycle transaction context before event insert.

---

# 16. Gate FG-10 — Cancellation guard

## The agent shall verify detection of

- Broad `catch(Exception)`.
- Broad `catch(Throwable)`.
- Unsafe `runCatching`.
- Unsafe `recover` and `recoverCatching`.
- Unsafe `onFailure`.
- Unsafe `getOrElse` or `fold`.
- Cancellation swallowed inside workers.
- Cancellation converted into success, fallback data, retry, or ordinary failure.

## Safe only when

- `CancellationException` is caught separately and rethrown, or
- The exact caught value is checked and rethrown before ordinary handling, or
- The canonical cancellation-safe helper processes that exact value.

## Hard failures

- Mentioning `CancellationException` elsewhere in the method.
- Rethrow after returning fallback.
- Outer WorkerExecutionGuard used to justify inner swallowed cancellation.
- Cancellation text in comments or strings counted as propagation.
- Alias-imported `runCatching` missed.
- Custom similarly named safe function falsely flagged.

## Acceptance

- Strict or exact ratchet passes.
- No new cancellation fingerprint.
- Critical lifecycle, backup, restore, worker, receipt, and transaction paths are not hidden by broad exceptions.

---

# 17. Gate FG-11 — Worker guard

## Worker discovery must include

- Direct `CoroutineWorker` subclasses.
- Fully qualified supertypes.
- Import aliases.
- Intermediate abstract base workers.
- Nested worker classes.
- Registry-defined and source-discovered workers.

## Every DB or sensitive worker must prove

- Canonical `WorkerExecutionGuard` invocation.
- Guard call is reachable.
- No protected DB/privacy work occurs before guard entry.
- No protected work occurs after guard scope.
- Correct work ID and attempt count are supplied.
- Correct blocked policy is used.
- Result derives from canonical result bridge.
- Cancellation propagates.
- Lease, barrier, permission/privacy checks, and run ledger share the intended execution scope.
- Worker registry/FQN inventory matches source discovery.

## Hard failures

- Guard merely imported.
- Guard called in an unused helper.
- Guard in dead code.
- DAO mutation outside guarded lambda.
- Worker class-wide exemption.
- DB worker allowed to omit guard.
- Maintenance-blocked worker returns success when policy requires retry.
- Terminal state can be written twice.
- Worker absent from registry.

---

# 18. Gate FG-12 — UI and DAO ownership

## The agent shall verify

No ViewModel, Compose screen, UI controller, or receiver directly:

- Injects a DAO.
- Imports a DAO for mutation.
- Calls `AppDatabase.someDao()`.
- Stores a DAO through a generic provider.
- Mutates Room entities through direct DAO paths.
- Performs raw DB operations.

## Required legal path

`UI → ViewModel → coordinator/use case/repository → legal lifecycle owner → DAO`

## Analyzer must detect

- Constructor injection.
- Field/property injection.
- Fully qualified DAO types.
- Import aliases.
- Typealiases.
- Local DAO variables.
- Direct database DAO chains.
- Generic provider wrappers.

## Acceptance

- UI DAO guard is strict zero.
- No production UI allowlist entry exists.
- Safe UI/domain models do not expose token-bearing or sensitive DB entities.
- Missing rows and maintenance blocks return typed outcomes.

---

# 19. Gate FG-13 — Database access and write barrier

## The mutation catalog must derive from

- `@Insert`
- `@Update`
- `@Delete`
- Mutating `@Query`
- Approved custom mutator declarations

## The analyzer shall verify

- Mutation belongs to an approved lifecycle owner.
- Write barrier dominates the mutation on the same control-flow path.
- Direct DAO chains are detected.
- Aliased receiver mutations are detected.
- Raw SQLite/file operations are restricted to exact structural owners.
- Debug-only mutation paths are actually absent from release or correctly gated.
- Maintenance/rescue operations prove ownership and exclusivity.

## Hard failures

- Barrier exists elsewhere in the file but not on the mutation path.
- New DAO method in a baseline class is silently accepted.
- Migration raw-SQL exception permits unrelated file operations.
- Backfill worker direct mutation bypasses lifecycle/worker guarantees.
- An allowlisted class gains a second unapproved mutation.

---

# 20. Gate FG-14 — Event writer and transaction ownership

## The agent shall verify

- Event entities are constructed only by exact approved writers.
- Event DAO inserts occur only through legal paths.
- State and required lifecycle events share a transaction context.
- Transaction context provenance is valid.
- Receipt, transaction, recurring, review, and operation events use their canonical writers.
- Restore/import replay uses a dedicated legal writer.

## Hard failures

- Filename-based writer authorization.
- Constructor approval also suppresses DAO insertion.
- Event construction in an unrelated method of an approved file.
- State mutation commits without its required event.
- Event commits while state mutation rolls back.
- Context is manually fabricated outside approved provenance.

---

# 21. Gate FG-15 — Receipt links, imports, and provenance

## Receipt links

The agent shall verify:

- Link, relink, and unlink route through ReceiptLinkService or exact canonical owner.
- `ScannedReceipt.expenseId` cannot be directly changed elsewhere.
- DAO link methods are ownership-restricted.
- Parent transaction rollback cannot leak link side effects.
- Migration exceptions identify exact migration SQL.

## Imports

The agent shall verify:

- Import mutations route through a top-level lifecycle coordinator.
- Write barrier is checked.
- Operation run/batch ownership exists.
- Provenance fields receive actual values.
- Category creation and expense creation cannot split on row failure.
- Cancellation propagates.
- Importers do not mutate DAOs directly.
- Allowlisted import owners remain checked for provenance, barrier, and cancellation.

## Provenance

The guard shall derive valid enums and keys from production declarations.

The agent shall verify:

- Required source fields are present on every write.
- Metadata does not contain forbidden raw values.
- Map, JSON, Bundle, serializer, and helper writes are analyzed.
- Hardcoded guard enum lists cannot drift from production.

---

# 22. Gate FG-16 — Privacy, PII, and cloud boundaries

## Privacy

The agent shall verify:

- Capture/read gates execute before sensitive payload extraction.
- Privacy is rechecked before queued payload decryption or replay.
- Privacy failures are fail-closed.
- Sensitive hashing uses approved typed external identifiers where required.
- Raw audit metadata cannot bypass typed policy.
- Privacy detector read/parser errors fail closed.

## PII logging

Production code must not contain:

- `printStackTrace`.
- Direct Android `Log`.
- Direct unsafe Timber calls.
- `println`, `System.out`, or `System.err`.
- Raw Throwable logging.
- Raw `Throwable.message`.
- OCR, receipt, email, bank, merchant, item, token, account, file, URL, or path values in logs/errors.
- Raw provider messages surfaced to UI.

A `BuildConfig.DEBUG` condition inside `src/main` is not a general privacy exemption.

## Cloud

The agent shall verify:

- Cloud capability is checked.
- Privacy policy prepares a typed `PreparedCloudPayload`.
- Request bodies derive from the prepared type or approved factory.
- Direct `RequestBody`, multipart, form, raw file, or serializer paths are rejected.
- Receipt upload accepts only managed asset IDs or approved URIs.
- MIME, ownership, size, and privacy are verified.
- Revoked privacy prevents upload.
- Cloud-disabled paths return typed failure.

## Acceptance

- PII guard is strict zero.
- Cloud guard is strict zero.
- No raw-sensitive logging allowlist is introduced.
- Sentinel PII tests prove values do not enter logs, UI, diagnostics, exceptions, or persistence.

---

# 23. Gate FG-17 — Money and time correctness guards

## Money

The agent shall verify detection of:

- Raw cross-currency sums.
- Raw `Double` financial arithmetic.
- Dashboard calculations using non-normalized fields.
- Unsafe budget/spending subtraction.
- Deprecated formatter usage.
- Hardcoded currency fallback.
- Intermediate accumulator bypasses.
- Aggregations with named lambda parameters.
- Multiline operations.

Preferred policy:

- Money guard operates strict zero for release-critical rules.
- Inputs are normalized and typed before computation.
- `MoneyTest` is active and release-critical.

## Time

The agent shall verify:

- Platform time calls are restricted to exact TimeProvider implementations.
- Fully qualified and alias-imported calls are detected.
- `System.currentTimeMillis`, `Date()`, and direct `Instant.now()` cannot appear in domain paths.
- A random `now()` call does not create a false exemption.
- Calendar/date logic uses declared timezone policy where required.

---

# 24. Gate FG-18 — DI and release source boundaries

## The agent shall verify

- Release bindings use approved production implementations.
- Debug, demo, fake, mock, stub, preview, and test implementations are absent or release-disabled by typed policy.
- No whole-file Gradle exemption exists.
- Release is non-debuggable.
- Minification and resource shrinking are enabled.
- Test modules are absent from release classpath.
- Disabled features cannot return fake success.
- Release properties come from evaluated build output, not fragile brace-counting regexes.

## Hard failures

- `app/build.gradle.kts` skipped by an exemption.
- Demo bank connector packaged in release.
- Debug logger binding selected.
- No-op implementation reports successful execution.
- Release checks prove only debug configuration.
- Minification exception remains after PR E.

---

# 25. Gate FG-19 — Ignored and critical tests

## The agent shall verify

- Ignore annotations are parser-detected, not line-counted.
- Comments and strings do not affect counts.
- Every ignored test has a valid reason.
- Release-critical classes cannot contain ignored tests.
- Critical-test policy comes from one canonical source.
- `MoneyTest` remains critical.
- Migration, worker, import/export, receipt-matching, recurring, and transaction lifecycle critical suites remain active.
- No test was renamed or moved to evade denylist matching.
- No critical test is skipped at runtime.

## Hard failures

- Increasing ignored-test budget.
- Removing a critical class from policy.
- Adding `@Ignore` to obtain green CI.
- Missing expected test class.
- JUnit reports show skips in a critical suite.
- Test filters omit a critical class.

---

# 26. Gate FG-20 — Gradle, unit tests, lint, and check

## Required proof

- Debug compilation succeeds.
- Debug unit tests succeed.
- Architecture/contract tests succeed.
- Room snapshot verification succeeds.
- Ignored-test verification succeeds.
- Currency guardrails succeed.
- `lintDebug` succeeds.
- `assembleDebug` succeeds.
- `:app:check` succeeds.
- Release-specific tests required by PR E succeed.

## Test-result requirements

- JUnit XML exists.
- Required test classes executed.
- No critical skip exists.
- No timeout occurred.
- No test exclusion was added to obtain green.
- Test count is plausible and validated.
- Reports upload even when another step fails.

## Timeout policy

- Diagnose hangs before increasing timeouts.
- Final observed runtime should remain below approximately 70–80% of the configured timeout.
- Emulator boot may have an infrastructure retry if explicitly distinguished.
- Failed migration, release, or architecture assertions must not be retried until green.

---

# 27. Gate FG-21 — Blocking migration proof

## Static proof

The agent shall verify:

- One canonical current schema version.
- One canonical minimum supported version.
- Contiguous supported migration registry.
- Every migration definition is registered.
- Every registry entry has a definition.
- Every supported schema snapshot exists.
- Production builder uses the canonical registry.
- Blocking migration tests contain no assumptions or ignores.

## Executable proof

For every supported start version:

- Create old schema.
- Seed representative synthetic data.
- Execute migration.
- Open using the production Room builder.
- Verify current user version.
- Verify representative data.
- Verify DAO access.
- Run integrity check.
- Run foreign-key check.

## Schema parity

Fresh and migrated databases must match semantically for:

- Tables.
- Columns.
- Types.
- Nullability.
- Defaults.
- Primary keys.
- Indexes.
- Uniqueness.
- Foreign keys and actions.
- Triggers.
- Views.
- Room identity.
- User version.

## Unsupported versions

Exactly one product policy must be tested:

- Block and rescue without mutation, or
- Explicit destructive fallback.

“Data may be preserved or destroyed” is not acceptable.

## CI requirements

- Migration Proof runs on every PR.
- Required emulator lanes pass.
- No `continue-on-error`.
- No skipped migration tests.
- Missing JUnit output fails.
- Production builder, not only manually supplied migrations, is tested.

---

# 28. Gate FG-22 — Actual release verification

## Build proof

- `lintRelease` passes.
- Release unit tests pass.
- Release APK builds.
- Release AAB builds.
- R8/minification succeeds.
- Resource shrinking succeeds.
- Required R8 reports exist.
- Dependency verification succeeds.

## Artifact proof

Inspect the actual AAB and AAB-generated APKs for:

- `debuggable=false`.
- `testOnly=false`.
- `allowBackup=false`.
- Cleartext disabled.
- Exact exported components.
- Exact component permissions.
- Exact permission policy.
- No debug/demo/test classes.
- No unsafe logging implementation.
- No HTTP body logger.
- No secret candidate.
- No insecure endpoint.
- No unsafe FileProvider path.
- No unexpected native library.
- Correct signing certificate policy.

## Runtime proof

Install bundle-generated APKs on required API lanes and prove:

- Installation succeeds.
- App launches.
- Hilt initializes.
- Room opens.
- WorkManager initializes.
- No fatal exception.
- No ANR.
- Safe deep links do not crash.
- Invalid deep links fail safely.
- Release-disabled features do not fake success.
- No demo data is inserted.
- No request is unexpectedly sent at startup.

## Signing

Pull requests use ephemeral non-debug verification signing.

Production signing material must:

- Remain in protected environments.
- Never be exposed to pull-request workflows.
- Never be uploaded as an artifact.
- Never appear in logs.

---

# 29. Gate FG-23 — Guard self-protection

## The agent shall run

1. Protected-base guard engine against PR-head application source.
2. PR-head guard engine against PR-head source.
3. Guard policy-delta verifier.
4. PR-head guard contract/adversarial corpus.

## Pass criteria

- Base guards find no head-introduced violation.
- Head guards pass their own contracts.
- Required modes are not weakened.
- Rule IDs are not silently removed or renamed.
- Detector changes include version and migration metadata.
- Allowlists/backlogs are not broadened.
- Workflow does not skip canonical execution.
- Required artifacts remain present.

## Hard failures

- PR weakens its own guard to permit its code.
- Existing detector deleted without replacement.
- Guard removed from registry.
- Fixture deleted to hide regression.
- Workflow required-check name changed without branch-policy migration.
- New `|| true`, warning mode, or swallowed exception introduced.
- Trusted-base scan is omitted.

---

# 30. Gate FG-24 — CI artifacts and observability

## Required artifacts

At minimum:

- Workflow validation report.
- Guard inventory.
- Guard registry report.
- Per-guard logs.
- Per-guard JSON.
- Static suite summary.
- Ratchet comparisons.
- Suppression report.
- Guard test reports.
- Unit-test reports.
- Lint reports.
- Migration reports and schema diffs.
- Release manifest report.
- Release class/resource/secret reports.
- Signing report.
- Release smoke report.
- Final acceptance report.

## Pass criteria

- Artifacts exist on success and failure.
- Artifact upload uses always-run behavior.
- Missing expected artifacts fail the relevant aggregator.
- Reports contain target SHA and detector versions.
- Reports do not expose sensitive source literals, credentials, tokens, DB contents, or private signing material.
- Artifact checksums are recorded where relevant.
- Logs are deterministic and actionable.

---

# 31. Gate FG-25 — Branch protection

Documentation alone is not proof that branch protection is configured.

## The agent shall verify through platform settings or API evidence

- Pull request required.
- Required approving review count.
- Required status checks.
- Branch must be current before merge.
- Conversation resolution policy.
- Administrator bypass disabled where intended.
- Force-push and deletion policy.
- CODEOWNERS coverage for:
  - Workflows.
  - Guard engine.
  - Registry.
  - Allowlists.
  - Backlogs.
  - Migration policy.
  - Release policy.
  - Privacy/security paths.

## Required checks

The actual branch rule must require the stable aggregators listed in Section 4.

## If the agent lacks permission

Verdict is `BLOCKED`, not GREEN.

Required evidence may be:

- GitHub API response.
- Administrator-exported configuration.
- Auditable screenshot plus repository/rule identity.
- Platform settings report.

---

# 32. Gate FG-26 — Documentation and issue traceability

## The agent shall verify

- Guard framework documentation matches implementation.
- Local commands match CI commands.
- Registry is the source of guard inventory.
- Counts are generated, not manually copied across documents.
- Warning-mode language is removed for ratcheted guards.
- Issue mappings are domain-specific.
- Resolved issues include implementation SHA and evidence.
- Partial debt remains explicitly partial.
- Unsupported scope is documented.
- No document claims release readiness beyond tested scope.

## Actual verification record must contain

- Exact target SHA.
- Base SHA.
- Workflow run ID.
- Trigger.
- Job conclusions and durations.
- Guard modes and versions.
- Finding counts.
- New/resolved counts.
- Test counts and skipped counts.
- Migration API lanes.
- Release API lanes.
- Artifact names/checksums.
- Explicit exclusions.
- Branch-protection evidence.

Words such as “expected,” “should pass,” and “locally verified” cannot replace actual CI evidence.

---

# 33. Gate FG-27 — Reproducibility and stability

## The agent shall obtain

Two consecutive complete green executions for the same candidate SHA, or one execution plus an explicit workflow rerun of all required jobs for that SHA.

## Pass criteria

- Same guard fingerprints.
- Same enforcement outcomes.
- Same required test classes.
- No flaky retry of a failed assertion.
- No timeout.
- No missing report.
- No job exceeds its configured timeout margin without explanation.
- Release artifacts are reproducible to the level defined by build policy, with stable dependency inputs and recorded checksums.

A green run for an older SHA followed by unverified changes is not valid.

---

# 34. Prohibited completion techniques

The agent must reject any implementation that obtains green CI by:

- Adding `|| true`.
- Adding `continue-on-error`.
- Switching a required guard to warning/report mode.
- Increasing ignored-test thresholds.
- Adding `@Ignore`.
- Removing a critical test from policy.
- Adding a broad allowlist entry.
- Adding a whole-file or whole-class exemption.
- Marking unresolved debt permanent.
- Increasing a backlog.
- Replacing an old baseline finding with a new one.
- Deleting a fixture or test.
- Skipping unreadable files.
- Swallowing parser/configuration errors.
- Adding a lint baseline to hide new errors.
- Globally disabling a lint detector.
- Adding broad R8 keep or `dontwarn` rules.
- Testing debug instead of release.
- Testing a manually configured DB builder instead of production builder.
- Accepting skipped migration tests.
- Accepting missing artifacts.
- Raising a timeout without diagnosing a hang.
- Presenting local output as GitHub branch-protection evidence.
- Recording “expected success” as actual verification.

---

# 35. Failure remediation procedure

When a gate fails, the agent shall:

1. Identify the exact failed gate and rule.
2. Classify the failure:
   - Product-code violation.
   - Guard false positive.
   - Guard false negative.
   - Infrastructure/configuration error.
   - Test failure.
   - Migration failure.
   - Release-artifact failure.
   - Branch-policy failure.
3. Preserve failure artifacts.
4. Fix the root cause.
5. Add or update a regression test.
6. Run the narrow affected check.
7. Run the complete guard suite.
8. Run all required final jobs.
9. Remove stale baseline or exception entries.
10. Regenerate evidence.

If a guard is wrong, fix the detector and prove the correction with positive, negative, and adversarial fixtures. Do not simply allowlist the finding.

---

# 36. Canonical validation sequence

Exact wrapper names may follow the final repository registry, but equivalent checks must exist.

```bash
# Workflow
actionlint

# Guard registry and contract
python3 scripts/ci/verify_guard_registry.py --root .
python3 scripts/ci/verify_guard_contracts.py --root .

# Complete canonical guard suite
python3 scripts/ci/run_static_guard_suite.py \
  --mode ci \
  --output-dir build/ci/static-guards

# Trusted-base integrity
python3 scripts/ci/verify_guard_integrity.py \
  --base-ref "$BASE_SHA" \
  --head-ref "$HEAD_SHA"

# Guard tests
python3 -m pytest scripts/test_*.py -v

# Debug JVM and Gradle verification
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
./gradlew :app:verifyRoomSchemaSnapshots \
  -PstrictRoomSchemas=true \
  --no-daemon \
  --stacktrace
./gradlew :app:verifyNoIgnoredGrowth \
  --no-daemon \
  --stacktrace
./gradlew :app:lintDebug :app:assembleDebug :app:check \
  --no-daemon \
  --stacktrace

# Migration proof
python3 scripts/verify_migration_matrix.py --fail-on-violation
python3 scripts/verify_migration_proof_suite.py --fail-on-violation
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.yourname.expensetracker.data.database.migrationproof \
  --no-daemon \
  --stacktrace

# Actual release build and verification
./gradlew :app:lintRelease :app:testReleaseUnitTest \
  :app:assembleRelease :app:bundleRelease \
  --no-daemon \
  --stacktrace
python3 scripts/release/verify_release_artifacts.py \
  --policy config/release-verification-policy.yml
```

If a required canonical command is absent, the implementation is incomplete.

---

# 37. Final agent acceptance checklist

## Repository and workflow

- [ ] Exact target/base SHAs recorded.
- [ ] Working tree clean.
- [ ] Actionlint passes.
- [ ] Required jobs run on the PR.
- [ ] No required job uses `continue-on-error`.
- [ ] No required command uses `|| true`.
- [ ] Workflow permissions are minimal.
- [ ] Required actions satisfy pinning policy.

## Guard framework

- [ ] Canonical registry exists.
- [ ] Every guard registered once.
- [ ] Rule IDs unique.
- [ ] Exit codes standardized.
- [ ] Missing/malformed input exits 2.
- [ ] Structured output validates.
- [ ] Outputs deterministic.
- [ ] No duplicate scanner disagrees with canonical implementation.

## Static guard enforcement

- [ ] Every registered guard executed.
- [ ] Every guard produced logs and JSON.
- [ ] No infrastructure errors.
- [ ] Strict guards have zero findings.
- [ ] Ratchet guards have zero new findings.
- [ ] Stale baseline entries removed.
- [ ] Baseline additions rejected.
- [ ] No warning/report mode in required CI.

## Exceptions

- [ ] No file/class/package exemption.
- [ ] Every exception matches one finding.
- [ ] Every temporary exception has expiry and issue.
- [ ] No expired/stale entry.
- [ ] Structural exceptions have compensating tests.
- [ ] No permanent unresolved debt.

## Guard testing

- [ ] Positive fixtures pass.
- [ ] Negative fixtures fail.
- [ ] Adversarial fixtures fail correctly.
- [ ] Infrastructure fixtures exit 2.
- [ ] Seeded tests execute real guards.
- [ ] Metamorphic tests pass.
- [ ] Mutation tests pass.
- [ ] Linux and Windows tests pass.
- [ ] Coverage policy passes.

## Architecture rules

- [ ] Cancellation ratchet/strict policy passes.
- [ ] Worker guard passes.
- [ ] UI DAO strict-zero passes.
- [ ] DB/write-barrier ratchet passes.
- [ ] Event-writer ratchet passes.
- [ ] Receipt-link guard passes.
- [ ] Import lifecycle guard passes.
- [ ] Provenance guard passes.
- [ ] Privacy guard passes.
- [ ] PII logging strict-zero passes.
- [ ] Cloud payload strict-zero passes.
- [ ] Money guard passes.
- [ ] Time guard passes.
- [ ] DI/release source guard passes.
- [ ] Ignored-test policy passes.

## Gradle and tests

- [ ] Unit tests pass.
- [ ] Architecture tests pass.
- [ ] No critical tests skipped.
- [ ] Room snapshot verification passes.
- [ ] Ignored-test verification passes.
- [ ] Currency guardrails pass.
- [ ] Lint passes.
- [ ] Debug assembly passes.
- [ ] `:app:check` passes.
- [ ] Required reports exist.

## Migration

- [ ] Static migration registry passes.
- [ ] Every supported edge executes.
- [ ] Every supported start reaches current.
- [ ] Production builder is tested.
- [ ] Representative data survives.
- [ ] Integrity/FK checks pass.
- [ ] Fresh/migrated parity passes.
- [ ] Unsupported-version policy is deterministic.
- [ ] No skipped migration tests.
- [ ] Migration Proof is blocking.

## Release

- [ ] Release lint and tests pass.
- [ ] R8 enabled.
- [ ] Resource shrinking enabled.
- [ ] APK and AAB produced.
- [ ] AAB validates.
- [ ] AAB-generated APKs validate.
- [ ] Signing policy passes.
- [ ] Manifest policy passes.
- [ ] Cleartext disabled.
- [ ] Exported components exact.
- [ ] Forbidden classes absent.
- [ ] Unsafe logging absent.
- [ ] Secrets scan clean.
- [ ] Insecure endpoint scan clean.
- [ ] Release DI contract passes.
- [ ] Minimum and modern API smoke tests pass.
- [ ] Hilt, Room, and WorkManager initialize.
- [ ] No fatal exception or ANR.
- [ ] Release Verification is blocking.

## Self-protection and branch policy

- [ ] Protected-base guard scans head source.
- [ ] Head guard suite passes.
- [ ] Policy delta passes.
- [ ] Detector migrations validated.
- [ ] Guard changes received required ownership review.
- [ ] Branch protection requires all stable checks.
- [ ] Admin bypass policy verified.

## Evidence

- [ ] Required artifacts exist.
- [ ] Artifacts identify exact SHA.
- [ ] Sensitive values are redacted.
- [ ] Two consecutive complete runs pass.
- [ ] Final report contains no “expected” claims.
- [ ] Remaining debt is exact, unchanged, and issue-linked.

---

# 38. Final acceptance report template

```text
FINAL CI GUARD ACCEPTANCE REPORT

Verdict: GREEN | RED | BLOCKED

Target SHA:
Base SHA:
Merge-base SHA:
Branch:
Pull request:
Workflow run IDs:
Verification date:

REQUIRED JOBS
- Validate Workflow:
- Static Guards:
- Guard Integrity:
- Unit Tests:
- Lint & Check:
- Migration Proof:
- Release Verification:

GUARD SUMMARY
- Registered guards:
- Executed guards:
- Strict findings:
- Ratcheted findings:
- New findings:
- Resolved findings:
- Stale baseline entries:
- Active structural exceptions:
- Active temporary exceptions:
- Infrastructure errors:

TEST SUMMARY
- Unit tests:
- Architecture tests:
- Guard tests:
- Skipped critical tests:
- Failed tests:
- Timed-out tests:

MIGRATION SUMMARY
- Minimum supported version:
- Current version:
- Tested start versions:
- API lanes:
- Fresh/migrated parity:
- Integrity check:
- Foreign-key check:
- Unsupported-version policy:

RELEASE SUMMARY
- Release APK:
- Release AAB:
- Minified:
- Resource shrunk:
- Signing certificate fingerprint:
- Manifest policy:
- Secret scan:
- Endpoint scan:
- Forbidden-class scan:
- Runtime API lanes:
- Startup result:

BRANCH PROTECTION
- Required checks verified:
- Up-to-date requirement:
- Bypass policy:
- CODEOWNERS coverage:

ARTIFACTS
- Static guard summary:
- Ratchet comparison:
- Unit-test reports:
- Lint reports:
- Migration reports:
- Release reports:
- Final provenance report:

UNRESOLVED DEBT
- Finding:
- Fingerprint:
- Issue:
- Owner:
- Removal target:

FINAL DECISION
- Merge permitted: YES | NO
- Blocking gates:
- Required remediation:
```

---

# 39. Final definition of done

The CI guardrail program is complete only when:

- Every required check runs on every protected-branch pull request.
- A green guard result proves successful reading, parsing, analysis, and policy comparison.
- Guard failures cannot be hidden through warning mode or execution suppression.
- Every exception is narrower than the safety invariant it preserves.
- Existing architecture debt can only remain identical or shrink.
- New violations cannot be added to a baseline by the introducing PR.
- Critical Kotlin rules use parser and control-flow context.
- Guard tests execute the real detectors and cover bypass techniques.
- The protected-base engine prevents a PR from weakening its own checks.
- Full Gradle verification passes.
- Room migrations execute and block merging on failure.
- The actual optimized release artifact is built, inspected, installed, and smoke-tested.
- Required branch-protection settings are verified, not merely documented.
- All evidence refers to the exact target SHA.
- Two consecutive complete executions pass.
- No required proof is missing, skipped, ignored, warning-only, or inferred.

## Final invariant

> **No code may merge unless CI has conclusively demonstrated that the guard system itself is intact, all required scopes were analyzed, no guarded debt grew, migrations are executable, and the actual release artifact satisfies its defined security and runtime contracts.**