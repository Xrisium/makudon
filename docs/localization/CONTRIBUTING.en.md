# Contributing translations to Makudon

Status: bilingual resources and validation implemented, 2026-11-02. English and Simplified Chinese include the current recycle bin. See the [implementation task](../tasks/0023-localization.md) and [current status](../status.md) for platform coverage and remaining checks.

Makudon is a local-first Android Markdown and plain-text editor. You can contribute translations through a GitHub pull request. No translation service account is required.

## Files and language tags

The layout uses complete English source strings in `app/src/main/res/values/strings.xml` and Simplified Chinese in `app/src/main/res/values-b+zh+Hans/strings.xml`. English is also the resource fallback. Stable Android resource keys identify messages; do not rename them to match a translation.

New languages use Android resource qualifiers, for example `values-ja` for Japanese or `values-b+zh+Hant` for Traditional Chinese. Use a script or region only where needed. Discuss the language tag and a native-language reviewer in your PR before adding it to the supported-language list. Do not create placeholder directories for languages you are not translating.

`app/src/main/res/xml/locales_config.xml` lists supported languages and drives the picker. Translate `language_native_name` into the language's self-name. Maintainers add a language to the declaration after translation and UI review. Work-in-progress translations can stay in a PR or branch; copying English into every untranslated entry does not make a translation complete.

## Translation rules

- Translate complete messages naturally. Keep the meaning of warnings, file operations and recovery choices precise.
- Preserve parameter indices and types, such as `%1$s` (text) and `%2$d` (integer). You may reorder them. Read the source comment for what each parameter represents.
- Escape XML and Android string characters correctly. Do not add manual line breaks just to fit your screen. Use straight ASCII characters inside format syntax, not typographic replacements.
- Use your language's Android plural categories. English uses `one` and `other`; Simplified Chinese generally only needs `other`. Do not copy English plural rules into every language. If a message uses ICU syntax, preserve its argument names and syntax rather than converting it to printf syntax.
- Do not translate keys, file extensions, MIME types, URLs, identifiers or entries marked `translatable="false"`.
- Manuscript text, existing filenames, folder names and labels supplied by other apps are user or third-party content. Do not translate them.
- Generated default names and copy suffixes are special: comments must identify them as filenames. Keep separators, control characters and unsafe path syntax out of them. Existing names stay unchanged when the app language changes.
- Keep original copyright and license texts intact. Translate navigation and explanatory notes separately.
- Use sentence case for English UI. Avoid abbreviations, promotional wording and claims that an operation succeeded when it has not.

Machine-assisted drafts are welcome, but identify them and review every message. Critical warnings need a language review, even when automated resource checks pass.

For example, the selection count uses these resources. The caller passes the same raw count both to select a plural category and to fill `%1$d`; translators may change the word order.

```xml
<!-- English, values/strings.xml. %1$d: number of selected top-level items. -->
<plurals name="selected_count">
    <item quantity="one">%1$d item selected</item>
    <item quantity="other">%1$d items selected</item>
</plurals>
```

```xml
<!-- Simplified Chinese, values-b+zh+Hans/strings.xml. -->
<plurals name="selected_count">
    <item quantity="other">已选 %1$d 项</item>
</plurals>
```

For translation-only PRs, preserve the source resource type. A change from string to plurals also requires a caller change.

## Initial glossary

These are the initial bilingual terms. Follow the meaning in context rather than translating the Chinese character by character.

| Chinese | English | Meaning |
| --- | --- | --- |
| 文稿 | Document | An editable text or Markdown document |
| 文稿库 | Library | The private collection of documents and folders |
| 目录 | Folders | The directory-tree navigation tab |
| 最近 | Recent | Recently opened documents |
| 源码 | Source | The original Markdown source |
| 排版 | Formatted | The app's limited editable Markdown layout |
| 复选 | Select items | Multi-selection action |
| 导入 | Import | Create an independent private copy; never edit the original URI |
| 导出副本 | Export a copy | Write a separate external file |
| 分享 | Share | Hand a copy to another app; this does not confirm it was saved |
| 已保存到本机 | Saved on device | The private document was successfully saved |
| 修改尚未保存 | Changes not saved | The latest input has not been committed |
| 上一保存版本 | Previous saved version | The previous version of one document |
| 恢复前快照 | Pre-restore snapshot | Local library state preserved before a restore; not an external backup |
| 单独保存 | Restore to a new folder | Restore without replacing the current library |
| 增量更新 | Update existing library | Add new content and resolve differing files explicitly |
| 完全覆盖 | Replace entire library | Destructively replace the current library |
| 保留本地 / 采用备份 / 保留双方 | Keep local / Use backup / Keep both | Explicit choices for a restore conflict |
| 跟随系统 | System default | Clear the application's explicit language choice |

Keep Makudon, Markdown, Android and official library names unchanged. Use the font's official English name where appropriate, for example LXGW WenKai. Fixed multilingual font samples are examples of glyph coverage, not normal UI copy.

## Submitting a pull request

1. State the language, tag, completion level and reviewer. Identify any machine-assisted translation and unresolved terms.
2. Translate resources, retaining comments and functional grouping. Provide all translatable keys before requesting official language support. Remove obsolete keys; do not duplicate non-translatable entries.
3. Run the project check with JDK 17: `pwsh -NoProfile -File scripts/android.ps1 -Action Check`. Linux CI uses `bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain`. Resource validation is part of the existing JVM test task, with no extra tool to install. To run it alone on Windows: `./gradlew.bat :app:testDebugUnitTest --tests io.github.xrisium.makudon.LocalizationResourcesTest --console=plain` (Linux: `bash ./gradlew` with the same arguments). If you cannot run a check, say so in the PR.
4. Review the main tabs, editor, settings, dialogs, file import/export and backup/restore. Include screenshots with synthetic content at normal size and at 320dp width / 200% font size. For RTL languages, also review direction, mixed filenames, navigation gestures and directional icons.
5. Test zero, one and multiple counts, long names and all critical warnings. Note Android version, locale, 12/24-hour setting and any untested cases. Do not share private manuscripts, real paths or content URIs in screenshots or logs.
6. Keep unrelated Kotlin, formatting and dependency changes out of translation PRs. Maintainers handle the language declaration and necessary picker changes. A clean build proves resource validity, not linguistic quality; provide the review result too.

When English source text changes, maintainers will flag affected keys in the PR. A key that still exists may need a new translation if its meaning changed. For a new feature, English and Simplified Chinese must be updated together; supported languages must be complete before release.

## Adding the next language

A new reviewed LTR language should normally need only its resource directory, an entry in the supported-language declaration, its self-name and review evidence. It must not require changing the storage format, translating document content or adding a translation runtime dependency.

Languages needing new plural forms, script-specific fonts or RTL behavior may also require implementation work. Track that work explicitly before claiming full support. Do not mark a language supported solely because its strings compile.

The validator rejects missing, obsolete and duplicate keys, empty values, translated invariants, changed types and printf parameters, and invalid plural branches. It requires `other` for all languages and `one` for English; review all categories needed by any new language. Parameter reordering and different category sets are allowed. Current messages do not use ICU; extend validation before introducing it.

Use the [translation PR template](../../.github/PULL_REQUEST_TEMPLATE/translation.md) to provide context and evidence. All declared languages are bundled for offline switching.
