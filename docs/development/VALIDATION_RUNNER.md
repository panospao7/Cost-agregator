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

- `request.json`: immutable requested profile, bounded inputs, effective
  `gradle_settings`, and resolved command;
- `result.json`: current state, timestamps, command, exit code, fingerprints,
  effective `gradle_settings`, heartbeat/output timestamps, byte counters, and
  log paths;
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

`Plan`, `request.json`, and `result.json` expose the same effective
`gradle_settings`: `max_workers`, `test_forks`, `fork_every`, `parallel`,
`gradle_daemon`, and `kotlin_compiler_strategy` (`null` for non-Gradle profiles).
The detached worker revalidates the serialized settings and reconstructs the
command. If a recorded request command differs, it fails closed with
`E_COMMAND_MISMATCH` before launching the child. Older schema-1 requests without
`gradle_settings` retain serial defaults, not the parallel preset; malformed
present settings are rejected rather than silently defaulted.

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
| `runner-protocol-tests` | Run isolated Windows runner protocol regression tests through Python pytest |
| `compile` | Compile debug Kotlin |
| `assemble-debug` | Build the debug APK |
| `targeted-unit-test` | Run `:app:testDebugUnitTest` with `-TestFilter` |
| `targeted-release-unit-test` | Run `:app:testReleaseUnitTest` with `-TestFilter` |
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

Gradle profiles default to `--console=plain --no-parallel --max-workers=1
--no-daemon`, one test fork at a time, and fork recycling after 50 **test
classes**, not test methods. Optional local settings are described below.

Guard profiles and `runner-protocol-tests` require a working Python interpreter.
Resolution tries `-PythonExecutable`, `VALIDATION_PYTHON`, then `python3` and
`python` on PATH. The runner never installs Python or pytest. The protocol-test
profile requires pytest already installed in that interpreter and executes
`-m pytest scripts/ci/test_validation_runner_protocol.py -q -p no:cacheprovider`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Start `
  -Profile runner-protocol-tests -PythonExecutable python
```

Available shards are `architecture-contracts`, `data`, `domain-a-m`,
`domain-n-z`, `runtime-ui`, and `integration-golden`. Serial execution is the
local default; optional concurrency is inside one runner invocation, not
multiple simultaneous shard runs. CI may run shards concurrently only in
isolated workspaces. Shard filters are unchanged by tuning settings.

The hanging-test ledger is `config/validation/known-hanging-tests.json`; see
`docs/testing/KNOWN_HANGING_TESTS.md`. It changes routing only and never weakens
the ignored-test guard.

## Per-run Gradle settings

| Option | Default | Bounds and behavior |
|---|---|---|
| `-MaxWorkers` | `1` | `1..16`; Gradle worker limit |
| `-TestForks` | `1` | `1..8`; maximum concurrent test JVMs, must be at most `MaxWorkers` |
| `-ForkEvery` | `50` | `0..1000` **test classes** per test JVM before recycling; `0` means unlimited classes/no recycling |
| `-Parallel` | Off | Emits project/task `--parallel`; presets omitted `MaxWorkers` to `4` and omitted `TestForks` to `2` |
| `-GradleDaemon` | Off | Emits explicit `--daemon`; otherwise explicit `--no-daemon` |
| `-KotlinCompilerStrategy` | `project` | `project`, `daemon`, or `in-process`; compiler override for this run only |

`-Parallel` supplies only omitted numeric worker/fork counts. Explicit values
win, even `1`: `-Parallel -MaxWorkers 1 -TestForks 1` keeps both counts at one
while still emitting `--parallel`. The fork/worker constraint always applies;
`-Parallel -MaxWorkers 1` alone would preset two forks and be rejected. Numeric
counts alone do not imply project parallelism: without `-Parallel` the runner
emits `--no-parallel`, even with multiple workers or test forks.

`-GradleDaemon` is an explicit opt-in, not merely removal of `--no-daemon`. Its
effective value is persisted in the request and reused by the detached worker.
It may keep a Gradle daemon warm across sequential runs, but never permits
overlapping runner processes. A daemon can survive timeout termination; report
any subsequent lock issue to the validation owner rather than starting another
build.

The Kotlin compiler strategy is independent of Gradle daemon selection.
`project` sends no compiler override and leaves `gradle.properties` in control
(currently `in-process`). `daemon` and `in-process` send the corresponding
`-Pkotlin.compiler.execution.strategy=...` override. The effective strategy is
serialized with the other settings; none of these options edit project files.

Explicit Gradle-only options are rejected on non-Gradle profiles, including
smoke profiles, `runner-protocol-tests`, and guard profiles, with
`E_GRADLE_OPTIONS_NOT_APPLICABLE`. This includes explicitly passing a default
value such as `-MaxWorkers 1` or `-KotlinCompilerStrategy project`.

### Local opt-in examples

Run these from the working checkout, `C:\Users\panos\Desktop\Cost-agregator`
(outside OneDrive). `Plan` resolves settings without starting validation;
`Start` launches the job. Poll each started run to a terminal result before
starting another.

```powershell
# Warm/fast compile candidate: opt-in settings, not a measured speedup
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Plan `
  -Profile compile -GradleDaemon -Parallel
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Start `
  -Profile compile -GradleDaemon -Parallel

# First parallel test experiment: architecture-contracts, four workers/two forks
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Plan `
  -Profile unit-test-shard -Shard architecture-contracts -Parallel -TestForks 2
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Start `
  -Profile unit-test-shard -Shard architecture-contracts -Parallel -TestForks 2

# Separate Kotlin-daemon experiment; worker/fork counts stay at serial defaults
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Plan `
  -Profile compile -GradleDaemon -KotlinCompilerStrategy daemon
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/validation-runner.ps1 -Action Start `
  -Profile compile -GradleDaemon -KotlinCompilerStrategy daemon
```

These settings establish neither a measured speedup nor full-suite parallel
safety. Each test JVM has a default `2g` maximum heap, in addition to Gradle and
compiler memory; eight forks is an upper bound, not the default. Start with the
architecture shard, but full-suite evidence is still needed before relying on
parallelism.

Benchmark matched, warmed no-op compiles, representative edit/recompile runs,
and identical test workloads separately. Repeat each configuration and compare
medians, recording commands, effective settings, run IDs, and cache/daemon
state. Do not compare a serial cold run with a fast-mode warm run. Use the
controlled duplicate-run override below only with an approved, recorded reason
when repeated measurements require it; serialization still applies.

Measure Defender/file-I/O contribution first; do not disable protection, add
exclusions, or make other system changes as part of this tuning. Guard-task
configuration-cache refactoring is deferred; do not skip guards to obtain a
faster run.

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
This overrides duplicate-result reuse checks only, never the global lock or
external-Gradle-client check: one runner process at a time remains mandatory.

## Controlled local benchmarks

The opt-in benchmark profiles keep the same lock, evidence, timeouts and
fail-closed command reconstruction as normal validation:
- `benchmark-compile`: reruns only `compileDebugKotlin`, disables build-cache
  reuse and Kotlin incremental compilation, and writes Gradle/Kotlin reports.
  This measures full compilation with warmed dependencies, not a cold build or
  an incremental edit. Normal `compile` settings are unchanged.
- `benchmark-test-shard -Shard <name>`: forces the selected test task to rerun
  with exactly the named manifest filters and no build-cache reuse.
- `benchmark-app-check`: forces both complete debug/release unit suites and
  runs `:app:check`; no tests, guards or lint tasks are excluded.

Repeated identical measurements use `-AllowOverlap -OverlapReasonCode
HUMAN_REQUEST` only to bypass successful-result deduplication; this never
bypasses the active lock. Keep source, dependency cache and compiler/worker
settings recorded, discard initial warm-up from comparisons, and check that
the measured target actually executed. No deletion of build outputs or caches
is necessary. `--profile` writes local reports; no build scan is uploaded.

## Guard safety

Guard profiles are verification-only. They must not generate or promote
baselines, broaden allowlists, add exceptions, or mutate guard policy. The
fail-closed, no-weakening, and self-protection rules remain authoritative in
`FINAL_CI_GUARD_ACCEPTANCE_GATE.md` (FG-03, FG-06, FG-07, FG-23).
