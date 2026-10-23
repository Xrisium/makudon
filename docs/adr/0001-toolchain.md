# ADR 0001：复用工具链与轻量单模块工程

- 日期：2026-10-24
- 状态：接受

## 背景

用户希望 Codex 开发 Android 文本编辑器，在 VS Code 审查，并频繁手动运行。现有共享环境已经有原生 Compose 示例、JDK 17、SDK 36、Gradle 缓存和可用 AVD；不希望安装 Android Studio 等重量级工具。

## 决定

复用 AGP 9.0.1 + Gradle 9.1.0 + JDK 17 + 内置 Kotlin/Compose compiler 2.2.10 + Compose BOM 2025.08.01。使用单 `:app` 模块、Kotlin DSL、版本目录、标准 Wrapper。

选择 CLI 构建和设备测试作为事实依据，VS Code 作为日常编辑器。Windows 项目脚本提供配置、检查、模拟器和真机入口。开发版独立包名，安装保留数据。

## 取舍

- 优点：复用缓存，少安装，用户能直接阅读修改；升级范围明确。
- 代价：VS Code 的 Kotlin Android 项目支持仍是实验性质，没有 Android Studio 完整调试/Compose 预览体验。
- 不采用 Flutter / React Native / KMP：当前只有 Android，跨平台运行时和工具链没有明确收益。
- 不现在引入 DI、数据库、多模块和 Markdown 库：还没有需要它们的功能。
- 这些不是“最新版本”的声明；升级须在明确收益、官方兼容检查与可回退验证后进行。

## 验证依据

[AGP 官方兼容表](https://developer.android.com/build/releases/agp-9-0-0-release-notes) 与本机样例一致。实际项目构建/模拟器验证以 [状态](../status.md) 及 [基础验收](../testing/2026-10-24-foundation.md) 为准。
