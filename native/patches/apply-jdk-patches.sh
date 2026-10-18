#!/bin/bash
# Applies patches/jdk25u-android.patch (bionic/Android + macOS-host cross-build fixes) once.
set -euo pipefail
SRC="$1"; PATCH="$(cd "$(dirname "$0")" && pwd)/jdk25u-android.patch"
cd "$SRC"
if git apply --reverse --check "$PATCH" 2>/dev/null; then exit 0; fi   # already applied
git apply "$PATCH"
