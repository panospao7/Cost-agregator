# Local validation throughput tuning

User authorized implementation after the read-only performance evaluation.
Actual checkout: `C:/Users/panos/Desktop/Cost-agregator`; outside this session
write boundary. The old OneDrive checkout has directory remnants only.

Scope: Segment 39 validation tooling. Preserve serial defaults, one validation
owner, durable evidence, fail-closed guards, and existing profile timeouts.
Do not restore unrelated deletions, change Defender, refactor configuration
cache, change CI, or raise shared JVM/worker limits.

Reviewed design:
- Fix GradleDaemon request/worker loss; explicitly use --daemon when selected.
- MaxWorkers 1..16 (default 1), TestForks 1..8 (default 1), ForkEvery 0..1000
  (default 50). Reject forks greater than workers.
- Parallel selects 4 workers/2 forks only where counts were omitted; explicit
  counts win. Project parallelism remains independent of numeric worker counts.
- KotlinCompilerStrategy project/daemon/in-process is a per-run experiment.
  Default project preserves the shared in-process strategy pending measurements.
- Store normalized settings and planned command; reconstruct from immutable
  request, publish actual command, reject disagreement before launching.
- Old schema-1 requests without settings retain historical defaults.
- Reject explicit Gradle options on Python/smoke profiles.
- Add bounded runner-protocol-tests so protocol tests use the validation owner.
- Remove unused kapt property; update runner/build documentation and default
  serial shard description only, no filter or guard-policy changes.

Files staged: runner, protocol tests, gradle.properties, two development guides,
and config/validation/test-shards.json. Apply script verifies baseline hashes
and absence of a validation lock before copying only those files.

Validation: strict independent review before applying. Validation-runner then
runs protocol tests and runner smoke first, followed by focused serial/tuned
Gradle evidence if current checkout supports it. Matched warmed workloads are
required before claiming speedups; repeated runs must use reason-coded
benchmark overrides without bypassing the active lock.

Known limitation: pre-existing working tree has 744 tracked deletions and 33
modifications; old saved build artifacts/logs come from the OneDrive path. Do
not interpret those older logs as post-move timings.

## Applied implementation and validation, 2026-10-14

The six-file reviewed patch was applied to the actual checkout after explicit
approval, with unchanged-baseline hashes checked before copying. Strict
independent review passed before validation. No commit was made. The user then
instructed: **do not use subagents from now on**. Delegated validation was
closed; subsequent work was performed directly, one runner invocation at a
time. Do not spawn further agents for continuation of this task.

Evidence under actual checkout `build/validation-runs/`:
- `vr-20261014-080714-f0733237`: runner-protocol-tests PASS, exit 0;
  194 tests passed in 93.41 seconds. Completion marker present, start/end
  fingerprints match. Includes real detached-worker harmless argv capture.
- `vr-20261014-081214-b314e7b2`: runner-smoke PASS, exit 0; completion
  marker present, start/end fingerprints match.
- `vr-20261014-081520-c25e602e`: default serial compile FAIL, exit 1,
  E_COMMAND_FAILED, approximately 15 seconds including runner overhead.
  Gradle fails at :app:mapDebugSourceSetPaths before Kotlin compilation:
  app/src/main/res/drawable/ic_dismiss.xml cannot be snapshotted as a regular
  file. Completion marker present, start/end fingerprints match.
  Read-only inspection shows Archive, ReparsePoint attributes, no symlink
  target; fsutil query reports tag 0x9000201a. Neighboring drawables also
  retain reparse-point attributes. No resource was restored, deleted,
  hydrated, or otherwise modified by this task.

Each run directory contains result.json, stdout.log, stderr.log, and
complete.marker. Protocol and smoke PASS are tooling evidence, not app
correctness evidence. Default compile failure blocks tuned compile/test
comparisons. Tuned Gradle execution, app tests, full static guards, and
performance benchmarks were NOT RUN after that failure. No speedup claimed.

Plan inspection (no build) confirmed:
- compile -GradleDaemon -Parallel resolves --parallel --max-workers=4,
  two forks, recycling 50, --daemon.
- compile -GradleDaemon -KotlinCompilerStrategy daemon resolves serial
  worker/fork defaults, --daemon, and the daemon compiler property.

Scoped git diff --check passed for all six modified files. The active
validation lock is absent. Shared heap/meta/worker limits, compiler default,
CI, guard policy, and shard filters remain unchanged.

Next step: separately investigate/rematerialize affected cloud/reparse files
while preserving all uncommitted edits/deletions. Do not reset/clean the tree
or delete reparse metadata as an automatic shortcut. Once baseline compilation
passes, perform matched warmed serial/tuned builds and representative multi-
class tests before selecting stronger presets or switching the shared Kotlin
strategy. No Defender exclusions or configuration-cache refactor performed.

## Post-recovery evidence and performance limits - 2026-10-14

The filesystem blocker was subsequently repaired during the Wave 5 continuation:
746 missing files were restored and 1,702 build inputs rematerialized without
changing bytes. The original compile failure above is historical, not current.
The performance changes remain separate, uncommitted and excluded from Wave 5.

The patched runner successfully completed real app workloads in the repaired
main checkout, but ONLY with historical serial defaults: max_workers=1,
test_forks=1, fork_every=50, parallel=false, gradle_daemon=false and the project
in-process Kotlin compiler. These runs prove default-mode operation, not tuned
mode speed or correctness:
- vr-20261014-102859-8597dce5: compile PASS, 164 seconds including runner
  overhead; Kotlin task executed, but nine other tasks came from cache.
- vr-20261014-103949-5baf47d6: app-check PASS, 1,674 seconds; both unit suites
  executed, 7,920 tests each, zero failures.
- vr-20261014-112957-42996321: static-guards PASS, 987 seconds; 25/25 gates.
  Guard tests took 657 seconds, known-good-state 150 seconds, DB guard 137
  seconds. Gradle options do not configure the separate Python guard suite.
These are observed single-run timings with differing inputs/cache states, not
matched benchmarks or cold-build measurements. Candidate-only Wave 5 runs
used the committed baseline runner and explicitly excluded this tuning patch.

Machine inspected: Ryzen 7 9800X3D, eight physical cores/16 logical processors,
approximately 32 GB installed RAM. Shared Gradle heap remains 4 GB, default
test JVM heap 2 GB, and Kotlin compiler strategy in-process. No hardware-wide
maximum-throughput preset has been established.

Still NOT RUN: matched serial-versus-tuned app benchmarks, real app execution
with persistent daemon/parallel workers/multiple test forks, and the separate
Kotlin-daemon experiment. The 194 protocol tests and smoke test verify option
forwarding/safety, not end-to-end speedups.

Next bounded performance task: benchmark identical source and comparable
cache/workloads for compilation and representative multi-class test shards;
measure CPU/RAM and correctness before choosing worker/fork/recycling settings.
Evaluate Python guards separately because Gradle parallelism does not speed
them up. Preserve the one-validation-owner lock, all tests, and fail-closed
guards. No speedup percentage is claimed.

## Controlled benchmark study - 2026-10-14

The runner protocol was expanded with three local-only benchmark profiles:
`benchmark-compile`, `benchmark-test-shard`, and `benchmark-app-check`.
They force the measured Gradle tasks to execute with `--rerun`, disable build
cache reuse, emit Gradle/Kotlin profiles, preserve all selected tests/checks,
and keep the validation lock. Normal profiles/defaults were not changed.
Runner regression validation passed: `vr-20261014-141940-6924df15`, 200 tests.

Study checkout: `C:/Users/panos/codex-validation/Cost-agregator-perf-20261014-171646`.
All benchmark source/settings were hash-matched to the working tree. Machine:
Ryzen 7 9800X3D, 8 cores/16 logical processors, ~31.1 GiB RAM. Evidence:
`build/validation-runs/performance-20261014-study.json`.

### Compile results

Forced full `compileDebugKotlin` runs, excluding warm-ups:
- serial/in-process: **97s, 97s**;
- Gradle daemon + in-process: **90s, 91s**;
- Gradle/Kotlin daemon, one worker: **72s, 64s, 62s, 62s**;
- Gradle/Kotlin daemon, four workers: **62s, 62s, 63s**.

The Kotlin daemon is the meaningful compile improvement: approximately 62–63s
versus 97s serial, about 35–36% faster in this forced, warmed dependency-cache
study. Four Gradle workers did not materially improve compilation. Kotlin-daemon
runs peaked around 8.7–9.3 GiB combined local Java/Python private memory and
left roughly 9.6–10.2 GiB available RAM in the samples; use this as a local
machine observation, not a universal memory guarantee.

### Test-shard results

The same test identities were verified from UTF-8 JUnit XML for every run:
- architecture-contracts: 416 tests; one fork ~99s, two ~82s, four ~80s;
- data: 1,684 tests; one fork ~189s, two ~143s, four ~114s.

All runs passed with zero failures/errors. Four test forks are the best measured
tradeoff so far. A small recycling comparison on architecture-contracts showed
`ForkEvery 100` at 88s versus `ForkEvery 0` at 93s, with lower observed memory;
this comparison had one run per setting and is directional rather than a full
median study.

### Guard and full-suite correctness results

The separate Python static guard suite passed all 25 guards in
`vr-20261014-153718-55e5a8d1` (1,029s). Gradle worker/fork settings do not
accelerate these Python guard processes; guard_tests, known_good_state and
db_access remain the dominant legs.

The selected local-fast preset was then verified with the forced full app-check:
`vr-20261014-160051-9e4fd87f`, PASS/0, 1,022s, using Gradle daemon, Kotlin
daemon, four Gradle workers, four test forks and `ForkEvery 100`. Debug and
release each executed 7,920 tests with zero failures/errors (276/277 skips).
`:app:check` completed successfully. Ten pre-existing configuration-cache
compatibility warnings were reported but did not fail the build; no guard or
test was skipped.

Recommended local preset, based on these measurements:
`-GradleDaemon -Parallel -MaxWorkers 4 -TestForks 4 -ForkEvery 100 -KotlinCompilerStrategy daemon`.
Use it locally only; serial defaults remain the safer default/CI posture. The
full app-check timing is not a strict apples-to-apples speedup percentage
against the historical serial run because cache/task state differed, while the
matched shard and forced compile comparisons are the reliable measurements.

No further performance validation was run after `vr-20261014-160051-9e4fd87f`,
per user instruction. No performance files were committed or pushed.
