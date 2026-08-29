#!/usr/bin/env python3
"""
GUARD_RATCHET -- Enforces no-growth baselines for architecture guards.

Runs a guard script, fingerprints its violations, and compares against
a stored baseline. Reports new, resolved, and unchanged findings.

Exit codes:
  0 -- no new findings and no stale/resolved baseline entries (pass)
  1 -- policy violation:
          protocol v1: new or stale/resolved findings detected when
          --fail-on-violation is enabled
          protocol v2: ANY comparison delta (new/resolved keys or
          occurrences) -- independent of --fail-on-violation, which has
          no effect on the v2 exit code
  2 -- infrastructure/configuration failure (guard crash, missing or
        malformed baseline, unlaunchable Python, unexpected child exit,
        proposal misuse, etc.).  Protocol v2: expired baseline debt also
        fails closed with exit 2 (GR-09 debt rule 9 -- expired reviewed
        debt is an invalid baseline state that must be re-reviewed, never
        a normal policy-violation signal), and blocking (non-advisory)
        report diagnostics are infrastructure failures while advisory
        diagnostics (bounded controlled_context["advisory"] marker,
        GR-07 Option-B amendment) never block.

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

Protocol v2 proposal mode (reviewed debt reduction, candidate output only):
  python scripts/ci/guard_ratchet.py \
    --guard-name db_access \
    --command-arg=python3 \
    --command-arg=scripts/verify_db_access_boundaries.py \
    --baseline config/baselines/db_access_v2.json \
    --finding-protocol=2 \
    --propose-baseline config/baselines/db_access_v2.proposed.json

  --propose-baseline writes a CANDIDATE v2 baseline (never the active
  baseline) that reflects the current reviewed debt state.  It is rejected
  in --ci-mode, with finding protocol v1, together with --update-baseline,
  and when the candidate path equals the active baseline path.  A candidate
  is generated only when there are no new findings (no growth), no
  unresolved classifications (report diagnostics), and no expired baseline
  debt; otherwise the run fails non-zero without writing a candidate.
  --update-baseline remains prohibited for v2 baselines and in --ci-mode.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from contextlib import suppress
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union

# Ensure stdout/stderr can handle Unicode on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# Ensure sibling modules are importable regardless of how the script is run.
_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guard_findings import (  # noqa: E402
    FINGERPRINT_VERSION,
    GuardFindingsError,
    _FP_RE,  # canonical protocol-v2 fingerprint pattern (aggregated findings)
    aggregate_findings,
    load_report,
)

# ------------------------------------------------------------------
# Bounded protocol limits
# ------------------------------------------------------------------
# Bounds applied by the baseline loaders AND the candidate baseline
# generator so a hostile or oversized baseline can never turn into
# unbounded memory use, unbounded report output, or an unbounded candidate
# file.  ``MAX_BASELINE_ENTRIES`` mirrors the report findings limit (a
# baseline can never legitimately exceed what a single report produces);
# the string bounds keep fingerprints and review metadata length-capped and
# free of control characters so they can never inject report lines or
# unbounded payloads.
MAX_BASELINE_ENTRIES = 100_000
MAX_BASELINE_FINGERPRINT = 2048
MAX_BASELINE_METADATA = 512
MAX_BASELINE_PREVIEW = 50

# NUL, C0 control characters (incl. newline/CR/tab), and DEL.  Any baseline
# string that later flows into diagnostics or reports must be free of these.
_BASELINE_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")

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
    non-string entries / duplicates / oversized entry count or fingerprint).
    Failures are reported with controlled diagnostics only -- never a
    traceback, a raw exception message, the baseline path, or any baseline
    value.
    """
    try:
        if not path.exists():
            return None
    except FileNotFoundError:
        # File disappeared between the existence check and the open.
        return None
    except Exception:
        # Unexpected failure while probing the baseline path (e.g. a
        # RuntimeError raised by a hostile or broken filesystem / stat
        # implementation).  Never echo the exception class, message, or path
        # (any of them may carry sensitive or hostile content); fail closed
        # with the fixed controlled diagnostic.  BaseException subclasses
        # (SystemExit, KeyboardInterrupt, GeneratorExit) are intentionally
        # NOT caught here and propagate unchanged.
        print(
            "RATCHET_BASELINE_PROBE_FAILED: baseline path could not be probed",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        # File disappeared between the existence check and the open.
        return None
    except UnicodeDecodeError:
        # Controlled code only -- never echo the baseline path or any
        # decoder detail (the message may carry the filesystem layout).
        print(
            "RATCHET_BASELINE_UNREADABLE: baseline file is not valid UTF-8",
            file=sys.stderr,
        )
        sys.exit(2)
    except json.JSONDecodeError:
        # Controlled code only -- never echo the baseline path or the
        # offending JSON text.
        print(
            "RATCHET_BASELINE_MALFORMED: baseline file is not valid JSON",
            file=sys.stderr,
        )
        sys.exit(2)
    except (PermissionError, OSError):
        # Controlled code only -- never echo the baseline path, OS error
        # text, or the exception class (the message may carry the
        # filesystem layout).
        print(
            "RATCHET_BASELINE_UNREADABLE: baseline file could not be read",
            file=sys.stderr,
        )
        sys.exit(2)
    except Exception:
        # Unexpected failure while opening/reading/parsing the baseline.
        # Never echo the exception class, message, or path (any of them may
        # carry sensitive or hostile content); fail closed with the fixed
        # controlled diagnostic.  BaseException subclasses (SystemExit,
        # KeyboardInterrupt, GeneratorExit) are intentionally NOT caught
        # here and propagate unchanged.
        print(
            "RATCHET_BASELINE_UNREADABLE: baseline file could not be read",
            file=sys.stderr,
        )
        sys.exit(2)

    if not isinstance(data, dict):
        # Controlled code only -- never echo the baseline path or the
        # offending top-level value.
        print(
            "RATCHET_BASELINE_INVALID: baseline JSON top-level value must be "
            "an object",
            file=sys.stderr,
        )
        sys.exit(2)

    if guard_name is not None:
        # Validate guard name matches.  Never interpolate the baseline
        # guard value or the requested guard name: both could carry hostile
        # content.
        if data.get("guard") != guard_name:
            print(
                "RATCHET_BASELINE_GUARD_MISMATCH: baseline guard name mismatch",
                file=sys.stderr,
            )
            sys.exit(2)

    fingerprints = data.get("fingerprints")
    if not isinstance(fingerprints, list):
        print(
            "RATCHET_BASELINE_INVALID: baseline 'fingerprints' is not a list",
            file=sys.stderr,
        )
        sys.exit(2)

    # Bounded size check before any per-entry work or set materialization:
    # a huge (or hostile) baseline must not be accepted.
    if len(fingerprints) > MAX_BASELINE_ENTRIES:
        print(
            "RATCHET_BASELINE_TOO_LARGE: baseline contains too many "
            "fingerprints",
            file=sys.stderr,
        )
        sys.exit(2)

    # Reject non-string or blank entries (they would poison the set-based
    # duplicate check and produce unstable fingerprints downstream) and
    # fingerprints that exceed the bounded length (they could turn reports
    # into unbounded output).
    for fp in fingerprints:
        if not isinstance(fp, str) or not fp.strip():
            print(
                "RATCHET_BASELINE_INVALID: baseline 'fingerprints' entries "
                "must be non-empty strings",
                file=sys.stderr,
            )
            sys.exit(2)
        if len(fp) > MAX_BASELINE_FINGERPRINT:
            print(
                "RATCHET_BASELINE_INVALID: baseline fingerprint exceeds "
                "maximum length",
                file=sys.stderr,
            )
            sys.exit(2)

    # Check for duplicate fingerprints
    if len(fingerprints) != len(set(fingerprints)):
        print(
            "RATCHET_BASELINE_INVALID: baseline contains duplicate "
            "fingerprints",
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
# Protocol v2 (structured findings)
# ------------------------------------------------------------------

# Strict canonical ISO-8601 datetime with an explicit timezone, matching the
# values the protocol writers emit (datetime.now(timezone.utc).isoformat()):
#   YYYY-MM-DDTHH:MM:SS[.ffffff](Z|±HH:MM)
# Only zero-padded components and a mandatory timezone are accepted; date-only,
# timezone-less, and other noncanonical spellings fail the match before any
# broad date parsing happens.
_ISO8601_DATETIME_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d{1,6})?"
    r"(?:Z|[+-]\d{2}:\d{2})$"
)

# Exact canonical calendar date: YYYY-MM-DD (zero-padded, no time component).
_YYYY_MM_DD_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# Exact allowed protocol-v2 baseline envelope keys.  The envelope is closed:
# unknown keys (extra metadata, diagnostics, ...) and missing required keys
# are rejected with the controlled RATCHET_BASELINE_INVALID exit 2 -- never
# silently ignored (which would let partial or stale envelopes compare).
_ALLOWED_ENVELOPE_KEYS = frozenset({
    "baseline_schema_version",
    "guard_output_schema_version",
    "fingerprint_schema_version",
    "guard",
    "generated_at",
    "entries",
})

# Exact allowed protocol-v2 baseline entry keys.  The entry schema is closed:
# unknown keys (legacy aliases such as ``expiry``, extra metadata, or
# diagnostics fields) are rejected as invalid -- never accepted as
# compatibility fields.
_ALLOWED_ENTRY_KEYS = frozenset({
    "fingerprint",
    "count",
    "rule",
    "classification",
    "reason",
    "owner",
    "linked_issue",
    "expires",
})


def _registry_finding_protocol(guard_name: str) -> Optional[int]:
    """Return the registered ``finding_protocol`` for ``guard_name`` if any.

    Consults the guard registry (scripts/ci/guard_registry.py) metadata.
    Registry metadata is optional: any import/format failure degrades to
    ``None`` and the caller falls back to the legacy protocol 1.
    """
    try:
        from guard_registry import GUARD_REGISTRY
    except Exception:
        return None
    entry = GUARD_REGISTRY.get(guard_name)
    if isinstance(entry, dict):
        value = entry.get("finding_protocol")
        if value in (1, 2):
            return value
    return None


def _resolve_finding_protocol(explicit: Optional[int], guard_name: str) -> int:
    """Resolve the finding protocol from ``--finding-protocol`` or registry
    metadata; defaults to legacy protocol 1 when nothing is declared."""
    if explicit is not None:
        if explicit in (1, 2):
            return explicit
        print("ERROR: --finding-protocol must be 1 or 2", file=sys.stderr)
        sys.exit(2)
    registered = _registry_finding_protocol(guard_name)
    if registered is not None:
        return registered
    return 1


def run_guard_command_v2(
    command: Union[str, List[str]], cwd: Path, timeout: int = 300
) -> Tuple[int, Optional[str]]:
    """Execute a protocol-v2 guard and return ``(exit_code, report_path)``.

    Creates a unique temporary report path with ``tempfile.mkstemp`` and hands
    the existing path to the child through ``COST_AGGREGATOR_GUARD_FINDINGS_FILE``
    together with ``COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=2``.  The securely
    created file is RETAINED: the parent descriptor is closed before spawning
    and the file is never unlinked beforehand, so the child always sees a
    writable existing report path and there is no symlink-replacement (TOCTOU)
    window between path creation and child execution.  The child may truncate
    and rewrite the existing file in place (or replace it atomically).  The
    child command is executed with ``shell=False`` as an argument list (the
    same ``--command-arg`` / legacy ``--command`` handling as the v1 path).
    The caller must unlink the returned ``report_path`` exactly once in its
    final cleanup after child/report processing, including on every error
    path.  A child that never writes leaves the retained file empty, which
    the report loader rejects as an invalid report (exit 2) per the contract.
    ``report_path`` is ``None`` only when the child command could not be
    launched or the temporary report path could not be created (both map to a
    controlled infrastructure exit 2).
    """
    report_path: Optional[str] = None
    fd = None
    try:
        fd, report_path = tempfile.mkstemp(
            prefix="cost-aggregator-guard-findings-", suffix=".json"
        )
        os.close(fd)
        fd = None  # The descriptor is closed; the file itself is retained.
    except Exception:
        # Infrastructure failure: the temporary report path could not be
        # created (or its descriptor could not be closed).  Never surface the
        # raw temp path or the exception text (the temp directory path may
        # leak filesystem layout); fail closed with a controlled (-1, None)
        # result so the caller reports the bounded exit-2 infrastructure
        # diagnostic.  Cleanup is preserved on this failure path: close and
        # unlink the just-created file so no temp artifact survives.
        if fd is not None:
            with suppress(OSError):
                os.close(fd)
        if report_path is not None:
            with suppress(OSError):
                os.unlink(report_path)
        return -1, None
    # The securely created report file is intentionally RETAINED for the
    # child: the child writes into (truncates/rewrites or atomically replaces)
    # the existing path.  There is no pre-execution unlink and therefore no
    # window in which the unique path could be re-created as a symlink or
    # another process's file (TOCTOU).  Cleanup is the caller's job in its
    # final finally after child/report processing -- never here.  A child that
    # never writes leaves the file empty, which the report loader rejects as
    # an invalid report (exit 2) per the contract.

    env = dict(os.environ)
    env["COST_AGGREGATOR_GUARD_FINDINGS_FILE"] = report_path
    env["COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA"] = "2"
    try:
        if isinstance(command, list):
            parts = list(command)
            if not parts:
                return -1, report_path
        else:
            parts = _split_legacy_command(command)
            if not parts:
                return -1, report_path
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
            env=env,
        )
        return result.returncode, report_path
    except subprocess.TimeoutExpired:
        return -1, report_path
    except FileNotFoundError:
        return -1, report_path
    except Exception as exc:
        # Controlled bounded diagnostic only -- never raw exception text.
        return -1, report_path


def load_baseline_v2(
    path: Path, guard_name: str, report_schema_version: int
) -> Optional[Dict]:
    """Load and validate a protocol-v2 baseline envelope.

    Returns the validated baseline dict, or ``None`` when the file is missing.
    Structural/schema/entry failures print one bounded controlled diagnostic
    and exit 2 -- raw baseline values and exception text are never echoed.
    """
    try:
        if not path.exists():
            return None
    except FileNotFoundError:
        # File disappeared between the existence check and the open.
        return None
    except Exception:
        # Unexpected failure while probing the baseline path (e.g. a
        # RuntimeError raised by a hostile or broken filesystem / stat
        # implementation).  Never echo the exception class, message, or path
        # -- any of them may carry sensitive or hostile content; fail closed
        # with the fixed controlled code.  BaseException subclasses
        # (SystemExit, KeyboardInterrupt, GeneratorExit) are intentionally
        # NOT caught here and propagate unchanged.
        print(
            "RATCHET_BASELINE_PROBE_FAILED: baseline path could not be probed",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        return None
    except UnicodeDecodeError:
        # Controlled code only -- never echo the baseline path or any
        # decoder detail.
        print(
            "RATCHET_BASELINE_UNREADABLE: baseline file is not valid UTF-8",
            file=sys.stderr,
        )
        sys.exit(2)
    except json.JSONDecodeError:
        # Controlled code only -- never echo the baseline path or the
        # offending JSON text.
        print(
            "RATCHET_BASELINE_MALFORMED: baseline file is not valid JSON",
            file=sys.stderr,
        )
        sys.exit(2)
    except (PermissionError, OSError):
        # Controlled code only -- never echo the baseline path, OS error
        # text, or the exception class (the message may carry the
        # filesystem layout).
        print(
            "RATCHET_BASELINE_UNREADABLE: baseline file could not be read",
            file=sys.stderr,
        )
        sys.exit(2)
    except Exception:
        # Unexpected failure while opening/reading/parsing the baseline
        # (e.g. a RuntimeError raised by a hostile or broken filesystem /
        # parser).  Never echo the exception class, message, or path -- any
        # of them may carry sensitive or hostile content; fail closed with
        # the fixed controlled code.  BaseException subclasses (SystemExit,
        # KeyboardInterrupt, GeneratorExit) are intentionally NOT caught
        # here and propagate unchanged.
        print(
            "RATCHET_BASELINE_UNREADABLE: baseline file could not be read",
            file=sys.stderr,
        )
        sys.exit(2)

    if not isinstance(data, dict):
        # Controlled code only -- never echo the baseline path or the
        # offending top-level value.
        print(
            "RATCHET_BASELINE_INVALID: baseline top-level value must be "
            "an object",
            file=sys.stderr,
        )
        sys.exit(2)
    _validate_baseline_v2(data, guard_name, report_schema_version)
    return data


def _expiry_date(value: str):
    """Validate an entry expiry (exact canonical YYYY-MM-DD) and return its ``date``.

    The exact ``YYYY-MM-DD`` regex is required *before* any date parsing so
    noncanonical forms are rejected with the controlled baseline exit 2:
    ``2026-1-1`` (unpadded components), ISO datetimes, surrounding whitespace,
    and similar spellings never reach ``date.fromisoformat``.
    """
    if not isinstance(value, str) or not _YYYY_MM_DD_RE.match(value):
        print(
            "RATCHET_BASELINE_INVALID: baseline entry expiry must be a valid "
            "YYYY-MM-DD date",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        return date.fromisoformat(value)
    except ValueError:
        print(
            "RATCHET_BASELINE_INVALID: baseline entry expiry must be a valid "
            "YYYY-MM-DD date",
            file=sys.stderr,
        )
        sys.exit(2)


def _validate_bounded_string_v2(
    value: object, label: str, max_length: int
) -> None:
    """Validate a bounded, controlled baseline string (exits 2 on violation).

    The string must be non-empty, free of leading/trailing whitespace, free
    of NUL/control characters (so it can never inject report lines or
    unbounded payloads), and length-capped at *max_length*.  The raw value is
    never echoed -- only the controlled field label from the closed baseline
    schema is reported.
    """
    if not isinstance(value, str) or not value.strip():
        print(
            f"RATCHET_BASELINE_INVALID: baseline entry {label} must be a "
            "non-empty string",
            file=sys.stderr,
        )
        sys.exit(2)
    if value != value.strip():
        print(
            f"RATCHET_BASELINE_INVALID: baseline entry {label} must not have "
            "leading or trailing whitespace",
            file=sys.stderr,
        )
        sys.exit(2)
    if _BASELINE_CONTROL_RE.search(value):
        print(
            f"RATCHET_BASELINE_INVALID: baseline entry {label} contains "
            "control characters",
            file=sys.stderr,
        )
        sys.exit(2)
    if len(value) > max_length:
        print(
            f"RATCHET_BASELINE_INVALID: baseline entry {label} exceeds "
            f"maximum length {max_length}",
            file=sys.stderr,
        )
        sys.exit(2)


def _validate_baseline_entry(entry: object) -> None:
    """Validate one protocol-v2 baseline entry (exits 2 on any violation).

    The canonical expiry field is exactly ``expires``.  The legacy ``expiry``
    alias is rejected as unknown/invalid schema -- never accepted as a
    compatibility alias, whether it appears alone or alongside ``expires``.

    The ``fingerprint`` must satisfy the same canonical protocol-v2
    fingerprint contract as aggregated findings (a full match of the
    ``v2|``-prefixed pattern, never a startswith-only prefix check), so bare
    prefixes, whitespace-only prefixes, missing semantic components, wrong
    prefixes/versions, and malformed/oversized values are rejected.

    Fingerprint and review metadata (rule, classification, reason, owner,
    linked_issue) are additionally bounded: length-capped and free of
    NUL/control characters and surrounding whitespace so a hostile baseline
    can never inject report lines or unbounded payloads.
    """
    if not isinstance(entry, dict):
        print(
            "RATCHET_BASELINE_INVALID: baseline entry must be an object",
            file=sys.stderr,
        )
        sys.exit(2)
    if "expiry" in entry:
        print(
            "RATCHET_BASELINE_INVALID: baseline entry uses unknown field "
            "'expiry'; the canonical v2 field is 'expires'",
            file=sys.stderr,
        )
        sys.exit(2)
    unknown = set(entry) - _ALLOWED_ENTRY_KEYS
    if unknown:
        # The v2 entry schema is closed: extra metadata or diagnostics fields
        # are rejected outright.  Unknown key names are never echoed (they may
        # carry arbitrary content); the controlled constant is the diagnostic.
        print(
            "RATCHET_BASELINE_INVALID: baseline entry contains unknown field(s)",
            file=sys.stderr,
        )
        sys.exit(2)
    for key in (
        "fingerprint",
        "rule",
        "classification",
        "reason",
        "owner",
        "linked_issue",
        "expires",
    ):
        value = entry.get(key)
        if not isinstance(value, str) or not value.strip():
            print(
                f"RATCHET_BASELINE_INVALID: baseline entry is missing required "
                f"field '{key}'",
                file=sys.stderr,
            )
            sys.exit(2)
    # Bounds and controlled shape (raw values are never echoed).  ``expires``
    # is validated separately by its exact canonical YYYY-MM-DD shape (a fixed
    # 10-character bound with no control characters).
    _validate_bounded_string_v2(
        entry["fingerprint"], "fingerprint", MAX_BASELINE_FINGERPRINT
    )
    for key in ("rule", "classification", "reason", "owner", "linked_issue"):
        _validate_bounded_string_v2(entry[key], key, MAX_BASELINE_METADATA)
    # The fingerprint must satisfy the SAME canonical protocol-v2 fingerprint
    # contract as aggregated findings (guard_findings._FP_RE, applied with a
    # full match -- never a startswith-only prefix check): the value must be a
    # bounded, non-empty string that begins with "v2|" followed by at least
    # one non-whitespace semantic component.  Bare prefixes ("v2|"), prefixes
    # followed by whitespace ("v2| "), missing semantic components,
    # malformed/oversized encodings, and wrong prefixes/versions ("v3|",
    # "xv2|", ...) are all rejected with the controlled baseline exit 2.
    fingerprint = entry["fingerprint"]
    if not _FP_RE.fullmatch(fingerprint):
        print(
            "RATCHET_BASELINE_INVALID: baseline fingerprint is not a "
            "protocol-v2 fingerprint",
            file=sys.stderr,
        )
        sys.exit(2)
    count = entry.get("count")
    if isinstance(count, bool) or not isinstance(count, int) or count < 1:
        print(
            "RATCHET_BASELINE_INVALID: baseline entry count must be a "
            "positive integer",
            file=sys.stderr,
        )
        sys.exit(2)
    # The per-entry occurrence count is bounded by the same protocol limit
    # that caps the number of entries (and mirrors the report findings limit
    # MAX_FINDINGS): a single fingerprint can never legitimately exceed what
    # a whole report produces, so an oversized count is rejected before any
    # arithmetic or report output can use it.  The raw count is never echoed.
    if count > MAX_BASELINE_ENTRIES:
        print(
            "RATCHET_BASELINE_INVALID: baseline entry count exceeds the "
            "maximum",
            file=sys.stderr,
        )
        sys.exit(2)
    if entry.get("classification") != "temporary_debt":
        print(
            "RATCHET_BASELINE_INVALID: baseline entry classification must be "
            "'temporary_debt'",
            file=sys.stderr,
        )
        sys.exit(2)
    _expiry_date(entry["expires"])


def _validate_generated_at(value: object) -> None:
    """Validate the v2 baseline envelope ``generated_at`` timestamp.

    Requires a non-empty string in the strict canonical ISO-8601 datetime
    form emitted by the protocol writers
    (``YYYY-MM-DDTHH:MM:SS[.ffffff](Z|±HH:MM)`` with an explicit timezone).
    Missing, non-string, malformed, date-only, timezone-less, and noncanonical
    values exit 2 with a controlled baseline diagnostic; the raw value is
    never echoed.
    """
    if not isinstance(value, str) or not value.strip():
        print(
            "RATCHET_BASELINE_INVALID: baseline 'generated_at' must be a "
            "non-empty ISO-8601 timestamp string",
            file=sys.stderr,
        )
        sys.exit(2)
    if not _ISO8601_DATETIME_RE.match(value):
        print(
            "RATCHET_BASELINE_INVALID: baseline 'generated_at' must be a "
            "strict ISO-8601 timestamp with an explicit timezone",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        # The regex has already constrained the shape to one canonical form,
        # so this only verifies the calendar/time values are real (e.g. no
        # month 13).  Normalize the 'Z' suffix for pre-3.11 fromisoformat.
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        print(
            "RATCHET_BASELINE_INVALID: baseline 'generated_at' must be a "
            "valid calendar ISO-8601 timestamp",
            file=sys.stderr,
        )
        sys.exit(2)


def _validate_baseline_v2(
    data: Dict, guard_name: str, report_schema_version: int
) -> None:
    """Validate the protocol-v2 baseline envelope (exits 2 on any violation).

    The envelope is closed: only the exact allowed keys are representable.
    Unknown envelope fields and missing required envelope fields are rejected
    with the controlled RATCHET_BASELINE_INVALID exit 2.  Missing key names are
    echoed only because they always come from the closed allowed set; unknown
    key names are never echoed (they may carry arbitrary content).
    """
    unknown = set(data) - _ALLOWED_ENVELOPE_KEYS
    if unknown:
        print(
            "RATCHET_BASELINE_INVALID: baseline envelope contains unknown "
            "field(s)",
            file=sys.stderr,
        )
        sys.exit(2)
    missing = sorted(_ALLOWED_ENVELOPE_KEYS - set(data))
    if missing:
        for key in missing:
            print(
                f"RATCHET_BASELINE_INVALID: baseline envelope is missing "
                f"required field '{key}'",
                file=sys.stderr,
            )
        sys.exit(2)
    if data.get("baseline_schema_version") != 2:
        print(
            "RATCHET_BASELINE_SCHEMA_MISMATCH: baseline_schema_version must be 2",
            file=sys.stderr,
        )
        sys.exit(2)
    if data.get("guard_output_schema_version") != report_schema_version:
        print(
            "RATCHET_BASELINE_SCHEMA_MISMATCH: guard_output_schema_version must "
            "match the report schema version",
            file=sys.stderr,
        )
        sys.exit(2)
    if data.get("fingerprint_schema_version") != FINGERPRINT_VERSION:
        print(
            "RATCHET_BASELINE_SCHEMA_MISMATCH: fingerprint_schema_version must "
            f"be {FINGERPRINT_VERSION}",
            file=sys.stderr,
        )
        sys.exit(2)
    if data.get("guard") != guard_name:
        # Fixed controlled diagnostic: the baseline guard value and the
        # requested guard name are never interpolated (either could carry
        # hostile content).
        print(
            "RATCHET_BASELINE_GUARD_MISMATCH: baseline guard does not match "
            "the requested guard",
            file=sys.stderr,
        )
        sys.exit(2)
    _validate_generated_at(data.get("generated_at"))
    entries = data.get("entries")
    if not isinstance(entries, list):
        print(
            "RATCHET_BASELINE_INVALID: baseline 'entries' must be a list",
            file=sys.stderr,
        )
        sys.exit(2)
    # Bounded size check before any per-entry work or set materialization: a
    # huge (or hostile) baseline must not be accepted.
    if len(entries) > MAX_BASELINE_ENTRIES:
        print(
            "RATCHET_BASELINE_TOO_LARGE: baseline exceeds the maximum number "
            "of entries",
            file=sys.stderr,
        )
        sys.exit(2)
    seen = set()
    for entry in entries:
        _validate_baseline_entry(entry)
        fingerprint = entry["fingerprint"]
        if fingerprint in seen:
            print(
                "RATCHET_BASELINE_INVALID: baseline contains duplicate "
                "fingerprint entries",
                file=sys.stderr,
            )
            sys.exit(2)
        seen.add(fingerprint)


def _collect_expired(entries: List[Dict]) -> List[str]:
    """Return sorted fingerprints of baseline entries whose expiry is past."""
    today = datetime.now(timezone.utc).date()
    expired = []
    for entry in entries:
        expires = entry.get("expires")
        if _expiry_date(expires) < today:
            expired.append(entry["fingerprint"])
    return sorted(expired)


def compare_counts_v2(
    baseline_entries: List[Dict], current_aggregates
) -> Dict[str, List[str]]:
    """Compare baseline counts to current aggregates by fingerprint.

    Returns sorted fingerprint lists under ``new_keys``, ``new_occurrences``
    (key present, current count > baseline count), ``resolved_keys``,
    ``resolved_occurrences`` (key present, current count < baseline count),
    and ``unchanged`` (key present, equal count).
    """
    baseline = {entry["fingerprint"]: entry["count"] for entry in baseline_entries}
    current = {agg.fingerprint: agg.count for agg in current_aggregates}
    baseline_set = set(baseline)
    current_set = set(current)
    return {
        "new_keys": sorted(current_set - baseline_set),
        "resolved_keys": sorted(baseline_set - current_set),
        "new_occurrences": sorted(
            fp for fp in current_set & baseline_set if current[fp] > baseline[fp]
        ),
        "resolved_occurrences": sorted(
            fp for fp in current_set & baseline_set if current[fp] < baseline[fp]
        ),
        "unchanged": sorted(
            fp for fp in current_set & baseline_set if current[fp] == baseline[fp]
        ),
    }


def _print_fingerprint_preview(label: str, fingerprints: List[str]) -> None:
    """Print one report section as a count plus a deterministic first-N preview.

    Only the first ``MAX_BASELINE_PREVIEW`` sorted fingerprints are printed;
    the remainder is summarized in one bounded line so a huge (or hostile)
    baseline can never turn the report into an unbounded fingerprint dump.
    """
    count = len(fingerprints)
    print(f"  {label}: {count}")
    for fp in fingerprints[:MAX_BASELINE_PREVIEW]:
        print(f"    {fp}")
    if count > MAX_BASELINE_PREVIEW:
        print(
            f"    ... and {count - MAX_BASELINE_PREVIEW} more ({count} total)"
        )


def print_report_v2(
    guard_name: str,
    baseline_entries: List[Dict],
    current_aggregates,
    comparison: Dict[str, List[str]],
    expired: List[str],
) -> str:
    """Print the protocol-v2 human-readable report.  Returns the status label."""
    baseline_count = len(baseline_entries)
    baseline_occurrences = sum(entry["count"] for entry in baseline_entries)
    current_count = len(current_aggregates)
    current_occurrences = sum(agg.count for agg in current_aggregates)

    print(f"Guard: {guard_name}")
    print(f"Protocol: v2")
    print(f"Baseline: {baseline_count} entries ({baseline_occurrences} occurrences)")
    print(f"Current:  {current_count} keys ({current_occurrences} occurrences)")
    # Each section prints a count and a deterministic bounded first-N preview,
    # never an unbounded list of fingerprints.
    _print_fingerprint_preview("NEW_KEYS", comparison["new_keys"])
    _print_fingerprint_preview("NEW_OCCURRENCES", comparison["new_occurrences"])
    _print_fingerprint_preview("RESOLVED_KEYS", comparison["resolved_keys"])
    _print_fingerprint_preview(
        "RESOLVED_OCCURRENCES", comparison["resolved_occurrences"]
    )
    _print_fingerprint_preview("UNCHANGED", comparison["unchanged"])
    _print_fingerprint_preview("EXPIRED_BASELINE_ENTRIES", expired)

    if comparison["new_keys"] or comparison["new_occurrences"]:
        status = "FAIL"
        msg = (
            f"{len(comparison['new_keys'])} new key(s), "
            f"{len(comparison['new_occurrences'])} new occurrence(s)"
        )
    elif comparison["resolved_keys"] or comparison["resolved_occurrences"]:
        status = "DECREASED"
        msg = (
            f"{len(comparison['resolved_keys'])} resolved key(s), "
            f"{len(comparison['resolved_occurrences'])} resolved occurrence(s)"
        )
    elif expired:
        status = "EXPIRED"
        msg = f"{len(expired)} expired baseline entr(y/ies)"
    else:
        status = "PASS"
        msg = "no new, resolved, or expired findings"

    print(f"Status: {status} -- {msg}")
    return status


def write_summary_json_v2(
    path: Path,
    guard_name: str,
    schema_version: int,
    baseline_entries: List[Dict],
    current_aggregates,
    comparison: Dict[str, List[str]],
    expired: List[str],
    exit_code: int,
) -> None:
    """Write the deterministic, bounded protocol-v2 summary JSON atomically.

    The summary records the guard identity, protocol/schema versions,
    baseline and current key/occurrence counts, every comparison category
    count (NEW_KEYS, NEW_OCCURRENCES, RESOLVED_KEYS, RESOLVED_OCCURRENCES,
    UNCHANGED, EXPIRED_BASELINE_ENTRIES), and the final exit code the process
    is about to exit with -- so a summary file always matches the process exit
    code and the reported categories.  Only counts are written (never raw
    fingerprint lists), keeping the summary bounded and deterministic.

    The write is atomic (temporary file in the target directory, then an
    os.replace) and sanitized: on any failure the function prints one bounded
    controlled diagnostic and exits 2 -- the summary path, OS error text, and
    exception messages are never echoed, and no partial summary is left at the
    target path.
    """
    baseline_occurrences = sum(entry["count"] for entry in baseline_entries)
    current_occurrences = sum(agg.count for agg in current_aggregates)
    payload = {
        "guard": guard_name,
        "protocol": 2,
        "schema": schema_version,
        "baseline": {
            "keys": len(baseline_entries),
            "occurrences": baseline_occurrences,
        },
        "current": {
            "keys": len(current_aggregates),
            "occurrences": current_occurrences,
        },
        "NEW_KEYS": len(comparison["new_keys"]),
        "NEW_OCCURRENCES": len(comparison["new_occurrences"]),
        "RESOLVED_KEYS": len(comparison["resolved_keys"]),
        "RESOLVED_OCCURRENCES": len(comparison["resolved_occurrences"]),
        "UNCHANGED": len(comparison["unchanged"]),
        "EXPIRED_BASELINE_ENTRIES": len(expired),
        "final_exit_code": exit_code,
    }
    tmp_path: Optional[str] = None
    fd: Optional[int] = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_path = tempfile.mkstemp(
            prefix="cost-aggregator-summary-",
            suffix=".json",
            dir=str(path.parent),
        )
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            fd = None  # os.fdopen now owns the descriptor
            json.dump(payload, f, indent=2, ensure_ascii=False, sort_keys=True)
            f.write("\n")
        os.replace(tmp_path, path)
    except Exception:
        # Controlled code only -- never echo the summary path, OS error text,
        # or the exception class/message.  BaseException subclasses (SystemExit,
        # KeyboardInterrupt, GeneratorExit) are intentionally NOT caught here
        # and propagate unchanged.  Any temporary file is cleaned up so a
        # partial summary never survives at the target path.
        if fd is not None:
            with suppress(OSError):
                os.close(fd)
        if tmp_path is not None:
            with suppress(OSError):
                os.unlink(tmp_path)
        print(
            "RATCHET_SUMMARY_WRITE_FAILED: could not write summary file",
            file=sys.stderr,
        )
        sys.exit(2)


class _ProposalResult:
    """Controlled outcome of a protocol-v2 candidate-baseline proposal attempt.

    ``status`` is a bounded controlled constant describing the proposal
    outcome (``blocked_growth``, ``blocked_expired``, or ``written``);
    ``exit_code`` is the exit code the proposal outcome maps to (growth
    blocks map to 1, expired-debt blocks fail closed with 2, a successful
    candidate write to 0);
    ``candidate_written`` tells the caller whether a candidate baseline was
    published at the proposal path.  The result lets the v2 flow report a
    proposal-blocking condition and write its summary without exiting early.
    """

    __slots__ = ("status", "exit_code", "candidate_written")

    def __init__(
        self, status: str, exit_code: int, candidate_written: bool
    ) -> None:
        self.status = status
        self.exit_code = exit_code
        self.candidate_written = candidate_written


def _write_v2_candidate(
    propose_path: Path,
    guard_name: str,
    report_schema_version: int,
    baseline_entries: List[Dict],
    current_aggregates,
    comparison: Dict[str, List[str]],
    expired: List[str],
) -> _ProposalResult:
    """Generate a protocol-v2 candidate baseline at ``propose_path``.

    The candidate reflects the current reviewed debt state and is written
    ONLY when the state is contract-permitting:

      * no new keys / new occurrences (growth blocks the candidate);
      * no expired baseline debt (expiry renewal requires review);
      * no unresolved classifications (report diagnostics already exit 2
        upstream and are never baseline-able).

    The active baseline is never modified: ``propose_path`` is enforced to
    differ from the active baseline path by the CLI layer, and the candidate
    is written to ``propose_path`` only.  Policy-blocking conditions (growth
    or expired debt) do NOT exit; they return a ``_ProposalResult`` with
    ``candidate_written=False`` so the caller can report the skipped status,
    write its summary, and exit with its own computed final code.
    Infrastructure failures (an oversized candidate, an unexpected aggregate
    state, or a write/publish error) exit 2 with the bounded
    ``PROPOSAL_ERROR`` diagnostic and write no candidate.

    The write itself is atomic: the candidate is serialized to a temporary
    file in the same directory as ``propose_path``, flushed and fsynced, then
    published with ``os.replace``.  On any write/publish failure the temporary
    file is removed and the function exits 2 with the bounded
    ``PROPOSAL_ERROR`` diagnostic -- a partial candidate is never left at the
    target path and the active baseline stays byte-identical.  Diagnostics
    are controlled: the candidate path, the temporary path, OS error text,
    and exception messages are never echoed.
    """
    if comparison["new_keys"] or comparison["new_occurrences"]:
        return _ProposalResult(
            status="blocked_growth", exit_code=1, candidate_written=False
        )
    if expired:
        return _ProposalResult(
            status="blocked_expired", exit_code=2, candidate_written=False
        )

    # Candidate generation enforces the same entry-count bound as the active
    # baseline loader: a report must never be materialized into a candidate
    # that exceeds the protocol limit.
    if len(current_aggregates) > MAX_BASELINE_ENTRIES:
        print(
            "PROPOSAL_ERROR: candidate generation failed (baseline would "
            "exceed the maximum number of entries)",
            file=sys.stderr,
        )
        sys.exit(2)

    # Every surviving current aggregate was already present in the validated
    # baseline (new keys are blocked above), so the reviewed metadata is
    # preserved from the active baseline and only the current counts differ.
    baseline_by_fp = {entry["fingerprint"]: entry for entry in baseline_entries}
    entries: List[Dict] = []
    for agg in current_aggregates:
        old = baseline_by_fp.get(agg.fingerprint)
        if old is None:
            # Unreachable given the growth block above; fail closed with a
            # controlled diagnostic rather than fabricate review metadata.
            print(
                "PROPOSAL_ERROR: candidate generation failed (unexpected "
                "aggregate state)",
                file=sys.stderr,
            )
            sys.exit(2)
        entries.append({
            "fingerprint": agg.fingerprint,
            "count": agg.count,
            "rule": agg.rule,
            "classification": "temporary_debt",
            "reason": old["reason"],
            "owner": old["owner"],
            "linked_issue": old["linked_issue"],
            "expires": old["expires"],
        })
    entries.sort(key=lambda entry: entry["fingerprint"])

    # The candidate must satisfy the same entry/fingerprint/metadata bounds as
    # the active baseline loader.  Re-validate every entry so a hostile
    # aggregate can never slip through the proposal path; violations exit 2
    # with a controlled diagnostic and write no candidate.
    for entry in entries:
        _validate_baseline_entry(entry)

    candidate = {
        "baseline_schema_version": 2,
        "guard_output_schema_version": report_schema_version,
        "fingerprint_schema_version": FINGERPRINT_VERSION,
        "guard": guard_name,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "entries": entries,
    }
    # Atomic publish: write the candidate to a temporary file in the SAME
    # directory as the target, flush and fsync it, then os.replace it into
    # place so a reader never observes a partial candidate.  On ANY failure
    # the temporary file is unlinked (nothing survives at the target path)
    # and the process exits 2 with the bounded controlled diagnostic.
    tmp_path: Optional[str] = None
    fd: Optional[int] = None
    try:
        propose_path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_path = tempfile.mkstemp(
            prefix="cost-aggregator-candidate-",
            suffix=".json",
            dir=str(propose_path.parent),
        )
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            fd = None  # os.fdopen now owns the descriptor
            json.dump(candidate, f, indent=2, ensure_ascii=False)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, propose_path)
    except Exception:
        # Controlled code only -- never echo the candidate path, the temporary
        # path, OS error text, or the exception class/message (any of them may
        # carry sensitive or hostile content).  BaseException subclasses
        # (SystemExit, KeyboardInterrupt, GeneratorExit) are intentionally NOT
        # caught here and propagate unchanged.  Cleanup is preserved on every
        # failure path so no partial candidate or temp artifact survives.
        if fd is not None:
            with suppress(OSError):
                os.close(fd)
        if tmp_path is not None:
            with suppress(OSError):
                os.unlink(tmp_path)
        print(
            "PROPOSAL_ERROR: candidate baseline could not be written",
            file=sys.stderr,
        )
        sys.exit(2)
    return _ProposalResult(
        status="written", exit_code=0, candidate_written=True
    )


def _main_v2(
    args,
    guard_command: Union[str, List[str]],
    project_root: Path,
    baseline_path: Path,
    propose_path: Optional[Path] = None,
) -> None:
    """Protocol-v2 ratchet flow: report transport via env, never stdout.

    When ``propose_path`` is given, a reviewed-debt-reduction CANDIDATE
    baseline is written there (never to the active baseline) if -- and only
    if -- the comparison state is contract-permitting: no new findings
    (growth), no unresolved classifications (report diagnostics already exit
    2 upstream), and no expired baseline debt.  A proposal-blocking condition
    returns a controlled ``_ProposalResult`` (it never exits early), so the
    run still writes its summary and exits with the single final code below.
    The final exit code is the same as without a proposal: any comparison
    delta exits 1 and expired baseline debt fails closed with 2, both
    regardless of --fail-on-violation; infrastructure/protocol failures
    exit 2.
    """
    guard_name = args.guard_name

    if args.update_baseline:
        print(
            "ERROR: baseline updates are not supported for finding protocol v2 "
            "(v2 baselines require a reviewed debt-reduction path)",
            file=sys.stderr,
        )
        sys.exit(2)

    guard_exit, report_path = run_guard_command_v2(
        guard_command, project_root, args.timeout
    )
    try:
        if guard_exit < 0:
            print(
                "Guard ratchet error: could not execute the guard command "
                "(timeout, missing executable, launch failure, or temporary "
                "report creation failure)",
                file=sys.stderr,
            )
            sys.exit(2)
        if guard_exit not in (0, 1):
            print(
                f"Guard exited with infrastructure/unknown code {guard_exit}",
                file=sys.stderr,
            )
            sys.exit(2)

        # Protocol v2: never parse stdout.  The report file is the only
        # transport, and only a fully validated GuardRunReport is accepted.
        try:
            report = load_report(report_path)
        except GuardFindingsError as exc:
            print(
                f"RATCHET_V2_REPORT_INVALID: guard={_sanitize_guard_name(guard_name)} "
                f"code={exc.code}",
                file=sys.stderr,
            )
            sys.exit(2)
        except Exception:
            print(
                f"RATCHET_V2_REPORT_INVALID: guard={_sanitize_guard_name(guard_name)} "
                f"code=LOAD_FAILED",
                file=sys.stderr,
            )
            sys.exit(2)

        if report.guard != guard_name:
            print(
                f"RATCHET_V2_GUARD_MISMATCH: guard={_sanitize_guard_name(guard_name)} "
                f"report guard does not match",
                file=sys.stderr,
            )
            sys.exit(2)
        # Advisory-aware diagnostics gate (GR-07 Option-B amendment, mirrored
        # from verify_db_access_boundaries._diagnostic_is_advisory): scanner
        # diagnostics flagged advisory in their bounded controlled context
        # (controlled_context["advisory"] is True) never break trust and never
        # block the run; every pre-scan/infrastructure diagnostic is unflagged
        # and therefore always blocking (fail closed).  Only BLOCKING
        # diagnostics take the infrastructure exit-2 path -- an advisory-only
        # trusted report (e.g. the GR-09 zero-findings evidence) compares
        # normally.
        blocking_diagnostics = tuple(
            item for item in report.diagnostics
            if item.controlled_context.get("advisory") is not True
        )
        if blocking_diagnostics:
            print(
                f"RATCHET_V2_REPORT_DIAGNOSTICS: "
                f"guard={_sanitize_guard_name(guard_name)} report contains "
                f"blocking infrastructure diagnostics",
                file=sys.stderr,
            )
            sys.exit(2)

        # Execution contract: child exit vs report state.
        if guard_exit == 0:
            if report.findings:
                print(
                    f"RATCHET_V2_REPORT_INCONSISTENT: "
                    f"guard={_sanitize_guard_name(guard_name)} exit=0 report has findings",
                    file=sys.stderr,
                )
                sys.exit(2)
        elif guard_exit == 1:
            if not report.findings:
                print(
                    f"RATCHET_V2_REPORT_INCONSISTENT: "
                    f"guard={_sanitize_guard_name(guard_name)} exit=1 report has no findings",
                    file=sys.stderr,
                )
                sys.exit(2)

        # Baseline envelope (validated; schema mismatch exits 2).
        # Explicit v1 baseline incompatibility guard: when the baseline is
        # a v1 envelope (no baseline_schema_version key) and the finding
        # protocol is 2, emit a controlled F2 migration-blocker diagnostic
        # instead of letting load_baseline_v2 fail with a generic envelope
        # validation error.  The active v1 baseline must not be silently
        # interpreted as a v2 baseline -- the fingerprint schemas are
        # incompatible (v1 text-derived vs v2 structured).
        try:
            if not baseline_path.exists():
                print(
                    "RATCHET_BASELINE_MISSING: baseline file not found",
                    file=sys.stderr,
                )
                sys.exit(2)
        except Exception:
            print(
                "RATCHET_BASELINE_PROBE_FAILED: baseline path could not be probed",
                file=sys.stderr,
            )
            sys.exit(2)
        try:
            with open(baseline_path, "r", encoding="utf-8") as _bf:
                _probe = json.load(_bf)
        except Exception:
            # Malformed/unreadable: let load_baseline_v2 report the
            # controlled diagnostic.
            _probe = None
        if isinstance(_probe, dict) and "baseline_schema_version" not in _probe:
            print(
                "RATCHET_V1_BASELINE_INCOMPATIBLE: active baseline is v1 "
                "(no baseline_schema_version) and cannot be used with "
                "finding protocol v2; migrate to a v2 baseline first",
                file=sys.stderr,
            )
            sys.exit(2)

        baseline_data = load_baseline_v2(
            baseline_path, guard_name, report.schema_version
        )
        if baseline_data is None:
            # Controlled code only -- never echo the baseline path.
            print(
                "RATCHET_BASELINE_MISSING: baseline file not found",
                file=sys.stderr,
            )
            sys.exit(2)

        entries = baseline_data["entries"]
        expired = _collect_expired(entries)
        try:
            current_aggregates = aggregate_findings(report.findings)
        except GuardFindingsError as exc:
            print(
                f"RATCHET_V2_AGGREGATE_ERROR: guard={_sanitize_guard_name(guard_name)} "
                f"code={exc.code}",
                file=sys.stderr,
            )
            sys.exit(2)
        comparison = compare_counts_v2(entries, current_aggregates)

        print_report_v2(guard_name, entries, current_aggregates, comparison, expired)

        # Outcome: expired debt fails closed (exit 2, GR-09 debt rule 9 --
        # an expired baseline is an invalid configuration state that must be
        # re-reviewed, never a normal policy signal); new/resolved comparison
        # deltas are policy signals (exit 1); anything else is exit 0.
        # Infrastructure/protocol failures exit 2.  The final exit is computed
        # BEFORE the proposal and the summary so a blocked proposal can still
        # record the exact code the process is about to exit with.
        if expired:
            final_exit = 2
        elif (
            comparison["new_keys"]
            or comparison["new_occurrences"]
            or comparison["resolved_keys"]
            or comparison["resolved_occurrences"]
        ):
            final_exit = 1
        else:
            final_exit = 0

        # -- Proposal (candidate output, protocol v2 only) --------------------
        # Policy-blocking conditions (growth / expired debt) return a
        # controlled _ProposalResult instead of exiting, so the run still
        # reaches the summary write and a single controlled final exit below.
        # Infrastructure failures inside the proposal (PROPOSAL_ERROR) still
        # exit 2 immediately and write no summary, matching every other
        # infrastructure failure path in this flow.
        proposal_result = None
        if propose_path is not None:
            proposal_result = _write_v2_candidate(
                propose_path,
                guard_name,
                report.schema_version,
                entries,
                current_aggregates,
                comparison,
                expired,
            )

        # Optional machine-readable summary (--output-summary).  The summary
        # records the final exit code the process is about to exit with, so a
        # summary file always matches the process outcome.  A failed summary
        # write is an infrastructure failure: the bounded controlled
        # diagnostic is printed and the process exits 2.
        summary_path = getattr(args, "output_summary", None)
        if summary_path is not None:
            write_summary_json_v2(
                Path(summary_path),
                guard_name,
                report.schema_version,
                entries,
                current_aggregates,
                comparison,
                expired,
                final_exit,
            )

        # -- Proposal status (after the summary, before the final exit) ------
        if proposal_result is not None:
            if proposal_result.candidate_written:
                # The candidate contains every current aggregate (new keys
                # are blocked upstream), so its entry count equals the
                # current aggregate count.
                print(
                    f"Proposal: candidate baseline written "
                    f"({len(current_aggregates)} entries) -- review before "
                    "promoting"
                )
            else:
                # Bounded controlled reason per blocked status; the exact
                # strings are stable for downstream consumers.
                _proposal_skipped_reason = {
                    "blocked_growth": "new findings present",
                    "blocked_expired": (
                        "expired baseline debt must be reviewed before "
                        "proposing"
                    ),
                }.get(proposal_result.status, "proposal blocked")
                print(
                    "PROPOSAL_SKIPPED: candidate not generated ("
                    + _proposal_skipped_reason
                    + ")",
                    file=sys.stderr,
                )

        sys.exit(final_exit)
    finally:
        if report_path is not None:
            with suppress(OSError):
                os.unlink(report_path)


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
        "--propose-baseline",
        type=Path,
        default=None,
        metavar="PATH",
        help="Protocol v2 only: write a reviewed-debt-reduction CANDIDATE "
             "baseline to PATH.  The active baseline is never modified.  "
             "Rejected in --ci-mode, together with --update-baseline, with "
             "finding protocol v1, and when PATH equals the active baseline "
             "path.  A candidate is generated only when there are no new "
             "findings, no unresolved classifications, and no expired "
             "baseline debt.",
    )
    parser.add_argument(
        "--ci-mode",
        action="store_true",
        help="CI mode: disables --update-baseline and enforces stricter policies.",
    )
    parser.add_argument(
        "--finding-protocol",
        type=int,
        default=None,
        help="Guard finding protocol version: 1 (legacy stdout fingerprints) "
             "or 2 (structured protocol-v2 JSON report via "
             "COST_AGGREGATOR_GUARD_FINDINGS_FILE).  When omitted, the guard "
             "registry entry for --guard-name is consulted; the legacy "
             "protocol 1 is used when the registry carries no "
             "finding_protocol metadata.",
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

    # -- Proposal restrictions ---------------------------------------------------
    if args.ci_mode and args.propose_baseline is not None:
        print("ERROR: Baseline proposal prohibited in CI mode", file=sys.stderr)
        sys.exit(2)
    if args.update_baseline and args.propose_baseline is not None:
        print(
            "ERROR: use either --update-baseline or --propose-baseline, "
            "not both",
            file=sys.stderr,
        )
        sys.exit(2)

    project_root = _find_project_root()
    baseline_path = Path(args.baseline)
    if not baseline_path.is_absolute():
        baseline_path = project_root / baseline_path

    # -- Protocol selection ------------------------------------------------------
    finding_protocol = _resolve_finding_protocol(
        args.finding_protocol, args.guard_name
    )
    if finding_protocol == 2:
        # Structured protocol-v2 path: report transport via env, never stdout.
        propose_path = None
        if args.propose_baseline is not None:
            propose_path = Path(args.propose_baseline)
            if not propose_path.is_absolute():
                propose_path = project_root / propose_path
            if propose_path.resolve() == baseline_path.resolve():
                print(
                    "ERROR: proposed baseline path must differ from the "
                    "active baseline path",
                    file=sys.stderr,
                )
                sys.exit(2)
        _main_v2(args, guard_command, project_root, baseline_path, propose_path)
        return

    # -- Legacy protocol v1 path -------------------------------------------------
    if args.propose_baseline is not None:
        print(
            "ERROR: --propose-baseline is only supported for finding "
            "protocol v2",
            file=sys.stderr,
        )
        sys.exit(2)

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
        # Controlled code only -- never interpolate the baseline path or any
        # raw error: the path (or a hostile filesystem's message) may carry
        # sensitive or hostile content.  Fail closed with the fixed controlled
        # code shared with the v2 flow.
        print(
            "RATCHET_BASELINE_MISSING: baseline file not found",
            file=sys.stderr,
        )
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
