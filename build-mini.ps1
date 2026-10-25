# DSH Mini 手工构建：不经过 Gradle，只用 Android SDK 自带工具链
# （和 android-build\build-manual.ps1 同一套路子，JDK/SDK 复用那边的）
$ErrorActionPreference = 'Stop'

$root = 'D:\whale home\dsh-mini'
$bd   = 'D:\whale home\android-build'
$sdk  = "$bd\sdk"
$bt   = "$sdk\build-tools\34.0.0"
$plat = "$sdk\platforms\android-34\android.jar"
$jdk  = "$bd\jdk\jdk-17.0.20.1+1"
$out  = "$root\out"
$proj = "$root\app\src\main"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$out\gen", "$out\classes", "$out\dex" | Out-Null
$env:JAVA_HOME = $jdk

Write-Output '[1/6] aapt2 compile'
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Output '[2/6] aapt2 link'
& "$bt\aapt2.exe" link -o "$out\base-unsigned.apk" -I $plat `
  --manifest "$proj\AndroidManifest.xml" `
  -R "$out\res.zip" --java "$out\gen" `
  --min-sdk-version 24 --target-sdk-version 34 `
  --version-code 1 --version-name 0.1 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Output '[3/6] javac'
$srcs = @()
$srcs += (Get-ChildItem "$proj\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
  -classpath $plat -d "$out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Output '[4/6] d8'
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --lib $plat --min-api 24 --output "$out\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Output '[5/6] 装 dex 进 APK'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = "$out\app-unsigned.apk"
Copy-Item "$out\base-unsigned.apk" $apkPath -Force
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
try {
  $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
  $es = $entry.Open()
  $bytes = [System.IO.File]::ReadAllBytes("$out\dex\classes.dex")
  $es.Write($bytes, 0, $bytes.Length)
  $es.Dispose()
} finally { $zip.Dispose() }

Write-Output '[6/6] zipalign + 签名'
$ks = "$bd\debug.keystore"
if (-not (Test-Path $ks)) { throw "缺 debug.keystore: $ks" }
& "$bt\zipalign.exe" -f 4 "$apkPath" "$out\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android `
  --key-pass pass:android --ks-key-alias androiddebugkey `
  --out "$out\dsh-mini.apk" "$out\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

$size = [math]::Round((Get-Item "$out\dsh-mini.apk").Length / 1KB, 1)
Write-Output ""
Write-Output "=== 完成: $out\dsh-mini.apk  ($size KB) ==="
