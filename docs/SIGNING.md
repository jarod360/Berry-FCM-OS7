# BerryFCM Android APK 固定签名与升级约定

> 更新：2026-11-18。**原文件中关于旧证书指纹、GitHub Secrets 尚未配置的说法已经失效。** 本文以已成功的 GitHub Actions 签名验证为准。

## 签名事实（已通过 Actions）

- 应用包名（applicationId）：`top.zesun.berryfcm`；与现有安装包保持相同。
- 当前固定签名证书 **SHA-256**：`BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615`。
- GitHub Actions Secrets：`BERRYFCM_KEYSTORE_BASE64`、`BERRYFCM_KEYSTORE_PASSWORD`、`BERRYFCM_KEY_ALIAS`、`BERRYFCM_KEY_PASSWORD`。用户已设置，**不需要再填写或重新生成密钥**。GitHub 无法通过 API 读取其明文，也不应该要求在聊天中重新提供。
- 工作流 `.github/workflows/build-apk.yml` 会解码 P12、检查 keytool 可读性、必要时规范化 Runner 临时 P12 的条目别名、导出证书并匹配上述指纹，之后使用该密钥构建 Release APK、执行 `apksigner verify`、上传 Artifact。
- **实际通过**：[Actions #44122890970](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44122890970) 的 `Compile APK sources`、`Prepare persistent release signing key`、`Build stable-signed APK`、`Verify signature and stage APK`、`Upload stable-signed APK` 均成功。上传 Artifact `BerryFCM-Guardian-v0.1.0-alpha.24`。

**最新再次验证：** [Actions #44132037143](https://github.com/jarod360/Berry-FCM-OS7/actions/runs/44132037143) 同样通过签名、验签及上传，Artifact `BerryFCM-Guardian-v0.1.0-alpha.26`。无需重新设置 Secrets。

## 禁止的操作

- **不重新生成签名密钥、不覆盖现有签名、不给用户重新发送四项 Secrets 填写教程**，除非后续 Actions 真有可信证据显示发生轮换/失效且用户明确要求。
- 不把 .p12、密码、私钥或 Base64 粘进 Git 仓库、日志、Markdown、Release 或公开 Issue。
- 不拿 P12 文件 SHA-256 代替证书 SHA-256。
- 不发布使用 GitHub Runner 临时 Debug key 签名的 APK。
- 用户 Windows 文件仅保存在 `D:\Codex`；云端 Runner 的临时存储不等于用户本机 C 盘。

## 覆盖升级契约

- 版本号 `versionCode` 必须大于已安装版本；保持同包名和相同签名。当前 Gradle 使用 GitHub Actions 的 `github.run_number` 作为可安装包 versionCode。
- 源码 Debug 编译仅用于校验，**用户可安装的 APK 必须出自固定签名 Release 构建**。更新时先验证证书，再上传。
- 用户端更新支持目标：GitHub Releases 作为唯一正式更新源；比较版本、显示更新说明、下载对应 APK，核对包名/签名及版本号后交给系统安装器。Android 系统可能要求用户允许“安装未知应用”，不提供静默安装。
- **截至 2026-11-18，仓库 GitHub Releases 为空**：只有 Actions Artifact；不可宣称已有在线升级。
- 用户必须线下备份 P12 和密码；丢失签名私钥不能凭生成新的同名密钥来覆盖升级。

## 相关文件

- [PRODUCT_PLAN.md](PRODUCT_PLAN.md)：产品权限、发布路线与验收。
- [HANDOFF.md](HANDOFF.md)：当前工作进度与构建事实。
- [Actions Build Android APK](https://github.com/jarod360/Berry-FCM-OS7/actions/workflows/build-apk.yml)。

### Release 流水线（新增设计，待 Actions 验证）

`Build Android APK` 增加手动 `workflow_dispatch` 的 `publish_release` 布尔输入，默认关闭。仅用户显式勾选后，下载同次通过签名验证的 APK Artifact，并以相同 run_number 发布 `v0.1.0-alpha.N` GitHub prerelease。普通 `push` 仅产生 Actions Artifact，不向 APK 更新接口提供半成品。
