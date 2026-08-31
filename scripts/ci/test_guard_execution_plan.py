#!/usr/bin/env python3
"""
test_guard_execution_plan.py

Pytest tests for the PR-GR-10A Slice 1 typed guard-execution compiler
(scripts/ci/guard_execution_plan.py).

Coverage:
  1. Registry loading (happy path, missing execution section diagnostic).
  2. Spec validation (engine/mode vocabulary, engine-mode pairing, shell-string
     rejection, ratchet spec presence/forbidden fields, timeout profiles,
     repo-relative paths, template placeholders).
  3. Compilation (interpreter substitution, repo-relative resolution, ratchet
     child argv as repeated single-token --command-arg=, protocol-v2 explicit
     intent, unknown guard / missing execution / unresolvable input fail-closed,
     timeout override, test-only override policy).
  4. Canonicalization (semantic equality across different absolute roots;
     detection of baseline/child-token/timeout/protocol changes).
  5. write_plan_json (deterministic, repo-relative, atomic replace).
  6. Suite plan (covers every registered active guard in registry order; no
     bare python tokens; no shell metacharacters; real-registry grounding).

Run:
    python -m pytest scripts/ci/test_guard_execution_plan.py -v
"""

import copy
import json
import os
import sys
from pathlib import Path

import pytest

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import guard_execution_plan as gep  # noqa: E402
import guard_registry  # noqa: E402

WORKTREE_ROOT = os.path.dirname(os.path.dirname(_SCRIPT_DIR))
REAL_REGISTRY_PATH = os.path.join(_SCRIPT_DIR, "guard_registry.py")

SHELL_METACHARS = (";", "|", "&", "`", "$", "\n", "\r")


# ── Helpers ──────────────────────────────────────────────────────────────────────


def _codes(diags) -> list:
    return [d.code for d in diags]


def _has_code(diags, code) -> bool:
    return any(d.code == code for d in diags)


def _write_registry(path: Path, guards: dict) -> str:
    """Write a temporary registry module and return its path."""
    lines = ["GUARD_REGISTRY = {"]
    for guard_id, entry in guards.items():
        lines.append(f"    {guard_id!r}: {entry!r},")
    lines.append("}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return str(path)


def _mini_entry(mode="blocking", engine="python-direct", **execution_overrides):
    """A minimal grounded registry entry used by temp-root tests."""
    entry = {
        "script": "scripts/verify_mini.py",
        "tests": None,
        "mode": mode,
        "baseline": "config/baselines/mini.json" if mode == "ratchet" else None,
        "allowlist": None,
        "policies": None,
        "description": "mini guard fixture",
        "execution": {
            "engine": engine,
            "entrypoint": "scripts/verify_mini.py",
            "arguments": ("--allowlist", "config/mini_allowlist.yml"),
            "mode": mode,
            "requiredInputs": ("config/mini_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    }
    if mode == "ratchet":
        entry["execution"]["ratchet"] = {
            "baselinePath": "config/baselines/mini.json",
            "findingProtocol": 1,
            "fingerprintSchema": 1,
            "childArgumentTemplate": (
                "{entrypoint}",
                "--allowlist", "config/mini_allowlist.yml",
            ),
            "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
        }
    entry["execution"].update(execution_overrides)
    return entry


def _make_tree(root: Path, with_inputs: bool = True) -> None:
    """Create the minimal file tree the mini guard fixture needs."""
    (root / "scripts").mkdir(parents=True, exist_ok=True)
    (root / "scripts" / "verify_mini.py").write_text(
        "print('mini')\n", encoding="utf-8")
    if with_inputs:
        (root / "config" / "baselines").mkdir(parents=True, exist_ok=True)
        (root / "config" / "mini_allowlist.yml").write_text(
            "allow: []\n", encoding="utf-8")
        (root / "config" / "baselines" / "mini.json").write_text(
            "{}\n", encoding="utf-8")


def _ctx(root, ci_mode=False, interpreter=None, **kwargs):
    return gep.ExecutionContext(
        repo_root=str(root),
        interpreter_path=interpreter if interpreter is not None else sys.executable,
        ci_mode=ci_mode,
        **kwargs,
    )


# ── 1. Registry loading ──────────────────────────────────────────────────────────


def test_load_real_registry_all_active_guards():
    specs, diags = gep.load_guard_specs(REAL_REGISTRY_PATH)
    assert specs, "real registry must load specs"
    assert not [d for d in diags if d.severity == "error"]
    assert {s.guard_id for s in specs} == set(guard_registry.GUARD_REGISTRY)
    assert len(specs) == len(guard_registry.GUARD_REGISTRY)


def test_load_missing_execution_section_diagnostic(tmp_path):
    registry = _write_registry(tmp_path / "registry_legacy.py", {
        "legacy_only": {
            "script": "scripts/verify_legacy.py",
            "tests": None,
            "mode": "blocking",
            "baseline": None,
            "allowlist": None,
            "policies": None,
            "description": "entry without an execution section",
        },
    })
    specs, diags = gep.load_guard_specs(registry)
    assert specs == (), "entries without execution sections must not load specs"
    assert _has_code(diags, gep.E_MISSING_EXECUTION)
    assert diags[0].guard_id == "legacy_only"
    assert diags[0].severity == "error"


# ── 2. Spec validation ───────────────────────────────────────────────────────────


def test_validate_rejects_invalid_engine(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "bad_engine": _mini_entry(engine="bash"),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _has_code(diags, gep.E_INVALID_ENGINE)


def test_validate_rejects_invalid_mode(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "bad_mode": _mini_entry(mode="advisory"),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _has_code(diags, gep.E_INVALID_MODE)


def test_validate_rejects_engine_mode_mismatch(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "ratchet_engine_blocking": _mini_entry(
            mode="blocking", engine="python-ratchet"),
        "direct_engine_ratchet": _mini_entry(
            mode="ratchet", engine="python-direct"),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _codes(diags).count(gep.E_ENGINE_MODE_MISMATCH) == 2


def test_validate_rejects_shell_string_token(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "shell_semicolon": _mini_entry(arguments=("run.sh; rm -rf build",)),
        "shell_chain": _mini_entry(arguments=("echo pwned && touch pwned",)),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _codes(diags).count(gep.E_UNSAFE_TOKEN) == 2


def test_validate_rejects_ratchet_guard_without_ratchet_spec(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "no_ratchet": _mini_entry(mode="ratchet", engine="python-ratchet",
                                  ratchet=None),
    })
    specs, _ = gep.load_guard_specs(registry)
    assert all(s.ratchet is None for s in specs)
    diags = gep.validate_guard_specs(specs)
    assert _has_code(diags, gep.E_RATCHET_SPEC_REQUIRED)


def test_validate_rejects_direct_guard_with_ratchet_spec(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "direct_with_ratchet": _mini_entry(
            mode="blocking", engine="python-direct",
            ratchet={
                "baselinePath": "config/baselines/mini.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}",),
                "ciRestrictions": ("no-update-baseline",),
            }),
    })
    specs, _ = gep.load_guard_specs(registry)
    assert all(s.ratchet is not None for s in specs)
    diags = gep.validate_guard_specs(specs)
    assert _has_code(diags, gep.E_RATCHET_FIELD_FORBIDDEN)


def test_validate_rejects_unknown_timeout_profile(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "turbo": _mini_entry(timeoutProfile="turbo"),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _has_code(diags, gep.E_UNKNOWN_TIMEOUT_PROFILE)


def test_validate_rejects_non_repo_relative_input(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "absolute_input": _mini_entry(requiredInputs=("/etc/passwd",)),
        "traversal_input": _mini_entry(requiredInputs=("../outside.yml",)),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _codes(diags).count(gep.E_NOT_REPO_RELATIVE) == 2


def test_validate_rejects_bad_template_tokens(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "unknown_placeholder": _mini_entry(arguments=("{bogus}",)),
        "malformed_braces": _mini_entry(arguments=("a{b",)),
    })
    specs, _ = gep.load_guard_specs(registry)
    diags = gep.validate_guard_specs(specs)
    assert _has_code(diags, gep.E_TEMPLATE_UNKNOWN_TOKEN)
    assert _has_code(diags, gep.E_TEMPLATE_MALFORMED)


# ── 3. Compilation ───────────────────────────────────────────────────────────────


def test_compile_direct_plan_resolves_interpreter_and_paths():
    specs, load_diags = gep.load_guard_specs(REAL_REGISTRY_PATH)
    assert not [d for d in load_diags if d.severity == "error"]
    plan, diags = gep.compile_guard_plan("ui_dao", _ctx(WORKTREE_ROOT),
                                         specs=specs)
    assert plan is not None, [d.context for d in diags]
    assert plan.outer_argv[0] == sys.executable
    assert plan.outer_argv[1] == os.path.normpath(os.path.join(
        WORKTREE_ROOT, "scripts/verify_ui_dao_boundaries.py"))
    assert "--fail-on-violation" in plan.outer_argv
    assert plan.resolved_required_inputs == (
        os.path.normpath(os.path.join(
            WORKTREE_ROOT, "scripts/allowlists/ui_dao_allowlist.yml")),
    )
    assert os.path.isfile(plan.resolved_required_inputs[0])
    assert plan.child_argv is None
    assert plan.baseline is None
    assert plan.protocol is None
    assert plan.timeout_seconds == 300
    assert plan.mode == "blocking"
    assert plan.engine == "python-direct"


def test_compile_rejects_bare_python_interpreter(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "mini": _mini_entry(),
    })
    specs, _ = gep.load_guard_specs(registry)
    _make_tree(tmp_path / "root")
    for bare in ("python", "python3"):
        plan, diags = gep.compile_guard_plan(
            "mini", _ctx(tmp_path / "root", interpreter=bare), specs=specs)
        assert plan is None
        assert _has_code(diags, gep.E_BARE_PYTHON)


def test_compile_ratchet_child_argv_single_token_form():
    specs, _ = gep.load_guard_specs(REAL_REGISTRY_PATH)
    plan, diags = gep.compile_guard_plan("cancellation", _ctx(WORKTREE_ROOT),
                                         specs=specs)
    assert plan is not None, [d.context for d in diags]
    entrypoint_abs = os.path.normpath(os.path.join(
        WORKTREE_ROOT, "scripts/verify_cancellation_boundaries.py"))
    baseline_abs = os.path.normpath(os.path.join(
        WORKTREE_ROOT, "config/baselines/cancellation.json"))
    assert plan.child_argv == (sys.executable, entrypoint_abs)
    assert f"{gep.COMMAND_ARG_PREFIX}{sys.executable}" in plan.outer_argv
    assert f"{gep.COMMAND_ARG_PREFIX}{entrypoint_abs}" in plan.outer_argv
    assert "--command" not in plan.outer_argv, \
        "legacy --command shell-string flag must never appear"
    assert "--ci-mode" in plan.outer_argv
    assert "--fail-on-violation" in plan.outer_argv
    assert plan.outer_argv[plan.outer_argv.index("--baseline") + 1] == baseline_abs
    assert plan.protocol == 1


def test_compile_db_plan_protocol2_explicit_intent():
    specs, _ = gep.load_guard_specs(REAL_REGISTRY_PATH)
    plan, diags = gep.compile_guard_plan("db_access", _ctx(WORKTREE_ROOT),
                                         specs=specs)
    assert plan is not None, [d.context for d in diags]
    assert plan.protocol == 2
    assert "--finding-protocol=2" in plan.outer_argv, \
        "protocol-v2 guards must pass explicit protocol intent (rule 4)"
    assert plan.baseline == os.path.normpath(os.path.join(
        WORKTREE_ROOT, "config/baselines/db_access_v2.json"))
    assert plan.timeout_seconds == 840  # D4 profile
    assert plan.child_argv is not None
    assert plan.child_argv[0] == sys.executable
    assert plan.child_argv[1] == os.path.normpath(os.path.join(
        WORKTREE_ROOT, "scripts/verify_db_access_boundaries.py"))
    config_inputs = (
        "config/guards/db_ownership_policy.yml",
        "config/guards/db_structural_exceptions.yml",
        "config/guards/db_structural_exceptions_expected_methods.yml",
        "config/guards/production_source_roots.yml",
    )
    resolved = tuple(os.path.normpath(os.path.join(WORKTREE_ROOT, p))
                     for p in config_inputs)
    assert plan.resolved_required_inputs == resolved
    for path in resolved:
        assert path in plan.child_argv
        assert f"{gep.COMMAND_ARG_PREFIX}{path}" in plan.outer_argv
    assert plan.outer_argv[1] == os.path.normpath(os.path.join(
        WORKTREE_ROOT, "scripts/ci/guard_ratchet.py"))


def test_compile_unknown_guard_fail_closed(tmp_path):
    plan, diags = gep.compile_guard_plan("does_not_exist", _ctx(tmp_path))
    assert plan is None
    assert _has_code(diags, gep.E_UNKNOWN_GUARD)


def test_compile_missing_execution_section_fail_closed(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {
        "legacy_only": {
            "script": "scripts/verify_legacy.py",
            "tests": None,
            "mode": "blocking",
            "baseline": None,
            "allowlist": None,
            "policies": None,
            "description": "entry without an execution section",
        },
    })
    specs, load_diags = gep.load_guard_specs(registry)
    assert _has_code(load_diags, gep.E_MISSING_EXECUTION)
    plan, diags = gep.compile_guard_plan("legacy_only", _ctx(tmp_path),
                                         specs=specs)
    assert plan is None
    assert _has_code(diags, gep.E_UNKNOWN_GUARD)


def test_compile_unresolvable_input_fail_closed(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root, with_inputs=False)
    plan, diags = gep.compile_guard_plan("mini", _ctx(root), specs=specs)
    assert plan is None
    assert _has_code(diags, gep.E_INPUT_UNRESOLVED)


def test_compile_timeout_override(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root)
    plan, diags = gep.compile_guard_plan(
        "mini", _ctx(root, timeout_override=42), specs=specs)
    assert plan is not None, [d.context for d in diags]
    assert plan.timeout_seconds == 42
    plan_default, diags = gep.compile_guard_plan("mini", _ctx(root),
                                                 specs=specs)
    assert plan_default is not None
    assert plan_default.timeout_seconds == 300  # standard profile


def test_compile_test_override_rejected_in_ci(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root)
    plan, diags = gep.compile_guard_plan(
        "mini",
        _ctx(root, ci_mode=True, test_only_overrides=(
            ("config/mini_allowlist.yml", str(root / "config" / "mini_allowlist.yml")),
        )),
        specs=specs)
    assert plan is None
    assert _has_code(diags, gep.E_TEST_OVERRIDE_IN_CI)


def test_compile_test_override_outside_root_rejected(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root)
    outside = tmp_path / "outside" / "mini_allowlist.yml"
    plan, diags = gep.compile_guard_plan(
        "mini",
        _ctx(root, test_only_overrides=(
            ("config/mini_allowlist.yml", str(outside)),
        )),
        specs=specs)
    assert plan is None
    assert _has_code(diags, gep.E_OVERRIDE_OUTSIDE_ROOT)


def test_compile_test_override_applies_when_permitted(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root)
    override = root / "overrides" / "mini_allowlist.yml"
    override.parent.mkdir(parents=True, exist_ok=True)
    override.write_text("allow: [override]\n", encoding="utf-8")
    plan, diags = gep.compile_guard_plan(
        "mini",
        _ctx(root, test_only_overrides=(
            ("config/mini_allowlist.yml", str(override)),
        )),
        specs=specs)
    assert plan is not None, [d.context for d in diags]
    assert plan.resolved_required_inputs == (
        os.path.normpath(str(override)),)
    assert os.path.normpath(str(override)) in plan.outer_argv


# ── 4. Canonicalization ──────────────────────────────────────────────────────────


def test_canonicalize_equal_across_different_roots(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root_a = tmp_path / "rootA"
    root_b = tmp_path / "rootB"
    _make_tree(root_a)
    _make_tree(root_b)
    plan_a, diags_a = gep.compile_guard_plan(
        "mini", _ctx(root_a, interpreter=str(root_a / "py" / "python3")),
        specs=specs)
    plan_b, diags_b = gep.compile_guard_plan(
        "mini", _ctx(root_b, interpreter=str(root_b / "py" / "python.exe")),
        specs=specs)
    assert plan_a is not None and plan_b is not None
    # Raw plans differ in absolute spellings...
    assert plan_a.outer_argv != plan_b.outer_argv
    # ...but are semantically equal after canonicalization.
    assert gep.canonicalize_plan_for_comparison(plan_a) == \
        gep.canonicalize_plan_for_comparison(plan_b)


def test_canonicalize_detects_semantic_changes():
    specs, _ = gep.load_guard_specs(REAL_REGISTRY_PATH)
    plan, diags = gep.compile_guard_plan("db_access", _ctx(WORKTREE_ROOT),
                                         specs=specs)
    assert plan is not None
    base = gep.canonicalize_plan_for_comparison(plan)

    changed_baseline = copy.deepcopy(base)
    changed_baseline["baseline"] = "config/baselines/other.json"
    assert changed_baseline != base

    changed_child = copy.deepcopy(base)
    changed_child["childArgv"] = changed_child["childArgv"][:-1] + (
        "config/guards/other_policy.yml",)
    assert changed_child != base

    changed_timeout = copy.deepcopy(base)
    changed_timeout["timeoutSeconds"] = base["timeoutSeconds"] + 1
    assert changed_timeout != base

    changed_protocol = copy.deepcopy(base)
    changed_protocol["protocol"] = 1
    assert changed_protocol != base


# ── 5. write_plan_json ───────────────────────────────────────────────────────────


def test_write_plan_json_deterministic_and_repo_relative(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root)
    plan, _ = gep.compile_guard_plan("mini", _ctx(root), specs=specs)
    assert plan is not None

    out_a = tmp_path / "a" / "plan.json"
    out_b = tmp_path / "b" / "nested" / "plan.json"
    gep.write_plan_json(plan, str(out_a))
    gep.write_plan_json(plan, str(out_b))

    bytes_a = out_a.read_bytes()
    assert bytes_a == out_b.read_bytes(), "plan JSON must be deterministic"

    text = bytes_a.decode("utf-8")
    payload = json.loads(text)
    assert payload["schemaVersion"] == 1
    canonical = payload["plan"]
    assert canonical["guardId"] == "mini"
    assert canonical["outerArgv"][0] == gep.INTERPRETER_PLACEHOLDER
    # No machine-specific absolute spellings may be persisted.
    assert str(root) not in text
    assert sys.executable not in text
    # Atomic write leaves no temp residue.
    assert not list(tmp_path.glob("**/*.tmp"))


def test_write_plan_json_atomic_replace(tmp_path):
    registry = _write_registry(tmp_path / "registry.py", {"mini": _mini_entry()})
    specs, _ = gep.load_guard_specs(registry)
    root = tmp_path / "root"
    _make_tree(root)
    plan, _ = gep.compile_guard_plan("mini", _ctx(root), specs=specs)
    assert plan is not None

    out = tmp_path / "plan.json"
    out.write_text("OLD CONTENT", encoding="utf-8")
    gep.write_plan_json(plan, str(out))
    new_text = out.read_text(encoding="utf-8")
    assert new_text != "OLD CONTENT"
    assert new_text.endswith("\n")
    json.loads(new_text)
    assert not list(tmp_path.glob("*.tmp"))


# ── 6. Suite plan and real-registry grounding ────────────────────────────────────


def test_suite_plan_covers_all_active_guards_in_order():
    plans, diags = gep.compile_static_suite_plan(_ctx(WORKTREE_ROOT))
    errors = [d for d in diags if d.severity == "error"]
    assert not errors, [d.context for d in errors]
    expected_ids = list(guard_registry.GUARD_REGISTRY.keys())
    assert [p.guard_id for p in plans] == expected_ids
    assert len(plans) == len(guard_registry.GUARD_REGISTRY)


def test_suite_plans_no_bare_python_or_shell_tokens():
    plans, diags = gep.compile_static_suite_plan(_ctx(WORKTREE_ROOT))
    assert not [d for d in diags if d.severity == "error"]
    for plan in plans:
        argv = list(plan.outer_argv)
        if plan.child_argv is not None:
            argv.extend(plan.child_argv)
        for token in argv:
            assert token not in ("python", "python3"), \
                f"{plan.guard_id}: bare interpreter token in canonical plan"
            for metachar in SHELL_METACHARS:
                assert metachar not in token, \
                    f"{plan.guard_id}: shell metacharacter {metachar!r} in token"


def test_real_registry_grounding_validate_with_root():
    specs, load_diags = gep.load_guard_specs(REAL_REGISTRY_PATH)
    assert not [d for d in load_diags if d.severity == "error"]
    diags = gep.validate_guard_specs(specs, repo_root=WORKTREE_ROOT)
    errors = [d for d in diags if d.severity == "error"]
    assert not errors, [f"{d.guard_id}: {d.code} {d.context}" for d in errors]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v", "--tb=short"]))
