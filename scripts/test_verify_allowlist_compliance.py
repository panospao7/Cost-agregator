"""Tests for verify_allowlist_compliance.py"""
import pytest
import os
import sys
import tempfile
from pathlib import Path

# Import the module under test
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_allowlist_compliance as vac


# ── Helpers ────────────────────────────────────────────────────────────────────

def _write_yaml(tmp_path, content):
    """Write a YAML file and return its path."""
    yaml_file = tmp_path / "test_allowlist.yml"
    yaml_file.write_text(content, encoding="utf-8")
    return str(yaml_file)


def _write_text(tmp_path, content):
    """Write a text allowlist file and return its path."""
    text_file = tmp_path / "test_allowlist.txt"
    text_file.write_text(content, encoding="utf-8")
    return str(text_file)


def _make_project_root_yaml(tmp_path):
    """Create a minimal project root structure for YAML tests."""
    return str(tmp_path)


# ── YAML Allowlist Tests ───────────────────────────────────────────────────────

def test_valid_yaml_entry(tmp_path):
    """A YAML entry with all required fields passes compliance check."""
    yaml_content = """allowed_writers:
  - class: TestClass
    daos: [testDao]
    reason: "Test purpose"
    owner: "@tester"
    linked_issue: "MIT-999"
"""
    yaml_file = _write_yaml(tmp_path, yaml_content)
    project_root = _make_project_root_yaml(tmp_path)
    violations = vac.check_yaml_allowlist(yaml_file, project_root=project_root)
    assert len(violations) == 0, f"Expected no violations, got: {violations}"


def test_missing_reason(tmp_path):
    """Entry without reason fails compliance check."""
    yaml_content = """allowed_writers:
  - class: BadClass
    daos: [testDao]
    owner: "@tester"
"""
    yaml_file = _write_yaml(tmp_path, yaml_content)
    project_root = _make_project_root_yaml(tmp_path)
    violations = vac.check_yaml_allowlist(yaml_file, project_root=project_root)
    assert len(violations) > 0, "Expected violation for missing reason"
    assert any("MISSING_REASON" in v for v in violations), \
        f"Expected MISSING_REASON violation, got: {violations}"


def test_missing_owner_before_grace(tmp_path, monkeypatch):
    """Entry without owner warns during grace period (before 2026-10-01)."""
    # Freeze today to before the grace period end
    import datetime
    monkeypatch.setattr(vac.datetime, 'date',
                        type('FrozenDate', (datetime.date,),
                             {'today': staticmethod(lambda: datetime.date(2026, 8, 1)),
                              'fromisoformat': datetime.date.fromisoformat,
                              '__sub__': datetime.date.__sub__}))

    yaml_content = """allowed_writers:
  - class: NoOwnerClass
    daos: [testDao]
    reason: "Valid reason but no owner"
"""
    yaml_file = _write_yaml(tmp_path, yaml_content)
    project_root = _make_project_root_yaml(tmp_path)
    violations = vac.check_yaml_allowlist(yaml_file, project_root=project_root)
    # During grace period, missing owner is a WARNING, not a violation
    missing_owner_violations = [v for v in violations if "MISSING_OWNER" in v]
    assert len(missing_owner_violations) == 0, \
        f"Expected no MISSING_OWNER violations during grace period, got: {missing_owner_violations}"


def test_expired_entry(tmp_path, monkeypatch):
    """Entry with past expiry date fails compliance check."""
    # Freeze today to after the expiry date
    import datetime
    monkeypatch.setattr(vac.datetime, 'date',
                        type('FrozenDate', (datetime.date,),
                             {'today': staticmethod(lambda: datetime.date(2026, 12, 1)),
                              'fromisoformat': datetime.date.fromisoformat,
                              '__sub__': datetime.date.__sub__}))

    yaml_content = """allowed_writers:
  - class: ExpiredClass
    daos: [testDao]
    reason: "Temporary reason"
    owner: "@tester"
    allowed_until: "2026-06-01"
"""
    yaml_file = _write_yaml(tmp_path, yaml_content)
    project_root = _make_project_root_yaml(tmp_path)
    violations = vac.check_yaml_allowlist(yaml_file, project_root=project_root)
    assert any("EXPIRED_ALLOWED_UNTIL" in v for v in violations), \
        f"Expected EXPIRED_ALLOWED_UNTIL violation, got: {violations}"


# ── Text Allowlist Tests ───────────────────────────────────────────────────────

def test_valid_text_entry(tmp_path):
    """Text allowlist with '# reason' passes compliance check."""
    content = """# Header comment
DiagnosticEventWriter.kt # owns PipelineDiagnosticEvent construction
"""
    text_file = _write_text(tmp_path, content)
    project_root = _make_project_root_yaml(tmp_path)
    violations = vac.check_text_allowlist(text_file, project_root=project_root)
    assert len(violations) == 0, f"Expected no violations, got: {violations}"


def test_missing_text_reason(tmp_path):
    """Text allowlist without '# reason' fails compliance check."""
    content = """SomeFile.kt
Another.kt # valid reason
"""
    text_file = _write_text(tmp_path, content)
    project_root = _make_project_root_yaml(tmp_path)
    violations = vac.check_text_allowlist(text_file, project_root=project_root)
    assert len(violations) > 0, "Expected violation for missing reason comment"
    assert any("MISSING_REASON_COMMENT" in v for v in violations), \
        f"Expected MISSING_REASON_COMMENT violation, got: {violations}"


# ── CLI / Exit Code Tests ──────────────────────────────────────────────────────

def test_fail_on_violation_exit_code(tmp_path, monkeypatch):
    """Verify exit code 1 with --fail-on-violation when violations exist."""
    # Create a YAML allowlist with violations
    yaml_content = """allowed_writers:
  - class: BadClass
    daos: [testDao]
"""
    yaml_file = _write_yaml(tmp_path, yaml_content)

    # Override YAML_ALLOWLISTS to point to our test file and TEXT_ALLOWLISTS to empty
    monkeypatch.setattr(vac, 'YAML_ALLOWLISTS', [yaml_file])
    monkeypatch.setattr(vac, 'TEXT_ALLOWLISTS', [])
    monkeypatch.setattr(vac, 'PROJECT_ROOT', str(tmp_path))

    # Simulate --fail-on-violation
    monkeypatch.setattr(sys, 'argv', [
        'verify_allowlist_compliance.py', '--fail-on-violation',
        '--root', str(tmp_path)
    ])

    with pytest.raises(SystemExit) as exc_info:
        vac.main()
    assert exc_info.value.code == 1, \
        f"Expected exit code 1 with --fail-on-violation, got {exc_info.value.code}"
