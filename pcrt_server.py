"""
pcrt_server.py
Version unificada: en UN solo comando levanta
  1) el bridge de telemetria de GT7 (WebSocket, puerto 8765)
  2) el servidor HTTP que sirve dashboard.html (puerto 8080)
Y antes de arrancar, mata cualquier proceso viejo colgado (bridge o
http.server de intentos anteriores) para que nunca mas tire
"Address already in use".

Instalacion (una sola vez):
    pip install gt-telem websockets

Uso normal:
    python pcrt_server.py
    python pcrt_server.py --ip 192.168.1.50   (si no descubre la PS5 sola)

Despues, desde cualquier tablet en la misma red, abrís en el navegador:
    http://<IP-de-este-celu>:8080/dashboard.html

Para saber la IP de este celu: Ajustes > Wifi > tocar la red conectada.
"""

import argparse
import asyncio
import functools
import http.server
import json
import os
import signal
import subprocess
import threading
import time

import websockets
from gt_telem import TurismoClient

HERE = os.path.dirname(os.path.abspath(__file__))

# Estado compartido entre el thread de telemetria y el server WS.
latest = {"connected": False}
_first_packet_seen = False

# Estado de version del repo, para que el dashboard detecte solo cuando
# hay una actualizacion nueva sin depender de que alguien reinicie Termux.
current_version = {"hash": None}
version_lock = threading.Lock()


def get_git_hash():
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=HERE, capture_output=True, text=True, timeout=5,
        )
        h = out.stdout.strip()
        return h if h else None
    except Exception:
        return None


def git_updater(interval_sec=180):
    """Corre en background y cada tanto busca actualizaciones del repo,
    sin depender de que se reinicie el widget de Termux.

    Usa fetch + reset --hard en vez de pull para que la sincronizacion
    sea a prueba de cambios locales, conflictos o ramas divergidas en
    el dispositivo de cada piloto (nadie deberia tocar el codigo a mano,
    pero si pasa, esto lo pisa solo sin que nadie tenga que intervenir)."""
    while True:
        try:
            before = get_git_hash()
            subprocess.run(
                ["git", "fetch", "--quiet", "origin"],
                cwd=HERE, capture_output=True, text=True, timeout=15,
            )
            subprocess.run(
                ["git", "reset", "--hard", "--quiet", "origin/main"],
                cwd=HERE, capture_output=True, text=True, timeout=15,
            )
            subprocess.run(
                ["git", "clean", "-fd", "--quiet"],
                cwd=HERE, capture_output=True, text=True, timeout=15,
            )
            after = get_git_hash()
            if after:
                with version_lock:
                    current_version["hash"] = after
                if before and after != before:
                    print(f"[pcrt_server] Nueva version detectada: {before} -> {after}")
        except Exception as e:
            print(f"[pcrt_server] Error buscando actualizaciones: {e}")
        time.sleep(interval_sec)


# ---------------------------------------------------------------------------
# Limpieza de procesos viejos colgados (bridge / http.server / esta misma
# app de una corrida anterior que no cerro bien) antes de arrancar.
# ---------------------------------------------------------------------------
def kill_old_processes():
    my_pid = os.getpid()
    patterns = ["gt7_bridge.py", "http.server", "pcrt_server.py"]
    killed_any = False
    for pattern in patterns:
        try:
            out = subprocess.run(
                ["pgrep", "-f", pattern], capture_output=True, text=True
            ).stdout
        except FileNotFoundError:
            # pgrep no esta disponible en este entorno; seguimos sin limpiar
            continue
        for line in out.splitlines():
            line = line.strip()
            if not line.isdigit():
                continue
            pid = int(line)
            if pid == my_pid:
                continue
            try:
                os.kill(pid, signal.SIGKILL)
                print(f"[pcrt_server] Mate proceso viejo colgado: PID {pid} ({pattern})")
                killed_any = True
            except ProcessLookupError:
                pass
    if killed_any:
        time.sleep(1)  # darle un segundo al sistema para liberar los puertos


# ---------------------------------------------------------------------------
# Telemetria GT7
# ---------------------------------------------------------------------------
async def on_telemetry(t):
    global latest, _first_packet_seen
    if not _first_packet_seen:
        _first_packet_seen = True
        print("[pcrt_server] >>> Primer paquete de telemetria recibido de la PS5 <<<")
    try:
        data = dict(t.as_dict)
    except Exception:
        data = {}
    data["connected"] = True
    data["_ts"] = time.time()
    latest = data


async def watchdog():
    await asyncio.sleep(8)
    if not _first_packet_seen:
        print("[pcrt_server] AVISO: pasaron 8s y no llego ningun paquete de la PS5 todavia.")
        print("[pcrt_server] Revisa: misma red que la PS5, IP correcta, y que GT7 este en pista (no en menus).")


def run_telemetry_client(ps_ip):
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    kwargs = {}
    if ps_ip:
        kwargs["ps_ip"] = ps_ip
    try:
        tc = TurismoClient(**kwargs)
    except TypeError:
        tc = TurismoClient()
    tc.register_callback(on_telemetry)
    print("[pcrt_server] Escuchando telemetria de GT7...")
    tc.run()


# ---------------------------------------------------------------------------
# WebSocket (retransmite 'latest' a los dashboards conectados)
# ---------------------------------------------------------------------------
async def ws_handler(websocket):
    print("[pcrt_server] Tablet conectada por WebSocket")
    try:
        while True:
            await websocket.send(json.dumps(latest))
            await asyncio.sleep(1 / 15)
    except websockets.exceptions.ConnectionClosed:
        print("[pcrt_server] Tablet desconectada")


# ---------------------------------------------------------------------------
# Servidor HTTP simple para dashboard.html (corre en su propio thread)
# Manda Cache-Control: no-store en cada respuesta para que el navegador
# jamas se quede con una version vieja cacheada del dashboard.
# ---------------------------------------------------------------------------
class NoCacheHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def do_GET(self):
        if self.path.startswith("/version"):
            with version_lock:
                h = current_version["hash"] or "unknown"
            body = json.dumps({"hash": h}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()


def run_http_server(http_port):
    handler = functools.partial(NoCacheHandler, directory=HERE)
    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", http_port), handler)
    print(f"[pcrt_server] Dashboard disponible en http://0.0.0.0:{http_port}/dashboard.html")
    httpd.serve_forever()


# ---------------------------------------------------------------------------
async def main(ws_port):
    asyncio.create_task(watchdog())
    async with websockets.serve(ws_handler, "0.0.0.0", ws_port):
        print(f"[pcrt_server] WebSocket de telemetria escuchando en ws://0.0.0.0:{ws_port}")
        await asyncio.Future()  # corre para siempre


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ip", help="IP de la PlayStation en tu red (opcional)", default=None)
    parser.add_argument("--ws-port", help="Puerto del WebSocket de telemetria", type=int, default=8765)
    parser.add_argument("--http-port", help="Puerto del servidor del dashboard", type=int, default=8080)
    args = parser.parse_args()

    print("[pcrt_server] Chequeando procesos viejos colgados...")
    kill_old_processes()

    current_version["hash"] = get_git_hash()
    t_update = threading.Thread(target=git_updater, daemon=True)
    t_update.start()

    t_telem = threading.Thread(target=run_telemetry_client, args=(args.ip,), daemon=True)
    t_telem.start()

    t_http = threading.Thread(target=run_http_server, args=(args.http_port,), daemon=True)
    t_http.start()

    asyncio.run(main(args.ws_port))
