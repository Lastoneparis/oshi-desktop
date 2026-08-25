# jpackage resource overrides — Linux

This directory is passed to jpackage as `--resource-dir` by the `packageDeb` and `packageRpm`
tasks, but **only when it contains something jpackage recognises**. It is empty of overrides
today, and the build is written so that an empty directory changes nothing: jpackage uses its
own built-in templates.

Drop a file here only to override a specific jpackage default. jpackage looks for exact
filenames; anything else (this README included) is ignored.

| file | overrides |
|---|---|
| `OSHI-Desktop.png` | the application icon (48×48 or larger) |
| `OSHI-Desktop.desktop` | the freedesktop menu entry |
| `control` | the Debian control file — `.deb` only |
| `postinst`, `prerm`, `preinst`, `postrm` | Debian maintainer scripts — `.deb` only |
| `template.spec` | the RPM spec — `.rpm` only |
| `launcher.template` | launcher properties |

Note the split: `control` and the maintainer scripts are read only when building a `.deb`, and
`template.spec` only when building an `.rpm`, even though both tasks point at this one
directory. A file for the other format is silently unused rather than an error — so if an
override "does nothing", check it belongs to the format you are building.

An icon and a `.desktop` entry are the most likely first entries here: `--linux-shortcut` is
already passed, so a menu entry is created today using jpackage's generic default icon.
