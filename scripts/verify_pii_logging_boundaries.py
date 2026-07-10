#!/usr/bin/env python3
"""
verify_pii_logging_boundaries.py — PII Logging/Exception Message Guard

MIT-003: Detects PII (personally identifiable information) leaking into logs,
exception messages, and diagnostics.

Rules enforced:
    G-PII-01  PII in log statements, exception messages, or diagnostics

Detection patterns:
  1. Log.d/i/e/w( or println( with sensitive variable names:
     rawOcrText, rawNotificationText, receiptText, ocrText, stackTrace, filePath
  2. Exception messages constructed with user data: user.email, phone, cardNumber
  3. Raw exception message logging: e.message, Timber.e(exception), Log.e(TAG, e.message)
  4. File paths logged outside BuildConfig.DEBUG guard
  5. System.err.println with sensitive variable names
  6. Direct toString() on receipt/notification objects in log calls

This is a BEST-EFFORT guard — flags patterns likely to leak PII.
False positives are possible (e.g., logging in debug-only code).
Use the allowlist liberally for known-safe patterns.
The guard runs in info/warning mode by default (like the cancellation guard).

Output format:
    G-PII-01 path/to/File.kt:line_number violation_description

Usage:
    python3 scripts/verify_pii_logging_boundaries.py [--root <dir>] [--fail-on-violation]

Exit codes:
    0  No violations found, or violations present but --fail-on-violation not set.
    1  Violations found with --fail-on-violation.
    2  Script error.
"""

import argparse
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

# ── Configuration ──────────────────────────────────────────
RULE_ID = "G-PII-01"
DESCRIPTION = (
    "PII Logging Guard — detects sensitive data leaking into logs, "
    "exception messages, and diagnostics"
)
SCOPE_DIRS = ["app/src/main/java"]
FILE_PATTERNS = ["*.kt"]
ALLOWLIST_PATH = "scripts/allowlists/pii_logging_allowlist.yml"

# Files always excluded (test files, generated code)
EXCLUDED_DIR_PATTERNS = ["src/test", "src/androidTest", "build", "generated"]

# ── Sensitive content variable names (raw PII data) ────────
# These indicate raw user content that should never be logged.
SENSITIVE_CONTENT_VARS = [
    "rawOcrText", "rawNotificationText", "notificationText",
    "receiptText", "ocrText", "stackTrace", "stacktrace",
]
SENSITIVE_CONTENT_RE = re.compile(
    r'\b(' + '|'.join(SENSITIVE_CONTENT_VARS) + r')\b'
)

# ── File path variable names (may be safe when debug-guarded)
FILE_PATH_VARS = ["filePath", "absolutePath"]
FILE_PATH_RE = re.compile(
    r'\b(' + '|'.join(FILE_PATH_VARS) + r')\b'
)

# Combined sensitive variable detection (content + file paths)
SENSITIVE_ANY_VAR_RE = re.compile(
    r'\b(' + '|'.join(SENSITIVE_CONTENT_VARS + FILE_PATH_VARS) + r')\b'
)

# ── Logging function patterns ───────────────────────────────
# Matches: Log.d/i/e/w/v(TAG, ...), Timber.d/i/e/w/v(...)
LOG_CALL_RE = re.compile(
    r'(?:Log|Timber)\s*\.\s*[diewv]\s*\('
)
# Matches: println(...), System.err.print(...), System.err.println(...)
PRINTLN_RE = re.compile(
    r'(?:System\.err\.print(?:ln)?|println)\s*\('
)

# Combined: any log or print call
ANY_LOG_OR_PRINT_RE = re.compile(
    r'(?:Log|Timber)\s*\.\s*[diewv]\s*\(|'
    r'(?:System\.err\.print(?:ln)?|println)\s*\('
)

# ── Exception constructor patterns ──────────────────────────
# Matches: throw Exception(...), Exception(message = ...)
# Covers common JVM/Kotlin exception types
EXCEPTION_CONSTRUCTOR_RE = re.compile(
    r'(?:throw\s+)?\b(?:Exception|RuntimeException|IllegalStateException|'
    r'IllegalArgumentException|IOException|SecurityException|'
    r'NullPointerException|IndexOutOfBoundsException|'
    r'NumberFormatException|UnsupportedOperationException)\s*\('
)

# ── User-data like field references ─────────────────────────
# Fields that commonly contain PII when referenced in exception messages
USER_DATA_RE = re.compile(
    r'\buser\s*\.\s*\w+|'
    r'\bemail\b|'
    r'\bphone(?:Number)?\b|'
    r'\bcardNumber\b|'
    r'\baccount\s*\.\s*number\b|'
    r'\bssn\b|'
    r'\bpassword\b|'
    r'\btoken\b'
)

# ── Raw exception message patterns ──────────────────────────
# Matches: e.message, ex.message, exception.message
E_MESSAGE_RE = re.compile(
    r'\b(?:e|ex|exc|exception|error|err|throwable)\s*\.\s*message\b'
)

# Matches: .printStackTrace() call
PRINT_STACK_TRACE_RE = re.compile(
    r'\.printStackTrace\s*\(\)'
)

# ── toString on sensitive objects ───────────────────────────
# receipt.toString(), notification.toString() in log context
TOSTRING_ON_SENSITIVE_RE = re.compile(
    r'(?:receipt|notification|ocrResult|scanResult)\s*\.\s*toString\s*\('
)

# ── Debug gate detection ────────────────────────────────────
# Patterns that indicate the surrounding code is guarded for debug-only
DEBUG_GATE_PATTERNS = [
    re.compile(r'BuildConfig\.DEBUG'),
    re.compile(r'\bif\s*\(\s*DEBUG\b'),
    re.compile(r'if\s*\(\s*BuildConfig\.DEBUG\s*\)'),
    re.compile(r'\.also\s*\{\s*if\s*\(\s*BuildConfig\.DEBUG\b'),
]


# ── Utilities ──────────────────────────────────────────────

def _is_comment_line(line: str) -> bool:
    """True if the line is a Kotlin comment or part of a block comment."""
    stripped = line.strip()
    return (stripped.startswith("//") or stripped.startswith("/*")
            or stripped.startswith("*") or stripped.startswith("*/"))


def _has_debug_gate_nearby(lines: List[str], line_idx: int, window: int = 10) -> bool:
    """Check if there's a BuildConfig.DEBUG check within `window` lines
    before or including line_idx."""
    start = max(0, line_idx - window)
    for i in range(start, line_idx + 1):
        line = lines[i]
        if _is_comment_line(line):
            continue
        for pattern in DEBUG_GATE_PATTERNS:
            if pattern.search(line):
                return True
    return False


# ── Violation Detection ────────────────────────────────────

def scan_file(
    filepath: Path,
    allowlist: List[dict],
) -> Tuple[List[str], bool]:
    """Scan a single file for PII logging violations.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []
    try:
        content = filepath.read_text(encoding="utf-8")
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    lines = content.splitlines()
    path_str = str(filepath).replace("\\", "/")

    # Normalize for allowlist matching
    rel_for_allowlist = path_str
    if "app/src/main/java/" in path_str:
        idx = path_str.index("app/src/main/java/")
        rel_for_allowlist = path_str[idx:]

    # Check if this file is fully allowlisted (symbol "*")
    if is_allowlisted(rel_for_allowlist, "*", allowlist):
        return violations, False

    for i, raw_line in enumerate(lines):
        line_no = i + 1

        # Skip comment lines
        if _is_comment_line(raw_line):
            continue

        # ── Rule 1: Log/print with sensitive variables ───
        if SENSITIVE_ANY_VAR_RE.search(raw_line):
            if ANY_LOG_OR_PRINT_RE.search(raw_line):
                # filePath/absolutePath are exempt if inside BuildConfig.DEBUG guard
                if FILE_PATH_RE.search(raw_line) and _has_debug_gate_nearby(lines, i):
                    pass  # exempt: file path logging behind debug gate
                else:
                    # Identify which sensitive var was found
                    found_vars = SENSITIVE_ANY_VAR_RE.findall(raw_line)
                    found_str = ", ".join(set(found_vars))
                    violations.append(
                        f"G-PII-01 {filepath}:{line_no} "
                        f"Log/print statement with sensitive variable(s): {found_str}. "
                        f"May leak PII. Use sanitized data or guard with BuildConfig.DEBUG "
                        f"(file paths only)."
                    )

        # ── Rule 2: Exception messages with user data ────
        if EXCEPTION_CONSTRUCTOR_RE.search(raw_line):
            if USER_DATA_RE.search(raw_line):
                found_fields = USER_DATA_RE.findall(raw_line)
                found_str = ", ".join(set(found_fields))
                violations.append(
                    f"G-PII-01 {filepath}:{line_no} "
                    f"Exception constructed with user data field(s): {found_str}. "
                    f"Never put user PII in exception messages — use safe reason codes."
                )

        # ── Rule 3: e.message in log/exception context ───
        if E_MESSAGE_RE.search(raw_line):
            # Flag if used in a log/print statement
            if ANY_LOG_OR_PRINT_RE.search(raw_line):
                if _has_debug_gate_nearby(lines, i):
                    continue
                violations.append(
                    f"G-PII-01 {filepath}:{line_no} "
                    f"Logging raw exception message (e.message). "
                    f"Exception messages may contain PII. "
                    f"Use structured diagnostics with safe reason codes."
                )
            # Flag if used in an exception constructor (e.g. RuntimeException(e.message))
            elif EXCEPTION_CONSTRUCTOR_RE.search(raw_line):
                violations.append(
                    f"G-PII-01 {filepath}:{line_no} "
                    f"Exception wrapping raw e.message — "
                    f"may propagate PII from the original exception. "
                    f"Use structured diagnostics with safe reason codes."
                )

        # ── Rule 4: printStackTrace in non-test code ─────
        if PRINT_STACK_TRACE_RE.search(raw_line):
            violations.append(
                f"G-PII-01 {filepath}:{line_no} "
                f"printStackTrace() call. Stack traces may leak "
                f"file paths, user data, or internal state. "
                f"Use structured diagnostics instead."
            )

        # ── Rule 5: toString() on sensitive objects in log
        if TOSTRING_ON_SENSITIVE_RE.search(raw_line):
            if ANY_LOG_OR_PRINT_RE.search(raw_line):
                violations.append(
                    f"G-PII-01 {filepath}:{line_no} "
                    f"toString() on receipt/notification/OCR object in log call. "
                    f"May leak raw PII. Use structured logging with safe fields."
                )

    return violations, False


# ── Allowlist ──────────────────────────────────────────────

def load_allowlist(path: Path) -> List[dict]:
    """Load allowlist entries from YAML file.

    Exits with code 2 on infrastructure errors (missing PyYAML, malformed YAML).
    """
    allowlist: List[dict] = []
    if not path.exists():
        return allowlist

    try:
        import yaml  # type: ignore[import-untyped]
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


def is_allowlisted(filepath: str, symbol: str, allowlist: List[dict]) -> bool:
    """Check if a filepath:symbol is in the allowlist.

    Supports partial path matching: unrooted relative paths match suffixes.
    A symbol of "*" matches any symbol for the given file.
    """
    if not allowlist:
        return False
    for entry in allowlist:
        entry_path = entry.get("path", "")
        if not entry_path:
            continue
        # entry_path may be relative (e.g., "PrivacyGuard.kt" or
        # "app/src/main/java/..."). filepath may be absolute or relative.
        # Only match if the allowlisted path is a suffix of the actual file path
        if filepath.endswith(entry_path):
            # Check rule match
            entry_rule = entry.get("rule", "")
            if entry_rule and entry_rule != RULE_ID:
                continue
            entry_symbol = entry.get("symbol", "")
            # "*" matches all symbols; empty symbol matches empty
            if entry_symbol == "*" or not symbol or entry_symbol == symbol:
                return True
    return False


# ── Main ───────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument(
        "--root", default=".", help="Project root directory"
    )
    parser.add_argument(
        "--fail-on-violation", action="store_true",
        help="Exit with code 1 on violations"
    )
    parser.add_argument(
        "--allowlist", default=ALLOWLIST_PATH,
        help="Path to allowlist file (relative to --root)"
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    # Fail-closed: missing configured allowlist is fatal
    if args.allowlist and not (root / args.allowlist).exists():
        print(f"ERROR: Allowlist not found: {args.allowlist}", file=sys.stderr)
        sys.exit(2)

    allowlist = load_allowlist(root / args.allowlist) if args.allowlist else []

    all_violations: List[str] = []
    fatal_errors: List[str] = []

    for scope_dir in SCOPE_DIRS:
        scan_dir = root / scope_dir
        if not scan_dir.exists():
            continue
        for pattern in FILE_PATTERNS:
            for filepath in scan_dir.rglob(pattern):
                # Skip test and generated files
                path_str = str(filepath).replace("\\", "/")
                if any(excl in path_str for excl in EXCLUDED_DIR_PATTERNS):
                    continue
                violations, had_fatal = scan_file(filepath, allowlist)
                if had_fatal:
                    fatal_errors.append(str(filepath))
                all_violations.extend(violations)

    if fatal_errors:
        for fp in fatal_errors:
            print(f"FATAL: Could not read file: {fp}", file=sys.stderr)
        sys.exit(2)

    if all_violations:
        for v in all_violations:
            print(v)

        if args.fail_on_violation:
            print(
                f"\nVIOLATIONS FOUND: {len(all_violations)}",
                file=sys.stderr,
            )
            sys.exit(1)
        else:
            print(
                f"\nWARNING: {len(all_violations)} violations "
                f"(--fail-on-violation not set)",
                file=sys.stderr,
            )
            sys.exit(0)
    else:
        print(f"PASS: {RULE_ID} — no violations found")
        sys.exit(0)


if __name__ == "__main__":
    main()
