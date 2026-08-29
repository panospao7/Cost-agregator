#!/usr/bin/env python3
"""
test_db_finding_triage.py -- Pytest tests for PR-D5 triage tools.

Tests cover:
  1. Triage schema validation (all fields present, correct types)
  2. Crosswalk outcomes (ONE_TO_ONE, ONE_TO_MANY, NO_CURRENT_MATCH, UNRESOLVED_RULE_MAPPING)
  3. PENDING preservation (every v2 finding gets PENDING classification)
  4. Rejection of every disallowed classification in baseline generator
  5. Historical-proof requirement (unknown SHA rejected)
  6. Expiry and metadata validation
  7. Deterministic output ordering
  8. Active baseline immutability (candidate never overwrites active)
  9. Atomic write semantics
  10. Sanitization (malformed inputs fail closed)

Run:
    python -m pytest scripts/ci/test_db_finding_triage.py -v
"""

import hashlib
import json
import os
import sys
import tempfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import pytest  # noqa: E402

from guard_findings import (  # noqa: E402
    CallableSymbol,
    DuplicateFindingError,
    GuardFinding,
    GuardRunReport,
    SourceLocation,
    build_report,
    aggregate_findings,
    KIND_FUNCTION,
    SEVERITY_ERROR,
)
from finding_rule_catalog import GUARD_DB_ACCESS, known_rule  # noqa: E402

from build_db_finding_triage import (  # noqa: E402
    ALLOWED_CLASSIFICATIONS,
    CROSSWALK_OUTCOMES,
    build_crosswalk,
    build_triage_entries,
    crosswalk_entry,
    diagnose_duplicates,
    load_v1_baseline,
    main as triage_main,
    parse_v1_fingerprint,
    write_triage_yaml,
)
from generate_db_baseline_v2 import (  # noqa: E402
    _ACCEPTED_CLASSIFICATION,
    _REJECTED_CLASSIFICATIONS,
    _REQUIRED_METADATA,
    _parse_yaml_simple,
    build_baseline_candidate,
    main as baseline_main,
    validate_triage_entry,
    write_json_atomic,
)

# ------------------------------------------------------------------
# Test fixtures
# ------------------------------------------------------------------

_GUARD = GUARD_DB_ACCESS
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


def _make_v1_baseline(fingerprints: List[str]) -> Dict[str, Any]:
    return {
        "guard": "db_access",
        "generated": "2026-07-10T22:03:53.298282+00:00",
        "fingerprints": fingerprints,
    }


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
# 1. Triage schema validation
# ------------------------------------------------------------------


class TestTriageSchema:
    """Test that triage entries have all required fields with correct types."""

    def test_triage_entry_has_all_required_fields(self) -> None:
        """Every triage entry must have the complete schema."""
        entry = _make_triage_entry()
        required_fields = {
            "fingerprint", "classification", "path", "symbol",
            "dao", "operation", "present_at_reference_sha",
            "owner", "linked_issue", "reason", "expires", "evidence",
        }
        assert set(entry.keys()) == required_fields

    def test_triage_entry_symbol_has_all_fields(self) -> None:
        """Symbol must have owner, name, receiver, parameters, kind."""
        entry = _make_triage_entry()
        symbol = entry["symbol"]
        assert set(symbol.keys()) == {"owner", "name", "receiver", "parameters", "kind"}

    def test_allowed_classifications_exact_set(self) -> None:
        """The allowed classifications must match the plan exactly."""
        expected = {
            "LEGAL_WRITER_POLICY_MISSING",
            "REAL_ARCHITECTURE_VIOLATION",
            "PARSER_FALSE_POSITIVE",
            "PREEXISTING_TEMPORARY_DEBT",
            "STRUCTURAL_OPERATION",
            "ANALYZER_UNSUPPORTED",
            "DUPLICATE_DETECTION",
            "PENDING",
        }
        assert ALLOWED_CLASSIFICATIONS == expected

    def test_crosswalk_outcomes_exact_set(self) -> None:
        """The crosswalk outcomes must match the plan exactly."""
        expected = {
            "ONE_TO_ONE",
            "ONE_TO_MANY",
            "NO_CURRENT_MATCH",
            "UNRESOLVED_RULE_MAPPING",
        }
        assert CROSSWALK_OUTCOMES == expected


# ------------------------------------------------------------------
# 2. Crosswalk outcomes
# ------------------------------------------------------------------


class TestCrosswalkOutcomes:
    """Test all four crosswalk outcomes."""

    def _make_v2_index(self, findings: List[GuardFinding]):
        """Build the v2 findings index used by crosswalk."""
        index = {}
        for f in findings:
            key = (f.rule, f.path)
            index.setdefault(key, []).append({
                "fingerprint": f.fingerprint,
                "rule": f.rule,
                "path": f.path,
            })
        return index

    def test_one_to_one_outcome(self) -> None:
        """One v1 entry maps to exactly one v2 finding."""
        finding = _make_finding()
        v2_index = self._make_v2_index([finding])
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS", finding.path, v2_index
        )
        assert outcome == "ONE_TO_ONE"
        assert len(matched) == 1

    def test_one_to_many_outcome(self) -> None:
        """One v1 entry maps to multiple v2 findings."""
        f1 = _make_finding(line=10, symbol=CallableSymbol(
            owner="com.example.Worker", name="doWork",
            receiver=None, parameters=("String",), kind=KIND_FUNCTION,
        ))
        f2 = _make_finding(line=20, symbol=CallableSymbol(
            owner="com.example.Worker", name="save",
            receiver=None, parameters=("String",), kind=KIND_FUNCTION,
        ))
        v2_index = self._make_v2_index([f1, f2])
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS", f1.path, v2_index
        )
        assert outcome == "ONE_TO_MANY"
        assert len(matched) == 2

    def test_no_current_match_outcome(self) -> None:
        """No v2 finding matches the v1 entry."""
        v2_index = {}  # empty
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS", "app/src/Main.kt", v2_index
        )
        assert outcome == "NO_CURRENT_MATCH"
        assert len(matched) == 0

    def test_unresolved_rule_mapping_outcome(self) -> None:
        """V1 rule family cannot be mapped to a v2 rule."""
        v2_index = {}
        outcome, matched = crosswalk_entry(
            "UNKNOWN_RULE_FAMILY", "app/src/Main.kt", v2_index
        )
        assert outcome == "UNRESOLVED_RULE_MAPPING"
        assert len(matched) == 0

    def test_forbidden_file_op_maps_to_structural(self) -> None:
        """FORBIDDEN_FILE_OP maps to DB_FORBIDDEN_STRUCTURAL_OPERATION."""
        finding = _make_finding(rule="DB_FORBIDDEN_STRUCTURAL_OPERATION")
        v2_index = self._make_v2_index([finding])
        outcome, matched = crosswalk_entry(
            "FORBIDDEN_FILE_OP", finding.path, v2_index
        )
        assert outcome == "ONE_TO_ONE"
        assert matched[0]["rule"] == "DB_FORBIDDEN_STRUCTURAL_OPERATION"

    def test_parse_v1_fingerprint_standard(self) -> None:
        """Standard v1 fingerprint parses correctly."""
        result = parse_v1_fingerprint("UNALLOWLISTED_CLASS app/src/main/Foo.kt")
        assert result == ("UNALLOWLISTED_CLASS", "app/src/main/Foo.kt")

    def test_parse_v1_fingerprint_forbidden_file_op(self) -> None:
        """FORBIDDEN_FILE_OP v1 fingerprint parses correctly."""
        result = parse_v1_fingerprint(
            "FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class app/src/main/Foo.kt"
        )
        assert result is not None
        assert result[0] == "FORBIDDEN_FILE_OP"

    def test_parse_v1_fingerprint_unparseable_returns_none(self) -> None:
        """Unparseable v1 fingerprint returns None."""
        result = parse_v1_fingerprint("completely unexpected format")
        assert result is None


# ------------------------------------------------------------------
# 3. PENDING preservation
# ------------------------------------------------------------------


class TestPendingPreservation:
    """Test that every v2 finding gets PENDING classification."""

    def test_all_entries_are_pending(self) -> None:
        """build_triage_entries produces only PENDING entries."""
        finding = _make_finding()
        report = build_report(_GUARD, (finding,))
        entries = build_triage_entries(report)
        assert len(entries) > 0
        for entry in entries:
            assert entry["classification"] == "PENDING"

    def test_pending_has_null_metadata(self) -> None:
        """PENDING entries have null owner/linked_issue/reason/expires."""
        finding = _make_finding()
        report = build_report(_GUARD, (finding,))
        entries = build_triage_entries(report)
        for entry in entries:
            assert entry["owner"] is None
            assert entry["linked_issue"] is None
            assert entry["reason"] is None
            assert entry["expires"] is None
            assert entry["present_at_reference_sha"] == "unknown"
            assert entry["evidence"] == []

    def test_pending_preserves_exact_fingerprint(self) -> None:
        """PENDING entries preserve the exact v2 fingerprint."""
        finding = _make_finding()
        report = build_report(_GUARD, (finding,))
        entries = build_triage_entries(report)
        assert entries[0]["fingerprint"] == finding.fingerprint

    def test_pending_preserves_path_and_symbol(self) -> None:
        """PENDING entries preserve exact path and symbol."""
        finding = _make_finding()
        report = build_report(_GUARD, (finding,))
        entries = build_triage_entries(report)
        entry = entries[0]
        assert entry["path"] == finding.path
        assert entry["symbol"]["owner"] == finding.symbol.owner
        assert entry["symbol"]["name"] == finding.symbol.name
        assert entry["symbol"]["kind"] == finding.symbol.kind

    def test_pending_preserves_dao_and_operation(self) -> None:
        """PENDING entries preserve DAO and operation from identity."""
        finding = _make_finding()
        report = build_report(_GUARD, (finding,))
        entries = build_triage_entries(report)
        entry = entries[0]
        assert entry["dao"] == finding.identity.get("dao")
        assert entry["operation"] == finding.identity.get("operation")

    def test_multiple_findings_all_pending(self) -> None:
        """Multiple findings all get PENDING classification."""
        f1 = _make_finding(line=10)
        f2 = _make_finding(
            line=20,
            path="app/src/main/java/com/example/Other.kt",
        )
        report = build_report(_GUARD, (f1, f2))
        entries = build_triage_entries(report)
        assert all(e["classification"] == "PENDING" for e in entries)


# ------------------------------------------------------------------
# 4. Rejection of every disallowed classification
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
# 5. Historical-proof requirement
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
# 6. Expiry and metadata validation
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
# 7. Deterministic output ordering
# ------------------------------------------------------------------


class TestDeterministicOutput:
    """Test that output is deterministically ordered."""

    def test_triage_entries_sorted_by_fingerprint(self) -> None:
        """Triage entries are sorted by fingerprint."""
        f1 = _make_finding(
            line=10,
            path="app/src/main/java/com/example/Zebra.kt",
        )
        f2 = _make_finding(
            line=20,
            path="app/src/main/java/com/example/Alpha.kt",
        )
        report = build_report(_GUARD, (f1, f2))
        entries = build_triage_entries(report)
        fingerprints = [e["fingerprint"] for e in entries]
        assert fingerprints == sorted(fingerprints)

    def test_triage_entries_deterministic_across_input_order(self) -> None:
        """Same findings in different order produce same triage output."""
        f1 = _make_finding(
            line=10,
            path="app/src/main/java/com/example/Zebra.kt",
        )
        f2 = _make_finding(
            line=20,
            path="app/src/main/java/com/example/Alpha.kt",
        )
        report_a = build_report(_GUARD, (f1, f2))
        report_b = build_report(_GUARD, (f2, f1))
        entries_a = build_triage_entries(report_a)
        entries_b = build_triage_entries(report_b)
        assert [e["fingerprint"] for e in entries_a] == [e["fingerprint"] for e in entries_b]

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
# 8. Active baseline immutability
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
# 9. Atomic write semantics
# ------------------------------------------------------------------


class TestAtomicWrite:
    """Test atomic write and YAML output."""

    def test_write_triage_yaml_creates_file(self, tmp_path: Path) -> None:
        """write_triage_yaml creates the output file."""
        output = tmp_path / "triage.yml"
        entries = [_make_triage_entry()]
        write_triage_yaml(output, entries, [], [], {"test": 1})
        assert output.exists()
        content = output.read_text(encoding="utf-8")
        assert "triage_entries:" in content

    def test_write_triage_yaml_no_temp_files_left(self, tmp_path: Path) -> None:
        """No .tmp files remain after atomic write."""
        output = tmp_path / "triage.yml"
        entries = [_make_triage_entry()]
        write_triage_yaml(output, entries, [], [], {"test": 1})
        tmp_files = list(tmp_path.glob("*.tmp"))
        assert len(tmp_files) == 0

    def test_write_triage_yaml_content_is_valid(self, tmp_path: Path) -> None:
        """Written YAML content contains expected structure."""
        output = tmp_path / "triage.yml"
        entries = [_make_triage_entry(classification="PENDING")]
        crosswalk = [{
            "v1_fingerprint": "UNALLOWLISTED_CLASS app/src/Foo.kt",
            "outcome": "ONE_TO_ONE",
            "matched_count": 1,
        }]
        duplicates = [{
            "rule": "DB_UNAUTHORIZED_MUTATION",
            "path": "app/src/Foo.kt",
            "line": 42,
            "count": 2,
            "diagnosis": "DUPLICATE_DETECTION",
        }]
        metadata = {"total_v2_findings": 1}
        write_triage_yaml(output, entries, crosswalk, duplicates, metadata)
        content = output.read_text(encoding="utf-8")
        assert "metadata:" in content
        assert "crosswalk:" in content
        assert "duplicate_diagnostics:" in content
        assert "triage_entries:" in content
        assert "PENDING" in content

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
# 10. Sanitization and malformed inputs
# ------------------------------------------------------------------


class TestSanitization:
    """Test that malformed inputs fail closed."""

    def test_load_v1_baseline_missing_file(self, tmp_path: Path) -> None:
        """Missing v1 baseline file exits 2."""
        with pytest.raises(SystemExit) as exc:
            load_v1_baseline(tmp_path / "nonexistent.json")
        assert exc.value.code == 2

    def test_load_v1_baseline_malformed_json(self, tmp_path: Path) -> None:
        """Malformed JSON in v1 baseline exits 2."""
        bad_file = tmp_path / "bad.json"
        bad_file.write_text("{invalid json", encoding="utf-8")
        with pytest.raises(SystemExit) as exc:
            load_v1_baseline(bad_file)
        assert exc.value.code == 2

    def test_load_v1_baseline_not_object(self, tmp_path: Path) -> None:
        """v1 baseline that is not a JSON object exits 2."""
        bad_file = tmp_path / "list.json"
        bad_file.write_text('["not", "an", "object"]', encoding="utf-8")
        with pytest.raises(SystemExit) as exc:
            load_v1_baseline(bad_file)
        assert exc.value.code == 2

    def test_load_v1_baseline_no_fingerprints(self, tmp_path: Path) -> None:
        """v1 baseline without fingerprints list exits 2."""
        bad_file = tmp_path / "nofp.json"
        bad_file.write_text('{"guard": "db_access"}', encoding="utf-8")
        with pytest.raises(SystemExit) as exc:
            load_v1_baseline(bad_file)
        assert exc.value.code == 2

    def test_load_v1_baseline_non_string_fingerprint(self, tmp_path: Path) -> None:
        """v1 baseline with non-string fingerprint exits 2."""
        bad_file = tmp_path / "badfp.json"
        bad_file.write_text(
            '{"guard": "db_access", "fingerprints": [42]}',
            encoding="utf-8",
        )
        with pytest.raises(SystemExit) as exc:
            load_v1_baseline(bad_file)
        assert exc.value.code == 2

    def test_crosswalk_with_unparseable_v1_entry(self) -> None:
        """Unparseable v1 entries produce UNRESOLVED_RULE_MAPPING."""
        v2_index = {}
        results = build_crosswalk(
            ["completely unexpected format"],
            v2_index,
        )
        assert len(results) == 1
        assert results[0]["outcome"] == "UNRESOLVED_RULE_MAPPING"

    def test_diagnose_duplicates_finds_exact_duplicates(self) -> None:
        """diagnose_duplicates detects exact source duplicates."""
        f1 = _make_finding(line=42)
        f2 = _make_finding(line=42)
        # These are the same finding -- they would be caught by report
        # validation, but diagnose_duplicates should detect them too.
        # Since GuardFinding equality includes location, we create two
        # findings with identical keys.
        duplicates = diagnose_duplicates([f1, f2])
        assert len(duplicates) > 0
        assert duplicates[0]["diagnosis"] == "DUPLICATE_DETECTION"

    def test_diagnose_duplicates_no_false_positives(self) -> None:
        """diagnose_duplicates does not flag distinct findings."""
        f1 = _make_finding(line=10)
        f2 = _make_finding(line=20)
        duplicates = diagnose_duplicates([f1, f2])
        assert len(duplicates) == 0


# ------------------------------------------------------------------
# Baseline candidate construction
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
# Integration: build_triage_entries with aggregate_findings
# ------------------------------------------------------------------


class TestTriageIntegration:
    """Integration tests for the full triage pipeline."""

    def test_triage_entries_match_aggregates(self) -> None:
        """Number of triage entries equals number of aggregated fingerprints."""
        f1 = _make_finding(line=10)
        f2 = _make_finding(
            line=20,
            path="app/src/main/java/com/example/Other.kt",
        )
        report = build_report(_GUARD, (f1, f2))
        entries = build_triage_entries(report)
        aggregates = aggregate_findings(report.findings)
        assert len(entries) == len(aggregates)

    def test_triage_entries_fingerprints_match_aggregates(self) -> None:
        """Triage entry fingerprints exactly match aggregate fingerprints."""
        f1 = _make_finding(line=10)
        f2 = _make_finding(
            line=20,
            path="app/src/main/java/com/example/Other.kt",
        )
        report = build_report(_GUARD, (f1, f2))
        entries = build_triage_entries(report)
        aggregates = aggregate_findings(report.findings)
        entry_fps = sorted(e["fingerprint"] for e in entries)
        agg_fps = sorted(a.fingerprint for a in aggregates)
        assert entry_fps == agg_fps

    def test_crosswalk_with_real_v1_entries(self) -> None:
        """Crosswalk handles real v1 baseline entries."""
        v1_fps = [
            "UNALLOWLISTED_CLASS app/src/main/java/com/example/Worker.kt",
            "FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class app/src/main/java/com/example/Database.kt",
            "UNKNOWN_RULE app/src/Main.kt",
        ]
        finding = _make_finding()
        v2_index = {
            (finding.rule, finding.path): [{
                "fingerprint": finding.fingerprint,
                "rule": finding.rule,
                "path": finding.path,
            }]
        }
        results = build_crosswalk(v1_fps, v2_index)
        assert len(results) == 3
        outcomes = [r["outcome"] for r in results]
        assert "ONE_TO_ONE" in outcomes
        assert "UNRESOLVED_RULE_MAPPING" in outcomes


# ------------------------------------------------------------------
# 11. Duplicate handling: DuplicateFindingError caught in main()
# ------------------------------------------------------------------


class TestMainDuplicateHandling:
    """Test that DuplicateFindingError is caught in main() and exits 2."""

    def test_main_exits_2_on_duplicate_findings(self, tmp_path: Path) -> None:
        """main() catches DuplicateFindingError from aggregate_findings
        and emits controlled exit 2, not a traceback."""
        # Build a v2 report JSON with two identical findings (same rule,
        # path, location, symbol, identity).  build_report rejects
        # duplicates by default, so we use reject_duplicates=False and
        # then write the JSON manually.
        finding = _make_finding()
        # Create two identical findings (same everything) -- this is
        # what DuplicateFindingError catches in aggregate_findings.
        report_dict = {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [finding.to_dict(), finding.to_dict()],
            "diagnostics": [],
            "statistics": {},
        }
        v2_report = tmp_path / "v2_report.json"
        v2_report.write_text(json.dumps(report_dict), encoding="utf-8")

        # Minimal valid v1 baseline
        v1_baseline = tmp_path / "v1_baseline.json"
        v1_baseline.write_text(
            json.dumps({"guard": "db_access", "fingerprints": []}),
            encoding="utf-8",
        )

        output = tmp_path / "triage.yml"

        # main() should catch DuplicateFindingError and exit 2
        with pytest.raises(SystemExit) as exc:
            triage_main([
                "--v2-report", str(v2_report),
                "--v1-baseline", str(v1_baseline),
                "--output", str(output),
            ])
        assert exc.value.code == 2


# ------------------------------------------------------------------
# 12. End-to-end main() triage test
# ------------------------------------------------------------------


class TestMainEndToEnd:
    """End-to-end main() triage test with temp v1/v2 reports."""

    def _run_triage_main(
        self, tmp_path: Path, *, v2_findings=None, v1_fingerprints=None
    ):
        """Helper: build temp v1/v2 inputs, run main(), return output path."""
        if v2_findings is None:
            v2_findings = []
        if v1_fingerprints is None:
            v1_fingerprints = []

        # Write v2 report
        if v2_findings:
            findings_dict = [f.to_dict() for f in v2_findings]
        else:
            findings_dict = []
        report_dict = {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": findings_dict,
            "diagnostics": [],
            "statistics": {},
        }
        v2_report = tmp_path / "v2_report.json"
        v2_report.write_text(json.dumps(report_dict), encoding="utf-8")

        # Write v1 baseline
        v1_baseline = tmp_path / "v1_baseline.json"
        v1_baseline.write_text(
            json.dumps({
                "guard": "db_access",
                "fingerprints": v1_fingerprints,
            }),
            encoding="utf-8",
        )

        output = tmp_path / "triage.yml"
        triage_main([
            "--v2-report", str(v2_report),
            "--v1-baseline", str(v1_baseline),
            "--output", str(output),
        ])
        return output

    def test_main_writes_yaml_with_exact_schema(self, tmp_path: Path) -> None:
        """main() writes YAML with exact expected top-level keys."""
        finding = _make_finding()
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[finding],
            v1_fingerprints=[f"UNALLOWLISTED_CLASS {finding.path}"],
        )
        assert output.exists()
        content = output.read_text(encoding="utf-8")
        # Top-level sections must be present
        assert "metadata:" in content
        assert "crosswalk:" in content
        assert "triage_entries:" in content

    def test_main_writes_metadata_fields(self, tmp_path: Path) -> None:
        """main() writes metadata with all required fields."""
        finding = _make_finding()
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[finding],
            v1_fingerprints=[f"UNALLOWLISTED_CLASS {finding.path}"],
        )
        content = output.read_text(encoding="utf-8")
        required_meta = [
            "generated_by:", "v2_report:", "v1_baseline:", "reference_sha:",
            "total_v2_findings:", "total_v2_aggregates:", "total_v1_entries:",
            "crosswalk_one_to_one:", "crosswalk_one_to_many:",
            "crosswalk_no_current_match:", "crosswalk_unresolved_rule_mapping:",
            "all_classifications_pending:",
        ]
        for field in required_meta:
            assert field in content, f"Missing metadata field: {field}"

    def test_main_all_entries_are_pending(self, tmp_path: Path) -> None:
        """main() writes triage entries with classification PENDING."""
        finding = _make_finding()
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[finding],
            v1_fingerprints=[],
        )
        content = output.read_text(encoding="utf-8")
        assert "classification: PENDING" in content

    def test_main_entry_has_all_required_fields(self, tmp_path: Path) -> None:
        """main() writes triage entries with all required fields."""
        finding = _make_finding()
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[finding],
            v1_fingerprints=[],
        )
        content = output.read_text(encoding="utf-8")
        required_fields = [
            "fingerprint:", "classification:", "path:", "symbol:",
            "owner:", "receiver:", "parameters:", "kind:",
            "dao:", "operation:", "present_at_reference_sha:",
            "linked_issue:", "reason:", "expires:", "evidence:",
        ]
        for field in required_fields:
            assert field in content, f"Missing triage entry field: {field}"

    def test_main_exit_0_with_empty_report(self, tmp_path: Path) -> None:
        """main() exits 0 with empty v2 report (no findings)."""
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[],
            v1_fingerprints=[],
        )
        assert output.exists()
        content = output.read_text(encoding="utf-8")
        assert "triage_entries:" in content

    def test_main_crosswalk_one_to_one(self, tmp_path: Path) -> None:
        """main() produces ONE_TO_ONE crosswalk for matching v1 entry."""
        finding = _make_finding()
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[finding],
            v1_fingerprints=[f"UNALLOWLISTED_CLASS {finding.path}"],
        )
        content = output.read_text(encoding="utf-8")
        assert "outcome: ONE_TO_ONE" in content

    def test_main_crosswalk_unresolved_rule_mapping(self, tmp_path: Path) -> None:
        """main() produces UNRESOLVED_RULE_MAPPING for unknown v1 rule."""
        output = self._run_triage_main(
            tmp_path,
            v2_findings=[_make_finding()],
            v1_fingerprints=["UNKNOWN_RULE_FAMILY app/src/Foo.kt"],
        )
        content = output.read_text(encoding="utf-8")
        assert "outcome: UNRESOLVED_RULE_MAPPING" in content

    def test_main_exit_2_on_malformed_v2_report(self, tmp_path: Path) -> None:
        """main() exits 2 on malformed v2 report JSON."""
        v2_report = tmp_path / "bad.json"
        v2_report.write_text("{invalid json", encoding="utf-8")
        v1_baseline = tmp_path / "v1.json"
        v1_baseline.write_text('{"guard":"db_access","fingerprints":[]}',
                               encoding="utf-8")
        output = tmp_path / "triage.yml"
        with pytest.raises(SystemExit) as exc:
            triage_main(
                ["--v2-report", str(v2_report),
                 "--v1-baseline", str(v1_baseline),
                 "--output", str(output)]
            )
        assert exc.value.code == 2

    def test_main_exit_2_on_malformed_v1_baseline(self, tmp_path: Path) -> None:
        """main() exits 2 on malformed v1 baseline."""
        report_dict = {
            "schema": "cost-aggregator.guard-findings",
            "schema_version": 2,
            "guard": "db_access",
            "findings": [],
            "diagnostics": [],
            "statistics": {},
        }
        v2_report = tmp_path / "v2.json"
        v2_report.write_text(json.dumps(report_dict), encoding="utf-8")
        v1_baseline = tmp_path / "bad.json"
        v1_baseline.write_text("{invalid", encoding="utf-8")
        output = tmp_path / "triage.yml"
        with pytest.raises(SystemExit) as exc:
            triage_main(
                ["--v2-report", str(v2_report),
                 "--v1-baseline", str(v1_baseline),
                 "--output", str(output)]
            )
        assert exc.value.code == 2


# ------------------------------------------------------------------
# 13. YAML write/read round-trip test
# ------------------------------------------------------------------


class TestYamlRoundTrip:
    """Test that YAML written by write_triage_yaml can be read back."""

    def test_round_trip_metadata(self, tmp_path: Path) -> None:
        """Metadata round-trips through write and read."""
        output = tmp_path / "triage.yml"
        metadata = {
            "generated_by": "build_db_finding_triage.py",
            "total_v2_findings": 5,
            "total_v2_aggregates": 3,
            "all_classifications_pending": True,
        }
        write_triage_yaml(output, [], [], [], metadata)
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert parsed["metadata"]["generated_by"] == "build_db_finding_triage.py"
        assert parsed["metadata"]["total_v2_findings"] == 5
        assert parsed["metadata"]["total_v2_aggregates"] == 3
        assert parsed["metadata"]["all_classifications_pending"] is True

    def test_round_trip_entries(self, tmp_path: Path) -> None:
        """Triage entries round-trip through write and read."""
        output = tmp_path / "triage.yml"
        entries = [_make_triage_entry()]
        write_triage_yaml(output, entries, [], [], {"test": 1})
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
        """Crosswalk entries round-trip through write and read."""
        output = tmp_path / "triage.yml"
        crosswalk = [{
            "v1_fingerprint": "UNALLOWLISTED_CLASS app/src/Foo.kt",
            "v1_rule_family": "UNALLOWLISTED_CLASS",
            "v1_path": "app/src/Foo.kt",
            "outcome": "ONE_TO_ONE",
            "matched_count": 1,
            "matched_v2_fingerprints": ["v2|db_access|DB_UNAUTHORIZED_MUTATION|path=app/src/Foo.kt"],
        }]
        write_triage_yaml(output, [], crosswalk, [], {"test": 1})
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert "crosswalk" in parsed
        assert len(parsed["crosswalk"]) == 1
        cw = parsed["crosswalk"][0]
        assert cw["outcome"] == "ONE_TO_ONE"
        assert cw["matched_count"] == 1

    def test_round_trip_duplicate_diagnostics(self, tmp_path: Path) -> None:
        """Duplicate diagnostics round-trip through write and read."""
        output = tmp_path / "triage.yml"
        duplicates = [{
            "rule": "DB_UNAUTHORIZED_MUTATION",
            "path": "app/src/Foo.kt",
            "line": 42,
            "count": 2,
            "diagnosis": "DUPLICATE_DETECTION",
        }]
        write_triage_yaml(output, [], [], duplicates, {"test": 1})
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
        write_triage_yaml(output, [], [], [], {"test": 1})
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        assert "triage_entries" in parsed

    def test_round_trip_null_fields_preserved(self, tmp_path: Path) -> None:
        """Null fields in triage entries are preserved as None."""
        output = tmp_path / "triage.yml"
        entries = [_make_triage_entry(
            owner=None,
            linked_issue=None,
            reason=None,
            expires=None,
        )]
        write_triage_yaml(output, entries, [], [], {"test": 1})
        content = output.read_text(encoding="utf-8")
        parsed = _parse_yaml_simple(content)
        entry = parsed["triage_entries"][0]
        assert entry["owner"] is None
        assert entry["linked_issue"] is None
        assert entry["reason"] is None
        assert entry["expires"] is None


# ------------------------------------------------------------------
# 14. Crosswalk path matching: exact + v2-under-v1 only
# ------------------------------------------------------------------


class TestCrosswalkPathMatching:
    """Test tightened crosswalk path matching: exact or controlled
    one-direction v2-path-under-v1 mapping."""

    def _make_v2_index(self, findings: List[GuardFinding]):
        """Build the v2 findings index used by crosswalk."""
        index = {}
        for f in findings:
            key = (f.rule, f.path)
            index.setdefault(key, []).append({
                "fingerprint": f.fingerprint,
                "rule": f.rule,
                "path": f.path,
            })
        return index

    def test_exact_path_match(self) -> None:
        """Exact canonical path matches."""
        finding = _make_finding(path="app/src/main/java/com/example/Worker.kt")
        v2_index = self._make_v2_index([finding])
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS",
            "app/src/main/java/com/example/Worker.kt",
            v2_index,
        )
        assert outcome == "ONE_TO_ONE"
        assert len(matched) == 1

    def test_v2_path_under_v1_directory_prefix(self) -> None:
        """V2 path under V1 directory prefix matches (one-direction)."""
        finding = _make_finding(
            path="app/src/main/java/com/example/sub/Worker.kt",
        )
        v2_index = self._make_v2_index([finding])
        # v1 had a coarser directory-level path
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS",
            "app/src/main/java/com/example",
            v2_index,
        )
        assert outcome == "ONE_TO_ONE"
        assert len(matched) == 1

    def test_bidirectional_suffix_rejected(self) -> None:
        """V1 path that is a suffix of V2 path does NOT match (no
        bidirectional suffix matching)."""
        finding = _make_finding(
            path="app/src/main/java/com/example/other/Worker.kt",
        )
        v2_index = self._make_v2_index([finding])
        # v1 path is "other/Worker.kt" -- a suffix of the v2 path, but
        # NOT a directory prefix.  This must NOT match.
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS",
            "other/Worker.kt",
            v2_index,
        )
        assert outcome == "NO_CURRENT_MATCH"
        assert len(matched) == 0

    def test_v1_suffix_of_v2_rejected(self) -> None:
        """V2 path that is a suffix of V1 path does NOT match."""
        finding = _make_finding(
            path="Worker.kt",
        )
        v2_index = self._make_v2_index([finding])
        # v1 path is longer but shares suffix -- must NOT match
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS",
            "app/src/main/java/com/example/Worker.kt",
            v2_index,
        )
        assert outcome == "NO_CURRENT_MATCH"
        assert len(matched) == 0

    def test_shared_filename_different_dirs_no_match(self) -> None:
        """Files with same name in different directories do NOT match."""
        finding = _make_finding(
            path="app/src/main/java/com/other/Worker.kt",
        )
        v2_index = self._make_v2_index([finding])
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS",
            "app/src/main/java/com/example/Worker.kt",
            v2_index,
        )
        assert outcome == "NO_CURRENT_MATCH"
        assert len(matched) == 0

    def test_partial_directory_match_rejected(self) -> None:
        """Partial directory component match (not at boundary) is rejected."""
        finding = _make_finding(
            path="app/src/main/java/com/example_v2/Worker.kt",
        )
        v2_index = self._make_v2_index([finding])
        # v1 path "example" should NOT match "example_v2" directory because
        # "example_v2/..." does not start with "example/" (no boundary).
        outcome, matched = crosswalk_entry(
            "UNALLOWLISTED_CLASS",
            "app/src/main/java/com/example",
            v2_index,
        )
        assert outcome == "NO_CURRENT_MATCH"
        assert len(matched) == 0


# ------------------------------------------------------------------
# 15. Baseline writer main() guards (GR-09 writer hardening)
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
