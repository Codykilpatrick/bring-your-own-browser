#!/bin/sh
# Start Xvfb, fluxbox, x11vnc, and noVNC, then run the given command in that display.
set -e
Xvfb "$DISPLAY" -screen 0 1280x900x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
for i in $(seq 1 50); do
  xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 && break
  sleep 0.2
done
fluxbox >/tmp/fluxbox.log 2>&1 &
x11vnc -display "$DISPLAY" -forever -shared -nopw -quiet -bg -rfbport 5900 -o /tmp/x11vnc.log
websockify --web /usr/share/novnc 6080 localhost:5900 >/tmp/websockify.log 2>&1 &
exec "$@"
