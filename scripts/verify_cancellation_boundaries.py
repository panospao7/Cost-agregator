#!/usr/bin/env python3
"""
verify_cancellation_boundaries.py — Cancellation Boundary Guard (Guard 1)

MIT-035 / PR 2: Detects unsafe CancellationException handling in Kotlin
suspend functions, worker paths, and coroutine-heavy code.

Rules enforced:
    G-CANCEL-01  Broad catch (Exception/Throwable/RuntimeException) in suspend
                 functions or worker paths without CancellationException propagation.
    G-CANCEL-02  runCatching in suspend/worker contexts (swallows CE).
    G-CANCEL-03  .onFailure without cancellation checking in suspend paths.

Output format:
    RULE_ID path/to/File.kt:line_number violation_description

Usage:
    python3 scripts/verify_cancellation_boundaries.py [--root <dir>] [--fail-on-violation]

Exit codes:
    0  No violations found, or violations present but --fail-on-violation not set.
    1  Violations found with --fail-on-violation.
    2  Script error.
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)

from kotlin_callable_parser import (  # noqa: E402
    ParserError,
    find_callable_declarations,
    find_owner_declarations,
)
from guardrails.cloud_payload_proof import CloudProofError, executable_kotlin_source  # noqa: E402

# ── Configuration ──────────────────────────────────────────
RULE_ID = "G-CANCEL-01"
DESCRIPTION = (
    "Cancellation Boundary Guard — detects unsafe CancellationException "
    "handling (broad catch without rethrow, runCatching, unsafe .onFailure)"
)
FILE_PATTERNS = ["*.kt"]
ALLOWLIST_PATH = "scripts/allowlists/cancellation_allowlist.yml"

# PR-GR-10B: the scanned production source scope is declared by the
# checked-in manifest ``config/guards/production_source_roots.yml`` (resolved
# via scripts/guardrails/production_source_scope.py).  There is NO
# conventional-root fallback: a missing, malformed, or undeclared manifest
# fails closed with exit 2.  The historical hard-coded root
# (``app/src/main/java``) is the manifest's currently declared single root,
# so the scanned file set is unchanged (the previous silent skip of a
# missing scope directory is now a fail-closed topology diagnostic).

# ── Pattern constants ──────────────────────────────────────
BROAD_CATCH_NAMES = {"Exception", "Throwable", "RuntimeException"}

# Regexes for the catch line itself
CATCH_BROAD_RE = re.compile(
    r'\bcatch\s*\(\s*\w+\s*:\s*(Exception|Throwable|RuntimeException)\b'
)
# Regex to distinguish CancellationException (safe to catch standalone) from broad types
CATCH_CANCELLATION_RE = re.compile(
    r'\bcatch\s*\(\s*\w+\s*:\s*(?:kotlinx\.coroutines\.)?(?:Timeout)?CancellationException\b'
)

# Patterns that indicate the code propagates CE correctly
CANCELLATION_SAFE_INDICATORS = [
    re.compile(r'CancellationException'),
    re.compile(r'rethrowIfCancellation'),
    re.compile(r'CancellationSafe\.'),
    re.compile(r'ensureActive\(\)'),
]

# Patterns indicating rethrow of the caught variable itself
# Covers: throw e, throw error, throw t, throw ex
RETHROW_CAUGHT_RE = re.compile(r'\bthrow\s+(e|error|t|ex|caught|exc|ce)\b')

# runCatching detection (not runCatchingCancellable)
RUN_CATCHING_RE = re.compile(r'(?<!\w)runCatching\s*\{')
RUN_CATCHING_SAFE_RE = re.compile(r'runCatchingCancellable')

# .onFailure detection
ON_FAILURE_RE = re.compile(r'\.onFailure\s*\{')

# suspend function detection
SUSPEND_FUN_RE = re.compile(r'\bsuspend\s+fun\b')

# Worker path detection
WORKER_DIR_MARKERS = {"worker", "workers"}

# Allowlistable files: files that are known to be safe even without explicit
# cancellation handling (e.g., UI composables, color helpers)
UI_COMPOSABLE_PATHS = {"ui/", "screens/", "components/"}


# ── Utilities ──────────────────────────────────────────────

def _is_broad_catch(line: str) -> bool:
    """True if the line contains catch of Exception/Throwable/RuntimeException."""
    m = CATCH_BROAD_RE.search(line)
    if not m:
        return False
    # If it catches ONLY CancellationException (not broad), skip
    if CATCH_CANCELLATION_RE.search(line) and not re.search(
        r'catch\s*\(\s*\w+\s*:\s*Exception\b', line
    ):
        return False
    return True


def _has_safe_pattern_in_window(lines: List[str], start: int, window: int = 15) -> bool:
    """Check if any cancellation-safe pattern exists within `window` lines
    after `start` (inclusive)."""
    end = min(len(lines), start + window)
    for i in range(start, end):
        line = lines[i]
        # Skip pure comment lines
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        for pattern in CANCELLATION_SAFE_INDICATORS:
            if pattern.search(line):
                return True
        if RETHROW_CAUGHT_RE.search(line):
            return True
    return False


def _has_preceding_sibling_ce_catch(lines: List[str], idx: int, window: int = 15) -> bool:
    """True when the broad catch at `idx` is preceded within the same try
    block by a sibling CancellationException catch that rethrows.

    Mirrors the JUnit guard's precedingSiblingCeCatch acceptance: Kotlin
    evaluates catch clauses in order, so a preceding CE rethrow means the
    broad catch below it can never observe a CancellationException. The
    upward scan stops at a `try` opener so an enclosing try's CE catch can
    never mark an inner broad catch safe.
    """
    start = max(0, idx - window)
    for j in range(idx - 1, start - 1, -1):
        line = lines[j]
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        if re.search(r'\btry\b', line):
            return False
        if not CATCH_CANCELLATION_RE.search(line):
            continue
        # Same-line rethrow (e.g. catch (e: CancellationException) { throw e })
        if RETHROW_CAUGHT_RE.search(line):
            return True
        # Rethrow on the first code line of the sibling catch block
        for k in range(j + 1, min(idx, j + 6)):
            inner = lines[k].strip()
            if inner.startswith("//") or inner.startswith("*"):
                continue
            return bool(RETHROW_CAUGHT_RE.search(lines[k]))
        return False
    return False


def _find_suspend_ranges(lines: List[str]) -> List[Tuple[int, int]]:
    """Find line ranges (start, end inclusive) of suspend functions using
    brace-depth tracking. Returns list of (start_line, end_line) 1-indexed."""
    ranges: List[Tuple[int, int]] = []
    lengths = len(lines)

    for i, raw in enumerate(lines):
        line = raw.lstrip()
        if not re.search(r'(?:^|\s)suspend\s+fun\s', line):
            continue

        # Found suspend fun at line i (1-indexed)
        start_line = i + 1

        # Find the function body opening brace
        j = i
        body_start = -1
        while j < lengths:
            if '{' in lines[j]:
                # Figure out column: use braces after `suspend fun`
                # First find if `{` is on same line as the fun declaration
                # or on a subsequent line
                body_start = j
                break
            j += 1

        if body_start < 0:
            ranges.append((start_line, start_line))
            continue

        # Track depth from the opening brace
        depth = 1
        # Find position of first '{' on body_start line
        brace_line = lines[body_start]
        brace_col = brace_line.index('{')
        # If this is not the start_line itself, we need to count from beginning
        k = body_start
        col = brace_col + 1 if k == body_start else 0

        while k < lengths and depth > 0:
            l = lines[k]
            start_col = col
            col = 0  # reset for subsequent lines
            for ch_idx in range(start_col, len(l)):
                ch = l[ch_idx]
                if ch == '{':
                    depth += 1
                elif ch == '}':
                    depth -= 1
                    if depth == 0:
                        end_line = k + 1
                        ranges.append((start_line, end_line))
                        break
            k += 1

        if depth > 0:
            ranges.append((start_line, lengths))

    return ranges


def _line_in_suspend_range(line_no: int, ranges: List[Tuple[int, int]]) -> bool:
    """Check if line_no (1-indexed) falls within any suspend fun range."""
    return any(start <= line_no <= end for start, end in ranges)


def _is_worker_path(filepath: str) -> bool:
    """Check if the file path indicates a worker/coroutine-heavy path."""
    norm = filepath.replace("\\", "/").lower()
    parts = set(norm.split("/"))
    return bool(parts & WORKER_DIR_MARKERS)


# ── Violation Detection ────────────────────────────────────

def _matches_allowlist_path(filepath: str, entry_path: str) -> bool:
    if not isinstance(entry_path, str) or not entry_path:
        return False
    expected = entry_path.replace("\\", "/")
    if (expected.startswith("/") or re.match(r"^[A-Za-z]:", expected)
            or any(part in {"", ".", "..", "*"} for part in expected.split("/"))):
        return False
    actual = filepath.replace("\\", "/")
    # scan_file supplies a repository-relative path. A basename or suffix must
    # never authorize another directory containing the same callable name.
    return actual == expected


def scan_file(
    filepath: Path,
    allowlist: List[dict],
) -> Tuple[List[str], bool]:
    """Scan a single file for cancellation boundary violations.

    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []
    try:
        content = filepath.read_text(encoding="utf-8")
    except Exception:
        print("CANCELLATION_SOURCE_UNREADABLE", file=sys.stderr)
        return violations, True

    try:
        masked = executable_kotlin_source(content)
    except (ParserError, CloudProofError):
        print("CANCELLATION_SOURCE_UNPARSEABLE", file=sys.stderr)
        return violations, True
    lines = masked.splitlines()
    path_str = str(filepath).replace("\\", "/")
    rel_for_allowlist = path_str
    # Normalize for allowlist matching
    if "app/src/main/java/" in path_str:
        idx = path_str.index("app/src/main/java/")
        rel_for_allowlist = path_str[idx:]

    # Unknown callable identity never grants an exemption. Class names and
    # generic labels cannot authorize every method or overload in a file.
    declarations = []
    if any(isinstance(entry, dict) and _matches_allowlist_path(
            rel_for_allowlist, entry.get("path", "")) for entry in allowlist):
        try:
            for owner in find_owner_declarations(content):
                declarations.extend(find_callable_declarations(
                    content, owner, tolerate_unresolved_types=True))
        except ParserError:
            # Detection still runs; an unresolved symbol cannot exempt it.
            declarations = []
    line_offsets = []
    offset = 0
    for line in masked.splitlines(keepends=True):
        line_offsets.append(offset)
        offset += len(line)

    def symbol_at(index: int, pattern) -> str:
        match = pattern.search(lines[index])
        if match is None:
            return ""
        position = line_offsets[index] + match.start()
        enclosing = [declaration for declaration in declarations
                     if declaration.status == "RESOLVED_EXACTLY"
                     and declaration.start_offset <= position < declaration.end_offset]
        if not enclosing:
            return ""
        declaration = min(enclosing, key=lambda item: item.end_offset - item.start_offset)
        # The member parser deliberately excludes local/anonymous functions.
        # Never let an outer-method exemption cover an unresolved inner callable.
        if len(re.findall(r"\bfun\b", masked[declaration.start_offset:position])) != 1:
            return ""
        signature = declaration.signature
        receiver = (signature.receiver + ".") if signature.receiver else ""
        parameters = ",".join(signature.parameter_types)
        return f"{declaration.owner}.{receiver}{signature.function_name}({parameters})"

    # Determine context
    is_worker = _is_worker_path(path_str)
    suspend_ranges = _find_suspend_ranges(lines)

    # ── G-CANCEL-01: broad catch without CE rethrow ────────
    for i, raw_line in enumerate(lines):
        line_no = i + 1

        # Skip comment-only lines for detection (code in comments is safe)
        stripped = raw_line.strip()
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            continue

        if not _is_broad_catch(raw_line):
            continue

        # Check if we're in a context that matters
        in_suspend = _line_in_suspend_range(line_no, suspend_ranges)
        if not in_suspend and not is_worker:
            # Broad catch outside suspend and outside worker path — not a violation
            continue

        # Check allowlist
        symbol = symbol_at(i, CATCH_BROAD_RE)
        if is_allowlisted(rel_for_allowlist, symbol, allowlist, rule_id="G-CANCEL-01"):
            continue

        # Check if cancellation is handled nearby
        if _has_safe_pattern_in_window(lines, i, window=15):
            continue
        if _has_preceding_sibling_ce_catch(lines, i):
            continue

        # Also check the catch line itself for inline safe patterns
        # (e.g., catch (e: Exception) { if (e is CancellationException) throw e })
        for pattern in CANCELLATION_SAFE_INDICATORS:
            if pattern.search(raw_line):
                break
        else:
            if not RETHROW_CAUGHT_RE.search(raw_line):
                # Flag as violation
                ctx = "suspend function" if in_suspend else "worker path"
                violations.append(
                    f"G-CANCEL-01 {filepath}:{line_no} symbol={symbol or '<unresolved>'} "
                    f"Broad catch (Exception/Throwable/RuntimeException) in {ctx} "
                    f"without CancellationException propagation"
                )

    # ── G-CANCEL-02: runCatching without allowlist ─────────
    for i, raw_line in enumerate(lines):
        line_no = i + 1

        stripped = raw_line.strip()
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            continue

        # Only flag runCatching (not runCatchingCancellable)
        if not RUN_CATCHING_RE.search(raw_line):
            continue
        if RUN_CATCHING_SAFE_RE.search(raw_line):
            continue

        # Only flag in context
        in_suspend = _line_in_suspend_range(line_no, suspend_ranges)
        if not in_suspend and not is_worker:
            # runCatching outside suspend/worker is common for UI/data parsing — not a violation
            continue

        # Check allowlist
        symbol = symbol_at(i, RUN_CATCHING_RE)
        if is_allowlisted(rel_for_allowlist, symbol, allowlist, rule_id="G-CANCEL-02"):
            continue

        ctx = "suspend function" if in_suspend else "worker path"
        violations.append(
            f"G-CANCEL-02 {filepath}:{line_no} symbol={symbol or '<unresolved>'} "
            f"runCatching in {ctx} — swallows CancellationException. "
            f"Use CancellationSafe.runCatchingCancellable or allowlist."
        )

    # ── G-CANCEL-03: .onFailure without cancellation check ─
    for i, raw_line in enumerate(lines):
        line_no = i + 1

        stripped = raw_line.strip()
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            continue

        if not ON_FAILURE_RE.search(raw_line):
            continue

        # Only flag in context
        in_suspend = _line_in_suspend_range(line_no, suspend_ranges)
        if not in_suspend and not is_worker:
            continue

        # Check allowlist
        symbol = symbol_at(i, ON_FAILURE_RE)
        if is_allowlisted(rel_for_allowlist, symbol, allowlist, rule_id="G-CANCEL-03"):
            continue

        # Check if cancellation handling is nearby
        if _has_safe_pattern_in_window(lines, i, window=15):
            continue
        if _has_preceding_sibling_ce_catch(lines, i):
            continue

        ctx = "suspend function" if in_suspend else "worker path"
        violations.append(
            f"G-CANCEL-03 {filepath}:{line_no} symbol={symbol or '<unresolved>'} "
            f".onFailure in {ctx} without CancellationException check — "
            f"cancellation may be swallowed"
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


def is_allowlisted(filepath: str, symbol: str, allowlist: List[dict], *, rule_id: str) -> bool:
    """Match the active rule and exact owner/receiver/method/parameter symbol.

    Paths must match exactly after separator normalization; callers supply
    repository-relative paths, never absolute paths or filename suffixes.
    Empty, class-only and unresolved symbols cannot authorize a file.
    """
    if not symbol or "(" not in symbol or not symbol.endswith(")") or not rule_id:
        return False
    for entry in allowlist:
        if not isinstance(entry, dict):
            continue
        if (entry.get("rule") == rule_id and entry.get("symbol") == symbol
                and _matches_allowlist_path(filepath, entry.get("path", ""))):
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

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).
    root_set, scope_diagnostics = resolve_production_source_scope(str(root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"ERROR: production source scope unresolved: {codes}",
            file=sys.stderr,
        )
        sys.exit(2)

    all_violations: List[str] = []
    fatal_errors: List[str] = []

    try:
        source_files = list(iter_production_kotlin_files(str(root), root_set))
    except ProductionSourceScopeError as exc:
        print(
            f"ERROR: production source enumeration failed: {exc.code}",
            file=sys.stderr,
        )
        sys.exit(2)

    for source_file in source_files:
        filepath = Path(source_file.absolute_path)
        # Skip test files (semantic filter, preserved verbatim)
        if "src/test" in str(filepath).replace("\\", "/"):
            continue
        if "src/androidTest" in str(filepath).replace("\\", "/"):
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
