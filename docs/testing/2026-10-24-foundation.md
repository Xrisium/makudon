# 基础工程验收 — 2026-10-24

## 环境

- Windows、本机 JDK 17.0.18+8、SDK 36、Build Tools 36.0.0。
- AGP 9.0.1 / Gradle 9.1.0 / Kotlin 与 Compose compiler 2.2.10。
- 现有 AVD Android_API_36，WHPX 可用。
- 用户已安装 Kotlin by JetBrains 0.0.27；未安装额外 IDE 或开发工具。

## 已验证

| 验证 | 结果 |
| --- | --- |
| Setup 生成本机路径与工作区 | 通过 |
| PowerShell 语法 / VS Code JSON | 通过 |
| assembleDebug（offline） | 通过 |
| lintDebug（offline） | 通过，0 issues |
| testDebugUnitTest | NO-SOURCE，当前没有业务单测 |
| origin 远程分支读取 | 通过 |
| 忽略本机配置、缓存和 APK | 通过 |
| Doctor / JDK / Gradle / AVD / ADB | 通过 |
| Run 启动现有 AVD、安装、打开应用 | 通过，emulator-5554 / API 36 |
| test-android.cmd 再次运行 | 通过，复用同一 AVD；36 个构建任务均 UP-TO-DATE |
| 覆盖安装保留数据 | 通过：run-as 在私有 files 中创建本次专用空标记，重装后存在，随后移除 |
| Screenshot 入口与图片检查 | 通过：白底黑色图标、标题、系统栏可见 |
| 文档本地链接与 git diff --check | 通过 |

## 本次产物

- 测试包：`io.github.xrisium.makudon.debug`（Makudon Dev）。
- APK：9,797,029 字节；这是未经发布优化的 debug 包，不作为正式包体积基准。
- APK SHA256：`A9D8744A16B97F0B04AE72D1EC6EA7CF199A6C5F73139271C6329183FFABFE1B`。
- Git 基点：`eaf50dcbad74620c2a50c1b858c5ac34ffaa1947`；初始化内容尚未提交，dirty=true。
- 最近安装：2026-10-24 04:23（Asia/Shanghai），详情在 `.local/last-run.json`。
- 本次截图：`.local/screenshots/makudon-20261024-042334-213.png`（不提交）。
- 不新增 Android Studio、JDK、SDK、系统镜像或全局 Gradle；编译依赖全部复用本机缓存。

## 限制

只有静态品牌占位页；无编辑能力。未做真机、最低 API 26、不同输入法、深色模式、多个同时在线设备或 VS Code 语言服务 UI 验收。持续 Logcat 入口已配置，未人工长时间观察。GitHub Actions 仅写入配置，尚未推送运行。默认 AVD 的系统时区未调整，截图时钟不代表本机记录时区。
