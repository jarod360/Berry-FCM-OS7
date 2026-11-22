# Berry-FCM KSU · RC5 源码候选

**尚未打包** 当前修复分支候选版本为 `1.0-rc.5 / 10005`，现有可安装测试包仍为 RC4（`1.0-rc.4 / 10004`）。RC5 ZIP 尚未生成；全部修复和核对完成后再由用户手动触发最终云编译。

KernelSU Root（UID 0）模块。模块 ID 仍为 `berry_fcm_ksu`，升级保留守护名单、开关和旧 TCP 监控历史。新 GMS 监控写入单独的 `mcs-*` 文件。

## 监控与恢复

- 「设置 → 后台保活」可选常规巡检或定时唤醒巡检。主服务由 KernelSU service.sh 常驻运行；定时模式由独立 ARM64 CLOCK_BOOTTIME_ALARM 定时器唤醒。定时器失败时回退常规巡检，不持有持续 CPU 唤醒锁。
- RC5 的 GMS 连接状态只依据 procfs：读取真实 GMS UID，检查 /proc/net/tcp 与 /proc/net/tcp6 的 MCS 5228/5229/5230 socket。dumpsys 不参与在线、断联、连接中、恢复或重连判断。ESTABLISHED 是连接正证据；SYN_SENT/SYN_RECV 是连接中；只有 TCP/TCP6 两份表均完整且无匹配 socket 才能确认断联。普通 443 不作 MCS 证据。
- 完整无匹配通常等待 15 秒并进行独立二次 procfs 采样；连接中按 60 秒观察。跨采样空档不会沿用过期状态直接重连，而是重新读取 TCP/TCP6。
- 不再使用原生 connectTime 停滞、Close 字段或 dumpsys 解析结果作为重连依据。重连必须由 procfs 的完整负证据和二次采样确认。
- 达到 procfs 断联确认条件后，Root 定向发送 `GCM_RECONNECT`。自动请求退避为 30 / 60 / 120 秒，之后封顶 180 秒；手动请求至少间隔 60 秒。后续观察到 MCS ESTABLISHED 后清零连续重试计数。
- 广播完成只记为已请求；后续只有新的 GMS UID MCS ESTABLISHED socket 才能确认连接恢复。TCP 建立不等于 FCM 认证成功或消息送达。
- 重连前先原子保存 pending、尝试次数和冷却锚点；保存失败不发广播。广播后结果保存失败保留 pending，重启后用 epoch 重建 monotonic 冷却锚点；时间回拨不会绕过或无限延长已建立的冷却。

热安装或 WebUI 启动服务时，服务会检查自身 unified cgroup：不论路径是 /apps/* 还是 /uid_*/pid_*，只要不是根组就尝试迁移到 cgroup v2 根组，并回读确认；迁移失败或无法读取 cgroup 会写入 guard.log，定时唤醒标记为不可用。旧锁 PID 必须同时匹配 /proc/PID/cmdline 中的本模块 service.sh；陈旧锁通过原子重命名后清理，避免 PID 重用和并发启动误判。服务启动时记录 service_cgroup_initial，便于判断实际继承的 cgroup。

## 安装与升级

1. 确认 KernelSU 已授予本模块 Root。
2. 从 KernelSU「模块」安装 `BerryFCM-KSU-v1.0-rc.4.zip`。
3. KernelSU 支持热安装时，BerryFCM 会停止旧监控服务并启动新服务，不需要为本模块更新重启设备。若管理器仍显示需重启，代表当前 KernelSU/管理器未完成热安装；请查看安装输出。
4. 从 KernelSU 模块 WebUI 打开页面，先点“立即检测”；Google FCM Diagnostics 仅作独立旁路参考，不参与 RC5 状态判定。
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
- `/data/adb/berry_fcm_ksu/mcs-native-events.log`：旧版本遗留事件文件；RC5 procfs 状态判定不依赖它
- `/data/adb/berry_fcm_ksu/guard.log`：守护巡检日志
- guard.log 中的 service_started、service_cgroup_*、fcm_check_start / fcm_check_end：分别记录服务启动/cgroup迁移和每次巡检开始结束；maintenance_start / maintenance_end 记录守护修复命令。wake-timer.log 记录 timer worker 的启动/退出、armed/fired、迟到和 suspend 时长。三者对照可定位空档发生在服务存活、cgroup 冻结、定时器唤醒还是检查调用阶段。
- 导出记录：su -c 'sh /data/adb/modules/berry_fcm_ksu/bin/ctl.sh log'。若 fcm_check_start 无对应 fcm_check_end，检查调用可能未返回；若 wake_timer_armed 无 wake_timer_fired，定时器没有完成唤醒；若长时间无 fcm_check_start，需检查 service.lock/pid、/proc/PID/cmdline、/proc/PID/cgroup 与 service_cgroup_*，区分服务退出、冻结或启动失败。单凭 sample_gap 不能确定原因。

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
- 普通 GMS 443 不计为 FCM；若 GMS UID 或 TCP/TCP6 来源不可用，页面会报告 unknown，不会凭空推断断联。
- 仅监控 Android 用户 0 的 GMS 主包 UID；厂商私有冻结、网络限制不保证可解除。
- Doze 白名单和后台数据放行可能增加待机耗电 / 流量。
