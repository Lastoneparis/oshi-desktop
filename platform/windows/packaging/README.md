# jpackage resource overrides — Windows

This directory is passed to jpackage as `--resource-dir` by the `packageExe` and `packageMsi`
tasks, but **only when it contains something jpackage recognises**. The build is written so that
an empty directory changes nothing: jpackage uses its own built-in templates.

It holds the launcher icons, and nothing else.

Drop a file here only to override a specific jpackage default. jpackage looks for exact
filenames; anything else (this README included) is ignored.

| file | overrides |
|---|---|
| `OSHI.ico` | the application and installer icon — **present** |
| `OSHI-Console.ico` | the `OSHI-Console` launcher's icon — **present** |
| `main.wxs` | the WiX installer definition — full control, and full responsibility |
| `overrides.wxi` | a WiX include, for smaller changes than replacing `main.wxs` |
| `OSHI-post-image.wsf` | a script run after the app image is built |
| `WinLauncher.template` | launcher properties |

Two cautions worth having written down:

- **`main.wxs` is version-coupled.** A `main.wxs` copied from a different JDK's jpackage can
  fail against this one, in WiX errors that do not mention the mismatch. Start from *this*
  JDK's template (`jdk/jmods` → `jdk.jpackage`), not from a web search result.
- **WiX v3 only.** jpackage 17 does not understand WiX v4+, and says something unhelpful when
  it finds one.

**The filenames above are the whole contract.** jpackage looks them up by EXACT name, built
from `--name OSHI` and from each add-launcher's name, and ignores anything else without a word.
An earlier version of this table said `OSHI-Desktop.ico`, which is the Gradle project's name and
not the app's: a file under that name sits here, changes nothing, and leaves Duke in the Start
menu with a green build. `BrandingTest` now asserts both names, and asserts that each `.ico`
carries 16/32/48px entries — a 256-only icon installs fine and is then downsampled into a blur
at taskbar size.

The image is the shipped iOS/macOS app icon
(`OSHI/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`), resampled. It is not a
desktop-only mark, and it must not become one.
