# 第三方与资产来源

- 项目代码：仓库根 LICENSE（MIT）。
- 原始图标：用户提供的根目录 `icon.svg`；Android VectorDrawable 保留原始几何路径，自适应图标仅增加安全边距。
- Gradle Wrapper：从本机已安装的 Gradle 9.1.0 重新生成，脚本保留 Gradle 的 Apache-2.0 声明。Wrapper JAR 随源代码提交，构建产物不提交。
- AndroidX / Compose 等库通过 Google Maven / Maven Central 获取，版本见 `gradle/libs.versions.toml`。首次正式分发前生成并核对实际依赖许可清单。
- 不包含用户文稿、系统镜像、SDK 或第三方二进制应用安装包。
