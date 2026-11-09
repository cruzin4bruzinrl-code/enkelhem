#!/data/data/com.termux/files/usr/bin/bash
# Spelar varje kanal på TV:n en stund och kontrollerar att bilden startar.
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37363}
adb connect "$ADDR" >/dev/null 2>&1
RES=$PREFIX/tmp/kanaltest.txt; : > "$RES"
curl -fsSL "https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/kanaler.txt?t=$RANDOM" | grep -v '^#' | while IFS='|' read -r kind name url; do
  [ -z "$name" ] && continue
  printf '%-18s ' "$name"
  adb logcat -c
  adb shell am start -n se.cruzi.tvhem/.PlayerActivity --es kind "$kind" --es url "'$url'" --es title "'$name'" >/dev/null 2>&1
  r=""
  for i in $(seq 1 22); do
    sleep 1
    r=$(adb logcat -d -s HemPlay | grep -E "spelar|fel " | tail -1)
    [ -n "$r" ] && break
  done
  case "$r" in
    *spelar*) echo "✓ fungerar"; echo "OK|$name" >> "$RES";;
    *fel*) echo "✗ ${r#*: fel $name: }"; echo "FEL|$name|${r#*: fel $name: }" >> "$RES";;
    *) echo "✗ inget svar"; echo "FEL|$name|inget svar" >> "$RES";;
  esac
done
adb shell input keyevent KEYCODE_BACK
echo; echo "Klart. Skicka en bild på listan."
