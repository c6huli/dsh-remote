# 手机操控（Shizuku）

DSH Remote 1.5.106 起内置「手机操控」：在 App 里看手机自己的屏幕，并点按、滑动、输入文字、启动应用。

它不需要 root，也不需要数据线，更不需要你打开「USB 调试」。权限来自 **Shizuku**——一个用 Android 自带的「无线调试」把 shell 权限借给普通应用的开源工具。

---

## 一、为什么是 Shizuku

普通 App 的沙箱里做不到这些事：截图要 MediaProjection（每次都要弹「开始录制」），注入触摸要 INJECT_EVENTS（只有 shell / system 有），读应用列表、启动别的应用也都受限。

Shizuku 的办法是：你在系统设置里手动开一次「无线调试」，Shizuku 用这个口子把自己提升成 shell 身份常驻后台，然后把它持有的 shell Binder 通过一个 ContentProvider 交给我们。之后我们的代码以 **shell uid（2000）** 运行，`input`、`screencap`、`pm`、`monkey` 这些命令就都能用了。

关键点：**这些命令在 shell 进程里执行，不在 App 进程里执行**。App 只能发有限的几种请求（见第四节），没有任意 shell、没有任意路径。

---

## 二、准备（一次性，约 3 分钟）

1. **安装 Shizuku**
   - Google Play 搜 `Shizuku`，或
   - GitHub：[RikkaApps/Shizuku Releases](https://github.com/RikkaApps/Shizuku/releases) 下载 APK 安装。
2. **启动 Shizuku 服务**
   - 打开 Shizuku，按它首页的指引做。Android 11 及以上：进「开发者选项 → 无线调试」，先「使用配对码配对设备」，再回 Shizuku 点「通过无线调试启动」，通知栏常驻即成功。
   - Android 10 及以下只能靠 adb 启动一次（这是系统限制，不是本 App 的）。
   - 手机重启后要重新启动 Shizuku（Android 11+ 也可以让 Shizuku 自己用无线调试恢复）。
3. **在 DSH Remote 里打开开关**
   - 打开 App → 左上角菜单 → 设置 → 「手机操控」→ 打开「开启手机操控」。
   - 点「进入手机操控」→ 点「请求授权」→ Shizuku 会弹出授权框，选 **允许**（可勾选「始终允许」）。
   - 回到页面点「连接特权通道」，状态变成「已连接」即可。

> 开关默认是**关闭**的。没打开时 App 不会加载 Shizuku、不会截屏、不会注入任何输入事件。

---

## 三、怎么用

| 操作 | 说明 |
|---|---|
| 镜像 | 打开「镜像」开关，屏幕上会显示手机当前的画面（约每 0.9 秒刷新一帧） |
| 点按 | 直接点画面里的位置，映射回手机真实像素坐标 |
| 滑动 | 在画面上拖动，松手后按起点→终点做一次 swipe |
| 返回 / 主页 / 任务 | 发送 KEYCODE_BACK / HOME / APP_SWITCH |
| 通知 | 下拉通知栏 |
| 音量± / 电源 | 发送对应按键 |
| 应用 | 列出已安装的第三方应用，点「打开」直接启动 |
| 输入文字 | 先在手机上点中输入框，再在这里输入并「发送」 |

镜像只在「手机操控」页显示、且开关打开时才跑；离开页面或关掉开关，截屏循环立即停止。

---

## 四、权限边界（重要）

Shizuku 侧的 `PhoneControlEngine` 只接受下面这几种 op，别的一律拒绝：

```
probe        握手：返回 shell uid 和屏幕尺寸
screenshot   screencap -p 到 /data/local/tmp/dsh_phone_screen.png
read         按偏移分片读回这张 PNG（Binder 单次约 1MB）
tap           input tap X Y
swipe         input swipe X Y X2 Y2 MS
text          input text <转义后的文字>
key           input keyevent <固定白名单里的键>
launch        monkey -p <包名> -c LAUNCHER 1
apps          pm list packages -3
foreground    dumpsys activity activities（只取前台包名）
release       释放
```

没有 `exec`、没有 `shell`、没有「读任意文件」、没有「安装 APK」。包名要过正则，按键名要过白名单，全部参数在 shell 侧再校验一次。

另外：
- 只有本 App 的 uid 能调用这个 Binder（`Binder.getCallingUid()` 校验），其它应用即使拿到 binder 也调不动。
- 关掉开关会 `unbindUserService`，shell 侧的进程随之退出，不会留后台。
- 截屏文件落在 `/data/local/tmp/`，只有 shell 能读；App 通过分片接口取回，不做持久保存。

---

## 五、常见问题

**「未安装 Shizuku」** — 装一下 Shizuku，然后回本页点「刷新状态」。

**「Shizuku 未运行」** — 打开 Shizuku App 把服务启动起来（Android 11+ 用无线调试）。手机重启后常见。

**「Shizuku 授权未通过」** — 点「请求授权」。如果 Shizuku 里之前拒绝过，去 Shizuku → 应用管理 → DSH Remote → 允许。

**「特权通道未连接」** — 授权通过了但 Binder 没绑上：点「连接特权通道」重试；仍不行就杀掉 Shizuku 服务重启一次。

**镜像黑屏 / 截屏失败** — 锁屏状态下 `screencap` 拿不到画面，先解锁。部分国产系统对 shell 截屏有限制，属系统策略。

**点按没反应** — 确认状态是「已连接」；系统级的「无障碍/防误触」可能拦注入事件；银行类 App 的 secure window 本身不接受注入。

**为什么不做成 adb 直连？** — 那需要你在电脑上执行 `adb tcpip` 或每次无线调试配对，还得保存密钥、处理 mDNS 发现，本质上仍然是借 shell。Shizuku 已经把这一步做完了，直接用它更省事；本 App 也完全不碰 adb。
