# BerryFCM Guardian · Android APK

统一目标与验收：[PRODUCT_PLAN.md](../docs/PRODUCT_PLAN.md) · 当前交接：[HANDOFF.md](../docs/HANDOFF.md) · [签名约定](../docs/SIGNING.md)

普通 Android APK，桌面名称 **BerryFCM**，包名 **top.zesun.berryfcm**。以 Root 或用户主动配对的 ADB Shell 读取 GMS FCM Socket，维护应用 stopped 状态，并使用原生 Android 前台服务与用户设置提高后台运行可靠性。不是第三方无障碍服务管理器。

## 导航模块

| 页面 | 核心操作 |
| --- | --- |
| 概览 \`MainActivity\` | 观测状态、采样年龄、已观测断联、授权按钮与各模块导航 |
| 授权 \`AuthActivity\` | 无线 ADB mDNS 发现、Shizuku 风格通知栏 RemoteInput 六位码、Root、静态 TCP、Shell UID 验证 |
| FCM \`MonitorActivity\` | 用户开启后台采样、Socket 证据/未知/过期、事件时间线、请求重连 |
| 守护 \`GuardActivity\` | 应用扫描、搜索、多选/已选置顶、系统应用过滤、stopped 检测与修复、自动守护 |
| 保活 \`BackgroundActivity\` | FGS/开机启动、DeviceIdle 与 AppOps、GMS Doze 与后台网络白名单、OriginOS 设置跳转、最近任务隐藏 |
| 诊断 \`DiagnosticsActivity\` | Google 原生 FCM Diagnostics、mtalk 端口可达性、GMS 策略、监控历史分享 |
| 设置 \`SettingsActivity\` | 浅色/深色/跟随系统、添加 FCM Quick Settings Tile、项目主页 |
| 更新 \`UpdateActivity\` | GitHub Release 版本比较、APK 下载、包名/签名/版本验证、调用系统安装器 |

## 无线调试通知栏配对

1. 在首页点 **进入授权中心**，保持「无线 ADB」模式。
2. 点 **开始通知栏配对（自动发现端口）**。系统如询问通知或附近设备权限，请允许。
3. 打开手机开发者选项 → 无线调试 → 使用配对码配对设备。
4. 停留在该系统页面，**下拉通知栏**，在 BerryFCM 通知中点输入六位配对码。
5. 检查通知中的配对结果，回 APK「验证当前 Root / ADB Shell 能力」。mDNS 发现失败时，直接在同一通知栏输入 `端口:六位码`（例如 `37123:123456`），**无需离开系统配对页面**；授权页手动配对端口仍是备用入口。

配对身份保存在 AndroidKeyStore，但配对码不保存。手机关闭无线调试、ROM 杀死 Shell 或重启后，**持久身份不代表当前 ADB Shell 有效**。APP 不能静默打开系统无线调试。

## 后台与权限边界

- \`FcmMonitorService\`：独立于页面的前台服务，计划每 15 秒采样；\`BootReceiver\` 在系统许可且用户设置开启时恢复服务；\`FcmTileService\` 用 Android TileService 实现下拉检测/入口。**三者均不需要 AccessibilityService**。
- Socket 证据仅说明 TCP 层观察；ADB Shell 未暴露 GMS UID、未知 DNS、脚本失败或采样过期时不报告已连接。几秒断联可能漏检。
- \`GuardOps\` 仅针对用户选中的应用与 GMS 检测 stopped，并受控调用系统解停命令；不同 ROM 上 ADB Shell 可能无该权限，必须以实际回读为准。
- Android 标准 Doze/AppOps 可使用授权 Shell 修改；GMS 白名单及计量网络的撤销只针对 BerryFCM 记录为自己添加的项目。
- OriginOS 的自启/关联启动/允许后台高耗电/最近任务锁定提供系统页入口，缺少可靠私有接口时不可伪装为自动开关。隐藏最近任务卡片不是隐藏进程。
- Android 11+ 通过 \`QUERY_ALL_PACKAGES\` 支持用户浏览/选择已安装应用。若以后上架 Google Play，需要再审查商店的包可见性政策；目前交付渠道为 GitHub Release。
- 用户主动选择监测时，前台服务通知可降低干扰但不能保证永久隐藏或永久不被杀。

## 旧功能移除

原 \`AccessibilityOps.java\`、\`RestoreService.java\`、\`RestoreTileService.java\` 与对应的第三方无障碍服务扫描/恢复、\`WRITE_SECURE_SETTINGS\` 用途已经删除。**不要把“应用停止状态守护”和“他人无障碍服务管理”混淆。** 原配置没有自动映射为新的应用守护名单，避免误启用不相干权限。

## 构建、签名与更新

- GitHub Actions [Build Android APK](https://github.com/jarod360/Berry-FCM-OS7/actions/workflows/build-apk.yml) 执行 Shell 状态机模拟测试、APK 模块结构检查、Debug 编译、固定证书 Release 签名、apksigner 验签和 Artifact 上传。
- 已经验证过的模块化 APK 构建：[Run #44139303543](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44139303543)（alpha.30）；**更新接口和通知栏无 mDNS 兜底也已通过 [Actions #44141729186](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44141729186)（alpha.33）；主题修正等后续改动仍需新一轮 CI。**
- \`workflow_dispatch.publish_release\` 默认关闭；普通 push 只上传测试 Artifact，等设备功能验收后由用户显式发起受控 GitHub prerelease。更新页面只检查正式 Release。
- 正式签名证书 SHA-256：\`BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615\`。不重新生成密钥、不在仓库记录 P12/密码，不再让用户填写已成功使用的四项 Secrets。
- 同包名、同签名、\`versionCode\` 递增才允许覆盖安装。APK 首次安装、ADB 配对、后台过夜及 GitHub 覆盖升级仍需要目标 OriginOS 实机测试。

**准入原则**：先完成页面模块与对应实际代码、通过 GitHub Actions，再交用户安装；不要要求测试已知不可用的旧授权流程。

> 深色模式新增原生 Android 深色控件主题，首页授权状态返回时自动更新；最终主题代码编译与目标手机 UI 截图仍待验证。

## 启动器图标

新版以 `res/mipmap-anydpi-v26/ic_launcher.xml` 自适应图标为主：纯蓝背景，白色信号弧线+圆点，不使用渐变或复杂装饰；支持圆形遮罩和 Android 13+ 单色主题图标。新图标需重新云编译，手机桌面缓存可能在安装更新后短时显示旧图标。

## 旧测试版在线升级路径

当前 alpha.36 的旧更新器只搜索 `BerryFCM-Guardian-v...` 格式资产。为了保证初次过渡，新签名 Release 工作流保留**同包双文件名**的 Release 资产：旧更新器读取旧名，新更新器优先使用新 `BerryFCM-v...`。同一个 APK、同一个证书、递增 versionCode、同一个包名，无需卸载重装。前提：必须有实际发布的 GitHub Release；普通 Actions Artifact 不会显示为可更新版本。安装需用户确认系统安装器，无法静默升级。

### Conscrypt / AndroidKeyStore 配对兼容性

新版使用独立的 `berry_fcm_adb_identity_v3` RSA 身份，按 [Google Conscrypt #718](https://github.com/google/conscrypt/issues/718) 同类错误修复补充 `DIGEST_NONE`、`ENCRYPTION_PADDING_NONE` 与 RSA-PSS 签名填充。更新后需要在系统“无线调试”重新配对一次；**不要求重新生成 APK 安装签名，也不删除旧证书**。这只是有源码依据的候选修复，仍必须在 OriginOS 真机确认 TLS 握手及 Shell UID。
