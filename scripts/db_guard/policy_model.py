"""Authoritative DB ownership policy v2 identity model (PR-01).

One entry authorizes exactly one DAO mutation pair in exactly one callable.
barrierMode is metadata only in PR-01.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Iterable, Optional, Tuple


class CallableKind(str, Enum):
    """Identity of the callable that owns a DAO mutation."""

    FUNCTION = 'function'
    CONSTRUCTOR = 'constructor'
    PROPERTY_GETTER = 'property_getter'
    PROPERTY_SETTER = 'property_setter'
    TOP_LEVEL_FUNCTION = 'top_level_function'
    INITIALIZER = 'initializer'


class BarrierMode(str, Enum):
    """How the owning callable reaches the DAO (metadata only in PR-01)."""

    DIRECT = 'direct'
    HELPER = 'helper'
    WORKER_MEDIATED = 'workerMediated'


def _require_non_empty_str(value, label):
    if not isinstance(value, str) or not value:
        raise TypeError('%s must be a non-empty string' % (label,))
    return value


@dataclass(frozen=True)
class CallableKey:
    """Immutable identity of exactly one callable."""

    path: str
    owner_fqcn: str
    kind: CallableKind
    method: str
    receiver: Optional[str]
    parameter_types: Tuple[str, ...]

    def __post_init__(self):
        _require_non_empty_str(self.path, 'path')
        _require_non_empty_str(self.owner_fqcn, 'owner_fqcn')
        if '.' not in self.owner_fqcn:
            raise TypeError('owner_fqcn must contain "."')
        if not isinstance(self.kind, CallableKind):
            raise TypeError('kind must be a CallableKind')
        _require_non_empty_str(self.method, 'method')
        if self.receiver is not None and not isinstance(self.receiver, str):
            raise TypeError('receiver must be None or a string')
        if not isinstance(self.parameter_types, tuple) or not all(
            isinstance(item, str) for item in self.parameter_types
        ):
            raise TypeError('parameter_types must be a tuple of strings')

    def canonical_key(self) -> str:
        receiver_part = self.receiver if self.receiver is not None else 'null'
        return '|'.join(
            [
                self.path,
                self.owner_fqcn,
                self.kind.value,
                self.method,
                receiver_part,
                ','.join(self.parameter_types),
            ]
        )


@dataclass(frozen=True)
class MutationKey:
    """Immutable identity of one DAO mutation inside one callable."""

    callable_key: CallableKey
    dao_accessor: str
    dao_fqcn: str
    operation: str

    def __post_init__(self):
        _require_non_empty_str(self.dao_accessor, 'dao_accessor')
        _require_non_empty_str(self.dao_fqcn, 'dao_fqcn')
        _require_non_empty_str(self.operation, 'operation')

    def canonical_key(self) -> str:
        return (
            self.callable_key.canonical_key()
            + '|'
            + self.dao_accessor
            + '|'
            + self.dao_fqcn
            + '|'
            + self.operation
        )


@dataclass(frozen=True)
class PolicyEntry:
    """One authorized DAO mutation pair in exactly one callable."""

    path: str
    owner_fqcn: str
    kind: CallableKind
    method: str
    receiver: Optional[str]
    parameter_types: Tuple[str, ...]
    dao_accessor: str
    dao_fqcn: str
    operation: str
    barrier_mode: BarrierMode
    reason: str
    owner: str
    linked_issue: str

    def __post_init__(self):
        _require_non_empty_str(self.path, 'path')
        _require_non_empty_str(self.owner_fqcn, 'owner_fqcn')
        if '.' not in self.owner_fqcn:
            raise TypeError('owner_fqcn must contain "."')
        if not isinstance(self.kind, CallableKind):
            raise TypeError('kind must be a CallableKind')
        _require_non_empty_str(self.method, 'method')
        if self.receiver is not None and not isinstance(self.receiver, str):
            raise TypeError('receiver must be None or a string')
        if not isinstance(self.parameter_types, tuple) or not all(
            isinstance(item, str) for item in self.parameter_types
        ):
            raise TypeError('parameter_types must be a tuple of strings')
        _require_non_empty_str(self.dao_accessor, 'dao_accessor')
        _require_non_empty_str(self.dao_fqcn, 'dao_fqcn')
        _require_non_empty_str(self.operation, 'operation')
        if not isinstance(self.barrier_mode, BarrierMode):
            raise TypeError('barrier_mode must be a BarrierMode')
        _require_non_empty_str(self.reason, 'reason')
        _require_non_empty_str(self.owner, 'owner')
        _require_non_empty_str(self.linked_issue, 'linked_issue')

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
        return MutationKey(
            callable_key=self.callable_key(),
            dao_accessor=self.dao_accessor,
            dao_fqcn=self.dao_fqcn,
            operation=self.operation,
        )


def match_mutation(
    entry,
    *,
    path,
    owner_fqcn,
    kind,
    receiver,
    parameter_types,
    dao_accessor,
    dao_fqcn,
    operation,
    method,
) -> bool:
    """Exact-match every identity field; no wildcards."""
    callable_key = entry.callable_key()
    mutation_key = entry.mutation_key()
    return (
        callable_key.path == path
        and callable_key.owner_fqcn == owner_fqcn
        and callable_key.kind == CallableKind(kind)
        and callable_key.method == method
        and callable_key.receiver == receiver
        and callable_key.parameter_types == tuple(parameter_types)
        and mutation_key.dao_accessor == dao_accessor
        and mutation_key.dao_fqcn == dao_fqcn
        and mutation_key.operation == operation
    )
