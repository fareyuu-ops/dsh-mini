# 远程访问（Tailscale）

目标：手机**不在家**也能用 dsh-mini（局域网 + 流量同一个地址，不用切换）。

## 一、电脑端：跑一次脚本

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\whale home\dsh-mini\setup.ps1"
```

它会：读 token → 确保 `trustedHosts` 配置 → 同步 token 到 8898 → 起静态服务 → 建开机自启。

**如果提示「没有任何 trustedHosts / 需要重启 DSH」**：重启一次 DSH，配置在启动时加载。

## 二、手机端：三件事

1. 装 **Tailscale**，登录和电脑同一个账号
2. Tailscale 设置里：
   - 打开 **Use Tailscale DNS**（主界面 → 设置）
   - **把 dsh-mini 加进「允许使用 VPN 的 App」**（split tunneling，国产 ROM 常有这个开关，不加就是 DNS 解析失败）
3. dsh-mini 里填：
   - 地址：`https://<机器名>.<你的 tailnet>.ts.net`（脚本末尾会打印）
   - token：脚本末尾会打印

## 三、排错

| 现象 | 原因 |
|---|---|
| `Unable to resolve host` | 手机 split tunneling 没勾 dsh-mini，或 Use Tailscale DNS 没开 |
| `403 Forbidden`（WS） | `cordis.patch.yml` 里 `id: connection` 的 `trustedHosts` 缺这个域名 |
| `401 Unauthorized`（WS） | cookie 和请求的 Host 对不上——**默认端口不能写进 origin**（代码已修） |
| `login: no Set-Cookie (401)` | token 过期（DSH 重启会换 token），用脚本打印的那个 |
| 能连但很慢 | 走了 DERP 中继（`tailscale status` 看是 `relay` 还是 `direct`）；直连一般 50ms，中继 300ms+ |

## 四、给另一条 AI 的一句话

> 读 `dsh-mini/REMOTE.md`，跑 `setup.ps1`，把输出的三个值填进 App。**不要改 `webserver` 下的 `trustedHosts`**——它在 `id: connection` 里才生效。

## 关键事实（省得重新试错）

- DSH 有 Host 栅栏：`Host` 必须是 loopback / 绑定 IP / `trustedHosts` 条目
- `trustedHosts` 属于 **`id: connection`**（包名是 `@deepseek-ai/dsh-client-connection`）
- cookie 的 authority **绑定登录时的 Host（含端口归一化）**，所以默认端口不能写进 origin
- 普通 HTTP 登录不挡域名，**只有 WS 严格**——所以「列表能看、聊天 401」是典型症状
