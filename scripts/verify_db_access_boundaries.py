#!/usr/bin/env python3
"""
verify_db_access_boundaries.py
Coherent Exact DB Access Boundary Scanner (Write/Read/Restore Barrier)

Scans app/src/main/java for:
  1. Direct DAO mutation calls outside the canonical ownership policy.
  2. DAO mutation pairs that are only partially approved — mixed
     approved/unapproved pairs in the same method fail.
  3. Forbidden DB file operations outside approved structural exceptions.

Approval sources:
  1. Ownership policy — EXACT match on (canonical path, class, method, DAO,
     operation).  The ``operation`` field must be the EXACT DAO method name
     that the method body invokes (e.g. ``insertOrIgnore``, ``archiveGroup``,
     ``staleAbortIfStillRunning``, ``deleteAllForGroup``).  The universal
     ``operation: write`` is rejected as invalid policy metadata.
  2. Structural exceptions — EXACT match on (canonical path, class,
     method_pattern, operation).  method_pattern is bounded: an exact Kotlin
     identifier or the single migration form ``MIGRATION_\\d+_\\d+``.
  3. Ratchet baseline (growth enforcement) — handled by guard_ratchet.py.

Canonical policy paths (both policy files):
  * repository-relative POSIX paths under an approved production source root
    (``app/src/main/java``), e.g.
    ``app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt``;
  * bare basenames, ``..``, backslashes, absolute paths, non-``.kt`` paths,
    and ambiguous suffix paths are rejected at load time (fail closed);
  * matching is exact canonical path equality — never basename or suffix.

Scan semantics:
  * class/object/interface names are parsed from the ACTUAL Kotlin
    declarations of the referenced file — never derived from the filename;
  * every mutation is associated with its exact enclosing class and its exact
    enclosing method (balanced body) inside that class; there is no
    file-wide or filename-wide fallback;
  * DAO identities resolve through class-scoped properties/constructor
    params and method-scoped locals to the Room accessor names used by the
    policy (e.g. ``private val groupDao: ExpenseGroupDao`` ->
    ``expenseGroupDao``);
  * authorization requires every extracted ``(dao_identity, operation)`` pair
    to be covered by an exact policy entry; a single uncovered pair fails.

Exit codes:
  0 — no violations
  1 — violations found AND --fail-on-violation flag is set
  2 — infrastructure/config error (loader rejection, invalid policy metadata
      supplied directly to scan(), missing source, unreadable file, no
      scanable files)

Usage:
  python3 scripts/verify_db_access_boundaries.py
  python3 scripts/verify_db_access_boundaries.py --fail-on-violation
"""

import argparse
import os
import re
import sys
from collections import Counter

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Optional PyYAML import ─────────────────────────────────────────────────────

try:
    import yaml
    _HAS_YAML = True
except ImportError:
    _HAS_YAML = False

# ── Explicit mutator grammar ──────────────────────────────────────────────────
# Every token is either a verb prefix or an exact compound method-name prefix of
# an ACTUAL DAO mutator present in app/src/main/java/.../data/database/dao/.
# Verb prefixes intentionally cover the compound mutators used in production
# (e.g. "update" covers updateIsNotMine, updateOwnerName, updateStatus, ...;
# "insert" covers insertOrIgnore, insertAll, insertOrUpdate, ...; "archive"
# covers archiveGroup; "claim" covers claimNotifications, ...; "mark" covers
# markSentFromClaimed, ...; "clear" covers clearSharedExpenseFlags, clearSession).
# This is an explicit grammar — never a broad `.*` exemption.
#
# IMPORTANT: the grammar is used ONLY for DETECTION.  Authorization compares
# the EXACT extracted DAO method name to the policy entry's ``operation``
# value; a policy entry with ``operation: write`` is invalid metadata and is
# rejected at load time.
MUTATION_VERBS = (
    "insert", "update", "delete", "clear", "mark", "set", "claim",
    "archive", "restore", "unlink", "link", "block", "unblock", "purge",
    "redact", "suppress", "fulfill", "approve", "reject", "dismiss",
    "expire", "deactivate", "finalize", "transition", "repair", "seed",
    "increment", "decrement", "upsert", "replace", "merge", "reassign",
    "record", "recover", "release", "reopen", "refresh", "reset", "cancel",
    "attach", "disconnect", "complete", "accept", "encrypt",
    # Prefix verbs for the remaining ACTUAL DAO mutators that would otherwise
    # be invisible to the grammar:
    #   "cleanup"       — cleanupOldDismissedAlerts (AnomalyAlertDao DELETE);
    #   "bulk"          — bulkRenameMerchant{,ByKey,ByName} and
    #                     bulkUpdateCategoryByMerchant{,ByKey,ByName}
    #                     (PendingReviewDao renames/updates);
    #   "add"           — addToGoalAmount (SavingsGoalDao atomic
    #                     UPDATE ... SET currentAmount = currentAmount + ..);
    #   "conditionally" — conditionallySetLocation (ExpenseDao
    #                     UPDATE ... WHERE latitude IS NULL).
    "cleanup", "bulk", "add", "conditionally",
)

# Exact compound mutator name-prefixes that do not start with any verb above.
# ``staleAbortIfStillRunning`` and ``getOrInsertByNameNoCase`` are the named
# policy operations that would otherwise be invisible to the grammar
# (``bulkRename`` is covered by the ``bulk`` verb prefix above).
MUTATION_EXACT_NAMES = (
    "getOrInsertByNameNoCase",
    "staleAbortIfStillRunning",
)

MUTATION_TOKENS = tuple(sorted(set(MUTATION_VERBS) | set(MUTATION_EXACT_NAMES)))
_MUTATION_ALTERNATION = "|".join(
    re.escape(token) for token in sorted(MUTATION_TOKENS, key=len, reverse=True)
)

MUTATION_PATTERN = re.compile(
    r'\.\s*(?:' + _MUTATION_ALTERNATION + r')\w*\s*\('
)

# Captures the exact method name after the dot (group ``method``) so the
# extracted operation can be compared EXACTLY against the policy.
_MUTATION_CALL_RE = re.compile(
    r'\b(?P<receiver>\w+)\s*\.\s*'
    r'(?P<method>(?:' + _MUTATION_ALTERNATION + r')\w*)\s*\('
)

# Inline chain form: `database.someDao().mutation(...)`.
_DIRECT_CHAIN_MUTATION_RE = re.compile(
    r'\b(?P<dao>\w+Dao)\s*\(\s*\)\s*\.\s*'
    r'(?P<method>(?:' + _MUTATION_ALTERNATION + r')\w*)\s*\('
)

# ── Structural file-operation evidence (exact masked call/token matching) ─────
# A structural exception is only satisfied when its ``operation`` appears in
# the COMMENT/STRING-MASKED text as an EXACT call (``name(`` — spanning line
# breaks via ``\s``) or EXACT token.  A raw substring match is never used, so
# comment/string text (including a trailing comment on the same line) and
# identifier prefixes (``getDatabasePathway`` for ``getDatabasePath``,
# ``openDatabaseHelper`` for ``openDatabase``, ``mywritableDatabase`` for
# ``writableDatabase``) can never satisfy evidence.  ``raw_``-prefixed
# operations are catch-all file operations, but only for occurrences the
# scanner PROVES as exact operations — never for prefix-like identifiers.
_FILE_OP_CALL_EVIDENCE = {
    "execSQL": re.compile(r"\bexecSQL\s*\("),
    "openDatabase": re.compile(r"\bopenDatabase\s*\("),
    "getDatabasePath": re.compile(r"\bgetDatabasePath\s*\("),
}
_FILE_OP_TOKEN_EVIDENCE = {
    "deleteRecursively": re.compile(r"\.deleteRecursively\s*\(\s*\)"),
    "writableDatabase": re.compile(r"\bwritableDatabase\b"),
}

# Complete detection table: every supported operation with its EXACT evidence
# pattern.  ``\s`` spans line breaks, so ``db.execSQL\n("...")`` and
# ``SQLiteDatabase.openDatabase\n(...)`` are detected from the COMPLETE
# statefully masked text (never a per-line scan).
_FILE_OP_PATTERNS = tuple(
    [(op, re_) for op, re_ in _FILE_OP_CALL_EVIDENCE.items()]
    + [(op, re_) for op, re_ in _FILE_OP_TOKEN_EVIDENCE.items()]
)

# Supported operations whose bare identifier appears in code but whose EXACT
# call evidence cannot be proven (``execSQL`` without ``(``,
# ``.deleteRecursively`` without ``()``) fail closed as an unsupported
# structural operation instead of being silently skipped.  ``writableDatabase``
# is itself the complete token evidence (a property access), so it has no
# unparseable form.  Each tuple is ``(operation, token_re, call_re)`` where
# ``call_re`` proves the token is a real call at the same offset.
_FILE_OP_UNSUPPORTED_TOKENS = (
    ("execSQL", re.compile(r"\bexecSQL\b"), _FILE_OP_CALL_EVIDENCE["execSQL"]),
    ("openDatabase", re.compile(r"\bopenDatabase\b"), _FILE_OP_CALL_EVIDENCE["openDatabase"]),
    ("getDatabasePath", re.compile(r"\bgetDatabasePath\b"), _FILE_OP_CALL_EVIDENCE["getDatabasePath"]),
    ("deleteRecursively", re.compile(r"\.deleteRecursively\b"), _FILE_OP_TOKEN_EVIDENCE["deleteRecursively"]),
)

# ── Declaration parsing (exact structural matching) ───────────────────────────
FUN_DECL_RE = re.compile(r'\bfun\s+(\w+)\s*[<(]')
VAL_OBJECT_DECL_RE = re.compile(r'\bval\s+(\w+)\s*=\s*object\b')
TYPE_DECL_RE = re.compile(r'\b(?:object|class|interface)\s+(\w+)\b')

# Capturing variant used by parse_type_declarations() — includes the kind.
_TYPE_DECL_NAME_RE = re.compile(
    r'\b(?P<kind>object|class|interface)\s+'
    r'(?P<name>[A-Za-z_][A-Za-z0-9_]*)\b'
)


def _declaration_name_from_masked(raw_line, masked_line):
    """Return the declared name when ``masked_line`` proves a real declaration.

    Detection runs on ``masked_line`` — a line from the stateful
    :func:`_mask_lines_for_structural_scan` copy where line comments, block
    comments, strings, triple-quoted strings, and char literals are replaced
    by spaces.  Fake ``fun``/``class``/``object``/``val NAME = object`` text
    inside comments or literals can therefore never create a declaration.
    Masking preserves per-line offsets, so when the masked line matches, the
    name is recovered from the CORRESPONDING RAW span of ``raw_line`` — raw
    text is only read where the mask proved it is code.  Returns None when the
    line does not start a declaration.
    """
    m = FUN_DECL_RE.search(masked_line)
    if m:
        s, e = m.span(1)
        return raw_line[s:e]
    m = VAL_OBJECT_DECL_RE.search(masked_line)
    if m:
        s, e = m.span(1)
        return raw_line[s:e]
    m = TYPE_DECL_RE.search(masked_line)
    if m:
        s, e = m.span(1)
        return raw_line[s:e]
    return None


def _find_declaration_name(line):
    """Return the name of the first enclosing declaration on a line.

    Handles ``fun name(`` / ``suspend fun name(`` / ``override fun name(``,
    bounded migration objects (``val MIGRATION_145_146 = object : Migration(...)``),
    and ``object|class|interface Name`` declarations.  Returns None when the
    line does not start a declaration.

    Detection is masking-aware via :func:`_mask_kotlin_line` (a single-line
    mask), so fake declaration text inside strings or comments is never
    treated as a declaration.  Whole-file scope accounting uses the stateful
    :func:`_declaration_name_from_masked` on the file-level mask instead, so
    multi-line block comments and triple-quoted strings stay masked to their
    true end.
    """
    return _declaration_name_from_masked(line, _mask_kotlin_line(line))


def _declarations_in_scope(lines, target_lineno):
    """Return the [(name, start_line)] declarations enclosing ``target_lineno``.

    ``target_lineno`` is 0-based.  The innermost declaration is last.  Names are
    parsed from actual Kotlin declarations so a file operation is associated
    with its true enclosing declaration rather than an arbitrary previous one.
    Bounded migration objects (``MIGRATION_\\d+_\\d+``) are supported through
    the ``val NAME = object`` declaration name.

    Declaration detection AND brace accounting are comment- and string-aware:
    the stateful mask replaces Kotlin strings, char literals, line comments,
    and block comments before matching, so a fake ``// class X {`` or
    ``"fun y() {"`` can never create a declaration or open/close a scope, and
    a ``// }`` or block-comment brace can never close (or open) a declaration.
    Masking preserves per-line length and line count, so source line mapping
    is untouched.
    """
    masked = _mask_lines_for_structural_scan(lines)
    stack = []  # (name, start_line, brace_depth_after_open)
    depth = 0
    for i in range(target_lineno):
        line = masked[i]
        while stack and depth < stack[-1][2]:
            stack.pop()
        decl_name = _declaration_name_from_masked(lines[i], line)
        if decl_name:
            stack.append((decl_name, i, depth + line.count("{")))
        depth += line.count("{") - line.count("}")
    while stack and depth < stack[-1][2]:
        stack.pop()

    result = [(name, start) for name, start, _ in stack]
    decl_name = _declaration_name_from_masked(
        lines[target_lineno], masked[target_lineno]
    )
    if decl_name:
        result.append((decl_name, target_lineno))
    return result

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
SOURCE_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java")

# Approved production source roots (repository-relative).  Canonical policy
# paths must live under one of these roots — a path cannot point at tests,
# generated code, or any other non-production tree.
APPROVED_PRODUCTION_SOURCE_ROOTS = ("app/src/main/java",)

OWNERSHIP_POLICY_PATH = os.path.join(
    PROJECT_ROOT, "config", "guards", "db_ownership_policy.yml"
)
STRUCTURAL_EXCEPTIONS_PATH = os.path.join(
    PROJECT_ROOT, "config", "guards", "db_structural_exceptions.yml"
)

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# ── Legacy allowlist path (retained for backward compatibility only) ───────────
ALLOWLIST_PATH = os.path.join(PROJECT_ROOT, "config", "db_access_allowlist.yml")


def canonical_policy_path_error(raw):
    """Return a human-readable error for a non-canonical policy path, or None.

    Canonical policy paths are:
      * non-empty strings;
      * repository-relative POSIX paths ('/' separators, no backslash);
      * not absolute (no leading '/', drive letter, or UNC prefix);
      * free of empty / '.' / '..' segments;
      * not bare basenames (must contain a directory component);
      * ending in ``.kt``;
      * under an approved production source root
        (``app/src/main/java``).

    Bare basenames are rejected because duplicate basenames exist across
    packages; suffix/ambiguous paths are rejected because matching is exact
    canonical path equality.
    """
    if not isinstance(raw, str):
        return "path must be a string"
    p = raw.strip()
    if not p:
        return "path must be non-empty"
    if "\\" in p:
        return f"path contains a backslash: {p!r} (use '/' separators)"
    if p.startswith("/") or p.startswith("\\\\") or re.match(r"^[A-Za-z]:[\\/]", p):
        return f"path must be repository-relative, not absolute: {p!r}"
    if p.startswith("./") or p == ".":
        return f"path must not start with './': {p!r}"
    parts = p.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return f"path must not contain empty, '.', or '..' segments: {p!r}"
    if len(parts) < 2:
        return f"bare basename path is ambiguous and not allowed: {p!r}"
    if not p.endswith(".kt"):
        return f"path must reference a Kotlin source file (.kt): {p!r}"
    for root in APPROVED_PRODUCTION_SOURCE_ROOTS:
        if p == root or p.startswith(root + "/"):
            return None
    return (
        f"path {p!r} is not under an approved production source root "
        f"{sorted(APPROVED_PRODUCTION_SOURCE_ROOTS)}"
    )


def canonical_policy_path(raw):
    """Return the canonical policy path string, or None when not canonical.

    Canonical policy paths are repository-relative POSIX paths under an
    approved production source root.  Matching is exact canonical path
    equality — a non-canonical policy path never authorizes anything.
    """
    if canonical_policy_path_error(raw) is None:
        return raw.strip()
    return None


def _scanned_file_canonical_path(filepath):
    """Return the repository-relative POSIX path of a scanned file.

    For production files under ``app/src/main/java`` this equals the canonical
    policy path form; for any other tree it produces a path that no canonical
    policy path can equal (fail closed).
    """
    return os.path.relpath(filepath, PROJECT_ROOT).replace("\\", "/")


# ── Pure metadata validation (no I/O, no sys.exit) ────────────────────────────

def _entry_label(entry, index, label):
    """Return a stable, human-readable context string for a policy entry.

    Prefers the entry's own ``path``, falls back to ``class``, then index —
    so every configuration error carries enough context to locate the entry
    without leaking unrelated payload data.
    """
    if isinstance(entry, dict):
        path_value = entry.get("path")
        if isinstance(path_value, str) and path_value.strip():
            return f"{label} #{index + 1} ({path_value})"
        class_value = entry.get("class")
        if isinstance(class_value, str) and class_value.strip():
            return f"{label} #{index + 1} ({class_value})"
    return f"{label} #{index + 1}"


def ownership_entry_metadata_errors(entry):
    """Return human-readable metadata errors for one ownership entry, or [].

    Pure helper (no I/O, no ``sys.exit``) that tests can exercise directly.
    Validates the COMPLETE ownership-entry contract shared by the loader and
    scan()'s direct API:

      1. ``entry`` must be a mapping;
      2. only keys in ``OWNERSHIP_ALLOWED_KEYS`` are accepted — an unknown key
         is a configuration error, never a silently-ignored field;
      3. the required string fields (path, class, method, operation, reason,
         owner, linked_issue) must be non-empty strings;
      4. ``path`` must be a canonical policy path;
      5. ``operation`` must be the EXACT DAO method name — the universal
         ``write`` value is invalid policy metadata;
      6. ``method`` must be EXACT — wildcard/pattern methods are rejected;
      7. ``daos`` must be a non-empty list of non-empty strings;
      8. ``barrier_required`` must be present and a REAL boolean;
      9. ``barrier_via`` / ``delegate_of`` must be non-empty strings when
         present, and ``private`` must be a real boolean when present.
    """
    errors = []
    if not isinstance(entry, dict):
        return ["entry must be a mapping"]

    # H2 strict schema: reject unknown keys so a mistyped field (e.g. ``daoz``)
    # can never be silently ignored while the guard approves a mutation.
    unknown_keys = set(entry) - OWNERSHIP_ALLOWED_KEYS
    if unknown_keys:
        errors.append(f"unknown key(s) {sorted(unknown_keys)}")

    for field in ("path", "class", "method", "operation", "reason", "owner", "linked_issue"):
        value = entry.get(field)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"'{field}' must be a non-empty string")

    path = entry.get("path")
    if isinstance(path, str) and path.strip():
        path_error = canonical_policy_path_error(path)
        if path_error:
            errors.append(f"'path' is not canonical: {path_error}")

    op = entry.get("operation")
    if op == "write":
        errors.append(
            "'operation: write' is invalid policy metadata — every entry must "
            "name the EXACT DAO method it authorizes "
            "(e.g. 'insert', 'insertOrIgnore', 'archiveGroup', "
            "'deleteAllForGroup', 'staleAbortIfStillRunning')"
        )
    elif not isinstance(op, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", op or ""):
        errors.append(f"'operation' must be an exact DAO method name, got {op!r}")

    method = entry.get("method")
    if isinstance(method, str) and _is_wildcard_method(method):
        errors.append(
            f"method {method!r} is a wildcard/pattern method — every writer "
            "method must be individually enumerated with an exact name"
        )

    daos = entry.get("daos")
    if "daos" not in entry:
        errors.append("'daos' must be a non-empty list of non-empty strings")
    elif not isinstance(daos, list) or not daos:
        errors.append("'daos' must be a non-empty list of non-empty strings")
    else:
        for dao in daos:
            if not isinstance(dao, str) or not dao.strip():
                errors.append("every 'daos' entry must be a non-empty string")
                break

    if "barrier_required" not in entry:
        errors.append("'barrier_required' must be a real boolean (true/false)")
    elif not isinstance(entry["barrier_required"], bool):
        errors.append("'barrier_required' must be a real boolean (true/false)")

    for field in ("barrier_via", "delegate_of"):
        if field in entry:
            value = entry[field]
            if not isinstance(value, str) or not value.strip():
                errors.append(f"'{field}' must be a non-empty string when present")

    if "private" in entry and not isinstance(entry["private"], bool):
        errors.append("'private' must be a real boolean (true/false) when present")

    return errors


def structural_entry_metadata_errors(entry):
    """Return human-readable metadata errors for one structural entry, or [].

    Pure helper (no I/O, no ``sys.exit``): validates the COMPLETE structural
    exception contract shared by the loader and scan()'s direct API:

      1. ``entry`` must be a mapping;
      2. only keys in ``STRUCTURAL_ALLOWED_KEYS`` are accepted — an unknown key
         is a configuration error;
      3. the required fields (path, class, method_pattern, operation) must be
         non-empty strings;
      4. optional documentation fields (reason, owner, linked_issue) must be
         non-empty strings when present;
      5. ``path`` must be a canonical policy path;
      6. ``method_pattern`` must be bounded: an exact method name or the single
         migration form ``MIGRATION_\\d+_\\d+``.
    """
    errors = []
    if not isinstance(entry, dict):
        return ["entry must be a mapping"]

    unknown_keys = set(entry) - STRUCTURAL_ALLOWED_KEYS
    if unknown_keys:
        errors.append(f"unknown key(s) {sorted(unknown_keys)}")

    for field in ("path", "class", "method_pattern", "operation"):
        value = entry.get(field)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"'{field}' must be a non-empty string")

    for field in ("reason", "owner", "linked_issue"):
        if field in entry and (
            not isinstance(entry[field], str) or not entry[field].strip()
        ):
            errors.append(f"'{field}' must be a non-empty string when present")

    path = entry.get("path")
    if isinstance(path, str) and path.strip():
        path_error = canonical_policy_path_error(path)
        if path_error:
            errors.append(f"'path' is not canonical: {path_error}")

    mp = entry.get("method_pattern")
    if not _is_valid_method_pattern(mp):
        errors.append(
            f"'method_pattern' must be an exact method name or the bounded "
            f"migration form MIGRATION_\\d+_\\d+, got {mp!r}"
        )
    return errors

# ── Policy loading ────────────────────────────────────────────────────────────

# H2 strict schema: the ONLY keys accepted in an ownership-policy entry.
# Everything else is a config error (exit 2) so typos/misnamed metadata can
# never silently change approval semantics.
OWNERSHIP_ALLOWED_KEYS = frozenset({
    "path", "class", "method", "daos", "operation",
    "barrier_required", "barrier_via", "reason", "owner",
    "linked_issue", "private", "delegate_of",
})

# H2 strict schema: the ONLY keys accepted in a structural-exception entry.
STRUCTURAL_ALLOWED_KEYS = frozenset({
    "path", "class", "method_pattern", "operation",
    "reason", "owner", "linked_issue",
})

_WILDCARD_CHARS = set("*?[]+")


def _is_wildcard_method(method):
    """Return True if ``method`` uses wildcard/regex syntax instead of an exact name.

    Exact writer methods must be individually enumerated.  ``"*"``, glob
    characters, or regex anchors are rejected.
    """
    if not isinstance(method, str):
        return True
    return (
        any(ch in _WILDCARD_CHARS for ch in method)
        or method.startswith("^")
        or method.endswith("$")
    )


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

    Every entry is validated at load time and must have:
      * non-empty string ``path``, ``class``, ``method``, ``operation``,
        ``reason``, ``owner``, ``linked_issue``;
      * ``path`` in CANONICAL form — a repository-relative POSIX path under an
        approved production source root (``app/src/main/java``).  Bare
        basenames, ``..``, backslashes, absolute paths, non-``.kt`` paths, and
        ambiguous suffix paths are rejected with exit 2;
      * ``operation`` equal to the EXACT DAO method name authorized by the
        entry.  The universal ``operation: write`` is rejected as invalid
        policy metadata (exit 2) — it would authorize every mutation and make
        the per-operation guard meaningless;
      * ``daos`` — a non-empty list of non-empty strings;
      * ``barrier_required`` — present and a REAL boolean;
      * ``barrier_via`` — either absent or a non-empty string documenting the
        mediation layer (e.g. ``WorkerExecutionGuard``).

    Malformed entries exit 2 with entry/path context.  Optional metadata (never
    relaxes exact matching):
      * ``private: true``  — the method is a private implementation writer.
      * ``delegate_of``    — the public method that delegates to this writer
        (the delegated-to method is the one approved; the public delegating
        method is NOT approved unless separately listed).

    Wildcard/pattern methods are rejected with exit 2.
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
        # Stable, human-readable context for every error below: prefer the
        # entry's own path, fall back to class, then index.
        label = _entry_label(entry, i, "ownership policy entry")

        # Complete validation — the SAME validator scan()'s direct API uses.
        # Rejects unknown keys, missing/non-string required fields, non-canonical
        # paths, universal ``operation: write``, wildcard methods, missing/empty
        # daos, non-real booleans, and malformed optional metadata with exit 2.
        errors = ownership_entry_metadata_errors(entry)
        if errors:
            for error in errors:
                print(f"ERROR: {error} in {label}: {entry}", file=sys.stderr)
            sys.exit(2)

    return entries

# ── Structural method_pattern validation (fail-closed whitelist) ──────────────
# Accepted patterns:
#   1. Exact method names — plain Kotlin identifiers (word characters only).
#   2. The single bounded migration form MIGRATION_\d+_\d+ (fullmatch only).
# Every other pattern (`.+`, `\w`, character classes, alternation, unbounded
# `.*?`, anchors, or any other regex metacharacter) is rejected at load time.
_EXACT_METHOD_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
# Matches the literal config text `MIGRATION_\d+_\d+` (backslashes as written
# in the YAML source string).  `\\d` matches a literal backslash-d and `\+` a
# literal plus, so the accepted string is exactly MIGRATION_\d+_\d+.
_MIGRATION_FORM_RE = re.compile(r"^MIGRATION_\\d\+_\\d\+$")


def _is_valid_method_pattern(method_pattern):
    """Return True only for whitelisted bounded structural patterns.

    Accepts an exact method name (a plain Kotlin identifier) or the single
    bounded migration form ``MIGRATION_\\d+_\\d+`` via ``re.fullmatch``.
    Everything else — including alternation, character classes, ``\\w``,
    unbounded ``.*?``/``.+``, and anchors — fails closed.
    """
    if not isinstance(method_pattern, str):
        return False
    pattern = method_pattern.strip()
    if not pattern:
        return False
    return bool(
        _EXACT_METHOD_NAME_RE.fullmatch(pattern)
        or _MIGRATION_FORM_RE.fullmatch(pattern)
    )


def load_db_structural_exceptions(exceptions_path=None):
    """Load and validate the DB structural exceptions.

    Returns a list of exception entry dicts.
    Each entry must have: path, class, method_pattern, operation, reason, owner.

    ``path`` must be a canonical policy path (repository-relative POSIX path
    under ``app/src/main/java``) and ``method_pattern`` must be an exact method
    name or the single bounded migration form ``MIGRATION_\\d+_\\d+``.
    Broad regex patterns are rejected with exit 2.
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
        label = _entry_label(entry, i, "structural exception entry")

        # Complete validation — the SAME validator scan()'s direct API uses.
        # Rejects unknown keys, missing/non-string required fields, non-canonical
        # paths, and unbounded method_patterns (``.*``, alternation, anchors,
        # character classes, ...) with exit 2.
        errors = structural_entry_metadata_errors(entry)
        if errors:
            for error in errors:
                print(f"ERROR: {error} in {label}: {entry}", file=sys.stderr)
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


def _scan_dao_var_lines(lines, start, end, exclude=None):
    """Scan the 0-based line range [start, end] for DAO variable declarations.

    Builds a mapping ``variable_name -> Room accessor name`` from:
      * class/constructor property declarations with explicit DAO types
        (``private val groupDao: ExpenseGroupDao`` -> ``expenseGroupDao``);
      * locals assigned from the database accessor
        (``val dao = database.scannedReceiptDao()`` -> ``scannedReceiptDao``),
        including the multi-line ``val x =\\n database.someDao()`` form.

    ``exclude`` is an optional set of 0-based line indices that must be
    skipped (used for the class-scope map so assignments inside method bodies
    can never pollute the class map).  A skipped line always resets any
    pending multi-line local so a continuation line cannot cross a method
    boundary.
    """
    var_map = {}
    pending_var = None

    for i in range(start, end + 1):
        if exclude is not None and i in exclude:
            pending_var = None
            continue

        line = lines[i]
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


def build_dao_var_map(lines, start=0, end=None):
    """Build a class/method-scoped DAO variable map over a 0-based line range.

    Pure helper (no I/O) that tests can exercise directly.  ``end`` defaults to
    the last line.  Returns ``dict: variable_name -> Room accessor name``.
    """
    if end is None:
        end = len(lines) - 1
    if end < start:
        return {}
    return _scan_dao_var_lines(lines, start, end)


def build_class_scope_dao_var_map(lines, start, end, excluded_line_numbers=None):
    """Build the CLASS-scope DAO map: constructor params, class property
    declarations, and class-body-level aliases only.

    ``excluded_line_numbers`` is an iterable of 0-based line indices that
    belong to method bodies.  Assignments on those lines are METHOD-LOCAL and
    must never appear in the class map — otherwise a ``val dao = ...`` in
    method A would authorize method B's unrelated use of ``dao``.
    """
    if excluded_line_numbers is None:
        excluded_line_numbers = set()
    return _scan_dao_var_lines(
        lines, start, end, exclude=set(excluded_line_numbers)
    )


def _resolve_dao_identity(receiver, var_map):
    """Resolve a mutation receiver to a DAO identity used by the policy.

    Priority:
      1. a known scoped property/local (``var_map``) — yields the Room
         accessor name (e.g. ``groupDao: ExpenseGroupDao`` -> ``expenseGroupDao``);
      2. the literal ``\\w+Dao`` naming convention.

    Returns None when the receiver is not a DAO identity (e.g. ``database``,
    ``db``, ``writeBarrier``, a diagnostic sink) — such calls are not DAO
    mutations and are never flagged.
    """
    if receiver in var_map:
        return var_map[receiver]
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*Dao", receiver):
        return receiver
    return None


def _line_of_offset(body, offset):
    """Return the 0-based line index within ``body`` containing ``offset``."""
    return body.count("\n", 0, offset)


def _mask_mutation_body(body):
    """Return a comment/string-masked copy of a Kotlin method body.

    Uses the stateful :func:`_mask_lines_for_structural_scan` so multi-line
    block comments and triple-quoted strings that begin inside the body stay
    masked to their true end, and preserves per-line length plus newline
    positions — char offsets and ``_line_of_offset`` line numbers therefore
    keep pointing at the original source lines.
    """
    return "\n".join(_mask_lines_for_structural_scan(body.split("\n")))


def _extract_mutation_matches(body, var_map=None, out_of_scope_aliases=None,
                              out_of_scope_alias_identities=None):
    """Extract every DAO mutation call from the COMPLETE ``body`` text.

    ``body`` is a Kotlin method body (signature + balanced braces).  The
    whitespace tokens in the call regexes span newlines, so multi-line calls
    such as ``dao\\n    .insert(x)`` are detected — extraction never relies on
    individual source lines.

    Returns a list of match dicts:
      * ``receiver`` — the receiver expression identifier (or chain DAO name);
      * ``dao`` — the resolved DAO identity (Room accessor name);
      * ``op`` — the EXACT DAO method name invoked;
      * ``start`` — char offset of the match inside ``body``;
      * ``lineno`` — 0-based line index (within ``body``) where the match
        starts (preserved for diagnostics);
      * ``out_of_scope`` — True when the receiver is a DAO local alias
        declared in ANOTHER method of the same class.  Such a match is never
        authorizable here (fail closed) even if a policy entry would otherwise
        cover the pair.

    ``var_map`` is the scoped class/method DAO map; ``out_of_scope_aliases``
    is the set of alias names that exist in other methods; and
    ``out_of_scope_alias_identities`` maps those names to the DAO identity for
    diagnostics.  Read-only calls (get/observe/count/exists/find/...) are
    never extracted.

    Extraction runs on a comment/string-MASKED copy of ``body`` (stateful
    masker, line mapping preserved), so a DAO-looking call inside a line
    comment, block comment, string, triple-quoted string, or char literal can
    never become a mutation pair.
    """
    if var_map is None:
        var_map = {}
    if out_of_scope_aliases is None:
        out_of_scope_aliases = frozenset()
    if out_of_scope_alias_identities is None:
        out_of_scope_alias_identities = {}
    matches = []

    def _record(receiver, dao, op, start, out_of_scope):
        matches.append({
            "receiver": receiver,
            "dao": dao,
            "op": op,
            "start": start,
            "lineno": _line_of_offset(body, start),
            "out_of_scope": bool(out_of_scope),
        })

    # The masked body has the same length and newline positions as ``body``,
    # so ``m.start()`` offsets stay valid for ``_line_of_offset`` diagnostics.
    masked_body = _mask_mutation_body(body)

    for m in _MUTATION_CALL_RE.finditer(masked_body):
        receiver = m.group("receiver")
        op = m.group("method")
        if receiver in var_map:
            _record(receiver, var_map[receiver], op, m.start(), False)
        elif receiver in out_of_scope_aliases:
            _record(
                receiver,
                out_of_scope_alias_identities.get(receiver, receiver),
                op,
                m.start(),
                True,
            )
        else:
            dao = _resolve_dao_identity(receiver, var_map)
            if dao is not None:
                _record(receiver, dao, op, m.start(), False)

    for m in _DIRECT_CHAIN_MUTATION_RE.finditer(masked_body):
        _record(m.group("dao"), m.group("dao"), m.group("method"), m.start(), False)

    return matches


def extract_mutation_pairs(body, var_map=None):
    """Extract exact ``(dao_identity, operation)`` pairs from a method body.

    Pure helper (no I/O, no scanning) that tests can exercise directly.

    ``body`` is a Kotlin method body text.  ``var_map`` is the scoped
    class/method DAO variable map (see :func:`build_dao_var_map`).

    Returns a list of ``(dao_identity, operation)`` pairs where ``operation``
    is the EXACT DAO method name invoked (e.g. ``insertOrIgnore``,
    ``archiveGroup``, ``staleAbortIfStillRunning``).  Read-only calls
    (get/observe/count/exists/find/...) are never extracted.
    """
    if var_map is None:
        var_map = {}
    pairs = []
    seen = set()
    for match in _extract_mutation_matches(body, var_map=var_map):
        pair = (match["dao"], match["op"])
        if pair not in seen:
            pairs.append(pair)
            seen.add(pair)
    return pairs


# ── Declaration / body parsing (exact class + method scoping) ─────────────────

def _type_body_end(lines, start):
    """Return the 0-based line index where the type body closes (balanced).

    Walks from the declaration line tracking parens and braces on a
    comment/string-aware copy of the source, so a ``"}"`` literal, a ``// }``
    comment, or a block-comment brace can never close the type body.  The body
    opens at the first ``{`` and ends when braces rebalance.  Body-less
    declarations (interfaces/objects without braces) end on their own line once
    the signature parens are balanced.
    """
    masked = _mask_lines_for_structural_scan(lines)
    depth = 0
    paren = 0
    started = False
    for i in range(start, len(lines)):
        line = masked[i]
        depth += line.count("{") - line.count("}")
        paren += line.count("(") - line.count(")")
        if line.count("{") > 0:
            started = True
        if started and depth == 0:
            return i
        if not started and paren <= 0 and i > start:
            return start
    return len(lines) - 1


def parse_type_declarations(lines):
    """Return every named type declaration in ``lines``.

    Pure helper (no I/O) that tests can exercise directly.

    Each entry is a dict: ``{kind, name, start, end}`` where ``kind`` is
    ``object``/``class``/``interface``, ``name`` is the declared name (never
    derived from the filename), and ``start``/``end`` are 0-based line indices
    of the full balanced declaration (body inclusive).  Nested declarations are
    included.  Duplicate names are NOT collapsed — callers fail closed when a
    policy class name is ambiguous within a file.

    Detection runs on a stateful comment/string mask of the source
    (:func:`_mask_lines_for_structural_scan`), which replaces line comments,
    block comments, strings, triple-quoted strings, and char literals with
    spaces while preserving offsets and newlines.  The ``kind``/``name`` are
    recovered from the CORRESPONDING RAW span only after the masked match
    proves the declaration is code, so fake ``class``/``object``/``interface``
    text inside comments or literals never creates a declaration and never
    alters scope.
    """
    masked = _mask_lines_for_structural_scan(lines)
    decls = []
    for i, line in enumerate(lines):
        m = _TYPE_DECL_NAME_RE.search(masked[i])
        if not m:
            continue
        kind = line[m.start("kind"):m.end("kind")]
        name = line[m.start("name"):m.end("name")]
        end = _type_body_end(lines, i)
        decls.append({
            "kind": kind,
            "name": name,
            "start": i,
            "end": end,
        })
    return decls


# Tokens whose presence at the END of a line force a multi-line expression
# body to continue onto the next line.
_EXPR_CONTINUATION_ENDINGS = (
    ".", ",", "=", "(", "[", "{", "->", "&&", "||", "+", "-", "*", "/", "%",
)

# Tokens a continued line may BEGIN with (leading-dot chains, elvis, etc.).
_EXPR_CONTINUATION_STARTS = (".", "?.", "!!", "?:")

# Control-flow keywords that can continue a compound expression body after a
# top-level block closes (``= if (...) {} else {}``, ``= try {} catch {}``,
# ``= try {} finally {}``).  Without recognizing these, an ``else``/``catch``/
# ``finally`` on a following line would be silently truncated out of the body.
_EXPR_BLOCK_CONTINUATION_RE = re.compile(r"\b(?:else|catch|finally)\b")

# Control-flow keywords whose BRACE-LESS expression-body forms cannot be
# bounded reliably by this scanner (``= if ...`` / ``= when ...`` /
# ``= try ...``).  A brace-less branch has no structural ``{`` the parser can
# track, so its end is unknowable without a full Kotlin grammar.  Such bodies
# fail closed with ``unsupported_expression`` instead of being truncated.
_EXPR_CF_START_RE = re.compile(r"^\s*\b(?:if|when|try)\b")


def _text_starts_with_control_flow(masked_text):
    """Return True when MASKED text begins with a brace-less control-flow
    keyword (``if`` / ``when`` / ``try``).

    Used to recognize expression bodies that begin with ``= if`` / ``= when`` /
    ``= try`` (the keyword may sit on the declaration line after ``=`` or lead
    a continuation line of a multi-line expression).  The mask guarantees
    string/comment content can never fake the keyword.
    """
    return _EXPR_CF_START_RE.match(masked_text) is not None


def _mask_lines_for_structural_scan(lines):
    """Return a copy of ``lines`` with strings/comments masked to spaces.

    Masks Kotlin string literals (single/double/triple-quoted, including
    multi-line triple quotes), char literals, line comments (``// ...``), and
    block comments (``/* ... */``, including multi-line blocks) before any
    brace/paren/bracket accounting, so a ``// }``, a block-comment brace, or a
    literal ``"}"`` can never open or close a body.

    Newline/CR characters are preserved and every other character is replaced
    in place with a space, so the returned list has the same line count and
    each line the same length as the input — source line mapping and char
    offsets stay valid.
    """
    out_lines = []
    in_block_comment = False
    in_triple_string = False
    for line in lines:
        out = list(line)
        n = len(out)
        i = 0
        while i < n:
            ch = out[i]
            if in_block_comment:
                if ch == "*" and i + 1 < n and out[i + 1] == "/":
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                    in_block_comment = False
                    continue
                if ch not in ("\n", "\r"):
                    out[i] = " "
                i += 1
                continue
            if in_triple_string:
                if ch == '"' and line.startswith('"""', i):
                    out[i] = " "
                    out[i + 1] = " "
                    out[i + 2] = " "
                    i += 3
                    in_triple_string = False
                    continue
                if ch not in ("\n", "\r"):
                    out[i] = " "
                i += 1
                continue
            if ch == '"':
                if line.startswith('"""', i):
                    out[i] = " "
                    out[i + 1] = " "
                    out[i + 2] = " "
                    i += 3
                    in_triple_string = True
                    continue
                # regular string literal
                j = i + 1
                while j < n:
                    c = line[j]
                    if c == "\\":
                        j += 2
                        continue
                    if c == '"':
                        break
                    j += 1
                end = j if j < n else n
                for k in range(i, min(end, n)):
                    if out[k] not in ("\n", "\r"):
                        out[k] = " "
                i = min(end + 1, n)
                continue
            if ch == "'":
                # char literal
                j = i + 1
                while j < n:
                    c = line[j]
                    if c == "\\":
                        j += 2
                        continue
                    if c == "'":
                        break
                    j += 1
                end = j if j < n else n
                for k in range(i, min(end, n)):
                    if out[k] not in ("\n", "\r"):
                        out[k] = " "
                i = min(end + 1, n)
                continue
            if ch == "/" and i + 1 < n:
                if out[i + 1] == "/":
                    j = i
                    while j < n:
                        if out[j] not in ("\n", "\r"):
                            out[j] = " "
                        j += 1
                    i = n
                    continue
                if out[i + 1] == "*":
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                    in_block_comment = True
                    continue
            i += 1
        out_lines.append("".join(out))
    return out_lines


def _mask_kotlin_line(line):
    """Stateless single-line mask (strings/comments -> spaces).

    Used by helpers that inspect one line (expression-body ``=`` detection and
    line-end continuation checks).  Multi-line block comments that begin on an
    earlier line are not tracked here — whole-file structural accounting uses
    the stateful :func:`_mask_lines_for_structural_scan` instead.
    """
    return _mask_lines_for_structural_scan([line])[0]


def _expression_body_split(line, initial_depth=0):
    """Return ``(prefix_before_eq, True)`` when ``line`` declares an expression
    body (``fun foo() = expr``), else ``(None, False)``.

    Finds the first ``=`` at paren/bracket depth 0 AFTER the signature's
    parameter list closes, skipping comparison operators (``==``, ``<=``,
    ``>=``, ``!=``, ``=>``) and default-value ``=`` tokens inside the
    parameter list (paren depth >= 1).  ``initial_depth`` carries open
    parens/brackets from earlier signature lines so a multi-line signature
    ending ``) = if (...) {`` is recognized the moment the parameter list
    closes.  A top-level ``{`` before any ``=`` at depth 0 (a block body such
    as ``fun foo() { val x = 1 }``) never matches, so block bodies are never
    mistaken for expression bodies.  Strings and comments are masked first so
    an ``=`` inside a comment or literal can never be mistaken for the
    expression-body ``=``.
    """
    line = _mask_kotlin_line(line)
    depth = initial_depth
    n = len(line)
    i = 0
    while i < n:
        ch = line[i]
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "{" and depth == 0:
            return None, False
        elif ch == "=" and depth == 0:
            prev = line[i - 1] if i > 0 else ""
            nxt = line[i + 1] if i + 1 < n else ""
            if prev in "!<>=:" or nxt in "=>":
                i += 1
                continue
            return line[:i], True
        i += 1
    return None, False


def _line_ends_with_expr_continuation(line):
    """Return True when a MASKED ``line`` ends with a continuation token.

    ``line`` must already be masked (strings/comments replaced by spaces), so
    trailing comments never hide a real trailing ``{``/``+``/``.`` and a
    comment-only trailing token can never force a spurious continuation.
    """
    s = line.rstrip()
    if not s:
        return False
    return s.endswith(_EXPR_CONTINUATION_ENDINGS)


def _next_line_starts_with_expr_continuation(lines, next_idx, bound):
    """Return True when the next non-blank line before ``bound`` begins with a
    continuation token (leading dot chain / elvis / not-null assert)."""
    for j in range(next_idx, bound):
        s = lines[j].strip()
        if not s:
            continue
        return s.startswith(_EXPR_CONTINUATION_STARTS)
    return False


def _next_line_starts_with_expr_keyword(lines, next_idx, bound):
    """Return True when the next non-blank line before ``bound`` begins with a
    control-flow continuation keyword (``else`` / ``catch`` / ``finally``).

    After a top-level block in a compound expression body closes, ``else`` /
    ``catch`` / ``finally`` may begin on the following line
    (``= if (...) {} \\n else {}``, ``= try {} \\n catch {}``).  Recognizing
    them keeps the expression body from being truncated at the closing brace.
    """
    for j in range(next_idx, bound):
        s = lines[j].strip()
        if not s:
            continue
        return _EXPR_BLOCK_CONTINUATION_RE.match(s) is not None
    return False


def _line_trails_expr_block_keyword(line):
    """Return True when a MASKED ``line`` carries a control-flow continuation
    keyword after its last ``}`` (e.g. ``} else`` or ``} catch (e: X)``).

    Handles the compact single-line continuation form
    (``} else \\n ...`` / ``} catch (...) \\n ...``) where the keyword does
    not open a block on the same line.
    """
    idx = line.rfind("}")
    if idx == -1:
        return False
    tail = line[idx + 1:].strip()
    if not tail:
        return False
    return _EXPR_BLOCK_CONTINUATION_RE.match(tail) is not None


def _next_non_blank_line_opens_block(masked_lines, next_idx, bound):
    """Return True when the next non-blank line before ``bound`` starts with
    ``{`` (a braced continuation branch opened on its own line)."""
    for j in range(next_idx, bound):
        s = masked_lines[j].strip()
        if not s:
            continue
        return s.startswith("{")
    return False


def _continuation_branch_opens_brace(masked_lines, line, next_idx, bound):
    """Return True when a control-flow continuation (``else``/``catch``/
    ``finally``) that keeps a compound expression body going is followed by a
    ``{`` on the same line or on the next non-blank line — i.e. the branch is
    provably braced.

    ``line`` is the current MASKED line; ``next_idx`` is the index of the
    following line (also masked).  Handles both the trailing-keyword form
    (``} else ...`` / ``} catch (...) ...``) and the next-line-keyword form
    (``}\\nelse ...``).  A brace-less branch returns False so callers fail
    closed with ``unsupported_expression`` instead of silently truncating the
    branch out of the method body.
    """
    idx = line.rfind("}")
    if idx != -1:
        tail = line[idx + 1:].strip()
        if tail:
            m = _EXPR_BLOCK_CONTINUATION_RE.match(tail)
            if m:
                if "{" in tail[m.end():]:
                    return True
                return _next_non_blank_line_opens_block(
                    masked_lines, next_idx, bound
                )
    for j in range(next_idx, bound):
        s = masked_lines[j].strip()
        if not s:
            continue
        m = _EXPR_BLOCK_CONTINUATION_RE.match(s)
        if not m:
            return False
        if "{" in s[m.end():]:
            return True
        return _next_non_blank_line_opens_block(masked_lines, j + 1, bound)
    return False


def _join_body_lines(body_lines):
    """Join collected body lines into a single body text.

    Each collected line may still carry its trailing line terminator (``\n`` /
    ``\r\n``) when it came from ``f.readlines()``.  Stripping those terminators
    before joining with ``\n`` guarantees EXACTLY one newline per line
    boundary, which keeps ``_line_of_offset`` (and therefore the 1-based
    ``abs_lineno`` used in violation diagnostics) precise.
    """
    return "\n".join(line.rstrip("\r\n") for line in body_lines)


def _method_body_end_and_text_detailed(lines, start, bound=None):
    """Return ``(end_line, body_text, unsupported_expression,
    unterminated_braced_body)`` for the function declared at ``start``.

    Walks from the declaration line tracking parens, brackets, and braces on a
    comment/string-aware copy of the source (``_mask_lines_for_structural_scan``
    replaces strings and comments with spaces, preserving line mapping), so
    ``"}"`` inside a literal, a ``// }`` line comment, or a block-comment
    brace can never close a body.  The body opens at the first ``{`` and runs
    until braces rebalance.  A signature-only/abstract declaration (no ``{``,
    no ``=`` expression body) ends at ``start`` with an empty body.

    Expression bodies (``= expr``) are parsed to their COMPLETE boundary — a
    multi-line expression is never truncated to one line.  Expression mode is
    detected BEFORE block-depth processing, so the ``{`` that opens a
    control-flow branch on the SAME line as ``= if`` / ``= try`` / ``= when``
    (``fun foo() = if (x) { ... }``) cannot hide the expression body, and a
    multi-line signature ending ``) = if (...) {`` is recognized too.
    Continuation is driven by bracket/brace/paren balance plus line-end/
    line-start tokens, and by control-flow continuation keywords (``else`` /
    ``catch`` / ``finally``) that can follow a closed top-level block, so
    ``= if (condition) { ... } else { ... }`` and ``= try { ... } catch { ... }``
    bodies (same-line or split across lines) are bounded structurally instead
    of being truncated at the first ``}``.  If a multi-line expression cannot
    be bounded within ``bound`` (inclusive line index, exclusive as a count),
    ``unsupported_expression`` is True so callers can fail closed instead of
    silently missing mutations inside the expression.

    Brace-less control-flow expression bodies (``= if ...`` / ``= when ...`` /
    ``= try ...``) have no structural ``{`` the parser can track, so their
    boundary cannot be proven.  They fail closed with ``unsupported_expression``
    True rather than authorizing a truncated body or silently omitting the
    mutations in the missing branch lines.  This covers:
      * a brace-less header whose branch body lives on later lines
        (``fun f() = if (x)`` + branch lines);
      * a brace-less ``else`` / ``catch`` / ``finally`` branch after a braced
        top-level block (``} else\\n    dao.update(...)``);
      * a brace-less ``when`` header with later branch lines.
    Already-supported braced forms (``= if (...) { ... }``, ``= when (x) {``,
    ``= try { ... } catch { ... }``) are unchanged.

    A NORMAL (non-expression) braced method body that never rebalances before
    ``bound`` is an unterminated method: the parser cannot prove where the body
    ends, so it returns ``unterminated_braced_body=True``.  Callers must fail
    closed with the controlled ``UNSUPPORTED_METHOD_BODY`` violation instead of
    authorizing mutations extracted from the partial body.
    """
    if start >= len(lines):
        return start, "", False, False
    if bound is None:
        bound = min(start + 200, len(lines))
    else:
        bound = min(bound, len(lines))

    # Mask strings/comments once for the whole file so line mapping stays
    # consistent even across multi-line strings/block comments; the RAW lines
    # are still what lands in ``body`` for mutation extraction.
    masked_lines = _mask_lines_for_structural_scan(lines)

    depth = 0
    paren = 0
    bracket = 0
    body_lines = []
    started = False
    expr_mode = False
    expr_begins_with_control_flow = False
    expr_consumed = False
    top_block_seen = False
    end = start

    for i in range(start, bound):
        raw_line = lines[i]
        line = masked_lines[i]
        body_lines.append(raw_line)
        end = i
        closed_top_block = False
        # Expression-mode detection runs BEFORE block-depth processing so a
        # control-flow block that opens on the same line as ``= if`` /
        # ``= try`` / ``= when`` (``fun foo() = if (x) {``) can never hide the
        # expression body behind the ``{`` that sets ``started``.  ``paren``
        # here is the depth accumulated by EARLIER signature lines, so a
        # multi-line signature ending ``) = if (...) {`` is recognized the
        # moment the parameter list closes.  The statefully masked line is
        # used so an ``=`` inside a cross-line comment or triple-quoted string
        # cannot fake an expression body.
        if not started:
            _prefix, is_expr = _expression_body_split(line, paren)
            if is_expr:
                expr_mode = True
                # A brace-less control-flow expression body (``= if ...`` /
                # ``= when ...`` / ``= try ...``) cannot be bounded reliably;
                # remember it so the method fails closed instead of silently
                # truncating the body at the first provably-ended line.
                expr_begins_with_control_flow = _text_starts_with_control_flow(
                    line[len(_prefix) + 1:]
                )
            elif (
                expr_mode
                and paren == 0 and bracket == 0 and depth == 0
                and not expr_begins_with_control_flow
            ):
                # A continuation line of a multi-line brace-less expression
                # body that begins with ``if`` / ``when`` / ``try`` marks the
                # expression as an unbounded control-flow form.
                expr_begins_with_control_flow = _text_starts_with_control_flow(
                    line.lstrip()
                )
        for ch in line:
            if ch == '{':
                if expr_mode and paren == 0 and bracket == 0 and depth == 0:
                    top_block_seen = True
                depth += 1
                started = True
            elif ch == '}':
                if started:
                    if expr_mode and depth == 1:
                        closed_top_block = True
                    depth -= 1
            elif ch == '(':
                paren += 1
            elif ch == ')':
                paren -= 1
            elif ch == '[':
                bracket += 1
            elif ch == ']':
                bracket -= 1
        if started and depth == 0:
            # A block-form compound expression body (``= if (...) {}`` /
            # ``= try {}``) rebalances its top-level block here, but the
            # expression may continue with ``else``/``catch``/``finally`` on a
            # following line — do not truncate it at the closing brace.
            if expr_mode and top_block_seen and (
                _next_line_starts_with_expr_keyword(
                    masked_lines, i + 1, bound
                )
                or _line_trails_expr_block_keyword(line)
            ):
                # The continuation keyword's branch is only provably bounded
                # when it opens a ``{`` on its own line or the next line.
                # A brace-less branch (``} else\\n    dao.update(...)``) cannot
                # be tracked safely — fail closed instead of truncating it.
                if not _continuation_branch_opens_brace(
                    masked_lines, line, i + 1, bound
                ):
                    return end, _join_body_lines(body_lines), True, False
            else:
                return end, _join_body_lines(body_lines), False, False
        if not started:
            if expr_mode:
                expr_consumed = True
                if (
                    paren == 0 and bracket == 0 and depth == 0
                    and not _line_ends_with_expr_continuation(line)
                    and not _next_line_starts_with_expr_continuation(
                        masked_lines, i + 1, bound
                    )
                    and not (
                        top_block_seen
                        and _next_line_starts_with_expr_keyword(
                            masked_lines, i + 1, bound
                        )
                    )
                    and not (
                        closed_top_block
                        and _line_trails_expr_block_keyword(line)
                    )
                ):
                    return (
                        end,
                        _join_body_lines(body_lines),
                        bool(expr_begins_with_control_flow),
                        False,
                    )
            elif paren == 0 and i > start:
                return start, "", False, False

    # Reached the bound without a boundary: an unbounded expression body is
    # reported as unsupported so the scanner can fail closed.  A NORMAL braced
    # method body that never closes before the bound is an unterminated method
    # (the parser cannot prove where the body ends) and is reported with
    # ``unterminated_braced_body`` so callers fail closed with
    # UNSUPPORTED_METHOD_BODY instead of authorizing mutations extracted from
    # the partial body.
    # ``expr_mode`` covers expression bodies whose first ``{`` arrived on the
    # declaration line (``= if (...) {``) — those never pass through the
    # ``not started`` branch to set ``expr_consumed``, yet they must still
    # fail closed when the expression cannot be bounded.
    if started or expr_consumed:
        if expr_mode or expr_consumed:
            return end, _join_body_lines(body_lines), True, False
        return end, _join_body_lines(body_lines), False, True
    return start, "", False, False


def _method_body_end_and_text(lines, start):
    """Return (end_line, body_text) for the function declared at ``start``.

    Walks from the declaration line tracking parens and braces.  The body opens
    at the first ``{``; the body text runs until braces rebalance.  If the
    signature's parens balance and no body brace opens (abstract/signature-only
    declaration) the function ends at ``start`` with an empty body.  Expression
    bodies (``= expr``) are parsed to their complete boundary — multi-line
    expression bodies are NOT truncated to one line.
    """
    end, body, _, _ = _method_body_end_and_text_detailed(lines, start)
    return end, body


def _extract_method_body_from_lines(lines, start):
    """Return the balanced body text of the function declared at ``start``.

    Fails closed: a signature-only/abstract declaration (no ``{`` body opener
    and no ``=`` expression body) NEVER absorbs later methods — evidence is
    only ever collected from the declaration's true body.
    """
    return _method_body_end_and_text(lines, start)[1]


def extract_method_body(lines, start):
    """Pure helper: return the balanced body text of a function (or \"\")."""
    return _extract_method_body_from_lines(lines, start)


def parse_function_declarations(lines, start, end):
    """Return every function declaration in the 0-based line range [start, end].

    Pure helper (no I/O) that tests can exercise directly.

    Each entry is a dict: ``{name, start, end, body, unsupported_expression,
    unterminated_braced_body}``.  ``end`` is the line where the balanced body
    closes; ``body`` is the full balanced text (signature + body).
    Signature-only/abstract declarations get an empty body and ``end == start``.
    ``unsupported_expression`` is True when a multi-line expression body could
    not be bounded within the range — callers must fail closed.
    ``unterminated_braced_body`` is True when a normal (non-expression) braced
    method body never closes within the range — callers must fail closed with
    ``UNSUPPORTED_METHOD_BODY`` instead of authorizing mutations from the
    partial body.

    Detection runs on a stateful comment/string mask of the source
    (:func:`_mask_lines_for_structural_scan`), which replaces line comments,
    block comments, strings, triple-quoted strings, and char literals with
    spaces while preserving offsets and newlines.  The function name is
    recovered from the CORRESPONDING RAW span only after the masked match
    proves the declaration is code, so fake ``fun`` text inside comments or
    literals never creates a declaration and never absorbs (or skips) a real
    declaration that follows it.
    """
    masked = _mask_lines_for_structural_scan(lines)
    decls = []
    i = start
    while i <= end:
        m = FUN_DECL_RE.search(masked[i])
        if m:
            name = lines[i][m.start(1):m.end(1)]
            fend, body, unsupported, unterminated = _method_body_end_and_text_detailed(
                lines, i, bound=end + 1
            )
            decls.append({
                "name": name,
                "start": i,
                "end": min(fend, end),
                "body": body,
                "unsupported_expression": unsupported,
                "unterminated_braced_body": unterminated,
            })
            i = fend + 1
        else:
            i += 1
    return decls


def innermost_type_at(types, lineno):
    """Return the innermost type declaration containing 0-based ``lineno``.

    Deterministic: for a line inside nested declarations the type with the
    largest start line wins (the innermost).  Returns None for top-level code.
    """
    best = None
    for t in types:
        if t["start"] <= lineno <= t["end"]:
            if best is None or t["start"] > best["start"]:
                best = t
    return best


def innermost_method_at(methods, lineno):
    """Return the innermost method declaration containing 0-based ``lineno``.

    Deterministic: for a line inside nested/local functions the declaration
    with the largest start line wins.  Returns None for class-level code.
    """
    best = None
    for m in methods:
        if m["start"] <= lineno <= m["end"]:
            if best is None or m["start"] > best["start"]:
                best = m
    return best

# ── Policy matching ───────────────────────────────────────────────────────────

def _normalize_policy_for_scan(entries, path_field="path"):
    """Attach ``_canonical_path`` to each entry for exact scan-time matching.

    Entries whose path is not canonical (bare basename, suffix, backslash,
    absolute, outside the approved production source root) get
    ``_canonical_path = None`` and can never authorize a mutation (fail
    closed).  This protects ``scan()`` even when it is called directly with
    hand-built policy dicts that bypass the loaders.
    """
    out = []
    for e in entries:
        if not isinstance(e, dict):
            continue
        e2 = dict(e)
        raw = e.get(path_field)
        e2["_canonical_path"] = canonical_policy_path(raw) if isinstance(raw, str) else None
        out.append(e2)
    return out


def matches_policy_pair(entries, file_rel, class_name, method_name, dao, op):
    """Return the first ownership entry that EXACTLY authorizes the pair.

    Pure authorization core (no I/O, no scanning) that tests can exercise
    directly.  All five dimensions must match:
      * canonical path equality (``_canonical_path == file_rel``);
      * exact class name;
      * exact method name;
      * exact DAO identity in the entry's ``daos`` list;
      * EXACT operation equality.

    Entries whose ``operation`` is the universal ``write`` or whose path is
    not canonical are invalid metadata and never authorize.  Returns None when
    no entry authorizes the pair (fail closed).
    """
    for e in entries:
        if e.get("_canonical_path") != file_rel:
            continue
        if e.get("operation") == "write":
            continue
        if e.get("operation") != op:
            continue
        if e.get("class") != class_name:
            continue
        if e.get("method") != method_name:
            continue
        daos = e.get("daos") or []
        if dao not in daos:
            continue
        return e
    return None


def _find_exact_authorization(entries, file_rel, class_name, method_name, dao, op, ambiguous_names):
    """Authorize a pair unless the class identity is ambiguous in its file.

    ``ambiguous_names`` is the set of type names declared more than once in
    the file being scanned.  If the mutation's enclosing class name is in that
    set, the policy class identity cannot be resolved deterministically and
    the pair fails closed.
    """
    if class_name in ambiguous_names:
        return None
    return matches_policy_pair(entries, file_rel, class_name, method_name, dao, op)


def _iter_file_operation_occurrences(masked_text):
    """Yield ``(operation, lineno_0)`` for every exact file-operation occurrence.

    Runs on the COMPLETE statefully masked text (offsets/newlines preserved),
    so a supported call may span line breaks
    (``db.execSQL\\n("...")``, ``SQLiteDatabase.openDatabase\\n(...)``) and is
    attributed to the 0-based line of its call-start token.  Only the exact
    call/token evidence forms are detected — prefix-like identifiers
    (``getDatabasePathway``, ``openDatabaseHelper``, ``mywritableDatabase``)
    and comment/string text can never match.  Results are source-ordered for
    deterministic diagnostics.
    """
    matches = []
    for operation, evidence_re in _FILE_OP_PATTERNS:
        for m in evidence_re.finditer(masked_text):
            matches.append((m.start(), operation))
    matches.sort(key=lambda item: item[0])
    for offset, operation in matches:
        yield operation, masked_text.count("\n", 0, offset)


def _iter_unsupported_file_operation_tokens(masked_text):
    """Yield ``(operation, lineno_0)`` for supported operation identifiers in
    code whose EXACT call evidence cannot be proven.

    A supported operation token (``execSQL``, ``openDatabase``,
    ``getDatabasePath``, ``.deleteRecursively``) that appears in the MASKED
    text but is not followed by its call form (``NAME(`` /
    ``.deleteRecursively()``) cannot be parsed safely.  It is reported as a
    controlled unsupported structural operation (fail closed) instead of
    being silently skipped.  ``writableDatabase`` has no unparseable form —
    the exact token itself is the complete evidence.  Results are
    source-ordered.
    """
    matches = []
    for operation, token_re, call_re in _FILE_OP_UNSUPPORTED_TOKENS:
        for m in token_re.finditer(masked_text):
            if call_re.match(masked_text, m.start()):
                continue  # provable exact call — detected normally
            matches.append((m.start(), operation))
    matches.sort(key=lambda item: item[0])
    for offset, operation in matches:
        yield operation, masked_text.count("\n", 0, offset)


def _matches_structural_exception(rel_path, class_name, decls, operation, structural_exceptions):
    """Return True if a DETECTED ``operation`` is covered by an exact
    structural entry.

    Matching is EXACT and the occurrence has already proven the exact
    COMMENT/STRING-masked call/token evidence (the evidence IS the
    detection):
      * a non-``raw_`` entry must name the operation EXACTLY — a raw
        substring match is never used, so a prefix-like identifier can never
        satisfy evidence for the real operation;
      * a ``raw_`` entry accepts any provably-exact operation — it can never
        authorize prefix-like identifiers (``getDatabasePathway``,
        ``openDatabaseHelper``, ``mywritableDatabase``) because detection
        never produces such occurrences;
      * the method_pattern must ``re.fullmatch`` one of the ACTUAL enclosing
        declaration names (function, object, or ``val NAME = object``
        migration declaration).  Substring matching is never used, so
        ``verify`` cannot match ``verifyInternal`` and a declaration from an
        unrelated earlier method cannot approve the operation.
    """
    for exc in structural_exceptions:
        if exc.get("_canonical_path") != rel_path:
            continue
        if exc.get("class") != class_name:
            continue
        op_type = exc["operation"]
        if not op_type.startswith("raw_"):
            if op_type != operation:
                continue
        method_pattern = exc["method_pattern"]
        for decl_name, _start in decls:
            if re.fullmatch(method_pattern, decl_name):
                return True

    return False


def _barrier_before_line(lines, fun_start, mutation_lineno):
    """Return True if a writeBarrier call appears between fun_start and mutation_lineno."""
    for i in range(fun_start, min(mutation_lineno - 1, len(lines))):
        if WRITE_BARRIER_PATTERN.search(lines[i]):
            return True
    return False


def _authorize_mutation_matches(matches, own, rel_path, class_name, method_name,
                                ambiguous, lines, method_for_barrier, violations):
    """Authorize every extracted ``(dao, op)`` match; append violations.

    All-or-nothing per match: an out-of-scope DAO alias, an uncovered pair, or
    a missing required write barrier each produce a violation.  ``matches``
    must carry an absolute 1-based ``abs_lineno`` for diagnostics.
    """
    for match in matches:
        dao = match["dao"]
        op = match["op"]
        mutation_lineno = match["abs_lineno"]
        line_text = (
            lines[mutation_lineno - 1].rstrip()
            if 0 <= mutation_lineno - 1 < len(lines)
            else ""
        )

        if match["out_of_scope"]:
            violations.append((
                rel_path, mutation_lineno, line_text,
                "OUT_OF_SCOPE_DAO_ALIAS: receiver "
                f"{match['receiver']!r} is a DAO local alias declared in "
                f"another method of class={class_name} and cannot authorize "
                f"method={method_name} dao={dao} op={op} "
                "rule=db_ownership_policy"
            ))
            continue

        entry = _find_exact_authorization(
            own, rel_path, class_name, method_name, dao, op, ambiguous
        )
        if entry is None:
            if class_name in ambiguous:
                detail = (
                    f"ambiguous class declaration {class_name!r} in source file"
                )
            else:
                detail = (
                    f"no exact policy entry for class={class_name} "
                    f"method={method_name} dao={dao} op={op}"
                )
            violations.append((
                rel_path, mutation_lineno, line_text,
                f"UNALLOWLISTED_CLASS: {detail} rule=db_ownership_policy"
            ))
            continue

        if entry.get("barrier_required", False):
            barrier_ok = False
            if method_for_barrier is not None:
                barrier_ok = _barrier_before_line(
                    lines, method_for_barrier["start"], mutation_lineno
                )
            if not barrier_ok:
                violations.append((
                    rel_path, mutation_lineno, line_text,
                    f"MISSING_WRITE_BARRIER: class={class_name} "
                    f"method={method_name} dao={dao} op={op} "
                    "rule=db_ownership_policy"
                ))


# ── Scan ──────────────────────────────────────────────────────────────────────

def scan(source_dir, ownership_policy=None, structural_exceptions=None):
    """Scan Kotlin sources for DB access boundary violations.

    Args:
        source_dir: Path to the Java/Kotlin source directory.
        ownership_policy: List of ownership policy entry dicts (from
            load_db_ownership_policy or hand-built for tests).  Every entry is
            validated with the COMPLETE metadata validator the loader uses
            (ownership_entry_metadata_errors) BEFORE scanning; an invalid entry
            (missing required fields, bad booleans, empty daos, unknown keys,
            non-canonical paths, wildcard methods, or ``operation: write``)
            emits a controlled ``DB_SCAN_INVALID_POLICY`` configuration error
            (mapped to exit 2 by main()) and is never authorized (fail closed).
        structural_exceptions: List of structural exception entry dicts.
            Validated with structural_entry_metadata_errors before scanning;
            an invalid entry (non-canonical path, unknown key, missing required
            field, or a broad method_pattern such as ``.*``) emits a controlled
            ``DB_SCAN_INVALID_POLICY`` configuration error and cannot approve
            file operations.

    Returns:
        (violations, files_scanned) tuple.  Each violation is a 4-tuple
        (rel_path, lineno, line_text, reason).

    Scan semantics:
      * class/object/interface names come from the ACTUAL Kotlin declarations
        in each file — never from the filename;
      * every mutation is attributed to its exact enclosing class and exact
        enclosing method (balanced body) inside that class — there is no
        file-wide or filename-wide fallback;
      * DAO mutations OUTSIDE a resolved method — class initializers,
        top-level code, and top-level function bodies — fail closed with
        UNSUPPORTED_DAO_SCOPE instead of being silently skipped; a
        method-scoped policy entry can never authorize them;
      * DAO mutations are extracted from the COMPLETE method body, so
        multi-line calls (``dao\\n    .insert(x)``) are detected and never
        authorized on a per-line basis;
      * file operations are detected from the COMPLETE statefully masked
        file text with EXACT call/token evidence — a supported call may span
        line breaks (``db.execSQL\\n("...")``, ``SQLiteDatabase.openDatabase\\n(...)``)
        and keeps the exact source line of its call-start token, while
        prefix-like identifiers (``getDatabasePathway``,
        ``openDatabaseHelper``, ``mywritableDatabase``) and comment/string
        text are never detected; ``raw_`` structural operations therefore
        authorize only provably-exact operations, never prefix-like text;
      * a supported operation token that cannot be proven as an exact call
        fails closed with UNSUPPORTED_STRUCTURAL_OP instead of being
        silently skipped;
      * class-scope DAO maps contain ONLY constructor params, class property
        declarations, and class-body aliases — a DAO local declared inside one
        method NEVER appears in another method's map, and using such an alias
        in a different method fails closed (OUT_OF_SCOPE_DAO_ALIAS);
      * multi-line expression bodies are parsed to their complete boundary; an
        expression that cannot be bounded fails closed
        (UNSUPPORTED_EXPRESSION_BODY) instead of silently skipping mutations;
        compound control-flow expression bodies (``= if (...) {} else {}``,
        ``= try {} catch {}``) are bounded structurally and never truncated at
        a closing brace;
      * a normal braced method body that never closes within its enclosing
        type fails closed (UNSUPPORTED_METHOD_BODY) — mutations inside the
        partial body are never authorized, even by a fully-covering policy;
      * brace/paren accounting is comment- and string-aware: Kotlin strings,
        char literals, line comments, and block comments are masked before
        counting, so a ``// }`` or block-comment brace can never close a body
        (source line mapping is preserved);
      * unreadable/undecodable sources emit only the controlled
        ``DB_SCAN_UNREADABLE_FILE`` diagnostic with the canonical relative
        path and make the scan exit 2 — raw exception messages or filesystem
        paths are never leaked;
      * a method/line is authorized only when EVERY extracted
        ``(dao_identity, operation)`` pair is covered by an exact policy entry
        for the same canonical path, class, method, DAO, and operation —
        mixed approved/unapproved pairs fail.
    """
    if ownership_policy is None:
        ownership_policy = []
    if structural_exceptions is None:
        structural_exceptions = []

    violations = []
    files_scanned = 0

    # Direct-API validation: every entry supplied to scan() is validated with
    # the COMPLETE metadata validators the loaders use (unknown keys, missing
    # required fields, bad booleans, empty daos, non-canonical paths, wildcard
    # methods, ``operation: write``, unbounded ``method_pattern`` such as
    # ``.*``).  An invalid entry emits a controlled ``DB_SCAN_INVALID_POLICY``
    # configuration error (mapped to exit 2 by main()) and is never authorized
    # (fail closed) — a malformed policy can never silently approve a mutation.
    valid_ownership = []
    for i, entry in enumerate(ownership_policy):
        errors = ownership_entry_metadata_errors(entry)
        if errors:
            label = _entry_label(entry, i, "ownership entry")
            violations.append((
                "config:db_ownership_policy", 0, "",
                "ERROR: DB_SCAN_INVALID_POLICY: " + label + " rejected: "
                + "; ".join(errors),
            ))
        else:
            valid_ownership.append(entry)
    valid_structural = []
    for i, entry in enumerate(structural_exceptions):
        errors = structural_entry_metadata_errors(entry)
        if errors:
            label = _entry_label(entry, i, "structural entry")
            violations.append((
                "config:db_structural_exceptions", 0, "",
                "ERROR: DB_SCAN_INVALID_POLICY: " + label + " rejected: "
                + "; ".join(errors),
            ))
        else:
            valid_structural.append(entry)
    ownership_policy = valid_ownership
    structural_exceptions = valid_structural

    own = _normalize_policy_for_scan(ownership_policy, "path")
    struct = _normalize_policy_for_scan(structural_exceptions, "path")

    for root, dirs, files in os.walk(source_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

        for filename in files:
            if not filename.endswith(".kt"):
                continue

            # DAO interface files define the mutators; they are not callers.
            # They are still counted so a source tree containing only DAO files
            # is distinguishable from an empty/misconfigured source tree.
            files_scanned += 1
            if filename.endswith("Dao.kt"):
                continue

            filepath = os.path.join(root, filename)
            rel_path = _scanned_file_canonical_path(filepath)

            try:
                with open(filepath, encoding="utf-8") as f:
                    lines = f.readlines()
            except (OSError, UnicodeDecodeError):
                # Controlled infrastructure error code only.  The raw
                # filesystem/decode exception (which can embed absolute paths,
                # SQL bytes, or arbitrary payload text) is never emitted; the
                # canonical repository-relative path is reported so operators
                # can still locate the file without leaking filesystem details.
                violations.append((
                    rel_path, 0, "",
                    "ERROR: DB_SCAN_UNREADABLE_FILE: cannot read Kotlin "
                    f"source file (path={rel_path})"
                ))
                continue

            # Parse ACTUAL class/object/interface declarations (nested
            # included).  Duplicate names are ambiguous — fail closed.
            types = parse_type_declarations(lines)
            class_counts = Counter(t["name"] for t in types)
            ambiguous = {name for name, count in class_counts.items() if count > 1}

            # Class-scoped DAO maps + function declarations per type.  The
            # class map contains ONLY constructor params / class property
            # declarations / class-body aliases — lines belonging to any
            # method body are excluded, so a ``val dao = ...`` inside method A
            # can never pollute the class map.  Each method's map is class
            # scope + that exact method's own locals; aliases declared in
            # OTHER methods are tracked as out-of-scope and fail closed.
            type_maps = {}
            type_methods = {}
            method_maps = {}
            method_out_of_scope = {}
            method_unsupported = {}
            method_unterminated = {}
            method_match_cache = {}
            type_all_local_aliases = {}
            type_all_local_alias_identities = {}
            for t in types:
                methods = parse_function_declarations(lines, t["start"], t["end"])
                type_methods[id(t)] = methods

                method_body_lines = set()
                for m in methods:
                    method_body_lines.update(range(m["start"], m["end"] + 1))
                type_maps[id(t)] = build_class_scope_dao_var_map(
                    lines, t["start"], t["end"],
                    excluded_line_numbers=method_body_lines,
                )

                all_locals = {}
                for m in methods:
                    body_lines = m["body"].split("\n")
                    local_map = build_dao_var_map(body_lines, 0, len(body_lines) - 1)
                    all_locals.update(local_map)
                    method_maps[id(m)] = {**type_maps[id(t)], **local_map}
                    method_unsupported[id(m)] = bool(
                        m.get("unsupported_expression", False)
                    )
                    method_unterminated[id(m)] = bool(
                        m.get("unterminated_braced_body", False)
                    )
                type_all_local_aliases[id(t)] = set(all_locals)
                type_all_local_alias_identities[id(t)] = all_locals
                for m in methods:
                    method_out_of_scope[id(m)] = (
                        type_all_local_aliases[id(t)] - set(method_maps[id(m)])
                    )

                # Pre-extract mutations from the COMPLETE method body so
                # multi-line DAO calls (receiver on one line, ``.insert(...)``
                # on the next) are never missed by per-line extraction.
                for m in methods:
                    matches = _extract_mutation_matches(
                        m["body"],
                        var_map=method_maps[id(m)],
                        out_of_scope_aliases=method_out_of_scope[id(m)],
                        out_of_scope_alias_identities=(
                            type_all_local_alias_identities[id(t)]
                        ),
                    )
                    method_match_cache[id(m)] = [
                        {
                            **match,
                            "abs_lineno": m["start"] + match["lineno"] + 1,
                        }
                        for match in matches
                    ]

            # ── File-operation guard ──────────────────────────────────────
            # Detection runs on the COMPLETE stateful comment/string mask of
            # the WHOLE file (offsets/newlines preserved), so fake `execSQL(`,
            # `openDatabase(`, `writableDatabase`, `getDatabasePath`, or
            # `.deleteRecursively()` text inside line comments, block
            # comments, strings, triple-quoted strings, or char literals —
            # including multi-line blocks that start on an earlier line — can
            # never be detected as a file operation.  Every REAL supported
            # operation is detected with its EXACT call/token evidence even
            # when the call spans line breaks (`db.execSQL\n("...")`,
            # `SQLiteDatabase.openDatabase\n(...)`), and it keeps the exact
            # source line of its call-start token for attribution.  A
            # supported operation token that cannot be proven as an exact
            # call fails closed with UNSUPPORTED_STRUCTURAL_OP instead of
            # being silently skipped.
            masked_lines = _mask_lines_for_structural_scan(lines)
            masked_text = _join_body_lines(masked_lines)

            for operation, lineno_0 in _iter_file_operation_occurrences(masked_text):
                lineno = lineno_0 + 1
                # Associate the operation with its actual enclosing declarations.
                decls = _declarations_in_scope(lines, lineno_0)
                t = innermost_type_at(types, lineno_0)
                class_name = t["name"] if t else "<top-level>"

                if _matches_structural_exception(
                    rel_path, class_name, decls, operation, struct
                ):
                    continue

                violations.append((
                    rel_path, lineno, lines[lineno - 1].rstrip(),
                    "FORBIDDEN_FILE_OP: DB file operation outside approved "
                    f"structural exception (class={class_name} op={operation} "
                    "rule=db_structural_exceptions)"
                ))

            for operation, lineno_0 in _iter_unsupported_file_operation_tokens(masked_text):
                lineno = lineno_0 + 1
                decls = _declarations_in_scope(lines, lineno_0)
                t = innermost_type_at(types, lineno_0)
                class_name = t["name"] if t else "<top-level>"
                violations.append((
                    rel_path, lineno, lines[lineno - 1].rstrip(),
                    "UNSUPPORTED_STRUCTURAL_OP: supported DB file operation "
                    f"token {operation} could not be parsed as an exact call "
                    f"(class={class_name} rule=db_structural_exceptions)"
                ))

            # ── DAO mutation guard ────────────────────────────────────────
            # Consume the COMPLETE method-body match cache.  Each method's
            # mutations were extracted from its full balanced body, so
            # multi-line calls (receiver on one line, ``.insert(...)`` on the
            # next) are always detected.  There is NO per-line fallback that
            # could miss a multi-line call.
            for t in types:
                class_name = t["name"]
                for m in type_methods.get(id(t), []):
                    method_name = m["name"]

                    # Fail closed on an unterminated normal (braced) method
                    # body: the parser cannot prove where the body ends, so
                    # mutations extracted from the partial body can never be
                    # authorized, even by a fully-covering policy.
                    if method_unterminated.get(id(m), False):
                        violations.append((
                            rel_path, m["start"] + 1,
                            lines[m["start"]].rstrip()
                            if m["start"] < len(lines) else "",
                            "UNSUPPORTED_METHOD_BODY: "
                            f"class={class_name} method={method_name} "
                            "braced method body is unterminated within its "
                            "enclosing type; refusing to scan mutations "
                            "rule=db_ownership_policy",
                        ))
                        continue

                    # Fail closed on an unbounded multi-line expression body:
                    # mutations inside it cannot be trusted to be complete, so
                    # the method is reported and never authorized.
                    if method_unsupported.get(id(m), False):
                        violations.append((
                            rel_path, m["start"] + 1,
                            lines[m["start"]].rstrip()
                            if m["start"] < len(lines) else "",
                            "UNSUPPORTED_EXPRESSION_BODY: "
                            f"class={class_name} method={method_name} "
                            "multi-line expression body could not be bounded; "
                            "refusing to scan mutations "
                            "rule=db_ownership_policy",
                        ))
                        continue

                    matches = method_match_cache.get(id(m), [])
                    if not matches:
                        continue
                    _authorize_mutation_matches(
                        matches, own, rel_path, class_name, method_name,
                        ambiguous, lines, m, violations,
                    )

            # ── DAO mutation guard: class-initializer / top-level scope ─────
            # A DAO mutation OUTSIDE a resolved method — a class initializer,
            # top-level code, or a top-level function body — has no exact
            # (class, method) pair a policy entry could authorize, so it can
            # never be approved.  It must also never be silently skipped: every
            # such mutation fails closed with UNSUPPORTED_DAO_SCOPE.
            #
            # Class-body matches are extracted from the COMPLETE type text (so
            # multi-line calls are still detected) and then attributed to a
            # real method only when their line falls inside a parsed method
            # body or a nested type — those are handled by their own
            # method/type processing and are never double-reported.
            for t in types:
                class_name = t["name"]
                covered = set()
                for m in type_methods.get(id(t), []):
                    covered.update(range(m["start"], m["end"] + 1))
                for nt in types:
                    if nt is t:
                        continue
                    if t["start"] < nt["start"] and nt["end"] <= t["end"]:
                        covered.update(range(nt["start"], nt["end"] + 1))
                type_text = "\n".join(
                    line.rstrip("\r\n") for line in lines[t["start"]:t["end"] + 1]
                )
                class_scope_matches = _extract_mutation_matches(
                    type_text,
                    var_map=type_maps[id(t)],
                    out_of_scope_aliases=type_all_local_aliases.get(id(t), set()),
                    out_of_scope_alias_identities=(
                        type_all_local_alias_identities.get(id(t), {})
                    ),
                )
                for match in class_scope_matches:
                    lineno_0 = t["start"] + match["lineno"]
                    if lineno_0 in covered:
                        continue
                    abs_lineno = lineno_0 + 1
                    line_text = (
                        lines[abs_lineno - 1].rstrip()
                        if abs_lineno - 1 < len(lines)
                        else ""
                    )
                    violations.append((
                        rel_path, abs_lineno, line_text,
                        "UNSUPPORTED_DAO_SCOPE: DAO mutation outside a "
                        "resolved method "
                        f"(scope=class-initializer class={class_name} "
                        f"dao={match['dao']} op={match['op']}) "
                        "rule=db_ownership_policy",
                    ))

            # Top-level code: mutations on lines outside every type
            # declaration (including top-level function bodies) have no
            # enclosing class and can never be authorized by a policy entry —
            # fail closed instead of silently skipping them.  Type lines are
            # blanked (offsets/newlines preserved) so line attribution stays
            # exact and method mutations are never double-reported.
            type_covered_lines = set()
            for t in types:
                type_covered_lines.update(range(t["start"], t["end"] + 1))
            top_level_lines = []
            for i, line in enumerate(lines):
                stripped = line.rstrip("\r\n")
                if i in type_covered_lines:
                    top_level_lines.append(" " * len(stripped))
                else:
                    top_level_lines.append(stripped)
            top_text = "\n".join(top_level_lines)
            for match in _extract_mutation_matches(top_text, var_map={}):
                abs_lineno = match["lineno"] + 1
                line_text = (
                    lines[abs_lineno - 1].rstrip()
                    if abs_lineno - 1 < len(lines)
                    else ""
                )
                violations.append((
                    rel_path, abs_lineno, line_text,
                    "UNSUPPORTED_DAO_SCOPE: DAO mutation outside a resolved "
                    f"method (scope=top-level dao={match['dao']} "
                    f"op={match['op']}) rule=db_ownership_policy",
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

    # Load ownership policy (rejects non-canonical paths, universal
    # `operation: write`, wildcard methods, and unknown keys with exit 2).
    ownership_policy_path = args.ownership_policy or OWNERSHIP_POLICY_PATH
    ownership_policy = load_db_ownership_policy(ownership_policy_path)

    # Load structural exceptions (rejects non-canonical paths and unbounded
    # method_patterns with exit 2).
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
    print("  1. Add an exact entry to config/guards/db_ownership_policy.yml with a reason.")
    print("  2. Add a structural exception to config/guards/db_structural_exceptions.yml.")
    print("  3. Route the write through the approved lifecycle coordinator.")
    print()
    print("See docs/DB_WRITE_OWNERSHIP.md for the ownership map.")

    if args.fail_on_violation:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
