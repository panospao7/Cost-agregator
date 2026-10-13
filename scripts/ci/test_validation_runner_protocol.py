import json
import os
import re
import subprocess
from pathlib import Path

import pytest


pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runner")
RUNNER = Path(__file__).resolve().parents[1] / "validation-runner.ps1"


def _functions(*names):
    source = RUNNER.read_text(encoding="utf-8-sig")
    definitions = []
    for name in names:
        matched = re.search(r"(?ms)^function " + re.escape(name) + r" \{.*?^\}", source)
        assert matched is not None, name
        definitions.append(matched.group())
    return "\n".join(definitions)


def _powershell(script):
    return subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True, text=True, timeout=30,
    )


def test_atomic_publication_keeps_record_readable(tmp_path):
    destination = tmp_path / "result.json"
    destination.write_text(json.dumps({"generation": -1}), encoding="utf-8")
    functions = _functions("Write-JsonAtomic")
    script = functions + f"\n$target = '{str(destination).replace(chr(39), chr(39) * 2)}'\n" + """
$ErrorActionPreference = 'Stop'
for ($generation = 0; $generation -lt 25; $generation++) {
    Write-JsonAtomic -Path $target -Value @{ generation = $generation }
    Start-Sleep -Milliseconds 10
}
"""
    process = subprocess.Popen(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    errors = []
    try:
        import time

        deadline = time.monotonic() + 30
        while process.poll() is None and time.monotonic() < deadline:
            try:
                json.loads(destination.read_text(encoding="utf-8-sig"))
            except (PermissionError, FileNotFoundError):
                # Windows can briefly deny a reader while File.Replace swaps
                # or moves the destination. Retry the read; only a
                # persistently invalid final record is a protocol failure.
                time.sleep(0.002)
                continue
            except (OSError, ValueError) as failure:
                errors.append(type(failure).__name__)
            time.sleep(0.005)
        stdout, stderr = process.communicate(timeout=5)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
    assert process.returncode == 0, (stdout, stderr)
    assert errors == []
    assert json.loads(destination.read_text(encoding="utf-8-sig")) == {"generation": 24}


def test_wait_reconciles_dead_worker_without_another_start(tmp_path):
    run_id = "vr-20260101-000000-deadbeef"
    runs = tmp_path / "runs"
    run = runs / run_id
    run.mkdir(parents=True)
    record = {
        "status": "STARTING", "child_pid": None, "exit_code": None,
        "failure_code": None, "finished_at": None, "worktree_fingerprint_end": None,
    }
    (run / "result.json").write_text(json.dumps(record), encoding="utf-8")
    (run / "result.json.999.tmp").write_text("incomplete", encoding="utf-8")
    lock_path = runs / "active.lock.json"
    lock_path.write_text(json.dumps({
        "run_id": run_id, "worker_pid": 0, "created_at": "2000-01-01T00:00:00Z",
    }), encoding="utf-8")
    functions = _functions(
        "Get-UtcTimestamp", "Write-JsonAtomic", "Read-JsonFile", "Get-RunDirectory",
        "Get-RunRecordPath", "Test-ProcessAlive", "Complete-OrphanedRun",
        "Resolve-ActiveLock", "Resolve-RunRecord", "Wait-ForRun",
    )
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(runs).replace(chr(39), chr(39) * 2)}'
$script:LockPath = '{str(lock_path).replace(chr(39), chr(39) * 2)}'
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
$RunId = '{run_id}'
$MaxWaitSeconds = 5
$PollSeconds = 1
function Get-WorktreeFingerprint {{ return 'end-fingerprint' }}
Wait-ForRun
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 2, (completed.stdout, completed.stderr)
    result = json.loads(completed.stdout)
    assert result["status"] == "INFRA_FAILURE"
    assert result["failure_code"] == "E_ORPHANED_RUN"
    assert result["exit_code"] == 2
    assert result["worktree_fingerprint_end"] == "end-fingerprint"
    assert (run / "complete.marker").is_file()
    assert (run / "stdout.log").is_file()
    assert (run / "stderr.log").is_file()
    assert not list(run.glob("result.json.*.tmp"))
    assert not lock_path.exists()


def test_record_reader_retries_transient_sharing_failures(tmp_path):
    destination = tmp_path / "result.json"
    destination.write_text(json.dumps({"generation": 7}), encoding="utf-8")
    functions = _functions("Read-JsonFile")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:readAttempts = 0
function Get-Content {{
    param($LiteralPath, [switch]$Raw, $Encoding)
    $script:readAttempts++
    if ($script:readAttempts -lt 3) {{ throw [System.IO.IOException]::new('sharing violation') }}
    Microsoft.PowerShell.Management\\Get-Content -LiteralPath $LiteralPath -Raw -Encoding UTF8
}}
$record = Read-JsonFile -Path '{str(destination).replace(chr(39), chr(39) * 2)}'
@{{ generation = $record.generation; attempts = $script:readAttempts }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"generation": 7, "attempts": 3}


def test_record_reader_does_not_accept_persistently_invalid_json(tmp_path):
    destination = tmp_path / "result.json"
    destination.write_text("incomplete", encoding="utf-8")
    functions = _functions("Read-JsonFile")
    setup = f"""
$ErrorActionPreference = 'Stop'
$record = Read-JsonFile -Path '{str(destination).replace(chr(39), chr(39) * 2)}'
@{{ missing = $null -eq $record }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"missing": True}


def test_wait_reconciles_orphan_without_a_lock(tmp_path):
    run_id = "vr-20260101-000000-deadbeef"
    runs = tmp_path / "runs"
    run = runs / run_id
    run.mkdir(parents=True)
    (run / "result.json").write_text(json.dumps({
        "status": "STARTING", "worker_pid": 0, "child_pid": None,
        "started_at": "2000-01-01T00:00:00Z", "finished_at": None,
        "exit_code": None, "failure_code": None, "worktree_fingerprint_end": None,
    }), encoding="utf-8")
    functions = _functions(
        "Get-UtcTimestamp", "Write-JsonAtomic", "Read-JsonFile", "Get-RunDirectory",
        "Get-RunRecordPath", "Test-ProcessAlive", "Complete-OrphanedRun",
        "Resolve-ActiveLock", "Resolve-RunRecord", "Wait-ForRun",
    )
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(runs).replace(chr(39), chr(39) * 2)}'
$script:LockPath = Join-Path $script:RunsRoot 'active.lock.json'
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
$RunId = '{run_id}'
$MaxWaitSeconds = 5
$PollSeconds = 1
function Get-WorktreeFingerprint {{ return 'end-fingerprint' }}
Wait-ForRun
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 2, (completed.stdout, completed.stderr)
    result = json.loads(completed.stdout)
    assert result["status"] == "INFRA_FAILURE"
    assert result["failure_code"] == "E_ORPHANED_RUN"
    assert (run / "complete.marker").is_file()


def test_wait_does_not_recover_a_live_worker_without_a_lock(tmp_path):
    run_id = "vr-20260101-000000-deadbeef"
    runs = tmp_path / "runs"
    run = runs / run_id
    run.mkdir(parents=True)
    (run / "result.json").write_text(json.dumps({
        "status": "STARTING", "worker_pid": 123,
        "started_at": "2000-01-01T00:00:00Z",
    }), encoding="utf-8")
    functions = _functions(
        "Read-JsonFile", "Get-RunDirectory", "Get-RunRecordPath",
        "Resolve-ActiveLock", "Resolve-RunRecord", "Wait-ForRun",
    )
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(runs).replace(chr(39), chr(39) * 2)}'
$script:LockPath = Join-Path $script:RunsRoot 'active.lock.json'
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
$RunId = '{run_id}'
$MaxWaitSeconds = 1
$PollSeconds = 1
function Test-ProcessAlive {{ param([int]$ProcessId) return $ProcessId -eq 123 }}
Wait-ForRun
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 3, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout)["failure_code"] == "E_WAIT_WINDOW_EXPIRED"
    assert not (run / "complete.marker").exists()


@pytest.mark.parametrize("profile,stored_command,requested_command,expected", [
    ("targeted-unit-test", "gradle --tests *ReceiptParser*", "gradle --tests *ReceiptParser*", True),
    ("targeted-unit-test", "gradle --tests *ReceiptParser*", "gradle --tests *TotalsAggregationEngine*", False),
    ("targeted-release-unit-test", "gradle :app:testReleaseUnitTest --tests *ReceiptParser*", "gradle :app:testReleaseUnitTest --tests *ReceiptParser*", True),
    ("targeted-release-unit-test", "gradle :app:testDebugUnitTest --tests *ReceiptParser*", "gradle :app:testReleaseUnitTest --tests *ReceiptParser*", False),
    ("registered-guard", "guard --guard-id cancellation", "guard --guard-id raw_money_aggregates", False),
])
def test_duplicate_detection_requires_the_same_validation_command(
    tmp_path, profile, stored_command, requested_command, expected,
):
    run = tmp_path / "vr-20260101-000000-deadbeef"
    run.mkdir()
    (run / "result.json").write_text(json.dumps({
        "status": "PASS", "exit_code": 0, "profile": profile,
        "command": stored_command,
        "worktree_fingerprint_start": "fingerprint",
        "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    functions = _functions("Read-JsonFile", "Find-OverlappingSuccessfulRun")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(tmp_path).replace(chr(39), chr(39) * 2)}'
$match = Find-OverlappingSuccessfulRun -RequestedProfile '{profile}' -RequestedCommand '{requested_command}' -Fingerprint 'fingerprint'
@{{ duplicate = $null -ne $match }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"duplicate": expected}


@pytest.mark.parametrize("disappears,expected", [(True, "absent"), (False, "E_INVALID_LOCK")])
def test_lock_disappearance_during_read_is_not_malformed_lock(tmp_path, disappears, expected):
    lock_path = tmp_path / "active.lock.json"
    lock_path.write_text("incomplete", encoding="utf-8")
    functions = _functions("Resolve-ActiveLock")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:LockPath = '{str(lock_path).replace(chr(39), chr(39) * 2)}'
function Read-JsonFile {{
    param($Path)
    if (${str(disappears).lower()}) {{ Remove-Item -LiteralPath $Path }}
    return $null
}}
try {{
    $lock = Resolve-ActiveLock
    @{{ outcome = if ($null -eq $lock) {{ 'absent' }} else {{ 'unexpected' }} }} | ConvertTo-Json
}} catch {{
    @{{ outcome = $_.Exception.Message }} | ConvertTo-Json
}}
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"outcome": expected}
