# Makudon — Agent 工作约定

## 目标与范围

面向个人写作的轻量、本地优先 Android Markdown / 纯文本编辑器。优先舒适编辑、阅读、简单目录与可恢复的文件；不做复杂知识库、账户或自建同步服务。
用户主要通过 Codex 开发，在 VS Code 审查修改，频繁亲自验收。**有真机连接时仅用真机，无真机才用模拟器；用户明确指定时按其要求。** 默认中文交流，代码标识符使用英文。

## 每次开始

1. 阅读 `docs/status.md`、`docs/product.md`，检查 `git status --short --branch`，保留用户已有改动。
2. 按 `docs/README.md` 的任务索引读取相关规范；只有涉及的文档需要加载。
3. 遵守当前用户范围。路线图是候选工作，不代表允许自动推进。当前已有原生工作区开发版，完整 MVP 验收未完成，以 `docs/status.md` 为准。
4. 涉及多步骤功能或数据迁移时，使用 `docs/templates/task.md` 在 `docs/tasks/` 建立短计划；小修直接实施。

## 技术与命令

- Kotlin + Compose，单 `:app` 模块，按功能分包。边界见 `docs/architecture.md`。
- 本机工具来自 `%USERPROFILE%\DevTools\Android`。先复用，禁止为了例行开发自行安装 Android Studio、全局 Gradle、重复 SDK/JDK/系统镜像。
- `pwsh -NoProfile -File scripts/android.ps1 -Action Setup` 初始化本机路径（已配置的机器通常无需再执行）。
- `pwsh -NoProfile -File scripts/android.ps1 -Action Check`：构建、已有单元测试、Android Lint。
- 设备操作前按 `docs/device-policy.md` 重新识别目标；仅一台在线真机时直接选它并显式传 `-Serial`，多台真机请用户选择，不随意取第一台。
- 有真机时仅在真机安装、运行、截图和读日志；unauthorized / offline、断连或测试失败时处理真机问题，禁止自动退回模拟器。仅确认无真机或用户明确要求时才使用模拟器。
- `pwsh -NoProfile -File scripts/android.ps1 -Action Run -Serial <目标真机序列号>`：构建、保留数据覆盖安装并启动。截图 / Logcat 同样传入目标 serial。
- 现有无参数 `Run`、双击入口和 `Ctrl+Shift+B` 仍选择 AVD，尚无自动真机优先逻辑；有真机时不得走这些默认路径。无真机时才可用无参数 `Run` 复用 / 启动 AVD。
- `Run` 会重启开发应用；测试前提醒用户保存正在编辑的内容。不得自行卸载应用、清数据、wipe AVD 或重置用户文稿。
- 禁止在真机上频繁、反复覆盖安装，应用 APK 与测试 APK 均适用。先集中完成本地修改、构建与检查，再安排必要的最终验收安装；额外安装须先说明原因与影响并获得用户明确授权。用户叫停后，本轮不再安装，也不得改用模拟器绕过。详见 `docs/device-policy.md`。
- 使用 JDK 17，不能误用终端默认 JDK 25。AGP 9 内置 Kotlin，不重复应用 `org.jetbrains.kotlin.android`。
- 版本集中于 `gradle/libs.versions.toml`；修改工具链须记录原因、兼容性与验证。

## 实施约束

- 先完成当前需求所需的最小纵向切片；不预建通用框架、空接口群、多模块、DI 框架或未来功能。
- 文稿是用户数据：不静默覆盖，不吞掉保存失败，不在日志输出正文；详细规则见 `docs/data-safety.md`。
- 已确定私有目录为权威正文；应用内导入和系统“打开方式”接收的纯文本 / `.md` 均创建独立私有副本，不写回源 URI、不覆盖已有稿。首片包含最小 `.txt` 导出，基线为 10 万 Unicode 码点且 UTF-8 ≤ 1 MiB。设计见 `docs/specs/0001-plain-text-editor.md` 及导入关联专题；这些需求尚未实现。
- 新依赖说明用途、体积影响和替代方案；只有需求需要时才引入。禁止动态版本、预览版本和未经验证的仓库镜像。
- 不在主线程执行文件 I/O；UI 不直接操作磁盘。用户字符串进入资源文件，支持字体缩放、系统栏和输入法。
- 不加入网络、遥测、广告、云账户或广泛存储权限，除非当前需求明确需要且已说明取舍。
- 默认在当前分支进行小任务；较大隔离任务可用 `codex/<topic>`。Git 工作流见 `docs/git-workflow.md`。未要求时不代为 commit/push；已授权后无需重复询问。

## 完成标准

- 执行与变更相关的验证；Kotlin/资源/构建变更至少运行 `Check`。`NO-SOURCE` 不等于已有单元测试通过。
- 可见功能交付给用户试用时，遵守安装频率与用户叫停约定；允许并安排最终设备验收时再运行 `Run`，提供具体步骤、预期结果、设备和 APK 身份。未获准安装时明确记录设备验收未完成，不能为满足完成标准自行重复安装。模拟器通过不等于真机通过。
- 修复缺陷优先增加能复现缺陷的测试，不写只验证常量、数据类或实现自身的测试。
- 更新 `docs/status.md` 的事实与下一步，重大决策写 ADR，功能状态同步路线图；不要堆积逐条操作日志。
- 根据 `docs/git-workflow.md` 进行 Git 提交，除非用户此前明确说明不进行 Git 操作。
- 交付说明结果、验证、限制和下一步，明确区分已实现、仅规划、未验证。

## Code Review Rules

优先检查数据丢失、保存竞争、主线程 I/O、权限失效、生命周期恢复和错误的设备目标。指出具体触发条件及后果；格式问题交给工具，不虚构发现。
