# 第三方素材与许可证管理

适用范围：字体、图标、插图、照片、音效及基于它们生成的品牌素材；代码依赖也必须保留其许可，但不以素材清单代替依赖审查。

## 引入原则

1. 优先官方来源和有明确许可证的素材。网页可下载、标注“免费”或搜索结果可见不等于可再分发；权利不明时先使用自制占位素材，不进入发布包。
2. 每次引入记录名称、作者 / 权利人、官方链接、实际下载 URL、固定版本或 commit、日期、SHA-256、许可证及使用位置。来源版本更新时一并更新记录，不仅指向会变动的 `main`。
3. 将实际分发素材对应的完整许可和版权声明放在 `docs/licenses/`，根 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 提供入口；不得用项目 MIT 许可证覆盖第三方素材许可。
4. 记录裁剪、子集化、转路径、颜色或几何修改及生成工具。若分发修改后的字体，核对原字体的 Reserved Font Names 和重命名要求，不自动把“自用”推定为允许再分发。
5. 分开标记“设计时使用”“已提交资产”“已进入 APK”。只使用字体制作图形，不等于把字体软件打包；字体原文件不因曾用于设计而自动加入 Android 资源。
6. Android 实现时，随包携带必要许可，并提供离线可读入口；发布前检查打包结果。**当前设置 → 开源许可与素材致谢及原文打包已实现**；正式发布仍需完整解析依赖审计，不能只检查仓库文档。
7. 仅引入必要字重、图标和图片；记录包体影响与替代方案。正式应用本地加载资源，不在运行时访问 Google Fonts / CDN，不增加网络权限。
8. 用户自行选择的字体文件与项目随包素材分开处理，不自动上传、对外分发或随文稿导出。实现自选字体时提示用户自行确认使用权。
9. 发布检查实际使用文件、素材清单、许可文件与 APK 是否对应；删除素材时确认是否仍有派生物需要来源记录，不机械删除全部致谢。

## 当前素材记录

更新：2026-10-25。品牌 / 字体来源见 [品牌 provenance.json](../assets/brand/provenance.json)，文件图标见 [图标 provenance.json](../assets/icons/provenance.json)，均记录固定下载 URL、commit 与哈希。

| 素材 | 来源 / 权利人 | 许可与本轮用途 | 分发状态 |
| --- | --- | --- | --- |
| Fredoka | [Google Fonts](https://fonts.google.com/specimen/Fredoka)，The Fredoka Project Authors | [OFL 1.1 原文](licenses/fredoka-OFL.txt)；600 字重生成 Makudon 路径字标 | 提交 SVG 图形；字体原文件仅在忽略的 `.local/` 作设计输入，未嵌入应用 |
| Noto Sans SC | [Google Fonts 官方仓库](https://github.com/google/fonts/tree/e7db6ca603fd4862010383ff17ffd6cc4ffac1c0/ofl/notosanssc)，Adobe 版权声明 | [OFL 1.1 原文](licenses/notosanssc-OFL.txt)；预览使用子集，应用使用完整原始字体 | 已加入 `res/font` 与 APK；原文件约 16.95 MiB、APK 压缩约 10.75 MiB，真实 450 / 500；未修改字体 |
| Lucide | [官方许可证](https://lucide.dev/license)，Lucide Icons and Contributors；部分图标源自 Cole Bemis / Feather | [完整 ISC 与 Feather MIT 声明](licenses/lucide-LICENSE.txt)；应用采用固定 commit 的 39 个按需图标 | 已转 VectorDrawable 并随 APK 分发，无图标运行库；逐文件来源 / 哈希见 [Android 清单](../assets/android/provenance.json) |
| Fluent UI System Icons / Markdown 20 Filled | [微软官方仓库](https://github.com/microsoft/fluentui-system-icons/tree/82ecf7cbdb43684403c7125efa160be04a33dca5)，Microsoft Corporation | [完整 MIT 声明](licenses/fluentui-system-icons-LICENSE.txt)；Markdown 文件类型标识 | 原始 SVG 保留，已转 Android 向量资源并集成 APK，无 Iconify 运行库 |
| Android robot | Google LLC，[官方品牌指南](https://developer.android.com/distribute/marketing-tools/brand-guidelines)，[原始 SVG](https://developer.android.com/static/images/brand/android-head_flat.svg) | [CC BY 3.0 原文](licenses/cc-by-3.0.txt)；文件选择器标识，署名见根目录及应用内离线许可 | 2026-10-31 下载，原始 SVG SHA-256 `5f05dae657da9f31e5d4a09b7976f39881617f6b3e4d4562d9ee16295d250bdb`；保留原文件，转换为 VectorDrawable，保持路径和颜色，按原图 152:89 比例适配 28dp 图标位，随 APK 分发；向量文件 SHA-256 `87a0ce0721cbea7412848a42264e780318a0f25d5ffb90b1e1f10ecb1a1f03d5` |
| 原始 Makudon 图标 | 用户提供的根 [icon.svg](../icon.svg) | 本项目资产，保持根文件原样 | 已有 Android 图标使用；本轮组合标志复用其几何 |

Fredoka 转路径生成的字标是设计作品，与分发字体软件不同；OFL 官方 [FAQ 1.1–1.1.2](https://openfontlicense.org/ofl-faq/) 说明了 Logo / 图形的使用与许可区别。本项目仍主动保留来源及许可副本以便追溯；不将“转路径”作为处理其他素材许可的通用豁免。

## 后续实际导入时补齐

- 字体：最终字体文件、覆盖范围、字重、子集 / 修改说明、哈希、包体增量、离线及缺字回退验证。
- 图标：上游版本、具体图标名称、源文件和 Android 转换结果、是否存在 Feather 派生项；保留完整上游许可最省误差。
- 图片 / 音频：作者、来源、署名要求、改作 / 再分发范围；涉及肖像、品牌等其他权利时另核对授权。
