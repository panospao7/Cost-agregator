"""GR-12 direct write-barrier dominance proof tests (adversarial fixtures).

Covers the plan's required placement matrix at the proof-module level.
All proofs run over a real parse -> CFG pipeline; sources are small inline
Kotlin files with an explicit import + property so the receiver resolver has
exact type evidence.
"""
from __future__ import annotations

import pytest

from scripts.kotlin_callable_parser import mask_kotlin_source

from scripts.db_guard.structural_analysis.barrier_markers import (
    collect_barrier_markers,
)
from scripts.db_guard.structural_analysis.barrier_proof import (
    CANONICAL_BARRIER_CONTRACT_V1,
    CanonicalBarrierContract,
    ProofStatus,
    ReceiverTypeResolver,
    canonical_barrier_call_sites,
    prove_direct_barrier,
)
from scripts.db_guard.structural_analysis.cfg import build_callable_cfg
from scripts.db_guard.structural_analysis.model import SourceSpan
from scripts.db_guard.structural_analysis.tokenizer import (
    _match_forward,
    parse_callable_body,
)

HEADER = (
    "package com.example.app\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class Repo(private val writeBarrier: DatabaseWriteBarrier) {\n"
)
FOOTER = "\n}\n"


def _fun_body_span(source: str) -> SourceSpan:
    marker = source.index("fun m(")
    open_brace = source.index("{", marker)
    close = _match_forward(source, open_brace, len(source))
    assert close > 0
    return SourceSpan(start=open_brace + 1, end=close - 1, line=source.count("\n", 0, open_brace) + 1, column=1)


def _sites(source: str, needles):
    sites = []
    for needle, operation in needles:
        start = source.index(needle)
        sites.append(
            type(
                "S",
                (),
                {
                    "span": SourceSpan(
                        start=start,
                        end=start + len(needle),
                        line=source.count("\n", 0, start) + 1,
                        column=1,
                    ),
                    "callable_key": "p|o|function|m|null|",
                    "dao_fqcn": "example.Dao",
                    "operation": operation,
                },
            )()
        )
    return tuple(sites)


def prove(source: str, needles=(("dao.insert", "insert"),), contract=CANONICAL_BARRIER_CONTRACT_V1):
    masked = mask_kotlin_source(source)
    assert len(masked) == len(source)
    body_span = _fun_body_span(source)
    parse_result = parse_callable_body(masked, body_span)
    assert parse_result.is_supported, [u.reason for u in parse_result.unsupported]
    sites = _sites(source, needles)
    markers = collect_barrier_markers(parse_result, masked)
    cfg, cfg_diagnostics = build_callable_cfg(
        parse_result,
        tuple(
            type(
                "MS",
                (),
                {
                    "span": site.span,
                    "callable_key": "p|o|function|m|null|",
                    "dao_fqcn": "example.Dao",
                    "operation": site.operation,
                    "mutation_kind": "ROOM_ABSTRACT_INSERT",
                    "source_identity": "example.Dao::" + site.operation,
                },
            )()
            for site in sites
        ),
        markers,
        path="app/src/main/java/Repo.kt",
        callable_key="p|o|function|m|null|",
    )
    assert cfg_diagnostics == ()
    resolver = ReceiverTypeResolver(masked)
    return prove_direct_barrier(
        masked,
        body_span,
        cfg,
        sites,
        contract,
        resolver,
        path="app/src/main/java/Repo.kt",
        callable_key="p|o|function|m|null|",
    )


def statuses(result):
    results, diagnostics = result
    return [item.status for item in results], diagnostics


class TestProvenPlacements:
    def test_sequential_check_then_mutation(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, diagnostics = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]
        assert diagnostics == ()

    def test_check_and_mutation_same_branch(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        if (a) {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "            dao.insert(x)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]

    def test_barrier_and_mutation_same_loop_body(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        while (a) {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "            dao.insert(x)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]

    def test_barrier_in_catch_mutation_in_catch_body(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        try {\n"
            + "            val a = 1\n"
            + "        } catch (e: E) {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "            dao.insert(x)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]

    def test_nested_branches_check_outer_then_mutation_inner(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        if (a) {\n"
            + "            if (b) {\n"
            + "                dao.insert(x)\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]

    def test_return_before_mutation_after_check(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        if (a) {\n"
            + "            return u\n"
            + "        }\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]

    def test_multiple_mutations_all_proven(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "        dao.delete(y)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source, (("dao.insert", "insert"), ("dao.delete", "delete"))))
        assert statuses_list == [ProofStatus.PROVEN, ProofStatus.PROVEN]

    def test_canonical_scope_around_mutation(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.runWrite {\n"
            + "            dao.insert(x)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.PROVEN]


class TestCounterexamplePlacements:
    def test_barrier_after_mutation(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        dao.insert(x)\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_barrier_only_in_one_branch_mutation_after_join(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        if (a) {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "        }\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_barrier_in_else_mutation_in_then(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        if (a) {\n"
            + "            dao.insert(x)\n"
            + "        } else {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_barrier_in_loop_zero_iterations_mutation_after(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        while (a) {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "        }\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_barrier_in_finally_after_mutation_in_try(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        try {\n"
            + "            dao.insert(x)\n"
            + "        } finally {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_barrier_in_catch_mutation_in_try_body(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        try {\n"
            + "            dao.insert(x)\n"
            + "        } catch (e: E) {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_runwrite_on_wrong_receiver_is_not_a_barrier(self):
        source = (
            "package com.example.app\n"
            "import com.example.other.Foo\n"
            "class Repo(private val foo: Foo) {\n"
            "    fun m() {\n"
            "        foo.runWrite {\n"
            "            dao.insert(x)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        # foo.runWrite is a barrier-form-unrecognized shape for the
        # tokenizer, so this fixture is validated at the resolver level:
        # the receiver resolves to a NON-canonical type.
        masked = mask_kotlin_source(source)
        resolver = ReceiverTypeResolver(masked)
        fqcn, resolution = resolver.resolve("foo")
        assert resolution == "RESOLVED"
        assert fqcn == "com.example.other.Foo"
        assert fqcn != CANONICAL_BARRIER_CONTRACT_V1.receiver_fqcn

    def test_correct_api_name_in_comment_is_not_a_barrier(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        // writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_same_method_on_fake_barrier_type_is_not_a_barrier(self):
        source = (
            "package com.example.app\n"
            "import com.example.fake.DatabaseWriteBarrier\n"
            "class Repo(private val writeBarrier: DatabaseWriteBarrier) {\n"
            "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + "}\n"
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]

    def test_counterexample_witness_carries_node_kinds_and_lines(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        if (a) {\n"
            + "            dao.insert(x)\n"
            + "        } else {\n"
            + "            writeBarrier.checkWritesAllowed(op)\n"
            + "        }\n"
            + "    }\n"
            + FOOTER
        )
        results, diagnostics = prove(source)
        result = results[0]
        assert result.status is ProofStatus.COUNTEREXAMPLE
        assert result.counterexample_node_kinds[0] == "ENTRY"
        assert result.counterexample_node_kinds[-1] == "MUTATION"
        assert len(result.counterexample_node_kinds) == len(result.counterexample_line_sequence)
        assert all(line >= 1 for line in result.counterexample_line_sequence)
        assert max(result.counterexample_line_sequence) <= len(source.splitlines())
        assert diagnostics == ()

    def test_mutation_without_any_barrier(self):
        source = HEADER + "    fun m() {\n        dao.insert(x)\n    }\n" + FOOTER
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]


class TestUnsupportedRoutes:
    def test_unresolved_receiver_fails_closed(self):
        # The receiver is a bare parameter-like name with no typed property:
        # canonical identity cannot be proven -> exit-2 route.
        source = (
            "package com.example.app\n"
            "class Repo {\n"
            "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + "}\n"
        )
        statuses_list, diagnostics = statuses(prove(source))
        assert statuses_list == [ProofStatus.UNSUPPORTED]
        assert diagnostics == ("DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED",)

    def test_ambiguous_receiver_type_fails_closed(self):
        source = (
            "package com.example.app\n"
            "import com.a.DatabaseWriteBarrier\n"
            "import com.b.DatabaseWriteBarrier\n"
            "class Repo(\n"
            "    private val writeBarrier: DatabaseWriteBarrier,\n"
            "    private val writeBarrier2: DatabaseWriteBarrier\n"
            ") {\n"
            "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + "}\n"
        )
        # Both properties resolve through the same AMBIGUOUS import simple
        # name: the resolver reports ambiguity -> UNSUPPORTED.
        statuses_list, diagnostics = statuses(prove(source))
        assert statuses_list == [ProofStatus.UNSUPPORTED]
        assert diagnostics == ("DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED",)

    def test_wrong_overload_name_is_not_a_barrier(self):
        # writesAllowed() is explicitly NOT a barrier (contract + API doc).
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.writesAllowed()\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        statuses_list, _ = statuses(prove(source))
        assert statuses_list == [ProofStatus.COUNTEREXAMPLE]


class TestReceiverResolution:
    def test_property_type_through_import(self):
        source = HEADER
        resolver = ReceiverTypeResolver(mask_kotlin_source(source))
        fqcn, resolution = resolver.resolve("writeBarrier")
        assert resolution == "RESOLVED"
        assert fqcn == "com.yourname.expensetracker.data.backup.DatabaseWriteBarrier"

    def test_same_package_type_without_import(self):
        source = (
            "package com.yourname.expensetracker.data.backup\n"
            "class Repo(private val writeBarrier: DatabaseWriteBarrier) {\n"
        )
        resolver = ReceiverTypeResolver(mask_kotlin_source(source))
        fqcn, resolution = resolver.resolve("writeBarrier")
        assert resolution == "RESOLVED"
        assert fqcn == "com.yourname.expensetracker.data.backup.DatabaseWriteBarrier"

    def test_unknown_receiver_reports_not_a_property(self):
        resolver = ReceiverTypeResolver(mask_kotlin_source(HEADER))
        fqcn, resolution = resolver.resolve("nope")
        assert resolution == "NOT_A_PROPERTY"
        assert fqcn is None

    def test_canonical_call_sites_extraction(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        foo.checkWritesAllowed(op)\n"
            + "    }\n"
            + FOOTER
        )
        masked = mask_kotlin_source(source)
        resolver = ReceiverTypeResolver(masked)
        sites = canonical_barrier_call_sites(
            masked, _fun_body_span(source), CANONICAL_BARRIER_CONTRACT_V1, resolver
        )
        assert len(sites) == 2
        by_receiver = {site.receiver_name: site for site in sites}
        assert by_receiver["writeBarrier"].receiver_fqcn == (
            "com.yourname.expensetracker.data.backup.DatabaseWriteBarrier"
        )
        assert by_receiver["foo"].receiver_fqcn != (
            "com.yourname.expensetracker.data.backup.DatabaseWriteBarrier"
        )


class TestContractAndSafety:
    def test_contract_rejects_bare_receiver_name(self):
        with pytest.raises(ValueError):
            CanonicalBarrierContract(
                contract_version=1,
                receiver_fqcn="DatabaseWriteBarrier",
                direct_check_methods=("checkWritesAllowed",),
                guarded_scope_methods=("runWrite",),
            )

    def test_contract_rejects_empty_methods(self):
        with pytest.raises(ValueError):
            CanonicalBarrierContract(
                contract_version=1,
                receiver_fqcn="com.a.B",
                direct_check_methods=(),
                guarded_scope_methods=("runWrite",),
            )

    def test_proof_result_has_no_raw_source(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        results, _ = prove(source)
        blob = repr(results)
        assert "dao.insert" not in blob
        assert "fun m" not in blob
        assert "C:\\" not in blob and "/Users/" not in blob

    def test_proof_is_deterministic(self):
        source = (
            HEADER
            + "    fun m() {\n"
            + "        writeBarrier.checkWritesAllowed(op)\n"
            + "        dao.insert(x)\n"
            + "    }\n"
            + FOOTER
        )
        assert prove(source) == prove(source)
