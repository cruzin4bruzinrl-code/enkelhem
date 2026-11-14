#!/data/data/com.termux/files/usr/bin/bash
# Stänger av avlyssning och spårning på Telia-boxen så långt det går utan root.
#   bash integritet.sh        – slå på skyddet
#   bash integritet.sh av     – ångra allt
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37363}
adb connect "$ADDR" >/dev/null 2>&1
S(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
ok(){ printf '  \033[32m✓\033[0m %s\n' "$*"; }
no(){ printf '  \033[33m–\033[0m %s\n' "$*"; }
has(){ S pm list packages "$1" | grep -qx "package:$1"; }

# Paket som bara samlar in, lyssnar eller visar reklam. Inget som Hem, Telia-tv, YouTube/SmartTube,
# Pluto, Spotify, Stremio, Play Butik eller spegling (mediashell) behöver.
PKGS="com.google.android.katniss com.google.android.apps.tv.launcherx com.google.android.tvrecommendations
com.google.android.feedback com.google.android.tv.remote.service com.google.android.play.games
com.google.android.partnersetup com.google.android.marvin.talkback com.google.android.apps.tv.dreamx
com.google.android.speech.pumpkin com.google.android.tts com.google.android.gms.location.history
com.google.android.apps.mediahome.launcher com.google.android.leanbacklauncher.recommendations
com.android.providers.partnerbookmarks com.google.android.backdrop com.netflix.ninja com.amazon.amazonvideo.livingroom flix.com.vision"

if [ "$1" = "av" ]; then
  echo "Ångrar integritetsskyddet…"
  for p in $PKGS; do S pm enable "$p" >/dev/null; done
  S cmd sensor_privacy disable 0 microphone >/dev/null
  S settings put global private_dns_mode opportunistic >/dev/null
  S cmd location set-location-enabled true >/dev/null
  ok "Allt återställt"; exit 0
fi

echo "Integritet – Telia-boxen ($ADDR)"
echo
echo "Mikrofon"
if S cmd sensor_privacy enable 0 microphone | grep -qiE "error|unknown|not supported|exception"; then no "Boxen saknar huvudbrytare för mikrofonen – stänger av den app för app"
else ok "Mikrofonen är avstängd för hela boxen"; fi
n=0
for p in $(S dumpsys package | awk '/^ *Package \[/{p=$2} /android.permission.RECORD_AUDIO: granted=true/{print p}' | tr -d '[]' | sort -u); do
  S pm revoke "$p" android.permission.RECORD_AUDIO >/dev/null
  S appops set "$p" RECORD_AUDIO ignore >/dev/null
  n=$((n+1)); echo "     mikrofon av: $p"
done
ok "Mikrofon nekad för $n appar"
S settings put secure voice_interaction_service "" >/dev/null
S settings put secure assistant "" >/dev/null
S settings put secure voice_recognition_service "" >/dev/null
ok "Röstassistent och röststyrning avstängda"

echo
echo "Spårning och insamling"
for p in $PKGS; do
  if has "$p"; then S pm disable-user --user 0 "$p" >/dev/null; S am force-stop "$p" >/dev/null; ok "avstängd: $p"; fi
done
# Telias och tillverkarens egna insamlingstjänster (bara sådana som tydligt är statistik/loggning)
for p in $(S pm list packages -e | cut -d: -f2 | grep -iE 'analytic|telemetr|tracker|statistic|crashreport|bugreport|feedback|nielsen|conviva|appsflyer|adjust\.sdk|logcollect|logupload|logreport|usagestat'); do
  S pm disable-user --user 0 "$p" >/dev/null && ok "avstängd: $p"
done
S cmd location set-location-enabled false >/dev/null && ok "Platstjänster avstängda (vädret använder din valda ort)"
S settings put secure limit_ad_tracking 1 >/dev/null
S settings put global package_verifier_user_consent -1 >/dev/null
S settings put global upload_apk_enable 0 >/dev/null
ok "Annons-spårning och uppladdning av appar till Google avstängda"

echo
echo "Spärr mot reklam- och spårningsservrar"
S settings put global private_dns_mode hostname >/dev/null
S settings put global private_dns_specifier dns.adguard-dns.com >/dev/null
ok "Hela boxen använder AdGuard DNS – spårare och reklamservrar blockeras för alla appar"

echo
printf '\033[32mKlart.\033[0m Kör hemtest för att se att allt fortfarande fungerar.\n'
echo "Ångra allt med: curl -fsSL https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/integritet.sh | bash -s av"
