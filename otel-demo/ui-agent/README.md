# ui-agent: usage tracking for Java desktop apps

One jar, no dependencies, runs on Java 6 through 21. It records which windows, tabs, and controls
a Swing or JavaFX app's users touch, JVM health, and the app's own log lines, and sends them to the
otel-poc Collector in the same shape the browser script sends, so the same OpenObserve dashboards
cover web and desktop apps. The app's source and jars are not changed.

## Point it at the cluster

The proxy listens for OTLP on port `4318` (`proxy.otlpPort` in `values.yaml`). From a workstation:

```
UI_AGENT_ENDPOINT=http://<proxy IP or host>:4318
UI_AGENT_APP=<app name, must match the catalog>     # default: the main class or jar name
UI_AGENT_VERSION=<app version>                       # default: Implementation-Version from the jar
UI_AGENT_ENV=<lab, ship, site name>                  # default: lab
```

Every setting is also a system property (`-Dui.agent.endpoint=...`) or an agent argument
(`-javaagent:ui-agent.jar=endpoint=...,app=...`). Arguments win over the settings file (below),
which wins over properties, which win over environment variables.

## Several apps on one machine

Each app gets its own agent, loaded into its own JVM. One settings file covers all of them, and
one command attaches to every app that is running. Copy `apps.conf` from `out/ui-agent/`, set the
endpoint, and write one section per app:

```
endpoint = http://10.0.0.5:4318
env = ship-3

[sonar-console]
match = sonar-console.jar
version = 4.2

[plot-editor]
match = com.example.plot.Main
title_trim = \s+-\s.*$|\d+
```

Lines above the first section apply to every app. The name in brackets is the app name in
OpenObserve. `match` is text from the app's line in `java -jar ui-agent.jar list` (part of its jar
path or its main class); the first section that matches wins.

```
/opt/java64/bin/java -jar /opt/ui-agent/ui-agent.jar attach-all /opt/ui-agent/apps.conf
```

```
attached  2114  sonar-console  (Java 1.8.0_504)
attached  2120  sonar-console  (Java 1.8.0_504)
already   2377  plot-editor  (ui-agent 0.2.0 is running in it)
skipped   2581  org.apache.catalina.startup.Bootstrap start  (no section in apps.conf matches)
2 attached, 1 already attached, 1 skipped, 0 run by another user, 0 failed. ...
```

It is safe to run again: apps that already have the agent are left alone, so run it after any app
restarts, or from cron. JVMs that match no section are not touched. The exit code is 0 when every
matching app has the agent, 1 otherwise.

A JVM only takes an agent from the user that started it. A normal user sees only their own apps.
Root sees everyone's and prints an `other` line with the command to run as that user:

```
other     96  sonar-console  (runs as opsuser; run: sudo -u opsuser java -jar /opt/ui-agent/ui-agent.jar attach-all /opt/ui-agent/apps.conf)
```

The file has to be readable by each of those users, because the app's JVM reads it too. The rules
for which `java` can run the command are the same as for `attach` below.

Two copies of the same app report under the same app name. Every row also carries
`service_instance_id` (`host:pid:start time`), `process_pid`, and `os_user`, and the "Copies of
each app" panel on the Java app health dashboard lists them.

The same file works without attach: `-javaagent:/opt/ui-agent/ui-agent.jar=config=/opt/ui-agent/apps.conf`
picks the section that matches the JVM it is loaded into. `attach <pid>` with its own arguments
still works for a single app.

## Three ways to load it

Pick whichever the app's launch allows. All three give the same data; the differences are in
`docs/java-ui-findings.md`.

1. **Launch script.** Add `-javaagent:/opt/ui-agent/ui-agent.jar` to the `java` command.

   ```
   java -javaagent:/opt/ui-agent/ui-agent.jar -jar sonar-console.jar
   ```

2. **Environment only.** Set `JAVA_TOOL_OPTIONS` where the app's JVM will see it (the user's
   profile, the service unit, the desktop launcher). The launch command stays as it is. The JVM
   prints `Picked up JAVA_TOOL_OPTIONS: ...` once at start; that is normal.

   ```
   export JAVA_TOOL_OPTIONS=-javaagent:/opt/ui-agent/ui-agent.jar
   ```

   Every JVM that user starts loads the agent. Apps that don't open a window send only JVM health
   and logs.

3. **Already running.** Attach to the process. The app keeps the Java it already runs on. The
   attach command is a second, short-lived program. It has to run as the same user as the app, on
   the same machine. Attach cannot reach a process on another computer.

   On a Java 8 runtime (Linux x64), copy all three files from `out/ui-agent/` into one folder
   and use the `java` that is already there:

   ```
   /opt/ui-agent/ui-agent.jar
   /opt/ui-agent/tools.jar        # attach classes, from the Zulu 8 JDK
   /opt/ui-agent/libattach.so     # attach native library, from the same JDK

   /opt/java64/bin/java -jar /opt/ui-agent/ui-agent.jar list      # pid and main class of each JVM
   /opt/java64/bin/java -jar /opt/ui-agent/ui-agent.jar attach <pid> app=sonar-console,endpoint=http://10.0.0.5:4318
   ```

   A Java 8 JDK needs only `ui-agent.jar`. On Java 9 and newer the command needs a JDK of the
   app's version (the `jdk.attach` module), and a Java 6 or 7 runtime cannot load the Java 8
   `tools.jar`; run the command there with any Java 8 runtime or JDK by its full path. Without
   the attach classes it stops with `No attach API: put tools.jar and libattach.so from a JDK 8
   next to ...`.

   To turn it off without restarting the app:

   ```
   /opt/java64/bin/java -jar /opt/ui-agent/ui-agent.jar stop <pid>
   ```

   It sends what is queued, removes its hooks, and ends its threads; the app's console shows
   `[ui-agent] ui-agent stopped`. The code stays loaded until the app restarts (a JVM cannot
   unload an agent), and attaching again starts it fresh. `stop` works the same on an agent
   loaded with `-javaagent`.

   Nothing before the attach is recorded. Windows already open are picked up; clicks and log
   lines from before it are not.

## What it sends

| Stream | What | Matches |
|---|---|---|
| traces `default` | one span per click, change (check box, combo, slider, spinner), submit (Enter in a text field): `event_type`, `page_path`, `target_text`, `target_element`, `target_xpath`, `rum_session_id`. The span lasts as long as the app's handler took | browser spans |
| logs `ui_views` | one row per window or tab (`type = view`), per stretch the operator was actually at the console (`type = presence`, `view_time_spent`), per control that exists (`type = inventory`), per click, change, or submit (`type = action`, `event_type`, `duration_ms`), per uncaught exception (`type = error`, naming the control when its handler threw), and one per agent start (`type = start`). Inventory, action, and error rows share `target_text`, `target_element`, and `target_page` | `_rumdata` for views |
| logs `java_logs` | java.util.logging, log4j 1 and 2, logback, stdout and stderr lines (`kind`, `logger`, `severity`), and event-thread freezes (`kind = freeze`). A freeze names the control when a click or key just before it armed one (`after [Label] JButton` in the message, `target_text` on the row) | |
| metrics `jvm_*`, `ui_event_thread_*`, `process_*` | heap and pool use, GC counts and time, threads, classes, CPU, event-thread latency and freezes, every 10 s | |

`page_path` is `/Window title/Selected tab`, or `/Owner title/Dialog title` for dialogs.
`target_text` is the button, menu item, or tab label, else the tooltip, accessible name, component
name, or the label just before the control. Under the default `UI_AGENT_PRIVACY=mask-values`
nothing a user typed and no table cell text is ever recorded; `allow` records the selected row's
first cell for tables and lists, like the browser script records row text.

`target_page` is where a control lives, and it does not change with what is on screen: the tab
that contains it, `/Window title` for menus and toolbars, the dialog for a dialog's controls.
`page_path` on the same row is the screen that was showing at the time. The "never used" list
compares inventory rows with action rows on `target_page`, `target_text`, and `target_element`.

A control is listed once it has been built inside a window that is showing. Tabs that have not
been opened are included. A dialog's controls are listed the first time anyone opens that dialog,
so a dialog nobody has ever opened shows as a never used menu item or button, not as its contents.
Tables, lists, trees, and custom panels are not in the list; they show up once someone clicks
them. The list is sent again every hour so it stays inside the dashboard's time range.

`duration_ms` is the time the event thread spent in the control's handler. Work the handler hands
to a background thread is not in it, and neither is the time a dialog it opened stayed up.

## Other settings

| Setting | Default | |
|---|---|---|
| `UI_AGENT_PRIVACY` | `mask-values` | `allow` records selected row text |
| `UI_AGENT_DEBUG` | `false` | prints every event to stderr, prefixed `[ui-agent]` |
| `UI_AGENT_HEALTH_SECONDS` | `10` | metrics interval |
| `UI_AGENT_FREEZE_MS` | `500` | event thread blocked longer than this is a freeze |
| `UI_AGENT_IDLE_SECONDS` | `180` | no mouse, key, or focus for this long ends a presence stretch. The window can stay open |
| `UI_AGENT_STDOUT` | `true` | tee System.out and System.err into `java_logs` |
| `UI_AGENT_TITLE_TRIM` | none | a regular expression; whatever it matches in a window, dialog, or tab title becomes `*`. `\d+` turns `Track 0042` into `Track *`, so titles with a number or file name count as one screen. A pattern with a comma has to go in the settings file or the environment, not in agent arguments |
| `UI_AGENT_INVENTORY_MINUTES` | `60` | how often the list of controls is sent again |
| `config` (argument only) | none | path to a settings file, see "Several apps on one machine" |

## Next to the official OpenTelemetry Java agent

Both can be loaded on the same command line (`-javaagent:ui-agent.jar -javaagent:opentelemetry-javaagent.jar`).
Tested with 2.33.1: both start, nothing is lost. The OTel agent traces ui-agent's own export
requests; the otel-poc collector drops those spans (`filter/self_export`), so no extra config is
needed on the workstation.

## Check it

With `UI_AGENT_DEBUG=true` (`debug = true` in the settings file), stderr shows
`[ui-agent] ui-agent 0.2.0 (premain) ...` at start, one `[ui-agent] inventory /Title/Tab [Label] JButton`
line per control it found, `[ui-agent] view /Title/Tab (initial_load)` when the first window opens,
and one `[ui-agent] click /Title/Tab [Label] JButton 12 ms` line per interaction.
`POST /v1/... failed` lines mean the endpoint is wrong or unreachable; events queue up and go out
when it comes back.

In OpenObserve: Traces, stream `default`, `service_name = <app>`; Logs, `ui_views` and
`java_logs`; Metrics, `jvm_memory_used`. Logs, `ui_views`, `type = 'start'` has one row per agent
start with the app name, user, and process id, which is the quickest check that every app on a
machine is reporting.
