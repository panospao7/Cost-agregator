"""
test_verify_migration_matrix.py
PR 5 acceptance tests for the migration matrix verification guard.

Run with: python -m pytest scripts/test_verify_migration_matrix.py -v
"""
import os
import sys
import pytest
from pathlib import Path

# Import the module under test
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_migration_matrix as mm


# ── Helpers ────────────────────────────────────────────────────────────────

def _write_kt(tmp_path, filename, content):
    """Write a Kotlin source file in a src/ tree under tmp_path."""
    src_dir = tmp_path / "app" / "src" / "main" / "java" / "com" / "example"
    src_dir.mkdir(parents=True, exist_ok=True)
    f = src_dir / filename
    f.write_text(content, encoding="utf-8")
    return f


def _write_schema_json(root: Path, version: int):
    """Create a minimal schema JSON file for the given version."""
    schema_dir = root / "app" / "schemas" / "com.example.database.AppDatabase"
    schema_dir.mkdir(parents=True, exist_ok=True)
    json_file = schema_dir / f"{version}.json"
    json_file.write_text(
        '{"formatVersion":1,"database":{"version":%d,"identityHash":"abc"}}'
        % version,
        encoding="utf-8",
    )


def _make_app_database(tmp_path, version):
    """Write AppDatabase.kt with the given schema version."""
    return _write_kt(tmp_path, "AppDatabase.kt",
        f"const val APP_DATABASE_SCHEMA_VERSION = {version}\n"
    )


def _make_schema_policy(tmp_path, baseline=145,
                        current_version_line=(
                            "const val CURRENT_VERSION = "
                            "APP_DATABASE_SCHEMA_VERSION")):
    """Write DatabaseSchemaPolicy.kt with the given CURRENT_VERSION line form.

    Default is the production (unqualified) delegate form:
        const val CURRENT_VERSION = APP_DATABASE_SCHEMA_VERSION
    """
    return _write_kt(tmp_path, "DatabaseSchemaPolicy.kt",
        "package com.example.database\n"
        "\n"
        "object DatabaseSchemaPolicy {\n"
        f"    {current_version_line}\n"
        f"    const val MIGRATION_BASELINE = {baseline}\n"
        "}\n"
    )


def _make_migrations(tmp_path, migrations, baseline_text="v145 is the baseline"):
    """Write DatabaseMigrations.kt with given migration list and baseline comment.

    migrations: list of (start, end) tuples
    """
    vals = []
    arr_entries = []
    for start, end in migrations:
        vals.append(
            f"    val MIGRATION_{start}_{end} = object : Migration({start}, {end}) {{\n"
            f"        override fun migrate(db: SupportSQLiteDatabase) {{ }}\n"
            f"    }}\n"
        )
        arr_entries.append(f"MIGRATION_{start}_{end}")

    # Pre-compute the ALL comment to avoid "v0 baseline" when baseline_text is empty
    base_ver = build_baseline(baseline_text)
    if baseline_text:
        all_comment = f"/** All registered migrations, starting from v{base_ver} baseline. */"
    else:
        all_comment = "/** All registered migrations. */"

    content = f"""package com.example

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Registry of supported Room migrations.
 *
 * {baseline_text}
 */
object DatabaseMigrations {{

{''.join(vals)}
    {all_comment}
    val ALL: Array<Migration> = arrayOf({', '.join(arr_entries)})
}}
"""
    return _write_kt(tmp_path, "DatabaseMigrations.kt", content)


def build_baseline(baseline_text):
    """Extract the version number from a baseline text like 'v145 is the baseline'."""
    import re
    match = re.search(r"v(\d+)", baseline_text)
    return int(match.group(1)) if match else None


# ── Tests ───────────────────────────────────────────────────────────────────

def test_all_expected_migrations_present(tmp_path):
    """Simulate a DatabaseMigrations.kt with baseline 145 and migrations 145-148.

    AppDatabase at v148, migrations 145→146, 146→147, 147→148.
    Expected: 0 missing.
    """
    _make_app_database(tmp_path, 148)
    _make_migrations(tmp_path,
        [(145, 146), (146, 147), (147, 148)],
        baseline_text="v145 is the baseline. There are intentionally no historical migrations"
    )
    _write_schema_json(tmp_path, 145)
    _write_schema_json(tmp_path, 146)
    _write_schema_json(tmp_path, 147)
    _write_schema_json(tmp_path, 148)

    # Locate the files we just wrote
    app_db = mm.find_kotlin_source(tmp_path, "AppDatabase.kt")
    mig = mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt")

    assert app_db is not None, "AppDatabase.kt not found"
    assert mig is not None, "DatabaseMigrations.kt not found"

    latest = mm.parse_latest_version(app_db)
    assert latest == 148, f"Expected latest=148, got {latest}"

    baseline, _ = mm.parse_baseline_version(mig)
    assert baseline == 145, f"Expected baseline=145, got {baseline}"

    registered, _, _ = mm.parse_registered_migrations(mig)
    expected = {(145, 146), (146, 147), (147, 148)}
    assert registered == expected, f"Expected {expected}, got {registered}"

    missing = mm.compute_missing_migrations(baseline, latest, registered)
    assert len(missing) == 0, f"Expected 0 missing, got {missing}"


def test_missing_migration_detected(tmp_path):
    """A gap in the middle of the supported range is detected.

    AppDatabase at v148, but only 145→146 and 147→148 are registered.
    Missing: 146→147.
    """
    _make_app_database(tmp_path, 148)
    _make_migrations(tmp_path,
        [(145, 146), (147, 148)],
        baseline_text="v145 is the baseline"
    )

    mig = mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt")
    app_db = mm.find_kotlin_source(tmp_path, "AppDatabase.kt")
    registered, _, _ = mm.parse_registered_migrations(mig)
    latest = mm.parse_latest_version(app_db)
    baseline, _ = mm.parse_baseline_version(mig)

    missing = mm.compute_missing_migrations(baseline, latest, registered)
    assert (146, 147) in missing, f"Expected (146, 147) to be missing, got {missing}"
    assert len(missing) == 1, f"Expected exactly 1 missing, got {len(missing)}: {missing}"


def test_known_gaps_are_excluded(tmp_path):
    """Versions below the baseline are intentionally excluded.

    AppDatabase at v148, baseline v145, migrations 145→146, 146→147, 147→148.
    Versions 1–144 should NOT be reported as missing.
    """
    _make_app_database(tmp_path, 148)
    _make_migrations(tmp_path,
        [(145, 146), (146, 147), (147, 148)],
        baseline_text="v145 is the baseline. There are intentionally no historical migrations below v145"
    )

    mig = mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt")
    app_db = mm.find_kotlin_source(tmp_path, "AppDatabase.kt")
    registered, _, _ = mm.parse_registered_migrations(mig)
    latest = mm.parse_latest_version(app_db)
    baseline, _ = mm.parse_baseline_version(mig)

    # The missing computation should only check from baseline to latest-1
    missing = mm.compute_missing_migrations(baseline, latest, registered)
    assert len(missing) == 0, f"Expected 0 missing in supported range, got {missing}"

    # Versions below baseline should NOT appear in missing
    for s, e in missing:
        assert s >= baseline, f"Version {s} is below baseline {baseline} but reported as missing"


def test_fail_on_violation_exit_code(tmp_path, monkeypatch):
    """--fail-on-violation causes exit code 1 when a migration is missing."""
    # Create a scenario with a missing migration
    _make_app_database(tmp_path, 148)
    _make_migrations(tmp_path,
        [(145, 146), (147, 148)],  # missing 146→147
        baseline_text="v145 is the baseline"
    )

    # Simulate CLI args
    monkeypatch.setattr(sys, 'argv', [
        'verify_migration_matrix.py',
        '--root', str(tmp_path),
        '--fail-on-violation',
    ])

    with pytest.raises(SystemExit) as exc_info:
        mm.main()
    assert exc_info.value.code == 1, \
        f"Expected exit code 1, got {exc_info.value.code}"


def test_pass_without_fail_flag(tmp_path, monkeypatch):
    """Without --fail-on-violation, missing migrations exit 0 (warning mode)."""
    _make_app_database(tmp_path, 148)
    _make_migrations(tmp_path,
        [(145, 146), (147, 148)],  # missing 146→147
        baseline_text="v145 is the baseline"
    )

    monkeypatch.setattr(sys, 'argv', [
        'verify_migration_matrix.py',
        '--root', str(tmp_path),
    ])

    with pytest.raises(SystemExit) as exc_info:
        mm.main()
    # Warning mode should exit 0 even with violations
    assert exc_info.value.code == 0, \
        f"Expected exit code 0 (warning mode), got {exc_info.value.code}"


def test_no_migrations_needed_when_baseline_equals_latest(tmp_path):
    """When baseline == latest, no migrations are needed."""
    _make_app_database(tmp_path, 145)
    _make_migrations(tmp_path,
        [],
        baseline_text="v145 is the baseline"
    )

    mig = mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt")
    app_db = mm.find_kotlin_source(tmp_path, "AppDatabase.kt")
    registered, _, _ = mm.parse_registered_migrations(mig)
    latest = mm.parse_latest_version(app_db)
    baseline, _ = mm.parse_baseline_version(mig)

    assert latest == 145
    assert baseline == 145
    assert len(registered) == 0

    missing = mm.compute_missing_migrations(baseline, latest, registered)
    assert len(missing) == 0, f"Expected 0 missing when baseline == latest, got {missing}"


def test_all_array_cross_validation_detects_missing_entries(tmp_path):
    """Migrations in ALL array but not defined as val are flagged."""
    # Write DatabaseMigrations.kt that references MIGRATION_150_151 in ALL
    # but doesn't define it as a val
    content = """package com.example

import androidx.room.migration.Migration

/**
 * v145 is the baseline.
 */
object DatabaseMigrations {

    val MIGRATION_145_146 = object : Migration(145, 146) {
        override fun migrate(db: SupportSQLiteDatabase) { }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_145_146, MIGRATION_150_151)
}
"""
    _write_kt(tmp_path, "DatabaseMigrations.kt", content)

    mig = mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt")
    registered, _, all_array = mm.parse_registered_migrations(mig)

    vals_not_in_array = registered - all_array
    array_not_vals = all_array - registered

    # MIGRATION_150_151 is in ALL but not as a val
    assert (150, 151) in array_not_vals, \
        f"Expected (150, 151) in array_not_vals, got {array_not_vals}"
    assert len(array_not_vals) >= 1


def test_baseline_fallback_to_lowest_registered(tmp_path):
    """When no baseline comment exists, fallback to lowest registered start version."""
    _make_app_database(tmp_path, 148)
    # No baseline comment — use fallback
    _make_migrations(tmp_path,
        [(146, 147), (147, 148)],
        baseline_text=""  # No baseline comment
    )

    mig = mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt")
    baseline, _ = mm.parse_baseline_version(mig)

    # Since no baseline comment, parse_baseline_version returns None
    assert baseline is None, \
        f"Expected baseline=None (no comment), got {baseline}"

    # The registered migrations start at 146
    registered, _, _ = mm.parse_registered_migrations(mig)
    fallback = min(s for s, e in registered)
    assert fallback == 146, f"Expected fallback baseline=146, got {fallback}"


def test_schema_version_parsing(tmp_path):
    """Schema JSON versions are correctly parsed from files on disk."""
    _write_schema_json(tmp_path, 145)
    _write_schema_json(tmp_path, 146)
    _write_schema_json(tmp_path, 148)

    versions = mm.parse_schema_versions(tmp_path)
    assert 145 in versions
    assert 146 in versions
    assert 148 in versions
    assert 147 not in versions  # intentionally not written

    # Non-JSON files should be ignored
    schema_dir = tmp_path / "app" / "schemas" / "com.example.database.AppDatabase"
    (schema_dir / "foo.txt").write_text("not json", encoding="utf-8")
    versions2 = mm.parse_schema_versions(tmp_path)
    assert versions == versions2, "Text files should not affect version parsing"


# ── Root-aware source resolution (build/ strays must never shadow) ──────────

def _write_stray_build_sources(tmp_path, app_db_version=150):
    """Write stray pytest-fixture-style copies under build/guard-debug/...

    Mirrors the verified defect: leftover guard-debug probe trees carry
    ``app/src/**`` copies of the exact production file names.  The stray
    AppDatabase declares a different version so that, if it ever shadowed
    the real source, the guard would report phantom missing migrations.
    """
    stray_dir = (
        tmp_path / "build" / "guard-debug" / "gr08d" / "pytest-tmp16"
        / "app" / "src" / "main" / "java" / "com" / "example"
    )
    stray_dir.mkdir(parents=True, exist_ok=True)
    (stray_dir / "AppDatabase.kt").write_text(
        f"const val APP_DATABASE_SCHEMA_VERSION = {app_db_version}\n",
        encoding="utf-8",
    )
    (stray_dir / "DatabaseMigrations.kt").write_text(
        "package com.example\n"
        "\n"
        "/** v145 is the baseline. */\n"
        "object DatabaseMigrations {\n"
        "    val MIGRATION_145_146 = object : Migration(145, 146) {}\n"
        "    val ALL: Array<Migration> = arrayOf(MIGRATION_145_146)\n"
        "}\n",
        encoding="utf-8",
    )
    return stray_dir


def test_find_kotlin_source_prefers_exact_known_production_path(tmp_path):
    """The exact known production path wins deterministically.

    A decoy copy under the production root whose package sorts BEFORE the
    known one would win a plain sorted fallback search; the known-path
    preference must outrank it (sorted-first applies only to the fallback).
    """
    known_dir = (
        tmp_path / "app" / "src" / "main" / "java" / "com" / "yourname"
        / "expensetracker" / "data" / "database"
    )
    known_dir.mkdir(parents=True)
    real = known_dir / "AppDatabase.kt"
    real.write_text("const val APP_DATABASE_SCHEMA_VERSION = 148\n", encoding="utf-8")

    decoy_dir = tmp_path / "app" / "src" / "main" / "java" / "com" / "aaa"
    decoy_dir.mkdir(parents=True)
    (decoy_dir / "AppDatabase.kt").write_text(
        "const val APP_DATABASE_SCHEMA_VERSION = 999\n", encoding="utf-8"
    )

    assert mm.find_kotlin_source(tmp_path, "AppDatabase.kt") == real


def test_find_kotlin_source_scoped_fallback_under_production_root(tmp_path):
    """Without a known production path, resolution falls back to a search
    scoped to app/src/main/java (fixture layouts keep working)."""
    real = _make_app_database(tmp_path, 148)

    resolved = mm.find_kotlin_source(tmp_path, "AppDatabase.kt")

    assert resolved == real
    assert mm.SOURCE_SUBDIR in resolved.relative_to(tmp_path).as_posix()


def test_find_kotlin_source_ignores_stray_build_tree_copies(tmp_path):
    """Stray fixture copies under build/ must never shadow real sources.

    Regression guard for the verified defect: the old whole-tree rglob could
    return a stale pytest-fixture copy under
    ``build/guard-debug/**/app/src/**`` (enumeration-order dependent),
    producing phantom missing migrations.  Resolution is now scoped to the
    production source root, so the real files are returned regardless of
    what sits under ``build/``.
    """
    real_app_db = _make_app_database(tmp_path, 148)
    real_mig = _make_migrations(
        tmp_path,
        [(145, 146), (146, 147), (147, 148)],
        baseline_text="v145 is the baseline",
    )
    _write_stray_build_sources(tmp_path)

    assert mm.find_kotlin_source(tmp_path, "AppDatabase.kt") == real_app_db
    assert mm.find_kotlin_source(tmp_path, "DatabaseMigrations.kt") == real_mig


def test_main_resolves_real_sources_despite_build_strays(tmp_path, monkeypatch, capsys):
    """End-to-end: real sources under app/src plus poisoned strays under
    build/ — the guard reports the real production files and passes."""
    _make_app_database(tmp_path, 148)
    _make_migrations(
        tmp_path,
        [(145, 146), (146, 147), (147, 148)],
        baseline_text="v145 is the baseline",
    )
    _make_schema_policy(tmp_path, baseline=145)
    _write_stray_build_sources(tmp_path)

    monkeypatch.setattr(sys, 'argv', [
        'verify_migration_matrix.py',
        '--root', str(tmp_path),
        '--fail-on-violation',
    ])

    with pytest.raises(SystemExit) as exc_info:
        mm.main()

    assert exc_info.value.code == 0
    out = capsys.readouterr().out
    latest_line = next(
        line for line in out.splitlines()
        if line.strip().startswith("Latest version:")
    )
    assert "v148" in latest_line, f"Real latest version not reported: {latest_line}"
    assert "build" not in latest_line, (
        f"Stray build/ path leaked into the report: {latest_line}"
    )


def test_missing_real_source_fails_controlled_despite_build_strays(
    tmp_path, monkeypatch, capsys
):
    """No production source under app/src/main/java -> controlled FATAL
    (exit 2), even when plausible-looking copies exist under build/.

    Fails before the fix: the whole-tree rglob resolved the stray copies,
    the guard parsed them happily and exited 0 instead of failing closed.
    """
    _write_stray_build_sources(tmp_path, app_db_version=148)

    monkeypatch.setattr(sys, 'argv', [
        'verify_migration_matrix.py',
        '--root', str(tmp_path),
    ])

    with pytest.raises(SystemExit) as exc_info:
        mm.main()

    assert exc_info.value.code == 2
    err = capsys.readouterr().err
    assert "Could not find AppDatabase.kt" in err


def test_parse_policy_versions_uses_explicit_app_database_path(tmp_path):
    """The explicitly resolved production AppDatabase.kt wins for delegate
    resolution — no sibling required, no project-wide search."""
    policy = _make_schema_policy(tmp_path, baseline=145)
    # No AppDatabase.kt sibling exists (only DatabaseSchemaPolicy.kt was
    # written); resolve the delegate from an explicit production path.
    app_db = (
        tmp_path / "app" / "src" / "main" / "java" / "com" / "other"
        / "AppDatabase.kt"
    )
    app_db.parent.mkdir(parents=True)
    app_db.write_text("const val APP_DATABASE_SCHEMA_VERSION = 148\n", encoding="utf-8")

    latest, baseline = mm.parse_policy_versions(policy, app_database_path=app_db)

    assert latest == 148, f"Expected latest=148, got {latest}"
    assert baseline == 145, f"Expected baseline=145, got {baseline}"


def test_parse_policy_versions_does_not_search_build_strays(tmp_path):
    """Without an explicit path or sibling, delegate resolution returns None
    instead of searching the project (a stray under build/ must not be
    picked up).

    Fails before the fix: the ancestor walk-up plus whole-tree rglob
    resolved the stray copy (latest=150) instead of returning None.
    """
    policy = _make_schema_policy(tmp_path, baseline=145)

    stray = (
        tmp_path / "build" / "app" / "src" / "main" / "java"
        / "AppDatabase.kt"
    )
    stray.parent.mkdir(parents=True)
    stray.write_text("const val APP_DATABASE_SCHEMA_VERSION = 150\n", encoding="utf-8")

    latest, baseline = mm.parse_policy_versions(policy)

    assert latest is None, f"Stray build/ copy was used as delegate: latest={latest}"
    assert baseline == 145, f"Expected baseline=145, got {baseline}"


# ── DatabaseSchemaPolicy.kt parsing (authoritative source) ──────────────────

def test_parse_policy_versions_unqualified_delegate(tmp_path):
    """The production policy file declares CURRENT_VERSION as an UNQUALIFIED
    delegate (DatabaseSchemaPolicy.kt: 'const val CURRENT_VERSION =
    APP_DATABASE_SCHEMA_VERSION'). parse_policy_versions must accept the
    unqualified form and still resolve the latest version through the
    AppDatabase.kt resolution chain.

    Fails before the fix: the regex required a qualified '<X>.' reference,
    so parsing returned (None, None) and the policy was silently ignored.
    """
    _make_app_database(tmp_path, 148)
    policy = _make_schema_policy(tmp_path, baseline=145)

    latest, baseline = mm.parse_policy_versions(policy)

    assert latest == 148, f"Expected latest=148, got {latest}"
    assert baseline == 145, f"Expected baseline=145, got {baseline}"


def test_parse_policy_versions_qualified_delegate(tmp_path):
    """A qualified delegate reference keeps parsing (backwards compatible)."""
    _make_app_database(tmp_path, 148)
    policy = _make_schema_policy(
        tmp_path, baseline=145,
        current_version_line=(
            "const val CURRENT_VERSION = "
            "AppDatabase.APP_DATABASE_SCHEMA_VERSION"),
    )

    latest, baseline = mm.parse_policy_versions(policy)

    assert latest == 148, f"Expected latest=148, got {latest}"
    assert baseline == 145, f"Expected baseline=145, got {baseline}"


def test_parse_policy_versions_lookalike_constant_not_matched(tmp_path):
    """A lookalike constant without a dot separator must NOT be treated as
    the delegate (guards against over-loosening the regex)."""
    _make_app_database(tmp_path, 148)
    policy = _make_schema_policy(
        tmp_path, baseline=145,
        current_version_line=(
            "const val CURRENT_VERSION = "
            "FOO_APP_DATABASE_SCHEMA_VERSION"),
    )

    latest, baseline = mm.parse_policy_versions(policy)

    assert latest is None, f"Expected latest=None, got {latest}"
    assert baseline is None, f"Expected baseline=None, got {baseline}"


def test_main_uses_unqualified_policy_as_authoritative(tmp_path, monkeypatch, capsys):
    """End-to-end: with the unqualified delegate form, the policy baseline is
    used as the authoritative source (reported with the 'authoritative'
    marker) instead of falling back to the DatabaseMigrations.kt comment."""
    _make_app_database(tmp_path, 148)
    _make_migrations(tmp_path,
        [(145, 146), (146, 147), (147, 148)],
        baseline_text="v145 is the baseline"
    )
    _make_schema_policy(tmp_path, baseline=145)

    monkeypatch.setattr(sys, 'argv', [
        'verify_migration_matrix.py',
        '--root', str(tmp_path),
    ])

    with pytest.raises(SystemExit) as exc_info:
        mm.main()

    assert exc_info.value.code == 0
    out = capsys.readouterr().out
    assert "authoritative" in out, (
        f"Policy baseline not used as authoritative source. Output:\n{out}"
    )


def test_all_present_modern_repo_integration():
    """Verify the actual production codebase has a complete migration matrix.

    This test checks the real DatabaseMigrations.kt and AppDatabase.kt in the
    repo, not fixtures.
    """
    project_root = os.path.join(os.path.dirname(__file__), "..")
    project_root = os.path.abspath(project_root)

    root = Path(project_root)
    # ``find_kotlin_source`` is root-aware: it resolves ONLY under
    # ``<root>/app/src/main/java`` (exact known production path first, then
    # a scoped sorted search).  The repository tree carries stale pytest
    # fixture copies of these exact file names under
    # ``build/**/app/src/**`` (leftover guard-debug probe trees); those
    # strays can no longer shadow the real production sources, so the
    # repository root is passed directly and the resolved paths are
    # asserted to live under the production root below.
    app_db_path = mm.find_kotlin_source(root, "AppDatabase.kt")
    mig_path = mm.find_kotlin_source(root, "DatabaseMigrations.kt")

    if app_db_path is None or mig_path is None:
        pytest.skip("Source files not found — not running in a source checkout?")

    # The resolved files must be the real production sources — never stray
    # copies under build/ trees.
    for resolved in (app_db_path, mig_path):
        rel = resolved.relative_to(root).as_posix()
        assert rel.startswith(mm.SOURCE_SUBDIR + "/"), (
            f"Source resolved outside the production root: {rel}"
        )

    latest = mm.parse_latest_version(app_db_path)
    baseline, _ = mm.parse_baseline_version(mig_path)

    if latest is None or baseline is None:
        pytest.fail("Could not parse versions from source files")

    registered, _, _ = mm.parse_registered_migrations(mig_path)

    # Cross-validate: every MIGRATION_N_M in ALL must have a val definition
    _, _, all_array = mm.parse_registered_migrations(mig_path)
    vals_not_in_array = registered - all_array
    array_not_vals = all_array - registered

    assert vals_not_in_array == set(), \
        f"Migrations defined but not in ALL array: {vals_not_in_array}"
    assert array_not_vals == set(), \
        f"Migrations in ALL but not defined as val: {array_not_vals}"

    missing = mm.compute_missing_migrations(baseline, latest, registered)
    assert missing == [], (
        f"Production codebase has {len(missing)} missing migration(s) "
        f"from v{baseline} to v{latest}:\n"
        + "\n".join(f"  v{s} → v{e}" for s, e in missing)
    )
