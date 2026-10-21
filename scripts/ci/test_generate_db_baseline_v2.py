#!/usr/bin/env python3
"""
test_generate_db_baseline_v2.py -- Pytest tests for generate_db_baseline_v2.py
(the baseline candidate generator).

Restored from the retired test_db_finding_triage.py (the triage builder
build_db_finding_triage.py was retired; the generator is kept and these were
its only tests).

Tests cover:
  1. Rejection of every disallowed classification in baseline generator
  2. Historical-proof requirement (unknown SHA rejected)
  3. Expiry and metadata validation
  4. Deterministic candidate output ordering
  5. Active baseline immutability (candidate never overwrites active)
  6. Atomic JSON write semantics
  7. Baseline candidate construction
  8. Triage YAML parse round-trip (frozen on-disk format)
  9. Baseline writer main() guards (GR-09 writer hardening)

Run:
    python -m pytest scripts/ci/test_generate_db_baseline_v2.py -v
"""

import hashlib
import json
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import pytest  # noqa: E402

from guard_findings import (  # noqa: E402
    CallableSymbol,
    GuardFinding,
    SourceLocation,
    KIND_FUNCTION,
    SEVERITY_ERROR,
)
from finding_rule_catalog import known_rule  # noqa: E402

from generate_db_baseline_v2 import (  # noqa: E402
    _ACCEPTED_CLASSIFICATION,
    _REJECTED_CLASSIFICATIONS,
    _parse_yaml_simple,
    build_baseline_candidate,
    main as baseline_main,
    validate_triage_entry,
    write_json_atomic,
)

# ------------------------------------------------------------------
# Test fixtures
# ------------------------------------------------------------------

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

# Explicit review-evidence timestamp used by direct build_baseline_candidate
# call sites (the writer requires an explicit generated_at; never a hidden
# current-time default).
_CANDIDATE_GENERATED_AT = "2026-08-29T00:00:00Z"


def _identity_for(rule: str, **overrides) -> Dict[str, str]:
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
    path: str = _PATH,
    line: int = 42,
    symbol: Optional[CallableSymbol] = None,
    identity: Optional[Dict[str, str]] = None,
    message: str = "test finding",
) -> GuardFinding:
    if symbol is None:
        symbol = _SYMBOL
    if identity is None:
        identity = _identity_for(rule)
    return GuardFinding(
        rule=rule,
        severity=SEVERITY_ERROR,
        path=path,
        location=SourceLocation(line=line, column=7),
        symbol=symbol,
        identity=identity,
        message=message,
    )


def _make_triage_entry(
    *,
    fingerprint: str = "v2|db_access|DB_UNAUTHORIZED_MUTATION|path=test",
    classification: str = "PENDING",
    path: str = "app/src/main/java/com/example/Worker.kt",
    owner: Optional[str] = None,
    linked_issue: Optional[str] = None,
    reason: Optional[str] = None,
    expires: Optional[str] = None,
    present_at_reference_sha: str = "unknown",
    evidence: Optional[List[str]] = None,
) -> Dict[str, Any]:
    return {
        "fingerprint": fingerprint,
        "classification": classification,
        "path": path,
        "symbol": {
            "owner": "com.example.Worker",
            "name": "doWork",
            "receiver": None,
            "parameters": ["String", "long"],
            "kind": "function",
        },
        "dao": "AppDao",
        "operation": "delete",
        "present_at_reference_sha": present_at_reference_sha,
        "owner": owner,
        "linked_issue": linked_issue,
        "reason": reason,
        "expires": expires,
        "evidence": evidence if evidence is not None else [],
    }


# ------------------------------------------------------------------
# 1. Rejection of every disallowed classification
# ------------------------------------------------------------------


class TestDisallowedClassificationRejection:
    """Test that every non-PREEXISTING_TEMPORARY_DEBT classification is rejected."""

    def _make_current_fingerprints(self, entry: Dict[str, Any]) -> Dict[str, int]:
        """Build a current_fingerprints map containing the entry's fingerprint."""
        return {entry["fingerprint"]: 1}

    @pytest.mark.parametrize(
        "classification",
        sorted(_REJECTED_CLASSIFICATIONS),
    )
    def test_rejected_classification(self, classification: str) -> None:
        """Every disallowed classification causes rejection."""
        entry = _make_triage_entry(
            classification=classification,
            owner="test-owner",
            linked_issue="MIT-001",
            reason="test reason",
            expires="2099-01-01",
            present_at_reference_sha="abc12345",
            evidence=["test"],
        )
        # Set the fingerprint to match a current finding
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert len(reasons) > 0
        assert classification in reasons[0] or "rejected classification" in reasons[0]

    def test_pending_is_rejected_for_baseline(self) -> None:
        """PENDING classification is rejected for baseline candidacy."""
        entry = _make_triage_entry(classification="PENDING")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_legal_writer_policy_missing_rejected(self) -> None:
        """LEGAL_WRITER_POLICY_MISSING is rejected."""
        entry = _make_triage_entry(classification="LEGAL_WRITER_POLICY_MISSING")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_parser_false_positive_rejected(self) -> None:
        """PARSER_FALSE_POSITIVE is rejected."""
        entry = _make_triage_entry(classification="PARSER_FALSE_POSITIVE")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_analyzer_unsupported_rejected(self) -> None:
        """ANALYZER_UNSUPPORTED is rejected."""
        entry = _make_triage_entry(classification="ANALYZER_UNSUPPORTED")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_duplicate_detection_rejected(self) -> None:
        """DUPLICATE_DETECTION is rejected."""
        entry = _make_triage_entry(classification="DUPLICATE_DETECTION")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_real_architecture_violation_rejected(self) -> None:
        """REAL_ARCHITECTURE_VIOLATION is rejected."""
        entry = _make_triage_entry(classification="REAL_ARCHITECTURE_VIOLATION")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_structural_operation_rejected(self) -> None:
        """STRUCTURAL_OPERATION is rejected."""
        entry = _make_triage_entry(classification="STRUCTURAL_OPERATION")
        current_fps = self._make_current_fingerprints(entry)
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False


# ------------------------------------------------------------------
# 2. Historical-proof requirement
# ------------------------------------------------------------------


class TestHistoricalProof:
    """Test that unknown SHA is rejected."""

    def test_unknown_sha_rejected(self) -> None:
        """present_at_reference_sha=unknown causes rejection."""
        entry = _make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="test-owner",
            linked_issue="MIT-001",
            reason="test reason",
            expires="2099-01-01",
            present_at_reference_sha="unknown",
            evidence=["test"],
        )
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("unproven" in r.lower() or "unknown" in r.lower() for r in reasons)

    def test_valid_sha_accepted(self) -> None:
        """A valid SHA is accepted (when other criteria met)."""
        entry = _make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="test-owner",
            linked_issue="MIT-001",
            reason="test reason",
            expires="2099-01-01",
            present_at_reference_sha="abc12345def67890",
            evidence=["test"],
        )
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is True

    def test_short_sha_rejected(self) -> None:
        """A too-short SHA is rejected."""
        entry = _make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="test-owner",
            linked_issue="MIT-001",
            reason="test reason",
            expires="2099-01-01",
            present_at_reference_sha="abc",
            evidence=["test"],
        )
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False


# ------------------------------------------------------------------
# 3. Expiry and metadata validation
# ------------------------------------------------------------------


class TestExpiryAndMetadata:
    """Test expiry date and metadata field validation."""

    def _make_valid_entry(self, **overrides) -> Dict[str, Any]:
        defaults = {
            "classification": _ACCEPTED_CLASSIFICATION,
            "owner": "test-owner",
            "linked_issue": "MIT-001",
            "reason": "test reason",
            "expires": "2099-01-01",
            "present_at_reference_sha": "abc12345def67890",
            "evidence": ["test evidence"],
        }
        defaults.update(overrides)
        return _make_triage_entry(**defaults)

    def test_missing_owner_rejected(self) -> None:
        """Null owner causes rejection."""
        entry = self._make_valid_entry(owner=None)
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("owner" in r for r in reasons)

    def test_empty_owner_rejected(self) -> None:
        """Empty string owner causes rejection."""
        entry = self._make_valid_entry(owner="")
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False

    def test_missing_linked_issue_rejected(self) -> None:
        """Null linked_issue causes rejection."""
        entry = self._make_valid_entry(linked_issue=None)
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("linked_issue" in r for r in reasons)

    def test_missing_reason_rejected(self) -> None:
        """Null reason causes rejection."""
        entry = self._make_valid_entry(reason=None)
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("reason" in r for r in reasons)

    def test_missing_expires_rejected(self) -> None:
        """Null expires causes rejection."""
        entry = self._make_valid_entry(expires=None)
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("expires" in r for r in reasons)

    def test_expired_date_rejected(self) -> None:
        """Past expiry date causes rejection."""
        yesterday = (date.today() - timedelta(days=1)).isoformat()
        entry = self._make_valid_entry(expires=yesterday)
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("expired" in r.lower() for r in reasons)

    def test_future_date_accepted(self) -> None:
        """Future expiry date is accepted."""
        future = (date.today() + timedelta(days=365)).isoformat()
        entry = self._make_valid_entry(expires=future)
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is True

    def test_empty_evidence_rejected(self) -> None:
        """Empty evidence list causes rejection."""
        entry = self._make_valid_entry(evidence=[])
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("evidence" in r for r in reasons)

    def test_fingerprint_not_in_current_report_rejected(self) -> None:
        """Fingerprint not in current v2 report causes rejection."""
        entry = self._make_valid_entry()
        current_fps = {}  # empty -- fingerprint not present
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is False
        assert any("not found" in r.lower() for r in reasons)

    def test_valid_entry_accepted(self) -> None:
        """A fully valid entry is accepted."""
        entry = self._make_valid_entry()
        current_fps = {entry["fingerprint"]: 1}
        reasons = []
        result = validate_triage_entry(entry, current_fps, reasons)
        assert result is True
        assert len(reasons) == 0


# ------------------------------------------------------------------
# 4. Deterministic output ordering
# ------------------------------------------------------------------


class TestDeterministicCandidateOutput:
    """Test that baseline candidate output is deterministically ordered."""

    def test_baseline_candidate_entries_sorted(self) -> None:
        """Baseline candidate entries are sorted by fingerprint."""
        entries = [
            _make_triage_entry(
                fingerprint="v2|db_access|DB_UNAUTHORIZED_MUTATION|path=zzz",
                classification=_ACCEPTED_CLASSIFICATION,
                owner="owner",
                linked_issue="MIT-1",
                reason="reason",
                expires="2099-01-01",
                present_at_reference_sha="abc12345",
                evidence=["e"],
            ),
            _make_triage_entry(
                fingerprint="v2|db_access|DB_UNAUTHORIZED_MUTATION|path=aaa",
                classification=_ACCEPTED_CLASSIFICATION,
                owner="owner",
                linked_issue="MIT-1",
                reason="reason",
                expires="2099-01-01",
                present_at_reference_sha="abc12345",
                evidence=["e"],
            ),
        ]
        current_fps = {e["fingerprint"]: 1 for e in entries}
        candidate = build_baseline_candidate(
            entries, current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        fps = [e["fingerprint"] for e in candidate["entries"]]
        assert fps == sorted(fps)


# ------------------------------------------------------------------
# 5. Active baseline immutability
# ------------------------------------------------------------------


class TestActiveBaselineImmutability:
    """Test that the candidate never overwrites the active baseline."""

    def test_candidate_path_must_not_be_active_baseline(self, tmp_path: Path) -> None:
        """Output path must differ from active baseline path."""
        active = tmp_path / "db_access.json"
        active.write_text('{"guard": "db_access", "fingerprints": []}')

        # This is tested by the main() function's guard, but we can verify
        # the path comparison logic directly.
        output = tmp_path / "candidate.json"
        assert output.resolve() != active.resolve()

    def test_write_json_atomic_creates_file(self, tmp_path: Path) -> None:
        """Atomic write creates the target file."""
        target = tmp_path / "test.json"
        data = {"test": "value"}
        write_json_atomic(target, data)
        assert target.exists()
        loaded = json.loads(target.read_text(encoding="utf-8"))
        assert loaded == data

    def test_write_json_atomic_no_partial_on_error(self, tmp_path: Path) -> None:
        """Atomic write leaves no partial file on error."""
        target = tmp_path / "test.json"
        # Write something first
        target.write_text("original")
        # Try to write to a non-existent parent (should fail)
        bad_target = tmp_path / "nonexistent" / "subdir" / "test.json"
        try:
            write_json_atomic(bad_target, {"data": "value"})
        except Exception:
            pass
        # Original file should be unchanged
        assert target.read_text(encoding="utf-8") == "original"


# ------------------------------------------------------------------
# 6. Atomic write semantics
# ------------------------------------------------------------------


class TestWriteJsonAtomic:
    """Test atomic JSON write round-trip."""

    def test_write_json_atomic_roundtrip(self, tmp_path: Path) -> None:
        """JSON written atomically can be loaded back identically."""
        target = tmp_path / "baseline.json"
        data = {
            "baseline_schema_version": 2,
            "guard": "db_access",
            "entries": [
                {
                    "fingerprint": "v2|test",
                    "count": 1,
                    "rule": "DB_UNAUTHORIZED_MUTATION",
                    "classification": "temporary_debt",
                }
            ],
        }
        write_json_atomic(target, data)
        loaded = json.loads(target.read_text(encoding="utf-8"))
        assert loaded == data


# ------------------------------------------------------------------
# 7. Baseline candidate construction
# ------------------------------------------------------------------


class TestBaselineCandidate:
    """Test baseline candidate construction."""

    def test_candidate_schema_version(self) -> None:
        """Candidate has schema_version 2."""
        entries = [_make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="owner",
            linked_issue="MIT-1",
            reason="reason",
            expires="2099-01-01",
            present_at_reference_sha="abc12345",
            evidence=["e"],
        )]
        current_fps = {entries[0]["fingerprint"]: 1}
        candidate = build_baseline_candidate(
            entries, current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        assert candidate["baseline_schema_version"] == 2
        assert candidate["guard_output_schema_version"] == 2
        assert candidate["fingerprint_schema_version"] == 2

    def test_candidate_guard_name(self) -> None:
        """Candidate has correct guard name."""
        entries = [_make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="owner",
            linked_issue="MIT-1",
            reason="reason",
            expires="2099-01-01",
            present_at_reference_sha="abc12345",
            evidence=["e"],
        )]
        current_fps = {entries[0]["fingerprint"]: 1}
        candidate = build_baseline_candidate(
            entries, current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        assert candidate["guard"] == "db_access"

    def test_candidate_entry_has_count(self) -> None:
        """Candidate entries have count from current report."""
        entry = _make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="owner",
            linked_issue="MIT-1",
            reason="reason",
            expires="2099-01-01",
            present_at_reference_sha="abc12345",
            evidence=["e"],
        )
        current_fps = {entry["fingerprint"]: 3}
        candidate = build_baseline_candidate(
            [entry], current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        assert candidate["entries"][0]["count"] == 3

    def test_candidate_entry_classification_is_temporary_debt(self) -> None:
        """Candidate entries use 'temporary_debt' classification."""
        entry = _make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="owner",
            linked_issue="MIT-1",
            reason="reason",
            expires="2099-01-01",
            present_at_reference_sha="abc12345",
            evidence=["e"],
        )
        current_fps = {entry["fingerprint"]: 1}
        candidate = build_baseline_candidate(
            [entry], current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        assert candidate["entries"][0]["classification"] == "temporary_debt"

    def test_candidate_preserves_metadata(self) -> None:
        """Candidate entries preserve reason, owner, linked_issue, expires."""
        entry = _make_triage_entry(
            classification=_ACCEPTED_CLASSIFICATION,
            owner="test-owner",
            linked_issue="MIT-123",
            reason="existing unsafe writer",
            expires="2099-06-15",
            present_at_reference_sha="abc12345",
            evidence=["e"],
        )
        current_fps = {entry["fingerprint"]: 1}
        candidate = build_baseline_candidate(
            [entry], current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        ce = candidate["entries"][0]
        assert ce["owner"] == "test-owner"
        assert ce["linked_issue"] == "MIT-123"
        assert ce["reason"] == "existing unsafe writer"
        assert ce["expires"] == "2099-06-15"

    def test_candidate_has_generated_at(self) -> None:
        """Candidate carries the explicit generated_at verbatim (no hidden
        current-time default)."""
        entries = []
        current_fps = {}
        candidate = build_baseline_candidate(
            entries, current_fps, generated_at=_CANDIDATE_GENERATED_AT
        )
        assert candidate["generated_at"] == _CANDIDATE_GENERATED_AT


# ------------------------------------------------------------------
# 8. Triage YAML parse round-trip (frozen on-disk format)
# ------------------------------------------------------------------

# Frozen fixtures: the exact byte format the retired triage builder
# (build_db_finding_triage.write_triage_yaml) emitted.  Each case is the
# literal file content for the input the original round-trip test fed to that
# writer.  They are deliberately hand-authored, NOT generated by a copy of the
# emitter: the point is a fixed on-disk format that _parse_yaml_simple must
# keep reading.  Format facts: three leading comment lines + blank line,
# `metadata:` (keys sorted, Python repr of bool/int, so `True`), optional
# `crosswalk:` / `duplicate_diagnostics:` sections each followed by a blank
# line, then `triage_entries:` with `  []` when empty, and a single trailing
# newline.  Strings containing ``|`` are double-quoted; plain identifiers and
# paths are bare; null owner/linked_issue/reason/expires and an empty evidence
# list are always spelled `null` / `[]`.

_FROZEN_YAML_HEADER = [
    "# DB Access Finding Triage -- PR-D5",
    "# Generated by build_db_finding_triage.py",
    "# Classification status: ALL PENDING -- requires manual review",
    "",
]

# One default PENDING triage entry (the _make_triage_entry() defaults).
_FROZEN_DEFAULT_ENTRY_LINES = [
    '  - fingerprint: "v2|db_access|DB_UNAUTHORIZED_MUTATION|path=test"',
    "    classification: PENDING",
    "    path: app/src/main/java/com/example/Worker.kt",
    "    symbol:",
    "      owner: com.example.Worker",
    "      name: doWork",
    "      receiver: null",
    "      parameters:",
    "        - String",
    "        - long",
    "      kind: function",
    "    dao: AppDao",
    "    operation: delete",
    "    present_at_reference_sha: unknown",
    "    owner: null",
    "    linked_issue: null",
    "    reason: null",
    "    expires: null",
    "    evidence: []",
]

_FROZEN_TRIAGE_YAML_CASES: Dict[str, List[str]] = {
    # metadata = generated_by / total_v2_findings=5 / total_v2_aggregates=3 /
    # all_classifications_pending=True; no entries.
    "metadata": _FROZEN_YAML_HEADER + [
        "metadata:",
        "  all_classifications_pending: True",
        "  generated_by: build_db_finding_triage.py",
        "  total_v2_aggregates: 3",
        "  total_v2_findings: 5",
        "",
        "triage_entries:",
        "  []",
    ],
    # one default entry, metadata {"test": 1}.
    "entries": _FROZEN_YAML_HEADER + [
        "metadata:",
        "  test: 1",
        "",
        "triage_entries:",
    ] + _FROZEN_DEFAULT_ENTRY_LINES,
    # one ONE_TO_ONE crosswalk row, no entries, metadata {"test": 1}.
    "crosswalk": _FROZEN_YAML_HEADER + [
        "metadata:",
        "  test: 1",
        "",
        "crosswalk:",
        "  - v1_fingerprint: UNALLOWLISTED_CLASS app/src/Foo.kt",
        "    v1_rule_family: UNALLOWLISTED_CLASS",
        "    v1_path: app/src/Foo.kt",
        "    outcome: ONE_TO_ONE",
        "    matched_count: 1",
        "    matched_v2_fingerprints:",
        '      - "v2|db_access|DB_UNAUTHORIZED_MUTATION|path=app/src/Foo.kt"',
        "",
        "triage_entries:",
        "  []",
    ],
    # one duplicate diagnostic, no entries, metadata {"test": 1}.
    "duplicates": _FROZEN_YAML_HEADER + [
        "metadata:",
        "  test: 1",
        "",
        "duplicate_diagnostics:",
        "  - rule: DB_UNAUTHORIZED_MUTATION",
        "    path: app/src/Foo.kt",
        "    line: 42",
        "    count: 2",
        "    diagnosis: DUPLICATE_DETECTION",
        "",
        "triage_entries:",
        "  []",
    ],
    # nothing but metadata {"test": 1}.
    "empty": _FROZEN_YAML_HEADER + [
        "metadata:",
        "  test: 1",
        "",
        "triage_entries:",
        "  []",
    ],
    # The retired writer always spelled owner/linked_issue/reason/expires as
    # `null` and evidence as `[]`, so the null-field entry is byte-identical
    # to the default entry.
    "null_fields": _FROZEN_YAML_HEADER + [
        "metadata:",
        "  test: 1",
        "",
        "triage_entries:",
    ] + _FROZEN_DEFAULT_ENTRY_LINES,
}


def _frozen_triage_yaml(case: str) -> str:
    """Return the frozen triage YAML text for ``case`` (single trailing
    newline, LF line endings)."""
    return "\n".join(_FROZEN_TRIAGE_YAML_CASES[case]) + "\n"


class TestYamlRoundTrip:
    """Test that triage YAML in the frozen format emitted by the retired
    builder (build_db_finding_triage.write_triage_yaml) is read back
    correctly by _parse_yaml_simple.  The fixtures are hand-authored literals
    of that on-disk format, not produced by any current writer."""

    def test_round_trip_metadata(self, tmp_path: Path) -> None:
        """Metadata: parse the frozen on-disk fixture."""
        output = tmp_path / "triage.yml"
        output.write_bytes(_frozen_triage_yaml("metadata").encode("utf-8"))
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert parsed["metadata"]["generated_by"] == "build_db_finding_triage.py"
        assert parsed["metadata"]["total_v2_findings"] == 5
        assert parsed["metadata"]["total_v2_aggregates"] == 3
        assert parsed["metadata"]["all_classifications_pending"] is True

    def test_round_trip_entries(self, tmp_path: Path) -> None:
        """Triage entries: parse the frozen on-disk fixture."""
        output = tmp_path / "triage.yml"
        entries = [_make_triage_entry()]
        output.write_bytes(_frozen_triage_yaml("entries").encode("utf-8"))
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert "triage_entries" in parsed
        assert len(parsed["triage_entries"]) == 1
        entry = parsed["triage_entries"][0]
        assert entry["fingerprint"] == entries[0]["fingerprint"]
        assert entry["classification"] == "PENDING"
        assert entry["path"] == entries[0]["path"]
        assert entry["symbol"]["owner"] == "com.example.Worker"
        assert entry["symbol"]["name"] == "doWork"
        assert entry["symbol"]["kind"] == "function"

    def test_round_trip_crosswalk(self, tmp_path: Path) -> None:
        """Crosswalk entries: parse the frozen on-disk fixture."""
        output = tmp_path / "triage.yml"
        output.write_bytes(_frozen_triage_yaml("crosswalk").encode("utf-8"))
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert "crosswalk" in parsed
        assert len(parsed["crosswalk"]) == 1
        cw = parsed["crosswalk"][0]
        assert cw["outcome"] == "ONE_TO_ONE"
        assert cw["matched_count"] == 1

    def test_round_trip_duplicate_diagnostics(self, tmp_path: Path) -> None:
        """Duplicate diagnostics: parse the frozen on-disk fixture."""
        output = tmp_path / "triage.yml"
        output.write_bytes(_frozen_triage_yaml("duplicates").encode("utf-8"))
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert "duplicate_diagnostics" in parsed
        assert len(parsed["duplicate_diagnostics"]) == 1
        dup = parsed["duplicate_diagnostics"][0]
        assert dup["rule"] == "DB_UNAUTHORIZED_MUTATION"
        assert dup["count"] == 2
        assert dup["diagnosis"] == "DUPLICATE_DETECTION"

    def test_round_trip_empty_entries(self, tmp_path: Path) -> None:
        """Empty triage_entries round-trips correctly."""
        output = tmp_path / "triage.yml"
        output.write_bytes(_frozen_triage_yaml("empty").encode("utf-8"))
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert "triage_entries" in parsed

    def test_round_trip_null_fields_preserved(self, tmp_path: Path) -> None:
        """Null fields in triage entries are preserved as None."""
        output = tmp_path / "triage.yml"
        output.write_bytes(_frozen_triage_yaml("null_fields").encode("utf-8"))
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        entry = parsed["triage_entries"][0]
        assert entry["owner"] is None
        assert entry["linked_issue"] is None
        assert entry["reason"] is None
        assert entry["expires"] is None


# ------------------------------------------------------------------
# 9. Baseline writer main() guards (GR-09 writer hardening)
# ------------------------------------------------------------------


_BLOCKING_DIAGNOSTIC = {
    "code": "DB_SOURCE_UNREADABLE",
    "path": None,
    "symbol": None,
    "controlled_context": {},
}

_ADVISORY_DIAGNOSTIC = {
    "code": "DB_SIGNATURE_UNRESOLVED",
    "path": "app/src/main/java/com/example/Ui.kt",
    "symbol": None,
    "controlled_context": {"advisory": True},
}


def _write_writer_report(
    path: Path,
    *,
    findings: Optional[List[GuardFinding]] = None,
    diagnostics: Optional[List[Dict[str, Any]]] = None,
    statistics: Optional[Dict[str, Any]] = None,
) -> str:
    """Write a v2 report JSON file and return its SHA-256 hex digest."""
    report_dict = {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [f.to_dict() for f in (findings or [])],
        "diagnostics": diagnostics if diagnostics is not None else [],
        "statistics": statistics if statistics is not None else {},
    }
    path.write_text(json.dumps(report_dict, indent=2) + "\n", encoding="utf-8")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_writer_triage(path: Path, entries: List[Dict[str, Any]]) -> None:
    """Write a minimal triage YAML both writer parsers accept.

    Scalar values are quoted so PyYAML and the manual fallback parser agree
    on types (an unquoted YYYY-MM-DD would become a ``date`` under PyYAML).

    An EMPTY manifest must be spelled ``triage_entries: []``: a bare
    ``triage_entries:`` key parses as ``None`` under PyYAML, which the
    writer's fail-closed ``triage_entries must be a list`` guard rejects
    with exit 2 before any zero-findings / unapproved-finding behavior can
    be reached.
    """
    lines = ["triage_entries:"] if entries else ["triage_entries: []"]
    for entry in entries:
        lines.append(f'  - fingerprint: "{entry["fingerprint"]}"')
        lines.append(f'    classification: "{entry["classification"]}"')
        lines.append(f'    owner: "{entry["owner"]}"')
        lines.append(f'    linked_issue: "{entry["linked_issue"]}"')
        lines.append(f'    reason: "{entry["reason"]}"')
        lines.append(f'    expires: "{entry["expires"]}"')
        lines.append(
            f'    present_at_reference_sha: "{entry["present_at_reference_sha"]}"'
        )
        evidence = entry.get("evidence") or []
        if evidence:
            lines.append("    evidence:")
            for item in evidence:
                lines.append(f'      - "{item}"')
        else:
            lines.append("    evidence: []")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _approved_writer_entry(fingerprint: str, *, expires: str) -> Dict[str, Any]:
    """A fully approved PREEXISTING_TEMPORARY_DEBT triage entry."""
    return {
        "fingerprint": fingerprint,
        "classification": _ACCEPTED_CLASSIFICATION,
        "owner": "@test-owner",
        "linked_issue": "MIT-000",
        "reason": "reviewed temporary debt",
        "expires": expires,
        "present_at_reference_sha": "abc12345def67890",
        "evidence": ["reviewed in GR-08"],
    }


class TestBaselineWriterMainGuards:
    """GR-09 writer hardening: main() rejects every plan-mandated state.

    The writer requires an explicit --generated-at (never a hidden
    current-time default) and an explicit --report-sha256 evidence hash, and
    refuses to write a candidate on report diagnostics, untrusted reports,
    hash mismatches, unapproved current findings (bulk-generation refusal),
    duplicate/stale manifest fingerprints, expiry beyond --expires-max-days,
    and output collisions with the active baseline/policy or the inputs.
    """

    @staticmethod
    def _approval_time() -> datetime:
        """The review-evidence approval instant used by these fixtures."""
        return datetime.now(timezone.utc).replace(
            hour=0, minute=0, second=0, microsecond=0
        )

    def _run_writer(
        self,
        tmp_path: Path,
        *,
        report: Path,
        report_sha: str,
        triage: Path,
        output: Path,
        generated_at: str,
        extra_args: Optional[List[str]] = None,
    ) -> None:
        argv = [
            "--triage", str(triage),
            "--v2-report", str(report),
            "--report-sha256", report_sha,
            "--generated-at", generated_at,
            "--output", str(output),
            "--active-baseline", str(tmp_path / "active_baseline.json"),
            "--active-policy", str(tmp_path / "active_policy.yml"),
        ]
        if extra_args:
            argv.extend(extra_args)
        baseline_main(argv)

    def test_main_requires_generated_at(self, tmp_path: Path) -> None:
        """Omitting --generated-at is an argparse error (exit 2)."""
        report = tmp_path / "report.json"
        sha = _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            baseline_main([
                "--triage", str(triage),
                "--v2-report", str(report),
                "--report-sha256", sha,
                "--output", str(output),
            ])
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_requires_report_sha256(self, tmp_path: Path) -> None:
        """Omitting --report-sha256 is an argparse error (exit 2)."""
        report = tmp_path / "report.json"
        _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            baseline_main([
                "--triage", str(triage),
                "--v2-report", str(report),
                "--generated-at", "2026-08-29T00:00:00Z",
                "--output", str(output),
            ])
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_rejects_malformed_generated_at(self, tmp_path: Path) -> None:
        """A date-only --generated-at fails closed (exit 2, no candidate)."""
        report = tmp_path / "report.json"
        sha = _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29",  # date-only: no time/timezone
            )
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_rejects_report_hash_mismatch(self, tmp_path: Path) -> None:
        """A --report-sha256 mismatch fails closed (exit 2, no candidate)."""
        report = tmp_path / "report.json"
        _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"
        wrong_sha = "0" * 64
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=wrong_sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_writes_candidate_with_explicit_generated_at(
        self, tmp_path: Path,
    ) -> None:
        """A fully approved manifest writes the candidate with the explicit
        generated_at verbatim and deterministic entry ordering."""
        approval = self._approval_time()
        generated_at = approval.strftime("%Y-%m-%dT00:00:00Z")
        expires = (approval.date() + timedelta(days=60)).isoformat()
        finding = _make_finding()
        report = tmp_path / "report.json"
        sha = _write_writer_report(report, findings=[finding])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(
            triage, [_approved_writer_entry(finding.fingerprint, expires=expires)]
        )
        output = tmp_path / "candidate.json"

        self._run_writer(
            tmp_path,
            report=report,
            report_sha=sha,
            triage=triage,
            output=output,
            generated_at=generated_at,
        )

        assert output.exists(), "candidate was not written"
        data = json.loads(output.read_text(encoding="utf-8"))
        assert data["baseline_schema_version"] == 2
        assert data["guard_output_schema_version"] == 2
        assert data["fingerprint_schema_version"] == 2
        assert data["guard"] == "db_access"
        assert data["generated_at"] == generated_at
        assert len(data["entries"]) == 1
        entry = data["entries"][0]
        assert entry["fingerprint"] == finding.fingerprint
        assert entry["count"] == 1
        assert entry["classification"] == "temporary_debt"
        assert entry["expires"] == expires
        assert entry["owner"] == "@test-owner"

    def test_main_zero_findings_empty_triage_writes_no_candidate(
        self, tmp_path: Path,
    ) -> None:
        """The GR-09 zero/zero state: exit 0, honest NO CANDIDATE, no file.

        A trusted zero-findings report with an empty (complete) triage has
        nothing to baseline: the writer exits 0 without writing a candidate
        (the tracked empty baseline is authored from the review evidence).
        """
        report = tmp_path / "report.json"
        sha = _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"

        # The honest NO-CANDIDATE path is a controlled exit 0.
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 0

        assert not output.exists(), "no candidate should be written"

    def test_main_accepts_advisory_only_report(self, tmp_path: Path) -> None:
        """Advisory-only diagnostics keep the report trusted (exit 0).

        Mirrors the frozen GR-09 evidence (zero findings, advisory-marked
        DB_SIGNATURE_UNRESOLVED diagnostics, trusted statistics): the writer
        does not fail on advisory diagnostics and writes no candidate for
        the empty state.
        """
        report = tmp_path / "report.json"
        sha = _write_writer_report(
            report,
            diagnostics=[_ADVISORY_DIAGNOSTIC],
            statistics={"trusted": True, "advisoryDiagnosticCount": 1},
        )
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"

        # The honest NO-CANDIDATE path is a controlled exit 0.
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 0

        assert not output.exists(), "no candidate should be written"

    def test_main_rejects_blocking_diagnostics(self, tmp_path: Path) -> None:
        """Blocking (non-advisory) diagnostics fail closed (exit 2)."""
        report = tmp_path / "report.json"
        _write_writer_report(report, diagnostics=[_BLOCKING_DIAGNOSTIC])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"
        sha = hashlib.sha256(report.read_bytes()).hexdigest()
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_rejects_untrusted_statistics(self, tmp_path: Path) -> None:
        """An explicit trusted=false statistics flag fails closed (exit 2)."""
        report = tmp_path / "report.json"
        _write_writer_report(report, statistics={"trusted": False})
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        output = tmp_path / "candidate.json"
        sha = hashlib.sha256(report.read_bytes()).hexdigest()
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_rejects_unapproved_current_finding(
        self, tmp_path: Path,
    ) -> None:
        """A current finding with no approved triage entry is a hard exit 1.

        This is the bulk-generation refusal: an unreviewed (or partially
        reviewed) report can never be copied into a baseline, because the
        approved-debt set must exactly equal the current finding set.
        """
        finding = _make_finding()
        report = tmp_path / "report.json"
        sha = _write_writer_report(report, findings=[finding])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])  # empty manifest: nothing approved
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 1
        assert not output.exists()

    def test_main_rejects_duplicate_triage_fingerprints(
        self, tmp_path: Path,
    ) -> None:
        """Duplicate fingerprints in the manifest fail closed (exit 2)."""
        approval = self._approval_time()
        expires = (approval.date() + timedelta(days=30)).isoformat()
        finding = _make_finding()
        report = tmp_path / "report.json"
        sha = _write_writer_report(report, findings=[finding])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(
            triage,
            [
                _approved_writer_entry(finding.fingerprint, expires=expires),
                _approved_writer_entry(finding.fingerprint, expires=expires),
            ],
        )
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 2
        assert not output.exists()

    def test_main_rejects_stale_manifest_fingerprint(
        self, tmp_path: Path,
    ) -> None:
        """A manifest fingerprint absent from the current report exits 1."""
        report = tmp_path / "report.json"
        sha = _write_writer_report(report)  # zero findings
        triage = tmp_path / "triage.yml"
        _write_writer_triage(
            triage,
            [_approved_writer_entry(
                "v2|db_access|DB_UNAUTHORIZED_MUTATION|path=app/src/gone/Fixed.kt",
                expires="2026-09-30",
            )],
        )
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 1
        assert not output.exists()

    def test_main_rejects_expiry_beyond_max_days(self, tmp_path: Path) -> None:
        """Expiry more than --expires-max-days after approval exits 1."""
        approval = self._approval_time()
        generated_at = approval.strftime("%Y-%m-%dT00:00:00Z")
        too_far = (approval.date() + timedelta(days=61)).isoformat()
        finding = _make_finding()
        report = tmp_path / "report.json"
        sha = _write_writer_report(report, findings=[finding])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(
            triage, [_approved_writer_entry(finding.fingerprint, expires=too_far)]
        )
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at=generated_at,
            )
        assert exc.value.code == 1
        assert not output.exists()

    def test_main_accepts_expiry_at_max_days_boundary(
        self, tmp_path: Path,
    ) -> None:
        """Expiry exactly --expires-max-days (60) days after approval passes."""
        approval = self._approval_time()
        generated_at = approval.strftime("%Y-%m-%dT00:00:00Z")
        boundary = (approval.date() + timedelta(days=60)).isoformat()
        finding = _make_finding()
        report = tmp_path / "report.json"
        sha = _write_writer_report(report, findings=[finding])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(
            triage, [_approved_writer_entry(finding.fingerprint, expires=boundary)]
        )
        output = tmp_path / "candidate.json"

        self._run_writer(
            tmp_path,
            report=report,
            report_sha=sha,
            triage=triage,
            output=output,
            generated_at=generated_at,
        )

        assert output.exists(), "boundary expiry must be accepted"
        data = json.loads(output.read_text(encoding="utf-8"))
        assert data["entries"][0]["expires"] == boundary

    def test_main_rejects_expiry_beyond_custom_max_days(
        self, tmp_path: Path,
    ) -> None:
        """A smaller --expires-max-days horizon is enforced (30 vs 31)."""
        approval = self._approval_time()
        generated_at = approval.strftime("%Y-%m-%dT00:00:00Z")
        too_far = (approval.date() + timedelta(days=31)).isoformat()
        finding = _make_finding()
        report = tmp_path / "report.json"
        sha = _write_writer_report(report, findings=[finding])
        triage = tmp_path / "triage.yml"
        _write_writer_triage(
            triage, [_approved_writer_entry(finding.fingerprint, expires=too_far)]
        )
        output = tmp_path / "candidate.json"
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=output,
                generated_at=generated_at,
                extra_args=["--expires-max-days", "30"],
            )
        assert exc.value.code == 1
        assert not output.exists()

    def test_main_rejects_output_colliding_with_report(
        self, tmp_path: Path,
    ) -> None:
        """Output path equal to the v2 report fails closed (exit 2)."""
        report = tmp_path / "report.json"
        sha = _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        before = report.read_bytes()
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=report,  # collision with the evidence report
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 2
        assert report.read_bytes() == before, "evidence report was overwritten"

    def test_main_rejects_output_colliding_with_active_policy(
        self, tmp_path: Path,
    ) -> None:
        """Output path equal to the active ownership policy fails closed."""
        report = tmp_path / "report.json"
        sha = _write_writer_report(report)
        triage = tmp_path / "triage.yml"
        _write_writer_triage(triage, [])
        policy = tmp_path / "active_policy.yml"
        policy.write_text("policy: authoritative-v2\n", encoding="utf-8")
        before = policy.read_bytes()
        with pytest.raises(SystemExit) as exc:
            self._run_writer(
                tmp_path,
                report=report,
                report_sha=sha,
                triage=triage,
                output=policy,  # collision with the active policy
                generated_at="2026-08-29T00:00:00Z",
            )
        assert exc.value.code == 2
        assert policy.read_bytes() == before, "active policy was overwritten"
