#!/usr/bin/env python3
"""
test_promote_db_policy_v2.py -- Pytest suite for PR-GR-07 Slice 1.

Covers scripts/ci/promote_db_policy_v2.py:

   1. Happy path: gated promotion -> exit 0, active == candidate bytes,
      active loads as v2, deterministic promotion record fields exact.
   2. Byte-preservation of the archived legacy v1 document.
   3. Record determinism across identical repositories (no timestamps).
   4. Idempotent re-run with --force-repromote: byte-identical outputs,
      archived v1 untouched, no temp files left behind.
   5-14. Readiness-gate refusals (exit 2, NOTHING written): candidate
      loader error, missing/untrusted/diagnostic-bearing/schema-unsupported/
      stale evidence, malformed accounting, accounting candidateSha mismatch,
      crosswalk missing key, incomplete record indices.
  15-16. Active-state refusals: v2 already active without --force-repromote;
      active not a loadable legacy v1 document.
  17-18. Archive mismatch refusal; source-root manifest failure refusal.
  19. Atomicity: injected staging failure leaves both files untouched and
      no temp files behind.
  20. Post-write verification catches a candidate tampered between staging
      and replacement (monkeypatched os.replace injection); state stays
      visible (record written, staged bytes landed), exit 2.
  21. Forced re-promotion over a DIFFERENT v2 active archives that active's
      bytes (archive semantics still apply under force).
  22. Tokenized-argv subprocess invocation (shell=False).
  23. Promotion record contains no absolute paths.

Every filesystem test builds its own synthetic repository under ``tmp_path``
(manifest, Kotlin sources, legacy v1 active, valid candidate, matching GR-06
evidence report, matching GR-05 accounting artifact); no test scans the real
repository, executes Gradle, or mutates anything outside ``tmp_path``.

Run:
    python -m pytest scripts/ci/test_promote_db_policy_v2.py -v
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
_PROJECT_ROOT = str(Path(_SCRIPT_DIR).parent.parent)
for _path in (_SCRIPT_DIR, _PROJECT_ROOT):
    if _path not in sys.path:
        sys.path.insert(0, _path)

import promote_db_policy_v2 as promote  # noqa: E402
import verify_db_policy_v2_evidence as shadow  # noqa: E402
from scripts.db_guard.policy_v2_loader import load_policy_v2  # noqa: E402

# ── Fixture constants ────────────────────────────────────────────────────────

MANIFEST_RELPATH = "config/guards/production_source_roots.yml"
MANIFEST_TEXT = (
    "schemaVersion: 1\n"
    "roots:\n"
    '  - module: ":app"\n'
    "    sourceSet: main\n"
    "    path: app/src/main/java\n"
)

CANDIDATE_RELPATH = (
    "config/guards/db_ownership_policy.signatures.candidate.yml"
)
ACTIVE_RELPATH = "config/guards/db_ownership_policy.yml"
ARCHIVE_RELPATH = "config/guards/db_ownership_policy.legacy.yml"
ACCOUNTING_RELPATH = (
    "config/guards/db_ownership_policy.signatures.accounting.json"
)
RECORD_RELPATH = promote.PROMOTION_RECORD_RELPATH
EVIDENCE_RELPATH = "build/v2-evidence.json"

REPO_KT = "app/src/main/java/com/example/Repo.kt"
DAO_KT = "app/src/main/java/com/example/data/GroupDao.kt"
OWNER = "com.example.Repo"

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

DAO_SOURCE = """\
package com.example.data

@Dao
interface GroupDao {
    @Insert
    fun insert(group: Group)
}

data class Group(val id: Int)
"""


def _candidate_text(reason="synthetic promotion fixture entry"):
    """A minimal valid v2 candidate with one entry matching HAPPY_SOURCE."""
    return (
        "schemaVersion: 2\n"
        "entries:\n"
        "- path: " + REPO_KT + "\n"
        "  ownerFqcn: " + OWNER + "\n"
        "  kind: function\n"
        "  method: insertGroup\n"
        "  receiver: null\n"
        "  parameterTypes:\n"
        "  - com.example.Group\n"
        "  daoAccessor: groupDao\n"
        "  daoFqcn: com.example.data.GroupDao\n"
        "  operation: insert\n"
        "  barrierMode: direct\n"
        "  reason: " + reason + "\n"
        "  owner: db-guard-tests\n"
        "  linkedIssue: GR07-S1-T\n"
    )


LEGACY_ACTIVE_TEXT = """\
# Legacy v1 DB ownership policy (synthetic fixture).
entries:
- path: app/src/main/java/com/example/Repo.kt
  class: Repo
  method: "insertGroup"
  daos: [groupDao]
  operation: "insert"
  barrier_required: true
  reason: "synthetic legacy fixture entry"
  owner: "@db-guard-tests"
  linked_issue: "GR07-S1-T"
"""

MUTATION_KEY = "|".join(
    [
        REPO_KT,
        OWNER,
        "function",
        "insertGroup",
        "null",
        "com.example.Group",
        "groupDao",
        "com.example.data.GroupDao",
        "insert",
    ]
)


# ── Repository / artifact builders ───────────────────────────────────────────


def _write(root, rel, content):
    if isinstance(content, str):
        content = content.encode("utf-8")
    target = Path(root).joinpath(*rel.split("/"))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
    return target


def _read_bytes(root, rel):
    return Path(root).joinpath(*rel.split("/")).read_bytes()


def _sha(data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _evidence_document(candidate_sha):
    return {
        "schema": shadow.REPORT_SCHEMA_NAME,
        "version": shadow.REPORT_SCHEMA_VERSION,
        "policy_path": CANDIDATE_RELPATH,
        "policy_sha256": candidate_sha,
        "tree_sha256": "d" * 64,
        "trusted": True,
        "groups": [],
        "diagnostics": [],
        "mutation_key_count": 1,
        "policy_mutation_key_count": 1,
    }


def _accounting_document(candidate_sha, mutation_keys=(MUTATION_KEY,),
                         input_count=1, records=None):
    if records is None:
        records = [
            {
                "action": "EMIT_CANDIDATE",
                "detail": "",
                "index": 0,
                "mutationKeys": list(mutation_keys),
                "outcome": "RESOLVED",
                "status": None,
            }
        ]
    return {
        "schema": "db-policy-migration-accounting",
        "version": 1,
        "candidateSha256": candidate_sha,
        "inputCount": input_count,
        "records": records,
        "sourceMutations": [],
        "sourcePolicyPath": ACTIVE_RELPATH,
        "sourcePolicySha256": "b" * 64,
        "sourceTreeSha": "c" * 64,
    }


def _make_repo(tmp_path, candidate_reason="synthetic promotion fixture entry"):
    """Synthetic repo: manifest, Kotlin tree, legacy v1 active, candidate,
    matching accounting artifact, and matching GR-06 evidence report."""
    _write(tmp_path, MANIFEST_RELPATH, MANIFEST_TEXT)
    _write(tmp_path, REPO_KT, HAPPY_SOURCE)
    _write(tmp_path, DAO_KT, DAO_SOURCE)
    _write(tmp_path, ACTIVE_RELPATH, LEGACY_ACTIVE_TEXT)
    candidate_text = _candidate_text(candidate_reason)
    _write(tmp_path, CANDIDATE_RELPATH, candidate_text)
    candidate_sha = _sha(candidate_text)
    _write(
        tmp_path,
        ACCOUNTING_RELPATH,
        json.dumps(
            _accounting_document(candidate_sha), indent=2, sort_keys=True
        )
        + "\n",
    )
    _write(
        tmp_path,
        EVIDENCE_RELPATH,
        json.dumps(_evidence_document(candidate_sha), indent=2, sort_keys=True)
        + "\n",
    )
    return {"candidate_sha": candidate_sha}


def _default_extra():
    return ["--evidence-report", EVIDENCE_RELPATH]


def _run(tmp_path, extra):
    argv = ["--root", str(tmp_path)] + list(extra)
    with pytest.raises(SystemExit) as excinfo:
        promote.main(argv)
    return excinfo.value.code


def _assert_untouched(tmp_path):
    """Nothing written: active still legacy v1; no archive; no record."""
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == LEGACY_ACTIVE_TEXT.encode(
        "utf-8"
    )
    assert not Path(tmp_path).joinpath(*ARCHIVE_RELPATH.split("/")).exists()
    assert not Path(tmp_path).joinpath(*RECORD_RELPATH.split("/")).exists()


def _no_temps(tmp_path):
    guards_dir = Path(tmp_path).joinpath("config", "guards")
    leftovers = [
        name
        for name in guards_dir.iterdir()
        if name.name.startswith(".db_promote")
    ]
    assert leftovers == []


def _record(tmp_path):
    path = Path(tmp_path).joinpath(*RECORD_RELPATH.split("/"))
    return json.loads(path.read_text(encoding="utf-8"))


# ===========================================================================
# 1-4. Happy path, byte preservation, determinism, idempotent re-run
# ===========================================================================


def test_happy_path_promotion_outputs_and_record_fields(tmp_path, capsys):
    state = _make_repo(tmp_path)
    code = _run(tmp_path, _default_extra())
    assert code == 0
    assert capsys.readouterr().out.strip() == "DB_PROMOTE_OK mode=promote"

    # Active now holds the EXACT candidate bytes and loads as v2.
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == _read_bytes(
        tmp_path, CANDIDATE_RELPATH
    )
    entries, errors = load_policy_v2(
        str(Path(tmp_path).joinpath(*ACTIVE_RELPATH.split("/")))
    )
    assert errors == []
    assert entries is not None and len(entries) == 1

    # Deterministic promotion record with exactly the six recorded SHAs.
    record = _record(tmp_path)
    assert record["schema"] == promote.RECORD_SCHEMA_NAME
    assert record["version"] == promote.RECORD_SCHEMA_VERSION
    shas = record["recordedShas"]
    assert set(shas) == {
        "candidate_sha256",
        "active_sha256",
        "previous_v1_sha256",
        "tree_sha256",
        "evidence_report_sha256",
        "accounting_sha256",
    }
    assert shas["candidate_sha256"] == state["candidate_sha"]
    assert shas["active_sha256"] == state["candidate_sha"]
    assert shas["previous_v1_sha256"] == _sha(LEGACY_ACTIVE_TEXT)
    assert shas["evidence_report_sha256"] == _sha(
        _read_bytes(tmp_path, EVIDENCE_RELPATH)
    )
    assert shas["accounting_sha256"] == _sha(
        _read_bytes(tmp_path, ACCOUNTING_RELPATH)
    )
    assert isinstance(shas["tree_sha256"], str)
    assert len(shas["tree_sha256"]) == 64
    _no_temps(tmp_path)


def test_archived_v1_bytes_preserved_exactly(tmp_path):
    _make_repo(tmp_path)
    code = _run(tmp_path, _default_extra())
    assert code == 0
    assert _read_bytes(tmp_path, ARCHIVE_RELPATH) == LEGACY_ACTIVE_TEXT.encode(
        "utf-8"
    )


def test_record_deterministic_across_identical_repos(tmp_path):
    repo_a = Path(tmp_path) / "repo_a"
    repo_b = Path(tmp_path) / "repo_b"
    repo_a.mkdir()
    repo_b.mkdir()
    _make_repo(repo_a)
    _make_repo(repo_b)
    assert _run(repo_a, _default_extra()) == 0
    assert _run(repo_b, _default_extra()) == 0
    assert _read_bytes(repo_a, RECORD_RELPATH) == _read_bytes(
        repo_b, RECORD_RELPATH
    )


def test_idempotent_rerun_with_force_byte_identical(tmp_path):
    _make_repo(tmp_path)
    assert _run(tmp_path, _default_extra()) == 0
    before_active = _read_bytes(tmp_path, ACTIVE_RELPATH)
    before_archive = _read_bytes(tmp_path, ARCHIVE_RELPATH)
    before_record = _read_bytes(tmp_path, RECORD_RELPATH)

    code = _run(tmp_path, _default_extra() + ["--force-repromote"])
    assert code == 0

    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == before_active
    assert _read_bytes(tmp_path, ARCHIVE_RELPATH) == before_archive
    assert _read_bytes(tmp_path, RECORD_RELPATH) == before_record
    _no_temps(tmp_path)


# ===========================================================================
# 5-14. Readiness-gate refusals: exit 2, nothing written
# ===========================================================================


def test_refusal_candidate_loader_error_writes_nothing(tmp_path):
    _make_repo(tmp_path)
    _write(tmp_path, CANDIDATE_RELPATH, "entries: []\n")  # not a v2 document
    code = _run(tmp_path, _default_extra())
    assert code == 2
    _assert_untouched(tmp_path)
    _no_temps(tmp_path)


def test_refusal_evidence_missing(tmp_path):
    _make_repo(tmp_path)
    code = _run(tmp_path, ["--evidence-report", "build/missing.json"])
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_evidence_untrusted(tmp_path):
    _make_repo(tmp_path)
    document = _evidence_document(_sha(_read_bytes(tmp_path, CANDIDATE_RELPATH)))
    document["trusted"] = False
    _write(
        tmp_path,
        "build/untrusted.json",
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, ["--evidence-report", "build/untrusted.json"])
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_evidence_diagnostics_present(tmp_path):
    _make_repo(tmp_path)
    document = _evidence_document(_sha(_read_bytes(tmp_path, CANDIDATE_RELPATH)))
    document["diagnostics"] = [
        {"code": "DB_V2_POLICY_MUTATION_NOT_FOUND", "context": {}}
    ]
    _write(
        tmp_path,
        "build/diagnostic.json",
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, ["--evidence-report", "build/diagnostic.json"])
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_stale_evidence_sha(tmp_path):
    _make_repo(tmp_path)
    document = _evidence_document("0" * 64)  # does not match candidate bytes
    _write(
        tmp_path,
        "build/stale.json",
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, ["--evidence-report", "build/stale.json"])
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_evidence_schema_unsupported(tmp_path):
    _make_repo(tmp_path)
    document = _evidence_document(_sha(_read_bytes(tmp_path, CANDIDATE_RELPATH)))
    document["schema"] = "some-other-schema"
    _write(
        tmp_path,
        "build/wrong_schema.json",
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, ["--evidence-report", "build/wrong_schema.json"])
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_accounting_malformed(tmp_path):
    _make_repo(tmp_path)
    _write(tmp_path, ACCOUNTING_RELPATH, "{not json")
    code = _run(tmp_path, _default_extra())
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_accounting_candidate_sha_mismatch(tmp_path):
    _make_repo(tmp_path)
    document = _accounting_document("e" * 64)
    _write(
        tmp_path,
        ACCOUNTING_RELPATH,
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, _default_extra())
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_crosswalk_missing_key(tmp_path):
    _make_repo(tmp_path)
    document = _accounting_document(
        _sha(_read_bytes(tmp_path, CANDIDATE_RELPATH)),
        mutation_keys=[],  # candidate key absent from every record
    )
    _write(
        tmp_path,
        ACCOUNTING_RELPATH,
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, _default_extra())
    assert code == 2
    _assert_untouched(tmp_path)


def test_refusal_accounting_index_incomplete(tmp_path):
    _make_repo(tmp_path)
    document = _accounting_document(
        _sha(_read_bytes(tmp_path, CANDIDATE_RELPATH)),
        input_count=2,  # only index 0 present: 0..inputCount-1 incomplete
    )
    _write(
        tmp_path,
        ACCOUNTING_RELPATH,
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    code = _run(tmp_path, _default_extra())
    assert code == 2
    _assert_untouched(tmp_path)


# ===========================================================================
# 15-18. Active-state, archive, and manifest gate refusals
# ===========================================================================


def test_refusal_v2_already_active_without_force(tmp_path):
    _make_repo(tmp_path)
    assert _run(tmp_path, _default_extra()) == 0
    promoted_active = _read_bytes(tmp_path, ACTIVE_RELPATH)
    promoted_archive = _read_bytes(tmp_path, ARCHIVE_RELPATH)
    promoted_record = _read_bytes(tmp_path, RECORD_RELPATH)

    code = _run(tmp_path, _default_extra())
    assert code == 2
    # State fully preserved after the refusal.
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == promoted_active
    assert _read_bytes(tmp_path, ARCHIVE_RELPATH) == promoted_archive
    assert _read_bytes(tmp_path, RECORD_RELPATH) == promoted_record


def test_refusal_active_not_legacy_v1(tmp_path):
    _make_repo(tmp_path)
    _write(tmp_path, ACTIVE_RELPATH, "::: not yaml at all [\n")
    code = _run(tmp_path, _default_extra())
    assert code == 2
    assert not Path(tmp_path).joinpath(*ARCHIVE_RELPATH.split("/")).exists()
    assert not Path(tmp_path).joinpath(*RECORD_RELPATH.split("/")).exists()


def test_refusal_archive_mismatch(tmp_path):
    _make_repo(tmp_path)
    foreign_bytes = b"some unrelated stale archive\n"
    _write(tmp_path, ARCHIVE_RELPATH, foreign_bytes)
    code = _run(tmp_path, _default_extra())
    assert code == 2
    # Active untouched, no record written, and the foreign archive was not
    # clobbered either.
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == LEGACY_ACTIVE_TEXT.encode(
        "utf-8"
    )
    assert _read_bytes(tmp_path, ARCHIVE_RELPATH) == foreign_bytes
    assert not Path(tmp_path).joinpath(*RECORD_RELPATH.split("/")).exists()
    _no_temps(tmp_path)


def test_refusal_manifest_missing(tmp_path, capsys):
    _make_repo(tmp_path)
    Path(tmp_path).joinpath(*MANIFEST_RELPATH.split("/")).unlink()
    code = _run(tmp_path, _default_extra())
    assert code == 2
    lines = capsys.readouterr().out.splitlines()
    assert lines
    assert all(
        line.split(" ")[0].startswith(("DB_PROMOTE_", "DB_SOURCE_ROOT_", "POLICY_ERROR_"))
        for line in lines
    )
    _assert_untouched(tmp_path)


# ===========================================================================
# 19-21. Atomicity, post-write verification, forced re-promotion
# ===========================================================================


def test_atomicity_injected_staging_failure_leaves_state_untouched(
    tmp_path, capsys, monkeypatch
):
    _make_repo(tmp_path)

    def _boom(_fd):
        raise OSError("injected staging failure")

    monkeypatch.setattr(promote.os, "fsync", _boom)
    code = _run(tmp_path, _default_extra())
    assert code == 2
    assert promote.CODE_WRITE_FAILED in capsys.readouterr().out

    # Both files untouched, no temps anywhere.
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == LEGACY_ACTIVE_TEXT.encode(
        "utf-8"
    )
    assert not Path(tmp_path).joinpath(*ARCHIVE_RELPATH.split("/")).exists()
    assert not Path(tmp_path).joinpath(*RECORD_RELPATH.split("/")).exists()
    _no_temps(tmp_path)


def test_post_write_verify_catches_tampered_candidate(
    tmp_path, capsys, monkeypatch
):
    _make_repo(tmp_path)
    active_path = Path(tmp_path).joinpath(*ACTIVE_RELPATH.split("/"))
    candidate_path = Path(tmp_path).joinpath(*CANDIDATE_RELPATH.split("/"))
    original_candidate = candidate_path.read_bytes()

    real_replace = os.replace

    def _tampering_replace(src, dst):
        real_replace(src, dst)
        # Inject candidate tampering right after the active replacement.
        if os.path.normcase(dst) == os.path.normcase(str(active_path)):
            with open(candidate_path, "ab") as handle:
                handle.write(b"\n# tampered\n")

    monkeypatch.setattr(promote.os, "replace", _tampering_replace)
    code = _run(tmp_path, _default_extra())
    assert code == 2
    out = capsys.readouterr().out
    assert promote.CODE_POST_WRITE_VERIFY_FAILED in out
    assert "active-bytes-mismatch" in out

    # State visible, NOT rolled back: staged bytes landed, v1 archived,
    # record written before verification failed.
    assert active_path.read_bytes() == original_candidate
    assert _read_bytes(tmp_path, ARCHIVE_RELPATH) == LEGACY_ACTIVE_TEXT.encode(
        "utf-8"
    )
    assert Path(tmp_path).joinpath(*RECORD_RELPATH.split("/")).exists()


def test_forced_repromote_over_different_v2_archives_old_active(tmp_path):
    _make_repo(tmp_path)
    assert _run(tmp_path, _default_extra()) == 0
    first_candidate_sha = _sha(_read_bytes(tmp_path, CANDIDATE_RELPATH))

    # A NEW candidate (same entry, different reason => different bytes),
    # with fresh matching evidence and accounting artifacts.
    second_text = _candidate_text(reason="second synthetic candidate entry")
    second_sha = _sha(second_text)
    assert second_sha != first_candidate_sha
    _write(tmp_path, CANDIDATE_RELPATH, second_text)
    _write(
        tmp_path,
        "build/accounting-b.json",
        json.dumps(
            _accounting_document(second_sha), indent=2, sort_keys=True
        )
        + "\n",
    )
    _write(
        tmp_path,
        "build/v2-evidence-b.json",
        json.dumps(
            _evidence_document(second_sha), indent=2, sort_keys=True
        )
        + "\n",
    )

    # Archive points at an ABSENT path so gate 5 passes under force.
    code = _run(
        tmp_path,
        [
            "--force-repromote",
            "--archive",
            "build/archive-b.yml",
            "--accounting",
            "build/accounting-b.json",
            "--evidence-report",
            "build/v2-evidence-b.json",
        ],
    )
    assert code == 0

    # Active holds the new candidate; the OLD v2 active bytes were archived;
    # the original v1 archive stayed untouched.
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == second_text.encode("utf-8")
    assert _read_bytes(tmp_path, "build/archive-b.yml") == _candidate_text(
        "synthetic promotion fixture entry"
    ).encode("utf-8")
    assert _read_bytes(tmp_path, ARCHIVE_RELPATH) == LEGACY_ACTIVE_TEXT.encode(
        "utf-8"
    )
    record = _record(tmp_path)
    assert record["recordedShas"]["previous_v1_sha256"] == first_candidate_sha


# ===========================================================================
# 22-23. CLI shape and privacy posture
# ===========================================================================


def test_tokenized_argv_subprocess_no_shell(tmp_path):
    _make_repo(tmp_path)
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(_SCRIPT_DIR) / "promote_db_policy_v2.py"),
            "--root",
            str(tmp_path),
            "--accounting",
            ACCOUNTING_RELPATH,
            "--evidence-report",
            EVIDENCE_RELPATH,
        ],
        shell=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0
    assert "DB_PROMOTE_OK" in completed.stdout
    assert _read_bytes(tmp_path, ACTIVE_RELPATH) == _read_bytes(
        tmp_path, CANDIDATE_RELPATH
    )


def test_record_contains_no_absolute_paths(tmp_path):
    _make_repo(tmp_path)
    assert _run(tmp_path, _default_extra()) == 0
    text = Path(tmp_path).joinpath(*RECORD_RELPATH.split("/")).read_text(
        encoding="utf-8"
    )
    assert str(tmp_path) not in text
    assert "\\" not in text
