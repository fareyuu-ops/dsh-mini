# PROJECT_INDEX · dsh-mini

> DSH 的**安卓原生客户端**。最后核验：2026-10-28
> 换窗口接手时读这一份就够；协议细节在 `知识库\DSH-协议逆向.md`，别重复造轮子。

## 一句话

不做 WebView 套壳，界面和通信全原生手写；**239 KB、零依赖、纯 Java**，手工构建（aapt2 + javac + d8，不走 Gradle）。

- 仓库：https://github.com/fareyuu-ops/dsh-mini
- 本地产物：`dsh-mini\out\dsh-mini.apk`
- 发布方式：复制到 `手机相册工具\share\`，从 `http://192.168.1.2:8898/dsh-mini.apk` 下载

## 当前状态（全部手机实测通过）

| 功能 | 说明 |
|---|---|
| 连接 / 会话列表 / 历史 | 填地址 + token，cookie 30 天；**连接前自动从 `:8898/dsh-token.txt` 拉最新 token** |
| 逐字流式 | 按 `assistant/message.data.stream` 的 `texts[] / dt[]` **时间轴回放**（不是推 token） |
| 思考中动画 | `step/start` 挂胶囊，24 帧 GIF 裁帧的摇尾巴 |
| 发图 / 看图 | 多选（最多 9 张）；待发缩略图条可删单张；历史多图全部显示（走 `session/attachment`） |
| 拍照 | MediaStore 条目 + URI 持久化（相机期间 Activity 被回收也能读回） |
| 上拉菜单 | 相册 / 拍摄 / 文件，微信风格白方块 + 矢量图标 |
| **工作区** | 列表按工作区分组；顶栏显示当前工作区，点击切换（含「全部 / 未分组」）；**新建对话落进当前工作区**（`session/create` 带 `workspaceId`） |
| **归档** | 长按 → 归档（`workspace/archiveSession`）；`archivedSessionIds` 缓存到 prefs，**归档的会话不显示** |
| **长按菜单** | 重命名 / 归档（不用左滑——和点击冲突，已砍） |
| **下拉刷新** | 列表顶部下拉，列表跟手 + 回弹 |
| 新建对话 | 顶栏 ⊕ 图标，落当前工作区 |
| 停止生成 | `session/cancel`，发送键变「停止」暗红，`turn/end` 自动恢复 |
| 切换模型 / 强度 | 聊天页 ⋯ →「切换模型 / 思考强度」，当前项打勾（本地缓存） |
| **白天模式** | `Theme.java` 两套配色，设置里切换；浅色下蓝气泡白字、背景图叠 70% 白纱、状态栏图标反色 |
| **后台通知** | 前台服务常驻，退后台接管 WS，收到回复→「鱼鱼回你了」；`turn/start` 才复位 |
| 设置页 | 服务器 / 头像 / 列表背景 / 聊天背景 / **主题** / 显示范围 / **账户余额** / 关于 |
| 重连 | 切后台主动断（转交服务），回前台按当前会话重连；选图期间不断不重连 |
| 记忆 | 地址、token、上次会话、主题、**当前工作区**、归档列表都存 SharedPreferences |

## 那些踩过的坑（接手前必读）

1. **Activity 会被系统重建**：选图/拍照期间华为经常回收 MainActivity。所以
   `pendingImages` 是 **static**、拍照 URI 存 SharedPreferences、读图结果要更新
   **静态 `current` 指向的实例**（不是捕获时那个）。
2. **主题切换**：设置页会直接改静态 `Theme.dark`，主界面不能拿它跟 prefs 比
   （永远相等），要用实例字段 `appliedDark`。
3. **`turn/end` 紧跟 `assistant/message`**：后台通知别在 turn/end 复位，否则
   「鱼鱼回你了」被瞬间刷掉；改成 `turn/start` 复位。
4. **通知图标必须单色**：彩色 PNG 会显示成白块，用 `ic_notify.xml`（白色剪影）。
5. **前台服务要传全参数**：`onPause` 只传 sessionId 不够，Service 里 url/token
   为 null 时 `connect()` 会直接 return（已改成从 SharedPreferences 兜底）。
6. **⚠️ 别给列表 item 设任何 `setOnLongClickListener` / `setOnClickListener`**：
   会让 View 变成 longClickable/clickable，`onTouchEvent` 在 DOWN 就 `return true`
   消费掉事件，ListView 的 `onItemClick` 永远不触发。**这个坑踩了两次**。
   长按一律挂在 `list.setOnItemLongClickListener` 上。
7. **左滑和点击天然冲突**（自定义 SwipeRow 会抢事件流），已放弃左滑，改用长按菜单。
8. **空会话（`blank=true`）会被服务端返回但通常没意义**，需要显式过滤；
   而「新建但还没发消息」的对话也是 blank，看需求决定显不显示。
9. **`$out` 这类短变量名在 PowerShell 5.1 里出过诡异 null**，脚本里改叫 `$outDir`。
10. **`build-mini.ps1` 必须带 UTF-8 BOM**：5.1 按 GBK 读无 BOM 文件时中文注释会解析错。
11. **d8 会因命令行超长失败**：先把 classes 打成 zip，d8 只收一个路径。

## 工作区接口（2026-10-28 摸出来的）

- `session/list` **没有** workspace 字段，只有 `cwd`
- `workspace/follow`（**WebSocket stream**）返回 baseline：
  `{items:[{workspaceId,path,title,sessionIds[]}], archivedSessionIds[], pinnedSessionIds[]}`
- `session/create` 支持 `workspaceId` / `cwd` / `agentPreset`
- `workspace/archiveSession` 参数是 `{request:{sessionId, stopActivity?}}`，
  返回全部 `archivedSessionIds`
- 探针：`test/TestWorkspace.java`（JVM 直连，不需要装 APK）

## 代码结构

```
app/src/main/java/com/whale/mini/
  MainActivity.java     界面 + 消息气泡 + 流式回放 + 选图 + 工作区/分组
  SettingsActivity.java 设置（服务器/头像/双背景/主题/范围/余额/关于）
  DshService.java       前台服务：后台保持连接 + 回复通知
  DshApi.java           HTTP RPC + workspace/follow 基线
  WsClient.java         自实现 RFC6455（文本帧 + ping/pong + 心跳）
  Md.java               极简 markdown → Spannable（粗体/代码块/标题/列表）
  Theme.java            深/浅两套配色
  WhaleTail.java        24 帧尾巴动画（GIF 抽帧）
  ImageProvider.java    给相机写临时文件（现已改用 MediaStore，保留未删）
app/src/main/res/
  drawable/tail_01..24.png   思考中动画帧（从用户录屏抽的）
  drawable/ic_*.xml          矢量图标（返回/相册/拍摄/文件/通知/新建/设置）
  mipmap-*/ic_launcher.png   应用图标（Q 版毛绒虎鲸，白底圆角）
test/                    纯 Java 探针：JVM 上直接调协议，不用装 APK
sync-token.ps1           盯 dsh-web-url.txt，把新 token 写到 8898 共享目录
```

## 开机自启（已配）

`%APPDATA%\...\Start Menu\Programs\Startup\` 里两个快捷方式：

- **DSH-Mini-Token** → `pwsh -File dsh-mini\sync-token.ps1`
- **DSH-Mini-Share** → `python -m http.server 8898 --directory 手机相册工具\share`


## 构建

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\whale home\dsh-mini\build-mini.ps1"
```

依赖 `android-build` 里的 JDK17 + SDK（android-34 / build-tools 34.0.0 / debug.keystore）。

## 协议

**全部黑盒逆向**，细节在 `知识库\DSH-协议逆向.md`：

- §5.6 逐字流式 = 时间轴回放（`texts[] + dt[]`）
- §5.7 `session/create`、`session/attachment`、图片只存在于用户消息
- 还有鉴权、WS 帧格式、踩坑表

## 已知限制

- **本机命令行连不上 GitHub**：curl/git 的 schannel 报 `SEC_E_NO_CREDENTIALS`，openssl 后端被 reset，Clash 7890 也 TLS EOF；但 DSH 的 `web_fetch` 能通 API。推送靠 GitHub Desktop 手动做。
- 工具结果里的图片**做不了**——协议里 `tool/result` 全是 text，没有 image 块。
- 空标题会话服务端返回字符串 `"null"`，客户端统一收成 `(未命名)`。

## 怎么让图在手机上显示（重要，别的窗口也照这个做）

**唯一可行的方式**：助手回复里写 markdown，引用一个**手机能访问的 URL**。

```markdown
![](http://192.168.1.2:8898/文件名.png)
```

**为什么**：`assistant/message` / `tool/result` 都不带 image 块（实测 42 个工具结果全是 text），
图片块只存在于 `user/message`。所以「把图塞进回复」在协议层面不存在，只能引用 URL。

**步骤**：

1. 图片放进 `D:\whale home\手机相册工具\share\`
2. 8898 服务要在跑（`手机相册工具\server.py`，或临时 `python -m http.server 8898 --directory share`）
3. 回复里写 `![](http://192.168.1.2:8898/xxx.png)`
4. dsh-mini 会在气泡下方挂一张图（异步加载 + 内存缓存）

**注意**：

- 只认 `http://` / `https://`；`D:\...` 这种本地路径手机访问不到，写了也只显示成文字
- 图压到 **1 MB 以内**（手机加载更快；前面那张角色图从 693 KB 压到 115 KB 就是这么做的）
- 8898 只绑局域网：手机用 4G 或不在同一 Wi-Fi 时这招失效
- 反过来「手机发图给 AI」走的是另一条路（相册选图 → base64 → image 块），不受这个限制


## 待办

- [x] ~~体验小毛病~~（2026-10-26：图片圆角+分侧对齐、圆头像、白天模式）
- [x] ~~上拉菜单~~（相册/拍摄/文件；语音因华为无本地识别移除）
- [x] ~~停止生成~~（`session/cancel`）
- [x] ~~账户余额~~（`account/getBalance`）
- [x] ~~按时间过滤~~（设置里「显示范围」）
- [x] ~~后台通知~~（B 方案前台服务）
- [x] ~~工作区分组 / 选择器 / 归档隐藏~~（2026-10-28）
- [x] ~~长按菜单（重命名 / 归档）~~（2026-10-28）
- [x] ~~下拉刷新~~（2026-10-28）
- [ ] 消息时间戳（记录里有 `time`）
- [ ] 滚动到底部小箭头
- [ ] 长按整条消息复制（注意：别再和列表点击打架）
- [ ] 清空当前会话（仅本地）
- [ ] 文件按钮只支持图片，非图片要 `receiptId` 上传（协议未摸）
- [ ] 通知正文取不到回复内容（`extractText` 在 Service 里返回空，需要查 `data` 结构）
- [ ] 聊天页同步显示当前模型 / 思考强度（现在只有本地缓存，不读服务端）
- [ ] 工作区改名 / 新建工作区（`workspace/rename`、`workspace/create` 已有）
- [ ] 新版本发布到 GitHub Releases；README 补截图
- [ ] Tailscale 远程（卡在 Host 校验，见下文）
  - 返回：`{status:"ready", value:[{currency,balance}], bonusWallets:[...]}`；失败时 `{status:"failed"}` 或 `null`
  - 实测 2026-10-25：`¥37.54`
  - 可顺带：刷新按钮、赠送钱包、`account/getState` 拿 `links.topUpUrl` / `usageUrl`
- [ ] **会话列表按时间过滤**（2026-10-25 用户提）
  - 设置里加「显示范围」：1 天 / 3 天 / 7 天 / 30 天 / 全部（默认「全部」）
  - `session/list` 每条有 `updatedAt`（毫秒），`parseNames()` 里按 `now - range` 过滤即可，**纯客户端逻辑**
  - 副作用：归档的旧会话通常很久没动，会被自然挡掉——相当于绕开了「服务端不给归档字段」的限制
- [ ] **回复完成通知**（2026-10-26 用户提，待定方案）
  - 前提：现在 `onPause` 主动 `closeWs()`，后台收不到消息 → 要通知就必须在后台保持连接
  - 方案 A（轻量）：发完消息切后台 → 保持 WS → 收到 `turn/end` 发系统通知 → 再断开。不用前台服务，但受 Doze 影响可能被杀
  - 方案 B（可靠）：前台服务常驻（会有一条常驻通知），任何时候都能收
  - 权限：Android 8+ 要 NotificationChannel，Android 13+ 要运行时申请 `POST_NOTIFICATIONS`
- [ ] 新版本发布到 GitHub Releases（现在只有源码）
- [ ] README 里补截图
- [ ] 如果有 DSH 新版本改了协议，先按 `知识库\DSH-协议逆向.md` §7 抓包，别猜
- [ ] **Tailscale 远程访问（2026-10-25 卡住，下次从这里接）**
  - 已做：Tailscale Serve 启用 + `tailscale serve --bg --http=80 http://192.168.1.2:19387`，入口 `http://node.tail16916c.ts.net/`
  - 已证：token 换 cookie 经 tailnet 返回 303；`tailscale ping` 到手机通（走 192.168.1.3）
  - **卡点**：`Host` 校验 403。根因是 **Connection 的 `trustedHosts` 来自 `ctx.webStartup.trustedHosts`**
    （启动时注入，等价于 `dsh web --trusted-host`），**不是插件配置**——写进 `cordis.patch.yml` 的
    `webserver.config` 会被 schema 忽略（已写但无效，原始备份 `bsk\cordis.patch.yml.bak-20261025-1707`）
  - 下一步：确认桌面版是否走 `dsh web` 的 CLI 分支；若是，给快捷方式/启动脚本加
    `--trusted-host node.tail16916c.ts.net`，或找别的办法注入 `webStartup.trustedHosts`
  - 另：手机端 MagicDNS 没解析域名；直接用 IP `http://100.122.22.82` 可绕 DNS，但同样撞 403
