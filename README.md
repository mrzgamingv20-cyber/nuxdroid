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

### Prefix consistency

Changing `TERMUX_APP__PACKAGE_NAME` is not enough on its own: the bootstrap
builder can reuse cached `termux-tools` packages that were compiled for the
default `com.termux` prefix. The build therefore passes `-f` to
`build-bootstraps.sh` and explicitly rebuilds `termux-am` and
`termux-am-socket`. A verification step rejects any bootstrap script that
still references `/data/data/com.termux`.

The first APK built before this fix may have an already-extracted prefix with
the stale `termux-tools` scripts. Reinstalling the APK without clearing app
data will not replace them. Run `scripts/repair-prefix.sh` inside the NuxDroid
terminal to repair the existing prefix:

```sh
bash /sdcard/Download/NuxDroid/repair-prefix.sh
```

## Branding and APK size

The launcher icon, adaptive icon, TV banner, and notification icon are
NuxDroid-specific. The upstream Termux artwork is not used. The Gradle
application ID and the Android resource names still use the `com.termux` /
`ic_launcher` identifiers for compatibility with the Termux app fork, but the
user-facing branding is NuxDroid.

The first NuxDroid APK embedded the complete Termux bootstrap. That bootstrap
also contained build-only packages (Perl, Python, Doxygen, DocBook, X11
headers, Tor, and others) which were extracted on first launch. The build now
runs `.github/scripts/prune-bootstrap.py` after the bootstrap is built. It
keeps the runtime dependency closure of the requested packages, removes
build-only packages, filters the dpkg database and symlinks, and verifies the
ELF `NEEDED` libraries.

Current result from the 2026-10-27 bootstrap:

| Metric | Before pruning | After pruning |
|---|---|---|
| Bootstrap zip | 122.3 MiB | 31.8 MiB |
| Zip entries | 18,495 | 4,526 |
| Packages in dpkg status | 158 | 87 |
| APK (expected) | ~132 MB | ~36 MB |

The first launch still extracts the bootstrap once. With the smaller archive
and fewer entries, that one-time extraction is substantially faster; later
launches skip it because the prefix already exists.

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
