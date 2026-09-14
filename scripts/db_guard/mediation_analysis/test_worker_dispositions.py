"""Worker-root disposition parse/validate contract tests (GR-14d).

Fail-closed schema: the vocabulary and field set are closed, duplicates
are rejected, and any malformed entry fails the WHOLE file (no partial
acceptance).  Cross-validation rejects unknown worker classes and
registry/disposition conflicts.
"""
from __future__ import annotations

from scripts.db_guard.mediation_analysis.worker_recognition import (
    parse_worker_root_dispositions,
    validate_worker_root_dispositions,
)

_VALID = """\
schemaVersion: 1
dispositions:
- workerFqcn: com.example.IntakeWorker
  disposition: COORDINATOR_DRIVEN_ONE_SHOT
  reason: coordinator-driven one-shot; not startup-scheduled
  owner: '@test'
  linkedIssue: GR-14d
- workerFqcn: com.example.SnoozeWorker
  disposition: EVENT_ACTION_TRIGGERED_ONE_SHOT
  reason: action-triggered one-shot; not startup-scheduled
  owner: '@test'
  linkedIssue: GR-14d
"""


class TestParseWorkerRootDispositions:
    def test_valid_file_parses_sorted(self):
        records, errors = parse_worker_root_dispositions(_VALID)
        assert errors == ()
        assert [record.worker_fqcn for record in records] == [
            "com.example.IntakeWorker",
            "com.example.SnoozeWorker",
        ]
        assert records[0].disposition == "COORDINATOR_DRIVEN_ONE_SHOT"

    def test_invalid_yaml_fails_closed(self):
        records, errors = parse_worker_root_dispositions("dispositions: [")
        assert records == ()
        assert errors == ("GR13_DISPOSITION_MALFORMED",)

    def test_unknown_top_level_key_rejected(self):
        records, errors = parse_worker_root_dispositions(
            _VALID + "extraKey: 1\n"
        )
        assert records == ()
        assert errors == ("GR13_DISPOSITION_MALFORMED",)

    def test_wrong_schema_version_rejected(self):
        records, errors = parse_worker_root_dispositions(
            _VALID.replace("schemaVersion: 1", "schemaVersion: 2")
        )
        assert records == ()
        assert errors == ("GR13_DISPOSITION_MALFORMED",)

    def test_unknown_entry_field_rejected(self):
        records, errors = parse_worker_root_dispositions(
            _VALID + "- workerFqcn: com.example.X\n"
            "  disposition: EVENT_ACTION_TRIGGERED_ONE_SHOT\n"
            "  reason: r\n"
            "  unexpected: true\n"
        )
        assert records == ()
        assert errors == ("GR13_DISPOSITION_MALFORMED",)

    def test_unknown_disposition_value_rejected(self):
        records, errors = parse_worker_root_dispositions(
            _VALID.replace(
                "COORDINATOR_DRIVEN_ONE_SHOT", "SOMEWHERE_ELWHERE_SCHEDULED"
            )
        )
        assert records == ()
        assert errors == ("GR13_DISPOSITION_UNKNOWN_VALUE",)

    def test_duplicate_worker_fqcn_rejected(self):
        records, errors = parse_worker_root_dispositions(
            _VALID + "- workerFqcn: com.example.IntakeWorker\n"
            "  disposition: EVENT_ACTION_TRIGGERED_ONE_SHOT\n"
            "  reason: duplicate\n"
        )
        assert records == ()
        assert errors == ("GR13_DISPOSITION_DUPLICATE",)

    def test_empty_or_missing_reason_rejected(self):
        records, errors = parse_worker_root_dispositions(
            _VALID.replace("reason: coordinator-driven one-shot; not"
                           " startup-scheduled", "reason: ''")
        )
        assert records == ()
        assert errors == ("GR13_DISPOSITION_MALFORMED",)

    def test_one_bad_entry_fails_whole_file(self):
        records, errors = parse_worker_root_dispositions(
            _VALID + "- workerFqcn: com.example.Bad\n"
            "  disposition: NOT_A_VALUE\n"
            "  reason: r\n"
        )
        assert records == ()
        assert "GR13_DISPOSITION_UNKNOWN_VALUE" in errors

    def test_empty_dispositions_list_is_valid(self):
        records, errors = parse_worker_root_dispositions(
            "schemaVersion: 1\ndispositions: []\n"
        )
        assert errors == ()
        assert records == ()


class TestValidateWorkerRootDispositions:
    def test_known_classes_pass(self):
        records, _errors = parse_worker_root_dispositions(_VALID)
        errors = validate_worker_root_dispositions(
            records,
            ("com.example.IntakeWorker", "com.example.SnoozeWorker"),
            ("com.example.OtherWorker",),
        )
        assert errors == ()

    def test_unknown_worker_class_fails_closed(self):
        records, _errors = parse_worker_root_dispositions(_VALID)
        errors = validate_worker_root_dispositions(
            records, ("com.example.SnoozeWorker",), None
        )
        assert errors == ("GR13_DISPOSITION_UNKNOWN_WORKER",)

    def test_registry_conflict_fails_closed(self):
        records, _errors = parse_worker_root_dispositions(_VALID)
        errors = validate_worker_root_dispositions(
            records,
            ("com.example.IntakeWorker", "com.example.SnoozeWorker"),
            ("com.example.IntakeWorker",),
        )
        assert errors == ("GR13_DISPOSITION_REGISTRY_CONFLICT",)

    def test_unavailable_registry_skips_conflict_check(self):
        records, _errors = parse_worker_root_dispositions(_VALID)
        errors = validate_worker_root_dispositions(
            records,
            ("com.example.IntakeWorker", "com.example.SnoozeWorker"),
            None,
        )
        assert errors == ()
