# DB Guard Evidence Protocol

Diagnostic-only, reproducible evidence workflow for the DB access guard at one
caller-pinned exact Git SHA (`--expected-sha`). This protocol is defined by
`scripts/ci/capture_db_guard_evidence.py` and is **not** an architecture guard.
It never mutates policy, baseline, config/guards, production Kotlin, Gradle,
workflow, scanner, or ratchet files.

The capture records whatever state exists at the pinned SHA — it never edits
policy to change it. At the latest trusted double capture the DB gate was
observed exit 0 / trusted with 0 findings and 20 advisory diagnostics (SHA
565018c5eed61fae4351cb59342dc5c274eb27e7, record
`gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7` in
docs/ci/GUARD_EVIDENCE_INDEX.yml); earlier captures observed a blocked gate
and recorded that truthfully.

## No trusted evidence bundle without two human clean captures

**No trusted evidence bundle exists until a human performs two clean captures at
the same caller-stated SHA (`--expected-sha`).** A single capture, a dirty
capture, an `--allow-dirty` capture, a capture whose preflight /
required-input/required-artifact checks failed closed, a capture rejected by the
run-pin gate or the post-capture drift re-check, or a capture that ended
incomplete (exit `2`, e.g. an INCOMPLETE command log) is **never** a trusted
bundle and must not be described as evidence of a green or passing state. The
capture tool is diagnostic-only; it observes and records, it does not certify.
A trusted bundle requires a human operator to:

1. run the capture twice at a clean checkout of the exact caller-stated SHA
   (`--expected-sha <40-lowercase-hex>`);
2. confirm both runs exited `0` and both `semantic-summary.json` files are
   byte-identical; and
3. confirm the DB gate remains blocked (observed child status, not a capture
   success) and no `infrastructure_warnings` indicate a failed-closed condition.

Until those two clean captures are performed and compared by a human, this
protocol makes **no claim that evidence exists** and **no claim of green
status**. Any prose ledger row is an index of a capture that already happened,
not proof of a later SHA's state.

## Caller-stated run pin (`--expected-sha`)

There is deliberately **no hard-coded target SHA**. The expected commit is a
per-run pin supplied by the caller:

- `--expected-sha <40-lowercase-hex>` is **mandatory** and must be exactly 40
  lowercase hexadecimal characters; the CLI fails closed (exit `2`) before any
  command is issued otherwise. It is **never derived from HEAD** — the caller
  must state it (a pin derived from HEAD would be tautological).
- **Pre-launch equality gate**: before any matrix command starts, the observed
  `git rev-parse HEAD` must equal the requested pin. On mismatch the capture
  fails closed pre-launch (exit `2`, `wrong-sha:<observed>`): the observed git
  state is still recorded, but no matrix command, artifact hash, or report
  validation is ever performed against an unpinned commit. A missing pin warns
  `missing-expected-sha`; a syntactically invalid pin warns
  `invalid-expected-sha` (the invalid value itself is never persisted).
- **Recorded identity**: `git-state.json` and `evidence.json` both record
  `requested_sha` (the caller-stated pin), `observed_sha` (the observed HEAD),
  and `tree_sha` (the observed `HEAD^{tree}`); `semantic-summary.json` records
  `requested_sha`, which is run-invariant, so byte-identical comparison across
  two runs at the same SHA still holds.
- **Post-capture drift re-check**: after the matrix ran, HEAD, tree, and
  porcelain status are re-observed. Any drift — or an unverifiable post-state —
  marks the bundle incomplete/untrusted and fails the capture (exit `2`) with
  `post-capture-drift:<surface>`, where `<surface>` is `head`, `tree`,
  `status`, or `unverifiable`. Status is compared after the same sanitization
  applied to the preflight record, so both sides are deterministic. The
  re-check is skipped when the pre-launch gate already blocked the launch
  (nothing ran, so nothing can have drifted).

## GATE-00R extension: base/merge-base/branch + platform preflight identity

Additive extension (existing output contracts unchanged; new fields only):

- **Mandatory base pin (`--base-ref`)**: like `--expected-sha`, the base ref is
  a caller-stated, mandatory pin that must be exactly 40 lowercase hexadecimal
  characters and is never derived from git state. A missing pin warns
  `missing-base-ref`; a syntactically invalid pin warns `invalid-base-ref`;
  both fail closed **before any command is issued** (zero runner calls).
- **Preflight resolution**: preflight resolves `git rev-parse <base-ref>`
  (recorded as `base_sha`), `git merge-base HEAD <base-ref>` (recorded as
  `merge_base_sha`), and `git branch --show-current` (recorded as a bounded,
  sanitized `branch` value; empty output on a detached HEAD — exit 0 — is the
  observation `branch: null`, not a failure). The caller-stated pin itself is
  recorded as `base_ref` next to `requested_sha` in `git-state.json`,
  `evidence.json`, and — for the resolved `base_sha` / `merge_base_sha` —
  additively in `semantic-summary.json` (run-invariant, so byte-identical
  two-run comparison still holds).
- **Base-identity launch gate**: an unresolved `base_sha` or `merge_base_sha`
  (nonzero exit, launch error, or a non-40-hex value) is a launch gate exactly
  like the run-pin gate: no matrix command starts, the capture fails closed
  (exit `2`), and the failure is diagnosed via `git-meta-failed:base-sha` /
  `git-meta-failed:merge-base`. A branch lookup failure is fatal the same way
  (`git-meta-failed:branch`) but does not block the launch — it is an
  observability defect, not an identity defect.
- **Platform identity fields**: `git-state.json` additionally records bounded,
  redacted `locale` (`locale.getlocale()`), `timezone` (`time.tzname`), and
  `os_identifier` (`platform.platform()`) values — each redacted (secrets and
  absolute/UNC/backslash path forms) and length-bounded, with a lookup failure
  resolving to `null`. No username, home path, token, or environment dump is
  collected.
- **Extended command matrix**: four rows are **appended** after the existing
  eight (order and argv below; all existing rows unchanged): `time-direct`
  (direct time-boundary guard), `time-tests` (time-guard test module, pinned
  `-p no:cacheprovider` like every pytest row), `db-inventory` (a second
  inventory-only DB run writing `reports/db-inventory.json` +
  `reports/room-mutators.json` inside the bundle, both declared as required
  hashed artifacts), and `gradle-compile` (`:app:compileDebugKotlin`,
  sequential). The capture pre-creates the bundle-internal `reports/` directory
  because the findings writer requires its target's parent to exist; the bundle
  lives under the git-ignored `build/` tree, so this is never an untracked
  working-tree side effect. The hashed input manifest additionally covers the
  new command inputs (`scripts/verify_time_boundaries.py`,
  `scripts/test_verify_time_boundaries.py`,
  `config/guards/time_boundary_exceptions.yml`) when they are tracked at the
  tested SHA.

## Capture exit codes: truthful observation vs failed capture

The capture tool itself exits `0` or `2` — never `1`:

- **Exit `0` — the guard failed truthfully (or passed)**: the capture completed,
  every required artifact is present, every command log is COMPLETE, and the
  pinned identity held for the whole run. Expected nonzero child exits (the
  `db-cli` gate exiting `2`, the blocked Gradle task, the ratchet observation)
  are stored observations; child exit codes are preserved verbatim in
  `evidence.json` and never alter the capture tool's own exit code.
- **Exit `2` — capture failed/incomplete**: the bundle is **not** evidence of
  anything and must not be indexed as such. Causes include: a dirty checkout
  without `--allow-dirty`; a missing or invalid `--expected-sha`; observed HEAD
  ≠ requested pin (pre-launch); post-capture HEAD/tree/status drift; any
  INCOMPLETE command log; a launch failure; a missing required input/artifact;
  an invalid required report; a containment/matrix-validation violation; a
  bounded-collection overflow; or a top-level output-hash failure.

## Output directory layout

```
build/guard-evidence/<full-sha>/<run-id>/
  evidence.json
  summary.md
  input-manifest.json
  input-sha256.txt
  environment.json
  git-state.json
  output-sha256.txt
  semantic-summary.json
  commands/
    00-registry.log
    01-focused-python-tests.log
    02-room-inventory.log
    03-db-cli.log
    04-db-ratchet.log
    05-static-suite.log
    06-gradle-db.log
    07-gradle-task-graph.log
    08-time-direct.log
    09-time-tests.log
    10-db-inventory.log
    11-gradle-compile.log
  02-room-inventory.findings.json
  02-room-mutators.json
  03-db-cli.findings.json
  04-db-ratchet.summary.json
  05-static-suite/
  reports/
    db-inventory.json
    room-mutators.json
```

Command **logs** are written under `commands/` (repository-relative). Required
**report artifacts** (room-inventory findings/mutators, the DB CLI report, the
ratchet summary, and the static-suite directory tree) are written at the
**bundle root**, not under `commands/`. `evidence.json` records each report's
repository-relative `report_path` (bundle-root relative) and its `report_sha256`.

`run-id` may contain a timestamp, but `semantic-summary.json` excludes
timestamps, durations, machine paths, Gradle cache paths, and transient temp
file names so two clean runs at the same SHA compare equal.

The evidence output lives under the git-ignored `build/guard-evidence/`
directory. Raw logs, local absolute paths, and generated reports are never
committed.

## Evidence JSON schema (`evidence.json`)

Top-level keys:

| Key | Type | Notes |
| --- | --- | --- |
| `schema` | string | `db-guard-evidence/v1` |
| `captured_at_utc` | string | Volatile; excluded from semantic summary |
| `tool` | string | `capture_db_guard_evidence.py` |
| `root` | string | Repository-relative bundle root |
| `commit` | string? | Git SHA (from `git rev-parse HEAD`) |
| `tree` | string? | Git tree (from `git rev-parse HEAD^{tree}`) |
| `requested_sha` | string? | Caller-stated `--expected-sha` run pin (never derived from HEAD; `null` when the pin itself was missing/invalid) |
| `observed_sha` | string? | Observed `git rev-parse HEAD` (mirrors `commit`) |
| `tree_sha` | string? | Observed `git rev-parse HEAD^{tree}` (mirrors `tree`) |
| `base_ref` | string? | Caller-stated `--base-ref` base pin (GATE-00R; `null` when missing/invalid) |
| `base_sha` | string? | Resolved `git rev-parse <base-ref>` (GATE-00R; `null` when unresolved) |
| `merge_base_sha` | string? | Resolved `git merge-base HEAD <base-ref>` (GATE-00R; `null` when unresolved) |
| `trusted` | bool | Bundle trusted-state (see below) |
| `allow_dirty` | bool | Whether `--allow-dirty` was passed |
| `dirty` | bool | Whether the checkout was dirty |
| `preservation` | object | `{ok, policy_ok, production_ok, staged_ok, untracked_ok, checked_paths, forbidden_changed}` |
| `environment` | object | Redacted environment snapshot |
| `git_state` | object | Preflight git/version capture; includes `preflight_ok`, `preflight_commands` (bounded, sanitized records), and the run-pin identity `requested_sha` / `observed_sha` / `tree_sha` |
| `input_manifest` | array | One entry per hashed input (includes `scripts/db_guard/*.py` and all policy/baseline/config inputs) |
| `input_manifest_sha256` | string | SHA-256 of canonical manifest JSON |
| `commands` | array | One record per command (see below) |
| `infrastructure_warnings` | array | Missing test files / missing artifacts |

Per-command record:

| Key | Type | Notes |
| --- | --- | --- |
| `id` | string | Stable command ID |
| `argv` | array | **Argv as an array, never shell text** |
| `cwd` | string | Always `.` (repository-relative) |
| `start_utc` / `end_utc` | string | Volatile |
| `elapsed_ms` | int | Volatile |
| `exit_code` | int? | Child exit code; `null` on launch failure |
| `log_path` | string | Repository-relative combined-log path |
| `log_sha256` | string | SHA-256 of the combined log; **empty** for an INCOMPLETE log (only complete logs are hashed) |
| `log_bytes` | int | Published log size in bytes (0 when no log was published) |
| `log_complete` | bool | True iff the entire combined stream was captured within the cap, published atomically, and read back successfully |
| `log_failure_code` | string? | Controlled constant from `{output-limit-exceeded, log-write-failed, log-unreadable}`; `null` iff the log is complete (a pre-execution rejection record is incomplete with no code; `launch_error` carries the reason) |
| `report_path` | string? | Repository-relative report path when applicable |
| `report_sha256` | string? | SHA-256 of the report when present |
| `report_schema_version` | int? | Parsed v2 schema version |
| `report_trusted` | bool? | Parsed `statistics.trusted` |
| `report_diagnostic_codes` | array | Parsed diagnostic `code` values |
| `report_finding_count` | int? | Parsed finding count |
| `parser_error` | string? | Controlled code when report absent/invalid (`MISSING_REPORT` when a declared `report_path` never appears on disk) |
| `launch_error` | string? | `LAUNCH_FAILED` on process launch failure, or when an ordinary internal error occurs while running/recording a command (a controlled fallback record with sanitized argv and `exit_code: null`; `KeyboardInterrupt`/`SystemExit` are never converted) |

## Semantic vs volatile fields

**Volatile (excluded from `semantic-summary.json`):** `captured_at_utc`,
per-command `start_utc`/`end_utc`/`elapsed_ms`, all `log_path`/`log_sha256`
values, the per-command log-volume metadata `log_bytes` / `log_complete` /
`log_failure_code` (a documented determinism choice — see the command-log
completeness contract), all `report_path`/`report_sha256` values, and any
absolute/temp path.

**Semantic (included, stable per SHA):** `commit`, `tree`, `requested_sha`,
`base_sha` / `merge_base_sha` (GATE-00R extension, run-invariant),
`trusted`, `preservation_ok`, interpreter/version fields,
`input_manifest_sha256`, and per command `id` / `argv` / `exit_code` /
`launch_error` / `report_schema_version` / `report_trusted` /
`report_diagnostic_codes` / `report_finding_count` / `parser_error`.

The per-command `argv` is included verbatim **except** for run-specific bundle
output paths: any occurrence of the repository-relative bundle prefix
(`build/guard-evidence/<sha>/<run-id>/…`) — whether as a bare token, a prefix,
an equals-form value (e.g. `--output=<bundle>/x.json` or `prefix=<bundle>`), or
embedded in the middle of a larger path (e.g. `some/dir/<bundle>/nested`) — is
normalized to a stable `<bundle>/…` marker before it is persisted. Only complete
path segments are masked, so a longer component that merely contains the prefix
as a substring (e.g. `out/run-10` when the bundle prefix is `out/run-1`) is left
untouched. This keeps `semantic-summary.json` byte-identical across runs at the
same SHA even though the run id differs; the run id itself never reaches the
semantic summary.

Two clean runs at the same requested SHA must produce byte-identical
`semantic-summary.json` files. Raw `commands/*.log` files may differ between
runs (timing and child output volume); only the semantic summaries must be
byte-identical.

## Command-log completeness contract

Every command log is in exactly one of two states — there is no third
"probably fine" state:

- **COMPLETE** — the entire combined stdout/stderr stream was captured within
  the bounded cap (`CHILD_OUTPUT_LIMIT`), written atomically, read back, and
  hashed (`log_complete=true`, `log_sha256` set, `log_failure_code=null`); or
- **INCOMPLETE** — the cap was exceeded (`output-limit-exceeded`), the log
  could not be written (`log-write-failed`), or the finished artifact could not
  be read back/hashed (`log-unreadable`). An incomplete log is still preserved
  on disk as a flagged artifact but is NEVER hashed as valid evidence: its
  `log_sha256` is empty, and it forces the whole capture to exit `2` with an
  `incomplete-command-log:<id>:<code>` warning. The capture never exits `0`
  with a silently truncated log.

Design properties:

- **Streaming atomic temp log**: sanitized chunks of the single merged
  stdout+stderr pipe are appended to a sibling temporary file as the child runs
  (line-local redaction, incremental reads); on completion the temp file is
  flushed, fsync'd, and `os.replace`d onto the final name, so a reader only
  ever sees a fully published artifact. Raw child payloads are never fully
  materialized in memory (bounded pending-line buffer).
- **Cap exceedance is fail-closed, not silent**: once the cap trips, the single
  `<truncated>` marker is appended exactly once, further input is discarded,
  and the child pipe **keeps being drained** so the child can never block on a
  full pipe (no deadlock); the log stays flagged INCOMPLETE and the capture
  exits `2`.
- **Only complete logs are hashed**: an INCOMPLETE log's `log_sha256` is the
  empty sentinel (the same "no authoritative hash" convention as
  `LAUNCH_FAILED` records).
- **Command records carry log-volume metadata**: `log_bytes` (published size in
  bytes), `log_complete`, and `log_failure_code`.
- **Raw log text never reaches `evidence.json` / `summary.md`**: only the
  structured per-command fields above do; log content lives exclusively in
  `commands/*.log`.
- **Documented determinism choice**: `log_bytes` / `log_complete` /
  `log_failure_code` are deliberately **excluded** from
  `semantic-summary.json` — byte counts vary between runs (child
  verbosity/timing), and incompleteness already forces exit `2` (reflected in
  `trusted`), so including them would break the byte-identical same-SHA
  comparison without adding trust.

## Command matrix

Executed in this order (plan sections A–H). Preflight (A) is recorded into
`git-state.json` / `environment.json`, not as a command log.

| ID | Command | Expected observation |
| --- | --- | --- |
| registry-validation | `python3 scripts/ci/verify_guard_registry.py` | observation |
| focused-python-tests | `python3 -m pytest scripts/ci/test_guard_findings.py ... -v --tb=short -p no:cacheprovider` | observation |
| room-inventory | `verify_db_access_boundaries.py --inventory-only` | exit 2, untrusted (`INVENTORY_DURABILITY_UNCONFIRMED` platform durability branch — never DB findings) |
| db-cli | `verify_db_access_boundaries.py --fail-on-violation ...` | exit 0, trusted at the verified SHA `565018c5eed61fae4351cb59342dc5c274eb27e7` (0 findings, 20 advisory — docs/ci/GUARD_EVIDENCE_INDEX.yml); an untrusted scan exits 2 with findings withheld |
| db-ratchet | `guard_ratchet.py --guard-name=db_access --command-arg=... --baseline=config/baselines/db_access_v2.json --ci-mode --finding-protocol=2` | observation (tokenized child args) |
| static-suite | `run_static_guard_suite.py --output-dir ...` | observation |
| gradle-db | `gradlew.bat :app:verifyDbAccessBoundaries --no-daemon --stacktrace` | observation (at the verified SHA: exit 1 on a Gradle configuration-cache storage failure; the embedded ratchet row printed PASS with 0/0 findings) |
| gradle-task-graph | `gradlew.bat :app:check --dry-run --no-daemon` | task-graph capture |
| time-direct | `python scripts/verify_time_boundaries.py --root . --allowlist config/guards/time_boundary_exceptions.yml --fail-on-violation` | observation (GATE-00R) |
| time-tests | `python -m pytest scripts/test_verify_time_boundaries.py -v --tb=short -p no:cacheprovider` | observation (GATE-00R) |
| db-inventory | `python scripts/verify_db_access_boundaries.py --inventory-only --findings-output <bundle>/reports/db-inventory.json --dump-room-mutators <bundle>/reports/room-mutators.json` | exit 0, trusted report (GATE-00R) |
| gradle-compile | `./gradlew :app:compileDebugKotlin --no-daemon --stacktrace --console=plain` | observation (GATE-00R) |

Every command runs as an **argv array with `shell=False`**. The ratchet uses
repeatable `--command-arg=<value>` tokens (every child argument, including
option-like values such as `--fail-on-violation`, is passed as
`--command-arg=<value>`) so option-like child values are never re-parsed by a
shell. Expected nonzero child exits are observations stored in `evidence.json`;
they never make the capture tool itself return `1`.

**Zero untracked working-tree side effects (hard requirement).** The command
matrix must leave the working tree exactly as it found it: no matrix command
may create, modify, or delete any tracked or untracked repository path. A
single untracked side effect (e.g. an unpinned pytest run materializing
`.pytest_cache/` — there is no pytest config file and `.gitignore` has no
entry for it) trips the post-capture drift re-check on the status surface
(exit `2`, `post-capture-drift:status`), so run-1 can never exit `0` and run-2
hits the dirty gate — the two-clean-capture protocol becomes unreachable. This
is enforced in two places:

- **At the source (declaration)**: every pytest invocation in the matrix pins
  `-p no:cacheprovider`, so pytest never writes its cache directory into the
  working tree. The focused-python-tests argv ends with
  `-v --tb=short -p no:cacheprovider`; a source-level regression test asserts
  the pin on every pytest-invoking matrix entry.
- **At verification (observation)**: the post-capture drift re-check re-runs
  `git status --porcelain=v1` after the matrix and fails the capture closed
  (exit `2`, `post-capture-drift:<head|tree|status|unverifiable>`) when any
  untracked or modified path appeared mid-matrix. A side-effecting matrix can
  therefore never yield a trusted bundle, and never the two byte-identical
  `semantic-summary.json` files (run-1/run-2) that a trusted comparison
  requires.

### Required artifacts (ratchet + static suite)

The ratchet and static-suite commands declare `required_artifacts` so the
capture fails closed when their complete output is missing or of the wrong
type:

- `db-ratchet` requires `04-db-ratchet.summary.json` (a **file**).
- `static-suite` requires the `05-static-suite/` directory **and**
  `05-static-suite/summary.json` (a **file** inside it).
- `room-inventory` requires `02-room-inventory.findings.json` and
  `02-room-mutators.json` (both **files**).
- `db-cli` requires `03-db-cli.findings.json` (a **file**).

Each required artifact is validated by **correct file/directory type** (a path
that exists but as the wrong type — e.g. a report path that is a directory —
fails closed with `invalid-required-artifact-type:<rel>` / `invalid-required-report:<id>`).
A required artifact that is a **symlink** (file or directory root) is rejected
outright with `symlink-artifact:<rel>` before any type/hash handling, so a symlink
is never followed into content outside the bundle. Every required artifact is then
**hashed explicitly** and recorded under `evidence.json → required_artifact_hashes`:
files by content, directories by a deterministic recursive SHA-256 over every
contained *regular* file (so the whole static-suite subtree — logs/reports/summary
— is covered by one digest). Symlinks inside an artifact directory are never
followed (skipped), and every walked file is re-checked against the bundle root
(TOCTOU-safe) before it is opened or hashed, so containment is enforced at the
moment of hashing, not only at an earlier path check. The per-command
`report_sha256` / `log_sha256` fields remain as additional coverage.

### Output containment

The `--out` bundle directory must resolve **inside** the repository root. The
containment check uses **resolved real paths** (`os.path.realpath`), so a path
that escapes the root via `..` traversal **or via a symlink** whose target lies
outside the root is rejected before any command runs; the tool returns `2` and
writes no bundle. All output paths stored in `evidence.json` (the bundle
`root`, each command `log_path` / `report_path`, and every `argv` token) are
**repository-relative** — never absolute machine paths. A custom (injected)
command matrix is additionally validated **in full** by `validate_command_matrix`
  *before any runner call*: any absolute or root-escaping path in its `argv` fails
  closed with `invalid-matrix-argv:<id>:<token>` so such a path is never persisted
  or executed. Empty and non-string argv tokens are likewise rejected
  (`invalid-matrix-argv:<id>:<empty>` / `<empty-or-non-string>`), and the pre-runner
  validates the **default** matrix as well as any custom matrix (defense in depth), so
  a malformed argv is caught even for the trusted default. The complete custom
  `CommandSpec` schema is enforced — a bounded safe
command `id` (`MAX_COMMAND_ID_LEN`), bounded `log_name` / `report_path` /
`required_artifacts` path strings (`MAX_PATH_LEN`), a bounded `required_artifacts`
count (`MAX_REQUIRED_ARTIFACTS`) and `artifact_kinds` count (`MAX_ARTIFACT_KINDS`),
and an **explicit valid artifact kind (`"file"` / `"dir"`) for every required
artifact** (a missing or non-`file`/`dir` kind fails closed with
`missing-artifact-kind:<id>:<rel>`). The hostile-path check rejects **backslash
separators, UNC shares (`//host/share`), Windows drive prefixes, POSIX absolute
paths, and `..` traversal segments** — not only leading-slash/absolute forms. The
offending token is reduced to a bounded `<redacted-path>` marker in the warning so
no raw machine/path content leaks into the bundle. The same hostile-path rejection
(and token sanitization) is applied to every derived bundle path (`log_name`,
`report_path`, `required_artifacts`) via `validate_bundle_paths`, so a
backslash/UNC/traversal bundle path also fails closed with
`invalid-bundle-path:<id>:<token>`. `validate_bundle_paths` validates the nested
`CommandSpec` field types (`log_name` / `report_path` / `required_artifacts` /
`artifact_kinds`) **before any `os.path` operation**, so a malformed non-string
value fails closed with a controlled `invalid-bundle-path:` warning rather than
raising (`TypeError`) or being resolved unsafely — and, because validation runs
before any runner call, with zero child commands executed. Overflow of any bounded
CommandSpec field fails closed with a controlled `OVERFLOW_COMMAND_ID` /
`OVERFLOW_PATH` / `OVERFLOW_ARTIFACT_KINDS` marker rather than persisting unbounded
content.

When matrix/path validation fails (a custom matrix embedding an absolute/outside
`argv` token, or any derived bundle path escaping the bundle), the capture tool
**stops before any runner call**: no child command is executed, the capture
fails closed (exit `2`) with the recorded `invalid-matrix-argv:` /
`invalid-bundle-path:` warnings, and `evidence.json → commands` is an empty list.
A hostile or malformed matrix is therefore never executed.

### Preflight command records

The preflight git/version matrix (plan section A) is recorded as bounded,
sanitized command records under `git_state.preflight_commands` (each with
`argv`, `exit_code`, and a redacted/truncated `output`). `git_state.preflight_ok`
is `false` when the essential `HEAD` / `HEAD^{tree}` identity cannot be
resolved, which fails the capture closed. `git_state.git_meta_ok` is `false` when
any preflight git metadata command (`git status --porcelain=v1`, `git diff
--name-only`, `git diff --cached --name-only` — and, since GATE-00R,
`git rev-parse <base-ref>`, `git merge-base HEAD <base-ref>`, and
`git branch --show-current`) fails (nonzero exit or launch
error); such a failure is **fatal** — the capture fails closed with
`git-meta-failed:<which>` so an unobservable checkout state is never recorded as
authoritative. GATE-00R also records the resolved `base_sha` /
`merge_base_sha` / `branch` fields and the bounded platform identity fields
(`locale` / `timezone` / `os_identifier`) in `git-state.json` (see the
GATE-00R extension section).

### Sanitization

- **Diagnostic codes**: every code parsed from a v2 report is validated against
  a controlled pattern (`^[A-Z][A-Z0-9_]*$`). Anything else (paths, messages,
  secrets) is replaced by `<redacted>` so untrusted report content cannot leak
  raw exception text, file paths, or payloads into the bundle.
- **Persisted output**: each child command's combined stdout/stderr is
  comprehensively sanitized before being written to its `commands/*.log` file.
  Matrix-command logs are streamed through the atomic log sink and sanitized
  **line-by-line** with the same redactors: absolute filesystem paths (Windows
  drive form, POSIX multi-segment paths, UNC/backslash forms) become
  `<redacted-path>`; secret assignments (`password=…`, `api_key=…`, etc.) become
  `<redacted-secret>`; SQL error / exception signatures become
  `<redacted-sql>`; and exception traceback lines become
  `<redacted-exception>`. Length bounding is enforced across the whole log by
  the sink: exceeding `CHILD_OUTPUT_LIMIT` is a fail-closed INCOMPLETE state
  (see the command-log completeness contract), never a silent truncation.
  Preflight records (git/version metadata) keep the whole-text bounded
  truncation. Child **exit codes are never altered** by sanitization.
   No absolute path, secret, SQL error, raw exception text, or unbounded payload
   reaches the persisted logs or `evidence.json`.
- **Preflight / version / environment metadata**: interpreter version strings
   (`python_version`, `python3_version`, `java_version`, `gradle_version`) and
   allowlisted environment values are sanitized (absolute paths / secrets
   redacted) and length-bounded before persistence, so an unusual version string
   cannot leak raw payloads into `git-state.json` / `environment.json`.
    - **Warning payloads**: every entry in `infrastructure_warnings` is passed through
    `_sanitize_warning` at assembly. The leading warning **code** must be a member
    of the **closed `WARNING_CODE_ALLOWLIST`** (an explicit `frozenset` of
    controlled codes — see below); an untrusted or malformed code means the whole
    warning is reduced to `<redacted>` rather than persisted verbatim. The only
    sanctioned constructor is `make_warning(code, *parts)`, which refuses to emit a
    code outside the allowlist and sanitizes every payload part (secrets and
    hostile path forms become controlled markers) before joining with `:`
    separators. Secret assignments and hostile path forms (backslash, UNC share,
    absolute path, traversal) in a warning payload are redacted to controlled
    markers (`<redacted-secret>`, `<redacted-path>`), control characters
    (NUL/newline/tab/DEL) are stripped, and the payload is length-bounded, so a
    rejected custom path or other untrusted payload never leaks raw
    machine/path/secret content into the bundle. Arbitrary untrusted
    payload text is never preserved: only the controlled code, controlled markers,
    and safe repository-relative tokens survive sanitization. Fail-closed:
    unrecognized/raw payload content is redacted by default, never passed through
    verbatim.

    - **Closed warning-code allowlist (exact)**: the following codes are the *only*
    codes permitted in `infrastructure_warnings`. Anything else is redacted to
    `<redacted>`:
    `OVERFLOW_DIAGNOSTIC_CODES`, `OVERFLOW_FINDING_COUNT`, `OVERFLOW_WARNINGS`,
    `OVERFLOW_MANIFEST`, `OVERFLOW_MATRIX`, `OVERFLOW_ARGV`, `OVERFLOW_COMMAND_ID`,
    `OVERFLOW_PATH`, `OVERFLOW_ARTIFACT_KINDS`, `OVERFLOW_VIOLATIONS`,
    `OVERFLOW_REQUIRED_ARTIFACT_HASHES`, `OUTPUT_HASH_FAILED`,
    `invalid-matrix-spec`, `invalid-matrix-argv`, `invalid-bundle-path`,
    `missing-artifact-kind`, `missing-required-input`, `missing-blob-id`,
    `missing-required-artifact`, `invalid-required-artifact-type`,
    `invalid-required-report`, `symlink-artifact`, `artifact-hash-failed`,
    `preflight-failed`, `wrong-sha`, `git-meta-failed`,
    `missing-expected-sha`, `invalid-expected-sha`, `post-capture-drift`,
    `missing-base-ref`, `invalid-base-ref`,
    `missing-test-file`, `incomplete-command-log`.

    - **Input-candidate realpath containment**: every input manifest candidate
   (including a custom `input_candidates` list) is validated with
   `_is_safe_repo_relative_path` — which resolves via `os.path.realpath` and
   rejects non-strings, backslashes, UNC shares, Windows drive prefixes, POSIX
   absolute paths, `..` traversal, and symlink escapes — **before** any file read
   or `git rev-parse HEAD:` access. An unsafe candidate is never read or hashed;
   it is recorded as missing (fail closed on a missing required input) with its raw
   value reduced to the bounded `<redacted-unsafe-candidate>` marker, so no
   machine/path content leaks into `input-manifest.json`.

     - **Race-safe file reads / hashing (inputs, reports, artifacts, logs)**: every
    input, v2 report, required artifact, command log, and top-level output is read
    and hashed through `_race_safe_read_bytes` / `_race_safe_hash_file`. The actual
    guarantee is: (1) `os.lstat` first and reject anything that is not a regular
    file (this catches symlinks, directories, devices); (2) where the platform
    exposes `O_NOFOLLOW` (Linux/macOS) the file is opened with that flag so a
    symlink is refused at `open` time; (3) the opened descriptor is `os.fstat`'d
    and confirmed to be the same regular file (`st_dev`/`st_ino`) we `lstat`'d, and
    its size recorded; (4) after reading, the descriptor is `fstat`'d again and the
    result is rejected if identity or size changed mid-read. On Windows `O_NOFOLLOW`
    is generally unavailable, so the `lstat` + descriptor `fstat` identity/size
    re-checks are the enforced containment — this is best-effort TOCTOU mitigation,
    not a hard guarantee against a privileged actor able to swap inodes between our
    checks. A rejection (symlink, non-regular, read error, replaced/changed file)
     returns `None`, which the caller turns into a fail-closed condition: a required
     artifact whose hash fails sets `artifact-hash-failed:<rel>` and exit `2`; a
     finished command log that cannot be read back/hashed is marked INCOMPLETE with
     `log-unreadable` (fail closed, exit `2` — the former
     `launch_error=LOG_HASH_FAILED` sentinel is superseded); a report hash failure sets
     `parser_error=REPORT_HASH_FAILED` (which also triggers `invalid-required-report`).
     - **Report hash and parse share one stable byte snapshot**: a required report is
    read exactly once via `_race_safe_read_bytes`; the same byte snapshot is used for
    BOTH the `report_sha256` digest and the `parse_v2_report` parse, so the evidence
    can never combine a hash of one report version with a parse of another (TOCTOU /
    replacement). The unsafe, non-race-safe `sha256_file` helper was removed; all
    hashing now routes through the race-safe helpers above.
- **Bounded persisted collections / counts / strings (fail-closed overflow
   diagnostics)**: every persisted collection, count, and string is finite-bounded
   and fails closed with a controlled overflow marker rather than persisting
   unbounded content — the report diagnostic-code list (`OVERFLOW_DIAGNOSTIC_CODES`),
   the report finding count (`OVERFLOW_FINDING_COUNT`, treated as unparseable), the
   input manifest (`OVERFLOW_MANIFEST`), the infrastructure warnings
   (`OVERFLOW_WARNINGS`), the custom command matrix (`OVERFLOW_MATRIX`), the custom
   argv token list    (`OVERFLOW_ARGV`), the custom command id (`OVERFLOW_COMMAND_ID`),
   derived path strings (`OVERFLOW_PATH`), the artifact-kind metadata
   (`OVERFLOW_ARTIFACT_KINDS`), and the validation-violation list
   (`OVERFLOW_VIOLATIONS`; `validate_bundle_paths` bounds collection **during
   iteration**, including inside its per-artifact loop, while
   `validate_command_matrix` bounds collection at each command-spec boundary and
   then truncates the returned list to the bound — in both cases the returned
   violation set never exceeds `MAX_VIOLATIONS` and always ends with the
   controlled marker), plus the summary
   markdown (truncated with `<truncated>`). No unbounded payload can reach the
   evidence bundle. The persisted
   sizes are themselves bounded: `input_manifest` ≤ `MAX_MANIFEST_ENTRIES`,
   `infrastructure_warnings` ≤ `MAX_WARNINGS`, `commands` ≤ `MAX_MATRIX_COMMANDS`,
   each argv token ≤ `MAX_ARGV_TOKEN_LEN`, each path string ≤ `MAX_PATH_LEN`, and
   each command id ≤ `MAX_COMMAND_ID_LEN`. On overflow the persisted collection is
   **truncated** to the bound (the overflow marker replacing the final entry where
    needed) so the materialized artifact never exceeds the maximum — the marker is a
    signal, not a permit to persist unbounded content. The `infrastructure_warnings`
     cap is applied **after** every final diagnostic — including the `output-hash-failed`
     warning emitted during top-level output hashing — so the persisted warning list
     can never exceed `MAX_WARNINGS` even when a hash failure pushes it over the bound.
     When `MAX_WARNINGS == 0` the cap is **exact**: the persisted list is empty (not
     even the `OVERFLOW_WARNINGS` marker is kept) yet the capture still fails closed
     (exit `2`, `trusted: false`), so the overflow is signaled via the exit code
     rather than a persisted warning.
  - **Strict typed v2 report containers (fail closed)**: `parse_v2_report`
     type-checks every nested container before any attribute access, so a hostile
     report can never make the parser raise and is never partially accepted:
     `findings` / `diagnostics` must be JSON arrays, `statistics` must be a JSON
     object, every diagnostic must be an object carrying a string `code`, and
     every finding must be a JSON object with at most `MAX_FINDING_KEYS` keys. A
     wrong-type container or entry yields a controlled `parser_error`
     (`MALFORMED_FINDINGS`, `MALFORMED_DIAGNOSTICS`, `MALFORMED_DIAGNOSTIC_ENTRY`,
     `MALFORMED_STATISTICS`, `MALFORMED_FINDING_ENTRY`) with the structured fields
     set to `None`/empty — previously a list-typed `statistics` raised
     `AttributeError` and a dict-typed `findings` was silently counted by its keys.
  - **Required-artifact hash collection bounded (fail closed)**: the set of required
    artifact hashes materialized during the required-artifact loop is bounded by
    `MAX_REQUIRED_ARTIFACT_HASHES`. The loop stops hashing once the finite aggregate
    limit is reached and fails closed with `OVERFLOW_REQUIRED_ARTIFACT_HASHES` (exit
    `2`); no more than the bound is ever materialized, so an unbounded custom matrix
    cannot inflate the evidence bundle with unbounded hashes.
 - **Top-level output hash failure fails closed (no empty hash)**: every top-level
    output listed in `output-sha256.txt` is hashed race-safely. A top-level output
    whose hash/read fails (symlink, non-regular file, read error, or replaced/changed
    mid-read) fails the capture closed with `output-hash-failed:<rel>` (exit `2`) and
    is **never** substituted with an empty hash; the bundle is marked untrusted and
    the diagnostic is persisted.
 - **No custom argv token persists verbatim**: every persisted argv token (command
  records and preflight command records) is passed through `_sanitize_argv_token`,
  which redacts secrets and absolute/UNC/backslash paths and length-bounds the
  token. A custom matrix embedding a secret/absolute token is rejected by
  `validate_command_matrix` (fail closed) and never executed.

## Trusted-state definition

A bundle is `trusted` only when **all** hold:

1. the checkout was clean (no `git status --porcelain=v1` output) **or**
   `--allow-dirty` was supplied (in which case the bundle is explicitly
   `trusted: false`);
  2. the preservation check passed (no forbidden file or `app/src/main` differs
    from HEAD); the check inspects **unstaged working-tree changes**, **staged
    changes**, and **untracked paths** (via `git ls-files --others` *and* the
    porcelain `??` form). Porcelain status lines are parsed positionally on the
    **raw, unstripped line**: the two status columns are removed before any
    filename comparison, so every form — the `??` untracked form, the `R  a -> b`
    rename form, and the leading-space ` M path` working-tree/staged form —
    resolves to the bare repository-relative path(s); an untracked forbidden file
    or an untracked `app/src/main` path fails
    the preservation check closed;
3. no command suffered a launch failure, and every executed command's log is
   COMPLETE (`log_complete=true`; any `log_failure_code` fails the capture
   closed);
4. every required artifact is present **and of the correct type**;
 5. the preflight git identity resolved (both `commit` and `tree` are known and
    are valid 40-hex SHAs from a successful `git rev-parse` — a nonzero exit or
    malformed SHA fails closed even if output text is present);
  6. the observed `HEAD` equals the caller-stated `--expected-sha` run pin
     (checked **before any matrix command starts**; a mismatch fails closed
     pre-launch with `wrong-sha:<observed>` and no command is executed);
  7. every required input candidate is present (none missing at the SHA) **and each
     present input resolves to a valid 40-hex Git blob ID**; the input manifest is
     built **dynamically** from tracked files via `git ls-files` over DB-guard
     pathspecs, unioned with the required DB config/policy/structural/raw-query
     candidates, so a missing required input is still surfaced; each input's
     `blob_id` is validated to be a 40-hex Git SHA-1, and a missing/forged
     (non-40-hex) blob ID fails the capture closed with `missing-blob-id:<rel>` (an
     untrusted or uncommitted input is never treated as a valid source of record);
 8. every required report that is present is valid (no invalid required report,
    and no required report path that resolves to a directory);
 9. the preflight git metadata commands (`git status --porcelain=v1`, `git diff
    --name-only`, `git diff --cached --name-only`) all succeeded — a failure is
    fatal (`git-meta-failed:<which>`), since an unobservable checkout state cannot
    be trusted;
 10. the post-capture identity re-check passed: HEAD, tree, and porcelain status
     re-observed after the matrix are unchanged from the preflight state (any
     drift — or an unverifiable post-state — fails closed with
     `post-capture-drift:<head|tree|status|unverifiable>`).

If any of (5)–(10) fails, the capture **fails closed**: it returns exit code `2`,
marks the bundle untrusted, and records a bounded `infrastructure_warnings`
  entry (`preflight-failed`, `missing-expected-sha`, `invalid-expected-sha`,
  `wrong-sha:<observed>`, `post-capture-drift:<head|tree|status|unverifiable>`,
  `git-meta-failed:<which>`, `incomplete-command-log:<id>:<code>`,
  `missing-required-input:<rel>`, `missing-blob-id:<rel>`, `symlink-artifact:<rel>`,
  `invalid-required-artifact-type:<rel>`, `invalid-required-report:<id>`,
  `invalid-matrix-argv:<id>:<token>` (including `<empty>` and `<empty-or-non-string>`
  forms), or `invalid-bundle-path:<id>:<token>` — each with hostile path tokens
  reduced to `<redacted-path>`).

`trusted` describes the **integrity of the capture**, not whether the DB gate
passed. A blocked DB gate is an observed child status, never a capture failure.

## Redaction policy

`environment.json` records every environment variable **name**, but only an
explicit allowlist of version-field values is retained. All other values are
replaced by `<redacted>`. Allowlisted keys (explicit, exact-match only):
`PYTHON_VERSION`, `PYTHON3_VERSION`, `JAVA_VERSION`, `GRADLE_VERSION`, `OS`,
`OSTYPE`. No suffix-based allowance (e.g. `*_VERSION`) exists, so a secret-like
variable such as `DB_PASSWORD_VERSION` is always redacted. No secrets, paths, or
payloads reach the bundle.

## output-sha256.txt contract

`output-sha256.txt` is a **deliberate, fixed contract**: it records the SHA-256
of only the top-level bundle outputs written by the capture tool itself. Each
line uses the **bundle-relative top-level name** (relative to the bundle
directory, e.g. `git-state.json` — never the repository-relative bundle path
such as `build/guard-evidence/<sha>/<run-id>/git-state.json`), so the contract
stays stable across run ids:

- `git-state.json`
- `environment.json`
- `input-manifest.json`
- `input-sha256.txt`
- `evidence.json`
- `summary.md`
- `semantic-summary.json`

It intentionally does **not** include `output-sha256.txt` itself, the
`commands/` combined logs, or the per-command report artifacts
(`*.findings.json`, `*.summary.json`, `05-static-suite/`). Those artifacts are
covered individually by the per-command `report_sha256` fields and — for
COMPLETE logs — the `log_sha256` field inside `evidence.json`; an INCOMPLETE
log carries an empty `log_sha256` and is flagged via `log_complete=false` plus
its controlled `log_failure_code` (failing the capture closed). Re-hashing them
into `output-sha256.txt` would be redundant and would make the contract unstable
across log-timing differences.
This set is the evidence-integrity contract and must not be widened without an
explicit protocol bump.

`output-sha256.txt` is written **last**, after any rewrite of the evidence /
semantic / summary artifacts triggered by a top-level output-hash failure or the
warning cap. Its hashes therefore always correspond to the **final** on-disk
artifacts; a failed (symlink / non-regular / replaced / changed) output is
excluded and is never substituted with an empty hash. A failure detected during
the **final rehash pass** is itself persisted before the file is written: the
`output-hash-failed:<rel>` diagnostic is appended to the warning list, the
warning cap is reapplied, and the evidence / semantic / summary artifacts are
rewritten to a consistent untrusted state; `output-sha256.txt` is then recomputed
from hashes taken **after** that rewrite (the failed output excluded, no stale
hash published). The converge-and-rehash loop is bounded — each failing pass
permanently excludes at least one output — so no final diagnostic can be lost
and the loop cannot spin.

## How CI uploads artifacts

CI runs the capture tool with
`--root . --expected-sha <40-lowercase-hex> --base-ref <40-lowercase-hex> --out build/guard-evidence/<sha>/run-1`
(and a second `run-2` for reproducibility), where the `--expected-sha` value is
the SHA the caller intends to capture — stated explicitly, never derived from
HEAD — and the `--base-ref` value is the caller-stated base SHA whose
resolution and merge-base are recorded at preflight (GATE-00R extension). The
`build/guard-evidence/` directory is uploaded as a CI artifact.
Because it is git-ignored, it is never committed to the repository.

## How a reviewer compares two bundles

1. Confirm both `git-state.json` `requested_sha` values are identical, and that
   each capture's `observed_sha`/`tree_sha` equals the requested pin
   (`commit`/`tree` mirror them) and matches the intended capture SHA.
2. Confirm both `input-manifest.json` file hashes are identical (same inputs).
3. Confirm both `semantic-summary.json` files are byte-identical.
4. Inspect the `db-cli` command: `report_trusted` must be `false` with a
   controlled diagnostic, not fabricated findings.
5. Inspect `room-inventory` independently (expected trusted, no diagnostics).
6. Confirm no forbidden file changed via `preservation.ok`.

Both captures must have exited `0`. Raw `commands/*.log` files are expected to
differ between the two runs (timing and child output volume); only
`semantic-summary.json` must be byte-identical. A capture that exited `2`
(run-pin rejection, post-capture drift, incomplete command log, or any other
failed-closed condition) is not comparable evidence at all.

## Why a checked-in prose ledger must never prove a later SHA

`docs/ci/DB_GUARD_HARDENING_LEDGER.md` is a durable **index** of evidence
bundles. A ledger row records the caller-stated SHA (`--expected-sha`), the
observed tree, and the artifact location of a capture that already happened. It is not a substitute for raw artifacts and must never
assert that a later, untested SHA is green or blocked. Each row's claims are
only as strong as the referenced bundle's `evidence.json` and `semantic-summary.json`.
