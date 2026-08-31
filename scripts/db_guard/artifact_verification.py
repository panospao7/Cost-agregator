"""Shared comparison helpers for tracked DB-policy artifact verification.

PR-GR-10b single source of truth for the round-12 (R12) tripwire
comparison contract.  Both the migrate CLI's ``--verify`` mode and the
pytest tripwire tests consume THESE implementations, so the comparison
logic can never drift apart the way the hand-edit tripwires did in
rounds R11-R13:

* candidate policy-entries section byte-exactness under the generator's
  own canonical YAML serialization;
* accounting stable-section byte-exactness under the artifact's own
  canonical JSON serialization (``schema``, ``version``,
  ``sourcePolicyPath``, ``sourcePolicySha256``, ``inputCount``,
  ``records``, ``seedRecords``);
* tree-dependent ``sourceMutations`` coverage SEMANTICS (the R12
  artifact-only contract: closed kind vocabulary, deterministic
  ordering, legacy-index consistency, count consistency) — never pinned
  by bytes because the section legitimately drifts whenever the
  production tree evolves;
* the fold-derived distribution (``resolved`` / ``unresolved`` /
  ``keeper`` counts) so pins are DERIVED from the fold engine instead of
  transcribed literals.

Privacy posture: every diagnostic produced here is a bounded structural
summary — section names, element positions, byte offsets, counts, and
controlled constants only.  Nothing ever echoes artifact content, raw
source text, paths from the compared data, or exception text.
"""

from __future__ import annotations

import json

try:
    import yaml
except ImportError:  # pragma: no cover - environment/configuration failure
    yaml = None

from .policy_v2_candidate import (
    COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
    COVERAGE_OBSERVED_BUT_UNRESOLVED,
    SOURCE_MUTATION_COVERAGE_KINDS,
)

__all__ = [
    "canonical_candidate_entries_bytes",
    "canonical_accounting_section_bytes",
    "coverage_section_problems",
    "assert_coverage_section_semantics",
    "bounded_byte_diff_summary",
    "first_differing_element",
    "derive_fold_distribution",
    "distribution_from_accounting_records",
]

#: Upper bound on collected coverage problems so a hostile/tampered
#: section cannot produce an unbounded diagnostic.  Five bounded
#: structural findings are enough to name the drift kind.
_MAX_REPORTED_COVERAGE_PROBLEMS = 5

_COVERAGE_ENTRY_FIELDS = {"kind", "legacyIndices", "operation", "path", "symbol"}

_KINDS_REQUIRING_LEGACY_INDICES = (
    COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
    COVERAGE_OBSERVED_BUT_UNRESOLVED,
)


# ── Canonical serializations (the generator's own forms) ─────────────────────


def canonical_candidate_entries_bytes(entries) -> bytes:
    """Serialize a candidate entries list with the generator's own form.

    Mirrors ``migrate_db_policy_signatures``' candidate serialization
    (``yaml.safe_dump(..., sort_keys=False, allow_unicode=False)`` with
    CRLF normalized away) so byte equality of this serialization is exact
    data equality of the policy-entries section.
    """
    return (
        yaml.safe_dump(entries, sort_keys=False, allow_unicode=False)
        .replace("\r\n", "\n")
        .encode("utf-8")
    )


def canonical_accounting_section_bytes(section) -> bytes:
    """Serialize one accounting section with the artifact's own JSON form.

    Mirrors the accounting artifact's serialization
    (``json.dumps(..., sort_keys=False, separators=(",", ":"))``) so byte
    equality of this serialization is exact data equality of the section.
    """
    return json.dumps(section, sort_keys=False, separators=(",", ":")).encode(
        "utf-8"
    )


# ── R12 coverage-section semantics ───────────────────────────────────────────


def coverage_section_problems(mutations, records) -> list:
    """Artifact-only semantics for a shipped coverage section, as problems.

    The ``sourceMutations`` section is TREE-STATE-DEPENDENT evidence (it
    observes the production tree), so it is never pinned by bytes or by
    exact per-kind counts.  Checked here (artifact-only): the closed kind
    vocabulary, the deterministic (path, symbol, operation) ordering,
    legacy-index consistency (ascending, deduped, within the artifact's
    own record index range for covered/unresolved kinds; empty for
    observation-only kinds), and count consistency (per-kind counts sum to
    the section total).

    Returns a bounded list of structural problem strings (empty when the
    section satisfies the contract).  Messages never echo field VALUES —
    a tampered kind/path/symbol is untrusted content, so diagnostics name
    the entry position and the violated rule only.

    Identity semantics (R13 278-vs-295 reconciliation): the section is
    SITE-level per the PR-GR-05 contract — one entry per observed
    caller-side DAO mutation (canonical mutation key: path + owner +
    callable + dao + operation).  The serialized entry carries NO dao
    identity, so distinct sites that share one callable and operation
    serialize identically and legitimately repeat; deliberately NO
    uniqueness assertion is made on (path, symbol, operation) tuples.
    The no-omission / no-double-count partition against the observed
    universe is pinned in-process by
    ``test_real_run_coverage_partitions_observed_universe``.
    """
    problems: list = []

    def _problem(message: str) -> None:
        if len(problems) < _MAX_REPORTED_COVERAGE_PROBLEMS:
            problems.append(message)

    if not mutations:
        _problem("coverage section must ship non-empty")
        return problems
    record_indexes = set()
    for record in records or ():
        if isinstance(record, dict):
            index = record.get("index")
            if isinstance(index, int) and not isinstance(index, bool):
                record_indexes.add(index)
    identities = []
    for position, item in enumerate(mutations):
        if not isinstance(item, dict) or set(item) != _COVERAGE_ENTRY_FIELDS:
            _problem("entry %d: unexpected field set" % position)
            continue
        if item["kind"] not in SOURCE_MUTATION_COVERAGE_KINDS:
            _problem(
                "entry %d: kind outside the closed coverage vocabulary"
                % position
            )
        path = item["path"]
        if not isinstance(path, str) or not path:
            _problem("entry %d: path must be a non-empty string" % position)
        elif "\\" in path or ":" in path or path.startswith("/"):
            _problem(
                "entry %d: path is not repository-relative POSIX" % position
            )
        elif any(
            segment in ("", ".", "..") for segment in path.split("/")
        ):
            _problem("entry %d: path has an invalid segment" % position)
        symbol = item["symbol"]
        if (
            not isinstance(symbol, str)
            or "#" not in symbol
            or len(symbol) > 200
        ):
            _problem(
                "entry %d: symbol must be a bounded Owner#callable string"
                % position
            )
        operation = item["operation"]
        if operation is not None and (
            not isinstance(operation, str) or not operation
        ):
            _problem(
                "entry %d: operation must be null or a non-empty string"
                % position
            )
        indices = item["legacyIndices"]
        if not isinstance(indices, list) or list(indices) != sorted(
            set(indices)
        ):
            _problem(
                "entry %d: legacyIndices must be ascending and unique"
                % position
            )
            indices = None
        elif item["kind"] in _KINDS_REQUIRING_LEGACY_INDICES:
            if not indices:
                _problem(
                    "entry %d: covered/unresolved kind must name legacy"
                    " indices" % position
                )
            elif not set(indices) <= record_indexes:
                _problem(
                    "entry %d: legacyIndices outside the artifact's own"
                    " record index range" % position
                )
        elif indices != []:
            # JSON decoding yields a LIST, never a tuple: compare against
            # the list form (an ``== ()`` pin can never hold here).
            _problem(
                "entry %d: observation-only kind must name no legacy"
                " indices" % position
            )
        identities.append(
            (
                path if isinstance(path, str) else "",
                symbol if isinstance(symbol, str) else "",
                (operation or "") if isinstance(operation, str) else "",
            )
        )
    # Deterministic (path, symbol, operation) ordering.  NOTE (R13
    # 278-vs-295 reconciliation): deliberately NO uniqueness assertion on
    # these tuples — site-level identity is finer than (path, symbol,
    # operation) because the entry schema carries no dao identity (tracked
    # artifact: 295 site entries -> 278 unique tuples, 17 legitimate
    # repeats across same-callable multi-DAO sites).
    if identities != sorted(identities):
        _problem("entries must be sorted by (path, symbol, operation)")
    kind_counts: dict = {}
    for item in mutations:
        if isinstance(item, dict) and "kind" in item:
            kind_counts[item["kind"]] = kind_counts.get(item["kind"], 0) + 1
    if sum(kind_counts.values()) != len(mutations):
        _problem("per-kind counts do not sum to the section total")
    return problems


def assert_coverage_section_semantics(mutations, records) -> None:
    """Assert the R12 coverage-section semantics (raises AssertionError).

    Test-facing wrapper over :func:`coverage_section_problems` so the
    tripwire tests and the ``--verify`` mode share ONE implementation of
    the semantic contract.
    """
    problems = coverage_section_problems(mutations, records)
    if problems:
        raise AssertionError("; ".join(problems))


# ── Bounded structural diff summaries ────────────────────────────────────────


def bounded_byte_diff_summary(expected: bytes, found: bytes) -> str:
    """Bounded structural first-diff summary; never echoes content.

    Names the length asymmetry or the first differing byte offset —
    enough to locate the drift, never the drifted payload itself.
    """
    if expected == found:
        return ""
    if len(expected) != len(found):
        return "serialized length expected=%d found=%d" % (
            len(expected),
            len(found),
        )
    for offset, (expected_byte, found_byte) in enumerate(zip(expected, found)):
        if expected_byte != found_byte:
            return "first difference at byte offset %d of %d" % (
                offset,
                len(expected),
            )
    return "serialized bytes differ"  # pragma: no cover - unreachable


def first_differing_element(expected, found):
    """Position of the first unequal element, or ``None`` when equal.

    For equal prefixes with a length asymmetry the shorter length is
    returned (the first position where one side has no element).
    """
    for position, (expected_item, found_item) in enumerate(zip(expected, found)):
        if expected_item != found_item:
            return position
    if len(expected) != len(found):
        return min(len(expected), len(found))
    return None


# ── Fold-derived distribution (PR-GR-10b derivation layer) ───────────────────


def derive_fold_distribution(result) -> dict:
    """``resolved``/``unresolved``/``keeper`` counts from the actual fold.

    Derived from the :class:`MigrationResult` the fold engine produced —
    never from pinned literals:

    * ``resolved`` — every distinct legacy index whose emission produced
      a canonical mutation key (kept emitters PLUS folded same-key
      indices; the Slice 4/5 ``emission_indices`` crosswalk union).  This
      is exactly the set the accounting artifact records as RESOLVED.
      When a result carries no crosswalk (direct constructions), the
      kept rows' indices are used as the fallback.
    * ``unresolved`` — the visible-debt row count.
    * ``keeper`` — distinct indices that KEEP a resolved row (the
      lowest-index emission of each fold group; one keeper can carry
      several keys of a multi-operation split).
    """
    emitting = set()
    for _key, indices in getattr(result, "emission_indices", ()) or ():
        emitting.update(indices)
    if not emitting:
        emitting = {row.index for row in result.resolved}
    keepers = {row.index for row in result.resolved}
    return {
        "resolved": len(emitting),
        "unresolved": len(result.unresolved),
        "keeper": len(keepers),
    }


def distribution_from_accounting_records(records) -> dict:
    """The same distribution triple, derived from accounting records.

    Independent second derivation over a shipped accounting artifact's
    ``records`` section (the legacy crosswalk only — seed rows are not
    legacy indices and never enter it):

    * ``resolved`` / ``unresolved`` — record outcome counts;
    * ``keeper`` — distinct lowest-index carrier per mutation key (the
      fold keeps the LOWEST-INDEX emission of each key, so the minimum
      carrying index of every key is its keeper; an index keeping
      several keys counts once).
    """
    resolved = set()
    unresolved = set()
    carrying: dict = {}
    for record in records:
        if not isinstance(record, dict):
            continue
        index = record.get("index")
        if not isinstance(index, int) or isinstance(index, bool):
            continue
        outcome = record.get("outcome")
        if outcome == "RESOLVED":
            resolved.add(index)
        elif outcome == "UNRESOLVED":
            unresolved.add(index)
        for key in record.get("mutationKeys") or ():
            carrying.setdefault(key, set()).add(index)
    keepers = {min(indexes) for indexes in carrying.values()}
    return {
        "resolved": len(resolved),
        "unresolved": len(unresolved),
        "keeper": len(keepers),
    }
