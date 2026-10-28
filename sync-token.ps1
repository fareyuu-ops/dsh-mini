# 把 DSH 的最新 token 同步给手机（dsh-mini 会自动来取）
#
# 用法：开着这个窗口就行，或者丢进启动项：
#   powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "D:\whale home\dsh-mini\sync-token.ps1"
#
# 原理：DSH 每次重启都会重写 ~\.dsh\dsh-web-url.txt（里面带新 token），
# 这里每 3 秒看一眼，变了就把 token 写到 8898 能服务的目录，
# dsh-mini 连接前会 GET http://<你的局域网IP>:8898/dsh-token.txt 拿它。

$ErrorActionPreference = "SilentlyContinue"

$src     = Join-Path $env:USERPROFILE ".dsh\dsh-web-url.txt"
$dstDir  = "D:\whale home\手机相册工具\share"
$dst     = Join-Path $dstDir "dsh-token.txt"
$last    = ""

Write-Host "watching $src"
Write-Host "writing  $dst"

while ($true) {
    try {
        if (Test-Path $src) {
            $json = Get-Content $src -Raw -Encoding UTF8 | ConvertFrom-Json
            $url = $json.loopback
            if ($url) {
                $m = [regex]::Match($url, "token=([A-Za-z0-9_\-]+)")
                if ($m.Success) {
                    $token = $m.Groups[1].Value
                    if ($token -ne $last) {
                        if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
                        Set-Content -Path $dst -Value $token -NoNewline -Encoding UTF8
                        $last = $token
                        Write-Host ("[{0}] token updated: {1}..." -f (Get-Date -Format "HH:mm:ss"), $token.Substring(0, 8))
                    }
                }
            }
        }
    } catch {
        # DSH 重启的瞬间文件可能半截，下一轮再读
    }
    Start-Sleep -Seconds 3
}
