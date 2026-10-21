import json
import os
import re
import subprocess
import sys
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
        "git_revision": "revision-a",
        "worktree_fingerprint_start": "fingerprint",
        "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    functions = _functions("Read-JsonFile", "Find-OverlappingSuccessfulRun")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(tmp_path).replace(chr(39), chr(39) * 2)}'
$match = Find-OverlappingSuccessfulRun -RequestedProfile '{profile}' -RequestedCommand '{requested_command}' -Fingerprint 'fingerprint' -Revision 'revision-a'
@{{ duplicate = $null -ne $match }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"duplicate": expected}


@pytest.mark.parametrize("stored_revision,requested_revision,expected", [
    ("revision-a", "revision-a", True),
    ("revision-a", "revision-b", False),
    ("revision-a", "REVISION-A", False),
    ("UNKNOWN", "UNKNOWN", False),
    ("revision-a", "UNKNOWN", False),
    ("revision-a", "", False),
    ("", "", False),
    ("UNKNOWN", "revision-a", False),
    (None, "revision-a", False),
    (None, "UNKNOWN", False),
])
def test_duplicate_detection_requires_the_same_known_git_revision(
    tmp_path, stored_revision, requested_revision, expected,
):
    # A clean tree always has the same fingerprint, so a new commit must never
    # be rejected as a duplicate of a PASS recorded at another revision.
    run = tmp_path / "vr-20260101-000000-deadbeef"
    run.mkdir()
    record = {
        "status": "PASS", "exit_code": 0, "profile": "compile",
        "command": "gradle :app:compileDebugKotlin",
        "worktree_fingerprint_start": "fingerprint",
        "worktree_fingerprint_end": "fingerprint",
    }
    if stored_revision is not None:
        record["git_revision"] = stored_revision
    (run / "result.json").write_text(json.dumps(record), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    functions = _functions("Read-JsonFile", "Find-OverlappingSuccessfulRun")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(tmp_path).replace(chr(39), chr(39) * 2)}'
$match = Find-OverlappingSuccessfulRun -RequestedProfile 'compile' -RequestedCommand 'gradle :app:compileDebugKotlin' -Fingerprint 'fingerprint' -Revision {_ps_quote(requested_revision)}
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


# Only extracted functions run in these fixtures. No test invokes the live
# runner entry point, the live validation lock, or a real Gradle wrapper.
DEFAULT_GRADLE_SETTINGS = {
    "max_workers": 1, "test_forks": 1, "fork_every": 50,
    "parallel": False, "gradle_daemon": False,
    "kotlin_compiler_strategy": "project",
}
GRADLE_PROFILE_ARGUMENTS = {
    "compile": [":app:compileDebugKotlin"],
    "benchmark-compile": [":app:compileDebugKotlin", "--rerun", "--no-build-cache", "-Pkotlin.incremental=false", "-Pkotlin.build.report.output=file", "--profile"],
    "benchmark-test-shard": [":app:testDebugUnitTest", "--rerun", "--tests", "*ShardOne*", "--tests", "*ShardTwo*", "--no-build-cache", "--profile"],
    "benchmark-app-check": [":app:testDebugUnitTest", "--rerun", ":app:testReleaseUnitTest", "--rerun", ":app:check", "--no-build-cache", "--profile"],
    "assemble-debug": [":app:assembleDebug"],
    "targeted-unit-test": [":app:testDebugUnitTest", "--tests", "*ProtocolCase*"],
    "targeted-release-unit-test": [":app:testReleaseUnitTest", "--tests", "*ProtocolCase*"],
    "unit-test-shard": [":app:testDebugUnitTest", "--tests", "*ShardOne*", "--tests", "*ShardTwo*"],
    "trusted-tests": [":app:testDebugUnitTest", "--tests", "*TrustedCase*"],
    "legacy-tests": [":app:testDebugUnitTest", "--tests", "*LegacyCase*"],
    "unit-tests": [":app:testDebugUnitTest"],
    "migration-tests": [":app:testDebugUnitTest", "--tests", "*Migration*"],
    "lint": [":app:lintDebug"],
    "app-check": [":app:check"],
    "connected-tests": [":app:connectedDebugAndroidTest"],
    "connected-test-class": [
        ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.ProtocolCase",
    ],
    "assemble-release": [":app:assembleRelease"],
}
NON_GRADLE_PROFILES = (
    "runner-smoke", "runner-progress-smoke", "runner-stall-smoke",
    "runner-protocol-tests", "pytest-subset", "static-guards", "registered-guard",
    "release-artifact-evidence",
)
CLASS_FILTER = "com.yourname.expensetracker.ProtocolCase"
# Profiles whose -TestFilter must be a fully qualified class name.
PROFILE_FILTERS = {"connected-test-class": CLASS_FILTER}
CONNECTED_PROFILES = ("connected-tests", "connected-test-class")
SERIAL = "emulator-5554"


def _settings(**overrides):
    return dict(DEFAULT_GRADLE_SETTINGS, **overrides)


def _ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def _ps_value(value):
    if value is None:
        return "$null"
    if isinstance(value, bool):
        return "$true" if value else "$false"
    if isinstance(value, str):
        return _ps_quote(value)
    return str(value)


def _ps_json(value):
    return f"({_ps_quote(json.dumps(value))} | ConvertFrom-Json)"


def _powershell_json(tmp_path, script, timeout=30, env=None):
    # -File avoids Windows' command-line length limit for extracted functions.
    probe = tmp_path / "protocol probe.ps1"
    probe.write_text(
        "Set-StrictMode -Version Latest\n$ErrorActionPreference = 'Stop'\n" + script,
        encoding="utf-8-sig",
    )
    completed = subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
         "Bypass", "-File", str(probe)],
        cwd=tmp_path, capture_output=True, text=True, timeout=timeout,
        env=None if env is None else {**os.environ, **env},
    )
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    return json.loads(completed.stdout)


def _invocation_setup(options, profile="compile"):
    values = {
        "Profile": profile, "MaxWorkers": 1, "TestForks": 1,
        "ForkEvery": 50, "Parallel": False, "GradleDaemon": False,
        "KotlinCompilerStrategy": "project",
    }
    lines = [f"${key} = {_ps_value(value)}" for key, value in values.items()]
    lines.append("$script:InvocationParameters = @{}")
    for key, value in options.items():
        lines.extend([
            f"${key} = {_ps_value(value)}",
            f"$script:InvocationParameters[{_ps_quote(key)}] = ${key}",
        ])
    return "\n".join(lines) + "\n"


def _gradle_fixture(tmp_path):
    root = tmp_path / "isolated repo's root"
    root.mkdir()
    runs = root / "build" / "validation-runs"
    runs.mkdir(parents=True)
    shards = root / "shards.json"
    ledger = root / "ledger.json"
    shards.write_text(json.dumps({
        "schema_version": 1,
        "shards": {"protocol-shard": {"filters": ["*ShardOne*", "*ShardTwo*"]}},
        "trusted": {"filters": ["*TrustedCase*"]},
    }), encoding="utf-8")
    ledger.write_text(json.dumps({
        "schema_version": 1,
        "entries": [{
            "id": "HANG-999", "status": "suspected", "routing": "legacy",
            "reason_code": "RUNNER_SELF_TEST", "owner": "protocol-test",
            "review_by": "2099-12-31", "test_filter": "*LegacyCase*",
        }],
    }), encoding="utf-8")
    source = _functions(
        "Read-JsonFile", "Read-ValidationConfig", "Assert-SafeTestFilters",
        "Get-TargetedTestFilters", "Get-ShardFilters", "Get-TrustedFilters", "Get-LegacyFilters",
        "New-TestArguments", "Test-GradleProfile", "Resolve-InvocationDeviceSerial",
        "New-GradleSettings",
        "Resolve-InvocationGradleSettings", "Resolve-RequestGradleSettings",
        "Get-ProfileSpec", "Get-SafeCommandDisplay",
    )
    source += f"""
$script:RepoRoot = {_ps_quote(root)}
$script:RunsRoot = {_ps_quote(runs)}
$script:LockPath = Join-Path $script:RunsRoot 'active.lock.json'
$script:ShardConfigPath = {_ps_quote(shards)}
$script:HangingLedgerPath = {_ps_quote(ledger)}
$script:SchemaVersion = 1
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
"""
    return root, source


def _gradle_arguments(profile, settings):
    arguments = GRADLE_PROFILE_ARGUMENTS[profile] + [
        "--console=plain", "--parallel" if settings["parallel"] else "--no-parallel",
        f"--max-workers={settings['max_workers']}",
        f"-PvalidationMaxParallelForks={settings['test_forks']}",
        f"-PvalidationForkEvery={settings['fork_every']}",
        "--daemon" if settings["gradle_daemon"] else "--no-daemon",
    ]
    if settings["kotlin_compiler_strategy"] != "project":
        arguments.append(
            f"-Pkotlin.compiler.execution.strategy={settings['kotlin_compiler_strategy']}"
        )
    return arguments


def _command_display(executable, arguments):
    return Path(executable).name + " " + " ".join(
        f'"{argument}"' if re.search(r"\s", argument) else argument
        for argument in map(str, arguments)
    )


def _case_key(*params):
    # Stable batch key derived from a test's own parametrized values.
    return json.dumps(params, sort_keys=True, default=str)


class _BatchResults:
    """Per-case outcomes of one batched PowerShell run; lookups fail closed."""

    def __init__(self, raw, index):
        self._raw = raw
        self._index = index

    def __getitem__(self, key):
        assert key in self._index, f"case missing from batch output: {key!r}"
        slot = self._index[key]
        assert slot in self._raw, f"case missing from batch output: {key!r}"
        text = self._raw[slot]
        assert isinstance(text, str) and text.strip(), f"empty batch output for case: {key}"
        try:
            value = json.loads(text)
        except ValueError:
            pytest.fail(f"garbled batch output for case: {key}: {text!r}")
        assert not (isinstance(value, dict) and "__batch_error__" in value), (key, value)
        return value


def _run_batch(base, source, cases, timeout=170):
    """Evaluate every (key, script) case in ONE PowerShell process.

    Each case runs in its own child scope inside its own try/catch; an unhandled
    exception becomes that case's `__batch_error__` outcome (which fails its
    test). Child scopes isolate ordinary variables only: script-scope state is
    shared across the batch, so each case first removes
    `$script:InvocationParameters` (the one script-scope variable the runner
    functions read) before its body runs. Whole-process failure, non-zero exit
    or unparseable stdout raises here, failing every dependent test. A key absent
    from the output fails the test that looks it up. The default timeout stays
    below the runner-protocol-tests no-output limit (180 s) so a hung batch fails
    as a pytest error instead of a runner stall.
    """
    keys = [key for key, _ in cases]
    assert len(keys) == len(set(keys)), "duplicate batch case key"
    parts = [source, "$batch = [ordered]@{}"]
    for position, (key, body) in enumerate(cases):
        parts.append(
            f"$batch[{_ps_quote(str(position))}] = & {{\n"
            "    try {\n"
            "        (& {\n"
            "            Remove-Variable InvocationParameters -Scope Script -ErrorAction SilentlyContinue\n"
            f"{body}\n        }} | Out-String)\n"
            "    } catch {\n"
            "        @{ '__batch_error__' = [string]$_.Exception.Message } | ConvertTo-Json\n"
            "    }\n"
            "}"
        )
    parts.append("$batch | ConvertTo-Json -Depth 3")
    raw = _powershell_json(base, "\n".join(parts), timeout=timeout)
    assert isinstance(raw, dict), "batch output is not a JSON object"
    index = {key: str(position) for position, (key, _) in enumerate(cases)}
    return _BatchResults(raw, index)


@pytest.fixture(scope="module")
def gradle_batch_env(tmp_path_factory):
    base = tmp_path_factory.mktemp("gradle-batch")
    root, source = _gradle_fixture(base)
    return base, root, source


@pytest.fixture(scope="module")
def profile_argv_batch(gradle_batch_env):
    base, _root, source = gradle_batch_env
    cases = []
    for profile in GRADLE_PROFILE_ARGUMENTS:
        selected_filter = _ps_quote(PROFILE_FILTERS.get(profile, "*ProtocolCase*"))
        cases.append((profile, _invocation_setup({}, profile) + f"""
$settings = Resolve-InvocationGradleSettings
$implicit = Get-ProfileSpec -Name $Profile -Filter {selected_filter} -ShardName 'protocol-shard'
$explicit = Get-ProfileSpec -Name $Profile -Filter {selected_filter} -ShardName 'protocol-shard' -GradleSettings $settings
@{{ settings = $settings; implicit = $implicit; explicit = $explicit }} | ConvertTo-Json -Depth 8
"""))
    return _run_batch(base, source, cases)


@pytest.mark.parametrize("profile", GRADLE_PROFILE_ARGUMENTS)
def test_all_gradle_profiles_retain_exact_historical_default_argv(gradle_batch_env, profile_argv_batch, profile):
    root = gradle_batch_env[1]
    result = profile_argv_batch[profile]
    assert result["settings"] == DEFAULT_GRADLE_SETTINGS
    expected = GRADLE_PROFILE_ARGUMENTS[profile] + [
        "--console=plain", "--no-parallel", "--max-workers=1",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50", "--no-daemon",
    ]
    for mode in ("implicit", "explicit"):
        assert result[mode]["executable"] == str(root / "gradlew.bat")
        assert result[mode]["arguments"] == expected


TUNING_CASES = [
    pytest.param({}, _settings(), id="defaults"),
    pytest.param({"Parallel": True}, _settings(max_workers=4, test_forks=2, parallel=True), id="parallel-preset"),
    pytest.param({"Parallel": True, "MaxWorkers": 6}, _settings(max_workers=6, test_forks=2, parallel=True), id="explicit-workers"),
    pytest.param({"Parallel": True, "TestForks": 1}, _settings(max_workers=4, parallel=True), id="explicit-default-forks"),
    pytest.param(
        {"Parallel": True, "MaxWorkers": 1, "TestForks": 1, "ForkEvery": 0, "GradleDaemon": False},
        _settings(fork_every=0, parallel=True), id="explicit-default-counts-false-zero",
    ),
    pytest.param({"Parallel": False, "GradleDaemon": False, "ForkEvery": 0}, _settings(fork_every=0), id="serial-explicit-false-zero"),
    pytest.param(
        {"MaxWorkers": 16, "TestForks": 8, "ForkEvery": 1000, "GradleDaemon": True, "KotlinCompilerStrategy": "IN-PROCESS"},
        _settings(max_workers=16, test_forks=8, fork_every=1000, gradle_daemon=True, kotlin_compiler_strategy="in-process"),
        id="upper-bounds-warm-in-process",
    ),
    pytest.param({"KotlinCompilerStrategy": "Daemon"}, _settings(kotlin_compiler_strategy="daemon"), id="normalize-daemon"),
    pytest.param({"KotlinCompilerStrategy": "PROJECT"}, _settings(), id="normalize-project"),
]


@pytest.fixture(scope="module")
def tuning_batch(gradle_batch_env):
    base, _root, source = gradle_batch_env
    cases = [
        (_case_key(case.values[0]), _invocation_setup(case.values[0]) + """
$settings = Resolve-InvocationGradleSettings
$spec = Get-ProfileSpec -Name $Profile -GradleSettings $settings
@{ settings = $settings; arguments = $spec.arguments } | ConvertTo-Json -Depth 8
""")
        for case in TUNING_CASES
    ]
    return _run_batch(base, source, cases)


@pytest.mark.parametrize("options,expected", TUNING_CASES)
def test_gradle_presets_only_replace_omitted_counts_and_build_explicit_flags(tuning_batch, options, expected):
    result = tuning_batch[_case_key(options)]
    assert result == {"settings": expected, "arguments": _gradle_arguments("compile", expected)}


WORKERS_BELOW_FORKS_OPTIONS = [
    {"MaxWorkers": 1, "TestForks": 2},
    {"Parallel": True, "MaxWorkers": 1},
]


@pytest.fixture(scope="module")
def workers_below_forks_batch(gradle_batch_env):
    base, _root, source = gradle_batch_env
    cases = [
        (_case_key(options), _invocation_setup(options) + """
try { $null = Resolve-InvocationGradleSettings; @{ outcome = 'accepted' } | ConvertTo-Json }
catch { @{ outcome = $_.Exception.Message } | ConvertTo-Json }
""")
        for options in WORKERS_BELOW_FORKS_OPTIONS
    ]
    return _run_batch(base, source, cases)


@pytest.mark.parametrize("options", WORKERS_BELOW_FORKS_OPTIONS)
def test_explicit_workers_below_test_forks_are_rejected_not_silently_raised(workers_below_forks_batch, options):
    result = workers_below_forks_batch[_case_key(options)]
    assert result == {"outcome": "E_TEST_FORKS_EXCEED_WORKERS"}


OUT_OF_BOUNDS_ARGUMENTS = [
    "-Workers 0", "-Workers 17", "-Forks 0", "-Workers 16 -Forks 9",
    "-RecycleAfter -1", "-RecycleAfter 1001", "-CompilerStrategy 'unbounded'",
]


@pytest.fixture(scope="module")
def out_of_bounds_batch(tmp_path_factory):
    cases = [
        (argument, f"""
try {{ $null = New-GradleSettings {argument}; @{{ accepted = $true }} | ConvertTo-Json }}
catch {{ @{{ accepted = $false; exception_type = $_.Exception.GetType().Name }} | ConvertTo-Json }}
""")
        for argument in OUT_OF_BOUNDS_ARGUMENTS
    ]
    return _run_batch(tmp_path_factory.mktemp("out-of-bounds-batch"), _functions("New-GradleSettings"), cases)


@pytest.mark.parametrize("argument", OUT_OF_BOUNDS_ARGUMENTS)
def test_new_gradle_settings_rejects_out_of_bounds_controls(out_of_bounds_batch, argument):
    result = out_of_bounds_batch[argument]
    assert result["accepted"] is False
    assert result["exception_type"].startswith("ParameterBinding")


NON_GRADLE_DEFAULT_OPTIONS = [
    {}, {"MaxWorkers": 1}, {"TestForks": 1}, {"ForkEvery": 50},
    {"Parallel": False}, {"GradleDaemon": False}, {"KotlinCompilerStrategy": "project"},
]


@pytest.fixture(scope="module")
def non_gradle_options_batch(tmp_path_factory):
    cases = [
        (_case_key(profile, options), _invocation_setup(options, profile) + """
try {
    $settings = Resolve-InvocationGradleSettings
    @{ outcome = if ($null -eq $settings) { 'not-applicable' } else { 'unexpected-settings' } } | ConvertTo-Json
} catch { @{ outcome = $_.Exception.Message } | ConvertTo-Json }
""")
        for profile in NON_GRADLE_PROFILES
        for options in NON_GRADLE_DEFAULT_OPTIONS
    ]
    source = _functions("Test-GradleProfile", "Resolve-InvocationGradleSettings")
    return _run_batch(tmp_path_factory.mktemp("non-gradle-options-batch"), source, cases)


@pytest.mark.parametrize("profile", NON_GRADLE_PROFILES)
@pytest.mark.parametrize("options", NON_GRADLE_DEFAULT_OPTIONS)
def test_non_gradle_profiles_reject_even_explicit_default_options(non_gradle_options_batch, profile, options):
    result = non_gradle_options_batch[_case_key(profile, options)]
    assert result == {
        "outcome": "E_GRADLE_OPTIONS_NOT_APPLICABLE" if options else "not-applicable",
    }


OLD_REQUEST_PROFILES = ["compile", "targeted-release-unit-test", *NON_GRADLE_PROFILES]


@pytest.fixture(scope="module")
def old_requests_batch(tmp_path_factory):
    cases = [
        (profile, f"""
$request = {_ps_json({'profile': profile})}
@{{ settings = (Resolve-RequestGradleSettings -Request $request) }} | ConvertTo-Json -Depth 8
""")
        for profile in OLD_REQUEST_PROFILES
    ]
    source = _functions("Test-GradleProfile", "New-GradleSettings", "Resolve-RequestGradleSettings")
    return _run_batch(tmp_path_factory.mktemp("old-requests-batch"), source, cases)


@pytest.mark.parametrize("profile", OLD_REQUEST_PROFILES)
def test_old_requests_without_gradle_settings_keep_historical_defaults(old_requests_batch, profile):
    result = old_requests_batch[profile]
    assert result["settings"] == (None if profile in NON_GRADLE_PROFILES else DEFAULT_GRADLE_SETTINGS)


NEW_REQUEST_SETTINGS_CASES = [
    (DEFAULT_GRADLE_SETTINGS, DEFAULT_GRADLE_SETTINGS),
    (_settings(fork_every=0, parallel=True, gradle_daemon=True, kotlin_compiler_strategy="Daemon"),
     _settings(fork_every=0, parallel=True, gradle_daemon=True, kotlin_compiler_strategy="daemon")),
    (_settings(max_workers=16, test_forks=8, fork_every=1000, kotlin_compiler_strategy="IN-PROCESS"),
     _settings(max_workers=16, test_forks=8, fork_every=1000, kotlin_compiler_strategy="in-process")),
]


@pytest.fixture(scope="module")
def new_request_settings_batch(tmp_path_factory):
    cases = [
        (_case_key(settings), f"""
$request = {_ps_json({'profile': 'compile', 'gradle_settings': settings})}
@{{ settings = (Resolve-RequestGradleSettings -Request $request) }} | ConvertTo-Json -Depth 8
""")
        for settings, _expected in NEW_REQUEST_SETTINGS_CASES
    ]
    source = _functions("Test-GradleProfile", "New-GradleSettings", "Resolve-RequestGradleSettings")
    return _run_batch(tmp_path_factory.mktemp("new-request-settings-batch"), source, cases)


@pytest.mark.parametrize("settings,expected", NEW_REQUEST_SETTINGS_CASES)
def test_new_request_settings_preserve_counts_false_zero_and_normalize_strategy(new_request_settings_batch, settings, expected):
    result = new_request_settings_batch[_case_key(settings)]
    assert result["settings"] == expected


def _malformed_settings_cases():
    cases = [pytest.param(value, "E_GRADLE_SETTINGS_INVALID", id=name) for name, value in [
        ("null", None), ("empty-object", {}), ("array", []), ("string", "defaults"),
    ]]
    for key in DEFAULT_GRADLE_SETTINGS:
        missing = _settings()
        del missing[key]
        cases.append(pytest.param(missing, "E_GRADLE_SETTINGS_INVALID", id=f"missing-{key}"))
        cases.append(pytest.param(_settings(**{key: None}), "E_GRADLE_SETTINGS_INVALID", id=f"null-{key}"))
    for key in ("max_workers", "test_forks", "fork_every"):
        for label, value in (("bool", True), ("string", "1"), ("fraction", 1.5), ("integral-float", 1.0), ("array", [1])):
            cases.append(pytest.param(_settings(**{key: value}), "E_GRADLE_SETTINGS_INVALID", id=f"{key}-{label}"))
    for key in ("parallel", "gradle_daemon"):
        for label, value in (("string", "false"), ("integer", 0), ("array", [False])):
            cases.append(pytest.param(_settings(**{key: value}), "E_GRADLE_SETTINGS_INVALID", id=f"{key}-{label}"))
    for value in (True, 1, ["daemon"]):
        cases.append(pytest.param(_settings(kotlin_compiler_strategy=value), "E_GRADLE_SETTINGS_INVALID", id=f"strategy-type-{type(value).__name__}"))
    for key, value in (
        ("max_workers", 0), ("max_workers", 17), ("test_forks", 0),
        ("test_forks", 9), ("fork_every", -1), ("fork_every", 1001),
        ("kotlin_compiler_strategy", "out-of-process"),
    ):
        cases.append(pytest.param(_settings(**{key: value}), None, id=f"range-{key}-{value}"))
    cases.append(pytest.param(_settings(test_forks=2), "E_TEST_FORKS_EXCEED_WORKERS", id="forks-exceed-workers"))
    return cases


MALFORMED_SETTINGS_CASES = _malformed_settings_cases()


@pytest.fixture(scope="module")
def malformed_settings_batch(tmp_path_factory):
    cases = [
        (case.id, f"""
$request = {_ps_json({'profile': 'compile', 'gradle_settings': case.values[0]})}
try {{
    $null = Resolve-RequestGradleSettings -Request $request
    @{{ accepted = $true }} | ConvertTo-Json
}} catch {{
    @{{ accepted = $false; code = $_.Exception.Message; exception_type = $_.Exception.GetType().Name }} | ConvertTo-Json
}}
""")
        for case in MALFORMED_SETTINGS_CASES
    ]
    source = _functions("Test-GradleProfile", "New-GradleSettings", "Resolve-RequestGradleSettings")
    return _run_batch(tmp_path_factory.mktemp("malformed-settings-batch"), source, cases)


@pytest.mark.parametrize("settings,expected_code", MALFORMED_SETTINGS_CASES)
def test_request_settings_fail_closed_on_missing_null_wrong_types_and_ranges(malformed_settings_batch, request, settings, expected_code):
    result = malformed_settings_batch[request.node.callspec.id]
    assert result["accepted"] is False
    if expected_code is not None:
        assert result["code"] == expected_code
    else:
        assert result["exception_type"].startswith("ParameterBinding")


NON_GRADLE_REQUEST_SETTINGS = [None, DEFAULT_GRADLE_SETTINGS]


@pytest.fixture(scope="module")
def non_gradle_request_batch(tmp_path_factory):
    cases = [
        (_case_key(profile, settings), f"""
$request = {_ps_json({'profile': profile, 'gradle_settings': settings})}
try {{
    $settings = Resolve-RequestGradleSettings -Request $request
    @{{ outcome = if ($null -eq $settings) {{ 'not-applicable' }} else {{ 'unexpected' }} }} | ConvertTo-Json
}} catch {{ @{{ outcome = $_.Exception.Message }} | ConvertTo-Json }}
""")
        for profile in NON_GRADLE_PROFILES
        for settings in NON_GRADLE_REQUEST_SETTINGS
    ]
    source = _functions("Test-GradleProfile", "Resolve-RequestGradleSettings")
    return _run_batch(tmp_path_factory.mktemp("non-gradle-request-batch"), source, cases)


@pytest.mark.parametrize("profile", NON_GRADLE_PROFILES)
@pytest.mark.parametrize("settings", NON_GRADLE_REQUEST_SETTINGS)
def test_non_gradle_request_settings_only_accept_null(non_gradle_request_batch, profile, settings):
    result = non_gradle_request_batch[_case_key(profile, settings)]
    assert result == {"outcome": "not-applicable" if settings is None else "E_GRADLE_OPTIONS_NOT_APPLICABLE"}


@pytest.mark.parametrize("options,duplicate", [
    ({"MaxWorkers": 4}, True),
    ({"MaxWorkers": 5}, False),
    ({"MaxWorkers": 4, "TestForks": 2}, False),
    ({"MaxWorkers": 4, "ForkEvery": 0}, False),
    ({"MaxWorkers": 4, "TestForks": 1, "Parallel": True}, False),
    ({"MaxWorkers": 4, "GradleDaemon": True}, False),
    ({"MaxWorkers": 4, "KotlinCompilerStrategy": "Daemon"}, False),
    ({"MaxWorkers": 4, "KotlinCompilerStrategy": "in-process"}, False),
    ({"MaxWorkers": 4, "KotlinCompilerStrategy": "PROJECT"}, True),
])
def test_dedup_uses_commands_that_distinguish_every_effective_gradle_setting(tmp_path, options, duplicate):
    root, source = _gradle_fixture(tmp_path)
    run = root / "build" / "validation-runs" / "vr-20260101-000000-deadbeef"
    run.mkdir()
    baseline = _command_display("gradlew.bat", _gradle_arguments("compile", _settings(max_workers=4)))
    (run / "result.json").write_text(json.dumps({
        "profile": "compile", "status": "PASS", "exit_code": 0,
        "command": baseline, "git_revision": "revision-a",
        "worktree_fingerprint_start": "fingerprint",
        "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    source += _functions("Find-OverlappingSuccessfulRun") + "\n" + _invocation_setup(options)
    result = _powershell_json(tmp_path, source + """
$settings = Resolve-InvocationGradleSettings
$spec = Get-ProfileSpec -Name $Profile -GradleSettings $settings
$command = Get-SafeCommandDisplay -Spec $spec
$match = Find-OverlappingSuccessfulRun -RequestedProfile $Profile -RequestedCommand $command -Fingerprint 'fingerprint' -Revision 'revision-a'
@{ duplicate = $null -ne $match } | ConvertTo-Json
""")
    assert result == {"duplicate": duplicate}


def _detached_worker_case(
    tmp_path, options=None, request_patch="", profile="compile", record_patch="",
    test_filter="*ProtocolCase*", parent_env=None, child_stdout=None, child_exit=0,
    parent_files=None, real_config_scan=False,
):
    # parent_env is set in the starter's environment, so it reaches the child
    # only through the real Start-Process -> detached worker -> Process.Start chain.
    # child_stdout (printed after the harmless line; "\n" and "\x1b" are decoded)
    # and child_exit make the fake child imitate a pytest run. parent_files are
    # written beside the fixture repository root, i.e. above it.
    # The upward pytest config scan is neutralised unless real_config_scan is set,
    # so the other fixtures do not depend on the machine's ancestor directories.
    environment = dict(parent_env or {})
    if child_stdout is not None:
        environment["PROTOCOL_FAKE_STDOUT"] = child_stdout
    if child_exit:
        environment["PROTOCOL_FAKE_EXIT"] = str(child_exit)
    root, source = _gradle_fixture(tmp_path)
    for name, text in (parent_files or {}).items():
        (root.parent / name).write_text(text, encoding="utf-8")
    (root / "scripts" / "ci").mkdir(parents=True)
    (root / "scripts" / "ci" / "test_guard_findings.py").write_text("", encoding="utf-8")
    capture = root / "capture argv.py"
    capture.write_text(r"""import json
import os
import sys
import time
from pathlib import Path

run = Path(os.environ["PROTOCOL_RUN_DIRECTORY"])
for attempt in range(100):
    try:
        record = json.loads((run / "result.json").read_text(encoding="utf-8-sig"))
        break
    except (OSError, ValueError):
        time.sleep(0.01)
else:
    raise RuntimeError("fixture launch metadata unreadable")
(run / "executed.json").write_text(json.dumps({
    "argv": sys.argv[1:], "cwd": os.getcwd(), "pid": os.getpid(),
    "android_serial": os.environ.get("ANDROID_SERIAL"),
    "pytest_addopts": os.environ.get("PYTEST_ADDOPTS"),
    "pytest_plugins": os.environ.get("PYTEST_PLUGINS"),
    "pytest_disable_autoload": os.environ.get("PYTEST_DISABLE_PLUGIN_AUTOLOAD"),
    "worker_pid": int(os.environ["PROTOCOL_WORKER_PID"]),
    "record_at_launch": record,
}), encoding="utf-8")
print("harmless argv capture; no Gradle")
fake = os.environ.get("PROTOCOL_FAKE_STDOUT")
if fake is not None:
    print(fake.replace("\\n", "\n").replace("\\x1b", "\x1b"))
sys.exit(int(os.environ.get("PROTOCOL_FAKE_EXIT", "0")))
""", encoding="utf-8")
    source += "\n" + _functions(
        "Get-UtcTimestamp", "Write-JsonAtomic", "Get-RunDirectory", "New-LockFile",
        "ConvertTo-ProcessArguments", "Find-OverlappingSuccessfulRun",
        "Start-ValidationRun", "Invoke-ValidationWorker", "Assert-SafePytestFilter",
        "Test-PytestProfile", "Test-PytestSummaryProfile", "Set-PytestChildEnvironment",
        "Test-PytestConfigOutsideRepo", "Get-PytestPassedCount",
    )
    if not real_config_scan:
        source += "\nfunction Test-PytestConfigOutsideRepo { param($Root, $StopAt) return $false }\n"
    # Keep command construction and both settings resolvers real. Replace only
    # the executable transport, external-process probing, and git fingerprint.
    # Start-Process and the worker's ProcessStartInfo/Process.Start are NOT mocked.
    source += f"""
$script:OriginalProfileSpec = ${{function:Get-ProfileSpec}}
function Get-ProfileSpec {{
    param($Name, $Filter, $ShardName, $SelectedGuard, $RequestedPython, $GradleSettings = $null, $SelectedSerial)
    $spec = & $script:OriginalProfileSpec @PSBoundParameters
    $spec.executable = {_ps_quote(sys.executable)}
    $spec.arguments = @({_ps_quote(capture)}) + @($spec.arguments)
    return $spec
}}
function Resolve-PythonCommand {{ param($Requested) return {_ps_quote(sys.executable)} }}
function Test-ExternalGradleClient {{ return $false }}
function Get-WorktreeFingerprint {{ return 'isolated-fingerprint' }}
function Get-GitRevision {{ return 'isolated-revision' }}
function Resolve-ActiveLock {{ return Read-JsonFile -Path $script:LockPath }}
$script:ScriptPath = $PSCommandPath
if ($Action -eq 'Start') {{
{_invocation_setup(options or {}, profile)}
    $TestFilter = {_ps_quote(test_filter)}
    $Shard = 'protocol-shard'
    $GuardId = 'protocol_guard'
    $PythonExecutable = ''
    $TimeoutSeconds = 20
    $NoOutputTimeoutSeconds = 10
    $AllowOverlap = $false
    $OverlapReasonCode = ''
    $started = Start-ValidationRun | ConvertFrom-Json
    $record = Read-JsonFile -Path (Join-Path (Get-RunDirectory -Id $started.run_id) 'result.json')
    $worker = Get-Process -Id $record.worker_pid -ErrorAction SilentlyContinue
    try {{
        if ($null -ne $worker -and -not $worker.WaitForExit(30000)) {{ throw 'Detached fixture timed out' }}
    }} finally {{
        if ($null -ne $worker -and -not $worker.HasExited) {{
            Stop-Process -InputObject $worker -Force -ErrorAction SilentlyContinue
        }}
    }}
    $started | Add-Member -NotePropertyName starter_pid -NotePropertyValue $PID
    $started | ConvertTo-Json -Depth 8
    return
}}
if ($Action -ne 'Worker') {{ throw 'Fixture action invalid' }}
$run = Get-RunDirectory -Id $RunId
$recordPath = Join-Path $run 'result.json'
# Wait for the real parent handoff before snapshotting or corrupting fixtures.
$owned = $false
for ($attempt = 0; $attempt -lt 200; $attempt++) {{
    $record = Read-JsonFile -Path $recordPath
    $lock = Read-JsonFile -Path $script:LockPath
    if ($null -ne $lock -and $lock.run_id -eq $RunId -and
        $lock.worker_pid -eq $PID -and $record.worker_pid -eq $PID) {{ $owned = $true; break }}
    Start-Sleep -Milliseconds 50
}}
if (-not $owned) {{ throw 'Fixture parent handoff missing' }}
$request = Read-JsonFile -Path (Join-Path $run 'request.json')
Write-JsonAtomic -Path (Join-Path $run 'start-request.json') -Value $request
Write-JsonAtomic -Path (Join-Path $run 'start-result.json') -Value $record
{request_patch}
Write-JsonAtomic -Path (Join-Path $run 'request.json') -Value $request
# Cached metadata is not authoritative: the worker must replace it before launch.
$record.command = 'untrusted cached metadata'
$record.gradle_settings = $null
{record_patch}
Write-JsonAtomic -Path $recordPath -Value $record
Write-JsonAtomic -Path (Join-Path $run 'worker-input-result.json') -Value $record
$env:PROTOCOL_RUN_DIRECTORY = $run
$env:PROTOCOL_WORKER_PID = [string]$PID
# A fresh worker must not re-resolve settings from these unrelated globals.
{_invocation_setup({'MaxWorkers': 13, 'TestForks': 7, 'ForkEvery': 999, 'Parallel': True, 'GradleDaemon': True, 'KotlinCompilerStrategy': 'in-process'})}
Invoke-ValidationWorker
"""
    shim = root / "isolated runner.ps1"
    shim.write_text(
        "param([string]$Action, [string]$RunId)\n"
        "Set-StrictMode -Version Latest\n$ErrorActionPreference = 'Stop'\n" + source,
        encoding="utf-8-sig",
    )
    started = _powershell_json(
        tmp_path, f"& {_ps_quote(shim)} -Action Start", timeout=45, env=environment or None,
    )
    run = root / "build" / "validation-runs" / started["run_id"]
    assert run.parent.parent.parent == root
    assert (run / "complete.marker").is_file(), started
    assert not (run.parent / "active.lock.json").exists()
    records = {
        name: json.loads((run / f"{name}.json").read_text(encoding="utf-8-sig"))
        for name in ("request", "result", "start-request", "start-result", "worker-input-result")
    }
    executed = run / "executed.json"
    records["executed"] = json.loads(executed.read_text(encoding="utf-8")) if executed.exists() else None
    return root, run, capture, started, records


@pytest.mark.parametrize("options,expected", TUNING_CASES)
def test_start_serialization_survives_fresh_detached_worker_and_actual_argv_capture(tmp_path, options, expected):
    root, run, capture, started, records = _detached_worker_case(tmp_path, options)
    arguments = _gradle_arguments("compile", expected)
    command = _command_display(sys.executable, [str(capture), *arguments])
    for name in ("start-request", "start-result", "request", "result"):
        assert records[name]["gradle_settings"] == expected
        assert records[name]["command"] == command
    assert records["start-result"]["status"] == "STARTING"
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("PASS", 0, None)
    executed = records["executed"]
    assert executed["argv"] == arguments
    assert executed["cwd"] == str(root)
    assert result["child_pid"] == executed["pid"]
    assert result["worker_pid"] == executed["worker_pid"]
    assert len({started["starter_pid"], result["worker_pid"], executed["pid"]}) == 3
    assert executed["record_at_launch"]["status"] == "RUNNING"
    assert executed["record_at_launch"]["command"] == command
    assert executed["record_at_launch"]["gradle_settings"] == expected
    assert "status=PASS" in (run / "complete.marker").read_text(encoding="utf-8-sig")
    assert "harmless argv capture; no Gradle" in (run / "stdout.log").read_text(encoding="utf-8")


def test_detached_worker_accepts_legacy_request_without_settings_or_command(tmp_path):
    request_patch = """
$request.PSObject.Properties.Remove('gradle_settings')
$request.PSObject.Properties.Remove('command')
"""
    record_patch = """
$record.PSObject.Properties.Remove('command')
$record.PSObject.Properties.Remove('gradle_settings')
"""
    _, _, capture, _, records = _detached_worker_case(
        tmp_path, {"Parallel": True, "GradleDaemon": True},
        request_patch=request_patch, record_patch=record_patch,
    )
    arguments = _gradle_arguments("compile", DEFAULT_GRADLE_SETTINGS)
    assert "gradle_settings" not in records["request"]
    assert "command" not in records["request"]
    assert "gradle_settings" not in records["worker-input-result"]
    assert "command" not in records["worker-input-result"]
    assert records["result"]["status"] == "PASS"
    assert records["result"]["gradle_settings"] == DEFAULT_GRADLE_SETTINGS
    assert records["result"]["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert records["executed"]["argv"] == arguments


@pytest.mark.parametrize("profile", [
    "benchmark-compile", "benchmark-test-shard", "benchmark-app-check",
])
def test_benchmark_profiles_survive_detached_worker_without_excluding_tasks(tmp_path, profile):
    options = {"GradleDaemon": True, "MaxWorkers": 4, "TestForks": 2}
    settings = _settings(gradle_daemon=True, max_workers=4, test_forks=2)
    _, run, _, _, records = _detached_worker_case(tmp_path, options, profile=profile)
    assert records["result"]["status"] == "PASS"
    argv = records["executed"]["argv"]
    assert argv == _gradle_arguments(profile, settings)
    assert "--rerun" in argv
    assert "--no-build-cache" in argv
    assert "--profile" in argv
    assert "-x" not in argv and "--exclude-task" not in argv
    if profile == "benchmark-app-check":
        assert argv[:5] == [
            ":app:testDebugUnitTest", "--rerun",
            ":app:testReleaseUnitTest", "--rerun", ":app:check",
        ]
    assert "status=PASS" in (run / "complete.marker").read_text(encoding="utf-8-sig")


def test_detached_non_gradle_worker_preserves_null_settings_and_executes_only_fake_command(tmp_path):
    _, _, capture, _, records = _detached_worker_case(tmp_path, profile="static-guards")
    arguments = ["scripts/ci/run_static_guard_suite.py", "--output-dir", "build/ci/static-guards"]
    command = _command_display(sys.executable, [str(capture), *arguments])
    for name in ("start-request", "start-result", "result"):
        assert records[name]["gradle_settings"] is None
        assert records[name]["command"] == command
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments
    assert records["executed"]["record_at_launch"]["gradle_settings"] is None


@pytest.mark.parametrize("patch", [
    "$request.command += ' --unexpected'",
    "$request.command = $request.command.ToUpperInvariant()",
    "$request.PSObject.Properties.Remove('command')",
    "$request.command = $null",
    "$request.command = ''",
    "$request.command = 123",
])
@pytest.mark.parametrize("profile", ["compile", "static-guards"])
def test_detached_worker_rejects_mismatched_or_missing_new_request_command_before_launch(tmp_path, patch, profile):
    _, run, capture, _, records = _detached_worker_case(tmp_path, request_patch=patch, profile=profile)
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, "E_COMMAND_MISMATCH")
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert result["finished_at"]
    assert result["gradle_settings"] == (DEFAULT_GRADLE_SETTINGS if profile == "compile" else None)
    arguments = (_gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS) if profile == "compile" else
                 ["scripts/ci/run_static_guard_suite.py", "--output-dir", "build/ci/static-guards"])
    assert result["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert "status=INFRA_FAILURE" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("patch,profile,code", [
    ("$request.gradle_settings = $null", "compile", "E_GRADLE_SETTINGS_INVALID"),
    ("$request.gradle_settings.max_workers = '4'", "compile", "E_GRADLE_SETTINGS_INVALID"),
    ("$request.gradle_settings.parallel = 'false'", "compile", "E_GRADLE_SETTINGS_INVALID"),
    ("$request.gradle_settings.test_forks = 2", "compile", "E_TEST_FORKS_EXCEED_WORKERS"),
    ("$request.gradle_settings.fork_every = 1001", "compile", "E_COMMAND_LAUNCH_FAILED"),
    ("$request.gradle_settings = [pscustomobject]@{ max_workers = 1 }", "static-guards", "E_GRADLE_OPTIONS_NOT_APPLICABLE"),
])
def test_detached_worker_finalizes_malformed_settings_as_infra_without_launch(tmp_path, patch, profile, code):
    _, run, _, _, records = _detached_worker_case(tmp_path, request_patch=patch, profile=profile)
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, code)
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert result["finished_at"]
    assert result["worktree_fingerprint_end"] == "isolated-fingerprint"
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


# --- D-22 / B-2: connected-test-class, -DeviceSerial, assemble-release, evidence ---

def _serial_arguments(serial):
    return [f"-PvalidationDeviceSerial={serial}"] if serial else []


# Every (profile, filter, serial) spec evaluation used by a test is registered at
# import time and evaluated by ONE PowerShell process (spec_batch). A spec that a
# test asks for but that was never registered fails closed as "missing".
_SPEC_REGISTRY = {}


def _spec_key(profile, filter_value=None, serial=None):
    return _case_key(profile, filter_value, serial)


def _register_spec(profile, filter_value=None, serial=None):
    _SPEC_REGISTRY[_spec_key(profile, filter_value, serial)] = (profile, filter_value, serial)


@pytest.fixture(scope="module")
def spec_batch(gradle_batch_env):
    base, _root, source = gradle_batch_env
    source += "\nfunction Resolve-PythonCommand { param($Requested) return 'C:\\fake\\python.exe' }\n"
    cases = []
    for key, (profile, filter_value, serial) in _SPEC_REGISTRY.items():
        options = {} if serial is None else {"DeviceSerial": serial}
        cases.append((key, _invocation_setup(options, profile) + f"""
try {{
    $settings = Resolve-InvocationGradleSettings
    $serial = Resolve-InvocationDeviceSerial
    $spec = Get-ProfileSpec -Name $Profile -Filter {_ps_value(filter_value)} -ShardName 'protocol-shard' -GradleSettings $settings -SelectedSerial $serial
    @{{ outcome = 'accepted'; arguments = $spec.arguments; timeout = $spec.timeout; no_output_timeout = $spec.no_output_timeout }} | ConvertTo-Json -Depth 8
}} catch {{ @{{ outcome = $_.Exception.Message }} | ConvertTo-Json }}
"""))
    return _run_batch(base, source, cases, timeout=170)


def _spec_outcome(spec_batch, profile, filter_value=None, serial=None):
    return spec_batch[_spec_key(profile, filter_value, serial)]


_register_spec("connected-test-class", CLASS_FILTER)
_register_spec("connected-tests")


def test_connected_test_class_exact_argv_and_timeouts(spec_batch):
    result = _spec_outcome(spec_batch, "connected-test-class", CLASS_FILTER)
    assert result["outcome"] == "accepted"
    assert result["arguments"] == [
        ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.ProtocolCase",
        "--console=plain", "--no-parallel", "--max-workers=1",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50", "--no-daemon",
    ]
    assert (result["timeout"], result["no_output_timeout"]) == (3600, 900)


CONNECTED_CLASS_ACCEPTED_FILTERS = [
    "com.yourname.expensetracker.feature.Case",
    "com.yourname.expensetracker.A1_b.Case_2",
    "com.yourname.expensetracker._Case",
]
for _filter in CONNECTED_CLASS_ACCEPTED_FILTERS:
    _register_spec("connected-test-class", _filter)


@pytest.mark.parametrize("filter_value", CONNECTED_CLASS_ACCEPTED_FILTERS)
def test_connected_test_class_accepts_package_qualified_classes(spec_batch, filter_value):
    result = _spec_outcome(spec_batch, "connected-test-class", filter_value)
    assert result["outcome"] == "accepted"
    assert result["arguments"][1] == f"-Pandroid.testInstrumentationRunnerArguments.class={filter_value}"


CONNECTED_CLASS_REJECTED_FILTERS = [
    "com.yourname.expensetracker.*",
    "com.yourname.expensetracker.A*",
    "*ProtocolCase*",
    "com.yourname.expensetracker.A,com.yourname.expensetracker.B",
    "com.yourname.expensetracker.A#method",
    "com.yourname.expensetracker.A=b",
    "com.yourname.expensetracker.A B",
    "com.yourname.expensetracker.A'",
    'com.yourname.expensetracker.A"',
    "com.yourname.expensetracker.A$Inner",
    "com.yourname.expensetracker.A\n",
    "com.yourname.expensetracker",
    "com.yourname.expensetracker.",
    "com.yourname.expensetracker..A",
    "com.yourname.expensetracker.1A",
    "com.yourname.expensetrackerX.A",
    "com.other.expensetracker.A",
    "Com.yourname.expensetracker.A",
    "ProtocolCase",
]
for _filter in CONNECTED_CLASS_REJECTED_FILTERS:
    _register_spec("connected-test-class", _filter)


@pytest.mark.parametrize("filter_value", CONNECTED_CLASS_REJECTED_FILTERS)
def test_connected_test_class_rejects_unsafe_or_foreign_filters(spec_batch, filter_value):
    assert _spec_outcome(spec_batch, "connected-test-class", filter_value) == {"outcome": "E_INVALID_TEST_FILTER"}


CONNECTED_CLASS_REQUIRED_FILTERS = [None, ""]
for _filter in CONNECTED_CLASS_REQUIRED_FILTERS:
    _register_spec("connected-test-class", _filter)


@pytest.mark.parametrize("filter_value", CONNECTED_CLASS_REQUIRED_FILTERS)
def test_connected_test_class_requires_a_filter(spec_batch, filter_value):
    assert _spec_outcome(spec_batch, "connected-test-class", filter_value) == {"outcome": "E_TEST_FILTER_REQUIRED"}


def test_connected_tests_default_argv_is_byte_identical_without_device_serial(spec_batch):
    result = _spec_outcome(spec_batch, "connected-tests")
    assert result["arguments"] == [
        ":app:connectedDebugAndroidTest", "--console=plain", "--no-parallel",
        "--max-workers=1", "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50",
        "--no-daemon",
    ]
    assert (result["timeout"], result["no_output_timeout"]) == (7200, 1200)


# --- B4-P1c: comma-separated -TestFilter for the targeted unit-test profiles ---

TARGETED_PROFILES = {
    "targeted-unit-test": ":app:testDebugUnitTest",
    "targeted-release-unit-test": ":app:testReleaseUnitTest",
}


def _targeted_expected(task, items):
    tail = _gradle_arguments("compile", DEFAULT_GRADLE_SETTINGS)[1:]
    arguments = [task]
    for item in items:
        arguments += ["--tests", item]
    return arguments + tail


TARGETED_ORDER_CASES = [
    ("*ProtocolCase*", ["*ProtocolCase*"]),
    ("com.example.A", ["com.example.A"]),
    ("*A*,*B*", ["*A*", "*B*"]),
    ("com.example.Z,com.example.A.method,*M?*,a$b-c", ["com.example.Z", "com.example.A.method", "*M?*", "a$b-c"]),
]
for _profile in TARGETED_PROFILES:
    for _filter, _items in TARGETED_ORDER_CASES:
        _register_spec(_profile, _filter)
    _register_spec(_profile, "*ProtocolCase*")
    _register_spec(_profile, "*a*,*A*")


@pytest.mark.parametrize("profile,task", TARGETED_PROFILES.items())
@pytest.mark.parametrize("filter_value,items", TARGETED_ORDER_CASES)
def test_targeted_profiles_emit_one_tests_flag_per_filter_in_order(spec_batch, profile, task, filter_value, items):
    result = _spec_outcome(spec_batch, profile, filter_value)
    assert result["outcome"] == "accepted"
    assert result["arguments"] == _targeted_expected(task, items)
    assert (result["timeout"], result["no_output_timeout"]) == (2400, 600)


@pytest.mark.parametrize("profile,task", TARGETED_PROFILES.items())
def test_targeted_profiles_single_filter_argv_is_unchanged(spec_batch, profile, task):
    result = _spec_outcome(spec_batch, profile, "*ProtocolCase*")
    assert result["arguments"] == GRADLE_PROFILE_ARGUMENTS[profile] + _gradle_arguments("compile", DEFAULT_GRADLE_SETTINGS)[1:]


TARGETED_MALFORMED_FILTERS = [
    "a,,b",
    ",a",
    "a,",
    ",",
    "a,a",
    "*A*,*B*,*A*",
    "a, b",
    "a ,b",
    "a b",
    "a,b#c",
    "a,b;c",
    "a,b/c",
    "a,b'c",
    "a,b=c",
    "",
    None,
]
FIFTY_FILTERS = ",".join(f"*Case{index}*" for index in range(50))
FIFTY_ONE_FILTERS = ",".join(f"*Case{index}*" for index in range(51))
FIFTY_ONE_WITH_EMPTY_FILTERS = ",".join(["*Case0*", ""] + [f"*Case{index}*" for index in range(1, 50)])
for _profile in TARGETED_PROFILES:
    for _filter in (*TARGETED_MALFORMED_FILTERS, FIFTY_FILTERS, FIFTY_ONE_FILTERS, FIFTY_ONE_WITH_EMPTY_FILTERS):
        _register_spec(_profile, _filter)


@pytest.mark.parametrize("profile", TARGETED_PROFILES)
@pytest.mark.parametrize("filter_value", TARGETED_MALFORMED_FILTERS)
def test_targeted_profiles_reject_malformed_filter_lists(spec_batch, profile, filter_value):
    assert _spec_outcome(spec_batch, profile, filter_value) == {"outcome": "E_INVALID_TEST_FILTER"}


@pytest.mark.parametrize("profile", TARGETED_PROFILES)
def test_targeted_profiles_enforce_the_fifty_filter_limit(spec_batch, profile):
    assert _spec_outcome(spec_batch, profile, FIFTY_FILTERS)["outcome"] == "accepted"
    assert _spec_outcome(spec_batch, profile, FIFTY_ONE_FILTERS) == {"outcome": "E_TOO_MANY_TEST_FILTERS"}


@pytest.mark.parametrize("profile", TARGETED_PROFILES)
def test_targeted_profiles_report_too_many_filters_before_per_item_validation(spec_batch, profile):
    assert len(FIFTY_ONE_WITH_EMPTY_FILTERS.split(",")) == 51
    assert _spec_outcome(spec_batch, profile, FIFTY_ONE_WITH_EMPTY_FILTERS) == {"outcome": "E_TOO_MANY_TEST_FILTERS"}


@pytest.mark.parametrize("profile,task", TARGETED_PROFILES.items())
def test_targeted_profiles_duplicate_check_is_case_sensitive(spec_batch, profile, task):
    result = _spec_outcome(spec_batch, profile, "*a*,*A*")
    assert result["outcome"] == "accepted"
    assert result["arguments"] == _targeted_expected(task, ["*a*", "*A*"])


TRAILING_NEWLINE_EXPRESSIONS = [
    "'*A*' + \"`n\"",
    "'*A*,*B*' + \"`n\"",
    "'*A*,*B*' + \"`r`n\"",
]


@pytest.fixture(scope="module")
def trailing_newline_batch(gradle_batch_env):
    base, _root, source = gradle_batch_env
    cases = [
        (filter_expression, _invocation_setup({}, "targeted-unit-test") + f"""
try {{
    $spec = Get-ProfileSpec -Name $Profile -Filter ({filter_expression}) -GradleSettings (Resolve-InvocationGradleSettings)
    @{{ outcome = 'accepted' }} | ConvertTo-Json
}} catch {{ @{{ outcome = $_.Exception.Message }} | ConvertTo-Json }}
""")
        for filter_expression in TRAILING_NEWLINE_EXPRESSIONS
    ]
    return _run_batch(base, source, cases)


@pytest.mark.parametrize("filter_expression", TRAILING_NEWLINE_EXPRESSIONS)
def test_targeted_profiles_reject_trailing_newlines(trailing_newline_batch, filter_expression):
    result = trailing_newline_batch[filter_expression]
    assert result == {"outcome": "E_INVALID_TEST_FILTER"}


_register_spec("connected-test-class", "com.yourname.expensetracker.A,com.yourname.expensetracker.B")


def test_comma_lists_are_rejected_by_connected_test_class_and_pytest_subset(spec_batch, pytest_subset_batch):
    connected = _spec_outcome(
        spec_batch, "connected-test-class",
        "com.yourname.expensetracker.A,com.yourname.expensetracker.B",
    )
    assert connected == {"outcome": "E_INVALID_TEST_FILTER"}
    pytest_result = _pytest_subset_outcome(
        pytest_subset_batch, "scripts/ci/test_guard_findings.py,scripts/ci/test_guard_findings.py",
    )
    assert pytest_result == {"outcome": "E_INVALID_PYTEST_FILTER"}


def test_targeted_filters_are_parsed_by_one_helper_before_the_lock_and_in_the_worker():
    spec = _functions("Get-ProfileSpec")
    assert spec.count("Get-TargetedTestFilters -Filter $Filter") == 2
    assert "--tests\", $Filter" not in spec
    start = _functions("Start-ValidationRun")
    assert start.index("Get-ProfileSpec") < start.index("New-LockFile")
    worker = _functions("Invoke-ValidationWorker")
    assert "-Filter $request.test_filter" in worker


@pytest.mark.parametrize("profile", TARGETED_PROFILES)
@pytest.mark.parametrize("patch,code", [
    ("$request.test_filter = '*A*,,*B*'", "E_INVALID_TEST_FILTER"),
    ("$request.test_filter = '*A*,*A*'", "E_INVALID_TEST_FILTER"),
    ("$request.test_filter = '*A*,*B* '", "E_INVALID_TEST_FILTER"),
    ("$request.test_filter = '*A*,*B*,'", "E_INVALID_TEST_FILTER"),
    (f"$request.test_filter = {_ps_quote(','.join(f'*C{index}*' for index in range(51)))}", "E_TOO_MANY_TEST_FILTERS"),
    ("$request.test_filter = '*A*'", "E_COMMAND_MISMATCH"),
    ("$request.test_filter = '*B*,*A*'", "E_COMMAND_MISMATCH"),
])
def test_detached_worker_revalidates_tampered_multi_filter_without_launch(tmp_path, profile, patch, code):
    _, run, _, _, records = _detached_worker_case(
        tmp_path, request_patch=patch, profile=profile, test_filter="*A*,*B*",
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, code)
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


def test_detached_worker_launches_multi_filter_with_one_tests_flag_per_item(tmp_path):
    _, run, capture, _, records = _detached_worker_case(tmp_path, profile="targeted-unit-test", test_filter="*A*,*B*")
    arguments = _targeted_expected(":app:testDebugUnitTest", ["*A*", "*B*"])
    assert records["result"]["status"] == "PASS"
    assert records["request"]["test_filter"] == "*A*,*B*"
    assert records["result"]["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert records["executed"]["argv"] == arguments


_register_spec("assemble-release", "ignored-filter")
_register_spec("release-artifact-evidence", "ignored-filter")


def test_assemble_release_argv_and_timeouts(spec_batch):
    result = _spec_outcome(spec_batch, "assemble-release", "ignored-filter")
    assert result["arguments"] == [
        ":app:assembleRelease", "--console=plain", "--no-parallel", "--max-workers=1",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50", "--no-daemon",
    ]
    assert (result["timeout"], result["no_output_timeout"]) == (3600, 900)
    assert "--tests" not in result["arguments"]


def test_release_artifact_evidence_uses_fixed_python_argv_without_free_inputs(spec_batch):
    result = _spec_outcome(spec_batch, "release-artifact-evidence", "ignored-filter")
    assert result["arguments"] == ["scripts/ci/release_artifact_evidence.py"]
    assert (result["timeout"], result["no_output_timeout"]) == (600, 300)


# --- B4-P1: pytest-subset profile (one pytest path under scripts/) ---

# Pinned argv after "-m pytest <filter>": quiet, no cache dir, slowest phases, and
# "-rs" so every skip and its reason is listed in the run log.
PYTEST_SUBSET_FLAGS = ["-q", "-p", "no:cacheprovider", "--durations=40", "-rs"]

# filter_expression is PowerShell source, so values that cannot survive a Python
# text-mode write (for example a bare trailing LF) are built in PowerShell.
# Expressions are registered at import time and evaluated by ONE PowerShell
# process (pytest_subset_batch); an unregistered expression fails as "missing".
_PYTEST_SUBSET_REGISTRY = []


def _register_pytest_subset_expression(filter_expression):
    if filter_expression not in _PYTEST_SUBSET_REGISTRY:
        _PYTEST_SUBSET_REGISTRY.append(filter_expression)


def _register_pytest_subset(filter_value):
    _register_pytest_subset_expression(_ps_value(filter_value))


@pytest.fixture(scope="module")
def pytest_subset_batch(tmp_path_factory):
    base = tmp_path_factory.mktemp("pytest-subset-batch")
    root, source = _gradle_fixture(base)
    (root / "scripts" / "ci").mkdir(parents=True)
    (root / "scripts" / "ci" / "test_guard_findings.py").write_text("", encoding="utf-8")
    source += "\n" + _functions("Assert-SafePytestFilter")
    source += "\nfunction Resolve-PythonCommand { param($Requested) return 'C:\\fake\\python.exe' }\n"
    cases = [
        (expression, _invocation_setup({}, "pytest-subset") + f"""
try {{
    $spec = Get-ProfileSpec -Name $Profile -Filter ({expression})
    @{{ outcome = 'accepted'; arguments = $spec.arguments; timeout = $spec.timeout; no_output_timeout = $spec.no_output_timeout }} | ConvertTo-Json -Depth 8
}} catch {{ @{{ outcome = $_.Exception.Message }} | ConvertTo-Json }}
""")
        for expression in _PYTEST_SUBSET_REGISTRY
    ]
    batch = _run_batch(base, source, cases, timeout=170)
    batch.root = root
    return batch


def _pytest_subset_outcome_expression(pytest_subset_batch, filter_expression):
    return pytest_subset_batch[filter_expression]


def _pytest_subset_outcome(pytest_subset_batch, filter_value):
    return pytest_subset_batch[_ps_value(filter_value)]


_register_pytest_subset("scripts/ci/test_guard_findings.py,scripts/ci/test_guard_findings.py")
PYTEST_SUBSET_ACCEPTED_FILTERS = [
    "scripts",
    "scripts/ci/test_guard_findings.py",
    "scripts\\ci\\test_guard_findings.py",
    "scripts/ci/test_guard_findings.py::test_case[param-1]",
    "scripts/ci/test_guard_findings.py::TestClass::test_case",
]
for _filter in PYTEST_SUBSET_ACCEPTED_FILTERS:
    _register_pytest_subset(_filter)


@pytest.mark.parametrize("filter_value", PYTEST_SUBSET_ACCEPTED_FILTERS)
def test_pytest_subset_accepts_existing_paths_under_scripts_with_exact_argv(pytest_subset_batch, filter_value):
    result = _pytest_subset_outcome(pytest_subset_batch, filter_value)
    assert result["outcome"] == "accepted"
    assert result["arguments"] == ["-m", "pytest", filter_value, *PYTEST_SUBSET_FLAGS]
    assert (result["timeout"], result["no_output_timeout"]) == (2400, 600)


PYTEST_SUBSET_REJECTED_FILTERS = [
    "../x",
    "scripts/../x",
    "scripts/ci/../../x",
    "scripts/./ci",
    "C:/scripts/ci/test_guard_findings.py",
    "C:\\scripts\\ci\\test_guard_findings.py",
    "/scripts/ci/test_guard_findings.py",
    "\\scripts\\ci\\test_guard_findings.py",
    "scripts/x; rm",
    "scripts/ci/test_guard_findings.py;",
    "scripts/ci/test_guard_findings.py && calc",
    "scripts/ci/test_guard_findings.py | more",
    # CRLF case: the probe is written in text mode, so this LF reaches the
    # PowerShell literal as CRLF (the bare-LF case is built in PowerShell below).
    "scripts/ci/test_guard_findings.py\n",
    "scripts/ci test_guard_findings.py",
    "scripts/ci/*.py",
    "scripts/ci/test_?.py",
    "scripts/ci/test_guard_findings.py'",
    'scripts/ci/test_guard_findings.py"',
    "scripts/ci/test_guard_findings.py::",
    "scripts/ci/test_guard_findings.py::test case",
    "scripts/ci/test_guard_findings.py::test_*",
    "scripts/ci/missing.py",
    "scripts/ci::test_case",
    "scriptsX/ci/test_guard_findings.py",
    "Scripts/ci/test_guard_findings.py",
    "app/src/test.py",
    "scripts/",
    "scripts//ci",
    "",
    None,
]
for _filter in PYTEST_SUBSET_REJECTED_FILTERS:
    _register_pytest_subset(_filter)


@pytest.mark.parametrize("filter_value", PYTEST_SUBSET_REJECTED_FILTERS)
def test_pytest_subset_rejects_unsafe_missing_or_out_of_scope_filters(pytest_subset_batch, filter_value):
    assert _pytest_subset_outcome(pytest_subset_batch, filter_value) == {"outcome": "E_INVALID_PYTEST_FILTER"}


PYTEST_SUBSET_OPTION_SHAPED_FILTERS = [
    "-p",
    "--rootdir=scripts",
    "-cscripts",
    "--confcutdir=scripts",
    "@scripts/x",
    # Traversal to a file that really exists in the fixture root (shards.json).
    "scripts/../shards.json",
]
for _filter in PYTEST_SUBSET_OPTION_SHAPED_FILTERS:
    _register_pytest_subset(_filter)


@pytest.mark.parametrize("filter_value", PYTEST_SUBSET_OPTION_SHAPED_FILTERS)
def test_pytest_subset_rejects_option_shaped_and_traversal_filters(pytest_subset_batch, filter_value):
    assert _pytest_subset_outcome(pytest_subset_batch, filter_value) == {"outcome": "E_INVALID_PYTEST_FILTER"}
    # The traversal case targets a file that exists, so rejection is not "missing path".
    assert (pytest_subset_batch.root / "shards.json").is_file()


PYTEST_SUBSET_NEWLINE_EXPRESSIONS = [
    "'scripts/ci/test_guard_findings.py' + \"`n\"",
    "'scripts/ci/test_guard_findings.py' + \"`r`n\"",
    "'scripts' + \"`n\"",
    "'scripts/ci/test_guard_findings.py::test_case' + \"`n\"",
]
for _expression in PYTEST_SUBSET_NEWLINE_EXPRESSIONS:
    _register_pytest_subset_expression(_expression)
    _register_pytest_subset_expression(_expression.split(" + ")[0])


@pytest.mark.parametrize("filter_expression", PYTEST_SUBSET_NEWLINE_EXPRESSIONS)
def test_pytest_subset_pins_the_absolute_end_anchor_against_trailing_newlines(pytest_subset_batch, filter_expression):
    # The value is built inside PowerShell so no text-mode newline translation
    # applies. A '$' anchor would accept a bare trailing LF; only '\z' rejects it.
    assert _pytest_subset_outcome_expression(pytest_subset_batch, filter_expression) == {
        "outcome": "E_INVALID_PYTEST_FILTER",
    }
    # Control: the same value without the trailing newline is accepted.
    base_expression = filter_expression.split(" + ")[0]
    accepted = _pytest_subset_outcome_expression(pytest_subset_batch, base_expression)
    assert accepted["outcome"] == "accepted"
    # The parenthesised expression must bind as the single -Filter value, not be
    # split into extra positional arguments.
    assert accepted["arguments"][2] == base_expression.strip("'")


@pytest.mark.parametrize("test_filter", ["../x", None])
def test_detached_worker_rejects_tampered_pytest_filter_without_launch(tmp_path, test_filter):
    _, run, _, _, records = _detached_worker_case(
        tmp_path, request_patch=f"$request.test_filter = {_ps_value(test_filter)}",
        profile="pytest-subset", test_filter="scripts/ci/test_guard_findings.py",
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == (
        "INFRA_FAILURE", 2, "E_INVALID_PYTEST_FILTER",
    )
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("replacement", [
    "scripts",
    "scripts/ci/test_guard_findings.py::test_other",
    "scripts\\ci\\test_guard_findings.py",
])
def test_detached_worker_rejects_a_different_valid_pytest_filter_as_command_mismatch(tmp_path, replacement):
    _, run, capture, _, records = _detached_worker_case(
        tmp_path, request_patch=f"$request.test_filter = {_ps_quote(replacement)}",
        profile="pytest-subset", test_filter="scripts/ci/test_guard_findings.py",
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == (
        "INFRA_FAILURE", 2, "E_COMMAND_MISMATCH",
    )
    assert result["child_pid"] is None
    assert records["executed"] is None
    # The worker rebuilt the command from the (valid) tampered filter, not the recorded one.
    assert result["command"] == _command_display(sys.executable, [
        str(capture), "-m", "pytest", replacement, *PYTEST_SUBSET_FLAGS,
    ])
    assert records["request"]["command"] != result["command"]
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


def test_detached_worker_launches_pytest_subset_with_exact_argv_preserving_backslashes(tmp_path):
    filter_value = "scripts\\ci\\test_guard_findings.py"
    root, run, capture, _, records = _detached_worker_case(
        tmp_path, profile="pytest-subset", test_filter=filter_value,
        child_stdout="1 passed in 0.01s",
    )
    arguments = ["-m", "pytest", filter_value, *PYTEST_SUBSET_FLAGS]
    command = _command_display(sys.executable, [str(capture), *arguments])
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("PASS", 0, None)
    for name in ("start-request", "request", "start-result", "result"):
        assert records[name]["command"] == command
    assert records["request"]["test_filter"] == filter_value
    executed = records["executed"]
    assert executed["argv"] == [
        "-m", "pytest", "scripts\\ci\\test_guard_findings.py", "-q", "-p", "no:cacheprovider",
        "--durations=40", "-rs",
    ]
    assert executed["cwd"] == str(root)
    assert result["child_pid"] == executed["pid"]
    assert "status=PASS" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("recorded,requested,duplicate", [
    ("scripts/ci/test_guard_findings.py", "scripts/ci/test_guard_findings.py", True),
    ("scripts/ci/test_guard_findings.py", "scripts", False),
    ("scripts/ci/test_guard_findings.py", "scripts/ci/test_guard_findings.py::test_other", False),
    ("scripts/ci/test_guard_findings.py::test_one", "scripts/ci/test_guard_findings.py::test_other", False),
    ("scripts/ci/test_guard_findings.py", "scripts\\ci\\test_guard_findings.py", False),
])
def test_dedup_treats_different_pytest_filters_as_different_runs(tmp_path, recorded, requested, duplicate):
    root, source = _gradle_fixture(tmp_path)
    (root / "scripts" / "ci").mkdir(parents=True)
    (root / "scripts" / "ci" / "test_guard_findings.py").write_text("", encoding="utf-8")
    run = root / "build" / "validation-runs" / "vr-20260101-000000-deadbeef"
    run.mkdir()
    baseline = _command_display("python.exe", ["-m", "pytest", recorded, *PYTEST_SUBSET_FLAGS])
    (run / "result.json").write_text(json.dumps({
        "profile": "pytest-subset", "status": "PASS", "exit_code": 0, "command": baseline,
        "git_revision": "revision-a",
        "worktree_fingerprint_start": "fingerprint", "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    source += "\n" + _functions("Find-OverlappingSuccessfulRun", "Assert-SafePytestFilter")
    source += "\n" + _invocation_setup({}, "pytest-subset")
    result = _powershell_json(tmp_path, source + f"""
function Resolve-PythonCommand {{ param($Requested) return 'C:\\fake\\python.exe' }}
$spec = Get-ProfileSpec -Name $Profile -Filter {_ps_quote(requested)}
$command = Get-SafeCommandDisplay -Spec $spec
$match = Find-OverlappingSuccessfulRun -RequestedProfile $Profile -RequestedCommand $command -Fingerprint 'fingerprint' -Revision 'revision-a'
@{{ duplicate = $null -ne $match }} | ConvertTo-Json
""")
    assert result == {"duplicate": duplicate}


def test_pytest_subset_validates_the_filter_before_the_lock_and_in_the_worker():
    start = _functions("Start-ValidationRun")
    assert start.index("Get-ProfileSpec") < start.index("New-LockFile")
    spec = _functions("Get-ProfileSpec")
    assert spec.index('"pytest-subset"') < spec.index("Assert-SafePytestFilter")
    assert "Assert-SafePytestFilter -Filter $Filter" in spec
    worker = _functions("Invoke-ValidationWorker")
    assert "-Filter $request.test_filter" in worker


VALID_DEVICE_SERIALS = ["emulator-5554", "R5CT1234ABC", "192.168.1.5:5555", "a.b_c-d:e", "A" * 64]
SERIAL_REJECTED_PROFILES = [
    "compile", "assemble-debug", "assemble-release", "targeted-unit-test",
    "unit-tests", "app-check", "lint", "runner-smoke", "static-guards",
    "registered-guard", "release-artifact-evidence", "pytest-subset",
]
INVALID_DEVICE_SERIALS = [
    "", " ", "bad serial", "a;b", "a'b", 'a"b', "a/b", "a\\b", "a&b", "a$b",
    "a=b", "a,b", "A" * 65, "emulator-5554\n", "-Pevil=1 x",
]
for _profile in CONNECTED_PROFILES:
    for _serial in (*VALID_DEVICE_SERIALS, *INVALID_DEVICE_SERIALS):
        _register_spec(_profile, PROFILE_FILTERS.get(_profile), _serial)
for _profile in SERIAL_REJECTED_PROFILES:
    _register_spec(_profile, "*ProtocolCase*", SERIAL)


@pytest.mark.parametrize("profile", CONNECTED_PROFILES)
@pytest.mark.parametrize("serial", VALID_DEVICE_SERIALS)
def test_device_serial_is_accepted_on_both_connected_profiles(spec_batch, profile, serial):
    result = _spec_outcome(spec_batch, profile, PROFILE_FILTERS.get(profile), serial)
    assert result["outcome"] == "accepted"
    # Inert recorded property follows the standard Gradle tail.
    assert result["arguments"][-1] == f"-PvalidationDeviceSerial={serial}"
    assert result["arguments"][:-1] == _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS)


@pytest.mark.parametrize("profile", SERIAL_REJECTED_PROFILES)
def test_device_serial_is_rejected_on_every_other_profile(spec_batch, profile):
    assert _spec_outcome(spec_batch, profile, "*ProtocolCase*", SERIAL) == {
        "outcome": "E_DEVICE_SERIAL_NOT_APPLICABLE",
    }


@pytest.mark.parametrize("serial", INVALID_DEVICE_SERIALS)
@pytest.mark.parametrize("profile", CONNECTED_PROFILES)
def test_invalid_device_serial_is_rejected_on_connected_profiles(spec_batch, profile, serial):
    result = _spec_outcome(spec_batch, profile, PROFILE_FILTERS.get(profile), serial)
    assert result == {"outcome": "E_INVALID_DEVICE_SERIAL"}


def test_omitted_device_serial_is_not_recorded_or_validated(tmp_path):
    _, source = _gradle_fixture(tmp_path)
    result = _powershell_json(tmp_path, source + _invocation_setup({}, "compile") + """
@{ serial = (Resolve-InvocationDeviceSerial) } | ConvertTo-Json
""")
    assert result == {"serial": None}


# --- B4-P1b: pytest profile hardening (ambient env, zero-pass runs, outside config) ---

PYTEST_FILTER = "scripts/ci/test_guard_findings.py"
PYTEST_ENV_PROFILES = ("runner-protocol-tests", "pytest-subset", "static-guards")
PYTEST_SUMMARY_PROFILES = ("runner-protocol-tests", "pytest-subset")
# PYTEST_DISABLE_PLUGIN_AUTOLOAD=0 is set on purpose: the suite itself may run
# under the runner, which already exports "1", and the runner must still set it.
HOSTILE_PYTEST_ENV = {
    "PYTEST_ADDOPTS": "--co",
    "PYTEST_PLUGINS": "protocol_fake_plugin",
    "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "0",
}
COLOUR_SUMMARY = r"\x1b[33m1 skipped\x1b[0m, \x1b[32m2 passed\x1b[0m\x1b[32m in 0.05s\x1b[0m"


@pytest.mark.parametrize("profile", PYTEST_ENV_PROFILES)
def test_pytest_profiles_launch_the_child_without_ambient_pytest_options(tmp_path, profile):
    # PYTEST_ADDOPTS=--co would make pytest exit 0 after running zero tests.
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile=profile, test_filter=PYTEST_FILTER,
        parent_env=HOSTILE_PYTEST_ENV, child_stdout="1 passed in 0.01s",
    )
    executed = records["executed"]
    assert executed["pytest_addopts"] is None
    assert executed["pytest_plugins"] is None
    assert executed["pytest_disable_autoload"] == "1"
    assert records["result"]["status"] == "PASS"


def test_non_pytest_profiles_keep_the_inherited_environment(tmp_path):
    # Scope pin: the sanitising applies only to the profiles that run pytest.
    _, _, _, _, records = _detached_worker_case(tmp_path, profile="compile", parent_env=HOSTILE_PYTEST_ENV)
    executed = records["executed"]
    assert executed["pytest_addopts"] == "--co"
    assert executed["pytest_plugins"] == "protocol_fake_plugin"
    assert executed["pytest_disable_autoload"] == "0"
    assert records["result"]["status"] == "PASS"


@pytest.mark.parametrize("stdout,exit_code,status,failure_code,result_exit", [
    # Everything skipped: pytest exits 0, the runner must not call it a PASS.
    ("3 skipped in 0.01s", 0, "FAIL", "E_NO_TESTS_PASSED", 1),
    # Summary missing entirely (collection-only output, crash after the tests, ...).
    (None, 0, "FAIL", "E_NO_TESTS_PASSED", 1),
    ("2 passed, 1 skipped in 0.01s", 0, "PASS", None, 0),
    ("2 passed in 0.01s", 0, "PASS", None, 0),
    ("=== 2 passed, 1 warning in 1.20s (0:00:01) ===", 0, "PASS", None, 0),
    (COLOUR_SUMMARY, 0, "PASS", None, 0),
    # Non-zero exits keep the historical mapping, whatever the summary says.
    ("1 failed, 2 passed in 0.01s", 1, "FAIL", "E_COMMAND_FAILED", 1),
    ("2 passed in 0.01s", 5, "FAIL", "E_COMMAND_FAILED", 5),
])
def test_pytest_subset_pass_requires_exit_zero_and_at_least_one_passed(
    tmp_path, stdout, exit_code, status, failure_code, result_exit,
):
    _, run, _, _, records = _detached_worker_case(
        tmp_path, profile="pytest-subset", test_filter=PYTEST_FILTER,
        child_stdout=stdout, child_exit=exit_code,
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == (status, result_exit, failure_code)
    marker = (run / "complete.marker").read_text(encoding="utf-8-sig")
    assert f"status={status}" in marker
    assert f"exit_code={result_exit}" in marker
    # The decision comes from the persisted log, not from a recorded exit alone.
    assert "harmless argv capture; no Gradle" in (run / "stdout.log").read_text(encoding="utf-8")


@pytest.mark.parametrize("stdout,status,failure_code", [
    ("3 skipped in 0.01s", "FAIL", "E_NO_TESTS_PASSED"),
    ("2 passed, 1 skipped in 0.01s", "PASS", None),
])
def test_runner_protocol_tests_profile_applies_the_same_zero_pass_rule(tmp_path, stdout, status, failure_code):
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile="runner-protocol-tests", child_stdout=stdout,
    )
    result = records["result"]
    assert (result["status"], result["failure_code"]) == (status, failure_code)


def test_zero_pass_rule_is_not_applied_to_profiles_without_a_pytest_summary(tmp_path):
    # static-guards stdout is the guard suite's, not a pytest summary line.
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile="static-guards", child_stdout="3 skipped in 0.01s",
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("PASS", 0, None)


def test_pytest_summary_parser_reads_only_the_final_summary_line(tmp_path):
    padding = b"filler line\n" * 20000  # > 64 KiB, so only the tail may be read
    cases = {
        "q-form": (b"..\n2 passed in 0.01s\n", 2),
        "decorated": (b"=========== 3 passed, 1 skipped in 0.12s ===========\n", 3),
        "skipped-only": (b"3 skipped in 0.01s\n", 0),
        "deselected-only": (b"1 deselected in 0.01s\n", 0),
        "no-tests-ran": (b"no tests ran in 0.01s\n", 0),
        "xpassed-is-not-passed": (b"1 xpassed in 0.01s\n", 0),
        "subtests-are-not-tests": (b"4 subtests passed in 0.01s\n", 0),
        "passed-plus-subtests": (b"1 passed, 4 subtests passed in 0.01s\n", 1),
        "warnings": (b"12 passed, 1 warning in 3.40s\n", 12),
        "time-suffix": (b"1 passed in 61.00s (0:01:01)\n", 1),
        "crlf": (b"1 passed in 0.01s\r\n", 1),
        "no-final-newline": (b"1 passed in 0.01s", 1),
        "colour": (COLOUR_SUMMARY.replace(r"\x1b", "\x1b").encode("ascii"), 2),
        # Realistic -q layout: the stats line comes AFTER the durations and -rs blocks.
        "durations-then-short-summary-then-stats": (
            b"..s\n"
            b"============================ slowest 3 durations ============================\n"
            b"0.02s call     scripts/x.py::test_a\n"
            b"0.01s setup    scripts/x.py::test_b\n"
            b"0.01s teardown scripts/x.py::test_b\n"
            b"=========================== short test summary info ===========================\n"
            b"SKIPPED [1] scripts/x.py:3: reason\n"
            b"2 passed, 1 skipped in 0.05s\n",
            2,
        ),
        "invalid-utf8-before": (b"\xff\xfe\n1 passed in 0.01s\n", 1),
        "tail-of-large-log": (padding + b"7 passed in 9.00s\n", 7),
        "trailing-blank-lines": (b"1 passed in 0.01s\n\n  \n", 1),
        "noise-after-summary": (b"5 passed in 0.10s\nINTERNALERROR\n", 0),
        "prefixed-spoof": (b"foo 5 passed in 0.10s\n", 0),
        "suffixed-spoof": (b"5 passed in 0.10s extra\n", 0),
        "uppercase": (b"5 PASSED in 0.10s\n", 0),
        "summary-in-earlier-output": (b"5 passed in 0.10s\nsomething else\n", 0),
        "failed-only": (b"1 failed in 0.01s\n", 0),
        "no-summary": (b"collected 3 items\n", 0),
        "empty": (b"", 0),
    }
    logs = {}
    for name, (content, _) in cases.items():
        path = tmp_path / f"{name}.log"
        path.write_bytes(content)
        logs[name] = str(path)
    logs["missing"] = str(tmp_path / "does-not-exist.log")
    cases["missing"] = (b"", 0)
    source = _functions("Get-PytestPassedCount") + f"""
$logs = {_ps_json(logs)}
$counts = [ordered]@{{}}
foreach ($property in $logs.PSObject.Properties) {{
    $counts[$property.Name] = [int](Get-PytestPassedCount -Path $property.Value)
}}
$counts | ConvertTo-Json
"""
    counts = _powershell_json(tmp_path, source, timeout=60)
    assert counts == {name: expected for name, (_, expected) in cases.items()}


def test_pytest_config_search_above_the_repo_root_is_detected(tmp_path):
    layouts = {
        "pytest-ini-empty": ("pytest.ini", "", True),
        "dot-pytest-ini": (".pytest.ini", "", True),
        "pytest-toml": ("pytest.toml", "", True),
        "tox-with-pytest-section": ("tox.ini", "[pytest]\naddopts = --co\n", True),
        "tox-without-pytest-section": ("tox.ini", "[tox]\nenvlist = py\n", False),
        "setup-cfg-with-section": ("setup.cfg", "[tool:pytest]\naddopts = -x\n", True),
        "setup-cfg-without-section": ("setup.cfg", "[metadata]\nname = x\n", False),
        "pyproject-ini-options": ("pyproject.toml", "[tool.pytest.ini_options]\naddopts = '--co'\n", True),
        "pyproject-tool-pytest": ("pyproject.toml", "[tool.pytest]\naddopts = ['--co']\n", True),
        "pyproject-other-tool": ("pyproject.toml", "[tool.black]\nline-length = 1\n", False),
        "pyproject-commented-section": ("pyproject.toml", "# [tool.pytest.ini_options]\n", False),
        "no-config": (None, None, False),
    }
    cases = {}
    for name, (filename, content, _) in layouts.items():
        outer = tmp_path / name / "outer"
        root = outer / "repo"
        root.mkdir(parents=True)
        if filename:
            (outer / filename).write_text(content, encoding="utf-8")
        cases[name] = str(root)
    # A config in the repository root itself is repo-owned, not outside configuration.
    own_root = tmp_path / "repo-owned" / "outer" / "repo"
    own_root.mkdir(parents=True)
    (own_root / "pytest.ini").write_text("[pytest]\n", encoding="utf-8")
    cases["repo-owned-config"] = str(own_root)
    # A config two levels above the repository root is found too.
    grandparent_root = tmp_path / "grandparent" / "mid" / "repo"
    grandparent_root.mkdir(parents=True)
    (tmp_path / "grandparent" / "pytest.ini").write_text("", encoding="utf-8")
    cases["grandparent-config"] = str(grandparent_root)
    # An unreadable candidate (tox.ini is a directory) fails closed.
    unreadable_root = tmp_path / "unreadable" / "outer" / "repo"
    unreadable_root.mkdir(parents=True)
    (tmp_path / "unreadable" / "outer" / "tox.ini").mkdir()
    cases["unreadable-candidate"] = str(unreadable_root)
    source = _functions("Test-PytestConfigOutsideRepo") + f"""
$stop = {_ps_quote(tmp_path)}
$roots = {_ps_json(cases)}
$found = [ordered]@{{}}
foreach ($property in $roots.PSObject.Properties) {{
    $found[$property.Name] = [bool](Test-PytestConfigOutsideRepo -Root $property.Value -StopAt $stop)
}}
$found | ConvertTo-Json
"""
    expected = {name: outcome for name, (_, _, outcome) in layouts.items()}
    expected.update({"repo-owned-config": False, "grandparent-config": True, "unreadable-candidate": True})
    assert _powershell_json(tmp_path, source, timeout=60) == expected


@pytest.mark.parametrize("profile", ["pytest-subset", "static-guards"])
def test_worker_refuses_to_launch_pytest_under_an_outside_config(tmp_path, profile):
    _, run, _, _, records = _detached_worker_case(
        tmp_path, profile=profile, test_filter=PYTEST_FILTER, real_config_scan=True,
        parent_files={"pytest.ini": "[pytest]\naddopts = --co\n"},
        child_stdout="1 passed in 0.01s",
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == (
        "INFRA_FAILURE", 2, "E_PYTEST_CONFIG_OUTSIDE_REPO",
    )
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert "status=INFRA_FAILURE" in (run / "complete.marker").read_text(encoding="utf-8-sig")


def test_outside_pytest_config_does_not_block_non_pytest_profiles(tmp_path):
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile="compile", real_config_scan=True,
        parent_files={"pytest.ini": "[pytest]\n"},
    )
    assert records["result"]["status"] == "PASS"
    assert records["executed"] is not None


def test_start_validates_inputs_before_claiming_the_lock():
    start = _functions("Start-ValidationRun")
    assert (start.index("Resolve-InvocationDeviceSerial") < start.index("Get-ProfileSpec")
            < start.index("New-LockFile"))
    assert "-SelectedSerial $requestedSerial" in start
    worker = _functions("Invoke-ValidationWorker")
    assert 'EnvironmentVariables["ANDROID_SERIAL"] = $requestSerial' in worker


@pytest.mark.parametrize("profile,filter_value,serial,duplicate", [
    ("connected-test-class", CLASS_FILTER, SERIAL, True),
    ("connected-test-class", CLASS_FILTER, None, False),
    ("connected-test-class", CLASS_FILTER, "emulator-5556", False),
    ("connected-test-class", "com.yourname.expensetracker.OtherCase", SERIAL, False),
    ("connected-tests", None, SERIAL, True),
    ("connected-tests", None, None, False),
    ("connected-tests", None, "emulator-5556", False),
])
def test_dedup_distinguishes_connected_class_and_device_serial(
    tmp_path, profile, filter_value, serial, duplicate,
):
    root, source = _gradle_fixture(tmp_path)
    run = root / "build" / "validation-runs" / "vr-20260101-000000-deadbeef"
    run.mkdir()
    baseline = _command_display(
        "gradlew.bat", _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS) + _serial_arguments(SERIAL),
    )
    (run / "result.json").write_text(json.dumps({
        "profile": profile, "status": "PASS", "exit_code": 0, "command": baseline,
        "git_revision": "revision-a",
        "worktree_fingerprint_start": "fingerprint", "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    options = {} if serial is None else {"DeviceSerial": serial}
    source += _functions("Find-OverlappingSuccessfulRun") + "\n" + _invocation_setup(options, profile)
    result = _powershell_json(tmp_path, source + f"""
$settings = Resolve-InvocationGradleSettings
$serial = Resolve-InvocationDeviceSerial
$spec = Get-ProfileSpec -Name $Profile -Filter {_ps_value(filter_value)} -GradleSettings $settings -SelectedSerial $serial
$command = Get-SafeCommandDisplay -Spec $spec
$match = Find-OverlappingSuccessfulRun -RequestedProfile $Profile -RequestedCommand $command -Fingerprint 'fingerprint' -Revision 'revision-a'
@{{ duplicate = $null -ne $match }} | ConvertTo-Json
""")
    assert result == {"duplicate": duplicate}


@pytest.mark.parametrize("profile,filter_value,serial,command_arguments,timeouts,settings", [
    ("connected-test-class", CLASS_FILTER, SERIAL,
     _gradle_arguments("connected-test-class", DEFAULT_GRADLE_SETTINGS) + [f"-PvalidationDeviceSerial={SERIAL}"],
     (3600, 900), DEFAULT_GRADLE_SETTINGS),
    ("connected-tests", None, "R5CT1234ABC",
     _gradle_arguments("connected-tests", DEFAULT_GRADLE_SETTINGS) + ["-PvalidationDeviceSerial=R5CT1234ABC"],
     (7200, 1200), DEFAULT_GRADLE_SETTINGS),
    ("assemble-release", None, None,
     _gradle_arguments("assemble-release", DEFAULT_GRADLE_SETTINGS), (3600, 900), DEFAULT_GRADLE_SETTINGS),
    ("release-artifact-evidence", None, None,
     ["scripts/ci/release_artifact_evidence.py"], (600, 300), None),
])
def test_plan_output_for_new_profiles(
    tmp_path, profile, filter_value, serial, command_arguments, timeouts, settings,
):
    _, source = _gradle_fixture(tmp_path)
    options = {} if serial is None else {"DeviceSerial": serial}
    source += _functions("Show-ValidationPlan") + "\n" + _invocation_setup(options, profile)
    result = _powershell_json(tmp_path, source + f"""
function Resolve-PythonCommand {{ param($Requested) return 'C:\\fake\\python.exe' }}
$TestFilter = {_ps_value(filter_value)}
$Shard = ''
$GuardId = ''
$PythonExecutable = ''
$TimeoutSeconds = 0
$NoOutputTimeoutSeconds = 0
Show-ValidationPlan
""")
    executable = "python.exe" if profile == "release-artifact-evidence" else "gradlew.bat"
    assert result["profile"] == profile
    assert result["command"] == _command_display(executable, command_arguments)
    assert (result["timeout_seconds"], result["no_output_timeout_seconds"]) == timeouts
    assert result["gradle_settings"] == settings
    assert result["device_serial"] == serial


def test_profiles_listing_names_every_profile_accepted_by_the_validate_set():
    source = RUNNER.read_text(encoding="utf-8-sig")
    declared = re.search(r'(?s)\[ValidateSet\(\s*("runner-smoke".*?)\)\]\s*\[string\]\$Profile', source)
    assert declared is not None
    names = re.findall(r'"([a-z0-9-]+)"', declared.group(1))
    for new_profile in ("connected-test-class", "assemble-release", "release-artifact-evidence"):
        assert new_profile in names
    listing = _powershell_listing()
    assert sorted(entry["name"] for entry in listing) == sorted(names)
    assert all(entry["purpose"] for entry in listing)


def _powershell_listing():
    completed = _powershell(_functions("Show-Profiles") + "\nShow-Profiles")
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    return json.loads(completed.stdout)


@pytest.mark.parametrize("profile", [
    "connected-test-class", "assemble-release", "release-artifact-evidence",
])
@pytest.mark.parametrize("patch", [
    "$request.command += ' --unexpected'",
    "$request.command = $request.command.ToUpperInvariant()",
    "$request.PSObject.Properties.Remove('command')",
])
def test_detached_worker_rejects_tampered_request_for_new_profiles(tmp_path, patch, profile):
    _, run, capture, _, records = _detached_worker_case(
        tmp_path, request_patch=patch, profile=profile,
        test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, "E_COMMAND_MISMATCH")
    assert result["child_pid"] is None
    assert records["executed"] is None
    arguments = (["scripts/ci/release_artifact_evidence.py"] if profile == "release-artifact-evidence"
                 else _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS))
    assert result["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert "status=INFRA_FAILURE" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("patch,profile,code", [
    ("$request.device_serial = 'emulator-9999'", "connected-tests", "E_COMMAND_MISMATCH"),
    ("$request.device_serial = $null", "connected-tests", "E_COMMAND_MISMATCH"),
    ("$request.device_serial = 'bad serial;'", "connected-tests", "E_INVALID_DEVICE_SERIAL"),
    ("$request.device_serial = 'bad serial;'", "connected-test-class", "E_INVALID_DEVICE_SERIAL"),
    ("$request.device_serial = 'emulator-5554'", "compile", "E_DEVICE_SERIAL_NOT_APPLICABLE"),
    ("$request.device_serial = 'emulator-5554'", "release-artifact-evidence", "E_DEVICE_SERIAL_NOT_APPLICABLE"),
    ("$request.test_filter = 'com.yourname.expensetracker.*'", "connected-test-class", "E_INVALID_TEST_FILTER"),
    ("$request.test_filter = $null", "connected-test-class", "E_TEST_FILTER_REQUIRED"),
])
def test_detached_worker_revalidates_serial_and_class_filter_without_launch(tmp_path, patch, profile, code):
    options = {"DeviceSerial": SERIAL} if profile in CONNECTED_PROFILES else {}
    _, run, _, _, records = _detached_worker_case(
        tmp_path, options, request_patch=patch, profile=profile,
        test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, code)
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("profile", CONNECTED_PROFILES)
def test_device_serial_reaches_argv_environment_and_records(tmp_path, monkeypatch, profile):
    monkeypatch.delenv("ANDROID_SERIAL", raising=False)
    root, run, capture, _, records = _detached_worker_case(
        tmp_path, {"DeviceSerial": SERIAL}, profile=profile,
        test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    arguments = _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS) + [f"-PvalidationDeviceSerial={SERIAL}"]
    command = _command_display(sys.executable, [str(capture), *arguments])
    for name in ("start-request", "request", "start-result", "result"):
        assert records[name]["device_serial"] == SERIAL
        assert records[name]["command"] == command
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments
    assert records["executed"]["android_serial"] == SERIAL
    assert records["executed"]["record_at_launch"]["device_serial"] == SERIAL


@pytest.mark.parametrize("profile", ["connected-tests", "connected-test-class", "assemble-release"])
def test_profiles_without_device_serial_record_null_and_keep_default_argv(tmp_path, monkeypatch, profile):
    monkeypatch.delenv("ANDROID_SERIAL", raising=False)
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile=profile, test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    arguments = _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS)
    for name in ("start-request", "request", "start-result", "result"):
        assert records[name]["device_serial"] is None
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments
    assert records["executed"]["android_serial"] is None


def test_legacy_request_without_device_serial_still_runs(tmp_path):
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile="connected-tests",
        request_patch="$request.PSObject.Properties.Remove('device_serial')",
    )
    assert "device_serial" not in records["request"]
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == _gradle_arguments("connected-tests", DEFAULT_GRADLE_SETTINGS)


def test_detached_release_artifact_evidence_runs_only_the_fixed_script_with_null_settings(tmp_path):
    _, _, capture, _, records = _detached_worker_case(tmp_path, profile="release-artifact-evidence")
    arguments = ["scripts/ci/release_artifact_evidence.py"]
    for name in ("start-request", "start-result", "result"):
        assert records[name]["gradle_settings"] is None
        assert records[name]["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments


def test_detached_assemble_release_runs_release_task_only(tmp_path):
    _, _, _, _, records = _detached_worker_case(tmp_path, profile="assemble-release")
    assert records["executed"]["argv"] == _gradle_arguments("assemble-release", DEFAULT_GRADLE_SETTINGS)
    assert records["executed"]["argv"][0] == ":app:assembleRelease"
