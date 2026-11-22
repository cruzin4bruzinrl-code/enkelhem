#!/data/data/com.termux/files/usr/bin/bash
# Visar vilken app som spelar ljud på boxen just nu och stänger den (musikappar som Spotify lämnas ifred).
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37625}
adb connect "$ADDR" >/dev/null 2>&1
A(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
if ! adb -s "$ADDR" get-state >/dev/null 2>&1; then printf '\033[31mInte ansluten till boxen (%s).\033[0m\n' "$ADDR"; exit 1; fi
pids=$(A dumpsys audio | grep -E 'state:started' | grep -oE 'u/pid:[0-9]+/[0-9]+' | cut -d/ -f3 | sort -u)
sess=$(A dumpsys media_session | grep -E 'package=|state=PlaybackState' | paste - - | grep -E 'state=3' | grep -oE 'package=[^ ]+' | cut -d= -f2 | sort -u)
pk=""
for p in $pids; do n=$(A cat /proc/$p/cmdline | tr '\0' ' ' | awk '{print $1}'); n=${n%%:*}; [ -n "$n" ] && pk="$pk $n"; done
pk=$(echo $pk $sess | tr ' ' '\n' | grep -v '^$' | sort -u)
if [ -z "$pk" ]; then echo "Inget ljud spelas på boxen just nu."; exit 0; fi
echo "Ljud kommer från:"; for p in $pk; do echo "  $p"; done
for p in $pk; do
  case "$p" in
    *spotify*|*tidal*|*deezer*|*soundcloud*|*music*|*podcast*|*radio*) echo "  $p är musik – lämnas igång";;
    se.cruzi.tvhem) A am force-stop se.cruzi.tvhem; A am start -n se.cruzi.tvhem/.HomeActivity >/dev/null
      A settings put secure enabled_accessibility_services se.cruzi.tvhem/.HomeKeyService; A settings put secure accessibility_enabled 1
      echo "  Hem startad om";;
    *) A am force-stop "$p"; echo "  $p stängd";;
  esac
done
A logcat -d -s HemTv | grep -E "pausade|ljud bakom|stängde" | tail -5 | sed 's/^/  logg: /'
