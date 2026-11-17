# BerryFCM Guardian APK 固定签名与覆盖升级

所有可分发 APK 使用**同一把本地生成的 PKCS12 Release 密钥**，包名固定为 `top.zesun.berryfcm`。**不要再生成第二把密钥**，不能使用 GitHub Runner 每次创建的 Debug 密钥分发安装包。

## 已确定的正式签名身份（2026-11-17）

用户已在 Windows 中自行创建并验证（私钥存在、RSA 3072）：

- 本地目录：`D:\Codex\Berry-FCM-Signing`
- 密钥文件：`berryfcm-release.p12`
- 本地密码说明：`SIGNING-SETUP.txt`
- PKCS12 别名：`berryfcm`（PowerShell 的 `FriendlyName` 已验证；仍需通过 Java `keytool` 复核实际 alias）
- **证书 SHA-256**：`D6506E38980A564BE06C101247AB54AA045AB565C2A5E935E7D47918084E5ECA`

这只是公开的**证书指纹**，不是私钥或密码。该指纹已固定在 `.github/workflows/build-apk.yml`，如果后续 Secrets 被错误换成另一把密钥，构建会直接失败，避免产生无法覆盖更新的 APK。

**重要：** 此处的“已生成”只表示本地密钥存在且 PowerShell 私钥检查通过；尚不代表 GitHub Actions Secrets 已配置、Java keytool 可成功读取，或 APK 已经签名发布。

## 1. 先在本地核对 PKCS12 的 Java 别名

在 PowerShell 执行（不会输出密码）：

```powershell
$dir = 'D:\Codex\Berry-FCM-Signing'
$ks = Join-Path $dir 'berryfcm-release.p12'
$info = Join-Path $dir 'SIGNING-SETUP.txt'
if (!(Test-Path $ks) -or !(Test-Path $info)) { throw '原密钥或说明文件不存在' }

$pw = ((Get-Content -LiteralPath $info | Where-Object { $_ -like 'StorePassword=*' } | Select-Object -First 1) -split '=', 2)[1]
if (!$pw) { throw '无法读取本地 Keystore 密码' }
& keytool -list -v -storetype PKCS12 -keystore $ks -storepass $pw -alias berryfcm |
    Select-String 'Alias name:|别名:|SHA256:|SHA-256:'
if ($LASTEXITCODE -ne 0) { throw 'Java keytool 无法按 alias berryfcm 读取原密钥，停止配置 Secrets' }
```

核对输出中的证书 SHA-256 与上面的指纹一致；如果 alias 不存在，**不要重新生成密钥**，先定位 PKCS12 实际条目名并修复构建配置。

## 2. 配置 GitHub Actions Secrets（仅需一次）

前往 [仓库 Actions Secrets 设置](https://github.com/jarod360/Berry-FCM-OS7/settings/secrets/actions)，创建以下四项 **Repository secrets**：

| Secret 名称 | 内容 |
|---|---|
| `BERRYFCM_KEYSTORE_BASE64` | 当前 `berryfcm-release.p12` 的 Base64 字符串 |
| `BERRYFCM_KEYSTORE_PASSWORD` | 本地 `StorePassword` |
| `BERRYFCM_KEY_ALIAS` | `berryfcm` |
| `BERRYFCM_KEY_PASSWORD` | 本地 `KeyPassword` |

复制现有密钥的 Base64，无需更换密钥：

```powershell
$ks = 'D:\Codex\Berry-FCM-Signing\berryfcm-release.p12'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)) | Set-Clipboard
```

在 GitHub 保存第一项后，建议清空剪贴板：`Set-Clipboard -Value ''`。密码只在本地文件中查看，不要发到聊天、Issue、Actions 日志、仓库或者 Artifact 中。将 P12 与密码文件离线备份。

GitHub Secrets 的创建需要用户在 GitHub 设置页面自行操作；当前聊天的 GitHub 连接无法访问或写入仓库 Secrets。

## 3. 云编译、签名验证和覆盖安装

[Build Android APK](https://github.com/jarod360/Berry-FCM-OS7/actions/workflows/build-apk.yml)：

1. 先运行不分发的 Debug **编译检查**。
2. 四项 Secrets 完整时，在 Runner 临时目录恢复 PKCS12，并通过 `keytool` 验证证书指纹与上述 SHA-256 完全一致。
3. 用该密钥构建 `assembleRelease`，使用 `apksigner` 验签，仅发布固定签名的 APK Artifact。
4. `applicationId` 保持 `top.zesun.berryfcm`，`versionCode` 使用递增的工作流运行编号。

首次安装应使用这一正式签名包。以后只要**包名相同、签名一致、版本号没有倒退**，就可以覆盖安装并保留应用数据。历史上用其他 Debug / Release 密钥签过的同包名 APK 不能直接覆盖；首次迁移可能需要先卸载旧包。

后续每一次正式发布都要继续使用 `D6506E38…84E5ECA` 这份证书，**不再运行生成工具**。

## 4. 安全与恢复

- 不上传 `.p12`、密码或 keystore Base64 到公开仓库和 Release。
- 签名材料保存在 `D:\Codex\Berry-FCM-Signing`，不移入 C 盘。
- 失去这把私钥后，不能重新生成可覆盖现有 APK 的密钥；保存离线备份。
- 不要卸载已安装的正式签名 APK 来修复普通编译错误；先修复源码和 CI。
