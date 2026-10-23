# 文档索引

根 `AGENTS.md` 保留少量始终适用的规则，专题文档按任务加载。不要复制同一条约定到多个地方；发生冲突时先尊重当前用户要求，再更新过时文档。

| 任务 | 阅读 |
| --- | --- |
| 每次开始 / 接续开发 | [状态](status.md)、[产品](product.md) |
| 环境、VS Code、构建 | [开发指南](development.md)、[设备优先规则](device-policy.md)、[工具链决策](adr/0001-toolchain.md) |
| 修改 Kotlin / UI | [架构](architecture.md)、[代码规范](coding-standards.md)、[设计原则](design.md) |
| 视觉、导航、双视图、字体与品牌素材 | [设计原则](design.md)、[导航与双视图草案](specs/0003-workspace-and-markdown-views.md)、[素材与许可证](third-party-assets.md)、[品牌草案](../assets/brand/README.md) |
| 编辑、保存、导入导出 | [数据安全](data-safety.md)、[首片设计](specs/0001-plain-text-editor.md)、[导入 / 关联协议](specs/0002-import-and-file-association.md)、[测试](testing.md) |
| 审阅首片存储取舍与产品决定 | [已接受存储 ADR](adr/0002-document-storage.md)、[D1–D3 决定记录](specs/0001-open-decisions.md) |
| 实施后验收首个编辑切片 | [首片验收规范（未执行）](testing/plain-text-editor-acceptance.md) |
| 分支、提交、发布 | [Git 工作流](git-workflow.md) |
| 安排后续功能 | [路线图](roadmap.md)、[任务模板](templates/task.md) |
| 改变长期技术选择 | [ADR 索引](adr/README.md)、[ADR 模板](templates/adr.md) |
| 交付实机测试 | [设备选择约定](device-policy.md)、[测试指南](testing.md)、[验收模板](templates/manual-test.md) |

## 维护方式

- `status.md` 是当前状态，不是聊天记录；完成一个任务后更新结果、限制、下一步。
- `roadmap.md` 区分已完成、候选、未开始；不能把计划自动当作开发授权。
- `adr/` 记录影响长期开发的决定。已接受决定改变时新增 ADR，并在旧文档标记被取代，不改写历史理由。
- `tasks/` 仅保存已经开始的复杂任务计划；完成后保留结论。小修无需新建文件。
- `specs/` 保存可执行设计，明确草案 / 已接受 / 已实现的区别；待决定事项独立列出，数字指标要注明是否实测。
- `testing/` 保存值得留档的里程碑验收记录；大日志、截图和 APK 存 `.local/` 或构建目录，不入 Git。
- 如未来某个目录需要特殊规则，可增加局部 `AGENTS.md`，并在根入口注明需要按任务显式读取。不要假设从根启动时会自动加载任意深层文件。
- 官方 Agent 文件发现机制：[AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。其他 `docs/` 文档是本项目约定，通过根入口按需引导读取。
