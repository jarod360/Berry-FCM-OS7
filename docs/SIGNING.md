# APK 固定签名密钥配置

BerryFCM Guardian 使用一个长期固定的 PKCS12 Release Keystore。每次 GitHub Actions 构建都使用同一证书签名，保证相同包名 `top.zesun.berryfcm` 的后续 APK 可以覆盖升级，并保留应用数据与 AndroidKeyStore ADB 身份。

## 1. 保存密钥

将交付包中的 `berryfcm-release.p12` 保存到本机：

`D:\Codex\Berry-FCM-OS7\signing\berryfcm-release.p12`

同时安全保存密钥包中的 `SIGNING-SETUP.txt`。不要把 `.p12`、密码文件或 Base64 内容提交到 GitHub。

证书 SHA-256 指纹：

`0B:F7:CF:9E:CF:46:8C:FC:8C:CC:C4:28:BE:FC:2C:21:9A:D9:C7:32:23:28:0C:93:77:BB:0D:63:F8:B5:1C:C9`

## 2. 添加 GitHub Actions Secrets

打开仓库 Settings → Secrets and variables → Actions → New repository secret，添加：

| Secret 名称 | 值 |
|---|---|
| `BERRYFCM_KEYSTORE_BASE64` | `berryfcm-release.p12` 文件的 Base64 内容 |
| `BERRYFCM_KEYSTORE_PASSWORD` | `SIGNING-SETUP.txt` 中的 Keystore password |
| `BERRYFCM_KEY_ALIAS` | `berryfcm` |
| `BERRYFCM_KEY_PASSWORD` | `SIGNING-SETUP.txt` 中的 Key password |

PowerShell 生成 Base64 并复制到剪贴板：

```powershell
$ks = 'D:\Codex\Berry-FCM-OS7\signing\berryfcm-release.p12'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)) | Set-Clipboard
```

把剪贴板内容粘贴到 `BERRYFCM_KEYSTORE_BASE64`，其余三个 Secret 按密钥包中的说明填写。

## 3. 构建与升级规则

- Actions 的 Debug 构建只用于编译检查，不上传、不用于安装。
- 只有四项签名 Secret 均存在时，才生成并上传固定密钥签名的 Release APK。
- `versionCode` 使用 GitHub APK 工作流递增的 `run_number`，每次新运行自动提高，避免 Android 拒绝降级安装。
- `applicationId` 固定为 `top.zesun.berryfcm`，不得随意更改。
- 相同 keystore + 相同 applicationId + 递增 versionCode，后续 APK 可直接覆盖升级。
- 若设备上曾安装过由其它密钥签名的同包名 APK，首次切换到本密钥时可能需要卸载旧 APK 一次；之后统一使用本密钥即可覆盖升级。

## 4. 密钥保管

Keystore 丢失后，无法再生成能覆盖当前安装版本的更新包。至少保留两份离线备份；不要上传到公开仓库、Release Artifact、Issue 或聊天日志。GitHub Actions 只保存加密 Secrets，工作流运行时在临时目录解码，用后由 Runner 销毁。
