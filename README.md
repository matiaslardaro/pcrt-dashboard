# PCRT Dashboard

Dashboard de telemetría en vivo de Gran Turismo 7 para Team PCRT (Matias, Mariano, Luciano, Walter).

Corre local en cada tablet vía Termux: recibe la telemetría UDP de la PS5 y la muestra en un dashboard web en tiempo real (circuito, vueltas, delta, combustible, etc).

## Instalación (una sola vez)

1. Instalar **Termux** desde F-Droid (NO desde Play Store):
   https://f-droid.org/packages/com.termux/

2. Instalar **Termux:Widget** desde F-Droid:
   https://f-droid.org/packages/com.termux.widget/

3. Abrir Termux y pegar este comando:

   ```
   curl -sL https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/install.sh | bash
   ```

   Deja todo instalado solo: Python, librerías, archivos del proyecto, y el widget listo.

4. En la pantalla de inicio del celu: mantener presionado > **Widgets** > buscar **Termux:Widget** > arrastrar el ícono **PCRT.sh** a la pantalla.

5. Listo. De ahora en más, un toque en ese ícono prende todo y abre el dashboard solo.

## Uso diario

Tocar el ícono **PCRT.sh** en la pantalla de inicio. Eso:
- Prende el servidor que escucha la telemetría de la PS5
- Detecta la IP del celu en la red
- Abre el dashboard en el navegador

Importante: el celu tiene que estar conectado a la **misma red wifi que la PS5**, y GT7 tiene que estar en pista (no en menús) para que empiece a llegar telemetría.

## Actualizar a una versión nueva

Se actualiza solo. Cada vez que tocás el ícono **PCRT.sh**, antes de prender el servidor busca cambios nuevos en el repo (`git pull`) y los instala — no hace falta correr ningún comando a mano. Si el celu no tiene internet en ese momento (ej: red de la PS5 sin salida a internet), sigue de largo con la versión que ya tenía instalada, sin trabarse.

## Problemas comunes

- **No conecta / "esperando telemetría"**: revisar que el celu y la PS5 estén en la misma red wifi, y que GT7 esté corriendo una vuelta (no en un menú).
- **El widget no aparece en Termux:Widget**: abrir Termux y correr `ls ~/.shortcuts/` — tiene que listar `PCRT.sh`. Si no está, correr el instalador de nuevo.
- **"Termux requires 'Display over other apps' permission"**: Android pide este permiso aparte para que el widget pueda abrir la sesión de Termux. Ir a **Ajustes > Apps > Termux > Permisos avanzados (u "Otros permisos") > Mostrar sobre otras apps** y activarlo. Después de eso el widget funciona normal.
