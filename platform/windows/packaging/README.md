# jpackage resource overrides — Windows

This directory is passed to jpackage as `--resource-dir` by the `packageMsi` task, but **only
when it contains something jpackage recognises**. It is empty of overrides today, and the build
is written so that an empty directory changes nothing: jpackage uses its own built-in templates.

Drop a file here only to override a specific jpackage default. jpackage looks for exact
filenames; anything else (this README included) is ignored.

| file | overrides |
|---|---|
| `OSHI-Desktop.ico` | the application and installer icon |
| `main.wxs` | the WiX installer definition — full control, and full responsibility |
| `overrides.wxi` | a WiX include, for smaller changes than replacing `main.wxs` |
| `OSHI-Desktop-post-image.wsf` | a script run after the app image is built |
| `WinLauncher.template` | launcher properties |

Two cautions worth having written down:

- **`main.wxs` is version-coupled.** A `main.wxs` copied from a different JDK's jpackage can
  fail against this one, in WiX errors that do not mention the mismatch. Start from *this*
  JDK's template (`jdk/jmods` → `jdk.jpackage`), not from a web search result.
- **WiX v3 only.** jpackage 17 does not understand WiX v4+, and says something unhelpful when
  it finds one.

An icon is the most likely first entry here — the packaged app currently ships jpackage's
generic default.
