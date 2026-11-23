#!/data/data/com.termux/files/usr/bin/bash
# Startar alla Telia-kanaler på boxen en i taget via Hem och kollar att Telias spelare kommer igång.
[ -f ~/.tvconf ] && . ~/.tvconf
# bash kanalalla.sh "Kanal 11" "CNBC" …  provar bara de kanalerna
ONLY=$(printf '%s\n' "$@")
adb connect "$ADDR" >/dev/null 2>&1
A(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
NAME=$(A settings get global hem_kanaler)
URL=https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/kanaler-$NAME.txt
[ "$NAME" = null ] && URL=https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/kanaler.txt
DIR=/storage/emulated/0/Download/kanaler_test_$NAME; rm -rf "$DIR"; mkdir -p "$DIR"; R=$DIR/resultat.txt; : > "$R"
ok=0; fel=0
while IFS='|' read -r kind name rest; do
  [ "$kind" = telia ] || continue
  [ -n "$ONLY" ] && ! printf '%s\n' "$ONLY" | grep -qxF "$name" && continue
  A input keyevent 3; sleep 2
  adb -s "$ADDR" logcat -c </dev/null
  w="remoteChannel(\"$name\")"
  A am start -n se.cruzi.tvhem/.HomeActivity --es js64 "$(printf '%s' "$w" | base64 -w0)" >/dev/null
  t0=$(date +%s); r=""
  while [ $(( $(date +%s) - t0 )) -lt 50 ]; do sleep 1; r=$(adb -s "$ADDR" logcat -d -s HemTv </dev/null | grep -m1 "klar:"); [ -n "$r" ] && break; done
  s=$(( $(date +%s) - t0 ))
  sleep 3; adb -s "$ADDR" exec-out screencap -p > "$DIR/$(echo "$name" | tr ' /' '__').png" 2>/dev/null
  case "$r" in *lyckades*) ok=$((ok+1)); printf '  \033[32m✓\033[0m %-22s %2ss\n' "$name" "$s"; echo "OK|$name|${s}s" >> "$R";;
    *) fel=$((fel+1)); printf '  \033[31m✗\033[0m %-22s %s\n' "$name" "${r##*klar: }"; echo "FEL|$name|${r##*klar: }" >> "$R";; esac
done < <(curl -fsSL "$URL?t=$RANDOM")
A input keyevent 3
echo; echo "Fungerar: $ok   Fel: $fel"
echo "Bilderna och resultat.txt ligger i Download/kanaler_test_$NAME"
