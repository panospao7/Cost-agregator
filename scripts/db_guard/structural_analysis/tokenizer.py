"""Shadow-only conservative Kotlin statement-boundary tokenizer.

Operates only on masked text (comments/strings blanked, offsets preserved).
Recognizes a small supported subset; anything else is an explicit
unsupported finding. Spans and kinds only, no raw source retained.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

from .diagnostics import DIAGNOSTIC_CODES
from .model import BarrierMarkerKind, SourceSpan

__all__ = [
    "RegionKind",
    "ParsedRegion",
    "UnsupportedFinding",
    "CallableBodyParse",
    "parse_callable_body",
]

_ALLOWED_CODES = (
    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
    "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
    "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
    "DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED",
)

_ID = r"[A-Za-z_][A-Za-z0-9_]*"
_RE_FUN = re.compile(r"\bfun\b")
_RE_OBJECT = re.compile(r"\bobject\s*(?::|\{)")
_RE_COROUTINE = re.compile(
    r"(?:^|[^\w$.])(?:launch|async|withContext|runBlocking)\s*[\(\{]"
)
_RE_BARRIER_SCOPE = re.compile(
    r"\bwriteBarrier\s*\.\s*runWrite\s*\{"
)
_RE_BARRIER_CHECK = re.compile(
    r"\bwriteBarrier\s*\.\s*checkWritesAllowed\s*\("
)
_RE_WORKER_GUARD = re.compile(
    r"\b[A-Za-z_][A-Za-z0-9_]*\s*\.\s*"
    r"(?P<method>runGuardedWithContext|runGuarded)\s*(?:\([^()]*\))?\s*\{"
)
_RE_LIKE_BARRIER = re.compile(
    r"\b\w+\s*\.\s*(?:runWrite|checkWritesAllowed)\s*[\(\{]"
)
_RE_RETURN_LABEL = re.compile(r"\breturn\s*@")
_RE_BREAK_CONT_LABEL = re.compile(r"\b(?:break|continue)\s*@")
_RE_IF = re.compile(r"if\s*\(")
_RE_WHEN = re.compile(r"when\s*\(")
_RE_WHILE = re.compile(r"while\s*\(")
_RE_FOR = re.compile(r"for\s*\(")
_RE_TRY = re.compile(r"try\s*\{")
_RE_CATCH = re.compile(r"catch\s*\(")
_RE_FINALLY = re.compile(r"finally\s*\{")
_RE_DO = re.compile(r"do\s*\{")
_RE_ELSE = re.compile(r"else\b")
_RE_ACCESSOR = re.compile(r"(?:^|[^\w])(?:get|set)\s*\(")
_RE_JUMP = re.compile(r"^(return|throw|break|continue)(?![\w])")

_CONT_END = set("+-*/%,:?.&|<>!(`[")
_WS = " \t\r\n\x0b\x0c"


class RegionKind(str, Enum):
    STATEMENT = "STATEMENT"
    BLOCK = "BLOCK"
    IF = "IF"
    WHEN = "WHEN"
    WHEN_BRANCH = "WHEN_BRANCH"
    LOOP = "LOOP"
    TRY = "TRY"
    CATCH = "CATCH"
    FINALLY = "FINALLY"
    RETURN = "RETURN"
    THROW = "THROW"
    BREAK = "BREAK"
    CONTINUE = "CONTINUE"
    ACCESSOR = "ACCESSOR"
    BARRIER_SCOPE = "BARRIER_SCOPE"
    DIRECT_CHECK = "DIRECT_CHECK"


@dataclass(frozen=True)
class ParsedRegion:
    kind: RegionKind
    span: SourceSpan
    children: tuple[ParsedRegion, ...] = ()
    barrier: BarrierMarkerKind | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.kind, RegionKind):
            raise TypeError("ParsedRegion.kind must be a RegionKind")
        if not isinstance(self.span, SourceSpan):
            raise TypeError("ParsedRegion.span must be a SourceSpan")
        if not isinstance(self.children, (tuple, list)):
            raise TypeError("ParsedRegion.children must be a tuple of ParsedRegion")
        for child in self.children:
            if not isinstance(child, ParsedRegion):
                raise TypeError("ParsedRegion.children must be a tuple of ParsedRegion")
        if self.barrier is not None and not isinstance(self.barrier, BarrierMarkerKind):
            raise TypeError("ParsedRegion.barrier must be a BarrierMarkerKind or None")
        object.__setattr__(self, "children", tuple(self.children))


@dataclass(frozen=True)
class UnsupportedFinding:
    code: str
    span: SourceSpan
    reason: str

    def __post_init__(self) -> None:
        if self.code not in DIAGNOSTIC_CODES or self.code not in _ALLOWED_CODES:
            raise ValueError("UnsupportedFinding.code must be an allowed code: %r" % (self.code,))
        if not isinstance(self.span, SourceSpan):
            raise TypeError("UnsupportedFinding.span must be a SourceSpan")
        if not isinstance(self.reason, str) or not self.reason:
            raise TypeError("UnsupportedFinding.reason must be a non-empty string")


@dataclass(frozen=True)
class CallableBodyParse:
    body_span: SourceSpan
    regions: tuple[ParsedRegion, ...]
    unsupported: tuple[UnsupportedFinding, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.body_span, SourceSpan):
            raise TypeError("CallableBodyParse.body_span must be a SourceSpan")
        if not isinstance(self.regions, (tuple, list)):
            raise TypeError("CallableBodyParse.regions must be a tuple of ParsedRegion")
        if not isinstance(self.unsupported, (tuple, list)):
            raise TypeError("CallableBodyParse.unsupported must be a tuple of UnsupportedFinding")
        for region in self.regions:
            if not isinstance(region, ParsedRegion):
                raise TypeError("CallableBodyParse.regions must be a tuple of ParsedRegion")
        for finding in self.unsupported:
            if not isinstance(finding, UnsupportedFinding):
                raise TypeError(
                    "CallableBodyParse.unsupported must be a tuple of UnsupportedFinding"
                )
        object.__setattr__(
            self, "regions", tuple(sorted(self.regions, key=lambda r: (r.span.start, r.span.end)))
        )
        object.__setattr__(
            self,
            "unsupported",
            tuple(sorted(self.unsupported, key=lambda f: (f.span.start, f.span.end, f.reason))),
        )

    @property
    def is_supported(self) -> bool:
        return not self.unsupported


def _line_starts(text: str) -> list[int]:
    starts = [0]
    for match in re.finditer(r"\n", text):
        starts.append(match.end())
    return starts


class _Cursor:
    def __init__(self, text: str) -> None:
        self.text = text
        self.starts = _line_starts(text)
        self.findings: list[UnsupportedFinding] = []

    def span(self, start: int, end: int) -> SourceSpan:
        import bisect

        if end < start:
            end = start
        idx = bisect.bisect_right(self.starts, start) - 1
        line = idx + 1
        column = start - self.starts[idx] + 1
        return SourceSpan(start=start, end=end, line=line, column=column)

    def fail(self, code: str, start: int, end: int, reason: str) -> None:
        self.findings.append(UnsupportedFinding(code=code, span=self.span(start, end), reason=reason))

    def non_ws(self, pos: int, end: int, forward: bool = True) -> int:
        if forward:
            while pos < end and self.text[pos] in _WS:
                pos += 1
            return pos
        pos -= 1
        while pos >= end and self.text[pos] in _WS:
            pos -= 1
        return pos + 1


def _match_forward(text: str, opening: int, end: int) -> int:
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack = [text[opening]]
    i = opening + 1
    while i < end:
        ch = text[i]
        if ch in pairs:
            stack.append(ch)
        elif ch in ")]}":
            if not stack or pairs[stack[-1]] != ch:
                return -1
            stack.pop()
            if not stack:
                return i + 1
        i += 1
    return -1


def _at_clause(text: str, pos: int, end: int) -> bool:
    """True when ``pos`` starts a clause keyword that continues the enclosing
    construct (else/catch/finally/while) or an accessor (get/set): a ``}``
    closing to depth 0 right before one of these ends the statement part so
    the clause parser sees the clause as its own part."""
    for word in ("else", "catch", "finally", "while", "get", "set"):
        if text.startswith(word, pos):
            after = pos + len(word)
            if after >= end or not (text[after].isalnum() or text[after] == "_"):
                return True
    return False


def _split_statements(cur: _Cursor, start: int, end: int) -> list[tuple[int, int]] | None:
    parts: list[tuple[int, int]] = []
    depth = 0
    stmt_start: int | None = None
    i = start
    text = cur.text
    while i < end:
        ch = text[i]
        if ch not in _WS and stmt_start is None:
            stmt_start = i
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                bad = stmt_start if stmt_start is not None else i
                cur.fail(
                    "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                    bad,
                    i + 1,
                    "stray-closing-delimiter",
                )
                return None
            if depth == 0 and ch == "}" and stmt_start is not None:
                nxt = cur.non_ws(i + 1, end)
                if nxt < end and _at_clause(text, nxt, end):
                    parts.append((stmt_start, i + 1))
                    stmt_start = None
        if depth == 0 and stmt_start is not None:
            if ch == ";":
                parts.append((stmt_start, i))
                stmt_start = None
            elif ch == "\n":
                prev = text[stmt_start:i].rstrip(_WS)
                nxt = cur.non_ws(i + 1, end)
                cont = bool(prev) and prev[-1] in _CONT_END
                dot_cont = nxt < end and text[nxt] == "."
                if not cont and not dot_cont:
                    parts.append((stmt_start, i))
                    stmt_start = None
        i += 1
    if depth != 0:
        bad = stmt_start if stmt_start is not None else start
        cur.fail(
            "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED", bad, end, "unbalanced-delimiter"
        )
        return None
    if stmt_start is not None:
        tail = text[stmt_start:end]
        if tail.strip(_WS):
            parts.append((stmt_start, end))
    return parts


def _strip(stmt: str) -> str:
    return stmt.strip(_WS)


def _leading_kw(stmt: str, word: str) -> bool:
    if not stmt.startswith(word):
        return False
    rest = stmt[len(word) :]
    return not rest or not (rest[0].isalnum() or rest[0] == "_")


def _parse_block(
    cur: _Cursor, abs_start: int, rel_open: int, abs_end: int, in_lambda: bool
) -> tuple[ParsedRegion | None, list[ParsedRegion]]:
    close = _match_forward(cur.text, abs_start + rel_open, abs_end)
    if close < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            abs_start + rel_open,
            abs_end,
            "unbalanced-brace",
        )
        return None, []
    inner = _parse_sequence(cur, abs_start + rel_open + 1, close - 1, in_lambda)
    region = ParsedRegion(
        kind=RegionKind.BLOCK, span=cur.span(abs_start, close), children=tuple(inner)
    )
    return region, [region]


def _parse_sequence(
    cur: _Cursor, start: int, end: int, in_lambda: bool
) -> list[ParsedRegion]:
    out: list[ParsedRegion] = []
    parts = _split_statements(cur, start, end)
    if parts is None:
        return out
    idx = 0
    while idx < len(parts):
        stmt_s, stmt_e = parts[idx]
        stmt = cur.text[stmt_s:stmt_e]
        stripped = _strip(stmt)
        if not stripped:
            idx += 1
            continue
        lead_ws = len(stmt) - len(stmt.lstrip(_WS))
        base = stmt_s + lead_ws
        kind_region: ParsedRegion | None = None

        if stripped.startswith("{"):
            close = _match_forward(cur.text, base, stmt_e)
            if close != stmt_e and cur.text[close:stmt_e].strip(_WS):
                cur.fail(
                    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                    base,
                    stmt_e,
                    "unknown-construct",
                )
                idx += 1
                continue
            if close < 0:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                    base,
                    stmt_e,
                    "unbalanced-brace",
                )
                idx += 1
                continue
            inner = _parse_sequence(cur, base + 1, close - 1, in_lambda)
            kind_region = ParsedRegion(
                kind=RegionKind.BLOCK,
                span=cur.span(base, close),
                children=tuple(inner),
            )
            out.append(kind_region)
            idx += 1
            continue

        if _RE_FUN.search(stripped):
            m = _RE_FUN.search(stripped)
            assert m is not None
            cur.fail(
                "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                base + m.start(),
                stmt_e,
                "local-function",
            )
            idx += 1
            continue

        if _RE_OBJECT.search(stripped):
            m = _RE_OBJECT.search(stripped)
            assert m is not None
            cur.fail(
                "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                base + m.start(),
                stmt_e,
                "anonymous-object",
            )
            idx += 1
            continue

        if _leading_kw(stripped, "if"):
            region, consumed = _parse_if(cur, base, stmt_e, parts, idx, in_lambda)
            if region is not None:
                out.append(region)
            idx = consumed
            continue

        if _leading_kw(stripped, "when"):
            region = _parse_when(cur, base, stmt_e, in_lambda)
            if region is not None:
                out.append(region)
            idx += 1
            continue

        if _leading_kw(stripped, "while") or _leading_kw(
            stripped, "for"
        ) or _leading_kw(stripped, "do"):
            region = _parse_loop(cur, base, stmt_e, parts, idx, in_lambda)
            if region is not None:
                out.append(region)
                if isinstance(region, _DoWhile):
                    idx = region.consumed_until
                elif region.span.end > stmt_e:
                    idx = _advance_past(parts, idx, region.span.end)
                else:
                    idx += 1
            else:
                idx += 1
            continue

        if _leading_kw(stripped, "try"):
            region, consumed = _parse_try(cur, base, stmt_e, parts, idx, in_lambda)
            if region is not None:
                out.append(region)
            idx = consumed
            continue

        if _leading_kw(stripped, "else") or _leading_kw(
            stripped, "catch"
        ) or _leading_kw(stripped, "finally"):
            cur.fail(
                "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED"
                if _leading_kw(stripped, "else")
                else "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
                base,
                stmt_e,
                "dangling-clause",
            )
            idx += 1
            continue

        jump = _RE_JUMP.match(stripped)
        if jump:
            word = jump.group(1)
            rest = stripped[jump.end() :]
            if "@" in rest.split("//")[0] if False else "@" in rest:
                code = (
                    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
                    if word == "return"
                    else "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED"
                )
                cur.fail(code, base, stmt_e, "labelled-%s" % word)
                idx += 1
                continue
            if word == "return" and in_lambda:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                    base,
                    stmt_e,
                    "non-local-return",
                )
                idx += 1
                continue
            if word in ("break", "continue") and rest.strip(_WS):
                cur.fail(
                    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                    base,
                    stmt_e,
                    "labelled-%s" % word,
                )
                idx += 1
                continue
            kind = {
                "return": RegionKind.RETURN,
                "throw": RegionKind.THROW,
                "break": RegionKind.BREAK,
                "continue": RegionKind.CONTINUE,
            }[word]
            out.append(ParsedRegion(kind=kind, span=cur.span(base, stmt_e)))
            idx += 1
            continue

        acc = _RE_ACCESSOR.match(stripped)
        if acc and ("get" in stripped[:8] or "set" in stripped[:8]):
            region = _parse_accessor(cur, base, stmt_e, in_lambda)
            if region is not None:
                out.append(region)
            idx += 1
            continue

        if _RE_BARRIER_SCOPE.search(stripped):
            m = _RE_BARRIER_SCOPE.search(stripped)
            assert m is not None
            if "{" in stripped[: m.start()]:
                # A lambda opened earlier in the statement (e.g. a callback
                # receiving the barrier scope) escapes before the barrier —
                # never a barrier candidate.
                cur.fail(
                    "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
                    base,
                    stmt_e,
                    "lambda-before-barrier-scope",
                )
                idx += 1
                continue
            brace_rel = stripped.find("{", m.start())
            brace_abs = base + brace_rel
            close = _match_forward(cur.text, brace_abs, stmt_e)
            tail = cur.text[close:stmt_e].strip(_WS) if close > 0 else ""
            if close < 0 or tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                    base,
                    stmt_e,
                    "unknown-construct",
                )
                idx += 1
                continue
            inner = _parse_sequence(cur, brace_abs + 1, close - 1, True)
            out.append(
                ParsedRegion(
                    kind=RegionKind.BARRIER_SCOPE,
                    span=cur.span(base, close),
                    children=tuple(inner),
                    barrier=BarrierMarkerKind.DIRECT_SCOPE,
                )
            )
            idx += 1
            continue

        if _RE_WORKER_GUARD.search(stripped):
            # Canonical worker-guard-shaped scope: a syntactic CANDIDATE only,
            # with no synchronicity or mediation assumption (GR-13 owns any
            # proof).  Same conservatism as the writeBarrier scope branch.
            m = _RE_WORKER_GUARD.search(stripped)
            assert m is not None
            brace_rel = stripped.find("{", m.start())
            brace_abs = base + brace_rel
            close = _match_forward(cur.text, brace_abs, stmt_e)
            tail = cur.text[close:stmt_e].strip(_WS) if close > 0 else ""
            if close < 0 or tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                    base,
                    stmt_e,
                    "unknown-construct",
                )
                idx += 1
                continue
            inner = _parse_sequence(cur, brace_abs + 1, close - 1, True)
            out.append(
                ParsedRegion(
                    kind=RegionKind.BARRIER_SCOPE,
                    span=cur.span(base, close),
                    children=tuple(inner),
                    barrier=BarrierMarkerKind.WORKER_GUARD_CANDIDATE,
                )
            )
            idx += 1
            continue

        if _RE_BARRIER_CHECK.search(stripped):
            m = _RE_BARRIER_CHECK.search(stripped)
            assert m is not None
            paren_rel = stripped.find("(", m.start())
            close = _match_forward(cur.text, base + paren_rel, stmt_e)
            tail = cur.text[close:stmt_e].strip(_WS) if close > 0 else ""
            if close < 0:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                    base,
                    stmt_e,
                    "unbalanced-paren",
                )
                idx += 1
                continue
            if tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                    base,
                    stmt_e,
                    "unknown-construct",
                )
                idx += 1
                continue
            if "{" in cur.text[base + paren_rel : close]:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
                    base,
                    stmt_e,
                    "lambda-in-barrier-check",
                )
                idx += 1
                continue
            out.append(
                ParsedRegion(
                    kind=RegionKind.DIRECT_CHECK,
                    span=cur.span(base, close),
                    barrier=BarrierMarkerKind.DIRECT_CHECK,
                )
            )
            idx += 1
            continue

        if _RE_LIKE_BARRIER.search(stripped):
            m = _RE_LIKE_BARRIER.search(stripped)
            assert m is not None
            cur.fail(
                "DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED",
                base + m.start(),
                stmt_e,
                "barrier-form-unrecognized",
            )
            idx += 1
            continue

        if _RE_COROUTINE.search(stripped):
            m = _RE_COROUTINE.search(stripped)
            assert m is not None
            start_off = base + m.start()
            if cur.text[start_off] not in _WS:
                start_off += 1
            cur.fail(
                "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                start_off,
                stmt_e,
                "coroutine-builder",
            )
            idx += 1
            continue

        if "{?:" in stripped.replace(" ", "").replace("\t", "") and "{" in stripped:
            pass

        if "?:" in stripped and "{" in stripped:
            qpos = stripped.find("?:")
            bpos = stripped.find("{", qpos)
            if bpos >= 0:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                    base,
                    stmt_e,
                    "elvis-block",
                )
                idx += 1
                continue

        if "{" in stripped or "}" in stripped:
            cur.fail(
                "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
                base,
                stmt_e,
                "lambda-escape",
            )
            idx += 1
            continue

        out.append(ParsedRegion(kind=RegionKind.STATEMENT, span=cur.span(base, stmt_e)))
        idx += 1
    return out


def _advance_past(
    parts: list[tuple[int, int]], idx: int, pos: int
) -> int:
    nxt = idx + 1
    while nxt < len(parts) and parts[nxt][0] < pos:
        nxt += 1
    return nxt


def _parse_if(
    cur: _Cursor,
    base: int,
    stmt_e: int,
    parts: list[tuple[int, int]],
    idx: int,
    in_lambda: bool,
) -> tuple[ParsedRegion | None, int]:
    text = cur.text
    stripped = text[base:stmt_e]
    paren_rel = stripped.find("(")
    if paren_rel < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "if-without-condition",
        )
        return None, idx + 1
    paren_abs = base + paren_rel
    cond_end = _match_forward(text, paren_abs, stmt_e)
    if cond_end < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            paren_abs,
            stmt_e,
            "unbalanced-paren",
        )
        return None, idx + 1
    rest = text[cond_end:stmt_e].strip(_WS)
    if not rest.startswith("{"):
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "unbraced-if-body",
        )
        return None, idx + 1
    brace_abs = stmt_e - len(text[cond_end:stmt_e]) + (len(text[cond_end:stmt_e]) - len(text[cond_end:stmt_e].lstrip(_WS)))
    brace_abs = cond_end + (len(text[cond_end:stmt_e]) - len(text[cond_end:stmt_e].lstrip(_WS)))
    close = _match_forward(text, brace_abs, stmt_e)
    tail = text[close:stmt_e].strip(_WS) if close > 0 else ""
    if close < 0 or tail:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "if-body-not-closed",
        )
        return None, idx + 1
    children = _parse_sequence(cur, brace_abs + 1, close - 1, in_lambda)
    span_end = close
    nxt = idx + 1
    while nxt < len(parts):
        ns, ne = parts[nxt]
        nstr = _strip(text[ns:ne])
        if _leading_kw(nstr, "else"):
            nbase = ns + (len(text[ns:ne]) - len(text[ns:ne].lstrip(_WS)))
            after = nstr[4:]
            if after and (after[0].isalnum() or after[0] == "_"):
                break
            if _leading_kw(after.lstrip(_WS), "if"):
                sub_rel = nstr.find("if")
                sub_base = nbase + sub_rel
                region, _ = _parse_if(cur, sub_base, ne, parts, nxt, in_lambda)
                if region is None:
                    return None, nxt + 1
                children = list(children) + [region]
                span_end = region.span.end
                nxt += 1
                continue
            e_rest = after.strip(_WS)
            if not e_rest.startswith("{"):
                cur.fail(
                    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                    nbase,
                    ne,
                    "unbraced-else-body",
                )
                return None, nxt + 1
            e_brace = nbase + (len(text[nbase:ne]) - len(text[nbase:ne].lstrip(_WS)) + 4)
            e_brace = nbase + (nstr.find("{"))
            e_close = _match_forward(text, e_brace, ne)
            e_tail = text[e_close:ne].strip(_WS) if e_close > 0 else ""
            if e_close < 0 or e_tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                    nbase,
                    ne,
                    "else-body-not-closed",
                )
                return None, nxt + 1
            inner = _parse_sequence(cur, e_brace + 1, e_close - 1, in_lambda)
            children = list(children) + [
                ParsedRegion(
                    kind=RegionKind.BLOCK,
                    span=cur.span(nbase, e_close),
                    children=tuple(inner),
                )
            ]
            span_end = e_close
            nxt += 1
        else:
            break
    return (
        ParsedRegion(
            kind=RegionKind.IF, span=cur.span(base, span_end), children=tuple(children)
        ),
        nxt,
    )


def _parse_when(
    cur: _Cursor, base: int, stmt_e: int, in_lambda: bool
) -> ParsedRegion | None:
    text = cur.text
    stripped = text[base:stmt_e]
    paren_rel = stripped.find("(")
    if paren_rel < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "when-without-subject",
        )
        return None
    paren_abs = base + paren_rel
    subj_end = _match_forward(text, paren_abs, stmt_e)
    if subj_end < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            paren_abs,
            stmt_e,
            "unbalanced-paren",
        )
        return None
    rest = text[subj_end:stmt_e]
    brace_off = rest.find("{")
    if brace_off < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "when-without-braces",
        )
        return None
    brace_abs = subj_end + brace_off
    if rest[:brace_off].strip(_WS):
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "when-preamble",
        )
        return None
    close = _match_forward(text, brace_abs, stmt_e)
    tail = text[close:stmt_e].strip(_WS) if close > 0 else ""
    if close < 0 or tail:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
            if close > 0
            else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            base,
            stmt_e,
            "when-body-not-closed" if close > 0 else "unbalanced-brace",
        )
        return None
    entries = _split_statements(cur, brace_abs + 1, close - 1)
    if entries is None:
        return None
    if not entries:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            brace_abs,
            close,
            "empty-when",
        )
        return None
    branches: list[ParsedRegion] = []
    for es, ee in entries:
        estr = text[es:ee]
        if not _strip(estr):
            continue
        arrow = estr.find("->")
        if arrow < 0:
            cur.fail(
                "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                es,
                ee,
                "when-branch-without-arrow",
            )
            return None
        rhs = estr[arrow + 2 :]
        rhs_s = rhs.strip(_WS)
        if not rhs_s:
            cur.fail(
                "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                es,
                ee,
                "when-branch-empty",
            )
            return None
        rhs_base = es + arrow + 2 + (len(rhs) - len(rhs.lstrip(_WS)))
        if rhs_s.startswith("{"):
            b_close = _match_forward(text, rhs_base, ee)
            b_tail = text[b_close:ee].strip(_WS) if b_close > 0 else ""
            if b_close < 0 or b_tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                    es,
                    ee,
                    "when-branch-not-closed",
                )
                return None
            inner = _parse_sequence(cur, rhs_base + 1, b_close - 1, in_lambda)
            branches.append(
                ParsedRegion(
                    kind=RegionKind.WHEN_BRANCH,
                    span=cur.span(es, b_close),
                    children=tuple(inner),
                )
            )
        else:
            # Arrow body: parse the RHS so mutations inside it stay
            # contained in the branch (a lambda there fails conservatively).
            inner = _parse_sequence(cur, rhs_base, ee, in_lambda)
            branches.append(
                ParsedRegion(
                    kind=RegionKind.WHEN_BRANCH,
                    span=cur.span(es, ee),
                    children=tuple(inner),
                )
            )
    return ParsedRegion(
        kind=RegionKind.WHEN, span=cur.span(base, close), children=tuple(branches)
    )


def _parse_loop(
    cur: _Cursor,
    base: int,
    stmt_e: int,
    parts: list[tuple[int, int]],
    idx: int,
    in_lambda: bool,
) -> ParsedRegion | None:
    text = cur.text
    stripped = text[base:stmt_e]
    if _leading_kw(stripped, "do"):
        rest = stripped[2:]
        if not rest.lstrip(_WS).startswith("{"):
            cur.fail(
                "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                base,
                stmt_e,
                "do-without-braces",
            )
            return None
        brace_abs = base + stripped.find("{")
        close = _match_forward(text, brace_abs, stmt_e)
        tail = text[close:stmt_e].strip(_WS) if close > 0 else ""
        if close < 0:
            cur.fail(
                "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                brace_abs,
                stmt_e,
                "unbalanced-brace",
            )
            return None
        span_end = close
        if not tail:
            nxt = idx + 1
            if nxt < len(parts):
                ns, ne = parts[nxt]
                nstr = _strip(text[ns:ne])
                if _leading_kw(nstr, "while"):
                    wparen = text[ns:ne].find("(")
                    if wparen < 0:
                        cur.fail(
                            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                            ns,
                            ne,
                            "do-while-without-condition",
                        )
                        return None
                    wclose = _match_forward(text, ns + wparen, ne)
                    wtail = text[wclose:ne].strip(_WS) if wclose > 0 else ""
                    if wclose < 0 or wtail:
                        cur.fail(
                            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
                            if wclose > 0
                            else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                            ns,
                            ne,
                            "do-while-condition-not-closed"
                            if wclose > 0
                            else "unbalanced-paren",
                        )
                        return None
                    span_end = wclose
                    inner = _parse_sequence(cur, brace_abs + 1, close - 1, in_lambda)
                    return _DoWhile(
                        kind=RegionKind.LOOP,
                        span=cur.span(base, span_end),
                        children=tuple(inner),
                        consumed_until=nxt + 1,
                    )
            cur.fail(
                "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
                base,
                stmt_e,
                "do-without-while",
            )
            return None
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "do-body-not-closed",
        )
        return None
    paren_rel = stripped.find("(")
    if paren_rel < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "loop-without-header",
        )
        return None
    paren_abs = base + paren_rel
    head_end = _match_forward(text, paren_abs, stmt_e)
    if head_end < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            paren_abs,
            stmt_e,
            "unbalanced-paren",
        )
        return None
    if _leading_kw(stripped, "for") and not re.search(r"\bin\b", stripped[: head_end - base]):
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "for-without-in",
        )
        return None
    rest = text[head_end:stmt_e]
    if not rest.strip(_WS).startswith("{"):
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "unbraced-loop-body",
        )
        return None
    brace_abs = head_end + (len(rest) - len(rest.lstrip(_WS)))
    close = _match_forward(text, brace_abs, stmt_e)
    tail = text[close:stmt_e].strip(_WS) if close > 0 else ""
    if close < 0 or tail:
        cur.fail(
            "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
            if close > 0
            else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            base,
            stmt_e,
            "loop-body-not-closed" if close > 0 else "unbalanced-brace",
        )
        return None
    inner = _parse_sequence(cur, brace_abs + 1, close - 1, in_lambda)
    return ParsedRegion(
        kind=RegionKind.LOOP, span=cur.span(base, close), children=tuple(inner)
    )


@dataclass(frozen=True)
class _DoWhile(ParsedRegion):
    #: Index of the first part AFTER the consumed ``while (...)`` condition.
    consumed_until: int = -1


def _parse_try(
    cur: _Cursor,
    base: int,
    stmt_e: int,
    parts: list[tuple[int, int]],
    idx: int,
    in_lambda: bool,
) -> tuple[ParsedRegion | None, int]:
    text = cur.text
    stripped = text[base:stmt_e]
    rest = stripped[3:]
    if not rest.lstrip(_WS).startswith("{"):
        cur.fail(
            "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
            base,
            stmt_e,
            "try-without-braces",
        )
        return None, idx + 1
    brace_abs = base + stripped.find("{")
    close = _match_forward(text, brace_abs, stmt_e)
    tail = text[close:stmt_e].strip(_WS) if close > 0 else ""
    if close < 0 or tail:
        cur.fail(
            "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED"
            if close > 0
            else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            base,
            stmt_e,
            "try-body-not-closed" if close > 0 else "unbalanced-brace",
        )
        return None, idx + 1
    children: list[ParsedRegion] = [
        ParsedRegion(
            kind=RegionKind.TRY,
            span=cur.span(base, close),
            children=tuple(_parse_sequence(cur, brace_abs + 1, close - 1, in_lambda)),
        )
    ]
    span_end = close
    nxt = idx + 1
    saw_catch_or_finally = False
    while nxt < len(parts):
        ns, ne = parts[nxt]
        nstr = _strip(text[ns:ne])
        nbase = ns + (len(text[ns:ne]) - len(text[ns:ne].lstrip(_WS)))
        if _leading_kw(nstr, "catch"):
            saw_catch_or_finally = True
            nstripped = text[nbase:ne]
            paren_rel = nstripped.find("(")
            if paren_rel < 0:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
                    nbase,
                    ne,
                    "catch-without-param",
                )
                return None, nxt + 1
            paren_abs = nbase + paren_rel
            head_end = _match_forward(text, paren_abs, ne)
            if head_end < 0:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                    paren_abs,
                    ne,
                    "unbalanced-paren",
                )
                return None, nxt + 1
            crest = text[head_end:ne]
            if not crest.strip(_WS).startswith("{"):
                cur.fail(
                    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
                    nbase,
                    ne,
                    "catch-without-braces",
                )
                return None, nxt + 1
            c_brace = head_end + (len(crest) - len(crest.lstrip(_WS)))
            c_close = _match_forward(text, c_brace, ne)
            c_tail = text[c_close:ne].strip(_WS) if c_close > 0 else ""
            if c_close < 0 or c_tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED"
                    if c_close > 0
                    else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                    nbase,
                    ne,
                    "catch-body-not-closed" if c_close > 0 else "unbalanced-brace",
                )
                return None, nxt + 1
            children.append(
                ParsedRegion(
                    kind=RegionKind.CATCH,
                    span=cur.span(nbase, c_close),
                    children=tuple(
                        _parse_sequence(cur, c_brace + 1, c_close - 1, in_lambda)
                    ),
                )
            )
            span_end = c_close
            nxt += 1
        elif _leading_kw(nstr, "finally"):
            saw_catch_or_finally = True
            nstripped = text[nbase:ne]
            frest = nstripped[7:]
            if not frest.lstrip(_WS).startswith("{"):
                cur.fail(
                    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
                    nbase,
                    ne,
                    "finally-without-braces",
                )
                return None, nxt + 1
            f_brace = nbase + nstripped.find("{")
            f_close = _match_forward(text, f_brace, ne)
            f_tail = text[f_close:ne].strip(_WS) if f_close > 0 else ""
            if f_close < 0 or f_tail:
                cur.fail(
                    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED"
                    if f_close > 0
                    else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                    nbase,
                    ne,
                    "finally-body-not-closed" if f_close > 0 else "unbalanced-brace",
                )
                return None, nxt + 1
            children.append(
                ParsedRegion(
                    kind=RegionKind.FINALLY,
                    span=cur.span(nbase, f_close),
                    children=tuple(
                        _parse_sequence(cur, f_brace + 1, f_close - 1, in_lambda)
                    ),
                )
            )
            span_end = f_close
            nxt += 1
            break
        else:
            break
    if not saw_catch_or_finally:
        cur.fail(
            "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
            base,
            span_end,
            "try-without-catch-finally",
        )
        return None, idx + 1
    first = children[0]
    merged = ParsedRegion(
        kind=RegionKind.TRY, span=cur.span(base, span_end), children=tuple(children)
    )
    _ = first
    return merged, nxt


def _parse_accessor(
    cur: _Cursor, base: int, stmt_e: int, in_lambda: bool
) -> ParsedRegion | None:
    text = cur.text
    stripped = text[base:stmt_e]
    paren_rel = stripped.find("(")
    if paren_rel < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
            base,
            stmt_e,
            "accessor-without-parens",
        )
        return None
    paren_abs = base + paren_rel
    head_end = _match_forward(text, paren_abs, stmt_e)
    if head_end < 0:
        cur.fail(
            "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            paren_abs,
            stmt_e,
            "unbalanced-paren",
        )
        return None
    rest = text[head_end:stmt_e]
    if not rest.strip(_WS).startswith("{"):
        cur.fail(
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
            base,
            stmt_e,
            "accessor-without-braces",
        )
        return None
    brace_abs = head_end + (len(rest) - len(rest.lstrip(_WS)))
    close = _match_forward(text, brace_abs, stmt_e)
    tail = text[close:stmt_e].strip(_WS) if close > 0 else ""
    if close < 0 or tail:
        cur.fail(
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED"
            if close > 0
            else "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
            base,
            stmt_e,
            "accessor-body-not-closed" if close > 0 else "unbalanced-brace",
        )
        return None
    inner = _parse_sequence(cur, brace_abs + 1, close - 1, in_lambda)
    return ParsedRegion(
        kind=RegionKind.ACCESSOR, span=cur.span(base, close), children=tuple(inner)
    )


def parse_callable_body(masked_text: str, body_span: SourceSpan) -> CallableBodyParse:
    if not isinstance(masked_text, str):
        raise TypeError("masked_text must be a string")
    if not isinstance(body_span, SourceSpan):
        raise TypeError("body_span must be a SourceSpan")
    cur = _Cursor(masked_text)
    if '"' in masked_text or "'" in masked_text:
        cur.fail(
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
            body_span.start,
            body_span.end,
            "masking-unverified",
        )
        return CallableBodyParse(
            body_span=body_span, regions=(), unsupported=tuple(cur.findings)
        )
    if (
        body_span.start < 0
        or body_span.end > len(masked_text)
        or body_span.end < body_span.start
    ):
        cur.fail(
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
            0,
            0,
            "span-out-of-range",
        )
        return CallableBodyParse(
            body_span=body_span, regions=(), unsupported=tuple(cur.findings)
        )
    raw = masked_text[body_span.start : body_span.end]
    if not raw.strip(_WS):
        return CallableBodyParse(body_span=body_span, regions=(), unsupported=())
    lstripped = raw.lstrip(_WS)
    if lstripped.startswith("=") and not lstripped.startswith("=="):
        eq = body_span.start + (len(raw) - len(lstripped))
        cur.fail(
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
            eq,
            body_span.end,
            "expression-body",
        )
        return CallableBodyParse(
            body_span=body_span, regions=(), unsupported=tuple(cur.findings)
        )
    content_start, content_end = body_span.start, body_span.end
    if lstripped.startswith("{"):
        open_abs = body_span.start + (len(raw) - len(lstripped))
        close = _match_forward(masked_text, open_abs, body_span.end)
        if close < 0:
            cur.fail(
                "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
                open_abs,
                body_span.end,
                "unbalanced-brace",
            )
            return CallableBodyParse(
                body_span=body_span, regions=(), unsupported=tuple(cur.findings)
            )
        if masked_text[close:body_span.end].strip(_WS):
            cur.fail(
                "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
                open_abs,
                body_span.end,
                "body-trailing-content",
            )
            return CallableBodyParse(
                body_span=body_span, regions=(), unsupported=tuple(cur.findings)
            )
        content_start, content_end = open_abs + 1, close - 1
    regions = _parse_sequence(cur, content_start, content_end, False)
    regions = _splice_dowhile(cur, masked_text, body_span, regions)
    return CallableBodyParse(
        body_span=body_span, regions=tuple(regions), unsupported=tuple(cur.findings)
    )


def _splice_dowhile(
    cur: _Cursor,
    text: str,
    body_span: SourceSpan,
    regions: list[ParsedRegion],
) -> list[ParsedRegion]:
    _ = (cur, text, body_span)
    out: list[ParsedRegion] = []
    skip_next_statement = False
    for region in regions:
        if skip_next_statement and region.kind == RegionKind.STATEMENT:
            inner_text = text[region.span.start : region.span.end]
            if _leading_kw(_strip(inner_text), "while"):
                skip_next_statement = False
                continue
            skip_next_statement = False
        if isinstance(region, _DoWhile):
            skip_next_statement = True
            plain = ParsedRegion(
                kind=region.kind, span=region.span, children=region.children
            )
            out.append(plain)
            continue
        if region.children:
            fixed_children = _splice_dowhile(cur, text, body_span, list(region.children))
            if fixed_children != list(region.children):
                out.append(
                    ParsedRegion(
                        kind=region.kind,
                        span=region.span,
                        children=tuple(fixed_children),
                        barrier=region.barrier,
                    )
                )
                continue
        out.append(region)
    return out
