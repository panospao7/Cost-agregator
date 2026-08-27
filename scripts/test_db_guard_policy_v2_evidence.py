"""Pytest suite for ``scripts/db_guard/policy_v2_evidence.py``.

Exercises ``verify_v2_policy_source_evidence(entries, repo_root)`` end to
end against temporary Kotlin fixtures written under
``tmp_path/app/src/main/java`` (the only approved production source root).
Entries are built directly via
:class:`scripts.db_guard.policy_model.PolicyEntry` so every identity field
is explicit; assertions always compare against the controlled constants
exported by ``scripts/db_guard/policy_errors.py`` -- never free-form text.

Since PR-GR-06 Slice 1 the verifier returns an :class:`EvidenceResult`
(frozen, deterministic) instead of a bare tuple of ``PolicyError``; the
helpers below flatten its per-group/batch diagnostics so every historical
code-level assertion keeps its exact semantics (same controlled codes, same
trust outcomes -- nothing weakened).

Covered contracts (one test each):

1. an exact entry verifies trusted with zero diagnostics;
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
23. manifest-declared Kotlin source root resolves (PR-GR-03 Slice D).
24. barrierMode=direct without local barrier syntax -> BARRIER_METADATA_
    INCONSISTENT, group untrusted (PR-GR-06 Slice 1);
25. barrierMode=helper carries no local direct-barrier requirement;
26. barrierMode=workerMediated carries no local direct-barrier requirement;
27. a barrier strictly AFTER the mutation never satisfies direct;
28. a barrier inside a comment never satisfies direct (masked evidence);
29. a qualified-receiver barrier never satisfies direct;
30. per-mutation proof: barrier before the second mutation only fails;
31. barrier before EVERY mutation verifies a two-mutation direct group;
32. EvidenceResult shape: groups sorted by canonical key, deterministic
    to_dict(), repo-relative paths only;
33. explicit source_roots SourceRootSet is honored verbatim (accepted and
    rejected membership both fail closed/exact);
34. empty entry list is vacuously trusted;
35. unresolvable source roots fail closed as ONE batch-level diagnostic;
36. EvidenceDiagnostic fails closed on unknown codes and sorts context.
37. PR-GR-06 Slice 2 "Step-1 negative contract lock": all ten mandated
    negative shapes plus the matrix gaps are pinned exactly once each --
    new tests live under the Step-1 banner section below; shapes already
    pinned by GR-01-era tests above are referenced from that banner and
    deliberately NOT duplicated.
38. N8 closure (Plan Step-1 #8): with a Room inventory provided, every
    group member's declared daoFqcn is cross-checked against the inventory
    FQCN set of the accessor resolved at the mutation site -- matching
    FQCN verifies trusted, swapped FQCN -> DAO_FQCN_MISMATCH (untrusted),
    and the pre-existing DAO_AMBIGUOUS path for accessors backed by two
    same-simple-name DAOs stays intact; without an inventory the pure
    daoFqcn-swap pass remains pinned as the documented limitation.
39. GR-06 closure: v2 evidence discovery runs under the parser's PR-GR-05
    tolerant type-resolution semantics -- an unresolvable-type SIBLING no
    longer poisons an exact target into whole-file PARSER_UNCERTAIN (the
    group verifies trusted), while a TARGET whose every same-name
    declaration is retained under TYPE_UNRESOLVED status fails with the
    distinct DB_V2_POLICY_SIGNATURE_UNRESOLVED code; the strict-mode
    SIGNATURE_UNSUPPORTED pins above are unchanged.
40. GR-08a closure: the class-scope DAO map spans the FULL owner
    declaration (constructor-parameter properties included), and a policy
    accessor may spell the source property alias (``dao``) -- resolved
    through the same scoped map to the derived Room accessor identity
    (``rawNotificationDao``) for the required-pair, ambiguity, unlisted,
    and inventory daoFqcn comparisons.  A large real-shape function
    (when-blocks, lambdas, try/catch, early-return duplicate branches)
    evidences ``dao.markProcessed`` end to end; sibling-owner headers stay
    isolated; the bridge never widens unlisted coverage; unknown
    accessors still fail closed.

Implementation-aligned notes (verified against current source):

* Wrong ordered ``parameterTypes``: ``resolve_callable`` returns
  ``SIGNATURE_UNSUPPORTED`` (not ``METHOD_MISSING``) whenever a same-name
  overload exists, and the evidence verifier maps that status to
  ``DB_V2_POLICY_PARSER_UNCERTAIN``.  The test pins that fail-closed
  behavior and explicitly asserts ``CALLABLE_MISSING`` is NOT produced for
  this scenario.
* ``daoFqcn`` enforcement is inventory-gated (Plan Step-1 #8): WITH a Room
  inventory the declared daoFqcn of every group member must belong to the
  inventory FQCN set of the accessor resolved at the mutation site, and a
  swap fails closed with ``DAO_FQCN_MISMATCH``.  WITHOUT an inventory a
  pure FQCN swap over an otherwise exact body still verifies cleanly --
  bodies yield accessor-scoped identities only (never reliable DAO
  FQCNs), so no ground truth exists; that limitation is pinned by
  test_without_inventory_pure_fqcn_swap_remains_documented_limitation.
  The historical pairing test (wrong daoFqcn behind an accessor that has
  NO mutation in the body) keeps its MUTATION_NOT_FOUND semantics.
* Direct-barrier evidence reuses the shared
  ``policy_parsing._barrier_before_line`` machinery (GR-05 approach):
  real unqualified ``writeBarrier.checkWritesAllowed(...)`` /
  ``writeBarrier.runWrite(...)`` calls only, statefully masked,
  strictly before EVERY mutation line.
"""

from __future__ import annotations

import json
import os
import sys

import pytest

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(_SCRIPTS_DIR)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.ci.finding_rule_catalog import (  # noqa: E402
    GUARD_DB_ACCESS,
    is_known_diagnostic,
    known_diagnostic,
)
from scripts.db_guard.dao_accessors import DaoId  # noqa: E402
from scripts.db_guard.policy_errors import (  # noqa: E402
    KNOWN_POLICY_ERROR_CODES,
    DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT,
    DB_V2_POLICY_BODY_UNSUPPORTED,
    DB_V2_POLICY_CALLABLE_AMBIGUOUS,
    DB_V2_POLICY_CALLABLE_MISSING,
    DB_V2_POLICY_DAO_AMBIGUOUS,
    DB_V2_POLICY_DAO_FQCN_MISMATCH,
    DB_V2_POLICY_FILE_UNREADABLE,
    DB_V2_POLICY_KIND_UNSUPPORTED,
    DB_V2_POLICY_MUTATION_NOT_FOUND,
    DB_V2_POLICY_OWNER_AMBIGUOUS,
    DB_V2_POLICY_OWNER_MISSING,
    DB_V2_POLICY_PARSER_UNCERTAIN,
    DB_V2_POLICY_PATH_OUTSIDE_ROOTS,
    DB_V2_POLICY_SIGNATURE_UNRESOLVED,
    DB_V2_POLICY_UNLISTED_MUTATION,
    PolicyError,
)
from scripts.db_guard.policy_model import (  # noqa: E402
    BarrierMode,
    CallableKind,
    PolicyEntry,
)
from scripts.db_guard.policy_v2_evidence import (  # noqa: E402
    EvidenceDiagnostic,
    EvidenceResult,
    verify_v2_policy_source_evidence,
)
from scripts.db_guard.room_inventory import (  # noqa: E402
    INVENTORY_SCHEMA,
    INVENTORY_VERSION,
    RoomInventory,
)
from scripts.db_guard.source_roots import (  # noqa: E402
    DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
    SourceRoot,
    SourceRootSet,
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
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

# Identical to HAPPY_SOURCE minus the direct-barrier call: the honest
# mutation evidence stands, but the default barrierMode=direct claim can no
# longer be locally consistent.
NO_BARRIER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

BARRIER_AFTER_MUTATION_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
        writeBarrier.checkWritesAllowed()
    }
}

data class Group(val id: Int)
"""

BARRIER_IN_COMMENT_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        // writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

QUALIFIED_BARRIER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        security.writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

TWO_MUTATIONS_PARTIAL_BARRIER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        groupDao.insert(group)
        writeBarrier.checkWritesAllowed()
        groupDao.delete(group.id)
    }
}

data class Group(val id: Int)
"""

TWO_MUTATIONS_FULL_BARRIER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
        groupDao.delete(group.id)
    }
}

data class Group(val id: Int)
"""

TWO_METHODS_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }

    fun removeGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.delete(group.id)
    }
}

data class Group(val id: Int)
"""

DUPLICATE_OWNER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

class Repo(private val legacyDao: LegacyGroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }

    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
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
        writeBarrier.checkWritesAllowed()
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

# GR-06 closure fixtures: ``ProjectType`` is declared nowhere in the file,
# imported by nothing, and not a builtin, so the parser's closed-world
# resolver cannot resolve it.  Under strict discovery EITHER fixture aborted
# wholesale as PARSER_UNCERTAIN; under the PR-GR-05/GR-06 tolerant semantics
# the debt is retained per declaration and only ever gates its own signature.
SIBLING_TYPE_UNRESOLVED_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }

    fun audit(entry: ProjectType) {}
}

data class Group(val id: Int)
"""

TARGET_TYPE_UNRESOLVED_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: ProjectType) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
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


def _diagnostics(result):
    """Flat bounded diagnostics in EvidenceResult.diagnostics order.

    ``EvidenceResult.diagnostics`` already aggregates every group's
    diagnostics EXACTLY ONCE, in sorted canonical-key group order (and, for
    a failed declared-root-set resolution, carries the single batch-level
    diagnostic with empty groups), so the aggregate itself is the exact
    historical single-emission sequence these assertions were pinned
    against.  Flattening ``result.groups`` a second time on top of the
    aggregate -- as this helper briefly did after the GR-06 EvidenceResult
    refactor -- re-included every group finding twice and doubled every
    diagnostic-family expectation.
    """
    return tuple(result.diagnostics)


def _codes(result):
    return [d.code for d in _diagnostics(result)]


def _first_context(result):
    return _diagnostics(result)[0].context_dict


def _inventory(*dao_fqcns):
    """Return a minimal real RoomInventory carrying only DAO identity data.

    Uses the production ``RoomInventory`` shape: ``daos`` is a tuple of
    ``DaoId(fqcn, canonical_path)`` -- exactly the identity data the
    evidence verifier's accessor->FQCN cross-check reads.  Methods,
    mutators, and diagnostics stay empty; the verifier consumes only the
    DAO FQCN set.
    """
    return RoomInventory(
        schema=INVENTORY_SCHEMA,
        schema_version=INVENTORY_VERSION,
        daos=tuple(
            DaoId(
                fqcn,
                "app/src/main/java/%s.kt" % fqcn.replace(".", "/"),
            )
            for fqcn in dao_fqcns
        ),
        methods=(),
        mutators=(),
        diagnostics=(),
    )


# ===========================================================================
# 1. Exact entry
# ===========================================================================


def test_exact_entry_verifies_trusted_with_zero_diagnostics(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(result, EvidenceResult)
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 1
    group = result.groups[0]
    assert group.trusted is True
    assert group.diagnostics == ()
    assert group.mutation_keys == ("groupDao|insert",)
    assert len(group.policy_keys) == 1
    assert result.mutation_key_count == 1
    assert result.policy_mutation_key_count == 1


# ===========================================================================
# 2-3. Owner resolution
# ===========================================================================


def test_missing_owner_fqcn_reports_owner_missing(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(owner_fqcn="com.example.MissingRepo")], str(tmp_path)
    )
    assert _codes(result) == [DB_V2_POLICY_OWNER_MISSING]


def test_two_same_fqcn_owners_report_owner_ambiguous(tmp_path):
    _write_repo(tmp_path, DUPLICATE_OWNER_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(result) == [DB_V2_POLICY_OWNER_AMBIGUOUS]


# ===========================================================================
# 4-6. Callable resolution
# ===========================================================================


def test_unknown_method_name_reports_callable_missing(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(method="deleteGroup")], str(tmp_path)
    )
    assert _codes(result) == [DB_V2_POLICY_CALLABLE_MISSING]


def test_identical_signature_overloads_report_callable_ambiguous(tmp_path):
    _write_repo(tmp_path, OVERLOAD_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(result) == [DB_V2_POLICY_CALLABLE_AMBIGUOUS]


def test_wrong_ordered_parameter_types_fail_closed(tmp_path):
    _write_repo(tmp_path, TWO_PARAM_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(parameter_types=("com.example.Options", "com.example.Group"))],
        str(tmp_path),
    )
    # The method name exists with a different ordered signature, so
    # resolve_callable() reports SIGNATURE_UNSUPPORTED, which the evidence
    # verifier maps to PARSER_UNCERTAIN -- never to a silent pass.
    assert _codes(result) == [DB_V2_POLICY_PARSER_UNCERTAIN]
    assert _first_context(result).get("status") == "SIGNATURE_UNSUPPORTED"
    assert DB_V2_POLICY_CALLABLE_MISSING not in _codes(result)


# ===========================================================================
# 7-8. Kind gate and body gate
# ===========================================================================


def test_constructor_kind_reports_kind_unsupported(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(kind=CallableKind.CONSTRUCTOR)], str(tmp_path)
    )
    assert _codes(result) == [DB_V2_POLICY_KIND_UNSUPPORTED]


def test_abstract_no_body_callable_reports_body_unsupported(tmp_path):
    _write_repo(tmp_path, ABSTRACT_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(result) == [DB_V2_POLICY_BODY_UNSUPPORTED]


# ===========================================================================
# 9-11. Mutation evidence
# ===========================================================================


def test_unresolvable_dao_accessor_reports_mutation_not_found(tmp_path):
    _write_repo(tmp_path, OTHER_ACCESSOR_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # The declared accessor groupDao has no mutation anywhere in the body;
    # the auditDao call resolves to a different DAO identity.
    assert _codes(result) == [DB_V2_POLICY_MUTATION_NOT_FOUND]


def test_dao_fqcn_mismatch_still_fails_with_controlled_error(tmp_path):
    _write_repo(tmp_path, OTHER_ACCESSOR_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(dao_fqcn="com.example.data.LegacyGroupDao")], str(tmp_path)
    )
    codes = _codes(result)
    assert codes, "expected at least one controlled error"
    assert all(code in KNOWN_POLICY_ERROR_CODES for code in codes)
    assert (
        DB_V2_POLICY_MUTATION_NOT_FOUND in codes
        or DB_V2_POLICY_DAO_FQCN_MISMATCH in codes
    )


def test_operation_not_invoked_reports_mutation_not_found(tmp_path):
    _write_repo(tmp_path, WRONG_OP_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(operation="upsert")], str(tmp_path)
    )
    assert _codes(result) == [DB_V2_POLICY_MUTATION_NOT_FOUND]


# ===========================================================================
# 12. Unlisted mutation
# ===========================================================================


def test_extra_body_mutation_reports_unlisted_mutation(tmp_path):
    _write_repo(tmp_path, EXTRA_MUTATION_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(result) == [DB_V2_POLICY_UNLISTED_MUTATION]


# ===========================================================================
# 13-14. Path and file gates
# ===========================================================================


def test_path_outside_approved_roots_reports_path_outside_roots(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(path="app/src/test/java/com/example/Repo.kt")], str(tmp_path)
    )
    assert _codes(result) == [DB_V2_POLICY_PATH_OUTSIDE_ROOTS]


def test_nonexistent_file_reports_file_unreadable(tmp_path):
    # The declared-root resolution needs the conventional production root
    # to exist; with it present, a policy path whose FILE is missing fails
    # as FILE_UNREADABLE.
    (tmp_path / "app" / "src" / "main" / "java").mkdir(parents=True)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert _codes(result) == [DB_V2_POLICY_FILE_UNREADABLE]


# ===========================================================================
# 15. Sibling overload isolation
# ===========================================================================


def test_sibling_overload_is_never_used_as_evidence(tmp_path):
    _write_repo(tmp_path, SIBLING_OVERLOAD_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # The targeted single-parameter overload resolves EXACTLY (no ambiguity
    # findings) but its own body holds no DAO mutation; the sibling
    # overload's body must never be borrowed as evidence and the entry
    # must never pass.
    assert _codes(result) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    assert DB_V2_POLICY_CALLABLE_MISSING not in _codes(result)
    assert DB_V2_POLICY_CALLABLE_AMBIGUOUS not in _codes(result)
    assert DB_V2_POLICY_UNLISTED_MUTATION not in _codes(result)


# ===========================================================================
# 16. Garbage input never raises
# ===========================================================================


def test_garbage_kotlin_file_returns_controlled_errors_without_raising(
    tmp_path,
):
    _write_repo(tmp_path, GARBAGE_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(result, EvidenceResult)
    codes = _codes(result)
    assert codes, "expected controlled findings for garbage input"
    for code in codes:
        assert code in KNOWN_POLICY_ERROR_CODES
    assert result.trusted is False

    # A bodyless owner declaration is parser-uncertain rather than fatal.
    _write_repo(tmp_path, BODYLESS_OWNER_SOURCE)
    uncertain = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(uncertain, EvidenceResult)
    assert _codes(uncertain) == [DB_V2_POLICY_PARSER_UNCERTAIN]


# ===========================================================================
# 17. DAO accessor ambiguity inside one callable group
# ===========================================================================


def test_two_dao_fqcns_behind_one_accessor_report_dao_ambiguous(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _entry(),
            _entry(dao_fqcn="com.example.data.LegacyGroupDao"),
        ],
        str(tmp_path),
    )
    # Both entries share one callable identity but declare different
    # daoFqcn values behind the same daoAccessor, so the accessor cannot
    # resolve to a single DAO identity and verification fails closed.
    assert _codes(result) == [DB_V2_POLICY_DAO_AMBIGUOUS]


# ===========================================================================
# 18. Top-level-function kind gate
# ===========================================================================


def test_top_level_function_kind_reports_kind_unsupported(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(kind=CallableKind.TOP_LEVEL_FUNCTION)], str(tmp_path)
    )
    # Only plain member functions are evidenced; any other kind fails
    # closed even when the owner resolves exactly once.
    assert _codes(result) == [DB_V2_POLICY_KIND_UNSUPPORTED]


# ===========================================================================
# 19. Per-member required-pair check inside one callable group
# ===========================================================================


def test_second_group_member_missing_mutation_reported(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
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
    assert _codes(result) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(result)
    assert context.get("operation") == "delete"
    assert context.get("dao_accessor") == "groupDao"


# ===========================================================================
# 20. Cross-method DAO alias isolation
# ===========================================================================


def test_cross_method_dao_alias_does_not_authorize(tmp_path):
    _write_repo(tmp_path, CROSS_METHOD_ALIAS_SOURCE)
    result = verify_v2_policy_source_evidence(
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
    assert _codes(result) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(result)
    assert context.get("dao_accessor") == "scopedDao"
    assert context.get("operation") == "insert"


# ===========================================================================
# 21. Unterminated block comment never raises
# ===========================================================================


def test_malformed_kotlin_reports_parser_uncertain_not_raise(tmp_path):
    _write_repo(tmp_path, UNTERMINATED_COMMENT_SOURCE)
    # The unterminated /* makes mask_kotlin_source() fail closed with
    # ParserError("MALFORMED_SOURCE"); the per-group guard converts that
    # into one controlled PARSER_UNCERTAIN finding instead of propagating.
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert isinstance(result, EvidenceResult)
    assert _codes(result) == [DB_V2_POLICY_PARSER_UNCERTAIN]
    # Context stays bounded: relative path plus exception class name only.
    context = _first_context(result)
    assert context.get("exc_type") == "ParserError"
    assert set(context.keys()) <= {"path", "exc_type"}
    assert context.get("path") == REPO_KT


# ===========================================================================
# 22. Nested-class method-local DAO alias isolation
# ===========================================================================


def test_nested_class_local_alias_cannot_authorize_outer_mutation(tmp_path):
    _write_repo(tmp_path, NESTED_CLASS_ALIAS_SOURCE)
    # Happy path: removeItem's dao.delete(item) resolves through the
    # class-body property alias (dao -> expenseDao, the Room-accessor
    # identity DAO_PROPERTY_DECL derives from ExpenseDao), so the honest
    # entry verifies trusted.  Without excluding nested-owner callable
    # spans, Cache's method-local `val dao = database.otherDao()` would
    # overwrite that property mapping via LOCAL_DAO_ASSIGN and this entry
    # would fail closed instead.
    result = verify_v2_policy_source_evidence(
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
    assert result.trusted is True
    assert _codes(result) == []
    assert result.groups[0].mutation_keys == ("expenseDao|delete",)

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
    assert _codes(forged) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(forged)
    assert context.get("dao_accessor") == "otherDao"
    assert context.get("operation") == "clear"


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
    single java tuple — authorizes the path and verification passes trusted
    with zero diagnostics.
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
    result = verify_v2_policy_source_evidence(
        [_entry(path=_KOTLIN_REPO_KT)], str(tmp_path)
    )
    assert result.trusted is True
    assert _codes(result) == []


# ===========================================================================
# 24-31. barrierMode metadata local consistency (PR-GR-06 Slice 1)
# ===========================================================================


def test_direct_mode_without_local_barrier_is_metadata_inconsistent(tmp_path):
    _write_repo(tmp_path, NO_BARRIER_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # The mutation evidence itself is exact, but the direct claim has no
    # local direct-barrier syntax before the mutation: exactly one
    # controlled diagnostic marks the GROUP untrusted.
    assert _codes(result) == [DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT]
    assert result.trusted is False
    assert len(result.groups) == 1
    assert result.groups[0].trusted is False
    assert _first_context(result).get("method") == "insertGroup"
    # The evidenced mutation keys are still reported for triage.
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


def test_helper_mode_carries_no_local_barrier_requirement(tmp_path):
    _write_repo(tmp_path, NO_BARRIER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(barrier_mode=BarrierMode.HELPER)], str(tmp_path)
    )
    # Mediation proof for helper mode is a later slice; locally there is
    # no requirement, so the absence of direct-barrier syntax cannot make
    # the group untrusted here.
    assert result.trusted is True
    assert _codes(result) == []


def test_worker_mediated_mode_carries_no_local_barrier_requirement(tmp_path):
    _write_repo(tmp_path, NO_BARRIER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(barrier_mode=BarrierMode.WORKER_MEDIATED)], str(tmp_path)
    )
    assert result.trusted is True
    assert _codes(result) == []


def test_barrier_after_mutation_never_satisfies_direct(tmp_path):
    _write_repo(tmp_path, BARRIER_AFTER_MUTATION_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # Only lines STRICTLY before the mutation are inspected: a barrier
    # placed after the mutation is no evidence for it.
    assert _codes(result) == [DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT]
    assert result.trusted is False


def test_barrier_inside_comment_never_satisfies_direct(tmp_path):
    _write_repo(tmp_path, BARRIER_IN_COMMENT_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # Barrier evidence is checked on statefully masked lines, so a fake
    # call inside a line comment can never satisfy the direct claim.
    assert _codes(result) == [DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT]
    assert result.trusted is False


def test_qualified_receiver_barrier_never_satisfies_direct(tmp_path):
    _write_repo(tmp_path, QUALIFIED_BARRIER_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # Only the standalone unqualified writeBarrier receiver counts; a
    # similarly-named member of another object is not barrier evidence.
    assert _codes(result) == [DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT]
    assert result.trusted is False


def test_direct_requires_proof_before_every_mutation(tmp_path):
    _write_repo(tmp_path, TWO_MUTATIONS_PARTIAL_BARRIER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _entry(operation="insert"),
            _entry(operation="delete"),
        ],
        str(tmp_path),
    )
    # Both mutations are listed and evidenced, but the first mutation has
    # no barrier before it (the single call sits between the mutations):
    # per-mutation proof fails and the group is untrusted.
    assert _codes(result) == [DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT]
    assert result.trusted is False
    assert result.groups[0].mutation_keys == (
        "groupDao|delete",
        "groupDao|insert",
    )


def test_barrier_before_every_mutation_verifies_direct_group(tmp_path):
    _write_repo(tmp_path, TWO_MUTATIONS_FULL_BARRIER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _entry(operation="insert"),
            _entry(operation="delete"),
        ],
        str(tmp_path),
    )
    assert result.trusted is True
    assert _codes(result) == []
    assert result.groups[0].trusted is True
    assert result.groups[0].mutation_keys == (
        "groupDao|delete",
        "groupDao|insert",
    )
    assert len(result.groups[0].policy_keys) == 2
    assert result.mutation_key_count == 2
    assert result.policy_mutation_key_count == 2


# ===========================================================================
# 32. EvidenceResult shape and determinism
# ===========================================================================


def test_result_groups_sorted_and_to_dict_deterministic(tmp_path):
    _write_repo(tmp_path, TWO_METHODS_SOURCE)
    entries = [
        _entry(method="removeGroup", operation="delete"),
        _entry(operation="insert"),
    ]
    result = verify_v2_policy_source_evidence(entries, str(tmp_path))
    assert result.trusted is True
    canonical_keys = [g.callable_key_canonical for g in result.groups]
    assert canonical_keys == sorted(canonical_keys)
    assert len(result.groups) == 2

    first = result.to_dict()
    second = verify_v2_policy_source_evidence(entries, str(tmp_path)).to_dict()
    assert first == second
    serialized = json.dumps(first, sort_keys=True)
    # Repo-relative POSIX paths only: no absolute fixture path and no
    # native separators may leak into the rendering.
    assert str(tmp_path) not in serialized
    assert "\\\\" not in serialized
    assert "app/src/main/java/com/example/Repo.kt" in serialized


# ===========================================================================
# 33. Explicit source_roots parameter
# ===========================================================================


def test_explicit_source_roots_is_honored_verbatim(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    declared = SourceRootSet(
        roots=(
            SourceRoot(module=":app", source_set="main", path="app/src/main/java"),
        )
    )
    result = verify_v2_policy_source_evidence(
        [_entry()], str(tmp_path), source_roots=declared
    )
    assert result.trusted is True
    assert _codes(result) == []

    # An explicit set that does not declare the entry's root must reject
    # the path exactly like the resolved set would (fail closed, same code).
    foreign = SourceRootSet(
        roots=(
            SourceRoot(
                module=":app", source_set="main", path="app/src/main/kotlin"
            ),
        )
    )
    rejected = verify_v2_policy_source_evidence(
        [_entry()], str(tmp_path), source_roots=foreign
    )
    assert _codes(rejected) == [DB_V2_POLICY_PATH_OUTSIDE_ROOTS]


# ===========================================================================
# 34-35. Batch-level outcomes
# ===========================================================================


def test_empty_entry_list_is_vacuously_trusted():
    result = verify_v2_policy_source_evidence([], "whatever-root")
    assert isinstance(result, EvidenceResult)
    assert result.trusted is True
    assert result.groups == ()
    assert result.diagnostics == ()
    assert result.mutation_key_count == 0
    assert result.policy_mutation_key_count == 0


def test_unresolvable_source_roots_fail_closed_as_one_batch_diagnostic(
    tmp_path,
):
    # No manifest and no conventional production root exists, so no path
    # can be authorized: exactly one bounded batch-level diagnostic, no
    # per-group results, untrusted.
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert result.trusted is False
    assert result.groups == ()
    assert len(result.diagnostics) == 1
    diagnostic = result.diagnostics[0]
    assert diagnostic.code == DB_V2_POLICY_PARSER_UNCERTAIN
    assert diagnostic.context_dict.get("reason") == "source-roots-unresolved"
    assert result.mutation_key_count == 0
    assert result.policy_mutation_key_count == 1


# ===========================================================================
# 36. EvidenceDiagnostic fail-closed construction
# ===========================================================================


def test_evidence_diagnostic_rejects_unknown_code_and_sorts_context():
    try:
        EvidenceDiagnostic(code="TOTALLY_UNKNOWN_CODE", context=(("a", 1),))
    except ValueError:
        pass
    else:
        raise AssertionError("unknown diagnostic code must fail closed")

    diagnostic = EvidenceDiagnostic.from_policy_error(
        PolicyError(DB_V2_POLICY_PARSER_UNCERTAIN, {"path": "a.kt", "b": 2})
    )
    assert diagnostic.context == (("b", 2), ("path", "a.kt"))
    assert diagnostic.to_dict() == {
        "code": DB_V2_POLICY_PARSER_UNCERTAIN,
        "context": {"b": 2, "path": "a.kt"},
    }


# ===========================================================================
# PR-GR-06 Slice 2 — Step-1 NEGATIVE CONTRACT LOCK
#
# Plan Step 1 mandates proof that v2 evidence FAILS CLOSED for ten named
# negative shapes before any implementation trust.  Every shape is pinned
# exactly once: the five shapes already locked by GR-01-era tests above are
# referenced (never duplicated, never weakened); the missing shapes get one
# focused test each below.  Zero false-trust everywhere: every test asserts
# the exact controlled DB_V2_POLICY_* code and an untrusted outcome, so any
# exactness regression fails the suite.
#
#   N1  same method name, different overload (policy targets save(String),
#       the mutation lives in save(Long))
#         -> DB_V2_POLICY_PARSER_UNCERTAIN (status SIGNATURE_UNSUPPORTED);
#            sibling overload body never borrowed; ADDED below
#            (test_step1_sibling_overload_holding_mutation_is_never_trusted).
#   N2  same simple class name, different FQCN
#         -> DB_V2_POLICY_OWNER_MISSING; ADDED below
#            (test_step1_same_simple_name_foreign_fqcn_reports_owner_missing).
#   N3  nested owner vs top-level owner confusion
#         -> DB_V2_POLICY_OWNER_MISSING for the top-level claim, with the
#            exact nested FQCN as a trusted positive control proving the
#            miss is exactness, not breakage; ADDED below
#            (test_step1_nested_owner_requires_exact_nested_fqcn).
#   N4  different receiver (method hosted only as an extension on another
#       type)
#         -> DB_V2_POLICY_CALLABLE_MISSING; ADDED below
#            (test_step1_method_on_different_receiver_reports_callable_missing).
#   N5  swapped parameters -> no match
#         -> DB_V2_POLICY_PARSER_UNCERTAIN (status SIGNATURE_UNSUPPORTED);
#            ALREADY PINNED above by
#            test_wrong_ordered_parameter_types_fail_closed.
#   N6  nullability difference -> no match
#         -> DB_V2_POLICY_PARSER_UNCERTAIN (status SIGNATURE_UNSUPPORTED);
#            ADDED below
#            (test_step1_nullability_difference_never_matches_signature).
#   N7  correct callable but wrong DAO accessor
#         -> DB_V2_POLICY_MUTATION_NOT_FOUND; ALREADY PINNED above by
#            test_unresolvable_dao_accessor_reports_mutation_not_found.
#   N8  correct accessor but wrong DAO FQCN -> DAO_FQCN_MISMATCH semantics:
#         CLOSED for the inventory-provided path (Plan Step-1 #8): with a
#         Room inventory, a correctly-evidenced accessor whose declared
#         daoFqcn is absent from the accessor's inventory FQCN set fails
#         closed with DB_V2_POLICY_DAO_FQCN_MISMATCH -- pinned below by
#         test_inventory_matching_dao_fqcn_verifies_trusted,
#         test_inventory_swapped_dao_fqcn_reports_fqcn_mismatch, and
#         test_inventory_ambiguous_accessor_keeps_dao_ambiguous_path.
#         WITHOUT an inventory a PURE FQCN swap over an otherwise exact
#         body remains the documented limitation (bodies yield
#         accessor-scoped identities only) and still verifies clean --
#         pinned AS a limitation, never as trust, by
#         test_without_inventory_pure_fqcn_swap_remains_documented_limitation.
#   N9  correct DAO but wrong operation
#         -> DB_V2_POLICY_MUTATION_NOT_FOUND; ALREADY PINNED above by
#            test_operation_not_invoked_reports_mutation_not_found.
#   N10 sibling callable containing the only mutation -> policy callable
#       stays untrusted
#         -> DB_V2_POLICY_MUTATION_NOT_FOUND (callable itself resolves,
#            sibling body never borrowed); ALREADY PINNED above by
#            test_sibling_overload_is_never_used_as_evidence.
#
# Matrix gaps:
#   G1  duplicate callable declaration ambiguity -> DB_V2_POLICY_CALLABLE_
#       AMBIGUOUS; ALREADY PINNED above by
#       test_identical_signature_overloads_report_callable_ambiguous.
#   G2  candidate path outside declared root -> DB_V2_POLICY_PATH_OUTSIDE_
#       ROOTS; ALREADY PINNED above by
#       test_path_outside_approved_roots_reports_path_outside_roots and the
#       explicit-root rejection arm of
#       test_explicit_source_roots_is_honored_verbatim.
#   G3  source symlink rejection (skipped on Windows) -> root-set resolution
#       fails closed as ONE batch-level DB_V2_POLICY_PARSER_UNCERTAIN whose
#       controlled codes carry DB_SOURCE_ROOT_SYMLINK_OUTSIDE, so nothing is
#       verified through the link; ADDED below
#       (test_matrix_symlinked_declared_root_rejected_before_verification).
#   G4  deterministic ordering across two runs (to_dict equality); ALREADY
#       PINNED above by test_result_groups_sorted_and_to_dict_deterministic.
#   G5  mutation closure one-vs-many rows in one callable: many rows may not
#       inflate the distinct-mutation closure; ADDED below
#       (test_matrix_many_rows_one_distinct_mutation_closure).
#   G6  comments/strings do not count as evidence nor as unlisted findings;
#       ADDED below
#       (test_matrix_comment_and_string_mutation_text_never_counts).
#   G7  complex/safe (non-DAO) receiver claim -> controlled
#       DB_V2_POLICY_MUTATION_NOT_FOUND; ADDED below
#       (test_matrix_safe_receiver_claim_reports_mutation_not_found).
# ===========================================================================


# ---------------------------------------------------------------------------
# Step-1 negative-contract fixtures (each shape isolated and minimal)
# ---------------------------------------------------------------------------

STEP1_SIBLING_OVERLOAD_MUTATION_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun save(id: Long) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(Group(id.toInt()))
    }
}

data class Group(val id: Int)
"""

STEP1_FOREIGN_PACKAGE_OWNER_SOURCE = """\
package com.example.data

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

STEP1_NESTED_OWNER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Outer {
    class Repo(private val groupDao: GroupDao) {
        fun insertGroup(group: Group) {
            writeBarrier.checkWritesAllowed()
            groupDao.insert(group)
        }
    }
}

data class Group(val id: Int)
"""

STEP1_EXTENSION_RECEIVER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun audit() {
        groupDao.count()
    }
}

fun GroupDao.insertGroup(group: Group) {
    insert(group)
}

data class Group(val id: Int)
"""

STEP1_NULLABLE_PARAMETER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group?) {
        writeBarrier.checkWritesAllowed()
        if (group != null) {
            groupDao.insert(group)
        }
    }
}

data class Group(val id: Int)
"""

MATRIX_MASKED_MUTATION_TEXT_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        // legacyDao.delete(group.id)
        report("groupDao.delete(group)")
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

MATRIX_SAFE_RECEIVER_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(
    private val groupDao: GroupDao,
    private val auditSink: AuditSink,
) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        auditSink.record(group.label)
        groupDao.insert(group)
    }
}

class AuditSink {
    fun record(value: String) {}
}

data class Group(val id: Int, val label: String)
"""

_SINGLE_ROOT_MANIFEST = """\
schemaVersion: 1
roots:
  - module: :app
    sourceSet: main
    path: app/src/main/java
"""


# ---------------------------------------------------------------------------
# N1: same method name, different overload
# ---------------------------------------------------------------------------


def test_step1_sibling_overload_holding_mutation_is_never_trusted(tmp_path):
    _write_repo(tmp_path, STEP1_SIBLING_OVERLOAD_MUTATION_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(method="save", parameter_types=("kotlin.String",))],
        str(tmp_path),
    )
    # The policy names save(String); the ONLY save overload carrying the
    # mutation is save(Long).  resolve_callable() reports SIGNATURE_UNSUPPORTED
    # for a same-name/different-parameter overload and the verifier maps that
    # to PARSER_UNCERTAIN: the sibling overload's body is never borrowed as
    # evidence and the entry can never pass.
    assert result.trusted is False
    assert _codes(result) == [DB_V2_POLICY_PARSER_UNCERTAIN]
    context = _first_context(result)
    assert context.get("method") == "save"
    assert context.get("status") == "SIGNATURE_UNSUPPORTED"
    assert result.groups[0].mutation_keys == ()
    # The fail-closed family contract: neither a weaker MISSING finding nor
    # any borrowed-sibling MUTATION_NOT_FOUND may substitute.
    assert DB_V2_POLICY_CALLABLE_MISSING not in _codes(result)
    assert DB_V2_POLICY_CALLABLE_AMBIGUOUS not in _codes(result)
    assert DB_V2_POLICY_MUTATION_NOT_FOUND not in _codes(result)


# ---------------------------------------------------------------------------
# N2: same simple class name, different FQCN
# ---------------------------------------------------------------------------


def test_step1_same_simple_name_foreign_fqcn_reports_owner_missing(tmp_path):
    _write_repo(tmp_path, STEP1_FOREIGN_PACKAGE_OWNER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(owner_fqcn="com.example.Repo")], str(tmp_path)
    )
    # The file declares com.example.data.Repo; a same-simple-name claim for
    # com.example.Repo must never resolve by simple-name matching.
    assert result.trusted is False
    assert _codes(result) == [DB_V2_POLICY_OWNER_MISSING]
    context = _first_context(result)
    assert context.get("owner_fqcn") == "com.example.Repo"
    assert context.get("path") == REPO_KT


# ---------------------------------------------------------------------------
# N3: nested owner versus top-level owner confusion
# ---------------------------------------------------------------------------


def test_step1_nested_owner_requires_exact_nested_fqcn(tmp_path):
    _write_repo(tmp_path, STEP1_NESTED_OWNER_SOURCE)
    # Top-level FQCN claim against a nested-only declaration: OWNER_MISSING.
    missed = verify_v2_policy_source_evidence(
        [_entry(owner_fqcn="com.example.Repo")], str(tmp_path)
    )
    assert missed.trusted is False
    assert _codes(missed) == [DB_V2_POLICY_OWNER_MISSING]
    context = _first_context(missed)
    assert context.get("owner_fqcn") == "com.example.Repo"
    assert context.get("path") == REPO_KT

    # Positive control: the EXACT nested FQCN verifies trusted with zero
    # diagnostics, proving the miss above is identity exactness (nesting is
    # part of the owner FQCN) and not accidental breakage.
    nested = verify_v2_policy_source_evidence(
        [_entry(owner_fqcn="com.example.Outer.Repo")], str(tmp_path)
    )
    assert nested.trusted is True
    assert _codes(nested) == []


# ---------------------------------------------------------------------------
# N4: different receiver
# ---------------------------------------------------------------------------


def test_step1_method_on_different_receiver_reports_callable_missing(tmp_path):
    _write_repo(tmp_path, STEP1_EXTENSION_RECEIVER_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # insertGroup exists in the FILE only as an extension on GroupDao; the
    # claimed owner com.example.Repo has no such member callable, so the
    # callable is MISSING for this owner and the extension body can never
    # act as evidence.
    assert result.trusted is False
    assert _codes(result) == [DB_V2_POLICY_CALLABLE_MISSING]
    context = _first_context(result)
    assert context.get("method") == "insertGroup"
    assert context.get("path") == REPO_KT
    assert result.groups[0].mutation_keys == ()


# ---------------------------------------------------------------------------
# N6: nullability difference
# ---------------------------------------------------------------------------


def test_step1_nullability_difference_never_matches_signature(tmp_path):
    _write_repo(tmp_path, STEP1_NULLABLE_PARAMETER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(parameter_types=("com.example.Group",))], str(tmp_path)
    )
    # The declaration is insertGroup(Group?): nullability is part of the
    # exact signature, so the non-null claim matches nothing and fails
    # closed as SIGNATURE_UNSUPPORTED -> PARSER_UNCERTAIN -- never a pass,
    # and never CALLABLE_MISSING, because the name does exist.
    assert result.trusted is False
    assert _codes(result) == [DB_V2_POLICY_PARSER_UNCERTAIN]
    context = _first_context(result)
    assert context.get("status") == "SIGNATURE_UNSUPPORTED"
    assert result.groups[0].mutation_keys == ()
    assert DB_V2_POLICY_CALLABLE_MISSING not in _codes(result)


# ---------------------------------------------------------------------------
# G3: source symlink rejection (skipped on Windows)
# ---------------------------------------------------------------------------


def test_matrix_symlinked_declared_root_rejected_before_verification(tmp_path):
    if os.name == "nt":
        pytest.skip("symlink creation requires privileges on Windows")
    guards = tmp_path / "config" / "guards"
    guards.mkdir(parents=True)
    (guards / "production_source_roots.yml").write_text(
        _SINGLE_ROOT_MANIFEST, encoding="utf-8"
    )
    outside = tmp_path / "outside"
    (outside / "com" / "example").mkdir(parents=True)
    (outside / "com" / "example" / "Repo.kt").write_text(
        HAPPY_SOURCE, encoding="utf-8"
    )
    link = tmp_path / "app" / "src" / "main" / "java"
    link.parent.mkdir(parents=True)
    try:
        os.symlink(str(outside), str(link))
    except OSError:
        pytest.skip("symlink creation not permitted on this platform")
    # The declared root resolves OUTSIDE the repository through a symlink:
    # declared-root-set resolution fails closed, so NOTHING is verified --
    # not even the honest Kotlin readable behind the link.  Exactly one
    # bounded batch-level diagnostic carries the controlled symlink code.
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    assert result.trusted is False
    assert result.groups == ()
    assert len(result.diagnostics) == 1
    diagnostic = result.diagnostics[0]
    assert diagnostic.code == DB_V2_POLICY_PARSER_UNCERTAIN
    context = diagnostic.context_dict
    assert context.get("reason") == "source-roots-unresolved"
    pinned_codes = set(context.get("codes", "").split(","))
    assert DB_SOURCE_ROOT_SYMLINK_OUTSIDE in pinned_codes
    assert result.mutation_key_count == 0
    assert result.policy_mutation_key_count == 1


# ---------------------------------------------------------------------------
# G5: mutation closure one-vs-many rows in one callable
# ---------------------------------------------------------------------------


def test_matrix_many_rows_one_distinct_mutation_closure(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(), _entry()], str(tmp_path)
    )
    # Two policy rows share ONE callable identity and ONE (accessor,
    # operation) pair: the closure counts DISTINCT mutations, so the single
    # group verifies trusted with exactly one actual key and exactly one
    # distinct policy mutation key -- row count never inflates the closure.
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 1
    assert result.groups[0].trusted is True
    assert result.groups[0].mutation_keys == ("groupDao|insert",)
    assert len(result.groups[0].policy_keys) == 1
    assert result.mutation_key_count == 1
    assert result.policy_mutation_key_count == 1


# ---------------------------------------------------------------------------
# G6: comments/strings do not count
# ---------------------------------------------------------------------------


def test_matrix_comment_and_string_mutation_text_never_counts(tmp_path):
    _write_repo(tmp_path, MATRIX_MASKED_MUTATION_TEXT_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # A DAO-looking call inside a line comment and inside a string literal
    # must never become evidence nor an unlisted finding: only the real
    # groupDao.insert survives masking, so the honest entry verifies trusted
    # with exactly one mutation key.  If masking ever regresses, the fake
    # legacyDao.delete / groupDao.delete texts surface as phantom actual
    # pairs (UNLISTED_MUTATION or MUTATION_NOT_FOUND) and this test fails.
    assert result.trusted is True
    assert _codes(result) == []
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


# ---------------------------------------------------------------------------
# G7: complex/safe receiver -> controlled diagnostic
# ---------------------------------------------------------------------------


def test_matrix_safe_receiver_claim_reports_mutation_not_found(tmp_path):
    _write_repo(tmp_path, MATRIX_SAFE_RECEIVER_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(dao_accessor="auditSink", operation="record")], str(tmp_path)
    )
    # auditSink.record(...) matches the mutation grammar lexically, but the
    # receiver resolves to NO DAO identity (its type is not a *Dao and no
    # scoped alias maps it), so it never becomes evidence: claiming it fails
    # closed with MUTATION_NOT_FOUND carrying the claimed pair -- never a
    # silent pass.
    assert result.trusted is False
    assert _codes(result) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(result)
    assert context.get("dao_accessor") == "auditSink"
    assert context.get("operation") == "record"
    # The safe receiver contributed zero evidence keys; only the real DAO
    # call was seen in the body (and it is irrelevant to this failure).
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


# ===========================================================================
# 38. N8 closure (Plan Step-1 #8): inventory-backed daoFqcn cross-check
#
# With a Room inventory provided, every group member's declared daoFqcn
# must belong to the inventory FQCN set of the accessor resolved at the
# mutation site; otherwise the group fails closed with the reserved
# DB_V2_POLICY_DAO_FQCN_MISMATCH code.  Without an inventory the check
# cannot run (bodies yield accessor-scoped identities only) and the
# historical behavior stays -- pinned as a documented limitation below.
# ===========================================================================


def test_inventory_matching_dao_fqcn_verifies_trusted(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry()],
        str(tmp_path),
        room_inventory=_inventory("com.example.data.GroupDao"),
    )
    # The declared daoFqcn IS the inventory FQCN of the evidenced accessor
    # groupDao (simple name GroupDao -> Room accessor groupDao), so the
    # inventory-backed cross-check passes and the group verifies trusted.
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 1
    assert result.groups[0].trusted is True
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


def test_inventory_swapped_dao_fqcn_reports_fqcn_mismatch(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(dao_fqcn="com.example.data.LegacyGroupDao")],
        str(tmp_path),
        room_inventory=_inventory(
            "com.example.data.GroupDao",
            "com.example.data.LegacyGroupDao",
        ),
    )
    # The sharpest N8 shape: BOTH DAOs exist in the inventory, but the
    # correctly-evidenced accessor groupDao resolves ONLY to GroupDao --
    # LegacyGroupDao lives behind legacyGroupDao.  Declaring LegacyGroupDao
    # behind groupDao can no longer pass as metadata: exactly one
    # controlled DB_V2_POLICY_DAO_FQCN_MISMATCH marks the group untrusted.
    assert result.trusted is False
    assert len(result.groups) == 1
    assert result.groups[0].trusted is False
    assert _codes(result) == [DB_V2_POLICY_DAO_FQCN_MISMATCH]
    context = _first_context(result)
    assert context.get("method") == "insertGroup"
    assert context.get("dao_accessor") == "groupDao"
    assert context.get("dao_fqcn") == "com.example.data.LegacyGroupDao"
    # Bounded context only: no raw source, paths, or payloads leak.
    assert set(context.keys()) == {"method", "dao_accessor", "dao_fqcn"}
    # The honest evidence keys are still reported for triage.
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


def test_inventory_ambiguous_accessor_keeps_dao_ambiguous_path(tmp_path):
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _entry(dao_fqcn="com.example.a.GroupDao"),
            _entry(dao_fqcn="com.example.b.GroupDao"),
        ],
        str(tmp_path),
        room_inventory=_inventory(
            "com.example.a.GroupDao",
            "com.example.b.GroupDao",
        ),
    )
    # Two inventory DAOs share the simple name GroupDao across packages,
    # so the accessor groupDao maps to BOTH FQCNs.  A group claiming both
    # FQCNs behind that one accessor takes the pre-existing
    # DB_V2_POLICY_DAO_AMBIGUOUS path -- the new FQCN cross-check neither
    # preempts nor replaces it, and containment alone never disambiguates.
    assert result.trusted is False
    assert len(result.groups) == 1
    assert result.groups[0].trusted is False
    assert _codes(result) == [DB_V2_POLICY_DAO_AMBIGUOUS]


def test_without_inventory_pure_fqcn_swap_remains_documented_limitation(
    tmp_path,
):
    # NO room_inventory is provided.  Callable bodies yield accessor-scoped
    # identities only -- masking and parsing never recover reliable DAO
    # FQCNs from call sites -- so without inventory ground truth a pure
    # daoFqcn swap over an otherwise exact body is UNDETECTABLE.  This test
    # pins that unchanged no-inventory behavior AS the documented
    # limitation (see CallableGroupResult.mutation_keys and the module
    # docstring); it is a limitation record, never a trust claim.  The gap
    # itself is closed by the inventory-backed tests above.
    _write_repo(tmp_path, HAPPY_SOURCE)
    result = verify_v2_policy_source_evidence(
        [_entry(dao_fqcn="com.example.data.LegacyGroupDao")], str(tmp_path)
    )
    assert result.trusted is True
    assert _codes(result) == []
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


# ===========================================================================
# 39. GR-06 closure: tolerant type-resolution discovery in v2 evidence
#
# The tracked 48-entry candidate was generated under the parser's PR-GR-05
# tolerant semantics, but evidence verification still discovered strictly,
# so any file containing ONE unresolvable-type declaration died wholesale as
# DB_V2_POLICY_PARSER_UNCERTAIN and its exact targets could never be
# evidenced (the real-repo GR-06 gap: only 8/48 candidate keys evidenced,
# untrusted).  Discovery now threads tolerate_unresolved_types=True at every
# call site (primary discovery + nested-owner rescan); retained
# TYPE_UNRESOLVED declarations can never match or authorize because every
# evidence gate filters to RESOLVED_EXACTLY at match_mutation-grade
# resolution, and a target that is itself type-unresolved fails with the
# distinct DB_V2_POLICY_SIGNATURE_UNRESOLVED code.
# ===========================================================================


def test_unresolvable_sibling_type_no_longer_poisons_exact_target(tmp_path):
    _write_repo(tmp_path, SIBLING_TYPE_UNRESOLVED_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # audit(entry: ProjectType) is retained under TYPE_UNRESOLVED status;
    # previously strict discovery aborted on it and the whole FILE reported
    # PARSER_UNCERTAIN.  The exact insertGroup(Group) target must now
    # verify trusted -- the retained sibling can never match nor forge
    # evidence for it.
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 1
    assert result.groups[0].trusted is True
    assert result.groups[0].mutation_keys == ("groupDao|insert",)


def test_type_unresolved_target_reports_signature_unresolved(tmp_path):
    _write_repo(tmp_path, TARGET_TYPE_UNRESOLVED_SOURCE)
    result = verify_v2_policy_source_evidence([_entry()], str(tmp_path))
    # The named callable exists but its own parameter type cannot be
    # resolved exactly: one DISTINCT controlled code -- never the generic
    # PARSER_UNCERTAIN family, never CALLABLE_MISSING, never a silent pass.
    assert result.trusted is False
    assert len(result.groups) == 1
    assert result.groups[0].trusted is False
    assert _codes(result) == [DB_V2_POLICY_SIGNATURE_UNRESOLVED]
    context = _first_context(result)
    assert context.get("method") == "insertGroup"
    assert context.get("status") == "TYPE_UNRESOLVED"
    # Bounded context only: controlled constants and target name.
    assert set(context.keys()) <= {"method", "status"}
    assert result.groups[0].mutation_keys == ()
    assert DB_V2_POLICY_PARSER_UNCERTAIN not in _codes(result)
    assert DB_V2_POLICY_CALLABLE_MISSING not in _codes(result)


def test_signature_unresolved_code_registered_in_closed_set_and_catalog():
    # One controlled code registered one-to-one in BOTH closed vocabularies:
    # the PolicyError/EvidenceDiagnostic construction gate and the finding
    # catalog's diagnostic profiles (never baseline-able).
    assert DB_V2_POLICY_SIGNATURE_UNRESOLVED in KNOWN_POLICY_ERROR_CODES
    assert is_known_diagnostic(DB_V2_POLICY_SIGNATURE_UNRESOLVED)
    profile = known_diagnostic(DB_V2_POLICY_SIGNATURE_UNRESOLVED)
    assert profile is not None
    assert profile.guard == GUARD_DB_ACCESS
    assert profile.baseline_able is False
    # The evidence diagnostic layer accepts it fail-closed.
    diagnostic = EvidenceDiagnostic.from_policy_error(
        PolicyError(DB_V2_POLICY_SIGNATURE_UNRESOLVED, {"method": "m"})
    )
    assert diagnostic.code == DB_V2_POLICY_SIGNATURE_UNRESOLVED
    assert diagnostic.context_dict == {"method": "m"}


# ===========================================================================
# 40. GR-08a closure: constructor-property DAO alias in a large real-shape
#     callable body.
#
# Probe evidence (build/guard-debug/gr08a/evidence-gr08a.json): the seeded
# handleAutoAcceptInTransaction row (accessor 'dao', operation
# 'markProcessed') failed DB_V2_POLICY_MUTATION_NOT_FOUND even though
# dao.markProcessed(rawId) sits at six sites inside the callable.  The body
# was NOT truncated: sourceStatsDao mutations from lines 1186 AND 1276 were
# evidenced while dao.markRelevance at line 1185 -- one line before an
# evidenced sourceStatsDao call -- was missing.  Root cause: the class-scope
# DAO map was sliced from owner.body_start, so the constructor-parameter
# property `private val dao: RawNotificationDao` (declared in the owner
# HEADER, before the body brace) never entered the map; and because `dao`
# never matches the `\w+Dao` naming convention, every dao.* mutation was
# silently dropped from extraction.  The fix spans the class-scope slice
# from the owner declaration and resolves a policy accessor spelling
# through the same scoped map before the required-pair/unlisted/fqcn
# comparisons.
# ===========================================================================


GR08A_PIPELINE_KT = "app/src/main/java/com/example/Pipeline.kt"


def _write_pipeline(tmp_path, text):
    """Write *text* to tmp_path/app/src/main/java/com/example/Pipeline.kt."""
    path = (
        tmp_path / "app" / "src" / "main" / "java"
        / "com" / "example" / "Pipeline.kt"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return str(tmp_path)


def _gr08a_entry(**overrides):
    """Return a PolicyEntry matching GR08A_PIPELINE_SOURCE, with overrides."""
    fields = dict(
        path=GR08A_PIPELINE_KT,
        owner_fqcn="com.example.Pipeline",
        kind=CallableKind.FUNCTION,
        method="handleAutoAccept",
        receiver=None,
        parameter_types=(
            "com.example.RawNotification",
            "Long",
            "com.example.PreDbContext",
            "String?",
        ),
        dao_accessor="dao",
        dao_fqcn="com.example.data.RawNotificationDao",
        operation="markProcessed",
        barrier_mode=BarrierMode.HELPER,
        reason="GR-08a evidence unit test",
        owner="db-guard-tests",
        linked_issue="GR00-EVIDENCE-T",
    )
    fields.update(overrides)
    return PolicyEntry(**fields)


GR08A_PIPELINE_SOURCE = """\
package com.example

import com.example.data.RawNotificationDao
import com.example.data.SourceStatsDao

class Pipeline(
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val expenseDao: ExpenseDao,
    private val pendingReviewDao: PendingReviewDao,
    private val sourceStatsDao: SourceStatsDao,
    private val writeBarrier: DatabaseWriteBarrier
) {
    fun handleAutoAccept(
        notification: RawNotification,
        rawId: Long,
        context: PreDbContext,
        correlationId: String? = null
    ): String {
        val isDuplicate = hasCanonicalDuplicate(context)
        if (isDuplicate) {
            dao.markRelevance(rawId, false)
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, 0L)
            try {
                auditRunner.run { audit ->
                    eventWriter.write(
                        audit,
                        auditEvent(
                            rawId = rawId,
                            matchType = "canonical_expense_duplicate"
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logWarning(e)
            }
            dao.markProcessed(rawId)
            return "Duplicate"
        }
        val mutation = coordinator.create(
            merchant = context.merchant,
            amount = context.amount
        )
        return when (val result = mutation.value) {
            is CreateResult.Created -> {
                val expenseId = result.expenseId
                dao.markRelevance(rawId, true)
                sourceStatsDao.incrementTotalAndAccepted(notification.packageName, 0L)
                try {
                    transactionRunner.run { ctx ->
                        eventWriter.write(
                            ctx,
                            auditEvent(
                                rawId = rawId,
                                matchType = result.expenseId
                            )
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logWarning(e)
                }
                dao.markProcessed(rawId)
                "AutoAccepted"
            }
            is CreateResult.DuplicateSkipped -> {
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, 0L)
                dao.markProcessed(rawId)
                "Duplicate"
            }
            is CreateResult.ValidationFailed -> {
                logWarning(result.errors)
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, 0L)
                dao.markProcessed(rawId)
                "Duplicate"
            }
            is CreateResult.Error -> {
                throw result.exception
            }
        }
    }

    private fun hasCanonicalDuplicate(context: PreDbContext): Boolean = false

    private fun logWarning(payload: Any) {}
}

data class RawNotification(val packageName: String)

data class PreDbContext(val merchant: String, val amount: Long)
"""


def test_gr08a_constructor_dao_alias_resolves_in_large_function_body(tmp_path):
    """The seeded GR-08a shape verifies end to end (fails before the fix).

    The constructor declares the DAO behind the alias name ``dao`` -- a
    receiver that can never fall back to the ``\\w+Dao`` naming convention.
    The callable body is large and structurally dense (early-return
    duplicate branch, when-block over result types, lambdas, try/catch,
    builder-style calls) with ``dao.markProcessed`` sites in the FIRST and
    LAST branches, so a body-end truncation or a header-excluded class map
    both surface as MUTATION_NOT_FOUND.  Before the fix the dao.* rows
    failed exactly that way; now every group member's own pair is
    evidenced and the group verifies trusted.
    """
    _write_pipeline(tmp_path, GR08A_PIPELINE_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _gr08a_entry(),
            _gr08a_entry(operation="markRelevance"),
            _gr08a_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
                operation="incrementTotalAndAccepted",
            ),
            _gr08a_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
                operation="incrementTotalAndDuplicate",
            ),
        ],
        str(tmp_path),
    )
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 1
    group = result.groups[0]
    assert group.trusted is True
    assert group.diagnostics == ()
    # Actual keys carry the RESOLVED Room accessor identity of the ``dao``
    # alias; every distinct body mutation is covered by the four rows.
    assert group.mutation_keys == (
        "rawNotificationDao|markProcessed",
        "rawNotificationDao|markRelevance",
        "sourceStatsDao|incrementTotalAndAccepted",
        "sourceStatsDao|incrementTotalAndDuplicate",
    )
    assert len(group.policy_keys) == 4

    # Fail-closed preserved: an accessor with no declaration and no
    # ``\\w+Dao`` shape still reports MUTATION_NOT_FOUND with its own
    # claimed pair -- the alias bridge never turns into a blanket pass.
    forged = verify_v2_policy_source_evidence(
        [_gr08a_entry(dao_accessor="missingDao")], str(tmp_path)
    )
    assert _codes(forged) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(forged)
    assert context.get("dao_accessor") == "missingDao"
    assert context.get("operation") == "markProcessed"


GR08A_SIBLING_OWNERS_SOURCE = """\
package com.example

import com.example.data.AuditDao
import com.example.data.GroupDao

class FirstRepo(private val dao: AuditDao) {
    fun clearAudit(item: Item) {
        dao.clear(item)
    }
}

class SecondRepo(
    private val dao: GroupDao
) {
    fun insertGroup(group: Group) {
        dao.insert(group)
    }
}

data class Item(val id: Int)

data class Group(val id: Int)
"""


def test_gr08a_header_alias_is_isolated_per_owner(tmp_path):
    """A sibling owner's header alias never leaks across the slice boundary.

    Both classes declare a constructor property named ``dao`` backed by
    DIFFERENT DAO types.  SecondRepo's evidence must resolve ``dao``
    through SecondRepo's OWN header (groupDao); if the class-scope slice
    ever started before the owner declaration, FirstRepo's auditDao
    mapping would win on line order and the honest insert row would fail
    closed -- and the forged clear row would wrongly resolve.
    """
    _write_repo(tmp_path, GR08A_SIBLING_OWNERS_SOURCE)
    honest = verify_v2_policy_source_evidence(
        [
            _entry(
                owner_fqcn="com.example.SecondRepo",
                method="insertGroup",
                parameter_types=("com.example.Group",),
                dao_accessor="dao",
                dao_fqcn="com.example.data.GroupDao",
                operation="insert",
            )
        ],
        str(tmp_path),
    )
    assert honest.trusted is True
    assert _codes(honest) == []
    assert honest.groups[0].mutation_keys == ("groupDao|insert",)

    # Claiming FirstRepo's dao.clear mutation against SecondRepo fails
    # closed: the sibling header alias is out of scope here.
    forged = verify_v2_policy_source_evidence(
        [
            _entry(
                owner_fqcn="com.example.SecondRepo",
                method="insertGroup",
                parameter_types=("com.example.Group",),
                dao_accessor="dao",
                dao_fqcn="com.example.data.GroupDao",
                operation="clear",
            )
        ],
        str(tmp_path),
    )
    assert _codes(forged) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(forged)
    assert context.get("dao_accessor") == "dao"
    assert context.get("operation") == "clear"


GR08A_ALIAS_UNLISTED_SOURCE = """\
package com.example

import com.example.data.GroupDao

class AliasRepo(private val dao: GroupDao) {
    fun insertGroup(group: Group) {
        dao.insert(group)
        dao.delete(group.id)
    }
}

data class Group(val id: Int)
"""


def test_gr08a_alias_bridge_does_not_widen_unlisted_coverage(tmp_path):
    """The alias bridge closes the spelling gap only -- never coverage.

    The body's dao.delete mutation resolves to the SAME DAO identity as the
    listed dao.insert row, but no row declares ``delete``: the unlisted
    gate must still fire on the resolved identity even though the accessor
    spelling itself is listed for another operation.
    """
    _write_repo(tmp_path, GR08A_ALIAS_UNLISTED_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _entry(
                owner_fqcn="com.example.AliasRepo",
                method="insertGroup",
                parameter_types=("com.example.Group",),
                dao_accessor="dao",
                dao_fqcn="com.example.data.GroupDao",
                operation="insert",
            )
        ],
        str(tmp_path),
    )
    assert result.trusted is False
    assert len(result.groups) == 1
    assert result.groups[0].trusted is False
    assert _codes(result) == [DB_V2_POLICY_UNLISTED_MUTATION]
    context = _first_context(result)
    assert context.get("method") == "insertGroup"
    assert context.get("count") == 1
    # Both resolved mutations are reported for triage.
    assert result.groups[0].mutation_keys == (
        "groupDao|delete",
        "groupDao|insert",
    )


GR08A_INVENTORY_SOURCE = """\
package com.example

import com.example.data.GroupDao

class InventoryRepo(private val dao: GroupDao) {
    fun insertGroup(group: Group) {
        dao.insert(group)
    }
}

data class Group(val id: Int)
"""


def test_gr08a_inventory_fqcn_cross_check_resolves_alias_accessor(tmp_path):
    """The inventory daoFqcn cross-check resolves the alias spelling too.

    With a Room inventory, the accessor resolved at the mutation site is
    the derived Room accessor (groupDao) of the declared alias (dao), so a
    matching daoFqcn verifies trusted and a swapped daoFqcn fails closed
    with DAO_FQCN_MISMATCH naming the alias spelling.
    """
    _write_repo(tmp_path, GR08A_INVENTORY_SOURCE)
    entry = dict(
        owner_fqcn="com.example.InventoryRepo",
        method="insertGroup",
        parameter_types=("com.example.Group",),
        dao_accessor="dao",
    )
    matching = verify_v2_policy_source_evidence(
        [
            _entry(
                **entry,
                dao_fqcn="com.example.data.GroupDao",
                operation="insert",
            )
        ],
        str(tmp_path),
        room_inventory=_inventory("com.example.data.GroupDao"),
    )
    assert matching.trusted is True
    assert _codes(matching) == []
    assert matching.groups[0].mutation_keys == ("groupDao|insert",)

    swapped = verify_v2_policy_source_evidence(
        [
            _entry(
                **entry,
                dao_fqcn="com.example.data.LegacyGroupDao",
                operation="insert",
            )
        ],
        str(tmp_path),
        room_inventory=_inventory(
            "com.example.data.GroupDao",
            "com.example.data.LegacyGroupDao",
        ),
    )
    assert swapped.trusted is False
    assert _codes(swapped) == [DB_V2_POLICY_DAO_FQCN_MISMATCH]
    context = _first_context(swapped)
    assert context.get("dao_accessor") == "dao"
    assert context.get("dao_fqcn") == "com.example.data.LegacyGroupDao"


# ===========================================================================
# 41. GR-08b closure: the three remaining pipeline callables
#     (processInternal / insertRawNotificationIfNotDuplicate shape) verify
#     end to end, and near-miss claims stay unauthorized.
#
# Mirrors the real GR-08b rows (MIT-DB-08B): the constructor-property DAO
# alias ``dao`` plus ``sourceStatsDao``, a dense multi-branch
# processInternal-style body, and a dedupe-guarded insertOrIgnore helper.
# The GR-08a alias-bridge evidence closure adds the ``pendingReviewDao``
# constructor-property accessor: a real body mutation
# (``upsertByRawNotificationId``) with no findings-report row fails closed
# as exactly one UNLISTED_MUTATION, and its closure row verifies trusted.
# ===========================================================================


def _gr08b_entry(**overrides):
    """Return a PolicyEntry matching GR08B_PIPELINE_SOURCE, with overrides."""
    fields = dict(
        path=GR08A_PIPELINE_KT,
        owner_fqcn="com.example.Pipeline",
        kind=CallableKind.FUNCTION,
        method="processInternal",
        receiver=None,
        parameter_types=(
            "com.example.RawNotification",
            "com.example.RawNotification",
            "Boolean",
            "String?",
            "com.example.NotificationPersistenceContext?",
        ),
        dao_accessor="dao",
        dao_fqcn="com.example.data.RawNotificationDao",
        operation="markProcessed",
        barrier_mode=BarrierMode.HELPER,
        reason="GR-08b evidence unit test",
        owner="db-guard-tests",
        linked_issue="GR00-EVIDENCE-T",
    )
    fields.update(overrides)
    return PolicyEntry(**fields)


GR08B_PIPELINE_SOURCE = """\
package com.example

import com.example.data.RawNotificationDao
import com.example.data.SourceStatsDao

class Pipeline(
    private val dao: RawNotificationDao,
    private val sourceStatsDao: SourceStatsDao
) {
    fun processInternal(
        notification: RawNotification,
        storageNotification: RawNotification,
        initializeClassifier: Boolean,
        correlationId: String?,
        persistenceContext: NotificationPersistenceContext?
    ): String {
        val rawId = insertRawNotificationIfNotDuplicate(notification, storageNotification)
        if (isDuplicate) {
            sourceStatsDao.incrementTotalAndDuplicate(notification.packageName, 0L)
            dao.markRelevance(rawId, false)
            dao.markProcessed(rawId)
            return "Duplicate"
        }
        if (needsReview) {
            sourceStatsDao.insertIfNotExists(SourceStats(notification.packageName))
            sourceStatsDao.incrementTotalAndPending(notification.packageName, 0L)
            dao.markRelevance(rawId, true)
            dao.markProcessed(rawId)
            return "NeedsReview"
        }
        sourceStatsDao.incrementTotalAndAutoRejected(notification.packageName, 0L)
        dao.markRelevance(rawId, false)
        dao.markProcessed(rawId)
        return "Rejected"
    }

    private fun insertRawNotificationIfNotDuplicate(
        notification: RawNotification,
        storageNotification: RawNotification
    ): Long {
        val existingId = dao.findIdByDedupeFingerprint("fingerprint")
        if (existingId != null) {
            return existingId
        }
        return dao.insertOrIgnore(storageNotification)
    }

    private val isDuplicate = false
    private val needsReview = false
}

data class RawNotification(val packageName: String)

data class SourceStats(val packageName: String)

data class NotificationPersistenceContext(val mode: String)
"""


def test_gr08b_pipeline_rows_verify_end_to_end(tmp_path):
    """Every seeded GR-08b shape verifies against the dense pipeline body.

    The ``dao`` alias resolves through the constructor header (GR-08a
    closure), the multi-branch body carries every claimed mutation, and the
    dedupe-guarded insertOrIgnore helper resolves its own row.  The read
    ``findIdByDedupeFingerprint`` is not a mutation and never blocks.
    """
    _write_pipeline(tmp_path, GR08B_PIPELINE_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _gr08b_entry(),
            _gr08b_entry(operation="markRelevance"),
            _gr08b_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
                operation="incrementTotalAndDuplicate",
            ),
            _gr08b_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
                operation="incrementTotalAndPending",
            ),
            _gr08b_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
                operation="incrementTotalAndAutoRejected",
            ),
            _gr08b_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
                operation="insertIfNotExists",
            ),
            _gr08b_entry(
                method="insertRawNotificationIfNotDuplicate",
                parameter_types=(
                    "com.example.RawNotification",
                    "com.example.RawNotification",
                ),
                operation="insertOrIgnore",
            ),
        ],
        str(tmp_path),
    )
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 2
    assert all(group.trusted for group in result.groups)
    # Groups are sorted by canonical callable key: the insert helper's
    # name sorts before processInternal.
    insert_group = result.groups[0]
    assert insert_group.mutation_keys == ("rawNotificationDao|insertOrIgnore",)
    process_group = result.groups[1]
    assert process_group.mutation_keys == (
        "rawNotificationDao|markProcessed",
        "rawNotificationDao|markRelevance",
        "sourceStatsDao|incrementTotalAndAutoRejected",
        "sourceStatsDao|incrementTotalAndDuplicate",
        "sourceStatsDao|incrementTotalAndPending",
        "sourceStatsDao|insertIfNotExists",
    )


def test_gr08b_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    """Claiming the helper's insertOrIgnore inside processInternal fails."""
    _write_pipeline(tmp_path, GR08B_PIPELINE_SOURCE)
    forged = verify_v2_policy_source_evidence(
        [_gr08b_entry(operation="insertOrIgnore")], str(tmp_path)
    )
    assert forged.trusted is False
    assert _codes(forged) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(forged)
    assert context.get("dao_accessor") == "dao"
    assert context.get("operation") == "insertOrIgnore"


def test_gr08b_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    """Claiming markProcessed on the stats accessor fails closed."""
    _write_pipeline(tmp_path, GR08B_PIPELINE_SOURCE)
    forged = verify_v2_policy_source_evidence(
        [
            _gr08b_entry(
                dao_accessor="sourceStatsDao",
                dao_fqcn="com.example.data.SourceStatsDao",
            )
        ],
        str(tmp_path),
    )
    assert forged.trusted is False
    assert _codes(forged) == [DB_V2_POLICY_MUTATION_NOT_FOUND]
    context = _first_context(forged)
    assert context.get("dao_accessor") == "sourceStatsDao"
    assert context.get("operation") == "markProcessed"


def test_gr08b_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    """A wrong-overload claim never resolves to the real callable.

    The tolerant discovery retains the same-name declaration, but the
    mismatched parameter count never resolves to the real 5-parameter
    callable: the group fails closed with the controlled parser-uncertain
    code (status SIGNATURE_UNSUPPORTED), never a silent pass.
    """
    _write_pipeline(tmp_path, GR08B_PIPELINE_SOURCE)
    forged = verify_v2_policy_source_evidence(
        [
            _gr08b_entry(
                parameter_types=(
                    "com.example.RawNotification",
                    "com.example.RawNotification",
                    "Boolean",
                    "String?",
                )
            )
        ],
        str(tmp_path),
    )
    assert forged.trusted is False
    assert _codes(forged) == [DB_V2_POLICY_PARSER_UNCERTAIN]
    context = _first_context(forged)
    assert context.get("method") == "processInternal"
    assert context.get("status") == "SIGNATURE_UNSUPPORTED"


def test_gr08b_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    """A foreign owner never borrows the pipeline's mutations."""
    _write_pipeline(tmp_path, GR08B_PIPELINE_SOURCE)
    forged = verify_v2_policy_source_evidence(
        [_gr08b_entry(owner_fqcn="com.example.OtherPipeline")], str(tmp_path)
    )
    assert forged.trusted is False
    assert _codes(forged) == [DB_V2_POLICY_OWNER_MISSING]


# ── 41b. GR-08b evidence closure: the pendingReviewDao accessor ──────────────
#
# The real GR-08b evidence run reported exactly two DB_V2_POLICY_UNLISTED_
# MUTATION diagnostics (processInternal, handleNeedsReviewInTransaction,
# count 1 each): the GR-08a alias-bridge fix made the constructor-property
# accessor ``pendingReviewDao`` fully evidenced, surfacing the real
# ``upsertByRawNotificationId`` body mutation the findings scanner never
# reported.  These tests pin both sides of that closure on a synthetic
# fixture with the same shape.


GR08B_CLOSURE_PIPELINE_SOURCE = """\
package com.example

import com.example.data.RawNotificationDao
import com.example.data.PendingReviewDao

class Pipeline(
    private val dao: RawNotificationDao,
    private val pendingReviewDao: PendingReviewDao
) {
    fun processInternal(
        notification: RawNotification,
        storageNotification: RawNotification,
        initializeClassifier: Boolean,
        correlationId: String?,
        persistenceContext: NotificationPersistenceContext?
    ): String {
        val rawId = dao.findIdByDedupeFingerprint(notification.packageName) ?: 0L
        if (needsReview) {
            val review = Review(notification.packageName)
            val reviewId = pendingReviewDao.upsertByRawNotificationId(review)
            dao.markRelevance(rawId, true)
            dao.markProcessed(rawId)
            return "NeedsReview"
        }
        dao.markRelevance(rawId, false)
        dao.markProcessed(rawId)
        return "Processed"
    }

    private val needsReview = false
}

data class RawNotification(val packageName: String)

data class Review(val packageName: String)

data class NotificationPersistenceContext(val mode: String)
"""


def test_gr08b_closure_unlisted_pending_review_upsert_fails_closed(tmp_path):
    """The pre-closure blocker shape: a real body mutation with no row.

    With only the findings-derived rows, the alias-bridge-evidenced
    ``pendingReviewDao.upsertByRawNotificationId`` call is a real mutation
    absent from the policy: exactly one UNLISTED_MUTATION diagnostic with
    count 1 marks the group untrusted (the exact GR-08b evidence-run
    signature).
    """
    _write_pipeline(tmp_path, GR08B_CLOSURE_PIPELINE_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _gr08b_entry(),
            _gr08b_entry(operation="markRelevance"),
        ],
        str(tmp_path),
    )
    assert result.trusted is False
    assert _codes(result) == [DB_V2_POLICY_UNLISTED_MUTATION]
    context = _first_context(result)
    assert context.get("method") == "processInternal"
    assert context.get("count") == 1
    # The actual set carries the unlisted accessor-scoped key.
    assert result.groups[0].mutation_keys == (
        "pendingReviewDao|upsertByRawNotificationId",
        "rawNotificationDao|markProcessed",
        "rawNotificationDao|markRelevance",
    )


def test_gr08b_closure_row_verifies_pending_review_upsert(tmp_path):
    """Adding the closure row closes the group: trusted, key evidenced.

    The closure row spells the source property accessor ``pendingReviewDao``
    exactly like the real GR-08b closure seed rows; the required pair
    resolves through the same scoped map (constructor header included) and
    the group verifies with zero diagnostics.
    """
    _write_pipeline(tmp_path, GR08B_CLOSURE_PIPELINE_SOURCE)
    result = verify_v2_policy_source_evidence(
        [
            _gr08b_entry(),
            _gr08b_entry(operation="markRelevance"),
            _gr08b_entry(
                dao_accessor="pendingReviewDao",
                dao_fqcn="com.example.data.PendingReviewDao",
                operation="upsertByRawNotificationId",
            ),
        ],
        str(tmp_path),
    )
    assert result.trusted is True
    assert _codes(result) == []
    assert len(result.groups) == 1
    group = result.groups[0]
    assert group.trusted is True
    assert group.mutation_keys == (
        "pendingReviewDao|upsertByRawNotificationId",
        "rawNotificationDao|markProcessed",
        "rawNotificationDao|markRelevance",
    )
    assert group.policy_keys == (
        "app/src/main/java/com/example/Pipeline.kt"
        "|com.example.Pipeline|function|processInternal|null"
        "|com.example.RawNotification,com.example.RawNotification,Boolean,"
        "String?,com.example.NotificationPersistenceContext?"
        "|dao|com.example.data.RawNotificationDao|markProcessed",
        "app/src/main/java/com/example/Pipeline.kt"
        "|com.example.Pipeline|function|processInternal|null"
        "|com.example.RawNotification,com.example.RawNotification,Boolean,"
        "String?,com.example.NotificationPersistenceContext?"
        "|dao|com.example.data.RawNotificationDao|markRelevance",
        "app/src/main/java/com/example/Pipeline.kt"
        "|com.example.Pipeline|function|processInternal|null"
        "|com.example.RawNotification,com.example.RawNotification,Boolean,"
        "String?,com.example.NotificationPersistenceContext?"
        "|pendingReviewDao|com.example.data.PendingReviewDao"
        "|upsertByRawNotificationId",
    )
