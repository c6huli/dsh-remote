# DSH Remote

把 [DeepSeek Harness](https://github.com/deepseek-ai)（dsh 0.2.x）搬到手机上用的完整方案：一个安卓 App + 一台 Windows 主机上的配套工具。

> 本仓库是**个人自用工具**，不是官方项目。dsh 本身请从其官方渠道获取。

---

## 赞助

如果这个项目对你有用，欢迎扫码支持（微信支付）。赞助纯属自愿，不影响任何功能。

<p align="center">
  <img src="assets/sponsor-wechat.png" width="360" alt="微信赞助码">
</p>

---

## 它解决什么问题

dsh 的 Web 界面很好用，但在手机上直接开浏览器有三个实际麻烦：

1. dsh 的 `/api` 有双重门禁：Host 必须是回环地址，还要带签名 cookie，手机直接访问会 403/401；
2. 手机走蜂窝/异地时，长连接容易半开，任务跑完了通知不到；
3. 手机浏览器切后台就被回收，会话和通知都断。

这个仓库把这三件事分别解决掉：

| 问题 | 做法 |
|---|---|
| 门禁 | 主机上用 Caddy 反代：改写 `Host`、注入签名 cookie |
| 协议 | dsh 0.1.x 到 0.2.x 的方法名变了，用一个轻量 Node 桥做翻译 |
| 长连接 | 安卓端常驻前台服务 + 事件流看门狗 + 完成通知 |

---

## 架构

```
手机 App (Kotlin/Compose)
   │  OkHttp：点号方法名 session.list / workspace.list …（User-Agent: okhttp/*）
   ▼
Caddy :8080 / :8443          按 User-Agent 分流
   ├─ okhttp/*  ──► Node 协议桥 :3084 ──► 翻译成 0.2.x 的 ns/method
   └─ 浏览器     ──► dsh web :3080      （Host 改为 127.0.0.1:3080 + 注入 cookie）
                        ▲
                        └── App 内嵌 WebView 也走这条，用于插件设置页 / better-sidebar 面板
```

设计上刻意让 **App 只讲一种协议**（点号旧方法名），把版本差异全部收在主机侧的协议桥里；dsh 升级时通常只需要改桥，不用重新发版 App。

---

## 目录

```
android/    安卓 App（Kotlin + Jetpack Compose）
host/       主机侧工具
  bridge.js            0.1.x → 0.2.x 协议桥（Node，无第三方依赖）
  watch-bridge.ps1     桥的守护循环（崩溃 3 秒内自动拉起）
  start-dsh-stack.ps1  一键启动 dsh web / 桥 / 隧道
  build-bundle.ps1     打包 Web 界面资源
  caddy/Caddyfile      反代配置模板（需填入自己的 cookie 与域名）
  mini/settings.html   轻量设置页（可选）
launcher/   Windows 启动器（VBS 静默启动 / BAT 双击启动）
assets/     赞助码等图片
docs/       版本记录
```

---

## 快速开始

### 1. 主机侧

前置：

- Windows + [Node.js](https://nodejs.org/)（跑 dsh 与协议桥）
- **dsh 0.2.x** 已安装并能 `dsh web` 起来（默认 `127.0.0.1:3080`）
- [Caddy 2](https://caddyserver.com/)（反代）
- 可选：[Tailscale](https://tailscale.com/)，用于手机远程访问（强烈推荐，见下）

步骤：

```powershell
# 1) 启动 dsh web（端口 3080）
dsh web

# 2) 起协议桥（3084），并交给守护脚本
powershell -ExecutionPolicy Bypass -File host\watch-bridge.ps1

# 3) 取会话 cookie，填进 host\caddy\Caddyfile
#    cookie 由 dsh 的持久密钥签名，最长 30 天；签发方式见 dsh 文档或自行用
#    ~/.dsh/.credentials.yaml 里的 client-connection/browser-session.secret 计算 HMAC
#    然后把 Caddyfile 里 dsh-auth-REPLACE_WITH_YOUR_SESSION_COOKIE 整段替换掉

# 4) 改 Caddyfile 里的站点域名/地址，然后跑 Caddy
caddy run --config host\caddy\Caddyfile
```

或者直接用 `launcher\启动 DSH Remote.bat` 一把起全套（需先把 Caddyfile 配好）。

### 2. 安卓侧

- 用 Android Studio 打开 `android/`，或命令行构建（见下）
- 首次启动填服务器地址，例如 `http://100.x.y.z:8080`（Tailscale IP）或 `https://你的域名`
- 设置里有连接状态、主题、通知、详细/简洁、下载位置等开关

### 3. 网络

推荐 Tailscale：手机与主机在同一 tailnet 内，走 WireGuard 直连，不需要公网 IP，也不需要开放端口。

> 如果 `tailscale status` 显示 `relay`，说明走了中继，吞吐会明显下降；尽量让它变成 `direct`。

---

## 构建 APK

```powershell
$env:JAVA_HOME  = "C:\path\to\jdk17"
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
cd android
.\gradlew assembleRelease
```

**签名**：本仓库**不包含**任何签名密钥。请在 `android/app/build.gradle.kts` 里改成你自己的 keystore：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("your-keystore.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = "your-alias"
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
```

用别人的密钥签名是危险的——那等于把「谁都能给你推送更新」的权力交出去。

---

## 已实现的功能（简）

- 会话列表 / 工作区 / 子智能体 / 文件浏览 / 轨迹
- 聊天流式输出、工具调用折叠块、Agent 预设、模型切换（按提供方折叠 + 搜索）
- 小鲸鱼运行时长（用宿主 `turn/start` 时间戳，跨设备准确）
- 常驻统计条：tok/s、上下文占用、缓存命中率
- 会话结束的轮次汇总与文件变动列表，可下载到手机
- 下载：进度通知、完成响铃、点通知直达、长按删除、可外部分享/打开、可用系统文件管理器指定目录
- 内嵌宿主设置面板与 `dsh-better-sidebar` 面板（仅渲染那一块），未安装时提示插件名与仓库地址
- 事件流看门狗：蜂窝下半开连接 30–45 秒内自动恢复并补回结果

版本演进见 [`docs/CHANGELOG-安卓端.txt`](docs/CHANGELOG-安卓端.txt)。

---

## 已知限制

- Caddyfile 里的 cookie 需要你自己签发；cookie 过期后需重签
- 内嵌的 Web 面板是「从宿主页面裁一块出来渲染」，宿主前端改版可能导致裁切失效
- 终端 / 侧边对话面板依赖实时 PTY，在裁切视图里不可靠，已移除入口
- 后台同步受系统省电策略影响：进程被冻结时只有通知能到，内容要等回到 App 才刷新
- 未做多连接分片下载（需要服务端支持 Range；桥当前不支持）

---

## 免责与许可

- 本项目为个人自用工具，与 DeepSeek 官方无关，不提供任何担保，使用风险自负
- 请遵守当地法律法规以及你所使用服务的条款
- 代码以 **MIT License** 发布，见 `LICENSE`

---

## 致谢

- [DeepSeek Harness](https://github.com/deepseek-ai) — 本体
- [dsh-better-sidebar](https://github.com/omdsh-dev/DSH-better-sidebar) — 侧边栏面板
- [Caddy](https://caddyserver.com/)、[Tailscale](https://tailscale.com/)、[OkHttp](https://square.github.io/okhttp/)
