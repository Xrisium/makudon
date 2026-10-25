# 原生复选与统一剪贴板验收

日期：2026-10-26。范围：[任务 0014](../tasks/0014-native-multiselect-and-clipboard.md)，方案见[任务 0013](../tasks/0013-directory-multiselect-preview.md)，数据边界见 [ADR 0005](../adr/0005-batch-directory-and-private-clipboard.md)。

## 实现与身份

- 功能与测试源码提交：`fb78a777e2ea0fc2fa0a4f93854592a405c582d4`。后续文档提交不改变 APK；模拟器实际安装包哈希与下列构建产物一致。
- Makudon Dev / `io.github.xrisium.makudon.debug` / `0.0.1-dev`，debug APK SHA256：`073A3260DB3506C905140CF3B01AC43CA8D08A842816E59DBD2E43F8E4A1708F`。
- 测试 APK SHA256：`99FD553A65049B7D84D1A8DB8DABEED487D45FA6667C6F95332B593314AF2AE7`。
- 行尾复选、X / 数量 / 清空顶部、底部复制 / 剪切 / 粘贴 / 删除已原生接入；普通页保留五按钮布局。最近仅用 Lucide `plus`，目录对应按钮用已有 `file-plus-corner` 向量（旧资源别名 `ic_file_plus_2`）。无新依赖或权限。
- 单项 / 批量共用进程内剪贴板，复制快照累计上限 8 MiB；退出 / 清空勾选保留，进程死亡不恢复。批量数据操作一次发布索引，不循环发布单项。

## 本地检查

- `pwsh -NoProfile -File scripts/android.ps1 -Action Check`：构建、36 项 JVM 测试、严格 Lint（0 问题）通过。
- 新增 4 项复选 / 目标规则和 7 项批量存储回归；包含父子规范化、半选 / 继承、同目录去重、跨目录 / 层级候选、根目录目标、目录环 / 原位 / 移动重名、批内副本名、独立正文、一次发布、第二篇正文写入失败、索引失败不部分移动 / 删除、清理失败仍尝试其他文件，以及累计复制预算拒绝。
- `:app:assembleDebugAndroidTest` 通过。单元测试使用真实临时文件与故障注入，不把 NO-SOURCE 算通过。

## 模拟器检查

用户本轮明确选择“授权本轮模拟器验收”，仅在已有 Android_API_36 / API 36 执行。应用 / 测试包保留数据安装，不卸载、清数据或 wipe。真机虽在线，本轮未安装、重启或运行测试；该授权仅用于本轮模拟器。

| 用例 | 结果 | 主要覆盖 |
| --- | --- | --- |
| `-e case selection` | 24 项通过 | 真实长按与点击、单项剪贴板、进入模式不改新建目标、删除顶部区域 / 隐藏主导航、跨目录 / 层级选取、不预选粘贴目标、取消保留、一次只粘贴到一个位置、正文完整、清空 / X、唯一目标、Activity 重建、父子去重 / 继承 / 半选、收起保留、剪切成功清空、多项复制从单项菜单粘贴、删除取消与独立快照保留 |
| `-e case selection-visual` | 2 项通过 | 实际 320dp 与系统字号 200%，顶部和四操作仍在屏幕内，位置确认的取消 / 确认可见且无歧义默认值；临时屏幕尺寸 / 字号 / 主题在 finally 恢复 |
| `-e case workspace` | 29 项通过 | 原有默认最近 / 根目录新建、Unicode 保存 / 撤销 / 双视图保真、重命名 / 删除确认、复制移动、导入独立副本、导出快照、非法编码、VIEW 去重、10 万码点及超限整次拒绝 |

合计 55 项检查通过。复选专项采用单次真实触摸和纵向滚动，避免重复无障碍动作意外二次切换；只清理本次创建的合成目录。保存到设备的正文进行了完整内容比较，测试日志不输出正文。原有 10 万码点输入与保存约 1.04s 是该模拟器单次观测，不代表 p95。

浅色复选、深色复选、位置确认、最近加号及窄屏大字号截图保存在忽略的 `.local/multiselect/`，已人工检查；截图不入 Git。完整 runner 输出在 `.local/selection-test.txt`、`.local/selection-visual-test.txt` 和 `.local/workspace-multiselect-regression.txt`。

复验须先重新识别设备并遵守[设备策略](../device-policy.md)，以下是本轮已授权模拟器的 runner 范围，不能视为以后真机在线时的自动授权：

```text
am instrument -w -e case selection io.github.xrisium.makudon.debug.test/io.github.xrisium.makudon.WorkspaceInstrumentation
am instrument -w -e case selection-visual io.github.xrisium.makudon.debug.test/io.github.xrisium.makudon.WorkspaceInstrumentation
am instrument -w -e case workspace io.github.xrisium.makudon.debug.test/io.github.xrisium.makudon.WorkspaceInstrumentation
```

## 限制与交接

本轮未在手机覆盖安装或验收，手机仍为此前版本；用户本人真机上手及 TalkBack 完整矩阵待安排。未验证突然断电、全部真实磁盘故障时点、深层大目录性能或长文 p95。残留材料不是回收站或独立备份，复制失败的未发布正文不会静默清理；这些延续 ADR 0003 / 0005 的边界。
