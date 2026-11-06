#!/bin/sh
# Click list for Sonar Console. Mirrors docs/demo-walkthrough.md: some features used, others left
# alone on purpose so "never used" rows show up (Print Summary, Exit, Drop Contact, Clear History,
# Tools > Recompute Bearings, Calibration Stop).
#
# Tabs and menus are opened with the mouse (positions come from the app's bounds file). Alt+mnemonic
# needs keyboard focus inside the frame, which Java 6 on X11 loses after a dialog closes.
set -e
APP=sonar-console
. "$(dirname "$0")/lib.sh"
bounds
focus_main '^Sonar Console$'

step "Narrowband: pick a contact, open details"
click "$tab_narrowband"; rebounds
click_at "$contacts_tl" 60 30                      # first row
click_at "$contacts_tl" 60 30
key alt+d                                          # Details...
win_click '^Contact Details$' 150 170              # Close
focus_main '^Sonar Console$'

step "Broadband: waterfall, toolbar, receiver controls"
click "$tab_broadband"; rebounds
click "$waterfall"
click_at "$waterfall_tl" 120 200
click "$zoom_in"
click "$zoom_in"
click "$zoom_out"
click "$mark"
click "$auto_track"
click "$band"; key Down; key Return                # combo: change
click_at "$gain_tl" 100 12                         # slider: change
set -- $averaging_wh; click_at "$averaging_tl" $(( $1 - 8 )) 5   # spinner up arrow: change
click "$bearing"; type_text "045"; key Return      # text field: submit
click "$recompute"                                 # blocks the event thread for 3 s
sleep 5

step "Menus: export, calibrate, self test, about"
# Open the menu with the mouse, then the item's mnemonic letter.
click "$menu_file"; key e; sleep 1; key Return     # Export Contacts... then OK
focus_main '^Sonar Console$'
click "$menu_tools"; key c; rebounds               # Calibrate... (AWT frame)
click "$cal_loopback"
click "$cal_start"
focus_main '^Sonar Console$'
click "$menu_tools"; key s; sleep 1                # Self Test: uncaught exception on the event thread
click "$menu_help"; key a; sleep 1; key Return     # About, OK
focus_main '^Sonar Console$'

step "History tab, then back"
click "$tab_history"
sleep 2
click "$tab_broadband"
echo "sonar-console scenario done"
