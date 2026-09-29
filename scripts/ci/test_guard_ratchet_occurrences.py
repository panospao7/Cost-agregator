"""CL-22: protocol-v1 debt is counted, not a file-wide authorization."""
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import guard_ratchet as ratchet


FP = "G-CANCEL-01 app/src/main/java/Example.kt"


def test_extraction_preserves_same_file_occurrences_but_ignores_line_movement(tmp_path):
    before = ratchet.extract_fingerprints(
        "G-CANCEL-01 app/src/main/java/Example.kt:10 unsafe\n"
        "G-CANCEL-01 app/src/main/java/Example.kt:30 unsafe", tmp_path)
    after = ratchet.extract_fingerprints(
        "G-CANCEL-01 app/src/main/java/Example.kt:15 unsafe\n"
        "G-CANCEL-01 app/src/main/java/Example.kt:35 unsafe", tmp_path)
    assert before == after == [FP, FP]
    assert ratchet.compare_fingerprints(before, after) == ([], [], [FP, FP])
    assert ratchet.compare_fingerprints([FP], after) == ([FP], [], [FP])
    assert ratchet.compare_fingerprints(before, [FP]) == ([], [FP], [FP])


def test_event_rule_identity_is_not_collapsed_to_generic_dao_or_entity(tmp_path):
    result = ratchet.extract_fingerprints(
        "[DAO] FirstEventDao.insert — app/src/main/java/Example.kt:10\n"
        "[DAO] SecondEventDao.insert — app/src/main/java/Example.kt:20\n"
        "[ENTITY] FirstEvent — app/src/main/java/Example.kt:30", tmp_path)
    assert result == [
        "DAO:FirstEventDao.insert app/src/main/java/Example.kt",
        "DAO:SecondEventDao.insert app/src/main/java/Example.kt",
        "ENTITY:FirstEvent app/src/main/java/Example.kt",
    ]


def test_actual_event_rule_names_with_operation_slashes_remain_parseable(tmp_path):
    assert ratchet.extract_fingerprints(
        "[DAO] operationRunDao.insert/update — app/src/main/java/Example.kt:10\n"
        "[DAO] backgroundJobRunDao.insert/update — app/src/main/java/Example.kt:20", tmp_path
    ) == [
        "DAO:backgroundJobRunDao.insert/update app/src/main/java/Example.kt",
        "DAO:operationRunDao.insert/update app/src/main/java/Example.kt",
    ]


def _baseline(tmp_path, **extra):
    path = tmp_path / "baseline.json"
    path.write_text(json.dumps({"guard": "cancellation", "fingerprints": [FP], **extra}), encoding="utf-8")
    return path


@pytest.mark.parametrize("counts", [None, [], {}, {FP: 0}, {FP: -1}, {FP: True},
                                    {FP: "2"}, {FP: 1.5}, {FP: 1000000000},
                                    {FP: 1, "unknown": 1}])
def test_invalid_reviewed_count_metadata_fails_closed(tmp_path, counts, capsys):
    with pytest.raises(SystemExit) as raised:
        ratchet.load_baseline(_baseline(tmp_path, occurrence_counts=counts), "cancellation")
    assert raised.value.code == 2
    assert "RATCHET_BASELINE_COUNTS_INVALID" in capsys.readouterr().err


def test_explicit_reviewed_counts_round_trip_without_duplicate_keys(tmp_path):
    path = tmp_path / "baseline.json"
    ratchet.save_baseline(path, "cancellation", [FP, FP])
    loaded = ratchet.load_baseline(path, "cancellation")
    assert loaded["fingerprints"] == [FP]
    assert loaded["occurrence_counts"] == {FP: 2}


def _run(tmp_path, baseline, output):
    child = tmp_path / "guard.py"
    child.write_text("import sys\nprint(" + repr(output) + ")\nsys.exit(1)\n", encoding="utf-8")
    return subprocess.run([
        sys.executable, str(Path(ratchet.__file__).resolve()),
        "--guard-name", "cancellation", "--baseline", str(baseline),
        "--command-arg=" + sys.executable, "--command-arg=" + str(child),
        "--fail-on-violation", "--ci-mode",
    ], capture_output=True, text=True, encoding="utf-8", timeout=30)


def test_legacy_baseline_cannot_authorize_a_second_same_file_violation(tmp_path):
    path = _baseline(tmp_path)
    original = path.read_bytes()
    result = _run(tmp_path, path,
        "G-CANCEL-01 app/src/main/java/Example.kt:10 unsafe\n"
        "G-CANCEL-01 app/src/main/java/Example.kt:20 unsafe")
    assert result.returncode == 1, result.stdout + result.stderr
    assert path.read_bytes() == original


def test_explicit_reviewed_multiplicity_passes_only_at_the_reviewed_count(tmp_path):
    path = _baseline(tmp_path, occurrence_counts={FP: 2})
    output = "G-CANCEL-01 app/src/main/java/Example.kt:10 unsafe\nG-CANCEL-01 app/src/main/java/Example.kt:20 unsafe"
    result = _run(tmp_path, path, output)
    assert result.returncode == 0, result.stdout + result.stderr
    grown = _run(tmp_path, path, output + "\nG-CANCEL-01 app/src/main/java/Example.kt:30 unsafe")
    assert grown.returncode == 1, grown.stdout + grown.stderr
