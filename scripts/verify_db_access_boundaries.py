#!/usr/bin/env python3
"""
verify_db_access_boundaries.py
Global Write/Read/Restore Barrier — PR H2 (DB Ownership Enforcement)

Scans app/src/main/java for:
  1. Direct DAO mutation calls outside the canonical ownership policy.
  2. Ownership-policy entries that require writeBarrier but don't have it.
  3. Forbidden DB file operations outside approved structural exceptions.

Three sources of approval:
  1. Ownership policy (exact match: path + class + method + DAO + operation)
  2. Structural exceptions (exact match: path + class + method pattern + operation)
  3. Ratchet baseline (for growth enforcement) — handled by guard_ratchet.py

Exit codes:
  0 — no violations
  1 — violations found AND --fail-on-violation flag is set

Usage:
  python3 scripts/verify_db_access_boundaries.py
  python3 scripts/verify_db_access_boundaries.py --fail-on-violation
"""

import os
import re
import sys
import argparse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Optional PyYAML import ─────────────────────────────────────────────────────

try:
    import yaml
    _HAS_YAML = True
except ImportError:
    _HAS_YAML = False

# ── Patterns ──────────────────────────────────────────────────────────────────

MUTATION_PATTERN = re.compile(
    r'\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|'
    r'set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|'
    r'bulkRename|approve|reject)\s*\('
)

FILE_OP_PATTERN = re.compile(
    r'(?:getDatabasePath|\.deleteRecursively\(\)|openDatabase|'
    r'writableDatabase\s*(?://.*)?$|execSQL\s*\()'
)

WRITE_BARRIER_PATTERN = re.compile(r'writeBarrier\s*\.\s*(?:checkWritesAllowed|runWrite)\s*\(')
DEBUG_GUARD_PATTERN = re.compile(r'BuildConfig\s*\.\s*DEBUG')
FUN_PATTERN = re.compile(r'^\s*(?:suspend\s+)?fun\s+(\w+)\s*\(')

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
SOURCE_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java")

OWNERSHIP_POLICY_PATH = os.path.join(
    PROJECT_ROOT, "config", "guards", "db_ownership_policy.yml"
)
STRUCTURAL_EXCEPTIONS_PATH = os.path.join(
    PROJECT_ROOT, "config", "guards", "db_structural_exceptions.yml"
)

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# ── Legacy allowlist path (retained for backward compatibility only) ───────────
ALLOWLIST_PATH = os.path.join(PROJECT_ROOT, "config", "db_access_allowlist.yml")

# ── Policy loading ────────────────────────────────────────────────────────────

def _yaml_safe_load_or_exit(filepath, label):
    """Load a YAML file with safe_load. Exit with code 2 on any error."""
    if not _HAS_YAML:
        print(
            f"ERROR: PyYAML is required to load {label}. "
            f"Install with: pip install pyyaml",
            file=sys.stderr,
        )
        sys.exit(2)

    if not os.path.exists(filepath):
        print(f"ERROR: {label} not found: {filepath}", file=sys.stderr)
        sys.exit(2)

    try:
        with open(filepath, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError as e:
        print(f"ERROR: Malformed {label} YAML: {e}", file=sys.stderr)
        sys.exit(2)

    if data is None:
        print(f"ERROR: {label} is empty or contains no entries", file=sys.stderr)
        sys.exit(2)

    return data


def load_db_ownership_policy(policy_path=None):
    """Load and validate the DB ownership policy.

    Returns a list of policy entry dicts.
    Each entry must have: path, class, method, daos, operation, barrier_required,
    reason, owner.
    """
    if policy_path is None:
        policy_path = OWNERSHIP_POLICY_PATH

    data = _yaml_safe_load_or_exit(policy_path, "DB ownership policy")
    entries = data.get("entries", data) if isinstance(data, dict) else data

    if not isinstance(entries, list):
        print(
            f"ERROR: Ownership policy entries must be a list, "
            f"got {type(entries).__name__}",
            file=sys.stderr,
        )
        sys.exit(2)

    for i, entry in enumerate(entries):
        for field in ["path", "class", "method", "daos", "operation"]:
            if field not in entry:
                print(
                    f"ERROR: Missing '{field}' in ownership policy entry #{i+1}: "
                    f"{entry}",
                    file=sys.stderr,
                )
                sys.exit(2)
        # Validate daos is a list
        if not isinstance(entry.get("daos"), list):
            print(
                f"ERROR: 'daos' must be a list in ownership policy entry #{i+1}: "
                f"{entry}",
                file=sys.stderr,
            )
            sys.exit(2)

    return entries


def load_db_structural_exceptions(exceptions_path=None):
    """Load and validate the DB structural exceptions.

    Returns a list of exception entry dicts.
    Each entry must have: path, class, method_pattern, operation, reason, owner.
    """
    if exceptions_path is None:
        exceptions_path = STRUCTURAL_EXCEPTIONS_PATH

    data = _yaml_safe_load_or_exit(exceptions_path, "DB structural exceptions")
    entries = data.get("entries", data) if isinstance(data, dict) else data

    if not isinstance(entries, list):
        print(
            f"ERROR: Structural exception entries must be a list, "
            f"got {type(entries).__name__}",
            file=sys.stderr,
        )
        sys.exit(2)

    for i, entry in enumerate(entries):
        for field in ["path", "class", "method_pattern", "operation"]:
            if field not in entry:
                print(
                    f"ERROR: Missing '{field}' in structural exception entry "
                    f"#{i+1}: {entry}",
                    file=sys.stderr,
                )
                sys.exit(2)

    return entries


# ── Legacy allowlist (for backward compatibility) ──────────────────────────────

def load_allowlist(path=None):
    """Load the legacy allowlist for backward-compatible callers.
    
    This is kept for test compatibility. New code should use
    load_db_ownership_policy() and load_db_structural_exceptions().
    """
    if path is None:
        path = ALLOWLIST_PATH
    entries = {}
    if not os.path.exists(path):
        return entries

    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    try:
        entries = _parse_allowlist_lines(lines)
    except Exception as e:
        print(f"ERROR: malformed allowlist file at {path}: {e}", file=sys.stderr)
        sys.exit(2)

    return entries


def _parse_allowlist_lines(lines):
    """Parse allowlist YAML-like lines into the entries dict."""
    entries = {}
    current = None
    in_daos = False
    in_methods = False
    _merging = False

    for line in lines:
        stripped = line.strip()

        if stripped.startswith("- class:"):
            cls_name = stripped[len("- class:"):].strip()
            if cls_name in entries:
                current = entries[cls_name]
                _merging = True
            else:
                _merging = False
                current = {
                    "class": cls_name,
                    "daos": set(),
                    "methods_only": None,
                    "debug_only": False,
                    "requires_write_barrier": True,
                    "reason": "",
                }
                entries[cls_name] = current
            in_daos = False
            in_methods = False

        elif current is None:
            continue

        elif stripped == "daos:":
            in_daos = True
            in_methods = False

        elif stripped == "methods_only:":
            in_methods = True
            in_daos = False
            if not _merging:
                current["methods_only"] = set()

        elif stripped.startswith("- ") and in_daos:
            current["daos"].add(stripped[2:].strip())

        elif stripped.startswith("- ") and in_methods:
            if current["methods_only"] is not None:
                current["methods_only"].add(stripped[2:].strip())

        elif stripped.startswith("reason:"):
            current["reason"] = stripped[len("reason:"):].strip().strip('"').strip("'")
            in_daos = False
            in_methods = False

        elif stripped.startswith("debug_only:"):
            if not _merging:
                val = stripped[len("debug_only:"):].strip().lower()
                current["debug_only"] = val == "true"
            in_daos = False
            in_methods = False

        elif stripped.startswith("requires_write_barrier:"):
            val = stripped[len("requires_write_barrier:"):].strip().lower()
            current["requires_write_barrier"] = val != "false"
            in_daos = False
            in_methods = False

        elif stripped.startswith("allowed_until:") or stripped.startswith("class:"):
            in_daos = False
            in_methods = False

    return entries


# ── DAO name extraction ───────────────────────────────────────────────────────

LOCAL_DAO_ASSIGN = re.compile(r'\bval\s+(\w+)\s*=\s*\w+\.(\w+Dao)\s*\(')
DIRECT_CHAIN_DAO = re.compile(
    r'\w+\.(\w+Dao)\s*\(\s*\)\s*\.\s*(?:insert|insertAll|update|delete|'
    r'deleteAll|clear|replace|upsert|set|mark|link|unlink|increment|'
    r'suppress|claim|fulfill|restore|save|bulkRename|approve|reject)\s*\('
)
DIRECT_DAO_MUTATION = re.compile(
    r'\b(\w+Dao)\s*\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|'
    r'replace|upsert|set|mark|link|unlink|increment|suppress|claim|fulfill|'
    r'restore|save|bulkRename|approve|reject)\s*\('
)

# Matches Kotlin property/constructor parameter declarations with explicit DAO types:
#   private val groupDao: ExpenseGroupDao
#   val usageDao: SubscriptionUsageDao
#   private val memberDao: GroupMemberDao
# Group 1: variable/property name, Group 2: declared DAO interface simple name
DAO_PROPERTY_DECL = re.compile(
    r'(?:private\s+|protected\s+|internal\s+|override\s+)*'
    r'(?:val|var|lateinit\s+var)\s+(\w+)\s*:\s*'
    r'(?:\w+\.)*(\w+Dao)\b'
)


def _interface_name_to_room_accessor(interface_name):
    """Derive the Room DB accessor name from a DAO interface simple name.

    Room generates an abstract method by lowercasing the first character of the
    DAO interface name.  E.g. ExpenseGroupDao -> expenseGroupDao,
    SubscriptionUsageDao -> subscriptionUsageDao.
    """
    if not interface_name or len(interface_name) < 2:
        return interface_name
    return interface_name[0].lower() + interface_name[1:]


def _extract_dao_names_from_line(line, dao_var_map):
    """Extract DAO type names from a mutation line.

    Returns a set of DAO type names (e.g., 'expenseDao', 'scannedReceiptDao')
    found in this line of code.

    Args:
        line: The source line containing a mutation call.
        dao_var_map: dict mapping local variable names to DAO type names.
    """
    daos = set()

    # Pattern 1: wordDao.someMutation( — direct DAO reference
    for m in DIRECT_DAO_MUTATION.finditer(line):
        daos.add(m.group(1))

    # Pattern 2: wordDao().someMutation( — direct chain
    for m in DIRECT_CHAIN_DAO.finditer(line):
        daos.add(m.group(1))

    # Pattern 3: localVar.someMutation( where localVar is a known DAO variable
    local_var_re = re.compile(r'\b(\w+)\s*\.\s*(?:insert|insertAll|update|delete|'
        r'deleteAll|clear|replace|upsert|set|mark|link|unlink|increment|suppress|'
        r'claim|fulfill|restore|save|bulkRename|approve|reject)\s*\(')
    for m in local_var_re.finditer(line):
        var_name = m.group(1)
        if var_name in dao_var_map:
            daos.add(dao_var_map[var_name])

    return daos


# ── Build DAO variable map from file ──────────────────────────────────────────

def _build_dao_var_map(lines):
    """Build a mapping from local variable names to DAO type names.

    Scans lines for patterns like:
        val expenseDao = database.expenseDao()
        val dao = database.scannedReceiptDao()

    Also resolves constructor/property declarations with explicit DAO types:
        private val groupDao: ExpenseGroupDao  -> groupDao -> expenseGroupDao
        val usageDao: SubscriptionUsageDao     -> usageDao -> subscriptionUsageDao

    Returns dict: variable_name -> DAO_room_accessor_name
      (e.g., 'dao' -> 'scannedReceiptDao', 'groupDao' -> 'expenseGroupDao')
    """
    var_map = {}
    pending_var = None

    for line in lines:
        s = line.strip()
        if s.startswith("//") or s.startswith("*"):
            continue

        # Pattern 1: val name = database.someDao()
        m = LOCAL_DAO_ASSIGN.search(line)
        if m:
            var_map[m.group(1)] = m.group(2)
            pending_var = None
            continue

        # Pattern 2: Multi-line val name =\n    database.someDao()
        m_pending = re.search(r'\bval\s+(\w+)\s*=\s*$', line.rstrip())
        if m_pending:
            pending_var = m_pending.group(1)
            continue
        if pending_var and re.search(r'\w+Dao\s*\(', line):
            m_dao = re.search(r'(\w+Dao)\s*\(', line)
            if m_dao:
                var_map[pending_var] = m_dao.group(1)
            pending_var = None
            continue
        pending_var = None

        # Pattern 3: val/var name: SomeDaoType (constructor/property injection)
        m_prop = DAO_PROPERTY_DECL.search(line)
        if m_prop:
            var_name = m_prop.group(1)
            interface_name = m_prop.group(2)
            room_accessor = _interface_name_to_room_accessor(interface_name)
            # Only add if not already mapped by more specific patterns above
            if var_name not in var_map:
                var_map[var_name] = room_accessor

    return var_map


# ── Ownership policy matching ─────────────────────────────────────────────────

def _matches_ownership_policy(
    rel_path, class_name, method_name, dao_names, ownership_policy
):
    """Check if a DAO mutation matches an ownership policy entry.

    Returns the matching entry dict, or None if no match.
    """
    path_suffix = rel_path.replace("\\", "/").split("/")[-1]

    for entry in ownership_policy:
        if entry["path"] != path_suffix:
            continue
        if entry["class"] != class_name:
            continue

        # Method match: "*" wildcard or exact match
        policy_method = entry.get("method", "*")
        if policy_method != "*" and policy_method != method_name:
            continue

        # DAO match: at least one DAO on the line is in the policy's DAO list
        policy_daos = entry.get("daos", [])
        if not any(d in policy_daos for d in dao_names):
            continue

        # Operation match: "write" wildcard or exact match
        policy_op = entry.get("operation", "write")
        if policy_op != "write":
            # For non-wildcard operations, we'd need to extract the specific op
            # For now, "write" covers all mutations
            continue

        return entry

    return None


# ── Structural exception matching ─────────────────────────────────────────────

def _matches_structural_exception(
    rel_path,
    class_name,
    current_fun,
    lineno,
    line,
    lines,
    structural_exceptions,
):
    """Return True if the file operation matches a proven structural exception.

    Checks: path suffix, class_name, method_pattern in either current_fun
    or nearby enclosing context, and operation type in the line.

    Special operation prefixes:
      - "raw_" matches any file operation (catch-all wildcard for a class/method)
      - otherwise the op_type string must appear literally in the line
    """
    path_suffix = rel_path.replace("\\", "/").split("/")[-1]

    for exc in structural_exceptions:
        if exc["path"] != path_suffix:
            continue
        if exc["class"] != class_name:
            continue

        # Check operation type
        op_type = exc["operation"]
        if not op_type.startswith("raw_"):
            if op_type not in line:
                continue

        # Check method_pattern in current function name
        method_pattern = exc["method_pattern"]
        if current_fun and re.search(method_pattern, current_fun):
            return True

        # Search backwards in lines for the pattern (for val/object declarations)
        # Use a wide window (40 lines) because MIGRATION objects can span many lines
        for back in range(max(0, lineno - 40), lineno):
            if re.search(method_pattern, lines[back]):
                return True

    return False


# ── Body extraction ───────────────────────────────────────────────────────────

def _extract_method_body(lines, start):
    """Extract the body of the function starting at line index `start`."""
    depth = 0
    body_lines = []
    started = False
    for i in range(start, min(start + 80, len(lines))):
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
        # Expression-bodied: no braces — single line
        if not started and '=' in line and i == start:
            body_lines.append(line)
            break
    return "".join(body_lines)


def _barrier_before_line(lines, fun_start, mutation_lineno):
    """Return True if a writeBarrier call appears between fun_start and mutation_lineno."""
    for i in range(fun_start, min(mutation_lineno - 1, len(lines))):
        if WRITE_BARRIER_PATTERN.search(lines[i]):
            return True
    return False


# ── Scan ──────────────────────────────────────────────────────────────────────

def scan(source_dir, ownership_policy=None, structural_exceptions=None):
    """Scan Kotlin sources for DB access boundary violations.

    Args:
        source_dir: Path to the Java/Kotlin source directory.
        ownership_policy: List of ownership policy entry dicts (from load_db_ownership_policy).
        structural_exceptions: List of structural exception entry dicts (from load_db_structural_exceptions).

    Returns:
        (violations, files_scanned) tuple.
    """
    if ownership_policy is None:
        ownership_policy = []
    if structural_exceptions is None:
        structural_exceptions = []

    violations = []
    files_scanned = 0

    for root, dirs, files in os.walk(source_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

        for filename in files:
            if not filename.endswith(".kt"):
                continue

            files_scanned += 1

            class_name = filename.removesuffix(".kt")

            if class_name.endswith("Dao"):
                continue

            filepath = os.path.join(root, filename)
            rel_path = os.path.relpath(filepath, PROJECT_ROOT)

            try:
                with open(filepath, encoding="utf-8") as f:
                    lines = f.readlines()
            except OSError as e:
                violations.append((
                    rel_path, 0, "",
                    f"ERROR: cannot read {rel_path}: {e}"
                ))
                continue

            # Build DAO variable map for this file
            dao_var_map = _build_dao_var_map(lines)

            # ── File-operation guard ──────────────────────────────────────
            file_op_fun = None
            for lineno, line in enumerate(lines, start=1):
                s = line.strip()
                if s.startswith("//") or s.startswith("*"):
                    continue

                # Track current function name
                m = FUN_PATTERN.match(line)
                if m:
                    file_op_fun = m.group(1)

                if not FILE_OP_PATTERN.search(line):
                    continue

                # Check structural exceptions
                if _matches_structural_exception(
                    rel_path, class_name, file_op_fun, lineno, line, lines,
                    structural_exceptions,
                ):
                    continue

                violations.append((
                    rel_path, lineno, line.rstrip(),
                    "FORBIDDEN_FILE_OP: DB file operation outside approved structural exception"
                ))

            # ── DAO mutation guard ────────────────────────────────────────
            current_fun = None
            current_fun_start = 0
            # Track local DAO variable names: var_name -> True (for "has local dao" check)
            local_dao_vars = set()
            # Track multi-line DAO assignment
            pending_dao_var = None

            for lineno, line in enumerate(lines, start=1):
                s = line.strip()
                if s.startswith("//") or s.startswith("*"):
                    continue

                # Track local DAO variable assignments: val dao = database.scannedReceiptDao()
                m_assign = LOCAL_DAO_ASSIGN.search(line)
                if m_assign:
                    local_dao_vars.add(m_assign.group(1))
                    pending_dao_var = None
                else:
                    # Multi-line: val dao =\n    database.scannedReceiptDao()
                    m_pending_start = re.search(r'\bval\s+(\w+)\s*=\s*$', line.rstrip())
                    if m_pending_start:
                        pending_dao_var = m_pending_start.group(1)
                    elif pending_dao_var and re.search(r'\w+Dao\s*\(', line):
                        local_dao_vars.add(pending_dao_var)
                        pending_dao_var = None
                    else:
                        pending_dao_var = None

                # Track current function name
                m = FUN_PATTERN.match(line)
                if m:
                    current_fun = m.group(1)
                    current_fun_start = lineno - 1
                    local_dao_vars.clear()
                    pending_dao_var = None

                # Check for DAO mutation — either via "Dao" reference or local dao var
                has_dao_ref = "Dao" in line
                has_local_dao = any(
                    re.search(r'\b' + re.escape(v) + r'\s*\.', line)
                    for v in local_dao_vars
                ) if local_dao_vars else False

                if not has_dao_ref and not has_local_dao:
                    continue
                if not MUTATION_PATTERN.search(line):
                    continue

                # Extract DAO names from this mutation line
                dao_names = _extract_dao_names_from_line(line, dao_var_map)

                # ── Check 1: Ownership policy ────────────────────────
                policy_match = _matches_ownership_policy(
                    rel_path, class_name, current_fun, dao_names,
                    ownership_policy,
                )

                if policy_match is not None:
                    # Check writeBarrier requirement
                    if policy_match.get("barrier_required", True):
                        if not _barrier_before_line(lines, current_fun_start, lineno):
                            violations.append((
                                rel_path, lineno, line.rstrip(),
                                f"MISSING_WRITE_BARRIER: {class_name}.{current_fun} "
                                f"is in ownership policy but no writeBarrier before DAO mutation"
                            ))
                    # Otherwise, policy match = PASS (no violation)
                    continue

                # ── No match in ownership policy → VIOLATION ───────────
                violations.append((
                    rel_path, lineno, line.rstrip(),
                    "UNALLOWLISTED_CLASS"
                ))

    return violations, files_scanned


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Verify DB access boundaries")
    parser.add_argument("--fail-on-violation", action="store_true")
    parser.add_argument(
        "--ownership-policy",
        default=None,
        help="Path to ownership policy YAML (default: config/guards/db_ownership_policy.yml)",
    )
    parser.add_argument(
        "--structural-exceptions",
        default=None,
        help="Path to structural exceptions YAML (default: config/guards/db_structural_exceptions.yml)",
    )
    args = parser.parse_args()

    if not os.path.isdir(SOURCE_DIR):
        print(f"ERROR: source directory not found: {SOURCE_DIR}", file=sys.stderr)
        sys.exit(2)

    # Load ownership policy
    ownership_policy_path = args.ownership_policy or OWNERSHIP_POLICY_PATH
    ownership_policy = load_db_ownership_policy(ownership_policy_path)

    # Load structural exceptions
    exceptions_path = args.structural_exceptions or STRUCTURAL_EXCEPTIONS_PATH
    structural_exceptions = load_db_structural_exceptions(exceptions_path)

    violations, files_scanned = scan(SOURCE_DIR, ownership_policy, structural_exceptions)

    if files_scanned == 0:
        print(
            "ERROR: no Kotlin source files found to scan in " + SOURCE_DIR,
            file=sys.stderr,
        )
        sys.exit(2)

    # Separate infrastructure errors (unreadable files) from real violations
    read_errors = [v for v in violations if v[1] == 0 and v[3].startswith("ERROR:")]
    real_violations = [v for v in violations if v not in read_errors]

    if read_errors:
        for _, _, _, reason in read_errors:
            print(reason, file=sys.stderr)
        sys.exit(2)

    if not real_violations:
        print("PASS: DB access boundaries — no unauthorized DAO mutations found.")
        sys.exit(0)

    status = "FAIL" if args.fail_on_violation else "WARNING"
    print(f"{status}: DB access boundaries — {len(real_violations)} violation(s):\n")

    for rel_path, lineno, line_text, reason in real_violations:
        print(f"  [{reason}]")
        print(f"  {rel_path}:{lineno}")
        print(f"    {line_text.strip()}")
        print()

    print("For each violation, either:")
    print("  1. Add an entry to config/guards/db_ownership_policy.yml with a reason.")
    print("  2. Add a structural exception to config/guards/db_structural_exceptions.yml.")
    print("  3. Route the write through the approved lifecycle coordinator.")
    print()
    print("See docs/DB_WRITE_OWNERSHIP.md for the ownership map.")

    if args.fail_on_violation:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
