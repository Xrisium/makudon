# 分场景缩略图缓存验收

- 日期：2026-11-13
- 范围：[任务 0039](../tasks/0039-scoped-thumbnail-cache.md)、[ADR 0014](../adr/0014-scoped-thumbnail-cache.md)。
- 环境：已有 JDK 17.0.18+8、离线 Gradle 缓存和 Android_API_36 AVD。开始时模拟器未运行，复用原有 AVD 启动，未下载环境或清除 AVD 数据。

## 自动验证

- `Check -Offline`、严格 Lint、测试 APK 构建通过；183 项 JVM 测试零失败 / 错误 / 跳过。
- 新增磁盘测试覆盖同一内容在四个场景的独立版本 / 统计、旧共享文件计入其他预览、单场景清理及旧任务阻断、其他场景旧任务正常提交、部分失败保留其他缓存和原图、跨场景统一 LRU 上限。
- API 36 `thumbnail-cache` 专项 **36 项通过**：三个场景确认 / 取消 / 无缓存直接关闭、真实索引写入失败不清缓存、只清理指定内存与磁盘、其他场景保留、仅内存命中时也确认、旧解码被阻断、重载保留选择及原图字节不变。
- 在真正持有 IO Mutex 阻塞设置写盘时，清理按钮前后截图裁片逐像素一致；重复点击仍被回调拦截，未触发冲突清理。
- API 36 图片回归 **83 项通过**，包含导入、EXIF / 宽长图、预览边界、阅览菜单、引用 / 撤销、导出 / 删除 / 恢复、内存 / 磁盘命中、清理并发，以及中英文 320dp / 200% 字号。合计 **119 项模拟器检查通过**。

日志位于 `.local/scoped-cache-check.log`、`.local/scoped-cache-test-build.log`、`.local/scoped-cache-emulator.log`、`.local/scoped-cache-images.log`，不提交大日志。

## 截图审查

已审查 `.local/screenshots/scoped-cache/`：

- `sizes.png`：三场景分别显示实际磁盘字节，总占用包含其他预览。
- `confirm.png`、`zh-Hans-confirm-200.png`、`en-confirm-200.png`：关闭确认、取消和中英文大字体操作完整可见；长说明可滚动。
- `en-page-200.png`：缓存页可滚动到清理按钮。
- `settings-order.png`：字体位于颜色主题后、目录前。

## APK 与交付

- 包名 `io.github.xrisium.makudon.debug`，Makudon Dev，版本 `0.1.0-dev`，构建号 `2`，min SDK 26 / target SDK 36。
- APK `app/build/outputs/apk/debug/app-debug.apk`，固定副本 `.local/deliveries/Makudon-0.1.0-dev-scoped-cache.apk`，80,656,515 字节。
- SHA-256：`FDCC15E25AF7BA2F2841ECB3ACBC5886C5F1FDC1E77DC7BDB711BDDD6A2CE29B`。
- 重新识别后唯一在线真机为华为 LIO-AN00，已单次推送并发起保留数据覆盖安装。初始等待仅取得上述 SHA-256 的安装启动输出，未取得完成回执；未轮询、追加安装、启动应用、查询进程或开展真机调试。手机端确认及人工验收由用户完成。未重置真机数据，未新增依赖、权限或工具链。
- 代码 / 测试提交 `7fe19a4`，不 push。

## 人工验收

1. 设置中确认字体位于颜色主题与目录之间；进入缩略图缓存，查看三个场景的占用及总占用。
2. 先浏览图片以生成缓存，再关闭对应场景：取消应保持开启与占用；确认应关闭并清零该场景，不改变其他场景及原图。再次关闭没有缓存的场景不应弹出确认。
3. 切换开关时清理按钮外观应保持稳定；重新进入 / 重启后选择保留。确认清理的失败提示及真机输入 / 字体体验仍需人工验收，模拟器结果不等于真机验收。

旧共享文件归入其他预览，可通过全量清理回收。相同内容在不同场景现在可能各占一份缓存，统一总上限不变；原图、文稿、回收站、快照和其他临时材料均不在清理范围。
