#!/usr/bin/env python3
"""
Rebuilds libs/zstd-jni-1.5.7-20.jar with our Android aarch64 zstd-jni native
library embedded, instead of the glibc one shipped by Maven Central.

The jar itself is committed to the repo, so Gradle never downloads zstd-jni.
This script only needs to be re-run when libs/native/ changes.

    python3 libs/build-zstd-jar.py

What it does:
  - keeps every .class from the existing jar untouched (verified by hash)
  - replaces linux/aarch64/libzstd-jni-1.5.7-20.so and linux/arm64/...
    with libs/native/libzstd-jni-a53-maxperf.so (bionic, Android NDK)
  - keeps linux/amd64/... from upstream so Gradle tests still run on CI
  - drops platforms nobody here uses (win, darwin, aix, freebsd, ...)
"""

import hashlib
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(HERE, "zstd-jni-1.5.7-20.jar")
NATIVE_DIR = os.path.join(HERE, "native")

# resourceName() inside com.github.luben.zstd.util.Native builds:
#   "/" + os.name + "/" + os.arch + "/libzstd-jni-1.5.7-20.so"
# os.name lowercases to "linux"; os.arch is "aarch64" on most Android JVMs
# and "arm64" on some, so both entries carry our library.
EMBED_ENTRIES = (
    "linux/aarch64/libzstd-jni-1.5.7-20.so",
    "linux/arm64/libzstd-jni-1.5.7-20.so",
)

# Upstream entry kept verbatim: CI runs Gradle tests on x86_64 glibc.
KEEP_UPSTREAM = ("linux/amd64/libzstd-jni-1.5.7-20.so",)

DROP_PREFIXES = (
    "aix/", "darwin/", "freebsd/", "win/",
    "linux/arm/", "linux/i386/", "linux/loongarch64/", "linux/mips64/",
    "linux/ppc64", "linux/riscv64/", "linux/s390x/",
)

STABLE_DATE = (2010, 1, 1, 0, 0, 0)


def find_native():
    """Prefer the versioned maxperf lib; fall back to any .so in native/."""
    preferred = os.path.join(NATIVE_DIR, "libzstd-jni-a53-maxperf.so")
    if os.path.isfile(preferred):
        return preferred
    candidates = sorted(
        os.path.join(NATIVE_DIR, n) for n in os.listdir(NATIVE_DIR) if n.endswith(".so")
    )
    if not candidates:
        sys.exit("libs/native/ has no .so - nothing to embed")
    return candidates[0]


def entry_info(name, mode=0o644):
    zi = zipfile.ZipInfo(name, date_time=STABLE_DATE)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = (mode << 16) | 0x80000000
    return zi


def main():
    if not os.path.isfile(JAR):
        sys.exit(f"missing {JAR} - commit the jar first")
    so_path = find_native()
    blob = open(so_path, "rb").read()
    print(f"embedding {so_path} ({len(blob)} bytes)")

    tmp = JAR + ".tmp"
    stats = {"embedded": 0, "upstream": 0, "dropped": 0, "other": 0}
    classes_before = {}

    with zipfile.ZipFile(JAR, "r") as zin:
        for info in zin.infolist():
            if info.filename.endswith(".class"):
                classes_before[info.filename] = hashlib.sha256(zin.read(info.filename)).hexdigest()
        zin.seek(0) if hasattr(zin, "seek") else None

    with zipfile.ZipFile(JAR, "r") as zin, zipfile.ZipFile(
        tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9
    ) as zout:
        for info in zin.infolist():
            name = info.filename
            if name.endswith("/"):
                continue
            if name in EMBED_ENTRIES:
                zout.writestr(entry_info(name, 0o755), blob)
                stats["embedded"] += 1
            elif name.startswith(DROP_PREFIXES):
                stats["dropped"] += 1
            else:
                zout.writestr(entry_info(name), zin.read(name))
                if name in KEEP_UPSTREAM:
                    stats["upstream"] += 1
                else:
                    stats["other"] += 1

        # add fallback entries the upstream jar never had
        for name in EMBED_ENTRIES:
            if name not in zin.namelist():
                zout.writestr(entry_info(name, 0o755), blob)

    os.replace(tmp, JAR)

    # .class bytes must be identical to before - we only touch native libs
    with zipfile.ZipFile(JAR, "r") as znow:
        for name, digest in classes_before.items():
            got = hashlib.sha256(znow.read(name)).hexdigest()
            if got != digest:
                sys.exit(f"REFUSING: class {name} changed")

    print(
        f"embedded={stats['embedded']} upstream_amd64={stats['upstream']} "
        f"dropped={stats['dropped']} kept={stats['other']}"
    )
    print(f"ok -> {JAR} ({os.path.getsize(JAR)} bytes)")


if __name__ == "__main__":
    main()
