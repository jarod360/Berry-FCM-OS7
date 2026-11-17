# Berry-FCM-OS7 / BerryFCM Guardian

面向 Android / OriginOS 的 **FCM 连接监控、受控恢复、应用守护与后台诊断** 开源项目。同一仓库维护 **Android APK** 和 **KernelSU 模块** 两条独立产线；当前重点先完善 APK，完成实机验收后再把可靠改进回移 KernelSU。

> 项目仍处于 alpha / 实机验证阶段。**GitHub Actions 构建成功只证明代码已编译和固定签名，不代表已经验证过长时间后台存活或所有 FCM 断联都能捕获。**

## 从这里开始

- **[统一目标、功能清单、权限架构与开发阶段](docs/PRODUCT_PLAN.md)**：唯一产品基线；所有新增/删除功能先据此评估。
- **[实时开发交接、最新构建和下一步](docs/HANDOFF.md)**：新会话可直接继续；每次代码提交更新。
- [APK 固定签名和覆盖升级约定](docs/SIGNING.md)
- [KernelSU 功能说明](module/README.md) · [APK 当前技术说明（历史基础，待逐步跟随新架构更新）](app/README.md)

## 当前代码与实际交付

| 交付物 | 已有源码 | 验证边界 |
| --- | --- | --- |
| Android APK | Root/ADB 授权、前台 FCM 监控 Service、复用的 Shell 状态机、监测页面、初步后台权限入口、图标；新版模块化页面（概览/授权/监控/应用守护/后台保活/诊断/设置/更新）已编写，编译与实机功能待验证 | [Actions #44132037143](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44132037143) 监控模拟测试、编译、固定签名及 Artifact 上传通过（alpha.26）；**功能尚未完成手机实机验证** |
| KernelSU | FCM TCP 5228–5230 / 可信 mtalk 443 观察、事件时间线、请求重连、stopped 应用守护、Doze/网络策略诊断、WebUI | v0.5.4-test，目标手机上短暂断联漏检、采样连续性仍需分析 |

**注意：** GitHub Actions Artifact 与 GitHub Release 是不同对象。目前仓库尚未发布正式 Release，应用内检查更新仍为下一阶段功能。

## 开发原则

1. 普通 Android API 优先；需要特权能力时通过用户授权的 ADB Shell/Root，**不为可由 ADB 实现的操作添加无障碍服务**。
2. GMS Socket 的存在不代表 FCM 已认证/必达；权限不足/采样暂停显示“未知”，不是“离线/一直在线”。广播返回不等于连接已恢复。
3. Root/ADB 身份、厂商设置和后台服务是独立机制；不承诺永久存活或普通 Shell 能读取所有 GMS Socket。
4. 不擅自改 DNS、VPN、代理、hosts 和路由；不自动强制重启 Google Play 服务。
5. 只使用仓库 GitHub Actions 构建；正式 APK 必须保持包名 `top.zesun.berryfcm` 和已经验证的同一签名。密钥/密码不得提交 Git。
6. 所有需求与进度持续写入 `docs/PRODUCT_PLAN.md` 与 `docs/HANDOFF.md`，在当前聊天和 GitHub 内完成开发，不跳转 Work。

## 项目位置

```text
app/                       Android APK
module/                    KernelSU 模块
tests/                     KernelSU 状态机模拟测试
.github/workflows/         APK/KSU 云编译
docs/PRODUCT_PLAN.md       产品基线
docs/HANDOFF.md            运行中的交接记录
docs/SIGNING.md            稳定签名和升级约定
```

**开源参考：** [Shizuku](https://github.com/RikkaApps/Shizuku)（通知栏配对）、[GKD](https://github.com/gkd-kit/gkd)（设置分组与状态）、[FCMFix ColorOS](https://github.com/ByteJuno/fcmfix-coloros)（FCM 监控判定）、[vivo-guardian](https://github.com/Lokside/vivo-guardian)（OriginOS 任务锁定与强制停止行为）、[libadb-android](https://github.com/MuntashirAkon/libadb-android)（ADB 客户端库）。参考思想与交互，不复制专有资源；如复用代码必须遵守许可证。

> 2026-11-18 交互决策：优先完成独立授权模块与通知栏无线 ADB 配对、修复首页顶住状态栏；FCM 自动恢复和下拉快捷磁贴**保留但不依赖无障碍**。详见 [产品计划 §5A](docs/PRODUCT_PLAN.md)。

> 首轮模块化 APK 已通过 [Actions #44138228340](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44138228340) 编译、签名和产物上传（alpha.29）。下一提交增加发布流程的手动开关与结构回归测试；尚未进行手机配对和后台实机验收。

> APK 在模块化界面基础上补齐 KSU 的 90 秒巡检、GMS 心跳与后台计量网络受控放行；新增代码须以新 Actions 为准，进度见 [HANDOFF](docs/HANDOFF.md)。

> 守护能力补齐版 [APK Actions #44140579529](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44140579529) 编译/签名成功，产物 alpha.31；目标手机 mDNS 配对和后台状态仍待实机检查。

> ADB 配对已加入通知栏 mDNS 失败时输入 `端口:六位码` 的兜底，防止 OriginOS 下拉配对流程卡住。后续以新 APK Actions 为准。
