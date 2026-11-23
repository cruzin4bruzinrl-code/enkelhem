#!/data/data/com.termux/files/usr/bin/bash
# Öppnar Telias tv-guide via Hem och fotograferar kanallistan sida för sida, så att boxens egna kanaler kan läggas in i Hem.
[ -f ~/.tvconf ] && . ~/.tvconf
adb connect "$ADDR" >/dev/null 2>&1
A(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
NAME=$(A settings get global hem_kanaler); [ "$NAME" = null ] && NAME=box
DIR=/storage/emulated/0/Download/kanaler_$NAME; rm -rf "$DIR"; mkdir -p "$DIR"
echo "Öppnar Telias tv-guide… rör inte fjärren."
w='OceanBridge.openTelia("Tv")'
A am start -n se.cruzi.tvhem/.HomeActivity --es js64 "$(printf '%s' "$w" | base64 -w0)" >/dev/null
sleep 14
for i in $(seq 1 25); do A input keyevent 19; done   # överst i listan
sleep 2
for p in $(seq 1 14); do
  adb -s "$ADDR" exec-out screencap -p > "$DIR/sida_$(printf %02d $p).png" 2>/dev/null
  echo "  sida $p"
  for i in 1 2 3 4 5 6; do A input keyevent 20; sleep 0.15; done
  sleep 1.2
  if [ $p -gt 1 ] && cmp -s "$DIR/sida_$(printf %02d $p).png" "$DIR/sida_$(printf %02d $((p-1))).png"; then rm "$DIR/sida_$(printf %02d $p).png"; break; fi
done
A input keyevent 3
echo "Klart. Bilderna ligger i Download/kanaler_$NAME – skicka dem till Claude."
