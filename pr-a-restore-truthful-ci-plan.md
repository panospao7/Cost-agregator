# PR A — Restore Truthful CI

## 1. PR definition

**Suggested title:**  
`ci: restore truthful blocking checks and resolve current CI failures`

**Base commit:**  
`ebb5aa93348282b31c1c669d1bf1271d584b9eb0`

**Primary issues:**  
MIT-001, MIT-002, MIT-026, MIT-060

**Estimated effort:** 4–7 engineering days.

### Objective

Produce one commit for which GitHub Actions truthfully proves that:

1. Every designated blocking static guard executes.
2. The UI/DAO boundary passes because the architecture is fixed—not because UI code is exempted.
3. The PII logging guard has zero violations.
4. JVM tests complete without timing out.
5. Android lint passes without suppressing real findings.
6. Debug compilation and `:app:check` pass.
7. Failure artifacts contain actionable output.
8. Documentation references an actual successful Actions run.

The current Actions run reports:

- Static Guards: failed.
- Unit Tests: exceeded the 30-minute timeout.
- Lint & Check: failed.
- Validate Workflow: passed.
- Static-guard logs were not uploaded because the configured paths did not exist. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29052459061))

---

## 2. Explicit non-goals

Keep this PR focused. Do not include:

- Full cancellation backlog burn-down.
- Full DB-access/event-writer backlog burn-down.
- Migration execution enforcement.
- Release APK/AAB verification.
- Worker allowlist redesign.
- Complete guard-parser hardening.
- New bank OAuth implementation.
- Broad architecture refactoring unrelated to failing CI.

Those belong to PRs B–F.

Do not:

- Add new `|| true` expressions.
- Add a lint baseline to hide failures.
- Add `@Ignore` to unblock tests.
- Raise ignored-test budgets.
- Add permanent UI/ViewModel allowlist entries.
- Convert real errors into warnings.
- claim CI success based only on local execution.

---

# 3. Workstream A1 — Make the static-guard job complete and observable

## Problem

The static guards are separate sequential workflow steps. A blocking failure prevents later guard steps and pytest from executing. Four existing guards are also run with `|| true`, while the artifact step expects files the guards do not generate. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/.github/workflows/ci.yml))

## Implementation

### 3.1 Add a guard-suite runner

Create:

`/scripts/ci/run_static_guard_suite.py`

The runner should contain a declarative manifest with:

- Guard name.
- Command and arguments.
- Mode: `blocking` or `warning`.
- Expected exit-code policy.
- Output log filename.

It must execute every guard even if an earlier guard fails.

### 3.2 Exit-code policy

Use these semantics:

- Blocking guard exit `0`: pass.
- Blocking guard exit `1`: violation; final suite fails.
- Any guard exit `2`: infrastructure/configuration error; final suite fails.
- Warning guard exit `1`: recorded warning, but not a blocking failure.
- Process crash, command missing, timeout, or malformed result: infrastructure failure.
- Final suite exit `0`: every blocking check passed.
- Final suite exit `1`: one or more blocking checks failed.
- Final suite exit `2`: suite infrastructure failed.

A warning guard must never silently pass when it could not execute.

### 3.3 Preserve current enforcement modes

For this PR, use the modes currently intended by the workflow:

**Warning backlog**

- Privacy.
- DB access.
- Event writers.
- Money.
- Cancellation.

**Blocking**

- Source provenance.
- UI DAO.
- Worker.
- Receipt-link.
- Import lifecycle.
- Cloud payload.
- PII logging.
- DI/release.
- Allowlist compliance.
- Migration matrix.
- Ignored-test budget.
- Guard pytest suite.

Do not promote backlog guards here; PR C will introduce fail-on-growth baselines.

### 3.4 Generate deterministic artifacts

Write:

- `build/ci/static-guards/<guard-name>.log`
- `build/ci/static-guards/summary.json`
- `build/ci/static-guards/summary.md`

The JSON summary should contain:

- Guard name.
- Mode.
- Command.
- Exit code.
- Outcome.
- Violation count if determinable.
- Duration.
- Log path.

The Markdown summary should be appended to `$GITHUB_STEP_SUMMARY`.

### 3.5 Update GitHub Actions

Replace individual static-guard execution steps with:

- Install Python dependencies.
- Run the suite runner.
- Upload `build/ci/static-guards/**` using `if: always()`.
- Use `if-no-files-found: error`.
- Keep the runner’s exit status as the job result.

Raise the static job timeout from 10 to 15 minutes only if measurements show it is necessary.

### 3.6 Test the runner

Create:

`/scripts/ci/test_run_static_guard_suite.py`

Required tests:

1. Later guards run after an earlier blocking failure.
2. A warning violation does not fail the suite.
3. A warning guard infrastructure error fails the suite.
4. A blocking violation fails the suite.
5. stdout and stderr are captured.
6. Summary JSON is valid and deterministically ordered.
7. Every manifest entry produces a log.
8. pytest is still executed after a prior failure.
9. Missing command produces exit 2.
10. No `|| true` is necessary.

## Acceptance criteria

- Every guard and pytest appears in `summary.json`.
- The artifact exists on success and failure.
- The final job fails if any blocking guard fails.
- A deliberately seeded failure does not stop later guards.
- No new warning suppression is introduced.

---

# 4. Workstream A2 — Remove DAO access from BankConnectionsViewModel

## Current problem

`BankConnectionsViewModel` injects `BankConnectionDao`, subscribes to it, fetches connections by ID, and directly calls `disconnect()`. It also logs raw Throwables and connection IDs and catches broad exceptions without explicit cancellation propagation. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsViewModel.kt))

The DAO provides direct read and mutation methods, including `getAllConnections()`, `getById()` and `disconnect()`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt))

## Target architecture

Use:

`BankConnectionsViewModel → BankConnectionLifecycleCoordinator → repository/integration → DAO`

The ViewModel must not import:

- `BankConnectionDao`
- `AppDatabase`
- Any DAO type
- Database entities containing encrypted tokens

## Implementation

### 4.1 Add safe UI/domain models

Create a token-free model such as:

`BankConnectionSummary`

It should contain only fields required by the screen:

- ID.
- Bank ID.
- Display name.
- Country.
- Connected/active state.
- Last sync time/status.
- Optional safe status code.

It must not expose:

- Access tokens.
- Refresh tokens.
- Token ciphertext.
- Token expiry unless actually needed by UI.
- Provider error text.

### 4.2 Introduce a repository port

Create a domain contract:

`BankConnectionRepository`

Responsibilities:

- Observe token-free summaries.
- Resolve an internal connection for lifecycle operations.
- Perform the low-level atomic disconnect mutation.

Create a data implementation that owns `BankConnectionDao`.

Read-only observation should map database entities to `BankConnectionSummary` before crossing the repository boundary.

### 4.3 Introduce the lifecycle coordinator

Create:

`BankConnectionLifecycleCoordinator`

Responsibilities:

- `observeConnections()`
- `syncConnection(connectionId)`
- `disconnectConnection(connectionId)`
- Return typed outcomes.
- Enforce the write barrier.
- Propagate cancellation.
- Emit safe operation diagnostics.
- Never return raw exception text.

Suggested outcomes:

- Success.
- NotFound.
- BlockedByMaintenance.
- UnsupportedDemoOperation.
- RetryableFailure with safe reason code.
- PermanentFailure with safe reason code.

### 4.4 Preserve bank-sync ordering

`BankApiIntegration` already owns a write barrier and operation-run recording around sync. Avoid creating duplicate run ledgers. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt))

Preferred design:

1. Add an ID-based sync entry point.
2. Start the existing operation run.
3. Check the write barrier.
4. Resolve the connection.
5. Execute sync.
6. Return a typed result.

Do not read the connection before maintenance/write-barrier authorization.

### 4.5 Disconnect implementation

Required order:

1. Start safe lifecycle/operation evidence.
2. Check write barrier.
3. Resolve connection existence.
4. Execute the DAO’s single-statement token-clearing disconnect.
5. Record a safe outcome.
6. Return a typed result.

No connection ID, bank account identifier, token, or raw exception may be logged.

### 4.6 Refactor the ViewModel

The ViewModel should inject only the coordinator.

Remove:

- DAO injection.
- DAO import.
- Direct `BankApiIntegration` dependency.
- Direct entity exposure.
- Logs containing connection IDs.
- Logs receiving raw Throwable objects.
- Raw `e.message` UI errors.

Room’s Flow already observes changes. Remove the artificial DAO resubscription unless a documented retry requirement exists.

If retry is required, expose a coordinator-level retry/reload operation rather than forcing the ViewModel to manipulate DAO subscriptions.

### 4.7 Loading and error-state correctness

Ensure:

- Loading resets in `finally`.
- Cancellation is rethrown and does not become an error state.
- Sync and disconnect cannot overlap accidentally for the same connection.
- UI errors use fixed reason codes or localized resources.
- A missing connection produces `NotFound`, not silent success.
- Placeholder/demo banks are produced outside the DAO path.

### 4.8 Remove the UI allowlist entry

Delete the `BankConnectionsViewModel` entry from:

`scripts/allowlists/ui_dao_allowlist.yml`

Do not replace it with a renamed UI exception.

If a new canonical lifecycle owner requires DB-guard recognition, add only the smallest rule/symbol-specific entry supported by that guard, with MIT-060 ownership and expiry. The total DB warning count must not increase.

## Tests

### Coordinator tests

- Observe maps entities without tokens.
- Barrier is checked before disconnect.
- Barrier failure prevents DAO mutation.
- Missing ID returns `NotFound`.
- Disconnect clears the connection exactly once.
- Sync resolves by ID only after authorization.
- Cancellation propagates.
- Safe diagnostics contain no sentinel PII.
- Raw exception messages do not enter results.

### ViewModel tests

- Initial connection collection.
- Empty state generates supported-bank placeholders.
- Sync success.
- Sync failure produces safe state.
- Disconnect success.
- Disconnect missing-row behavior.
- Cancellation does not become an error.
- Loading state always terminates.
- No DAO dependency is required in the test fixture.

### Architecture tests

- UI DAO guard reports zero violations.
- Seeded ViewModel DAO injection still fails.
- Source scan confirms no DAO imports under the bank UI package.

## Acceptance criteria

- UI DAO guard: zero violations.
- No UI DAO allowlist entry.
- `BankConnectionsViewModel` has no database-layer imports.
- No token-bearing entity reaches UI state.
- Cancellation tests pass.
- Existing bank screen behavior remains functional.

---

# 5. Workstream A3 — Burn down all ten blocking PII findings

## Safety policy

Do not replace `printStackTrace()` with `Timber.e(e, ...)`. Passing the original Throwable can still serialize its message, cause chain, stack, filenames, URLs, OCR content, or provider details.

Use a safe diagnostic mechanism accepting only:

- A compile-time reason code.
- Severity.
- Optional exception class name.
- Typed, pre-approved metadata.

It must reject:

- Throwable objects.
- Arbitrary strings.
- `Throwable.message`.
- Receipt/OCR/email text.
- Paths.
- Tokens.
- Connection identifiers.
- Merchant or item names.

If a suitable safe diagnostic abstraction already exists, reuse it. Otherwise introduce a minimal `SafeDiagnosticReporter`.

## File-by-file changes

### 5.1 NaturalLanguageSearchViewModel

Current code calls `printStackTrace()` and places `e.message` directly into UI state. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt))

Change:

- Explicitly rethrow `CancellationException`.
- Report `NATURAL_LANGUAGE_SEARCH_FAILED`.
- Replace raw UI message with a typed `SearchError.SearchFailed`.
- Map the typed error to a fixed localized UI string.
- Never log the user’s query.

### 5.2 BillNegotiationViewModel

Current analysis failure calls `printStackTrace()`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/ui/screens/negotiation/BillNegotiationViewModel.kt))

Change:

- Propagate cancellation.
- Report `NEGOTIATION_ANALYSIS_FAILED`.
- Preserve empty/error-state behavior explicitly.
- Do not log subscription IDs, prices, notes, savings, or the Throwable.

### 5.3 PriceProtectionViewModel

Three `printStackTrace()` paths exist in stream, load, and refresh handling. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/ui/screens/price/PriceProtectionViewModel.kt))

Change:

- Add cancellation propagation in all three paths.
- Use distinct safe reason codes:
  - `PRICE_DROP_STREAM_FAILED`
  - `PRICE_PROTECTION_LOAD_FAILED`
  - `PRICE_PROTECTION_REFRESH_FAILED`
- Ensure `_isLoading` is reset through `finally`.
- Do not log receipt IDs, item names, tracking keys, purchase dates, or Throwables.

### 5.4 ReceiptOcrService

Current code logs the Throwable and wraps raw `e.message` into new exceptions. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt))

Change:

- Add a typed `ReceiptOcrFailure` reason.
- Use fixed messages such as:
  - `RECEIPT_PDF_SCAN_FAILED`
  - `RECEIPT_IMAGE_LOAD_FAILED`
- Remove raw `e.message` interpolation.
- Do not pass OCR-processing Throwables to Timber.
- Preserve cancellation propagation.
- Ensure callers receive only a fixed safe message/reason.
- If the cause is retained internally, prove it is never serialized to logs, UI, diagnostics, or persisted error fields.

### 5.5 ReceiptLifecycleCoordinator

Replace any exception message containing an email address with a typed email-validation failure.

Also audit the touched paths for:

- `SaveEmailReceiptResult.Failed(e.message)`
- `DomainResult.Error(... message = it.message)`
- Other raw failure strings crossing into UI or persistence.

The file already contains additional raw exception-message propagation that should be converted while modifying this surface. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt))

Use fixed reasons such as:

- `EMAIL_RECEIPT_VALIDATION_FAILED`
- `EMAIL_RECEIPT_SAVE_FAILED`
- `EMAIL_RECEIPT_DUPLICATE_CONFLICT`

### 5.6 ValidateBankStatementTransactionsUseCase

The current log references `rawOcrText` for length metadata, causing the sensitive-variable guard to classify the statement as unsafe. The OCR text is also intentionally included in the validation prompt later in the function. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt))

Change:

- Remove `rawOcrText` from all logging expressions.
- Log only a fixed operation code and candidate count if needed.
- Do not log a truncated OCR sample, hash, prefix, or suffix.
- Do not alter the separately privacy-gated prompt behavior in this PR.

### 5.7 SqliteSnapshotCreator

Current fallback logging interpolates `e.message`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/data/backup/SqliteSnapshotCreator.kt))

Change:

- Log only `SQLITE_VACUUM_FALLBACK`.
- Optionally include an approved exception class category.
- Do not log source/target paths.
- Preserve fallback deletion and copy behavior.

## Regression tests

Add tests using sentinel values resembling:

- Email addresses.
- File paths.
- API keys.
- IBAN/card numbers.
- Receipt item text.
- Merchant names.
- OAuth tokens.
- Raw OCR lines.

Capture:

- Diagnostic output.
- UI error state.
- Exception messages.
- Persisted operation metadata.

Assert that no sentinel appears.

Extend PII guard fixtures to detect:

- `printStackTrace`.
- Raw `e.message`.
- Throwable arguments to logging methods.
- User fields interpolated into exceptions.
- Sensitive variables passed to log calls.

## Acceptance criteria

- `verify_pii_logging_boundaries.py --fail-on-violation`: zero violations.
- No PII allowlist additions.
- No `printStackTrace()` in production source.
- No raw `e.message` in the modified paths.
- No raw Throwable passed to production logging in the modified paths.
- Cancellation tests pass.

---

# 6. Workstream A4 — Resolve lint truthfully

## Procedure

1. Download and inspect the `lint-results` artifact from Actions run `29052459061`.
2. Run `:app:lintDebug` locally from a clean checkout.
3. Classify each error:
   - Real correctness issue.
   - API-level compatibility issue.
   - Resource/manifest issue.
   - Compose/state issue.
   - False positive requiring narrow justification.
4. Fix production code first.
5. For genuine false positives, use the narrowest symbol-level suppression with an explanatory comment and regression test.
6. Do not create or expand `lint-baseline.xml`.
7. Do not set `abortOnError=false`.
8. Do not globally disable detectors.

## Artifact improvements

Upload with `if: always()`:

- HTML lint report.
- XML lint report.
- SARIF report if available.
- Lint text output.

Use a guaranteed artifact directory, such as:

`build/ci/lint/`

Copy reports there before upload and fail if expected reports are absent after lint execution.

## Acceptance criteria

- `:app:lintDebug` exits zero.
- No baseline growth.
- No global detector suppression.
- Lint reports are uploaded on success and failure.
- `:app:assembleDebug` runs after lint and succeeds.

---

# 7. Workstream A5 — Diagnose and remove the JVM-test timeout

## 7.1 Diagnose before changing timeout

Run the exact CI command with:

- Gradle stacktrace.
- Info logging.
- Test start/finish logging.
- JUnit XML output.
- Gradle profiling.

Determine whether the 30-minute failure is caused by:

- Compilation/Hilt processing.
- A hanging test.
- Architecture source scans.
- Excessive test count.
- Deadlock/coroutine leak.
- Resource exhaustion.
- Repeated task execution.

Do not assume the whole suite merely needs more time.

## 7.2 Decision path

### If one or more tests hang

- Fix coroutine/test-scope ownership.
- Replace unbounded waits with deterministic virtual-time advancement.
- Add explicit test timeouts.
- Ensure dispatchers/executors are closed.
- Keep the existing job structure if the corrected suite finishes comfortably.

### If architecture scans dominate

Split into:

- Core JVM tests.
- Architecture/contract guard tests.
- Verification tasks.

Ensure each source tree is scanned once per shard where practical.

### If total legitimate duration exceeds the job budget

Create separate jobs:

- `unit-tests-core`
- `architecture-tests`
- `unit-verification`

Then create an aggregator job whose displayed name remains **Unit Tests**, preserving branch-protection compatibility. The aggregator must use `if: always()` and fail unless every required dependency succeeded.

`unit-verification` should own:

- Room snapshot verification.
- Ignored-test growth verification.
- Currency guardrails.

## 7.3 Timeout policy

A timeout increase may be used temporarily to capture diagnostics.

The final timeout should provide approximately 30% headroom over observed p95 duration. Do not leave a 60-minute timeout around a suite that normally finishes in five minutes.

## 7.4 Artifact policy

Always upload:

- JUnit XML.
- Test HTML.
- Gradle profile.
- Architecture-test reports.
- Verification-task output.

Each shard must generate its own artifact even if another shard fails.

## Acceptance criteria

- No job timeout.
- No new ignored tests.
- No test exclusion added merely to obtain green CI.
- Two consecutive CI runs complete successfully.
- Each test shard remains below 70% of its timeout.
- Expected JUnit artifacts exist.

---

# 8. Workstream A6 — Final workflow and documentation synchronization

## Workflow verification order

Required jobs should prove:

### Validate Workflow

- actionlint succeeds.

### Static Guards

- Every guard executes.
- Guard pytest executes.
- UI DAO is clean.
- PII is clean.
- Warning backlogs are reported.
- Summary and logs are uploaded.

### Unit Tests

- All JVM and architecture tests pass.
- Room snapshot verification passes.
- Ignored-test checks pass.
- Currency checks pass.

### Lint & Check

- Lint passes.
- Debug assembly passes.
- `:app:check` passes.

Instrumented tests remain outside this PR’s blocking scope.

## Documentation updates

Update only after a successful GitHub Actions run:

- `docs/ci/LATEST_CI_VERIFICATION.md`
- `docs/ci/GUARD_VIOLATION_AUDIT.md`
- `docs/ci/CI_GUARDRAILS_BASELINE.md`
- `docs/ci/local-ci.md`
- `docs/ci/developer-quickstart.md`
- `MASTER_ISSUE_TRACKER.md` entries for MIT-001, MIT-002, MIT-026 and MIT-060 as appropriate.

The verification document must include:

- Exact commit SHA.
- Actions run ID.
- Trigger type.
- Job conclusions.
- Job durations.
- Guard modes.
- Actual violation counts.
- Explicit skipped/non-blocking coverage.
- Artifact names.
- Date of execution.

Do not use “expected,” “should pass,” or “locally verified” as substitutes for an actual Actions result.

---

# 9. Recommended commit sequence

## Commit A1

`ci: run complete static guard suite and publish deterministic results`

Contains:

- Guard-suite runner.
- Runner tests.
- Workflow integration.
- Static artifacts and summary.

## Commit A2

`refactor(bank): move connection actions behind lifecycle coordinator`

Contains:

- Safe bank summary model.
- Repository port and implementation.
- Lifecycle coordinator.
- ViewModel refactor.
- Hilt bindings.
- UI allowlist removal.
- Coordinator/ViewModel tests.

## Commit A3

`fix(privacy): remove blocking PII logging and exception propagation`

Contains:

- Safe diagnostic reason codes.
- Ten PII fixes.
- Cancellation corrections in touched code.
- Sentinel privacy tests.
- PII guard fixture improvements.

## Commit A4

`fix(lint): resolve current blocking Android lint findings`

Contains only lint-related production/test corrections and artifact improvements.

## Commit A5

`ci(test): make JVM verification deterministic and bounded`

Contains:

- Hanging-test fixes or sharding.
- Test reporting.
- Aggregator job if needed.
- Evidence-based timeouts.

## Commit A6

`docs(ci): record successful truthful CI verification`

Must be created only after the complete Actions run passes.

---

# 10. Risk controls

## Bank behavior regression

Risk:

- Placeholder banks disappear.
- Room Flow no longer refreshes.
- Sync runs are duplicated.
- Tokens leak through the new model.

Mitigation:

- Golden mapping tests.
- Screen/ViewModel regression tests.
- Single operation-run owner.
- Token-free model assertions.

## Diagnostic over-sanitization

Risk:

- Failures become impossible to investigate.

Mitigation:

- Keep stable reason codes.
- Retain exception class categories.
- Store counts, stage and outcome.
- Never retain raw message/content.

## Static runner masks failure

Risk:

- Runner incorrectly classifies a blocking failure as warning.

Mitigation:

- Manifest tests.
- Seeded violations for every mode.
- Exit-code contract tests.
- Infrastructure errors always block.

## Test sharding hides tests

Risk:

- Filter patterns omit classes.

Mitigation:

- Add a discovery/count test comparing all discovered test classes against shard membership.
- Fail on duplicate or unassigned classes.
- Keep the unsharded suite available as a scheduled/manual verification command.

---

# 11. Pull-request acceptance checklist

## Architecture

- [ ] No DAO import or injection in `BankConnectionsViewModel`.
- [ ] No token-bearing database entity exposed to UI.
- [ ] UI DAO allowlist entry removed.
- [ ] Barrier executes before bank mutation/read-for-mutation.
- [ ] Cancellation propagates from sync and disconnect.

## Privacy

- [ ] All ten PII findings removed.
- [ ] No `printStackTrace()` in production.
- [ ] No raw `e.message` in modified paths.
- [ ] No Throwable passed to logging in modified paths.
- [ ] No raw exception text reaches UI.
- [ ] Sentinel privacy tests pass.

## Static CI

- [ ] Every guard executes.
- [ ] Guard pytest executes.
- [ ] UI DAO guard passes.
- [ ] PII guard passes with zero violations.
- [ ] Warning findings remain visible.
- [ ] Static logs and summaries upload on every run.

## JVM CI

- [ ] Unit tests finish without timeout.
- [ ] Architecture tests pass.
- [ ] Room snapshot verification passes.
- [ ] Ignored-test checks pass.
- [ ] Currency guardrails pass.
- [ ] JUnit reports upload.

## Gradle/Android

- [ ] `lintDebug` passes.
- [ ] `assembleDebug` passes.
- [ ] `:app:check` passes.
- [ ] No lint-baseline growth.
- [ ] No ignored-test growth.

## Evidence

- [ ] Complete GitHub Actions run is green.
- [ ] A second run is green without code changes.
- [ ] Exact SHA and run ID recorded.
- [ ] Documentation matches actual workflow modes.
- [ ] No claim of release readiness is made.

---

# 12. Definition of done

PR A is complete only when a single commit has an actual GitHub Actions run where:

- Validate Workflow succeeds.
- Static Guards succeeds after executing the complete suite.
- PII and UI DAO guards report zero violations.
- Unit Tests succeed without timeout.
- Lint & Check succeeds.
- Debug assembly succeeds.
- `:app:check` succeeds.
- All expected artifacts exist.
- Warning debt remains visible and accurately counted.
- No architectural or privacy finding was hidden through a new exemption, suppression, skipped test, or `|| true`.

At that point, CI is **truthful for its currently designated blocking scope**. It does not yet prove migration execution, release safety, or zero warning debt; those remain explicit follow-up PRs.