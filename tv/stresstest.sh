#!/data/data/com.termux/files/usr/bin/bash
# Stresstest av launchern: prestanda, minne, HOME-knappen, kanaler, Telia-styrningen och loggar.
# Allt hamnar i Download/hemtest_DATUM/rapport.txt (+ skärmdumpar).
[ -f ~/.tvconf ] && . ~/.tvconf; ADDR=${ADDR:-192.168.0.116:37363}
PKG=se.cruzi.tvhem
RAW=https://raw.githubusercontent.com/cruzin4bruzinrl-code/enkelhem/main/tv
DIR=/storage/emulated/0/Download/hemtest_$(date +%m%d_%H%M)
mkdir -p "$DIR"; R=$DIR/rapport.txt; : > "$R"
command -v termux-wake-lock >/dev/null && termux-wake-lock

a(){ adb "$@" </dev/null; }
S(){ adb shell "$@" </dev/null 2>&1 | tr -d '\r'; }
log(){ printf '%s\n' "$*" >> "$R"; }
sec(){ printf '\n\033[36m== %s\033[0m\n' "$*"; log ""; log "===== $* ====="; }
say(){ printf '  %s\n' "$*"; log "$*"; }
ms(){ date +%s%3N; }
shot(){ a exec-out screencap -p > "$DIR/$1.png" 2>/dev/null; }
top_act(){ S dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -oE "[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+" | head -1; }
home(){ S input keyevent KEYCODE_HOME >/dev/null; }
keys(){ S input keyevent "$@" >/dev/null; }

# Kör JavaScript i launchern och hämta svaret (kräver TV-appen från 13 nov eller senare)
js_send(){ JID=$(ms)$RANDOM; local w="Promise.resolve().then(()=>($1)).then(r=>console.log('HEMTEST $JID '+JSON.stringify(r)),e=>console.log('HEMTEST $JID ERR '+e))"
  a shell am start -n $PKG/.HomeActivity --es js64 "$(printf '%s' "$w" | base64 -w0)" >/dev/null 2>&1; }
js_get(){ local id=$1 to=$2 r
  for i in $(seq 1 "$to"); do sleep 1; r=$(a logcat -d -s HemJs | tr -d '\r' | grep -m1 "HEMTEST $id ")
    [ -n "$r" ] && { r=${r#*HEMTEST $id }; echo "${r% @*}"; return 0; }; done; echo "inget svar"; return 1; }
js(){ js_send "$1"; js_get "$JID" "${2:-6}"; }

FPS='new Promise(res=>{const t=[];let s=performance.now(),l=s;function f(n){t.push(n-l);l=n;if(n-s<DUR)requestAnimationFrame(f);else{t.shift();t.sort((a,b)=>a-b);const v=t.reduce((a,b)=>a+b,0)/t.length;res({fps:+(1000/v).toFixed(1),p50:+t[t.length>>1].toFixed(1),p95:+t[Math.floor(t.length*.95)].toFixed(1),max:+t[t.length-1].toFixed(0),over50ms:t.filter(x=>x>50).length,over100ms:t.filter(x=>x>100).length,frames:t.length})}}requestAnimationFrame(f)})'
fps(){ echo "${FPS//DUR/$1}"; }
SNAP='(()=>{const ls={};let tot=0;for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i),v=localStorage.getItem(k)||"";ls[k]=Math.round(v.length/1024)+"KB";tot+=v.length}const ua=navigator.userAgent.match(/Chrome\/[\d.]+/);return{ver:typeof VERSION!="undefined"?VERSION:"?",zone:typeof zone!="undefined"?zone:"?",apps:typeof APPS!="undefined"?APPS.length:"?",live:typeof LIVE!="undefined"?LIVE.map(t=>t.tab+":"+t.ch.length).join(","):"?",dom:document.getElementsByTagName("*").length,anim:document.getAnimations?document.getAnimations().length:"?",vw:innerWidth,vh:innerHeight,dpr:devicePixelRatio,chrome:ua?ua[0]:"?",heap:performance.memory?Math.round(performance.memory.usedJSHeapSize/1048576)+"MB":"?",lsTot:Math.round(tot/1024)+"KB",ls,bridge:window.OceanBridge?Object.keys(OceanBridge).join(" "):"saknas"}})()'
mem(){ S dumpsys meminfo $PKG | grep -m1 -E "TOTAL PSS|TOTAL:" | awk '{print $3" kB (PSS)"}'; }
render_mem(){ S dumpsys meminfo | grep -iE "sandboxed_process|webview" | head -3 | sed 's/^ *//'; }
cpu(){ S top -b -n 2 -d 3 -m 12 -o PID,%CPU,RES,NAME | tail -13; }

echo "Stresstest av Hem – tar ca 10 minuter. Rör inte fjärren."
a connect "$ADDR" >/dev/null 2>&1
a devices | grep -q "^$ADDR[[:space:]]*device" || { echo "Kunde inte ansluta till $ADDR. Kör 'tv' först."; exit 1; }
log "Hem stresstest $(date '+%Y-%m-%d %H:%M')  ($ADDR)"

sec "Enhet"
say "Modell: $(S getprop ro.product.manufacturer) $(S getprop ro.product.model) ($(S getprop ro.product.device))"
say "Android: $(S getprop ro.build.version.release) (SDK $(S getprop ro.build.version.sdk)), bygg $(S getprop ro.build.display.id)"
say "Chip: $(S getprop ro.board.platform) $(S getprop ro.hardware), kärnor: $(S nproc)"
say "Skärm: $(S wm size | tail -1 | awk '{print $NF}'), densitet $(S wm density | tail -1 | awk '{print $NF}')"
say "$(S cat /proc/meminfo | awk '/MemTotal/{t=$2}/MemAvailable/{f=$2}END{printf "RAM: %.2f GB totalt, %.2f GB ledigt",t/1048576,f/1048576}')"
say "Lagring: $(S df -h /data | tail -1 | awk '{print $4" ledigt av "$2}')"
say "WebView: $(S dumpsys webviewupdate | grep -m1 -E 'Current WebView package' | sed 's/.*(//;s/)//')"
say "Hem-appen: $(S dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
say "Telia-launcher: $(S dumpsys package se.telia.androidtv.launcher | grep -m1 -E 'enabled=' | grep -oE 'enabled=[0-9]')"
say "Temperatur: $(S dumpsys thermalservice | grep -m3 -E 'Temperature\{' | grep -oE 'mValue=[0-9.]+|mName=[A-Za-z0-9_-]+' | paste -sd' ')"
say "Igång sedan: $(S uptime | sed 's/^ *//')"

sec "Inställningar"
say "HOME-roll: $(S cmd role get-role-holders android.app.role.HOME)"
say "Tillgänglighet: $(S settings get secure enabled_accessibility_services)"
say "Notislyssnare: $(S settings get secure enabled_notification_listeners | tr ':' '\n' | grep -c tvhem) av Hem"
say "HDMI-CEC: control=$(S settings get global hdmi_control_enabled) autoAv=$(S settings get global hdmi_control_auto_device_off_enabled)"
say "Vila: sleep=$(S settings get secure sleep_timeout) attentive=$(S settings get secure attentive_timeout) skärm=$(S settings get system screen_off_timeout)"
say "Animationer: $(S settings get global window_animation_scale)/$(S settings get global transition_animation_scale)/$(S settings get global animator_duration_scale)"
say "Avstängda paket: $(S pm list packages -d | cut -d: -f2 | paste -sd' ')"

sec "Start från annan app"
for n in 1 2 3; do
  S am start -a android.settings.SETTINGS >/dev/null; sleep 3
  t=$(S am start -W -n $PKG/.HomeActivity | grep -E "TotalTime|WaitTime" | awk '{print $2}' | paste -sd/)
  say "Start $n: ${t:-?} ms (total/vänt)"
done
sleep 3

sec "Testkanal till launchern"
a logcat -c
ok=$(js '1+1' 6)
if [ "$ok" != "2" ]; then
  say "Launchern svarar inte på test – uppdaterar TV-appen"
  command -v tvapp >/dev/null && tvapp </dev/null >/dev/null 2>&1
  sleep 8; home; sleep 4; a logcat -c; ok=$(js '1+1' 8)
fi
if [ "$ok" = "2" ]; then JSOK=1; say "OK"; else JSOK=0; say "Svarar inte ($ok) – JS-delarna hoppas över. Vänta 5 min och kör igen."; fi

sec "Läge i launchern"
home; sleep 3; shot 01_hem
[ $JSOK = 1 ] && say "$(js "$SNAP" 6)"
say "Design på GitHub: $(curl -fsSL "$RAW/index.html?t=$RANDOM" | grep -m1 -oE 'VERSION="[0-9.]+"')"
[ $JSOK = 1 ] && say "Hämtning av kanaler.txt: $(js '(async()=>{const t=performance.now();const r=await fetch(KSRC+"?t="+Date.now(),{cache:"no-store"});await r.text();return r.status+" på "+Math.round(performance.now()-t)+" ms"})()' 12)"
say "Minne app: $(mem)"
say "Minne WebView: $(render_mem | paste -sd'; ')"

sec "CPU i vila (3 s)"
cpu | while read -r l; do say "$l"; done

sec "Bildflyt"
# rotation på startsidan: mäter både i vila och under navigering
if [ $JSOK = 1 ]; then
  say "Vila 5 s: $(js "$(fps 5000)" 9)"
fi
nav(){ # namn, js för att öppna, tangentsekvens, stäng-js
  local name=$1 open=$2 seq=$3 close=$4 id r
  [ $JSOK = 1 ] && [ -n "$open" ] && { js "$open" 4 >/dev/null; sleep 1.5; }
  S dumpsys gfxinfo $PKG reset >/dev/null
  [ $JSOK = 1 ] && { js_send "$(fps 9000)"; id=$JID; }
  for k in $seq; do keys $k; done
  sleep 1; shot "02_$name"
  [ $JSOK = 1 ] && r=$(js_get "$id" 12)
  local g; g=$(S dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|90th|99th" | sed 's/^ *//' | paste -sd'; ')
  say "$name: JS ${r:--}"
  say "   ramar: $g"
  [ $JSOK = 1 ] && [ -n "$close" ] && { js "$close" 4 >/dev/null; sleep 1; }
}
R6="22 22 22 22 22 22 21 21 21 21 21 21"
nav startsida "" "$R6 20 22 22 22 21 21 21 19 21 21 20" 'zone=="panel"&&closePanel()'
nav snabbpanel "openPanel()" "20 20 20 20 20 20 20 20 19 19 19 19 19 19 19 19" "closePanel()"
nav allaappar "openGrid()" "22 22 22 22 20 20 21 21 21 21 20 22 22 22 19 19 19" "closeGrid()"
nav livetv "openLive()" "22 22 22 22 22 22 21 21 21 21 21 21 19 22 20 22 22 22" "closeLive()"

sec "Hamring (300 snabba tangenter)"
pid1=$(S pidof $PKG); m1=$(mem)
S dumpsys gfxinfo $PKG reset >/dev/null
t0=$(ms)
for i in 1 2 3 4 5; do keys 22 22 22 22 22 22 22 22 22 22 21 21 21 21 21 21 21 21 21 21 20 20 20 19 19 19 22 22 21 21 20 20 19 19 22 21 22 21 22 21 20 19 20 19 22 22 21 21 22 21 20 19 22 21 20 19 22 21 22 21; done
say "Tid: $(( ($(ms)-t0) )) ms"
sleep 2; shot 03_efter_hamring
pid2=$(S pidof $PKG)
say "Process: före $pid1, efter $pid2 $( [ "$pid1" = "$pid2" ] && echo '(ingen krasch)' || echo '(STARTADE OM!)')"
say "Minne: före $m1, efter $(mem)"
say "Ramar: $(S dumpsys gfxinfo $PKG | grep -E "Total frames|Janky frames|90th|99th" | sed 's/^ *//' | paste -sd'; ')"
[ $JSOK = 1 ] && say "Läge efter: $(js '({zone,dom:document.getElementsByTagName("*").length,heap:performance.memory?Math.round(performance.memory.usedJSHeapSize/1048576)+"MB":"?"})' 6)"

sec "HOME-knappen"
a logcat -c
hok=0; htot=0
for app in "am start -a android.settings.SETTINGS" "monkey -p com.android.vending -c android.intent.category.LEANBACK_LAUNCHER 1" "am start -a android.intent.action.VIEW -d https://pluto.tv/live-tv/top-gear-se -p tv.pluto.android"; do
  S $app >/dev/null; sleep 6
  before=$(top_act); t0=$(ms); home
  for i in 1 2 3 4 5 6 7 8 9 10; do sleep 0.3; cur=$(top_act); [[ "$cur" == $PKG* ]] && break; done
  htot=$((htot+1)); [[ "$cur" == $PKG* ]] && hok=$((hok+1))
  say "Från ${before:-?}: $([[ "$cur" == $PKG* ]] && echo "OK på $(( $(ms)-t0 )) ms" || echo "FEL, hamnade i $cur")"
done
tl=$(a logcat -d | tr -d '\r' | grep -E "START.*se\.telia\.androidtv\.launcher" | wc -l)
say "Resultat: $hok av $htot. Telias launcher startades $tl gånger under testet."

sec "SVT (inbyggd spelare)"
curl -fsSL "$RAW/kanaler.txt?t=$RANDOM" | grep -v '^#' > "$DIR/kanaler.txt"
while IFS='|' read -r kind name url tab desc col; do
  [ "$kind" = svt ] || continue
  a logcat -c; t0=$(ms)
  S am start -n $PKG/.PlayerActivity --es kind "$kind" --es url "'$url'" --es title "'$name'" >/dev/null
  r=""; for i in $(seq 1 20); do sleep 1; r=$(a logcat -d -s HemPlay | tr -d '\r' | grep -E "spelar|fel " | tail -1); [ -n "$r" ] && break; done
  case "$r" in *spelar*) say "$name: OK på $(( $(ms)-t0 )) ms";; *fel*) say "$name: FEL ${r##*fel }";; *) say "$name: inget svar";; esac
done < "$DIR/kanaler.txt"
sleep 2; shot 04_svt; home; sleep 2

sec "Pluto TV"
n=0
while IFS='|' read -r kind name url tab desc col; do
  [ "$kind" = pluto ] || continue
  n=$((n+1))
  S am start -a android.intent.action.VIEW -d "https://pluto.tv/live-tv/$url" -p tv.pluto.android >/dev/null
  sleep 10
  act=$(top_act)
  txt=$(timeout 10 adb shell "uiautomator dump /sdcard/hemui.xml >/dev/null 2>&1 && cat /sdcard/hemui.xml" </dev/null 2>/dev/null | tr -d '\r' | grep -oE 'text="[^"]+"' | sed 's/text="//;s/"$//' | head -6 | paste -sd'|')
  f=$(printf '05_pluto_%02d' $n); shot "$f"
  say "$name ($url): ${act:-?}  text: ${txt:-–}  bild: $f.png"
done < "$DIR/kanaler.txt"
home; sleep 3

sec "Telia-kanaler"
if [ $JSOK = 1 ]; then
  while IFS='|' read -r kind name url tab desc col; do
    [ "$kind" = telia ] || continue
    home; sleep 3; a logcat -c
    js_send "OceanBridge.openTelia(\"Tv:$url\")" >/dev/null
    sleep 12; s1=$(top_act); shot "06_telia_${url}_12s"
    sleep 10; s2=$(top_act); shot "06_telia_${url}_22s"
    say "$name: efter 12 s ${s1:-?}, efter 22 s ${s2:-?}"
    a logcat -d -s HemTv | tr -d '\r' | grep -v '^-' | tail -40 | sed 's/^/     /' >> "$R"
  done < "$DIR/kanaler.txt"
  home; sleep 2
else say "Hoppas över (testkanalen svarade inte)"; fi

sec "Slutläge"
home; sleep 3; shot 07_slut
say "Minne app: $(mem)"
say "Minne WebView: $(render_mem | paste -sd'; ')"
[ $JSOK = 1 ] && say "$(js "$SNAP" 6)"

sec "Fel i loggarna"
a logcat -d -b crash | tr -d '\r' | tail -40 >> "$R"
a logcat -d | tr -d '\r' | grep -E "HemJs.*(ERROR|WARNING)|AndroidRuntime|ANR in|FATAL|tvhem.*(Exception|died)" | grep -v HEMTEST | tail -40 >> "$R"
S dumpsys activity processes | grep -A2 -i "tvhem" | grep -iE "crash|anr" | head -5 >> "$R"

command -v termux-wake-unlock >/dev/null && termux-wake-unlock
echo
printf '\033[32mKlart!\033[0m Rapporten finns i Download/%s/rapport.txt\n' "$(basename "$DIR")"
echo "Skicka rapport.txt till Claude (plus gärna bilderna 02_*.png)."
