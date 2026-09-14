"""Focused contracts for the resolved-mutation observation seam (PR-GR-11 Slice 1).

``scripts/db_guard/mutation_observation.py`` is a constructor-only seam: the
D4 scanner (``scan_db_access``) builds one immutable
:class:`~scripts.db_guard.mutation_observation.MutationObservation` per FULLY
resolved DAO mutation from the same values it passes to ``match_mutation``,
and hands it to the optional ``mutation_observation_sink``.  The tests below
pin the Slice-1 contracts:

- fail-closed construction: the frozen record rejects mutation, and any
  missing/empty identity, non-int/bool/negative offset, non-monotonic span,
  or non-positive line/column raises ``TypeError``/``ValueError``;
- the exact v2 ``CallableKey.canonical_key()`` spelling of ``callable_key``
  (six pipe segments, ``null`` receiver, verbatim comma-joined parameter
  types including commas inside generics);
- 1-based line/column derived from ``call_start`` exactly like the scanner's
  ``_line`` helper, including a multi-line source; and
- bounded report rendering: ``bounded_fields()`` is exactly
  ``{"path", "line", "column"}`` -- never source offsets or identity strings.

The scanner integration section proves the sink observes WITHOUT changing
the report: with and without a sink the protocol payload is identical (same
findings, diagnostics, trusted state), the sink collects exactly the
resolved mutations, and every observation field equals the corresponding
finding payload/coordinate value.  Nothing here scans source on its own.
"""

from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

import scripts.db_guard.scanner as db_guard_scanner_module
from scripts.db_guard.mutation_observation import (
    NULL_RECEIVER,
    MutationObservation,
    build_mutation_observation,
    canonical_callable_key,
)
from scripts.db_guard.policy_model import (
    BarrierMode,
    CallableKey,
    CallableKind,
    PolicyEntry,
)
from scripts.db_guard.scanner import scan_db_access


# ---------------------------------------------------------------------------
# Unit: fail-closed construction of the immutable record
# ---------------------------------------------------------------------------

_IDENTITY_FIELDS = (
    "path",
    "callable_key",
    "dao_accessor",
    "dao_fqcn",
    "operation",
    "mutation_kind",
    "source_identity",
)

_OFFSET_FIELDS = ("source_start", "source_end", "line", "column")


def _observation_kwargs() -> dict:
    """One fully valid field set; every identity is a bounded structural
    coordinate and every offset is a non-negative 1-based/0-based int."""
    return dict(
        path="app/src/main/java/example/Repo.kt",
        callable_key=(
            "app/src/main/java/example/Repo.kt|example.Repo|function|save"
            "|null|Int"
        ),
        source_start=61,
        source_end=79,
        line=3,
        column=9,
        dao_accessor="expenseDao",
        dao_fqcn="example.ExpenseDao",
        operation="insert",
        mutation_kind="ROOM_INSERT",
        source_identity=(
            "app/src/main/java/example/Repo.kt"
            "::example.ExpenseDao#insert(Int)"
        ),
    )


def _observation(**overrides) -> MutationObservation:
    values = _observation_kwargs()
    values.update(overrides)
    return MutationObservation(**values)


def test_observation_is_frozen():
    """The dataclass is frozen: no field can be reassigned after build."""
    observation = _observation()
    with pytest.raises(FrozenInstanceError):
        observation.path = "app/src/main/java/example/Other.kt"
    with pytest.raises(FrozenInstanceError):
        observation.source_start = 0


@pytest.mark.parametrize("field", _IDENTITY_FIELDS)
@pytest.mark.parametrize("bad_value", ["", None])
def test_empty_or_non_string_identity_fails_closed(field, bad_value):
    """Every identity field must be a non-empty string; anything else is a
    TypeError instead of a half-resolved observation."""
    with pytest.raises(TypeError):
        _observation(**{field: bad_value})


def test_missing_identity_field_fails_closed():
    kwargs = _observation_kwargs()
    del kwargs["dao_fqcn"]
    with pytest.raises(TypeError):
        MutationObservation(**kwargs)


@pytest.mark.parametrize("field", _OFFSET_FIELDS)
@pytest.mark.parametrize("bad_value", ["9", 9.0, True, -1])
def test_non_int_bool_or_negative_offset_fails_closed(field, bad_value):
    """Offsets are non-negative ints; strings, floats, bools, and negatives
    are all TypeErrors (``bool`` is an ``int`` subclass but is rejected)."""
    with pytest.raises(TypeError):
        _observation(**{field: bad_value})


def test_source_end_before_source_start_raises_value_error():
    with pytest.raises(ValueError):
        _observation(source_start=20, source_end=19)


def test_zero_length_span_is_valid():
    observation = _observation(source_start=20, source_end=20)
    assert (observation.source_start, observation.source_end) == (20, 20)


@pytest.mark.parametrize("field", ["line", "column"])
def test_zero_line_or_column_raises_value_error(field):
    """Line/column are 1-based; 0 is the only value below 1 that survives the
    non-negative-int gate, and it raises ValueError (negatives are TypeErrors
    via the offset matrix above)."""
    with pytest.raises(ValueError):
        _observation(**{field: 0})


# ---------------------------------------------------------------------------
# Unit: canonical_callable_key -- the exact v2 callable-key spelling
# ---------------------------------------------------------------------------


def test_canonical_callable_key_exact_six_segment_pipe_spelling():
    key = canonical_callable_key(
        "app/src/main/java/example/Repo.kt",
        "example.Repo",
        CallableKind.FUNCTION,
        "save",
        None,
        ("Int", "String"),
    )
    assert key == (
        "app/src/main/java/example/Repo.kt|example.Repo|function|save"
        "|null|Int,String"
    )
    # Six segments: a comma-joined parameter list must never add segments.
    assert len(key.split("|")) == 6


def test_kind_enum_member_and_plain_string_spell_identically():
    enum_spelled = canonical_callable_key(
        "p/Repo.kt", "example.Repo", CallableKind.PROPERTY_SETTER,
        "value", None, ("String",),
    )
    plain_spelled = canonical_callable_key(
        "p/Repo.kt", "example.Repo", "property_setter",
        "value", None, ("String",),
    )
    assert enum_spelled == plain_spelled
    assert enum_spelled == "p/Repo.kt|example.Repo|property_setter|value|null|String"


def test_absent_receiver_renders_literal_null_and_present_receiver_verbatim():
    assert NULL_RECEIVER == "null"
    absent = canonical_callable_key(
        "p/Repo.kt", "example.Repo", CallableKind.FUNCTION,
        "save", None, ("Int",),
    )
    assert absent == "p/Repo.kt|example.Repo|function|save|null|Int"
    present = canonical_callable_key(
        "p/Repo.kt", "example.Repo", CallableKind.FUNCTION,
        "save", "db", ("Int",),
    )
    assert present == "p/Repo.kt|example.Repo|function|save|db|Int"


def test_parameter_types_join_with_comma_including_commas_inside_generics():
    """The policy model joins parameter types with ``,`` VERBATIM, including
    commas inside generic spellings; the seam must reproduce that exactly."""
    key = canonical_callable_key(
        "p/Repo.kt", "example.Repo", CallableKind.FUNCTION,
        "save", None, ("List<Pair<Int, String>>",),
    )
    assert key == "p/Repo.kt|example.Repo|function|save|null|List<Pair<Int, String>>"


@pytest.mark.parametrize("receiver", [None, "db"])
@pytest.mark.parametrize(
    "parameter_types", [("Int",), ("List<Pair<Int, String>>", "Long?")],
)
def test_callable_key_matches_policy_model_canonical_key_exactly(
    receiver, parameter_types,
):
    """Byte-identical to ``CallableKey.canonical_key()`` for every shape the
    policy model accepts."""
    policy_key = CallableKey(
        path="p/Repo.kt",
        owner_fqcn="example.Repo",
        kind=CallableKind.FUNCTION,
        method="save",
        receiver=receiver,
        parameter_types=parameter_types,
    ).canonical_key()
    assert canonical_callable_key(
        "p/Repo.kt", "example.Repo", CallableKind.FUNCTION,
        "save", receiver, parameter_types,
    ) == policy_key


# ---------------------------------------------------------------------------
# Unit: build_mutation_observation -- scanner._line-derived coordinates
# ---------------------------------------------------------------------------


def test_line_and_column_derive_from_call_start_like_scanner_line():
    source = "fun save(value: Int) {\n    db.insert(value)\n}\n"
    # The scanner's _METHOD_CALL match starts at the DOT, so call_start is the
    # dot offset (``db|.insert(value)``), not the receiver's first character.
    call_start = source.index(".insert")
    call_end = source.index(")", call_start) + 1
    observation = build_mutation_observation(
        path="app/src/main/java/example/Repo.kt",
        owner_fqcn="example.Repo",
        kind=CallableKind.FUNCTION,
        method="save",
        receiver=None,
        parameter_types=("Int",),
        source=source,
        call_start=call_start,
        call_end=call_end,
        dao_accessor="db",
        dao_fqcn="example.RepoDao",
        operation="insert",
        mutation_kind="ROOM_INSERT",
        source_identity=(
            "app/src/main/java/example/Repo.kt::example.RepoDao#insert(Int)"
        ),
    )
    # Fixture line literal: the call sits on the second source line; its dot
    # is the seventh column (4 spaces + ``db``).
    assert observation.line == 2
    assert observation.column == 7
    # Formula parity with the scanner's own line helper.
    assert observation.line == db_guard_scanner_module._line(source, call_start)
    # The half-open span passes through verbatim.
    assert (observation.source_start, observation.source_end) == (
        call_start, call_end,
    )
    # Identity passthrough: the observation carries exactly the values the
    # scanner resolved (and passes to match_mutation).
    assert observation.path == "app/src/main/java/example/Repo.kt"
    assert observation.dao_accessor == "db"
    assert observation.dao_fqcn == "example.RepoDao"
    assert observation.operation == "insert"
    assert observation.mutation_kind == "ROOM_INSERT"
    assert observation.source_identity == (
        "app/src/main/java/example/Repo.kt::example.RepoDao#insert(Int)"
    )
    assert observation.callable_key == canonical_callable_key(
        "app/src/main/java/example/Repo.kt", "example.Repo",
        CallableKind.FUNCTION, "save", None, ("Int",),
    )


def test_line_and_column_derive_across_multi_line_sources():
    source = (
        "package example\n"
        "\n"
        "class Repo {\n"
        "    fun store(value: Int) {\n"
        "        db.insert(value)\n"
        "    }\n"
        "}\n"
    )
    call_start = source.index(".insert")
    call_end = source.index(")", call_start) + 1
    observation = build_mutation_observation(
        path="app/src/main/java/example/Repo.kt",
        owner_fqcn="example.Repo",
        kind=CallableKind.FUNCTION,
        method="store",
        receiver=None,
        parameter_types=("Int",),
        source=source,
        call_start=call_start,
        call_end=call_end,
        dao_accessor="db",
        dao_fqcn="example.RepoDao",
        operation="insert",
        mutation_kind="ROOM_INSERT",
        source_identity=(
            "app/src/main/java/example/Repo.kt::example.RepoDao#insert(Int)"
        ),
    )
    # Fixture line literal: the call sits on the fifth source line and its
    # dot is the eleventh column (8 spaces + ``db``).  The count starts at
    # the source head, so earlier lines must not leak into the coordinate.
    assert observation.line == 5
    assert observation.column == 11
    assert observation.line == db_guard_scanner_module._line(source, call_start)


# ---------------------------------------------------------------------------
# Unit: bounded_fields -- the only report rendering the seam exposes
# ---------------------------------------------------------------------------


def test_bounded_fields_carry_exactly_path_line_column():
    observation = _observation()
    bounded = observation.bounded_fields()
    assert bounded == {
        "path": "app/src/main/java/example/Repo.kt",
        "line": 3,
        "column": 9,
    }
    # Explicit negatives for the contract's other half: internal source
    # offsets and identity strings never reach a report built from this dict.
    assert "source_start" not in bounded
    assert "source_end" not in bounded
    assert "callable_key" not in bounded
    assert "source_identity" not in bounded
    assert "dao_fqcn" not in bounded
    assert "dao_accessor" not in bounded


# ---------------------------------------------------------------------------
# Scanner integration: the sink observes WITHOUT changing the report
# ---------------------------------------------------------------------------

# Fixture style mirrors scripts/test_db_guard_scanner_d4.py's typed matrix
# (_TYPED_SOURCE/_typed_entry/_typed_root): one file carrying the Item type,
# a minimal valid @Dao (the inventory leg fails closed without one), and a
# repository whose ``save`` performs exactly one resolvable direct DAO
# mutation.
_OBSERVATION_SOURCE = """package example

data class Item(val id: Int)

@androidx.room.Dao
interface ExpenseDao {
    @androidx.room.Insert
    fun insert(item: Item)
}

class Repository(private val expenseDao: ExpenseDao) {
    fun save(item: Item) {
        expenseDao.insert(item)
    }
}
"""

_OBSERVATION_PATH = "app/src/main/java/example/ObservationRepository.kt"

_EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}


def _observation_root(tmp_path: Path) -> Path:
    root = tmp_path / "app" / "src" / "main" / "java"
    package = root / "example"
    package.mkdir(parents=True)
    # The fixture file must live at EXACTLY _OBSERVATION_PATH relative to the
    # repository root: v2 authorization matches on full path equality.
    (package / "ObservationRepository.kt").write_text(
        _OBSERVATION_SOURCE, encoding="utf-8",
    )
    return root


def _policy_entry(**overrides) -> PolicyEntry:
    """The EXACT typed entry for Repository.save -> expenseDao.insert."""
    values = dict(
        path=_OBSERVATION_PATH,
        owner_fqcn="example.Repository",
        kind=CallableKind.FUNCTION,
        method="save",
        receiver=None,
        parameter_types=("example.Item",),
        dao_accessor="expenseDao",
        dao_fqcn="example.ExpenseDao",
        operation="insert",
        barrier_mode=BarrierMode.HELPER,
        reason="observation-seam",
        owner="@d4",
        linked_issue="PR-GR-11",
    )
    values.update(overrides)
    return PolicyEntry(**values)


def test_sink_observes_without_changing_the_unauthorized_report(tmp_path):
    """A sink-attached scan returns the byte-identical report of the sink-less
    production default, and the one collected observation carries exactly the
    finding's payload/coordinate values."""
    root = _observation_root(tmp_path)
    # A minimal policy entry that deliberately does NOT authorize the
    # mutation, so the scan emits the unauthorized-mutation finding whose
    # payload/coordinates the observation must match.
    entry = _policy_entry(operation="remove")

    baseline = scan_db_access(
        root, [entry], raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    observations: list = []
    observed = scan_db_access(
        root, [entry], raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
        mutation_observation_sink=observations,
    )

    # The sink never alters the report: identical findings, diagnostics, and
    # trusted state (full protocol-payload equality, not field sampling).
    assert observed.to_dict() == baseline.to_dict()
    # ...and that identical payload is the KNOWN-GOOD sink-less shape.
    assert observed.to_dict() == {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access",
        "findings": [{
            "rule": "DB_UNAUTHORIZED_MUTATION", "severity": "error",
            "path": _OBSERVATION_PATH,
            # Fixture line literal: ``expenseDao.insert(item)`` is the
            # thirteenth source line.
            "location": {"line": 13, "end_line": 13},
            "symbol": {"owner": "example.Repository", "name": "save",
                       "receiver": None, "parameters": ["example.Item"],
                       "kind": "function"},
            "identity": {"dao": "example.ExpenseDao",
                         "accessor": "expenseDao", "operation": "insert",
                         "mutation_kind": "ROOM_INSERT",
                         "call_form": "receiver"},
            "message": "Database mutation is not owned by an exact policy entry",
        }],
        "diagnostics": [],
        "statistics": {"files_scanned": 1, "declarations_scanned": 3,
                       "inventory_daos": 1, "inventory_mutators": 1,
                       "trusted": True, "advisoryDiagnosticCount": 0},
    }

    # Exactly one fully resolved mutation was observed.
    assert len(observations) == 1
    observation = observations[0]
    finding = observed.findings[0]
    # Payload/coordinate parity: every observation field equals the value the
    # finding carries for the SAME mutation.
    assert observation.path == finding.path
    assert observation.dao_fqcn == finding.identity["dao"]
    assert observation.dao_accessor == finding.identity["accessor"]
    assert observation.operation == finding.identity["operation"]
    assert observation.mutation_kind == finding.identity["mutation_kind"]
    assert observation.line == finding.location.line
    # The observation's callable key is spelled from the finding's own symbol
    # identity (kind renders as the finding's plain "function" spelling).
    assert observation.callable_key == canonical_callable_key(
        finding.path, finding.symbol.owner, finding.symbol.kind,
        finding.symbol.name, finding.symbol.receiver,
        tuple(finding.symbol.parameters),
    )
    # Exact coordinates and key for this fixture: line 13, the dot of
    # ``expenseDao.insert`` is the 19th column (8 spaces + ``expenseDao``),
    # and the span is the half-open _METHOD_CALL match from that dot.
    assert (observation.path, observation.line, observation.column) == (
        _OBSERVATION_PATH, 13, 19,
    )
    dot = _OBSERVATION_SOURCE.index("expenseDao.insert") + len("expenseDao")
    assert observation.source_start == dot
    assert observation.source_end == dot + len(".insert(")
    assert observation.callable_key == (
        "app/src/main/java/example/ObservationRepository.kt"
        "|example.Repository|function|save|null|example.Item"
    )


def test_sink_observes_authorized_mutation_with_unchanged_clean_report(tmp_path):
    """Observations are authorization-independent (they carry resolved
    identity only): the exact policy entry authorizes the mutation cleanly,
    the report stays finding-free and trusted -- and the sink still collected
    the one resolved observation with the entry's exact identity."""
    root = _observation_root(tmp_path)
    entry = _policy_entry()

    baseline = scan_db_access(
        root, [entry], raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    observations: list = []
    observed = scan_db_access(
        root, [entry], raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
        mutation_observation_sink=observations,
    )

    assert observed.to_dict() == baseline.to_dict()
    assert observed.to_dict() == {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access", "findings": [], "diagnostics": [],
        "statistics": {"files_scanned": 1, "declarations_scanned": 3,
                       "inventory_daos": 1, "inventory_mutators": 1,
                       "trusted": True, "advisoryDiagnosticCount": 0},
    }

    assert len(observations) == 1
    observation = observations[0]
    # The observation's identity is exactly the authorized policy identity.
    assert observation.callable_key == entry.callable_key().canonical_key()
    assert (
        observation.dao_accessor,
        observation.dao_fqcn,
        observation.operation,
    ) == (entry.dao_accessor, entry.dao_fqcn, entry.operation)
    assert (observation.path, observation.line, observation.column) == (
        _OBSERVATION_PATH, 13, 19,
    )
