#!/bin/bash
# Emulator smoke for v0.4: go live with simulated viewers (real free model through the Kilo gateway),
# let the 直播专家组 run, screenshot 直播间 / 导播台「她在想」/ 她的成长, stop (diary + next plan), dump state.
set -x
OUT=out/smoke; mkdir -p $OUT
P=com.dl1991.feiyu/.MainActivity
shot() { adb exec-out screencap -p > $OUT/feiyu-v04-$1.png; }
go() { adb shell am start --activity-single-top -n $P "$@"; }
adb install -r apk/feiyu-live-debug.apk || exit 1
adb shell pm grant com.dl1991.feiyu android.permission.RECORD_AUDIO || true
adb shell pm grant com.dl1991.feiyu android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n $P --ez ci_demo true
sleep 10; shot home
go --ez ci_live true --ez ci_autoapprove true --es ci_route dfy/live
sleep 75; shot live-1
go --es ci_route fy/director
sleep 60; shot director-1
adb shell input swipe 540 1500 540 700 400; sleep 2; shot director-2
adb shell input swipe 540 1500 540 700 400; sleep 2; shot director-3
sleep 45
go --es ci_route dfy/live; sleep 8; shot live-2
go --ez ci_dump true; sleep 2
go --ez ci_stop true
sleep 60
go --es ci_route fy/growth; sleep 6; shot growth-1
adb shell input swipe 540 1500 540 600 400; sleep 2; shot growth-2
go --ez ci_dump true; sleep 3
adb logcat -d > $OUT/logcat.txt
grep -E "FeiyuCI|FATAL EXCEPTION|AndroidRuntime|DafeiyuLive|Dafeiyu " $OUT/logcat.txt | tail -300 > $OUT/summary.txt
cat $OUT/summary.txt | grep -E "FeiyuCI|FATAL" | tail -60
ls -la $OUT
exit 0
