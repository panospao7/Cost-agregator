[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Start", "Plan", "Status", "Wait", "List", "Profiles", "Worker")]
    [string]$Action,

    [ValidateSet(
        "runner-smoke",
        "runner-progress-smoke",
        "runner-stall-smoke",
        "compile",
        "assemble-debug",
        "targeted-unit-test",
        "unit-test-shard",
        "trusted-tests",
        "legacy-tests",
        "unit-tests",
        "migration-tests",
        "lint",
        "static-guards",
        "registered-guard",
        "app-check",
        "connected-tests"
    )]
    [string]$Profile,

    [string]$RunId,
    [string]$TestFilter,
    [string]$Shard,
    [string]$GuardId,
    [string]$PythonExecutable,
    [ValidateRange(10, 14400)]
    [int]$TimeoutSeconds = 0,
    [ValidateRange(0, 7200)]
    [int]$NoOutputTimeoutSeconds = 0,
    [switch]$AllowOverlap,
    [ValidateSet("REQUIRED_FINAL_GATE", "INVESTIGATE_INCONSISTENT_RESULT", "HUMAN_REQUEST", "RUNNER_SELF_TEST")]
    [string]$OverlapReasonCode,
    [ValidateRange(1, 30)]
    [int]$PollSeconds = 3,
    [ValidateRange(1, 300)]
    [int]$MaxWaitSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:ScriptPath = $PSCommandPath
$script:RunsRoot = Join-Path $script:RepoRoot "build\validation-runs"
$script:LockPath = Join-Path $script:RunsRoot "active.lock.json"
$script:ShardConfigPath = Join-Path $script:RepoRoot "config\validation\test-shards.json"
$script:HangingLedgerPath = Join-Path $script:RepoRoot "config\validation\known-hanging-tests.json"
$script:TerminalStatuses = @("PASS", "FAIL", "TIMEOUT", "STALE_RESULT", "INFRA_FAILURE")
$script:SchemaVersion = 1

function Get-UtcTimestamp {
    return [DateTime]::UtcNow.ToString("o")
}

function Write-JsonAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )

    $directory = Split-Path -Parent $Path
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = "$Path.$PID.tmp"
    $json = $Value | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($temporary, $json, (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    }
    catch {
        return $null
    }
}

function Get-RunDirectory {
    param([Parameter(Mandatory = $true)][string]$Id)
    if ($Id -notmatch '^vr-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}$') {
        throw "E_INVALID_RUN_ID"
    }
    return Join-Path $script:RunsRoot $Id
}

function Get-RunRecordPath {
    param([Parameter(Mandatory = $true)][string]$Id)
    return Join-Path (Get-RunDirectory -Id $Id) "result.json"
}

function Test-ProcessAlive {
    param([int]$ProcessId)
    if ($ProcessId -le 0) { return $false }
    try {
        Get-Process -Id $ProcessId -ErrorAction Stop | Out-Null
        return $true
    }
    catch {
        return $false
    }
}

function Complete-OrphanedRun {
    param([Parameter(Mandatory = $true)]$Lock)
    $recordPath = Get-RunRecordPath -Id $Lock.run_id
    $record = Read-JsonFile -Path $recordPath
    if ($null -ne $record -and $script:TerminalStatuses -notcontains $record.status) {
        # A dead worker can leave a live Gradle child. Do not free the global
        # lock until the child tree is gone, or a second build could overlap.
        if ($record.child_pid -and (Test-ProcessAlive -ProcessId ([int]$record.child_pid))) {
            try { & taskkill.exe /PID ([int]$record.child_pid) /T /F 2>$null | Out-Null } catch { }
            if (Test-ProcessAlive -ProcessId ([int]$record.child_pid)) { throw "E_ORPHANED_CHILD_STILL_RUNNING" }
        }
        $record.status = "INFRA_FAILURE"
        $record.failure_code = "E_ORPHANED_RUN"
        $record.finished_at = Get-UtcTimestamp
        Write-JsonAtomic -Path $recordPath -Value $record
        [System.IO.File]::WriteAllText(
            (Join-Path (Get-RunDirectory -Id $Lock.run_id) "complete.marker"),
            "VALIDATION_COMPLETE`nstatus=INFRA_FAILURE`nexit_code=2`n",
            (New-Object System.Text.UTF8Encoding($false))
        )
    }
}

function Resolve-ActiveLock {
    if (-not (Test-Path -LiteralPath $script:LockPath -PathType Leaf)) {
        return $null
    }

    $lock = Read-JsonFile -Path $script:LockPath
    if ($null -eq $lock -or $null -eq $lock.run_id) {
        throw "E_INVALID_LOCK"
    }

    $record = Read-JsonFile -Path (Get-RunRecordPath -Id $lock.run_id)
    if ($null -ne $record -and $script:TerminalStatuses -contains $record.status) {
        if (Test-ProcessAlive -ProcessId ([int]$lock.worker_pid)) { return $lock }
        Remove-Item -LiteralPath $script:LockPath -Force -ErrorAction SilentlyContinue
        return $null
    }

    $pidValue = 0
    if ($null -ne $lock.worker_pid) { $pidValue = [int]$lock.worker_pid }
    if (Test-ProcessAlive -ProcessId $pidValue) {
        return $lock
    }

    $created = [DateTime]::MinValue
    [DateTime]::TryParse([string]$lock.created_at, [ref]$created) | Out-Null
    if (((Get-Date).ToUniversalTime() - $created.ToUniversalTime()).TotalSeconds -lt 30) {
        return $lock
    }

    Complete-OrphanedRun -Lock $lock
    Remove-Item -LiteralPath $script:LockPath -Force -ErrorAction SilentlyContinue
    return $null
}

function New-LockFile {
    param([Parameter(Mandatory = $true)]$Lock)
    [System.IO.Directory]::CreateDirectory($script:RunsRoot) | Out-Null
    try {
        $stream = [System.IO.File]::Open(
            $script:LockPath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )
        try {
            $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes(($Lock | ConvertTo-Json -Depth 6))
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        }
        finally {
            $stream.Dispose()
        }
    }
    catch [System.IO.IOException] {
        throw "E_VALIDATION_BUSY"
    }
}

function Get-GitRevision {
    try {
        $revision = (& git -C $script:RepoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
        if ($revision -and ([string]$revision) -match '^[a-f0-9]{40,64}$') { return [string]$revision }
    }
    catch { }
    return "UNKNOWN"
}

function Get-WorktreeFingerprint {
    try {
        # Hash file bytes rather than decoded `git diff` output. Detached
        # Windows PowerShell processes can use a different console encoding,
        # which would otherwise produce false STALE_RESULT outcomes.
        $trackedPaths = @(& git -C $script:RepoRoot -c core.quotepath=false -c core.safecrlf=false diff --name-only HEAD -- 2>$null)
        if ($LASTEXITCODE -ne 0) { return "UNKNOWN" }
        $untrackedPaths = @(& git -C $script:RepoRoot -c core.quotepath=false ls-files --others --exclude-standard 2>$null)
        if ($LASTEXITCODE -ne 0) { return "UNKNOWN" }

        $parts = New-Object System.Collections.Generic.List[string]
        $allPaths = @($trackedPaths + $untrackedPaths | Sort-Object -Unique)
        foreach ($relative in $allPaths) {
            $parts.Add("PATH:$relative")
            $candidate = Join-Path $script:RepoRoot $relative
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                try { $parts.Add((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash) }
                catch { return "UNKNOWN" }
            }
            else { $parts.Add("MISSING") }
        }
        $payload = [System.Text.Encoding]::UTF8.GetBytes(($parts -join "`n"))
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($payload))).Replace("-", "").ToLowerInvariant() }
        finally { $sha.Dispose() }
    }
    catch {
        return "UNKNOWN"
    }
}

function Resolve-PythonCommand {
    param([string]$Requested)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Requested) { $candidates.Add($Requested) }
    if ($env:VALIDATION_PYTHON) { $candidates.Add($env:VALIDATION_PYTHON) }
    $candidates.Add("python3")
    $candidates.Add("python")

    foreach ($candidate in $candidates) {
        try {
            $command = Get-Command $candidate -ErrorAction Stop
            if ((Split-Path -Leaf $command.Source) -notmatch '^python(?:3(?:\.\d+)?)?\.exe$') {
                continue
            }
            $process = Start-Process -FilePath $command.Source -ArgumentList "--version" -Wait -PassThru -WindowStyle Hidden
            if ($process.ExitCode -eq 0) { return $command.Source }
        }
        catch { }
    }
    throw "E_PYTHON_UNAVAILABLE"
}

function Read-ValidationConfig {
    param([Parameter(Mandatory = $true)][string]$Path)
    $config = Read-JsonFile -Path $Path
    if ($null -eq $config -or $config.schema_version -ne 1) {
        throw "E_VALIDATION_CONFIG_INVALID"
    }
    return $config
}

function Assert-SafeTestFilters {
    param([object[]]$Filters)
    if ($null -eq $Filters -or $Filters.Count -eq 0) { throw "E_TEST_FILTERS_EMPTY" }
    foreach ($filter in $Filters) {
        if (-not $filter -or ([string]$filter) -notmatch '^[A-Za-z0-9_.$*?\-]+$') {
            throw "E_INVALID_TEST_FILTER"
        }
    }
}

function Get-ShardFilters {
    param([Parameter(Mandatory = $true)][string]$Name)
    if ($Name -notmatch '^[a-z][a-z0-9-]{1,63}$') { throw "E_INVALID_SHARD" }
    $config = Read-ValidationConfig -Path $script:ShardConfigPath
    $entry = $config.shards.PSObject.Properties[$Name]
    if ($null -eq $entry) { throw "E_UNKNOWN_SHARD" }
    $filters = @($entry.Value.filters)
    Assert-SafeTestFilters -Filters $filters
    return $filters
}

function Get-TrustedFilters {
    $config = Read-ValidationConfig -Path $script:ShardConfigPath
    $filters = @($config.trusted.filters)
    Assert-SafeTestFilters -Filters $filters
    return $filters
}

function Get-LegacyFilters {
    $ledger = Read-ValidationConfig -Path $script:HangingLedgerPath
    $seenIds = @{}
    $filters = New-Object System.Collections.Generic.List[string]
    foreach ($entry in @($ledger.entries)) {
        if (-not $entry.id -or $entry.id -notmatch '^HANG-[0-9]{3,}$' -or $seenIds.ContainsKey([string]$entry.id)) {
            throw "E_HANGING_LEDGER_INVALID"
        }
        $seenIds[[string]$entry.id] = $true
        if ($entry.status -notin @("suspected", "confirmed", "resolved") -or
            $entry.routing -notin @("legacy", "normal") -or
            -not $entry.reason_code -or $entry.reason_code -notmatch '^[A-Z][A-Z0-9_]{2,63}$' -or
            -not $entry.owner -or -not $entry.review_by) {
            throw "E_HANGING_LEDGER_INVALID"
        }
        $reviewDate = [DateTime]::MinValue
        if (-not [DateTime]::TryParseExact([string]$entry.review_by, "yyyy-MM-dd", $null, [Globalization.DateTimeStyles]::None, [ref]$reviewDate)) {
            throw "E_HANGING_LEDGER_INVALID"
        }
        Assert-SafeTestFilters -Filters @($entry.test_filter)
        if ($entry.routing -eq "legacy" -and $entry.status -in @("suspected", "confirmed")) {
            if ($reviewDate.Date -lt [DateTime]::UtcNow.Date) { throw "E_HANGING_LEDGER_REVIEW_OVERDUE" }
            $filters.Add([string]$entry.test_filter)
        }
    }
    Assert-SafeTestFilters -Filters $filters
    return @($filters)
}

function New-TestArguments {
    param([object[]]$Filters)
    $arguments = New-Object System.Collections.Generic.List[string]
    $arguments.Add(":app:testDebugUnitTest")
    foreach ($filter in $Filters) {
        $arguments.Add("--tests")
        $arguments.Add([string]$filter)
    }
    return @($arguments)
}

function Get-ProfileSpec {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$Filter,
        [string]$ShardName,
        [string]$SelectedGuard,
        [string]$RequestedPython
    )

    $gradle = Join-Path $script:RepoRoot "gradlew.bat"
    $gradleTail = @(
        "--console=plain", "--no-parallel", "--max-workers=1", "--no-daemon",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50"
    )
    switch ($Name) {
        "runner-smoke" {
            return @{ executable = "$env:SystemRoot\System32\cmd.exe"; arguments = @("/d", "/c", "exit", "0"); timeout = 60; no_output_timeout = 30 }
        }
        "runner-progress-smoke" {
            return @{ executable = "$env:SystemRoot\System32\cmd.exe"; arguments = @("/d", "/c", "ping", "127.0.0.1", "-n", "10"); timeout = 30; no_output_timeout = 3 }
        }
        "runner-stall-smoke" {
            return @{ executable = "$env:SystemRoot\System32\cmd.exe"; arguments = @("/d", "/c", "ping", "127.0.0.1", "-n", "31", ">nul"); timeout = 60; no_output_timeout = 10 }
        }
        "compile" { return @{ executable = $gradle; arguments = @(":app:compileDebugKotlin") + $gradleTail; timeout = 1800; no_output_timeout = 600 } }
        "assemble-debug" { return @{ executable = $gradle; arguments = @(":app:assembleDebug") + $gradleTail; timeout = 3600; no_output_timeout = 900 } }
        "targeted-unit-test" {
            if (-not $Filter -or $Filter -notmatch '^[A-Za-z0-9_.$*?\-]+$') { throw "E_INVALID_TEST_FILTER" }
            return @{ executable = $gradle; arguments = @(":app:testDebugUnitTest", "--tests", $Filter) + $gradleTail; timeout = 2400; no_output_timeout = 600 }
        }
        "unit-test-shard" { return @{ executable = $gradle; arguments = (New-TestArguments -Filters (Get-ShardFilters -Name $ShardName)) + $gradleTail; timeout = 3600; no_output_timeout = 900 } }
        "trusted-tests" { return @{ executable = $gradle; arguments = (New-TestArguments -Filters (Get-TrustedFilters)) + $gradleTail; timeout = 3600; no_output_timeout = 900 } }
        "legacy-tests" { return @{ executable = $gradle; arguments = (New-TestArguments -Filters (Get-LegacyFilters)) + $gradleTail; timeout = 3600; no_output_timeout = 600 } }
        "unit-tests" { return @{ executable = $gradle; arguments = @(":app:testDebugUnitTest") + $gradleTail; timeout = 5400; no_output_timeout = 900 } }
        "migration-tests" { return @{ executable = $gradle; arguments = @(":app:testDebugUnitTest", "--tests", "*Migration*") + $gradleTail; timeout = 3600; no_output_timeout = 900 } }
        "lint" { return @{ executable = $gradle; arguments = @(":app:lintDebug") + $gradleTail; timeout = 3600; no_output_timeout = 900 } }
        "app-check" { return @{ executable = $gradle; arguments = @(":app:check") + $gradleTail; timeout = 7200; no_output_timeout = 1200 } }
        "connected-tests" { return @{ executable = $gradle; arguments = @(":app:connectedDebugAndroidTest") + $gradleTail; timeout = 7200; no_output_timeout = 1200 } }
        "static-guards" {
            $python = Resolve-PythonCommand -Requested $RequestedPython
            return @{
                executable = $python
                arguments = @("scripts/ci/run_static_guard_suite.py", "--output-dir", "build/ci/static-guards")
                timeout = 7200
                no_output_timeout = 1800
            }
        }
        "registered-guard" {
            if (-not $SelectedGuard -or $SelectedGuard -notmatch '^[a-z][a-z0-9_]{1,63}$') { throw "E_INVALID_GUARD_ID" }
            $python = Resolve-PythonCommand -Requested $RequestedPython
            return @{
                executable = $python
                arguments = @(
                    "scripts/ci/run_registered_guard.py",
                    "--guard-id", $SelectedGuard,
                    "--context", "direct",
                    "--root", ".",
                    "--ci-mode"
                )
                timeout = 2400
                no_output_timeout = 1200
            }
        }
        default { throw "E_UNKNOWN_PROFILE" }
    }
}

function Get-SafeCommandDisplay {
    param([Parameter(Mandatory = $true)]$Spec)
    $executable = Split-Path -Leaf $Spec.executable
    return (($executable) + " " + (($Spec.arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join " ")).Trim()
}

function ConvertTo-ProcessArguments {
    param([string[]]$Arguments)
    return (($Arguments | ForEach-Object {
        $value = [string]$_
        if ($value -notmatch '[\s"]') { return $value }
        # Profiles reject free-form shell syntax. This quoting handles the
        # remaining Windows argv cases without invoking a command shell.
        return '"' + $value.Replace('\', '\').Replace('"', '\"') + '"'
    }) -join " ")
}

function Test-ExternalGradleClient {
    try {
        $clients = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
            $_.ProcessId -ne $PID -and $_.CommandLine -and $_.CommandLine -match 'GradleWrapperMain'
        })
        return $clients.Count -gt 0
    }
    catch {
        # The durable lock remains authoritative if process enumeration is unavailable.
        return $false
    }
}

function Find-OverlappingSuccessfulRun {
    param(
        [Parameter(Mandatory = $true)][string]$RequestedProfile,
        [Parameter(Mandatory = $true)][string]$Fingerprint
    )
    if (-not (Test-Path -LiteralPath $script:RunsRoot -PathType Container)) { return $null }
    foreach ($directory in @(Get-ChildItem -LiteralPath $script:RunsRoot -Directory -Filter "vr-*" |
        Sort-Object Name -Descending | Select-Object -First 50)) {
        $record = Read-JsonFile -Path (Join-Path $directory.FullName "result.json")
        if ($null -eq $record -or $record.status -ne "PASS") { continue }
        if ($record.worktree_fingerprint_end -ne $Fingerprint) { continue }
        if (-not (Test-Path -LiteralPath (Join-Path $directory.FullName "complete.marker") -PathType Leaf)) { continue }
        if ($record.profile -eq $RequestedProfile) { return $record }
        # Gradle :app:check and the canonical static suite are different gates.
        # Never assume that one passing result satisfies another profile.
    }
    return $null
}

function Start-ValidationRun {
    if (-not $Profile) { throw "E_PROFILE_REQUIRED" }
    if ($AllowOverlap -and -not $OverlapReasonCode) { throw "E_OVERLAP_REASON_REQUIRED" }
    if (-not $AllowOverlap -and $OverlapReasonCode) { throw "E_OVERLAP_REASON_WITHOUT_OVERRIDE" }
    if ($null -ne (Resolve-ActiveLock)) { throw "E_VALIDATION_BUSY" }
    if ($Profile -ne "runner-smoke" -and (Test-ExternalGradleClient)) { throw "E_EXTERNAL_GRADLE_ACTIVE" }

    # Resolve and validate every profile input before claiming the global lock.
    if ($Profile -eq "unit-test-shard" -and -not $Shard) { throw "E_SHARD_REQUIRED" }
    $spec = Get-ProfileSpec -Name $Profile -Filter $TestFilter -ShardName $Shard -SelectedGuard $GuardId -RequestedPython $PythonExecutable
    $effectiveTimeout = if ($TimeoutSeconds -gt 0) { $TimeoutSeconds } else { [int]$spec.timeout }
    $effectiveNoOutputTimeout = if ($NoOutputTimeoutSeconds -gt 0) { $NoOutputTimeoutSeconds } else { [int]$spec.no_output_timeout }
    if ($effectiveNoOutputTimeout -ge $effectiveTimeout) { throw "E_NO_OUTPUT_TIMEOUT_INVALID" }
    $startFingerprint = Get-WorktreeFingerprint
    if ($startFingerprint -eq "UNKNOWN") { throw "E_WORKTREE_FINGERPRINT_UNAVAILABLE" }
    if (-not $AllowOverlap) {
        $overlap = Find-OverlappingSuccessfulRun -RequestedProfile $Profile -Fingerprint $startFingerprint
        if ($null -ne $overlap) { throw "E_DUPLICATE_OR_OVERLAPPING_VALIDATION" }
    }
    $id = "vr-{0}-{1}" -f ([DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss")), ([Guid]::NewGuid().ToString("N").Substring(0, 8))
    $runDirectory = Get-RunDirectory -Id $id
    [System.IO.Directory]::CreateDirectory($runDirectory) | Out-Null

    $settings = @{
        schema_version = $script:SchemaVersion
        run_id = $id
        profile = $Profile
        test_filter = $TestFilter
        shard = $Shard
        guard_id = $GuardId
        python_executable = $PythonExecutable
        timeout_seconds = $effectiveTimeout
        no_output_timeout_seconds = $effectiveNoOutputTimeout
        allow_overlap = [bool]$AllowOverlap
        overlap_reason_code = $OverlapReasonCode
    }
    Write-JsonAtomic -Path (Join-Path $runDirectory "request.json") -Value $settings

    $record = @{
        schema_version = $script:SchemaVersion
        run_id = $id
        profile = $Profile
        status = "STARTING"
        exit_code = $null
        failure_code = $null
        started_at = Get-UtcTimestamp
        finished_at = $null
        timeout_seconds = $effectiveTimeout
        command = Get-SafeCommandDisplay -Spec $spec
        git_revision = Get-GitRevision
        worktree_fingerprint_start = $startFingerprint
        worktree_fingerprint_end = $null
        stdout_log = "build/validation-runs/$id/stdout.log"
        stderr_log = "build/validation-runs/$id/stderr.log"
        completion_marker = "build/validation-runs/$id/complete.marker"
        worker_pid = $null
        child_pid = $null
        last_heartbeat_at = $null
        last_output_at = $null
        stdout_bytes = 0
        stderr_bytes = 0
        elapsed_seconds = 0
        timeout_kind = $null
    }
    Write-JsonAtomic -Path (Join-Path $runDirectory "result.json") -Value $record

    $lock = @{ run_id = $id; worker_pid = 0; created_at = Get-UtcTimestamp }
    New-LockFile -Lock $lock
    try {
        $escapedScriptPath = $script:ScriptPath.Replace("'", "''")
        $workerCommand = "& '$escapedScriptPath' -Action Worker -RunId '$id'"
        $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($workerCommand))
        $worker = Start-Process -FilePath "powershell.exe" -ArgumentList @(
            "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encoded
        ) -PassThru -WindowStyle Hidden

        $lock.worker_pid = $worker.Id
        Write-JsonAtomic -Path $script:LockPath -Value $lock
        $record.worker_pid = $worker.Id
        Write-JsonAtomic -Path (Join-Path $runDirectory "result.json") -Value $record
    }
    catch {
        Remove-Item -LiteralPath $script:LockPath -Force -ErrorAction SilentlyContinue
        $record.status = "INFRA_FAILURE"
        $record.exit_code = 2
        $record.failure_code = "E_WORKER_LAUNCH_FAILED"
        $record.finished_at = Get-UtcTimestamp
        Write-JsonAtomic -Path (Join-Path $runDirectory "result.json") -Value $record
        [System.IO.File]::WriteAllText(
            (Join-Path $runDirectory "complete.marker"),
            "VALIDATION_COMPLETE`nstatus=INFRA_FAILURE`nexit_code=2`n",
            (New-Object System.Text.UTF8Encoding($false))
        )
        throw "E_WORKER_LAUNCH_FAILED"
    }

    [PSCustomObject]@{
        run_id = $id
        status = "STARTING"
        profile = $Profile
        result_file = "build/validation-runs/$id/result.json"
        next_command = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Status -RunId $id"
    } | ConvertTo-Json -Depth 5
}

function Show-ValidationPlan {
    if (-not $Profile) { throw "E_PROFILE_REQUIRED" }
    if ($Profile -eq "unit-test-shard" -and -not $Shard) { throw "E_SHARD_REQUIRED" }
    $spec = Get-ProfileSpec -Name $Profile -Filter $TestFilter -ShardName $Shard -SelectedGuard $GuardId -RequestedPython $PythonExecutable
    $effectiveTimeout = if ($TimeoutSeconds -gt 0) { $TimeoutSeconds } else { [int]$spec.timeout }
    $effectiveNoOutputTimeout = if ($NoOutputTimeoutSeconds -gt 0) { $NoOutputTimeoutSeconds } else { [int]$spec.no_output_timeout }
    if ($effectiveNoOutputTimeout -ge $effectiveTimeout) { throw "E_NO_OUTPUT_TIMEOUT_INVALID" }
    [PSCustomObject]@{
        profile = $Profile
        shard = $Shard
        command = Get-SafeCommandDisplay -Spec $spec
        timeout_seconds = $effectiveTimeout
        no_output_timeout_seconds = $effectiveNoOutputTimeout
    } | ConvertTo-Json -Depth 5
}

function Invoke-ValidationWorker {
    if (-not $RunId) { throw "E_RUN_ID_REQUIRED" }
    $runDirectory = Get-RunDirectory -Id $RunId
    $request = Read-JsonFile -Path (Join-Path $runDirectory "request.json")
    $recordPath = Join-Path $runDirectory "result.json"
    $record = Read-JsonFile -Path $recordPath
    if ($null -eq $request -or $null -eq $record) { throw "E_RUN_RECORD_MISSING" }

    # Parent publishes the worker PID and initial record after Start-Process.
    # Wait for that handoff before touching either file to avoid overwriting
    # RUNNING with a late parent-side STARTING write.
    $lock = $null
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $lock = Read-JsonFile -Path $script:LockPath
        $record = Read-JsonFile -Path $recordPath
        if ($null -ne $lock -and $lock.run_id -eq $RunId -and
            $lock.worker_pid -eq $PID -and $null -ne $record -and $record.worker_pid -eq $PID) { break }
        Start-Sleep -Milliseconds 200
    }
    if ($null -eq $lock -or $lock.run_id -ne $RunId -or
        $lock.worker_pid -ne $PID -or $record.worker_pid -ne $PID) { throw "E_LOCK_OWNERSHIP_LOST" }
    $lock.worker_pid = $PID
    Write-JsonAtomic -Path $script:LockPath -Value $lock

    $record.worker_pid = $PID
    $record.status = "RUNNING"
    Write-JsonAtomic -Path $recordPath -Value $record

    $exitCode = 2
    $failureCode = $null
    try {
        $spec = Get-ProfileSpec -Name $request.profile -Filter $request.test_filter -ShardName $request.shard -SelectedGuard $request.guard_id -RequestedPython $request.python_executable
        $stdout = Join-Path $runDirectory "stdout.log"
        $stderr = Join-Path $runDirectory "stderr.log"
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $spec.executable
        $startInfo.Arguments = ConvertTo-ProcessArguments -Arguments ([string[]]$spec.arguments)
        $startInfo.WorkingDirectory = $script:RepoRoot
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        $streamOptions = [System.IO.FileOptions]::Asynchronous -bor [System.IO.FileOptions]::WriteThrough
        $outputStream = New-Object System.IO.FileStream -ArgumentList @(
            $stdout, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::Read, 1, $streamOptions
        )
        $errorStream = New-Object System.IO.FileStream -ArgumentList @(
            $stderr, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::Read, 1, $streamOptions
        )
        try {
            if (-not $process.Start()) { throw "E_COMMAND_LAUNCH_FAILED" }
            $record.child_pid = $process.Id
            $record.last_heartbeat_at = Get-UtcTimestamp
            $record.last_output_at = $record.last_heartbeat_at
            Write-JsonAtomic -Path $recordPath -Value $record
            $outputCopy = $process.StandardOutput.BaseStream.CopyToAsync($outputStream)
            $errorCopy = $process.StandardError.BaseStream.CopyToAsync($errorStream)

            $startedAt = [DateTime]::UtcNow
            $lastOutputAt = $startedAt
            $lastStdoutBytes = 0L
            $lastStderrBytes = 0L
            $timeoutKind = $null
            $completed = $false
            while (-not $completed) {
                $completed = $process.WaitForExit(5000)
                $now = [DateTime]::UtcNow
                $stdoutBytes = if (Test-Path -LiteralPath $stdout) { (Get-Item -LiteralPath $stdout).Length } else { 0L }
                $stderrBytes = if (Test-Path -LiteralPath $stderr) { (Get-Item -LiteralPath $stderr).Length } else { 0L }
                if ($stdoutBytes -ne $lastStdoutBytes -or $stderrBytes -ne $lastStderrBytes) {
                    $lastOutputAt = $now
                    $lastStdoutBytes = $stdoutBytes
                    $lastStderrBytes = $stderrBytes
                }
                $elapsed = [int][Math]::Floor(($now - $startedAt).TotalSeconds)
                $silentFor = [int][Math]::Floor(($now - $lastOutputAt).TotalSeconds)
                $record.last_heartbeat_at = $now.ToString("o")
                $record.last_output_at = $lastOutputAt.ToString("o")
                $record.stdout_bytes = $stdoutBytes
                $record.stderr_bytes = $stderrBytes
                $record.elapsed_seconds = $elapsed
                Write-JsonAtomic -Path $recordPath -Value $record

                if (-not $completed -and $elapsed -ge [int]$request.timeout_seconds) {
                    $timeoutKind = "absolute"
                    break
                }
                if (-not $completed -and $silentFor -ge [int]$request.no_output_timeout_seconds) {
                    $timeoutKind = "no_output"
                    break
                }
            }
            if ($null -ne $timeoutKind) {
                try { & taskkill.exe /PID $process.Id /T /F | Out-Null } catch { }
                $process.WaitForExit(10000) | Out-Null
                # A surviving descendant can retain a redirected handle even
                # after the parent is killed. Never wait forever for EOF.
                if (-not ($outputCopy.IsCompleted -and $errorCopy.IsCompleted)) {
                    $process.StandardOutput.BaseStream.Dispose()
                    $process.StandardError.BaseStream.Dispose()
                }
            }
            else { $process.WaitForExit() }
            if ($null -eq $timeoutKind) {
                if (-not [System.Threading.Tasks.Task]::WaitAll(
                    [System.Threading.Tasks.Task[]]@($outputCopy, $errorCopy), 10000
                )) { throw "E_OUTPUT_DRAIN_TIMEOUT" }
                $outputCopy.GetAwaiter().GetResult()
                $errorCopy.GetAwaiter().GetResult()
            }
        }
        finally {
            $outputStream.Dispose()
            $errorStream.Dispose()
        }
        if ($null -ne $timeoutKind) {
            $record.status = "TIMEOUT"
            $exitCode = 2
            $record.timeout_kind = $timeoutKind
            $failureCode = if ($timeoutKind -eq "no_output") { "E_NO_OUTPUT_TIMEOUT" } else { "E_COMMAND_TIMEOUT" }
        }
        else {
            $exitCode = [int]$process.ExitCode
            if ($exitCode -eq 0) { $record.status = "PASS" }
            else { $record.status = "FAIL"; $failureCode = "E_COMMAND_FAILED" }
        }
    }
    catch {
        $record.status = "INFRA_FAILURE"
        $exitCode = 2
        $failureCode = if ([string]$_.Exception.Message -match '^E_[A-Z0-9_]+$') {
            [string]$_.Exception.Message
        } else { "E_COMMAND_LAUNCH_FAILED" }
    }
    finally {
        $endFingerprint = Get-WorktreeFingerprint
        $record.worktree_fingerprint_end = $endFingerprint
        if ($endFingerprint -eq "UNKNOWN") {
            $record.status = "INFRA_FAILURE"
            $exitCode = 2
            $failureCode = "E_WORKTREE_FINGERPRINT_UNAVAILABLE"
        }
        elseif ($record.status -eq "PASS" -and $record.worktree_fingerprint_start -ne $endFingerprint) {
            $record.status = "STALE_RESULT"
            $exitCode = 1
            $failureCode = "E_WORKTREE_CHANGED"
        }
        $record.exit_code = $exitCode
        $record.failure_code = $failureCode
        $record.finished_at = Get-UtcTimestamp
        Write-JsonAtomic -Path $recordPath -Value $record
        [System.IO.File]::WriteAllText(
            (Join-Path $runDirectory "complete.marker"),
            "VALIDATION_COMPLETE`nstatus=$($record.status)`nexit_code=$exitCode`n",
            (New-Object System.Text.UTF8Encoding($false))
        )
        $currentLock = Read-JsonFile -Path $script:LockPath
        if ($null -ne $currentLock -and $currentLock.run_id -eq $RunId) {
            Remove-Item -LiteralPath $script:LockPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Write-RunStatus {
    param([Parameter(Mandatory = $true)][string]$Id)
    # Reconcile a dead detached worker into a durable infra result instead of
    # leaving callers to poll RUNNING forever.
    Resolve-ActiveLock | Out-Null
    $record = Read-JsonFile -Path (Get-RunRecordPath -Id $Id)
    if ($null -eq $record) { throw "E_RUN_NOT_FOUND" }
    if ($record.status -eq "PASS" -and -not (Test-Path -LiteralPath (Join-Path (Get-RunDirectory -Id $Id) "complete.marker") -PathType Leaf)) {
        if ($null -ne (Resolve-ActiveLock)) { $record.status = "RUNNING" }
        else {
            $record.status = "INFRA_FAILURE"
            $record.failure_code = "E_COMPLETION_MARKER_MISSING"
        }
    }
    $record | ConvertTo-Json -Depth 10
    if ($record.status -in @("STARTING", "RUNNING")) { exit 3 }
    if ($record.status -eq "PASS") { exit 0 }
    if ($record.status -in @("FAIL", "STALE_RESULT")) { exit 1 }
    exit 2
}

function Wait-ForRun {
    if (-not $RunId) { throw "E_RUN_ID_REQUIRED" }
    $deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
    do {
        $record = Read-JsonFile -Path (Get-RunRecordPath -Id $RunId)
        if ($null -eq $record) { throw "E_RUN_NOT_FOUND" }
        if ($script:TerminalStatuses -contains $record.status) {
            if ($record.status -eq "PASS" -and -not (Test-Path -LiteralPath (Join-Path (Get-RunDirectory -Id $RunId) "complete.marker") -PathType Leaf)) {
                if ($null -ne (Resolve-ActiveLock)) {
                    Start-Sleep -Seconds $PollSeconds
                    continue
                }
                $record.status = "INFRA_FAILURE"
                $record.failure_code = "E_COMPLETION_MARKER_MISSING"
            }
            $record | ConvertTo-Json -Depth 10
            if ($record.status -eq "PASS") { exit 0 }
            if ($record.status -in @("FAIL", "STALE_RESULT")) { exit 1 }
            exit 2
        }
        Start-Sleep -Seconds $PollSeconds
    } while ((Get-Date) -lt $deadline)

    [PSCustomObject]@{
        run_id = $RunId
        status = $record.status
        failure_code = "E_WAIT_WINDOW_EXPIRED"
        note = "The validation process is still independent; poll Status again."
    } | ConvertTo-Json
    exit 3
}

function List-Runs {
    if (-not (Test-Path -LiteralPath $script:RunsRoot -PathType Container)) {
        @() | ConvertTo-Json
        return
    }
    $records = @(Get-ChildItem -LiteralPath $script:RunsRoot -Directory -Filter "vr-*" |
        Sort-Object Name -Descending |
        Select-Object -First 20 |
        ForEach-Object { Read-JsonFile -Path (Join-Path $_.FullName "result.json") } |
        Where-Object { $null -ne $_ })
    $records | ConvertTo-Json -Depth 8
}

function Show-Profiles {
    @(
        @{ name = "runner-smoke"; purpose = "Exercise only the detached runner protocol" },
        @{ name = "runner-progress-smoke"; purpose = "Exercise heartbeat/output progress detection" },
        @{ name = "runner-stall-smoke"; purpose = "Exercise no-output timeout handling; expected TIMEOUT" },
        @{ name = "compile"; purpose = "Compile debug Kotlin" },
        @{ name = "assemble-debug"; purpose = "Build the debug APK" },
        @{ name = "targeted-unit-test"; purpose = "Run one allowlisted Gradle test filter" },
        @{ name = "unit-test-shard"; purpose = "Run one named manifest shard with -Shard" },
        @{ name = "trusted-tests"; purpose = "Run the curated trusted structural test group" },
        @{ name = "legacy-tests"; purpose = "Run ledger-routed suspected/confirmed hanging tests in isolation" },
        @{ name = "unit-tests"; purpose = "Run all debug unit tests" },
        @{ name = "migration-tests"; purpose = "Run migration-filtered unit tests" },
        @{ name = "lint"; purpose = "Run debug lint" },
        @{ name = "static-guards"; purpose = "Run the canonical fail-closed static guard suite" },
        @{ name = "registered-guard"; purpose = "Run one canonical registered guard in CI mode" },
        @{ name = "app-check"; purpose = "Run :app:check" },
        @{ name = "connected-tests"; purpose = "Run connected debug Android tests" }
    ) | ConvertTo-Json -Depth 5
}

try {
    switch ($Action) {
        "Start" { Start-ValidationRun }
        "Plan" { Show-ValidationPlan }
        "Status" {
            if (-not $RunId) { throw "E_RUN_ID_REQUIRED" }
            Write-RunStatus -Id $RunId
        }
        "Wait" { Wait-ForRun }
        "List" { List-Runs }
        "Profiles" { Show-Profiles }
        "Worker" { Invoke-ValidationWorker }
    }
}
catch {
    $code = [string]$_.Exception.Message
    if ($code -notmatch '^E_[A-Z0-9_]+$') { $code = "E_RUNNER_UNEXPECTED" }
    [PSCustomObject]@{
        status = "INFRA_FAILURE"
        failure_code = $code
        exception_type = $_.Exception.GetType().Name
    } | ConvertTo-Json
    exit 2
}
