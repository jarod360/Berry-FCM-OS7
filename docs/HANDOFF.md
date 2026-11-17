# Berry-FCM-OS7 实时开发交接

**更新：2026-11-18** · 详细产品目标和权限裁决以 [PRODUCT_PLAN.md](PRODUCT_PLAN.md) 为准。此文件记录**已实现 / 已构建 / 实机未证实 / 下一步**，每次 GitHub 提交时更新。

## 1. 现在做什么

- **目标优先级：先 APK 后 KernelSU 双线同步。** 不放弃 KernelSU，也不把已有 KSU 代码与普通 APK 的权限混为一谈。
- **产品功能：** FCM 长连接观察/受控重连、事件时间线、后台可靠性与 OriginOS 权限优化、应用守护 stopped 修复、诊断与 Release 更新。
- **交互结构：** 首页只显示概览和各模块入口。独立授权（ADB 配对通过通知栏输入）、FCM 监控、应用守护、后台保活、诊断、设置/关于。不能再把按钮/长名单挤在一个页面。
- **权限原则：** Android API 能做则不用 ADB；ADB Shell/Root 能做则不引入 AccessibilityService；OriginOS 厂商私有锁定/高耗电开关不可凭空承诺自动修改。现有“管理第三方无障碍服务”代码与 FCM 主线不同，需审议后迁移/精简，暂不破坏保存数据。
- **协作范围：** 当前聊天 + GitHub 工具 + GitHub Actions。不得跳转 Work；本地 Windows 相关文件仅放 `D:\Codex`，未请求时不创建用户电脑的文件。

## 2. 仓库现状，已核查的事实

- 仓库：`jarod360/Berry-FCM-OS7`，主分支 `main`。
- **本次开始整理时 HEAD**：`3561ec8fb9dae9818680dc11f0780e1a3b5b00d5`（这是文档修订前的代码基线；未来提交见仓库当前历史）。
- **APK 构建**：[Actions #44122890970](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44122890970) 成功：Debug 编译、签名准备、Release、apksigner、Artifact 上传均成功；产物 `BerryFCM-Guardian-v0.1.0-alpha.24`（Artifact ID 14347120446）。**并未证明手机后台可靠、网络断联识别准确。**
- **签名：** GitHub Actions 当前固定证书 SHA256 `BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615`；签名四项 Secrets 已在成功构建中实际使用。**禁止再要求重生密钥/重复填写 Secrets。**
- **Releases：** 2026-11-18 查询仓库 Releases 数组为空；尚无正式 Release，无法声称已经支持在线自动更新。Actions Artifact 不是 Release。
- **KernelSU：** `module/module.prop` 为 `0.5.4-test`；`module/bin/ctl.sh` 有守护应用 stopped/解除停止、网络/Doze 诊断、名单管理、网络测试、原生 Diagnostics 跳转；`module/bin/fcm-monitor.sh` 有 FCM Socket 状态机与约 120s/300s 重连规则；`module/service.sh` 后台循环约 30s，并承担 stopped 巡检。仍需手机实机交叉验证。
- **APK：** 已存在 `FcmMonitorService.java`（前台服务，名义上每 15s 采样）、`app/src/main/assets/fcm-monitor.sh`、`MonitorActivity.java`、`BackgroundSettings.java`、`AdbConnectionManager.java`（AndroidKeyStore + libadb-android）、启动图标、`BootReceiver.java`。监控是否在 OriginOS 长时间存活尚未实测；`MainActivity.java` 仍是长表单且以第三方无障碍管理为主，UI 需重做。
- **旧有代码：** `AccessibilityOps.java`、`RestoreService.java`、`RestoreTileService.java`、`MainActivity.java` 中扫描/勾选无障碍服务逻辑仍在。用户明确要求仅保留实际有价值的功能；**先对齐“应用守护名单”和“第三方无障碍服务管理”两者的差异，再做最小、安全的迁移**。快捷磁贴建议保留、功能改为 FCM 相关。
- **历史误判：** 先前 `docs/SIGNING.md`、README 记录旧 SHA 和“密钥未配置”，均为过期结论，已经被 GitHub 成功构建推翻；不可继续传播。

## 3. 当前已暴露的问题 / 未经实机证实

1. 有些短暂的 Google FCM Diagnostics 断联（例如几秒钟）不在 KSU 轮询记录内；**轮询漏检并非必然进程死亡**。
2. KSU 长时间不打开 WebUI 后当前连接计时可能变化；Socket 替换、脚本卡住、采样暂停必须区分；不能从 UI 重置直接判定 Doze。
3. APK Root/ADB shell `ss -tnpe` 的 GMS UID 可见性未在目标 Android 17/OriginOS 上逐模式验证。
4. APK 监控 Service 使用 Android FGS，厂商可杀/冻结；检查采样记录、自动恢复、崩溃错误和服务活动状态。
5. 目前后台优化只做若干标准 AppOps/DeviceIdle 调用；OriginOS 自启动/关联启动/高耗电/最近任务锁定无可靠自动写入证据，不得显示假成功。
6. 配对目前仍在主界面输入，实际应参考 Shizuku 通过通知 `RemoteInput` 输入，并支持 mDNS。
7. 最近任务可隐藏但和“锁定任务”有先后关系；明确的开启/关闭开关还未合入主分支。
8. 应用内 GitHub Releases 更新/下载/版本签名验证尚未合入，正式 Release 流程也未建。
9. 主界面风格、导航、系统服务过滤、应用守护导入、浅深主题均未按最终规划验收。

## 4. P0-1 首个修复增量（进行中）

- APK `app/src/main/assets/fcm-monitor.sh` 在 `ss -tnpe` 成功执行、但返回的全部 Socket 均缺少 UID 元数据时，直接报 `unknown / ss_uid_metadata_unavailable`，**不再误判“没有 FCM Socket”**。
- `tests/fcm-monitor-smoke.sh` 支持切换 KSU/APK 监控脚本；APK 增加“UID 被 ADB Shell 隐去不触发自动重连”回归用例。
- APK Actions 在 Gradle 编译前新增 APK 监控脚本 Shell 语法与模拟状态机测试。此处是待 CI 验证的源码变更，**不等于通过手机测试**。

## 5. 下一步 **只做一个阶段**

**P0-1：验证与修正监控基线**。先检查 `FcmMonitorService` 与复用脚本在 Root/ADB 下的失败分支，增强采样间隔、数据过期、Shell 权限变化与日志，保证“不采样/不可读 ≠ 已连接/已断联”；随后开始 P0-2 UI 导航与 P0-3 通知栏配对。

完成一项就将证据、commit、Actions run、未解决事项写回此文件，同时更新 PRODUCT_PLAN.md 的阶段状态。不要因新讨论跳过本阶段。

## 6. 验收证据格式（后续统一）

- **Commit / Actions：** SHA + URL + APK / KSU Artifact。
- **编译验签：** Gradle 结果 + apksigner 结果 + 证书匹配。
- **实机：** 手机系统、Android / OriginOS 版本、Root/ADB 模式、测试步骤、时间点、Google 原生 Diagnostics 与 BerryFCM 对照、日志。
- **判断：** “已实机通过 / 已编译未实测 / 失败待查 / 设计中”四种，不用含混的“搞定”。
- **敏感信息：** 不记录 P12/Base64、密码、私人通知、完整设备唯一标识。只记录公开证书指纹。
