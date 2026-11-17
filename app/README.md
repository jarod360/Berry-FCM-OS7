# BerryFCM Guardian APK 0.1.0-alpha

普通 Android APK：管理用户明确选择的无障碍服务，并使用 Root、ADB Shell 或一次性 `WRITE_SECURE_SETTINGS` 授权完成设置。默认授权入口为无线 ADB，Root 可选。

## 授权方式

- **Root**：调用 `su -c`，需在 KernelSU / Magisk 中由用户明确授予。
- **无线 ADB**：首次在开发者选项开启无线调试，在 APK 输入设备 IP、配对端口和六位配对码。配对身份保存在 AndroidKeyStore；配对后尝试执行 `pm grant top.zesun.berryfcm android.permission.WRITE_SECURE_SETTINGS`。
- **一次性持久授权**：若系统允许授予 `WRITE_SECURE_SETTINGS`，授权会保留在应用权限状态中，通常跨重启有效。此后 APK 可直接通过 `Settings.Secure` 管理服务，不必每次开机重新连接 ADB。是否真正授予以应用内“检查一次性授权状态”为准；OriginOS 版本可能拒绝或限制。
- **静态 ADB TCP**：连接用户已经开启的 ADB TCP 端点，填写 host / port。APK 不会自行开启 adbd 端口；不同 Android 版本和 adbd 协议的兼容性必须在目标 ROM 实测。
- **USB / 外部**：APK 不是电脑端 USB ADB Host。电脑通过 USB 连接手机后，可执行一次：`adb shell pm grant top.zesun.berryfcm android.permission.WRITE_SECURE_SETTINGS`。回到 APK 检查授权。若系统拒绝该授权，可由电脑继续提供 ADB Shell 或初始化 TCP ADB 端点。
- **手动**：跳转系统无障碍设置，由用户自行启用或关闭。

## 无障碍服务管理

应用扫描已安装应用声明 `android.permission.BIND_ACCESSIBILITY_SERVICE` 的服务。只有用户勾选的组件才会被管理。

写入时先移除本工具上次管理的组件，再合并本次选择，保留其它无障碍服务。优先使用已授予的 `WRITE_SECURE_SETTINGS` 直接写入；否则使用当前 Root / ADB Shell。每次更新后按完整组件名及无障碍总开关复查，系统未确认时显示失败。

## 开机恢复与快捷磁贴

- 开机恢复默认关闭，用户主动开启后才执行。
- 如果 `WRITE_SECURE_SETTINGS` 已授予，开机恢复无需重新建立 ADB Shell。
- 如果没有持久授权，Root 模式要求 Root 授权仍有效；无线 ADB 要求系统无线调试开启且设备已配对；静态端口要求 adbd 在重启后仍监听指定端口。
- Android / OriginOS 可能限制开机广播、后台启动、通知或网络发现；不保证每台 ROM 都能自动恢复。
- Android 13+ 可以请求系统添加 Quick Settings Tile；系统确认由用户决定。

## 固定签名与升级

GitHub Actions 的 Debug APK 只用于编译检查，不会作为安装包上传。可安装 APK 使用固定 PKCS12 Release Keystore 签名；每次构建读取相同 GitHub Actions Secrets，并以工作流递增的 `run_number` 作为 `versionCode`。因此相同包名的后续 APK 可以覆盖升级。

**不要丢失签名密钥。** 配置步骤见 [`../docs/SIGNING.md`](../docs/SIGNING.md)。密钥文件不得提交到公开仓库。

## 构建

本地仅做编译检查：

```bash
gradle --no-daemon :app:assembleDebug
```

生成可安装的固定签名包：

```bash
gradle --no-daemon :app:assembleRelease
```

Release 构建必须提供环境变量 `BERRYFCM_KEYSTORE_FILE`、`BERRYFCM_KEYSTORE_PASSWORD`、`BERRYFCM_KEY_ALIAS`、`BERRYFCM_KEY_PASSWORD`。GitHub Actions 自动解码 Secrets 到临时 keystore 文件，构建后验证签名。

## 安全边界

- ADB Shell 接近设备 Shell UID 权限，只配对自己的设备，并在不再需要时从系统无线调试页面撤销配对。
- 一次性 `WRITE_SECURE_SETTINGS` 授权不是普通 APK 默认权限；必须由用户通过 Root / ADB 明确授予，并以应用实际权限检查为准。
- 没有 Root / ADB Shell / `WRITE_SECURE_SETTINGS` 时，普通 APK 不能静默启用其它应用的无障碍服务，只能打开系统设置让用户操作。
- 本 alpha 版本不承诺普通 APK 可读取完整 GMS UID Socket；FCM 完整连接监控以 KernelSU 版为准。
