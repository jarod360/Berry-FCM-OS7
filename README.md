# Berry-FCM-OS7

**BerryFCM Guardian**：面向 Android / OriginOS 的 FCM 连接观察、重连与无障碍服务辅助工具。一个仓库维护两个独立交付物：KernelSU 模块 ZIP 和 Android APK。

> 当前为测试 / alpha 阶段，尚未完成目标手机实机验收。GitHub Actions 会分别构建两个产物；不要把构建成功等同于 FCM 已能在所有网络下稳定送达。

## 交付版本

| 版本 | 产物 | 权限模型 | 主要用途 |
|---|---|---|---|
| KernelSU | `BerryFCM-KSU-v0.5.3-test.zip` | Root | 观察 GMS UID Socket、FCM 连接时间线、自动请求 GCM 重连，并保留原有应用守护与策略诊断 |
| Android APK | `BerryFCM-Guardian-v0.1.0-alpha-debug.apk` | Root / 无线 ADB / 静态 ADB TCP / 手动 | 管理用户明确选择的无障碍服务、开机恢复和下拉快捷磁贴 |

## KernelSU 版

基于此前 v0.5.2 源码继续完善，模块 ID 保持 `berry_fcm_ksu`，升级保留 `/data/adb/berry_fcm_ksu/` 下的现有配置。

- 仅检查 GMS UID 所属 TCP Socket；5228–5230 的已建立连接作为 MCS 连接证据。
- TCP 443 不是单凭端口判定。只有远端 IP 匹配 `mtalk.google.com` 等 Google mtalk 主机名解析地址，或本次启动周期内在 5228–5230 上实际观察到的地址，才作为 443 候选。
- 任意 GMS HTTPS 443（例如普通 CDN / API 连接）不计为 FCM 在线。UID、Socket 或地址证据不足时显示 `unknown`，不把未知当断联。
- 记录首次观察连接时间、连接持续时长、首次观察缺失时间、缺失持续时长、最近恢复时间、重连请求时间 / 原因 / 次数 / 结果，以及最近 120 条事件。
- 服务约每 30 秒采样；连续约 120 秒处于可确认的缺失 / 连接尝试状态后发送定向 `GCM_RECONNECT` 广播；两次请求至少间隔 300 秒。
- 广播返回 `Broadcast completed` 只表示请求已发送；只有后续再次观察到可信 Socket，才记为恢复。
- 深度 Doze / CPU suspend 可能推迟采样，因此 120 秒和 300 秒是监控进程实际运行时间规则，不是锁屏后的严格墙钟保证。

**重要：** TCP `ESTABLISHED` 只能证明采样时观察到 Socket，不能证明 MCS 已完成认证、心跳正常或推送必达。应与 Google 原生 FCM Diagnostics 和真实推送交叉验证。

详细说明：[`module/README.md`](module/README.md)。

## Android APK 版

应用名称：**BerryFCM Guardian**，包名 `top.zesun.berryfcm`。默认进入无线 ADB 模式；Root 是可选模式。

授权入口：

1. **Root**：用户在 KernelSU / Magisk 中明确授予 `su` 后执行 Shell。
2. **无线 ADB**：用户先在开发者选项中开启无线调试，在 APK 输入设备 IP、配对端口和配对码；配对身份保存于 AndroidKeyStore，之后通过 mDNS 尝试重新连接。重启后仍要求系统无线调试开启，OriginOS 也可能限制后台启动。
3. **静态 ADB TCP**：连接用户已开启的 ADB TCP 端点，需填写主机和端口；APK 不会自行打开 adbd 监听端口。端点协议兼容性需在目标 ROM 上实测。
4. **USB / 外部**：APK 不是电脑端 USB ADB Host，不能在手机上自行打开 USB 调试。此入口提供由电脑 USB ADB 初始化后再连接静态端口的说明。
5. **手动**：跳转系统无障碍设置，由用户在系统 UI 中操作。

APK 扫描应用声明的无障碍服务。只有用户明确勾选的服务才会被管理；应用更新 `enabled_accessibility_services` 时保留未由本应用管理的其它服务。开机恢复默认关闭，用户主动启用后才执行；Android / OriginOS 可能限制后台启动。

Android 13+ 可请求系统添加 Quick Settings Tile。系统会显示确认，用户可拒绝。

**权限边界：** 普通 APK 没有权限静默启用其它应用的无障碍服务，也不能无 Root 读取 GMS 的完整 Socket。无线 ADB / 静态 ADB 会话具备 Shell 级权限，必须只连接自己的设备。ADB 会话断开后，Shell 权限不会永久变成 APK 的普通权限；`WRITE_SECURE_SETTINGS` 也不会因一次 ADB 操作而永久授予普通 APK。

APK 目前聚焦授权与无障碍服务管理，不宣称在普通权限模式下提供 KSU 版的完整 FCM Socket 监控。

详细说明：[`app/README.md`](app/README.md)。

## GitHub Actions 云编译

推送 `main` 后，两个工作流按各自文件路径独立触发，也可以在 Actions 页面手动运行：

- **Build KernelSU ZIP**：Shell 语法、FCM 状态机模拟测试、WebUI JavaScript 检查，输出 `BerryFCM-KSU-v0.5.3-test.zip`。
- **Build Android APK**：使用 JDK 17、Android SDK 35、Gradle 8.11.1 构建 debug APK，输出 `BerryFCM-Guardian-v0.1.0-alpha-debug.apk`。

产物在对应 Actions Run 的 Artifacts 中下载，保留 30 天。APK 是 debug 构建，不是面向商店发布的 release 签名包。实机验收前不创建正式 Release。

## 仓库结构

```text
module/                       KernelSU 模块完整源码
app/                          Android APK 源码
 tests/fcm-monitor-smoke.sh    FCM 监控 Shell 模拟测试
 docs/HANDOFF.md              开发交接、状态和验收记录
 .github/workflows/           两个独立云编译工作流
```

## 本地检查

```sh
sh -n module/bin/ctl.sh module/bin/fcm-monitor.sh module/service.sh
sh tests/fcm-monitor-smoke.sh
```

APK 云编译：

```sh
gradle --no-daemon :app:assembleDebug
```

## 参考项目

- [Google FCM 网络配置](https://firebase.google.com/docs/cloud-messaging/network-configuration)：FCM 设备端端口与 mtalk 主机名。
- [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)：参考 443 必须结合 mtalk 地址证据的识别思路；本项目没有移植其 LSPosed Hook。
- [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android)：Android ADB 客户端实现参考，使用者应查看其许可证和依赖 NOTICE。

## 安全原则

- 不自动修改 DNS、hosts、VPN、代理或路由。
- 不强制停止、杀死或重启 GMS。
- 不静默开启开发者选项 / 无线调试，不恢复用户未选择的无障碍服务。
- 自动重连只发送 GMS 定向广播，不把广播完成当作连接成功。
