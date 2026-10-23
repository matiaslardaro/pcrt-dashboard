#!/data/data/com.termux/files/usr/bin/bash
# install.sh — instalador de un solo comando para el dashboard PCRT
# Uso (pegar en Termux recien instalado):
#   curl -sL https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/install.sh | bash
#
# Deja todo listo: Python, librerias, el proyecto, y que el dashboard arranque
# solo cada vez que se abre Termux (ya no hace falta Termux:Widget ni permisos
# raros). Se puede correr de nuevo sin problema: si algo ya esta, lo actualiza.

REPO_URL="https://github.com/matiaslardaro/pcrt-dashboard.git"
CARPETA="$HOME/pcrt"
LOG="$HOME/pcrt-instalacion.log"
TOTAL=6

V='\033[1;32m'; A='\033[1;33m'; R='\033[1;31m'; N='\033[0m'
paso(){ echo ""; echo -e "${A}[$1/$TOTAL]${N} $2"; }
ok(){ echo -e "      ${V}OK${N} $1"; }
falla(){
  echo ""
  echo -e "${R}ERROR: $1${N}"
  echo "  Ultimas lineas del detalle:"
  echo "  ------------------------------------------"
  tail -n 12 "$LOG" | cut -c1-120
  echo "  ------------------------------------------"
  echo "  Sacale una captura a esta pantalla y mandasela a Mati."
  echo "  (El detalle completo quedo en $LOG)"
  exit 1
}
# reintentar <que> <comando...>: hasta 3 intentos, porque la red de Termux a veces falla de a ratos
reintentar(){
  local que="$1"; shift
  for i in 1 2 3; do
    if "$@" >>"$LOG" 2>&1 < /dev/null; then return 0; fi
    [ "$i" -lt 3 ] && echo "      fallo $que, reintentando ($i/3)..." && sleep 4
  done
  return 1
}

: > "$LOG"
echo "=========================================="
echo "   PCRT - Instalador del dashboard"
echo "=========================================="
echo " Tarda unos minutos. No cierres Termux"
echo " ni apagues la pantalla hasta que termine."

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold")

# instalar_pkgs <paquetes...>: si pkg install falla, arregla lo tipico (dpkg cortado a la mitad,
# dependencias rotas, indice viejo de un mirror desactualizado -> 404) y prueba otra vez
instalar_pkgs(){
  pkg install "${APT_OPTS[@]}" "$@" && return 0
  echo ">>> fallo pkg install $*, reparando apt/dpkg y refrescando el indice"
  dpkg --force-confdef --force-confold --configure -a
  apt-get "${APT_OPTS[@]}" --fix-broken install
  apt-get update && apt-get install "${APT_OPTS[@]}" "$@"
}

paso 1 "Actualizando Termux..."
reintentar "la actualizacion" pkg update "${APT_OPTS[@]}" \
  || falla "No se pudo actualizar Termux. Revisa que el celu tenga internet y volve a pegar el comando."
reintentar "la actualizacion" pkg upgrade "${APT_OPTS[@]}" \
  || falla "No se pudo actualizar Termux. Revisa que el celu tenga internet y volve a pegar el comando."
ok "Termux al dia"

paso 2 "Instalando Python y git..."
reintentar "la instalacion" instalar_pkgs python git \
  || falla "No se pudieron instalar Python y git."
ok "Python y git instalados"

paso 3 "Bajando el dashboard PCRT..."
if [ -d "$CARPETA/.git" ]; then
  # ya estaba instalado: lo dejamos igual a la ultima version (a prueba de cambios locales)
  reintentar "la descarga" git -C "$CARPETA" fetch --quiet origin \
    && git -C "$CARPETA" reset --hard --quiet origin/main >>"$LOG" 2>&1 \
    || falla "No se pudo actualizar el dashboard."
else
  rm -rf "$CARPETA"
  reintentar "la descarga" git clone --quiet "$REPO_URL" "$CARPETA" \
    || falla "No se pudo bajar el dashboard."
fi
ok "Dashboard en $CARPETA"

paso 4 "Instalando las librerias de la telemetria (lo que mas tarda)..."
if ! reintentar "la instalacion" pip install gt-telem websockets; then
  # la libreria que descifra los datos de la PS5 a veces hay que compilarla: instalamos el compilador y probamos de nuevo
  echo "      instalando el compilador y probando de nuevo..."
  reintentar "la instalacion" instalar_pkgs clang make binutils \
    && reintentar "la instalacion" pip install gt-telem websockets \
    || falla "No se pudieron instalar las librerias de Python."
fi
python -c "import gt_telem, websockets" >>"$LOG" 2>&1 || falla "Las librerias quedaron mal instaladas."
ok "Librerias listas"

paso 5 "Haciendo que el dashboard arranque solo al abrir Termux..."
touch "$HOME/.bashrc"
# saca un bloque viejo (si se corre el instalador de nuevo) y agrega el actual
sed -i '/# >>> PCRT >>>/,/# <<< PCRT <<</d' "$HOME/.bashrc"
cat >> "$HOME/.bashrc" <<'EOF'
# >>> PCRT >>>
# Al abrir Termux arranca el dashboard PCRT. Para no arrancarlo, borrar este bloque.
if [ -z "$PCRT_ARRANCADO" ] && [ -f "$HOME/pcrt/PCRT.sh" ]; then
  export PCRT_ARRANCADO=1
  bash "$HOME/pcrt/PCRT.sh"
fi
# <<< PCRT <<<
EOF
# acceso directo para quien ya usa Termux:Widget (opcional, no hace falta)
mkdir -p "$HOME/.shortcuts"
cp "$CARPETA/PCRT.sh" "$HOME/.shortcuts/PCRT.sh" && chmod +x "$HOME/.shortcuts/PCRT.sh"
ok "Listo: cada vez que abras Termux, arranca solo"

paso 6 "Pidiendo que Android no cierre Termux en segundo plano..."
echo "      Si aparece un cartel sobre la bateria, toca PERMITIR."
am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d package:com.termux >>"$LOG" 2>&1 || true
sleep 4
ok "Hecho"

echo ""
echo -e "${V}==========================================${N}"
echo -e "${V}   Instalacion terminada!${N}"
echo -e "${V}==========================================${N}"
echo " De ahora en mas: abri Termux y el dashboard"
echo " se abre solo en el navegador."
echo ""
echo " Abriendolo ahora por primera vez..."
sleep 2
export PCRT_ARRANCADO=1
bash "$CARPETA/PCRT.sh"
