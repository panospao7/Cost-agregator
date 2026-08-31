#!/usr/bin/env python3
"""
test_result_freshness.py -- PR-GR-10f stale-result guard (freshness contract).

Background (evidence): validation round R12 mis-read STALE round-11 JUnit
test-result XMLs under app/build/test-results as fresh failures -- a wasted
round.  Gradle's JUnit XML does not record the commit the tests ran against,
so freshness cannot be derived from the XMLs themselves.  This script
implements a two-end freshness contract instead of a guard over Gradle
internals:

  write end (--write)
      Records a stamp at <results-dir>/.freshness-stamp.json
      {schemaVersion, commitSha, treeSha, completedAtUtc, suiteName},
      written ATOMICALLY (temp file in the same directory, fsync, then
      os.replace).  Invoked by the orchestrator right after a test run
      (documented in docs/ci/GR00-GR04_validation_checklist.md section 2).

  check end (--check)
      Verifies, BEFORE any test-result XML is consumed:
        1. the stamp exists;
        2. the stamp's commitSha matches the reference commit
           (default: ``git rev-parse HEAD`` at --repo-root; overridable
           via --commit-sha);
        3. the stamp is not older than --max-age-hours (default 24);
        4. no *.xml file anywhere under the results directory has an mtime
           newer than the stamp file's own mtime (a small filesystem-
           granularity tolerance applies) -- i.e. no post-stamp test run
           silently replaced the results.
      First failing check wins; later checks are not run.

Exit codes (--check):
  0 -- fresh: stamp exists, SHA matches, stamp within max age, no XML
      newer than the stamp.  The results may be consumed.
  1 -- determinable NOT-fresh (stale-mismatch): stamp missing (including a
      missing results directory), SHA drift, stamp expired, or an XML
      newer than the stamp.  The results must NOT be consumed as fresh.
  2 -- infrastructure, fail closed: malformed/unsupported stamp (bad JSON,
      wrong schemaVersion, missing/invalid fields, unparseable timestamp,
      unreadable stamp file), git unavailable, or an unexpected OS error
      while scanning the results directory.

Exit codes (--write):
  0 -- stamp written.
  2 -- infrastructure: git unavailable (and no --commit-sha/--tree-sha
      overrides given), an invalid SHA override, or the stamp path is
      unwritable.

Output (deterministic, bounded, pure ASCII; exactly one stdout line):
  --check prints:
      verdict=<token> commit_match=<true|false> xml_count=<int> xml_newer=<int>
  with <token> one of the controlled constants: fresh, stamp_missing,
  sha_drift, stamp_expired, xml_newer_than_stamp, malformed_stamp,
  git_unavailable, results_dir_error.  xml_count/xml_newer are 0 unless the
  XML scan actually ran (verdicts fresh / xml_newer_than_stamp).
  --write prints:
      written=<true|false> [reason=<token>] commit_sha=<sha|none> tree_sha=<sha|none>

  Never prints filesystem paths, exception text, stack traces, or raw tool
  output (privacy posture: counts, booleans, controlled constants only).

Determinism: the stamp bytes are a pure function of the inputs (sorted keys,
fixed indent, second-granularity UTC timestamp); the --check line is a pure
function of (stamp contents, reference SHA, stamp/XML mtimes, max age, now).

Usage:
  python scripts/ci/test_result_freshness.py --write --suite-name testDebugUnitTest
  python scripts/ci/test_result_freshness.py --check
  python scripts/ci/test_result_freshness.py --check --max-age-hours 48
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional, Sequence, Tuple

# ── Controlled constants ────────────────────────────────────────────────────────

SCHEMA_VERSION = 1
STAMP_NAME = ".freshness-stamp.json"
DEFAULT_RESULTS_DIR = "app/build/test-results"
DEFAULT_MAX_AGE_HOURS = 24.0

# Filesystem mtime granularity slack: an XML whose mtime exceeds the stamp
# file's mtime by more than this is treated as newer (post-stamp test run).
# A real post-stamp rerun writes XMLs seconds-to-minutes after the stamp, so
# one second of slack never masks the R12 failure mode while avoiding false
# positives on coarse-granularity filesystems.
MTIME_TOLERANCE_SECONDS = 1.0

_GIT_TIMEOUT_SECONDS = 60
_SHA40_RE = re.compile(r"^[0-9a-f]{40}$")
_STAMP_TIME_FORMAT = "%Y-%m-%dT%H:%M:%SZ"

VERDICT_FRESH = "fresh"
VERDICT_STAMP_MISSING = "stamp_missing"
VERDICT_SHA_DRIFT = "sha_drift"
VERDICT_STAMP_EXPIRED = "stamp_expired"
VERDICT_XML_NEWER = "xml_newer_than_stamp"
VERDICT_MALFORMED = "malformed_stamp"
VERDICT_GIT_UNAVAILABLE = "git_unavailable"
VERDICT_RESULTS_DIR_ERROR = "results_dir_error"

VERDICT_TOKENS = frozenset(
    {
        VERDICT_FRESH,
        VERDICT_STAMP_MISSING,
        VERDICT_SHA_DRIFT,
        VERDICT_STAMP_EXPIRED,
        VERDICT_XML_NEWER,
        VERDICT_MALFORMED,
        VERDICT_GIT_UNAVAILABLE,
        VERDICT_RESULTS_DIR_ERROR,
    }
)

EXIT_FRESH = 0
EXIT_STALE = 1
EXIT_INFRA = 2


# ── Small helpers ───────────────────────────────────────────────────────────────


def _default_repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _utc_now() -> datetime:
    """Current UTC time (seam for deterministic tests)."""
    return datetime.now(timezone.utc)


def _git_rev_parse(repo_root: Path, ref: str) -> Optional[str]:
    """Resolve a git revision to a 40-hex SHA; None on any failure.

    Never raises; never echoes git stderr (privacy posture).
    """
    try:
        completed = subprocess.run(
            ["git", "rev-parse", ref],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=_GIT_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if completed.returncode != 0:
        return None
    value = (completed.stdout or "").strip()
    if not _SHA40_RE.fullmatch(value):
        return None
    return value


def _atomic_write_json(path: Path, payload: dict) -> None:
    """Write JSON bytes atomically: temp file in the target directory,
    flush + fsync, then os.replace.  Raises OSError outward."""
    directory = path.parent
    directory.mkdir(parents=True, exist_ok=True)
    handle_fd, temp_name = tempfile.mkstemp(
        dir=str(directory), prefix=".freshness-stamp.", suffix=".tmp"
    )
    temp_path = Path(temp_name)
    try:
        with os.fdopen(handle_fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
    except BaseException:
        try:
            temp_path.unlink()
        except OSError:
            pass
        raise


# ── Write end ───────────────────────────────────────────────────────────────────


def write_stamp(
    results_dir: Path,
    commit_sha: str,
    tree_sha: str,
    suite_name: str = "",
    now: Optional[datetime] = None,
) -> Tuple[int, str]:
    """Write the freshness stamp atomically; returns (exit_code, output_line).

    Pure filesystem work: both SHAs must already be resolved and validated
    by the caller (the CLI resolves them from git or --commit-sha/--tree-sha
    overrides).  ``now`` is injectable for deterministic tests.
    """
    sha_suffix = f"commit_sha={commit_sha} tree_sha={tree_sha}"
    if not _SHA40_RE.fullmatch(commit_sha) or not _SHA40_RE.fullmatch(tree_sha):
        return EXIT_INFRA, f"written=false reason=invalid_sha {sha_suffix}"
    completed_at = (now if now is not None else _utc_now()).strftime(
        _STAMP_TIME_FORMAT
    )
    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "commitSha": commit_sha,
        "treeSha": tree_sha,
        "completedAtUtc": completed_at,
        "suiteName": suite_name,
    }
    stamp_path = results_dir / STAMP_NAME
    try:
        _atomic_write_json(stamp_path, payload)
    except OSError:
        return (
            EXIT_INFRA,
            f"written=false reason=stamp_unwritable {sha_suffix}",
        )
    return EXIT_FRESH, f"written=true {sha_suffix}"


# ── Check end ───────────────────────────────────────────────────────────────────


def _load_stamp(stamp_path: Path) -> Optional[dict]:
    """Load and fully validate the stamp; None on any shape violation.

    A stamp that exists but cannot be read/parsed/validated is infrastructure
    (the caller maps None to malformed_stamp / exit 2, fail closed).
    """
    try:
        raw = stamp_path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError, ValueError):
        return None
    try:
        payload = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(payload, dict):
        return None
    schema = payload.get("schemaVersion")
    if isinstance(schema, bool) or schema != SCHEMA_VERSION:
        return None
    commit_sha = payload.get("commitSha")
    tree_sha = payload.get("treeSha")
    completed_raw = payload.get("completedAtUtc")
    suite_name = payload.get("suiteName")
    if not isinstance(commit_sha, str) or not _SHA40_RE.fullmatch(commit_sha):
        return None
    if not isinstance(tree_sha, str) or not _SHA40_RE.fullmatch(tree_sha):
        return None
    if not isinstance(suite_name, str):
        return None
    if not isinstance(completed_raw, str):
        return None
    try:
        completed = datetime.strptime(completed_raw, _STAMP_TIME_FORMAT)
    except ValueError:
        return None
    return {
        "commitSha": commit_sha,
        "treeSha": tree_sha,
        "completedAtUtc": completed.replace(tzinfo=timezone.utc),
        "suiteName": suite_name,
    }


def _count_xml_newer_than(results_dir: Path, stamp_path: Path) -> Tuple[int, int]:
    """Count *.xml files under results_dir and how many are newer than the
    stamp file's own mtime (with the documented tolerance).  Raises OSError
    outward (mapped to results_dir_error / exit 2 by the caller)."""
    stamp_mtime = stamp_path.stat().st_mtime
    xml_count = 0
    xml_newer = 0
    for path in results_dir.rglob("*.xml"):
        xml_count += 1
        if path.stat().st_mtime > stamp_mtime + MTIME_TOLERANCE_SECONDS:
            xml_newer += 1
    return xml_newer, xml_count


def check_freshness(
    results_dir: Path,
    commit_sha: str,
    max_age_hours: float = DEFAULT_MAX_AGE_HOURS,
    now: Optional[datetime] = None,
) -> Tuple[int, str]:
    """Verify stamp freshness; returns (exit_code, output_line).

    Pure filesystem/JSON work: the reference ``commit_sha`` must already be
    resolved by the caller (the CLI resolves it from git or --commit-sha).
    ``now`` is injectable for deterministic tests.  First failing check
    wins; later checks are not run.
    """
    stamp_path = results_dir / STAMP_NAME
    if not results_dir.is_dir() or not stamp_path.is_file():
        return (
            EXIT_STALE,
            f"verdict={VERDICT_STAMP_MISSING} commit_match=false "
            "xml_count=0 xml_newer=0",
        )
    stamp = _load_stamp(stamp_path)
    if stamp is None:
        return (
            EXIT_INFRA,
            f"verdict={VERDICT_MALFORMED} commit_match=false "
            "xml_count=0 xml_newer=0",
        )
    commit_match = stamp["commitSha"] == commit_sha
    if not commit_match:
        return (
            EXIT_STALE,
            f"verdict={VERDICT_SHA_DRIFT} commit_match=false "
            "xml_count=0 xml_newer=0",
        )
    reference_now = now if now is not None else _utc_now()
    age = reference_now - stamp["completedAtUtc"]
    if age > timedelta(hours=max_age_hours):
        return (
            EXIT_STALE,
            f"verdict={VERDICT_STAMP_EXPIRED} commit_match=true "
            "xml_count=0 xml_newer=0",
        )
    try:
        xml_newer, xml_count = _count_xml_newer_than(results_dir, stamp_path)
    except OSError:
        return (
            EXIT_INFRA,
            f"verdict={VERDICT_RESULTS_DIR_ERROR} commit_match=true "
            "xml_count=0 xml_newer=0",
        )
    if xml_newer > 0:
        return (
            EXIT_STALE,
            f"verdict={VERDICT_XML_NEWER} commit_match=true "
            f"xml_count={xml_count} xml_newer={xml_newer}",
        )
    return (
        EXIT_FRESH,
        f"verdict={VERDICT_FRESH} commit_match=true "
        f"xml_count={xml_count} xml_newer=0",
    )


# ── CLI ─────────────────────────────────────────────────────────────────────────


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser(
        description=(
            "PR-GR-10f test-result freshness stamp: --write records the "
            "stamp after a test run; --check verifies it before any "
            "test-result XML is consumed."
        ),
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--write",
        action="store_true",
        help="Write the freshness stamp (orchestrator-side, after a test run).",
    )
    mode.add_argument(
        "--check",
        action="store_true",
        help="Verify stamp freshness (before consuming test-result XMLs).",
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Repository root (default: two levels up from this script).",
    )
    parser.add_argument(
        "--results-dir",
        default=DEFAULT_RESULTS_DIR,
        help=(
            "Test-results directory (default: app/build/test-results; "
            "relative paths resolve against --repo-root)."
        ),
    )
    parser.add_argument(
        "--commit-sha",
        default=None,
        help="Override the reference commit SHA (40-hex; default: git rev-parse HEAD).",
    )
    parser.add_argument(
        "--tree-sha",
        default=None,
        help="Override the recorded tree SHA (40-hex; default: git rev-parse HEAD^{tree}).",
    )
    parser.add_argument(
        "--suite-name",
        default="",
        help="Optional suite label recorded in the stamp (write mode).",
    )
    parser.add_argument(
        "--max-age-hours",
        type=float,
        default=DEFAULT_MAX_AGE_HOURS,
        help="Maximum stamp age in hours (check mode; default 24).",
    )
    args = parser.parse_args(argv)

    if args.commit_sha is not None and not _SHA40_RE.fullmatch(args.commit_sha):
        parser.error("--commit-sha must be a 40-hex lowercase git SHA")
    if args.tree_sha is not None and not _SHA40_RE.fullmatch(args.tree_sha):
        parser.error("--tree-sha must be a 40-hex lowercase git SHA")
    if args.max_age_hours <= 0:
        parser.error("--max-age-hours must be positive")

    repo_root = (
        Path(args.repo_root).resolve() if args.repo_root else _default_repo_root()
    )
    results_dir = Path(args.results_dir)
    if not results_dir.is_absolute():
        results_dir = repo_root / results_dir

    if args.write:
        head = args.commit_sha or _git_rev_parse(repo_root, "HEAD")
        tree = args.tree_sha or _git_rev_parse(repo_root, "HEAD^{tree}")
        if head is None or tree is None:
            print(
                "written=false reason=git_unavailable commit_sha=none "
                "tree_sha=none"
            )
            sys.exit(EXIT_INFRA)
        exit_code, line = write_stamp(
            results_dir, head, tree, suite_name=args.suite_name
        )
        print(line)
        sys.exit(exit_code)

    # --check
    head = args.commit_sha or _git_rev_parse(repo_root, "HEAD")
    if head is None:
        print(
            f"verdict={VERDICT_GIT_UNAVAILABLE} commit_match=false "
            "xml_count=0 xml_newer=0"
        )
        sys.exit(EXIT_INFRA)
    exit_code, line = check_freshness(
        results_dir, head, max_age_hours=args.max_age_hours
    )
    print(line)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
