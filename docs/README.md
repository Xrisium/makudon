# 文档索引

根 `AGENTS.md` 保留少量始终适用的规则，专题文档按任务加载。不要复制同一条约定到多个地方；发生冲突时先尊重当前用户要求，再更新过时文档。

| 任务 | 阅读 |
| --- | --- |
| 每次开始 / 接续开发 | [状态](status.md)、[产品](product.md) |
| 环境、VS Code、构建 | [开发指南](development.md)、[模拟器测试与真机交付规则](device-policy.md)、[工具链决策](adr/0001-toolchain.md) |
| 修改 Kotlin / UI | [架构](architecture.md)、[代码规范](coding-standards.md)、[设计原则](design.md) |
| 本地化 / 翻译贡献 | [中英文设计](specs/0004-localization.md)、[实施](tasks/0023-localization.md)、[English contribution guide](localization/CONTRIBUTING.en.md) |
| 视觉、导航、双视图、字体与品牌素材 | [设计原则](design.md)、[导航与双视图草案](specs/0003-workspace-and-markdown-views.md)、[素材与许可证](third-party-assets.md)、[品牌草案](../assets/brand/README.md) |
| 编辑、保存、导入导出 | [数据安全](data-safety.md)、[首片设计](specs/0001-plain-text-editor.md)、[导入 / 关联协议](specs/0002-import-and-file-association.md)、[测试](testing.md) |
| Markdown 三视图、实时预览与写作工具 | [行为规范](specs/0005-markdown-three-modes.md)、[连续源码 ADR](adr/0011-markdown-source-projection.md)、[任务](tasks/0026-markdown-three-modes.md)、[页签与分组工具栏](tasks/0027-markdown-toolbar-layout.md) |
| Markdown 长文排版滚动性能 | [优化任务](tasks/0043-markdown-scroll-performance.md)、[同机比较与验收](testing/2026-11-16-markdown-scroll.md) |
| Markdown 表格单元格编辑 | [单元格输入 ADR](adr/0012-markdown-table-cells.md)、[实施与验证](tasks/0028-markdown-table-editing.md)、[内容高度与整篇滚动](tasks/0030-markdown-table-flow.md) |
| Markdown 菜单开关与表后正文 | [边界修复](tasks/0029-markdown-menu-and-table-boundary.md)、[行为规范](specs/0005-markdown-three-modes.md) |
| 缓存概览、提示文案与设置卡片范式 | [任务](tasks/0040-cache-overview-and-ui-audit.md)、[设计](design.md)、[审查与用户决定](reviews/2026-11-14-ui-copy-and-settings-layout.md) |
| Markdown 图片、目录预览与缓存 | [实施与验收](tasks/0037-markdown-images.md)、[缓存二级设置与阅览菜单](tasks/0038-image-cache-and-menus.md)、[分场景占用与关闭清理](tasks/0039-scoped-thumbnail-cache.md)、[引用与存储协议](adr/0013-library-images.md) |
| 行距、纯文本双视图、位置恢复与长文导航 | [行为规范](specs/0006-reading-and-navigation.md)、[视口存储 ADR](adr/0015-document-viewport.md)、[任务](tasks/0042-reading-and-long-document-navigation.md) |
| 编辑导航边距、目录长文件名与羽化预览 | [任务与确认边界](tasks/0044-editor-controls-and-filename.md)、[导航规范](specs/0006-reading-and-navigation.md) |
| Markdown 大纲 | [底部面板与标题跳转](tasks/0031-markdown-outline.md)、[行为规范](specs/0005-markdown-three-modes.md) |
| 启动产品页 | [品牌布局、主题首帧与验收](tasks/0032-startup-product-page.md) |
| 最近卡片内容概览开关 | [偏好、显示范围与验证](tasks/0034-recent-content-overview.md) |
| 最近卡片时间选择、编辑页精简与文案筛查 | [实施与验证](tasks/0035-recent-date-and-copy.md)、[文案筛查报告](reviews/2026-11-12-ui-copy.md) |
| 最近排列依据、设置卡片归并与文案精简 | [修正与验证](tasks/0036-recent-sort-and-copy.md)、[同类设置归并规范](design.md) |
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
