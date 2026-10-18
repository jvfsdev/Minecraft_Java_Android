#!/bin/bash
# Dev loop: build + install debug APK, start the first instance in demo mode, dump game log.
set -e
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
./gradlew assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell am force-stop dev.prismdroid
adb shell am start -n dev.prismdroid/.ui.MainActivity >/dev/null; sleep 8
adb logcat -c
# tap the "Demo" button of the first instance card, wherever the layout put it
B=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -oE 'text="Demo"[^>]*bounds="[^"]*"' | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 \
  | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
set -- $B
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
for i in $(seq 1 ${WAIT:-12}); do sleep 5; adb logcat -d | grep -q 'game exited' && break; done
adb logcat -d | grep -E 'PrismDroid|Minecraft|FATAL|DEBUG  ' | grep -vE 's_gl|DT_RPATH|^\S+ \S+ +[0-9]+ +[0-9]+ I Minecraft: \s+at ' | tail -${LINES_OUT:-60}
