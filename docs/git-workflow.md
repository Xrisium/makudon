# 单人 Git 工作流

## 当前配置

- 远程：`origin = https://github.com/Xrisium/makudon.git`。
- 主分支：`main` 跟踪 `origin/main`；初始化时远程读取成功。
- 沿用已有 Git 用户身份与认证方式，不改全局设置。
- 本仓库局部设置：`core.autocrlf=false`、`core.eol=lf`、`pull.ff=only`、`fetch.prune=true`、`push.default=simple`。
- `.gitattributes` 约定文本 LF、Windows 批处理 CRLF、二进制原样；`.gitignore` 排除缓存、APK、私人路径、密钥和日志，以及其他构建产物、测试构建产物、按技术栈惯常规范不应该被 git 跟踪的其他文件、以及用户主动要求不跟踪的文件。

重新克隆后可按需执行下列本仓库配置（Git 本地 config 不随仓库分发）：

```powershell
git config --local core.autocrlf false
git config --local core.eol lf
git config --local pull.ff only
git config --local fetch.prune true
git config --local push.default simple
```

## 日常循环

1. 查看状态与差异，保留未提交的手动修改；需要远程更新时 `git fetch origin`，干净且可快进时 `git pull --ff-only`。
2. 小改动可直接留在 main；较大功能建议 `git switch -c codex/<topic>`，方便整体审查或暂停。
3. 完成一件可说明的改动，执行相关检查，随时用测试入口试用。
4. `git diff` / VS Code Source Control 审查，按文件暂存。避免无检查地 `git add .`。
5. 每轮会话完成、且对项目有实际修改后，自动对本次修改的内容创建细粒度的 commit（规范见下），对于不同模块的改动（例如同时改变了业务代码和文档）应该拆分后分开提交。
6. 禁止自动 push 到远程仓库（push 这一步应该由用户本人完成）。

## Commit Message 规范（Agent 必须遵守）

本仓库使用 Conventional Commits。所有 commit message 必须符合：

<type>(<scope>): <subject>

### 强制规则

1. type 只能从以下列表选择：
   - feat: 新增用户可感知的功能
   - fix: 修复缺陷
   - docs: 仅文档/翻译/注释变更
   - style: 仅代码格式，不改变逻辑
   - refactor: 重构，不修 bug 不加功能
   - perf: 性能优化
   - test: 测试相关
   - build: 构建系统、打包器、编译器、Dockerfile
   - ci: CI/CD 配置或脚本
   - chore: 依赖升级、工具配置等杂项
   - revert: 回滚提交

2. scope 可选，仅在需要时添加，使用小写英文或短横线，如 auth、i18n、webpack、deps。
3. subject 使用祈使句、现在时、必须使用简体中文，不超过 72 字符，不加句号。
4. 一次提交只做一件事。构建配置与业务代码必须分开提交。
5. 破坏性变更必须在 type 后加 `!`，并在 body/footer 写 `BREAKING CHANGE:`。
6. 禁止使用未列出的 type，如 update、change、misc。
7. 禁止空泛描述，如“更新配置”“修改代码”。
8. 除了上述 <type>(<scope>): <subject> 的 commit message 规范外，还应附带较为详细的 Description。使用简体中文，用有序列表描述此次 commit 涉及的改动。

### 类型选择决策

- 改构建系统本身 → build
- 改 CI/CD 流水线 → ci
- 只升级依赖/锁文件 → chore(deps)
- 只改翻译/文档 → docs(i18n) 或 docs
- 修复错误翻译导致显示错误 → fix(i18n)
- 不确定且不影响业务 → chore，但必须在 body 说明原因

### 正例

build(vite): 调整生产环境分包策略
ci(github-actions): 增加 Node 20 构建矩阵
docs(i18n): 修正登录页中文翻译
fix(i18n): 修复删除按钮误译为编辑
chore(deps): 升级 esbuild 至 0.21

### 反例

update config
fix: 改了点东西
build: 更新配置
feat: 修改翻译文件

## 保护与恢复

- 不自动 amend 已推送提交、force push、reset --hard、clean -fd 或覆盖用户工作；共享/已推送历史用新的修复提交。
- 不添加自动安装依赖、提交后启动模拟器或有网络副作用的 Git hooks。
- Debug 使用 Android 本机调试签名；正式密钥需单独备份，绝不能入库。开发与正式包隔离。

## CI 范围

`.github/workflows/android.yml` 在 main/codex 分支 push、PR 或手动触发时构建 debug、运行已有单测和 Lint，并保留短期 debug APK。它不发布 Release、不签正式包、不运行付费设备测试。GitHub 运行器会准备自身工具，和本机无需安装新工具是两回事。

首次远程执行前该配置只能标记“已配置，未在 GitHub 验证”。
