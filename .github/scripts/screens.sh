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
BOOK=$(get book); AUTHOR=$(get authorpage2); CYCLE=$(get cycle)

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
[ -n "$CYCLE" ] && shot 09-cycle "book:$CYCLE" 14
[ -n "$AUTHOR" ] && shot 05-author "author:$AUTHOR" 14
shot 06-authors-cloud tab:authors 12
shot 07-recommendations tab:recs 25
shot 08-profile tab:profile 6
# ---- читалка: открываем тестовые FB2 и EPUB как «Открыть с помощью» ----
SZ=$(adb shell wm size | tr -d '\r' | awk -F': ' '{print $2}' | tail -1); W=${SZ%x*}; H=${SZ#*x}
for f in test.fb2 test.epub; do
  adb push .github/test-books/$f /data/local/tmp/$f
  adb shell chmod 644 /data/local/tmp/$f
  adb shell run-as $PKG cp /data/local/tmp/$f files/$f
done
adb shell am start -W -a android.intent.action.VIEW -t application/x-fictionbook+xml \
  -d file:///data/data/$PKG/files/test.fb2 -n $PKG/.ui.MainActivity
sleep 8; adb exec-out screencap -p > "$OUT/10-reader-fb2.png"
adb shell input tap $((W*85/100)) $((H/2)); sleep 2; adb exec-out screencap -p > "$OUT/11-reader-page2.png"
adb shell input swipe $((W*80/100)) $((H/2)) $((W*20/100)) $((H/2)) 200; sleep 2
adb shell input tap $((W/2)) $((H/2)); sleep 2; adb exec-out screencap -p > "$OUT/12-reader-menu.png"
adb shell input keyevent 4; sleep 2
adb shell am start -W -a android.intent.action.VIEW -t application/epub+zip \
  -d file:///data/data/$PKG/files/test.epub -n $PKG/.ui.MainActivity
sleep 8; adb exec-out screencap -p > "$OUT/13-reader-epub.png"
adb shell input keyevent 4; sleep 2
shot 14-library route:library 6
shot 15-profile tab:profile 6
adb logcat -d | grep -iE "AndroidRuntime|FATAL|moistranicy" | tail -40 > "$OUT/logcat.txt" || true
ls -la "$OUT"
