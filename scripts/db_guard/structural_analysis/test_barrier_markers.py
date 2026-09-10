"""Barrier-marker extraction tests (shadow-only syntax observation)."""
from __future__ import annotations

from scripts.db_guard.structural_analysis.barrier_markers import (
    barrier_like_call_spans,
    collect_barrier_markers,
    lambda_opacity_predicate,
)
from scripts.db_guard.structural_analysis.model import BarrierMarkerKind, SourceSpan
from scripts.db_guard.structural_analysis.test_tokenizer import parse


def markers_for(source: str):
    masked_source = None
    from scripts.kotlin_callable_parser import mask_kotlin_source

    masked_source = mask_kotlin_source(source)
    return collect_barrier_markers(parse(source), masked_source)


def kinds_found(source: str):
    return [marker.kind for marker in markers_for(source)]


class TestRecognizedRegions:
    def test_direct_check_region(self):
        found = kinds_found("writeBarrier.checkWritesAllowed()\ndao.insert(x)\n")
        assert found == [BarrierMarkerKind.DIRECT_CHECK]

    def test_barrier_scope_region(self):
        found = kinds_found("writeBarrier.runWrite {\n  dao.insert(x)\n}\n")
        assert found == [BarrierMarkerKind.DIRECT_SCOPE]

    def test_receiver_fqcn_stays_unresolved(self):
        for marker in markers_for("writeBarrier.runWrite {\n  val x = 1\n}\n"):
            assert marker.receiver_fqcn is None


class TestTextScan:
    def test_worker_guard_candidate(self):
        found = markers_for("workerExecutionGuard.runGuarded {\n  val x = 1\n}\n")
        assert [marker.kind for marker in found] == [
            BarrierMarkerKind.WORKER_GUARD_CANDIDATE
        ]
        assert found[0].method == "runGuarded"

    def test_worker_guard_with_context(self):
        found = kinds_found("guard.runGuardedWithContext(ctx) {\n  val x = 1\n}\n")
        assert found == [BarrierMarkerKind.WORKER_GUARD_CANDIDATE]

    def test_unknown_receiver_barrier_like(self):
        found = markers_for("otherBarrier.runWrite {\n  dao.insert(x)\n}\n")
        # The body is conservatively unsupported (BARRIER_FORM_UNRECOGNIZED),
        # but the unknown-receiver call shape stays recorded as explicit
        # uncertainty.
        assert [marker.kind for marker in found] == [
            BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL
        ]
        assert found[0].method == "runWrite"

    def test_canonical_receiver_in_unmodelable_region_stays_silent(self):
        source = (
            "list.forEach { writeBarrier.runWrite { val z = 1 } }\n"
            "val y = 2\n"
        )
        found = markers_for(source)
        assert found == ()

    def test_unknown_receiver_on_supported_body(self):
        source = "val x = 1\nfake.checkWritesAllowed()\n"
        found = markers_for(source)
        assert [marker.kind for marker in found] == [
            BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL
        ]
        assert found[0].method == "checkWritesAllowed"

    def test_no_double_report_inside_recognized_region(self):
        found = kinds_found("writeBarrier.runWrite {\n  dao.insert(x)\n}\n")
        assert found.count(BarrierMarkerKind.DIRECT_SCOPE) == 1
        assert BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL not in found


class TestConservativeSkipping:
    def test_no_text_markers_from_unsupported_body(self):
        source = (
            "list.forEach { writeBarrier.runWrite { val z = 1 } }\n"
            "workerGuard.runGuarded { val y = 2 }\n"
        )
        result = parse(source)
        assert not result.is_supported
        masked = __import__(
            "scripts.kotlin_callable_parser", fromlist=["mask_kotlin_source"]
        ).mask_kotlin_source(source)
        found = collect_barrier_markers(result, masked)
        # The recognized worker-guard region keeps its candidate marker; the
        # canonical writeBarrier receiver inside the unmodelable forEach
        # lambda stays silent; nothing unknown is invented.
        assert [marker.kind for marker in found] == [
            BarrierMarkerKind.WORKER_GUARD_CANDIDATE
        ]


class TestDeterminism:
    def test_sorted_and_stable(self):
        source = (
            "workerGuard.runGuarded {\n  val a = 1\n}\n"
            "writeBarrier.runWrite {\n  val b = 2\n}\n"
        )
        first = markers_for(source)
        second = markers_for(source)
        assert first == second
        starts = [marker.span.start for marker in first]
        assert starts == sorted(starts)


class TestOpaqueLambdaGate:
    """GR-12: the soundness gate for opaque-lambda modeling."""

    def test_barrier_like_call_spans_finds_canonical_and_like_shapes(self):
        source = (
            "writeBarrier.checkWritesAllowed(op)\n"
            "writeBarrier.runWrite {\n  val a = 1\n}\n"
            "foo.runWrite(x)\n"
            "workerGuard.runGuarded {\n  val b = 2\n}\n"
        )
        spans = barrier_like_call_spans(source, 0, len(source))
        assert spans
        joined = " | ".join(source[s:e] for s, e in spans)
        assert "checkWritesAllowed(" in joined
        assert "runWrite" in joined
        assert "runGuarded" in joined

    def test_gate_refuses_site_inside_brace_group(self):
        source = "require(x) {\n  dao.insert(y)\n}\n"
        site_start = source.index("dao.insert")
        predicate = lambda_opacity_predicate(
            source, (type("S", (), {"span": SourceSpan(site_start, site_start + 1, 1, 1)})(),),
            (),
        )
        assert predicate(0, len(source)) is False

    def test_gate_refuses_marker_overlapping_group(self):
        source = "require(x) {\n  foo.runWrite(y)\n}\n"
        marker_start = source.index("foo.runWrite")
        predicate = lambda_opacity_predicate(source, (), ((marker_start, marker_start + 12),))
        assert predicate(0, len(source)) is False

    def test_gate_allows_clean_lambda_groups(self):
        source = "require(x.isNotEmpty()) {\n  val m = 1\n}\n"
        predicate = lambda_opacity_predicate(source, (), ())
        assert predicate(0, len(source)) is True

    def test_gate_refuses_unbalanced_braces(self):
        source = "require(x) {\n  val m = 1\n"
        predicate = lambda_opacity_predicate(source, (), ())
        assert predicate(0, len(source)) is False

    def test_gate_is_deterministic(self):
        source = "require(x) {\n  val m = 1\n}\n"
        first = lambda_opacity_predicate(source, (), ())
        second = lambda_opacity_predicate(source, (), ())
        assert first(0, len(source)) == second(0, len(source))
