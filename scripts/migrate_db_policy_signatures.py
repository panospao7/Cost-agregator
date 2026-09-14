#!/usr/bin/env python3
"""Migrate the legacy v1 DB ownership policy into v2 candidates (PR-GR-02).

Thin CLI adapter over ``scripts.db_guard.policy_v2_candidate`` (steps 3, 4,
6, and 7 of PR-GR-02): every row-level migration decision is made by
``migrate_policy``; this adapter owns only flag handling, legacy-YAML
loading, deterministic report/candidate serialization, atomic writes, and
the exit-code table.

Since PR-GR-05 Slice 4 the adapter also owns TRACKED ARTIFACT GENERATION:
``--write-candidate --accounting-out PATH`` (alias ``--write-accounting``)
writes the candidate together with its standalone accounting artifact from
the SAME run, and ``--generate`` writes both tracked artifacts to their
canonical repository paths.  A requested pair is crosswalk-verified
against each other before any byte is written and staged temp-first so it
lands both-or-nothing.

Since PR-GR-05 Slice 5 the migration itself folds every same-metadata
re-authorization into one candidate entry (lowest-index reason text kept)
and converts genuine authorization-metadata conflicts into closed
``AUTHORIZATION_METADATA_CONFLICT`` debt rows instead of colliding
candidates; :func:`find_duplicate_mutation_keys` remains as a
defense-in-depth exit-2 guard against leaked contradictions.

Since the PR-GR-05 source-mutation coverage amendment, runs that request a
standalone accounting artifact also build the observed-mutation coverage
section (``sourceMutations``) from the Room inventory over the declared
production roots: every caller-side DAO mutation site the tree performs is
classified as covered by a resolved legacy row, matching an unresolved
row's intent, outside the legacy policy, or analyzer-input-limited.  The
section is evidence-only (it never adds candidate entries); a generation
run whose coverage cannot be assembled fails closed instead of shipping an
artifact that silently lacks it.

Since GR-08a the adapter also consumes REVIEWED EXACT SEED ROWS via
``--seed-rows PATH``: a v2-shaped YAML document loaded through the ordinary
v2 loader (full validation, within-document duplicate rejection), merged
into the generated candidate only after seed/legacy duplicate-key
rejection, and crosswalked into the accounting artifact's ``seedRecords``
section so promotion-time candidate/accounting verification stays bijective
over the full rendered candidate.  Seeds never bypass the loader, the
evidence verifier, or the promotion gates.

Since PR-GR-10b the adapter also owns ARTIFACT SYNC VERIFICATION via
``--verify``: given the tracked candidate/accounting artifacts (the
canonical repository paths by default, overridable with ``--output`` /
``--accounting-out``) it regenerates the pair IN MEMORY from the SAME
inputs (``--policy``, ``--seed-rows``) and compares — via the shared
``scripts.db_guard.artifact_verification`` helpers, the single
comparison truth consumed by the tripwire tests too:

* the candidate's policy-entries section BYTE-EXACT under the
  generator's own canonical YAML serialization (plus ``schemaVersion``);
* the accounting's stable sections BYTE-EXACT under the artifact's own
  canonical JSON serialization (``schema``, ``version``,
  ``sourcePolicyPath``, ``sourcePolicySha256``, ``inputCount``,
  ``records``, ``seedRecords``);
* the tree-dependent ``sourceMutations`` coverage section SEMANTICALLY
  (the R12 artifact-only contract — the section legitimately drifts
  whenever the production tree evolves, so it is never pinned by
  bytes), plus the pair-consistency digest of the tracked candidate
  bytes;
* the fold-derived distribution (``resolved`` / ``unresolved`` /
  ``keeper`` counts from the actual fold) against the same triple
  derived independently from the tracked accounting's records — the
  derivation layer that replaced the R12 transcribed literals.

Output is a deterministic verify report (match/mismatch per section plus
a bounded structural first-diff summary that never echoes artifact
content) on stdout, optionally persisted with ``--report``.  Exit codes:
0 verified, 1 drift, 2 infrastructure.  ``--verify`` NEVER writes the
verified artifacts — the only optional write is the report, which is
collision-checked against every input like any other artifact.

Privacy posture: reports and candidates carry identity fields, controlled
status constants, and counts only — never raw source text, absolute paths,
exception text, SQL, or user data.  Stderr diagnostics are fixed bounded
strings; stdout carries counts plus controlled status constants only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - environment/configuration failure
    yaml = None

# ``policy_v2_candidate`` uses in-package relative imports, so it must be
# imported as ``scripts.db_guard.policy_v2_candidate``.  Make the repository
# root (this file's parent directory's parent) importable first.  This uses
# the real on-disk location at import time; ``main`` re-derives the repo
# root from ``__file__`` at call time so embedders can redirect it.
_REPO_ROOT = Path(__file__).resolve().parents[1]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    build_accounting_artifact,
    build_source_mutation_coverage,
    find_duplicate_mutation_keys,
    migrate_policy,
    production_source_manifest_digest,
)
from scripts.db_guard.policy_v2_loader import (  # noqa: E402
    build_policy_entry,
    load_policy_v2,
)
from scripts.db_guard.artifact_verification import (  # noqa: E402
    bounded_byte_diff_summary,
    canonical_accounting_section_bytes,
    canonical_candidate_entries_bytes,
    coverage_section_problems,
    derive_fold_distribution,
    distribution_from_accounting_records,
    first_differing_element,
)

# Post-activation alignment (GR-07 wave 2): the default --policy input is the
# ARCHIVED legacy v1 document, not the activated v2 policy.  Migration is
# v1 -> v2 ONLY: the active ``db_ownership_policy.yml`` now IS the v2
# activation output and can never be migration input again.
DEFAULT_POLICY = "config/guards/db_ownership_policy.legacy.yml"

#: The ACTIVATED v2 policy path.  Artifact writes colliding with it are
#: refused before any analysis so a migration run can never clobber the
#: activated authorization truth.
_ACTIVE_V2_POLICY_RELPATH = "config/guards/db_ownership_policy.yml"

#: Canonical tracked artifact paths used by ``--generate`` (PR-GR-05
#: Slice 4).  Both are repository-relative POSIX and overridable per run
#: with ``--output`` / ``--accounting-out`` so tests never touch the repo.
_TRACKED_CANDIDATE_RELPATH = (
    "config/guards/db_ownership_policy.signatures.candidate.yml"
)
_TRACKED_ACCOUNTING_RELPATH = (
    "config/guards/db_ownership_policy.signatures.accounting.json"
)

_REPORT_SCHEMA = "db-policy-migration-report"
_REPORT_VERSION = 2
_V2_SCHEMA_VERSION = 2

#: PR-GR-10b ``--verify`` report identity and the accounting sections the
#: R12 contract pins BYTE-EXACT (policy-derived; the tree-dependent
#: ``sourceMutations``/``sourceTreeSha``/``candidateSha256`` evidence is
#: asserted semantically instead).
_VERIFY_REPORT_SCHEMA = "db-policy-artifact-verify-report"
_VERIFY_REPORT_VERSION = 1
_STABLE_ACCOUNTING_SCALARS = (
    "schema",
    "version",
    "sourcePolicyPath",
    "sourcePolicySha256",
    "inputCount",
)
_STABLE_ACCOUNTING_LISTS = ("records", "seedRecords")

#: Bounded fallback identifier for policies outside the repository tree;
#: absolute paths never enter reports.
_CUSTOM_POLICY_LABEL = "custom-policy"

# Fixed, bounded stderr diagnostics — never exception text, never paths.
_MSG_INPUT = "malformed or unreadable DB policy input"
_MSG_INFRASTRUCTURE = "db policy migration infrastructure failure"
_MSG_OUTPUT_REQUIRED = "--write-candidate requires --output"
_MSG_PATH_COLLISION = "output/report paths collide with the active policy or each other"
_MSG_ACCOUNTING_UNAVAILABLE = "accounting artifact could not be assembled from this run"
_MSG_MALFORMED_OUTPUT = "generated candidate failed verification"
_MSG_PAIR_MISMATCH = "candidate and accounting artifacts disagree"
#: Post-activation guard: migration is v1 -> v2 ONLY.  The message is a fixed
#: bounded string that names the archived legacy input path — never a payload.
_MSG_V2_INPUT = (
    "refusing v2-shaped policy input: migration reads the archived legacy v1"
    " policy only (config/guards/db_ownership_policy.legacy.yml)"
)
#: GR-08a seed rows: reviewed exact v2 rows merged into the generated
#: candidate.  Fixed bounded diagnostics — never seed payloads or paths.
_MSG_SEED_INVALID = "seed rows file is not a valid v2 policy document"
_MSG_SEED_DUPLICATE = (
    "seed rows duplicate a legacy-resolved candidate mutation key"
)
#: PR-GR-10b --verify: fixed bounded diagnostics for the tracked-artifact
#: comparison mode.  Never paths, never artifact content.
_MSG_VERIFY_CANDIDATE_UNREADABLE = (
    "tracked candidate artifact is missing, unreadable, or malformed"
)
_MSG_VERIFY_ACCOUNTING_UNREADABLE = (
    "tracked accounting artifact is missing, unreadable, or malformed"
)
_MSG_VERIFY_BASELINE_CONTRADICTORY = (
    "regeneration baseline is contradictory (duplicate mutation keys)"
)


class CliFailure(Exception):
    """CLI failure carrying only a fixed bounded public message."""

    def __init__(self, message: str) -> None:
        super().__init__(message)
        #: Fixed bounded diagnostic; never exception text or paths.
        self.message = message


# ── Legacy input loading ──────────────────────────────────────────────────────


def _load_legacy_entries(policy_path: Path) -> list[Any]:
    """Load the legacy v1 policy YAML and return its raw entries list.

    Only the document shape is validated here (mapping with an ``entries``
    list); every per-entry problem is surfaced later as row-level debt by
    ``migrate_policy`` instead of being rejected up front.  Any read or
    parse failure fails closed with a bounded diagnostic.

    Post-activation guard (GR-07 wave 2): a v2-shaped document
    (``schemaVersion: 2``) is refused with the controlled ``_MSG_V2_INPUT``
    diagnostic — migration is v1 -> v2 only, and the activated policy can
    never be re-ingested as migration input.
    """
    try:
        with open(policy_path, "r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
    except (OSError, UnicodeDecodeError, yaml.YAMLError):
        raise CliFailure(_MSG_INPUT)
    if not isinstance(data, dict) or not isinstance(data.get("entries"), list):
        raise CliFailure(_MSG_INPUT)
    if data.get("schemaVersion") == _V2_SCHEMA_VERSION:
        raise CliFailure(_MSG_V2_INPUT)
    return data["entries"]


# ── Reviewed seed rows (GR-08a) ───────────────────────────────────────────────


def _load_seed_entries(seed_path: Path) -> list[Any]:
    """Load REVIEWED exact v2 seed rows through the ordinary v2 loader.

    The seed file must be a full v2-shaped document
    (``{schemaVersion: 2, entries: [...]}``) so it gets EVERY ordinary
    loader guarantee: exact field set, type/enum/signature validation,
    canonical path syntax, and within-document duplicate mutation-key
    rejection.  Any loader error fails closed with the bounded
    ``_MSG_SEED_INVALID`` diagnostic plus the loader's controlled codes
    (never payloads, never paths).

    Seeds are merged into the generated candidate verbatim AFTER the
    legacy migration; a seed key colliding with a legacy-resolved key is
    rejected by :func:`_reject_seed_duplicates` before any write.
    """
    entries, errors = load_policy_v2(seed_path)
    if errors or entries is None:
        for error in errors:
            print(
                "%s %s" % (error.code, error.context), file=sys.stderr
            )
        raise CliFailure(_MSG_SEED_INVALID)
    return list(entries)


def _reject_seed_duplicates(result: Any, seed_entries: list[Any]) -> None:
    """Fail closed when a seed key collides with a legacy-resolved key.

    The legacy migration is authoritative for its own keys; a seed row may
    never re-authorize (or silently shadow) a machine-migrated entry.
    Duplicate keys WITHIN the seed document are already rejected by the
    v2 loader.  Diagnostics carry counts only.
    """
    legacy_keys = {
        row.entry.mutation_key().canonical_key() for row in result.resolved
    }
    collisions = sum(
        1
        for entry in seed_entries
        if entry.mutation_key().canonical_key() in legacy_keys
    )
    if collisions:
        print(
            "%s count=%d" % (_MSG_SEED_DUPLICATE, collisions), file=sys.stderr
        )
        raise CliFailure(_MSG_SEED_DUPLICATE)


# ── Report/candidate serialization ────────────────────────────────────────────


def _policy_identifier(policy_path: Path, repo_root: Path) -> str:
    """Return the repo-relative posix policy path, or a bounded constant."""
    try:
        return policy_path.resolve().relative_to(repo_root.resolve()).as_posix()
    except (OSError, ValueError):
        return _CUSTOM_POLICY_LABEL


def _resolved_view(row: Any) -> dict[str, Any]:
    """One report row built exclusively from v2 :class:`PolicyEntry` fields."""
    entry = row.entry
    return {
        "index": row.index,
        "path": entry.path,
        "ownerFqcn": entry.owner_fqcn,
        "kind": entry.kind.value,
        "method": entry.method,
        "receiver": entry.receiver,
        "parameterTypes": list(entry.parameter_types),
        "daoAccessor": entry.dao_accessor,
        "daoFqcn": entry.dao_fqcn,
        "operation": entry.operation,
        "barrierMode": entry.barrier_mode.value,
    }


def _unresolved_view(row: Any) -> dict[str, Any]:
    """One debt row: controlled status constant plus bounded detail only."""
    return {
        "index": row.index,
        "legacyClass": row.legacy_class,
        "legacyMethod": row.legacy_method,
        "status": row.status,
        "detail": row.detail,
    }


def _build_report_payload(
    result: Any,
    duplicates: tuple[str, ...],
    policy_identifier: str,
    accounting: dict[str, Any] | None = None,
    seed_count: int = 0,
) -> dict[str, Any]:
    """Deterministic v2 report payload; identity fields and counts only.

    ``accounting`` is an optional additive section (PR-GR-05 Slice 1): the
    serialized :class:`AccountingArtifact` tying every legacy row to its
    candidate keys or closed debt status.  When its inputs cannot be
    produced deterministically the section is omitted entirely rather than
    approximated.  ``seed_count`` (GR-08a) reports how many reviewed seed
    rows were merged into the candidate; seeds are not legacy rows and
    never enter the per-index records.
    """
    resolved = [_resolved_view(row) for row in result.resolved]
    resolved.sort(
        key=lambda item: (
            item["index"],
            item["method"],
            item["daoAccessor"],
            item["operation"],
        )
    )
    unresolved = [_unresolved_view(row) for row in result.unresolved]
    unresolved.sort(key=lambda item: (item["index"], item["status"]))
    payload = {
        "schema": _REPORT_SCHEMA,
        "version": _REPORT_VERSION,
        "policy": policy_identifier,
        "counts": {
            "input": result.input_count,
            "resolved": len(resolved),
            "unresolved": len(unresolved),
            "seeds": seed_count,
        },
        "resolved": resolved,
        "unresolved": unresolved,
        "duplicateMutationKeys": list(duplicates),
    }
    if accounting is not None:
        payload["accounting"] = accounting
    return payload


# ── Accounting wiring (PR-GR-05 Slice 1) ─────────────────────────────────────


def _sha256_bytes(data: bytes) -> str:
    """Lowercase hex sha256 of ``data``."""
    return hashlib.sha256(data).hexdigest()


def _build_accounting_section(
    result: Any,
    policy_path: Path,
    repo_root: Path,
    candidate_text: str | None,
    source_mutations: Any = (),
    seed_entries: list[Any] | None = None,
) -> dict[str, Any] | None:
    """Best-effort accounting artifact payload; ``None`` when unavailable.

    Builds the :class:`AccountingArtifact` for this batch from real input
    hashes: the legacy policy bytes, the declared production tree manifest
    digest, and — when a candidate document was produced — the exact
    candidate text.  ``source_mutations`` carries the observed-mutation
    coverage evidence (PR-GR-05); it is embedded verbatim, so callers that
    could not build it pass ``()`` and the section ships with an empty
    coverage list rather than an approximated one.  ``seed_entries``
    (GR-08a) carries the reviewed seed rows whose keys join the artifact's
    ``seedRecords`` crosswalk section.  Any failure to assemble
    a fully valid artifact is swallowed into omission (never approximation),
    so conversion semantics and exit codes are untouched.  Generation runs
    that REQUEST a standalone accounting artifact use
    :func:`_require_accounting_section` instead, which fails closed.
    """
    try:
        policy_sha256 = _sha256_bytes(policy_path.read_bytes())
        source_tree_sha = production_source_manifest_digest(repo_root)
        if source_tree_sha is None:
            return None
        candidate_sha256 = (
            _sha256_bytes(candidate_text.encode("utf-8"))
            if candidate_text is not None
            else None
        )
        artifact = build_accounting_artifact(
            result,
            [row.entry for row in result.resolved],
            source_policy_path=_policy_identifier(policy_path, repo_root),
            source_policy_sha256=policy_sha256,
            source_tree_sha=source_tree_sha,
            candidate_sha256=candidate_sha256,
            source_mutations=tuple(source_mutations),
            seed_entries=tuple(seed_entries or ()),
        )
        return artifact.to_dict()
    except Exception:
        # Accounting is additive evidence and must never change conversion
        # semantics or exit codes: ANY failure to assemble a fully valid
        # artifact (unreadable policy bytes, undecidable tree manifest,
        # contradictory batch, unexpected internal error) omits the section
        # instead of failing the migration run.  Nothing is logged here —
        # the report simply carries no "accounting" key.
        return None


def _require_accounting_section(
    result: Any,
    policy_path: Path,
    repo_root: Path,
    candidate_text: str,
    source_mutations: Any = (),
    seed_entries: list[Any] | None = None,
) -> dict[str, Any]:
    """Mandatory accounting assembly for generation runs; fails closed.

    Unlike the best-effort report section, a requested standalone
    accounting artifact MUST be producible from THIS run: any assembly
    failure raises :class:`CliFailure` so neither artifact of the pair is
    written (both-or-neither).  A ``source_mutations`` value of ``None``
    means the coverage machinery failed closed upstream; a generation run
    never ships an artifact whose coverage silently degraded, so that also
    raises :class:`CliFailure`.
    """
    if source_mutations is None:
        raise CliFailure(_MSG_ACCOUNTING_UNAVAILABLE)
    payload = _build_accounting_section(
        result,
        policy_path,
        repo_root,
        candidate_text,
        source_mutations,
        seed_entries,
    )
    if payload is None:
        raise CliFailure(_MSG_ACCOUNTING_UNAVAILABLE)
    return payload


def _verify_candidate_accounting_pair(
    candidate_text: str, accounting_payload: dict[str, Any]
) -> None:
    """Re-verify the rendered candidate against its accounting artifact.

    Both artifacts were produced from one run; before a single byte is
    written this gate re-parses the EXACT candidate bytes that will be
    written and fails closed on:

    * malformed output — unparseable YAML, wrong document shape, or any
      entry the ordinary v2 entry builder rejects;
    * duplicate mutation keys inside the rendered document;
    * candidate/accounting mismatch — the set of canonical mutation keys
      in the rendered entries differs from the union of the accounting
      records' keys (the Slice 1 crosswalk, re-verified over the written
      bytes rather than in-memory objects).
    """
    try:
        document = yaml.safe_load(candidate_text)
    except yaml.YAMLError:
        raise CliFailure(_MSG_MALFORMED_OUTPUT)
    if (
        not isinstance(document, dict)
        or document.get("schemaVersion") != _V2_SCHEMA_VERSION
        or not isinstance(document.get("entries"), list)
        or not document["entries"]
    ):
        raise CliFailure(_MSG_MALFORMED_OUTPUT)
    built_entries = []
    for position, raw_entry in enumerate(document["entries"]):
        entry, errors = build_policy_entry(raw_entry, position)
        if entry is None or errors:
            raise CliFailure(_MSG_MALFORMED_OUTPUT)
        built_entries.append(entry)
    keys = [entry.mutation_key().canonical_key() for entry in built_entries]
    if len(set(keys)) != len(keys):
        raise CliFailure(_MSG_MALFORMED_OUTPUT)
    record_keys = {
        key
        for record in accounting_payload.get("records", [])
        for key in record.get("mutationKeys", [])
    }
    # GR-08a: reviewed seed rows join the crosswalk so the rendered
    # candidate's FULL key set stays verifiable against the artifact.
    seed_records = accounting_payload.get("seedRecords", [])
    if not isinstance(seed_records, list):
        raise CliFailure(_MSG_MALFORMED_OUTPUT)
    for seed_record in seed_records:
        if not isinstance(seed_record, dict):
            raise CliFailure(_MSG_MALFORMED_OUTPUT)
        seed_key = seed_record.get("key")
        if not isinstance(seed_key, str) or not seed_key:
            raise CliFailure(_MSG_MALFORMED_OUTPUT)
        record_keys.add(seed_key)
    if set(keys) != record_keys:
        raise CliFailure(_MSG_PAIR_MISMATCH)


def _entry_document(entry: Any) -> dict[str, Any]:
    """One full v2 candidate entry (round-trips through the v2 loader)."""
    return {
        "path": entry.path,
        "ownerFqcn": entry.owner_fqcn,
        "kind": entry.kind.value,
        "method": entry.method,
        "receiver": entry.receiver,
        "parameterTypes": list(entry.parameter_types),
        "daoAccessor": entry.dao_accessor,
        "daoFqcn": entry.dao_fqcn,
        "operation": entry.operation,
        "barrierMode": entry.barrier_mode.value,
        "reason": entry.reason,
        "owner": entry.owner,
        "linkedIssue": entry.linked_issue,
    }


def _candidate_document(result: Any, seed_entries: list[Any]) -> dict[str, Any]:
    """Inert v2 candidate document with deterministically sorted entries.

    GR-08a: reviewed seed entries are merged with the legacy-resolved rows
    and the whole document is sorted by the same deterministic key, so a
    seeded candidate is byte-stable across re-runs.  Callers must reject
    seed/legacy key collisions (``_reject_seed_duplicates``) BEFORE this
    merge so a seed can never shadow a machine-migrated entry.
    """
    entries = [_entry_document(row.entry) for row in result.resolved]
    entries.extend(_entry_document(entry) for entry in seed_entries)
    entries.sort(
        key=lambda item: (
            item["path"],
            item["ownerFqcn"],
            item["method"],
            item["daoAccessor"],
            item["operation"],
        )
    )
    return {"schemaVersion": _V2_SCHEMA_VERSION, "entries": entries}


# ── Atomic writes ─────────────────────────────────────────────────────────────


def _stage_temporary(target: Path, text: str, temporary_prefix: str) -> str:
    """Stage ``text`` in a fsynced temp file beside ``target``; no replace."""
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(
        prefix=temporary_prefix, suffix=".tmp", dir=str(target.parent), text=True
    )
    try:
        # newline="\n" keeps line endings normalized on every platform.
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise
    return temporary


def _atomic_write_all(items: list[tuple[Path, str, str]]) -> None:
    """Write every ``(target, text, temp_prefix)`` item as one group.

    Every temp file is staged and fsynced FIRST; the ``os.replace`` swaps
    happen only after ALL stages succeeded, so a staging failure (bad
    target directory, unwritable path, ...) leaves every previous target
    untouched — the candidate+accounting pair lands both-or-nothing.  Each
    individual swap is atomic within its directory; a swap failure after
    an earlier swap succeeded cannot be rolled back on ordinary
    filesystems and is documented residual risk, not silently swallowed.
    """
    staged: list[tuple[str, Path]] = []
    try:
        for target, text, temporary_prefix in items:
            staged.append(
                (_stage_temporary(target, text, temporary_prefix), target)
            )
    except Exception:
        for temporary, _target in staged:
            try:
                os.unlink(temporary)
            except OSError:
                pass
        raise
    for temporary, target in staged:
        os.replace(temporary, target)


def _atomic_write_text(target: Path, text: str, temporary_prefix: str) -> None:
    """Write ``text`` atomically: temp file in the target dir + os.replace."""
    _atomic_write_all([(target, text, temporary_prefix)])


# ── Path collision guard ──────────────────────────────────────────────────────


def _validate_output_paths(
    report: str | None,
    output: str | Path | None,
    policy_path: Path,
    accounting: str | Path | None = None,
    repo_root: Path | None = None,
    seed: str | Path | None = None,
) -> None:
    """Reject artifact collisions before analysis or any write begins.

    Every requested artifact path (report, candidate output, standalone
    accounting) must differ from the migration input policy and from each
    other.  Post-activation (GR-07 wave 2) every requested artifact path
    must ALSO differ from the ACTIVATED v2 policy document under
    ``repo_root``: a migration run reads the archived legacy input and can
    never overwrite the activated authorization truth.  The GR-08a seed
    file is read-only input and must likewise differ from every requested
    artifact path so no run can overwrite its own reviewed input.
    """
    try:
        resolved_policy = policy_path.resolve()
        resolved_report = Path(report).resolve() if report else None
        resolved_output = Path(output).resolve() if output else None
        resolved_accounting = (
            Path(accounting).resolve() if accounting else None
        )
        resolved_seed = Path(seed).resolve() if seed else None
        resolved_active_v2 = (
            (Path(repo_root) / Path(*_ACTIVE_V2_POLICY_RELPATH.split("/"))).resolve()
            if repo_root is not None
            else None
        )
    except (OSError, RuntimeError):
        raise CliFailure(_MSG_PATH_COLLISION)
    named = [
        ("report", resolved_report),
        ("output", resolved_output),
        ("accounting", resolved_accounting),
    ]
    for name, resolved in named:
        if resolved is not None and resolved == resolved_policy:
            raise CliFailure(_MSG_PATH_COLLISION)
        if (
            resolved is not None
            and resolved_active_v2 is not None
            and resolved == resolved_active_v2
        ):
            raise CliFailure(_MSG_PATH_COLLISION)
    if (
        resolved_seed is not None
        and resolved_seed
        in (resolved_policy, resolved_report, resolved_output, resolved_accounting)
    ):
        raise CliFailure(_MSG_PATH_COLLISION)
    for position, (first_name, first) in enumerate(named):
        for second_name, second in named[position + 1 :]:
            if (
                first is not None
                and second is not None
                and first == second
            ):
                raise CliFailure(_MSG_PATH_COLLISION)


def _resolve_write_targets(args: Any, repo_root: Path) -> tuple[
    Path | None, Path | None
]:
    """Resolve ``(candidate_target, accounting_target)`` for this mode.

    * ``--check``           -> ``(None, None)``: nothing is ever written;
    * ``--generate``        -> both tracked artifact paths, each
      overridable with ``--output`` / ``--accounting-out``;
    * ``--write-candidate`` -> the required ``--output`` path plus the
      standalone accounting path only when ``--accounting-out``
      (alias ``--write-accounting``) was given.
    """
    if args.check:
        return None, None
    if args.generate:
        candidate = (
            Path(args.output)
            if args.output
            else repo_root / Path(*_TRACKED_CANDIDATE_RELPATH.split("/"))
        )
        accounting = (
            Path(args.accounting_out)
            if args.accounting_out
            else repo_root / Path(*_TRACKED_ACCOUNTING_RELPATH.split("/"))
        )
        return candidate, accounting
    return (
        Path(args.output),
        Path(args.accounting_out) if args.accounting_out else None,
    )


# ── Artifact sync verification (PR-GR-10b) ───────────────────────────────────


def _resolve_verify_targets(args: Any, repo_root: Path) -> tuple[Path, Path]:
    """Resolve the tracked artifact paths ``--verify`` compares against.

    Defaults are the canonical tracked artifact paths (the same ones
    ``--generate`` writes); ``--output`` / ``--accounting-out`` override
    them so tests never touch the repository.  Both are READ-ONLY
    targets: ``--verify`` never writes either path.
    """
    candidate = (
        Path(args.output)
        if args.output
        else repo_root / Path(*_TRACKED_CANDIDATE_RELPATH.split("/"))
    )
    accounting = (
        Path(args.accounting_out)
        if args.accounting_out
        else repo_root / Path(*_TRACKED_ACCOUNTING_RELPATH.split("/"))
    )
    return candidate, accounting


def _read_tracked_candidate(path: Path) -> tuple[bytes, dict[str, Any]]:
    """Read and shape-check the tracked candidate; returns (bytes, document).

    Any read, decode, parse, or document-shape failure fails closed with
    the bounded ``_MSG_VERIFY_CANDIDATE_UNREADABLE`` diagnostic (exit 2 —
    the artifact cannot serve as a comparison baseline).  A parseable
    document with a drifted ``schemaVersion`` stays comparable and is
    reported as drift instead.
    """
    try:
        data = path.read_bytes()
    except OSError:
        raise CliFailure(_MSG_VERIFY_CANDIDATE_UNREADABLE)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise CliFailure(_MSG_VERIFY_CANDIDATE_UNREADABLE)
    try:
        document = yaml.safe_load(text)
    except yaml.YAMLError:
        raise CliFailure(_MSG_VERIFY_CANDIDATE_UNREADABLE)
    if (
        not isinstance(document, dict)
        or not isinstance(document.get("entries"), list)
    ):
        raise CliFailure(_MSG_VERIFY_CANDIDATE_UNREADABLE)
    return data, document


def _load_tracked_accounting_payload(path: Path) -> dict[str, Any]:
    """Read and shape-check the tracked accounting artifact payload.

    Any read, decode, parse, or payload-shape failure fails closed with
    the bounded ``_MSG_VERIFY_ACCOUNTING_UNREADABLE`` diagnostic (exit 2).
    The ``records`` list is the minimum shape the comparison and the
    fold-distribution derivation need.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        raise CliFailure(_MSG_VERIFY_ACCOUNTING_UNREADABLE)
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        raise CliFailure(_MSG_VERIFY_ACCOUNTING_UNREADABLE)
    if (
        not isinstance(payload, dict)
        or not isinstance(payload.get("records"), list)
    ):
        raise CliFailure(_MSG_VERIFY_ACCOUNTING_UNREADABLE)
    return payload


def _bounded_scalar(value: Any) -> str:
    """Bounded repr of a compared scalar (identity fields/counts only)."""
    text = repr(value)
    if len(text) > 120:
        text = text[:117] + "..."
    return text


def _compare_candidate_document(
    tracked_document: dict[str, Any], regenerated_text: str
) -> dict[str, Any]:
    """Byte-exact candidate comparison under the generator's own form.

    The candidate document is exactly ``{schemaVersion, entries}`` (the
    R12 contract): ``schemaVersion`` is compared as a scalar and the
    policy-entries section is compared BYTE-EXACT under the shared
    canonical YAML serialization.  Details are bounded structural
    summaries (lengths, byte offsets, element positions) that never echo
    entry content.
    """
    regenerated_document = yaml.safe_load(regenerated_text)
    sections: dict[str, Any] = {}
    details: dict[str, str] = {}
    expected_version = regenerated_document.get("schemaVersion")
    found_version = tracked_document.get("schemaVersion")
    if expected_version == found_version:
        sections["schemaVersion"] = "match"
    else:
        sections["schemaVersion"] = "mismatch"
        details["schemaVersion"] = "expected %s found %s" % (
            _bounded_scalar(expected_version),
            _bounded_scalar(found_version),
        )
    expected_entries = regenerated_document["entries"]
    found_entries = tracked_document["entries"]
    expected_bytes = canonical_candidate_entries_bytes(expected_entries)
    found_bytes = canonical_candidate_entries_bytes(found_entries)
    if expected_bytes == found_bytes:
        sections["entries"] = "match"
    else:
        detail = bounded_byte_diff_summary(expected_bytes, found_bytes)
        position = first_differing_element(expected_entries, found_entries)
        if position is not None:
            detail = "%s; first differing entry at position %d" % (
                detail,
                position,
            )
        sections["entries"] = "mismatch"
        details["entries"] = detail
    payload: dict[str, Any] = {
        "sections": sections,
        "match": all(
            status == "match" for status in sections.values()
        ),
    }
    if details:
        payload["detail"] = details
    return payload


def _compare_accounting_payload(
    tracked_payload: dict[str, Any],
    regenerated_payload: dict[str, Any],
    tracked_candidate_bytes: bytes,
) -> dict[str, Any]:
    """Accounting comparison: stable sections byte-exact, evidence semantic.

    Byte-exact (policy-derived, per the R12 contract): ``schema``,
    ``version``, ``sourcePolicyPath``, ``sourcePolicySha256``,
    ``inputCount``, ``records``, ``seedRecords`` — each under the
    artifact's own canonical JSON serialization.  Semantic
    (tree-dependent evidence): the pair-consistency digest (the tracked
    accounting's ``candidateSha256`` must equal the sha256 of the tracked
    candidate FILE bytes — the pair is regenerated together), the
    well-formed tree digest, and the R12 coverage-section semantics over
    the tracked ``sourceMutations``.  Details are bounded structural
    summaries that never echo section content.
    """
    sections: dict[str, Any] = {}
    details: dict[str, str] = {}
    for field in _STABLE_ACCOUNTING_SCALARS:
        expected = regenerated_payload.get(field)
        found = tracked_payload.get(field)
        if expected == found:
            sections[field] = "match"
        else:
            sections[field] = "mismatch"
            details[field] = "expected %s found %s" % (
                _bounded_scalar(expected),
                _bounded_scalar(found),
            )
    for section in _STABLE_ACCOUNTING_LISTS:
        expected_present = section in regenerated_payload
        found_present = section in tracked_payload
        if expected_present != found_present:
            sections[section] = "mismatch"
            details[section] = "section present only in %s" % (
                "the regeneration" if expected_present else "the tracked artifact"
            )
            continue
        if not expected_present:
            # Absent from both (seedless pair): nothing to compare.
            sections[section] = "match"
            continue
        expected_bytes = canonical_accounting_section_bytes(
            regenerated_payload[section]
        )
        found_bytes = canonical_accounting_section_bytes(
            tracked_payload[section]
        )
        if expected_bytes == found_bytes:
            sections[section] = "match"
        else:
            detail = bounded_byte_diff_summary(expected_bytes, found_bytes)
            position = first_differing_element(
                regenerated_payload[section], tracked_payload[section]
            )
            if position is not None:
                detail = "%s; first differing element at position %d" % (
                    detail,
                    position,
                )
            sections[section] = "mismatch"
            details[section] = detail
    semantic: dict[str, str] = {}
    semantic_details: dict[str, str] = {}
    # Pair consistency: the tracked accounting's candidate digest must
    # match the tracked candidate FILE bytes (regenerated together).
    expected_candidate_sha = _sha256_bytes(tracked_candidate_bytes)
    found_candidate_sha = tracked_payload.get("candidateSha256")
    if expected_candidate_sha == found_candidate_sha:
        semantic["candidateSha256Pair"] = "match"
    else:
        semantic["candidateSha256Pair"] = "mismatch"
    # Tree digest: well-formed lowercase hex64 only — a tree-shape
    # fingerprint by design, never compared by value.
    tree_digests_wellformed = True
    for payload in (tracked_payload, regenerated_payload):
        digest = payload.get("sourceTreeSha")
        if not isinstance(digest, str) or len(digest) != 64:
            tree_digests_wellformed = False
        else:
            try:
                int(digest, 16)
            except ValueError:
                tree_digests_wellformed = False
    semantic["sourceTreeSha"] = (
        "match" if tree_digests_wellformed else "mismatch"
    )
    # Coverage semantics: the R12 artifact-only contract over the tracked
    # section (the regeneration's coverage is deliberately not rebuilt —
    # tree-dependent evidence is asserted semantically, never by bytes;
    # the deep partition invariant stays pinned in-process by the
    # tripwire suite).
    coverage_problems = coverage_section_problems(
        tracked_payload.get("sourceMutations"),
        tracked_payload.get("records"),
    )
    if coverage_problems:
        semantic["coverage"] = "mismatch"
        semantic_details["coverage"] = "; ".join(coverage_problems)
    else:
        semantic["coverage"] = "match"
    payload: dict[str, Any] = {
        "sections": sections,
        "semantic": semantic,
        "match": all(status == "match" for status in sections.values())
        and all(status == "match" for status in semantic.values()),
    }
    if details:
        payload["detail"] = details
    if semantic_details:
        payload["semanticDetail"] = semantic_details
    return payload


def _build_verify_report(
    tracked_candidate: dict[str, Any],
    tracked_candidate_bytes: bytes,
    tracked_accounting: dict[str, Any],
    regenerated_candidate_text: str,
    regenerated_accounting: dict[str, Any],
    result: Any,
    candidate_is_tracked: bool,
    accounting_is_tracked: bool,
) -> dict[str, Any]:
    """Deterministic verify report; identity fields and counts only.

    Sections: ``candidate`` (byte-exact entries comparison),
    ``accounting`` (byte-exact stable sections + semantic evidence), and
    ``distribution`` (the fold-derived resolved/unresolved/keeper triple
    against the same triple derived from the tracked accounting's
    records — the PR-GR-10b derivation layer).  ``match`` is the
    conjunction of every section; no timestamps, no paths, no content.
    """
    candidate_payload = _compare_candidate_document(
        tracked_candidate, regenerated_candidate_text
    )
    accounting_payload = _compare_accounting_payload(
        tracked_accounting,
        regenerated_accounting,
        tracked_candidate_bytes,
    )
    derived = derive_fold_distribution(result)
    tracked_distribution = distribution_from_accounting_records(
        tracked_accounting["records"]
    )
    distribution: dict[str, Any] = {
        "derivedFromFold": derived,
        "derivedFromTrackedRecords": tracked_distribution,
    }
    if derived == tracked_distribution:
        distribution["status"] = "match"
    else:
        distribution["status"] = "mismatch"
        distribution["detail"] = "; ".join(
            "%s expected=%d found=%d"
            % (name, derived[name], tracked_distribution[name])
            for name in ("resolved", "unresolved", "keeper")
            if derived[name] != tracked_distribution[name]
        )
    return {
        "schema": _VERIFY_REPORT_SCHEMA,
        "version": _VERIFY_REPORT_VERSION,
        "artifacts": {
            "candidate": "tracked" if candidate_is_tracked else "custom",
            "accounting": "tracked" if accounting_is_tracked else "custom",
        },
        "candidate": candidate_payload,
        "accounting": accounting_payload,
        "distribution": distribution,
        "match": (
            candidate_payload["match"]
            and accounting_payload["match"]
            and distribution["status"] == "match"
        ),
    }


def _run_verify(args: Any, repo_root: Path, policy_path: Path) -> int:
    """``--verify`` mode: tracked artifacts vs an in-memory regeneration.

    Regenerates the candidate/accounting pair IN MEMORY from the SAME
    inputs (``--policy``, ``--seed-rows``) and compares it against the
    tracked artifacts through the shared PR-GR-10b comparison helpers.
    NEVER writes either verified artifact; the only optional write is
    the verify report via ``--report`` (collision-checked against every
    input like any other artifact).  Exit codes: 0 verified, 1 drift,
    2 infrastructure (unreadable tracked artifacts, contradictory
    regeneration baseline, or any fail-closed assembly failure).
    """
    candidate_path, accounting_path = _resolve_verify_targets(args, repo_root)
    _validate_output_paths(
        args.report,
        candidate_path,
        policy_path,
        accounting_path,
        repo_root=repo_root,
        seed=args.seed_rows,
    )
    # Reviewed seed rows load FIRST (full v2 loader validation, fail
    # closed) — the verify regeneration must consume the SAME reviewed
    # input as generation or the comparison reports the drift (the R12
    # lesson: a seed-less regeneration can never match a seeded artifact).
    seed_entries: list[Any] = []
    if args.seed_rows:
        seed_entries = _load_seed_entries(Path(args.seed_rows))
    tracked_candidate_bytes, tracked_candidate = _read_tracked_candidate(
        candidate_path
    )
    tracked_accounting = _load_tracked_accounting_payload(accounting_path)
    entries = _load_legacy_entries(policy_path)
    result = migrate_policy(entries, repo_root, dao_index=None)
    _reject_seed_duplicates(result, seed_entries)
    duplicates = find_duplicate_mutation_keys(result)
    if duplicates:
        raise CliFailure(_MSG_VERIFY_BASELINE_CONTRADICTORY)
    # The candidate text is rendered in memory so the accounting payload
    # hashes the EXACT regeneration bytes; the coverage scan is
    # deliberately NOT rebuilt (tree-dependent evidence is compared
    # semantically per the R12 contract, so the stable sections — which
    # never depend on it — are the only regenerated accounting content
    # the comparison needs).
    candidate_text = yaml.safe_dump(
        _candidate_document(result, seed_entries),
        sort_keys=False,
        allow_unicode=False,
    ).replace("\r\n", "\n")
    accounting_payload = _require_accounting_section(
        result,
        policy_path,
        repo_root,
        candidate_text,
        (),
        seed_entries,
    )
    default_candidate = repo_root / Path(
        *_TRACKED_CANDIDATE_RELPATH.split("/")
    )
    default_accounting = repo_root / Path(
        *_TRACKED_ACCOUNTING_RELPATH.split("/")
    )
    report = _build_verify_report(
        tracked_candidate,
        tracked_candidate_bytes,
        tracked_accounting,
        candidate_text,
        accounting_payload,
        result,
        candidate_path == default_candidate,
        accounting_path == default_accounting,
    )
    rendered = json.dumps(report, sort_keys=False, separators=(",", ":"))
    if args.report:
        _atomic_write_text(
            Path(args.report),
            rendered + "\n",
            ".db-policy-verify-report-",
        )
    print(rendered)
    return 0 if report["match"] else 1


# ── Exit-code table ───────────────────────────────────────────────────────────


def _decide_exit(result: Any, duplicates: tuple[str, ...]) -> tuple[int, bool]:
    """Map the analysis outcome to ``(exit_code, may_write_candidate)``.

    * duplicate mutation keys      -> 2, no candidate write (defense-in-
      depth guard only: since Slice 5, genuine authorization-metadata
      conflicts surface as AUTHORIZATION_METADATA_CONFLICT debt rows and
      never reach this branch from a real migration);
    * zero resolved rows           -> 1, no candidate write;
    * unresolved debt, some solved -> 1, candidate write allowed;
    * every row resolved           -> 0, candidate write allowed.
    """
    if duplicates:
        return 2, False
    if not result.resolved:
        return 1, False
    return (0 if not result.unresolved else 1), True


def _print_summary(
    result: Any, duplicates: tuple[str, ...], seed_count: int = 0
) -> None:
    """Bounded stdout summary: counts plus controlled status constants."""
    status_counts: dict[str, int] = {}
    for row in result.unresolved:
        status_counts[row.status] = status_counts.get(row.status, 0) + 1
    print(
        "db-policy migration: input=%d resolved=%d unresolved=%d"
        " duplicateMutationKeys=%d seeds=%d"
        % (
            result.input_count,
            len(result.resolved),
            len(result.unresolved),
            len(duplicates),
            seed_count,
        )
    )
    for status in sorted(status_counts):
        print("unresolved %s=%d" % (status, status_counts[status]))


# ── CLI entry point ───────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="migrate legacy DB ownership policy entries into v2 candidates"
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write-candidate", action="store_true")
    mode.add_argument(
        "--generate",
        action="store_true",
        help="write BOTH tracked artifacts (candidate + accounting) atomically",
    )
    mode.add_argument(
        "--verify",
        action="store_true",
        help=(
            "verify the tracked candidate/accounting artifacts against an"
            " in-memory regeneration (PR-GR-10b); writes nothing but the"
            " optional --report; exit 0 verified / 1 drift / 2"
            " infrastructure"
        ),
    )
    parser.add_argument("--output")
    parser.add_argument("--policy", default=DEFAULT_POLICY)
    parser.add_argument("--report")
    parser.add_argument(
        "--seed-rows",
        dest="seed_rows",
        help=(
            "GR-08a reviewed exact v2 seed rows YAML (v2-shaped document);"
            " merged into the generated candidate after full loader"
            " validation and duplicate rejection"
        ),
    )
    parser.add_argument(
        "--accounting-out",
        "--write-accounting",
        dest="accounting_out",
        help=(
            "standalone accounting artifact path (paired with the"
            " candidate); with --verify, overrides the tracked accounting"
            " path under verification"
        ),
    )
    args = parser.parse_args(argv)
    try:
        if yaml is None:
            raise CliFailure(_MSG_INFRASTRUCTURE)
        repo_root = Path(__file__).resolve().parents[1]
        policy_path = (
            repo_root / DEFAULT_POLICY
            if args.policy == DEFAULT_POLICY
            else Path(args.policy)
        )
        if args.verify:
            return _run_verify(args, repo_root, policy_path)
        if args.write_candidate and not args.output:
            raise CliFailure(_MSG_OUTPUT_REQUIRED)
        # GR-08a: reviewed seed rows load FIRST (full v2 loader validation,
        # fail closed) so an invalid seed file never reaches analysis.
        seed_entries: list[Any] = []
        if args.seed_rows:
            seed_entries = _load_seed_entries(Path(args.seed_rows))
        candidate_target, accounting_target = _resolve_write_targets(
            args, repo_root
        )
        # In check mode both targets are None: nothing is ever written.
        _validate_output_paths(
            args.report,
            candidate_target,
            policy_path,
            accounting_target,
            repo_root=repo_root,
            seed=args.seed_rows,
        )
        entries = _load_legacy_entries(policy_path)
        result = migrate_policy(entries, repo_root, dao_index=None)
        # Seed/legacy key collisions fail closed before anything renders.
        _reject_seed_duplicates(result, seed_entries)
        duplicates = find_duplicate_mutation_keys(result)
        exit_code, may_write_candidate = _decide_exit(result, duplicates)
        # The candidate text is rendered first so the accounting artifact
        # hashes and crosswalk-verifies the EXACT bytes that will be
        # written; the candidate+accounting pair is then staged temp-first
        # and swapped together (both-or-neither) BEFORE the optional report.
        candidate_text = None
        accounting_payload = None
        write_pair: list[tuple[Path, str, str]] = []
        # PR-GR-05 source-mutation coverage: built ONCE for runs that
        # request a standalone accounting artifact.  ``None`` means the
        # coverage machinery failed closed; best-effort consumers degrade to
        # an empty section while generation runs fail closed instead of
        # shipping degraded evidence.
        coverage_mutations = None
        if accounting_target is not None and may_write_candidate:
            try:
                coverage_mutations = build_source_mutation_coverage(
                    repo_root, result, entries
                )
            except Exception:
                coverage_mutations = None
        if candidate_target is not None and may_write_candidate:
            candidate_text = yaml.safe_dump(
                _candidate_document(result, seed_entries),
                sort_keys=False,
                allow_unicode=False,
            ).replace("\r\n", "\n")
            if accounting_target is not None:
                accounting_payload = _require_accounting_section(
                    result,
                    policy_path,
                    repo_root,
                    candidate_text,
                    coverage_mutations,
                    seed_entries,
                )
                _verify_candidate_accounting_pair(
                    candidate_text, accounting_payload
                )
                write_pair.append(
                    (
                        accounting_target,
                        json.dumps(
                            accounting_payload,
                            sort_keys=False,
                            separators=(",", ":"),
                        )
                        + "\n",
                        ".db-policy-accounting-",
                    )
                )
            write_pair.insert(
                0, (candidate_target, candidate_text, ".db-policy-candidate-")
            )
        if write_pair:
            _atomic_write_all(write_pair)
        if args.report:
            report_accounting = accounting_payload
            if report_accounting is None:
                report_accounting = _build_accounting_section(
                    result,
                    policy_path,
                    repo_root,
                    candidate_text,
                    coverage_mutations if coverage_mutations is not None else (),
                    seed_entries,
                )
            payload = _build_report_payload(
                result,
                duplicates,
                _policy_identifier(policy_path, repo_root),
                accounting=report_accounting,
                seed_count=len(seed_entries),
            )
            _atomic_write_text(
                Path(args.report),
                json.dumps(payload, sort_keys=False, separators=(",", ":")) + "\n",
                ".db-policy-report-",
            )
        _print_summary(result, duplicates, len(seed_entries))
        return exit_code
    except CliFailure as failure:
        print(failure.message, file=sys.stderr)
        return 2
    except Exception:
        # Infrastructure failure: fail closed with a bounded diagnostic that
        # never echoes exception text, paths, SQL, or payloads.
        print(_MSG_INFRASTRUCTURE, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
