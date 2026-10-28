# DSH Mini

DeepSeek Harness 的**安卓原生极简客户端**。不做 WebView 套壳，界面和通信全原生手写。

- **约 227 KB** 的 APK，**零第三方依赖**
- 纯 Java，WebSocket 协议自己实现（RFC 6455）
- 手工构建（aapt2 + javac + d8），不经过 Gradle

## 功能

- 连 DSH（填地址 + token），会话列表、历史、发消息
- **逐字流式**：按服务端给的 `stream` 时间轴回放，节奏和桌面端一致
- 消息气泡、markdown（粗体 / 代码块 / 标题 / 列表）
- **发图**：上拉菜单（相册 / 拍摄 / 文件），支持多选（最多 9 张）
- **看图**：历史里的附件走 `session/attachment` 拉回，多张全部显示
- **停止生成**：`session/cancel`，发送键变「停止」
- **新建对话**：`session/create`
- **白天 / 深色主题**：浅色下蓝气泡白字、背景图自动叠白纱
- **后台通知**：前台服务常驻，收到回复弹「鱼鱼回你了」
- 头像、对话列表背景、聊天背景都可自定义（中心裁剪，不拉伸）
- 账户余额（`account/getBalance`）
- 会话列表按时间过滤（1/3/7/30 天 / 全部）
- 切后台自动断、回前台自动重连；记住地址 / token / 上次会话 / 主题

## 适用范围

dsh-mini 是 DSH 的**客户端**：它自己不跑模型、不连 DeepSeek 的服务器，只跟你自己的
DSH 实例通信。

**能用**：

- DSH 跑在同一局域网的电脑上，手机连同一个 Wi-Fi
- DSH 跑在服务器上，**手机能直接访问到它的 HTTP 端口**（默认 19387）

**目前不支持**：

- **HTTPS / 反向代理 / 域名** —— dsh-mini 只走 `http://` + `ws://`。而且 DSH 有 Host 校验，
  用域名访问会被 403（需要让 DSH 信任那个 Host，见下文「Tailscale」）
- 客户端主动穿透 NAT —— 内网里的 DSH，手机在外网连不上，需要你自己搭 Tailscale / frp 之类

**一句话判断能不能用**：手机浏览器打开 `http://<地址>:19387` 能看到 DSH 的页面，dsh-mini 就能连。

## 怎么拿到地址和 token

DSH 启动后会把连接信息写到：

- Windows：`%USERPROFILE%\.dsh\dsh-web-url.txt`
- macOS / Linux：`~/.dsh/dsh-web-url.txt`

里面长这样：

```json
{
  "loopback": "http://127.0.0.1:19387/?token=xxxxxxxx",
  "lan": [
    "http://192.168.1.2:19387/?token=xxxxxxxx"
  ],
  "port": 19387
}
```

- **地址**：填 `lan` 里那条（手机能访问到的 IP），比如 `http://192.168.1.2:19387`
  - `loopback` 里的 `127.0.0.1` **手机访问不到**，别填这个
- **token**：URL 里 `token=` 后面那串（43 位）
- **重要**：DSH 每次重启都会生成新 token，旧的立刻失效

### 自动同步 token（可选，省事）

电脑上跑一个脚本盯着 `dsh-web-url.txt`，一变就把 token 写到能被手机访问的地方：

```powershell
# dsh-mini\sync-token.ps1 每 3 秒检查一次，把 token 写到
# D:\whale home\手机相册工具\share\dsh-token.txt
pwsh -NoProfile -WindowStyle Hidden -File "D:\whale home\dsh-mini\sync-token.ps1"
```

再用任意静态服务把它暴露出来（例如 `python -m http.server 8898 --directory <那个目录>`），
dsh-mini 点「连接」时会先 `GET http://<同一台主机>:8898/dsh-token.txt`，
**拿到就用新的**，拿不到才用设置里存的。

### Tailscale / 远程访问

可以用，而且局域网和远程能共用同一个地址。关键只有一条配置：

```yaml
# ~/.dsh/profiles/desktop/cordis.patch.yml
- id: connection
  name: "@deepseek-ai/dsh-client-connection"
  inject: [ webStartup ]
  config:
    trustedHosts: [ "node.xxx.ts.net" ]
```

注意 **`id` 必须是 `connection`**（不是 `webserver`，也不是包名 `client-connection`），
写错会静默失效、WS 握手继续 403。

一键脚本见 [`setup.ps1`](setup.ps1)（自动读 token、补配置、起服务、建自启），
完整步骤和排错见 [`REMOTE.md`](REMOTE.md)。

## 构建

需要 Android SDK（platform-tools / platforms;android-34 / build-tools;34.0.0）和 JDK 17：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build-mini.ps1
```

产物：`out\dsh-mini.apk`

构建脚本里的 SDK / JDK 路径按自己机器改。

## 协议

DSH 没有公开 API，这个项目的协议全部是**黑盒逆向**出来的，细节写在：

- [`docs/DSH-协议逆向.md`](docs/DSH-协议逆向.md)

包括：鉴权、HTTP RPC、WebSocket 帧格式、`session/follow`、逐字流式的真相（**回放时间轴**）、
`session/create`、`session/attachment`、`session/cancel`、`account/getBalance`、图片内容块，以及一堆踩过的坑。

## 结构

```
app/src/main/java/com/whale/mini/
  MainActivity.java     界面 + 消息渲染 + 流式回放 + 选图/拍照
  SettingsActivity.java 设置（服务器 / 头像 / 双背景 / 主题 / 显示范围 / 余额 / 关于）
  DshService.java       前台服务：后台保持连接 + 回复通知
  DshApi.java           HTTP RPC 层
  WsClient.java         自实现 WebSocket（文本帧 + ping/pong + 心跳）
  Md.java               极简 markdown → Spannable
  Theme.java            深色 / 浅色两套配色
  WhaleTail.java        思考中的摇尾巴动画（24 帧）
  ImageProvider.java    给相机写临时文件的极简 ContentProvider
app/src/main/res/
  drawable/tail_01..24.png   思考中动画帧
  drawable/ic_*.xml          矢量图标（返回 / 相册 / 拍摄 / 文件 / 通知）
  mipmap-*/ic_launcher.png   应用图标
test/                    纯 Java 探针：JVM 上直接调协议，不用装 APK
```

## 截图

（暂无，欢迎补）

## License

自用项目，随意。协议逆向部分仅用于个人学习和自建服务。
