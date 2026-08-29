"""Boundary tests against the repository's real policy documents.

Post-activation state (GR-07/GR-08): the active DB ownership gate document
``config/guards/db_ownership_policy.yml`` IS the activated v2 policy
(``schemaVersion: 2`` with the v2 contract's ``kind`` / ``ownerFqcn``
fields) and is accepted by the v2 loader
(``scripts.db_guard.policy_v2_loader.load_policy_v2``).  The pre-activation
v1 bytes — top-level ``entries`` without ``schemaVersion``, with per-entry
legacy keys (``class``, ``daos``, ``signature``, ``barrier_required``, ...)
— live on only in the archive
``config/guards/db_ownership_policy.legacy.yml``.  The v2 loader must keep
rejecting such v1-shaped documents outright rather than partially accept
them — there is no silent upgrade path from v1.

The signatures candidate
``config/guards/db_ownership_policy.signatures.candidate.yml`` is a valid
v2 document (``schemaVersion: 2``).  Post-GR-08 it carries the 472 entries
of the activated v2 policy (one entry per canonical mutation key); the v2
loader must accept it with zero errors.

These tests pin both boundaries against the real repository files.

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

ARCHIVED_V1_POLICY_PATH = (
    REPO_ROOT / "config" / "guards" / "db_ownership_policy.legacy.yml"
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
# Characterization: archived v1 stays rejected; active-path v2 and the
# signatures candidate are accepted
# ===========================================================================


def test_archived_v1_policy_rejected_by_v2_loader():
    """The archived v1 policy is not a valid v2 document.

    Post-activation the active path (``db_ownership_policy.yml``) holds v2
    and is accepted; the pre-activation v1 bytes survive only in the archive
    (``db_ownership_policy.legacy.yml``).  The v2 loader must keep rejecting
    that v1 shape outright — no silent upgrade path from v1.
    """
    document, errors = _load_or_skip(ARCHIVED_V1_POLICY_PATH)
    assert document is None, (
        "archived v1 policy must not be accepted by the v2 loader"
    )
    assert errors, "rejection must report at least one controlled error"


def test_candidate_signatures_accepted_by_v2_loader():
    """The tracked signatures candidate is a valid v2 document with 472 entries.

    Derivation of the 472 pin: the candidate is the activation artifact that
    was promoted over the active path (scripts/ci/promote_db_policy_v2.py),
    so it carries the same entries as the activated v2 policy document
    ``config/guards/db_ownership_policy.yml`` — 472 entries post-GR-08, one
    per canonical mutation key.  The earlier PR-GR-05 truth (55 unique keys
    folded from the 99 legacy v1 inputs) is superseded by the GR-08 policy
    growth; that 55 remains pinned as migration accounting over the ARCHIVED
    v1 input by ``test_migrate_db_policy_signatures.py``.
    """
    document, errors = _load_or_skip(CANDIDATE_SIGNATURES_PATH)
    assert document is not None, (
        "tracked signatures candidate must be accepted by the v2 loader"
    )
    assert len(document) == 472, (
        "candidate must carry exactly the 472 current signature entries"
    )
    assert not errors, "acceptance must report zero errors"


def test_rejection_codes_are_controlled():
    """Every reported error code belongs to the closed controlled set."""
    collected = []
    for path in (ARCHIVED_V1_POLICY_PATH, CANDIDATE_SIGNATURES_PATH):
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
