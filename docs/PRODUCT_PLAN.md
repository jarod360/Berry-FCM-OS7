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
| 后台持续采样及自动恢复 | Android Foreground Service、`START_STICKY`、开机/更新广播、显式启停持久配置 | **自动恢复的是 BerryFCM 监控任务及可选应用守护，不是他人无障碍服务**；Doze/OEM/强行停止可能阻止恢复，必须记录空白时间 | 否 |
| 忽略电池优化 | 系统电池授权页；ADB Root/Shell 可辅助 DeviceIdle 白名单 | Android 用户授权与厂商高耗电开关不同，不能混为一谈 | 否 |
| OriginOS 自启动、关联启动、允许后台高耗电 | 先试明确可用的系统/厂商设置入口 | 厂商私有开关通常不能靠普通 ADB 可靠写入；跳转后由用户确认并标为“待确认” | 手动不需要 |
| 最近任务锁定 | 厂商最近任务 UI | **先锁定，后隐藏卡片**；ADB 不保证能改厂商私有锁名单 | 手动不需要 |
| 隐藏/显示最近任务卡片 | Android AppTask `setExcludeFromRecents` 或等效合法 API | 只影响卡片，不隐藏进程/前台任务；可随时恢复 | 否 |
| 下拉快捷设置磁贴 | Android `TileService` | 由用户在系统下拉栏添加；提供 FCM 状态/立即检测/受控重连等操作；**不恢复第三方无障碍服务** | 否 |
| Release 检查、下载、升级 | 网络/系统下载与安装器 | 正式 Release 资源、版本与签名必须验证；不静默升级 | 否 |
| 扫描/启停 **第三方无障碍服务** | **确定从新版 APK 移除** | 旧代码混入的非 FCM 功能；不再为此使用 `WRITE_SECURE_SETTINGS` | 不需要 |
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

## 5A. 2026-11-18 用户最新实机反馈与定案（优先级最高）

**问题依据**：用户提供的 APK 首页及近期日志截图，指出①配对码输入框留在 APP 内，切换到无线调试配对界面就无法同时输入，流程实际上不可测；②首页标题压住 Android 状态栏；③长页面堆满授权与不相关的第三方无障碍服务管理。**这三项是 UI/授权阻断问题，不是让用户重新配密钥或先测旧 APK 可以解决的。**

**已定的处理：**
1. **页面优先**：先完整设计并实现按模块导航的页面与对应操作，再提供测试 APK。首页仅 FCM 概览、授权状态与各模块入口，点击「授权」才进入独立授权页；不得在首页放输入框。
2. **通知栏配对**：授权页一键开始监听 `_adb-tls-pairing._tcp` mDNS 并显示配对通知；用户在系统无线调试界面生成六位码，下拉通知栏使用 `RemoteInput` 提交。必要时支持手动配对端口作为备用，不能强迫切回 APP 才能提交配对码。通知权限禁用时必须明示并跳到设置。
3. **状态栏适配**：不要使用覆盖状态栏的默认布局或沉浸式处理；处理系统安全区域、标题顶部间距及 OriginOS 不同状态栏高度。以用户截图为回归依据。
4. **自动恢复**：保留/实现**FCM 后台监控与用户选中的应用守护任务自动恢复**（开机、更新、进程被系统回收后系统许可的恢复）。**完全不需要第三方无障碍服务。**
5. **磁贴**：保留 **Android Quick Settings Tile**，提供 FCM 立即检测/监控入口或受控重连；用户自行添加到下拉快捷设置，不依赖 AccessibilityService，不做旧“恢复无障碍服务”操作。
6. **删除无关旧功能**：第三方无障碍服务扫描、多选、开启关闭、旧自动恢复服务及对应 UI 不属于 BerryFCM。应同步清理 Manifest、入口与死代码；**不可因此删掉 FCM 应用守护名单的搜索/多选与开机自动恢复功能**。
7. **新版本验收顺序**：导航、布局、配对通知可操作 → Github Actions 编译固定签名 → 首次实机检查配对和授权 → FCM 监控/保活长期对照；不要求用户在不可配对的 alpha.26 上做无效测试。

**还未实测**：通知栏 RemoteInput 在目标 OriginOS 上的展示方式、ADB mDNS 可发现性、无 Root Shell 下读取 GMS Socket 的可见性、开机自恢复。要求显示明确错误，不凭模拟测试宣称成功。

## 6. 版本、签名和 Release

- APK 包名固定 `top.zesun.berryfcm`；后续可覆盖升级必须保持证书与版本号正确。
- 已经确认**当前有效签名证书 SHA-256**：`BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615`。这是证书指纹，绝不可提交私钥、密码和 P12 Base64。**不重新生成密钥、不再让用户重复填写四项 Secrets。**
- 2026-11-17 APK [Actions #44122890970](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44122890970)：Debug 编译、固定签名准备、Release 构建、apksigner 验证、Artifact 上传全部成功；Artifact 名称 `BerryFCM-Guardian-v0.1.0-alpha.24`。**编译成功 ≠ 手机实机稳定。**
- 截至本次核查，仓库 **Releases 列表为空**；不能说已支持在线升级。下一阶段采用签名构建 → 验签 → 手动审核/受控发布到 GitHub Releases → APP 检查 `versionCode`、下载并校验签名/包名 → 系统安装器升级。Release 可用性须在正式发布后实测。
- 原有 `docs/SIGNING.md` 曾记录旧指纹/旧状态，本次同步修订。密钥必须仅留在用户授权的本地 D 盘或 GitHub Secrets。

## 7. 固定实施顺序：先完整模块，再形成可测 APK

**原则：开发者自己先完成静态/编译/模拟验证；只有界面、入口和功能真正可操作后，才请求用户安装新 APK 做实机功能测试。** 不让用户在缺少通知栏配对的旧 APK 上继续徒劳测试；代码功能尚不存在时不写“已完成”。

| 门槛 | 阶段及不可跳过的交付 | 验收标准 | 当前状态 |
| --- | --- | --- | --- |
| G0 | **需求和现状冻结**：统一产品目标、权限矩阵、KSU 迁移差异、现有缺陷截图、签名与构建基线 | `PRODUCT_PLAN.md`、`HANDOFF.md` 可直接交接 | 文档已提交，后续需求持续增补 |
| G1 | **确定并实现全部 APK 功能模块和页面**：导航、首页、授权、FCM 监控、应用守护、后台保活、诊断、设置/关于。移除第三方无障碍管理，不丢 FCM 需求 | **每个菜单都能进入对应页面、控制项有真实操作及回读，页面不压状态栏，不是仅有空白导航** | 模块代码已合入；Actions #44138228340 编译/签名成功，真实 UI/授权待测试 |
| G2 | **授权可操作**：无线 ADB mDNS 配对发现、系统通知栏 `RemoteInput` 输入配对码、状态/失败反馈；Root/静态端口备选；系统设置直达 | **用户无需离开无线调试页面返回 APK 才能输入六位码**；通知禁用时正确提示 | 通知栏 RemoteInput 与 mDNS 已通过编译，待实机配对验证 |
| G3 | **接齐核心后台功能**：KSU FCM 观测/历史/重连、`stopped` 守护名单及修复、自动恢复与 FCM 快捷磁贴、OriginOS 设置/后台控制、任务列表隐藏、诊断 | **不采样=未知；可授权才执行；每个开关回读；无无障碍服务依赖** | 页面与后台逻辑已通过编译；权限边界和数据迁移仍需审查 |
| G4 | **界面与发布闭环**：浅/深色主题、版本与项目主页、GitHub Releases 检查/下载/验签升级；建立签名 Release 发布流程 | **独立模块完整，操作/错误/权限状态显示清楚** | 设置/检查/下载安装已通过编译；受控 Release 发布工作流已加，需 Actions 验证 |
| G5 | **开发者自验和云编译**：Shell 单测、Gradle、固定 P12 签名、`apksigner`、APK 功能代码与 Manifest 自检、构建 Artifact；文档同步 | **全部 CI 通过，逐项列明仍需手机验证的限制** | 既有 alpha.26 CI 通过，新增功能需重新跑 |
| G6 | **用户实机测试（仅 G1–G5 完成后）**：首次/重新 ADB 配对、后台授权、FCM 与 Diagnostics 对照、联网/锁屏过夜、任务隐藏、开机、自启动、覆盖升级 | **提供清晰且实际可执行的操作步骤；收集日志后修复再测** | 尚不具备条件 |
| G7 | **KernelSU 双线回移**：仅回移 APK 实测可靠的通用监控/修复算法，维护单独 ZIP Actions | 不破坏 KSU 已有配置和原有功能 | 等待 APK 验收 |

### G1 页面-功能-权限矩阵（开发前确认，实施中不得丢项）

| 独立模块 | 必须有的功能和 UI 状态 | 使用能力 |
| --- | --- | --- |
| **首页概览** | FCM 观测状态/采样是否新鲜、监控是否运行、最新断联/未知次数、授权状态；各模块入口；**授权仅一个按钮进入授权页** | 普通 Android API，读取自身缓存 |
| **ADB / Root 授权** | 一键打开开发者选项/无线调试；通知栏输入六位码、自动 mDNS 端口、配对成功/失败/断线/重连回读；Root/静态模式；Shell UID 和权限能力说明 | Android NSD + RemoteInput + libadb-android / su，不用无障碍 |
| **FCM 监控** | 持续采样、Socket 可信证据、在线/缺失/未知/过期、连接时间线、Socket 替换/采样 gap、手动受控重连、历史日志 | FGS + Root/ADB Shell + 本地状态文件 |
| **应用守护** | 安装应用扫描/搜索、多选、选中置顶、显示系统应用开关、GMS 必选、`stopped` 检测与受控修复、守护规则 | PackageManager + Root/ADB Shell；**不是无障碍服务列表** |
| **后台保活** | 自动监控/恢复、开机/更新后恢复、DeviceIdle 与 AppOps 回读、忽略电池优化系统页、OriginOS 自启/联动/高耗电设置跳转、任务锁定说明、**隐藏/显示最近任务开关**、通知低干扰开关 | FGS、BootReceiver、AppOps/ADB、系统/厂商页面、AppTask API；全程不用无障碍 |
| **诊断** | 原生 Google FCM Diagnostics 跳转、TCP/网络可达性与权限限制提示、日志导出/复制、采样错误及在线证据 | Shell / Android API |
| **设置 / 关于** | 浅色/深色/跟随系统、快捷磁贴添加与功能说明、GitHub Release 检查与版本日志、系统下载/安装、项目主页、反馈链接 | Android API、GitHub HTTPS、固定签名验签 |

**磁贴和自动恢复特别说明**：磁贴是 `TileService`，自动恢复是已启用的 FCM 前台服务及系统允许的启动广播/恢复路径；**二者都不需要无障碍权限，且不再操作任何第三方无障碍服务**。现有旧无障碍代码从新版移除，不应继续把它当作产品需求。用户的 KSU 应用守护名单是另一套功能，须迁移到“应用守护”。

**未实现项不得展示假开关**；无法通过 ADB 确认 OriginOS 厂商开关时，显示“打开设置/待人工确认”，不可声称已自动授权。实际手机测试只能在上述 G1–G5 交付完成后进行。

## 8. 讨论后才决定的事项（明确记录，不偷偷做）

1. **权限方式**：APK 以无线 ADB 为首选、Root 可选；但在不重启手机的前提下关闭无线调试后持续采集特权 Socket 的方案尚无证据，不作保证。是否引入额外系统级辅助通道，必须先证明必要。
2. **无障碍管理旧代码已确定删除**：`AccessibilityOps.java`、`RestoreService.java` 和旧 `RestoreTileService.java` 当前实现的是**扫描/启停/开机恢复第三方无障碍服务**，与 FCM 无关。重构时移除相关 UI、Service、Receiver 调用、权限与磁贴行为；迁移前避免误删用户已保存的旧配置，并将用于 FCM 的“自动恢复”和“磁贴”作为独立新功能重新实现。**BerryFCM 自身不申请 AccessibilityService，不把其他应用无障碍服务当作应用守护目标。**
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

> 以上是开发约束与验收计划，不表示表内“待实施”功能已经完成。**先交付完整、可操作的页面模块和无线 ADB 通知栏配对流程，再安排用户实机验收**；不能再次要求用户测试尚不能完成配对的 APK。随后完善诊断、正式 Release，最后回移 KSU。

## 11. 2026-11-18 模块化 APK 已合入代码（CI 已通过，待实机检查）

- 首页：`MainActivity` 仅展示 FCM 概览、授权按钮、守护/保活/诊断/设置入口。
- UI：`Ui.java` 共用分组布局与底部导航，在系统状态栏、导航栏开启 edge-to-edge 时使用 WindowInsets 留出安全区域。
- 授权：`AuthActivity`、`AdbPairingService` 通过 Android mDNS 发现配对端口，在通知栏 RemoteInput 输入六位码；Root/静态 ADB 继续可选。
- 监控：`MonitorActivity` 和 `FcmMonitorService` 独立于 UI，含检查、重连请求、失联/未知与采样历史；可按用户开关自动启动。
- 应用守护：`GuardActivity` 使用系统应用包名单搜索/多选，`GuardOps` 检查与受控修复 stopped，不扫描无障碍服务。
- 保活：`BackgroundActivity` 包含后台服务、开机恢复、Android 后台优化、OriginOS 系统设置入口、最近任务卡片隐藏与恢复。
- 诊断：`DiagnosticsActivity` 原生 FCM 页面、TCP 检测和历史分享。
- 设置/更新：`SettingsActivity` 提供主题/磁贴/项目主页，`UpdateActivity` 从 GitHub Releases 检测并验证 APK 签名再交系统安装器。
- 旧无关代码：**已删除** `AccessibilityOps`、`RestoreService`、`RestoreTileService` 及无障碍扫描 Manifest；不再为此申请 `WRITE_SECURE_SETTINGS`。

**源代码和固定签名 APK 已通过 GitHub Actions，不能据此推断无线配对真实成功或后台过夜可靠。正式 Release 仍保持手动发布，须实机验收后开启。**

## 12. 安全与发布补充（2026-11-18）

1. 旧无障碍管理代码、Service 与权限声明已经从 APK 正式源码删除；这是与用户明确目标一致的范围精简，不涉及 KernelSU 应用守护名单。由于旧 APK 的“无障碍勾选”数据存储键与新的守护应用包名列表完全不同，新版不把它们自动转换为守护目标。
2. GMS Doze 手动放行增加操作归属记录，**仅撤销 BerryFCM 自己新加的白名单**；已有系统/其他用户白名单不能随意删除。
3. mDNS 仅接受本机网络接口或 loopback 对应的配对服务，不对局域网其它设备擅自配对。
4. `build-apk.yml` 的 `publish_release` 为 **workflow_dispatch 显式选择才执行**；普通 `push` 只产生固定签名的测试 Artifact，不发布到 Releases。上线前先完成实机验收。
5. 为防后续功能回退，`tests/apk-module-smoke.sh` 检查导航模块、ADB 通知栏配对、WindowInsets、FCM 磁贴和无障碍权限的移除；这属于结构测试，不证明真实设备行为。

## 13. KernelSU 功能差异追平（2026-11-18，已通过 CI）

- 应用守护：除了已合入的应用列表、多选、stopped 检查/恢复、后台约五分钟守护，又增加 90 秒逐次巡检（7 次，每次间隔 15 秒）、主动 GMS 心跳请求；后台只在成功恢复目标时补发心跳，**不把广播返回当成通知送达**。
- 后台网络：GMS Doze 白名单、数据节省模式下的 UID 后台计量网络白名单，支持用户明确开启/撤销，**严格记录由本应用添加的操作及其 UID，不能随意撤销已有系统策略**。
- 诊断：读取 GMS DeviceIdle、DataSaver、后台网络放行和通知 AppOps；这些都是 Shell 结果，无法确认则显示失败而非成功。
- 本次新增能力是在 APK 的 Android 权限约束内复用 KSU 规则，不等于获得 LSPosed/系统进程 Hook 特权；普通 ADB shell 无法执行时必须失败可见。
- **当前状态：APK Actions #44140579529 编译、签名、验签已通过，OriginOS 实机行为待验收。** 等全部模块可操作、签名构建通过后才安排用户第一次新版本功能测试。


## 14. APK 首轮模块化开发完成度与验收边界

- `ecd20cde`：界面导航、状态栏 WindowInsets、独立授权页、mDNS + RemoteInput、监控/应用守护/保活/诊断/设置/更新模块、FCM 磁贴、移除第三方无障碍管理。
- `ed18989f`：修正 mDNS 仅接受本机配对广播、GMS Doze 白名单归属、选中应用即时置顶、`publish_release` 默认关闭、APK 静态结构回归测试。
- `615121d7`：90 秒巡检、GMS 心跳、后台计量网络白名单归属、策略与通知诊断。**APK Actions #44140579529 已通过**，产物 alpha.31。
- 更新接口 Java 兼容性已通过 APK Actions #44141173241。mDNS 失败的通知栏兜底已通过 APK Actions #44141729186。**GitHub Actions 成功不等于目标 OriginOS 的无线配对和后台持久运行成功。**

## 15. 通知栏 mDNS 故障兜底（2026-11-18）

用户明确指出，在无线调试配对页面无法回到 APP 输入六位码。已落实：**发现端口→通知栏六位码；未发现端口→直接在同一通知栏输入 `端口:六位码`**。该设计不要求返回 APP，支持 mDNS 受阻的 OriginOS；两种输入均仅在内存中处理。代码已通过 GitHub Actions #44141729186 的编译/验签，仍需目标手机确认系统通知栏行为。

## 16. 深色控件主题及首页状态刷新（待下一次 CI）

- 参考成熟 Android 应用的原生控件一致性，在 UI 自定义深/浅页面色彩之外，为深色模式增加 `AppThemeDark` 原生 Android 主题，避免 CheckBox、EditText、RadioButton 仍为浅色控件。
- `Ui.java` 在 Activity onCreate 之前根据用户主题或系统暗色状态选择原生主题；WindowInsets 仍负责状态栏和导航栏安全区域。
- `MainActivity` 返回时刷新 Root/ADB 授权模式与最近配对状态，防止首页显示进入授权页之前的过期模式。
- `tests/apk-module-smoke.sh` 增加对原生深色主题选择路径的静态校验；**仍需最后一次 Actions 和手机截图验证**。


## 17. 图标设计定案（2026-11-18）

用户指出旧图标过于花哨，**改用原生自适应极简图标**：单一深蓝色纯色背景、白色 FCM 信号弧线与圆点；不使用渐变、拟物阴影、复杂装饰与文字；Android 13+ 支持单色主题图标。使用 Android Vector Drawable 维护 XML 源码，无须增加图像生成依赖。首次验证以 Actions 资源打包/签名为准，实际桌面最终显示待用户实机截图验收。

## 14. 按 OpenCode AGENTS.md 四项原则进行代码审查（2026-11-18）

**适用范围**：当前 APK Java、Shell、更新与授权流程。此四条是用户给出的代码规范，不是让助手从无依据的理论风险出发无限增加代码。

1. **防过度防御**：检查异常时先判断是否真实可达、是否影响核心能力；未知的罕见并发/厂商行为先记录待验证，不为它们擅自加入会话管理器、兜底层或大量状态机。
2. **入口单次校验**：六位 ADB 码与端口在通知 RemoteInput 边界检验；包名在拼接授权 Shell 命令处校验；下载更新在 GitHub 响应和已保存下载文件进入系统安装器两个**不同信任边界**各自验证。内部对已验证数据尽量不重复判断。
3. **拒绝提前抽象**：`Ui` 由所有页面复用、`PrivilegeAccess` 由多个授权 Shell 场景复用，属于有价值的复用；单次逻辑不再为“未来也许用到”新增工具类。
4. **控制复杂度**：只在 Android API、网络、Shell、文件等**可能实际失败的系统边界**捕获异常；不捕获 `Throwable` 吞掉 `OutOfMemoryError`、链接错误等严重错误，不以“多一层保险”为理由增加嵌套。

### 本次已定位并处理的事实性问题

- `GuardOps.command` 原来按包名排序后最多处理 10 项，超过 10 项名单会静默漏巡检，**连 GMS 都可能排到 10 名之后**。已去掉无需求依据的硬限制，并新增 `tests/GuardOpsSmoke.java`：25 个用户目标及 GMS 均应出现在检查/修复命令里。
- `FcmMonitorService` 原有三处 `catch(Throwable)`：改为 `catch(Exception)`，只捕获预期的 Shell/IO/JSON/Android 运行错误，不阻断系统级严重错误。

### 不应错误删除的安全校验

- `UpdateActivity`：下载文件的包名、版本号、固定证书签名和下载状态是覆盖升级的安全边界，不是“过度防御”；不能为了压缩行数移除。
- `AdbPairingService`：通知码/端口的边界校验与同机 mDNS 地址过滤用于防止错误配对，不是内部重复校验。
- `BackgroundSettings`：执行策略前后回读、仅撤销本应用添加的系统白名单属于**必要的回滚约束**。

### 仍待证据确认，不在本轮额外设计

- `AdbConnectionManager` 是全局连接实例，授权页/守护服务对连接的使用可能相互断开；未有手机上复现证据，先以授权页及后台服务交替操作验证，再决定是否改连接生命周期。不得仅凭“理论并发”增加复杂会话架构。
- `UpdateActivity` GitHub API 文本上限及 `AdbConnectionManager.shell` 读取上限若发生真实截断，再根据日志处理；避免提前引入通用流控层。
- 不为单次使用的简单逻辑新建无意义 helper，不增加与功能无关的设置或权限。

**已通过 CI**：[APK Actions #44143876578](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44143876578)，守护 25+GMS 回归测试、Gradle Debug/Release、固定签名、apksigner 验证及 Artifact 上传全部成功，产物 `BerryFCM-Guardian-v0.1.0-alpha.36`。**OriginOS 配对/保活/应用 stopped 修复效果仍未实机证实。**


## 15. 2026-11-18 OriginOS 实机配对失败与体验修复

**真机证据**：无线 ADB 通知栏配对返回 `SSL library / RSA routines: OPENSSL_internal:internal error`；截图中未授权时首页仍先显示 FCM 采样，授权方式单选纵向展开但四组流程叠在一起。以上均为实机问题，非已通过的 Android 兼容性测试。

**源码复核与修复**：
- `AdbConnectionManager` 旧 `AndroidKeyStore` RSA 身份只允许 `RSA_PKCS1` 签名填充；所用 libadb-android 3.1.1 的 TLSv1.3 配对握手可能要求 RSA-PSS。同时对照 [Google Conscrypt #718](https://github.com/google/conscrypt/issues/718) 相同错误，除 RSA-PSS（保留 PKCS1）外，补充 DIGEST_NONE 与 ENCRYPTION_PADDING_NONE，以满足 Conscrypt 的原始 RSA 操作，使用独立 `berry_fcm_adb_identity_v3`，旧密钥不自动删除。本次是有源码依据的高概率原因，不是已经实机证实的唯一原因；新版需重新配对才能测试。
- ADB 无线连接限定 TLS mDNS 入口，不再把明文 TCP 发现当成无线 ADB；共享 ADB 会话不在每次 `AdbShell.close()` 时断开，避免手动检测与后台采样互相抢连接。后台重连和 Shell 身份均需执行 `id` 实际回读。
- 首页授权卡置于 FCM 卡前，提示未授权/验证过期与最近错误；不能在无有效 Shell 时显示历史 Socket 为当前连接。
- 授权页改为 **横向无线 ADB / Root / ADB TCP / USB** 方式切换，**每次只渲染选中的流程**；电脑 USB 只是教程，不作为 APP 的有效 Shell 模式。首次无线配对直接展示「开始配对 → 系统无线调试 → 通知栏输入」，不预先要求按下「验证 Shell」。配对成功自动连接并验证，后续可手动重连。
- 监控、应用守护与后台启用入口在尚未验证 Shell 时跳转授权中心；仍允许浏览历史数据或通过普通 Android API 做无特权检测。无需长期缓存「永久授权」假状态。
- UI 采用原生紧凑卡片、模块图标、底部图标+名称导航；Material Icons 图标取自 Google 的 Apache-2.0 图标库，启动器为项目独立的 FCM 消息通知图形，替换 Wi-Fi 弧线。应用名和主要 APK 文件名缩短为 **BerryFCM**，不改包名。
- 更新：旧 alpha.36 内置更新器只识别 `BerryFCM-Guardian-...`，新安装包改为 `BerryFCM-v...`。为保证旧版本第一次在线升级，手动 GitHub prerelease 同时附两个文件名的**同一个固定签名 APK**；新更新器优先识别短名称，也保留旧名称兼容。无正式 Release 时「检查更新」仍不会找到 Actions Artifact。不能静默安装。

**下一轮验收**：① Actions 编译和固定签名成功；② OriginOS 点击配对通知并验证 `uid=2000(shell)`；③ 隐藏无线调试再返回检查过期/错误是否真实；④ 开启监控读取 GMS Socket，比较 Root 与 ADB 差异；⑤ 通过正式手动 prerelease 验证现有 alpha.36 → BerryFCM 短名称包的在线覆盖升级。CI 通过仅证明编译和模拟，不代表这些手机行为已通过。

**补充源码证据**：Shizuku 的 `AdbKey.kt` 使用 AndroidKeyStore AES 加密保存软件 RSA 私钥，避免直接将 AndroidKeyStore 私钥交给 TLS；Conscrypt 官方 #718 报告了几乎相同的 `RSA routines:OPENSSL_internal:internal error`，维护者确认 TLS 签名底层可能使用 `RSA/ECB/NoPadding`，并给出在密钥生成中添加 `ENCRYPTION_PADDING_NONE` 的修复。当前优先沿用现有 libadb-android/AndroidKeyStore 结构，增加官方确认的最小参数修复并生成 v3 新身份；若 OriginOS 实机仍失败，再评估 Shizuku 的 AES-GCM + 软件 RSA 私钥方案，不能先断言成功。旧身份保留不删除。

**UI 状态可信度补充**：Shell 最近验证状态不能只以本地墙钟判断；缓存记录现在同时保存本次开机的 `elapsedRealtime`，只在两种时间增量一致且 120 秒内时显示为近期验证通过。首页授权错误和监控错误采用短摘要，原始监控事件保留于详情，不把 TLS 长异常堆满首页。实际 USB/Root/Wireless 连接仍以当次 `id` 和后续 Shell 命令执行为准。
 

## 18. 2026-11-18 alpha.39 后续静态复核：授权状态与后台模式切换

本轮在 `alpha.39` 已通过签名构建但尚未拿到新 APK 的 OriginOS 实机反馈时，继续针对可从代码证实的路径错误做最小修正：

- **特权会话与模式一致性**：原 `FcmMonitorService` 使用服务内 `adbConnected` 缓存，仅在部分安装/错误路径清除；当用户在授权页切换无线 ADB / 静态 TCP，或配对服务重新连接时，单靠这个标记可能绕过当前模式检查。改为每次 Shell 操作先调用共享 `AdbConnectionManager.autoConnectLocal` 或 `connectStatic`，由该 manager 按协议/主机/端口和 `isConnected()` 复用连接；不创建第二套会话管理器。必要时 `id` 回读；错误则清除验证记录并断连。
- **避免配对码重投递**：原 `AdbPairingService` 对含 RemoteInput 六位码的 `ACTION_REPLY` 返回 `START_REDELIVER_INTENT`；进程异常结束后系统可能再次投递一次性配对请求。统一改为 `START_NOT_STICKY`，空 Intent 直接停止，不自动重复处理敏感输入。
- **重新配对时旧状态失效**：重新开启配对监听先清除 Shell 近期验证缓存；尚未完成配对不能沿用 120 秒内的旧“已授权”状态。配对成功仍需执行 `id` 验证。
- **图标一致**：FCM 前台监控通知、Quick Settings 添加弹框及磁贴统一使用已有 `ic_bf_fcm`；无线 ADB 配对通知使用已有 `ic_bf_auth`。不新增依赖或与项目目标无关的功能。
- **静态回归**：`tests/apk-module-smoke.sh` 检查不再存在 `START_REDELIVER_INTENT` 和服务内 `adbConnected` 缓存，并核验授权失效与图标引用。
- **发布边界**：本轮以 GitHub Actions 编译/签名测试为准；无 Android 17 真机新证据时不能声称 TLS 真实握手已通过。正式 GitHub prerelease 仍需手动触发 `publish_release=true`，不擅自发布。

**成本与取舍**：每次执行会调用轻量连接状态检查，manager 可复用已建立的 ADB 会话；比可能使用错误 Shell 通道的缓存标记更可靠。不改变监控计划周期，不修改 Root/KSU 核心规则。


## 19. 当前开发进度与剩余工作（2026-11-18）

**按代码覆盖范围的主观估算**：APK 约 75% 完成。不是功能验收通过率，也不是时间进度。G0 文档、G1 主模块结构、G5 GitHub 固定签名 CI 基本达成；G2 授权和 G3 监测/守护的代码已经落地但有设备权限边界；G4 在线更新逻辑与手动 Release 工作流已就绪但还没有正式 Release；G6 由用户负责实机验证，G7 等可靠算法实测通过后回移 KernelSU。

| 当前模块 | 源码状态 | 待补充或待实机验证 |
|---|---|---|
| 首次授权 / Root / 无线 ADB / 静态端口 | 有完整 UI、通知栏 RemoteInput、mDNS 和 Shell UID 读回 | OriginOS TLS 握手、连接权限维持、通知栏可用性待实测 |
| FCM Socket 观察、状态机、事件和自动/手动重连 | 有 Shell 逻辑、15 秒前台采样、gap 检测和日志 | 目标 ROM 的 UID/Socket 元信息可见性、短断联漏检需与原生 Diagnostics 对照 |
| 应用 stopped 守护、搜索、多选、已选置顶 | 有检查/修复、90 秒手动巡检、后台约 5 分钟巡检、心跳 | ADB Shell 的 unstop 权限、长列表响应和修复后实际状态待实测 |
| 后台运行与厂商设置 | 有 FGS、开机/更新广播、系统电池页、OriginOS 设置入口、任务隐藏、策略设置 | 系统强停不可保活；厂商私有设置只引导，不报假成功；需进一步完善逐项确认 |
| 基础网络诊断与系统策略诊断 | 有 GMS Diagnostics 跳转、Shell 策略检查；本轮修复为无 Root/ADB 使用 Java Socket 检查 mtalk TCP 端口 | TCP 通断不代表 GMS 用户态连接或通知送达，三端口可达性尚待真实网络实测 |
| 更新与交付 | 有固定签名、版本递增、Release 查询/下载/证书校验和系统安装器入口 | GitHub Releases 列表仍为空，正式灰度和旧版覆盖升级尚未闭环 |
| KernelSU | 既有 KSU 模块/构建独立 | 等 APK 核心算法可靠后回移，共享逻辑前需差异测试 |

**本轮具体开发（非手机验收）**：
1. 将 `DiagnosticsActivity.checkNetwork` 由 `PrivilegeAccess.open` / Shell `toybox nc` 改为 Android App 自身 Java Socket 连接；无需 Root / ADB，3 秒连接超时（DNS 解析另计），结果只说明当前 APP 网络路径。
2. `BackgroundSettings.optimize` 改成 **先读原值、记录所有权、写入、回读**。Android AppOps 原值无法解析时不写入，不再盲目把未知状态当成功；`BackgroundActivity` 增加对应的安全撤销入口和标准权限状态摘要。
3. 对于新代码添加的自己 Doze 白名单及 RUN_*_IN_BACKGROUND AppOps，在记录的原值基础上允许恢复。旧版没有回滚记录的设置不能安全推测，明确不自动删除；如果外部已改动 AppOps，不覆盖外部值。
4. 仍不强杀 GMS、不擅自修改 VPN/DNS/代理、不新增无障碍或新运行时权限、不改变 APK 包名及固定签名。Github Actions 编译/签名结果应单独写入 HANDOFF。

**开发后续顺序**：P0 授权状态/监测缺失时不误报 + Shell 权限失败可诊断；P1 后台策略与应用守护状态逐项回读；P1 信息层级与交互；P2 实机接受后发布签名 Release；P2 APK 稳定后 KSU 算法回移。无实机证据时不将 TLS 配对标记为通过。

**OriginOS 入口复核补充**：原界面“自启动”和“关联启动”两个按钮实际上调用同一个 `BackgroundSettings.autostart`，并没有分别到达两个不同页面。检索 [traccar](https://github.com/traccar/traccar-client-android) 等开源 Android 项目仅找到可参考的 Vivo 自启动管理组件，未证实当前 OriginOS 有单独可达的关联启动 Activity。因此合并为一个“打开 OriginOS 启动管理”按钮并增加明确的手工检查说明；后台高耗电按钮真实目的地是应用信息页，按实际目的改名，不声称直达电池开关。

**本轮 CI 完成状态**：本节新增 Java Socket 诊断与策略回滚代码已通过 [APK Actions #44197295228（alpha.41）](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44197295228)；OriginOS 入口修正及全部累计代码再次通过 [APK Actions #44197684568（alpha.42）](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44197684568)。以上为源码、模拟和签名验收，不是设备层验收。

## 20. 界面与工作流集中改版（2026-11-18，合并一次构建）

用户再次反馈的实际体验问题：已配对仍频繁显示授权与“验证”；首页第一张卡一直为授权、看不到 FCM 主体；监控未产生采样却没有执行反馈；原启动图标类似短信；选择守护应用时一进入就扫描并显示所有系统包；配对按钮和无线调试跳转被拆成两次操作。

设计参考：
- [Shizuku](https://github.com/RikkaApps/Shizuku)：首页依连接/授权状态显隐操作，首次配对使用系统开发者选项和通知栏，Root/ADB 路径分流
- [GKD](https://github.com/gkd-kit/gkd)：首页关键状态为主、异常按需显示、非频繁操作下沉独立页面和折叠区域
- [Hail](https://github.com/aistra0528/Hail)、[Shelter](https://github.com/PeterCxy/Shelter)：应用选择与系统应用过滤均需考虑系统组件的实际可选性，不把 QUERY_ALL_PACKAGES 枚举结果直接等同于用户可守护应用

**本批次实施**：
1. `Ui` 公共卡片边界/字体层级/图标导航统一，底部五项清晰选中，授权、诊断、更新等二级页面使用返回键；避免重复底栏。添加最小折叠容器用于技术字段和高级操作，不引入新的 Compose/Material 依赖。
2. 首页顺序改为**FCM 监控为首、快捷功能其次**，必要时才插入“需要处理”的授权/异常卡。正常情况下无“无线调试已连接”常驻卡。采样显示最后成功时间、最近尝试时间和最近错误，**不使用 120 秒 Shell 验证记录判断配对失效**。
3. `AppPrefs` 新增持久的 `adb_paired` 标志（兼容旧版已有“配对成功”文本），只在首次成功配对时写入，不随 Shell 120 秒检查结果过期。是否真正连通/可用由实际 Shell 调用与采样结果决定。
4. `AdbPairingService` 配对成功后启动 FCM 前台监控，后台自动尝试连接/验证 UID/持续采样，不再要求用户手点“验证当前 Shell”。首次配对单入口启动通知+mDNS 搜索并自动尝试打开系统无线调试页面；参考 Shizuku 的系统 Developer Options hint，直达组件不可用则退回开发者选项。需要系统通知权限时请求并在用户授权后继续。**无线调试被关闭时不能静默开启，只能引导用户打开；配对身份仍保留。**
5. `MonitorActivity` 默认显示状态/最近六条事件；技术证据与更多事件折叠，明确记录采样失败/启动中；`SettingsActivity` 修复 RadioGroup 无 ID 导致的主题切换可能失效，横向三项、版本更新逐步显隐。
6. `GuardActivity` 默认**只显示所选守护应用的名称与实际图标、最近一次检查结果**（固定核心 GMS 可见但不可取消）。按“选择应用”才扫描加载候选，勾选立即保存并移动到已选区。系统应用默认隐藏，开启筛选才列出符合静态标准的候选：已安装且启用、非持久、普通应用 UID、非本应用，并且系统应用须存在可启动的用户入口。旧版已有但失效/不合条件的所选包仍在已选区提示，可主动取消而不是隐藏不可恢复。**这只是保守候选过滤，最终权限/可操作性仍必须由 `cmd package unstop` 实际执行和 stopped 状态回读确认**。
7. `BackgroundActivity` 默认重要授权/系统入口，危险性低但不常用的 Doze/AppOps/GMS 改动和最近任务配置收进高级折叠层。
8. 图标替换为本项目原创的深蓝盾牌+绿色 FCM 连接心跳路径；桌面自适应/单色/通知及导航使用同一识别语言，不再采用短信气泡或 Wi-Fi 弧线，避免把 GMS 官方标识当成项目商标。
9. 静态 smoke 测试增加配对成功自动监控、已配对持久状态、首页条件卡、守护候选懒加载和系统应用筛选；**整批源码只合并一次触发 GitHub Actions 编译**，不逐个控件触发 CI，也不在没实机验证前发布正式 Release。

**验收边界**：CI 只保证编译、签名和模拟行为；OriginOS 真机的自动跳转具体落点、mDNS 发现、TLS 连接、GMS Socket 采样、前台服务存活仍由用户验证。已配对一般不需重新输入码，但系统撤销凭据/清空开发者配对设备时依然需重新配对。

## 2026-11-18 实机纠错：GMS MCS 原生状态优先（未合并、未编译）

alpha.43 的用户截图证明 TCP Socket 观测不能代表 FCM MCS 应用层状态。Google FCM Diagnostics 在 Wi-Fi 无 GFW 限制的环境下显示 Connecting、alt7-mtalk.google.com:5228、Time disconnected 55:15、ERR_IO_RST_HB；此前约 06:46 起长时间重连失败。后续 08:05 起 Wi-Fi/Cell 交替是用户出门造成的网络变化，不能混入之前的稳定网络异常分析。心跳调度延迟在截图中为 68～143 秒量级，仅为诊断线索，不确定根因。

用户在 Termux Root 下验证了 `dumpsys -t 5 activity service com.google.android.gms/.gcm.GcmService`：真实返回 `connected=mtalk.google.com/... ,port=5228`（实际逗号之前无空格）、`connectTime=03:22/00:00` 以及历史 Close err、Connecting 事件。因此主状态改读 GMS MCS 服务当前块，参考 [fcm-keep-alive](https://github.com/AndyYuenOk/fcm-keep-alive) 的 dumpsys 入口及公开旧版 GMS 字段结构。连接、重连、pending 未连接、未知区分；TCP ESTAB 只能作为不可读取 GMS 时的辅助证据，不能升级为已连接。原生时长以快照为准；当前状态过期或失败时不继续外推。

仅提取获准的状态字段和 MCS 连接关闭、成功、失败、WiFi/Cell 尝试、Heartbeat alarm delay 等事件，不持久化完整 dumpsys/DeviceID/消息会计/应用数据。事件按原始时间戳和内容去重，保留最近 600 条；关闭事件数不等同于整体独立断联次数。原来 TCP 统计的文件保留，使用新 mcs-* 文件避免混算。自动重连以 GMS 原生未连接状态及原生时长为主，继续遵守间隔和 GMS stopped 回读；不因单次网络切换立即发广播，不自动改网络或强杀 GMS。

APK 新增独立原生状态模拟回归脚本：已登录、TCP 已建立但 MCS Connecting、pending、旧历史不可当作当前连接、权限拒绝、重复历史、限频重连。KernelSU 原算法及测试保持不变，等待 APK 验收后迁移。当前只在开发分支暂存，不触发 main 的 CI，不发布 Release。

尚未验证：目标设备 ADB uid=2000 是否同样允许读取 GcmService（现有证据为 Root）；GMS ROM 版本差异、原生历史缓冲限制、休眠中实际恢复行为。后续还需执行脚本回归及统一 APK 构建。本次不把根因推测标为结论。

### 原生 MCS 恢复机制与 ADB 命令执行（开发分支，2026-11-18）

源码实现提交：[3e43c1a](https://github.com/jarod360/Berry-FCM-OS7/commit/3e43c1a2b39947b93b63f5748753ff0e7d809abc)，仍位于 `dev/native-fcm-state`，未合并 main，未构建 APK 或 KernelSU 安装包。

- 原生 MCS 状态为唯一在线判据；区分 `permission_denied`、`timeout`、`service_unavailable`、`gms_stopped`、`empty` 等具体读取异常。部分 dumpsys 超时不接受为当前在线证据。
- 默认允许自动恢复（可单独关闭而继续监测）。应用默认网络变化后观察 45 秒，无活动网络或网络信息不可读时暂停自动干预；应用默认网络只是保守门槛，不证明 GMS 具体路由。原生未连接达到 120 秒、持续观测至少 15 秒后才请求 GCM_RECONNECT；连续重试间隔为 5、10、15 分钟，上限 15 分钟，恢复后清除连续重试级别。手动请求也先读取原生状态并作 60 秒防重复限制。
- 广播退出码及拒绝/异常文本共同判断请求是否得到提交确认；只有后续原生状态恢复才记录观察到恢复，不能证明广播造成恢复。
- ADB 命令改为直接命令流、随机完成标记、45 秒等待上限及取消处理，规避交互 Shell 回显、EOF 时序与无限阻塞；保留调用方原有结果解析约定。脚本安装先写临时文件并 `sh -n`，成功后替换并校验安装完成标记；合并重复采样和重连请求。
- 首页/监控页显示原生读取原因、实际状态、限频策略；原生事件和 BerryFCM 恢复/采样事件分别保存、显示。诊断分享包含两类事件，策略只读检查补充当前 Doze 深/浅状态及 GMS 后台 AppOps。
- 边界：不强杀 GMS、不修改 DNS/代理/路由、不擅自改变权限或电池策略；不新增运行权限。GMS 真实恢复取决于设备和系统，禁止把 CI 模拟当成设备实测。
- **专项回归结果**：[Native FCM shell verification #44207074830](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44207074830) 全部成功，约 11 秒；实际测试基于该开发源码提交加独立验证工作流。检查 APK Shell 语法、原生状态与历史去重、网络等待、自动恢复关闭、连续重试、权限/超时、手动重连保护，并执行既有 KernelSU 脚本语法与生命周期测试。未执行 Gradle、签名、APK 或 KSU 打包，Java/Android 编译和实机验收仍待下一批集中构建。
- 下一阶段优先核验目标机 adb shell uid=2000 的 GMS dump 权限、真实 GCM_RECONNECT 效果和长时间休眠后采样连续性。只有有证据再考虑 OriginOS 私有策略，不凭心跳延迟直接认定根因。

### 监控后台连续性与连接切换修正（2026-11-18）

本批次继续参考 Shizuku 的本机 ADB mDNS 发现、Termux 的 wake lock 使用方式。不同于 Termux 持续唤醒方案，BerryFCM 仅在实际采样运行期间使用超时上限 90 秒的 PARTIAL_WAKE_LOCK，并在 finally 释放；新增正常权限 WAKE_LOCK，不持有常驻唤醒锁、不请求精确闹钟、不保证系统深睡期间 15 秒必然执行。

- `FcmMonitorService` 记录每次服务运行 session、连接配置 key、单调时间采样锚点。旧 session、换模式/静态端点、超过 90 秒等情况下，不把历史快照当当前连接。开关偏好与实际运行状态分离；首页、监控、后台设置、磁贴使用实际服务状态。
- 已启用任务在用户重新打开 APP 且服务未运行/采样过期时请求恢复；运行中的服务注册默认网络变化、屏幕唤醒/解锁、退出 device idle 的事件触发，合并重复检查，onDestroy 注销。前台服务按系统 START_STICKY 及已有开机/更新接收器恢复；不能绕过用户强行停止或厂商限制。
- 采样/连接失败后 15/30/60/120 秒退避；手动采样和网络恢复触发可提前检查。实际原生 FCM 自动恢复仍遵守前一批的 45 秒网络稳定、120 秒断联及 5/10/15 分钟限频策略。
- Root Shell 统一使用有界进程、45 秒超时、身份检查、输出上限与临时文件清理。ADB 保留已配对 v3 密钥；已配对的无线调试失败不自动重新配对，传统 TCP 首次授权仍允许正常系统公钥确认。
- 授权标签仅浏览候选。Root/TCP 或已配对无线候选须实际验证通过才提交新连接配置，失败保留旧设置。手动操作与监控的 ADB 连接+命令按共享 manager 串行，避免切换时命令误用其他 transport。
- Java/Android 编译、真机休眠与广播效果尚待验证。本批与此前 native MCS 检测和恢复机制合并为一轮 APK 构建；不发布 Release，不更新 KernelSU。

### alpha.44 集中构建与后续 GMS 兼容性/运行环境诊断

- alpha.44 的源码基线为 `deba445363de958fb10e652e88b1b3f06decdc5f`。独立构建分支 `build/native-fcm-batch` 的提交 `4bbc19f` 仅在该分支增加 APK 构建触发条件，未更新 main；[Actions #44208154338](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44208154338) 已成功。固定签名 Artifact `BerryFCM-v0.1.0-alpha.44`（ID 14384467571，压缩文件 7374465 字节）；原生 Shell 回归、模块检查、Android 编译、固定签名、APK 验签、上传均通过。无 Release、无 KSU 打包。真机验证尚未完成，不宣布实际恢复成功。
- 构建期间继续推进 `dev/native-fcm-state`：提交 [1543b79](https://github.com/jarod360/Berry-FCM-OS7/commit/1543b79e7ae37d5935f021503c5696a2ac807723) 支持 GMS 标题存在差异时，通过精确当前态字段与有效 connectTime/disconnectTime 或 streamId 的相互佐证进行解析。无标题且只有孤立的 connected= 字段不视为可靠；拒绝和当前状态冲突的时长、历史列表中的伪当前状态以及不完整 dump。增加对应模拟测试与服务会话、唤醒锁、授权切换的静态断言。[仅 Shell/模块检查 #44208517040](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44208517040) 已全部通过，不打包 APK。
- 再次继续开发：在原有采样结果内记录本应用默认网络 transport（WiFi/Cell/VPN/Ethernet）、Android 网络验证标记、屏幕状态、Doze 及省电模式、GMS Doze 白名单读取结果；变化时写入最近 80 条环境事件，在监控页高级区域和分享诊断中展示。只读取公开 Android 状态、不记录 SSID、账户、地址等附加标识，不新增网络探测。网络 capability 中 transport 类型变化也触发合并检查。应用默认网络不代表 GMS UID 实际路由、Android validated 也不代表 FCM 可达。采样环境只是相关线索，不证明断联原因。
- **版本边界**：最后两个开发批次不包含于 alpha.44。GMS 标题兼容性有 Shell/模块回归证据；新加入的环境事件 Java 代码仍需下一次集中 Android 编译和实机测试。保持独立开发分支、不逐项重打包；未迁移到 KSU。

### 无线调试常驻通知与独立 Shell 服务（下一批开发）

用户实机反馈：BerryFCM 启用后 Android 系统一直显示“已连接到无线调试”。原因是当前内置 ADB 监控在执行完成后仍然保持 TLS transport，并不是 FCM 通知本身。不能将关闭系统通知或取消配对当作修复。

- 内置无线 ADB / ADB TCP 的配对与执行连接分开；`AdbConnectionManager.executeOnce` 串行完成连接、实时校验 uid=2000、执行和 finally 断开。配对本身也在 finally 关闭连接，保留原 AndroidKeyStore v3 身份。监控、手动策略操作、授权验证共用此入口；没有删除或重新生成已配对密钥。直接 ADB 模式仍需在采样时连接，因此**可能短暂显示系统无线调试通知**，不能声称彻底无通知。
- 增加可选的 [Shizuku 官方 UserService](https://github.com/RikkaApps/Shizuku-API) 实现，固定 SDK 13.1.5，需要用户已安装并启动 Shizuku v13+（或兼容 Sui）并明确授权 BerryFCM。使用官方受权限保护的 ShizukuProvider、Binder AIDL 和进程级 UserService，不开放任意 TCP/HTTP Shell 服务；服务端校验调用者就是 BerryFCM 应用 UID，命令 45 秒超时、输出 200 KB 上限，并清理临时文件。不使用已准备废弃的 newProcess API。
- 授权页新增 Shizuku 选项，确认实际 uid=0/2000 后才切换运行方式；后台采样、守护与策略通过同一 UserService；原生 Root、无线 ADB、ADB TCP 保留。Shizuku 服务未启动或权限撤销时显示真实异常，不偷偷切换权限方式。Root 模式仍是已 Root 手机无 ADB 通知的最直接路径。
- **边界**：Shizuku 选项复用已安装的独立权限服务，并非已经在 BerryFCM 内独立实现 Shizuku 服务端。无外部服务的内置无线 ADB 仍是短连接；要进一步实现首次配对后完全脱离 ADB 的自包含 daemon，需要单独验证 Android app_process / Binder 启动、授权与回收的兼容性。Shizuku 通过 ADB 启动时重启后也可能需重新启动服务，不能保证自行跨重启保持。
- 采用现有固定签名与 APK 包名；本次只更新开发分支和源码回归规则，不触发 APK 构建或发布。待下一批集中编译确认 AIDL、SDK 集成及实机通知行为。

### 2026-11-18：采用 GKD 同款 Priv Kit 独立服务（开发分支）

- 用户明确纠正：正常监控期间不能反复连接无线 ADB，即使每次执行后断开、短暂弹出系统通知，也不符合目标。
- 依据 [GKD 的依赖配置](https://github.com/gkd-kit/gkd/blob/main/gradle/libs.versions.toml) 与 [Priv Kit v0.20.0](https://github.com/priv-kit/priv-kit/tree/v0.20.0)，接入正式 Maven Central `io.github.priv-kit:priv-core:0.20.0`。不再自制 app_process 服务、不通过 socket 暴露执行接口。
- 保留原有 AndroidKeyStore v3 配对身份，通过现有 ADB executeOnce 启动 Priv Kit 正式 nativeStarterCommand，之后断开 ADB。等待真实 UID 2000 Binder，日常采样、守护、策略、手动命令及授权验证均经官方 startCommand/awaitResult；命令超时 45 秒，输出有界。禁止自动 fallback 到 ADB 短连接执行普通命令。
- 服务失效时先等待现有 Binder 回传。自动启动失败后不逐次采样重开 ADB；之前启动成功但服务随后死亡时，至少间隔 5 分钟才允许自动启动；显式启动及成功的新配对可恢复尝试。Binder 存活与配对保存是两个不同状态。重新启动服务不等于重新配对。系统重启/服务真的退出可能仍需要一次 ADB 启动，不能声称所有场景永远没有通知。
- 保留独立 Root、ADB TCP 和可选 Shizuku 路径；不要求安装 Shizuku。切换到其他方式后停止本项目的 Priv Kit 服务。
- 构建配置与上游匹配：compileSdk 37、AGP 9.4.1、Gradle 9.6.1、JDK 21；targetSdk 35、minSdk 26、applicationId 及固定签名保持不变。原生启动器兼容 Android 8/9 的提取式打包。通过 manifest 合并 remove 排除 WRITE_SECURE_SETTINGS，CI 检查最终 APK。
- 本批代码完成后仍需实际 Android 编译、依赖解析及真机验证，包括首次启动后 ADB 断开、关无线调试后 Binder 命令、网络切换、服务死亡、旧版本覆盖升级以及权限范围。不发布 Release；不迁移到 KSU。


### 独立服务状态显示
首页与监控页直接检查 UID 2000 Binder 是否存活。服务未运行时不会呈现历史 FCM 在线快照为实时状态；已配对用户只显示启动独立服务的入口，不要求重新授权。

构建记录：alpha.45（#44228012040）在 Android 工具安装阶段失败，尚未编译，旧的 `platforms;android-37` 包 ID 不存在。Flutter 的 SDK 配置和 Android API 37.0 工具要求确认正式包 ID 为 `platforms;android-37.0`。CI 已改为该包并固定 Build Tools 37.0.0；签名配置不变。

alpha.46（#44228259741）已通过 API 37.0 SDK 安装、依赖解析、原生脚本及模块回归；Java 编译仅报告可选 Shizuku BerryShellUserService.java 的隐藏 API `ServiceSpecificException` 不可见。更换为 Binder 支持传输的公开 `IllegalStateException`，不变更权限范围、配对密钥或 Priv Kit 运行路径。
### alpha.47 已构建成功；后续独立服务重连生命周期

- [alpha.47 #44228547705](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44228547705) 已完整成功：SDK 37.0、Android 编译、原生脚本与模块测试、合并权限清单检查、固定签名、验签及 Artifact 14394306272（8,158,195 字节 ZIP）。测试 APK 源码为 ccf9c9f，构建提交 23fc50c；开发分支随后修改的文档与本节生命周期改动不包含于这个包。未发布 Release，未更新 main/KSU。
- 参考 [GKD OwnerLifecycle](https://github.com/gkd-kit/gkd/blob/main/gkd-app/src/androidMain/kotlin/li/gkd/app/priv/PrivilegeOwnerLifecycle.kt) 与 Priv Kit v0.20.0 PrivilegeConfig：启动 APP 时初始化回传监听，设独立服务在 owner 死亡后的**被动**接续窗口为一小时。activeReconnectOnOwnerDeath=false，不使用特权服务主动拉起 APP，不承诺进程常驻。现有服务回传等待从 1.5 秒延至 5 秒。
- ADB 启动之前同步持久化单调时间、墙钟和 boot_count，避免应用进程被回收导致五分钟冷却失效；同一次系统启动优先单调时间，boot_count 不可读时采用保守时间判断。原 Binder 优先，失败仍禁止逐次命令回退；显式重新启动服务不受自动冷却限制，成功新配对可清除启动记录。
- 配对通知明确区分配对成功、请求启动监控、系统拒绝启动监控；不再把 startForegroundService 请求当成运行成功。保留配对身份，打开 APP 可恢复监控。
- 增加纯 Java 冷却测试覆盖首次启动、应用重启、系统重启、时钟变化及 boot_count 不可读。此批源码尚未进入 alpha.47；依旧按集中修改再编译的原则，先执行轻量专项回归。


独立服务诊断：现有分享日志增加 Binder UID/PID 与 APP PID、累计 ADB 启动尝试、最新启动结果和冷却剩余。只读现有信息，不触发 ADB、启动或采样；计数在实际尝试之前记录，不代表成功连接。用于验证普通采样是否仍依赖 ADB。
### 2026-11-18 alpha.47 真机：配对成功但独立服务未启动
用户截图确认 prefs 已显示已保存配对身份；连接授权却提示“未发现本机无线 ADB 服务”。此错误来自旧版 libadb 的 connectTls mDNS 发现路径，发生在 Priv Kit native starter 执行之前，并非已证实的密钥错误。尚不能据此认定 ROM 的具体 mDNS 故障原因。
- 保留原 AndroidKeyStore v3 身份，连接端口发现改用 GKD 同款 Priv Kit v0.20.0 的公开 createAdbManager(...).discoverConnectPort(15000)。只使用其发现功能，不切换到 Priv Kit 自己的 ADB 密钥。连接本机 loopback、校验 uid=2000、启动独立服务后 finally 关闭 ADB；不扫描 LAN 端口、不恢复逐次 ADB 采样。
- 新增连接端口（不是配对端口）一次性手动备用入口，使用无线调试主页所显示的端口；不永久缓存会变化的端口。从“打开无线调试并自动启动”返回设置后尝试一次启动，无需再次配对。无本地配对标记时可显式检查已有身份，真实 ADB 认证和 UID 验证成功后保存配对标记；新建密钥必须清除旧标记。不同应用自己的 ADB 密钥不能共享配对。
- 配对通知等待实际 Priv Kit 握手及 uid 回读成功才报告独立服务运行，并区分前台监控请求是否提交；后续启动失败保留已配对身份，停止 mDNS 回调覆盖配对状态和重复通知输入。候选连接失败不强制切换已有模式。
- 仍需 OriginOS 实测 SDK 37 现代 NSD 是否能发现本机连接端口、loopback 是否可达以及 native starter 回传；不保证仅凭源码或编译成功就能启动。若发现失败，先读取系统无线调试主页端口进行一次启动。

### 实机失败后的授权重构：先发现配对服务，再输入码

用户已重新配对三次，但 alpha.49 独立服务仍未启动。此前的 CI 仅验证编译与脚本等，不能作为真机成功记录。本轮完整使用 Priv Kit v0.20.0 官方 manager 的 discoverPairingPort、pair、checkPairing 与 Privilege.startAdb，同一设备名/密钥，不再借用旧 libadb 的无线 TLS 或外部启动方式；旧 libadb 仅保留显式静态 TCP。参考 priv-ui 的 PrivilegeUiAdbPairingActions.kt：6 秒发现，500ms 重试/端口监听，找到真实本机端口才允许通知输入六位码，关闭服务即收回输入；PairingGate 防止过期及重复回复。正常流程无需输入端口。新身份首次迁移确实需一次配对，不能继承旧标记。checkPairing 遇 UNPAIRED 清除历史，UNAVAILABLE/ERROR 显示待验证。Binder 与 UID 回读成功才开始监控；启动日志包含发现、认证及握手阶段。没有手机连接权限，不能宣布真机测试成功。本批只在开发分支提交，未打包。
