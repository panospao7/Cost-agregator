"""
test_verify_lint_baseline_policy.py
I-04-2 tests for verify_lint_baseline_policy.py: the MissingTranslation ceiling is a pinned
constant (never derived from the baseline being checked) and the flag can only lower it.

Run with: python -m pytest scripts/test_verify_lint_baseline_policy.py -v

The expected ceiling below is a literal pin, deliberately NOT imported from the module, so
raising the constant in the guard fails these tests until a recorded owner decision updates them.
"""
import os
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "verify_lint_baseline_policy.py"
PINNED_CEILING = 2223


def _write_baseline(tmp_path: Path, missing_translation: int, forbidden_ids=()) -> Path:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<issues format="6" by="lint 8.0.0">']
    for i in range(missing_translation):
        lines.append(
            f'  <issue id="MissingTranslation" message="synthetic {i}">'
            f'<location file="app/src/main/res/values/strings.xml" line="{i + 1}"/></issue>'
        )
    for j, issue_id in enumerate(forbidden_ids):
        lines.append(
            f'  <issue id="{issue_id}" message="synthetic forbidden {j}">'
            f'<location file="app/src/main/java/Synthetic{j}.kt" line="{j + 1}"/></issue>'
        )
    lines.append("</issues>")
    path = tmp_path / "lint-baseline.xml"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def _run(baseline: Path, *extra: str, fail_on_violation: bool = True):
    cmd = [sys.executable, "-I", str(SCRIPT), "--baseline", str(baseline)]
    if fail_on_violation:
        cmd.append("--fail-on-violation")
    cmd.extend(extra)
    return subprocess.run(cmd, capture_output=True, text=True, env=dict(os.environ))


def test_count_at_pinned_ceiling_passes(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING)
    result = _run(baseline)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout


def test_count_below_pinned_ceiling_passes(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING - 1)
    result = _run(baseline)
    assert result.returncode == 0, result.stdout + result.stderr


def test_count_above_pinned_ceiling_fails_with_fail_on_violation(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING + 1)
    result = _run(baseline)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "exceeds maximum" in result.stdout


def test_count_above_pinned_ceiling_without_flag_reports_but_exits_zero(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING + 1)
    result = _run(baseline, fail_on_violation=False)
    assert result.returncode == 0
    assert "FAIL" in result.stdout


def test_ceiling_is_not_derived_from_baseline_total(tmp_path):
    # A wholesale-regenerated baseline far above the ceiling must still fail.
    baseline = _write_baseline(tmp_path, PINNED_CEILING + 500)
    result = _run(baseline)
    assert result.returncode == 1, result.stdout + result.stderr


def test_forbidden_issue_id_fails_even_with_low_missing_translation_count(tmp_path):
    baseline = _write_baseline(tmp_path, 5, forbidden_ids=["HardcodedText"])
    result = _run(baseline)
    assert result.returncode == 1, result.stdout + result.stderr
    assert "FORBIDDEN" in result.stdout
    assert "HardcodedText" in result.stdout


def test_flag_cannot_raise_ceiling_above_pinned_constant(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING + 1)
    result = _run(baseline, "--max-missing-translations", "3000")
    assert result.returncode == 1, result.stdout + result.stderr
    assert f"exceeds maximum ({PINNED_CEILING})" in result.stdout


def test_flag_above_constant_still_passes_at_pinned_ceiling(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING)
    result = _run(baseline, "--max-missing-translations", "3000")
    assert result.returncode == 0, result.stdout + result.stderr


def test_lower_flag_value_tightens_ceiling_and_fails_at_pinned_count(tmp_path):
    baseline = _write_baseline(tmp_path, PINNED_CEILING)
    result = _run(baseline, "--max-missing-translations", "100")
    assert result.returncode == 1, result.stdout + result.stderr
    assert "exceeds maximum (100)" in result.stdout


def test_lower_flag_value_passes_when_count_within_it(tmp_path):
    baseline = _write_baseline(tmp_path, 100)
    result = _run(baseline, "--max-missing-translations", "100")
    assert result.returncode == 0, result.stdout + result.stderr


def test_missing_baseline_file_is_an_error(tmp_path):
    result = _run(tmp_path / "does-not-exist.xml")
    assert result.returncode == 2
