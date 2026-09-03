"""Adversarial unit tests for the conservative statement-boundary tokenizer.

Fixtures are small inline Kotlin snippets, masked with the production
``mask_kotlin_source`` before parsing (the tokenizer's input contract).
Assertions cover the model CONTENT the parser preserves for GR-12 — never a
pass/fail dominance outcome, which GR-11 does not decide.
"""
from __future__ import annotations

import pytest

from scripts.kotlin_callable_parser import mask_kotlin_source

from scripts.db_guard.structural_analysis.model import (
    AnalysisStatus,
    BarrierMarkerKind,
    SourceSpan,
    SyntaxFamily,
)
from scripts.db_guard.structural_analysis.parser import (
    analyze_callable_body,
    classify_callable_body,
)
from scripts.db_guard.structural_analysis.tokenizer import (
    RegionKind,
    parse_callable_body,
)


def parse(body: str):
    masked = mask_kotlin_source(body)
    assert len(masked) == len(body), "masking must preserve offsets"
    return parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1))


def kinds(parse_result):
    return [region.kind for region in parse_result.regions]


class TestGraphShapeMatrix:
    def test_sequential_statements(self):
        result = parse("val x = 1\nval y = 2\ndao.insert(x)\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT] * 3

    def test_nested_blocks(self):
        # A leading brace pair is the callable's own body braces, so a plain
        # nested block is observed inside a control body instead.
        result = parse("if (a) {\n  val x = 1\n  {\n    val y = 2\n  }\n}\n")
        assert result.is_supported
        if_body = result.regions[0].children
        assert [child.kind for child in if_body] == [
            RegionKind.STATEMENT,
            RegionKind.BLOCK,
        ]

    def test_if_without_else(self):
        result = parse("if (a) {\n  dao.insert(x)\n}\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.IF]
        assert len(result.regions[0].children) == 1

    def test_if_else(self):
        result = parse("if (a) {\n  dao.insert(x)\n} else {\n  val y = 2\n}\n")
        assert result.is_supported
        if_region = result.regions[0]
        assert if_region.kind == RegionKind.IF
        assert len(if_region.children) == 2

    def test_else_if_chain(self):
        result = parse(
            "if (a) {\n  val x = 1\n} else if (b) {\n  val y = 2\n} else {\n  val z = 3\n}\n"
        )
        assert result.is_supported
        if_region = result.regions[0]
        assert if_region.kind == RegionKind.IF
        # then-block, nested else-if, else-block
        assert len(if_region.children) == 3

    def test_when_with_else(self):
        result = parse("when (a) {\n  1 -> dao.insert(x)\n  else -> val y = 2\n}\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.WHEN]
        assert len(result.regions[0].children) == 2

    def test_while_loop(self):
        result = parse("while (a) {\n  dao.insert(x)\n}\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.LOOP]
        assert len(result.regions[0].children) == 1

    def test_for_loop(self):
        result = parse("for (i in items) {\n  dao.insert(i)\n}\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.LOOP]

    def test_do_while(self):
        result = parse("do {\n  dao.insert(x)\n} while (a)\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.LOOP]

    def test_try_catch_finally(self):
        result = parse(
            "try {\n  dao.insert(x)\n} catch (e: E) {\n  val y = 1\n} finally {\n  val z = 2\n}\n"
        )
        assert result.is_supported
        try_region = result.regions[0]
        assert try_region.kind == RegionKind.TRY
        assert [child.kind for child in try_region.children] == [
            RegionKind.TRY,
            RegionKind.CATCH,
            RegionKind.FINALLY,
        ]

    def test_nested_try_finally(self):
        result = parse(
            "try {\n  try {\n    dao.insert(x)\n  } finally {\n    val y = 1\n  }\n} finally {\n  val z = 2\n}\n"
        )
        assert result.is_supported
        outer = result.regions[0]
        assert outer.kind == RegionKind.TRY
        # outer.children[0] is the outer try BODY wrapper; the inner merged
        # try/finally statement is its single child.
        outer_body = outer.children[0]
        assert outer_body.kind == RegionKind.TRY
        assert len(outer_body.children) == 1
        inner = outer_body.children[0]
        assert inner.kind == RegionKind.TRY
        assert [child.kind for child in inner.children] == [
            RegionKind.TRY,
            RegionKind.FINALLY,
        ]

    def test_jump_statements(self):
        result = parse("return\nthrow e\nbreak\ncontinue\n")
        assert result.is_supported
        assert kinds(result) == [
            RegionKind.RETURN,
            RegionKind.THROW,
            RegionKind.BREAK,
            RegionKind.CONTINUE,
        ]

    def test_clauses_on_their_own_lines(self):
        result = parse(
            "try {\n  dao.insert(x)\n}\ncatch (e: E) {\n  val y = 1\n}\nfinally {\n  val z = 2\n}\n"
        )
        assert result.is_supported
        assert [child.kind for child in result.regions[0].children] == [
            RegionKind.TRY,
            RegionKind.CATCH,
            RegionKind.FINALLY,
        ]

    def test_property_accessors(self):
        result = parse("get() {\n  return field\n}\nset(value) {\n  field = value\n}\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.ACCESSOR, RegionKind.ACCESSOR]


class TestMalformedInput:
    def test_unclosed_brace(self):
        result = parse("if (a) {\n  dao.insert(x)\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED"

    def test_stray_closing_brace(self):
        result = parse("val x = 1\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED"

    def test_unbalanced_paren(self):
        result = parse("if (a {\n  val x = 1\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED"

    def test_empty_body_is_supported_with_no_regions(self):
        result = parse("   \n  \n")
        assert result.is_supported
        assert result.regions == ()


class TestMaskedLookalikes:
    def test_comment_cannot_create_structure(self):
        source = "// if (a) { writeBarrier.runWrite { } }\nval x = 1\n"
        result = parse(source)
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT]

    def test_string_cannot_create_structure(self):
        source = 'val s = "if (a) { writeBarrier.runWrite { } }"\nval x = 1\n'
        result = parse(source)
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT, RegionKind.STATEMENT]

    def test_comment_cannot_create_barrier_marker(self):
        source = "// writeBarrier.checkWritesAllowed()\ndao.insert(x)\n"
        result = parse(source)
        assert result.is_supported
        for region in result.regions:
            assert region.barrier is None

    def test_string_cannot_create_barrier_marker(self):
        source = 'val s = "writeBarrier.runWrite"\ndao.insert(x)\n'
        result = parse(source)
        assert result.is_supported
        for region in result.regions:
            assert region.barrier is None


class TestConservativeUnsupported:
    def test_expression_body(self):
        result = parse("= foo(bar)\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED"
        assert result.unsupported[0].reason == "expression-body"

    def test_local_function(self):
        result = parse("fun helper() {\n  val y = 1\n}\nval x = 2\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED"
        assert result.unsupported[0].reason == "local-function"

    def test_anonymous_object(self):
        result = parse("val x = object : Foo() {\n  val y = 1\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED"
        assert result.unsupported[0].reason == "anonymous-object"

    @pytest.mark.parametrize("builder", ["launch", "async", "withContext", "runBlocking"])
    def test_coroutine_builders(self, builder):
        result = parse("%s {\n  dao.insert(x)\n}\n" % builder)
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
        assert result.unsupported[0].reason == "coroutine-builder"

    def test_lambda_in_unknown_call(self):
        result = parse("unknownCall({\n  dao.insert(x)\n})\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"

    def test_escaping_callback_lambda(self):
        result = parse("list.forEach { item ->\n  dao.insert(item)\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"

    def test_labelled_return(self):
        result = parse("return@loop\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
        assert result.unsupported[0].reason == "labelled-return"

    def test_labelled_break(self):
        result = parse("break@outer\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED"

    def test_elvis_block_form(self):
        result = parse("val x = foo() ?: {\n  val y = 1\n  y\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
        assert result.unsupported[0].reason == "elvis-block"

    def test_unbraced_if_body(self):
        result = parse("if (a) dao.insert(x)\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED"
        assert result.unsupported[0].reason == "unbraced-if-body"

    def test_try_without_catch_or_finally(self):
        result = parse("try {\n  dao.insert(x)\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED"

    def test_dangling_else(self):
        result = parse("val x = 1\nelse {\n  val y = 2\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED"


class TestBarrierObservation:
    def test_barrier_scope_candidate(self):
        result = parse("writeBarrier.runWrite {\n  dao.insert(x)\n}\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.BARRIER_SCOPE]
        assert result.regions[0].barrier is BarrierMarkerKind.DIRECT_SCOPE
        assert len(result.regions[0].children) == 1

    def test_direct_check_candidate(self):
        result = parse("writeBarrier.checkWritesAllowed()\ndao.insert(x)\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.DIRECT_CHECK, RegionKind.STATEMENT]
        assert result.regions[0].barrier is BarrierMarkerKind.DIRECT_CHECK

    def test_unknown_receiver_is_not_a_barrier(self):
        result = parse("otherBarrier.runWrite {\n  dao.insert(x)\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED"

    def test_lambda_in_barrier_check_is_rejected(self):
        result = parse("writeBarrier.checkWritesAllowed({ x -> x })\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"
        assert result.unsupported[0].reason == "lambda-in-barrier-check"

    def test_barrier_after_mutation_keeps_its_own_span(self):
        source = "dao.insert(x)\nwriteBarrier.runWrite {\n  val y = 1\n}\n"
        result = parse(source)
        assert result.is_supported
        mutation = result.regions[0]
        barrier = result.regions[1]
        assert mutation.kind == RegionKind.STATEMENT
        assert barrier.kind == RegionKind.BARRIER_SCOPE
        assert mutation.span.start < barrier.span.start

    def test_barrier_in_sibling_lambda_is_not_attached(self):
        source = (
            "list.forEach { writeBarrier.runWrite { val z = 1 } }\n"
            "dao.insert(x)\n"
        )
        result = parse(source)
        assert not result.is_supported
        # The forEach lambda is an escaping lambda; no supported barrier
        # region may be produced from inside it.
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"
        assert all(
            region.barrier is None for region in result.regions
        )


class TestDeterminismAndSafety:
    def test_same_input_parses_equally_twice(self):
        source = (
            "if (a) {\n  writeBarrier.runWrite {\n    dao.insert(x)\n  }\n"
            "} else {\n  try {\n    dao.insert(x)\n  } finally {\n    val y = 1\n  }\n}\n"
        )
        assert parse(source) == parse(source)

    def test_no_raw_source_in_parse_output(self):
        source = "dao.insert(secretValue)\nwriteBarrier.runWrite {\n  dao.insert(x)\n}\n"
        rendered = repr(parse(source))
        assert "dao.insert" not in rendered
        assert "secretValue" not in rendered


class TestClassification:
    def _classify(self, source: str, path: str = "app/src/main/java/A.kt"):
        masked = mask_kotlin_source(source)
        return analyze_callable_body(
            masked,
            SourceSpan(0, len(masked), 1, 1),
            path=path,
            callable_key="app/src/main/java/A.kt|com.example.A|function|write|null|",
        )

    def test_supported_classification_families(self):
        classification = self._classify(
            "if (a) {\n  dao.insert(x)\n} else {\n  val y = 2\n}\n"
            "while (b) {\n  val z = 3\n}\n"
        )
        assert classification.status is AnalysisStatus.SUPPORTED
        assert classification.diagnostics == ()
        assert classification.syntax_families == (
            SyntaxFamily.IF_ELSE,
            SyntaxFamily.LOOP,
        )

    def test_barrier_scope_counts_as_nested_lambda_family(self):
        classification = self._classify(
            "writeBarrier.runWrite {\n  dao.insert(x)\n}\n"
        )
        assert classification.status is AnalysisStatus.SUPPORTED
        assert classification.syntax_families == (SyntaxFamily.NESTED_LAMBDA,)

    def test_unsupported_classification_produces_closed_diagnostics(self):
        classification = self._classify("list.forEach { item ->\n  dao.insert(item)\n}\n")
        assert classification.status is AnalysisStatus.UNSUPPORTED_CONSERVATIVELY
        assert classification.syntax_families == ()
        assert len(classification.diagnostics) == 1
        diagnostic = classification.diagnostics[0]
        assert diagnostic.code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"
        assert diagnostic.path == "app/src/main/java/A.kt"
        assert diagnostic.line >= 1
        assert diagnostic.callable_key.endswith("|write|null|")

    def test_absolute_path_rejected(self):
        with pytest.raises(ValueError):
            self._classify(
                "list.forEach { item ->\n  dao.insert(item)\n}\n",
                path="/abs/A.kt",
            )

    def test_classification_is_deterministic(self):
        source = (
            "try {\n  dao.insert(x)\n} catch (e: E) {\n  val y = 1\n} finally {\n  val z = 2\n}\n"
        )
        assert self._classify(source) == self._classify(source)
