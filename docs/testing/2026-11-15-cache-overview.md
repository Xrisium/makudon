# 缓存概览与全量清理确认验收

- 日期：2026-11-15；需求与文案审查始于 2026-11-14。
- 范围：[任务 0040](../tasks/0040-cache-overview-and-ui-audit.md)、[设计规则](../design.md)、[审查及用户决定](../reviews/2026-11-14-ui-copy-and-settings-layout.md)。
- 工具：已有 JDK 17.0.18+8、离线 Gradle 缓存、Android_API_36 AVD。普通启动超时，结束已验证属于该 AVD 的卡住进程后保留数据冷启动成功；没有 wipe、下载环境或改用真机调试。

## 自动验证

- `Check -Offline`、严格 Lint、测试 APK 构建通过；183 项 JVM 测试零失败 / 错误 / 跳过。
- API 36 `thumbnail-cache` 专项 **47 项通过**：指定说明已移除，缓存概览位于占用字段上方；全量清理先确认、取消保留内存 / 磁盘和偏好、确认状态经 Activity 重建保留、冲突操作受门控，确认后全部缓存清空而开关及原图保持。
- 同时回归三个场景的单独确认 / 取消 / 清理、空缓存直接关闭、写盘失败保护、旧解码阻断、按钮像素稳定与其他预览统计。中英文 320dp / 200% 字号下，全量清理及单场景确认均可操作，取消不改数据。
- API 36 `backup` 回归 **26 项通过**：增量冲突 / 单独保存 / 完全覆盖、取消恢复、快照撤回、导出 ZIP、保存失败屏障等。合计 **73 项模拟器检查通过**。

日志仅在 `.local/cache-overview-check.log`、`.local/cache-overview-test-build.log`、`.local/cache-overview-thumbnail-cache.log`、`.local/cache-overview-backup.log`，不提交大日志。

## 视觉与文案审查

已查看 `.local/screenshots/cache-overview/overview.png`、`confirm-en-200.png`、`backup-conflict.png`：外置分类标题与概览 / 操作分隔线符合规范，英文大字体确认完整可达，C7 使用“同一路径的内容不同，请选择保留方式。”。同目录另存普通 / 简中大字体确认截图，供复查。

已按用户确认保留 C1–C6 必要说明，C4 补充 JPEG、PNG、静态 WebP 图片；C7–C8 双语精简。布局 L1–L4 仅列出，不将报告当作批量改造授权。

## APK 与单次交付

- 包名 `io.github.xrisium.makudon.debug`，Makudon Dev，`0.1.0-dev`，构建号 `2`，min SDK 26 / target SDK 36。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；固定副本 `.local/deliveries/Makudon-0.1.0-dev-cache-overview.apk`，80,657,291 字节。
- SHA-256：`933552E0BCEF287968722F88B45BBE16119C12F3A2049A60CC8AFF8A5D35EE1A`。
- 重新识别后，唯一在线真机为华为 LIO-AN00；已通过本次识别目标执行单次推送并发起保留数据覆盖安装。初始等待仅取得上述 SHA-256 的安装启动输出，未取得完成回执；未轮询、重复安装、启动应用或查询进程，手机端确认和人工验收由用户完成。未重置真机数据，未新增依赖、权限或工具链。
- 代码 / 测试提交 `6293115`，不 push。

## 人工验收

1. 开发版设置 → 缩略图缓存中，指定场景原理说明不再显示，其位置为“缓存概览”；卡片内为“缓存总占用”和“清理全部缓存”，信息 / 操作以分隔线区分。
2. 点击清理全部缓存，先取消，确认占用和开关不变；再次点击并确认，确认占用归零、开关保留、原图仍可查看。
3. 检查中英文 / 大字号的显示与操作，以及备份格式说明包含图片、冲突说明和文件操作错误已精简。真机体验仍待人工验收，模拟器通过不等于真机通过。
