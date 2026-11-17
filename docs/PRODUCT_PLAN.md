# Berry-FCM-OS7：统一产品目标、功能基线与开发计划

> **唯一产品基线 / Single Source of Truth** · 更新：2026-11-18 · 状态：需求框架已确认，权限策略与个别功能待实机裁决。  
> 仓库：[jarod360/Berry-FCM-OS7](https://github.com/jarod360/Berry-FCM-OS7) · 交接：[HANDOFF.md](HANDOFF.md) · 签名：[SIGNING.md](SIGNING.md)
>
> **执行约束**：只在当前聊天沟通并通过 GitHub 仓库改代码、GitHub Actions 编译和验证；不跳转 Work。不擅自删旧功能；任何分歧先列证据、成本和替代方案。每次功能变更须同步更新本文件与 HANDOFF.md，避免新会话失忆。用户 Windows 本地开发目录仅为 `D:\Codex`；不擅自写 C 盘。GitHub Runner 的云端临时环境不等于在用户电脑写 C 盘。

## 1. 已确定的八项目标（来自本轮需求）

1. **APK 优先，KernelSU 双线保留**：先把既有 KernelSU 有效的 FCM/应用守护功能逐步移植、完善到 APK，在目标 OriginOS 手机上验收后，复用可靠代码或规则回移到 KernelSU；并非弃用 KernelSU。
2. **先做权限架构**：逐项区分标准 Android API、Root/ADB Shell、厂商页面手动授权以及可能需要无障碍的极少数操作。能通过 Android API/ADB 完成的，**不新增 AccessibilityService**。
3. **重新设计实用界面**：参考成熟开源 Android 应用的导航、分组、状态呈现与错误反馈，不抄界面；**不得把全部功能纵向堆一个页面**。
4. **授权尽量省操作**：能可靠写入并回读的设置一键配置；否则单击跳转到可用的系统/OriginOS 页面，返回后检查状态。不用无法验证的假开关。
5. **GitHub Releases 在线更新**：正式发布的固定签名 APK 在应用内检查版本、展示日志、下载并交给系统安装；Release 与 Actions Artifact 必须区分。
6. **对话提炼与需求留痕**：保存目标、决策、反对意见、未解决风险、测试证据和下一步任务；不把猜测写成事实。
7. **全部代码和交付在 GitHub**：单仓库，APK/KSU 各自 Actions，固定签名、版本递增、测试包与正式 Release 分开；文档跟随提交。
8. **不转 Work**：当前聊天直接讨论、审查仓库、提交代码与查看 Actions。临近聊天长度限制也以 Markdown 为衔接依据。

## 2. 产品定位与边界

**BerryFCM Guardian** 是面向 Android / OriginOS 的 **FCM 状态观察、受控重连、应用停止状态守护、后台运行诊断/引导** 工具；不是通用“无障碍管理器”，也不是声称永久不死的进程工具。

原则：
- **观察和修复分开**：Socket `ESTAB` 只是 TCP 观察证据，不等于 Google MCS 已认证或推送必达。重连广播已返回也不等于连接已经恢复。
- **三态加过期**：在线（有证据）、缺失/连接中（有证据）、未知（权限/网络/DNS/采样缺失）、数据过期；无数据时不延续“持续在线”计时。
- **漏检必须可见**：对照原生 Google FCM Diagnostics 与真实消息；相隔 15–30 秒的轮询无法承诺捕获 2–4 秒断联。不得虚构断开时刻。
- **不破坏系统**：不修改 DNS、代理、VPN、路由、hosts；不自动强杀/重启 GMS，不自动解除用户在系统设置中主动停止的应用。
- **权限最小化、可撤销**：先回读权限及原值，再改变；只撤销本应用自己添加的白名单/策略；不要无条件替用户把 GMS 和所有应用改为高耗电。
- **后台保活是尽力而为**：标准前台服务、开机广播、厂商授权组合可提高可靠性，但无法承诺任意厂商/Doze/强行停止下永久运行。隐藏最近任务卡片 ≠ 隐藏后台进程/前台服务通知。

## 3. KernelSU 现有功能迁移清单

依据 `module/bin/ctl.sh`、`module/bin/fcm-monitor.sh`、`module/service.sh`、`module/webroot/index.html`，**这是已有能力清单，不代表 APK 已实机验收**。

| 编号 | 功能 | KSU 基线 | APK 目标 | 优先级 |
| --- | --- | --- | --- | --- |
| K01 | GMS UID、5228/5229/5230 Socket、可信 mtalk 443 候选识别 | 已有 Shell 状态机 | 复用判定且保守处理 UID/ss 不可读 | P0 |
| K02 | connected/connecting/disconnected/unknown、事件历史、Socket 更换 | 已有；最近 120 条 | 持久保存、采样空白时间单列 | P0 |
| K03 | 请求 `GCM_RECONNECT`、连续缺失阈值与冷却 | 约 120 秒、300 秒 | 默认沿用；记录“请求”与实际观察恢复区别 | P0 |
| K04 | 后台巡检守护、开机运行 | KSU shell 约每 30 秒 | APK 前台服务；监控与 UI 解耦，失败重试 | P0 |
| K05 | 守护应用扫描、搜索、多选、已选置顶、包含系统应用选项 | KSU WebUI 已有 | APK 独立“应用守护”页面；按包名选目标，绝不与“无障碍服务扫描”混淆 | P1 |
| K06 | 检测应用 stopped，尝试 `cmd package unstop`、心跳与受限修复 | KSU Root 已有 | Root/ADB 分别做权限验证，不能解除的显示失败 | P1 |
| K07 | Doze 白名单、后台计量网络/数据节省策略、GMS 通知检查 | KSU 已有策略与诊断 | 一键设置/回读/按归属撤销 | P1 |
| K08 | Google 原生 FCM Diagnostics 跳转、TCP 可达性测试 | KSU 已有 | 单独诊断入口，说明测试路由与 GMS 路由可能不同 | P1 |
| K09 | 浅色/深色主题、清晰移动端展示 | KSU 已有 | APK 统一主题和导航；尊重系统或用户选择 | P1 |
| K10 | KernelSU 双线更新 | KSU Actions 已有 | APK 验收后回移不依赖 APK 的监控/修复优化 | P2 |

**不要把 KSU 旧版全部源码直接粘入 APK**：`module/bin/ctl.sh` 使用 Root-only 判定和目录，普通 ADB Shell 不能默认拥有同等权限。对每个 Shell 命令必须实机比对 `id`、退出码和读回值。KSU 版现有独立目录与数据要保留。

## 4. 权限分工与评估结论

| 功能 | 最小推荐能力 | 失败/限制与处理 | 要无障碍吗 |
| --- | --- | --- | --- |
| 页面、状态、日志、统计、历史、主题 | Android API + 本地持久化 | 采样源失效显示未知 | 否 |
| 应用名单/图标/搜索/多选 | PackageManager 可见性查询 | Android 11+ 包可见性限制；只按实际所需声明 queries，是否用广域扫描权限需另行评审 | 否 |
| FCM Socket/UID、stopped、政策检查与受控修复 | 已连接 Root 或授权 ADB Shell | ADB `shell` 未必有 Root Socket 权限，`ss -tnpe` 可能删去 UID；不满足时显示未知而非离线 | 否 |
| Android 设备空闲白名单、AppOps | ADB Shell/Root，先回读后验证 | Shell 操作可能被 ROM 拒绝；可撤回自己添加的设置 | 否 |
| 无线 ADB 配对 | ADB 客户端库 + AndroidKeyStore + mDNS + 通知栏 RemoteInput | 用户需在系统开发者选项显式启用无线调试；不能静默开启；mDNS 失败可备用手动端口 | 否 |
| 后台持续采样 | 可见且合法的 Android Foreground Service + 开机恢复广播 | Doze、OEM、用户强行停止可能暂停或阻止，记录采样空白和死亡恢复 | 否 |
| 忽略电池优化 | 系统电池授权页；ADB Root/Shell 可辅助 DeviceIdle 白名单 | Android 用户授权与厂商高耗电开关不同，不能混为一谈 | 否 |
| OriginOS 自启动、关联启动、允许后台高耗电 | 先试明确可用的系统/厂商设置入口 | 厂商私有开关通常不能靠普通 ADB 可靠写入；跳转后由用户确认并标为“待确认” | 手动不需要 |
| 最近任务锁定 | 厂商最近任务 UI | **先锁定，后隐藏卡片**；ADB 不保证能改厂商私有锁名单 | 手动不需要 |
| 隐藏/显示最近任务卡片 | Android AppTask `setExcludeFromRecents` 或等效合法 API | 只影响卡片，不隐藏进程/前台任务；可随时恢复 | 否 |
| 快捷设置 Tile | Android `TileService` | 改为 FCM 检测/受控重连等相关操作；不得静默安装到下拉栏 | 否 |
| Release 检查、下载、升级 | 网络/系统下载与安装器 | 正式 Release 资源、版本与签名必须验证；不静默升级 | 否 |
| 扫描/启停 **第三方无障碍服务** | ADB Shell/`WRITE_SECURE_SETTINGS`（若明确做该功能） | 这不是 FCM 守护应用扫描；目前非核心、待取舍，不应成为 APK 默认定位 | 实现此项本身也不需要 BerryFCM 申请 AccessibilityService |
| 自动点击厂商权限、监听界面事件 / GUI 自动化 | 可能需 AccessibilityService | 脆弱、用户授权敏感、厂商兼容成本高；当前**不纳入**，经实机证明必要后再讨论 | 可能需要 |

**未达成的权限验证不能伪装成“已授权”**。特别是 `WRITE_SECURE_SETTINGS` 是设置管理权限，并非持续 ADB Shell，也非读取 GMS UID Socket 的通用授权。

## 5. 设计稿：推荐信息架构

目标采用 **清晰的分组卡片 + 独立导航页面 + 回读状态行**，参考 [GKD](https://github.com/gkd-kit/gkd) 的设置组织、[Shizuku](https://github.com/RikkaApps/Shizuku) 的授权/通知栏配对、[FCMFix ColorOS](https://github.com/ByteJuno/fcmfix-coloros) 的诊断与设置表达、[vivo-guardian](https://github.com/Lokside/vivo-guardian) 的 OriginOS 后台限制经验。只借鉴流程，不复制源代码、界面或颜色资源。

**首页 / 概览**：大状态卡（FCM 观测状态、采样新鲜度、监控开关、断联/未知次数）；一键进入“ADB / Root 授权”模块；监控、应用守护、后台保活、诊断、设置/关于入口。**不放配对码输入，也不放长列表。**

**授权**：选择无线 ADB、Root、静态 ADB、外部 USB 初始化；优先无线 ADB；显示 Shell UID、连接能力/权限区别和最近错误；首次配对按钮 → 通知栏可直接输入 6 位配对码（Android RemoteInput）→ 配对/连接结果；配对身份保留在 AndroidKeyStore；断开重新连接有明确提示。不在应用主界面强制输入配对码。通知权限被禁时显示解释与跳转入口。

**FCM 监控**：在线/缺失/未知/过期清晰区分；最近采样、Socket 类型/远端、时间线、切换次数、持续时长；记录采样空白；手动重连有冷却提示；原生 Diagnostics 一键打开。

**应用守护**：搜索已安装应用、系统应用开关、已选置顶、多选，GMS 默认不可误删除；已守护目标、`stopped` 状态和最新修复事件，独立于“无障碍服务”。

**后台保活与授权**：后台监控、开机启动、忽略电池优化、后台 AppOps、OriginOS 自启动、关联启动、后台高耗电、最近任务锁定引导、隐藏最近任务卡片、静音/低干扰通知，逐项显示 `已确认生效 / 需手动设置 / 无法验证 / 未开启`，带开启、关闭、跳转按钮；有撤销或恢复路径。

**设置与关于**：浅色/深色/跟随系统、日志导出（隐去敏感信息）、项目主页、问题反馈、检查 GitHub Releases 更新、更新日志、安装器升级。默认不自动安装或跳转 Work。

**明确的 UI 禁止项**：把所有控件叠在一页、假开关、配对码日志泄露、靠颜色一项表达授权状态、申请无必要权限、隐藏 Android 前台服务身份。

## 6. 版本、签名和 Release

- APK 包名固定 `top.zesun.berryfcm`；后续可覆盖升级必须保持证书与版本号正确。
- 已经确认**当前有效签名证书 SHA-256**：`BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615`。这是证书指纹，绝不可提交私钥、密码和 P12 Base64。**不重新生成密钥、不再让用户重复填写四项 Secrets。**
- 2026-11-17 APK [Actions #44122890970](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44122890970)：Debug 编译、固定签名准备、Release 构建、apksigner 验证、Artifact 上传全部成功；Artifact 名称 `BerryFCM-Guardian-v0.1.0-alpha.24`。**编译成功 ≠ 手机实机稳定。**
- 截至本次核查，仓库 **Releases 列表为空**；不能说已支持在线升级。下一阶段采用签名构建 → 验签 → 手动审核/受控发布到 GitHub Releases → APP 检查 `versionCode`、下载并校验签名/包名 → 系统安装器升级。Release 可用性须在正式发布后实测。
- 原有 `docs/SIGNING.md` 曾记录旧指纹/旧状态，本次同步修订。密钥必须仅留在用户授权的本地 D 盘或 GitHub Secrets。

## 7. 实施阶段（按顺序，不随新需求随意跳线）

| 阶段 | 交付与验收 | 状态 |
| --- | --- | --- |
| **P0-0 目标与交接冻结** | 本文件、HANDOFF、签名文档和首页 README 同步，建立待办及范围变更规则 | **本次进行** |
| **P0-1 修复监控可信度** | Root/ADB 实机分别检查 `ss` 权限、状态机；采样 gap/超时、Shell 重连失败、错误日志与数据过期；原生 Diagnostics 与真实通知对照 | **代码已有，实机未验** |
| **P0-2 UI 导航重构** | 首页简洁，独立授权/监控/守护/保活/设置页；不丢保存的模式与守护名单；浅深主题；手机实机可用 | 待实施 |
| **P0-3 授权体验** | 通知栏 RemoteInput 配对码、mDNS 发现、授权/能力验证、授权中断恢复、跳转开发者选项 | 待实施 |
| **P0-4 后台设置与保活** | FGS 启停及复活、开机、DeviceIdle/AppOps 真状态、厂商页面导航、手动锁定后隐藏任务切换 | 现有局部实现，待验证 |
| **P1-1 应用守护搬迁** | KSU `targets`/多选/系统应用/停止状态/修复与必要的冷却和回读；权限不足不硬执行 | 待实施 |
| **P1-2 诊断与性能** | FCM 网络可达性测试、可复制/导出日志、采样时长与电耗统计；多日锁屏/网络切换验收 | 待实施 |
| **P1-3 GitHub Releases 升级** | 正式签名 Release、更新说明、版本比较、下载、安装包名/签名检验、覆盖升级实测 | 待实施 |
| **P2 KSU 双线回移** | 将 APK 实机证实的监控判定/错误处理回移至 KernelSU，跑 smoke 与 KSU Actions，保留旧配置 | 待实施 |

验收 **不以“Actions 绿灯”代替手机测试**。核心用例：关闭 APK、隐藏最近任务、锁屏 1h/过夜、Doze、Wi‑Fi/移动网络/代理切换、无线调试断开、GMS Socket 替换与断联、原生 Diagnostics 秒级断联对照、设备重启、后台被系统清理、连续覆盖更新。统计数据必须在界面关闭后持续积累；不采样的区间不得当成在线。

## 8. 讨论后才决定的事项（明确记录，不偷偷做）

1. **权限方式**：APK 以无线 ADB 为首选、Root 可选；但在不重启手机的前提下关闭无线调试后持续采集特权 Socket 的方案尚无证据，不作保证。是否引入额外系统级辅助通道，必须先证明必要。
2. **无障碍管理旧代码**：仓库里仍有 `AccessibilityOps.java`、`RestoreService.java` 等旧功能；它们是“管理第三方无障碍服务”，**不是 FCM 应用守护**。当前不扩展、不误删用户数据；待完成迁移差异核对，再决定精简与兼容处理。
3. **OriginOS 厂商权限自动开关**：只有获得可靠、可逆、经真实设备回读的写法才做自动开关；否则使用“跳转手动设置”，不得以本地布尔值假装生效。
4. **前台通知隐藏**：可以静音或尽量降低干扰，但不能承诺隐藏强制前台服务状态同时保持持久运行。最近任务隐藏独立设计。
5. **应用守护修复范围**：不自动对所有应用无限制解除停止；只针对用户名单，尊重用户主动停止行为，防止额外耗电和误唤醒。

## 9. 变更管理与会话交接规范

任何具体开发提交至少同步：
- 本文件：变更的目标/阶段、设计选择、是否增加权限、回滚方式。
- [HANDOFF.md](HANDOFF.md)：**当前 commit/Actions run、已做、已验证、未完成、下一步一个动作**。不得保留冲突的“旧指纹/Secrets 未配置”等过期结论。
- 代码与测试：尽量以小提交、少依赖完成；保留已有配置；失败时先检查日志，禁止未经证据责怪密钥或手机。
- Release 记录：正式可安装 APK 只能由固定签名产线发布；KSU ZIP 与 APK 分开追踪。
- 若聊天超长，新聊天直接提供这两个 Markdown 链接即可接着工作；聊天内讨论形成的新决定当轮更新仓库。

## 10. 开源参考与关键限制

- [Shizuku](https://github.com/RikkaApps/Shizuku)：`AdbPairingService.kt` 使用 Android `RemoteInput` 通知输入配对码、mDNS 查找配对端口；参考其交互，保留本项目现有 `libadb-android`，不复制整个依赖体系。
- [GKD](https://github.com/gkd-kit/gkd)：清晰设置分组与服务状态呈现；其无障碍保活不直接等于 FCM Socket 监测。
- [FCMFix ColorOS](https://github.com/ByteJuno/fcmfix-coloros)：诊断和 mtalk 443 限定；其 LSPosed/系统 Hook 能力不能等同于普通 APK 的 ADB 权限。
- [vivo-guardian](https://github.com/Lokside/vivo-guardian)：OriginOS 的强制停止、任务锁定和保活边界；其无障碍服务专为监听/解封 stopped 场景，不能无依据直接迁移。

> 以上是开发约束与验收计划，不表示表内“待实施”功能已经完成。先让 APK 可测且可诊断，再推进外观和 Release，最后回移 KSU。
