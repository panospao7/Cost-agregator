"""GR-14b Contract v2 tests: synchronous transparent scopes.

Positives prove the three real lambda-wall shapes (BudgetRepository
deleteBudget / ReceiptLifecycleCoordinator deleteReceipt / CategoryRepository
deleteCategory) end-to-end through parse -> admission -> CFG -> dominance.
Adversarial tests pin the fail-closed behavior: a non-contract receiver, a
missing exact import, a mismatched label, a launch inside the callable, and
a conditional barrier check all stay unproven — never silently authorized.
"""
from __future__ import annotations

import pytest

from scripts.kotlin_callable_parser import mask_kotlin_source

from scripts.db_guard.structural_analysis.barrier_markers import (
    collect_barrier_markers,
)
from scripts.db_guard.structural_analysis.barrier_proof import (
    CANONICAL_BARRIER_CONTRACT_V1,
    CANONICAL_BARRIER_CONTRACT_V2,
    CanonicalBarrierContract,
    ProofStatus,
    ReceiverTypeResolver,
    TransparentScopeWrapper,
    admit_transparent_scope_candidates,
    prove_direct_barrier,
)
from scripts.db_guard.structural_analysis.cfg import build_callable_cfg
from scripts.db_guard.structural_analysis.model import SourceSpan
from scripts.db_guard.structural_analysis.tokenizer import (
    RegionKind,
    _match_forward,
    parse_callable_body,
)

_FILE_HEADER = (
    "package com.yourname.expensetracker.data.repository\n"
    "import com.yourname.expensetracker.data.database.AppDatabase\n"
    "import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner\n"
    "import kotlinx.coroutines.withContext\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class Repo(\n"
    "    private val database: AppDatabase,\n"
    "    private val runner: DomainTransactionRunner,\n"
    "    private val writeBarrier: DatabaseWriteBarrier,\n"
    ") {\n"
)
_FOOTER = "\n}\n"


def _fun_body_span(source: str) -> SourceSpan:
    marker = source.index("fun m(")
    open_brace = source.index("{", marker)
    close = _match_forward(source, open_brace, len(source))
    assert close > 0
    return SourceSpan(
        start=open_brace + 1,
        end=close - 1,
        line=source.count("\n", 0, open_brace) + 1,
        column=1,
    )


def _mutation_sites(source: str, needles):
    sites = []
    for needle, operation in needles:
        start = source.index(needle)
        sites.append(
            type(
                "MS",
                (),
                {
                    "span": SourceSpan(
                        start=start,
                        end=start + len(needle),
                        line=source.count("\n", 0, start) + 1,
                        column=1,
                    ),
                    "callable_key": "p|o|function|m|null|",
                    "dao_fqcn": "example.Dao",
                    "operation": operation,
                    "mutation_kind": "ROOM_ABSTRACT_DELETE",
                    "source_identity": "example.Dao::" + operation,
                },
            )()
        )
    return tuple(sites)


def _transparent_candidates(parse):
    found: list[tuple[str | None, str | None]] = []

    def walk(regions):
        for region in regions:
            if region.kind is RegionKind.TRANSPARENT_SCOPE:
                found.append((region.scope_method, region.scope_receiver))
            walk(region.children)

    walk(parse.regions)
    return found


def _top_level_return_shape(parse):
    """(has_top_return_with_transparent_child, top_region_kind) for the
    first RETURN region found, or (False, None)."""
    for region in parse.regions:
        if region.kind is RegionKind.RETURN and region.children:
            child = region.children[0]
            return child.kind is RegionKind.TRANSPARENT_SCOPE, region.kind
        if region.kind is RegionKind.RETURN:
            return False, region.kind
    return False, None


def prove_v2(source: str, needles):
    """Full v2 pipeline for one mutation set. Returns (results, parse)."""
    masked = mask_kotlin_source(source)
    assert len(masked) == len(source)
    body_span = _fun_body_span(source)
    parse_result = parse_callable_body(
        masked,
        body_span,
        transparent_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods,
    )
    assert parse_result.is_supported, [u.reason for u in parse_result.unsupported]
    sites = _mutation_sites(source, needles)
    markers = collect_barrier_markers(parse_result, masked)
    resolver = ReceiverTypeResolver(masked)
    admitted = admit_transparent_scope_candidates(
        parse_result, CANONICAL_BARRIER_CONTRACT_V2, resolver
    )
    cfg, _ = build_callable_cfg(
        parse_result,
        tuple(
            type(
                "MS2",
                (),
                {
                    "span": site.span,
                    "callable_key": "p|o|function|m|null|",
                    "dao_fqcn": "example.Dao",
                    "operation": site.operation,
                    "mutation_kind": "ROOM_ABSTRACT_DELETE",
                    "source_identity": "example.Dao::" + site.operation,
                },
            )()
            for site in sites
        ),
        markers,
        path="app/src/main/java/Repo.kt",
        callable_key="p|o|function|m|null|",
        admitted_transparent_spans=admitted,
    )
    results, _ = prove_direct_barrier(
        masked,
        body_span,
        cfg,
        sites,
        CANONICAL_BARRIER_CONTRACT_V2,
        resolver,
        path="app/src/main/java/Repo.kt",
        callable_key="p|o|function|m|null|",
    )
    return results, parse_result


# ── Contract pins ────────────────────────────────────────────────────────────


def test_contract_v2_pins_documented_wrappers():
    """The v2 wrapper table must exactly match
    docs/ci/db-structural/GR-12_CANONICAL_BARRIER_API.md: any change requires
    a dedicated reviewed diff of BOTH the contract class and that doc."""
    assert CANONICAL_BARRIER_CONTRACT_V2.contract_version == 2
    assert CANONICAL_BARRIER_CONTRACT_V2.receiver_fqcn == (
        "com.yourname.expensetracker.data.backup.DatabaseWriteBarrier"
    )
    assert CANONICAL_BARRIER_CONTRACT_V2.direct_check_methods == ("checkWritesAllowed",)
    assert CANONICAL_BARRIER_CONTRACT_V2.guarded_scope_methods == ("runWrite",)
    wrappers = {
        w.method: w for w in CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_wrappers
    }
    assert set(wrappers) == {"withTransaction", "runInTransaction", "withContext"}
    assert wrappers["withTransaction"].receiver_fqcns == (
        "androidx.room.RoomDatabase",
        "com.yourname.expensetracker.data.database.AppDatabase",
    )
    assert wrappers["withTransaction"].import_fqcn is None
    assert wrappers["runInTransaction"].receiver_fqcns == (
        "com.yourname.expensetracker.domain.transaction.DomainTransactionRunner",
    )
    assert wrappers["runInTransaction"].import_fqcn is None
    assert wrappers["withContext"].receiver_fqcns == ()
    assert wrappers["withContext"].import_fqcn == "kotlinx.coroutines.withContext"


def test_contract_v1_unchanged_and_methods_disjoint():
    assert CANONICAL_BARRIER_CONTRACT_V1.contract_version == 1
    assert CANONICAL_BARRIER_CONTRACT_V1.transparent_scope_methods == ()
    assert "runWrite" not in CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_by_method
    overlap = set(CANONICAL_BARRIER_CONTRACT_V2.direct_check_methods) | set(
        CANONICAL_BARRIER_CONTRACT_V2.guarded_scope_methods
    )
    assert not overlap & set(CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods)


def test_wrapper_validation_fails_closed():
    with pytest.raises(ValueError):
        TransparentScopeWrapper("x", (), None)  # receiverless without import
    with pytest.raises(ValueError):
        TransparentScopeWrapper("x", ("nodot",), None)  # bare receiver name
    with pytest.raises(ValueError):
        TransparentScopeWrapper("x", ("a.B",), "c.D")  # both receiver and import
    with pytest.raises(ValueError):
        # a wrapper method cannot double as a check or guarded scope
        CanonicalBarrierContract(
            contract_version=2,
            receiver_fqcn="com.example.DbWriteBarrier",
            direct_check_methods=("checkWritesAllowed",),
            guarded_scope_methods=("runWrite",),
            transparent_scope_wrappers=(
                TransparentScopeWrapper("checkWritesAllowed", ("a.B",), None),
            ),
        )


# ── Positives: the three real lambda-wall shapes ────────────────────────────


def test_with_transaction_scope_is_proven():
    """BudgetRepository.deleteBudget shape: check -> database.withTransaction
    { two mutations }.  The check dominates the admitted scope entry, so it
    dominates both mutations inside."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(budget: Int, forecastId: Int): Int {
        writeBarrier.checkWritesAllowed("Repo.m")
        database.withTransaction {
            budgetDao.delete(budget)
            budgetForecastDao.deleteForecastsForBudget(forecastId)
        }
        return 1
    }
"""
        + _FOOTER
    )
    results, parse = prove_v2(
        source,
        (("budgetDao.delete", "delete"), ("budgetForecastDao.deleteForecastsForBudget", "deleteForecastsForBudget")),
    )
    assert [r.status for r in results] == [ProofStatus.PROVEN, ProofStatus.PROVEN]
    assert all(r.barrier_form == "DIRECT_CHECK" for r in results)
    assert _transparent_candidates(parse) == [("withTransaction", "database")]


def test_run_in_transaction_scope_is_proven():
    """ReceiptLifecycleCoordinator.deleteReceipt shape: check inside try,
    mutations inside runner.runInTransaction(...) { ... }."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(receiptId: Int): Int {
        try {
            writeBarrier.checkWritesAllowed("Repo.m")
        } catch (e: Exception) {
            return 0
        }
        return try {
            runner.runInTransaction(
                correlationId = java.util.UUID.randomUUID().toString(),
                operationId = "receipt.delete"
            ) { context ->
                scannedReceiptDao.delete(receiptId)
                receiptEventDao.insert(receiptId)
            }
            1
        } catch (e: Exception) {
            2
        }
    }
"""
        + _FOOTER
    )
    results, parse = prove_v2(
        source,
        (("scannedReceiptDao.delete", "delete"), ("receiptEventDao.insert", "insert")),
    )
    assert [r.status for r in results] == [ProofStatus.PROVEN, ProofStatus.PROVEN]
    assert _transparent_candidates(parse) == [("runInTransaction", "runner")]


def test_return_with_context_labelled_returns_is_proven():
    """CategoryRepository.deleteCategory shape: check -> return
    withContext(IO) { ... return@withContext ... mutation ... }.  The proof
    is now contract-backed: RETURN wraps an admitted TRANSPARENT_SCOPE child
    (never the historical leaf-RETURN inlining), and the labelled returns
    are lambda-local edges to the scope continuation."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(categoryId: Int): Int {
        writeBarrier.checkWritesAllowed("Repo.m")
        return withContext(Dispatchers.IO) {
            val category = categoryDao.getById(categoryId)
                ?: return@withContext 0
            if (category.isDefault) {
                return@withContext 1
            }
            categoryDao.delete(category)
            2
        }
    }
"""
        + _FOOTER
    )
    results, parse = prove_v2(source, (("categoryDao.delete", "delete"),))
    assert [r.status for r in results] == [ProofStatus.PROVEN]
    assert results[0].barrier_form == "DIRECT_CHECK"
    has_explicit_child, _ = _top_level_return_shape(parse)
    assert has_explicit_child, "return withContext must parse as RETURN wrapping a TRANSPARENT_SCOPE"


def test_return_with_context_without_labels_is_proven():
    """addCategory/mergeCategories shape: the accidental v1 return-leaf
    proof becomes an explicit contract-backed RETURN(TRANSPARENT_SCOPE)."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(name: String): Int {
        writeBarrier.checkWritesAllowed("Repo.m")
        return withContext(Dispatchers.IO) {
            val id = categoryDao.insert(name)
            categoryDao.touch(id)
            id
        }
    }
"""
        + _FOOTER
    )
    results, parse = prove_v2(source, (("categoryDao.insert", "insert"),))
    assert [r.status for r in results] == [ProofStatus.PROVEN]
    has_explicit_child, _ = _top_level_return_shape(parse)
    assert has_explicit_child


# ── Adversarial: fail closed ────────────────────────────────────────────────


def test_non_contract_receiver_stays_unsupported():
    """``database`` resolving to a non-contract FQCN (no AppDatabase import
    in the fixture) means the candidate is never admitted: the mutation is
    UNSUPPORTED, never PROVEN."""
    source = (
        "package com.yourname.expensetracker.data.repository\n"
        "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
        "class Repo(private val database: AppDatabase, private val writeBarrier: DatabaseWriteBarrier) {\n"
        "    suspend fun m(x: Int): Int {\n"
        '        writeBarrier.checkWritesAllowed("Repo.m")\n'
        "        database.withTransaction {\n"
        "            budgetDao.delete(x)\n"
        "        }\n"
        "        return 1\n"
        "    }\n"
        "}\n"
    )
    masked = mask_kotlin_source(source)
    body_span = _fun_body_span(source)
    parse_result = parse_callable_body(
        masked,
        body_span,
        transparent_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods,
    )
    assert parse_result.is_supported
    resolver = ReceiverTypeResolver(masked)
    admitted = admit_transparent_scope_candidates(
        parse_result, CANONICAL_BARRIER_CONTRACT_V2, resolver
    )
    assert admitted == frozenset()
    results, _ = prove_v2(source, (("budgetDao.delete", "delete"),))
    assert results[0].status is ProofStatus.UNSUPPORTED
    assert results[0].diagnostic_code == "DB_DIRECT_BARRIER_PROOF_UNSUPPORTED"


def test_with_context_without_exact_import_stays_unsupported():
    source = (
        "package com.yourname.expensetracker.data.repository\n"
        "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
        "class Repo(private val writeBarrier: DatabaseWriteBarrier) {\n"
        "    suspend fun m(x: Int): Int {\n"
        '        writeBarrier.checkWritesAllowed("Repo.m")\n'
        "        withContext(Dispatchers.IO) {\n"
        "            categoryDao.delete(x)\n"
        "        }\n"
        "        return 1\n"
        "    }\n"
        "}\n"
    )
    results, _ = prove_v2(source, (("categoryDao.delete", "delete"),))
    assert results[0].status is ProofStatus.UNSUPPORTED


def test_wrong_label_fails_closed():
    """`return@otherLabel` inside a withContext scope: not lambda-local to
    the wrapper (different label) — the callable stays unmodelable."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(x: Int): Int {
        writeBarrier.checkWritesAllowed("Repo.m")
        withContext(Dispatchers.IO) {
            if (x > 0) {
                return@otherLabel 1
            }
            categoryDao.delete(x)
            2
        }
        return 3
    }
"""
        + _FOOTER
    )
    masked = mask_kotlin_source(source)
    body_span = _fun_body_span(source)
    parse_result = parse_callable_body(
        masked,
        body_span,
        transparent_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods,
    )
    assert not parse_result.is_supported
    reasons = [u.reason for u in parse_result.unsupported]
    assert "labelled-return" in reasons


def test_launch_inside_callable_fails_closed():
    source = (
        _FILE_HEADER
        + """    suspend fun m(x: Int): Int {
        writeBarrier.checkWritesAllowed("Repo.m")
        scope.launch {
            categoryDao.delete(x)
        }
        return 1
    }
"""
        + _FOOTER
    )
    masked = mask_kotlin_source(source)
    body_span = _fun_body_span(source)
    parse_result = parse_callable_body(
        masked,
        body_span,
        transparent_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods,
    )
    assert not parse_result.is_supported
    assert any(u.reason == "lambda-escape" for u in parse_result.unsupported)


def test_conditional_check_is_counterexample():
    """Dominance stays precise: when the canonical check only runs on one
    branch, the wired scope body has a concrete barrier-free path —
    COUNTEREXAMPLE, not PROVEN."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(x: Int, cond: Boolean): Int {
        if (cond) {
            writeBarrier.checkWritesAllowed("Repo.m")
        }
        database.withTransaction {
            budgetDao.delete(x)
        }
        return 1
    }
"""
        + _FOOTER
    )
    results, _ = prove_v2(source, (("budgetDao.delete", "delete"),))
    assert results[0].status is ProofStatus.COUNTEREXAMPLE
    assert results[0].counterexample_node_kinds


def test_v1_default_mode_keeps_strict_lambda_wall():
    """Without transparent_scope_methods (GR-11 shadow / historical mode),
    withContext statements keep failing the parse — byte-identical v1
    behavior."""
    source = (
        _FILE_HEADER
        + """    suspend fun m(x: Int): Int {
        writeBarrier.checkWritesAllowed("Repo.m")
        withContext(Dispatchers.IO) {
            categoryDao.delete(x)
        }
        return 1
    }
"""
        + _FOOTER
    )
    masked = mask_kotlin_source(source)
    body_span = _fun_body_span(source)
    parse_result = parse_callable_body(masked, body_span)
    assert not parse_result.is_supported
    assert any(u.reason == "coroutine-builder" for u in parse_result.unsupported)
