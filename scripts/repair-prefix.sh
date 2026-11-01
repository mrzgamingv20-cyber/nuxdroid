#!/data/data/com.nuxdroid/files/usr/bin/bash
#
# Repair an already-extracted NuxDroid prefix that was created from the old
# bootstrap whose termux-tools scripts still referenced /data/data/com.termux.
#
# Run this inside the NuxDroid terminal:
#
#   bash /sdcard/Download/NuxDroid/repair-prefix.sh
#
# The script only rewrites known text files under the NuxDroid prefix. It does
# not use the official Termux prefix and it does not install packages.

set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.nuxdroid/files/usr}"

if [ "$PREFIX" != "/data/data/com.nuxdroid/files/usr" ]; then
	echo "Refusing to repair unexpected prefix: $PREFIX" >&2
	echo "Expected PREFIX=/data/data/com.nuxdroid/files/usr" >&2
	exit 1
fi

if [ ! -d "$PREFIX/bin" ]; then
	echo "NuxDroid prefix not found at $PREFIX" >&2
	exit 1
fi

backup_dir="$HOME/nuxdroid-prefix-repair-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"

changed=0
backed_up=0

repair_file() {
	local file="$1"
	local rel="${file#"$PREFIX"/}"
	local backup="$backup_dir/$rel"

	if ! grep -Iq . "$file" 2>/dev/null; then
		return 0
	fi

	if ! grep -qE \
		'/data/data/com\.termux|"com\.termux"|com\.termux/com\.termux\.app\.|com\.nuxdroid/com\.nuxdroid\.app\.|com\.termux\.app\.reload_style|com\.termux\.service_wake_(lock|unlock)|Termux \(com\.termux;|escaped_package_name="com\.termux"' \
		"$file"; then
		return 0
	fi

	mkdir -p "$(dirname "$backup")"
	if [ ! -e "$backup" ]; then
		cp -p "$file" "$backup"
		backed_up=$((backed_up + 1))
	fi

	sed -i \
		-e 's|/data/data/com\.termux|/data/data/com.nuxdroid|g' \
		-e 's|com\.termux/com\.termux\.app\.|com.nuxdroid/com.termux.app.|g' \
		-e 's|com\.nuxdroid/com\.nuxdroid\.app\.|com.nuxdroid/com.termux.app.|g' \
		-e 's|com\.termux\.app\.reload_style|com.nuxdroid.app.reload_style|g' \
		-e 's|com\.termux\.service_wake_lock|com.nuxdroid.service_wake_lock|g' \
		-e 's|com\.termux\.service_wake_unlock|com.nuxdroid.service_wake_unlock|g' \
		-e 's|"com\.termux"|"com.nuxdroid"|g' \
		-e 's|Termux (com\.termux;|Termux (com.nuxdroid;|g' \
		-e 's|escaped_package_name="com\.termux"|escaped_package_name="com.nuxdroid"|g' \
		"$file"

	changed=$((changed + 1))
	printf 'repaired %s\n' "$rel"
}

while IFS= read -r -d '' file; do
	repair_file "$file"
done < <(find \
	"$PREFIX/bin" \
	"$PREFIX/etc" \
	"$PREFIX/libexec" \
	"$PREFIX/var/lib/dpkg/info" \
	-type f -print0 2>/dev/null | sort -z)

remaining="$(
	grep -rIl --binary-files=without-match \
		'/data/data/com\.termux' \
		"$PREFIX/bin" \
		"$PREFIX/etc" \
		"$PREFIX/libexec" \
		"$PREFIX/var/lib/dpkg/info" 2>/dev/null || true
)"

if [ -n "$remaining" ]; then
	echo "Repair incomplete; these files still reference the old prefix:" >&2
	printf '%s\n' "$remaining" >&2
	exit 1
fi

for entry in bin/pkg bin/login bin/termux-info bin/termux-setup-storage; do
	if [ -f "$PREFIX/$entry" ] && ! grep -q 'com\.nuxdroid' "$PREFIX/$entry"; then
		echo "Repair incomplete; $entry does not use com.nuxdroid" >&2
		exit 1
	fi
done

for entry in bin/termux-open bin/termux-reset bin/termux-wake-lock bin/termux-wake-unlock; do
	if [ -f "$PREFIX/$entry" ] &&
		! grep -q 'com\.nuxdroid/com\.termux\.app\.' "$PREFIX/$entry"; then
		echo "Repair incomplete; $entry does not target the Termux Java namespace" >&2
		exit 1
	fi
done

if grep -rIq --binary-files=without-match \
	'com\.termux/com\.termux\.app\.|com\.nuxdroid/com\.nuxdroid\.app\.|com\.termux\.app\.reload_style|com\.termux\.service_wake_' \
	"$PREFIX/bin" "$PREFIX/etc" "$PREFIX/libexec" 2>/dev/null; then
	echo "Repair incomplete; old app component/action names remain" >&2
	exit 1
fi

echo
echo "Prefix repair complete."
echo "  changed files: $changed"
echo "  backups:       $backup_dir"
echo
# Configure NuxDroid package repository (replaces official Termux repo)
if [ -f "$PREFIX/etc/apt/sources.list" ]; then
	echo "deb [trusted=yes] https://mrzgamingv20-cyber.github.io/nuxdroid-packages aarch64 main" > "$PREFIX/etc/apt/sources.list"
	echo "Configured NuxDroid package repository."
fi

echo "Test with:"
echo "  pkg --help"
echo "  pkg install fastfetch"
echo
echo "Package repository: https://mrzgamingv20-cyber.github.io/nuxdroid-packages"
echo "The permission-denied error from termux-setup-package-manager is fixed."
