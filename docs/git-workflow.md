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

1. 开始和接续会话时执行 `git status --short --branch`、`git branch --show-current`、`git worktree list`，首次进度说明报告实际分支；默认目标为 `main`，不能根据会话标题、上轮记忆或任务名称推断当前分支。保留未提交的手动修改；需要远程更新时 `git fetch origin --prune`，干净且可快进时 `git pull --ff-only`。
2. 单人串行任务默认直接在 `main` 开发和提交，不因功能较大自动建分支。一次只允许一个会修改同一工作区的开发会话；VS Code、终端和 Agent 共用的工作区切换分支会互相影响。
3. 完成一件可说明的改动，执行相关检查，随时用测试入口试用。
4. `git diff` / VS Code Source Control 审查，按文件暂存。避免无检查地 `git add .`。每次暂存 / 提交前重新执行 `git branch --show-current` 与 `git status --short --branch`，确认仍在本任务目标分支；不能只在会话开始时检查一次。
5. 每轮会话完成、且对项目有实际修改后，自动对本次修改的内容创建细粒度的 commit（规范见下），对于不同模块的改动（例如同时改变了业务代码和文档）应该拆分后分开提交。提交后检查工作区，交付报告实际分支、提交号、验证结果及相对上游的未推送提交数。
6. 默认由用户本人 push；用户当轮明确授权 Agent 推送时可执行，无需重复确认。本地提交 / 合并 / 删分支不会自动更新 GitHub。

### 分支不符合目标时

- 实际分支不是 `main`，且本轮没有明确授权在该功能分支开发时，先停止修改文件。工作区干净、`main` 未被其他 worktree 占用，且 `git merge-base --is-ancestor HEAD main` 返回 0 时，可直接 `git switch main` 并重新核对状态。
- 如果有未提交改动、当前分支有 main 未包含的提交、main 被占用或处于 detached HEAD，先报告实际情况并明确归属；不得自动 stash、丢弃、带着改动跨分支或在旧分支继续新任务。合并与清理需要属于当前用户授权范围，本次分支整理不构成今后任意合并的长期授权。
- 不把“GitHub 默认分支为 main”当作本地已切到 main 的证据；日常可在 VS Code 状态栏核对分支。

### 临时分支的例外与收尾

仅用户明确要求隔离试验、暂停未完成工作或并行开发时创建 `codex/<topic>`；从已核对的 main 建立，任务计划写明目标分支、范围、验收和合并方式。新任务超出该分支范围时回到 main，不继续堆叠。

授权隔离任务默认包含本地收尾：验证完成后回到 main，优先 `git merge --ff-only codex/<topic>`；发生分叉先审查差异，不能为得到直线历史而自动改写提交。确认 `git merge-base --is-ancestor codex/<topic> main` 成功后，使用 `git branch -d codex/<topic>` 安全删除。暂时不能合并时报告原因和剩余分支，不能宣称已收尾。远端分支删除和 push 仍需用户授权。

### 直接在 main 开发的边界

单人串行开发可以只使用 main；分支用于隔离，并非保存历史的前提。保持小步、可说明的提交，完成相关检查后再提交；撤销已共享修改优先用新的 `git revert` 提交，发布版本继续用标签定位。定期由用户推送，避免唯一副本只在电脑上。

此模式下 main 表示最新开发状态，不保证每个中间提交都是正式发布版本。需要长期保留不完整试验、同时维护旧版本或并行会话时，再明确启用临时分支。可在 GitHub 保护 main，禁止删除和强制推送并约束管理员绕过；保持允许直接推送，不强制 PR / 他人批准，否则与该模式冲突。GitHub 保护只能约束远端更新，无法阻止本地提交到错误分支；文档核对是行为约定，不是强制技术拦截。参见 [GitHub 分支保护](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。

## 2026-11-16 整理后的 GitHub 交接

本次本地快进保留全部原提交，删除五个已合入的本地功能分支，仅保留 main；`v0.1.0` 标签及远端分支未改动。清理前完整 Git bundle 与引用清单位于被忽略的 `.local/git-cleanup-2026-11-16/`；任务和验证见 [0041](tasks/0041-main-branch-consolidation.md)。GitHub 默认分支已核实为 main，无需改名或新建仓库。

1. 在本仓库核对 `git status --short --branch` 后执行 `git push origin main`，无需 force。若非快进被拒绝，先 fetch 并审查远端新提交，不要强推。
2. 到 [GitHub Actions](https://github.com/Xrisium/makudon/actions) 检查本次 main 推送的构建、单测与 Lint；远端成功不能用本地 Check 代替。
3. 推送成功后执行下列脚本：先 fetch 并逐一核对祖先关系，任何一步失败即停止，不删除分支。若分支在此期间新增未合入提交，保留并重新审查。删除远端分支只移除分支名，已合入 main 的历史和版本标签继续保留。

```powershell
git fetch origin --prune
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed' }
$obsoleteBranches = @('codex/markdown-images', 'codex/markdown-three-modes')
foreach ($branch in $obsoleteBranches) {
    git merge-base --is-ancestor "origin/$branch" origin/main
    if ($LASTEXITCODE -ne 0) { throw "Stop: review $branch before deletion" }
}
git push origin --delete $obsoleteBranches
if ($LASTEXITCODE -ne 0) { throw 'Remote branch deletion failed' }
git fetch origin --prune
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed' }
git branch -avv
```

也可在 [GitHub 分支页](https://github.com/Xrisium/makudon/branches) 核对后删除这两个功能分支。若有相关未关闭 PR，核对内容已进入 main 后再处理，不重复 squash / 合并已有提交。[GitHub 分支管理](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-branches-in-your-repository)。不要删除 `v0.1.0` 或现有 Release；标签是否推送及发布附件仍按原发布任务单独处理。

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

## 手动发布 Android APK

在仓库根目录用 PowerShell 7 执行构建。`BuildRelease` 使用项目现有的 JDK / SDK 配置，生成未签名 APK 并执行 Release Lint。

```powershell
pwsh -NoProfile -File scripts/android.ps1 -Action Check
pwsh -NoProfile -File scripts/android.ps1 -Action BuildRelease
```

以下命令使用本机标准工具路径。首次发布且没有既有 Makudon 发布密钥时，在自己的终端生成密钥并按提示设置密码。将密钥和密码分别安全备份，后续同包名的更新继续使用该密钥。已有正式密钥时，填写它的实际路径和别名，跳过生成步骤。

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\DevTools\Android\Jdk\17.0.18+8"
$buildTools = "$env:USERPROFILE\DevTools\Android\Sdk\build-tools\36.0.0"
$releaseKey = "$env:USERPROFILE\.android\makudon-signing\makudon-release.p12"
$releaseAlias = 'makudon'
New-Item -ItemType Directory -Force (Split-Path -Parent $releaseKey) | Out-Null
if (!(Test-Path -LiteralPath $releaseKey)) {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $releaseKey -storetype PKCS12 -alias $releaseAlias -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Makudon'
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the release key.' }
}
```

将 `0.1.0` 替换为本次版本。签名工具会提示输入密码，密钥文件和密码只保留在本地。本机 0.1.0 已于 2026-11-11 创建上述专用密钥，随机密码保存在同目录的 `makudon-release-password.txt`；后续继续复用并自行安全备份。签名与校验通过后，上传签名 APK 和可选的 SHA-256 文件。

```powershell
$releaseVersion = '0.1.0'
$unsignedApk = 'app/build/outputs/apk/release/app-release-unsigned.apk'
New-Item -ItemType Directory -Force .local/release | Out-Null
$alignedApk = ".local/release/Makudon-$releaseVersion-aligned.apk"
$signedApk = ".local/release/Makudon-$releaseVersion.apk"
& "$buildTools\zipalign.exe" -f -P 16 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
& "$buildTools\apksigner.bat" sign --ks $releaseKey --ks-key-alias $releaseAlias --out $signedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& "$buildTools\apksigner.bat" verify --verbose --print-certs $signedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$apkHash = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
"$apkHash  $(Split-Path -Leaf $signedApk)" | Set-Content -LiteralPath "$signedApk.sha256" -Encoding ascii
```

对已检查的源代码提交打版本标签，由用户推送分支和对应标签。在 [新建 GitHub Release](https://github.com/Xrisium/makudon/releases/new) 选择已推送的标签，填写标题和版本说明，上传 `.local/release/Makudon-<版本>.apk` 及其 `.sha256`，核对附件后选择 **Publish release**。正式版本保持 **This is a pre-release** 未勾选。[GitHub 发布文档](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository) · [Android APK 签名文档](https://developer.android.com/tools/apksigner)

## CI 范围

`.github/workflows/android.yml` 在 main/codex 分支 push、PR 或手动触发时构建 debug、运行已有单测和 Lint，并保留短期 debug APK。它不发布 Release、不签正式包、不运行付费设备测试。GitHub 运行器会准备自身工具，和本机无需安装新工具是两回事。

CI 固定使用 Ubuntu 24.04 和 JDK 17，通过 `${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager` 安装 SDK 36 / Build Tools 36.0.0，不依赖运行器将 `sdkmanager` 加入 PATH。构建仍使用仓库 Wrapper；项目 Gradle / AGP / Kotlin 版本不随 Actions 升级。

2026-11-05 检查时，已有 17 次远程运行均失败；最新一次在 SDK 准备步骤以退出码 127 结束，构建步骤未执行。已修复命令路径并迁移 Node 24 Actions；本地验证通过，修复后的远程运行仍待用户推送后确认，不能标记为 GitHub CI 已通过。
