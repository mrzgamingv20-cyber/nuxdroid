#!/data/data/com.nuxdroid/files/usr/bin/bash
# Automated test for nux-mkdeb and nux-repo (run in temp dirs)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# invoke via bash to avoid shebang path issues on dev machines
NMX() { bash "$SCRIPT_DIR/bin/nux-mkdeb" "$@"; }
NR() { bash "$SCRIPT_DIR/bin/nux-repo" "$@"; }
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "=== Test 1: nux-mkdeb init ==="
cd "$WORK"
NMX init testpkg
test -f testpkg/control
test -f testpkg/files/bin/testpkg
echo "PASS: init"

echo "=== Test 2: nux-mkdeb build ==="
cd "$WORK/testpkg"
NMX .
ls *.deb >/dev/null
DEB=$(ls *.deb | head -1)
echo "built: $DEB"
echo "PASS: build"

echo "=== Test 3: nux-repo build ==="
if command -v termux-apt-repo >/dev/null 2>&1; then
    cd "$WORK"
    mkdir -p debs repo
    cp testpkg/*.deb debs/
    NR build debs repo
    test -f repo/dists/stable/main/binary-aarch64/Packages.gz
    echo "PASS: repo build"
else
    echo "SKIP: termux-apt-repo not installed"
fi

echo "=== Test 4: idempotent apply (dry-run) ==="
export PREFIX="$PREFIX"
mkdir -p "$PREFIX/etc/nuxdroid" "$PREFIX/etc/apt/sources.list.d" "$PREFIX/etc/apt/keyrings"
# use temp conf
CONF_BACKUP=""
if [[ -f "$PREFIX/etc/nuxdroid/repos.conf" ]]; then
    cp "$PREFIX/etc/nuxdroid/repos.conf" /tmp/repos.conf.bak
fi
# point conf to temp by overriding via env not supported; use real path but restore later
echo "local http://127.0.0.1:8000 stable main trusted" > "$PREFIX/etc/nuxdroid/repos.conf"
NR apply --dry-run >/dev/null
NR apply --dry-run >/dev/null
echo "PASS: idempotent dry-run"

# restore
if [[ -f /tmp/repos.conf.bak ]]; then
    mv /tmp/repos.conf.bak "$PREFIX/etc/nuxdroid/repos.conf"
else
    rm -f "$PREFIX/etc/nuxdroid/repos.conf"
fi

echo "=== ALL TESTS PASSED ==="