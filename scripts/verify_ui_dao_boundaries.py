#!/usr/bin/env python3
"""
verify_ui_dao_boundaries.py
G-UI-DAO-01 — UI/ViewModel DAO Boundary Guard

Scans the declared production Kotlin source scope (the roots of the
checked-in manifest ``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py`` — currently
``app/src/main/java``), enumerating EVERY declared production file first
and then applying the guard-specific UI/ViewModel semantic filter, for:
  1. ViewModels or UI code directly injecting/calling mutating DAOs.
  2. Files in ui/** paths containing @Inject ...Dao or dao.var calls with
     insert/update/delete.
  3. Files named *ViewModel.kt that import *Dao classes.
  4. @Composable functions in ui/screens/ with direct DAO mutation calls.

Exit codes:
  0 — no violations (or warning mode without --fail-on-violation)
  1 — violations found AND --fail-on-violation flag is set
  2 — script error (e.g. production source scope unresolved, source
      directory not found)

Usage:
  python3 scripts/verify_ui_dao_boundaries.py
  python3 scripts/verify_ui_dao_boundaries.py --fail-on-violation
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Optional, Tuple, Dict

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Configuration ──────────────────────────────────────────────────
RULE_ID = "G-UI-DAO-01"
DESCRIPTION = "UI/ViewModel DAO Boundary Guard"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)

ALLOWLIST_PATH = os.path.join(SCRIPT_DIR, "allowlists", "ui_dao_allowlist.yml")

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# ── Regex patterns ─────────────────────────────────────────────────

# DAO mutation methods — insert, update, delete variants
DAO_MUTATION_RE = re.compile(
    r'\.(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|'
    r'set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|'
    r'bulkRename|approve|reject|disconnect|syncTransactions|purge)\s*\('
)

# DAO read-only access (queries) — flagged but at a lower severity
DAO_READ_RE = re.compile(
    r'\.(?:get(?:All)?|find|query|fetch|load|count|search|check|exists|'
    r'is|select|stream|flow|observe)\w*\s*\('
)

# `@Inject ...Dao` in constructor or field injection
INJECT_DAO_RE = re.compile(
    r'(?:@Inject\s+)?(?:private\s+)?(?:val|var)\s+\w+\s*:\s*\w*Dao\b'
)

# Constructor-based injection: @Inject constructor(... Dao)
CONSTRUCTOR_DAO_RE = re.compile(
    r'@Inject\s+constructor\s*\([^)]*Dao\b'
)

# `import ...Dao` in ViewModel files
IMPORT_DAO_RE = re.compile(r'^import\s+.*Dao\s*$', re.MULTILINE)

# @Composable detection
COMPOSABLE_RE = re.compile(r'@Composable\s*\n\s*fun\s+(\w+)\s*\(')

# Direct DAO chain: database.someDao().mutate(...)
DIRECT_CHAIN_DAO_MUTATE_RE = re.compile(
    r'\w+\.\w*Dao\s*\(\s*\)\s*\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|bulkRename|approve|reject|disconnect|purge)\s*\('
)

# Track `val someVar = database.someDao()` local DAO assignments
LOCAL_DAO_ASSIGN_RE = re.compile(r'\b(?:val|var)\s+(\w+Dao\w*)\s*=\s*\w+\.\w*Dao\s*\(')

# ── Allowlist ──────────────────────────────────────────────────────

def load_allowlist(path: str) -> List[dict]:
    """Load allowlist entries from YAML file.

    Exits with code 2 on infrastructure errors (missing PyYAML, malformed YAML).
    """
    allowlist: List[dict] = []
    if not os.path.exists(path):
        return allowlist
    try:
        import yaml
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        if data and isinstance(data, list):
            allowlist = data
    except ImportError:
        print("ERROR: PyYAML not installed. pip install pyyaml", file=sys.stderr)
        sys.exit(2)
    except yaml.YAMLError as e:
        print(f"ERROR: Malformed allowlist: {e}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"ERROR: Could not load allowlist: {e}", file=sys.stderr)
        sys.exit(2)
    return allowlist


def is_allowlisted_file(rel_path: str, allowlist: List[dict]) -> bool:
    """Check if a file is in the allowlist."""
    for entry in allowlist:
        if entry.get("rule") != RULE_ID:
            continue
        entry_path = entry.get("path", "")
        # Normalize paths for comparison
        norm_rel = rel_path.replace("\\", "/")
        norm_entry = entry_path.replace("\\", "/")
        # Only match if the allowlisted path is a suffix of the actual file path
        if norm_rel.endswith(norm_entry):
            return True
    return False


# ── Violation detection ────────────────────────────────────────────

def violation(rel_path: str, lineno: int, reason: str, line: str = "") -> str:
    """Format a violation string."""
    return f"{RULE_ID} {rel_path}:{lineno} {reason}"


def scan_file(filepath: str, rel_path: str, allowlist: List[dict]) -> List[str]:
    """Scan a single file for G-UI-DAO-01 violations."""
    violations: List[str] = []

    # Skip allowlisted files entirely
    if is_allowlisted_file(rel_path, allowlist):
        return violations

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except OSError:
        return violations

    lines = content.splitlines()
    filename = os.path.basename(filepath)

    # Determine file category
    is_ui_file = "ui" in rel_path.replace("\\", "/").split("/")
    is_viewmodel = filename.endswith("ViewModel.kt")
    is_composable_file = "ui" in rel_path.replace("\\", "/").split("/") and "@Composable" in content

    # Not a UI or ViewModel file at all — skip
    if not is_ui_file and not is_viewmodel:
        return violations

    # --- Check 1: @Inject constructor with Dao parameter ---
    if CONSTRUCTOR_DAO_RE.search(content):
        # Multi-line aware: find the entire constructor block
        found_violation = False
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            if found_violation:
                break
            if "@Inject" in line and "constructor" in line:
                # Check if Dao is on the same line
                if re.search(r'Dao\b', line):
                    violations.append(
                        violation(rel_path, i,
                                  "@Inject constructor injects Dao directly in UI layer — "
                                  "use repository/lifecycle coordinator instead",
                                  line.strip()[:120])
                    )
                    found_violation = True
                else:
                    # Multi-line constructor: scan ahead for Dao param
                    constructor_block = _extract_paren_block(lines, i - 1)
                    if re.search(r'Dao\b', constructor_block):
                        violations.append(
                            violation(rel_path, i,
                                      "@Inject constructor injects Dao directly in UI layer — "
                                      "use repository/lifecycle coordinator instead",
                                      line.strip()[:120])
                        )
                        found_violation = True

    # --- Check 2: Import *Dao in ViewModel files ---
    if is_viewmodel:
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("//"):
                continue
            if IMPORT_DAO_RE.match(stripped):
                violations.append(
                    violation(rel_path, i,
                              "ViewModel imports Dao class directly — "
                              "use repository abstraction",
                              stripped[:120])
                )

    # --- Check 3: Direct DAO mutation in UI files ---
    if is_ui_file:
        # Track local DAO variable assignments
        local_dao_vars: set = set()
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue

            # Track local DAO assignments
            m = LOCAL_DAO_ASSIGN_RE.search(line)
            if m:
                local_dao_vars.add(m.group(1))

            # Check for direct chain: database.someDao().mutate(...)
            if DIRECT_CHAIN_DAO_MUTATE_RE.search(line) and "//" not in stripped[:stripped.index("(") if "(" in stripped else len(stripped)]:
                violations.append(
                    violation(rel_path, i,
                              "Direct DAO chain mutation in UI layer — "
                              "use repository/lifecycle coordinator",
                              stripped[:120])
                )

            # Check for DAO mutation (any Dao ref + mutation method)
            if has_dao_mutation(line, local_dao_vars):
                violations.append(
                    violation(rel_path, i,
                              "DAO mutation call in UI layer — "
                              "use repository/lifecycle coordinator",
                              stripped[:120])
                )

    # --- Check 4: @Composable functions with direct DAO calls ---
    if is_ui_file:
        composable_functions = find_composable_functions(content)
        for func_name, start_lineno in composable_functions:
            # Extract function body roughly
            body_lines = extract_function_body(lines, start_lineno - 1)
            for j, bline in enumerate(body_lines):
                # Check for Dao mutation in composable
                if DAO_MUTATION_RE.search(bline):
                    actual_lineno = start_lineno + j
                    if actual_lineno <= len(lines):
                        violations.append(
                            violation(rel_path, actual_lineno,
                                      f"@Composable function '{func_name}' contains direct DAO mutation — "
                                      "Compose functions must not call DAOs directly",
                                      bline.strip()[:120])
                        )

    return violations


def has_dao_mutation(line: str, local_dao_vars: set) -> bool:
    """Check if a line contains a DAO mutation call."""
    # Must have a mutation method
    if not DAO_MUTATION_RE.search(line):
        return False
    # Must reference a Dao
    if "Dao" in line:
        return True
    # Check local dao vars
    for var in local_dao_vars:
        if var in line and '.' in line.split(var, 1)[1]:
            return True
    return False


def find_composable_functions(content: str) -> List[Tuple[str, int]]:
    """Find @Composable functions and return (name, line_number)."""
    funcs = []
    for i, line in enumerate(content.splitlines()):
        if re.match(r'\s*@Composable\s*$', line):
            # The next line should be the fun declaration
            funcs.append((None, i + 1))  # placeholder
    # Find actual fun declarations following @Composable annotations
    all_lines = content.splitlines()
    result = []
    for j, line in enumerate(all_lines):
        stripped = line.strip()
        # Match @Composable on its own line OR inline: @Composable fun Name(...)
        m_composable_inline = re.match(r'@Composable\s+fun\s+(\w+)\s*\(', stripped)
        if m_composable_inline:
            result.append((m_composable_inline.group(1), j + 1))
        elif re.match(r'\s*@Composable\s*$', stripped):
            # Look ahead for fun declaration
            for k in range(j + 1, min(j + 5, len(all_lines))):
                next_line = all_lines[k].strip()
                m_fun = re.match(r'(?:private\s+)?fun\s+(\w+)\s*\(', next_line)
                if m_fun:
                    result.append((m_fun.group(1), j + 1))
                    break
    return result


def extract_function_body(lines: List[str], start_idx: int, max_lookahead: int = 80) -> List[str]:
    """Extract the body of a function starting at line index start_idx."""
    body_lines = []
    depth = 0
    started = False
    for i in range(start_idx, min(start_idx + max_lookahead, len(lines))):
        line = lines[i]
        for ch in line:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
        body_lines.append(line)
        if started and depth == 0:
            break
    return body_lines


def _extract_paren_block(lines: List[str], start_idx: int, max_lookahead: int = 30) -> str:
    """Extract text from an opening '(' through its matching ')' across lines."""
    block_lines = []
    depth = 0
    started = False
    for i in range(start_idx, min(start_idx + max_lookahead, len(lines))):
        line = lines[i]
        for ch in line:
            if ch == '(':
                depth += 1
                started = True
            elif ch == ')':
                depth -= 1
        block_lines.append(line)
        if started and depth == 0:
            break
    return "\n".join(block_lines)


# ── Main ───────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 on violations")
    parser.add_argument("--root", default=PROJECT_ROOT,
                        help="Project root directory")
    parser.add_argument("--allowlist", default=ALLOWLIST_PATH,
                        help="Path to allowlist file")
    args = parser.parse_args()

    root = args.root

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).  ALL declared
    # roots are enumerated first; the UI/ViewModel relevance check below is
    # a semantic filter, not a root filter.
    root_set, scope_diagnostics = resolve_production_source_scope(str(root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"ERROR: production source scope unresolved: {codes}",
            file=sys.stderr,
        )
        sys.exit(2)

    # Fail-closed: missing configured allowlist is fatal
    if not os.path.exists(args.allowlist):
        print(f"ERROR: Allowlist not found: {args.allowlist}", file=sys.stderr)
        sys.exit(2)

    allowlist = load_allowlist(args.allowlist)

    all_violations: List[str] = []
    files_scanned = 0

    try:
        source_files = list(iter_production_kotlin_files(str(root), root_set))
    except ProductionSourceScopeError as exc:
        print(
            f"ERROR: production source enumeration failed: {exc.code}",
            file=sys.stderr,
        )
        sys.exit(2)

    for source_file in source_files:
        filepath = source_file.absolute_path
        rel_path = source_file.repository_relative_path
        filename = os.path.basename(filepath)

        # Skip non-UI and non-ViewModel files quickly (pre-filter preserved
        # verbatim from the pre-GR-10B implementation).
        is_ui = "ui" in rel_path.replace("\\", "/").split(os.sep)
        is_vm = filename.endswith("ViewModel.kt")
        if not is_ui and not is_vm:
            continue

        files_scanned += 1
        file_violations = scan_file(filepath, rel_path, allowlist)
        all_violations.extend(file_violations)

    print(f"Scanned {files_scanned} UI/ViewModel files for G-UI-DAO-01 violations.")

    if all_violations:
        for v in all_violations:
            print(v)
        print(f"\nFound {len(all_violations)} violation(s).")

        if args.fail_on_violation:
            print("FAIL: G-UI-DAO-01 violations (--fail-on-violation set)", file=sys.stderr)
            sys.exit(1)
        else:
            print("WARNING: G-UI-DAO-01 violations (pass --fail-on-violation to fail CI)",
                  file=sys.stderr)
            sys.exit(0)
    else:
        print(f"PASS: {RULE_ID} — no UI-layer DAO violations found")
        sys.exit(0)


if __name__ == "__main__":
    main()
