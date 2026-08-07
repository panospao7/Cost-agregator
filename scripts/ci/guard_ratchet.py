#!/usr/bin/env python3
"""
GUARD_RATCHET -- Enforces no-growth baselines for architecture guards.

Runs a guard script, fingerprints its violations, and compares against
a stored baseline. Reports new, resolved, and unchanged findings.

Exit codes:
  0 -- no new findings and no stale/resolved baseline entries (pass)
  1 -- policy violation: new or stale/resolved findings detected when
       --fail-on-violation is enabled
  2 -- infrastructure/configuration failure (guard crash, missing or
       malformed baseline, unlaunchable Python, unexpected child exit, etc.)

Usage (preferred -- repeatable single-token --command-arg=<value> list, shell=False):
  python scripts/ci/guard_ratchet.py \
    --guard-name cancellation \
    --command-arg=python3 \
    --command-arg=scripts/verify_cancellation_boundaries.py \
    --baseline config/baselines/cancellation.json \
    --fail-on-violation

  Every child argument is encoded as ONE --command-arg=<value> token,
  including option-like child values, e.g.:

    --command-arg=--fail-on-violation
    --command-arg=--ownership-policy
    --command-arg=--structural-exceptions
    --command-arg=--structural-manifest

  A separate "--command-arg <value>" pair would let argparse re-parse
  option-like child values as the ratchet's own flags and abort with
  "expected one argument".  (The separate-token form still parses for
  ordinary non-option values, but the single-token form is unambiguous for
  every argument and is therefore preferred.)

Usage (legacy compatibility -- --command shell string):
  python scripts/ci/guard_ratchet.py \
    --guard-name cancellation \
    --command "python3 scripts/verify_cancellation_boundaries.py" \
    --baseline config/baselines/cancellation.json \
    --fail-on-violation

  The legacy form is parsed with a cross-platform, shell-free tokenizer
  (never shell=True).  Syntactic surrounding quotes are removed so quoted
  paths containing spaces work on every platform, e.g.:

    --command 'python3 "C:\\dir with spaces\\guard.py"'

  Shell metacharacters (;, &&, |, >, ...) are inert tokens, never operators.
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union

# Ensure stdout/stderr can handle Unicode on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ------------------------------------------------------------------
# Fingerprint extraction
# ------------------------------------------------------------------

# Pattern for a line containing .kt:NNN or .java:NNN
_PATH_LINE_RE = re.compile(r'(.+\.(?:kt|java):\d+)')

# Pattern to extract a bracketed rule/reason code:  [CODE]
_BRACKET_RULE_RE = re.compile(r'^\s*\[([A-Z].+?)\]\s*$')

# Pattern for standard single-line:  RULE_ID path:line ...
# e.g., "G-CANCEL-01 app/src/.../File.kt:123 description"
_STANDARD_RE = re.compile(
    r'^([A-Z][A-Z0-9_-]{2,40})\s+'
    r'(.+\.(?:kt|java):\d+)'
)

# Pattern for money guard:  Lnnn [G-MONEY-NN]
_MONEY_RULE_RE = re.compile(
    r'^\s*L(\d+)\s+\[(G-MONEY-\d+)\]\s'
)

# Pattern to extract just the path from a money header line
_MONEY_PATH_HEADER_RE = re.compile(
    r'^(?:FAIL|PASS)\s+(.+):$'
)


def _find_project_root() -> Path:
    """Return the project root directory (current working directory)."""
    return Path.cwd().resolve()


def _sanitize_guard_name(name: str) -> str:
    """Return a bounded, controlled representation of a guard name.

    Non ``[A-Za-z0-9_.-]`` characters (including newlines) are replaced with
    ``_`` and the result is capped at 80 characters so a hostile or malformed
    ``--guard-name`` can never turn a diagnostic into multiple unbounded lines
    or inject unbounded payloads.
    """
    return re.sub(r"[^A-Za-z0-9_.-]", "_", name)[:80]


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
        # Not under project root -- keep as-is but normalise separators
        result = file_path.replace("\\", "/")

    if line_num:
        return f"{result}:{line_num}"
    return result


def _make_fingerprint(rule_id: str, path_line: str, project_root: Path) -> str:
    """Create a normalized fingerprint from a rule_id and path:line.

    Line numbers are stripped for stable fingerprints — a blank line added
    above a violation must not create a false positive.
    """
    normalized = _normalize_path(path_line.strip(), project_root)
    # Strip line number for stable fingerprints
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

    # -- Format A: Bracketed rule-id prefix  [CODE] ... ---------------------
    if stripped.startswith("["):
        # Find the closing bracket
        rb = stripped.find("]", 1)
        if rb < 1:
            return None
        rule_id = stripped[1:rb].strip()
        if not rule_id or not rule_id[0].isupper():
            return None

        rest = stripped[rb + 1:].lstrip()

        # Try to find a dash-separator (em-dash, en-dash, or hyphen)
        # that indicates an entity name before the path
        dash_sep = re.search(r'\s[—–-]\s', rest)
        if dash_sep:
            rest = rest[dash_sep.end():]

        pm = _PATH_LINE_RE.search(rest)
        if pm:
            path_line = pm.group(1)
            return (rule_id, _normalize_path(path_line, project_root))
        return None

    # -- Format B: Standard bare rule_id + path ------------------------------
    sm = _STANDARD_RE.match(stripped)
    if sm:
        rule_id = sm.group(1)
        path_line = sm.group(2)
        return (rule_id, _normalize_path(path_line, project_root))

    return None


def extract_fingerprints(stdout: str, project_root: Optional[Path] = None) -> List[str]:
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
        project_root = _find_project_root()

    fingerprints: Set[str] = set()
    lines = stdout.splitlines()
    n = len(lines)

    # Track the last-seen file path for money guard
    last_money_path: Optional[str] = None

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue

        # -- Money guard: detect file-path header  "FAIL path:" ---------------
        mp = _MONEY_PATH_HEADER_RE.match(stripped)
        if mp:
            last_money_path = mp.group(1)
            continue

        # -- Money guard: rule line  "Lnnn [G-MONEY-NN] ..." -----------------
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

        # -- Same-line format (standard, bracketed, dash-separated) -----------
        same_line = _try_extract_from_line(line, project_root)
        if same_line is not None:
            rule_id, norm_path = same_line
            # Strip line number for stable fingerprints
            if ":" in norm_path:
                file_path = norm_path.rsplit(":", 1)[0]
            else:
                file_path = norm_path
            fingerprints.add(f"{rule_id} {file_path}")
            continue

        # -- Two-line format (db_access): standalone path:line, rule above ---
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


# ------------------------------------------------------------------
# Baseline I/O
# ------------------------------------------------------------------

def load_baseline(path: Path, guard_name: Optional[str] = None) -> Optional[Dict]:
    """Load a baseline JSON file.  Returns None if the file is missing.

    When *guard_name* is provided, validates structure and exits 2 on
    malformed or mismatched data (unreadable/unparseable file, non-dict
    JSON top level, wrong guard name, fingerprints missing / not a list /
    non-string entries / duplicates).  Failures are reported with controlled
    diagnostics only -- never a traceback or a raw exception message.
    """
    if not path.exists():
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        # File disappeared between the existence check and the open.
        return None
    except UnicodeDecodeError:
        print(f"ERROR: Baseline file is not valid UTF-8: {path}", file=sys.stderr)
        sys.exit(2)
    except json.JSONDecodeError:
        print(f"ERROR: Malformed baseline JSON in {path}", file=sys.stderr)
        sys.exit(2)
    except (PermissionError, OSError) as exc:
        print(
            f"ERROR: Could not read baseline file {path} "
            f"({exc.__class__.__name__})",
            file=sys.stderr,
        )
        sys.exit(2)

    if not isinstance(data, dict):
        print(
            f"ERROR: Baseline JSON top-level value must be an object in {path}",
            file=sys.stderr,
        )
        sys.exit(2)

    if guard_name is not None:
        # Validate guard name matches
        actual_guard = data.get("guard")
        if actual_guard != guard_name:
            shown = actual_guard if isinstance(actual_guard, str) else "<non-string>"
            print(
                f"ERROR: Baseline guard name mismatch in {path}: "
                f"expected '{guard_name}', got '{shown}'",
                file=sys.stderr,
            )
            sys.exit(2)

    fingerprints = data.get("fingerprints")
    if not isinstance(fingerprints, list):
        print(
            f"ERROR: Baseline 'fingerprints' is not a list in {path}",
            file=sys.stderr,
        )
        sys.exit(2)

    # Reject non-string or blank entries (they would poison the set-based
    # duplicate check and produce unstable fingerprints downstream).
    for fp in fingerprints:
        if not isinstance(fp, str) or not fp.strip():
            print(
                f"ERROR: Baseline 'fingerprints' entries must be non-empty "
                f"strings in {path}",
                file=sys.stderr,
            )
            sys.exit(2)

    # Check for duplicate fingerprints
    if len(fingerprints) != len(set(fingerprints)):
        print(
            f"ERROR: Baseline contains duplicate fingerprints in {path}",
            file=sys.stderr,
        )
        sys.exit(2)

    return data


def save_baseline(path: Path, guard_name: str, fingerprints: List[str]) -> None:
    """Write (or overwrite) a baseline JSON file."""
    baseline = {
        "guard": guard_name,
        "generated": datetime.now(timezone.utc).isoformat(),
        "fingerprints": fingerprints,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(baseline, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ------------------------------------------------------------------
# Comparison
# ------------------------------------------------------------------

def compare_fingerprints(
    baseline_fps: List[str], current_fps: List[str]
) -> Tuple[List[str], List[str], List[str]]:
    """Compare baseline and current fingerprint lists.

    Returns:
        (new, resolved, unchanged) -- each sorted.
    """
    bset = set(baseline_fps)
    cset = set(current_fps)

    new = sorted(cset - bset)
    resolved = sorted(bset - cset)
    unchanged = sorted(bset & cset)
    return new, resolved, unchanged


# ------------------------------------------------------------------
# Guard execution
# ------------------------------------------------------------------

def _resolve_python(command: List[str]) -> List[str]:
    """Resolve the Python interpreter in a command for cross-platform compatibility.

    On Windows, ``python3`` may map to a non-functional Microsoft Store alias.
    We use ``sys.executable`` as the safe fallback, which works on all platforms.
    """
    if not command:
        return command

    exe = command[0]
    # Only resolve known Python entry-point names
    if exe not in ("python3", "python"):
        return command

    # On Windows, the Microsoft Store app execution alias for python3.exe
    # may appear on PATH but fail at runtime. Always prefer sys.executable.
    if sys.platform == "win32":
        return [sys.executable] + command[1:]

    # On Linux/macOS: use python3 if available, fall back to python, then sys.executable
    if shutil.which(exe) is not None:
        return command

    alt = "python" if exe == "python3" else "python3"
    if shutil.which(alt) is not None:
        return [alt] + command[1:]

    return [sys.executable] + command[1:]


def _strip_syntactic_quotes(token: str) -> str:
    """Remove one matching pair of syntactic surrounding quotes from a token.

    Only a quote character that both starts AND ends the token (with the same
    quote character) is removed.  Quotes embedded inside a token are legitimate
    inner characters and are preserved untouched, so ``py"th"on`` stays
    ``py"th"on`` and only ``"C:\\dir with spaces\\x.py"`` loses its quotes.
    """
    if len(token) >= 2 and token[0] in ('"', "'") and token[-1] == token[0]:
        return token[1:-1]
    return token


def _split_legacy_command(command: str) -> List[str]:
    """Split a legacy ``--command`` string into an argument list without a shell.

    Cross-platform, injection-safe alternative to ``shlex.split`` for the
    legacy compatibility path (never executes through a shell):

      * whitespace separates tokens;
      * whitespace inside a matching pair of quotes is part of the same
        token, so quoted paths containing spaces stay intact
        (``"C:\\Program Files\\x.py"`` -> ``C:\\Program Files\\x.py``);
      * shell metacharacters (``;``, ``&&``, ``|``, ``>``, ...) are ordinary
        characters -- they are passed through as inert argument tokens and are
        never interpreted, because the result is executed with ``shell=False``;
      * each token has only its syntactic surrounding quotes removed (see
        :func:`_strip_syntactic_quotes`); legitimate inner quote characters
        are preserved;
      * backslashes are literal (Windows path separators are never treated as
        shell escape characters).

    Returns an empty list when *command* contains only whitespace.
    """
    tokens: List[str] = []
    buf: List[str] = []
    i = 0
    n = len(command)
    while i < n:
        ch = command[i]
        if ch.isspace():
            if buf:
                tokens.append("".join(buf))
                buf = []
            i += 1
            continue
        if ch in ('"', "'"):
            # A quoted span: keep the quote characters in the buffer so the
            # post-pass can strip them as syntactic surrounding quotes while
            # leaving any inner characters untouched.
            close = command.find(ch, i + 1)
            if close != -1:
                buf.append(ch)
                buf.append(command[i + 1:close])
                buf.append(ch)
                i = close + 1
                continue
            # Unbalanced opening quote: keep it as a literal character.
            buf.append(ch)
            i += 1
            continue
        buf.append(ch)
        i += 1
    if buf:
        tokens.append("".join(buf))
    return [_strip_syntactic_quotes(t) for t in tokens]


def run_guard_command(
    command: Union[str, List[str]], cwd: Path, timeout: int = 300
) -> Tuple[int, str, str]:
    """Execute a guard command and return (exit_code, stdout, stderr).

    ``command`` may be either of:

      * a list of arguments (the preferred ``--command-arg=<value>``
        single-token form) — executed directly with ``shell=False``, so
        paths containing spaces need no quoting, can never be reinterpreted
        by a shell, and option-like child values stay inside the child
        command; or
      * a command string (the legacy ``--command`` compatibility form) —
        parsed with :func:`_split_legacy_command` (a cross-platform,
        shell-free tokenizer that removes only syntactic surrounding quotes,
        so quoted paths containing spaces work on Windows and POSIX alike) and
        executed with ``shell=False``.

    Exit code -1 signals an infrastructure error (timeout, not-found, ...).

    Resolves ``python3`` / ``python`` interpreter tokens to ``sys.executable``
    for cross-platform portability (avoids Windows Microsoft Store alias
    failures).
    """
    try:
        if isinstance(command, list):
            parts = list(command)
            if not parts:
                return -1, "", "Empty command"
        else:
            # Parse command string safely for shell=False execution.
            # Quoted paths with spaces are kept as single tokens and only
            # syntactic surrounding quotes are removed; shell metacharacters
            # stay inert argument tokens (never shell operators).
            parts = _split_legacy_command(command)
            if not parts:
                return -1, "", "Empty command"

        # Resolve Python interpreter for cross-platform compatibility
        resolved = _resolve_python(parts)

        result = subprocess.run(
            resolved,
            shell=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            cwd=str(cwd),
        )
        return result.returncode, (result.stdout or ""), (result.stderr or "")
    except subprocess.TimeoutExpired:
        return -1, "", f"Timeout after {timeout}s"
    except FileNotFoundError:
        first = command[0] if isinstance(command, list) and command else str(command)
        return -1, "", f"Command not found: {first}"
    except Exception as exc:
        # Controlled bounded diagnostic only -- never expose raw exception
        # text (may carry file paths, messages, or internal state).
        return -1, "", f"RATCHET_SUBPROCESS_ERROR: {exc.__class__.__name__}"


# ------------------------------------------------------------------
# Reporting
# ------------------------------------------------------------------

def print_report(
    guard_name: str,
    baseline_count: int,
    current_count: int,
    new: List[str],
    resolved: List[str],
    unchanged: List[str],
) -> str:
    """Print a human-readable report to stdout.  Returns the status label."""
    print(f"Guard: {guard_name}")
    print(f"Baseline: {baseline_count} findings")
    print(f"Current:  {current_count} findings")
    print(f"  NEW: {len(new)}")
    for fp in new:
        print(f"    {fp}")
    print(f"  RESOLVED: {len(resolved)}")
    for fp in resolved:
        print(f"    {fp}")
    print(f"  UNCHANGED: {len(unchanged)}")

    if new:
        status = "FAIL"
        msg = f"{len(new)} new findings detected"
    elif resolved:
        status = "DECREASED"
        msg = f"{len(resolved)} findings resolved"
    else:
        status = "PASS"
        msg = "no new or resolved findings"

    print(f"Status: {status} -- {msg}")
    return status


def write_summary_json(
    path: Path,
    guard_name: str,
    new: List[str],
    resolved: List[str],
    unchanged: List[str],
    status: str,
    exit_code: int,
) -> None:
    """Write a machine-readable summary JSON."""
    payload = {
        "guard": guard_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "exit_code": exit_code,
        "counts": {
            "baseline": len(resolved) + len(unchanged),
            "current": len(new) + len(unchanged),
            "new": len(new),
            "resolved": len(resolved),
            "unchanged": len(unchanged),
        },
        "new": new,
        "resolved": resolved,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ------------------------------------------------------------------
# Main
# ------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Guard Ratchet -- enforces no-growth baselines for architecture guards."
    )
    parser.add_argument(
        "--guard-name", required=True, help="Human-readable guard name for reporting."
    )
    parser.add_argument(
        "--command",
        required=False,
        default=None,
        help="Shell command to run the guard script.  LEGACY compatibility "
             "path only — prefer the repeatable --command-arg=<value> form "
             "so the command is executed as an argument list with shell=False.",
    )
    parser.add_argument(
        "--command-arg",
        action="append",
        dest="command_args",
        default=None,
        metavar="VALUE",
        help="One argument of the guard command.  Repeatable.  Encode each "
             "child argument as a single --command-arg=<value> token, "
             "including option-like child values (e.g. "
             "--command-arg=--fail-on-violation), so argparse can never "
             "re-parse them as the ratchet's own flags.  The arguments are "
             "executed as an argument list with shell=False, so paths "
             "containing spaces need no quoting and can never be "
             "reinterpreted by a shell.  Use either this or --command, not "
             "both.",
    )
    parser.add_argument(
        "--baseline", required=True, help="Path to baseline JSON file."
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Exit with code 1 when new or stale/resolved findings are "
             "detected (policy violation).",
    )
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="Rewrite the baseline with current findings (only if count "
        "decreased or stayed the same; rejects if count increased).",
    )
    parser.add_argument(
        "--ci-mode",
        action="store_true",
        help="CI mode: disables --update-baseline and enforces stricter policies.",
    )
    parser.add_argument(
        "--timeout", type=int, default=300, help="Command timeout in seconds."
    )
    parser.add_argument(
        "--output-summary",
        type=Path,
        default=None,
        help="Write a machine-readable summary JSON to this path.",
    )
    args = parser.parse_args()

    # -- Command resolution ---------------------------------------------------
    # Prefer the repeatable single-token --command-arg=<value> argument-list
    # form (executed with shell=False, no shell-string ambiguity; option-like
    # child values stay inside the child command).  --command is retained only
    # as a compatibility path for older callers; the two forms are mutually
    # exclusive.  (The separate "--command-arg <value>" pair also still parses
    # for ordinary values, but the single-token form is unambiguous for every
    # argument.)
    if args.command_args is not None and args.command is not None:
        print(
            "ERROR: use either --command or --command-arg, not both",
            file=sys.stderr,
        )
        sys.exit(2)
    if args.command_args is not None:
        guard_command = args.command_args
    elif args.command is not None:
        guard_command = args.command
    else:
        print(
            "ERROR: one of --command or --command-arg is required",
            file=sys.stderr,
        )
        sys.exit(2)

    # -- CI mode: reject baseline updates ----------------------------------------
    if args.ci_mode and args.update_baseline:
        print("ERROR: Baseline updates prohibited in CI mode", file=sys.stderr)
        sys.exit(2)

    project_root = _find_project_root()
    baseline_path = Path(args.baseline)
    if not baseline_path.is_absolute():
        baseline_path = project_root / baseline_path

    # -- 1. Run the guard command ------------------------------------------------
    guard_exit, stdout, stderr = run_guard_command(
        guard_command, project_root, args.timeout
    )

    if guard_exit < 0:
        print(f"Guard ratchet error: {stderr}", file=sys.stderr)
        sys.exit(2)

    if guard_exit == 0:
        # Guard passed — findings should be empty or structured
        pass
    elif guard_exit == 1:
        # Guard found violations — parse findings below.  Raw child output is
        # intentionally NOT echoed here: parseability is decided only after
        # fingerprint extraction, and unparseable output is reported below as
        # a single bounded diagnostic.
        pass
    elif guard_exit == 2:
        # Guard infrastructure error
        print(f"Guard exited with infrastructure error (code 2)", file=sys.stderr)
        sys.exit(2)
    else:
        # Unknown exit code
        print(f"Guard exited with unknown code {guard_exit}", file=sys.stderr)
        sys.exit(2)

    # -- 2. Extract fingerprints -------------------------------------------------
    # Never echo raw child stdout/stderr before deciding parseability: it may
    # carry unparseable or sensitive payloads.  Parseable findings are surfaced
    # only through the sanitized fingerprint report (print_report) below.
    current_fps = extract_fingerprints(stdout, project_root)

    # If guard exited 1 but no fingerprints could be parsed, that's an
    # infrastructure error (output format changed, guard broken, etc.).
    # Emit exactly one bounded, structured diagnostic -- never raw child
    # stdout/stderr (may carry sensitive data) -- then exit 2.
    if guard_exit == 1 and len(current_fps) == 0:
        print(
            f"RATCHET_UNPARSEABLE_GUARD_OUTPUT: "
            f"guard={_sanitize_guard_name(args.guard_name)} "
            f"exit=1 (no parseable findings)",
            file=sys.stderr,
        )
        sys.exit(2)

    # -- 3. Load baseline --------------------------------------------------------
    baseline_data = load_baseline(baseline_path, args.guard_name)
    if baseline_data is None:
        print(f"ERROR: Baseline file not found: {baseline_path}", file=sys.stderr)
        sys.exit(2)

    baseline_fps = baseline_data.get("fingerprints", [])

    # -- 4. Compare --------------------------------------------------------------
    new, resolved, unchanged = compare_fingerprints(baseline_fps, current_fps)

    # -- 5. Report ---------------------------------------------------------------
    status = print_report(
        args.guard_name,
        len(baseline_fps),
        len(current_fps),
        new,
        resolved,
        unchanged,
    )

    # -- 6. Determine final exit code (before summary JSON for consistency)   ----
    if args.fail_on_violation:
        final_exit = 1 if (new or resolved) else 0
    else:
        final_exit = 0

    # -- 7. Summary JSON (optional) ----------------------------------------------
    summary_path = args.output_summary
    if summary_path is not None:
        write_summary_json(
            summary_path,
            args.guard_name,
            new,
            resolved,
            unchanged,
            status,
            final_exit,
        )

    # -- 8. Update baseline (optional) -------------------------------------------
    if args.update_baseline:
        if len(new) > 0:
            print(
                "ERROR: Cannot update baseline -- findings increased. "
                "Fix the new findings or review before updating.",
                file=sys.stderr,
            )
            sys.exit(1 if args.fail_on_violation else 2)
        save_baseline(baseline_path, args.guard_name, current_fps)
        print(f"Baseline updated: {len(current_fps)} findings")
        # After successful update, reset final_exit (maintenance mode)
        final_exit = 0

    # -- 9. Exit -----------------------------------------------------------
    sys.exit(final_exit)


if __name__ == "__main__":
    main()
