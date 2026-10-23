# 当前状态

更新日期：2026-10-24。阶段：**工程基础 + 首片需求已确定（非 MVP，功能未实现）**。

## 已实现

- 复用已有 Android SDK / JDK / Gradle 缓存，固定 Kotlin + Compose 工具链。
- 单模块工程、Android 入口、主题、品牌占位页、矢量/自适应图标。
- Setup / Doctor / Build / Check / Run / Emulator / Devices / Logcat / Screenshot 脚本和 VS Code 任务、双击测试入口。
- 仓库局部 Git 设置、忽略/换行规则，GitHub Actions 配置。
- 根 AGENTS.md、专题规范、决策记录、任务与验收模板。

## 本轮设计交付（尚未实施）

- [首个可靠纯文本编辑切片方案](specs/0001-plain-text-editor.md)：单活动编辑器、每稿独立 ID、最小重开列表、单写者保存、生命周期、有限恢复与保障边界。
- [ADR 0002](adr/0002-document-storage.md)：私有正文 + 只读导入副本 / 系统打开关联 + 最小 `.txt` 导出；**状态已由提议改为接受**。
- [D1–D3 决定记录](specs/0001-open-decisions.md)：主存储、文件出入口、10 万 Unicode 码点且 UTF-8 ≤ 1 MiB 基线均已确定，无待批准产品选择。
- [导入与文件关联协议](specs/0002-import-and-file-association.md)：本机纯文本 / `.md` 创建独立私有副本，不改源；重名、切换、MIME、授权、重复请求与失败中断处理。
- [首片验收规范](testing/plain-text-editor-acceptance.md)：扩展为保存、恢复、输入 / 性能、导入、系统关联与设备策略，**功能用例尚未执行**。
- [设备优先约定](device-policy.md)：有真机时仅用真机，无真机才用模拟器，用户主动指定除外。真机授权 / offline / 运行故障不自动回退模拟器。
- 本轮仅修改项目 Markdown 文档（含根 `AGENTS.md`、`README.md`）；未修改功能代码、Manifest、依赖、构建配置或测试脚本。记录见 [初次设计](tasks/0001-plain-text-design.md) 和 [决定同步](tasks/0002-confirm-import-and-device-policy.md)。

## 当前测试入口限制

`android.ps1` 的无参数 Run / Logcat / Screenshot、无参数双击入口与 `Ctrl+Shift+B` 仍选择配置 AVD，尚未自动真机优先。本轮按用户要求只写约定，没有改脚本；Agent 必须先识别当前真机并显式 `-Serial`。后续修改脚本可落实自动分流，不能宣称已实现。

## 验证

- Debug 编译、Android Lint：通过（离线使用本机已有缓存）。
- 单元测试入口：成功执行，但当前为 `NO-SOURCE`；没有业务逻辑测试。
- 模拟器运行、重复安装、数据保留标记与截图验收通过：见 [基础工程验收记录](testing/2026-10-24-foundation.md)。
- Git origin 读取成功；main 跟踪 origin/main；本地初始化与设计基线已提交，尚未推送。
- GitHub CI、真机、API 26 运行、VS Code 图形界面的语言服务导入尚未验收。
- 本轮只读识别到一台已授权 USB 真机：HUAWEI LIO-AN00，设备报告 Android 12 / API 31，默认输入法组件为 `com.sohu.inputmethod.sogou/.SogouIME`。未安装或启动应用，不等于真机功能验收通过；连接状态会变化，后续需重新识别。
- 上述运行验证属于工程基础阶段；本轮文档工作没有重新运行构建或设备测试，不能将历史结果视为编辑切片已验证。
- 本次提交前尝试 `Check`，但本机未缓存 Gradle 9.1.0 Wrapper 分发包，联网下载连接超时，未能复验构建、单测或 Lint；不影响以上历史验收记录。

## 未实现

文稿编辑、最小列表、存储、自动保存、Markdown 预览、本机导入副本、系统文件打开关联、导出、备份恢复、正式签名发布。domain/data 仅有边界说明，没有实现。

## 下一步

产品选择已明确，等待用户给出具体实施任务后分步实现；建议先做现有工具链下的真机输入体验验证。可复制指令见 [路线图](roadmap.md)。文档确认不自动开启开发；首片功能仍未开始。
