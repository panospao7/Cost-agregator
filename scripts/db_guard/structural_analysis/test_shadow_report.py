"""Shadow-report assembly and safety tests (shadow-only)."""
from __future__ import annotations

import json

import pytest

from scripts.kotlin_callable_parser import mask_kotlin_source

from scripts.db_guard.structural_analysis.barrier_markers import (
    collect_barrier_markers,
)
from scripts.db_guard.structural_analysis.model import AnalysisStatus, SourceSpan
from scripts.db_guard.structural_analysis.shadow_report import (
    analyze_callable_structurally,
    build_shadow_report,
)


def span_for(source: str) -> SourceSpan:
    return SourceSpan(0, len(source), 1, 1)


def analyze(source: str, sites=(), masked: str | None = None):
    return analyze_callable_structurally(
        masked if masked is not None else mask_kotlin_source(source),
        span_for(source),
        "app/src/main/java/A.kt",
        "app/src/main/java/A.kt|com.example.A|function|write|null|",
        mutation_sites=sites,
        barrier_marker_fn=collect_barrier_markers,
    )


class TestCallableAnalysis:
    def test_supported_callable(self):
        source = "writeBarrier.checkWritesAllowed()\ndao.insert(x)\n"
        result = analyze(source)
        assert result.status is AnalysisStatus.SUPPORTED
        assert result.graph is not None
        assert result.node_count > 0
        assert result.edge_count > 0
        assert result.diagnostics == ()

    def test_unsupported_callable(self):
        result = analyze("list.forEach { item ->\n  dao.insert(item)\n}\n")
        assert result.status is AnalysisStatus.UNSUPPORTED_CONSERVATIVELY
        assert result.graph is None
        assert result.node_count == 0

    def test_malformed_input_is_infrastructure(self):
        result = analyze_callable_structurally(
            "val x = 1\n",
            "not-a-span",
            "app/src/main/java/A.kt",
            "key",
        )
        assert result.status is AnalysisStatus.INFRASTRUCTURE_FAILURE
        assert result.diagnostics[0].code == "DB_STRUCTURAL_MODEL_REPORT_INVALID"


class TestReportAssembly:
    def _results(self):
        return [
            analyze("writeBarrier.checkWritesAllowed()\ndao.insert(x)\n"),
            analyze("list.forEach { item ->\n  dao.insert(item)\n}\n"),
            analyze_callable_structurally(
                "val x = 1\n", "not-a-span", "app/src/main/java/B.kt", "key-b"
            ),
        ]

    def test_summary_counts(self):
        report = build_shadow_report(self._results(), target_sha="a" * 40)
        assert report["schemaVersion"] == 1
        assert report["reportOnly"] is True
        assert report["summary"]["callableCount"] == 3
        assert report["summary"]["supportedCount"] == 1
        assert report["summary"]["unsupportedCount"] == 1
        assert report["summary"]["infrastructureFailureCount"] == 1

    def test_deterministic_across_builds(self):
        assert build_shadow_report(self._results()) == build_shadow_report(
            self._results()
        )

    def test_callables_sorted_by_key(self):
        report = build_shadow_report(self._results())
        keys = [entry["callableKey"] for entry in report["callables"]]
        assert keys == sorted(keys)

    def test_no_graph_payload_by_default(self):
        report = build_shadow_report(self._results())
        assert all("graph" not in entry for entry in report["callables"])

    def test_include_graphs_adds_payload(self):
        report = build_shadow_report(self._results(), include_graphs=True)
        with_graphs = [
            entry for entry in report["callables"] if "graph" in entry
        ]
        assert len(with_graphs) == 1
        assert with_graphs[0]["graph"]["nodes"]
        assert with_graphs[0]["graph"]["edges"]

    def test_no_raw_source_in_report(self):
        source = "writeBarrier.checkWritesAllowed()\ndao.insert(secretPayload)\n"
        report = build_shadow_report([analyze(source)])
        rendered = json.dumps(report)
        assert "dao.insert" not in rendered
        assert "secretPayload" not in rendered
        assert "writeBarrier" not in rendered

    def test_no_absolute_paths_in_report(self):
        report = build_shadow_report(self._results())
        rendered = json.dumps(report)
        assert "C:\\" not in rendered
        assert rendered.count("/abs/") == 0
