"""GR-13 exact call-graph construction over a bounded Kotlin corpus.

Shadow-only mediation analysis (PR-GR-13, plan Step 4).  Builds the closed,
exact callable/call-site graph the mediation proof consumes:

  * one ``FileModel`` per corpus file (masked once, offsets preserved)
    carrying its package, imports, owner declarations, supertypes,
    properties, and ``fun`` callables;
  * one ``CallableModel`` per callable with its exact masked body span;
  * per-call-site resolution into the closed ``ResolutionState``
    vocabulary, with exact corpus targets or fail-closed uncertainty;
  * deterministic edges, explicit SCC (recursion) detection, and closed
    root classification.

Fail-closed rules (plan hard stops, restated for this layer):

  * a call edge is modeled ONLY for the plan's supported closed edge
    families; everything else resolves to an explicit uncertainty state;
  * interface/open dispatch produce uncertain potential-target edges,
    never a guessed single target;
  * generic callees, unknown receiver types, and method-only name matches
    produce uncertainty, never an exact edge;
  * scope-shaped method names (worker guard / direct barrier) on a receiver
    that is not the canonical receiver resolve to ``UNRESOLVED_TARGET``;
  * no raw source text is retained in any graph model — only bounded
    offsets, line numbers, and identity strings.  The one bounded raw-text
    inspection (``WorkerGuardRequest`` waiver-flag detection) reads only the
    argument region of a canonical guard call and stores two booleans.

Determinism: every collection the graph exposes is sorted or built in a
fixed order derived from file order then source offsets, so two runs over
the same corpus produce identical graphs.  The legacy regex worker script
is never imported or consulted.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from ...kotlin_callable_parser import mask_kotlin_source
from .models import CallableNode, ResolutionState, RootKind

__all__ = [
    "AnalysisContract",
    "CallEdge",
    "CallGraph",
    "CallGraphBuilder",
    "CallRecord",
    "CallableModel",
    "FileModel",
    "ImportEntry",
    "LambdaRegion",
    "OwnerModel",
    "ROOT_KIND_REASON_CODES",
    "SCCState",
    "find_balanced",
    "parse_file_model",
]


# ── Contract ─────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class AnalysisContract:
    """Closed source contract for one analysis run.

    ``worker_guard_receiver_fqcn`` + ``worker_guard_scope_methods`` come from
    the recorded GR-13 worker-guard contract (receiver-exact: a same-name
    method on any other receiver is never a canonical guard).
    ``direct_scope_*`` come from the GR-12 canonical barrier contract.
    ``direct_scope_allow_receiverless`` exists for the synthetic fixture
    corpus only (its barrier helper is a same-owner function); the production
    contract never admits a receiverless direct scope.
    ``worker_base_fqcns`` are the closed known worker base classes; a class
    whose resolved supertype chain reaches one is a discovered worker.
    ``transparent_scope_methods`` are context-preserving wrappers (the set
    GR-12 admits); their lambdas neither create nor remove guard context.
    """

    worker_guard_receiver_fqcn: str
    worker_guard_scope_methods: tuple[str, ...]
    direct_scope_receiver_fqcn: str
    direct_scope_methods: tuple[str, ...]
    direct_scope_allow_receiverless: bool = False
    worker_base_fqcns: tuple[str, ...] = ()
    transparent_scope_methods: tuple[str, ...] = ()
    transparent_inline_methods: tuple[str, ...] = ()
    structured_launch_receivers: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.transparent_inline_methods, tuple):
            raise TypeError("transparent_inline_methods must be a tuple")
        for name in self.transparent_inline_methods:
            if not isinstance(name, str) or not re.fullmatch(
                r"[A-Za-z_][A-Za-z0-9_]*", name
            ):
                raise ValueError(
                    "transparent_inline_methods entries must be plain identifiers"
                )
        if not isinstance(self.structured_launch_receivers, tuple):
            raise TypeError("structured_launch_receivers must be a tuple")
        for name in self.structured_launch_receivers:
            if not isinstance(name, str) or not re.fullmatch(
                r"[A-Za-z_][A-Za-z0-9_]*", name
            ):
                raise ValueError(
                    "structured_launch_receivers entries must be plain identifiers"
                )
        inline = set(self.transparent_inline_methods)
        if inline & set(STRUCTURED_LAUNCH_METHODS):
            raise ValueError(
                "transparent_inline_methods overlaps the structured launch methods"
            )
        disjoint_groups = (
            ("worker_guard_scope_methods", self.worker_guard_scope_methods),
            ("direct_scope_methods", self.direct_scope_methods),
            ("transparent_scope_methods", self.transparent_scope_methods),
        )
        inline = set(self.transparent_inline_methods)
        for label, group in disjoint_groups:
            if not isinstance(group, tuple):
                raise TypeError("%s must be a tuple" % (label,))
            overlap = inline & set(group)
            if overlap:
                raise ValueError(
                    "transparent_inline_methods overlaps %s: %s"
                    % (label, sorted(overlap))
                )

    def is_worker_guard_method(self, method: str) -> bool:
        return method in self.worker_guard_scope_methods

    def is_direct_scope_method(self, method: str) -> bool:
        return method in self.direct_scope_methods

    def is_transparent_scope_method(self, method: str) -> bool:
        return method in self.transparent_scope_methods

    def is_inline_transparent_method(self, method: str) -> bool:
        return method in self.transparent_inline_methods


# ── GR-14f production inline-carrier table ───────────────────────────────────
#
# Closed, reviewed set of lambda-carrier method names admitted as
# ``transparent`` at the mediation layer (carrier classification only — this
# is NOT the GR-12 barrier contract and grants no authorization by itself).
# Every entry must satisfy BOTH:
#   1. the method is Kotlin-inline (or a project wrapper that provably
#      invokes its lambda argument exactly once, inline, before returning),
#      so the lambda cannot execute after the enclosing callable returns and
#      cannot capture guard context out of it; and
#   2. the method runs the block synchronously in the caller's coroutine
#      context (structured concurrency), so caller-side guard context flows
#      into the lambda unchanged.
# Admissibility evidence (GR-14f): the stdlib entries are `inline` in
# kotlin-stdlib/kotlinx-coroutines; `runOperation` is
# com.yourname.expensetracker.domain.diagnostics.OperationRunRecorder's
# start/run/finalize wrapper (block invoked once inline, RoomOperationRunRecorder
# runOperation); `runCatchingCancellable` is
# com.yourname.expensetracker.domain.util.CancellationSafe's inline
# try/catch wrapper; `runPostCommitSafely` is a private suspend wrapper in
# ReceiptRepository / NotificationProcessingPipeline / ReviewQueueRepository.
# A production tree-wide declaration scan (GR-14f manifest) shows no project
# member/extension declares any of these names with a lambda parameter, so
# name-exact admission cannot be shadowed onto a non-inline carrier.
# Lazy carriers (Sequence/Flow builders, custom dispatch) are deliberately
# absent and keep the default `async` uncertainty.  Any change to this tuple
# requires a dedicated reviewed diff with fixture coverage
# (fixtures helper_proof HP-13..HP-20) and a shadow before/after delta.
PRODUCTION_TRANSPARENT_INLINE_METHODS: tuple[str, ...] = (
    # kotlin scope functions (inline, eager)
    "let",
    "also",
    "apply",
    "run",
    "with",
    "takeIf",
    "takeUnless",
    # kotlin error wrappers (inline)
    "runCatching",
    "getOrElse",
    "onFailure",
    # kotlin resource/control (inline)
    "use",
    "repeat",
    # kotlin eager collection operators with lambda (inline)
    "forEach",
    "forEachIndexed",
    "map",
    "mapNotNull",
    "mapIndexed",
    "filter",
    "any",
    "none",
    "count",
    "first",
    "firstOrNull",
    "associateBy",
    "buildSet",
    "buildList",
    # kotlinx structured suspend scopes (inline)
    "withLock",
    "withTimeout",
    "withTimeoutOrNull",
    # kotlinx structured per-emission flow terminal (context-inheriting)
    "collect",
    # kotlinx structured semaphore (suspend inline, like withLock)
    "withPermit",
    # project inline wrappers (see evidence above)
    "runOperation",
    "runCatchingCancellable",
    "runPostCommitSafely",
    # GR-14j: project inline try/catch wrapper
    # (service/receiptmatching/ReceiptMatchingWorker.safeRecordMatchEvent —
    # block invoked inline, cancellation rethrown)
    "safeRecordMatchEvent",
    # GR-14l: androidx.activity.compose.setContent — the composition root;
    # its content lambda executes during composition (context inherited)
    "setContent",
)

# ── GR-14j structured coroutine launch admission ─────────────────────────────
#
# The mediation engine classifies every coroutine launch lambda as
# ``async``-uncertain: the block may run after the call site returns, so
# caller-side guard context cannot be trusted.  For THIS program's guard
# model that conservatism is misplaced in one specific way: the
# maintenance barrier (RestoreMaintenanceMode) is app-global state checked
# AT THE MUTATION SITE at execution time — caller-side context is
# irrelevant to barrier correctness.  What matters is that the writer
# carries its own canonical scope (DatabaseWriteBarrier.runWrite).
#
# Approved design (GR-14j, owner-approved 2026-09-07): a `launch` on a
# REVIEWED STRUCTURED receiver (name-exact closed set — ViewModel- or
# service-owned CoroutineScope properties, cancelled with their owner;
# tree-wide grep evidence in the batch manifest) resolves EXACTLY, with
# context inherited from the enclosing callable (never a guard source by
# itself).  Consequences, deliberately stricter overall:
#   * a writer whose mutations sit behind their own canonical scope
#     proves INDEPENDENT of launch callers;
#   * a writer WITHOUT local scope on a launch-reachable path becomes a
#     COUNTEREXAMPLE (definite unguarded call path) instead of
#     async-unproven — the honest classification that drives the
#     remaining remediation batches;
#   * launches on UNKNOWN receivers (GlobalScope, injected dispatchers,
#     custom parameters) stay async-uncertain (fail closed).
# STRUCTURED_LAUNCH_METHODS is closed: only `launch` is evidenced in the
# production tree for the admitted receivers.  Any change to either set
# requires a dedicated reviewed diff with fixture coverage (fixtures
# structured_launch SL-01..SL-03) and a shadow before/after delta.
STRUCTURED_LAUNCH_METHODS: tuple[str, ...] = ("launch",)


# ── File model ───────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ImportEntry:
    """One file-scope import binding.

    ``bound_name`` is the name visible in file scope (alias or simple name);
    ``fqcn`` is the exact imported spelling.  For a wildcard import
    ``is_star`` is True and ``fqcn`` is the package prefix.
    """

    bound_name: str
    fqcn: str
    is_alias: bool = False
    is_star: bool = False


@dataclass(frozen=True)
class OwnerModel:
    """One class/object/interface declaration with its resolved identity."""

    fqcn: str
    simple_name: str
    kind: str  # class | object | interface
    file: str
    supertype_texts: tuple[str, ...] = ()
    properties: tuple[tuple[str, str], ...] = ()  # (name, type text) name-sorted


@dataclass(frozen=True)
class FileModel:
    """Parsed model of one corpus file.

    ``masked`` drives every offset-bearing model; ``raw`` exists only for
    literal argument-type inference (masking blanks string literals) and is
    never copied into any graph model or report.
    """

    path: str
    masked: str
    raw: str = ""
    package: str = ""
    waiver_marker: bool = False
    imports: tuple[ImportEntry, ...] = ()
    owners: tuple[OwnerModel, ...] = ()
    callables: tuple[CallableModel, ...] = ()


@dataclass(frozen=True)
class CallableModel:
    """One ``fun`` declaration with its exact masked body span."""

    key: str  # canonical callable key: path|ownerFqcn|kind|method|receiver|params
    node: CallableNode
    file: str
    owner_fqcn: str  # owning owner FQCN, or the file's package for top-level
    method: str
    visibility: str  # public|private|internal|protected
    is_override: bool = False
    is_open: bool = False
    is_generic: bool = False
    is_suspend: bool = False
    is_composable: bool = False
    params_named: tuple[tuple[str, str], ...] = ()  # (name, type text)
    param_types: tuple[str, ...] = ()
    decl_start: int = 0
    decl_end: int = 0
    body_start: int = -1  # index after the body's '{' / after `=`; -1 = no body
    body_end: int = -1  # index of the body's '}' / expression end; [start, end)


@dataclass(frozen=True)
class LambdaRegion:
    """One lambda literal in a callable body with its carrier classification.

    ``carrier`` is closed: ``canonical_direct``, ``canonical_worker`` (with
    recorded backup-waiver flags), ``escaping``, ``async``, or
    ``transparent``.  Nesting is by span containment; context derivation
    walks innermost-first (transparent passes through, canonical stops with
    its context, escaping/async stop as uncertainty).
    """

    start: int
    end: int
    carrier: str
    call_id: int = -1  # owning call record id; -1 for assigned literals
    receiver_fqcn: str = ""
    method: str = ""
    waiver_backup_export: bool = False
    waiver_requires_db_write_false: bool = False

    @property
    def waived(self) -> bool:
        return (
            self.carrier == "canonical_worker"
            and self.waiver_backup_export
            and self.waiver_requires_db_write_false
        )


@dataclass(frozen=True)
class CallRecord:
    """One call/reference site inside a callable body (masked offsets)."""

    call_id: int
    caller_key: str
    file: str
    name: str
    name_start: int
    line: int
    is_reference: bool = False  # ::name function reference, not an invocation
    receiver_text: str = ""  # simple receiver text before the dot, "" = unqualified
    qualification: str = ""  # dotted package/type prefix for qualified names
    chained: bool = False  # `.name(...)` on an untracked expression chain
    lambda_start: int = -1  # trailing lambda literal start, -1 = none
    lambda_end: int = -1


@dataclass(frozen=True)
class CallEdge:
    """One resolved (or explicitly uncertain) call edge of the graph."""

    caller_key: str
    state: ResolutionState
    line: int
    file: str
    name_start: int
    targets: tuple[str, ...] = ()  # exact or potential corpus callee keys, sorted
    external: bool = False  # resolved outside the corpus (stdlib/SDK/import)
    context_kind: str = "none"  # none | direct | worker at the call site
    uncertain: bool = False

    def sort_key(self) -> tuple:
        return (self.caller_key, self.file, self.name_start, self.state.value)


@dataclass(frozen=True)
class SCCState:
    """Explicit recursion/SCC detection result (never silently iterated)."""

    cyclic_keys: tuple[str, ...] = ()  # keys on a cycle (SCC > 1 or self-loop)
    scc_count: int = 0


ROOT_KIND_REASON_CODES = {
    RootKind.WORKER_DO_WORK: "ROOT_WORKER_DO_WORK",
    RootKind.FRAMEWORK_CALLBACK: "ROOT_FRAMEWORK_CALLBACK",
    RootKind.PUBLIC_OR_PROTECTED_EXTERNAL: "ROOT_PUBLIC_OR_PROTECTED_EXTERNAL",
    RootKind.TOP_LEVEL_EXTERNAL: "ROOT_TOP_LEVEL_EXTERNAL",
    RootKind.CONSTRUCTOR_EXTERNAL: "ROOT_CONSTRUCTOR_EXTERNAL",
    RootKind.UNKNOWN_EXTERNAL: "ROOT_UNKNOWN_EXTERNAL",
}

_KNOWN_EXTERNAL_ROOTS = ("java", "javax", "kotlin", "android", "androidx")
_CONTROL_KEYWORDS = frozenset({
    "if", "for", "while", "when", "return", "catch", "synchronized", "try",
    "else", "do", "init", "get", "set",
})
_TYPE_DECL_RE = re.compile(
    r"\b(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|enum|annotation|inner)\s+)*"
    r"(class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
_FUN_DECL_RE = re.compile(r"\bfun\b")
_FUN_DECL_SKIP_RE = re.compile(r"\bfun\s+(?:interface|class|object|typealias)\b")
_IMPORT_RE = re.compile(
    r"\bimport\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?"
)
# Machine-readable read-only-backup waiver marker (fixture corpus only): the
# recorded GR-13 contract makes a guard scope with an explicit
# allowDuringBackupExport=true / requiresDatabaseWrite=false request a
# NON-proving scope; production call sites carry the flags in the
# WorkerGuardRequest arguments, synthetic fixtures via this marker.
_WAIVER_MARKER_RE = re.compile(
    r"//\s*GR-13-WAIVER:\s*allowDuringBackupExport=true\s+requiresDatabaseWrite=false"
)
_PACKAGE_RE = re.compile(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)")
_PROPERTY_DECL_RE = re.compile(
    r"(?:^|\n)[ \t]*(?:private|protected|internal|public|override|open|const|lateinit|[ \t])*"
    r"(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_.<>,\s?]+?))?\s*(?:=|\bget\b|\bby\b|$)",
    re.MULTILINE,
)
_CTOR_VAL_PARAM_RE = re.compile(
    r"\b(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_.<>,\s?]+?)\s*(?=,|\)|$)"
)
_VAL_LOCAL_RE = re.compile(
    r"(?:^|[^\w.])(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_.<>,\s?]+?))?\s*="
)
_ID = r"[A-Za-z_][A-Za-z0-9_]*"
_CALL_CANDIDATE_RE = re.compile(
    r"(?P<ref>::\s*)?(?P<lead>(?:\.|\:\:)\s*)?"
    r"(?P<qual>(?:%s\s*\.\s*)*)"
    r"(?P<name>%s)\s*(?=\(|\{)" % (r"[A-Za-z_][A-Za-z0-9_]*", _ID)
)
_REFERENCE_RE = re.compile(r"::\s*([A-Za-z_][A-Za-z0-9_]*)")
_BUILTIN_TYPE_NAMES = frozenset({
    "String", "Int", "Long", "Boolean", "Double", "Float", "Char", "Byte",
    "Short", "Any", "Unit", "Nothing", "Number", "List", "MutableList", "Set",
    "MutableSet", "Map", "MutableMap", "Collection", "Iterable", "Sequence",
    "Pair", "Triple", "Array", "Result", "Throwable", "Exception", "Enum",
    "Comparable", "Iterator", "StringBuilder", "Regex", "CharSequence",
    "IntArray", "LongArray", "ByteArray", "BooleanArray",
})


def find_balanced(text: str, open_idx: int, open_ch: str, close_ch: str) -> int:
    """Index just past the balanced close for ``text[open_idx] == open_ch``.

    Returns -1 when the region never closes (masked text is bracket-balanced
    by construction, so callers treat -1 as a malformed-region skip).
    """
    depth = 0
    length = len(text)
    for idx in range(open_idx, length):
        ch = text[idx]
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return idx + 1
    return -1


def _strip_type_text(text: str) -> str:
    """Normalize a type spelling for comparison: drop generics/nullability."""
    text = text.strip()
    while text.endswith("?"):
        text = text[:-1].strip()
    if "<" in text:
        text = text[: text.index("<")].strip()
    return text


def _simple_name_of_type(text: str) -> str:
    text = _strip_type_text(text)
    return text.rsplit(".", 1)[-1] if "." in text else text


def _declaration_prefix(masked: str, fun_start: int) -> str:
    """Text of the declaration header prefix containing the `fun` keyword.

    Line-oriented: Kotlin visibility/modifier lists are written on the
    declaration's first line, so the prefix runs from that line's start to
    the parameter-list open paren.  Annotation-only lines above the `fun`
    are not needed for visibility/override/suspend/open classification.
    """
    line_start = masked.rfind(chr(10), 0, fun_start) + 1
    return masked[line_start:fun_start]


def _visibility_of(prefix: str) -> str:
    for token in ("private", "protected", "internal", "public"):
        if re.search(r"\b%s\b" % token, prefix):
            return token
    return "public"



def _has_annotation_above(masked: str, fun_start: int, name: str) -> bool:
    """True when an ``@<name>`` annotation line sits directly above the
    declaration (walking up across consecutive annotation/blank lines).

    Line-oriented by design: the callgraph never needs annotation
    arguments, only the presence of the name.
    """
    pattern = re.compile(r"^\s*@" + name + r"\b")
    own_line_start = masked.rfind(chr(10), 0, fun_start) + 1
    if pattern.match(masked[own_line_start:fun_start]):
        return True
    idx = own_line_start
    while idx > 0:
        prev_start = masked.rfind(chr(10), 0, max(idx - 1, 0)) + 1
        line = masked[prev_start:idx].rstrip()
        if not line.strip():
            idx = prev_start
            continue
        if pattern.match(line):
            return True
        if line.lstrip().startswith("@"):
            idx = prev_start
            continue
        break
    return False


def _has_modifier(prefix: str, modifier: str) -> bool:
    return re.search(r"\b%s\b" % modifier, prefix) is not None


def _owner_body_brace(masked: str, keyword_start: int) -> int:
    """Paren-aware index of a type declaration's body brace (-1 when none).

    A constructor default with a lambda literal (``val f: (Int) -> Unit =
    { it }``) keeps its braces inside the parameter list; only a depth-0
    ``{`` opens the body.
    """
    paren_depth = 0
    for index in range(keyword_start, len(masked)):
        ch = masked[index]
        if ch in "([":
            paren_depth += 1
        elif ch in ")]":
            paren_depth -= 1
        elif paren_depth == 0 and ch == "{":
            return index
    return -1


_ADOPT_TRIGGER_RE = re.compile(
    r"(?:^|\n)[ \t]*(?:[A-Za-z_][A-Za-z0-9_]*[ \t]+)*"
    r"(?:class|interface|object|fun|val|var|enum|init|companion|typealias)\b"
)


def _header_adopt_trigger(masked: str, keyword_start: int, brace: int) -> int | None:
    """Start of the first fresh-line declaration keyword before ``brace``.

    Scans the region between a type declaration's header start and the first
    depth-0 ``{`` found after it, at parenthesis depth 0 (constructor
    parameters and default values live at depth >= 1).  A non-None return
    means that ``{`` belongs to a LATER declaration (the next ``fun``, class,
    or property), so the scanned type declaration is brace-less and the brace
    was adopted.  The modifier-tolerant prefix keeps ``private class`` /
    ``data class`` / ``lateinit var`` triggers working while staying
    line-local (`[ \\t]` only, never a newline).
    """
    depth = 0
    index = keyword_start
    while index < brace:
        ch = masked[index]
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif depth == 0 and ch == "\n":
            match = _ADOPT_TRIGGER_RE.match(masked, index)
            if match:
                return index
        index += 1
    return None


def _supertype_texts(header: str) -> list[str]:
    """Supertype spellings from a type header up to the body brace.

    The primary-constructor parameter list (balanced parentheses after the
    type name) precedes the supertype colon, so the supertype colon is the
    first colon at parenthesis depth 0; parameter type annotations live at
    depth 1 and never trigger it.  Constructor-call parentheses in a
    supertype entry (``CoroutineWorker(context, params)``) are stripped.
    """
    header = header.split("where")[0]
    colon = -1
    depth = 0
    for index, ch in enumerate(header):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif depth == 0 and ch == ":":
            colon = index
            break
    if colon < 0:
        return []
    raw = header[colon + 1 :]
    parts: list[str] = []
    current: list[str] = []
    angle_depth = 0
    for ch in raw:
        if ch in "(<[":
            angle_depth += 1
            current.append(ch)
        elif ch in ")>]":
            angle_depth -= 1
            current.append(ch)
        elif ch == "," and angle_depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    parts.append("".join(current))
    supertypes: list[str] = []
    for piece in parts:
        piece = piece.strip()
        while "(" in piece:
            stripped = re.sub(r"\([^()]*\)", " ", piece)
            if stripped == piece:
                piece = ""
                break
            piece = stripped.strip()
        piece = re.sub(r"\s+by\s+.*$", "", piece).strip()
        if piece and re.match(r"^[A-Za-z_][A-Za-z0-9_.]*$", piece):
            supertypes.append(piece)
    return supertypes


def parse_file_model(path: str, text: str) -> FileModel:
    """Build the FileModel for one file; masking preserves every offset."""
    masked = mask_kotlin_source(text)
    package_match = _PACKAGE_RE.search(masked)
    package = package_match.group(1) if package_match else ""
    imports: list[ImportEntry] = []
    for match in _IMPORT_RE.finditer(masked):
        fqcn = match.group(1)
        alias = match.group(2)
        if fqcn.endswith("."):
            fqcn = fqcn[:-1]
        is_star = masked[match.end() : match.end() + 1] == "*"
        imports.append(
            ImportEntry(
                bound_name=alias or fqcn.rsplit(".", 1)[-1],
                fqcn=fqcn,
                is_alias=alias is not None,
                is_star=is_star,
            )
        )

    # All type declarations (top-level and nested), innermost-first spans.
    # Spans carry their header end: a brace-less declaration (`object Failed
    # : RefreshOutcome` with no body) becomes a header-only owner that
    # contains nothing, instead of adopting the NEXT declaration's body
    # brace and nesting that later declaration under its FQCN.
    owner_spans: list[tuple[int, int, str, str, int]] = []
    type_matches = list(_TYPE_DECL_RE.finditer(masked))
    for idx, match in enumerate(type_matches):
        keyword_start = match.start(1)
        kind = match.group(1)
        simple = match.group(2)
        brace = _owner_body_brace(masked, keyword_start)
        if brace < 0:
            continue
        trigger = _header_adopt_trigger(masked, keyword_start, brace)
        if trigger is not None:
            owner_spans.append((match.start(), trigger, simple, kind, trigger))
            continue
        body_close = find_balanced(masked, brace, "{", "}")
        if body_close < 0:
            continue
        owner_spans.append((match.start(), body_close, simple, kind, brace))
    owner_spans.sort(key=lambda span: (span[0], -span[1]))

    owners: list[OwnerModel] = []
    fqcn_by_index: list[str] = [""] * len(owner_spans)
    for idx, (start, end, simple, kind, _header_end) in enumerate(owner_spans):
        enclosing_idx = None
        for jdx, (start2, end2, _s2, _k2, _h2) in enumerate(owner_spans):
            if jdx != idx and start2 < start < end2:
                if enclosing_idx is None or owner_spans[jdx][0] > owner_spans[enclosing_idx][0]:
                    enclosing_idx = jdx
        if enclosing_idx is not None:
            parent = fqcn_by_index[enclosing_idx]
            fqcn = parent + "." + simple
        else:
            fqcn = (package + "." if package else "") + simple
        fqcn_by_index[idx] = fqcn

    for idx, (start, end, simple, kind, header_end) in enumerate(owner_spans):
        header = masked[start:header_end]
        properties: dict[str, str] = {}
        for param_match in _CTOR_VAL_PARAM_RE.finditer(header):
            properties.setdefault(param_match.group(2), param_match.group(3).strip())
        body = masked[start:end]
        for prop_match in _PROPERTY_DECL_RE.finditer(body):
            name = prop_match.group(1)
            declared = (prop_match.group(2) or "").strip()
            if not declared and prop_match.group(0).rstrip().endswith("="):
                tail = body[prop_match.end() :]
                ctor = re.match(r"\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\(", tail)
                if ctor:
                    declared = ctor.group(1)
            if name not in properties or (declared and not properties[name]):
                properties[name] = declared
        owners.append(
            OwnerModel(
                fqcn=fqcn_by_index[idx],
                simple_name=simple,
                kind=kind,
                file=path,
                supertype_texts=tuple(_supertype_texts(header)),
                properties=tuple(sorted(properties.items())),
            )
        )

    callables = _callable_models(path, masked, package, owners, owner_spans)
    return FileModel(
        path=path,
        masked=masked,
        raw=text,
        package=package,
        waiver_marker=_WAIVER_MARKER_RE.search(text) is not None,
        imports=tuple(imports),
        owners=tuple(owners),
        callables=tuple(callables),
    )


def _callable_models(
    path: str,
    masked: str,
    package: str,
    owners: list[OwnerModel],
    owner_spans: list[tuple[int, int, str, str, int]],
) -> list[CallableModel]:
    """All ``fun`` declarations: member (per owner) and top-level.

    Two passes: rough fun spans first (so local functions declared inside
    another function's body are skipped), then full models.
    """
    headers: list[tuple[int, int, str]] = []  # (fun_start, paren, name)
    for match in _FUN_DECL_RE.finditer(masked):
        fun_start = match.start()
        if _FUN_DECL_SKIP_RE.match(masked[fun_start : fun_start + 40]):
            continue
        paren = masked.find("(", match.end())
        if paren < 0:
            continue
        head = masked[fun_start : paren + 1]
        name_match = re.search(r"(?P<name>%s)\s*(?:<[^<>]*>)?\s*\(" % _ID, head)
        if not name_match:
            continue
        headers.append((fun_start, paren, name_match.group("name")))

    rough_spans: list[tuple[int, int]] = []
    for fun_start, paren, _name in headers:
        params_close = find_balanced(masked, paren, "(", ")")
        if params_close < 0:
            rough_spans.append((fun_start, paren))
            continue
        # The SAME precise span logic as the full models: a declaration's
        # own body must be found via its optional return type, never by a
        # tail-wide first-brace/first-equals scan (a bodyless declaration's
        # tail would otherwise adopt a LATER declaration's `=` or `{` and
        # swallow it as a local function).
        body_start, _body_end, decl_end = _body_span_after_params(
            masked, params_close
        )
        end = decl_end if body_start >= 0 else params_close
        rough_spans.append((fun_start, end if end > fun_start else params_close))

    models: list[CallableModel] = []
    for (fun_start, paren, name), (_fs, rough_end) in zip(headers, rough_spans):
        # Local functions live inside another function's body of the same
        # owner scope; they are body detail, not corpus callables.
        enclosing_fun = any(
            other_start < fun_start < other_end and other_start != fun_start
            for other_start, other_end in rough_spans
        )
        if enclosing_fun:
            continue
        owner_idx = None
        for idx, (start, end, _simple, _kind, _header_end) in enumerate(owner_spans):
            if start < fun_start < end:
                if owner_idx is None or owner_spans[idx][0] > owner_spans[owner_idx][0]:
                    owner_idx = idx
        if owner_idx is None:
            owner_fqcn = package
            kind_value = "top_level_function"
        else:
            owner_fqcn = owners[owner_idx].fqcn
            kind_value = "function"
        prefix = _declaration_prefix(masked, fun_start)
        params_close = find_balanced(masked, paren, "(", ")")
        if params_close < 0:
            continue
        param_text = masked[paren + 1 : params_close - 1]
        params_named: list[tuple[str, str]] = []
        if param_text.strip():
            depth = 0
            pieces: list[str] = []
            current: list[str] = []
            for ch in param_text:
                if ch in "(<[":
                    depth += 1
                elif ch in ")>]":
                    depth -= 1
                if ch == "," and depth == 0:
                    pieces.append("".join(current))
                    current = []
                else:
                    current.append(ch)
            pieces.append("".join(current))
            for piece in pieces:
                piece = piece.strip()
                if not piece:
                    continue
                pm = re.match(
                    r"^(?:(?:vararg|noinline|crossinline)\s+)*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+)$",
                    piece,
                )
                if pm:
                    params_named.append((pm.group(1), pm.group(2).strip()))
                else:
                    params_named.append(("", piece))
        before_name = masked[fun_start : paren]
        receiver = None
        name_head = re.search(r"(%s)\s*(?:<[^<>]*>)?\s*$" % _ID, before_name)
        if name_head is None:
            continue
        if owner_idx is None:
            receiver_match = re.search(
                r"([A-Za-z_][A-Za-z0-9_.]*)\s*\.\s*%s\s*(?:<[^<>]*>)?\s*$"
                % re.escape(name),
                before_name,
            )
            if receiver_match:
                receiver = receiver_match.group(1)
        param_types = tuple(
            _strip_type_text(type_text) for _name, type_text in params_named
        )
        receiver_norm = _strip_type_text(receiver) if receiver else None
        key = "|".join(
            [
                path,
                owner_fqcn,
                kind_value,
                name,
                receiver_norm if receiver_norm is not None else "null",
                ",".join(param_types),
            ]
        )
        node = CallableNode(
            path=path,
            ownerFqcn=owner_fqcn,
            kind=kind_value,
            method=name,
            receiver=receiver_norm or "",
            parameterTypes=param_types,
        )
        # Body span: braced body preferred; expression bodies (`= expr`,
        # including `= withContext(...) { ... }`) span their whole
        # expression.  After the parameter list comes an optional return
        # type (`: T`), then either `{` (braced body) or `=` (expression
        # body).  The scan must stop at a fresh-line declaration keyword,
        # or a bodyless declaration (`abstract fun doWork(): Result`) would
        # swallow the NEXT declaration's body.
        body_start, body_end, decl_end = _body_span_after_params(masked, params_close)
        models.append(
            CallableModel(
                key=key,
                node=node,
                file=path,
                owner_fqcn=owner_fqcn,
                method=name,
                visibility=_visibility_of(prefix),
                is_override=_has_modifier(prefix, "override"),
                is_open=_has_modifier(prefix, "open") or _has_modifier(prefix, "abstract"),
                is_generic=bool(re.search(r"fun\s*<", masked[fun_start : paren])),
                is_suspend=_has_modifier(prefix, "suspend"),
                is_composable=_has_modifier(prefix, "Composable")
                or _has_annotation_above(masked, fun_start, "Composable"),
                params_named=tuple(params_named),
                param_types=param_types,
                decl_start=fun_start,
                decl_end=decl_end,
                body_start=body_start,
                body_end=body_end,
            )
        )
    return models


# ── Call-site extraction ─────────────────────────────────────────────────────


def _skip_ws(masked: str, index: int) -> int:
    length = len(masked)
    while index < length and masked[index] in " \t\r\n":
        index += 1
    return index


_FRESH_DECLARATION_RE = re.compile(
    r"(?:^|\n)\s*(?:public|private|protected|internal|open|abstract|final|"
    r"override|suspend|inline|companion|fun|val|var|class|object|interface|"
    r"enum|init|constructor|typealias)\b"
)


def _skip_return_type(masked: str, index: int) -> int:
    """Skip a return-type spelling after the parameter list's colon.

    Returns the index of the `{` that opens the braced body when one exists,
    the index of a trailing-lambda `{` after an argument list is NOT handled
    here, and otherwise an index NOT pointing at `{` (no braced body).  The
    scan stops at the first fresh-line declaration keyword so a bodyless
    declaration cannot swallow the next declaration's body.
    """
    floor = min(len(masked), index + 400)
    depth = 0
    while index < floor:
        ch = masked[index]
        if ch == "-" and index + 1 < floor and masked[index + 1] == ">":
            # Kotlin arrow (`->`): not a generic close bracket.
            index += 2
            continue
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        elif depth == 0 and ch == "{":
            return index
        elif depth == 0 and ch == "=":
            return index
        elif depth == 0 and ch == "\n":
            match = _FRESH_DECLARATION_RE.match(masked, index)
            if match:
                return index + 1  # not a `{`: no braced body
        index += 1
    return index


def _expression_body_end(masked: str, start: int) -> int:
    """Exclusive end index of an expression body (`= <expr>`).

    Bracket-balanced scan from the expression start: nested calls, lambda
    literals, and index expressions all nest, so `= withContext(...) { ... }`
    spans its whole lambda — the lambda's braces are counted, otherwise the
    first declaration keyword inside the lambda would end the body early.
    Only `(`/`)`, `[`/`]`, and `{`/`}` move the depth: generic spellings are
    character-balanced in valid Kotlin, while comparison spellings are not
    (`a < b` has no close, `a > b` is not an open), so treating `<`/`>` as
    brackets would either leak depth past the expression end or truncate it
    at a comparison.  The expression ends at a depth-0 `;`, an unmatched
    closing bracket (it closes an enclosing declaration), or a fresh-line
    declaration keyword.  Masked text is bracket-balanced, so the file end is
    only the last resort.
    """
    depth = 0
    index = start
    length = len(masked)
    while index < length:
        ch = masked[index]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return index
            depth -= 1
        elif depth == 0:
            if ch == ";":
                return index
            if ch == "\n" and _FRESH_DECLARATION_RE.match(masked, index):
                return index
        index += 1
    return length


def _body_span_after_params(masked: str, params_close: int) -> tuple[int, int, int]:
    """(body_start, body_end, decl_end) for the body following a parameter list.

    Braced body: ``[cursor + 1, body_close - 1]`` with ``decl_end`` at the
    closing brace.  Expression body (`= expr`): ``[eq + 1, expression end]``
    with ``decl_end`` at the expression end.  A bodyless declaration
    (abstract/open without a body) returns ``(-1, -1, params_close)``.
    """
    cursor = _skip_ws(masked, params_close)
    if cursor < len(masked) and masked[cursor] == ":":
        cursor = _skip_return_type(masked, cursor + 1)
    if cursor < len(masked) and masked[cursor] == "{":
        body_close = find_balanced(masked, cursor, "{", "}")
        if body_close > 0:
            return cursor + 1, body_close - 1, body_close
    elif cursor < len(masked) and masked[cursor] == "=":
        expr_end = _expression_body_end(masked, cursor + 1)
        if expr_end > cursor + 1:
            return cursor + 1, expr_end, expr_end
    return -1, -1, params_close


def _call_lambda_span(masked: str, name_start: int, name_len: int) -> tuple[int, int]:
    """(lambda_start, lambda_end) of a call's trailing lambda, or (-1, -1)."""
    cursor = _skip_ws(masked, name_start + name_len)
    if cursor < len(masked) and masked[cursor] == "{":
        end = find_balanced(masked, cursor, "{", "}")
        return (cursor, end - 1) if end > 0 else (-1, -1)
    if cursor < len(masked) and masked[cursor] == "(":
        close = find_balanced(masked, cursor, "(", ")")
        if close > 0:
            brace = _skip_ws(masked, close)
            if brace < len(masked) and masked[brace] == "{":
                end = find_balanced(masked, brace, "{", "}")
                return (brace, end - 1) if end > 0 else (-1, -1)
    return -1, -1


def extract_calls(
    masked: str,
    callable_model: CallableModel,
) -> tuple[CallRecord, ...]:
    """Extract call/reference records for one callable body (masked offsets)."""
    if callable_model.body_start < 0 or callable_model.body_end < 0:
        return ()
    calls: list[CallRecord] = []
    call_id = 0
    for match in _CALL_CANDIDATE_RE.finditer(
        masked, callable_model.body_start, callable_model.body_end
    ):
        name = match.group("name")
        if name in _CONTROL_KEYWORDS:
            continue
        name_start = match.start("name")
        prefix = masked[callable_model.body_start : name_start].rstrip()
        if prefix.endswith("@"):
            continue  # annotation use-site, never a call
        is_reference = match.group("ref") is not None or prefix.endswith("::")
        lead = match.group("lead") is not None
        qual = re.sub(r"\s+", "", match.group("qual") or "")
        qualification = qual[:-1] if qual.endswith(".") else qual
        receiver_text = ""
        if qualification and "." not in qualification:
            # Single-prefix calls are member-shaped: `guard.runGuarded(...)`,
            # `store.put(...)`, `Type.static(...)`.  The resolver classifies
            # the receiver (local/param/property/corpus type).
            receiver_text = qualification
            qualification = ""
        chained = bool(lead) and not qualification and not receiver_text
        lambda_start, lambda_end = _call_lambda_span(
            masked, name_start, len(name)
        )
        line = masked.count("\n", 0, name_start) + 1
        calls.append(
            CallRecord(
                call_id=call_id,
                caller_key=callable_model.key,
                file=callable_model.file,
                name=name,
                name_start=name_start,
                line=line,
                is_reference=is_reference,
                receiver_text=receiver_text,
                qualification=qualification,
                chained=chained,
                lambda_start=lambda_start,
                lambda_end=lambda_end,
            )
        )
        call_id += 1
    # Function references (`::name`, `this::name`, `Type::name`) with no
    # argument list or trailing lambda are invisible to the call-candidate
    # regex; scan them separately (`::class` property references excluded).
    for match in _REFERENCE_RE.finditer(
        masked, callable_model.body_start, callable_model.body_end
    ):
        name = match.group(1)
        if name == "class":
            continue
        name_start = match.start(1)
        if any(call.name_start == name_start for call in calls):
            continue
        line = masked.count(chr(10), 0, name_start) + 1
        calls.append(
            CallRecord(
                call_id=call_id,
                caller_key=callable_model.key,
                file=callable_model.file,
                name=name,
                name_start=name_start,
                line=line,
                is_reference=True,
            )
        )
        call_id += 1
    return tuple(calls)


# ── Graph assembly ───────────────────────────────────────────────────────────


class CallGraphBuilder:
    """Builds the exact call graph for a bounded corpus (deterministic)."""

    def __init__(self, contract: AnalysisContract, files: dict[str, str]) -> None:
        self.contract = contract
        self.file_models: dict[str, FileModel] = {}
        for path in sorted(files):
            self.file_models[path] = parse_file_model(path, files[path])
        self._duplicate_callable_keys: set[str] = set()
        self.callables: dict[str, CallableModel] = {}
        for path in sorted(self.file_models):
            for model in self.file_models[path].callables:
                if model.key in self.callables:
                    self._duplicate_callable_keys.add(model.key)
                    continue
                self.callables[model.key] = model
        self.callables_by_name: dict[str, list[str]] = {}
        for key in sorted(self.callables):
            self.callables_by_name.setdefault(self.callables[key].method, []).append(key)
        self._duplicate_owner_fqcns: set[str] = set()
        self.owners: dict[str, OwnerModel] = {}
        for path in sorted(self.file_models):
            for owner in self.file_models[path].owners:
                if owner.fqcn in self.owners:
                    self._duplicate_owner_fqcns.add(owner.fqcn)
                    continue
                self.owners[owner.fqcn] = owner
        self.corpus_type_fqcns = frozenset(self.owners)
        self.corpus_types_by_simple: dict[str, tuple[str, ...]] = {}
        by_simple: dict[str, list[str]] = {}
        for fqcn in sorted(self.owners):
            by_simple.setdefault(fqcn.rsplit(".", 1)[-1], []).append(fqcn)
        self.corpus_types_by_simple = {
            name: tuple(keys) for name, keys in sorted(by_simple.items())
        }
        self.corpus_types_by_file_simple: dict[tuple[str, str], str] = {}
        by_package: dict[tuple[str, str], list[str]] = {}
        for fqcn in sorted(self.owners):
            owner = self.owners[fqcn]
            self.corpus_types_by_file_simple[(owner.file, owner.simple_name)] = fqcn
            package = fqcn.rsplit(".", 1)[0] if "." in fqcn else ""
            by_package.setdefault((package, owner.simple_name), []).append(fqcn)
        self.corpus_types_by_package = {
            name: tuple(keys) for name, keys in sorted(by_package.items())
        }
        self.top_level_fqcn_by_owner: dict[str, str] = {}
        for key in sorted(self.callables):
            model = self.callables[key]
            if model.node.kind == "top_level_function" and not model.node.receiver:
                self.top_level_fqcn_by_owner.setdefault(
                    model.owner_fqcn + "." + model.method, key
                )
        self.owner_supertypes: dict[str, tuple[str, ...]] = {}
        for fqcn in sorted(self.owners):
            owner = self.owners[fqcn]
            file_model = self.file_models[owner.file]
            self.owner_supertypes[fqcn] = tuple(
                self._resolve_type(file_model, text)[0] or ""
                for text in owner.supertype_texts
            )
        self._local_vals: dict[str, list[tuple[str, str, int]]] = {}
        for key in sorted(self.callables):
            self._collect_local_vals(self.callables[key])
        self.calls_by_callable: dict[str, tuple[CallRecord, ...]] = {}
        self.lambda_regions_by_callable: dict[str, tuple[LambdaRegion, ...]] = {}
        self.edges: tuple[CallEdge, ...] = ()
        self.scc = SCCState()
        self.scc_exact = SCCState()
        self.roots: dict[str, RootKind] = {}

    # ── type resolution ──

    def _resolve_type(self, file_model: FileModel, type_text: str) -> tuple[str, str]:
        """(fqcn, origin) for a type spelling; origin: corpus|external|unknown.

        An exact import/alias or a dotted spelling resolves to its exact FQCN
        even when the target lives outside the corpus (origin external);
        unknown simple names fail closed to ("", "unknown").
        """
        stripped = _strip_type_text(type_text)
        # GR-14n: parameter texts carry default-value assignments
        # (`viewModel: ReviewViewModel = hiltViewModel()`); resolution must
        # use the declared type only.  Canonical keys keep the raw text —
        # this normalization is resolution-local.
        if " = " in stripped:
            stripped = _strip_type_text(stripped.split(" = ", 1)[0])
        if not stripped:
            return "", "unknown"
        if "." in stripped:
            if stripped in self.owners:
                return stripped, "corpus"
            if stripped in self.corpus_type_fqcns:
                return stripped, "corpus"
            if stripped.split(".")[0] in _KNOWN_EXTERNAL_ROOTS:
                return stripped, "external"
            return "", "unknown"
        simple = stripped
        for entry in file_model.imports:
            if entry.is_star:
                continue
            if entry.bound_name == simple:
                if entry.fqcn in self.owners:
                    return entry.fqcn, "corpus"
                return entry.fqcn, "external"
        same_file = self.corpus_types_by_file_simple.get((file_model.path, simple))
        if same_file:
            return same_file, "corpus"
        package_candidates = self.corpus_types_by_package.get(
            (file_model.package, simple), ()
        )
        if len(package_candidates) == 1:
            return package_candidates[0], "corpus"
        corpus_candidates = self.corpus_types_by_simple.get(simple, ())
        if len(corpus_candidates) == 1:
            return corpus_candidates[0], "corpus"
        if simple in _BUILTIN_TYPE_NAMES:
            return "kotlin." + simple, "external"
        # A single star import binds a simple name exactly the way Kotlin
        # does (prefix + name); two or more star imports could both export
        # the name, which stays fail-closed unknown.
        star_prefixes = [
            entry.fqcn for entry in file_model.imports if entry.is_star
        ]
        if len(star_prefixes) == 1:
            candidate = star_prefixes[0] + "." + simple
            if candidate in self.owners:
                return candidate, "corpus"
            return candidate, "external"
        return "", "unknown"

    def _resolve_simple(self, file_model: FileModel, name: str) -> str:
        return self._resolve_type(file_model, name)[0]

    # ── receiver typing ──

    def receiver_fqcn_for_call(self, call: CallRecord) -> tuple[str, bool]:
        """(receiver FQCN, known) for a call record.

        Receiverless calls are "known" (implicit this/package scope).  The
        resolution order for a simple receiver: nearest preceding local
        ``val/var`` binding, enclosing callable parameters, owner properties,
        then corpus type names (static-style member chains).
        """
        receiver = call.receiver_text
        if not receiver:
            return "", True
        model = self.callables.get(call.caller_key)
        if receiver == "this" or receiver.startswith("this@"):
            if model is not None and model.owner_fqcn in self.owners:
                return model.owner_fqcn, True
            return "", False
        file_model = self.file_models[call.file]
        local_type = self._local_val_type(call)
        if local_type is not None:
            fqcn, origin = self._resolve_type(file_model, local_type)
            return fqcn, origin != "unknown"
        if model is not None:
            for param_name, param_type in model.params_named:
                if param_name == receiver:
                    fqcn, origin = self._resolve_type(file_model, param_type)
                    return fqcn, origin != "unknown"
            owner_fqcn = model.owner_fqcn
            seen_owners: set[str] = set()
            while owner_fqcn and owner_fqcn in self.owners and owner_fqcn not in seen_owners:
                seen_owners.add(owner_fqcn)
                owner = self.owners[owner_fqcn]
                for prop_name, prop_type in owner.properties:
                    if prop_name == receiver:
                        fqcn, origin = self._resolve_type(file_model, prop_type)
                        return fqcn, origin != "unknown"
                nxt = ""
                for supertype in self.owner_supertypes.get(owner_fqcn, ()):
                    if supertype and supertype in self.owners:
                        nxt = supertype
                        break
                owner_fqcn = nxt
        fqcn, origin = self._resolve_type(file_model, receiver)
        if origin != "unknown":
            return fqcn, True
        return "", False

    def _local_val_type(self, call: CallRecord) -> str | None:
        bindings = self._local_vals.get(call.caller_key, ())
        best: tuple[int, str] | None = None
        for name, type_text, offset in bindings:
            if name == call.receiver_text and offset < call.name_start:
                if best is None or offset > best[0]:
                    best = (offset, type_text)
        return best[1] if best is not None else None

    def _collect_local_vals(self, model: CallableModel) -> None:
        if model.body_start < 0:
            self._local_vals[model.key] = ()
            return
        masked = self.file_models[model.file].masked
        bindings: list[tuple[str, str, int]] = []
        region = masked[model.body_start : model.body_end]
        for match in _VAL_LOCAL_RE.finditer(region):
            name = match.group(1)
            declared = (match.group(2) or "").strip()
            if not declared:
                rest = region[match.end() : match.end() + 160]
                ctor = re.match(r"\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\(", rest)
                declared = ctor.group(1) if ctor else ""
            bindings.append((name, declared, model.body_start + match.start(1)))
        self._local_vals[model.key] = bindings

    # ── lambda region classification ──

    def _lambda_regions(
        self, model: CallableModel, calls: tuple[CallRecord, ...]
    ) -> tuple[LambdaRegion, ...]:
        masked = self.file_models[model.file].masked
        model_file = self.file_models[model.file]
        regions: list[LambdaRegion] = []
        for call in calls:
            if call.lambda_start < 0:
                continue
            carrier = "async"
            receiver_fqcn = ""
            waiver_backup = False
            waiver_no_write = False
            if not call.is_reference:
                receiver_fqcn, receiver_known = self.receiver_fqcn_for_call(call)
                if self.contract.is_worker_guard_method(call.name):
                    if receiver_known and receiver_fqcn == self.contract.worker_guard_receiver_fqcn:
                        carrier = "canonical_worker"
                        arg_text = self._call_argument_text(masked, call)
                        waiver_backup = (
                            "allowDuringBackupExport" in arg_text
                            and "true" in arg_text
                        )
                        waiver_no_write = (
                            "requiresDatabaseWrite" in arg_text
                            and "false" in arg_text
                        )
                        if model_file.waiver_marker:
                            waiver_backup = True
                            waiver_no_write = True
                    else:
                        # Scope-shaped call on a non-canonical receiver: the
                        # lambda's invocation contract is unresolved.
                        carrier = "unresolved_scope"
                elif self.contract.is_direct_scope_method(call.name):
                    receiverless_ok = (
                        self.contract.direct_scope_allow_receiverless
                        and not call.receiver_text
                    )
                    if receiverless_ok or (
                        receiver_known
                        and receiver_fqcn == self.contract.direct_scope_receiver_fqcn
                    ):
                        carrier = "canonical_direct"
                    else:
                        carrier = "unresolved_scope"
                elif self.contract.is_transparent_scope_method(
                    call.name
                ) or self.contract.is_inline_transparent_method(call.name):
                    carrier = "transparent"
                elif (
                    call.name in STRUCTURED_LAUNCH_METHODS
                    and call.receiver_text
                    in self.contract.structured_launch_receivers
                ):
                    # GR-14j: launch on a reviewed structured receiver —
                    # context inherited (never a guard source), edge exact.
                    carrier = "transparent"
                elif (
                    carrier == "async"
                    and model.is_composable
                    and call.name not in STRUCTURED_LAUNCH_METHODS
                ):
                    # GR-14l: inside a @Composable callable, every
                    # non-launch lambda region (layout content, onClick,
                    # remember blocks) executes in composition context —
                    # context inherited (never a guard source), edge exact.
                    # Launch-family carriers keep their own admission rule
                    # (unknown receivers stay async-uncertain).
                    carrier = "transparent"
            regions.append(
                LambdaRegion(
                    start=call.lambda_start,
                    end=call.lambda_end,
                    carrier=carrier,
                    call_id=call.call_id,
                    receiver_fqcn=receiver_fqcn,
                    method=call.name,
                    waiver_backup_export=waiver_backup,
                    waiver_requires_db_write_false=waiver_no_write,
                )
            )
        # Assigned lambda literals (`val x = { ... }` / `x = { ... }` at
        # statement level) escape their scope.  GR-14m: `name = { ... }`
        # INSIDE a call's parentheses is a named-argument lambda (dialog
        # onConfirm/onDismiss/onClick handlers) — it never escapes the
        # enclosing callable's context and must not be flagged.  Matches
        # at paren depth > 0 are argument lambdas and are skipped.
        region_text_start = model.body_start
        region_text = masked[model.body_start : model.body_end]
        paren_depth = [0] * (len(region_text) + 1)
        depth = 0
        for i, ch in enumerate(region_text):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth = max(0, depth - 1)
            paren_depth[i + 1] = depth
        for match in re.finditer(
            r"=\s*\{", region_text
        ):
            if paren_depth[match.start()] > 0:
                continue
            literal_start = model.body_start + match.end() - 1
            if any(region.start == literal_start for region in regions):
                continue
            literal_end = find_balanced(masked, literal_start, "{", "}")
            if literal_end > 0:
                regions.append(
                    LambdaRegion(
                        start=literal_start,
                        end=literal_end - 1,
                        carrier="escaping",
                        call_id=-1,
                    )
                )
        # GR-14l: nested-carrier inheritance.  A non-launch lambda region
        # whose innermost containing region is an admitted TRANSPARENT one
        # (inline carrier, structured launch, composable lambda,
        # setContent root) executes in that same inherited context — e.g.
        # themed-wrapper composables nested inside setContent.  Launch
        # families keep their own admission rule; assigned-literal escapes
        # are never unlocked.
        regions = self._propagate_nested_transparency(regions)
        regions.sort(key=lambda region: (region.start, region.end))
        return tuple(regions)

    def _propagate_nested_transparency(self, regions):
        import dataclasses

        mutable = list(regions)
        changed = True
        while changed:
            changed = False
            for idx, region in enumerate(mutable):
                if region.carrier != "async":
                    continue
                if region.method in STRUCTURED_LAUNCH_METHODS:
                    continue
                container = None
                for other in mutable:
                    if other is region or other.carrier != "transparent":
                        continue
                    if other.start <= region.start and region.end <= other.end:
                        if container is None or other.start > container.start:
                            container = other
                if container is not None:
                    mutable[idx] = dataclasses.replace(
                        region, carrier="transparent"
                    )
                    changed = True
        return mutable

    def _call_argument_text(self, masked: str, call: CallRecord) -> str:
        """Bounded argument region of a call (waiver-flag inspection only).

        The text is read transiently and never stored; the region carries no
        user payload (it is a WorkerGuardRequest construction site).
        """
        cursor = _skip_ws(masked, call.name_start + len(call.name))
        if cursor >= len(masked) or masked[cursor] != "(":
            return ""
        close = find_balanced(masked, cursor, "(", ")")
        if close < 0:
            return ""
        return masked[cursor:close]

    # ── context derivation ──

    def _innermost_lambda(
        self, regions: tuple[LambdaRegion, ...], offset: int
    ) -> LambdaRegion | None:
        best: LambdaRegion | None = None
        for region in regions:
            if region.start <= offset < region.end:
                if best is None or region.start > best.start:
                    best = region
        return best

    def _edge_context(self, regions, offset: int) -> str:
        region = self._innermost_lambda(regions, offset)
        if region is None:
            return "none"
        if region.carrier == "canonical_direct":
            return "direct"
        if region.carrier == "canonical_worker":
            return "worker"
        if region.carrier == "transparent":
            return self._edge_context(
                tuple(r for r in regions if r is not region), offset
            )
        return "none"

    def _uncertain_region_state(
        self, regions, offset: int
    ) -> ResolutionState | None:
        region = self._innermost_lambda(regions, offset)
        if region is None:
            return None
        if region.carrier == "escaping":
            return ResolutionState.ESCAPING_LAMBDA
        if region.carrier == "async":
            return ResolutionState.ASYNC_DISPATCH
        if region.carrier == "unresolved_scope":
            return ResolutionState.UNRESOLVED_TARGET
        if region.carrier == "transparent":
            return self._uncertain_region_state(
                tuple(r for r in regions if r is not region), offset
            )
        return None

    # ── graph construction ──

    def build(self) -> "CallGraph":
        edge_lists: list[CallEdge] = []
        for key in sorted(self.callables):
            model = self.callables[key]
            calls = extract_calls(self.file_models[model.file].masked, model)
            regions = self._lambda_regions(model, calls)
            self.calls_by_callable[key] = calls
            self.lambda_regions_by_callable[key] = regions
            for call in calls:
                edge_lists.extend(self._resolve_call(model, call, regions))
        edge_lists.sort(key=lambda edge: edge.sort_key())
        self.edges = tuple(edge_lists)
        self.scc = self._detect_scc()
        self.scc_exact = self._detect_scc(
            tuple(edge for edge in self.edges if not edge.uncertain)
        )
        self._classify_roots()
        return CallGraph(
            builder=self,
            edges=self.edges,
            scc=self.scc,
            scc_exact=self.scc_exact,
            roots=dict(sorted(self.roots.items())),
        )

    def _resolve_call(
        self, model: CallableModel, call: CallRecord, regions
    ) -> list[CallEdge]:
        if call.is_reference:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.FUNCTION_REFERENCE,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=self._name_match_targets(call.name),
                    context_kind=self._edge_context(regions, call.name_start),
                    uncertain=True,
                )
            ]
        uncertain_state = self._uncertain_region_state(regions, call.name_start)
        if uncertain_state is not None:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=uncertain_state,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=self._name_match_targets(call.name),
                    context_kind="none",
                    uncertain=True,
                )
            ]
        context = self._edge_context(regions, call.name_start)
        resolved = self._resolve_invocation(model, call, context)
        if self._chain_admitted_by_engine_carriers(regions, call.name_start):
            # GR-14f/GR-14j resolution-preservation rule: an admitted-carrier
            # site must never lose corpus reachability relative to the
            # historical uncertain-edge behavior.  When exact resolution
            # cannot bind the call to corpus targets (untracked receiver
            # chains, external receivers), keep the conservative
            # name-matched uncertain edge instead — otherwise
            # reverse-reachability would shrink and reclassify real
            # recursion/inbound evidence as zero-inbound external entry.
            if not any(edge.targets for edge in resolved):
                return [
                    CallEdge(
                        caller_key=model.key,
                        state=ResolutionState.ASYNC_DISPATCH,
                        line=call.line,
                        file=call.file,
                        name_start=call.name_start,
                        targets=self._name_match_targets(call.name),
                        context_kind="none",
                        uncertain=True,
                    )
                ]
        return resolved

    def _chain_admitted_by_engine_carriers(self, regions, offset: int) -> bool:
        """True when the transparent region chain at ``offset`` includes a
        carrier admitted by an engine contract (GR-14f inline table or
        GR-14j structured launch).  Called only after
        ``_uncertain_region_state`` returned None, so every region
        containing ``offset`` is already transparent; wrapper-only chains
        keep their historical resolution path.  A transparent region whose
        method is a structured launch method can only be an ADMITTED
        launch (unadmitted launches classify as default-async)."""
        return any(
            self.contract.is_inline_transparent_method(region.method)
            or (
                region.carrier == "transparent"
                and region.method in STRUCTURED_LAUNCH_METHODS
            )
            for region in regions
            if region.start <= offset < region.end
        )

    def _name_match_targets(self, name: str) -> tuple[str, ...]:
        """Corpus callables sharing only a method name (plan hard-stop edges)."""
        return tuple(sorted(self.callables_by_name.get(name, ())))

    def _resolve_invocation(
        self, model: CallableModel, call: CallRecord, context: str
    ) -> list[CallEdge]:
        scope_shaped = self.contract.is_worker_guard_method(
            call.name
        ) or self.contract.is_direct_scope_method(call.name)
        if scope_shaped:
            if not self._is_canonical_scope_call(call):
                return [
                    CallEdge(
                        caller_key=model.key,
                        state=ResolutionState.UNRESOLVED_TARGET,
                        line=call.line,
                        file=call.file,
                        name_start=call.name_start,
                        context_kind=context,
                        uncertain=True,
                    )
                ]
        if call.is_reference:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.FUNCTION_REFERENCE,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=self._name_match_targets(call.name),
                    context_kind=context,
                    uncertain=True,
                )
            ]
        if call.chained:
            # `.name(...)` on an untracked expression chain: the receiver
            # expression type is not statically bound here — fail closed.
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.UNRESOLVED_TARGET,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=self._name_match_targets(call.name),
                    context_kind=context,
                    uncertain=True,
                )
            ]
        if call.qualification:
            return self._resolve_qualified_call(model, call, context)
        if call.receiver_text:
            return self._resolve_member_call(model, call, context)
        return self._resolve_unqualified_call(model, call, context)

    def _is_canonical_scope_call(self, call: CallRecord) -> bool:
        if self.contract.is_worker_guard_method(call.name):
            receiver_fqcn, receiver_known = self.receiver_fqcn_for_call(call)
            return receiver_known and receiver_fqcn == self.contract.worker_guard_receiver_fqcn
        if self.contract.is_direct_scope_method(call.name):
            if self.contract.direct_scope_allow_receiverless and not call.receiver_text:
                return True
            receiver_fqcn, receiver_known = self.receiver_fqcn_for_call(call)
            return receiver_known and receiver_fqcn == self.contract.direct_scope_receiver_fqcn
        return False

    def _resolve_qualified_call(
        self, model: CallableModel, call: CallRecord, context: str
    ) -> list[CallEdge]:
        """Dotted-prefix calls: corpus top-level, package path, or type chain."""
        dotted = call.qualification + "." + call.name
        target = self.top_level_fqcn_by_owner.get(dotted)
        if target is not None:
            return [self._exact_edge(model, call, context, (target,))]
        file_model = self.file_models[call.file]
        segments = call.qualification.split(".")
        if all(segment[:1].islower() for segment in segments):
            # Package-qualified top-level call outside the corpus: the exact
            # dotted spelling is the exact binding (plan edge family 3).
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.EXACT_SYNCHRONOUS,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    external=True,
                    context_kind=context,
                )
            ]
        type_fqcn, origin = self._resolve_type(file_model, call.qualification)
        if origin == "corpus":
            member_call = CallRecord(
                call_id=call.call_id,
                caller_key=call.caller_key,
                file=call.file,
                name=call.name,
                name_start=call.name_start,
                line=call.line,
                receiver_text=call.qualification,
                qualification="",
            )
            return self._resolve_member_call(model, member_call, context)
        if origin == "external":
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.EXACT_SYNCHRONOUS,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    external=True,
                    context_kind=context,
                )
            ]
        if len(segments) >= 2:
            member_call = CallRecord(
                call_id=call.call_id,
                caller_key=call.caller_key,
                file=call.file,
                name=call.name,
                name_start=call.name_start,
                line=call.line,
                receiver_text=segments[-1],
                qualification="",
            )
            return self._resolve_member_call(model, member_call, context)
        return [
            CallEdge(
                caller_key=model.key,
                state=ResolutionState.UNRESOLVED_TARGET,
                line=call.line,
                file=call.file,
                name_start=call.name_start,
                context_kind=context,
                uncertain=True,
            )
        ]

    def _resolve_member_call(
        self, model: CallableModel, call: CallRecord, context: str
    ) -> list[CallEdge]:
        receiver_fqcn, receiver_known = self.receiver_fqcn_for_call(call)
        if not receiver_known or not receiver_fqcn:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.UNRESOLVED_TARGET,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=self._name_match_targets(call.name),
                    context_kind=context,
                    uncertain=True,
                )
            ]
        if receiver_fqcn not in self.owners:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.EXACT_SYNCHRONOUS,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    external=True,
                    context_kind=context,
                )
            ]
        owner = self.owners[receiver_fqcn]
        if owner.kind == "interface":
            if not self._members_named(receiver_fqcn, call.name):
                return [
                    CallEdge(
                        caller_key=model.key,
                        state=ResolutionState.UNRESOLVED_TARGET,
                        line=call.line,
                        file=call.file,
                        name_start=call.name_start,
                        context_kind=context,
                        uncertain=True,
                    )
                ]
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.INTERFACE_DISPATCH,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=tuple(sorted(self._override_targets(receiver_fqcn, call.name))),
                    context_kind=context,
                    uncertain=True,
                )
            ]
        member, member_owner = self._member_in_scope(receiver_fqcn, call.name)
        if member is None:
            extension = self._extension_target(receiver_fqcn, call.name)
            if extension is not None:
                return [self._exact_edge(model, call, context, (extension,))]
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.UNRESOLVED_TARGET,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=self._name_match_targets(call.name),
                    context_kind=context,
                    uncertain=True,
                )
            ]
        if member.is_generic:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.AMBIGUOUS_TARGET,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    context_kind=context,
                    uncertain=True,
                )
            ]
        if member.is_open or member.is_override or self._has_override_named(
            member_owner, call.name
        ):
            overrides = {member.key}
            overrides.update(self._override_targets(member_owner, call.name))
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.VIRTUAL_DISPATCH,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=tuple(sorted(overrides)),
                    context_kind=context,
                    uncertain=True,
                )
            ]
        return [self._exact_edge(model, call, context, (member.key,))]

    def _resolve_unqualified_call(
        self, model: CallableModel, call: CallRecord, context: str
    ) -> list[CallEdge]:
        file_model = self.file_models[call.file]
        owner = self.owners.get(model.owner_fqcn)
        if owner is not None:
            members = self._members_named(owner.fqcn, call.name)
            if len(members) == 1:
                member = members[0]
                if member.is_generic:
                    return [self._ambiguous_edge(model, call, context)]
                return [
                    self._exact_edge(
                        model, call, context, (member.key,), canonical_scope=True
                    )
                ]
            if len(members) > 1:
                selected = self._select_overload(members, call)
                if selected is not None:
                    return [self._exact_edge(model, call, context, (selected.key,))]
                return [self._ambiguous_edge(model, call, context)]
            enclosing = self._enclosing_owner_of(owner.fqcn)
            while enclosing is not None:
                members = self._members_named(enclosing, call.name)
                if len(members) == 1 and not members[0].is_generic:
                    return [
                        self._exact_edge(
                            model, call, context, (members[0].key,), canonical_scope=True
                        )
                    ]
                enclosing = self._enclosing_owner_of(enclosing)
        top_candidates = self._top_level_candidates(call.name, file_model)
        if len(top_candidates) == 1:
            return [
                self._exact_edge(
                    model, call, context, top_candidates, canonical_scope=True
                )
            ]
        if len(top_candidates) > 1:
            return [self._ambiguous_edge(model, call, context)]
        exact_imports = [
            entry
            for entry in file_model.imports
            if not entry.is_star
            and (
                entry.bound_name == call.name
                or entry.fqcn.endswith("." + call.name)
            )
        ]
        distinct_fqcns = sorted({entry.fqcn for entry in exact_imports})
        if len(distinct_fqcns) > 1:
            # Two conflicting exact imports for one name: honest ambiguity.
            return [self._ambiguous_edge(model, call, context)]
        if len(distinct_fqcns) == 1:
            target = self.top_level_fqcn_by_owner.get(distinct_fqcns[0])
            if target is not None:
                return [self._exact_edge(model, call, context, (target,))]
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.EXACT_SYNCHRONOUS,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    external=True,
                    context_kind=context,
                )
            ]
        star_candidates: list[str] = []
        for entry in file_model.imports:
            if not entry.is_star:
                continue
            for key in self.callables_by_name.get(call.name, ()):
                member = self.callables[key]
                if member.node.kind == "top_level_function" and member.owner_fqcn == entry.fqcn:
                    star_candidates.append(key)
        if len(star_candidates) == 1:
            return [self._exact_edge(model, call, context, tuple(star_candidates))]
        if len(star_candidates) > 1:
            return [self._ambiguous_edge(model, call, context)]
        star_import_count = sum(
            1 for entry in file_model.imports if entry.is_star
        )
        if star_import_count > 1:
            # The name is unresolved in scope and two wildcard imports could
            # both export it: the clash cannot be dismissed from source.
            return [self._ambiguous_edge(model, call, context)]
        name_targets = self._name_match_targets(call.name)
        if name_targets:
            return [
                CallEdge(
                    caller_key=model.key,
                    state=ResolutionState.UNRESOLVED_TARGET,
                    line=call.line,
                    file=call.file,
                    name_start=call.name_start,
                    targets=name_targets,
                    context_kind=context,
                    uncertain=True,
                )
            ]
        return [
            CallEdge(
                caller_key=model.key,
                state=ResolutionState.EXACT_SYNCHRONOUS,
                line=call.line,
                file=call.file,
                name_start=call.name_start,
                external=True,
                context_kind=context,
            )
        ]

    def _ambiguous_edge(
        self, model: CallableModel, call: CallRecord, context: str
    ) -> CallEdge:
        return CallEdge(
            caller_key=model.key,
            state=ResolutionState.AMBIGUOUS_TARGET,
            line=call.line,
            file=call.file,
            name_start=call.name_start,
            context_kind=context,
            uncertain=True,
        )

    def _select_overload(
        self, members: list[CallableModel], call: CallRecord
    ) -> CallableModel | None:
        """Unique non-generic overload selected by argument-type compatibility.

        Argument types are inferred conservatively (integer/decimal/string/
        boolean literals only); when inference cannot separate the overloads,
        selection fails closed (None).
        """
        masked = self.file_models[call.file].masked
        raw = self.file_models[call.file].raw or masked
        cursor = _skip_ws(masked, call.name_start + len(call.name))
        if cursor >= len(masked) or masked[cursor] != "(":
            return None
        close = find_balanced(masked, cursor, "(", ")")
        if close < 0:
            return None
        # Masking blanks string literals; literal-type inference needs the
        # delimiters, so the argument region is read from the raw text at
        # the same offsets (never stored).
        arg_text = raw[cursor + 1 : close - 1]
        if not arg_text.strip():
            arg_types: list[str | None] = []
        else:
            depth = 0
            pieces: list[str] = []
            current: list[str] = []
            for ch in arg_text:
                if ch in "(<[":
                    depth += 1
                elif ch in ")>]":
                    depth -= 1
                if ch == "," and depth == 0:
                    pieces.append("".join(current).strip())
                    current = []
                else:
                    current.append(ch)
            pieces.append("".join(current).strip())
            arg_types = [_infer_argument_type(piece) for piece in pieces]
        compatible: list[CallableModel] = []
        for member in members:
            if member.is_generic or len(member.param_types) != len(arg_types):
                continue
            if all(
                arg is not None
                and _simple_name_of_type(param) == arg
                for arg, param in zip(arg_types, member.param_types)
            ):
                compatible.append(member)
        return compatible[0] if len(compatible) == 1 else None

    # ── member/override lookups ──

    def _members_named(self, owner_fqcn: str, method: str) -> list[CallableModel]:
        results = [
            self.callables[key]
            for key in self.callables_by_name.get(method, ())
            if self.callables[key].owner_fqcn == owner_fqcn
            and self.callables[key].node.kind == "function"
        ]
        return sorted(results, key=lambda member: member.key)

    def _member_in_scope(
        self, owner_fqcn: str, method: str
    ) -> tuple[CallableModel | None, str]:
        """Member lookup walking the corpus supertype chain (owner, owner)."""
        seen: set[str] = set()
        current = owner_fqcn
        while current and current not in seen and current in self.owners:
            seen.add(current)
            members = self._members_named(current, method)
            if len(members) == 1:
                return members[0], current
            if len(members) > 1:
                return members[0], current  # ambiguity handled by caller checks
            supertypes = self.owner_supertypes.get(current, ())
            nxt = ""
            for supertype in supertypes:
                if supertype and supertype in self.owners:
                    nxt = supertype
                    break
            current = nxt
        return None, owner_fqcn

    def _has_override_named(self, owner_fqcn: str, method: str) -> bool:
        for key in self.callables_by_name.get(method, ()):
            member = self.callables[key]
            if member.is_override and self._inherits_from(member.owner_fqcn, owner_fqcn):
                return True
        return False

    def _inherits_from(self, owner_fqcn: str, base_fqcn: str) -> bool:
        seen: set[str] = set()
        current = owner_fqcn
        while current and current not in seen:
            seen.add(current)
            if current == base_fqcn:
                return True
            supertypes = self.owner_supertypes.get(current, ())
            nxt = ""
            for supertype in supertypes:
                if supertype and supertype in self.owners:
                    nxt = supertype
                    break
            current = nxt
        return False

    def _override_targets(self, base_fqcn: str, method: str) -> set[str]:
        targets: set[str] = set()
        for key in self.callables_by_name.get(method, ()):
            member = self.callables[key]
            if member.is_override and self._inherits_from(member.owner_fqcn, base_fqcn):
                targets.add(key)
        return targets

    def _extension_target(self, receiver_fqcn: str, method: str) -> str | None:
        candidates = []
        for key in self.callables_by_name.get(method, ()):
            member = self.callables[key]
            if member.node.kind == "top_level_function" and member.node.receiver:
                resolved = self._resolve_simple(self.file_models[member.file], member.node.receiver)
                if resolved == receiver_fqcn:
                    candidates.append(key)
        return candidates[0] if len(candidates) == 1 else None

    def _top_level_candidates(self, name: str, file_model: FileModel) -> list[str]:
        candidates: set[str] = set()
        for key in self.callables_by_name.get(name, ()):
            member = self.callables[key]
            if member.node.kind != "top_level_function" or member.node.receiver:
                continue
            if member.file == file_model.path or member.owner_fqcn == file_model.package:
                candidates.add(key)
        return sorted(candidates)

    def _enclosing_owner_of(self, fqcn: str) -> str | None:
        if "." not in fqcn:
            return None
        parent = fqcn.rsplit(".", 1)[0]
        return parent if parent in self.owners else None

    def _exact_edge(
        self,
        model: CallableModel,
        call: CallRecord,
        context: str,
        targets: tuple[str, ...],
        canonical_scope: bool = False,
    ) -> CallEdge:
        state = (
            ResolutionState.EXACT_CANONICAL_SCOPE
            if canonical_scope
            else ResolutionState.EXACT_SYNCHRONOUS
        )
        return CallEdge(
            caller_key=model.key,
            state=state,
            line=call.line,
            file=call.file,
            name_start=call.name_start,
            targets=tuple(sorted(targets)),
            context_kind=context,
        )

    # ── SCC + roots ──

    def _detect_scc(self, edges: tuple[CallEdge, ...] | None = None) -> SCCState:
        """Iterative Tarjan SCC over the edge target graph (explicit cycles)."""
        edge_set = self.edges if edges is None else edges
        adjacency: dict[str, list[str]] = {key: [] for key in sorted(self.callables)}
        for edge in edge_set:
            for target in edge.targets:
                if target in adjacency and target != edge.caller_key:
                    adjacency[edge.caller_key].append(target)
        for key in adjacency:
            adjacency[key] = sorted(set(adjacency[key]))
        self_loop_keys = {
            edge.caller_key
            for edge in edge_set
            if edge.caller_key in edge.targets
        }
        index: dict[str, int] = {}
        lowlink: dict[str, int] = {}
        stack: list[str] = []
        on_stack: set[str] = set()
        cyclic: set[str] = set()
        scc_count = 0
        counter = 0
        for start in sorted(adjacency):
            if start in index:
                continue
            work: list[tuple[str, int]] = [(start, 0)]
            while work:
                node, pointer = work[-1]
                if pointer == 0:
                    index[node] = counter
                    lowlink[node] = counter
                    counter += 1
                    stack.append(node)
                    on_stack.add(node)
                advanced = False
                neighbors = adjacency[node]
                while pointer < len(neighbors):
                    target = neighbors[pointer]
                    pointer += 1
                    if target not in index:
                        work[-1] = (node, pointer)
                        work.append((target, 0))
                        advanced = True
                        break
                    if target in on_stack:
                        lowlink[node] = min(lowlink[node], index[target])
                if advanced:
                    continue
                work.pop()
                if lowlink[node] == index[node]:
                    component: list[str] = []
                    while True:
                        member = stack.pop()
                        on_stack.discard(member)
                        component.append(member)
                        if member == node:
                            break
                    scc_count += 1
                    if len(component) > 1:
                        cyclic.update(component)
                    elif component[0] in self_loop_keys:
                        cyclic.add(component[0])
                if work:
                    parent = work[-1][0]
                    lowlink[parent] = min(lowlink[parent], lowlink[node])
        return SCCState(cyclic_keys=tuple(sorted(cyclic)), scc_count=scc_count)

    def _classify_roots(self) -> None:
        inbound: dict[str, bool] = {key: False for key in self.callables}
        for edge in self.edges:
            for target in edge.targets:
                if target in inbound:
                    inbound[target] = True
        worker_classes = set(self.worker_classes())
        do_work_roots: set[str] = set()
        for key in sorted(self.callables):
            model = self.callables[key]
            if model.method == "doWork" and model.owner_fqcn in worker_classes:
                do_work_roots.add(key)
        for key in sorted(self.callables):
            model = self.callables[key]
            if inbound.get(key, False):
                continue
            if key in do_work_roots:
                self.roots[key] = RootKind.WORKER_DO_WORK
            elif model.node.kind == "top_level_function":
                self.roots[key] = RootKind.TOP_LEVEL_EXTERNAL
            elif model.visibility in ("public", "protected"):
                if model.is_override and not self._owner_has_corpus_supertype(model):
                    self.roots[key] = RootKind.FRAMEWORK_CALLBACK
                else:
                    self.roots[key] = RootKind.PUBLIC_OR_PROTECTED_EXTERNAL
            else:
                self.roots[key] = RootKind.UNKNOWN_EXTERNAL

    def _owner_has_corpus_supertype(self, model: CallableModel) -> bool:
        for supertype in self.owner_supertypes.get(model.owner_fqcn, ()):
            if supertype and supertype in self.owners:
                return True
        return False

    # ── worker discovery support (consumed by worker_recognition) ──

    def worker_classes(self) -> list[str]:
        """Corpus owner FQCNs whose supertype chain reaches a worker base.

        The chain follows a resolved supertype into an EXTERNAL base too: a
        production worker's direct base is usually the external
        ``CoroutineWorker``, so restricting continuation to corpus owners
        would end every walk before the base check.
        """
        bases = set(self.contract.worker_base_fqcns)
        results: list[str] = []
        for fqcn in sorted(self.owners):
            seen: set[str] = set()
            current = fqcn
            found = False
            while current and current not in seen:
                seen.add(current)
                if current in bases:
                    found = True
                    break
                supertypes = self.owner_supertypes.get(current, ())
                nxt = ""
                for supertype in supertypes:
                    if not supertype:
                        continue
                    if supertype in bases or supertype in self.owners:
                        nxt = supertype
                        break
                current = nxt
            if found and fqcn not in bases:
                results.append(fqcn)
        return results


@dataclass(frozen=True)
class CallGraph:
    """Deterministic view over the built graph (the proof layer's input)."""

    builder: CallGraphBuilder
    edges: tuple[CallEdge, ...]
    scc: SCCState
    scc_exact: SCCState  # cycles over exact edges only (uncertain edges excluded)
    roots: dict[str, RootKind]

    def edges_to(self, target_key: str) -> tuple[CallEdge, ...]:
        return tuple(edge for edge in self.edges if target_key in edge.targets)


def _infer_argument_type(piece: str) -> str | None:
    """Conservative literal argument-type inference (None = unknown)."""
    piece = piece.strip()
    if not piece:
        return None
    if re.match(r"^-?\d+$", piece):
        return "Int"
    if re.match(r"^-?\d+L$", piece):
        return "Long"
    if re.match(r"^-?\d*\.\d+[fF]$", piece):
        return "Float"
    if re.match(r"^-?\d*\.\d+$", piece):
        return "Double"
    if piece.startswith('"') and piece.endswith('"'):
        return "String"
    if piece in ("true", "false"):
        return "Boolean"
    if piece.endswith("'") and piece.startswith("'"):
        return "Char"
    return None
