#!/usr/bin/env python3
"""
SQL_CLASSIFIER -- Fail-closed SQL statement classifier for Room @Query findings.

Classifies a SQL string as a read (SELECT) or as one of the mutating
operations (INSERT/UPDATE/DELETE/REPLACE/CREATE/DROP/ALTER/VACUUM/ATTACH/
DETACH) using only the Python standard library.  It is intended for the
db-access guard to decide whether a Room ``@Query`` may mutate the database.

Design
------
* Character-level tokenizer that tracks parenthesis depth and handles
  single-quoted strings, double-quoted identifiers/strings, backtick
  identifiers, doubled-quote escapes (``''`` / ``""`` / ``` ``` ``),
  ``--`` line comments, ``/* ... */`` block comments, and SQLite square-
  bracket quoted identifiers (including doubled ``]]`` escapes).
* SQLite bind parameters (``:name``, ``@name``, ``$name``, ``?`` and
  ``?NNN``) are tokenized as single parameter-expression tokens, never as
  operators followed by identifiers or as reserved identifiers.  Malformed
  parameter forms (a bare ``:``/``@``/``$``, a numeric named parameter
  such as ``:1``/``@2``/``$3``, or a name that does not begin with a
  letter or underscore) fail closed with ``REASON_INVALID_PARAMETER``
  instead of being silently re-read as operators or identifiers.
  Parameter-looking text inside quotes or comments is never inspected.
* Keywords are recognized only as whole top-level tokens.  Nothing is ever
  matched by substring, and text inside quotes, quoted identifiers, or
  comments is never inspected for keywords.
* Reserved SQL keywords are never accepted as bare expression values,
  identifiers, or aliases (``SELECT 1 WHERE 1 = UPDATE`` and
  ``SELECT 1 FROM SELECT`` fail closed); quoted identifiers such as
  ``"select"`` and function-call shapes such as ``REPLACE(...)`` remain
  valid.  After a ``FROM`` table expression an implicit alias is consumed
  only when the next token is a non-reserved identifier or a quoted
  identifier; reserved clause words (``WHERE``, ``GROUP``, ``HAVING``,
  ``ORDER``, ``LIMIT``, ``OFFSET``, ``UNION``, ``INTERSECT``, ``EXCEPT``,
  ``JOIN``, ``ON``, ...) always begin their clauses instead of being read
  as aliases.
* Expression validation rejects a ``(`` that immediately follows a
  completed value unless that ``(`` opens a function call, which requires
  a function-name identifier directly before it.  ``1(2)``, ``'x'(2)``,
  ``SELECT 1(2)``, and ``UPDATE t SET x = 1(2)`` fail closed, while
  ``COUNT(*)``, ``CAST(...)``, ``REPLACE(...)``, grouped ``(a + b)``,
  and parenthesized subqueries keep their existing valid shape.
* Bounded JOIN grammar: ``JOIN``, ``INNER JOIN``, ``CROSS JOIN``, and
  ``LEFT/RIGHT/FULL [OUTER] JOIN`` are validated with ``ON`` expressions
  or ``USING (column, ...)`` constraints, table and alias names, quoted
  identifiers, and chained joins.  Joins are supported inside SELECT
  tails, CTE bodies, ``INSERT ... SELECT`` tails, and SQLite
  ``UPDATE ... join-clause SET`` forms.  Malformed joins (missing target,
  missing/empty/invalid ON or USING, unknown tails, or unsupported forms
  such as NATURAL JOIN and a bare OUTER JOIN) fail closed with
  ``DB_ROOM_QUERY_UNCLASSIFIABLE``.
* Multi-statement SQL is split on top-level semicolons; each statement is
  classified independently and the results are combined fail-closed: any
  unclassifiable statement makes the whole input unclassifiable, otherwise
  mutation wins and an all-read input is a read.
* CTE handling: a statement beginning with ``WITH [RECURSIVE]`` is
  classified by the first top-level DML keyword after the CTE definitions
  (a depth-0 keyword token that is not a CTE name followed by ``AS``).
* Anything unknown, ambiguous, or syntactically uncertain returns
  ``operation=OPERATION_UNCLASSIFIABLE`` with
  ``error_code=ERROR_UNCLASSIFIABLE`` and a controlled ``reason``; the
  classifier never guesses and never raises.

Public API
----------
* ``classify_sql(sql: str) -> SqlClassification`` -- classify a SQL string.
* ``SqlClassification`` -- immutable result model.
* Constants: ``ERROR_UNCLASSIFIABLE``, ``OPERATION_UNCLASSIFIABLE``,
  ``MUTATING_KEYWORDS``, ``READ_KEYWORDS``, and the ``REASON_*`` codes.

Consumers can import this module from the repository root as a package,
e.g.::

    from scripts.db_guard.sql_classifier import classify_sql, SqlClassification

Known unsupported SQL / limitations
-----------------------------------
* Verbs outside the keyword sets (``PRAGMA``, ``EXPLAIN``, ``ANALYZE``,
  ``REINDEX``, ``BEGIN``/``COMMIT``/``ROLLBACK``/``SAVEPOINT``, plain
  ``VALUES``, ...) are reported unclassifiable (fail closed) rather than
  guessed.
* ``CREATE TRIGGER`` bodies are limited to mutating DML (INSERT/UPDATE/
  DELETE/REPLACE); a standalone SELECT or any other read/unknown statement
  inside a trigger body is rejected fail-closed instead of being trusted
  as a read or a mutation.
* The supported DDL subset is bounded and token-aware: ``CREATE TABLE``
  ``[IF NOT EXISTS]`` with a non-empty column-definition group,
  ``CREATE [UNIQUE] INDEX [IF NOT EXISTS] ... ON ... (columns)``,
  ``CREATE VIEW [IF NOT EXISTS] ... AS SELECT ...``,
  ``CREATE TRIGGER [IF NOT EXISTS] ... BEGIN <mutating DML>; END``,
  ``CREATE VIRTUAL TABLE ... USING module(...)``, ``DROP
  TABLE/INDEX/VIEW/TRIGGER [IF EXISTS]``, and ``ALTER TABLE`` ``ADD
  COLUMN``/``RENAME TO``/``RENAME COLUMN``/``DROP COLUMN``.  The
  ``UNIQUE`` modifier is legal only directly before ``INDEX``
  (``CREATE UNIQUE INDEX``); ``CREATE UNIQUE TABLE/VIEW/TRIGGER`` and any
  other unsupported placement fail closed.  Anything outside those forms (a
  view column-name list, a virtual table without a ``USING module(...)``
  group, an ``IF NOT EXISTS`` on ``DROP``, or an unknown tail) fails closed
  as unclassifiable.
* Compound ``UNION``/``INTERSECT``/``EXCEPT`` selects are read-classified
  by their leading depth-0 ``SELECT``; a statement that begins with a
  parenthesized subquery has no depth-0 keyword and is unclassifiable.
* Nested block comments and MySQL-style executable comments are not
  supported; an unclosed ``/*`` is unclassifiable.
* ``#`` line comments (MySQL) are not recognized and their text is
  tokenized normally; this can only cause false-positive mutations, never
  a missed one.
* Backslash-quote sequences inside quoted text are dialect-ambiguous
  (SQLite does not treat ``\\'`` as an escape; MySQL does).  Any odd-length
  run of backslashes directly before a quote char is therefore reported
  unclassifiable instead of risking a mis-tokenized string boundary.
* Only the documented bind-parameter forms are supported.  A ``?`` that is
  immediately followed by a non-digit (``WHERE x = ?a``) is tokenized as an
  anonymous parameter plus a separate token, which fails closed inside
  expressions; named parameters never accept empty or numeric names.
* SQL syntax itself is not validated; malformed SQL is classified by
  best-effort tokenization and otherwise fails closed.
"""

from dataclasses import dataclass
from typing import List, Optional, Tuple

# ------------------------------------------------------------------
# Controlled constants
# ------------------------------------------------------------------

# Result/error code returned for any unknown, ambiguous, or uncertain input.
ERROR_UNCLASSIFIABLE = "DB_ROOM_QUERY_UNCLASSIFIABLE"

# operation value for unclassifiable results.
OPERATION_UNCLASSIFIABLE = "UNCLASSIFIABLE"

# Mutating top-level statement keywords.  ``REPLACE`` covers the
# ``REPLACE INTO ...`` form (an alias for ``INSERT OR REPLACE``).
MUTATING_KEYWORDS = frozenset(
    {
        "INSERT",
        "UPDATE",
        "DELETE",
        "REPLACE",
        "CREATE",
        "DROP",
        "ALTER",
        "VACUUM",
        "ATTACH",
        "DETACH",
    }
)

# Read-only top-level statement keywords.
READ_KEYWORDS = frozenset({"SELECT"})

# Controlled reason codes for unclassifiable results (never raw SQL text).
REASON_NO_STATEMENT = "NO_STATEMENT"
REASON_INVALID_INPUT = "INVALID_INPUT"
REASON_UNBALANCED_PARENS = "UNBALANCED_PARENS"
REASON_UNTERMINATED_STRING = "UNTERMINATED_STRING"
REASON_UNTERMINATED_COMMENT = "UNTERMINATED_COMMENT"
REASON_UNCERTAIN_STRING_ESCAPE = "UNCERTAIN_STRING_ESCAPE"
REASON_UNKNOWN_KEYWORD = "UNKNOWN_KEYWORD"
REASON_MIXED_UNCLASSIFIABLE = "MIXED_UNCLASSIFIABLE"
REASON_INVALID_STATEMENT_TAIL = "INVALID_STATEMENT_TAIL"
REASON_INVALID_STATEMENT_SHAPE = "INVALID_STATEMENT_SHAPE"
REASON_INVALID_NUMERIC_LITERAL = "INVALID_NUMERIC_LITERAL"
REASON_INVALID_PARAMETER = "INVALID_PARAMETER"

__all__ = [
    "classify_sql",
    "SqlClassification",
    "ERROR_UNCLASSIFIABLE",
    "OPERATION_UNCLASSIFIABLE",
    "MUTATING_KEYWORDS",
    "READ_KEYWORDS",
    "REASON_NO_STATEMENT",
    "REASON_INVALID_INPUT",
    "REASON_UNBALANCED_PARENS",
    "REASON_UNTERMINATED_STRING",
    "REASON_UNTERMINATED_COMMENT",
    "REASON_UNCERTAIN_STRING_ESCAPE",
    "REASON_UNKNOWN_KEYWORD",
    "REASON_MIXED_UNCLASSIFIABLE",
    "REASON_INVALID_STATEMENT_TAIL",
    "REASON_INVALID_STATEMENT_SHAPE",
    "REASON_INVALID_NUMERIC_LITERAL",
    "REASON_INVALID_PARAMETER",
]

# ------------------------------------------------------------------
# Result model
# ------------------------------------------------------------------


@dataclass(frozen=True)
class SqlClassification:
    """Immutable result of classifying a SQL string.

    Attributes:
        operation: Canonical operation name -- one of ``SELECT``, a
            ``MUTATING_KEYWORDS`` member, or ``OPERATION_UNCLASSIFIABLE``.
        is_mutation: True when any top-level statement is mutating.
        is_read: True when every classified top-level statement is a read.
        keyword: Canonical classification keyword (uppercase), or None when
            unclassifiable.
        statement_count: Number of non-empty top-level statements found.
        recursive: True when a ``WITH RECURSIVE`` introduced the statement.
        cte_names: Names of CTEs declared at statement level.
        error_code: ``ERROR_UNCLASSIFIABLE`` when the SQL could not be
            classified, else None.  A non-None value means fail-closed.
        reason: Controlled reason code when unclassifiable, else None.
    """

    operation: str
    is_mutation: bool
    is_read: bool
    keyword: Optional[str] = None
    statement_count: int = 0
    recursive: bool = False
    cte_names: Tuple[str, ...] = ()
    error_code: Optional[str] = None
    reason: Optional[str] = None

    @property
    def is_unknown(self) -> bool:
        """True when the classifier could not confirm the SQL's behavior."""
        return self.error_code is not None

    @property
    def is_classified(self) -> bool:
        """True when the SQL was confidently classified as read or mutation."""
        return self.error_code is None


# ------------------------------------------------------------------
# Internal token model
# ------------------------------------------------------------------

# Token kinds: WORD, QUOTED_IDENT, STRING, NUMBER, PARAM, LPAREN, RPAREN,
# SEMI, OP, COMMENT_LINE, COMMENT_BLOCK.


@dataclass(frozen=True)
class _Token:
    kind: str
    text: str
    depth: int


def _is_word_char(ch: str) -> bool:
    """True for characters that can appear in a bare SQL identifier/keyword."""
    return ch.isalnum() or ch == "_" or ch == "$"


def _tokenize(sql: str) -> Tuple[List[_Token], Optional[str]]:
    """Tokenize ``sql`` tracking parenthesis depth and quote/comment state.

    Returns ``(tokens, error_reason)`` where ``error_reason`` is None on
    success and a controlled ``REASON_*`` code on any structural problem
    (unterminated string/comment, unbalanced parens, dialect-ambiguous
    backslash-quote escape).  A token's ``depth`` is the parenthesis depth
    immediately before the token, so top-level tokens have depth 0.
    """
    tokens: List[_Token] = []
    depth = 0
    i = 0
    n = len(sql)
    uncertain_escape = False

    while i < n:
        c = sql[i]

        if c.isspace():
            i += 1
            continue

        # -- line comment (SQLite/SQL standard)
        if c == "-" and i + 1 < n and sql[i + 1] == "-":
            start = i
            while i < n and sql[i] != "\n":
                i += 1
            tokens.append(_Token("COMMENT_LINE", sql[start:i], depth))
            continue

        # /* ... */ block comment (non-nested)
        if c == "/" and i + 1 < n and sql[i + 1] == "*":
            start = i
            i += 2
            closed = False
            while i < n:
                if sql[i] == "*" and i + 1 < n and sql[i + 1] == "/":
                    i += 2
                    closed = True
                    break
                i += 1
            if not closed:
                return tokens, REASON_UNTERMINATED_COMMENT
            tokens.append(_Token("COMMENT_BLOCK", sql[start:i], depth))
            continue

        # SQLite square-bracket quoted identifiers.  A doubled closing
        # bracket is an escaped bracket and must not terminate the identifier.
        if c == "[":
            start = i
            i += 1
            terminated = False
            while i < n:
                if sql[i] == "]":
                    if i + 1 < n and sql[i + 1] == "]":
                        i += 2
                        continue
                    i += 1
                    terminated = True
                    break
                i += 1
            if not terminated:
                return tokens, REASON_UNTERMINATED_STRING
            tokens.append(_Token("QUOTED_IDENT", sql[start:i], depth))
            continue

        # Quoted text: '...', "...", `...`.  Doubled quotes are escapes.
        # A backslash run ending directly before a quote char is ambiguous
        # between SQLite (no backslash escapes) and MySQL-style dialects, so
        # an odd-length run before a quote fails closed.
        if c in ("'", '"', "`"):
            quote = c
            start = i
            i += 1
            terminated = False
            while i < n:
                ch = sql[i]
                if ch == "\\":
                    j = i
                    while j < n and sql[j] == "\\":
                        j += 1
                    run_len = j - i
                    if j < n and sql[j] in ("'", '"', "`"):
                        if run_len % 2 == 1:
                            uncertain_escape = True
                        i = j
                        continue
                    i = j
                    continue
                if ch == quote:
                    if i + 1 < n and sql[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    terminated = True
                    break
                i += 1
            if not terminated:
                return tokens, REASON_UNTERMINATED_STRING
            kind = "STRING" if quote == "'" else "QUOTED_IDENT"
            tokens.append(_Token(kind, sql[start:i], depth))
            continue

        if c == "(":
            tokens.append(_Token("LPAREN", c, depth))
            depth += 1
            i += 1
            continue

        if c == ")":
            if depth == 0:
                return tokens, REASON_UNBALANCED_PARENS
            tokens.append(_Token("RPAREN", c, depth))
            depth -= 1
            i += 1
            continue

        if c == ";":
            tokens.append(_Token("SEMI", c, depth))
            i += 1
            continue

        # Numeric literals must be recognized before generic word tokens;
        # ``str.isalnum`` also accepts digits and would otherwise make the
        # tokenization order accidental for numeric-only SQL.  Consume the
        # complete numeric-shaped lexeme before validating it so malformed
        # forms (1..2, 1.2.3, 1e+, ...) cannot be split into apparently valid
        # operands and operators.
        if c.isdigit() or (c == "." and i + 1 < n and sql[i + 1].isdigit()):
            start = i
            while i < n and sql[i].isdigit():
                i += 1
            if i < n and sql[i] == ".":
                i += 1
                while i < n and sql[i].isdigit():
                    i += 1
            elif start == i and sql[start] == ".":
                while i < n and sql[i].isdigit():
                    i += 1
            if i < n and sql[i] in "eE":
                i += 1
                if i < n and sql[i] in "+-":
                    i += 1
                exponent_digits = i
                while i < n and sql[i].isdigit():
                    i += 1
                if i == exponent_digits:
                    return tokens, REASON_INVALID_NUMERIC_LITERAL
            # A second dot/exponent, or a sign directly attached to a
            # numeric lexeme, is not another SQL token boundary.  Mark a
            # second dot/exponent and fail closed rather than guessing.  A
            # plus/minus without an exponent is still a valid expression
            # operator (for example, ``1+2``).
            malformed_tail = i
            while malformed_tail < n and sql[malformed_tail] in ".eE":
                malformed_tail += 1
                while malformed_tail < n and sql[malformed_tail].isdigit():
                    malformed_tail += 1
            if malformed_tail != i:
                i = malformed_tail
                return tokens, REASON_INVALID_NUMERIC_LITERAL
            if i == start:
                tokens.append(_Token("OP", c, depth))
                i += 1
                continue
            tokens.append(_Token("NUMBER", sql[start:i], depth))
            continue

        # SQLite bind parameters: ?, ?NNN, :name, @name, $name.  A parameter
        # is a single expression token, never an operator followed by an
        # identifier or a bare identifier.  A malformed parameter (a bare
        # ``:``/``@``/``$``, a numeric named parameter such as ``:1``, or a
        # name not starting with a letter or underscore) fails closed with
        # REASON_INVALID_PARAMETER so it cannot be silently re-read as an
        # operator or identifier.  ``$`` is also an identifier character, so
        # this branch must precede the word branch; mid-word ``$`` (``a$b``)
        # is still consumed as an ordinary identifier.
        if c in ("?", ":", "@", "$"):
            if c == "?":
                j = i + 1
                while j < n and sql[j].isdigit():
                    j += 1
                tokens.append(_Token("PARAM", sql[i:j], depth))
                i = j
                continue
            j = i + 1
            if j < n and (sql[j].isalpha() or sql[j] == "_"):
                j += 1
                while j < n and _is_word_char(sql[j]):
                    j += 1
                tokens.append(_Token("PARAM", sql[i:j], depth))
                i = j
                continue
            return tokens, REASON_INVALID_PARAMETER

        if _is_word_char(c):
            start = i
            while i < n and _is_word_char(sql[i]):
                i += 1
            tokens.append(_Token("WORD", sql[start:i], depth))
            continue

        # Multi-character operators (``||``, ``==``, ``!=``, ``<>``,
        # ``<=``, ``>=``, ``>>``, ``<<``).  Emitting them as a single
        # token lets the expression validator match them against
        # ``_BINARY_OPS`` instead of seeing two adjacent single-char
        # operators that break the value/operator alternation.
        if i + 1 < n:
            two = c + sql[i + 1]
            if two in ("||", "==", "!=", "<>", "<=", ">=", ">>", "<<"):
                tokens.append(_Token("OP", two, depth))
                i += 2
                continue

        tokens.append(_Token("OP", c, depth))
        i += 1

    if uncertain_escape:
        return tokens, REASON_UNCERTAIN_STRING_ESCAPE
    if depth != 0:
        return tokens, REASON_UNBALANCED_PARENS
    return tokens, None


def _split_statements(tokens: List[_Token]) -> List[List[_Token]]:
    """Split a token stream into statements at top-level semicolons."""
    statements: List[List[_Token]] = []
    current: List[_Token] = []
    trigger_body = False
    for tok in tokens:
        if tok.kind == "SEMI" and tok.depth == 0:
            # SQLite trigger bodies contain their own top-level semicolons;
            # the terminating semicolon follows END.  Keep those inner
            # separators out of the bounded trigger grammar.
            if trigger_body and not any(
                    t.kind == "WORD" and t.depth == 0 and t.text.upper() == "END"
                    for t in current):
                current.append(tok)
                continue
            statements.append(current)
            current = []
            trigger_body = False
        else:
            current.append(tok)
            if (not trigger_body and tok.kind == "WORD" and tok.depth == 0 and
                    tok.text.upper() == "TRIGGER" and any(
                        t.kind == "WORD" and t.depth == 0 and t.text.upper() == "CREATE"
                        for t in current)):
                trigger_body = True
    if current:
        statements.append(current)
    return statements


def _is_significant(tokens: List[_Token]) -> bool:
    """True when a statement contains tokens other than whitespace/comments."""
    return any(tok.kind not in ("COMMENT_LINE", "COMMENT_BLOCK") for tok in tokens)


def _rebase_tokens(tokens: List[_Token], amount: int) -> List[_Token]:
    """Make a parenthesized token slice look like an independent statement."""
    return [_Token(token.kind, token.text, max(0, token.depth - amount)) for token in tokens]


@dataclass(frozen=True)
class _StatementResult:
    operation: str
    keyword: Optional[str]
    is_mutation: bool
    is_read: bool
    recursive: bool
    cte_names: Tuple[str, ...]


_CLAUSE_WORDS = frozenset({
    "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
    "UNION", "INTERSECT", "EXCEPT", "RETURNING", "VALUES", "SET",
})
_BINARY_OPS = frozenset({"=", "==", "!=", "<>", "<", "<=", ">", ">=", "+", "-", "*", "/", "%", "||", "&", "|", "<<", ">>"})
_CONFLICT_MODIFIERS = frozenset({"ROLLBACK", "ABORT", "FAIL", "IGNORE", "REPLACE"})

# Trigger-body DML verbs.  SQLite trigger bodies may only contain mutating
# DML (INSERT/UPDATE/DELETE, with REPLACE as ``INSERT OR REPLACE``); a
# standalone SELECT or any other statement inside a trigger body is rejected
# so ``CREATE TRIGGER`` can never be claimed as a confident mutation from a
# read-only or unknown body.
_TRIGGER_BODY_DML = frozenset({"INSERT", "UPDATE", "DELETE", "REPLACE"})

# SQLite reserved keywords (canonical keyword list plus RETURNING).  A bare
# reserved keyword is never a valid expression value, identifier, or alias;
# malformed statements such as ``SELECT 1 WHERE 1 = UPDATE`` or
# ``SELECT 1 FROM SELECT`` must fail closed instead of being mistaken for a
# confidently readable query.  Quoted identifiers (``"select"``, ``[update]``,
# ```from``) and function-call shapes (``REPLACE(...)``, ``CAST(...)``) stay
# valid because their keyword is not used as a bare identifier/value.
_RESERVED_WORDS = frozenset({
    "ABORT", "ACTION", "ADD", "AFTER", "ALL", "ALTER", "ANALYZE", "AND",
    "AS", "ASC", "ATTACH", "AUTOINCREMENT", "BEFORE", "BEGIN", "BETWEEN",
    "BY", "CASCADE", "CASE", "CAST", "CHECK", "COLLATE", "COLUMN", "COMMIT",
    "CONFLICT", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_DATE",
    "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATABASE", "DEFAULT", "DEFERRABLE",
    "DEFERRED", "DELETE", "DESC", "DETACH", "DISTINCT", "DROP", "EACH",
    "ELSE", "END", "ESCAPE", "EXCEPT", "EXCLUSIVE", "EXISTS", "EXPLAIN",
    "FAIL", "FOR", "FOREIGN", "FROM", "FULL", "GLOB", "GROUP", "HAVING",
    "IF", "IGNORE", "IMMEDIATE", "IN", "INDEX", "INDEXED", "INITIALLY",
    "INNER", "INSERT", "INSTEAD", "INTERSECT", "INTO", "IS", "ISNULL",
    "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "MATCH", "NATURAL", "NO", "NOT",
    "NOTNULL", "NULL", "OF", "OFFSET", "ON", "OR", "ORDER", "OUTER", "PLAN",
    "PRAGMA", "PRIMARY", "QUERY", "RAISE", "RECURSIVE", "REFERENCES",
    "REGEXP", "REINDEX", "RELEASE", "RENAME", "REPLACE", "RESTRICT", "RIGHT",
    "RETURNING", "ROLLBACK", "ROW", "SAVEPOINT", "SELECT", "SET", "TABLE",
    "TEMP", "TEMPORARY", "THEN", "TO", "TRANSACTION", "TRIGGER", "UNION",
    "UNIQUE", "UPDATE", "USING", "VACUUM", "VALUES", "VIEW", "VIRTUAL",
    "WHEN", "WHERE", "WITH", "WITHOUT",
})


def _name_at(tokens: List[_Token], index: int) -> int:
    """Consume a bare/quoted SQLite name, optionally qualified by dots.

    A bare reserved keyword is never a valid name; quoted identifiers always
    are (``"select"``, ``[update]``, ```from```).
    """
    def name(t: _Token) -> bool:
        if t.kind == "QUOTED_IDENT":
            return True
        return t.kind == "WORD" and t.text.upper() not in _RESERVED_WORDS
    if index >= len(tokens) or not name(tokens[index]):
        return -1
    index += 1
    while index + 1 < len(tokens) and tokens[index].kind == "OP" and tokens[index].text == ".":
        if not name(tokens[index + 1]):
            return -1
        index += 2
    return index


def _word(tokens: List[_Token], index: int, value: str) -> bool:
    return (index < len(tokens) and tokens[index].kind == "WORD" and
            tokens[index].text.upper() == value and tokens[index].depth == 0)


def _matching_group(tokens: List[_Token], index: int) -> int:
    if index >= len(tokens) or tokens[index].kind != "LPAREN":
        return -1
    wanted = tokens[index].depth + 1
    for i in range(index + 1, len(tokens)):
        if tokens[i].kind == "RPAREN" and tokens[i].depth == wanted:
            return i + 1
    return -1


def _valid_expression(tokens: List[_Token]) -> bool:
    """Validate token shape without interpreting names or schema contents."""
    if not tokens:
        return False
    # Treat a parenthesized SELECT as one expression operand after validating
    # the nested SELECT with the same complete-tail grammar.
    normalized: List[_Token] = []
    index = 0
    while index < len(tokens):
        if (tokens[index].kind == "LPAREN" and index + 1 < len(tokens) and
                tokens[index + 1].kind == "WORD" and tokens[index + 1].text.upper() == "SELECT"):
            end = _matching_group(tokens, index)
            if end < 0 or not _valid_select_tail(_rebase_tokens(tokens[index + 1:end - 1], 1)):
                return False
            normalized.append(_Token("WORD", "__subquery", tokens[index].depth))
            index = end
        else:
            normalized.append(tokens[index])
            index += 1
    tokens = normalized
    expect_value = True
    parens = 0
    index = 0
    previous: Optional[_Token] = None
    while index < len(tokens):
        token = tokens[index]
        if token.kind in ("COMMENT_LINE", "COMMENT_BLOCK"):
            index += 1
            continue
        if token.kind in ("NUMBER", "STRING", "QUOTED_IDENT", "PARAM"):
            if not expect_value:
                return False
            expect_value = False
        elif token.kind == "WORD":
            upper = token.text.upper()
            if upper in {"AND", "OR"}:
                if expect_value:
                    return False
                expect_value = True
            elif upper in {"IS", "IN", "LIKE", "GLOB", "MATCH", "REGEXP", "BETWEEN"}:
                if expect_value:
                    return False
                expect_value = True
            elif upper in {"NOT", "NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP"}:
                if upper == "NOT":
                    if not expect_value:
                        return False
                elif not expect_value:
                    # A function name followed by '(' is handled below; a
                    # second value is never silently accepted.
                    return False
                expect_value = False
            elif upper in {"AS", "ESCAPE", "COLLATE"}:
                if expect_value:
                    return False
                expect_value = True
            elif upper in {"CASE", "WHEN", "THEN", "ELSE", "END"}:
                # CASE syntax is deliberately only accepted when token-shaped;
                # its operands still have to be present.
                expect_value = upper in {"CASE", "WHEN", "THEN", "ELSE"}
            else:
                # Identifier or function name.  A bare reserved keyword is
                # never a valid identifier or value here (``1 = UPDATE`` is
                # malformed), but the function-call shape ``word(`` keeps
                # SQLite functions such as REPLACE() and CAST() valid.
                if upper in _RESERVED_WORDS and not (
                        index + 1 < len(tokens) and tokens[index + 1].kind == "LPAREN"):
                    return False
                if not expect_value:
                    return False
                expect_value = False
        elif token.kind == "LPAREN":
            if not expect_value:
                # A '(' directly after a completed value is legal only as a
                # function call, and a function call requires a function-name
                # identifier immediately before the '(': ``1(2)``, ``'x'(2)``,
                # ``:p(2)``, a completed group ``(a + b)(2)``, and a completed
                # subquery ``(SELECT ...)(2)`` are never function calls and
                # fail closed.  ``COUNT(*)``, ``CAST(...)``, ``REPLACE(...)``,
                # grouped ``(a + b)``, and parenthesized subqueries keep their
                # existing valid shape.
                if (previous is None or previous.kind not in ("WORD", "QUOTED_IDENT") or
                        (previous.kind == "WORD" and previous.text == "__subquery")):
                    return False
            parens += 1
            expect_value = True
        elif token.kind == "RPAREN":
            if expect_value or parens == 0:
                return False
            parens -= 1
            expect_value = False
        elif token.kind == "OP":
            op = token.text
            if op == "?":
                if not expect_value:
                    return False
                expect_value = False
            elif op == "*" and expect_value:
                # Projection wildcard (including table.*).
                expect_value = False
            elif op == "." and not expect_value:
                # Qualified expression/name; the following component must be
                # another identifier or wildcard.
                expect_value = True
            elif op in _BINARY_OPS:
                if op in {"+", "-"} and expect_value:
                    previous = token
                    index += 1
                    continue
                if expect_value:
                    return False
                expect_value = True
            elif op == ",":
                if expect_value or parens == 0:
                    return False
                expect_value = True
            else:
                return False
        else:
            return False
        previous = token
        index += 1
    return parens == 0 and not expect_value


def _split_top(tokens: List[_Token], separator: str = ",") -> List[List[_Token]]:
    result, start = [], 0
    for i, token in enumerate(tokens):
        if token.kind == "OP" and token.text == separator and token.depth == 0:
            result.append(tokens[start:i])
            start = i + 1
    result.append(tokens[start:])
    return result


def _valid_select_tail(tokens: List[_Token]) -> bool:
    if not tokens or not _word(tokens, 0, "SELECT"):
        return False
    i = 1
    if _word(tokens, i, "DISTINCT") or _word(tokens, i, "ALL"):
        i += 1
    start = i
    while i < len(tokens) and not (tokens[i].kind == "WORD" and tokens[i].depth == 0 and tokens[i].text.upper() in _CLAUSE_WORDS):
        i += 1
    projection = tokens[start:i]
    if not projection:
        return False
    for item in _split_top(projection):
        if not item:
            return False
        # Explicit AS aliases and the SQLite shorthand ``expr alias``.
        as_at = next((n for n, t in enumerate(item) if _word(item, n, "AS")), None)
        if as_at is not None:
            if as_at == 0 or _name_at(item, as_at + 1) != len(item):
                return False
            item = item[:as_at]
        elif len(item) >= 2 and item[-1].kind in ("WORD", "QUOTED_IDENT"):
            # Only a single trailing identifier may be an implicit alias.  A
            # reserved keyword is never an alias: ``SELECT 1 UPDATE ...``
            # must not be consumed as ``SELECT 1 AS UPDATE``.
            before = item[:-1]
            if (before and _valid_expression(before) and
                    (item[-1].kind != "WORD" or item[-1].text.upper() not in _RESERVED_WORDS)):
                item = before
        if not _valid_expression(item):
            return False
    while i < len(tokens):
        if _word(tokens, i, "FROM"):
            i += 1
            i = _name_at(tokens, i) if i < len(tokens) else -1
            if i < 0:
                return False
            if i < len(tokens) and _word(tokens, i, "AS"):
                # Explicit alias: the name after AS must be a valid
                # identifier; a missing or reserved name fails closed.
                i = _name_at(tokens, i + 1)
            elif i < len(tokens):
                # An implicit table alias is optional.  Consume it only when
                # the next token is a non-reserved identifier or a quoted
                # identifier.  Reserved clause words (WHERE, GROUP, HAVING,
                # ORDER, LIMIT, OFFSET, UNION, INTERSECT, EXCEPT, JOIN, ON,
                # ...) must never be swallowed as aliases: they stay at ``i``
                # so the clause dispatch below begins the clause.
                alias_end = _name_at(tokens, i)
                if alias_end > i:
                    i = alias_end
            if i < 0:
                return False
            # Chained JOIN clauses: ``... FROM t JOIN u ON ... LEFT JOIN v
            # USING (...)``.  A malformed or unsupported join fails closed.
            while i < len(tokens):
                operator_end = _join_operator_end(tokens, i)
                if operator_end < 0:
                    return False
                if operator_end == 0:
                    break
                i = _consume_join_clause(tokens, i)
                if i < 0:
                    return False
        elif _word(tokens, i, "WHERE"):
            i += 1; end = _next_clause(tokens, i)
            if not _valid_expression(tokens[i:end]): return False
            i = end
        elif _word(tokens, i, "ORDER"):
            if not _word(tokens, i + 1, "BY"): return False
            i += 2; end = _next_clause(tokens, i)
            keys = _order_expression(tokens, i, end)
            if not keys or not all(_valid_expression(key) for key in keys): return False
            i = end
        elif _word(tokens, i, "LIMIT"):
            i += 1; end = _next_clause(tokens, i)
            if not _valid_expression(tokens[i:end]): return False
            i = end
        elif _word(tokens, i, "OFFSET"):
            i += 1; end = _next_clause(tokens, i)
            if not _valid_expression(tokens[i:end]): return False
            i = end
        elif _word(tokens, i, "GROUP"):
            if not _word(tokens, i + 1, "BY"): return False
            i += 2; end = _next_clause(tokens, i)
            if not _valid_expression(tokens[i:end]): return False
            i = end
        elif _word(tokens, i, "HAVING"):
            i += 1; end = _next_clause(tokens, i)
            if not _valid_expression(tokens[i:end]): return False
            i = end
        elif _word(tokens, i, "RETURNING"):
            i += 1; end = _next_clause(tokens, i)
            if not _valid_expression(tokens[i:end]): return False
            i = end
        elif _word(tokens, i, "UNION") or _word(tokens, i, "INTERSECT") or _word(tokens, i, "EXCEPT"):
            i += 1
            if _word(tokens, i, "ALL"): i += 1
            if not _valid_select_tail(tokens[i:]): return False
            return True
        else:
            return False
    return True


def _next_clause(tokens: List[_Token], start: int) -> int:
    for i in range(start, len(tokens)):
        if tokens[i].kind == "WORD" and tokens[i].depth == 0 and tokens[i].text.upper() in _CLAUSE_WORDS:
            return i
    return len(tokens)


def _order_expression(tokens: List[_Token], start: int, end: int) -> List[List[_Token]]:
    """ORDER BY keys from ``tokens[start:end]`` as one token slice per key,
    with each key's single optional trailing sort direction removed.

    SQLite's ORDER BY grammar is ``expr [ASC|DESC] [, expr [ASC|DESC]]*``.
    Both direction keywords are reserved words, so leaving a direction
    inside an expression slice makes every directed ORDER BY fail closed as
    an illegal bare reserved word.  The key list is split on top-level
    commas only (parenthesis-depth aware; commas inside function-call
    parentheses never split, and bracket-quoted identifiers are single
    tokens), and the caller validates every key independently, so each key
    may carry its own optional direction.  Each key drops exactly ONE
    trailing direction; any malformed key -- a lone direction, doubled
    directions, or an empty key from a leading/trailing/doubled comma --
    makes this return no keys at all so the clause still fails closed
    through the caller's non-empty check and the ordinary expression
    grammar.
    """
    groups: List[List[_Token]] = []
    group_start = start
    for index in range(start, end):
        if (tokens[index].kind == "OP" and tokens[index].text == ","
                and tokens[index].depth == 0):
            groups.append(tokens[group_start:index])
            group_start = index + 1
    groups.append(tokens[group_start:end])
    keys: List[List[_Token]] = []
    for group in groups:
        if (len(group) >= 2 and group[-1].kind == "WORD"
                and group[-1].text.upper() in ("ASC", "DESC")):
            group = group[:-1]
        if not group:
            return []
        keys.append(group)
    return keys


def _join_operator_end(tokens: List[_Token], index: int) -> int:
    """Return the index after a supported join operator at ``index``.

    Supported operators: ``JOIN``, ``INNER JOIN``, ``CROSS JOIN``, and
    ``LEFT/RIGHT/FULL [OUTER] JOIN``.  Returns ``0`` when the token at
    ``index`` does not start a join operator, and ``-1`` for a
    malformed/unsupported join-shaped form (for example ``INNER`` without
    ``JOIN``, or ``LEFT`` without ``JOIN``/``OUTER JOIN``).
    """
    if index >= len(tokens) or tokens[index].kind != "WORD" or tokens[index].depth != 0:
        return 0

    def joins(after: int) -> bool:
        return (after < len(tokens) and tokens[after].kind == "WORD" and
                tokens[after].depth == 0 and tokens[after].text.upper() == "JOIN")

    word = tokens[index].text.upper()
    if word == "JOIN":
        return index + 1
    if word in ("INNER", "CROSS"):
        return index + 2 if joins(index + 1) else -1
    if word in ("LEFT", "RIGHT", "FULL"):
        if joins(index + 1):
            return index + 2
        if (index + 1 < len(tokens) and tokens[index + 1].kind == "WORD" and
                tokens[index + 1].depth == 0 and tokens[index + 1].text.upper() == "OUTER"):
            return index + 3 if joins(index + 2) else -1
        return -1
    return 0


def _next_clause_or_join(tokens: List[_Token], start: int) -> int:
    """Find the end of an ON expression: the next depth-0 clause word or the
    next supported join operator, whichever comes first."""
    for i in range(start, len(tokens)):
        token = tokens[i]
        if token.kind == "WORD" and token.depth == 0:
            if token.text.upper() in _CLAUSE_WORDS or _join_operator_end(tokens, i) > 0:
                return i
    return len(tokens)


def _consume_join_clause(tokens: List[_Token], index: int) -> int:
    """Consume ``<join-operator> <target> [AS alias] [ON expr | USING (cols)]``.

    Returns the index after the complete join clause, or ``-1`` when the
    clause is malformed: a missing target, a missing/empty/invalid ON or
    USING constraint (CROSS JOIN takes none), an invalid USING column
    list, or an unknown tail.
    """
    operator_end = _join_operator_end(tokens, index)
    if operator_end <= 0:
        return -1
    is_cross = tokens[index].text.upper() == "CROSS"
    i = _name_at(tokens, operator_end)
    if i < 0:
        return -1
    if i < len(tokens) and _word(tokens, i, "AS"):
        i = _name_at(tokens, i + 1)
    elif i < len(tokens):
        alias_end = _name_at(tokens, i)
        if alias_end > i:
            i = alias_end
    if i < 0:
        return -1
    if _word(tokens, i, "ON"):
        end = _next_clause_or_join(tokens, i + 1)
        if not _valid_expression(tokens[i + 1:end]):
            return -1
        return end
    if _word(tokens, i, "USING"):
        if i + 1 >= len(tokens) or tokens[i + 1].kind != "LPAREN":
            return -1
        end = _matching_group(tokens, i + 1)
        if end < 0:
            return -1
        columns = _split_top(_rebase_tokens(tokens[i + 2:end - 1], 1))
        if not columns or any(_name_at(column, 0) != len(column) for column in columns):
            return -1
        return end
    if is_cross:
        return i
    return -1


def _valid_statement_grammar(keyword: str, tail: List[_Token]) -> bool:
    def name(i: int) -> int: return _name_at(tail, i)
    def conflict_modifier(i: int, verb: str) -> int:
        if not _word(tail, i, "OR"):
            return i
        if verb not in {"INSERT", "UPDATE"} or i + 1 >= len(tail):
            return -1
        modifier = tail[i + 1]
        if modifier.kind != "WORD" or modifier.text.upper() not in _CONFLICT_MODIFIERS:
            return -1
        return i + 2
    if keyword in ("INSERT", "REPLACE"):
        i = 0
        i = conflict_modifier(i, keyword)
        if i < 0:
            return False
        if not _word(tail, i, "INTO"): return False
        i = name(i + 1)
        if i < 0: return False
        if i < len(tail) and tail[i].kind == "LPAREN":
            opening = i
            i = _matching_group(tail, i)
            if i < 0 or not _valid_column_list(tail[opening:i]): return False
        if i < 0: return False
        if _word(tail, i, "DEFAULT"):
            return _word(tail, i + 1, "VALUES") and i + 2 == len(tail)
        if _word(tail, i, "VALUES"):
            groups = _split_top(tail[i + 1:])
            def valid_values_group(group: List[_Token]) -> bool:
                if not group or group[0].kind != "LPAREN" or _matching_group(group, 0) != len(group):
                    return False
                # The outer VALUES parentheses delimit the row. Rebase the
                # body before splitting columns, then validate each expression
                # independently so (1), (1, 2), and nested expressions work.
                body = _rebase_tokens(group[1:-1], 1)
                expressions = _split_top(body)
                return bool(expressions) and all(_valid_expression(expr) for expr in expressions)
            return bool(groups) and all(valid_values_group(group) for group in groups)
        return _valid_select_tail(tail[i:])
    if keyword == "UPDATE":
        i = 0
        i = conflict_modifier(i, keyword)
        if i < 0:
            return False
        i = name(i)
        if i < 0: return False
        if _word(tail, i, "AS"):
            i = name(i + 1)
        elif i < len(tail) and _join_operator_end(tail, i) == 0 and tail[i].kind in ("WORD", "QUOTED_IDENT") and not _word(tail, i, "SET"):
            i = name(i)
        if i < 0: return False
        # SQLite 3.33+ allows ``UPDATE <target> [AS alias] <join-clause> SET``.
        while i < len(tail):
            operator_end = _join_operator_end(tail, i)
            if operator_end < 0:
                return False
            if operator_end == 0:
                break
            i = _consume_join_clause(tail, i)
            if i < 0:
                return False
        if not _word(tail, i, "SET"): return False
        end = _next_clause(tail, i + 1)
        assignments = _split_top(tail[i + 1:end])
        def valid_assignment(assignment: List[_Token]) -> bool:
            equals = [n for n, token in enumerate(assignment)
                      if token.kind == "OP" and token.text == "="]
            if len(equals) != 1:
                return False
            eq = equals[0]
            # SQLite assignment targets are names, not arbitrary expressions.
            return eq > 0 and _name_at(assignment, 0) == eq and _valid_expression(assignment[eq + 1:])
        if not assignments or any(not valid_assignment(a) for a in assignments): return False
        i = end
    elif keyword == "DELETE":
        if not _word(tail, 0, "FROM"): return False
        i = name(1)
        if i < 0: return False
    elif keyword in ("ATTACH", "DETACH"):
        if not _word(tail, 0, "DATABASE"): return False
        if keyword == "DETACH": return name(1) == len(tail)
        as_at = next((n for n, t in enumerate(tail) if _word(tail, n, "AS")), -1)
        return as_at > 1 and _valid_expression(tail[1:as_at]) and name(as_at + 1) == len(tail)
    elif keyword == "VACUUM":
        if not tail:
            return True
        # Only the built-in SQLite schema names are accepted; any other
        # identifier (e.g. ``VACUUM garbage``) fails closed.
        if len(tail) == 1 and tail[0].kind == "WORD" and tail[0].text.upper() in ("MAIN", "TEMP"):
            return True
        return _word(tail, 0, "INTO") and len(tail) == 2 and tail[1].kind == "STRING"
    elif keyword == "SELECT":
        return _valid_select_tail([_Token("WORD", "SELECT", 0)] + tail)
    else:
        return _valid_ddl(keyword, tail)
    while i < len(tail):
        if _word(tail, i, "WHERE") or _word(tail, i, "HAVING"):
            i += 1; end = _next_clause(tail, i)
            if not _valid_expression(tail[i:end]): return False
            i = end
        elif _word(tail, i, "ORDER"):
            if not _word(tail, i + 1, "BY"): return False
            i += 2; end = _next_clause(tail, i)
            keys = _order_expression(tail, i, end)
            if not keys or not all(_valid_expression(key) for key in keys): return False
            i = end
        elif _word(tail, i, "LIMIT") or _word(tail, i, "OFFSET"):
            i += 1; end = _next_clause(tail, i)
            if not _valid_expression(tail[i:end]): return False
            i = end
        elif _word(tail, i, "RETURNING"):
            i += 1; end = _next_clause(tail, i)
            if not _valid_expression(tail[i:end]): return False
            i = end
        else: return False
    return True


def _valid_column_list(tokens: List[_Token]) -> bool:
    if not tokens or tokens[0].kind != "LPAREN" or _matching_group(tokens, 0) != len(tokens):
        return False
    body = _rebase_tokens(tokens[1:-1], 1)
    columns = _split_top(body)
    return bool(columns) and all(item and _name_at(item, 0) == len(item) for item in columns)


def _valid_identifier_list(tokens: List[_Token]) -> bool:
    """Validate a list whose entries must each be one identifier token."""
    if not tokens or tokens[0].kind != "LPAREN" or _matching_group(tokens, 0) != len(tokens):
        return False
    body = _rebase_tokens(tokens[1:-1], 1)
    entries = _split_top(body)
    if not entries or any(len(entry) != 1 or entry[0].kind not in ("WORD", "QUOTED_IDENT")
                          for entry in entries):
        return False
    normalized = [entry[0].text.upper() for entry in entries]
    return len(set(normalized)) == len(normalized)


def _valid_column_definition(tokens: List[_Token]) -> bool:
    """Validate the bounded column-definition subset used by the DDL guard."""
    if not tokens:
        return False
    if any(t.kind in ("SEMI", "COMMENT_LINE", "COMMENT_BLOCK") for t in tokens):
        return False
    first = _name_at(tokens, 0)
    if first < 0:
        return False
    i = first
    # A type name is required in the supported subset.  Type parameters such
    # as VARCHAR(20) are accepted, but arbitrary operators are not.
    if i >= len(tokens) or tokens[i].kind not in ("WORD", "QUOTED_IDENT"):
        return False
    i += 1
    if i < len(tokens) and tokens[i].kind == "LPAREN":
        end = _matching_group(tokens, i)
        if end < 0 or end == i + 1:
            return False
        # Type arguments are deliberately numeric only (e.g. VARCHAR(20,2)).
        args = _rebase_tokens(tokens[i + 1:end - 1], 1)
        if any(t.kind not in ("NUMBER", "OP") or
               (t.kind == "OP" and t.text != ",") for t in args):
            return False
        if (not args or args[0].kind != "NUMBER" or args[-1].kind != "NUMBER" or
                any((n % 2 == 0 and arg.kind != "NUMBER") or
                    (n % 2 == 1 and not (arg.kind == "OP" and arg.text == ","))
                    for n, arg in enumerate(args))):
            return False
        i = end

    constraint_words = {"PRIMARY", "NOT", "UNIQUE", "CHECK", "DEFAULT",
                        "COLLATE", "REFERENCES", "CONSTRAINT", "GENERATED"}
    while i < len(tokens):
        if tokens[i].kind != "WORD":
            return False
        constraint = tokens[i].text.upper()
        if constraint not in constraint_words:
            return False
        i += 1
        if constraint == "PRIMARY":
            if i >= len(tokens) or not _word(tokens, i, "KEY"):
                return False
            i += 1
            if i < len(tokens) and _word(tokens, i, "AUTOINCREMENT"):
                i += 1
        elif constraint == "NOT":
            if i >= len(tokens) or not _word(tokens, i, "NULL"):
                return False
            i += 1
        elif constraint == "UNIQUE":
            pass
        elif constraint == "CHECK":
            if i >= len(tokens) or tokens[i].kind != "LPAREN":
                return False
            end = _matching_group(tokens, i)
            if end < 0 or not _valid_ddl_expression(_rebase_tokens(tokens[i + 1:end - 1], 1)):
                return False
            i = end
        elif constraint == "DEFAULT":
            end = i
            while end < len(tokens) and not (tokens[end].kind == "WORD" and
                                              tokens[end].text.upper() in constraint_words):
                end += 1
            if not _valid_ddl_expression(tokens[i:end]):
                return False
            i = end
        elif constraint == "COLLATE":
            i = _name_at(tokens, i)
            if i < 0:
                return False
        elif constraint == "REFERENCES":
            i = _name_at(tokens, i)
            if i < 0:
                return False
            if i < len(tokens) and tokens[i].kind == "LPAREN":
                end = _matching_group(tokens, i)
                if end < 0 or not _valid_column_list(tokens[i:end]):
                    return False
                i = end
            while i < len(tokens) and _word(tokens, i, "ON"):
                if i + 1 >= len(tokens) or not (_word(tokens, i + 1, "DELETE") or _word(tokens, i + 1, "UPDATE")):
                    return False
                if i + 2 >= len(tokens) or tokens[i + 2].kind != "WORD":
                    return False
                i += 3
        elif constraint == "CONSTRAINT":
            i = _name_at(tokens, i)
            if i < 0:
                return False
        elif constraint == "GENERATED":
            if _word(tokens, i, "ALWAYS"):
                i += 1
            if not _word(tokens, i, "AS") or i + 1 >= len(tokens) or tokens[i + 1].kind != "LPAREN":
                return False
            end = _matching_group(tokens, i + 1)
            if end < 0 or not _valid_ddl_expression(_rebase_tokens(tokens[i + 2:end - 1], 1)):
                return False
            i = end
            if i >= len(tokens) or not (_word(tokens, i, "VIRTUAL") or _word(tokens, i, "STORED")):
                return False
            i += 1
    return i == len(tokens)


def _valid_ddl_expression(tokens: List[_Token]) -> bool:
    """Validate an expression only where DDL explicitly permits one."""
    # Bind parameters are never legal inside DDL (CHECK constraints,
    # DEFAULT values, generated columns, trigger WHEN clauses).  The
    # tokenizer emits them as PARAM tokens; the legacy operator spellings
    # are kept as a defensive guard for any other token source.
    if any(t.kind == "PARAM" for t in tokens):
        return False
    if any(t.kind == "OP" and t.text in {"?", "@"} for t in tokens):
        return False
    return _valid_expression(tokens)


def _valid_table_definition_group(tokens: List[_Token]) -> bool:
    """Validate the non-empty bounded CREATE TABLE definition group."""
    if not tokens or tokens[0].kind != "LPAREN" or _matching_group(tokens, 0) != len(tokens):
        return False
    body = _rebase_tokens(tokens[1:-1], 1)
    definitions = _split_top(body)
    return bool(definitions) and all(_valid_column_definition(item) for item in definitions)


def _valid_virtual_table(tail: List[_Token], i: int) -> bool:
    """Validate ``CREATE VIRTUAL TABLE [IF NOT EXISTS] name USING module(...)``.

    The virtual-table name and the module name must be valid name tokens
    (quoted identifiers stay valid; reserved keywords are never names), and
    the module argument group must be present and bounded by
    ``_valid_module_argument_group``.  Any other shape fails closed.
    """
    if not _word(tail, i, "TABLE"):
        return False
    i += 1
    if _word(tail, i, "IF") and _word(tail, i + 1, "NOT") and _word(tail, i + 2, "EXISTS"):
        i += 3
    i = _name_at(tail, i)
    if i < 0:
        return False
    if not _word(tail, i, "USING"):
        return False
    module_end = _name_at(tail, i + 1)
    if module_end < 0:
        return False
    if module_end >= len(tail) or tail[module_end].kind != "LPAREN":
        return False
    end = _matching_group(tail, module_end)
    return end == len(tail) and _valid_module_argument_group(tail[module_end:end])


def _valid_module_argument_group(tokens: List[_Token]) -> bool:
    """Validate the non-empty parenthesized module-argument group of a
    ``CREATE VIRTUAL TABLE ... USING module(...)`` declaration.

    Each comma-separated argument is bounded to a name (optionally
    qualified) or a ``name = value`` module-option assignment whose value is
    a single string/number literal or a name.  Empty entries, nested
    groups, operators, and an empty group fail closed.
    """
    if not tokens or tokens[0].kind != "LPAREN" or _matching_group(tokens, 0) != len(tokens):
        return False
    body = _rebase_tokens(tokens[1:-1], 1)
    if not body:
        return False
    for item in _split_top(body):
        if not item:
            return False
        if _name_at(item, 0) == len(item):
            continue
        equals = [n for n, token in enumerate(item)
                  if token.kind == "OP" and token.text == "="]
        if len(equals) != 1:
            return False
        eq = equals[0]
        if eq == 0 or _name_at(item, 0) != eq:
            return False
        value = item[eq + 1:]
        if len(value) != 1 or value[0].kind not in ("STRING", "NUMBER", "QUOTED_IDENT"):
            return False
    return True


def _valid_ddl(keyword: str, tail: List[_Token]) -> bool:
    """Validate the supported DDL subset: tables/indexes/views/triggers and
    virtual tables, table ADD/RENAME/DROP actions, and DROP with ``IF
    EXISTS``.  Anything outside the bounded forms fails closed.
    """
    i = 0
    if _word(tail, i, "TEMP") or _word(tail, i, "TEMPORARY"): i += 1
    if keyword == "CREATE" and _word(tail, i, "VIRTUAL"):
        return _valid_virtual_table(tail, i + 1)
    if keyword == "CREATE" and _word(tail, i, "UNIQUE"):
        # The UNIQUE modifier is legal only for ``CREATE UNIQUE INDEX``.
        # SQLite permits no unique table/view/trigger, so any other object
        # type (or a missing one) after UNIQUE fails closed instead of being
        # treated as a confident mutation.
        i += 1
        if not _word(tail, i, "INDEX"):
            return False
    types = {"TABLE", "INDEX", "TRIGGER", "VIEW"}
    if i >= len(tail) or tail[i].kind != "WORD" or tail[i].text.upper() not in types:
        return False
    object_type = tail[i].text.upper()
    i += 1
    if keyword == "CREATE" and _word(tail, i, "IF") and _word(tail, i + 1, "NOT") and _word(tail, i + 2, "EXISTS"):
        i += 3
    if keyword == "DROP" and _word(tail, i, "IF") and _word(tail, i + 1, "EXISTS"):
        i += 2
    i = _name_at(tail, i)
    if i < 0: return False
    if keyword == "DROP":
        return object_type in {"TABLE", "INDEX", "VIEW", "TRIGGER"} and i == len(tail)
    if keyword == "ALTER":
        if object_type != "TABLE":
            return False
        if _word(tail, i, "ADD"):
            i += 1
            if _word(tail, i, "COLUMN"): i += 1
            # ``_valid_column_definition`` expects to start at the column
            # name, so hand it the whole remaining definition (name, type,
            # constraints); the name is consumed only to prove it is present.
            j = _name_at(tail, i)
            return j >= 0 and _valid_column_definition(tail[i:])
        if _word(tail, i, "DROP") and _word(tail, i + 1, "COLUMN"): return _name_at(tail, i + 2) == len(tail)
        if _word(tail, i, "RENAME") and _word(tail, i + 1, "COLUMN"):
            j = _name_at(tail, i + 2); return j >= 0 and _word(tail, j, "TO") and _name_at(tail, j + 1) == len(tail)
        if _word(tail, i, "RENAME") and _word(tail, i + 1, "TO"): return _name_at(tail, i + 2) == len(tail)
        return False
    if i == len(tail): return False
    if object_type == "VIEW":
        # A view must have a complete ``AS SELECT`` body; a column-name list
        # before AS and any other tail fail closed.
        return _word(tail, i, "AS") and _valid_select_tail(tail[i + 1:])
    if tail[i].kind == "LPAREN":
        end = _matching_group(tail, i)
        if end == len(tail):
            return _valid_table_definition_group(tail[i:]) if object_type == "TABLE" else _valid_column_list(tail[i:])
        return (end == len(tail) - 2 and _word(tail, end, "WITHOUT") and
                _word(tail, end + 1, "ROWID") and
                (_valid_table_definition_group(tail[i:end]) if object_type == "TABLE"
                 else _valid_column_list(tail[i:end])))
    if _word(tail, i, "AS"):
        return object_type == "TABLE" and _valid_select_tail(tail[i + 1:])
    if object_type == "TRIGGER":
        return _valid_trigger_definition(tail, i)
    if _word(tail, i, "ON"):
        if object_type != "INDEX":
            return False
        j = _name_at(tail, i + 1)
        if j < 0 or j >= len(tail) or tail[j].kind != "LPAREN":
            return False
        end = _matching_group(tail, j)
        return end == len(tail) and _valid_column_list(tail[j:end])
    return False


def _valid_trigger_definition(tail: List[_Token], i: int) -> bool:
    """Validate the supported CREATE TRIGGER header/body boundary.

    Each trigger-body statement must be a supported mutating DML form
    (INSERT/UPDATE/DELETE/REPLACE).  A standalone SELECT or any other
    read/unknown statement inside the body is rejected fail-closed so the
    trigger can never be claimed as a confident mutation from a read-only
    or unknown body.
    """
    if i >= len(tail):
        return False
    if _word(tail, i, "BEFORE") or _word(tail, i, "AFTER"):
        i += 1
    elif _word(tail, i, "INSTEAD") and _word(tail, i + 1, "OF"):
        i += 2
    else:
        return False
    if not (_word(tail, i, "INSERT") or _word(tail, i, "UPDATE") or _word(tail, i, "DELETE")):
        return False
    i += 1
    if i < len(tail) and _word(tail, i, "OF"):
        i += 1
        start = i
        while i < len(tail) and not _word(tail, i, "ON"):
            i += 1
        if not _valid_column_list([_Token("LPAREN", "(", 0)] + tail[start:i] + [_Token("RPAREN", ")", 1)]):
            return False
    if not _word(tail, i, "ON"):
        return False
    i = _name_at(tail, i + 1)
    if i < 0:
        return False
    if _word(tail, i, "FOR") and _word(tail, i + 1, "EACH") and _word(tail, i + 2, "ROW"):
        i += 3
    if _word(tail, i, "WHEN"):
        begin = next((n for n in range(i + 1, len(tail)) if _word(tail, n, "BEGIN")), -1)
        if begin < 0 or begin == i + 1 or not _valid_ddl_expression(tail[i + 1:begin]):
            return False
        i = begin
    if not (i < len(tail) and _word(tail, i, "BEGIN") and
            _word(tail, len(tail) - 1, "END") and i + 1 < len(tail)):
        return False
    body = tail[i + 1:-1]
    statements: List[List[_Token]] = []
    current: List[_Token] = []
    for token in body:
        if token.kind == "SEMI" and token.depth == 0:
            statements.append(current)
            current = []
        else:
            current.append(token)
    if current:
        statements.append(current)
    if not statements:
        return False
    for statement in statements:
        result = _classify_statement(statement)
        if result is None or not result.is_mutation or result.operation not in _TRIGGER_BODY_DML:
            return False
    return True


def _classify_statement(tokens: List[_Token]) -> Optional[_StatementResult]:
    """Classify a single statement's tokens.

    Returns None for empty/comment-only statements, otherwise a
    ``_StatementResult`` whose ``operation`` may be ``OPERATION_UNCLASSIFIABLE``.
    """
    significant = [t for t in tokens if t.kind not in ("COMMENT_LINE", "COMMENT_BLOCK")]
    top_words = [t for t in significant if t.kind == "WORD" and t.depth == 0]
    if not top_words:
        return None

    first = top_words[0].text
    keyword_raw = first
    cte_names: List[str] = []
    recursive = False

    if first.upper() == "WITH":
        # Parse CTEs from the actual token stream.  Looking only at depth-zero
        # words mistakes words inside malformed CTE bodies for the main verb.
        index = 1
        if index < len(significant) and significant[index].kind == "WORD" and significant[index].text.upper() == "RECURSIVE":
            recursive = True
            index += 1
        while True:
            if index >= len(significant) or significant[index].kind != "WORD" or significant[index].depth != 0:
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            name = significant[index].text
            if name.upper() in _RESERVED_WORDS:
                # A CTE name is an identifier: ``WITH select AS (...)`` is
                # malformed and must not be mistaken for a valid statement.
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            index += 1
            # Optional CTE column list.
            if index < len(significant) and significant[index].kind == "LPAREN" and significant[index].depth == 0:
                opening = index
                end = _matching_group(significant, index)
                if end < 0 or not _valid_identifier_list(significant[opening:end]):
                    return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
                names = _split_top(_rebase_tokens(significant[opening + 1:end - 1], 1))
                normalized_names = [tuple((token.kind, token.text.upper()) for token in name) for name in names]
                if len(set(normalized_names)) != len(normalized_names):
                    return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
                index = end
            if index >= len(significant) or significant[index].kind != "WORD" or significant[index].text.upper() != "AS":
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            index += 1
            if index >= len(significant) or significant[index].kind != "LPAREN" or significant[index].depth != 0:
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            opening = index
            closing = index + 1
            while closing < len(significant) and not (significant[closing].kind == "RPAREN" and significant[closing].depth == 1):
                closing += 1
            if closing >= len(significant) or closing == opening + 1:
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            body = _classify_statement(_rebase_tokens(significant[opening + 1:closing], 1))
            if body is None or not body.is_read or body.operation != "SELECT":
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            cte_names.append(name)
            index = closing + 1
            if index < len(significant) and significant[index].kind == "OP" and significant[index].text == "," and significant[index].depth == 0:
                index += 1
                continue
            if index >= len(significant) or significant[index].kind != "WORD" or significant[index].depth != 0:
                return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))
            keyword_raw = significant[index].text
            break

    keyword = keyword_raw.upper()
    if keyword not in READ_KEYWORDS and keyword not in MUTATING_KEYWORDS:
        return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))

    # A classifier must not trust a recognized leading verb.  Validate the
    # minimum shape of each mutating form before classifying it.  This is not a
    # SQL parser; it is a deliberately small proof that the statement has the
    # required target/action and has not acquired an unknown top-level tail.
    start = next(i for i, token in enumerate(significant) if token.kind == "WORD" and token.depth == 0 and token.text.upper() == keyword)
    tail = significant[start + 1:]

    def bad_shape() -> _StatementResult:
        return _StatementResult(OPERATION_UNCLASSIFIABLE, None, False, False, recursive, tuple(cte_names))

    # The classifier is intentionally not a SQL parser, but it must prove that
    # the complete top-level statement was consumed.  Keep this grammar small
    # and explicit: accepting an unknown tail is worse than a false negative in
    # a database access guard.
    if not _valid_statement_grammar(keyword, tail):
        return bad_shape()
    return _StatementResult(
        keyword if keyword in MUTATING_KEYWORDS else "SELECT",
        keyword if keyword in MUTATING_KEYWORDS else "SELECT",
        keyword in MUTATING_KEYWORDS,
        keyword == "SELECT",
        recursive,
        tuple(cte_names),
    )

def _merge_cte_names(results: List[_StatementResult]) -> Tuple[str, ...]:
    """Merge CTE names across statements, preserving first-seen order."""
    seen: List[str] = []
    for result in results:
        for name in result.cte_names:
            if name not in seen:
                seen.append(name)
    return tuple(seen)


def _unclassified_result(reason: str, statement_count: int = 0) -> SqlClassification:
    return SqlClassification(
        operation=OPERATION_UNCLASSIFIABLE,
        is_mutation=False,
        is_read=False,
        keyword=None,
        statement_count=statement_count,
        recursive=False,
        cte_names=(),
        error_code=ERROR_UNCLASSIFIABLE,
        reason=reason,
    )


# ------------------------------------------------------------------
# Public API
# ------------------------------------------------------------------


def classify_sql(sql: str) -> SqlClassification:
    """Classify a SQL string as a read or a mutating statement (fail-closed).

    Args:
        sql: The SQL text (multiline supported).

    Returns:
        A frozen ``SqlClassification``.  When the SQL is unknown, ambiguous,
        or structurally uncertain, ``operation`` is ``UNCLASSIFIABLE`` and
        ``error_code`` is ``DB_ROOM_QUERY_UNCLASSIFIABLE`` with a controlled
        ``reason``; the classifier never guesses and never raises.
    """
    if sql is None or not isinstance(sql, str):
        return _unclassified_result(REASON_INVALID_INPUT)
    if not sql.strip():
        return _unclassified_result(REASON_NO_STATEMENT)

    tokens, token_error = _tokenize(sql)
    if token_error is not None:
        return _unclassified_result(token_error)

    non_empty = [s for s in _split_statements(tokens) if _is_significant(s)]
    if not non_empty:
        return _unclassified_result(REASON_NO_STATEMENT)

    results: List[_StatementResult] = []
    for statement in non_empty:
        result = _classify_statement(statement)
        if result is not None:
            results.append(result)

    mutation = None
    reads = 0
    unknown = 0
    for result in results:
        if result.is_mutation:
            if mutation is None:
                mutation = result
        elif result.is_read:
            reads += 1
        else:
            unknown += 1

    # Unknown statements take precedence over mutations.  A mixed batch must
    # not be treated as safely mutating or safely readable based on the one
    # statement we happened to recognize.
    if unknown:
        return _unclassified_result(REASON_MIXED_UNCLASSIFIABLE if len(non_empty) > 1 else REASON_UNKNOWN_KEYWORD, len(non_empty))

    if mutation is not None:
        return SqlClassification(
            operation=mutation.operation,
            is_mutation=True,
            is_read=False,
            keyword=mutation.keyword,
            statement_count=len(non_empty),
            recursive=mutation.recursive,
            cte_names=mutation.cte_names,
            error_code=None,
            reason=None,
        )

    # All statements confidently read.
    if unknown == 0 and reads > 0:
        return SqlClassification(
            operation="SELECT",
            is_mutation=False,
            is_read=True,
            keyword="SELECT",
            statement_count=len(non_empty),
            recursive=any(result.recursive for result in results),
            cte_names=_merge_cte_names(results),
            error_code=None,
            reason=None,
        )

    return _unclassified_result(REASON_UNKNOWN_KEYWORD, len(non_empty))
