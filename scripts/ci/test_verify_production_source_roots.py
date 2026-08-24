#!/usr/bin/env python3
"""
test_verify_production_source_roots.py -- Pytest suite for PR-GR-03 Slice B.

Covers scripts/ci/verify_production_source_roots.py:

  1. Catalog registration of the five DB_SOURCE_ROOT_* diagnostic codes.
  2. ``parse_declared_modules``: literal single/multi/multi-arg includes,
     single vs double quotes, multi-line literal calls, de-duplication,
     dynamic-include fail-closed reasons, sourceSets/projectDir markers.
  3. ``observed_conventional_roots``: java/kotlin discovery, empty dirs,
     excluded non-production segments.
  4. ``verify_topology``: missing settings, undeclared observed roots,
     declared-but-absent roots, valid real-shape manifests, malformed
     manifests, fail-closed short-circuiting, symlinks, unreadable roots.
  5. CLI adapter: exit codes 0/2, bounded output, deterministic ordering.

Every filesystem test builds its own synthetic repository under ``tmp_path``;
no test scans the real repository or executes Gradle.

Run:
    python -m pytest scripts/ci/test_verify_production_source_roots.py -v
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import verify_production_source_roots as vpsr  # noqa: E402

# Importing the module under test puts the repository root on sys.path, so
# the package-form import below resolves to the same modules it uses.
from finding_rule_catalog import GUARD_DB_ACCESS, known_diagnostic  # noqa: E402
from scripts.db_guard.source_roots import (  # noqa: E402
    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
    DB_SOURCE_ROOT_MANIFEST_INVALID,
    DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
    DB_SOURCE_ROOT_UNDECLARED,
    DB_SOURCE_ROOT_UNREADABLE,
    load_source_root_manifest,
)

APP_JAVA = "app/src/main/java"
CORE_KOTLIN = "core/data/src/main/kotlin"

# Exact content of the checked-in manifest
# config/guards/production_source_roots.yml (pinned byte-for-byte).
MANIFEST_TEXT = (
    "schemaVersion: 1\n"
    "roots:\n"
    '  - module: ":app"\n'
    "    sourceSet: main\n"
    "    path: app/src/main/java\n"
)


# ── Helpers ───────────────────────────────────────────────────────────────────


def _write(root, rel, content="// kt\n"):
    """Create ``root/rel`` (parents included) with text content."""
    target = Path(root).joinpath(*rel.split("/"))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def _write_settings(root, body):
    (Path(root) / "settings.gradle.kts").write_text(body, encoding="utf-8")


def _settings_includes(*modules):
    return 'rootProject.name = "synthetic"\n' + "".join(
        'include("{0}")\n'.format(module) for module in modules
    )


def _write_manifest_file(root, text=MANIFEST_TEXT):
    rel = "config/guards/production_source_roots.yml"
    _write(root, rel, text)
    return str(Path(root).joinpath(*rel.split("/")))


def _codes(diagnostics):
    return [code for code, _context in diagnostics]


# ── Catalog registration ─────────────────────────────────────────────────────


def test_catalog_registers_five_source_root_codes():
    expected = {
        DB_SOURCE_ROOT_MANIFEST_INVALID,
        DB_SOURCE_ROOT_UNDECLARED,
        DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
        DB_SOURCE_ROOT_UNREADABLE,
        DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
    }
    for code in expected:
        # ``known_diagnostic`` returns the registered DiagnosticProfile;
        # ``is_known_diagnostic`` is the boolean membership predicate.
        profile = known_diagnostic(code)
        assert profile is not None, code
        assert profile.code == code
        assert profile.guard == GUARD_DB_ACCESS
        assert profile.baseline_able is False
        assert profile.description


# ── parse_declared_modules ───────────────────────────────────────────────────


def test_parse_single_include():
    modules, reason = vpsr.parse_declared_modules('include(":app")\n')
    assert modules == (":app",)
    assert reason is None


def test_parse_multi_multi_arg_and_multiline_literal_includes():
    text = (
        'include(":app")\n'
        "include(':lib')\n"
        'include(":core", ":data")\n'
        'include(":app")\n'  # duplicate -> de-duplicated
        'include(\n    ":feature:ui",\n    ":feature:data"\n)\n'
        # Later parentheses must not corrupt argument extraction:
        "dependencyResolutionManagement {\n"
        "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
        "}\n"
    )
    modules, reason = vpsr.parse_declared_modules(text)
    assert modules == (":app", ":lib", ":core", ":data", ":feature:ui", ":feature:data")
    assert reason is None


def test_parse_dynamic_include_expression_unsupported():
    cases = [
        'val mods = listOf(":a")\nmods.forEach { include(it) }\n',
        'include(":a" + suffix)\n',
        "include(project.name)\n",
        'include(":tpl${suffix}")\n',
        'include(":unclosed"\n',  # never closes
        "include()\n",  # empty call -> unparseable
    ]
    for text in cases:
        modules, reason = vpsr.parse_declared_modules(text)
        assert reason == "dynamic-include-expression", text
        assert modules == (), text


def test_parse_custom_sourceset_or_projectdir_unsupported():
    cases = [
        'include(":app")\nproject(":app").projectDir = file("moved")\n',
        'include(":app")\nrootProject.projectDir = file("root-moved")\n',
        'sourceSets { getByName("main") { } }\n',
    ]
    for text in cases:
        modules, reason = vpsr.parse_declared_modules(text)
        assert reason == "custom-source-set-or-projectdir", text
        assert modules == (), text


# ── observed_conventional_roots ──────────────────────────────────────────────


def test_observed_java_root_found(tmp_path):
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    observed, issues = vpsr.observed_conventional_roots(str(tmp_path), (":app",))
    assert observed == (APP_JAVA,)
    assert issues == ()


def test_observed_kotlin_root_found(tmp_path):
    _write(tmp_path, "core/data/src/main/kotlin/com/example/B.kt")
    observed, issues = vpsr.observed_conventional_roots(
        str(tmp_path), (":core:data",)
    )
    assert observed == (CORE_KOTLIN,)
    assert issues == ()


def test_dirs_without_kotlin_ignored(tmp_path):
    _write(tmp_path, "app/src/main/java/com/example/Readme.md")
    observed, issues = vpsr.observed_conventional_roots(str(tmp_path), (":app",))
    assert observed == ()
    assert issues == ()


def test_excluded_segments_not_observed(tmp_path):
    for rel in (
        "app/src/main/java/com/test/Sample.kt",
        "app/src/main/java/androidTest/Sample.kt",
        "app/src/main/java/debug/Sample.kt",
        "app/src/main/java/release/Sample.kt",
        "app/src/main/java/generated/Sample.kt",
        "app/src/main/java/build/Sample.kt",
    ):
        _write(tmp_path, rel)
    observed, issues = vpsr.observed_conventional_roots(str(tmp_path), (":app",))
    assert observed == ()
    assert issues == ()


# ── verify_topology ──────────────────────────────────────────────────────────


def test_missing_settings_file_layout_unsupported(tmp_path):
    diagnostics = vpsr.verify_topology(str(tmp_path), str(tmp_path / "m.yml"))
    assert diagnostics == (
        (DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED, {"reason": "settings-file-missing"}),
    )


def test_undeclared_observed_root_reported(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app", ":extra"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write(tmp_path, "extra/src/main/java/com/example/B.kt")
    manifest = _write_manifest_file(tmp_path)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_UNDECLARED,
            {
                "target": "extra/src/main/java",
                "reason": "undeclared-observed-root",
            },
        ),
    )


def test_declared_but_absent_root_reported_not_observed(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app", ":core:data"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    manifest = _write_manifest_file(
        tmp_path,
        text=(
            "schemaVersion: 1\n"
            "roots:\n"
            '  - module: ":app"\n'
            "    sourceSet: main\n"
            "    path: app/src/main/java\n"
            '  - module: ":core:data"\n'
            "    sourceSet: main\n"
            "    path: core/data/src/main/kotlin\n"
        ),
    )

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    # The absent directory is unreadable (topology check) AND unobserved
    # (comparison): both signals are reported, fail closed.
    assert (DB_SOURCE_ROOT_UNREADABLE, {"target": CORE_KOTLIN}) in diagnostics
    assert (
        DB_SOURCE_ROOT_UNDECLARED,
        {"target": CORE_KOTLIN, "reason": "declared-root-not-observed"},
    ) in diagnostics
    assert set(_codes(diagnostics)) <= {
        DB_SOURCE_ROOT_UNREADABLE,
        DB_SOURCE_ROOT_UNDECLARED,
    }


def test_valid_real_shape_manifest_zero_diagnostics(tmp_path):
    _write_settings(
        tmp_path, 'rootProject.name = "ExpenseTracker"\ninclude(":app")\n'
    )
    _write(tmp_path, "app/src/main/java/com/example/expensetracker/App.kt")
    manifest = _write_manifest_file(tmp_path)
    assert vpsr.verify_topology(str(tmp_path), manifest) == ()


def test_checked_in_manifest_is_valid_and_pinned():
    repo_root = Path(__file__).resolve().parents[2]
    manifest_path = repo_root / "config" / "guards" / "production_source_roots.yml"
    assert manifest_path.read_text(encoding="utf-8") == MANIFEST_TEXT
    root_set, diagnostics = load_source_root_manifest(str(manifest_path))
    assert diagnostics == ()
    assert root_set is not None
    assert root_set.paths == (APP_JAVA,)


def test_malformed_manifest_reports_manifest_invalid(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    manifest = _write_manifest_file(tmp_path, text="roots: [unclosed\n")

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (DB_SOURCE_ROOT_MANIFEST_INVALID, {"reason": "malformed-yaml"}),
    )


def test_dynamic_include_fail_closed_skips_partial_conclusions(tmp_path):
    _write_settings(
        tmp_path,
        'val mods = listOf(":a")\nmods.forEach { include(it) }\n',
    )
    manifest = _write_manifest_file(tmp_path, text="roots: [unclosed\n")

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    # Layout failure short-circuits: no manifest or topology conclusions.
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "dynamic-include-expression"},
        ),
    )


def test_symlinked_declared_root_reported_symlink_outside(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    _write(outside, "Keeper.kt")
    _write_settings(repo, _settings_includes(":app"))
    manifest = _write_manifest_file(repo)

    java_parent = repo / "app" / "src" / "main"
    java_parent.mkdir(parents=True)
    try:
        os.symlink(str(outside), str(java_parent / "java"), target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlink privileges unavailable on this platform")

    diagnostics = vpsr.verify_topology(str(repo), manifest)
    assert (DB_SOURCE_ROOT_SYMLINK_OUTSIDE, {"target": APP_JAVA}) in diagnostics
    assert set(_codes(diagnostics)) <= {
        DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
        DB_SOURCE_ROOT_UNDECLARED,
    }


def test_unreadable_declared_root_reported_unreadable(tmp_path, monkeypatch):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    manifest = _write_manifest_file(tmp_path)

    target_abs = os.path.normcase(
        os.path.abspath(str(tmp_path / "app" / "src" / "main" / "java"))
    )
    real_listdir = os.listdir

    def _fake_listdir(path):
        if os.path.normcase(os.path.abspath(str(path))) == target_abs:
            raise PermissionError("simulated denial")
        return real_listdir(path)

    monkeypatch.setattr(os, "listdir", _fake_listdir)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (DB_SOURCE_ROOT_UNREADABLE, {"target": APP_JAVA}),
        (
            DB_SOURCE_ROOT_UNDECLARED,
            {"target": APP_JAVA, "reason": "declared-root-not-observed"},
        ),
    )


# ── CLI adapter ──────────────────────────────────────────────────────────────


def _make_clean_repo(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    return _write_manifest_file(tmp_path)


def test_cli_exit_zero_on_clean_repo(tmp_path, capsys):
    manifest = _make_clean_repo(tmp_path)
    with pytest.raises(SystemExit) as excinfo:
        vpsr.main(["--root", str(tmp_path), "--manifest", manifest])
    assert excinfo.value.code == 0
    assert capsys.readouterr().out == ""


def test_cli_exit_two_prints_bounded_diagnostic_line(tmp_path, capsys):
    _write_settings(tmp_path, _settings_includes(":app", ":extra"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write(tmp_path, "extra/src/main/java/com/example/B.kt")
    manifest = _write_manifest_file(tmp_path)

    with pytest.raises(SystemExit) as excinfo:
        vpsr.main(["--root", str(tmp_path), "--manifest", manifest])
    assert excinfo.value.code == 2

    lines = capsys.readouterr().out.splitlines()
    assert lines == [
        "DB_SOURCE_ROOT_UNDECLARED"
        " reason=undeclared-observed-root target=extra/src/main/java"
    ]


def test_cli_output_deterministic_across_two_runs(tmp_path, capsys):
    _write_settings(
        tmp_path, _settings_includes(":app", ":feature:a", ":feature:b")
    )
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write(tmp_path, "feature/a/src/main/java/A.kt")
    _write(tmp_path, "feature/b/src/main/java/B.kt")
    manifest = _write_manifest_file(tmp_path)

    outputs = []
    codes = []
    for _run in range(2):
        with pytest.raises(SystemExit) as excinfo:
            vpsr.main(["--root", str(tmp_path), "--manifest", manifest])
        codes.append(excinfo.value.code)
        outputs.append(capsys.readouterr().out)

    assert codes == [2, 2]
    assert outputs[0] == outputs[1]
    assert outputs[0].splitlines() == [
        "DB_SOURCE_ROOT_UNDECLARED"
        " reason=undeclared-observed-root target=feature/a/src/main/java",
        "DB_SOURCE_ROOT_UNDECLARED"
        " reason=undeclared-observed-root target=feature/b/src/main/java",
    ]
