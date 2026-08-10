#!/usr/bin/env python3
"""
test_guard_findings.py

Pytest tests for the canonical guard-finding model (scripts/ci/guard_findings.py)
as implemented for protocol v2 (docs/ci/GUARD_FINDING_PROTOCOL.md).

Tests verify:
  1. A valid v2 empty report roundtrips through dict and JSON with the exact
     envelope schema (schema, schema_version, guard, tool,
     fingerprint_profile, created_at, findings, diagnostics).
  2. A valid DB finding (rule `DB_UNAUTHORIZED_MUTATION`) roundtrips; symbol
     is optional and diagnostics carry controlled catalog codes only.
  3. The v2 fingerprint is stable when line/column/message change, while any
     semantic component (rule, kind, severity, path, symbol,
     diagnostic_codes) change is not. Delimiter characters are
     percent-encoded safely.
  4. Invalid paths (traversal, backslash traversal, empty/`.`/`..` segments,
     drive-relative) are rejected; Windows backslashes are normalized.
  5. Catalog enforcement: unknown rule/diagnostic codes and
     diagnostic-as-finding are rejected.
  6. Schema/version/guard mismatches and invalid guard identifiers are
     rejected.
  7. Required keys are enforced and unknown keys (including a stray
     `statistics` envelope field) are rejected on read.
  8. Duplicate fingerprints are rejected by the report, build, JSON, and
     dedupe paths; multiplicity is retained by aggregation and honored by
     `reject_duplicates=False`.
  9. Deterministic ordering matches `sorted_findings` regardless of input
     order.
 10. Atomic write / load JSON roundtrip preserves the report exactly;
     malformed JSON and missing files are rejected.
 11. Privacy bounds: control characters, NUL bytes, unstripped strings,
     non-finite numbers, and non-JSON values are rejected in every bounded
     field.

Run:
    python -m pytest scripts/ci/test_guard_findings.py -v
"""

import json
import os
import re
import sys
from pathlib import Path
from typing import Optional, Tuple

# Make this directory importable so the sibling guard_findings module can be
# imported regardless of how pytest runs.
_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import pytest  # noqa: E402

from guard_findings import (  # noqa: E402
    AtomicWriteError,
    CallableSymbol,
    DuplicateFindingError,
    FingerprintProfile,
    FINGERPRINT_VERSION,
    FrozenDict,
    GuardDiagnostic,
    GuardFinding,
    GuardRunReport,
    JsonValidationError,
    KIND_VIOLATION,
    KIND_WARNING,
    REPORT_SCHEMA,
    REPORT_SCHEMA_VERSION,
    SEVERITY_ERROR,
    SEVERITY_WARNING,
    SourceLocation,
    ValidationError,
    aggregate_findings,
    aggregate_report,
    build_report,
    canonicalize_path,
    compute_fingerprint,
    dedupe_findings,
    fingerprint_v2,
    load_report_json,
    sorted_findings,
    validate_report_dict,
    write_report_atomic,
)

_GUARD = "db_access"
_TOOL = "verify_db_access_boundaries.py"
_RULE = "DB_UNAUTHORIZED_MUTATION"
_PATH = "app/src/main/java/com/example/Worker.kt"
_SYMBOL = CallableSymbol(qualified_name="com.example.Worker.doWork", name="doWork")
_DIAGNOSTIC = GuardDiagnostic(
    code="DB_SIGNATURE_UNRESOLVED",
    message="exact callable signature cannot be resolved",
    data={"count": 1, "nested": {"ok": True}},
)


def _make_finding(
    *,
    rule: str = _RULE,
    kind: str = KIND_VIOLATION,
    severity: str = SEVERITY_ERROR,
    message: str = "Mutation is not owned by an exact DB policy entry",
    line: int = 42,
    column: int = 7,
    path: str = _PATH,
    symbol: Optional[CallableSymbol] = _SYMBOL,
    diagnostics: Tuple[GuardDiagnostic, ...] = (_DIAGNOSTIC,),
) -> GuardFinding:
    return GuardFinding(
        rule=rule,
        kind=kind,
        severity=severity,
        message=message,
        location=SourceLocation(path=path, line=line, column=column),
        symbol=symbol,
        diagnostics=diagnostics,
    )


def _report_dict_with_finding(**report_overrides):
    report = build_report(
        _GUARD,
        _TOOL,
        (_make_finding(),),
        created_at="2026-08-10T00:00:00Z",
    )
    data = report.to_dict()
    data.update(report_overrides)
    return data


# 1. Valid v2 empty report -----------------------------------------------------


def test_valid_v2_empty_report() -> None:
    report = build_report(_GUARD, _TOOL, ())
    assert report.schema == REPORT_SCHEMA
    assert report.schema_version == REPORT_SCHEMA_VERSION
    assert report.guard == _GUARD
    assert report.tool == _TOOL
    assert report.findings == ()
    assert report.diagnostics == ()
    assert report.fingerprint_profile.version == FINGERPRINT_VERSION == 2
    assert report.created_at is None

    data = report.to_dict()
    assert data["schema"] == REPORT_SCHEMA
    assert data["schema_version"] == 2
    assert data["guard"] == _GUARD
    # The implemented v2 envelope is exactly these keys (no `statistics`
    # field; a stray `statistics` key is rejected as unknown).
    assert set(data) == {
        "schema",
        "schema_version",
        "guard",
        "tool",
        "fingerprint_profile",
        "created_at",
        "findings",
        "diagnostics",
    }
    assert GuardRunReport.from_dict(data) == report
    assert GuardRunReport.from_dict(json.loads(report.to_json())) == report
    assert validate_report_dict(data) == report


# 2. Finding roundtrip ---------------------------------------------------------


def test_valid_db_finding_roundtrip() -> None:
    finding = _make_finding()
    assert finding.rule == _RULE
    assert finding.kind == KIND_VIOLATION
    assert finding.severity == SEVERITY_ERROR
    assert finding.fingerprint.startswith("v2:")
    assert GuardFinding.from_dict(finding.to_dict()) == finding
    assert (
        GuardFinding.from_dict(json.loads(json.dumps(finding.to_dict()))) == finding
    )


def test_finding_roundtrip() -> None:
    report = build_report(_GUARD, _TOOL, (_make_finding(),))
    loaded = GuardRunReport.from_dict(report.to_dict())
    assert loaded == report
    assert loaded.findings[0].fingerprint == _make_finding().fingerprint
    assert validate_report_dict(report.to_dict()) == report


def test_finding_without_symbol_roundtrip() -> None:
    finding = _make_finding(symbol=None)
    assert "symbol" not in finding.to_dict()
    report = build_report(_GUARD, _TOOL, (finding,))
    loaded = GuardRunReport.from_dict(report.to_dict())
    assert loaded.findings[0] == finding
    assert loaded.findings[0].symbol is None


# 3. Fingerprint stability and semantic components -----------------------------


def test_v2_fingerprint_stable_when_line_and_message_change() -> None:
    first = _make_finding(line=42, column=7, message="first wording")
    moved = _make_finding(line=999, column=0, message="completely reworded")
    assert first.fingerprint == moved.fingerprint
    assert first.fingerprint == fingerprint_v2(first) == compute_fingerprint(first)
    assert re.fullmatch(r"v2:[0-9a-f]{64}", first.fingerprint)
    assert first.fingerprint == first.to_dict()["fingerprint"]
    assert (
        GuardFinding.from_dict(first.to_dict()).fingerprint == first.fingerprint
    )
    # Explicit column-only movement is also excluded.
    different_column = _make_finding(line=42, column=123, message="first wording")
    assert different_column.fingerprint == first.fingerprint


@pytest.mark.parametrize(
    "change",
    [
        pytest.param({"rule": "DB_MISSING_WRITE_BARRIER"}, id="rule"),
        pytest.param({"kind": KIND_WARNING}, id="kind"),
        pytest.param({"severity": SEVERITY_WARNING}, id="severity"),
        pytest.param(
            {"path": "app/src/main/java/com/example/Dao.kt"}, id="path"
        ),
        pytest.param(
            {
                "symbol": CallableSymbol(
                    qualified_name="com.example.Dao.save", name="save"
                )
            },
            id="symbol",
        ),
        pytest.param(
            {
                "diagnostics": (
                    GuardDiagnostic(
                        code="DB_METHOD_BODY_UNSUPPORTED", data={}
                    ),
                )
            },
            id="diagnostic_codes",
        ),
    ],
)
def test_fingerprint_semantic_component_change(change: dict) -> None:
    base = _make_finding()
    altered = _make_finding(**change)
    assert altered.fingerprint != base.fingerprint


def test_fingerprint_profile_excludes_diagnostic_fields() -> None:
    profile = FingerprintProfile()
    assert profile.version == FINGERPRINT_VERSION == 2
    assert profile.algorithm == "sha256"
    assert profile.encoding == "percent-encoded"
    assert profile.prefix == "v2"
    assert set(profile.excluded_fields) == {"line", "column", "message"}
    assert profile.excluded_fields == ("column", "line", "message")


def test_fingerprint_delimiter_encoding_safe() -> None:
    path = "app/src/main/java/com/example/A&B=C.kt"
    finding = _make_finding(path=path, message="m")
    assert re.fullmatch(r"v2:[0-9a-f]{64}", finding.fingerprint)
    assert finding.fingerprint == fingerprint_v2(finding) == compute_fingerprint(finding)
    # Delimiters are encoded, not collapsed: semantic identity is preserved.
    moved = _make_finding(path=path, line=500, message="reworded")
    assert moved.fingerprint == finding.fingerprint
    other = _make_finding(path="app/src/main/java/com/example/AXBC.kt", message="m")
    assert other.fingerprint != finding.fingerprint


# 4. Path validation -----------------------------------------------------------


@pytest.mark.parametrize(
    "bad_path",
    [
        "../secret.txt",
        "..\\..\\etc/passwd",
        "app/src/../Main.kt",
        "./app/Main.kt",
        "/app//Main.kt",
        "C:relative",
        "C:/repo/../x",
        "",
    ],
)
def test_invalid_path_rejected(bad_path: str) -> None:
    with pytest.raises(ValidationError):
        canonicalize_path(bad_path)
    with pytest.raises(ValidationError):
        SourceLocation(path=bad_path, line=1)


def test_valid_absolute_and_relative_paths_accepted() -> None:
    assert (
        canonicalize_path("app/src/main/java/Worker.kt")
        == "app/src/main/java/Worker.kt"
    )
    resolved = canonicalize_path("/tmp/repo/Worker.kt")
    assert resolved
    assert os.path.isabs(resolved)


def test_windows_path_normalization() -> None:
    assert (
        canonicalize_path("app\\src\\main\\java\\Worker.kt")
        == "app/src/main/java/Worker.kt"
    )
    location = SourceLocation(path="app\\src\\main\\java\\Worker.kt", line=1)
    assert location.path == "app/src/main/java/Worker.kt"
    resolved = canonicalize_path("C:/repo/Worker.kt")
    assert os.path.isabs(resolved)


# 5. Catalog enforcement -------------------------------------------------------


def test_catalog_unknown_rule_and_diagnostic_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="GUARD_001")
    assert exc.value.code == "UNKNOWN_RULE"
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="MADE_UP_RULE")
    assert exc.value.code == "UNKNOWN_RULE"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="GUARD_001", data={})
    assert exc.value.code == "UNKNOWN_DIAGNOSTIC"
    # Rule codes are not registered as diagnostic codes (and vice versa).
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_UNAUTHORIZED_MUTATION", data={})
    assert exc.value.code == "UNKNOWN_DIAGNOSTIC"


def test_diagnostic_as_finding_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="DB_SIGNATURE_UNRESOLVED")
    assert exc.value.code == "DIAGNOSTIC_AS_FINDING"


def test_unknown_kind_severity_rejected() -> None:
    with pytest.raises(ValidationError):
        _make_finding(kind="unknown_kind")
    with pytest.raises(ValidationError):
        _make_finding(severity="unknown_severity")
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="bad rule!")
    assert exc.value.code == "INVALID_FORMAT"


# 6. Schema / version / guard mismatches ---------------------------------------


def test_schema_mismatch_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(
            schema="cost-aggregator.other", guard=_GUARD, tool=_TOOL
        )
    assert exc.value.code == "SCHEMA_MISMATCH"


def test_schema_version_mismatch_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(guard=_GUARD, tool=_TOOL, schema_version=1)
    assert exc.value.code == "SCHEMA_VERSION"
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(guard=_GUARD, tool=_TOOL, schema_version="2")
    assert exc.value.code == "SCHEMA_VERSION"
    # JSON read path rejects a full report with a wrong schema_version.
    data = build_report(_GUARD, _TOOL, (_make_finding(),)).to_dict()
    data["schema_version"] = 1
    with pytest.raises(ValidationError):
        validate_report_dict(data)


def test_guard_mismatch_rejected() -> None:
    finding = _make_finding()
    with pytest.raises(ValidationError) as exc:
        build_report("other_guard", _TOOL, (finding,))
    assert exc.value.code == "GUARD_MISMATCH"
    # Invalid report-level guard identifiers are also rejected.
    with pytest.raises(ValidationError) as exc:
        build_report("bad guard!", _TOOL, ())
    assert exc.value.code == "INVALID_FORMAT"


# 7. Required and unknown keys -------------------------------------------------


def test_required_keys_enforced() -> None:
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict({})
    assert exc.value.code == "MISSING_KEY"

    missing_tool = _report_dict_with_finding()
    del missing_tool["tool"]
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(missing_tool)
    assert exc.value.code == "MISSING_KEY"

    finding_data = _make_finding().to_dict()
    del finding_data["message"]
    with pytest.raises(JsonValidationError) as exc:
        GuardFinding.from_dict(finding_data)
    assert exc.value.code == "MISSING_KEY"

    diag_data = _DIAGNOSTIC.to_dict()
    del diag_data["data"]
    with pytest.raises(JsonValidationError) as exc:
        GuardDiagnostic.from_dict(diag_data)
    assert exc.value.code == "MISSING_KEY"


def test_unknown_keys_rejected() -> None:
    # A stray `statistics` envelope field is not part of the implemented v2
    # schema and must be rejected as unknown.
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(_report_dict_with_finding(statistics={}))
    assert exc.value.code == "UNKNOWN_KEY"

    data = _report_dict_with_finding()
    data["findings"][0]["guard_id"] = "worker"
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "UNKNOWN_KEY"

    finding_data = _make_finding().to_dict()
    finding_data["guard_id"] = "worker"
    with pytest.raises(JsonValidationError) as exc:
        GuardFinding.from_dict(finding_data)
    assert exc.value.code == "UNKNOWN_KEY"

    loc_data = _make_finding().location.to_dict()
    loc_data["end_line"] = 50
    with pytest.raises(JsonValidationError):
        SourceLocation.from_dict(loc_data)

    diag_data = _DIAGNOSTIC.to_dict()
    diag_data["path"] = "x.kt"
    with pytest.raises(JsonValidationError):
        GuardDiagnostic.from_dict(diag_data)


def test_stored_fingerprint_mismatch_rejected() -> None:
    data = build_report(_GUARD, _TOOL, (_make_finding(),)).to_dict()
    data["findings"][0]["fingerprint"] = "v2:" + "0" * 64
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "FINGERPRINT_MISMATCH"


# 8. Deterministic ordering ----------------------------------------------------


def test_deterministic_ordering() -> None:
    f_base = _make_finding(message="base file", line=10)
    f_other = _make_finding(
        message="other file",
        line=20,
        path="app/src/main/java/com/example/Dao.kt",
    )
    assert f_base.fingerprint != f_other.fingerprint
    report = build_report(_GUARD, _TOOL, (f_other, f_base))
    assert report.findings == sorted_findings((f_base, f_other))
    assert report.findings == sorted_findings((f_other, f_base))


# 9. Duplicate occurrence and multiplicity -------------------------------------


def test_duplicate_occurrence_rejected() -> None:
    f1 = _make_finding(line=10)
    f2 = _make_finding(line=11)  # same fingerprint: line/column/message excluded
    assert f1.fingerprint == f2.fingerprint
    with pytest.raises(DuplicateFindingError):
        GuardRunReport(
            guard=_GUARD,
            tool=_TOOL,
            findings=(f1, f2),
        )
    with pytest.raises(DuplicateFindingError):
        build_report(_GUARD, _TOOL, (f1, f2))
    with pytest.raises(DuplicateFindingError):
        dedupe_findings((f1, f2), reject_duplicates=True)
    # The JSON read path also rejects duplicate source occurrences.
    data = build_report(_GUARD, _TOOL, (f1,)).to_dict()
    data["findings"].append(f2.to_dict())
    with pytest.raises(DuplicateFindingError):
        GuardRunReport.from_dict(data)


def test_multiplicity_count_retained() -> None:
    f1 = _make_finding(line=10, message="occurrence one")
    f2 = _make_finding(line=20, message="occurrence two")
    assert f1.fingerprint == f2.fingerprint

    # reject_duplicates=False keeps the first occurrence per fingerprint.
    deduped = dedupe_findings((f1, f2), reject_duplicates=False)
    assert len(deduped) == 1
    assert deduped[0].fingerprint == f1.fingerprint

    # build_report collapses multiplicity when asked; default rejects.
    report = build_report(
        _GUARD,
        _TOOL,
        (f1, f2),
        reject_duplicates=False,
    )
    assert len(report.findings) == 1
    assert report.findings[0].fingerprint == f1.fingerprint
    assert aggregate_report(report)[0].count == 1


def test_aggregation_count_and_locations() -> None:
    f1 = _make_finding(line=10, message="occurrence one")
    f2 = _make_finding(line=20, message="occurrence two")
    assert f1.fingerprint == f2.fingerprint

    aggregated = aggregate_findings([f1, f2])
    assert len(aggregated) == 1
    agg = aggregated[0]
    assert agg.fingerprint == f1.fingerprint
    assert agg.count == 2
    assert len(agg.locations) == 2
    assert {loc.line for loc in agg.locations} == {10, 20}
    assert agg.guard_id == _GUARD
    assert agg.kind == KIND_VIOLATION
    assert agg.severity == SEVERITY_ERROR
    assert agg.symbols == ("com.example.Worker.doWork",)
    assert agg.diagnostic_codes == ("DB_SIGNATURE_UNRESOLVED",)

    report = build_report(_GUARD, _TOOL, (f1,))
    report_agg = aggregate_report(report)
    assert len(report_agg) == 1
    assert report_agg[0].count == 1
    assert report_agg[0].locations == (f1.location,)


# 10. Atomic write / load roundtrip --------------------------------------------


def test_atomic_write_load_roundtrip(tmp_path: Path) -> None:
    report = build_report(
        _GUARD,
        _TOOL,
        (_make_finding(),),
        created_at="2026-08-10T00:00:00Z",
    )
    target = tmp_path / "reports" / "report.json"
    target.parent.mkdir(parents=True)
    written = write_report_atomic(report, target)
    assert written == str(target)
    assert target.is_file()
    assert not list(tmp_path.glob("**/*.tmp"))
    assert load_report_json(target) == report
    # Rewriting produces byte-identical canonical JSON.
    assert target.read_text(encoding="utf-8") == report.to_json()

    with pytest.raises(AtomicWriteError):
        write_report_atomic(report, tmp_path / "no_such_dir" / "report.json")


# 11. Malformed JSON -----------------------------------------------------------


def test_malformed_json_rejected(tmp_path: Path) -> None:
    malformed = tmp_path / "bad.json"
    malformed.write_text("{ not valid json !!!", encoding="utf-8")
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(malformed)
    assert exc.value.code == "INVALID_JSON"

    schema_bad = tmp_path / "schema_bad.json"
    schema_bad.write_text('{"schema_version": 1}', encoding="utf-8")
    with pytest.raises(JsonValidationError):
        load_report_json(schema_bad)

    missing = tmp_path / "does_not_exist.json"
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(missing)
    assert exc.value.code == "MISSING_FILE"


# 12. Privacy bounds -----------------------------------------------------------


def test_privacy_bounds_enforced() -> None:
    # Control characters / NUL bytes are rejected in every bounded string field.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED", data={"raw": "line1\nline2"}
        )
    assert exc.value.code == "CONTROL_CHARACTER"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED", data={"raw": "nul\x00byte"}
        )
    assert exc.value.code == "NUL_BYTE"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", message=" leading space")
    assert exc.value.code == "UNSTRIPPED"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED", data={"nan": float("nan")}
        )
    assert exc.value.code == "NON_FINITE_NUMBER"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", data={"bad": {1, 2}})
    assert exc.value.code == "NOT_JSONABLE"
    # Finding messages and paths are bounded the same way.
    with pytest.raises(ValidationError) as exc:
        _make_finding(message="raw source snippet\nwith newline")
    assert exc.value.code == "CONTROL_CHARACTER"
    with pytest.raises(ValidationError) as exc:
        SourceLocation(path="app/src\0/main.kt", line=1)
    assert exc.value.code == "NUL_BYTE"
    # Diagnostic data is stored frozen/immutable.
    data = _DIAGNOSTIC.data
    assert isinstance(data, FrozenDict)
    assert data["count"] == 1
