# 架构决策记录

| 编号 | 状态 | 主题 |
| --- | --- | --- |
| [0001](0001-toolchain.md) | 接受 | 复用本机工具链、单模块 Kotlin/Compose、CLI + VS Code |
| [0002](0002-document-storage.md) | **接受** | 私有正文、本机文件导入副本与打开关联、最小 `.txt` 导出 |
| [0003](0003-workspace-catalog.md) | 接受并实施 | 工作区目录索引原子发布、进程单写者与有限原生排版编辑 |
| [0004](0004-root-default-without-inbox.md) | 接受并实施 | 取消自动收件箱、默认根目录并保留旧文件夹数据 |
| [0005](0005-batch-directory-and-private-clipboard.md) | 接受并实施 | 复选操作根、批量索引发布、统一私有剪贴板与唯一粘贴目标 |
| [0006](0006-plain-zip-and-library-generations.md) | 接受并实施 | 纯目录 ZIP、逐项冲突、整库代际发布与最近一次恢复快照 |
| [0007](0007-file-manager-first.md) | 接受并实施 | 应用选择、文件管理分享、固定导出副本与下载目录保存 |

新决定使用 [模板](../templates/adr.md)，顺序编号。已接入原生工作区、私有保存和文件出入口；实现与未覆盖验收以 [当前状态](../status.md) 为准。
