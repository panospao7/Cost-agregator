# Serialized Validation Runner

## Purpose

`validation-runner` is the sole agent allowed to execute compilation, tests,
lint, connected tests, or static guards. General coders, reviewers, static
testers, and debuggers remain non-executing so parallel agent work cannot start
overlapping Gradle processes.

The runner also avoids depending on a long-lived OpenCode terminal call. A
short `Start` command launches a detached worker and returns a run ID. Later
`Status` calls read the durable result from disk.

## Entry point

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Start -Profile compile
```

Do not invoke Gradle or guard scripts directly from an agent.

## Durable protocol

Each run writes under `build/validation-runs/<run-id>/`:

- `request.json`: immutable requested profile and bounded inputs;
- `result.json`: current state, timestamps, command, exit code, fingerprints,
  heartbeat/output timestamps, byte counters, and log paths;
- `stdout.log` and `stderr.log`: command output kept outside agent context;
- `complete.marker`: written only after the child process exits or is
  terminated.

`build/validation-runs/active.lock.json` serializes execution. A missing,
invalid, orphaned, timed-out, stale, or infrastructure-error result is never a
pass.

Terminal statuses are:

- `PASS`
- `FAIL`
- `TIMEOUT`
- `STALE_RESULT` (tracked/untracked worktree content changed during the run)
- `INFRA_FAILURE`

`STARTING` and `RUNNING` are non-terminal. Poll them; do not launch a second
run.

While running, `result.json` is refreshed approximately every five seconds.
`last_heartbeat_at` proves the detached worker is alive; `last_output_at` and
the byte counters show command progress. Each profile has both an absolute
timeout and a shorter no-output timeout. A no-output timeout records
`E_NO_OUTPUT_TIMEOUT` and `timeout_kind=no_output`.

## Commands

```powershell
# Available profiles
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Profiles

# Resolve a profile without starting it
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Plan `
  -Profile unit-test-shard -Shard architecture-contracts

# Poll a run (exit 3 means still running)
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Status -RunId <run-id>

# Bounded polling window; the detached job continues after this returns
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Wait -RunId <run-id> `
  -MaxWaitSeconds 30

# Recent runs
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action List
```

## Profiles

| Profile | Use |
|---|---|
| `runner-smoke` | Test only the detached runner protocol |
| `runner-progress-smoke` | Self-test heartbeat and output-byte progress |
| `runner-stall-smoke` | Self-test no-output termination; TIMEOUT is expected |
| `compile` | Compile debug Kotlin |
| `assemble-debug` | Build the debug APK |
| `targeted-unit-test` | Run `:app:testDebugUnitTest` with `-TestFilter` |
| `unit-test-shard` | Run one named shard from `config/validation/test-shards.json` with `-Shard` |
| `trusted-tests` | Run the curated fast structural group; this does not exempt other tests |
| `legacy-tests` | Isolate active ledger entries without ignoring them |
| `unit-tests` | Run all debug unit tests |
| `migration-tests` | Run migration-filtered unit tests |
| `lint` | Run debug lint |
| `static-guards` | Run the canonical static guard suite |
| `registered-guard` | Run one `-GuardId` through the canonical CI-mode bridge |
| `app-check` | Run `:app:check` |
| `connected-tests` | Run connected debug Android tests |

Gradle profiles use `--console=plain --no-parallel --max-workers=1
--no-daemon`, one test fork at a time, and fork recycling after 50 tests.
Guard profiles require a working Python interpreter. Set
`VALIDATION_PYTHON` or provide `-PythonExecutable`; the runner never installs or
guesses missing tooling.

Available shards are `architecture-contracts`, `data`, `domain-a-m`,
`domain-n-z`, `runtime-ui`, and `integration-golden`. They are serial locally;
CI may parallelize them only in isolated workspaces.

The hanging-test ledger is `config/validation/known-hanging-tests.json`; see
`docs/testing/KNOWN_HANGING_TESTS.md`. It changes routing only and never weakens
the ignored-test guard.

## Workflow gate

Normal slice:

```text
coder -> tester-static/test author -> reviewer -> validation-runner
```

Risky slice:

```text
scout -> guardian -> coder -> static test gate -> strict reviewer
      -> validation-runner -> debugger/coder on failure -> repeat gates
```

Before final handoff or commit, run the narrowest profiles applicable to the
diff. A failure is routed to `ci-build-debugger` or the coder using the run ID
and persisted logs. After any fix, create a new validation run.

The runner rejects a duplicate successful profile on an unchanged worktree.
`app-check` and `static-guards` are distinct gates; do not assume one covers the
other. Run both when both are required, even on an unchanged worktree.
Use `-AllowOverlap` only with a controlled `-OverlapReasonCode` when the reason
is explicit and reported in the handoff. Accepted codes are
`REQUIRED_FINAL_GATE`, `INVESTIGATE_INCONSISTENT_RESULT`, `HUMAN_REQUEST`, and
`RUNNER_SELF_TEST`.

## Guard safety

Guard profiles are verification-only. They must not generate or promote
baselines, broaden allowlists, add exceptions, or mutate guard policy. The
fail-closed, no-weakening, and self-protection rules remain authoritative in
`FINAL_CI_GUARD_ACCEPTANCE_GATE.md` (FG-03, FG-06, FG-07, FG-23).
