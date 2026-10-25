# Makudon

轻量、本地优先的 Android Markdown / 纯文本编辑器，用于笔记、日记和长篇写作。

**当前阶段：原生工作区开发版。** 已接入目录 / 最近 / 设置、私有文稿、全自动保存、文件导入导出、目录整理与有限 Markdown 排版编辑。完整富文本、独立备份和真机体验仍待后续验证。实际结果见 [当前状态](docs/status.md)。

## 在这台 Windows 电脑上使用

工具链复用 `C:\Users\chris\DevTools\Android`，无需 Android Studio。

1. 在 VS Code 打开 `.local/Makudon.code-workspace`（本机初始化生成）。也可以直接打开仓库目录。
2. 先运行 VS Code 任务 **Makudon: Devices**。有真机连接时仅用真机：选择 **Makudon: Run on selected device** 并输入本次真机序列号，或执行 `test-android.cmd -Serial YOUR_SERIAL`。
3. 只有无真机连接或你明确要求模拟器时，才按 **Ctrl+Shift+B** 或无参数双击 **test-android.cmd**，复用 / 启动 `Android_API_36`。

覆盖安装保留应用数据；应用进程会重启，请先保存编辑内容。[设备选择约定](docs/device-policy.md) 已改为真机优先；**现有快捷入口尚未实现自动侦测**，有真机时须显式指定。真机离线 / 未授权或运行失败时，不自动转向模拟器。

首次克隆或更换工具位置时：

```powershell
pwsh -NoProfile -File scripts/android.ps1 -Action Setup
pwsh -NoProfile -File scripts/android.ps1 -Action Doctor
pwsh -NoProfile -File scripts/android.ps1 -Action Check
```

详细路径覆盖、真机、日志、截图与故障处理见 [开发环境](docs/development.md) 和 [测试指南](docs/testing.md)。

## 从哪里读代码

- `MainActivity.kt`：Android 入口。
- `app/MakudonApp.kt`：主题和应用装配入口。
- `feature/workspace/`：工作区、目录、新建、编辑器、ViewModel 与保存协调。
- `domain/Workspace.kt`、`data/WorkspaceStore.kt`：规则、私有目录与正文存储。
- `ui/theme/Theme.kt`：主题、UI 与正文字体角色。
- `gradle/libs.versions.toml`：依赖版本。

代码位于 `app/src/main/kotlin/io/github/xrisium/makudon/`。工程采用一个 Gradle 模块，后续按功能扩展；详见 [架构](docs/architecture.md)。

## 项目文档

[文档索引](docs/README.md) · [当前状态](docs/status.md) · [下一步与可复制指令](docs/roadmap.md) · [Agent 约定](AGENTS.md) · [Git 工作流](docs/git-workflow.md)

应用代码遵循 [MIT](LICENSE)。`icon.svg` 是原始图标，Android 矢量版本沿用其路径；Gradle Wrapper 文件保留自身 Apache-2.0 许可声明，见 [第三方说明](docs/third-party.md)。
