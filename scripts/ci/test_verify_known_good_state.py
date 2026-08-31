#!/usr/bin/env python3
"""
test_verify_known_good_state.py -- tests for the PR-GR-10e/10f scorecard.

Covers:
  * the all-pass path (exit 0, six pinned PASS rows plus the optional
    PR-GR-10f test-result freshness row, deterministic rendering);
  * each row's FAIL branch via fixture-injected command runners (no real
    CLI is ever spawned);
  * the INFRA branches and exit-code precedence (any INFRA row -> exit 2);
  * the platform-conditional inventory-only row (both documented branches,
    exercised by monkeypatching the module's barrier seam);
  * the optional freshness row's outcome mapping: SKIP when no stamp exists
    (documented, non-blocking, no child spawned), PASS/FAIL/INFRA per the
    freshness CLI's exit code and bounded verdict line, SKIP on the
    stamp-missing TOCTOU grace, bounded output_unparsed projection;
  * deterministic output (byte-identical across runs, no filesystem paths);
  * ASCII-safe rendering (pure-ASCII scorecard; non-ASCII glyphs mapped to
    ASCII equivalents so a cp1252-redirected Windows stdout cannot crash);
  * guard wiring: registry entry, GUARD_MANIFEST entry, time budget, and
    registry/manifest consistency.

Run:
  python -m pytest scripts/ci/test_verify_known_good_state.py -v
"""

import importlib.util
import json
from pathlib import Path

import pytest

_SCRIPT_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPT_DIR.parent.parent


def _load_module(name: str, relpath: str):
    spec = importlib.util.spec_from_file_location(name, _SCRIPT_DIR / relpath)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


vkgs = _load_module("verify_known_good_state", "verify_known_good_state.py")
_runner = _load_module("run_static_guard_suite", "run_static_guard_suite.py")
_guard_registry = _load_module("guard_registry", "guard_registry.py")


ADVISORY_CODE = "DB_SIGNATURE_UNRESOLVED"
DURABILITY_CODE = "INVENTORY_DURABILITY_UNCONFIRMED"


# ── Fixture helpers ─────────────────────────────────────────────────────────────


def _write_structural_manifest(
    guards: Path,
    structural_entries: int = 64,
    expected: int = 60,
    fixtures: int = 4,
) -> None:
    """Write the expected-methods manifest in its REAL schema shape.

    ``expected``/``fixtures`` are entry LISTS (the scorecard row must
    compare and render only their counts, never the raw lists); the pinned
    total lives under ``counts.structural_entries``.
    """
    expected_list = "".join(f"  - path: expected-{i}\n" for i in range(expected))
    fixtures_list = "".join(f"  - path: fixture-{i}\n" for i in range(fixtures))
    (guards / "db_structural_exceptions_expected_methods.yml").write_text(
        "".join(
            (
                f"counts:\n  structural_entries: {structural_entries}\n",
                "expected:\n",
                expected_list,
                "fixtures:\n",
                fixtures_list,
            )
        ),
        encoding="utf-8",
    )


def _write_min_repo(root: Path) -> None:
    """Minimal tracked-state fixture files the in-process row checks read."""
    guards = root / "config" / "guards"
    baselines = root / "config" / "baselines"
    guards.mkdir(parents=True, exist_ok=True)
    baselines.mkdir(parents=True, exist_ok=True)
    (guards / "db_ownership_policy.yml").write_text(
        "schemaVersion: 2\nentries:\n  - id: a\n", encoding="utf-8"
    )
    (guards / "db_ownership_policy.legacy.yml").write_text(
        "entries:\n  - id: legacy\n", encoding="utf-8"
    )
    (baselines / "db_access_v2.json").write_text(
        json.dumps({"entries": []}), encoding="utf-8"
    )
    candidate_entries = "".join(f"  - id: e{i}\n" for i in range(472))
    (guards / "db_ownership_policy.signatures.candidate.yml").write_text(
        "schemaVersion: 2\nentries:\n" + candidate_entries, encoding="utf-8"
    )
    structural_entries = "".join(f"  - id: s{i}\n" for i in range(64))
    (guards / "db_structural_exceptions.yml").write_text(
        "entries:\n" + structural_entries, encoding="utf-8"
    )
    _write_structural_manifest(guards)


@pytest.fixture()
def scorecard_env(tmp_path):
    """Minimal repo fixture; returns (repo, scratch) under tmp_path."""
    repo = tmp_path / "repo"
    repo.mkdir()
    _write_min_repo(repo)
    scratch = tmp_path / "scratch"
    return repo, scratch


def _report_payload(trusted, findings_count, codes):
    return {
        "schema": "guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [{} for _ in range(findings_count)],
        "diagnostics": [
            {"code": code, "controlled_context": {}} for code in codes
        ],
        "statistics": {"trusted": trusted},
    }


def _write_json(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload), encoding="utf-8")


def _argv_value(argv, flag: str) -> Path:
    return Path(argv[argv.index(flag) + 1])


def _gate_handler(exit_code=0, trusted=True, findings=0, codes=None, report=True):
    if codes is None:
        codes = [ADVISORY_CODE] * 20
    payload = _report_payload(trusted, findings, codes)

    def handler(argv, cwd, timeout):
        if report:
            _write_json(_argv_value(argv, "--findings-output"), payload)
        return vkgs.CommandResult(exit_code, "", "")

    return handler


def _inventory_handler(exit_code=None, trusted=None, codes=None, dump=True, report=True):
    """Defaults resolve at CALL time against the (monkeypatchable) barrier."""

    def handler(argv, cwd, timeout):
        barrier = vkgs._HAS_DIRECTORY_BARRIER
        ec = exit_code if exit_code is not None else (0 if barrier else 2)
        tr = trusted if trusted is not None else barrier
        cs = codes if codes is not None else ([] if barrier else [DURABILITY_CODE])
        if report:
            _write_json(
                _argv_value(argv, "--findings-output"),
                _report_payload(tr, 0, cs),
            )
        if dump:
            dump_path = _argv_value(argv, "--dump-room-mutators")
            dump_path.parent.mkdir(parents=True, exist_ok=True)
            dump_path.write_text("{}", encoding="utf-8")
        return vkgs.CommandResult(ec, "", "")

    return handler


def _migrate_check_handler(exit_code=1, input_count=99, resolved=57,
                           unresolved=42, duplicates=0, seeds=0):
    stdout = (
        f"db-policy migration: input={input_count} resolved={resolved} "
        f"unresolved={unresolved} duplicateMutationKeys={duplicates} "
        f"seeds={seeds}\n"
    )
    return lambda argv, cwd, timeout: vkgs.CommandResult(exit_code, stdout, "")


def _meta_handler(exit_code=0, stdout=""):
    return lambda argv, cwd, timeout: vkgs.CommandResult(exit_code, stdout, "")


def _verify_handler(exit_code=0, match=True, raw_stdout=None):
    def handler(argv, cwd, timeout):
        stdout = raw_stdout
        if stdout is None:
            stdout = json.dumps({"match": match}) + "\n"
        return vkgs.CommandResult(exit_code, stdout, "")

    return handler


def _freshness_handler(exit_code=0, stdout=None):
    if stdout is None:
        stdout = "verdict=fresh commit_match=true xml_count=2 xml_newer=0\n"
    return lambda argv, cwd, timeout: vkgs.CommandResult(exit_code, stdout, "")


def _router(gate=None, inventory=None, migrate_check=None, meta=None,
            verify=None, freshness=None):
    """Fixture-injected runner: dispatches on the invoked script/flag."""
    calls = []

    def runner(argv, cwd, timeout):
        tokens = [str(part) for part in argv]
        calls.append(tokens)
        joined = " ".join(tokens)
        if "verify_db_access_boundaries.py" in joined:
            if "--inventory-only" in joined:
                assert inventory is not None, joined
                return inventory(argv, cwd, timeout)
            assert gate is not None, joined
            return gate(argv, cwd, timeout)
        if "migrate_db_policy_signatures.py" in joined:
            if "--check" in joined:
                assert migrate_check is not None, joined
                return migrate_check(argv, cwd, timeout)
            assert verify is not None, joined
            return verify(argv, cwd, timeout)
        if "verify_production_source_roots.py" in joined:
            assert meta is not None, joined
            return meta(argv, cwd, timeout)
        if "test_result_freshness.py" in joined:
            assert freshness is not None, joined
            return freshness(argv, cwd, timeout)
        raise AssertionError(f"unexpected command: {joined}")

    runner.calls = calls
    return runner


def _passing_router():
    return _router(
        gate=_gate_handler(),
        inventory=_inventory_handler(),
        migrate_check=_migrate_check_handler(),
        meta=_meta_handler(),
        verify=_verify_handler(),
        freshness=_freshness_handler(),
    )


def _row(rows, name):
    for row in rows:
        if row.row == name:
            return row
    raise AssertionError(f"row not found: {name}")


def _run(repo, scratch, runner):
    return vkgs.run_scorecard(
        repo_root=repo, scratch_dir=scratch, run_command=runner
    )


# ── All-pass path ───────────────────────────────────────────────────────────────


class TestAllPass:
    def test_pinned_rows_pass_and_freshness_skips_without_stamp(
        self, scorecard_env
    ):
        repo, scratch = scorecard_env
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 0
        assert [row.outcome for row in rows] == (
            [vkgs.OUTCOME_PASS] * 6 + [vkgs.OUTCOME_SKIP]
        )
        assert [row.row for row in rows] == (
            vkgs.ROW_ACTIVE_DB_GATE,
            vkgs.ROW_INVENTORY_ONLY,
            vkgs.ROW_MIGRATION_FOLD,
            vkgs.ROW_META_SOURCE_ROOTS,
            vkgs.ROW_CANDIDATE_REPRODUCIBLE,
            vkgs.ROW_STRUCTURAL_MANIFEST,
            vkgs.ROW_TEST_RESULT_FRESHNESS,
        )

    def test_all_seven_rows_pass_with_fresh_stamp(self, scorecard_env):
        repo, scratch = scorecard_env
        results = repo.joinpath(*vkgs._FRESHNESS_RESULTS_RELPATH)
        results.mkdir(parents=True)
        (results / vkgs._FRESHNESS_STAMP_NAME).write_text("{}", encoding="utf-8")
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 0
        assert [row.outcome for row in rows] == [vkgs.OUTCOME_PASS] * 7

    def test_scorecard_rendering_is_deterministic(self, scorecard_env):
        repo, scratch = scorecard_env
        first = vkgs.render_scorecard(*_run(repo, scratch, _passing_router()))
        second = vkgs.render_scorecard(*_run(repo, scratch, _passing_router()))
        assert first == second
        assert "summary: rows=7 pass=6 fail=0 infra=0 skip=1 exit=0" in first

    def test_rendered_scorecard_contains_no_paths(self, scorecard_env, tmp_path):
        repo, scratch = scorecard_env
        rendered = vkgs.render_scorecard(*_run(repo, scratch, _passing_router()))
        assert str(tmp_path) not in rendered

    def test_rendered_scorecard_is_pure_ascii(self, scorecard_env):
        repo, scratch = scorecard_env
        rendered = vkgs.render_scorecard(*_run(repo, scratch, _passing_router()))
        assert all(ord(c) < 128 for c in rendered)

    def test_expected_strings_pin_documented_contract(self):
        assert "20xDB_SIGNATURE_UNRESOLVED" in vkgs._EXPECTED_GATE
        assert "input=99 resolved=57 unresolved=42" in vkgs._EXPECTED_MIGRATION
        assert "entries=472" in vkgs._EXPECTED_CANDIDATE
        assert "structural_entries=64" in vkgs._EXPECTED_STRUCTURAL
        assert vkgs._EXPECTED_META == "exit=0 silent"
        assert vkgs._EXPECTED_FRESHNESS == (
            "exit=0 verdict=fresh commit_match=true xml_newer=0"
        )

    def test_structural_row_observed_carries_counts_only(self, scorecard_env):
        repo, scratch = scorecard_env
        rows, _exit_code = _run(repo, scratch, _passing_router())
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        # Counts only -- never the raw expected/fixtures entry lists.
        assert row.observed == (
            "structural_entries=64 expected=60 fixtures=4 yaml_entries=64"
        )
        assert row.observed == row.expected
        assert "expected-0" not in row.observed
        assert "fixture-0" not in row.observed


# ── ASCII-safe output (Windows cp1252 stdout crash guard) ───────────────────────


class TestAsciiSafeOutput:
    def test_glyph_map_pins_ascii_equivalents(self):
        mapped = vkgs._ascii_safe("a\u2192b\u23F1c\u2713d\u2717e")
        assert mapped == "a->b[slow]cPASSdFAILe"
        # Unmapped non-ASCII reduces deterministically to '?'; newlines and
        # already-ASCII text pass through untouched.
        assert vkgs._ascii_safe("caf\u00E9\n") == "caf?\n"
        assert vkgs._ascii_safe("plain ascii\n") == "plain ascii\n"

    def test_render_maps_non_ascii_row_fields(self, scorecard_env):
        repo, scratch = scorecard_env
        rows, exit_code = _run(repo, scratch, _passing_router())
        # Simulate external drift reaching an observed field (e.g. an echoed
        # diagnostic code): the render must still be pure ASCII.
        drifted = vkgs.RowResult(
            rows[0].row, rows[0].outcome, rows[0].expected,
            "diagnostics=1xDRIFTED\u2192",
        )
        rendered = vkgs.render_scorecard((drifted,) + rows[1:], exit_code)
        assert "\u2192" not in rendered
        assert "diagnostics=1xDRIFTED->" in rendered
        assert all(ord(c) < 128 for c in rendered)


# ── Row FAIL branches (fixture-injected drift) ──────────────────────────────────


class TestGateFailBranches:
    def test_fail_on_real_findings(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(exit_code=1, findings=3),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_FAIL
        assert "gate_exit=1" in gate.observed
        assert "findings=3" in gate.observed
        assert all(
            row.outcome in (vkgs.OUTCOME_PASS, vkgs.OUTCOME_SKIP)
            for row in rows
            if row.row != vkgs.ROW_ACTIVE_DB_GATE
        )

    def test_fail_on_advisory_count_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(codes=[ADVISORY_CODE] * 19),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_FAIL
        assert "diagnostics=19xDB_SIGNATURE_UNRESOLVED" in gate.observed

    def test_fail_on_untrusted_report(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(trusted=False),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        assert _row(rows, vkgs.ROW_ACTIVE_DB_GATE).outcome == vkgs.OUTCOME_FAIL

    def test_fail_when_v1_archive_missing(self, scorecard_env):
        repo, scratch = scorecard_env
        (repo / "config" / "guards" / "db_ownership_policy.legacy.yml").unlink()
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_FAIL
        assert "v1_archived=false" in gate.observed

    def test_fail_when_ratchet_baseline_nonempty(self, scorecard_env):
        repo, scratch = scorecard_env
        baseline = repo / "config" / "baselines" / "db_access_v2.json"
        baseline.write_text(json.dumps({"entries": [{"f": "x"}]}), encoding="utf-8")
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_FAIL
        assert "ratchet_v2_empty=false" in gate.observed

    def test_fail_when_active_policy_not_v2(self, scorecard_env):
        repo, scratch = scorecard_env
        policy = repo / "config" / "guards" / "db_ownership_policy.yml"
        policy.write_text("schemaVersion: 1\nentries:\n  - id: a\n", encoding="utf-8")
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_FAIL
        assert "active_policy_v2=false" in gate.observed


class TestInventoryFailBranches:
    def test_fail_on_wrong_branch_without_barrier(self, scorecard_env, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_HAS_DIRECTORY_BARRIER", False)
        # Barrier-less platform must see the durability fallback (exit 2);
        # a trusted exit 0 is the WRONG branch here.
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(exit_code=0, trusted=True, codes=[]),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        assert _row(rows, vkgs.ROW_INVENTORY_ONLY).outcome == vkgs.OUTCOME_FAIL

    def test_fail_on_wrong_branch_with_barrier(self, scorecard_env, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_HAS_DIRECTORY_BARRIER", True)
        # Barrier-capable platform must see the trusted exit-0 contract;
        # the durability fallback is the WRONG branch here.
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(
                exit_code=2, trusted=False, codes=[DURABILITY_CODE]
            ),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        assert _row(rows, vkgs.ROW_INVENTORY_ONLY).outcome == vkgs.OUTCOME_FAIL

    def test_fail_when_dump_missing(self, scorecard_env, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_HAS_DIRECTORY_BARRIER", False)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(dump=False),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        inventory = _row(rows, vkgs.ROW_INVENTORY_ONLY)
        assert inventory.outcome == vkgs.OUTCOME_FAIL
        assert "dump=missing" in inventory.observed

    def test_fail_when_source_root_codes_present(self, scorecard_env, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_HAS_DIRECTORY_BARRIER", False)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(
                codes=[DURABILITY_CODE, "DB_SOURCE_ROOT_UNDECLARED"]
            ),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        inventory = _row(rows, vkgs.ROW_INVENTORY_ONLY)
        assert inventory.outcome == vkgs.OUTCOME_FAIL
        assert "db_source_root_codes=1" in inventory.observed


class TestMigrationFailBranches:
    def test_fail_on_resolved_count_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(resolved=56),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        migration = _row(rows, vkgs.ROW_MIGRATION_FOLD)
        assert migration.outcome == vkgs.OUTCOME_FAIL
        assert "resolved=56" in migration.observed

    def test_fail_on_clean_exit(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(exit_code=0),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        migration = _row(rows, vkgs.ROW_MIGRATION_FOLD)
        assert migration.outcome == vkgs.OUTCOME_FAIL
        assert migration.observed == "exit=0"

    def test_fail_on_duplicate_mutation_keys(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(duplicates=2),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        assert _row(rows, vkgs.ROW_MIGRATION_FOLD).outcome == vkgs.OUTCOME_FAIL


class TestMetaFailBranches:
    def test_fail_on_noisy_stdout(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(stdout="DB_SOURCE_ROOT_UNDECLARED reason=x\n"),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        meta = _row(rows, vkgs.ROW_META_SOURCE_ROOTS)
        assert meta.outcome == vkgs.OUTCOME_FAIL
        assert meta.observed == "exit=0 stdout_nonempty"

    def test_fail_on_topology_diagnostics(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(exit_code=2, stdout="DB_SOURCE_ROOT_UNDECLARED\n"),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        meta = _row(rows, vkgs.ROW_META_SOURCE_ROOTS)
        assert meta.outcome == vkgs.OUTCOME_FAIL
        assert meta.observed == "exit=2 diagnostics=1"


class TestCandidateFailBranches:
    def test_fail_on_verify_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(exit_code=1),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        candidate = _row(rows, vkgs.ROW_CANDIDATE_REPRODUCIBLE)
        assert candidate.outcome == vkgs.OUTCOME_FAIL
        assert candidate.observed == "verify_exit=1"

    def test_fail_on_match_false(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(match=False),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        candidate = _row(rows, vkgs.ROW_CANDIDATE_REPRODUCIBLE)
        assert candidate.outcome == vkgs.OUTCOME_FAIL
        assert candidate.observed == "verify_exit=0 match=false"

    def test_fail_on_entry_count_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        candidate = repo / "config" / "guards" / "db_ownership_policy.signatures.candidate.yml"
        entries = "".join(f"  - id: e{i}\n" for i in range(471))
        candidate.write_text(
            "schemaVersion: 2\nentries:\n" + entries, encoding="utf-8"
        )
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_CANDIDATE_REPRODUCIBLE)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "entries=471" in row.observed

    def test_fail_on_v1_schema(self, scorecard_env):
        repo, scratch = scorecard_env
        candidate = repo / "config" / "guards" / "db_ownership_policy.signatures.candidate.yml"
        candidate.write_text("entries:\n  - id: e0\n", encoding="utf-8")
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_CANDIDATE_REPRODUCIBLE)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "schemaVersion=None" in row.observed


class TestStructuralFailBranches:
    def test_fail_on_pinned_count_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        guards = repo / "config" / "guards"
        _write_structural_manifest(guards, structural_entries=63)
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "structural_entries=63" in row.observed

    def test_fail_on_expected_list_count_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        guards = repo / "config" / "guards"
        _write_structural_manifest(guards, expected=59)
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "expected=59" in row.observed

    def test_fail_on_fixtures_count_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        guards = repo / "config" / "guards"
        _write_structural_manifest(guards, fixtures=5)
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "fixtures=5" in row.observed

    def test_fail_when_expected_is_not_a_list(self, scorecard_env):
        # Schema drift: a non-list `expected` reduces to the -1 sentinel
        # (fail closed) -- never a crash, never a raw-value echo.
        repo, scratch = scorecard_env
        manifest = repo / "config" / "guards" / "db_structural_exceptions_expected_methods.yml"
        manifest.write_text(
            "counts:\n  structural_entries: 64\nexpected: 60\nfixtures: 4\n",
            encoding="utf-8",
        )
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "expected=-1" in row.observed

    def test_fail_on_yaml_entries_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        exceptions = repo / "config" / "guards" / "db_structural_exceptions.yml"
        entries = "".join(f"  - id: s{i}\n" for i in range(63))
        exceptions.write_text("entries:\n" + entries, encoding="utf-8")
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "yaml_entries=63" in row.observed

    def test_fail_when_manifest_unreadable(self, scorecard_env):
        repo, scratch = scorecard_env
        manifest = repo / "config" / "guards" / "db_structural_exceptions_expected_methods.yml"
        manifest.unlink()
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_STRUCTURAL_MANIFEST)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert row.observed == "manifest_unreadable"


# ── INFRA branches and exit-code precedence ─────────────────────────────────────


class TestInfraBranches:
    def test_gate_exit2_is_infra(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(exit_code=2),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_INFRA
        assert gate.observed == "gate_exit=2"

    def test_gate_timeout_is_infra(self, scorecard_env):
        repo, scratch = scorecard_env

        def timeout_handler(argv, cwd, timeout):
            return vkgs.CommandResult(-1, "", "", timed_out=True)

        runner = _router(
            gate=timeout_handler,
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_ACTIVE_DB_GATE).observed == "gate_timeout"

    def test_gate_report_unreadable_is_infra(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(report=False),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_ACTIVE_DB_GATE).observed == "gate_report_unreadable"

    def test_migrate_exit2_is_infra(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(exit_code=2),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_MIGRATION_FOLD).outcome == vkgs.OUTCOME_INFRA

    def test_verify_exit2_is_infra(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(exit_code=2),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_CANDIDATE_REPRODUCIBLE).outcome == vkgs.OUTCOME_INFRA

    def test_meta_unexpected_exit_is_infra(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(exit_code=3),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_META_SOURCE_ROOTS).outcome == vkgs.OUTCOME_INFRA

    def test_unexpected_crash_fails_closed_to_infra(self, scorecard_env):
        repo, scratch = scorecard_env

        def crashing_handler(argv, cwd, timeout):
            raise RuntimeError("boom")

        runner = _router(
            gate=crashing_handler,
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        gate = _row(rows, vkgs.ROW_ACTIVE_DB_GATE)
        assert gate.outcome == vkgs.OUTCOME_INFRA
        assert gate.observed == "check_crashed"
        assert "boom" not in gate.observed

    def test_infra_takes_precedence_over_fail(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(exit_code=2),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(resolved=56),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_ACTIVE_DB_GATE).outcome == vkgs.OUTCOME_INFRA
        assert _row(rows, vkgs.ROW_MIGRATION_FOLD).outcome == vkgs.OUTCOME_FAIL


# ── Platform-conditional inventory row ──────────────────────────────────────────


class TestPlatformConditionalInventory:
    def test_barrier_platform_expects_trusted_exit_zero(self, scorecard_env, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_HAS_DIRECTORY_BARRIER", True)
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 0
        inventory = _row(rows, vkgs.ROW_INVENTORY_ONLY)
        assert inventory.outcome == vkgs.OUTCOME_PASS
        assert inventory.expected == (
            "exit=0 trusted=true diagnostics=0 dump=written db_source_root_codes=0"
        )

    def test_no_barrier_platform_expects_durability_fallback(self, scorecard_env, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_HAS_DIRECTORY_BARRIER", False)
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 0
        inventory = _row(rows, vkgs.ROW_INVENTORY_ONLY)
        assert inventory.outcome == vkgs.OUTCOME_PASS
        assert "INVENTORY_DURABILITY_UNCONFIRMED" in inventory.expected
        assert "exit=2 trusted=false" in inventory.expected
        assert "dump=written" in inventory.observed


# ── Optional freshness row (PR-GR-10f) ──────────────────────────────────────────


class TestTestResultFreshnessRow:
    def _with_stamp(self, repo: Path) -> None:
        results = repo.joinpath(*vkgs._FRESHNESS_RESULTS_RELPATH)
        results.mkdir(parents=True, exist_ok=True)
        (results / vkgs._FRESHNESS_STAMP_NAME).write_text("{}", encoding="utf-8")

    def test_skip_when_no_stamp_and_no_child_spawned(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _passing_router()
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 0
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_SKIP
        assert row.observed == "stamp=missing"
        assert row.expected == vkgs._EXPECTED_FRESHNESS
        spawned = [
            call for call in runner.calls
            if "test_result_freshness.py" in " ".join(call)
        ]
        assert spawned == []

    def test_pass_when_stamp_exists_and_child_fresh(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        rows, exit_code = _run(repo, scratch, _passing_router())
        assert exit_code == 0
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_PASS
        assert row.observed == (
            "exit=0 verdict=fresh commit_match=true xml_count=2 xml_newer=0"
        )

    def test_check_argv_targets_repo_root(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _passing_router()
        _run(repo, scratch, runner)
        freshness_calls = [
            call for call in runner.calls
            if "test_result_freshness.py" in " ".join(call)
        ]
        assert len(freshness_calls) == 1
        joined = " ".join(freshness_calls[0])
        assert "--check" in joined
        assert "--repo-root" in joined
        assert str(repo) in joined

    def test_fail_on_sha_drift(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=_freshness_handler(
                exit_code=1,
                stdout="verdict=sha_drift commit_match=false "
                       "xml_count=0 xml_newer=0\n",
            ),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert row.observed == (
            "exit=1 verdict=sha_drift commit_match=false "
            "xml_count=0 xml_newer=0"
        )

    def test_fail_on_xml_newer_than_stamp(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=_freshness_handler(
                exit_code=1,
                stdout="verdict=xml_newer_than_stamp commit_match=true "
                       "xml_count=3 xml_newer=1\n",
            ),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert "xml_newer=1" in row.observed

    def test_skip_on_stamp_missing_after_probe(self, scorecard_env):
        # TOCTOU grace: the stamp existed at probe time but the child saw it
        # gone -> the documented never-stamped state, non-blocking SKIP.
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=_freshness_handler(
                exit_code=1,
                stdout="verdict=stamp_missing commit_match=false "
                       "xml_count=0 xml_newer=0\n",
            ),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 0
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_SKIP
        assert row.observed == "stamp=missing"

    def test_fail_closed_on_unparsed_child_output(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=_freshness_handler(
                exit_code=1,
                stdout="Traceback (most recent call last):\nboom\n",
            ),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_FAIL
        assert row.observed == "exit=1 output_unparsed"
        assert "boom" not in row.observed
        assert "Traceback" not in row.observed

    def test_infra_on_child_exit_two(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=_freshness_handler(
                exit_code=2,
                stdout="verdict=malformed_stamp commit_match=false "
                       "xml_count=0 xml_newer=0\n",
            ),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_INFRA
        assert row.observed == "freshness_exit=2"

    def test_infra_on_timeout(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)

        def timeout_handler(argv, cwd, timeout):
            return vkgs.CommandResult(-1, "", "", timed_out=True)

        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=timeout_handler,
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS).observed == (
            "freshness_timeout"
        )

    def test_infra_on_spawn_failure(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)

        def crash_handler(argv, cwd, timeout):
            return vkgs.CommandResult(-1, "", "", crashed=True)

        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=crash_handler,
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        assert _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS).observed == (
            "freshness_spawn_failed"
        )

    def test_unexpected_crash_fails_closed_to_infra(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)

        def crashing_handler(argv, cwd, timeout):
            raise RuntimeError("boom")

        runner = _router(
            gate=_gate_handler(),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
            freshness=crashing_handler,
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 2
        row = _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS)
        assert row.outcome == vkgs.OUTCOME_INFRA
        assert row.observed == "check_crashed"
        assert "boom" not in row.observed

    def test_skip_does_not_block_fail_exit_code(self, scorecard_env):
        # SKIP is non-blocking: a FAIL elsewhere still drives exit 1.
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(exit_code=1, findings=2),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        rows, exit_code = _run(repo, scratch, runner)
        assert exit_code == 1
        assert _row(rows, vkgs.ROW_TEST_RESULT_FRESHNESS).outcome == (
            vkgs.OUTCOME_SKIP
        )
        assert _row(rows, vkgs.ROW_ACTIVE_DB_GATE).outcome == (
            vkgs.OUTCOME_FAIL
        )

    def test_freshness_row_never_spawns_write_mode(self, scorecard_env):
        repo, scratch = scorecard_env
        self._with_stamp(repo)
        runner = _passing_router()
        _run(repo, scratch, runner)
        for call in runner.calls:
            joined = " ".join(call)
            if "test_result_freshness.py" in joined:
                assert "--write" not in joined, joined


# ── Read-only posture of the underlying commands ────────────────────────────────


class TestNoTrackedArtifactWrites:
    def test_underlying_commands_are_read_only(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _passing_router()
        _run(repo, scratch, runner)
        assert runner.calls, "the runner must have recorded commands"
        for call in runner.calls:
            joined = " ".join(call)
            for forbidden in (
                "--generate",
                "--write-candidate",
                "--output",
                "--accounting-out",
                "--write-accounting",
            ):
                assert forbidden not in joined, joined

    def test_gate_argv_carries_four_config_flags(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _passing_router()
        _run(repo, scratch, runner)
        gate_calls = [
            call
            for call in runner.calls
            if "verify_db_access_boundaries.py" in " ".join(call)
            and "--inventory-only" not in call
        ]
        assert len(gate_calls) == 1
        joined = " ".join(gate_calls[0])
        for flag in (
            "--fail-on-violation",
            "--ownership-policy",
            "--structural-exceptions",
            "--structural-manifest",
            "--raw-query-policy",
        ):
            assert flag in joined, joined

    def test_verify_argv_consumes_reviewed_seed_input(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _passing_router()
        _run(repo, scratch, runner)
        verify_calls = [
            call
            for call in runner.calls
            if "migrate_db_policy_signatures.py" in " ".join(call)
            and "--verify" in call
        ]
        assert len(verify_calls) == 1
        joined = " ".join(verify_calls[0])
        assert "--seed-rows docs/ci/db-findings/GR-08-seeds.yml" in joined

    def test_reports_land_under_scratch_dir(self, scorecard_env):
        repo, scratch = scorecard_env
        runner = _passing_router()
        _run(repo, scratch, runner)
        for call in runner.calls:
            joined = call
            if "--findings-output" in joined:
                out = joined[joined.index("--findings-output") + 1]
                assert out.startswith(str(scratch)), out


# ── CLI entry point ─────────────────────────────────────────────────────────────


class TestMain:
    def test_main_exit_zero_and_prints_scorecard(self, scorecard_env, capsys, monkeypatch):
        repo, scratch = scorecard_env
        monkeypatch.setattr(vkgs, "_run_command", _passing_router())
        with pytest.raises(SystemExit) as excinfo:
            vkgs.main(["--repo-root", str(repo), "--scratch-dir", str(scratch)])
        assert excinfo.value.code == 0
        out = capsys.readouterr().out
        assert "summary: rows=7 pass=6 fail=0 infra=0 skip=1 exit=0" in out

    def test_main_exit_one_on_fail(self, scorecard_env, capsys, monkeypatch):
        repo, scratch = scorecard_env
        runner = _router(
            gate=_gate_handler(exit_code=1, findings=1),
            inventory=_inventory_handler(),
            migrate_check=_migrate_check_handler(),
            meta=_meta_handler(),
            verify=_verify_handler(),
        )
        monkeypatch.setattr(vkgs, "_run_command", runner)
        with pytest.raises(SystemExit) as excinfo:
            vkgs.main(["--repo-root", str(repo), "--scratch-dir", str(scratch)])
        assert excinfo.value.code == 1
        out = capsys.readouterr().out
        assert "row=active_db_gate result=FAIL" in out

    def test_main_scratch_unavailable_exits_two(self, scorecard_env, tmp_path, capsys, monkeypatch):
        repo, _scratch = scorecard_env
        blocker = tmp_path / "blocker"
        blocker.write_text("not a directory", encoding="utf-8")
        monkeypatch.setattr(vkgs, "_run_command", _passing_router())
        with pytest.raises(SystemExit) as excinfo:
            vkgs.main(["--repo-root", str(repo), "--scratch-dir", str(blocker)])
        assert excinfo.value.code == 2
        err = capsys.readouterr().err
        assert "scratch directory unavailable" in err


# ── Guard wiring (registry + manifest consistency) ──────────────────────────────


class TestGuardWiring:
    GUARD_NAME = "known_good_state"
    GUARD_SCRIPT = "scripts/ci/verify_known_good_state.py"
    GUARD_TESTS = "scripts/ci/test_verify_known_good_state.py"

    def test_guard_registered_blocking_with_tests_field(self):
        entry = _guard_registry.GUARD_REGISTRY[self.GUARD_NAME]
        assert entry["mode"] == "blocking"
        assert entry["script"] == self.GUARD_SCRIPT
        assert entry["tests"] == self.GUARD_TESTS
        assert entry["description"]

    def test_guard_files_exist_in_repository(self):
        entry = _guard_registry.GUARD_REGISTRY[self.GUARD_NAME]
        assert (_REPO_ROOT / entry["script"]).is_file()
        assert (_REPO_ROOT / entry["tests"]).is_file()

    def test_guard_is_in_ci_manifest_blocking(self):
        entries = [
            (name, command, mode)
            for name, command, mode in _runner.GUARD_MANIFEST
            if name == self.GUARD_NAME
        ]
        assert len(entries) == 1, entries
        _name, command, mode = entries[0]
        assert mode == "blocking"
        assert command == ["python3", self.GUARD_SCRIPT]

    def test_time_budget_declared(self):
        budgets = _runner.GUARD_TIME_BUDGETS
        assert self.GUARD_NAME in budgets
        assert budgets[self.GUARD_NAME] >= 900

    def test_registry_validation_passes(self):
        assert _guard_registry.validate_registry(str(_REPO_ROOT)) == []

    def test_registry_and_manifest_names_consistent(self):
        infra_names = {"guard_tests", "guard_registry"}
        manifest_names = {
            name
            for name, _command, _mode in _runner.GUARD_MANIFEST
            if name not in infra_names
        }
        # PR-GR-10A Slice 3: declared-external registry entries (engine not
        # python-direct/python-ratchet) are excluded from the canonical suite
        # plan by design (plan Step 5 "unless declared external").
        external = {
            guard_id
            for guard_id, entry in _guard_registry.GUARD_REGISTRY.items()
            if isinstance(entry, dict)
            and (entry.get("execution") or {}).get("engine")
            not in ("python-direct", "python-ratchet")
        }
        assert manifest_names == set(_guard_registry.GUARD_REGISTRY) - external
