"""Small, fail-closed Kotlin callable scanner used by the DB discovery guard."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping

try:  # package mode: imported as ``scripts.kotlin_callable_parser``
    from .db_policy_signature import FunctionSignature, SignatureError, normalize_type_text
except ImportError:  # pragma: no cover - flat mode: standalone tools put ``scripts`` on sys.path
    from db_policy_signature import FunctionSignature, SignatureError, normalize_type_text

__all__ = [
    "ParserError", "CallableDeclaration", "OwnerDeclaration", "mask_kotlin_source",
    "canonical_source_path", "parse_kotlin_file", "find_owner_declarations",
    "find_callable_declarations", "resolve_callable", "erase_star_projections",
    "ProjectTypeIndex", "project_type_declarations",
    "project_nested_type_declarations",
]

MAX_SOURCE = 2_000_000
MAX_DEPTH = 128
MAX_TOKENS = 250_000
#: Generic repo-Kotlin-path bounds for ``canonical_source_path``: at most
#: 16 path components and 256 characters.  Purely syntactic -- no
#: source-root/topology knowledge lives in this module.
MAX_SOURCE_PATH_COMPONENTS = 16
MAX_SOURCE_PATH_LENGTH = 256
_MESSAGE = "kotlin callable parser error"

# Signature failures retain their controlled codes at this boundary.  The
# code is safe to expose; input and exception text are never exposed.
_SIGNATURE_ERROR_CODES = frozenset({
    "NOT_TEXT", "BLANK_TYPE", "CONTROL_TYPE", "CONTROL_PATH",
    "UNSUPPORTED_TOKEN", "BAD_TYPE", "UNBALANCED_ANGLE", "UNBALANCED_PAREN",
    "UNBALANCED_ARRAY", "BAD_COMMAS", "EMPTY_GENERIC", "DUPLICATE_NULLABLE",
    "MISSING_ARROW", "EMPTY_FUNCTION", "VARARG_CONTEXT", "VARARG_PREFIX",
    "TYPE_TOO_LONG", "TYPE_TOO_MANY_TOKENS", "NESTING_TOO_DEEP",
    "PATH_TOO_LONG", "PATH_TOO_DEEP", "PATH_SEGMENT_TOO_LONG", "BAD_NAME",
    "BAD_PATH", "BAD_PARAMS", "NOT_OBJECT", "BAD_KEYS", "BAD_OWNER",
    "BAD_RECEIVER", "INVALID_SIGNATURE_ERROR",
})

# Controlled path-syntax codes for ``canonical_source_path``: exactly one
# code per rejection class.  The set is closed so unknown reason codes
# cannot leak into diagnostics; the codes are safe to expose, input text
# is not.
_PATH_SYNTAX_ERROR_CODES = frozenset({
    "PATH_NOT_TEXT", "PATH_EMPTY", "PATH_BACKSLASH", "PATH_ABSOLUTE",
    "PATH_UNC", "PATH_DRIVE", "PATH_TRAILING_SLASH", "PATH_DOUBLE_SLASH",
    "PATH_TRAVERSAL", "PATH_DOT_SEGMENT", "PATH_TOO_LONG", "PATH_TOO_DEEP",
    "PATH_NOT_KOTLIN",
})


class ParserError(Exception):
    """An error with a deliberately fixed, non-data-bearing diagnostic."""
    def __init__(self, code: str = "PARSER_ERROR") -> None:
        allowed_codes = {
            "PARSER_ERROR", "SIGNATURE_UNSUPPORTED", "TYPE_UNRESOLVED",
            "PATH_TOO_LONG", "PATH_TOO_DEEP", "PATH_SEGMENT_TOO_LONG",
        } | _SIGNATURE_ERROR_CODES | _PATH_SYNTAX_ERROR_CODES
        self.code = code if code in allowed_codes else "PARSER_ERROR"
        self.message = _MESSAGE
        super().__init__(self.message)


def _fail(code: str = "PARSER_ERROR") -> None:
    raise ParserError(code)


def _fail_signature(error: SignatureError) -> None:
    _fail(error.code if error.code in _SIGNATURE_ERROR_CODES else "PARSER_ERROR")


# GR-07 convergence round: star projections.  ``Type<*>`` is Kotlin's erased
# wildcard argument; the closed signature grammar below it has no ``*`` token,
# so every spelling carrying one used to die as UNSUPPORTED_TOKEN and killed
# the WHOLE file's callable discovery (evidence: AiServiceResult<*> x1 in
# HybridDedupeJudgeService.kt made all three of its callables vanish, and 12
# production files carry at least one star spelling).  The wildcard carries no
# type information -- it is ERASED to ``Any?`` (Kotlin's own semantic
# equivalent, ``List<*> == List<out Any?>``), which is already inside the
# closed grammar on BOTH sides of every downstream exact signature comparison,
# so no policy/loader/consumer change is needed.  Placement is validated by a
# closed scanner: a ``*`` is rewritten ONLY directly inside a generic argument
# list between ``<``/`,` and `,`/``>``.  Every other occurrence (bare, doubled,
# mid-type) is left untouched so ``normalize_type_text`` keeps rejecting the
# text with its existing sanitized UNSUPPORTED_TOKEN failure -- malformed uses
# fail closed exactly as before.
def erase_star_projections(text: str) -> str:
    """Rewrite legal star-projection arguments to their erased ``Any?`` form."""
    if not isinstance(text, str) or "*" not in text:
        return text
    out: list[str] = []
    depth = 0
    prev = ""  # last significant (non-space) character seen
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("->", i):
            # A function-type arrow is not a generic closer: skip both chars
            # without touching the angle depth or the boundary state.
            out.append("->")
            prev = ">"
            i += 2
            continue
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
        elif c == "*" and depth > 0 and prev in ("<", ","):
            j = i + 1
            while j < n and text[j].isspace():
                j += 1
            nxt = text[j] if j < n else ""
            if nxt in (",", ">"):
                out.append("Any?")
                prev = "?"
                i += 1
                continue
        if not c.isspace():
            prev = c
        out.append(c)
        i += 1
    return "".join(out)


# GR-07 convergence round, second residual sweep (evidence:
# build/guard-debug/gr07/probe15_token.py reproduction over every retained
# S2b failure family, 2026-08-27).  Three further ERASE-only normalizations
# join the pipeline, each proven by production spellings and each invisible
# to downstream identity because BOTH sides of every comparison pass through
# the same normalizer:
#
# 1. Named parameters inside FUNCTION-TYPE argument groups
#    (``onInsideTransaction: suspend (groupId: Long) -> Unit`` x28 in
#    GroupTransactionCoordinator.kt alone): Kotlin ignores argument names for
#    function-type identity, so ``name:`` prefixes are dropped.
# 2. ``@JvmSuppressWildcards`` inside generic argument lists (DI multibinding
#    modules): a compile-time-only annotation; erased to nothing.
# 3. Use-site variance prefixes ``out``/``in`` inside generic argument lists
#    (``Class<out ListenableWorker>`` x6, WorkerSpecScheduler.kt): erased at
#    runtime by Kotlin itself.
#
# Anything outside these closed shapes keeps failing closed through
# ``normalize_type_text`` exactly as before.
_TYPE_JVM_SUPPRESS_WILDCARDS = re.compile(
    r"@(?:[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\.)?JvmSuppressWildcards\s+"
)
_TYPE_VARIANCE_PREFIX = re.compile(r"([<,])\s*(?:out|in)\s+(?=[A-Za-z_])")


def _strip_function_type_param_names(inner: str) -> str:
    parts = _split_top(inner)
    rewritten: list[str] = []
    for part in parts:
        candidate = part.strip()
        match = re.fullmatch(r"([A-Za-z_]\w*)\s*:\s*(.+)", candidate, re.S)
        if match:
            rewritten.append(_erase_function_type_parameter_names(match.group(2).strip()))
        else:
            rewritten.append(_erase_function_type_parameter_names(candidate))
    return ",".join(rewritten)


def _erase_function_type_parameter_names(text: str) -> str:
    """Drop ``name:`` prefixes inside function-type argument groups.

    Only a parenthesized group DIRECTLY followed by ``->`` counts; ordinary
    parenthesized types and every other parenthesis use keep their text.
    """
    if "(" not in text or "->" not in text or ":" not in text:
        return text
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        if text[i] != "(":
            out.append(text[i])
            i += 1
            continue
        depth = 0
        j = i
        while j < n:
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if j >= n:
            out.append(text[i:])
            break
        k = j + 1
        while k < n and text[k].isspace():
            k += 1
        if text.startswith("->", k):
            out.append("(")
            out.append(_strip_function_type_param_names(text[i + 1:j]))
            out.append(")")
            i = j + 1
            continue
        out.append(text[i:j + 1])
        i = j + 1
    return "".join(out)


def _normalize_closed_type_text(typ: str) -> str:
    """Run the full closed erase pipeline over one raw type spelling."""
    erased = _erase_function_type_parameter_names(typ)
    erased = _TYPE_JVM_SUPPRESS_WILDCARDS.sub("", erased)
    erased = _TYPE_VARIANCE_PREFIX.sub(r"\1", erased)
    return erase_star_projections(erased)


def mask_kotlin_source(text: str) -> str:
    """Blank comments, strings, and character literals without changing offsets.

    String templates are tracked: ``${...}`` inside a literal is part of the
    literal, so a quote or a nested template inside the template expression
    can neither terminate the enclosing literal early nor leak structural
    braces/quotes into the masked output.  Template-expression characters are
    blanked exactly like the rest of the literal (newlines preserved), and a
    ``${`` that is never closed fails closed as MALFORMED_SOURCE.  Sources
    without ``${`` inside literals mask byte-for-byte identically to the
    previous non-template-aware masker.
    """
    if not isinstance(text, str) or len(text) > MAX_SOURCE:
        _fail()
    out = list(text)
    i, n, mode, depth = 0, len(text), None, 0
    #: Saved ``(mode, template_depth)`` return points.  Entering ``${...}``
    #: saves the enclosing literal; entering a nested literal from template
    #: code saves the template expression with its current brace depth.
    saved: list[tuple[str | None, int]] = []
    template_depth = 0
    while i < n:
        c = text[i]
        if mode == "line":
            if c not in "\r\n": out[i] = " "
            if c in "\r\n": mode = None
        elif mode == "block":
            if text.startswith("/*", i): depth += 1; out[i] = out[i + 1] = " "; i += 1
            elif text.startswith("*/", i):
                depth -= 1; out[i] = out[i + 1] = " "; i += 1
                if depth == 0: mode = None
            elif c not in "\r\n": out[i] = " "
        elif mode == "template":
            # Expression code of ``${...}``: still literal text, so blanked,
            # but structurally walked to find the true end of the literal.
            if c == "{":
                template_depth += 1
                out[i] = " "
            elif c == "}":
                out[i] = " "
                if template_depth == 0:
                    mode, template_depth = saved.pop()
                    i += 1
                    continue
                template_depth -= 1
            elif text.startswith('"""', i):
                saved.append(("template", template_depth))
                mode = "triple"
                out[i:i + 3] = [" "] * 3
                i += 2
            elif c == '"':
                saved.append(("template", template_depth))
                mode = "string"
                out[i] = " "
            elif c == "'":
                saved.append(("template", template_depth))
                mode = "char"
                out[i] = " "
            elif c not in "\r\n":
                out[i] = " "
        elif mode in ("string", "triple", "char"):
            if text.startswith("${", i):
                saved.append((mode, 0))
                mode = "template"
                template_depth = 0
                out[i] = out[i + 1] = " "
                i += 1
            else:
                end = ((mode == "triple" and text.startswith('"""', i)) or
                       (mode == "string" and c == '"') or
                       (mode == "char" and c == "'"))
                if end:
                    width = 3 if mode == "triple" else 1
                    for j in range(width): out[i + j] = " "
                    mode, template_depth = saved.pop() if saved else (None, 0)
                    i += width - 1
                else:
                    if c not in "\r\n": out[i] = " "
                    if mode != "triple" and c == "\\" and i + 1 < n:
                        if text[i + 1] not in "\r\n": out[i + 1] = " "
                        i += 1
        else:
            if text.startswith("//", i): mode = "line"; out[i] = out[i + 1] = " "; i += 1
            elif text.startswith("/*", i): mode = "block"; depth = 1; out[i] = out[i + 1] = " "; i += 1
            elif text.startswith('"""', i): mode = "triple"; out[i:i + 3] = [" "] * 3; i += 2
            elif c == '"': mode = "string"; out[i] = " "
            elif c == "'": mode = "char"; out[i] = " "
        i += 1
    if mode is not None or saved:
        # An unterminated comment, literal, or ``${...}`` template leaves the
        # source structure unverifiable: fail closed.
        _fail("MALFORMED_SOURCE")
    return "".join(out)


def canonical_source_path(path: str | Path) -> str:
    """Return a syntactically valid repository-relative POSIX ``.kt`` path.

    GENERIC repo-Kotlin-path syntax validation only: repository-relative
    POSIX form, ``.kt`` extension, bounded length and depth.  There is
    deliberately no source-root/topology knowledge left in this module --
    any module tree (``app/src/main/java``, ``feature/src/main/kotlin``,
    ``lib/core/src/main/java``, ...) is syntactically valid, and so is any
    source set.  Root MEMBERSHIP is a separate concern, validated later by
    root-aware stages via ``source_roots.is_declared_production_path``.

    Exactly one controlled ``ParserError`` code per rejection class:

      * non-string input          -> PATH_NOT_TEXT
      * blank                     -> PATH_EMPTY
      * backslash separator       -> PATH_BACKSLASH
      * leading ``/``             -> PATH_ABSOLUTE
      * leading ``//`` (UNC)      -> PATH_UNC
      * drive prefix (``C:``)     -> PATH_DRIVE
      * trailing ``/``            -> PATH_TRAILING_SLASH
      * duplicate slash           -> PATH_DOUBLE_SLASH
      * ``..`` segment            -> PATH_TRAVERSAL
      * ``.`` segment             -> PATH_DOT_SEGMENT
      * more than MAX_SOURCE_PATH_LENGTH characters     -> PATH_TOO_LONG
      * more than MAX_SOURCE_PATH_COMPONENTS components -> PATH_TOO_DEEP
      * missing ``.kt`` suffix    -> PATH_NOT_KOTLIN

    The input is never normalized before validation: normalization would
    turn an invalid path into a valid-looking one and hide traversal or
    foreign roots.
    """
    try:
        if isinstance(path, Path):
            if path.drive:
                # pathlib spells UNC drives as ``//server/share``.
                _fail("PATH_UNC" if path.drive.startswith(("//", "\\")) else "PATH_DRIVE")
            if path.root or path.is_absolute():
                _fail("PATH_ABSOLUTE")
            if any("\\" in part for part in path.parts):
                _fail("PATH_BACKSLASH")
            raw = "/".join(path.parts)
        elif isinstance(path, str):
            raw = path
        else:
            _fail("PATH_NOT_TEXT")
        # Do not normalize first: normalization would turn an invalid path into
        # a valid-looking one (and would hide traversal or foreign roots).
        if not raw:
            _fail("PATH_EMPTY")
        if "\\" in raw:
            _fail("PATH_BACKSLASH")
        if raw.startswith("//"):
            _fail("PATH_UNC")
        if raw.startswith("/"):
            _fail("PATH_ABSOLUTE")
        if re.match(r"^[A-Za-z]:", raw):
            _fail("PATH_DRIVE")
        if raw.endswith("/"):
            _fail("PATH_TRAILING_SLASH")
        parts = raw.split("/")
        if any(part == "" for part in parts):
            _fail("PATH_DOUBLE_SLASH")
        if any(part == ".." for part in parts):
            _fail("PATH_TRAVERSAL")
        if any(part == "." for part in parts):
            _fail("PATH_DOT_SEGMENT")
        if len(raw) > MAX_SOURCE_PATH_LENGTH:
            _fail("PATH_TOO_LONG")
        if len(parts) > MAX_SOURCE_PATH_COMPONENTS:
            _fail("PATH_TOO_DEEP")
        if not raw.endswith(".kt"):
            _fail("PATH_NOT_KOTLIN")
        return raw
    except ParserError:
        raise
    except Exception:
        _fail()


@dataclass(frozen=True)
class OwnerDeclaration:
    owner: str
    name: str
    start_offset: int
    end_offset: int
    body_start: int
    body_end: int
    status: str = "RESOLVED_EXACTLY"


@dataclass(frozen=True)
class CallableDeclaration:
    signature: FunctionSignature
    owner: str
    start_offset: int
    end_offset: int
    body: str | None
    status: str = "RESOLVED_EXACTLY"

    @property
    def offsets(self) -> tuple[int, int]:
        return self.start_offset, self.end_offset


_ID = r"[A-Za-z_][A-Za-z0-9_]*"
# Interfaces are owners too: every Kotlin declaration container (class,
# object, interface) qualifies its members.  The D4 declaration scan inventories
# ``interface`` members with interface-qualified owners; an owner parser that
# only knew class/object left those members ownerless, so their containing
# callables could never be matched (and a bodyless class header before an
# interface swallowed the interface's body brace, hiding its members entirely).
_OWNER = re.compile(r"\b(class|object|interface)\s+(%s)" % _ID)
_FUN = re.compile(r"\bfun\b")
_MODIFIERS = re.compile(r"(?:public|private|protected|internal|expect|actual|inline|infix|operator|tailrec|override|final|open|abstract|external|suspend|crossinline|noinline|reified|const|lateinit|\s)+")

# Names which are concrete without a declaration in the source file.  This is
# deliberately a small, closed list: accepting every capitalised identifier
# made the old scanner silently authorize misspelled/project-local types.
#
# GR-07 hardening step B (guarded stdlib extension): the three names appended
# below are kotlin.stdlib types evidenced by the REAL production tree -- the
# distinct unresolved simple names collected per-file across every
# ``DB_SIGNATURE_UNRESOLVED`` diagnostic file of the activated scan, after the
# step-A project-wide index.  Evidence (occurrences among retained
# TYPE_UNRESOLVED declarations): ``Throwable`` x38, ``Exception`` x3,
# ``MutableList`` x1.  None of them is declared anywhere in the project index,
# so the addition can never shadow a project type (builtins resolve AFTER
# same-file scopes, the file's package, imports, and aliases, and BEFORE the
# project index).  Every other evidenced residual name was deliberately NOT
# added: java.util/java.time types (``Date``, ``Calendar``, ``InputStream``,
# ``DateTimeFormatter``, ``ZoneId``), Android types (``Bitmap``,
# ``StatusBarNotification``), kotlin.text types outside the approved
# extension set (``Appendable``, ``Regex``), and project-local debt that
# belongs to the step-A index or explicit imports -- never to this closed
# builtin set.  The set stays CLOSED; any further addition must repeat this
# evidence probe first and is a deliberate, documented decision.
_BUILTINS = frozenset({
    "Any", "Nothing", "Unit", "String", "Char", "Boolean", "Byte", "Short",
    "Int", "Long", "Float", "Double", "Number", "Array", "ByteArray",
    "ShortArray", "IntArray", "LongArray", "FloatArray", "DoubleArray",
    "BooleanArray", "CharArray", "List", "Set", "Map", "Collection",
    "Iterable", "Iterator", "Sequence", "Comparable", "Enum", "Pair", "Triple",
    # GR-07 step B append point -- evidenced kotlin.stdlib types only.
    "Throwable", "Exception", "MutableList",
    # GR-07 hardening step C (second evidence probe, 2026-08-26 activated-scan
    # residual ``TYPE_UNRESOLVED`` reproduction, build/guard-debug/gr07/
    # probe_sites_typefail.json): ``MutableSet`` x1 and ``MutableMap`` x1 are
    # the same default-imported kotlin.collections family as the already
    # accepted ``MutableList``; ``Appendable`` x13 and ``Regex`` x4 are
    # default-imported kotlin.text types (no import statement is required for
    # them in Kotlin, which is why no import-based resolution can ever see
    # them); ``Date`` x7 and ``Calendar`` x3 arrive through ``import
    # java.util.*`` wildcard imports, whose members are outside source-visible
    # closed-world knowledge.  None is declared anywhere in the project index,
    # so none can shadow a project type.  The earlier step-B note declined
    # these names when they were single-digit noise; with the C1 argument-type
    # pollution fixed they are now dominant honest residual debt, and this
    # append repeats the documented evidence-first process.
    "MutableSet", "MutableMap", "Appendable", "Regex", "Date", "Calendar",
    # GR-07 convergence round (third evidence probe, 2026-08-27,
    # build/guard-debug/gr07/probe15_token.py): ``Class`` x2
    # (WorkerSpecScheduler.kt ``Class<out ListenableWorker>``) is a
    # java.lang default-imported type -- no import statement is required in
    # Kotlin, so no import-based resolution can ever see it.  No project type
    # is named ``Class``, so the addition cannot shadow anything.
    "Class",
})

# Closed root packages of EXTERNAL (non-project) platform/SDK types.  A fully
# qualified spelling under one of these roots is concrete without an import:
# the compiler resolves it syntactically and no project declaration can share
# the root.  GR-07 hardening step C evidence (same probe): fully qualified
# parameter spellings ``androidx.sqlite.db.SupportSQLiteDatabase`` x144,
# ``android.database.Cursor`` x5, ``java.util.regex.Matcher`` x3,
# ``android.content.Context`` x2, ``android.os.Bundle`` x2, ``android.net.Uri``
# x2, ``java.io.File`` x3, ``java.io.OutputStream`` x1,
# ``java.math.BigDecimal`` x1, ``androidx.room.RoomDatabase.Builder<...>`` x1,
# ``android.graphics.BitmapFactory.Options`` x1.  Accepting only these five
# ROOTS keeps the set closed and auditable; a misspelled external FQCN now
# resolves, but its exact spelling still participates verbatim in every
# downstream signature comparison, so a fabricated identity can never match a
# real DAO authorization target -- it stays visible as an unmatched call.
_EXTERNAL_TYPE_ROOTS = frozenset({"java", "javax", "kotlin", "android", "androidx"})


@dataclass(frozen=True)
class ProjectTypeIndex:
    """Closed-world PROJECT-WIDE type index (GR-07 hardening step A).

    Built once per scan by the root-aware stages (``declaration_scanner``
    walks the declared production roots) and threaded explicitly into
    ``find_callable_declarations`` exactly like
    ``tolerate_unresolved_types``.  It extends -- never replaces -- the
    same-file closed world: a simple name the file itself cannot resolve is
    looked up here, and ONLY a unique match resolves.  A simple name
    declared in two or more packages maps to several FQCNs and fails closed
    as ``TYPE_UNRESOLVED``: ambiguity is honest debt, never a guessed
    resolution.

    ``by_simple_name`` maps each declared simple name to the sorted tuple of
    package-qualified FQCNs declaring it; ``qualified`` carries every
    indexed fully-qualified spelling so an exact dotted reference can be
    accepted without an import.  Both members are sorted/frozen so the same
    tree always yields the same index and therefore the same resolutions.
    """

    by_simple_name: Mapping[str, tuple[str, ...]]
    qualified: frozenset[str]


_PROJECT_TYPE_DECL = re.compile(
    r"\b(?:enum\s+class|annotation\s+class|interface|class|object)\s+(%s)" % _ID
)


def project_type_declarations(text: str) -> tuple[str, ...]:
    """Package-qualified FQCNs of one file's TOP-LEVEL type declarations.

    Covers ``class``/``object``/``interface`` plus ``enum class`` and
    ``annotation class`` spellings, qualified by the file's package
    declaration.  Declarations nested inside an owner body are excluded:
    they are not package-level project types.  Malformed sources fail
    closed through ``mask_kotlin_source`` exactly like every other parser
    entry point; index builders skip such files silently because the
    failing file already emits its own diagnostic.
    """
    masked = mask_kotlin_source(text)
    package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)", masked)
    package = package_match.group(1) if package_match else ""
    owners = find_owner_declarations(masked)
    names: set[str] = set()
    for match in _PROJECT_TYPE_DECL.finditer(masked):
        parent = max(
            (owner for owner in owners
             if owner.body_start <= match.start() < owner.body_end),
            key=lambda owner: owner.body_start,
            default=None,
        )
        if parent is not None:
            continue
        names.add(((package + ".") if package else "") + match.group(1))
    return tuple(sorted(names))


def project_nested_type_declarations(text: str) -> tuple[str, ...]:
    """Parent-qualified FQCNs of one file's NESTED type declarations.

    GR-07 hardening step C companion to :func:`project_type_declarations`:
    ``class Outer { class Inner }`` yields ``<package>.Outer.Inner`` (nested
    chains qualify through their full parent chain, which owner discovery
    already provides).  Top-level declarations are excluded -- the sibling
    function owns those.  The project type index uses this to accept exact
    ``Outer.Inner`` REFERENCES without ever adding nested simple names to the
    simple-name resolution map, so a bare ``Inner`` stays out of scope exactly
    as Kotlin requires without an import.
    """
    masked = mask_kotlin_source(text)
    owners = find_owner_declarations(masked)
    names: set[str] = set()
    for match in _PROJECT_TYPE_DECL.finditer(masked):
        parent = max(
            (owner for owner in owners
             if owner.body_start <= match.start() < owner.body_end),
            key=lambda owner: owner.body_start,
            default=None,
        )
        if parent is None:
            continue
        names.add(parent.owner + "." + match.group(1))
    return tuple(sorted(names))


@dataclass(frozen=True)
class _TypeEnvironment:
    package: str
    types: frozenset[str]
    imports: dict[str, str | None]
    aliases: dict[str, str | None]
    type_counts: dict[str, int]
    owner_scope: str = ""
    project_types: ProjectTypeIndex | None = None
    #: Packages of ``import pkg.*`` wildcard imports (GR-07 hardening step C).
    #: Sorted so the same file always yields the same environment.  A wildcard
    #: package alone proves nothing about membership; resolution only accepts
    #: ``pkg.Name`` spellings that the PROJECT index independently confirms.
    wildcards: tuple[str, ...] = ()
    #: Generic type-parameter names declared in this file's owner headers and
    #: ``fun <T>`` headers (GR-07 hardening step C).  A bare ``T`` in a
    #: parameter list is a declared type variable, not a misspelled class.
    #: Collected per FILE (not per fun) on purpose: the set only ever admits
    #: names that are declared generics somewhere in the same file, and every
    #: resolved identity still participates verbatim in downstream exact
    #: signature comparisons.
    type_variables: frozenset[str] = frozenset()


def _generic_parameter_names(masked: str) -> frozenset[str]:
    """Collect declared generic type-parameter names from one masked file.

    Bounded closed-world extraction: a ``<...>`` group is considered a generic
    parameter list only when it directly follows ``fun `` or an
    ``class|object|interface`` header name.  Each top-level comma segment
    contributes its declared variable: optional ``in``/``out`` variance prefix,
    then the name, then an optional `` : bound`` (bounds may contain nested
    angles and are ignored -- only the DECLARED variable name is collected).
    """
    names: set[str] = set()
    # ``fun <T>`` carries its parameter list directly after the keyword; an
    # owner spells its NAME first (``class Repo<T : Any>``).  Both shapes are
    # covered; a ``<`` following anything else is never a parameter list.
    for keyword_match in re.finditer(
        r"\b(?:fun|(?:enum\s+)?class|interface|object)\s+(?:[A-Za-z_]\w*\s*)?<", masked,
    ):
        opening = keyword_match.end() - 1
        depth = 0
        end = -1
        for i in range(opening, len(masked)):
            char = masked[i]
            if char == "<":
                depth += 1
            elif char == ">":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        if end < 0:
            continue
        for segment in _split_top(masked[opening + 1:end]):
            declaration = segment.split(":", 1)[0].strip()
            declaration = re.sub(r"^(?:in|out|reified)\s+", "", declaration)
            name_match = re.fullmatch(r"[A-Za-z_]\w*", declaration)
            if name_match:
                names.add(declaration)
    return frozenset(sorted(names))


def _type_environment(
    masked: str,
    owner_scope: str = "",
    project_types: ProjectTypeIndex | None = None,
) -> _TypeEnvironment:
    package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)", masked)
    package = package_match.group(1) if package_match else ""
    imports: dict[str, str | None] = {}
    for match in re.finditer(r"\bimport\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s+as\s+(%s))?" % _ID, masked):
        fqcn, alias = match.group(1), match.group(2)
        key = alias or fqcn.rsplit(".", 1)[-1]
        if key in imports and imports[key] != fqcn:
            imports[key] = None
        else:
            imports[key] = fqcn
    # GR-07 hardening step C: wildcard imports carry no member knowledge, but
    # the project index can later confirm ``pkg.Name`` spellings concretely.
    wildcards = tuple(sorted({
        match.group(1)
        for match in re.finditer(r"\bimport\s+([A-Za-z_][A-Za-z0-9_.]*)\.\*", masked)
    }))
    aliases: dict[str, str | None] = {}
    for match in re.finditer(r"\btypealias\s+(%s)\s*=\s*([^\n;]+)" % _ID, masked):
        key, target = match.group(1), match.group(2).strip()
        if key in aliases and aliases[key] != target:
            aliases[key] = None
        else:
            aliases[key] = target
    types = set()
    type_counts: dict[str, int] = {}
    owners = find_owner_declarations(masked)
    for match in re.finditer(r"\b(?:class|object|interface|enum\s+class|annotation\s+class)\s+(%s)" % _ID, masked):
        # A declaration nested in an owner is not a package-level type.  The
        # old package-prefixed pass added ``package.Inner`` for
        # ``package.Outer.Inner`` as well, making an otherwise exact nested
        # lookup appear ambiguous.
        parent = max(
            (owner for owner in owners
             if owner.body_start <= match.start() < owner.body_end),
            key=lambda owner: owner.body_start,
            default=None,
        )
        fqcn = ((parent.owner if parent else package) + "." if (parent or package) else "") + match.group(1)
        types.add(fqcn)
        type_counts[fqcn] = type_counts.get(fqcn, 0) + 1
    # Nested declarations are also concrete symbols.  Owner discovery supplies
    # the authoritative qualified spelling; the filename is never involved.
    for owner in find_owner_declarations(masked):
        types.add(owner.owner)
    return _TypeEnvironment(package, frozenset(types), imports, aliases,
                            type_counts, owner_scope, project_types,
                            wildcards,
                            _generic_parameter_names(masked))


def _resolve_type(typ: str, env: _TypeEnvironment, *, allow_vararg: bool = False) -> str:
    """Normalize and resolve every named component of a type expression."""
    # GR-07 hardening step C: ``suspend`` is a modifier of FUNCTION TYPES, not
    # a type name, and the closed signature grammar has no spelling for it
    # (``normalize_type_text`` rejects every ``suspend ...`` form).  The
    # modifier is therefore stripped before normalization and NOT re-attached:
    # ``suspend () -> T`` resolves to the bare function type.  This is honest
    # within the closed world -- Room DAO signatures can never carry suspend
    # function-type parameters (the same grammar governs both sides), so no
    # cross-side identity mismatch is introduced, while real Kotlin files
    # whose helpers take ``suspend () -> T`` blocks stop dying as BAD_TYPE.
    suspend_match = re.match(r"suspend\s+", typ)
    if suspend_match:
        typ = typ[suspend_match.end():]
    try:
        normalized = normalize_type_text(_normalize_closed_type_text(typ), allow_vararg=allow_vararg)
    except SignatureError as error:
        _fail_signature(error)
    vararg = normalized.startswith("vararg ")
    body = normalized[7:] if vararg else normalized

    def resolve_atom(name: str, seen: set[str]) -> str:
        if "." in name:
            if name in env.types:
                if env.type_counts.get(name, 0) != 1:
                    _fail("TYPE_UNRESOLVED")
                return name
            if name in _BUILTINS:
                return name
            # Imported symbols may be referenced by their full spelling.
            if name in env.imports.values():
                return name
            # GR-07 step A: an exact fully-qualified spelling of a real
            # project type is concrete even without a same-file import.
            # Step C extends this to NESTED project types (``Outer.Inner``):
            # the index's qualified set carries them, its simple-name map
            # deliberately does not.
            if env.project_types is not None and name in env.project_types.qualified:
                return name
            # GR-07 step C: member references on a RESOLVABLE root.
            # ``Outer.Mode`` (imported Outer), ``Owner.Inner`` (same-file
            # owner), and ``Map.Entry`` (builtin root) are relative spellings
            # of concrete types.  The head resolves through the ordinary
            # simple-name rules; every further segment must be verifiable in
            # the same-file type set or the project index -- except members
            # of BUILTIN roots, whose default-imported nesting
            # (kotlin.collections.Map.Entry) lies outside source visibility
            # and is accepted as spelled (evidence: Map.Entry<String,
            # CacheEntry> x1 in the activated-scan residual probe).
            head, _, rest = name.partition(".")
            if rest:
                try:
                    root = resolve_atom(head, seen)
                except ParserError:
                    root = None
                if root is not None:
                    if root in _BUILTINS:
                        return name
                    if root.split(".", 1)[0] in _EXTERNAL_TYPE_ROOTS:
                        # GR-07 step C: external-rooted member chains resolve
                        # through an IMPORTED head (``import android.graphics.
                        # BitmapFactory`` then ``BitmapFactory.Options``).
                        # Platform API surfaces are outside the project index,
                        # so the canonicalized spelling is accepted as
                        # concrete (evidence: BitmapFactory.Options x1).
                        return root + "." + rest
                    current = root
                    verified = True
                    for segment in rest.split("."):
                        candidate = current + "." + segment
                        if candidate in env.types or (
                            env.project_types is not None
                            and candidate in env.project_types.qualified
                        ):
                            current = candidate
                        else:
                            verified = False
                            break
                    if verified:
                        return current
            # GR-07 step C, closed external-platform rule: a dotted spelling
            # whose ROOT is one of the five platform packages (java/javax/
            # kotlin/android/androidx) is an external SDK type no project
            # declaration can share.  Evidence: androidx.sqlite.db.
            # SupportSQLiteDatabase x144 and ~25 further android/java FQCN
            # parameter spellings in the activated-scan residual probe.  A
            # misspelled external FQCN resolves here but keeps its exact
            # spelling for every downstream comparison, so it can never match
            # a real authorization target silently.
            if name.split(".", 1)[0] in _EXTERNAL_TYPE_ROOTS:
                return name
            _fail("TYPE_UNRESOLVED")
        # Resolution is deliberately ordered: exact qualified spelling,
        # lexical/nested owner scope, same package, imports, then builtins.
        owner_scope = env.owner_scope
        nested_candidates = []
        if owner_scope:
            scope = owner_scope
            while "." in scope:
                candidate = scope + "." + name
                if candidate in env.types:
                    nested_candidates = [candidate]
                    break
                scope = scope.rsplit(".", 1)[0]
        if nested_candidates:
            candidates = nested_candidates
        elif env.package and env.package + "." + name in env.types:
            candidates = [env.package + "." + name]
        else:
            # An unimported type from another package is not in scope.  Do
            # not turn the closed-world inventory into a nondeterministic
            # simple-name search.
            candidates = []
        if candidates:
            if len(candidates) != 1 or any(env.type_counts.get(candidate, 0) != 1 for candidate in candidates):
                _fail("TYPE_UNRESOLVED")
            return candidates[0]
        target = env.imports.get(name)
        if name in env.imports and target is None:
            _fail("TYPE_UNRESOLVED")
        if target:
            return target
        target = env.aliases.get(name)
        if name in env.aliases and target is None:
            _fail("TYPE_UNRESOLVED")
        if target is not None:
            if name in seen:
                _fail("TYPE_UNRESOLVED")
            return resolve_expr(target, seen | {name})
        if name in _BUILTINS:
            return name
        # GR-07 step C: a bare generic type variable declared in this file's
        # owner or fun headers (``class Repo<T>``, ``fun <T> run(...)``) is a
        # declared name, not a misspelled class.  Checked after every real
        # declaration source so it can never shadow one.
        if name in env.type_variables:
            return name
        # GR-07 step A, final fallback: the project-wide index.  Same-file
        # scopes, the file's package, imports, aliases, and builtins all
        # keep precedence, so every pre-index resolution is unchanged.  A
        # simple name declared in several packages is ambiguous debt and
        # fails closed -- never a guessed resolution.
        #
        # GR-07 step C refines the fallback with Kotlin import precedence:
        # explicit imports (handled above) > wildcard imports > same-package
        # declarations > other packages.  A wildcard-imported candidate
        # therefore BEATS a same-named declaration in another package
        # (``import com.example.model.*`` resolves ``PlannedExpense`` to the
        # model package even though the entity package declares the same
        # name), and only a wildcard collision between several wildcard
        # packages still fails closed.
        if env.project_types is not None:
            wildcard_candidates = set()
            for wildcard_package in env.wildcards:
                wildcard_candidate = wildcard_package + "." + name
                if wildcard_candidate in env.project_types.qualified:
                    wildcard_candidates.add(wildcard_candidate)
            if len(wildcard_candidates) > 1:
                _fail("TYPE_UNRESOLVED")
            if len(wildcard_candidates) == 1:
                return next(iter(wildcard_candidates))
            if env.package:
                same_package = env.package + "." + name
                if same_package in env.project_types.qualified:
                    return same_package
            project_candidates = set(env.project_types.by_simple_name.get(name, ()))
            if len(project_candidates) > 1:
                _fail("TYPE_UNRESOLVED")
            if len(project_candidates) == 1:
                return next(iter(project_candidates))
        _fail("TYPE_UNRESOLVED")

    def resolve_expr(expr: str, seen: set[str]) -> str:
        # Function types are split only at the top-level arrow.
        depth = 0
        arrow = -1
        for i, char in enumerate(expr):
            if char in "(<[": depth += 1
            elif char in ")>]": depth -= 1
            elif char == "-" and depth == 0 and expr[i:i + 2] == "->":
                arrow = i
                break
        if arrow >= 0:
            left, right = expr[:arrow].strip(), expr[arrow + 2:].strip()
            if not (left.startswith("(") and left.endswith(")")):
                _fail("TYPE_UNRESOLVED")
            args_text = left[1:-1].strip()
            args = [] if not args_text else _split_top(args_text)
            return "(" + ",".join(resolve_expr(x.strip(), seen) for x in args) + ")->" + resolve_expr(right, seen)
        suffix = ""
        while expr.endswith("?") or expr.endswith("[]"):
            if expr.endswith("?"):
                suffix = "?" + suffix; expr = expr[:-1].rstrip()
            else:
                suffix = "[]" + suffix; expr = expr[:-2].rstrip()
        if expr.startswith("(") and expr.endswith(")"):
            return "(" + resolve_expr(expr[1:-1].strip(), seen) + ")" + suffix
        lt = expr.find("<")
        if lt < 0:
            return resolve_atom(expr, seen) + suffix
        if not expr.endswith(">"):
            _fail("TYPE_UNRESOLVED")
        base = resolve_atom(expr[:lt].strip(), seen)
        args = _split_top(expr[lt + 1:-1])
        return base + "<" + ",".join(resolve_expr(x.strip(), seen) for x in args) + ">" + suffix

    resolved = resolve_expr(body, set())
    return ("vararg " if vararg else "") + resolved


def _pairs(text: str, a: int, b: str, limit: int = MAX_DEPTH) -> int:
    # GR-07 hardening step C: ``<``/``>`` are NOT tracked as a bracket pair
    # here.  This walker spans PARAMETER LISTS, whose default-value
    # expressions legally contain comparison operators (``if (rate > 0.0)``);
    # treating such a ``>`` as an angle closer failed the whole file with
    # MALFORMED_SOURCE and silently dropped it from the project type index.
    # Generic grammar inside each parameter stays validated later by
    # ``_split_top``/``_resolve_type``, so nothing malformed passes silently.
    close = {"(": ")", "{": "}", "[": "]"}
    stack: list[str] = []
    i = a
    while i < len(text):
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack (same rule as
            # ``declaration_scanner._header_opening``), so function-type
            # parameter spans such as ``(Int) -> String`` stay parseable
            # instead of failing closed as MALFORMED_SOURCE.
            i += 2
            continue
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")}]":
            if not stack or stack.pop() != c: _fail("MALFORMED_SOURCE")
            if not stack: return i
        i += 1
    _fail("MALFORMED_SOURCE")


def _body_end(text: str, start: int, limit: int = MAX_DEPTH) -> int:
    """Match a Kotlin block without treating comparison operators as angles."""
    stack: list[str] = []
    close = {"{": "}", "(": ")", "[": "]"}
    for i in range(start, len(text)):
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")]}" :
            if not stack or stack.pop() != c: _fail("MALFORMED_SOURCE")
            if not stack: return i
    _fail("MALFORMED_SOURCE")


def _owner_body(text: str, start: int, scope_end: int, limit: int = MAX_DEPTH) -> tuple[int | None, int]:
    """Find only a declaration header's body delimiter.

    In particular, never use a later brace belonging to a sibling declaration
    as the body of a bodyless class/object.  A ``fun`` keyword at the top
    level of the enclosing scope is such a sibling too: a bodyless owner must
    stop there instead of adopting the function's body brace (which would
    hide the function from its real owner's callable scan).
    """
    stack: list[str] = []
    # GR-07 hardening step C: no ``<``/``>`` bracket pairing (see ``_pairs``).
    # Owner headers legally contain comparison operators in constructor
    # default values; generic parameter lists need no tracking to locate the
    # body brace or the next sibling declaration.
    close = {"(": ")", "[": "]"}
    i = start
    while i < scope_end:
        c = text[i]
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow (e.g. a function-typed constructor
            # parameter such as ``(UiText) -> String``) is not a closing
            # angle bracket: skip its ``>`` without touching the delimiter
            # stack, exactly like ``_pairs``/``_header_body_start``.
            # Treating that ``>`` as an angle close failed the whole owner
            # walk with MALFORMED_SOURCE and poisoned every declaration in
            # the file.
            i += 2
            continue
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")]":
            if not stack or stack.pop() != c: _fail("MALFORMED_SOURCE")
        elif not stack:
            if c == "{": return i, i
            if c == "}": return None, i
            if re.match(r"(?:fun|class|object|interface)\b", text[i:]): return None, i
        i += 1
    if stack: _fail("MALFORMED_SOURCE")
    return None, scope_end


def _split_top(text: str, sep: str = ",") -> list[str]:
    result, start, stack = [], 0, []
    i = 0
    while i < len(text):
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack (same rule as
            # ``_pairs``/``_header_body_start``), so function-type parameter
            # spans such as ``(Int) -> String`` and generics containing them
            # split at real top-level commas instead of failing closed as
            # MALFORMED_SOURCE when the arrow's ``>`` reaches this scanner.
            i += 2
            continue
        c = text[i]
        if c in "(<[{":
            stack.append(c)
        elif c == ">":
            # GR-07 hardening step C: a ``>`` that does not close an open
            # generic is a comparison OPERATOR inside a default-value
            # expression (``= if (rate > 0.0) rate else null`` -- the real
            # ExportTransaction.kt poisoning shape) and is ignored instead of
            # failing the whole parameter list as MALFORMED_SOURCE.  An
            # UNCLOSED ``<`` stays structurally fatal below, so genuinely
            # broken angle pairing keeps its early sanitized rejection.
            if stack and stack[-1] == "<":
                stack.pop()
        elif c in ")]}":
            expected = {"(": ")", "[": "]", "{": "}"}
            if not stack or expected[stack[-1]] != c: _fail("MALFORMED_SOURCE")
            stack.pop()
        elif c == sep and not stack:
            result.append(text[start:i]); start = i + 1
        i += 1
    if stack: _fail("MALFORMED_SOURCE")
    result.append(text[start:])
    return result


def _header_body_start(text: str, start: int, scope_end: int, limit: int = MAX_DEPTH) -> tuple[int | None, int]:
    """Find a function body after a header, allowing multiline return types."""
    stack: list[str] = []
    # GR-07 hardening step C: no ``<``/``>`` bracket pairing (see ``_pairs``).
    # Headers legally contain comparison operators in default-value
    # expressions; return-type generics need no tracking to locate the body.
    close = {"(": ")", "[": "]"}
    i = start
    while i < scope_end:
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow (e.g. a function-typed return type such as
            # ``(Int) -> String``) is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack.
            i += 2
            continue
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")]":
            if not stack or stack.pop() != c:
                _fail("MALFORMED_SOURCE")
        elif not stack:
            if c in "={":
                return i, i
            if re.match(r"(?:fun|class|object|interface|enum|annotation)\b", text[i:]):
                return None, i
        i += 1
    if stack:
        _fail("MALFORMED_SOURCE")
    return None, scope_end


# GR-08d: expression-bodied members (``fun f(): T = <expr>``) keep their
# historical fail-closed ``UNSUPPORTED_EXPRESSION_BODY`` status -- they can
# never act as exactly-resolved braced candidates -- but the evidence
# verifier can only authorize their mutation when the EXPRESSION TEXT itself
# is captured and scannable.  The walker below bounds one expression body
# with the same semantics the declaration scanner uses for accessor
# expressions: balanced ``()``/``[]``/``{}`` spans keep the expression alive;
# a top-level ``;``, a fresh-line sibling declaration boundary, the owner
# scope's closing ``}``, or the scope end terminates it.  Structural
# malformation (stray ``)``/``]``, an unclosed bracket at the boundary)
# yields NO capture so the caller keeps the bodyless fail-closed shape
# instead of authorizing a truncated body.
_EXPRESSION_SIBLING_KEYWORD = re.compile(
    r"(?:fun|val|var|class|interface|object|enum|annotation|typealias|init)\b"
)
_EXPRESSION_SIBLING_MODIFIER = re.compile(
    r"(?:value|override|operator|suspend|inline|infix|tailrec|external|expect|actual|inner|"
    r"const|lateinit|vararg|noinline|crossinline|reified|"
    r"data|sealed|open|abstract|final|public|private|protected|internal|companion)\b"
)


def _at_fresh_line(masked: str, index: int) -> bool:
    """True when ``index`` is the first token of its source line."""
    cursor = index - 1
    while cursor >= 0 and masked[cursor] in " \t\r":
        cursor -= 1
    return cursor < 0 or masked[cursor] == "\n"


def _expression_starts_sibling(masked: str, index: int, scope_end: int) -> bool:
    """True when ``index`` starts the next sibling's declaration header.

    Fresh-line declaration keywords, annotations, and modifier chains
    (``private data class ...``) bound an expression body; ``object :`` opens
    an anonymous-object EXPRESSION and never bounds one.  The owner scope's
    closing ``}`` (or the scope end) is always a boundary.
    """
    if index >= scope_end or masked[index] == "}":
        return True
    if not _at_fresh_line(masked, index):
        return False
    if masked[index] == "@":
        return True
    if masked.startswith("object", index):
        cursor = index + len("object")
        while cursor < scope_end and masked[cursor] in " \t\r\n":
            cursor += 1
        if cursor < scope_end and masked[cursor] == ":":
            return False
    if _EXPRESSION_SIBLING_KEYWORD.match(masked, index) is not None:
        return True
    modifier = _EXPRESSION_SIBLING_MODIFIER.match(masked, index)
    if modifier is None:
        return False
    cursor = modifier.end()
    while cursor < scope_end:
        while cursor < scope_end and masked[cursor] in " \t\r\n":
            cursor += 1
        if cursor >= scope_end:
            return False
        if masked[cursor] == "@":
            return True
        if _EXPRESSION_SIBLING_KEYWORD.match(masked, cursor) is not None:
            return True
        modifier = _EXPRESSION_SIBLING_MODIFIER.match(masked, cursor)
        if modifier is None:
            return False
        cursor = modifier.end()
    return False


def _expression_body_end(masked: str, start: int, scope_end: int) -> int | None:
    """Exclusive end of one fun expression body after ``=``, or ``None``.

    ``None`` means "no capturable expression": an empty expression (the next
    token is already a sibling boundary) or structurally malformed text.
    """
    if start >= scope_end or _expression_starts_sibling(masked, start, scope_end):
        return None
    stack: list[str] = []
    closers = {"(": ")", "[": "]", "{": "}"}
    index = start
    while index < scope_end:
        char = masked[index]
        if char == "-" and index + 1 < scope_end and masked[index + 1] == ">":
            # A Kotlin ``->`` arrow is expression text, never a delimiter.
            index += 2
            continue
        if char in closers:
            stack.append(closers[char])
        elif char in ")]}":
            if not stack:
                if char == "}":
                    return index
                return None
            if stack.pop() != char:
                return None
        elif not stack:
            if char == ";":
                return index
            if char == "\n":
                cursor = index + 1
                while cursor < scope_end and masked[cursor].isspace():
                    cursor += 1
                if cursor >= scope_end:
                    return scope_end
                if _expression_starts_sibling(masked, cursor, scope_end):
                    return index
        index += 1
    return None


def find_owner_declarations(text: str) -> tuple[OwnerDeclaration, ...]:
    masked = mask_kotlin_source(text)
    package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)", masked)
    package = package_match.group(1) if package_match else ""
    found: list[OwnerDeclaration] = []
    for m in _OWNER.finditer(masked):
        brace, header_end = _owner_body(masked, m.end(), len(masked))
        parent = next((x.owner for x in reversed(found) if x.body_start < m.start() < x.body_end), "")
        owner = (parent + "." if parent else (package + "." if package else "")) + m.group(2)
        if brace is None:
            # Unsupported, exact declaration only: this scope is empty, so a
            # following sibling can never be swallowed by it.
            found.append(OwnerDeclaration(owner, m.group(2), m.start(), header_end,
                                          header_end, header_end, "SIGNATURE_UNSUPPORTED"))
        else:
            end = _body_end(masked, brace)
            found.append(OwnerDeclaration(owner, m.group(2), m.start(), end + 1, brace + 1, end))
    return tuple(found)


def _callable_body_ranges(text: str, scope_start: int, scope_end: int) -> tuple[tuple[int, int, int], ...]:
    """Return ``(declaration_start, body_start, body_end)`` for braced funs.

    This is intentionally a structural pass over masked source.  A ``fun``
    found while walking an owner's body may be a local declaration rather than
    an owner member; its enclosing method range is needed before signature
    parsing can safely consider it.
    """
    ranges: list[tuple[int, int, int]] = []
    for fm in _FUN.finditer(text, scope_start, scope_end):
        p = text.find("(", fm.end(), scope_end)
        if p < 0:
            _fail("MALFORMED_SOURCE")
        pend = _pairs(text, p, ")")
        q = pend + 1
        while q < scope_end and text[q].isspace():
            q += 1
        if q < scope_end and text[q] == ":":
            q += 1
        body_start, boundary = _header_body_start(text, q, scope_end)
        q = body_start if body_start is not None else boundary
        if q < scope_end and text[q] == "{":
            ranges.append((fm.start(), q, _body_end(text, q) + 1))
    return tuple(ranges)


def _type_before(text: str, pos: int) -> tuple[str | None, int]:
    s = text[:pos].rstrip()
    if not s: return None, pos
    # Receiver ends immediately before the function name and may contain a dot.
    m = re.search(r"(%s(?:\s*\.\s*%s)*(?:\s*<[^<>]*>)?(?:\s*\?)?(?:\s*\[\])*)\s*\.\s*$" % (_ID, _ID), s)
    if not m: return None, pos
    return m.group(1).replace(" ", ""), m.start()


def _raw_parameter_type(param: str) -> str:
    """Extract one parameter's raw type text exactly as written (no resolution)."""
    if ":" not in param: _fail("SIGNATURE_UNSUPPORTED")
    declaration, typ = param.split(":", 1)
    vararg_prefix = bool(re.match(r"\s*vararg\b", declaration))
    typ = typ.split("=", 1)[0].strip()
    if vararg_prefix:
        typ = "vararg " + typ
    return typ


def _normalize_retaining_suspend(typ: str, *, allow_vararg: bool = False) -> str:
    """Grammar-normalize ``typ`` after stripping a leading ``suspend`` modifier.

    ``suspend`` modifies FUNCTION TYPES (``suspend () -> T``); the closed
    signature grammar has no spelling for it, so the modifier is stripped
    before normalization and not re-attached -- the retained identity is the
    bare function type (see :func:`_resolve_type` for why that is honest
    within this closed world).  Used by the tolerant retention path, which
    keeps grammar-normalized source spellings without resolving them.
    """
    suspend_match = re.match(r"suspend\s+", typ)
    if suspend_match:
        return normalize_type_text(
            _normalize_closed_type_text(typ[suspend_match.end():]), allow_vararg=allow_vararg
        )
    return normalize_type_text(_normalize_closed_type_text(typ), allow_vararg=allow_vararg)


def find_callable_declarations(
    text: str,
    owner: OwnerDeclaration | str,
    *,
    tolerate_unresolved_types: bool = False,
    project_types: ProjectTypeIndex | None = None,
) -> tuple[CallableDeclaration, ...]:
    """Discover the member ``fun`` declarations of one owner, fail-closed.

    With the default ``tolerate_unresolved_types=False``, a parameter or
    receiver type the closed-world resolver cannot resolve aborts the whole
    discovery with ``ParserError("TYPE_UNRESOLVED")`` -- the exact behavior
    the scanner and evidence verifiers depend on.

    ``project_types`` (GR-07 hardening step A) optionally supplies the
    project-wide type index built once per scan from the declared
    production roots.  It is the LAST resolution fallback, after same-file
    scopes, the file's package, imports, aliases, and builtins: a unique
    simple-name match resolves to its package-qualified FQCN, an ambiguous
    simple name (declared in several packages) still fails closed as
    ``TYPE_UNRESOLVED``, and ``None`` keeps the pure single-file closed
    world byte-for-byte.

    With ``tolerate_unresolved_types=True`` (PR-GR-05 Slice 3 narrow
    repair) ONLY that type-resolution family becomes non-fatal, and only
    per declaration: the affected declaration is retained with status
    ``"TYPE_UNRESOLVED"`` and its signature kept as grammar-normalized
    simple names exactly as written (never resolved, never fabricated), so
    discovery continues with the remaining declarations.  Retained
    declarations can never act as exactly-resolved candidates downstream
    (``resolve_callable`` reports ``SIGNATURE_UNSUPPORTED`` for them).
    Every other failure family (masking, structure, signature grammar)
    stays fatal in both modes.
    """
    masked = mask_kotlin_source(text)
    owner_name = owner.owner if isinstance(owner, OwnerDeclaration) else owner
    environment = _type_environment(masked, owner_name, project_types)
    scope_start = owner.body_start if isinstance(owner, OwnerDeclaration) else 0
    scope_end = owner.body_end if isinstance(owner, OwnerDeclaration) else len(masked)
    owners = find_owner_declarations(text)
    body_ranges = _callable_body_ranges(masked, scope_start, scope_end)
    result: list[CallableDeclaration] = []
    for fm in _FUN.finditer(masked, scope_start, scope_end):
        if fm.start() >= scope_end: break
        # Ignore functions belonging to a nested owner when scanning this owner.
        nested = [x for x in owners if scope_start <= x.start_offset < scope_end and x.start_offset < fm.start() < x.end_offset]
        if nested and nested[-1].owner != owner_name: continue
        # A function declaration inside a method/block is local Kotlin code,
        # not a callable member of this owner.  Nested class/object scopes are
        # the one supported exception: their members are discovered by their
        # own owner scan, even when the nested owner itself is local to a
        # method.  Do not let an enclosing method from that outer scope hide
        # the nested owner's direct members.
        enclosing = [r for r in body_ranges if r[0] < fm.start() < r[2]]
        if enclosing:
            nested_owner_scope = (
                isinstance(owner, OwnerDeclaration)
                and any(r[0] < scope_start < r[2] for r in body_ranges)
            )
            if not nested_owner_scope or any(r[0] >= scope_start for r in enclosing):
                continue
        head = masked[scope_start:fm.start()]
        p = masked.find("(", fm.end(), scope_end)
        if p < 0: _fail("MALFORMED_SOURCE")
        prefix = masked[fm.end():p].strip()
        prefix = re.sub(r"^<[^<>]*>\s*", "", prefix)
        nm = re.search(r"(%s)\s*$" % _ID, prefix)
        if not nm: _fail("MALFORMED_SOURCE")
        name = nm.group(1)
        before_name = prefix[:nm.start()].rstrip()
        receiver_text = None
        if before_name:
            if not before_name.endswith("."): _fail("SIGNATURE_UNSUPPORTED")
            receiver_text = before_name[:-1].strip()
            if not receiver_text: _fail("SIGNATURE_UNSUPPORTED")
        name_end = p
        pend = _pairs(masked, p, ")")
        params = [] if not masked[p + 1:pend].strip() else _split_top(masked[p + 1:pend])
        types: list[str] = []
        unresolved_types = False
        try:
            for param in params:
                types.append(_resolve_type(_raw_parameter_type(param), environment, allow_vararg=True))
            receiver = receiver_text
            if receiver is not None:
                receiver = _resolve_type(receiver, environment)
        except ParserError as error:
            # Narrow repair (PR-GR-05 Slice 3): only the closed-world
            # type-resolution family is tolerable, and only behind the
            # explicit flag.  The raise happens mid-construction (per
            # parameter / on the receiver), so the declaration is rebuilt
            # from its raw parameter texts as grammar-normalized simple
            # names -- faithful source spelling, never a resolved or
            # fabricated identity -- and discovery continues after this
            # one fun.
            if not tolerate_unresolved_types or error.code != "TYPE_UNRESOLVED":
                raise
            try:
                types = [
                    _normalize_retaining_suspend(_raw_parameter_type(param), allow_vararg=True)
                    for param in params
                ]
                receiver = (
                    normalize_type_text(receiver_text)
                    if receiver_text is not None
                    else None
                )
            except SignatureError as signature_error:
                # Grammar validation of the retained simple names is NOT
                # part of the tolerated family: stay fatal, exactly as the
                # strict pass would have been when reaching that parameter.
                _fail_signature(signature_error)
            unresolved_types = True
        try:
            sig = FunctionSignature(canonical_source_path("app/src/main/unknown.kt"), owner_name, name, receiver, tuple(types))
        except SignatureError as error: _fail_signature(error)
        q = pend + 1
        while q < scope_end and masked[q].isspace(): q += 1
        if q < scope_end and masked[q] == ":":
            q += 1
        body_start, boundary = _header_body_start(masked, q, scope_end)
        q = body_start if body_start is not None else boundary
        body = None
        end = q
        if q < scope_end and masked[q] == "{":
            end = _body_end(masked, q) + 1
            body = text[q:end]
        status = "RESOLVED_EXACTLY"
        if q < scope_end and masked[q] == "=":
            # GR-08d: the status stays UNSUPPORTED_EXPRESSION_BODY (an
            # expression-bodied declaration can never act as an
            # exactly-resolved braced candidate), but the full expression
            # text is captured as the body so the evidence verifier can scan
            # it for mutations.  A non-capturable expression (empty or
            # structurally malformed) keeps the bodyless fail-closed shape.
            status = "UNSUPPORTED_EXPRESSION_BODY"
            expr_start = q + 1
            while expr_start < scope_end and masked[expr_start].isspace():
                expr_start += 1
            expr_end = _expression_body_end(masked, expr_start, scope_end)
            if expr_end is not None:
                while expr_end > expr_start and masked[expr_end - 1].isspace():
                    expr_end -= 1
                if expr_end > expr_start:
                    end = expr_end
                    body = text[expr_start:expr_end]
        if unresolved_types:
            # Tolerant retention names the type-resolution fact -- the
            # exact failure strict mode died on for this declaration.
            # Either status keeps the declaration out of exact resolution;
            # this one is the specific debt this slice exposes.
            status = "TYPE_UNRESOLVED"
        result.append(CallableDeclaration(sig, owner_name, fm.start(), end, body, status))
    return tuple(result)


def parse_kotlin_file(path: str | Path) -> tuple[CallableDeclaration, ...]:
    try:
        canonical = canonical_source_path(path)
        # Validate the caller-supplied path before touching the filesystem.
        # This is intentionally separate from Path.read_text so rejected
        # absolute, backslash, and traversal paths cannot be opened.
        text = Path(path).read_text(encoding="utf-8")
        owners = find_owner_declarations(text)
        out: list[CallableDeclaration] = []
        for o in owners:
            for d in find_callable_declarations(text, o):
                sig = FunctionSignature(canonical, d.owner, d.signature.function_name, d.signature.receiver, d.signature.parameter_types)
                out.append(CallableDeclaration(sig, d.owner, d.start_offset, d.end_offset, d.body, d.status))
        return tuple(out)
    except ParserError:
        raise
    except Exception:
        _fail()


def resolve_callable(declarations: Iterable[CallableDeclaration], owner: str, name: str, receiver: str | None, parameter_types: Iterable[str]) -> str:
    try:
        params = tuple(normalize_type_text(x, allow_vararg=True) for x in parameter_types)
        recv = normalize_type_text(receiver) if receiver is not None else None
    except SignatureError as error:
        return error.code if error.code in _SIGNATURE_ERROR_CODES else "PARSER_ERROR"
    except TypeError:
        return "NOT_TEXT"
    matches = [d for d in declarations if d.owner == owner and d.signature.function_name == name and d.signature.receiver == recv and d.signature.parameter_types == params]
    if any(d.status != "RESOLVED_EXACTLY" for d in matches): return "SIGNATURE_UNSUPPORTED"
    if len(matches) == 1: return "RESOLVED_EXACTLY"
    if len(matches) > 1: return "AMBIGUOUS_OVERLOAD"
    if any(d.owner == owner and d.signature.function_name == name for d in declarations): return "SIGNATURE_UNSUPPORTED"
    return "METHOD_MISSING"
