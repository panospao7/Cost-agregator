"""Pytest suite for ``scripts/db_guard/policy_v2_evidence.py``.

Exercises ``verify_v2_policy_source_evidence(entries, repo_root)`` end to
end against temporary Kotlin fixtures written under
``tmp_path/app/src/main/java`` (the only approved production source root).
Entries are built directly via
:class:`scripts.db_guard.policy_model.PolicyEntry` so every identity field
is explicit; assertions always compare against the controlled constants
exported by ``scripts/db_guard/policy_errors.py`` -- never free-form text.

Covered contracts (one test each):

1. an exact entry verifies with zero errors;
2. missing owner FQCN            -> OWNER_MISSING;
3. duplicate owner FQCN          -> OWNER_AMBIGUOUS;
4. unknown method name           -> CALLABLE_MISSING;
5. identical-signature overloads -> CALLABLE_AMBIGUOUS;
6. wrong ordered parameterTypes  -> fails closed (see notes);
7. constructor kind              -> KIND_UNSUPPORTED;
8. abstract/no-body callable     -> BODY_UNSUPPORTED;
9. daoAccessor absent from body  -> MUTATION_NOT_FOUND;
10. daoFqcn mismatch             -> controlled error (see notes);
11. operation not invoked        -> MUTATION_NOT_FOUND;
12. extra body mutation          -> UNLISTED_MUTATION;
13. path outside approved roots  -> PATH_OUTSIDE_ROOTS;
14. nonexistent file             -> FILE_UNREADABLE;
15. sibling overload never used as evidence (no pass);
16. garbage Kotlin never raises -- controlled codes only;
17. one accessor, two daoFqcn values -> DAO_AMBIGUOUS;
18. top-level-function kind          -> KIND_UNSUPPORTED;
19. second group member's operation missing -> MUTATION_NOT_FOUND;
20. sibling method-local DAO alias never authorizes another method
    (no cross-method pollution);
21. unterminated block comment       -> PARSER_UNCERTAIN, never raises.
22. nested-object method-local DAO alias never enters the class-scope
    map; the outer class property alias still resolves.

Implementation-aligned notes (verified against current source):

* Wrong ordered ``parameterTypes``: ``resolve_callable`` returns
  ``SIGNATURE_UNSUPPORTED`` (not ``METHOD_MISSING``) whenever a same-name
  overload exists, and the evidence verifier maps that status to
  ``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN``.  The test pins that
  fail-closed behavior and explicitly asserts ``CALLABLE_MISSING`` is NOT
  produced for this scenario.
* ``daoFqcn`` is metadata-only in PR-01: a pure FQCN swap over an otherwise
  exact body currently verifies cleanly because ``DAO_FQCN_MISMATCH`` is a
  reserved-but-unemitted code.  The mismatch test therefore pairs the wrong
  ``daoFqcn`` with an accessor identity that cannot be evidenced in the
  body and accepts either the current ``MUTATION_NOT_FOUND`` semantics or a
  future ``DAO_FQCN_MISMATCH`` finding -- asserting only that verification
  fails closed with a controlled v2-evidence code.
"""

from __future__ import annotations

import os
import sys

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(_SCRIPTS_DIR)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.db_guard.policy_errors import (  # noqa: E402
    KNOWN_POLICY_ERROR_CODES,
    POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED,
    POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS,
    POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING,
    POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS,
    POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH,
    POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE,
    POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED,
    POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND,
    POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS,
    POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING,
    POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
    POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS,
    POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION,
    PolicyError,
)
from scripts.db_guard.policy_model import (  # noqa: E402
    BarrierMode,
    CallableKind,
    PolicyEntry,
)
from scripts.db_guard.policy_v2_evidence import (  # noqa: E402
    verify_v2_policy_source_evidence,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

REPO_KT = "app/src/main/java/com/example/Repo.kt"

HAPPY_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

DUPLICATE_OWNER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
    }
}

class Repo(private val legacyDao: LegacyGroupDao) {
    fun insertGroup(group: Group) {
        legacyDao.insert(group)
    }
}

data class Group(val id: Int)
"""

OVERLOAD_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
    }

    fun insertGroup(group: Group) {
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

TWO_PARAM_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group, options: Options) {
        groupDao.insert(group)
    }
}

data class Group(val id: Int)

data class Options(val flag: Boolean)
"""

ABSTRACT_SOURCE = """\
package com.example

import com.example.data.GroupDao

abstract class Repo(private val groupDao: GroupDao) {
    abstract fun insertGroup(group: Group)
}

data class Group(val id: Int)
"""

OTHER_ACCESSOR_SOURCE = """\
package com.example

import com.example.data.AuditDao
import com.example.data.GroupDao

class Repo(
    private val groupDao: GroupDao,
    private val auditDao: AuditDao,
) {
    fun insertGroup(group: Group) {
        auditDao.insert(group)
    }
}

data class Group(val id: Int)
"""

WRONG_OP_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.delete(group)
    }
}

data class Group(val id: Int)
"""

EXTRA_MUTATION_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
        groupDao.delete(group.id)
    }
}

data class Group(val id: Int)
"""

SIBLING_OVERLOAD_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        logInsert(group)
    }

    fun insertGroup(group: Group, options: Options) {
        groupDao.insert(group)
    }
}

data class Group(val id: Int)

data class Options(val flag: Boolean)
"""

GARBAGE_SOURCE = (
    "this is not kotlin at all !!! @@@ ###\n"
    "random identifiers everywhere 42 %%%\n"
)

BODYLESS_OWNER_SOURCE = """\
package com.example

class Repo

data class Group(val id: Int)
"""

UNTERMINATED_COMMENT_SOURCE = (
    "package com.example\n"
    "\n"
    "/* this block comment is never terminated\n"
    "class Repo(private val groupDao: GroupDao) {\n"
    "    fun insertGroup(group: Group) {\n"
    "        groupDao.insert(group)\n"
    "    }\n"
    "}\n"
)

CROSS_METHOD_ALIAS_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun auditGroup(item: Item) {
        logAudit(item)
    }

    fun otherMethod(item: Item) {
        val scopedDao = database.groupDao()
        scopedDao.insert(item)
    }
}

data class Item(val id: Int)
"""

NESTED_CLASS_ALIAS_SOURCE = """\
package com.example

import com.example.data.ExpenseDao

class Repo {
    private val dao: ExpenseDao

    fun removeItem(item: Item) {
        dao.delete(item)
    }

    object Cache {
        fun clear() {
            val dao = database.otherDao()
        }
    }
}

data class Item(val id: Int)
"""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _write_repo(tmp_path, text):
    """Write *text* to tmp_path/app/src/main/java/com/example/Repo.kt."""
    path = (
        tmp_path / "app" / "src" / "main" / "java"
        / "com" / "example" / "Repo.kt"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return str(tmp_path)


def _entry(**overrides):
    """Return a PolicyEntry matching HAPPY_SOURCE, with overrides applied."""
    fields = dict(
        path=REPO_KT,
        owner_fqcn="com.example.Repo",
        kind=CallableKind.FUNCTION,
        method="insertGroup",
        receiver=None,
        parameter_types=("com.example.Group",),
        dao_accessor="groupDao",
        dao_fqcn="com.example.data.GroupDao",
        operation="insert",
        barrier_mode=BarrierMode.DIRECT,
        reason="guard evidence unit test",
        owner="db-guard-tests",
        linked_issue="GR00-EVIDENCE-T",
    )
    fields.update(overrides)
    return PolicyEntry(**fields)


def _codes(errors):
    return [error.code for error in errors]


# ===========================================================================
# 1. Exact entry
# ===========================================================================


def test_exact_entry_produces_zero_errors(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert errors == ()


# ===========================================================================
# 2-3. Owner resolution
# ===========================================================================


def test_missing_owner_fqcn_reports_owner_missing(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(owner_fqcn="com.example.MissingRepo")], str(tmp_path)
    )
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING]


def test_two_same_fqcn_owners_report_owner_ambiguous(tmp_path):
    _write_repo(tmp_path, DUPLICATE_OWNER_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS]


# ===========================================================================
# 4-6. Callable resolution
# ===========================================================================


def test_unknown_method_name_reports_callable_missing(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(method="deleteGroup")], str(tmp_path)
    )
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING]


def test_identical_signature_overloads_report_callable_ambiguous(tmp_path):
    _write_repo(tmp_path, OVERLOAD_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS]


def test_wrong_ordered_parameter_types_fail_closed(tmp_path):
    _write_repo(tmp_path, TWO_PARAM_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(parameter_types=("com.example.Options", "com.example.Group"))],
        str(tmp_path),
    )
    # The method name exists with a different ordered signature, so
    # resolve_callable() reports SIGNATURE_UNSUPPORTED, which the evidence
    # verifier maps to PARSER_UNCERTAIN -- never to a silent pass.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN]
    assert errors[0].context.get("status") == "SIGNATURE_UNSUPPORTED"
    assert POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING not in _codes(errors)


# ===========================================================================
# 7-8. Kind gate and body gate
# ===========================================================================


def test_constructor_kind_reports_kind_unsupported(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(kind=CallableKind.CONSTRUCTOR)], str(tmp_path)
    )
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED]


def test_abstract_no_body_callable_reports_body_unsupported(tmp_path):
    _write_repo(tmp_path, ABSTRACT_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED]


# ===========================================================================
# 9-11. Mutation evidence
# ===========================================================================


def test_unresolvable_dao_accessor_reports_mutation_not_found(tmp_path):
    _write_repo(tmp_path, OTHER_ACCESSOR_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # The declared accessor groupDao has no mutation anywhere in the body;
    # the auditDao call resolves to a different DAO identity.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND]


def test_dao_fqcn_mismatch_still_fails_with_controlled_error(tmp_path):
    _write_repo(tmp_path, OTHER_ACCESSOR_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(dao_fqcn="com.example.data.LegacyGroupDao")], str(tmp_path)
    )
    assert errors, "expected at least one controlled error"
    codes = _codes(errors)
    assert all(code in KNOWN_POLICY_ERROR_CODES for code in codes)
    assert (
        POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND in codes
        or POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH in codes
    )


def test_operation_not_invoked_reports_mutation_not_found(tmp_path):
    _write_repo(tmp_path, WRONG_OP_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(operation="upsert")], str(tmp_path)
    )
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND]


# ===========================================================================
# 12. Unlisted mutation
# ===========================================================================


def test_extra_body_mutation_reports_unlisted_mutation(tmp_path):
    _write_repo(tmp_path, EXTRA_MUTATION_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION]


# ===========================================================================
# 13-14. Path and file gates
# ===========================================================================


def test_path_outside_approved_roots_reports_path_outside_roots(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(path="app/src/test/java/com/example/Repo.kt")], str(tmp_path)
    )
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS]


def test_nonexistent_file_reports_file_unreadable(tmp_path):
    # The declared-root resolution needs the conventional production root
    # to exist; with it present, a policy path whose FILE is missing fails
    # as FILE_UNREADABLE.
    (tmp_path / "app" / "src" / "main" / "java").mkdir(parents=True)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE]


# ===========================================================================
# 15. Sibling overload isolation
# ===========================================================================


def test_sibling_overload_is_never_used_as_evidence(tmp_path):
    _write_repo(tmp_path, SIBLING_OVERLOAD_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # The targeted single-parameter overload resolves EXACTLY (no ambiguity
    # findings) but its own body holds no DAO mutation; the sibling
    # overload's body must never be borrowed as evidence and the entry
    # must never pass.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND]
    assert POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING not in _codes(errors)
    assert POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS not in _codes(errors)
    assert POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION not in _codes(errors)


# ===========================================================================
# 16. Garbage input never raises
# ===========================================================================


def test_garbage_kotlin_file_returns_controlled_errors_without_raising(
    tmp_path,
):
    _write_repo(tmp_path, GARBAGE_SOURCE)
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(errors, tuple)
    assert errors, "expected controlled findings for garbage input"
    for error in errors:
        assert isinstance(error, PolicyError)
        assert error.code in KNOWN_POLICY_ERROR_CODES

    # A bodyless owner declaration is parser-uncertain rather than fatal.
    _write_repo(tmp_path, BODYLESS_OWNER_SOURCE)
    uncertain = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(uncertain, tuple)
    assert _codes(uncertain) == [POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN]


# ===========================================================================
# 17. DAO accessor ambiguity inside one callable group
# ===========================================================================


def test_two_dao_fqcns_behind_one_accessor_report_dao_ambiguous(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [
            _entry(),
            _entry(dao_fqcn="com.example.data.LegacyGroupDao"),
        ],
        str(tmp_path),
    )
    # Both entries share one callable identity but declare different
    # daoFqcn values behind the same daoAccessor, so the accessor cannot
    # resolve to a single DAO identity and verification fails closed.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS]


# ===========================================================================
# 18. Top-level-function kind gate
# ===========================================================================


def test_top_level_function_kind_reports_kind_unsupported(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [_entry(kind=CallableKind.TOP_LEVEL_FUNCTION)], str(tmp_path)
    )
    # Only plain member functions are evidenced; any other kind fails
    # closed even when the owner resolves exactly once.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED]


# ===========================================================================
# 19. Per-member required-pair check inside one callable group
# ===========================================================================


def test_second_group_member_missing_mutation_reported(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [
            _entry(operation="insert"),
            _entry(operation="delete"),
        ],
        str(tmp_path),
    )
    # Both entries share one callable identity (callable_key excludes the
    # operation), so they form ONE group verified against the SAME body:
    # member A's insert is evidenced, but member B's delete is not.  The
    # per-member required-pair check must report B's own missing pair
    # instead of letting A's evidence pass the whole group.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND]
    assert errors[0].context.get("operation") == "delete"
    assert errors[0].context.get("dao_accessor") == "groupDao"


# ===========================================================================
# 20. Cross-method DAO alias isolation
# ===========================================================================


def test_cross_method_dao_alias_does_not_authorize(tmp_path):
    _write_repo(tmp_path, CROSS_METHOD_ALIAS_SOURCE)
    errors = verify_v2_policy_source_evidence(
        [
            _entry(
                method="auditGroup",
                parameter_types=("com.example.Item",),
                dao_accessor="scopedDao",
                operation="insert",
            )
        ],
        str(tmp_path),
    )
    # otherMethod declares the method-local alias scopedDao and uses it
    # only there; the class-scope DAO map excludes every member callable's
    # declaration span, so that alias can never leak into auditGroup's
    # evidence.  auditGroup's body holds no DAO mutation at all, so the
    # entry fails with MUTATION_NOT_FOUND -- never a silent pass.
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND]
    assert errors[0].context.get("dao_accessor") == "scopedDao"
    assert errors[0].context.get("operation") == "insert"


# ===========================================================================
# 21. Unterminated block comment never raises
# ===========================================================================


def test_malformed_kotlin_reports_parser_uncertain_not_raise(tmp_path):
    _write_repo(tmp_path, UNTERMINATED_COMMENT_SOURCE)
    # The unterminated /* makes mask_kotlin_source() fail closed with
    # ParserError("MALFORMED_SOURCE"); the per-group guard converts that
    # into one controlled PARSER_UNCERTAIN finding instead of propagating.
    errors = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(errors, tuple)
    assert _codes(errors) == [POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN]
    # Context stays bounded: relative path plus exception class name only.
    assert errors[0].context.get("exc_type") == "ParserError"
    assert set(errors[0].context.keys()) <= {"path", "exc_type"}
    assert errors[0].context.get("path") == REPO_KT


# ===========================================================================
# 22. Nested-class method-local DAO alias isolation
# ===========================================================================


def test_nested_class_local_alias_cannot_authorize_outer_mutation(tmp_path):
    _write_repo(tmp_path, NESTED_CLASS_ALIAS_SOURCE)
    # Happy path: removeItem's dao.delete(item) resolves through the
    # class-body property alias (dao -> expenseDao, the Room-accessor
    # identity DAO_PROPERTY_DECL derives from ExpenseDao), so the honest
    # entry verifies with zero errors.  Without excluding nested-owner
    # callable spans, Cache's method-local `val dao = database.otherDao()`
    # would overwrite that property mapping via LOCAL_DAO_ASSIGN and this
    # entry would fail closed instead.
    errors = verify_v2_policy_source_evidence(
        [
            _entry(
                method="removeItem",
                parameter_types=("com.example.Item",),
                dao_accessor="expenseDao",
                dao_fqcn="com.example.data.ExpenseDao",
                operation="delete",
            )
        ],
        str(tmp_path),
    )
    assert errors == ()

    # The nested object's method-local alias must never enter the
    # class-scope DAO map: claiming its accessor identity and operation
    # against removeItem fails closed -- the nested-local neither
    # authorizes foreign mutations nor overwrites the property alias.
    forged = verify_v2_policy_source_evidence(
        [
            _entry(
                method="removeItem",
                parameter_types=("com.example.Item",),
                dao_accessor="otherDao",
                dao_fqcn="com.example.data.ExpenseDao",
                operation="clear",
            )
        ],
        str(tmp_path),
    )
    assert _codes(forged) == [POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND]
    assert forged[0].context.get("dao_accessor") == "otherDao"
    assert forged[0].context.get("operation") == "clear"


# ===========================================================================
# 23. Manifest-declared Kotlin source root (PR-GR-03 Slice D)
# ===========================================================================


_KOTLIN_REPO_KT = "app/src/main/kotlin/com/example/Repo.kt"

_DUAL_ROOT_MANIFEST = """\
schemaVersion: 1
roots:
  - module: :app
    sourceSet: main
    path: app/src/main/java
  - module: :app
    sourceSet: main
    path: app/src/main/kotlin
"""


def test_manifest_declared_kotlin_root_path_resolves(tmp_path):
    """A policy path under a manifest-declared src/main/kotlin root verifies.

    The synthetic repo ships a source-root manifest declaring BOTH the java
    and the kotlin production roots; the entry and its source file live
    under the kotlin root, so declared-root membership — not the historical
    single java tuple — authorizes the path and verification passes with
    zero errors.
    """
    guards = tmp_path / "config" / "guards"
    guards.mkdir(parents=True)
    (guards / "production_source_roots.yml").write_text(
        _DUAL_ROOT_MANIFEST, encoding="utf-8"
    )
    # Topology gate: every declared root must exist, including the
    # otherwise-unused java root.
    (tmp_path / "app" / "src" / "main" / "java").mkdir(parents=True)
    kotlin_repo = (
        tmp_path / "app" / "src" / "main" / "kotlin"
        / "com" / "example" / "Repo.kt"
    )
    kotlin_repo.parent.mkdir(parents=True)
    kotlin_repo.write_text(HAPPY_SOURCE, encoding="utf-8")
    errors = verify_v2_policy_source_evidence(
        [_entry(path=_KOTLIN_REPO_KT)], str(tmp_path)
    )
    assert errors == ()
