# Berry-FCM-OS7 实时开发交接

**更新：2026-11-18** · 详细产品目标和权限裁决以 [PRODUCT_PLAN.md](PRODUCT_PLAN.md) 为准。此文件记录**已实现 / 已构建 / 实机未证实 / 下一步**，每次 GitHub 提交时更新。

## 1. 现在做什么

- **目标优先级：先 APK 后 KernelSU 双线同步。** 不放弃 KernelSU，也不把已有 KSU 代码与普通 APK 的权限混为一谈。
- **产品功能：** FCM 长连接观察/受控重连、事件时间线、后台可靠性与 OriginOS 权限优化、应用守护 stopped 修复、诊断与 Release 更新。
- **交互结构：** 首页只显示概览和各模块入口。独立授权（ADB 配对通过通知栏输入）、FCM 监控、应用守护、后台保活、诊断、设置/关于。不能再把按钮/长名单挤在一个页面。
- **权限原则：** Android API 能做则不用 ADB；ADB Shell/Root 能做则不引入 AccessibilityService；OriginOS 厂商私有锁定/高耗电开关不可凭空承诺自动修改。**已决定移除第三方无障碍服务扫描/启停/恢复**，并以 FCM 应用守护和 FCM 自动恢复替代旧入口；不能误删既有 KSU 应用守护。
- **协作范围：** 当前聊天 + GitHub 工具 + GitHub Actions。不得跳转 Work；本地 Windows 相关文件仅放 `D:\Codex`，未请求时不创建用户电脑的文件。

## 2. 仓库现状，已核查的事实

- 仓库：`jarod360/Berry-FCM-OS7`，主分支 `main`。
- **本次开始整理时 HEAD**：`3561ec8fb9dae9818680dc11f0780e1a3b5b00d5`（这是文档修订前的代码基线；未来提交见仓库当前历史）。
- **APK 构建**：[Actions #44122890970](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44122890970) 成功：Debug 编译、签名准备、Release、apksigner、Artifact 上传均成功；产物 `BerryFCM-Guardian-v0.1.0-alpha.24`（Artifact ID 14347120446）。**并未证明手机后台可靠、网络断联识别准确。**
- **签名：** GitHub Actions 当前固定证书 SHA256 `BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615`；签名四项 Secrets 已在成功构建中实际使用。**禁止再要求重生密钥/重复填写 Secrets。**
- **Releases：** 2026-11-18 查询仓库 Releases 数组为空；尚无正式 Release，无法声称已经支持在线自动更新。Actions Artifact 不是 Release。
- **KernelSU：** `module/module.prop` 为 `0.5.4-test`；`module/bin/ctl.sh` 有守护应用 stopped/解除停止、网络/Doze 诊断、名单管理、网络测试、原生 Diagnostics 跳转；`module/bin/fcm-monitor.sh` 有 FCM Socket 状态机与约 120s/300s 重连规则；`module/service.sh` 后台循环约 30s，并承担 stopped 巡检。仍需手机实机交叉验证。
- **APK：** 已存在 `FcmMonitorService.java`（前台服务，名义上每 15s 采样）、`app/src/main/assets/fcm-monitor.sh`、`MonitorActivity.java`、`BackgroundSettings.java`、`AdbConnectionManager.java`（AndroidKeyStore + libadb-android）、启动图标、`BootReceiver.java`。监控是否在 OriginOS 长时间存活尚未实测；`MainActivity.java` 仍是长表单且以第三方无障碍管理为主，UI 需重做。
- **已裁决需删除的旧代码：** `AccessibilityOps.java`、`RestoreService.java`、`RestoreTileService.java` 的**第三方无障碍**行为，以及 `MainActivity.java` 对第三方无障碍服务的扫描/开关/自动恢复。新版的「自动恢复」服务只负责用户启用的 FCM 监控及应用守护；「快捷磁贴」改为 FCM 立即检查/状态或受控重连，并允许从系统下拉栏添加。**不需要 APK 申请 AccessibilityService。代码尚未实施重构，不能说已删除。**
- **历史误判：** 先前 `docs/SIGNING.md`、README 记录旧 SHA 和“密钥未配置”，均为过期结论，已经被 GitHub 成功构建推翻；不可继续传播。

## 3. 当前已暴露的问题 / 未经实机证实

1. 有些短暂的 Google FCM Diagnostics 断联（例如几秒钟）不在 KSU 轮询记录内；**轮询漏检并非必然进程死亡**。
2. KSU 长时间不打开 WebUI 后当前连接计时可能变化；Socket 替换、脚本卡住、采样暂停必须区分；不能从 UI 重置直接判定 Doze。
3. APK Root/ADB shell `ss -tnpe` 的 GMS UID 可见性未在目标 Android 17/OriginOS 上逐模式验证。
4. APK 监控 Service 使用 Android FGS，厂商可杀/冻结；检查采样记录、自动恢复、崩溃错误和服务活动状态。
5. 目前后台优化只做若干标准 AppOps/DeviceIdle 调用；OriginOS 自启动/关联启动/高耗电/最近任务锁定无可靠自动写入证据，不得显示假成功。
6. **用户截图确认当前配对流程不可操作**：配对码在主页面输入，系统无线调试页面与 APP 无法同时操作；必须按 Shizuku 的通知栏 `RemoteInput` 输入并支持 mDNS，首页仅留「授权」入口。
7. 最近任务可隐藏但和“锁定任务”有先后关系；明确的开启/关闭开关还未合入主分支。
8. 应用内 GitHub Releases 更新/下载/版本签名验证尚未合入，正式 Release 流程也未建。
9. **用户截图确认首页顶住状态栏、UI 又长又乱**；必须先完成独立导航/安全区域适配，第三方无障碍扫描应移除，真正的 FCM 应用守护导入、浅深主题仍待实施。

## 4. P0-1 首个修复增量（CI 已通过，实机未验证）

- APK `app/src/main/assets/fcm-monitor.sh` 在 `ss -tnpe` 成功执行、但返回的全部 Socket 均缺少 UID 元数据时，直接报 `unknown / ss_uid_metadata_unavailable`，**不再误判“没有 FCM Socket”**。
- `tests/fcm-monitor-smoke.sh` 支持切换 KSU/APK 监控脚本；APK 增加“UID 被 ADB Shell 隐去不触发自动重连”回归用例。
- APK Actions 在 Gradle 编译前新增 Shell 语法与模拟状态机测试。**已经通过** [APK Actions #44132037143](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44132037143)：监控模拟、Debug 编译、P12 固定签名、Release 构建、apksigner 验证及 Artifact 上传均成功，产物 `BerryFCM-Guardian-v0.1.0-alpha.26`（Artifact 14349893236）。同提交的 [KSU Actions #44132036952](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44132036952) 成功。**本项仍未在目标手机实机验证**。

## 5. 下一步 **只做一个阶段**

**下一项开发门槛 G1–G5（模块先完备、后测试）：** 完成首页/授权/监控/应用守护/保活/设置的独立页面框架，修复状态栏覆盖；在独立授权页实现 mDNS + 通知栏 `RemoteInput` 配对。同步删除第三方无障碍服务扫描、旧自动恢复行为及旧磁贴，改为 FCM 自动恢复与 FCM 磁贴。**必须同时补齐监控、应用守护、后台保活、诊断和设置/关于各模块的真实功能，再要求用户测试**。随后通过 Actions 自验，再进行真实连接诊断与保活测试。

完成一项就将证据、commit、Actions run、未解决事项写回此文件，同时更新 PRODUCT_PLAN.md 的阶段状态。不要因新讨论跳过本阶段。

## 5A. 2026-11-18 新反馈决策记录

- 不是用户操作不当：**alpha.26 的无线 ADB 配对设计不具备在系统无线调试页输入配对码的可行交互**。不要求用户继续测试旧 UI。
- 首页不应含第三方无障碍服务扫描/多选，也不能被误认为 KSU 的“守护应用扫描/多选”。**第三方无障碍管理功能明确排除**。
- Android `BroadcastReceiver`、`ForegroundService`、`TileService` 足以构建自动恢复和下拉磁贴的基本能力；这两项均**不依赖 AccessibilityService**，但仍受 OriginOS 的后台启动限制。
- 优先修复顶部状态栏重叠，重构按模块导航；首页只显示一个授权入口，配对码从通知栏输入。
- 当前仅将决策同步 Markdown；**尚未完成新版 UI、配对和磁贴代码，不要声称已实机验收。**
- 详见 [PRODUCT_PLAN.md §5A](PRODUCT_PLAN.md)。

## 5B. 下一版 APK 的测试准入清单（用户再次强调）

**只有以下全部实现并通过开发者静态检查/Actions 后，才向用户提供安装步骤和要求测试：**

- [ ] 首页只显示概览和模块入口，授权只一个跳转按钮；全页面不再被系统状态栏遮挡
- [ ] 授权模块可在系统无线调试界面下拉通知栏输入六位码（RemoteInput），有 mDNS 自动发现/错误提示
- [ ] FCM 监控页面可启动、关闭、回读状态、查看断联/未知/采样空白及请求重连
- [ ] 应用守护页真正扫描已安装应用、搜索/多选/显示系统应用、查看 stopped 与受控修复
- [ ] 后台保活页包含电池豁免、OriginOS 自启动/关联启动/高耗电的授权入口和可验证项、任务列表隐藏开关及恢复、FCM 自动恢复
- [ ] 快捷设置磁贴真正执行 FCM 相关操作；无障碍服务扫描、恢复和旧磁贴全部移除
- [ ] 诊断/主题/项目主页及 GitHub Release 检查更新入口具备实际动作
- [ ] 新版 GitHub Actions Debug 编译、固定签名 Release 构建及验签成功；新会话 MD 同步本次变更

**当前 alpha.26 未达到上述标准，请勿让用户继续按旧界面尝试 ADB 配对。** 文档变更本身不能证明 APK 功能已完成。

## 6. 验收证据格式（后续统一）

- **Commit / Actions：** SHA + URL + APK / KSU Artifact。
- **编译验签：** Gradle 结果 + apksigner 结果 + 证书匹配。
- **实机：** 手机系统、Android / OriginOS 版本、Root/ADB 模式、测试步骤、时间点、Google 原生 Diagnostics 与 BerryFCM 对照、日志。
- **判断：** “已实机通过 / 已编译未实测 / 失败待查 / 设计中”四种，不用含混的“搞定”。
- **敏感信息：** 不记录 P12/Base64、密码、私人通知、完整设备唯一标识。只记录公开证书指纹。

## 7. 本轮代码变更（等待 GitHub Actions 结果）

已编写 APK 新版独立页面 `MainActivity` / `AuthActivity` / `MonitorActivity` / `GuardActivity` / `BackgroundActivity` / `DiagnosticsActivity` / `SettingsActivity` / `UpdateActivity`，共用 `Ui` 导航与 WindowInsets。ADB 配对 `AdbPairingService` 提供通知栏 RemoteInput + NSD 发现，`GuardOps` 提供 stopped 检查/恢复，`FcmTileService` 改为 FCM 监控磁贴，`BootReceiver` 仅恢复监控/应用守护。

第三方无障碍扫描/恢复代码从新版编译路径移除，`WRITE_SECURE_SETTINGS` 不再用于操纵别人的无障碍服务。Root/ADB 失效不得报告在线/已修复。

**下一步：检查代码编译、固定签名、工作流与新模块间依赖，修复所有 CI 问题。随后完善 Release 发布流水线；所有用户实机测试继续暂缓，直到所有模块实际可操作。**

## 8. 模块化首轮构建、自动测试与后续安排

- **已确认 Actions 成功**：[APK #44138228340](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44138228340)，新增 `MainActivity`、`Ui`、`AuthActivity`、`AdbPairingService`、`GuardActivity`、`BackgroundActivity`、`MonitorActivity`、`DiagnosticsActivity`、`SettingsActivity`、`UpdateActivity` 均编译成功；签名、验签、Artifact 上传通过；APK `BerryFCM-Guardian-v0.1.0-alpha.29`。尚未实机验收。
- 已移除第三方无障碍管理源代码与注册项。APP 的 FCM 自动恢复采用 `BootReceiver` + `FcmMonitorService`，下拉磁贴采用 `FcmTileService`，不需要 AccessibilityService。
- **正在补充**：受控 `workflow_dispatch publish_release` 和 `tests/apk-module-smoke.sh`，mDNS 本机过滤、GMS Doze 白名单操作归属及应用守护名单已选置顶；提交后须重新验证 Actions。
- **用户测试准入不变**：先完成全部模块可操作逻辑、GitHub Action 构建和静态检查；可预期的设备差异要在页面清楚提示，再给用户完整可执行的测试步骤。暂不正式发布 GitHub Release。
