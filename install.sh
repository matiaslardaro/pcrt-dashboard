#!/data/data/com.termux/files/usr/bin/bash
# install.sh — instalador de un solo comando para el dashboard PCRT
# Uso (pegar en Termux recien instalado):
#   curl -sL https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/install.sh | bash

set -e

REPO_URL="https://github.com/matiaslardaro/pcrt-dashboard.git"
CARPETA="$HOME/pcrt"

echo "=========================================="
echo " PCRT - Instalador del dashboard"
echo "=========================================="

echo ""
echo "[1/6] Actualizando paquetes de Termux..."
export DEBIAN_FRONTEND=noninteractive
pkg update -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" < /dev/null
pkg upgrade -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" < /dev/null

echo "[2/6] Instalando python y git..."
pkg install -y python git < /dev/null >/dev/null 2>&1

echo "[3/6] Pidiendo permiso de almacenamiento..."
termux-setup-storage || true
sleep 2

echo "[4/6] Bajando el proyecto PCRT..."
if [ -d "$CARPETA" ]; then
  echo "  Ya existe $CARPETA, actualizando con git pull..."
  cd "$CARPETA"
  git pull
else
  git clone "$REPO_URL" "$CARPETA"
  cd "$CARPETA"
fi

echo "[5/6] Instalando librerias de Python (gt-telem, websockets)..."
pip install gt-telem websockets < /dev/null

echo "[6/6] Dejando listo el widget..."
mkdir -p "$HOME/.shortcuts"
cp "$CARPETA/PCRT.sh" "$HOME/.shortcuts/PCRT.sh"
chmod +x "$HOME/.shortcuts/PCRT.sh"

echo ""
echo "=========================================="
echo " Listo! Ahora falta un solo paso a mano:"
echo ""
echo " 1. Instalar la app 'Termux:Widget' (F-Droid)"
echo "    si todavia no la tenes"
echo " 2. Mantener presionado en la pantalla de"
echo "    inicio > Widgets > Termux:Widget"
echo " 3. Arrastrar 'PCRT.sh' a la pantalla"
echo ""
echo " De ahi en mas: un toque y arranca solo."
echo "=========================================="
