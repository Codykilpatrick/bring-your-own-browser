#!/bin/sh
# Click list for Fire Control. Left alone on purpose: Abort All, Exit, Weapons > Safe, Hold All,
# the drop button, Drop track, the Auto toggle, Hold and Abort in an engagement.
#
# Menus are opened with the mouse (positions come from the app's bounds file). Inside an open
# JavaFX menu, FX 8 starts with no item focused and FX 17 with the first one, so items are reached
# with Up from the bottom: Up once is the last item on both. Alt+mnemonic is also not reliable on
# FX 17 (Alt alone focuses the first menu).
set -e
APP=fire-control
. "$(dirname "$0")/lib.sh"
bounds
focus_main '^Fire Control$'

step "Tracks: select, designate, context menu"
click "$tab_tracks"; rebounds
click_at "$tracks_tl" 60 40                        # first row
click "$designate"                                 # graphic-only button with a tooltip
click_at "$tracks_tl" 60 64 3                      # right-click second row
sleep 1; key Up; key Up; key Return                # context menu (Designate, Drop track): Designate

step "Engagements: engage, fire"
click "$tab_engagements"; rebounds
click "$engage"; rebounds
click "$fire"
focus_main '^Fire Control$'
key ctrl+n; sleep 2                                # New Engagement... by accelerator

step "Weapons: controls, compute (blocks the FX thread)"
focus_main '^Fire Control$'
click "$tab_weapons"; rebounds
click "$ready"
click "$mode"; key Down; key Return
click_at "$range_tl" 200 8
set -- $salvo_wh; click_at "$salvo_tl" $(( $1 - 10 )) 8
click "$override"; type_text "25"; key Return
click "$compute"
sleep 5

step "Menus: arm, self test, settings (Swing dialog), about"
click "$menu_weapons"; key Up; key Up; key Up; key Return      # Weapons (Arm, Safe, Self Test): Arm
click "$menu_weapons"; key Up; key Return; sleep 1             # Weapons: Self Test, uncaught exception
click "$menu_view"; key Up; key Return; sleep 3                # View (Settings...): Swing JDialog
win_click '^Settings$' 180 22                      # Verbose logging checkbox
win_click '^Settings$' 150 95                      # OK
focus_main '^Fire Control$'
click "$menu_help"; key Up; key Return; sleep 1; key Return    # Help (About), then OK
focus_main '^Fire Control$'
click "$tab_tracks"
echo "fire-control scenario done"
