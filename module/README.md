# Berry-FCM KSU v0.5.4-test

KernelSU Root 模块，保留 v0.5.2 的应用守护、策略诊断和 WebUI，在此基础上增加 FCM Socket 生命周期监控。模块 ID 仍为 `berry_fcm_ksu`，升级保留 `/data/adb/berry_fcm_ksu/` 下的守护名单和配置。

## v0.5.3 基线与 v0.5.4 修正

- **真实 Socket 观察**：仅检查 GMS UID 的 TCP 连接；优先识别 5228–5230。兼容 Android 多用户 UID（例如 `10200` / `99910200`）。
- **受限的 443 候选识别**：只将远端 IP 与 Google mtalk 主机名解析结果，或本启动周期内从**已建立** 5228–5230 Socket 学到的远端 IP 匹配的 GMS `:443` ESTABLISHED Socket 作为候选；SYN-SENT 不会学习信任地址。普通 GMS HTTPS 443（例如 Fastly 或其它服务）不算 FCM 在线。mtalk DNS 匹配仍不能证明 MCS 认证，UI 会标记为候选。
- **保守未知状态**：GMS UID / `ss` 不可读取时，或存在 GMS `:443` 连接但 mtalk DNS 地址完全无法确认时，显示“无法确认”，清除本轮缺失计时，不触发自动重连。
- **连接生命周期**：记录首次观察到 Socket 的采样时间、观测持续时长、首次观察到 Socket 缺失的采样时间、连续缺失观测时长、最近再次观察时间、Socket 更换和采样时间。时间不是内核精确事件时间：实际连接变化发生在相邻采样之间，正常约有一个 30 秒采样周期误差，Doze 可能进一步延迟。
- **自动重连记录**：自动/手动请求时间、原因、次数、广播是否被系统接受，以及之后是否实际观察到连接恢复。`Broadcast completed` 只记为“请求已发送”，不等于连接成功。
- **事件时间线**：最近 120 条事件保存在 `/data/adb/berry_fcm_ksu/fcm-events.log`，状态保存在 `fcm-monitor.state`。
- **WebUI**：增加连接时间线卡片，显示连接时长、断联时长、重连结果和事件历史；保留浅色/深色主题、Google 原生 FCM Diagnostics 跳转、应用多选、Doze/后台网络诊断等原功能。

## 自动重连规则

- 服务每约 30 秒采样一次。
- 在可确认的“未观察到已建立 FCM Socket”或“只有连接尝试尚未建立”状态下，连续约 120 秒后请求一次 `com.google.android.intent.action.GCM_RECONNECT`。
- 两次自动/手动请求至少间隔 300 秒。
- 只有 GMS `stopped=false` 且状态可读取时才发送广播。
- 如果检测结果为 unknown，不累计断联、不发送自动重连。
- 深度 Doze / CPU suspend 可能延迟 Shell `sleep` 和采样；120 秒是监控进程实际运行时间门槛，不承诺锁屏后严格 5 分钟内恢复。

## 443 判定边界

Google 官方网络文档列出 Android FCM 所需 TCP 端口 5228、5229、5230、443，以及 `mtalk.google.com`、`mtalk4.google.com`、`alt1-mtalk.google.com` 至 `alt8-mtalk.google.com` 等主机名。实现参考了 FCMFix ColorOS 对“443 必须匹配 mtalk 地址，不能把所有 GMS HTTPS 连接都算作 FCM”的公开思路，但本模块独立使用 Root Shell / `ss` 实现，没有移植 LSPosed Hook。

DNS 查询由本机系统解析器执行，可能受当前网络、分流或 DNS 策略影响；如果无法建立可信地址集合，模块宁可显示 unknown，也不把任意 443 误判为在线或离线。GMS 的真实 MCS 状态仍应与 Google 原生 FCM Diagnostics 和真实推送测试交叉核对。

## 安装与升级

1. 确认 KernelSU 已授予本模块 Root。
2. 从 KernelSU「模块」安装 `BerryFCM-KSU-v0.5.4-test.zip`。
3. 若支持热安装，按管理器提示操作；临时 Root 设备不要为了更新而贸然重启。
4. 从 KernelSU 模块 WebUI 打开页面，先点“立即检测”，再核对 Google 原生 Diagnostics 的 `Connected` / `Close` 记录。
5. 自动守护默认沿用原配置。先观察事件时间线和真实推送，再决定是否开启。

## 手动诊断

```sh
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh status'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh events'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh reconnect'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh check'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh guard on'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh guard off'
```

运行时数据：

- `/data/adb/berry_fcm_ksu/targets.txt`：守护应用名单
- `/data/adb/berry_fcm_ksu/enabled`：自动守护开关
- `/data/adb/berry_fcm_ksu/fcm-monitor.state`：当前连接状态与时间戳
- `/data/adb/berry_fcm_ksu/fcm-events.log`：连接 / 断联 / 重连事件
- `/data/adb/berry_fcm_ksu/guard.log`：守护巡检日志

## 保留功能

- GMS / 指定应用 stopped 状态检查和修复、广播心跳。
- 应用守护名单增删、连续多选、已选置顶、分批加载全部应用。
- GMS Doze 白名单、Android 数据节省模式和后台计量网络状态诊断及手动放行/撤销。
- Google FCM Diagnostics 原生页面跳转、只读 TCP 可达性测试。
- 浅色 / 深色主题；不依赖 Shizuku、LSPosed。

## 安全与限制

- 不修改 hosts、DNS、VPN、代理、路由或系统分区。
- 不强制停止 / 重启 GMS，不清除数据，不声称永久保活。
- TCP ESTABLISHED 只证明采样时观察到 GMS UID Socket；mtalk 地址匹配的 443 只是候选，不等于 MCS 已认证或消息一定送达。
- 普通 GMS 443 不计为 FCM；DNS/UID/Socket 读取失败时状态未知。
- 仅监控 Android 用户 0 的 GMS 主包 UID；厂商私有冻结、网络限制不保证可解除。
- Doze 白名单和后台数据放行可能增加待机耗电 / 流量。
