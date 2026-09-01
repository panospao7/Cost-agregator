#!/usr/bin/env python3
"""
test_verify_production_source_roots.py -- Pytest suite for PR-GR-03 Slice B
and the PR-GR-10B Slice 3 topology upgrade.

Covers scripts/ci/verify_production_source_roots.py:

  1. Catalog registration of the five DB_SOURCE_ROOT_* diagnostic codes.
  2. ``parse_declared_modules``: literal single/multi/multi-arg includes,
     single vs double quotes, multi-line literal calls, de-duplication,
     dynamic-include fail-closed reasons, sourceSets/projectDir markers.
  3. ``parse_module_build_sources`` (GR-10B Slice 3): literal ``main``
     ``java.srcDirs``/``kotlin.srcDirs`` supported; dynamic arguments,
     projectDir overrides, unmodeled closures, missing/unreadable build
     files, and root-build source-layout markers fail closed; the real
     ``:app`` shape (non-main dynamic ``assets.srcDirs``) is supported.
  4. ``observed_conventional_roots``: java/kotlin discovery, empty dirs,
     excluded non-production segments, literal main srcDir candidates.
  5. ``verify_topology``: missing settings, undeclared observed roots,
     declared-but-absent roots, valid real-shape manifests, malformed
     manifests, fail-closed short-circuiting, symlinks, unreadable roots,
     missing/unreadable module build files, both mismatch directions for
     literal srcDir roots.
  6. CLI adapter: exit codes 0/2, bounded output, deterministic ordering.

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


# Benign module build-file body: no sourceSets/srcDir/projectDir/setRoot
# markers, so a module with this file keeps the conventional layout.
_DEFAULT_MODULE_BUILD = (
    "plugins {\n"
    '    id("org.jetbrains.kotlin.android")\n'
    "}\n"
)


def _write_module_build(root, module=":app", body=_DEFAULT_MODULE_BUILD):
    """Create ``<module-dir>/build.gradle.kts`` for a declared module.

    GR-10B Slice 3: every declared module must have a readable build file,
    so every synthetic repository with declared modules writes one.
    """
    module_dir = module.strip().lstrip(":").replace(":", "/")
    return _write(root, module_dir + "/build.gradle.kts", body)


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
    _write_module_build(tmp_path, ":app")
    _write_module_build(tmp_path, ":extra")
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
    _write_module_build(tmp_path, ":app")
    _write_module_build(tmp_path, ":core:data")
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
    _write_module_build(tmp_path, ":app")
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
    _write_module_build(tmp_path, ":app")
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
    _write_module_build(repo, ":app")
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
    _write_module_build(tmp_path, ":app")
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
    _write_module_build(tmp_path, ":app")
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
    _write_module_build(tmp_path, ":app")
    _write_module_build(tmp_path, ":extra")
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
    _write_module_build(tmp_path, ":app")
    _write_module_build(tmp_path, ":feature:a")
    _write_module_build(tmp_path, ":feature:b")
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


# ── GR-10B Slice 3: module build-file parsing (pure) ─────────────────────────


def test_parse_module_build_conventional_no_sourcesets():
    dirs, reason = vpsr.parse_module_build_sources(_DEFAULT_MODULE_BUILD)
    assert dirs == ()
    assert reason is None


def test_parse_module_build_projectdir_reads_are_not_overrides():
    # Read-only $projectDir uses (no assignment/.set) stay supported.
    dirs, reason = vpsr.parse_module_build_sources(
        'arg("room.schemaLocation", "$projectDir/schemas")\n'
    )
    assert dirs == ()
    assert reason is None


def test_parse_module_build_literal_main_srcdirs_call_and_chain_forms():
    braced = (
        "android {\n"
        "    sourceSets {\n"
        "        getByName(\"main\") {\n"
        "            java.srcDirs(\"src/main/extra\")\n"
        "            kotlin.srcDirs(\"src/main/kotlin-extra\", \"src/main/other\")\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    dirs, reason = vpsr.parse_module_build_sources(braced)
    assert reason is None
    assert dirs == ("src/main/extra", "src/main/kotlin-extra", "src/main/other")

    chained = (
        "sourceSets {\n"
        "    getByName(\"main\").java.srcDirs(\"src/main/chain\")\n"
        "}\n"
        "sourceSets {\n"
        "    getByName(\"main\") {\n"
        "        java.srcDirs(\"src/main/braced\")\n"
        "    }\n"
        "}\n"
    )
    dirs, reason = vpsr.parse_module_build_sources(chained)
    assert reason is None
    assert dirs == ("src/main/chain", "src/main/braced")


def test_parse_module_build_non_main_dynamic_assets_supported():
    # Exact shape of the real :app build file: dynamic $projectDir-based
    # assets.srcDirs on NON-main source sets can never define production
    # roots, so the layout stays supported.
    body = (
        "plugins {\n"
        "    id(\"com.android.application\")\n"
        "}\n"
        "android {\n"
        "    sourceSets {\n"
        "        getByName(\"debug\").assets.srcDirs(\"$projectDir/schemas\")\n"
        "        getByName(\"androidTest\").assets.srcDirs(\"$projectDir/schemas\")\n"
        "        getByName(\"test\").assets.srcDirs(\"$projectDir/schemas\")\n"
        "    }\n"
        "    lint {\n"
        "        baseline = file(\"lint-baseline.xml\")\n"
        "    }\n"
        "}\n"
    )
    dirs, reason = vpsr.parse_module_build_sources(body)
    assert dirs == ()
    assert reason is None


@pytest.mark.parametrize(
    "srcdirs_line",
    [
        "java.srcDirs(extraDir)",                       # variable
        'java.srcDirs("$projectDir/extra")',            # string template
        'java.srcDirs("a" + suffix)',                   # concatenation
        "kotlin.srcDirs()",                             # empty -> unmodelable
        'java.srcDirs("../outside")',                   # traversal
        'java.srcDirs("/abs/path")',                    # absolute
        'java.srcDirs("a\\\\b")',                       # backslash / escape
    ],
)
def test_parse_module_build_dynamic_main_srcdirs_unsupported(srcdirs_line):
    body = (
        "sourceSets {\n"
        "    getByName(\"main\") {\n"
        "            " + srcdirs_line + "\n"
        "    }\n"
        "}\n"
    )
    dirs, reason = vpsr.parse_module_build_sources(body)
    assert dirs == (), srcdirs_line
    assert reason == "dynamic-source-dir-expression", srcdirs_line


@pytest.mark.parametrize(
    "override_line",
    [
        'projectDir = file("moved")\n',
        'rootProject.projectDir = file("root-moved")\n',
        'projectDir.set(file("moved"))\n',
        'android { sourceSets { getByName("main") { setRoot("moved") } } }\n',
    ],
)
def test_parse_module_build_projectdir_and_setroot_overrides_unsupported(
    override_line,
):
    dirs, reason = vpsr.parse_module_build_sources(override_line)
    assert dirs == (), override_line
    assert reason == "custom-source-set-or-projectdir", override_line


@pytest.mark.parametrize(
    "body",
    [
        # Unattributed closure group inside sourceSets:
        "sourceSets {\n    configureEach {\n    }\n}\n",
        # Assignment / setSrcDirs forms inside a main group:
        "sourceSets {\n"
        "    getByName(\"main\") {\n"
        "        java.srcDirs = listOf(\"src/main/extra\")\n"
        "    }\n"
        "}\n",
        # Nested accessor block (java { ... }) inside a main group:
        "sourceSets {\n"
        "    getByName(\"main\") {\n"
        "        java {\n"
        '            srcDirs("src/main/extra")\n'
        "        }\n"
        "    }\n"
        "}\n",
        # Groovy-style sourceSets without a following brace:
        'sourceSets.main.java.srcDirs("x")\n',
        # Unclosed sourceSets block:
        "sourceSets {\n    getByName(\"main\") {\n",
        # srcDir usage outside any sourceSets block:
        'val junk = srcDirs("x")\n',
        # Block comments are unhandled -> fail closed rather than guess:
        "/* sourceSets */\nval x = 1\n",
    ],
)
def test_parse_module_build_unmodeled_customization_unsupported(body):
    dirs, reason = vpsr.parse_module_build_sources(body)
    assert dirs == (), body
    assert reason == "custom-source-set-or-projectdir", body


def test_parse_module_build_non_string_input_unsupported():
    dirs, reason = vpsr.parse_module_build_sources(None)
    assert dirs == ()
    assert reason == "dynamic-source-dir-expression"


# ── GR-10B Slice 3: topology integration ─────────────────────────────────────


_REAL_APP_SHAPE_BUILD = (
    "plugins {\n"
    "    id(\"com.android.application\")\n"
    "}\n"
    "android {\n"
    "    sourceSets {\n"
    "        getByName(\"debug\").assets.srcDirs(\"$projectDir/schemas\")\n"
    "        getByName(\"androidTest\").assets.srcDirs(\"$projectDir/schemas\")\n"
    "        getByName(\"test\").assets.srcDirs(\"$projectDir/schemas\")\n"
    "    }\n"
    "}\n"
)


def _write_two_root_manifest(root):
    return _write_manifest_file(
        root,
        text=(
            "schemaVersion: 1\n"
            "roots:\n"
            '  - module: ":app"\n'
            "    sourceSet: main\n"
            "    path: app/src/main/java\n"
            '  - module: ":app"\n'
            "    sourceSet: main\n"
            "    path: app/src/main/kotlin\n"
        ),
    )


def test_missing_module_build_file_fail_closed(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    manifest = _write_manifest_file(tmp_path)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "module-build-file-missing"},
        ),
    )


def test_unreadable_module_build_file_fail_closed(tmp_path, monkeypatch):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(tmp_path, ":app")
    manifest = _write_manifest_file(tmp_path)

    target_abs = os.path.normcase(
        os.path.abspath(str(tmp_path / "app" / "build.gradle.kts"))
    )
    real_open = open

    def _fake_open(file, *args, **kwargs):
        if os.path.normcase(os.path.abspath(str(file))) == target_abs:
            raise PermissionError("simulated denial")
        return real_open(file, *args, **kwargs)

    monkeypatch.setattr("builtins.open", _fake_open)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "module-build-file-unreadable"},
        ),
    )


def test_root_build_file_unreadable_fail_closed(tmp_path, monkeypatch):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(tmp_path, ":app")
    _write(tmp_path, "build.gradle.kts", "plugins {\n}\n")
    manifest = _write_manifest_file(tmp_path)

    target_abs = os.path.normcase(
        os.path.abspath(str(tmp_path / "build.gradle.kts"))
    )
    real_open = open

    def _fake_open(file, *args, **kwargs):
        if os.path.normcase(os.path.abspath(str(file))) == target_abs:
            raise PermissionError("simulated denial")
        return real_open(file, *args, **kwargs)

    monkeypatch.setattr("builtins.open", _fake_open)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "root-build-file-unreadable"},
        ),
    )


def test_root_build_file_source_layout_marker_fail_closed(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(tmp_path, ":app")
    _write(
        tmp_path,
        "build.gradle.kts",
        "subprojects {\n    sourceSets {\n    }\n}\n",
    )
    manifest = _write_manifest_file(tmp_path)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "custom-source-set-or-projectdir"},
        ),
    )


def test_literal_main_srcdirs_declared_zero_diagnostics(tmp_path):
    # A literal srcDirs entry that points at a conventional tail
    # (java.srcDirs("src/main/kotlin")) creates a second production root;
    # once the manifest declares it, the layout verifies cleanly.
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(
        tmp_path,
        ":app",
        body=(
            "android {\n"
            "    sourceSets {\n"
            "        getByName(\"main\") {\n"
            '            java.srcDirs("src/main/kotlin")\n'
            "        }\n"
            "    }\n"
            "}\n"
        ),
    )
    _write(tmp_path, "app/src/main/kotlin/com/example/B.kt")
    manifest = _write_two_root_manifest(tmp_path)

    assert vpsr.verify_topology(str(tmp_path), manifest) == ()


def test_real_app_source_sets_shape_zero_diagnostics(tmp_path):
    # The real :app build file shape (dynamic non-main assets.srcDirs)
    # must stay supported; only the conventional root is declared.
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(tmp_path, ":app", body=_REAL_APP_SHAPE_BUILD)
    manifest = _write_manifest_file(tmp_path)

    assert vpsr.verify_topology(str(tmp_path), manifest) == ()


def test_dynamic_main_srcdirs_fail_closed(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(
        tmp_path,
        ":app",
        body=(
            "sourceSets {\n"
            "    getByName(\"main\") {\n"
            "        java.srcDirs(extraDir)\n"
            "    }\n"
            "}\n"
        ),
    )
    manifest = _write_manifest_file(tmp_path)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "dynamic-source-dir-expression"},
        ),
    )


def test_module_projectdir_override_fail_closed(tmp_path):
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(
        tmp_path, ":app", body='projectDir = file("moved")\n'
    )
    manifest = _write_manifest_file(tmp_path)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            {"reason": "custom-source-set-or-projectdir"},
        ),
    )


def test_listed_custom_root_undeclared_reported(tmp_path):
    # Mismatch direction A: a supported module build file adds a literal
    # main srcDir root; the Kotlin-containing root it creates must be
    # declared, otherwise the topology comparison fails closed (UNDECLARED,
    # never a silent partial scope).
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write_module_build(
        tmp_path,
        ":app",
        body=(
            "sourceSets {\n"
            "    getByName(\"main\") {\n"
            '        java.srcDirs("src/main/kotlin")\n'
            "    }\n"
            "}\n"
        ),
    )
    _write(tmp_path, "app/src/main/kotlin/com/example/B.kt")
    manifest = _write_manifest_file(tmp_path)  # declares app/src/main/java only

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_UNDECLARED,
            {
                "target": "app/src/main/kotlin",
                "reason": "undeclared-observed-root",
            },
        ),
    )


def test_declared_root_without_kotlin_not_observed(tmp_path):
    # Mismatch direction B: the manifest declares a root whose directory
    # exists but contains no Kotlin, so nothing observes it and the
    # declaration is not explainable by topology.
    _write_settings(tmp_path, _settings_includes(":app"))
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write(tmp_path, "app/src/main/kotlin/README.md", "readme\n")
    _write_module_build(tmp_path, ":app")  # conventional only, no srcDirs
    manifest = _write_two_root_manifest(tmp_path)

    diagnostics = vpsr.verify_topology(str(tmp_path), manifest)
    assert diagnostics == (
        (
            DB_SOURCE_ROOT_UNDECLARED,
            {
                "target": "app/src/main/kotlin",
                "reason": "declared-root-not-observed",
            },
        ),
    )


def test_observed_roots_include_custom_dirs(tmp_path):
    _write(tmp_path, "app/src/main/java/com/example/A.kt")
    _write(tmp_path, "app/src/main/extra/B.kt")
    observed, issues = vpsr.observed_conventional_roots(
        str(tmp_path), (":app",), {":app": ("src/main/extra",)}
    )
    assert observed == (APP_JAVA, "app/src/main/extra")
    assert issues == ()
