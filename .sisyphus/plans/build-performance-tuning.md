# Build Performance Tuning (Local Machine Optimization)

## Goal

Maximize local compilation/test/guard throughput on a high-end machine (Ryzen 7 9800X3D, 8C/16T, 32 GB RAM, NVMe) without weakening the documented serial reliability defaults of the validation pipeline. Configuration-cache refactor is explicitly OUT OF SCOPE (tracked as follow-up).

## Current-state findings (verified)

- Machine: Ryzen 7 9800X3D (16 threads), 32 GB RAM (~13 GB free at survey), NVMe SSD, Java 17 Temurin.
- Repo lives inside `C:\Users\panos\OneDrive\Desktop\Cost-agregator` — OneDrive file I/O interception + Defender scanning slow builds and can cause file-lock flakes.
- `gradle.properties`: daemon heap only 4 GB (`-Xmx4096m`), no `org.gradle.workers.max`, no `kotlin.daemon.jvmargs`, dead `kapt.incremental.apt=true` (KSP-only project), `kotlin.compiler.execution.strategy=in-process` (compiler shares daemon heap — so daemon heap matters a lot).
- `scripts/validation-runner.ps1` hard-codes `--no-parallel --max-workers=1`, `-PvalidationMaxParallelForks=1`, `-PvalidationForkEvery=50`, and `--no-daemon` (opt-out only via existing `-GradleDaemon` switch). Every local run is fully serial despite 16 threads.
- Unit-test forks default to `availableProcessors / 2` in `app/build.gradle.kts`, but runner overrides to 1 fork.
- Config-cache enabled, but `:app:check` has 13 unresolved CC problems (5 custom guard tasks `notCompatibleWithConfigurationCache` + `exec{}` bridge) — CC never benefits the heaviest profile. Deferred to follow-up.

## Design decisions (user-approved)

1. **OneDrive**: configure exclusions in place (no repo move). Be honest that OneDrive cannot exclude arbitrary subfolders — plan uses: (a) OneDrive "Choose folders" deselect guidance, (b) Windows Defender exclusions for build dirs (biggest real win), (c) optional follow-up note about relocating.
2. **Runner**: add **opt-in** fast mode. Defaults stay fully serial; new parameters unlock parallelism. No change to default policy in AGENTS.md/docs beyond documenting new opt-in flags.
3. **Config-cache fix**: deferred; recorded as follow-up only.

## Batches

### Batch 1 — `gradle.properties` resource tuning (fast mode, low risk)

File: `gradle.properties`

- Raise daemon heap and metaspace for the in-process Kotlin compiler:
  - `org.gradle.jvmargs=-Xmx8192m -XX:MaxMetaspaceSize=2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8`
  - Rationale: `in-process` Kotlin strategy compiles inside the 4 GB daemon; 8 GB leaves headroom for KSP/Hilt/Room + workers without swapping (32 GB total; leave room for tests + OS).
- Add `org.gradle.workers.max=12` (leave 4 threads headroom for test JVMs and OS).
- Add `org.gradle.daemon.idletimeout=7200000` (keep warm daemon 2 h for rapid iterations when `-GradleDaemon` is used).
- Remove dead `kapt.incremental.apt=true` (no kapt plugin applied anywhere).
- Keep `kotlin.compiler.execution.strategy=in-process` (fastest; heap bump above compensates).
- Keep `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true` as-is.

Risk: low. Heap figures conservative vs 32 GB. No behavior change for correctness.

### Batch 2 — validation-runner opt-in fast mode (medium risk, main win)

File: `scripts/validation-runner.ps1`

Add parameters (defaults preserve current serial behavior exactly):

- `[ValidateRange(1,16)][int]$MaxWorkers = 1` → appends `--max-workers=<n>`; when > 1, drop `--no-parallel` and set `-PvalidationMaxParallelForks=<forks>`.
- `[ValidateRange(1,16)][int]$TestForks = 1` → maps to `-PvalidationMaxParallelForks` and `validationForkEvery` stays 50 (or add `[int]$ForkEvery = 50`).
- `[switch]$Parallel` → convenience preset: sets MaxWorkers=8, TestForks=8 unless explicitly overridden.
- Keep existing `-GradleDaemon` as-is (warm daemon + parallel is where the big repeat-run wins are).

Implementation notes:

- `$gradleTail` construction: append `--max-workers=N`; omit `--no-parallel` when `$MaxWorkers -gt 1`; always keep `--console=plain`.
- The `-PvalidationMaxParallelForks`/`-PvalidationForkEvery` entries currently in `$gradleTail` become parameterized.
- Help text must state: fast mode is for capable local machines; CI and default runs remain serial. Do not change default profile timeouts.
- Heap for test JVMs: allow `-PtestMaxHeapSize` passthrough already exists in `app/build.gradle.kts`; no change needed there. Optionally document `-TestMaxHeapSize` later — out of scope.

Docs (same batch, minimal):

- `docs/development/VALIDATION_RUNNER.md`: add short "Opt-in fast mode" subsection documenting `-Parallel`, `-MaxWorkers`, `-TestForks`, and the caveat that serial remains the default/CI posture.
- `AGENTS.md`: no policy change; optionally one line under Compilation noting fast-mode switches exist for local capable machines. (Keep diff minimal — only if it fits existing wording; otherwise skip AGENTS.md.)

Risk: medium — parallel test execution can expose order-dependent tests. Mitigation: opt-in only; user runs full `unit-tests` in fast mode once to smoke-test before relying on it.

### Batch 3 — OneDrive + Defender exclusions (low code risk, manual steps)

No repo code changes required; deliver as applied system config + short doc:

1. **Windows Defender exclusions** (biggest measurable win; requires admin):
   - Exclude directories: repo `build\`, repo `.gradle\`, `%USERPROFILE%\.gradle`, Android SDK path (from `local.properties` sdk.dir), and `%LOCALAPPDATA%\Android\Sdk`.
   - Command shape: `Add-MpExclusion -Path <dir>` — run via an explicit, user-approved elevated command (never silently).
2. **OneDrive**:
   - OneDrive → Settings → Sync and back up → Choose folders: deselect `Cost-agregator` (or at least confirm `.gradle`/`build` outputs are not being synced). Honest caveat: OneDrive does not support subfolder-level exclusions; unchecking removes that folder from cloud sync while keeping files local in most current client versions — user confirms files remain local after the change.
   - Guard files: verify `build/` and `.gradle/` are not locked/creating sync churn after a build (check OneDrive activity).
3. Optional follow-up note (not this task): relocate repo to `C:\dev\Cost-agregator` for full isolation.

Doc: add a concise section to `docs/development/BUILD_GUIDE.md (which is also stale re: "Gradle 7.x" — fix that one line while touching the file) covering Defender/OneDrive exclusions and recommended local fast-mode invocations, e.g.:

```
scripts\validation-runner.ps1 -Profile compile -GradleDaemon -Parallel
scripts\validation-runner.ps1 -Profile unit-tests -GradleDaemon -Parallel -TestForks 8
```

Risk: low for repo; Defender changes need admin consent and are performed visibly.

## Verification (Batch 4)

All via validation-runner only (per AGENTS.md):

1. `scripts/validation-runner.ps1 -Profile compile` (default, serial) → must PASS — proves defaults unchanged.
2. `scripts/validation-runner.ps1 -Profile compile -GradleDaemon -Parallel` → must PASS — proves fast mode works, config-cache still OK for compile.
3. `scripts/validation-runner.ps1 -Profile targeted-unit-test -GradleDaemon -Parallel -TestForks 4` with an existing filter (e.g. `*WorkerExecutionGuard*`) → must PASS.
4. Report wall-clock times for serial vs fast-mode compile to quantify the win.
5. Static guards are Python-only (no Gradle impact) — no change needed; spot-run one cheap registered guard only if runner changes touch shared code paths (they do not beyond ps1).

## Out of scope / follow-ups (record, do not implement)

- Fix 13 configuration-cache problems (migrate 5 guard tasks + `exec{}` bridge to CC-compatible patterns) so `:app:check` benefits from CC — separate strict batch.
- CI workflow (`ci.yml`) Metaspace inconsistency (512m vs 1024m) and mixed `--no-daemon` usage — separate, low priority.
- Repo relocation out of OneDrive.

## Files touched

- `gradle.properties` — heap/metaspace, workers.max, daemon timeout, remove dead kapt key
- `scripts/validation-runner.ps1` — opt-in `-Parallel`/`-MaxWorkers`/`-TestForks` parameters
- `docs/development/VALIDATION_RUNNER.md` — document fast mode
- `docs/development/BUILD_GUIDE.md` — fix stale Gradle 7.x line + local perf/exclusions section
- System config (visible, user-approved): Defender exclusions; OneDrive folder deselect guidance
