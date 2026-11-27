# ADR 0017：行尾显示映射保留原生插入手柄

- 日期：2026-11-27
- 状态：接受并实施，补充 ADR 0016

## 问题

软换行的上游光标使用只存在于 OutputTransformation 的零宽映射。固定的 Compose Foundation Android 1.9.0 在 `TextFieldSelectionState.observeTextChanges` 观察 `visualText`；映射改变也被视为文本编辑，从而清除原生插入手柄与菜单。提前在按下时准备映射只能掩盖较慢点触，零停顿点触仍复现手柄消失。正文手势观察器另在按下和移动时清除映射，导致滑动后的光标回到下一行行首。

## 决定

保留原生手柄、坐标计算、拖动、剪贴板菜单及真实输入路径。使用现有 AGP 的 ASM instrumentation API，仅将固定二进制中 `observeTextChanges$lambda$18` 的一次 `TransformedTextFieldState.getVisualText` 调用改为同类型的 `getUntransformedText`。真正的正文变化仍隐藏原生手柄；输出映射变化不会触发这条编辑观察器。其他视觉布局及选区路径仍使用转换后的文本。

变换在 Debug / Release 生效；调用签名与堆栈不变，复制既有 frame。只匹配一个类、一个完整方法签名和一个 getter 签名，并要求命中恰好一次；另固定检查 BOM 2025.08.01。更换 Compose 版本或目标二进制结构时必须重新审查，否则构建失败。没有新增依赖、运行时反射、权限或工具链升级。此为需随依赖维护的局部兼容修正，不能视为上游已解决。

应用手势只在完成短点触时提交新位置；滑动、长按及取消不清除现有行尾映射。正文、范围选区、组合、失焦和视图生命周期继续使用已有状态清理规则。

## 取舍与验证

原生 BasicTextField 没有公开的插入手柄显隐设置。自行绘制替代指针需要重做拖动与弹出菜单，合成第二次触摸会改变真实操作；两者均不采用。升级依赖也不能仅凭版本推断解决该观察逻辑。

验证须包含旧版零停顿点触失败与滑动丢失视觉位置、修复后零停顿及短点触、原生手柄同位 / 拖动 / 菜单、真实 InputConnection 输入仍隐藏手柄、正文与复制 / 撤销不含映射、只读行为和视图切换。构建覆盖 Debug / Release；实际结果见[验收记录](../testing/2026-11-27-caret-handle-affinity.md)。

## 参考

- [AGP 9.0 AsmClassVisitorFactory](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/instrumentation/AsmClassVisitorFactory)
- [AndroidX TextFieldSelectionState 源码](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/internal/selection/TextFieldSelectionState.kt)；实施以本机固定 1.9.0 artifact 和 sources 核对结果为准。
