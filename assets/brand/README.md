# Makudon 品牌字标草案

日期：2026-10-24；2026-10-25 集成。状态：用户采用方案，已转换为 Android 矢量组合，原始 SVG 保留。实际资源见 [Android 集成记录](../android/README.md)。

- [makudon-wordmark.svg](makudon-wordmark.svg)：仅文字轮廓，透明背景，`currentColor`。
- [makudon-lockup.svg](makudon-lockup.svg)：原始图标几何 + 字标横向组合，透明背景，`currentColor`。
- [provenance.json](provenance.json)：输入文件固定来源、commit、SHA-256、许可与生成参数。

文字为首字母大写的 **Makudon**，来自 Google Fonts 的真实 Fredoka 可变字体：`wght=600`（Semibold）、`wdth=100`。HarfBuzz 完成文字 shaping 与 kerning，fontTools 提取轮廓，每字增加 4 个字体单位的微小字距（64px em 时约 0.256px）。保留原字形，不人工改变字符造型。

组合中图形可视宽约 66.2 单位，与字标间距 16 单位；按视觉中心对齐，供后续实际尺寸校正。图标的三条原始路径、线宽比例和圆端点不变；只变换比例与位置，将组合版本的白背景去掉以适应主题。根 `icon.svg` 保持原样。

两个 SVG 均只有路径与变换，没有 `<text>`、字体引用、嵌入字体、外部链接或脚本；Fredoka 的任务在轮廓生成后结束，不应放入 Android 字体目录。图形来源和字体软件许可分开记录，见[素材管理规范](../../docs/third-party-assets.md)。

SVG 作为图片使用时，外部元素的 `color` 通常不会传入图片内部；集成时使用可着色的 Android 矢量路径或生成固定浅 / 深色版本，不依赖外层 CSS 继承。路径草案默认可用深色显示，深底需反白。

推荐位置：文稿列表入口、关于页；小型工具栏优先只用图形标志或直接显示当前文稿名。清晰留白建议不小于字标小写字高的约一半，最终以手机实际尺寸校验。
