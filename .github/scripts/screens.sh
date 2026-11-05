#!/usr/bin/env bash
# Съёмка скриншотов для магазина на эмуляторе (вызывается из workflow screenshots.yml)
set -x
PKG=ru.moistranicy.app
OUT=${OUT:-screens}
mkdir -p "$OUT"
adb install -r app/build/outputs/apk/debug/app-debug.apk
# вертикальная ориентация и чуть крупнее шрифт
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation ${ROTATION:-0}
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
# аккуратная строка состояния
adb shell settings put global sysui_demo_allowed 1
dm() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
dm enter; dm clock -e hhmm 1200; dm battery -e level 100 -e plugged false
dm network -e wifi show -e level 4 -e mobile hide; dm notifications -e visible false

adb shell am start -W -n $PKG/.DemoSeedActivity
for i in $(seq 1 90); do
  adb shell run-as $PKG cat files/seed_done 2>/dev/null | grep -q ok && break
  sleep 5
done
IDS=$(adb shell run-as $PKG cat files/seed_ids.txt)
echo "$IDS"
get() { echo "$IDS" | tr -d '\r' | grep "^$1=" | head -1 | cut -d= -f2; }
BOOK=$(get book); AUTHOR=$(get authorpage2)

adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 12
dm enter; dm clock -e hhmm 1200; dm notifications -e visible false

shot() { # $1 — имя файла, $2 — куда перейти, $3 — сколько ждать
  adb shell am start -n $PKG/.ui.MainActivity --es open "$2"
  sleep "${3:-8}"
  adb exec-out screencap -p > "$OUT/$1.png"
}
shot 01-shelf-want   shelf:WANT 10
shot 02-shelf-read   shelf:READ 10
shot 03-shelf-reading shelf:READING 8
[ -n "$BOOK" ] && shot 04-book "book:$BOOK" 10
[ -n "$AUTHOR" ] && shot 05-author "author:$AUTHOR" 14
shot 06-authors-cloud tab:authors 12
shot 07-recommendations tab:recs 25
shot 08-profile tab:profile 6
ls -la "$OUT"
