# PCRT Dashboard

Telemetría de Gran Turismo 7 en vivo en el celu o la tablet, para Team PCRT (Matias, Mariano, Luciano, Walter): marcha, LEDs de cambio, tiempos, delta, gomas, nafta y más, en un display estilo GT3.

Cada carrera que corras con la palanca **FECHA** prendida se sube sola a la web del campeonato, en la pestaña **Telemetría**.

---

## Cómo instalarlo

Se hace **una sola vez** y tarda unos 10 minutos. Solo para **Android**.

### 1. Instalá Termux

Es la app que recibe los datos de la PS5. Bajala desde F-Droid:

👉 **https://f-droid.org/packages/com.termux/**

Bajá hasta el final de esa página y tocá **Descargar APK** (el primero de la lista). Cuando Android pregunte si permitís instalar apps desde el navegador, tocá **Permitir** e instalala.

> ⚠️ **No la bajes de Play Store**: esa versión está vieja y no funciona.

### 2. Copiá este comando

Tocá el botón de copiar que aparece a la derecha del cuadro:

```
curl -sL https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/install.sh | bash
```

### 3. Pegalo en Termux

1. Abrí **Termux** (la pantalla negra).
2. **Mantené apretado** el dedo en la pantalla y tocá **Pegar**.
3. Tocá **Enter** en el teclado.

Se instala todo solo. Vas a ver los pasos del 1 al 6 poniéndose en **OK**. No cierres Termux ni apagues la pantalla mientras tanto.

Si aparece un cartel sobre la **batería**, tocá **Permitir**: así Android no corta el dashboard mientras corrés.

### 4. Listo

Al terminar se abre el dashboard solo. Elegí tu piloto y tocá **Encender motor**.

> Si aparece algo en **rojo** que dice **ERROR**, sacale una captura y mandásela a Mati.

---

## Cada vez que corras

1. **Abrí Termux.** El dashboard se abre solo en el navegador. Después dejá Termux en segundo plano, no lo cierres.
2. El celu tiene que estar en la **misma wifi que la PS5**.
3. En una fecha del campeonato, prendé la palanca **FECHA** arriba a la derecha (dice **REGISTRANDO**). Así cada carrera se sube sola a la web cuando cruzás la meta.

Los datos llegan cuando estás en pista, no en los menús. Se actualiza solo cuando hay una versión nueva, no hay que hacer nada.

---

## Si algo no anda

| Qué pasa | Qué hacer |
|---|---|
| Dice "esperando" y no aparecen datos | Revisá que el celu esté en la misma wifi que la PS5 y que GT7 esté en pista. |
| Se corta al rato | Ajustes > Apps > Termux > Batería > **Sin restricciones**. |
| Quedó algo raro | Volvé a pegar el comando del paso 2 en Termux. Se puede hacer las veces que haga falta. |
| Mis carreras no aparecen en la web | Fijate que la palanca **FECHA** esté prendida (dice REGISTRANDO) antes de largar. |
| Tengo iPhone | No se puede instalar. Podés ver el dashboard de otro celu o tablet que ya lo tenga: abrí en Safari la dirección que muestra Termux en ese equipo (empieza con `http://192.168…`). |

---

## Para el que mantiene el proyecto

- `pcrt_server.py`: recibe la telemetría de la PS5 (con [gt-telem](https://github.com/RaceCrewAI/gt-telem)), la manda por WebSocket (puerto 8765) y sirve el dashboard (puerto 8080). Cada 3 minutos se actualiza solo desde la rama `main`.
- `dashboard.html`: el display. Sube las carreras a Supabase (tabla `dashboard_sessions`) cuando la palanca FECHA está prendida.
- `install.sh`: instalador de un comando. Deja configurado que `PCRT.sh` corra solo al abrir Termux (bloque `# >>> PCRT >>>` en `~/.bashrc`; borrarlo para que no arranque solo). El detalle de la instalación queda en `~/pcrt-instalacion.log`.
- `PCRT.sh`: arranca el server y abre el dashboard. También sirve como widget de Termux:Widget.
- Autos nuevos de GT7: la lista sale de [ddm999/gt7info](https://github.com/ddm999/gt7info) (`cars.csv`). Si aparece "Auto #… (no identificado)", hay que actualizar `CAR_NAMES` en `dashboard.html`.
