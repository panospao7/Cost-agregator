"""Comprehensive pytest tests for DB guard policy v2 loader and builder.

Covers valid loading, schema validation, unknown/legacy keys, required-field
checks, type/format validation, wildcard rejection, duplicate-mutation
detection, distinct-operation/daoAccessor allowance, path syntax validation
(generic repo-Kotlin syntax only; root membership is a later, root-aware
concern), and build_policy_entry edge cases.
"""

from __future__ import annotations

import textwrap

import pytest

try:
    from scripts.db_guard.policy_errors import (
        POLICY_ERROR_INVALID_SIGNATURE,
        POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY,
        POLICY_ERROR_V2_PATH_NOT_CANONICAL,
    )
except ImportError:
    from db_guard.policy_errors import (
        POLICY_ERROR_INVALID_SIGNATURE,
        POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY,
        POLICY_ERROR_V2_PATH_NOT_CANONICAL,
    )

try:
    from scripts.db_guard.policy_v2_loader import build_policy_entry, load_policy_v2
except ImportError:
    from db_guard.policy_v2_loader import build_policy_entry, load_policy_v2


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_VALID_ENTRY_YAML = textwrap.dedent(
    """\
    schemaVersion: 2
    entries:
      - path: app/src/main/java/com/example/Repo.kt
        ownerFqcn: com.example.Repo
        kind: function
        method: insertGroup
        receiver: null
        parameterTypes:
          - com.example.Group
        daoAccessor: groupDao
        daoFqcn: com.example.data.GroupDao
        operation: insert
        barrierMode: direct
        reason: r
        owner: o
        linkedIssue: X-1
    """
)


def _write_yaml(tmp_path, text, filename="policy.yaml"):
    """Write YAML text to *tmp_path*/*filename* and return the path string."""
    p = tmp_path / filename
    p.write_text(text, encoding="utf-8")
    return str(p)


def _mutate(mutations, *, extra_yaml=""):
    """Return a YAML string derived from the valid template.

    *mutations* is a list of ``(find, replace)`` pairs applied in order.
    *extra_yaml* is appended verbatim to the end of the document (useful for
    injecting unknown top-level keys).
    """
    text = _VALID_ENTRY_YAML
    for find, replace in mutations:
        text = text.replace(find, replace, 1)
    if extra_yaml:
        text = text.rstrip("\n") + "\n" + extra_yaml
    return text


def _assert_rejected(errors):
    """Assert *errors* is non-empty and every code starts with ``POLICY_ERROR_``."""
    assert errors, "expected at least one error, got empty list"
    for err in errors:
        code = getattr(err, "code", None)
        assert code is not None, f"error has no .code attribute: {err!r}"
        assert code.startswith("POLICY_ERROR_"), (
            f"error code {code!r} does not start with POLICY_ERROR_"
        )


def _codes(errors):
    """Return the list of controlled codes carried by *errors*."""
    return [err.code for err in errors]


# ===========================================================================
# Valid document tests
# ===========================================================================


def test_minimal_valid_loads_one_entry(tmp_path):
    path = _write_yaml(tmp_path, _VALID_ENTRY_YAML)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 1
    e = entries[0]
    assert e.path == "app/src/main/java/com/example/Repo.kt"
    assert e.owner_fqcn == "com.example.Repo"
    assert e.kind.value == "function"
    assert e.method == "insertGroup"
    assert e.receiver is None
    assert e.parameter_types == ("com.example.Group",)
    assert e.dao_accessor == "groupDao"
    assert e.dao_fqcn == "com.example.data.GroupDao"
    assert e.operation == "insert"
    assert e.barrier_mode.value == "direct"
    assert e.reason == "r"
    assert e.owner == "o"
    assert e.linked_issue == "X-1"


def test_two_overloads_differing_only_in_ordered_parameter_types(tmp_path):
    # ``_VALID_ENTRY_YAML`` is textwrap.dedent-ed: entry keys sit at 4 spaces
    # and list items at 6, so this splice must use those indents -- deeper
    # indents silently no-op the replace() and leave a one-entry document.
    # The second entry must be appended AFTER the first entry's final field
    # (``linkedIssue``): splicing mid-entry would leave the first entry
    # without its trailing required fields and re-attach them to the second
    # entry as duplicate keys.
    yaml_text = _mutate(
        [
            (
                "    linkedIssue: X-1\n",
                "    linkedIssue: X-1\n"
                "  - path: app/src/main/java/com/example/Repo.kt\n"
                "    ownerFqcn: com.example.Repo\n"
                "    kind: function\n"
                "    method: insertGroup\n"
                "    receiver: null\n"
                "    parameterTypes:\n"
                "      - com.example.Options\n"
                "      - com.example.Group\n"
                "    daoAccessor: groupDao\n"
                "    daoFqcn: com.example.data.GroupDao\n"
                "    operation: insert\n"
                "    barrierMode: direct\n"
                "    reason: r\n"
                "    owner: o\n"
                "    linkedIssue: X-1\n",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert len(entries) == 2
    assert entries[0].parameter_types == ("com.example.Group",)
    assert entries[1].parameter_types == ("com.example.Options", "com.example.Group")


def test_nested_owner_fqcn_loads(tmp_path):
    yaml_text = _mutate([("ownerFqcn: com.example.Repo", "ownerFqcn: com.example.Outer.Inner")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries[0].owner_fqcn == "com.example.Outer.Inner"


def test_extension_receiver_string_loads(tmp_path):
    yaml_text = _mutate([("receiver: null", "receiver: com.example.Ext")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries[0].receiver == "com.example.Ext"


def test_receiver_padded_whitespace_is_normalized_on_load(tmp_path):
    # The double-quoted YAML scalar keeps the surrounding whitespace intact
    # through parsing; normalize_type_text strips it, so the stored receiver
    # must equal the normalized text -- proving normalization ran on load.
    yaml_text = _mutate([("receiver: null", 'receiver: " com.example.Ext "')])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries[0].receiver == "com.example.Ext"


def test_top_level_function_kind_loads(tmp_path):
    yaml_text = _mutate([("kind: function", "kind: top_level_function")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries[0].kind.value == "top_level_function"


# ===========================================================================
# Schema-version validation
# ===========================================================================


def test_schema_version_missing_rejected(tmp_path):
    yaml_text = _mutate([("schemaVersion: 2\n", "")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_schema_version_1_rejected(tmp_path):
    yaml_text = _mutate([("schemaVersion: 2", "schemaVersion: 1")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_schema_version_3_rejected(tmp_path):
    yaml_text = _mutate([("schemaVersion: 2", "schemaVersion: 3")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_schema_version_string_two_rejected(tmp_path):
    yaml_text = _mutate([("schemaVersion: 2", 'schemaVersion: "2"')])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_schema_version_null_rejected(tmp_path):
    yaml_text = _mutate([("schemaVersion: 2", "schemaVersion: null")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


# ===========================================================================
# Unknown / legacy keys
# ===========================================================================


def test_unknown_top_level_key_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML + "extraKey: value\n"
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_unknown_entry_key_rejected(tmp_path):
    yaml_text = _mutate([], extra_yaml="")
    # inject an unknown key into the entry by appending after linkedIssue
    yaml_text = yaml_text.replace(
        "linkedIssue: X-1\n",
        "linkedIssue: X-1\n        bogusField: nope\n",
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_legacy_key_class_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace(
        "linkedIssue: X-1\n",
        "linkedIssue: X-1\n        class: com.example.Repo\n",
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_legacy_key_daos_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace(
        "linkedIssue: X-1\n",
        "linkedIssue: X-1\n        daos:\n          - com.example.Dao\n",
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_legacy_key_signature_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace(
        "linkedIssue: X-1\n",
        "linkedIssue: X-1\n        signature: foo\n",
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


# ===========================================================================
# Required-field validation
# ===========================================================================


def test_missing_owner_fqcn_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace("ownerFqcn: com.example.Repo\n", "")
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_dotless_owner_fqcn_rejected(tmp_path):
    yaml_text = _mutate([("ownerFqcn: com.example.Repo", "ownerFqcn: NoDot")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_missing_kind_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace("kind: function\n", "")
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_invalid_kind_string_rejected(tmp_path):
    yaml_text = _mutate([("kind: function", "kind: not_a_real_kind")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_missing_receiver_key_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace("receiver: null\n", "")
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_malformed_receiver_text_rejected(tmp_path):
    # A receiver with unbalanced generics is malformed type text.
    yaml_text = _mutate([("receiver: null", "receiver: com.example.Unbalanced<")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_missing_parameter_types_rejected(tmp_path):
    # List items sit at 6 spaces in the dedented template; a deeper indent
    # would silently no-op the replace() and leave the field present.
    yaml_text = _VALID_ENTRY_YAML.replace(
        "parameterTypes:\n      - com.example.Group\n", ""
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_non_list_parameter_types_rejected(tmp_path):
    yaml_text = _VALID_ENTRY_YAML.replace(
        "parameterTypes:\n      - com.example.Group\n",
        "parameterTypes: com.example.Group\n",
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_noncanonical_parameter_item_loads_normalized(tmp_path):
    # Documented current behavior: the shared signature grammar skips
    # whitespace between tokens, so Kotlin's non-canonical nullable spelling
    # "T ?" (space before ?) is NOT rejected -- it is normalized on load to
    # the canonical identity "T?" (same policy as receiver padding above).
    # The stored parameter type must equal the canonical text, proving
    # canonicalization ran; identities stay exact-match and duplicate-key
    # safe because both spellings collapse to one mutation key.
    yaml_text = _mutate(
        [("      - com.example.Group", "      - com.example.Group ?")]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 1
    assert entries[0].parameter_types == ("com.example.Group?",)


# ===========================================================================
# Method / wildcard validation
# ===========================================================================


def test_wildcard_method_star_rejected(tmp_path):
    yaml_text = _mutate([("method: insertGroup", "method: '*'")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)
    assert _codes(errors) == [POLICY_ERROR_INVALID_SIGNATURE]


def test_wildcard_method_question_rejected(tmp_path):
    yaml_text = _mutate([("method: insertGroup", "method: '?'")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)
    assert _codes(errors) == [POLICY_ERROR_INVALID_SIGNATURE]


def test_wildcard_method_left_bracket_rejected(tmp_path):
    yaml_text = _mutate([("method: insertGroup", "method: '['")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)
    assert _codes(errors) == [POLICY_ERROR_INVALID_SIGNATURE]


def test_wildcard_method_right_bracket_rejected(tmp_path):
    yaml_text = _mutate([("method: insertGroup", "method: ']'")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)
    assert _codes(errors) == [POLICY_ERROR_INVALID_SIGNATURE]


def test_anchor_method_characters_load_under_exact_match(tmp_path):
    # Reviewer note (documented current behavior): the loader's wildcard
    # gate rejects only ``*``, ``?``, ``[``, and ``]``.  Regex anchor
    # characters (``^`` and ``$``) are NOT wildcard-rejected; under
    # exact-match semantics they are ordinary method-name characters, so an
    # anchored method name loads successfully instead of being rejected.
    yaml_text = _mutate([("method: insertGroup", "method: ^insertGroup$")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 1
    assert entries[0].method == "^insertGroup$"


# ===========================================================================
# Duplicate mutation key
# ===========================================================================


def test_duplicate_mutation_key_across_entries_rejected(tmp_path):
    # Two identical entries -> same mutation key -> rejected.
    entry_block = textwrap.dedent(
        """\
          - path: app/src/main/java/com/example/Repo.kt
            ownerFqcn: com.example.Repo
            kind: function
            method: insertGroup
            receiver: null
            parameterTypes:
              - com.example.Group
            daoAccessor: groupDao
            daoFqcn: com.example.data.GroupDao
            operation: insert
            barrierMode: direct
            reason: r
            owner: o
            linkedIssue: X-1
        """
    )
    yaml_text = "schemaVersion: 2\nentries:\n" + entry_block + entry_block
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)
    duplicates = [
        err
        for err in errors
        if err.code == POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY
    ]
    assert len(duplicates) == 1
    # Reported once per document as a count only: the second of the two
    # identical entries is the single extra occurrence -> count == 1.
    assert duplicates[0].context.get("count") == 1


# ===========================================================================
# Distinct operation / daoAccessor allowed
# ===========================================================================


def test_same_callable_distinct_operation_allowed(tmp_path):
    entry2 = textwrap.dedent(
        """\
          - path: app/src/main/java/com/example/Repo.kt
            ownerFqcn: com.example.Repo
            kind: function
            method: insertGroup
            receiver: null
            parameterTypes:
              - com.example.Group
            daoAccessor: groupDao
            daoFqcn: com.example.data.GroupDao
            operation: delete
            barrierMode: direct
            reason: r
            owner: o
            linkedIssue: X-1
        """
    )
    yaml_text = _VALID_ENTRY_YAML.rstrip("\n") + "\n" + entry2
    # Replace the entries header so both entries are under the same list.
    yaml_text = yaml_text.replace(
        "schemaVersion: 2\nentries:\n",
        "schemaVersion: 2\nentries:\n",
        1,
    )
    # The template already has one entry; append the second under entries.
    # We need to make sure the second entry is indented as a list item.
    # Rebuild cleanly:
    yaml_text = (
        "schemaVersion: 2\n"
        "entries:\n"
        "  - path: app/src/main/java/com/example/Repo.kt\n"
        "    ownerFqcn: com.example.Repo\n"
        "    kind: function\n"
        "    method: insertGroup\n"
        "    receiver: null\n"
        "    parameterTypes:\n"
        "      - com.example.Group\n"
        "    daoAccessor: groupDao\n"
        "    daoFqcn: com.example.data.GroupDao\n"
        "    operation: insert\n"
        "    barrierMode: direct\n"
        "    reason: r\n"
        "    owner: o\n"
        "    linkedIssue: X-1\n"
        "  - path: app/src/main/java/com/example/Repo.kt\n"
        "    ownerFqcn: com.example.Repo\n"
        "    kind: function\n"
        "    method: insertGroup\n"
        "    receiver: null\n"
        "    parameterTypes:\n"
        "      - com.example.Group\n"
        "    daoAccessor: groupDao\n"
        "    daoFqcn: com.example.data.GroupDao\n"
        "    operation: delete\n"
        "    barrierMode: direct\n"
        "    reason: r\n"
        "    owner: o\n"
        "    linkedIssue: X-1\n"
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 2


def test_same_callable_distinct_dao_accessor_allowed(tmp_path):
    yaml_text = (
        "schemaVersion: 2\n"
        "entries:\n"
        "  - path: app/src/main/java/com/example/Repo.kt\n"
        "    ownerFqcn: com.example.Repo\n"
        "    kind: function\n"
        "    method: insertGroup\n"
        "    receiver: null\n"
        "    parameterTypes:\n"
        "      - com.example.Group\n"
        "    daoAccessor: groupDao\n"
        "    daoFqcn: com.example.data.GroupDao\n"
        "    operation: insert\n"
        "    barrierMode: direct\n"
        "    reason: r\n"
        "    owner: o\n"
        "    linkedIssue: X-1\n"
        "  - path: app/src/main/java/com/example/Repo.kt\n"
        "    ownerFqcn: com.example.Repo\n"
        "    kind: function\n"
        "    method: insertGroup\n"
        "    receiver: null\n"
        "    parameterTypes:\n"
        "      - com.example.Group\n"
        "    daoAccessor: otherDao\n"
        "    daoFqcn: com.example.data.GroupDao\n"
        "    operation: insert\n"
        "    barrierMode: direct\n"
        "    reason: r\n"
        "    owner: o\n"
        "    linkedIssue: X-1\n"
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 2


# ===========================================================================
# daoFqcn validation
# ===========================================================================


def test_empty_dao_fqcn_rejected(tmp_path):
    yaml_text = _mutate([("daoFqcn: com.example.data.GroupDao", "daoFqcn: ''")])
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


# ===========================================================================
# Path canonicalization
# ===========================================================================


def test_path_traversal_rejected(tmp_path):
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: ../outside/Repo.kt",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_backslash_path_rejected(tmp_path):
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: app\\src\\main\\java\\com\\example\\Repo.kt",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_basename_only_path_passes_syntax_only_validation(tmp_path):
    # Documented current behavior (PR-GR-03R part 1): the loader validates
    # GENERIC repo-Kotlin SYNTAX only.  A bare basename is repository-
    # relative POSIX ``.kt`` syntax, so it loads; whether it points under a
    # declared production root is decided later by root-aware stages via
    # ``source_roots.is_declared_production_path``.
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: Repo.kt",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 1
    assert entries[0].path == "Repo.kt"


def test_feature_module_kotlin_path_loads(tmp_path):
    # PR-GR-03R part 1: ``path`` validation is GENERIC repo-Kotlin syntax,
    # so a feature-module tree is a syntactically valid v2 entry path.
    # Root membership is enforced later by root-aware stages via
    # ``source_roots.is_declared_production_path``.
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: feature/src/main/kotlin/com/example/Repo.kt",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 1
    assert entries[0].path == "feature/src/main/kotlin/com/example/Repo.kt"


def test_library_module_java_root_kotlin_path_loads(tmp_path):
    # Same syntax-only contract for a library module using a java root:
    # the source-root NAME carries no weight at load time.
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: lib/core/src/main/java/com/example/Repo.kt",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert errors == []
    assert entries is not None
    assert len(entries) == 1
    assert entries[0].path == "lib/core/src/main/java/com/example/Repo.kt"


@pytest.mark.parametrize(("bad_path", "parser_code"), [
    ("", "PATH_EMPTY"),
    ("/absolute/path/Repo.kt", "PATH_ABSOLUTE"),
    ("//server/share/Repo.kt", "PATH_UNC"),
    ("C:/repo/Repo.kt", "PATH_DRIVE"),
    ("app\\src\\main\\java\\com\\example\\Repo.kt", "PATH_BACKSLASH"),
    ("../outside/Repo.kt", "PATH_TRAVERSAL"),
    ("app/src/main/java/com/../example/Repo.kt", "PATH_TRAVERSAL"),
    ("./app/src/main/java/com/example/Repo.kt", "PATH_DOT_SEGMENT"),
    ("app//src/main/java/com/example/Repo.kt", "PATH_DOUBLE_SLASH"),
    ("app/src/main/java/com/example/Repo.kt/", "PATH_TRAILING_SLASH"),
    ("app/src/main/java/com/example/Repo.java", "PATH_NOT_KOTLIN"),
    ("/".join(["dir"] * 16) + "/Repo.kt", "PATH_TOO_DEEP"),
    ("d/" + "x" * 252 + ".kt", "PATH_TOO_LONG"),
])
def test_path_syntax_rejection_matrix_keeps_distinct_parser_codes(
    tmp_path, bad_path, parser_code
):
    """Loader-level mirror of the parser's syntax rejection matrix.

    Every rejection class maps to POLICY_ERROR_V2_PATH_NOT_CANONICAL with
    the parser's controlled code riding in ``context.parser_code`` (a
    bounded constant -- never the offending path text).
    """
    yaml_value = bad_path if bad_path else "''"
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: " + yaml_value,
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)
    path_errors = [
        err
        for err in errors
        if err.code == POLICY_ERROR_V2_PATH_NOT_CANONICAL
    ]
    assert len(path_errors) == 1
    assert path_errors[0].context.get("parser_code") == parser_code
    # Bounded context: the offending path text never leaks into diagnostics.
    if bad_path:
        assert bad_path not in repr(path_errors[0].context)


def test_absolute_path_rejected(tmp_path):
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: /absolute/path/Repo.kt",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


def test_java_path_rejected(tmp_path):
    yaml_text = _mutate(
        [
            (
                "path: app/src/main/java/com/example/Repo.kt",
                "path: app/src/main/java/com/example/Repo.java",
            )
        ]
    )
    path = _write_yaml(tmp_path, yaml_text)
    entries, errors = load_policy_v2(path)
    assert entries is None
    _assert_rejected(errors)


# ===========================================================================
# build_policy_entry edge cases
# ===========================================================================


def test_build_policy_entry_none_returns_errors():
    entry, errors = build_policy_entry(None, 0)
    assert entry is None
    _assert_rejected(errors)
