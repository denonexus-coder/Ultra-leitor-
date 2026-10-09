# A53 native build notes (`libzstd-jni-a53-maxperf.so`)

Target: 8× Cortex-A53 (MediaTek MT8768, `asimd`+`crc32`), Android bionic,
used for `linux/aarch64` and `linux/arm64` inside `libs/zstd-jni-1.5.7-20.jar`.

Source tree: `/root/zstd-jni-a53/src` (luben/zstd-jni fork, version file `1.5.7-21`).
Helper scripts saved next to it: `build_variant.sh`, `train_pgo.sh`, `bench.c`.

## Flags that made the cut (variant `v2b` + PGO)

```
compile:
  -O3 -march=armv8-a+crc -mtune=cortex-a53 -fomit-frame-pointer -fPIC
  -fno-semantic-interposition -fprofile-instr-use=profdata/v2b.profdata

link:
  -fuse-ld=lld
  -Wl,-Bsymbolic
  -Wl,--version-script=libzstd-jni.so.map        # only Java_*/ZSTD_*/ZDICT_*/zstd_java_* exported
  -Wl,-z,max-page-size=16384,-z,common-page-size=16384,-z,relro,-z,now
```

Two traps found the hard way:

* the old lib was configured **without `-DNDEBUG`** → `assert()` compiled into
  hot zstd loops; a clean `-O3 -DNDEBUG` rebuild alone is worth ~3 %.
* `-flto=thin` (even thin) gave no gain and high run-to-run variance here.

## PGO flow

```sh
# 1. instrumented build  (build_variant.sh v2bgen)
# 2. train on real chunk NBT (mix of 1.12.2 + 1.21.11 decompressed .mca payloads):
LLVM_PROFILE_FILE=profdata/v2b-%p.profraw taskset -c 2 ./bench lib_v2bgen.so mix.bin 3
# 3. merge + rebuild with use
llvm-profdata merge -o profdata/v2b.profdata profdata/v2b-*.profraw
build_variant.sh v2buse
```

## Measured (3 interleaved rounds, pinned to a 2.2 GHz A53 core, 26 MB real
chunk-NBT mix, best-of-7, C harness dlopening each candidate):

| lib | compress | decompress | 16k compress | 16k decompress |
|---|---|---|---|---|
| old maxperf (872 KB) | 902 MB/s | 2096 MB/s | 375 MB/s | 698 MB/s |
| **new (626 KB)** | **941 MB/s** | **2199 MB/s** | **390 MB/s** | 697 MB/s |

Roundtrip byte-exactness is asserted by `bench.c` on every run; all 149
`Java_com_github_luben_zstd*` exports verified identical to the old lib.

## Install

```sh
python3 libs/build-zstd-jar.py   # re-embeds libs/native/libzstd-jni-a53-maxperf.so
```
