# 行尾插入手柄与滚动视觉位置回归

日期：2026-11-27。开发与自动化只使用现有 API 36 x86_64 模拟器，JDK 17；版本与工具链未升级。

## 旧版复现

基线为 `c205f96` 应用及增强后的合成文稿专项 `unified-rendering`：

- `tap-ms=0`：前 7 项通过，源码软换行处原始选择为 38，视觉光标在上一行右侧，但没有原生 `SelectionHandleInfo(Cursor)`，第 8 项失败。
- `tap-ms=80`：前 10 项通过，手柄存在且同位；真实滑动保持原始偏移，回到首屏时显示映射丢失，光标回到下一行，第 11 项失败。

原始日志保留在被忽略的 `.local/caret-baseline-quick.log` 和 `.local/caret-baseline-scroll.log`。正文均为专项创建的独立合成样本，未记录用户正文。

## 修正与构建

正文手势仅完成短点触才提交视觉位置；滚动 / 长按 / 取消不清除既有行尾映射。Foundation 1.9.0 把显示映射变化当编辑隐藏手柄的观察器，按 [ADR 0017](../adr/0017-native-caret-observer.md)进行单调用构建变换；保留全部原生手柄及输入路径。

Debug / Release 构建、196 项 JVM 测试和 Debug Lint 通过。用 `javap` 核对实际已变换依赖，目标 lambda 读取 `getUntransformedText`；保留的字节码记录为 `.local/caret-transformed-bytecode.txt`。BOM 与恰好一次命中均有构建守卫，没有新增依赖或权限。

## 模拟器与交付

专项使用真实触摸，跨原生弹出窗口检查 `SelectionHandleInfo` 与菜单语义，并用真实 `InputConnection.commitText` 验证输入。测试专用反射仅用于提取原生内部手柄坐标，产品没有运行时反射。菜单截图实际正常，首次菜单断言因只查询活动窗口而失败，已改成查询弹出窗口；复制后的输入测试也显式恢复首屏再触摸，避免沿用复制前的屏幕坐标。

| 专项 | 实际结果 |
| --- | --- |
| Markdown 源码 / 排版，`tap-ms=0` | 34 项通过 |
| Markdown 源码 / 排版，`tap-ms=80` | 34 项通过 |
| 纯文本编辑，`tap-ms=0, plain=true` | 19 项通过 |
| 选区菜单切视图 `editor-regressions` | 21 项通过，无崩溃，旧菜单关闭 |
| 表格原生输入 `markdown-table` | 43 项通过，含中文组合替换、保存失败及只读切换 |

共 151 项通过，日志为 `.local/caret-fixed-quick4.log`、`caret-fixed-80.log`、`caret-fixed-plain.log`、`caret-editor-regressions.log`、`caret-markdown-table.log`。本地汇总命令最初将成功断言中的 “failed cell save” 误判为失败；已用行首 `FAILED after`、instrumentation 错误和最终 `Passed N checks` 核对全部原始日志，无需重复测试。查看了真实手柄和菜单截图。真实正文输入仍隐藏手柄，源码、复制及撤销无零宽映射字符。

构建提交 `d620e4c`，应用 / 测试提交 `db6349e`，main，未 push。冻结 APK：`.local/deliveries/Makudon-0.1.0-dev-caret-db6349e.apk`，包名 `io.github.xrisium.makudon.debug`，版本 `0.1.0-dev` / code 2，80,666,351 字节；SHA-256：

`9A5DD04E21D969A16341954F8E431459B6897B625A7AD99E0A4DF7FA9FB73CD4`

完成检查后重新识别唯一在线 LIO-AN00，核对实际待安装 APK 与冻结文件哈希一致，显式指定序列号执行一次 `InstallDevice`。已推送 APK 并发起保留数据覆盖安装，初始等待未取得成功回执；不追加安装轮询、启动、进程查询或真机调试，未重置手机文稿。

人工验收：在长纯文本及 Markdown 源码 / 排版的软换行最右侧点触，确认插入手柄出现、可拖动及打开菜单；随后滑动离开再返回，确认仍在上一行行尾，并实际输入 / 撤销。LIO-AN00 厂商输入法、另一台 Harmony 安卓兼容层测试机和完整发布矩阵仍未验收，不能以模拟器通过或已发起安装替代。
