# DSH Mini 手工构建：不经过 Gradle，只用 Android SDK 自带工具链
# （和 android-build\build-manual.ps1 同一套路子，JDK/SDK 复用那边的）
$ErrorActionPreference = 'Stop'

$root = 'D:\whale home\dsh-mini'
$bd   = 'D:\whale home\android-build'
$sdk  = "$bd\sdk"
$bt   = "$sdk\build-tools\34.0.0"
$plat = "$sdk\platforms\android-34\android.jar"
$jdk  = "$bd\jdk\jdk-17.0.20.1+1"
$outDir  = "$root\out"
$proj = "$root\app\src\main"

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$outDir\gen", "$outDir\classes", "$outDir\dex" | Out-Null
$env:JAVA_HOME = $jdk

Write-Output '[1/6] aapt2 compile'
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$outDir\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Output '[2/6] aapt2 link'
& "$bt\aapt2.exe" link -o "$outDir\base-unsigned.apk" -I $plat `
  --manifest "$proj\AndroidManifest.xml" `
  -R "$outDir\res.zip" --java "$outDir\gen" `
  --min-sdk-version 24 --target-sdk-version 34 `
  --version-code 1 --version-name 0.1 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Output '[3/6] javac'
$srcs = @()
$srcs += (Get-ChildItem "$proj\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$outDir\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -nowarn `
  -classpath $plat -d "$outDir\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Output '[4/6] d8'
# class 一多命令行就超长；先打成 zip，d8 只收一个路径
$classesZip = "$outDir\classes.zip"
Compress-Archive -Path "$outDir\classes\*" -DestinationPath $classesZip -Force
& "$bt\d8.bat" --lib $plat --min-api 24 --output "$outDir\dex" $classesZip
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Output '[5/6] 装 dex 进 APK'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = "$outDir\app-unsigned.apk"
Copy-Item "$outDir\base-unsigned.apk" $apkPath -Force
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
try {
  $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
  $es = $entry.Open()
  $bytes = [System.IO.File]::ReadAllBytes("$outDir\dex\classes.dex")
  $es.Write($bytes, 0, $bytes.Length)
  $es.Dispose()
} finally { $zip.Dispose() }

Write-Output '[6/6] zipalign + 签名'
$ks = "$bd\debug.keystore"
if (-not (Test-Path $ks)) { throw "缺 debug.keystore: $ks" }
& "$bt\zipalign.exe" -f 4 "$apkPath" "$outDir\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android `
  --key-pass pass:android --ks-key-alias androiddebugkey `
  --out "$outDir\dsh-mini.apk" "$outDir\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

$size = [math]::Round((Get-Item "$outDir\dsh-mini.apk").Length / 1KB, 1)
Write-Output ""
Write-Output "=== 完成: $outDir\dsh-mini.apk  ($size KB) ==="
