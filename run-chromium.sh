#!/usr/bin/env bash
# Highside: launch GPU Chromium as a normal desktop window.
# Usage: ./run-chromium.sh [URL] [extra chromium flags...]   (default URL: chrome://gpu)
set -euo pipefail
URL=${1:-chrome://gpu}; shift || true
PROFILE=${CHROMIUM_PROFILE:-$HOME/.local/share/chromium-gpu}   # profile + Downloads persist here
mkdir -p "$PROFILE"

xhost +SI:localuser:"$(whoami)" >/dev/null

# Share the host D-Bus so Chromium stops logging "Failed to connect to the bus".
BUS=/run/user/$(id -u)/bus
DBUS=()
[[ -S $BUS ]] && DBUS+=(-v "$BUS:$BUS" -e DBUS_SESSION_BUS_ADDRESS="unix:path=$BUS")
[[ -S /run/dbus/system_bus_socket ]] && DBUS+=(-v /run/dbus/system_bus_socket:/run/dbus/system_bus_socket)

exec podman run --rm \
  "${DBUS[@]}" \
  --device nvidia.com/gpu=all \
  --security-opt label=disable \
  --userns keep-id \
  -e DISPLAY="$DISPLAY" \
  -e HOME=/home/chrome \
  -v "$PROFILE":/home/chrome \
  -v /tmp/.X11-unix:/tmp/.X11-unix:ro \
  --shm-size 2g \
  localhost/chromium-gpu:latest \
  chromium-browser --no-sandbox --ignore-gpu-blocklist "$@" "$URL"
