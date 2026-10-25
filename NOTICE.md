# NOTICE

NuxDroid is a fork of the Termux Android application.

## Termux

- Project: https://github.com/termux/termux-app
- Upstream commit imported: 8629e632fcb95da272221be327db653fb24befe9
- License: GPLv3 (see `LICENSE.md`)

The following source components are derived from Termux:

- `app/`
- `terminal-emulator/`
- `terminal-view/`
- `termux-shared/`
- Gradle and resource files

NuxDroid changes the application ID to `com.nuxdroid`, the app name to
`NuxDroid`, and the Termux prefix to
`/data/data/com.nuxdroid/files/usr`. The Java namespace remains `com.termux`
for compatibility with the upstream source tree; Android component names
therefore continue to use the `com.termux.*` class namespace while the
installed package is `com.nuxdroid`.

## Termux packages

The custom bootstrap is built from:

- https://github.com/termux/termux-packages
- Pinned commit: 824221abf741a02ac50dc8ce161b52604b3e1a58

Those packages carry their own licenses. Their source and build recipes are
available in the Termux packages repository.

## Planned components

- Termux:X11 / Xlorie (GPLv3): https://github.com/termux/termux-x11
- XFCE packages from https://github.com/termux/x11-packages

No Winlator source code is included in this phase.
