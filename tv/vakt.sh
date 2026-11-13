#!/data/data/com.termux/files/usr/bin/bash
# Startar "vakten" på TV-boxen: den stoppar Telias startsida innan den hinner visas och öppnar Hem i stället.
# Behöver köras igen om boxen har startats om.
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37363}
adb connect "$ADDR" >/dev/null 2>&1
adb shell 'pkill -f se.cruzi.tvhem.Guard; P=$(pm path se.cruzi.tvhem | head -1 | cut -d: -f2); [ -z "$P" ] && { echo "Hem saknas"; exit 1; }
  CLASSPATH=$P setsid app_process / se.cruzi.tvhem.Guard </dev/null >/data/local/tmp/hemvakt.log 2>&1 &
  sleep 2; cat /data/local/tmp/hemvakt.log' </dev/null
if adb shell pgrep -f se.cruzi.tvhem.Guard </dev/null >/dev/null 2>&1; then printf '\033[32mVakten är igång – Telias startsida kan inte visas.\033[0m\n'
else printf '\033[31mVakten startade inte.\033[0m\n'; fi
