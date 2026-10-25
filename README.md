# DSH Mini

DeepSeek Harness 的**安卓原生极简客户端**。不做 WebView 套壳，界面和通信全原生手写。

- 约 250 KB 的 APK，**零第三方依赖**
- 手工构建（aapt2 + javac + d8），不经过 Gradle
- 纯 Java，WebSocket 协议自己实现（RFC 6455）

## 功能

- 连 DSH（填地址 + token），会话列表、历史、发消息
- **逐字流式**：按服务端给的 `stream` 时间轴回放，节奏和桌面端一致
- 消息气泡（深色）、markdown（粗体/代码/标题/列表）
- **发图片**（相册选图 → base64 → image 内容块）
- **看图片**（历史消息里的附件，走 `session/attachment` 拉回来）
- 新建对话（`session/create`）
- 头像、对话列表背景、聊天背景都可自定义
- 切后台自动断、回前台自动重连；记住地址/token/上次会话
- 会话列表按「多久以前」显示相对时间，空会话自动隐藏

## 构建

需要 Android SDK（platform-tools / platforms;android-34 / build-tools;34.0.0）和 JDK 17：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build-mini.ps1
```

产物：`out\dsh-mini.apk`

构建脚本里的 SDK/JDK 路径按自己机器改。

## 协议

DSH 没有公开 API，这个项目的协议全部是**黑盒逆向**出来的，细节写在：

- [`docs/DSH-协议逆向.md`](docs/DSH-协议逆向.md)

里面包括：鉴权、HTTP RPC、WebSocket 帧格式、`session/follow`、逐字流式的真相（回放时间轴）、
`session/create`、`session/attachment`、图片内容块、以及一堆踩过的坑。

## 结构

```
app/src/main/java/com/whale/mini/
  MainActivity.java    界面 + 消息渲染 + 流式回放
  SettingsActivity.java 设置（服务器/头像/背景/关于）
  DshApi.java          HTTP RPC 层
  WsClient.java        自实现 WebSocket（文本帧 + ping/pong）
  Md.java              极简 markdown → Spannable
  WhaleTail.java       思考中的摇尾巴动画
test/                  纯 Java 探针（可以不开模拟器直接调协议）
```

## 截图

（暂无，欢迎补）

## License

自用项目，随意。协议逆向部分仅用于个人学习和自建服务。
