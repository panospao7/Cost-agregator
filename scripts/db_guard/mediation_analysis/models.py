"""Frozen data models for the DB guard mediation analysis.

Bounded, privacy-safe structures only: no raw source text, no raw file
contents, and no free-form exception text are stored in these models.
"""

from dataclasses import dataclass
from enum import Enum


class ResolutionState(Enum):
    EXACT_SYNCHRONOUS = "exact_synchronous"
    EXACT_CANONICAL_SCOPE = "exact_canonical_scope"
    AMBIGUOUS_TARGET = "ambiguous_target"
    UNRESOLVED_TARGET = "unresolved_target"
    VIRTUAL_DISPATCH = "virtual_dispatch"
    INTERFACE_DISPATCH = "interface_dispatch"
    FUNCTION_REFERENCE = "function_reference"
    ESCAPING_LAMBDA = "escaping_lambda"
    ASYNC_DISPATCH = "async_dispatch"
    RECURSIVE_UNSUPPORTED = "recursive_unsupported"
    EXTERNAL_ENTRY = "external_entry"
    UNSUPPORTED_SYNTAX = "unsupported_syntax"


class RootKind(Enum):
    WORKER_DO_WORK = "worker_do_work"
    FRAMEWORK_CALLBACK = "framework_callback"
    PUBLIC_OR_PROTECTED_EXTERNAL = "public_or_protected_external"
    TOP_LEVEL_EXTERNAL = "top_level_external"
    CONSTRUCTOR_EXTERNAL = "constructor_external"
    UNKNOWN_EXTERNAL = "unknown_external"


class GuardContext(Enum):
    NONE = "none"
    DIRECT_BARRIER = "direct_barrier"
    WORKER_GUARD = "worker_guard"


class ProofState(Enum):
    PROVEN_HELPER = "proven_helper"
    PROVEN_WORKER_MEDIATED = "proven_worker_mediated"
    COUNTEREXAMPLE_UNGUARDED_CALL_PATH = "counterexample_unguarded_call_path"
    COUNTEREXAMPLE_NON_WORKER_ROOT = "counterexample_non_worker_root"
    COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE = "counterexample_outside_worker_scope"
    UNPROVEN_EXTERNAL_ENTRY = "unproven_external_entry"
    UNPROVEN_AMBIGUOUS_CALL = "unproven_ambiguous_call"
    UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK = "unproven_async_or_escaping_callback"
    UNPROVEN_RECURSION = "unproven_recursion"
    UNSUPPORTED_SOURCE = "unsupported_source"
    INFRASTRUCTURE_FAILURE = "infrastructure_failure"


@dataclass(frozen=True)
class CallableNode:
    path: str
    ownerFqcn: str
    kind: str
    method: str
    receiver: str = ""
    parameterTypes: tuple[str, ...] = ()


@dataclass(frozen=True)
class CallSite:
    callerKey: str
    filePath: str
    line: int
    snippetHash: str = ""


@dataclass(frozen=True)
class CallEdge:
    callerKey: str
    calleeKey: str
    dispatch: str
    span: tuple[int, int] = (0, 0)


@dataclass(frozen=True)
class CallResolution:
    edgeId: str
    state: ResolutionState
    reasonCode: str = ""


@dataclass(frozen=True)
class EntryRoot:
    rootId: str
    kind: RootKind
    callableKey: str


@dataclass(frozen=True)
class GuardContextInfo:
    kind: GuardContext
    scopeId: str = ""


@dataclass(frozen=True)
class MediationPath:
    mutationKey: str
    steps: tuple[str, ...]
    guard: GuardContext = GuardContext.NONE


@dataclass(frozen=True)
class MediationProofResult:
    mutationKey: str
    state: ProofState
    reasonCode: str = ""


@dataclass(frozen=True)
class MediationDiagnostic:
    # Bounded fields only: controlled code, target key, short detail.
    # Never place raw source, file contents, or exception text here.
    code: str
    target: str
    detail: str = ""


@dataclass(frozen=True)
class WorkerRoot:
    workerFqcn: str
    doWorkKey: str
    registered: bool = False
    dispositioned: bool = False


@dataclass(frozen=True)
class WorkerGuardScope:
    scopeId: str
    receiverFqcn: str
    method: str
    inBackupWaiver: bool = False
