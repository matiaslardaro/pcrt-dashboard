#!/data/data/com.termux/files/usr/bin/bash
# pcrt_vigia.sh — mantiene vivo pcrt_server.py. Si Android lo mata en segundo
# plano (phantom process killer de Android 12+, ahorro de bateria, etc.) o se
# cae por lo que sea, lo vuelve a levantar a los 2s. El dashboard se reconecta
# solo, asi que el piloto ni se entera.
# Lo arranca PCRT.sh; en el log queda cada caida con su codigo de salida
# (137 o -9 = lo mato Android).
#
# OJO: este archivo NO puede tener el nombre del server en su linea de comando,
# porque pcrt_server.py al arrancar mata todo lo que matchee con su nombre.

CARPETA="$HOME/pcrt"
LOG="$CARPETA/pcrt_server.log"
cd "$CARPETA" || exit 1

while true; do
  # el log no puede crecer infinito si se cae en loop
  if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 2000000 ]; then
    tail -c 500000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
  echo "[vigia $(date '+%F %T')] arrancando server" >> "$LOG"
  # PCRT_VIGIA=1: el server sabe que lo esta cuidando el vigia (si no, se relanza bajo el vigia)
  PCRT_VIGIA=1 python -u pcrt_server.py >> "$LOG" 2>&1
  CODIGO=$?
  echo "[vigia $(date '+%F %T')] el server se cayo (codigo $CODIGO), lo reinicio en 2s" >> "$LOG"
  sleep 2
done
