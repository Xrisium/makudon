# 高频测试指南

## 最短路径

按 [设备选择约定](device-policy.md)：**Agent 会话测试优先模拟器，完成后集中向测试真机覆盖安装；除非用户要求，不直接通过数据线调试真机。**

日常使用无参数 `test-android.cmd` 或 `Ctrl+Shift+B` 复用 / 启动配置 AVD。最终交付前重新识别测试真机，唯一在线真机直接选取并显式指定序列号；多台且未指定时请用户选择。真机未授权 / offline 时记录交付未完成，不反复安装或转为真机自动化。

除非本轮用户要求或确有必须真机调试的原因，真机交付仅单向推送 APK 并发起覆盖安装，随后即视为完成该子任务；不二次询问安装是否完成，不追加启动 / 进程检查，也不等待手机端确认。已有命令明确报错则如实记录，详见[交付完成规则](device-policy.md#日常测试与最终交付)。

无 `-Serial` 的 Run / Screenshot / Logcat 选择配置 AVD，不会自动转向真机；脚本不创建新镜像、卸载、清数据或 wipe。

日常迭代先集中完成本地修改、增量构建与 `Check`，禁止在真机上频繁、反复覆盖安装，测试 APK 同样适用。最终交付合并安装；额外安装须说明原因和影响并获用户明确授权。用户叫停后本轮不再安装，也不改用模拟器绕过，具体见 [安装频率约定](device-policy.md#安装频率与测试数据)。正常退出保存的 Quick Boot 快照通常可加快下次模拟器启动。

连接的测试真机只用于本软件人工测试，可按需要覆盖、重建或重置 Makudon 开发包的文稿与目录树，无需逐次确认；不默认卸载应用、清除其他设置或操作其他应用。该测试授权不改变产品数据保护规则。覆盖安装会重启进程，交付前提示并记录本次是否重置文稿。

**覆盖安装保留已持久化数据，重启进程不会保留仅在内存中的未保存内容。未来编辑器接入前后都应先保存再运行测试入口。**

## 常用操作

```powershell
# 日常 Agent 测试优先模拟器
pwsh -NoProfile -File scripts/android.ps1 -Action Emulator
# 构建与静态检查，不启动设备
pwsh -NoProfile -File scripts/android.ps1 -Action Check
# 查看设备，包括 unauthorized/offline 状态
pwsh -NoProfile -File scripts/android.ps1 -Action Devices
# 完成本地 / 模拟器验证后的最终交付：替换为目标真机 serial
& "$env:USERPROFILE\DevTools\Android\Sdk\platform-tools\adb.exe" -s YOUR_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
# 日常截图与日志：使用本次识别的模拟器序列号
pwsh -NoProfile -File scripts/android.ps1 -Action Screenshot -Serial EMULATOR_SERIAL
pwsh -NoProfile -File scripts/android.ps1 -Action Logcat -Serial EMULATOR_SERIAL
```

真机交付需要设备已授权，不自动安装未知手机驱动；手机一律显式指定 `-Serial`。除非用户要求，截图、日志与自动化均在模拟器进行，不在真机安装测试 APK。

## 产物与复现

- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- Lint：`app/build/reports/lint-results-debug.html`。
- 未来的 JVM 单测报告：`app/build/reports/tests/testDebugUnitTest/index.html`；尚无单测时不会生成报告，任务为 `NO-SOURCE`。
- 最近 Run 记录：`.local/last-run.json`，含时间、设备、APK SHA256、Git HEAD 和是否有未提交改动。dirty=true 时 HEAD 不能代表完整源码；直接 adb 单向交付不会更新此文件，须另记本次推送的设备 / APK 身份，不把旧记录当作本次安装证据。
- 模拟器启动日志：`.local/logs/`；截图：`.local/screenshots/`。这些文件不提交。
- 使用 [验收模板](templates/manual-test.md) 记录有价值的里程碑；引用产物身份，不能只写“测试通过”。

## 分层验证

1. 基础工程：编译 / Lint、打开应用、前后台、深浅主题和系统栏；确需验证覆盖安装行为时单独安排，不作为每轮迭代的默认操作。
2. 文本编辑功能：中文组合输入、emoji、选区、撤销、长按菜单、软键盘遮挡、字体缩放、旋转、长文滚动。
3. 存储功能：杀进程后恢复、保存竞争、磁盘/权限失败、导入导出往返、覆盖冲突。
4. 真机：输入法、性能、后台回收与不同厂商行为；模拟器结果不可替代。

业务规则已有 JVM 测试；平台交互使用单独测试 APK 的原生 Instrumentation / UiAutomation，未新增 UI 测试运行库。测试 provider 只存在测试 APK，提供合成只读源文件及导出目标；不向正式应用加入测试入口。

```powershell
# 以下为一次集中验收的命令示例，不应在每次修改后重复执行安装
# 先按设备策略识别当前模拟器目标
$env:JAVA_HOME = "$env:USERPROFILE\DevTools\Android\Jdk\17.0.18+8"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# Run 会构建并保留数据重启应用；先等正文已保存
pwsh -NoProfile -File scripts/android.ps1 -Action Run -Serial emulator-5554
& "$env:USERPROFILE\DevTools\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& "$env:USERPROFILE\DevTools\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am instrument -w io.github.xrisium.makudon.debug.test/io.github.xrisium.makudon.WorkspaceInstrumentation
```

该 runner 输出逐项 PASS 和最终汇总，任一 FAILED / Process crashed 都视为失败，不能只看 adb 退出码。它会创建合成文稿、验证长按 / 自动保存 / 目录操作并保留部分测试资料供用户试用，不清数据或修改已有文稿。`-e case long` 只执行长文边界，不能代替完整流程。模拟器序列号每次需重新确认。

离散页签回归使用 `-e case tabs`，覆盖 19 项长按 / 拖动文字像素稳定、松手识别、取消、边界、跨标签、重复操作与旧回执、重建及零动画倍率；见 [离散页签验收](testing/2026-10-25-discrete-tabs.md)。按用户明确授权，频繁自动化在模拟器执行，最终真机仅集中安装应用 APK，交由用户本人验收。

配色与底栏专项使用 `-e case appearance`，覆盖 17 项重复选中配色不写盘 / 状态与像素不变、延迟持久化时文字颜色稳定且导航仍互斥、纸白→米杏 / 雾蓝→鼠尾草的背景和文字中间像素，以及单个底栏强调背景的真实横移、单向运动、准确落点和重复点击稳定。测试通过反射暂时持有现有 I/O 锁制造可观察的慢写盘，不增加产品测试入口；系统动画倍率与原主题在 finally 恢复。文字亮度允许两个配色本身的差异，不能把正常渐变误判为闪烁。

复选与统一剪贴板使用 `-e case selection`（24 项），覆盖真实触摸、父子规范化 / 继承 / 半选、单项与批量互通、跨目录目标无默认值且一次择一、取消 / 清空 / X、重建、剪切成功后清空、删除源后快照可用。`-e case selection-visual`（2 项）临时使用实际 320dp / 系统字号 200%，检查顶部、四操作和目标确认可达，在 finally 恢复屏幕 / 字号 / 主题并清理本次合成目录。见[本次验收](testing/2026-10-26-multiselect.md)；设备仍须每轮重新识别并遵守当前授权。

动效回归提供 `-e case motion`（26 项横滑、状态恢复、保存失败、生命周期、主题和零动画倍率）与 `-e case motion-visual`（8 项退场会话、主题像素、中间帧、系统深浅、许可页及实际 200% 字体检查）。不指定 case 或指定 `workspace` 执行原有 29 项完整流程。画面用例会临时修改动画倍率、字号和夜间模式，在 `finally` 恢复；截图只保存于应用测试缓存，取出后放 `.local/`。详见[动效验收](testing/2026-10-25-motion.md)。

首个编辑切片的 [详细验收规范](testing/plain-text-editor-acceptance.md) 已形成草案，覆盖保存竞争、失败注入、真实进程中断、恢复、中文输入与长文性能；目前全部待执行，原基础工程验收不能替代这些结果。

最近日期与编辑菜单使用 `-e case recent`：合成四档文稿，检查分组与日期、系统日期 / 时区广播刷新、真实长按、编辑菜单、重命名冲突 / 标题快捷入口、删除取消、保存失败阻止删除、删除后来源导航和无延迟保存复活。系统时间 / 时区 / 自动时间配置在 finally 恢复，本次合成文稿在 finally 清理。此用例只在模拟器执行，不默认对真机做时间修改或故障注入。

## 常见问题

ZIP 备份恢复使用 `-e case backup`（27 项），真实 320dp / 200% 字号用 `-e case backup-visual`（2 项）。覆盖标准电脑 ZIP、三种模式、逐项冲突、覆盖确认 / 取消、快照交换、迟到保存、普通重读后继续保存、SAF 导出 / 源不变及保存失败屏障。测试跟随库指针选择当前代际，不再假定权威正文始终在旧 workspace 目录；详见 [ZIP 验收记录](testing/2026-10-27-zip-backup.md)。

- **AVD 已在启动 / offline**：等启动完成重试；脚本宁可报错也不启动同一 AVD 的第二实例。可先 `Devices` 查看。不要删除 AVD 解决启动慢。
- **启动超时**：查看 `.local/logs`，可加 `-BootTimeoutSeconds 300`。WHPX 失败参照共享环境说明处理，不自动修改 BIOS/Windows 功能。
- **快照异常**：手动关闭对应模拟器后 `-Action Run -ColdBoot`；这只跳过加载快照，保留 userdata。
- **安装签名不匹配 / 降级错误**：保留错误与现有数据，先决定备份方案；脚本不会自动卸载或允许降级。
- **应用秒退**：Run 会检查启动状态和进程。可用 `adb -s SERIAL logcat -b crash -d` 查崩溃，不记录私人正文。
- **频繁测试资源占用**：保留一个 AVD 与 Gradle daemon；不必每次退出模拟器。完成工作后正常关闭模拟器即可。
- **终端双击后消失**：失败会暂停保留日志；成功后窗口关闭，模拟器继续运行。

不设置 commit 后自动弹模拟器的 hook：Git 提交应可独立、可重复。用户可在每个功能/commit 后主动按同一快捷键测试。
