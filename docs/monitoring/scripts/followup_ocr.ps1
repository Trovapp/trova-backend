param([Parameter(Mandatory=$true)][string]$ImagePath)
# Offline evidence inspection only; no device input or network requests.
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Graphics.Imaging.BitmapDecoder,Windows.Foundation,ContentType=WindowsRuntime] > $null
[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime] > $null
[Windows.Media.Ocr.OcrResult,Windows.Foundation,ContentType=WindowsRuntime] > $null
[Windows.Graphics.Imaging.SoftwareBitmap,Windows.Foundation,ContentType=WindowsRuntime] > $null
function AwaitResult($operation,$resultType) {
 $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
 $task=$method.MakeGenericMethod($resultType).Invoke($null,@($operation)); $task.Wait(); $task.Result
}
$memory=New-Object System.IO.MemoryStream
$bytes=[System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $ImagePath).Path)
$memory.Write($bytes,0,$bytes.Length)
$memory.Position=0
$stream=[System.IO.WindowsRuntimeStreamExtensions]::AsRandomAccessStream($memory)
$decoder=AwaitResult ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap=AwaitResult ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
$result=AwaitResult ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
$rows=@(foreach($line in $result.Lines) {
 if($line.Text -match 'memo|offline|delete|친구|저장|연결|네트워크|삭제|완료|이삭|연화|광안|변경|다시|불러|사라|없어') {
  [pscustomobject]@{text=$line.Text;bounds=@($line.Words | ForEach-Object { $_.BoundingRect.ToString() })}
 }
})
ConvertTo-Json -InputObject $rows -Compress -Depth 4
