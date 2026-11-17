# Berry-FCM-OS7 · BerryFCM

面向 Android / OriginOS 的 **FCM 连接观察、受控重连、应用 stopped 状态守护与后台设置诊断**。同一个 GitHub 仓库维护 **APK** 和 **KernelSU** 两个独立交付物：优先完善 APK，实机稳定后将通用改进回移 KernelSU。

## 开发入口

- [**完整目标、权限架构、所有页面功能和测试准入**](docs/PRODUCT_PLAN.md) — 项目单一需求基线
- [**实时开发交接、已验证构建与下一步**](docs/HANDOFF.md) — 新会话直接接续
- [APK 架构、无线配对操作与技术限制](app/README.md) · [固定签名及 Release 约定](docs/SIGNING.md)
- [KernelSU 模块功能](module/README.md)

## APK 当前代码

| 页面 | 已实现的操作 |
| --- | --- |
| 概览 | 连接状态、采样新鲜度、授权入口、功能导航 |
| Root / ADB 授权 | mDNS 配对端口自动发现、通知栏输入六位码、mDNS 失败时通知栏输入 \`端口:六位码\`、静态端口/Root Shell |
| FCM 监控 | GMS Socket 观测、连接时间线、unknown/stale、后台采样、受控重连 |
| 应用守护 | 已安装应用搜索/多选/系统应用选项、stopped 检测与修复、90 秒巡检和心跳 |
| 后台保活 | FGS/开机恢复、标准 Doze/AppOps、GMS 后台网络放行及受控撤销、OriginOS 设置跳转、最近任务隐藏 |
| 诊断 | Google 原生 FCM Diagnostics、mtalk TCP 检测、GMS 策略和事件分享 |
| 设置 / 更新 | 原生深浅色主题、FCM 快捷磁贴、项目主页、GitHub Release 检查/下载/固定签名校验安装 |

**原先的第三方无障碍服务扫描/开关/自动恢复已删除**。FCM 自动恢复与下拉快捷磁贴均使用 Android 原生机制，不需要 AccessibilityService。

## 已验证和未验证

- 最新已通过的 APK： [Actions #44197684568](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44197684568)（alpha.42），包括 FCM 监控模拟、模块结构测试、Gradle 编译、固定 P12 签名、apksigner 验证及 Artifact 上传。无线配对在 OriginOS 真机上仍需验证。
- KernelSU 仍为 v0.5.4-test，源码与数据目录独立，不受 APK UI 迁移影响。
- **GitHub Releases 目前尚无正式 APK**；普通 push 只发布 Actions Artifact。\`workflow_dispatch.publish_release\` 明确手动勾选后才创建预发布版本，应用内更新只读取正式 Release。
- 未完成的 **真机验证**：OriginOS 系统通知栏是否呈现 RemoteInput、ADB Shell 能否看到 GMS UID Socket、stopped 修复权限、锁屏过夜采样、用户授权和系统安装器覆盖升级。**CI 绿灯不代表这些已经通过。**

## 设计和安全原则

Android API 优先，Root/ADB Shell 只做确有权限需求的功能。无法验证时显示未知、不误报连接恢复，不重启/杀死 GMS，不擅自修改 DNS/VPN/代理/路由。OriginOS 自启动、关联启动、高耗电、任务锁定若没有可靠 API 则跳系统设置，不能提供虚假的成功开关。隐藏最近任务卡片不是隐藏后台进程。所有固定签名材料留在 GitHub Secrets 和用户 D 盘，不提交私钥或密码。

**开发只通过当前对话及 GitHub 仓库/Actions 进行，不切换 Work；每次更新同步产品计划和实时交接 Markdown。**

## 开源参考

[Shizuku](https://github.com/RikkaApps/Shizuku)（通知栏无线 ADB 配对）、[GKD](https://github.com/gkd-kit/gkd)（模块导航/后台服务经验）、[FCMFix ColorOS](https://github.com/ByteJuno/fcmfix-coloros)（mtalk Socket 识别边界）、[vivo-guardian](https://github.com/Lokside/vivo-guardian)（OriginOS 后台限制）、[libadb-android](https://github.com/MuntashirAkon/libadb-android)（ADB 客户端）。借鉴适用实现，不直接照搬界面。

## 新版外观与在线更新兼容

APP 桌面名称及新版安装包文件名已缩短为 **BerryFCM**。底部导航与各功能模块使用 Apache-2.0 的 [Google Material Icons](https://github.com/google/material-design-icons) 矢量图标，启动器是原创的 FCM 消息通知图形，不再使用 Wi-Fi 弧线。

旧 alpha.36 的在线更新检查器只认 `BerryFCM-Guardian-v...`，因此**首个新正式 prerelease 会同时附上旧名字兼容文件和 `BerryFCM-v...` 短名字文件，两份 APK 内容与固定签名相同**；新版本更新器兼容两者并优先使用新名字。GitHub Actions 普通构建仅提供测试 Artifact；未人工发布 GitHub Release 前，现有 APP 无法发现更新。

## 2026-11-18 进度简述

按产品计划，APK 已具备大部分页面和对应后端逻辑，功能代码完成度约 75%（主观估算，非验收通过率）；无线 ADB / GMS Socket 可见性、OriginOS 后台恢复、Release 在线覆盖升级仍需手机验证，APK 稳定后才开展 KernelSU 回移。本轮已将不需特权的 mtalk TCP 检测从 Shell 改为 App 原生 Socket，并为 BerryFCM 自身 Doze/AppOps 配置加入原值记录与安全回滚。旧版缺少原值记录的历史更改不会盲目撤销。
