# DB Guard Evidence Protocol

Diagnostic-only, reproducible evidence workflow for the DB access guard at one
exact Git SHA. This protocol is defined by `scripts/ci/capture_db_guard_evidence.py`
and is **not** an architecture guard. It never mutates policy, baseline,
config/guards, production Kotlin, Gradle, workflow, scanner, or ratchet files.

The DB gate is expected to remain **blocked** at the tested SHA. Capturing that
state truthfully is the deliverable. This tool must not edit policy to change it.

## No trusted evidence bundle without two human clean captures

**No trusted evidence bundle exists until a human performs two clean captures at
the fixed SHA `9b97e7979130de605d164386bbf719cf20579475`.** A single capture, a
dirty capture, an `--allow-dirty` capture, a capture at any other SHA, or a
capture whose preflight/required-input/required-artifact checks failed closed is
**never** a trusted bundle and must not be described as evidence of a green or
passing state. The capture tool is diagnostic-only; it observes and records, it
does not certify. A trusted bundle requires a human operator to:

1. run the capture twice at a clean checkout of the exact fixed SHA;
2. confirm both `semantic-summary.json` files are byte-identical; and
3. confirm the DB gate remains blocked (observed child status, not a capture
   success) and no `infrastructure_warnings` indicate a failed-closed condition.

Until those two clean captures are performed and compared by a human, this
protocol makes **no claim that evidence exists** and **no claim of green
status**. Any prose ledger row is an index of a capture that already happened,
not proof of a later SHA's state.

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
  02-room-inventory.findings.json
  02-room-mutators.json
  03-db-cli.findings.json
  04-db-ratchet.summary.json
  05-static-suite/
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
| `trusted` | bool | Bundle trusted-state (see below) |
| `allow_dirty` | bool | Whether `--allow-dirty` was passed |
| `dirty` | bool | Whether the checkout was dirty |
| `preservation` | object | `{ok, policy_ok, production_ok, staged_ok, untracked_ok, checked_paths, forbidden_changed}` |
| `environment` | object | Redacted environment snapshot |
| `git_state` | object | Preflight git/version capture; includes `preflight_ok` and `preflight_commands` (bounded, sanitized records) |
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
| `log_sha256` | string | SHA-256 of the combined log |
| `report_path` | string? | Repository-relative report path when applicable |
| `report_sha256` | string? | SHA-256 of the report when present |
| `report_schema_version` | int? | Parsed v2 schema version |
| `report_trusted` | bool? | Parsed `statistics.trusted` |
| `report_diagnostic_codes` | array | Parsed diagnostic `code` values |
| `report_finding_count` | int? | Parsed finding count |
| `parser_error` | string? | Controlled code when report absent/invalid |
| `launch_error` | string? | `LAUNCH_FAILED` on process launch failure, or when an ordinary internal error occurs while running/recording a command (a controlled fallback record with sanitized argv and `exit_code: null`; `KeyboardInterrupt`/`SystemExit` are never converted) |

## Semantic vs volatile fields

**Volatile (excluded from `semantic-summary.json`):** `captured_at_utc`,
per-command `start_utc`/`end_utc`/`elapsed_ms`, all `log_path`/`log_sha256`
values, all `report_path`/`report_sha256` values, and any absolute/temp path.

**Semantic (included, stable per SHA):** `commit`, `tree`, `trusted`,
`preservation_ok`, interpreter/version fields, `input_manifest_sha256`, and per
command `id` / `argv` / `exit_code` / `launch_error` / `report_schema_version`
/ `report_trusted` / `report_diagnostic_codes` / `report_finding_count` /
`parser_error`.

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

Two clean runs at the same SHA must produce byte-identical
`semantic-summary.json` files. Logs may differ only in timing.

## Command matrix

Executed in this order (plan sections A–H). Preflight (A) is recorded into
`git-state.json` / `environment.json`, not as a command log.

| ID | Command | Expected at SHA `9b97e79` |
| --- | --- | --- |
| registry-validation | `python3 scripts/ci/verify_guard_registry.py` | observation |
| focused-python-tests | `python3 -m pytest scripts/ci/test_guard_findings.py ... -v --tb=short` | observation |
| room-inventory | `verify_db_access_boundaries.py --inventory-only` | exit 0, trusted report |
| db-cli | `verify_db_access_boundaries.py --fail-on-violation ...` | exit 2, untrusted, policy diagnostic |
| db-ratchet | `guard_ratchet.py --guard-name=db_access --command-arg=... --baseline=config/baselines/db_access.json --ci-mode --finding-protocol=2` | observation (tokenized child args) |
| static-suite | `run_static_guard_suite.py --output-dir ...` | observation |
| gradle-db | `./gradlew :app:verifyDbAccessBoundaries --no-daemon --stacktrace` | expected failure (blocked) |
| gradle-task-graph | `./gradlew :app:check --dry-run --no-daemon` | task-graph capture |

Every command runs as an **argv array with `shell=False`**. The ratchet uses
repeatable `--command-arg=<value>` tokens (every child argument, including
option-like values such as `--fail-on-violation`, is passed as
`--command-arg=<value>`) so option-like child values are never re-parsed by a
shell. Expected nonzero child exits are observations stored in `evidence.json`;
they never make the capture tool itself return `1`.

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
--name-only`, `git diff --cached --name-only`) fails (nonzero exit or launch
error); such a failure is **fatal** — the capture fails closed with
`git-meta-failed:<which>` so an unobservable checkout state is never recorded as
authoritative.

### Sanitization

- **Diagnostic codes**: every code parsed from a v2 report is validated against
  a controlled pattern (`^[A-Z][A-Z0-9_]*$`). Anything else (paths, messages,
  secrets) is replaced by `<redacted>` so untrusted report content cannot leak
  raw exception text, file paths, or payloads into the bundle.
- **Persisted output**: each child command's combined stdout/stderr is
  comprehensively sanitized before being written to its `commands/*.log` file.
  Absolute filesystem paths (Windows drive form and POSIX multi-segment paths)
  are replaced by `<redacted-path>`; secret assignments (`password=…`,
  `api_key=…`, etc.) by `<redacted-secret>`; SQL error / exception signatures by
  `<redacted-sql>`; and exception traceback lines by `<redacted-exception>`. The
  total length is bounded (`CHILD_OUTPUT_LIMIT`) so no unbounded payload reaches
  the persisted logs. Child **exit codes are never altered** by sanitization.
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
    `preflight-failed`, `wrong-sha`, `git-meta-failed`, `missing-test-file`.

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
    artifact whose hash fails sets `artifact-hash-failed:<rel>` and exit `2`; a log
    hash failure sets `launch_error=LOG_HASH_FAILED`; a report hash failure sets
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
3. no command suffered a launch failure;
4. every required artifact is present **and of the correct type**;
 5. the preflight git identity resolved (both `commit` and `tree` are known and
    are valid 40-hex SHAs from a successful `git rev-parse` — a nonzero exit or
    malformed SHA fails closed even if output text is present);
 6. `HEAD` equals the approved exact target SHA `9b97e7979130de605d164386bbf719cf20579475`
    (a differing HEAD fails closed with `wrong-sha:<actual>`);
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
    be trusted.

If any of (5)–(9) fails, the capture **fails closed**: it returns exit code `2`,
marks the bundle untrusted, and records a bounded `infrastructure_warnings`
  entry (`preflight-failed`, `wrong-sha:<actual>`, `git-meta-failed:<which>`,
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
covered individually by the per-command `log_sha256` / `report_sha256` fields
inside `evidence.json`; re-hashing them into `output-sha256.txt` would be
redundant and would make the contract unstable across log-timing differences.
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

CI runs the capture tool with `--root . --out build/guard-evidence/<sha>/run-1`
(and a second `run-2` for reproducibility). The `build/guard-evidence/`
directory is uploaded as a CI artifact. Because it is git-ignored, it is never
committed to the repository.

## How a reviewer compares two bundles

1. Confirm both `git-state.json` `commit`/`tree` values are identical and match
   the PR base SHA.
2. Confirm both `input-manifest.json` file hashes are identical (same inputs).
3. Confirm both `semantic-summary.json` files are byte-identical.
4. Inspect the `db-cli` command: `report_trusted` must be `false` with a
   controlled diagnostic, not fabricated findings.
5. Inspect `room-inventory` independently (expected trusted, no diagnostics).
6. Confirm no forbidden file changed via `preservation.ok`.

## Why a checked-in prose ledger must never prove a later SHA

`docs/ci/DB_GUARD_HARDENING_LEDGER.md` is a durable **index** of evidence
bundles. A ledger row records the SHA, tree, and artifact location of a capture
that already happened. It is not a substitute for raw artifacts and must never
assert that a later, untested SHA is green or blocked. Each row's claims are
only as strong as the referenced bundle's `evidence.json` and `semantic-summary.json`.
