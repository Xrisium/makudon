# 架构与扩展边界

## 已落地结构

一个 `:app` Android 应用模块，Kotlin + Jetpack Compose。先保持直接、可读，直到构建速度或边界确实需要再拆模块。

```text
app/src/main/kotlin/io/github/xrisium/makudon/
  MainActivity.kt          Android 生命周期与 Compose 接入
  app/MakudonApp.kt        应用装配；未来放少量手动依赖组装
  feature/workspace/      当前品牌占位页；未来文档工作区
  ui/theme/               主题
  domain/README.md         未来纯 Kotlin 规则的边界说明
  data/README.md           未来 Android 文件适配的边界说明
```

`domain` / `data` 当前没有可运行实现。编辑器、导航、ViewModel、Repository、数据格式都还未实现。

首片的产品方向已确定，见 [职责与持久化设计](specs/0001-plain-text-editor.md)、[已接受存储 ADR](adr/0002-document-storage.md) 和 [导入 / 关联协议](specs/0002-import-and-file-association.md)。每篇私有文稿使用独立 ID，同时只编辑一篇；最小列表保留旧导入副本的可达性。单写者、恢复、导入、关联和导出均尚未落地，不把设计结构当作现有代码。

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
