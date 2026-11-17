# APK 固定签名密钥配置

BerryFCM Guardian 的所有可分发 APK 必须使用同一个 PKCS12 Release Keystore。包名固定为 `top.zesun.berryfcm`。只生成一次密钥，之后所有 Actions 构建均从 GitHub Secrets 读取同一密钥签名。

> 当前尚无可确认已创建并配置的 Release keystore。此前 APK Actions 只完成 Debug 源码编译检查，未上传 Debug APK，也未生成可安装的 Release APK。不要把 CI 编译成功误认为签名已配置。

## 1. 在 Windows 生成唯一密钥

先将仓库克隆到 `D:\Codex\Berry-FCM-OS7`，然后在 PowerShell 执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\New-BerryFCMSigningKey.ps1
```

脚本要求 JDK 17+ 的 `keytool` 可用。它会：

- 创建 `signing\berryfcm-release.p12`（PKCS12、RSA 3072、alias `berryfcm`）。
- 生成高强度随机密码，保存到本地 `signing\SIGNING-SETUP.txt`。
- 将 keystore Base64 复制到剪贴板。
- 如果 keystore 已存在则拒绝覆盖，避免误换签名身份。

`signing\` 目录已加入 `.gitignore`，不得提交密钥、密码或 Base64 文件。将整个 signing 目录至少离线备份两份。

## 2. 配置 GitHub Actions Secrets

打开仓库 Settings → Secrets and variables → Actions → New repository secret，添加：

| Secret 名称 | 值 |
|---|---|
| `BERRYFCM_KEYSTORE_BASE64` | 脚本复制到剪贴板的 Base64 内容 |
| `BERRYFCM_KEYSTORE_PASSWORD` | `SIGNING-SETUP.txt` 中的 Keystore password |
| `BERRYFCM_KEY_ALIAS` | `berryfcm` |
| `BERRYFCM_KEY_PASSWORD` | `SIGNING-SETUP.txt` 中的 Key password |

脚本当前使用相同的随机密码作为 store password 和 key password。不要把密码粘贴到 Issue、代码、提交记录或公开聊天。

## 3. 构建与覆盖安装规则

- APK 工作流先执行 Debug 编译检查；Debug APK 不上传、不用于安装。
- 四项 Secrets 全部存在且 keystore 验证成功后，才构建并上传 Release APK。
- 所有 Release APK 使用同一 PKCS12 文件、alias 和证书。
- `applicationId` 固定为 `top.zesun.berryfcm`，不得随意修改。
- `versionCode` 使用 APK 工作流递增的 `run_number`，避免 Android 因版本号倒退拒绝升级。
- 相同包名 + 相同签名证书 + 递增 versionCode，后续版本可以覆盖安装并保留应用数据。
- 如果设备上已经安装过由另一把密钥签名的同包名 APK，Android 会拒绝覆盖；首次切换签名身份可能需要卸载旧包。之后必须一直使用本密钥。

## 4. 丢失或更换密钥

Keystore 丢失后，无法再生成能覆盖当前固定密钥版本的 APK。不能通过改版本号解决签名不匹配。若确实需要换钥匙，应视为不兼容迁移，用户需要卸载旧应用后重新安装，应用私有数据也可能丢失。

不要把 `.p12` 放入公开 Release、Actions Artifact 或仓库。GitHub Actions 只保存加密 Secrets；构建时在 Runner 临时目录解码，任务结束后由 Runner 回收。
