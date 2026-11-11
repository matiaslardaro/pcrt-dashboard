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
import math
import os
import signal
import socket
import subprocess
import threading
import time

import websockets
from gt_telem import TurismoClient

HERE = os.path.dirname(os.path.abspath(__file__))

# Estado compartido entre el thread de telemetria y el server WS.
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


def get_upstream():
    """Rama remota que sigue este celu (origin/main en todos los pilotos).
    Permite probar una version en UNA tablet (cambiandola de rama) sin que el
    auto-update la vuelva a pisar con main a los 3 minutos."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"],
            cwd=HERE, capture_output=True, text=True, timeout=5,
        )
        u = out.stdout.strip()
        return u if out.returncode == 0 and u.startswith("origin/") else "origin/main"
    except Exception:
        return "origin/main"


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
                ["git", "reset", "--hard", "--quiet", get_upstream()],
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
# Arranque bajo el vigia. Copias viejas del script de arranque (el acceso directo de
# Termux:Widget se copio una sola vez al instalar y nunca se actualiza) levantan el
# server SIN el vigia: si Android lo mata, nadie lo levanta (cortes del 10/11).
# Si arrancamos sin el vigia, arreglamos el acceso directo y nos relanzamos bajo el vigia.
# ---------------------------------------------------------------------------
ACCESO_DIRECTO = """#!/data/data/com.termux/files/usr/bin/bash
# Acceso directo de Termux:Widget: llama al script de arranque actualizado del repo
exec bash "$HOME/pcrt/PCRT.sh"
"""


def asegurar_vigia():
    en_termux = "com.termux" in os.environ.get("PREFIX", "")
    vigia = os.path.join(HERE, "pcrt_vigia.sh")
    if not en_termux or os.environ.get("PCRT_VIGIA") == "1" or not os.path.isfile(vigia):
        return
    atajo = os.path.join(os.path.expanduser("~"), ".shortcuts", "PCRT.sh")
    try:
        if os.path.isfile(atajo):
            with open(atajo) as f:
                viejo = f.read()
            if viejo != ACCESO_DIRECTO:
                with open(atajo, "w") as f:
                    f.write(ACCESO_DIRECTO)
                os.chmod(atajo, 0o755)
                print("[pcrt_server] Acceso directo viejo reemplazado por uno que usa PCRT.sh actualizado")
    except OSError as e:
        print(f"[pcrt_server] No se pudo arreglar el acceso directo: {e}")
    print("[pcrt_server] Arrancado sin el vigia: me relanzo bajo el vigia para que me levante si Android me mata")
    subprocess.Popen(
        ["bash", vigia], cwd=HERE, start_new_session=True,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    raise SystemExit(0)


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
def _limpiar(v):
    # NaN/Infinity no son JSON valido para el navegador: un solo campo asi hace que
    # el dashboard no pueda leer NINGUN paquete y quede congelado. Los pasamos a None.
    if isinstance(v, float):
        return v if math.isfinite(v) else None
    if isinstance(v, (list, tuple)):
        return [_limpiar(x) for x in v]
    if isinstance(v, dict):
        return {k: _limpiar(x) for k, x in v.items()}
    return v


# ultimo paquete crudo de la PS5 (objeto Telemetry + hora de llegada). Se guarda tal cual:
# convertirlo a dict/JSON se hace recien al mandarlo, 15 veces por segundo y no 60.
# Menos CPU en segundo plano = menos motivos para que Android mate Termux.
ultimo_paquete = (None, None)
_cache = {"ts": None, "data": None}


paquetes_ps5 = [0]       # paquetes procesados de la PS5 desde el ultimo latido
tablets_conectadas = [0]


def latido(interval_sec=60):
    """Una linea por minuto en el log con la hora: si el log se corta de golpe sin
    'el server se cayo' del vigia, Android mato Termux entero; si siguen los latidos
    con 0 paquetes, el server vivia pero la PS5 no mandaba."""
    while True:
        time.sleep(interval_sec)
        n, paquetes_ps5[0] = paquetes_ps5[0], 0
        print(f"[pcrt_server {time.strftime('%H:%M:%S')}] latido: {n} paquetes PS5/min, "
              f"{tablets_conectadas[0]} tablet(s) conectada(s)")


def on_telemetry(t):
    # sync a proposito: gt-telem crea un event loop NUEVO por paquete (60 por segundo)
    # para callbacks async; sync es mucho mas liviano y Android tiene menos motivos para matarlo
    global ultimo_paquete, _first_packet_seen
    if not _first_packet_seen:
        _first_packet_seen = True
        print("[pcrt_server] >>> Primer paquete de telemetria recibido de la PS5 <<<")
    ultimo_paquete = (t, time.time())
    paquetes_ps5[0] += 1


def armar_latest():
    """dict listo para mandar con el ultimo paquete (se arma una sola vez por paquete nuevo)."""
    t, ts = ultimo_paquete
    if t is None:
        return {"connected": False}
    if _cache["ts"] != ts:
        try:
            data = _limpiar(dict(t.as_dict))
        except Exception:
            data = {}
        data["connected"] = True
        data["_ts"] = ts
        _cache["data"], _cache["ts"] = data, ts
    return _cache["data"]


async def watchdog():
    await asyncio.sleep(8)
    if not _first_packet_seen:
        print("[pcrt_server] AVISO: pasaron 8s y no llego ningun paquete de la PS5 todavia.")
        print("[pcrt_server] Revisa: misma red que la PS5, IP correcta, y que GT7 este en pista (no en menus).")


# IP de la PS5 que esta usando el cliente (la completa run_telemetry_client).
ps_ip_actual = {"ip": None}

# Puerto donde GT7 escucha el heartbeat (33339 + 400 para GT7).
HEARTBEAT_PORT = 33739


def heartbeat_keeper(interval_sec=1.0):
    """Heartbeat propio, a prueba de microcortes de wifi.

    gt-telem manda su heartbeat cada 10s, pero si UN envio falla (wifi que
    parpadea un segundo -> OSError), esa tarea muere para siempre sin avisar:
    el proceso sigue abierto, la PS5 deja de mandar telemetria y el dashboard
    queda tildado hasta reiniciar Termux. Este thread manda el heartbeat cada
    1s y, si falla, reintenta en el siguiente ciclo en vez de morirse."""
    sock = None
    fallos = 0
    while True:
        ip = ps_ip_actual["ip"]
        if ip:
            try:
                if sock is None:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.sendto(b"A", (ip, HEARTBEAT_PORT))
                if fallos:
                    print(f"[pcrt_server] Heartbeat recuperado despues de {fallos} fallos")
                fallos = 0
            except OSError as e:
                fallos += 1
                if fallos == 1 or fallos % 30 == 0:
                    print(f"[pcrt_server] Heartbeat fallo ({e}), reintentando...")
                try:
                    sock.close()
                except Exception:
                    pass
                sock = None
        time.sleep(interval_sec)


class ClienteLiviano(TurismoClient):
    """TurismoClient de gt-telem, pero gastando mucha menos CPU (para que Android no mate Termux):
    - la PS5 manda 60 paquetes/s y al dashboard le mandamos 15: descartamos los sobrantes
      ANTES de descifrarlos y parsearlos (gt-telem procesa los 60).
    - el callback se llama directo, sin crear un thread por paquete."""
    _ultimo_procesado = 0.0

    def _handle_data(self, data):
        ahora = time.monotonic()
        if ahora - self._ultimo_procesado < 1 / 20:
            return
        self._ultimo_procesado = ahora
        super()._handle_data(data)

    def _invoke_callbacks(self, telemetry_value):
        for callback in list(self._telem_update_callbacks):
            try:
                callback(telemetry_value)
            except Exception as e:
                print(f"[pcrt_server] Error procesando paquete: {e}")


def run_telemetry_client(ps_ip):
    """Si el cliente de gt-telem se cae (o no encuentra la PS5 al arrancar),
    lo vuelve a levantar solo en vez de dejar el thread muerto."""
    while True:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            kwargs = {}
            if ps_ip:
                kwargs["ps_ip"] = ps_ip
            try:
                tc = ClienteLiviano(**kwargs)
            except TypeError:
                tc = ClienteLiviano()
            ps_ip_actual["ip"] = getattr(tc, "ip_addr", None) or ps_ip
            tc.register_callback(on_telemetry)
            print(f"[pcrt_server] Escuchando telemetria de GT7 (PS5 en {ps_ip_actual['ip']})...")
            tc.run()
            print("[pcrt_server] El cliente de telemetria se detuvo, reiniciando en 3s...")
        except Exception as e:
            print(f"[pcrt_server] Error en cliente de telemetria ({e}), reintentando en 3s...")
        try:
            if not loop.is_closed():
                loop.close()
        except Exception:
            pass
        time.sleep(3)


# ---------------------------------------------------------------------------
# WebSocket (retransmite el ultimo paquete a los dashboards conectados)
# ---------------------------------------------------------------------------
async def ws_handler(websocket):
    tablets_conectadas[0] += 1
    print(f"[pcrt_server {time.strftime('%H:%M:%S')}] Tablet conectada por WebSocket")
    try:
        while True:
            latest = armar_latest()
            payload = latest
            # Si hace mas de 4s que no llega un paquete nuevo de la PS5 (UDP cortado,
            # repetidor con microcortes, etc.), avisamos al dashboard en vez de
            # mandarle para siempre el ultimo dato bueno como si nada (bug reportado
            # por Walter: la app queda "tildada" sin que se note).
            ts = latest.get("_ts")
            if ts is not None and (time.time() - ts) > 4:
                payload = dict(latest)
                payload["connected"] = False
            # default=str: si algun campo raro no se puede pasar a JSON, que no se caiga la conexion
            await websocket.send(json.dumps(payload, default=str))
            await asyncio.sleep(1 / 15)
    except websockets.exceptions.ConnectionClosed:
        print(f"[pcrt_server {time.strftime('%H:%M:%S')}] Tablet desconectada")
    except Exception as e:
        print(f"[pcrt_server {time.strftime('%H:%M:%S')}] Error mandando a la tablet: {e!r}")
    finally:
        tablets_conectadas[0] -= 1


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
        # no exponer el repo ni el log a la red (solo hace falta servir el dashboard y sus archivos)
        ruta = self.path.split("?", 1)[0]
        if ruta.startswith("/.git") or ruta.endswith((".log", ".py", ".sh")):
            self.send_error(404)
            return
        if self.path.startswith("/diag"):
            # cola del log de esta vez y de la anterior: el dashboard la sube sola a Supabase
            # despues de un corte, asi se diagnostica sin que el piloto tenga que hacer nada
            partes = []
            for nombre in ("pcrt_server.log.anterior", "pcrt_server.log"):
                try:
                    with open(os.path.join(HERE, nombre), "r", errors="replace") as f:
                        lineas = f.readlines()[-80:]
                    partes.append(f"===== {nombre} =====\n" + "".join(lineas))
                except OSError:
                    partes.append(f"===== {nombre} (no existe) =====\n")
            body = "\n".join(partes).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
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

    asegurar_vigia()

    print("[pcrt_server] Chequeando procesos viejos colgados...")
    kill_old_processes()

    current_version["hash"] = get_git_hash()
    t_update = threading.Thread(target=git_updater, daemon=True)
    t_update.start()

    t_telem = threading.Thread(target=run_telemetry_client, args=(args.ip,), daemon=True)
    t_telem.start()

    t_hb = threading.Thread(target=heartbeat_keeper, daemon=True)
    t_hb.start()

    threading.Thread(target=latido, daemon=True).start()

    t_http = threading.Thread(target=run_http_server, args=(args.http_port,), daemon=True)
    t_http.start()

    asyncio.run(main(args.ws_port))
