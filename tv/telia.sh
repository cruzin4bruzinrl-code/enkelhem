#!/data/data/com.termux/files/usr/bin/bash
# Tillbaka till Telias egen meny – men boxen förblir snabb och utan reklam, spårning och avlyssning.
#   bash <(curl -fsSL https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/telia.sh)
RAW=https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv
[ -f ~/.tvconf ] && . ~/.tvconf
adb connect "$ADDR" >/dev/null 2>&1
adb -s "$ADDR" get-state >/dev/null 2>&1 || { printf '\033[31mInte ansluten till %s. Kör: tvbox NAMN IP:PORT\033[0m\n' "$ADDR"; exit 1; }
S(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
ok(){ printf '  \033[32m✓\033[0m %s\n' "$*"; }
no(){ printf '  \033[31m✗\033[0m %s\n' "$*"; }
PKG=se.cruzi.tvhem
echo "Box: $ADDR ($(S getprop ro.product.model))"

echo; echo "Telias meny tillbaka"
# Telia's own home screen (whatever launcher is on the box besides Hem)
TL=$(S cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep / | grep -v "$PKG" | grep -vi "settings\|fallback\|launcherx" | head -1 | tr -d ' ')
[ -z "$TL" ] && TL=$(S pm list packages | grep -oE 'se\.telia[^ ]*launcher[^ ]*' | head -1)
TP=${TL%%/*}
if [ -z "$TP" ]; then no "Hittade inte Telias meny på boxen"; exit 1; fi
S pm enable "$TP" >/dev/null 2>&1
# Hem lets go: no HOME capture, no guard, no screensaver of its own
S settings put secure enabled_accessibility_services "$(S settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v "$PKG" | paste -sd: -)" >/dev/null
S cmd role remove-role-holder android.app.role.HOME $PKG 0 >/dev/null 2>&1
S cmd role add-role-holder android.app.role.HOME "$TP" 0 >/dev/null 2>&1
[ "$TL" != "$TP" ] && S cmd package set-home-activity "$TL" >/dev/null 2>&1
for p in $(S ps -A -o PID,ARGS 2>/dev/null | grep -E 'Guard|tvhem' | grep -v grep | awk '{print $1}'); do S kill $p >/dev/null 2>&1; done
case "$(S settings get secure screensaver_components)" in *$PKG*) S settings delete secure screensaver_components >/dev/null; S settings delete secure screensaver_default_component >/dev/null;; esac
S pm uninstall $PKG >/dev/null 2>&1
S settings delete global hem_guard >/dev/null 2>&1; S settings delete global hem_stop >/dev/null 2>&1; S settings delete global hem_kanaler >/dev/null 2>&1
S am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 2
if S pm path $PKG | grep -q apk; then no "Hem kunde inte tas bort"; else ok "Hem borttagen"; fi
if S cmd role get-role-holders android.app.role.HOME | grep -q "$TP"; then ok "HOME-knappen går till Telias meny ($TP)"; else no "HOME-rollen sattes inte – tryck HOME och välj Telia, 'Alltid'"; fi

echo; echo "Snabb och utan reklam, spårning och avlyssning (behålls)"
curl -fsSL "$RAW/snabb.sh?v=$RANDOM" | bash | sed '1d;/Starta om boxen/d'
echo; printf '\033[32mKlart.\033[0m Starta om boxen en gång.\n'
