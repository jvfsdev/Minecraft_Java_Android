#!/bin/bash
# Cross-compiles OpenJDK 25 (jdk25u) for Android arm64 (bionic) and packages a
# runtime archive for the launcher: app/src/main/assets/runtime/jre-25-arm64.tar.xz
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src/jdk25u"
OUT="$HERE/out/jre"
DEPS="$HERE/out/jre-deps"
NDK="${NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
API="${API:-30}"
BOOT_JDK="${BOOT_JDK:-${JAVA_HOME:?set JAVA_HOME (or BOOT_JDK) to a JDK 25}}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
export SDKROOT="$(xcrun --show-sdk-path)"
SYSROOT="$TC/sysroot"
TRIPLE=aarch64-linux-android
JOBS="${JOBS:-$(sysctl -n hw.ncpu)}"

mkdir -p "$OUT" "$DEPS/include" "$DEPS/lib"
# bionic folds pthread/rt into libc: empty archives satisfy -lpthread -lrt
for l in pthread rt; do [ -f "$DEPS/lib/lib$l.a" ] || "$TC/bin/llvm-ar" rcs "$DEPS/lib/lib$l.a"; done

# --- headers-only deps (libraries are dlopen()ed at runtime and never present) ---
if [ ! -d "$DEPS/include/cups" ]; then
  cp -R "$(xcrun --show-sdk-path)/usr/include/cups" "$DEPS/include/cups"
fi
if [ ! -f "$DEPS/include/fontconfig/fontconfig.h" ]; then
  mkdir -p "$DEPS/include/fontconfig"
  curl -fsSL -o "$DEPS/include/fontconfig/fontconfig.h" \
    https://gitlab.freedesktop.org/fontconfig/fontconfig/-/raw/2.16.0/fontconfig/fontconfig.h
fi

# --- source patches for bionic (idempotent) ---
"$HERE/patches/apply-jdk-patches.sh" "$SRC"

cd "$SRC"
if [ ! -f "$OUT/spec.gmk" ] || [ "${RECONFIGURE:-0}" = 1 ]; then
  ( cd "$OUT" && bash "$SRC/configure" \
    --openjdk-target=$TRIPLE \
    --with-toolchain-type=clang \
    --with-sysroot="$SYSROOT" \
    --with-boot-jdk="$BOOT_JDK" \
    --with-jvm-variants=server \
    --with-debug-level=release \
    --with-native-debug-symbols=none \
    --enable-headless-only \
    --disable-warnings-as-errors \
    --disable-precompiled-headers \
    --with-stdc++lib=dynamic \
    --with-build-jdk="$BOOT_JDK" \
    --with-cups-include="$DEPS/include" \
    --with-fontconfig-include="$DEPS/include" \
    --with-freetype=bundled --with-libpng=bundled --with-libjpeg=bundled \
    --with-giflib=bundled --with-lcms=bundled --with-zlib=system \
    --with-extra-cflags="-fPIC -DMUSL_LIBC" \
    --with-extra-cxxflags="-fPIC -DMUSL_LIBC" \
    --with-extra-ldflags="-static-libstdc++ -Wl,-z,max-page-size=16384 -L$DEPS/lib -Wl,--undefined-version" \
    --with-vendor-name=PrismDroid \
    CC="$TC/bin/${TRIPLE}${API}-clang" \
    CXX="$TC/bin/${TRIPLE}${API}-clang++" \
    AR="$TC/bin/llvm-ar" NM="$TC/bin/llvm-nm" \
    OBJCOPY="$TC/bin/llvm-objcopy" STRIP="$TC/bin/llvm-strip" \
    READELF="$TC/bin/llvm-readelf" \
    BUILD_CC="$HERE/tools/host-cc" BUILD_CXX="$HERE/tools/host-cxx" \
    BUILD_AR=/usr/bin/ar BUILD_NM=/usr/bin/nm \
    BUILD_OBJCOPY="$TC/bin/llvm-objcopy" BUILD_STRIP=/usr/bin/strip )
fi

make -C "$OUT" ${MAKE_EXTRA:-} JOBS="$JOBS" ${TARGET:-images}
[ "${TARGET:-images}" = images ] || exit 0

# --- package the runtime (no jmods/demos/headers) ---
IMG="$OUT/images/jdk"
STAGE="$HERE/out/jre-stage/jre-25"
rm -rf "$HERE/out/jre-stage"; mkdir -p "$STAGE"
cp -R "$IMG"/{bin,conf,lib,legal,release} "$STAGE/"
rm -rf "$STAGE/lib/src.zip" "$STAGE/lib/ct.sym" "$STAGE/lib/"*.diz
find "$STAGE" -name '*.so' -exec "$TC/bin/llvm-strip" --strip-unneeded {} +
DEST="$HERE/../app/src/main/assets/runtime"
mkdir -p "$DEST"
COPYFILE_DISABLE=1 tar -C "$HERE/out/jre-stage" -cf - jre-25 | xz -T0 -9 > "$DEST/jre-25-arm64.tar.xz"
sha256sum "$DEST/jre-25-arm64.tar.xz" 2>/dev/null || shasum -a 256 "$DEST/jre-25-arm64.tar.xz"
