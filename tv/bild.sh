#!/data/data/com.termux/files/usr/bin/bash
# Bästa bild och ljud boxen och tv:n klarar: högsta upplösning, rätt bildfrekvens för varje kanal/film, HDR och surroundljud.
#   bash <(curl -fsSL https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/bild.sh)
[ -f ~/.tvconf ] && . ~/.tvconf
adb connect "$ADDR" >/dev/null 2>&1
adb -s "$ADDR" get-state >/dev/null 2>&1 || { printf '\033[31mInte ansluten till %s. Kör: tvbox NAMN IP:PORT\033[0m\n' "$ADDR"; exit 1; }
S(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
ok(){ printf '  \033[32m✓\033[0m %s\n' "$*"; }
no(){ printf '  \033[33m!\033[0m %s\n' "$*"; }
SDK=$(S getprop ro.build.version.sdk)
echo "Box: $ADDR ($(S getprop ro.product.model), Android $(S getprop ro.build.version.release))"

echo; echo "Bild"
D=$(S dumpsys display)
MODES=$(echo "$D" | grep -oE 'id=[0-9]+, width=[0-9]+, height=[0-9]+, fps=[0-9.]+' | sort -u)
CUR=$(echo "$D" | grep -m1 -oE 'mActiveModeId=[0-9]+' | cut -d= -f2)
if [ -z "$MODES" ]; then no "Boxen visar inte vilka lägen tv:n klarar"; else
  echo "$MODES" | awk -F'[=, ]+' '{printf "  tv:n klarar %sx%s %.0f Hz\n",$4,$6,$8}' | sort -u -t' ' -k4,4 | head -12
  # best: most pixels, then the highest refresh up to 60 Hz
  BEST=$(echo "$MODES" | awk -F'[=, ]+' '$8<=61{print $4*$6, $8, $2, $4, $6}' | sort -k1,1n -k2,2n | tail -1)
  set -- $BEST; BID=$3; BW=$4; BH=$5; BF=$2
  CW=$(echo "$MODES" | grep "id=$CUR," | awk -F'[=, ]+' '{print $4"x"$6" "int($8+.5)" Hz"}')
  echo "  nu: ${CW:-okänt}"
  if [ "$BID" = "$CUR" ]; then ok "Redan bästa läget: ${BW}x${BH} $(printf %.0f $BF) Hz"
  else
    S cmd display set-user-preferred-display-mode $BW $BH $BF >/dev/null 2>&1
    sleep 3; NOW=$(S dumpsys display | grep -m1 -oE 'mActiveModeId=[0-9]+' | cut -d= -f2)
    if [ "$NOW" = "$BID" ]; then ok "Bytt till ${BW}x${BH} $(printf %.0f $BF) Hz"
    else no "Kunde inte byta själv – välj ${BW}x${BH} under Inställningar → Skärm & ljud → Upplösning"; fi
  fi
fi
# switch the TV to the video's own frame rate (25/50 Hz tv, 24 Hz film) = smooth movement, no judder
S settings put secure match_content_frame_rate 1 >/dev/null 2>&1
S settings put global match_content_frame_rate 1 >/dev/null 2>&1
ok "Bildfrekvensen följer kanalen/filmen (mjukare rörelser)"
HDR=$(echo "$D" | grep -m1 -oE 'mSupportedHdrTypes=\[[^]]*\]|supportedHdrTypes=\[[^]]*\]')
[ -n "$HDR" ] && ok "HDR: ${HDR#*=}" || no "Ingen HDR rapporterad av tv:n"
S settings put global user_disabled_hdr_formats "" >/dev/null 2>&1
S settings put global are_user_disabled_hdr_formats_allowed 1 >/dev/null 2>&1
ok "Alla HDR-format som tv:n klarar är påslagna"

echo; echo "Ljud"
S settings put global encoded_surround_output 0 >/dev/null 2>&1
ok "Surround (Dolby/DTS) skickas när tv:n eller förstärkaren klarar det"
S settings delete global encoded_surround_output_enabled_formats >/dev/null 2>&1
AUD=$(S dumpsys audio | grep -m1 -oiE 'HDMI[^,]*' )
[ -n "$AUD" ] && ok "Ljudet går via ${AUD}"
echo
printf '\033[32mKlart.\033[0m Starta om boxen en gång.\n'
echo "Tips för tv:n själv (görs med tv:ns egen kontroll): bildläge Film/Naturlig, stäng av rörelseutjämning och energisparläge."
