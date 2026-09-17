"""Typed model for DB ownership policy v3 documents (PR-GR-15).

v3 makes proven mediation part of validated policy state: every entry
carries an immutable :class:`BarrierRequirement` pairing a closed
:class:`BarrierProofMode` with the one :class:`BarrierProofContract` the
normal DB gate will prove for it at scan time.  Metadata alone can no
longer authorize a mutation.

The mutation ownership identity is UNCHANGED from v2:
:meth:`PolicyEntryV3.mutation_key` returns the exact
:class:`~scripts.db_guard.policy_model.MutationKey` a v2 entry with the
same identity fields would return.  The barrier requirement is validated
policy state but is deliberately NOT part of the mutation identity or any
fingerprint.

Prohibited v2 spellings (``barrierMode``, ``metadataOnly``, free-form
contracts) are rejected by the loader, never silently upgraded.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

try:  # package mode: imported as ``scripts.db_guard.policy_v3_model``
    from .policy_model import CallableKind, CallableKey, MutationKey
except ImportError:  # pragma: no cover - flat mode: standalone tools put ``scripts`` on sys.path
    from policy_model import CallableKind, CallableKey, MutationKey

__all__ = [
    "BarrierProofMode",
    "BarrierProofContract",
    "PROOF_CONTRACT_BY_MODE",
    "BarrierRequirement",
    "PolicyEntryV3",
]


class BarrierProofMode(str, Enum):
    """Closed proof-mode vocabulary (PR-GR-15 target schema)."""

    DIRECT = "direct"
    HELPER = "helper"
    WORKER_MEDIATED = "workerMediated"


class BarrierProofContract(str, Enum):
    """Closed proof-contract vocabulary (one contract per mode)."""

    CFG_DIRECT_DOMINANCE_V1 = "cfg-direct-dominance-v1"
    BOUNDED_HELPER_MEDIATION_V1 = "bounded-helper-mediation-v1"
    BOUNDED_WORKER_MEDIATION_V1 = "bounded-worker-mediation-v1"


#: The closed mode -> contract mapping.  No other pair is accepted.
PROOF_CONTRACT_BY_MODE = {
    BarrierProofMode.DIRECT: BarrierProofContract.CFG_DIRECT_DOMINANCE_V1,
    BarrierProofMode.HELPER: BarrierProofContract.BOUNDED_HELPER_MEDIATION_V1,
    BarrierProofMode.WORKER_MEDIATED: BarrierProofContract.BOUNDED_WORKER_MEDIATION_V1,
}


@dataclass(frozen=True)
class BarrierRequirement:
    """The proof the gate must derive from live source for one entry."""

    mode: BarrierProofMode
    contract: BarrierProofContract

    def __post_init__(self):
        expected = PROOF_CONTRACT_BY_MODE.get(self.mode)
        if expected is None or expected is not self.contract:
            raise ValueError("barrier contract does not fit the mode")


@dataclass(frozen=True)
class PolicyEntryV3:
    """One exact v3 policy mutation with its required proof contract."""

    path: str
    owner_fqcn: str
    kind: CallableKind
    method: str
    receiver: str
    parameter_types: tuple
    dao_accessor: str
    dao_fqcn: str
    operation: str
    barrier_requirement: BarrierRequirement
    reason: str
    owner: str
    linked_issue: str

    def callable_key(self) -> CallableKey:
        return CallableKey(
            path=self.path,
            owner_fqcn=self.owner_fqcn,
            kind=self.kind,
            method=self.method,
            receiver=self.receiver,
            parameter_types=self.parameter_types,
        )

    def mutation_key(self) -> MutationKey:
        """Exact v2-equivalent mutation identity (requirement excluded)."""
        return MutationKey(
            callable_key=self.callable_key(),
            dao_accessor=self.dao_accessor,
            dao_fqcn=self.dao_fqcn,
            operation=self.operation,
        )
