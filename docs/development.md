# 开发环境与 VS Code

## 已复用的工具链

| 组件 | 固定版本 / 路径 |
| --- | --- |
| SDK | `%USERPROFILE%\DevTools\Android\Sdk` |
| 构建 JDK | `%USERPROFILE%\DevTools\Android\Jdk\17.0.18+8` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Build Tools | 36.0.0 |
| Gradle Wrapper | 9.1.0，发行包 SHA256 已锁定 |
| Android Gradle Plugin | 9.0.1 |
| Kotlin / Compose compiler | 2.2.10，Kotlin 由 AGP 内置 |
| Compose BOM / Activity Compose | 2025.08.01 / 1.10.1 |
| AVD | Android_API_36，现有 API 36 Google APIs x86_64 |
| 编辑器扩展 | 已安装 Kotlin by JetBrains；当前检测为 0.0.27 |
| 脚本环境 | 已安装 PowerShell 7 (`pwsh`) |

这是一组为复用本机缓存而固定的兼容组合，不声称是最新版本。无需 Android Studio、全局 Gradle、额外 JDK、NDK 或 CMake。

## 初始化与常用命令

在仓库根目录执行。设备操作先按 [设备选择约定](device-policy.md) 识别：有真机时仅用真机，无真机才用模拟器，用户明确指定除外。

```powershell
pwsh -NoProfile -File scripts/android.ps1 -Action Setup
pwsh -NoProfile -File scripts/android.ps1 -Action Doctor
pwsh -NoProfile -File scripts/android.ps1 -Action Build
pwsh -NoProfile -File scripts/android.ps1 -Action Check
pwsh -NoProfile -File scripts/android.ps1 -Action Devices
# 有真机时：替换为本次识别的目标序列号
pwsh -NoProfile -File scripts/android.ps1 -Action Run -Serial YOUR_SERIAL
```

`Setup` 只生成/更新 `.local/android.json`、`.local/Makudon.code-workspace` 和 `local.properties` 的 SDK 配置，不下载安装工具。三个文件都不进入 Git。已生成的本机配置无需每次重复初始化。

其他机器或自定义路径：

```powershell
pwsh -NoProfile -File scripts/android.ps1 -Action Setup -SdkPath 'D:\Android\Sdk' -JdkPath 'D:\Java\jdk-17' -AvdName 'My_AVD'
```

脚本解析优先级：显式参数 → `MAKUDON_SDK` / `MAKUDON_JDK` / `MAKUDON_AVD` → `.local/android.json` → 本机约定路径。不依赖终端默认 Java，也不修改全局环境变量。`-Offline` 用于已有完整缓存的断网验证；缓存缺失时去掉该参数，Gradle 只下载所需依赖。

## VS Code

推荐 `code .local/Makudon.code-workspace`，或从 VS Code 打开这个工作区文件。也可直接打开项目根目录，所有任务仍能正确选择 JDK。

- `Ctrl+Shift+B`：默认构建、安装并启动到模拟器。
- 上述快捷键仍是现有的模拟器路径，尚未自动真机优先；有真机时改用 `Makudon: Run on selected device` 或显式 `-Serial`，不触发默认任务。只有无真机或用户主动指定模拟器时才用 `Ctrl+Shift+B`。
- 命令面板 → `Tasks: Run Task`：选择 Check、Build APK、Devices、Screenshot、Logcat 等。
- 打开 `MainActivity.kt` 激活现有 JetBrains Kotlin 扩展。首次工作区信任、扩展条款等由用户在界面确认。
- 从生成工作区启动的集成终端自带 `JAVA_HOME` / `ANDROID_HOME`。如果语言服务导入不正确，先 `Check` 判断是否为真实编译问题，然后重载窗口/工作区并检查 Kotlin 输出。
- 官方 Kotlin LSP 对 Android Gradle 工程仍属实验性支持；可手动编辑、跳转和检查代码，但不能承诺 Android Studio 同等的调试器、Compose 预览或布局检查器。当前不配置无效的 F5 调试入口。
- 使用本地 Noto Sans SC，UI 500 / 正文 450、独立字体角色与多主题；按设备约定观察，有真机时仅用真机，用户指定模拟器时例外。

普通终端直接运行 Wrapper 时，需要先设置本进程的 `JAVA_HOME`：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\DevTools\Android\Jdk\17.0.18+8"
.\gradlew.bat :app:assembleDebug
```

macOS/Linux 用户可用 JDK 17 + SDK 36 + `bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；Windows 测试脚本不跨平台。SDK 路径可通过本机 `local.properties` 或 `ANDROID_HOME` 指定。

## 缓存、网络与权限

工作区显式增加 lifecycle-viewmodel-android 2.9.0（进程内单编辑所有者）与 kotlinx-coroutines-android 1.8.1（后台 I/O、单写者和生命周期保存），继续使用本机缓存的稳定版本；虚拟时钟 coroutines-test 仅进入测试。替代方案是 Activity 自持状态及手写线程 / 队列，但会增加重建、调度和故障测试成本，因此未采用。未引入 DI、数据库、导航或 Markdown 运行库。

依赖独立净增量尚未剥离测量；当前 debug APK 约 20.9 MiB，其中完整字体压缩约 10.75 MiB 是主要素材体积。字体选择与来源见 [Android 集成](../assets/android/README.md)。工具链没有升级；`lint.xml` 只在固定版本声明文件忽略“有新版本”的提示，兼容性与代码检查仍全部启用。

- 继续使用用户现有 `.gradle` 缓存和代理配置。本机共享配置此前使用 `127.0.0.1:7890`，仓库不写代理地址或凭据。
- 下载失败先检查网络/现有代理，不改用不明 Maven 镜像，不删除全部缓存。
- Codex 沙箱可能阻止 Gradle 缓存、ADB 用户目录或 Git 凭据访问；此时需要工具的受控权限升级，不应安装另一套环境绕过。
- ADB、模拟器和 Gradle 运行会写各自正常的用户缓存/AVD 数据；项目脚本只生成本仓库 `.local` 日志和构建产物。

## 官方依据

- [AGP 9.0 兼容表与内置 Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Kotlin Language Server / VS Code](https://kotlinlang.org/docs/kotlin-lsp.html)
- [Emulator 命令行](https://developer.android.com/studio/run/emulator-commandline)

本机初始安装说明另见 `C:\Users\chris\DevTools\Android\README.zh-CN.md`；其历史验收状态不等于本项目当前验收结果。
