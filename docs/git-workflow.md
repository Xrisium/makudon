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
5. 任务完成后自动创建粒度合适的 commit，对于不同模块的改动应该分开提交。标题及描述都必须使用简体中文，禁止自动 push 到远程仓库（push 这一步应该由用户本人完成）。

提交建议：`build: 初步完成安卓构建设置`、`feat(editor): ...`、`fix(storage): ...`、`docs: ...`。必须使用简体中文，重点是解释行为与原因。

## 保护与恢复

- 不自动 amend 已推送提交、force push、reset --hard、clean -fd 或覆盖用户工作；共享/已推送历史用新的修复提交。
- 不添加自动安装依赖、提交后启动模拟器或有网络副作用的 Git hooks。
- Debug 使用 Android 本机调试签名；正式密钥需单独备份，绝不能入库。开发与正式包隔离。

## CI 范围

`.github/workflows/android.yml` 在 main/codex 分支 push、PR 或手动触发时构建 debug、运行已有单测和 Lint，并保留短期 debug APK。它不发布 Release、不签正式包、不运行付费设备测试。GitHub 运行器会准备自身工具，和本机无需安装新工具是两回事。

首次远程执行前该配置只能标记“已配置，未在 GitHub 验证”。
