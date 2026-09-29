#!/usr/bin/env python3
"""
verify_worker_boundaries.py
G-WORKER-01 — Worker Full Boundary Guard

Scans the declared production Kotlin source scope (the roots of the
checked-in manifest ``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py`` — currently
``app/src/main/java``), enumerating EVERY declared production file and then
applying the guard-specific worker-relevance semantic filter, for
background workers that violate architecture
boundaries established by Pipeline 9 (S8) WorkerExecutionGuard rules.

Detects:
  1. class *Worker extending CoroutineWorker that does NOT call
     runGuarded/runGuardedWithContext (WorkerExecutionGuard bypass).
  2. Workers directly calling DAO mutators (insert/update/delete) outside
     the scope of a repository or lifecycle coordinator.
  3. Workers with doWork() whose return path does NOT go through
     Result.success() or Result.failure().
  4. Workers catching broad exceptions (catch (e: Exception)) without
     proper diagnostic recording.

Exit codes:
  0 — no violations (or warning mode without --fail-on-violation)
  1 — violations found AND --fail-on-violation flag is set
  2 — script error (e.g. production source scope unresolved, source
      directory not found)

Usage:
  python3 scripts/verify_worker_boundaries.py
  python3 scripts/verify_worker_boundaries.py --fail-on-violation
"""

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Dict, Optional, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Configuration ──────────────────────────────────────────────────
RULE_ID = "G-WORKER-01"
DESCRIPTION = "Worker Full Boundary Guard"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)
from kotlin_callable_parser import (  # noqa: E402
    ParserError, find_owner_declarations, find_callable_declarations,
    mask_kotlin_source,
)
from guardrails.cloud_payload_proof import CloudProofError, executable_kotlin_source  # noqa: E402

ALLOWLIST_PATH = os.path.join(SCRIPT_DIR, "allowlists", "worker_allowlist.yml")

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# ── Known-safe patterns ───────────────────────────────────────────

# Workers that do not need the guard because they are non-DB helpers
# currently no production workers are exempt. See worker_allowlist.yml.

# ── Regex patterns ─────────────────────────────────────────────────

# CoroutineWorker supertype: `: CoroutineWorker` at class declaration
COROUTINE_WORKER_RE = re.compile(r""":\s*(?:androidx\.work\.)?CoroutineWorker\b""")

# Guard invocation: runGuarded( or runGuardedWithContext(
GUARD_INVOCATION_RE = re.compile(r"""runGuarded(WithContext)?\s*\(""")

# DAO mutation methods
DAO_MUTATION_RE = re.compile(
    r'\.(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|'
    r'set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|'
    r'bulkRename|approve|reject|disconnect|purge)\s*\('
)

# DAO variable reference patterns
DAO_VAR_RE = re.compile(r'\b\w+Dao\b')

# doWork() function
DO_WORK_RE = re.compile(r'override\s+suspend\s+fun\s+doWork\s*\(')

# Result.success() / Result.failure() calls
RESULT_SUCCESS_RE = re.compile(r'Result\s*\.\s*success\s*\(')
RESULT_FAILURE_RE = re.compile(r'Result\s*\.\s*failure\s*\(')
TO_WORKER_RESULT_RE = re.compile(r'toWorkerResult\s*\(')
GUARD_RESULT_TO_RESULT_RE = re.compile(r'guardResult\s*\.\s*toWorkerResult\s*\(')

# Broad exception catching patterns
CATCH_BROAD_RE = re.compile(r'catch\s*\(\s*(?:e\s*:\s*)?Exception\s*\)')
CATCH_THROWABLE_RE = re.compile(r'catch\s*\(\s*(?:e\s*:\s*)?Throwable\s*\)')

# Diagnostic recording patterns
DIAGNOSTIC_WRITE_RE = re.compile(
    r'(?:diagnosticEventWriter|diagnosticSink|safeRecordMatchEvent|'
    r'workerTerminalDiagnosticSink|recordBlockedOperation|'
    r'Timber\.(?:w|e|d|i)|Log\.(?:w|e|d|i))\s*\('
)

# Cancellation-safe rethrow
CANCELLATION_SAFE_RE = re.compile(
    r'(?:CancellationSafe\s*\.\s*rethrowIfCancellation|'
    r'if\s*\(\s*e\s+is\s+CancellationException\s*\)\s+throw\s+e)'
)

# Class name extraction
CLASS_NAME_RE = re.compile(r'class\s+(\w+Worker)\b')

# ── Violation data ──────────────────────────────────────────────────

@dataclass
class Violation:
    """Structured violation record used for allowlist-aware filtering."""
    rule_id: str
    rel_path: str
    lineno: int
    symbol: str
    reason: str
    line: str = ""

    def __str__(self) -> str:
        return f"{self.rule_id} {self.rel_path}:{self.lineno} {self.symbol} — {self.reason}"

    def __contains__(self, item: str) -> bool:
        """Support ``'substring' in violation`` for test assertions."""
        return item in str(self)


def violation(rel_path: str, lineno: int, symbol: str, reason: str, line: str = "") -> Violation:
    """Create a structured violation record."""
    return Violation(
        rule_id=RULE_ID,
        rel_path=rel_path,
        lineno=lineno,
        symbol=symbol,
        reason=reason,
        line=line,
    )


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


def matches_allowlist(violation: Violation, allowlist: List[dict]) -> bool:
    """Check whether a specific violation is covered by an allowlist entry.

    An allowlist entry matches when rule_id, file path, and symbol all
    match exactly.  Unlike the previous whole-file skip, this means other
    violations in the same file are still detected.
    """
    norm_path = violation.rel_path.replace("\\", "/")
    for entry in allowlist:
        if entry.get("rule") != violation.rule_id:
            continue
        entry_path = (entry.get("path") or "").replace("\\", "/")
        if entry_path != norm_path:
            continue
        entry_symbol = entry.get("symbol") or ""
        if entry_symbol != violation.symbol:
            continue
        return True
    return False


# ── Violation detection ────────────────────────────────────────────


class WorkerSourceError(RuntimeError):
    """An unreadable declared source must never disappear from coverage."""


def _brace_depth(text: str, offset: int) -> int:
    prefix = text[:offset]
    return prefix.count("{") - prefix.count("}")


def _closing(text: str, start: int) -> int:
    pairs = {"(": ")", "{": "}"}
    opening = text[start:start + 1]
    if opening not in pairs:
        return -1
    depth = 0
    for i in range(start, len(text)):
        if text[i] == opening:
            depth += 1
        elif text[i] == pairs[opening]:
            depth -= 1
            if depth == 0:
                return i
    return -1


def _direct_guard_expression(expression: str, receivers: set[str]) -> bool:
    expression = expression.strip().rstrip(";").strip()
    call = re.match(r"(?:this\.)?(\w+)\.runGuarded(?:WithContext)?\s*\(", expression)
    if not call or call.group(1) not in receivers:
        return False
    end_args = _closing(expression, call.end() - 1)
    if end_args < 0:
        return False
    tail = expression[end_args + 1:].strip()
    return tail.startswith("{") and _closing(tail, 0) == len(tail) - 1


def _returned_guard_bridge(expression: str, result: str, receivers: set[str], methods, source: str) -> bool:
    """Recognize direct bridges and a returned if with guarded branch values.

    A branch helper is proved from its body, never authorized by its name.
    This includes the existing privacy-cleanup worker path without exempting it.
    """
    expression = expression.strip().rstrip(";").strip()
    if re.fullmatch(re.escape(result) + r"\s*\.\s*toWorkerResult\s*\(\s*\)", expression):
        return True
    helper = re.fullmatch(r"(\w+)\([^{};]*\)\s*\.\s*toWorkerResult\s*\(\s*\)", expression)
    if helper:
        candidates = [m for m in methods if m.signature.function_name == helper.group(1)]
        if len(candidates) != 1 or not candidates[0].body or not candidates[0].body.startswith("{"):
            return False
        candidate = candidates[0]
        body_start = source.find(candidate.body, candidate.start_offset, candidate.end_offset)
        if body_start < 0:
            return False
        header = mask_kotlin_source(source[candidate.start_offset:body_start])
        if any(re.search(r"\b" + re.escape(receiver) + r"\s*:", header) for receiver in receivers):
            return False
        body = mask_kotlin_source(candidates[0].body)[1:-1].strip()
        return body.startswith("return ") and _direct_guard_expression(body[7:], receivers)
    condition = re.match(r"if\s*\(", expression)
    if not condition:
        return False
    end_condition = _closing(expression, condition.end() - 1)
    if end_condition < 0:
        return False
    branches = expression[end_condition + 1:].strip()
    if not branches.startswith("{"):
        return False
    end_first = _closing(branches, 0)
    if end_first < 0:
        return False
    rest = branches[end_first + 1:].strip()
    if not rest.startswith("else"):
        return False
    second = rest[4:].strip()
    if not second.startswith("{") or _closing(second, 0) != len(second) - 1:
        return False
    return all(_returned_guard_bridge(branch, result, receivers, methods, source)
               for branch in (branches[1:end_first], second[1:-1]))


def _guarded_entry_points(content: str):
    """Bounded proof, not a Kotlin CFG: direct typed guard calls in doWork.

    Comments/literals, sibling methods, nested local functions and stored
    lambdas cannot prove execution. Unsupported entry-point shapes fail closed.
    Return-bridge checks below are restricted to these same entry-point bodies.
    """
    masked = mask_kotlin_source(content)
    for owner in find_owner_declarations(content):
        header = masked[owner.start_offset:owner.body_start]
        if not COROUTINE_WORKER_RE.search(header):
            continue
        receivers = set(re.findall(
            r"\bval\s+(\w+)\s*:\s*(?:[\w]+\.)*WorkerExecutionGuard\b", header
        ))
        declarations = find_callable_declarations(
            content, owner, tolerate_unresolved_types=True
        )
        methods = [d for d in declarations if d.signature.function_name == "doWork"]
        bodies = []
        guarded = False
        if len(methods) == 1:
            method = methods[0]
            if method.status == "RESOLVED_EXACTLY" and method.body and method.body.startswith("{"):
                body = mask_kotlin_source(method.body)[1:-1]
                bodies.append(body)
                for match in re.finditer(
                    r"(?m)^\s*val\s+(\w+)\s*=\s*(?:this\.)?(\w+)"
                    r"\s*\.\s*runGuarded(?:WithContext)?\s*\(", body
                ):
                    # Only an actual top-level call counts, never a call inside
                    # an unused local declaration or conditional branch.
                    if match.group(2) not in receivers or _brace_depth(body, match.start()) != 0:
                        continue
                    prefix = body[:match.start()]
                    if re.search(r"\b(?:val|var)\s+" + re.escape(match.group(2)) + r"\b", prefix):
                        continue
                    if any(_brace_depth(prefix, r.start()) == 0
                           for r in re.finditer(r"\b(?:return|throw)\b", prefix)):
                        continue
                    # Existing input validation may return failure before the
                    # guard, but an early success/retry cannot bypass it.
                    if any(not re.match(r"return\s+Result\s*\.\s*failure\s*\(\s*\)", prefix[r.start():])
                           for r in re.finditer(r"\breturn\b", prefix)):
                        continue
                    returns = [r for r in re.finditer(r"\breturn\s+", body)
                               if r.start() > match.end() and _brace_depth(body, r.start()) == 0]
                    guarded = len(returns) == 1 and _returned_guard_bridge(
                        body[returns[0].end():], match.group(1), receivers, declarations, content
                    )
                    if guarded:
                        break
        yield owner.name, content.count("\n", 0, owner.start_offset) + 1, guarded, "\n".join(bodies)


def scan_file(filepath: str, rel_path: str) -> List[Violation]:
    """Scan a single file for G-WORKER-01 violations."""
    violations: List[Violation] = []

    try:
        with open(filepath, encoding="utf-8") as f:
            content = f.read()
    except (OSError, UnicodeError):
        raise WorkerSourceError("WORKER_SOURCE_UNREADABLE") from None

    raw_content = content
    content = executable_kotlin_source(content)
    lines = content.splitlines()
    filename = os.path.basename(filepath)

    # Must be a CoroutineWorker file
    if not COROUTINE_WORKER_RE.search(content):
        return violations

    # Extract class name
    class_name_match = CLASS_NAME_RE.search(content)
    class_name = class_name_match.group(1) if class_name_match else "UnknownWorker"

    # ── Check 1: WorkerExecutionGuard usage ────────────────────────
    entry_points = list(_guarded_entry_points(raw_content))
    uses_guard = bool(entry_points) and all(item[2] for item in entry_points)
    entry_content = "\n".join(item[3] for item in entry_points)

    if not uses_guard:
        for name, lineno, guarded, _body in entry_points or [(class_name, 1, False, "")]:
            if not guarded:
                violations.append(violation(
                    rel_path, lineno, f"{name}.noguard",
                    f"CoroutineWorker '{name}' has no proved WorkerExecutionGuard doWork path "
                    "with a direct typed runGuarded/runGuardedWithContext call and returned bridge",
                ))
        # If no guard at all, remaining checks still apply (must not mutate
        # Daos directly and must return proper Results)

    # ── Check 2: Direct DAO mutations ──────────────────────────────
    # Only check if the file references Daos AND has mutations
    has_dao_ref = bool(DAO_VAR_RE.search(content))
    has_dao_mutation = bool(DAO_MUTATION_RE.search(content))

    if has_dao_ref and has_dao_mutation:
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue
            # Check if this line has BOTH a Dao reference and a mutation
            if DAO_VAR_RE.search(line) and DAO_MUTATION_RE.search(line):
                violations.append(
                    violation(rel_path, i,
                              f"{class_name}.daoMutation",
                              f"Worker '{class_name}' directly calls DAO mutator — "
                              "route writes through repository/lifecycle coordinator",
                              stripped[:120])
                )

    # ── Check 3: doWork() return path ──────────────────────────────
    if not uses_guard:
        # Workers without the guard must handle return path themselves
        has_do_work = bool(DO_WORK_RE.search(content))
        if has_do_work:
            has_success = bool(RESULT_SUCCESS_RE.search(entry_content))
            has_failure = bool(RESULT_FAILURE_RE.search(entry_content))
            has_to_worker = bool(TO_WORKER_RESULT_RE.search(entry_content))

            if not has_success and not has_failure and not has_to_worker:
                # Find the doWork line
                for i, line in enumerate(lines, 1):
                    if DO_WORK_RE.search(line):
                        violations.append(
                            violation(rel_path, i,
                                      f"{class_name}.badReturn",
                                      f"doWork() in '{class_name}' has no Result.success() / "
                                      "Result.failure() / toWorkerResult() return path — "
                                      "all code paths must produce a ListenableWorker.Result",
                                      line.strip()[:120])
                        )
                        break
    else:
        # Workers WITH the guard should use toWorkerResult()
        has_to_worker = bool(TO_WORKER_RESULT_RE.search(entry_content))
        has_guard_result = bool(GUARD_RESULT_TO_RESULT_RE.search(entry_content))

        if not has_to_worker and not has_guard_result:
            # Find the doWork return path
            for i, line in enumerate(lines, 1):
                if DO_WORK_RE.search(line):
                    violations.append(
                        violation(rel_path, i,
                                  f"{class_name}.badReturn",
                                  f"doWork() in guarded worker '{class_name}' does not call "
                                  "toWorkerResult() — use guardResult.toWorkerResult() or "
                                  "result.toWorkerResult() to bridge the guard",
                                  line.strip()[:120])
                    )
                    break

    # ── Check 4: Broad exception catching without diagnostics ──────
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue

        if CATCH_BROAD_RE.search(line) or CATCH_THROWABLE_RE.search(line):
            # This worker catches broad exceptions
            # Check the surrounding block for diagnostic recording
            catch_block = extract_catch_block(lines, i - 1)
            has_cancellation_check = bool(CANCELLATION_SAFE_RE.search(catch_block))
            has_diagnostic = bool(DIAGNOSTIC_WRITE_RE.search(catch_block))

            # Not a violation if it properly rethrows cancellation AND has diagnostics
            if not has_cancellation_check and not has_diagnostic:
                violations.append(
                    violation(rel_path, i,
                              f"{class_name}.broadCatch",
                              f"Worker '{class_name}' catches broad Exception/Throwable "
                              "without CancellationSafe.check AND without diagnostic recording — "
                              "add CancellationSafe.rethrowIfCancellation(e) + structured diagnostic",
                              stripped[:120])
                )
            elif not has_cancellation_check:
                violations.append(
                    violation(rel_path, i,
                              f"{class_name}.broadCatch",
                              f"Worker '{class_name}' catches broad Exception/Throwable "
                              "without CancellationSafe check — cancellation must be rethrown",
                              stripped[:120])
                )

            # Stop checking after first broad catch per block to avoid duplicate
            # violations when both catch (e: Exception) and catch (e: Throwable) appear

    return violations


def extract_catch_block(lines: List[str], catch_line_idx: int, lookahead: int = 30) -> str:
    """Extract the catch block body starting from the catch line.

    Handles the Kotlin pattern `} catch (e: Exception) {` where the closing
    brace of the preceding block and the opening brace of the catch appear
    on the same line. The first `{` after the `catch` keyword is treated as
    the block opener.
    """
    body_lines = []
    depth = 0
    started = False
    for i in range(catch_line_idx, min(catch_line_idx + lookahead, len(lines))):
        line = lines[i]
        # Normalise braces: count unmatched '{' and '}'
        for ch in line:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                if depth > 0:
                    depth -= 1
                # else: closing brace of an outer block — ignore
        body_lines.append(line)
        if started and depth == 0:
            break
    return "\n".join(body_lines)


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
    # roots are enumerated; worker relevance is a semantic filter applied
    # per file, not a root filter.
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

    all_violations: List[Violation] = []
    worker_files_scanned = 0

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

        worker_files_scanned += 1
        try:
            file_violations = scan_file(filepath, rel_path)
        except (WorkerSourceError, ParserError, CloudProofError):
            print("ERROR: WORKER_SOURCE_UNREADABLE_OR_UNPARSEABLE", file=sys.stderr)
            sys.exit(2)
        all_violations.extend(file_violations)

    # Filter violations through the allowlist (per-symbol, not whole-file)
    allowed_count = 0
    filtered_violations: List[Violation] = []
    for v in all_violations:
        if matches_allowlist(v, allowlist):
            allowed_count += 1
        else:
            filtered_violations.append(v)

    # Count actual worker files detected
    # We already scan all .kt files but we need to count how many were workers
    actual_workers = sum(
        1 for v in all_violations if "CoroutineWorker" in v.reason
    ) if all_violations else 0

    print(f"Scanned production source tree for G-WORKER-01 violations.")

    if filtered_violations:
        for v in filtered_violations:
            print(str(v))
        print(f"\nFound {len(filtered_violations)} violation(s) ({allowed_count} suppressed by allowlist).")

        if args.fail_on_violation:
            print("FAIL: G-WORKER-01 violations (--fail-on-violation set)", file=sys.stderr)
            sys.exit(1)
        else:
            print("WARNING: G-WORKER-01 violations (pass --fail-on-violation to fail CI)",
                  file=sys.stderr)
            sys.exit(0)
    else:
        print(f"PASS: {RULE_ID} — all workers comply with boundary requirements")
        sys.exit(0)


if __name__ == "__main__":
    main()
