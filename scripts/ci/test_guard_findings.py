#!/usr/bin/env python3
"""
test_guard_findings.py

Pytest tests for the protocol-v2 guard-finding model
(scripts/ci/guard_findings.py, docs/ci/GUARD_FINDING_PROTOCOL.md).

The suite exercises the clean v2 API against the real catalog
(scripts/ci/finding_rule_catalog.py):

  * Models: SourceLocation, CallableSymbol, GuardFinding, GuardDiagnostic,
    GuardRunReport, AggregatedFinding.
  * Envelope keys are exactly schema, schema_version, guard, findings,
    diagnostics, statistics (no tool/fingerprint_profile/created_at).
  * Functions: build_report, validate_report, fingerprint_finding,
    aggregate_findings, canonicalize_report, load_report_json, load_report,
    write_report_atomic, canonical_path.

Coverage map:

  1.  Valid empty v2 envelope roundtrip (exact key set, from_dict / JSON /
      validate_report).
  2.  Valid DB finding whose identity fields come from the rule catalog.
   3.  Unknown rule / unknown diagnostic / diagnostic-as-finding rejection;
       reports with unregistered guard names fail closed (UNKNOWN_GUARD)
       regardless of the findings/diagnostics content: empty, findings-only,
       or diagnostics-only reports all fail with the controlled UNKNOWN_GUARD
       code and never echo the guard name or report payload.
  4.  Unknown symbol kind rejection and `unknown` blocking symbol for
      resolved rules; unresolved required signatures (missing/empty owner,
      name, or parameters) are rejected with the controlled
      `UNRESOLVED_SYMBOL_BLOCKING` protocol/infrastructure error and the
      emitter must use the `DB_SIGNATURE_UNRESOLVED` diagnostic instead;
      error text is sanitized (never echoes raw symbol values).
  5.  Schema / version / guard / required-key / unknown-key failures.
  6.  Canonical relative paths; backslash normalization; absolute / drive /
      traversal rejection.
  7.  Deterministic finding sort independent of input order; symbol identity
      (owner/name/receiver/parameters/kind) and identity fields precede the
      location tie-breaker, and message is never a sort field.
  8.  Fingerprint excludes line/column/message/severity but changes for
       owner, name, receiver, parameter order, DAO, operation, and mutation
       kind.
  9.  Delimiter collision safety (percent-encoding of |, =, &, <none>).
  10.  Distinct-source occurrences with the same semantic fingerprint survive
       the report and aggregate by distinct-location count; exact duplicates
       (rule, path, location, symbol, identity) are rejected by the report and
       by aggregate_findings (never silently deduplicated).
  11.  Bounded/privacy validation: raw source, exception/user payload and
       non-JSON values are rejected.
  12.  Malformed JSON rejection and atomic write/load roundtrip.
  13.  Recursive controlled_context/statistics bounds: bounded scalars
       (strings/numbers/bools/null), dict/list depth and item-count limits,
       forbidden payload-like key names at every level.
  14.  Findings count limit (MAX_FINDINGS) enforced on build and read paths.
   15.  Sanitized errors: load_report/load_report_json/from_dict/atomic-write
        failures carry controlled codes and never echo filesystem paths,
        exception messages, or user values; from_dict rejects non-list
        findings/diagnostics and non-mapping statistics before iteration;
        write_report_atomic canonicalizes first and wraps path/filesystem
        failures in sanitized AtomicWriteError codes; is_file / parent-dir
        probe failures map to the fixed FILE_CHECK_FAILED / PARENT_CHECK_FAILED
        codes.
  16.  Deep immutability: validated mappings are recursively frozen to
       FrozenDict and sequences to tuples before storage in identity,
       controlled_context, and statistics; mutation after construction is
       rejected and serialization stays deterministic.  FrozenDict hashing
       is deterministic and stable across processes (subprocess hash
       contract: same hash output twice, mutation remains impossible) and
       canonicalizes equal-value numeric forms (1 vs 1.0, -0.0 vs 0.0,
       True vs 1) so equal mappings hash equally while unequal numeric
       values keep distinct hashes.
  17.  Structural rule identity: DB_FORBIDDEN_STRUCTURAL_OPERATION declares
       the complete callable identity (owner, name, receiver, parameters,
       kind) plus operation, and fingerprints are sensitive to extension
       receiver, parameter order, and kind.
  18.  Explicit diagnostic conversion contract: unresolved_symbol_diagnostic
       builds the controlled DB_SIGNATURE_UNRESOLVED diagnostic (exit 2),
       rejects resolved symbols and unregistered codes with ProtocolFailure,
       and unknown rules use the catalog-backed UNKNOWN_RULE code -- an
       unknown blocking symbol can never become a baseline-able GuardFinding
       or appear in a public serialized report.
  19.  Declared-order fingerprint: the exact fingerprint string follows the
       catalog-declared identity field order (not lexicographic sort);
       ``test_fingerprint_exact_documented_string`` asserts the verbatim
       expected fingerprint and ``test_fingerprint_declared_order_not_lexicographic``
       confirms the declared order differs from ``sorted()`` while the
       fingerprint respects it.
  20.  Read-path precedence: unknown-guard, schema-mismatch, and
       schema-version checks on the ``from_dict`` / ``validate_report`` path
       execute **before** any findings/diagnostics/statistics content is
       materialized; ``test_unknown_guard_precedes_malformed_content_on_read``
       and ``test_schema_and_version_validated_before_content_on_read`` prove
       that malformed or hostile content is never reached when the envelope
       metadata is invalid.

Run:
    python -m pytest scripts/ci/test_guard_findings.py -v
"""

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Mapping, Optional
from urllib.parse import quote

# Make this directory importable so the sibling guard_findings module can be
# imported regardless of how pytest runs.
_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import pytest  # noqa: E402

from finding_rule_catalog import GUARD_DB_ACCESS, known_rule, is_known_diagnostic  # noqa: E402
from guard_findings import (  # noqa: E402
    AggregatedFinding,
    AtomicWriteError,
    CallableSymbol,
    DIAGNOSTIC_SIGNATURE_UNRESOLVED,
    DuplicateFindingError,
    FingerprintProfile,
    FrozenDict,
    GuardDiagnostic,
    GuardFinding,
    GuardRunReport,
    JsonValidationError,
    KIND_FUNCTION,
    KIND_PROPERTY_GETTER,
    KIND_UNKNOWN,
    MAX_CONTEXT,
    MAX_CONTEXT_DEPTH,
    MAX_CONTEXT_ITEMS,
    MAX_FINDINGS,
    MAX_MESSAGE,
    MAX_NUMBER,
    ProtocolFailure,
    REPORT_SCHEMA,
    REPORT_SCHEMA_VERSION,
    SEVERITY_ERROR,
    SEVERITY_WARNING,
    SourceLocation,
    ValidationError,
    aggregate_findings,
    build_report,
    canonical_path,
    canonicalize_report,
    fingerprint_finding,
    load_report,
    load_report_json,
    unresolved_symbol_diagnostic,
    validate_report,
    write_report_atomic,
)

_GUARD = GUARD_DB_ACCESS  # "db_access" -- every catalog rule belongs to it.
_RULE = "DB_UNAUTHORIZED_MUTATION"
_PATH = "app/src/main/java/com/example/Worker.kt"

_DEFAULT_IDENTITY = {
    "dao": "AppDao",
    "accessor": "direct",
    "operation": "delete",
    "mutation_kind": "update",
    "call_form": "interface",
}

_SYMBOL = CallableSymbol(
    owner="com.example.Worker",
    name="doWork",
    receiver=None,
    parameters=("String", "long"),
    kind=KIND_FUNCTION,
)


def _symbol(**overrides) -> CallableSymbol:
    """Build a CallableSymbol with the canonical defaults plus overrides."""
    base = {
        "owner": "com.example.Worker",
        "name": "doWork",
        "receiver": None,
        "parameters": ("String", "long"),
        "kind": KIND_FUNCTION,
    }
    base.update(overrides)
    return CallableSymbol(**base)


def _identity_for(rule: str, **overrides) -> Dict[str, str]:
    """Build an identity mapping from the catalog-declared ``identity.*``
    fields for ``rule``, so no placeholder catalog values are hard-coded."""
    profile = known_rule(rule)
    if profile is None:
        return {}
    declared = {f[9:] for f in profile.identity_fields if f.startswith("identity.")}
    identity = {key: _DEFAULT_IDENTITY[key] for key in sorted(declared)}
    identity.update(overrides)
    return identity


def _make_finding(
    *,
    rule: str = _RULE,
    severity: str = SEVERITY_ERROR,
    message: str = "Mutation is not owned by an exact DB policy entry",
    line: int = 42,
    column: int = 7,
    path: str = _PATH,
    symbol: Optional[CallableSymbol] = None,
    identity: Optional[Mapping[str, str]] = None,
) -> GuardFinding:
    if symbol is None:
        symbol = _SYMBOL
    if identity is None:
        identity = _identity_for(rule)
    return GuardFinding(
        rule=rule,
        severity=severity,
        path=path,
        location=SourceLocation(line=line, column=column),
        symbol=symbol,
        identity=identity,
        message=message,
    )


def _field_text(finding, name):
    """Resolve one profile identity field to its canonical fingerprint text.

    Mirrors the module-private ``_field`` used by ``fingerprint_finding`` so
    tests can assert the exact declared-order component list without reaching
    into private internals.
    """
    if name == "path":
        return finding.path
    if name.startswith("symbol."):
        symbol = finding.symbol
        attr = name[7:]
        if attr == "owner":
            return symbol.owner
        if attr == "name":
            return symbol.name
        if attr == "receiver":
            return symbol.receiver if symbol.receiver is not None else "<none>"
        if attr == "parameters":
            return json.dumps(list(symbol.parameters), separators=(",", ":"))
        if attr == "kind":
            return symbol.kind
    if name.startswith("identity."):
        key = name[9:]
        if key in finding.identity:
            return finding.identity[key]
    raise AssertionError(f"unsupported identity field {name!r}")


def _fingerprint_field_parts(finding):
    """Expected ``key=value`` fingerprint parts in declared profile order."""
    profile = known_rule(finding.rule)
    assert profile is not None
    return [f"{name}={quote(_field_text(finding, name), safe='')}" for name in profile.identity_fields]


# 1. Valid empty v2 envelope ---------------------------------------------------


def test_valid_empty_v2_envelope() -> None:
    report = build_report(_GUARD, ())
    assert report.schema == REPORT_SCHEMA == "cost-aggregator.guard-findings"
    assert report.schema_version == REPORT_SCHEMA_VERSION == 2
    assert report.guard == _GUARD
    assert report.findings == ()
    assert report.diagnostics == ()
    assert isinstance(report.statistics, Mapping) and len(report.statistics) == 0

    data = report.to_dict()
    # The v2 envelope is exactly these keys (no tool/fingerprint_profile/created_at).
    assert set(data) == {
        "schema",
        "schema_version",
        "guard",
        "findings",
        "diagnostics",
        "statistics",
    }

    assert GuardRunReport.from_dict(data) == report
    assert GuardRunReport.from_dict(json.loads(json.dumps(data))) == report
    assert validate_report(data) == report
    assert validate_report(report) == report


# 2. Valid DB finding with catalog identity ------------------------------------


def test_valid_db_finding_with_catalog_identity() -> None:
    finding = _make_finding()
    profile = known_rule(_RULE)
    assert profile is not None
    declared = {f[9:] for f in profile.identity_fields if f.startswith("identity.")}
    assert set(finding.identity) == declared
    assert finding.identity["dao"] == "AppDao"
    assert finding.identity["operation"] == "delete"
    assert finding.identity["mutation_kind"] == "update"

    assert finding.rule == _RULE
    assert finding.severity == SEVERITY_ERROR
    assert finding.path == _PATH
    assert finding.location.line == 42
    assert finding.symbol.owner == "com.example.Worker"
    assert finding.symbol.name == "doWork"
    assert finding.symbol.kind == KIND_FUNCTION
    assert finding.fingerprint.startswith("v2|db_access|DB_UNAUTHORIZED_MUTATION|")

    restored = GuardFinding.from_dict(finding.to_dict())
    assert restored == finding
    assert GuardFinding.from_dict(json.loads(json.dumps(finding.to_dict()))) == finding
    assert restored.fingerprint == finding.fingerprint


def test_warning_severity_finding_roundtrip() -> None:
    finding = _make_finding(severity=SEVERITY_WARNING)
    assert finding.severity == SEVERITY_WARNING
    assert GuardFinding.from_dict(finding.to_dict()) == finding


def test_valid_report_with_finding_roundtrip() -> None:
    report = build_report(_GUARD, (_make_finding(),))
    assert len(report.findings) == 1
    assert report.findings[0].identity["dao"] == "AppDao"
    assert report.findings[0].identity["mutation_kind"] == "update"

    data = report.to_dict()
    assert data["findings"][0]["identity"]["dao"] == "AppDao"
    loaded = GuardRunReport.from_dict(data)
    assert loaded == report
    assert validate_report(data) == report
    assert loaded.findings[0].fingerprint == _make_finding().fingerprint


def test_report_with_diagnostic_roundtrip() -> None:
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    report = build_report(_GUARD, (_make_finding(),), diagnostics=(diag,))
    assert report.diagnostics == (diag,)

    loaded = GuardRunReport.from_dict(report.to_dict())
    assert loaded == report
    assert loaded.diagnostics[0].code == "DB_SIGNATURE_UNRESOLVED"
    assert loaded.diagnostics[0].path == _PATH
    assert isinstance(loaded.diagnostics[0].controlled_context, FrozenDict)
    assert loaded.diagnostics[0].controlled_context["count"] == "1"


# 3. Unknown rule / diagnostic / diagnostic-as-finding --------------------------


def test_unknown_rule_rejected() -> None:
    for bad_rule in ("MADE_UP_RULE", "GUARD_001"):
        with pytest.raises(ValidationError) as exc:
            _make_finding(rule=bad_rule)
        assert exc.value.code == "UNKNOWN_RULE"


def test_unknown_rule_never_echoes_hostile_rule() -> None:
    # The UNKNOWN_RULE failure uses a fixed controlled message on every public
    # path: a hostile rule code never appears in str(exc).
    hostile = "MADE_UP_RULE_SECRET"

    with pytest.raises(ValidationError) as exc:
        _make_finding(rule=hostile)
    assert exc.value.code == "UNKNOWN_RULE"
    message = str(exc.value)
    assert message == "rule is not registered in the rule catalog"
    assert hostile not in message
    assert "SECRET" not in message

    with pytest.raises(ValidationError) as exc:
        FingerprintProfile.from_rule(hostile)
    assert exc.value.code == "UNKNOWN_RULE"
    assert hostile not in str(exc.value)
    assert "SECRET" not in str(exc.value)

    with pytest.raises(ValidationError) as exc:
        AggregatedFinding(fingerprint="v2|db_access|MADE_UP_RULE_SECRET|", count=1, rule=hostile)
    assert exc.value.code == "UNKNOWN_RULE"
    assert hostile not in str(exc.value)
    assert "SECRET" not in str(exc.value)


def test_invalid_rule_format_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="db_unauthorized_mutation")
    assert exc.value.code == "INVALID_FORMAT"


def test_unknown_diagnostic_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="MADE_UP_DIAGNOSTIC", controlled_context={})
    assert exc.value.code == "UNKNOWN_DIAGNOSTIC"
    # A rule code is not registered as a diagnostic code.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_UNAUTHORIZED_MUTATION", controlled_context={})
    assert exc.value.code == "UNKNOWN_DIAGNOSTIC"


def test_diagnostic_as_finding_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="DB_SIGNATURE_UNRESOLVED")
    assert exc.value.code == "DIAGNOSTIC_AS_FINDING"


# 4. Symbol kinds ---------------------------------------------------------------


def test_unknown_symbol_kind_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        CallableSymbol(owner="a", name="b", receiver=None, parameters=(), kind="monkey_patch")
    assert exc.value.code == "INVALID_KIND"
    with pytest.raises(ValidationError) as exc:
        CallableSymbol(owner="a", name="b", receiver=None, parameters=(), kind=123)
    assert exc.value.code == "INVALID_KIND"


def test_unknown_kind_symbol_blocked_in_resolved_rules() -> None:
    # KIND_UNKNOWN is a valid kind value...
    symbol = CallableSymbol(owner="a", name="b", receiver=None, parameters=(), kind=KIND_UNKNOWN)
    assert symbol.kind == KIND_UNKNOWN
    # ...but every catalog rule requires symbol.* identity fields, so an
    # unresolved `unknown` symbol is blocking for a finding.
    with pytest.raises(ValidationError) as exc:
        _make_finding(symbol=symbol)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"


def test_unresolved_kind_unknown_symbol_blocked_in_finding() -> None:
    # KIND_UNKNOWN is a valid kind value, but any resolved rule that requires
    # symbol.* identity fields blocks it with the controlled
    # UNRESOLVED_SYMBOL_BLOCKING protocol/infrastructure error.
    symbol = CallableSymbol(owner="a", name="b", receiver=None, parameters=(), kind=KIND_UNKNOWN)
    with pytest.raises(ValidationError) as exc:
        _make_finding(symbol=symbol)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"


def test_blank_owner_and_name_rejected_before_finding() -> None:
    # Blank owner/name can never reach the finding-level unresolved check: the
    # bounded CallableSymbol constructor fails closed with EMPTY_STRING.
    with pytest.raises(ValidationError) as exc:
        CallableSymbol(owner="", name="b", receiver=None, parameters=(), kind=KIND_FUNCTION)
    assert exc.value.code == "EMPTY_STRING"
    with pytest.raises(ValidationError) as exc:
        CallableSymbol(owner="a", name="", receiver=None, parameters=(), kind=KIND_FUNCTION)
    assert exc.value.code == "EMPTY_STRING"


def test_blank_parameters_unresolved_symbol_blocked_in_finding() -> None:
    # An empty parameters tuple is a valid CallableSymbol, but a missing/empty
    # required signature component counts as unresolved: a finding in a
    # resolved rule is blocked with UNRESOLVED_SYMBOL_BLOCKING.
    blank_params = CallableSymbol(
        owner="com.example.Worker",
        name="doWork",
        receiver=None,
        parameters=(),
        kind=KIND_FUNCTION,
    )
    with pytest.raises(ValidationError) as exc:
        _make_finding(symbol=blank_params)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"


def test_unresolved_symbol_error_never_echoes_hostile_values() -> None:
    # The UNRESOLVED_SYMBOL_BLOCKING error carries only the controlled rule
    # code and the diagnostic constant; hostile owner/name/receiver/parameters
    # values never appear in the exception text.
    hostile = CallableSymbol(
        owner="com.example.HostileSecretOwner",
        name="leakyName",
        receiver="SecretReceiver",
        parameters=("HOSTILE_TYPE", "a|b&c"),
        kind=KIND_UNKNOWN,
    )
    with pytest.raises(ValidationError) as exc:
        _make_finding(symbol=hostile)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"
    message = str(exc.value)
    for fragment in (
        "HostileSecretOwner",
        "leakyName",
        "SecretReceiver",
        "HOSTILE_TYPE",
        "a|b&c",
    ):
        assert fragment not in message


def test_signature_unresolved_diagnostic_roundtrip() -> None:
    # The emitter must use the catalog diagnostic DIAGNOSTIC_SIGNATURE_UNRESOLVED
    # (DB_SIGNATURE_UNRESOLVED) instead of a finding; it roundtrips through the
    # report envelope with its controlled code and symbol field.
    diag = GuardDiagnostic(
        code=DIAGNOSTIC_SIGNATURE_UNRESOLVED,
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    report = build_report(_GUARD, (_make_finding(),), diagnostics=(diag,))
    assert report.diagnostics == (diag,)
    loaded = GuardRunReport.from_dict(report.to_dict())
    assert loaded == report
    assert loaded.diagnostics[0].code == DIAGNOSTIC_SIGNATURE_UNRESOLVED
    assert validate_report(report.to_dict()) == report


def test_signature_unresolved_diagnostic_as_finding_rejected() -> None:
    # A diagnostic code must never be emitted as a baseline-able finding.
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule=DIAGNOSTIC_SIGNATURE_UNRESOLVED)
    assert exc.value.code == "DIAGNOSTIC_AS_FINDING"


# 5. Schema / version / guard / required / unknown keys -------------------------


def test_unknown_severity_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(severity="unknown_severity")
    assert exc.value.code == "INVALID_SEVERITY"


def test_schema_mismatch_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(schema="cost-aggregator.other", guard=_GUARD)
    assert exc.value.code == "SCHEMA_MISMATCH"


def test_schema_mismatch_never_echoes_hostile_schema() -> None:
    # The SCHEMA_MISMATCH failure uses a fixed controlled message: the raw
    # schema value from an untrusted report never appears in str(exc).
    hostile = "cost-aggregator.HOSTILE_SCHEMA-<secret>"
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(schema=hostile, guard=_GUARD)
    assert exc.value.code == "SCHEMA_MISMATCH"
    message = str(exc.value)
    assert message == "schema does not match the expected report schema"
    assert hostile not in message
    assert "HOSTILE_SCHEMA" not in message
    assert "secret" not in message


def test_schema_version_mismatch_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(guard=_GUARD, schema_version=1)
    assert exc.value.code == "SCHEMA_VERSION"
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(guard=_GUARD, schema_version="2")
    assert exc.value.code == "SCHEMA_VERSION"
    with pytest.raises(ValidationError) as exc:
        build_report(_GUARD, (), schema_version=1)
    assert exc.value.code == "SCHEMA_VERSION"
    # The JSON read path rejects a report with a wrong schema_version too.
    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    data["schema_version"] = 1
    with pytest.raises(ValidationError):
        validate_report(data)


def test_unknown_guard_with_findings_rejected() -> None:
    # The guard registry check is unconditional: an unregistered guard fails
    # closed with UNKNOWN_GUARD even when the report carries findings.  (With
    # the current single-guard catalog every registered rule belongs to
    # db_access, so a known guard can never receive a finding from another
    # registered guard; the GUARD_MISMATCH invariant remains for future
    # multi-guard catalogs and is never shadowed by the registry check.)
    with pytest.raises(ValidationError) as exc:
        build_report("other_guard", (_make_finding(),))
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert "other_guard" not in message
    assert "DB_UNAUTHORIZED_MUTATION" not in message


def test_unknown_guard_never_echoes_hostile_guard() -> None:
    # The UNKNOWN_GUARD failure uses a fixed controlled message: a hostile
    # guard name never appears in str(exc).
    hostile = "hostile_guard_secret_42"
    with pytest.raises(ValidationError) as exc:
        build_report(hostile, ())
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert message == "guard is not registered in the report guard catalog"
    assert hostile not in message
    assert "secret" not in message


def test_empty_report_unregistered_guard_rejected() -> None:
    # Fail closed: an empty report claiming an unregistered guard is rejected,
    # even though there is no finding rule to tie the report to a guard.
    with pytest.raises(ValidationError) as exc:
        build_report("other_guard", ())
    assert exc.value.code == "UNKNOWN_GUARD"
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(
            {
                "schema": REPORT_SCHEMA,
                "schema_version": REPORT_SCHEMA_VERSION,
                "guard": "other_guard",
                "findings": [],
                "diagnostics": [],
                "statistics": {},
            }
        )
    assert exc.value.code == "UNKNOWN_GUARD"

    # The registered db_access guard remains valid for an empty report.
    empty = build_report(_GUARD, ())
    assert empty.findings == ()
    assert GuardRunReport.from_dict(empty.to_dict()) == empty


def test_diagnostics_only_unknown_guard_rejected_direct() -> None:
    # Direct GuardRunReport construction with only diagnostics still validates
    # the guard name: an unregistered guard fails closed with UNKNOWN_GUARD and
    # the fixed message never echoes the guard name, diagnostic code, symbol,
    # path, or any payload value.
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(guard="secret_guard", diagnostics=(diag,))
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert message == "guard is not registered in the report guard catalog"
    assert "secret_guard" not in message
    assert "DB_SIGNATURE_UNRESOLVED" not in message
    assert "com.example.Worker.doWork" not in message
    assert _PATH not in message


def test_diagnostics_only_unknown_guard_rejected_build_report() -> None:
    # build_report with diagnostics but no findings rejects an unregistered
    # guard with UNKNOWN_GUARD (the empty-findings bypass is closed).
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    with pytest.raises(ValidationError) as exc:
        build_report("secret_guard", (), diagnostics=(diag,))
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert "secret_guard" not in message
    assert _PATH not in message
    assert "com.example.Worker.doWork" not in message


def test_diagnostics_only_unknown_guard_rejected_from_dict() -> None:
    # The JSON read path (from_dict) enforces the same unconditional guard
    # registry check for a diagnostics-only report.
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    data = {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": "secret_guard",
        "findings": [],
        "diagnostics": [diag.to_dict()],
        "statistics": {},
    }
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert "secret_guard" not in message
    assert "DB_SIGNATURE_UNRESOLVED" not in message
    assert "com.example.Worker.doWork" not in message
    assert _PATH not in message


def test_diagnostics_only_unknown_guard_rejected_serialized_report(tmp_path: Path) -> None:
    # A serialized report file carrying only diagnostics under an unregistered
    # guard is rejected on load with UNKNOWN_GUARD; the raw guard name,
    # diagnostic code, symbol, and path never leak into the error.
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    report = build_report(_GUARD, (), diagnostics=(diag,))
    data = report.to_dict()
    data["guard"] = "secret_guard"
    target = tmp_path / "unknown_guard_diagnostics.json"
    target.write_text(json.dumps(data), encoding="utf-8")

    with pytest.raises(ValidationError) as exc:
        load_report_json(target)
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert message == "guard is not registered in the report guard catalog"
    assert "secret_guard" not in message
    assert "DB_SIGNATURE_UNRESOLVED" not in message
    assert "com.example.Worker.doWork" not in message
    assert _PATH not in message


def test_known_guard_diagnostics_only_report_remains_valid() -> None:
    # Positive control: a diagnostics-only report under the registered
    # db_access guard remains valid through direct construction, build_report,
    # from_dict, and the serialized roundtrip.
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path=_PATH,
        symbol="com.example.Worker.doWork",
        controlled_context={"count": "1"},
    )
    report = build_report(_GUARD, (), diagnostics=(diag,))
    assert report.findings == ()
    assert report.diagnostics == (diag,)

    direct = GuardRunReport(guard=_GUARD, diagnostics=(diag,))
    assert direct == report
    loaded = GuardRunReport.from_dict(report.to_dict())
    assert loaded == report
    assert validate_report(report.to_dict()) == report
    assert loaded.diagnostics[0].code == "DB_SIGNATURE_UNRESOLVED"


def test_invalid_guard_identifier_rejected() -> None:
    with pytest.raises(ValidationError) as exc:
        build_report("bad guard!", ())
    assert exc.value.code == "INVALID_FORMAT"
    with pytest.raises(ValidationError) as exc:
        build_report("", ())
    assert exc.value.code == "EMPTY_STRING"


def test_required_keys_enforced_on_read() -> None:
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict({})
    assert exc.value.code == "MISSING_KEY"

    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    del data["statistics"]
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "MISSING_KEY"

    fdata = _make_finding().to_dict()
    del fdata["message"]
    with pytest.raises(JsonValidationError) as exc:
        GuardFinding.from_dict(fdata)
    assert exc.value.code == "MISSING_KEY"

    ddata = GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={}).to_dict()
    del ddata["controlled_context"]
    with pytest.raises(JsonValidationError) as exc:
        GuardDiagnostic.from_dict(ddata)
    assert exc.value.code == "MISSING_KEY"

    sdata = _SYMBOL.to_dict()
    del sdata["kind"]
    with pytest.raises(JsonValidationError) as exc:
        CallableSymbol.from_dict(sdata)
    assert exc.value.code == "MISSING_KEY"

    ldata = SourceLocation(line=1).to_dict()
    del ldata["line"]
    with pytest.raises(JsonValidationError) as exc:
        SourceLocation.from_dict(ldata)
    assert exc.value.code == "MISSING_KEY"


def test_unknown_keys_rejected_on_read() -> None:
    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    data["tool"] = "verify_db_access_boundaries.py"
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "UNKNOWN_KEY"

    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    data["created_at"] = "2026-08-10T00:00:00Z"
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "UNKNOWN_KEY"

    fdata = _make_finding().to_dict()
    fdata["guard_id"] = "worker"
    with pytest.raises(JsonValidationError) as exc:
        GuardFinding.from_dict(fdata)
    assert exc.value.code == "UNKNOWN_KEY"

    ldata = SourceLocation(line=1).to_dict()
    ldata["file"] = "x.kt"
    with pytest.raises(JsonValidationError) as exc:
        SourceLocation.from_dict(ldata)
    assert exc.value.code == "UNKNOWN_KEY"

    ddata = GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={}).to_dict()
    ddata["message"] = "leak"
    with pytest.raises(JsonValidationError) as exc:
        GuardDiagnostic.from_dict(ddata)
    assert exc.value.code == "UNKNOWN_KEY"

    sdata = _SYMBOL.to_dict()
    sdata["qualified_name"] = "com.example.Worker.doWork"
    with pytest.raises(JsonValidationError) as exc:
        CallableSymbol.from_dict(sdata)
    assert exc.value.code == "UNKNOWN_KEY"


def test_validate_report_rejects_unknown_type() -> None:
    with pytest.raises(ValidationError) as exc:
        validate_report("not a report")
    assert exc.value.code == "REPORT_TYPE"


# F1: from_dict requires list-typed findings/diagnostics and mapping
# statistics before iterating (no raw TypeError on hostile JSON shapes).


def test_from_dict_rejects_non_list_findings_and_diagnostics() -> None:
    base = build_report(_GUARD, ()).to_dict()
    for bad in (None, {}, "findings", 42, ()):
        data = dict(base)
        data["findings"] = bad
        with pytest.raises(JsonValidationError) as exc:
            GuardRunReport.from_dict(data)
        assert exc.value.code == "FINDINGS_NOT_LIST"
    for bad in (None, {}, "diagnostics", 42, ()):
        data = dict(base)
        data["diagnostics"] = bad
        with pytest.raises(JsonValidationError) as exc:
            GuardRunReport.from_dict(data)
        assert exc.value.code == "DIAGNOSTICS_NOT_LIST"


def test_from_dict_rejects_non_mapping_statistics() -> None:
    base = build_report(_GUARD, ()).to_dict()
    for bad in (None, [], "statistics", 42, True):
        data = dict(base)
        data["statistics"] = bad
        with pytest.raises(JsonValidationError) as exc:
            GuardRunReport.from_dict(data)
        assert exc.value.code == "STATISTICS_NOT_MAPPING"


# F1: the JSON read path validates the registered guard (and schema/version)
# before materializing findings/diagnostics content.


def test_unknown_guard_precedes_malformed_content_on_read() -> None:
    # An unregistered guard fails closed with UNKNOWN_GUARD even when the
    # findings/diagnostics content is malformed or unknown: non-list
    # collections never mask the guard registry check, and the fixed message
    # never echoes the guard name or any payload content.
    hostile = "hostile_guard_secret_42"
    malformed = {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": hostile,
        "findings": 42,
        "diagnostics": None,
        "statistics": "not a mapping",
    }
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(malformed)
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert message == "guard is not registered in the report guard catalog"
    assert hostile not in message
    assert "42" not in message

    # Unknown guard also precedes malformed-but-list content: entries that
    # would fail materialization (unknown rule code, unknown diagnostic,
    # forbidden statistics key) are never reached.
    unknown_content = {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": hostile,
        "findings": [{"rule": "MADE_UP_RULE"}],
        "diagnostics": [{"code": "MADE_UP_DIAGNOSTIC"}],
        "statistics": {"user_payload": "raw"},
    }
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(unknown_content)
    assert exc.value.code == "UNKNOWN_GUARD"
    message = str(exc.value)
    assert message == "guard is not registered in the report guard catalog"
    assert "MADE_UP_RULE" not in message
    assert "MADE_UP_DIAGNOSTIC" not in message
    assert "user_payload" not in message


def test_schema_and_version_validated_before_content_on_read() -> None:
    # The top-level schema/version checks also run before any content checks.
    base = {
        "schema": "cost-aggregator.other",
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": _GUARD,
        "findings": 42,
        "diagnostics": None,
        "statistics": "x",
    }
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(base)
    assert exc.value.code == "SCHEMA_MISMATCH"

    base["schema"] = REPORT_SCHEMA
    base["schema_version"] = 1
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(base)
    assert exc.value.code == "SCHEMA_VERSION"


def test_known_guard_preserves_malformed_content_errors_on_read() -> None:
    # For a registered guard with valid schema/version the read path still
    # reports malformed content with its normal controlled codes.
    base = {
        "schema": REPORT_SCHEMA,
        "schema_version": REPORT_SCHEMA_VERSION,
        "guard": _GUARD,
    }
    data = dict(base, findings=42, diagnostics=[], statistics={})
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "FINDINGS_NOT_LIST"

    data = dict(base, findings=[], diagnostics=None, statistics={})
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "DIAGNOSTICS_NOT_LIST"

    data = dict(base, findings=[], diagnostics=[], statistics="x")
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "STATISTICS_NOT_MAPPING"


# 6. Canonical paths ------------------------------------------------------------


def test_canonical_relative_path_accepted() -> None:
    assert canonical_path("app/src/main/java/Worker.kt") == "app/src/main/java/Worker.kt"
    assert canonical_path("a/b/c.kt") == "a/b/c.kt"
    assert canonical_path(Path("app/src/main/java/Worker.kt")) == "app/src/main/java/Worker.kt"


def test_windows_backslash_normalized_in_paths() -> None:
    assert canonical_path("app\\src\\main\\java\\Worker.kt") == "app/src/main/java/Worker.kt"
    finding = _make_finding(path="app\\src\\main\\java\\Worker.kt")
    assert finding.path == "app/src/main/java/Worker.kt"
    diag = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        path="app\\src\\main\\java\\Worker.kt",
        controlled_context={},
    )
    assert diag.path == "app/src/main/java/Worker.kt"


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
        "a//b",
        "",
    ],
)
def test_non_canonical_paths_rejected(bad_path: str) -> None:
    with pytest.raises(ValidationError):
        canonical_path(bad_path)


def test_absolute_and_drive_paths_rejected_in_findings() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(path="/abs/repo/Worker.kt")
    assert exc.value.code == "NON_CANONICAL_PATH"
    with pytest.raises(ValidationError) as exc:
        _make_finding(path="C:/repo/Worker.kt")
    assert exc.value.code == "NON_CANONICAL_PATH"


# 7. Deterministic sort ----------------------------------------------------------


def test_deterministic_sort() -> None:
    f_dao = _make_finding(path="app/src/main/java/com/example/Dao.kt", line=10)
    f_service = _make_finding(path="app/src/main/java/com/example/Service.kt", line=20)
    f_worker = _make_finding(path="app/src/main/java/com/example/Worker.kt", line=30)
    inputs = (f_worker, f_dao, f_service)

    report_a = build_report(_GUARD, inputs)
    report_b = build_report(_GUARD, tuple(reversed(inputs)))
    assert report_a.findings == report_b.findings
    assert report_a.findings == tuple(sorted(inputs, key=lambda f: (f.rule, f.path)))
    assert [f.path.rsplit("/", 1)[-1] for f in report_a.findings] == [
        "Dao.kt",
        "Service.kt",
        "Worker.kt",
    ]


def test_symbol_identity_order_precedes_line_order() -> None:
    # Same path, same rule, same identity: different callable symbols at
    # different lines. Canonical sorting keys on symbol identity
    # (owner/name/receiver/parameters/kind) before the location tie-breaker,
    # so symbol order wins even when a later-line symbol sorts earlier by
    # name.
    same_path = "app/src/main/java/com/example/Repo.kt"
    early_line_zeta = _make_finding(
        path=same_path,
        line=10,
        symbol=_symbol(name="zetaMethod"),
    )
    late_line_alpha = _make_finding(
        path=same_path,
        line=500,
        symbol=_symbol(name="alphaMethod"),
    )

    report = build_report(_GUARD, (early_line_zeta, late_line_alpha))
    assert [f.symbol.name for f in report.findings] == ["alphaMethod", "zetaMethod"]
    assert [f.location.line for f in report.findings] == [500, 10]

    # Deterministic: reversing the input order yields the same canonical order.
    reversed_report = build_report(_GUARD, (late_line_alpha, early_line_zeta))
    assert reversed_report.findings == report.findings


def test_symbol_receiver_kind_parameters_order_precedes_line_order() -> None:
    # receiver, kind, and ordered parameters are symbol identity fields: they
    # sort before the location tie-breaker. A later-line finding with a
    # lexicographically smaller symbol identity field sorts first.
    same_path = "app/src/main/java/com/example/Repo.kt"

    # receiver: "<none>" sorts before "AppDao", so the line-900 finding first.
    no_receiver_late = _make_finding(
        path=same_path,
        line=900,
        symbol=_symbol(name="doWork", receiver=None, kind=KIND_FUNCTION),
    )
    receiver_early = _make_finding(
        path=same_path,
        line=5,
        symbol=_symbol(name="doWork", receiver="AppDao", kind=KIND_FUNCTION),
    )
    report = build_report(_GUARD, (no_receiver_late, receiver_early))
    assert [f.symbol.receiver for f in report.findings] == [None, "AppDao"]
    assert [f.location.line for f in report.findings] == [900, 5]

    # kind: "function" sorts before "property_getter", so the line-900
    # function finding sorts before the line-5 property getter.
    function_late = _make_finding(
        path=same_path,
        line=900,
        symbol=_symbol(name="apply", kind=KIND_FUNCTION),
    )
    getter_early = _make_finding(
        path=same_path,
        line=5,
        symbol=_symbol(name="apply", kind=KIND_PROPERTY_GETTER),
    )
    report = build_report(_GUARD, (function_late, getter_early))
    assert [f.symbol.kind for f in report.findings] == [KIND_FUNCTION, KIND_PROPERTY_GETTER]
    assert [f.location.line for f in report.findings] == [900, 5]

    # parameters: a shorter ordered parameter tuple is a prefix and sorts
    # before a longer one, so the line-900 single-parameter finding sorts
    # before the line-5 two-parameter finding.
    single_param_late = _make_finding(
        path=same_path,
        line=900,
        symbol=_symbol(name="load", parameters=("String",)),
    )
    two_param_early = _make_finding(
        path=same_path,
        line=5,
        symbol=_symbol(name="load", parameters=("String", "long")),
    )
    report = build_report(_GUARD, (single_param_late, two_param_early))
    assert [f.symbol.parameters for f in report.findings] == [
        ("String",),
        ("String", "long"),
    ]
    assert [f.location.line for f in report.findings] == [900, 5]


def test_identity_order_precedes_line_order() -> None:
    # identity fields (sorted by key) are canonical sort fields before the
    # location tie-breaker: "AppDao" < "OtherDao" wins even when the AppDao
    # finding is on a later line.
    same_path = "app/src/main/java/com/example/Repo.kt"
    app_dao_late = _make_finding(
        path=same_path,
        line=900,
        identity=_identity_for(_RULE, dao="AppDao"),
    )
    other_dao_early = _make_finding(
        path=same_path,
        line=5,
        identity=_identity_for(_RULE, dao="OtherDao"),
    )
    report = build_report(_GUARD, (other_dao_early, app_dao_late))
    assert [f.identity["dao"] for f in report.findings] == ["AppDao", "OtherDao"]
    assert [f.location.line for f in report.findings] == [900, 5]


def test_message_is_never_a_sort_field() -> None:
    # Two occurrences of the same semantic finding differ only in message and
    # line; canonical order keys on the location tie-breaker alone and never
    # on message text.
    reworded_later = _make_finding(line=30, message="zzz reworded")
    earlier = _make_finding(line=10, message="aaa original")
    report = build_report(_GUARD, (reworded_later, earlier))
    assert [f.location.line for f in report.findings] == [10, 30]
    assert [f.message for f in report.findings] == ["aaa original", "zzz reworded"]


# 8. Fingerprint excludes location/message; semantic components change it --------


def test_fingerprint_excludes_line_column_message() -> None:
    base = _make_finding(line=42, column=7, message="first wording")
    moved = _make_finding(line=999, column=123, message="completely reworded")
    assert base.fingerprint == moved.fingerprint
    assert base.fingerprint == fingerprint_finding(base)
    assert GuardFinding.from_dict(base.to_dict()).fingerprint == base.fingerprint


def test_severity_does_not_affect_fingerprint() -> None:
    # severity is diagnostic-only (protocol v2, section 7.4): an error and a
    # warning for the same semantic finding must produce identical
    # fingerprints so a severity flip never churns the baseline.
    error = _make_finding(severity=SEVERITY_ERROR)
    warning = _make_finding(severity=SEVERITY_WARNING)
    assert error.severity != warning.severity
    assert error.fingerprint == warning.fingerprint


def test_serialized_finding_has_no_fingerprint() -> None:
    data = _make_finding().to_dict()
    # The v2 finding envelope never serializes a fingerprint; it is derived.
    assert set(data) == {
        "rule",
        "severity",
        "path",
        "location",
        "symbol",
        "identity",
        "message",
    }
    assert "fingerprint" not in data
    # And a fingerprint supplied on read is rejected as an unknown key.
    data["fingerprint"] = _make_finding().fingerprint
    with pytest.raises(JsonValidationError) as exc:
        GuardFinding.from_dict(data)
    assert exc.value.code == "UNKNOWN_KEY"


def test_fingerprint_changes_with_semantic_components() -> None:
    base = _make_finding()
    assert base.fingerprint != _make_finding(symbol=_symbol(owner="com.example.Other")).fingerprint
    assert base.fingerprint != _make_finding(symbol=_symbol(name="otherMethod")).fingerprint
    assert base.fingerprint != _make_finding(symbol=_symbol(receiver="AppDao")).fingerprint
    assert base.fingerprint != _make_finding(symbol=_symbol(parameters=("String",))).fingerprint
    assert base.fingerprint != _make_finding(identity=_identity_for(_RULE, dao="OtherDao")).fingerprint
    assert base.fingerprint != _make_finding(identity=_identity_for(_RULE, operation="insert")).fingerprint
    assert base.fingerprint != _make_finding(identity=_identity_for(_RULE, mutation_kind="query")).fingerprint
    assert base.fingerprint != _make_finding(rule="DB_MISSING_WRITE_BARRIER").fingerprint


def test_fingerprint_parameter_order_sensitive() -> None:
    base = _make_finding(symbol=_symbol(parameters=("String", "long")))
    reordered = _make_finding(symbol=_symbol(parameters=("long", "String")))
    assert base.fingerprint != reordered.fingerprint


def test_fingerprint_exact_documented_string() -> None:
    # Protocol v2 section 7.2: the fingerprint is exactly
    # v2|<guard>|<rule>|key=value|... with identity fields in the
    # catalog-declared profile order (path, full callable symbol identity,
    # then declared identity.* fields) and values percent-encoded.
    finding = _make_finding()
    expected = (
        "v2|db_access|DB_UNAUTHORIZED_MUTATION|"
        "path=app%2Fsrc%2Fmain%2Fjava%2Fcom%2Fexample%2FWorker.kt|"
        "symbol.owner=com.example.Worker|"
        "symbol.name=doWork|"
        "symbol.receiver=%3Cnone%3E|"
        "symbol.parameters=%5B%22String%22%2C%22long%22%5D|"
        "identity.dao=AppDao|"
        "identity.accessor=direct|"
        "identity.operation=delete|"
        "identity.mutation_kind=update|"
        "identity.call_form=interface"
    )
    assert finding.fingerprint == expected


def test_fingerprint_fields_follow_declared_profile_order() -> None:
    # Every rule's fingerprint uses its own catalog-declared identity order:
    # the same path/symbol values placed under profiles with different
    # declared orders produce the exact documented ordering for each profile
    # (identity fields are never lexicographically re-sorted).
    for rule in (
        "DB_UNAUTHORIZED_MUTATION",
        "DB_MISSING_WRITE_BARRIER",
        "DB_FORBIDDEN_STRUCTURAL_OPERATION",
    ):
        finding = _make_finding(rule=rule)
        profile = known_rule(rule)
        assert profile is not None
        parts = finding.fingerprint.split("|")
        assert parts[0] == "v2"
        assert parts[1] == profile.guard
        assert parts[2] == rule
        assert parts[3:] == _fingerprint_field_parts(finding)


def test_fingerprint_declared_order_not_lexicographic() -> None:
    # For DB_FORBIDDEN_STRUCTURAL_OPERATION the declared profile order places
    # symbol.kind before identity.operation. A lexicographic sort of the
    # declared fields would place identity.operation first (i < s); the
    # fingerprint must follow the declared order exactly.
    profile = known_rule(_STRUCTURAL_RULE)
    assert profile is not None
    assert list(profile.identity_fields) != sorted(profile.identity_fields)
    finding = _make_finding(rule=_STRUCTURAL_RULE)
    keys = [part.split("=", 1)[0] for part in finding.fingerprint.split("|")[3:]]
    assert keys == list(profile.identity_fields)
    assert finding.fingerprint.index("symbol.kind=") < finding.fingerprint.index("identity.operation=")


def test_fingerprint_semantic_value_change_alters_fingerprint_and_preserves_position() -> None:
    # A semantic value change alters the fingerprint (protocol v2 section
    # 7.5) while the changed component stays in its declared position.
    base = _make_finding(identity=_identity_for(_RULE, dao="AppDao"))
    changed = _make_finding(identity=_identity_for(_RULE, dao="OtherDao"))
    assert base.fingerprint != changed.fingerprint
    base_parts = base.fingerprint.split("|")
    changed_parts = changed.fingerprint.split("|")
    assert len(base_parts) == len(changed_parts)
    dao_index = next(i for i, part in enumerate(base_parts) if part.startswith("identity.dao="))
    assert base_parts[:dao_index] == changed_parts[:dao_index]
    assert base_parts[dao_index].startswith("identity.dao=AppDao")
    assert changed_parts[dao_index].startswith("identity.dao=OtherDao")


# 9. Delimiter collision safety ---------------------------------------------------


def test_fingerprint_delimiter_collision_safe() -> None:
    # The | part delimiter inside a value is percent-encoded.
    delim = _make_finding(line=1, identity=_identity_for(_RULE, dao="a|b"))
    assert "a%7Cb" in delim.fingerprint
    assert "a|b" not in delim.fingerprint

    # = and & inside a value are encoded too.
    eq = _make_finding(line=1, identity=_identity_for(_RULE, dao="a=b&c"))
    assert "a%3Db%26c" in eq.fingerprint
    assert "a=b&c" not in eq.fingerprint

    # A raw delimiter-like value and the literal encoded form never collide.
    literal = _make_finding(line=1, identity=_identity_for(_RULE, dao="a%7Cb"))
    assert delim.fingerprint != literal.fingerprint

    # The <none> marker for a missing receiver is encoded, not emitted raw.
    none_marker = _make_finding(line=1)
    assert "%3Cnone%3E" in none_marker.fingerprint
    assert "<none>" not in none_marker.fingerprint


# 10. Distinct-source occurrences survive; exact duplicates are rejected --------


def test_distinct_locations_same_fingerprint_survive_report() -> None:
    f1 = _make_finding(line=10)
    f2 = _make_finding(line=20)  # same identity -> same fingerprint
    assert f1.fingerprint == f2.fingerprint
    assert f1.location != f2.location

    # Distinct source locations with the same semantic fingerprint are allowed.
    report = build_report(_GUARD, (f1, f2))
    assert len(report.findings) == 2
    assert GuardRunReport(guard=_GUARD, findings=(f1, f2)) == report

    # The JSON read path accepts them too.
    data = report.to_dict()
    assert len(data["findings"]) == 2
    loaded = GuardRunReport.from_dict(data)
    assert {f.location.line for f in loaded.findings} == {10, 20}


def test_exact_duplicate_rejected() -> None:
    f1 = _make_finding(line=10)
    f2 = _make_finding(line=10)  # identical (rule, path, location, symbol, identity)
    assert f1 == f2
    assert f1.fingerprint == f2.fingerprint

    with pytest.raises(DuplicateFindingError):
        GuardRunReport(guard=_GUARD, findings=(f1, f2))
    with pytest.raises(DuplicateFindingError):
        build_report(_GUARD, (f1, f2))

    # The JSON read path rejects exact duplicates too.
    data = build_report(_GUARD, (f1,)).to_dict()
    data["findings"].append(f2.to_dict())
    with pytest.raises(DuplicateFindingError):
        GuardRunReport.from_dict(data)

    # ``message`` is not part of the dedup key, so the same location with
    # different wording is still an exact duplicate.
    reworded = _make_finding(line=10, message="reworded message")
    with pytest.raises(DuplicateFindingError):
        build_report(_GUARD, (f1, reworded))


def test_reject_duplicates_false_keeps_first_occurrence() -> None:
    f1 = _make_finding(line=10)
    f2 = _make_finding(line=10)  # exact duplicate of f1
    report = build_report(_GUARD, (f1, f2), reject_duplicates=False)
    assert len(report.findings) == 1
    assert report.findings[0].fingerprint == f1.fingerprint


def test_same_identity_multiplicity_aggregation() -> None:
    f1 = _make_finding(line=10)
    f2 = _make_finding(line=20)
    assert f1.fingerprint == f2.fingerprint

    aggregated = aggregate_findings((f1, f2))
    assert len(aggregated) == 1
    agg = aggregated[0]
    assert agg.fingerprint == f1.fingerprint
    assert agg.count == 2
    assert agg.rule == _RULE
    assert {loc.line for loc in agg.locations} == {10, 20}

    # Distinct fingerprints group separately and sort deterministically by
    # fingerprint string ("AppDao" < "OtherDao").
    other = _make_finding(identity=_identity_for(_RULE, dao="OtherDao"), line=30)
    combined = aggregate_findings((other, f2, f1))
    assert len(combined) == 2
    assert [a.count for a in combined] == [2, 1]


def test_aggregate_findings_rejects_exact_duplicates() -> None:
    f1 = _make_finding(line=10)
    f2 = _make_finding(line=10)  # identical (rule, path, location, symbol, identity)
    assert f1 == f2
    assert f1.fingerprint == f2.fingerprint

    # Exact duplicates are rejected before fingerprint grouping, matching the
    # report-level duplicate contract; aggregation never silently dedupes.
    with pytest.raises(DuplicateFindingError) as exc:
        aggregate_findings((f1, f2))
    assert exc.value.code == "DUPLICATE_FINDING"

    # ``message`` is not part of the dedup key, so the same location with
    # different wording is still an exact duplicate for aggregation too.
    reworded = _make_finding(line=10, message="reworded message")
    with pytest.raises(DuplicateFindingError) as exc:
        aggregate_findings((f1, reworded))
    assert exc.value.code == "DUPLICATE_FINDING"

    # Distinct-location aggregation still counts one occurrence per location:
    # the exact-duplicate rejection must not change same-fingerprint counts.
    distinct = aggregate_findings((_make_finding(line=10), _make_finding(line=20)))
    assert len(distinct) == 1
    assert distinct[0].count == 2
    assert {loc.line for loc in distinct[0].locations} == {10, 20}


def test_duplicate_finding_never_echoes_hostile_values() -> None:
    # The DUPLICATE_FINDING failure uses a fixed controlled message on every
    # public path: the raw path, rule, symbol, identity, and location line
    # never appear in str(exc).
    hostile_path = "app/src/main/java/com/example/secret/HostileDao.kt"
    f1 = _make_finding(line=10, path=hostile_path)
    f2 = _make_finding(line=10, path=hostile_path)  # exact duplicate of f1

    for builder in (
        lambda: build_report(_GUARD, (f1, f2)),
        lambda: aggregate_findings((f1, f2)),
    ):
        with pytest.raises(DuplicateFindingError) as exc:
            builder()
        assert exc.value.code == "DUPLICATE_FINDING"
        message = str(exc.value)
        assert "HostileDao" not in message
        assert "secret" not in message
        assert "com.example" not in message
        assert "10" not in message

    # The JSON read path uses the same fixed message.
    data = build_report(_GUARD, (f1,)).to_dict()
    data["findings"].append(f2.to_dict())
    with pytest.raises(DuplicateFindingError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "DUPLICATE_FINDING"
    assert "HostileDao" not in str(exc.value)
    assert "10" not in str(exc.value)


# 11. Bounded / privacy validation -------------------------------------------------


def test_bounded_fields_reject_raw_source() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(message="raw source snippet\nwith newline")
    assert exc.value.code == "CONTROL_CHARACTER"
    with pytest.raises(ValidationError) as exc:
        _make_finding(message="nul\x00byte")
    assert exc.value.code == "NUL_BYTE"
    with pytest.raises(ValidationError) as exc:
        _make_finding(path="app/src\0main.kt")
    assert exc.value.code == "NUL_BYTE"
    with pytest.raises(ValidationError) as exc:
        _make_finding(message=" leading space")
    assert exc.value.code == "UNSTRIPPED"
    with pytest.raises(ValidationError) as exc:
        _make_finding(message="x" * (MAX_MESSAGE + 1))
    assert exc.value.code == "STRING_TOO_LONG"


def test_controlled_context_rejects_payload_values() -> None:
    # Exception/user payload objects are not allowed as context values; the
    # payload-smuggling key name itself is blocked as a forbidden context key.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context={"user_payload": {"amount": 99.99}},
        )
    assert exc.value.code == "FORBIDDEN_CONTEXT_KEY"
    # Raw keys (NUL/control/unstripped) are rejected by the frozen mapping.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"raw\x00key": "x"})
    assert exc.value.code == "NUL_BYTE"
    # Over-long context values are rejected.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context={"detail": "x" * (MAX_CONTEXT + 1)},
        )
    assert exc.value.code == "STRING_TOO_LONG"
    # Non-finite numbers and non-JSON values are rejected everywhere.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"value": float("nan")})
    assert exc.value.code == "NON_FINITE_NUMBER"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"value": {"a", "b"}})
    assert exc.value.code == "NOT_JSONABLE"

    # Valid context is frozen and roundtrips.
    diag = GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"count": "1"})
    assert isinstance(diag.controlled_context, FrozenDict)
    assert diag.controlled_context["count"] == "1"


def test_symbol_bounds() -> None:
    with pytest.raises(ValidationError) as exc:
        _symbol(owner="com.example\x00Worker")
    assert exc.value.code == "NUL_BYTE"
    with pytest.raises(ValidationError) as exc:
        _symbol(name=" doWork")
    assert exc.value.code == "UNSTRIPPED"
    with pytest.raises(ValidationError) as exc:
        CallableSymbol(owner="a", name="b", parameters="String")
    assert exc.value.code == "INVALID_PARAMETERS"
    with pytest.raises(ValidationError) as exc:
        CallableSymbol(owner="a", name="b", receiver=123)
    assert exc.value.code == "NOT_STRING"


def test_symbol_parameters_type_rejected() -> None:
    # parameters must be a list or tuple of bounded strings: None, a mapping,
    # an int, a bare string, or an arbitrary iterable (generator) all fail
    # closed with INVALID_PARAMETERS instead of leaking a raw TypeError.
    for bad_parameters in (
        None,
        {"String": "long"},  # mapping/dict
        42,  # int
        "String",  # single string
        (p for p in ("String", "long")),  # generator
    ):
        with pytest.raises(ValidationError) as exc:
            _symbol(parameters=bad_parameters)
        assert exc.value.code == "INVALID_PARAMETERS"


def test_symbol_parameters_valid_list_or_tuple_preserve_order() -> None:
    # Both a list and a tuple are valid; element order is preserved and
    # normalized to a tuple so fingerprints stay parameter-order sensitive.
    from_list = _symbol(parameters=["String", "long"])
    from_tuple = _symbol(parameters=("String", "long"))
    assert from_list.parameters == ("String", "long")
    assert from_tuple.parameters == ("String", "long")
    assert from_list == from_tuple
    assert from_list.to_dict()["parameters"] == ["String", "long"]


def test_identity_catalog_enforced() -> None:
    with pytest.raises(ValidationError) as exc:
        _make_finding(identity=_identity_for(_RULE, extra="x"))
    assert exc.value.code == "IDENTITY_UNDECLARED"
    with pytest.raises(ValidationError) as exc:
        identity = _identity_for(_RULE)
        del identity["operation"]
        _make_finding(identity=identity)
    assert exc.value.code == "IDENTITY_MISSING"
    with pytest.raises(ValidationError) as exc:
        _make_finding(identity=_identity_for(_RULE, dao=123))
    assert exc.value.code == "NOT_STRING"


# 12. Malformed JSON and atomic write/load roundtrip --------------------------------


def test_atomic_write_load_roundtrip(tmp_path: Path) -> None:
    report = build_report(_GUARD, (_make_finding(),))
    target = tmp_path / "reports" / "report.json"
    target.parent.mkdir(parents=True)

    written = write_report_atomic(target, report)
    assert written == str(target)
    assert target.is_file()
    assert not list(tmp_path.glob("**/*.tmp"))

    loaded = load_report_json(target)
    assert loaded == report
    assert loaded.findings[0].fingerprint == report.findings[0].fingerprint

    # Writing is idempotent: a second write/load still roundtrips exactly.
    write_report_atomic(target, report)
    assert load_report_json(target) == report

    with pytest.raises(AtomicWriteError):
        write_report_atomic(tmp_path / "no_such_dir" / "report.json", report)


def test_canonicalize_and_load_apis_roundtrip(tmp_path: Path) -> None:
    f1 = _make_finding(line=20)
    f2 = _make_finding(line=10)
    report = build_report(_GUARD, (f1, f2))
    assert [f.location.line for f in report.findings] == [10, 20]

    canonical = canonicalize_report(report)
    assert isinstance(canonical, GuardRunReport)
    assert canonical == report
    # Deterministic: canonicalizing an already-canonical report is stable.
    assert canonicalize_report(canonical) == canonical

    # canonicalize also validates a JSON-parsed dict and a loaded file.
    assert canonicalize_report(report.to_dict()) == report

    target = tmp_path / "canonical.json"
    write_report_atomic(target, report)
    assert load_report(target) == report
    assert load_report(target) == load_report_json(target)
    assert canonicalize_report(load_report(target)) == report

    with pytest.raises(ValidationError) as exc:
        canonicalize_report("not a report")
    assert exc.value.code == "REPORT_TYPE"


def test_canonicalize_report_independent_copy_and_write_uses_it(tmp_path: Path, monkeypatch) -> None:
    f1 = _make_finding(line=20)
    f2 = _make_finding(line=10)
    report = build_report(_GUARD, (f2, f1))
    assert [f.location.line for f in report.findings] == [10, 20]

    canonical = canonicalize_report(report)
    # A fresh, independent GuardRunReport copy -- never the same object --
    # with findings sorted deterministically.
    assert isinstance(canonical, GuardRunReport)
    assert canonical is not report
    assert canonical == report
    assert [f.location.line for f in canonical.findings] == [10, 20]
    # Deterministic and idempotent: canonicalizing the canonical copy is
    # stable and still returns another fresh object.
    again = canonicalize_report(canonical)
    assert again == canonical
    assert again is not canonical

    # write_report_atomic canonicalizes before serializing: spy on the
    # module-level canonicalize_report so the call is observable.
    calls = []
    original_canonicalize = canonicalize_report

    def _spy(value):
        result = original_canonicalize(value)
        calls.append(result)
        return result

    monkeypatch.setattr("guard_findings.canonicalize_report", _spy)
    target = tmp_path / "reports" / "report.json"
    target.parent.mkdir(parents=True)
    write_report_atomic(target, report)
    assert len(calls) == 1
    assert isinstance(calls[0], GuardRunReport)
    assert calls[0] == report
    # The serialized file roundtrips to the canonical report.
    assert load_report_json(target) == canonical


def test_write_failures_are_sanitized(tmp_path: Path) -> None:
    report = build_report(_GUARD, (_make_finding(),))

    # Non-report input -> controlled REPORT_TYPE (nothing is serialized).
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(tmp_path / "report.json", {"not": "a report"})
    assert exc.value.code == "REPORT_TYPE"

    # Non-path-like target -> controlled INVALID_PATH, never a raw TypeError.
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(12345, report)
    assert exc.value.code == "INVALID_PATH"

    # Missing parent directory -> controlled MISSING_PARENT; the raw
    # filesystem path and any OS error text must never leak.
    missing = tmp_path / "secret-dir" / "report.json"
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(missing, report)
    assert exc.value.code == "MISSING_PARENT"
    message = str(exc.value)
    assert "secret-dir" not in message
    assert "report.json" not in message

    # os.replace failure (the target already exists as a directory) ->
    # controlled WRITE_FAILED; the sibling temp file is cleaned up and no
    # raw path leaks into the error.
    parent = tmp_path / "sub"
    parent.mkdir()
    blocked = parent / "report.json"
    blocked.mkdir()
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(blocked, report)
    assert exc.value.code == "WRITE_FAILED"
    message = str(exc.value)
    assert "report.json" not in message
    assert "sub" not in message
    assert not list(parent.glob("*.tmp"))


def test_malformed_json_rejected(tmp_path: Path) -> None:
    malformed = tmp_path / "bad.json"
    malformed.write_text("{ not valid json !!!", encoding="utf-8")
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(malformed)
    assert exc.value.code == "INVALID_JSON"

    missing = tmp_path / "does_not_exist.json"
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(missing)
    assert exc.value.code == "MISSING_FILE"

    schema_bad = tmp_path / "schema_bad.json"
    schema_bad.write_text(
        json.dumps(
            {
                "schema": "cost-aggregator.other",
                "schema_version": 2,
                "guard": _GUARD,
                "findings": [],
                "diagnostics": [],
                "statistics": {},
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(ValidationError):
        load_report_json(schema_bad)


# 13. Recursive free-form bounds: depth, item count, numbers, forbidden keys ----
# 14. Findings count limit (MAX_FINDINGS) enforced before materialization -------
# 15. Sanitized load / unknown-key errors ----------------------------------------


def _nested_context(depth: int) -> Dict[str, Any]:
    """Build a nested dict chain of ``depth + 1`` levels ending in a scalar."""
    node: Dict[str, Any] = {"leaf": "x"}
    for _ in range(depth):
        node = {"n": node}
    return node


def test_controlled_context_depth_and_item_limits() -> None:
    # Nesting beyond MAX_CONTEXT_DEPTH is rejected with a controlled code.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context=_nested_context(MAX_CONTEXT_DEPTH),
        )
    assert exc.value.code == "CONTEXT_TOO_DEEP"

    # Exactly MAX_CONTEXT_DEPTH levels of nesting are still accepted.
    accepted = _nested_context(MAX_CONTEXT_DEPTH - 1)
    ok = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        controlled_context=accepted,
    )
    # Depth-agnostic content check: the accepted chain must survive freezing
    # unchanged regardless of how deep MAX_CONTEXT_DEPTH currently is.
    assert ok.controlled_context == accepted

    # The statistics mapping uses the same recursive depth limit.
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(guard=_GUARD, statistics=_nested_context(MAX_CONTEXT_DEPTH))
    assert exc.value.code == "CONTEXT_TOO_DEEP"

    # Dict and list item counts are bounded by MAX_CONTEXT_ITEMS.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context={"items": ["x"] * (MAX_CONTEXT_ITEMS + 1)},
        )
    assert exc.value.code == "CONTEXT_TOO_MANY"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context={f"key{i}": "x" for i in range(MAX_CONTEXT_ITEMS + 1)},
        )
    assert exc.value.code == "CONTEXT_TOO_MANY"


@pytest.mark.parametrize(
    "key",
    ("user_payload", "source", "exception", "sql", "ocr", "trace"),
)
def test_controlled_context_rejects_forbidden_keys_nested(key: str) -> None:
    # Payload-like key names are blocked at every nesting level of
    # controlled_context, not just at the top level.
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context={"section": {"details": {key: "raw"}}},
        )
    assert exc.value.code == "FORBIDDEN_CONTEXT_KEY"


def test_statistics_rejects_forbidden_key_nested() -> None:
    # statistics is validated with the same recursive free-form guard.
    with pytest.raises(ValidationError) as exc:
        GuardRunReport(
            guard=_GUARD,
            statistics={"nested": {"user_payload": {"amount": 1}}},
        )
    assert exc.value.code == "FORBIDDEN_CONTEXT_KEY"


def test_controlled_context_rejects_nonfinite_and_huge_numbers() -> None:
    # Non-finite floats are rejected, even when nested.
    for bad in (float("inf"), float("-inf")):
        with pytest.raises(ValidationError) as exc:
            GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"value": bad})
        assert exc.value.code == "NON_FINITE_NUMBER"
    with pytest.raises(ValidationError) as exc:
        GuardDiagnostic(
            code="DB_SIGNATURE_UNRESOLVED",
            controlled_context={"nested": {"value": float("inf")}},
        )
    assert exc.value.code == "NON_FINITE_NUMBER"

    # Integers and floats beyond MAX_NUMBER are rejected by magnitude.
    for bad in (MAX_NUMBER + 1, -MAX_NUMBER - 1, 1e300):
        with pytest.raises(ValidationError) as exc:
            GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"value": bad})
        assert exc.value.code == "NUMBER_OUT_OF_RANGE"

    # Exactly MAX_NUMBER is still accepted.
    ok = GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context={"value": MAX_NUMBER})
    assert ok.controlled_context["value"] == MAX_NUMBER


def test_max_findings_limit_enforced_before_materialization() -> None:
    # The read path checks the raw findings list length before parsing each
    # entry into a GuardFinding: the oversized entries here are empty dicts
    # that would fail materialization with MISSING_KEY, but the report rejects
    # the payload by length alone with TOO_MANY_FINDINGS.
    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    data["findings"] = [{}] * (MAX_FINDINGS + 1)
    with pytest.raises(ValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "TOO_MANY_FINDINGS"
    with pytest.raises(ValidationError) as exc:
        validate_report(data)
    assert exc.value.code == "TOO_MANY_FINDINGS"


def test_load_errors_do_not_leak_path_or_exception(tmp_path: Path) -> None:
    missing = tmp_path / "secret-dir" / "report.json"
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(missing)
    assert exc.value.code == "MISSING_FILE"
    message = str(exc.value)
    assert "secret-dir" not in message
    assert "report.json" not in message
    assert "No such file" not in message

    malformed = tmp_path / "bad.json"
    malformed.write_text("{ nope: 'still not json' !!!", encoding="utf-8")
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(malformed)
    assert exc.value.code == "INVALID_JSON"
    message = str(exc.value)
    assert "bad.json" not in message
    # The raw parser message (e.g. "Expecting property name...") must not leak.
    assert "Expecting" not in message
    assert "property name" not in message


def test_unknown_key_error_does_not_echo_key_or_value() -> None:
    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    data["secret_field"] = "raw sensitive payload"
    with pytest.raises(JsonValidationError) as exc:
        GuardRunReport.from_dict(data)
    assert exc.value.code == "UNKNOWN_KEY"
    message = str(exc.value)
    assert "secret_field" not in message
    assert "raw sensitive" not in message

    fdata = _make_finding().to_dict()
    fdata["leaky_key"] = {"amount": 12345}
    with pytest.raises(JsonValidationError) as exc:
        GuardFinding.from_dict(fdata)
    assert exc.value.code == "UNKNOWN_KEY"
    message = str(exc.value)
    assert "leaky_key" not in message
    assert "amount" not in message


# 16. Path-like hardening: custom path objects, invalid path types, deep JSON ----
# 17. Sanitized error output: fixed controlled codes/messages, no raw text -------


class _CustomPathLike:
    """Minimal os.PathLike stand-in returning a str path."""

    def __init__(self, value):
        self._value = value

    def __fspath__(self):
        return self._value


class _HostilePathLike:
    """Path-like whose repr/payload must never reach error text."""

    def __repr__(self):
        return "HOSTILE-REPR-SECRET"

    def __fspath__(self):
        return 12345  # invalid fspath result -> TypeError from os.fspath


class _RaisingPathLike:
    """Path-like whose __fspath__ raises an unexpected RuntimeError."""

    def __repr__(self):
        return "RAISING-PATH-REPR-SECRET"

    def __fspath__(self):
        raise RuntimeError("secret fspath conversion failure")


def test_custom_path_like_objects_supported(tmp_path: Path) -> None:
    # Objects implementing __fspath__ (os.PathLike) are accepted by both the
    # atomic-write and load APIs, just like plain str/Path values.
    report = build_report(_GUARD, (_make_finding(),))
    target = tmp_path / "custom.json"

    written = write_report_atomic(_CustomPathLike(str(target)), report)
    assert written == str(target)
    assert target.is_file()

    loaded = load_report_json(_CustomPathLike(str(target)))
    assert loaded == report


@pytest.mark.parametrize("bad", (12345, None, b"bytes.json", object()))
def test_load_report_json_invalid_path_type_controlled(bad: object) -> None:
    # Invalid path types fail closed with INVALID_PATH; the raw TypeError,
    # the object repr, and any OS text are never echoed.
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(bad)
    assert exc.value.code == "INVALID_PATH"
    message = str(exc.value)
    assert message == "target path must be a path-like value"
    assert "TypeError" not in message
    assert repr(bad) not in message


def test_invalid_custom_path_like_controlled(tmp_path: Path) -> None:
    # A custom __fspath__ returning a non-string (with a hostile repr) is
    # rejected with INVALID_PATH on both read and write; the repr, the raw
    # TypeError, and the invalid fspath result never leak.
    report = build_report(_GUARD, (_make_finding(),))
    hostile = _HostilePathLike()
    for call, error_cls in (
        (lambda: load_report_json(hostile), JsonValidationError),
        (lambda: write_report_atomic(hostile, report), AtomicWriteError),
    ):
        with pytest.raises(error_cls) as exc:
            call()
        assert exc.value.code == "INVALID_PATH"
        message = str(exc.value)
        assert message == "target path must be a path-like value"
        assert "HOSTILE" not in message
        assert "SECRET" not in message
        assert "TypeError" not in message


def test_raising_fspath_hook_sanitized(tmp_path: Path) -> None:
    # A custom __fspath__ that raises an unexpected RuntimeError (instead of
    # returning a value) is mapped to the fixed INVALID_PATH code on both
    # read and write; the raw exception text, the hostile repr, and any
    # "RuntimeError" wording never leak.
    report = build_report(_GUARD, (_make_finding(),))
    hostile = _RaisingPathLike()
    for call, error_cls in (
        (lambda: load_report_json(hostile), JsonValidationError),
        (lambda: write_report_atomic(hostile, report), AtomicWriteError),
    ):
        with pytest.raises(error_cls) as exc:
            call()
        assert exc.value.code == "INVALID_PATH"
        message = str(exc.value)
        assert message == "target path must be a path-like value"
        assert "secret fspath conversion failure" not in message
        assert "RAISING" not in message
        assert "SECRET" not in message
        assert "RuntimeError" not in message


def test_read_hook_runtime_error_sanitized(tmp_path: Path, monkeypatch) -> None:
    # A read hook that raises an unexpected RuntimeError (a patched
    # Path.read_text) is mapped to the fixed READ_FAILED code; the raw
    # exception text and the target path never leak.
    report = build_report(_GUARD, (_make_finding(),))
    target = tmp_path / "read_hook.json"
    write_report_atomic(target, report)

    def _raising_read_text(self, *args, **kwargs):
        raise RuntimeError("secret read hook failure")

    monkeypatch.setattr("guard_findings.Path.read_text", _raising_read_text)
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(target)
    assert exc.value.code == "READ_FAILED"
    message = str(exc.value)
    assert message == "failed to read report file"
    assert "secret read hook failure" not in message
    assert "read_hook.json" not in message
    assert "RuntimeError" not in message


def test_is_file_probe_failure_sanitized(tmp_path: Path, monkeypatch) -> None:
    # A probe of the target file (Path.is_file) that raises an unexpected
    # exception is mapped to the fixed FILE_CHECK_FAILED code; the raw
    # exception text and the target path never leak.
    target = tmp_path / "probe.json"
    target.write_text("{}", encoding="utf-8")

    def _raising_is_file(self):
        raise RuntimeError("secret is_file probe failure")

    monkeypatch.setattr("guard_findings.Path.is_file", _raising_is_file)
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(target)
    assert exc.value.code == "FILE_CHECK_FAILED"
    message = str(exc.value)
    assert message == "failed to inspect report file"
    assert "secret is_file probe failure" not in message
    assert "probe.json" not in message
    assert "RuntimeError" not in message


def test_parent_dir_probe_failure_sanitized(tmp_path: Path, monkeypatch) -> None:
    # A probe of the parent directory (Path.is_dir) that raises an unexpected
    # exception is mapped to the fixed PARENT_CHECK_FAILED code; the raw
    # exception text and the target path never leak and no temp file is left
    # behind (the probe fails before the temp file is created).
    report = build_report(_GUARD, (_make_finding(),))
    parent = tmp_path / "sub"
    parent.mkdir()
    target = parent / "probe.json"

    def _raising_is_dir(self):
        raise RuntimeError("secret is_dir probe failure")

    monkeypatch.setattr("guard_findings.Path.is_dir", _raising_is_dir)
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(target, report)
    assert exc.value.code == "PARENT_CHECK_FAILED"
    message = str(exc.value)
    assert message == "failed to inspect target parent directory"
    assert "secret is_dir probe failure" not in message
    assert "probe.json" not in message
    assert "sub" not in message
    assert "RuntimeError" not in message
    assert not list(parent.glob("*.tmp"))


def test_write_hook_runtime_error_sanitized(tmp_path: Path, monkeypatch) -> None:
    # A write/fsync hook that raises an unexpected RuntimeError is mapped to
    # the fixed WRITE_FAILED code; the sibling temp file is cleaned up and
    # the raw exception text / paths never leak.
    report = build_report(_GUARD, (_make_finding(),))
    parent = tmp_path / "sub"
    parent.mkdir()
    target = parent / "write_hook.json"

    def _raising_fsync(fileno):
        raise RuntimeError("secret fsync hook failure")

    monkeypatch.setattr("guard_findings.os.fsync", _raising_fsync)
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(target, report)
    assert exc.value.code == "WRITE_FAILED"
    message = str(exc.value)
    assert message == "failed to write report atomically"
    assert "secret fsync hook failure" not in message
    assert "write_hook.json" not in message
    assert "sub" not in message
    assert "RuntimeError" not in message
    assert not list(parent.glob("*.tmp"))


def test_replace_hook_runtime_error_sanitized(tmp_path: Path, monkeypatch) -> None:
    # An os.replace hook that raises an unexpected RuntimeError is mapped to
    # the fixed WRITE_FAILED code; the sibling temp file is cleaned up and
    # the raw exception text / paths never leak.
    report = build_report(_GUARD, (_make_finding(),))
    parent = tmp_path / "sub"
    parent.mkdir()
    target = parent / "replace_hook.json"

    def _raising_replace(src, dst):
        raise RuntimeError("secret replace hook failure")

    monkeypatch.setattr("guard_findings.os.replace", _raising_replace)
    with pytest.raises(AtomicWriteError) as exc:
        write_report_atomic(target, report)
    assert exc.value.code == "WRITE_FAILED"
    message = str(exc.value)
    assert message == "failed to write report atomically"
    assert "secret replace hook failure" not in message
    assert "replace_hook.json" not in message
    assert "sub" not in message
    assert "RuntimeError" not in message
    assert not list(parent.glob("*.tmp"))


def test_system_exit_and_keyboard_interrupt_not_swallowed() -> None:
    # Sanitization must never swallow BaseException subclasses: SystemExit and
    # KeyboardInterrupt raised by a custom __fspath__ propagate unchanged.
    class _ExitPath:
        def __fspath__(self):
            raise SystemExit(7)

    class _InterruptPath:
        def __fspath__(self):
            raise KeyboardInterrupt()

    with pytest.raises(SystemExit) as exc:
        load_report_json(_ExitPath())
    assert exc.value.code == 7

    with pytest.raises(KeyboardInterrupt):
        load_report_json(_InterruptPath())


def test_deep_malformed_json_controlled(tmp_path: Path) -> None:
    # A JSON document nested far beyond the parser recursion limit must be
    # converted to a controlled INVALID_JSON code with a fixed message; the
    # raw RecursionError text is never exposed.
    deep = tmp_path / "deep.json"
    deep.write_text("[" * 200_000 + "]" * 200_000, encoding="utf-8")
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(deep)
    assert exc.value.code == "INVALID_JSON"
    message = str(exc.value)
    assert message == "report file is not valid JSON"
    assert "RecursionError" not in message
    assert "maximum recursion" not in message


def test_malformed_nested_report_type_remains_json_validation_error(tmp_path: Path) -> None:
    # A document that parses as JSON but has a structurally invalid nested
    # report type (a non-object findings entry) must raise the underlying
    # JsonValidationError from from_dict, not be remapped to INVALID_JSON or
    # READ_FAILED.
    data = build_report(_GUARD, (_make_finding(),)).to_dict()
    data["findings"] = [42]
    target = tmp_path / "nested_type.json"
    target.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(JsonValidationError) as exc:
        load_report_json(target)
    assert exc.value.code == "NOT_OBJECT"
    message = str(exc.value)
    assert "42" not in message


# 16. Deep immutability: recursive freeze before storage -------------------------


def test_frozen_dict_deeply_copies_and_freezes_nested_data() -> None:
    # A nested dict/list passed to FrozenDict is deep-copied: mutating the
    # caller's original after construction never affects the frozen mapping.
    source = {"nested": {"count": 1}, "items": ["a", "b"]}
    frozen = FrozenDict(source)
    source["nested"]["count"] = 999
    source["items"].append("c")

    assert isinstance(frozen["nested"], FrozenDict)
    assert isinstance(frozen["items"], tuple)
    assert frozen["nested"]["count"] == 1
    assert frozen["items"] == ("a", "b")

    # Mutation attempts after construction fail closed (no __setitem__ on
    # FrozenDict, no mutation on the tuple).
    with pytest.raises(TypeError):
        frozen["nested"]["count"] = 999
    with pytest.raises(TypeError):
        frozen["key"] = "x"
    with pytest.raises(AttributeError):
        frozen["items"].append("c")  # type: ignore[attr-defined]


def test_frozen_dict_nested_sorting_and_hashing_deterministic() -> None:
    a = FrozenDict({"b": {"z": 2, "a": 1}, "a": [3, 1]})
    b = FrozenDict({"a": [3, 1], "b": {"a": 1, "z": 2}})
    # Equal deep content -> equal (and hash-equal) regardless of key/order.
    assert a == b
    assert hash(a) == hash(b)
    assert list(a) == ["a", "b"]
    assert list(a["b"]) == ["a", "z"]
    # JSON roundtrip is deterministic.
    assert json.dumps(_plain_test(a), sort_keys=True) == json.dumps(_plain_test(b), sort_keys=True)


def _plain_test(value):
    """Local plain-dict/tuple-to-JSON helper mirroring module ``_plain``."""
    if isinstance(value, Mapping):
        return {key: _plain_test(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain_test(item) for item in value]
    return value


def test_frozen_dict_hash_stable_across_processes_and_mutation_impossible() -> None:
    # Hash contract: equal FrozenDicts must hash to the same value in this
    # process AND in fresh interpreter subprocesses, regardless of
    # PYTHONHASHSEED (Python's built-in hash(str) is randomized per process
    # and would otherwise make equal FrozenDicts hash differently).
    source = {"b": {"z": 2, "a": 1}, "a": [3, 1]}
    frozen = FrozenDict(source)
    # Same object hashes to the same value twice in this process.
    assert hash(frozen) == hash(frozen)
    expected = hash(frozen)

    # A child interpreter rebuilds the same FrozenDict from source content
    # and prints its hash.  Run twice with different PYTHONHASHSEED values:
    # both runs must print the same hash as the parent (and as each other),
    # and mutation must remain impossible after construction.
    script = (
        "import sys\n"
        "from pathlib import Path\n"
        "_SCRIPT_DIR = str(Path(sys.argv[1]).resolve())\n"
        "if _SCRIPT_DIR not in sys.path:\n"
        "    sys.path.insert(0, _SCRIPT_DIR)\n"
        "from guard_findings import FrozenDict\n"
        "frozen = FrozenDict({'b': {'z': 2, 'a': 1}, 'a': [3, 1]})\n"
        "blocked = False\n"
        "try:\n"
        "    frozen['x'] = 1\n"
        "except TypeError:\n"
        "    blocked = True\n"
        "print(hash(frozen))\n"
        "print('MUTATION_BLOCKED' if blocked else 'MUTATION_ALLOWED')\n"
    )
    outputs = []
    for seed in ("1", "987654"):
        env = dict(os.environ)
        env["PYTHONHASHSEED"] = seed
        proc = subprocess.run(
            [sys.executable, "-c", script, _SCRIPT_DIR],
            capture_output=True,
            text=True,
            env=env,
            check=True,
        )
        lines = proc.stdout.splitlines()
        assert len(lines) == 2, proc.stdout
        outputs.append(lines)

    # Same hash output twice, across independent interpreter runs.
    assert outputs[0][0] == outputs[1][0]
    # And that output matches this process's deterministic hash, so equal
    # FrozenDicts hash equally within and across processes.
    assert outputs[0][0] == str(expected)
    assert outputs[1][0] == str(expected)
    # Mutation remains impossible after construction in the subprocess too.
    assert outputs[0][1] == "MUTATION_BLOCKED"
    assert outputs[1][1] == "MUTATION_BLOCKED"


def test_frozen_dict_hash_equality_for_equal_numeric_forms() -> None:
    # FrozenDict equality follows Python mapping semantics (1 == 1.0,
    # -0.0 == 0.0, True == 1, False == 0), so equal-value mappings must hash
    # equally: hash inequality here would violate the dict hash contract and
    # break deduplication when FrozenDicts are used as set items/dict keys.
    cases = (
        ({"a": 1}, {"a": 1.0}),
        ({"a": -0.0}, {"a": 0.0}),
        ({"a": True}, {"a": 1}),
        ({"a": False}, {"a": 0}),
        ({"a": True}, {"a": 1.0}),
        ({"a": {"b": 1}}, {"a": {"b": 1.0}}),
        ({"a": [1, 2.0, True, -0.0]}, {"a": [1.0, 2, 1, 0.0]}),
    )
    for left_source, right_source in cases:
        left = FrozenDict(left_source)
        right = FrozenDict(right_source)
        assert left == right
        assert hash(left) == hash(right)
        # Equal hashes make the equal mappings collapse in a set, exactly as
        # a plain dict would.
        assert len({left, right}) == 1

    # Deep immutability is preserved: the hash is derived from a normalized
    # copy and the stored values keep their original types/forms.
    stored = FrozenDict({"a": 1.0, "b": True, "c": -0.0})
    assert isinstance(stored["a"], float) and stored["a"] == 1.0
    assert stored["b"] is True
    assert isinstance(stored["c"], float) and stored["c"] == -0.0
    assert hash(stored) == hash(FrozenDict({"a": 1, "b": 1, "c": 0.0}))


def test_frozen_dict_hash_differs_for_unequal_numeric_values() -> None:
    # Unequal numeric values must keep distinct hashes: the normalization
    # collapses only values equal under Python semantics (True/1, 0.0/0), never
    # genuinely distinct ones.
    assert hash(FrozenDict({"a": 1})) != hash(FrozenDict({"a": 2}))
    assert hash(FrozenDict({"a": 1})) != hash(FrozenDict({"a": 1.5}))
    assert hash(FrozenDict({"a": -1})) != hash(FrozenDict({"a": 1}))
    assert hash(FrozenDict({"a": 0})) != hash(FrozenDict({"a": 1}))
    assert hash(FrozenDict({"a": 0.5})) != hash(FrozenDict({"a": -0.5}))
    assert hash(FrozenDict({"a": 0.5})) != hash(FrozenDict({"a": 1.5}))
    assert hash(FrozenDict({"a": 2.0})) != hash(FrozenDict({"a": 2.5}))
    # Distinct hashes reflect real mapping inequality, and the unequal values
    # are never collapsed by the set.
    assert FrozenDict({"a": 1}) != FrozenDict({"a": 1.5})
    assert len({FrozenDict({"a": 1}), FrozenDict({"a": 1.5})}) == 2


def test_frozen_dict_hash_stable_across_processes_mixed_numerics() -> None:
    # Cross-process hash contract extended to equal-value numeric forms: a
    # FrozenDict whose values are floats/bools/negative-zero must hash
    # identically to its int-valued equivalent in this process and in fresh
    # interpreters under different PYTHONHASHSEED values.
    frozen = FrozenDict({"a": 1.0, "b": True, "c": -0.0, "n": {"d": 2.0}})
    equivalent = FrozenDict({"a": 1, "b": 1, "c": 0.0, "n": {"d": 2}})
    assert frozen == equivalent
    expected = hash(frozen)
    assert hash(equivalent) == expected

    script = (
        "import sys\n"
        "from pathlib import Path\n"
        "_SCRIPT_DIR = str(Path(sys.argv[1]).resolve())\n"
        "if _SCRIPT_DIR not in sys.path:\n"
        "    sys.path.insert(0, _SCRIPT_DIR)\n"
        "from guard_findings import FrozenDict\n"
        "frozen = FrozenDict({'a': 1.0, 'b': True, 'c': -0.0, 'n': {'d': 2.0}})\n"
        "equivalent = FrozenDict({'a': 1, 'b': 1, 'c': 0.0, 'n': {'d': 2}})\n"
        "print(hash(frozen))\n"
        "print(hash(equivalent))\n"
    )
    outputs = []
    for seed in ("1", "987654"):
        env = dict(os.environ)
        env["PYTHONHASHSEED"] = seed
        proc = subprocess.run(
            [sys.executable, "-c", script, _SCRIPT_DIR],
            capture_output=True,
            text=True,
            env=env,
            check=True,
        )
        lines = proc.stdout.splitlines()
        assert len(lines) == 2, proc.stdout
        outputs.append(lines)

    # Same hash output twice, across independent interpreter runs, for both
    # the float/bool/zero form and its int-valued equivalent.
    assert outputs[0][0] == outputs[1][0]
    assert outputs[0][0] == str(expected)
    assert outputs[0][1] == outputs[1][1]
    assert outputs[0][1] == str(expected)


def test_diagnostic_controlled_context_deeply_frozen() -> None:
    context = {"section": {"count": "1"}, "items": ["a"]}
    diag = GuardDiagnostic(code="DB_SIGNATURE_UNRESOLVED", controlled_context=context)
    # Mutating the caller's original mapping/list after construction has no
    # effect on the stored (validated + deep-frozen) context.
    context["section"]["count"] = "999"
    context["items"].append("b")

    assert isinstance(diag.controlled_context, FrozenDict)
    assert isinstance(diag.controlled_context["section"], FrozenDict)
    assert isinstance(diag.controlled_context["items"], tuple)
    assert diag.controlled_context["section"]["count"] == "1"
    assert diag.controlled_context["items"] == ("a",)

    with pytest.raises(TypeError):
        diag.controlled_context["section"]["count"] = "999"
    with pytest.raises(AttributeError):
        diag.controlled_context["items"].append("b")  # type: ignore[attr-defined]


def test_statistics_deeply_frozen() -> None:
    statistics = {"counts": {"files": 3}, "labels": ["x"]}
    report = GuardRunReport(guard=_GUARD, statistics=statistics)
    statistics["counts"]["files"] = 99
    statistics["labels"].append("y")

    assert isinstance(report.statistics, FrozenDict)
    assert isinstance(report.statistics["counts"], FrozenDict)
    assert isinstance(report.statistics["labels"], tuple)
    assert report.statistics["counts"]["files"] == 3
    assert report.statistics["labels"] == ("x",)

    with pytest.raises(TypeError):
        report.statistics["counts"]["files"] = 99
    with pytest.raises(AttributeError):
        report.statistics["labels"].append("y")  # type: ignore[attr-defined]


def test_finding_identity_deeply_frozen() -> None:
    identity = _identity_for(_RULE)
    finding = _make_finding(identity=identity)
    identity["dao"] = "Hacked"
    assert finding.identity["dao"] == "AppDao"
    assert isinstance(finding.identity, FrozenDict)
    with pytest.raises(TypeError):
        finding.identity["dao"] = "Hacked"


def test_deep_frozen_report_roundtrip_and_deterministic_serialization() -> None:
    report = build_report(
        _GUARD,
        (_make_finding(),),
        diagnostics=(
            GuardDiagnostic(
                code="DB_SIGNATURE_UNRESOLVED",
                controlled_context={"n": {"b": 2, "a": 1}, "l": [3, 1]},
            ),
        ),
        statistics={"nested": {"z": 1, "a": [2, 1]}, "k": "v"},
    )
    data = report.to_dict()
    loaded = GuardRunReport.from_dict(json.loads(json.dumps(data)))
    assert loaded == report
    # Repeated serialization is byte-deterministic (keys sorted, values
    # stable) even after deep freezing.
    assert json.dumps(report.to_dict(), sort_keys=True) == json.dumps(loaded.to_dict(), sort_keys=True)
    assert loaded.diagnostics[0].controlled_context["n"] == FrozenDict({"a": 1, "b": 2})
    assert loaded.statistics["nested"]["a"] == (2, 1)


# 17. Structural rule identity: complete callable fingerprint ----------------------


_STRUCTURAL_RULE = "DB_FORBIDDEN_STRUCTURAL_OPERATION"


def test_structural_rule_profile_declares_complete_callable_identity() -> None:
    profile = known_rule(_STRUCTURAL_RULE)
    assert profile is not None
    assert profile.identity_fields == (
        "path",
        "symbol.owner",
        "symbol.name",
        "symbol.receiver",
        "symbol.parameters",
        "symbol.kind",
        "identity.operation",
    )


def test_structural_finding_roundtrip() -> None:
    finding = _make_finding(
        rule=_STRUCTURAL_RULE,
        symbol=_symbol(receiver="AppDao", parameters=("String", "long"), kind=KIND_PROPERTY_GETTER),
    )
    assert finding.identity == FrozenDict({"operation": "delete"})
    assert finding.fingerprint.startswith("v2|db_access|DB_FORBIDDEN_STRUCTURAL_OPERATION|")
    restored = GuardFinding.from_dict(finding.to_dict())
    assert restored == finding
    assert restored.fingerprint == finding.fingerprint


def test_structural_fingerprint_extension_receiver_sensitive() -> None:
    base = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(receiver=None))
    extension = _make_finding(
        rule=_STRUCTURAL_RULE,
        symbol=_symbol(receiver="android.database.sqlite.SQLiteDatabase"),
    )
    assert base.fingerprint != extension.fingerprint


def test_structural_fingerprint_parameter_order_sensitive() -> None:
    base = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(parameters=("String", "long")))
    reordered = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(parameters=("long", "String")))
    assert base.fingerprint != reordered.fingerprint


def test_structural_fingerprint_kind_sensitive() -> None:
    base = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(kind=KIND_FUNCTION))
    getter = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(kind=KIND_PROPERTY_GETTER))
    assert base.fingerprint != getter.fingerprint


def test_structural_fingerprint_operation_sensitive() -> None:
    op_delete = _make_finding(rule=_STRUCTURAL_RULE, identity=_identity_for(_STRUCTURAL_RULE, operation="delete"))
    op_create = _make_finding(rule=_STRUCTURAL_RULE, identity=_identity_for(_STRUCTURAL_RULE, operation="create"))
    assert op_delete.fingerprint != op_create.fingerprint


def test_structural_fingerprint_parameter_order_changes_baseline() -> None:
    # Two findings that differ only by parameter order must never collapse
    # into one fingerprint (protocol v2, section 7.6 collision resistance).
    f1 = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(parameters=("String", "long")), line=10)
    f2 = _make_finding(rule=_STRUCTURAL_RULE, symbol=_symbol(parameters=("long", "String")), line=20)
    assert f1.fingerprint != f2.fingerprint
    aggregated = aggregate_findings((f1, f2))
    assert len(aggregated) == 2


def test_structural_unresolved_symbol_blocked() -> None:
    # The structural rule requires symbol.* identity, so a symbol without a
    # resolved signature (empty parameters) is blocking and must become a
    # diagnostic, never a baseline-able finding.
    blank = _symbol(parameters=())
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule=_STRUCTURAL_RULE, symbol=blank)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"


# 18. Explicit diagnostic conversion contract -------------------------------------


def test_unresolved_symbol_diagnostic_creates_controlled_diagnostic() -> None:
    hostile = CallableSymbol(
        owner="com.example.Worker",
        name="doWork",
        receiver=None,
        parameters=(),
        kind=KIND_UNKNOWN,
    )
    diag = unresolved_symbol_diagnostic(symbol=hostile, path=_PATH, count="1")
    assert isinstance(diag, GuardDiagnostic)
    assert diag.code == DIAGNOSTIC_SIGNATURE_UNRESOLVED
    assert diag.path == _PATH
    assert diag.symbol == "com.example.Worker.doWork"
    assert isinstance(diag.controlled_context, FrozenDict)
    assert diag.controlled_context["count"] == "1"

    # The diagnostic is never baseline-able: the code cannot be a finding.
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule=diag.code)
    assert exc.value.code == "DIAGNOSTIC_AS_FINDING"

    # It roundtrips through the public report envelope with no findings.
    report = build_report(_GUARD, (), diagnostics=(diag,))
    loaded = GuardRunReport.from_dict(report.to_dict())
    assert loaded == report
    assert loaded.findings == ()
    assert [d.code for d in loaded.diagnostics] == [DIAGNOSTIC_SIGNATURE_UNRESOLVED]


def test_unresolved_symbol_diagnostic_accepts_string_symbol() -> None:
    diag = unresolved_symbol_diagnostic(symbol="com.example.Worker.doWork", path=_PATH)
    assert diag.code == DIAGNOSTIC_SIGNATURE_UNRESOLVED
    assert diag.symbol == "com.example.Worker.doWork"


def test_unresolved_symbol_diagnostic_rejects_resolved_symbol() -> None:
    # A resolved callable must be emitted as a finding, never downgraded to a
    # signature diagnostic: the helper fails closed with ProtocolFailure.
    resolved = _symbol()
    assert resolved.parameters == ("String", "long")
    with pytest.raises(ProtocolFailure) as exc:
        unresolved_symbol_diagnostic(symbol=resolved, path=_PATH)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"


def test_unresolved_symbol_diagnostic_rejects_unregistered_code() -> None:
    hostile = CallableSymbol(owner="a", name="b", receiver=None, parameters=(), kind=KIND_UNKNOWN)
    with pytest.raises(ProtocolFailure) as exc:
        unresolved_symbol_diagnostic(symbol=hostile, code="MADE_UP_CODE")
    assert exc.value.code == "UNKNOWN_DIAGNOSTIC"


def test_unresolved_symbol_diagnostic_context_still_validated() -> None:
    hostile = CallableSymbol(owner="a", name="b", receiver=None, parameters=(), kind=KIND_UNKNOWN)
    with pytest.raises(ValidationError) as exc:
        unresolved_symbol_diagnostic(symbol=hostile, user_payload={"amount": 1})
    assert exc.value.code == "FORBIDDEN_CONTEXT_KEY"
    with pytest.raises(ValidationError) as exc:
        unresolved_symbol_diagnostic(symbol=hostile, value=float("nan"))
    assert exc.value.code == "NON_FINITE_NUMBER"


def test_unknown_blocking_symbol_cannot_be_baselined_or_serialized() -> None:
    hostile = CallableSymbol(
        owner="com.example.Worker",
        name="doWork",
        receiver=None,
        parameters=(),
        kind=KIND_UNKNOWN,
    )
    # Constructing a baseline-able finding fails with the controlled
    # UNRESOLVED_SYMBOL_BLOCKING protocol error...
    with pytest.raises(ValidationError) as exc:
        _make_finding(symbol=hostile)
    assert exc.value.code == "UNRESOLVED_SYMBOL_BLOCKING"

    # ...so the only controlled representation is the infrastructure
    # diagnostic, which is never baseline-able and never a finding.
    diag = unresolved_symbol_diagnostic(symbol=hostile, path=_PATH)
    report = build_report(_GUARD, (), diagnostics=(diag,))
    data = report.to_dict()
    assert data["findings"] == []
    assert [d["code"] for d in data["diagnostics"]] == [DIAGNOSTIC_SIGNATURE_UNRESOLVED]

    # The public serialized report roundtrips and still contains no finding.
    serialized = json.dumps(data)
    assert DIAGNOSTIC_SIGNATURE_UNRESOLVED in serialized
    loaded = GuardRunReport.from_dict(json.loads(serialized))
    assert loaded.findings == ()
    assert loaded.diagnostics[0].code == DIAGNOSTIC_SIGNATURE_UNRESOLVED


def test_unknown_rule_is_catalog_backed_protocol_error() -> None:
    # UNKNOWN_RULE is a registered, controlled diagnostic code...
    assert is_known_diagnostic("UNKNOWN_RULE")
    # ...and an unregistered rule is a direct protocol error (exit 2) with a
    # controlled code that is never baseline-able.
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="MADE_UP_RULE")
    assert exc.value.code == "UNKNOWN_RULE"
    assert isinstance(exc.value, ProtocolFailure)
    # The controlled UNKNOWN_RULE code itself cannot be emitted as a finding.
    with pytest.raises(ValidationError) as exc:
        _make_finding(rule="UNKNOWN_RULE")
    assert exc.value.code == "DIAGNOSTIC_AS_FINDING"
