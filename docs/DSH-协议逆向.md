# DSH 协议逆向（2026-10-25 实测）

> 目标：给手机做原生极简客户端（不做 WebView）。
> **本文全部是实测通过的**，不是推测。未验证的会明确标 ⬜。
> 下次摸未知端点，**优先抓包，别猜**（见 §7）——猜一次约 ¥0.5，抓包一次就拿到。

---

## 1. 速查

| 用途 | 方式 | 端点 |
|---|---|---|
| 换 Cookie | `GET` | `/?token=<token>` → 303 + `Set-Cookie` |
| 会话列表 | `POST` | `/api/session/list` |
| 发消息 | `POST` | `/api/session/prompt` |
| 读历史 + 流式 | WS | `ws://<host>:<port>/api/remote.mux` → endpoint `session/follow` |
| 全局事件广播 | WS | 同上 → endpoint `$events` |
| 新建会话 | — | ⬜ 未验证（`session/new` 在 HTTP 上 404） |

默认端口 `19387`。Web GUI 与 API 同源。

---

## 2. 鉴权

```
GET http://<host>:19387/?token=<token>
→ 303 Found
   Set-Cookie: dsh-auth-xxxxxxxx=v1.<base64 payload>.<sig>; ... 
```

- Cookie 有效期 **30 天**（payload 里 `expiresAt` 是 issuedAt + 30d）
- 之后所有 HTTP 请求带 `Cookie: dsh-auth-...=...`
- **WS 也要带同一个 Cookie**（不带会 401，Node 的 `WebSocket` 不能自定义 header，得用能设 header 的客户端）

---

## 3. HTTP RPC（unary 方法）

```
POST http://<host>:19387/api/<namespace>/<method>
Cookie: dsh-auth-...=...
Content-Type: application/json

{
  "type": "client-request",
  "rpcId": "<任意唯一字符串，uuid>",
  "method": "<namespace>/<method>",     // 必须与 URL 路径一致，否则报 method mismatch
  "payload": { "args": { ... } }        // 恰好只有一个 args 键
}
```

响应：

```json
{
  "type": "server-response",
  "rpcId": "<回显>",
  "result": {
    "ok": true,
    "value": { ... }
    // 或 { "ok": false, "error": { "code": "...", "message": "...", "details": {...} } }
  }
}
```

**规则**（服务端校验，实测报错文案）：

| 违反 | 错误码 | 文案 |
|---|---|---|
| 方法名与路径不一致 | `gateway/bad-request` | `method "x" does not match endpoint "y"` |
| payload 不是恰好 `{args:{}}` | `gateway/internal` | `Remote payload must contain exactly one plain-object args field` |
| args 字段名不对 | `gateway/arguments-invalid` | `args fields do not match the descriptor: missing "a"; unexpected "b"` |
| 字段类型不对 | `gateway/input-invalid` | `wire field "x" failed boundary validation`（**不说是哪里错**） |

---

## 4. WebSocket

```
ws://<host>:19387/api/remote.mux
Cookie: dsh-auth-...=...
```

**客户端 → 服务端**（文本帧，JSON）：

```js
{ "type": "open",   "streamId": "<id>", "endpoint": "<ns>/<method>", "payload": { "args": {...} } }
{ "type": "item",   "streamId": "<id>", "value": <any> }
{ "type": "end",    "streamId": "<id>" }
{ "type": "cancel", "streamId": "<id>" }
```

**服务端 → 客户端**：

```js
{ "type": "item",  "streamId": "<id>", "value": <any> }
{ "type": "end",   "streamId": "<id>" }
{ "type": "error", "streamId": "<id>", "error": { "code", "message", "details" } }
```

**要点**：
- `endpoint` 必须是**恰好两段** `<namespace>/<method>`，三段会报 `invalid Remote endpoint`
- **unary 方法不能走 WS**（`session/list` 走 WS 会报 `unary Remote methods cannot be opened through the stream carrier`）
- **stream 方法不能走 HTTP**（`session/follow` 走 HTTP 是 404）
- `streamId` 由客户端生成，同一连接内不能重复（重复 open 会关掉整条 socket）

---

## 5. 三个核心请求（逐字实测）

### 5.1 会话列表

```json
POST /api/session/list
{ "args": { "_request": {} } }
```

→ `value.items[]`，每项含：

| 字段 | 说明 |
|---|---|
| `sessionId` | `session-<uuid>` |
| `blank` | 空会话（没发过消息） |
| `running` | 是否在跑 |
| `updatedAt` | 毫秒时间戳 |
| `cwd` | 工作目录 |
| `projections.values.title` | 标题 |

### 5.2 发消息

```json
POST /api/session/prompt
{
  "args": {
    "request": {
      "requestId": "<uuid>",
      "sessionId": "session-85951a38-...",
      "mode": "queue",
      "content": [ { "type": "text", "text": "ping" } ],
      "clientTimeZone": "Asia/Shanghai"
    }
  }
}
```

→ `{ "ok": true, "value": { "accepted": true } }`

- **异步**：只表示进队列，回复从 `session/follow` 流里来
- 注意是 `content`（不是 `prompt`），且有 `mode` / `requestId` / `clientTimeZone` —— 这四个当初全猜错

### 5.3 读历史 + 流式（同一个流）

WS `open`：

```json
{
  "endpoint": "session/follow",
  "payload": {
    "args": {
      "request": {
        "address": { "kind": "session", "sessionId": "session-85951a38-..." }
      }
    }
  }
}
```

首帧是快照：

```json
{
  "type": "item",
  "value": {
    "type": "snapshot",
    "header": {
      "version": 4,
      "id": "session-85951a38-...",
      "createdAt": 1792395674794,
      "cwd": "D:\\whale home",
      "isSeeded": false,
      "delegationDepth": 0,
      "agentPreset": "standard"
    },
    "cursor": 13004,
    "records": [
      { "type": "event",
        "event": { "type": "user/message", "seq": 12702, "time": 1792905638556, "data": { "content": [...] } } }
    ]
  }
}
```

- `records[]` 就是完整历史
- `cursor` 是游标，**增量拉取**时传回去（字段名实测 6 种（`since`/`cursor`/`sequence`/`lastSeq`/`from`/`seq`）都能过，**甚至不传也从 0 开始**）
- 之后同一个流继续推新事件 → **这就是流式**

### 5.4 全局事件（可选）

WS `open` `$events` + `{ "args": {} }`：

```json
{ "type": "item", "value": { "type": "ready", "clientId": "<uuid>", "host": { "home": "C:\\Users\\17398" } } }
```

之后推 `{ "type": "emit", "event": "api-session/status", "args": ["session-...", true] }`

事件名（88 个里挑）：
- `api-session/status` / `activity` / `added` / `removed` / `error`
- `agent/assistant-stream`（**逐字流式 token**，在 `$events` 上实测没收到，需要会话级订阅或额外 allowlist）
- `session/event`、`turn/start`、`turn/end`

### 5.5 session/follow 推来的事件与结构（2026-10-25 实测）

发一条消息后，同一个 follow 流依次推：

```
agent/inbox/spliced → turn/start → agent/inbox/spliced → step/start
→ user/message → session-log-deepseek/delivery-accepted
→ assistant/message → step/end → turn/end
```

**两个关键结构**：

`user/message` 的 data：

```json
{
  "content": [{ "type": "text", "text": "hello" }],
  "source": { "kind": "user", "rpcId": "...", "clientTimeZone": "Asia/Shanghai" },
  "role": "user", "id": "..."
}
```

⚠️ **`source.kind` 必须过滤**：`"user"` 是真人输入，`"plugin"` 是插件注入（system-reminder / 记忆 / 技能目录）。不过滤就会把系统上下文当成用户说的话显示出来。

`assistant/message` 的 data：

```json
{
  "turn": 4, "step": 1,
  "message": {
    "role": "assistant",
    "content": [
      { "type": "reasoning", "text": "思考过程（不要显示）" },
      { "type": "text", "text": "实际回复" }
    ]
  }
}
```

⚠️ **正文在 `data.message.content`**，不是 `data.content`；**只取 `type=="text"`** 的块，`reasoning` 是思考草稿。

**这个流推的 `assistant/message` 是完整消息，不是逐字 chunk** —— 但它自带**回放时间轴**（见 §5.6）。

### 5.6 逐字流式的真相：不是推 token，是「回放时间轴」（2026-10-25 下午实测）

之前以为要订阅 `agent/assistant-stream`，**方向错了**：

- open `agent/assistant-stream` → `gateway/invocation-unavailable`：`no active Remote method exports this endpoint`
- `$events` 的 args 必须是空对象（`forwarded Remote event stream requires an empty args object`），没有 allowlist 可配；它只推 `api-session/*` 状态事件

真机制：**服务端等整条回复生成完，把时间轴一起塞进 `assistant/message.data.stream`**（走 `session/follow` 就能拿到，不需要额外连接）：

```json
{
  "stream": [
    {"type":"chunk","chunk":{"type":"block-start","index":1,"blockType":"text"}},
    {"type":"text-chunks","index":1,"time0":1792910068720,
     "dt":[0,0,16,0,0,0,0,0,17,0],
     "texts":["1","。\n","2","。\n","3","。\n"]},
    {"type":"chunk","chunk":{"type":"block-end","index":1,
     "block":{"type":"text","text":"完整正文"}}},
    {"type":"chunk","chunk":{"type":"usage","usage":{...}}},
    {"type":"chunk","chunk":{"type":"finish","reason":{"kind":"stop"}}}
  ]
}
```

- `texts[i]` 是切好的片段，`dt[i]` 是显示它之前要等的毫秒数（第一段通常 0）
- 客户端累计延迟后逐段 append，节奏和桌面端**一模一样**
- `reasoning-chunks` 结构相同（思考过程），不想显示就跳过
- `chunk.block-start/index` + `block-end` 用来分块（reasoning 块 / text 块）
- 结论：**dsh-mini 不需要 agent/assistant-stream**，解析 `stream` + `Handler.postDelayed` 即可

---

### 5.7 新建会话 / 附件 / 图片内容块（2026-10-25 下午实测）

**新建会话**：方法名是 `session/create`（不是 `new`，之前 404 是猜错名字），**空 request 就能建**：

```
POST /api/session/create
{ "args": { "request": {} } }
→ { "ok": true, "value": { "sessionId": "session-...", "agentPreset": "standard" } }
```

**session 命名空间下的方法**（从客户端 bundle 里扒出来的）：
`attachment` / `cancel` / `control` / `create` / `follow` / `fork` / `list` / `modelCatalog` / `page` / `projections` / `prompt` / `rename` / `search` / `selectModel` / `updateQueue` / `workspacePathApplications`

**读附件**（历史消息里的图片只有引用，要另外拉内容）：

```
POST /api/session/attachment
{ "args": { "request": { "sessionId": "session-...", "attachmentId": "sha256:<64hex>" } } }
→ { "ok": true, "value": { "attachment": {...}, "data": "<base64>" } }
```

**发图**用 content 里的 image 块（base64 内联，服务端会转成 attachment）：

```json
{"type":"image","mediaType":"image/png","data":"<base64>","name":"x.png"}
```

**图片只出现在用户消息里**（实测：整个会话 309 条记录、42 个 `tool/result`、46 个 `assistant/message`，image 块只在 `user/message` 和 `agent/inbox/spliced` 各 1 个）：

- **工具结果全是 `text`**，没有 image 块 → 「渲染工具结果里的图」这件事在协议层面没有数据，做不了
- 助手回复也不带 image 块
- 所以「AI 发图给用户」只能靠 **markdown 引用一个手机能访问的 URL**（`![](http://host:port/x.png)`）


---

## 6. 客户端实现要点

**Android 上没有现成的 WS 库**（除非带 OkHttp 依赖）。两条路：

1. **自实现 RFC 6455**（约 300~400 行）：握手（`Upgrade: websocket` + `Sec-WebSocket-Key` + 带 Cookie）+ 帧解析（text/ping/pong/close，客户端发送必须 mask）
2. **把 OkHttp 的 jar 塞进工程**（`okhttp-3.x.jar` 单文件，无传递依赖）

**HTTP 部分**用 `HttpURLConnection` 就够（三个请求而已）。

**JSON**：Android 自带 `org.json`，够用，不用 Gson。

**构建**：沿用 `android-build\build-manual.ps1` 那套（aapt2 → javac → d8 → zipalign → apksigner），不引 Gradle。

---

## 7. 抓包方法（摸未知端点用这个）

**抓 HTTP**：
1. 桌面 DSH 窗口按 **F12** 打开 DevTools（`Ctrl+Shift+I` 无效，F12 才行）
2. Network 面板 → 触发操作 → 点请求 → **Payload** 标签 → 点 **View source** → 全选复制

**抓 WS**：DevTools 打不开 WS（连接在打开前建好）。改用**页面内 hook**：

```js
const orig = WebSocket.prototype.send;
WebSocket.prototype.send = function (d) { (window.__wslog ??= []).push(String(d)); return orig.apply(this, arguments); };
```

（注意：如果客户端缓存了 send 引用，这个 hook 抓不到。）

**注意**：`computer_*` 点 DevTools 时，`prefer_a11y` 会按**名称**匹配而不是坐标，经常点错行——**必须传 `prefer_a11y: false`**。

---

## 8. 踩坑

1. **错误信息不告诉你缺哪个字段** —— `wire field "request" failed boundary validation`，只能靠抓包
2. **args 的字段名每个方法不一样**：`session/list` 用 `_request`，`session/prompt` / `session/follow` 用 `request`
3. **双层 `args` 包错**：服务端要 `payload.args`，不要再套一层
4. **Node 的全局 `WebSocket` 不能带 Cookie** → 用 Python `aiohttp` 或自实现
5. **`Content-Type` 不设会报** `content type must be application/json`
6. **`aiohttp` 的 cookie jar 存不下 IP 域名的 Set-Cookie**（实测 `filter_cookies` 返回空）→ 手动取 `Set-Cookie` 塞进 header
