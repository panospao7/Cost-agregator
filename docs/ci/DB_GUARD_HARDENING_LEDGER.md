# DB Guard Hardening Ledger

Durable index of DB guard evidence bundles produced by
`scripts/ci/capture_db_guard_evidence.py`. This ledger is **not** a substitute
for raw artifacts and must never assert that a later, untested SHA is green or
blocked. Each row's claims are only as strong as the referenced bundle's
`evidence.json` and `semantic-summary.json`.

Status vocabulary is intentionally conservative: `pending`, `partial`,
`conditional`, `blocked`, `near-complete`. Do **not** insert fabricated
`green` / `done` / `complete` statements.

## No trusted bundle without two human clean captures

**No trusted evidence bundle exists until a human performs two clean captures at
the caller-stated SHA via `--expected-sha`.**

## How to add a row

Run the capture tool twice at a clean SHA, confirm the two `semantic-summary.json`
files are identical, then append one row below. Link the artifact location or CI
run reference; do not paste raw logs here.

## Ledger

| Evidence SHA | Tree SHA | Branch | Capture date | Clean/Dirty | Platform / interpreters | Input-manifest hash | Command IDs | Exit codes | First diagnostic | Trusted | Artifact / CI run | Reviewer class | Next PR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |

## Notes

- GR-00 scope: capture reproducible evidence at SHA `9b97e79`. The DB gate is
  expected to remain **blocked** (untrusted report, controlled policy/source
  diagnostic) because active policy entries lack v2 signatures. This is an
  observed status, not a capture failure.
- Forbidden changes for GR-00: `config/baselines/db_access.json`,
  `config/guards/db_ownership_policy.yml`,
  `config/guards/db_ownership_policy.signatures.candidate.yml`,
  `config/guards/db_structural_exceptions.yml`,
  `config/guards/db_structural_exceptions_expected_methods.yml`,
  `config/guards/db_raw_query_classification.yml`, and all of `app/src/main`.
- The capture tool is diagnostic-only and is intentionally absent from the guard
  registry.

## GR-00 hardening applied (this revision)

The capture tool and its tests were hardened per strict-review blockers:

- **Target SHA enforcement**: `HEAD` must equal the approved exact SHA
  `9b97e7979130de605d164386bbf719cf20579475`; a differing HEAD fails closed
  (`wrong-sha:<actual>`, exit `2`). Preflight also rejects a nonzero `git
  rev-parse` exit or a malformed (non-40-hex) SHA even when output text exists.
- **Output containment (realpath + symlink)**: `--out` is resolved via
  `os.path.realpath` and must lie inside the repo root; traversal **and symlink
  escapes** are rejected (exit `2`, no bundle written).
- **No absolute paths in evidence argv**: all output paths embedded in command
  `argv` are stored repository-relative; custom (injected) matrices are validated
  and any absolute/outside `argv` token fails closed (`invalid-matrix-argv`).
- **`--command-arg=<value>`**: every ratchet child argument uses the equals form
  (asserted in full, not merely by presence).
- **Dynamic input manifest**: built from `git ls-files` over DB-guard pathspecs
  (all tracked `scripts/db_guard/*.py` and DB config/policy/structural/raw-query
  files) unioned with required candidates; missing-input diagnostics preserved.
- **Required-artifact type + hashing**: each required artifact is validated by
  correct file/directory type (a directory where a file is required fails closed
  with `invalid-required-artifact-type` / `invalid-required-report`) and hashed
  explicitly into `evidence.json → required_artifact_hashes` (files by content,
  directories recursively).
- **Fail closed** on missing required inputs, preflight git-identity failure,
  invalid (present-but-unparseable) required reports, and required report paths
  that are directories.
- **Preflight command records** captured under `git_state.preflight_commands`
  with `preflight_ok`.
- **Comprehensive sanitization**: child combined output is redacted for absolute
  paths (`<redacted-path>`), secrets (`<redacted-secret>`), SQL errors
  (`<redacted-sql>`), and exception tracebacks (`<redacted-exception>`), then
  length-bounded; child exit codes are never swallowed.
- **Protocol layout corrected**: required report artifacts live at the bundle
  root (not under `commands/`), matching the implementation; hashes and
  fail-closed semantics documented accordingly.
- Tests added/updated for each blocker (wrong-SHA, failed/malformed preflight,
  manifest completeness, symlink/custom-matrix, artifact type/hash coverage,
  raw secret/exception/SQL output, complete ratchet argv, report-path-directory
  rejection); the invalid-report case expects exit `2`.

## Strict-review fixes applied in this revision

The following additional strict-review fixes were applied to the four GR-00
files (`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Derived bundle-path containment (realpath + symlink)**: every derived
  `log_name` (under `commands/`), `report_path`, and `required_artifact` is
  resolved via `os.path.realpath` and must stay inside the bundle; a `..`
  traversal or an external symlink read/write is rejected (`invalid-bundle-path`
  / `BUNDLE_PATH_ESCAPE` / `REPORT_PATH_ESCAPES_BUNDLE`), and the tool never
  reads from or writes to a path outside the bundle. Filesystem symlink and path
  traversal tests cover report/log/artifact paths.
- **Preflight metadata sanitization + bounding**: `git status` / `git diff`
  / `git diff --cached` metadata is sanitized (absolute paths, secrets, SQL,
  exceptions redacted) and bounded; `git log --oneline` metadata persists SHAs
  only (commit-message text dropped); preflight command-record output is fully
  sanitized. Regression tests assert no raw path / secret / commit-message text
  reaches `git-state.json`.
- **Fixed target SHA, not configurable**: the `target_sha` override was removed;
  `TARGET_SHA` is enforced unconditionally. A test asserts injection of a
  non-target SHA (or any `target_sha` kwarg) is rejected.
- **Exact plan command**: preflight uses `java -version` (not `java --version`).
- **Staged-diff preservation**: `git diff --cached --name-only` is captured into
  `git_state.staged_diff_name_only`; tests assert staged changes are preserved.
- **Protocol docs**: explicitly state that no trusted evidence bundle exists
  until a human performs two clean captures at the fixed SHA, and that no green
  status is claimed.

These are diagnostic-only hardening changes. They do **not** by themselves
produce a trusted bundle; the two-clean-capture human gate above still applies.

## GR-00 hardening applied (retry revision)

Additional hardening applied to the four GR-00 files in the retry pass:

- **Git status / diff / staged-diff failures are fatal**: `run_preflight` now
  records the return code of `git status --porcelain=v1`, `git diff --name-only`,
  and `git diff --cached --name-only`. If any fails (nonzero exit or launch
  error), `git_state.git_meta_ok` is `false` and the capture **fails closed**
  (exit `2`) with `git-meta-failed:<which>`. An unobservable checkout state is
  never recorded as authoritative.
- **Blob ID validation**: `git_blob_id` now validates that the resolved blob ID
  is a 40-hex Git SHA-1; any non-conforming value (garbage, path, multi-line, or
  truncated/forged) is rejected (`None`), so an untrusted blob ID cannot enter
  the input manifest.
- **Hostile custom-path rejection (backslash / UNC / drive / absolute /
  traversal)**: `validate_command_matrix` and `validate_bundle_paths` reject
  backslash separators, UNC shares (`//host/share`), Windows drive prefixes,
  POSIX absolute paths, and `..` traversal segments — not only leading-slash
  forms. The offending token is reduced to a bounded `<redacted-path>` marker in
  the warning so no raw machine/path content leaks into the bundle.
- **Warning-payload sanitization (fail-closed)**: every `infrastructure_warnings`
  entry is passed through `_sanitize_warning` at assembly. Hostile path forms in a
  warning payload are redacted to controlled markers and the payload is
  length-bounded; unrecognized/raw payload content is redacted by default, never
  passed through verbatim.
- **Preflight / version / environment metadata bounded sanitization**: interpreter
  version strings and allowlisted environment values are sanitized (absolute
  paths / secrets redacted) and length-bounded before persistence, so an unusual
  version string cannot leak raw payloads into `git-state.json` /
  `environment.json`.
- **Hostile regression tests added**: git-meta failure (full + partial), blob-ID
  validation (valid / non-hex / short / multi-line / nonzero-exit), custom-matrix
  backslash and UNC rejection, bundle-path backslash rejection, warning-payload
  sanitization (unit + integration), and version/environment metadata
  sanitization. The three existing traversal tests were updated to assert the
  sanitized `<redacted-path>` warning form (the raw `../` token is no longer
  leaked).
- **Protocol documentation updated**: `DB_GUARD_EVIDENCE_PROTOCOL.md` documents
  `git_meta_ok` as a fatal preflight condition, blob-ID validation, hostile-path
  rejection + warning sanitization, and bounded version/env metadata; the
  trusted-state definition lists `git-meta-failed` and the new condition (9).

These are diagnostic-only hardening changes. They do **not** by themselves
produce a trusted bundle; the two-clean-capture human gate above still applies.

## GR-00 hardening applied (second retry revision)

Additional hardening applied to the four GR-00 files in this pass, addressing the
remaining strict-review gaps:

- **Stop before any runner call on matrix/path validation failure**: when
  `validate_command_matrix` (custom matrices) or `validate_bundle_paths` (every
  matrix) reports a violation, `capture_evidence` now sets
  `matrix_validation_failed` and **skips all child-command execution**. The
  capture fails closed (exit `2`) with the recorded `invalid-matrix-argv:` /
  `invalid-bundle-path:` warnings and an empty `commands` list, so a hostile or
  malformed matrix is never executed. Previously the commands were still run even
  after validation flagged them.
- **Custom input-candidate realpath containment before reads**:
  `collect_input_manifest` now validates every candidate (including a custom
  `input_candidates` list) with `_is_safe_repo_relative_path` — which resolves
  via `os.path.realpath` and rejects non-strings, backslashes, UNC shares,
  Windows drive prefixes, POSIX absolute paths, `..` traversal, and symlink
  escapes — **before** any `open()` / `git rev-parse HEAD:` access. An unsafe
  candidate is never read or hashed; it is recorded as missing (fail closed on a
  missing required input) with its raw value reduced to the bounded
  `<redacted-unsafe-candidate>` marker so no machine/path content leaks into the
  input manifest.
- **Bounded persisted collections / counts / strings (fail-closed overflow
  diagnostics)**: the previously-unenforced finite bounds are now enforced with
  controlled overflow markers — `MAX_DIAGNOSTIC_CODES` (report diagnostic-code
  list → `OVERFLOW_DIAGNOSTIC_CODES`), `MAX_FINDING_COUNT` (report finding count
  → `OVERFLOW_FINDING_COUNT`, treated as unparseable), `MAX_MANIFEST_ENTRIES`
  (input manifest → `OVERFLOW_MANIFEST`), `MAX_WARNINGS` (infrastructure warnings
  → `OVERFLOW_WARNINGS`), and `MAX_SUMMARY_CHARS` (summary markdown truncated
  with `<truncated>`). Every bounded field fails closed rather than persisting
  unbounded content.
- **UNC / backslash and KeyboardInterrupt / StopIteration sanitization**: child
  combined output is redacted for UNC shares (`//host/share`), backslash paths,
  Windows drive prefixes, and POSIX absolute paths (`<redacted-path>`), and for
  exception-class text including `KeyboardInterrupt`, `StopIteration`, and
  `GeneratorExit` (`<redacted-exception>`), so raw machine/path/exception content
  never reaches the persisted logs.
- **No custom argv token persists verbatim**: every persisted argv token
  (command records and preflight command records) is passed through
  `_sanitize_argv_token`, which redacts secrets and absolute/UNC/backslash paths
  and length-bounds the token. A custom matrix embedding a secret/absolute token
  is rejected by `validate_command_matrix` (fail closed) and never executed.
- **Explicit artifact-kind metadata**: required artifacts continue to require
  explicit `artifact_kinds` metadata (`"file"` / `"dir"`); a missing/unknown kind
  fails closed with `missing-artifact-kind:<id>:<rel>`. Dot-in-basename inference
  remains absent.
- **Git filename sanitization**: `git status` / `git diff` / `git diff --cached`
  filenames continue to be normalized and sanitized (`_sanitize_git_filenames` /
  `_is_safe_git_filename`); hostile forms are reduced to `<redacted-path>`.
  - **Regression tests + docs for each fix**: `test_capture_db_guard_evidence.py`
   adds coverage for runner-stop-on-validation, input-candidate containment, every
   overflow bound, UNC/backslash + KeyboardInterrupt/StopIteration redaction, and
   argv-token sanitization; this ledger and `DB_GUARD_EVIDENCE_PROTOCOL.md` are
   updated to document the new fail-closed behavior.

These are diagnostic-only hardening changes. They do **not** by themselves
produce a trusted bundle; the two-clean-capture human gate above still applies.

## GR-00 hardening applied (latest strict-review pass)

Additional strict-review blockers addressed across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Porcelain status parsing corrected (untracked + rename forms)**:
  `_extract_git_filenames` now strips the two leading status columns for *every*
  porcelain line (the previous `m.group(1) != line[:2]` guard never stripped them,
  so `?? path` / ` M path` / `R  a -> b` were returned with their status columns
  attached). Filenames are now normalized **before** comparison, so untracked
  forbidden files and untracked `app/src/main` paths are correctly detected by the
  preservation check. Tests added for untracked-forbidden, untracked-production,
  staged, and mixed change surfaces.
- **Bounds enforced on all custom `CommandSpec` fields (fail closed)**: new
  constants `MAX_COMMAND_ID_LEN`, `MAX_PATH_LEN`, `MAX_REQUIRED_ARTIFACTS`,
  `MAX_ARTIFACT_KINDS` and overflow markers `OVERFLOW_COMMAND_ID`, `OVERFLOW_PATH`,
  `OVERFLOW_ARTIFACT_KINDS`. `validate_command_matrix` now bounds the command id,
  `log_name` / `report_path` / `required_artifacts` path strings, the
  `required_artifacts` and `artifact_kinds` counts, and every persisted collection
  (manifest, warnings, commands) is finite-bounded; overflow is replaced by a
  controlled marker rather than serializing unbounded content. Tests assert the
  persisted sizes stay within the `MAX_*` bounds.
- **Complete custom `CommandSpec` schema validation**: `validate_command_matrix`
  now requires an explicit valid artifact kind (`"file"` / `"dir"`) for *every*
  required artifact; a missing or non-`file`/`dir` kind fails closed with
  `missing-artifact-kind:<id>:<rel>` (and, because validation runs before any
  runner call, the capture stops with an empty `commands` list). Tests added for
  invalid/missing artifact-kind cases for both file and directory artifacts.
- **Symlink tests repaired to match stop-before-run**: every symlink / bundle-path
  rejection test now asserts **zero runner calls** and an **empty `commands` list**
  (the previously-broken `test_actual_symlink_report_path_rejected` accessed
  `commands[0]` which no longer exists after stop-before-run). New fixtures prove
  hash **containment** of nested internal regular files and **exclusion/rejection**
  of nested internal and external symlinks (an external symlink inside an artifact
  directory is never read or hashed; tampering the external target does not change
  the artifact hash).
- **Warning sanitization uses controlled codes/markers only**: `_sanitize_warning`
  now validates the leading warning code against a controlled pattern
  (`^[A-Za-z][A-Za-z0-9_-]*$`); an untrusted/malformed code is reduced to
  `<redacted>`. Secret assignments and hostile path forms in the payload are
  redacted, so arbitrary untrusted payload text is never persisted verbatim.
- **Protocol docs updated**: `DB_GUARD_EVIDENCE_PROTOCOL.md` documents the
  `MAX_*` bounds and `OVERFLOW_*` markers, the full custom `CommandSpec` schema
  validation (bounded id, path strings, explicit artifact kinds), the porcelain
  parsing guarantee for untracked/rename preservation, and the controlled
  warning-code sanitization. No claim of evidence existence or green status is made;
  the two-clean-capture human gate remains the only path to a trusted bundle.

## GR-00 hardening applied (retry revision — strict-review items 1–6)

The following strict-review blockers were addressed across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Porcelain status parsed positionally before trimming; staged/rename handled**:
  `_extract_git_filenames` now reads the two status columns by position (the
  separator is a single space at column 2) and strips them **before** any filename
  comparison. This correctly handles untracked (``?? path``), working-tree modified
  (`` M path``), staged added (``A  path``), and rename/copy (``R  a -> b`` /
  ``C  a -> b``) forms; a bare ``--name-only`` line (no status columns) is returned
  verbatim. The previous regex (`^(\S\S)\s+`) failed for any status whose second
  column was a space, leaking the status columns into the extracted filename and
  breaking untracked/staged/rename detection in the preservation check.
- **Forged/missing blob IDs are fatal**: `git_blob_id` already rejects any
  non-40-hex value, and `capture_evidence` now fails closed when a required input
  exists but its blob ID is missing/forged (`missing-blob-id:<rel>`), so an
  untrusted or uncommitted input can never be treated as a valid source of record.
  The fake test runner returns a valid 40-hex blob for `rev-parse HEAD:<path>` so
  the existing clean-capture tests remain green while the fail-closed path is
  covered by `test_missing_blob_id_fails_closed`.
- **Empty/non-string/malformed argv rejected by the CommandSpec pre-runner**:
  `validate_command_matrix` now treats an empty or non-string argv token as a
  violation (`invalid-matrix-argv:<id>:<empty-or-non-string>`) instead of silently
  skipping it, and rejects an empty argv (`invalid-matrix-argv:<id>:<empty>`). The
  pre-runner now validates **every** matrix (default + custom) before any runner
  call, so a malformed argv in the trusted default matrix is also caught (defense in
  depth). `test_validate_command_matrix_rejects_empty_and_nonstring_argv` locks this
  in.
- **Warning payloads controlled/redacted completely**: `_sanitize_warning` now also
  strips control characters (NUL/newline/tab/DEL) and redacts secret assignments, so
  an infrastructure warning can never smuggle raw machine/path/secret content or break
  JSON/log parsing. The leading code is still enforced against the controlled
  pattern; an untrusted/malformed code collapses to `<redacted>`. `_sanitize_warning_token`
  additionally redacts secrets before hostile-path redaction.
- **Manifest/warning bounds enforced before materialization; persisted no more than
  maxima**: the input manifest and the infrastructure-warnings list are now
  **truncated** (not merely flagged) when they exceed `MAX_MANIFEST_ENTRIES` /
  `MAX_WARNINGS`, so the persisted collections can never exceed the finite bounds.
  The manifest hash is recomputed from the truncated manifest for consistency. The
  overflow markers (`OVERFLOW_MANIFEST` / `OVERFLOW_WARNINGS`) still signal the
  truncation. `test_manifest_overflow_fails_closed` and
  `test_warnings_overflow_fails_closed` continue to assert the markers; the
  persisted-size assertions in `test_persisted_sizes_within_bounds` now hold for
  overflow cases as well.
- **Symlink artifact roots rejected; containment rechecked before hashing/opening**:
  `hash_artifact` now rejects a symlink artifact *root* outright (returns `None`) for
  both file and directory kinds, and re-checks every walked file against the bundle
  root (TOCTOU-safe) before opening/hashing. `capture_evidence` additionally rejects
  a symlink required artifact (`symlink-artifact:<rel>`) before type/hash handling.
  `test_hash_artifact_rejects_symlink_root` locks in the root-rejection behavior.

## GR-00 hardening applied (latest strict-review blockers)

Additional strict-review blockers addressed across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Collection bounds enforced during iteration (fail closed)**: candidate
  discovery (`discover_input_candidates`), the input manifest
  (`collect_input_manifest`), the validation-violation list
  (`validate_command_matrix`, bounded by the new `MAX_VIOLATIONS` /
  `OVERFLOW_VIOLATIONS`), the v2 diagnostic-code list (`parse_v2_report`), and the
  required-artifact hash set now **stop materializing once the finite bound is
  reached** and retain only the controlled overflow marker
  (`OVERFLOW_MANIFEST` / `OVERFLOW_VIOLATIONS` / `OVERFLOW_DIAGNOSTIC_CODES`). The
  persisted `input_manifest`, `infrastructure_warnings`, `commands`,
  `preflight_commands`, diagnostic codes, finding count, and summary markdown are
  all finite-bounded; a matrix exceeding `MAX_MATRIX_COMMANDS` stops the runner
  entirely (zero child calls, empty `commands`). Tests prove runner calls and
  persisted arrays stay bounded under overflow.
- **Required artifact / report / log hash failures fail closed (exit 2)**: a
  required artifact that is present and of the correct type but cannot be hashed
  (symlink swapped in, non-regular, read error, or replaced/changed mid-read) now
  sets `artifact-hash-failed:<rel>` and `capture_failed` (exit `2`). A command-log
  hash failure sets `launch_error=LOG_HASH_FAILED`; a required-report hash failure
  sets `parser_error=REPORT_HASH_FAILED` (which also triggers
  `invalid-required-report`). A hash-failure integration test covers each.
- **Race-safe reads / hashing (symlink + TOCTOU containment)**: inputs, v2
  reports, required artifacts, command logs, and top-level outputs are read/hashed
  through `_race_safe_read_bytes` / `_race_safe_hash_file`, which `os.lstat` first,
  open with `O_NOFOLLOW` where supported, `os.fstat` the descriptor to confirm
  identity (`st_dev`/`st_ino`) and size, and re-check after reading; a symlink,
  non-regular file, read error, or mid-read change is rejected (`None`) and fails
  the capture closed. The protocol documents the exact (best-effort on Windows)
  guarantee, and replacement/race-oriented tests cover symlink/non-regular
  rejection.
- **Injected `CommandSpec` / nested-field validation before access**: every matrix
  entry is checked with `isinstance(spec, CommandSpec)` and each nested field
  (`argv`, `required_artifacts`, `artifact_kinds`) is type-checked **before** any
  `len`/unpack/iteration. A non-`CommandSpec` entry, a non-`list`/`tuple` or
  non-string-bearing `argv`/`required_artifacts`, or a malformed `artifact_kinds`
  entry fails closed with `invalid-matrix-spec:<idx>[:<field>]` and **zero runner
  calls** (the validator runs before any child command). Tests cover
  non-`CommandSpec`, malformed `artifact_kinds`, and malformed `argv` inputs.
- **Closed `WARNING_CODE_ALLOWLIST` + structured `make_warning`**: warning
  acceptance is no longer a regex; `_sanitize_warning` admits only codes in the
  explicit `WARNING_CODE_ALLOWLIST` (documented exactly in the protocol), and
  `make_warning(code, *parts)` is the only sanctioned constructor — an unknown code
  or arbitrary payload collapses to `<redacted>`. Unknown-code and payload tests
  lock this in.
- **Exact SHA / preflight / manifest / artifact / hash / report / argv / privacy /
  docs / forbidden scope preserved**: `TARGET_SHA`, the preflight matrix, the
  dynamic input manifest, required-artifact type + hashing, v2 report parsing,
  repository-relative `argv`, privacy redaction, and the forbidden-file
  preservation scope are unchanged in behavior; only the hardening above was added.

These are diagnostic-only hardening changes. They do **not** by themselves produce
a trusted bundle; the two-clean-capture human gate above still applies, and no
green/blocked status is asserted for any SHA here.

## GR-00 hardening applied (latest strict-review blockers — non-string paths / overflow / output hash / snapshot)

Additional strict-review blockers addressed across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Nested `CommandSpec` field types validated before any `os.path` operation**:
  `validate_bundle_paths` now type-checks `log_name` / `report_path` /
  `required_artifacts` / `artifact_kinds` *before* any `os.path.join` /
  `os.path.realpath` call, so a malformed non-string value fails closed with a
  controlled `invalid-bundle-path:` warning instead of raising (`TypeError`) or
  being resolved unsafely. Because validation runs before any runner call, a
  non-string `log_name` / `report_path` yields **zero runner calls** and an empty
  `commands` list. `validate_command_matrix` already enforced the same type checks
  before its own `os.path` use. Tests cover non-string `log_name` and `report_path`
  (unit + integration, asserting zero runner calls).
- **Required-artifact hash collection bounded (fail closed)**: the set of required
  artifact hashes materialized during the required-artifact loop is bounded by the
  new `MAX_REQUIRED_ARTIFACT_HASHES`; the loop stops hashing once the finite
  aggregate limit is reached and fails closed with
  `OVERFLOW_REQUIRED_ARTIFACT_HASHES` (exit `2`), never materializing more than the
  bound. An overflow test asserts the marker and that the persisted hash set stays
  within the limit.
- **Top-level output hash failure fails closed (no empty hash)**: every top-level
  output in `output-sha256.txt` is hashed race-safely; a failure (symlink,
  non-regular file, read error, or replaced/changed mid-read) sets
  `capture_failed`, emits `output-hash-failed:<rel>`, marks the bundle untrusted,
  and returns exit `2`. An empty hash is **never** substituted for the failed
  output. A regression test asserts the diagnostic, the untrusted state, and that
  no empty-hash line appears in `output-sha256.txt`.
- **Report hash and parse share one stable byte snapshot**: a required report is
  read once via `_race_safe_read_bytes`; the same byte snapshot feeds both
  `report_sha256` and `parse_v2_report`, so the evidence can never combine a hash of
  one report version with a parse of another (TOCTOU / replacement). The unsafe,
  non-race-safe `sha256_file` helper was removed; all hashing now routes through the
  race-safe helpers. Tests assert the hash and parse both derive from the single
  snapshot.
- **Protocol/ledger claims kept accurate**: this ledger and the protocol doc make no
  claim that evidence exists and no claim of green status for any SHA; the
  two-clean-capture human gate remains the only path to a trusted bundle.

## GR-00 hardening applied (semantic / output-hash / warning-cap fixes)

Additional strict-review fixes applied across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Semantic summary excludes/normalizes run-specific output paths**:
  `build_semantic_summary` now normalizes every command `argv` token that begins
  with the repository-relative bundle prefix (`build/guard-evidence/<sha>/<run-id>/…`)
  to a stable `<bundle>/…` marker via the new `_normalize_semantic_argv` helper. The
  run id therefore never reaches `semantic-summary.json`, and two clean runs at the
  same SHA produce byte-identical semantic summaries (regression test
  `test_semantic_summary_argv_normalizes_run_specific_paths` plus the existing
  `test_semantic_summaries_equal_across_runs`).
- **`output-sha256.txt` uses documented bundle-relative top-level names
  consistently**: the contract now records each top-level output by its
  **bundle-relative top-level name** (relative to the bundle directory, e.g.
  `git-state.json`) rather than the repository-relative bundle path
  (`build/guard-evidence/<sha>/<run-id>/git-state.json`). The names are stable
  across run ids and match the documented contract exactly (regression test
  `test_output_sha256_uses_bundle_relative_top_level_names`).
- **Final hashes correspond to final rewritten artifacts; failure path not stale**:
  `output-sha256.txt` is now written **last**, after any rewrite of the evidence /
  semantic / summary artifacts triggered by a top-level output-hash failure or the
  warning cap. Its hashes are recomputed from the final on-disk artifacts, so they
  never reflect a stale pre-rewrite version. A failed output is excluded and is
  never substituted with an empty hash (regression test
  `test_output_sha256_reflects_rewritten_artifacts_on_failure`).
- **Warnings are capped after final diagnostics**: the `MAX_WARNINGS` cap is now
  applied **after** the `output-hash-failed` diagnostic is appended during
  top-level output hashing (and after the warning cap itself is detected), so the
  persisted `infrastructure_warnings` list can never exceed `MAX_WARNINGS` even when
  a hash failure pushes it over the bound. The cap truncates to the bound with the
  `OVERFLOW_WARNINGS` marker and fails closed (regression test
  `test_warnings_capped_after_output_hash_failure`).

## GR-00 refinements (embedded bundle-path masking / MAX_WARNINGS==0 / final-pass consistency)

Refinements applied across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Semantic argv masks embedded run-specific bundle paths (any position)**:
  `_normalize_bundle_prefix` now replaces **every** complete path segment equal to
  the repository-relative bundle prefix (`build/guard-evidence/<sha>/<run-id>`), not
  only a leading prefix or an exact match. This covers bare tokens, prefix forms,
  equals-form values (`--output=<bundle>/x.json`, `prefix=<bundle>`), and a bundle
  path embedded in the middle of a larger value (`some/dir/<bundle>/nested`). Only
  complete path segments are masked (a segment boundary is start-of-string or `/`
  on both sides), so a longer component that merely contains the prefix as a
  substring (e.g. `out/run-10` when the prefix is `out/run-1`) is left untouched.
  The run id therefore never reaches `semantic-summary.json` regardless of where
  the bundle path appears in an argv token (regression tests
  `test_semantic_argv_normalizes_output_and_prefix_equals_forms` and
  `test_semantic_argv_normalizes_embedded_bundle_path`).
- **`MAX_WARNINGS == 0` is an exact empty cap**: when `MAX_WARNINGS` is `0` the
  persisted `infrastructure_warnings` list is empty (not even the `OVERFLOW_WARNINGS`
  marker is kept), yet the capture still fails closed (exit `2`, `trusted: false`)
  so the overflow is signaled via the exit code rather than a persisted warning.
  The persisted list therefore never exceeds `MAX_WARNINGS` **including zero**
  (regression test `test_warnings_capped_at_zero_max_warnings`).
- **Final output-hash pass failure yields consistent untrusted evidence**: a
  top-level output whose hash succeeds on the first pass but fails on the **final**
  pass (a TOCTOU / replacement between the two passes) still sets `capture_failed`,
  emits the controlled `output-hash-failed:<rel>` diagnostic, and rewrites the
  on-disk evidence / semantic / summary artifacts to a consistent untrusted state
  (never a stale pre-failure trusted artifact). No empty hash is substituted into
  `output-sha256.txt` (regression test
  `test_final_output_hash_pass_failure_is_consistent_and_untrusted`).

These are diagnostic-only hardening changes. They do **not** by themselves produce
a trusted bundle; the two-clean-capture human gate above still applies, and no
green/blocked status is asserted for any SHA here.

## GR-00 hardening applied (typed report parsing / final-rehash convergence / raw porcelain / bounded path violations)

Additional strict-review fixes applied across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **Strict typed v2 report containers (`parse_v2_report`)**: `findings` and
  `diagnostics` must be JSON arrays, `statistics` must be a JSON object, every
  diagnostic must be an object carrying a string `code`, and every finding must
  be a JSON object with at most `MAX_FINDING_KEYS` keys. Every nested value is
  type-checked **before any attribute access**, so the parser never raises on a
  hostile shape; a wrong-type container or entry returns a controlled
  `parser_error` (`MALFORMED_FINDINGS`, `MALFORMED_DIAGNOSTICS`,
  `MALFORMED_DIAGNOSTIC_ENTRY`, `MALFORMED_STATISTICS`,
  `MALFORMED_FINDING_ENTRY`) instead of being partially accepted. Two prior gaps
  are closed: a list-typed `statistics` previously raised `AttributeError`
  (violating the never-raise contract), and a dict-typed `findings` was silently
  counted by its keys with `parser_error` `None`; a diagnostic entry without a
  string `code` was previously skipped silently.
- **Final output-hash convergence (no lost final diagnostic)**: the final rehash
  pass over the top-level outputs now records every failure (controlled
  `output-hash-failed:<rel>` warning, `capture_failed`, permanent exclusion from
  `output-sha256.txt`) and then converges deterministically: the capped finalize
  helper rewrites evidence / semantic / summary so every accumulated final-pass
  diagnostic is persisted, and `output-sha256.txt` is recomputed from hashes
  taken **after** that rewrite. Each failing pass permanently excludes at least
  one output, so the loop is bounded and cannot spin; the previous single-shot
  retry silently dropped (`continue`) any failure detected during its post-rewrite
  rehash.
- **Raw porcelain parsing in `preservation_check`**: porcelain
  `git status --porcelain=v1` lines are no longer routed through the stripping
  `name_only` helper before `_extract_git_filenames`. Status columns are parsed
  positionally on the raw line, so the leading-space working-tree/staged form
  (` M path`) resolves to the bare path instead of being mangled into
  `M path` (which matches neither the forbidden set nor `app/src/main`).
- **Bounded violation collection in `validate_bundle_paths`**: violations are
  bounded **during iteration** — once `MAX_VIOLATIONS` is reached, both the spec
  loop and the per-artifact loop stop materializing violations and only the
  controlled `OVERFLOW_VIOLATIONS` marker is retained. In
  `validate_command_matrix` the bound is enforced at each command-spec boundary
  and the returned list is then truncated to the bound; in both validators the
  returned violation set never exceeds `MAX_VIOLATIONS`.

Regression tests were added for each behavior (statistics list / diagnostics dict
/ findings dict / non-string diagnostic code / malformed finding shape; first-pass
success with a single-output final-pass failure verified against persisted
warnings, summary, semantic trust state, and recomputed hashes; leading-space
staged porcelain detection; bundle-path violation overflow). These tests have
**not been executed in this editing pass** (no test/build commands were run);
the targeted suite
(`pytest scripts/ci/test_capture_db_guard_evidence.py`) is still **pending**.
Status remains **pending** until that run is green; no green/blocked status is
asserted for any SHA here.

## GR-00 hardening applied (dead-code exception handlers / test arity / test setup fixes)

Strict-review blockers fixed in this pass, touching only
`scripts/ci/capture_db_guard_evidence.py` and
`scripts/ci/test_capture_db_guard_evidence.py`:

- **Dead-code exception handlers removed (both command-run sites)**: in
  `run_command` and in the `capture_evidence` command loop, the previous
  `except BaseException: raise` followed by `except Exception:` was unreachable
  dead code (`BaseException` catches everything first), so an ordinary failure
  (e.g. `OSError` / `FileNotFoundError` from a missing executable) crashed the
  whole capture instead of being recorded. Both sites now catch `Exception`
  only: `KeyboardInterrupt` / `SystemExit` derive directly from
  `BaseException` and still propagate unchanged, while an ordinary failure is
  converted to a controlled `LAUNCH_FAILED` outcome. The loop emits a bounded
  fallback `CommandResult` (sanitized argv, `exit_code: null`,
  `launch_error="LAUNCH_FAILED"`) so the bundle stays complete and the capture
  fails closed with exit `2`.
- **Test arity fixes**: `_fake_matrix(root, out_dir)` requires two arguments;
  `test_semantic_summaries_equal_across_runs` (both calls) and
  `test_output_traversal_rejected` previously passed one and would have raised
  `TypeError`. All `_fake_matrix(` call sites now pass `(root, out)`.
- **Test setup fixes**: `test_required_artifact_hash_failure_fails_closed` and
  `test_required_artifact_hash_overflow_fails_closed` wrote pre-seeded artifacts
  into the bundle directory before it existed (`FileNotFoundError` at setup);
  both now create the directory before writing.

These are diagnostic-only corrections. No tests were executed in this editing
pass; the targeted suite remains **pending**, and no green/blocked status is
asserted for any SHA here.

## GR-00 hardening applied (command-id sanitization / fake-runner cwd resolution / doc corrections)

Strict-review fixes applied across the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`):

- **`CommandSpec.id` sanitized before persistence**: every persisted
  `CommandResult.id` — the normal path in `run_command`, the
  bundle-path-escape early return, and the bounded fallback record in the
  `capture_evidence` command loop — now passes through the new
  `_sanitize_command_id` helper: a non-string or empty id collapses to
  `<non-string>`, secret assignments and absolute/UNC/backslash path forms are
  redacted, control characters are stripped, and the result is length-bounded by
  `MAX_COMMAND_ID_LEN`. A short-but-hostile custom id (which passes matrix
  validation) can therefore never leak raw payload content into `evidence.json`,
  `semantic-summary.json`, or `summary.md`; warning payloads built from the id
  remain covered by `make_warning`. Unit and integration regression tests lock
  this in.
- **Fake test runners resolve artifact writes against the runner `cwd`**: every
  fake-runner closure that mirrors child artifact writes (`_write_fake_outputs`
  and the five inline report writers) now resolves the repository-relative
  output paths embedded in `argv` against the runner's ``cwd`` argument (the
  repository root the capture passes), never against the test process's current
  working directory. Previously those reports landed outside the fake repository
  whenever pytest ran from any other directory, so the targeted suite could not
  pass regardless of code correctness.
- **`validate_command_matrix` overflow test added**: a dedicated test proves the
  returned violation set stays within `MAX_VIOLATIONS` and terminates with the
  controlled `OVERFLOW_VIOLATIONS` marker when a hostile matrix would generate
  far more violations than the bound (mirroring the existing
  `validate_bundle_paths` overflow test).
- **Protocol/ledger details corrected**: the protocol's `preservation` object
  now lists the full field set actually persisted (`ok`, `policy_ok`,
  `production_ok`, `staged_ok`, `untracked_ok`, `checked_paths`,
  `forbidden_changed`); the ratchet matrix row shows the equals-form
  `--guard-name=db_access` token actually executed; and the validator bounding
  wording now states accurately that `validate_bundle_paths` bounds during
  iteration (including inside its per-artifact loop) while
  `validate_command_matrix` bounds at the command-spec boundary and truncates
  the returned list to the bound.

These are diagnostic-only corrections. No tests were executed in this editing
pass; the targeted suite (`pytest scripts/ci/test_capture_db_guard_evidence.py`)
remains **pending**, and no green/blocked status is asserted for any SHA here.

## GR-00R applied (caller-stated run pin + command-log completeness contract) — pending human validation

PR-GR-00R changes were applied to the four GR-00 files
(`scripts/ci/capture_db_guard_evidence.py`,
`scripts/ci/test_capture_db_guard_evidence.py`,
`docs/ci/DB_GUARD_EVIDENCE_PROTOCOL.md`, `docs/ci/DB_GUARD_HARDENING_LEDGER.md`).
Status: **pending human validation** — no DONE/GREEN/complete claim is made
here; the two-clean-capture human gate above still applies.

- **Run-pin replacement (rationale)**: the previous hard-coded target pin
  (`TARGET_SHA = 9b97e7979130de605d164386bbf719cf20579475`, enforced as an
  unconditional `HEAD == TARGET_SHA` preflight) made the tool **unusable at
  current SHAs**: the repository has advanced past the pinned commit, so every
  capture at any newer HEAD was rejected `wrong-sha` and no fresh evidence
  could ever be produced. The fixed pin is removed. The expected commit is now
  a **mandatory, caller-stated per-run pin** (`--expected-sha
  <40-lowercase-hex>` / `expected_sha=`): exactly 40 lowercase hex characters,
  **never derived from HEAD**, validated before any runner invocation
  (`missing-expected-sha` / `invalid-expected-sha`; an invalid value itself is
  never persisted verbatim). A pre-launch gate requires the observed
  `git rev-parse HEAD` to equal the requested pin before any matrix command
  starts (mismatch → `wrong-sha:<observed>`, exit `2`, zero commands run).
  `git-state.json` and `evidence.json` record `requested_sha` / `observed_sha`
  / `tree_sha`; `semantic-summary.json` records `requested_sha`
  (run-invariant, so byte-identical comparison across two runs at the same SHA
  still holds). A **post-capture drift re-check** re-observes HEAD/tree/status
  after the matrix; any drift or unverifiable post-state fails the capture
  closed (exit `2`) with
  `post-capture-drift:<head|tree|status|unverifiable>`.
- **Command-log completeness contract**: every command log is in exactly one of
  two states — **COMPLETE** (entire combined stream captured within
  `CHILD_OUTPUT_LIMIT`, published atomically, read back, hashed:
  `log_complete=true`, `log_sha256` set, `log_failure_code=null`) or
  **INCOMPLETE** (`log_failure_code` a controlled constant from
  `{output-limit-exceeded, log-write-failed, log-unreadable}`; `log_sha256`
  empty — never hashed as valid evidence). Logs are persisted through a
  streaming **atomic temporary-log sink** (sanitized incremental chunks into a
  sibling temp file, then fsync + `os.replace` onto the final name), shared by
  the live merged-pipe production runner and injected runners. Cap exceedance
  appends the single `<truncated>` marker once, keeps draining the child pipe
  (no deadlock), and fails the whole capture closed (exit `2`). Raw log text
  never reaches `evidence.json` / `summary.md`. The former
  `launch_error=LOG_HASH_FAILED` sentinel is superseded by the structured
  `log-unreadable` code.
- **New warning codes** (added to the closed `WARNING_CODE_ALLOWLIST`):
  `missing-expected-sha`, `invalid-expected-sha`, `post-capture-drift`, and
  `incomplete-command-log:<id>:<code>` (payload is the sanitized command id
  plus a controlled `LOG_FAILURE_CODES` constant).
- **Schema field additions**: `evidence.json` and `git-state.json` gain
  `requested_sha` / `observed_sha` / `tree_sha`; `semantic-summary.json` gains
  `requested_sha`; per-command records gain `log_bytes` (published log size in
  bytes), `log_complete`, and `log_failure_code`. The three log-volume fields
  are deliberately excluded from `semantic-summary.json` (documented
  determinism choice: byte counts vary between runs; incompleteness already
  forces exit `2` and is reflected in `trusted`).
- **Supersession note**: earlier sections of this ledger that require captures
  "at the fixed SHA `9b97e79…`" describe the retired GR-00 pin. Under GR-00R
  the tool has no hard-coded target; the two-clean-capture human gate applies
  at whatever SHA the caller pins with `--expected-sha`, with both runs exiting
  `0` and producing byte-identical `semantic-summary.json` files.

These are diagnostic-only changes. They do **not** by themselves produce a
trusted bundle; the two-clean-capture human gate above still applies, and no
green/blocked status is asserted for any SHA here. The targeted test suite
(`pytest scripts/ci/test_capture_db_guard_evidence.py`) has **not** been
executed in this documentation pass and remains **pending**.
