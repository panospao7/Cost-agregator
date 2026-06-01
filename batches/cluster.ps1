param([int]$Minutes = 12)
$dir = "app\build\test-results\testDebugUnitTest"
$cut = (Get-Date).AddMinutes(-1 * $Minutes)
$xmls = Get-ChildItem -LiteralPath $dir -Filter *.xml -File | Where-Object { $_.LastWriteTime -gt $cut }
$rows=@()
foreach($x in $xmls){
  [xml]$d = Get-Content -LiteralPath $x.FullName -Raw
  foreach($tc in $d.testsuite.testcase){
    if($tc.failure -or $tc.error){
      $node = if($tc.failure){$tc.failure}else{$tc.error}
      $type = $node.type
      $msg  = "$($node.message)"
      $first = ($msg -split "`r?`n")[0]
      if($first.Length -gt 180){$first=$first.Substring(0,180)}
      $shortClass = ($tc.classname -split '\.')[-1]
      $rows += [PSCustomObject]@{Class=$shortClass;Type=$type;First=$first}
    }
  }
}
"=== Failure count by exception type ==="
$rows | Group-Object Type | Sort-Object Count -Descending | ForEach-Object { "{0,4}  {1}" -f $_.Count, $_.Name }
""
"=== Failure count by class ==="
$rows | Group-Object Class | Sort-Object Count -Descending | ForEach-Object { "{0,4}  {1}" -f $_.Count, $_.Name }
""
"=== First message line clusters (signature) ==="
$rows | ForEach-Object { ($_.First -replace '\d+','N' -replace '\s+',' ').Trim() } | Group-Object | Sort-Object Count -Descending | Select-Object -First 40 | ForEach-Object { "{0,4}  {1}" -f $_.Count, $_.Name }
