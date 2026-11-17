# BerryFCM Guardian APK 0.1.0-alpha

普通 Android APK 实现线：管理用户明确选择的无障碍服务，并通过用户选择的 Root 或 ADB Shell 会话执行设置命令。默认授权入口为无线 ADB，Root 可选。

## 授权方式

- **Root**：调用 `su -c`，需在 KernelSU / Magisk 中由用户明确授予。
- **无线 ADB**：首次在开发者选项开启无线调试，在 APK 输入设备 IP、配对端口和六位配对码。配对身份保存于 AndroidKeyStore；之后通过 mDNS 尝试重新连接。设备重启后仍要求无线调试保持开启。
- **静态 ADB TCP**：连接用户已经开启的 ADB TCP 端点，填写 host / port。APK 不会自行开启 adbd 端口；不同 Android 版本和 adbd 协议的兼容性必须在目标 ROM 实测。
- **USB / 外部**：APK 不是电脑端 USB ADB Host，不能自行打开 USB 调试。可先由电脑通过 USB ADB 初始化设备端 TCP ADB，再回到 APK 连接对应端点；具体端口和协议由设备端 adbd 决定。
- **手动**：跳转系统无障碍设置，由用户自行启用或关闭。

## 无障碍服务管理

应用扫描已安装应用声明 `android.permission.BIND_ACCESSIBILITY_SERVICE` 的服务。只有用户勾选的组件才会被管理。

应用通过 Root 或已建立的 ADB Shell 会话更新 `enabled_accessibility_services`：先移除本工具上次管理的组件，再合并本次选择，保留其它无障碍服务。取消勾选后再次应用，即移除该组件。每次更新后会重新读取系统设置进行核对；系统未确认时显示失败，不把命令执行当成成功。

## 开机恢复与快捷磁贴

- 开机恢复默认关闭，用户主动开启后才执行。
- Root 模式要求 Root 授权仍有效。
- 无线 ADB 模式要求系统无线调试开启且设备已配对；启动时尝试 mDNS 重连。
- 静态端口模式要求 adbd 在重启后仍监听指定地址和端口。
- Android / OriginOS 可能限制开机广播、后台启动、通知或网络发现；不保证每台 ROM 都能自动恢复。
- Android 13+ 可以请求系统添加 Quick Settings Tile；系统确认由用户决定。

## 构建

```bash
gradle --no-daemon :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 构建的也是 debug APK，仅用于测试，不是 Play 商店发布包。

## 安全边界

- ADB Shell 接近设备 Shell UID 权限，只配对自己的设备，并在不再需要时从系统无线调试页面撤销配对。
- ADB Shell 会话断开后，Shell 权限不会永久变成普通 APK 权限。`WRITE_SECURE_SETTINGS` 也不会因一次 ADB 授权而永久授予普通 APK。
- 没有 Root / ADB Shell 时，普通 APK 不能静默启用其它应用的无障碍服务，只能打开系统设置让用户操作。
- 本 alpha 版本不承诺普通 APK 可读取完整 GMS UID Socket；FCM 完整连接监控以 KernelSU 版为准。
