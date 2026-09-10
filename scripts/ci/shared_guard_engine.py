#!/usr/bin/env python3
"""
SHARED_GUARD_ENGINE — Common utilities for architecture guard scripts.

Centralizes logic that is duplicated across guard scripts:
  1. YAML loading with validation
  2. Allowlist matching (exact path + symbol)
  3. Fingerprint extraction
  4. Safe file reading
  5. Project root detection
  6. Path normalization

All utilities are importable by both guard scripts and the ratchet.
"""

import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

# ── YAML loading ────────────────────────────────────────────────────────────────

_YAML_AVAILABLE: Optional[bool] = None  # cached


def _check_yaml() -> bool:
    """Check if PyYAML is available (cached)."""
    global _YAML_AVAILABLE
    if _YAML_AVAILABLE is None:
        try:
            import yaml  # noqa: F401
            _YAML_AVAILABLE = True
        except ImportError:
            _YAML_AVAILABLE = False
    return _YAML_AVAILABLE


def load_yaml_or_exit(filepath: str, label: str) -> Any:
    """Load a YAML file with safe_load. Exit with code 2 on any error.

    Args:
        filepath: Path to the YAML file.
        label: Human-readable label for error messages.

    Returns:
        Parsed YAML data (dict, list, or None for empty).
    """
    if not _check_yaml():
        print(
            f"ERROR: PyYAML is required to load {label}. "
            f"Install with: pip install pyyaml",
            file=sys.stderr,
        )
        sys.exit(2)

    import yaml

    if not os.path.exists(filepath):
        print(f"ERROR: {label} not found: {filepath}", file=sys.stderr)
        sys.exit(2)

    try:
        with open(filepath, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError as e:
        print(f"ERROR: Malformed {label} YAML: {e}", file=sys.stderr)
        sys.exit(2)

    return data


def load_yaml_allowlist(
    filepath: str,
    label: str = "allowlist",
    required_fields: Optional[List[str]] = None,
) -> List[Dict[str, Any]]:
    """Load and validate a YAML allowlist file.

    Args:
        filepath: Path to the YAML allowlist file.
        label: Human-readable label for error messages.
        required_fields: List of required top-level keys for each entry
                         (default: ["rule", "path", "reason", "owner"]).

    Returns:
        List of validated allowlist entry dicts.

    Exit code 2 on any validation failure.
    """
    if required_fields is None:
        required_fields = ["rule", "path", "reason", "owner"]

    data = load_yaml_or_exit(filepath, label)

    if not isinstance(data, list):
        print(
            f"ERROR: {label} must be a list of entries, "
            f"got {type(data).__name__}",
            file=sys.stderr,
        )
        sys.exit(2)

    for i, entry in enumerate(data):
        if not isinstance(entry, dict):
            print(
                f"ERROR: {label} entry #{i+1} is not a dict: {entry}",
                file=sys.stderr,
            )
            sys.exit(2)
        for field in required_fields:
            if field not in entry or not entry[field]:
                print(
                    f"ERROR: Missing or empty '{field}' in {label} entry #{i+1}: {entry}",
                    file=sys.stderr,
                )
                sys.exit(2)

    return data


# ── Allowlist matching ──────────────────────────────────────────────────────────

def match_allowlist(
    entry: Dict[str, Any],
    file_path: str,
    symbol: Optional[str] = None,
    rule_id: Optional[str] = None,
) -> bool:
    """Check if an allowlist entry matches a given file, symbol, and rule.

    Matching logic:
      - ``path`` must match the file_path (case-insensitive suffix or exact).
      - ``symbol`` (optional) must match if present in entry and provided.
      - ``rule`` must match if provided.

    Args:
        entry: Allowlist entry dict with keys 'path', 'symbol' (optional), 'rule'.
        file_path: Normalized file path to check.
        symbol: Optional class/function name to match.
        rule_id: Optional rule ID to match.

    Returns:
        True if the entry covers the given file/symbol/rule.
    """
    # Path matching: case-insensitive suffix match
    entry_path = (entry.get("path") or "").replace("\\", "/").lower()
    norm_path = file_path.replace("\\", "/").lower()

    if not norm_path.endswith(entry_path.lstrip("/")):
        return False

    # Rule matching (if rule_id provided)
    if rule_id is not None:
        entry_rule = entry.get("rule", "")
        if entry_rule and entry_rule != rule_id:
            return False

    # Symbol matching (if entry specifies one)
    entry_symbol = entry.get("symbol")
    if entry_symbol:
        if symbol is None:
            return False
        # Exact match or strip quotes
        sym = entry_symbol.strip('"').strip("'")
        if symbol != sym:
            return False

    return True


# ── Fingerprint extraction ─────────────────────────────────────────────────────

# Pattern for a line containing .kt:NNN or .java:NNN
_PATH_LINE_RE = re.compile(r'(.+\.(?:kt|java):\d+)')

# Pattern to extract a bracketed rule/reason code:  [CODE]
_BRACKET_RULE_RE = re.compile(r'^\s*\[([A-Z].+?)\]\s*$')

# Pattern for standard single-line:  RULE_ID path:line ...
_STANDARD_RE = re.compile(
    r'^([A-Z][A-Z0-9_-]{2,40})\s+'
    r'(.+\.(?:kt|java):\d+)'
)

# Pattern for money guard output:  Lnnn [G-MONEY-NN]
_MONEY_RULE_RE = re.compile(
    r'^\s*L(\d+)\s+\[(G-MONEY-\d+)\]\s'
)

# Pattern to extract just the path from a money header line
_MONEY_PATH_HEADER_RE = re.compile(
    r'^(?:FAIL|PASS)\s+(.+):$'
)


def _normalize_path(path_line: str, project_root: Path) -> str:
    """Normalize a path:line string to be relative to project root.

    Handles absolute Windows/Linux paths, already-relative paths, and
    paths outside the project root (kept as-is except for backslash
    normalization).
    """
    if ":" in path_line:
        colon_idx = path_line.rfind(":")
        file_path = path_line[:colon_idx]
        line_num = path_line[colon_idx + 1:]
    else:
        file_path = path_line
        line_num = ""

    p = Path(file_path)
    if not p.is_absolute():
        p = project_root / p

    resolved = p.resolve() if p.exists() else p

    try:
        rel = resolved.relative_to(project_root.resolve())
        result = str(rel).replace("\\", "/")
    except (ValueError, OSError):
        result = file_path.replace("\\", "/")

    if line_num:
        return f"{result}:{line_num}"
    return result


def _make_fingerprint(rule_id: str, path_line: str, project_root: Path) -> str:
    """Create a normalized fingerprint from a rule_id and path:line.

    Line numbers are stripped for stable fingerprints.
    """
    normalized = _normalize_path(path_line.strip(), project_root)
    if ":" in normalized:
        file_path = normalized.rsplit(":", 1)[0]
    else:
        file_path = normalized
    return f"{rule_id} {file_path}"


def _try_extract_from_line(
    line: str, project_root: Path
) -> Optional[Tuple[str, str]]:
    """Try to extract (rule_id, normalized_path:line) from a single line.

    Handles these same-line formats:
      * Standard:   ``G-CANCEL-01 path:line description``
      * Bracketed:  ``[G5] path:line``  or  ``[CODE] path:line``
      * Dash-sep:   ``[TYPE] Name -- path:line``  (event_writers)
    """
    stripped = line.strip()
    if not stripped:
        return None

    # Format A: Bracketed rule-id prefix  [CODE] ...
    if stripped.startswith("["):
        rb = stripped.find("]", 1)
        if rb < 1:
            return None
        rule_id = stripped[1:rb].strip()
        if not rule_id or not rule_id[0].isupper():
            return None

        rest = stripped[rb + 1:].lstrip()

        # Try to find a dash-separator (em-dash, en-dash, or hyphen)
        dash_sep = re.search(r'\s[—–-]\s', rest)
        if dash_sep:
            rest = rest[dash_sep.end():]

        pm = _PATH_LINE_RE.search(rest)
        if pm:
            path_line = pm.group(1)
            return (rule_id, _normalize_path(path_line, project_root))
        return None

    # Format B: Standard bare rule_id + path
    sm = _STANDARD_RE.match(stripped)
    if sm:
        rule_id = sm.group(1)
        path_line = sm.group(2)
        return (rule_id, _normalize_path(path_line, project_root))

    return None


def extract_fingerprints(
    stdout: str, project_root: Optional[Path] = None
) -> List[str]:
    """Extract sorted, unique fingerprints from guard script stdout.

    Handles multiple guard output formats:

    * **Same-line** (cancellation, privacy, event_writers):
      ``RULE_ID path:line``, ``[CODE] path:line``, or
      ``[TYPE] Name -- path:line``.

    * **Two-line** (db_access):
      Line N: ``[REASON_CODE]``, Line N+1: ``path:line``.

    * **Money** (two-line):
      ``FAIL path:`` then ``Lnnn [G-MONEY-NN] description``.

    Returns a sorted list of unique fingerprints.
    """
    if project_root is None:
        project_root = Path.cwd().resolve()

    fingerprints: Set[str] = set()
    lines = stdout.splitlines()
    n = len(lines)

    last_money_path: Optional[str] = None

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue

        # Money guard: detect file-path header  "FAIL path:"
        mp = _MONEY_PATH_HEADER_RE.match(stripped)
        if mp:
            last_money_path = mp.group(1)
            continue

        # Money guard: rule line  "Lnnn [G-MONEY-NN] ..."
        if last_money_path is not None:
            mm = _MONEY_RULE_RE.match(stripped)
            if mm:
                line_num = mm.group(1)
                rule_id = mm.group(2)
                path_line = f"{last_money_path}:{line_num}"
                fingerprints.add(
                    _make_fingerprint(rule_id, path_line, project_root)
                )
                continue

        # Same-line format (standard, bracketed, dash-separated)
        same_line = _try_extract_from_line(line, project_root)
        if same_line is not None:
            rule_id, norm_path = same_line
            if ":" in norm_path:
                file_path = norm_path.rsplit(":", 1)[0]
            else:
                file_path = norm_path
            fingerprints.add(f"{rule_id} {file_path}")
            continue

        # Two-line format (db_access): standalone path:line, rule above
        pm = _PATH_LINE_RE.match(stripped)
        if pm and i > 0:
            path_line = pm.group(1)
            prev_stripped = lines[i - 1].strip()
            rm = _BRACKET_RULE_RE.match(prev_stripped)
            if rm:
                rule_id = rm.group(1).strip()
                if rule_id:
                    fingerprints.add(
                        _make_fingerprint(rule_id, path_line, project_root)
                    )
                    continue

    return sorted(fingerprints)


# ── Safe file reading ───────────────────────────────────────────────────────────

def safe_read_file(filepath: str) -> Tuple[Optional[str], Optional[str]]:
    """Safely read a file, returning (content, error_message).

    Args:
        filepath: Path to the file to read.

    Returns:
        Tuple of (file_content, error_message). Exactly one will be None.
    """
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            return f.read(), None
    except FileNotFoundError:
        return None, f"File not found: {filepath}"
    except PermissionError:
        return None, f"Permission denied: {filepath}"
    except IsADirectoryError:
        return None, f"Is a directory: {filepath}"
    except UnicodeDecodeError as e:
        return None, f"Encoding error in {filepath}: {e}"
    except OSError as e:
        return None, f"OS error reading {filepath}: {e}"


# ── Project root detection ──────────────────────────────────────────────────────

def find_project_root(start_path: Optional[Path] = None) -> Path:
    """Find the project root by walking up from start_path.

    Identifies the root by the presence of a recognized marker:
    settings.gradle.kts, build.gradle.kts, or .git directory.

    Args:
        start_path: Directory to start searching from (default: cwd).

    Returns:
        Resolved Path to the project root.
    """
    if start_path is None:
        start_path = Path.cwd()
    current = start_path.resolve()
    for _ in range(20):
        if (current / "settings.gradle.kts").exists():
            return current
        if (current / "build.gradle.kts").exists() and (current / ".git").exists():
            return current
        parent = current.parent
        if parent == current:
            break
        current = parent
    # Fallback: return the start path
    return start_path.resolve()


# ── Guard suite output directory ─────────────────────────────────────────────────

def get_output_dir(project_root: Optional[Path] = None) -> Path:
    """Return the standard CI output directory for guard results."""
    if project_root is None:
        project_root = Path.cwd()
    return project_root / "build" / "ci" / "static-guards"


# ── Comment stripping utilities ─────────────────────────────────────────────────

_LINE_COMMENT_RE = re.compile(r'//.*$', re.MULTILINE)
_BLOCK_COMMENT_RE = re.compile(r'/\*.*?\*/', re.DOTALL)


def strip_kotlin_comments(content: str) -> str:
    """Strip Kotlin line (//) and block (/* */) comments from source."""
    content = _BLOCK_COMMENT_RE.sub('', content)
    content = _LINE_COMMENT_RE.sub('', content)
    return content


# ── File discovery ──────────────────────────────────────────────────────────────

def find_source_files(
    root: str,
    subdir: str = "",
    patterns: Optional[List[str]] = None,
    skip_dirs: Optional[Set[str]] = None,
) -> List[str]:
    """Find source files under a directory tree.

    Args:
        root: Project root directory.
        subdir: Subdirectory relative to root to scan.
        patterns: File glob patterns (default: ["*.kt", "*.java"]).
        skip_dirs: Directory names to skip (default: {"test", "androidTest",
                     "migration", "generated", "build"}).

    Returns:
        Sorted list of absolute file paths.
    """
    if patterns is None:
        patterns = ["*.kt", "*.java"]
    if skip_dirs is None:
        skip_dirs = {"test", "androidTest", "migration", "generated", "build"}

    search_root = os.path.join(root, subdir) if subdir else root
    files: List[str] = []

    if not os.path.isdir(search_root):
        return files

    for dirpath, dirnames, filenames in os.walk(search_root):
        # Prune skip directories
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]

        for filename in filenames:
            for pattern in patterns:
                # Simple glob matching (no full fnmatch needed for *.kt/*.java)
                if pattern.startswith("*."):
                    ext = pattern[1:]
                    if filename.endswith(ext):
                        files.append(os.path.join(dirpath, filename))
                        break
                elif filename == pattern:
                    files.append(os.path.join(dirpath, filename))
                    break

    return sorted(files)


# ── Report writing ──────────────────────────────────────────────────────────────

def write_report_json(
    path: Path,
    guard_name: str,
    exit_code: int,
    findings: List[str],
    summary: Optional[Dict[str, Any]] = None,
) -> None:
    """Write a machine-readable JSON report for a guard run."""
    import json
    from datetime import datetime, timezone

    payload: Dict[str, Any] = {
        "guard": guard_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "exit_code": exit_code,
        "findings_count": len(findings),
        "findings": findings,
    }
    if summary:
        payload["summary"] = summary

    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")
