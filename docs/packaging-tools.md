# NuxDroid Packaging Tools

Tools to build `.deb` packages and manage apt repositories for NuxDroid
(prefix `/data/data/com.nuxdroid/files/usr`). Official Termux packages are
NOT compatible (hardcoded `/data/data/com.termux` paths).

## Tools

- `bin/nux-mkdeb` - build a `.deb` from a spec directory.
- `bin/nux-repo` - build/manage apt repositories.

## nux-mkdeb

Spec layout:
```
mypkg/
  control
  files/...       (relative to prefix)
  scripts/{preinst,postinst,prerm,postrm}  (optional)
```

Usage:
```
nux-mkdeb init <name>       # create template
nux-mkdeb <spec-dir>        # build .deb
nux-mkdeb --dry-run <dir>   # preview
```

## nux-repo

Build a repo:
```
nux-repo build <dir-debs> <dir-output> [--dist stable] [--comp main] [--sign KEYID] [--force]
```
Requires `termux-apt-repo` (`pip3 install termux-apt-repo`).

Manage repos (user side, declarative):
```
nux-repo add <name> <url> <dist> <comp> [--key URL] [--insecure] [--allow-http]
nux-repo remove <name>
nux-repo list
nux-repo apply [--dry-run]
```

Config: `$PREFIX/etc/nuxdroid/repos.conf`, one repo per line:
```
<name> <url> <dist> <comp> [key=<URL>|trusted]
```

## Testing

```
bash tests/test_nux_tools.sh
```

## Security notes

- `trusted`/`--insecure` disables signature verification - only use for local testing.
- HTTP URLs require `--allow-http`.
- Termux mirror URLs (packages.termux.dev) are rejected (incompatible prefix).
- All writes are atomic (temp file + mv) and idempotent.