# 文件类型图标素材

更新：2026-10-25。仅用于讨论例图，尚未集成 Android。

- [fluent/markdown-20-filled.svg](fluent/markdown-20-filled.svg)：微软 Fluent UI System Icons 的 Markdown 20 Filled。原生 20×20 画布适合当前目录行和新建页的文件类型标识，避免将 16px 版本放大或加载全套图标库。
- 源 SVG 保持原样，例图将该 SVG 作为 CSS mask，按主题的次级文字颜色着色；不重新描画、不嵌入图标字体、不引入 Iconify 运行库或网络请求。
- 操作按钮继续使用 Lucide。Fluent 是用户指定的 Markdown 文件类型标识例外，不扩展为混用两套操作图标。
- [provenance.json](provenance.json)记录微软上游的固定 commit、原始文件与许可 URL、大小、SHA-256 及使用范围。
- 完整 [MIT 许可](../../docs/licenses/fluentui-system-icons-LICENSE.txt)和根[第三方声明](../../THIRD_PARTY_NOTICES.md)一同保留。Android 转换和 APK 许可携带仍需在实际集成时验证。
