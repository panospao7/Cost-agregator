"""Characterization tests: the v2 loader rejects the repository's real v1 documents.

Transitional state (pre GR-07): the active DB ownership gate is still the
v1 policy document ``config/guards/db_ownership_policy.yml``, alongside the
v1-shaped signatures candidate
``config/guards/db_ownership_policy.signatures.candidate.yml``.  Both are
v1 documents: top-level ``entries`` without ``schemaVersion``, and
per-entry legacy keys (``class``, ``daos``, ``signature``,
``barrier_required``, ...) instead of the v2 contract's ``kind`` /
``ownerFqcn`` fields.

The v2 loader (``scripts.db_guard.policy_v2_loader.load_policy_v2``) must
reject such v1-shaped documents outright rather than partially accept
them — there is no silent upgrade path from v1.  These tests pin that
boundary against the real repository files until GR-07 activation flips
the gate to a v2 document.

Paths are derived from ``__file__`` (repo root = ``parents[1]``).  Each
test skips gracefully when a config file is absent so the suite stays
portable outside a full checkout.
"""

from __future__ import annotations

from pathlib import Path

import pytest

try:
    from scripts.db_guard.policy_v2_loader import load_policy_v2
except ImportError:  # flat mode: standalone tools put ``scripts`` on sys.path
    from db_guard.policy_v2_loader import load_policy_v2

try:
    from scripts.db_guard.policy_errors import KNOWN_POLICY_ERROR_CODES
except ImportError:  # flat mode: standalone tools put ``scripts`` on sys.path
    from db_guard.policy_errors import KNOWN_POLICY_ERROR_CODES


# ---------------------------------------------------------------------------
# Real repository fixtures (paths derived from __file__)
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[1]

ACTIVE_V1_POLICY_PATH = (
    REPO_ROOT / "config" / "guards" / "db_ownership_policy.yml"
)
CANDIDATE_SIGNATURES_PATH = (
    REPO_ROOT / "config" / "guards" / "db_ownership_policy.signatures.candidate.yml"
)


def _load_or_skip(path):
    """Run ``load_policy_v2`` against *path*, skipping if the file is absent."""
    if not path.is_file():
        pytest.skip(f"config file not present in this checkout: {path.name}")
    return load_policy_v2(str(path))


# ===========================================================================
# Characterization: v1 remains the active gate; v2 loader rejects v1 shapes
# ===========================================================================


def test_active_v1_policy_rejected_by_v2_loader():
    """The active v1 policy is not a valid v2 document."""
    document, errors = _load_or_skip(ACTIVE_V1_POLICY_PATH)
    assert document is None, (
        "active v1 policy must not be accepted by the v2 loader"
    )
    assert errors, "rejection must report at least one controlled error"


def test_candidate_signatures_rejected_by_v2_loader():
    """The signatures candidate lacks kind/ownerFqcn and uses class keys."""
    document, errors = _load_or_skip(CANDIDATE_SIGNATURES_PATH)
    assert document is None, (
        "v1-shaped signatures candidate must not be accepted by the v2 loader"
    )
    assert errors, "rejection must report at least one controlled error"


def test_rejection_codes_are_controlled():
    """Every reported error code belongs to the closed controlled set."""
    collected = []
    for path in (ACTIVE_V1_POLICY_PATH, CANDIDATE_SIGNATURES_PATH):
        if not path.is_file():
            continue
        _, errors = load_policy_v2(str(path))
        collected.extend(errors)
    if not collected:
        pytest.skip("no config policy files present in this checkout")
    for error in collected:
        assert error.code in KNOWN_POLICY_ERROR_CODES, (
            f"uncontrolled error code leaked into diagnostics: {error.code!r}"
        )
