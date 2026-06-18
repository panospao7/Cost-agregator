# CI guardrails: Checks for currency-related regressions (sumOf effectiveAmount, deprecated CurrencyFormatter.format, EUR hardcodes)
param(
    [string]$SourceDir = "app/src/main/java",
    [string]$ProjectRoot = ""
)

if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$ErrorActionPreference = "Stop"
$reportLines = [System.Collections.ArrayList]@()
$exitCode = 0

# Helper: write to report
function Add-Report($text) { [void]$reportLines.Add($text) }

# ---------------------------------------------------------------------------
Add-Report "=== Currency Guardrails Report ==="
Add-Report "Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
# ---------------------------------------------------------------------------

# ============================================================
# CHECK 1: Raw sumOf { it.effectiveAmount } occurrences
# ============================================================
Add-Report "`n[1] Raw sumOf effectiveAmount occurrences"
Add-Report "  (Searching *.kt in $SourceDir, excluding test/ dirs and // SAFE: commented lines)"

$sumOfResults = @()
$allFiles = Get-ChildItem -Recurse -Filter "*.kt" -Path (Join-Path $ProjectRoot $SourceDir) `
    | Where-Object { $_.FullName -notmatch '\\test\\' }

foreach ($file in $allFiles) {
    $lines = Get-Content $file.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # Match .sumOf { ... .effectiveAmount ... } on a single line
        if ($line -match '\.sumOf\s*\{[^}]*\.effectiveAmount') {
            # Skip if previous line has // SAFE:
            $isSafe = ($i -gt 0) -and ($lines[$i - 1] -match '//\s*SAFE:')
            if (-not $isSafe) {
                $relPath = $file.FullName.Substring($ProjectRoot.Length).TrimStart('\')
                $sumOfResults += [PSCustomObject]@{ File = $relPath; Line = $i + 1 }
            }
        }
    }
}

$check1Count = $sumOfResults.Count
Add-Report "  Raw sumOf effectiveAmount: $check1Count occurrences"
foreach ($r in $sumOfResults) { Add-Report "    - $($r.File):$($r.Line)" }

if ($check1Count -gt 0) { $exitCode = 1 }

# ============================================================
# CHECK 2: Deprecated CurrencyFormatter.format(amount) without explicit currency
# ============================================================
Add-Report "`n[2] Deprecated CurrencyFormatter.format(amount) calls"
Add-Report "  (Single-argument calls that default to EUR)"

$formatResults = @()

foreach ($file in $allFiles) {
    $content = Get-Content $file.FullName -Raw
    $searchFrom = 0
    $pattern = 'CurrencyFormatter.format('

    while ($true) {
        $pos = $content.IndexOf($pattern, $searchFrom, [System.StringComparison]::Ordinal)
        if ($pos -lt 0) { break }

        # Determine line number
        $lineNum = 1
        [int]$charCount = 0
        foreach ($line in ($content -split "`r`n|`n")) {
            $charCount += $line.Length + 2
            if ($charCount -gt $pos) { break }
            $lineNum++
        }

        # Walk forward tracking parenthesis depth to find matching close-paren
        $depth = 1
        $i = $pos + $pattern.Length
        $hasTopLevelComma = $false
        while ($i -lt $content.Length -and $depth -gt 0) {
            $ch = $content[$i]
            if    ($ch -eq '(') { $depth++ }
            elseif ($ch -eq ')') { $depth-- }
            elseif ($ch -eq ',' -and $depth -eq 1) { $hasTopLevelComma = $true; break }
            $i++
        }

        if (-not $hasTopLevelComma) {
            $relPath = $file.FullName.Substring($ProjectRoot.Length).TrimStart('\')
            $formatResults += [PSCustomObject]@{ File = $relPath; Line = $lineNum }
        }

        $searchFrom = $pos + 1
    }
}

$check2Count = $formatResults.Count
Add-Report "  Deprecated CurrencyFormatter.format(amount) calls: $check2Count occurrences"
foreach ($r in $formatResults) { Add-Report "    - $($r.File):$($r.Line)" }

# ============================================================
# CHECK 3: "EUR" hardcodes in production code
# ============================================================
Add-Report "`n[3] EUR hardcodes in production code"
Add-Report '  (Excluding: CurrencyCode.EUR, CurrencyCode("EUR"), test files, // LEGITIMATE: comments)'

$eurResults = @()

foreach ($file in $allFiles) {
    $lines = Get-Content $file.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # Look for "EUR" string literal
        if ($line -match '"EUR"') {
            # Exclusions
            if ($line -match '//\s*LEGITIMATE:') { continue }
            if ($line -match 'CurrencyCode\.EUR') { continue }
            if ($line -match 'CurrencyCode\("EUR"\)') { continue }

            $relPath = $file.FullName.Substring($ProjectRoot.Length).TrimStart('\')
            # Determine directory category
            $category = 'other/'
            if    ($relPath -match '\\domain\\') { $category = 'domain/' }
            elseif ($relPath -match '\\data\\')   { $category = 'data/' }
            elseif ($relPath -match '\\ui\\')     { $category = 'ui/' }

            $eurResults += [PSCustomObject]@{
                File = $relPath
                Line = $i + 1
                Category = $category
            }
        }
    }
}

$check3Count = $eurResults.Count
$categoryGroups = $eurResults | Group-Object Category
Add-Report "  EUR hardcodes in production code: $check3Count occurrences"
foreach ($cat in $categoryGroups) {
    Add-Report "    $($cat.Name): $($cat.Count)"
}

# ============================================================
# Summary
# ============================================================
Add-Report "`n=== Summary ==="
Add-Report "  Check 1 (sumOf effectiveAmount): $check1Count $(if ($check1Count -gt 0) { '-- NEW occurrences (not marked SAFE)' } else { '-- OK (all safe)' })"
Add-Report "  Check 2 (deprecated format calls): $check2Count -- Warning"
Add-Report "  Check 3 (EUR hardcodes): $check3Count -- Warning"
$total = $check1Count + $check2Count + $check3Count
Add-Report "  Total items to investigate: $total"

# Output report
$reportLines -join "`n" | Write-Host

exit $exitCode
