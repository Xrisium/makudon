# 任务：准备 Makudon 0.1.0 首次 GitHub 发布

- 状态：正式 APK 已签名，待用户上传发布
- 用户目标与范围：定版 0.1.0，验证 Release 构建并交接 GitHub Release 发布步骤。
- 不在范围：改动产品功能或引入自动发布流水线。
- 相关规范：[Git 工作流](../git-workflow.md)、[设备约定](../device-policy.md)。

## 验收条件

- Release APK 使用 `io.github.xrisium.makudon`、`versionName=0.1.0` 和递增的 `versionCode=2`。
- 通过项目 Check，验证 Release 包与签名流程，记录正式签名和远程发布状态。
- 中英文 README 提供正式 APK 安装入口，记录本机签名和网页发布的步骤。

## 实施步骤

1. 更新版本及 Release 构建入口，使用现有 JDK 与 SDK。
2. 构建并验证 APK，核对正式包身份。
3. 记录验证与发布交接，创建本地版本标签。

## 验证与交接

- 本机 JDK 17 `Check -Offline` 和 `BuildRelease -Offline` 成功，Debug / Release 严格 Lint 通过。Debug 单测任务为 `UP-TO-DATE`，既有 XML 结果 152 项、零失败 / 错误 / 跳过。
- `aapt2` 核对正式包名、版本、构建号、`minSdk=26`、`targetSdk=36` 以及非可调试包。使用临时测试密钥完成 `zipalign -P 16`、签名和 `apksigner verify`，v2 / v3 验签通过。
- 配置的 `Android_API_36` 初次启动超时，跳过快照冷启动后恢复。`emulator-5554` 安装临时签名包成功，`am start -W` 回报冷启动成功，系统报告主活动在前台且应用进程存活。测试包已清理；真机未安装。
- 未签名 `app-release-unsigned.apk` 为 76,419,288 字节，SHA-256 为 `EF25038648F9654CC8748074ECB862511A9F17306B185AB1AAB16926AAB44EBB`。正式签名后应使用新文件的 SHA-256。
- 2026-11-07 [GitHub Releases](https://github.com/Xrisium/makudon/releases) 检查时尚无公开版本；当时本机 Git 远程查询因 TLS 握手失败未取得分支 / 标签状态。
- 2026-11-11 用户授权直接签名。创建长期 RSA 4096 / PKCS12 发布密钥，别名 `makudon`、有效期 10,000 天。密钥 `makudon-release.p12` 和随机密码文件 `makudon-release-password.txt` 保存在 `%USERPROFILE%/.android/makudon-signing/`，目录限制为当前用户及 SYSTEM 可访问。用户需备份并在后续更新继续使用。
- 正式 APK `.local/release/Makudon-0.1.0.apk` 为 76,473,249 字节，SHA-256 为 `3B2BAD6E899B906807C39AF72071462AE0EE94E1DE213E62A528C0FCD3924664`，旁附 `.sha256`。v2 / v3 验签、16 KiB ZIP 对齐及非调试的 0.1.0 / 构建号 2 包身份核对通过，证书 SHA-256 为 `AD3706A2271363143AE516D8F677D84A5BC1B19438E303B7B90C14E083C59EAB`。本次仅签名与校验，复用原构建验证，设备验收沿用前述范围。
- 用户下一步推送已定版的源码和标签，再按 [Git 工作流](../git-workflow.md#手动发布-android-apk)上传正式 APK。

## GitHub Release 文案

标题使用 `Makudon 0.1.0`。

> Makudon 的首个公开版本。支持本地 Markdown 和纯文本写作，提供源码、排版和阅读三种 Markdown 视图，以及标题大纲、查找替换、目录、回收站和 ZIP 备份恢复。支持 Android 8.0 及以上，界面提供简体中文和英文。
>
> 在 Assets 下载 `Makudon-0.1.0.apk`。若已使用 Makudon Dev，可从开发版导出 ZIP，再在正式版恢复文稿。
>
> Makudon’s first public release supports local Markdown and plain-text writing. It includes Source, Formatted, and Reading views, a heading outline, find and replace, folders, a recycle bin, and ZIP backup and restore. Requires Android 8.0 or later. Available in English and Simplified Chinese.
>
> Download `Makudon-0.1.0.apk` from Assets. To bring documents over from Makudon Dev, export a ZIP from the development build and restore it in the release.
