# 架构与扩展边界

## 已落地结构

一个 `:app` Android 应用模块，Kotlin + Jetpack Compose。先保持直接、可读，直到构建速度或边界确实需要再拆模块。

```text
app/src/main/kotlin/io/github/xrisium/makudon/
  MainActivity.kt          Android 生命周期与 Compose 接入
  app/                    进程 ViewModel 所有者、主题与应用装配
  feature/workspace/      三标签、目录、新建、编辑、保存协调、撤销差量
  ui/theme/               主题
  domain/Workspace.kt      节点、排序、UTF-8 / 名称规则、有限 Markdown 范围解析
  data/WorkspaceStore.kt   私有目录、原子索引发布、正文 / 上一版本与恢复
```

现已实现上述职责；[ADR 0003](adr/0003-workspace-catalog.md)记录原生工作区的数据格式、索引事务边界和有限排版编辑。没有 DI、数据库、导航运行库或 WebView。

每篇私有文稿使用独立 ID，同时只编辑一篇；进程内单一 ViewModel / Mutex 协调保存和目录操作。外部 VIEW 与应用内导入共用只读副本链路，正文不写回源 URI。保存、恢复、导入与导出已接入；完整故障 / 进程中断矩阵及真机体验仍须验收，见 [当前状态](status.md)。

主标签将手势识别与过渡分离：有效 pointer-up 或底栏点击向 ViewModel 发布一次离散切换，Compose AnimatedContent 只挂载来源与目标，固定移动一屏；不使用 Pager 跟手偏移，也不经过中间标签或触发磁盘刷新。切换有独立递增 ID 与完成回执，旧回执不能解锁新切换；过渡期间独立拦截输入 / 隐藏不可交互语义，不改变控件 enabled 颜色。每个标签通过稳定 key 的 SaveableStateProvider 保留查询、展开与列表滚动状态。二级页导航由 ViewModel 发布方向与递增导航 ID，UI 在动画结束后回执；退出编辑器先通过保存屏障，保留原会话作只读退场，收到匹配的回执后释放。后台 / 重建同样能收尾，旧导航回执不能清理新会话。

## 按需增长的依赖方向

```text
UI / ViewModel → domain 中实际需要的规则或仓库契约
data → domain 契约 + Android 文件 API
app → 组装 data 实现与 feature
```

- 第一个功能引入必要的 ViewModel，使用不可变 UI state + 用户动作 + 单向状态更新；后台工作用结构化协程。
- `domain` 不依赖 Compose、Activity、Context、Android Uri；仅放已经出现的业务规则。只有多处复用或逻辑复杂时才抽 use case。
- `data` 封装私有文件、SAF 等实际后端。失败必须返回可区分、可向用户说明的结果。
- UI 不知道真实文件路径，不能直接调用文件 API。Android URI 由适配层持有；不要伪造 URI 为文件路径。
- 初期手动构造依赖即可；无 Hilt/Koin、Room、DataStore、导航库或 Markdown 库的预装义务。
- 文件正文应能以标准 `.md` / `.txt` 导出；元数据不能锁死正文格式。是否加索引数据库在实际需求出现时决定。

## 构建与安装身份

- namespace / 正式 applicationId：`io.github.xrisium.makudon`。
- 开发 applicationId：`io.github.xrisium.makudon.debug`，名称 **Makudon Dev**。
- 开发包和未来正式包数据隔离，不自动迁移；发布前应再次确认正式包名。
- 当前仅有 debug 本机测试链路。release 未配置签名、混淆或发布流程。
