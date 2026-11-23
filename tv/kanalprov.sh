#!/data/data/com.termux/files/usr/bin/bash
# Startar en kanal via Hem och visar vad som hände.   kanalprov "TV4 Sportkanalen"
[ -f ~/.tvconf ] && . ~/.tvconf
adb connect "$ADDR" >/dev/null 2>&1
A(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
CH=${1:-TV4}
A input keyevent 3; sleep 2
adb -s "$ADDR" logcat -c </dev/null
w="remoteChannel(\"$CH\")"
A am start -n se.cruzi.tvhem/.HomeActivity --es js64 "$(printf '%s' "$w" | base64 -w0)" >/dev/null
echo "Startar $CH … vänta 30 s"
sleep 30
F=/storage/emulated/0/Download/kanalprov.txt
{ echo "Kanal: $CH"; echo "Vakten: $(A pgrep -f "tvhem.Gu[a]rd" | head -1)"; echo "Nu på skärmen: $(A dumpsys activity activities | grep -m1 topResumedActivity)"; A logcat -d -s HemTv; } > "$F"
adb -s "$ADDR" exec-out screencap -p > /storage/emulated/0/Download/kanalprov.png 2>/dev/null
grep -E "rätt kanal|hittade inte|klar:|skriver|Vakten" "$F" | tail -6
echo "Klart. Skicka Download/kanalprov.txt och kanalprov.png till Claude."
