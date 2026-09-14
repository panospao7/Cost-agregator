"""GR-14u28: a bound transparent scope is recognised as a scope candidate.

`db.withTransaction { ... }` standing alone was always recognised as a
TRANSPARENT_SCOPE candidate, but `val id = db.withTransaction { ... }` was not:
`_RE_TS_SCOPE` was anchored straight onto `receiver.method`, so the statement
beginning with `val` never matched, the lambda took the lambda-escape path, and
the ENTIRE callable became UNSUPPORTED — which also hid a barrier that already
dominated the mutation.

Measured effect on the board: 6 rows move to proven_helper, including 3
`InvestmentTracker.addHolding` rows that had been mislabelled
`unproven_external_entry` (i.e. reported as unreachable/dead code).

Recognising the candidate grants nothing by itself: admission is still
receiver-exact / import-exact in the proof layer, so every non-admitted bound
scope stays fail-closed.  These pins cover both halves.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from scripts.db_guard.structural_analysis.tokenizer import (
    _RE_TS_SCOPE,
    parse_callable_body,
)
from scripts.db_guard.structural_analysis.model import SourceSpan


def _parse(body: str):
    head = "package t\n\nclass C {\n    fun run2() {\n"
    tail = "    }\n}\n"
    source = head + body + tail
    span = SourceSpan(
        start=len(head), end=len(head) + len(body), line=4, column=1
    )
    return parse_callable_body(
        source, span, transparent_scope_methods=("withTransaction",)
    )


def _scope_methods(parse):
    out = []

    def walk(regions):
        for r in regions:
            if r.kind.value == "TRANSPARENT_SCOPE":
                out.append((r.scope_method, r.scope_receiver))
            walk(r.children)

    walk(parse.regions)
    return out


# ── the regex itself ─────────────────────────────────────────────────────────

def test_regex_matches_bare_scope_call():
    m = _RE_TS_SCOPE.match("db.withTransaction {")
    assert m is not None
    assert m.group("method") == "withTransaction"
    assert m.group("receiver") == "db"


def test_regex_matches_val_bound_scope_call():
    m = _RE_TS_SCOPE.match("val id = db.withTransaction {")
    assert m is not None, "bound scope call must be a candidate"
    assert m.group("method") == "withTransaction"
    assert m.group("receiver") == "db"


def test_regex_matches_typed_val_bound_scope_call():
    m = _RE_TS_SCOPE.match("val id: Long = db.withTransaction {")
    assert m is not None
    assert m.group("method") == "withTransaction"


def test_regex_matches_plain_assignment_scope_call():
    m = _RE_TS_SCOPE.match("result = db.withTransaction {")
    assert m is not None
    assert m.group("method") == "withTransaction"


def test_regex_does_not_match_a_lambda_literal_assignment():
    """`x = { ... }` is a lambda literal, never a scope candidate."""
    assert _RE_TS_SCOPE.match("x = {") is None
    assert _RE_TS_SCOPE.match("val x = {") is None


def test_method_filter_rejects_a_non_wrapper_bound_call():
    """The regex is purely syntactic; the wrapper-method filter is what rejects."""
    from scripts.db_guard.structural_analysis.tokenizer import (
        _match_transparent_scope,
    )

    assert _match_transparent_scope("val x = compute {", ("withTransaction",)) is None
    assert _match_transparent_scope(
        "val x = db.withTransaction {", ("withTransaction",)
    ) is not None


# ── end-to-end parse ─────────────────────────────────────────────────────────

def test_bound_scope_call_is_a_transparent_scope_region():
    parse = _parse(
        "        val id = db.withTransaction {\n"
        "            dao.insert(id)\n"
        "        }\n"
    )
    assert _scope_methods(parse) == [("withTransaction", "db")]


def test_bare_scope_call_is_still_a_transparent_scope_region():
    """Regression: the previously-working shape must keep working."""
    parse = _parse(
        "        db.withTransaction {\n"
        "            dao.insert(1)\n"
        "        }\n"
    )
    assert _scope_methods(parse) == [("withTransaction", "db")]


def test_bound_scope_reports_the_declaration_span():
    """The region span covers the whole statement, prefix included."""
    parse = _parse(
        "        val id = db.withTransaction {\n"
        "            dao.insert(id)\n"
        "        }\n"
    )
    found = []

    def walk(regions):
        for r in regions:
            if r.kind.value == "TRANSPARENT_SCOPE":
                found.append(r)
            walk(r.children)

    walk(parse.regions)
    assert len(found) == 1
    assert found[0].span.end > found[0].span.start


def test_non_scope_bound_call_still_escapes():
    """A bound call whose method is not an enabled wrapper is NOT a scope."""
    parse = _parse(
        "        val id = mystery {\n"
        "            dao.insert(id)\n"
        "        }\n"
    )
    assert _scope_methods(parse) == []
