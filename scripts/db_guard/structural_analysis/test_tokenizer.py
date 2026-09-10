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
        # nested block is observed inside the if's braced body: the then-body
        # is one BLOCK region (the else binding is unambiguous), and the
        # inner plain block is a second child of that BLOCK.
        result = parse("if (a) {\n  val x = 1\n  {\n    val y = 2\n  }\n}\n")
        assert result.is_supported
        if_body = result.regions[0].children
        assert [child.kind for child in if_body] == [RegionKind.BLOCK]
        assert [child.kind for child in if_body[0].children] == [
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
        # GR-12 capability extension: a single-statement unbraced if body is
        # modeled conservatively (the statement becomes the branch body).
        result = parse("if (a) dao.insert(x)\n")
        assert result.is_supported
        if_region = result.regions[0]
        assert if_region.kind.value == "IF"
        assert len(if_region.children) == 1

    def test_unbraced_if_body_unparseable_stays_unsupported(self):
        result = parse("if (a) dao.insert(x\n")
        assert not result.is_supported

    def test_unbraced_if_without_body_fails_closed(self):
        result = parse("if (a)\n")
        assert not result.is_supported
        assert result.unsupported[0].reason in ("unbraced-if-body", "if-without-body")

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


def parse_with_predicate(body: str, site_starts=(), barrier_spans=()):
    """Parse with a soundness gate over the given site starts / barrier spans."""
    from scripts.db_guard.structural_analysis.barrier_markers import (
        lambda_opacity_predicate,
    )

    masked = mask_kotlin_source(body)
    assert len(masked) == len(body), "masking must preserve offsets"
    sites = tuple(
        type("S", (), {"span": SourceSpan(s, s + 1, 1, 1)})() for s in site_starts
    )
    predicate = lambda_opacity_predicate(masked, sites, tuple(barrier_spans))
    return parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), lambda_opacity_predicate=predicate)


class TestReturnConstructs:
    """GR-12 extension 1: `return try/if/when ...` construct returns."""

    def test_return_try_catch_supported(self):
        result = parse(
            "return try {\n  writeBarrier.checkWritesAllowed(op)\n  dao.insert(x)\n}"
            " catch (e: E) {\n  dao.insert(y)\n}\n"
        )
        assert result.is_supported
        assert kinds(result) == [RegionKind.RETURN]
        ret = result.regions[0]
        assert len(ret.children) == 1
        assert ret.children[0].kind == RegionKind.TRY
        body, catch = ret.children[0].children
        assert body.kind == RegionKind.TRY
        assert catch.kind == RegionKind.CATCH

    def test_return_try_catch_finally_supported(self):
        result = parse(
            "return try {\n  dao.insert(x)\n} catch (e: E) {\n  val a = 1\n}"
            " finally {\n  val b = 2\n}\n"
        )
        assert result.is_supported
        try_region = result.regions[0].children[0]
        assert [child.kind for child in try_region.children] == [
            RegionKind.TRY,
            RegionKind.CATCH,
            RegionKind.FINALLY,
        ]

    def test_return_if_else_unbraced_supported(self):
        result = parse("return if (a) dao.insert(x) else val y = 2\n")
        assert result.is_supported
        if_region = result.regions[0].children[0]
        assert if_region.kind == RegionKind.IF
        assert len(if_region.children) == 2

    def test_return_when_subjectless_supported(self):
        result = parse(
            "return when {\n  a -> dao.insert(x)\n  else -> val y = 2\n}\n"
        )
        assert result.is_supported
        when_region = result.regions[0].children[0]
        assert when_region.kind == RegionKind.WHEN
        assert len(when_region.children) == 2

    def test_return_try_with_mutation_inside_try_body(self):
        source = (
            "return try {\n  writeBarrier.checkWritesAllowed(op)\n  dao.insert(x)\n}"
            " catch (e: E) {\n  val a = 1\n}\n"
        )
        result = parse(source)
        assert result.is_supported
        start = source.index("dao.insert")
        direct_checks = [
            region for region in result.regions[0].children[0].children[0].children
            if region.kind == RegionKind.DIRECT_CHECK
        ]
        assert len(direct_checks) == 1
        assert direct_checks[0].span.start < start

    def test_return_try_with_mutation_inside_catch_body(self):
        result = parse(
            "return try {\n  val a = 1\n} catch (e: E) {\n  dao.insert(y)\n}\n"
        )
        assert result.is_supported
        catch_children = result.regions[0].children[0].children[1].children
        assert catch_children[0].kind == RegionKind.STATEMENT

    def test_return_try_unparseable_with_hidden_site_fails_closed(self):
        # The try body contains an unparseable construct; with a site inside,
        # the opacity gate must refuse the leaf fallback (fail closed).
        source = (
            "return try {\n  val a = if (b) dao.insert(x) else c(\n} catch (e: E) {\n  val y = 1\n}\n"
        )
        start = source.index("dao.insert")
        result = parse_with_predicate(source, site_starts=(start,))
        assert not result.is_supported

    def test_return_construct_without_hidden_content_falls_back_to_leaf(self):
        # Masked string literals leave an empty unbraced if body; with no
        # sites or barrier calls hidden, the historical leaf model stands.
        result = parse_with_predicate(
            'return if (isRaw) "RAW" else "TRANSIENT"\n', site_starts=()
        )
        assert result.is_supported
        assert kinds(result) == [RegionKind.RETURN]
        assert result.regions[0].children == ()

    def test_plain_return_expression_unchanged(self):
        result = parse("val x = 1\nreturn x\n")
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT, RegionKind.RETURN]
        assert result.regions[1].children == ()


class TestValConstructInitializers:
    """GR-12 extension: `val x = if/when/try ...` construct initializers."""

    def test_val_when_subjectless_supported(self):
        result = parse(
            "val id = when {\n  a -> dao.insert(x)\n  else -> val y = 2\n}\n"
        )
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT]
        assert result.regions[0].children[0].kind == RegionKind.WHEN

    def test_val_if_else_braced_supported(self):
        result = parse(
            "val b = if (cond) {\n  budget.copy(x = 1)\n} else budget\n"
        )
        assert result.is_supported
        assert result.regions[0].children[0].kind == RegionKind.IF

    def test_val_try_catch_supported(self):
        result = parse(
            "val r = try {\n  dao.insert(x)\n} catch (e: E) {\n  val a = 1L\n}\n"
        )
        assert result.is_supported
        assert result.regions[0].children[0].kind == RegionKind.TRY

    def test_val_if_string_branches_fall_back_to_leaf(self):
        result = parse_with_predicate(
            'val payloadMode = if (isRaw) "RAW" else "TRANSIENT"\n', site_starts=()
        )
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT]
        assert result.regions[0].children == ()

    def test_val_when_with_hidden_site_fails_closed_on_unparseable(self):
        source = "val id = when {\n  a -> dao.insert(x\n}\n"
        start = source.index("dao.insert")
        result = parse_with_predicate(source, site_starts=(start,))
        assert not result.is_supported


class TestUnbracedIfBodies:
    """GR-12 extension 2: single-statement unbraced if/else bodies."""

    def test_unbraced_if_with_mutation_body(self):
        result = parse("if (affected == 1) dao.insert(x)\n")
        assert result.is_supported
        if_region = result.regions[0]
        assert if_region.kind == RegionKind.IF
        assert if_region.children[0].kind == RegionKind.BLOCK
        assert if_region.children[0].children[0].kind == RegionKind.STATEMENT

    def test_unbraced_if_else_chain(self):
        result = parse(
            "if (a) dao.insert(x) else if (b) dao.insert(y) else dao.insert(z)\n"
        )
        assert result.is_supported
        if_region = result.regions[0]
        # then-branch + else-if region; the else-if carries its own two
        # branch bodies.
        assert len(if_region.children) == 2
        nested = if_region.children[1]
        assert nested.kind == RegionKind.IF
        assert len(nested.children) == 2

    def test_unbraced_else_if_chain(self):
        result = parse("if (a) {\n  val x = 1\n} else if (b) dao.insert(y)\n")
        assert result.is_supported
        if_region = result.regions[0]
        assert len(if_region.children) == 2
        assert if_region.children[1].kind == RegionKind.IF

    def test_unbraced_body_with_barrier_call(self):
        result = parse("if (a) writeBarrier.checkWritesAllowed(op)\n")
        assert result.is_supported
        assert if_region_barrier(result) is not None

    def test_ambiguous_dangling_else_fails_closed(self):
        result = parse("if (a) if (b) dao.insert(x) else dao.insert(y)\n")
        assert not result.is_supported
        assert result.unsupported[0].reason == "ambiguous-dangling-else"

    def test_multiline_unbraced_if_body_from_next_part(self):
        result = parse("if (affected == 1)\n  dao.insert(x)\nval z = 1\n")
        assert result.is_supported
        if_region = result.regions[0]
        assert if_region.kind == RegionKind.IF
        assert if_region.children[0].kind == RegionKind.BLOCK
        assert if_region.children[0].children[0].kind == RegionKind.STATEMENT


def if_region_barrier(parse_result):
    for region in parse_result.regions:
        if region.kind == RegionKind.IF:
            stack = list(region.children)
            while stack:
                item = stack.pop()
                if item.kind == RegionKind.DIRECT_CHECK:
                    return item
                stack.extend(item.children)
    return None


class TestOpaqueLambdaGate:
    """GR-12 extension 3: mutation-free argument lambdas may be opaque."""

    def test_require_style_lambda_supported_with_gate(self):
        result = parse_with_predicate(
            "require(x.isNotEmpty()) {\n  val m = 1\n}\n", site_starts=()
        )
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT]

    def test_mutation_inside_lambda_stays_lambda_escape(self):
        source = "require(x.isNotEmpty()) {\n  dao.insert(y)\n}\n"
        start = source.index("dao.insert")
        result = parse_with_predicate(source, site_starts=(start,))
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"

    def test_barrier_like_call_inside_lambda_stays_unsupported(self):
        # A barrier-shaped call inside a lambda body must never be modeled
        # opaque; the statement fails closed (via the barrier-check or
        # lambda-escape path depending on statement shape).
        source = "require(x.isNotEmpty()) {\n  writeBarrier.checkWritesAllowed(op)\n}\n"
        result = parse_with_predicate(
            source, site_starts=(), barrier_spans=((30, 70),)
        )
        assert not result.is_supported
        assert result.unsupported[0].code in (
            "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
            "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
        )

    def test_mutation_call_with_lambda_argument_supported(self):
        source = "exchangeRateDao.insertOrUpdateAll(rates.map { it.toEntity() })\n"
        start = source.index("exchangeRateDao")
        result = parse_with_predicate(source, site_starts=(start,))
        assert result.is_supported
        assert kinds(result) == [RegionKind.STATEMENT]

    def test_lambda_containing_second_mutation_keeps_failure(self):
        source = "helper(x.map { it.toEntity() })\ndao.insert(x)\n"
        site = source.index("dao.insert")
        # a site inside the lambda body (modeled as text offset in the lambda)
        inner = source.index("toEntity")
        result = parse_with_predicate(source, site_starts=(site, inner))
        assert not result.is_supported

    def test_no_predicate_keeps_strict_behavior(self):
        result = parse("require(x.isNotEmpty()) {\n  val m = 1\n}\n")
        assert not result.is_supported
        assert result.unsupported[0].code == "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE"
