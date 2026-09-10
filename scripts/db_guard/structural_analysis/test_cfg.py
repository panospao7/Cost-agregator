"""CFG construction tests (shadow-only, no dominance verdicts)."""
from __future__ import annotations

import pytest

from scripts.db_guard.structural_analysis.cfg import build_callable_cfg
from scripts.db_guard.structural_analysis.model import (
    BarrierMarker,
    BarrierMarkerKind,
    EdgeKind,
    MutationSite,
    NodeKind,
    SourceSpan,
)
from scripts.db_guard.structural_analysis.test_tokenizer import parse


def site_at(source: str, needle: str) -> MutationSite:
    start = source.index(needle)
    return MutationSite(
        span=SourceSpan(
            start=start,
            end=start + len(needle),
            line=source.count("\n", 0, start) + 1,
            column=start - source.rfind("\n", 0, start),
        ),
        callable_key="p|o|function|m|null|",
        dao_fqcn="example.Dao",
        operation="insert",
        mutation_kind="ROOM_ABSTRACT_INSERT",
        source_identity="example.Dao::insert",
    )


def build(source: str, sites=()):
    return build_callable_cfg(
        parse(source),
        tuple(sites),
        (),
        path="app/src/main/java/A.kt",
        callable_key="p|o|function|m|null|",
    )


def nodes_of(graph, kind: NodeKind):
    return [node for node in graph.nodes if node.kind is kind]


def edge_pairs(graph, kind: EdgeKind | None = None):
    return [
        (edge.source_node_id, edge.target_node_id, edge.kind)
        for edge in graph.edges
        if kind is None or edge.kind is kind
    ]


def successors(graph, node_id: str):
    return [
        (edge.target_node_id, edge.kind)
        for edge in graph.edges
        if edge.source_node_id == node_id
    ]


class TestSequentialFlow:
    def test_sequential_with_one_mutation(self):
        source = "val a = 1\ndao.insert(x)\nval b = 2\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        assert len(nodes_of(graph, NodeKind.ENTRY)) == 1
        assert len(nodes_of(graph, NodeKind.EXIT_NORMAL)) == 1
        mutations = nodes_of(graph, NodeKind.MUTATION)
        assert len(mutations) == 1
        entry = nodes_of(graph, NodeKind.ENTRY)[0]
        mutation = mutations[0]
        # The mutation is reachable from entry over NORMAL edges.
        reachable = {entry.id}
        frontier = [entry.id]
        while frontier:
            current = frontier.pop()
            for target, _ in successors(graph, current):
                if target not in reachable:
                    reachable.add(target)
                    frontier.append(target)
        assert mutation.id in reachable

    def test_multiple_mutations_get_distinct_attached_nodes(self):
        source = "dao.insert(a)\nval x = 1\ndao.insert(b)\n"
        sites = [site_at(source, "dao.insert(a)"), site_at(source, "dao.insert(b)")]
        graph, diagnostics = build(source, sites)
        assert diagnostics == ()
        mutations = nodes_of(graph, NodeKind.MUTATION)
        assert len(mutations) == 2
        assert mutations[0].span != mutations[1].span
        for site, mutation in zip(sites, mutations):
            owners = [
                node
                for node in graph.nodes
                if node.kind is NodeKind.STATEMENT
                and node.span.start <= site.span.start < node.span.end
            ]
            assert owners, "mutation site must sit inside its statement"
            assert any(
                target == mutation.id
                for target, _ in successors(graph, owners[0].id)
            )

    def test_mutation_outside_body_is_unresolved_and_attached_to_entry(self):
        source = "val a = 1\n"
        outside = MutationSite(
            span=SourceSpan(
                start=len(source) + 50,
                end=len(source) + 60,
                line=9,
                column=1,
            ),
            callable_key="p|o|function|m|null|",
            dao_fqcn="example.Dao",
            operation="insert",
            mutation_kind="ROOM_ABSTRACT_INSERT",
            source_identity="example.Dao::insert",
        )
        graph, diagnostics = build(source, [outside])
        assert [diagnostic.code for diagnostic in diagnostics] == [
            "DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED"
        ]
        entry = nodes_of(graph, NodeKind.ENTRY)[0]
        assert any(
            target == nodes_of(graph, NodeKind.MUTATION)[0].id
            for target, _ in successors(graph, entry.id)
        )


class TestBranches:
    def test_if_else_edges(self):
        source = "if (a) {\n  dao.insert(x)\n} else {\n  val y = 2\n}\nval z = 3\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        branch = nodes_of(graph, NodeKind.BRANCH)[0]
        outgoing_kinds = [kind for _, kind in successors(graph, branch.id)]
        assert EdgeKind.TRUE_BRANCH in outgoing_kinds
        assert EdgeKind.FALSE_BRANCH in outgoing_kinds

    def test_if_without_else_false_branch_goes_to_join(self):
        source = "if (a) {\n  val x = 1\n}\nval y = 2\n"
        graph, _ = build(source)
        branch = nodes_of(graph, NodeKind.BRANCH)[0]
        assert EdgeKind.FALSE_BRANCH in [
            kind for _, kind in successors(graph, branch.id)
        ]

    def test_when_branch_edges(self):
        source = "when (a) {\n  1 -> val x = 1\n  else -> val y = 2\n}\n"
        graph, _ = build(source)
        when_node = nodes_of(graph, NodeKind.WHEN)[0]
        when_edges = edge_pairs(graph, EdgeKind.WHEN_BRANCH)
        assert len([item for item in when_edges if item[0] == when_node.id]) == 2


class TestLoops:
    def test_loop_header_body_exit(self):
        source = "while (a) {\n  dao.insert(x)\n}\nval y = 2\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        header = nodes_of(graph, NodeKind.LOOP_HEADER)[0]
        outgoing_kinds = [kind for _, kind in successors(graph, header.id)]
        assert EdgeKind.LOOP_BODY in outgoing_kinds
        assert EdgeKind.LOOP_EXIT in outgoing_kinds

    def test_break_and_continue(self):
        source = "while (a) {\n  break\n  continue\n}\n"
        graph, _ = build(source)
        assert nodes_of(graph, NodeKind.BREAK)
        assert nodes_of(graph, NodeKind.CONTINUE)
        break_node = nodes_of(graph, NodeKind.BREAK)[0]
        continue_node = nodes_of(graph, NodeKind.CONTINUE)[0]
        assert EdgeKind.LOOP_EXIT in [kind for _, kind in successors(graph, break_node.id)]
        assert EdgeKind.NORMAL in [
            kind for _, kind in successors(graph, continue_node.id)
        ]


class TestExceptionFlow:
    def test_try_catch_finally(self):
        source = (
            "try {\n  dao.insert(x)\n} catch (e: E) {\n  val y = 1\n} finally {\n  val z = 2\n}\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        try_node = nodes_of(graph, NodeKind.TRY)[0]
        catch_node = nodes_of(graph, NodeKind.CATCH)[0]
        finally_node = nodes_of(graph, NodeKind.FINALLY)[0]
        assert (try_node.id, catch_node.id, EdgeKind.EXCEPTION) in edge_pairs(graph)
        assert (try_node.id, finally_node.id, EdgeKind.FINALLY) in edge_pairs(graph)
        assert (catch_node.id, finally_node.id, EdgeKind.FINALLY) in edge_pairs(graph)

    def test_throw_reaches_exceptional_exit(self):
        source = "if (a) {\n  throw e\n}\nval y = 1\n"
        graph, _ = build(source)
        exceptional = nodes_of(graph, NodeKind.EXIT_EXCEPTIONAL)
        assert len(exceptional) == 1
        throw_node = nodes_of(graph, NodeKind.THROW)[0]
        assert (throw_node.id, exceptional[0].id, EdgeKind.THROW_EXIT) in edge_pairs(graph)


class TestBarrierScope:
    def test_scope_outgoing_edge_is_unknown(self):
        source = "writeBarrier.runWrite {\n  dao.insert(x)\n}\nval y = 1\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        scope = nodes_of(graph, NodeKind.BARRIER_SCOPE)[0]
        outgoing = successors(graph, scope.id)
        assert outgoing
        assert all(kind is EdgeKind.UNKNOWN for _, kind in outgoing)
        assert not any(kind is EdgeKind.NORMAL for _, kind in outgoing)


class TestBarrierPlacementAdversarial:
    """Barrier/mutation in DIFFERENT model regions — the shapes where a CFG
    construction bug could fabricate or destroy the distinction GR-12's
    dominance decision depends on.  Model-content assertions only."""

    def test_barrier_in_else_mutation_after_join(self):
        source = (
            "if (a) {\n  val x = 1\n} else {\n  writeBarrier.runWrite {\n"
            "    val y = 2\n  }\n}\ndao.insert(z)\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        scope = nodes_of(graph, NodeKind.BARRIER_SCOPE)[0]
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        # The scope lives in the else subtree; the mutation is after the join.
        assert scope.span.end <= mutation.span.start
        branch = nodes_of(graph, NodeKind.BRANCH)[0]
        kinds = [kind for _, kind in successors(graph, branch.id)]
        assert EdgeKind.TRUE_BRANCH in kinds and EdgeKind.FALSE_BRANCH in kinds

    def test_barrier_in_then_mutation_after_join(self):
        source = (
            "if (a) {\n  writeBarrier.runWrite {\n    val y = 1\n  }\n"
            "} else {\n  val x = 2\n}\ndao.insert(z)\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        scope = nodes_of(graph, NodeKind.BARRIER_SCOPE)[0]
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        assert scope.span.end <= mutation.span.start
        branch = nodes_of(graph, NodeKind.BRANCH)[0]

        def reachable(start_id):
            seen = {start_id}
            frontier = [start_id]
            while frontier:
                for target, _ in successors(graph, frontier.pop()):
                    if target not in seen:
                        seen.add(target)
                        frontier.append(target)
            return seen

        true_reach = set()
        for target, kind in successors(graph, branch.id):
            if kind is EdgeKind.TRUE_BRANCH:
                true_reach |= reachable(target)
        false_reach = set()
        for target, kind in successors(graph, branch.id):
            if kind is EdgeKind.FALSE_BRANCH:
                false_reach |= reachable(target)
        # The scope is on the true path only; the mutation after the join is
        # reachable from BOTH paths — the distinction GR-12 must decide on.
        assert scope.id in true_reach
        assert scope.id not in false_reach
        assert mutation.id in true_reach
        assert mutation.id in false_reach

    def test_barrier_in_loop_body_mutation_after_loop(self):
        source = (
            "while (a) {\n  writeBarrier.runWrite {\n    val y = 1\n  }\n}\ndao.insert(z)\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        header = nodes_of(graph, NodeKind.LOOP_HEADER)[0]
        scope = nodes_of(graph, NodeKind.BARRIER_SCOPE)[0]
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        # The scope is inside the loop body; the mutation is after the loop.
        assert header.span.start <= scope.span.start < header.span.end
        assert mutation.span.start >= header.span.end

    def test_barrier_in_catch_mutation_in_try(self):
        source = (
            "try {\n  dao.insert(x)\n} catch (e: E) {\n  writeBarrier.runWrite {\n"
            "    val y = 1\n  }\n}\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        try_nodes = nodes_of(graph, NodeKind.TRY)
        catch_node = nodes_of(graph, NodeKind.CATCH)[0]
        scope = nodes_of(graph, NodeKind.BARRIER_SCOPE)[0]
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        # Mutation inside the try body region, scope inside the catch region.
        assert try_nodes[0].span.start <= mutation.span.start < try_nodes[0].span.end
        assert catch_node.span.start <= scope.span.start < catch_node.span.end
        assert (try_nodes[0].id, catch_node.id, EdgeKind.EXCEPTION) in edge_pairs(graph)

    def test_barrier_in_finally_mutation_in_try(self):
        source = (
            "try {\n  dao.insert(x)\n} finally {\n  writeBarrier.runWrite {\n"
            "    val y = 1\n  }\n}\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        try_nodes = nodes_of(graph, NodeKind.TRY)
        finally_node = nodes_of(graph, NodeKind.FINALLY)[0]
        scope = nodes_of(graph, NodeKind.BARRIER_SCOPE)[0]
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        assert try_nodes[0].span.start <= mutation.span.start < try_nodes[0].span.end
        assert finally_node.span.start <= scope.span.start < finally_node.span.end

    def test_barrier_in_uncalled_local_function_yields_no_model_content(self):
        from scripts.db_guard.structural_analysis.barrier_markers import (
            collect_barrier_markers,
        )
        from scripts.kotlin_callable_parser import mask_kotlin_source

        source = (
            "fun unused() {\n  writeBarrier.runWrite {\n    val z = 1\n  }\n}\n"
            "dao.insert(x)\n"
        )
        result = parse(source)
        assert not result.is_supported
        assert result.unsupported[0].reason == "local-function"
        masked = mask_kotlin_source(source)
        assert collect_barrier_markers(result, masked) == ()


class TestMarkerAttachment:
    def test_unknown_marker_becomes_barrier_call_node(self):
        source = "val a = 1\nplainCall(x)\n"
        # A text-scanned marker covers the CALL shape, a strict sub-span of
        # the statement region's span.
        marker_start = source.index("plainCall")
        marker = BarrierMarker(
            kind=BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL,
            span=SourceSpan(
                start=marker_start,
                end=marker_start + len("plainCall"),
                line=2,
                column=1,
            ),
            receiver_fqcn=None,
            method="checkWritesAllowed",
        )
        graph, diagnostics = build_callable_cfg(
            parse(source),
            (),
            (marker,),
            path="app/src/main/java/A.kt",
            callable_key="p|o|function|m|null|",
        )
        assert diagnostics == ()
        barrier_calls = nodes_of(graph, NodeKind.BARRIER_CALL)
        assert len(barrier_calls) == 1
        # The marker node is reachable in the normal flow.
        statement = [
            node for node in graph.nodes
            if node.kind is NodeKind.STATEMENT
            and node.span.start <= marker_start < node.span.end
        ]
        assert statement
        assert any(
            target == barrier_calls[0].id
            for target, _ in successors(graph, statement[0].id)
        )


class TestDeterminism:
    def test_two_builds_are_identical(self):
        source = (
            "if (a) {\n  writeBarrier.runWrite {\n    dao.insert(x)\n  }\n"
            "} else {\n  try {\n    dao.insert(x)\n  } finally {\n    val y = 1\n  }\n}\n"
        )
        first = build(source, [site_at(source, "dao.insert")])
        second = build(source, [site_at(source, "dao.insert")])
        assert first == second


def build_gated(source: str, sites=()):
    """Build a CFG with the opaque-lambda gate active over ``sites``."""
    import pytest
    from scripts.db_guard.structural_analysis.barrier_markers import (
        barrier_like_call_spans,
        lambda_opacity_predicate,
    )
    from scripts.db_guard.structural_analysis.tokenizer import (
        parse_callable_body,
    )
    from scripts.kotlin_callable_parser import mask_kotlin_source

    masked = mask_kotlin_source(source)
    predicate = lambda_opacity_predicate(
        masked, tuple(sites), barrier_like_call_spans(masked, 0, len(masked))
    )
    parse_result = parse_callable_body(
        masked,
        SourceSpan(0, len(masked), 1, 1),
        lambda_opacity_predicate=predicate,
    )
    return build_callable_cfg(
        parse_result,
        tuple(sites),
        (),
        path="app/src/main/java/A.kt",
        callable_key="p|o|function|m|null|",
    )


class TestReturnConstructFlow:
    """GR-12 extension 1: `return try/if/when ...` carries real flow."""

    def test_return_try_catch_exception_edges(self):
        source = (
            "return try {\n  dao.insert(x)\n} catch (e: E) {\n  val y = 1\n}\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        try_node = nodes_of(graph, NodeKind.TRY)[0]
        catch_node = nodes_of(graph, NodeKind.CATCH)[0]
        assert (try_node.id, catch_node.id, EdgeKind.EXCEPTION) in edge_pairs(graph)
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        entry = nodes_of(graph, NodeKind.ENTRY)[0]

        def reachable(start_id):
            seen = {start_id}
            frontier = [start_id]
            while frontier:
                for target, _ in successors(graph, frontier.pop()):
                    if target not in seen:
                        seen.add(target)
                        frontier.append(target)
            return seen

        assert mutation.id in reachable(entry.id)

    def test_return_try_mutation_in_catch_reachable_via_exception(self):
        source = (
            "return try {\n  val a = 1\n} catch (e: E) {\n  dao.insert(y)\n}\n"
        )
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        try_node = nodes_of(graph, NodeKind.TRY)[0]
        catch_node = nodes_of(graph, NodeKind.CATCH)[0]
        assert (try_node.id, catch_node.id, EdgeKind.EXCEPTION) in edge_pairs(graph)
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        assert mutation.span.start > catch_node.span.start

    def test_return_if_unbraced_branch_flow(self):
        source = "return if (a) dao.insert(x) else val y = 2\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        branch = nodes_of(graph, NodeKind.BRANCH)[0]
        kinds = [kind for _, kind in successors(graph, branch.id)]
        assert EdgeKind.TRUE_BRANCH in kinds
        assert EdgeKind.FALSE_BRANCH in kinds

    def test_val_when_branch_flow(self):
        source = "val id = when {\n  a -> dao.insert(x)\n  else -> val y = 2\n}\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        when_node = nodes_of(graph, NodeKind.WHEN)[0]
        kinds = [kind for _, kind in successors(graph, when_node.id)]
        assert EdgeKind.WHEN_BRANCH in kinds
        assert len(nodes_of(graph, NodeKind.MUTATION)) == 1

    def test_unbraced_if_mutation_maps_to_statement(self):
        source = "if (affected == 1) dao.insert(x)\nval z = 1\n"
        graph, diagnostics = build(source, [site_at(source, "dao.insert")])
        assert diagnostics == ()
        branch = nodes_of(graph, NodeKind.BRANCH)[0]
        mutation = nodes_of(graph, NodeKind.MUTATION)[0]
        # The mutation node hangs off its innermost owning statement inside
        # the branch subtree.
        incoming = [
            edge.source_node_id
            for edge in graph.edges
            if edge.target_node_id == mutation.id
        ]
        assert incoming

        def reachable(start_id):
            seen = {start_id}
            frontier = [start_id]
            while frontier:
                for target, _ in successors(graph, frontier.pop()):
                    if target not in seen:
                        seen.add(target)
                        frontier.append(target)
            return seen

        for source_id in incoming:
            if source_id in reachable(branch.id):
                break
        else:
            raise AssertionError("mutation owner not reachable from branch")


class TestOpaqueLambdaGateFlow:
    """GR-12 extension 3: opaque statement flow with the gate active."""

    def test_mutation_call_with_lambda_argument_maps(self):
        source = "exchangeRateDao.insertOrUpdateAll(rates.map { it.toEntity() })\nval z = 1\n"
        site = site_at(source, "exchangeRateDao")
        graph, diagnostics = build_gated(source, [site])
        assert diagnostics == ()
        assert len(nodes_of(graph, NodeKind.MUTATION)) == 1
        entry = nodes_of(graph, NodeKind.ENTRY)[0]
        assert successors(graph, entry.id)

    def test_site_inside_lambda_raises_invariant(self):
        source = "require(x.isNotEmpty()) {\n  dao.insert(y)\n}\n"
        site = site_at(source, "dao.insert")
        with pytest.raises(ValueError):
            build_gated(source, [site])

    def test_free_lambda_statement_keeps_sequential_flow(self):
        source = (
            "require(x.isNotEmpty()) {\n  val m = 1\n}\ndao.insert(y)\n"
        )
        site = site_at(source, "dao.insert")
        graph, diagnostics = build_gated(source, [site])
        assert diagnostics == ()
        assert len(nodes_of(graph, NodeKind.MUTATION)) == 1
        statements = [
            node for node in graph.nodes
            if node.kind is NodeKind.STATEMENT and node.span.start < site.span.start
        ]
        assert statements  # the require(...) statement is modeled
