#!/bin/bash
# Installs the debug APK on a running emulator, opens every sample document in every mode,
# and fails if the app crashes or a document never reports "ready". Screenshots go to out/.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.documentfacilitator
FX=core/src/test/resources/fx
OUT=emulator-out
mkdir -p $OUT
adb install -r -g $APK || exit 1
adb shell run-as $PKG mkdir -p files/fx
for f in $FX/*; do
  n=$(basename "$f")
  adb push "$f" /data/local/tmp/$n >/dev/null
  adb shell "cat /data/local/tmp/$n | run-as $PKG sh -c 'cat > files/fx/$n'"
done
DIR=/data/data/$PKG/files/fx
fail=0
adb logcat -c
adb shell am start -W -n $PKG/.HomeActivity >/dev/null; sleep 3
adb exec-out screencap -p > $OUT/home.png
for f in $FX/*; do
  n=$(basename "$f")
  case $n in *-1.png) continue;; esac
  for m in 0 1 2; do
    adb logcat -c
    adb shell am start -W -S -n $PKG/.ViewerActivity -d "file://$DIR/$n" --ei mode $m >/dev/null
    ok=0
    case $n in *.pdf|*.png) need="ready .* mode=$m";; *) need="dom ready";; esac
    for i in $(seq 1 30); do
      if adb logcat -d -s DocFacilitator | grep -q "ready .* mode=$m" && adb logcat -d -s DocFacilitator | grep -q "$need"; then ok=1; break; fi
      if adb logcat -d | grep -q "FATAL EXCEPTION"; then break; fi
      sleep 1
    done
    sleep 2
    adb exec-out screencap -p > "$OUT/${n}_m$m.png"
    crash=$(adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -40)
    alive=$(adb shell pidof $PKG)
    if [ -n "$crash" ] || [ -z "$alive" ] || [ $ok -ne 1 ]; then
      echo "FAIL $n mode=$m ready=$ok alive=${alive:-no}"; echo "$crash"
      adb logcat -d -s DocFacilitator AndroidRuntime chromium | tail -30
      fail=$((fail+1))
    else
      echo "PASS $n mode=$m :: $(adb logcat -d -s DocFacilitator | grep -E 'paged:|dom ready|text:|fill:' | head -3 | sed 's/.*DocFacilitator: //' | tr '\n' ' ')"
    fi
    adb logcat -d > "$OUT/${n}_m$m.log"
  done
done
# split view with two documents, landscape
adb shell am force-stop $PKG
adb logcat -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell am start -W -n $PKG/.ViewerActivity --esa uris "file://$DIR/form.pdf,file://$DIR/sample.docx" >/dev/null
sleep 8
adb exec-out screencap -p > $OUT/split.png
if adb logcat -d | grep -q "FATAL EXCEPTION"; then echo "FAIL split"; adb logcat -d | grep -A25 "FATAL EXCEPTION"; fail=$((fail+1)); else echo "PASS split view"; fi
adb shell settings put system user_rotation 0
echo "failures: $fail"
exit $fail
