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
