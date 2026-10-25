# 验收：首个原生工作区开发版

- 日期 / 测试者：2026-10-25 / Codex；用户明确指定本轮使用模拟器。
- 源码：`fbdb519`（构建）、`2959f51`（存储）、`0fce9ed`（工作区）、`89f9504`（导出边界保护）。测试时有本地改动，最终文档提交不改变 APK，不以旧 HEAD 冒充当前源码。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，21,860,174 字节；`io.github.xrisium.makudon.debug` / `0.0.1-dev`。
- APK SHA256：`dd9fa38f3ae2640160796afe798645dd644429b57702f0ca98c4aa94db5784ea`。最终安装记录见被忽略的 `.local/last-run.json`。
- 设备：既有 Android_API_36，Android 16 / API 36，1080×2400 / 420dpi；Gboard `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`。模拟器曾有 480px 历史覆盖尺寸，已恢复物理尺寸；字体测试结束后恢复 1.0。
- 未操作真机；没有卸载应用、清数据、wipe AVD 或新增系统镜像。

## 自动与人工检查

| 操作 | 预期 / 实际 | 结果 |
| --- | --- | --- |
| `pwsh -NoProfile -File scripts/android.ps1 -Action Check` | debug 构建、23 项 JVM 测试、严格 Lint，无 baseline；固定版本升级提示仅在版本声明文件局部排除 | 通过 |
| 保存虚拟时钟 / 慢 writer / 失败注入 | 500ms 停顿与 2s 上限；一个在途 + 最新待写，旧回执不确认新版本；失败保留输入并暂停自动重试 | 通过 |
| 私有正文与目录索引 JVM 故障用例 | previous 写失败不动主稿、部分 `.new` 不成为主稿、索引发布 / 重读失败阻止后续写入、恢复前保留坏稿现场 | 通过 |
| 目录规则 JVM 用例 | 冲突不覆盖、目录环拒绝、复制独立 ID / 正文、递归删除保留同级稿、自然排序及混排比较器一致性 | 通过 |
| 原生 Instrumentation 完整流程 | 29 项逐项 PASS；真实按住手势、无障碍长按动作、名称输入、取消 / 确认删除、复制移动、跨视图修改与撤销 | 通过 |
| 导入 / 导出 provider 边界 | 测试 APK 独立合成只读源；BOM / CRLF 处理、非法 UTF-8 拒绝、编辑后源字节不变、导出使用捕获快照，异常 file URI 不覆盖私有稿 | 通过 |
| VIEW 与 Activity 重建 | Manifest 可匹配 Markdown 候选，热入口新建独立副本，Activity 重建不重复导入、不替换活动正文 | 通过 |
| 10 万码点输入 | 单段 10 万中文与含 emoji 的多段 10 万码点可保存；完整磁盘字节一致，超限粘贴整次拒绝 | 通过；非完整性能验收 |
| 真实进程结束与重开 | 已保存合成稿 force-stop 前后 SHA256 完全一致；重启默认最近，列表可达 | 通过 |
| 外部布局 / 交互复核 | 10 项检查，名称搜索过滤、排序按钮状态、主题切换、200% 系统字体和 320dp 工具栏；截图人工查看 | 通过 |
| 字体 / 图标 / 许可 APK 核对 | 仅完整 Noto 字体，无 Fredoka；39 个 Lucide 向量哈希与来源对应，完整字体哈希一致；随包许可原文存在 | 通过 |

长文仅有少量模拟器样本：单段 10 万中文输入至保存约 0.8–1.1s；不据此推导 p95。高比例 emoji 的 10 万码点单段曾超过 12s 测试等待，随后观察到已保存状态，仍需专项优化，未作为流畅输入验收通过。含 emoji 的多段样本保持完整性；未擅自缩小 10 万码点上限。

测试资料全部为合成内容。runner 会实际创建文稿与目录，保留部分合成稿供用户试用；重复测试的同名稿触发过冲突保护，后续改用唯一名称，未清数据。早期测试夹具独立进程缺少 Kotlin 运行时，已改为仅平台 Java 的 provider；该故障属于测试 APK，不是应用主进程崩溃。测试脚本曾误选无障碍包装节点 / 动画中的坐标，已改为可编辑节点及稳定条目动作，最终全流程重新通过。

证据留在 `.local/native-final-instrumentation.log`、`.local/native-review/`、`.local/last-run.json`，不提交截图、正文或设备日志。

## 复现

```powershell
pwsh -NoProfile -File scripts/android.ps1 -Action Devices
pwsh -NoProfile -File scripts/android.ps1 -Action Check
# 本轮用户明确要求模拟器。每次重新确认 serial，先等待编辑器已保存。
pwsh -NoProfile -File scripts/android.ps1 -Action Run -Serial emulator-5554
```

完整 instrumentation 的构建、安装和运行命令见 [测试指南](../testing.md)。判定必须读取 `Passed 29 checks` 且没有 `FAILED` / `Process crashed`，adb 返回 0 本身不证明成功。

## 未覆盖与交接

- [首片完整清单](plain-text-editor-acceptance.md)未全部通过：真机中文 composition、200% / TalkBack 全流程、长文多样本 p95、断电和每一个真实文件提交中断阶段仍待验证。
- 导入导出已验证真实 ContentResolver / PFD 路径，但不是所有 DocumentsUI / 文件管理器 / 云 provider 的兼容验收；真正冷启动外部 VIEW、provider 失效与长时间阻塞仍需补测。
- 完整富文本、跨段连续选择、相对图片、独立备份、回收站、索引重建和孤立文件维护工具没有实现。
- 当前适合在模拟器用合成文稿体验界面；实际使用应定期导出副本。用户亲自确认编辑舒适度后，再安排真机与完整故障恢复验收。
