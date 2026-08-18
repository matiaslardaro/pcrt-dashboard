#!/data/data/com.termux/files/usr/bin/bash
# PCRT.sh — poner en ~/.shortcuts/ para que aparezca como widget de Termux
# en la pantalla de inicio. Un solo toque:
#   1. mata procesos viejos colgados
#   2. levanta pcrt_server.py en background
#   3. espera a que el server este listo
#   4. abre el dashboard directo en el navegador, ya instalado como PWA
#      si "Añadir a pantalla de inicio" se hizo una vez antes

CARPETA="$HOME/pcrt"
LOG="$CARPETA/pcrt_server.log"

echo "Cerrando procesos viejos..."
pkill -9 -f pcrt_server.py 2>/dev/null
pkill -9 -f gt7_bridge.py 2>/dev/null
pkill -9 -f "http.server" 2>/dev/null
sleep 1

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

URL="http://$IP:8080/dashboard.html"
echo "Abriendo $URL"
termux-open-url "$URL"
