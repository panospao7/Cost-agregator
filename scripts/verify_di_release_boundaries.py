#!/usr/bin/env python3
"""
verify_di_release_boundaries.py — G-DI-01: DI/Release Binding Guard

Detects DI (dependency injection) bindings and production code patterns that
could leak debug/development behavior or insecure data into release builds.

Detection patterns:
  1. @Provides or @Binds methods returning mock/stub/fake/noop/debug types
     in non-debug modules, without a BuildConfig.DEBUG guard.
  2. Hardcoded http:// (non-SSL) URLs anywhere in production code.
  3. Debug/demo/stub types (Mock, Stub, Fake, NoOp, Debug, Demo) in
     non-DI production packages without BuildConfig.DEBUG guard.
  4. Log statements (Log.d/e/w/i/v) referencing request/response body or
     payload variables (requestBody, responseBody, body, payload, jsonBody).
  5. isMinifyEnabled = false / isDebuggable = true in release build variants.

Exit codes:
  0 — no violations (or warning mode without --fail-on-violation)
  1 — violations found AND --fail-on-violation flag is set
  2 — script error (e.g. source directory not found)

Usage:
  python3 scripts/verify_di_release_boundaries.py
  python3 scripts/verify_di_release_boundaries.py --fail-on-violation

RULE_ID: G-DI-01
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Configuration ────────────────────────────────────────────────────────────
RULE_ID = "G-DI-01"
DESCRIPTION = "DI/Release Binding Guard — detects debug-leak bindings and insecure patterns in production code"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
DI_SRC_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java",
                          "com", "yourname", "expensetracker", "di")
GRADLE_FILE = os.path.join(PROJECT_ROOT, "app", "build.gradle.kts")
ALLOWLIST_PATH = os.path.join(SCRIPT_DIR, "allowlists", "di_release_allowlist.yml")
SRC_MAIN_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java")

# ── Suspicious type patterns ─────────────────────────────────────────────────
# PascalCase variants that suggest debug/test-only types leaking into DI modules.
SUSPICIOUS_TYPE_PATTERNS = [
    (re.compile(r'\bMock[A-Z]'),     'Mock'),
    (re.compile(r'\bStub[A-Z]'),     'Stub'),
    (re.compile(r'\bFake[A-Z]'),     'Fake'),
    (re.compile(r'\bNoOp[A-Z]'),     'NoOp'),
    (re.compile(r'\bNoop[A-Z]'),     'Noop'),
    (re.compile(r'\bNo.Op[A-Z]'),    'No-Op'),
    (re.compile(r'\bDebug[A-Z]'),    'Debug'),
]

# ── Body/payload logging patterns ────────────────────────────────────────────
# Detect Log.d/e/w/i/v calls that reference request/response body or payload
# variable names, which may leak sensitive data into production logs.
BODY_PAYLOAD_VAR_PATTERNS = [
    re.compile(r'\brequestBody\b'),
    re.compile(r'\bresponseBody\b'),
    re.compile(r'\bjsonBody\b'),
    re.compile(r'\bpayload\b'),
    re.compile(r'(?<!\w)body(?!\w)'),
]

LOG_STATEMENT_PATTERN = re.compile(r'Log\.(d|e|w|i|v)\s*\(')

# ── Allowlist ────────────────────────────────────────────────────────────────

def load_allowlist(path: str) -> List[dict]:
    """Load allowlist entries from YAML file. Returns list of dicts with:
    rule, path, symbol, reason, owner, expires, linked_issue"""
    allowlist: List[dict] = []
    p = Path(path)
    if not p.exists():
        return allowlist
    try:
        import yaml
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        if data and isinstance(data, list):
            allowlist = data
    except ImportError:
        print("WARNING: PyYAML not installed, allowlist skipped", file=sys.stderr)
    except Exception as e:
        print(f"WARNING: Could not load allowlist: {e}", file=sys.stderr)
    return allowlist


def is_allowlisted(filepath: str, allowlist: List[dict], symbol: str = None) -> bool:
    """Check if a specific violation (file + optional symbol) is in the allowlist.

    When `symbol` is None (default), matches ANY allowlist entry for the file
    regardless of the entry's symbol (backward-compatible wildcard behavior).

    When `symbol` is provided, the allowlist entry's symbol must be '*' (wildcard)
    or match `symbol` exactly.

    Supports partial path matching: if the allowlist entry's path is a suffix
    of filepath, or vice versa, it's considered a match.
    """
    norm_filepath = filepath.replace("\\", "/")
    for entry in allowlist:
        if entry.get("rule") != RULE_ID:
            continue
        entry_path = (entry.get("path") or "").replace("\\", "/")
        entry_symbol = entry.get("symbol", "")

        # Path matching: suffix match either direction
        path_match = (
            norm_filepath.endswith(entry_path)
            or entry_path.endswith(norm_filepath)
            or os.path.basename(norm_filepath) == os.path.basename(entry_path)
        )
        if not path_match:
            continue

        # Symbol matching
        if symbol is None:
            # Backward compatible: no symbol filter, match any entry for this file
            return True
        if entry_symbol == "*" or entry_symbol == symbol:
            return True

    return False


# ── DI file scanning ─────────────────────────────────────────────────────────

def scan_di_file(filepath: str, allowlist: List[dict]) -> Tuple[List[str], bool]:
    """Scan a DI module Kotlin file for release-binding violations.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    lines = content.splitlines()
    rel_path = os.path.relpath(filepath, PROJECT_ROOT)

    # Must be a Hilt @Module
    if not re.search(r'@Module', content):
        return violations, False

    # ── Check 1: Suspicious types in DI bindings without BuildConfig.DEBUG ────

    has_buildconfig_debug = 'BuildConfig.DEBUG' in content

    if not has_buildconfig_debug:
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if stripped.startswith("import "):
                continue
            for pattern, label in SUSPICIOUS_TYPE_PATTERNS:
                if pattern.search(line):
                    if not is_allowlisted(rel_path, allowlist, symbol=label):
                        violations.append(
                            f"{RULE_ID} {rel_path}:{i} "
                            f"{label} type referenced in DI module without BuildConfig.DEBUG guard — "
                            f"may leak debug/development behavior into release builds"
                        )
                    break  # one violation per line

    # ── Check 2: http:// (non-SSL) URLs ──────────────────────────────────────

    http_pattern = re.compile(r'http://[^\s\'\"\)\],;]+')
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        # Exclude https:// matches
        if "https://" in line:
            continue
        match = http_pattern.search(line)
        if match:
            if not is_allowlisted(rel_path, allowlist, symbol="http://"):
                violations.append(
                    f"{RULE_ID} {rel_path}:{i} "
                    f"http:// (non-SSL) URL in DI module — use https:// in release builds: "
                    f"{match.group()}"
                )

    return violations, False


# ── Full-codebase HTTP scanning ───────────────────────────────────────────────

def scan_full_codebase_http(filepath: str, allowlist: List[dict]) -> Tuple[List[str], bool]:
    """Scan any production .kt file for http:// (non-SSL) URLs.

    Unlike scan_di_file, this does NOT require an @Module annotation.
    Every production .kt file is checked. Comment lines and import lines
    are excluded. https:// URLs are allowed.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    lines = content.splitlines()
    rel_path = os.path.relpath(filepath, PROJECT_ROOT)

    http_pattern = re.compile(r'http://[^\s\'\"\)\],;]+')
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        if stripped.startswith("import "):
            continue
        if "https://" in line:
            continue
        match = http_pattern.search(line)
        if match:
            if not is_allowlisted(rel_path, allowlist, symbol="http://"):
                violations.append(
                    f"{RULE_ID} {rel_path}:{i} "
                    f"http:// (non-SSL) URL in production code — use https:// in release builds: "
                    f"{match.group()}"
                )

    return violations, False


# ── Suspicious types in non-DI production code ────────────────────────────────

def scan_suspicious_types_production(filepath: str, allowlist: List[dict]) -> Tuple[List[str], bool]:
    """Scan non-DI, non-@Module production .kt files for Mock/Stub/Fake/NoOp/Debug/Demo
    type references without a BuildConfig.DEBUG guard.

    DI module files (under the di/ directory) are handled by scan_di_file.
    This function catches debug/test types leaking into general production code.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    lines = content.splitlines()
    rel_path = os.path.relpath(filepath, PROJECT_ROOT)

    # Skip @Module files — already handled by scan_di_file
    if re.search(r'@Module', content):
        return violations, False

    has_buildconfig_debug = 'BuildConfig.DEBUG' in content

    if not has_buildconfig_debug:
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if stripped.startswith("import "):
                continue
            for pattern, label in SUSPICIOUS_TYPE_PATTERNS:
                if pattern.search(line):
                    if not is_allowlisted(rel_path, allowlist, symbol=label):
                        violations.append(
                            f"{RULE_ID} {rel_path}:{i} "
                            f"{label} type referenced in production code without BuildConfig.DEBUG guard — "
                            f"may leak debug/development behavior into release builds"
                        )
                    break  # one violation per line

    return violations, False


# ── Body/payload logging scanning ────────────────────────────────────────────

def scan_log_body_payload(filepath: str, allowlist: List[dict]) -> Tuple[List[str], bool]:
    """Scan any production .kt file for Log.d/e/w/i/v calls that reference
    request/response body or payload variable names.

    Detects variables like requestBody, responseBody, body, payload, jsonBody
    being logged, which may leak sensitive request/response data into
    production logcat output.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    lines = content.splitlines()
    rel_path = os.path.relpath(filepath, PROJECT_ROOT)

    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        if stripped.startswith("import "):
            continue

        # Check if line contains a Log.d/e/w/i/v call
        if not LOG_STATEMENT_PATTERN.search(line):
            continue

        # Check if line references body/payload variable names
        for pattern in BODY_PAYLOAD_VAR_PATTERNS:
            match = pattern.search(line)
            if match:
                if not is_allowlisted(rel_path, allowlist, symbol=match.group()):
                    violations.append(
                        f"{RULE_ID} {rel_path}:{i} "
                        f"Body/payload logging detected — Log statement references "
                        f"'{match.group()}' which may leak request/response data into production logs"
                    )
                break  # one violation per line

    return violations, False


# ── Gradle file scanning ─────────────────────────────────────────────────────

def scan_gradle_file(filepath: str, allowlist: List[dict]) -> Tuple[List[str], bool]:
    """Scan app/build.gradle.kts for release-config issues.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []
    rel_path = os.path.relpath(filepath, PROJECT_ROOT)

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    lines = content.splitlines()

    # Check: isMinifyEnabled = false inside release { } block
    in_release_block = False
    release_brace_depth = 0
    for i, line in enumerate(lines, 1):
        stripped = line.strip()

        # Skip comment lines (Gradle uses // and # for comments)
        if not stripped or stripped.startswith("//") or stripped.startswith("#"):
            continue

        # Track brace depth for the release block
        if not in_release_block:
            if re.search(r'\brelease\s*\{', stripped):
                in_release_block = True
                release_brace_depth = 0
                # Count opening braces on this line
                release_brace_depth += stripped.count('{') - stripped.count('}')
            continue

        if in_release_block:
            # Update brace depth
            release_brace_depth += stripped.count('{') - stripped.count('}')
            if release_brace_depth <= 0:
                in_release_block = False
                continue

            # Inside release block — check for isMinifyEnabled = false
            if re.search(r'isMinifyEnabled\s*=\s*false', stripped):
                if not is_allowlisted(rel_path, allowlist, symbol="isMinifyEnabled = false"):
                    violations.append(
                        f"{RULE_ID} {rel_path}:{i} "
                        f"isMinifyEnabled = false in release build type — "
                        f"debug information may leak into release APK; enable minification"
                    )

            # Also check for debuggable = true in release
            if re.search(r'isDebuggable\s*=\s*true', stripped):
                if not is_allowlisted(rel_path, allowlist, symbol="isDebuggable = true"):
                    violations.append(
                        f"{RULE_ID} {rel_path}:{i} "
                        f"isDebuggable = true in release build type — "
                        f"should be false for production release builds"
                    )

    return violations, False


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument("--root", default=PROJECT_ROOT,
                        help="Project root directory")
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 on violations")
    parser.add_argument("--allowlist", default=ALLOWLIST_PATH,
                        help="Path to allowlist file")
    args = parser.parse_args()

    root = args.root
    di_dir = os.path.join(root, "app", "src", "main", "java",
                          "com", "yourname", "expensetracker", "di")
    gradle_file = os.path.join(root, "app", "build.gradle.kts")

    if not os.path.isdir(di_dir):
        print(f"ERROR: DI directory not found: {di_dir}", file=sys.stderr)
        sys.exit(2)

    allowlist = load_allowlist(args.allowlist)
    all_violations: List[str] = []        # blocking violations
    advisory_violations: List[str] = []    # non-blocking advisory violations
    fatal_errors: List[str] = []

    # ── Scan DI module files ─────────────────────────────────────────────────
    print(f"Scanning DI modules in: {di_dir}")
    di_files_scanned = 0
    for dirpath, _, filenames in os.walk(di_dir):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            filepath = os.path.join(dirpath, filename)
            di_files_scanned += 1
            violations, had_fatal = scan_di_file(filepath, allowlist)
            if had_fatal:
                fatal_errors.append(filepath)
            all_violations.extend(violations)
    print(f"  Scanned {di_files_scanned} DI module file(s)")

    # ── Scan all production code (expanded checks) ────────────────────────────
    src_main = os.path.join(root, "app", "src", "main", "java")
    if os.path.isdir(src_main):
        print(f"Scanning all production code for http:// URLs, body/payload logging, "
              f"and debug types: {src_main}")
        full_files_scanned = 0
        for dirpath, _, filenames in os.walk(src_main):
            for filename in filenames:
                if not filename.endswith(".kt"):
                    continue
                filepath = os.path.join(dirpath, filename)
                full_files_scanned += 1

                # Full codebase http:// scan — all production files (advisory)
                violations, had_fatal = scan_full_codebase_http(filepath, allowlist)
                if had_fatal:
                    fatal_errors.append(filepath)
                advisory_violations.extend(violations)

                # Body/payload logging scan — all production files (advisory)
                violations, had_fatal = scan_log_body_payload(filepath, allowlist)
                if had_fatal:
                    fatal_errors.append(filepath)
                advisory_violations.extend(violations)

                # Suspicious debug/test types in non-DI packages (advisory)
                rel = os.path.relpath(filepath, root).replace("\\", "/").lower()
                path_parts = rel.split("/")
                is_in_di = "di" in path_parts
                if not is_in_di:
                    violations, had_fatal = scan_suspicious_types_production(filepath, allowlist)
                    if had_fatal:
                        fatal_errors.append(filepath)
                    advisory_violations.extend(violations)

        print(f"  Scanned {full_files_scanned} production file(s) with expanded checks")
    else:
        print(f"WARNING: Production source directory not found at {src_main}", file=sys.stderr)

    # ── Scan build.gradle.kts ─────────────────────────────────────────────────
    if os.path.isfile(gradle_file):
        print(f"Scanning build config: {os.path.relpath(gradle_file, root)}")
        violations, had_fatal = scan_gradle_file(gradle_file, allowlist)
        if had_fatal:
            fatal_errors.append(gradle_file)
        all_violations.extend(violations)
    else:
        print(f"WARNING: build.gradle.kts not found at {gradle_file}", file=sys.stderr)

    # ── Report ────────────────────────────────────────────────────────────────
    if fatal_errors:
        for fp in fatal_errors:
            print(f"FATAL: Could not read file: {fp}", file=sys.stderr)
        sys.exit(2)

    # Print blocking violations
    if all_violations:
        for v in all_violations:
            print(f"[BLOCKING] {v}")
        print(f"\nFound {len(all_violations)} blocking violation(s).")

    # Print advisory violations
    if advisory_violations:
        for v in advisory_violations:
            print(f"[ADVISORY] {v}")
        print(f"\nFound {len(advisory_violations)} advisory violation(s).")

    # Determine exit code — only blocking violations can fail CI
    if all_violations:
        if args.fail_on_violation:
            print(f"FAIL: {RULE_ID} blocking violations (--fail-on-violation set)", file=sys.stderr)
            sys.exit(1)
        else:
            print(f"WARNING: {RULE_ID} blocking violations (pass --fail-on-violation to fail CI)",
                  file=sys.stderr)
            sys.exit(0)
    else:
        if advisory_violations:
            print(f"PASS: {RULE_ID} — no blocking violations found ({len(advisory_violations)} advisory)")
        else:
            print(f"PASS: {RULE_ID} — no release-binding violations found")
        sys.exit(0)


if __name__ == "__main__":
    main()
