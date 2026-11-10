package poc.uiagent;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns UI events into the same records poc.js sends from browsers, so OpenObserve columns and the
 * dashboards line up: interaction spans (event_type, page.path, target.*, rum.session_id), view and
 * error rows with _rumdata names, log rows, and gauge/sum metrics.
 */
public final class Telemetry {
    private static final SecureRandom RND = new SecureRandom();
    /** Controls remembered for the resend. Titles that keep changing with no title_trim would otherwise grow this without end. */
    private static final int INVENTORY_MAX = 5000;
    public final Config cfg;
    public final String sessionId = hex(16);
    public final Exporter exporter;
    private final Map<String, String> viewIds = new HashMap<String, String>();
    private final Map<String, Long> viewStarts = new HashMap<String, Long>();
    private final Map<String, String> viewTypes = new HashMap<String, String>();
    /** Every control seen in this process: key to {page, label, element}. */
    private final Map<String, String[]> inventorySeen = new LinkedHashMap<String, String[]>();
    private final List<Action> open = new ArrayList<Action>();
    private long lastInput, presentSince, armedAt, failedAt, lastInventory = System.currentTimeMillis();
    private boolean focused;
    private String armedText = "", armedElement = "", armedPath = "", armedHome = "";
    private Map<String, Object> failed;
    /** Set by Agent.stop. Listeners left on controls still call in; they are dropped here. */
    private volatile boolean stopped;

    public Telemetry(Config cfg) {
        this.cfg = cfg;
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        String pid = pid();
        Map<String, Object> res = Json.map();
        res.put("service.name", cfg.app);
        res.put("service.version", cfg.version);
        // One app can run several times on one machine; this tells the copies apart.
        res.put("service.instance.id", hostName() + ":" + pid + ":" + rt.getStartTime() / 1000);
        res.put("deployment.environment.name", cfg.env);
        res.put("telemetry.sdk.name", "poc.ui-agent");
        res.put("telemetry.sdk.language", "java");
        res.put("telemetry.sdk.version", Agent.VERSION);
        res.put("process.runtime.name", System.getProperty("java.runtime.name"));
        res.put("process.runtime.version", System.getProperty("java.runtime.version"));
        res.put("process.pid", pid);
        res.put("host.name", hostName());
        res.put("os.user", System.getProperty("user.name"));
        this.exporter = new Exporter(cfg, Json.attrs(res));
        exporter.beforeShutdown = new Runnable() {
            public void run() {
                finishOpen();
                synchronized (Telemetry.this) { endPresence(System.currentTimeMillis()); }
            }
        };
    }

    /** "1234" from the JVM's "1234@host" name. */
    static String pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name == null ? -1 : name.indexOf('@');
        return at > 0 ? name.substring(0, at) : "0";
    }

    /** Stops taking new records and closes the open view. The exporter sends what is queued. */
    public void stop() {
        stopped = true;
        finishOpen();
        endAllViews();
    }

    /**
     * One row per agent start. It carries every column the dashboards read, empty, so a panel does not
     * fail on a site where nobody has clicked or hit an exception yet: OpenObserve refuses a query that
     * names a column the stream has never seen.
     */
    public void started(String how) {
        Map<String, Object> a = rumCommon("");
        a.put("type", "start");
        a.put("load_mode", how);
        a.put("view_id", "");
        a.put("view_time_spent", Long.valueOf(0));
        a.put("view_loading_type", "");
        a.put("event_type", "");
        a.put("target.text", "");
        a.put("target.element", "");
        a.put("target.page", "");
        a.put("duration_ms", Long.valueOf(0));
        a.put("error_message", "");
        a.put("error_stack", "");
        a.put("error_source", "");
        exporter.log(logRecord(9, "INFO", "ui-agent started via " + how, a));
    }

    /** Mouse, key, or a window coming forward. Throttled; the health tick turns this into presence time. */
    public void touch() {
        if (stopped) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastInput < 1000) return;
            lastInput = now;
        }
    }

    /** A page window is in front. Losing that ends the current presence stretch. */
    public void focus(boolean on) {
        if (stopped) return;
        synchronized (this) {
            focused = on;
            if (on) lastInput = System.currentTimeMillis();
            else endPresence(System.currentTimeMillis());
        }
    }

    /** Called once a second from the health thread. */
    public void presenceTick(long now) {
        synchronized (this) {
            if (here(focused, now, lastInput, cfg.idleMillis)) {
                if (presentSince == 0) presentSince = now;
            } else {
                endPresence(now);
            }
        }
    }

    private void endPresence(long now) {
        if (presentSince == 0) return;
        long spent = now - presentSince;
        presentSince = 0;
        if (spent < 1000) return;
        String path = Views.currentPath();
        Map<String, Object> a = rumCommon(path);
        a.put("type", "presence");
        a.put("view_id", hex(16));
        a.put("view_time_spent", Long.valueOf(spent * 1000000L));
        a.put("view_loading_type", "active");
        exporter.log(logRecord(9, "INFO", "present " + (spent / 1000) + "s " + path, a));
        Agent.debug("present " + (spent / 1000) + "s " + path);
    }

    /**
     * The control a user event is about to run, recorded before the app's own handler. A handler that
     * blocks the event thread runs before our click listener, so the freeze has to use this.
     * A later listener for the same control must not move the timestamp forward.
     * home is where the control lives (see SwingHook.homeOf); path is the page showing now.
     */
    public void arm(String text, String element, String path, String home) {
        if (stopped) return;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (armedAt != 0 && now - armedAt < cfg.freezeMillis + 1500
                    && eq(text, armedText) && eq(element, armedElement)) return;
            armedAt = now;
            armedText = text == null ? "" : text;
            armedElement = element == null ? "" : element;
            armedPath = path == null ? "" : path;
            armedHome = home == null ? "" : home;
        }
    }

    /** The armed control if it was handled around the time this ping went out, else null. */
    public Map<String, Object> hungControl(long sentAt) {
        synchronized (this) {
            if (!blame(sentAt, armedAt, cfg.freezeMillis)) return null;
            Map<String, Object> a = armedControl();
            a.put("page.path", armedPath);
            return a;
        }
    }

    private Map<String, Object> armedControl() {
        Map<String, Object> a = Json.map();
        a.put("target.text", armedText);
        a.put("target.element", armedElement);
        a.put("target.page", armedHome);
        return a;
    }

    /**
     * The app's handler threw during a user event that an action was recorded for, so the armed control
     * is that action's. The uncaught-exception row that follows names it.
     */
    public void threw() {
        synchronized (this) {
            if (armedAt == 0) return;
            failed = armedControl();
            failedAt = System.currentTimeMillis();
        }
    }

    /** The control whose handler just threw, else an action of this toolkit still running its handler, else null. */
    private Map<String, Object> failedControl(String source) {
        if ("swing".equals(source)) {
            synchronized (this) {
                Map<String, Object> f = failed;
                failed = null;
                if (f != null && System.currentTimeMillis() - failedAt < 2000) return f;
            }
        }
        synchronized (open) {
            for (int i = open.size() - 1; i >= 0; i--) {
                Action a = open.get(i);
                if (!a.toolkit.equals(source)) continue;
                Map<String, Object> f = Json.map();
                f.put("target.text", a.text);
                f.put("target.element", a.element);
                f.put("target.page", a.home);
                return f;
            }
        }
        return null;
    }

    /**
     * A control that exists, by where it lives, its label, and its type. Sent once, then again with every
     * other control seen so far each inventory_minutes: a dashboard looking at the last day has to list
     * the controls of an app that has been up for a month.
     * ponytail: two controls with the same label on one page collapse. Add the xpath to the key if that shows up.
     */
    public void inventory(String home, String label, String element) {
        if (stopped) return;
        String[] row = { home == null ? "" : home, label == null ? "" : label, element == null ? "" : element };
        String key = row[0] + "\0" + row[2] + "\0" + row[1];
        synchronized (inventorySeen) {
            if (inventorySeen.containsKey(key) || inventorySeen.size() >= INVENTORY_MAX) return;
            inventorySeen.put(key, row);
        }
        inventoryRow(row);
        Agent.debug("inventory " + row[0] + " [" + row[1] + "] " + row[2]);
    }

    private void inventoryRow(String[] row) {
        Map<String, Object> a = rumCommon(row[0]);
        a.put("type", "inventory");
        a.put("target.text", row[1]);
        a.put("target.element", row[2]);
        a.put("target.page", row[0]);
        exporter.log(logRecord(9, "INFO", "inventory " + row[0] + " [" + row[1] + "] " + row[2], a));
    }

    /** Called once a second from the health thread. */
    public void inventoryTick(long now) {
        if (stopped || now - lastInventory < cfg.inventoryMillis) return;
        lastInventory = now;
        List<String[]> rows;
        synchronized (inventorySeen) { rows = new ArrayList<String[]>(inventorySeen.values()); }
        for (String[] row : rows) inventoryRow(row);
        Agent.debug("inventory resent, " + rows.size() + " controls");
    }

    static boolean here(boolean focused, long now, long lastInput, long idleMs) {
        return focused && lastInput > 0 && now - lastInput < idleMs;
    }

    /** Click just before the ping, or just after it was queued and then blocked the thread. */
    static boolean blame(long sentAt, long armedAt, long freezeMs) {
        if (armedAt == 0) return false;
        return sentAt - armedAt <= freezeMs + 1500 && armedAt - sentAt <= 1500;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null || b.length() == 0 : a.equals(b);
    }

    /** Run with `java -jar ui-agent.jar self-check`. */
    static void selfCheck() {
        if (!here(true, 10000, 9000, 3000)) throw new AssertionError("focused and recent");
        if (here(true, 10000, 1000, 3000)) throw new AssertionError("idle");
        if (here(false, 10000, 9000, 3000)) throw new AssertionError("blurred");
        if (!blame(1000, 1200, 500)) throw new AssertionError("click after ping queued");
        if (!blame(2000, 1500, 500)) throw new AssertionError("click just before ping");
        if (blame(10000, 1000, 500)) throw new AssertionError("old click");
        if (millis(0, 2600000) != 3) throw new AssertionError("rounds to the nearest ms");
        if (millis(5000, 1000) != 0) throw new AssertionError("never negative");
    }

    static String hostName() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "unknown"; }
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return s.toString();
    }

    static long nowNanos() { return System.currentTimeMillis() * 1000000L; }

    static long millis(long startNanos, long endNanos) {
        return Math.max(0, endNanos - startNanos + 500000L) / 1000000L;
    }

    // ---- interactions -------------------------------------------------------------------------

    /**
     * One click, change, or submit. The caller holds it until the app's own handler has returned and
     * then calls finish, so the record carries how long the app took to answer.
     */
    public final class Action {
        final String eventType, toolkit, pagePath, home, text, element, xpath, id, name;
        private long startNanos = System.nanoTime(), startWall = nowNanos();
        private boolean done;

        Action(String eventType, String toolkit, String pagePath, String home, String text, String element, String xpath, String id, String name) {
            this.eventType = eventType;
            this.toolkit = toolkit;
            this.pagePath = pagePath == null ? "" : pagePath;
            this.home = home == null ? this.pagePath : home;
            this.text = text == null ? "" : text;
            this.element = element == null ? "" : element;
            this.xpath = xpath == null ? "" : xpath;
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        /** The user event began at this System.nanoTime(). The app's listener may have run before ours did. */
        void startedAt(long nanoTime) {
            if (nanoTime >= startNanos) return;
            startWall -= startNanos - nanoTime;
            startNanos = nanoTime;
        }

        /** Sends the span and the action row. end is a System.nanoTime(). Safe to call twice. */
        public void finish(long end) {
            synchronized (open) {
                if (done) return;
                done = true;
                open.remove(this);
            }
            long ms = millis(startNanos, end);
            Map<String, Object> a = Json.map();
            a.put("event_type", eventType);
            a.put("ui.toolkit", toolkit);
            a.put("page.path", pagePath);
            a.put("target.text", text);
            a.put("target.element", element);
            a.put("target.page", home);
            a.put("target.xpath", xpath);
            a.put("target.id", id);
            a.put("target.name", name);
            a.put("rum.session_id", sessionId);
            a.put("url.full", pagePath);
            StringBuilder b = new StringBuilder(512);
            b.append("{\"traceId\":\"").append(hex(16)).append("\",\"spanId\":\"").append(hex(8)).append("\",\"name\":").append(Json.str(eventType))
             .append(",\"kind\":1,\"startTimeUnixNano\":\"").append(startWall).append("\",\"endTimeUnixNano\":\"").append(startWall + Math.max(ms, 1) * 1000000L)
             .append("\",\"attributes\":").append(Json.attrs(a)).append(",\"status\":{}}");
            exporter.span(b.toString());
            // The same event as a row next to the inventory rows. OpenObserve cannot join traces to logs,
            // and "exists but never used" needs both in one stream.
            Map<String, Object> r = rumCommon(pagePath);
            r.put("type", "action");
            r.put("event_type", eventType);
            r.put("target.text", text);
            r.put("target.element", element);
            r.put("target.page", home);
            r.put("duration_ms", Long.valueOf(ms));
            exporter.log(logRecord(9, "INFO", eventType + " " + home + " [" + text + "] " + element, r, startWall));
            exporter.flush();
            Agent.debug(eventType + " " + pagePath + " [" + text + "] " + element + " " + ms + " ms");
        }
    }

    /**
     * Starts an action for a click, change, or submit; null once the agent is stopped. home is the
     * page the control lives on, which is not always the page showing (a menu item lives on the window).
     */
    public Action interaction(String eventType, String toolkit, String pagePath, String home, String text, String element, String xpath, String id, String name) {
        if (stopped) return null;
        Action a = new Action(eventType, toolkit, pagePath, home, text, element, xpath, id, name);
        synchronized (open) { open.add(a); }
        arm(a.text, a.element, a.pagePath, a.home);
        return a;
    }

    /** At stop and at JVM exit. A click on Exit never returns from its handler and would otherwise be lost. */
    private void finishOpen() {
        List<Action> left;
        synchronized (open) { left = new ArrayList<Action>(open); }
        long now = System.nanoTime();
        for (Action a : left) a.finish(now);
    }

    // ---- views and errors (same column names as OpenObserve RUM, stream ui_views) -------------

    /** Starts a view for a window or tab key; ends whichever view that key had before. */
    public synchronized void viewStart(String key, String path, String loadingType) {
        if (stopped) return;
        viewEnd(key);
        String id = hex(16);
        viewIds.put(key, id);
        viewStarts.put(key, Long.valueOf(System.currentTimeMillis()));
        viewTypes.put(key, path);
        viewRow(id, path, 0L, loadingType);
        Agent.debug("view " + path + " (" + loadingType + ")");
    }

    public synchronized void viewEnd(String key) {
        String id = viewIds.remove(key);
        if (id == null) return;
        long spent = System.currentTimeMillis() - viewStarts.remove(key).longValue();
        viewRow(id, viewTypes.remove(key), spent * 1000000L, "update");
    }

    public synchronized String currentView(String key) { return viewTypes.get(key); }

    public synchronized void endAllViews() {
        for (String k : new java.util.ArrayList<String>(viewIds.keySet())) viewEnd(k);
    }

    private void viewRow(String viewId, String path, long spentNanos, String loadingType) {
        Map<String, Object> a = rumCommon(path);
        a.put("type", "view");
        a.put("view_id", viewId);
        a.put("view_time_spent", Long.valueOf(spentNanos));
        a.put("view_loading_type", loadingType);
        exporter.log(logRecord(9, "INFO", "view " + path, a));
    }

    /** An uncaught exception. source is "swing", "javafx", or "thread:name". Names the control when its handler threw. */
    public void error(String message, String stack, String source, String path) {
        if (stopped) return;
        Map<String, Object> a = rumCommon(path);
        a.put("type", "error");
        a.put("error_message", message);
        a.put("error_stack", stack);
        a.put("error_source", source);
        Map<String, Object> control = failedControl(source);
        if (control != null) {
            a.putAll(control);
            Agent.debug("error after [" + control.get("target.text") + "] " + control.get("target.element"));
        }
        exporter.log(logRecord(17, "ERROR", message, a));
        exporter.flush();
    }

    private Map<String, Object> rumCommon(String path) {
        Map<String, Object> a = Json.map();
        a.put("service", cfg.app);
        a.put("application_id", cfg.app);
        a.put("version", cfg.version);
        a.put("env", cfg.env);
        a.put("session_id", sessionId);
        a.put("view_url", path == null ? "" : path);
        a.put("source", "ui-agent");
        return a;
    }

    // ---- application logs (stream java_logs) --------------------------------------------------

    public void log(String severity, String logger, String thread, String body, String kind, String stack) {
        log(severity, logger, thread, body, kind, stack, null);
    }

    public void log(String severity, String logger, String thread, String body, String kind, String stack, Map<String, Object> extra) {
        if (stopped) return;
        Map<String, Object> a = Json.map();
        a.put("logger", logger);
        a.put("thread", thread);
        a.put("kind", kind);
        a.put("session_id", sessionId);
        if (stack != null) a.put("exception.stacktrace", stack);
        if (extra != null) a.putAll(extra);
        exporter.log(logRecord(severityNumber(severity), severity, body, a));
    }

    static int severityNumber(String s) {
        s = s == null ? "" : s.toUpperCase();
        if (s.startsWith("TRACE") || s.equals("FINEST") || s.equals("FINER")) return 1;
        if (s.startsWith("DEBUG") || s.equals("FINE") || s.equals("CONFIG")) return 5;
        if (s.startsWith("WARN")) return 13;
        if (s.startsWith("ERROR") || s.equals("SEVERE")) return 17;
        if (s.startsWith("FATAL")) return 21;
        return 9;
    }

    private String logRecord(int sevNum, String sevText, String body, Map<String, Object> attrs) {
        return logRecord(sevNum, sevText, body, attrs, nowNanos());
    }

    private String logRecord(int sevNum, String sevText, String body, Map<String, Object> attrs, long atNanos) {
        StringBuilder b = new StringBuilder(512);
        b.append("{\"timeUnixNano\":\"").append(atNanos).append("\",\"observedTimeUnixNano\":\"").append(nowNanos())
         .append("\",\"severityNumber\":").append(sevNum).append(",\"severityText\":").append(Json.str(sevText))
         .append(",\"body\":{\"stringValue\":").append(Json.str(body == null ? "" : body)).append("},\"attributes\":").append(Json.attrs(attrs)).append('}');
        return b.toString();
    }

    // ---- metrics ------------------------------------------------------------------------------

    public void gauge(String name, String unit, double value, Map<String, Object> attrs) {
        exporter.metric(metric(name, unit, "gauge", value, false, attrs));
    }

    /** Monotonic cumulative counter (the JVM already keeps the running total). */
    public void counter(String name, String unit, double value, Map<String, Object> attrs) {
        exporter.metric(metric(name, unit, "sum", value, true, attrs));
    }

    private final long startNanos = nowNanos();

    private String metric(String name, String unit, String kind, double value, boolean sum, Map<String, Object> attrs) {
        long now = nowNanos();
        StringBuilder b = new StringBuilder(256);
        b.append("{\"name\":").append(Json.str(name)).append(",\"unit\":").append(Json.str(unit)).append(",\"").append(kind).append("\":{");
        if (sum) b.append("\"aggregationTemporality\":2,\"isMonotonic\":true,");
        b.append("\"dataPoints\":[{\"startTimeUnixNano\":\"").append(startNanos).append("\",\"timeUnixNano\":\"").append(now).append("\",");
        if (value == Math.rint(value) && Math.abs(value) < 9e15) b.append("\"asInt\":\"").append((long) value).append('"');
        else b.append("\"asDouble\":").append(value);
        b.append(",\"attributes\":").append(Json.attrs(attrs == null ? Json.map() : attrs)).append("}]}}");
        return b.toString();
    }
}
