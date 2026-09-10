# PR F — Harden Guard Implementation

## 1. PR definition

**Suggested title:**  
`ci(guards): make architecture guards fail-closed, parser-aware, and self-protecting`

**Base:** Successful final commit of PR E.

**Reference implementation inspected:**  
`ebb5aa93348282b31c1c669d1bf1271d584b9eb0`

**Primary issues:**

- MIT-003 — Architecture guard completeness
- MIT-005 — Critical-test enforcement
- MIT-016 — Worker guard enforcement
- MIT-022 — Cloud fail-closed enforcement
- MIT-026 — PII/logging enforcement
- MIT-030 — Write-barrier enforcement
- MIT-031 — Event-writer ownership
- MIT-034 — Cancellation propagation
- MIT-036 — DAO ownership
- MIT-040 — Receipt-link ownership
- MIT-047 — Import lifecycle ownership
- MIT-050 — Money correctness
- MIT-060 — UI DAO boundaries

**Estimated effort:** 10–16 engineering days.

**Risk:** S0. A false-green architecture guard can permit privacy, database, money, cancellation, or release regressions despite apparently green CI.

---

# 2. Verified starting weaknesses

The reference guard template permits missing allowlists, missing PyYAML, and malformed YAML to become empty allowlists; it also performs permissive bidirectional suffix path matching. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/guard_template.py))

Several guards repeat the same failure modes:

- UI and worker guards silently swallow YAML-loading errors and source-read errors.
- UI and worker allowlists skip entire files/classes.
- The DI guard can skip an entire production or Gradle file.
- Receipt and import guards treat malformed or missing allowlists as warnings.
- Event-writer ownership is matched by filename or substring.
- DB access uses a hand-written YAML parser and silently continues when source files are unreadable. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_ui_dao_boundaries.py))

The cloud guard contains a concrete filtering defect: both the allowlisted and non-allowlisted branches append the violation, so the intended exemption behavior is not implemented correctly. It also silently accepts missing YAML dependencies and unreadable files. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_cloud_payload_boundaries.py))

Privacy, money, and provenance scanners also contain read-error paths that return no findings instead of reporting an infrastructure failure. Some coverage lists, such as expected enum values and approved source files, are duplicated manually rather than derived from production declarations. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/scripts/verify_source_provenance_boundaries.py))

Guard logic currently exists in four partially overlapping systems:

1. Python scripts.
2. Standalone Kotlin `.kts` scripts.
3. Inline Gradle source scanners.
4. JVM architecture tests with their own regexes and allowlists.

Some Gradle tasks warn and return when a required guard script is missing. Several worker rules and allowlists are duplicated between Python and JVM tests. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/test/java/com/yourname/expensetracker/architecture/SourceScanningArchitectureGuardTest.kt))

The existing seeded-violation test generally checks that a script exists and that a separately recreated regex matches a temporary string; it does not execute the real guard and verify its exit status. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/test/java/com/yourname/expensetracker/guards/GuardSeededViolationTest.kt))

PR F must eliminate these false-green paths rather than merely add more regexes.

---

# 3. Objective

After PR F:

1. Every guard follows one executable contract.
2. Missing, unreadable, malformed, or unparseable input fails with exit code 2.
3. No guard silently converts execution errors into zero findings.
4. Kotlin rules inspect syntax rather than unstructured source lines.
5. High-risk rules use symbol and control-flow context where necessary.
6. Comments, string literals, imports, aliases, multiline calls, and nested scopes are handled correctly.
7. Every guard has positive, negative, adversarial, infrastructure-failure, and real-repository tests.
8. Every seeded test executes the actual detector.
9. Every guard is registered exactly once.
10. CI, Gradle, documentation, allowlists, backlogs, and tests derive from the same registry.
11. Duplicate KTS, inline Gradle, and JVM regex scanners are removed or converted to thin adapters.
12. Changes to guard implementation cannot silently weaken CI.
13. Detector upgrades cannot reset or inflate the PR C backlogs.
14. Findings and errors use deterministic JSON and SARIF formats.
15. The guard suite remains fast enough for required PR execution.

---

# 4. Non-goals

Do not include:

- Burn-down of all remaining architecture debt.
- New application features.
- Migration or release policy redesign already handled by PRs D and E.
- A complete Kotlin compiler or data-flow framework.
- Network-dependent guard execution.
- A baseline that tolerates guard crashes or parse failures.
- Whole-package or whole-file exemptions.
- Replacing strict checks with advisory output.

Do not:

- Add `except Exception: pass`.
- Use `errors="replace"` for production source.
- Return an empty finding list after an I/O failure.
- Treat missing dependencies as optional.
- use filename substrings as architectural authorization.
- retain independent copies of the same allowlist.
- add `|| true`.
- permit required CI to use report/warning mode.
- update every backlog fingerprint merely because the detector implementation changed.

---

# 5. Target guard architecture

## 5.1 Three detector engines

| Engine | Responsibility |
|---|---|
| Python guard core | Registry, configuration, paths, allowlists, backlog comparison, reporting, artifact/schema validation |
| Kotlin source analyzer | Parser-aware Kotlin architecture rules, symbol indexing, limited control-flow analysis |
| Specialized verifiers | Release artifacts, migration results, JUnit XML, Room schemas and other structured formats |

Regex-only source scanning should remain only for genuinely lexical rules that do not require understanding Kotlin scope.

## 5.2 New project structure

Create:

- `config/guards/registry.yml`
- `config/guards/schemas/guard-registry.schema.json`
- `config/guards/schemas/allowlist.schema.json`
- `config/guards/schemas/finding.schema.json`
- `config/guards/schemas/result.schema.json`
- `scripts/guardlib/`
- `scripts/guardlib/model.py`
- `scripts/guardlib/errors.py`
- `scripts/guardlib/config.py`
- `scripts/guardlib/paths.py`
- `scripts/guardlib/runner.py`
- `scripts/guardlib/reporters.py`
- `scripts/guardlib/registry.py`
- `scripts/guardlib/backlog_adapter.py`
- `scripts/ci/verify_guard_registry.py`
- `scripts/ci/verify_guard_integrity.py`
- `scripts/ci/verify_guard_contracts.py`
- `tools/guard-engine/` — standalone Kotlin/JVM module
- `scripts/fixtures/guards/`

## 5.3 Canonical registry entry

Every guard must declare:

- Guard ID and unique rule IDs.
- Description.
- Owner.
- Detector version.
- Engine.
- Entrypoint.
- Source scopes.
- Include and exclude patterns.
- Required configuration.
- Allowlist path, if applicable.
- Backlog path and enforcement mode.
- Timeout.
- Expected output schema.
- Test manifest.
- Documentation anchor.
- Whether SARIF publication is permitted.
- Whether source snippets must be redacted.

No guard may exist only in workflow YAML or Gradle.

---

# 6. Workstream F1 — Inventory and freeze the current system

## 6.1 Produce a complete inventory

Create `build/ci/guard-inventory.json` containing:

- Every `verify_*.py`.
- Every guard-related `.kts`.
- Every Gradle verification task.
- Every JVM architecture/source-scanning test.
- Every allowlist and backlog.
- Every workflow invocation.
- Every rule ID.
- Every duplicated rule family.

## 6.2 Classify each implementation

Assign each implementation one disposition:

- `canonical`
- `compatibility_wrapper`
- `behavioral_test`
- `duplicate_remove`
- `superseded`
- `specialized_verifier`

Examples:

- `WorkerExecutionGuardTest`: retain as behavioral coverage.
- Worker source regex tests: replace with canonical analyzer integration tests.
- `check_direct_time_calls.kts`: remove after Kotlin analyzer parity.
- Inline `checkLifecycleBypass`: remove after DB/DAO analyzer parity.
- Python entry scripts: retain temporarily as thin CLI wrappers.

## 6.3 Capture a reproducible snapshot

From the final PR E SHA:

1. Execute every existing guard twice from clean checkouts.
2. Store raw output and exit status.
3. Record scanned file counts.
4. Identify nondeterministic ordering.
5. Identify disagreements between duplicate implementations.
6. Resolve disagreements before retiring either implementation.

## Acceptance criteria

- Every guard-like implementation has a documented disposition.
- No unknown guard invocation remains.
- Duplicate rules have a named canonical successor.
- The initial snapshot is deterministic or explicitly documents existing nondeterminism.

---

# 7. Workstream F2 — Implement the canonical guard contract

## 7.1 Result model

Every execution must produce a structured result containing:

- Schema version.
- Guard ID.
- Detector version.
- Enforcement mode.
- Status: `pass`, `violation`, or `error`.
- Start/end timestamps.
- Duration.
- Repository-relative root.
- Files and bytes scanned.
- Findings.
- Suppressed findings.
- Infrastructure errors.
- Output artifacts.

Each finding must contain:

- Rule ID.
- Stable fingerprint.
- Severity.
- Exact relative path.
- Start/end line and column.
- Containing symbol.
- Finding kind.
- Safe operation signature.
- Remediation.
- Linked architectural policy.
- Suppression metadata, if suppressed.

## 7.2 Exit codes

| Code | Meaning |
|---:|---|
| 0 | Policy satisfied |
| 1 | One or more policy violations |
| 2 | Detector, configuration, parser, input, or infrastructure failure |

No other exit code is valid. A terminated process, timeout, or signal is normalized to exit code 2 by the suite runner.

## 7.3 Standard CLI

All wrappers must support:

- Project root.
- Guard mode.
- Output format.
- Output file.
- Registry path.
- Configuration path.
- Allowlist path.
- Backlog path.
- Base revision.
- Diagnostic verbosity.

Keep `--fail-on-violation` temporarily as a compatibility alias for strict/ratchet enforcement, but remove it from CI and documentation.

## 7.4 Atomic output

Write reports to a temporary file, flush, then atomically rename.

A crashed guard must not leave a partial JSON document that downstream jobs interpret as success.

## 7.5 Error taxonomy

Create typed errors for:

- Missing input.
- Invalid root.
- Invalid configuration.
- Unsupported schema version.
- Source read failure.
- Kotlin parse failure.
- Symbol-resolution failure.
- Duplicate finding.
- Allowlist failure.
- Backlog mismatch.
- Timeout.
- Tool/dependency failure.
- Output-write failure.

Reports should include relative paths and safe error categories, not arbitrary source content.

---

# 8. Workstream F3 — Make configuration and source traversal fail closed

## 8.1 Strict configuration loading

Replace each custom YAML parser with one shared loader.

Requirements:

- PyYAML or the selected YAML library is pinned and mandatory.
- Use safe loading.
- Reject duplicate keys.
- Reject unknown fields.
- Validate with JSON Schema.
- Reject empty documents when configuration is required.
- Reject wrong top-level types.
- Reject invalid dates.
- Reject wildcards where policy forbids them.
- Reject absolute paths.
- Reject paths outside the repository.
- Treat a missing required allowlist as exit 2.
- Permit an empty allowlist only when an explicit empty list is present.

## 8.2 Path normalization

Use one path utility that:

- Resolves the repository root explicitly.
- Produces POSIX repository-relative paths.
- Rejects `..` traversal.
- Resolves symlinks.
- Rejects symlinks escaping the repository.
- Detects duplicate files reached through multiple paths.
- Handles Windows drive and case behavior.
- Rejects case-colliding paths.
- Sorts paths deterministically.

## 8.3 Strict source reading

- Require valid UTF-8.
- Treat invalid encoding as exit 2.
- Treat permission/read failures as exit 2.
- Do not replace invalid bytes.
- Do not skip unreadable files.
- Record the failed relative path.
- Continue scanning other files for diagnostics, but final status remains error.

## 8.4 Coverage sanity checks

Each guard must declare:

- Required source roots.
- At least one sentinel file or package.
- Whether an empty scope is legal.
- Minimum expected categories, such as at least one discovered worker.

Fail when:

- A required scope does not exist.
- Zero files are scanned unexpectedly.
- The canonical package moved without registry updates.
- A guard discovers no target symbols where targets are expected.

## Infrastructure tests

Test:

- Missing allowlist.
- Malformed YAML.
- Duplicate YAML key.
- Unknown YAML field.
- Missing source directory.
- Unreadable file.
- Invalid UTF-8.
- Symlink outside repository.
- Empty scan scope.
- Output directory unwritable.
- Invalid base revision.
- Missing parser dependency.

Every case must exit 2.

---

# 9. Workstream F4 — Add a Kotlin parser-aware guard engine

## 9.1 Module design

Create a standalone JVM module using Kotlin compiler PSI matched to the project’s Kotlin version.

It must not depend on Android runtime.

Core components:

- `KotlinSourceLoader`
- `KotlinParseSession`
- `SourceIndex`
- `SymbolIndex`
- `CallIndex`
- `ControlFlowIndex`
- `RuleRegistry`
- `GuardRule`
- `FindingEmitter`
- `JsonReporter`

## 9.2 Parse once

Parse the production Kotlin source tree once and share the resulting index across rules.

The index should expose:

- Packages and imports.
- Import aliases.
- Classes, objects and interfaces.
- Supertypes.
- Constructors and injected properties.
- Functions and suspend modifiers.
- Annotations.
- Calls and receiver expressions.
- Constructor calls.
- Catch clauses.
- Lambdas and callable references.
- Returns.
- String templates and literals.
- Property assignments.
- `when` branches.
- Source locations.

## 9.3 Syntax and resolution tiers

### Tier 1 — Syntax PSI

Use for:

- Direct API calls.
- Constructors.
- Catch clauses.
- annotations.
- imports.
- assignments.
- raw SQL literals.

### Tier 2 — Local symbol resolution

Resolve:

- Import aliases.
- Fully qualified names.
- Constructor property types.
- Local variable types where explicit.
- DAO receiver properties.
- Helper calls within the same file.

### Tier 3 — Project type resolution

Use for high-risk cases requiring:

- CoroutineWorker inheritance.
- DAO interfaces.
- Room mutation annotations.
- Safe diagnostic API identity.
- Cloud payload type flow.
- Event entity and writer types.

A type-resolution failure in a required rule is an infrastructure error, not “no finding.”

## 9.4 Limited control-flow analysis

Implement conservative control-flow checks for:

- Guard call dominance.
- Write-barrier dominance.
- Cancellation rethrow before normal handling.
- DB work occurring inside guarded lambdas.
- Every `doWork` return deriving from the guard result.
- Privacy/payload preparation preceding network send.

When the analyzer cannot prove safety, report a finding rather than assuming safety.

## 9.5 Parse-error behavior

Any syntax error in production Kotlin fails guard execution with exit 2, unless normal Gradle compilation has already classified it as a compile failure. The guard result must still show the parse-error location.

---

# 10. Workstream F5 — Migrate high-risk source guards

## 10.1 Cancellation

Implement exact rules for:

- Broad catches in suspend functions.
- Broad catches in `CoroutineWorker.doWork`.
- Broad catches in lambdas called from suspend contexts.
- `runCatching`, including fully qualified and alias-imported calls.
- `onFailure`, `recover`, `recoverCatching`, `getOrElse`, and `fold` where cancellation can become a value.
- Throwable catches inside guarded worker bodies.

A catch is safe only when:

1. `CancellationException` is caught separately, or
2. The caught value is checked and rethrown before normal error handling, or
3. A canonical cancellation-safe helper is invoked on that exact caught value.

A reference to `CancellationException` elsewhere in the function is not proof.

Required adversarial fixtures:

- Safe call in a nested unrelated function.
- Cancellation text in a comment.
- Cancellation text in a string.
- Rethrow after returning a fallback.
- Alias-imported `runCatching`.
- Custom function also named `runCatching`.
- Multiline catches.
- Nested lambdas.
- `TimeoutCancellationException`.

## 10.2 Worker boundaries

Discover workers through resolved inheritance, including:

- Fully qualified supertypes.
- Import aliases.
- Intermediate abstract base workers.
- Nested worker classes.

Verify:

- `doWork()` invokes the canonical guard.
- The guard call is reachable.
- It occurs on the path leading to protected work.
- DB or sensitive work does not occur before or after the guarded body.
- `workId` and `runAttemptCount` are supplied where required.
- Blocked policy matches worker classification.
- Guard result is converted through the canonical result bridge.
- All `doWork` return paths are valid.
- Cancellation is not swallowed inside the body.
- Registry-discovered workers and source-discovered workers match.

Importing the guard or calling it in an unused helper must not pass.

## 10.3 UI/ViewModel DAO boundaries

Detect:

- DAO constructor injection.
- Field/property injection.
- Fully qualified DAO types.
- Alias-imported DAO types.
- DAO typealiases.
- `AppDatabase.someDao()` calls.
- Local DAO variables.
- DAO passed through a generic provider.
- Read and write calls from ViewModels or Compose UI.

Do not flag repositories merely because a method or variable contains the text `Dao`.

No UI allowlist may suppress an entire file.

## 10.4 Database access and write barriers

Build the mutation catalog from actual DAO declarations:

- `@Insert`
- `@Update`
- `@Delete`
- Mutating `@Query`
- Approved custom mutation annotations or registry entries

Detect:

- Injected DAO mutations.
- Direct database DAO chains.
- Aliased receiver variables.
- Raw SQLite operations.
- Mutating transactions hidden in helpers.

Prove the write barrier dominates the mutation on the same path. A barrier anywhere earlier in the file or method is insufficient.

Structural migration/rescue exceptions remain exact rule-and-symbol exceptions.

## 10.5 Event writers

Resolve actual symbols for:

- Event entity construction.
- Event DAO mutation.
- Approved event-writer calls.
- Transaction context.

Authorization must use exact fully qualified symbols, not filenames.

A permitted event constructor must not automatically permit direct DAO insertion or a second constructor elsewhere in the file.

## 10.6 Cloud payloads and privacy

Replace file-level marker checks with call-path checks.

Detect all body construction forms:

- `RequestBody.create`
- `toRequestBody`
- `asRequestBody`
- `MultipartBody.Builder`
- `FormBody.Builder`
- Direct serializers passed into network bodies
- POST, PUT and PATCH bodies
- Raw files and URI streams

Prove the request body derives from `PreparedCloudPayload` or an approved typed factory.

Merely referencing `CloudPayloadPolicy` elsewhere in the file must not pass.

Derive enum coverage from production enum declarations instead of hardcoded expected-value sets.

## 10.7 PII logging and exception handling

Enforce the canonical safe diagnostics API rather than relying primarily on sensitive variable names.

Block in production source:

- Direct Android `Log`.
- Direct Timber calls outside the safe adapter.
- `println` and standard streams.
- `printStackTrace`.
- Raw Throwable logging.
- `Throwable.message`.
- Arbitrary interpolated exception messages in privacy-sensitive paths.
- Logging of financial, merchant, category, OCR, receipt, notification, bank, email, token, path, or account values.

`BuildConfig.DEBUG` in `src/main` is not a blanket exemption. Debug-only logging belongs in `src/debug`.

Reverse any fixture that treats merchant names, amounts, or categories as generally safe log values.

## 10.8 Receipt-link ownership

Resolve:

- `ScannedReceipt` construction.
- `copy(expenseId=...)`.
- DAO link/unlink methods.
- Direct SQL changes.
- Receipt-link service calls.

Do not approve every file whose name contains `Migration`.

Migration exceptions must identify an exact migration and exact SQL operation.

## 10.9 Import lifecycle

Discover import paths through types and interfaces, not filenames alone.

Verify:

- Expense creation routes through the canonical lifecycle API.
- Category creation routes through the approved owner.
- Provenance fields are assigned values, not merely referenced.
- Barrier and operation-run ownership exist.
- Error paths cannot leave category-only writes.
- Aliased DAO variables are detected.

An allowlisted importer must still be checked for provenance, cancellation, and barrier rules.

## 10.10 Source provenance

Derive:

- `SourceEntityType` values from the enum.
- `ExpenseSource` values from the enum.
- Safe metadata keys from the canonical production declaration.

Detect metadata writes through:

- Maps.
- JSONObject.
- Bundles.
- Kotlin serialization builders.
- Wrapper/helper APIs.

Do not duplicate enum and key lists in the guard.

## 10.11 Money and time

Money rules must resolve actual calls and property symbols:

- Any lambda parameter name, not only `it`.
- Multiline aggregation.
- Intermediate local variables.
- Numeric accumulators.
- Mixed-currency operations.
- Raw budget/dashboard fields.
- Unsafe fallback conversions.

Time rules must resolve actual platform clock calls and aliases.

Remove permissive heuristics such as treating any line containing `now()` as safe.

Only exact `TimeProvider` implementations or adapters may use platform time.

## 10.12 DI/release source rules

Use PSI for Hilt bindings and an evaluated Gradle model for release properties.

A `BuildConfig.DEBUG` reference anywhere in a module is not proof that every suspicious binding is guarded.

Release properties such as debuggability, minification, and resource shrinking should come from a Gradle-generated machine-readable report, not brace-counting source regexes.

---

# 11. Workstream F6 — Harden structured and artifact verifiers

Guards that do not scan Kotlin should use format-specific parsers.

## Migration/schema verifiers

- Parse Room schema JSON with strict JSON schemas.
- Reject duplicate or malformed versions.
- Read migration policy from the canonical PR D source.
- Treat absent JUnit results as errors.
- Detect skipped execution.

## Ignored-test guard

- Parse Kotlin/Java annotations rather than counting lines containing `@Ignore`.
- Distinguish comments and strings.
- Associate reasons with exact classes/methods.
- Reject aliases that bypass annotation detection.
- Derive critical-test policy from one source.

## Release verifier

- Parse ZIP/APK/AAB structures safely.
- Reject traversal entries.
- Reject duplicate archive entries.
- Enforce decompression and file-size limits.
- Validate all expected reports exist.
- Treat external-tool parse failure as exit 2.
- Redact suspected secrets in output.

## Allowlist/backlog verifiers

- Validate against strict schemas.
- Reject duplicate entries and fingerprints.
- Require exact live finding matches.
- Reject unsupported detector versions.
- Reject configuration weakening.

---

# 12. Workstream F7 — Preserve PR C backlog integrity during detector upgrades

## 12.1 Detector-version protocol

Every rule implementation has a detector version.

A behavior-changing detector update must:

1. Increment the version.
2. Run old and new detectors against the PR base.
3. Run the new detector against the PR head.
4. Produce a migration report.
5. Classify findings as preserved, resolved, newly detected on base, or newly introduced on head.

## 12.2 Existing finding remapping

A backlog fingerprint may be remapped only when:

- Old and new findings refer to the same path, symbol, rule, kind, and operation.
- The difference is caused solely by fingerprint implementation.
- The mapping is one-to-one.
- A generated migration report proves equivalence.

## 12.3 Newly discovered historical findings

If the hardened detector finds a violation already present at the PR E base:

- Confirm it is real.
- Fix it immediately when practical.
- Otherwise bootstrap it through the PR C base-proof mechanism.
- Do not treat it as introduced by PR F.
- Require owner and issue assignment.

A finding present only in PR F head must be fixed; it cannot be added to the backlog.

## 12.4 No blanket suppression

Even during detector migration:

- No file-wide exemptions.
- No class-wide exemptions.
- No report mode.
- No global “legacy detector” switch.
- No baseline count reset.

---

# 13. Workstream F8 — Remove duplicate guard implementations

## 13.1 Retire standalone KTS scanners

Remove after parity proof:

- `check_lifecycle_bypasses.kts`
- `check_raw_money_aggregates.kts`
- `check_direct_time_calls.kts`

Their rule IDs must remain stable in the canonical engine where possible.

## 13.2 Remove inline Gradle scanning logic

Replace inline source scanners with thin tasks that invoke the canonical suite or Kotlin analyzer.

A missing executable, report, or registry must fail the Gradle task—not warn and return.

## 13.3 Refactor JVM architecture tests

Keep:

- Runtime/behavioral tests for WorkerExecutionGuard.
- Transaction and coordinator contract tests.
- Tests requiring Android/JVM behavior.

Replace source-regex tests with integration tests that invoke the canonical detector against fixtures or the real source tree.

## 13.4 Replace seeded tests

`GuardSeededViolationTest` must:

1. Create a temporary project fixture.
2. Execute the real guard process.
3. Assert exit code 1.
4. Parse the real JSON result.
5. Assert the expected rule and symbol.
6. Correct the fixture.
7. Re-execute and assert exit code 0.

Never reproduce the guard regex in the test.

## 13.5 Compatibility wrappers

Existing `scripts/verify_*.py` commands may remain temporarily, but they must contain no detection logic.

Each wrapper should:

- Load the registry.
- Invoke the canonical engine for one guard.
- Forward structured output.
- Preserve exit codes.

Mark wrappers for removal only after local documentation and external automation have migrated.

---

# 14. Workstream F9 — Build an adversarial guard test framework

## 14.1 Fixture layout

Use:

- `scripts/fixtures/guards/<guard>/positive/`
- `scripts/fixtures/guards/<guard>/negative/`
- `scripts/fixtures/guards/<guard>/bypass/`
- `scripts/fixtures/guards/<guard>/infrastructure/`
- `scripts/fixtures/guards/<guard>/expected/`

Every rule must have:

- At least one positive fixture.
- At least one canonical negative fixture.
- At least three bypass/adversarial fixtures.
- One malformed-input fixture where applicable.
- One real-repository integration test.

## 14.2 Adversarial variations

Test:

- CRLF and LF.
- UTF-8 BOM.
- Multiline calls.
- Fully qualified calls.
- Import aliases.
- Typealiases.
- Nested classes.
- Extension functions.
- Named and unnamed lambda parameters.
- Comments containing safe markers.
- Strings containing safe markers.
- Braces in strings.
- Reordered annotations.
- Extra whitespace.
- Tabs.
- Expression-bodied functions.
- Generic receivers.
- Local helper indirection.
- Dead/unreachable guard calls.
- Similar but unrelated API names.
- Duplicate filenames in different packages.

## 14.3 Metamorphic tests

For each seeded violation, automatically transform:

- Whitespace.
- Line wrapping.
- Comments.
- Variable names.
- Import style.
- Qualified versus imported references.

The finding fingerprint should remain stable when semantics remain unchanged.

## 14.4 Mutation tests

Deliberately weaken detectors in test copies by:

- Removing a pattern.
- Reversing an allowlist condition.
- Returning an empty list after a read error.
- Treating parse failure as success.
- Skipping one rule registration.

The test corpus must kill these mutations.

## 14.5 Coverage targets

Recommended minimums:

- Python guard core: 95% branch coverage.
- Kotlin analyzer core: 90% branch coverage.
- Every critical rule path: direct positive and negative coverage.
- Every exit code: exercised through subprocess tests.
- Every allowlist path: exact-match, stale, malformed, and non-match tests.

Coverage alone is not acceptance; adversarial fixtures are mandatory.

---

# 15. Workstream F10 — Add guard self-protection

A contributor can otherwise weaken a guard in the same PR that introduces a prohibited change.

## 15.1 Trusted-base execution

For pull requests, use two checkouts:

- **Trusted tools:** guard engine, registry, policies, and backlogs from the protected base SHA.
- **Target source:** application source from the PR head SHA.

Run:

1. Base guard suite against PR-head source.
2. PR-head guard suite against PR-head source.
3. Guard policy-delta validation.
4. Guard contract corpus against PR-head engine.

The PR must pass all four.

## 15.2 What trusted execution protects

The base suite catches:

- Deleting an existing detector.
- Weakening an existing detector.
- Removing a guard from the registry.
- Changing an allowlist to hide a current rule.
- Modifying workflow logic to skip normal head execution.

The head suite proves new implementation correctness.

## 15.3 Guard infrastructure changes

Changes to these paths require CODEOWNER review:

- Workflow guard jobs.
- Guard engine.
- Registry.
- Schemas.
- Allowlists.
- Backlogs.
- Fixture expectations.
- Result verifier.
- Compatibility wrappers.

## 15.4 Integrity meta-guard

Fail when:

- A registered guard disappears.
- A rule ID changes without migration metadata.
- Required mode weakens.
- Detector version changes without migration report.
- Tests or fixtures are removed without rule removal.
- A guard is invoked directly outside the canonical runner.
- New `|| true`, warning mode, or swallowed exceptions appear.
- Required output artifacts are no longer generated.
- Workflow required-check names change unexpectedly.

---

# 16. Workstream F11 — CI integration

## 16.1 Jobs

### `guard-core-tests`

Run:

- Python formatting/lint.
- Static typing.
- Unit tests.
- Schema tests.
- CLI subprocess tests.
- Coverage enforcement.

### `guard-kotlin-tests`

Run:

- Kotlin analyzer compilation.
- Unit tests.
- PSI/symbol-resolution fixtures.
- Control-flow fixtures.
- Coverage enforcement.

### `guard-adversarial-tests`

Run:

- Metamorphic fixtures.
- Mutation tests.
- Real-repository seeded tests.
- Path/configuration failure tests.

### `guard-platform-tests`

Matrix:

- Ubuntu.
- Windows.

Use the canonical Python and JDK versions from the repository.

### `guard-integrity`

Run:

- Trusted-base scan.
- Head scan.
- Policy delta.
- Registry completeness.
- Detector migration validation.

### Stable aggregator

Use displayed name:

**Guard Integrity**

It must fail unless every required guard-hardening job succeeds.

## 16.2 Static Guards integration

The existing Static Guards suite must invoke only the canonical registry runner.

It should no longer list every guard command manually in workflow YAML.

## 16.3 Timeouts

- Per-guard timeout declared in registry.
- Suite-level timeout.
- Timeout is infrastructure failure.
- No automatic retry of a detector assertion.
- Capture thread/process diagnostics on timeout.

## 16.4 Performance gate

Measure:

- Source indexing duration.
- Per-rule duration.
- Total suite duration.
- Peak memory.
- Number of files parsed.

Fail or require explicit review when a guard change causes a significant regression over the protected-branch baseline.

Parse Kotlin once rather than once per rule.

---

# 17. Workstream F12 — Reporting and diagnostics

## 17.1 Output formats

Support:

- Human-readable text.
- Canonical JSON.
- SARIF 2.1.0.
- Markdown summary.

## 17.2 Deterministic ordering

Sort findings by:

1. Rule.
2. Path.
3. Symbol.
4. Operation.
5. Location.

Do not rely on filesystem traversal order.

## 17.3 Sensitive findings

For PII, secrets, tokens, OCR, receipt, email, and bank-related rules:

- Do not include the full source line.
- Include a normalized operation signature.
- Redact literals.
- Hash secret candidates.
- Avoid copying source snippets into uploaded artifacts.

## 17.4 Suite summary

Publish:

| Guard | Version | Mode | Files | Findings | Suppressed | Errors | Duration | Result |
|---|---:|---|---:|---:|---:|---:|---:|---|

Any error must produce `ERROR`, not `PASS` with zero findings.

---

# 18. Workstream F13 — Guard authoring workflow

Replace the current copy-and-edit template with a scaffold command.

The scaffold should create:

- Registry entry.
- Rule module.
- Positive fixture.
- Negative fixture.
- Bypass fixture.
- CLI contract test.
- Documentation stub.
- Owner metadata.
- Detector version.

A new guard cannot pass registry validation until all required pieces exist.

Update `guard-framework.md` with:

- Detector-engine selection.
- Error semantics.
- Finding schema.
- Exact suppression policy.
- Backlog migration policy.
- Adversarial test requirements.
- Trusted-base execution.
- Performance expectations.
- Sensitive-output requirements.

Generate registry tables in documentation automatically.

---

# 19. Recommended commit sequence

## Commit F1

`ci(guards): inventory guard implementations and add canonical registry`

Contains:

- Inventory generator.
- Guard registry.
- Registry schema.
- Duplicate-rule map.
- Completeness tests.

## Commit F2

`ci(guards): add fail-closed guard core and strict configuration loading`

Contains:

- Python guard library.
- Typed errors.
- Strict YAML/JSON schemas.
- Path and source handling.
- Standard CLI/result contract.

## Commit F3

`build(guards): add parser-aware Kotlin guard engine`

Contains:

- JVM module.
- PSI source index.
- Symbol resolution.
- JSON output.
- Parser failure tests.

## Commit F4

`ci(guards): migrate cancellation worker UI DAO and database rules`

Contains:

- High-risk rule implementations.
- Control-flow checks.
- Adversarial fixtures.
- Detector migration reports.

## Commit F5

`ci(guards): migrate event privacy cloud PII import and receipt rules`

Contains:

- Remaining lifecycle/privacy rules.
- Exact symbol ownership.
- Safe-output behavior.
- Fixtures.

## Commit F6

`ci(guards): migrate money time provenance DI and structured verifiers`

Contains:

- Money/time rules.
- Enum-derived provenance coverage.
- Gradle model verification.
- Ignored-test parser.

## Commit F7

`refactor(guards): remove duplicate KTS Gradle and JVM scanners`

Contains:

- KTS removal.
- Inline Gradle scanner removal.
- Thin canonical Gradle tasks.
- JVM integration-test conversion.
- Real seeded execution tests.

## Commit F8

`ci(guards): add trusted-base execution and adversarial guard testing`

Contains:

- Dual checkout.
- Guard Integrity job.
- Metamorphic/mutation tests.
- Cross-platform matrix.
- Performance reporting.

## Commit F9

`docs(guards): document hardened guard framework and verification evidence`

Create only after actual successful Actions runs.

---

# 20. Risks and mitigations

## Kotlin compiler API coupling

**Risk:** PSI/compiler APIs change with Kotlin upgrades.

**Mitigation:**

- Pin analyzer dependency to the project Kotlin version.
- Centralize compiler API usage.
- Add a version compatibility test.
- Fail clearly on version mismatch.
- Upgrade analyzer and project Kotlin together.

## Detector expansion reveals historical violations

**Risk:** Hardened rules discover previously missed debt.

**Mitigation:**

- Compare old/new detectors on the protected base.
- Fix high-risk findings immediately.
- Use PR C historical bootstrap only for proven pre-existing debt.
- Never baseline head-only findings.

## False positives from conservative control flow

**Risk:** Safe code cannot be proven automatically.

**Mitigation:**

- Prefer architecture changes that make safety explicit.
- Use exact structural exceptions only when necessary.
- Attach evidence tests.
- Keep exceptions finding-scoped.

## CI runtime growth

**Risk:** Parser-aware scans become expensive.

**Mitigation:**

- Parse once.
- Share indexes.
- Parallelize independent structured verifiers.
- Cache stable dependencies, not findings.
- Enforce measured performance budgets.

## Duplicate-rule removal changes behavior

**Risk:** Old and new implementations disagree.

**Mitigation:**

- Run in shadow mode.
- Require parity or documented corrections.
- Do not remove old implementation until migration report is reviewed.

## Guard self-modification attack

**Risk:** A PR weakens its own guard.

**Mitigation:**

- Run protected-base tools against PR-head source.
- Require stable branch-protected check names.
- Require CODEOWNER review.
- Enforce policy delta and fixture completeness.

---

# 21. PR acceptance checklist

## Framework

- [ ] Canonical guard registry exists.
- [ ] Every guard is registered exactly once.
- [ ] Rule IDs are unique.
- [ ] Standard finding/result schemas are enforced.
- [ ] Exit codes are consistently 0/1/2.
- [ ] Output is deterministic.
- [ ] Atomic output writing is implemented.

## Fail-closed behavior

- [ ] Missing source fails with exit 2.
- [ ] Missing required config fails with exit 2.
- [ ] Malformed config fails with exit 2.
- [ ] Missing dependency fails with exit 2.
- [ ] Unreadable source fails with exit 2.
- [ ] Invalid UTF-8 fails with exit 2.
- [ ] Parse failure fails with exit 2.
- [ ] Empty unexpected scan scope fails.
- [ ] No detector uses silent exception handling.

## Parser-aware source rules

- [ ] Cancellation rule uses syntax and context.
- [ ] Worker rule proves guard placement and result flow.
- [ ] UI DAO rule resolves aliases and types.
- [ ] DB rule derives mutation methods and proves barrier ordering.
- [ ] Event rule uses exact symbols.
- [ ] Cloud rule proves prepared-payload flow.
- [ ] PII rule enforces safe diagnostic ownership.
- [ ] Import and receipt rules remain active inside approved owners.
- [ ] Provenance enum/key sets derive from production declarations.
- [ ] Money/time rules resolve calls rather than raw names.

## Suppressions and backlogs

- [ ] No whole-file/class exemption remains.
- [ ] Each suppression matches one finding.
- [ ] Stale suppressions fail.
- [ ] Detector versions are tracked.
- [ ] Backlog migration reports exist.
- [ ] No PR F head-only finding is added to a backlog.
- [ ] Resolved findings are pruned.

## Duplicate removal

- [ ] Legacy KTS guards removed.
- [ ] Inline Gradle scanners removed.
- [ ] Duplicate JVM source scanners removed or converted.
- [ ] One allowlist source remains per rule.
- [ ] Gradle tasks fail when canonical guards are unavailable.
- [ ] No external `kotlin` CLI is required.

## Tests

- [ ] Every rule has positive fixtures.
- [ ] Every rule has negative fixtures.
- [ ] Every high-risk rule has adversarial fixtures.
- [ ] Infrastructure failures are tested through the CLI.
- [ ] Seeded tests execute real guards.
- [ ] Metamorphic tests pass.
- [ ] Mutation tests pass.
- [ ] Real-repository integration tests pass.
- [ ] Python and Kotlin coverage thresholds pass.
- [ ] Windows and Linux tests pass.

## Self-protection

- [ ] Protected-base guard suite scans PR-head source.
- [ ] Head guard suite also passes.
- [ ] Policy delta passes.
- [ ] Registry completeness passes.
- [ ] Workflow weakening fails.
- [ ] Detector changes require version/migration metadata.
- [ ] Guard infrastructure requires CODEOWNER review.

## CI evidence

- [ ] Static Guards passes.
- [ ] Guard Integrity passes.
- [ ] Unit tests pass.
- [ ] Lint and `:app:check` pass.
- [ ] Migration Proof passes.
- [ ] Release Verification passes.
- [ ] Required artifacts exist on success and failure.
- [ ] Two consecutive complete Actions runs pass.
- [ ] Exact SHA and run IDs are documented.

---

# 22. Definition of done

PR F is complete only when:

- A guard can no longer pass because its input, configuration, or parser failed.
- Kotlin architecture rules inspect actual syntax and symbols.
- High-risk ordering rules use conservative control-flow proof.
- Every finding is deterministic and machine-readable.
- Every suppression is exact and independently validated.
- Detector upgrades preserve PR C backlog integrity.
- Seeded violations execute and fail the actual guard.
- Duplicate KTS, Gradle, Python, and JVM implementations no longer drift.
- The protected-base suite prevents a PR from weakening its own enforcement.
- CI proves guard behavior on Linux and Windows.
- All required checks remain green without warning modes or hidden errors.

The required invariant is:

> **A green guard result means the complete intended scope was successfully read, parsed, analyzed, compared against exact policy, and tested against known bypass techniques.**