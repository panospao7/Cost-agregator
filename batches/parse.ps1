param([int]$Minutes = 6)
$dir = "app\build\test-results\testDebugUnitTest"
$cut = (Get-Date).AddMinutes(-1 * $Minutes)
$xmls = Get-ChildItem -LiteralPath $dir -Filter *.xml -File | Where-Object { $_.LastWriteTime -gt $cut }
$tot=0;$fail=0;$err=0;$skip=0; $fails=@()
foreach($x in $xmls){
  [xml]$d = Get-Content -LiteralPath $x.FullName -Raw
  $ts=$d.testsuite; $tot+=[int]$ts.tests; $fail+=[int]$ts.failures; $err+=[int]$ts.errors; $skip+=[int]$ts.skipped
  foreach($tc in $ts.testcase){
    if($tc.failure -or $tc.error){
      $m = if($tc.failure){$tc.failure.message}else{$tc.error.message}
      $m = ($m -replace "`r?`n"," ")
      if($m.Length -gt 200){$m=$m.Substring(0,200)}
      $fails += [PSCustomObject]@{Class=$tc.classname;Test=$tc.name;Msg=$m}
    }
  }
}
"Result XMLs (last $Minutes min): $($xmls.Count)"
"TOTAL=$tot FAIL=$fail ERR=$err SKIP=$skip"
if($fails.Count -gt 0){ "--- Failing tests ---"; $fails | Format-Table -Wrap -AutoSize }
