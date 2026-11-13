#!/data/data/com.termux/files/usr/bin/bash
# Startar "vakten" på TV-boxen: den stoppar Telias startsida innan den hinner visas och öppnar Hem i stället.
# Behöver köras igen om boxen har startats om.
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37363}
adb connect "$ADDR" >/dev/null 2>&1
T=${TMPDIR:-$PREFIX/tmp}/hemvakt.txt
adb shell 'for p in $(pgrep -f "se.cruzi.tvhem.Gu[a]rd"); do kill $p; done
P=$(pm path se.cruzi.tvhem | head -1 | cut -d: -f2)
[ -z "$P" ] && { echo "Hem saknas på boxen"; exit 1; }
L=/data/local/tmp/hemvakt.log
: > $L
CLASSPATH=$P nohup app_process /system/bin se.cruzi.tvhem.Guard >$L 2>&1 </dev/null &
sleep 3
if pgrep -f "se.cruzi.tvhem.Gu[a]rd" >/dev/null; then echo "VAKT_OK"; else echo "VAKT_FEL"; fi
head -c 1500 $L' </dev/null | tr -d '\r' > $T 2>&1
if grep -q VAKT_OK $T; then printf '\033[32mVakten är igång – Telias startsida kan inte visas.\033[0m\n'
else printf '\033[31mVakten startade inte.\033[0m\n'; grep -v VAKT_ $T | head -25; adb logcat -d -s HemVakt AndroidRuntime </dev/null | tail -15; fi
rm -f $T
