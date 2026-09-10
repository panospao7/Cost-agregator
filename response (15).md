I reviewed the full 13-commit sequence from `ebb5aa9` through `b710120`, including PRs A–F, follow-up fixes, current source, and Actions run #431. **Verdict: RED — the branch is not merge-ready.** The latest run already has failures in Static Guards, Release Check, and `:app:check`. ([github.com](https://github.com/panospao7/Cost-agregator/compare/ebb5aa93348282b31c1c669d1bf1271d584b9eb0...b71012066943a8cccf1afb26ebf7af6b4f960f3d))

<CI_GUARDRAILS_DEEP_REVIEW_B710120.md>
# CI Guardrails Deep Review

**Target:** `b71012066943a8cccf1afb26ebf7af6b4f960f3d`  
**Compared against:** `ebb5aa93348282b31c1c669d1bf1271d584b9eb0`  
**Scope:** 13 commits, 111 changed files. ([github.com](https://github.com/panospao7/Cost-agregator/compare/ebb5aa93348282b31c1c669d1bf1271d584b9eb0...b71012066943a8cccf1afb26ebf7af6b4f960f3d))  
**Verdict:** **RED — do not merge**

---

# 1. Actual CI status

Actions run **#431**, triggered July 10, 2026 at 18:56, shows:

| Job | Result at review time |
|---|---|
| Validate Workflow | Passed |
| Static Guards | **Failed — exit 2** |
| Lint & Check | **Failed — `:app:check`** |
| Release Check | **Failed — artifact verifier exit 1** |
| Unit Tests | Still running at capture time |
| Instrumented Tests | Not scheduled on feature-branch pushes |

The lint failure is no longer `lintDebug`; execution reached the later `Run Gradle check` step. Therefore the MissingTranslation baseline appears to have allowed lint and assembly to complete, but the overall Gradle verification remains red. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29116299203))

No successful complete run exists for the target SHA. Run #430 was cancelled by the newer push and already showed failures in Static Guards, Release Check, and Lint & Check. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29115246513))

---

# 2. Critical blocker: Static Guards is structurally broken

## 2.1 Release artifact verification incorrectly runs in Static Guards

`run_static_guard_suite.py` includes `release_artifact` as a blocking guard. However, the Static Guards job only checks out source, installs Python, and runs the suite. It never builds an APK.

`verify_release_artifact.py` reports a violation if no release APK exists. Therefore a clean Static Guards checkout cannot pass this guard. Release verification belongs exclusively after `assembleRelease`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b71012066943a8cccf1afb26ebf7af6b4f960f3d/.github/workflows/ci.yml))

### Required fix

Remove `release_artifact` from `GUARD_MANIFEST`.

Keep it only in the Release Check job after the release artifact has been produced.

---

## 2.2 Ratchet exit-code contract is incompatible with the suite runner

`guard_ratchet.py` returns:

- `0` for unchanged.
- `1` for new findings.
- `2` for some errors.
- `3` when findings were resolved.

The suite runner interprets only `0` as pass and `1` as violation; every other value is classified as infrastructure failure. Therefore a successful backlog reduction returns exit 3 and causes Static Guards to exit 2. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b71012066943a8cccf1afb26ebf7af6b4f960f3d/scripts/ci/run_static_guard_suite.py))

This directly explains the current exit-2 behavior:

- PR C created a DB baseline with 70 findings.
- `b710120` changed the DB guard result to zero.
- `b710120` did not modify the baseline file.
- The ratchet therefore sees resolved findings and returns 3.
- The suite classifies 3 as infrastructure failure. ([github.com](https://github.com/panospao7/Cost-agregator/compare/ebb5aa93348282b31c1c669d1bf1271d584b9eb0...b71012066943a8cccf1afb26ebf7af6b4f960f3d))

### Required fix

Use only the standard contract:

- `0`: policy satisfied.
- `1`: policy violation, including stale/resolved baseline entries that must be pruned.
- `2`: infrastructure failure.

Delete exit code 3.

Prefer this behavior:

```text
new finding             -> exit 1
resolved but unpruned   -> exit 1
exact baseline match    -> exit 0
guard/config error      -> exit 2
```

---

## 2.3 The latest ratchet fix remains incorrect

`b710120` treats any non-zero guard exit with empty stdout as an infrastructure error. This is not sufficient:

- A guard exiting `2` with stdout is still an infrastructure error.
- A legitimate guard could report through stderr or a structured output file.
- Unknown exit codes must always be errors regardless of stdout.
- Exit code `1` should remain a violation even if stdout is empty.

The updated test no longer tests a genuinely missing command. It now runs Python with `sys.exit(1)`, so its original “command not found” behavior is no longer covered. ([github.com](https://github.com/panospao7/Cost-agregator/commit/b71012066943a8cccf1afb26ebf7af6b4f960f3d))

### Correct handling

```text
child exit 0 -> pass
child exit 1 -> findings/violation
child exit 2 -> infrastructure error
other exit   -> infrastructure error
```

Output presence must not determine semantic exit status.

---

# 3. Critical blocker: DB violations were exempted, not fixed

`b710120` reports zero DB findings because it added approximately 17 class-level allowlist entries and approved two whole classes for raw DB file operations. Many entries explicitly set:

```text
requires_write_barrier: false
```

Affected paths include:

- Receipt lifecycle services.
- Transaction event writers.
- RestoreJournalImporter.
- BankApiIntegration.
- Source-link writers.
- OperationRunRecorder.
- SmartBillNegotiationEngine.
- WarrantyExpirationWorker.
- FinancialRescueCoordinator.
- DatabaseMigrations. ([github.com](https://github.com/panospao7/Cost-agregator/commit/b71012066943a8cccf1afb26ebf7af6b4f960f3d))

Most entries:

- Have no `methods_only`.
- Authorize every recognized matching mutation in the class.
- Waive the write-barrier requirement.
- Use the generic reason “pre-existing pattern.”
- Link to generic MIT-003 rather than the relevant ownership issue.
- Have no concrete removal date or evidence test.

This contradicts PR B’s goal of exact, finding-scoped exemptions.

## Scanner weaknesses remain

The current DB guard:

- Uses a custom line-oriented YAML parser.
- Returns an empty allowlist when the file is missing.
- Prints only a warning for missing allowlist.
- Silently skips unreadable Kotlin files.
- Matches class ownership by filename.
- Proves a barrier only by checking whether matching text appears earlier in the function.
- Does not prove control-flow dominance.
- Approves DB file operations for an entire class, not an exact method. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/b71012066943a8cccf1afb26ebf7af6b4f960f3d/scripts/verify_db_access_boundaries.py))

Therefore PR F’s claimed fail-closed hardening does not cover this high-risk guard.

## Required fix

1. Revert the new class-wide exemptions.
2. Restore the 70 exact pre-existing findings to the DB ratchet temporarily.
3. Keep only genuine structural exceptions:
   - Exact migration functions inside `DatabaseMigrations`.
   - Exact maintenance-owned operations in `FinancialRescueCoordinator`, after maintenance ownership is proven.
4. Route ordinary mutations through lifecycle owners.
5. Require a write barrier where appropriate.
6. Add exact method and operation matching.
7. Make missing/unreadable configuration or source exit 2.
8. Prune the baseline only after actual code changes remove findings.

A guard reaching zero because all findings are exempted is not architecture closure.

---

# 4. Critical blocker: PII “strict zero” is produced through unsafe suppressions

PR A properly removed several targeted `printStackTrace`, raw OCR, email, and raw exception-message paths. That part was directionally correct. ([github.com](https://github.com/panospao7/Cost-agregator/commit/d40c230))

However, `eaa59ca` subsequently added PII exemptions for:

- `absolutePath`
- `e.message_logging`
- `e.message_wrap`
- `rawOcrText`

across backup, export, debug, and UI files. Reasons claim paths

:warning: The provider stream ended early, so this response may be incomplete.