# Android 素材集成

2026-10-25：品牌组合、39 个按需 Lucide 图标、Fluent Markdown 20 Filled 和完整 Noto Sans SC 可变字体已进入 Android 资源。固定来源、下载哈希、转换结果哈希见 [provenance.json](provenance.json)。

- 图标从固定上游 SVG 转为 VectorDrawable，保留路径 / 图形几何，继承主题 tint。Lucide 保留 24px 画布和 2px 圆端点线宽；Markdown 保留原生 20px 画布。未引入整套图标字体或运行库。
- 源仓库对旧别名的文件名已调整：`file-plus-2` 对应 `file-plus-corner`，`trash-2` 对应 `trash`，`more-horizontal` 对应 `ellipsis`。记录的是实际固定下载 URL。
- 2026-10-26 新建入口调整复用已有资源：最近为 `ic_plus`，目录为 `ic_file_plus_2`（实际来源 `file-plus-corner.svg`）。无新增素材、下载或依赖，固定来源、原始 / 转换哈希和许可保持不变。
- Noto Sans SC 使用 [品牌来源记录](../brand/provenance.json)中的原始完整字体，100–900 可变轴，未裁剪字形。两个字体角色分别指定 UI 500 / 正文 450，共享一个 17,772,300 字节文件；APK 中压缩约 10.75 MiB。采用完整覆盖换取中文编辑能力，不能用设计例文子集缩小正式编辑器字体。
- Fredoka 未打包，仅分发已经转路径的字标。根 icon.svg 保持原样；品牌组合通过嵌套向量组保留原变换。
- 原字体、Lucide、Fluent、Fredoka 及 AndroidX / kotlinx.coroutines 许可合并随包放在 `res/raw/third_party_notices.txt`，设置页提供离线入口。最终 APK 仍需核对资源清单与许可；正式发布前补齐完整解析依赖的分发审计。

资源没有运行时网络加载，不新增网络权限。字体切换只保留 UiFont / EditorFont 角色，尚无系统字体 / 自选文件设置实现。
