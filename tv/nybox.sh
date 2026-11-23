#!/data/data/com.termux/files/usr/bin/bash
# Sätter upp Hem på en ny Telia-box (t.ex. hos föräldrarna) och sparar den som en egen box i telefonen.
#   bash <(curl -fsSL https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv/nybox.sh)
RAW=https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv
APK=https://github.com/cruzin4bruzinrl-code/enkelhem/releases/download/app-latest/hem.apk
PKG=se.cruzi.tvhem
g(){ printf '\033[32m✓ %s\033[0m\n' "$*"; }
r(){ printf '\033[31m✗ %s\033[0m\n' "$*"; }
q(){ printf '\033[36m%s\033[0m ' "$*"; }
mkdir -p ~/.tvboxes
# din nuvarande box sparas som "hemma" innan något ändras
[ -f ~/.tvconf ] && [ ! -f ~/.tvboxes/hemma ] && grep -q ADDR ~/.tvconf && cp ~/.tvconf ~/.tvboxes/hemma

# ---- kommandot tvbox: byt mellan boxarna ----
cat > $PREFIX/bin/tvbox <<'X'
#!/data/data/com.termux/files/usr/bin/bash
# tvbox          – visa boxarna
# tvbox NAMN     – använd den boxen (tvapp, hemtest, hemvakt … gäller sedan den)
# tvbox NAMN IP:PORT – ny port efter att trådlös felsökning slagits på igen
cd ~/.tvboxes 2>/dev/null || { echo "Inga boxar sparade."; exit 1; }
cur=$(grep -s ADDR ~/.tvconf | cut -d= -f2)
if [ -z "$1" ]; then for f in *; do a=$(cut -d= -f2 "$f"); [ "$a" = "$cur" ] && m="  ← används" || m=""; echo "$f  $a$m"; done; exit 0; fi
[ -f "$1" ] || { echo "Ingen box som heter $1"; exit 1; }
[ -n "$2" ] && echo "ADDR=$2" > "$1"
cp "$1" ~/.tvconf
adb disconnect >/dev/null 2>&1
. ~/.tvconf; adb connect "$ADDR" | tail -1
X
chmod +x $PREFIX/bin/tvbox

echo
echo "Ny box för Hem"
echo "1. På tv:n: Inställningar → Enhetsinställningar → Utvecklaralternativ → Trådlös felsökning → slå på."
echo "2. Välj 'Parkoppla enhet med parkopplingskod'."
echo "   Telefonen måste vara på samma wifi som boxen."
echo
q "Vad ska boxen heta (t.ex. foraldrar)?"; read -r NAME </dev/tty
NAME=$(echo "${NAME:-foraldrar}" | tr 'A-ZÅÄÖ ' 'a-zaao_' | tr -cd 'a-z0-9_')
q "IP-adress och port i parkopplingsrutan (t.ex. 192.168.1.20:41234):"; read -r PAIR </dev/tty
q "Parkopplingskoden (6 siffror):"; read -r CODE </dev/tty
adb disconnect >/dev/null 2>&1
if adb pair "$PAIR" "$CODE" </dev/null 2>&1 | grep -qi success; then g "Parkopplad"; else r "Parkopplingen misslyckades – kontrollera adress, port och kod och kör igen."; exit 1; fi
IP=${PAIR%%:*}
echo "3. Stäng parkopplingsrutan. Under 'Trådlös felsökning' står nu 'IP-adress och port'."
q "Porten där (bara siffrorna efter kolon):"; read -r PORT </dev/tty
ADDR="$IP:$PORT"
adb connect "$ADDR" </dev/null | tail -1
adb -s "$ADDR" get-state >/dev/null 2>&1 || { r "Kunde inte ansluta till $ADDR"; exit 1; }
echo "ADDR=$ADDR" > ~/.tvboxes/$NAME; cp ~/.tvboxes/$NAME ~/.tvconf
g "Boxen sparad som '$NAME'. Alla kommandon går nu till den."
A(){ adb -s "$ADDR" shell "$@" </dev/null 2>&1 | tr -d '\r'; }

echo "Modell: $(A getprop ro.product.model), Android $(A getprop ro.build.version.release)"

# ---- installera Hem ----
echo "Hämtar Hem…"
curl -fsSL -o $PREFIX/tmp/hem.apk "$APK" || { r "Kunde inte hämta appen"; exit 1; }
adb -s "$ADDR" install -r -g $PREFIX/tmp/hem.apk </dev/null | tail -1
A pm path $PKG | grep -q apk || { r "Appen installerades inte"; exit 1; }
g "Hem installerad"

# ---- behörigheter och roller ----
A pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
A appops set $PKG GET_USAGE_STATS allow >/dev/null
A cmd role add-role-holder android.app.role.HOME $PKG 0 >/dev/null
A cmd package set-home-activity $PKG/.HomeActivity >/dev/null
A settings put secure enabled_accessibility_services $PKG/.HomeKeyService
A settings put secure accessibility_enabled 1
A cmd notification allow_listener $PKG/.MediaListener >/dev/null
A settings put secure screensaver_components $PKG/.ParticleDream
A settings put secure screensaver_enabled 1
A settings put global hem_kanaler "$NAME"
[ -n "$(A cmd role get-role-holders android.app.role.HOME | grep $PKG)" ] && g "HOME går till Hem" || r "HOME-rollen sattes inte"
A settings get secure enabled_accessibility_services | grep -q HomeKeyService && g "HOME-knappen fångas" || r "Tillgänglighetstjänsten slogs inte på"
A am start -n $PKG/.HomeActivity >/dev/null
sleep 4

# ---- vakten och självstart ----
echo "Startar vakten…"
curl -fsSL "$RAW/vakt.sh?v=$RANDOM" | bash

# ---- integritet ----
echo
echo "Integritetsskyddet stänger av Google-assistentens mikrofon, reklam-id och spårning."
echo "OBS: då fungerar inte röstsökning med fjärrkontrollens mikrofonknapp."
q "Slå på integritetsskyddet på den här boxen? (j/n)"; read -r P </dev/tty
case "$P" in j*|J*) curl -fsSL "$RAW/integritet.sh?v=$RANDOM" | bash;; *) echo "Hoppar över.";; esac

# ---- kommandon ----
for c in hemtest:stresstest.sh hemvakt:vakt.sh; do n=${c%%:*}; f=${c#*:}
  [ -f $PREFIX/bin/$n ] || { printf '#!/data/data/com.termux/files/usr/bin/bash\ncurl -fsSL "%s/%s?v=$RANDOM" | bash\n' "$RAW" "$f" > $PREFIX/bin/$n; chmod +x $PREFIX/bin/$n; }; done
printf '#!/data/data/com.termux/files/usr/bin/bash\nbash <(curl -fsSL "%s/guide.sh?v=$RANDOM")\n' "$RAW" > $PREFIX/bin/hemguide; chmod +x $PREFIX/bin/hemguide

echo
g "Klart! Hem är installerad på '$NAME'."
echo "Nästa steg – deras Telia-kanaler: kör  hemguide  så fotograferas Telias kanallista. Skicka bilderna till Claude."
echo "Byt till din egen box sen med:  tvbox hemma 192.168.0.116:PORT"
