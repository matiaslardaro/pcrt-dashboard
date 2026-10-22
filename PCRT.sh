#!/data/data/com.termux/files/usr/bin/bash
# PCRT.sh — arranca el dashboard. Corre solo cada vez que se abre Termux (lo
# deja configurado install.sh en ~/.bashrc); tambien sirve como widget de
# Termux:Widget si alguien lo tiene. Hace:
#   1. mata procesos viejos colgados
#   2. levanta pcrt_server.py en background
#   3. espera a que el server este listo
#   4. abre el dashboard con ?v=<hash del commit>, asi Chrome siempre pide
#      el HTML nuevo de la red y no queda pegado a una version vieja en cache

CARPETA="$HOME/pcrt"
LOG="$CARPETA/pcrt_server.log"

echo "Pidiendo wake lock (evita que Android frene Termux en segundo plano)..."
termux-wake-lock 2>/dev/null

echo "Cerrando procesos viejos..."
pkill -9 -f pcrt_server.py 2>/dev/null
pkill -9 -f gt7_bridge.py 2>/dev/null
pkill -9 -f "http.server" 2>/dev/null
sleep 1

echo "Buscando actualizaciones..."
if ! cd "$CARPETA"; then
  echo "No encuentro el dashboard en $CARPETA. Volve a correr el instalador."
  exit 1
fi
ANTES=$(git rev-parse HEAD 2>/dev/null)
# fetch + reset en vez de pull: a prueba de cambios locales o ramas divergidas (igual que el server)
RAMA=$(git rev-parse --abbrev-ref --symbolic-full-name @{u} 2>/dev/null || echo origin/main)
if timeout 15 git fetch --quiet origin 2>/dev/null && git reset --hard --quiet "$RAMA" 2>/dev/null; then
  DESPUES=$(git rev-parse HEAD 2>/dev/null)
  if [ "$ANTES" != "$DESPUES" ]; then
    echo "Hay una version nueva, instalando dependencias..."
    pip install -q gt-telem websockets < /dev/null 2>/dev/null
  fi
else
  echo "No se pudo buscar actualizaciones (sin internet?), sigo con la version actual"
fi

# Hash del commit actual, para forzar a Chrome a pedir el HTML de nuevo
# cada vez que cambia (cache-busting sin tocar nada a mano)
HASH=$(git rev-parse --short HEAD 2>/dev/null)
if [ -z "$HASH" ]; then
  HASH=$(date +%s)
fi

echo "Iniciando servidor PCRT..."
cd "$CARPETA" || exit 1
nohup python pcrt_server.py > "$LOG" 2>&1 &

# Esperar a que el servidor HTTP realmente conteste (no solo confiar en el log), maximo ~15s
LISTO=0
for i in $(seq 1 30); do
  if (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null; then
    exec 3<&- 3>&-
    LISTO=1
    break
  fi
  sleep 0.5
done
if [ "$LISTO" != "1" ]; then
  echo "El servidor no respondio a tiempo, revisa $LOG"
fi

# Sacar la IP local del celu probando varios metodos, porque "ip route" no anda igual en todos los Android
IP=""
if [ -z "$IP" ]; then
  IP=$(ip route get 1.1.1.1 2>/dev/null | grep -oE 'src [0-9.]+' | awk '{print $2}')
fi
if [ -z "$IP" ]; then
  IP=$(ip -4 addr show wlan0 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print $2}')
fi
if [ -z "$IP" ]; then
  IP=$(ifconfig wlan0 2>/dev/null | grep -oE 'inet (addr:)?[0-9.]+' | awk '{print $NF}' | sed 's/addr://')
fi
if [ -z "$IP" ]; then
  IP=$(getprop dhcp.wlan0.ipaddress 2>/dev/null)
fi
if [ -z "$IP" ] && command -v termux-wifi-connectioninfo >/dev/null 2>&1; then
  IP=$(termux-wifi-connectioninfo 2>/dev/null | grep -oE '"ip": *"[0-9.]+"' | grep -oE '[0-9.]+')
fi
if [ -z "$IP" ]; then
  IP="127.0.0.1"
  echo "No se pudo detectar la IP de red, usando 127.0.0.1 (solo funciona en este mismo celu)"
fi

URL="http://$IP:8080/dashboard.html?v=$HASH"
echo "Abriendo $URL"
termux-open-url "$URL"

echo ""
echo "=========================================="
echo " PCRT andando. Ya podes ir al navegador."
echo " Dejá Termux abierto en segundo plano"
echo " (no lo cierres desde la notificacion)."
echo ""
echo " Desde otro celu o tablet en la misma wifi:"
echo "   http://$IP:8080/dashboard.html"
echo "=========================================="
