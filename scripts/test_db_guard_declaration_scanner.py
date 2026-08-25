"""Contract tests for the fail-closed declaration-range DB scanner."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
import yaml

from scripts.db_guard import declaration_scanner
from scripts.ci.finding_rule_catalog import is_known_diagnostic
from scripts.db_guard.declaration_scanner import (
    DeclarationRange,
    Diagnostic,
    DiagnosticContextError,
    MAX_CONTEXT,
    MAX_CONTEXT_DEPTH,
    MAX_CONTEXT_ITEMS,
    MAX_CONTEXT_NUMBER,
    MAX_LOCATION_NUMBER,
    _absolute_root_anchor,
    ScanWriteError,
    scan_production_declarations,
    write_scan_delta_atomic,
)
from scripts.db_guard.room_inventory import build_room_inventory
from scripts.db_guard.source_roots import (
    SourceRoot,
    SourceRootSet,
    collect_production_kotlin_files,
)


def _write(root: Path, relative: str, source: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


def _scan(tmp_path: Path, relative: str, source: str):
    global _CURRENT_SOURCE
    _CURRENT_SOURCE = source
    _write(tmp_path, relative, source)
    return scan_production_declarations(tmp_path)


_CURRENT_SOURCE: str | None = None


def _range(path: str, owner: str, kind: str, start: int, end: int,
           dao: bool, abstract: bool, body_start: int | None,
           body_end: int | None, *, source_start: int | None = None,
           source_end: int | None = None,
           source: str | None = None) -> DeclarationRange:
    source = source if source is not None else _CURRENT_SOURCE
    if source is not None:
        lines = source.splitlines(keepends=True)
        starts = [0]
        for line in lines:
            starts.append(starts[-1] + len(line))
        if source_start is None and 1 <= start <= len(lines):
            line_start = starts[start - 1]
            line = lines[start - 1]
            content = line.rstrip("\r\n")
            keywords = {
                "function": ("fun",),
                "property": ("const", "val", "var"),
                "class": ("class",),
                "interface": ("interface",),
                "enum": ("enum",),
                "annotation": ("annotation",),
                "companion": ("companion",),
            }.get(kind, ())
            positions = [line.find(keyword) for keyword in keywords]
            positions = [position for position in positions if position >= 0]
            source_start = line_start + (min(positions) if positions else len(line) - len(line.lstrip()))
        if source_end is None and 1 <= end <= len(lines):
            if body_end is not None:
                source_end = body_end + 1
            else:
                line_start = starts[end - 1]
                content = lines[end - 1].rstrip("\r\n")
                source_end = line_start + len(content)
                if kind == "property":
                    semicolon = source.find(";", source_start or line_start, line_start + len(content))
                    if semicolon >= 0:
                        source_end = semicolon
    return DeclarationRange(path, owner, kind, start, end, dao, abstract,
                            body_start, body_end, None, (), source_start,
                            source_end)


def _tuple(item: DeclarationRange) -> tuple:
    """Comparison tuple containing the original nine declaration fields."""
    return (item.path, item.owner_fqcn, item.kind, item.start_line,
            item.end_line, item.is_dao, item.is_abstract, item.body_start,
            item.body_end)


def _tuples(items) -> tuple:
    return tuple(_tuple(item) for item in items)


def _diagnostic(code: str, path: str | None = None) -> Diagnostic:
    return Diagnostic(code, path)


def test_dao_and_same_file_unsafe_writer_keep_only_helper_mutation_in_caller_scan(tmp_path):
    source = """package example
@Dao
interface ReportingDao {
    fun declared()
}

class UnsafeWriter {
    fun write(dao: ReportingDao) {
        dao.insert()
    }
}
"""
    scan = _scan(tmp_path, "app/src/main/java/example/ReportingDao.kt", source)
    path = "app/src/main/java/example/ReportingDao.kt"
    assert scan.dao_declarations == (
        _range(path, "example.ReportingDao", "dao", 3, 5, True, True, 45, 65, source=source),
    )
    assert scan.skipped_dao_declaration_ranges == (
        _range(path, "example.ReportingDao", "function", 4, 4, True, True, None, None, source=source),
    )
    assert scan.helper_ranges == (
        _range(path, "example.UnsafeWriter", "class", 7, 11, False, False, 88, 151, source=source),
        _range(path, "example.UnsafeWriter", "function", 8, 10, False, False, 123, 149, source=source),
    )
    assert scan.findings == ()


def test_top_level_writer_with_dao_parameter_is_never_skipped(tmp_path):
    path = "app/src/main/java/example/TopLevel.kt"
    source = """package example
@Dao interface ReportingDao { fun declared() }
fun unsafeWrite(dao: ReportingDao) {
    dao.insert()
}
"""
    scan = _scan(tmp_path, path, source)
    assert scan.skipped_dao_declaration_ranges == (
         _range(path, "example.ReportingDao", "function", 2, 2, True, True, None, None,
                source_start=46, source_end=60, source=source),
    )
    assert scan.helper_ranges[-1] == _range(
        path, "example", "function", 3, 5, False, False, 99, 117, source=source
    )
    assert scan.helper_ranges[-1].is_dao is False


def test_companion_writer_has_exact_enclosing_owner_and_symbol_range(tmp_path):
    path = "app/src/main/java/example/Storage.kt"
    scan = _scan(tmp_path, path, """package example
class Storage {
    companion object {
        fun write() {
            println(1)
        }
    }
}
""")
    assert scan.helper_ranges == (
        _range(path, "example.Storage", "class", 2, 8, False, False, 31, 116),
        _range(path, "example.Storage", "companion", 3, 7, False, False, 54, 114),
        _range(path, "example.Storage", "function", 4, 6, False, False, 76, 108),
    )


def test_reporting_dao_filename_does_not_skip_non_dao_helper(tmp_path):
    path = "app/src/main/java/example/ReportingDao.kt"
    scan = _scan(tmp_path, path, """package example
class ReportingHelper {
    fun write() {}
}
""")
    assert scan.dao_declarations == ()
    assert scan.helper_ranges == (
        _range(path, "example.ReportingHelper", "class", 2, 4, False, False, 39, 59),
        _range(path, "example.ReportingHelper", "function", 3, 3, False, False, 57, 57),
    )


def test_dao_in_storage_contracts_is_inventoried_by_annotation_not_filename(tmp_path):
    path = "app/src/main/java/example/StorageContracts.kt"
    source = """package example
@Dao
interface ReportingContracts {
    fun load()
}
"""
    scan = _scan(tmp_path, path, source)
    assert scan.files_scanned == (path,)
    assert scan.dao_declarations == (
        _range(path, "example.ReportingContracts", "dao", 3, 5, True, True, 51, 67,
               source=source),
    )


def test_two_helpers_in_one_file_have_all_type_and_callable_ranges(tmp_path):
    path = "app/src/main/java/example/Combined.kt"
    scan = _scan(tmp_path, path, """package example
class FirstWriter {
    fun first() {}
}
class SecondWriter {
    fun second() {}
}
""")
    assert scan.helper_ranges == (
        _range(path, "example.FirstWriter", "class", 2, 4, False, False, 35, 55),
        _range(path, "example.FirstWriter", "function", 3, 3, False, False, 53, 53),
        _range(path, "example.SecondWriter", "class", 5, 7, False, False, 77, 98),
        _range(path, "example.SecondWriter", "function", 6, 6, False, False, 96, 96),
    )


def test_abstract_dao_method_is_skipped_but_default_method_body_is_retained(tmp_path):
    path = "app/src/main/java/example/Reporting.kt"
    source = """package example
@Dao
interface ReportingDao {
    fun abstractRead()
    fun defaultRead() {
        println(1)
    }
}
"""
    scan = _scan(tmp_path, path, source)
    assert scan.dao_declarations == (
        _range(path, "example.ReportingDao", "dao", 3, 8, True, True, 45, 118,
               source=source),
    )
    assert scan.skipped_dao_declaration_ranges == (
        _range(path, "example.ReportingDao", "function", 4, 5, True, True, None, None,
               source_start=50, source_end=68, source=source),
    )
    assert scan.helper_ranges == (
        _range(path, "example.ReportingDao", "function", 5, 7, True, False, 92, 116),
    )


def test_missing_empty_and_unreadable_production_roots_fail_closed(tmp_path, monkeypatch):
    missing = scan_production_declarations(tmp_path / "missing")
    assert missing.dao_declarations == ()
    assert missing.helper_ranges == ()
    assert missing.diagnostics == (_diagnostic("DB_DECLARATION_INVALID_SOURCE"),)

    empty_root = tmp_path / "empty" / "app" / "src" / "main" / "java"
    empty_root.mkdir(parents=True)
    empty = scan_production_declarations(empty_root)
    assert empty.dao_declarations == ()
    assert empty.helper_ranges == ()
    assert empty.diagnostics == (_diagnostic("DB_DECLARATION_SOURCE_EMPTY"),)

    def unreadable(_source, topdown=True, onerror=None):
        assert onerror is not None
        onerror(OSError("fixture"))
        return iter(())

    monkeypatch.setattr(declaration_scanner.os, "walk", unreadable)
    unreadable_scan = scan_production_declarations(empty_root)
    assert unreadable_scan.dao_declarations == ()
    assert unreadable_scan.helper_ranges == ()
    assert unreadable_scan.diagnostics == (_diagnostic("DB_DECLARATION_SOURCE_UNREADABLE"),)


def test_scan_and_atomic_delta_are_deterministic_and_failed_write_cleans_temporary_file(tmp_path, monkeypatch):
    _write(tmp_path, "app/src/main/java/z/Z.kt", "package z\nclass Z {}\n")
    _write(tmp_path, "app/src/main/java/a/A.kt", "package a\nclass A {}\n")
    first = scan_production_declarations(tmp_path)
    second = scan_production_declarations(tmp_path)
    assert first == second
    assert first.files_scanned == (
        "app/src/main/java/a/A.kt", "app/src/main/java/z/Z.kt"
    )

    target = tmp_path / "scan.json"
    write_scan_delta_atomic(target, first)
    first_payload = target.read_bytes()
    write_scan_delta_atomic(target, second)
    assert target.read_bytes() == first_payload
    assert json.loads(first_payload)["files_scanned"] == list(first.files_scanned)

    target.write_text("old", encoding="utf-8")
    monkeypatch.setattr(declaration_scanner.os, "replace", lambda *_args: (_ for _ in ()).throw(OSError()))
    with pytest.raises(ScanWriteError) as error:
        write_scan_delta_atomic(target, first)
    assert str(error.value) == "DB_DECLARATION_SCAN_WRITE_FAILED"
    assert target.read_text(encoding="utf-8") == "old"
    assert tuple(target.parent.glob(f".{target.name}.*.tmp")) == ()


def test_filename_based_skip_regression_is_closed_for_multiple_ordinary_helpers(tmp_path):
    _write(tmp_path, "app/src/main/java/example/ReportingDao.kt", "package example\nclass Plain {}\n")
    _write(tmp_path, "app/src/main/java/example/Other.kt", "package example\nclass Other {}\n")
    scan = scan_production_declarations(tmp_path)
    assert scan.files_scanned == (
        "app/src/main/java/example/Other.kt",
        "app/src/main/java/example/ReportingDao.kt",
    )
    assert {item.owner_fqcn for item in scan.helper_ranges} == {
        "example.Other", "example.Plain"
    }
    assert scan.dao_declarations == ()


def test_non_dao_and_nested_interfaces_are_inventoried_with_exact_owners(tmp_path):
    path = "app/src/main/java/example/Interfaces.kt"
    scan = _scan(tmp_path, path, """package example
interface Outer {
    interface Inner {
        fun local() { println(1) }
    }
    interface Bodyless
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Outer", "interface", 2, 7, False, True, 33, 120),
        (path, "example.Outer.Inner", "interface", 3, 5, False, True, 55, 95),
        (path, "example.Outer.Inner", "function", 4, 4, False, False, 77, 89),
        (path, "example.Outer.Bodyless", "interface", 6, 6, False, True, 120, 120),
    )
    bodyless = next(item for item in scan.helper_ranges
                    if item.owner_fqcn == "example.Outer.Bodyless")
    # A bodyless nested owner keeps its own exact boundary: the range ends at
    # its line and the empty body never borrows the enclosing ``}``.
    assert (bodyless.start_line, bodyless.end_line) == (6, 6)
    assert (bodyless.body_start, bodyless.body_end) == (120, 120)


def test_fun_interface_is_owned_only_by_owner_parser_not_direct_functions(tmp_path):
    path = "app/src/main/java/example/Listener.kt"
    scan = _scan(tmp_path, path, """package example
fun interface Listener {
    fun onEvent()
}
""")
    # ``fun interface`` declarations are interface owners owned exclusively
    # by the owner parser; direct function scanning must never invent a bogus
    # function for their ``fun`` keyword.  The interface and its abstract
    # method are the only ranges: no package-level function exists for the
    # ``fun interface`` line and the file scans without a diagnostic.
    assert scan.diagnostics == ()
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Listener", "interface", 2, 4, False, True, 40, 59),
        (path, "example.Listener", "function", 3, 3, False, False, None, None),
    )


def test_malformed_file_emits_one_controlled_diagnostic_without_partial_ranges(tmp_path):
    scan = _scan(tmp_path, "app/src/main/java/example/Broken.kt", """package example
class Broken {
    val value = 1
    fun write() {
""")
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/main/java/example/Broken.kt"),)


def test_property_initializer_and_accessor_are_in_one_structural_range(tmp_path):
    path = "app/src/main/java/example/Props.kt"
    scan = _scan(tmp_path, path, """package example
class Props {
    val value = makeValue()
        get() { return field }
    fun next() {}
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Props", "class", 2, 6, False, False, 29, 107),
        (path, "example.Props", "property", 3, 4, False, False, 73, 87),
        (path, "example.Props", "function", 5, 5, False, False, 105, 105),
    )
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    # The property is one structural range: its start is the ``val`` header and
    # its end is the accessor body close, not the initializer only.
    assert (prop.start_line, prop.end_line) == (3, 4)
    # The accessor body bounds are exact and owned by the property.
    assert (prop.body_start, prop.body_end) == (73, 87)
    assert prop.owner_fqcn == "example.Props"
    # The property must never overlap or capture the following sibling.
    assert prop.end_line < sibling.start_line
    assert (sibling.start_line, sibling.end_line) == (5, 5)
    assert (sibling.body_start, sibling.body_end) == (105, 105)


def test_semicolon_sibling_property_ends_before_the_semicolon_and_sibling_starts_exactly(tmp_path):
    path = "app/src/main/java/example/Semicolon.kt"
    scan = _scan(tmp_path, path, """package example
class Props {
    val x = 1; fun next() {}
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Props", "class", 2, 4, False, False, 29, 59),
        (path, "example.Props", "function", 3, 3, False, False, 57, 57),
        (path, "example.Props", "property", 3, 3, False, False, None, None),
    )
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    # The ``;`` separates the statements: the property ends on its own line
    # and the sibling starts at its exact offset on the same line, so the
    # sibling is never captured inside the property range.
    assert (prop.start_line, prop.end_line) == (3, 3)
    assert (sibling.start_line, sibling.end_line) == (3, 3)
    assert (prop.body_start, prop.body_end) == (None, None)


def test_same_line_accessors_end_the_property_at_final_accessor_expression_or_body(tmp_path):
    path = "app/src/main/java/example/SameLineAccessors.kt"
    scan = _scan(tmp_path, path, """package example
class Props {
    val a get() = 1
    val b get() = 2
        set(value) { field = value }
    val c get() { return 3 }
    fun next() {}
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Props", "class", 2, 8, False, False, 29, 154),
        (path, "example.Props", "property", 3, 3, False, False, None, None),
        (path, "example.Props", "property", 4, 5, False, False, 90, 105),
        (path, "example.Props", "property", 6, 6, False, False, 124, 134),
        (path, "example.Props", "function", 7, 7, False, False, 152, 152),
    )
    # Each accessor-only property ends at its final accessor expression or
    # block body; none extends to the enclosing class scope.
    a, b, c = (next(item for item in scan.helper_ranges if item.kind == "property"
                    and item.start_line == line) for line in (3, 4, 6))
    assert (a.end_line, a.body_start, a.body_end) == (3, None, None)
    assert (b.end_line, b.body_start, b.body_end) == (5, 90, 105)
    assert (c.end_line, c.body_start, c.body_end) == (6, 124, 134)


def test_accessor_expression_ends_before_modifier_prefixed_typealias_sibling(tmp_path):
    path = "app/src/main/java/example/Props.kt"
    source = """package example
class Props {
    val value get() = 1
    public typealias Alias = String
    fun next() {}
}
"""
    scan = _scan(tmp_path, path, source)

    assert scan.diagnostics == ()
    properties = [item for item in scan.helper_ranges if item.kind == "property"]
    assert _tuples(properties) == (
        (path, "example.Props", "property", 3, 3, False, False, None, None),
    )
    prop = properties[0]
    sibling = next(item for item in scan.helper_ranges
                   if item.kind == "function" and item.owner_fqcn == "example.Props")
    function_open = source.index("{", source.index("fun next"))
    function_close = source.index("}", function_open)
    assert prop.end_line == 3
    assert sibling == _range(path, "example.Props", "function", 5, 5,
                             False, False, function_open + 1, function_close)
    assert prop.end_line < sibling.start_line


def test_accessor_keyword_embedded_in_identifier_is_not_an_accessor():
    # ``get``/``set`` only start an accessor as a standalone token.  An
    # identifier that merely contains the keyword (``forget``, ``reset``,
    # ``getValue``) or a member call (``target.getValue()``, ``target.get()``)
    # never counts.
    for source, keyword in (
        ("forget()", "get"),
        ("reset()", "set"),
        ("getValue()", "get"),
        ("target.getValue()", "get"),
        ("target.get()", "get"),
        ("val x = forget()", "get"),
        ("val x = reset()", "set"),
        ("val x = getValue()", "get"),
    ):
        index = source.index(keyword)
        assert declaration_scanner._is_accessor_at(
            source, index, len(source)
        ) is False


def test_real_property_accessor_forms_are_recognized():
    # Standalone accessor tokens keep matching: after the property name
    # (``val x get()``), after an initializer (``= compute() get()``), and in
    # setter form (``set(value)``).
    for source, keyword in (
        ("val x get() = 1", "get"),
        ("val x = compute() get() = field", "get"),
        ("var x set(value) { field = value }", "set"),
        ("set(value) { field = value }", "set"),
        ("get() { return field }", "get"),
    ):
        index = source.index(keyword)
        assert declaration_scanner._is_accessor_at(
            source, index, len(source)
        ) is True


def test_get_set_like_identifiers_in_initializers_keep_exact_property_ranges(tmp_path):
    path = "app/src/main/java/example/AccessorTokens.kt"
    scan = _scan(tmp_path, path, """package example
class Holder {
    val handler = set
    val get = value
    val forget = reset
    val getValue = target.getValue()
    val member = target.get()
    fun next() {}
}
""")
    # Bare ``get``/``set`` identifiers, identifiers containing those tokens,
    # and member calls are ordinary expressions, never accessors.  Every
    # property therefore keeps its exact single-line range.
    assert scan.diagnostics == ()
    props = [item for item in scan.helper_ranges if item.kind == "property"]
    assert _tuples(props) == (
        (path, "example.Holder", "property", 3, 3, False, False, None, None),
        (path, "example.Holder", "property", 4, 4, False, False, None, None),
        (path, "example.Holder", "property", 5, 5, False, False, None, None),
        (path, "example.Holder", "property", 6, 6, False, False, None, None),
        (path, "example.Holder", "property", 7, 7, False, False, None, None),
    )
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    assert (sibling.start_line, sibling.end_line) == (8, 8)


def test_property_ends_before_fresh_line_annotation_sibling(tmp_path):
    path = "app/src/main/java/example/AnnotatedProperty.kt"
    scan = _scan(tmp_path, path, """package example
val a = 1
@Marker
fun next() {}
""")
    # ``_property_bounds`` ends a property at a fresh-line annotation block:
    # ``val a = 1`` (line 2) never absorbs ``@Marker`` (line 3) into its
    # range, and the annotated function sibling starts exactly at its own
    # ``fun`` keyword (line 4).
    assert scan.diagnostics == ()
    assert _tuples(scan.helper_ranges) == (
        (path, "example", "property", 2, 2, False, False, None, None),
        (path, "example", "function", 4, 4, False, False, 46, 46),
    )
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    assert (prop.start_line, prop.end_line) == (2, 2)
    assert (prop.body_start, prop.body_end) == (None, None)


def test_typed_property_accessors_keep_exact_getter_and_setter_ranges(tmp_path):
    path = "app/src/main/java/example/TypedAccessors.kt"
    source = """package example
class Typed {
    val name: String
        get () = "value"
    var count: Int
        get () { return field }
        set (value) { field = value }
    fun sibling() {}
}
"""
    scan = _scan(tmp_path, path, source)
    assert scan.diagnostics == ()
    properties = [item for item in scan.helper_ranges if item.kind == "property"]
    setter_open = source.index("{", source.index("set (value)"))
    setter_close = source.index("}", setter_open)
    assert _tuples(properties) == (
        (path, "example.Typed", "property", 3, 4, False, False, None, None),
        (path, "example.Typed", "property", 5, 7, False, False,
         setter_open + 1, setter_close),
    )
    getter, setter = properties
    assert (getter.body_start, getter.body_end) == (None, None)
    assert (setter.body_start, setter.body_end) == (setter_open + 1, setter_close)
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    assert (sibling.start_line, sibling.end_line) == (8, 8)
    assert setter.end_line < sibling.start_line


def test_property_ends_before_typealias_sibling(tmp_path):
    path = "app/src/main/java/example/TypeAliasSibling.kt"
    scan = _scan(tmp_path, path, """package example
val value = 1
typealias Alias = String
fun next() {}
""")
    assert scan.diagnostics == ()
    assert _tuples(scan.helper_ranges) == (
        (path, "example", "property", 2, 2, False, False, None, None),
        (path, "example", "function", 4, 4, False, False, 67, 67),
    )
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    assert prop.end_line < sibling.start_line


def test_bodyless_owner_and_function_end_before_fresh_line_typealias(tmp_path):
    path = "app/src/main/java/example/TypeAliasBoundaries.kt"
    scan = _scan(tmp_path, path, """package example
class EmptyOwner
typealias Alias = String
fun first()
public typealias PublicAlias = String
fun second() {}
""")
    assert scan.diagnostics == ()
    assert _tuples(scan.helper_ranges) == (
        (path, "example.EmptyOwner", "class", 2, 2, False, False, 33, 33),
        (path, "example", "function", 4, 4, False, False, None, None),
        (path, "example", "function", 6, 6, False, False, 122, 122),
    )
    owner = next(item for item in scan.helper_ranges if item.kind == "class")
    first = next(item for item in scan.helper_ranges
                 if item.kind == "function" and item.start_line == 4)
    assert (owner.start_line, owner.end_line) == (2, 2)
    assert (first.start_line, first.end_line) == (4, 4)
    assert first.body_start is None and first.body_end is None


def test_multiline_accessor_body_followed_by_sibling_ends_property_at_balanced_body(tmp_path):
    path = "app/src/main/java/example/MultilineAccessor.kt"
    scan = _scan(tmp_path, path, """package example
class Props {
    val cache = makeCache()
        get() {
            return field
        }
    fun next() {}
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Props", "class", 2, 8, False, False, 29, 127),
        (path, "example.Props", "property", 3, 6, False, False, 73, 107),
        (path, "example.Props", "function", 7, 7, False, False, 125, 125),
    )
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    # The balanced accessor body close is the property end (line 6); the
    # sibling starts on its own line and is never captured.
    assert (prop.start_line, prop.end_line) == (3, 6)
    assert (prop.body_start, prop.body_end) == (73, 107)
    assert (sibling.start_line, sibling.end_line) == (7, 7)


def test_multiline_initializer_lambda_followed_by_sibling_never_truncates_property(tmp_path):
    path = "app/src/main/java/example/MultilineLambda.kt"
    scan = _scan(tmp_path, path, """package example
class Holder {
    val loaded = run {
        prepare()
        dao.load()
    }
    fun next() {}
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Holder", "class", 2, 8, False, False, 30, 115),
        (path, "example.Holder", "property", 3, 6, False, False, 53, 95),
        (path, "example.Holder", "function", 7, 7, False, False, 113, 113),
    )
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    sibling = next(item for item in scan.helper_ranges if item.kind == "function")
    # The inner lambda braces are reported as the body span but never
    # truncate the declaration: the property ends at the direct-scope
    # sibling (line 7) and the lambda's close is inside the range.
    assert (prop.start_line, prop.end_line) == (3, 6)
    assert (prop.body_start, prop.body_end) == (53, 95)
    assert (sibling.start_line, sibling.end_line) == (7, 7)


def test_symlinked_source_is_rejected_without_ranges(tmp_path):
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "Escaped.kt").write_text("class Escaped {}", encoding="utf-8")
    try:
        (root / "escaped").symlink_to(outside, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    scan = scan_production_declarations(tmp_path)
    assert scan.dao_declarations == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_SYMLINK_OUTSIDE"),)


def test_mixed_valid_and_malformed_files_are_diagnostics_only(tmp_path):
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    _write(tmp_path, "app/src/main/java/example/Broken.kt", "package example\nclass Broken {\n")
    scan = scan_production_declarations(tmp_path)
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (
        _diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/main/java/example/Broken.kt"),
    )


@pytest.mark.parametrize("unsafe", [
    "app/src/main/java/example/Secret file.kt",       # whitespace segment
    "app/src/main/java/example/Secret\x01file.kt",    # control character
    "app/src/main/java/example/../Secret.kt",         # traversal segment
])
def test_unsafe_discovered_filename_is_rejected_without_ranges(tmp_path, monkeypatch, unsafe):
    # The unsafe filename is injected through the file-discovery hook because
    # Windows cannot host names with control characters or traversal.
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True)
    monkeypatch.setattr(
        declaration_scanner, "_files",
        lambda project, source: ([(unsafe, root / "Secret.kt")], False, set()),
    )
    scan = scan_production_declarations(tmp_path)
    assert scan.files_scanned == ()
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_INVALID_SOURCE"),)
    joined = json.dumps([d.to_dict() for d in scan.diagnostics])
    assert "Secret" not in joined
    assert "Secret file.kt" not in joined


def test_mixed_valid_and_unsafe_filenames_are_diagnostics_only(tmp_path, monkeypatch):
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    real_files = declaration_scanner._files

    def unsafe_files(project, source):
        result, failed, symlink_diagnostics = real_files(project, source)
        result.append(("app/src/main/java/example/Secret file.kt", source / "Secret file.kt"))
        return result, failed, symlink_diagnostics

    monkeypatch.setattr(declaration_scanner, "_files", unsafe_files)
    scan = scan_production_declarations(tmp_path)
    # The unsafe path never enters files_scanned; the valid file is read
    # metadata only and no partial inventory (ranges) survives.
    assert scan.files_scanned == ("app/src/main/java/example/Good.kt",)
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_INVALID_SOURCE"),)
    joined = json.dumps([d.to_dict() for d in scan.diagnostics])
    assert "Secret" not in joined
    assert "Secret file.kt" not in joined


def test_all_declaration_scanner_diagnostics_are_cataloged():
    emitted = {
        declaration_scanner.ScanWriteError.code,
        "DB_DECLARATION_UNRESOLVED",
        "DB_DECLARATION_INVALID_SOURCE",
        "DB_DECLARATION_SOURCE_EMPTY",
        "DB_DECLARATION_SOURCE_UNREADABLE",
        "DB_DECLARATION_SYMLINK_OUTSIDE",
    }
    assert all(is_known_diagnostic(code) for code in emitted)


def test_constructor_default_lambda_and_function_type_braces_do_not_steal_owner_body(tmp_path):
    path = "app/src/main/java/example/Headers.kt"
    scan = _scan(tmp_path, path, """package example
class Holder(val factory: (Int) -> String = { it.toString() }) {
    fun convert(value: (Int) -> String = { it.toString() }) { }
}
""")
    assert scan.helper_ranges == (
        _range(path, "example.Holder", "class", 2, 4, False, False, 80, 145),
        _range(path, "example.Holder", "function", 3, 3, False, False, 142, 143),
    )


def test_function_type_arrow_is_not_a_generic_closer_in_owner_headers(tmp_path):
    # The ``->`` arrow appears before the generic ``>`` of ``List<String>``;
    # the arrow must never decrement the angle depth.
    path = "app/src/main/java/example/Arrows.kt"
    scan = _scan(tmp_path, path, """package example
class Holder(val factory: (Int) -> List<String> = { listOf(it.toString()) }) {
    fun convert(value: (Int) -> String = { it.toString() }) { }
}
""")
    assert scan.helper_ranges == (
        _range(path, "example.Holder", "class", 2, 4, False, False, 94, 159),
        _range(path, "example.Holder", "function", 3, 3, False, False, 156, 157),
    )


def test_bodyless_owner_does_not_borrow_later_sibling_body(tmp_path):
    path = "app/src/main/java/example/Bodyless.kt"
    scan = _scan(tmp_path, path, """package example
class A
class B {}
""")
    # ``class A`` has no body of its own; its range ends exactly at the
    # sibling boundary and the empty body keeps it from owning ``class B``'s
    # braces.  The bodyless sibling's end boundary is its own line: end_line
    # == start_line and the empty body span is the header end only.
    assert _tuples(scan.helper_ranges) == (
        (path, "example.A", "class", 2, 2, False, False, 24, 24),
        (path, "example.B", "class", 3, 3, False, False, 33, 33),
    )
    bodyless = next(item for item in scan.helper_ranges
                    if item.owner_fqcn == "example.A")
    assert (bodyless.start_line, bodyless.end_line) == (2, 2)
    assert (bodyless.body_start, bodyless.body_end) == (24, 24)


def test_bodyless_constructor_only_class_with_default_lambda_does_not_borrow_sibling_body(tmp_path):
    path = "app/src/main/java/example/NoBody.kt"
    scan = _scan(tmp_path, path, """package example
class NoBody(val factory: (Int) -> String = { it.toString() })
class Next {}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.NoBody", "class", 2, 2, False, False, 79, 79),
        (path, "example.Next", "class", 3, 3, False, False, 91, 91),
    )
    bodyless = next(item for item in scan.helper_ranges
                    if item.owner_fqcn == "example.NoBody")
    # The constructor-default lambda is consumed inside the header: the
    # bodyless range ends exactly at its own line, never at the sibling's.
    assert (bodyless.start_line, bodyless.end_line) == (2, 2)
    assert (bodyless.body_start, bodyless.body_end) == (79, 79)


def test_bodyless_owner_ends_before_annotated_sibling(tmp_path):
    path = "app/src/main/java/example/AnnotatedSibling.kt"
    scan = _scan(tmp_path, path, """package example
class A
@Marker
class B {}
""")
    # ``class A`` is bodyless and the next declaration opens with its own
    # annotation block: A's range ends before ``@Marker`` (line 3), so the
    # annotation is never absorbed into A and B starts exactly at its
    # ``class`` keyword.
    assert _tuples(scan.helper_ranges) == (
        (path, "example.A", "class", 2, 2, False, False, 24, 24),
        (path, "example.B", "class", 4, 4, False, False, 41, 41),
    )
    bodyless = next(item for item in scan.helper_ranges
                    if item.owner_fqcn == "example.A")
    assert (bodyless.start_line, bodyless.end_line) == (2, 2)
    assert (bodyless.body_start, bodyless.body_end) == (24, 24)
    sibling = next(item for item in scan.helper_ranges
                   if item.owner_fqcn == "example.B")
    assert (sibling.start_line, sibling.end_line) == (4, 4)


def test_bodyless_owner_ends_before_modifier_prefixed_sibling(tmp_path):
    path = "app/src/main/java/example/ModifierSibling.kt"
    scan = _scan(tmp_path, path, """package example
class A
override fun first() {}
suspend fun second() {}
inline fun third() {}
inner class Fourth {}
noinline fun seventh(f: () -> Unit) {}
crossinline fun eighth(f: () -> Unit) {}
reified fun ninth() {}
class B
const val FIFTH = 1
class C
lateinit var sixth: String
""")
    # Every leading token is a Kotlin declaration/parameter modifier that
    # binds the following declaration's kind keyword.  Each bodyless owner
    # (``class A``, ``class B``, ``class C``) must end before the next
    # sibling's modifier block (``override``, ``const``, ``lateinit``) --
    # never absorbing it -- and the modifier-prefixed sibling is then owned
    # exactly where its kind keyword starts.
    assert _tuples(scan.helper_ranges) == (
        (path, "example.A", "class", 2, 2, False, False, 24, 24),
        (path, "example", "function", 3, 3, False, False, 46, 46),
        (path, "example", "function", 4, 4, False, False, 70, 70),
        (path, "example", "function", 5, 5, False, False, 92, 92),
        (path, "example.Fourth", "class", 6, 6, False, False, 114, 114),
        (path, "example", "function", 7, 7, False, False, 153, 153),
        (path, "example", "function", 8, 8, False, False, 194, 194),
        (path, "example", "function", 9, 9, False, False, 217, 217),
        (path, "example.B", "class", 10, 10, False, False, 227, 227),
        (path, "example", "property", 11, 11, False, False, None, None),
        (path, "example.C", "class", 12, 12, False, False, 255, 255),
        (path, "example", "property", 13, 13, False, False, None, None),
    )
    owner = next(item for item in scan.helper_ranges
                 if item.owner_fqcn == "example.A")
    sibling = next(item for item in scan.helper_ranges
                   if item.owner_fqcn == "example" and item.kind == "function"
                   and item.start_line == 3)
    # The bodyless owner's exact range ends at its own line, before the
    # sibling's ``override`` modifier block; the modifier-prefixed sibling is
    # a real top-level declaration owned by the package, never absorbed into
    # ``class A``.
    assert (owner.start_line, owner.end_line) == (2, 2)
    assert (owner.body_start, owner.body_end) == (24, 24)
    assert owner.end_line < sibling.start_line
    assert (sibling.start_line, sibling.end_line) == (3, 3)
    assert sibling.owner_fqcn == "example"
    # ``const`` and ``lateinit`` are modifiers too: bodyless owners B and C
    # end exactly at the modifier block start (not at the later ``val``/
    # ``var`` kind keyword), and the properties they introduce are owned by
    # the package.
    assert next(item for item in scan.helper_ranges
                if item.owner_fqcn == "example.B").body_start == 227
    assert next(item for item in scan.helper_ranges
                if item.owner_fqcn == "example.C").body_start == 255
    assert scan.diagnostics == ()


def test_bodyless_owner_ends_before_value_class_modifier_sibling(tmp_path):
    path = "app/src/main/java/example/ValueSibling.kt"
    scan = _scan(tmp_path, path, """package example
class A
value class B(val x: Int)
""")
    # ``value`` is a declaration modifier (``value class``): the bodyless
    # ``class A`` must end before the sibling's ``value`` block (line 3), so
    # the modifier is never absorbed into A, and ``value class B`` is then
    # owned exactly where its ``class`` keyword starts with its own empty
    # body.
    assert scan.diagnostics == ()
    assert _tuples(scan.helper_ranges) == (
        (path, "example.A", "class", 2, 2, False, False, 24, 24),
        (path, "example.B", "class", 3, 3, False, False, 50, 50),
    )
    bodyless = next(item for item in scan.helper_ranges
                    if item.owner_fqcn == "example.A")
    sibling = next(item for item in scan.helper_ranges
                   if item.owner_fqcn == "example.B")
    # A's empty body ends exactly at the ``value`` modifier start (24), never
    # at the later ``class`` keyword (30), so the value-class sibling is never
    # absorbed into A.
    assert (bodyless.body_start, bodyless.body_end) == (24, 24)
    assert (sibling.body_start, sibling.body_end) == (50, 50)
    assert bodyless.end_line < sibling.start_line


def test_bodyless_function_header_ends_before_fresh_line_annotation_sibling(tmp_path):
    path = "app/src/main/java/example/AnnotatedFunction.kt"
    scan = _scan(tmp_path, path, """package example
fun first()
@Marker
fun second() {}
""")
    # ``_header_tokens`` ends a bodyless function header at a fresh-line
    # annotation block: ``fun first()`` (line 2) never absorbs ``@Marker``
    # (line 3) into its range, and the annotated sibling starts exactly at
    # its own ``fun`` keyword (line 4).
    assert scan.diagnostics == ()
    assert _tuples(scan.helper_ranges) == (
        (path, "example", "function", 2, 2, False, False, None, None),
        (path, "example", "function", 4, 4, False, False, 50, 50),
    )
    first = next(item for item in scan.helper_ranges
                 if item.owner_fqcn == "example" and item.start_line == 2)
    assert (first.start_line, first.end_line) == (2, 2)
    assert (first.body_start, first.body_end) == (None, None)


def test_direct_annotation_on_bodyless_owner_is_preserved(tmp_path):
    # ``@Marker`` directly attached to ``class A`` belongs to A: the range
    # starts at the ``class`` keyword (the same convention as ``@Dao``
    # interfaces), the annotation is never treated as a sibling boundary, and
    # the bodyless owner is inventoried exactly.
    for relative, source, expected in (
        ("DirectAnnotation.kt", "package example\n@Marker\nclass A\n", (3, 3)),
        ("DirectInline.kt", "package example\n@Marker class A\n", (2, 2)),
    ):
        path = "app/src/main/java/example/" + relative
        # Each case is a separate source file; use a fresh scan root so the
        # first fixture cannot remain in the inventory for the second case.
        scan = _scan(tmp_path / relative.removesuffix(".kt"), path, source)
        assert scan.diagnostics == ()
        assert _tuples(scan.helper_ranges) == (
            (path, "example.A", "class", expected[0], expected[1],
             False, False, 32, 32),
        )
        bodyless = scan.helper_ranges[0]
        assert (bodyless.start_line, bodyless.end_line) == expected
        assert (bodyless.body_start, bodyless.body_end) == (32, 32)


def test_arrow_in_string_and_comment_is_masked_and_ranges_survive(tmp_path):
    path = "app/src/main/java/example/MaskedArrows.kt"
    scan = _scan(tmp_path, path, """package example
class Holder(val factory: (Int) -> String = { it.toString() }) {
    // arrow -> inside a comment
    val text = "arrow -> inside a string"
    fun convert(value: (Int) -> String = { it.toString() }) { }
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Holder", "class", 2, 6, False, False, 80, 220),
        (path, "example.Holder", "property", 4, 4, False, False, None, None),
        (path, "example.Holder", "function", 5, 5, False, False, 217, 218),
    )


@pytest.mark.parametrize("source", [
    "package example\nfun broken(value: (Int) -> String {\n",
    "package example\nval broken = {\n",
])
def test_malformed_top_level_function_or_property_is_diagnostics_only(tmp_path, source):
    scan = _scan(tmp_path, "app/src/main/java/example/TopLevel.kt", source)
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (
        _diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/main/java/example/TopLevel.kt"),
    )


@pytest.mark.parametrize("source", ["package example\nval answer = 1\n", "package example\nval answer = 1"])
def test_range_end_line_uses_exclusive_end_and_handles_final_newline(tmp_path, source):
    path = "app/src/main/java/example/TopLevel.kt"
    scan = _scan(tmp_path, path, source)
    answer = next(item for item in scan.helper_ranges if item.kind == "property")
    assert (answer.start_line, answer.end_line) == (2, 2)


def test_multiline_property_consumes_balanced_lambda_and_stops_at_next_direct_declaration(tmp_path):
    path = "app/src/main/java/example/Multiline.kt"
    source = """package example
class Holder {
    val loaded = listOf(
        "not a declaration { val fake = 1 }",
        run {
            dao.insert()
            dao.delete()
        }
    )
    fun next() {}
}
"""
    scan = _scan(tmp_path, path, source)
    prop = next(item for item in scan.helper_ranges if item.kind == "property")
    assert (prop.start_line, prop.end_line) == (3, 8)
    assert prop.body_start == source.index("{\n            dao") + 1
    assert prop.body_end == source.index("\n        }\n    )") + len("\n        ")
    following = next(item for item in scan.helper_ranges
                     if item.kind == "function" and item.owner_fqcn == "example.Holder")
    assert (following.start_line, following.end_line) == (10, 10)


def test_braces_in_strings_and_comments_do_not_change_owner_ranges(tmp_path):
    path = "app/src/main/java/example/Braces.kt"
    scan = _scan(tmp_path, path, """package example
class Braces {
    // } { this is not structure
    val text = "{ still a string }"
    fun write() { println(text) }
}
""")
    owner = next(item for item in scan.helper_ranges if item.kind == "class")
    method = next(item for item in scan.helper_ranges if item.kind == "function")
    assert (owner.start_line, owner.end_line) == (2, 6)
    assert (method.start_line, method.end_line) == (5, 5)


def test_enum_and_annotation_owner_kinds_are_preserved(tmp_path):
    path = "app/src/main/java/example/Owners.kt"
    scan = _scan(tmp_path, path, """package example
enum class Mode { ONE, TWO }
annotation class Marker {
    val value: String = "x"
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Mode", "enum", 2, 2, False, False, 33, 43),
        (path, "example.Marker", "annotation", 3, 5, False, True, 70, 99),
        (path, "example.Marker", "property", 4, 4, False, False, None, None),
    )


def test_each_enum_and_annotation_owner_has_exactly_one_owner_tuple(tmp_path):
    # The generic class declaration pattern must never claim the ``class``
    # keyword of an ``enum class``/``annotation class`` declaration: each
    # enum/annotation owner is inventoried exactly once with its exclusive
    # kind, and no duplicate generic ``class`` owner exists for it.  This
    # holds for bare, modifier-prefixed, and nested declarations.
    path = "app/src/main/java/example/ExclusiveOwners.kt"
    scan = _scan(tmp_path, path, """package example
enum class Mode { ONE, TWO }
public enum class Flag { ON, OFF }
annotation class Marker
class Holder {
    enum class Inner { A }
    annotation class Ann {
        val value: String = "x"
    }
}
""")
    assert _tuples(scan.helper_ranges) == (
        (path, "example.Mode", "enum", 2, 2, False, False, 33, 43),
        (path, "example.Flag", "enum", 3, 3, False, False, 69, 78),
        (path, "example.Marker", "annotation", 4, 4, False, True, 104, 104),
        (path, "example.Holder", "class", 5, 10, False, False, 118, 211),
        (path, "example.Holder.Inner", "enum", 6, 6, False, False, 141, 144),
        (path, "example.Holder.Ann", "annotation", 7, 9, False, True, 172, 209),
        (path, "example.Holder.Ann", "property", 8, 9, False, False, None, None),
    )
    for fqcn in (
        "example.Mode", "example.Flag", "example.Marker",
        "example.Holder.Inner", "example.Holder.Ann",
    ):
        exclusive = [item for item in scan.helper_ranges
                     if item.owner_fqcn == fqcn and item.kind in ("enum", "annotation")]
        assert len(exclusive) == 1
        assert not any(item.kind == "class" and item.owner_fqcn == fqcn
                       for item in scan.helper_ranges)


def test_symlinked_kotlin_file_outside_root_is_rejected_without_ranges(tmp_path):
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True)
    outside = tmp_path / "outside.kt"
    outside.write_text("class Escaped {}", encoding="utf-8")
    try:
        (root / "Escaped.kt").symlink_to(outside)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    scan = scan_production_declarations(tmp_path)
    assert scan.dao_declarations == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_SYMLINK_OUTSIDE"),)


def test_symlinked_parent_component_is_rejected_before_resolution(tmp_path):
    # The supplied root itself is not a symlink, but a parent component is:
    # the scan-level leaf check cannot see the escape and resolution would
    # silently dereference it, so the component walk must fail closed first.
    real = tmp_path / "real"
    _write(real, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    parent = tmp_path / "parent"
    try:
        parent.symlink_to(real, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    scan = scan_production_declarations(parent / "app" / "src" / "main" / "java")
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_SYMLINK_OUTSIDE"),)


def test_relative_supplied_root_under_symlinked_parent_is_rejected_before_resolution(tmp_path, monkeypatch):
    # The supplied root is a RELATIVE path and the process working directory
    # is itself reached through a symlinked parent.  For a relative root
    # ``Path(root).anchor`` is empty, so a walk anchored at the caller's
    # anchor would start at the current directory and never see the parent
    # symlink; the absolute-lexical derivation walks from the drive anchor
    # and must reject the escape before ``resolve`` dereferences it into a
    # trusted path.
    real = tmp_path / "real"
    _write(real, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    link = tmp_path / "link"
    try:
        link.symlink_to(real, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    monkeypatch.chdir(link)
    scan = scan_production_declarations(Path("app") / "src" / "main" / "java")
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_SYMLINK_OUTSIDE"),)
    joined = json.dumps([d.to_dict() for d in scan.diagnostics])
    assert "Good.kt" not in joined
    assert str(link) not in joined


def test_real_root_without_symlinked_components_scans_normally(tmp_path):
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    scan = scan_production_declarations(tmp_path)
    assert scan.files_scanned == ("app/src/main/java/example/Good.kt",)
    assert not any(d.code == "DB_DECLARATION_SYMLINK_OUTSIDE" for d in scan.diagnostics)
    assert any(item.kind == "class" for item in scan.helper_ranges)


def test_relative_real_root_without_symlinked_components_scans_normally(tmp_path, monkeypatch):
    # A real root supplied as a relative path stays accepted: the
    # absolute-lexical derivation walks every component from the drive anchor
    # (no symlinked component), so the normal scan proceeds and the root is
    # never downgraded to an invalid source or a symlink escape.
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    monkeypatch.chdir(tmp_path)
    scan = scan_production_declarations(Path("app") / "src" / "main" / "java")
    assert scan.files_scanned == ("app/src/main/java/example/Good.kt",)
    assert not any(d.code == "DB_DECLARATION_SYMLINK_OUTSIDE" for d in scan.diagnostics)
    assert not any(d.code == "DB_DECLARATION_INVALID_SOURCE" for d in scan.diagnostics)
    assert any(item.kind == "class" and item.owner_fqcn == "example.Good" for item in scan.helper_ranges)


def test_windows_style_temp_source_root_scans_normally_not_invalid_source(tmp_path):
    # Regression: a normal temporary/source root reaches the scanner with a
    # string anchor (``C:\\`` on Windows, ``/`` on POSIX).  The symlink
    # component walk used to mix that string with ``Path / part``, raising a
    # TypeError on the first component that was downgraded to
    # ``DB_DECLARATION_INVALID_SOURCE``, so a valid root never scanned.  The
    # anchor is now handled as a ``Path`` consistently, so the root must scan
    # successfully and never be reported as an invalid source.
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    scan = scan_production_declarations(tmp_path)
    assert scan.files_scanned == ("app/src/main/java/example/Good.kt",)
    assert not any(d.code == "DB_DECLARATION_INVALID_SOURCE" for d in scan.diagnostics)
    assert any(item.kind == "class" and item.owner_fqcn == "example.Good" for item in scan.helper_ranges)


def test_diagnostic_serialization_keeps_code_path_location_and_context_separate(tmp_path):
    path = "app/src/main/java/example/Broken.kt"
    scan = _scan(tmp_path, path, "package example\nclass Broken {\n")
    diagnostic = scan.diagnostics[0]
    assert diagnostic.code == "DB_DECLARATION_UNRESOLVED"
    assert diagnostic.path == path
    assert diagnostic.to_dict() == {
        "code": "DB_DECLARATION_UNRESOLVED",
        "path": path,
        "location": None,
        "controlled_context": {},
    }
    assert ":" not in diagnostic.code


def _nested_context(depth: int):
    """Build a nested dict chain of ``depth`` mapping levels ending in a scalar."""
    node: dict = {"leaf": "x"}
    for _ in range(depth):
        node = {"n": node}
    return node


def test_valid_controlled_context_is_deep_frozen_and_roundtrips():
    diag = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        controlled_context={
            "count": 3,
            "detail": "declared",
            "nested": {"target": "dao", "flags": [1, 2]},
            "enabled": True,
            "nothing": None,
        },
    )
    context = diag.controlled_context
    assert isinstance(context, declaration_scanner.FrozenContext)
    assert context["count"] == 3
    assert isinstance(context["nested"], declaration_scanner.FrozenContext)
    assert isinstance(context["nested"]["flags"], tuple)
    assert context["nested"]["flags"] == (1, 2)
    assert diag.to_dict()["controlled_context"] == {
        "count": 3,
        "detail": "declared",
        "nested": {"target": "dao", "flags": [1, 2]},
        "enabled": True,
        "nothing": None,
    }
    # Deep immutability: nested mappings are frozen and sequences are tuples,
    # so mutation attempts fail instead of silently mutating the diagnostic.
    with pytest.raises(TypeError):
        context["nested"]["target"] = "mutated"
    with pytest.raises(TypeError):
        context["nested"]["flags"] += (3,)
    with pytest.raises(TypeError):
        context["count"] = 4


def test_controlled_context_is_sorted_deterministic_and_serializable():
    diag = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        controlled_context={"b": 2, "a": 1, "n": {"z": 1, "y": 2}},
    )
    assert diag.to_dict()["controlled_context"] == {"b": 2, "a": 1, "n": {"z": 1, "y": 2}}
    payload = json.dumps(diag.to_dict(), sort_keys=True)
    assert json.loads(payload)["controlled_context"] == {"b": 2, "a": 1, "n": {"z": 1, "y": 2}}


@pytest.mark.parametrize("key", [
    "source", "snippet", "exception", "traceback", "stack", "sql", "ocr",
    "user", "raw", "path",
])
def test_controlled_context_rejects_forbidden_keys(key):
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={key: "value"})
    assert error.value.code == "FORBIDDEN_CONTEXT_KEY"
    assert key not in str(error.value)


@pytest.mark.parametrize("key", [
    "user_payload", "raw_sql", "source_file", "nested_exception",
])
def test_controlled_context_rejects_forbidden_key_word_parts(key):
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={key: "value"})
    assert error.value.code == "FORBIDDEN_CONTEXT_KEY"


def test_controlled_context_rejects_forbidden_keys_nested():
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(
            code="DB_DECLARATION_UNRESOLVED",
            controlled_context={"section": {"details": {"sql": "SELECT * FROM t"}}},
        )
    assert error.value.code == "FORBIDDEN_CONTEXT_KEY"


def test_controlled_context_rejects_oversized_and_deep_values():
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(
            code="DB_DECLARATION_UNRESOLVED",
            controlled_context={"detail": "x" * (MAX_CONTEXT + 1)},
        )
    assert error.value.code == "STRING_TOO_LONG"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(
            code="DB_DECLARATION_UNRESOLVED",
            controlled_context=_nested_context(MAX_CONTEXT_DEPTH),
        )
    assert error.value.code == "CONTEXT_TOO_DEEP"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(
            code="DB_DECLARATION_UNRESOLVED",
            controlled_context={"items": ["x"] * (MAX_CONTEXT_ITEMS + 1)},
        )
    assert error.value.code == "CONTEXT_TOO_MANY"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(
            code="DB_DECLARATION_UNRESOLVED",
            controlled_context={f"key{i}": "x" for i in range(MAX_CONTEXT_ITEMS + 1)},
        )
    assert error.value.code == "CONTEXT_TOO_MANY"


def test_controlled_context_accepts_boundary_depth_and_items():
    diag = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        controlled_context=_nested_context(MAX_CONTEXT_DEPTH - 1),
    )
    assert diag.controlled_context["n"]["n"]["n"]["leaf"] == "x"
    ok = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        controlled_context={"detail": "x" * MAX_CONTEXT, "items": ["x"] * MAX_CONTEXT_ITEMS},
    )
    assert ok.controlled_context["detail"] == "x" * MAX_CONTEXT
    assert isinstance(ok.controlled_context["items"], tuple)


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
def test_controlled_context_rejects_nonfinite_numbers(bad):
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"value": bad})
    assert error.value.code == "NON_FINITE_NUMBER"


def test_controlled_context_rejects_out_of_range_numbers():
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"value": MAX_CONTEXT_NUMBER + 1})
    assert error.value.code == "NUMBER_OUT_OF_RANGE"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"value": -MAX_CONTEXT_NUMBER - 1})
    assert error.value.code == "NUMBER_OUT_OF_RANGE"
    ok = Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"value": MAX_CONTEXT_NUMBER})
    assert ok.controlled_context["value"] == MAX_CONTEXT_NUMBER


def test_controlled_context_rejects_raw_path_and_exception_text():
    for raw in (
        "C:\\Users\\panos\\secret.kt",
        "/home/user/secret.kt",
        "Traceback (most recent call last):",
        "PermissionError: raw secret path",
    ):
        with pytest.raises(DiagnosticContextError) as error:
            Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"detail": raw})
        assert error.value.code == "RAW_CONTEXT_VALUE"
        assert raw not in str(error.value)


@pytest.mark.parametrize("raw", [
    "secret/file.kt",                                   # relative, filename tail
    "app/src/main/java/example/Secret.kt",              # relative, deep hierarchy
    "../x",                                             # traversal
    "./x",                                              # current-dir
    "src/main/java",                                    # directory hierarchy
    "dir/",                                             # trailing slash / empty segment
])
def test_controlled_context_rejects_relative_path_shapes(raw):
    # Relative path-shaped strings are raw paths even when they are not
    # rooted, and must never enter controlled context.
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"detail": raw})
    assert error.value.code == "RAW_CONTEXT_VALUE"
    assert raw not in str(error.value)


@pytest.mark.parametrize("raw", [
    "C:secret.kt",                                      # drive-relative, no separator
    "C:foo/bar",                                        # drive-relative with slash
    "D:tmp",                                            # drive-relative directory name
    "c:temp\\notes.txt",                                # lowercase drive, backslash
])
def test_controlled_context_rejects_drive_relative_values(raw):
    # Any ``X:`` prefix is a drive designator, not a controlled identifier,
    # so drive-relative values without a separator after the colon are raw
    # path text too and must never enter controlled context.
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"detail": raw})
    assert error.value.code == "RAW_CONTEXT_VALUE"
    assert raw not in str(error.value)


@pytest.mark.parametrize("raw", [
    "secret.kt", "file.json", "trace.log", "backup.db", "foo.py",
    "data.sqlite", "notes.txt", "Report.csv", "settings.xml",
    "config.yaml", "db.sql", "archive.zip", "report.md", "app.apk",
    "id_rsa.key", "Credentials.pem", "trace.log.bak",
])
def test_controlled_context_rejects_single_segment_filenames(raw):
    # A separator-free value whose final dot segment is a known file
    # extension is a raw filename (the extension set is case-insensitive),
    # never a controlled reason identifier.
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"detail": raw})
    assert error.value.code == "RAW_CONTEXT_VALUE"
    assert raw not in str(error.value)


@pytest.mark.parametrize("raw", [
    ".gitignore", ".env", ".secret", ".DS_Store", ".hidden.kt",
])
def test_controlled_context_rejects_hidden_files(raw):
    # Hidden/dotfile names are raw filenames even though they have no
    # extension-shaped suffix.
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"detail": raw})
    assert error.value.code == "RAW_CONTEXT_VALUE"
    assert raw not in str(error.value)


@pytest.mark.parametrize("raw", [
    "secret\\file.kt",                                  # backslash separator
    "dir/sub/file.kt",                                  # deep hierarchy, dotted tail
    "a/b/c",                                            # three or more segments
    "x/../y",                                           # traversal segment
    "app/src/main/java/example/Secret.kt/..",           # traversal tail
])
def test_controlled_context_rejects_path_separator_shapes(raw):
    # Path separators (backslash or multi-segment slash hierarchies) mark a
    # value as raw path text; hostile values never echo.
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"detail": raw})
    assert error.value.code == "RAW_CONTEXT_VALUE"
    assert raw not in str(error.value)


def test_controlled_context_retains_valid_controlled_identifiers():
    # Controlled identifiers and reason codes survive raw-path detection:
    # a namespaced short identifier, an uppercase reason code, a plain stage
    # name, and dotted identifiers the contract allows (``foo.bar``) are all
    # valid context values that round-trip unchanged.
    for valid in (
        "writer/helper",
        "EXPORT_FAILED",
        "stage",
        "foo.bar",
        "db_access",
        "example.ReportingDao",
        "symbol.owner",
    ):
        diag = Diagnostic(
            code="DB_DECLARATION_UNRESOLVED",
            controlled_context={"target": valid},
        )
        assert diag.controlled_context["target"] == valid
        assert diag.to_dict()["controlled_context"] == {"target": valid}
        plain = json.dumps(diag.to_dict(), sort_keys=True)
        assert json.loads(plain)["controlled_context"] == {"target": valid}


def test_controlled_context_retains_valid_short_identifiers_with_single_namespace_separator():
    # Short controlled reason/target identifiers with one namespace separator
    # are allowed by contract and must keep round-tripping.
    diag = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        controlled_context={"target": "writer/helper"},
    )
    assert diag.controlled_context["target"] == "writer/helper"
    assert diag.to_dict()["controlled_context"] == {"target": "writer/helper"}
    plain = json.dumps(diag.to_dict(), sort_keys=True)
    assert json.loads(plain)["controlled_context"] == {"target": "writer/helper"}


def test_controlled_context_rejects_non_mapping_and_non_json_values():
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"value": {"a", "b"}})
    assert error.value.code == "NOT_JSONABLE"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={"value": object()})
    assert error.value.code == "NOT_JSONABLE"


def test_controlled_context_error_never_echoes_raw_values():
    raw_key = "sql\x00secret"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", controlled_context={raw_key: "raw payload"})
    assert error.value.code == "INVALID_CONTEXT_KEY"
    message = str(error.value)
    assert "sql" not in message
    assert "raw payload" not in message


def test_invalid_context_at_scan_level_emits_controlled_diagnostic_and_clears_ranges(tmp_path, monkeypatch):
    # A diagnostic whose controlled_context fails protocol validation must
    # fail the complete inventory closed: only the controlled unresolved
    # diagnostic survives and every range is cleared.
    empty_root = tmp_path / "empty" / "app" / "src" / "main" / "java"
    empty_root.mkdir(parents=True)
    real_diag = declaration_scanner._diag

    def bad_diag(code, path=None, *, location=None, context=None):
        if code == "DB_DECLARATION_SOURCE_EMPTY":
            return Diagnostic(code, path, location, {"sql": "raw"})
        return real_diag(code, path, location=location, context=context)

    monkeypatch.setattr(declaration_scanner, "_diag", bad_diag)
    scan = scan_production_declarations(empty_root)
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_UNRESOLVED"),)


def test_per_file_unreadable_source_fails_closed_without_raw_path_or_exception(monkeypatch, tmp_path):
    # Windows cannot reliably deny reads through chmod/ACL bit flips, so the
    # unreadable file is injected through the scanner's read hook instead.
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    _write(tmp_path, "app/src/main/java/example/Unreadable.kt", "package example\nclass Unreadable {}\n")
    real_read_text = Path.read_text

    def deny_read(path, *args, **kwargs):
        if Path(path).name == "Unreadable.kt":
            raise PermissionError("raw secret path")
        return real_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", deny_read)
    scan = scan_production_declarations(tmp_path)
    assert any(
        d.code == "DB_DECLARATION_SOURCE_UNREADABLE"
        and d.path == "app/src/main/java/example/Unreadable.kt"
        for d in scan.diagnostics
    )
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    joined = json.dumps([d.to_dict() for d in scan.diagnostics])
    assert "raw secret path" not in joined
    assert "PermissionError" not in joined
    assert str(tmp_path) not in joined


def test_diagnostic_rejects_unknown_code_without_echoing():
    unknown = "DB_MADE_UP_UNKNOWN_CODE"
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code=unknown)
    assert error.value.code == "DB_DECLARATION_UNRESOLVED"
    message = str(error.value)
    assert unknown not in message
    assert "MADE_UP" not in message


def test_diagnostic_rejects_non_string_code():
    for bad in (None, 123, ("DB_DECLARATION_UNRESOLVED",)):
        with pytest.raises(DiagnosticContextError) as error:
            Diagnostic(code=bad)
        assert error.value.code == "DB_DECLARATION_UNRESOLVED"


@pytest.mark.parametrize("bad", [
    "C:\\Users\\panos\\secret.kt",                     # drive + backslash
    "/app/src/main/java/example/File.kt",              # absolute
    "app\\src\\main\\java\\example\\File.kt",          # backslash
    "app/src/main/../java/example/File.kt",            # traversal
    "app/src/main/java/./example/File.kt",             # dot segment
    "app/src/main/java/example/File.kt/..",            # traversal tail
    "app/src/main/java/example/F ile.kt",              # internal whitespace
    "app/src/main/java/example/File.kt ",              # trailing whitespace
    " app/src/main/java/example/File.kt",              # leading whitespace
    "app/src/main/java/example/\x00File.kt",           # NUL byte
    "app/src/main/java/example/\x1fFile.kt",           # control char
    "app/src/main/java/example/File.kt\x7f",           # DEL control
    "src/main/java/example/File.kt",                   # outside app/src root
    "lib/src/main/java/example/File.kt",               # outside app/src root
    "app/src",                                         # not under app/src/
    "app/src/",                                        # root only, empty tail
    "app//src/main/java/example/File.kt",              # empty segment
    "app/src/main/java/example/File.kt" + "x" * 500,   # unbounded length
])
def test_diagnostic_rejects_invalid_path_without_echoing(bad):
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", path=bad)
    assert error.value.code == "INVALID_DIAGNOSTIC_PATH"
    message = str(error.value)
    assert bad not in message


def test_diagnostic_rejects_non_string_path():
    for bad in (123, Path("app/src/main/java/example/File.kt")):
        with pytest.raises(DiagnosticContextError) as error:
            Diagnostic(code="DB_DECLARATION_UNRESOLVED", path=bad)
        assert error.value.code == "INVALID_DIAGNOSTIC_PATH"


@pytest.mark.parametrize("bad", [
    {"line": 0, "column": None},
    {"line": -1, "column": None},
    {"line": True, "column": None},
    {"line": 3, "column": 0},
    {"line": 3, "column": -2},
    {"line": 3, "column": True},
    {"line": 3, "column": "17"},
    {"line": "3", "column": None},
    {"line": 3.0, "column": None},
    {"line": 3, "column": 17.0},
    {"line": None, "column": None},
    {"line": 3},                                     # missing column
    {"line": 3, "column": None, "end_line": 9},      # arbitrary extra key
    {"line": MAX_LOCATION_NUMBER + 1, "column": None},
    {"line": 3, "column": MAX_LOCATION_NUMBER + 1},
])
def test_diagnostic_rejects_invalid_location(bad):
    with pytest.raises(DiagnosticContextError) as error:
        Diagnostic(code="DB_DECLARATION_UNRESOLVED", location=bad)
    assert error.value.code == "INVALID_DIAGNOSTIC_LOCATION"
    message = str(error.value)
    assert repr(bad) not in message


def test_diagnostic_rejects_non_mapping_location():
    for bad in ("3:17", 3, [3, 17]):
        with pytest.raises(DiagnosticContextError) as error:
            Diagnostic(code="DB_DECLARATION_UNRESOLVED", location=bad)
        assert error.value.code == "INVALID_DIAGNOSTIC_LOCATION"


def test_diagnostic_valid_path_and_location_serialize_exactly():
    diag = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        path="app/src/main/java/example/Broken.kt",
        location={"line": 3, "column": 17},
        controlled_context={"reason": "scan failure"},
    )
    assert diag.path == "app/src/main/java/example/Broken.kt"
    assert diag.location == {"line": 3, "column": 17}
    assert diag.to_dict() == {
        "code": "DB_DECLARATION_UNRESOLVED",
        "path": "app/src/main/java/example/Broken.kt",
        "location": {"line": 3, "column": 17},
        "controlled_context": {"reason": "scan failure"},
    }
    no_column = Diagnostic(
        code="DB_DECLARATION_UNRESOLVED",
        location={"line": 5, "column": None},
    )
    assert no_column.to_dict() == {
        "code": "DB_DECLARATION_UNRESOLVED",
        "path": None,
        "location": {"line": 5, "column": None},
        "controlled_context": {},
    }


def test_standalone_empty_kotlin_file_is_diagnostics_only(tmp_path):
    path = "app/src/main/java/example/Empty.kt"
    scan = _scan(tmp_path, path, "   \n\n\t\n  ")
    assert scan.files_scanned == (path,)
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (_diagnostic("DB_DECLARATION_SOURCE_EMPTY", path),)


def test_mixed_valid_and_empty_kotlin_files_are_diagnostics_only(tmp_path):
    _write(tmp_path, "app/src/main/java/example/Good.kt", "package example\nclass Good {}\n")
    _write(tmp_path, "app/src/main/java/example/Empty.kt", "\n \n")
    scan = scan_production_declarations(tmp_path)
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()
    assert scan.diagnostics == (
        _diagnostic("DB_DECLARATION_SOURCE_EMPTY", "app/src/main/java/example/Empty.kt"),
    )
    joined = json.dumps([d.to_dict() for d in scan.diagnostics])
    assert "class Good" not in joined


# ── Shared root-set resolution (PR-GR-03 Slice C2) ───────────────────────────


def _write_repo_manifest(repo: Path, paths: tuple[str, ...]) -> None:
    payload = {
        "schemaVersion": 1,
        "roots": [
            {"module": ":app", "sourceSet": "main", "path": path}
            for path in paths
        ],
    }
    _write(repo, "config/guards/production_source_roots.yml",
           yaml.safe_dump(payload))


def test_manifest_declared_kotlin_root_declarations_are_discovered(tmp_path):
    """A manifest declaring ``app/src/main/kotlin`` puts that root's Kotlin
    declarations in the inventory under repository-relative POSIX paths."""
    _write_repo_manifest(tmp_path, ("app/src/main/kotlin",))
    path = "app/src/main/kotlin/example/KotlinRepo.kt"
    _write(tmp_path, path, "package example\nclass KotlinRepo {\n    fun load() {}\n}\n")
    scan = scan_production_declarations(tmp_path)
    assert scan.diagnostics == ()
    assert scan.files_scanned == (path,)
    owner = next(item for item in scan.helper_ranges
                 if item.owner_fqcn == "example.KotlinRepo")
    assert (owner.kind, owner.path) == ("class", path)


def test_undeclared_kotlin_root_fails_closed_without_partial_results(tmp_path):
    """Production Kotlin beside a declared java root but omitted from the
    manifest is a declared-vs-observed mismatch: the complete scan fails
    closed with a controlled ``DB_SOURCE_ROOT_*`` failure and nothing — not
    even the declared java file — is scanned."""
    _write_repo_manifest(tmp_path, ("app/src/main/java",))
    _write(tmp_path, "app/src/main/java/example/Declared.kt",
           "package example\nclass Declared {}\n")
    _write(tmp_path, "app/src/main/kotlin/example/Undeclared.kt",
           "package example\nclass Undeclared {}\n")
    scan = scan_production_declarations(tmp_path)
    assert [item.code for item in scan.diagnostics] == [
        "DB_DECLARATION_INVALID_SOURCE",
        "DB_SOURCE_ROOT_UNDECLARED",
    ]
    assert scan.files_scanned == ()
    assert scan.dao_declarations == ()
    assert scan.skipped_dao_declaration_ranges == ()
    assert scan.helper_ranges == ()


def test_declaration_scanner_and_room_inventory_agree_on_root_membership(tmp_path):
    """For a synthetic two-root repository the declaration scanner considers
    exactly the declared production Kotlin file set, and the room inventory
    discovers the same membership (the Kotlin-root DAO included)."""
    _write_repo_manifest(tmp_path, ("app/src/main/java", "app/src/main/kotlin"))
    _write(tmp_path, "app/src/main/java/example/JavaHelper.kt",
           "package example\nclass JavaHelper {}\n")
    _write(tmp_path, "app/src/main/kotlin/example/KotlinDao.kt",
           "package example\n@Dao interface KotlinDao { @Insert fun put(v: Item) }\n")
    root_set = SourceRootSet(roots=(
        SourceRoot(module=":app", source_set="main", path="app/src/main/java"),
        SourceRoot(module=":app", source_set="main", path="app/src/main/kotlin"),
    ))
    collected, collection_diagnostics = collect_production_kotlin_files(
        str(tmp_path), root_set
    )
    assert collection_diagnostics == ()
    scan = scan_production_declarations(tmp_path, root_set=root_set)
    assert scan.diagnostics == ()
    # Identical root membership: the same files, spanning both declared roots.
    assert sorted(scan.files_scanned) == sorted(collected)
    assert scan.files_scanned == (
        "app/src/main/java/example/JavaHelper.kt",
        "app/src/main/kotlin/example/KotlinDao.kt",
    )
    inventory = build_room_inventory(
        tmp_path, {"version": 1, "methods": []}, source_root_set=root_set
    )
    assert not inventory.diagnostics
    assert [dao.fqcn for dao in inventory.daos] == ["example.KotlinDao"]
    assert [method.dao.canonical_path for method in inventory.methods] == [
        "app/src/main/kotlin/example/KotlinDao.kt"
    ]


def test_duplicate_fqcn_across_declared_roots_membership_agrees_and_inventory_fails_closed(tmp_path):
    """Mirror of the room-inventory cross-root duplicate-FQCN fixture over the
    shared root-set resolution: the declaration scanner has no
    duplicate-identity surface, so it agrees with the inventory only on
    declared-root membership -- both copies are scanned and inventoried as
    distinct path-anchored DAO ranges with no scanner diagnostic -- while the
    fail-closed duplicate outcome remains exclusively the room inventory's
    controlled ``DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS`` contract."""
    source = "package com.example\n@Dao interface DuplicatedDao { @Insert fun put(v: Item) }\n"
    java_path = "app/src/main/java/com/example/DuplicatedDao.kt"
    kotlin_path = "app/src/main/kotlin/com/example/DuplicatedDao.kt"
    _write_repo_manifest(tmp_path, ("app/src/main/java", "app/src/main/kotlin"))
    _write(tmp_path, java_path, source)
    _write(tmp_path, kotlin_path, source)
    scan = scan_production_declarations(tmp_path)
    # Membership agreement: both declared roots' copies are scanned.
    assert scan.files_scanned == (java_path, kotlin_path)
    # The range scanner claims no duplicate-identity diagnostic: each copy is
    # its own path-anchored DAO range.
    assert scan.diagnostics == ()
    assert [(item.path, item.owner_fqcn, item.kind) for item in scan.dao_declarations] == [
        (java_path, "com.example.DuplicatedDao", "dao"),
        (kotlin_path, "com.example.DuplicatedDao", "dao"),
    ]
    # The fail-closed duplicate outcome is the inventory's contract only.
    inventory = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    assert not inventory.mutators
    assert any(
        diagnostic.startswith("DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS")
        for diagnostic in inventory.diagnostics
    )


def test_absolute_conventional_kotlin_root_anchors(tmp_path):
    """An absolute conventional ``src/main/kotlin`` root anchors and scans.

    Regression for the GR-03 shared-root migration: an implicit absolute
    ``src/main/kotlin`` root must anchor at the module's parent directory —
    the same single project-root-above-module convention as the java tail —
    through a like-for-like native-tail comparison.  A list-vs-tuple tail
    mismatch made ``_absolute_root_anchor`` return ``None``, so the root was
    silently dropped from ``declared_root_pairs`` and the scan failed closed
    with ``DB_DECLARATION_INVALID_SOURCE`` instead of scanning; a later
    drive-relative anchor rebuild ("C:Users\\..." on Windows) broke the same
    anchoring, so the helper-level ``os.path.isabs`` invariant is asserted
    directly."""
    # App-module conventional kotlin layout (same shape as the java sibling
    # fixture): emitted canonical paths must satisfy the documented
    # ``app/src`` diagnostic-path contract.
    relative = "app/src/main/kotlin/example/Plain.kt"
    _write(tmp_path, relative, "package example\n\nclass Plain {\n    fun hold() {}\n}\n")
    kotlin_root = tmp_path / "app" / "src" / "main" / "kotlin"
    # Helper-level anchoring invariant (platform-neutral): the anchor derived
    # for the absolute fixture root must itself be ABSOLUTE and must resolve
    # the written file below it.  A drive-relative rebuild ("C:Users\\..."
    # instead of "C:\\Users\\...") violates os.path.isabs on Windows and made
    # every downstream relative_to(anchor) fail closed.
    anchor = _absolute_root_anchor(str(kotlin_root))
    assert anchor is not None
    assert os.path.isabs(anchor)
    # No-information-loss invariant: the fixture root is the enclosing
    # project of the conventional dir passed directly, so the rebuilt
    # anchor must reproduce it exactly after normpath -- true on every
    # platform shape (POSIX "/", Windows drive, UNC).
    assert anchor == os.path.normpath(str(tmp_path))
    resolved = os.path.relpath(os.fspath(tmp_path / relative), anchor)
    assert not os.path.isabs(resolved)
    assert resolved.replace(os.sep, "/") == relative
    scan = scan_production_declarations(kotlin_root)
    # The declared root was anchored (not dropped) and the file was scanned,
    # emitted repository-relative POSIX below the module's parent directory
    # (the shared project-root-above-module convention).
    assert scan.files_scanned == (relative,)
    assert scan.diagnostics == ()
    # The plain class is discovered as a helper declaration with its owner.
    assert any(
        item.owner_fqcn == "example.Plain" and item.kind == "class"
        for item in scan.helper_ranges
    )
    assert scan.dao_declarations == ()


# ── PR-GR-03 part 2: topology-neutral diagnostic paths ───────────────────────


def _diagnostic(code, path=None):
    """Build a Diagnostic with the given code and optional path."""
    return Diagnostic(code, path=path)


class TestDiagnosticPathTopologyNeutral:
    """Diagnostic path validation accepts any repo-relative POSIX .kt path.

    PR-GR-03 part 2: the declaration_scanner's diagnostic-path contract
    no longer requires an app/src prefix. Any repo-relative POSIX .kt path
    is syntactically valid; topology membership is validated separately.
    """

    def test_accepts_app_src_main_java(self):
        d = _diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/main/java/example/File.kt")
        assert d.path == "app/src/main/java/example/File.kt"

    def test_accepts_feature_src_main_kotlin(self):
        d = _diagnostic("DB_DECLARATION_UNRESOLVED", "feature/src/main/kotlin/example/File.kt")
        assert d.path == "feature/src/main/kotlin/example/File.kt"

    def test_accepts_lib_core_src_main_java(self):
        d = _diagnostic("DB_DECLARATION_UNRESOLVED", "lib/core/src/main/java/example/File.kt")
        assert d.path == "lib/core/src/main/java/example/File.kt"

    def test_accepts_shallow_path(self):
        d = _diagnostic("DB_DECLARATION_UNRESOLVED", "src/File.kt")
        assert d.path == "src/File.kt"

    def test_rejects_non_kt_suffix(self):
        with pytest.raises(DiagnosticContextError):
            _diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/main/java/example/File.java")

    def test_rejects_no_suffix(self):
        with pytest.raises(DiagnosticContextError):
            _diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/main/java/example/File")

    def test_rejects_absolute(self):
        with pytest.raises(DiagnosticContextError):
            _diagnostic("DB_DECLARATION_UNRESOLVED", "/app/src/main/java/example/File.kt")

    def test_rejects_backslash(self):
        with pytest.raises(DiagnosticContextError):
            _diagnostic("DB_DECLARATION_UNRESOLVED", "app\\src\\main\\java\\example\\File.kt")

    def test_rejects_traversal(self):
        with pytest.raises(DiagnosticContextError):
            _diagnostic("DB_DECLARATION_UNRESOLVED", "app/src/../java/example/File.kt")

    def test_rejects_drive_prefix(self):
        with pytest.raises(DiagnosticContextError):
            _diagnostic("DB_DECLARATION_UNRESOLVED", "C:/app/src/main/java/example/File.kt")


class TestDeclarationScannerNonAppRoot:
    """Declaration scanner behavior for a declared non-app feature root.

    With ``feature/src/main/kotlin`` declared through an explicit
    ``SourceRootSet`` (the ``root_set=`` seam, no manifest), the scanner must
    discover the root's real declarations and report any diagnostic under the
    exact repository-relative POSIX path.  When the kotlin root is NOT
    declared, the complete scan fails closed with nothing scanned.
    """

    def test_diagnostic_path_for_feature_root(self, tmp_path):
        """A declared feature root yields concrete discovery plus a
        repository-relative diagnostic path."""
        root_set = SourceRootSet(roots=(
            SourceRoot(module=":feature", source_set="main",
                       path="feature/src/main/kotlin"),
        ))
        path = "feature/src/main/kotlin/feature/example/Feature.kt"
        _write(tmp_path, path,
               "package feature.example\nclass Feature {\n    fun load() {}\n}\n")
        trusted = scan_production_declarations(tmp_path, root_set=root_set)
        # Concrete discovery: the declared feature-root file is scanned and
        # its declaration inventoried under the repository-relative POSIX
        # path, with no diagnostic.
        assert trusted.files_scanned == (path,)
        assert trusted.diagnostics == ()
        owner = next(item for item in trusted.helper_ranges
                     if item.owner_fqcn == "feature.example.Feature")
        assert (owner.kind, owner.path) == ("class", path)
        # A parse failure in the SAME declared root is reported with the
        # exact repository-relative POSIX diagnostic path (never absolute,
        # never rejected for lacking an app/src prefix), and the fail-closed
        # scan clears every range.
        _write(tmp_path, path, "package feature.example\nclass Feature {\n")
        broken = scan_production_declarations(tmp_path, root_set=root_set)
        assert broken.files_scanned == (path,)
        assert broken.diagnostics == (
            _diagnostic("DB_DECLARATION_UNRESOLVED", path),
        )
        assert broken.dao_declarations == ()
        assert broken.skipped_dao_declaration_ranges == ()
        assert broken.helper_ranges == ()

    def test_undeclared_feature_root_is_rejected_without_partial_results(self, tmp_path):
        """The same feature fixture fails closed when its kotlin root is NOT
        declared: production Kotlin beside a declared java root is a
        declared-vs-observed mismatch and nothing — not even a range — is
        scanned."""
        _write(tmp_path, "feature/src/main/kotlin/feature/example/Feature.kt",
               "package feature.example\nclass Feature {\n    fun load() {}\n}\n")
        java_only = SourceRootSet(roots=(
            SourceRoot(module=":feature", source_set="main",
                       path="feature/src/main/java"),
        ))
        scan = scan_production_declarations(tmp_path, root_set=java_only)
        assert [item.code for item in scan.diagnostics] == [
            "DB_DECLARATION_INVALID_SOURCE",
            "DB_SOURCE_ROOT_UNDECLARED",
        ]
        assert scan.files_scanned == ()
        assert scan.dao_declarations == ()
        assert scan.skipped_dao_declaration_ranges == ()
        assert scan.helper_ranges == ()
