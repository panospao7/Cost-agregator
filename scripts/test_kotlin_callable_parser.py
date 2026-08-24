"""Contract tests for the fail-closed Kotlin callable parser.

These tests exercise the real parser APIs (``mask_kotlin_source``,
``find_owner_declarations``, ``canonical_source_path``,
``parse_kotlin_file``, ``resolve_callable``) against temporary Kotlin
fixtures and assert exact ``FunctionSignature`` identities and status
codes -- never text markers.

Notes on intentional behavior asserted here:

- ``parse_kotlin_file`` reads the file through a repo-relative POSIX path
  (``canonical_source_path`` rejects absolute and Windows-style paths), so
  fixtures are written under ``tmp_path`` and parsed while the CWD points
  at ``tmp_path``.
- Function-type parameter syntax is normalized by the shared
  ``db_policy_signature`` grammar, including nested function types.
- A ``vararg`` parameter marker is part of the stored identity and therefore
  cannot collide with an ordinary parameter of the same underlying type.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import db_policy_signature as dbsig  # noqa: E402
import kotlin_callable_parser as parser  # noqa: E402


REL = "app/src/main/java/example/Fixture.kt"
CANONICAL = "app/src/main/java/example/Fixture.kt"

_MASK_MESSAGE = "kotlin callable parser error"


def _write(tmp_path: Path, source: str, relative: str = REL) -> Path:
    path = tmp_path / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    return path


@pytest.fixture
def parse_file(tmp_path, monkeypatch):
    """Parse a temporary Kotlin fixture via its repo-relative path."""
    monkeypatch.chdir(tmp_path)

    def _parse(source: str, relative: str = REL):
        _write(tmp_path, source, relative=relative)
        return parser.parse_kotlin_file(relative)

    return _parse


def _signature(*, owner, name, receiver=None, parameter_types=(), path=CANONICAL):
    return dbsig.FunctionSignature(
        canonical_path=path,
        owner_fqcn=owner,
        function_name=name,
        receiver=receiver,
        parameter_types=tuple(parameter_types),
    )


def _assert_declaration(
    declaration,
    *,
    owner,
    name,
    receiver=None,
    parameter_types=(),
    status="RESOLVED_EXACTLY",
    path=CANONICAL,
):
    expected = _signature(
        owner=owner,
        name=name,
        receiver=receiver,
        parameter_types=parameter_types,
        path=path,
    )
    assert declaration.signature == expected
    assert declaration.signature.canonical_path == path
    assert declaration.signature.owner_fqcn == owner
    assert declaration.signature.function_name == name
    assert declaration.signature.receiver == receiver
    assert declaration.signature.parameter_types == tuple(parameter_types)
    assert declaration.owner == owner
    assert declaration.status == status


# ---------------------------------------------------------------- masking


def test_mask_line_comment_keeps_newlines_and_offsets():
    source = "package x // line\"hidden\"\nclass V {}\n"
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    assert masked == "package x                \nclass V {}\n"
    assert "hidden" not in masked
    assert masked[source.index("class V"):].startswith("class V")
    assert masked[source.index("package x"):].startswith("package x")


def test_mask_nested_block_comment_keeps_newlines():
    source = "/* outer /* inner */ done */ class V {}\n"
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    assert masked == "                             class V {}\n"
    assert "inner" not in masked
    assert "done" not in masked


def test_mask_strings_escaped_quotes_and_char_literals():
    source = "\"esc \\\"quote\"\nval c = '\\''\nval a = 'x'\n"
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    assert masked == "             \nval c =     \nval a =    \n"
    assert "quote" not in masked


def test_mask_triple_quoted_string_keeps_newlines():
    source = "val t = \"\"\"line1\nline2\"\"\"\n"
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    assert masked == "val t =         \n        \n"
    assert "line1" not in masked
    assert "line2" not in masked


@pytest.mark.parametrize("source", [
    "\"unterminated string",
    "/* unterminated block",
    "val c = 'x",
])
def test_mask_unterminated_literals_fail_closed(source):
    with pytest.raises(parser.ParserError) as excinfo:
        parser.mask_kotlin_source(source)
    assert excinfo.value.code == "PARSER_ERROR"
    assert str(excinfo.value) == _MASK_MESSAGE


# -------------------------------------------------------------- owner scope


def test_owner_discovery_class_and_object_scope():
    source = """package example
class Fixture {
    fun ok(value: Int) {}
}
object Top {
    fun run(value: String) {}
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.name, o.status) for o in owners] == [
        ("example.Fixture", "Fixture", "RESOLVED_EXACTLY"),
        ("example.Top", "Top", "RESOLVED_EXACTLY"),
    ]


def test_owner_discovery_nested_scope_qualifies_parent():
    source = """package example
class Outer {
    object Companion {
        class Inner
    }
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.name) for o in owners] == [
        ("example.Outer", "Outer"),
        ("example.Outer.Companion", "Companion"),
        ("example.Outer.Companion.Inner", "Inner"),
    ]


def test_owner_discovery_bodyless_scope_is_unsupported():
    source = """package example
class Bodyless
object Top
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.name, o.status) for o in owners] == [
        ("example.Bodyless", "Bodyless", "SIGNATURE_UNSUPPORTED"),
        ("example.Top", "Top", "SIGNATURE_UNSUPPORTED"),
    ]
    for owner in owners:
        assert owner.body_start == owner.body_end


def test_owner_multiline_class_header_scope():
    source = """package example.pkg

class Outer<T,
    U> : Base<T> {
    fun inside(value: Int) {}
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.name, o.status) for o in owners] == [
        ("example.pkg.Outer", "Outer", "RESOLVED_EXACTLY"),
    ]


@pytest.mark.parametrize("scanner, opening, closing", [
    (parser._pairs, "(", ")"),
    (parser._body_end, "{", "}"),
])
def test_structural_nesting_limit_boundary_is_controlled(scanner, opening, closing):
    def _scan(text: str) -> int:
        # Contract difference: ``_pairs`` takes the opening delimiter as its
        # third positional argument, while ``_body_end`` takes the integer
        # nesting limit there (the brace is implied).  Passing the delimiter
        # string into ``_body_end`` would crash the ``len(stack) > limit``
        # comparison with TypeError instead of exercising the boundary.
        if scanner is parser._body_end:
            return scanner(text, 0)
        return scanner(text, 0, opening)

    text = opening * parser.MAX_DEPTH + closing * parser.MAX_DEPTH
    start = _scan(text)
    assert start == len(text) - 1

    too_deep = opening * (parser.MAX_DEPTH + 1) + closing * (parser.MAX_DEPTH + 1)
    with pytest.raises(parser.ParserError) as excinfo:
        _scan(too_deep)
    assert excinfo.value.code == "NESTING_TOO_DEEP"
    assert too_deep not in repr(excinfo.value)


def test_header_nesting_limit_boundary_is_controlled():
    exact = "(" * parser.MAX_DEPTH + ")" * parser.MAX_DEPTH + " {"
    brace, _ = parser._header_body_start(exact, 0, len(exact))
    assert brace == len(exact) - 1
    owner_brace, _ = parser._owner_body(exact, 0, len(exact))
    assert owner_brace == len(exact) - 1

    too_deep = "(" * (parser.MAX_DEPTH + 1) + ")" * (parser.MAX_DEPTH + 1) + " {"
    with pytest.raises(parser.ParserError) as excinfo:
        parser._header_body_start(too_deep, 0, len(too_deep))
    assert excinfo.value.code == "NESTING_TOO_DEEP"
    assert too_deep not in repr(excinfo.value)
    with pytest.raises(parser.ParserError) as excinfo:
        parser._owner_body(too_deep, 0, len(too_deep))
    assert excinfo.value.code == "NESTING_TOO_DEEP"
    assert too_deep not in repr(excinfo.value)


def test_nested_generic_and_function_depth_boundaries_keep_status(parse_file):
    generic = "List<" * 31 + "String" + ">" * 31
    declarations = parse_file(f"class Fixture {{ fun generic(value: {generic}) {{}} }}\n")
    assert declarations[0].signature.parameter_types == (generic,)

    function = "(" * 31 + "String" + ")" * 31 + " -> String"
    declarations = parse_file(f"class Fixture {{ fun callback(value: {function}) {{}} }}\n")
    assert declarations[0].signature.parameter_types == (function,)

    too_deep_function = "(" * 32 + "String" + ")" * 32 + " -> String"
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file(f"class Fixture {{ fun callback(value: {too_deep_function}) {{}} }}\n")
    assert excinfo.value.code == "NESTING_TOO_DEEP"
    assert too_deep_function not in repr(excinfo.value)


# ---------------------------------------------------------------- callables


def test_multiline_fun_header_signatures(parse_file):
    source = """package example
class Fixture {
    fun multi(
        first: List<String>,
        second: Array<Int>
    ): Boolean {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="multi",
        parameter_types=("List<String>", "Array<Int>"),
    )


def test_simple_and_nullable_signatures(parse_file):
    source = """package example
class Fixture {
    fun simple(amount: Int): Unit {}
    fun maybe(value: String?): String? {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="simple",
        parameter_types=("Int",),
    )
    _assert_declaration(
        declarations[1],
        owner="example.Fixture",
        name="maybe",
        parameter_types=("String?",),
    )
    assert declarations[0].signature != declarations[1].signature


def test_direct_callable_discovery_preserves_signature_identity():
    source = """package example
class Fixture {
    fun direct(value: Int) {}
}
"""
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(source, owner)

    assert len(declarations) == 1
    declaration = declarations[0]
    assert declaration.owner == owner.owner == "example.Fixture"
    assert declaration.signature == _signature(
        owner="example.Fixture",
        name="direct",
        parameter_types=("Int",),
        path="app/src/main/unknown.kt",
    )


def test_local_fun_inside_method_is_not_an_owner_callable(parse_file):
    source = """package example
class Fixture {
    fun outer(value: Int) {
        fun inner(value: Int) {}
    }
    fun inner(value: Int) {}
}
"""
    declarations = parse_file(source)
    assert [(d.signature.function_name, d.signature.parameter_types) for d in declarations] == [
        ("outer", ("Int",)),
        ("inner", ("Int",)),
    ]
    assert parser.resolve_callable(
        declarations, "example.Fixture", "inner", None, ("Int",)
    ) == "RESOLVED_EXACTLY"
    assert sum(d.signature.function_name == "inner" for d in declarations) == 1


def test_generic_and_array_signatures(parse_file):
    source = """package example
class Fixture {
    fun generic(value: Map<String, Int>?): Boolean {}
    fun arrays(first: Array<Int>, second: Array<Array<String>>): Boolean {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="generic",
        parameter_types=("Map<String, Int>?",),
    )
    _assert_declaration(
        declarations[1],
        owner="example.Fixture",
        name="arrays",
        parameter_types=("Array<Int>", "Array<Array<String>>"),
    )


def test_function_type_parameter_has_exact_signature(parse_file):
    source = """package example
class Fixture {
    fun callback(handler: (String) -> Int) {}
    fun nested(handler: Map<String, (Int) -> List<String>>) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    _assert_declaration(declarations[0], owner="example.Fixture", name="callback",
                        parameter_types=("(String) -> Int",))
    _assert_declaration(declarations[1], owner="example.Fixture", name="nested",
                        parameter_types=("Map<String, (Int) -> List<String>>",))
    assert declarations[0].signature.canonical() == (
        "app/src/main/java/example/Fixture.kt::example.Fixture#callback(\\(String\\) -> Int)"
    )


def test_empty_function_types_are_normalized_exactly():
    assert dbsig.normalize_type_text("() -> Unit") == "() -> Unit"
    assert dbsig.normalize_type_text("(  )\n -> Result") == "() -> Result"
    with pytest.raises(dbsig.SignatureError) as excinfo:
        dbsig.normalize_type_text("()")
    assert excinfo.value.code == "MISSING_ARROW"
    with pytest.raises(dbsig.SignatureError) as excinfo:
        dbsig.normalize_type_text("() ->")
    assert excinfo.value.code == "BAD_TYPE"
    with pytest.raises(dbsig.SignatureError) as excinfo:
        dbsig.normalize_type_text("List<>")
    assert excinfo.value.code == "EMPTY_GENERIC"


def test_empty_function_type_parameter_keeps_signature(parse_file):
    declarations = parse_file("""package example
class Result
class Fixture {
    fun callbacks(onDone: () -> Unit, onResult: () -> Result) {}
}
""")
    _assert_declaration(
        declarations[0], owner="example.Fixture", name="callbacks",
        parameter_types=("() -> Unit", "() -> Result"),
    )


def test_vararg_parameter_marker_is_preserved_and_distinct(parse_file):
    source = """package example
class Fixture {
    fun values(vararg items: String) {}
    fun values(items: String) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="values",
        parameter_types=("vararg String",),
    )
    _assert_declaration(declarations[1], owner="example.Fixture", name="values",
                        parameter_types=("String",))
    assert parser.resolve_callable(
        declarations, "example.Fixture", "values", None, ("vararg String",)
    ) == "RESOLVED_EXACTLY"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "values", None, ("String",)
    ) == "RESOLVED_EXACTLY"


def test_extension_receiver_signature_and_resolution(parse_file):
    source = """package example
class Fixture {
    fun List<String>?.firstOr(value: String): String? {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="firstOr",
        receiver="List<String>?",
        parameter_types=("String",),
    )
    assert parser.resolve_callable(
        declarations, "example.Fixture", "firstOr", "List<String>?", ("String",)
    ) == "RESOLVED_EXACTLY"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "firstOr", None, ("String",)
    ) == "SIGNATURE_UNSUPPORTED"


def test_receiver_overloads_remain_distinct(parse_file):
    source = """package example
class Fixture {
    fun String.report() {}
    fun Long.report() {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="report",
        receiver="String",
    )
    _assert_declaration(
        declarations[1],
        owner="example.Fixture",
        name="report",
        receiver="Long",
    )
    assert parser.resolve_callable(
        declarations, "example.Fixture", "report", "String", ()
    ) == "RESOLVED_EXACTLY"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "report", "Long", ()
    ) == "RESOLVED_EXACTLY"


# ---------------------------------------------------------------- resolution


def test_overload_exact_resolution(parse_file):
    source = """package example
class Fixture {
    fun load(id: Int) {}
    fun load(id: String) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    assert parser.resolve_callable(
        declarations, "example.Fixture", "load", None, ("Int",)
    ) == "RESOLVED_EXACTLY"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "load", None, ("String",)
    ) == "RESOLVED_EXACTLY"


def test_overload_duplicate_exact_is_ambiguous(parse_file):
    source = """package example
class Fixture {
    fun load(id: Int) {}
    fun load(id: Int) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    assert parser.resolve_callable(
        declarations, "example.Fixture", "load", None, ("Int",)
    ) == "AMBIGUOUS_OVERLOAD"


def test_overload_missing_and_param_mismatch_fail_closed(parse_file):
    source = """package example
class Fixture {
    fun load(id: Int) {}
}
"""
    declarations = parse_file(source)
    assert parser.resolve_callable(
        declarations, "example.Fixture", "load", None, ("Long",)
    ) == "SIGNATURE_UNSUPPORTED"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "load", None, ()
    ) == "SIGNATURE_UNSUPPORTED"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "missing", None, ()
    ) == "METHOD_MISSING"
    assert parser.resolve_callable(
        declarations, "example.Other", "load", None, ("Int",)
    ) == "METHOD_MISSING"


def test_expression_body_status_unsupported(parse_file):
    source = """package example
class Fixture {
    fun calculated(value: Int): Int = value + 1
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="calculated",
        parameter_types=("Int",),
        status="UNSUPPORTED_EXPRESSION_BODY",
    )
    assert parser.resolve_callable(
        declarations, "example.Fixture", "calculated", None, ("Int",)
    ) == "SIGNATURE_UNSUPPORTED"


def test_multiline_expression_header_is_not_truncated(parse_file):
    source = """package example
class Fixture {
    fun f(value: Int)
        : Int
        = if (value > 0) value else 0
    fun braced(value: Int)
        : Int
        { return value }
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 2
    _assert_declaration(declarations[0], owner="example.Fixture", name="f",
                        parameter_types=("Int",), status="UNSUPPORTED_EXPRESSION_BODY")
    _assert_declaration(declarations[1], owner="example.Fixture", name="braced",
                        parameter_types=("Int",))


def test_comparison_operators_in_body_do_not_break_parsing(parse_file):
    source = """package example
class Fixture {
    fun compare(a: Int, b: Int): Boolean {
        if (a < b && b > a) {
            return true
        }
        return a <= b
    }
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="compare",
        parameter_types=("Int", "Int"),
    )
    assert parser.resolve_callable(
        declarations, "example.Fixture", "compare", None, ("Int", "Int")
    ) == "RESOLVED_EXACTLY"


# ------------------------------------------------------------ canonical path


def test_canonical_path_acceptance():
    for good in (
        "app/src/main/java/example/Fixture.kt",
        "app/src/main/java/com/yourname/expensetracker/data/ExpenseDao.kt",
    ):
        assert parser.canonical_source_path(good) == good


def test_canonical_path_accepts_relative_path_object():
    relative = Path("app") / "src" / "main" / "java" / "example" / "Fixture.kt"
    assert parser.canonical_source_path(relative) == CANONICAL


@pytest.mark.parametrize(("path", "code"), [
    ("app/src/main/" + "/".join(["x"] * 30) + "/Fixture.kt", "PATH_TOO_DEEP"),
    ("app/src/main/" + "x" * 129 + "/Fixture.kt", "PATH_SEGMENT_TOO_LONG"),
    ("app/src/main/" + "x" * 500 + ".kt", "PATH_TOO_LONG"),
])
def test_canonical_path_bounds_have_exact_codes(path, code):
    with pytest.raises(parser.ParserError) as excinfo:
        parser.canonical_source_path(path)
    assert excinfo.value.code == code


@pytest.mark.parametrize("bad_path", [
    "outside/Fixture.kt",
    "app/src/test/java/example/Fixture.kt",
    "app/src/main/java/example/Fixture.txt",
    "app/src/main/java/../secret/Fixture.kt",
    "app//src/main/java/example/Fixture.kt",
    "C:/app/src/main/java/Fixture.kt",
    "/app/src/main/java/Fixture.kt",
    "app\\src\\main\\java\\example\\Fixture.kt",
])
def test_canonical_path_rejection_is_sanitized(bad_path):
    with pytest.raises(parser.ParserError) as excinfo:
        parser.canonical_source_path(bad_path)
    assert excinfo.value.code == "PARSER_ERROR"
    assert str(excinfo.value) == _MASK_MESSAGE
    assert bad_path not in repr(excinfo.value)


def test_parse_rejects_non_kt_canonical_path(parse_file):
    relative = "app/src/main/java/example/Fixture.txt"
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("class Fixture { fun ok() {} }\n", relative=relative)
    assert excinfo.value.code == "PARSER_ERROR"
    assert str(excinfo.value) == _MASK_MESSAGE


def test_parse_canonicalizes_before_reading_hostile_path(monkeypatch):
    opened = []

    def read_hook(self, *args, **kwargs):
        opened.append(str(self))
        raise AssertionError("rejected path was opened")

    monkeypatch.setattr(parser.Path, "read_text", read_hook)
    with pytest.raises(parser.ParserError) as excinfo:
        parser.parse_kotlin_file("../outside/app/src/main/java/Fixture.kt")
    assert excinfo.value.code == "PARSER_ERROR"
    assert opened == []


# --------------------------------------------------------------- type scope


def test_unresolved_type_fails_closed(parse_file):
    source = """package example
class Fixture {
    fun f(value: MissingType) {}
}
"""
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file(source)
    assert excinfo.value.code == "TYPE_UNRESOLVED"
    assert str(excinfo.value) == _MASK_MESSAGE
    assert "MissingType" not in repr(excinfo.value)


def test_typealias_resolves_to_target_type(parse_file):
    source = """package example
typealias Alias = String
class Fixture {
    fun f(value: Alias) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="f",
        parameter_types=("String",),
    )


def test_unqualified_nested_owner_type_beats_same_package_type(parse_file):
    declarations = parse_file("""package example
class Inner
class Outer {
    class Inner
    fun f(value: Inner) {}
}
""")
    declaration = next(item for item in declarations if item.signature.function_name == "f")
    _assert_declaration(declaration, owner="example.Outer", name="f",
                        parameter_types=("example.Outer.Inner",))


def test_truly_ambiguous_same_package_type_fails_closed(parse_file):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("""package example
class Shared
class Shared
class Outer {
    fun f(value: Shared) {}
}
""")
    assert excinfo.value.code == "TYPE_UNRESOLVED"
    assert "Shared" not in repr(excinfo.value)


def test_qualified_duplicate_type_fails_closed(parse_file):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("""package example
class Shared
class Shared
class Outer {
    fun f(value: example.Shared) {}
}
""")
    assert excinfo.value.code == "TYPE_UNRESOLVED"


def test_qualified_duplicate_nested_type_fails_closed(parse_file):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("""package example
class Outer {
    class Inner
}
class Outer {
    class Inner
}
class Fixture {
    fun f(value: example.Outer.Inner) {}
}
""")
    assert excinfo.value.code == "TYPE_UNRESOLVED"


@pytest.mark.parametrize(("type_text", "code"), [
    ("List<", "UNBALANCED_ANGLE"),
    ("List<" * 33 + "String" + ">" * 33, "NESTING_TOO_DEEP"),
    ("String" + "?" * 2, "DUPLICATE_NULLABLE"),
    ("List<" + ",".join(["String"] * 130) + ">", "TYPE_TOO_MANY_TOKENS"),
])
def test_signature_limits_and_delimiters_keep_exact_parser_codes(parse_file, type_text, code):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file(f"class Fixture {{ fun f(value: {type_text}) {{}} }}\n")
    assert excinfo.value.code == code
    assert str(excinfo.value) == _MASK_MESSAGE
    assert type_text not in repr(excinfo.value)


def test_imported_type_resolves_to_fqcn(parse_file):
    source = """package example
import java.math.BigDecimal
class Fixture {
    fun f(value: BigDecimal) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="f",
        parameter_types=("java.math.BigDecimal",),
    )


def test_import_alias_resolves_exactly(parse_file):
    declarations = parse_file("""package example
import java.math.BigDecimal as Money
class Fixture { fun f(value: Money) {} }
""")
    _assert_declaration(declarations[0], owner="example.Fixture", name="f",
                        parameter_types=("java.math.BigDecimal",))


def test_ambiguous_import_alias_fails_with_exact_status(parse_file):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("""package example
import a.Value as Alias
import b.Value as Alias
class Fixture { fun f(value: Alias) {} }
""")
    assert excinfo.value.code == "TYPE_UNRESOLVED"


def test_alias_cycle_fails_with_exact_status(parse_file):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("""package example
typealias First = Second
typealias Second = First
class Fixture { fun f(value: First) {} }
""")
    assert excinfo.value.code == "TYPE_UNRESOLVED"


# ------------------------------------------------------------ duplicate scope


def test_duplicate_class_scopes_keep_all_declarations(parse_file):
    source = """package example
class Outer {
    fun before(value: Int) {}
    class Inner {
        fun inside(value: String) {}
    }
    fun after(value: Long) {}
}
class Outer {
    fun duplicate(value: Boolean) {}
}
"""
    declarations = parse_file(source)
    assert [(d.owner, d.signature.function_name, d.signature.parameter_types) for d in declarations] == [
        ("example.Outer", "before", ("Int",)),
        ("example.Outer", "after", ("Long",)),
        ("example.Outer.Inner", "inside", ("String",)),
        ("example.Outer", "duplicate", ("Boolean",)),
    ]
    _assert_declaration(
        declarations[0],
        owner="example.Outer",
        name="before",
        parameter_types=("Int",),
    )
    _assert_declaration(
        declarations[3],
        owner="example.Outer",
        name="duplicate",
        parameter_types=("Boolean",),
    )
    assert parser.resolve_callable(
        declarations, "example.Outer", "duplicate", None, ("Boolean",)
    ) == "RESOLVED_EXACTLY"


def test_bodyless_owner_does_not_swallow_sibling_scope(parse_file):
    source = """package example
class Bodyless
class WithFun {
    fun ok(value: Int) {}
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.WithFun",
        name="ok",
        parameter_types=("Int",),
    )


# -------------------------------------------------------- fake decls / calls


def test_calls_and_fake_fun_in_comment_string_are_ignored(parse_file):
    source = """package example
// fun fakeInComment(x: Int) {}
class Fixture {
    val a = "fakeFun(1)"
    fun real(value: Int) {
        helper(1)
        repo.save(value)
    }
}
"""
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="real",
        parameter_types=("Int",),
    )


# ----------------------------------------------------------- sanitized errors


@pytest.mark.parametrize("hostile", [
    "unterminated /* raw-comment-payload",
    "class Leaked { fun value(x: Not<closed) {} }",
    "class Leaked { fun value(x: String & Int) {} }",
    "class Fixture { fun bad(value: MissingType) {} }",
])
def test_parser_errors_are_sanitized(parse_file, hostile):
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file(hostile)
    error = excinfo.value
    expected_codes = {
        "unterminated /* raw-comment-payload": "PARSER_ERROR",
        "class Leaked { fun value(x: Not<closed) {} }": "UNBALANCED_ANGLE",
        "class Leaked { fun value(x: String & Int) {} }": "UNSUPPORTED_TOKEN",
        "class Fixture { fun bad(value: MissingType) {} }": "TYPE_UNRESOLVED",
    }
    assert error.code == expected_codes[hostile]
    assert str(error) == _MASK_MESSAGE
    assert error.message == _MASK_MESSAGE
    assert hostile not in repr(error)
    assert "ParserError" not in str(error)


def test_source_decode_error_is_sanitized(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    path = tmp_path / REL
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"class Fixture { // user-secret-source\n\xff")
    with pytest.raises(parser.ParserError) as excinfo:
        parser.parse_kotlin_file(REL)
    error = excinfo.value
    assert error.code == "PARSER_ERROR"
    assert str(error) == _MASK_MESSAGE
    assert "user-secret-source" not in str(error)
    assert "user-secret-source" not in repr(error)
    assert "UnicodeDecodeError" not in repr(error)
