# BerryFCM Guardian APK — 当前技术基线

**最新产品规划与权限分工**：[../docs/PRODUCT_PLAN.md](../docs/PRODUCT_PLAN.md)  
**实时交接与已验证构建**：[../docs/HANDOFF.md](../docs/HANDOFF.md)

## 目前实际已有的代码

- Android 应用包名 `top.zesun.berryfcm`，`minSdk=26`，`targetSdk=35`，Gradle JDK 17；`app/build.gradle` 使用稳定签名配置。
- `AdbConnectionManager.java` 利用 [libadb-android](https://github.com/MuntashirAkon/libadb-android) 和 AndroidKeyStore 保存配对身份，支持用户显式配对、mDNS 连接与静态 TCP 连接；`RootShell.java` 是单独的 Root 通道。
- `FcmMonitorService.java` 使用 Android 前台服务调度后台 Shell；`assets/fcm-monitor.sh` 复用 KSU FCM Socket 状态机并记录连接时间线；`BootReceiver.java` 尝试在系统允许时恢复服务。
- `MonitorActivity.java` 现已具有初步连接时间线、前台服务开关、后台优化与系统设置跳转，但布局及流程尚未按最终方案重构。
- `MainActivity.java` 中仍有早期的 **第三方无障碍服务管理**，`AccessibilityOps.java`、`RestoreService.java`、`RestoreTileService.java` 为旧功能基础。它们不等于 KSU 的守护应用 stopped 名单。将根据统一产品计划逐步迁移，**在范围最终裁决前不擅自删除用户保留的配置数据**。
- Android APK 当前 **尚没有** Shizuku 风格的通知栏输入 ADB 配对码、完整 OriginOS 独立设置页、最近任务隐藏切换、正式 GitHub Releases 应用内升级。不要把“已有设计”当成“已经实现”。

## 授权与可靠性边界

- ADB Shell 授权需要首次配对（或电脑外部 ADB、Root）；`WRITE_SECURE_SETTINGS` 只是一种设置管理权限，**不等于永久 ADB Shell，更不等于直接具有读取 GMS Socket 的系统权限**。
- 随用户关闭无线调试、进入网络受限状态或厂商杀进程，后台 Shell 可能不可用。监控必须将此记录为未知/采样空白，而不是误报断联。
- Android FGS 不保证不可终止；系统可能要求通知，不能以“隐藏最近任务卡片”代替后台运行权限。
- ADB 配对码、会话密钥和其它授权材料不进入日志或仓库。

## 构建与发布

GitHub Actions [Build Android APK](https://github.com/jarod360/Berry-FCM-OS7/actions/workflows/build-apk.yml)：先 Debug 编译，再以固定 P12 签名 Release、验证证书和上传 APK Artifact。**最新已确认通过的构建**：[Run #44132037143](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44132037143)，产物 `BerryFCM-Guardian-v0.1.0-alpha.26`。用户不需要重新设置 Secrets。

正式 GitHub Release、应用内在线检查升级和 OriginOS 目标手机实机验收列在 [产品计划](../docs/PRODUCT_PLAN.md)，**尚未完成**。
