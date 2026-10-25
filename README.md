# NuxDroid

NuxDroid is a native, rootless Linux desktop environment for Android. It is
built as a Termux fork with a custom application ID and prefix, so it can be
installed alongside the official Termux app.

The project is currently in **Phase 0: build infrastructure**. It does not
include XFCE or Xlorie yet.

## Current architecture (Phase 0)

| Component | Status |
|---|---|
| Termux app fork | In progress |
| Application ID | `com.nuxdroid` |
| Prefix | `/data/data/com.nuxdroid/files/usr` |
| Bootstrap | Built from `termux-packages` for the custom prefix |
| X server | Planned: Xlorie |
| Desktop | Planned: XFCE 4.20 |
| Proot | Not used |
| Root | Not required |

## Why a custom prefix?

Termux binaries have the prefix hardcoded at compile time. An app with the
package name `com.nuxdroid` cannot use the official `com.termux` bootstrap.
The bootstrap and packages must therefore be rebuilt for
`/data/data/com.nuxdroid/files/usr`.

This is also why NuxDroid can coexist with Termux: both apps have separate
package names, data directories, and prefixes.

## Build

The APK is built with GitHub Actions:

- `.github/workflows/build.yml`
- `smoke-apk`: compile-only validation using the upstream Termux bootstrap.
  **Do not install this artifact.**
- `bootstrap`: builds `bootstrap-aarch64.zip` for `com.nuxdroid`.
- `apk`: builds the installable APK using the custom bootstrap.

The workflow uses:

- JDK 17
- Android SDK 36
- NDK 29.0.14206865
- `termux/termux-packages` pinned at
  `824221abf741a02ac50dc8ce161b52604b3e1a58`

Artifacts are available from the GitHub Actions run.

## Roadmap

1. Phase 0a: custom prefix, bootstrap, terminal APK, coexist with Termux.
2. Phase 0b: embed Xlorie and verify `xterm`.
3. Phase 0c: build XFCE packages for the custom prefix.
4. Phase 0d: launch XFCE, test input, storage, stability.
5. Phase 1: NuxDroid launcher, app shortcuts, file manager, settings.
6. Later: optional apps, audio, GPU acceleration.

## Licensing

NuxDroid is based on Termux. The combined application is distributed under
GPLv3. See `LICENSE.md` and `NOTICE.md` for attribution and upstream details.

Termux is a trademark of the Termux project. NuxDroid is an independent fork
and is not affiliated with or endorsed by the Termux project.
