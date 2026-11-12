# 图片缓存二级设置与菜单验收

- 日期：2026-11-13
- 范围：[任务 0038](../tasks/0038-image-cache-and-menus.md)
- 环境：现有 JDK 17.0.18+8、离线 Gradle 缓存、API 36 配置 AVD；交互、截图和 instrumentation 均仅在模拟器执行。
- 本轮无新增依赖、工具链或权限；未安装新环境，未重置真机数据。

## 自动验证

- `pwsh -NoProfile -File scripts/android.ps1 -Action Check -Offline` 通过，179 项 JVM 测试零失败 / 错误，严格 Android Lint 通过；测试 APK 编译通过。
- 业务测试新增旧偏好缺省开启、三个偏好独立持久化、发布失败后停止写入且保留原索引，以及缓存策略拒绝旧任务写回。
- API 36 模拟器最终图片专项 **83 项**、Markdown **33 项**、字号 **25 项**、导入导出 **39 项**，合计 **180 项通过**。覆盖分场景缓存命中 / 绕过 / 不写入、偏好失败回退、清理和解码竞争、图片信息 / 重命名 / 导出 / 引用影响删除、正文大小与查找顺序、新建文案、中英文小屏和大字体。

日志只保留在 `.local/image-menus-*-final.log`、`.local/image-menus-check.log` 和 `.local/image-menus-test-build.log`。回归脚本先从二级页恢复的滚动位置回到开关区；字号测试先进入源码，再点击排版，以验证真实切换，避免重复点击已选中且无点击动作的标签；系统保存取消测试等待实际结果回调，避免窗口切换时的重复返回关闭应用导出面板。图片引用扫描使用 60 秒有界等待，超时保留实际忙碌及导航状态。

## 截图审查

实际截图保留在 `.local/screenshots/image-menus/`，不提交大文件：

- `images-cache-page.png`：原生二级页面、三开关、占用与清理。
- `images-viewer-menu.png`：详细信息、重命名、导出图片、分隔线、删除。
- `images-editor-menu-source.png` / `images-editor-menu-plain.png`：正文大小、查找与替换、文件操作的顺序。
- `images-new-page.png`：“导入文稿”与“导入图片”相邻，旧导入说明移除。
- `images-zh-Hans-large-cache-clear.png` / `images-en-large-cache-clear.png`：320dp / 200% 字号下滚动至底部，清理操作完整可见。

## APK 身份与交付

- 包名：`io.github.xrisium.makudon.debug`（Makudon Dev）。
- 版本：`0.1.0-dev`，构建号 `2`，min SDK 26 / target SDK 36。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，80,653,463 字节；固定副本 `.local/deliveries/Makudon-0.1.0-dev-image-settings.apk`。
- SHA-256：`7AF269D3A70B7798E09B3F468B123CCF2CEFE3997CE0CCB83FF9C8EF8ED2DE32`。
- 重新识别后，唯一在线真机为华为 LIO-AN00。已通过 `InstallDevice -Serial <本次识别目标>` 单次推送 APK 并发起保留数据覆盖安装；初始等待仅取得上述 SHA-256 的安装启动输出，未取得完成回执。遵守单向交付策略，未轮询、重复安装、启动应用或查询进程，未重置真机文稿。手机端确认和人工验收待用户完成；正式包未改动。
- 业务 / 测试提交：`d0fef27`；未 push。

## 人工验收步骤

1. 在 Makudon Dev 的设置 → 数据管理 → 缩略图缓存进入二级页，分别切换目录树、长按菜单和更多信息，返回设置并重新进入，确认选择保留。清理后显示实际剩余空间，原图仍可打开。
2. 打开目录图片，使用右上角菜单查看详细信息、重命名和导出原图；确认像素单位为“像素”，名称及时更新，缩放和拖动仍可用。删除被文稿引用的图片时应列出文稿；先取消，再确认删除并从回收站恢复，确认引用重新可用。
3. 在纯文本、Markdown 源码与排版中打开菜单，检查查找与替换位于字号控件下方；打开新建页，确认“导入文稿”及原图标保留，旧说明框消失。
4. 真机中文输入法、横屏和字体缩放手感、回收站只读图片信息 / 导出、旧版 Android 仍待人工验收。本轮未进行真机交互、日志或截图调试。
