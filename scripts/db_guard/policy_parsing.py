"""Shared source-parsing machinery extracted from the legacy CLI in PR-01
(``scripts/verify_db_access_boundaries.py``).

The legacy CLI retains temporary duplicates of these definitions until its
rewiring task switches it over to this shared module.
"""
from __future__ import annotations

import re


_TYPE_DECL_NAME_RE = re.compile(
    r'\b(?P<kind>object|class|interface)\s+'
    r'(?P<name>[A-Za-z_][A-Za-z0-9_]*)\b'
)


def _mask_lines_for_structural_scan(lines):
    """Return a copy of ``lines`` with strings/comments masked to spaces.

    Masks Kotlin string literals (single/double/triple-quoted, including
    multi-line triple quotes), char literals, line comments (``// ...``), and
    block comments (``/* ... */``, including multi-line blocks) before any
    brace/paren/bracket accounting, so a ``// }``, a block-comment brace, or a
    literal ``"}"`` can never open or close a body.

    Newline/CR characters are preserved and every other character is replaced
    in place with a space, so the returned list has the same line count and
    each line the same length as the input — source line mapping and char
    offsets stay valid.
    """
    out_lines = []
    in_block_comment = False
    in_triple_string = False
    for line in lines:
        out = list(line)
        n = len(out)
        i = 0
        while i < n:
            ch = out[i]
            if in_block_comment:
                if ch == "*" and i + 1 < n and out[i + 1] == "/":
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                    in_block_comment = False
                    continue
                if ch not in ("\n", "\r"):
                    out[i] = " "
                i += 1
                continue
            if in_triple_string:
                if ch == '"' and line.startswith('"""', i):
                    out[i] = " "
                    out[i + 1] = " "
                    out[i + 2] = " "
                    i += 3
                    in_triple_string = False
                    continue
                if ch not in ("\n", "\r"):
                    out[i] = " "
                i += 1
                continue
            if ch == '"':
                if line.startswith('"""', i):
                    out[i] = " "
                    out[i + 1] = " "
                    out[i + 2] = " "
                    i += 3
                    in_triple_string = True
                    continue
                # regular string literal
                j = i + 1
                while j < n:
                    c = line[j]
                    if c == "\\":
                        j += 2
                        continue
                    if c == '"':
                        break
                    j += 1
                end = j if j < n else n
                for k in range(i, min(end, n)):
                    if out[k] not in ("\n", "\r"):
                        out[k] = " "
                i = min(end + 1, n)
                continue
            if ch == "'":
                # char literal
                j = i + 1
                while j < n:
                    c = line[j]
                    if c == "\\":
                        j += 2
                        continue
                    if c == "'":
                        break
                    j += 1
                end = j if j < n else n
                for k in range(i, min(end, n)):
                    if out[k] not in ("\n", "\r"):
                        out[k] = " "
                i = min(end + 1, n)
                continue
            if ch == "/" and i + 1 < n:
                if out[i + 1] == "/":
                    j = i
                    while j < n:
                        if out[j] not in ("\n", "\r"):
                            out[j] = " "
                        j += 1
                    i = n
                    continue
                if out[i + 1] == "*":
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                    in_block_comment = True
                    continue
            i += 1
        out_lines.append("".join(out))
    return out_lines


# Tokens whose presence at the END of a class/object/interface header line mean
# the header continues on the following line (a supertype list or a
# type-parameter clause): ``,`` separates supertypes, ``:``/``<``/``(`` carry
# the rest of a delegation/type-parameter clause, and a trailing ``.`` continues
# a qualified type name.  Without these, a multi-line header whose constructor
# parens close BEFORE the body brace (e.g. ``AnomalyAlertRepositoryImpl``) would
# be truncated at the paren-close line instead of reaching the first top-level
# ``{``.
_TYPE_HEADER_CONTINUATION_ENDINGS = (",", ":", "<", "(", ".")


def _line_ends_with_type_header_continuation(line):
    """Return True when a MASKED header line continues onto the next line.

    ``line`` must already be masked (strings/comments replaced by spaces), so a
    trailing ``,``/``:``/``.``/``<``/``(`` inside a comment or a string can
    never be mistaken for a real header continuation.
    """
    s = line.rstrip()
    if not s:
        return False
    return s.endswith(_TYPE_HEADER_CONTINUATION_ENDINGS)


def _type_body_end(lines, start):
    """Return the 0-based line index where the type body closes (balanced).

    Walks from the declaration line tracking parens and braces on a
    comment/string-aware copy of the source, so a ``"}"`` literal, a ``// }``
    comment, or a block-comment brace can never close the type body.  The body
    opens at the FIRST TOP-LEVEL ``{`` (paren depth 0) and ends when braces
    rebalance.  A brace inside constructor default values (paren depth >= 1) is
    header content, never the body opener.

    A multi-line header whose constructor parens close BEFORE the body brace is
    tracked across the full masked header until that first top-level ``{``, so a
    class like AnomalyAlertRepositoryImpl::

        class AnomalyAlertRepositoryImpl(
            private val dao: AnomalyAlertDao
        ) : SomeContract,
            OtherContract {

    is no longer truncated at the declaration line just because the parens
    balance on an earlier line.  A header line that ends with a continuation
    token (``,``/``:``/``.``/``<``/``(``) carries the supertype list /
    type-parameter clause onto the following line.  Body-less declarations
    (interfaces/objects/sealed members without braces) still end on their own
    line once the signature parens are balanced and no continuation follows.
    """
    masked = _mask_lines_for_structural_scan(lines)
    depth = 0
    paren = 0
    started = False
    for i in range(start, len(lines)):
        line = masked[i]
        if started:
            depth += line.count("{") - line.count("}")
        else:
            for j, ch in enumerate(line):
                if ch == "(":
                    paren += 1
                elif ch == ")":
                    paren -= 1
                elif ch == "{" and paren == 0:
                    started = True
                    rest = line[j + 1:]
                    depth = 1 + rest.count("{") - rest.count("}")
                    break
            if not started and paren <= 0 and i > start:
                # Constructor parens balanced but no body brace opened yet.  A
                # header-continuation token on this line means the header
                # carries on to the next line; otherwise the declaration is
                # body-less and ends on its declaration line.
                if _line_ends_with_type_header_continuation(line):
                    continue
                return start
        if started and depth == 0:
            return i
    return len(lines) - 1


def parse_type_declarations(lines):
    """Return every named type declaration in ``lines``.

    Pure helper (no I/O) that tests can exercise directly.

    Each entry is a dict: ``{kind, name, start, end}`` where ``kind`` is
    ``object``/``class``/``interface``, ``name`` is the declared name (never
    derived from the filename), and ``start``/``end`` are 0-based line indices
    of the full balanced declaration (body inclusive).  Nested declarations are
    included.  Duplicate names are NOT collapsed — callers fail closed when a
    policy class name is ambiguous within a file.

    Detection runs on a stateful comment/string mask of the source
    (:func:`_mask_lines_for_structural_scan`), which replaces line comments,
    block comments, strings, triple-quoted strings, and char literals with
    spaces while preserving offsets and newlines.  The ``kind``/``name`` are
    recovered from the CORRESPONDING RAW span only after the masked match
    proves the declaration is code, so fake ``class``/``object``/``interface``
    text inside comments or literals never creates a declaration and never
    alters scope.
    """
    masked = _mask_lines_for_structural_scan(lines)
    decls = []
    for i, line in enumerate(lines):
        m = _TYPE_DECL_NAME_RE.search(masked[i])
        if not m:
            continue
        kind = line[m.start("kind"):m.end("kind")]
        name = line[m.start("name"):m.end("name")]
        end = _type_body_end(lines, i)
        decls.append({
            "kind": kind,
            "name": name,
            "start": i,
            "end": end,
        })
    return decls


# Tokens whose presence at the END of a line force a multi-line expression
# body to continue onto the next line.
_EXPR_CONTINUATION_ENDINGS = (
    ".", ",", "=", "(", "[", "{", "->", "&&", "||", "+", "-", "*", "/", "%",
)

# Tokens a continued line may BEGIN with (leading-dot chains, elvis, etc.).
_EXPR_CONTINUATION_STARTS = (".", "?.", "!!", "?:")

# Control-flow keywords that can continue a compound expression body after a
# top-level block closes (``= if (...) {} else {}``, ``= try {} catch {}``,
# ``= try {} finally {}``).  Without recognizing these, an ``else``/``catch``/
# ``finally`` on a following line would be silently truncated out of the body.
_EXPR_BLOCK_CONTINUATION_RE = re.compile(r"\b(?:else|catch|finally)\b")

# Control-flow keywords whose BRACE-LESS expression-body forms cannot be
# bounded reliably by this scanner (``= if ...`` / ``= when ...`` /
# ``= try ...``).  A brace-less branch has no structural ``{`` the parser can
# track, so its end is unknowable without a full Kotlin grammar.  Such bodies
# fail closed with ``unsupported_expression`` instead of being truncated.
_EXPR_CF_START_RE = re.compile(r"^\s*\b(?:if|when|try)\b")


def _text_starts_with_control_flow(masked_text):
    """Return True when MASKED text begins with a brace-less control-flow
    keyword (``if`` / ``when`` / ``try``).

    Used to recognize expression bodies that begin with ``= if`` / ``= when`` /
    ``= try`` (the keyword may sit on the declaration line after ``=`` or lead
    a continuation line of a multi-line expression).  The mask guarantees
    string/comment content can never fake the keyword.
    """
    return _EXPR_CF_START_RE.match(masked_text) is not None


FUN_DECL_RE = re.compile(r'\bfun\s+(\w+)\s*[<(]')


def _mask_kotlin_line(line):
    """Stateless single-line mask (strings/comments -> spaces).

    Used by helpers that inspect one line (expression-body ``=`` detection and
    line-end continuation checks).  Multi-line block comments that begin on an
    earlier line are not tracked here — whole-file structural accounting uses
    the stateful :func:`_mask_lines_for_structural_scan` instead.
    """
    return _mask_lines_for_structural_scan([line])[0]


def _expression_body_split(line, initial_depth=0):
    """Return ``(prefix_before_eq, True)`` when ``line`` declares an expression
    body (``fun foo() = expr``), else ``(None, False)``.

    Finds the first ``=`` at paren/bracket depth 0 AFTER the signature's
    parameter list closes, skipping comparison operators (``==``, ``<=``,
    ``>=``, ``!=``, ``=>``) and default-value ``=`` tokens inside the
    parameter list (paren depth >= 1).  ``initial_depth`` carries open
    parens/brackets from earlier signature lines so a multi-line signature
    ending ``) = if (...) {`` is recognized the moment the parameter list
    closes.  A top-level ``{`` before any ``=`` at depth 0 (a block body such
    as ``fun foo() { val x = 1 }``) never matches, so block bodies are never
    mistaken for expression bodies.  Strings and comments are masked first so
    an ``=`` inside a comment or literal can never be mistaken for the
    expression-body ``=``.
    """
    line = _mask_kotlin_line(line)
    depth = initial_depth
    n = len(line)
    i = 0
    while i < n:
        ch = line[i]
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "{" and depth == 0:
            return None, False
        elif ch == "=" and depth == 0:
            prev = line[i - 1] if i > 0 else ""
            nxt = line[i + 1] if i + 1 < n else ""
            if prev in "!<>=:" or nxt in "=>":
                i += 1
                continue
            return line[:i], True
        i += 1
    return None, False


def _line_ends_with_expr_continuation(line):
    """Return True when a MASKED ``line`` ends with a continuation token.

    ``line`` must already be masked (strings/comments replaced by spaces), so
    trailing comments never hide a real trailing ``{``/``+``/``.`` and a
    comment-only trailing token can never force a spurious continuation.
    """
    s = line.rstrip()
    if not s:
        return False
    return s.endswith(_EXPR_CONTINUATION_ENDINGS)


def _next_line_starts_with_expr_continuation(lines, next_idx, bound):
    """Return True when the next non-blank line before ``bound`` begins with a
    continuation token (leading dot chain / elvis / not-null assert)."""
    for j in range(next_idx, bound):
        s = lines[j].strip()
        if not s:
            continue
        return s.startswith(_EXPR_CONTINUATION_STARTS)
    return False


def _next_line_starts_with_expr_keyword(lines, next_idx, bound):
    """Return True when the next non-blank line before ``bound`` begins with a
    control-flow continuation keyword (``else`` / ``catch`` / ``finally``).

    After a top-level block in a compound expression body closes, ``else`` /
    ``catch`` / ``finally`` may begin on the following line
    (``= if (...) {} \\n else {}``, ``= try {} \\n catch {}``).  Recognizing
    them keeps the expression body from being truncated at the closing brace.
    """
    for j in range(next_idx, bound):
        s = lines[j].strip()
        if not s:
            continue
        return _EXPR_BLOCK_CONTINUATION_RE.match(s) is not None
    return False


def _line_trails_expr_block_keyword(line):
    """Return True when a MASKED ``line`` carries a control-flow continuation
    keyword after its last ``}`` (e.g. ``} else`` or ``} catch (e: X)``).

    Handles the compact single-line continuation form
    (``} else \\n ...`` / ``} catch (...) \\n ...``) where the keyword does
    not open a block on the same line.
    """
    idx = line.rfind("}")
    if idx == -1:
        return False
    tail = line[idx + 1:].strip()
    if not tail:
        return False
    return _EXPR_BLOCK_CONTINUATION_RE.match(tail) is not None


def _next_non_blank_line_opens_block(masked_lines, next_idx, bound):
    """Return True when the next non-blank line before ``bound`` starts with
    ``{`` (a braced continuation branch opened on its own line)."""
    for j in range(next_idx, bound):
        s = masked_lines[j].strip()
        if not s:
            continue
        return s.startswith("{")
    return False


def _continuation_branch_opens_brace(masked_lines, line, next_idx, bound):
    """Return True when a control-flow continuation (``else``/``catch``/
    ``finally``) that keeps a compound expression body going is followed by a
    ``{`` on the same line or on the next non-blank line — i.e. the branch is
    provably braced.

    ``line`` is the current MASKED line; ``next_idx`` is the index of the
    following line (also masked).  Handles both the trailing-keyword form
    (``} else ...`` / ``} catch (...) ...``) and the next-line-keyword form
    (``}\\nelse ...``).  A brace-less branch returns False so callers fail
    closed with ``unsupported_expression`` instead of silently truncating the
    branch out of the method body.
    """
    idx = line.rfind("}")
    if idx != -1:
        tail = line[idx + 1:].strip()
        if tail:
            m = _EXPR_BLOCK_CONTINUATION_RE.match(tail)
            if m:
                if "{" in tail[m.end():]:
                    return True
                return _next_non_blank_line_opens_block(
                    masked_lines, next_idx, bound
                )
    for j in range(next_idx, bound):
        s = masked_lines[j].strip()
        if not s:
            continue
        m = _EXPR_BLOCK_CONTINUATION_RE.match(s)
        if not m:
            return False
        if "{" in s[m.end():]:
            return True
        return _next_non_blank_line_opens_block(masked_lines, j + 1, bound)
    return False


def _join_body_lines(body_lines):
    """Join collected body lines into a single body text.

    Each collected line may still carry its trailing line terminator (``\n`` /
    ``\r\n``) when it came from ``f.readlines()``.  Stripping those terminators
    before joining with ``\n`` guarantees EXACTLY one newline per line
    boundary, which keeps ``_line_of_offset`` (and therefore the 1-based
    ``abs_lineno`` used in violation diagnostics) precise.
    """
    return "\n".join(line.rstrip("\r\n") for line in body_lines)


def _method_body_end_and_text_detailed(lines, start, bound=None):
    """Return ``(end_line, body_text, unsupported_expression,
    unterminated_braced_body)`` for the function declared at ``start``.

    Walks from the declaration line tracking parens, brackets, and braces on a
    comment/string-aware copy of the source (``_mask_lines_for_structural_scan``
    replaces strings and comments with spaces, preserving line mapping), so
    ``"}"`` inside a literal, a ``// }`` line comment, or a block-comment
    brace can never close a body.  The body opens at the first ``{`` and runs
    until braces rebalance.  A signature-only/abstract declaration (no ``{``,
    no ``=`` expression body) ends at ``start`` with an empty body.

    Expression bodies (``= expr``) are parsed to their COMPLETE boundary — a
    multi-line expression is never truncated to one line.  Expression mode is
    detected BEFORE block-depth processing, so the ``{`` that opens a
    control-flow branch on the SAME line as ``= if`` / ``= try`` / ``= when``
    (``fun foo() = if (x) { ... }``) cannot hide the expression body, and a
    multi-line signature ending ``) = if (...) {`` is recognized too.
    Continuation is driven by bracket/brace/paren balance plus line-end/
    line-start tokens, and by control-flow continuation keywords (``else`` /
    ``catch`` / ``finally``) that can follow a closed top-level block, so
    ``= if (condition) { ... } else { ... }`` and ``= try { ... } catch { ... }``
    bodies (same-line or split across lines) are bounded structurally instead
    of being truncated at the first ``}``.  If a multi-line expression cannot
    be bounded within ``bound`` (inclusive line index, exclusive as a count),
    ``unsupported_expression`` is True so callers can fail closed instead of
    silently missing mutations inside the expression.

    Brace-less control-flow expression bodies (``= if ...`` / ``= when ...`` /
    ``= try ...``) have no structural ``{`` the parser can track, so their
    boundary cannot be proven.  They fail closed with ``unsupported_expression``
    True rather than authorizing a truncated body or silently omitting the
    mutations in the missing branch lines.  This covers:
      * a brace-less header whose branch body lives on later lines
        (``fun f() = if (x)`` + branch lines);
      * a brace-less ``else`` / ``catch`` / ``finally`` branch after a braced
        top-level block (``} else\\n    dao.update(...)``);
      * a brace-less ``when`` header with later branch lines.
    Already-supported braced forms (``= if (...) { ... }``, ``= when (x) {``,
    ``= try { ... } catch { ... }``) are unchanged.

    A NORMAL (non-expression) braced method body that never rebalances before
    ``bound`` is an unterminated method: the parser cannot prove where the body
    ends, so it returns ``unterminated_braced_body=True``.  Callers must fail
    closed with the controlled ``UNSUPPORTED_METHOD_BODY`` violation instead of
    authorizing mutations extracted from the partial body.
    """
    if start >= len(lines):
        return start, "", False, False
    if bound is None:
        bound = min(start + 200, len(lines))
    else:
        bound = min(bound, len(lines))

    # Mask strings/comments once for the whole file so line mapping stays
    # consistent even across multi-line strings/block comments; the RAW lines
    # are still what lands in ``body`` for mutation extraction.
    masked_lines = _mask_lines_for_structural_scan(lines)

    depth = 0
    paren = 0
    bracket = 0
    body_lines = []
    started = False
    expr_mode = False
    expr_begins_with_control_flow = False
    expr_consumed = False
    top_block_seen = False
    end = start

    for i in range(start, bound):
        raw_line = lines[i]
        line = masked_lines[i]
        body_lines.append(raw_line)
        end = i
        closed_top_block = False
        # Expression-mode detection runs BEFORE block-depth processing so a
        # control-flow block that opens on the same line as ``= if`` /
        # ``= try`` / ``= when`` (``fun foo() = if (x) {``) can never hide the
        # expression body behind the ``{`` that sets ``started``.  ``paren``
        # here is the depth accumulated by EARLIER signature lines, so a
        # multi-line signature ending ``) = if (...) {`` is recognized the
        # moment the parameter list closes.  The statefully masked line is
        # used so an ``=`` inside a cross-line comment or triple-quoted string
        # cannot fake an expression body.
        if not started:
            _prefix, is_expr = _expression_body_split(line, paren)
            if is_expr:
                expr_mode = True
                # A brace-less control-flow expression body (``= if ...`` /
                # ``= when ...`` / ``= try ...``) cannot be bounded reliably;
                # remember it so the method fails closed instead of silently
                # truncating the body at the first provably-ended line.
                expr_begins_with_control_flow = _text_starts_with_control_flow(
                    line[len(_prefix) + 1:]
                )
            elif (
                expr_mode
                and paren == 0 and bracket == 0 and depth == 0
                and not expr_begins_with_control_flow
            ):
                # A continuation line of a multi-line brace-less expression
                # body that begins with ``if`` / ``when`` / ``try`` marks the
                # expression as an unbounded control-flow form.
                expr_begins_with_control_flow = _text_starts_with_control_flow(
                    line.lstrip()
                )
        for ch in line:
            if ch == '{':
                if expr_mode and paren == 0 and bracket == 0 and depth == 0:
                    top_block_seen = True
                depth += 1
                started = True
            elif ch == '}':
                if started:
                    if expr_mode and depth == 1:
                        closed_top_block = True
                    depth -= 1
            elif ch == '(':
                paren += 1
            elif ch == ')':
                paren -= 1
            elif ch == '[':
                bracket += 1
            elif ch == ']':
                bracket -= 1
        if started and depth == 0:
            # A block-form compound expression body (``= if (...) {}`` /
            # ``= try {}``) rebalances its top-level block here, but the
            # expression may continue with ``else``/``catch``/``finally`` on a
            # following line — do not truncate it at the closing brace.
            if expr_mode and top_block_seen and (
                _next_line_starts_with_expr_keyword(
                    masked_lines, i + 1, bound
                )
                or _line_trails_expr_block_keyword(line)
            ):
                # The continuation keyword's branch is only provably bounded
                # when it opens a ``{`` on its own line or the next line.
                # A brace-less branch (``} else\\n    dao.update(...)``) cannot
                # be tracked safely — fail closed instead of truncating it.
                if not _continuation_branch_opens_brace(
                    masked_lines, line, i + 1, bound
                ):
                    return end, _join_body_lines(body_lines), True, False
            else:
                return end, _join_body_lines(body_lines), False, False
        if not started:
            if expr_mode:
                expr_consumed = True
                if (
                    paren == 0 and bracket == 0 and depth == 0
                    and not _line_ends_with_expr_continuation(line)
                    and not _next_line_starts_with_expr_continuation(
                        masked_lines, i + 1, bound
                    )
                    and not (
                        top_block_seen
                        and _next_line_starts_with_expr_keyword(
                            masked_lines, i + 1, bound
                        )
                    )
                    and not (
                        closed_top_block
                        and _line_trails_expr_block_keyword(line)
                    )
                ):
                    return (
                        end,
                        _join_body_lines(body_lines),
                        bool(expr_begins_with_control_flow),
                        False,
                    )
            elif paren == 0 and i > start:
                return start, "", False, False

    # Reached the bound without a boundary: an unbounded expression body is
    # reported as unsupported so the scanner can fail closed.  A NORMAL braced
    # method body that never closes before the bound is an unterminated method
    # (the parser cannot prove where the body ends) and is reported with
    # ``unterminated_braced_body`` so callers fail closed with
    # UNSUPPORTED_METHOD_BODY instead of authorizing mutations extracted from
    # the partial body.
    # ``expr_mode`` covers expression bodies whose first ``{`` arrived on the
    # declaration line (``= if (...) {``) — those never pass through the
    # ``not started`` branch to set ``expr_consumed``, yet they must still
    # fail closed when the expression cannot be bounded.
    if started or expr_consumed:
        if expr_mode or expr_consumed:
            return end, _join_body_lines(body_lines), True, False
        return end, _join_body_lines(body_lines), False, True
    return start, "", False, False


def parse_function_declarations(lines, start, end):
    """Return every function declaration in the 0-based line range [start, end].

    Pure helper (no I/O) that tests can exercise directly.

    Each entry is a dict: ``{name, start, end, body, unsupported_expression,
    unterminated_braced_body}``.  ``end`` is the line where the balanced body
    closes; ``body`` is the full balanced text (signature + body).
    Signature-only/abstract declarations get an empty body and ``end == start``.
    ``unsupported_expression`` is True when a multi-line expression body could
    not be bounded within the range — callers must fail closed.
    ``unterminated_braced_body`` is True when a normal (non-expression) braced
    method body never closes within the range — callers must fail closed with
    ``UNSUPPORTED_METHOD_BODY`` instead of authorizing mutations from the
    partial body.

    Detection runs on a stateful comment/string mask of the source
    (:func:`_mask_lines_for_structural_scan`), which replaces line comments,
    block comments, strings, triple-quoted strings, and char literals with
    spaces while preserving offsets and newlines.  The function name is
    recovered from the CORRESPONDING RAW span only after the masked match
    proves the declaration is code, so fake ``fun`` text inside comments or
    literals never creates a declaration and never absorbs (or skips) a real
    declaration that follows it.
    """
    masked = _mask_lines_for_structural_scan(lines)
    decls = []
    i = start
    while i <= end:
        m = FUN_DECL_RE.search(masked[i])
        if m:
            name = lines[i][m.start(1):m.end(1)]
            fend, body, unsupported, unterminated = _method_body_end_and_text_detailed(
                lines, i, bound=end + 1
            )
            decls.append({
                "name": name,
                "start": i,
                "end": min(fend, end),
                "body": body,
                "unsupported_expression": unsupported,
                "unterminated_braced_body": unterminated,
            })
            i = fend + 1
        else:
            i += 1
    return decls


# ── Explicit mutator grammar ──────────────────────────────────────────────────
# Every token is either a verb prefix or an exact compound method-name prefix of
# an ACTUAL DAO mutator present in app/src/main/java/.../data/database/dao/.
# Verb prefixes intentionally cover the compound mutators used in production
# (e.g. "update" covers updateIsNotMine, updateOwnerName, updateStatus, ...;
# "insert" covers insertOrIgnore, insertAll, insertOrUpdate, ...; "archive"
# covers archiveGroup; "claim" covers claimNotifications, ...; "mark" covers
# markSentFromClaimed, ...; "clear" covers clearSharedExpenseFlags, clearSession).
# This is an explicit grammar — never a broad `.*` exemption.
#
# IMPORTANT: the grammar is used ONLY for DETECTION.  Authorization compares
# the EXACT extracted DAO method name to the policy entry's ``operation``
# value; a policy entry with ``operation: write`` is invalid metadata and is
# rejected at load time.
MUTATION_VERBS = (
    "insert", "update", "delete", "clear", "mark", "set", "claim",
    "archive", "restore", "unlink", "link", "block", "unblock", "purge",
    "redact", "suppress", "fulfill", "approve", "reject", "dismiss",
    "expire", "deactivate", "finalize", "transition", "repair", "seed",
    "increment", "decrement", "upsert", "replace", "merge", "reassign",
    "record", "recover", "release", "reopen", "refresh", "reset", "cancel",
    "attach", "disconnect", "complete", "accept", "encrypt",
    # Prefix verbs for the remaining ACTUAL DAO mutators that would otherwise
    # be invisible to the grammar:
    #   "cleanup"       — cleanupOldDismissedAlerts (AnomalyAlertDao DELETE);
    #   "bulk"          — bulkRenameMerchant{,ByKey,ByName} and
    #                     bulkUpdateCategoryByMerchant{,ByKey,ByName}
    #                     (PendingReviewDao renames/updates);
    #   "add"           — addToGoalAmount (SavingsGoalDao atomic
    #                     UPDATE ... SET currentAmount = currentAmount + ..);
    #   "conditionally" — conditionallySetLocation (ExpenseDao
    #                     UPDATE ... WHERE latitude IS NULL).
    "cleanup", "bulk", "add", "conditionally",
)

# Exact compound mutator name-prefixes that do not start with any verb above.
# ``staleAbortIfStillRunning`` and ``getOrInsertByNameNoCase`` are the named
# policy operations that would otherwise be invisible to the grammar
# (``bulkRename`` is covered by the ``bulk`` verb prefix above).
MUTATION_EXACT_NAMES = (
    "getOrInsertByNameNoCase",
    "staleAbortIfStillRunning",
)

MUTATION_TOKENS = tuple(sorted(set(MUTATION_VERBS) | set(MUTATION_EXACT_NAMES)))
_MUTATION_ALTERNATION = "|".join(
    re.escape(token) for token in sorted(MUTATION_TOKENS, key=len, reverse=True)
)

# Captures the exact method name after the dot (group ``method``) so the
# extracted operation can be compared EXACTLY against the policy.
_MUTATION_CALL_RE = re.compile(
    r'\b(?P<receiver>\w+)\s*\.\s*'
    r'(?P<method>(?:' + _MUTATION_ALTERNATION + r')\w*)\s*\('
)

# Inline chain form: `database.someDao().mutation(...)`.
_DIRECT_CHAIN_MUTATION_RE = re.compile(
    r'\b(?P<dao>\w+Dao)\s*\(\s*\)\s*\.\s*'
    r'(?P<method>(?:' + _MUTATION_ALTERNATION + r')\w*)\s*\('
)

# ── DAO name extraction ───────────────────────────────────────────────────────

LOCAL_DAO_ASSIGN = re.compile(r'\bval\s+(\w+)\s*=\s*\w+\.(\w+Dao)\s*\(')

# Matches Kotlin property/constructor parameter declarations with explicit DAO types:
#   private val groupDao: ExpenseGroupDao
#   val usageDao: SubscriptionUsageDao
#   private val memberDao: GroupMemberDao
# Group 1: variable/property name, Group 2: declared DAO interface simple name
DAO_PROPERTY_DECL = re.compile(
    r'(?:private\s+|protected\s+|internal\s+|override\s+)*'
    r'(?:val|var|lateinit\s+var)\s+(\w+)\s*:\s*'
    r'(?:\w+\.)*(\w+Dao)\b'
)


def _interface_name_to_room_accessor(interface_name):
    """Derive the Room DB accessor name from a DAO interface simple name.

    Room generates an abstract method by lowercasing the first character of the
    DAO interface name.  E.g. ExpenseGroupDao -> expenseGroupDao,
    SubscriptionUsageDao -> subscriptionUsageDao.
    """
    if not interface_name or len(interface_name) < 2:
        return interface_name
    return interface_name[0].lower() + interface_name[1:]


def _scan_dao_var_lines(lines, start, end, exclude=None):
    """Scan the 0-based line range [start, end] for DAO variable declarations.

    Builds a mapping ``variable_name -> Room accessor name`` from:
      * class/constructor property declarations with explicit DAO types
        (``private val groupDao: ExpenseGroupDao`` -> ``expenseGroupDao``);
      * locals assigned from the database accessor
        (``val dao = database.scannedReceiptDao()`` -> ``scannedReceiptDao``),
        including the multi-line ``val x =\\n database.someDao()`` form.

    ``exclude`` is an optional set of 0-based line indices that must be
    skipped (used for the class-scope map so assignments inside method bodies
    can never pollute the class map).  A skipped line always resets any
    pending multi-line local so a continuation line cannot cross a method
    boundary.
    """
    var_map = {}
    pending_var = None

    for i in range(start, end + 1):
        if exclude is not None and i in exclude:
            pending_var = None
            continue

        line = lines[i]
        s = line.strip()
        if s.startswith("//") or s.startswith("*"):
            continue

        # Pattern 1: val name = database.someDao()
        m = LOCAL_DAO_ASSIGN.search(line)
        if m:
            var_map[m.group(1)] = m.group(2)
            pending_var = None
            continue

        # Pattern 2: Multi-line val name =\n    database.someDao()
        m_pending = re.search(r'\bval\s+(\w+)\s*=\s*$', line.rstrip())
        if m_pending:
            pending_var = m_pending.group(1)
            continue
        if pending_var and re.search(r'\w+Dao\s*\(', line):
            m_dao = re.search(r'(\w+Dao)\s*\(', line)
            if m_dao:
                var_map[pending_var] = m_dao.group(1)
            pending_var = None
            continue
        pending_var = None

        # Pattern 3: val/var name: SomeDaoType (constructor/property injection)
        m_prop = DAO_PROPERTY_DECL.search(line)
        if m_prop:
            var_name = m_prop.group(1)
            interface_name = m_prop.group(2)
            room_accessor = _interface_name_to_room_accessor(interface_name)
            # Only add if not already mapped by more specific patterns above
            if var_name not in var_map:
                var_map[var_name] = room_accessor

    return var_map


def build_dao_var_map(lines, start=0, end=None):
    """Build a class/method-scoped DAO variable map over a 0-based line range.

    Pure helper (no I/O) that tests can exercise directly.  ``end`` defaults to
    the last line.  Returns ``dict: variable_name -> Room accessor name``.
    """
    if end is None:
        end = len(lines) - 1
    if end < start:
        return {}
    return _scan_dao_var_lines(lines, start, end)


def build_class_scope_dao_var_map(lines, start, end, excluded_line_numbers=None):
    """Build the CLASS-scope DAO map: constructor params, class property
    declarations, and class-body-level aliases only.

    ``excluded_line_numbers`` is an iterable of 0-based line indices that
    belong to method bodies.  Assignments on those lines are METHOD-LOCAL and
    must never appear in the class map — otherwise a ``val dao = ...`` in
    method A would authorize method B's unrelated use of ``dao``.
    """
    if excluded_line_numbers is None:
        excluded_line_numbers = set()
    return _scan_dao_var_lines(
        lines, start, end, exclude=set(excluded_line_numbers)
    )


def _resolve_dao_identity(receiver, var_map):
    """Resolve a mutation receiver to a DAO identity used by the policy.

    Priority:
      1. a known scoped property/local (``var_map``) — yields the Room
         accessor name (e.g. ``groupDao: ExpenseGroupDao`` -> ``expenseGroupDao``);
      2. the literal ``\\w+Dao`` naming convention.

    Returns None when the receiver is not a DAO identity (e.g. ``database``,
    ``db``, ``writeBarrier``, a diagnostic sink) — such calls are not DAO
    mutations and are never flagged.
    """
    if receiver in var_map:
        return var_map[receiver]
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*Dao", receiver):
        return receiver
    return None


def _line_of_offset(body, offset):
    """Return the 0-based line index within ``body`` containing ``offset``."""
    return body.count("\n", 0, offset)


def _mask_mutation_body(body):
    """Return a comment/string-masked copy of a Kotlin method body.

    Uses the stateful :func:`_mask_lines_for_structural_scan` so multi-line
    block comments and triple-quoted strings that begin inside the body stay
    masked to their true end, and preserves per-line length plus newline
    positions — char offsets and ``_line_of_offset`` line numbers therefore
    keep pointing at the original source lines.
    """
    return "\n".join(_mask_lines_for_structural_scan(body.split("\n")))


def _extract_mutation_matches(body, var_map=None, out_of_scope_aliases=None,
                              out_of_scope_alias_identities=None):
    """Extract every DAO mutation call from the COMPLETE ``body`` text.

    ``body`` is a Kotlin method body (signature + balanced braces).  The
    whitespace tokens in the call regexes span newlines, so multi-line calls
    such as ``dao\\n    .insert(x)`` are detected — extraction never relies on
    individual source lines.

    Returns a list of match dicts:
      * ``receiver`` — the receiver expression identifier (or chain DAO name);
      * ``dao`` — the resolved DAO identity (Room accessor name);
      * ``op`` — the EXACT DAO method name invoked;
      * ``start`` — char offset of the match inside ``body``;
      * ``lineno`` — 0-based line index (within ``body``) where the match
        starts (preserved for diagnostics);
      * ``out_of_scope`` — True when the receiver is a DAO local alias
        declared in ANOTHER method of the same class.  Such a match is never
        authorizable here (fail closed) even if a policy entry would otherwise
        cover the pair.

    ``var_map`` is the scoped class/method DAO map; ``out_of_scope_aliases``
    is the set of alias names that exist in other methods; and
    ``out_of_scope_alias_identities`` maps those names to the DAO identity for
    diagnostics.  Read-only calls (get/observe/count/exists/find/...) are
    never extracted.

    Extraction runs on a comment/string-MASKED copy of ``body`` (stateful
    masker, line mapping preserved), so a DAO-looking call inside a line
    comment, block comment, string, triple-quoted string, or char literal can
    never become a mutation pair.
    """
    if var_map is None:
        var_map = {}
    if out_of_scope_aliases is None:
        out_of_scope_aliases = frozenset()
    if out_of_scope_alias_identities is None:
        out_of_scope_alias_identities = {}
    matches = []

    def _record(receiver, dao, op, start, out_of_scope):
        matches.append({
            "receiver": receiver,
            "dao": dao,
            "op": op,
            "start": start,
            "lineno": _line_of_offset(body, start),
            "out_of_scope": bool(out_of_scope),
        })

    # The masked body has the same length and newline positions as ``body``,
    # so ``m.start()`` offsets stay valid for ``_line_of_offset`` diagnostics.
    masked_body = _mask_mutation_body(body)

    for m in _MUTATION_CALL_RE.finditer(masked_body):
        receiver = m.group("receiver")
        op = m.group("method")
        if receiver in var_map:
            _record(receiver, var_map[receiver], op, m.start(), False)
        elif receiver in out_of_scope_aliases:
            _record(
                receiver,
                out_of_scope_alias_identities.get(receiver, receiver),
                op,
                m.start(),
                True,
            )
        else:
            dao = _resolve_dao_identity(receiver, var_map)
            if dao is not None:
                _record(receiver, dao, op, m.start(), False)

    for m in _DIRECT_CHAIN_MUTATION_RE.finditer(masked_body):
        _record(m.group("dao"), m.group("dao"), m.group("method"), m.start(), False)

    return matches


def extract_mutation_pairs(body, var_map=None):
    """Extract exact ``(dao_identity, operation)`` pairs from a method body.

    Pure helper (no I/O, no scanning) that tests can exercise directly.

    ``body`` is a Kotlin method body text.  ``var_map`` is the scoped
    class/method DAO variable map (see :func:`build_dao_var_map`).

    Returns a list of ``(dao_identity, operation)`` pairs where ``operation``
    is the EXACT DAO method name invoked (e.g. ``insertOrIgnore``,
    ``archiveGroup``, ``staleAbortIfStillRunning``).  Read-only calls
    (get/observe/count/exists/find/...) are never extracted.
    """
    if var_map is None:
        var_map = {}
    pairs = []
    seen = set()
    for match in _extract_mutation_matches(body, var_map=var_map):
        pair = (match["dao"], match["op"])
        if pair not in seen:
            pairs.append(pair)
            seen.add(pair)
    return pairs


# Canonical write-barrier evidence.  The ONLY barrier calls that enforce the
# restore/write barrier before a DAO mutation are
# ``writeBarrier.checkWritesAllowed(...)`` (every production writer) and
# ``writeBarrier.runWrite(...)`` (the DatabaseWriteBarrier API form that
# checks the barrier internally before running its block).  The core call
# pattern is exact, never broad: the bounded method-name alternation means a
# read-only mode predicate (``writeBarrier.writesAllowed()`` — it does NOT
# block writes) or text that merely shares a prefix can never satisfy barrier
# evidence.  Receiver qualification is decided by
# :func:`_write_barrier_receiver_is_unqualified`, which inspects the MASKED
# context BEFORE the ``writeBarrier`` token — never by a single lookbehind —
# so a qualified receiver with spaces or comments around the dot can never be
# mistaken for the unqualified ``writeBarrier`` identifier.
WRITE_BARRIER_PATTERN = re.compile(
    r'writeBarrier\s*\.\s*(?:checkWritesAllowed|runWrite)\s*\('
)


def _write_barrier_receiver_is_unqualified(masked_line, match_start):
    """Return True when the ``writeBarrier`` receiver is UNQUALIFIED.

    ``masked_line`` is a comment/string-masked source line (line comments,
    block comments, strings, triple-quoted strings, and char literals are
    spaces; offsets are preserved) and ``match_start`` is the char offset
    where ``writeBarrier`` begins.

    Inspects the masked context immediately before the token instead of
    relying on a single lookbehind:

      * a word character directly before the token means the token is the
        tail of a longer identifier (``somewriteBarrier.checkWritesAllowed``)
        — reject;
      * skipping whitespace (a masked comment is whitespace), a preceding
        ``.`` means the receiver is QUALIFIED — reject, whether the dot is
        adjacent (``foo.writeBarrier``), spaced (``foo . writeBarrier``), or
        comment-padded (``foo. /*c*/ writeBarrier``);
      * otherwise the token is the standalone identifier ``writeBarrier`` used
        as the receiver at method/body scope — accept.
    """
    if match_start <= 0:
        return True
    prev = masked_line[match_start - 1]
    if prev == "_" or prev.isalnum():
        return False
    j = match_start - 1
    while j >= 0 and masked_line[j].isspace():
        j -= 1
    if j >= 0 and masked_line[j] == ".":
        return False
    return True


def _barrier_before_line(lines, fun_start, mutation_lineno):
    """Return True if a REAL writeBarrier call appears strictly between
    ``fun_start`` and ``mutation_lineno``.

    ``fun_start`` is the 0-based line of the enclosing method declaration;
    ``mutation_lineno`` is the 1-based line of the DAO mutation.  Only lines
    strictly before the mutation line are inspected, so a barrier AFTER the
    mutation never satisfies evidence.

    Evidence is checked on the STATEFULLY MASKED source lines (line comments,
    block comments, strings, triple-quoted strings, and char literals replaced
    with spaces; line count and ordering preserved), so a fake
    ``writeBarrier.checkWritesAllowed(...)`` inside a comment or string can
    never satisfy barrier evidence.

    Every candidate call is receiver-aware: the masked context before the
    ``writeBarrier`` token is inspected (see
    :func:`_write_barrier_receiver_is_unqualified`), so a qualified receiver
    — including ``foo . writeBarrier`` or ``foo. /*c*/ writeBarrier`` — can
    never satisfy evidence.
    """
    masked = _mask_lines_for_structural_scan(lines)
    for i in range(fun_start, min(mutation_lineno - 1, len(lines))):
        line = masked[i]
        for m in WRITE_BARRIER_PATTERN.finditer(line):
            if _write_barrier_receiver_is_unqualified(line, m.start()):
                return True
    return False


# Controlled reason codes emitted by verify_ownership_policy_source_evidence().
# These are the ONLY codes the CLI prints for source-evidence failures.
SOURCE_EVIDENCE_CODES = frozenset({
    "ENTRY_INVALID",
    "PATH_NOT_FOUND",
    "PATH_INVALID",
    "FILE_UNREADABLE",
    "CLASS_MISSING",
    "CLASS_AMBIGUOUS",
    "METHOD_MISSING",
    "METHOD_BODY_UNSUPPORTED",
    "DAO_RESOLUTION_FAILED",
    "PAIR_NOT_FOUND",
    "PAIR_NOT_COVERED",
    "MISSING_WRITE_BARRIER",
    "MEDIATED_METADATA_UNTRUTHFUL",
})


def _source_evidence_error(path, class_name, method_name, code, detail,
                           dao=None, operation=None):
    """Build one structured source-evidence error dict.

    Bounded, controlled fields only — never raw source text or exception
    messages.  ``code`` must be one of the controlled SOURCE_EVIDENCE_CODES.
    ``dao``/``operation`` are added only when the error concerns a specific
    mutation pair.
    """
    error = {
        "path": path,
        "class": class_name,
        "method": method_name,
        "code": code,
        "detail": detail,
    }
    if dao is not None:
        error["dao"] = dao
    if operation is not None:
        error["operation"] = operation
    return error
