# Third-party notices

Makudon 的项目许可证为根 `LICENSE` 中的 MIT；第三方字体、图标和素材保留各自许可。

Android 开发版已集成完整 Noto Sans SC、按需 Lucide / Fluent 向量图标与路径字标；Fredoka 字体没有打包。设置页提供离线许可入口，完整文本随 APK 的 `res/raw/third_party_notices.txt` 分发。

- Fredoka — Copyright 2016 The Fredoka Project Authors；[SIL OFL 1.1](docs/licenses/fredoka-OFL.txt)。
- Noto Sans SC — Copyright 2014–2021 Adobe，Reserved Font Name “Source”；[SIL OFL 1.1](docs/licenses/notosanssc-OFL.txt)。
- Lucide — Copyright (c) 2026 Lucide Icons and Contributors；部分图标源自 Feather，Copyright (c) 2013-present Cole Bemis；[ISC 与 MIT 完整声明](docs/licenses/lucide-LICENSE.txt)。
- Fluent UI System Icons / Markdown 20 Filled — Copyright (c) 2020 Microsoft Corporation；[MIT 完整声明](docs/licenses/fluentui-system-icons-LICENSE.txt)，[固定来源及哈希](assets/icons/provenance.json)。
- AndroidX / Compose — The Android Open Source Project；[Apache 2.0](docs/licenses/androidx-LICENSE.txt)。
- kotlinx.coroutines — JetBrains / Kotlin Contributors；[Apache 2.0](docs/licenses/kotlinx-coroutines-LICENSE.txt)。

使用范围与引入要求见 [素材管理规范](docs/third-party-assets.md)，品牌来源与哈希见 [品牌 provenance.json](assets/brand/provenance.json)，文件图标见 [图标 provenance.json](assets/icons/provenance.json)，实际 Android 文件与体积见 [集成记录](assets/android/README.md)。首次正式发布仍须审计完整解析依赖及其许可，不能以开发版素材清单代替发布审查。
