#!/usr/bin/env python3
"""
MIGRATION_MATRIX — Validates Room database migration coverage.

Checks:
  1. Every version from baseline to latest has a registered migration step
  2. No missing migration in the supported range
  3. Known intentional gaps are documented and excluded from failure

Authoritative source: DatabaseSchemaPolicy.kt (CURRENT_VERSION + MIGRATION_BASELINE).
Fallback: AppDatabase.kt (APP_DATABASE_SCHEMA_VERSION) and DatabaseMigrations.kt comment.
Registered migrations are parsed from DatabaseMigrations.kt.
Schema JSON files under app/schemas/ are used to verify version coverage.

Exit codes: 0 = all migrations present, 1 = missing migrations (with --fail-on-violation)

Rule ID: G-MIG-01
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


RULE_ID = "G-MIG-01"
DESCRIPTION = "Validates Room database migration coverage from baseline to latest"


def find_file(root: Path, filename: str) -> Optional[Path]:
    """Find a file by name anywhere under the given root directory."""
    for path in root.rglob(filename):
        if path.is_file():
            return path
    return None


def find_kotlin_source(root: Path, simple_name: str) -> Optional[Path]:
    """Find a Kotlin source file by its simple name (e.g. 'AppDatabase.kt')."""
    for path in root.rglob(simple_name):
        if path.is_file() and "src" in path.parts:
            return path
    return None


def parse_latest_version(app_database_path: Path) -> Optional[int]:
    """Extract APP_DATABASE_SCHEMA_VERSION from AppDatabase.kt."""
    try:
        content = app_database_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"ERROR reading {app_database_path}: {e}", file=sys.stderr)
        return None

    match = re.search(r"APP_DATABASE_SCHEMA_VERSION\s*=\s*(\d+)", content)
    if match:
        return int(match.group(1))
    return None


def parse_baseline_version(migrations_path: Path) -> Tuple[Optional[int], Optional[int]]:
    """Extract the baseline version from the comment in DatabaseMigrations.kt.

    Returns (baseline_version, baseline_line_number).
    """
    try:
        content = migrations_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"ERROR reading {migrations_path}: {e}", file=sys.stderr)
        return None, None

    # Look for comment like "v145 is the baseline"
    for i, line in enumerate(content.splitlines(), 1):
        if "baseline" in line.lower() and "v" in line.lower():
            match = re.search(r"v(\d+)", line)
            if match:
                return int(match.group(1)), i

    return None, None


def parse_policy_versions(policy_path: Path) -> Tuple[Optional[int], Optional[int]]:
    """Extract CURRENT_VERSION and MIGRATION_BASELINE from DatabaseSchemaPolicy.kt.

    This is the authoritative source — preferred over AppDatabase.kt and
    the baseline comment in DatabaseMigrations.kt.

    Returns (latest_version, baseline_version).
    """
    try:
        content = policy_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"ERROR reading {policy_path}: {e}", file=sys.stderr)
        return None, None

    latest_match = re.search(r"CURRENT_VERSION\s*=\s*\S+\.APP_DATABASE_SCHEMA_VERSION", content)
    baseline_match = re.search(r"MIGRATION_BASELINE\s*=\s*(\d+)", content)

    if not latest_match or not baseline_match:
        return None, None

    # Parse the actual integer values — CURRENT_VERSION is a constant delegate,
    # so we resolve it by also reading AppDatabase.kt if available.
    # But for simplicity, we parse the APP_DATABASE_SCHEMA_VERSION from the same file
    # by following the chain: CURRENT_VERSION → AppDatabase.APP_DATABASE_SCHEMA_VERSION
    baseline = int(baseline_match.group(1))

    # CURRENT_VERSION delegates to AppDatabase.APP_DATABASE_SCHEMA_VERSION.
    # Try to resolve from the policy file's parent directory.
    app_db_path = policy_path.parent / "AppDatabase.kt"
    latest = None
    if app_db_path.exists():
        latest = parse_latest_version(app_db_path)
    else:
        # Fallback: search the project
        root = policy_path
        while root.parent != root:
            root = root.parent
            candidate = root / "app" / "src"
            if candidate.exists():
                break
        for path in root.rglob("AppDatabase.kt"):
            if path.is_file() and "src" in path.parts:
                latest = parse_latest_version(path)
                break

    return latest, baseline


def parse_registered_migrations(migrations_path: Path) -> Tuple[
    Set[Tuple[int, int]],  # migrations as (startVersion, endVersion)
    Dict[Tuple[int, int], List[int]],  # migration -> line numbers in file
    Set[Tuple[int, int]],  # migrations found in ALL array
]:
    """Parse DatabaseMigrations.kt for registered MIGRATION_N_M objects and ALL array."""
    try:
        content = migrations_path.read_text(encoding="utf-8")
        lines = content.splitlines()
    except Exception as e:
        print(f"ERROR reading {migrations_path}: {e}", file=sys.stderr)
        return set(), {}, set()

    migrations: Set[Tuple[int, int]] = set()
    migration_lines: Dict[Tuple[int, int], List[int]] = {}
    all_array_migrations: Set[Tuple[int, int]] = set()

    # Parse val MIGRATION_N_M patterns
    for i, line in enumerate(lines, 1):
        match = re.search(r"MIGRATION[_\s]*(\d+)[_\s]*(\d+)", line)
        if match:
            start_ver = int(match.group(1))
            end_ver = int(match.group(2))
            key = (start_ver, end_ver)
            migrations.add(key)
            if key not in migration_lines:
                migration_lines[key] = []
            migration_lines[key].append(i)

    # Parse the ALL array for cross-validation
    # Look for the val ALL: Array<Migration> = arrayOf(...) block
    in_all = False
    all_content = ""
    for line in lines:
        if "val ALL" in line and "Array<Migration>" in line:
            in_all = True
        if in_all:
            all_content += line + "\n"
            if line.strip().endswith(")"):
                in_all = False

    # Extract MIGRATION_N_M references from the ALL array content
    if all_content:
        for match in re.finditer(r"MIGRATION[_\s]*(\d+)[_\s]*(\d+)", all_content):
            start_ver = int(match.group(1))
            end_ver = int(match.group(2))
            all_array_migrations.add((start_ver, end_ver))

    return migrations, migration_lines, all_array_migrations


def parse_schema_versions(root: Path) -> Set[int]:
    """Find all schema JSON files and extract their version numbers."""
    schema_dir = root / "app" / "schemas"
    versions: Set[int] = set()

    if not schema_dir.exists():
        return versions

    for json_file in schema_dir.rglob("*.json"):
        try:
            version = int(json_file.stem)
            versions.add(version)
        except ValueError:
            continue

    return versions


def compute_missing_migrations(
    baseline: int,
    latest: int,
    registered: Set[Tuple[int, int]],
) -> List[Tuple[int, int]]:
    """Compute which migrations are missing in the supported range [baseline, latest).

    For every version N from baseline to latest-1, there must be a migration N→N+1.
    """
    missing: List[Tuple[int, int]] = []
    for version in range(baseline, latest):
        step = (version, version + 1)
        if step not in registered:
            missing.append(step)
    return missing


def main():
    parser = argparse.ArgumentParser(
        description=f"{RULE_ID}: {DESCRIPTION}"
    )
    parser.add_argument(
        "--root", default=".",
        help="Project root directory (default: current directory)"
    )
    parser.add_argument(
        "--fail-on-violation", action="store_true",
        help="Exit with code 1 when missing migrations are detected"
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    # ── Locate source files ──────────────────────────────────────────
    app_db_path = find_kotlin_source(root, "AppDatabase.kt")
    if app_db_path is None:
        print(f"FATAL ({RULE_ID}): Could not find AppDatabase.kt under {root}",
              file=sys.stderr)
        sys.exit(2)

    mig_path = find_kotlin_source(root, "DatabaseMigrations.kt")
    if mig_path is None:
        print(f"FATAL ({RULE_ID}): Could not find DatabaseMigrations.kt under {root}",
              file=sys.stderr)
        sys.exit(2)

    policy_path = find_kotlin_source(root, "DatabaseSchemaPolicy.kt")

    # ── Parse versions ───────────────────────────────────────────────
    # Prefer DatabaseSchemaPolicy.kt as the authoritative source.
    # Fall back to AppDatabase.kt + DatabaseMigrations.kt comment if missing.
    latest_version: Optional[int] = None
    baseline_version: Optional[int] = None
    baseline_line: Optional[int] = None
    policy_source = False

    if policy_path is not None:
        policy_latest, policy_baseline = parse_policy_versions(policy_path)
        if policy_latest is not None and policy_baseline is not None:
            latest_version = policy_latest
            baseline_version = policy_baseline
            policy_source = True

    if latest_version is None:
        latest_version = parse_latest_version(app_db_path)
    if latest_version is None:
        print(
            f"FATAL ({RULE_ID}): Could not parse CURRENT_VERSION "
            f"from any source",
            file=sys.stderr
        )
        sys.exit(2)

    if baseline_version is None:
        baseline_version, baseline_line = parse_baseline_version(mig_path)
    if baseline_version is None:
        # Fallback: use the lowest startVersion among registered migrations
        registered, _, _ = parse_registered_migrations(mig_path)
        if registered:
            baseline_version = min(s for s, e in registered)
            print(
                f"WARNING ({RULE_ID}): No baseline found in "
                f"DatabaseSchemaPolicy.kt or DatabaseMigrations.kt. "
                f"Using lowest registered migration start version "
                f"v{baseline_version} as baseline.",
                file=sys.stderr,
            )
        else:
            print(
                f"FATAL ({RULE_ID}): No baseline version found and no "
                f"registered migrations to fall back on.",
                file=sys.stderr,
            )
            sys.exit(2)

    # ── Parse migrations ─────────────────────────────────────────────
    registered, migration_lines, all_array = parse_registered_migrations(mig_path)

    # Cross-validate: migrations in ALL but not as standalone val
    vals_not_in_array = registered - all_array
    array_not_vals = all_array - registered

    # ── Schema versions ──────────────────────────────────────────────
    schema_versions = parse_schema_versions(root)

    # ── Build human-readable path references ─────────────────────────
    mig_rel = str(mig_path.relative_to(root)) if mig_path.is_relative_to(root) else str(mig_path)
    app_db_rel = str(app_db_path.relative_to(root)) if app_db_path.is_relative_to(root) else str(app_db_path)
    policy_rel = (
        str(policy_path.relative_to(root))
        if policy_path is not None and policy_path.is_relative_to(root)
        else (str(policy_path) if policy_path is not None else None)
    )

    # ── Compute expected migrations ──────────────────────────────────
    missing = compute_missing_migrations(baseline_version, latest_version, registered)

    # ── Identify known gaps (versions below baseline) ────────────────
    known_gaps: List[Tuple[int, int]] = []
    for version in range(1, baseline_version):
        step = (version, version + 1)
        if step not in registered:
            known_gaps.append(step)

    # ── Report results ───────────────────────────────────────────────
    exit_code = 0
    has_issues = False

    # Header
    print(f"{RULE_ID} -- Migration Matrix Verification")
    print(f"  Project root:   {root}")
    print(f"  Latest version: v{latest_version}  ({app_db_rel})")
    if policy_source and policy_rel is not None:
        print(f"  Baseline:       v{baseline_version}  ({policy_rel} — authoritative)")
    elif baseline_line is not None:
        print(f"  Baseline:       v{baseline_version}  ({mig_rel}:{baseline_line})")
    else:
        print(f"  Baseline:       v{baseline_version}  (inferred from registered migrations)")
    print(f"  Registered:     {len(registered)} migrations")
    print(f"  Schema JSONs:   {len(schema_versions)} versions "
           f"(v{min(schema_versions) if schema_versions else 'N/A'} -- "
          f"v{max(schema_versions) if schema_versions else 'N/A'})")
    print()

    # Show registered migrations
    print("Registered migrations:")
    for start, end in sorted(registered):
        line_refs = ", ".join(
            f"{mig_rel}:{ln}" for ln in migration_lines.get((start, end), [])
        )
        print(f"  v{start} -> v{end}  ({line_refs})")
    print()

    # Cross-validation warnings (non-fatal)
    if vals_not_in_array:
        print("WARNING: Migrations defined but NOT in ALL array:")
        for start, end in sorted(vals_not_in_array):
            print(f"  MIGRATION_{start}_{end} -- add to ALL array")
        print()

    if array_not_vals:
        print("WARNING: Migrations in ALL array but NOT defined as val:")
        for start, end in sorted(array_not_vals):
            print(f"  MIGRATION_{start}_{end} -- define val or remove from ALL")
        print()

    # Known gaps (informational)
    if known_gaps:
        gap_start = min(s for s, e in known_gaps)
        gap_end = max(e for s, e in known_gaps)
        print(
            f"INFO: Known intentional gaps — versions v{gap_start} to "
            f"v{gap_end} are below the migration baseline (v{baseline_version}) "
            f"and are explicitly unsupported ({len(known_gaps)} steps)."
        )
        print("  These pre-baseline versions require destructive migration or "
              "legacy import paths.")
        print()

    # Schema coverage info
    if schema_versions:
        schema_gaps = set(range(min(schema_versions), max(schema_versions) + 1)) - schema_versions
        if schema_gaps:
            print(
                f"INFO: Schema JSON versions with gaps: "
                f"{sorted(schema_gaps)}"
            )
            print("  Schema JSON gaps are informational — only the migration "
                  "chain determines correctness.")
            print()

    # Missing migrations
    if missing:
        has_issues = True
        print(f"VIOLATIONS FOUND ({RULE_ID}): {len(missing)} missing migration(s)")
        print()
        for start, end in sorted(missing):
            # Determine if this gap has a schema file on either side
            has_start_schema = start in schema_versions
            has_end_schema = end in schema_versions
            schema_info = ""
            if has_start_schema or has_end_schema:
                parts = []
                if has_start_schema:
                    parts.append(f"v{start} schema")
                if has_end_schema:
                    parts.append(f"v{end} schema")
                schema_info = f" (schema JSONs exist: {', '.join(parts)})"

            print(
                f"  {RULE_ID} {mig_rel}: "
                f"MISSING MIGRATION v{start} -> v{end}{schema_info}"
            )
            print(
                f"    Hint: Create MIGRATION_{start}_{end} in {mig_rel} "
                f"and add it to the ALL array."
            )
        print()

    if not has_issues:
        print(f"PASS ({RULE_ID}): All {len(registered)} migration(s) present "
              f"from v{baseline_version} to v{latest_version}.")
        print(f"  Supported range: v{baseline_version} -> v{latest_version} "
              f"({latest_version - baseline_version} steps)")
        print(f"  Schema JSON coverage: "
              f"{len(schema_versions)} versions")
        print()
        sys.exit(0)

    if args.fail_on_violation:
        print(f"FAIL ({RULE_ID}): Migration matrix is incomplete. "
              f"{len(missing)} migration(s) missing.",
              file=sys.stderr)
        sys.exit(1)
    else:
        print(
            f"WARNING ({RULE_ID}): {len(missing)} migration(s) missing "
            f"(run with --fail-on-violation to fail CI).",
            file=sys.stderr,
        )
        sys.exit(0)


if __name__ == "__main__":
    main()
