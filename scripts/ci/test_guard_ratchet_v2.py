#!/usr/bin/env python3
"""
test_guard_ratchet_v2.py

Pytest tests for the protocol-v2 consumption path of the guard ratchet
(scripts/ci/guard_ratchet.py --finding-protocol 2).

The suite drives the real ratchet as a subprocess against mock guard scripts
that honor COST_AGGREGATOR_GUARD_FINDINGS_FILE and
COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=2, and verifies:

  1. Execution-contract matrix (child exit x report state -> ratchet exit):
       exit 0 + empty valid report             -> exit 0 (pass)
       exit 0 + findings present               -> exit 2 (inconsistency)
       exit 0 + missing report                 -> exit 2 (invalid report)
       exit 1 + valid findings                 -> compare (exit 0 or 1)
       exit 1 + missing / malformed / empty report -> exit 2
       exit 2 / unexpected exit                -> exit 2
       schema/guard mismatch / invalid report  -> exit 2
       report with diagnostics                 -> exit 2
  2. Baseline envelope validation:
       baseline_schema_version / guard_output_schema_version /
       fingerprint_schema_version must equal 2  -> exit 2 on mismatch
       guard must match                         -> exit 2
       generated_at must be a non-empty strict ISO-8601 timestamp with an
       explicit timezone (missing, non-string, malformed, timezone-less,
       and noncanonical values)                 -> exit 2
         entries must be a list, unique, positive counts,
         classification == temporary_debt,
         owner/reason/linked_issue/expires present -> exit 2 on violation
         the expiry field is canonical: exactly 'expires'; the legacy
         'expiry' alias (alone or alongside 'expires') is rejected as
         unknown/invalid schema                   -> exit 2
         envelope and entry schemas are closed: unknown top-level or entry
         fields (extra metadata, diagnostics, ...) and missing required
         keys are rejected as RATCHET_BASELINE_INVALID -> exit 2, and the
         active baseline file is never rewritten
         expires must be exact canonical YYYY-MM-DD (unpadded dates, ISO
         datetimes, and surrounding whitespace)   -> exit 2
         expired entries                          -> exit 1
  3. Count-aware comparison by fingerprint:
       NEW_KEYS / NEW_OCCURRENCES / RESOLVED_KEYS / RESOLVED_OCCURRENCES /
       UNCHANGED
  4. stdout is never parsed for v2 (the report is the only transport).
   5. The temporary report file is retained for the child (never unlinked
      before child execution -- no symlink-replacement TOCTOU window) and is
      always cleaned up by the caller afterward (success and error); a child
      that leaves the file empty yields an invalid-report exit 2.
  6. A temporary report path that cannot be created (mkstemp failure) is a
     controlled infrastructure error: the ratchet exits 2 and never leaks a
     traceback, the raw temp path, or the exception message (exercised
     in-process with a monkeypatched mkstemp).
  7. Protocol defaults stay on legacy v1 and invalid --finding-protocol
     values are rejected.
   8. Baseline load diagnostics are sanitized: missing / unreadable / UTF-8 /
      JSON / non-object baseline failures use controlled codes with no raw
      baseline path, OS error, exception text, key, or value (hostile-path
      and hostile-exception tests assert no leak).  The same no-leak rule
      holds for the legacy v1 missing-baseline report, which uses the fixed
      controlled RATCHET_BASELINE_MISSING code (hostile-path regression test).
   9. --propose-baseline (protocol v2 only, candidate output):
         rejected in --ci-mode (exit 2)
         rejected for finding protocol v1 (exit 2)
         rejected when the candidate path equals the active baseline path
         candidate generation never modifies the active baseline
         a candidate is written only when the state is contract-permitting
         (no new findings, no unresolved classifications, no expired debt);
         growth / unresolved / expired block the candidate (non-zero, no file)
  10. v2 exit codes are independent of --fail-on-violation: comparison
      deltas (new/resolved) and expired debt exit 1 whether or not the flag
      is present; infrastructure/protocol failures stay exit 2.
  11. Proposal candidate writes are atomic and sanitized: the candidate is
      written via a same-directory temp file, flushed/fsynced, and published
      with os.replace.  A failed write or replace exits 2 with the bounded
      PROPOSAL_ERROR, leaves no partial candidate or temp artifact, never
      touches the active baseline, and leaks no path or exception text
      (monkeypatched os.replace / json.dump failure tests).
   12. --propose-baseline combined with --output-summary: a proposal run
       (growth-blocked, expired-debt, or successful debt-reduction) still
       writes the fresh summary -- the final_exit_code equals the process
       exit code, the comparison categories (NEW_KEYS / NEW_OCCURRENCES /
       RESOLVED_* / UNCHANGED / EXPIRED_BASELINE_ENTRIES) are populated, and
       a stale summary at the target path is overwritten.  A report carrying
       diagnostics exits 2 BEFORE the summary write step, so no fresh summary
       is created and a pre-existing stale summary is never reused or
       refreshed.

Run:
    python -m pytest scripts/ci/test_guard_ratchet_v2.py -v
"""

import json
import os
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Dict, List, Optional

# Make this directory importable so sibling modules can be imported
# regardless of how pytest runs.
_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import pytest  # noqa: E402

from finding_rule_catalog import known_rule  # noqa: E402
from guard_findings import (  # noqa: E402
    CallableSymbol,
    GuardFinding,
    KIND_FUNCTION,
    SEVERITY_ERROR,
    SourceLocation,
)


# -- Helpers ----------------------------------------------------------------------

RATCHET_SCRIPT = Path(__file__).resolve().parent / "guard_ratchet.py"
_GUARD = "db_access"
_RULE = "DB_UNAUTHORIZED_MUTATION"
_PATH = "app/src/main/java/com/example/Worker.kt"

_DEFAULT_IDENTITY = {
    "dao": "AppDao",
    "accessor": "direct",
    "operation": "delete",
    "mutation_kind": "update",
    "call_form": "interface",
}


def _identity_for(rule: str, **overrides) -> Dict[str, str]:
    """Build an identity mapping from the catalog-declared ``identity.*`` fields."""
    profile = known_rule(rule)
    assert profile is not None, f"rule {rule} must be registered"
    declared = {f[9:] for f in profile.identity_fields if f.startswith("identity.")}
    identity = {key: _DEFAULT_IDENTITY[key] for key in sorted(declared)}
    identity.update(overrides)
    return identity


def _finding_dict(
    *,
    rule: str = _RULE,
    path: str = _PATH,
    owner: str = "com.example.Worker",
    name: str = "doWork",
    line: int = 42,
    column: int = 7,
    operation: Optional[str] = None,
    dao: Optional[str] = None,
) -> Dict:
    """Build a valid protocol-v2 finding dict for the mock child report."""
    identity = _identity_for(rule)
    if operation is not None:
        identity = dict(identity)
        identity["operation"] = operation
    if dao is not None:
        identity = dict(identity)
        identity["dao"] = dao
    return {
        "rule": rule,
        "severity": SEVERITY_ERROR,
        "path": path,
        "location": {"line": line, "column": column},
        "symbol": {
            "owner": owner,
            "name": name,
            "receiver": None,
            "parameters": ["String", "long"],
            "kind": KIND_FUNCTION,
        },
        "identity": identity,
        "message": "Mutation is not owned by an exact DB policy entry",
    }


def _fingerprint_of(finding_dict: Dict) -> str:
    """Return the protocol-v2 fingerprint of a finding dict (validates it too)."""
    return GuardFinding.from_dict(finding_dict).fingerprint


def _report_dict(
    findings: Optional[List[Dict]] = None,
    *,
    guard: str = _GUARD,
    schema_version: int = 2,
    diagnostics: Optional[List[Dict]] = None,
    statistics: Optional[Dict] = None,
) -> Dict:
    """Build a protocol-v2 report envelope dict."""
    return {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": schema_version,
        "guard": guard,
        "findings": findings if findings is not None else [],
        "diagnostics": diagnostics if diagnostics is not None else [],
        "statistics": statistics if statistics is not None else {},
    }


def _entry(
    fingerprint: str,
    *,
    count: int = 1,
    rule: str = _RULE,
    expires: str = "2099-12-31",
    **overrides,
) -> Dict:
    """Build one protocol-v2 baseline entry (temporary debt)."""
    entry = {
        "fingerprint": fingerprint,
        "count": count,
        "rule": rule,
        "classification": "temporary_debt",
        "reason": "Existing debt awaiting lifecycle migration",
        "owner": "@test-owner",
        "linked_issue": "MIT-000",
        "expires": expires,
    }
    entry.update(overrides)
    return entry


def _write_baseline_v2(
    path: Path,
    guard_name: str,
    entries: List[Dict],
    *,
    drop_keys: Optional[List[str]] = None,
    **overrides,
) -> None:
    """Write a protocol-v2 baseline envelope (defaults are schema-valid).

    ``drop_keys`` removes envelope keys (e.g. ``["generated_at"]``) before
    ``overrides`` are applied, so tests can exercise missing-field handling.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    baseline = {
        "baseline_schema_version": 2,
        "guard_output_schema_version": 2,
        "fingerprint_schema_version": 2,
        "guard": guard_name,
        "generated_at": "2026-08-10T00:00:00+00:00",
        "entries": entries,
    }
    if drop_keys:
        for key in drop_keys:
            baseline.pop(key, None)
    baseline.update(overrides)
    path.write_text(json.dumps(baseline, indent=2) + "\n", encoding="utf-8")


def _write_mock_guard(
    path: Path,
    report,
    exit_code: int,
    *,
    stdout: str = "",
    marker_path: Optional[Path] = None,
    require_existing_report: bool = False,
) -> None:
    """Write a mock protocol-v2 guard script.

    ``report`` may be a dict (serialized), a str (written verbatim), or None
    (the report file is never written, so the retained report file stays
    empty).  The script asserts the child environment carries
    COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA=2 and optionally records the report
    path in ``marker_path`` so tests can verify temp cleanup.

    When ``require_existing_report`` is set, the script additionally fails
    (exit 9 with a controlled stderr marker) if the target report path does
    not already exist or is not writable before it writes -- a regression
    guard for the report-path TOCTOU: the ratchet must retain the securely
    created file (never unlink it before child execution) so the child always
    sees a writable existing report path.
    """
    report_text = None
    if report is not None:
        report_text = report if isinstance(report, str) else json.dumps(report)

    lines = [
        "#!/usr/bin/env python3",
        "import os",
        "import sys",
        'target = os.environ.get("COST_AGGREGATOR_GUARD_FINDINGS_FILE")',
        'schema = os.environ.get("COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA")',
        'if schema != "2":',
        '    print("SCHEMA_ENV_MISMATCH", file=sys.stderr)',
        "    sys.exit(9)",
        "if not target:",
        '    print("NO_REPORT_PATH", file=sys.stderr)',
        "    sys.exit(9)",
    ]
    if require_existing_report:
        # The retained mkstemp file must already exist and be writable when
        # the child starts; a pre-execution unlink would fail these probes.
        lines.append("if not os.path.isfile(target):")
        lines.append('    print("REPORT_TARGET_MISSING", file=sys.stderr)')
        lines.append("    sys.exit(9)")
        lines.append("try:")
        lines.append("    with open(target, 'a', encoding='utf-8'):")
        lines.append("        pass")
        lines.append("except OSError:")
        lines.append('    print("REPORT_TARGET_NOT_WRITABLE", file=sys.stderr)')
        lines.append("    sys.exit(9)")
    if report_text is not None:
        lines.append("with open(target, 'w', encoding='utf-8') as f:")
        lines.append(f"    f.write({report_text!r})")
    if marker_path is not None:
        lines.append(f"with open({str(marker_path)!r}, 'w', encoding='utf-8') as f:")
        lines.append("    f.write(target)")
    if stdout:
        lines.append(f"print({stdout!r})")
    lines.append(f"sys.exit({exit_code})")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _write_legacy_mock_guard(path: Path, stdout: str, exit_code: int = 1) -> None:
    """Write a legacy protocol-v1 guard script (stdout fingerprints only).

    Unlike ``_write_mock_guard``, this guard does NOT require the protocol-v2
    child environment: on the legacy path the ratchet never sets
    COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA, so a v2-env-checking mock would
    exit 9 before emitting its stdout.
    """
    path.write_text(
        "\n".join([
            "#!/usr/bin/env python3",
            "import sys",
            f"print({stdout!r})",
            f"sys.exit({exit_code})",
        ]) + "\n",
        encoding="utf-8",
    )


def _run_ratchet(
    guard_name: str,
    command_args: List[str],
    baseline: Path,
    *,
    protocol: Optional[int] = None,
    extra_args: Optional[List[str]] = None,
    cwd: Optional[Path] = None,
) -> subprocess.CompletedProcess:
    """Run the ratchet and return the CompletedProcess."""
    cmd = [
        sys.executable,
        str(RATCHET_SCRIPT),
        "--guard-name", guard_name,
        "--baseline", str(baseline),
    ]
    if protocol is not None:
        cmd.append(f"--finding-protocol={protocol}")
    for arg in command_args:
        cmd.append(f"--command-arg={arg}")
    if extra_args:
        cmd.extend(extra_args)
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
        cwd=str(cwd) if cwd is not None else None,
    )


def _guard_py(tmp_path: Path) -> Path:
    return tmp_path / "mock_guard.py"


def _baseline(tmp_path: Path) -> Path:
    return tmp_path / "baseline.json"


# -- Execution contract matrix -----------------------------------------------------


def test_v2_exit0_empty_valid_report_passes(tmp_path: Path) -> None:
    """exit 0 + empty valid report + empty baseline -> exit 0 (pass)."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(), exit_code=0)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "Protocol: v2" in result.stdout
    assert "PASS" in result.stdout
    assert "NEW_KEYS: 0" in result.stdout


def test_v2_exit0_report_with_findings_exits_two(tmp_path: Path) -> None:
    """exit 0 + findings present -> exit 2 (child/report inconsistency)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=0)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INCONSISTENT" in result.stderr


def test_v2_exit0_missing_report_exits_two(tmp_path: Path) -> None:
    """exit 0 with the retained report file left empty -> exit 2 (fail closed;
    stdout is never parsed).  The report file is created by the ratchet (never
    pre-unlinked) and the child does not write it, so the loader sees an empty
    invalid report."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, None, exit_code=0, stdout="G-NOPE app/src/x.kt:1 fake")
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr


def test_v2_exit1_valid_findings_new_key_exits_one(tmp_path: Path) -> None:
    """exit 1 + valid findings with a new key -> compare -> exit 1."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    other = _finding_dict(name="otherMethod")
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(other))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "NEW_KEYS: 1" in result.stdout
    assert "RESOLVED_KEYS: 1" in result.stdout
    assert "FAIL" in result.stdout


def test_v2_exit1_valid_findings_unchanged_exits_zero(tmp_path: Path) -> None:
    """exit 1 + valid findings matching baseline -> exit 0 (unchanged)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "UNCHANGED: 1" in result.stdout
    assert "PASS" in result.stdout


def test_v2_exit1_missing_report_exits_two(tmp_path: Path) -> None:
    """exit 1 with the retained report file left empty -> exit 2 (an empty
    report is an invalid-report infra error)."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, None, exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr


def test_v2_exit1_malformed_report_exits_two(tmp_path: Path) -> None:
    """exit 1 + malformed report JSON -> exit 2 (never falls back to stdout)."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, "{not valid json", exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr


def test_v2_exit1_empty_findings_exits_two(tmp_path: Path) -> None:
    """exit 1 + report with zero findings -> exit 2 (child/report inconsistency)."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INCONSISTENT" in result.stderr


def test_v2_child_exit_two_exits_two(tmp_path: Path) -> None:
    """child exit 2 (infrastructure error) -> ratchet exit 2."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(), exit_code=2)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )


def test_v2_child_unknown_exit_exits_two(tmp_path: Path) -> None:
    """unexpected child exit code -> ratchet exit 2."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(), exit_code=7)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "unknown code 7" in result.stderr


def test_v2_report_schema_version_mismatch_exits_two(tmp_path: Path) -> None:
    """report schema_version != 2 -> exit 2 (invalid report)."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py, _report_dict(schema_version=1), exit_code=1
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr


def test_v2_report_unknown_guard_exits_two(tmp_path: Path) -> None:
    """report claims an unregistered guard -> exit 2 (fail closed)."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py, _report_dict(guard="not_a_registered_guard"), exit_code=1
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr


def test_v2_report_guard_mismatch_exits_two(tmp_path: Path) -> None:
    """report guard differs from the requested guard name -> exit 2."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(guard=_GUARD), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        "cancellation", [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_GUARD_MISMATCH" in result.stderr


def test_v2_report_diagnostics_exits_two(tmp_path: Path) -> None:
    """report carries an infrastructure diagnostic -> exit 2 (never baseline-able)."""
    diagnostic = {
        "code": "DB_SOURCE_UNREADABLE",
        "path": None,
        "symbol": None,
        "controlled_context": {},
    }
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[], diagnostics=[diagnostic]),
        exit_code=1,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_DIAGNOSTICS" in result.stderr


def test_v2_report_unknown_rule_exits_two(tmp_path: Path) -> None:
    """report contains an unregistered rule -> exit 2 (protocol failure)."""
    finding = _finding_dict(rule="DB_NOT_A_RULE")
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr


# -- Baseline envelope validation --------------------------------------------------


def test_v2_baseline_schema_version_mismatch_exits_two(tmp_path: Path) -> None:
    """v1 baseline (no schema version) with a v2 guard -> exit 2 schema mismatch."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [], baseline_schema_version=1)

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_SCHEMA_MISMATCH" in result.stderr


def test_v2_baseline_guard_output_schema_version_mismatch_exits_two(
    tmp_path: Path,
) -> None:
    """guard_output_schema_version != report.schema_version -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        guard_output_schema_version=1,
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_SCHEMA_MISMATCH" in result.stderr


def test_v2_baseline_fingerprint_schema_version_mismatch_exits_two(
    tmp_path: Path,
) -> None:
    """fingerprint_schema_version != 2 -> exit 2 schema mismatch."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        fingerprint_schema_version=1,
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_SCHEMA_MISMATCH" in result.stderr


def test_v2_baseline_guard_mismatch_exits_two(tmp_path: Path) -> None:
    """baseline guard does not match the requested guard -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, "some_other_guard", [_entry(_fingerprint_of(finding))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_GUARD_MISMATCH" in result.stderr


def test_v2_baseline_missing_entries_exits_two(tmp_path: Path) -> None:
    """baseline 'entries' missing / not a list -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [], entries="not-a-list")

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr


def test_v2_baseline_duplicate_fingerprints_exits_two(tmp_path: Path) -> None:
    """duplicate baseline fingerprints -> exit 2."""
    finding = _finding_dict()
    fp = _fingerprint_of(finding)
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(fp), _entry(fp)],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "duplicate fingerprint entries" in result.stderr


def test_v2_baseline_zero_count_exits_two(tmp_path: Path) -> None:
    """non-positive baseline count -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding), count=0)])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "count must be a positive integer" in result.stderr


def test_v2_baseline_oversized_count_controlled_exit_two(tmp_path: Path) -> None:
    """baseline entry count above MAX_BASELINE_ENTRIES -> controlled exit 2.

    The per-entry occurrence-count bound is enforced during baseline
    validation, BEFORE any arithmetic or report output: an oversized count is
    rejected with the controlled RATCHET_BASELINE_INVALID diagnostic, the
    huge count value is never echoed, no occurrences arithmetic runs, and the
    failure output stays bounded (no huge report dump).
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    huge_count = 10**18
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), count=huge_count)],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr
    assert "count exceeds the maximum" in result.stderr
    combined = result.stdout + result.stderr
    assert str(huge_count) not in combined, "oversized count value was echoed"
    assert "occurrences" not in result.stdout, "report arithmetic ran after rejection"
    assert len(combined) < 2000, "oversized-count failure produced unbounded output"
    assert "Traceback" not in combined


def test_v2_baseline_missing_metadata_exits_two(tmp_path: Path) -> None:
    """baseline entry missing owner metadata -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    del entry["owner"]
    _write_baseline_v2(baseline, _GUARD, [entry])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "missing required field 'owner'" in result.stderr


def test_v2_baseline_wrong_classification_exits_two(tmp_path: Path) -> None:
    """baseline entry classification != temporary_debt -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD,
        [_entry(_fingerprint_of(finding), classification="structural_exception")],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "classification must be 'temporary_debt'" in result.stderr


def test_v2_baseline_invalid_expiry_exits_two(tmp_path: Path) -> None:
    """baseline entry with an invalid expiry date -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="not-a-date")],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "expiry must be a valid YYYY-MM-DD date" in result.stderr


def test_v2_baseline_missing_file_exits_two(tmp_path: Path) -> None:
    """missing baseline file -> exit 2 (controlled code, no path leak)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = tmp_path / "does-not-exist.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_MISSING" in result.stderr
    assert "does-not-exist.json" not in result.stderr


def test_v2_baseline_expired_exits_one(tmp_path: Path) -> None:
    """expired baseline debt -> exit 1 (policy signal, not infra)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2000-01-01")],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "EXPIRED_BASELINE_ENTRIES: 1" in result.stdout
    assert "UNCHANGED: 1" in result.stdout


def test_v2_baseline_future_expiry_unchanged_passes(tmp_path: Path) -> None:
    """non-expired, unchanged debt -> exit 0 (pass)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2099-12-31")],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "EXPIRED_BASELINE_ENTRIES: 0" in result.stdout
    assert "UNCHANGED: 1" in result.stdout
    assert "PASS" in result.stdout


# -- generated_at envelope validation ----------------------------------------------


def test_v2_baseline_missing_generated_at_exits_two(tmp_path: Path) -> None:
    """baseline without 'generated_at' -> exit 2 (controlled baseline schema)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        drop_keys=["generated_at"],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "generated_at" in result.stderr


@pytest.mark.parametrize(
    "generated_at",
    [
        2026,  # integer
        None,  # null
        ["2026-08-10T00:00:00+00:00"],  # list
        {"raw": "2026-08-10T00:00:00+00:00"},  # object
    ],
)
def test_v2_baseline_non_string_generated_at_exits_two(
    tmp_path: Path, generated_at: object
) -> None:
    """non-string 'generated_at' -> exit 2 (controlled baseline schema)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        generated_at=generated_at,
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "generated_at" in result.stderr


@pytest.mark.parametrize(
    "generated_at",
    [
        "",  # empty string
        "   ",  # whitespace-only
        "not-a-date",  # malformed garbage
        "2026-13-40T00:00:00+00:00",  # impossible calendar values
        "2026-08-10",  # date-only (timezone-less / non-ISO)
        "2026-08-10T00:00:00",  # timezone-less datetime
        "2026-8-10T00:00:00+00:00",  # noncanonical unpadded month
        "2026-08-10T00:00:00+0000",  # noncanonical offset without colon
        " 2026-08-10T00:00:00+00:00",  # leading whitespace
        "2026-08-10T00:00:00+00:00 ",  # trailing whitespace
    ],
)
def test_v2_baseline_malformed_generated_at_exits_two(
    tmp_path: Path, generated_at: str
) -> None:
    """malformed / timezone-less / non-ISO 'generated_at' -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        generated_at=generated_at,
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "generated_at" in result.stderr


@pytest.mark.parametrize(
    "generated_at",
    [
        "2026-08-10T00:00:00+00:00",  # canonical UTC offset, no fraction
        "2026-08-10T00:00:00.298282+00:00",  # microseconds (writer form)
        "2026-08-10T00:00:00Z",  # canonical Z form
        "2026-08-10T23:59:59-04:00",  # non-UTC offset still ISO-8601
    ],
)
def test_v2_baseline_valid_generated_at_passes(
    tmp_path: Path, generated_at: str
) -> None:
    """strict ISO-8601 'generated_at' values are accepted (exit 0 on pass)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        generated_at=generated_at,
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "UNCHANGED: 1" in result.stdout
    assert "PASS" in result.stdout


# -- expiry canonical-form validation ----------------------------------------------


@pytest.mark.parametrize(
    "expiry",
    [
        "2026-1-1",  # unpadded month/day
        "2026-8-10",  # unpadded month
        "2026-08-1",  # unpadded day
        "2026-08-10T00:00:00+00:00",  # ISO datetime, not a date
        "2026-08-10T00:00:00",  # datetime without timezone
        "2026-08-10 00:00:00",  # space-separated datetime
        " 2026-08-10",  # leading whitespace
        "2026-08-10 ",  # trailing whitespace
        "2026/08/10",  # wrong separator
        "20260810",  # compact YYYYMMDD
        "2026-08",  # year-month only
        "2026-08-32",  # impossible calendar day
    ],
)
def test_v2_baseline_noncanonical_expiry_exits_two(
    tmp_path: Path, expiry: str
) -> None:
    """noncanonical expiry forms -> exit 2 (never broadly date-parsed)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires=expiry)],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "expiry must be a valid YYYY-MM-DD date" in result.stderr


@pytest.mark.parametrize(
    "expiry, expected_exit, stdout_marker",
    [
        ("2099-12-31", 0, "EXPIRED_BASELINE_ENTRIES: 0"),  # valid canonical, not expired
        ("2000-01-01", 1, "EXPIRED_BASELINE_ENTRIES: 1"),  # valid canonical, expired
    ],
)
def test_v2_baseline_canonical_expiry_dates(
    tmp_path: Path, expiry: str, expected_exit: int, stdout_marker: str
) -> None:
    """canonical YYYY-MM-DD expiry dates parse; expired ones stay exit 1."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires=expiry)],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == expected_exit, (
        f"Expected exit {expected_exit}, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert stdout_marker in result.stdout


def test_v2_baseline_expiry_alias_rejected_exits_two(tmp_path: Path) -> None:
    """baseline entry using the legacy 'expiry' alias -> exit 2 (unknown field)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    del entry["expires"]
    entry["expiry"] = "2099-12-31"
    _write_baseline_v2(baseline, _GUARD, [entry])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "unknown field 'expiry'" in result.stderr


def test_v2_baseline_expiry_alias_with_expires_rejected_exits_two(
    tmp_path: Path,
) -> None:
    """entry carrying both 'expires' and 'expiry' -> exit 2 (invalid schema)."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    entry["expiry"] = "2099-12-31"
    _write_baseline_v2(baseline, _GUARD, [entry])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "unknown field 'expiry'" in result.stderr


def test_v2_baseline_missing_expires_exits_two(tmp_path: Path) -> None:
    """baseline entry without the canonical 'expires' field -> exit 2."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    del entry["expires"]
    _write_baseline_v2(baseline, _GUARD, [entry])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "missing required field 'expires'" in result.stderr


# -- strict envelope/entry key validation -------------------------------------------


def test_v2_baseline_unknown_envelope_field_exits_two(tmp_path: Path) -> None:
    """unknown top-level envelope field -> exit 2, active baseline untouched.

    The v2 envelope is closed: an unknown top-level field (extra metadata,
    diagnostics, or any other key) on an otherwise valid baseline is rejected
    with the controlled RATCHET_BASELINE_INVALID exit 2, and the active
    baseline file is never rewritten.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding))],
        diagnostics=[],
    )
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_v2_baseline_unknown_entry_field_exits_two(tmp_path: Path) -> None:
    """unknown baseline entry field -> exit 2, active baseline untouched.

    The v2 entry schema is closed: an unknown entry field (extra metadata,
    diagnostics, or any other key) on an otherwise valid baseline is rejected
    with the controlled RATCHET_BASELINE_INVALID exit 2, and the active
    baseline file is never rewritten.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    entry["severity"] = "error"
    _write_baseline_v2(baseline, _GUARD, [entry])
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_v2_baseline_unknown_fields_both_exits_two(tmp_path: Path) -> None:
    """unknown envelope AND entry fields combined -> exit 2, baseline untouched.

    A baseline with an unknown top-level field and an unknown entry field on
    an otherwise valid baseline is rejected with the controlled
    RATCHET_BASELINE_INVALID exit 2; the active baseline file is never
    rewritten.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    entry["diagnostics"] = [{"code": "EXTRA"}]
    _write_baseline_v2(
        baseline, _GUARD, [entry],
        diagnostics=[],
    )
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr
    assert baseline.read_bytes() == before, "active baseline was modified"


# -- Count-aware comparison --------------------------------------------------------


def test_v2_new_occurrence_exits_one(tmp_path: Path) -> None:
    """same key, higher current count -> NEW_OCCURRENCES -> exit 1."""
    base = _finding_dict(line=42)
    second = _finding_dict(line=99)  # same semantic fingerprint, different line
    assert _fingerprint_of(base) == _fingerprint_of(second)
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py, _report_dict(findings=[base, second]), exit_code=1
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(base), count=1)],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "NEW_OCCURRENCES: 1" in result.stdout
    assert "UNCHANGED: 0" in result.stdout


def test_v2_resolved_occurrence_exits_one(tmp_path: Path) -> None:
    """same key, lower current count -> RESOLVED_OCCURRENCES -> exit 1."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), count=2)],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RESOLVED_OCCURRENCES: 1" in result.stdout


def test_v2_resolved_key_exits_one(tmp_path: Path) -> None:
    """baseline key absent from current findings -> RESOLVED_KEYS -> exit 1.

    Uses child exit 0 with an empty report: all baseline debt resolved, which
    still requires a reviewed shrink before the ratchet turns green.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[]), exit_code=0)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RESOLVED_KEYS: 1" in result.stdout


def test_v2_multiple_categories_reported(tmp_path: Path) -> None:
    """one new key, one resolved key, one unchanged key are all reported."""
    unchanged = _finding_dict(name="unchangedMethod")
    new = _finding_dict(name="newMethod")
    resolved = _finding_dict(name="resolvedMethod")
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py, _report_dict(findings=[unchanged, new]), exit_code=1
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD,
        [
            _entry(_fingerprint_of(unchanged)),
            _entry(_fingerprint_of(resolved)),
        ],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "NEW_KEYS: 1" in result.stdout
    assert "RESOLVED_KEYS: 1" in result.stdout
    assert "UNCHANGED: 1" in result.stdout


# -- stdout is never parsed ---------------------------------------------------------


def test_v2_never_parses_stdout(tmp_path: Path) -> None:
    """v2 ignores stdout entirely: report content decides the outcome."""
    report_finding = _finding_dict(name="reportMethod")
    stdout_line = "DB_UNAUTHORIZED_MUTATION app/src/main/java/com/example/Stdout.kt:1 fake"
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[report_finding]),
        exit_code=1,
        stdout=stdout_line,
    )
    baseline = _baseline(tmp_path)
    # Baseline matches the REPORT finding; if stdout were parsed, the fake
    # stdout finding would be a new key and the ratchet would exit 1.
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(report_finding))],
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0 (report used, stdout ignored), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "UNCHANGED: 1" in result.stdout
    assert "NEW_KEYS: 0" in result.stdout


# -- temp report cleanup ------------------------------------------------------------


def test_v2_temp_report_removed_after_run(tmp_path: Path) -> None:
    """the temporary report file is removed after a successful comparison."""
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    marker = tmp_path / "seen_paths.txt"
    _write_mock_guard(
        guard_py, _report_dict(findings=[finding]), exit_code=1, marker_path=marker
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    recorded = marker.read_text(encoding="utf-8").strip()
    assert recorded, "mock guard did not record the report path"
    assert not Path(recorded).exists(), (
        f"temporary report was not cleaned up: {recorded}"
    )


def test_v2_temp_report_removed_on_error(tmp_path: Path) -> None:
    """the temporary report file is removed even when the ratchet errors out."""
    guard_py = _guard_py(tmp_path)
    marker = tmp_path / "seen_paths.txt"
    _write_mock_guard(
        guard_py, _report_dict(), exit_code=2, marker_path=marker
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    recorded = marker.read_text(encoding="utf-8").strip()
    assert recorded, "mock guard did not record the report path"
    assert not Path(recorded).exists(), (
        f"temporary report was not cleaned up: {recorded}"
    )


def test_v2_child_sees_existing_writable_report_path(tmp_path: Path) -> None:
    """the child sees the retained writable report path and writes the report.

    Regression guard for the report-path TOCTOU: the ratchet must retain the
    securely created (mkstemp) report file -- never unlink it before child
    execution -- so the child sees an existing, writable report path and can
    truncate/write it in place.  The mock guard fails (exit 9) if the target
    does not already exist or cannot be opened for writing, then writes a
    valid report; the comparison passes and the temporary report is cleaned up
    afterward.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    marker = tmp_path / "seen_paths.txt"
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[finding]),
        exit_code=1,
        marker_path=marker,
        require_existing_report=True,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0 (child wrote a valid report into the retained path), "
        f"got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "REPORT_TARGET_MISSING" not in result.stderr, (
        "child did not see the retained report path at spawn time"
    )
    assert "REPORT_TARGET_NOT_WRITABLE" not in result.stderr, (
        "child could not write the retained report path"
    )
    assert "UNCHANGED: 1" in result.stdout
    assert "PASS" in result.stdout
    recorded = marker.read_text(encoding="utf-8").strip()
    assert recorded, "mock guard did not record the report path"
    assert not Path(recorded).exists(), (
        f"temporary report was not cleaned up: {recorded}"
    )


def test_v2_child_leaves_empty_retained_report_exits_two(tmp_path: Path) -> None:
    """a child that never writes leaves the retained file EMPTY -> exit 2.

    The retained mkstemp file exists at load time but carries no report
    content, so ``load_report`` rejects it as invalid JSON and the ratchet
    fails closed with RATCHET_V2_REPORT_INVALID (exit 2) -- the contract
    requires an invalid/empty report to remain an infrastructure failure, and
    the empty retained file is cleaned up afterward.
    """
    guard_py = _guard_py(tmp_path)
    marker = tmp_path / "seen_paths.txt"
    _write_mock_guard(
        guard_py, None, exit_code=1, marker_path=marker,
        require_existing_report=True,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (empty report is invalid), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_INVALID" in result.stderr
    recorded = marker.read_text(encoding="utf-8").strip()
    assert recorded, "mock guard did not record the report path"
    assert not Path(recorded).exists(), (
        f"temporary report was not cleaned up: {recorded}"
    )


# -- temporary report creation failure -----------------------------------------------


def test_v2_mkstemp_failure_is_controlled_infra_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture
) -> None:
    """mkstemp failure -> controlled (-1, None); the ratchet exits 2.

    Simulates ``tempfile.mkstemp`` raising (e.g. an unwritable temp
    directory) and asserts the failure is reported as a bounded
    infrastructure diagnostic -- never a traceback, the raw temp path, or
    the raw exception message.
    """
    import guard_ratchet as gr

    def _boom_mkstemp(*args, **kwargs):
        raise RuntimeError(
            "could not create file in C:\\Users\\SECRETS\\cost-aggregator-tmp"
        )

    monkeypatch.setattr("tempfile.mkstemp", _boom_mkstemp)

    # Unit contract: the guarded call returns a controlled infrastructure
    # result with no report path (nothing was created, nothing to clean up).
    exit_code, report_path = gr.run_guard_command_v2(
        [sys.executable, "-c", "pass"], tmp_path, timeout=10
    )
    assert exit_code == -1
    assert report_path is None
    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "Traceback" not in combined
    assert "RuntimeError" not in combined
    assert "SECRETS" not in combined
    assert "cost-aggregator-tmp" not in combined

    # End-to-end contract: the v2 flow maps the infrastructure failure to the
    # controlled exit-2 path with a bounded diagnostic (no raw leak).
    args = SimpleNamespace(guard_name=_GUARD, update_baseline=False, timeout=10)
    with pytest.raises(SystemExit) as excinfo:
        gr._main_v2(
            args,
            [sys.executable, "-c", "pass"],
            tmp_path,
            _baseline(tmp_path),
        )
    assert excinfo.value.code == 2
    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "could not execute the guard command" in captured.err
    assert "Traceback" not in combined
    assert "RuntimeError" not in combined
    assert "SECRETS" not in combined
    assert "cost-aggregator-tmp" not in combined


# -- protocol selection -------------------------------------------------------------


def test_v2_protocol_default_is_legacy(tmp_path: Path) -> None:
    """without --finding-protocol 2 the ratchet stays on the legacy stdout path.

    A v2-style child (writes a report, prints nothing parseable) run without
    the flag must NOT be consumed as a report: the legacy path sees empty
    stdout and treats exit 1 as an unparseable-output infra error (exit 2).
    """
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[_finding_dict()]), exit_code=1)
    baseline = _baseline(tmp_path)
    baseline.write_text(
        json.dumps({"guard": _GUARD, "generated": "2026-07-10", "fingerprints": []})
        + "\n",
        encoding="utf-8",
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (legacy path), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "no parseable findings" in result.stderr


def test_v2_invalid_finding_protocol_rejected(tmp_path: Path) -> None:
    """--finding-protocol with an unsupported value -> exit 2."""
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(), exit_code=0)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=3, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "must be 1 or 2" in result.stderr


# -- v2 baseline updates are prohibited ------------------------------------------------


def test_v2_update_baseline_rejected_and_active_unchanged(tmp_path: Path) -> None:
    """--update-baseline on v2 is a controlled exit 2; the active baseline is untouched.

    The v2 flow rejects baseline updates outright (v2 baselines require a
    reviewed debt-reduction path) before the guard command ever runs.  Even
    with a child report that would otherwise compare unchanged (exit 0), the
    ratchet exits 2 and never rewrites the active baseline -- no candidate
    silently overwrites it.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2099-12-31")]
    )
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path, extra_args=["--update-baseline"],
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (v2 baseline update prohibited), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "baseline updates are not supported for finding protocol v2" in result.stderr
    assert "Baseline updated" not in result.stdout
    assert baseline.read_bytes() == before, (
        "active baseline was modified by a rejected --update-baseline run"
    )


# -- --propose-baseline (candidate output) ------------------------------------------


def test_propose_baseline_rejected_in_ci_mode_and_active_unchanged(
    tmp_path: Path,
) -> None:
    """--propose-baseline in --ci-mode is rejected (exit 2) before anything runs.

    The proposal is prohibited outright in CI mode: the guard command never
    runs, the active baseline is never touched, and no candidate file is
    created.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2099-12-31")]
    )
    candidate = tmp_path / "proposed_baseline.json"
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--propose-baseline", str(candidate), "--ci-mode"],
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (CI mode prohibits proposals), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "Baseline proposal prohibited in CI mode" in result.stderr
    assert not candidate.exists(), "candidate was written despite CI-mode rejection"
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_propose_baseline_rejected_for_v1(tmp_path: Path) -> None:
    """--propose-baseline with finding protocol v1 is rejected (exit 2).

    The candidate feature exists only on the structured protocol-v2 path;
    requesting it on the legacy v1 path fails closed before the guard command
    runs, writes no candidate, and never touches the active baseline.
    """
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[_finding_dict()]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])
    candidate = tmp_path / "proposed_baseline.json"
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=1, cwd=tmp_path,
        extra_args=["--propose-baseline", str(candidate)],
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (proposal is v2-only), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "--propose-baseline is only supported for finding protocol v2" in result.stderr
    assert not candidate.exists(), "candidate was written for protocol v1"
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_propose_baseline_same_path_rejected(tmp_path: Path) -> None:
    """--propose-baseline pointing at the active baseline is rejected (exit 2).

    The CLI enforces that the candidate path differs from the active baseline
    path so a proposal can never overwrite the active baseline; the active
    file stays byte-identical.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2099-12-31")]
    )
    before = baseline.read_bytes()

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--propose-baseline", str(baseline)],
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (proposal path equals active baseline), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert (
        "proposed baseline path must differ from the active baseline path"
        in result.stderr
    )
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_propose_debt_reduction_writes_candidate_only(tmp_path: Path) -> None:
    """A resolved-occurrence debt reduction writes ONLY the candidate.

    The active baseline stays byte-identical; the candidate reflects the
    reduced current count while preserving the reviewed metadata (reason,
    owner, linked_issue, expires) from the active entry.  Per the current
    implementation the final exit is 1: the resolved-occurrence delta is a
    policy signal even though the candidate was generated.
    """
    finding = _finding_dict()
    fp = _fingerprint_of(finding)
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(fp, count=2, expires="2099-12-31")])
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--propose-baseline", str(candidate)],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (resolved-occurrence delta), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RESOLVED_OCCURRENCES: 1" in result.stdout
    assert "Proposal: candidate baseline written (1 entries)" in result.stdout
    assert baseline.read_bytes() == before, "active baseline was modified"
    assert candidate.exists(), "candidate baseline was not written"
    data = json.loads(candidate.read_text(encoding="utf-8"))
    assert data["baseline_schema_version"] == 2
    assert data["guard"] == _GUARD
    assert len(data["entries"]) == 1
    entry = data["entries"][0]
    assert entry["fingerprint"] == fp
    assert entry["count"] == 1
    assert entry["expires"] == "2099-12-31"  # reviewed metadata preserved
    assert entry["reason"] == "Existing debt awaiting lifecycle migration"


def test_propose_growth_writes_no_candidate(tmp_path: Path) -> None:
    """A new-key growth blocks the candidate: non-zero exit, no file.

    The proposal path prints one controlled PROPOSAL_SKIPPED diagnostic and
    exits non-zero WITHOUT writing a candidate; the active baseline is
    untouched.
    """
    finding = _finding_dict()
    other = _finding_dict(name="otherMethod")
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(other), expires="2099-12-31")]
    )
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--propose-baseline", str(candidate)],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (growth blocks proposal), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "NEW_KEYS: 1" in result.stdout
    assert (
        "PROPOSAL_SKIPPED: candidate not generated (new findings present)"
        in result.stderr
    )
    assert not candidate.exists(), "candidate was written despite growth"
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_propose_expired_debt_writes_no_candidate(tmp_path: Path) -> None:
    """Expired baseline debt blocks the candidate: non-zero exit, no file.

    The proposal path treats expired debt as requiring review before any
    shrink: one controlled PROPOSAL_SKIPPED diagnostic is printed and the run
    exits non-zero WITHOUT writing a candidate; the active baseline stays
    byte-identical.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2000-01-01")]
    )
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--propose-baseline", str(candidate)],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (expired debt blocks proposal), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "EXPIRED_BASELINE_ENTRIES: 1" in result.stdout
    assert (
        "PROPOSAL_SKIPPED: candidate not generated (expired baseline debt "
        "must be reviewed before proposing)"
        in result.stderr
    )
    assert not candidate.exists(), "candidate was written despite expired debt"
    assert baseline.read_bytes() == before, "active baseline was modified"


def test_propose_report_diagnostics_writes_no_candidate(tmp_path: Path) -> None:
    """A valid report carrying diagnostics blocks the candidate.

    An unresolved classification (report infrastructure diagnostic) is never
    baseline-able: the v2 flow fails closed with RATCHET_V2_REPORT_DIAGNOSTICS
    (exit 2) before any candidate generation; no candidate is written and the
    active baseline stays byte-identical.
    """
    diagnostic = {
        "code": "DB_SOURCE_UNREADABLE",
        "path": None,
        "symbol": None,
        "controlled_context": {},
    }
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[finding], diagnostics=[diagnostic]),
        exit_code=1,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2099-12-31")]
    )
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--propose-baseline", str(candidate)],
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (diagnostics block proposal), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_DIAGNOSTICS" in result.stderr
    assert not candidate.exists(), "candidate was written despite diagnostics"
    assert baseline.read_bytes() == before, "active baseline was modified"


# -- --output-summary (v2 summary JSON) ---------------------------------------------


def test_v2_output_summary_written_and_matches_process(tmp_path: Path) -> None:
    """--output-summary writes a deterministic JSON matching exit/categories.

    The v2 flow honors --output-summary: the summary file records the guard,
    protocol/schema, baseline and current key/occurrence counts, every
    comparison category count (NEW_KEYS, NEW_OCCURRENCES, RESOLVED_KEYS,
    RESOLVED_OCCURRENCES, UNCHANGED, EXPIRED_BASELINE_ENTRIES), and the final
    exit code -- which must match the actual process exit code.
    """
    unchanged = _finding_dict(name="unchangedMethod")
    new = _finding_dict(name="newMethod")
    expired_finding = _finding_dict(name="expiredMethod")
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[unchanged, new, expired_finding]),
        exit_code=1,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD,
        [
            _entry(_fingerprint_of(unchanged), expires="2099-12-31"),
            _entry(
                _fingerprint_of(_finding_dict(name="resolvedMethod")),
                expires="2099-12-31",
            ),
            _entry(_fingerprint_of(expired_finding), expires="2000-01-01"),
        ],
    )
    summary_path = tmp_path / "summary.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=["--output-summary", str(summary_path)],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (new/resolved/expired deltas), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "NEW_KEYS: 1" in result.stdout
    assert "RESOLVED_KEYS: 1" in result.stdout
    assert "UNCHANGED: 2" in result.stdout
    assert "EXPIRED_BASELINE_ENTRIES: 1" in result.stdout

    assert summary_path.exists(), "summary file was not written"
    data = json.loads(summary_path.read_text(encoding="utf-8"))
    assert data["guard"] == _GUARD
    assert data["protocol"] == 2
    assert data["schema"] == 2
    assert data["baseline"] == {"keys": 3, "occurrences": 3}
    assert data["current"] == {"keys": 3, "occurrences": 3}
    assert data["NEW_KEYS"] == 1
    assert data["NEW_OCCURRENCES"] == 0
    assert data["RESOLVED_KEYS"] == 1
    assert data["RESOLVED_OCCURRENCES"] == 0
    assert data["UNCHANGED"] == 2
    assert data["EXPIRED_BASELINE_ENTRIES"] == 1
    assert data["final_exit_code"] == result.returncode == 1


def test_v2_output_summary_write_failure_is_controlled_infra_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture
) -> None:
    """summary write failure -> controlled exit 2, no partial file, no leak.

    Simulates an os.replace failure while writing the summary and asserts the
    v2 flow reports the failure with the bounded controlled diagnostic and
    exits 2 -- never echoing the summary path, the exception text, or a
    traceback, and leaving no partial summary at the target path.
    """
    import guard_ratchet as gr

    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])
    summary_path = tmp_path / "summary.json"

    def _boom_replace(src, dst):
        raise RuntimeError("replace failed on C:\\Users\\SECRETS\\summary.json")

    monkeypatch.setattr(os, "replace", _boom_replace)

    args = SimpleNamespace(
        guard_name=_GUARD,
        update_baseline=False,
        timeout=10,
        output_summary=summary_path,
    )
    with pytest.raises(SystemExit) as excinfo:
        gr._main_v2(
            args,
            [sys.executable, str(guard_py)],
            tmp_path,
            baseline,
        )
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "RATCHET_SUMMARY_WRITE_FAILED" in captured.err
    assert "SECRETS" not in combined
    assert "summary.json" not in combined
    assert "Traceback" not in combined
    assert "RuntimeError" not in combined
    assert "replace failed" not in combined
    assert not summary_path.exists(), "partial summary left at the target path"


# -- --propose-baseline + --output-summary (proposal summary behavior) ---------------


def test_propose_growth_with_output_summary_writes_fresh_summary(tmp_path: Path) -> None:
    """Growth-blocked proposal + --output-summary: fresh summary, no candidate.

    The v2 flow computes the final exit code BEFORE the proposal, so even a
    blocked (growth) proposal still writes a fresh summary: the summary
    records the comparison categories (NEW_KEYS / NEW_OCCURRENCES populated),
    and ``final_exit_code`` exactly equals the process exit code (1).  A stale
    summary already sitting at the target path is overwritten, no candidate is
    written, and the active baseline stays byte-identical.
    """
    grown = _finding_dict(name="grownMethod", line=42)
    grown_second = _finding_dict(name="grownMethod", line=99)  # same fp, new location
    assert _fingerprint_of(grown) == _fingerprint_of(grown_second)
    new_key = _finding_dict(name="brandNewMethod")
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[grown, grown_second, new_key]),
        exit_code=1,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(grown), count=1)]
    )
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"
    summary = tmp_path / "summary.json"
    summary.write_text(json.dumps({"stale": True, "final_exit_code": 0}), encoding="utf-8")

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=[
            "--propose-baseline", str(candidate),
            "--output-summary", str(summary),
        ],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (growth blocks proposal), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "NEW_KEYS: 1" in result.stdout
    assert "NEW_OCCURRENCES: 1" in result.stdout
    assert (
        "PROPOSAL_SKIPPED: candidate not generated (new findings present)"
        in result.stderr
    )
    assert not candidate.exists(), "candidate was written despite growth"
    assert baseline.read_bytes() == before, "active baseline was modified"

    data = json.loads(summary.read_text(encoding="utf-8"))
    assert data.get("stale") is not True, "stale summary was not overwritten"
    assert data["guard"] == _GUARD
    assert data["NEW_KEYS"] == 1
    assert data["NEW_OCCURRENCES"] == 1
    assert data["RESOLVED_KEYS"] == 0
    assert data["RESOLVED_OCCURRENCES"] == 0
    assert data["UNCHANGED"] == 0
    assert data["EXPIRED_BASELINE_ENTRIES"] == 0
    assert data["baseline"] == {"keys": 1, "occurrences": 1}
    assert data["current"] == {"keys": 2, "occurrences": 3}
    assert data["final_exit_code"] == result.returncode == 1


def test_propose_expired_debt_with_output_summary_writes_fresh_summary(
    tmp_path: Path,
) -> None:
    """Expired-debt proposal + --output-summary: fresh summary, no candidate.

    A proposal blocked by expired baseline debt still writes a fresh summary:
    EXPIRED_BASELINE_ENTRIES is populated and ``final_exit_code`` equals the
    process exit code (1).  No candidate is written and the active baseline
    stays byte-identical.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2000-01-01")]
    )
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"
    summary = tmp_path / "summary.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=[
            "--propose-baseline", str(candidate),
            "--output-summary", str(summary),
        ],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (expired debt blocks proposal), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "EXPIRED_BASELINE_ENTRIES: 1" in result.stdout
    assert (
        "PROPOSAL_SKIPPED: candidate not generated (expired baseline debt "
        "must be reviewed before proposing)"
        in result.stderr
    )
    assert not candidate.exists(), "candidate was written despite expired debt"
    assert baseline.read_bytes() == before, "active baseline was modified"

    data = json.loads(summary.read_text(encoding="utf-8"))
    assert data["guard"] == _GUARD
    assert data["EXPIRED_BASELINE_ENTRIES"] == 1
    assert data["NEW_KEYS"] == 0
    assert data["UNCHANGED"] == 1
    assert data["final_exit_code"] == result.returncode == 1


def test_propose_debt_reduction_with_output_summary_writes_candidate_and_summary(
    tmp_path: Path,
) -> None:
    """Successful debt-reduction proposal + --output-summary: candidate + summary.

    A resolved-occurrence debt reduction writes the candidate AND a fresh
    summary: the summary reflects the resolved categories and the final exit
    code (1), while the active baseline stays byte-identical.
    """
    finding = _finding_dict()
    fp = _fingerprint_of(finding)
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(fp, count=2, expires="2099-12-31")])
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"
    summary = tmp_path / "summary.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=[
            "--propose-baseline", str(candidate),
            "--output-summary", str(summary),
        ],
    )

    assert result.returncode == 1, (
        f"Expected exit 1 (resolved-occurrence delta), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RESOLVED_OCCURRENCES: 1" in result.stdout
    assert "Proposal: candidate baseline written (1 entries)" in result.stdout
    assert baseline.read_bytes() == before, "active baseline was modified"
    assert candidate.exists(), "candidate baseline was not written"
    candidate_data = json.loads(candidate.read_text(encoding="utf-8"))
    assert candidate_data["entries"][0]["count"] == 1

    data = json.loads(summary.read_text(encoding="utf-8"))
    assert data["guard"] == _GUARD
    assert data["RESOLVED_OCCURRENCES"] == 1
    assert data["RESOLVED_KEYS"] == 0
    assert data["UNCHANGED"] == 0
    assert data["baseline"] == {"keys": 1, "occurrences": 2}
    assert data["current"] == {"keys": 1, "occurrences": 1}
    assert data["final_exit_code"] == result.returncode == 1


def test_propose_diagnostics_with_output_summary_exits_two_and_writes_no_summary(
    tmp_path: Path,
) -> None:
    """Diagnostics report + --output-summary: exit 2, no fresh summary written.

    The RATCHET_V2_REPORT_DIAGNOSTICS failure exits 2 BEFORE the summary
    write step, so no fresh summary is created and a pre-existing stale
    summary file is never reused or refreshed: its content stays byte-identical
    (no stale-summary reuse).  No candidate is written and the active baseline
    stays byte-identical.
    """
    diagnostic = {
        "code": "DB_SOURCE_UNREADABLE",
        "path": None,
        "symbol": None,
        "controlled_context": {},
    }
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(
        guard_py,
        _report_dict(findings=[finding], diagnostics=[diagnostic]),
        exit_code=1,
    )
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, _GUARD, [_entry(_fingerprint_of(finding), expires="2099-12-31")]
    )
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"
    summary = tmp_path / "summary.json"
    stale = json.dumps({"stale": True, "final_exit_code": 0})
    summary.write_text(stale, encoding="utf-8")

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path,
        extra_args=[
            "--propose-baseline", str(candidate),
            "--output-summary", str(summary),
        ],
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (diagnostics), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_V2_REPORT_DIAGNOSTICS" in result.stderr
    assert not candidate.exists(), "candidate was written despite diagnostics"
    assert baseline.read_bytes() == before, "active baseline was modified"
    assert summary.read_text(encoding="utf-8") == stale, (
        "stale summary was reused/refreshed despite the diagnostics failure"
    )


# -- v2 exit codes are independent of --fail-on-violation ----------------------------


@pytest.mark.parametrize(
    "fail_flag", [False, True], ids=["no_fail_flag", "with_fail_on_violation"]
)
@pytest.mark.parametrize("kind", ["new", "resolved", "expired"])
def test_v2_delta_exit_independent_of_fail_flag(
    tmp_path: Path, kind: str, fail_flag: bool
) -> None:
    """v2 comparison deltas and expired debt exit 1 with or without the flag.

    The v2 exit code is a policy signal driven by the comparison state, not by
    --fail-on-violation: new / resolved deltas and expired baseline debt exit 1
    whether the flag is present or not (infrastructure failures stay 2).
    """
    finding = _finding_dict()
    other = _finding_dict(name="otherMethod")
    guard_py = _guard_py(tmp_path)
    baseline = _baseline(tmp_path)
    fp = _fingerprint_of(finding)
    other_fp = _fingerprint_of(other)

    if kind == "new":
        _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
        _write_baseline_v2(
            baseline, _GUARD, [_entry(other_fp, expires="2099-12-31")]
        )
        marker = "NEW_KEYS: 1"
    elif kind == "resolved":
        _write_mock_guard(guard_py, _report_dict(findings=[]), exit_code=0)
        _write_baseline_v2(baseline, _GUARD, [_entry(fp, expires="2099-12-31")])
        marker = "RESOLVED_KEYS: 1"
    else:  # expired
        _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
        _write_baseline_v2(baseline, _GUARD, [_entry(fp, expires="2000-01-01")])
        marker = "EXPIRED_BASELINE_ENTRIES: 1"

    extra_args = ["--fail-on-violation"] if fail_flag else None
    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline,
        protocol=2, cwd=tmp_path, extra_args=extra_args,
    )

    assert result.returncode == 1, (
        f"Expected exit 1 for {kind} with fail_flag={fail_flag}, "
        f"got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert marker in result.stdout


# -- Baseline load sanitization (sensitive paths / hostile exceptions) -----------


def test_v2_baseline_missing_under_sensitive_directory_exits_two(
    tmp_path: Path,
) -> None:
    """missing baseline under a sensitive directory name -> controlled exit 2.

    The missing-baseline failure is reported as RATCHET_BASELINE_MISSING
    without echoing the baseline path, so a sensitive directory name in the
    path can never leak through the diagnostic.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = tmp_path / "SECRETS" / "prod" / "baseline.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_MISSING" in result.stderr
    assert "SECRETS" not in result.stderr
    assert "baseline.json" not in result.stderr
    assert "Traceback" not in result.stderr


def test_v1_baseline_missing_hostile_path_no_leak(tmp_path: Path) -> None:
    """legacy v1 missing baseline under a sensitive directory -> controlled code.

    The legacy protocol-v1 path reports a missing baseline with the fixed
    controlled RATCHET_BASELINE_MISSING diagnostic: the baseline path (which
    may carry a sensitive or hostile directory name) and any raw error are
    never interpolated, and no traceback is emitted.  Regression guard for a
    missing-baseline report that previously echoed the raw baseline path.
    """
    guard_py = _guard_py(tmp_path)
    _write_legacy_mock_guard(
        guard_py,
        "G-CANCEL-01 app/src/main/java/com/example/Worker.kt:42 desc",
        exit_code=1,
    )
    baseline = tmp_path / "SECRETS" / "prod" / "baseline.json"

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2 (missing baseline), got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_MISSING" in result.stderr
    assert "Baseline file not found" not in result.stderr
    assert str(baseline) not in result.stderr
    assert "SECRETS" not in result.stderr
    assert "baseline.json" not in result.stderr
    assert "Traceback" not in result.stderr


def test_v2_baseline_malformed_under_sensitive_directory_exits_two(
    tmp_path: Path,
) -> None:
    """malformed baseline JSON under a sensitive directory -> controlled exit 2.

    The malformed-JSON failure is reported as RATCHET_BASELINE_MALFORMED
    without the baseline path, the offending JSON text, or a traceback.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    sensitive_dir = tmp_path / "SECRETS"
    sensitive_dir.mkdir(parents=True, exist_ok=True)
    baseline = sensitive_dir / "baseline.json"
    baseline.write_text("{ this is not valid json at all }", encoding="utf-8")

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_MALFORMED" in result.stderr
    assert "SECRETS" not in result.stderr
    assert "baseline.json" not in result.stderr
    assert "not valid json" not in result.stderr
    assert "Traceback" not in result.stderr


@pytest.mark.parametrize(
    "content_bytes, expected_code",
    [
        (b"[1, 2, 3]", "RATCHET_BASELINE_INVALID"),  # valid JSON, non-object list
        (b'"a plain string"', "RATCHET_BASELINE_INVALID"),  # valid JSON, non-object str
        (b"\xff\xfe\x00 not utf-8 \x80\x81", "RATCHET_BASELINE_ENCODING"),  # bad UTF-8
    ],
    ids=["non_object_list", "non_object_string", "invalid_utf8"],
)
def test_v2_baseline_non_object_or_invalid_utf8_controlled(
    tmp_path: Path, content_bytes: bytes, expected_code: str
) -> None:
    """non-object top-level JSON / invalid UTF-8 -> controlled code, no path leak.

    A valid-JSON non-object top level maps to the controlled schema code
    RATCHET_BASELINE_INVALID; non-UTF-8 bytes map to RATCHET_BASELINE_ENCODING.
    Neither diagnostic echoes the sensitive directory name, the file name, the
    offending value, or a traceback.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    sensitive_dir = tmp_path / "SECRETS"
    sensitive_dir.mkdir(parents=True, exist_ok=True)
    baseline = sensitive_dir / "baseline.json"
    baseline.write_bytes(content_bytes)

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert expected_code in result.stderr
    assert "SECRETS" not in result.stderr
    assert "baseline.json" not in result.stderr
    assert "Traceback" not in result.stderr


@pytest.mark.parametrize(
    "target, hostile_exc, expected_code",
    [
        (
            "builtins.open",
            PermissionError("Access denied to C:\\Users\\SECRETS\\baseline.json"),
            "RATCHET_BASELINE_UNREADABLE",
        ),
        (
            "json.load",
            PermissionError("Access denied to C:\\Users\\SECRETS\\baseline.json"),
            "RATCHET_BASELINE_UNREADABLE",
        ),
        (
            "json.load",
            UnicodeDecodeError(
                "utf-8",
                b"\xff\xfe\x00hidden-secret\x80",
                0,
                3,
                "invalid start byte",
            ),
            "RATCHET_BASELINE_ENCODING",
        ),
    ],
    ids=[
        "open_permission_error",
        "json_load_permission_error",
        "json_load_unicode_decode_error",
    ],
)
def test_v2_baseline_load_hostile_exception_controlled(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture,
    target: str,
    hostile_exc: Exception,
    expected_code: str,
) -> None:
    """monkeypatched open/json.load raising hostile text -> controlled code only.

    The v2 baseline loader (load_baseline_v2) reports only the bounded
    controlled diagnostic: the raw exception message (which carries a hostile
    path and secret text), the exception class, and any traceback are never
    surfaced.
    """
    import guard_ratchet as gr

    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    def _boom(*args, **kwargs):
        raise hostile_exc

    monkeypatch.setattr(target, _boom)

    with pytest.raises(SystemExit) as excinfo:
        gr.load_baseline_v2(baseline, _GUARD, 2)
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert expected_code in captured.err
    assert "SECRETS" not in combined
    assert "hidden-secret" not in combined
    assert "baseline.json" not in combined
    assert "invalid start byte" not in combined
    assert "Traceback" not in combined
    assert "PermissionError" not in combined
    assert "UnicodeDecodeError" not in combined


# -- Load hardening: RuntimeError / BaseException propagation --------------------


@pytest.mark.parametrize(
    "target, hostile_exc",
    [
        (
            "builtins.open",
            RuntimeError(
                "filesystem exploded on C:\\Users\\SECRETS\\prod\\baseline.json"
            ),
        ),
        (
            "json.load",
            RuntimeError("parser exploded with SECRET-PAYLOAD-MARKER-12345"),
        ),
    ],
    ids=["open_runtime_error", "json_load_runtime_error"],
)
def test_v2_baseline_load_runtime_error_controlled(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture,
    target: str,
    hostile_exc: Exception,
) -> None:
    """unexpected RuntimeError from open/json.load -> controlled exit 2, no leak.

    The unexpected-exception fallback in ``load_baseline_v2`` reports only the
    bounded ``RATCHET_BASELINE_UNREADABLE`` diagnostic: the exception class,
    message (which may carry a secret path or payload), the baseline path, and
    any traceback are never surfaced.
    """
    import guard_ratchet as gr

    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    def _boom(*args, **kwargs):
        raise hostile_exc

    monkeypatch.setattr(target, _boom)

    with pytest.raises(SystemExit) as excinfo:
        gr.load_baseline_v2(baseline, _GUARD, 2)
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "RATCHET_BASELINE_UNREADABLE" in captured.err
    assert "SECRETS" not in combined
    assert "SECRET-PAYLOAD-MARKER-12345" not in combined
    assert "baseline.json" not in combined
    assert "Traceback" not in combined
    assert "RuntimeError" not in combined
    assert "exploded" not in combined


def test_v2_baseline_exists_probe_runtime_error_controlled(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture,
) -> None:
    """path.exists() raising RuntimeError -> controlled RATCHET_BASELINE_UNREADABLE.

    The existence probe at the top of ``load_baseline_v2`` shares the same
    sanitized error boundary as the read path: an unexpected probe failure
    (e.g. a hostile filesystem or a broken stat implementation) maps to the
    fixed controlled code and never leaks the exception class, the message
    (which may carry a secret path/payload), the baseline path, or a
    traceback.
    """
    import guard_ratchet as gr

    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    def _boom_exists(self, *args, **kwargs):
        raise RuntimeError(
            "stat exploded on C:\\Users\\SECRETS\\prod\\baseline.json"
        )

    monkeypatch.setattr(Path, "exists", _boom_exists)

    with pytest.raises(SystemExit) as excinfo:
        gr.load_baseline_v2(baseline, _GUARD, 2)
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "RATCHET_BASELINE_UNREADABLE" in captured.err
    assert "SECRETS" not in combined
    assert "baseline.json" not in combined
    assert "Traceback" not in combined
    assert "RuntimeError" not in combined
    assert "exploded" not in combined


@pytest.mark.parametrize(
    "target, exc_type, sentinel",
    [
        ("builtins.open", KeyboardInterrupt, None),
        ("json.load", KeyboardInterrupt, None),
        ("builtins.open", SystemExit, "baseline-load-not-swallowed"),
        ("json.load", SystemExit, "baseline-load-not-swallowed"),
    ],
    ids=[
        "open_keyboard_interrupt",
        "json_load_keyboard_interrupt",
        "open_system_exit",
        "json_load_system_exit",
    ],
)
def test_v2_baseline_load_base_exception_propagates(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture,
    target: str,
    exc_type: type,
    sentinel,
) -> None:
    """BaseException subclasses propagate unchanged (never silently swallowed).

    ``load_baseline_v2`` documents that SystemExit / KeyboardInterrupt are
    outside the ``except Exception`` contract: they are never converted into a
    controlled exit-2 diagnostic and the original exception is re-raised to
    the caller.  For SystemExit the original exit value is preserved.
    """
    import guard_ratchet as gr

    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [])

    def _boom(*args, **kwargs):
        if exc_type is SystemExit:
            raise exc_type(sentinel)
        raise exc_type("interrupt")

    monkeypatch.setattr(target, _boom)

    with pytest.raises(exc_type) as excinfo:
        gr.load_baseline_v2(baseline, _GUARD, 2)
    if exc_type is SystemExit:
        assert excinfo.value.code == sentinel

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "RATCHET_BASELINE_UNREADABLE" not in combined
    assert "Traceback" not in combined


# -- Baseline guard hardening (hostile / non-string guard) -----------------------


@pytest.mark.parametrize(
    "hostile_guard, marker",
    [
        (
            "db_access\nHOSTILE-SECRET-GUARD",
            "HOSTILE-SECRET-GUARD",
        ),  # newline-smuggled secret in the baseline guard value
        (["db_access"], "db_access"),  # non-string guard (list)
        ({"name": "HOSTILE-SECRET-GUARD"}, "HOSTILE-SECRET-GUARD"),  # non-string guard (object)
    ],
    ids=[
        "hostile_newline_guard",
        "non_string_guard_list",
        "non_string_guard_object",
    ],
)
def test_v2_baseline_hostile_guard_mismatch_fixed_message(
    tmp_path: Path, hostile_guard: object, marker: str
) -> None:
    """hostile / non-string baseline guard -> fixed controlled message, no leak.

    The v2 baseline guard mismatch diagnostic is a fixed controlled string:
    neither the baseline guard value (which may smuggle a newline plus secret
    text, or be a non-string) nor the requested guard name is ever
    interpolated, so hostile content cannot reach the diagnostic.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(
        baseline, hostile_guard, [_entry(_fingerprint_of(finding))]
    )

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_GUARD_MISMATCH" in result.stderr
    assert marker not in result.stderr
    assert "Traceback" not in result.stderr


# -- Bounded baseline strings (oversize / control / whitespace) ------------------


@pytest.mark.parametrize(
    "entry_overrides, expected_fragment, marker",
    [
        (
            {"fingerprint": "v2|" + ("FP-SECRET-" * 300)},
            "fingerprint exceeds maximum length 2048",
            "FP-SECRET",
        ),  # fingerprint over MAX_BASELINE_FINGERPRINT
        (
            {"reason": "REASON-SECRET-" * 80},
            "reason exceeds maximum length 512",
            "REASON-SECRET",
        ),  # review metadata over MAX_BASELINE_METADATA
        (
            {
                "fingerprint": (
                    "v2|db_access|DB_UNAUTHORIZED_MUTATION|"
                    "path=app/Worker.kt\x00FP-SECRET"
                )
            },
            "fingerprint contains control characters",
            "FP-SECRET",
        ),  # NUL control character in fingerprint
        (
            {"owner": "@owner\nOWNER-SECRET"},
            "owner contains control characters",
            "OWNER-SECRET",
        ),  # newline control character in review metadata
        (
            {"fingerprint": "  v2|WS-SECRET|path=app/Worker.kt"},
            "fingerprint must not have leading or trailing whitespace",
            "WS-SECRET",
        ),  # surrounding whitespace on fingerprint
    ],
    ids=[
        "oversized_fingerprint",
        "oversized_reason",
        "control_fingerprint",
        "control_owner",
        "whitespace_fingerprint",
    ],
)
def test_v2_baseline_bounded_string_violation_controlled(
    tmp_path: Path,
    entry_overrides: Dict,
    expected_fragment: str,
    marker: str,
) -> None:
    """oversized / control / whitespace baseline strings -> controlled exit 2.

    Baseline fingerprints and review metadata are length-capped and must be
    free of surrounding whitespace and control characters so hostile values
    can never inject report lines or unbounded payloads.  The raw value is
    never echoed -- only the fixed controlled diagnostic.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(_fingerprint_of(finding))
    entry.update(entry_overrides)
    _write_baseline_v2(baseline, _GUARD, [entry])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr
    assert expected_fragment in result.stderr
    assert marker not in result.stderr
    assert "Traceback" not in result.stderr


# -- Baseline fingerprint canonical v2 format ----------------------------------------


@pytest.mark.parametrize(
    "bad_fingerprint, expected_fragment, marker",
    [
        (
            "",
            "missing required field 'fingerprint'",
            None,
        ),  # empty fingerprint
        (
            " ",
            "missing required field 'fingerprint'",
            None,
        ),  # whitespace-only fingerprint
        (
            "v2|",
            "not a protocol-v2 fingerprint",
            None,
        ),  # truncated bare prefix: no semantic component after "v2|"
        (
            "v2| ",
            "must not have leading or trailing whitespace",
            None,
        ),  # prefix followed by whitespace
        (
            "v2",
            "not a protocol-v2 fingerprint",
            None,
        ),  # truncated: version marker without the pipe separator
        (
            "v3|db_access|DB_UNAUTHORIZED_MUTATION|path=app%2FWorker.kt",
            "not a protocol-v2 fingerprint",
            "v3|db_access",
        ),  # wrong version in the prefix
        (
            "xv2|db_access|DB_UNAUTHORIZED_MUTATION|path=app%2FWorker.kt",
            "not a protocol-v2 fingerprint",
            "xv2|db_access",
        ),  # wrong prefix (not v2)
        (
            42,
            "missing required field 'fingerprint'",
            None,
        ),  # non-string fingerprint
        (
            "not-a-v2-fingerprint",
            "not a protocol-v2 fingerprint",
            "not-a-v2-fingerprint",
        ),  # unrelated string
    ],
    ids=[
        "empty",
        "whitespace_only",
        "truncated_bare_prefix",
        "prefix_trailing_space",
        "truncated_missing_pipe",
        "wrong_version",
        "wrong_prefix",
        "non_string",
        "unrelated_string",
    ],
)
def test_v2_baseline_malformed_fingerprint_exits_two(
    tmp_path: Path, bad_fingerprint: object, expected_fragment: str, marker
) -> None:
    """malformed / non-canonical baseline fingerprints -> controlled exit 2.

    Baseline entry fingerprints must satisfy the SAME canonical v2 fingerprint
    validation contract as aggregated findings (guard_findings._FP_RE, applied
    with a full match -- never a startswith-only prefix check).  Empty,
    whitespace-only, bare-prefix (``v2|``), prefix-plus-whitespace (``v2| ``),
    wrong-prefix/version (``v3|``, ``xv2|``), truncated (missing semantic
    components), non-string, and unrelated values are all rejected with the
    controlled RATCHET_BASELINE_INVALID exit 2; the raw value is never echoed.
    """
    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    entry = _entry(bad_fingerprint)  # type: ignore[arg-type]
    _write_baseline_v2(baseline, _GUARD, [entry])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "RATCHET_BASELINE_INVALID" in result.stderr
    assert expected_fragment in result.stderr
    if marker is not None:
        assert str(marker) not in result.stderr, "raw malformed fingerprint was echoed"
    assert "Traceback" not in result.stderr


def test_v2_baseline_valid_canonical_fingerprint_passes(tmp_path: Path) -> None:
    """a canonical protocol-v2 fingerprint is accepted (exit 0 on pass).

    Positive control for the canonical fingerprint contract: a baseline entry
    whose fingerprint is a real v2 fingerprint
    (``v2|<guard>|<rule>|key=value|...``) passes entry validation and the
    unchanged comparison exits 0.
    """
    finding = _finding_dict()
    fp = _fingerprint_of(finding)
    assert fp.startswith("v2|db_access|DB_UNAUTHORIZED_MUTATION|")
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(fp, expires="2099-12-31")])

    result = _run_ratchet(
        _GUARD, [sys.executable, str(guard_py)], baseline, protocol=2, cwd=tmp_path
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "UNCHANGED: 1" in result.stdout
    assert "PASS" in result.stdout


# -- Baseline size bound (too many entries) --------------------------------------


def test_v2_baseline_too_many_entries_controlled(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture
) -> None:
    """entries beyond MAX_BASELINE_ENTRIES -> RATCHET_BASELINE_TOO_LARGE exit 2.

    The entry-count bound is enforced BEFORE any per-entry validation or set
    materialization: an oversized baseline is rejected as a whole with the
    controlled RATCHET_BASELINE_TOO_LARGE diagnostic, and the per-entry schema
    validator is never reached (the entries are deliberately non-objects).
    """
    import guard_ratchet as gr

    monkeypatch.setattr(gr, "MAX_BASELINE_ENTRIES", 10)
    baseline = _baseline(tmp_path)
    # Non-dict entries on purpose: the count bound must trigger before any
    # per-entry object validation would even start.
    _write_baseline_v2(baseline, _GUARD, ["not-an-entry"] * 11)

    with pytest.raises(SystemExit) as excinfo:
        gr.load_baseline_v2(baseline, _GUARD, 2)
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    assert "RATCHET_BASELINE_TOO_LARGE" in captured.err
    assert "must be an object" not in captured.err
    assert "not-an-entry" not in captured.err
    assert "Traceback" not in captured.err


# -- Bounded report preview -------------------------------------------------------


def test_v2_print_report_preview_is_bounded(capsys: pytest.CaptureFixture) -> None:
    """print_report_v2 prints counts plus a bounded first-N preview.

    Each section prints its count, exactly the first MAX_BASELINE_PREVIEW
    fingerprints, and a single bounded "... and N more (TOTAL)" tail line when
    the section is larger -- a fingerprint beyond the preview never reaches
    the report.
    """
    import guard_ratchet as gr

    preview = gr.MAX_BASELINE_PREVIEW
    extra = 10
    total = preview + extra
    new_fps = [
        f"v2|db_access|DB_UNAUTHORIZED_MUTATION|key=value{i}"
        for i in range(total)
    ]
    comparison = {
        "new_keys": new_fps,
        "new_occurrences": [],
        "resolved_keys": [],
        "resolved_occurrences": [],
        "unchanged": [],
    }

    status = gr.print_report_v2(_GUARD, [], [], comparison, [])

    captured = capsys.readouterr()
    out = captured.out
    assert f"  NEW_KEYS: {total}" in out
    fingerprint_lines = [
        line
        for line in out.splitlines()
        if line.startswith("    ") and not line.startswith("    ...")
    ]
    assert len(fingerprint_lines) == preview
    assert f"    ... and {extra} more ({total} total)" in out
    assert new_fps[0] in out
    assert new_fps[preview] not in out
    assert new_fps[-1] not in out
    assert status == "FAIL"


# -- Proposal size bound ----------------------------------------------------------


def test_propose_candidate_max_entries_proposal_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture
) -> None:
    """candidate generation over MAX_BASELINE_ENTRIES -> PROPOSAL_ERROR exit 2.

    The candidate generator enforces the same entry-count bound as the active
    baseline loader: a report whose aggregates would exceed the protocol limit
    fails with the controlled PROPOSAL_ERROR diagnostic, writes no candidate,
    and never modifies the active baseline.
    """
    import guard_ratchet as gr

    monkeypatch.setattr(gr, "MAX_BASELINE_ENTRIES", 5)
    candidate = tmp_path / "proposed_baseline.json"
    current = [
        SimpleNamespace(
            fingerprint=f"v2|db_access|DB_UNAUTHORIZED_MUTATION|key=k{i}",
            count=1,
            rule=_RULE,
        )
        for i in range(6)
    ]
    comparison = {
        "new_keys": [],
        "new_occurrences": [],
        "resolved_keys": [],
        "resolved_occurrences": [],
        "unchanged": [agg.fingerprint for agg in current],
    }

    with pytest.raises(SystemExit) as excinfo:
        gr._write_v2_candidate(
            candidate, _GUARD, 2, [], current, comparison, []
        )
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    assert "PROPOSAL_ERROR" in captured.err
    assert "exceed the maximum number of entries" in captured.err
    assert not candidate.exists(), "candidate was written despite PROPOSAL_ERROR"
    assert "Traceback" not in captured.err


@pytest.mark.parametrize(
    "target, hostile_text",
    [
        (
            "os.replace",
            "replace failed on C:\\Users\\SECRETS\\proposed_baseline.json",
        ),
        (
            "json.dump",
            "dump failed writing C:\\Users\\SECRETS\\proposed_baseline.json",
        ),
    ],
    ids=["os_replace_failure", "json_dump_failure"],
)
def test_propose_candidate_write_failure_atomic_and_controlled(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture,
    target: str,
    hostile_text: str,
) -> None:
    """candidate write/replace failure -> PROPOSAL_ERROR exit 2, no artifacts, no leak.

    Simulates an ``os.replace`` failure (the publish step) and a ``json.dump``
    failure (the write step) while generating a proposal candidate.  In both
    cases the v2 flow reports the bounded controlled PROPOSAL_ERROR
    diagnostic and exits 2 -- never echoing the candidate path, the temporary
    path, the exception text, or a traceback -- leaves NO partial candidate at
    the target path, leaves NO temporary candidate file behind, and never
    modifies the active baseline (byte-identical).
    """
    import guard_ratchet as gr

    finding = _finding_dict()
    guard_py = _guard_py(tmp_path)
    _write_mock_guard(guard_py, _report_dict(findings=[finding]), exit_code=1)
    baseline = _baseline(tmp_path)
    _write_baseline_v2(baseline, _GUARD, [_entry(_fingerprint_of(finding))])
    before = baseline.read_bytes()
    candidate = tmp_path / "proposed_baseline.json"

    def _boom(*args, **kwargs):
        raise RuntimeError(hostile_text)

    monkeypatch.setattr(target, _boom)

    args = SimpleNamespace(
        guard_name=_GUARD,
        update_baseline=False,
        timeout=10,
        output_summary=None,
    )
    with pytest.raises(SystemExit) as excinfo:
        gr._main_v2(
            args,
            [sys.executable, str(guard_py)],
            tmp_path,
            baseline,
            propose_path=candidate,
        )
    assert excinfo.value.code == 2

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "PROPOSAL_ERROR" in captured.err
    assert "SECRETS" not in combined
    assert "proposed_baseline.json" not in combined
    assert "Traceback" not in combined
    assert "RuntimeError" not in combined
    assert "replace failed" not in combined
    assert "dump failed" not in combined
    assert not candidate.exists(), "partial candidate left at the target path"
    leftovers = [
        p
        for p in tmp_path.iterdir()
        if p.name.startswith("cost-aggregator-candidate-")
    ]
    assert leftovers == [], f"temporary candidate files left behind: {leftovers}"
    assert baseline.read_bytes() == before, "active baseline was modified"
