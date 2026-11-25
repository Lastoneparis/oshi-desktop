# jpackage resource overrides — Linux

This directory is passed to jpackage as `--resource-dir` by the `packageDeb` and `packageRpm`
tasks, but **only when it contains something jpackage recognises**. It currently contains the
application icon and Debian lifecycle scripts. The scripts intentionally make menu registration
best-effort: a headless machine must still be able to install, upgrade, and remove OSHI.

Drop a file here only to override a specific jpackage default. jpackage looks for exact
filenames; anything else (this README included) is ignored.

| file | overrides |
|---|---|
| `OSHI.png` | the application icon (48×48 or larger; must match the jpackage application name) |
| `OSHI.desktop` | the freedesktop menu entry |
| `control` | the Debian control file — `.deb` only |
| `postinst`, `prerm`, `preinst`, `postrm` | Debian maintainer scripts — `.deb` only |
| `template.spec` | the RPM spec — `.rpm` only |
| `launcher.template` | launcher properties |

Note the split: `control` and the maintainer scripts are read only when building a `.deb`, and
`template.spec` only when building an `.rpm`, even though both tasks point at this one
directory. A file for the other format is silently unused rather than an error — so if an
override "does nothing", check it belongs to the format you are building.

`--linux-shortcut` makes jpackage create the `.desktop` entry. The checked-in `OSHI.png`
overrides its icon. Do not add a custom `.desktop` file unless it is tested on both Debian and
RPM-based distributions: it becomes part of the package's install and removal contract.
