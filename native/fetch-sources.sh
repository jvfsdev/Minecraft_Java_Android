#!/bin/bash
# Fetches pinned upstream sources needed for the Android runtime (official repos only).
set -e
cd "$(dirname "$0")/src"
clone() { [ -d "$1" ] || git clone --depth 1 --branch "$2" "$3" "$1"; }
clone jdk25u      jdk-25.0.4-ga   https://github.com/openjdk/jdk25u.git
clone lwjgl3      3.4.3           https://github.com/LWJGL/lwjgl3.git
clone SDL         release-3.4.18  https://github.com/libsdl-org/SDL.git
clone libffi      v3.8.0          https://github.com/libffi/libffi.git
clone openal-soft 1.25.2          https://github.com/kcat/openal-soft.git
clone freetype    VER-2-15-0      https://gitlab.freedesktop.org/freetype/freetype.git
clone SPIRV-Cross vulkan-sdk-1.4.363.0 https://github.com/KhronosGroup/SPIRV-Cross.git
clone shaderc     v2026.4         https://github.com/google/shaderc.git
clone jna         5.17.0          https://github.com/java-native-access/jna.git
# shaderc's own dependencies, at the revisions pinned in shaderc/DEPS
pin() { [ -d "$1" ] && return; mkdir -p "$1"; git -C "$1" init -q; git -C "$1" remote add origin "$2"
        git -C "$1" fetch -q --depth 1 origin "$3"; git -C "$1" checkout -q FETCH_HEAD; }
pin shaderc/third_party/glslang       https://github.com/KhronosGroup/glslang.git       e1b562a8bed273a02f30b59b66a5d499793cede5
pin shaderc/third_party/spirv-headers https://github.com/KhronosGroup/SPIRV-Headers.git 04fd3caa1e8267e4d95c806cad901181728e1006
pin shaderc/third_party/spirv-tools   https://github.com/KhronosGroup/SPIRV-Tools.git   ef96ed763b43b59b33b31b362f09a02b729fa1c9

# Local patches (Android/bionic and build fixes)
P="$(cd .. && pwd)/patches"
git -C jdk25u   apply --reverse --check "$P/jdk25u-android.patch" 2>/dev/null || git -C jdk25u apply "$P/jdk25u-android.patch"
git -C lwjgl3   apply --reverse --check "$P/lwjgl3-android.patch" 2>/dev/null || git -C lwjgl3 apply "$P/lwjgl3-android.patch"
git -C shaderc  apply --reverse --check "$P/shaderc-cmake.patch"  2>/dev/null || git -C shaderc apply "$P/shaderc-cmake.patch"
git -C SDL      apply --reverse --check "$P/sdl-android.patch"     2>/dev/null || git -C SDL apply "$P/sdl-android.patch"
