#!/bin/bash
# PCRT.command — arranca el dashboard PCRT en una Mac. Doble clic y listo.
# Vive en ~/pcrt/PCRT.command; mac/instalar.sh deja en el Escritorio un acceso directo que lo llama. Cada vez que arranca:
#   1. baja la ultima version del dashboard de GitHub (sin git)
#   2. si faltan las librerias de Python, las instala
#   3. levanta pcrt_server.py y abre el dashboard en el navegador
# Cerrar esta ventana de Terminal apaga el dashboard.
#
# Para pasarle la IP de la PS5 a mano (si no la encuentra sola), guardarla en ~/pcrt/.ps5ip:
#   echo 192.168.0.50 > ~/pcrt/.ps5ip

CARPETA="$HOME/pcrt"
REPO="matiaslardaro/pcrt-dashboard"
export PATH="$HOME/.local/bin:$PATH"

V='\033[1;32m'; A='\033[1;33m'; R='\033[1;31m'; N='\033[0m'
error(){
  echo ""
  echo -e "${R}ERROR: $1${N}"
  echo "Sacale una captura a esta ventana y mandasela a Mati."
  read -r -p "Apreta Enter para cerrar..." _
  exit 1
}

mkdir -p "$CARPETA" && cd "$CARPETA" || error "No pude crear la carpeta $CARPETA"
RAMA=$(cat .rama 2>/dev/null); [ -z "$RAMA" ] && RAMA=main

# bajar <rama>: baja el codigo de esa rama y lo copia encima de la carpeta (no toca .venv)
bajar(){
  local tmp dir
  tmp=$(mktemp -d) || return 1
  if ! curl -fsSL --max-time 60 "https://api.github.com/repos/$REPO/tarball/$1" | tar xz -C "$tmp" 2>/dev/null; then
    rm -rf "$tmp"; return 1
  fi
  dir=$(ls "$tmp" | head -n 1)
  [ -n "$dir" ] && [ -f "$tmp/$dir/pcrt_server.py" ] || { rm -rf "$tmp"; return 1; }
  cp -R "$tmp/$dir/." "$CARPETA/" && echo "${dir##*-}" > "$CARPETA/.version"
  rm -rf "$tmp"
}

if [ "$1" = "--actualizado" ]; then
  shift
  echo -e "${V}OK${N} version $(cat .version)"
else
echo -e "${A}Buscando actualizaciones...${N}"
if bajar "$RAMA"; then
  echo -e "${V}OK${N} version $(cat .version)"
elif [ "$RAMA" != "main" ] && bajar main; then
  # la rama de prueba ya no existe (se paso a main): de aca en mas seguimos main
  echo main > .rama
  echo -e "${V}OK${N} version $(cat .version)"
elif [ -f pcrt_server.py ]; then
  echo "No se pudo actualizar (sin internet?), sigo con la version que ya esta."
else
  error "No pude bajar el dashboard. Revisa que la Mac tenga internet."
fi
# si cambio este mismo script, lo reemplazamos (mv, no se pisa el que esta corriendo) y lo relanzamos
if [ -f mac/PCRT.command ] && ! cmp -s mac/PCRT.command PCRT.command; then
  cp mac/PCRT.command PCRT.command.nuevo && chmod +x PCRT.command.nuevo && mv -f PCRT.command.nuevo PCRT.command \
    && exec bash "$CARPETA/PCRT.command" --actualizado "$@"
fi
fi

if ! .venv/bin/python -c "import gt_telem, websockets" 2>/dev/null; then
  echo -e "${A}Instalando Python y las librerias (solo la primera vez, tarda un par de minutos)...${N}"
  command -v uv >/dev/null 2>&1 || error "Falta uv. Volve a correr el instalador."
  rm -rf .venv
  uv venv --quiet --python 3.12 .venv || error "No se pudo instalar Python."
  uv pip install --quiet --python .venv/bin/python gt-telem websockets || error "No se pudieron instalar las librerias."
  echo -e "${V}OK${N} librerias listas"
fi

pkill -f pcrt_server.py 2>/dev/null
sleep 1

ARGS=()
PS5IP=$(cat .ps5ip 2>/dev/null | tr -d '[:space:]')
[ -n "$PS5IP" ] && ARGS=(--ip "$PS5IP")

echo -e "${A}Arrancando el dashboard...${N}"
.venv/bin/python -u pcrt_server.py "${ARGS[@]}" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT INT TERM HUP

LISTO=0
for i in $(seq 1 40); do
  if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then LISTO=1; break; fi
  kill -0 $SERVER 2>/dev/null || break
  sleep 0.5
done
[ "$LISTO" = "1" ] || error "El servidor no arranco. Mira los mensajes de arriba."

open "http://localhost:8080/dashboard.html?v=$(cat .version 2>/dev/null)"

IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)
echo ""
echo -e "${V}==========================================${N}"
echo -e "${V} PCRT andando. Ya podes ir al navegador.${N}"
echo " Deja esta ventana abierta mientras corres:"
echo " si la cerras, se apaga el dashboard."
[ -n "$IP" ] && echo "" && echo " Desde un celu o tablet en la misma wifi:" && echo "   http://$IP:8080/dashboard.html"
echo -e "${V}==========================================${N}"
echo ""

wait $SERVER
