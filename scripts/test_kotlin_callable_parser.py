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
    """Empty-parameter function types keep their normalized spacing, and
    their components resolve through the SAME closed-world atom resolution
    as bare parameter types.

    Layering: normalization (spacing/grammar) is the shared
    ``db_policy_signature`` grammar's job; resolution is the parser's ordered
    nested-owner/package/import/builtin chain.  The project-local ``Result``
    therefore resolves to its package-qualified FQCN inside the function
    type exactly as a bare ``Result`` parameter would, while the builtin
    ``Unit`` keeps its simple name.
    """
    declarations = parse_file("""package example
class Result
class Fixture {
    fun callbacks(onDone: () -> Unit, onResult: () -> Result) {}
}
""")
    _assert_declaration(
        declarations[0], owner="example.Fixture", name="callbacks",
        parameter_types=("() -> Unit", "() -> example.Result"),
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
    """Syntax-only acceptance: app entries unchanged, non-app trees valid.

    ``canonical_source_path`` carries no topology knowledge: any
    repository-relative POSIX ``.kt`` path passes, including module trees
    outside ``app/src/main`` and non-production source sets.  Whether a
    path lives under a DECLARED production root is decided later by
    root-aware stages (``source_roots.is_declared_production_path``),
    never here.
    """
    for good in (
        # App entries: unchanged behavior.
        "app/src/main/java/example/Fixture.kt",
        "app/src/main/java/com/yourname/expensetracker/data/ExpenseDao.kt",
        # Non-app module trees are now syntactically valid.
        "feature/src/main/kotlin/com/example/Fixture.kt",
        "lib/core/src/main/java/com/example/CoreDao.kt",
        # Topology ignorance: non-main source sets and unknown roots pass
        # syntax too; membership is a later, root-aware concern.
        "app/src/test/java/example/Fixture.kt",
        "outside/module/Fixture.kt",
    ):
        assert parser.canonical_source_path(good) == good


def test_canonical_path_accepts_relative_path_object():
    relative = Path("app") / "src" / "main" / "java" / "example" / "Fixture.kt"
    assert parser.canonical_source_path(relative) == CANONICAL


def test_canonical_path_component_bound_is_exact():
    boundary = "/".join(["d"] * 15 + ["Fixture.kt"])
    assert len(boundary.split("/")) == parser.MAX_SOURCE_PATH_COMPONENTS
    assert parser.canonical_source_path(boundary) == boundary

    too_deep = "/".join(["d"] * 16 + ["Fixture.kt"])
    assert len(too_deep.split("/")) == parser.MAX_SOURCE_PATH_COMPONENTS + 1
    with pytest.raises(parser.ParserError) as excinfo:
        parser.canonical_source_path(too_deep)
    assert excinfo.value.code == "PATH_TOO_DEEP"
    assert str(excinfo.value) == _MASK_MESSAGE
    assert too_deep not in repr(excinfo.value)


def test_canonical_path_length_bound_is_exact():
    boundary = "d/" + "x" * 251 + ".kt"
    assert len(boundary) == parser.MAX_SOURCE_PATH_LENGTH
    assert parser.canonical_source_path(boundary) == boundary

    too_long = "d/" + "x" * 252 + ".kt"
    assert len(too_long) == parser.MAX_SOURCE_PATH_LENGTH + 1
    with pytest.raises(parser.ParserError) as excinfo:
        parser.canonical_source_path(too_long)
    assert excinfo.value.code == "PATH_TOO_LONG"
    assert str(excinfo.value) == _MASK_MESSAGE
    assert too_long not in repr(excinfo.value)


@pytest.mark.parametrize(("bad_path", "code"), [
    # One distinct controlled code per rejection class.
    ("", "PATH_EMPTY"),
    ("/abs/path/Fixture.kt", "PATH_ABSOLUTE"),
    ("//server/share/Fixture.kt", "PATH_UNC"),
    ("C:/repo/Fixture.kt", "PATH_DRIVE"),
    ("repo\\src\\Fixture.kt", "PATH_BACKSLASH"),
    # Backslash is classified before the drive prefix, deterministically.
    ("C:\\repo\\Fixture.kt", "PATH_BACKSLASH"),
    ("repo/../secret/Fixture.kt", "PATH_TRAVERSAL"),
    ("./repo/Fixture.kt", "PATH_DOT_SEGMENT"),
    ("repo/./src/Fixture.kt", "PATH_DOT_SEGMENT"),
    ("repo//src/Fixture.kt", "PATH_DOUBLE_SLASH"),
    # Trailing slash is classified before duplicate slash.
    ("repo/src/", "PATH_TRAILING_SLASH"),
    ("repo//", "PATH_TRAILING_SLASH"),
    ("repo/src/Fixture.txt", "PATH_NOT_KOTLIN"),
])
def test_canonical_path_rejection_classes_have_distinct_codes(bad_path, code):
    with pytest.raises(parser.ParserError) as excinfo:
        parser.canonical_source_path(bad_path)
    assert excinfo.value.code == code
    assert str(excinfo.value) == _MASK_MESSAGE
    # The empty string is a substring of every repr, so the no-echo
    # assertion is only meaningful for non-empty payloads.  For the
    # PATH_EMPTY case the masked-message assertion above already proves
    # no payload text is carried.
    if bad_path:
        assert bad_path not in repr(excinfo.value)


@pytest.mark.parametrize("not_text", [None, 123, b"repo/Fixture.kt", ["repo/Fixture.kt"]])
def test_canonical_path_non_text_input_fails_closed(not_text):
    with pytest.raises(parser.ParserError) as excinfo:
        parser.canonical_source_path(not_text)
    assert excinfo.value.code == "PATH_NOT_TEXT"
    assert str(excinfo.value) == _MASK_MESSAGE
    assert "Fixture" not in repr(excinfo.value)


def test_parse_rejects_non_kt_canonical_path(parse_file):
    relative = "app/src/main/java/example/Fixture.txt"
    with pytest.raises(parser.ParserError) as excinfo:
        parse_file("class Fixture { fun ok() {} }\n", relative=relative)
    assert excinfo.value.code == "PATH_NOT_KOTLIN"
    assert str(excinfo.value) == _MASK_MESSAGE


def test_parse_canonicalizes_before_reading_hostile_path(monkeypatch):
    opened = []

    def read_hook(self, *args, **kwargs):
        opened.append(str(self))
        raise AssertionError("rejected path was opened")

    monkeypatch.setattr(parser.Path, "read_text", read_hook)
    with pytest.raises(parser.ParserError) as excinfo:
        parser.parse_kotlin_file("../outside/app/src/main/java/Fixture.kt")
    assert excinfo.value.code == "PATH_TRAVERSAL"
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
    # ``List<`` breaks STRUCTURAL pairing before the signature grammar runs:
    # the parser's delimiter scanners (_pairs/_body_end) track ``<``/``>`` as
    # paired delimiters, so an unbalanced angle in the SOURCE fails closed
    # earlier with MALFORMED_SOURCE, which ParserError sanitizes to the fixed
    # generic PARSER_ERROR code.  The signature layer's UNBALANCED_ANGLE stays
    # reachable and pinned at its own boundary (see
    # ``test_signature_layer_unbalanced_angle_code`` below).
    ("List<", "PARSER_ERROR"),
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


def test_signature_layer_unbalanced_angle_code():
    """Pin UNBALANCED_ANGLE at the layer that owns it.

    The controlled UNBALANCED_ANGLE code is produced by the shared signature
    grammar (``db_policy_signature.normalize_type_text``) for type TEXT with
    unbalanced generic delimiters; the callable parser adopts it verbatim
    whenever signature validation is reached.  End-to-end sources whose
    angles break structural pairing are rejected earlier (sanitized generic
    PARSER_ERROR) — see ``test_signature_limits_and_delimiters_keep_exact_parser_codes``.
    """
    with pytest.raises(dbsig.SignatureError) as excinfo:
        dbsig.normalize_type_text("List<")
    assert excinfo.value.code == "UNBALANCED_ANGLE"
    assert excinfo.value.message == "unbalanced generic delimiters"


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


# ------------------------------------------------- tolerant type resolution
#
# PR-GR-05 Slice 3 narrow repair: exactly ONE failure family (closed-world
# type-resolution fatality) becomes per-declaration tolerable behind an
# explicit keyword-only flag.  The default OFF pins the scanner/evidence
# fail-closed behavior byte-for-byte.


_TOLERANT_FIXTURE_SOURCE = """package example
class Fixture {
    fun a(unresolvable: ProjectType) {}
    fun b(x: String) {}
}
"""


def _tolerant_fixture_declarations():
    owner = parser.find_owner_declarations(_TOLERANT_FIXTURE_SOURCE)[0]
    return parser.find_callable_declarations(
        _TOLERANT_FIXTURE_SOURCE, owner, tolerate_unresolved_types=True
    )


def test_tolerant_discovery_retains_unresolved_and_finds_sibling():
    """The unresolved declaration is retained (not skipped) under its
    explicit status while its resolvable sibling is still discovered."""
    declarations = _tolerant_fixture_declarations()
    assert [(d.signature.function_name, d.status) for d in declarations] == [
        ("a", "TYPE_UNRESOLVED"),
        ("b", "RESOLVED_EXACTLY"),
    ]
    # Retained signature = parsed-with-simple-names, faithful to source.
    unresolved = declarations[0]
    assert unresolved.owner == "example.Fixture"
    assert unresolved.signature.function_name == "a"
    assert unresolved.signature.parameter_types == ("ProjectType",)
    assert unresolved.signature.receiver is None
    # The sibling is discovered exactly as in a clean file.
    _assert_declaration(
        declarations[1],
        owner="example.Fixture",
        name="b",
        parameter_types=("String",),
        path="app/src/main/unknown.kt",
    )


def test_tolerant_retained_signature_keeps_every_parameter_in_order():
    """Reconstruction after a mid-signature failure keeps ALL parameters."""
    source = """package example
class Fixture {
    fun mixed(first: ProjectType, second: Int, third: List<String>) {}
}
"""
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, tolerate_unresolved_types=True
    )
    assert len(declarations) == 1
    assert declarations[0].status == "TYPE_UNRESOLVED"
    assert declarations[0].signature.parameter_types == (
        "ProjectType",
        "Int",
        "List<String>",
    )


def test_strict_discovery_still_raises_type_unresolved_exactly():
    """Default (and explicit False) aborts exactly as before the repair."""
    owner = parser.find_owner_declarations(_TOLERANT_FIXTURE_SOURCE)[0]
    with pytest.raises(parser.ParserError) as excinfo:
        parser.find_callable_declarations(_TOLERANT_FIXTURE_SOURCE, owner)
    assert excinfo.value.code == "TYPE_UNRESOLVED"
    assert str(excinfo.value) == _MASK_MESSAGE
    assert "ProjectType" not in repr(excinfo.value)
    with pytest.raises(parser.ParserError) as excinfo:
        parser.find_callable_declarations(
            _TOLERANT_FIXTURE_SOURCE, owner, tolerate_unresolved_types=False
        )
    assert excinfo.value.code == "TYPE_UNRESOLVED"


def test_resolve_callable_never_treats_unresolved_declaration_as_candidate():
    """Disambiguation over tolerant results ignores unresolved decls."""
    declarations = _tolerant_fixture_declarations()
    # The resolvable sibling resolves despite the unresolved declaration.
    assert parser.resolve_callable(
        declarations, "example.Fixture", "b", None, ("String",)
    ) == "RESOLVED_EXACTLY"
    # The unresolved declaration can never act as an exactly-resolved
    # candidate -- not even against its own simple-name spelling.
    assert parser.resolve_callable(
        declarations, "example.Fixture", "a", None, ("ProjectType",)
    ) == "SIGNATURE_UNSUPPORTED"
    # Wrong-hint queries over the same-name group still fail closed.
    assert parser.resolve_callable(
        declarations, "example.Fixture", "b", None, ("Long",)
    ) == "SIGNATURE_UNSUPPORTED"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "missing", None, ()
    ) == "METHOD_MISSING"


def test_tolerant_mode_retains_unresolved_receiver_simple_name():
    """Receiver resolution failures are retained in the same family."""
    source = """package example
class Fixture {
    fun ext(value: Int) {}
    fun ProjectType.ext(value: Int) {}
}
"""
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, tolerate_unresolved_types=True
    )
    assert [(d.signature.function_name, d.status) for d in declarations] == [
        ("ext", "RESOLVED_EXACTLY"),
        ("ext", "TYPE_UNRESOLVED"),
    ]
    assert declarations[1].signature.receiver == "ProjectType"
    # Only the exactly-resolved null-receiver declaration is selectable.
    assert parser.resolve_callable(
        declarations, "example.Fixture", "ext", None, ("Int",)
    ) == "RESOLVED_EXACTLY"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "ext", "ProjectType", ("Int",)
    ) == "SIGNATURE_UNSUPPORTED"


def test_tolerant_mode_keeps_other_failure_families_fatal():
    """Signature-grammar failures are a different family: still fatal."""
    source = "class Fixture { fun bad(value: String & Int) {} }\n"
    owner = parser.find_owner_declarations(source)[0]
    with pytest.raises(parser.ParserError) as excinfo:
        parser.find_callable_declarations(
            source, owner, tolerate_unresolved_types=True
        )
    assert excinfo.value.code == "UNSUPPORTED_TOKEN"
    assert str(excinfo.value) == _MASK_MESSAGE


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
    # ``Not<closed`` breaks STRUCTURAL delimiter pairing (the parser's
    # scanners track ``<``/``>`` as paired delimiters), so the source is
    # rejected before signature validation with MALFORMED_SOURCE, which
    # ParserError sanitizes to the fixed generic PARSER_ERROR code.  The
    # signature layer's UNBALANCED_ANGLE remains pinned at its own boundary
    # by ``test_signature_layer_unbalanced_angle_code``.
    expected_codes = {
        "unterminated /* raw-comment-payload": "PARSER_ERROR",
        "class Leaked { fun value(x: Not<closed) {} }": "PARSER_ERROR",
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


# ------------------------------------------------- GR-07: template-aware mask
#
# ``${...}`` template expressions are part of their enclosing literal: a
# quote or nested template inside the expression can neither terminate the
# literal early nor leak structural braces into the masked output.


def test_mask_string_template_with_embedded_quotes_stays_masked():
    """A quote inside ``${...}`` no longer terminates the literal: the whole
    template expression masks as literal text with zero structural leakage."""
    source = 'val s = "a ${x.replace("\\"", "")} b"\n'
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    # Offsets and the code prefix are untouched; the literal region is blank.
    assert masked.startswith("val s = ")
    assert set(masked[8:-1]) <= {" "}
    assert masked.endswith("\n")
    # No structural delimiter from inside the template survives.
    for structural in "{}()\"":
        assert structural not in masked[8:-1]


def test_mask_nested_template_inside_template_expression():
    """A template inside a nested string inside a template (the real
    JSON-building shape ``"...joinToString { \\"${it}\\"" }..."``) unwinds its
    context stack exactly: nothing leaks as structural source."""
    source = 'val j = "[${items.joinToString { "\\"${it}\\"" }}]"\n'
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    assert masked.startswith("val j = ")
    assert set(masked[8:-1]) <= {" "}
    for structural in "{}[]\"$":
        assert structural not in masked[8:-1]


def test_mask_simple_template_output_is_flat_blanked():
    """Sources without embedded quotes mask byte-for-byte like the previous
    non-template-aware masker: template code stays blanked, never exposed."""
    source = 'val s = "${x}"\n'
    masked = parser.mask_kotlin_source(source)
    assert masked == 'val s = ' + ' ' * 6 + '\n'


def test_mask_triple_quoted_string_with_template_and_quotes():
    """Triple-quoted literals track ``${...}`` the same way: braces and
    quotes of embedded JSON-shaped text never leak."""
    source = 'val t = """json: {"k": "${v()}"}"""\n'
    masked = parser.mask_kotlin_source(source)
    assert len(masked) == len(source)
    assert masked.startswith("val t = ")
    assert set(masked[8:-1]) <= {" "}


@pytest.mark.parametrize("source", [
    'val s = "${x',
    'val s = "${x"',
])
def test_mask_unterminated_template_fails_closed(source):
    """A ``${`` that never closes leaves the literal end unknowable: the
    masker fails closed with the fixed sanitized parser error."""
    with pytest.raises(parser.ParserError) as excinfo:
        parser.mask_kotlin_source(source)
    assert excinfo.value.code == "PARSER_ERROR"
    assert str(excinfo.value) == _MASK_MESSAGE


def test_parse_kotlin_file_json_builder_with_template_quotes_resolves(parse_file):
    """The real failing repository shape (an ``appendLine`` of JSON text with
    escaped quotes AND string templates on one line) parses: the member is
    discovered RESOLVED_EXACTLY instead of the file mis-masking."""
    source = '''package example
class JsonBuilder {
    fun merchantLine(merchant: String): String {
        appendLine("      \\"merchant\\": \\"${merchant.replace("\\"", "\\\\\\"")}\\",")
        return "done"
    }
}
'''
    declarations = parse_file(source)
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.JsonBuilder",
        name="merchantLine",
        parameter_types=("String",),
    )
    assert declarations[0].body is not None


# ------------------------------------------- GR-07 step A: project type index
#
# The closed-world resolver only saw same-file declarations, so a parameter
# or receiver type declared in ANOTHER production file (legal Kotlin when
# the packages match or an import exists) failed TYPE_UNRESOLVED and, in the
# D4 scanner, degraded the whole file to DB_SIGNATURE_UNRESOLVED.  The
# project-wide index extends resolution as the LAST fallback: unique simple
# name -> its package-qualified FQCN; ambiguous simple name -> still fails
# closed.  Same-file scopes, package, imports, aliases, and builtins keep
# precedence, so every pre-index resolution is unchanged.


def _index_from_sources(*sources: str) -> parser.ProjectTypeIndex:
    """Mirror of the declaration_scanner builder over in-memory sources."""
    by_simple: dict[str, set[str]] = {}
    qualified: set[str] = set()
    for source in sources:
        for fqcn in parser.project_type_declarations(source):
            qualified.add(fqcn)
            by_simple.setdefault(fqcn.rsplit(".", 1)[-1], set()).add(fqcn)
    return parser.ProjectTypeIndex(
        by_simple_name={
            name: tuple(sorted(fqcns))
            for name, fqcns in sorted(by_simple.items())
        },
        qualified=frozenset(qualified),
    )


_FILE_A = """package example
class Fixture {
    fun save(expense: Expense) {}
}
"""

_FILE_B = """package example
data class Expense(val id: Int)
"""


def test_project_type_declarations_extracts_top_level_types_only():
    source = """package com.example.app
class Plain
data class DataHolder(val id: Int)
interface Contract
fun interface FunContract { fun act() }
enum class Mode { ON }
annotation class Marker
object Singleton
class Outer {
    class Nested
    interface InnerContract
}
"""
    assert parser.project_type_declarations(source) == (
        "com.example.app.Contract",
        "com.example.app.DataHolder",
        "com.example.app.FunContract",
        "com.example.app.Marker",
        "com.example.app.Mode",
        "com.example.app.Outer",
        "com.example.app.Plain",
        "com.example.app.Singleton",
    )


def test_project_type_declarations_without_package_uses_bare_name():
    assert parser.project_type_declarations("class Bare\n") == ("Bare",)


def test_project_index_resolves_cross_file_type_end_to_end():
    """Type declared in file B, used in file A's signature: resolves
    RESOLVED_EXACTLY to its package-qualified FQCN."""
    index = _index_from_sources(_FILE_A, _FILE_B)
    owner = parser.find_owner_declarations(_FILE_A)[0]
    declarations = parser.find_callable_declarations(
        _FILE_A, owner, project_types=index
    )
    assert len(declarations) == 1
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="save",
        parameter_types=("example.Expense",),
        path="app/src/main/unknown.kt",
    )
    assert declarations[0].status == "RESOLVED_EXACTLY"
    assert parser.resolve_callable(
        declarations, "example.Fixture", "save", None, ("example.Expense",)
    ) == "RESOLVED_EXACTLY"


def test_project_index_generic_containment_resolves():
    """Generic arguments recurse through the index: List<Expense> resolves
    Expense inside the generic."""
    source = """package example
class Fixture {
    fun all(expenses: List<Expense>): Map<String, List<Expense>> {
        return mapOf()
    }
}
"""
    index = _index_from_sources(source, _FILE_B)
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, project_types=index
    )
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="all",
        parameter_types=("List<example.Expense>",),
        path="app/src/main/unknown.kt",
    )


def test_project_index_receiver_and_nullable_forms_resolve():
    source = """package example
class Fixture {
    fun Expense.persist(): Boolean = true
    fun maybe(value: Expense?) {}
}
"""
    index = _index_from_sources(source, _FILE_B)
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, project_types=index
    )
    assert [(d.signature.function_name, d.signature.receiver,
             d.signature.parameter_types) for d in declarations] == [
        # ``Boolean`` is persist's RETURN type; its only value channel is
        # the receiver.
        ("persist", "example.Expense", ()),
        ("maybe", None, ("example.Expense?",)),
    ]


def test_project_index_ambiguous_simple_name_fails_closed():
    """The same simple name declared in two packages is honest debt: strict
    discovery raises TYPE_UNRESOLVED and tolerant mode retains the
    declaration under that exact status -- never a guessed resolution."""
    index = parser.ProjectTypeIndex(
        by_simple_name={"Expense": ("a.Expense", "b.Expense")},
        qualified=frozenset({"a.Expense", "b.Expense"}),
    )
    owner = parser.find_owner_declarations(_FILE_A)[0]
    with pytest.raises(parser.ParserError) as excinfo:
        parser.find_callable_declarations(_FILE_A, owner, project_types=index)
    assert excinfo.value.code == "TYPE_UNRESOLVED"
    retained = parser.find_callable_declarations(
        _FILE_A, owner, tolerate_unresolved_types=True, project_types=index
    )
    assert [(d.signature.function_name, d.status) for d in retained] == [
        ("save", "TYPE_UNRESOLVED"),
    ]
    # The retained spelling stays the faithful source simple name.
    assert retained[0].signature.parameter_types == ("Expense",)


def test_project_index_unknown_name_still_fails_closed():
    index = _index_from_sources(_FILE_B)
    owner = parser.find_owner_declarations(_FILE_A.replace(
        "Expense", "Missing"))[0]
    with pytest.raises(parser.ParserError) as excinfo:
        parser.find_callable_declarations(
            _FILE_A.replace("Expense", "Missing"), owner,
            project_types=index,
        )
    assert excinfo.value.code == "TYPE_UNRESOLVED"


def test_same_file_import_alias_builtin_precede_index_entries():
    """Resolution order: same-file declaration > import > alias > builtin >
    project index.  Each parameter below is resolvable WITHOUT the index;
    the index deliberately carries conflicting foreign spellings that must
    all lose."""
    foreign = parser.ProjectTypeIndex(
        by_simple_name={
            "Local": ("foreign.pkg.Local",),
            "String": ("foreign.pkg.String",),
            "Imported": ("foreign.pkg.Imported",),
            "Aliased": ("foreign.pkg.Aliased",),
        },
        qualified=frozenset({
            "foreign.pkg.Local", "foreign.pkg.String",
            "foreign.pkg.Imported", "foreign.pkg.Aliased",
        }),
    )
    source = """package example
import other.pkg.Imported
typealias Aliased = example.AliasTarget
class Local
class AliasTarget
class Fixture {
    fun ordered(first: Local, second: Imported, third: Aliased, fourth: String) {}
}
"""
    owner = next(o for o in parser.find_owner_declarations(source)
                 if o.owner == "example.Fixture")
    declarations = parser.find_callable_declarations(
        source, owner, project_types=foreign
    )
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="ordered",
        parameter_types=(
            "example.Local",
            "other.pkg.Imported",
            "example.AliasTarget",
            "String",
        ),
        path="app/src/main/unknown.kt",
    )


def test_same_file_declaration_beats_foreign_index_entry():
    source = """package example
class Expense
class Fixture {
    fun local(value: Expense) {}
}
"""
    foreign = parser.ProjectTypeIndex(
        by_simple_name={"Expense": ("foreign.pkg.Expense",)},
        qualified=frozenset({"foreign.pkg.Expense"}),
    )
    owner = next(o for o in parser.find_owner_declarations(source)
                 if o.owner == "example.Fixture")
    declarations = parser.find_callable_declarations(
        source, owner, project_types=foreign
    )
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="local",
        parameter_types=("example.Expense",),
        path="app/src/main/unknown.kt",
    )


def test_builtin_beats_index_entry():
    source = """package example
class Fixture {
    fun text(value: String) {}
}
"""
    foreign = parser.ProjectTypeIndex(
        by_simple_name={"String": ("foreign.pkg.String",)},
        qualified=frozenset({"foreign.pkg.String"}),
    )
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, project_types=foreign
    )
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="text",
        parameter_types=("String",),
        path="app/src/main/unknown.kt",
    )


def test_import_beats_index_entry():
    source = """package example
import other.pkg.Expense
class Fixture {
    fun imported(value: Expense) {}
}
"""
    foreign = parser.ProjectTypeIndex(
        by_simple_name={"Expense": ("foreign.pkg.Expense",)},
        qualified=frozenset({"foreign.pkg.Expense"}),
    )
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, project_types=foreign
    )
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="imported",
        parameter_types=("other.pkg.Expense",),
        path="app/src/main/unknown.kt",
    )


def test_project_qualified_spelling_resolves_without_import():
    source = """package example
class Fixture {
    fun exact(value: com.example.expense.Expense) {}
}
"""
    index = parser.ProjectTypeIndex(
        by_simple_name={"Expense": ("com.example.expense.Expense",)},
        qualified=frozenset({"com.example.expense.Expense"}),
    )
    owner = parser.find_owner_declarations(source)[0]
    declarations = parser.find_callable_declarations(
        source, owner, project_types=index
    )
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="exact",
        parameter_types=("com.example.expense.Expense",),
        path="app/src/main/unknown.kt",
    )


def test_none_index_keeps_single_file_closed_world_byte_for_byte():
    """Default and explicit None behave exactly as before step A."""
    owner = parser.find_owner_declarations(_FILE_A)[0]
    for kwargs in ({}, {"project_types": None}):
        with pytest.raises(parser.ParserError) as excinfo:
            parser.find_callable_declarations(_FILE_A, owner, **kwargs)
        assert excinfo.value.code == "TYPE_UNRESOLVED"


def test_tolerant_mode_with_index_resolves_instead_of_retaining():
    """Tolerant discovery plus the index turns formerly-retained debt into
    exactly-resolved declarations."""
    owner = parser.find_owner_declarations(_FILE_A)[0]
    index = _index_from_sources(_FILE_A, _FILE_B)
    declarations = parser.find_callable_declarations(
        _FILE_A, owner, tolerate_unresolved_types=True, project_types=index
    )
    assert [d.status for d in declarations] == ["RESOLVED_EXACTLY"]


# ------------------------------------------- GR-07 step B: guarded _BUILTINS extension
#
# Three evidenced kotlin.stdlib names were appended to the closed builtin set:
# ``Throwable`` (38 retained TYPE_UNRESOLVED occurrences across the activated
# real-tree scan), ``Exception`` (3), and ``MutableList`` (1).  None of them is
# declared anywhere in the project, so the addition can never shadow a project
# type: builtins resolve AFTER same-file scopes, the file's package, imports,
# and aliases, and BEFORE the project-wide index.  The tests below pin each
# name's resolution in the pure single-file closed world (no project_types at
# all) plus the exact closure of the whole set.


def test_builtin_throwable_resolves_by_simple_name(parse_file):
    """``Throwable`` is concrete without any declaration or import."""
    declarations = parse_file("""package example
class Fixture {
    fun handle(error: Throwable) {}
}
""")
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="handle",
        parameter_types=("Throwable",),
    )
    assert parser.resolve_callable(
        declarations, "example.Fixture", "handle", None, ("Throwable",)
    ) == "RESOLVED_EXACTLY"


def test_builtin_exception_resolves_nullable_and_generic_positions(parse_file):
    """``Exception`` resolves bare and inside generic containment."""
    declarations = parse_file("""package example
class Fixture {
    fun wrap(error: Exception?) {}
    fun causes(history: List<Exception>) {}
}
""")
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="wrap",
        parameter_types=("Exception?",),
    )
    _assert_declaration(
        declarations[1],
        owner="example.Fixture",
        name="causes",
        parameter_types=("List<Exception>",),
    )


def test_builtin_mutable_list_resolves_with_type_arguments(parse_file):
    """``MutableList`` resolves as a generic head type; its argument keeps
    resolving through the ordinary ordered chain."""
    declarations = parse_file("""package example
class Fixture {
    fun collect(items: MutableList<String>) {}
}
""")
    _assert_declaration(
        declarations[0],
        owner="example.Fixture",
        name="collect",
        parameter_types=("MutableList<String>",),
    )


def test_builtin_set_closure_pins_evidenced_trio():
    """The builtin set stays CLOSED: GR-07 step B appended exactly the three
    evidenced kotlin.stdlib names to the frozen pre-existing set, GR-07
    step C appended exactly six further names backed by the 2026-08-26
    activated-scan residual probe (probe_sites_typefail.json), and the GR-07
    convergence round appended ``Class`` backed by the 2026-08-27 third
    evidence probe (probe15_token.py; java.lang default import, no project
    type named Class).  Any further addition must repeat the evidence probe
    first and extend this pin (and the documented append-point comment)
    deliberately."""
    assert parser._BUILTINS == frozenset({
        # Frozen pre-step-B set.
        "Any", "Nothing", "Unit", "String", "Char", "Boolean", "Byte", "Short",
        "Int", "Long", "Float", "Double", "Number", "Array", "ByteArray",
        "ShortArray", "IntArray", "LongArray", "FloatArray", "DoubleArray",
        "BooleanArray", "CharArray", "List", "Set", "Map", "Collection",
        "Iterable", "Iterator", "Sequence", "Comparable", "Enum", "Pair",
        "Triple",
        # GR-07 step B append point -- evidenced kotlin.stdlib types only.
        "Throwable", "Exception", "MutableList",
        # GR-07 step C append point -- second evidence probe, 2026-08-26:
        # default-imported kotlin.collections / kotlin.text types and
        # java.util wildcard-import members with no source-visible
        # declaration to resolve through.
        "MutableSet", "MutableMap", "Appendable", "Regex", "Date", "Calendar",
        # GR-07 convergence round append point -- third evidence probe,
        # 2026-08-27: java.lang default-imported ``Class``.
        "Class",
    })


# ------------------------------------------- GR-07 residual: owner-walk repairs
#
# Two production-tree failures were traced to the owner parser: (1) a ``->``
# arrow of a function-typed header parameter was read as a closing angle
# bracket, failing the whole owner walk with MALFORMED_SOURCE and poisoning
# every declaration in the file; (2) interfaces were not owners at all, so a
# bodyless class header before an interface swallowed the interface's body
# brace and its members were undiscoverable.


def test_owner_body_skips_function_type_arrow_in_class_header():
    source = """package example
class Formatter(private val resolve: (String) -> String) {
    fun build(value: Int): String { return "" }
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.status) for o in owners] == [
        ("example.Formatter", "RESOLVED_EXACTLY"),
    ]
    declarations = parser.find_callable_declarations(source, owners[0])
    assert [d.signature.function_name for d in declarations] == ["build"]


def test_owner_body_multiline_header_arrow_and_secondary_constructors():
    source = """package example
class Formatter private constructor(
    private val resolve: (UiText) -> String
) {
    constructor() : this(resolve = { text -> text.asString() })

    fun build(value: Int): String { return "" }
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.status) for o in owners] == [
        ("example.Formatter", "RESOLVED_EXACTLY"),
    ]


def test_interface_is_an_owner_with_qualified_members():
    source = """package example
interface EmailParser {
    fun canParse(sender: String): Boolean
}
class Impl : EmailParser {
    override fun canParse(sender: String): Boolean { return false }
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.name, o.status) for o in owners] == [
        ("example.EmailParser", "EmailParser", "RESOLVED_EXACTLY"),
        ("example.Impl", "Impl", "RESOLVED_EXACTLY"),
    ]
    declarations = parser.find_callable_declarations(source, owners[0])
    assert [(d.signature.function_name, d.signature.parameter_types)
            for d in declarations] == [
        ("canParse", ("String",)),
    ]


def test_bodyless_class_header_does_not_swallow_following_interface_body():
    source = """package example
data class Item(val id: Int)
interface Dao {
    fun fetch(id: Long): Item
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.name, o.body_start < o.body_end) for o in owners] == [
        ("example.Item", "Item", False),
        ("example.Dao", "Dao", True),
    ]
    declarations = parser.find_callable_declarations(source, owners[1])
    assert [d.signature.function_name for d in declarations] == ["fetch"]


# ── GR-07 hardening step C: residual TYPE_UNRESOLVED/BAD_TYPE families ───────
#
# Evidence: build/guard-debug/gr07/probe_sites_typefail.json (2026-08-26
# activated-scan reproduction of every retained S2b symbol-construction
# failure).  Each rule below resolves an evidenced family while keeping the
# closed world: every newly accepted spelling still participates verbatim in
# downstream exact signature comparisons, and every non-evidenced unknown
# name keeps failing closed as TYPE_UNRESOLVED.


def _resolve_in(source: str, owner_scope: str, typ: str, index=None) -> str:
    """Resolve one type text against one file's environment (test helper)."""
    masked = parser.mask_kotlin_source(source)
    environment = parser._type_environment(masked, owner_scope, index)
    return parser._resolve_type(typ, environment)


def _expect_type_unresolved(source: str, owner_scope: str, typ: str, index=None) -> None:
    try:
        _resolve_in(source, owner_scope, typ, index)
    except parser.ParserError as error:
        assert error.code == "TYPE_UNRESOLVED"
    else:
        raise AssertionError("expected ParserError(TYPE_UNRESOLVED)")


def test_owner_walk_tolerates_comparison_operator_in_constructor_defaults():
    """A ``>`` comparison inside a constructor default value is an operator,
    not an angle closer.  The real ExportTransaction.kt poisoned its whole
    file through this shape and silently dropped it from the project index."""
    source = """package com.example.export
class ExportTransaction(
    val id: String,
    val rateUsed: Double? = if (rate > 0.0) rate else null,
) {
    val isDiscounted: Boolean get() = rateUsed != null
}
"""
    owners = parser.find_owner_declarations(source)
    assert [(o.owner, o.status) for o in owners] == [
        ("com.example.export.ExportTransaction", "RESOLVED_EXACTLY"),
    ]
    assert parser.project_type_declarations(source) == (
        "com.example.export.ExportTransaction",
    )


def test_callable_discovery_tolerates_comparison_gt_in_fun_default_args():
    source = """package example
class Clamp {
    fun clamp(value: Int = if (raw > upper) upper else raw): Int { return value }
}
"""
    owners = parser.find_owner_declarations(source)
    declarations = parser.find_callable_declarations(source, owners[0])
    assert [d.signature.function_name for d in declarations] == ["clamp"]


def test_step_c_builtin_extensions_resolve():
    source = "package example\nclass Holder\n"
    for name in ("MutableSet", "MutableMap", "Appendable", "Regex",
                 "Date", "Calendar"):
        assert _resolve_in(source, "example.Holder", name) == name


def test_dotted_builtin_member_spelling_resolves_as_written():
    source = """package example
class Cache {
    class CacheEntry
    fun evict(eldest: Map.Entry<String, CacheEntry>) {}
}
"""
    assert _resolve_in(
        source, "example.Cache", "Map.Entry<String,CacheEntry>",
    ) == "Map.Entry<String,example.Cache.CacheEntry>"


def test_external_platform_fqcns_resolve_concretely():
    source = """package example
class Repair {
    fun open(database: androidx.sqlite.db.SupportSQLiteDatabase) {}
    fun read(cursor: android.database.Cursor) {}
    fun save(output: java.io.OutputStream) {}
}
"""
    assert _resolve_in(
        source, "example.Repair", "androidx.sqlite.db.SupportSQLiteDatabase",
    ) == "androidx.sqlite.db.SupportSQLiteDatabase"
    assert _resolve_in(
        source, "example.Repair", "android.database.Cursor",
    ) == "android.database.Cursor"
    assert _resolve_in(
        source, "example.Repair", "java.io.OutputStream",
    ) == "java.io.OutputStream"


def test_non_platform_unknown_fqcn_still_fails_closed():
    source = "package example\nclass Repair\n"
    _expect_type_unresolved(source, "example.Repair", "com.misspelled.Nope")


def test_nested_project_types_resolve_through_index_qualified_only():
    index = parser.ProjectTypeIndex(
        by_simple_name={"Outer": ("pkg.Outer",)},
        qualified=frozenset({"pkg.Outer", "pkg.Outer.Mode"}),
    )
    user_source = """package other
import pkg.Outer
class User {
    fun handle(mode: Outer.Mode) {}
}
"""
    assert _resolve_in(user_source, "other.User", "Outer.Mode", index) == \
        "pkg.Outer.Mode"
    bare_source = """package other
class User2 {
    fun handle(mode: Mode) {}
}
"""
    _expect_type_unresolved(bare_source, "other.User2", "Mode", index)


def test_same_package_declaration_beats_cross_package_duplicate():
    index = parser.ProjectTypeIndex(
        by_simple_name={"Thing": ("a.Thing", "b.Thing")},
        qualified=frozenset({"a.Thing", "b.Thing"}),
    )
    source = "package b\nclass Assembler { fun build(input: Thing) {} }\n"
    assert _resolve_in(source, "b.Assembler", "Thing", index) == "b.Thing"


def test_wildcard_project_import_resolves_confirmed_member():
    index = parser.ProjectTypeIndex(
        by_simple_name={"PlannedExpense": ("com.example.model.PlannedExpense",)},
        qualified=frozenset({"com.example.model.PlannedExpense"}),
    )
    source = """package logic
import com.example.model.*
class Engine {
    fun plan(expense: PlannedExpense) {}
}
"""
    assert _resolve_in(source, "logic.Engine", "PlannedExpense", index) == \
        "com.example.model.PlannedExpense"


def test_wildcard_project_import_ambiguity_fails_closed():
    index = parser.ProjectTypeIndex(
        by_simple_name={"PlannedExpense": (
            "com.example.model.PlannedExpense",
            "com.other.model.PlannedExpense",
        )},
        qualified=frozenset({
            "com.example.model.PlannedExpense",
            "com.other.model.PlannedExpense",
        }),
    )
    source = """package logic
import com.example.model.*
import com.other.model.*
class Engine {
    fun plan(expense: PlannedExpense) {}
}
"""
    _expect_type_unresolved(source, "logic.Engine", "PlannedExpense", index)


def test_wildcard_import_beats_cross_package_duplicate():
    """Kotlin import precedence: a wildcard-imported candidate resolves the
    unqualified reference even when another package declares the same simple
    name (the real SynthesisEngine ``model.*`` vs entity-duplicate shape)."""
    index = parser.ProjectTypeIndex(
        by_simple_name={"PlannedExpense": (
            "com.example.data.database.entity.PlannedExpense",
            "com.example.domain.model.PlannedExpense",
        )},
        qualified=frozenset({
            "com.example.data.database.entity.PlannedExpense",
            "com.example.domain.model.PlannedExpense",
        }),
    )
    source = """package logic
import com.example.domain.model.*
class Engine {
    fun plan(expense: PlannedExpense) {}
}
"""
    assert _resolve_in(source, "logic.Engine", "PlannedExpense", index) == \
        "com.example.domain.model.PlannedExpense"


def test_external_rooted_member_chain_resolves_through_import():
    """``BitmapFactory.Options`` resolves through the imported head: the
    canonicalized external spelling is concrete without project-index
    membership (evidence: BitmapFactory.Options x1 in the residual probe)."""
    source = """package example
import android.graphics.BitmapFactory
class Decoder {
    fun decode(options: BitmapFactory.Options) {}
}
"""
    assert _resolve_in(
        source, "example.Decoder", "BitmapFactory.Options",
    ) == "android.graphics.BitmapFactory.Options"


def test_suspend_function_type_parameter_resolves_strict_mode():
    """``suspend () -> T`` stops dying as BAD_TYPE.  The closed signature
    grammar has no spelling for the suspend modifier, so the resolved
    identity is the bare function type -- honest within this closed world,
    since Room DAO signatures can never carry suspend function-type
    parameters either (the same grammar governs both sides)."""
    source = """package example
class WorkerRunContext
class Guard<T> {
    fun <T> runExclusive(block: suspend () -> T): T { return block() }
    fun guarded(block: suspend (WorkerRunContext) -> T): T { return block() }
}
"""
    owners = parser.find_owner_declarations(source)
    guard = next(o for o in owners if o.owner == "example.Guard")
    declarations = parser.find_callable_declarations(source, guard)
    by_name = {d.signature.function_name: d for d in declarations}
    assert by_name["runExclusive"].signature.parameter_types == ("() -> T",)
    assert by_name["guarded"].signature.parameter_types == \
        ("(example.WorkerRunContext) -> T",)


def test_generic_type_variables_resolve_in_parameters():
    source = """package example
class Repo<T : Any> {
    fun <R> map(default: R, block: (T) -> R): R { return default }
}
"""
    owners = parser.find_owner_declarations(source)
    declarations = parser.find_callable_declarations(source, owners[0])
    assert [d.signature.parameter_types for d in declarations] == [
        ("R", "(T) -> R"),
    ]


def test_generic_variance_and_bounds_do_not_leak_as_variables():
    """Only the DECLARED variable name is collected; ``in``/``out`` variance
    prefixes and bound names are not variables themselves."""
    source = """package example
class Pipe<in T, out R : SecretBound> {
    fun pump(value: T): R
}
"""
    owners = parser.find_owner_declarations(source)
    declarations = parser.find_callable_declarations(source, owners[0])
    assert [d.signature.parameter_types for d in declarations] == [("T",)]
    _expect_type_unresolved(source, "example.Pipe", "SecretBound")


# ── GR-07 convergence round: star projections + erase-only grammar families ──
#
# Evidence: build/guard-debug/gr07/probe_star_postfix.py (star spellings killed
# WHOLE-FILE callable discovery: AiServiceResult<*> x1 made all three
# HybridDedupeJudgeService callables vanish) and probe15_token.py (per-family
# reproduction of every retained S2b failure).  Each rule below ERASES a
# spelling to its closed-grammar equivalent; both sides of every downstream
# exact signature comparison pass through the same normalizer, so no identity
# can be fabricated.  Malformed uses keep failing closed.


def test_star_projection_erases_to_any_question_mark():
    """``Type<*>`` is Kotlin's erased wildcard; the canonical closed-grammar
    spelling is ``Type<Any?>`` (Kotlin's own semantic equivalent)."""
    assert parser.erase_star_projections("AiServiceResult<*>") == \
        "AiServiceResult<Any?>"
    assert parser.erase_star_projections("Map<String, *>") == \
        "Map<String, Any?>"
    assert parser.erase_star_projections("Pair<*, Int>") == "Pair<Any?, Int>"
    assert parser.erase_star_projections("List<List<*>>") == \
        "List<List<Any?>>"
    assert parser.erase_star_projections("Foo<*>?") == "Foo<Any?>?"
    assert parser.erase_star_projections(
        "suspend () -> Flow<AiServiceResult<*>>",
    ) == "suspend () -> Flow<AiServiceResult<Any?>>"


def test_malformed_star_projection_keeps_failing_closed():
    """A star outside a legal generic-argument position is left untouched so
    the signature normalizer keeps rejecting it (UNSUPPORTED_TOKEN)."""
    for text in ("Int*", "List<**>", "List<String*>", "*"):
        try:
            parser.normalize_type_text(parser.erase_star_projections(text))
        except parser.SignatureError as error:
            assert error.code in ("UNSUPPORTED_TOKEN", "BAD_TYPE")
        else:
            raise AssertionError(f"expected rejection for {text!r}")


def test_star_projection_parameter_no_longer_kills_file_discovery():
    """The exact production shape: one ``AiServiceResult<*>`` parameter used
    to abort discovery of the whole owner (UNSUPPORTED_TOKEN); now every
    callable resolves and the wildcard carries its erased identity."""
    source = """package example

class AiServiceResult<T>
class DedupeJudgeSuggestion

class Fixture {
    fun errorMessage(result: AiServiceResult<*>): String {
        return "x"
    }

    fun safeExecute(block: suspend () -> AiServiceResult<DedupeJudgeSuggestion>) {
        TODO()
    }

    fun both(m: Map<String, *>, p: Pair<*, Int>): List<List<*>> = TODO()
}
"""
    owners = parser.find_owner_declarations(source)
    fixture = next(o for o in owners if o.owner.endswith("Fixture"))
    declarations = parser.find_callable_declarations(source, fixture)
    by_name = {d.signature.function_name: d for d in declarations}
    assert by_name["errorMessage"].signature.parameter_types == \
        ("example.AiServiceResult<Any?>",)
    assert by_name["both"].signature.parameter_types == (
        "Map<String, Any?>", "Pair<Any?, Int>",
    )
    assert by_name["both"].status == "UNSUPPORTED_EXPRESSION_BODY"


def test_function_type_named_parameters_are_erased():
    """``suspend (groupId: Long) -> Unit`` x28 (GroupTransactionCoordinator)
    died as UNSUPPORTED_TOKEN because the signature grammar has no ``:``.
    Argument names are erased -- Kotlin ignores them for function-type
    identity."""
    source = """package example

class Fixture {
    fun run(onInsideTransaction: suspend (groupId: Long) -> Unit) {
        onInsideTransaction(1L)
    }

    fun mixed(block: (key: String, value: Int) -> Boolean): Boolean {
        return block("a", 1)
    }
}
"""
    owners = parser.find_owner_declarations(source)
    fixture = next(o for o in owners if o.owner.endswith("Fixture"))
    declarations = parser.find_callable_declarations(source, fixture)
    by_name = {d.signature.function_name: d for d in declarations}
    assert by_name["run"].signature.parameter_types == ("(Long) -> Unit",)
    assert by_name["mixed"].signature.parameter_types == \
        ("(String, Int) -> Boolean",)


def test_jvm_suppress_wildcards_annotation_is_erased():
    source = """package example

class RetentionTarget

class RetentionModule {
    fun targets(targets: Set<@JvmSuppressWildcards RetentionTarget>): Int {
        return targets.size
    }
}
"""
    owners = parser.find_owner_declarations(source)
    module = next(o for o in owners if o.owner.endswith("RetentionModule"))
    declarations = parser.find_callable_declarations(source, module)
    assert declarations[0].signature.parameter_types == \
        ("Set<example.RetentionTarget>",)


def test_use_site_variance_prefixes_are_erased():
    source = """package example

class ListenableWorker

class WorkerSpecScheduler {
    fun schedule(workerClass: Class<out ListenableWorker>): String {
        return workerClass.name
    }
}
"""
    owners = parser.find_owner_declarations(source)
    scheduler = next(o for o in owners if o.owner.endswith("WorkerSpecScheduler"))
    declarations = parser.find_callable_declarations(source, scheduler)
    assert declarations[0].signature.parameter_types == \
        ("Class<example.ListenableWorker>",)


def test_java_lang_class_resolves_as_default_imported_builtin():
    """``Class`` requires no import in Kotlin (java.lang default import), so
    no import-based resolution could ever see it.  Evidence: bare
    ``Class<out ListenableWorker>`` x2; no project type is named Class."""
    source = "package example\nclass Holder\n"
    assert _resolve_in(source, "example.Holder", "Class<String>") == \
        "Class<String>"
