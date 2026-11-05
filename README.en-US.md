Read this in other languages: [简体中文](README.md) · **English**

<br>
<div align="center">
  <img src="icon.svg" width="96" height="96" alt="Makudon icon">
</div>

# Makudon

**A quiet place to write on Android.**

Makudon is a local-first Markdown and plain text editor for quick notes, travel journals, and stories that take a little longer to tell. A soft interface, simple folders, and flexible writing views help you stay with your words.

No account required. Works offline, with no ads, telemetry, or built-in cloud sync. Take your writing with you as ordinary text files or ZIP archives.

[Install](#install) · [Get started](#get-started) · [Backup and restore](#backup-and-restore) · [Build from source](#build-from-source) · [Report an issue](https://github.com/Xrisium/makudon/issues)

> This is a development build. Full testing on physical devices is still in progress. Start with copies of your documents and export ZIP backups regularly.

## Install

**Requires Android 8.0 or later (API 26+).**

### Download a development APK

1. Open [GitHub Actions · Android checks](https://github.com/Xrisium/makudon/actions/workflows/android.yml) and select a successful run on the branch you want to try.
2. Under **Artifacts** at the bottom of the page, download `makudon-debug-<commit>`, extract it, and find `app-debug.apk`. Downloads require a GitHub account. Artifacts are kept for 7 days; if they have expired, [build from source](#build-from-source).
3. Transfer the APK to your phone and open it to install. When prompted, allow your file manager or browser to install apps.
4. Open **Makudon Dev** and start writing.

Debug signing keys may differ between build environments. If an update reports a signature conflict, export a ZIP backup from the installed version first. Uninstalling the app deletes its documents.

## Features

- **Three Markdown views**: edit exact syntax in Source, write with live formatting in Formatted, and review in Reading. All three use the same document.
- **Everyday writing tools**: headings, bold and italic text, lists, tasks, quotes, links, code, and tables. Includes undo/redo, Markdown find and replace, and heading navigation.
- **Automatic saving**: documents save to private app storage as you write, with visible save status. Retry a failed save or export the current text.
- **Simple organization**: a folder tree, name search, sorting, recently opened documents, and batch copy, cut, and delete for documents and folders.
- **Portable files**: import UTF-8 `.md` and `.txt` files, export source copies, and back up the library as a standard ZIP that preserves folders.
- **A separate recycle bin**: preview and restore deleted documents and folders. Items do not expire automatically, and permanent deletion requires confirmation.
- **Comfortable reading**: five color themes plus a system option, separate interface and document fonts and weights, three document text sizes, and a choice of English or Simplified Chinese.

## Get started

### Create and organize

On **Recent**, tap **＋** in the top-right corner and choose **Plain text** or **Markdown**. New documents go in the library root by default. To write in a particular folder, select it on **Folders** before creating a document.

Your text saves automatically. Tap the document title in the editor to rename it. In Folders, touch and hold an item to select multiple items, then copy, cut, paste, or delete them together. Search by name in Folders, or use Recent to pick up where you left off.

### Write Markdown

Switch between the three views at the bottom of the editor:

| View | How to use it |
| --- | --- |
| **Source** | Edit Markdown syntax directly for precise control over markup and table structure. |
| **Formatted** | Write with live formatting. Relevant markup appears where you edit; tap table headers or cells to edit them directly. |
| **Reading** | Review without editing. Select and copy text or search within the document. |

On narrow screens, the view labels shorten to **Code / Write / Read**. Source and Formatted share the same text and session undo history. The toolbar groups commands under **Text / Paragraph / Insert**. Tap a group to switch or collapse it; with the keyboard open, swipe the toolbar sideways to switch groups. Use **Outline** at the top right to jump to a heading. The more menu contains find and replace, text size, and export actions.

Supported Markdown includes H1–H6, nested lists, task lists, quotes, links, code blocks, strikethrough, and tables. Images and HTML are preserved as source but are not rendered. Use Source to change table rows and columns.

### Import and export

- **Import**: choose **Import** on the new-document screen and select a file. You can also choose Makudon from a file manager’s “Open with” menu when the file manager offers it.
- **Export**: use the editor’s more menu to export a copy: `.md` for Markdown or `.txt` for plain text. Choose a save location or share with another app. On Android 10 and later, you can also save directly to `Download/Makudon`.

**Import always creates an independent copy. Editing never writes back to the original file.** Each document must be UTF-8, with at most 100,000 Unicode code points and an encoded size of no more than 1 MiB. When sharing to another app, finish saving there; opening the share sheet does not mean a backup has been saved.

### Adjust appearance and language

Open **Settings** to choose a theme and separate interface and document fonts. Noto Sans SC, Noto Serif SC, LXGW WenKai, and system fonts are available offline. The editor’s **Text size** menu offers S / M / L, with support for system font scaling.

Under **Settings → Language**, choose **System default / 简体中文 / English**. Existing document names and text stay as they are.

## Backup and restore

**Documents live in private app storage. Uninstalling the app or clearing its data deletes them.** Automatic saving, the recycle bin, and local restore snapshots do not replace an external backup.

Open **Settings → Backup and restore**, export the library as a ZIP, and save it outside the app. The archive contains ordinary folders and UTF-8 `.md` / `.txt` files you can extract, read, and edit on a computer. No proprietary format is required.

To restore, select a ZIP, review the preview, and confirm a restore mode:

| Mode | When to use it and what happens |
| --- | --- |
| **Restore to a new folder** (default) | Place the archive in a separate folder to inspect a backup or bring in another library. |
| **Update existing library** | Compare by path. Identical text is skipped; for different text, choose Keep local, Use backup, or Keep both. Local-only content is preserved. |
| **Replace entire library** | Replace the current library with the ZIP contents, after confirmation. |

You can also restore a folder ZIP you created on a computer. The library from before the latest restore is kept as a local snapshot. Choose **Undo last restore** on the same page to switch back; the library you leave, including edits made after restoring, becomes the new snapshot.

ZIP backups contain current documents and folders only: **no recycle bin, document history, or app settings**. They do not automatically sync devices. To recover an accidental deletion, open **Settings → Recycle bin** and preview or restore the item. Emptying the bin or permanently deleting an item removes the ability to recover it from the bin.

## Build from source

Makudon uses **Kotlin + Jetpack Compose** in a single `:app` module. You need **JDK 17**, the Android SDK (Platform 36 / Build Tools 36.0.0), and Git. The repository includes the Gradle Wrapper.

```sh
git clone https://github.com/Xrisium/makudon.git
cd makudon
```

### Windows / PowerShell 7

Point setup at your existing SDK and JDK, then check the environment and run the build, unit tests, and lint:

```powershell
pwsh -NoProfile -File scripts/android.ps1 -Action Setup -SdkPath 'D:\Android\Sdk' -JdkPath 'D:\Java\jdk-17'
pwsh -NoProfile -File scripts/android.ps1 -Action Doctor
pwsh -NoProfile -File scripts/android.ps1 -Action Check
```

`Setup` generates local configuration only; it does not install tools. If your tools already use the project’s expected layout under `%USERPROFILE%\DevTools\Android`, omit the path arguments. You can also open `scripts/test-android.cmd` for the Chinese build and debugging menu.

### Use the Gradle Wrapper directly

On other systems, point `JAVA_HOME` at JDK 17 and configure the Android SDK through `ANDROID_HOME` or `sdk.dir` in `local.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

In Windows PowerShell, use `./gradlew.bat` instead. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See the [development guide](docs/development.md) (Chinese) for configuration, emulator, and VS Code instructions.

## Feedback and contributions

[Open an issue](https://github.com/Xrisium/makudon/issues) to report a bug or suggest an improvement. Include the app version, Android version, device model, and steps to reproduce. Use a shareable sample document when reporting text-related problems.

Developer resources (Chinese unless noted): [Documentation index](docs/README.md) · [Validation status](docs/status.md) · [Architecture](docs/architecture.md) · [Translation contribution guide (English)](docs/localization/CONTRIBUTING.en.md)

## License

Project code is licensed under [MIT](LICENSE). Fonts, icons, and third-party dependencies retain their own licenses; see [Third-party notices](THIRD_PARTY_NOTICES.md). Notices are also available offline under **Settings → Open-source licenses and credits**.
