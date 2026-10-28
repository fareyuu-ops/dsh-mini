# DSH Mini 一键配置：让手机（局域网 / Tailscale 远程）能连上你的 DSH
#
# 用法（在电脑上，PowerShell 7 或 5.1 都行）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File "setup.ps1"
#
# 它做五件事：
#   1. 读 DSH 当前的 host/port/token
#   2. 确保 cordis.patch.yml 里有 id: connection 的 trustedHosts（远程必须）
#   3. 把 token 同步到 8898 共享目录（手机自动拉取用）
#   4. 起 8898 静态服务（如果没在跑）
#   5. 建开机自启快捷方式
#
# 幂等：重复跑不会出问题。

$ErrorActionPreference = "Stop"

$dshHome   = Join-Path $env:USERPROFILE ".dsh"
$urlFile   = Join-Path $dshHome "dsh-web-url.txt"
$patchFile = Join-Path $dshHome "profiles\desktop\cordis.patch.yml"
$shareDir  = "D:\whale home\手机相册工具\share"
$tokenFile = Join-Path $shareDir "dsh-token.txt"
$miniDir   = "D:\whale home\dsh-mini"
$startup   = [Environment]::GetFolderPath("Startup")

function Say($msg) { Write-Host "[dsh-mini] $msg" }

# ---- 1. 读 DSH 连接信息 ----
if (-not (Test-Path $urlFile)) { throw "找不到 $urlFile —— DSH 启动过吗？" }
$info = Get-Content $urlFile -Raw -Encoding UTF8 | ConvertFrom-Json
$loop = $info.loopback
if (-not $loop) { throw "dsh-web-url.txt 里没有 loopback 地址" }

$token = ([regex]::Match($loop, "token=([A-Za-z0-9_\-]+)")).Groups[1].Value
if (-not $token) { throw "从 $loop 里没解析出 token" }

$lan = ""
if ($info.lan) {
    # 优先挑真正的私网地址（192.168 / 10. / 172.16-31），排除 Tailscale 的 100.64/10
    foreach ($cand in @($info.lan)) {
        $h = ([regex]::Match($cand, "//([^:/]+)")).Groups[1].Value
        if ($h -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)') { $lan = $cand; break }
    }
    if (-not $lan) { $lan = @($info.lan)[0] }
}
$lanHost = ([regex]::Match($lan, "//([^:/]+)")).Groups[1].Value
$port = if ($info.port) { $info.port } else { 19387 }
Say "DSH: ${lanHost}:${port}  (token ${token}...)"

# ---- 2. 确保 trustedHosts 配置存在 ----
# 关键：这一段属于 id: connection（不是 webserver，也不是包名 client-connection）
$hostsBlock = @"
- id: connection
  name: "@deepseek-ai/dsh-client-connection"
  inject: [ webStartup ]
  config:
    trustedHosts:
"@

$tsName = $env:DSH_TS_HOST   # 可选：设置环境变量 DSH_TS_HOST=xxx.ts.net 指定域名
if (-not $tsName) {
    # 没指定就扫一遍已有的 ts.net 域名（tailscale status 里的 DNSName）
    $tsExe = "C:\Program Files\Tailscale\tailscale.exe"
    if (Test-Path $tsExe) {
        try {
            $statusJson = & $tsExe status --json 2>$null | ConvertFrom-Json
            $tsName = $statusJson.Self.DNSName
            if ($tsName) { $tsName = $tsName.TrimEnd('.') }
        } catch { }
    }
}

if (Test-Path $patchFile) {
    $patch = Get-Content $patchFile -Raw -Encoding UTF8
    if ($patch -match "(?m)^- id: connection\s*$") {
        Say "cordis.patch.yml 里已有 id: connection（跳过改写，请自行确认 trustedHosts 是否包含你的域名）"
    } else {
        $add = "`r`n# ── 远程访问：Host 信任列表（dsh-mini setup.ps1 添加）─────────────`r`n"
        $add += $hostsBlock
        if ($tsName) { $add += "      - `"$tsName`"`r`n" }
        $add += "      - `"localhost`"`r`n"
        Add-Content -Path $patchFile -Value $add -Encoding UTF8
        Say "已给 cordis.patch.yml 追加 trustedHosts（域名: $tsName）"
        Say "⚠️ 需要重启 DSH 才生效"
    }
} else {
    Say "⚠️ 没找到 $patchFile，跳过 trustedHosts 配置"
}

# ---- 3. token 同步 ----
if (-not (Test-Path $shareDir)) { New-Item -ItemType Directory -Path $shareDir -Force | Out-Null }
Set-Content -Path $tokenFile -Value $token -NoNewline -Encoding UTF8
Say "token 已写入 $tokenFile"

# ---- 4. 起 8898 ----
$listening = Get-NetTCPConnection -LocalPort 8898 -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    $py = "C:\Users\17398\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
    if (-not (Test-Path $py)) { $py = "python" }
    Start-Process -FilePath $py -ArgumentList @("-m", "http.server", "8898", "--directory", $shareDir) -WindowStyle Hidden
    Start-Sleep -Seconds 2
    Say "已启动 8898 静态服务"
} else {
    Say "8898 已在运行"
}

# ---- 5. 开机自启 ----
$w = New-Object -ComObject WScript.Shell
$tokenLnk = Join-Path $startup "DSH-Mini-Token.lnk"
$shareLnk = Join-Path $startup "DSH-Mini-Share.lnk"
$pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (-not $pwsh) { $pwsh = "powershell.exe" }

if (-not (Test-Path $tokenLnk)) {
    $s = $w.CreateShortcut($tokenLnk)
    $s.TargetPath = $pwsh
    $s.Arguments = "-NoProfile -WindowStyle Hidden -File `"$miniDir\sync-token.ps1`""
    $s.WorkingDirectory = $miniDir
    $s.Save()
    Say "已建开机自启: DSH-Mini-Token"
}
if (-not (Test-Path $shareLnk)) {
    $py2 = "C:\Users\17398\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
    $s2 = $w.CreateShortcut($shareLnk)
    $s2.TargetPath = $py2
    $s2.Arguments = "-m http.server 8898 --directory `"$shareDir`""
    $s2.WorkingDirectory = $shareDir
    $s2.Save()
    Say "已建开机自启: DSH-Mini-Share"
}

# ---- 收尾提示 ----
Write-Host ""
Write-Host "==================== 手机端要填的 ===================="
Write-Host "局域网:  http://${lanHost}:${port}"
if ($tsName) { Write-Host "远程:    https://$tsName" }
Write-Host "token:   $token"
Write-Host ""
Write-Host "远程还需要（手机侧，脚本改不了）："
Write-Host "  1. 装 Tailscale 并登录同一账号"
Write-Host "  2. 设置里把 dsh-mini 加进「允许使用 VPN 的 App」（split tunneling）"
Write-Host "  3. 打开 Use Tailscale DNS"
Write-Host "======================================================"
