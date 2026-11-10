# Monitoring Java desktop apps without touching them: findings

What we learned building `ui-agent` and running it against two test apps on five JDKs in three
attach modes. Read this before picking a rollout approach for the real Swing and JavaFX apps.

## What was tested

Two apps written to look like the real ones, with the kinds of controls legacy UIs have:

- **Sonar Console**: Swing, compiled for Java 6. Menus with mnemonics, icon-only toolbar
  buttons (some with tooltips, some without), tabs, a custom-painted panel with its own mouse
  handling, a table, slider, spinner, combo, text field, modal dialogs, `JOptionPane`s, and an
  old AWT `Frame` with AWT `Button`/`Checkbox`. Logs with log4j 1.2. Leaks 100 KB/s on purpose.
  Has a button that blocks the event thread for 3 s and a menu item that throws on the event
  thread.
- **Fire Control**: JavaFX 8 API. Same shape (menus, graphic-only buttons, tabs, table with a
  context menu, list, check box, combo, slider, spinner, text field), one extra `Stage` per
  engagement, an `Alert`, and a Swing `JDialog` opened from JavaFX. Logs with
  `java.util.logging`. Same 3 s block and uncaught exception.

Each run was one container (Xvfb, fluxbox, noVNC) driven by an `xdotool` click list that used
about two thirds of the controls and deliberately skipped the rest. The apps and the click rig
have been removed from the repo. The table below is the record of those runs.

| Run (`deployment_environment_name`) | App | JDK | How the agent loaded |
|---|---|---|---|
| `jdk7-a` | Sonar Console | Zulu 7 | A: `-javaagent` on the command line |
| `jdk6-b` | Sonar Console | Zulu 6 | B: `JAVA_TOOL_OPTIONS=-javaagent:...`, launch untouched |
| `jdk8-c` | Sonar Console | Zulu 8 | C: attached 15 s after launch |
| `jdk8fx-b` | Fire Control | Zulu 8 with bundled JavaFX | B |
| `jdk17fx-c` | Fire Control | Liberica 17 + OpenJFX 17 modules | C |
| `jdk8fx-otel` | Fire Control | Zulu 8 FX | A, with the official OpenTelemetry Java agent 2.33.1 loaded next to it |

## Results

Every run passed all eleven checks in `verify.sh` (66 of 66). Per run: click, change, and
submit spans with `page_path` and `target_text`; window and tab views in `ui_views`; the uncaught
exception as a `type = error` row; application log lines, stdout lines, and a freeze report with
the event thread's stack in `java_logs`; `jvm_memory_used` samples and a non-zero
`ui_event_thread_freezes` counter.

What the same click list produced, every JDK and mode alike:

- Pages: `/Sonar Console/Broadband`, `/Sonar Console/Narrowband`, `/Sonar Console/History`,
  `/Sonar Console/Contact Details`, `/Sonar Console/Export`, `/Sonar Console/About`,
  `/Calibration`; `/Fire Control/Tracks`, `/Fire Control/Engagements`, `/Fire Control/Weapons`,
  `/Fire Control/Engagement N`, `/Settings`, `/About`.
- Controls: every menu item used, the toolbar buttons that have a tooltip (`Mark contact`,
  `Designate track`), tab headers, table rows (`contacts`, `tracks`), check boxes and toggles
  as `change`, combo, slider, and spinner as `change` labelled from the `JLabel`/`Label` before
  them (`Band`, `Gain`, `Averaging`, `Mode`, `Range (km)`, `Salvo`), Enter in a text field as
  `submit` (`Bearing`, `Range override`), AWT `Button`/`Checkbox` in the calibration frame, the
  Swing dialog inside the JavaFX app, and the `OK` of every `JOptionPane`/`Alert`.
- Clicks on the custom-painted waterfall panel arrive as clicks with an empty label and
  `target_element = Waterfall`. Same for the two zoom buttons, which have neither text nor
  tooltip. They count toward the app's interactions but cannot be rows in the catalog.
- The 3 s block showed up as a `freeze` row naming `Thread.sleep` under the app's handler, and
  as `ui_event_thread_latency_max` between 2.2 and 2.8 s (the pinger samples once a second, so
  it sees most of a 3 s block, not all of it). The deliberate exception landed as an error
  row with the full stack on all six runs, including Java 6 where the default uncaught handler
  does not cover the event thread (`sun.awt.exception.handler` is set instead).
- Heap climbed from about 25 MB to 150 to 180 MB over the 14 minutes the Sonar Console runs were
  up, and to 280 MB on Fire Control: the planted 100 KB/s leak is visible in the "Heap used" panel.

With the official OpenTelemetry Java agent loaded alongside, both agents started, the app ran,
and every ui-agent check passed. The OTel agent added its own JVM metrics (`jvm_cpu_time`,
`jvm_gc_duration`, ...) and traced ui-agent's export POSTs, one `POST` span per batch. The
collector now drops client spans whose URL ends in `/v1/traces|logs|metrics`
(`filter/self_export` in `collector-config.yaml`), so those never reach OpenObserve.

## What each attach mode costs

**A. `-javaagent` in the launch script.** Hooks are in place before the first window opens, so
the first view is `initial_load` and nothing is missed. Needs one edit to whatever starts the
app (script, `.desktop` file, service unit). If a launcher is shared by several apps, set
`UI_AGENT_APP` per app or every one reports under its jar name.

**B. `JAVA_TOOL_OPTIONS` in the environment.** Same coverage as A with no change to the launch
command. Every JVM that user starts picks it up, including `javac`, build tools, and helper
JVMs; those report JVM health under their main class name until `UI_AGENT_APP` is set or they
exit. The JVM prints `Picked up JAVA_TOOL_OPTIONS: ...` to stderr once; some wrapper scripts
treat any stderr output as failure. On Java 6 and 7 this worked unchanged.

**C. Attach to the running process.** No change to launch or environment. The app keeps its own
Java. The attach command is a second program and needs the attach API (`tools.jar` plus
`libattach.so` on 6 to 8, the `jdk.attach` module on 9+), the same user as the app, and the same
machine. On a Java 8 runtime, the `tools.jar` and `libattach.so` shipped next to `ui-agent.jar`
are enough; the install card in `ui-agent/README.md` has the commands. Someone or something still has to run
`java -jar ui-agent.jar attach <pid>` after every app start.
Windows already open are picked up by a scan on attach (the first view is a `route_change` with
no `initial_load`), but clicks before the attach are gone. Log lines before the attach are gone
too; the exporter only sees what is logged after the hooks go in.

Ranking for the real apps: **A and B first, pick by what the launch allows**, C only where
neither is possible or as a one-off on a machine that is already running. A is the safest when
the app has its own launch script. B is the right one when the launcher cannot be edited (vendor
installer, shortcut owned by someone else) and the user's environment can be. C is a fallback,
not a plan.

## Misses and caveats

- **Unlabelled controls** (icon buttons with no tooltip, custom panels) are recorded as clicks
  with an empty `target_text`. They show in interaction counts and in "Labelled controls used
  that are not in the catalog" only if they have a label. Ask the app owners to add tooltips,
  or accept that those buttons are counted as clicks on the page but not named.
- **Table and list rows** record the component name or id (`contacts`, `tracks`) under the
  default `privacy=mask-values`. Row text is never sent. `privacy=allow` sends the selected
  row's first cell, like the browser script does for a clicked row.
- **Dialogs are pages.** A `JOptionPane` or `Alert` becomes `/Owner title/Dialog title` (or
  `/Dialog title` when it has no owner). Catalog them as pages; their `OK` buttons are controls.
- **Menu items count on whichever tab is showing.** `Tools > Self Test` on the Broadband tab is
  `/Sonar Console/Broadband` + `Self Test`. Catalog menu items with a pattern that matches every
  tab (`^/Sonar Console/[^/]+$`).
- **Late attach misses the start.** Mode C only; see above.
- **Java 6 on X11** throws a `NullPointerException` from `XWindowPeer.restoreTransientFor` when
  a modal dialog closes while another frame is open. It is a JDK bug, not ours, but the agent
  reports it as an uncaught exception. Expect noise like this from old JDKs in the "Uncaught
  exceptions" panel; group by message.
- **JavaFX menus differ between 8 and 17.** Alt+mnemonic opens the menu bar's first menu on
  FX 17, and an open FX 17 menu starts with its first item focused where FX 8 starts with none.
  The agent records whatever actually fired; this only affected the test driver, which now
  opens menus with the mouse and walks items with `Up` from the bottom.
- **No replay.** The Java sessions appear in the low-use panels with a session id, but the
  "Open replay" link leads nowhere because there is no recording. Replay stays browser-only.
- **The `$app` dropdown on the three usage dashboards lists apps from `nginx_access`**, so Java
  apps are only included when the dropdown is on All. The Java app health dashboard has its own
  dropdown fed from `java_logs`.
- **The dashboards need both view streams to exist.** OpenObserve fails a query when a stream in
  a `UNION ALL` is missing, so on a site with no Java agent reporting yet, generate the
  dashboards with `python3 tools/make_dashboards.py --web-only` until the first agent row lands,
  then regenerate and re-import.
- **Logging frameworks**: `java.util.logging`, log4j 1.2, log4j 2, and logback are hooked by
  reflection and polled for a short while after start in case the app configures them late.
  Anything else is caught only if it prints to stdout or stderr (the tee), and then with the
  severity guessed from the stream (`INFO` for stdout, `ERROR` for stderr).
- **Bootstrap**: the agent jar is loaded by the system class loader, not the boot class path,
  so it cannot see app classes that live in custom class loaders unless they come through the
  AWT or JavaFX event path, which all UI events do.

## 0.2.0: unused controls, slow controls, several apps on one machine

Tested with the same two apps and five runs (Java 6, 7, 8 Swing; JavaFX on 8 and 17; premain,
`JAVA_TOOL_OPTIONS`, and attach), plus one container running three copies of Sonar Console, one
Fire Control, and one JVM that matched nothing.

- **Never used, without a catalog.** The agent lists every control it finds and the "Java feature
  use" dashboard subtracts the ones that were clicked. The scenarios leave fourteen labelled
  controls alone on purpose (Print Summary, Exit, Drop Contact, Clear History, Abort All,
  Hold All, ...). The "Never used" panel returned those fourteen and nothing else. An app
  attached and left alone listed all of its main-window controls, including the ones on tabs
  nobody had opened.
- **Controls with no label cannot be told apart.** Fire Control's unused icon button and Sonar
  Console's three used ones show in "Controls with no label" as one row per screen and kind.
  A tooltip in the app is what gives each its own row.
- **A dialog's controls appear when the dialog is first opened.** Until then the dialog is only
  visible as the menu item or button that opens it. If that item shows as never used, so is
  everything behind it.
- **Slow controls.** Both apps have a button that blocks the event thread for 3 s. "Slowest
  controls" showed them at 3008 and 3018 ms typical, counted as froze in every use, and nothing
  else above 150 ms. The time is the event thread's time in the handler. A menu item that opens
  a dialog is timed until the dialog appears, not until it closes.
- **Exceptions name the control.** `Self Test` (both apps) and a toolbar button patched to throw
  appear in "Controls that threw" with the control, its screen, and the message. The Java 6 X11
  `NullPointerException` above is filed under the dialog's `OK` button, which is where it
  happens.
- **Changing titles.** Fire Control opens `Engagement 1`, `Engagement 2`, ... With
  `title_trim = \d+` they are one screen, `/Fire Control/Engagement *`.
- **`attach-all`.** First run: three `attached`, one `skipped`. Second run: three `already`. After
  `stop` on one, only that one is attached again. The three copies of one app are separate rows
  in "Copies of each app" (`service_instance_id`, pid, user).
- **Other users.** A JVM takes an agent only from the user that started it. Run as root, a
  plain attach to another user's Java 8 app waited 10 s, failed, and made the app print a full
  thread dump to its stdout. `attach-all` now checks the owner first and prints the `sudo -u`
  command instead. Run as that user, it attached.
- **Not tested:** attach on a Java 9+ app without a matching JDK on the machine (the README
  rule still applies), SELinux or container boundaries between the command and the app, and
  apps started by a service manager with a private `/tmp` (attach needs the app's `/tmp`).

## Rollout sketch

1. Ship `out/ui-agent/ui-agent.jar` and `out/ui-agent/README.md` (the install card) with the
   chart. The proxy already listens on `proxy.otlpPort` (4318) and forwards `/v1/*` to the
   collector.
2. Per app, decide A or B, set `UI_AGENT_ENDPOINT=http://<proxy IP>:4318`, `UI_AGENT_APP`,
   `UI_AGENT_ENV`. Leave `UI_AGENT_PRIVACY` at `mask-values`.
3. Run the app once with `UI_AGENT_DEBUG=true` and watch stderr: `view /Title/Tab`, one
   `click`/`change`/`submit` line per interaction, no `POST ... failed`.
4. Add the app's pages and controls to `feature_catalog.csv` using the paths and labels the debug
   output shows, run `tools/check_catalog.py`, reload the enrichment table, re-import the
   dashboards.
