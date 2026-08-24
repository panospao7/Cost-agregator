"""Contract tests for the fail-closed Room SQL classifier."""

from __future__ import annotations

import pytest

from scripts.db_guard.sql_classifier import (
    ERROR_UNCLASSIFIABLE,
    OPERATION_UNCLASSIFIABLE,
    classify_sql,
)


@pytest.mark.parametrize("verb", "INSERT UPDATE DELETE REPLACE CREATE DROP ALTER VACUUM ATTACH DETACH".split())
def test_every_mutating_sql_verb_is_detected(verb):
    sql = {
        "INSERT": "INSERT INTO things(id) VALUES (1)",
        "UPDATE": "UPDATE things SET id = 2",
        "DELETE": "DELETE FROM things",
        "REPLACE": "REPLACE INTO things(id) VALUES (1)",
        "CREATE": "CREATE TABLE things(id INTEGER)",
        "DROP": "DROP TABLE things",
        "ALTER": "ALTER TABLE things ADD COLUMN name TEXT",
        "VACUUM": "VACUUM",
        "ATTACH": "ATTACH DATABASE 'x.db' AS other",
        "DETACH": "DETACH DATABASE other",
    }[verb]
    result = classify_sql(sql)
    assert result.operation == verb
    assert result.is_mutation and not result.is_read
    assert result.error_code is None


@pytest.mark.parametrize("sql", [
    "SELECT * FROM things",
    "WITH recent AS (SELECT * FROM things) SELECT * FROM recent",
    "WITH RECURSIVE tree AS (SELECT id FROM nodes UNION ALL SELECT id FROM nodes) SELECT * FROM tree",
])
def test_select_and_cte_reads_are_read_only(sql):
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_mutation
    assert result.error_code is None


@pytest.mark.parametrize("verb", ["INSERT", "UPDATE", "DELETE"])
def test_cte_dml_is_mutating(verb):
    sql = {
        "INSERT": "WITH incoming AS (SELECT 1) INSERT INTO things SELECT * FROM incoming",
        "UPDATE": "WITH changed AS (SELECT 1) UPDATE things SET id = 2",
        "DELETE": "WITH doomed AS (SELECT 1) DELETE FROM things WHERE id IN (SELECT * FROM doomed)",
    }[verb]
    assert classify_sql(sql).operation == verb
    assert classify_sql(sql).is_mutation


@pytest.mark.parametrize("sql", [
    "WITH incoming(id, label) AS (SELECT 1, 'x') REPLACE INTO things VALUES (1, 'x')",
    "WITH incoming(id) AS (SELECT 1) REPLACE INTO things VALUES (1)",
])
def test_cte_column_definitions_and_replace_are_valid(sql):
    result = classify_sql(sql)
    assert result.operation == "REPLACE"
    assert result.is_mutation and result.error_code is None


@pytest.mark.parametrize("sql", [
    "WITH incoming() AS (SELECT 1) SELECT * FROM incoming",
    "WITH incoming(1) AS (SELECT 1) SELECT * FROM incoming",
    "WITH incoming(id, id) AS (SELECT 1, 2) SELECT * FROM incoming",
    "WITH incoming(id,) AS (SELECT 1) SELECT * FROM incoming",
    "WITH incoming(id label) AS (SELECT 1) SELECT * FROM incoming",
])
def test_cte_column_definitions_fail_closed(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE


def test_comments_quotes_parentheses_and_semicolon_statements_are_structural():
    result = classify_sql(
        "/* INSERT */ SELECT 'UPDATE', \"DELETE\" FROM things "
        "WHERE id IN (SELECT id FROM things); -- DROP\n SELECT 1"
    )
    assert result.is_read and result.statement_count == 2
    assert classify_sql("SELECT (SELECT 'CREATE TABLE')").operation == "SELECT"


@pytest.mark.parametrize("sql", [
    "SELECT * FROM insertion_log",  # substring is not a keyword
    "SELECT * FROM update_history",
    "SELECT 'DELETE FROM things'",
    "SELECT /* REPLACE */ 1",
])
def test_substrings_and_hidden_words_do_not_create_mutations(sql):
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert not result.is_mutation


@pytest.mark.parametrize("sql", [
    "SELECT [UPDATE] FROM things",
    "SELECT [INSERT] FROM things",
    "SELECT [a]]UPDATE] FROM things",
])
def test_square_bracket_identifiers_hide_sql_keywords(sql):
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


@pytest.mark.parametrize("sql", [
    "",
    "WITH only_a_name AS",
    "SELECT (1",
    "SELECT ) (",
    "SELECT 'unterminated",
    "SELECT /* unterminated",
    "PRAGMA user_version",
])
def test_malformed_or_uncertain_sql_fails_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert result.is_unknown


def test_mixed_statements_fail_closed_when_one_statement_is_unknown():
    result = classify_sql("SELECT 1; PRAGMA user_version")
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


def test_mixed_mutation_and_unknown_statement_is_unclassifiable():
    result = classify_sql("UPDATE things SET id = 2; PRAGMA user_version")
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", ["SELECT 1", "SELECT 1.25", "SELECT -42", "SELECT 1e3"])
def test_numeric_literals_are_not_sql_keywords(sql):
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT 1..2",
    "SELECT 1.2.3",
    "SELECT 1e",
    "SELECT 1e+",
    "SELECT 1e-",
    "SELECT 1e2e3",
])
def test_malformed_numeric_literals_in_select_expressions_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation


@pytest.mark.parametrize("sql", [
    "CREATE TABLE t (amount DECIMAL(1..2))",
    "CREATE TABLE t (amount DECIMAL(1.2.3))",
    "CREATE TABLE t (amount DECIMAL(1e))",
    "CREATE TABLE t (amount DECIMAL(1e+))",
    "CREATE TABLE t (amount DECIMAL(1e-))",
])
def test_malformed_numeric_literals_in_ddl_type_arguments_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation


@pytest.mark.parametrize("sql", [
    "SELECT 1 UPDATE things SET id = 2",
    "WITH good AS (SELECT 1 UPDATE things SET id = 2) SELECT * FROM good",
    "WITH good AS (SELECT 1), bad AS (UPDATE things SET id = 2) SELECT * FROM good",
])
def test_structurally_invalid_statement_tails_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


def test_empty_top_level_separators_are_valid_statement_boundaries():
    result = classify_sql("; SELECT 1;; UPDATE things SET id = 2; ")
    assert result.operation == "UPDATE"
    assert result.statement_count == 2
    assert result.is_mutation


@pytest.mark.parametrize("sql", [
    "UPDATE",
    "UPDATE things",
    "UPDATE things SET",
    "SELECT FROM things",
    "VACUUM garbage",
    "UPDATE things SET value = 1 unknown_tail",
])
def test_incomplete_garbage_and_unknown_tails_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "UPDATE things SET value = 1",
    "DELETE FROM things WHERE id = 1",
    "SELECT 1; UPDATE things SET value = 2",
    "VACUUM",
])
def test_valid_statement_boundaries_remain_classified(sql):
    assert classify_sql(sql).error_code is None


@pytest.mark.parametrize("sql", [
    "INSERT",
    "INSERT INTO",
    "INSERT INTO things",
    "INSERT INTO things VALUES",
    "INSERT INTO things VALUES ()",
    "INSERT INTO things DEFAULT",
    "REPLACE INTO things garbage",
    "UPDATE",
    "UPDATE things SET",
    "UPDATE things SET value =",
    "DELETE",
    "DELETE things",
    "DELETE FROM",
    "CREATE",
    "CREATE TABLE",
    "CREATE TABLE things garbage",
    "DROP",
    "DROP TABLE",
    "DROP TABLE things garbage",
    "ALTER TABLE things garbage",
    "ATTACH",
    "ATTACH DATABASE",
    "ATTACH DATABASE 'x.db'",
    "ATTACH DATABASE 'x.db' AS",
    "DETACH",
    "DETACH DATABASE",
    "DETACH DATABASE other garbage",
    "VACUUM INTO",
    "VACUUM main INTO 'x.db'",
])
def test_invalid_mutating_minimum_shapes_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "SELECT 1 AS answer",
    'SELECT 1 AS "answer"',
    'SELECT "quoted column" AS "quoted alias" FROM "quoted table"',
    "SELECT 1e3 AS answer",
    "SELECT 1.2E-3 AS scientific",
])
def test_select_alias_scientific_and_quoted_identifiers_remain_reads(sql):
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


def test_select_implicit_alias_is_consumed_as_alias():
    result = classify_sql("SELECT 1 answer")
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT * FROM things WHERE id = 1",
    "SELECT * FROM things WHERE id = :id",
    "SELECT * FROM things ORDER BY id LIMIT ?1",
    "SELECT * FROM things GROUP BY kind HAVING COUNT(*) > 1",
    "SELECT * FROM things LIMIT ?1 OFFSET ?2",
    "SELECT * FROM things UNION SELECT * FROM other",
    "SELECT * FROM things INTERSECT SELECT * FROM other",
    "SELECT * FROM things EXCEPT SELECT * FROM other",
])
def test_from_clause_words_begin_their_clauses_not_aliases(sql):
    """Reserved clause words after a FROM table expression must begin their
    clauses; they must never be consumed as implicit table aliases."""
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


def test_implicit_table_alias_after_from_still_parses():
    result = classify_sql("SELECT * FROM things t WHERE t.id = :id")
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT * FROM things ORDER BY id ASC",
    "SELECT * FROM things ORDER BY id DESC LIMIT ?1",
    "SELECT * FROM things ORDER BY kind ASC, id DESC",
    "SELECT * FROM things WHERE id = :id ORDER BY id DESC",
])
def test_directed_order_by_remains_a_read(sql):
    """One trailing ASC/DESC sort-direction keyword belongs to the ORDER BY
    clause; both are reserved words, so the expression grammar must exclude
    the single trailing direction instead of failing closed on it."""
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


@pytest.mark.parametrize("sql", [
    "UPDATE things SET value = 1 WHERE id = 2 ORDER BY id DESC LIMIT 1",
    "DELETE FROM things WHERE id = 2 ORDER BY id ASC",
])
def test_directed_order_by_in_mutation_tails_remain_classified(sql):
    assert classify_sql(sql).error_code is None


@pytest.mark.parametrize("sql", [
    # A direction with no ordered expression at all stays malformed.
    "SELECT * FROM things ORDER BY ASC",
    "SELECT * FROM things ORDER BY DESC LIMIT 1",
    # Doubled directions are not a SQLite sort specification.
    "SELECT * FROM things ORDER BY id ASC DESC",
])
def test_malformed_direction_shapes_still_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    'SELECT * FROM "things" "where"',
    'SELECT * FROM things "order"',
    'SELECT * FROM things AS "group"',
    'SELECT * FROM things AS "limit"',
    'SELECT * FROM "select"',
    'SELECT * FROM "where" WHERE id = 1',
    'SELECT * FROM things "where" WHERE id = 1',
])
def test_quoted_clause_looking_table_and_alias_identifiers_remain_valid(sql):
    """Quoted identifiers that look like clause words stay valid table/alias
    names and must not be mistaken for clause boundaries."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert result.is_read and not result.is_mutation


@pytest.mark.parametrize("sql", [
    "UPDATE things SET value = 1 WHERE id = 2 ORDER BY id LIMIT 1",
    "DELETE FROM things WHERE id = 2 ORDER BY id LIMIT 1",
    "INSERT INTO things (id, value) VALUES (1, 2), (3, 4)",
    "REPLACE INTO things DEFAULT VALUES",
    "INSERT INTO things SELECT 1, 'value'",
    "ALTER TABLE things RENAME COLUMN old_name TO new_name",
    "ALTER TABLE things RENAME TO other_things",
    "CREATE INDEX idx_things ON things (value)",
    "DROP INDEX old_index",
    "ATTACH DATABASE 'x.db' AS other",
    "DETACH DATABASE other",
    "VACUUM main",
    "VACUUM INTO 'x.db'",
])
def test_complete_supported_tails_are_consumed(sql):
    assert classify_sql(sql).error_code is None


@pytest.mark.parametrize("sql", [
    "UPDATE things SET value = 1",
    'UPDATE "things" SET "value" = 1',
    "UPDATE things SET other.value = 1",
])
def test_update_name_assignments_accept_qualified_and_quoted_targets(sql):
    assert classify_sql(sql).operation == "UPDATE"


@pytest.mark.parametrize("sql", [
    "UPDATE things SET a b = 1",
    "UPDATE things SET a = 1 = 2",
    "UPDATE things SET a == 1",
    "UPDATE things SET = 1",
    "UPDATE things SET a =",
])
def test_update_malformed_assignment_targets_fail_closed(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "INSERT INTO things VALUES (1)",
    "INSERT INTO things VALUES (1, 2)",
    "INSERT INTO things VALUES (1, 2), (3, 4)",
    "INSERT INTO things VALUES (abs(1), (SELECT 2))",
])
def test_values_rows_validate_rebased_parenthesized_expressions(sql):
    assert classify_sql(sql).operation == "INSERT"
    assert classify_sql(sql).error_code is None


@pytest.mark.parametrize("sql", [
    "INSERT INTO things VALUES ()",
    "INSERT INTO things VALUES (1,)",
    "INSERT INTO things VALUES (,1)",
    "INSERT INTO things VALUES (1),",
    "INSERT INTO things VALUES (1) garbage",
])
def test_values_rows_reject_empty_or_malformed_groups(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE
    assert classify_sql(sql).error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "INSERT OR ROLLBACK INTO things VALUES (1)",
    "INSERT OR ABORT INTO things VALUES (1)",
    "INSERT OR FAIL INTO things VALUES (1)",
    "INSERT OR IGNORE INTO things VALUES (1)",
    "INSERT OR REPLACE INTO things VALUES (1)",
    "UPDATE OR ROLLBACK things SET value = 1",
    "UPDATE OR IGNORE things SET value = 1",
])
def test_sqlite_conflict_modifiers_are_allowed_only_in_legal_forms(sql):
    assert classify_sql(sql).error_code is None


@pytest.mark.parametrize("sql", [
    "INSERT OR GARBAGE INTO things VALUES (1)",
    "UPDATE OR GARBAGE things SET value = 1",
    "REPLACE OR IGNORE INTO things VALUES (1)",
    "DELETE OR IGNORE FROM things",
])
def test_unknown_or_illegal_conflict_modifiers_fail_closed(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE
    assert classify_sql(sql).error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "CREATE TABLE things (id INTEGER, label TEXT)",
    "CREATE TABLE things (id INTEGER PRIMARY KEY, label TEXT NOT NULL)",
    "CREATE TABLE things (id INTEGER) WITHOUT ROWID",
    "ALTER TABLE things ADD COLUMN label TEXT",
    "ALTER TABLE things RENAME COLUMN old_name TO new_name",
    "DROP TABLE things",
])
def test_supported_ddl_forms_remain_classified(sql):
    assert classify_sql(sql).error_code is None


def test_decimal_type_arguments_require_numeric_alternating_values_and_commas():
    assert classify_sql("CREATE TABLE t (amount DECIMAL(10,2))").operation == "CREATE"


@pytest.mark.parametrize("sql", [
    "CREATE TABLE t (amount DECIMAL(10,,2))",
    "CREATE TABLE t (amount DECIMAL(,2))",
    "CREATE TABLE t (amount DECIMAL(10,))",
    "CREATE TABLE t (amount DECIMAL(10,garbage))",
])
def test_decimal_type_arguments_reject_malformed_lists(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN UPDATE t SET value = 1; END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN UPDATE t SET value = 1; DELETE FROM t WHERE value = 2; END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN INSERT INTO log (value) VALUES (NEW.value); END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN REPLACE INTO log (value) VALUES (NEW.value); END",
])
def test_trigger_body_statements_are_classified_individually(sql):
    assert classify_sql(sql).operation == "CREATE"


@pytest.mark.parametrize("sql", [
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN PRAGMA user_version; END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN ; END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN UPDATE t SET value = ; END",
])
def test_trigger_unknown_empty_or_malformed_body_fails_closed(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN SELECT 1; END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN SELECT * FROM t; END",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN SELECT 1; UPDATE t SET value = 1; END",
])
def test_trigger_standalone_select_body_fails_closed(sql):
    """A standalone SELECT inside a trigger body is never a confident DML
    body: the CREATE TRIGGER must fail closed as unclassifiable instead of
    being trusted as a read or a mutation."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation
    assert not result.is_read


@pytest.mark.parametrize("body", [
    "UPDATE t SET value = 1; PRAGMA user_version;",
    "UPDATE t SET value = 1; UPDATE t SET value = ;",
    "UPDATE t SET value = 1; CREATE TABLE t (id INTEGER) garbage;",
])
def test_trigger_valid_mutation_followed_by_unknown_or_malformed_body_fails_closed(body):
    result = classify_sql(f"CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN {body} END")
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation
    assert not result.is_read


@pytest.mark.parametrize("sql", [
    "CREATE TABLE things ()",
    "CREATE TABLE things (id INTEGER,)",
    "CREATE TABLE things (id = 1)",
    "CREATE TABLE things (id INTEGER +)",
    "ALTER TABLE things ADD COLUMN label =",
    "ALTER TABLE things ADD COLUMN",
    "ALTER TABLE things ADD COLUMN label TEXT +",
    "ALTER TABLE things garbage",
    "CREATE TABLE things (id INTEGER) garbage",
])
def test_ddl_empty_invalid_and_trailing_syntax_fails_closed(sql):
    assert classify_sql(sql).operation == OPERATION_UNCLASSIFIABLE
    assert classify_sql(sql).error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "UPDATE things SET value = 1 WHERE",
    "UPDATE things SET value = 1 garbage",
    "DELETE FROM things WHERE",
    "DELETE FROM things LIMIT",
    "INSERT INTO things VALUES (1) garbage",
    "INSERT INTO things SELECT",
    "ALTER TABLE things ADD COLUMN",
    "ALTER TABLE things RENAME COLUMN old_name",
    "CREATE TABLE things (id INTEGER) garbage",
    "DROP TABLE things garbage",
    "ATTACH DATABASE 'x.db' AS",
    "DETACH DATABASE other garbage",
    "VACUUM main INTO 'x.db'",
    "SELECT 1 FROM",
    "SELECT 1 WHERE",
    "SELECT 1 trailing_garbage extra",
    "WITH data AS (SELECT 1) UPDATE things SET value = 1 WHERE",
])
def test_incomplete_or_unconsumed_supported_tails_fail_closed(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "CREATE TABLE t (id INTEGER ?)",
    "CREATE TABLE t (id INTEGER @)",
    "ALTER TABLE t ADD c =",
    "CREATE TABLE t (id INTEGER PRIMARY)",
    "CREATE TABLE t (id INTEGER CHECK)",
])
def test_ddl_rejects_unsupported_operators_and_malformed_constraints(sql):
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
    "CREATE UNIQUE INDEX ix_t_name ON t (name)",
    "DROP TABLE t",
    "DROP INDEX ix_t_name",
    "ALTER TABLE t ADD COLUMN amount INTEGER",
    "ALTER TABLE t RENAME COLUMN old_name TO new_name",
    "ALTER TABLE t RENAME TO t_new",
    "ALTER TABLE t DROP COLUMN obsolete",
])
def test_supported_ddl_subset_is_classified(sql):
    assert classify_sql(sql).error_code is None


@pytest.mark.parametrize("sql", [
    "CREATE TABLE things (id INTEGER, label TEXT)",
    "CREATE TABLE IF NOT EXISTS things (id INTEGER)",
    "CREATE UNIQUE INDEX ix_things ON things (value)",
    "CREATE UNIQUE INDEX IF NOT EXISTS ix_things ON things (value)",
    "CREATE INDEX IF NOT EXISTS ix_things ON things (value)",
    "CREATE INDEX ix_things ON things (a, b)",
    "CREATE VIEW v AS SELECT 1",
    "CREATE VIEW v AS SELECT id, label FROM things WHERE active = 1",
    "CREATE VIEW IF NOT EXISTS v AS SELECT * FROM things",
    "CREATE TRIGGER tr_t AFTER INSERT ON t BEGIN UPDATE t SET value = 1; END",
    "CREATE TRIGGER IF NOT EXISTS tr_t BEFORE UPDATE ON t BEGIN INSERT INTO log (v) VALUES (NEW.v); END",
    "CREATE VIRTUAL TABLE v USING fts5(content, title)",
    "CREATE VIRTUAL TABLE v USING fts5(content, tokenize = 'porter')",
    "CREATE VIRTUAL TABLE IF NOT EXISTS v USING rtree(id, minX, maxX)",
    "DROP TABLE things",
    "DROP TABLE IF EXISTS things",
    "DROP INDEX ix_things",
    "DROP INDEX IF EXISTS ix_things",
    "DROP VIEW v",
    "DROP VIEW IF EXISTS v",
    "DROP TRIGGER tr_t",
    "DROP TRIGGER IF EXISTS tr_t",
    "ALTER TABLE things ADD COLUMN label TEXT",
    "ALTER TABLE things ADD COLUMN label TEXT NOT NULL",
    "ALTER TABLE things RENAME TO other_things",
    "ALTER TABLE things RENAME COLUMN old_name TO new_name",
    "ALTER TABLE things DROP COLUMN obsolete",
])
def test_mutating_ddl_forms_are_classified(sql):
    """Every bounded CREATE/DROP/ALTER DDL form is a confident mutation."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert result.is_mutation and not result.is_read


@pytest.mark.parametrize("sql", [
    'CREATE TABLE "things" ("id" INTEGER)',
    'CREATE INDEX "ix" ON "things" ("value")',
    'CREATE VIEW "v" AS SELECT "id" FROM "things"',
    'CREATE VIRTUAL TABLE "v" USING fts5("content")',
    'DROP TABLE "things"',
    'DROP INDEX "ix"',
    'DROP VIEW "v"',
    'DROP TRIGGER "tr"',
    'ALTER TABLE "things" ADD COLUMN "label" TEXT',
    'ALTER TABLE "things" RENAME COLUMN "old" TO "new"',
])
def test_mutating_ddl_quoted_identifiers_are_classified(sql):
    """Quoted identifiers stay valid names in every bounded DDL form."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert result.is_mutation and not result.is_read


@pytest.mark.parametrize("sql", [
    "CREATE VIEW v",
    "CREATE VIEW v AS",
    "CREATE VIEW v AS SELECT",
    "CREATE VIEW v garbage",
    "CREATE VIEW v (id, label) AS SELECT 1",
    "DROP VIEW",
    "DROP VIEW v garbage",
    "DROP TRIGGER",
    "DROP TRIGGER tr garbage",
    "DROP TABLE IF NOT EXISTS things",
    "CREATE INDEX ix ON things ()",
    "CREATE INDEX ix ON things (a b)",
    "CREATE INDEX ix ON things (a) garbage",
    "CREATE INDEX ix ON things",
    "CREATE INDEX ix ON",
    "CREATE INDEX ix garbage",
    "CREATE VIRTUAL TABLE v",
    "CREATE VIRTUAL TABLE v USING",
    "CREATE VIRTUAL TABLE v USING fts5",
    "CREATE VIRTUAL TABLE v USING fts5()",
    "CREATE VIRTUAL TABLE v USING fts5(content,, title)",
    "CREATE VIRTUAL TABLE v USING fts5(content garbage)",
    "CREATE VIRTUAL TABLE v USING fts5(content) garbage",
    "CREATE VIRTUAL TABLE v USING select(content)",
    "CREATE VIRTUAL TABLE v USING fts5(content + title)",
    "ALTER TABLE things RENAME TO",
    "ALTER TABLE things RENAME COLUMN old TO",
    "ALTER TABLE things DROP COLUMN",
])
def test_malformed_or_unsupported_ddl_forms_fail_closed(sql):
    """Malformed tails, empty definitions, and unsupported shapes are never
    guessed as confident mutations."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation and not result.is_read


@pytest.mark.parametrize("sql", [
    "CREATE UNIQUE INDEX ix_things ON things (value)",
    "CREATE UNIQUE INDEX IF NOT EXISTS ix_things ON things (value)",
    "CREATE TEMP UNIQUE INDEX ix_things ON things (value)",
])
def test_create_unique_index_forms_remain_classified(sql):
    """The UNIQUE modifier stays legal for ``CREATE UNIQUE INDEX``."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert result.is_mutation and not result.is_read


@pytest.mark.parametrize("sql", [
    "CREATE UNIQUE TABLE things (id INTEGER)",
    "CREATE UNIQUE TABLE IF NOT EXISTS things (id INTEGER)",
    "CREATE UNIQUE VIEW v AS SELECT 1",
    "CREATE UNIQUE TRIGGER tr AFTER INSERT ON t BEGIN UPDATE t SET value = 1; END",
    "CREATE UNIQUE",
    "CREATE UNIQUE INDEX",
])
def test_create_unique_on_unsupported_objects_fails_closed(sql):
    """UNIQUE is legal only directly before INDEX.  ``CREATE UNIQUE
    TABLE/VIEW/TRIGGER`` and any other unsupported placement fail closed as
    ``DB_ROOM_QUERY_UNCLASSIFIABLE``."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation and not result.is_read


@pytest.mark.parametrize("sql", [
    "SELECT 1 WHERE 1 = UPDATE",
    "SELECT 1 WHERE value = SELECT",
    "SELECT 1 WHERE id = DELETE",
    "SELECT 1 WHERE name = INSERT",
    "SELECT 1 WHERE amount = CREATE",
    "SELECT 1 WHERE total = FROM",
])
def test_reserved_sql_keywords_are_not_expression_values(sql):
    """A reserved keyword must not be accepted where an expression value is
    expected (e.g. the malformed ``SELECT 1 WHERE 1 = UPDATE``)."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_read and not result.is_mutation


@pytest.mark.parametrize("sql", [
    "SELECT 1 FROM",
    "SELECT 1 FROM SELECT",
    "SELECT * FROM UPDATE",
    "SELECT * FROM INSERT",
    "UPDATE UPDATE SET value = 1",
    "DELETE FROM CREATE",
    "WITH SELECT AS (SELECT 1) SELECT * FROM things",
    "WITH UPDATE AS (SELECT 1) SELECT * FROM things",
])
def test_reserved_sql_keywords_are_not_table_identifiers(sql):
    """A reserved keyword must not be accepted as a bare identifier
    (e.g. ``SELECT 1 FROM SELECT`` or a missing ``SELECT 1 FROM``)."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "SELECT 1 AS UPDATE",
    "SELECT 1 AS SELECT",
    "SELECT 1 AS FROM",
    "SELECT 1 AS WHERE",
    "SELECT 1 UPDATE",
    "SELECT 1 SELECT",
])
def test_reserved_sql_keywords_are_not_aliases(sql):
    """A reserved keyword must not be accepted as an explicit or implicit
    alias (e.g. ``SELECT 1 AS UPDATE`` or ``SELECT 1 UPDATE``)."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "SELECT REPLACE(customSplitsJson, ' ', '') FROM things",
    "SELECT CAST(x AS INTEGER) FROM things",
    "SELECT CAST(strftime('%Y', date) AS INTEGER) FROM things",
    "SELECT REPLACE('a', 'b')",
])
def test_reserved_keyword_function_calls_remain_valid(sql):
    """Function-call shapes using keywords (REPLACE(), CAST()) are preserved."""
    result = classify_sql(sql)
    assert result.operation == "SELECT"
    assert result.is_read and not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT COUNT(*) FROM things",
    "SELECT CAST(x AS INTEGER) FROM things",
    "SELECT REPLACE(name, 'a', 'b') FROM things",
    "SELECT (a + b) FROM things",
    "SELECT (SELECT 1)",
    "SELECT * FROM things WHERE total = (SELECT SUM(amount) FROM other)",
    "INSERT INTO things VALUES ((a + b))",
])
def test_function_calls_groups_and_subqueries_remain_valid(sql):
    """A '(' stays valid when it opens a function call after a function-name
    identifier, a grouping at a value position, or a parenthesized subquery."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert not result.is_unknown


@pytest.mark.parametrize("sql", [
    "1(2)",
    "'x'(2)",
    "SELECT 1(2)",
    "UPDATE t SET x = 1(2)",
    "SELECT 'x'(2)",
    "UPDATE t SET x = 'x'(2)",
    "SELECT 1(2) AS a",
    "SELECT (1)(2)",
    "SELECT (SELECT 1)(2)",
])
def test_parenthesis_after_completed_value_is_not_a_function_call(sql):
    """A '(' immediately after a completed literal/number/identifier value is
    never a function call unless that position is a valid function-name
    position: ``1(2)``, ``'x'(2)``, ``SELECT 1(2)`` and ``UPDATE t SET x =
    1(2)`` must fail closed instead of being trusted as reads or mutations."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_read and not result.is_mutation


@pytest.mark.parametrize("sql", [
    'SELECT "select" FROM things',
    'SELECT "update" FROM things',
    'SELECT * FROM "from"',
    'SELECT "select" AS "update" FROM things',
    "SELECT [select] FROM things",
    "SELECT `update` FROM things",
    'UPDATE "things" SET "select" = 1',
    'INSERT INTO "insert" ("values") VALUES (1)',
    'SELECT "from" FROM "where"',
])
def test_quoted_sql_keywords_remain_valid_identifiers(sql):
    """Quoted identifiers that spell reserved keywords stay valid names."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT :name",
    "SELECT @name",
    "SELECT $name",
    "SELECT ?",
    "SELECT ?1",
    "SELECT ?42",
    "SELECT ?0",
    "SELECT * FROM things WHERE id = :id",
    "SELECT * FROM things WHERE id = @id",
    "SELECT * FROM things WHERE id = $id",
    "SELECT * FROM things WHERE id = ?",
    "SELECT * FROM things WHERE id = ?7",
    "SELECT * FROM things WHERE id = ?1 AND name = ?2",
    "UPDATE things SET label = :label WHERE id = :id",
    "UPDATE things SET label = @label WHERE id = @id",
    "UPDATE things SET label = $label WHERE id = $id",
    "UPDATE things SET value = ? WHERE id = ?1",
    "INSERT INTO things (label, amount) VALUES (:label, :amount)",
    "INSERT INTO things (label) VALUES (@label)",
    "INSERT INTO things (label) VALUES ($label)",
    "INSERT INTO things (label) VALUES (?)",
    "INSERT INTO things (label) VALUES (?3)",
    "DELETE FROM things WHERE id = :id",
    "DELETE FROM things WHERE id = ?",
    "WITH incoming AS (SELECT :value) SELECT * FROM incoming",
    "WITH incoming AS (SELECT ?1) UPDATE things SET value = ?2",
    "SELECT :a + :b AS total",
    "SELECT COALESCE(:a, 0)",
    "SELECT * FROM things LIMIT :limit OFFSET :offset",
])
def test_sqlite_bind_parameters_are_valid_expression_values(sql):
    """All documented SQLite bind-parameter forms are valid expression values
    in reads, mutations, CTE tails, and SELECT tails."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT :",
    "SELECT @",
    "SELECT $",
    "SELECT :1",
    "SELECT @123",
    "SELECT $9",
    "SELECT :1a",
    "SELECT :a:b",
    "SELECT * FROM things WHERE id = :",
    "SELECT * FROM things WHERE id = @123",
    "UPDATE things SET value = :",
    "INSERT INTO things (label) VALUES (:1)",
    "DELETE FROM things WHERE id = $",
    "SELECT :a + :b :c",
])
def test_malformed_bind_parameters_fail_closed(sql):
    """Empty, numeric, or invalid named parameters must fail closed."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation
    assert not result.is_read


@pytest.mark.parametrize("sql", [
    "SELECT ':name' FROM things",
    'SELECT "@name" FROM things',
    "SELECT '$name' FROM things",
    "SELECT '?1' FROM things",
    "SELECT * FROM things WHERE x = ':update'",
    "SELECT * FROM things WHERE x = :real -- :fake",
    "SELECT * FROM things WHERE x = :real /* :fake */",
    "SELECT ':delete' AS \"quoted\" FROM things WHERE id = :id",
    "SELECT ':insert' || ':drop' FROM things",
])
def test_bind_parameter_like_text_inside_quotes_and_comments_is_masked(sql):
    """Parameter-shaped text inside strings, identifiers, and comments is
    never treated as a parameter expression."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert result.is_read and not result.is_mutation


def test_bind_parameters_in_mixed_read_and_mutation_statements():
    result = classify_sql("SELECT :x AS value; UPDATE things SET y = :y WHERE id = ?1")
    assert result.operation == "UPDATE"
    assert result.is_mutation
    assert result.statement_count == 2
    assert result.error_code is None


def test_bind_parameters_do_not_hide_unknown_statements():
    result = classify_sql("SELECT :x; PRAGMA user_version")
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE


@pytest.mark.parametrize("sql", [
    "CREATE TABLE t (id INTEGER DEFAULT :x)",
    "CREATE TABLE t (id INTEGER CHECK (id > :min))",
])
def test_bind_parameters_are_rejected_in_ddl_expressions(sql):
    """Bind parameters are not legal inside DDL expressions and fail closed."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert not result.is_mutation


@pytest.mark.parametrize("sql", [
    "SELECT * FROM a JOIN b ON a.id = b.id",
    "SELECT * FROM a LEFT JOIN b ON a.id = b.id",
    "SELECT * FROM a RIGHT JOIN b ON a.id = b.id",
    "SELECT * FROM a FULL JOIN b ON a.id = b.id",
    "SELECT * FROM a INNER JOIN b ON a.id = b.id",
    "SELECT * FROM a CROSS JOIN b",
    "SELECT * FROM a LEFT OUTER JOIN b ON a.id = b.id",
    "SELECT * FROM a RIGHT OUTER JOIN b ON a.id = b.id",
    "SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id",
    "SELECT * FROM a JOIN b USING (id)",
    "SELECT * FROM a LEFT JOIN b USING (id)",
    "SELECT * FROM a JOIN b USING (id, label)",
    "SELECT * FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id LEFT JOIN d USING (x, y)",
    'SELECT * FROM "a" "x" JOIN "b" "y" ON "x"."id" = "y"."id"',
    "SELECT e.*, c.name AS categoryName FROM expenses e LEFT JOIN categories c "
    "ON e.categoryId = c.id WHERE e.merchantKey = :merchantKey "
    "GROUP BY e.date LIMIT :limit",
    "SELECT * FROM filtered f JOIN stats s ON s.merchantKey = f.merchantKey AND s.latestDate = f.date",
])
def test_valid_join_forms_are_classified(sql):
    """Every supported join operator/constraint shape is a confident read."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert result.is_read and not result.is_mutation
    assert not result.is_unknown


@pytest.mark.parametrize("sql", [
    "WITH recent AS (SELECT * FROM a JOIN b ON a.id = b.id) SELECT * FROM recent",
    "WITH recent AS (SELECT * FROM a JOIN b USING (id)) INSERT INTO t SELECT * FROM recent",
    "UPDATE t JOIN y ON t.id = y.id SET t.value = 1",
    "DELETE FROM t WHERE id IN (SELECT id FROM a JOIN b ON a.id = b.id)",
    "INSERT INTO t SELECT * FROM a JOIN b ON a.id = b.id",
])
def test_valid_joins_inside_ctes_and_dml_are_classified(sql):
    """Joins are valid inside CTE bodies, DML tails, and DML subqueries."""
    result = classify_sql(sql)
    assert result.error_code is None
    assert not result.is_unknown


@pytest.mark.parametrize("sql", [
    "SELECT * FROM a JOIN",
    "SELECT * FROM a JOIN b",
    "SELECT * FROM a LEFT JOIN b",
    "SELECT * FROM a RIGHT JOIN b",
    "SELECT * FROM a FULL JOIN b",
    "SELECT * FROM a LEFT JOIN",
    "SELECT * FROM a CROSS",
    "SELECT * FROM a INNER b ON a.id = b.id",
    "SELECT * FROM a JOIN b ON",
    "SELECT * FROM a JOIN b ON = 1",
    "SELECT * FROM a JOIN b ON a.id = b.id garbage",
    "SELECT * FROM a JOIN b USING",
    "SELECT * FROM a JOIN b USING ()",
    "SELECT * FROM a JOIN b USING (id,)",
    "SELECT * FROM a JOIN b USING (x x)",
    "SELECT * FROM a JOIN b USING (id) garbage",
    "SELECT * FROM a JOIN b ON a.id = b.id , c",
    "SELECT * FROM a OUTER JOIN b ON a.id = b.id",
    "SELECT * FROM a NATURAL JOIN b",
    "UPDATE t JOIN y SET t.value = 1",
    "UPDATE t JOIN y ON t.id = y.id SET t.value = 1 garbage",
])
def test_malformed_join_forms_fail_closed(sql):
    """Missing targets/constraints, empty/trailing USING columns, unsupported
    NATURAL/bare OUTER joins, and garbage tails are unclassifiable."""
    result = classify_sql(sql)
    assert result.operation == OPERATION_UNCLASSIFIABLE
    assert result.error_code == ERROR_UNCLASSIFIABLE
    assert result.is_unknown
    assert not result.is_read and not result.is_mutation
