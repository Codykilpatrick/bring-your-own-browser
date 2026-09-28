#!/usr/bin/env bash
# Highside: launch GPU Chromium as a normal desktop window.
# Usage: ./run-chromium.sh [URL] [extra chromium flags...]   (default URL: chrome://gpu)
set -euo pipefail
URL=${1:-chrome://gpu}; shift || true
PROFILE=${CHROMIUM_PROFILE:-$HOME/.local/share/chromium-gpu}   # profile + Downloads persist here
mkdir -p "$PROFILE"

xhost +SI:localuser:"$(whoami)" >/dev/null

exec podman run --rm \
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
