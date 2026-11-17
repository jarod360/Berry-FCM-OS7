# Berry-FCM-OS7 开发交接文档

最后更新：2026-11-17
仓库：<https://github.com/jarod360/Berry-FCM-OS7>

## 1. 目标与交付物

同一仓库维护两个独立交付物：

1. **KernelSU 版**：从既有 v0.5.2 完整源码继续维护，提供 GMS UID Socket 观察、FCM 连接生命周期时间线、受限的 443 识别、GCM_RECONNECT 请求及原有应用守护 / 策略诊断。
2. **Android APK：BerryFCM Guardian**：普通 Android 应用，默认无线 ADB，另外提供 Root、静态 ADB TCP、USB/外部初始化说明和手动模式；管理用户明确选择的无障碍服务，提供开机恢复与 Quick Settings Tile。

重要边界：普通 APK 没有 Root / ADB Shell 权限时，不能静默启用第三方无障碍服务，也不能读取完整 GMS UID Socket。不能把“普通 APK”包装成天然具备特权的应用。Root / ADB 授权都由用户主动选择。

最小实现原则：不修改代理、VPN、DNS、hosts、路由；不杀死或重启 GMS；unknown 不等于 disconnected；广播完成不等于连接恢复。

## 2. 已恢复的 v0.5.2 基线

基线来自之前保存的 `BerryFCM-KSU-v0.5.2-test.zip`（24,936 bytes），已解压并核对以下文件：

- `module/module.prop`
- `module/customize.sh`
- `module/hotinstall.sh`
- `module/service.sh`
- `module/action.sh`
- `module/bin/ctl.sh`
- `module/webroot/index.html`
- `module/README.md`
- `module/LICENSE`

模块 ID：`berry_fcm_ksu`。
数据目录：`/data/adb/berry_fcm_ksu/`。
原有功能包括应用守护名单、多选与已选置顶、分批加载应用、浅色 / 深色主题、Google 原生 FCM Diagnostics 跳转、Doze / 后台网络策略诊断、stopped 检查修复及 30 秒巡检。

## 3. KernelSU v0.5.3-test 当前代码

### 3.1 新增文件与入口

- `module/bin/fcm-monitor.sh`：FCM Socket 观察、状态机、事件历史和重连请求。
- `module/service.sh`：启动后约每 30 秒执行 monitor，并保留原 stopped 巡检。
- `module/webroot/index.html`：增加连接状态 / 时间线 / 事件记录面板。
- `module/module.prop`：版本 `0.5.3-test`，versionCode `8`。

### 3.2 Socket 识别规则

- 通过 `cmd package list packages -U com.google.android.gms`，回退 `pm` / `dumpsys`，读取 GMS UID。
- `ss -tnpe` 仅接受 GMS UID 所属 TCP Socket；使用 `uid % 100000` 兼容 Android 次用户 UID 表示。
- 已建立 TCP 5228 / 5229 / 5230 作为 MCS Socket 观察证据。
- TCP 443 仅在远端 IP 匹配 mtalk DNS 地址，或本次启动周期内在 5228–5230 上实际学习到的远端地址时，才作为 FCM 候选。
- 任意 GMS HTTPS 443 不计作 FCM。UID / ss 不可读，或存在未验证 443 且无法建立 mtalk 地址证据时，状态可为 `unknown`；unknown 不累计断联、不触发自动重连。
- 该方法只能观察 Socket，不能直接证明 MCS 完成认证或推送可达。必须与 Google 原生 FCM Diagnostics 和真实推送交叉验证。

### 3.3 状态与时间线

状态文件：`/data/adb/berry_fcm_ksu/fcm-monitor.state`。
事件文件：`/data/adb/berry_fcm_ksu/fcm-events.log`，保留最近 120 条。

已记录：

- `connected / connecting / disconnected / unknown / never_seen` 状态。
- 当前 Socket key、传输类型、远端 IP。
- 本次连接首次观察时间、连接持续时长、最近在线采样。
- 首次观察到连接缺失的时间、连续缺失时长。
- 自动 / 手动重连请求时间、原因、次数和广播结果。
- 之后重新观察到可信 Socket 的恢复时间、累计 outage 次数、Socket 更换次数。
- 最近采样时间和采样新鲜度。

持续时长使用 `/proc/uptime` monotonic 时间，显示时间使用 epoch wall-clock。重启后清除上一启动周期的活动计时，不将旧连接时长延续到新启动周期。

### 3.4 自动重连

- 服务目标采样间隔约 30 秒。
- 可确认的缺失 / 连接尝试状态连续约 120 秒后，检查 GMS `stopped=false` 再定向发送 `com.google.android.intent.action.GCM_RECONNECT`。
- 自动 / 手动请求最小间隔 300 秒。
- `Broadcast completed` 只记为 `requested`；后续实际观察到可信 Socket 才记 `restored`。
- 深度 Doze / CPU suspend 会延迟脚本执行，因此 120 秒是实际监控进程运行时间门槛，不承诺锁屏墙钟时间严格 2 分钟触发或 5 分钟恢复。

### 3.5 已完成的本地模拟检查

`tests/fcm-monitor-smoke.sh` 覆盖：

1. GMS UID 5228 Socket 识别。
2. 已知 mtalk 地址的 443 识别。
3. 无关 GMS HTTPS 443 拒绝。
4. 120 秒门槛发送一次重连请求。
5. 广播请求不被当作连接成功。
6. 后续观察到可信 Socket 后记录恢复。
7. mtalk DNS 不可用且只有未验证 443 时进入 unknown，不累计缺失、不重连。

已通过本地 `sh -n` 和 smoke test。WebUI 内联 JavaScript 在 CI 中由 Node `--check` 验证。**尚未完成目标手机实机验证。**

## 4. Android APK 0.1.0-alpha 当前代码

应用：`BerryFCM Guardian`。
包名：`top.zesun.berryfcm`。
默认授权模式：无线 ADB；Root 可手动选择。

现有代码文件：

- `MainActivity.java`：授权模式、服务扫描 / 选择、连接测试、磁贴请求。
- `AdbConnectionManager.java`：使用 `MuntashirAkon/libadb-android`，AndroidKeyStore 保存 ADB 身份，提供配对 / mDNS 连接 / 静态 host:port 连接。
- `RootShell.java`、`AdbShell.java`、`PrivilegeShell.java`：不同权限通道。
- `AccessibilityOps.java`：扫描声明 AccessibilityService 的应用；只管理用户选择的组件，更新后读取验证并保留其它组件。
- `BootReceiver.java`、`RestoreService.java`：可选开机恢复。
- `RestoreTileService.java`：快捷设置磁贴。

授权方式的真实边界：

- Root：由 KernelSU / Magisk 显式授予 `su`。
- 无线 ADB：首次输入 IP、配对端口、配对码；之后尝试 mDNS 重连。无线调试必须仍开启，ROM 可能限制后台启动。
- 静态 ADB TCP：连接用户已开启的 host:port；APK 不会自行开启 adbd。不同 adbd / TLS 协议兼容性待实机验证。
- USB / 外部：仅说明由电脑 USB ADB 初始化的路径；APK 不是电脑端 USB ADB Host。
- 手动：跳转系统无障碍设置。

开机恢复默认关闭。只有用户明确选中的服务才会写入；Root / ADB Shell 不可用时不能静默启用第三方无障碍服务。

**尚未验证：** APK 尚未通过 GitHub Actions 云编译；ADB 库版本、Android 13+ 磁贴 API、Foreground Service 声明、OriginOS 开机恢复和无障碍服务实际启用流程均需根据构建 / 实机结果修复。当前 APK 尚未集成 KSU 版完整 FCM Socket 时间线；普通 APK 权限本身无法替代 Root / ADB Shell 读取其它 UID Socket。后续如要在 Root / ADB 模式复用 FCM monitor，应把 Shell 监控脚本作为明确授权后的执行模块接入，不能在普通模式宣称支持。

## 5. GitHub Actions

- `.github/workflows/build-ksu.yml`：main 对 `module/**` / `tests/**` 的提交或手动运行时执行 Shell 语法、状态机 smoke test、WebUI JavaScript 检查，并打包 `BerryFCM-KSU-v0.5.3-test.zip`。
- `.github/workflows/build-apk.yml`：main 对 `app/**` / Gradle 文件的提交或手动运行时，使用 JDK 17、Android SDK 35、Gradle 8.11.1 构建 `BerryFCM-Guardian-v0.1.0-alpha-debug.apk`。
- 两个工作流分别上传 Artifact，保留 30 天。
- APK 当前为 debug build，不是生产发布签名包。实机验收前不发布正式 Release。

## 6. 下一步开发顺序

1. 初始化仓库并推送当前完整源码；确认 `main` 出现初始提交。
2. 检查两个 Actions Run；先修复真实云编译错误，不凭猜测修改。
3. 下载 KSU ZIP、APK，检查包内文件和签名 / 安装情况。
4. KSU 实机：正常 Wi-Fi、受限 Wi-Fi、移动网络、仅 443、DNS 不可用、网络切换、锁屏 Doze；与原生 Diagnostics 对照连接 / 断联时间线。
5. 验证 120 秒触发门槛、300 秒冷却、广播结果、真实恢复事件和 unknown 安全行为。
6. APK 实机：Root、无线 ADB 首次配对、重启后 mDNS 重连、静态端口、USB 初始化说明、服务多选 / 取消、保留其它服务、开机恢复、磁贴、通知权限和 OriginOS 后台限制。
7. 根据 APK 的实际目标重新评估是否在授权模式下接入 FCM monitor；普通权限模式不声称有 GMS Socket 可见性。
8. 实机验收后再建立正式版本号和 Release。

## 7. 参考项目 / 来源

- Google FCM 网络配置：<https://firebase.google.com/docs/cloud-messaging/network-configuration>
- FCM 443 地址匹配思路：<https://github.com/ByteJuno/fcmfix-coloros>
- Android ADB 客户端库：<https://github.com/MuntashirAkon/libadb-android>

本仓库只参考上述项目公开的网络识别 / ADB 客户端设计，没有复制 FCMFix 的 LSPosed Hook。注意检查 ADB 依赖许可证与 NOTICE。
