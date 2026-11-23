#!/data/data/com.termux/files/usr/bin/bash
# Gör boxen snabbare, slår på integritetsskyddet och kontrollerar skärmsläckaren.
#   bash <(curl -fsSL https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/snabb.sh)
RAW=https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv
[ -f ~/.tvconf ] && . ~/.tvconf
adb connect "$ADDR" >/dev/null 2>&1
adb -s "$ADDR" get-state >/dev/null 2>&1 || { printf '\033[31mInte ansluten till %s. Kör: tvbox NAMN IP:PORT\033[0m\n' "$ADDR"; exit 1; }
S(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
ok(){ printf '  \033[32m✓\033[0m %s\n' "$*"; }
no(){ printf '  \033[31m✗\033[0m %s\n' "$*"; }
PKG=se.cruzi.tvhem
echo "Box: $ADDR ($(S getprop ro.product.model))"

echo; echo "Skärmsläckare"
S settings put secure screensaver_components $PKG/.ParticleDream
S settings put secure screensaver_default_component $PKG/.ParticleDream
S settings put secure screensaver_enabled 1
S settings put secure screensaver_activate_on_sleep 1
S settings put secure screensaver_activate_on_dock 1
t=$(S settings get system screen_off_timeout)
if [ -z "$t" ] || [ "$t" = null ] || [ "$t" -gt 1800000 ] 2>/dev/null || [ "$t" -lt 300000 ] 2>/dev/null; then S settings put system screen_off_timeout 600000; t=600000; fi
ok "Startar efter $((t/60000)) min, även inne i appar"
S am start -n com.android.systemui/.Somnambulator >/dev/null; sleep 4
if S dumpsys dreams | grep -q ParticleDream; then ok "Provkörd: partiklarna visas"; else no "Kunde inte provköra – den startar ändå av sig själv efter $((t/60000)) min"; fi
S input keyevent KEYCODE_WAKEUP >/dev/null; S am start -n $PKG/.HomeActivity >/dev/null

echo; echo "Snabbare"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do S settings put global $k 0.5; done
ok "Animationer dubbelt så snabba"
S settings put global wifi_scan_always_enabled 0; S settings put global ble_scan_always_enabled 0
S settings put global network_recommendations_enabled 0
ok "Ingen ständig wifi/bluetooth-sökning i bakgrunden"
S pm trim-caches 64G >/dev/null; ok "Cache rensad"
echo "  Optimerar apparna (kan ta några minuter)…"
S cmd package bg-dexopt-job >/dev/null && ok "Apparna optimerade" || no "Optimeringen hoppades över"

echo; echo "Reklam och avlyssning"
curl -fsSL "$RAW/integritet.sh?v=$RANDOM" | bash | sed '1,2d;/Ångra allt/d;/Kör hemtest/d'

echo; printf '\033[32mKlart.\033[0m Starta om boxen en gång så får allt full effekt.\n'
