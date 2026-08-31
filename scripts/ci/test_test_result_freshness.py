#!/usr/bin/env python3
"""
test_test_result_freshness.py -- tests for the PR-GR-10f freshness contract.

Covers:
  * the stamp write/read round-trip (schema fields, deterministic bytes);
  * the --check verdict matrix: fresh (exit 0), stamp missing (1), SHA
    drift (1), XML newer than the stamp (1), max-age expiry (1, with the
    exact-24h boundary fresh), malformed stamp (2), git unavailable (2),
    results-dir scan error (2);
  * the mtime tolerance boundary (post-stamp rerun detection vs
    filesystem-granularity slack);
  * atomic write behavior (no temp leftovers, latest stamp wins);
  * deterministic single-line output (byte-identical across runs) that is
    pure ASCII and strictly formatted (the scorecard parses it);
  * CLI behavior: mode exclusivity, SHA/max-age validation, --commit-sha
    override avoiding git, git-unavailable fail-closed paths.

All tests are hermetic: git is monkeypatched (never spawned) and time is
injected; no real app/build directory is touched.

Run:
  python -m pytest scripts/ci/test_test_result_freshness.py -v
"""

import importlib.util
import json
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

_SCRIPT_DIR = Path(__file__).resolve().parent


def _load_module(name: str, relpath: str):
    spec = importlib.util.spec_from_file_location(name, _SCRIPT_DIR / relpath)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


trf = _load_module("test_result_freshness", "test_result_freshness.py")

SHA_A = "a" * 40
SHA_B = "b" * 40
TREE_A = "c" * 40
TREE_B = "d" * 40

FIXED_NOW = datetime(2026, 8, 31, 12, 0, 0, tzinfo=timezone.utc)

CHECK_LINE_RE = re.compile(
    r"^verdict=([a-z_]+) commit_match=(true|false) "
    r"xml_count=([0-9]+) xml_newer=([0-9]+)$"
)
WRITE_LINE_RE = re.compile(
    r"^written=(true|false)( reason=[a-z_]+)? "
    r"commit_sha=([0-9a-f]{40}|none) tree_sha=([0-9a-f]{40}|none)$"
)


# ── Fixtures / helpers ──────────────────────────────────────────────────────────


@pytest.fixture()
def results_env(tmp_path):
    """Isolated repo/results fixture; returns (repo, results) under tmp_path."""
    repo = tmp_path / "repo"
    repo.mkdir()
    results = repo / "app" / "build" / "test-results"
    return repo, results


def _write_stamp(
    results: Path,
    commit_sha: str = SHA_A,
    tree_sha: str = TREE_A,
    suite_name: str = "testDebugUnitTest",
    now: datetime = FIXED_NOW,
):
    return trf.write_stamp(
        results, commit_sha, tree_sha, suite_name=suite_name, now=now
    )


def _read_stamp(results: Path) -> dict:
    return json.loads(
        (results / trf.STAMP_NAME).read_text(encoding="utf-8")
    )


def _write_xml(results: Path, name: str = "TEST-com.example.xml") -> Path:
    results.mkdir(parents=True, exist_ok=True)
    path = results / name
    path.write_text("<testsuite tests='1'></testsuite>", encoding="utf-8")
    return path


def _set_mtime(path: Path, when: float) -> None:
    os.utime(path, (when, when))


def _stamp_mtime(results: Path) -> float:
    return (results / trf.STAMP_NAME).stat().st_mtime


def _assert_check_shape(line: str) -> re.Match:
    match = CHECK_LINE_RE.fullmatch(line)
    assert match is not None, line
    assert match.group(1) in trf.VERDICT_TOKENS, line
    return match


def _assert_ascii(text: str) -> None:
    assert all(ord(char) < 128 for char in text), text


# ── Write/read round-trip ───────────────────────────────────────────────────────


class TestStampRoundTrip:
    def test_write_then_read_all_schema_fields(self, results_env):
        _repo, results = results_env
        exit_code, line = _write_stamp(results, suite_name="testDebugUnitTest")
        assert exit_code == 0
        assert line == f"written=true commit_sha={SHA_A} tree_sha={TREE_A}"
        stamp = _read_stamp(results)
        assert stamp == {
            "schemaVersion": 1,
            "commitSha": SHA_A,
            "treeSha": TREE_A,
            "completedAtUtc": "2026-08-31T12:00:00Z",
            "suiteName": "testDebugUnitTest",
        }

    def test_stamp_lands_at_documented_path_and_name(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        assert (results / ".freshness-stamp.json").is_file()

    def test_round_trip_check_is_fresh(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        exit_code, line = trf.check_freshness(
            results, SHA_A, now=FIXED_NOW + timedelta(minutes=5)
        )
        assert exit_code == 0
        match = _assert_check_shape(line)
        assert match.group(1) == "fresh"
        assert match.group(2) == "true"
        assert match.group(3) == "0"
        assert match.group(4) == "0"

    def test_stamp_bytes_are_deterministic(self, results_env, tmp_path):
        _repo, results = results_env
        _write_stamp(results)
        other = tmp_path / "other-results"
        _write_stamp(other)
        first = (results / trf.STAMP_NAME).read_bytes()
        second = (other / trf.STAMP_NAME).read_bytes()
        assert first == second

    def test_latest_stamp_wins_on_overwrite(self, results_env):
        _repo, results = results_env
        _write_stamp(results, commit_sha=SHA_A, tree_sha=TREE_A)
        _write_stamp(
            results,
            commit_sha=SHA_B,
            tree_sha=TREE_B,
            now=FIXED_NOW + timedelta(hours=1),
        )
        stamp = _read_stamp(results)
        assert stamp["commitSha"] == SHA_B
        assert stamp["treeSha"] == TREE_B
        assert stamp["completedAtUtc"] == "2026-08-31T13:00:00Z"

    def test_atomic_write_leaves_no_temp_files(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        _write_stamp(results)  # overwrite path also must clean up
        leftovers = [
            path
            for path in results.iterdir()
            if path.name != trf.STAMP_NAME
        ]
        assert leftovers == []

    def test_write_rejects_non_sha40_input(self, results_env):
        _repo, results = results_env
        exit_code, line = trf.write_stamp(results, "nothex", TREE_A)
        assert exit_code == 2
        assert "reason=invalid_sha" in line
        assert not (results / trf.STAMP_NAME).exists()

    def test_write_unwritable_stamp_path_is_infra(self, tmp_path):
        blocker = tmp_path / "blocker"
        blocker.write_text("not a directory", encoding="utf-8")
        exit_code, line = trf.write_stamp(
            blocker / "sub" / "dir", SHA_A, TREE_A, now=FIXED_NOW
        )
        assert exit_code == 2
        assert "reason=stamp_unwritable" in line


# ── Check verdict matrix ────────────────────────────────────────────────────────


class TestCheckVerdicts:
    def test_missing_stamp_exit_one(self, results_env):
        _repo, results = results_env
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 1
        match = _assert_check_shape(line)
        assert match.group(1) == "stamp_missing"
        assert match.group(2) == "false"

    def test_missing_results_dir_exit_one(self, tmp_path):
        exit_code, line = trf.check_freshness(
            tmp_path / "does-not-exist", SHA_A, now=FIXED_NOW
        )
        assert exit_code == 1
        assert "verdict=stamp_missing" in line

    def test_sha_drift_exit_one(self, results_env):
        _repo, results = results_env
        _write_stamp(results, commit_sha=SHA_A)
        exit_code, line = trf.check_freshness(results, SHA_B, now=FIXED_NOW)
        assert exit_code == 1
        match = _assert_check_shape(line)
        assert match.group(1) == "sha_drift"
        assert match.group(2) == "false"
        assert match.group(3) == "0"
        assert match.group(4) == "0"

    def test_xml_newer_than_stamp_exit_one(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        xml = _write_xml(results)
        _set_mtime(xml, _stamp_mtime(results) + 60.0)
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 1
        match = _assert_check_shape(line)
        assert match.group(1) == "xml_newer_than_stamp"
        assert match.group(2) == "true"
        assert match.group(3) == "1"
        assert match.group(4) == "1"

    def test_xml_older_than_stamp_is_fresh(self, results_env):
        _repo, results = results_env
        xml = _write_xml(results)
        _write_stamp(results)
        _set_mtime(xml, _stamp_mtime(results) - 60.0)
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 0
        match = _assert_check_shape(line)
        assert match.group(1) == "fresh"
        assert match.group(3) == "1"
        assert match.group(4) == "0"

    def test_xml_scan_is_recursive(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        nested = results / "testDebugUnitTest"
        nested.mkdir(parents=True)
        xml = nested / "TEST-nested.xml"
        xml.write_text("<testsuite/>", encoding="utf-8")
        _set_mtime(xml, _stamp_mtime(results) + 60.0)
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 1
        match = _assert_check_shape(line)
        assert match.group(3) == "1"
        assert match.group(4) == "1"

    def test_mtime_tolerance_boundary(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        stamp_mtime = _stamp_mtime(results)
        within = _write_xml(results, "TEST-within.xml")
        _set_mtime(within, stamp_mtime + (trf.MTIME_TOLERANCE_SECONDS - 0.1))
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 0
        assert "verdict=fresh" in line
        beyond = _write_xml(results, "TEST-beyond.xml")
        _set_mtime(beyond, stamp_mtime + (trf.MTIME_TOLERANCE_SECONDS + 5.0))
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 1
        assert "verdict=xml_newer_than_stamp" in line

    def test_empty_results_dir_with_stamp_is_fresh(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 0
        assert "xml_count=0" in line

    def test_max_age_expiry_exit_one(self, results_env):
        _repo, results = results_env
        _write_stamp(results, now=FIXED_NOW - timedelta(hours=25))
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 1
        match = _assert_check_shape(line)
        assert match.group(1) == "stamp_expired"
        assert match.group(2) == "true"

    def test_max_age_boundary_exactly_24h_is_fresh(self, results_env):
        _repo, results = results_env
        _write_stamp(results, now=FIXED_NOW - timedelta(hours=24))
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 0
        assert "verdict=fresh" in line

    def test_max_age_boundary_one_second_past_is_expired(self, results_env):
        _repo, results = results_env
        _write_stamp(
            results, now=FIXED_NOW - timedelta(hours=24, seconds=1)
        )
        exit_code, line = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert exit_code == 1
        assert "verdict=stamp_expired" in line

    def test_custom_max_age_hours_respected(self, results_env):
        _repo, results = results_env
        _write_stamp(results, now=FIXED_NOW - timedelta(hours=30))
        exit_code, line = trf.check_freshness(
            results, SHA_A, max_age_hours=48.0, now=FIXED_NOW
        )
        assert exit_code == 0
        assert "verdict=fresh" in line

    def test_sha_drift_wins_over_expiry(self, results_env):
        _repo, results = results_env
        _write_stamp(
            results, commit_sha=SHA_A, now=FIXED_NOW - timedelta(hours=48)
        )
        exit_code, line = trf.check_freshness(results, SHA_B, now=FIXED_NOW)
        assert exit_code == 1
        assert "verdict=sha_drift" in line


# ── Malformed stamp -> infrastructure (exit 2) ──────────────────────────────────


class TestMalformedStamp:
    def _check(self, results: Path, raw: str):
        results.mkdir(parents=True, exist_ok=True)
        (results / trf.STAMP_NAME).write_text(raw, encoding="utf-8")
        return trf.check_freshness(results, SHA_A, now=FIXED_NOW)

    def test_garbage_text_exit_two(self, results_env):
        _repo, results = results_env
        exit_code, line = self._check(results, "not json at all")
        assert exit_code == 2
        match = _assert_check_shape(line)
        assert match.group(1) == "malformed_stamp"

    def test_json_array_exit_two(self, results_env):
        _repo, results = results_env
        exit_code, line = self._check(results, "[]")
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line

    def test_wrong_schema_version_exit_two(self, results_env):
        _repo, results = results_env
        stamp = _read_stamp_after_write(results)
        stamp["schemaVersion"] = 2
        exit_code, line = self._check(results, json.dumps(stamp))
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line

    def test_boolean_schema_version_exit_two(self, results_env):
        _repo, results = results_env
        # True == 1 in Python; the loader must not accept it as schemaVersion 1.
        exit_code, line = self._check(
            results,
            json.dumps(
                {
                    "schemaVersion": True,
                    "commitSha": SHA_A,
                    "treeSha": TREE_A,
                    "completedAtUtc": "2026-08-31T12:00:00Z",
                    "suiteName": "",
                }
            ),
        )
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line

    def test_missing_commit_field_exit_two(self, results_env):
        _repo, results = results_env
        exit_code, line = self._check(
            results,
            json.dumps(
                {
                    "schemaVersion": 1,
                    "treeSha": TREE_A,
                    "completedAtUtc": "2026-08-31T12:00:00Z",
                    "suiteName": "",
                }
            ),
        )
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line

    def test_bad_sha_format_exit_two(self, results_env):
        _repo, results = results_env
        exit_code, line = self._check(
            results,
            json.dumps(
                {
                    "schemaVersion": 1,
                    "commitSha": "zz" * 20,
                    "treeSha": TREE_A,
                    "completedAtUtc": "2026-08-31T12:00:00Z",
                    "suiteName": "",
                }
            ),
        )
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line

    def test_unparseable_timestamp_exit_two(self, results_env):
        _repo, results = results_env
        exit_code, line = self._check(
            results,
            json.dumps(
                {
                    "schemaVersion": 1,
                    "commitSha": SHA_A,
                    "treeSha": TREE_A,
                    "completedAtUtc": "yesterday-ish",
                    "suiteName": "",
                }
            ),
        )
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line

    def test_non_string_suite_name_exit_two(self, results_env):
        _repo, results = results_env
        exit_code, line = self._check(
            results,
            json.dumps(
                {
                    "schemaVersion": 1,
                    "commitSha": SHA_A,
                    "treeSha": TREE_A,
                    "completedAtUtc": "2026-08-31T12:00:00Z",
                    "suiteName": 7,
                }
            ),
        )
        assert exit_code == 2
        assert "verdict=malformed_stamp" in line


def _read_stamp_after_write(results: Path) -> dict:
    trf.write_stamp(results, SHA_A, TREE_A, now=FIXED_NOW)
    return json.loads(
        (results / trf.STAMP_NAME).read_text(encoding="utf-8")
    )


# ── Deterministic, ASCII-safe, strictly formatted output ────────────────────────


class TestOutputContract:
    def test_check_line_identical_across_runs(self, results_env):
        _repo, results = results_env
        _write_stamp(results)
        first = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        second = trf.check_freshness(results, SHA_A, now=FIXED_NOW)
        assert first == second

    def test_every_verdict_line_is_pure_ascii(self, results_env):
        _repo, results = results_env
        scenarios = [
            trf.check_freshness(results, SHA_A, now=FIXED_NOW),  # missing
        ]
        _write_stamp(results)
        scenarios.append(trf.check_freshness(results, SHA_A, now=FIXED_NOW))
        scenarios.append(trf.check_freshness(results, SHA_B, now=FIXED_NOW))
        scenarios.append(
            trf.check_freshness(
                results, SHA_A, now=FIXED_NOW + timedelta(hours=48)
            )
        )
        (results / trf.STAMP_NAME).write_text("garbage", encoding="utf-8")
        scenarios.append(trf.check_freshness(results, SHA_A, now=FIXED_NOW))
        for exit_code, line in scenarios:
            _assert_ascii(line)
            _assert_check_shape(line)

    def test_write_line_is_pure_ascii_and_strict(self, results_env):
        _repo, results = results_env
        _assert_ascii(f"written=true commit_sha={SHA_A} tree_sha={TREE_A}")
        exit_code, line = _write_stamp(results)
        assert WRITE_LINE_RE.fullmatch(line)
        _assert_ascii(line)

    def test_verdict_tokens_are_controlled_constants(self):
        assert trf.VERDICT_TOKENS == frozenset(
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


# ── CLI behavior ────────────────────────────────────────────────────────────────


class TestCli:
    def test_cli_write_and_check_round_trip(
        self, results_env, capsys, monkeypatch
    ):
        repo, results = results_env
        monkeypatch.setattr(
            trf,
            "_git_rev_parse",
            lambda root, ref: SHA_A if ref == "HEAD" else TREE_A,
        )
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--write",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                    "--suite-name", "testDebugUnitTest",
                ]
            )
        assert excinfo.value.code == 0
        assert "written=true" in capsys.readouterr().out
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                ]
            )
        assert excinfo.value.code == 0
        out = capsys.readouterr().out
        assert "verdict=fresh commit_match=true" in out
        assert str(repo) not in out  # no paths in output

    def test_cli_check_sha_drift_exits_one(
        self, results_env, capsys, monkeypatch
    ):
        repo, results = results_env
        _write_stamp(results, commit_sha=SHA_A)
        monkeypatch.setattr(
            trf,
            "_git_rev_parse",
            lambda root, ref: SHA_B if ref == "HEAD" else TREE_A,
        )
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                ]
            )
        assert excinfo.value.code == 1
        assert "verdict=sha_drift" in capsys.readouterr().out

    def test_cli_git_unavailable_check_exits_two(
        self, results_env, capsys, monkeypatch
    ):
        repo, results = results_env
        _write_stamp(results)
        monkeypatch.setattr(trf, "_git_rev_parse", lambda root, ref: None)
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                ]
            )
        assert excinfo.value.code == 2
        assert "verdict=git_unavailable" in capsys.readouterr().out

    def test_cli_git_unavailable_write_exits_two(
        self, results_env, capsys, monkeypatch
    ):
        repo, results = results_env
        monkeypatch.setattr(trf, "_git_rev_parse", lambda root, ref: None)
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--write",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                ]
            )
        assert excinfo.value.code == 2
        out = capsys.readouterr().out
        assert "written=false reason=git_unavailable" in out
        assert not (results / trf.STAMP_NAME).exists()

    def test_cli_commit_sha_override_avoids_git(
        self, results_env, monkeypatch
    ):
        repo, results = results_env
        _write_stamp(results, commit_sha=SHA_A)

        def forbidden(root, ref):
            raise AssertionError("git must not be consulted")

        monkeypatch.setattr(trf, "_git_rev_parse", forbidden)
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                    "--commit-sha", SHA_A,
                ]
            )
        assert excinfo.value.code == 0

    def test_cli_relative_results_dir_resolves_against_repo_root(
        self, results_env, monkeypatch
    ):
        repo, results = results_env
        monkeypatch.setattr(
            trf,
            "_git_rev_parse",
            lambda root, ref: SHA_A if ref == "HEAD" else TREE_A,
        )
        with pytest.raises(SystemExit) as excinfo:
            trf.main(["--write", "--repo-root", str(repo)])
        assert excinfo.value.code == 0
        assert (results / trf.STAMP_NAME).is_file()

    def test_cli_rejects_invalid_commit_sha(self, results_env):
        repo, results = results_env
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                    "--commit-sha", "not-a-sha",
                ]
            )
        assert excinfo.value.code == 2

    def test_cli_rejects_non_positive_max_age(self, results_env):
        repo, results = results_env
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                    "--max-age-hours", "0",
                ]
            )
        assert excinfo.value.code == 2

    def test_cli_requires_exactly_one_mode(self, results_env):
        repo, results = results_env
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                ["--repo-root", str(repo), "--results-dir", str(results)]
            )
        assert excinfo.value.code == 2
        with pytest.raises(SystemExit) as excinfo:
            trf.main(
                [
                    "--write", "--check",
                    "--repo-root", str(repo),
                    "--results-dir", str(results),
                ]
            )
        assert excinfo.value.code == 2

    def test_cli_default_repo_root_is_script_repo(self):
        # The default repo root must be the real repository two levels up.
        assert trf._default_repo_root() == _SCRIPT_DIR.parent.parent
