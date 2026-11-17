#!/bin/bash
# instalar.sh — instalador de un comando del dashboard PCRT para Mac.
# Uso (pegar en la Terminal):
#   curl -fsSL https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/mac/instalar.sh | bash
#
# No pide contraseña ni necesita las herramientas de desarrollo de Apple:
#   - instala uv (https://docs.astral.sh/uv/), que trae su propio Python, en ~/.local/bin
#   - baja el dashboard en ~/pcrt
#   - deja "PCRT" en el Escritorio: doble clic para arrancarlo
# Se puede correr de nuevo sin problema.

REPO="matiaslardaro/pcrt-dashboard"
RAMA="${RAMA:-main}"
CARPETA="$HOME/pcrt"
export PATH="$HOME/.local/bin:$PATH"

V='\033[1;32m'; A='\033[1;33m'; R='\033[1;31m'; N='\033[0m'
falla(){ echo -e "\n${R}ERROR: $1${N}\nSacale una captura a esta ventana y mandasela a Mati."; exit 1; }

echo "=========================================="
echo "   PCRT - Instalador para Mac"
echo "=========================================="

[ "$(uname)" = "Darwin" ] || falla "Este instalador es para Mac."

echo -e "\n${A}[1/3]${N} Instalando uv (trae Python)..."
if ! command -v uv >/dev/null 2>&1; then
  curl -LsSf https://astral.sh/uv/install.sh | env UV_NO_MODIFY_PATH=1 sh >/dev/null 2>&1 \
    || falla "No se pudo instalar uv. Revisa que la Mac tenga internet."
fi
command -v uv >/dev/null 2>&1 || falla "uv quedo mal instalado."
echo -e "      ${V}OK${N}"

echo -e "\n${A}[2/3]${N} Bajando el dashboard..."
mkdir -p "$CARPETA" || falla "No pude crear $CARPETA"
echo "$RAMA" > "$CARPETA/.rama"
curl -fsSL "https://raw.githubusercontent.com/$REPO/$RAMA/mac/PCRT.command" -o "$CARPETA/PCRT.command" \
  || falla "No pude bajar el dashboard. Revisa que la Mac tenga internet."
chmod +x "$CARPETA/PCRT.command"
echo -e "      ${V}OK${N}"

echo -e "\n${A}[3/3]${N} Dejando el acceso directo en el Escritorio..."
echo "      (si la Mac pregunta si la Terminal puede usar el Escritorio, toca Permitir)"
# el acceso directo solo llama al script de ~/pcrt, que se actualiza solo
if printf '#!/bin/bash\n# Acceso directo al dashboard PCRT (el script real esta en ~/pcrt y se actualiza solo)\nexec bash "$HOME/pcrt/PCRT.command"\n' > "$HOME/Desktop/PCRT.command" 2>/dev/null; then
  chmod +x "$HOME/Desktop/PCRT.command"
  echo -e "      ${V}OK${N} De ahora en mas: doble clic en PCRT del Escritorio."
else
  echo "      No pude escribir en el Escritorio. Para arrancarlo: abri la carpeta pcrt"
  echo "      (en tu carpeta de usuario) y hace doble clic en PCRT.command."
fi

echo -e "\n${V}Instalacion lista. Arrancando por primera vez...${N}\n"
exec bash "$CARPETA/PCRT.command" < /dev/tty
