# 高频测试指南

## 最短路径

先按 [设备选择约定](device-policy.md) 查看连接状态：**有真机时仅用真机，无真机才用模拟器，用户明确要求除外。**

有真机时使用 `test-android.cmd -Serial YOUR_SERIAL` 或 VS Code 的 `Makudon: Run on selected device`。Agent 应识别唯一在线真机并传序列号；多台真机未指定时再请用户选择。未授权 / offline / 运行中断连时处理真机问题，不自动回退模拟器。

现有无参数双击、`Ctrl+Shift+B` 和无 `-Serial` 的 Run / Screenshot / Logcat 仍走配置 AVD，**尚无自动设备分流**。仅确认无真机或用户要求模拟器时才用它们；脚本会复用 / 启动 AVD，不会每次创建、clean、卸载、清数据或 wipe。

指定目标后每次先增量构建，再 `adb install -r`、重启开发应用并检查进程。无真机使用模拟器时，正常退出保存的 Quick Boot 快照通常可加快下次启动。

当前应进入“最近”，可新建、编辑、自动保存和重新打开；目录长按提供整理动作。开发版详细范围与限制见 [工作区验收记录](testing/2026-10-25-workspace.md)。运行前等待“已保存到本机”，尤其是保留数据覆盖安装会重启进程。

**覆盖安装保留已持久化数据，重启进程不会保留仅在内存中的未保存内容。未来编辑器接入前后都应先保存再运行测试入口。**

## 常用操作

```powershell
# 仅无真机或用户明确要求模拟器时使用
pwsh -NoProfile -File scripts/android.ps1 -Action Emulator
# 构建与静态检查，不启动设备
pwsh -NoProfile -File scripts/android.ps1 -Action Check
# 查看设备，包括 unauthorized/offline 状态
pwsh -NoProfile -File scripts/android.ps1 -Action Devices
# 有真机时仅用真机：替换为本次 Devices 中的目标 serial
pwsh -NoProfile -File scripts/android.ps1 -Action Run -Serial YOUR_SERIAL
# 截图：.local/screenshots/
pwsh -NoProfile -File scripts/android.ps1 -Action Screenshot -Serial YOUR_SERIAL
# 当前开发应用日志，Ctrl+C 停止；进程重启后重新执行
pwsh -NoProfile -File scripts/android.ps1 -Action Logcat -Serial YOUR_SERIAL
```

真机开启开发者选项与 USB 调试，在手机确认 RSA 授权。不自动为未知手机安装驱动。手机一律明确 `-Serial`，截图与日志沿用同一目标；多设备选择按设备约定，不把现有脚本默认选 AVD 当作允许绕过真机的理由。

## 产物与复现

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Lint：`app/build/reports/lint-results-debug.html`。
- 未来的 JVM 单测报告：`app/build/reports/tests/testDebugUnitTest/index.html`；尚无单测时不会生成报告，任务为 `NO-SOURCE`。
- 最近安装记录：`.local/last-run.json`，含时间、设备、APK SHA256、Git HEAD 和是否有未提交改动。dirty=true 时 HEAD 不能代表完整源码；复现前应保存差异或提交当前改动。
- 模拟器启动日志：`.local/logs/`；截图：`.local/screenshots/`。这些文件不提交。
- 使用 [验收模板](templates/manual-test.md) 记录有价值的里程碑；引用产物身份，不能只写“测试通过”。

## 分层验证

1. 基础工程：编译 / Lint、打开应用、重复安装、前后台、深浅主题和系统栏。
2. 文本编辑功能：中文组合输入、emoji、选区、撤销、长按菜单、软键盘遮挡、字体缩放、旋转、长文滚动。
3. 存储功能：杀进程后恢复、保存竞争、磁盘/权限失败、导入导出往返、覆盖冲突。
4. 真机：输入法、性能、后台回收与不同厂商行为；模拟器结果不可替代。

业务规则已有 JVM 测试；平台交互使用单独测试 APK 的原生 Instrumentation / UiAutomation，未新增 UI 测试运行库。测试 provider 只存在测试 APK，提供合成只读源文件及导出目标；不向正式应用加入测试入口。

```powershell
# 先按设备策略识别目标；下面仅示意本次明确要求的模拟器
$env:JAVA_HOME = "$env:USERPROFILE\DevTools\Android\Jdk\17.0.18+8"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# Run 会构建并保留数据重启应用；先等正文已保存
pwsh -NoProfile -File scripts/android.ps1 -Action Run -Serial emulator-5554
& "$env:USERPROFILE\DevTools\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& "$env:USERPROFILE\DevTools\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am instrument -w io.github.xrisium.makudon.debug.test/io.github.xrisium.makudon.WorkspaceInstrumentation
```

该 runner 输出逐项 PASS 和最终汇总，任一 FAILED / Process crashed 都视为失败，不能只看 adb 退出码。它会创建合成文稿、验证长按 / 自动保存 / 目录操作并保留部分测试资料供用户试用，不清数据或修改已有文稿。`-e case long` 只执行长文边界，不能代替完整流程。模拟器序列号每次需重新确认。

首个编辑切片的 [详细验收规范](testing/plain-text-editor-acceptance.md) 已形成草案，覆盖保存竞争、失败注入、真实进程中断、恢复、中文输入与长文性能；目前全部待执行，原基础工程验收不能替代这些结果。

## 常见问题

- **AVD 已在启动 / offline**：等启动完成重试；脚本宁可报错也不启动同一 AVD 的第二实例。可先 `Devices` 查看。不要删除 AVD 解决启动慢。
- **启动超时**：查看 `.local/logs`，可加 `-BootTimeoutSeconds 300`。WHPX 失败参照共享环境说明处理，不自动修改 BIOS/Windows 功能。
- **快照异常**：手动关闭对应模拟器后 `-Action Run -ColdBoot`；这只跳过加载快照，保留 userdata。
- **安装签名不匹配 / 降级错误**：保留错误与现有数据，先决定备份方案；脚本不会自动卸载或允许降级。
- **应用秒退**：Run 会检查启动状态和进程。可用 `adb -s SERIAL logcat -b crash -d` 查崩溃，不记录私人正文。
- **频繁测试资源占用**：保留一个 AVD 与 Gradle daemon；不必每次退出模拟器。完成工作后正常关闭模拟器即可。
- **终端双击后消失**：失败会暂停保留日志；成功后窗口关闭，模拟器继续运行。

不设置 commit 后自动弹模拟器的 hook：Git 提交应可独立、可重复。用户可在每个功能/commit 后主动按同一快捷键测试。
