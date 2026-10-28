#!/usr/bin/env python3
"""Prune build-only packages from a Termux bootstrap zip.

The Termux bootstrap builder currently builds all build dependencies of the
requested packages and then extracts every *.deb found in its output
directory.  The resulting bootstrap therefore contains build-only tools such
as perl, python, doxygen, docbook, tor and X11 development headers.  Those
packages are not needed at runtime, but they make the first-launch bootstrap
extraction much slower and make the APK considerably larger.

This script computes the runtime dependency closure of the bootstrap's
top-level packages and rewrites the zip with only those packages.  It also:

  * filters the dpkg status database so that dpkg sees a consistent set of
    installed packages,
  * drops the dpkg metadata (.list/.md5sums/.postinst/...) of removed
    packages,
  * filters SYMLINKS.txt, and
  * keeps files that are not owned by any package (the bootstrap's own
    second-stage script, profile snippets, etc.).

Usage:
    python3 prune-bootstrap.py INPUT.zip OUTPUT.zip
"""

from __future__ import annotations

import argparse
import os
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# The bootstrap is built for the NuxDroid prefix.  Keep this in sync with
# TERMUX_APP__PACKAGE_NAME in .github/workflows/build.yml.
PREFIX = "/data/data/com.nuxdroid/files/usr"

# Top-level packages requested by build-bootstraps.sh (after the workflow's
# patch that replaces bzip2 with libbz2).  These must be present in the
# bootstrap status database.
REQUIRED_TARGETS = [
    "apt",
    "bash",
    "libbz2",
    "coreutils",
    "dash",
    "diffutils",
    "findutils",
    "gawk",
    "grep",
    "gzip",
    "less",
    "procps",
    "psmisc",
    "sed",
    "tar",
    "termux-core",
    "termux-exec",
    "termux-keyring",
    "termux-tools",
    "util-linux",
    "ed",
    "debianutils",
    "dos2unix",
    "inetutils",
    "lsof",
    "nano",
    "net-tools",
    "patch",
    "unzip",
]

# Small useful commands that happen to be present in the unpruned bootstrap
# because they are build dependencies.  They are optional: if a future
# bootstrap does not contain them, they are simply skipped.
OPTIONAL_TARGETS = [
    "openssl-tool",   # the `openssl` command line tool
    "ncurses-utils",  # clear, tput, reset
    "file",           # inspect file types
    "libmount",       # needed by util-linux's lsns but not declared in Depends
]

# Packages that must never be pruned.  These are the packages needed to
# extract the bootstrap, run the second stage and use apt/dpkg afterwards.
SYSTEM_LIBS = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
    "libnativehelper.so",
    "libnetd_client.so",
    "libstdc++.so",
}

MUST_KEEP = [
    "apt",
    "bash",
    "bzip2",
    "ca-certificates",
    "coreutils",
    "curl",
    "dash",
    "diffutils",
    "dpkg",
    "findutils",
    "gawk",
    "grep",
    "gzip",
    "less",
    "libandroid-support",
    "libbz2",
    "libc++",
    "libcurl",
    "libmount",
    "ncurses",
    "openssl",
    "procps",
    "psmisc",
    "readline",
    "sed",
    "tar",
    "termux-core",
    "termux-exec",
    "termux-keyring",
    "termux-licenses",
    "termux-tools",
    "util-linux",
    "xz-utils",
    "zlib",
]

def fail(message: str) -> "NoReturn":
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def warn(message: str) -> None:
    print(f"warning: {message}", file=sys.stderr)


def get_field(block: str, name: str) -> str:
    match = re.search(r"^" + re.escape(name) + r":\s*(.*)$", block, re.MULTILINE)
    return match.group(1).strip() if match else ""


def parse_status(text: str) -> list[tuple[str, str, dict[str, str]]]:
    """Return (package, raw_block, fields) for every package in status."""
    result: list[tuple[str, str, dict[str, str]]] = []
    for raw in text.strip("\n").split("\n\n"):
        if not raw.strip():
            continue
        package = get_field(raw, "Package")
        if not package:
            continue
        package = package.split(":", 1)[0]
        fields = {
            "Package": package,
            "Depends": get_field(raw, "Depends"),
            "Pre-Depends": get_field(raw, "Pre-Depends"),
            "Provides": get_field(raw, "Provides"),
            "Essential": get_field(raw, "Essential"),
        }
        result.append((package, raw, fields))
    return result


def parse_dep_groups(field: str) -> list[list[str]]:
    """Parse a Depends/Provides field into alternatives groups.

    Example: "foo (>= 1), bar | baz" -> [["foo"], ["bar", "baz"]]
    """
    groups: list[list[str]] = []
    for group in field.split(","):
        alternatives: list[str] = []
        for alternative in group.split("|"):
            alternative = alternative.strip()
            if not alternative:
                continue
            name = alternative.split()[0].split(":", 1)[0].strip()
            if name:
                alternatives.append(name)
        if alternatives:
            groups.append(alternatives)
    return groups


def map_list_path(path: str) -> str | None:
    """Map an absolute dpkg .list path to a path relative to the prefix."""
    path = path.strip()
    if path == PREFIX:
        return None
    if not path.startswith(PREFIX + "/"):
        return None
    relative = path[len(PREFIX) + 1:].strip("/")
    return relative or None


def map_md5_path(path: str) -> str | None:
    """Map a dpkg md5sums path to a path relative to the prefix.

    dpkg md5sums normally store paths relative to the Android filesystem
    root, e.g. "data/data/com.nuxdroid/files/usr/bin/bash".
    """
    path = path.strip()
    if path.startswith("./"):
        path = path[2:]
    prefix_no_leading_slash = PREFIX.lstrip("/")
    if path.startswith(prefix_no_leading_slash + "/"):
        return path[len(prefix_no_leading_slash) + 1:].strip("/") or None
    if path.startswith(PREFIX + "/"):
        return path[len(PREFIX) + 1:].strip("/") or None
    return None


def parse_file_list(text: str) -> list[str]:
    """Map absolute dpkg .list paths to paths relative to the prefix."""
    result: list[str] = []
    for line in text.splitlines():
        relative = map_list_path(line)
        if relative:
            result.append(relative)
    return result


def filter_info_text(name: str, text: str, extra_removed: set[str]) -> str:
    """Remove extra-pruned paths from a retained .list or .md5sums file."""
    if not extra_removed:
        return text if text.endswith("\n") else text + "\n"
    kept: list[str] = []
    for line in text.splitlines():
        if name.endswith(".list"):
            relative = map_list_path(line)
        else:
            fields = line.split(None, 1)
            relative = map_md5_path(fields[1]) if len(fields) == 2 else None
        if relative and relative in extra_removed:
            continue
        kept.append(line)
    return "\n".join(kept) + ("\n" if kept else "")


def check_elf_needed(output_zip: Path, kept_symlinks: list[str]) -> None:
    """Fail if a retained ELF file needs a library that was pruned.

    The dpkg dependency metadata is not always complete (for example,
    util-linux's lsns links against libmount without declaring it).  Checking
    the actual ELF NEEDED entries catches that class of problem.
    """
    readelf = shutil.which("readelf")
    if not readelf:
        warn("readelf not found; skipping ELF NEEDED check")
        return

    with zipfile.ZipFile(output_zip, "r") as z:
        available_libs = {
            posixpath.basename(name)
            for name in z.namelist()
            if name.startswith("lib/") and not name.endswith("/")
        }
        for line in kept_symlinks:
            parts = line.split("\u2190")
            if len(parts) != 2:
                continue
            link_relative = posixpath.normpath(parts[1].strip())
            if link_relative.startswith("lib/") and not link_relative.endswith("/"):
                available_libs.add(posixpath.basename(link_relative))

        missing: list[tuple[str, str]] = []
        checked = 0
        for info in z.infolist():
            name = info.filename
            if info.is_dir():
                continue
            if not (
                name.startswith("bin/")
                or name.startswith("lib/")
                or name.startswith("libexec/")
            ):
                continue
            data = z.read(info)
            if data[:4] != b"\x7fELF":
                continue

            temp_path = ""
            try:
                with tempfile.NamedTemporaryFile(
                    prefix="nuxdroid-elf-", delete=False
                ) as temp:
                    temp.write(data)
                    temp_path = temp.name
                result = subprocess.run(
                    [readelf, "-d", temp_path],
                    capture_output=True,
                    text=True,
                    timeout=20,
                )
            except (OSError, subprocess.TimeoutExpired) as error:
                warn(f"readelf failed for {name}: {error}")
                continue
            finally:
                if temp_path:
                    try:
                        os.unlink(temp_path)
                    except OSError:
                        pass

            if result.returncode != 0:
                warn(f"readelf returned {result.returncode} for {name}")
                continue

            checked += 1
            for line in result.stdout.splitlines():
                if "(NEEDED)" not in line:
                    continue
                needed = line.split("[", 1)[-1].rstrip("]").strip()
                if needed and needed not in available_libs and needed not in SYSTEM_LIBS:
                    missing.append((name, needed))

        if missing:
            formatted = "\n  ".join(f"{name} needs {needed}" for name, needed in missing[:40])
            fail("retained ELF files need pruned libraries:\n  " + formatted)
        print(f"  ELF check: {checked} files, no missing NEEDED libraries")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="unpruned bootstrap zip")
    parser.add_argument("output", type=Path, help="pruned bootstrap zip")
    args = parser.parse_args()

    if not args.input.is_file():
        fail(f"input zip does not exist: {args.input}")
    if args.output.exists():
        fail(f"output already exists: {args.output}")

    with zipfile.ZipFile(args.input, "r") as zin:
        zip_names = zin.namelist()
        zip_name_set = set(zip_names)

        if "var/lib/dpkg/status" not in zip_name_set:
            fail("var/lib/dpkg/status is missing from the bootstrap")

        status_text = zin.read("var/lib/dpkg/status").decode("utf-8", "replace")
        blocks = parse_status(status_text)
        status_order = [package for package, _, _ in blocks]
        status = {package: fields for package, _, fields in blocks}
        if not status:
            fail("no packages found in var/lib/dpkg/status")

        file_lists: dict[str, list[str]] = {}
        for name in zip_names:
            if not name.startswith("var/lib/dpkg/info/") or not name.endswith(".list"):
                continue
            package = name[len("var/lib/dpkg/info/"):-len(".list")]
            if package:
                file_lists[package] = parse_file_list(
                    zin.read(name).decode("utf-8", "replace")
                )

        if "apt" not in file_lists:
            fail("var/lib/dpkg/info/apt.list is missing; cannot detect the prefix")

        provides: dict[str, list[str]] = {}
        for package, fields in status.items():
            for group in parse_dep_groups(fields.get("Provides", "")):
                for virtual_name in group:
                    provides.setdefault(virtual_name, []).append(package)

        available = set(status) | set(provides)

        targets = list(REQUIRED_TARGETS)
        missing_required = [
            package for package in REQUIRED_TARGETS if package not in available
        ]
        if missing_required:
            fail(
                "required bootstrap package(s) missing from status: "
                + ", ".join(missing_required)
            )

        for package in OPTIONAL_TARGETS:
            if package in available:
                targets.append(package)
            else:
                print(f"optional target not present, skipping: {package}")

        essential = [
            package
            for package, fields in status.items()
            if fields.get("Essential", "").lower() == "yes"
        ]

        selected: set[str] = set()
        unresolved: set[str] = set()
        stack = list(dict.fromkeys(targets + essential))

        while stack:
            dependency = stack.pop()
            if dependency in selected:
                continue

            if dependency in status:
                provider = dependency
            else:
                candidates = provides.get(dependency, [])
                if not candidates:
                    unresolved.add(dependency)
                    continue
                # Prefer a provider that is already selected, otherwise take
                # the first one.  All providers in status are installed.
                provider = next(
                    (candidate for candidate in candidates if candidate in selected),
                    candidates[0],
                )

            if provider in selected:
                continue
            selected.add(provider)

            fields = status.get(provider, {})
            for field_name in ("Pre-Depends", "Depends"):
                for alternatives in parse_dep_groups(fields.get(field_name, "")):
                    candidates = [
                        alternative
                        for alternative in alternatives
                        if alternative in available
                    ]
                    if candidates:
                        chosen = next(
                            (
                                candidate
                                for candidate in candidates
                                if candidate in selected
                            ),
                            candidates[0],
                        )
                        stack.append(chosen)
                    else:
                        stack.extend(alternatives)

        if unresolved:
            fail(
                "unresolved runtime dependencies: " + ", ".join(sorted(unresolved))
            )

        missing_keep = [
            package
            for package in MUST_KEEP
            if package in available and package not in selected
        ]
        if missing_keep:
            fail(
                "refusing to prune packages required at runtime: "
                + ", ".join(missing_keep)
            )

        removed = [package for package in status_order if package not in selected]
        if not removed:
            fail("no packages were pruned; the status database is unexpected")

        retained_blocks = [raw for package, raw, _ in blocks if package in selected]
        if len(retained_blocks) != len(selected):
            fail(
                f"selected package count ({len(selected)}) differs from retained "
                f"status block count ({len(retained_blocks)})"
            )
        status_output = "\n\n".join(retained_blocks) + "\n"

        retained_files: set[str] = set()
        for package in selected:
            retained_files.update(file_lists.get(package, []))

        removed_files: set[str] = set()
        for package in removed:
            removed_files.update(file_lists.get(package, []))
        removed_files -= retained_files

        # Some packages ship test binaries and Python bindings that are not
        # needed at runtime but are not separable via Depends.  Remove them
        # explicitly and filter the retained dpkg metadata so that dpkg does
        # not expect them.
        extra_prune_prefixes: set[str] = {"libexec/installed-tests/"}
        if "python" not in selected:
            for name in zip_names:
                match = re.match(r"^(lib/python\d+\.\d+)/", name)
                if match:
                    extra_prune_prefixes.add(match.group(1) + "/")

        if "SYMLINKS.txt" not in zip_name_set:
            fail("SYMLINKS.txt is missing from the bootstrap")
        symlink_text = zin.read("SYMLINKS.txt").decode("utf-8", "replace")

        extra_removed: set[str] = {
            name
            for name in zip_names
            if not name.endswith("/")
            and any(name.startswith(prefix) for prefix in extra_prune_prefixes)
        }
        # Symlinks are not zip entries; they only appear in SYMLINKS.txt.
        # Include links under extra-pruned trees so both the links and their
        # .list/.md5sums entries are removed consistently.
        for line in symlink_text.splitlines():
            parts = line.split("\u2190")
            if len(parts) != 2:
                continue
            link_relative = posixpath.normpath(parts[1].strip())
            if any(link_relative.startswith(prefix) for prefix in extra_prune_prefixes):
                extra_removed.add(link_relative)

        if extra_removed:
            retained_files -= extra_removed
            removed_files |= extra_removed

        removed_info_prefixes = tuple(
            f"var/lib/dpkg/info/{package}." for package in removed
        )

        if "SYMLINKS.txt" not in zip_name_set:
            fail("SYMLINKS.txt is missing from the bootstrap")

        kept_symlinks: list[str] = []
        dropped_symlinks = 0
        bad_symlinks: list[str] = []
        for line in symlink_text.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split("\u2190")
            if len(parts) != 2:
                fail(f"malformed SYMLINKS.txt line: {line!r}")
            target, link = parts[0].strip(), parts[1].strip()
            if link.startswith("/"):
                fail(f"absolute symlink path is not supported: {line!r}")
            link_relative = posixpath.normpath(link)
            if link_relative.startswith("./"):
                link_relative = link_relative[2:]
            if link_relative.startswith("../") or link_relative == "..":
                fail(f"symlink escapes the bootstrap prefix: {line!r}")

            if link_relative in removed_files:
                dropped_symlinks += 1
                continue

            # A retained symlink must not point at a file whose package was
            # removed.  Normally the dependency closure prevents this; fail
            # loudly instead of producing a subtly broken bootstrap.
            target_relative: str | None = None
            if target.startswith(PREFIX + "/"):
                target_relative = target[len(PREFIX) + 1:]
            elif not target.startswith("/"):
                target_relative = posixpath.normpath(
                    posixpath.join(posixpath.dirname(link_relative), target)
                )
            if (
                target_relative
                and target_relative in removed_files
                and target_relative not in retained_files
            ):
                bad_symlinks.append(
                    f"{line} -> missing target {target_relative}"
                )
            kept_symlinks.append(line)

        if bad_symlinks:
            fail(
                "retained symlink(s) point at pruned files:\n  "
                + "\n  ".join(bad_symlinks[:20])
            )
        if not kept_symlinks:
            fail("all symlinks were pruned; TermuxInstaller requires SYMLINKS.txt")

        def should_skip(name: str) -> bool:
            if removed_info_prefixes and name.startswith(removed_info_prefixes):
                return True
            return name in removed_files

        output_entries = 0
        with zipfile.ZipFile(
            args.output,
            "w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=9,
            allowZip64=True,
        ) as zout:
            for info in zin.infolist():
                name = info.filename

                if name == "SYMLINKS.txt":
                    info.compress_type = zipfile.ZIP_DEFLATED
                    zout.writestr(
                        info,
                        "\n".join(kept_symlinks) + "\n",
                        compress_type=zipfile.ZIP_DEFLATED,
                        compresslevel=9,
                    )
                    output_entries += 1
                    continue

                if name == "var/lib/dpkg/status":
                    info.compress_type = zipfile.ZIP_DEFLATED
                    zout.writestr(
                        info,
                        status_output,
                        compress_type=zipfile.ZIP_DEFLATED,
                        compresslevel=9,
                    )
                    output_entries += 1
                    continue

                if name.startswith("var/lib/dpkg/info/") and (
                    name.endswith(".list") or name.endswith(".md5sums")
                ):
                    package = name[len("var/lib/dpkg/info/"):].rsplit(".", 1)[0]
                    if package in selected:
                        info.compress_type = zipfile.ZIP_DEFLATED
                        filtered = filter_info_text(
                            name,
                            zin.read(info).decode("utf-8", "replace"),
                            extra_removed,
                        )
                        zout.writestr(
                            info,
                            filtered,
                            compress_type=zipfile.ZIP_DEFLATED,
                            compresslevel=9,
                        )
                        output_entries += 1
                        continue

                if info.is_dir():
                    # Keep directory entries: they are cheap, and some
                    # packages expect empty directories such as
                    # var/lib/dpkg/updates to exist.
                    info.compress_type = zipfile.ZIP_DEFLATED
                    zout.writestr(
                        info,
                        b"",
                        compress_type=zipfile.ZIP_DEFLATED,
                        compresslevel=9,
                    )
                    output_entries += 1
                    continue

                if should_skip(name):
                    continue

                data = zin.read(info)
                info.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(
                    info,
                    data,
                    compress_type=zipfile.ZIP_DEFLATED,
                    compresslevel=9,
                )
                output_entries += 1

        input_size = args.input.stat().st_size
        output_size = args.output.stat().st_size
        print(f"bootstrap pruned: {args.input} -> {args.output}")
        print(f"  packages: {len(status)} total, {len(selected)} kept, {len(removed)} removed")
        print(f"  zip entries: {len(zip_names)} -> {output_entries}")
        print(
            f"  symlinks: {len(kept_symlinks)} kept, {dropped_symlinks} dropped"
        )
        print(
            f"  size: {input_size / 1024 / 1024:.1f} MiB -> "
            f"{output_size / 1024 / 1024:.1f} MiB "
            f"({100 * output_size / input_size:.1f}%)"
        )
        print("  removed: " + ", ".join(removed))

        check_elf_needed(args.output, kept_symlinks)

        # Verify the output is readable and internally consistent.
        with zipfile.ZipFile(args.output, "r") as zout:
            output_names = set(zout.namelist())
            if "SYMLINKS.txt" not in output_names:
                fail("pruned zip is missing SYMLINKS.txt")
            output_status = zout.read("var/lib/dpkg/status").decode(
                "utf-8", "replace"
            )
            output_packages = {
                get_field(block, "Package")
                for block in output_status.strip("\n").split("\n\n")
                if get_field(block, "Package")
            }
            if not selected.issubset(output_packages):
                missing = sorted(selected - output_packages)
                fail("pruned status is missing selected packages: " + ", ".join(missing))
            if any(name.startswith(removed_info_prefixes) for name in output_names):
                fail("pruned zip still contains metadata of removed packages")
            if zout.testzip() is not None:
                fail("pruned zip failed its CRC check")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
