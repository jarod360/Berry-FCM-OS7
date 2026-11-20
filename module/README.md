# Berry-FCM KSU v0.5.12-test

KernelSU Root（UID 0）模块。模块 ID 仍为 `berry_fcm_ksu`，升级保留守护名单、开关和旧 TCP 监控历史。新 GMS 监控写入单独的 `mcs-*` 文件。

## 监控与恢复

- 「设置 → 后台保活」可选随系统唤醒（默认，约每 30 秒尝试巡检）或 3 分钟定时巡检。后者保留常规巡检，并由独立的 ARM64 Root 定时器增加检查机会。定时器失败或迟到时，常规巡检继续运行。两种方式都没有持续 CPU 唤醒锁。
- 以 GMS `GcmService` 原生状态显示已连接、连接中、断开、未知和过期。原生状态不可读时，GMS UID 的 TCP 仅提供辅助诊断，不能触发重连。
- 明确断开通常等 15 秒，连接中通常等 60 秒。达到条件后在同一执行窗口二次确认。跨采样空档仅将此前断联当作复查线索。
- 确认 GMS 未被停止后，Root 定向发送 `GCM_RECONNECT`。自动和手动共用 5 / 10 / 15 分钟退避，原生状态恢复连接后清零连续重试计数。
- 广播完成只记为已请求。WebUI 用后续 GMS 原生采样确认连接，实际推送送达需单测。

热安装时，如果 Root 服务继承了管理器的应用冻结组，服务仅迁移自身到 cgroup v2 根组，子进程继承该组。迁移失败会在 WebUI 和后台日志中显示。

## 安装与升级

1. 确认 KernelSU 已授予本模块 Root。
2. 从 KernelSU「模块」安装 `BerryFCM-KSU-v0.5.12-test.zip`。
3. 若支持热安装，按管理器提示操作；临时 Root 设备不要为了更新而贸然重启。
4. 从 KernelSU 模块 WebUI 打开页面，先点“立即检测”，再核对 Google 原生 Diagnostics 的 `Connected` / `Close` 记录。
5. 自动守护默认沿用原配置。先观察事件时间线和真实推送，再决定是否开启。

## 手动诊断

```sh
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh status'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh events'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh native-events'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/fcm-monitor.sh reconnect'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh check'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh guard on'
su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh guard off'
```

运行时数据：

- `/data/adb/berry_fcm_ksu/targets.txt`：守护应用名单
- `/data/adb/berry_fcm_ksu/enabled`：自动守护开关
- `/data/adb/berry_fcm_ksu/mcs-monitor.state`：当前连接状态与时间戳
- `/data/adb/berry_fcm_ksu/mcs-monitor-events.log`：巡检空档 / 断联 / 重连事件
- `/data/adb/berry_fcm_ksu/mcs-native-events.log`：筛选和去重后的 GMS 原生事件
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
