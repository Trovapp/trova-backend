# 교차 판정 GPT 쪽(#437) — 샘플마다 Codex(구독)에 프레임 8장 + gpt_prompt.txt(제목·설명·자막)를 넣고 JSON을 받는다.
# 명령 실행 없이 답만 받으므로 read-only 샌드박스. 키·오디오는 넘기지 않는다.
# 사용(PowerShell): .\eval\extraction\cross_gpt.ps1 -Run eval\extraction\cross\20261116 -Ids c001,c002
param(
    [Parameter(Mandatory = $true)][string]$Run,
    [Parameter(Mandatory = $true)][string[]]$Ids,
    [int]$TimeoutSec = 600
)
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$work = Join-Path $repo "pipeline-test\work\cross"
$schema = Join-Path $repo "eval\extraction\cross\gpt_schema.json"
$outDir = Join-Path (Resolve-Path (Join-Path $repo $Run)) "gpt"
New-Item -ItemType Directory -Force $outDir | Out-Null
foreach ($id in ($Ids -split ',')) {
    $id = $id.Trim()
    $target = Join-Path $outDir "$id.json"
    $prompt = Join-Path $work "$id\gpt_prompt.txt"
    if ((Test-Path $target) -or -not (Test-Path $prompt)) { continue }
    $frames = Get-ChildItem (Join-Path $work "$id\frames") -Filter *.jpg | Sort-Object Name | ForEach-Object { "-i `"$($_.FullName)`"" }
    $log = Join-Path $work "$id\gpt_log.txt"
    $cmd = "codex exec -s read-only --skip-git-repo-check -C `"$work\$id`" --output-schema `"$schema`" -o `"$target`" $($frames -join ' ') - < `"$prompt`" > `"$log`" 2>&1"
    $started = Get-Date
    $proc = Start-Process -FilePath cmd.exe -ArgumentList "/c", $cmd -PassThru -WindowStyle Hidden
    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        Stop-Process -Id $proc.Id -Force
        Remove-Item $target -ErrorAction SilentlyContinue
        Write-Output "$id TIMEOUT"
        continue
    }
    $sec = [int]((Get-Date) - $started).TotalSeconds
    if (Test-Path $target) { Write-Output "$id ok ${sec}s" } else { Write-Output "$id FAIL ${sec}s (log: $log)" }
}
