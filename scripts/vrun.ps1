#!/usr/bin/env pwsh
# vrun.ps1 — start a validation-runner run, BLOCK until done, print verdict + artifact paths.
# Project tooling (human convenience): wraps scripts/validation-runner.ps1 Start+Wait.
# Agents normally invoke validation-runner directly per AGENTS.md; this wrapper exists so a
# human never has to hand-poll a detached run.
#
# Usage (from anywhere; resolves this repo):
#   pwsh scripts\vrun.ps1 -Worktree rp-22 -Profile targeted-unit-test -TestFilter '*RestoreJournal*'
#   pwsh scripts\vrun.ps1 -Worktree .    -Profile compile
#   pwsh scripts\vrun.ps1 -Worktree rp-20 -Profile trusted-tests
#   -Worktree accepts: 'rp-NN'/'gr-*' lane shorthand (build\worktrees\<name>), a directory
#   path, or '.' for the current checkout. -Raw = strict dedup (default allows overlap with
#   reason HUMAN_REQUEST — a human pressing the button IS the human request).
# Exit code mirrors the run: 0 PASS, 1 FAIL/STALE, 2 infra/timeout.
param(
    [Parameter(Mandatory = $true)][string]$Worktree,
    [Parameter(Mandatory = $true)][string]$Profile,
    [string]$TestFilter,
    [string]$Shard,
    [switch]$Raw,
    [switch]$GradleDaemon,
    [int]$MaxTotalMinutes = 60
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ($Worktree -match '^rp-\d+$' -or $Worktree -match '^gr-') {
    $dir = Join-Path $repo ("build\worktrees\" + $Worktree)
} elseif (Test-Path $Worktree) {
    $dir = (Resolve-Path $Worktree).Path
} else {
    $dir = Join-Path $repo $Worktree
}
if (-not (Test-Path (Join-Path $dir "scripts\validation-runner.ps1"))) {
    Write-Error ("no validation-runner under " + $dir)
    exit 2
}
$runner = Join-Path $dir "scripts\validation-runner.ps1"

$startArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runner,
    '-Action', 'Start', '-Profile', $Profile)
if ($TestFilter) { $startArgs += @('-TestFilter', $TestFilter) }
if ($Shard)      { $startArgs += @('-Shard', $Shard) }
if (-not $Raw)   { $startArgs += @('-AllowOverlap', '-OverlapReasonCode', 'HUMAN_REQUEST') }
if ($GradleDaemon) { $startArgs += '-GradleDaemon' }

Push-Location $dir
$runId = ""
try {
    $out = (& powershell @startArgs 2>&1) | Out-String
    $m = [regex]::Match($out, 'vr-\d{8}-\d{6}-[a-f0-9]{8}')
    if (-not $m.Success) {
        Write-Host $out
        exit 2
    }
    $runId = $m.Value
    $label = $Profile
    if ($TestFilter) { $label = $Profile + " " + $TestFilter }
    Write-Host ("started " + $runId + " (" + $label + ") - blocking until done...")

    $deadline = (Get-Date).AddMinutes($MaxTotalMinutes)
    while ($true) {
        if ((Get-Date) -gt $deadline) {
            Write-Host ("still running past " + $MaxTotalMinutes + " min: " + $runId)
            exit 2
        }
        $w = (& powershell -NoProfile -ExecutionPolicy Bypass -File $runner -Action Wait -RunId $runId -MaxWaitSeconds 300 2>&1) | Out-String
        $rec = $null
        try { $rec = $w | ConvertFrom-Json } catch { $rec = $null }
        if ($null -ne $rec -and $null -ne $rec.status -and $rec.status -ne "RUNNING" -and $rec.status -ne "STARTING") {
            $min = "?"
            if ($rec.elapsed_seconds) { $min = [math]::Round($rec.elapsed_seconds / 60, 1) }
            Write-Host ("VERDICT: " + $rec.status + "  (" + $min + " min)")
            if ($rec.failure_code) { Write-Host ("  failure: " + $rec.failure_code) }
            Write-Host ("  result : build/validation-runs/" + $runId + "/result.json")
            Write-Host ("  stdout : build/validation-runs/" + $runId + "/stdout.log")
            Write-Host ("  stderr : build/validation-runs/" + $runId + "/stderr.log")
            if ($rec.status -eq "PASS") { exit 0 }
            if ($rec.status -eq "FAIL" -or $rec.status -eq "STALE_RESULT") { exit 1 }
            exit 2
        }
    }
}
finally {
    Pop-Location
}
