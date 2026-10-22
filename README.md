# PCRT Dashboard

Dashboard de telemetría en vivo de Gran Turismo 7 para Team PCRT (Matias, Mariano, Luciano, Walter).

Corre local en cada celu o tablet Android vía Termux: recibe la telemetría de la PS5 y la muestra en un display estilo GT3 en tiempo real (marcha, LEDs de cambio, tiempos, delta, gomas, nafta, etc).

**Guía paso a paso con botón para copiar el comando:** en la web del campeonato, `instalar.html` (ej. `https://<web-pcrt>/instalar.html`).

## Instalación (una sola vez, ~10 minutos)

1. **Instalar Termux** desde F-Droid (NO desde Play Store, esa versión está vieja y no anda):
   https://f-droid.org/packages/com.termux/ → al final de la página, **Descargar APK**. Android va a pedir permiso para instalar apps desde el navegador: aceptar.

2. **Abrir Termux, pegar este comando y apretar Enter** (mantener apretado en la pantalla negra → Pegar):

   ```
   curl -sL https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/install.sh | bash
   ```

   Instala todo solo. Si aparece un cartel sobre la batería, tocar **Permitir** (así Android no corta el dashboard en segundo plano).

3. Listo. Al terminar se abre el dashboard en el navegador: elegir piloto y **Encender motor**.

Si algo falla, el instalador muestra un mensaje en rojo: mandarle una captura a Mati. El detalle queda en `~/pcrt-instalacion.log`.

## Uso diario

**Abrir Termux.** El dashboard arranca solo y se abre en el navegador. Dejar Termux abierto en segundo plano.

- El celu tiene que estar en la **misma wifi que la PS5**.
- GT7 tiene que estar en pista (no en menús) para que llegue telemetría.
- Desde otro celu o tablet en la misma wifi se puede abrir la dirección que muestra Termux al arrancar (`http://IP:8080/dashboard.html`).

Ya no hace falta Termux:Widget. Si alguien lo tiene instalado, el ícono **PCRT.sh** sigue funcionando igual.

## Actualizar a una versión nueva

Se actualiza solo. Mientras el servidor está prendido, cada 3 minutos busca cambios en el repo y los instala, y el dashboard se recarga solo cuando hay versión nueva (nunca en medio de una sesión con vueltas). Al abrir Termux también busca actualizaciones. Sin internet sigue con la versión que tenía.

## Problemas comunes

- **No conecta / "esperando telemetría"**: revisar que el celu y la PS5 estén en la misma wifi, y que GT7 esté corriendo (no en un menú).
- **Se corta al rato**: en Ajustes > Apps > Termux > Batería, ponerlo en **Sin restricciones**.
- **Algo quedó raro**: volver a pegar el comando de instalación. Se puede correr las veces que haga falta, actualiza lo que ya está.
- **No quiero que arranque solo al abrir Termux**: borrar el bloque `# >>> PCRT >>>` del archivo `~/.bashrc`.
