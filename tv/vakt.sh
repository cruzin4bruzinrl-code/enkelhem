#!/data/data/com.termux/files/usr/bin/bash
# Startar "vakten" på TV-boxen: den stoppar Telias startsida innan den hinner visas och öppnar Hem i stället.
# Behöver köras igen om boxen har startats om.
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37363}
adb connect "$ADDR" >/dev/null 2>&1
A(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
P=$(A pm path se.cruzi.tvhem | head -1 | cut -d: -f2)
echo "apk: ${P:-SAKNAS}"
[ -z "$P" ] && exit 1
for p in $(A pgrep -f 'se.cruzi.tvhem.Gu[a]rd'); do A kill "$p" >/dev/null; done
echo "provkör i förgrunden:"
A "CLASSPATH=$P timeout 5 app_process /system/bin se.cruzi.tvhem.Guard" | head -15 | sed 's/^/  /'
A "CLASSPATH=$P nohup app_process /system/bin se.cruzi.tvhem.Guard >/data/local/tmp/hemvakt.log 2>&1 </dev/null &"
sleep 3
if [ -n "$(A pgrep -f 'se.cruzi.tvhem.Gu[a]rd')" ]; then printf '\033[32mVakten är igång – Telias startsida kan inte visas.\033[0m\n'
else printf '\033[31mVakten startade inte.\033[0m\n'; A cat /data/local/tmp/hemvakt.log | head -15 | sed 's/^/  /'
  A logcat -d -s HemVakt AndroidRuntime | tail -10 | sed 's/^/  /'; fi
