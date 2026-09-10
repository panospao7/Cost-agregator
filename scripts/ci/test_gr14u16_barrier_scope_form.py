"""GR-14u16: the barrier-scope form must match the REAL `runWrite` API.

`DatabaseWriteBarrier.runWrite(operation, block)` requires the operation, so real
code writes `writeBarrier.runWrite(op) { ... }` — never a bare
`writeBarrier.runWrite { ... }`.  `_RE_BARRIER_SCOPE` only accepted the bare form,
so canonical guarded bodies fell through to the like-barrier tripwire and failed
with `DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED`, making the whole body
unmodelable (47 of the 82 unmodelable observed callables).

These pins lock the accepted forms AND the fail-closed cases that must NOT start
being accepted.
"""
from __future__ import annotations

from scripts.db_guard.structural_analysis.barrier_proof import (
    CANONICAL_BARRIER_CONTRACT_V2,
    ReceiverTypeResolver,
    canonical_barrier_call_sites,
)
from scripts.db_guard.structural_analysis.model import SourceSpan
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body
from scripts.kotlin_callable_parser import mask_kotlin_source

_HEAD = (
    "package com.example\n"
    "\n"
    "import com.yourname.expensetracker.data.backup.DatabaseAccessOperation\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "\n"
    "class Repo(private val dao: Dao) {\n"
    "    private val writeBarrier: DatabaseWriteBarrier = TODO()\n"
    "\n"
    "    suspend fun subject() {\n"
)
_TAIL = "    }\n}\n"


def _analyse(body: str):
    """(unsupported reasons, barrier call sites) for one synthetic body."""
    masked = mask_kotlin_source(_HEAD + body + _TAIL)
    start = masked.index("suspend fun subject")
    body_start = masked.index("{", masked.index(")", start))
    depth = 0
    body_end = body_start
    for i in range(body_start, len(masked)):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                body_end = i + 1
                break
    span = SourceSpan(body_start, body_end, 1, 1)
    parse = parse_callable_body(
        masked,
        span,
        transparent_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods,
    )
    sites = canonical_barrier_call_sites(
        masked, span, CANONICAL_BARRIER_CONTRACT_V2, ReceiverTypeResolver(masked)
    )
    return [f.reason for f in parse.unsupported], sites, parse


class TestAcceptedBarrierScopeForms:
    def test_runwrite_with_operation_argument_parses(self):
        """The real API shape: runWrite(operation) { block }."""
        reasons, sites, _ = _analyse(
            "        writeBarrier.runWrite(DatabaseAccessOperation(\"op\")) {\n"
            "            dao.delete()\n"
            "        }\n"
        )
        assert reasons == []
        assert [s.method for s in sites] == ["runWrite"]

    def test_parenthesised_lambda_form_stays_fail_closed(self):
        """Documented limitation: `runWrite(op, { ... })` is NOT a barrier scope.

        Its lambda sits INSIDE the argument list, a different shape from the
        trailing-lambda form, and the tokenizer keeps refusing it rather than
        guessing.  That is fail-closed: the body stays unmodelable, so the
        mutation cannot be claimed guarded (GR-14u15 keeps it out of
        counterexamples only when a barrier call precedes it).
        """
        reasons, sites, _ = _analyse(
            "        writeBarrier.runWrite(DatabaseAccessOperation(\"op\"), {\n"
            "            dao.delete()\n"
            "        })\n"
        )
        assert reasons == ["barrier-form-unrecognized"]
        # The call is still VISIBLE to the text-only barrier scan, which is what
        # keeps such a body out of the counterexamples.
        assert [s.method for s in sites] == ["runWrite"]

    def test_bare_runwrite_brace_still_parses(self):
        """Back-compat: the shape the old regex accepted keeps working."""
        reasons, sites, _ = _analyse(
            "        writeBarrier.runWrite {\n"
            "            dao.delete()\n"
            "        }\n"
        )
        assert reasons == []
        assert [s.method for s in sites] == ["runWrite"]


class TestStillFailClosed:
    def test_unknown_receiver_runwrite_is_not_a_barrier(self):
        """Only the canonical receiver may open a barrier scope.

        A same-named method on another receiver must stay unrecognized (the
        like-barrier tripwire), never silently become a guarded scope.
        """
        reasons, _sites, _ = _analyse(
            "        other.runWrite(DatabaseAccessOperation(\"op\")) {\n"
            "            dao.delete()\n"
            "        }\n"
        )
        assert reasons == ["barrier-form-unrecognized"]

    def test_lambda_before_barrier_scope_still_escapes(self):
        """A barrier scope nested in an enclosing lambda is not a scope."""
        reasons, _sites, _ = _analyse(
            "        items.forEach {\n"
            "            writeBarrier.runWrite(DatabaseAccessOperation(\"op\")) {\n"
            "                dao.delete()\n"
            "            }\n"
            "        }\n"
        )
        assert reasons, "an enclosing lambda must keep the body fail-closed"
