"""Barrier-marker extraction tests (shadow-only syntax observation)."""
from __future__ import annotations

from scripts.db_guard.structural_analysis.barrier_markers import (
    collect_barrier_markers,
)
from scripts.db_guard.structural_analysis.model import BarrierMarkerKind
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
