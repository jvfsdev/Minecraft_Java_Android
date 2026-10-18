#!/bin/bash
# Builds every native library the Android game process loads, into app/src/main/jniLibs/arm64-v8a:
#   libSDL3.so (+ SDL's Java glue), liblwjgl*.so, libopenal.so, libfreetype.so,
#   libspirv-cross.so, libshaderc.so, libjnidispatch.so (JNA)
# Usage: native/build-natives.sh [component...]   (default: all)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"; OUT="$HERE/out/natives"
NDK="${NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
API="${API:-30}"; ABI=arm64-v8a
SDK="$(dirname "$(dirname "$NDK")")"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"; NINJA="$SDK/cmake/3.22.1/bin/ninja"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
DEST="$HERE/../app/src/main/jniLibs/$ABI"
JNI_INCLUDE="$HERE/out/jre/images/jdk/include"
mkdir -p "$OUT" "$DEST"

cm() { # cm <name> <source dir> [cmake args...]
  local name=$1 src=$2; shift 2
  "$CMAKE" -S "$src" -B "$OUT/$name" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" "$@"
  "$CMAKE" --build "$OUT/$name" -j "$(sysctl -n hw.ncpu)"
}
strip_to() { "$TC/bin/llvm-strip" --strip-unneeded -o "$DEST/$2" "$1"; echo "  -> $2"; }

build_sdl() {
  cm sdl "$SRC/SDL" -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST_LIBRARY=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF
  strip_to "$OUT/sdl/libSDL3.so" libSDL3.so
  local J="$HERE/../app/src/main/java/org/libsdl/app"
  mkdir -p "$J"; cp "$SRC/SDL/android-project/app/src/main/java/org/libsdl/app/"*.java "$J/"
}

build_lwjgl() {
  [ -f "$JNI_INCLUDE/jni.h" ] || { echo "build the JRE first (needs $JNI_INCLUDE)"; exit 1; }
  cm lwjgl "$HERE/lwjgl" -DJNI_INCLUDE="$JNI_INCLUDE"
  for l in lwjgl lwjgl_stb lwjgl_vma lwjgl_opengl; do strip_to "$OUT/lwjgl/lib$l.so" "lib$l.so"; done
}

build_openal() {
  cm openal "$SRC/openal-soft" -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF \
    -DALSOFT_INSTALL=OFF -DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_EMBED_HRTF_DATA=ON
  strip_to "$OUT/openal/libopenal.so" libopenal.so
}

build_freetype() {
  cm freetype "$SRC/freetype" -DBUILD_SHARED_LIBS=ON -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON \
    -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
  strip_to "$(ls "$OUT"/freetype/libfreetype.so* | head -1)" libfreetype.so
}

build_spvc() {
  cm spvc "$SRC/SPIRV-Cross" -DSPIRV_CROSS_SHARED=ON -DSPIRV_CROSS_STATIC=ON -DSPIRV_CROSS_CLI=OFF \
    -DSPIRV_CROSS_ENABLE_TESTS=OFF -DSPIRV_CROSS_FORCE_PIC=ON
  strip_to "$(ls "$OUT"/spvc/libspirv-cross-c-shared.so* | head -1)" libspirv-cross.so
}

build_shaderc() {
  # Upstream shaderc (LWJGL 3.4.3 needs a newer API than the NDK copy), with glslang,
  # SPIRV-Tools and SPIRV-Headers checked out at the revisions pinned in shaderc's DEPS.
  cm shaderc "$SRC/shaderc" -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
    -DSHADERC_SKIP_INSTALL=ON -DSPIRV_SKIP_EXECUTABLES=ON -DSPIRV_SKIP_TESTS=ON -DENABLE_GLSLANG_BINARIES=OFF \
    -DSPIRV_TOOLS_BUILD_STATIC=ON -DSHADERC_ENABLE_SHARED_CRT=OFF \
    -DGLSLANG_ENABLE_INSTALL=OFF -DSKIP_GLSLANG_INSTALL=ON -DSKIP_SPIRV_TOOLS_INSTALL=ON -DSPIRV_HEADERS_SKIP_INSTALL=ON
  strip_to "$OUT/shaderc/libshaderc/libshaderc_shared.so" libshaderc.so
}

build_jna() {
  # JNA ships no bionic libjnidispatch (its jar has glibc linux-aarch64 only), so oshi & co. fail with
  # "Could not initialize class com.sun.jna.Native". Build it from the JNA source with its bundled
  # libffi. No autotools: libffi's few sources are compiled directly with a fixed fficonfig.h.
  local J="$SRC/jna" B="$OUT/jna" CC="$TC/bin/aarch64-linux-android$API-clang"
  local JNI_VER JNI_MD5
  JNI_VER=$(sed -nE 's/.*name="jni\.(major|minor|revision)" value="([0-9]+)".*/\2/p' "$J/common.xml" | paste -sd. -)
  JNI_MD5=$(sed -nE 's/.*name="jni\.md5" value="([0-9a-f]+)".*/\1/p' "$J/common.xml")
  rm -rf "$B"; mkdir -p "$B/headers" "$B/classes" "$B/ffi/include" "$B/obj"
  # JNI headers (com_sun_jna_Native.h, ...), as JNA's ant "compile" target makes them
  find "$J/src/com/sun/jna" -maxdepth 1 -name '*.java' > "$B/sources.txt"
  find "$J/src/com/sun/jna/ptr" "$J/src/com/sun/jna/internal" "$J/src/com/sun/jna/win32" -name '*.java' >> "$B/sources.txt"
  "${BOOT_JDK:-${JAVA_HOME:?set JAVA_HOME to a JDK 25}}/bin/javac" -nowarn -XDignore.symbol.file -d "$B/classes" -h "$B/headers" @"$B/sources.txt" 2>&1 | grep -v '^Note:' || true
  # libffi headers
  local F="$J/native/libffi"
  sed -e 's/@VERSION@/jna/' -e 's/@TARGET@/AARCH64/' -e 's/@HAVE_LONG_DOUBLE@/1/' -e 's/@FFI_EXEC_TRAMPOLINE_TABLE@/0/' \
    "$F/include/ffi.h.in" > "$B/ffi/include/ffi.h"
  cp "$F/src/aarch64/ffitarget.h" "$B/ffi/include/"; cp "$HERE/jna/fficonfig.h" "$B/ffi/include/"
  local CFLAGS=(-O2 -fPIC -fno-omit-frame-pointer -fno-strict-aliasing -funwind-tables -fno-short-enums -ffunction-sections
    -DFFI_STATIC_BUILD -I"$B/ffi/include" -I"$F/include" -I"$F/src/aarch64")
  local o objs=()
  for c in prep_cif.c types.c raw_api.c java_raw_api.c closures.c tramp.c aarch64/ffi.c aarch64/sysv.S; do
    o="$B/obj/ffi_$(basename "${c%.*}").o"; "$CC" "${CFLAGS[@]}" -c "$F/src/$c" -o "$o"; objs+=("$o")
  done
  for c in dispatch.c callback.c; do
    o="$B/obj/${c%.c}.o"
    "$CC" "${CFLAGS[@]}" -w -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" -I"$B/headers" -D_REENTRANT -DHAVE_PROTECTION -DNO_JAWT \
      -DNO_WEAK_GLOBALS -DJNA_JNI_VERSION="\"$JNI_VER\"" -DCHECKSUM="\"$JNI_MD5\"" -c "$J/native/$c" -o "$o"
    objs+=("$o")
  done
  "$CC" -shared -o "$B/libjnidispatch.so" "${objs[@]}" -Wl,-soname,libjnidispatch.so,-Bsymbolic \
    -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 -ldl -lm
  strip_to "$B/libjnidispatch.so" libjnidispatch.so
}

ALL=(sdl lwjgl openal freetype spvc shaderc jna)
for c in "${@:-${ALL[@]}}"; do echo "== $c"; "build_$c"; done
ls -la "$DEST"
