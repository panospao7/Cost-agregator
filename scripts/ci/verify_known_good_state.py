#!/usr/bin/env python3
"""
verify_known_good_state.py -- PR-GR-10e executable known-good scorecard.

Executes the section-7 rows of docs/ci/GR00-GR04_validation_checklist.md
against the live repository and prints a deterministic scorecard, so silent
drift of the documented known-good state (the R11 lesson: the table pinned a
blocked exit-2 gate while the gate was activated exit-0) is detected
mechanically instead of by re-reading prose.

Rows (fixed order, one check each):

1. active_db_gate          -- the real four-config-flag gate run
                              (scripts/verify_db_access_boundaries.py) must
                              accept the real tree: exit 0, trusted, 0
                              findings, exactly 20 x DB_SIGNATURE_UNRESOLVED
                              advisory diagnostics; the active policy must be
                              v2, the v1 bytes archived at
                              config/guards/db_ownership_policy.legacy.yml,
                              and the v2 ratchet live with an empty baseline.
                              (Decision recorded in PR-GR-10e: the scorecard
                              runs the REAL gate rather than reusing the
                              GR-09 frozen report -- the script is meant to
                              be run explicitly, not per-commit.)
2. inventory_only          -- platform-conditional.  On a durability-barrier
                              platform (os.O_DIRECTORY present; Linux CI) the
                              --inventory-only run must exit 0 trusted with
                              zero diagnostics; without a confirmable barrier
                              (Windows) it must exit 2 untrusted with exactly
                              the single controlled
                              INVENTORY_DURABILITY_UNCONFIRMED diagnostic and
                              a written mutators dump (the atomic replace
                              precedes the barrier check).  Zero
                              DB_SOURCE_ROOT_* codes in both branches.
3. migration_fold          -- scripts/migrate_db_policy_signatures.py
                              --check must exit 1 (documented migration debt)
                              with the current fold truth input=99
                              resolved=57 unresolved=42 duplicates=0.
4. meta_guard_source_roots -- scripts/ci/verify_production_source_roots.py
                              must exit 0 silent.
5. candidate_reproducible  -- the migrate CLI's --verify mode (in-memory
                              regeneration over the SAME reviewed seed input;
                              never writes the tracked artifacts) must exit 0
                              against the tracked pair, and the tracked
                              candidate must be a v2 document with exactly
                              472 entries.
6. structural_manifest     -- the structural expected-methods manifest must
                               pin counts.structural_entries=64
                               (expected=60 + fixtures=4) and
                               config/guards/db_structural_exceptions.yml must
                               carry exactly 64 entries.
7. test_result_freshness   -- OPTIONAL (PR-GR-10f).  The test-result
                               freshness stamp at
                               app/build/test-results/.freshness-stamp.json
                               (written by
                               scripts/ci/test_result_freshness.py --write
                               right after a test run) must be fresh per
                               scripts/ci/test_result_freshness.py --check:
                               matches HEAD, within max age, no XML newer
                               than the stamp.  SKIP (documented,
                               non-blocking) when no stamp exists (the
                               workflow was never adopted); PASS when fresh;
                               FAIL on stale/SHA drift (the R12 lesson:
                               stale round-11 XMLs were consumed as fresh
                               failures).

Output: a deterministic scorecard on stdout (fixed row order; row, result,
expected, observed; no timestamps, no paths, no raw tool output).  The
rendered scorecard is pure ASCII by construction (_ascii_safe): decorative
non-ASCII glyphs map to fixed ASCII equivalents and any other non-ASCII
character reduces to '?', so a cp1252-redirected Windows stdout (which
would otherwise raise UnicodeEncodeError) can never crash the scorecard.

Exit codes:
  0 -- all pinned rows PASS and the optional freshness row is PASS or SKIP
       (the known-good state holds; SKIP only documents the never-stamped
       state and is non-blocking);
  1 -- at least one row FAIL (documented state drift; route to the fix loop);
  2 -- infrastructure (a row could not be executed/observed: spawn failure,
       timeout, unreadable report, tool-side exit 2, missing PyYAML).  Any
       INFRA row forces exit 2 regardless of other rows (fail closed).

Row-outcome mapping per underlying tool (mirrors the static suite's
exit-code semantics where they overlap):

  * gate exit 0/1 -> verdict (1 = real findings on the real tree = drift);
    gate exit 2/other -> INFRA (untrusted infrastructure diagnostics);
  * inventory exit 0 (barrier) / exit 2 (no barrier) -> verdict; other -> INFRA;
  * migrate --check exit 1 -> verdict on the fold counts; exit 0 -> FAIL
    (debt resolved = drift); exit 2/other -> INFRA;
  * meta-guard exit 0 -> verdict (must be silent); exit 2 -> FAIL (its
    documented topology-diagnostics path is determinable drift); other -> INFRA;
  * migrate --verify exit 0 -> verdict (plus candidate shape); exit 1 -> FAIL
    (byte drift); exit 2/other -> INFRA;
  * freshness --check exit 0 -> PASS; exit 1 -> FAIL, except the parsed
    verdict stamp_missing which maps to the documented SKIP (never-stamped
    state); exit 2/other -> INFRA.

Runtime: deliberately expensive (full gate + inventory + migration fold +
meta-guard + candidate verify; roughly gate ~250s warm / ~700s cold plus
~60s inventory + ~60s migration + ~2s meta + ~60s verify + ~1s freshness
stamp check).  The script is
meant to be run explicitly (static-suite entry + orchestrator), not
per-commit.  Internal per-command timeouts (gate 1200s; inventory, migrate,
verify 600s; meta 300s; freshness 120s) sit inside the static suite's
default 1500s per-guard budget for the warm path; raise
GUARD_TIMEOUT_SECONDS for the suite when running against a cold tree.

Privacy posture: observed fields carry counts, controlled status constants,
and booleans only -- never raw tool stdout/stderr, exception text, stack
traces, or filesystem paths.
"""

from __future__ import annotations

import argparse
import functools
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Sequence, Tuple

try:
    import yaml
except ImportError:  # pragma: no cover - environment/configuration failure
    yaml = None


# ── Row identity ────────────────────────────────────────────────────────────────

ROW_ACTIVE_DB_GATE = "active_db_gate"
ROW_INVENTORY_ONLY = "inventory_only"
ROW_MIGRATION_FOLD = "migration_fold"
ROW_META_SOURCE_ROOTS = "meta_guard_source_roots"
ROW_CANDIDATE_REPRODUCIBLE = "candidate_reproducible"
ROW_STRUCTURAL_MANIFEST = "structural_manifest"
ROW_TEST_RESULT_FRESHNESS = "test_result_freshness"

OUTCOME_PASS = "PASS"
OUTCOME_FAIL = "FAIL"
OUTCOME_INFRA = "INFRA"
OUTCOME_SKIP = "SKIP"


# ── Repository paths (POSIX, repo-relative) ─────────────────────────────────────

_GATE_SCRIPT = "scripts/verify_db_access_boundaries.py"
_OWNERSHIP_POLICY = "config/guards/db_ownership_policy.yml"
_STRUCTURAL_EXCEPTIONS = "config/guards/db_structural_exceptions.yml"
_STRUCTURAL_EXPECTED_METHODS = (
    "config/guards/db_structural_exceptions_expected_methods.yml"
)
_RAW_QUERY_POLICY = "config/guards/db_raw_query_classification.yml"
_LEGACY_POLICY = "config/guards/db_ownership_policy.legacy.yml"
_RATCHET_BASELINE = "config/baselines/db_access_v2.json"
_MIGRATE_SCRIPT = "scripts/migrate_db_policy_signatures.py"
_SEED_ROWS = "docs/ci/db-findings/GR-08-seeds.yml"
_CANDIDATE = "config/guards/db_ownership_policy.signatures.candidate.yml"
_META_SCRIPT = "scripts/ci/verify_production_source_roots.py"
_META_MANIFEST = "config/guards/production_source_roots.yml"
_FRESHNESS_SCRIPT = "scripts/ci/test_result_freshness.py"
_FRESHNESS_RESULTS_RELPATH = ("app", "build", "test-results")
_FRESHNESS_STAMP_NAME = ".freshness-stamp.json"

_DEFAULT_SCRATCH_RELPATH = ("build", "guard-debug", "known-good-state")


# ── Documented pins (checklist section 7, current truth) ────────────────────────

_ADVISORY_CODE = "DB_SIGNATURE_UNRESOLVED"
_ADVISORY_COUNT = 20
_INVENTORY_DURABILITY_CODE = "INVENTORY_DURABILITY_UNCONFIRMED"
_DB_SOURCE_ROOT_PREFIX = "DB_SOURCE_ROOT_"
_EXPECTED_INPUT_COUNT = 99
_EXPECTED_RESOLVED = 57
_EXPECTED_UNRESOLVED = 42
_EXPECTED_DUPLICATES = 0
_CANDIDATE_SCHEMA_VERSION = 2
_CANDIDATE_ENTRIES = 472
_STRUCTURAL_ENTRIES = 64
_STRUCTURAL_EXPECTED = 60
_STRUCTURAL_FIXTURES = 4


# ── Per-command timeouts (seconds) ──────────────────────────────────────────────

_GATE_TIMEOUT_SECONDS = 1200
_INVENTORY_TIMEOUT_SECONDS = 600
_MIGRATE_TIMEOUT_SECONDS = 600
_META_TIMEOUT_SECONDS = 300
_VERIFY_TIMEOUT_SECONDS = 600
_FRESHNESS_TIMEOUT_SECONDS = 120

# Platform seam: a confirmable directory durability barrier (CPython exposes
# os.O_DIRECTORY on Unix, not on Windows).  Read at check time so tests can
# monkeypatch the module attribute to exercise both documented branches.
_HAS_DIRECTORY_BARRIER = hasattr(os, "O_DIRECTORY")


# ── Expected strings (the documented contractual expectation per row) ──────────

_EXPECTED_GATE = (
    "gate_exit=0 trusted=true findings=0 "
    f"diagnostics={_ADVISORY_COUNT}x{_ADVISORY_CODE} "
    "active_policy_v2=true v1_archived=true ratchet_v2_empty=true"
)


def _inventory_expected() -> str:
    """Platform-conditional expected string for the inventory-only row."""
    if _HAS_DIRECTORY_BARRIER:
        return "exit=0 trusted=true diagnostics=0 dump=written db_source_root_codes=0"
    return (
        f"exit=2 trusted=false diagnostics=1x{_INVENTORY_DURABILITY_CODE} "
        "dump=written db_source_root_codes=0"
    )


_EXPECTED_MIGRATION = (
    f"exit=1 input={_EXPECTED_INPUT_COUNT} resolved={_EXPECTED_RESOLVED} "
    f"unresolved={_EXPECTED_UNRESOLVED} duplicates={_EXPECTED_DUPLICATES}"
)
_EXPECTED_META = "exit=0 silent"
_EXPECTED_CANDIDATE = (
    f"verify_exit=0 schemaVersion={_CANDIDATE_SCHEMA_VERSION} "
    f"entries={_CANDIDATE_ENTRIES}"
)
_EXPECTED_STRUCTURAL = (
    f"structural_entries={_STRUCTURAL_ENTRIES} "
    f"expected={_STRUCTURAL_EXPECTED} fixtures={_STRUCTURAL_FIXTURES} "
    f"yaml_entries={_STRUCTURAL_ENTRIES}"
)
_EXPECTED_FRESHNESS = "exit=0 verdict=fresh commit_match=true xml_newer=0"

_MIGRATION_SUMMARY_RE = re.compile(
    r"db-policy migration: input=(\d+) resolved=(\d+) unresolved=(\d+) "
    r"duplicateMutationKeys=(\d+) seeds=(\d+)"
)


# ── Value types ─────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class CommandResult:
    """Bounded outcome of one underlying CLI invocation."""

    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False
    crashed: bool = False


@dataclass(frozen=True)
class RowResult:
    """One deterministic scorecard row."""

    row: str
    outcome: str
    expected: str
    observed: str


class _Infrastructure(Exception):
    """Controlled internal signal: the row could not be executed/observed.

    The message is a bounded controlled token (never exception text, paths,
    or tool output) and becomes the row's observed field verbatim.
    """


@dataclass(frozen=True)
class _Context:
    """Everything a row check may touch; injectable for tests."""

    repo_root: Path
    scratch_dir: Path
    run_command: Callable[[Sequence[str], Path, int], CommandResult]


# ── Small helpers ───────────────────────────────────────────────────────────────


def _bool(value: bool) -> str:
    return "true" if value else "false"


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _diag_summary(codes: Sequence[object]) -> str:
    """Bounded deterministic diagnostic summary: 'COUNTxCODE' joined by '+'.

    Codes are controlled constants from the findings protocol, so echoing
    them is safe; counts and code names only, never contexts or paths.
    """
    counts: dict = {}
    for code in codes:
        key = code if isinstance(code, str) else "<nonstring>"
        counts[key] = counts.get(key, 0) + 1
    if not counts:
        return "0"
    return "+".join(f"{count}x{code}" for code, count in sorted(counts.items()))


def _require_yaml() -> None:
    if yaml is None:
        raise _Infrastructure("yaml_unavailable")


def _try_read_yaml(path: Path):
    """Load a YAML document; None on any read/parse failure (drift, not infra)."""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return yaml.safe_load(handle)
    except Exception:
        return None


def _schema_version_is(path: Path, expected: int) -> bool:
    document = _try_read_yaml(path)
    return isinstance(document, dict) and document.get("schemaVersion") == expected


def _yaml_has_nonempty_entries(path: Path) -> bool:
    document = _try_read_yaml(path)
    entries = document.get("entries") if isinstance(document, dict) else None
    return isinstance(entries, list) and len(entries) > 0


def _baseline_entries_empty(path: Path) -> bool:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            document = json.load(handle)
    except Exception:
        return False
    return isinstance(document, dict) and document.get("entries") == []


# ── Freshness child-output contract (scripts/ci/test_result_freshness.py) ───────

# The freshness CLI prints exactly one bounded line per --check run.  Only
# lines matching this strict shape with a controlled verdict token are
# echoed into the scorecard; anything else reduces to the generic
# 'output_unparsed' projection (fail closed, never raw output).
_FRESHNESS_VERDICT_TOKENS = frozenset(
    {
        "fresh",
        "stamp_missing",
        "sha_drift",
        "stamp_expired",
        "xml_newer_than_stamp",
        "malformed_stamp",
        "git_unavailable",
        "results_dir_error",
    }
)
_FRESHNESS_LINE_RE = re.compile(
    r"^verdict=([a-z_]+) commit_match=(true|false) "
    r"xml_count=([0-9]+) xml_newer=([0-9]+)$"
)


def _parse_freshness_line(stdout: str) -> Optional[str]:
    """Return the child's single bounded line if strictly well-formed,
    else None (the caller renders a generic bounded projection)."""
    text = (stdout or "").strip()
    if not text or "\n" in text or "\r" in text:
        return None
    match = _FRESHNESS_LINE_RE.fullmatch(text)
    if match is None or match.group(1) not in _FRESHNESS_VERDICT_TOKENS:
        return None
    return text


# ── Real command runner ─────────────────────────────────────────────────────────


def _run_command(argv: Sequence[str], cwd: Path, timeout: int) -> CommandResult:
    """Run one underlying CLI to completion; never raises outward."""
    try:
        completed = subprocess.run(
            [str(part) for part in argv],
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return CommandResult(exit_code=-1, stdout="", stderr="", timed_out=True)
    except OSError:
        return CommandResult(exit_code=-1, stdout="", stderr="", crashed=True)
    return CommandResult(
        exit_code=completed.returncode,
        stdout=completed.stdout or "",
        stderr=completed.stderr or "",
    )


# ── Row-check decorator ─────────────────────────────────────────────────────────


def _row_check(name: str, expected_provider: Callable[[], str]):
    """Wrap a check returning (outcome, observed) into a total RowResult.

    The wrapped function either returns (OUTCOME_PASS | OUTCOME_FAIL, observed)
    or raises _Infrastructure with a bounded token.  Any unexpected exception
    is fail-closed to INFRA ('check_crashed') -- never a silent pass.
    """

    def decorate(fn):
        @functools.wraps(fn)
        def wrapper(ctx: _Context) -> RowResult:
            try:
                outcome, observed = fn(ctx)
            except _Infrastructure as exc:
                return RowResult(name, OUTCOME_INFRA, expected_provider(), str(exc))
            except Exception:
                return RowResult(name, OUTCOME_INFRA, expected_provider(), "check_crashed")
            return RowResult(name, outcome, expected_provider(), observed)

        return wrapper

    return decorate


# ── Row 1: active DB gate ───────────────────────────────────────────────────────


@_row_check(ROW_ACTIVE_DB_GATE, lambda: _EXPECTED_GATE)
def _check_active_db_gate(ctx: _Context) -> Tuple[str, str]:
    report_path = ctx.scratch_dir / "active-db-gate.findings.json"
    argv = [
        sys.executable, _GATE_SCRIPT,
        "--fail-on-violation",
        "--ownership-policy", _OWNERSHIP_POLICY,
        "--structural-exceptions", _STRUCTURAL_EXCEPTIONS,
        "--structural-manifest", _STRUCTURAL_EXPECTED_METHODS,
        "--raw-query-policy", _RAW_QUERY_POLICY,
        "--findings-output", str(report_path),
    ]
    result = ctx.run_command(argv, ctx.repo_root, _GATE_TIMEOUT_SECONDS)
    if result.timed_out:
        raise _Infrastructure("gate_timeout")
    if result.crashed:
        raise _Infrastructure("gate_spawn_failed")
    if result.exit_code not in (0, 1):
        # Untrusted infrastructure diagnostics (or an unexpected code): the
        # gate's own exit-2 path.  The state cannot be determined -> INFRA.
        raise _Infrastructure(f"gate_exit={result.exit_code}")
    try:
        payload = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise _Infrastructure("gate_report_unreadable")
    statistics = payload.get("statistics") if isinstance(payload, dict) else None
    trusted = isinstance(statistics, dict) and statistics.get("trusted") is True
    findings = payload.get("findings") if isinstance(payload, dict) else None
    findings_count = len(findings) if isinstance(findings, list) else -1
    diagnostics = payload.get("diagnostics") if isinstance(payload, dict) else None
    codes = [
        entry.get("code")
        for entry in (diagnostics or [])
        if isinstance(entry, dict)
    ]
    ok = (
        result.exit_code == 0
        and trusted
        and findings_count == 0
        and codes == [_ADVISORY_CODE] * _ADVISORY_COUNT
    )
    parts = [
        f"gate_exit={result.exit_code}",
        f"trusted={_bool(trusted)}",
        f"findings={findings_count}",
        f"diagnostics={_diag_summary(codes)}",
    ]
    _require_yaml()
    active_v2 = _schema_version_is(ctx.repo_root / _OWNERSHIP_POLICY, 2)
    parts.append(f"active_policy_v2={_bool(active_v2)}")
    ok = ok and active_v2
    archived = _yaml_has_nonempty_entries(ctx.repo_root / _LEGACY_POLICY)
    parts.append(f"v1_archived={_bool(archived)}")
    ok = ok and archived
    empty = _baseline_entries_empty(ctx.repo_root / _RATCHET_BASELINE)
    parts.append(f"ratchet_v2_empty={_bool(empty)}")
    ok = ok and empty
    return (OUTCOME_PASS if ok else OUTCOME_FAIL), " ".join(parts)


# ── Row 2: inventory-only (platform-conditional) ────────────────────────────────


@_row_check(ROW_INVENTORY_ONLY, _inventory_expected)
def _check_inventory_only(ctx: _Context) -> Tuple[str, str]:
    barrier = _HAS_DIRECTORY_BARRIER
    findings_path = ctx.scratch_dir / "inventory-only.findings.json"
    dump_path = ctx.scratch_dir / "inventory-only.mutators.json"
    argv = [
        sys.executable, _GATE_SCRIPT,
        "--inventory-only",
        "--findings-output", str(findings_path),
        "--dump-room-mutators", str(dump_path),
    ]
    result = ctx.run_command(argv, ctx.repo_root, _INVENTORY_TIMEOUT_SECONDS)
    if result.timed_out:
        raise _Infrastructure("inventory_timeout")
    if result.crashed:
        raise _Infrastructure("inventory_spawn_failed")
    try:
        payload = json.loads(findings_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise _Infrastructure("inventory_report_unreadable")
    statistics = payload.get("statistics") if isinstance(payload, dict) else None
    trusted = isinstance(statistics, dict) and statistics.get("trusted") is True
    diagnostics = payload.get("diagnostics") if isinstance(payload, dict) else None
    codes = [
        entry.get("code")
        for entry in (diagnostics or [])
        if isinstance(entry, dict)
    ]
    root_code_count = sum(
        1 for code in codes
        if isinstance(code, str) and code.startswith(_DB_SOURCE_ROOT_PREFIX)
    )
    dump_written = dump_path.is_file()
    if barrier:
        ok = (
            result.exit_code == 0
            and trusted
            and codes == []
            and dump_written
            and root_code_count == 0
        )
    else:
        # Documented Windows durability fallback: the atomic replace precedes
        # the barrier check, so the dump exists while the CLI reports exactly
        # the single controlled INVENTORY_DURABILITY_UNCONFIRMED diagnostic
        # with an untrusted report.
        ok = (
            result.exit_code == 2
            and not trusted
            and codes == [_INVENTORY_DURABILITY_CODE]
            and dump_written
            and root_code_count == 0
        )
    observed = " ".join(
        (
            f"exit={result.exit_code}",
            f"trusted={_bool(trusted)}",
            f"diagnostics={_diag_summary(codes)}",
            f"dump={'written' if dump_written else 'missing'}",
            f"db_source_root_codes={root_code_count}",
        )
    )
    return (OUTCOME_PASS if ok else OUTCOME_FAIL), observed


# ── Row 3: migration fold truth ─────────────────────────────────────────────────


@_row_check(ROW_MIGRATION_FOLD, lambda: _EXPECTED_MIGRATION)
def _check_migration_fold(ctx: _Context) -> Tuple[str, str]:
    argv = [sys.executable, _MIGRATE_SCRIPT, "--check"]
    result = ctx.run_command(argv, ctx.repo_root, _MIGRATE_TIMEOUT_SECONDS)
    if result.timed_out:
        raise _Infrastructure("migrate_timeout")
    if result.crashed:
        raise _Infrastructure("migrate_spawn_failed")
    if result.exit_code == 0:
        # Debt fully resolved: drift from the documented migration-debt state.
        return OUTCOME_FAIL, "exit=0"
    if result.exit_code != 1:
        # The migrate CLI's own infrastructure path (fail closed).
        raise _Infrastructure(f"migrate_exit={result.exit_code}")
    match = _MIGRATION_SUMMARY_RE.search(result.stdout or "")
    if match is None:
        raise _Infrastructure("summary_unparsed")
    input_count, resolved, unresolved, duplicates, _seeds = (
        int(group) for group in match.groups()
    )
    ok = (
        input_count == _EXPECTED_INPUT_COUNT
        and resolved == _EXPECTED_RESOLVED
        and unresolved == _EXPECTED_UNRESOLVED
        and duplicates == _EXPECTED_DUPLICATES
    )
    observed = (
        f"exit=1 input={input_count} resolved={resolved} "
        f"unresolved={unresolved} duplicates={duplicates}"
    )
    return (OUTCOME_PASS if ok else OUTCOME_FAIL), observed


# ── Row 4: source-roots meta-guard ──────────────────────────────────────────────


@_row_check(ROW_META_SOURCE_ROOTS, lambda: _EXPECTED_META)
def _check_meta_source_roots(ctx: _Context) -> Tuple[str, str]:
    argv = [
        sys.executable, _META_SCRIPT,
        "--root", ".",
        "--manifest", _META_MANIFEST,
    ]
    result = ctx.run_command(argv, ctx.repo_root, _META_TIMEOUT_SECONDS)
    if result.timed_out:
        raise _Infrastructure("meta_timeout")
    if result.crashed:
        raise _Infrastructure("meta_spawn_failed")
    if result.exit_code == 0:
        # Section 3 contract: exit 0, silent.
        silent = not (result.stdout or "").strip()
        observed = "exit=0 silent" if silent else "exit=0 stdout_nonempty"
        return (OUTCOME_PASS if silent else OUTCOME_FAIL), observed
    if result.exit_code == 2:
        # The meta-guard's documented diagnostics path: determinable
        # topology drift, not an execution failure of the scorecard.
        # Diagnostic lines are bounded controlled codes; only the count is
        # echoed, never their content.
        diagnostic_lines = sum(
            1 for line in (result.stdout or "").splitlines() if line.strip()
        )
        return OUTCOME_FAIL, f"exit=2 diagnostics={diagnostic_lines}"
    raise _Infrastructure(f"meta_exit={result.exit_code}")


# ── Row 5: candidate byte-reproducibility ───────────────────────────────────────


@_row_check(ROW_CANDIDATE_REPRODUCIBLE, lambda: _EXPECTED_CANDIDATE)
def _check_candidate_reproducible(ctx: _Context) -> Tuple[str, str]:
    argv = [sys.executable, _MIGRATE_SCRIPT, "--verify", "--seed-rows", _SEED_ROWS]
    result = ctx.run_command(argv, ctx.repo_root, _VERIFY_TIMEOUT_SECONDS)
    if result.timed_out:
        raise _Infrastructure("verify_timeout")
    if result.crashed:
        raise _Infrastructure("verify_spawn_failed")
    if result.exit_code == 2:
        raise _Infrastructure("verify_exit=2")
    if result.exit_code != 0:
        # Exit 1 = tracked artifacts drifted from the in-memory regeneration.
        return OUTCOME_FAIL, f"verify_exit={result.exit_code}"
    # Defense in depth: the verify report's match flag, when parseable.
    try:
        parsed = json.loads(result.stdout or "")
    except ValueError:
        parsed = None
    if isinstance(parsed, dict) and parsed.get("match") is not True:
        return OUTCOME_FAIL, "verify_exit=0 match=false"
    _require_yaml()
    document = _try_read_yaml(ctx.repo_root / _CANDIDATE)
    if not isinstance(document, dict):
        return OUTCOME_FAIL, "verify_exit=0 candidate_unreadable"
    schema = document.get("schemaVersion")
    entries = document.get("entries")
    entries_count = len(entries) if isinstance(entries, list) else -1
    ok = (
        schema == _CANDIDATE_SCHEMA_VERSION
        and entries_count == _CANDIDATE_ENTRIES
    )
    observed = f"verify_exit=0 schemaVersion={schema} entries={entries_count}"
    return (OUTCOME_PASS if ok else OUTCOME_FAIL), observed


# ── Row 6: structural manifest pin ──────────────────────────────────────────────


@_row_check(ROW_STRUCTURAL_MANIFEST, lambda: _EXPECTED_STRUCTURAL)
def _check_structural_manifest(ctx: _Context) -> Tuple[str, str]:
    _require_yaml()
    manifest = _try_read_yaml(ctx.repo_root / _STRUCTURAL_EXPECTED_METHODS)
    exceptions = _try_read_yaml(ctx.repo_root / _STRUCTURAL_EXCEPTIONS)
    if not isinstance(manifest, dict) or not isinstance(exceptions, dict):
        return OUTCOME_FAIL, "manifest_unreadable"
    counts = manifest.get("counts")
    counts = counts if isinstance(counts, dict) else {}
    raw_structural = counts.get("structural_entries")
    structural_entries = raw_structural if _is_int(raw_structural) else -1
    # The manifest pins `expected`/`fixtures` as entry LISTS; only their
    # counts are compared and rendered (bounded output: never echo the raw
    # entry payloads into the scorecard).  A non-list shape reduces to the
    # -1 sentinel, which can never match a pinned count (fail closed).
    expected_list = manifest.get("expected")
    fixtures_list = manifest.get("fixtures")
    expected_count = len(expected_list) if isinstance(expected_list, list) else -1
    fixtures_count = len(fixtures_list) if isinstance(fixtures_list, list) else -1
    entries = exceptions.get("entries")
    yaml_entries = len(entries) if isinstance(entries, list) else -1
    ok = (
        structural_entries == _STRUCTURAL_ENTRIES
        and expected_count == _STRUCTURAL_EXPECTED
        and fixtures_count == _STRUCTURAL_FIXTURES
        and yaml_entries == _STRUCTURAL_ENTRIES
        and expected_count + fixtures_count == structural_entries
    )
    observed = (
        f"structural_entries={structural_entries} "
        f"expected={expected_count} fixtures={fixtures_count} "
        f"yaml_entries={yaml_entries}"
    )
    return (OUTCOME_PASS if ok else OUTCOME_FAIL), observed


# ── Row 7 (optional): test-result freshness stamp ───────────────────────────────


@_row_check(ROW_TEST_RESULT_FRESHNESS, lambda: _EXPECTED_FRESHNESS)
def _check_test_result_freshness(ctx: _Context) -> Tuple[str, str]:
    stamp_path = ctx.repo_root.joinpath(
        *_FRESHNESS_RESULTS_RELPATH, _FRESHNESS_STAMP_NAME
    )
    if not stamp_path.is_file():
        # Documented optional-row contract: the stamp workflow was never
        # adopted (no stamp was ever written) -> SKIP, non-blocking.  The
        # row must never block a repo that does not use the stamp workflow.
        return OUTCOME_SKIP, "stamp=missing"
    argv = [
        sys.executable, _FRESHNESS_SCRIPT,
        "--check",
        "--repo-root", str(ctx.repo_root),
    ]
    result = ctx.run_command(argv, ctx.repo_root, _FRESHNESS_TIMEOUT_SECONDS)
    if result.timed_out:
        raise _Infrastructure("freshness_timeout")
    if result.crashed:
        raise _Infrastructure("freshness_spawn_failed")
    parsed = _parse_freshness_line(result.stdout or "")
    if result.exit_code == 0:
        observed = f"exit=0 {parsed}" if parsed else "exit=0 output_unparsed"
        return OUTCOME_PASS, observed
    if result.exit_code == 1:
        if parsed is not None and "verdict=stamp_missing" in parsed:
            # TOCTOU grace: the stamp vanished between the existence probe
            # and the child run -> the documented never-stamped state.
            return OUTCOME_SKIP, "stamp=missing"
        observed = f"exit=1 {parsed}" if parsed else "exit=1 output_unparsed"
        return OUTCOME_FAIL, observed
    # Exit 2 (malformed stamp, git unavailable, scan error) or any other
    # code: the freshness state cannot be determined -> INFRA (fail closed).
    raise _Infrastructure(f"freshness_exit={result.exit_code}")


# ── Scorecard driver ────────────────────────────────────────────────────────────


_ROW_CHECKS: Tuple[Tuple[str, Callable[[], str], Callable[[_Context], RowResult]], ...] = (
    (ROW_ACTIVE_DB_GATE, lambda: _EXPECTED_GATE, _check_active_db_gate),
    (ROW_INVENTORY_ONLY, _inventory_expected, _check_inventory_only),
    (ROW_MIGRATION_FOLD, lambda: _EXPECTED_MIGRATION, _check_migration_fold),
    (ROW_META_SOURCE_ROOTS, lambda: _EXPECTED_META, _check_meta_source_roots),
    (ROW_CANDIDATE_REPRODUCIBLE, lambda: _EXPECTED_CANDIDATE, _check_candidate_reproducible),
    (ROW_STRUCTURAL_MANIFEST, lambda: _EXPECTED_STRUCTURAL, _check_structural_manifest),
    (ROW_TEST_RESULT_FRESHNESS, lambda: _EXPECTED_FRESHNESS, _check_test_result_freshness),
)


def _execute_checks(ctx: _Context) -> Tuple[RowResult, ...]:
    """Run every row in fixed order; a row never aborts the others."""
    rows: list = []
    for name, expected_provider, fn in _ROW_CHECKS:
        try:
            rows.append(fn(ctx))
        except Exception:
            # Belt-and-braces fail-closed net (the decorator already catches);
            # bounded observed, never exception text.
            rows.append(RowResult(name, OUTCOME_INFRA, expected_provider(), "check_crashed"))
    return tuple(rows)


def _scorecard_exit(rows: Sequence[RowResult]) -> int:
    """0 all pass (SKIP rows are documented non-blocking states and are
    ignored); 1 any FAIL; 2 any INFRA (infrastructure takes precedence)."""
    if any(row.outcome == OUTCOME_INFRA for row in rows):
        return 2
    if any(row.outcome == OUTCOME_FAIL for row in rows):
        return 1
    return 0


def _default_repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def run_scorecard(
    repo_root: Optional[Path] = None,
    scratch_dir: Optional[Path] = None,
    run_command: Optional[Callable[[Sequence[str], Path, int], CommandResult]] = None,
) -> Tuple[Tuple[RowResult, ...], int]:
    """Run all seven rows (six pinned + the optional freshness row) and
    return (rows, exit_code).

    ``run_command`` is injectable for tests; the default shells out to the
    real CLIs under ``repo_root``.  Scratch outputs (gate/inventory findings
    reports, mutators dump) land under ``scratch_dir`` and never influence
    the deterministic scorecard text.
    """
    root = Path(repo_root) if repo_root is not None else _default_repo_root()
    scratch = (
        Path(scratch_dir)
        if scratch_dir is not None
        else root.joinpath(*_DEFAULT_SCRATCH_RELPATH)
    )
    scratch.mkdir(parents=True, exist_ok=True)
    ctx = _Context(
        repo_root=root,
        scratch_dir=scratch,
        run_command=run_command or _run_command,
    )
    rows = _execute_checks(ctx)
    return rows, _scorecard_exit(rows)


# ── ASCII-safe rendering (Windows cp1252 stdout crash guard) ────────────────────

# Decorative glyphs map to fixed ASCII equivalents; any other non-ASCII
# character (never expected -- the scorecard vocabulary is ASCII) reduces to
# '?' deterministically.  Identity on already-ASCII text, so the documented
# all-pass scorecard bytes are unchanged.
_ASCII_GLYPH_MAP = {
    "\u2192": "->",      # rightwards arrow
    "\u2190": "<-",      # leftwards arrow
    "\u23F1": "[slow]",  # stopwatch
    "\u2713": "PASS",    # check mark
    "\u2717": "FAIL",    # ballot X
    "\u2714": "PASS",    # heavy check mark
    "\u2718": "FAIL",    # heavy ballot X
}


def _ascii_safe(text: str) -> str:
    """Restrict text to pure ASCII so cp1252-redirected stdout cannot crash.

    Applied at the render choke point so every emitted field -- including
    echoed external diagnostic codes -- is covered.  Deterministic: a pure
    function of the input, never raising.
    """
    if all(ord(char) < 128 for char in text):
        return text
    return "".join(
        _ASCII_GLYPH_MAP.get(char, "?" if ord(char) >= 128 else char)
        for char in text
    )


def render_scorecard(rows: Sequence[RowResult], exit_code: int) -> str:
    """Deterministic scorecard text: fixed order, no timestamps, no paths;
    pure ASCII (safe for a cp1252-redirected Windows stdout)."""
    lines = [
        "KNOWN-GOOD STATE SCORECARD "
        "(docs/ci/GR00-GR04_validation_checklist.md section 7, "
        "PR-GR-10e/10f)",
    ]
    for index, row in enumerate(rows, start=1):
        lines.append(f"[{index}/{len(rows)}] row={row.row} result={row.outcome}")
        lines.append(f"  expected: {row.expected}")
        lines.append(f"  observed: {row.observed}")
    passed = sum(1 for row in rows if row.outcome == OUTCOME_PASS)
    failed = sum(1 for row in rows if row.outcome == OUTCOME_FAIL)
    infra = sum(1 for row in rows if row.outcome == OUTCOME_INFRA)
    skipped = sum(1 for row in rows if row.outcome == OUTCOME_SKIP)
    lines.append(
        f"summary: rows={len(rows)} pass={passed} fail={failed} "
        f"infra={infra} skip={skipped} exit={exit_code}"
    )
    return _ascii_safe("\n".join(lines) + "\n")


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser(
        description="Executable known-good state scorecard (PR-GR-10e/10f).",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Repository root (default: two levels up from this script).",
    )
    parser.add_argument(
        "--scratch-dir",
        default=None,
        help=(
            "Scratch directory for gate/inventory reports "
            "(default: <repo>/build/guard-debug/known-good-state)."
        ),
    )
    args = parser.parse_args(argv)
    repo_root = (
        Path(args.repo_root).resolve() if args.repo_root else _default_repo_root()
    )
    try:
        rows, exit_code = run_scorecard(
            repo_root=repo_root, scratch_dir=args.scratch_dir
        )
    except OSError:
        print("known-good-state scorecard: scratch directory unavailable",
              file=sys.stderr)
        sys.exit(2)
    sys.stdout.write(render_scorecard(rows, exit_code))
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
