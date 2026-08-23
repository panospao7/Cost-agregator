#!/usr/bin/env python3
"""test_capture_db_guard_evidence.py

Pytest suite for scripts/ci/capture_db_guard_evidence.py.

Every test uses temporary fake commands and files; none invoke Gradle, scan the
real repository, or touch production Kotlin / policy / baseline / config files.
The capture tool is exercised through its importable ``capture_evidence`` API
with an injectable runner and an injectable command matrix, so the real guard
control plane is never executed here.
"""

from __future__ import annotations

import fnmatch
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import capture_db_guard_evidence as cap  # noqa: E402

REPORT_SCHEMA = cap.REPORT_SCHEMA
REPORT_SCHEMA_VERSION = cap.REPORT_SCHEMA_VERSION

TEST_SHA = "9b97e7979130de605d164386bbf719cf20579475"
# Must be a valid 40-hex Git SHA so preflight identity validation accepts it.
TEST_TREE = "1111111111111111111111111111111111111111"

# The complete set of tracked input files created by ``_make_root``.  The fake
# git runner reports exactly these via ``git ls-files`` so the dynamic input
# manifest discovery can be exercised.
TRACKED_FILES = [
    "scripts/verify_db_access_boundaries.py",
    "scripts/ci/guard_ratchet.py",
    "scripts/ci/guard_registry.py",
    "scripts/ci/run_static_guard_suite.py",
    "scripts/ci/guard_findings.py",
    "config/baselines/db_access.json",
    "config/guards/db_ownership_policy.yml",
    "config/guards/db_ownership_policy.signatures.candidate.yml",
    "config/guards/db_structural_exceptions.yml",
    "config/guards/db_structural_exceptions_expected_methods.yml",
    "config/guards/db_raw_query_classification.yml",
    "config/guards/production_source_roots.yml",
    "app/build.gradle.kts",
    ".github/workflows/ci.yml",
    "settings.gradle.kts",
    "scripts/db_guard/__init__.py",
    "scripts/db_guard/dao_accessors.py",
    "scripts/db_guard/declaration_scanner.py",
    "scripts/db_guard/reporting.py",
    "scripts/db_guard/room_inventory.py",
    "scripts/db_guard/scanner.py",
    "scripts/db_guard/sql_classifier.py",
]

# Cross-platform absolute-path / backslash detector for stored (repo-relative)
# paths.  A repository-relative path uses POSIX separators and must never be an
# absolute path on either platform: no leading "/", no backslash, and no Windows
# drive-letter prefix (e.g. "C:\" or "C:/").
_DRIVE_LETTER_RE = re.compile(r"[A-Za-z]:[\\/]")


def _assert_repo_relative(path: str) -> None:
    assert "\\" not in path, f"backslash in stored path: {path!r}"
    assert not path.startswith("/"), f"absolute path in stored path: {path!r}"
    assert not _DRIVE_LETTER_RE.search(path), f"drive letter in stored path: {path!r}"


def _assert_no_absolute(token: str) -> None:
    """A stored argv token must never be an absolute (machine) path."""
    assert "\\" not in token, f"backslash in argv token: {token!r}"
    assert not token.startswith("/"), f"absolute path in argv token: {token!r}"
    assert not _DRIVE_LETTER_RE.search(token), f"drive letter in argv token: {token!r}"


# ── Semantic command matching (fixture helpers) ───────────────────────────────
def _argv_basename(token) -> str:
    """Basename of an argv token, tolerant of POSIX and Windows separators."""
    return os.path.basename(str(token).replace("\\", "/"))


def _matches(argv, script_name: str, *flags: str) -> bool:
    """Semantic match for a child command invocation.

    True iff any argv element's basename equals ``script_name`` (so the
    repository-relative prefixed tokens used by the production command
    matrices — e.g. ``scripts/verify_db_access_boundaries.py`` — still match)
    AND every flag in ``flags`` appears verbatim in argv.  The verbatim-flag
    requirement keeps sibling invocations of the same script unambiguous
    (``--inventory-only`` vs ``--fail-on-violation``) and prevents a ratchet
    command that merely embeds the script as a ``--command-arg=<value>`` token
    from firing the db-cli branch.
    """
    if not any(_argv_basename(token) == script_name for token in argv):
        return False
    return all(flag in argv for flag in flags)


def _is_git_cmd(argv, subcommand: str, *leading: str) -> bool:
    """Semantic match for ``git <subcommand>`` carrying every ``leading`` token.

    Tokens are compared verbatim and order-insensitively against ``argv[2:]``,
    so extra flags/pathspecs (e.g. the ``--`` pathspec lists sent by the
    preservation checker) do not break the match, while distinct revisions
    (``HEAD`` vs ``HEAD^{tree}`` vs ``HEAD:<path>``) remain separate tokens and
    never cross-match.
    """
    if len(argv) < 2 or argv[0] != "git" or argv[1] != subcommand:
        return False
    rest = list(argv[2:])
    return all(token in rest for token in leading)


# ── Fake runner ───────────────────────────────────────────────────────────────
class FakeOutcome:
    def __init__(self, returncode: int, combined: str = "") -> None:
        self.returncode = returncode
        self.combined = combined


def _write_fake_report(path: str, *, trusted: bool, codes=(), findings=0) -> None:
    finding_list = [
        {"rule": "X", "severity": "error", "path": "p",
         "location": {"line": 1},
         "symbol": {"owner": "o", "name": f"n{i}", "parameters": [],
                    "kind": "function"},
         "identity": {"operation": "x"}, "message": "m"}
        for i in range(findings)
    ]
    report = {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": "db_access",
        "findings": finding_list,
        "diagnostics": [{"code": c} for c in codes],
        "statistics": {"trusted": trusted, "files_scanned": 1},
    }
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle)


def _write_fake_outputs(argv, cwd):
    """Mirror what the real commands would write, derived from argv flags.

    Output paths embedded in ``argv`` are repository-relative (the capture tool
    runs every child command with ``cwd`` = the repository root), so every
    artifact write is resolved against the runner's ``cwd`` argument — never
    against this test process's current working directory.
    """
    def _resolve(rel):
        return os.path.join(str(cwd), rel)

    if "--findings-output" in argv:
        idx = argv.index("--findings-output")
        out = _resolve(argv[idx + 1])
        if "--inventory-only" in argv:
            _write_fake_report(out, trusted=True)
        else:
            _write_fake_report(out, trusted=False, codes=["DB_POLICY_INCOMPLETE_V2"])
    if "--dump-room-mutators" in argv:
        idx = argv.index("--dump-room-mutators")
        out = _resolve(argv[idx + 1])
        parent = os.path.dirname(out)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(out, "w", encoding="utf-8") as handle:
            json.dump([], handle)
    if "--output-dir" in argv:
        idx = argv.index("--output-dir")
        out_dir = _resolve(argv[idx + 1])
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as handle:
            json.dump({"ok": True}, handle)


class ConfigurableFakeRunner:
    """Injectable runner that fakes git/version preflight and command outputs.

    Branch matching is semantic, not raw membership: a guard-script branch
    fires when any argv element's basename equals the script name (so the
    repo-relative prefixed tokens used by the production command matrices —
    e.g. ``scripts/ci/guard_ratchet.py`` — match) AND the branch's semantic
    flags appear verbatim in argv.  Git subcommands match via ``_is_git_cmd``.
    Anything unmatched falls through explicitly to ``(0, "ok")`` and is
    recorded in ``fallthroughs`` so tests can assert which commands went
    unmatched instead of silently succeeding.
    """

    def __init__(self, *, dirty: bool = False, command_returncodes=None,
                 launch_fail_token: str = None, write_reports: bool = True,
                 staged_diff: str = "", untracked: str = "") -> None:
        self.dirty = dirty
        self.command_returncodes = dict(command_returncodes or {})
        self.launch_fail_token = launch_fail_token
        self.write_reports = write_reports
        self.staged_diff = staged_diff
        self.untracked = untracked
        self.calls: list = []
        # Explicit fallthrough log: every argv that matched no registered
        # branch (assertable; the fallthrough always returns exit 0).
        self.fallthroughs: list = []

    def __call__(self, argv, cwd):
        argv = list(argv)
        self.calls.append(argv)
        if self.launch_fail_token is not None and self.launch_fail_token in argv:
            raise FileNotFoundError("simulated missing executable")
        if argv and argv[0] == "git":
            return self._git(argv)
        if argv and _argv_basename(argv[0]) == "gradlew":
            return FakeOutcome(self.command_returncodes.get("gradle", 1), "gradle output")
        if "pytest" in argv:
            return FakeOutcome(self.command_returncodes.get("pytest", 0), "pytest output")
        if _matches(argv, "verify_guard_registry.py"):
            return FakeOutcome(self.command_returncodes.get("registry", 0), "registry ok")
        if _matches(argv, "guard_ratchet.py"):
            return FakeOutcome(self.command_returncodes.get("ratchet", 2), "ratchet blocked")
        if _matches(argv, "run_static_guard_suite.py"):
            if self.write_reports:
                _write_fake_outputs(argv, cwd)
            return FakeOutcome(self.command_returncodes.get("static", 0), "static ok")
        if _matches(argv, "verify_db_access_boundaries.py", "--inventory-only"):
            rc = self.command_returncodes.get("inventory", 0)
            if self.write_reports:
                _write_fake_outputs(argv, cwd)
            return FakeOutcome(rc, "inventory ok" if rc == 0 else "inventory blocked")
        if _matches(argv, "verify_db_access_boundaries.py", "--fail-on-violation"):
            rc = self.command_returncodes.get("dbcli", 2)
            if self.write_reports:
                _write_fake_outputs(argv, cwd)
            return FakeOutcome(rc, "db cli ok" if rc == 0 else "db cli blocked")
        # Explicit fallthrough: no registered branch matched this command.
        self.fallthroughs.append(argv)
        if self.write_reports:
            _write_fake_outputs(argv, cwd)
        return FakeOutcome(0, "ok")

    def _git(self, argv):
        if _is_git_cmd(argv, "rev-parse", "HEAD^{tree}"):
            return FakeOutcome(0, TEST_TREE)
        if _is_git_cmd(argv, "rev-parse", "HEAD"):
            return FakeOutcome(0, TEST_SHA)
        if len(argv) == 3 and argv[1] == "rev-parse" and argv[2].startswith("HEAD:"):
            # Any committed input resolves to a valid 40-hex blob ID so the
            # forged/missing-blob-ID fail-closed path can be exercised by tests
            # that deliberately return an invalid blob.
            return FakeOutcome(0, "a" * 40)
        if _is_git_cmd(argv, "status", "--porcelain=v1"):
            base = " M config/guards/db_ownership_policy.yml\n" if self.dirty else ""
            # Surface untracked paths as porcelain ``??`` entries so the
            # preservation checker can observe them.
            if self.untracked:
                for u in self.untracked.splitlines():
                    if u.strip():
                        base += f"?? {u.strip()}\n"
            return FakeOutcome(0, base)
        if _is_git_cmd(argv, "diff", "--cached", "--exit-code"):
            # Staged changes vs HEAD fail closed when any staged diff exists.
            return FakeOutcome(1 if self.staged_diff else 0, "")
        if _is_git_cmd(argv, "diff", "--exit-code") and "--cached" not in argv:
            return FakeOutcome(1 if self.dirty else 0, "")
        if _is_git_cmd(argv, "diff", "--cached", "--name-only"):
            return FakeOutcome(0, self.staged_diff)
        if _is_git_cmd(argv, "diff", "HEAD", "--name-only"):
            # Combined staged + unstaged modifications vs HEAD.
            return FakeOutcome(0, self.staged_diff or "")
        if _is_git_cmd(argv, "diff", "--name-only") and "--cached" not in argv:
            return FakeOutcome(0, "")
        if _is_git_cmd(argv, "log", "--oneline", "-20"):
            return FakeOutcome(0, f"{TEST_SHA} base commit\n")
        if _is_git_cmd(argv, "ls-files") and "--others" in argv[2:]:
            # Untracked paths (never tracked files).
            return FakeOutcome(0, self.untracked or "")
        if _is_git_cmd(argv, "ls-files"):
            # Return tracked files matching the requested pathspec patterns.
            rest = list(argv[2:])
            patterns = rest[rest.index("--") + 1:] if "--" in rest else rest
            matched = [
                f for f in TRACKED_FILES
                if any(fnmatch.fnmatch(f, p) for p in patterns)
            ]
            return FakeOutcome(0, "\n".join(matched))
        return FakeOutcome(0, "")


# ── Fixture helpers ───────────────────────────────────────────────────────────
def _make_root(tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    (root / "scripts" / "ci").mkdir(parents=True)
    (root / "config" / "guards").mkdir(parents=True)
    (root / "config" / "baselines").mkdir(parents=True)
    (root / "app").mkdir(parents=True)
    (root / ".github" / "workflows").mkdir(parents=True)

    fake_inputs = {rel: _fake_input_content(rel) for rel in TRACKED_FILES}
    for rel, content in fake_inputs.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return root


def _fake_input_content(rel: str) -> str:
    """Deterministic fake content for a tracked input path."""
    if rel.endswith(".py"):
        return "print('fake')\n"
    if rel.endswith(".json"):
        return "{}\n"
    if rel.endswith(".yml") or rel.endswith(".yaml"):
        return "entries: []\n"
    if rel.endswith(".kts"):
        return "// gradle\n"
    if rel.endswith(".yml") or rel == ".github/workflows/ci.yml":
        return "name: ci\n"
    return "// settings\n"


def _fake_matrix(root, out_dir):
    """A custom command matrix using repository-relative argv/report paths.

    Mirrors the real ``default_command_matrix`` contract: argv tokens that point
    at bundle outputs are repository-relative (``bundle_rel/...``), while
    ``report_path`` is relative to the bundle directory.  This keeps the matrix
    valid under ``validate_command_matrix`` (no absolute/outside paths).
    """
    bundle_rel = cap._posix_rel(out_dir, str(root))

    def rop(*parts):
        return "/".join([bundle_rel, *parts])

    return [
        cap.CommandSpec(
            id="registry-validation", log_name="00-registry.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py"],
        ),
        cap.CommandSpec(
            id="room-inventory", log_name="02-room-inventory.log",
            argv=["python3", "scripts/verify_db_access_boundaries.py", "--inventory-only",
                  "--findings-output", rop("02-room-inventory.findings.json"),
                  "--dump-room-mutators", rop("02-room-mutators.json")],
            report_path="02-room-inventory.findings.json",
            required_artifacts=("02-room-inventory.findings.json", "02-room-mutators.json"),
            artifact_kinds=(
                ("02-room-inventory.findings.json", "file"),
                ("02-room-mutators.json", "file"),
            ),
        ),
        cap.CommandSpec(
            id="db-cli", log_name="03-db-cli.log",
            argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation",
                  "--findings-output", rop("03-db-cli.findings.json")],
            report_path="03-db-cli.findings.json",
            required_artifacts=("03-db-cli.findings.json",),
            artifact_kinds=(("03-db-cli.findings.json", "file"),),
        ),
        cap.CommandSpec(
            id="static-suite", log_name="05-static-suite.log",
            argv=["python3", "scripts/ci/run_static_guard_suite.py", "--output-dir", rop("05-static-suite")],
            required_artifacts=("05-static-suite", "05-static-suite/summary.json"),
            artifact_kinds=(
                ("05-static-suite", "dir"),
                ("05-static-suite/summary.json", "file"),
            ),
        ),
        cap.CommandSpec(
            id="gradle-db", log_name="06-gradle-db.log",
            argv=["./gradlew", ":app:verifyDbAccessBoundaries", "--no-daemon"],
        ),
    ]


def _default_candidates():
    # Discovery is now the default path inside capture_evidence; returning None
    # keeps any legacy caller using the dynamic tracked-file manifest.
    return None


def _db_cli_matrix(root, out, findings_json="03-db-cli.findings.json"):
    """A single db-cli command spec with repository-relative argv/report paths."""
    bundle_rel = cap._posix_rel(str(out), str(root))
    return cap.CommandSpec(
        id="db-cli", log_name="03-db-cli.log",
        argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation",
              "--findings-output", "/".join([bundle_rel, findings_json])],
        report_path=findings_json,
        required_artifacts=(findings_json,),
        artifact_kinds=((findings_json, "file"),),
    )


# ── Fixture regression tests: semantic runner matching ────────────────────────
def test_fake_runner_matches_prefixed_production_argv(tmp_path):
    """Regression (GR-00): branch matching must be basename-based so the
    repo-relative prefixed tokens used by the production command matrices
    (``scripts/verify_db_access_boundaries.py``) reach the intended branch
    instead of silently hitting the exit-0 fallthrough."""
    scratch = str(tmp_path)
    runner = ConfigurableFakeRunner(dirty=False)
    # The db-cli branch fires with its blocked default, never the fallthrough.
    dbcli_outcome = runner(["python3", "scripts/verify_db_access_boundaries.py",
                            "--fail-on-violation"], scratch)
    assert dbcli_outcome.returncode == 2
    assert runner.fallthroughs == []
    # Flag gating keeps sibling invocations of the same script distinct.
    inv_outcome = runner(["python3", "scripts/verify_db_access_boundaries.py",
                          "--inventory-only"], scratch)
    assert inv_outcome.returncode == 0
    # Prefixed guard-tool commands match their registered branches too.
    assert runner(["python3", "scripts/ci/verify_guard_registry.py"], scratch).returncode == 0
    assert runner(["python3", "scripts/ci/run_static_guard_suite.py",
                   "--output-dir", "out/run-1/05-static-suite"], scratch).returncode == 0
    # A ratchet command embedding the db script as a ``--command-arg=`` value
    # fires the ratchet branch, not the db-cli branch (verbatim-flag gating:
    # ``--command-arg=--fail-on-violation`` is not a verbatim flag).
    ratchet_outcome = runner(["python3", "scripts/ci/guard_ratchet.py",
                              "--command-arg=python3",
                              "--command-arg=scripts/verify_db_access_boundaries.py",
                              "--command-arg=--fail-on-violation"], scratch)
    assert ratchet_outcome.returncode == 2


def test_fake_runner_git_matching_is_flag_semantic():
    """Regression (GR-00): git subcommand matching is semantic (subcommand +
    verbatim flag tokens), so pathspec-bearing preflight/preservation forms
    match the intended branch regardless of extra arguments."""
    runner = ConfigurableFakeRunner(dirty=False, staged_diff="config/staged.yml\n")
    assert runner(["git", "rev-parse", "HEAD"], ".").combined.strip() == TEST_SHA
    assert runner(["git", "rev-parse", "HEAD^{tree}"], ".").combined.strip() == TEST_TREE
    assert runner(["git", "rev-parse", "HEAD:scripts/x.py"], ".").combined.strip() == "a" * 40
    assert runner(["git", "status", "--porcelain=v1"], ".").combined == ""
    assert runner(["git", "diff", "--cached", "--name-only"], ".").combined == \
        "config/staged.yml\n"
    assert runner(["git", "diff", "--name-only"], ".").returncode == 0
    assert runner(["git", "diff", "HEAD", "--name-only"], ".").combined == \
        "config/staged.yml\n"
    # Pathspec-bearing preservation diffs hit the --exit-code branches.
    assert runner(["git", "diff", "--exit-code", "--", "config/guards/x.yml"],
                  ".").returncode == 0
    assert runner(["git", "diff", "--cached", "--exit-code", "--", "app/src/main"],
                  ".").returncode == 1


# ── New tests: forged/missing blob ID is fatal (strict-review item 2) ────────────
def test_missing_blob_id_fails_closed(tmp_path):
    """A required input whose blob ID is forged/missing fails the capture closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class BadBlobRunner(ConfigurableFakeRunner):
        def _git(self, argv):
            sub = argv[1:]
            if sub[:1] == ["rev-parse"] and len(sub) == 2 and sub[1].startswith("HEAD:"):
                # Forge an invalid (non-40-hex) blob ID for one required input.
                if sub[1].endswith("verify_db_access_boundaries.py"):
                    return FakeOutcome(0, "not-a-valid-blob-id")
                return FakeOutcome(0, "a" * 40)
            return super()._git(argv)

    runner = BadBlobRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("missing-blob-id:" in w for w in evidence["infrastructure_warnings"])


# ── New tests: empty/non-string/malformed argv rejected (strict-review item 3) ────
def test_validate_command_matrix_rejects_empty_and_nonstring_argv(tmp_path):
    """validate_command_matrix rejects empty and non-string argv tokens."""
    root = tmp_path / "repo"
    root.mkdir()
    empty_spec = cap.CommandSpec(id="e", log_name="00.log", argv=["python3", ""])
    nonstring_spec = cap.CommandSpec(id="n", log_name="01.log", argv=["python3", 123])
    empty_argv_spec = cap.CommandSpec(id="x", log_name="02.log", argv=[])
    violations = cap.validate_command_matrix(
        [empty_spec, nonstring_spec, empty_argv_spec], str(root))
    assert any("invalid-matrix-argv:e:" in v for v in violations)
    assert any("invalid-matrix-argv:n:" in v for v in violations)
    assert any("invalid-matrix-argv:x:<empty>" in v for v in violations)


# ── New tests: symlink artifact roots rejected by hash_artifact (item 6) ──────────
def test_hash_artifact_rejects_symlink_root(tmp_path):
    """hash_artifact rejects a symlink artifact root (never follows it)."""
    out = tmp_path / "bundle"
    out.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    secret = outside / "secret.json"
    secret.write_text("EXTERNAL_SECRET", encoding="utf-8")
    link = out / "link.json"
    _try_symlink(str(secret), str(link))
    # A symlink file root is rejected (returns None), so outside content is never read.
    assert cap.hash_artifact(str(link), "file") is None
    # A symlink directory root is likewise rejected.
    dlink = out / "dlink"
    _try_symlink(str(outside), str(dlink))
    assert cap.hash_artifact(str(dlink), "dir") is None


# __APPEND_TESTS__

# ── Tests ─────────────────────────────────────────────────────────────────────
def test_clean_checkout_succeeds(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                             command_matrix=_fake_matrix(str(root), str(out)),
                             )
    assert rc == 0
    assert (out / "evidence.json").is_file()
    assert (out / "summary.md").is_file()
    assert (out / "semantic-summary.json").is_file()
    assert (out / "output-sha256.txt").is_file()
    # Every guard-tool command matched a registered runner branch; the only
    # intentional fallthroughs are the interpreter/version probes.
    assert all(argv[1] in ("--version", "-version") for argv in runner.fallthroughs)


def test_dirty_checkout_fails_by_default(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=True)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                             command_matrix=_fake_matrix(str(root), str(out)),
                             )
    assert rc == 2
    # No bundle is written for a rejected dirty checkout.
    assert not (out / "evidence.json").is_file()


def test_allow_dirty_captures_but_untrusted(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=True)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, allow_dirty=True,
                             command_matrix=_fake_matrix(str(root), str(out)),
                             )
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["trusted"] is False
    assert evidence["dirty"] is True
    assert evidence["allow_dirty"] is True


def test_command_exit_codes_recorded_exactly(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [
        cap.CommandSpec(id="c0", log_name="00.log", argv=["python3", "scripts/ci/verify_guard_registry.py"]),
        cap.CommandSpec(id="c1", log_name="01.log", argv=["./gradlew", "x"]),
        cap.CommandSpec(id="c2", log_name="02.log", argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation"]),
    ]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix,
                             )
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    by_id = {c["id"]: c["exit_code"] for c in evidence["commands"]}
    assert by_id["c0"] == 0
    assert by_id["c1"] == 1
    assert by_id["c2"] == 2


def test_launch_failure_creates_capture_exit_2(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [
        cap.CommandSpec(id="missing", log_name="00.log", argv=["nonexistent_exe_xyz", "--flag"]),
    ]
    runner = ConfigurableFakeRunner(dirty=False, launch_fail_token="nonexistent_exe_xyz")
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix,
                             )
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["commands"][0]["launch_error"] == "LAUNCH_FAILED"
    assert evidence["commands"][0]["exit_code"] is None


def test_missing_required_artifact_causes_capture_exit_2(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [
        cap.CommandSpec(id="needs-artifact", log_name="00.log", argv=["python3", "scripts/ci/verify_guard_registry.py"],
                        required_artifacts=("99-missing.log",),
                        artifact_kinds=(("99-missing.log", "file"),)),
    ]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix,
                             )
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("missing-required-artifact" in w for w in evidence["infrastructure_warnings"])


def test_invalid_json_report_preserved_but_parser_failure(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    clean_checkout = ConfigurableFakeRunner(dirty=False)

    def bad_runner(argv, cwd):
        # Command-aware: ONLY the db-cli stage is blocked and writes the invalid
        # report; the git preflight/preservation surface stays CLEAN so this
        # test exercises the invalid-report stage, not preflight rejection.
        if _matches(argv, "verify_db_access_boundaries.py", "--fail-on-violation"):
            idx = argv.index("--findings-output")
            # Repository-relative argv output paths resolve against the runner
            # ``cwd`` (the repository root), never the process cwd.
            p = os.path.join(str(cwd), argv[idx + 1])
            parent = os.path.dirname(p)
            if parent:
                os.makedirs(parent, exist_ok=True)
            with open(p, "w", encoding="utf-8") as handle:
                handle.write("{not valid json")
            return FakeOutcome(2, "blocked")
        return clean_checkout(argv, cwd)

    matrix = [_db_cli_matrix(str(root), str(out))]
    rc = cap.capture_evidence(str(root), str(out), runner=bad_runner, command_matrix=matrix,
                              )
    # A required report that is present but invalid must fail the capture closed.
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd = evidence["commands"][0]
    assert cmd["parser_error"] == "INVALID_JSON"
    assert cmd["report_trusted"] is None
    assert cmd["report_schema_version"] is None
    # Artifact is preserved on disk.
    assert (out / "03-db-cli.findings.json").is_file()
    assert any("invalid-required-report" in w for w in evidence["infrastructure_warnings"])


def test_v2_diagnostics_parse_correctly(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                             command_matrix=_fake_matrix(str(root), str(out)),
                             )
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    by_id = {c["id"]: c for c in evidence["commands"]}
    cli = by_id["db-cli"]
    assert cli["report_schema_version"] == 2
    assert cli["report_trusted"] is False
    assert cli["report_diagnostic_codes"] == ["DB_POLICY_INCOMPLETE_V2"]
    assert cli["report_finding_count"] == 0
    inv = by_id["room-inventory"]
    assert inv["report_trusted"] is True
    assert inv["parser_error"] is None


def test_output_paths_are_repository_relative(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)),
                        )
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # Real cross-platform assertion: the bundle root must be repository-relative
    # (no absolute path, no backslash, no Windows drive-letter prefix).
    _assert_repo_relative(evidence["root"])
    for c in evidence["commands"]:
        _assert_repo_relative(c["log_path"])
        # Command cwd is always the repository root ("."), never an absolute path.
        assert c["cwd"] == "."
        if c["report_path"] is not None:
            _assert_repo_relative(c["report_path"])


def test_no_absolute_temp_path_leaks_into_evidence(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)),
                        )
    raw = (out / "evidence.json").read_text(encoding="utf-8")
    assert ".tmp" not in raw
    # No Windows drive letters or leading absolute slashes in stored paths.
    assert "C:\\" not in raw
    assert ":/" not in raw


def test_input_hashes_change_when_bytes_change(tmp_path):
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(dirty=False)
    candidates = ["scripts/verify_db_access_boundaries.py"]
    m1 = cap.collect_input_manifest(str(root), candidates, runner)
    # Mutate the input bytes.
    target = root / "scripts" / "verify_db_access_boundaries.py"
    target.write_text("print('changed')  # different bytes\n", encoding="utf-8")
    m2 = cap.collect_input_manifest(str(root), candidates, runner)
    assert m1[0]["sha256"] != m2[0]["sha256"]
    assert m2[0]["size"] != m1[0]["size"]


def test_command_output_written_atomically(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)),
                        )
    # No leftover temp files anywhere under the bundle.
    leftover = []
    for dirpath, _dirs, files in os.walk(str(out)):
        for name in files:
            if ".tmp" in name:
                leftover.append(name)
    assert leftover == []
    # A log file exists and is non-empty.
    assert (out / "commands" / "00-registry.log").stat().st_size > 0


def test_semantic_summaries_equal_across_runs(tmp_path):
    root = _make_root(tmp_path)
    out1 = root / "out" / "run-1"
    out2 = root / "out" / "run-2"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out1), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out1)),
                        )
    cap.capture_evidence(str(root), str(out2), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out2)),
                        )
    s1 = json.loads((out1 / "semantic-summary.json").read_text(encoding="utf-8"))
    s2 = json.loads((out2 / "semantic-summary.json").read_text(encoding="utf-8"))
    assert s1 == s2
    assert s1["commit"] == TEST_SHA
    assert s1["tree"] == TEST_TREE


def test_semantic_summary_argv_normalizes_run_specific_paths(tmp_path):
    """Run-specific bundle output paths embedded in command argv are normalized to a
    stable ``<bundle>/`` marker so the semantic summary never leaks the run id."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)))
    s = json.loads((out / "semantic-summary.json").read_text(encoding="utf-8"))
    # The run id must not appear anywhere in the semantic summary.
    assert "run-1" not in json.dumps(s)
    for c in s["commands"]:
        for tok in c["argv"]:
            # Run-specific output paths become ``<bundle>/...``; stable tokens are
            # unchanged and never start with the repository-relative bundle prefix.
            assert "run-1" not in tok, tok
            assert tok.startswith("<bundle>/") or not tok.startswith("out/"), tok


def test_semantic_argv_normalizes_output_and_prefix_equals_forms(tmp_path):
    """Equals-form flags (``--output=...``, ``prefix=...``) that embed the
    run-specific bundle path are normalized to the stable ``<bundle>`` marker so
    the run id never reaches ``semantic-summary.json``."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    bundle_rel = cap._posix_rel(str(out), str(root))

    def rop(*parts):
        return "/".join([bundle_rel, *parts])

    matrix = [
        cap.CommandSpec(
            id="output-equals", log_name="00-output.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py",
                  "--output=" + rop("00-output.json")],
        ),
        cap.CommandSpec(
            id="prefix-equals", log_name="01-prefix.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py",
                  "prefix=" + bundle_rel],
        ),
        cap.CommandSpec(
            id="bare-bundle", log_name="02-bare.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py",
                  rop("02-bare.json")],
        ),
    ]
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    s = json.loads((out / "semantic-summary.json").read_text(encoding="utf-8"))
    # The run id must not appear anywhere in the semantic summary.
    assert "run-1" not in json.dumps(s)
    by_id = {c["id"]: c for c in s["commands"]}
    # ``--output=<bundle_rel>/00-output.json`` -> ``--output=<bundle>/00-output.json``
    assert "--output=<bundle>/00-output.json" in by_id["output-equals"]["argv"]
    # ``prefix=<bundle_rel>`` -> ``prefix=<bundle>``
    assert "prefix=<bundle>" in by_id["prefix-equals"]["argv"]
    # A bare bundle-relative token is masked to ``<bundle>/02-bare.json``.
    assert "<bundle>/02-bare.json" in by_id["bare-bundle"]["argv"]


def test_semantic_argv_normalizes_embedded_bundle_path(tmp_path):
    """A run-specific bundle path embedded in the MIDDLE of a larger value (not just
    a prefix) is still masked to ``<bundle>`` so the run id never leaks."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    bundle_rel = cap._posix_rel(str(out), str(root))
    matrix = [
        cap.CommandSpec(
            id="embedded", log_name="00-embedded.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py",
                  "some/dir/" + bundle_rel + "/nested/file.json"],
        ),
    ]
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    s = json.loads((out / "semantic-summary.json").read_text(encoding="utf-8"))
    assert "run-1" not in json.dumps(s)
    tok = s["commands"][0]["argv"][2]
    assert tok == "some/dir/<bundle>/nested/file.json", tok
    # A longer component that merely contains the bundle prefix is NOT masked.
    matrix2 = [
        cap.CommandSpec(
            id="near-miss", log_name="01-near.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py",
                  bundle_rel + "0/sibling.json"],
        ),
    ]
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix2)
    s2 = json.loads((out / "semantic-summary.json").read_text(encoding="utf-8"))
    # ``out/run-10`` is a distinct component from ``out/run-1`` and must be kept.
    assert "out/run-10/sibling.json" in s2["commands"][0]["argv"]


def test_preservation_checker_fails_on_changed_forbidden_file(tmp_path):
    root = _make_root(tmp_path)
    clean = ConfigurableFakeRunner(dirty=False)
    dirty = ConfigurableFakeRunner(dirty=True)
    ok = cap.preservation_check(str(root), clean)
    assert ok["ok"] is True
    bad = cap.preservation_check(str(root), dirty)
    assert bad["ok"] is False
    assert bad["policy_ok"] is False


def test_command_argv_is_array_not_shell_text(tmp_path):
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)),
                        )
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    for c in evidence["commands"]:
        assert isinstance(c["argv"], list)
        joined = " ".join(c["argv"])
        # A shell-string would embed operators or equal the whole command.
        assert " && " not in joined
        assert "; " not in joined
        assert "|" not in joined


def test_environment_values_redacted_except_version_fields(monkeypatch):
    monkeypatch.setenv("MY_SECRET_TOKEN", "super-secret")
    monkeypatch.setenv("PYTHON_VERSION", "3.11.4")
    monkeypatch.setenv("PATH", "/usr/bin:/secret/bin")
    env = cap.collect_environment()
    assert env["variables"]["MY_SECRET_TOKEN"] == cap.REDACTED_MARKER
    assert env["variables"]["PATH"] == cap.REDACTED_MARKER
    assert env["variables"]["PYTHON_VERSION"] == "3.11.4"
    assert "PYTHON_VERSION" in env["allowed_value_keys"]
    assert env["redacted_count"] >= 2


# ── New tests from tester-static findings ─────────────────────────────────────

def test_arbitrary_version_suffix_is_redacted(monkeypatch):
    """Regression: a secret-like ``*_VERSION`` variable must stay redacted."""
    monkeypatch.setenv("DB_PASSWORD_VERSION", "super-secret")
    monkeypatch.setenv("API_KEY_VERSION", "also-secret")
    monkeypatch.setenv("PYTHON_VERSION", "3.11.4")
    env = cap.collect_environment()
    assert env["variables"]["DB_PASSWORD_VERSION"] == cap.REDACTED_MARKER
    assert env["variables"]["API_KEY_VERSION"] == cap.REDACTED_MARKER
    assert env["variables"]["PYTHON_VERSION"] == "3.11.4"
    assert "DB_PASSWORD_VERSION" not in env["allowed_value_keys"]
    assert "API_KEY_VERSION" not in env["allowed_value_keys"]
    assert "PYTHON_VERSION" in env["allowed_value_keys"]


def test_infrastructure_warning_for_missing_test_file(tmp_path):
    """A referenced .py test file that is absent at the SHA is flagged."""
    root = _make_root(tmp_path)
    # Create one referenced test file so it is NOT flagged.
    existing = root / "scripts" / "ci" / "test_guard_findings.py"
    existing.parent.mkdir(parents=True, exist_ok=True)
    existing.write_text("def test_x(): pass\n", encoding="utf-8")
    missing = "scripts/ci/does_not_exist_test.py"
    matrix = [
        cap.CommandSpec(
            id="focused-python-tests", log_name="01.log",
            argv=["python3", "-m", "pytest",
                  "scripts/ci/test_guard_findings.py", missing, "-v"],
        ),
    ]
    warnings = cap.collect_infrastructure_warnings(matrix, str(root))
    assert f"missing-test-file:{missing}" in warnings
    # The existing test file must not be flagged.
    assert not any("test_guard_findings.py" in w for w in warnings)


def test_preservation_failure_makes_evidence_untrusted(tmp_path):
    """Integration: a failed preservation check makes the whole bundle untrusted."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class PreservationFailRunner:
        """Clean checkout, but the forbidden-file diff fails (preservation fails)."""

        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            if argv[:2] == ["git", "diff"] and "--exit-code" in argv:
                return FakeOutcome(1, "")
            return self._inner(argv, cwd)

    runner = PreservationFailRunner()
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                             command_matrix=_fake_matrix(str(root), str(out)),
                             )
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # Preservation failed independently of a dirty checkout.
    assert evidence["preservation"]["ok"] is False
    assert evidence["preservation"]["policy_ok"] is False
    assert evidence["dirty"] is False
    # The failure must propagate to the top-level trusted flag.
    assert evidence["trusted"] is False


def test_v2_report_with_nonzero_findings(tmp_path):
    """A v2 report carrying findings is parsed with the correct count."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    clean_checkout = ConfigurableFakeRunner(dirty=False)

    def findings_runner(argv, cwd):
        # Command-aware: git preflight stays CLEAN; only the db-cli stage is
        # blocked while writing its findings report.
        if _matches(argv, "verify_db_access_boundaries.py", "--fail-on-violation"):
            idx = argv.index("--findings-output")
            # Resolve against the runner ``cwd`` (repository root).
            out_path = os.path.join(str(cwd), argv[idx + 1])
            _write_fake_report(out_path, trusted=False,
                               codes=["DB_POLICY_INCOMPLETE_V2"], findings=3)
            return FakeOutcome(2, "blocked")
        return clean_checkout(argv, cwd)

    matrix = [_db_cli_matrix(str(root), str(out))]
    rc = cap.capture_evidence(str(root), str(out), runner=findings_runner, command_matrix=matrix,
                              )
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd = evidence["commands"][0]
    assert cmd["report_finding_count"] == 3
    assert cmd["report_schema_version"] == 2
    assert cmd["report_trusted"] is False
    assert cmd["report_diagnostic_codes"] == ["DB_POLICY_INCOMPLETE_V2"]


def test_output_sha256_contract_excludes_command_and_report_artifacts(tmp_path):
    """output-sha256.txt is a fixed contract over top-level outputs only."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)),
                        )
    text = (out / "output-sha256.txt").read_text(encoding="utf-8")
    lines = [ln for ln in text.splitlines() if ln.strip()]
    rels = [ln.split("  ", 1)[1] for ln in lines]
    expected = {
        "git-state.json", "environment.json", "input-manifest.json",
        "input-sha256.txt", "evidence.json", "summary.md", "semantic-summary.json",
    }
    assert set(rels) == expected
    # Command logs and per-command report artifacts are intentionally excluded.
    assert not any(r.startswith("commands/") for r in rels)
    assert not any(r.endswith(".findings.json") for r in rels)
    assert "output-sha256.txt" not in text


def test_output_sha256_uses_bundle_relative_top_level_names(tmp_path):
    """output-sha256.txt records the documented bundle-relative top-level names
    (relative to the bundle directory), not the repository-relative bundle path."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)))
    text = (out / "output-sha256.txt").read_text(encoding="utf-8")
    rels = [ln.split("  ", 1)[1] for ln in text.splitlines() if ln.strip()]
    # No repository-relative bundle prefix (e.g. ``out/run-1/``) leaks into the names.
    assert not any(r.startswith("out/") for r in rels)
    # The documented bare top-level names are present.
    for name in ["git-state.json", "environment.json", "input-manifest.json",
                 "input-sha256.txt", "evidence.json", "summary.md",
                 "semantic-summary.json"]:
        assert name in rels


def test_output_sha256_reflects_rewritten_artifacts_on_failure(tmp_path, monkeypatch):
    """When a top-level output hash fails, output-sha256.txt carries the FINAL hashes
    of the rewritten evidence/semantic/summary artifacts (not the pre-rewrite ones)."""
    real_hash = cap._race_safe_hash_file

    def partial_hash(path, *a, **k):
        # Fail only for git-state.json; the other top-level outputs hash normally.
        if str(path).endswith("git-state.json"):
            return None
        return real_hash(path, *a, **k)

    monkeypatch.setattr(cap, "_race_safe_hash_file", partial_hash)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    out_sha = (out / "output-sha256.txt").read_text(encoding="utf-8")
    # git-state.json failed and must be excluded (no empty hash line substituted).
    assert "git-state.json" not in out_sha
    # The rewritten evidence/semantic/summary must appear with their FINAL hashes,
    # which must match the on-disk files exactly (no stale pre-rewrite hash).
    for name in ["evidence.json", "semantic-summary.json", "summary.md"]:
        actual = cap._race_safe_hash_file(str(out / name))
        assert actual is not None
        assert f"{actual}  {name}" in out_sha


def test_warnings_capped_after_output_hash_failure(tmp_path, monkeypatch):
    """The warning cap is applied AFTER the output-hash-failed diagnostic, so the
    final persisted list never exceeds MAX_WARNINGS even when the failure pushes it
    over the bound."""
    monkeypatch.setattr(cap, "MAX_WARNINGS", 3)
    # Force every output hash to fail so 7 output-hash-failed warnings are generated.
    monkeypatch.setattr(cap, "_race_safe_hash_file", lambda *a, **k: None)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)))
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    warns = evidence["infrastructure_warnings"]
    assert len(warns) <= 3
    # The overflow marker must be present (capped after the failure diagnostics).
    assert any(cap.OVERFLOW_WARNINGS in w for w in warns)


# ── New tests from strict reviewer blockers ───────────────────────────────────

def test_default_matrix_argv_has_no_absolute_paths(tmp_path):
    """Regression: output paths embedded in argv must be repository-relative."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = cap.default_command_matrix(str(root), str(out))
    for spec in matrix:
        for token in spec.argv:
            _assert_no_absolute(token)


def test_output_outside_root_rejected(tmp_path):
    """Fail closed when the output bundle escapes the repository root."""
    root = _make_root(tmp_path)
    # Sibling of the repo root, therefore not contained within it.
    out = tmp_path / "outside" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              )
    assert rc == 2
    # No bundle is written for a rejected output location.
    assert not (out / "evidence.json").is_file()


def test_output_traversal_rejected(tmp_path):
    """Fail closed when the output path traverses outside the repository root."""
    root = _make_root(tmp_path)
    out = os.path.join(str(root), "..", "escape", "run-1")
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), out, runner=runner,
                              command_matrix=_fake_matrix(str(root), out),
                              )
    assert rc == 2
    assert not (tmp_path / "escape" / "run-1" / "evidence.json").is_file()


def test_missing_required_input_fails_closed(tmp_path):
    """Fail closed when a required input candidate is absent at the SHA."""
    root = _make_root(tmp_path)
    # Remove one required input so the manifest marks it missing.
    (root / "scripts" / "verify_db_access_boundaries.py").unlink()
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              )
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("missing-required-input:scripts/verify_db_access_boundaries.py" in w
               for w in evidence["infrastructure_warnings"])


def test_preflight_failure_fails_closed(tmp_path):
    """Fail closed when the essential git identity cannot be resolved."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class NoIdentityRunner:
        """Clean checkout, but ``git rev-parse HEAD`` yields no SHA."""

        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            if argv[:2] == ["git", "rev-parse"] and argv[2] == "HEAD":
                return FakeOutcome(0, "")
            return self._inner(argv, cwd)

    runner = NoIdentityRunner()
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              )
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["git_state"]["preflight_ok"] is False
    assert any("preflight-failed" in w for w in evidence["infrastructure_warnings"])


def test_preflight_commands_recorded(tmp_path):
    """Preflight command records are captured for reproducibility."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                         command_matrix=_fake_matrix(str(root), str(out)),
                         )
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    commands = evidence["git_state"]["preflight_commands"]
    assert isinstance(commands, list) and len(commands) > 0
    for rec in commands:
        assert "argv" in rec and "exit_code" in rec and "output" in rec
        for token in rec["argv"]:
            _assert_no_absolute(token)
    assert evidence["git_state"]["preflight_ok"] is True


def test_ratchet_uses_command_arg_equals_form(tmp_path):
    """Every ratchet child arg uses the ``--command-arg=<value>`` token form."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = cap.default_command_matrix(str(root), str(out))
    ratchet = next(s for s in matrix if s.id == "db-ratchet")
    for token in ratchet.argv:
        if token.startswith("--command-arg"):
            assert token.startswith("--command-arg="), f"non-equals form: {token!r}"
        if token.startswith("--guard-name") or token.startswith("--baseline") \
                or token.startswith("--finding-protocol"):
            assert "=" in token, f"expected =value form: {token!r}"


def test_required_ratchet_static_artifacts(tmp_path):
    """Ratchet and static-suite commands require their complete artifacts."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = cap.default_command_matrix(str(root), str(out))
    by_id = {s.id: s for s in matrix}
    assert "04-db-ratchet.summary.json" in by_id["db-ratchet"].required_artifacts
    assert "05-static-suite" in by_id["static-suite"].required_artifacts
    assert "05-static-suite/summary.json" in by_id["static-suite"].required_artifacts


def test_diagnostic_code_sanitized(tmp_path):
    """A non-controlled diagnostic code is redacted, never leaked verbatim."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    clean_checkout = ConfigurableFakeRunner(dirty=False)

    def bad_code_runner(argv, cwd):
        # Command-aware: git preflight stays CLEAN; only the db-cli stage is
        # blocked while writing the report with the hostile diagnostic code.
        if _matches(argv, "verify_db_access_boundaries.py", "--fail-on-violation"):
            idx = argv.index("--findings-output")
            # Resolve against the runner ``cwd`` (repository root).
            out_path = os.path.join(str(cwd), argv[idx + 1])
            _write_fake_report(out_path, trusted=False,
                               codes=["db/policy/C:\\secret\\path"], findings=0)
            return FakeOutcome(2, "blocked")
        return clean_checkout(argv, cwd)

    matrix = [_db_cli_matrix(str(root), str(out))]
    rc = cap.capture_evidence(str(root), str(out), runner=bad_code_runner, command_matrix=matrix,
                              )
    # The report is valid JSON, so the capture still succeeds; the code is sanitized.
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd = evidence["commands"][0]
    assert cmd["report_diagnostic_codes"] == [cap.REDACTED_MARKER]
    assert "C:\\secret" not in json.dumps(evidence)


def test_persisted_output_redacts_absolute_paths(tmp_path):
    """Absolute paths in child output are redacted before being persisted."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class LeakyRunner:
        """Wrap the normal fake runner but inject an absolute path in output."""

        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            outcome = self._inner(argv, cwd)
            if argv and argv[0] == "git":
                # Keep the git preflight/preservation surface CLEAN; only child
                # command output carries the leaky payload.
                return outcome
            combined = outcome.combined + \
                " wrote config to C:\\Users\\tester\\secret.log and /etc/passwd done"
            return FakeOutcome(outcome.returncode, combined)

    matrix = [
        cap.CommandSpec(id="leaky", log_name="00-leaky.log",
                        argv=["python3", "scripts/ci/verify_guard_registry.py"]),
    ]
    runner = LeakyRunner()
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix,
                         )
    log = (out / "commands" / "00-leaky.log").read_text(encoding="utf-8")
    assert "<redacted-path>" in log
    assert "C:\\Users\\tester" not in log
    assert "/etc/passwd" not in log


# ── New tests: target-SHA enforcement (requirement 1) ───────────────────────────
class WrongShaRunner(ConfigurableFakeRunner):
    def _git(self, argv):
        sub = argv[1:]
        if sub[:2] == ["rev-parse", "HEAD"] and len(sub) == 2:
            return FakeOutcome(0, "0" * 40)
        return super()._git(argv)


def test_target_sha_enforced_wrong_sha(tmp_path):
    """Fail closed when HEAD differs from the approved exact target SHA."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = WrongShaRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["git_state"]["commit"] == "0" * 40
    assert any(w.startswith("wrong-sha:") for w in evidence["infrastructure_warnings"])


class NonzeroHeadRunner(ConfigurableFakeRunner):
    def _git(self, argv):
        sub = argv[1:]
        if sub[:2] == ["rev-parse", "HEAD"] and len(sub) == 2:
            # Nonzero exit but output present: must still be rejected.
            return FakeOutcome(1, TEST_SHA)
        return super()._git(argv)


def test_preflight_nonzero_exit_rejected(tmp_path):
    """Fail closed when git rev-parse HEAD exits nonzero even if output exists."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = NonzeroHeadRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["git_state"]["preflight_ok"] is False
    assert evidence["git_state"]["commit"] is None
    assert any("preflight-failed" in w for w in evidence["infrastructure_warnings"])


class MalformedHeadRunner(ConfigurableFakeRunner):
    def _git(self, argv):
        sub = argv[1:]
        if sub[:2] == ["rev-parse", "HEAD"] and len(sub) == 2:
            return FakeOutcome(0, "not-a-valid-sha\n")
        return super()._git(argv)


def test_preflight_malformed_sha_rejected(tmp_path):
    """Fail closed when git rev-parse HEAD yields a malformed (non-40-hex) SHA."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = MalformedHeadRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["git_state"]["preflight_ok"] is False
    assert evidence["git_state"]["commit"] is None


# ── New tests: dynamic input manifest (requirement 2) ──────────────────────────
def test_input_manifest_built_dynamically(tmp_path):
    """The manifest is built dynamically and includes all db_guard scripts + required config."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                         command_matrix=_fake_matrix(str(root), str(out)))
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    rels = {e["rel_path"] for e in evidence["input_manifest"]}
    for f in TRACKED_FILES:
        if f.startswith("scripts/db_guard/") and f.endswith(".py"):
            assert f in rels, f"missing tracked db_guard script: {f}"
    for req in cap.REQUIRED_INPUT_CANDIDATES:
        assert req in rels, f"missing required DB input: {req}"


def test_input_manifest_completeness(tmp_path):
    """Completeness: every tracked file is discovered and required inputs are present."""
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(dirty=False)
    candidates = cap.discover_input_candidates(str(root), runner)
    for f in TRACKED_FILES:
        assert f in candidates, f"tracked file not discovered: {f}"
    assert len(candidates) == len(set(candidates))
    for req in cap.REQUIRED_INPUT_CANDIDATES:
        assert req in candidates


def test_input_manifest_includes_production_source_roots(tmp_path):
    """PR-GR-03 Slice E: the built manifest includes the source-root manifest.

    ``config/guards/production_source_roots.yml`` must appear in the dynamic
    input manifest when present in the fixture repo, hashed and observed like
    every other required DB guard config input (exists + blob id + sha256).
    """
    rel = "config/guards/production_source_roots.yml"
    assert rel in cap.REQUIRED_INPUT_CANDIDATES
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    entries = {e["rel_path"]: e for e in evidence["input_manifest"]}
    entry = entries.get(rel)
    assert entry is not None, f"{rel} missing from the built input manifest"
    assert entry["exists"] is True
    assert entry["blob_id"]
    assert entry["sha256"]
    # The capture must not fail closed over this input when it is present.
    assert not any(w.startswith(f"missing-required-input:{rel}") or
                   w.startswith(f"missing-blob-id:{rel}")
                   for w in evidence["infrastructure_warnings"])
    assert rc == 0


# ── New tests: realpath / symlink / custom-matrix validation (requirement 3) ────
def test_symlink_escape_rejected(tmp_path, monkeypatch):
    """Fail closed when the resolved (realpath) output escapes the repo root."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    outside = tmp_path / "escape"

    def fake_realpath(p):
        ap = os.path.abspath(p)
        if ap == os.path.abspath(str(out)) or ap.startswith(os.path.abspath(str(out)) + os.sep):
            return str(outside)
        return ap

    monkeypatch.setattr(cap.os.path, "realpath", fake_realpath)
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    assert not (out / "evidence.json").is_file()
    # Output containment is checked before any runner call: zero child commands ran.
    assert runner.calls == []


def test_custom_matrix_absolute_path_rejected(tmp_path):
    """A custom command matrix embedding an absolute path fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    abs_path = os.path.abspath(tmp_path / "secret" / "evil.py")
    matrix = [cap.CommandSpec(id="bad", log_name="00.log", argv=["python3", abs_path])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-matrix-argv:") for w in evidence["infrastructure_warnings"])


def test_validate_command_matrix_rejects_absolute_and_outside_root(tmp_path):
    """validate_command_matrix flags both absolute and root-escaping argv tokens.

    Direct unit coverage of the custom-matrix containment gate (requirement 3)
    so the absolute/outside rejection is locked in independently of the full
    capture flow.
    """
    root = _make_root(tmp_path)
    abs_spec = cap.CommandSpec(
        id="a", log_name="00.log",
        argv=["python3", os.path.abspath(tmp_path / "evil.py")],
    )
    # Repository-relative token that resolves outside the root via traversal.
    escape_spec = cap.CommandSpec(
        id="b", log_name="01.log",
        argv=["python3", "../escape.py"],
    )
    violations = cap.validate_command_matrix([abs_spec, escape_spec], str(root))
    assert any(v.startswith("invalid-matrix-argv:a:") for v in violations)
    assert any(v.startswith("invalid-matrix-argv:b:") for v in violations)
    # A clean repository-relative token is not flagged.
    ok_spec = cap.CommandSpec(
        id="c", log_name="02.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
    )
    assert cap.validate_command_matrix([ok_spec], str(root)) == []


# ── New tests: required-artifact type + hashing (requirement 4) ─────────────────
def test_required_artifact_type_mismatch_fails(tmp_path):
    """A required artifact present but of the wrong type fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    (out / "02-room-inventory.findings.json").mkdir(parents=True)
    matrix = [cap.CommandSpec(
        id="room-inventory", log_name="02.log",
        argv=["python3", "scripts/verify_db_access_boundaries.py", "--inventory-only"],
        required_artifacts=("02-room-inventory.findings.json",),
        # Explicit artifact-kind metadata is now mandatory; without it the capture
        # fails closed with ``missing-artifact-kind`` before the type check, so the
        # fake matrix must declare the kind to exercise the type-mismatch path.
        artifact_kinds=(("02-room-inventory.findings.json", "file"),),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("invalid-required-artifact-type:02-room-inventory.findings.json" in w
               for w in evidence["infrastructure_warnings"])


def test_required_artifact_hashes_present(tmp_path):
    """Every required artifact is explicitly hashed into the evidence bundle."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                         command_matrix=_fake_matrix(str(root), str(out)))
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    hashes = evidence["required_artifact_hashes"]
    for spec in _fake_matrix(str(root), str(out)):
        for art in spec.required_artifacts:
            assert art in hashes, f"missing hash for required artifact: {art}"
            assert isinstance(hashes[art], str) and len(hashes[art]) == 64
    # The static-suite directory hash covers its summary recursively.
    assert "05-static-suite" in hashes
    assert "05-static-suite/summary.json" in hashes


# ── New tests: comprehensive sanitization (requirement 5) ───────────────────────
def test_raw_secret_exception_sql_output(tmp_path):
    """Raw secrets, exception text, and SQL errors are redacted from persisted logs."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class LeakyRunner:
        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            outcome = self._inner(argv, cwd)
            if argv and argv[0] == "git":
                # Keep the git preflight/preservation surface CLEAN; only child
                # command output carries the leaky payload.
                return outcome
            combined = outcome.combined + "\n".join([
                "password=hunter2secret",
                "api_key=sk_live_abc123",
                "Traceback (most recent call last):",
                '  File "internal/secret.py", line 10, in run',
                "ValueError: something failed badly",
                'android.database.sqlite.SQLiteException: near "SELECT": syntax error',
            ])
            return FakeOutcome(outcome.returncode, combined)

    matrix = [cap.CommandSpec(id="leaky", log_name="00-leaky.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = LeakyRunner()
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    log = (out / "commands" / "00-leaky.log").read_text(encoding="utf-8")
    assert "<redacted-secret>" in log
    assert "hunter2secret" not in log
    assert "sk_live_abc123" not in log
    assert "<redacted-exception>" in log
    assert "ValueError: something failed badly" not in log
    assert "internal/secret.py" not in log
    assert "<redacted-sql>" in log
    assert "SQLiteException" not in log
    # Child exit code is preserved, never swallowed by sanitization.
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["commands"][0]["exit_code"] == 0


def test_child_output_bounded(tmp_path):
    """Persisted child output is bounded; unbounded payloads are truncated."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class HugeRunner:
        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            outcome = self._inner(argv, cwd)
            if argv and argv[0] == "git":
                # Keep the git preflight/preservation surface CLEAN (an
                # oversized status payload would trip the dirty rejection
                # instead of exercising the output-bounding stage).
                return outcome
            return FakeOutcome(outcome.returncode, "x" * (cap.CHILD_OUTPUT_LIMIT * 4))

    matrix = [cap.CommandSpec(id="huge", log_name="00-huge.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = HugeRunner()
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    log = (out / "commands" / "00-huge.log").read_text(encoding="utf-8")
    assert "<truncated>" in log
    assert len(log) <= cap.CHILD_OUTPUT_LIMIT + len("<truncated>")


# ── New tests: complete ratchet argv assertion (requirement 7) ──────────────────
def test_ratchet_argv_complete(tmp_path):
    """The full ratchet argv is asserted exactly, not merely --command-arg= presence."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = cap.default_command_matrix(str(root), str(out))
    ratchet = next(s for s in matrix if s.id == "db-ratchet")
    bundle_rel = cap._posix_rel(str(out), str(root))
    expected = [
        "python3", "scripts/ci/guard_ratchet.py",
        "--guard-name=db_access",
        "--command-arg=python3",
        "--command-arg=scripts/verify_db_access_boundaries.py",
        "--command-arg=--fail-on-violation",
        "--command-arg=--ownership-policy",
        "--command-arg=config/guards/db_ownership_policy.yml",
        "--command-arg=--structural-exceptions",
        "--command-arg=config/guards/db_structural_exceptions.yml",
        "--command-arg=--structural-manifest",
        "--command-arg=config/guards/db_structural_exceptions_expected_methods.yml",
        "--baseline=config/baselines/db_access.json",
        "--ci-mode",
        "--finding-protocol=2",
        "--output-summary", "/".join([bundle_rel, "04-db-ratchet.summary.json"]),
    ]
    assert ratchet.argv == expected


# ── New tests: required report path directory rejection (requirement 8) ─────────
def test_required_report_path_directory_rejected(tmp_path):
    """A required report path that resolves to a directory fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    (out / "03-db-cli.findings.json").mkdir(parents=True)
    bundle_rel = cap._posix_rel(str(out), str(root))
    matrix = [cap.CommandSpec(
        id="db-cli", log_name="03-db-cli.log",
        argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation",
              "--findings-output", "/".join([bundle_rel, "03-db-cli.findings.json"])],
        report_path="03-db-cli.findings.json",
        required_artifacts=(),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("invalid-required-report:db-cli" in w for w in evidence["infrastructure_warnings"])


# ── New tests: derived bundle-path containment (traversal + symlink) (req 1) ────
def _try_symlink(target, link):
    """Create a real filesystem symlink; skip the test if privileges are lacking."""
    try:
        os.symlink(target, link)
    except OSError as exc:
        msg = str(exc).lower()
        if "privilege" in msg or getattr(exc, "winerror", None) in (1, 5, 1314):
            pytest.skip("filesystem symlinks require elevated privilege on this host")
        raise


def test_log_name_traversal_rejected(tmp_path):
    """A log_name that traverses outside the bundle fails closed (no external write)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(id="bad", log_name="../escape.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The hostile traversal token is redacted, never leaked verbatim.
    assert any(w == "invalid-bundle-path:bad:<redacted-path>"
               for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command executed, no log written outside the bundle.
    assert evidence["commands"] == []
    assert runner.calls == []
    assert not (tmp_path / "escape.log").is_file()


def test_report_path_traversal_rejected(tmp_path):
    """A report_path that traverses outside the bundle fails closed (no external read)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    bundle_rel = cap._posix_rel(str(out), str(root))
    matrix = [cap.CommandSpec(
        id="db-cli", log_name="03-db-cli.log",
        argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation",
              "--findings-output", "/".join([bundle_rel, "03-db-cli.findings.json"])],
        report_path="../03-db-cli.findings.json",
        required_artifacts=(),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The hostile traversal token is redacted, never leaked verbatim.
    assert any(w == "invalid-bundle-path:db-cli:<redacted-path>"
               for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command executed.
    assert evidence["commands"] == []
    assert runner.calls == []


def test_required_artifact_traversal_rejected(tmp_path):
    """A required artifact that traverses outside the bundle fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(
        id="needs-artifact", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("../99-missing.log",),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The hostile traversal token is redacted, never leaked verbatim.
    assert any(w == "invalid-bundle-path:needs-artifact:<redacted-path>"
               for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command executed.
    assert evidence["commands"] == []
    assert runner.calls == []


def test_actual_symlink_report_path_rejected(tmp_path):
    """An external symlink report_path is rejected (no external read)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    out.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    outside_report = outside / "evil.json"
    outside_report.write_text(
        '{"schema":"cost-aggregator.guard-findings","schema_version":2}', encoding="utf-8")
    _try_symlink(str(outside_report), str(out / "evil_link"))
    bundle_rel = cap._posix_rel(str(out), str(root))
    matrix = [cap.CommandSpec(
        id="db-cli", log_name="03-db-cli.log",
        argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation",
              "--findings-output", "/".join([bundle_rel, "evil_link"])],
        report_path="evil_link",
        required_artifacts=(),
    )]
    runner = ConfigurableFakeRunner(dirty=False, write_reports=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("invalid-bundle-path:db-cli:evil_link" in w
               for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command was executed and no command record exists.
    assert evidence["commands"] == []
    assert runner.calls == []
    # The external file must not have been read into the bundle.
    assert "evil_link" not in json.dumps(evidence.get("commands", []))


def test_actual_symlink_required_artifact_rejected(tmp_path):
    """An external symlink required artifact is rejected (no external read)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    out.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    outside_art = outside / "evil.json"
    outside_art.write_text("{}", encoding="utf-8")
    _try_symlink(str(outside_art), str(out / "evil_art"))
    matrix = [cap.CommandSpec(
        id="needs-artifact", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("evil_art",),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("invalid-bundle-path:needs-artifact:evil_art" in w
               for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command executed, no command record exists.
    assert evidence["commands"] == []
    assert runner.calls == []


def test_actual_symlink_log_name_rejected(tmp_path):
    """An external symlink log_name is rejected (no external write)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    out.mkdir(parents=True)
    (out / "commands").mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    outside_log = outside / "escape.log"
    outside_log.write_text("leak", encoding="utf-8")
    _try_symlink(str(outside_log), str(out / "commands" / "evil.log"))
    matrix = [cap.CommandSpec(id="bad", log_name="evil.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any("invalid-bundle-path:bad:commands/evil.log" in w
               for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command executed, no log written through the symlink.
    assert evidence["commands"] == []
    assert runner.calls == []
    # No log written through the symlink to the outside file.
    assert outside_log.read_text(encoding="utf-8") == "leak"


# ── New tests: preflight metadata sanitization + bounding (req 2) ───────────────
def test_preflight_metadata_sanitized_and_bounded(tmp_path):
    """Preflight status/diff/log metadata is sanitized, secret-free, and bounded."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class LeakyPreflightRunner(ConfigurableFakeRunner):
        def _git(self, argv):
            sub = argv[1:]
            if sub[:2] == ["status", "--porcelain=v1"]:
                return FakeOutcome(0, " M config/guards/db_ownership_policy.yml\n M /abs/secret/path/file.yml\n")
            if sub[:2] == ["diff", "--name-only"]:
                return FakeOutcome(0, "config/x.yml\n/abs/leak.yml\n")
            if sub[:3] == ["diff", "--cached", "--name-only"]:
                return FakeOutcome(0, "")
            if sub[:3] == ["log", "--oneline", "-20"]:
                return FakeOutcome(0, f"{TEST_SHA} secret=supersecret token=abc commit message\n" + "0" * 40 + " another message\n")
            return super()._git(argv)

    runner = LeakyPreflightRunner(dirty=False)
    # The hostile status lines this test injects mark the checkout dirty by
    # design; allow_dirty lets the metadata-persistence stage run so the
    # sanitization of the PERSISTED git state is actually exercised.
    cap.capture_evidence(str(root), str(out), runner=runner, allow_dirty=True,
                        command_matrix=_fake_matrix(str(root), str(out)))
    gs = json.loads((out / "git-state.json").read_text(encoding="utf-8"))
    raw = json.dumps(gs)
    # No absolute paths persisted.
    assert "/abs/" not in raw
    # No secret assignments persisted (status/diff or preflight command records).
    assert "supersecret" not in raw
    assert "token=abc" not in raw
    # No commit-message text persisted (only SHAs in log_oneline / log record).
    assert "commit message" not in raw
    assert "another message" not in raw
    assert gs["log_oneline"] == TEST_SHA + "\n" + "0" * 40
    # Bounded.
    assert len(gs["status"]) <= cap.CHILD_OUTPUT_LIMIT
    assert len(gs["diff_name_only"]) <= cap.CHILD_OUTPUT_LIMIT


# ── New tests: fixed target SHA is not configurable (req 3) ─────────────────────
def test_target_sha_not_configurable(tmp_path):
    """The capture tool must not accept a target_sha override (fixed SHA enforced)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    with pytest.raises(TypeError):
        cap.capture_evidence(str(root), str(out), runner=runner,
                             command_matrix=_fake_matrix(str(root), str(out)),
                             target_sha="0" * 40)


# ── New tests: staged-diff preservation (req 5) ─────────────────────────────────
def test_staged_diff_preserved(tmp_path):
    """Staged changes are captured into git-state.json (staged_diff_name_only)."""
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(dirty=False, staged_diff="config/staged.yml\n")
    gs = cap.run_preflight(str(root), runner)
    assert gs["staged_diff_name_only"] == "config/staged.yml"


def test_staged_diff_preserved_in_bundle(tmp_path):
    """Staged diff metadata lands in the written git-state.json."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False, staged_diff="config/staged.yml\n")
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)))
    gs = json.loads((out / "git-state.json").read_text(encoding="utf-8"))
    assert gs["staged_diff_name_only"] == "config/staged.yml"


# ── New tests: git status/diff/staged-diff failures are fatal (GR-00 hardening) ──
class GitMetaFailRunner(ConfigurableFakeRunner):
    """Clean checkout, but the preflight git metadata commands fail (exit 1)."""

    def _git(self, argv):
        sub = argv[1:]
        if sub[:2] == ["status", "--porcelain=v1"]:
            return FakeOutcome(1, "")
        if sub[:2] == ["diff", "--name-only"]:
            return FakeOutcome(1, "")
        if sub[:3] == ["diff", "--cached", "--name-only"]:
            return FakeOutcome(1, "")
        return super()._git(argv)


def test_git_meta_failure_fails_closed(tmp_path):
    """Fail closed when git status / diff / staged-diff cannot be observed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = GitMetaFailRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["git_state"]["git_meta_ok"] is False
    assert any(w.startswith("git-meta-failed:") for w in evidence["infrastructure_warnings"])


def test_git_meta_partial_failure_fails_closed(tmp_path):
    """A single failing git metadata command still fails the capture closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class PartialMetaFailRunner(ConfigurableFakeRunner):
        def _git(self, argv):
            sub = argv[1:]
            if sub[:2] == ["diff", "--name-only"]:
                return FakeOutcome(1, "")
            return super()._git(argv)

    runner = PartialMetaFailRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["git_state"]["git_meta_ok"] is False
    assert any("git-meta-failed:diff" in w for w in evidence["infrastructure_warnings"])


# ── New tests: blob ID validation (GR-00 hardening) ─────────────────────────────
def test_blob_id_validated(tmp_path):
    """git_blob_id accepts only a 40-hex SHA-1; everything else is rejected."""
    root = _make_root(tmp_path)
    valid = "a" * 40

    class BlobRunner:
        def __init__(self, blob):
            self._inner = ConfigurableFakeRunner(dirty=False)
            self.blob = blob

        def __call__(self, argv, cwd):
            # Semantic match for the 3-token blob query ``git rev-parse HEAD:<path>``.
            if (len(argv) == 3 and argv[:2] == ["git", "rev-parse"]
                    and argv[2].startswith("HEAD:")):
                return FakeOutcome(0, self.blob)
            return self._inner(argv, cwd)

    assert cap.git_blob_id(str(root), "scripts/verify_db_access_boundaries.py",
                           BlobRunner(valid)) == valid
    # Non-hex, too short, multi-line, and nonzero-exit are all rejected.
    assert cap.git_blob_id(str(root), "x.py", BlobRunner("not-a-sha")) is None
    assert cap.git_blob_id(str(root), "x.py", BlobRunner("abc")) is None
    assert cap.git_blob_id(str(root), "x.py", BlobRunner(valid + "\nextra")) is None

    class FailBlobRunner:
        def __call__(self, argv, cwd):
            # Semantic match for the 3-token blob query ``git rev-parse HEAD:<path>``.
            if (len(argv) == 3 and argv[:2] == ["git", "rev-parse"]
                    and argv[2].startswith("HEAD:")):
                return FakeOutcome(1, "")
            return ConfigurableFakeRunner(dirty=False)(argv, cwd)

    assert cap.git_blob_id(str(root), "x.py", FailBlobRunner()) is None


# ── New tests: hostile custom-path rejection (backslash / UNC / traversal) ───────
def test_custom_matrix_backslash_path_rejected(tmp_path):
    """A backslash in an argv token is a hostile path form and fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(id="bad", log_name="00.log",
                              argv=["python3", "scripts\\evil.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-matrix-argv:") for w in evidence["infrastructure_warnings"])
    # The raw backslash path must not appear anywhere in the evidence.
    assert "scripts\\evil.py" not in json.dumps(evidence)


def test_custom_matrix_unc_path_rejected(tmp_path):
    """A UNC share path in an argv token fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(id="bad", log_name="00.log",
                              argv=["python3", "//server/share/evil.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-matrix-argv:") for w in evidence["infrastructure_warnings"])
    assert "//server/share/evil.py" not in json.dumps(evidence)


def test_bundle_path_backslash_rejected(tmp_path):
    """A report_path containing a backslash is rejected (hostile separator)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    bundle_rel = cap._posix_rel(str(out), str(root))
    matrix = [cap.CommandSpec(
        id="db-cli", log_name="03-db-cli.log",
        argv=["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation",
              "--findings-output", "/".join([bundle_rel, "03-db-cli.findings.json"])],
        report_path="sub\\evil.json",
        required_artifacts=(),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-bundle-path:") for w in evidence["infrastructure_warnings"])
    assert "sub\\evil.json" not in json.dumps(evidence)


def test_validate_bundle_paths_violations_bounded(tmp_path, monkeypatch):
    """validate_bundle_paths bounds violation collection during iteration.

    An unbounded malformed artifact list stops materializing violations at
    ``MAX_VIOLATIONS`` and retains only the controlled ``OVERFLOW_VIOLATIONS``
    marker, so a hostile matrix can never inflate the returned violation set.
    """
    monkeypatch.setattr(cap, "MAX_VIOLATIONS", 4)
    out_dir = tmp_path / "bundle"
    out_dir.mkdir()
    # Every artifact is a ``..`` traversal: one violation per entry, 250 entries
    # per spec across 5 specs (far beyond the bound).
    hostile_artifacts = tuple(f"../escape_{i}.json" for i in range(250))
    matrix = [
        cap.CommandSpec(
            id=f"c{i}", log_name=f"{i:02d}.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py"],
            required_artifacts=hostile_artifacts,
        )
        for i in range(5)
    ]
    violations = cap.validate_bundle_paths(matrix, str(out_dir))
    # The materialized list never exceeds the bound and ends with the marker.
    assert len(violations) <= cap.MAX_VIOLATIONS
    assert violations[-1] == cap.OVERFLOW_VIOLATIONS
    # Real violations were collected up to the bound before truncation.
    assert sum(1 for v in violations if v.startswith("invalid-bundle-path:")) == \
        cap.MAX_VIOLATIONS - 1


def test_validate_command_matrix_violations_bounded(tmp_path, monkeypatch):
    """validate_command_matrix bounds the returned violation set.

    A hostile matrix whose per-spec artifact lists would generate far more than
    ``MAX_VIOLATIONS`` violations yields a returned list that never exceeds the
    bound and terminates with the controlled ``OVERFLOW_VIOLATIONS`` marker, so a
    malformed matrix can never inflate the violation set handed to the capture.
    """
    monkeypatch.setattr(cap, "MAX_VIOLATIONS", 4)
    root = tmp_path / "repo"
    root.mkdir()
    # Every artifact is a ``..`` traversal: one invalid-bundle-path violation per
    # entry (plus missing-artifact-kind), 60 entries per spec across 10 specs
    # (far beyond the bound).
    hostile_artifacts = tuple(f"../escape_{i}.json" for i in range(60))
    matrix = [
        cap.CommandSpec(
            id=f"c{i}", log_name=f"{i:02d}.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py"],
            required_artifacts=hostile_artifacts,
        )
        for i in range(10)
    ]
    violations = cap.validate_command_matrix(matrix, str(root))
    # The returned list never exceeds the bound and ends with the marker.
    assert len(violations) <= cap.MAX_VIOLATIONS
    assert violations[-1] == cap.OVERFLOW_VIOLATIONS
    # Real violations were collected up to the bound before truncation.
    assert sum(1 for v in violations if v.startswith("invalid-bundle-path:")) == \
        cap.MAX_VIOLATIONS - 1


# ── New tests: warning payload sanitization (GR-00 hardening) ───────────────────
def test_warning_payload_sanitized(tmp_path):
    """A rejected custom path token is redacted, never leaked verbatim."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(id="bad", log_name="00.log",
                              argv=["python3", "C:\\Users\\tester\\secret.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    raw = json.dumps(evidence)
    assert "C:\\Users\\tester" not in raw
    assert "/Users/tester" not in raw
    # The warning uses a bounded redaction marker, not the raw token.
    assert any("<redacted-path>" in w for w in evidence["infrastructure_warnings"])


def test_sanitize_warning_redacts_hostile_payload():
    """Unit coverage of warning-payload sanitization (backslash / UNC / safe)."""
    assert cap._sanitize_warning("invalid-matrix-argv:bad:C:\\secret\\x") == \
        "invalid-matrix-argv:bad:<redacted-path>"
    assert cap._sanitize_warning("invalid-matrix-argv:bad://server/share/x") == \
        "invalid-matrix-argv:bad:<redacted-path>"
    # A safe repository-relative payload is preserved unchanged.
    assert cap._sanitize_warning("missing-required-input:scripts/x.py") == \
        "missing-required-input:scripts/x.py"


# ── New tests: preflight/version metadata bounded sanitization (GR-00) ──────────
class LeakyVersionRunner(ConfigurableFakeRunner):
    """Inject an absolute path into the ``python --version`` output."""

    def __call__(self, argv, cwd):
        if argv and argv[0] == "python":
            self.calls.append(list(argv))
            return FakeOutcome(0, "Python 3.11.4 from /usr/bin/python3 leaked\n")
        # Git preflight and every other command keep the clean base behavior.
        return super().__call__(argv, cwd)


def test_version_metadata_sanitized_and_bounded(tmp_path):
    """Interpreter version strings are sanitized (paths redacted) and bounded."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = LeakyVersionRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                        command_matrix=_fake_matrix(str(root), str(out)))
    gs = json.loads((out / "git-state.json").read_text(encoding="utf-8"))
    # The leaked absolute path in the version string is redacted.
    assert "/usr/bin/python3" not in json.dumps(gs)
    assert gs["python_version"] is not None
    assert len(gs["python_version"]) <= 256


def test_environment_allowed_values_sanitized(tmp_path, monkeypatch):
    """Even allowlisted env values are sanitized (paths redacted) and bounded."""
    monkeypatch.setenv("PYTHON_VERSION", "3.11.4 from /usr/bin/python leaked")
    env = cap.collect_environment()
    value = env["variables"]["PYTHON_VERSION"]
    assert "/usr/bin/python" not in value
    assert len(value) <= 256
    assert "PYTHON_VERSION" in env["allowed_value_keys"]


# ── New tests: stop runner on matrix/path validation failure (GR-00 retry) ──────
def test_stop_runner_on_matrix_validation_failure(tmp_path):
    """No child command runs once matrix/path validation fails closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    abs_path = os.path.abspath(tmp_path / "secret" / "evil.py")
    matrix = [cap.CommandSpec(id="bad", log_name="00.log", argv=["python3", abs_path])]

    executed_matrix_commands = []

    class SpyRunner:
        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            # Record any attempt to actually execute the hostile matrix command.
            if abs_path in argv:
                executed_matrix_commands.append(list(argv))
            return self._inner(argv, cwd)

    runner = SpyRunner()
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    # Validation failed closed, so the capture returns 2 and no matrix command
    # was ever executed (the runner is stopped before any child call).
    assert rc == 2
    assert executed_matrix_commands == []
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-matrix-argv:") for w in evidence["infrastructure_warnings"])
    # No command records were produced because the runner was never invoked.
    assert evidence["commands"] == []


# ── New tests: custom input candidate realpath containment (GR-00 retry) ─────────
def test_custom_input_candidate_realpath_containment(tmp_path):
    """Hostile custom input candidates are never read/hashed; fail closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    # Absolute path, traversal, UNC share, and backslash separators — none may be
    # read, hashed, or persisted verbatim.
    candidates = [
        os.path.abspath(tmp_path / "outside" / "evil.py"),
        "../escape.py",
        "//server/share/evil.py",
        "scripts\\evil.py",
    ]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              input_candidates=candidates)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    raw = json.dumps(evidence["input_manifest"])
    # No raw hostile token leaks into the manifest (reduced to a bounded marker).
    assert "<redacted-unsafe-candidate>" in raw
    assert os.path.abspath(tmp_path / "outside" / "evil.py") not in raw
    assert "../escape.py" not in raw
    assert "//server/share/evil.py" not in raw
    assert "scripts\\evil.py" not in raw
    # Each hostile candidate is recorded as missing (fail closed).
    assert any("missing-required-input" in w for w in evidence["infrastructure_warnings"])


# ── New tests: bounded persisted collections/counts (GR-00 retry) ───────────────
def test_diagnostic_codes_overflow_bounded(tmp_path, monkeypatch):
    """An unbounded diagnostic-code list is replaced by a fail-closed marker."""
    monkeypatch.setattr(cap, "MAX_DIAGNOSTIC_CODES", 2)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    clean_checkout = ConfigurableFakeRunner(dirty=False)

    def many_codes_runner(argv, cwd):
        # Command-aware: git preflight stays CLEAN; only the db-cli stage is
        # blocked while writing the overflowing diagnostic-code report.
        if _matches(argv, "verify_db_access_boundaries.py", "--fail-on-violation"):
            idx = argv.index("--findings-output")
            # Resolve against the runner ``cwd`` (repository root).
            out_path = os.path.join(str(cwd), argv[idx + 1])
            _write_fake_report(out_path, trusted=False,
                               codes=[f"CODE{i}" for i in range(5)], findings=0)
            return FakeOutcome(2, "blocked")
        return clean_checkout(argv, cwd)

    matrix = [_db_cli_matrix(str(root), str(out))]
    rc = cap.capture_evidence(str(root), str(out), runner=many_codes_runner,
                              command_matrix=matrix)
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd = evidence["commands"][0]
    assert cmd["report_diagnostic_codes"] == [cap.OVERFLOW_DIAGNOSTIC_CODES]


def test_finding_count_overflow_fails_closed(tmp_path, monkeypatch):
    """A report exceeding the finding-count bound fails closed (unparseable)."""
    monkeypatch.setattr(cap, "MAX_FINDING_COUNT", 3)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    clean_checkout = ConfigurableFakeRunner(dirty=False)

    def many_findings_runner(argv, cwd):
        # Command-aware: git preflight stays CLEAN; only the db-cli stage is
        # blocked while writing the overflowing findings report.
        if _matches(argv, "verify_db_access_boundaries.py", "--fail-on-violation"):
            idx = argv.index("--findings-output")
            # Resolve against the runner ``cwd`` (repository root).
            out_path = os.path.join(str(cwd), argv[idx + 1])
            _write_fake_report(out_path, trusted=False, codes=["X"], findings=5)
            return FakeOutcome(2, "blocked")
        return clean_checkout(argv, cwd)

    matrix = [_db_cli_matrix(str(root), str(out))]
    rc = cap.capture_evidence(str(root), str(out), runner=many_findings_runner,
                              command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd = evidence["commands"][0]
    assert cmd["parser_error"] == cap.OVERFLOW_FINDING_COUNT
    assert cmd["report_finding_count"] is None


def test_manifest_overflow_fails_closed(tmp_path, monkeypatch):
    """An unbounded input-manifest set fails closed with an overflow marker."""
    monkeypatch.setattr(cap, "MAX_MANIFEST_ENTRIES", 3)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    candidates = [f"scripts/ci/missing_{i}.py" for i in range(6)]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              input_candidates=candidates)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w == cap.OVERFLOW_MANIFEST for w in evidence["infrastructure_warnings"])


def test_warnings_overflow_fails_closed(tmp_path, monkeypatch):
    """An unbounded warning set fails closed with an overflow marker."""
    monkeypatch.setattr(cap, "MAX_WARNINGS", 2)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    candidates = [f"scripts/ci/missing_{i}.py" for i in range(3)]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              input_candidates=candidates)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w == cap.OVERFLOW_WARNINGS for w in evidence["infrastructure_warnings"])


def test_warnings_capped_at_zero_max_warnings(tmp_path, monkeypatch):
    """When ``MAX_WARNINGS == 0`` the persisted warning list is exactly empty (the
    cap is exact, including zero) yet the capture still fails closed (exit 2,
    untrusted) so the overflow is signaled via the exit code rather than a
    persisted warning."""
    monkeypatch.setattr(cap, "MAX_WARNINGS", 0)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    # Three missing required inputs would normally yield three warnings.
    candidates = [f"scripts/ci/missing_{i}.py" for i in range(3)]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              input_candidates=candidates)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The persisted list never exceeds MAX_WARNINGS — including zero.
    assert len(evidence["infrastructure_warnings"]) == 0
    # The overflow is still signaled via the untrusted / failed-closed state.
    assert evidence["trusted"] is False


def test_summary_markdown_bounded(tmp_path, monkeypatch):
    """The persisted summary markdown is length-bounded (fail closed)."""
    monkeypatch.setattr(cap, "MAX_SUMMARY_CHARS", 80)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                         command_matrix=_fake_matrix(str(root), str(out)))
    summary = (out / "summary.md").read_text(encoding="utf-8")
    assert summary.endswith("<truncated>")
    assert len(summary) <= 80 + len("<truncated>")


# ── New tests: UNC/backslash + KeyboardInterrupt/StopIteration sanitization ──────
def test_unc_and_backslash_in_child_output_redacted(tmp_path):
    """UNC shares and backslash paths in child output are redacted."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class LeakyRunner:
        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            outcome = self._inner(argv, cwd)
            if argv and argv[0] == "git":
                # Keep the git preflight/preservation surface CLEAN; only child
                # command output carries the leaky payload.
                return outcome
            combined = outcome.combined + "\n".join([
                "wrote to //server/share/secret.log",
                "wrote to C:\\Users\\tester\\secret.log",
                "wrote to /etc/passwd",
            ])
            return FakeOutcome(outcome.returncode, combined)

    matrix = [cap.CommandSpec(id="leaky", log_name="00-leaky.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = LeakyRunner()
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    log = (out / "commands" / "00-leaky.log").read_text(encoding="utf-8")
    assert "<redacted-path>" in log
    assert "//server/share/secret.log" not in log
    assert "C:\\Users\\tester" not in log
    assert "/etc/passwd" not in log


def test_keyboardinterrupt_stopiteration_in_child_output_redacted(tmp_path):
    """KeyboardInterrupt / StopIteration / GeneratorExit text is redacted."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"

    class LeakyRunner:
        def __init__(self):
            self._inner = ConfigurableFakeRunner(dirty=False)

        def __call__(self, argv, cwd):
            outcome = self._inner(argv, cwd)
            if argv and argv[0] == "git":
                # Keep the git preflight/preservation surface CLEAN; only child
                # command output carries the leaky payload.
                return outcome
            combined = outcome.combined + "\n".join([
                "Traceback (most recent call last):",
                "KeyboardInterrupt",
                "StopIteration: no more items",
                "GeneratorExit: closed",
            ])
            return FakeOutcome(outcome.returncode, combined)

    matrix = [cap.CommandSpec(id="leaky", log_name="00-leaky.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = LeakyRunner()
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    log = (out / "commands" / "00-leaky.log").read_text(encoding="utf-8")
    assert "<redacted-exception>" in log
    assert "KeyboardInterrupt" not in log
    assert "StopIteration" not in log
    assert "GeneratorExit" not in log


# ── New tests: custom argv tokens never persist verbatim (GR-00 retry) ──────────
def test_custom_argv_secret_rejected_and_not_persisted(tmp_path):
    """A secret token in a custom matrix is rejected (fail closed), never executed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(
        id="secret", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py", "password=hunter2secret"],
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert "password=hunter2secret" not in json.dumps(evidence)
    assert any(w.startswith("invalid-matrix-argv:") for w in evidence["infrastructure_warnings"])
    # No command was executed because matrix validation failed closed.
    assert evidence["commands"] == []


def test_sanitize_argv_token_redacts_secret_and_path():
    """Unit: argv token sanitizer redacts secrets and absolute/UNC/backslash paths."""
    assert cap._sanitize_argv_token("password=hunter2") == "<redacted-secret>"
    assert cap._sanitize_argv_token("api_key=sk_live_x") == "<redacted-secret>"
    assert "C:\\secret" not in cap._sanitize_argv_token("C:\\secret\\x")
    assert "//server/share" not in cap._sanitize_argv_token("//server/share/x")
    # A safe repository-relative token is preserved unchanged.
    assert cap._sanitize_argv_token("scripts/ci/verify_guard_registry.py") == \
        "scripts/ci/verify_guard_registry.py"


def test_sanitize_command_id_redacts_hostile_forms():
    """Unit: a CommandSpec id is sanitized (redacted + bounded) before persistence."""
    assert cap._sanitize_command_id("registry-validation") == "registry-validation"
    # A non-string or empty id collapses to the controlled marker.
    assert cap._sanitize_command_id("") == "<non-string>"
    assert cap._sanitize_command_id(None) == "<non-string>"
    # Control characters are stripped.
    assert "\n" not in cap._sanitize_command_id("bad\nid")
    assert "\t" not in cap._sanitize_command_id("bad\tid")
    # Secret assignments and absolute path forms are redacted.
    assert "hunter2" not in cap._sanitize_command_id("password=hunter2")
    assert "<redacted-secret>" in cap._sanitize_command_id("password=hunter2")
    assert "C:\\Users" not in cap._sanitize_command_id("id C:\\Users\\x")
    # An oversized id is length-bounded by MAX_COMMAND_ID_LEN.
    long_id = "a" * (cap.MAX_COMMAND_ID_LEN + 5)
    sanitized_long = cap._sanitize_command_id(long_id)
    assert len(sanitized_long) <= cap.MAX_COMMAND_ID_LEN + len("<truncated>")


def test_preflight_argv_tokens_sanitized(tmp_path):
    """Preflight command records store sanitized argv (no verbatim tokens)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                         command_matrix=_fake_matrix(str(root), str(out)))
    gs = json.loads((out / "git-state.json").read_text(encoding="utf-8"))
    for rec in gs["preflight_commands"]:
        for token in rec["argv"]:
            _assert_no_absolute(token)


# ── New tests: porcelain status parsing (requirement 1) ───────────────────────────
def test_extract_git_filenames_porcelain_forms():
    """Porcelain status parsing strips status columns before filename comparison."""
    # Untracked ``??`` form.
    assert cap._extract_git_filenames("?? config/guards/db_ownership_policy.yml") == \
        ["config/guards/db_ownership_policy.yml"]
    # Modified tracked form (status columns stripped).
    assert cap._extract_git_filenames(" M app/src/main/Foo.kt") == ["app/src/main/Foo.kt"]
    # Staged form.
    assert cap._extract_git_filenames("A  app/src/main/New.kt") == ["app/src/main/New.kt"]
    # Rename form yields both names.
    assert cap._extract_git_filenames("R  old.py -> new.py") == ["old.py", "new.py"]
    # Quoted name (git quoting of special characters) is unquoted.
    assert cap._extract_git_filenames(' M "weird name.py"') == ["weird name.py"]
    # ``--name-only`` diff line (no status columns) is returned as-is.
    assert cap._extract_git_filenames("scripts/ci/x.py") == ["scripts/ci/x.py"]


def test_preservation_fails_on_untracked_forbidden_file(tmp_path):
    """An untracked forbidden file fails the preservation check (porcelain ?? form)."""
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(
        dirty=False, untracked="config/guards/db_ownership_policy.yml\n")
    result = cap.preservation_check(str(root), runner)
    assert result["ok"] is False
    assert result["policy_ok"] is False
    assert result["untracked_ok"] is False


def test_preservation_fails_on_untracked_production_file(tmp_path):
    """An untracked app/src/main file fails the preservation check."""
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(dirty=False, untracked="app/src/main/Foo.kt\n")
    result = cap.preservation_check(str(root), runner)
    assert result["ok"] is False
    assert result["production_ok"] is False
    assert result["untracked_ok"] is False


def test_preservation_fails_on_staged_change(tmp_path):
    """A staged change to a forbidden file fails the preservation check."""
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(
        dirty=False, staged_diff="config/guards/db_ownership_policy.yml\n")
    result = cap.preservation_check(str(root), runner)
    assert result["ok"] is False
    assert result["policy_ok"] is False
    assert result["staged_ok"] is False


def test_preservation_fails_on_leading_space_staged_porcelain(tmp_path):
    """A raw porcelain ``' M path'`` line resolves to the bare path.

    Porcelain status lines must reach ``_extract_git_filenames`` UNSTRIPPED: the
    two status columns are parsed positionally on the raw line, so stripping
    first would mangle `` M path`` into ``M path`` (which matches nothing) and
    the forbidden file would be missed on the porcelain surface.
    """
    root = _make_root(tmp_path)

    class PorcelainOnlyRunner(ConfigurableFakeRunner):
        """Every diff surface stays clean; only porcelain reports the change."""

        def _git(self, argv):
            sub = argv[1:]
            if sub[:2] == ["status", "--porcelain=v1"]:
                return FakeOutcome(0, " M config/guards/db_ownership_policy.yml\n")
            return super()._git(argv)

    result = cap.preservation_check(str(root), PorcelainOnlyRunner(dirty=False))
    # The leading-space form is detected on the porcelain surface itself.
    assert result["ok"] is False
    assert result["policy_ok"] is False
    assert result["untracked_ok"] is False
    assert "config/guards/db_ownership_policy.yml" in result["forbidden_changed"]


def test_preservation_fails_on_mixed_changes(tmp_path):
    """Mixed dirty + staged + untracked changes all fail the preservation check."""
    root = _make_root(tmp_path)
    runner = ConfigurableFakeRunner(
        dirty=True,
        staged_diff="config/baselines/db_access.json\n",
        untracked="app/src/main/Bar.kt\n",
    )
    result = cap.preservation_check(str(root), runner)
    assert result["ok"] is False
    assert result["policy_ok"] is False
    assert result["production_ok"] is False
    assert result["untracked_ok"] is False


# ── New tests: complete custom CommandSpec schema validation (req 2 & 3) ──────────
def test_validate_command_matrix_missing_artifact_kind_file(tmp_path):
    """A required file artifact without an explicit kind fails closed."""
    root = _make_root(tmp_path)
    spec = cap.CommandSpec(
        id="c", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("out.json",),
        artifact_kinds=(),
    )
    violations = cap.validate_command_matrix([spec], str(root))
    assert any(v.startswith("missing-artifact-kind:c:") for v in violations)


def test_validate_command_matrix_missing_artifact_kind_dir(tmp_path):
    """A required directory artifact without an explicit kind fails closed."""
    root = _make_root(tmp_path)
    spec = cap.CommandSpec(
        id="c", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("outdir",),
        artifact_kinds=(),
    )
    violations = cap.validate_command_matrix([spec], str(root))
    assert any(v.startswith("missing-artifact-kind:c:") for v in violations)


def test_validate_command_matrix_invalid_artifact_kind_file(tmp_path):
    """A required artifact declared with a non-file/non-dir kind fails closed."""
    root = _make_root(tmp_path)
    spec = cap.CommandSpec(
        id="c", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("out.json",),
        artifact_kinds=(("out.json", "blob"),),
    )
    violations = cap.validate_command_matrix([spec], str(root))
    assert any(v.startswith("missing-artifact-kind:c:") for v in violations)


def test_missing_artifact_kind_fails_closed_stop_before_run(tmp_path):
    """A missing artifact kind fails closed with zero runner calls / empty commands."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(
        id="c", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("out.json",),
        artifact_kinds=(),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("missing-artifact-kind:") for w in evidence["infrastructure_warnings"])
    # Stop-before-run: no child command executed, no command record produced.
    assert evidence["commands"] == []
    assert runner.calls == []


def test_custom_command_id_overflow_fails_closed(tmp_path):
    """An oversized custom command id fails closed with a bounded marker."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    long_id = "x" * (cap.MAX_COMMAND_ID_LEN + 10)
    matrix = [cap.CommandSpec(
        id=long_id, log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.OVERFLOW_COMMAND_ID) for w in evidence["infrastructure_warnings"])
    assert evidence["commands"] == []
    assert runner.calls == []


def test_command_id_sanitized_before_persistence(tmp_path):
    """A short-but-hostile custom command id is sanitized before it is persisted.

    Such an id passes matrix validation (non-empty string within
    ``MAX_COMMAND_ID_LEN``), so the command runs and a ``CommandResult`` record
    is produced; the persisted ``id`` must carry no control characters, secret,
    or absolute-path content in ``evidence.json`` / ``semantic-summary.json``.
    """
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    hostile_id = "le\nak\tid password=hunter2 C:\\Users\\tester"
    assert len(hostile_id) <= cap.MAX_COMMAND_ID_LEN  # passes validation
    matrix = [cap.CommandSpec(
        id=hostile_id, log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 0
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd_id = evidence["commands"][0]["id"]
    raw = json.dumps(evidence)
    # Control characters are stripped; secrets and absolute paths are redacted.
    assert "\n" not in cmd_id
    assert "\t" not in cmd_id
    assert "hunter2" not in raw
    assert "C:\\Users\\tester" not in raw
    assert "<redacted-secret>" in cmd_id
    assert "<redacted-path>" in cmd_id
    # The semantic summary derives its id from the same sanitized record.
    semantic = json.loads((out / "semantic-summary.json").read_text(encoding="utf-8"))
    assert semantic["commands"][0]["id"] == cmd_id


def test_custom_path_overflow_fails_closed(tmp_path):
    """An oversized derived path string fails closed with a bounded marker."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    long_path = "x" * (cap.MAX_PATH_LEN + 10) + ".log"
    matrix = [cap.CommandSpec(
        id="bad", log_name=long_path,
        argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.OVERFLOW_PATH) for w in evidence["infrastructure_warnings"])
    assert evidence["commands"] == []
    assert runner.calls == []


def test_persisted_collections_are_bounded(tmp_path):
    """Persisted collections are bounded by the finite MAX_* constants."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    cap.capture_evidence(str(root), str(out), runner=runner,
                         command_matrix=_fake_matrix(str(root), str(out)))
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert len(evidence["input_manifest"]) <= cap.MAX_MANIFEST_ENTRIES
    assert len(evidence["infrastructure_warnings"]) <= cap.MAX_WARNINGS
    assert len(evidence["commands"]) <= cap.MAX_MATRIX_COMMANDS
    assert len(evidence["git_state"]["preflight_commands"]) <= 64


# ── New tests: warning sanitization (requirement 5) ──────────────────────────────
def test_warning_sanitization_redacts_secret_and_validates_code():
    """Warning sanitization redacts secret payloads and rejects untrusted codes."""
    # A secret assignment inside a warning payload is redacted, never persisted verbatim.
    out = cap._sanitize_warning("missing-required-input:foo password=secret bar")
    assert "password=secret" not in out
    assert "<redacted-secret>" in out
    # An untrusted / malformed warning code is reduced to a controlled marker.
    assert cap._sanitize_warning("9b97e79:arbitrary") == cap.REDACTED_MARKER
    assert cap._sanitize_warning("") == cap.REDACTED_MARKER
    # A controlled code with a safe repo-relative payload is preserved.
    assert cap._sanitize_warning("missing-required-input:scripts/x.py") == \
        "missing-required-input:scripts/x.py"


# ── New tests: nested internal/external symlink hash containment (requirement 4) ──
def test_nested_internal_symlink_hash_exclusion(tmp_path):
    """A nested internal symlink inside an artifact dir is excluded from the hash."""
    out = tmp_path / "bundle"
    out.mkdir()
    art = out / "artdir"
    art.mkdir()
    (art / "summary.json").write_text("internal", encoding="utf-8")
    nested = art / "nested"
    nested.mkdir()
    (nested / "deep.json").write_text("deep", encoding="utf-8")
    # Internal symlink (points within the bundle) must be skipped, not followed.
    _try_symlink(str(art / "summary.json"), str(art / "internal_link.json"))
    h1 = cap.hash_artifact(str(art), "dir")
    assert h1 is not None
    # Tampering an internal regular file changes the hash (containment proof).
    (nested / "deep.json").write_text("changed", encoding="utf-8")
    h2 = cap.hash_artifact(str(art), "dir")
    assert h1 != h2


def test_external_symlink_hash_exclusion(tmp_path):
    """An external symlink inside an artifact dir is excluded (never read/hashed)."""
    out = tmp_path / "bundle"
    out.mkdir()
    art = out / "artdir"
    art.mkdir()
    (art / "summary.json").write_text("internal", encoding="utf-8")
    outside = tmp_path / "outside"
    outside.mkdir()
    secret = outside / "secret.json"
    secret.write_text("EXTERNAL_SECRET", encoding="utf-8")
    _try_symlink(str(secret), str(art / "evil_link.json"))
    h1 = cap.hash_artifact(str(art), "dir")
    assert h1 is not None
    # Tampering the external file must NOT change the hash (excluded/rejected).
    secret.write_text("TAMPERED", encoding="utf-8")
    h2 = cap.hash_artifact(str(art), "dir")
    assert h1 == h2


# ── New tests: strict-review blockers (latest pass) ────────────────────────────
# Requirement 1: collection bounds enforced during iteration; persisted arrays
# bounded; runner calls bounded (stop before run on overflow).
def test_matrix_overflow_stops_runner(tmp_path):
    """A matrix exceeding MAX_MATRIX_COMMANDS fails closed with zero runner calls."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    big = [
        cap.CommandSpec(id=f"c{i}", log_name=f"{i:02d}.log",
                        argv=["python3", "scripts/ci/verify_guard_registry.py"])
        for i in range(cap.MAX_MATRIX_COMMANDS + 5)
    ]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=big)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.OVERFLOW_MATRIX) for w in evidence["infrastructure_warnings"])
    # Validation failed before any child command ran.
    assert runner.calls == []
    assert evidence["commands"] == []


def test_persisted_arrays_bounded_under_overflow(tmp_path, monkeypatch):
    """Persisted manifest/warnings stay within the finite MAX_* bounds on overflow."""
    monkeypatch.setattr(cap, "MAX_MANIFEST_ENTRIES", 3)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    candidates = [f"scripts/ci/missing_{i}.py" for i in range(6)]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)),
                              input_candidates=candidates)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The materialized manifest is bounded (truncated during iteration) and the
    # controlled overflow marker is retained.
    assert len(evidence["input_manifest"]) <= cap.MAX_MANIFEST_ENTRIES
    assert any(w == cap.OVERFLOW_MANIFEST for w in evidence["infrastructure_warnings"])


# Requirement 2: any required artifact/report/log hash failure, disappearance,
# invalid type, symlink, or parse/read failure fails closed (exit 2).
def test_required_artifact_hash_failure_fails_closed(tmp_path, monkeypatch):
    """A present, correctly-typed artifact whose hash fails closes the capture."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    monkeypatch.setattr(cap, "hash_artifact", lambda *a, **k: None)
    matrix = [cap.CommandSpec(
        id="needs-artifact", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("present.log",),
        artifact_kinds=(("present.log", "file"),),
    )]
    # The bundle directory does not exist until capture_evidence creates it;
    # create it here so the pre-seeded artifact write cannot fail at setup.
    out.mkdir(parents=True, exist_ok=True)
    (out / "present.log").write_text("data", encoding="utf-8")
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.ARTIFACT_HASH_FAILED)
               for w in evidence["infrastructure_warnings"])


def test_log_hash_failure_fails_closed(tmp_path, monkeypatch):
    """A log hash failure sets launch_error and fails the capture closed."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    monkeypatch.setattr(cap, "_race_safe_hash_file", lambda *a, **k: None)
    matrix = [cap.CommandSpec(id="c", log_name="00.log",
                              argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["commands"][0]["launch_error"] == "LOG_HASH_FAILED"


def test_report_hash_failure_fails_closed(tmp_path, monkeypatch):
    """A required report whose read/hash fails closes the capture (parser_error)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    # The report hash now derives from the same race-safe byte snapshot used for
    # parsing, so a read failure (None) must fail closed with REPORT_HASH_FAILED.
    monkeypatch.setattr(cap, "_race_safe_read_bytes", lambda *a, **k: None)
    matrix = [_db_cli_matrix(str(root), str(out))]
    runner = ConfigurableFakeRunner(dirty=False, write_reports=True)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert evidence["commands"][0]["parser_error"] == "REPORT_HASH_FAILED"


# Requirement 3: race-safe reads/hashing reject symlinks / non-regular files.
def test_race_safe_rejects_symlink_input(tmp_path):
    """A symlinked input is rejected by the race-safe reader (None)."""
    root = _make_root(tmp_path)
    target = root / "scripts" / "verify_db_access_boundaries.py"
    link = root / "scripts" / "link_input.py"
    _try_symlink(str(target), str(link))
    assert cap._race_safe_read_bytes(str(link)) is None


def test_race_safe_rejects_symlink_report(tmp_path):
    """A symlinked report is rejected by the race-safe hasher (None)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    out.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    outside_report = outside / "evil.json"
    outside_report.write_text(
        '{"schema":"cost-aggregator.guard-findings","schema_version":2}', encoding="utf-8")
    _try_symlink(str(outside_report), str(out / "evil_link.json"))
    assert cap._race_safe_hash_file(str(out / "evil_link.json")) is None


def test_race_safe_rejects_nonregular(tmp_path):
    """A directory (non-regular) is rejected by the race-safe readers."""
    root = _make_root(tmp_path)
    d = root / "adir"
    d.mkdir()
    assert cap._race_safe_read_bytes(str(d)) is None
    assert cap._race_safe_hash_file(str(d)) is None


# Requirement 4: malformed injected CommandSpec / nested fields fail closed.
def test_non_command_spec_fails_closed(tmp_path):
    """A matrix entry that is not a CommandSpec fails closed with zero runner calls."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [{"id": "bad", "argv": ["python3", "x"]}]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.INVALID_MATRIX_SPEC)
               for w in evidence["infrastructure_warnings"])
    assert runner.calls == []
    assert evidence["commands"] == []


def test_malformed_artifact_kinds_fails_closed(tmp_path):
    """A malformed artifact_kinds entry (non 2-tuple) fails closed, zero runner calls."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(
        id="c", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=("out.json",),
        artifact_kinds=(("out.json", "file"), "bad-entry"),
    )]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.INVALID_MATRIX_SPEC)
               for w in evidence["infrastructure_warnings"])
    assert runner.calls == []
    assert evidence["commands"] == []


def test_malformed_argv_type_fails_closed(tmp_path):
    """An argv that is not a list of strings fails closed (type + per-token warning)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(id="c", log_name="00.log", argv=["python3", 123])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.INVALID_MATRIX_SPEC)
               for w in evidence["infrastructure_warnings"])
    assert any("invalid-matrix-argv:c:" in w for w in evidence["infrastructure_warnings"])
    assert runner.calls == []
    assert evidence["commands"] == []


# Requirement 5: closed WARNING_CODE_ALLOWLIST + structured make_warning.
def test_make_warning_structured():
    """make_warning is the structured constructor; payloads are sanitized."""
    assert cap.make_warning("missing-required-input", "scripts/x.py") == \
        "missing-required-input:scripts/x.py"
    assert cap.make_warning("OVERFLOW_MANIFEST") == cap.OVERFLOW_MANIFEST
    assert "<redacted-secret>" in cap.make_warning("invalid-matrix-argv", "bad", "password=hunter2")
    assert "<redacted-path>" in cap.make_warning("invalid-bundle-path", "bad", "C:\\secret")


def test_make_warning_unknown_code_redacted():
    """An unknown warning code collapses to the redaction marker."""
    assert cap.make_warning("not-a-real-code", "payload") == cap.REDACTED_MARKER
    assert cap.make_warning("9b97e79", "arbitrary") == cap.REDACTED_MARKER


def test_warning_allowlist_rejects_unknown_code():
    """A warning whose code is outside the closed allowlist is redacted at assembly."""
    assert cap._sanitize_warning("unknown-code:some arbitrary payload") == cap.REDACTED_MARKER
    assert cap._sanitize_warning("missing-required-input:scripts/x.py") == \
        "missing-required-input:scripts/x.py"


# ── New tests: latest strict-review blockers (non-string paths / overflow / output hash / snapshot) ──
# Requirement 1: nested CommandSpec log_name / report_path types validated before any
# os.path operation; malformed non-string values fail closed with zero runner calls.
def test_validate_command_matrix_rejects_nonstring_log_and_report_path(tmp_path):
    """validate_command_matrix flags non-string log_name / report_path (fail closed)."""
    root = tmp_path / "repo"
    root.mkdir()
    log_spec = cap.CommandSpec(id="c", log_name=123, argv=["python3", "x"])
    report_spec = cap.CommandSpec(
        id="c", log_name="00.log", argv=["python3", "x"], report_path=456)
    v1 = cap.validate_command_matrix([log_spec], str(root))
    v2 = cap.validate_command_matrix([report_spec], str(root))
    assert any(w.startswith("invalid-bundle-path:") for w in v1)
    assert any(w.startswith("invalid-bundle-path:") for w in v2)


def test_nonstring_log_name_fails_closed_zero_runner(tmp_path):
    """A non-string log_name fails closed with zero runner calls (no os.path crash)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(id="c", log_name=123, argv=["python3", "scripts/ci/verify_guard_registry.py"])]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-bundle-path:") for w in evidence["infrastructure_warnings"])
    # Validation failed before any child command ran.
    assert runner.calls == []
    assert evidence["commands"] == []


def test_nonstring_report_path_fails_closed_zero_runner(tmp_path):
    """A non-string report_path fails closed with zero runner calls."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [cap.CommandSpec(
        id="c", log_name="00.log", argv=["python3", "scripts/ci/verify_guard_registry.py"],
        report_path=456)]
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith("invalid-bundle-path:") for w in evidence["infrastructure_warnings"])
    assert runner.calls == []
    assert evidence["commands"] == []


# Requirement 2: required-artifact hash collection bounded by an aggregate limit;
# overflow fails closed with a controlled marker, never materializing unbounded hashes.
def test_required_artifact_hash_overflow_fails_closed(tmp_path, monkeypatch):
    """An unbounded required-artifact hash set fails closed (OVERFLOW marker)."""
    monkeypatch.setattr(cap, "MAX_REQUIRED_ARTIFACT_HASHES", 2)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    arts = tuple(f"art{i}.json" for i in range(5))
    kinds = tuple((f"art{i}.json", "file") for i in range(5))
    matrix = [cap.CommandSpec(
        id="many", log_name="00.log",
        argv=["python3", "scripts/ci/verify_guard_registry.py"],
        required_artifacts=arts, artifact_kinds=kinds)]
    # The bundle directory does not exist until capture_evidence creates it;
    # create it here so the pre-seeded artifact writes cannot fail at setup.
    out.mkdir(parents=True, exist_ok=True)
    for i in range(5):
        (out / f"art{i}.json").write_text("data", encoding="utf-8")
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w == cap.OVERFLOW_REQUIRED_ARTIFACT_HASHES
               for w in evidence["infrastructure_warnings"])
    # Hashes were bounded: at most the limit was materialized (no unbounded set).
    assert len(evidence["required_artifact_hashes"]) <= cap.MAX_REQUIRED_ARTIFACT_HASHES


# Requirement 3: any top-level output hash/read failure fails closed (exit 2),
# emits a controlled diagnostic, and never substitutes an empty hash.
def test_output_hash_failure_fails_closed(tmp_path, monkeypatch):
    """A top-level output whose hash fails closes the capture (no empty hash)."""
    monkeypatch.setattr(cap, "_race_safe_hash_file", lambda *a, **k: None)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    assert any(w.startswith(cap.OUTPUT_HASH_FAILED)
               for w in evidence["infrastructure_warnings"])
    # The bundle is no longer trusted once an output hash failed.
    assert evidence["trusted"] is False
    # No empty hash line was substituted into output-sha256.txt.
    out_sha = (out / "output-sha256.txt").read_text(encoding="utf-8")
    for line in out_sha.splitlines():
        if not line:
            continue
        assert not line.startswith("  "), f"empty hash line found: {line!r}"


def test_final_output_hash_pass_failure_is_consistent_and_untrusted(tmp_path, monkeypatch):
    """A top-level output whose hash succeeds on the first pass but fails on the
    FINAL pass (a TOCTOU / replacement between the two passes) still sets
    ``capture_failed``, emits the controlled ``output-hash-failed`` diagnostic, and
    rewrites the on-disk evidence to a consistent untrusted state — never a stale
    trusted artifact."""
    # Return a valid hash on the first call per path, None on the second (the final
    # pass).  Each top-level output is hashed exactly twice (first pass + final
    # pass); command logs are hashed once and stay valid.
    call_counts: dict = {}

    def fake_hash(path):
        call_counts[path] = call_counts.get(path, 0) + 1
        if call_counts[path] == 1:
            return "a" * 64
        return None

    monkeypatch.setattr(cap, "_race_safe_hash_file", fake_hash)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    # The final-pass failure closes the capture.
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The controlled diagnostic is present and the bundle is untrusted.
    assert any(w.startswith(cap.OUTPUT_HASH_FAILED)
               for w in evidence["infrastructure_warnings"])
    assert evidence["trusted"] is False
    # The on-disk evidence reflects the final (untrusted) state, not a stale
    # pre-failure trusted artifact.
    assert evidence["trusted"] is False
    # No empty hash line was substituted into output-sha256.txt.
    out_sha = (out / "output-sha256.txt").read_text(encoding="utf-8")
    for line in out_sha.splitlines():
        if not line:
            continue
        assert not line.startswith("  "), f"empty hash line found: {line!r}"


def test_final_pass_single_output_failure_persisted_and_recomputed(tmp_path, monkeypatch):
    """First hash pass succeeds for every output; the FINAL pass fails for one
    different output.  The diagnostic must be persisted into the evidence /
    summary / semantic artifacts and ``output-sha256.txt`` must be recomputed
    AFTER the final rewrite: the failed output excluded, every surviving hash
    matching the final on-disk bytes (no stale hash, no lost diagnostic)."""
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    victim_rel = "summary.md"
    out_real = os.path.realpath(str(out))
    real_hash = cap._race_safe_hash_file
    call_counts: dict = {}

    def fake_hash(path):
        call_counts[path] = call_counts.get(path, 0) + 1
        # First pass (call #1 per path) succeeds everywhere; from the second
        # (final) pass on, only the victim output fails (TOCTOU on one file).
        if call_counts[path] > 1 and cap._posix_rel(path, out_real) == victim_rel:
            return None
        return real_hash(path)

    monkeypatch.setattr(cap, "_race_safe_hash_file", fake_hash)
    runner = ConfigurableFakeRunner(dirty=False)
    rc = cap.capture_evidence(str(root), str(out), runner=runner,
                              command_matrix=_fake_matrix(str(root), str(out)))
    assert rc == 2
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    # The final-pass diagnostic is persisted (not lost) and the bundle is untrusted.
    assert f"{cap.OUTPUT_HASH_FAILED}:{victim_rel}" in evidence["infrastructure_warnings"]
    assert evidence["trusted"] is False
    # summary.md was rewritten by the finalization and carries the same warning;
    # semantic-summary.json reflects the untrusted state.
    summary_text = (out / "summary.md").read_text(encoding="utf-8")
    assert f"- {cap.OUTPUT_HASH_FAILED}:{victim_rel}" in summary_text
    semantic = json.loads((out / "semantic-summary.json").read_text(encoding="utf-8"))
    assert semantic["trusted"] is False
    # output-sha256.txt was recomputed after the final rewrite: the victim is
    # excluded and every published hash matches the FINAL on-disk bytes.
    out_sha = (out / "output-sha256.txt").read_text(encoding="utf-8")
    published = {}
    for line in out_sha.splitlines():
        if not line:
            continue
        h, rel = line.split("  ", 1)
        published[rel] = h
    assert victim_rel not in published
    expected_names = {
        "git-state.json", "environment.json", "input-manifest.json",
        "input-sha256.txt", "evidence.json", "semantic-summary.json",
    }
    assert set(published) == expected_names
    for rel, h in published.items():
        assert h == real_hash(os.path.join(out_real, rel)), f"stale hash published for {rel}"


# Requirement 4: report hash and parse use one stable byte snapshot (no version mix).
def test_report_hash_and_parse_use_one_snapshot(tmp_path, monkeypatch):
    """run_command hashes and parses the SAME report byte snapshot."""
    fixed = json.dumps({
        "schema": cap.REPORT_SCHEMA, "schema_version": cap.REPORT_SCHEMA_VERSION,
        "guard": "db_access", "findings": [], "diagnostics": [{"code": "X"}],
        "statistics": {"trusted": True, "files_scanned": 1},
    }).encode("utf-8")
    monkeypatch.setattr(cap, "_race_safe_read_bytes", lambda *a, **k: fixed)
    root = _make_root(tmp_path)
    out = root / "out" / "run-1"
    matrix = [_db_cli_matrix(str(root), str(out))]
    runner = ConfigurableFakeRunner(dirty=False, write_reports=True)
    cap.capture_evidence(str(root), str(out), runner=runner, command_matrix=matrix)
    evidence = json.loads((out / "evidence.json").read_text(encoding="utf-8"))
    cmd = evidence["commands"][0]
    # The hash and the parse both derive from the single ``fixed`` snapshot.
    assert cmd["report_sha256"] == cap.sha256_bytes(fixed)
    assert cmd["parser_error"] is None
    assert cmd["report_diagnostic_codes"] == ["X"]


def test_parse_v2_report_accepts_raw_snapshot(tmp_path):
    """parse_v2_report parses a supplied raw snapshot without re-reading the path."""
    raw = json.dumps({
        "schema": cap.REPORT_SCHEMA, "schema_version": cap.REPORT_SCHEMA_VERSION,
        "findings": [], "diagnostics": [], "statistics": {"trusted": False},
    }).encode("utf-8")
    parsed = cap.parse_v2_report("ignored-path", raw=raw)
    assert parsed["schema_version"] == cap.REPORT_SCHEMA_VERSION
    assert parsed["trusted"] is False
    assert parsed["parser_error"] is None


# ── Strict typed v2 report containers (malformed shapes fail closed) ──────────
def _v2_raw(**overrides):
    """A minimal valid v2 report byte snapshot with per-key overrides."""
    report = {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [],
        "statistics": {"trusted": True},
    }
    report.update(overrides)
    return json.dumps(report).encode("utf-8")


def test_parse_v2_report_rejects_statistics_list():
    """A list-typed statistics container is malformed (never raises, fails closed).

    Regression: a list previously reached ``statistics.get("trusted")`` and raised
    ``AttributeError``, violating the parser's never-raise contract.
    """
    parsed = cap.parse_v2_report("ignored-path", raw=_v2_raw(statistics=["trusted"]))
    assert parsed["parser_error"] == cap.MALFORMED_STATISTICS
    assert parsed["trusted"] is None
    assert parsed["finding_count"] is None
    assert parsed["diagnostic_codes"] == []


def test_parse_v2_report_rejects_diagnostics_dict():
    """A dict-typed diagnostics container is malformed, never partially accepted."""
    parsed = cap.parse_v2_report(
        "ignored-path", raw=_v2_raw(diagnostics={"code": "X"}))
    assert parsed["parser_error"] == cap.MALFORMED_DIAGNOSTICS
    assert parsed["diagnostic_codes"] == []
    assert parsed["finding_count"] is None


def test_parse_v2_report_rejects_findings_dict():
    """A dict-typed findings container is malformed (count never derived from keys).

    Regression: a dict previously produced ``finding_count`` = number of keys with
    ``parser_error`` ``None`` — silently accepted.
    """
    parsed = cap.parse_v2_report(
        "ignored-path",
        raw=_v2_raw(findings={"a": {"rule": "X"}, "b": {"rule": "Y"}}))
    assert parsed["parser_error"] == cap.MALFORMED_FINDINGS
    assert parsed["finding_count"] is None


def test_parse_v2_report_rejects_malformed_diagnostic_entry():
    """A diagnostic entry that is not an object with a string code fails closed.

    A non-string code (or a bare-string diagnostic) previously was silently
    skipped; it now yields a controlled parser error.
    """
    parsed_nonstring_code = cap.parse_v2_report(
        "ignored-path", raw=_v2_raw(diagnostics=[{"code": 123}]))
    assert parsed_nonstring_code["parser_error"] == cap.MALFORMED_DIAGNOSTIC_ENTRY
    parsed_bare_string = cap.parse_v2_report(
        "ignored-path",
        raw=_v2_raw(diagnostics=["DB_POLICY_INCOMPLETE_V2"]))
    assert parsed_bare_string["parser_error"] == cap.MALFORMED_DIAGNOSTIC_ENTRY
    # No partial codes survive a malformed entry.
    assert parsed_nonstring_code["diagnostic_codes"] == []
    assert parsed_bare_string["diagnostic_codes"] == []


def test_parse_v2_report_rejects_malformed_finding_shape():
    """Each finding must be a bounded JSON object; anything else fails closed."""
    parsed_scalar = cap.parse_v2_report(
        "ignored-path", raw=_v2_raw(findings=["not-an-object"]))
    assert parsed_scalar["parser_error"] == cap.MALFORMED_FINDING_ENTRY
    # A hostile wide-object finding exceeding MAX_FINDING_KEYS also fails closed.
    wide = {f"k{i}": i for i in range(cap.MAX_FINDING_KEYS + 1)}
    parsed_wide = cap.parse_v2_report(
        "ignored-path", raw=_v2_raw(findings=[wide]))
    assert parsed_wide["parser_error"] == cap.MALFORMED_FINDING_ENTRY
    # A well-formed finding still parses cleanly.
    ok = cap.parse_v2_report(
        "ignored-path",
        raw=_v2_raw(findings=[{"rule": "X", "severity": "error"}]))
    assert ok["parser_error"] is None
    assert ok["finding_count"] == 1


