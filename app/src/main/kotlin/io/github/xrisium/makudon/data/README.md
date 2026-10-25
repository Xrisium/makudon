# Data 边界

WorkspaceStore.kt 封装私有目录索引、标准 UTF-8 正文、上一提交版本、目录复制移动和确认后的删除。所有调用由进程 ViewModel 在 IO dispatcher / 单一 Mutex 下串行执行；ContentResolver 出入口在 ViewModel 的 IO 适配边界，UI 不操作磁盘。当前不使用数据库，也没有独立备份。实际布局与事务边界见 docs/adr/0003-workspace-catalog.md，约束见 docs/data-safety.md。
