"""GR-15 Step 2 loader tests: the v3 model/loader rejection contract.

Covers the twelve test requirements from
``docs/guardrails/PR-GR-15_enforce_proven_mediation_plan.md`` (Step 2):

1. minimal valid direct v3 entry;
2. valid helper v3 entry;
3. valid worker-mediated v3 entry;
4. every wrong mode/contract pair;
5. missing barrier requirement;
6. legacy ``barrierMode`` rejected;
7. v2 active policy rejected;
8. unknown field rejected;
9. duplicate mutation key rejected;
10. exact identity behavior unchanged (v3 == v2 mutation identity);
11. no wildcard/fallback;
12. controlled error contains no raw source.

The loader never raises: every rejection is a controlled
``PolicyError`` with a closed code and bounded context.
"""
from __future__ import annotations

import os
import sys

import pytest

pytest.importorskip("yaml")

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

try:
    from scripts.db_guard.policy_errors import (
        KNOWN_POLICY_ERROR_CODES,
        POLICY_ERROR_MISSING_FIELD,
        POLICY_ERROR_SCHEMA_MISMATCH,
        POLICY_ERROR_UNKNOWN_FIELD,
        POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID,
        POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY,
    )
    from scripts.db_guard.policy_v2_loader import load_policy_v2
    from scripts.db_guard.policy_v3_loader import load_policy_v3
    from scripts.db_guard.policy_v3_model import BarrierRequirement
except ImportError:  # pragma: no cover - flat mode
    from db_guard.policy_errors import (
        KNOWN_POLICY_ERROR_CODES,
        POLICY_ERROR_MISSING_FIELD,
        POLICY_ERROR_SCHEMA_MISMATCH,
        POLICY_ERROR_UNKNOWN_FIELD,
        POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID,
        POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY,
    )
    from policy_v2_loader import load_policy_v2
    from policy_v3_loader import load_policy_v3
    from policy_v3_model import BarrierRequirement


def _entry_yaml(mode: str, contract: str, extra_field: str = "") -> str:
    return f"""
schemaVersion: 3
entries:
- path: app/src/main/java/com/example/Repo.kt
  ownerFqcn: com.example.Repo
  kind: function
  method: save
  receiver: null
  parameterTypes:
  - Long
  daoAccessor: dao
  daoFqcn: com.example.ExampleDao
  operation: insert{extra_field}
  barrierRequirement:
    mode: {mode}
    contract: {contract}
  reason: GR-15 step-2 fixture row
  owner: "@test"
  linkedIssue: GR-15
"""


def _write(tmp_path, text: str) -> str:
    path = tmp_path / "policy_v3_fixture.yml"
    path.write_text(text, encoding="utf-8")
    return str(path)


# 1-3. minimal valid entries for each mode
def test_minimal_valid_direct_v3_entry(tmp_path):
    document, errors = load_policy_v3(_write(tmp_path, _entry_yaml("direct", "cfg-direct-dominance-v1")))
    assert errors == []
    assert len(document) == 1
    requirement = document[0].barrier_requirement
    assert isinstance(requirement, BarrierRequirement)
    assert requirement.mode.value == "direct"
    assert requirement.contract.value == "cfg-direct-dominance-v1"


def test_valid_helper_v3_entry(tmp_path):
    document, errors = load_policy_v3(
        _write(tmp_path, _entry_yaml("helper", "bounded-helper-mediation-v1"))
    )
    assert errors == []
    assert document[0].barrier_requirement.mode.value == "helper"
    assert document[0].barrier_requirement.contract.value == "bounded-helper-mediation-v1"


def test_valid_worker_mediated_v3_entry(tmp_path):
    document, errors = load_policy_v3(
        _write(tmp_path, _entry_yaml("workerMediated", "bounded-worker-mediation-v1"))
    )
    assert errors == []
    assert document[0].barrier_requirement.mode.value == "workerMediated"
    assert document[0].barrier_requirement.contract.value == "bounded-worker-mediation-v1"


# 4. every wrong mode/contract pair
@pytest.mark.parametrize(
    ("mode", "contract"),
    [
        ("direct", "bounded-helper-mediation-v1"),
        ("direct", "bounded-worker-mediation-v1"),
        ("helper", "cfg-direct-dominance-v1"),
        ("helper", "bounded-worker-mediation-v1"),
        ("workerMediated", "cfg-direct-dominance-v1"),
        ("workerMediated", "bounded-helper-mediation-v1"),
    ],
)
def test_wrong_mode_contract_pair_rejected(tmp_path, mode, contract):
    document, errors = load_policy_v3(_write(tmp_path, _entry_yaml(mode, contract)))
    assert document is None
    assert any(e.code == POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID for e in errors)


def test_unknown_mode_or_contract_rejected(tmp_path):
    document, errors = load_policy_v3(_write(tmp_path, _entry_yaml("direct", "not-a-contract")))
    assert document is None
    assert any(e.code == POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID for e in errors)
    document, errors = load_policy_v3(_write(tmp_path, _entry_yaml("unchecked", "cfg-direct-dominance-v1")))
    assert document is None
    assert any(e.code == POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID for e in errors)


# 5. missing barrier requirement
def test_missing_barrier_requirement_rejected(tmp_path):
    text = _entry_yaml("direct", "cfg-direct-dominance-v1").replace(
        "  barrierRequirement:\n    mode: direct\n    contract: cfg-direct-dominance-v1\n",
        "",
    )
    document, errors = load_policy_v3(_write(tmp_path, text))
    assert document is None
    assert any(
        e.code == POLICY_ERROR_MISSING_FIELD and e.context.get("field") == "barrierRequirement"
        for e in errors
    )


# 6. legacy barrierMode rejected
def test_legacy_barrier_mode_field_rejected(tmp_path):
    text = _entry_yaml("direct", "cfg-direct-dominance-v1").replace(
        "  barrierRequirement:",
        "  barrierMode: helper\n  barrierRequirement:",
    )
    document, errors = load_policy_v3(_write(tmp_path, text))
    assert document is None
    assert any(e.code == POLICY_ERROR_UNKNOWN_FIELD for e in errors)


# 7. v2 active policy rejected
def test_v2_policy_rejected_by_v3_loader(tmp_path):
    v2_text = """
schemaVersion: 2
entries:
- path: app/src/main/java/com/example/Repo.kt
  ownerFqcn: com.example.Repo
  kind: function
  method: save
  receiver: null
  parameterTypes:
  - Long
  daoAccessor: dao
  daoFqcn: com.example.ExampleDao
  operation: insert
  barrierMode: direct
  reason: v2 fixture row
  owner: "@test"
  linkedIssue: GR-15
"""
    document, errors = load_policy_v3(_write(tmp_path, v2_text))
    assert document is None
    assert any(e.code == POLICY_ERROR_SCHEMA_MISMATCH for e in errors)


def test_v2_loader_still_rejects_v3_document(tmp_path):
    document, errors = load_policy_v2(_write(tmp_path, _entry_yaml("direct", "cfg-direct-dominance-v1")))
    assert document is None
    assert errors


# 8. unknown field rejected
def test_unknown_field_rejected(tmp_path):
    text = _entry_yaml("direct", "cfg-direct-dominance-v1").replace(
        "  reason:",
        "  proofDisabled: true\n  reason:",
    )
    document, errors = load_policy_v3(_write(tmp_path, text))
    assert document is None
    assert any(e.code == POLICY_ERROR_UNKNOWN_FIELD for e in errors)


# 9. duplicate mutation key rejected
def test_duplicate_mutation_key_rejected(tmp_path):
    body = _entry_yaml("direct", "cfg-direct-dominance-v1").split("entries:\n", 1)[1]
    path = _write(tmp_path, "schemaVersion: 3\nentries:\n" + body + body)
    document, errors = load_policy_v3(path)
    assert document is None
    duplicate = [e for e in errors if e.code == POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY]
    assert len(duplicate) == 1
    assert duplicate[0].context.get("count") == 1


# 10. exact identity behavior unchanged
def test_v3_mutation_identity_equals_v2_identity(tmp_path):
    v3_document, errors = load_policy_v3(_write(tmp_path, _entry_yaml("helper", "bounded-helper-mediation-v1")))
    assert errors == []
    v2_text = _entry_yaml("helper", "bounded-helper-mediation-v1").replace("schemaVersion: 3", "schemaVersion: 2").replace(
        "  barrierRequirement:\n    mode: helper\n    contract: bounded-helper-mediation-v1\n",
        "  barrierMode: helper\n",
    )
    v2_document, v2_errors = load_policy_v2(_write(tmp_path, v2_text))
    assert v2_errors == []
    v3_key = v3_document[0].mutation_key().canonical_key()
    v2_key = v2_document[0].mutation_key().canonical_key()
    assert v3_key == v2_key


# 11. no wildcard/fallback
def test_wildcard_method_rejected(tmp_path):
    text = _entry_yaml("direct", "cfg-direct-dominance-v1").replace("method: save", "method: save*")
    document, errors = load_policy_v3(_write(tmp_path, text))
    assert document is None
    assert errors


def test_every_error_code_is_in_the_closed_set(tmp_path):
    document, errors = load_policy_v3(_write(tmp_path, _entry_yaml("direct", "not-a-contract")))
    assert document is None
    for error in errors:
        assert error.code in KNOWN_POLICY_ERROR_CODES


# 12. controlled error contains no raw source
def test_error_context_stays_bounded(tmp_path):
    text = _entry_yaml("helper", "bounded-worker-mediation-v1").replace(
        "reason: GR-15 step-2 fixture row",
        "reason: SECRET reason text payload",
    )
    document, errors = load_policy_v3(_write(tmp_path, text))
    assert document is None
    rendered = repr([ (e.code, dict(e.context)) for e in errors ])
    assert "SECRET" not in rendered
    assert "fixture row" not in rendered or "reason" not in rendered
