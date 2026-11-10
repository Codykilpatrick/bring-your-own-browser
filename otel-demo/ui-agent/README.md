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
(`-javaagent:ui-agent.jar=endpoint=...,app=...`). Arguments win over properties, which win over
environment variables.

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
| traces `default` | one span per click, change (check box, combo, slider, spinner), submit (Enter in a text field): `event_type`, `page_path`, `target_text`, `target_element`, `target_xpath`, `rum_session_id` | browser spans |
| logs `ui_views` | one row per window or tab (`type = view`), per stretch the operator was actually at the console (`type = presence`, `view_time_spent`), per control that exists (`type = inventory`, `target_text`, `target_element`), and per uncaught exception (`type = error`) | `_rumdata` for views |
| logs `java_logs` | java.util.logging, log4j 1 and 2, logback, stdout and stderr lines (`kind`, `logger`, `severity`), and event-thread freezes (`kind = freeze`). A freeze names the control when a click or key just before it armed one (`after [Label] JButton` in the message, `target_text` on the row) | |
| metrics `jvm_*`, `ui_event_thread_*`, `process_*` | heap and pool use, GC counts and time, threads, classes, CPU, event-thread latency and freezes, every 10 s | |

`page_path` is `/Window title/Selected tab`, or `/Owner title/Dialog title` for dialogs.
`target_text` is the button, menu item, or tab label, else the tooltip, accessible name, component
name, or the label just before the control. Under the default `UI_AGENT_PRIVACY=mask-values`
nothing a user typed and no table cell text is ever recorded; `allow` records the selected row's
first cell for tables and lists, like the browser script records row text.

## Other settings

| Setting | Default | |
|---|---|---|
| `UI_AGENT_PRIVACY` | `mask-values` | `allow` records selected row text |
| `UI_AGENT_DEBUG` | `false` | prints every event to stderr, prefixed `[ui-agent]` |
| `UI_AGENT_HEALTH_SECONDS` | `10` | metrics interval |
| `UI_AGENT_FREEZE_MS` | `500` | event thread blocked longer than this is a freeze |
| `UI_AGENT_IDLE_SECONDS` | `180` | no mouse, key, or focus for this long ends a presence stretch. The window can stay open |
| `UI_AGENT_STDOUT` | `true` | tee System.out and System.err into `java_logs` |

## Next to the official OpenTelemetry Java agent

Both can be loaded on the same command line (`-javaagent:ui-agent.jar -javaagent:opentelemetry-javaagent.jar`).
Tested with 2.33.1: both start, nothing is lost. The OTel agent traces ui-agent's own export
requests; the otel-poc collector drops those spans (`filter/self_export`), so no extra config is
needed on the workstation.

## Check it

With `UI_AGENT_DEBUG=true`, stderr shows `[ui-agent] ui-agent 0.1.0 (premain) ...` at start,
`[ui-agent] view /Title/Tab (initial_load)` when the first window opens, and one
`[ui-agent] click /Title/Tab [Label] JButton` line per interaction. `POST /v1/... failed` lines mean
the endpoint is wrong or unreachable; events queue up and go out when it comes back.

In OpenObserve: Traces, stream `default`, `service_name = <app>`; Logs, `ui_views` and
`java_logs`; Metrics, `jvm_memory_used`.
