# ADR 0009：原生双语资源与独立应用语言

- 日期：2026-11-02。
- 状态：接受并实施；平台验证见[本轮记录](../testing/2026-11-02-localization.md)。

## 背景与约束

用户要求实施中英文与可贡献的扩展方案。语言变更不得重命名已有条目、改变正文或库协议，不引入网络，设置不与工作区代际绑定，不在主线程自行读写文件。

## 考虑的方案

仅跟随系统 / API 33+ 系统选择不能满足旧系统独立选择；自行包装 Activity Context 需要维护整套配置和生命周期。因此采用 AndroidX AppCompat 应用语言 API。

## 决定与理由

采用完整英文默认资源、`values-b+zh+Hans` 简中、原生 plurals 和显式 LocaleConfig。选择器读取同一份声明和各语言的 `language_native_name`，新增语言不修改 Kotlin 注册表。关闭 App Bundle 语言拆分，保证离线切换。

新增固定 AppCompat 1.6.1，复用本机缓存中的稳定版本和现有官方仓库，不升级工具链。它提供旧系统应用语言与 API 33+ 系统对接；宿主迁为 AppCompatActivity，并适配两个无 ActionBar 主题。代价是新增 View / Fragment / 生命周期等传递支撑依赖，实测包体记录见验收文档，不引入产品导航框架。

API 33+ 以平台应用语言为权威；API 26–32 通过 IO dispatcher 读写 `files/app-preferences/language` 的原子偏好。不启用会在主线程读写的 autoStoreLocales。冷启等待偏好和有效配置，但不等待同语言设置中不会发生的重建；工作区保留原进程级单一编辑 / 保存所有者。

设备升级后的旧偏好只作一次迁移候选，不能静默覆盖系统的空列表。系统已有显式语言时消耗旧候选；否则在设置中提供沿用 / 跟随系统选择。应用内切换复用保存屏障，外部系统切换沿用会话保留、后台保存及导入任务去重。

UI 消息保存资源及参数，根位置标签按 ID 显示。新名称通过操作快照传入现有复制 / 恢复算法；已准备的导出和 ZIP 预览不重新命名。非 UI 调用保留无语言含义的数字后缀默认值。仅显示排序接受 Locale，名称冲突与路径匹配规则独立。

资源验证作为 JVM 测试接入现有 Check / CI，无额外全局工具或翻译平台。新语言必须完整覆盖，结构检查不能替代语言审阅与布局验收。

## 代价、迁移与回退

英文成为未匹配语言的应用资源回退，Android 仍自行解析语言列表和脚本。固定字形样文与法定许可保留原文。不升级目录索引，不修改旧名称、正文或 ZIP 路径。清空显式语言可回到跟随系统；不以卸载作为回退方法。

旧系统冷启可能多一次重建，进程死亡前未提交输入仍遵循既有数据安全边界。RTL 伪语言只作压力测试；旧系统和升级迁移的设备交互覆盖如实记录，未安装额外镜像。

## 验证依据

[设计](../specs/0004-localization.md)、[实施任务](../tasks/0023-localization.md)、[本轮验证](../testing/2026-11-02-localization.md)。[Android 应用语言 API](https://developer.android.com/guide/topics/resources/app-languages)说明系统同步、Compose 宿主和自动存储限制；[数量资源](https://developer.android.com/topic/architecture/views/resources/string-resource-views)说明复数和参数机制。
