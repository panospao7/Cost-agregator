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


def test_all_present_modern_repo_integration():
    """Verify the actual production codebase has a complete migration matrix.

    This test checks the real DatabaseMigrations.kt and AppDatabase.kt in the
    repo, not fixtures.
    """
    project_root = os.path.join(os.path.dirname(__file__), "..")
    project_root = os.path.abspath(project_root)

    root = Path(project_root)
    app_db_path = mm.find_kotlin_source(root, "AppDatabase.kt")
    mig_path = mm.find_kotlin_source(root, "DatabaseMigrations.kt")

    if app_db_path is None or mig_path is None:
        pytest.skip("Source files not found — not running in a source checkout?")

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
