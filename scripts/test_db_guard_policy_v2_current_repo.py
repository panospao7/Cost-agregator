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
v2 document (``schemaVersion: 2``).  Post-GR-08 (and post-GR-14c truth
sync, 2026-09-06) it carried the 475 entries; post-GR-14h Pattern E
removals plus the GR-14u34 dead-writer singles (2026-09-11) it carries the 432 entries
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
    """The tracked signatures candidate is a valid v2 document with 432 entries.

    Derivation of the 432 pin: the candidate is the activation artifact that
    was promoted over the active path (scripts/ci/promote_db_policy_v2.py),
    so it carries the same entries as the activated v2 policy document
    ``config/guards/db_ownership_policy.yml`` — 475 entries post-GR-08,
    471 post-GR-14h, 461 post-GR-14u, 451 post-GR-14u2, 441 post-GR-14u3, 434 post-GR-14u4 (dead-writer tranche 5), 432 post-GR-14u34 (zero-caller singles: ExpenseGroupDao.insertGroupWithMembers, RoomRecurringLifecycleEventWriter.writeDiagnostic),
    one per canonical mutation key.  Drift history: the GR-14b
    EXACT_IDENTITY_MOVE regenerated the candidate 472 -> 471 (sanctioned
    --generate path); the post-GR-14c truth sync (2026-09-06) closed the
    8-key candidate/active drift — the 2 dead legacy keys GR-14c removed
    from the active policy were removed from the migration input (fold
    56/43/46 -> 54/43/44 over 97 inputs) and the 6 post-activation rows
    entered as reviewed GR-14 seeds — bringing the candidate back to
    exactly the active policy key set (475).  The GR-14h Pattern E tranche
    (2026-09-07) removed the 3 dead callables' 4 rows from the generation
    inputs (3 GR-08l2/e2 seed rows + 4 legacy rows incl. the fold sources),
    regenerating the tracked pair at 471 (GR-14u: 461; GR-14u2: 451; GR-14u3: 441; GR-14u4: 434; GR-14u34: 432); promotion realigns active.  The earlier PR-GR-05 truth
    (55 unique keys folded from the pre-sync legacy v1 inputs) is
    superseded by the GR-08 policy growth; that 55 remains pinned as
    migration accounting over the ARCHIVED v1 input by
    ``test_migrate_db_policy_signatures.py``.
    """
    document, errors = _load_or_skip(CANDIDATE_SIGNATURES_PATH)
    assert document is not None, (
        "tracked signatures candidate must be accepted by the v2 loader"
    )
    assert len(document) == 432, (
        "candidate must carry exactly the 432 current signature entries"
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
