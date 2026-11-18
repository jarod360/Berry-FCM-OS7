# Berry-FCM-OS7 开发进度与接续交接

> 最近整理：2026-11-18。**唯一产品基线：** [PRODUCT_PLAN.md](PRODUCT_PLAN.md)。本文件只记录当前真实状态、构建证据、未验证项目和接续动作。全部代码和文档在 GitHub；不跳转 Work。用户电脑文件仅允许存放 D:\Codex，未获许可不触碰 C 盘。

## 1. 不可变更的产品目标

- **APK 优先**：完整迁移 KernelSU 版有价值的 FCM Socket 监控/重连、事件历史、应用 stopped 守护、GMS Doze/后台网络策略与诊断。APK 稳定后再将可靠改进回移 KernelSU，**两条线保留**。
- **先做完整可操作的 UI 和功能，再请求用户测试**。首页只显示概览和授权入口；独立授权、FCM 监控、应用守护、后台保活、诊断、设置、在线更新模块。不得恢复旧的堆叠长表单。
- **无线 ADB 配对码必须从系统通知栏 RemoteInput 输入**，用户在 OriginOS 的“无线调试”界面下拉通知提交，不能切回首页输入。自动 mDNS 发现端口，手动端口仅备用。
- Android API 可做的不用 ADB，ADB/Root 可做的不用无障碍；**不管理其它应用的无障碍服务，也不申请 AccessibilityService**。FGS 自动恢复和 FCM Tile 是正常 Android 服务，不需要无障碍。
- 标准 Android 策略尽力通过 ADB/Root 配置并回读；OriginOS 厂商自启、关联启动、高耗电、任务锁定如无可信私有 API，只能跳转系统页面手动授权，**不能伪造状态开关**。
- Android 可能停止普通 APK，ADB Shell 配对身份不等于永久权限。Socket 缺失/不可读/样本中断要区分；TCP 连接不等于 FCM 已认证/推送必达。任何重连广播不等于实际恢复。
- 新安装包必须使用现有固定签名证书，GitHub Actions 编译、签名、验签和制品上传。GitHub Release 升级从**手动批准的正式 prerelease**获取，不自动发布半成品。

## 2. 已合入的新版 APK 模块

首个模块化重构提交：[ecd20cde](https://github.com/jarod360/Berry-FCM-OS7/commit/ecd20cdefd81854c432e8565783ab3cc0f2abd7d)。第二次增强：[ed18989f](https://github.com/jarod360/Berry-FCM-OS7/commit/ed18989fb216ea9118ca8a7a2503799000f13c86)。

| 页面 / 组件 | 已合入代码 | 关键约束 |
| --- | --- | --- |
| 首页 \`MainActivity\`, 共用 \`Ui\` | 监控概览、授权单按钮、各模块导航、WindowInsets 状态栏/导航栏安全区域、主题底色 | 首页无配对码输入/无障碍服务列表 |
| \`AuthActivity\` + \`AdbPairingService\` | Root / 无线 ADB / 静态端口、通知栏 RemoteInput 六位码、mDNS 自动发现、配对状态、权限申请 | 必须在目标 OriginOS 真机验证配对是否可用；不会保存配对码 |
| \`FcmMonitorService\` + \`MonitorActivity\` | 前台服务 15s 采样目标、事件历史、未知/过期/断联区分、受控手动重连、服务退出后按系统规则尽力恢复 | 启动和 Shell 失效均需显示诊断；不宣称永久保活 |
| \`GuardActivity\` + \`GuardOps\` | 包名/名称搜索、系统应用过滤、已选置顶、多选、GMS 核心目标、stopped 检测/受控修复、后台约 5 分钟检查 | 与旧第三方无障碍服务扫描完全不同 |
| \`BackgroundActivity\` + \`BackgroundSettings\` | 监控/守护/开机恢复开关、应用电池豁免、标准 Android AppOps、GMS Doze、OriginOS 厂商页面、最近任务隐藏恢复 | 厂商私有开关跳页手动；已存在的 GMS 白名单不能冒充自己添加 |
| \`DiagnosticsActivity\` | Google 原生 FCM Diagnostics、mtalk TCP 可达性、监控事件分享 | Root/ADB 测试路由和 GMS UID 路由可能不同 |
| \`SettingsActivity\`, \`UpdateActivity\` | 跟随系统/浅/深主题、FCM 磁贴添加、项目主页、GitHub Release 检查/下载/包名+签名校验/系统安装 | 还没有正式 Release；更新页应显示无可用 Release |
| \`BootReceiver\`, \`FcmTileService\` | 开机/更新后恢复**用户开启的 FCM 监控与应用守护**，磁贴执行 FCM 即时检查或打开监控 | 不启停、恢复其它应用无障碍 |

**已删除**：\`AccessibilityOps.java\`、\`RestoreService.java\`、\`RestoreTileService.java\`，及其 Manifest 注册和无关 \`WRITE_SECURE_SETTINGS\` 授权。原有“已勾选第三方无障碍服务”历史配置不自动映射为“守护应用”。

## 3. 确认过的构建事实

- 固定签名证书 SHA256：\`BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615\`。同一 keystore GitHub Actions Secrets **已经配置且实际签名成功**；不再重新生成、不再索要或修改四项 Secrets。
- **新版首轮 APK 构建**：[Actions #44138228340](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44138228340) 成功，Gradle Debug/Release、keytool、apksigner、Artifact 上传全部通过；\`BerryFCM-Guardian-v0.1.0-alpha.29\`。
- **最新已验证的 APK 构建**：[Actions #44139303543](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44139303543) 成功，\`tests/fcm-monitor-smoke.sh\` 和 \`tests/apk-module-smoke.sh\`、编译、签名、验签、Artifact 上传均通过；\`BerryFCM-Guardian-v0.1.0-alpha.30\`。
- **同次 KSU 构建**：[Actions #44139303661](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44139303661) 成功。KSU 模块仍为 \`0.5.4-test\`，数据目录 \`/data/adb/berry_fcm_ksu\` 沿用旧配置。
- **KSU 追平增量已经通过 CI**：[commit 615121d7](https://github.com/jarod360/Berry-FCM-OS7/commit/615121d72494887d1b251d34cf45ac76ef24c367) / [APK Actions #44140579529](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44140579529)，FCM/结构模拟测试、Gradle、固定签名和 Artifact 上传全部成功，APK `BerryFCM-Guardian-v0.1.0-alpha.31`。新增 90 秒巡检、受控 GMS 心跳、Doze 与计量网络、策略/通知诊断代码均在该版本中；**手机权限和效果尚未验收**。
- GitHub **Releases 列表仍为空**；工作流 \`workflow_dispatch.publish_release\` 默认 false，仅手动选择发布且签名 APK 构建成功时才创建 Release，尚未执行发布。应用内升级是否正常需首次 Release 后实测。

以上 CI 证明源代码和签名链路有效，**不表示手机已成功完成 mDNS 配对、锁屏过夜监测、stopped 恢复或覆盖升级**。

## 4. 本轮最后一次需求定案与截图问题

用户提供的 alpha.26 截图确认：旧主界面“可管理无障碍服务”不属于项目目标；在旧主界面输入 ADB 配对码，无法与系统无线调试页面同时操作；标题顶住状态栏；长页面难以使用。**修复方向不是继续让用户测试旧版，而是先交付完整模块、通知栏配对、系统安全区域布局和原有 KernelSU 功能迁移**。最新版原生代码已包含解决这些问题的实现，仍要真机确认效果。

## 5. 待验证事项（只陈述事实，不填成功）

1. 在 Android 17 / OriginOS 实机测试：从授权页开启监听 → 系统无线调试配对 → 下拉通知栏输入六位码 → mDNS 识别本机配对端口 → Shell UID 读回。未知 ROM 行为不得推断。
2. 运行态下 ADB \`ss -tnpe\` 能否读取 GMS UID：如果权限限制/UID 元信息被隐藏，要显示 unknown；需要 Root 时明确说明。
3. 监控是否在未打开主界面时持续采样，CPU suspend/Doze、系统后台管控、无线调试关闭后的错误/恢复是否明确；真实 FCM Diagnostics 的几秒断联可能被 15s 轮询漏掉。
4. OriginOS 自启、关联启动、后台高耗电、最近任务锁定只能手动页面确认；隐藏最近任务卡片可能被 ROM 禁止，不能误称隐藏进程。
5. 应用 stopped 修复（\`cmd package unstop\`）、心跳广播及 90 秒巡检在 ADB Shell 与 Root 的实际权限区别。
6. GitHub Release 首次发布、应用内下载、SHA256/签名/包名/版本回读和覆盖升级；目前 **没有已发布 Release**。
7. GitHub Actions 的静态检查不等于 UI 自动化测试。界面样式和状态栏空间仍需手机截图确认。

## 6. 下一步严格顺序

1. **当前正在做**：KSU 追平增量、更新页面旧 Android 兼容性均已通过 [APK Actions #44141173241](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44141173241)（alpha.32，固定签名验签成功）。已完成 **mDNS 失败时通知栏输入“端口:配对码”**兜底且 [APK Actions #44141729186](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44141729186) 编译/签名通过。之后才提供第一个可操作的模块化 APK 实机验收步骤。
2. **只有新增 Actions 全部通过、功能代码入口完整后**，再提供安装测试版和一套最小验证步骤；先测试授权/模块导航，随后后台状态/守护，不要让用户在旧版浪费时间。
3. 发现实机问题就针对性修复并更新这里；设备确认可覆盖升级后，再人工发布第一个 GitHub prerelease，验证 APP 在线升级。
4. APK 稳定后双线回移 KernelSU；保持独立编译和版本记录。

**每次提交同步 \`docs/PRODUCT_PLAN.md\` 和本文件。** 不为新需求重新移除既有功能，不转 Work，不要求用户重新生成密钥。日志不得上传六位配对码、签名私钥、密码或完整敏感通知内容。

## 7.1 最终测试门槛与试用范围

- **源码模块已齐**：概览、授权、FCM、应用守护、后台保活、诊断、设置和 Release 更新均有真实实现；Shizuku 式通知栏配对和正常 Android FCM Tile 已存在。
- **云端阶段证据**：APK alpha.29、alpha.30、alpha.31 的源码编译及签名均通过；最后兼容性微调仍需单独 Actions 通过才作为最新版本推荐。
- **待实机的不能在 CI 验证**：OriginOS mDNS/通知栏配对、后台保活、Root/ADB 读取 GMS Socket、运行态 UI 截图及在线覆盖升级。由于无连接的真机，不能宣称“配对已成功”或“永不被杀”。
- **测试顺序**：① 看首页布局及页面导航，② 系统无线调试界面下拉通知输入配对码并验证 Shell，③ 开启 FCM 监控查最新样本，④ 守护名单/后台权限/任务隐藏/磁贴，⑤ 锁屏与 Wi-Fi 切换对照 Google Diagnostics，⑥ 正式 Release 发布后覆盖升级。

## 7.2 无需切回 APK 的配对兜底（已通过 CI，待实机）

- 系统无线调试页面如果 mDNS 自动发现到端口，通知栏只输入六位码。
- 如果 OriginOS 屏蔽 mDNS，通知栏仍可以输入 `37123:123456`（实际配对端口:六位码），不必回到 BerryFCM 应用输入端口，保持在系统配对页面即可。
- 不记录或保存配对码，输入来自 Android `RemoteInput` 的 mutable PendingIntent；PairingService 仅连接本机配对地址。
- **代码/签名已通过** [APK Actions #44141729186](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44141729186)，产物 `BerryFCM-Guardian-v0.1.0-alpha.33`；Android/OriginOS 通知栏 RemoteInput 真实呈现仍需真机验收。

## 7.3 最后一轮界面外观修正（正在提交，待 CI）

- 深色模式采用完整 Android Material Dark 系统控件主题 `AppThemeDark`，而非仅改变文本/卡片颜色；修改 `Ui.java` 和 `styles.xml`。
- 首页返回时重新读取 Root/ADB 模式与配对状态，消除静态授权卡片的旧状态；修改 `MainActivity.java`。
- 扩展 `tests/apk-module-smoke.sh`，校验深色原生主题存在；变更提交并编译后再将对应 Actions Run/Artifact 写回。
- 这不会修改包名、签名证书、GitHub Secrets 或 KernelSU 模块。**所有 APK 运行效果仍需要用户在目标机验证**。

## 7.4 极简启动图标（本次改动，待构建）

用户明确反馈现有图标过度装饰。新版采用 Android 自适应图标：纯蓝背景、白色两条信号弧线与一个圆点，无渐变、阴影、叠层、文字；附 Android 13+ 单色主题图标。图标由 XML Vector Drawable 源码实现，分辨率无关，避免图片尺寸失真。原旧 WebP 图标资源清理。Manifest 中圆形图标和标准图标使用同一个自适应资源。

**当前功能状态**：APK 八个模块已接入，最近一次 UI 主题版 [Actions #44142556439](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44142556439) 已成功，固定签名 Artifact `BerryFCM-Guardian-v0.1.0-alpha.34` 已上传；此图标改动需要新的 GitHub Actions 构建/签名验证。尚未开展目标 OriginOS 实机验证，不发布 GitHub Release。

## 8. 按用户图片四条代码原则的审查（2026-11-18）

基于当前 GitHub main 代码审查，结论见 [PRODUCT_PLAN.md §14](PRODUCT_PLAN.md)：

- 确认真实问题：`GuardOps.command` 有无需求依据的**最多 10 个应用**截断，排序后 GMS 也可能漏巡检；删除限制，并通过 `tests/GuardOpsSmoke.java` 检查 25 个守护目标 + GMS 全覆盖。
- 去掉 `FcmMonitorService` 三处 `catch(Throwable)`，保留真正的 I/O、Shell、系统异常捕获（`Exception`）。
- 不动下载安装的包名/版本/签名安全验证、不动 ADB 配对码与端口入口验证、不动策略回读与自有白名单归属校验；它们是必要边界。
- 可能的 ADB 共享连接互相干扰、输出上限，**未实机复现，不为理论风险增加新会话层**；列入后续实机检查。

**状态：已通过 [APK Actions #44143876578](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44143876578)：守护名单回归测试、Gradle Debug/Release、固定签名、apksigner 及上传全部成功，产物 `BerryFCM-Guardian-v0.1.0-alpha.36`。** 这不是手机实机验收。下一步继续确认新 UI 和 OriginOS ADB 通知栏配对，不要求用户测试旧版。


## 9. 2026-11-18 后续版本：OriginOS 真实报错、UI 与升级兼容

- 用户新截图：原无线配对报 `Read error: ssl=... RSA routines:OPENSSL_internal:internal error`；首页授权状态和 FCM 展示顺序错误，授权页纵向展示四种模式的全部表单且验证按钮在配对之前。
- 修复基于源码的高概率 TLSv1.3/RSA-PSS 不兼容：新 AndroidKeyStore RSA 身份 `berry_fcm_adb_identity_v3` 同时允许 PKCS1、PSS，按 Google Conscrypt #718 补充 DIGEST_NONE 和 ENCRYPTION_PADDING_NONE；保留旧 alias；**新身份需要重新进行系统无线配对**。暂未实机验证不宣称修复成功。
- UI：授权卡先行、Shell 状态新鲜度/错误、无权限时历史数据不误报；授权方式横向单选、只显示所选操作，USB 为说明非 Shell 模式；自动配对后通过 `id` 验证 uid；重要功能按钮无授权时引导授权。底部导航和功能行引入 Google Material Icons（Apache-2.0）；启动器由 Wi-Fi 弧线换成 FCM 消息通知图形。
- 确认了库与监控共用 ADB 会话的冲突：Shell wrapper 不再擅自断开共享会话，静态/无线会话明确区分；服务真正执行 Shell 后更新验证时间。
- 应用显示名及新 APK 文件名为 `BerryFCM`，包名 `top.zesun.berryfcm` 和固定证书保持不变。为使旧 alpha.36 内置更新器仍能在线检测，**首个新 GitHub prerelease 必须同时上传旧名称兼容 APK 资产和新短名称 APK 资产**（二者相同签名内容）；本轮 Actions 工作流已做准备。旧版更新器只识别旧名称是有源码证据的限制。
- **发布约束**：普通 push 产生 Actions Artifact；不会自动成为 Release。必须在 CI 完成且得到真实设备准入后手动发起 `publish_release=true` 的 workflow_dispatch。用户现在没有 Release 可供在线更新；首版正式发布前仍需从 Actions 手动安装一次或通过首次发布的旧名称资产更新。
- 本轮只通过 GitHub 修改仓库与 Actions 验证，不改用户电脑 C 盘。APK 真机 TLS、OriginOS mDNS、通知栏行为、锁屏后台、旧版覆盖安装尚待逐项核实。

**TLS 二次复核**：检查 Shizuku `AdbKey.kt` / `AdbPairingClient.kt`，确认上游优先以软件 RSA + AndroidKeyStore AES-GCM 保存密钥，避免 TLS 原生调用与硬件 RSA 不兼容。查到 [Google Conscrypt #718](https://github.com/google/conscrypt/issues/718) 中相同的 OpenSSL_internal RSA 错误与已证实的 `ENCRYPTION_PADDING_NONE` 补丁，因此在前述 PSS 修复外增补 `DIGEST_NONE` 与 `ENCRYPTION_PADDING_NONE`，密钥升级至 `berry_fcm_adb_identity_v3`，以确保旧不兼容密钥不复用。此变更待下一轮 Actions 与手机配对实测；如失败再评估软件 RSA，而不是盲目清理系统所有授权。

## 10. 2026-11-18 最新编译证据与状态新鲜度

- [APK Actions #44151060176](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44151060176)：alpha.37 首轮 UI/图标/新名字与签名 APK 编译、模块和 FCM 模拟测试全部成功，Artifact 已上传。
- [APK Actions #44151785615](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44151785615)：alpha.38 补齐 Google Conscrypt #718 所需密钥参数后，模块测试、编译、固定签名及 APK 上传全部成功。
- 新增 UI 错误摘要长度限制，技术细节仍在监控错误/诊断保留。Shell 验证记录采用 `currentTimeMillis` 与 `SystemClock.elapsedRealtime` 双时钟：可检测突然改时钟或设备重启后旧记录过期，避免只靠持久化时间误判。
- 下一次源码提交后仍必须重新验证 Actions。**两个 CI 成功均不是 Android 17/OriginOS 的实际 TLS 配对成功证据**。

## 11. 2026-11-18 最终 alpha.39 CI 结果

- **成功**：[Actions #44152371697](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44152371697) / 源码 [299539cd](https://github.com/jarod360/Berry-FCM-OS7/commit/299539cd25983ef2fa221ab78dc6db0309bdc64b)。
- 所有门槛通过：FCM 模拟监控、APK 模块与权限检查、Java/Android Debug 编译、固定签名 Release 编译、apksigner、Artifact 上传。签名 Artifact **`BerryFCM-v0.1.0-alpha.39`**，不是正式 Release；GitHub Releases 当前仍为空。
- 本版包含 TLS AndroidKeyStore v3 密钥参数、需要用户首次重新配对；首页授权和错误摘要、按权限类型切换的独立操作页、模块图标与新 FCM 消息通知图标、短桌面/文件名、旧版更新器兼容旧资产名称。
- **下一步真机首要验证**：在“无线调试”系统页面保持配对码可见，在 BerryFCM 通知栏 RemoteInput 提交六位码，返回后查看是否 `uid=2000(shell)`。若仍有 TLS 错误，检查原生 Conscrypt 私钥调用；按 [Shizuku](https://github.com/RikkaApps/Shizuku) 的软件 RSA + AndroidKeyStore AES-GCM 路线备选，不再改 APK 签名。其后测试 GMS Socket 读取、锁屏和服务恢复。
- **在线升级门槛**：已有 alpha.36 只有识别旧文件名的内置更新器，因此本仓库的首次 `publish_release=true` 手动构建将同时上传新短名称和旧兼容名称资产，确保用户可以从旧版在 APP 内升级；发布前必须先确认测试接受该 prerelease 机制。普通 Actions push 不等于 Release。
- 用户本机所有新开发资料如果落盘只允许 `D:\\Codex`；此次全部通过云端 GitHub 仓库和 Actions 进行，没有触及用户 C 盘。

## 12. alpha.39 后续源码复核与 CI 待验收

- **代码问题（已验证原因）**：`FcmMonitorService` 的 `adbConnected` 可能因 UI 模式切换/共享连接复用而指向旧授权方式；`AdbPairingService` 返回 `START_REDELIVER_INTENT` 对一次性通知栏配对码不合适。两者不是新手机实测结果，而是可由源码路径直接复核。
- **修复**：每次后台 Shell 执行先通过共享 `AdbConnectionManager` 确定无线 TLS 或静态 TCP 模式，避免私有布尔缓存；服务结束时不抢占断开 UI 共用会话。配对服务所有分支改用 `START_NOT_STICKY`，null Intent 不重新开始配对。开启新配对时使旧 Shell 验证记录失效。
- **视觉与命名**：系统配对通知 `ic_bf_auth`，FCM 前台通知、系统快捷磁贴和添加请求 `ic_bf_fcm`。不更改 APK 显示名 BerryFCM、不更新安装签名或包名。
- **需要确认**：GitHub Actions 新一轮模块测试/Gradle/签名结果；真机无线 ADB 配对 uid、切换授权方式后检查、后台监控是否持续记录、在线更新从正式 GitHub prerelease 是否可用。仅 CI 完成不能声称上述实机测试通过。
- **发布策略**：仍只有 Actions 测试 Artifact，尚无已确认可用的 GitHub Release；保留旧名称兼容资产以便未来启用应用内更新，正式发布必须显式批准并满足实机验收。

## 13. alpha.40 固定签名构建验证结果

- **基线提交**：[f587b60](https://github.com/jarod360/Berry-FCM-OS7/commit/f587b601b7aa4b94db6da19e5d806233bf602def)。
- **APK Actions #44196211389**：[查看构建](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44196211389)，七项关键步骤均成功：FCM 模拟测试、模块与权限检查、源码编译、固定签名证书准备、Release 编译、apksigner 验签、Artifact 上传。
- **测试包**：`BerryFCM-v0.1.0-alpha.40`，Artifact id `14379855865`，ZIP 大小 7,357,296 字节。签名基线、`top.zesun.berryfcm` 包名和用户配置保持不变，可走覆盖安装流程，但目标 ROM 行为未验证。
- **发布状态**：再次核对 GitHub Releases 列表为空。为遵循已确认的真机验收门槛，本轮**未执行** `publish_release=true`；当前旧版本仍不能从 App 内在线找到本次 Actions 测试 Artifact。正式 prerelease 发布时工作流会携带短/旧两种文件名以供过渡。
- **最小真机验证**：首次打开检查授权卡和横向模式切换；无线调试系统页面下拉通知输入六位码；核对是否报告 `uid=2000(shell)`；再启用 FCM 监控并核对实际 Socket 证据。若新的 RSA/TLS 失败仍复现，保留原始错误与对应动作，优先审查 libadb-android/Conscrypt 的硬件 KeyStore 私钥路径，必要时参考 Shizuku 软件 RSA+AndroidKeyStore AES，不无依据重建 APK 签名。


## 14. 按产品计划核对整体进度与本轮新增功能

- 截至上一次已验签构建为 APK `alpha.40`：[Actions #44196211389](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44196211389)，FCM 模拟、模块结构检查、Gradle、固定签名及 Artifact 均通过；源码基线为 `f587b60`，后续只更新过文档。
- **源码完成度估算约 75%**：七大 APK 页面及 K01–K09 多数有功能代码，但距离产品完成还有至少四类未闭环：OriginOS 真实授权/FCM UID 可见性；后台权限逐项回读与失败解释；首次安全 Release 在线覆盖升级；实测稳定后回移 KernelSU。不把源码覆盖率理解为实机通过率。
- 用户明确由其负责实机验证，开发端继续独立推进未阻塞模块。不要每修一项都停下要求手机截图。
- **本次修复**：`DiagnosticsActivity` 的三端口 TCP 基础连通性不再调用 Root/ADB Shell，直接以 Java Socket 连 `mtalk.google.com:5228/5229/5230`；保留 GMS 策略/UID 等特权诊断在 Shell 通道。运行结果不是推送送达证明。
- **本次修复**：`BackgroundSettings.optimize(context,shell)` 先读并记录可识别的 Doze 与两个 AppOps 原值，执行后复查；新增 `restoreSelf` 与“撤销本版 BerryFCM 添加的后台策略”。禁止撤销未持有所有权的旧/外部设置，旧版未记录原值的变更不能凭猜测还原；权限不可读即不盲写。保活页面标准状态增加通知授权与 Root/ADB 近期验证，并明确厂商私有开关需手工确认。
- **后续唯一开发优先级**：继续核对应用守护/后台 UI 的逐项状态及错误反馈；待 CI 构建成功后记录 Run、Artifact 和 commit。不要擅自创建 GitHub Release；首次 Release 必须保留旧文件名兼容包。

## 15. alpha.41 编译验签通过与后续入口整理

- [Actions #44197295228](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44197295228) / commit `a8a351e`：`BerryFCM-v0.1.0-alpha.41` Artifact 已上传（ID `14379252792`），六项关键门槛：FCM Shell 模拟、模块检查、APK Java/Android 编译、固定签名 Release 编译、apksigner、上传均成功。仅验证 CI，不代表 OriginOS 实测通过。
- 在此基础上继续修复 OriginOS 自启动和关联启动按钮重复指向同一 Activity 的误导行为；新版本以一个入口和明确说明替代重复入口。仍保持与 Shizuku/GKD/Traccar 等开源项目交互取舍一致，不捏造 ROM 私有 Activity。
- 不产生 GitHub Release，等待用户自行实机验收并确认进入发布阶段。

## 16. 当前已签名 APK：alpha.42（本轮最终构建）

- APK 源码 commit：[879b5db](https://github.com/jarod360/Berry-FCM-OS7/commit/879b5db4d2ae239e376eeeb418953392971a2a1d)；固定签名构建 [Actions #44197684568](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44197684568) 全部成功。
- 测试 Artifact：`BerryFCM-v0.1.0-alpha.42`，Artifact ID `14380091886`，压缩文件大小 7,357,687 字节。Shell 监测模拟、模块测试、Java/Android 编译、固定证书准备、Release 构建、签名验证、上传均通过。
- 本轮相较 alpha.40：原生 APP 无 Root/ADB 的 mtalk 三端口 TCP 检测；BerryFCM 自身 Doze 与后台 AppOps 更改先记录原状态、回读、可撤销；后台页面增加通知权限和 Shell 新鲜度提示；修正 OriginOS 自启动/关联启动重复跳页的误导。
- **未验收**：OriginOS 真机 TLS 配对、Shell UID Socket 权限、后台过夜/开机自动恢复、原始 AppOps 命令厂商兼容、完整 Release 在线覆盖升级；不以 CI 成功代替。
- 未执行正式 Release；用户负责真机验证，开发继续按第 19 节优先级推进。未经实测不要回移不确定的 Shell 规则到 KernelSU。

## 17. 2026-11-18 集中 UI/授权/守护改版交接

- **核心纠错**：旧 `shellRecentlyVerified()` 的 120 秒 TTL 只是短时 Shell 成功证据，不能作为“无线 ADB 是否配对过”的依据；用户已成功配对后不再反复要求手动授权/连接。持久记录 `adb_paired`，旧版配对成功文本兼容；配对成功立刻启用并启动 FCM 监控，UID/采样由后台自动回读、重试，不以配对成功冒充实际已采样。
- **配对流程**：主按钮“开始配对并打开无线调试”同时启动前台配对通知、mDNS 发现与系统无线调试界面；系统直达组件可能被 OriginOS 隐藏，按 Shizuku 方案回退到带无线选项提示的开发者选项。六位码只通过通知栏提交；重配操作在“配对已完成”状态下折叠隐藏。
- **主页**：FCM 状态第一、服务开关与最近采样证据在主屏；权限卡仅首次未配对或明确采样错误时出现。未采样首次显示尝试中，不再静态展示空旧快照误导。
- **守护页面**：已选目标含 GMS 固定显示名称/图标；列表候选只在打开选择器时才加载，已选置于列表外，勾选后立刻进入已选区；系统应用手动开启选项才显示，且对系统候选过滤非普通 UID、未启用/未安装、持久进程、无 Launcher 入口；最终能否 unstop 仍由实际回读决定。
- **设计**：参考 Shizuku/GKD/Hail/Shelter 的状态优先、渐进披露和系统应用筛选；仅修改原生 Android Java UI，不切换 Compose；桌面自适应图标更新成原创的盾牌+心跳电路、通知/导航一致。
- **CI 使用约束**：本次各模块 UI/后端与回归测试先全部暂存 GitHub blobs，最后只发布一个 main 提交，触发一次 Actions；不产生中间测试版本、不自动发布 GitHub Release。CI 通过后才确认构建版本。
- **未实测**：OriginOS 私有无线调试入口是否直达、系统是否禁止通知/新 FGS、GMS Socket 对 uid=2000 可见性、完整长期保活以及旧版本直接覆盖安装。实机验证由用户负责。

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

### 2026-11-18 后续：授权状态及长时间 FCM 断开恢复
- 上次授权优先界面和 Priv Kit 自动配对地址修正已进入独立构建分支（e0f86f3e），尚未获得新 APK 的实际认证结果。此次保留启动异常的具体原因，授权页可展开并复制；快捷设置磁贴也检查真实 Binder / 授权状态。
- 用户提供 Google 原生 FCM Diagnostics：06:25、06:29、06:39 多次关闭后恢复，06:46:15 起再次断开，06:54:26 失败并切换备用服务器；07:41 显示 Connecting、alt7-mtalk.google.com:5228、断开 55:15、ERR_IO_RST_HB。心跳调度延迟约 68–143 秒。该证据只证明 GMS 原生连接异常，不证明 OriginOS、网络或 BerryFCM 单独为根因。
- 原轮询在超过 90 秒间隔后重置连续观察，深睡间歇唤醒可能始终错过 15 秒确认条件。长时间原生断开、网络稳定、自动恢复开启、达到冷却期限时，新增同一次唤醒内相隔 2 秒的第二次原生检查；第二次恢复、未知或端点/断开时长不符合连续性时不发送广播。保留 5/10/15 分钟冷却、禁止强杀 GMS、禁止修改 DNS 等边界。第三次及后续交替尝试定向 MCS_HEARTBEAT，属于未公开 GMS 接口，只有原生 Connected 可证明恢复，不保证推送送达。
- Binder 失效且已有配对、无线调试开启时，原启动失败标志不再永久阻断恢复；仍按持久化 5 分钟冷却限频，不在正常 Binder 命令中启动 ADB。不自动重新配对。
- 已增补模拟回归测试，尚待实际构建和真机验收。先验证 ADB、FCM 原生状态，再验证锁屏期间断开日志与恢复请求。尚不创建 Release。

### 当前阶段统一交接（与 PRODUCT_PLAN 最新章节同步）
- G0 完成，G1 主体实现，G2 授权 P0 阻断，G3 主体实现仍修复，G4 更新未闭环，G5 最新代码尚无完整 CI 证据，G6 已开始但未达到最终验收，G7 未回移。不能说只剩真机。详见 PRODUCT_PLAN.md 最新统一表。
- 新源码提交 [55434d1](https://github.com/jarod360/Berry-FCM-OS7/commit/55434d18785f62461656ccd59bb084561de0c467)：各入口统一实际授权条件；守护结果改为准确的强制停止语义，保存检查时间；后台策略读取必须成功并排除权限失败后才写入，Doze 使用完整包名匹配。回滚记录先于 GMS 策略变更写入。未新增权限，尚未确认新 CI。
- 上一独立构建提交 [8b42663](https://github.com/jarod360/Berry-FCM-OS7/commit/8b426632f40bf761bd4c437412e56868ea033d4e) 已推送；此交接更新时尚未取得该批完成结果。历史 alpha.47 已有签名/Artifact 证据，alpha.49 的实机失败由用户反馈记录，不能混同最新版成功证据。
- 下一动作：将本轮完整代码同步到 build/native-fcm-batch，核对实际工作流和最新 Artifact；继续解决编译/回归失败及非阻塞模块，之后才提供实测包。未获设备通过不发布 Release，不修改 main/KSU。

**构建分支推进记录（本轮最后核对）**：代码批次对应 [8ba62c0](https://github.com/jarod360/Berry-FCM-OS7/commit/8ba62c04652597da23a158d59fd1ad40da317039)，已推送 `build/native-fcm-batch`；该提交与开发代码基线 1c878df 仅存在构建 workflow 触发配置差异。已实际查询历史 [#44228547705](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44228547705)：build 成功、签名与验签成功、Artifact alpha.47（14394306272）未过期，发布任务跳过。当前新提交尚无可确认的完成结果，不能推荐旧 alpha.47 作为已修复的授权版本。此后开发分支只追加文档记录，不因此重复触发构建。


### 最新执行约束：停止碎片化 APK 构建，集中完成研发

用户明确要求整体代码与功能基本完成后再统一编译，不再每次修改少量代码就触发 APK 构建或要求反复安装。即日起只在 dev/native-fcm-state 集中提交完整逻辑、回归用例及双文档，不更新 build/native-fcm-batch，不合并 main，不发布 Release。开发阶段可做源码检查、独立逻辑回归；集中完成授权、FCM、守护、保活、诊断、更新相关代码审查与修复后，才进行一次完整 Gradle 编译、固定签名与制品验签。构建错误需要针对性处理，不将新测试包的数量当作研发进度。

本批源码 [835e945](https://github.com/jarod360/Berry-FCM-OS7/commit/835e9458919c2adadf3a31f07fd40e193e38cdb9)：复核 Priv Kit v0.20.0 官方启动文档及 Android 局域网权限文档（https://developer.android.com/privacy-and-security/local-network-permission）。当前 targetSdk=35，Android 17 的 ACCESS_LOCAL_NETWORK 强制运行时授权只适用于 targetSdk>=37，先不增加多余授权，不把这项变化判为现有故障根因。配对通知渠道禁用时明确跳转对应设置；自动发现失败约 15 秒后才提供通知栏端口:配对码的显式备用入口，正常仅输入六位码（手动端口仍受上游本机 loopback 支持限制）；防旧通知/重复回复，新增门控回归用例；保存实际启动日志到本地应用私有存储；Binder 延迟回传时首页恢复用户之前开启的任务。没有获得真机成功证据，不标为已解决 ADB。当前仅源码提交，未触发完整 APK 构建。下一步重点对照上游启动阶段、状态切换和真实失败诊断，继续非阻塞模块。此前已推送的构建分支保留，停发新增测试构建。


### 下拉快捷磁贴与前台通知（本轮集中开发）
- 用户指出未授权时下拉仍显示 BerryFCM。区分系统快捷磁贴、配对通知和监控前台服务通知。快捷磁贴组件默认禁用，仅授权成功后由用户在设置中主动添加，提供移除入口；系统管理磁贴位置，已主动添加的磁贴在临时失去授权时显示不可用。取消添加恢复之前的组件状态。去掉 active tile 模式以在打开快捷栏时刷新状态。
- 监控前台服务入口、系统重启/更新恢复及执行前均检查授权资格；首次配对交换、历史开关不足以启动。已实际验证过 UID 2000 的用户在无线调试已开且未被拒绝时保留有限自动恢复能力，通知明确暂未监控；没有授权/资格时停止服务并移除前台通知，保留用户的任务偏好。配对通知只在主动配对期间展示，成功或失败结果不属于监控。正常 monitor 通知显示原生 FCM 状态或检查中，不以历史采样冒充实时连接。Android 前台服务通知仍遵循系统规定。
- 参考 Android 官方 Quick Settings 指南：https://developer.android.com/develop/ui/views/quicksettings-tiles 。没有触发 APK 构建，待集中回归及统一编译。
**用户再次定案：**即使 ADB/Root/Shizuku 已授权、服务启动成功、FCM 正在监控，也不得自动添加或显示快捷磁贴。默认禁用磁贴组件；仅“设置 → 添加 FCM 快捷磁贴”的显式操作可启用，系统确认添加后才进入快捷栏。移除磁贴独立于后台监控、守护和恢复。系统要求的前台服务通知不等于磁贴，不能承诺由应用绕过系统通知规则。增加静态回归约束，禁止授权、监控、开机路径启用磁贴。未触发 APK 构建。

### 后台权限统一入口与状态回读（用户最新要求）
- 磁贴、通知、系统电池优化、OriginOS 自启动/关联启动/高耗电/最近任务锁定统一归入后台保活页面；设置页仅保留导航入口。磁贴默认关闭，主动添加后读取系统添加/移除回调；不随服务启动添加。监控通知类别和总体通知权限分别读取，返回页面重新刷新。标准 Android 电池豁免、后台限制用系统 API 回读；不能将 Android 未限制等同于 OriginOS 允许高耗电。
- 参考 Oolong (https://github.com/HuanCheng65/Oolong/blob/ba4b1a074781679240c03dd837eb4a9be8649f1b/app/src/main/java/oolong/client/utils/BackgroundSettings.kt) 的 On/Off/Unknown 和厂商设置跳转，Smart-Edge (https://github.com/Imtiaz-Official/Smart-Edge/blob/04cbca29b4776985b9e0eee84161d5c23ee1e89e/app/src/main/java/com/imi/smartedge/sidebar/panel/Extensions.kt) 的 Vivo 私有 AppOps 10002 候选。仅当 ROM 自身返回明确 autoStart 操作名才尝试只读模式，且标注厂商开关需实机核对，异常/未知不标为关闭。未发现经过 OriginOS 7 验证的关联启动、高耗电、最近任务锁定读取接口；当前逐项显示需系统确认，后续必须以目标 ROM 开关前后差分验证读回，不伪造布尔值，不申请无障碍。未触发 APK 编译。

### 授权管理与辅助后台权限的最终分工（覆盖前面页面布局说明）
用户明确：自启动、关联启动、允许后台高耗电、锁定后台等全部在授权管理给出点击跳转，返回后检查真实状态。它们仅辅助系统稳定性，不作为 ADB/Root/Shizuku 已授权后的监控/守护门槛；通知或快捷磁贴未开启也不得停任务，更不自动添加磁贴。后台保活页面只管理用户任务开关、运行状态、已有高级策略及授权管理导航。没有核心授权才禁止执行特权命令；无线调试关闭但现有 UID 2000 Binder 仍活着时继续运行。
- 授权页直接列出辅助权限和显示选项，返回时立即读取系统状态。自启动沿用 ROM 名称校验的 AppOps 候选，关联启动、高耗电、最近任务锁定仍需目标设备差分验证，不冒充已授权。参考 HelloDaemon、YCAppTool、Smart-Edge 的 Vivo 高耗电页面入口，以及 Legado 的 PurviewTabActivity 权限管理入口；全部按可打开性逐级降级，不声称厂商页面必然直达。最近任务在用户确认且具备现有核心权限后仅发送 APP_SWITCH，锁定由用户亲自操作；未授权时给手动操作提示。隐藏卡片和锁定是不同选项。
- 低干扰显示仅刷新现有前台通知，不再借由显示开关启动/停止监控；不变更任务开关。回归约束检查监控/授权资格/开机恢复不引用辅助权限，避免后续引入硬门槛。当前源码提交，不编译。后续须验证 OriginOS 7 的真实权限状态映射。

### 应用内在线升级（集中研发）
现有更新器读取 GitHub Releases（包括命名匹配的 prerelease），Actions Artifact 不属于发布源；此前确认的构建发布任务跳过。用户不应手动下载 APK 再寻找文件安装。本批改为点击“立即更新”后，由系统下载管理器下载、展示进度、在更新页可见时自动校验包名、版本、SHA256（Release 提供时）和固定签名，自动打开系统安装器；首次安装来源权限由用户授予，返回自动继续。下载中离开页面，返回继续；失败可重试，不静默安装、不卸载旧版、不引入新权限。参考 GKD UpdateStatus 的独立检查/下载/安装状态管理。发布仍以实机验收后允许的固定签名 Release 为准，未发布新版本之前无法在线获取该版本。当前仅源码修改，不触发 APK 构建；在线升级实际覆盖安装尚未验证。

## 集中开发最新唯一执行清单（覆盖旧阶段/发布说明）
用户要求汇总最近全部问题，集中解决，不再碎片编译。当前仍非单纯验收阶段。P0：真实自动 ADB 配对/发现、启动 UID2000 Binder、保留有效配对、失败日志、平时不连接 ADB；P1：原生 FCM 状态、断联/深睡恢复、守护与保活不依赖辅助权限；P1：授权管理中自启动/关联启动/高耗电/锁定/电池/通知/磁贴逐项真实回读及跳转，磁贴仅显式添加；P1：在线更新发现、应用内下载、签名与版本校验、覆盖安装。最终完成 APK 真机验收后再回移 KSU。
本轮实际修改：
- 检查无线和静态 ADB 的实际 id 输出后才保存模式，拒绝假授权；启动结果异常但已收到有效 Binder 时接续该 Binder，不重复开 ADB。旧错误提示在恢复时清除。此前自动发现、启动及配对失败仍须目标机器验证，不声明已经解决。
- 原厂框架线索来自 https://github.com/imwangwang/vivo-apps/blob/6aff38cfa6133250fc58a2fdc5f6452c676440aa/main/java/com/vivo/services/security/client/VivoPermissionType.java 与同目录 VivoPermissionManager/VivoPermissionInfo：通过现有 ROM 自身类型名 AUTO_START、getVPTypeId、getAppPermission(self)、getPermissionResult、GRANTED/DENIED 常量读取自启动；不硬编码私有 ID，不修改权限，隐藏接口拒绝时仍为未知。另参考 https://github.com/Lokside/vivo-guardian/blob/579030d4a6d413bfe5a80424fa16e1b92300661d/docs/TECHNICAL.md，OriginOS 7 的最近任务锁定涉及 UserLockedAppFilter/LockedAppManager，但没有公开已验证的读取接口。新增只读本 APP AppOps 诊断以便开关前后比对；关联启动/高耗电/后台锁定的真实自动回读仍待目标 ROM 证据，不能用通用 Android 状态或访问页面记录冒充。
- 应用内更新：开始查更新时清旧下载候选。发布工作流由原“再次编译并发布”改为人工选择已成功构建的 accepted_run_id，跳过 build，校验原运行来源/状态、从该运行下载同一固定签名 Artifact、验签/包名/版本号后创建对应源提交的 prerelease。无需为同一份 APK 重新编译，禁止自动发布。以后可明确选择通过完整 CI 的测试 prerelease 供应用内升级，稳定发布仍须验收；不发布当前未验证代码。旧包原来没有新 Release 因而找不到更新，不能假定新源码已经在用户手机生效。
- 集中验收顺序：本地/轻量静态与脚本回归（不生成 APK）；完整批次一次 CI；选通过的同一产物受控发布测试 prerelease；当前旧固定签名 APK 在线升级；首次配对、真实 UID/Binder、无线调试关闭后仍监控、系统撤销与服务死亡；自启动/关联/高耗电/锁定各自开关前后真实差分；磁贴和通知独立；锁屏/断网/重连及应用守护；回移 KSU 与最终发布。
本轮只更新 dev/native-fcm-state；不触发 APK 构建，不推 build 分支、不合并 main、不发布 Release。尚无最新版 Android 编译或真实手机通过证据。

### 集中测试包准入修正（2026-11-18）
用户同意自检到可验证时发布测试 APK，供现有版本应用内在线升级；不要求等所有真机功能验证后才发布测试 prerelease，不得当稳定正式版。检查原厂 Priv Kit v0.20.0 manifest 与 NSD 实现：无 NEARBY_WIFI_DEVICES 依赖，移除本项目额外的强制附近 Wi-Fi 权限请求（保留上游自己的标准声明，targetSdk35）。授权配对通知回复复用已在前台的服务，不以过期回复创建新的前台服务；结束提示 15 秒后消失，可点击授权页。首次成功自动监控，但已有明确关闭偏好不因重新授权被重置。厂商自启动读取转后台线程；辅助权限继续未知时据实显示。守护目标以每批最多5个执行，避免25个应用的最坏情况超45秒单命令超时，检查未安装的目标不执行 unstop。在实际容器 JDK 中执行仓库原始 PairingGateSmoke、LaunchCooldownSmoke、GuardOpsSmoke，通过并比对六个源文件 Git blob SHA；更新的守护分批用例随完整 CI 执行。此前累计静态匹配不是完整CI证据。待一次集中 SDK/Gradle/脚本/模块/固定签名/验签后，才以同一个通过产物发布测试 Release；主分支与KSU不改。厂商关联启动、高耗电、锁定回读尚未有证据，仍需真机协同核对。

### 集中自检及测试发布准入记录

- 2026-11-18 用户已明确授权自检通过后发布一份可在线升级的测试 prerelease；不修改 main、不提前回移 KSU。开发基线 [898905d](https://github.com/jarod360/Berry-FCM-OS7/commit/898905dc2d446c158f0f0bee6f04c8662acaedbc)。
- 实际 JDK 执行 PairingGateSmoke、LaunchCooldownSmoke 与原始及新增的 GuardOpsSmoke，均 PASS；源码/用例 Git blob SHA 与仓库逐一一致。新增分批守护 25 个目标 + GMS（6 批，每批至多5个、GMS仅一次）PASS。纠正已迁移到 AuthActivity 的磁贴图标静态断言。
- 唯一集中构建提交 [e84dbb3](https://github.com/jarod360/Berry-FCM-OS7/commit/e84dbb342344d50e4c9e71604e8e84f3ca3dd241) 已推送 `build/native-fcm-batch`，与开发基线仅 workflow 不同。该提交的明确 [publish-test] 标识及分支限制代表本次用户授权；只有原生 Shell 回归、模块检查、完整 Gradle 编译、合并权限检查、固定签名和 Artifact 上传全部通过，发布 job 才会下载这一运行的同一 APK，再验签和包名/版本号，创建对应 commit 的 GitHub prerelease（双兼容 APK 名称，非 latest 稳定版）。发布不重新编译；不通过任何关卡就不发布。
- 当前已核对分支更新成功，但未获取 CI 最终状态或 Release URL。不标记构建或发布成功。可通过 [Actions](https://github.com/jarod360/Berry-FCM-OS7/actions/workflows/build-apk.yml?query=branch%3Abuild%2Fnative-fcm-batch) 或提交状态 berryfcm/build、berryfcm/test-release 核对真实结果。原厂私有权限尚未验证的项由目标手机实测；无需等待稳定版发布才做测试升级。

### 2026-11-18 集中构建失败修复
- [Actions #44242562721](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44242562721)：SDK、原生 FCM 模拟以及完整 APK 模块/权限 scope 回归均实际通过；`compileDebugJavaWithJavac` 唯一报错 `PrivKitShell.java:92`，官方 `createAdbManager` 声明 checked `PrivilegeStartupException`，Java 包装方法遗漏 throws。已查明所有三处调用方已声明 throws Exception，直接向上透传原始异常供现有诊断，无额外捕获或授权逻辑变动。签名/发布未执行。只针对已确认的完整构建失败修正，仍以同一个通过产物自动发布用户已批准的测试 prerelease。

### alpha.54 制品发布修复
- [Actions #44245895574](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44245895574) 完整 build 成功：原生 FCM、模块检查、Debug/Release 编译、合并权限范围、固定证书、公钥签名验签、上传全部通过。制品 14401338560，BerryFCM-v0.1.0-alpha.54，已确认原始签名 `V2 Signer: certificate SHA-256 digest` 与固定指纹一致且 Number of signers=1。发布 job 下载原制品且 ZIP digest 正确，但脚本只匹配旧 Signer #1 输出导致校验失败，并非签名错误。
- 发布/晋升统一支持 Build Tools 37 的 V2 Signer 和历史 Signer #n 输出，继续要求单签名、精确固定指纹、包名和原版本号。仅允许 build/native-fcm-batch 的显式 `[promote-test:<run_id>]` 触发仅发布 job（跳过 build）；校验指定 run 原 build 成功和八项必要门槛，允许发布 job 自身失败的旧 run，下载其同一制品。此轮只晋升原 #44245895574 / alpha.54，不重复编译、不生成新 APK，不改签名或版本。实际发布状态待 Actions 确认。
