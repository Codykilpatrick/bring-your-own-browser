# Shared by the click lists. Source after setting APP (sonar-console or fire-control).
# The app writes /tmp/$APP-bounds.sh every 5 s with "name=\"x y\"" screen centres of its controls.
export DISPLAY=${DISPLAY:-:99}
PAUSE=${PAUSE:-1.2}

bounds() {
  for i in $(seq 1 90); do
    [ -f /tmp/$APP-bounds.sh ] && { . /tmp/$APP-bounds.sh; return 0; }
    sleep 1
  done
  echo "no bounds file from $APP after 90 s" >&2
  exit 1
}

# The app rewrites the file every second; call after a tab change so hidden controls get positions.
rebounds() { sleep 2; . /tmp/$APP-bounds.sh; }
# click "x y" [button]
click() { [ -n "$1" ] || { echo "click: no coordinates" >&2; exit 1; }; xdotool mousemove $1 click ${2:-1}; sleep $PAUSE; }
# click_at "x y" dx dy [button]: click offset from a top-left corner
click_at() { [ -n "$1" ] || { echo "click_at: no coordinates" >&2; exit 1; }
  set -- $1 $2 $3 ${4:-1}; xdotool mousemove $(( $1 + $3 )) $(( $2 + $4 )) click $5; sleep $PAUSE; }
key() { xdotool key --clearmodifiers "$@"; sleep $PAUSE; }
type_text() { xdotool type --delay 80 "$1"; sleep 0.5; }
# Activate the window whose title matches the regex and click inside it at window-relative x y.
win_click() { local id; id=$(xdotool search --name "$1" | tail -1); [ -n "$id" ] || { echo "no window $1" >&2; return 1; }
  xdotool windowactivate --sync "$id" 2>/dev/null; sleep 0.5; xdotool mousemove --window "$id" $2 $3 click 1; sleep $PAUSE; }
# Bring the main window back: activate, focus, and click its status bar (a plain panel, not counted).
focus_main() { local id; id=$(xdotool search --name "$1" | head -1)
  xdotool windowactivate --sync "$id" 2>/dev/null; xdotool windowfocus --sync "$id" 2>/dev/null; sleep 0.5
  xdotool mousemove --window "$id" 300 735 click 1; sleep 1; }
step() { echo "== $*"; }
